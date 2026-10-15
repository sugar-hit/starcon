package com.starcon.catalog;

import static com.starcon.catalog.VendorDetailModels.*;
import static com.starcon.catalog.VendorDetailRules.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * 6.1.1.2 供应商详情：<b>单供应商全景</b>（基础信息 / 账期与开票 / 报价 / 采购历史 / 交期质量 / 联系人 / 附件资质）
 * 与它自己的那部分写入动作。
 *
 * <p><b>本类最重要的一条纪律与 6.1.1.1 同源：不建第二份供应商主数据。</b>
 * 编码 / 名称 / 联系方式 / 合作等级 / 主体状态 / 采购侧合作状态 全部<b>只读</b>，
 * 逐字段的所有者对照见 {@code V142__vendor_detail_terms_qualifications.sql} 第二节。
 * 本类对 {@code party} / {@code party_supplier_profile} / {@code vendor_supply_profile}
 * 一个字节都不写 —— 测试 {@code 本模块不回写上游主数据} 把四个写端点跑一遍后逐列比对。
 *
 * <p><b>可写的只有两样</b>，都是采购视角独有的：
 *
 * <ul>
 *   <li><b>账期与开票信息</b>（{@code vendor_settlement_terms}）—— 蓝图加粗句「账期与开票信息是付款审批的输入」。
 *       它必须是<b>可维护的真实数据</b>，所以它有自己的表与写端点；只从别处只读一个字段等于没交付。
 *       注意它与 3.6.1.2 的主体默认账期<b>同时返回、分开标注</b>：两者不同是正常的，
 *       用一个覆盖另一个会让 12 财务拿错口径算到期日。
 *   <li><b>附件资质</b>（{@code vendor_qualification_attachment}）—— 证件有编号、发证机构与有效期，
 *       每份附件一行；文件本体不入库，只存对象存储 key（二进制进业务库会让备份、迁移与 RLS 审计都变成搬文件）。
 * </ul>
 *
 * <p><b>四个聚合没有一个是存下来的字段</b>，全部是本类的 SQL 聚合：
 * 准时交付率 / 平均延期天数 / 最大延期天数 / 早到笔数（{@code vendor_delivery_record}）、
 * 质量合格率与不合格数量（{@code vendor_quality_record}）、采购金额按币种、报价覆盖 SKU 数（{@code supplier_quote}）。
 * 口径沿用 V137 第二节：<b>比率的分母为 0 是 null 而不是 0</b>、<b>跨币种不相加</b>、
 * 准时由 {@code received_date <= due_date} 推出而<b>不存 on_time 布尔列</b>。
 *
 * <p><b>指标窗口</b>：交期与质量是<b>窗口统计</b>，窗口回显在响应的 {@code metricWindow} 上
 * —— 一个不说清是哪一段的「准时率 96%」在供应商评审里没有意义（与 6.1.1.1 同一口径与理由）。
 *
 * <p><b>租户</b>：每条 SQL 的 where 都带显式 {@code tenant_id=?}，被 join 的 party / psp /
 * vendor_delivery_record / supplier_quote 等表还有各自的 FORCE RLS —— 两道闸，任何一道单独失效都不放行跨租户。
 */
@Service
public class VendorDetailService {

  /**
   * 每条查询都带的那一段：SUPPLIER 角色 + 未删除主体。
   *
   * <p>与筛选条件<b>分开</b>且放在最前面：租户与「这是不是一个供应商」是安全边界，
   * 放进可变的 where 拼装里早晚会被一次重构挪到别处，而那种挪动不报错，只是隔离口径悄悄变了一条。
   */
  private static final String ALWAYS_WHERE =
      " r.tenant_id=? and r.role_type='SUPPLIER' and p.deleted_at is null";

  /** 主体 + 角色 + 两张只读档案 + 类目字典。计数与取行共用，保证集合口径不可能分叉。 */
  private static final String BASE_FROM =
      " from party_role r"
          + " join party p on p.tenant_id=r.tenant_id and p.id=r.party_id"
          + " left join party_supplier_profile psp on psp.tenant_id=r.tenant_id and psp.role_id=r.id"
          + " left join vendor_supply_profile vsp on vsp.tenant_id=r.tenant_id and vsp.role_id=r.id"
          + " left join product_category pc on pc.tenant_id=vsp.tenant_id and pc.id=vsp.category_id";

  private static final String IDENTITY_SELECT =
      "select p.id as party_id,p.code as party_code,p.name as party_name,p.party_type,"
          + "p.country_code,p.email,p.phone,p.website,p.status as party_status,p.status_reason,"
          + "r.id as role_id,"
          + "psp.cooperation_level,psp.payment_terms_days,psp.settlement_method,"
          + "psp.moq_quantity,psp.moq_unit_code,"
          + "(psp.role_id is not null) as supplier_profile_found,"
          + "vsp.supply_status,vsp.category_id,pc.name as category_name,vsp.note as supply_note,"
          + "(vsp.role_id is not null) as supply_profile_found";

  private final DSLContext sql;

  public VendorDetailService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 全景

