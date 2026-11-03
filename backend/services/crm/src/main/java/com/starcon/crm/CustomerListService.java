package com.starcon.crm;

import static com.starcon.crm.CustomerListModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.crm.CustomerFactsPort.AftersalesCasePage;
import com.starcon.crm.CustomerFactsPort.AftersalesCaseView;
import com.starcon.crm.CustomerFactsPort.OrderPage;
import com.starcon.crm.CustomerFactsPort.OrderView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.3.1.1 客户列表。
 *
 * <p>蓝图 15 卷 15.3.1.1 原话：
 *
 * <blockquote>
 * 职责：客户列表：客户标识（按店铺）、来源店铺、首末购买时间、订单数、累计消费、退款率、客单价、标签、联系方式（脱敏）、风险标记。
 * 输入：筛选条件（店铺、标签、价值分层、风险）；用户数据范围。
 * 输出：客户列表 + 分层统计。
 * 关系：上游 = 9 订单；下游 = 15.3.1.2 详情、15.3.1.3 跨店合并结果（合并交付后列表按逻辑客户展示 ⇒ 下游增强，不是开工前置）。
 * <b>列表默认脱敏显示联系方式</b>，需要显式授权才完整展示。
 * </blockquote>
 *
 * <p><b>本类落五条纪律</b>：
 *
 * <ol>
 *   <li><b>客户是「按来源店铺」的</b>：主档身份是
 *       {@code (source_shop, customer_ref)}，**不是**逻辑客户。
 *       跨店合并（15.3.1.3）拥有逻辑客户与合并映射，本模块**不提前做** ——
 *       同一个人在两个来源下就是两行，这是**有意的**，不是 bug。</li>
 *   <li><b>判定基于「拉取到的事实」</b>：{@link CustomerFactsPort} 经 trade 的授权 HTTP API
 *       读订单与售后事实，<b>不直连对方库</b>；读不到就<b>显式失败</b>（503），
 *       绝不返回空集合冒充「上游没有客户」。</li>
 *   <li><b>金额按币种分开</b>：累计消费与客单价都按币种分行；
 *       跨币种筛选金额时**必须**同时给币种（否则「≥ 100」没有意义）。</li>
 *   <li><b>分页与统计各自独立</b>：列表是 {@code LIMIT/OFFSET + 稳定全序} 的真分页；
 *       分层统计来自**单独一条**统计查询，<b>不是</b>拿当前页聚合出来的。</li>
 *   <li><b>联系方式默认脱敏</b>：脱敏形态在<b>写库之前</b>算好落库；
 *       明文只在校验到 {@code crm.customers.pii.read} 且调用方显式要求时才返回。</li>
 * </ol>
 *
 * <p><b>跨服务纪律</b>：上游 9 订单 / 11 售后在 <b>trade</b> 服务，
 * 客户档案（联系方式）在 <b>catalog</b> 服务 —— 本服务<b>一条 SQL 都不碰</b>对方的库；
 * 本库只存逻辑引用 + 快照，<b>不建跨库 FK</b>。
 */
@Service
public class CustomerListService {

  // ================================================================ 常量

  /** 列表页最大条数（与本服务其它模块同一上限）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 刷新时默认的统计窗口（天）。 */
  static final int DEFAULT_WINDOW_DAYS = 365;

  /**
   * 订单回看天数。
   *
   * <p>售后单<b>不带买家维度</b>，只有 {@code orderId} ⇒ 归因要靠订单。
   * 若只拉窗口内的订单，「窗口内受理、但订单更早」的售后单就<b>归不到人</b>
   * （分母归零或被人为低估）。多拉一段回看窗口专门用来建归因索引，
   * <b>回看段的订单不进分母</b>（分母只算窗口内的订单）。
   */
  static final int ORDER_LOOKBACK_DAYS = 365;

  /** 一次刷新最多落多少位客户（超出则如实报 truncated）。 */
  static final int MAX_CUSTOMERS_PER_RUN = 5000;

  /**
   * 计入「退款率」的售后单类型。
   *
   * <p>⚠️ 换货 / 补发 / 维修**不算退货** —— 它们是「货有问题但没退回来」。
   * 口径照抄 15.3.2.1 客户标签（同服务、同一个客户口径），
   * 让「退款率」在每个租户、每一次运行里指的是同一件事。
   */
  static final Set<String> RETURN_CASE_TYPES = Set.of("RETURN_REFUND", "REFUND_ONLY");

  /**
   * 「退款率显著高于租户基线」的倍率。
   *
   * <p>照抄 15.3.2.1 的默认倍率（{@code baseline_multiplier} 默认 2）：
   * 只比「绝对值高」是不够的 —— 全平台都在涨退款率的时候，
   * 一个略高于均值的买家并不特殊。
   */
  static final BigDecimal REFUND_RATE_BASELINE_MULTIPLIER = new BigDecimal("2");

  /** 价值分层的 VIP 线：主币种累计消费 ≥ 租户同币种人均 × 3。 */
  static final BigDecimal VALUE_TIER_VIP_MULTIPLIER = new BigDecimal("3");

  /**
   * 判「退款率显著高于基线」的最低样本量。
   *
   * <p>照抄 15.3.2.1 的默认 {@code min_sample}：一个人只下一单、退了一次，
   * 退款率是 100% —— 那不是「退款率高的买家」，那是**样本不足**。
   * 样本不足时风险标记是 {@code INSUFFICIENT_SAMPLE}（**不可判定**），不是「没风险」。
   */
  static final int REFUND_RATE_MIN_SAMPLE = 5;

  /** 价值分层的 HIGH 线：主币种累计消费 ≥ 租户同币种人均。 */
  static final BigDecimal VALUE_TIER_HIGH_MULTIPLIER = BigDecimal.ONE;

  private final DSLContext sql;
  private final CustomerFactsPort facts;
  private final CustomerPartyPort parties;

  public CustomerListService(DSLContext sql, CustomerFactsPort facts, CustomerPartyPort parties) {
    this.sql = sql;
    this.facts = facts;
    this.parties = parties;
  }

  /** 判定依据快照的 JSON mapper：BigDecimal 按原样写出（不转成浮点）。 */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
          // 🔴 读回来也必须是 BigDecimal：默认会把 JSON 数字读成 double，
          // 那一行金额就会掉精度（禁浮点纪律的另一半）。
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  // ================================================================ 能力声明

