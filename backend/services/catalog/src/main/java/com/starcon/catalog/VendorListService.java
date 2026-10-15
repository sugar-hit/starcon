package com.starcon.catalog;

import static com.starcon.catalog.VendorListModels.*;
import static com.starcon.catalog.VendorListRules.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.1.1.1 供应商列表：<b>采购视角</b>的供应商清单、四个关键指标与采购侧运营动作。
 *
 * <p><b>本类最重要的一条纪律：不建第二份供应商主数据。</b> 蓝图那句「供应商数据主数据在 03，本模块呈现采购视角的视图与运营动作，避免同一实体两处可编辑」
 * 是硬要求，落实方式只有一句：<b>编码 / 名称 / 联系方式 / 合作等级 / 主体状态 全部只读</b>，
 * 本服务一条 UPDATE 都不发给 {@code party} / {@code party_role} / {@code party_supplier_profile}。
 * 逐字段的所有者对照表与理由见 {@code V137__vendor_supply_perspective.sql} 第一节。
 *
 * <p><b>四个关键指标没有一个是存下来的字段</b>，全部是本类 SQL 里的聚合（口径见 V137 第二节）：
 *
 * <ul>
 *   <li><b>M1 供货 SKU 数</b> = {@code count(distinct sku_id)} over {@code supplier_quote where status='ACTIVE'}（3.6.2.1 的表，只读）。
 *   <li><b>M2 采购金额累计</b> = {@code sum(amount) group by currency} over {@code vendor_delivery_record}。<b>跨币种不相加</b>（规则 R4）——
 *       本库没有汇率表，100 USD + 90 EUR = 190 是一个看起来对、实际错的数字，而且错得极难被发现。
 *   <li><b>M3 准时交付率</b> = {@code count(received_date <= due_date) / count(*)}。<b>不存 on_time 布尔列</b>，准时与否由两个日期事实推出。
 *   <li><b>M4 质量合格率</b> = {@code sum(qualified_qty) / sum(inspected_qty)}，按<b>数量</b>比而不是批次比。
 * </ul>
 *
 * <p><b>M3 / M4 的分母为 0 时返回 null 而不是 0</b>（规则 R3）：「还没交货过」与「交货全部延期」是<b>相反</b>的两件事。
 * 折叠成 0 会让一个刚建档、正在试单的 TRIAL 供应商在列表上看起来是最差的那个。
 *
 * <p><b>指标窗口</b>：M3 / M4 是有时间窗的统计。窗口由 {@link VendorListRules#metricWindow} 定出并<b>回显在响应里</b> ——
 * 一个不说清是哪一段的「准时率 96%」在供应商评审里没有意义。窗口同时约束两处：
 * 到货比 {@code received_date}（date 列 → LocalDate），质检比 {@code inspected_at}（timestamptz 列 → OffsetDateTime，values 位置写
 * {@code ?::timestamptz}；用 {@code Timestamp.valueOf(LocalDateTime)} 绑会静默偏 −28800s）。
 *
 * <p><b>权限口径</b>：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}，与 3.6.1.1 / 3.6.1.2 完全一致 ——
 * 供应商档案是商品主数据域里往来单位档案的下半部分，不新造权限词表（那要改 identity 服务的迁移，属于另一个服务的写入所有权）。
 *
 * <p><b>租户</b>：一律来自已认证成员关系（{@link AccessService} 向 identity 解析），<b>不读 {@code X-Tenant-Id} 头</b>。
 * 每条 SQL 的 where 都带显式 {@code tenant_id=?} 绑定，而被 join 的 party 三张表还有各自的 FORCE RLS —— 两道闸，任何一道单独失效都不放行跨租户。
 */
@Service
public class VendorListService {
  /**
   * 每条查询都带的那一段：SUPPLIER 角色 + 未删除主体。
   *
   * <p>与筛选条件<b>分开</b>且放在最前面：租户与「这是不是一个供应商」是安全边界，
   * 放进可变的 where 拼装里早晚会被一次重构挪到别处，而那种挪动不报错，只是隔离口径悄悄变了一条。
   *
   * <p><b>刻意不过滤 BLACKLISTED / INACTIVE</b>：拉黑的供应商必须在采购列表里看得见（运营要看到「为什么不能下单」），
   * 停用的也一样（历史采购单与对账单还要引用它）。是否只看某一种主体状态交给 {@code partyStatus} 筛选参数。
   */
  private static final String ALWAYS_WHERE =
      " r.tenant_id=? and r.role_type='SUPPLIER' and p.deleted_at is null";

  /** SELECT 列表（只读字段段；指标段在 {@link #metrics()} 里追加）。 */
  private static final String SELECT_LIST =
      "select p.id as party_id,p.code as party_code,p.name as party_name,p.party_type,"
          + "p.country_code,p.email,p.phone,p.website,p.status as party_status,p.status_reason,"
          + "r.id as role_id,r.status as role_status,"
          + "psp.cooperation_level,psp.payment_terms_days,psp.settlement_method,"
          + "(psp.role_id is not null) as supplier_profile_found,"
          + "coalesce(vsp.supply_status,'PENDING_REVIEW') as supply_status,"
          + "vsp.category_id,pc.name as category_name,vsp.note as supply_note,"
          + "(vsp.role_id is not null) as supply_profile_found,vsp.version as supply_profile_version";