  /**
   * 供应商全景：七段职责 + 操作入口 + 治理警告。
   *
   * <p><b>绩效三段（交期 / 质量 / 金额）都是窗口内的 SQL 聚合</b>，窗口由
   * {@link VendorListRules#metricWindow} 定出并回显 —— 与 6.1.1.1 同一套窗口语义，
   * 所以同一个供应商在列表里和详情里的「准时率」永远是同一个口径。
   */
  public VendorPanorama panorama(UUID roleId, OffsetDateTime from, OffsetDateTime to) {
    requireSupplierRole(roleId);
    var window = resolveWindow(from, to);
    var today = LocalDate.now(ZoneOffset.UTC);
    var uuid = currentTenantId();

    var identity =
        sql.fetchOne(
            IDENTITY_SELECT + BASE_FROM + " where " + ALWAYS_WHERE + " and r.id = ?", uuid, roleId);
    if (identity == null) throw CatalogService.missing();

    var delivery = deliveryPerformance(uuid, roleId, window);
    var quality = qualityPerformance(uuid, roleId, window);
    var amounts = purchaseAmounts(uuid, roleId, window);
    var quoteSummary = quoteSummary(uuid, roleId, today);
    var termsRow =
        sql.fetchOne(
            "select currency,payment_terms_days,settlement_method,invoice_type,invoice_title,"
                + "tax_id,tax_rate,credit_limit,deposit_ratio,effective_from,note,version"
                + " from vendor_settlement_terms where tenant_id=? and role_id=?",
            uuid,
            roleId);
    var terms =
        termsRow == null
            ? null
            : new VendorTermsView(
                roleId,
                termsRow.get("currency", String.class),
                termsRow.get("payment_terms_days", Integer.class),
                identity.get("payment_terms_days", Integer.class),
                identity.get("settlement_method", String.class),
                termsRow.get("settlement_method", String.class),
                termsRow.get("invoice_type", String.class),
                termsRow.get("invoice_title", String.class),
                termsRow.get("tax_id", String.class),
                decimal(termsRow.get("tax_rate", BigDecimal.class)),
                termsRow.get("credit_limit", BigDecimal.class),
                decimal(termsRow.get("deposit_ratio", BigDecimal.class)),
                termsRow.get("effective_from", LocalDate.class),
                note(termsRow.get("note", String.class)),
                termsRow.get("version", Long.class),
                true);

    var contacts =
        sql.fetch(
                "select ct.id,ct.name,ct.position,ct.email,ct.phone,ct.mobile,ct.is_primary,ct.note"
                    + " from party_contact ct"
                    + " join party_role r on r.tenant_id=ct.tenant_id and r.party_id=ct.party_id"
                    + " where ct.tenant_id=? and r.id=? and r.role_type='SUPPLIER'"
                    + " order by ct.is_primary desc, ct.name",
                uuid,
                roleId)
            .map(
                r ->
                    new VendorContactRow(
                        r.get("id", UUID.class),
                        r.get("name", String.class),
                        r.get("position", String.class),
                        r.get("email", String.class),
                        r.get("phone", String.class),
                        r.get("mobile", String.class),
                        Boolean.TRUE.equals(r.get("is_primary", Boolean.class)),
                        note(r.get("note", String.class))));

    var qualificationCounts =
        sql.fetchOne(
            "select count(*) as total,"
                + "count(*) filter (where expires_on is not null and expires_on < ?) as expired,"
                + "count(*) filter (where expires_on is not null and expires_on >= ?"
                + "   and expires_on <= ?) as expiring,"
                + "min(expires_on) filter (where expires_on is not null and expires_on >= ?) as nearest"
                + " from vendor_qualification_attachment where tenant_id=? and role_id=?",
            today,
            today,
            today.plusDays(EXPIRING_SOON_DAYS),
            today,
            uuid,
            roleId);

    var warnings = new ArrayList<String>();
    if (terms == null) {
      warnings.add(
          "还没建账期与开票信息：12 财务无法自动生成付款申请（到期日、发票类型、税号都取不到），"
              + "下单前必须补齐。");
    } else {
      if ("NONE".equals(terms.invoiceType())) {
        warnings.add("开票方式登记为「无票」：12 财务不能走自动付款申请，只能人工单据处理。");
      }
      if (terms.creditLimit() == null) {
        warnings.add("没设采购信用额度：12 财务的授信审批没有输入（未设额度 ≠ 额度为 0）。");
      }
      if (terms.taxId() == null || terms.taxId().isBlank()) {
        warnings.add("账期与开票信息里没有税号：需要发票的结算方式缺了这个字段，付款申请会被退回来。");
      }
    }
    if (!Boolean.TRUE.equals(identity.get("supplier_profile_found", Boolean.class))) {
      warnings.add("还没建供应商角色档案（3.6.1.2）：合作等级与默认账期为空，本页的「主体默认账期」显示为未设置。");
    }
    if (!Boolean.TRUE.equals(identity.get("supply_profile_found", Boolean.class))) {
      warnings.add("还没建采购侧运营档案（6.1.1.1）：采购侧合作状态与主供类目为空，06 建单时候选筛选拿不到口径。");
    }
    if (quoteSummary.effectiveCount() == 0) {
      warnings.add("没有有效报价：06 建单带不出价格，新建采购单会缺单价与起订量。");
    }
    if (contacts.isEmpty()) {
      warnings.add("没有登记联系人：问交期、约提货、对账时没有可拨的人（联系人在往来单位档案里维护）。");
    }
    var expiredCount = longOf(qualificationCounts, "expired");
    var expiringCount = longOf(qualificationCounts, "expiring");
    if (expiredCount > 0) {
      warnings.add(
          "有 " + expiredCount + " 份附件资质已过期：下单前必须换证，否则清关与平台准入会被卡。");
    }
    if (expiringCount > 0) {
      warnings.add(
          "有 " + expiringCount + " 份附件资质将在 " + EXPIRING_SOON_DAYS + " 天内到期：续期要 2~4 周，现在就该催。");
    }
    if (delivery.deliveryCount() == 0) {
      warnings.add("窗口内没有到货记录：准时率显示为「暂无数据」而不是 0%（这两者是相反的两件事）。");
    }

    return new VendorPanorama(
        roleId,
        identity.get("party_id", UUID.class),
        identity.get("party_code", String.class),
        identity.get("party_name", String.class),
        identity.get("party_type", String.class),
        identity.get("country_code", String.class),
        identity.get("email", String.class),
        identity.get("phone", String.class),
        identity.get("website", String.class),
        identity.get("party_status", String.class),
        identity.get("status_reason", String.class),
        identity.get("cooperation_level", String.class),
        identity.get("settlement_method", String.class),
        identity.get("moq_unit_code", String.class),
        identity.get("moq_quantity", BigDecimal.class),
        Boolean.TRUE.equals(identity.get("supplier_profile_found", Boolean.class)),
        identity.get("supply_status", String.class),
        identity.get("category_id", UUID.class),
        identity.get("category_name", String.class),
        identity.get("supply_note", String.class),
        Boolean.TRUE.equals(identity.get("supply_profile_found", Boolean.class)),
        terms,
        terms != null,
        quoteSummary.quoteCount(),
        quoteSummary.effectiveCount(),
        quoteSummary.skuCount(),
        quoteSummary.currency(),
        delivery.deliveryCount(),
        amounts,
        delivery.lastDeliveryAt(),
        delivery,
        quality,
        contacts,
        longOf(qualificationCounts, "total"),
        expiredCount,
        expiringCount,
        qualificationCounts.get("nearest", LocalDate.class),
        operations(terms, identity),
        warnings,
        window.window(),
        "基础信息、合作等级与采购侧合作状态分别来自 3.6.1.1 / 3.6.1.2 / 6.1.1.1，本页只读；"
            + "本页可写的只有账期与开票信息、附件资质两段。交期与质量表现是**窗口统计**"
            + "（当前窗口已回显在 metricWindow 上），不是终身累计。");
  }