  /**
   * 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 两个维度刻意是 {@code available=false}：
   *
   * <ul>
   *   <li>{@code logical_customer} —— 15.3.1.3 跨店合并<b>未交付</b>：
   *       本模块的客户是**按来源店铺**的，同一个人在两个来源下是两行。</li>
   *   <li>{@code customer_detail} —— 15.3.1.2 客户详情<b>未交付</b>：
   *       本模块只给列表与分层统计，详情的时间线由它自己聚合。</li>
   * </ul>
   */
  public CrmCustomerListCapabilities capabilities() {
    List<CrmCustomerListCapabilityDimension> dimensions =
        List.of(
            new CrmCustomerListCapabilityDimension(
                "customer_master",
                "客户主档（**按来源店铺**的客户标识、来源店铺、买家名快照）",
                true,
                "本服务 V432 crm_customer_profile",
                "",
                "客户身份是 (来源店铺, 客户标识)：同一个人在两个来源下是**两行**，"
                    + "合并由 15.3.1.3 负责（它的上游就是本模块）"),
            new CrmCustomerListCapabilityDimension(
                "purchase_metrics",
                "首末购买时间、订单数、累计消费、客单价（按币种分开）",
                true,
                "上游 trade 9.1.1.1 全渠道统一订单列表，经授权 HTTP API 只读"
                    + "（GET /trade/sales-orders/list）",
                "9",
                "金额**按币种分行**：USD 与 EUR 加起来不是任何一个金额；"
                    + "按金额筛选时**必须**同时给币种"),
            new CrmCustomerListCapabilityDimension(
                "refund_rate",
                "退款率（退款单数 ÷ 订单数）",
                true,
                "上游 trade 11.4.2.1 售后单列表，经授权 HTTP API 只读"
                    + "（GET /trade/aftersales/cases）",
                "11",
                "口径照抄 15.3.2.1：只计 RETURN_REFUND / REFUND_ONLY 两类；"
                    + "分母为 0 时是**不可判定**（null），不是 0%"),
            new CrmCustomerListCapabilityDimension(
                "tags",
                "标签（展示 + 按标签筛选）",
                true,
                "本服务 V295 crm_customer_tag_assignment（**只读**，本模块一个字都不写）",
                "15.3.2.1",
                "标签由 15.3.2.1 拥有；本模块只**读**它来展示与筛选"),
            new CrmCustomerListCapabilityDimension(
                "contact_masking",
                "联系方式（默认脱敏，显式授权才给明文）",
                true,
                "catalog 3.6.1.1 客户档案（GET /catalog/parties/{id}，只读）+ 人工登记",
                "",
                "明文联系方式需要额外的权限码 crm.customers.pii.read；"
                    + "没有它就只给脱敏形态（a***@***.com / 138****1234）"),
            new CrmCustomerListCapabilityDimension(
                "risk_flags",
                "风险标记（退款率显著高于基线 / 已登记生效中的退订）",
                true,
                "上游退款率 + 本服务 V299 crm_customer_opt_out（**只读**）",
                "",
                "风险标记**必须带原因**（DB CHECK 强制）；"
                    + "它**不触发**任何对买家的动作（不屏蔽、不拒绝、不降权）"),
            new CrmCustomerListCapabilityDimension(
                "segmentation_statistics",
                "分层统计（价值分层 / 来源店铺 / 风险标记 / 币种）",
                true,
                "本服务 V432 的**独立统计查询**（不受分页影响）",
                "",
                "统计**不是**拿当前页聚合出来的：翻到第 3 页看到的分层分布与第 1 页一样"),
            new CrmCustomerListCapabilityDimension(
                "logical_customer",
                "跨店合并后的逻辑客户",
                false,
                "**15.3.1.3 跨店合并未交付**",
                "15.3.1.3",
                "本模块**不假装**已跨店合并：列表按来源店铺展示，"
                    + "合并结果交付后由它增强（下游增强，不是开工前置）"),
            new CrmCustomerListCapabilityDimension(
                "customer_detail",
                "客户详情与统一时间线",
                false,
                "**15.3.1.2 客户详情未交付**",
                "15.3.1.2",
                "本模块**只给列表与分层统计**；订单 / 售后 / 消息 / 接触记录的时间线由它聚合"));
    return new CrmCustomerListCapabilities(
        dimensions,
        filterOptions(),
        java.util.Arrays.stream(CustomerValueTier.values()).map(Enum::name).toList(),
        java.util.Arrays.stream(CustomerRiskFlag.values()).map(Enum::name).toList(),
        "🔴 联系方式**默认脱敏**：邮箱 a***@***.com、手机 138****1234。"
            + "完整值需要**显式授权**（权限码 crm.customers.pii.read）**并**在请求里显式要求；"
            + "没有授权时即使显式要求也会 403，而不是悄悄给明文。",
        "本模块的客户是**按来源店铺**的客户标识，不是逻辑客户 —— 跨店合并由 15.3.1.3 拥有。"
            + "上游 9 订单 / 11 售后在 trade 服务、客户档案在 catalog 服务，"
            + "本服务**只经授权 HTTP API 读**，一条跨库 SQL / 跨库 FK / 跨库视图都没有。",
        "「退款率高」这类判断**要谨慎使用**：一个人只下一单、退了一次，退款率是 100% —— "
            + "那不是「退款率高的买家」，那是**样本不足**。"
            + "所以风险标记带样本量门槛、带租户基线、带原因与证据。");
  }

  /** 筛选项下拉用的租户级词表（全部来自真实数据 DISTINCT，不是硬编码常量列表）。 */
  public CrmCustomerListFilterOptions filterOptions() {
    List<String> shops =
        sql.fetch("select distinct source_shop from crm_customer_profile where tenant_id = ? order by source_shop",
                currentTenant())
            .map(r -> r.get(0, String.class));
    List<String> tiers =
        sql.fetch("select distinct value_tier from crm_customer_profile where tenant_id = ? order by value_tier",
                currentTenant())
            .map(r -> r.get(0, String.class));
    List<String> risks =
        sql.fetch("select distinct risk_flag from crm_customer_profile where tenant_id = ? order by risk_flag",
                currentTenant())
            .map(r -> r.get(0, String.class));
    List<String> currencies =
        sql.fetch("select distinct currency from crm_customer_amount where tenant_id = ? order by currency",
                currentTenant())
            .map(r -> r.get(0, String.class));
    List<String> tagCodes =
        sql.fetch("select distinct t.tag_code from crm_customer_tag t where t.tenant_id = ? order by t.tag_code",
                currentTenant())
            .map(r -> r.get(0, String.class));
    return new CrmCustomerListFilterOptions(shops, tiers, risks, currencies, tagCodes);
  }

  // ================================================================ 列表

  /**
   * 客户列表：真分页（{@code LIMIT/OFFSET} + 稳定全序）+ 与分页无关的分层统计。
   *
   * @param revealContact 调用方是否显式要求明文联系方式（还要**另有**权限才给）
   */
  public CrmCustomerListPage customerPage(
      String sourceShop,
      String tagCode,
      String valueTier,
      String riskFlag,
      String keyword,
      Integer minOrderCount,
      BigDecimal minTotalAmount,
      String currency,
      String sort,
      int page,
      int size,
      boolean revealContact,
      AccessService.Access actor) {

    // 🔴 明文联系方式是**两把锁**：一把是权限码，一把是调用方的显式要求。
    //   只有权限没有显式要求 ⇒ 不给明文（页面默认脱敏）；
    //   有显式要求没有权限 ⇒ 403，而不是悄悄退回脱敏（那会让调用方以为权限已生效）。
    if (revealContact && !actor.permissions().contains("crm.customers.pii.read")) {
      throw forbidden(
          "查看明文联系方式需要额外的权限码 crm.customers.pii.read："
              + "客户列表默认脱敏显示联系方式（a***@***.com / 138****1234），"
              + "完整值必须显式授权。");
    }

    UUID tagId = null;
    if (blankToNull(tagCode) != null) {
      var row =
          sql.fetchOne(
              "select id from crm_customer_tag where tenant_id = ? and tag_code = ?",
              currentTenant(),
              tagCode.trim());
      if (row == null) throw bad("标签编码不存在：" + tagCode.trim());
      tagId = row.get(0, UUID.class);
    }

    List<Object> binds = new ArrayList<>();
    String where = customerWhere(sourceShop, tagId, valueTier, riskFlag, keyword, minOrderCount,
        minTotalAmount, currency, binds);

    long total =
        sql.fetchOne("select count(*) from crm_customer_profile p" + where, binds.toArray())
            .get(0, Long.class);

    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);

    List<Object> orderBinds = new ArrayList<>(binds);
    String orderBy = customerOrderBy(sort, currency, orderBinds);
    List<Object> pageBinds = new ArrayList<>(orderBinds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);

    List<Record> records =
        sql.fetch(customerColumns() + where + orderBy + " limit ? offset ?", pageBinds.toArray());
    List<CrmCustomerListRow> rows = assemble(records, revealContact);