  /** 主体 + 角色 + 两张档案 + 类目字典（计数查询与取行查询共用，保证两者的集合口径不可能分叉）。 */
  private static final String BASE_FROM =
      " from party_role r"
          + " join party p on p.tenant_id=r.tenant_id and p.id=r.party_id"
          + " left join vendor_supply_profile vsp on vsp.tenant_id=r.tenant_id and vsp.role_id=r.id"
          + " left join party_supplier_profile psp on psp.tenant_id=r.tenant_id and psp.role_id=r.id"
          + " left join product_category pc on pc.tenant_id=vsp.tenant_id and pc.id=vsp.category_id";

  /**
   * 四个指标的 LATERAL 聚合。
   *
   * <p><b>用 LATERAL 而不是把这些子查询 join 进主表</b>：join 出去会让一个供应商被复制成 N 行
   * （三条到货记录 = 三行），于是「总数」与「翻页翻到底」立刻对不上，而 COUNT 查询并不会跟着一起膨胀 —— 那是最难查的一类不一致。
   *
   * <p>金额 LATERAL 是三段的：{@link #AMOUNT_LATERAL_HEAD} 之后由调用方补上可选的币种条件，再接 {@link #AMOUNT_LATERAL_TAIL}。
   */
  private static final String SKU_LATERAL =
      " left join lateral ("
          + "   select count(distinct sq.sku_id) as sku_count"
          + "   from supplier_quote sq"
          + "   where sq.tenant_id=r.tenant_id and sq.supplier_role_id=r.id and sq.status='ACTIVE'"
          + " ) q on true";

  private static final String DELIVERY_LATERAL =
      " left join lateral ("
          + "   select count(*) as delivery_count,"
          + "          count(*) filter (where vd.received_date <= vd.due_date) as on_time_count,"
          + "          max(vd.received_date)::text as last_delivery_at"
          + "   from vendor_delivery_record vd"
          + "   where vd.tenant_id=r.tenant_id and vd.role_id=r.id and not vd.is_void"
          + "     and vd.received_date between ? and ?"
          + " ) dv on true";

  private static final String QUALITY_LATERAL =
      " left join lateral ("
          + "   select sum(vq.inspected_qty) as inspected_qty,"
          + "          sum(vq.qualified_qty) as qualified_qty,"
          + "          max(vq.inspected_at) as last_inspection_at"
          + "   from vendor_quality_record vq"
          + "   where vq.tenant_id=r.tenant_id and vq.role_id=r.id and not vq.is_void"
          + "     and vq.inspected_at >= ?::timestamptz and vq.inspected_at <= ?::timestamptz"
          + " ) qq on true";

  private static final String AMOUNT_LATERAL_HEAD =
      " left join lateral ("
          + "   select coalesce(max(t.amount),0) as purchase_amount,"
          + "          (array_agg(t.currency order by t.amount desc))[1] as purchase_amount_currency"
          + "   from ("
          + "     select vda.currency, sum(vda.amount) as amount"
          + "     from vendor_delivery_record vda"
          + "     where vda.tenant_id=r.tenant_id and vda.role_id=r.id and not vda.is_void"
          + "       and vda.received_date between ? and ?";

  private static final String AMOUNT_LATERAL_TAIL = " group by vda.currency) t) amt on true";

  /**
   * 指标别名段。
   *
   * <p>{@code sku_count} / {@code purchase_amount} / {@code on_time_rate} / {@code quality_rate} 同时是<b>ORDER BY 的别名</b> ——
   * PostgreSQL 的 ORDER BY 可以引用 SELECT 别名，所以 {@link VendorListRules#sortColumn} 能直接用这些名字排序，
   * 不必把四个聚合再写一遍（写两遍必然会有一次口径不一样，而那种不一致不报错）。
   */
  private static final String METRICS =
      " ,coalesce(q.sku_count,0) as sku_count"
          + " ,coalesce(dv.delivery_count,0) as delivery_count"
          + " ,coalesce(dv.on_time_count,0) as on_time_count"
          + " ,dv.last_delivery_at"
          + " ,qq.inspected_qty,qq.qualified_qty,qq.last_inspection_at"
          + " ,amt.purchase_amount,amt.purchase_amount_currency"
          // 两个比率别名：既供界面读，也供 ORDER BY 直接引用。
          // **除法前必须转 numeric**：on_time_count / delivery_count 在 PostgreSQL 里是两个 int 相除，
          // 得到的是整数除法（2/3 = 0），比率会直接算成 0 —— 而它看上去仍然像一个百分比。
          // 分母为 0 时给 null 而不是 0（规则 R3）。
          + " ,case when coalesce(dv.delivery_count,0)=0 then null"
          + "     else round(coalesce(dv.on_time_count,0)::numeric / dv.delivery_count, 4) end"
          + "  as on_time_rate"
          + " ,case when coalesce(qq.inspected_qty,0)=0 then null"
          + "     else round(qq.qualified_qty / qq.inspected_qty, 4) end"
          + "  as quality_rate";

  private final DSLContext sql;

  public VendorListService(DSLContext sql) {
    this.sql = sql;
  }