  /**
   * 操作入口。
   *
   * <p><b>灰掉的按钮必须给出理由</b>（{@code available=false} 时 {@code reason} 非空）：
   * 灰掉一个按钮而不说为什么，运营只会以为系统坏了，而实际上「新建采购单」还挂在未交付的 6.3 上。
   *
   * <p>这里**不造** 6.3 / 6.5 的端点：它们是未交付的模块，为一个还不存在的模块造一个
   * 「空实现」端点，就是 3.8.2.1 导出通道那个教训（放行不该存在的路径比 404 更糟）。
   */
  private List<VendorOperationEntry> operations(VendorTermsView terms, Record identity) {
    var entries = new ArrayList<VendorOperationEntry>();
    var supplyStatus = identity.get("supply_status", String.class);
    entries.add(
        new VendorOperationEntry(
            "NEW_PURCHASE_ORDER",
            "新建采购单",
            false,
            null,
            "6.3.1.2 采购单尚未交付，本页不造占位入口。"
                + (terms == null ? "另外：账期与开票信息还没建，采购单带不出到期日口径。" : "")
                + (supplyStatus == null || !"ACTIVE".equals(supplyStatus)
                    ? "采购侧合作状态不是「合作中」，建单候选里也不会出现这个供应商。"
                    : "")));
    entries.add(
        new VendorOperationEntry(
            "START_RECONCILIATION",
            "发起对账",
            false,
            null,
            "6.5.1 对账尚未交付，本页不造占位入口。"
                + (terms == null ? "对账按币种分批，账期与开票信息缺失时无法确定对账区间与到期日。" : "")));
    entries.add(
        new VendorOperationEntry(
            "UPDATE_QUOTE",
            "更新报价",
            true,
            "/partners/offers",
            "报价的写入所有权在 3.6.2.1 报价台账（supplier_quote 表），本页只读 —— "
                + "在同一份数据上提供第二套编辑能力，就是蓝图点名要避免的「同一实体两处可编辑」。"));
    entries.add(
        new VendorOperationEntry(
            "EDIT_PARTY_PROFILE",
            "维护基础信息与联系人",
            true,
            "/partners",
            "编码 / 名称 / 联系方式 / 合作等级 / 联系人的写入所有权在往来单位档案（3.6.x）。"));
    return entries;
  }

  // ================================================================ 绩效聚合（全部是窗口内的 SQL 聚合）

  /**
   * 交期表现。
   *
   * <p>{@code received_date - due_date} 在 PostgreSQL 里是两个 date 相减，得到整数天数，
   * 所以 {@code avg()} 出的是 numeric —— <b>不需要也不允许</b>在这里换成浮点。
   *
   * <p>「平均延期天数」允许为负（提前到货），且**不把提前与延期互相抵消成 0**：
   * 一个总是早到 5 天的供应商与一个准时率 100% 但从不早到的供应商是两种，
   * 折叠成一个数之后采购在谈交期时拿不到任何信号。
   *
   * <p>「最大延期天数」只统计晚到的（{@code greatest(...,0)}）：早到 5 天的那一批不该让最大值变成 -5。
   */
  private VendorDeliveryPerformance deliveryPerformance(
      UUID uuid, UUID roleId, Window window) {
    var r =
        sql.fetchOne(
            "select count(*) as delivery_count,"
                + "count(*) filter (where d.received_date <= d.due_date) as on_time_count,"
                + "count(*) filter (where d.received_date < d.due_date) as early_count,"
                + "avg((d.received_date - d.due_date)) as avg_days_late,"
                + "max(greatest((d.received_date - d.due_date),0)) as max_days_late,"
                + "max(d.received_date)::text as last_delivery_at"
                + " from vendor_delivery_record d"
                + " where d.tenant_id=? and d.role_id=? and not d.is_void"
                + "   and d.received_date between ? and ?",
            uuid,
            roleId,
            window.fromDate(),
            window.toDate());
    var total = longOf(r, "delivery_count");
    var onTime = longOf(r, "on_time_count");
    return new VendorDeliveryPerformance(
        total,
        onTime,
        // 分母为 0 → null 而不是 0（D4）。
        total == 0 ? null : scale(onTime, 4, total),
        r.get("avg_days_late", BigDecimal.class) == null
            ? null
            : r.get("avg_days_late", BigDecimal.class).setScale(2, RoundingMode.HALF_UP),
        r.get("max_days_late", Integer.class),
        longOf(r, "early_count"),
        r.get("last_delivery_at", String.class));
  }