    CrmCustomerListStats stats = statistics(where, binds);

    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCustomerListPage(
        rows,
        safePage,
        safeSize,
        total,
        totalPages,
        stats,
        dataScopeOf(actor),
        "客户主档是**租户级**的（没有「归属人」这一维），"
            + "所以 SELF / DEPARTMENT 两种范围在客户列表上退化为租户级 —— "
            + "客户列表不按人切分，只有订单 / 工单这类有归属的单据才按人切。");
  }

  /** 客户列表的 WHERE 片段（列表与统计**共用同一份**，保证「统计的就是筛出来的」）。 */
  private String customerWhere(
      String sourceShop,
      UUID tagId,
      String valueTier,
      String riskFlag,
      String keyword,
      Integer minOrderCount,
      BigDecimal minTotalAmount,
      String currency,
      List<Object> binds) {
    StringBuilder where = new StringBuilder(" where p.tenant_id = ?");
    binds.add(currentTenant());
    if (blankToNull(sourceShop) != null) {
      where.append(" and p.source_shop = ?");
      binds.add(sourceShop.trim());
    }
    if (blankToNull(valueTier) != null) {
      where.append(" and p.value_tier = ?");
      binds.add(valueTier.trim().toUpperCase(Locale.ROOT));
    }
    if (blankToNull(riskFlag) != null) {
      where.append(" and p.risk_flag = ?");
      binds.add(riskFlag.trim().toUpperCase(Locale.ROOT));
    }
    if (blankToNull(keyword) != null) {
      where.append(
          " and (strpos(lower(p.customer_ref), lower(?)) > 0 or strpos(lower(p.customer_name), lower(?)) > 0)");
      binds.add(keyword.trim());
      binds.add(keyword.trim());
    }
    if (minOrderCount != null) {
      where.append(" and p.order_count >= ?");
      binds.add(minOrderCount);
    }
    if (tagId != null) {
      where.append(
          " and exists (select 1 from crm_customer_tag_assignment a"
              + " where a.tenant_id = p.tenant_id and a.customer_ref = p.customer_ref"
              + " and a.state = 'ACTIVE' and a.tag_id = ?)");
      binds.add(tagId);
    }
    if (minTotalAmount != null) {
      // 🔴 按金额筛选**必须**给币种：USD 与 EUR 加起来不是任何一个金额，
      //   「累计消费 ≥ 100」在没有币种时**没有意义**。
      if (blankToNull(currency) == null) {
        throw bad("按累计消费筛选时必须同时给出币种（currency）：跨币种的金额不能相加。");
      }
      where.append(
          " and exists (select 1 from crm_customer_amount a"
              + " where a.tenant_id = p.tenant_id and a.customer_id = p.id"
              + " and a.currency = ? and a.total_amount >= ?)");
      binds.add(currency.trim().toUpperCase(Locale.ROOT));
      binds.add(minTotalAmount);
    }
    return where.toString();
  }

  /** 稳定全序排序（同一组键在多次请求之间给出**同一个**顺序）。 */
  private String customerOrderBy(String sort, String currency, List<Object> binds) {
    String key = blankToNull(sort) == null ? "ORDER_COUNT_DESC" : sort.trim().toUpperCase(Locale.ROOT);
    switch (key) {
      case "ORDER_COUNT_DESC":
        return " order by p.order_count desc, p.customer_ref asc, p.source_shop asc";
      case "ORDER_COUNT_ASC":
        return " order by p.order_count asc, p.customer_ref asc, p.source_shop asc";
      case "REFUND_RATE_DESC":
        return " order by p.refund_rate desc nulls last, p.customer_ref asc, p.source_shop asc";
      case "LAST_ORDER_AT_DESC":
        return " order by p.last_order_at desc nulls last, p.customer_ref asc, p.source_shop asc";
      case "CONSUMPTION_DESC":
      case "CONSUMPTION_ASC": {
        if (blankToNull(currency) == null) {
          throw bad("按累计消费排序时必须同时给出币种（currency）：跨币种的金额不能相加。");
        }
        binds.add(currency.trim().toUpperCase(Locale.ROOT));
        String direction = "CONSUMPTION_DESC".equals(key) ? "desc" : "asc";
        return " order by (select a.total_amount from crm_customer_amount a"
            + " where a.tenant_id = p.tenant_id and a.customer_id = p.id and a.currency = ?)"
            + " " + direction + " nulls last, p.customer_ref asc, p.source_shop asc";
      }
      default:
        throw bad(
            "排序维度只能是 ORDER_COUNT_DESC / ORDER_COUNT_ASC / CONSUMPTION_DESC /"
                + " CONSUMPTION_ASC / REFUND_RATE_DESC / LAST_ORDER_AT_DESC。");
    }
  }

  /**
   * 分层统计 —— <b>单独一条一条的统计查询</b>，与分页无关。
   *
   * <p>🔴 关键：这些数字**不是**拿当前页的 {@code rows} 聚合出来的。
   * 拿当前页聚合会得到「翻到第 3 页，分层分布就变了」的假象 ——
   * 而运营正是靠这个分布判断要不要做定向运营。
   */
  private CrmCustomerListStats statistics(String where, List<Object> binds) {
    var totals =
        sql.fetchOne(
            "select count(*), coalesce(sum(p.order_count),0), coalesce(sum(p.refund_case_count),0)"
                + " from crm_customer_profile p" + where,
            binds.toArray());
    long customerCount = totals.get(0, Long.class);
    long orderCount = totals.get(1, Long.class);
    long refundCases = totals.get(2, Long.class);

    List<CrmCustomerListTierBucket> byTier =
        sql.fetch(
                "select p.value_tier, count(*), coalesce(sum(p.order_count),0)"
                    + " from crm_customer_profile p" + where + " group by p.value_tier order by p.value_tier",
                binds.toArray())
            .map(
                r ->
                    new CrmCustomerListTierBucket(
                        r.get(0, String.class),
                        valueTierLabel(r.get(0, String.class)),
                        r.get(1, Long.class),
                        r.get(2, Long.class)));

    List<CrmCustomerListShopBucket> byShop =
        sql.fetch(
                "select p.source_shop, count(*), coalesce(sum(p.order_count),0)"
                    + " from crm_customer_profile p" + where
                    + " group by p.source_shop order by p.source_shop",
                binds.toArray())
            .map(
                r ->
                    new CrmCustomerListShopBucket(
                        r.get(0, String.class), r.get(1, Long.class), r.get(2, Long.class)));

    List<CrmCustomerListRiskBucket> byRisk =
        sql.fetch(
                "select p.risk_flag, count(*), coalesce(sum(p.order_count),0)"
                    + " from crm_customer_profile p" + where + " group by p.risk_flag order by p.risk_flag",
                binds.toArray())
            .map(
                r ->
                    new CrmCustomerListRiskBucket(
                        r.get(0, String.class),
                        riskFlagLabel(r.get(0, String.class)),
                        r.get(1, Long.class),
                        r.get(2, Long.class)));

    // 金额按币种分开统计（跨币种不相加）。
    List<Object> amountBinds = new ArrayList<>();
    amountBinds.add(currentTenant());
    amountBinds.addAll(binds);
    List<CrmCustomerListCurrencyAmount> amounts =
        sql.fetch(
                "select a.currency, sum(a.total_amount), count(*)"
                    + " from crm_customer_amount a"
                    + " where a.tenant_id = ? and a.customer_id in"
                    + " (select p.id from crm_customer_profile p" + where + ")"
                    + " group by a.currency order by a.currency",
                amountBinds.toArray())
            .map(
                r ->
                    new CrmCustomerListCurrencyAmount(
                        r.get(0, String.class), r.get(1, BigDecimal.class), r.get(2, Long.class)));

    return new CrmCustomerListStats(
        customerCount,
        orderCount,
        refundCases,
        byTier,
        byShop,
        byRisk,
        amounts,
        "统计口径：分层统计是**独立查询**的结果，**不受分页影响** —— "
            + "翻到第 3 页看到的分布与第 1 页完全一样。"
            + "金额按币种分开统计：USD 与 EUR 加起来不是任何一个金额。"
            + "客户是**按来源店铺**的：同一个人在两个来源下算两位客户。");
  }

  /** 把查出来的行装配成传输模型（挂上按币种的金额与标签）。 */
  private List<CrmCustomerListRow> assemble(List<Record> records, boolean revealContact) {
    List<CrmCustomerListRow> rows = new ArrayList<>();
    if (records.isEmpty()) return rows;

    List<UUID> ids = new ArrayList<>();
    List<String> refs = new ArrayList<>();
    for (Record r : records) {
      ids.add(r.get("id", UUID.class));
      refs.add(r.get("customer_ref", String.class));
    }

    Map<UUID, List<CrmCustomerListAmount>> amountsByCustomer = new HashMap<>();
    for (Record r :
        sql.fetch(
            "select a.customer_id, a.currency, a.total_amount, a.order_count, a.avg_order_amount"
                + " from crm_customer_amount a where a.tenant_id = ? and a.customer_id in ("
                + placeholders(ids.size()) + ") order by a.customer_id, a.currency",
            bindAll(currentTenant(), ids))) {
      amountsByCustomer
          .computeIfAbsent(r.get("customer_id", UUID.class), k -> new ArrayList<>())
          .add(
              new CrmCustomerListAmount(
                  r.get("currency", String.class),
                  r.get("total_amount", BigDecimal.class),
                  r.get("order_count", Integer.class),
                  r.get("avg_order_amount", BigDecimal.class)));
    }

    Map<String, List<CrmCustomerListTag>> tagsByCustomer = new HashMap<>();
    for (Record r :
        sql.fetch(
            "select a.customer_ref, t.tag_code, t.name, t.category"
                + " from crm_customer_tag_assignment a"
                + " join crm_customer_tag t on t.tenant_id = a.tenant_id and t.id = a.tag_id"
                + " where a.tenant_id = ? and a.state = 'ACTIVE' and a.customer_ref in ("
                + placeholders(refs.size()) + ") order by a.customer_ref, t.tag_code",
            bindAll(currentTenant(), refs))) {
      tagsByCustomer
          .computeIfAbsent(r.get("customer_ref", String.class), k -> new ArrayList<>())
          .add(
              new CrmCustomerListTag(
                  r.get("tag_code", String.class),
                  r.get("name", String.class),
                  r.get("category", String.class),
                  tagCategoryLabel(r.get("category", String.class))));
    }

    for (Record r : records) {
      UUID id = r.get("id", UUID.class);
      List<CrmCustomerListAmount> amounts = amountsByCustomer.getOrDefault(id, List.of());
      CrmCustomerListAmount primary = primaryAmount(amounts);
      String tier = nz(r.get("value_tier", String.class));
      String risk = nz(r.get("risk_flag", String.class));
      boolean masked = !revealContact;
      rows.add(
          new CrmCustomerListRow(
              id,
              nz(r.get("customer_ref", String.class)),
              nz(r.get("source_shop", String.class)),
              nz(r.get("customer_name", String.class)),
              r.get("first_order_at", OffsetDateTime.class) == null
                  ? null
                  : r.get("first_order_at", OffsetDateTime.class).toString(),
              r.get("last_order_at", OffsetDateTime.class) == null
                  ? null
                  : r.get("last_order_at", OffsetDateTime.class).toString(),
              r.get("order_count", Integer.class),
              r.get("refund_case_count", Integer.class),
              r.get("refund_rate", BigDecimal.class),
              tier,
              valueTierLabel(tier),
              risk,
              riskFlagLabel(risk),
              nz(r.get("risk_reason", String.class)),
              amounts,
              primary == null ? null : primary.totalAmount(),
              primary == null ? null : primary.currency(),
              primary == null ? null : primary.avgOrderAmount(),
              tagsByCustomer.getOrDefault(nz(r.get("customer_ref", String.class)), List.of()),
              masked ? nz(r.get("contact_email_masked", String.class)) : nz(r.get("contact_email", String.class)),
              masked ? nz(r.get("contact_phone_masked", String.class)) : nz(r.get("contact_phone", String.class)),
              masked,
              nz(r.get("contact_source", String.class)),
              contactSourceLabel(r.get("contact_source", String.class)),
              nz(r.get("opt_out_state", String.class)),
              optOutStateLabel(r.get("opt_out_state", String.class)),
              r.get("window_from", OffsetDateTime.class) == null
                  ? null
                  : r.get("window_from", OffsetDateTime.class).toString(),
              r.get("window_to", OffsetDateTime.class) == null
                  ? null
                  : r.get("window_to", OffsetDateTime.class).toString(),
              r.get("synced_at", OffsetDateTime.class) == null
                  ? null
                  : r.get("synced_at", OffsetDateTime.class).toString()));
    }
    return rows;
  }

  /** 主币种金额：订单数最多的那个币种（并列时取累计消费更大的那个）。 */
  private static CrmCustomerListAmount primaryAmount(List<CrmCustomerListAmount> amounts) {
    CrmCustomerListAmount best = null;
    for (CrmCustomerListAmount amount : amounts) {
      if (best == null
          || amount.orderCount() > best.orderCount()
          || (amount.orderCount() == best.orderCount()
              && nz(amount.totalAmount()).compareTo(nz(best.totalAmount())) > 0)) {
        best = amount;
      }
    }
    return best;
  }

  // ================================================================ 刷新（从上游拉事实）

  /**
   * 从上游刷新客户档案。
   *
   * <p>🔴 三条纪律：
   *
   * <ol>
   *   <li>上游订单 / 售后事实读不到 ⇒ <b>抛 503</b>，一条客户档案都不落
   *       （绝不返回空集合冒充「上游没有客户」）。</li>
   *   <li>统计窗口的起止**成对**给；只给一个会让窗口两端对不上。</li>
   *   <li>联系方式（catalog）读不到 ⇒ 该客户的联系方式留空，并在运行记录里
   *       <b>如实报出没取到几个</b>；订单 / 退款的**核心事实**读不到才整体失败。</li>
   * </ol>
   */
  @Transactional
  public CrmCustomerListSyncResult sync(
      CrmCustomerListSyncInput input, UUID actorId, String bearer) {
    OffsetDateTime startedAt = now();
    OffsetDateTime windowTo = startedAt;
    OffsetDateTime windowFrom = startedAt.minusDays(DEFAULT_WINDOW_DAYS);
    String fromText = blankToNull(input == null ? null : input.windowFrom());
    String toText = blankToNull(input == null ? null : input.windowTo());
    if ((fromText == null) != (toText == null)) {
      throw bad("统计窗口的起止必须**成对**给：只给一个会让窗口的两端对不上。");
    }
    if (fromText != null) {
      windowFrom = parseMoment(fromText, "窗口起点");
      windowTo = parseMoment(toText, "窗口终点");
      if (!windowFrom.isBefore(windowTo)) throw bad("窗口起点必须早于终点。");
    }

    FactScan scan = scanFacts(windowFrom, windowTo, bearer);

    // 🔴 一次刷新最多落 MAX_CUSTOMERS_PER_RUN 位客户；超出则**如实报 truncated**，
    //   而不是悄悄少算（少算的那部分会让分层统计的分母变小，运营会以为那就是全量）。
    List<CustomerAggregate> aggregates = new ArrayList<>(scan.customers().values());
    boolean truncated = scan.truncated();
    if (aggregates.size() > MAX_CUSTOMERS_PER_RUN) {
      aggregates = aggregates.subList(0, MAX_CUSTOMERS_PER_RUN);
      truncated = true;
    }

    // 联系方式补齐（补充维度，读不到不影响核心事实）。
    int contactResolved = 0;
    int contactUnavailable = 0;
    String contactFailure = "";
    for (CustomerAggregate aggregate : aggregates) {
      if (aggregate.partyId == null) {
        contactUnavailable++;
        continue;
      }
      CustomerPartyPort.ContactLookup lookup = parties.contact(aggregate.partyId, bearer);
      if (lookup.hasContact()) {
        aggregate.contactEmail = nz(lookup.email()).trim();
        aggregate.contactPhone = nz(lookup.phone()).trim();
        aggregate.contactSource = "CATALOG_PARTY";
        contactResolved++;
      } else {
        contactUnavailable++;
        if (!lookup.unavailableReason().isBlank() && contactFailure.isBlank()) {
          contactFailure = lookup.unavailableReason();
        }
      }
    }

    // 落库：先写主档与金额（价值分层与风险标记在第二轮写，因为它们依赖租户基线）。
    BigDecimal refundBaseline = scan.baseline();

    for (CustomerAggregate aggregate : aggregates) {
      writeProfile(aggregate, windowFrom, windowTo);
      writeAmounts(aggregate);
    }

    // 🔴 价值分层的基线**必须在金额落库之后**算：
    //   先算基线再落库的话，第一次刷新时基线是空的 ⇒ 所有人都是「常规」，
    //   而分层统计看上去又「有值」—— 那是最难发现的一种错。
    Map<String, BigDecimal> tierBaseline = tierBaselines();

    for (CustomerAggregate aggregate : aggregates) {
      String tier = valueTier(aggregate, tierBaseline);
      int orderCount = aggregate.windowOrders().size();
      boolean enoughSample = orderCount >= REFUND_RATE_MIN_SAMPLE;
      boolean highReturn =
          enoughSample
              && refundBaseline != null
              && aggregate.refundRate() != null
              && aggregate.refundRate().compareTo(refundBaseline.multiply(REFUND_RATE_BASELINE_MULTIPLIER)) >= 0;
      boolean optedOut = scan.optOutRefs().contains(aggregate.customerRef);
      boolean insufficient = !enoughSample && aggregate.returnCases() > 0;
      String riskFlag = riskFlag(highReturn, optedOut, insufficient);
      String riskReason =
          riskReason(highReturn, optedOut, insufficient, aggregate, refundBaseline);
      writeTierAndRisk(
          aggregate, tier, riskFlag, riskReason, optedOut ? "ACTIVE" : scan.optOutStateOf(aggregate.customerRef));
    }

    UUID runId = UUID.randomUUID();
    String runNo = nextRunNo();

    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("windowFrom", windowFrom.toString());
    facts.put("windowTo", windowTo.toString());
    facts.put("scannedOrderCount", scan.scannedOrders());
    facts.put("scannedCaseCount", scan.scannedCases());
    facts.put("windowOrderCount", scan.windowOrders());
    facts.put("windowCaseCount", scan.windowCases());
    facts.put("unattributedCaseCount", scan.unattributedCases());
    facts.put("customerCount", aggregates.size());
    facts.put("countedCaseTypes", new ArrayList<>(RETURN_CASE_TYPES.stream().sorted().toList()));
    facts.put("orderLookbackDays", ORDER_LOOKBACK_DAYS);
    facts.put("refundRateBaseline", refundBaseline);
    facts.put("refundRateBaselineMultiplier", REFUND_RATE_BASELINE_MULTIPLIER);
    facts.put("valueTierBaselines", new LinkedHashMap<>(tierBaseline));
    facts.put("contactResolvedCount", contactResolved);
    facts.put("contactUnavailableCount", contactUnavailable);
    if (!contactFailure.isBlank()) facts.put("contactUnavailableReason", contactFailure);

    String failureReason = "";
    if (!contactFailure.isBlank()) {
      failureReason = "联系方式未全部补齐：" + contactFailure + "（订单与退款事实不受影响）";
    }
    if (truncated && !failureReason.isBlank()) {
      failureReason = failureReason + "；🔴 本次扫描**被截断**，名单不是全量";
    } else if (truncated) {
      failureReason = "🔴 本次扫描**被截断**，名单不是全量";
    }

    sql.execute(
        "insert into crm_customer_sync_run"
            + "(id,tenant_id,run_no,status,window_from,window_to,customer_count,order_count,"
            + " case_count,unattributed_case_count,contact_resolved_count,contact_unavailable_count,"
            + " truncated,baseline,failure_reason,facts,operator_id,started_at,finished_at,created_at)"
            + " values (?,?,?,'SUCCEEDED',?::timestamptz,?::timestamptz,?,?,?,?,?,?,?,?,?,?::jsonb,?,"
            + "?::timestamptz,?::timestamptz,?::timestamptz)",
        runId,
        currentTenant(),
        runNo,
        windowFrom,
        windowTo,
        aggregates.size(),
        scan.windowOrders(),
        scan.windowCases(),
        scan.unattributedCases(),
        contactResolved,
        contactUnavailable,
        truncated,
        refundBaseline,
        failureReason,
        toJson(facts),
        actorId,
        startedAt,
        now(),
        now());

    return new CrmCustomerListSyncResult(
        runId,
        runNo,
        "SUCCEEDED",
        windowFrom.toString(),
        windowTo.toString(),
        aggregates.size(),
        scan.windowOrders(),
        scan.windowCases(),
        scan.unattributedCases(),
        contactResolved,
        contactUnavailable,
        truncated,
        refundBaseline,
        failureReason,
        startedAt.toString(),
        now().toString(),
        "客户档案来自上游 trade 的订单与售后事实（授权 HTTP API 只读），"
            + "落库后由本服务按租户隔离读取。"
            + (truncated ? " 🔴 本次扫描**被截断**（扫到上限了），名单不是全量。" : "")
            + (scan.unattributedCases() > 0
                ? " 有 " + scan.unattributedCases() + " 张售后单归不到客户"
                    + "（其订单不在扫描窗口内）—— 这一部分没有进入任何人的退款率。"
                : ""));
  }

  /**
   * 扫描上游订单与售后事实，聚合成「（来源店铺, 客户）→ 事实」。
   *
   * <p>🔴 上游读不到 ⇒ <b>抛 503</b>，绝不返回空集合冒充「上游没有客户」。
   */
  private FactScan scanFacts(OffsetDateTime windowFrom, OffsetDateTime windowTo, String bearer) {
    OffsetDateTime orderFrom = windowFrom.minusDays(ORDER_LOOKBACK_DAYS);
    List<OrderView> orders = new ArrayList<>();
    boolean truncated = false;
    int page = 0;
    while (true) {
      OrderPage p = facts.orders(orderFrom, windowTo, page, bearer);
      orders.addAll(p.rows());
      long consumed = (long) (page + 1) * CustomerFactsPort.PAGE_SIZE;
      if (p.rows().isEmpty() || consumed >= p.total()) break;
      page++;
      if (page >= CustomerFactsPort.MAX_PAGES) {
        truncated = true;
        break;
      }
    }
    List<AftersalesCaseView> cases = new ArrayList<>();
    page = 0;
    while (true) {
      AftersalesCasePage p = facts.aftersalesCases(windowFrom, windowTo, page, bearer);
      cases.addAll(p.rows());
      long consumed = (long) (page + 1) * CustomerFactsPort.PAGE_SIZE;
      if (p.rows().isEmpty() || consumed >= p.total()) break;
      page++;
      if (page >= CustomerFactsPort.MAX_PAGES) {
        truncated = true;
        break;
      }
    }

    // 归因索引：orderId → 客户（**只认带 customer_party_id 的订单**）。
    Map<UUID, CustomerAggregate> orderOwner = new HashMap<>();
    Map<String, CustomerAggregate> aggregates = new LinkedHashMap<>();
    for (OrderView order : orders) {
      if (order.id() == null || order.customerPartyId() == null) continue;
      String shop = nz(order.orderSource()).trim();
      if (shop.isEmpty()) shop = "UNKNOWN";
      final String sourceShop = shop;
      String ref = order.customerPartyId().toString();
      String key = key(sourceShop, ref);
      CustomerAggregate aggregate =
          aggregates.computeIfAbsent(key, k -> new CustomerAggregate(sourceShop, ref));
      aggregate.partyId = order.customerPartyId();
      aggregate.add(order);
      orderOwner.put(order.id(), aggregate);
    }
    int unattributed = 0;
    for (AftersalesCaseView c : cases) {
      if (c.orderId() == null) {
        unattributed++;
        continue;
      }
      CustomerAggregate aggregate = orderOwner.get(c.orderId());
      if (aggregate == null) {
        unattributed++;
        continue;
      }
      aggregate.addCase(c);
    }

    // 窗口内订单数（分母）—— 窗口外的订单只用来建归因索引，**不进分母**。
    for (CustomerAggregate aggregate : aggregates.values()) {
      aggregate.markWindow(windowFrom, windowTo);
    }

    int windowOrders = 0;
    int windowCases = 0;
    for (CustomerAggregate aggregate : aggregates.values()) {
      windowOrders += aggregate.windowOrders().size();
      windowCases += aggregate.returnCases();
    }
    BigDecimal baseline =
        windowOrders <= 0
            ? null
            : BigDecimal.valueOf(windowCases)
                .divide(BigDecimal.valueOf(windowOrders), 6, RoundingMode.HALF_UP);

    // 退订状态（**只读** 15.3.2.2 的表，快照到主档行）。
    Map<String, String> optOutState = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select customer_ref, state, recorded_at from crm_customer_opt_out"
                + " where tenant_id = ? order by customer_ref, recorded_at desc",
            currentTenant())) {
      optOutState.putIfAbsent(r.get("customer_ref", String.class), r.get("state", String.class));
    }
    Set<String> optedOut = new LinkedHashSet<>();
    for (var entry : optOutState.entrySet()) {
      if ("ACTIVE".equals(entry.getValue())) optedOut.add(entry.getKey());
    }

    return new FactScan(
        aggregates,
        orders.size(),
        cases.size(),
        unattributed,
        windowOrders,
        windowCases,
        baseline,
        truncated,
        optedOut,
        optOutState);
  }

  /** 写客户主档行（身份、订单侧事实与统计窗口）。 */
  private void writeProfile(
      CustomerAggregate aggregate, OffsetDateTime windowFrom, OffsetDateTime windowTo) {
    OffsetDateTime moment = now();
    try {
      sql.execute(
          "insert into crm_customer_profile"
              + "(id,tenant_id,customer_ref,source_shop,customer_party_id,customer_name,"
              + " first_order_at,last_order_at,order_count,refund_case_count,refund_rate,"
              + " value_tier,risk_flag,risk_reason,window_from,window_to,synced_at,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?, 'LOW','NONE','',"
              + " ?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz)"
              + " on conflict (tenant_id,source_shop,customer_ref) do update set"
              + " customer_party_id = excluded.customer_party_id,"
              + " customer_name = excluded.customer_name,"
              + " first_order_at = excluded.first_order_at,"
              + " last_order_at = excluded.last_order_at,"
              + " order_count = excluded.order_count,"
              + " refund_case_count = excluded.refund_case_count,"
              + " refund_rate = excluded.refund_rate,"
              + " window_from = excluded.window_from,"
              + " window_to = excluded.window_to,"
              + " synced_at = excluded.synced_at,"
              + " updated_at = excluded.updated_at",
          UUID.randomUUID(),
          currentTenant(),
          aggregate.customerRef,
          aggregate.sourceShop,
          aggregate.partyId,
          aggregate.customerName,
          aggregate.firstOrderAt,
          aggregate.lastOrderAt,
          aggregate.windowOrders().size(),
          aggregate.returnCases(),
          aggregate.refundRate(),
          windowFrom,
          windowTo,
          moment,
          moment,
          moment);
    } catch (DataIntegrityViolationException e) {
      throw new IllegalStateException("客户主档写入冲突：" + aggregate.customerRef, e);
    }
  }

  /** 写按币种分开的累计消费与客单价。 */
  private void writeAmounts(CustomerAggregate aggregate) {
    UUID customerId = profileIdOf(aggregate);
    for (Map.Entry<String, BigDecimal> entry : aggregate.amountsByCurrency().entrySet()) {
      String currency = entry.getKey();
      BigDecimal total = entry.getValue();
      int orderCount = aggregate.ordersByCurrency().getOrDefault(currency, 0);
      BigDecimal avg =
          orderCount <= 0 ? null : total.divide(BigDecimal.valueOf(orderCount), 4, RoundingMode.HALF_UP);
      OffsetDateTime moment = now();
      sql.execute(
          "insert into crm_customer_amount"
              + "(id,tenant_id,customer_id,currency,total_amount,order_count,avg_order_amount,"
              + " created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)"
              + " on conflict (tenant_id,customer_id,currency) do update set"
              + " total_amount = excluded.total_amount,"
              + " order_count = excluded.order_count,"
              + " avg_order_amount = excluded.avg_order_amount,"
              + " updated_at = excluded.updated_at",
          UUID.randomUUID(),
          currentTenant(),
          customerId,
          currency,
          total,
          orderCount,
          avg,
          moment,
          moment);
    }
  }

  /** 写价值分层、风险标记、退订状态与联系方式。 */
  private void writeTierAndRisk(
      CustomerAggregate aggregate, String tier, String riskFlag, String riskReason, String optOutState) {
    UUID customerId = profileIdOf(aggregate);
    sql.execute(
        "update crm_customer_profile set value_tier = ?, risk_flag = ?, risk_reason = ?,"
            + " opt_out_state = ?, updated_at = ?::timestamptz where tenant_id = ? and id = ?",
        tier,
        riskFlag,
        riskReason,
        optOutState,
        now(),
        currentTenant(),
        customerId);
    // 🔴 联系方式**只在真的取到的时候才写**：
    //   catalog 读不到时**不能**把人工登记过的联系方式清空 —— 那是把一份还能用的数据弄没了。
    if (!aggregate.contactEmail.isBlank() || !aggregate.contactPhone.isBlank()) {
      sql.execute(
          "update crm_customer_profile set contact_email = ?, contact_phone = ?,"
              + " contact_email_masked = ?, contact_phone_masked = ?, contact_source = ?,"
              + " updated_at = ?::timestamptz where tenant_id = ? and id = ?",
          aggregate.contactEmail,
          aggregate.contactPhone,
          maskEmail(aggregate.contactEmail),
          maskPhone(aggregate.contactPhone),
          aggregate.contactSource,
          now(),
          currentTenant(),
          customerId);
    }
  }

  /** 客户主档行的 id（按身份列查回来）。 */
  private UUID profileIdOf(CustomerAggregate aggregate) {
    var row =
        sql.fetchOne(
            "select id from crm_customer_profile where tenant_id = ? and source_shop = ? and customer_ref = ?",
            currentTenant(),
            aggregate.sourceShop,
            aggregate.customerRef);
    if (row == null) throw new IllegalStateException("客户主档行缺失：" + aggregate.customerRef);
    return row.get(0, UUID.class);
  }

  /**
   * 租户基线：每个币种上「人均累计消费」。
   *
   * <p>口径：{@code avg(total_amount)} over {@code crm_customer_amount}（租户内全部客户行）。
   * 用**租户自己的**真实分布，而不是写死的数字 —— ���写死的门槛在新店开张时会把
   * 每一个客户都判成「高价值」。
   */
  private Map<String, BigDecimal> tierBaselines() {
    Map<String, BigDecimal> out = new TreeMap<>();
    for (Record r :
        sql.fetch(
            "select currency, avg(total_amount) from crm_customer_amount where tenant_id = ? group by currency",
            currentTenant())) {
      BigDecimal avg = r.get(1, BigDecimal.class);
      if (avg != null) out.put(r.get(0, String.class), avg);
    }
    return out;
  }

  /** 价值分层：主币种累计消费相对租户同币种人均的倍数。 */
  private String valueTier(CustomerAggregate aggregate, Map<String, BigDecimal> baselines) {
    CrmCustomerListAmount primary = primaryAmountOf(aggregate);
    if (primary == null || primary.totalAmount().signum() <= 0) return "LOW";
    BigDecimal baseline = baselines.get(primary.currency());
    if (baseline == null || baseline.signum() <= 0) return "NORMAL";
    BigDecimal ratio = primary.totalAmount().divide(baseline, 6, RoundingMode.HALF_UP);
    if (ratio.compareTo(VALUE_TIER_VIP_MULTIPLIER) >= 0) return "VIP";
    if (ratio.compareTo(VALUE_TIER_HIGH_MULTIPLIER) >= 0) return "HIGH";
    return "NORMAL";
  }

  private static CrmCustomerListAmount primaryAmountOf(CustomerAggregate aggregate) {
    CrmCustomerListAmount best = null;
    for (Map.Entry<String, BigDecimal> entry : aggregate.amountsByCurrency().entrySet()) {
      int orders = aggregate.ordersByCurrency().getOrDefault(entry.getKey(), 0);
      CrmCustomerListAmount candidate =
          new CrmCustomerListAmount(entry.getKey(), entry.getValue(), orders, null);
      if (best == null
          || candidate.orderCount() > best.orderCount()
          || (candidate.orderCount() == best.orderCount()
              && candidate.totalAmount().compareTo(best.totalAmount()) > 0)) {
        best = candidate;
      }
    }
    return best;
  }

  private static String riskFlag(boolean highReturn, boolean optedOut, boolean insufficientSample) {
    if (highReturn && optedOut) return "HIGH_RETURN_RATE_AND_OPT_OUT";
    if (highReturn) return "HIGH_RETURN_RATE";
    if (optedOut) return "OPT_OUT";
    if (insufficientSample) return "INSUFFICIENT_SAMPLE";
    return "NONE";
  }

  /** 风险标记的原因（**必须**有：没有原因的风险标记没法复核）。 */
  private static String riskReason(
      boolean highReturn,
      boolean optedOut,
      boolean insufficientSample,
      CustomerAggregate aggregate,
      BigDecimal baseline) {
    List<String> parts = new ArrayList<>();
    if (highReturn) {
      parts.add(
          "退款率 "
              + plain(aggregate.refundRate())
              + "（"
              + aggregate.returnCases()
              + " / "
              + aggregate.windowOrders().size()
              + " 单）达到租户基线 "
              + plain(baseline)
              + " × "
              + plain(REFUND_RATE_BASELINE_MULTIPLIER)
              + "。");
    }
    if (optedOut) {
      parts.add("客户已登记生效中的退订（15.3.2.2 接触记录里的退订状态）。");
    }
    if (insufficientSample) {
      parts.add(
          "退款率 **不可判定**：统计窗口内只有 "
              + aggregate.windowOrders().size()
              + " 单，低于最低样本量 "
              + REFUND_RATE_MIN_SAMPLE
              + " 单。样本不足时不能说「是」，也不能说「不是」。");
    }
    return String.join(" ", parts);
  }

  // ================================================================ 联系方式登记

  /**
   * 登记 / 修正联系方式（人工路径）。
   *
   * <p>客户必须**已经存在**于本租户的客户主档里（按来源店铺 + 客户标识定位）。
   */
  @Transactional
  public CrmCustomerListRow registerContact(
      CrmCustomerListContactInput input, UUID actorId, boolean revealContact) {
    String ref = nz(input.customerRef()).trim();
    String shop = nz(input.sourceShop()).trim();
    if (ref.isEmpty() || shop.isEmpty()) {
      throw bad("必须同时给出客户标识与来源店铺：客户是**按来源店铺**标识的。");
    }
    String email = nz(input.email()).trim();
    String phone = nz(input.phone()).trim();
    if (email.isEmpty() && phone.isEmpty()) {
      throw bad("邮箱与电话至少给一项：两个都空等于没有联系方式。");
    }
    var row =
        sql.fetchOne(
            "select id from crm_customer_profile where tenant_id = ? and source_shop = ? and customer_ref = ?",
            currentTenant(),
            shop,
            ref);
    if (row == null) {
      throw notFound(
          "该来源店铺下没有这位客户（来源店铺 "
              + shop
              + "，客户标识 "
              + ref
              + "）：请先刷新客户档案，或确认来源店铺填对了。");
    }
    UUID customerId = row.get(0, UUID.class);
    sql.execute(
        "update crm_customer_profile set contact_email = ?, contact_phone = ?,"
            + " contact_email_masked = ?, contact_phone_masked = ?, contact_source = 'MANUAL',"
            + " updated_at = ?::timestamptz where tenant_id = ? and id = ?",
        email,
        phone,
        maskEmail(email),
        maskPhone(phone),
        now(),
        currentTenant(),
        customerId);

    var record =
        sql.fetchOne(customerColumns() + " where p.tenant_id = ? and p.id = ?", currentTenant(), customerId);
    return assemble(List.of(record), revealContact).get(0);
  }

  // ================================================================ 刷新运行记录

  /** 刷新运行记录列表（真分页）。 */
  public CrmCustomerListSyncRunPage runPage(int page, int size) {
    long total =
        sql.fetchOne("select count(*) from crm_customer_sync_run where tenant_id = ?", currentTenant())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<CrmCustomerListSyncRunRow> rows =
        sql.fetch(
                "select id,tenant_id,run_no,status,window_from,window_to,customer_count,order_count,"
                    + " case_count,unattributed_case_count,contact_resolved_count,"
                    + " contact_unavailable_count,truncated,baseline,failure_reason,facts::text as facts,"
                    + " operator_id,started_at,finished_at"
                    + " from crm_customer_sync_run where tenant_id = ?"
                    + " order by started_at desc, id desc limit ? offset ?",
                currentTenant(),
                safeSize,
                safePage * safeSize)
            .map(CustomerListService::runRow);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCustomerListSyncRunPage(rows, safePage, safeSize, total, totalPages);
  }

  // ================================================================ 脱敏

  /**
   * 邮箱脱敏：{@code a***@***.com}。
   *
   * <p>🔴 脱敏在**写库之前**算好并落库（{@code contact_email_masked}），
   * 不是读的时候现算 —— 这样脱敏规则改了也不会让历史行显示出另一种形态，
   * 也保证「默认返回脱敏形态」这件事不依赖某一次请求恰好走了哪条分支。
   */
  static String maskEmail(String email) {
    String text = nz(email).trim();
    if (text.isEmpty()) return "";
    int at = text.indexOf('@');
    if (at <= 0) return "***";
    String local = text.substring(0, at);
    String domain = text.substring(at + 1);
    int dot = domain.indexOf('.');
    String maskedDomain = dot > 0 ? "***" + domain.substring(dot) : "***";
    return local.charAt(0) + "***@" + maskedDomain;
  }

  /**
   * 手机脱敏：{@code 138****1234}。
   *
   * <p>不足 7 位时保留首尾各一位，中间一律打码。
   */
  static String maskPhone(String phone) {
    String text = nz(phone).replaceAll("\\s+", "");
    if (text.isEmpty()) return "";
    int length = text.length();
    if (length <= 4) return "****";
    if (length < 7) {
      return text.charAt(0) + "***" + text.charAt(length - 1);
    }
    return text.substring(0, 3) + "****" + text.substring(length - 4);
  }

  // ================================================================ 小工具

  private static String customerColumns() {
    return "select p.id,p.tenant_id,p.customer_ref,p.source_shop,p.customer_name,p.customer_party_id,"
        + " p.first_order_at,p.last_order_at,p.order_count,p.refund_case_count,p.refund_rate,"
        + " p.value_tier,p.risk_flag,p.risk_reason,p.contact_email,p.contact_phone,"
        + " p.contact_email_masked,p.contact_phone_masked,p.contact_source,p.opt_out_state,"
        + " p.window_from,p.window_to,p.synced_at,p.created_at,p.updated_at"
        + " from crm_customer_profile p";
  }

  private static String placeholders(int count) {
    if (count <= 0) return "null";
    return String.join(",", java.util.Collections.nCopies(count, "?"));
  }

  private static Object[] bindAll(Object first, List<?> rest) {
    Object[] binds = new Object[rest.size() + 1];
    binds[0] = first;
    for (int i = 0; i < rest.size(); i++) binds[i + 1] = rest.get(i);
    return binds;
  }

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** 刷新运行号：CS-yyyyMMdd-000001（序列单独授权，见 V432 授权段）。 */
  private String nextRunNo() {
    var row =
        sql.fetchOne(
            "select nextval('crm_customer_sync_run_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "CS-" + day + "-" + String.format("%06d", seq);
  }

  /**
   * 解析带时区偏移量的时刻。
   *
   * <p>刻意用 {@code OffsetDateTime.parse}：用 {@code Timestamp.valueOf(LocalDateTime)} 会在东八区
   * **静默偏 −28800 秒**，而页面看上去一切正常。
   */
  private static OffsetDateTime parseMoment(String value, String label) {
    String text = nz(value).trim();
    if (text.isEmpty()) throw bad("必须指定" + label + "，例如 2026-10-10T08:00:00+08:00");
    try {
      return OffsetDateTime.parse(text);
    } catch (java.time.format.DateTimeParseException e) {
      throw bad(
          label
              + "「"
              + text
              + "」格式不对：请带时区偏移量，例如 2026-10-10T08:00:00+08:00。"
              + "缺偏移量的时间会在东八区静默偏 8 小时，页面却看不出异常。");
    }
  }

  private static String toJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("刷新依据快照无法序列化", e);
    }
  }

  private static Map<String, Object> parseJson(String raw) {
    if (raw == null || raw.isBlank()) return new LinkedHashMap<>();
    try {
      return JSON.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return new LinkedHashMap<>();
    }
  }

  private static String plain(BigDecimal value) {
    return value == null ? "" : value.stripTrailingZeros().toPlainString();
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String valueTierLabel(String value) {
    if (value == null) return "";
    try {
      return CustomerValueTier.valueOf(value).label();
    } catch (IllegalArgumentException e) {
      return value;
    }
  }

  private static String riskFlagLabel(String value) {
    if (value == null) return "";
    try {
      return CustomerRiskFlag.valueOf(value).label();
    } catch (IllegalArgumentException e) {
      return value;
    }
  }

  private static String contactSourceLabel(String value) {
    if (value == null) return "";
    try {
      return CustomerContactSource.valueOf(value).label();
    } catch (IllegalArgumentException e) {
      return value;
    }
  }

  private static String optOutStateLabel(String value) {
    if (value == null) return "";
    try {
      return CustomerOptOutState.valueOf(value).label();
    } catch (IllegalArgumentException e) {
      return value;
    }
  }

  /** 标签分类的可读名（照 15.3.2.1 的分类词表）。 */
  private static String tagCategoryLabel(String value) {
    if (value == null) return "";
    return switch (value) {
      case "VALUE" -> "价值分层";
      case "BEHAVIOR" -> "行为";
      case "RISK" -> "风险与售后";
      case "SOURCE" -> "渠道来源";
      case "CUSTOM" -> "自定义";
      default -> value;
    };
  }

  /** 数据范围：取调用方最宽松的那一档（与其它服务的口径一致）。 */
  private static String dataScopeOf(AccessService.Access actor) {
    String scope = "ALL";
    if (actor.grants() != null) {
      for (AccessService.Grant grant : actor.grants()) {
        if ("ALL".equals(grant.scopeMode())) return "ALL";
        if ("SELF".equals(grant.scopeMode())) scope = "SELF";
        else if ("DEPARTMENT".equals(grant.scopeMode()) && "ALL".equals(scope)) scope = "DEPARTMENT";
      }
    }
    return scope;
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException forbidden(String message) {
    return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static CrmCustomerListSyncRunRow runRow(Record r) {
    return new CrmCustomerListSyncRunRow(
        r.get("id", UUID.class),
        nz(r.get("run_no", String.class)),
        nz(r.get("status", String.class)),
        str(r.get("window_from", OffsetDateTime.class)),
        str(r.get("window_to", OffsetDateTime.class)),
        r.get("customer_count", Integer.class),
        r.get("order_count", Integer.class),
        r.get("case_count", Integer.class),
        r.get("unattributed_case_count", Integer.class),
        r.get("contact_resolved_count", Integer.class),
        r.get("contact_unavailable_count", Integer.class),
        Boolean.TRUE.equals(r.get("truncated", Boolean.class)),
        r.get("baseline", BigDecimal.class),
        nz(r.get("failure_reason", String.class)),
        parseJson(r.get("facts", String.class)),
        r.get("operator_id", UUID.class) == null ? null : r.get("operator_id", UUID.class).toString(),
        str(r.get("started_at", OffsetDateTime.class)),
        str(r.get("finished_at", OffsetDateTime.class)));
  }

  private static String str(OffsetDateTime value) {
    return value == null ? null : value.toString();
  }

  // ================================================================ 内部结构

  /** 一次扫描的结果。 */
  private record FactScan(
      Map<String, CustomerAggregate> customers,
      int scannedOrders,
      int scannedCases,
      int unattributedCases,
      int windowOrders,
      int windowCases,
      BigDecimal baseline,
      boolean truncated,
      Set<String> optOutRefs,
      Map<String, String> optOutStates) {

    String optOutStateOf(String customerRef) {
      String state = optOutStates.get(customerRef);
      return state == null ? "NONE" : state;
    }
  }

  /** 一位客户（一个来源店铺下）的聚合事实。 */
  static final class CustomerAggregate {
    final String sourceShop;
    final String customerRef;
    UUID partyId;
    String customerName = "";
    final List<OrderView> allOrders = new ArrayList<>();
    final List<OrderView> windowOrders = new ArrayList<>();
    final Map<String, BigDecimal> amountsByCurrency = new TreeMap<>();
    final Map<String, Integer> ordersByCurrency = new TreeMap<>();
    final List<AftersalesCaseView> cases = new ArrayList<>();
    OffsetDateTime firstOrderAt;
    OffsetDateTime lastOrderAt;
    String contactEmail = "";
    String contactPhone = "";
    String contactSource = "NONE";

    CustomerAggregate(String sourceShop, String customerRef) {
      this.sourceShop = sourceShop;
      this.customerRef = customerRef;
    }

    void add(OrderView order) {
      allOrders.add(order);
      if (order.buyerName() != null && !order.buyerName().isBlank()) customerName = order.buyerName();
      // 首末购买时间按**本次扫描到的全部订单**算（统计窗口 + 365 天回看段），
      // 不按窗口算 —— 「第一次什么时候买的」问的是历史，不是这个窗口。
      OffsetDateTime at = orderTime(order);
      if (at == null) return;
      if (firstOrderAt == null || at.isBefore(firstOrderAt)) firstOrderAt = at;
      if (lastOrderAt == null || at.isAfter(lastOrderAt)) lastOrderAt = at;
    }

    void addCase(AftersalesCaseView c) {
      cases.add(c);
    }

    /** 把落在窗口内的订单挑出来（**分母只用这一份**）。 */
    void markWindow(OffsetDateTime from, OffsetDateTime to) {
      for (OrderView order : allOrders) {
        OffsetDateTime at = orderTime(order);
        if (at == null || at.isBefore(from) || !at.isBefore(to)) continue;
        windowOrders.add(order);
        String currency = nz(order.currency()).trim();
        BigDecimal amount = order.grandTotalAmount();
        if (!currency.isEmpty() && amount != null) {
          amountsByCurrency.merge(currency, amount, BigDecimal::add);
          ordersByCurrency.merge(currency, 1, Integer::sum);
        }
      }
    }

    /** 计入「退款率」的售后单数（只算退货 / 退款两类）。 */
    int returnCases() {
      int count = 0;
      for (AftersalesCaseView c : cases) {
        if (c.caseType() != null && RETURN_CASE_TYPES.contains(c.caseType())) count++;
      }
      return count;
    }

    BigDecimal refundRate() {
      int denominator = windowOrders.size();
      if (denominator <= 0) return null;
      return BigDecimal.valueOf(returnCases())
          .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    List<OrderView> windowOrders() {
      return windowOrders;
    }

    Map<String, BigDecimal> amountsByCurrency() {
      return amountsByCurrency;
    }

    Map<String, Integer> ordersByCurrency() {
      return ordersByCurrency;
    }

    static OffsetDateTime orderTime(OrderView order) {
      return order.submittedAt() != null ? order.submittedAt() : order.createdAt();
    }
  }

  private static String key(String sourceShop, String customerRef) {
    return sourceShop + " " + customerRef;
  }
}