  /**
   * 指标窗口在 SQL 里占用的绑定位（顺序固定，改动必须同步 {@link #metricBinds}）。
   *
   * <p>顺序 = 三个 LATERAL 在 SQL 文本里出现的先后：到货窗口（date ×2）→ 质检窗口（timestamptz ×2）→ 金额窗口（date ×2）→ 可选币种。
   */
  private List<Object> metricBinds(LocalDate fromDate, LocalDate toDate, String currency) {
    var binds = new ArrayList<Object>();
    binds.add(fromDate);
    binds.add(toDate);
    binds.add(fromDate.atStartOfDay().atOffset(java.time.ZoneOffset.UTC));
    binds.add(toDate.plusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC));
    binds.add(fromDate);
    binds.add(toDate);
    if (currency != null) binds.add(currency);
    return binds;
  }

  /**
   * 指标窗口的实际生效区间（转成三处 SQL 各需要的形态）。
   *
   * <p><b>质检窗口的右端取 {@code toDate + 1 天的零点</b>：{@code inspected_at} 是 timestamptz，而窗口的 {@code to} 是一个时刻；
   * 若直接比 {@code <= to}，「到 2026-10-15 12:00」这个窗口会漏掉当天 12:00 之后发生的质检 ——
   * 而界面上显示的窗口是「到 10-15」，用户会以为当天都算进去了。
   */
  private record Window(LocalDate fromDate, LocalDate toDate, String from, String to) {}

  private Window resolveWindow(OffsetDateTime from, OffsetDateTime to) {
    var window = metricWindow(from, to, OffsetDateTime.now());
    return new Window(
        LocalDate.parse(window.from().substring(0, 10)),
        LocalDate.parse(window.to().substring(0, 10)),
        window.from(),
        window.to());
  }

  // ================================================================ 列表

  /**
   * 供应商列表：分页 + 关键词 / 合作状态 / 类目 / 合作等级 / 主体状态 / 「缺运营档案」筛选，
   * 每行带四个关键指标（口径见类注释）。
   *
   * <p><b>计数与取行引同一处筛选条件构造</b>（{@link #conditions}），而不是各拼一遍 —— 两处各拼一遍的症状是
   * 「页面说筛出 7 条，翻到第 2 页是空的」。
   */
  public VendorListPage list(VendorListQuery query) {
    String sortProblem = sortProblem(query.sort());
    CatalogService.check(sortProblem == null, sortProblem);
    String levelProblem = cooperationLevelProblem(query.cooperationLevel());
    CatalogService.check(levelProblem == null, levelProblem);
    String statusProblem = partyStatusProblem(query.partyStatus());
    CatalogService.check(statusProblem == null, statusProblem);
    String currencyProblem = currencyProblem(query.currency());
    CatalogService.check(currencyProblem == null, currencyProblem);

    Window window = resolveWindow(query.metricFrom(), query.metricTo());
    String currency = normalizeEnum(query.currency());

    List<String> where = new ArrayList<>();
    List<Object> conditionBinds = new ArrayList<>();
    conditions(query, where, conditionBinds);

    long total =
        sql.fetchOne(
                "select count(*)" + BASE_FROM + " where " + ALWAYS_WHERE + whereClause(where),
                concat(List.<Object>of(currentTenantId()), conditionBinds).toArray())
            .get(0, Long.class);

    int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
    int page = Math.max(query.page(), 0);
    boolean desc = query.desc() == null ? defaultDescFor(query.sort()) : query.desc();
    // 比率列的 null 一律沉到最后：窗口内没有记录的供应商是「还没有表现」，
    // 让它排在「准时率 5%」的前面，运营会以为那是最差的一家。
    String orderBy =
        " order by "
            + sortColumn(query.sort())
            + (desc ? " desc" : " asc")
            + (isRateSort(query.sort()) ? " nulls last" : "")
            + ", p.code, p.id";

    var binds = new ArrayList<Object>();
    binds.addAll(metricBinds(window.fromDate(), window.toDate(), currency));
    binds.add(currentTenantId());
    binds.addAll(conditionBinds);
    binds.add(size);
    binds.add((long) page * size);

    var rows =
        sql.fetch(
            SELECT_LIST
                + METRICS
                + BASE_FROM
                + SKU_LATERAL
                + DELIVERY_LATERAL
                + QUALITY_LATERAL
                + AMOUNT_LATERAL_HEAD
                + (currency == null ? "" : " and vda.currency=?")
                + AMOUNT_LATERAL_TAIL
                + " where "
                + ALWAYS_WHERE
                + whereClause(where)
                + orderBy
                + " limit ? offset ?",
            binds.toArray());

    var amounts = purchaseAmounts(rows, window.fromDate(), window.toDate(), currency);
    var items = new ArrayList<VendorRow>(rows.size());
    for (Record r : rows) {
      items.add(toRow(r, amounts.getOrDefault(r.get("role_id", UUID.class), List.of())));
    }
    return new VendorListPage(
        items,
        total,
        page,
        size,
        new VendorMetricWindow(window.from(), window.to()),
        LIST_NOTE);
  }

  private static final String LIST_NOTE =
      "编码 / 名称 / 联系方式 / 合作等级 / 主体状态**只读**自 3.6.1.1 与 3.6.1.2 —— 供应商主数据在 03，本页只呈现采购视角。"
          + "合作状态与主供类目是本模块自有的采购侧字段，与主体状态是两个不同的决定（暂停采购不需要停用主体）。"
          + "**还没建采购侧档案的供应商，合作状态按「待评估」呈现**（不是空白）：那正是它现在的实际状态，"
          + "空白会被读成「没填」而掩盖「该填」。"
          + "准时交付率与质量合格率按上面显示的窗口统计；**窗口内没有记录的供应商，比率是 null（显示「暂无数据」）而不是 0** ——"
          + "「还没交货过」与「交货全部延期」是相反的两件事。"
          + "采购金额按币种分组、不跨币种相加（本库没有汇率表，折算是 12 财务的资产）；排序取金额最大的那一笔。";

  /**
   * 逐供应商的<b>分币种</b>金额明细（列表展示用）。
   *
   * <p>单独一条查询而不是把明细塞进主查询：主查询里那个 {@code purchase_amount} 只是<b>代表值</b>（排序用），
   * 界面要展示的是「USD 12,000 / EUR 3,400」这样一份明细。一次 {@code in (...)} 取回整页，避免 N+1。
   */
  private Map<UUID, List<VendorPurchaseAmount>> purchaseAmounts(
      List<Record> rows, LocalDate from, LocalDate to, String currency) {
    var result = new HashMap<UUID, List<VendorPurchaseAmount>>();
    if (rows.isEmpty()) return result;
    var roleIds = rows.stream().map(r -> r.get("role_id", UUID.class)).distinct().toList();
    var placeholders = new StringBuilder();
    for (int i = 0; i < roleIds.size(); i++) placeholders.append(i == 0 ? "?" : ",?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenantId());
    binds.addAll(roleIds);
    binds.add(from);
    binds.add(to);
    String currencyFilter = "";
    if (currency != null) {
      currencyFilter = " and currency=?";
      binds.add(currency);
    }
    for (Record r :
        sql.fetch(
            "select role_id,currency,sum(amount) as amount"
                + " from vendor_delivery_record"
                + " where tenant_id=? and not is_void and role_id in ("
                + placeholders
                + ") and received_date between ? and ?"
                + currencyFilter
                + " group by role_id,currency order by role_id, sum(amount) desc",
            binds.toArray())) {
      result
          .computeIfAbsent(r.get("role_id", UUID.class), k -> new ArrayList<>())
          .add(
              new VendorPurchaseAmount(
                  r.get("currency", String.class), decimal(r.get("amount", BigDecimal.class))));
    }
    return result;
  }

  // ================================================================ 概览

  /**
   * 概览：供应商规模、采购侧状态分布、指标覆盖率与全租户口径的两个比率。
   *
   * <p><b>全租户口径的比率按记录数 / 数量加权，不是各家比率的平均</b>：一家只有 1 单且准时到货的供应商
   * 与一家 500 单的供应商各占一半，算出来的平均值与真实整体表现毫无关系。
   *
   * <p>{@code withDeliveryRecord} / {@code withQualityRecord} 是这两个比率的<b>可信度提示</b>：
   * 只有 3 家供应商有记录时，那个「全租户准时率」基本没有参考价值，界面上要能看出来。
   */
  public VendorListOverview overview() {
    Window window = resolveWindow(null, null);
    UUID tenant = currentTenantId();

    Record counts =
        sql.fetchOne(
            "select count(*) as supplier_count,"
                + " count(vsp.role_id) as profile_count,"
                + " count(*) filter (where vsp.supply_status='ACTIVE') as active_count,"
                + " count(*) filter (where vsp.supply_status='SUSPENDED') as suspended_count"
                + BASE_FROM
                + " where "
                + ALWAYS_WHERE,
            tenant);
    Record delivery =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where received_date <= due_date) as on_time,"
                + " count(distinct role_id) as suppliers"
                + " from vendor_delivery_record"
                + " where tenant_id=? and not is_void and received_date between ? and ?",
            tenant,
            window.fromDate(),
            window.toDate());
    Record quality =
        sql.fetchOne(
            "select sum(inspected_qty) as inspected, sum(qualified_qty) as qualified,"
                + " count(distinct role_id) as suppliers"
                + " from vendor_quality_record"
                + " where tenant_id=? and not is_void"
                + " and inspected_at >= ?::timestamptz and inspected_at <= ?::timestamptz",
            tenant,
            window.fromDate().atStartOfDay().atOffset(java.time.ZoneOffset.UTC),
            window.toDate().plusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC));

    long supplierCount = counts.get("supplier_count", Long.class);
    return new VendorListOverview(
        supplierCount,
        counts.get("profile_count", Long.class),
        supplierCount - counts.get("profile_count", Long.class),
        counts.get("active_count", Long.class),
        counts.get("suspended_count", Long.class),
        delivery.get("suppliers", Long.class),
        quality.get("suppliers", Long.class),
        onTimeRate(delivery.get("on_time", Long.class), delivery.get("total", Long.class)),
        qualityRate(
            decimal(quality.get("qualified", BigDecimal.class)),
            decimal(quality.get("inspected", BigDecimal.class))),
        new VendorMetricWindow(window.from(), window.to()),
        "概览只统计未删除主体的 SUPPLIER 身份。missingProfileCount 是**采购侧运营档案**的缺口"
            + "（合作状态与主供类目还没填），与 3.6.1.2 的供应商角色档案覆盖率是两件事。"
            + "两个全租户比率按记录数 / 数量加权，**不是各家比率的平均** —— 只有一家供应商有记录时它几乎等于那一家，"
            + "请结合 withDeliveryRecord / withQualityRecord 一起看。");
  }

  // ================================================================ 词表

  /**
   * 词表选项：前端下拉的数据源。
   *
   * <p><b>合作等级与主体状态是只读词表</b>（3.6.1.2 / 3.6.1.1 的资产），本模块只是把同一份取值给前端下拉，
   * 以兑现「枚举只增不改要在**一处**兑现」—— 前端与后端各维护一份迟早会不一样。本模块<b>不</b>提供写这两个字段的入口。
   *
   * <p>币种<b>刻意不给词表</b>：ISO 4217 完整词表是 12 财务的资产，在本模块硬编码一份就是复制别人的写入所有权。
   */
  public VendorListOptions options() {
    var categories =
        sql.fetch(
                "select pc.id,pc.code,pc.name from product_category pc"
                    + " join product_category_tree t on t.tenant_id=pc.tenant_id and t.id=pc.tree_id"
                    // 停用的树（enabled=false）与停用的类目（enabled=false）都不进下拉：
                    // 选一个停用类目作主供类目，06 采购拆单时才会发现它已经不在有效目录里。
                    + " where pc.tenant_id=? and t.enabled and pc.enabled"
                    + " order by pc.code,pc.name limit 500",
                currentTenantId())
            .map(
                r ->
                    new VendorCategoryOption(
                        r.get("id", UUID.class),
                        r.get("code", String.class),
                        r.get("name", String.class)));
    return new VendorListOptions(
        SUPPLY_STATUSES,
        COOPERATION_LEVELS,
        PARTY_STATUSES,
        categories,
        sorts(),
        "合作状态与主供类目是本模块可写的采购侧字段；**合作等级与主体状态只读**（写入所有权在 3.6.1.2 / 3.6.1.1）。"
            + "币种不给词表：ISO 4217 是 12 财务的资产，这里走自由输入 + 三位大写格式校验。");
  }

  // ================================================================ 采购侧运营动作（写）

  /**
   * 建 / 改采购侧运营档案。
   *
   * <p>入参里**没有 code / name / email / cooperationLevel** —— 不是遗漏，是刻意：本方法只写
   * {@code vendor_supply_profile} 三列，写入所有权在本模块（规则 R1、R2）。
   */
  public VendorFactSaved saveProfile(
      AccessService.Access actor, UUID roleId, VendorSupplyProfileInput input) {
    requireSupplierRole(roleId);
    String problem = supplyStatusProblem(input.supplyStatus());
    CatalogService.check(problem == null, problem);
    String status = input.supplyStatus().trim().toUpperCase(java.util.Locale.ROOT);
    if (input.categoryId() != null) requireCategory(input.categoryId());
    UUID tenant = currentTenantId();

    var existing =
        sql.fetchOne(
            "select version from vendor_supply_profile where tenant_id=? and role_id=?",
            tenant,
            roleId);
    if (existing == null && input.version() != 0)
      throw conflict("这条采购侧档案还不存在（version 应为 0），请刷新后重试");
    if (existing != null && existing.get(0, Long.class) != input.version())
      throw conflict(
          "这条采购侧档案已被他人改动（当前 version=" + existing.get(0, Long.class) + "），请刷新后重试");

    sql.execute(
        "insert into vendor_supply_profile(tenant_id,role_id,supply_status,category_id,note,version)"
            + " values(?,?,?,?,?,?)"
            + " on conflict (tenant_id,role_id) do update set"
            + " supply_status=excluded.supply_status,category_id=excluded.category_id,"
            + " note=excluded.note,version=vendor_supply_profile.version+1,updated_at=now()",
        tenant,
        roleId,
        status,
        input.categoryId(),
        note(input.note()),
        input.version());
    audit(actor, roleId, "VENDOR_SUPPLY_PROFILE");
    var saved =
        sql.fetchOne(
            "select version from vendor_supply_profile where tenant_id=? and role_id=?",
            tenant,
            roleId);
    return new VendorFactSaved(roleId, saved.get(0, Long.class));
  }

  /**
   * 追加一条到货记录。
   *
   * <p><b>只追加，没有 UPDATE</b>（规则 R5）：到货是发生过的事实，改单会让「这个供应商准时率里混进了什么」无法追溯。
   * 录错了用 {@link #voidDelivery} 作废并留理由。
   *
   * <p>{@code orderedAt} 是 {@code timestamptz}，values 位置显式 {@code ?::timestamptz}。
   */
  public VendorFactSaved recordDelivery(
      AccessService.Access actor, UUID roleId, VendorDeliveryInput input) {
    requireSupplierRole(roleId);
    String problem =
        deliveryProblem(input.quantity(), input.amount(), input.dueDate(), input.receivedDate());
    CatalogService.check(problem == null, problem);
    String currencyProblem = currencyProblem(input.currency());
    CatalogService.check(currencyProblem == null, currencyProblem);
    CatalogService.check(
        !input.orderedAt().toLocalDate().isAfter(input.receivedDate()),
        "下单日期不能晚于实际到货日：收货事实不可能早于下单");
    if (input.skuId() != null) requireSku(input.skuId());
    if (notBlank(input.purchaseUnitCode())) requireUnit(input.purchaseUnitCode().trim());

    UUID id = Ids.next();
    sql.execute(
        "insert into vendor_delivery_record(id,tenant_id,role_id,delivery_ref,sku_id,purchase_unit_code,"
            + "quantity,amount,currency,ordered_at,due_date,received_date,note)"
            + " values(?::uuid,?::uuid,?::uuid,?,?::uuid,?,?,?,?,?::timestamptz,?,?,?)",
        id,
        currentTenantId(),
        roleId,
        input.deliveryRef().trim(),
        input.skuId(),
        blankToNull(input.purchaseUnitCode()),
        input.quantity(),
        input.amount(),
        input.currency().trim().toUpperCase(java.util.Locale.ROOT),
        input.orderedAt(),
        input.dueDate(),
        input.receivedDate(),
        note(input.note()));
    audit(actor, roleId, "VENDOR_DELIVERY");
    return new VendorFactSaved(id, 0L);
  }

  /** 追加一条质检记录。 */
  public VendorFactSaved recordInspection(
      AccessService.Access actor, UUID roleId, VendorQualityInspectionInput input) {
    requireSupplierRole(roleId);
    String problem = qualityProblem(input.inspectedQuantity(), input.qualifiedQuantity());
    CatalogService.check(problem == null, problem);
    if (input.deliveryId() != null) {
      var found =
          sql.fetchOne(
              "select id from vendor_delivery_record where tenant_id=? and id=? and role_id=?",
              currentTenantId(),
              input.deliveryId(),
              roleId);
      CatalogService.check(found != null, "引用的到货记录不存在，或不属于这个供应商");
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into vendor_quality_record(id,tenant_id,role_id,delivery_id,inspection_ref,"
            + "inspected_qty,qualified_qty,inspected_at,note)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?::timestamptz,?)",
        id,
        currentTenantId(),
        roleId,
        input.deliveryId(),
        input.inspectionRef().trim(),
        input.inspectedQuantity(),
        input.qualifiedQuantity(),
        input.inspectedAt(),
        note(input.note()));
    audit(actor, roleId, "VENDOR_QUALITY");
    return new VendorFactSaved(id, 0L);
  }

  /** 作废一条到货记录（留理由，不物理删除，规则 R5）。 */
  public void voidDelivery(
      AccessService.Access actor, UUID roleId, UUID recordId, VendorVoidInput input) {
    voidFact(actor, "vendor_delivery_record", roleId, recordId, input.reason(), "VENDOR_DELIVERY_VOID");
  }

  /** 作废一条质检记录。 */
  public void voidInspection(
      AccessService.Access actor, UUID roleId, UUID recordId, VendorVoidInput input) {
    voidFact(actor, "vendor_quality_record", roleId, recordId, input.reason(), "VENDOR_QUALITY_VOID");
  }

  /**
   * 作废的共同实现。
   *
   * <p>{@code table} 是本文件里写死的两个字面量（{@code vendor_delivery_record} / {@code vendor_quality_record}），
   * 绝不接受外部输入 —— 它会被拼进 SQL。
   */
  private void voidFact(
      AccessService.Access actor, String table, UUID roleId, UUID recordId, String reason, String action) {
    CatalogService.check(notBlank(reason), "作废必须填理由：没有理由的作废会让「准时率突然变好」无法解释");
    int updated =
        sql.execute(
            "update " + table + " set is_void=true, void_reason=?"
                + " where tenant_id=? and id=? and role_id=? and not is_void",
            reason.trim(),
            currentTenantId(),
            recordId,
            roleId);
    CatalogService.check(updated == 1, "记录不存在、已作废，或不属于这个供应商");
    audit(actor, roleId, action);
  }

  // ================================================================ 采购视角详情（列表下钻）

  /**
   * 采购视角详情：列表行 + 最近到货 + 最近质检 + 警告。
   *
   * <p><b>刻意不叫「供应商详情」</b>：6.1.1.2 才是那个模块（基础信息 + 账期 + 报价 + 附件资质全景）。
   * 本方法只回答采购侧的一件事：这个供应商在我们这儿<b>现在是什么状态、表现如何、事实是什么</b>。
   * 两处各做一份「详情」的后果与 V137 第一节同源。
   */
  public VendorPurchaseView purchaseView(UUID roleId) {
    Window window = resolveWindow(null, null);
    var binds = new ArrayList<Object>();
    binds.addAll(metricBinds(window.fromDate(), window.toDate(), null));
    binds.add(currentTenantId());
    binds.add(roleId);

    var summary =
        sql.fetch(
                SELECT_LIST
                    + METRICS
                    + BASE_FROM
                    + SKU_LATERAL
                    + DELIVERY_LATERAL
                    + QUALITY_LATERAL
                    + AMOUNT_LATERAL_HEAD
                    + AMOUNT_LATERAL_TAIL
                    + " where "
                    + ALWAYS_WHERE
                    + " and r.id = ?",
                binds.toArray())
            .stream()
            .findFirst()
            .orElse(null);
    if (summary == null) throw CatalogService.missing();

    var deliveries =
        sql.fetch(
                "select d.id,d.delivery_ref,d.sku_id,s.code as sku_code,s.name as sku_name,"
                    + "d.purchase_unit_code,d.quantity,d.amount,d.currency,d.ordered_at,d.due_date,"
                    + "d.received_date,d.is_void,d.void_reason,d.note"
                    + " from vendor_delivery_record d"
                    + " left join sku s on s.tenant_id=d.tenant_id and s.id=d.sku_id"
                    + " where d.tenant_id=? and d.role_id=?"
                    + " order by d.received_date desc, d.id desc limit 100",
                currentTenantId(),
                roleId)
            .map(
                r -> {
                  var due = r.get("due_date", LocalDate.class);
                  var received = r.get("received_date", LocalDate.class);
                  return new VendorDeliveryRow(
                      r.get("id", UUID.class),
                      r.get("delivery_ref", String.class),
                      r.get("sku_id", UUID.class),
                      r.get("sku_code", String.class),
                      r.get("sku_name", String.class),
                      r.get("purchase_unit_code", String.class),
                      decimal(r.get("quantity", BigDecimal.class)),
                      decimal(r.get("amount", BigDecimal.class)),
                      r.get("currency", String.class),
                      instant(r.get("ordered_at", OffsetDateTime.class)),
                      due,
                      received,
                      VendorListRules.onTime(due, received),
                      daysLate(due, received),
                      Boolean.TRUE.equals(r.get("is_void", Boolean.class)),
                      r.get("void_reason", String.class),
                      r.get("note", String.class));
                });

    var inspections =
        sql.fetch(
                "select q.id,q.delivery_id,q.inspection_ref,q.inspected_qty,q.qualified_qty,"
                    + "q.inspected_at,q.is_void,q.void_reason,q.note"
                    + " from vendor_quality_record q"
                    + " where q.tenant_id=? and q.role_id=?"
                    + " order by q.inspected_at desc, q.id desc limit 100",
                currentTenantId(),
                roleId)
            .map(
                r ->
                    new VendorQualityInspectionRow(
                        r.get("id", UUID.class),
                        r.get("delivery_id", UUID.class),
                        r.get("inspection_ref", String.class),
                        decimal(r.get("inspected_qty", BigDecimal.class)),
                        decimal(r.get("qualified_qty", BigDecimal.class)),
                        qualityRate(
                            decimal(r.get("qualified_qty", BigDecimal.class)),
                            decimal(r.get("inspected_qty", BigDecimal.class))),
                        instant(r.get("inspected_at", OffsetDateTime.class)),
                        Boolean.TRUE.equals(r.get("is_void", Boolean.class)),
                        r.get("void_reason", String.class),
                        r.get("note", String.class)));

    var amounts =
        purchaseAmounts(List.of(summary), window.fromDate(), window.toDate(), null)
            .getOrDefault(roleId, List.of());
    var warnings = new ArrayList<String>();
    if (!Boolean.TRUE.equals(summary.get("supplier_profile_found", Boolean.class)))
      warnings.add("还没建供应商角色档案（3.6.1.2）：06 采购单取不到账期与结算方式的默认值。");
    if (!Boolean.TRUE.equals(summary.get("supply_profile_found", Boolean.class)))
      warnings.add("还没建采购侧运营档案：合作状态与主供类目为空，列表按「未建档」看待。");
    if (summary.get("cooperation_level", String.class) == null)
      warnings.add("合作等级为空：它在 3.6.1.2 维护，本模块只读，需要时请到往来单位页面补建。");
    return new VendorPurchaseView(
        toRow(summary, amounts),
        deliveries,
        inspections,
        warnings,
        "这是**采购视角**的详情（状态 / 表现 / 事实）。供应商全景（账期、报价、采购历史、附件资质）是 6.1.1.2 的范围，"
            + "刻意不在这里重复一份 —— 供应商主数据只有一份，写入所有权也只有一份。");
  }

  // ================================================================ 组装与筛选

  private VendorRow toRow(Record r, List<VendorPurchaseAmount> amounts) {
    Long deliveryCount = r.get("delivery_count", Long.class);
    Long onTimeCount = r.get("on_time_count", Long.class);
    BigDecimal inspected = decimal(r.get("inspected_qty", BigDecimal.class));
    BigDecimal qualified = decimal(r.get("qualified_qty", BigDecimal.class));
    return new VendorRow(
        r.get("role_id", UUID.class),
        r.get("party_id", UUID.class),
        r.get("party_code", String.class),
        r.get("party_name", String.class),
        r.get("party_type", String.class),
        r.get("country_code", String.class),
        r.get("email", String.class),
        r.get("phone", String.class),
        r.get("website", String.class),
        r.get("party_status", String.class),
        r.get("status_reason", String.class),
        r.get("cooperation_level", String.class),
        r.get("payment_terms_days", Integer.class),
        r.get("settlement_method", String.class),
        Boolean.TRUE.equals(r.get("supplier_profile_found", Boolean.class)),
        r.get("supply_status", String.class),
        r.get("category_id", UUID.class),
        r.get("category_name", String.class),
        r.get("supply_note", String.class),
        Boolean.TRUE.equals(r.get("supply_profile_found", Boolean.class)),
        r.get("supply_profile_version", Long.class),
        r.get("sku_count", Long.class),
        amounts,
        decimal(r.get("purchase_amount", BigDecimal.class)),
        r.get("purchase_amount_currency", String.class),
        onTimeCount,
        deliveryCount,
        onTimeRate(onTimeCount, deliveryCount),
        qualified,
        inspected,
        qualityRate(qualified, inspected),
        r.get("last_delivery_at", String.class),
        instant(r.get("last_inspection_at", OffsetDateTime.class)));
  }

  /**
   * 筛选条件：计数与取行<b>共用同一处</b>构造，保证两者的集合不可能分叉。
   *
   * <p><b>币种不进这里</b>：它只收窄 LATERAL 内部的金额聚合，不是行级过滤 ——
   * 按 USD 筛不应该把只有 EUR 往来的供应商整行滤掉（那会让列表凭空少掉一批供应商）。
   */
  private void conditions(VendorListQuery query, List<String> where, List<Object> binds) {
    if (notBlank(query.keyword())) {
      String like = "%" + escape(query.keyword().trim()) + "%";
      where.add(
          "(p.code ilike ? escape '!' or p.name ilike ? escape '!'"
              + " or coalesce(p.email,'') ilike ? escape '!'"
              + " or coalesce(p.phone,'') ilike ? escape '!')");
      for (int i = 0; i < 4; i++) binds.add(like);
    }
    if (notBlank(query.supplyStatus())) {
      String value = normalizeEnum(query.supplyStatus());
      String problem = supplyStatusProblem(value);
      CatalogService.check(problem == null, problem);
      where.add("coalesce(vsp.supply_status,'PENDING_REVIEW') = ?");
      binds.add(value);
    }
    if (query.categoryId() != null) {
      where.add("vsp.category_id = ?");
      binds.add(query.categoryId());
    }
    if (notBlank(query.cooperationLevel())) {
      where.add("psp.cooperation_level = ?");
      binds.add(normalizeEnum(query.cooperationLevel()));
    }
    if (notBlank(query.partyStatus())) {
      where.add("p.status = ?");
      binds.add(normalizeEnum(query.partyStatus()));
    }
    if (Boolean.TRUE.equals(query.missingProfile())) where.add("vsp.role_id is null");
  }

  private static String whereClause(List<String> where) {
    return where.isEmpty() ? "" : " and " + String.join(" and ", where);
  }

  // ================================================================ 存在性与归属校验

  /**
   * 该 role 必须是<b>本租户内、未删除主体</b>上的 SUPPLIER 角色；否则 404。
   *
   * <p>用 404 而不是 403：不泄露「这个 id 存在但不属于你」这一信息本身。
   */
  private void requireSupplierRole(UUID roleId) {
    var found =
        sql.fetchOne(
            "select r.id from party_role r"
                + " join party p on p.tenant_id=r.tenant_id and p.id=r.party_id"
                + " where r.tenant_id=? and r.id=? and r.role_type='SUPPLIER' and p.deleted_at is null",
            currentTenantId(),
            roleId);
    if (found == null) throw CatalogService.missing();
  }

  private void requireCategory(UUID categoryId) {
    var found =
        sql.fetchOne(
            "select id from product_category where tenant_id=? and id=?", currentTenantId(), categoryId);
    if (found == null) CatalogService.check(false, "主供类目不存在");
  }

  private void requireSku(UUID skuId) {
    var found = sql.fetchOne("select id from sku where tenant_id=? and id=?", currentTenantId(), skuId);
    if (found == null) CatalogService.check(false, "SKU 不存在");
  }

  private void requireUnit(String code) {
    var found =
        sql.fetchOne(
            "select code from unit_of_measure where tenant_id=? and code=?", currentTenantId(), code);
    if (found == null) CatalogService.check(false, "采购单位未在 3.3.3 登记：" + code);
  }

  // ================================================================ 小工具

  /** {@code timestamptz} 读出口径：统一转成 UTC 的 ISO-8601 串（带 {@code Z}），不交给前端猜时区。 */
  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  /** NUMERIC → BigDecimal；空值归零（聚合的 {@code sum()} 在全空时返回 null，而「0 次准时 / 0 次到货」要表达成 null）。 */
  private static BigDecimal decimal(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /** 当前租户来自 {@code app.current_tenant}（由 AccessService 在认证上下文里设置），**不读任何请求头**。 */
  private UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static String note(String value) {
    return value == null ? "" : value.trim();
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String blankToNull(String value) {
    return notBlank(value) ? value.trim() : null;
  }

  /** LIKE 的转义：用户输入里的 {@code %} 与 {@code _} 必须当普通字符，否则搜「50%」会变成通配。 */
  private static String escape(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private static List<Object> concat(List<Object> first, List<Object> second) {
    var out = new ArrayList<Object>(first.size() + second.size());
    out.addAll(first);
    out.addAll(second);
    return out;
  }

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        resourceId,
        action);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