  /**
   * 质量表现。按<b>数量</b>比而不是批次数比（与 6.1.1.1 的 M4 同一理由）：
   * 一批 10000 件里合格 9999 与一批 3 件里合格 1，按批次算是同一个结果（1/2），
   * 按数量算才是 99.99% 与 33.33% —— 后者才是采购真正承担的风险大小。
   */
  private VendorQualityPerformance qualityPerformance(UUID uuid, UUID roleId, Window window) {
    var r =
        sql.fetchOne(
            "select count(*) as inspection_count,"
                + "coalesce(sum(q.inspected_qty),0) as inspected_qty,"
                + "coalesce(sum(q.qualified_qty),0) as qualified_qty,"
                + "max(q.inspected_at) as last_inspection_at"
                + " from vendor_quality_record q"
                + " where q.tenant_id=? and q.role_id=? and not q.is_void"
                + "   and q.inspected_at >= ?::timestamptz and q.inspected_at <= ?::timestamptz",
            uuid,
            roleId,
            window.fromInstantStart(),
            window.toInstantEnd());
    var inspected = decimal(r.get("inspected_qty", BigDecimal.class));
    var qualified = decimal(r.get("qualified_qty", BigDecimal.class));
    return new VendorQualityPerformance(
        longOf(r, "inspection_count"),
        inspected,
        qualified,
        VendorListRules.qualityRate(qualified, inspected),
        inspected.subtract(qualified),
        instant(r.get("last_inspection_at", OffsetDateTime.class)));
  }

  /** 采购金额按币种分组（<b>跨币种不相加</b>，D3）。 */
  private List<VendorListModels.VendorPurchaseAmount> purchaseAmounts(UUID uuid, UUID roleId, Window window) {
    return sql.fetch(
            "select d.currency,sum(d.amount) as amount"
                + " from vendor_delivery_record d"
                + " where d.tenant_id=? and d.role_id=? and not d.is_void"
                + "   and d.received_date between ? and ?"
                + " group by d.currency order by sum(d.amount) desc",
            uuid,
            roleId,
            window.fromDate(),
            window.toDate())
        .map(r -> new VendorListModels.VendorPurchaseAmount(r.get("currency", String.class), decimal(r.get("amount", BigDecimal.class))));
  }

  /**
   * 报价摘要。
   *
   * <p>{@code skuCount} 只数 {@code status='ACTIVE'} 的报价，与 6.1.1.1 的 M1 <b>逐字同口径</b>：
   * 停了用的报价不再代表「他供得上这个 SKU」。刻意<b>不加</b>有效期过滤 ——
   * 「今天供得上」与「曾经供得上」两个口径在界面上混成一个数就失去了对比意义。
   * 而 {@code effectiveCount} 是另一回事：它按给定日期判有效期，两者不是同一个数。
   */
  private QuoteSummary quoteSummary(UUID uuid, UUID roleId, LocalDate today) {
    var r =
        sql.fetchOne(
            "select count(*) as quote_count,"
                + "count(*) filter (where sq.status='ACTIVE' and sq.valid_from <= ?"
                + "   and (sq.valid_to is null or sq.valid_to >= ?)) as effective_count,"
                + "count(distinct sq.sku_id) filter (where sq.status='ACTIVE') as sku_count,"
                + "(array_agg(sq.currency))[1] as currency"
                + " from supplier_quote sq where sq.tenant_id=? and sq.supplier_role_id=?",
            today,
            today,
            uuid,
            roleId);
    return new QuoteSummary(
        longOf(r, "quote_count"), longOf(r, "effective_count"), longOf(r, "sku_count"), r.get("currency", String.class));
  }

  private record QuoteSummary(long quoteCount, long effectiveCount, long skuCount, String currency) {}

  // ================================================================ 采购历史与金额

  /**
   * 采购历史：窗口内到货记录<b>真分页</b> + 聚合（按单位/币种分桶 + 按月金额序列）。
   *
   * <p><b>计数与取行引同一处筛选构造</b>（{@link #historyConditions}）—— 各拼一遍的症状是
   * 「页面说 7 条，翻到第 2 页是空的」。
   *
   * <p>数量按<b>采购单位 + 币种</b>分桶返回而不是给一个总数：把「3 箱 + 200 件」加成一个数
   * 在报表上看起来正常、实际上毫无意义（跨单位不可换算，AGENTS.md 禁浮点记账同理）。
   */
  public VendorPurchaseHistoryPage purchaseHistory(
      UUID roleId, String currency, int page, int size, OffsetDateTime from, OffsetDateTime to) {
    requireSupplierRole(roleId);
    var uuid = currentTenantId();
    var window = resolveWindow(from, to);
    var rows = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    binds.add(uuid);
    binds.add(roleId);
    binds.add(window.fromDate());
    binds.add(window.toDate());
    historyConditions(currency, rows, binds);
    var where = rows.isEmpty() ? "" : " and " + String.join(" and ", rows);

    var total =
        longOf(
            sql.fetchOne(
                "select count(*) as n from vendor_delivery_record d"
                    + " where d.tenant_id=? and d.role_id=? and d.received_date between ? and ?"
                    + where,
                binds.toArray()),
            "n");
    var offset = Math.max(0, page) * Math.max(1, size);
    var pageBinds = new ArrayList<Object>(binds);
    pageBinds.add(size);
    pageBinds.add(offset);

    var items =
        sql.fetch(
                "select d.id,d.delivery_ref,d.sku_id,s.code as sku_code,s.name as sku_name,"
                    + "d.purchase_unit_code,d.quantity,d.amount,d.currency,d.ordered_at,d.due_date,"
                    + "d.received_date,d.is_void,d.void_reason,d.note"
                    + " from vendor_delivery_record d"
                    + " left join sku s on s.tenant_id=d.tenant_id and s.id=d.sku_id"
                    + " where d.tenant_id=? and d.role_id=? and d.received_date between ? and ?"
                    + where
                    + " order by d.received_date desc, d.id desc limit ? offset ?",
                pageBinds.toArray())
            .map(
                r -> {
                  var due = r.get("due_date", LocalDate.class);
                  var received = r.get("received_date", LocalDate.class);
                  return new VendorPurchaseHistoryRow(
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
                      note(r.get("note", String.class)));
                });

    var buckets =
        sql.fetch(
                "select coalesce(d.purchase_unit_code,'') as purchase_unit_code,d.currency,"
                    + "sum(d.quantity) as quantity,sum(d.amount) as amount,count(*) as delivery_count"
                    + " from vendor_delivery_record d"
                    + " where d.tenant_id=? and d.role_id=? and not d.is_void"
                    + "   and d.received_date between ? and ?"
                    + where
                    + " group by d.purchase_unit_code,d.currency"
                    + " order by sum(d.amount) desc",
                binds.toArray())
            .map(
                r ->
                    new VendorHistoryBucket(
                        r.get("purchase_unit_code", String.class),
                        r.get("currency", String.class),
                        decimal(r.get("quantity", BigDecimal.class)),
                        decimal(r.get("amount", BigDecimal.class)),
                        longOf(r, "delivery_count")));

    var monthly =
        sql.fetch(
                "select to_char(date_trunc('month',d.received_date),'YYYY-MM') as month,"
                    + "d.currency,sum(d.amount) as amount,count(*) as delivery_count"
                    + " from vendor_delivery_record d"
                    + " where d.tenant_id=? and d.role_id=? and not d.is_void"
                    + "   and d.received_date between ? and ?"
                    + where
                    + " group by 1,d.currency order by 1 desc, sum(d.amount) desc",
                binds.toArray())
            .map(
                r ->
                    new VendorMonthlyAmount(
                        r.get("month", String.class),
                        r.get("currency", String.class),
                        decimal(r.get("amount", BigDecimal.class)),
                        longOf(r, "delivery_count")));

    // 总数量只给「所有分桶都是同一个单位」的情形，否则给 null：
    // 跨单位求和是一个看起来正常、实际毫无意义的数字。
    BigDecimal totalQuantity = null;
    var units = new java.util.LinkedHashSet<String>();
    for (var bucket : buckets) units.add(bucket.purchaseUnitCode());
    if (units.size() == 1 && !buckets.isEmpty()) totalQuantity = buckets.get(0).quantity();

    return new VendorPurchaseHistoryPage(
        items,
        total,
        Math.max(0, page),
        Math.max(1, size),
        buckets.stream().mapToLong(VendorHistoryBucket::deliveryCount).sum(),
        totalQuantity,
        buckets,
        monthly,
        window.window(),
        "金额按币种分组，绝不相加（��库没有汇率表）；数量按采购单位分组，跨单位不给总数。");
  }

  /** 采购历史的筛选：<b>币种只收窄聚合与取行，不影响 total 的集合定义之外的东西</b>（它同时进两者，所以翻页不会错位）。 */
  private void historyConditions(String currency, List<String> where, List<Object> binds) {
    if (currency != null && !currency.isBlank()) {
      String value = currency.trim().toUpperCase(java.util.Locale.ROOT);
      CatalogService.check(
          value.matches("^[A-Z]{3}$"), "币种必须是 ISO 4217 三位大写字母码，收到的是：" + currency);
      where.add("d.currency = ?");
      binds.add(value);
    }
  }

  // ================================================================ 供货 SKU 与报价

  /**
   * 报价清单：分页 + 状态 / 币种筛选 + 「只看有效」。
   *
   * <p>{@code firstTierUnitPrice} 取<b>起订量最小的那一档</b>：全景页只回答「从多少钱起订」，
   * 把全部阶梯摊在列表里会让一行报价变成一屏数字而读不出结论；档位明细在 3.6.2.1 的报价详情里。
   *
   * <p>「有效」在这里是<b>现算</b>的（{@code status='ACTIVE'} 且落在 valid_from..valid_to 闭区间内），
   * 与 3.6.2.1 同一口径 —— 过期不落成一个 status 值，理由见 supplier_quote 的 DDL 注释。
   */
  public VendorQuotePage quotes(
      UUID roleId, String status, String currency, Boolean effectiveOnly, LocalDate at, int page, int size) {
    requireSupplierRole(roleId);
    var uuid = currentTenantId();
    var on = at == null ? LocalDate.now(ZoneOffset.UTC) : at;
    var rows = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    binds.add(uuid);
    binds.add(roleId);
    if (status != null && !status.isBlank()) {
      var problem = quoteStatusProblem(status);
      CatalogService.check(problem == null, problem);
      rows.add("sq.status = ?");
      binds.add(VendorListRules.normalizeEnum(status));
    }
    if (currency != null && !currency.isBlank()) {
      var value = currency.trim().toUpperCase(java.util.Locale.ROOT);
      CatalogService.check(
          value.matches("^[A-Z]{3}$"), "币种必须是 ISO 4217 三位大写字母码，收到的是：" + currency);
      rows.add("sq.currency = ?");
      binds.add(value);
    }
    if (Boolean.TRUE.equals(effectiveOnly)) {
      rows.add("sq.status='ACTIVE' and sq.valid_from <= ? and (sq.valid_to is null or sq.valid_to >= ?)");
      binds.add(on);
      binds.add(on);
    }
    var where = rows.isEmpty() ? "" : " and " + String.join(" and ", rows);

    // 计数查询里 SELECT 列表的 filter 参数在 SQL 文本上**先于** WHERE 的参数出现，
    // 所以这里必须把两个 on 放在 binds 前面 —— 顺序反了不会报错，只会静默查出一个错的数。
    var countBinds = new ArrayList<Object>();
    countBinds.add(on);
    countBinds.add(on);
    countBinds.addAll(binds);
    var counts =
        sql.fetchOne(
            "select count(*) as n,"
                + "count(distinct sq.sku_id) filter (where sq.status='ACTIVE'"
                + "   and sq.valid_from <= ? and (sq.valid_to is null or sq.valid_to >= ?)) as sku_count"
                + " from supplier_quote sq"
                + " where sq.tenant_id=? and sq.supplier_role_id=?"
                + where,
            countBinds.toArray());
    var total = longOf(counts, "n");
    var effectiveSkuCount = longOf(counts, "sku_count");

    // 取行查询的 SELECT 列表里**没有** effective 判定占位（那是 Java 侧算的），
    // 所以 binds 后面只能接 limit / offset —— 多塞一个 on 会把日期绑到 limit 上，
    // 报出来的是一句与真实原因无关的「invalid input syntax for type bigint」。
    var pageBinds = new ArrayList<Object>(binds);
    pageBinds.add(Math.max(1, size));
    pageBinds.add(Math.max(0, page) * Math.max(1, size));
    var items =
        sql.fetch(
                "select sq.id,sq.sku_id,s.code as sku_code,s.name as sku_name,sq.supplier_sku_code,"
                    + "sq.purchase_unit_code,sq.currency,sq.moq_quantity,sq.lead_time_days,"
                    + "sq.valid_from,sq.valid_to,sq.priority,sq.status,sq.note,sq.version,"
                    + "(select count(*) from supplier_quote_price_tier t"
                    + "   where t.tenant_id=sq.tenant_id and t.quote_id=sq.id) as tier_count,"
                    + "(select t2.unit_price from supplier_quote_price_tier t2"
                    + "   where t2.tenant_id=sq.tenant_id and t2.quote_id=sq.id"
                    + "   order by t2.min_quantity limit 1) as first_tier_price"
                    + " from supplier_quote sq"
                    + " left join sku s on s.tenant_id=sq.tenant_id and s.id=sq.sku_id"
                    + " where sq.tenant_id=? and sq.supplier_role_id=?"
                    + where
                    + " order by sq.priority, sq.valid_from desc, sq.id limit ? offset ?",
                pageBinds.toArray())
            .map(
                r -> {
                  var status2 = r.get("status", String.class);
                  var validFrom = r.get("valid_from", LocalDate.class);
                  var validTo = r.get("valid_to", LocalDate.class);
                  return new VendorQuoteRow(
                      r.get("id", UUID.class),
                      r.get("sku_id", UUID.class),
                      r.get("sku_code", String.class),
                      r.get("sku_name", String.class),
                      r.get("supplier_sku_code", String.class),
                      r.get("purchase_unit_code", String.class),
                      r.get("currency", String.class),
                      decimal(r.get("moq_quantity", BigDecimal.class)),
                      r.get("lead_time_days", Integer.class),
                      r.get("first_tier_price", BigDecimal.class),
                      r.get("tier_count", Integer.class),
                      validFrom,
                      validTo,
                      r.get("priority", Integer.class),
                      status2,
                      "ACTIVE".equals(status2)
                          && validFrom != null
                          && !validFrom.isAfter(on)
                          && (validTo == null || !validTo.isBefore(on)),
                      note(r.get("note", String.class)),
                      r.get("version", Long.class));
                });

    return new VendorQuotePage(
        items,
        total,
        Math.max(0, page),
        Math.max(1, size),
        effectiveSkuCount,
        on,
        "报价的写入所有权在 3.6.2.1（supplier_quote 表），本页只读。"
            + "「有效」= status 为 ACTIVE 且落在 valid_from..valid_to 闭区间内（按 effectiveOn 那天判定）。");
  }

  // ================================================================ 附件资质

  /** 附件资质清单，按「快过期的排前面」排 —— 这个顺序就是运营的处理顺序。 */
  public VendorQualificationList qualifications(UUID roleId) {
    requireSupplierRole(roleId);
    var today = LocalDate.now(ZoneOffset.UTC);
    var items =
        sql.fetch(
                "select qa.id,qa.role_id,qa.qualification_type,qa.title,qa.cert_no,qa.issuer,"
                    + "qa.issued_on,qa.expires_on,qa.file_key,qa.file_name,qa.mime_type,"
                    + "qa.file_size_bytes,qa.note,qa.version"
                    + " from vendor_qualification_attachment qa"
                    + " where qa.tenant_id=? and qa.role_id=?"
                    + " order by (qa.expires_on is null), qa.expires_on, qa.qualification_type, qa.id",
                currentTenantId(),
                roleId)
            .map(r -> toQualification(r, today));
    long expired = items.stream().filter(i -> "EXPIRED".equals(i.validity())).count();
    long expiring = items.stream().filter(i -> "EXPIRING_SOON".equals(i.validity())).count();
    return new VendorQualificationList(
        items,
        items.size(),
        expired,
        expiring,
        "有效期状态是**现算**的（按今天与 expires_on 比较），不落库 —— "
            + "存一列就要一个每日任务去维护它，而它与读出口算出的那天差一天时，"
            + "「这张证到底过期没有」会答错，而这个答案直接决定能不能下单。文件本体不入库，只存对象存储 key。");
  }

  private VendorQualificationRow toQualification(Record r, LocalDate today) {
    var expiresOn = r.get("expires_on", LocalDate.class);
    return new VendorQualificationRow(
        r.get("id", UUID.class),
        r.get("role_id", UUID.class),
        r.get("qualification_type", String.class),
        r.get("title", String.class),
        note(r.get("cert_no", String.class)),
        note(r.get("issuer", String.class)),
        r.get("issued_on", LocalDate.class),
        expiresOn,
        qualificationValidity(expiresOn, today),
        daysToExpiry(expiresOn, today),
        r.get("file_key", String.class),
        note(r.get("file_name", String.class)),
        r.get("mime_type", String.class),
        r.get("file_size_bytes", Long.class),
        note(r.get("note", String.class)),
        r.get("version", Long.class));
  }

  /**
   * 新建 / 修改一份附件资质。
   *
   * <p>{@code attachmentId} 为 null 时新建。资质是<b>行政记录</b>（换证就换一行），所以可以改可以删 ——
   * 与账期与开票信息不同，后者是付款依据，只有一份，改了留 version 与审计（D5）。
   */
  public VendorQualificationSaved saveQualification(
      AccessService.Access actor,
      UUID roleId,
      UUID attachmentId,
      VendorQualificationInput input,
      boolean creating) {
    requireSupplierRole(roleId);
    var uuid = currentTenantId();
    var typeProblem = qualificationTypeProblem(input.qualificationType());
    CatalogService.check(typeProblem == null, typeProblem);
    if (input.issuedOn() != null
        && input.expiresOn() != null
        && input.expiresOn().isBefore(input.issuedOn())) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "到期日不能早于签发日：签发 " + input.issuedOn() + "、到期 " + input.expiresOn());
    }
    var type = VendorListRules.normalizeEnum(input.qualificationType());
    if (creating) {
      var id = Ids.next();
      sql.execute(
          "insert into vendor_qualification_attachment(tenant_id,id,role_id,qualification_type,title,"
              + "cert_no,issuer,issued_on,expires_on,file_key,file_name,mime_type,file_size_bytes,note,"
              + "version,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,now(),now())",
          uuid,
          id,
          roleId,
          type,
          input.title().trim(),
          note(input.certNo()),
          note(input.issuer()),
          input.issuedOn(),
          input.expiresOn(),
          input.fileKey().trim(),
          note(input.fileName()),
          note(input.mimeType()),
          input.fileSizeBytes(),
          note(input.note()));
      audit(actor, roleId, "VENDOR_QUALIFICATION_CREATE");
      return new VendorQualificationSaved(id, 0L);
    }
    var updated =
        sql.execute(
            "update vendor_qualification_attachment set qualification_type=?,title=?,cert_no=?,"
                + "issuer=?,issued_on=?,expires_on=?,file_key=?,file_name=?,mime_type=?,"
                + "file_size_bytes=?,note=?,version=version+1,updated_at=now()"
                + " where tenant_id=? and id=? and role_id=? and version=?",
            type,
            input.title().trim(),
            note(input.certNo()),
            note(input.issuer()),
            input.issuedOn(),
            input.expiresOn(),
            input.fileKey().trim(),
            note(input.fileName()),
            note(input.mimeType()),
            input.fileSizeBytes(),
            note(input.note()),
            uuid,
            attachmentId,
            roleId,
            input.version());
    CatalogService.check(
        updated == 1, "附件资质不存在、不属于这个供应商，或已被别人改过（version 不匹配）");
    audit(actor, roleId, "VENDOR_QUALIFICATION_UPDATE");
    return new VendorQualificationSaved(
        attachmentId,
        longOf(sql.fetchOne("select version from vendor_qualification_attachment where tenant_id=? and id=?", uuid, attachmentId), "version"));
  }

  /** 删除一份附件资质（行政记录，不是只追加事实表，所以真的删而不是作废）。 */
  public void deleteQualification(AccessService.Access actor, UUID roleId, UUID attachmentId) {
    requireSupplierRole(roleId);
    var deleted =
        sql.execute(
            "delete from vendor_qualification_attachment where tenant_id=? and id=? and role_id=?",
            currentTenantId(),
            attachmentId,
            roleId);
    CatalogService.check(deleted == 1, "附件资质不存在或不属于这个供应商");
    audit(actor, roleId, "VENDOR_QUALIFICATION_DELETE");
  }

  // ================================================================ 账期与开票信息（写入）

  /**
   * 新建 / 修改账期与开票信息。
   *
   * <p>蓝图加粗句「<b>账期与开票信息是付款审批的输入</b>」的落点就是这个写端点：
   * 它必须是真实可维护的数据（硬要求），而不是从别处只读一个字段。
   *
   * <p><b>刻意不回写 {@code party_supplier_profile.payment_terms_days}</b>：那是 3.6.1.2 的主体默认账期，
   * 本采购线的约定与它可以不同（USD 预付 + EUR 月结的主体很常见）。「顺手同步一下」
   * 会让 12 应付按主体默认算出来的到期日与采购单上的账期对不上，而没有任何东西会报错。
   */
  public VendorTermsView saveTerms(AccessService.Access actor, UUID roleId, VendorTermsInput input) {
    requireSupplierRole(roleId);
    var uuid = currentTenantId();
    var methodProblem = settlementMethodProblem(input.settlementMethod());
    CatalogService.check(methodProblem == null, methodProblem);
    var invoiceProblem = invoiceTypeProblem(input.invoiceType());
    CatalogService.check(invoiceProblem == null, invoiceProblem);
    var method = VendorListRules.normalizeEnum(input.settlementMethod());
    var invoice = VendorListRules.normalizeEnum(input.invoiceType());
    var currency = input.currency().trim().toUpperCase(java.util.Locale.ROOT);
    var taxId = note(input.taxId());

    if ("PREPAID".equals(method) && input.depositRatio().signum() <= 0) {
      throw badRequest(
          "结算方式是「预付」时预付比例必须大于 0：0 会让 12 财务算出一份「不用预付」的预付约定，"
              + "界面看不出问题而付款申请会被退回来。");
    }
    if (!"NONE".equals(invoice) && !"PROFORMA".equals(invoice) && taxId.isEmpty()) {
      throw badRequest(
          "开票方式是「" + invoice + "」时必须填税号：缺了它 12 财务无法自动生成付款申请。"
              + "确实拿不到税号的供应商，请把开票方式登记成「无票」。");
    }
    if (input.paymentTermsDays() > 365) {
      throw badRequest("账期上限 365 天：填进来的数字超过一年，那不是账期，是把年填成了天。");
    }

    var exists =
        sql.fetchOne("select 1 from vendor_settlement_terms where tenant_id=? and role_id=?", uuid, roleId)
            != null;
    if (exists) {
      var updated =
          sql.execute(
              "update vendor_settlement_terms set currency=?,payment_terms_days=?,settlement_method=?,"
                  + "invoice_type=?,invoice_title=?,tax_id=?,tax_rate=?,credit_limit=?,deposit_ratio=?,"
                  + "effective_from=?,note=?,version=version+1,updated_at=now()"
                  + " where tenant_id=? and role_id=? and version=?",
              currency,
              input.paymentTermsDays(),
              method,
              invoice,
              input.invoiceTitle().trim(),
              taxId,
              input.taxRate(),
              input.creditLimit(),
              input.depositRatio(),
              input.effectiveFrom(),
              note(input.note()),
              uuid,
              roleId,
              input.version());
      CatalogService.check(
          updated == 1, "账期与开票信息不存在，或已被别人改过（version 不匹配，请刷新后重试）");
      audit(actor, roleId, "VENDOR_TERMS_UPDATE");
    } else {
      CatalogService.check(
          input.version() == 0,
          "账期与开票信息还不存在，保存时请把 version 传 0（新建）而不是 " + input.version());
      sql.execute(
          "insert into vendor_settlement_terms(tenant_id,role_id,currency,payment_terms_days,"
              + "settlement_method,invoice_type,invoice_title,tax_id,tax_rate,credit_limit,"
              + "deposit_ratio,effective_from,note,version,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,0,now(),now())",
          uuid,
          roleId,
          currency,
          input.paymentTermsDays(),
          method,
          invoice,
          input.invoiceTitle().trim(),
          taxId,
          input.taxRate(),
          input.creditLimit(),
          input.depositRatio(),
          input.effectiveFrom(),
          note(input.note()));
      audit(actor, roleId, "VENDOR_TERMS_CREATE");
    }
    return termsView(uuid, roleId);
  }

  /** 读回账期与开票信息，并带上 3.6.1.2 的主体默认账期做对照。 */
  public VendorTermsView termsView(UUID roleId) {
    requireSupplierRole(roleId);
    return termsView(currentTenantId(), roleId);
  }

  private VendorTermsView termsView(UUID uuid, UUID roleId) {
    var row =
        sql.fetchOne(
            "select t.currency,t.payment_terms_days,t.settlement_method,t.invoice_type,t.invoice_title,"
                + "t.tax_id,t.tax_rate,t.credit_limit,t.deposit_ratio,t.effective_from,t.note,t.version,"
                + "psp.payment_terms_days as master_days,psp.settlement_method as master_method"
                + " from vendor_settlement_terms t"
                + " left join party_supplier_profile psp on psp.tenant_id=t.tenant_id and psp.role_id=t.role_id"
                + " where t.tenant_id=? and t.role_id=?",
            uuid,
            roleId);
    if (row == null) return null;
    return new VendorTermsView(
        roleId,
        row.get("currency", String.class),
        row.get("payment_terms_days", Integer.class),
        row.get("master_days", Integer.class),
        row.get("master_method", String.class),
        row.get("settlement_method", String.class),
        row.get("invoice_type", String.class),
        row.get("invoice_title", String.class),
        row.get("tax_id", String.class),
        decimal(row.get("tax_rate", BigDecimal.class)),
        row.get("credit_limit", BigDecimal.class),
        decimal(row.get("deposit_ratio", BigDecimal.class)),
        row.get("effective_from", LocalDate.class),
        note(row.get("note", String.class)),
        row.get("version", Long.class),
        true);
  }

  // ================================================================ 词表

  /** 词表选项：前端下拉的数据源，与 {@link VendorDetailRules} 同源。 */
  public Map<String, List<VendorListRules.Option>> options() {
    var map = new java.util.LinkedHashMap<String, List<VendorListRules.Option>>();
    map.put("settlementMethods", SETTLEMENT_METHODS);
    map.put("invoiceTypes", INVOICE_TYPES);
    map.put("qualificationTypes", QUALIFICATION_TYPES);
    map.put("quoteStatuses", List.of(
        new VendorListRules.Option("ACTIVE", "启用中", "参与有效报价判定"),
        new VendorListRules.Option("DISABLED", "已停用", "断货 / 涨价未确认时临时停用")));
    return map;
  }

  // ================================================================ 存在性 / 窗口 / 小工具

  /** 该 role 必须是本租户内、未删除主体上的 SUPPLIER 角色；否则 404（不泄露「存在但不属于你」）。 */
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

  /**
   * 指标窗口（与 6.1.1.1 同一套语义）：默认最近 365 天、上限 1095 天，实际生效区间回显在响应里。
   *
   * <p>质检窗口右端取 {@code to + 1 天的零点}：{@code inspected_at} 是 timestamptz 而窗口的 to 是一个时刻，
   * 直接比 {@code <= to} 会漏掉当天窗口之后的质检，而界面上显示的是「到 10-15」。
   */
  private record Window(
      LocalDate fromDate, LocalDate toDate, OffsetDateTime fromInstantStart, OffsetDateTime toInstantEnd,
      VendorListModels.VendorMetricWindow window) {}

  private Window resolveWindow(OffsetDateTime from, OffsetDateTime to) {
    var w = VendorListRules.metricWindow(from, to, OffsetDateTime.now(ZoneOffset.UTC));
    var fromDate = LocalDate.parse(w.from().substring(0, 10));
    var toDate = LocalDate.parse(w.to().substring(0, 10));
    return new Window(
        fromDate,
        toDate,
        fromDate.atStartOfDay().atOffset(ZoneOffset.UTC),
        toDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC),
        w);
  }

  /** {@code timestamptz} 读出口径：统一转成 UTC 的 ISO-8601 串（带 {@code Z}），不交给前端猜时区。 */
  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  /** NUMERIC → BigDecimal；空值归零。 */
  private static BigDecimal decimal(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static BigDecimal scale(long numerator, int scale, long denominator) {
    if (denominator == 0) return null;
    return BigDecimal.valueOf(numerator)
        .divide(BigDecimal.valueOf(denominator), scale, RoundingMode.HALF_UP);
  }

  private static long longOf(Record record, String column) {
    var value = record.get(column, Long.class);
    return value == null ? 0L : value;
  }

  private static int daysLate(LocalDate due, LocalDate received) {
    if (due == null || received == null) return 0;
    return (int) (received.toEpochDay() - due.toEpochDay());
  }

  private static String note(String value) {
    return value == null ? "" : value.trim();
  }

  /** 当前租户来自 {@code app.current_tenant}（由 AccessService 在认证上下文里设置），**不读任何请求头**。 */
  private UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid").get(0, UUID.class);
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

  private static org.springframework.web.server.ResponseStatusException badRequest(String message) {
    return new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.BAD_REQUEST, message);
  }
}
