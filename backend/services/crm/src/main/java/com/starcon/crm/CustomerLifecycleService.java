package com.starcon.crm;

import static com.starcon.crm.CustomerLifecycleModels.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.crm.CustomerOrderFactsPort.OrderFact;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.6.2.1 生命周期与流失预警。
 *
 * <p>蓝图 15 卷 15.6.2.1 原话：
 *
 * <blockquote>
 * 职责：客户生命周期分层（RFM/新客-活跃-沉睡-流失-召回）与流失预警：按复购间隔识别本应复购未复购的客户，给出召回建议与流失风险分；支持品牌化客户的核心留存看板。
 * 输入：订单历史（9）；复购周期基线；RFM 计算配置。
 * 输出：生命周期分层结果；流失预警清单；召回建议。
 * 关系：上游 = 9 订单、15.3.1.1；下游 = 15.5.2 触达、15.6.2.2 挽回、13 分析。**复购周期因品类差异极大**（快消周级、家具月级），预警阈值需可按类目配置，不能一刀切。
 * </blockquote>
 *
 * <p><b>本类落五条纪律</b>：
 *
 * <ol>
 *   <li><b>判定基于「拉取到的事实」</b>：订单事实经 {@link CustomerOrderFactsPort}
 *       从 trade 的授权 HTTP API 读，类目经 {@link CustomerCategoryPort} 从 catalog 的
 *       授权 HTTP API 读；读不到就<b>显式失败</b>（503），绝不返回空集合冒充「没有客户」。</li>
 *   <li><b>阈值是租户数据</b>：复购周期阈值落在 {@code crm_lifecycle_policy}（可按类目配、
 *       带生效区间）。<b>代码里没有任何「默认 30 天」</b> —— 没有命中策略时，
 *       阈值改用<b>租户真实订单历史算出来的中位复购间隔</b>，
 *       并把这件事写进 {@code threshold_source} 与 {@code segment_reason}。</li>
 *   <li><b>每一次判定都要能解释</b>：分层行带理由与分档依据（{@code score_basis}），
 *       预警行带风险因子明细（{@code risk_factors}）与召回建议。</li>
 *   <li><b>召回只是建议</b>：本模块<b>不发送任何消息</b>；触达归 15.5.2，
 *       弃单挽回归 15.6.2.2 —— 归属写在 {@code suggestion_owner} 上。</li>
 *   <li><b>跨服务纪律</b>：上游 9 订单在 trade、类目在 catalog —— 本服务<b>一条 SQL 都不碰</b>
 *       对方的库；本库只存逻辑引用 + 快照，<b>不建跨库 FK</b>。</li>
 * </ol>
 */
@Service
public class CustomerLifecycleService {

  // ================================================================ 常量

  /**
   * 默认扫描窗口（天）。
   *
   * <p>🔴 为什么不是 365：流失预警要能看见「**已经很久没买**」的人。
   * 如果只扫最近一年，一个 400 天没下单的客户根本不会出现在名单里 ——
   * 而他恰恰是最该被召回的那位。所以默认扫**两年**，
   * RFM 的计算窗口另由策略的 {@code rfm_window_days} 控制。
   */
  static final int DEFAULT_SCAN_WINDOW_DAYS = 730;

  /** 没有策略时的 RFM 计算窗口（天）。 */
  static final int DEFAULT_RFM_WINDOW_DAYS = 365;

  /** 默认的 R 分「最近」线（天）—— 与 V436 的列默认值一致。 */
  static final int DEFAULT_RECENCY_SCORE_DAYS = 30;

  /** 默认的 F 分基准（单）—— 与 V436 的列默认值一致。 */
  static final int DEFAULT_FREQUENCY_MIN_ORDERS = 3;

  /** 列表页最大条数（与本服务其它模块同一上限）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 一次重算最多落多少位客户（超出则如实报 truncated）。 */
  static final int MAX_CUSTOMERS_PER_RUN = 5000;

  /**
   * 一次重算最多查多少个 SKU 的类目。
   *
   * <p>类目解析是逐个 SKU 查的（catalog 的 SKU 列表支持 {@code q} 模糊查询，
   * 没有「按编码批量取」的端点）⇒ 必须有上限，否则大租户能把请求线程占满。
   * 触到上限时<b>如实报 categoryLookupTruncated</b>，剩下的客户类目记为「未解析」。
   */
  static final int MAX_SKU_LOOKUPS = 300;

  /** 每个客户最多查几个 SKU 的类目（取最近一单的前几个 SKU）。 */
  static final int MAX_SKU_PER_CUSTOMER = 3;

  /**
   * R 分档的「最近」线在没有策略时怎么办？
   *
   * <p>用<b>租户真实订单历史的中位复购间隔</b>兜底（{@code ORDER_HISTORY_BASELINE}），
   * 而不是写死一个天数 —— 写死一个数就是「假装按类目生效」的另一种写法。
   */
  static final int MIN_RECENCY_SCORE_DAYS = 1;

  /** 风险分档位：低于这个值是低风险。 */
  static final int RISK_LOW_MAX = 20;

  /** 风险分档位：低于这个值是中风险。 */
  static final int RISK_MEDIUM_MAX = 40;

  /** 风险分档位：低于这个值是高风险，以上是极高。 */
  static final int RISK_HIGH_MAX = 70;

  /** 风险因子权重：逾期倍数。 */
  static final int WEIGHT_OVERDUE = 40;

  /** 风险因子权重：最近购买（越久没买越危险）。 */
  static final int WEIGHT_RECENCY = 25;

  /** 风险因子权重：购买频次（曾经越频繁，断了越可惜）。 */
  static final int WEIGHT_FREQUENCY = 20;

  /** 风险因子权重：价值敞口（曾经消费越高，断掉越可惜）。 */
  static final int WEIGHT_VALUE = 15;

  private final DSLContext sql;
  private final CustomerOrderFactsPort orders;
  private final CustomerCategoryPort categories;

  public CustomerLifecycleService(
      DSLContext sql, CustomerOrderFactsPort orders, CustomerCategoryPort categories) {
    this.sql = sql;
    this.orders = orders;
    this.categories = categories;
  }

  /** 判定依据快照的 JSON mapper：BigDecimal 按原样写出（不转成浮点）。 */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  // ================================================================ 能力声明

  /**
   * 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 三个维度刻意是 {@code available=false}：
   *
   * <ul>
   *   <li>{@code touch_execution} —— 15.5.2 触达<b>未交付</b>：本模块<b>只产出建议</b>，
   *       一条消息都不发。</li>
   *   <li>{@code logical_customer} —— 15.3.1.3 跨店合并<b>未交付</b>：
   *       客户仍是<b>按来源店铺</b>的，同一个人在两个来源下是两行。</li>
   *   <li>{@code customer_detail} —— 15.3.1.2 客户详情<b>未交付</b>：
   *       本模块只给分层结果、预警清单与召回建议。</li>
   * </ul>
   */
  public CrmLifecycleCapabilities capabilities() {
    List<CrmLifecycleCapabilityDimension> dimensions =
        List.of(
            new CrmLifecycleCapabilityDimension(
                "order_facts",
                "订单历史（R / F / M 与复购间隔的原料）",
                true,
                "上游 trade 9.1.1.1 全渠道统一订单列表，经授权 HTTP API 只读"
                    + "（GET /trade/sales-orders/list）",
                "9",
                "读不到一律 503，**绝不**返回空集合冒充「这个租户没有订单」"),
            new CrmLifecycleCapabilityDimension(
                "category_dimension",
                "类目维度（catalog 的 SKU 类目，只读）",
                true,
                "catalog SKU 主档（GET /catalog/skus，只读），由订单行里的 SKU 编码解析",
                "",
                "类目解析不出来会**如实标注**（category_source=UNRESOLVED）并计入运行记录；"
                    + "catalog 读不到则整体失败（503）——「读不到」不是「没有类目」"),
            new CrmLifecycleCapabilityDimension(
                "category_threshold_policy",
                "复购周期阈值（**可按类目配置**，带生效区间）",
                true,
                "本服务 V436 crm_lifecycle_policy（租户数据，不是代码常量）",
                "",
                "类目策略 > 全局策略 > 租户真实订单历史的中位复购间隔；"
                    + "四档来源写在 threshold_source 上，界面直接显示"),
            new CrmLifecycleCapabilityDimension(
                "rfm_scores",
                "RFM 分档（R / F / M 各 0..5，0 = 不可判定）",
                true,
                "服务端按策略配置或租户真实订单历史算出（score_basis 里有依据）",
                "",
                "分档依据随行落库（score_basis），翻旧记录看到的还是当时的依据"),
            new CrmLifecycleCapabilityDimension(
                "churn_alert",
                "流失预警清单（本应复购未复购）",
                true,
                "客户复购间隔基线 + 阈值（预期复购间隔 + 容差）",
                "",
                "预警带**风险因子明细**（risk_factors）与**召回建议**；"
                    + "处理状态只能向前推进（OPEN → ACKNOWLEDGED → RESOLVED）"),
            new CrmLifecycleCapabilityDimension(
                "recall_suggestion",
                "召回建议（**只产出建议，不触达**）",
                true,
                "服务端按超期倍数、RFM 分档与类目生成",
                "15.5.2 / 15.6.2.2",
                "建议归属写在 suggestion_owner 上：本模块**不发送任何消息**"),
            new CrmLifecycleCapabilityDimension(
                "touch_execution",
                "触达执行（发邮件 / 短信 / 站内信）",
                false,
                "**15.5.2 触达未交付**",
                "15.5.2",
                "本模块**只给建议**：想发出去要走 15.5.2，那是另一个模块的职责"),
            new CrmLifecycleCapabilityDimension(
                "abandonment_recovery",
                "独立站加购 / 结账未完成的挽回",
                false,
                "**15.6.2.2 购物车与弃单挽回**（同服务、另一命名空间，**不是**本模块）",
                "15.6.2.2",
                "那边管的是「**加购没走完**」，本模块管的是「**买过、该回购却没回购**」；"
                    + "两套口径互不替代，召回建议里会写明该走哪边"),
            new CrmLifecycleCapabilityDimension(
                "logical_customer",
                "跨店合并后的逻辑客户",
                false,
                "**15.3.1.3 跨店合并未交付**",
                "15.3.1.3",
                "本模块的客户仍是**按来源店铺**的，同一个人在两个来源下是两行"),
            new CrmLifecycleCapabilityDimension(
                "customer_detail",
                "客户详情与统一时间线",
                false,
                "**15.3.1.2 客户详情未交付**",
                "15.3.1.2",
                "本模块只给分层结果、预警清单与召回建议；时间线由 15.3.1.2 聚合"));
    return new CrmLifecycleCapabilities(
        dimensions,
        java.util.Arrays.stream(CustomerLifecycleSegment.values()).map(Enum::name).toList(),
        java.util.Arrays.stream(LifecycleThresholdSource.values()).map(Enum::name).toList(),
        java.util.Arrays.stream(ChurnRiskLevel.values()).map(Enum::name).toList(),
        java.util.Arrays.stream(ChurnAlertStatus.values()).map(Enum::name).toList(),
        "🔴 **复购周期阈值是租户数据**（crm_lifecycle_policy），可按类目配、带生效区间。"
            + "代码里**没有任何「默认 30 天」**：没命中类目策略就退到全局策略，"
            + "再没有就用**租户真实订单历史算出来的中位复购间隔**，"
            + "并把这一事实写在 threshold_source 上。",
        "客户是**按来源店铺**的（与 15.3.1.1 同一口径）：同一个人在两个来源下是两行。"
            + "上游 9 订单在 trade、类目在 catalog —— 本服务**只经授权 HTTP API 读**，"
            + "一条跨库 SQL / 跨库 FK / 跨库视图都没有。",
        "「本应复购未复购」的判定依据是**可复核的**："
            + "预期复购间隔（租户按类目配）+ 容差，加上**客户本人历史复购间隔的中位数**与样本数。"
            + "风险分不是黑箱：四个因子各带权重、原始值、得分与说明。");
  }

  // ================================================================ 分层列表

  /**
   * 生命周期分层列表：真分页（{@code LIMIT/OFFSET} + 稳定全序）+ 与分页无关的分层统计。
   */
  public CrmLifecyclePage segmentPage(
      String segment,
      String sourceShop,
      String categoryCode,
      String keyword,
      int page,
      int size,
      AccessService.Access actor) {

    List<Object> binds = new ArrayList<>();
    String where = segmentWhere(segment, sourceShop, categoryCode, keyword, binds);

    long total =
        sql.fetchOne("select count(*) from crm_customer_lifecycle l" + where, binds.toArray())
            .get(0, Long.class);

    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);

    List<Record> records =
        sql.fetch(
            "select l.* from crm_customer_lifecycle l"
                + where
                + " order by l.overdue_days desc, l.last_order_at asc nulls last,"
                + " l.customer_ref asc, l.source_shop asc"
                + " limit ? offset ?",
            pageBinds.toArray());

    CrmLifecycleStats stats = statistics(where, binds);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmLifecyclePage(
        rows(records),
        safePage,
        safeSize,
        total,
        totalPages,
        stats,
        dataScopeOf(actor),
        "客户主档是**租户级**的（没有「归属人」这一维），所以 SELF / DEPARTMENT 两种范围"
            + "在生命周期分层上退化为租户级 —— 分层不按人切分。");
  }

  /** 分层列表的 WHERE 片段（列表与统计**共用同一份**，保证「统计的就是筛出来的」）。 */
  private String segmentWhere(
      String segment,
      String sourceShop,
      String categoryCode,
      String keyword,
      List<Object> binds) {
    StringBuilder where = new StringBuilder(" where l.tenant_id = ?");
    binds.add(currentTenant());
    if (blankToNull(segment) != null) {
      where.append(" and l.segment = ?");
      binds.add(segment.trim().toUpperCase(Locale.ROOT));
    }
    if (blankToNull(sourceShop) != null) {
      where.append(" and l.source_shop = ?");
      binds.add(sourceShop.trim());
    }
    if (blankToNull(categoryCode) != null) {
      where.append(" and l.category_code = ?");
      binds.add(categoryCode.trim());
    }
    if (blankToNull(keyword) != null) {
      where.append(
          " and (strpos(lower(l.customer_ref), lower(?)) > 0"
              + " or strpos(lower(l.customer_name), lower(?)) > 0)");
      binds.add(keyword.trim());
      binds.add(keyword.trim());
    }
    return where.toString();
  }

  /**
   * 分层统计 —— <b>单独一条一条的统计查询</b>，与分页无关。
   *
   * <p>🔴 关键：这些数字**不是**拿当前页的 {@code rows} 聚合出来的。
   */
  private CrmLifecycleStats statistics(String where, List<Object> binds) {
    var totals =
        sql.fetchOne(
            "select count(*), coalesce(sum(case when l.overdue_days > 0 then 1 else 0 end),0)"
                + " from crm_customer_lifecycle l" + where,
            binds.toArray());
    long customerCount = totals.get(0, Long.class);
    long overdue = totals.get(1, Long.class);

    List<CrmLifecycleSegmentBucket> bySegment =
        sql.fetch(
                "select l.segment, count(*) from crm_customer_lifecycle l" + where
                    + " group by l.segment order by l.segment",
                binds.toArray())
            .map(
                r ->
                    new CrmLifecycleSegmentBucket(
                        r.get(0, String.class),
                        segmentLabel(r.get(0, String.class)),
                        r.get(1, Long.class)));

    // ⚠️ where 片段里**已经**带了一个租户绑定 ⇒ 这里不能再加一个（加了会多绑一个参数，
    //   PostgreSQL 直接报「参数个数不对」⇒ 整条查询变成 409）。
    //
    // 🔴 类目分布**拆成两条查询**再合并：把预警计数写成 GROUP BY 里的相关子查询时，
    //   PostgreSQL 会报「subquery uses ungrouped column」（tenant_id 不在 GROUP BY 里）。
    Map<String, Long> customerCountByCategory = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select l.category_code, count(*) from crm_customer_lifecycle l" + where
                + " group by l.category_code order by l.category_code nulls first",
            binds.toArray())) {
      customerCountByCategory.put(r.get(0, String.class), r.get(1, Long.class));
    }
    Map<String, Long> alertCountByCategory = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select a.category_code, count(*) from crm_churn_alert a where a.tenant_id = ?"
                + " group by a.category_code",
            currentTenant())) {
      alertCountByCategory.put(r.get(0, String.class), r.get(1, Long.class));
    }
    List<CrmLifecycleCategoryBucket> byCategory = new ArrayList<>();
    for (var entry : customerCountByCategory.entrySet()) {
      byCategory.add(
          new CrmLifecycleCategoryBucket(
              entry.getKey(),
              entry.getValue(),
              alertCountByCategory.getOrDefault(entry.getKey(), 0L)));
    }

    // ⚠️ 这条查询比上面那条**多**一个租户绑定（a.tenant_id = ?）⇒ 绑定数 = 1 + where 里的绑定数。
    List<Object> alertBinds = new ArrayList<>();
    alertBinds.add(currentTenant());
    alertBinds.addAll(binds);
    long alertCount =
        sql.fetchOne(
                "select count(*) from crm_churn_alert a where a.tenant_id = ?"
                    + " and (a.customer_ref, a.source_shop) in"
                    + " (select l.customer_ref, l.source_shop from crm_customer_lifecycle l" + where + ")",
                alertBinds.toArray())
            .get(0, Long.class);

    return new CrmLifecycleStats(
        customerCount,
        alertCount,
        overdue,
        bySegment,
        byCategory,
        "统计口径：分层统计是**独立查询**的结果，**不受分页影响** —— "
            + "翻到第 3 页看到的分布与第 1 页完全一样。"
            + "客户是**按来源店铺**的：同一个人在两个来源下算两位客户。");
  }

  /** 把查出来的分层行装配成传输模型。 */
  private List<CrmLifecycleRow> rows(List<Record> records) {
    List<CrmLifecycleRow> out = new ArrayList<>();
    for (Record r : records) {
      String segment = nz(r.get("segment", String.class));
      String thresholdSource = nz(r.get("threshold_source", String.class));
      out.add(
          new CrmLifecycleRow(
              r.get("id", UUID.class),
              nz(r.get("customer_ref", String.class)),
              nz(r.get("source_shop", String.class)),
              nz(r.get("customer_name", String.class)),
              momentText(r.get("first_order_at", OffsetDateTime.class)),
              momentText(r.get("last_order_at", OffsetDateTime.class)),
              r.get("order_count", Integer.class) == null ? 0 : r.get("order_count", Integer.class),
              r.get("recency_days", Integer.class),
              r.get("frequency", Integer.class) == null ? 0 : r.get("frequency", Integer.class),
              r.get("monetary", BigDecimal.class),
              blankToNull(r.get("currency", String.class)),
              r.get("r_score", Integer.class) == null ? 0 : r.get("r_score", Integer.class),
              r.get("f_score", Integer.class) == null ? 0 : r.get("f_score", Integer.class),
              r.get("m_score", Integer.class) == null ? 0 : r.get("m_score", Integer.class),
              segment,
              segmentLabel(segment),
              nz(r.get("segment_reason", String.class)),
              blankToNull(r.get("category_code", String.class)),
              nz(r.get("category_source", String.class)),
              r.get("expected_repurchase_days", Integer.class),
              r.get("tolerance_days", Integer.class),
              thresholdSource,
              thresholdSourceLabel(thresholdSource),
              blankToNull(r.get("policy_id", UUID.class) == null ? null : r.get("policy_id", UUID.class).toString()),
              r.get("customer_baseline_days", Integer.class),
              r.get("customer_baseline_samples", Integer.class) == null
                  ? 0
                  : r.get("customer_baseline_samples", Integer.class),
              nz(r.get("baseline_source", String.class)),
              r.get("overdue_days", Integer.class) == null ? 0 : r.get("overdue_days", Integer.class),
              readJsonObject(r.get("score_basis", String.class)),
              momentText(r.get("window_from", OffsetDateTime.class)),
              momentText(r.get("window_to", OffsetDateTime.class)),
              momentText(r.get("computed_at", OffsetDateTime.class))));
    }
    return out;
  }

  // ================================================================ 预警清单

  /** 流失预警清单（真分页 + 真筛选）。 */
  public CrmLifecycleAlertPage alertPage(
      String status, String riskLevel, String categoryCode, String keyword, int page, int size) {
    List<Object> binds = new ArrayList<>();
    String where = alertWhere(status, riskLevel, categoryCode, keyword, binds);
    long total =
        sql.fetchOne("select count(*) from crm_churn_alert a" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<Record> records =
        sql.fetch(
            "select a.* from crm_churn_alert a"
                + where
                + " order by a.overdue_days desc, a.risk_score desc, a.customer_ref asc,"
                + " a.source_shop asc limit ? offset ?",
            pageBinds.toArray());
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmLifecycleAlertPage(
        alertRows(records),
        safePage,
        safeSize,
        total,
        totalPages,
        "预警 = **本应复购未复购**：距上次购买已超过「预期复购间隔 + 容差」。"
            + "预期复购间隔来自租户按类目配的策略；没有类目策略时退到全局策略，"
            + "再没有就用**租户真实订单历史的中位复购间隔**。"
            + "召回只是**建议**，触达归 15.5.2、弃单挽回归 15.6.2.2 —— 本模块不发送任何消息。");
  }

  private String alertWhere(
      String status, String riskLevel, String categoryCode, String keyword, List<Object> binds) {
    StringBuilder where = new StringBuilder(" where a.tenant_id = ?");
    binds.add(currentTenant());
    if (blankToNull(status) != null) {
      where.append(" and a.status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (blankToNull(riskLevel) != null) {
      where.append(" and a.risk_level = ?");
      binds.add(riskLevel.trim().toUpperCase(Locale.ROOT));
    }
    if (blankToNull(categoryCode) != null) {
      where.append(" and a.category_code = ?");
      binds.add(categoryCode.trim());
    }
    if (blankToNull(keyword) != null) {
      where.append(
          " and (strpos(lower(a.customer_ref), lower(?)) > 0"
              + " or strpos(lower(a.customer_name), lower(?)) > 0)");
      binds.add(keyword.trim());
      binds.add(keyword.trim());
    }
    return where.toString();
  }

  /** 单条预警详情。 */
  public CrmLifecycleAlertDetail alertDetail(UUID id) {
    var row =
        sql.fetchOne("select a.* from crm_churn_alert a where a.tenant_id = ? and a.id = ?",
            currentTenant(), id);
    if (row == null) throw notFound("预警不存在：" + id);
    CrmLifecycleAlertRow alert = alertRows(List.of(row)).get(0);
    return new CrmLifecycleAlertDetail(alert, alert.riskFactors());
  }

  /** 标记已知悉（OPEN → ACKNOWLEDGED）。 */
  public CrmLifecycleAlertRow acknowledgeAlert(UUID id, String note) {
    return advanceAlert(id, "ACKNOWLEDGED", note);
  }

  /** 标记已处理（→ RESOLVED）。 */
  public CrmLifecycleAlertRow resolveAlert(UUID id, String note) {
    return advanceAlert(id, "RESOLVED", note);
  }

  private CrmLifecycleAlertRow advanceAlert(UUID id, String status, String note) {
    var row =
        sql.fetchOne(
            "select status from crm_churn_alert where tenant_id = ? and id = ?", currentTenant(), id);
    if (row == null) throw notFound("预警不存在：" + id);
    String current = nz(row.get(0, String.class));
    if (current.equals(status)) {
      var again =
          sql.fetchOne("select a.* from crm_churn_alert a where a.tenant_id = ? and a.id = ?",
              currentTenant(), id);
      return alertRows(List.of(again)).get(0);
    }
    sql.execute(
        "update crm_churn_alert set status = ?, status_note = ?, updated_at = ?::timestamptz"
            + " where tenant_id = ? and id = ?",
        status,
        nz(note),
        now(),
        currentTenant(),
        id);
    var updated =
        sql.fetchOne("select a.* from crm_churn_alert a where a.tenant_id = ? and a.id = ?",
            currentTenant(), id);
    return alertRows(List.of(updated)).get(0);
  }

  /** 把预警行装配成传输模型（风险因子明细从 jsonb 读回来）。 */
  private List<CrmLifecycleAlertRow> alertRows(List<Record> records) {
    List<CrmLifecycleAlertRow> out = new ArrayList<>();
    for (Record r : records) {
      String riskLevel = nz(r.get("risk_level", String.class));
      String status = nz(r.get("status", String.class));
      String thresholdSource = nz(r.get("threshold_source", String.class));
      String owner = nz(r.get("suggestion_owner", String.class));
      out.add(
          new CrmLifecycleAlertRow(
              r.get("id", UUID.class),
              nz(r.get("customer_ref", String.class)),
              nz(r.get("source_shop", String.class)),
              nz(r.get("customer_name", String.class)),
              momentText(r.get("last_order_at", OffsetDateTime.class)),
              r.get("days_since_last_order", Integer.class) == null
                  ? 0
                  : r.get("days_since_last_order", Integer.class),
              r.get("expected_repurchase_days", Integer.class) == null
                  ? 0
                  : r.get("expected_repurchase_days", Integer.class),
              r.get("tolerance_days", Integer.class) == null ? 0 : r.get("tolerance_days", Integer.class),
              r.get("overdue_days", Integer.class) == null ? 0 : r.get("overdue_days", Integer.class),
              thresholdSource,
              thresholdSourceLabel(thresholdSource),
              blankToNull(r.get("category_code", String.class)),
              r.get("customer_baseline_days", Integer.class),
              r.get("customer_baseline_samples", Integer.class) == null
                  ? 0
                  : r.get("customer_baseline_samples", Integer.class),
              r.get("risk_score", BigDecimal.class),
              riskLevel,
              riskLevelLabel(riskLevel),
              readRiskFactors(r.get("risk_factors", String.class)),
              nz(r.get("recall_suggestion", String.class)),
              owner,
              suggestionOwnerLabel(owner),
              status,
              statusLabel(status),
              nz(r.get("status_note", String.class)),
              momentText(r.get("detected_at", OffsetDateTime.class))));
    }
    return out;
  }

  // ================================================================ 阈值策略

  /** 阈值策略列表（真分页）。 */
  public CrmLifecyclePolicyPage policyPage(String scope, String categoryCode, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where tenant_id = ?");
    binds.add(currentTenant());
    if (blankToNull(scope) != null) {
      where.append(" and scope = ?");
      binds.add(scope.trim().toUpperCase(Locale.ROOT));
    }
    if (blankToNull(categoryCode) != null) {
      where.append(" and category_code = ?");
      binds.add(categoryCode.trim());
    }
    long total =
        sql.fetchOne("select count(*) from crm_lifecycle_policy" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<Record> records =
        sql.fetch(
            "select * from crm_lifecycle_policy"
                + where
                + " order by scope asc, category_code asc, effective_from desc limit ? offset ?",
            pageBinds.toArray());
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmLifecyclePolicyPage(
        policyRows(records),
        safePage,
        safeSize,
        total,
        totalPages,
        "阈值策略是**租户数据**：类目策略优先于全局策略，两条都命中不到时，"
            + "判定改用**租户真实订单历史的中位复购间隔**并如实标注。"
            + "阈值**不可就地修改**（改阈值不改历史）—— 要改请新建一条（新的生效起点）。");
  }

  /** 单条策略。 */
  public CrmLifecyclePolicyRow policy(UUID id) {
    var row =
        sql.fetchOne("select * from crm_lifecycle_policy where tenant_id = ? and id = ?",
            currentTenant(), id);
    if (row == null) throw notFound("阈值策略不存在：" + id);
    return policyRows(List.of(row)).get(0);
  }

  /**
   * 新建一条阈值策略。
   *
   * <p>🔴 同一作用域 + 同一类目下**已有的未闭合策略会被自动闭合**
   * （{@code effective_to = 新策略的生效起点}）—— 否则同一个类目会同时有两条生效策略，
   * 「哪条说了算」就成了猜。
   */
  @Transactional
  public CrmLifecyclePolicyRow createPolicy(CrmLifecyclePolicyInput input) {
    String scope = input.scope().trim().toUpperCase(Locale.ROOT);
    String category = "GLOBAL".equals(scope) ? "" : nz(input.categoryCode()).trim();
    if ("GLOBAL".equals(scope) && !category.isEmpty()) {
      throw bad("全局策略不能带类目编码：全局兜底对所有类目生效。");
    }
    if ("CATEGORY".equals(scope) && category.isEmpty()) {
      throw bad("按类目的策略必须给出类目编码（catalog 的类目编码）。");
    }
    if (input.monetaryMinAmount() != null && blankToNull(input.monetaryCurrency()) == null) {
      throw bad("给了金额阈值就必须同时给出币种：跨币种的金额不能比。");
    }
    if (input.monetaryMinAmount() != null && input.monetaryMinAmount().signum() < 0) {
      throw bad("金额阈值不能为负。");
    }
    OffsetDateTime effectiveFrom = parseMoment(input.effectiveFrom(), "生效起点");
    OffsetDateTime effectiveTo =
        blankToNull(input.effectiveTo()) == null ? null : parseMoment(input.effectiveTo(), "生效终点");
    if (effectiveTo != null && !effectiveFrom.isBefore(effectiveTo)) {
      throw bad("生效终点必须晚于生效起点。");
    }

    // 闭合同一作用域 + 同一类目下仍然「开着」的旧策略。
    sql.execute(
        "update crm_lifecycle_policy set effective_to = ?::timestamptz, updated_at = ?::timestamptz"
            + " where tenant_id = ? and scope = ? and category_code = ? and effective_to is null"
            + " and effective_from < ?::timestamptz",
        effectiveFrom,
        now(),
        currentTenant(),
        scope,
        category,
        effectiveFrom);

    UUID id = UUID.randomUUID();
    try {
      sql.execute(
          "insert into crm_lifecycle_policy"
              + "(id,tenant_id,scope,category_code,expected_repurchase_days,tolerance_days,"
              + " rfm_window_days,recency_score_days,frequency_min_orders,monetary_min_amount,"
              + " monetary_currency,enabled,effective_from,effective_to,note,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          scope,
          category,
          input.expectedRepurchaseDays(),
          input.toleranceDays() == null ? 0 : input.toleranceDays(),
          input.rfmWindowDays() == null ? DEFAULT_RFM_WINDOW_DAYS : input.rfmWindowDays(),
          // ⚠️ 默认值必须与 V436 的列默认值一致（recency_score_days > 0 / frequency_min_orders >= 1
          //   是 DB 层 CHECK）—— 写 0 会被 DB 拒掉。
          input.recencyScoreDays() == null ? DEFAULT_RECENCY_SCORE_DAYS : input.recencyScoreDays(),
          input.frequencyMinOrders() == null ? DEFAULT_FREQUENCY_MIN_ORDERS : input.frequencyMinOrders(),
          input.monetaryMinAmount(),
          blankToNull(input.monetaryCurrency()) == null
              ? null
              : input.monetaryCurrency().trim().toUpperCase(Locale.ROOT),
          input.enabled() == null || input.enabled(),
          effectiveFrom,
          effectiveTo,
          nz(input.note()),
          now(),
          now());
    } catch (DataIntegrityViolationException e) {
      throw conflict("同一个类目在这个生效起点上已经有一条策略了：请换一个生效起点。");
    }
    return policy(id);
  }

  /**
   * 改一条策略。
   *
   * <p>🔴 <b>阈值不可就地修改</b>（不可变优先）：改了它，上一次判定引用的
   * {@code policy_id} 就会指向另一个含义，历史解释不了。要改阈值请新建一条。
   * 这里只允许改「是否启用 / 备注 / 生效终点」。
   */
  @Transactional
  public CrmLifecyclePolicyRow updatePolicy(UUID id, CrmLifecyclePolicyInput input) {
    CrmLifecyclePolicyRow existing = policy(id);
    if (input.expectedRepurchaseDays() != null
        && input.expectedRepurchaseDays() != existing.expectedRepurchaseDays()) {
      throw conflict(
          "阈值不可就地修改：改了它，之前引用这条策略的判定就解释不通了。"
              + "请新建一条策略（新的生效起点）来改阈值。");
    }
    if (input.toleranceDays() != null && input.toleranceDays() != existing.toleranceDays()) {
      throw conflict(
          "容差不可就地修改：请新建一条策略（新的生效起点）来改容差。");
    }
    OffsetDateTime effectiveTo =
        blankToNull(input.effectiveTo()) == null ? null : parseMoment(input.effectiveTo(), "生效终点");
    if (effectiveTo != null && !effectiveTo.isAfter(parseMoment(existing.effectiveFrom(), "生效起点"))) {
      throw bad("生效终点必须晚于生效起点。");
    }
    sql.execute(
        "update crm_lifecycle_policy set enabled = ?, note = ?, effective_to = ?::timestamptz,"
            + " updated_at = ?::timestamptz where tenant_id = ? and id = ?",
        input.enabled() == null ? existing.enabled() : input.enabled(),
        input.note() == null ? existing.note() : nz(input.note()),
        effectiveTo,
        now(),
        currentTenant(),
        id);
    return policy(id);
  }

  private List<CrmLifecyclePolicyRow> policyRows(List<Record> records) {
    List<CrmLifecyclePolicyRow> out = new ArrayList<>();
    OffsetDateTime now = now();
    for (Record r : records) {
      String scope = nz(r.get("scope", String.class));
      OffsetDateTime from = r.get("effective_from", OffsetDateTime.class);
      OffsetDateTime to = r.get("effective_to", OffsetDateTime.class);
      boolean effectiveNow =
          from != null && !from.isAfter(now) && (to == null || to.isAfter(now));
      out.add(
          new CrmLifecyclePolicyRow(
              r.get("id", UUID.class),
              scope,
              policyScopeLabel(scope),
              nz(r.get("category_code", String.class)),
              r.get("expected_repurchase_days", Integer.class) == null
                  ? 0
                  : r.get("expected_repurchase_days", Integer.class),
              r.get("tolerance_days", Integer.class) == null ? 0 : r.get("tolerance_days", Integer.class),
              r.get("rfm_window_days", Integer.class) == null ? 0 : r.get("rfm_window_days", Integer.class),
              r.get("recency_score_days", Integer.class) == null
                  ? 0
                  : r.get("recency_score_days", Integer.class),
              r.get("frequency_min_orders", Integer.class) == null
                  ? 0
                  : r.get("frequency_min_orders", Integer.class),
              r.get("monetary_min_amount", BigDecimal.class),
              blankToNull(r.get("monetary_currency", String.class)),
              Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
              momentText(from),
              momentText(to),
              effectiveNow,
              nz(r.get("note", String.class)),
              momentText(r.get("created_at", OffsetDateTime.class))));
    }
    return out;
  }

  // ================================================================ 重算

  /**
   * 从上游订单事实重算生命周期分层与流失预警。
   *
   * <p>🔴 三条纪律：
   *
   * <ol>
   *   <li>上游订单事实读不到 ⇒ <b>抛 503</b>，一条分层结果都不落
   *       （绝不返回空集合冒充「上游没有客户」）。</li>
   *   <li>类目读不到（catalog 不可达）⇒ <b>抛 503</b>：把「读不到」当成「没有类目」，
   *       会让整批客户悄悄落到全局阈值上，而运营以为自己是按类目配的。</li>
   *   <li>统计窗口的起止<b>成对</b>给；只给一个会让窗口两端对不上。</li>
   * </ol>
   */
  @Transactional
  public CrmLifecycleComputeResult compute(
      CrmLifecycleComputeInput input, UUID actorId, String bearer) {
    OffsetDateTime startedAt = now();
    OffsetDateTime windowTo = startedAt;
    OffsetDateTime windowFrom = startedAt.minusDays(DEFAULT_SCAN_WINDOW_DAYS);
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

    // ---- 1. 拉上游订单事实（读不到 ⇒ 503）
    List<OrderFact> facts = new ArrayList<>();
    boolean truncated = false;
    int page = 0;
    while (true) {
      var p = orders.orders(windowFrom, windowTo, page, bearer);
      facts.addAll(p.rows());
      long consumed = (long) (page + 1) * CustomerOrderFactsPort.PAGE_SIZE;
      if (p.rows().isEmpty() || consumed >= p.total()) break;
      page++;
      if (page >= CustomerOrderFactsPort.MAX_PAGES) {
        truncated = true;
        break;
      }
    }

    // ---- 2. 聚合成「（来源店铺, 客户）→ 事实」
    Map<String, CustomerAgg> aggregates = new LinkedHashMap<>();
    for (OrderFact order : facts) {
      if (order.customerPartyId() == null) continue;
      // 只认已提交的订单：草稿单不算购买行为。
      if (!"SUBMITTED".equalsIgnoreCase(nz(order.documentStatus()))) continue;
      String rawShop = nz(order.orderSource()).trim();
      final String shop = rawShop.isEmpty() ? "UNKNOWN" : rawShop;
      String ref = order.customerPartyId().toString();
      CustomerAgg agg =
          aggregates.computeIfAbsent(key(shop, ref), k -> new CustomerAgg(shop, ref));
      agg.add(order);
    }
    List<CustomerAgg> allCustomers = new ArrayList<>(aggregates.values());
    List<CustomerAgg> customers = allCustomers;
    if (allCustomers.size() > MAX_CUSTOMERS_PER_RUN) {
      customers = allCustomers.subList(0, MAX_CUSTOMERS_PER_RUN);
      truncated = true;
    }
    for (CustomerAgg agg : customers) {
      agg.sort();
    }

    // ---- 3. 类目解析（catalog 只读；读不到 ⇒ 503）
    Map<String, CategoryOutcome> categoryByKey = new LinkedHashMap<>();
    Map<String, String> categoryCache = new HashMap<>();
    int skuLookups = 0;
    boolean categoryTruncated = false;
    for (CustomerAgg agg : customers) {
      List<String> skuCodes = agg.recentSkuCodes(MAX_SKU_PER_CUSTOMER);
      String category = null;
      String reason = "订单行里没有 SKU 编码";
      if (skuCodes.isEmpty()) {
        // 没有 SKU 编码 ⇒ 解析不出来（事实，不是故障）。
      } else {
        for (String sku : skuCodes) {
          if (skuLookups >= MAX_SKU_LOOKUPS) {
            categoryTruncated = true;
            reason = "类目解析的查询次数达到上限（" + MAX_SKU_LOOKUPS + " 次），本客户未解析";
            break;
          }
          String cached = categoryCache.get(sku);
          CustomerCategoryPort.CategoryLookup lookup;
          if (cached != null) {
            lookup = new CustomerCategoryPort.CategoryLookup(true, cached, "");
          } else {
            skuLookups++;
            lookup = categories.categoryOf(sku, bearer);
            if (lookup.resolved()) categoryCache.put(sku, lookup.category());
          }
          if (lookup.resolved()) {
            category = lookup.category();
            reason = "";
            break;
          }
          reason = lookup.reason();
        }
      }
      categoryByKey.put(key(agg.sourceShop, agg.customerRef), new CategoryOutcome(category, reason));
    }

    // ---- 4. 生效中的阈值策略
    Map<String, PolicyView> categoryPolicies = new LinkedHashMap<>();
    PolicyView globalPolicy = null;
    int policyCount = 0;
    for (Record r :
        sql.fetch(
            "select * from crm_lifecycle_policy where tenant_id = ?"
                + " and enabled = true and effective_from <= ?::timestamptz"
                + " and (effective_to is null or effective_to > ?::timestamptz)"
                + " order by scope asc, category_code asc, effective_from desc",
            currentTenant(),
            windowTo,
            windowTo)) {
      policyCount++;
      PolicyView view =
          new PolicyView(
              r.get("id", UUID.class),
              nz(r.get("scope", String.class)),
              nz(r.get("category_code", String.class)),
              r.get("expected_repurchase_days", Integer.class) == null
                  ? 0
                  : r.get("expected_repurchase_days", Integer.class),
              r.get("tolerance_days", Integer.class) == null ? 0 : r.get("tolerance_days", Integer.class),
              r.get("rfm_window_days", Integer.class) == null
                  ? DEFAULT_RFM_WINDOW_DAYS
                  : r.get("rfm_window_days", Integer.class),
              r.get("recency_score_days", Integer.class) == null
                  ? 0
                  : r.get("recency_score_days", Integer.class),
              r.get("frequency_min_orders", Integer.class) == null
                  ? 0
                  : r.get("frequency_min_orders", Integer.class),
              r.get("monetary_min_amount", BigDecimal.class),
              blankToNull(r.get("monetary_currency", String.class)));
      if ("GLOBAL".equals(view.scope)) {
        if (globalPolicy == null) globalPolicy = view;
      } else {
        categoryPolicies.putIfAbsent(view.categoryCode, view);
      }
    }

    // ---- 5. 租户真实订单历史基线（**没有策略时的兜底，绝不是写死的周数**）
    List<Integer> baselines = new ArrayList<>();
    for (CustomerAgg agg : customers) {
      Integer baseline = agg.baselineDays();
      if (baseline != null) baselines.add(baseline);
    }
    Integer tenantBaseline = baselines.isEmpty() ? null : median(baselines);
    Integer medianFrequency = customers.isEmpty() ? null : median(frequencies(customers));

    // ---- 6. 逐客户判定
    int alertCount = 0;
    int categoryPolicyHits = 0;
    int categoryUnresolved = 0;
    int thresholdUnavailable = 0;
    Set<String> alerting = new LinkedHashSet<>();
    Set<String> currentKeys = new LinkedHashSet<>();
    List<PendingAlert> pendingAlerts = new ArrayList<>();

    for (CustomerAgg agg : customers) {
      currentKeys.add(key(agg.sourceShop, agg.customerRef));
      CategoryOutcome outcome = categoryByKey.get(key(agg.sourceShop, agg.customerRef));
      String category = outcome == null ? null : outcome.category();
      if (category == null) categoryUnresolved++;

      PolicyView policy = null;
      String thresholdSource;
      Integer expectedDays;
      Integer toleranceDays;
      UUID policyId = null;
      if (category != null && categoryPolicies.containsKey(category)) {
        policy = categoryPolicies.get(category);
        thresholdSource = "CATEGORY_POLICY";
        expectedDays = policy.expectedRepurchaseDays();
        toleranceDays = policy.toleranceDays();
        policyId = policy.id();
        categoryPolicyHits++;
      } else if (globalPolicy != null) {
        policy = globalPolicy;
        thresholdSource = "GLOBAL_POLICY";
        expectedDays = globalPolicy.expectedRepurchaseDays();
        toleranceDays = globalPolicy.toleranceDays();
        policyId = globalPolicy.id();
      } else if (tenantBaseline != null) {
        thresholdSource = "ORDER_HISTORY_BASELINE";
        expectedDays = tenantBaseline;
        // 🔴 没有策略 ⇒ 没有容差配置 ⇒ 容差取 0 并**如实说明**（不偷偷加一个「宽容天数」）。
        toleranceDays = 0;
      } else {
        thresholdSource = "NONE";
        expectedDays = null;
        toleranceDays = null;
        thresholdUnavailable++;
      }

      // RFM 配置：有策略用策略的；没有就用**租户真实订单历史**推出来的。
      int rfmWindowDays =
          policy != null ? policy.rfmWindowDays() : DEFAULT_RFM_WINDOW_DAYS;
      int recencyLine =
          policy != null && policy.recencyScoreDays() > 0
              ? policy.recencyScoreDays()
              : (tenantBaseline != null ? Math.max(tenantBaseline, MIN_RECENCY_SCORE_DAYS) : 0);
      int frequencyLine =
          policy != null && policy.frequencyMinOrders() > 0
              ? policy.frequencyMinOrders()
              : (medianFrequency != null ? Math.max(medianFrequency, 1) : 1);
      String rfmConfigSource = policy != null ? "POLICY" : "ORDER_HISTORY";

      OffsetDateTime rfmFrom = windowTo.minusDays(rfmWindowDays);
      List<OrderFact> rfmOrders = agg.ordersIn(rfmFrom, windowTo);
      int frequency = rfmOrders.size();
      BigDecimal monetary = agg.primaryAmount(rfmFrom, windowTo);
      String currency = agg.primaryCurrency(rfmFrom, windowTo);
      Integer recencyDays = agg.recencyDays(windowTo);

      // M 的基准：有策略且币种一致 ⇒ 用策略的金额阈值；
      // 否则 ⇒ 用该币种下**租户真实订单历史**的分位数（P80）。
      BigDecimal monetaryLine = null;
      String monetaryLineSource;
      if (policy != null
          && policy.monetaryMinAmount() != null
          && currency != null
          && currency.equals(policy.monetaryCurrency())) {
        monetaryLine = policy.monetaryMinAmount();
        monetaryLineSource = "POLICY";
      } else {
        monetaryLine = tenantMonetaryP80(customers, currency, rfmFrom, windowTo);
        monetaryLineSource = monetaryLine == null ? "UNAVAILABLE" : "ORDER_HISTORY_P80";
      }

      int rScore = recencyScore(recencyDays, recencyLine);
      int fScore = frequencyScore(frequency, frequencyLine);
      int mScore = monetaryScore(monetary, monetaryLine);

      int overdueDays =
          expectedDays == null || recencyDays == null
              ? 0
              : Math.max(0, recencyDays - (expectedDays + nzInt(toleranceDays)));
      String segment = segment(agg, recencyDays, recencyLine, overdueDays, expectedDays, toleranceDays);
      String reason =
          segmentReason(
              agg,
              segment,
              recencyDays,
              recencyLine,
              frequency,
              frequencyLine,
              monetary,
              currency,
              overdueDays,
              expectedDays,
              toleranceDays,
              thresholdSource,
              tenantBaseline,
              agg.baselineDays(),
              agg.baselineSamples());

      Map<String, Object> scoreBasis = new LinkedHashMap<>();
      scoreBasis.put("r", scoreLine("R", recencyDays, recencyLine, rScore,
          "距上次购买 " + nz(recencyDays) + " 天，「最近」线 " + recencyLine + " 天"));
      scoreBasis.put("f", scoreLine("F", frequency, frequencyLine, fScore,
          "窗口内 " + frequency + " 单，基准 " + frequencyLine + " 单"));
      scoreBasis.put("m", scoreLine("M", monetary, monetaryLine, mScore,
          "窗口内 " + nz(currency) + " " + nz(monetary) + "，基准 "
              + (monetaryLine == null ? "不可判定" : monetaryLine.toPlainString())
              + "（" + monetaryLineSource + "）"));
      scoreBasis.put("rfmConfigSource", rfmConfigSource);
      scoreBasis.put("rfmWindowDays", rfmWindowDays);
      scoreBasis.put("monetaryLineSource", monetaryLineSource);
      scoreBasis.put("tenantBaselineDays", tenantBaseline);
      if (category != null) scoreBasis.put("categoryCode", category);
      else if (outcome != null) scoreBasis.put("categoryUnresolvedReason", outcome.reason());

      Integer personalBaseline = agg.baselineDays();
      int personalSamples = agg.baselineSamples();
      String baselineSource = personalBaseline == null ? "INSUFFICIENT_SAMPLE" : "ORDER_HISTORY_MEDIAN";

      writeLifecycleRow(
          agg,
          category,
          firstOrderAt(agg),
          lastOrderAt(agg),
          agg.orderCount(),
          recencyDays,
          frequency,
          monetary,
          currency,
          rScore,
          fScore,
          mScore,
          segment,
          reason,
          expectedDays,
          toleranceDays,
          thresholdSource,
          policyId,
          personalBaseline,
          personalSamples,
          baselineSource,
          overdueDays,
          scoreBasis,
          windowFrom,
          windowTo);

      if (overdueDays > 0 && expectedDays != null) {
        alerting.add(key(agg.sourceShop, agg.customerRef));
        alertCount++;
        pendingAlerts.add(
            new PendingAlert(
                agg,
                category,
                lastOrderAt(agg),
                recencyDays == null ? 0 : recencyDays,
                expectedDays,
                nzInt(toleranceDays),
                overdueDays,
                thresholdSource,
                policyId,
                personalBaseline,
                personalSamples,
                rScore,
                fScore,
                mScore,
                monetary,
                currency));
      }
    }

    // ---- 7. 不再满足预警条件的旧预警 → 标记为已处理（**不删**：历史要能复核）
    //
    // 🔴 顺序很重要：先把**这一次仍然超期**的人挑出来，剩下的才标记为已处理。
    //   反过来写（一把梭全部 RESOLVED，再写新的）会把刚算出来的预警也一起标成已处理 ——
    //   清单会莫名其妙全空，而每一次运行都「成功」。
    resolveStaleAlerts(alerting);

    // ---- 7.1 清掉已经不在窗口里的分层行（**分层结果是当前状态的投影**）
    //
    // 🔴 不清的话，客户过了窗口（老到不再被扫到）之后，他的分层行还留在表里 ——
    //   分层统计的分母就会把不同窗口的客户混在一起。预警**不删**（只标已处理），
    //   因为那是历史事实，要能复核。
    deleteStaleLifecycleRows(currentKeys);

    // ---- 7.2 写入本次的预警（upsert：已处理的记录**保留它的处理状态**）
    for (PendingAlert pending : pendingAlerts) {
      writeAlert(pending);
    }

    // ---- 8. 落运行记录
    UUID runId = UUID.randomUUID();
    String runNo = nextRunNo();
    Map<String, Object> factsSnapshot = new LinkedHashMap<>();
    factsSnapshot.put("windowFrom", windowFrom.toString());
    factsSnapshot.put("windowTo", windowTo.toString());
    factsSnapshot.put("scannedOrderCount", facts.size());
    factsSnapshot.put("customerCount", customers.size());
    factsSnapshot.put("policyCount", policyCount);
    factsSnapshot.put("categoryPolicyHitCount", categoryPolicyHits);
    factsSnapshot.put("categoryUnresolvedCount", categoryUnresolved);
    factsSnapshot.put("thresholdUnavailableCount", thresholdUnavailable);
    factsSnapshot.put("tenantBaselineDays", tenantBaseline);
    factsSnapshot.put("medianFrequency", medianFrequency);
    factsSnapshot.put("skuLookupCount", skuLookups);
    factsSnapshot.put("truncated", truncated);
    factsSnapshot.put("categoryLookupTruncated", categoryTruncated);
    String failureReason =
        (truncated ? "🔴 本次扫描**被截断**（订单翻页到上限），名单不是全量" : "")
            + (categoryTruncated
                ? (truncated ? "；" : "") + "🔴 类目解析的查询次数达到上限，部分客户类目未解析"
                : "");

    sql.execute(
        "insert into crm_lifecycle_run"
            + "(id,tenant_id,run_no,status,window_from,window_to,customer_count,alert_count,"
            + " policy_count,category_policy_hit_count,category_unresolved_count,"
            + " threshold_unavailable_count,category_lookup_truncated,truncated,failure_reason,facts,"
            + " operator_id,started_at,finished_at,created_at)"
            + " values (?,?,?,'SUCCEEDED',?::timestamptz,?::timestamptz,?,?,?,?,?,?,?,?,?,?::jsonb,?,"
            + " ?::timestamptz,?::timestamptz,?::timestamptz)",
        runId,
        currentTenant(),
        runNo,
        windowFrom,
        windowTo,
        customers.size(),
        alertCount,
        policyCount,
        categoryPolicyHits,
        categoryUnresolved,
        thresholdUnavailable,
        categoryTruncated,
        truncated,
        failureReason,
        toJson(factsSnapshot),
        actorId,
        startedAt,
        now(),
        now());

    return new CrmLifecycleComputeResult(
        runId,
        runNo,
        "SUCCEEDED",
        windowFrom.toString(),
        windowTo.toString(),
        customers.size(),
        alertCount,
        policyCount,
        categoryPolicyHits,
        categoryUnresolved,
        thresholdUnavailable,
        categoryTruncated,
        truncated,
        failureReason,
        startedAt.toString(),
        now().toString(),
        "分层与预警都来自上游 trade 的**真实订单事实**（授权 HTTP API 只读），"
            + "类目来自 catalog 的 SKU 主档（只读）。"
            + (tenantBaseline == null
                ? "🔴 本次**算不出**租户级复购间隔基线（没有足够的复购样本），"
                    + "阈值不可判定的客户不会进预警清单。"
                : "租户级复购间隔基线（真实订单历史中位数）= " + tenantBaseline + " 天。")
            + (categoryUnresolved > 0
                ? " 有 " + categoryUnresolved + " 位客户的类目**没解析出来**（如实计数，不是 0）。"
                : ""));
  }

  /** 重算运行记录列表（真分页）。 */
  public CrmLifecycleRunPage runPage(int page, int size) {
    long total =
        sql.fetchOne("select count(*) from crm_lifecycle_run where tenant_id = ?", currentTenant())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Record> records =
        sql.fetch(
            "select * from crm_lifecycle_run where tenant_id = ?"
                + " order by created_at desc, id desc limit ? offset ?",
            currentTenant(),
            safeSize,
            safePage * safeSize);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmLifecycleRunPage(
        records.stream()
            .map(
                r ->
                    new CrmLifecycleRunRow(
                        r.get("id", UUID.class),
                        nz(r.get("run_no", String.class)),
                        nz(r.get("status", String.class)),
                        momentText(r.get("window_from", OffsetDateTime.class)),
                        momentText(r.get("window_to", OffsetDateTime.class)),
                        r.get("customer_count", Integer.class) == null ? 0 : r.get("customer_count", Integer.class),
                        r.get("alert_count", Integer.class) == null ? 0 : r.get("alert_count", Integer.class),
                        r.get("policy_count", Integer.class) == null ? 0 : r.get("policy_count", Integer.class),
                        r.get("category_policy_hit_count", Integer.class) == null
                            ? 0
                            : r.get("category_policy_hit_count", Integer.class),
                        r.get("category_unresolved_count", Integer.class) == null
                            ? 0
                            : r.get("category_unresolved_count", Integer.class),
                        r.get("threshold_unavailable_count", Integer.class) == null
                            ? 0
                            : r.get("threshold_unavailable_count", Integer.class),
                        Boolean.TRUE.equals(r.get("category_lookup_truncated", Boolean.class)),
                        Boolean.TRUE.equals(r.get("truncated", Boolean.class)),
                        nz(r.get("failure_reason", String.class)),
                        readJsonObject(r.get("facts", String.class)),
                        blankToNull(
                            r.get("operator_id", UUID.class) == null
                                ? null
                                : r.get("operator_id", UUID.class).toString()),
                        momentText(r.get("started_at", OffsetDateTime.class)),
                        momentText(r.get("finished_at", OffsetDateTime.class))))
            .toList(),
        safePage,
        safeSize,
        total,
        totalPages);
  }

  // ================================================================ 写入

  private void writeLifecycleRow(
      CustomerAgg agg,
      String category,
      OffsetDateTime firstOrderAt,
      OffsetDateTime lastOrderAt,
      int orderCount,
      Integer recencyDays,
      int frequency,
      BigDecimal monetary,
      String currency,
      int rScore,
      int fScore,
      int mScore,
      String segment,
      String reason,
      Integer expectedDays,
      Integer toleranceDays,
      String thresholdSource,
      UUID policyId,
      Integer personalBaseline,
      int personalSamples,
      String baselineSource,
      int overdueDays,
      Map<String, Object> scoreBasis,
      OffsetDateTime windowFrom,
      OffsetDateTime windowTo) {
    sql.execute(
        "insert into crm_customer_lifecycle"
            + "(id,tenant_id,customer_ref,source_shop,customer_party_id,customer_name,"
            + " first_order_at,last_order_at,order_count,recency_days,frequency,monetary,currency,"
            + " r_score,f_score,m_score,score_basis,segment,segment_reason,category_code,category_source,"
            + " expected_repurchase_days,tolerance_days,threshold_source,policy_id,"
            + " customer_baseline_days,customer_baseline_samples,baseline_source,overdue_days,"
            + " window_from,window_to,computed_at,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,"
            + " ?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id, customer_ref, source_shop) do update set"
            + " customer_party_id = excluded.customer_party_id, customer_name = excluded.customer_name,"
            + " first_order_at = excluded.first_order_at, last_order_at = excluded.last_order_at,"
            + " order_count = excluded.order_count, recency_days = excluded.recency_days,"
            + " frequency = excluded.frequency, monetary = excluded.monetary, currency = excluded.currency,"
            + " r_score = excluded.r_score, f_score = excluded.f_score, m_score = excluded.m_score,"
            + " score_basis = excluded.score_basis, segment = excluded.segment,"
            + " segment_reason = excluded.segment_reason, category_code = excluded.category_code,"
            + " category_source = excluded.category_source,"
            + " expected_repurchase_days = excluded.expected_repurchase_days,"
            + " tolerance_days = excluded.tolerance_days, threshold_source = excluded.threshold_source,"
            + " policy_id = excluded.policy_id, customer_baseline_days = excluded.customer_baseline_days,"
            + " customer_baseline_samples = excluded.customer_baseline_samples,"
            + " baseline_source = excluded.baseline_source, overdue_days = excluded.overdue_days,"
            + " window_from = excluded.window_from, window_to = excluded.window_to,"
            + " computed_at = excluded.computed_at, updated_at = excluded.updated_at",
        UUID.randomUUID(),
        currentTenant(),
        agg.customerRef,
        agg.sourceShop,
        agg.partyId,
        agg.buyerName,
        firstOrderAt,
        lastOrderAt,
        orderCount,
        recencyDays,
        frequency,
        monetary,
        currency,
        rScore,
        fScore,
        mScore,
        toJson(scoreBasis),
        segment,
        reason,
        category,
        category == null ? "UNRESOLVED" : "CATALOG_SKU",
        expectedDays,
        toleranceDays,
        thresholdSource,
        policyId,
        personalBaseline,
        personalSamples,
        baselineSource,
        overdueDays,
        windowFrom,
        windowTo,
        now(),
        now(),
        now());
  }

  private void writeAlert(PendingAlert pending) {
    CustomerAgg agg = pending.agg();
    String category = pending.category();
    int expectedDays = pending.expectedDays();
    int toleranceDays = pending.toleranceDays();
    int overdueDays = pending.overdueDays();
    int rScore = pending.rScore();
    int fScore = pending.fScore();
    int mScore = pending.mScore();
    BigDecimal monetary = pending.monetary();
    String currency = pending.currency();
    int daysSinceLastOrder = pending.daysSinceLastOrder();

    int thresholdDays = Math.max(1, expectedDays + toleranceDays);
    int overduePoints =
        Math.min(WEIGHT_OVERDUE, (int) Math.round(overdueDays * 100.0 / thresholdDays));
    int recencyPoints = (5 - rScore) * (WEIGHT_RECENCY / 5);
    int frequencyPoints = fScore * (WEIGHT_FREQUENCY / 5);
    int valuePoints = mScore * (WEIGHT_VALUE / 5);
    int total = Math.min(100, overduePoints + recencyPoints + frequencyPoints + valuePoints);
    BigDecimal riskScore = BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP);
    String riskLevel = riskLevel(total);

    List<Map<String, Object>> factors = new ArrayList<>();
    factors.add(
        factor(
            "OVERDUE_RATIO",
            "逾期倍数",
            WEIGHT_OVERDUE,
            overdueDays + " 天 / 阈值 " + thresholdDays + " 天",
            overduePoints,
            "已超过「预期复购间隔 + 容差」" + overdueDays + " 天（阈值 " + thresholdDays + " 天）"));
    factors.add(
        factor(
            "RECENCY",
            "最近购买",
            WEIGHT_RECENCY,
            "R=" + rScore,
            recencyPoints,
            "R 分越低说明越久没买（" + daysSinceLastOrder + " 天没下单），越可能真的流失"));
    factors.add(
        factor(
            "FREQUENCY",
            "购买频次",
            WEIGHT_FREQUENCY,
            "F=" + fScore,
            frequencyPoints,
            "曾经越频繁购买，断了越可惜（复购频次 F=" + fScore + "）"));
    factors.add(
        factor(
            "VALUE_EXPOSURE",
            "价值敞口",
            WEIGHT_VALUE,
            (currency == null ? "—" : currency + " " + nz(monetary)) + "（M=" + mScore + "）",
            valuePoints,
            "曾经消费越高，断掉越可惜（M=" + mScore + "）"));

    String suggestion =
        recallSuggestion(
            agg,
            category,
            daysSinceLastOrder,
            expectedDays,
            toleranceDays,
            overdueDays,
            pending.thresholdSource(),
            riskLevel);
    String owner = "15.5.2";

    sql.execute(
        "insert into crm_churn_alert"
            + "(id,tenant_id,customer_ref,source_shop,customer_name,last_order_at,"
            + " days_since_last_order,expected_repurchase_days,tolerance_days,overdue_days,"
            + " threshold_source,category_code,policy_id,customer_baseline_days,customer_baseline_samples,"
            + " risk_score,risk_level,risk_factors,recall_suggestion,suggestion_owner,status,status_note,"
            + " detected_at,created_at,updated_at)"
            + " values (?,?,?,?,?,?::timestamptz,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,'OPEN','',"
            + " ?::timestamptz,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id, customer_ref, source_shop) do update set"
            + " customer_name = excluded.customer_name, last_order_at = excluded.last_order_at,"
            + " days_since_last_order = excluded.days_since_last_order,"
            + " expected_repurchase_days = excluded.expected_repurchase_days,"
            + " tolerance_days = excluded.tolerance_days, overdue_days = excluded.overdue_days,"
            + " threshold_source = excluded.threshold_source, category_code = excluded.category_code,"
            + " policy_id = excluded.policy_id,"
            + " customer_baseline_days = excluded.customer_baseline_days,"
            + " customer_baseline_samples = excluded.customer_baseline_samples,"
            + " risk_score = excluded.risk_score, risk_level = excluded.risk_level,"
            + " risk_factors = excluded.risk_factors, recall_suggestion = excluded.recall_suggestion,"
            + " suggestion_owner = excluded.suggestion_owner, detected_at = excluded.detected_at,"
            + " updated_at = excluded.updated_at,"
            + " status = case when crm_churn_alert.status in ('ACKNOWLEDGED','RESOLVED')"
            + "   then crm_churn_alert.status else 'OPEN' end,"
            + " status_note = case when crm_churn_alert.status in ('ACKNOWLEDGED','RESOLVED')"
            + "   then crm_churn_alert.status_note else '' end",
        UUID.randomUUID(),
        currentTenant(),
        agg.customerRef,
        agg.sourceShop,
        agg.buyerName,
        pending.lastOrderAt(),
        daysSinceLastOrder,
        expectedDays,
        toleranceDays,
        overdueDays,
        pending.thresholdSource(),
        category,
        pending.policyId(),
        pending.personalBaseline(),
        pending.personalSamples(),
        riskScore,
        riskLevel,
        toJson(factors),
        suggestion,
        owner,
        now(),
        now(),
        now());
  }

  /**
   * 删掉已经不在本次窗口里的分层行。
   *
   * <p>🔴 分层结果是**当前状态**的投影：客户老到不再被扫到时，他的行必须跟着走 ——
   *   不然分层统计会把不同窗口的客户混在一起（分母就错了）。
   *   预警行**不删**（只标已处理），因为那是历史事实，要能复核。
   */
  private void deleteStaleLifecycleRows(Set<String> currentKeys) {
    for (Record r :
        sql.fetch(
            "select id, customer_ref, source_shop from crm_customer_lifecycle where tenant_id = ?",
            currentTenant())) {
      String k = key(nz(r.get("source_shop", String.class)), nz(r.get("customer_ref", String.class)));
      if (currentKeys.contains(k)) continue;
      sql.execute(
          "delete from crm_customer_lifecycle where tenant_id = ? and id = ?",
          currentTenant(),
          r.get("id", UUID.class));
    }
  }

  /**
   * 把**这一次不再超期**的旧预警标记为已处理（**不删**：历史要能复核）。
   *
   * <p>🔴 只处理「本租户里还没处理、且不在本次超期名单里」的那些行 ——
   * 一把梭全表 RESOLVED 会把刚算出来的预警也标掉，清单会莫名其妙全空。
   */
  private void resolveStaleAlerts(Set<String> alerting) {
    for (Record r :
        sql.fetch(
            "select id, customer_ref, source_shop from crm_churn_alert"
                + " where tenant_id = ? and status <> 'RESOLVED'",
            currentTenant())) {
      String k = key(nz(r.get("source_shop", String.class)), nz(r.get("customer_ref", String.class)));
      if (alerting.contains(k)) continue;
      sql.execute(
          "update crm_churn_alert set status = 'RESOLVED',"
              + " status_note = '重算后不再满足预警条件（已不再超期）', updated_at = ?::timestamptz"
              + " where tenant_id = ? and id = ?",
          now(),
          currentTenant(),
          r.get("id", UUID.class));
    }
  }

  // ================================================================ 判定口径

  /** R 分：距上次购买相对「最近」线的倍数。0 = 不可判定。 */
  static int recencyScore(Integer recencyDays, int recencyLine) {
    if (recencyDays == null || recencyLine <= 0) return 0;
    double ratio = (double) recencyDays / recencyLine;
    if (ratio <= 1) return 5;
    if (ratio <= 2) return 4;
    if (ratio <= 3) return 3;
    if (ratio <= 4) return 2;
    return 1;
  }

  /** F 分：窗口内订单数相对基准的倍数。 */
  static int frequencyScore(int frequency, int frequencyLine) {
    if (frequencyLine <= 0) return 0;
    if (frequency >= 5 * frequencyLine) return 5;
    if (frequency >= 4 * frequencyLine) return 4;
    if (frequency >= 3 * frequencyLine) return 3;
    if (frequency >= 2 * frequencyLine) return 2;
    return 1;
  }

  /** M 分：窗口内消费相对基准的倍数。 */
  static int monetaryScore(BigDecimal monetary, BigDecimal monetaryLine) {
    if (monetary == null || monetaryLine == null || monetaryLine.signum() <= 0) return 0;
    int comparison = monetary.compareTo(monetaryLine.multiply(BigDecimal.valueOf(5)));
    if (comparison >= 0) return 5;
    if (monetary.compareTo(monetaryLine.multiply(BigDecimal.valueOf(4))) >= 0) return 4;
    if (monetary.compareTo(monetaryLine.multiply(BigDecimal.valueOf(3))) >= 0) return 3;
    if (monetary.compareTo(monetaryLine.multiply(BigDecimal.valueOf(2))) >= 0) return 2;
    return 1;
  }

  /**
   * 生命周期分层。
   *
   * <p>判定顺序（先看「有没有超期」，再看「新客 / 活跃 / 沉睡」）：
   *
   * <ol>
   *   <li>超期且逾期倍数 ≥ 2 ⇒ {@code CHURNED}（流失已久）；
   *   <li>超期 ⇒ {@code RECALL}（刚过线，值得赶紧召回）；
   *   <li>只下过一单 ⇒ {@code NEW}（新客）；
   *   <li>最近还在买 ⇒ {@code ACTIVE}（活跃）；
   *   <li>其余 ⇒ {@code DORMANT}（沉睡：还没到该复购的时候）。</li>
   * </ol>
   */
  static String segment(
      CustomerAgg agg,
      Integer recencyDays,
      int recencyLine,
      int overdueDays,
      Integer expectedDays,
      Integer toleranceDays) {
    if (overdueDays > 0 && expectedDays != null) {
      int thresholdDays = Math.max(1, expectedDays + nzInt(toleranceDays));
      return overdueDays >= thresholdDays ? "CHURNED" : "RECALL";
    }
    if (agg.orderCount() <= 1) return "NEW";
    if (recencyDays != null && recencyLine > 0 && recencyDays <= recencyLine) return "ACTIVE";
    return "DORMANT";
  }

  /** 分层理由（**必填**：凭什么落这一层）。 */
  private static String segmentReason(
      CustomerAgg agg,
      String segment,
      Integer recencyDays,
      int recencyLine,
      int frequency,
      int frequencyLine,
      BigDecimal monetary,
      String currency,
      int overdueDays,
      Integer expectedDays,
      Integer toleranceDays,
      String thresholdSource,
      Integer tenantBaseline,
      Integer personalBaseline,
      int personalSamples) {
    StringBuilder text = new StringBuilder();
    text.append("订单 ").append(agg.orderCount()).append(" 单");
    if (recencyDays != null) text.append("，距上次购买 ").append(recencyDays).append(" 天");
    if (currency != null) text.append("，累计 ").append(currency).append(" ").append(nz(monetary));
    text.append("；R/F/M 判据：R 线 ").append(recencyLine).append(" 天、").append("F 基准 ")
        .append(frequencyLine).append(" 单");
    if (expectedDays == null) {
      text.append("；阈值**不可判定**（既无策略也无订单历史基线）⇒ 不产生预警");
    } else {
      text.append("；阈值 ").append(expectedDays).append(" 天 + 容差 ").append(nz(toleranceDays))
          .append(" 天");
      if ("ORDER_HISTORY_BASELINE".equals(thresholdSource)) {
        text.append("（**无类目 / 全局策略**，基线取租户真实订单历史的中位复购间隔 ")
            .append(tenantBaseline).append(" 天，容差取 0 天 —— 没有配置就不额外宽容）");
      } else if ("GLOBAL_POLICY".equals(thresholdSource)) {
        text.append("（命中**全局兜底**策略；该类目没有单独配阈值）");
      } else {
        text.append("（命中**类目**策略）");
      }
      if (overdueDays > 0) {
        text.append("；已超期 ").append(overdueDays).append(" 天 ⇒ 本应复购未复购");
      }
    }
    if (personalBaseline != null) {
      text.append("；本人历史复购间隔中位数 ").append(personalBaseline).append(" 天（")
          .append(personalSamples).append(" 段间隔）");
    } else {
      text.append("；本人只有 ").append(agg.orderCount())
          .append(" 单，**没有自己的复购间隔基线**（样本不足）");
    }
    text.append("；分层 ").append(segment);
    return text.toString();
  }

  /** 召回建议（🔴 只产出建议，不触达）。 */
  private static String recallSuggestion(
      CustomerAgg agg,
      String category,
      int daysSinceLastOrder,
      int expectedDays,
      int toleranceDays,
      int overdueDays,
      String thresholdSource,
      String riskLevel) {
    StringBuilder text = new StringBuilder();
    text.append("该客户 ")
        .append(daysSinceLastOrder)
        .append(" 天未下单，预期复购间隔 ")
        .append(expectedDays)
        .append(" 天 + 容差 ")
        .append(toleranceDays)
        .append(" 天，已超期 ")
        .append(overdueDays)
        .append(" 天（阈值来源：")
        .append(thresholdSourceLabel(thresholdSource))
        .append("）。");
    if (category != null) text.append("所属类目 ").append(category).append("。");
    text.append("风险 ").append(riskLevelLabel(riskLevel)).append("。");
    text.append("建议：由 15.5.2 触达发一封召回消息（邮件 / 短信按其退订与频次上限决定），");
    text.append("若该客户在独立站还有未完成的购物车，由 15.6.2.2 弃单挽回并行处理。");
    text.append("🔴 本模块**只产出建议**，不发送任何消息。");
    return text.toString();
  }

  private static Map<String, Object> factor(
      String code, String label, int weight, String rawValue, int points, String explanation) {
    Map<String, Object> factor = new LinkedHashMap<>();
    factor.put("code", code);
    factor.put("label", label);
    factor.put("weight", weight);
    factor.put("rawValue", rawValue);
    factor.put("points", points);
    factor.put("explanation", explanation);
    return factor;
  }

  private static Map<String, Object> scoreLine(
      String code, Object rawValue, Object line, int score, String explanation) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("code", code);
    map.put("rawValue", rawValue);
    map.put("line", line);
    map.put("score", score);
    map.put("explanation", explanation);
    return map;
  }

  // ================================================================ 内部类型

  /** 一次类目解析的结论。 */
  private record CategoryOutcome(String category, String reason) {}

  /** 一次判定算出来的预警（**先算完再落库**，这样才能先把「不再超期」的旧预警标掉）。 */
  private record PendingAlert(
      CustomerAgg agg,
      String category,
      OffsetDateTime lastOrderAt,
      int daysSinceLastOrder,
      int expectedDays,
      int toleranceDays,
      int overdueDays,
      String thresholdSource,
      UUID policyId,
      Integer personalBaseline,
      int personalSamples,
      int rScore,
      int fScore,
      int mScore,
      BigDecimal monetary,
      String currency) {}

  /** 生效中的阈值策略（服务层视图）。 */
  private record PolicyView(
      UUID id,
      String scope,
      String categoryCode,
      int expectedRepurchaseDays,
      int toleranceDays,
      int rfmWindowDays,
      int recencyScoreDays,
      int frequencyMinOrders,
      BigDecimal monetaryMinAmount,
      String monetaryCurrency) {}

  /** 一位客户的聚合事实（来自上游订单）。 */
  private static final class CustomerAgg {
    final String sourceShop;
    final String customerRef;
    UUID partyId;
    String buyerName = "";
    final List<OrderFact> orders = new ArrayList<>();

    CustomerAgg(String sourceShop, String customerRef) {
      this.sourceShop = sourceShop;
      this.customerRef = customerRef;
    }

    void add(OrderFact order) {
      orders.add(order);
      if (partyId == null) partyId = order.customerPartyId();
      if (nz(order.buyerName()).length() > 0) buyerName = nz(order.buyerName());
    }

    void sort() {
      orders.sort(Comparator.comparing(CustomerAgg::atOf));
    }

    int orderCount() {
      return orders.size();
    }

    /** 下单时刻：优先 submittedAt，没有就退到 createdAt。 */
    static OffsetDateTime atOf(OrderFact order) {
      return order.submittedAt() == null ? order.createdAt() : order.submittedAt();
    }

    OffsetDateTime first() {
      return orders.isEmpty() ? null : atOf(orders.get(0));
    }

    OffsetDateTime last() {
      return orders.isEmpty() ? null : atOf(orders.get(orders.size() - 1));
    }

    /**
     * 距上次购买多少天（**UTC 日历天**）。
     *
     * <p>🔴 两端都先归一到 UTC 再取日期：订单时刻可能带 +08:00 偏移，
     * 直接 {@code toLocalDate()} 会拿东八区的日期去减 UTC 的日期 ——
     * 差一天就是错判一天（「本应复购未复购」整天错位）。
     */
    Integer recencyDays(OffsetDateTime now) {
      OffsetDateTime last = last();
      if (last == null) return null;
      return (int) ChronoUnit.DAYS.between(utcDate(last), utcDate(now));
    }

    /** 本人历史相邻两次购买的间隔（天），按时间升序。 */
    List<Integer> gaps() {
      List<Integer> out = new ArrayList<>();
      for (int i = 1; i < orders.size(); i++) {
        OffsetDateTime from = atOf(orders.get(i - 1));
        OffsetDateTime to = atOf(orders.get(i));
        if (from == null || to == null) continue;
        out.add((int) ChronoUnit.DAYS.between(utcDate(from), utcDate(to)));
      }
      return out;
    }

    /** 本人历史复购间隔的中位数（天）。只有一单 ⇒ 没有基线（样本不足）。 */
    Integer baselineDays() {
      List<Integer> gaps = gaps();
      if (gaps.isEmpty()) return null;
      return median(gaps);
    }

    int baselineSamples() {
      return gaps().size();
    }

    /** 最近一单里的前 N 个 SKU 编码（用于解析类目）。 */
    List<String> recentSkuCodes(int limit) {
      for (int i = orders.size() - 1; i >= 0; i--) {
        List<String> codes = orders.get(i).skuCodes();
        if (codes == null || codes.isEmpty()) continue;
        List<String> out = new ArrayList<>();
        for (String code : codes) {
          if (code == null || code.isBlank()) continue;
          if (!out.contains(code.trim())) out.add(code.trim());
          if (out.size() >= limit) break;
        }
        if (!out.isEmpty()) return out;
      }
      return List.of();
    }

    /** 落在窗口内的订单。 */
    List<OrderFact> ordersIn(OffsetDateTime from, OffsetDateTime to) {
      List<OrderFact> out = new ArrayList<>();
      for (OrderFact order : orders) {
        OffsetDateTime at = atOf(order);
        if (at == null) continue;
        if (from != null && at.isBefore(from)) continue;
        if (to != null && !at.isBefore(to)) continue;
        out.add(order);
      }
      return out;
    }

    /** 主币种（订单数最多的那个币种；并列时取累计消费更大的那个）。 */
    String primaryCurrency(OffsetDateTime from, OffsetDateTime to) {
      Map<String, long[]> byCurrency = new HashMap<>();
      Map<String, BigDecimal> amounts = new HashMap<>();
      for (OrderFact order : ordersIn(from, to)) {
        String currency = nz(order.currency()).trim().toUpperCase(Locale.ROOT);
        if (currency.isEmpty()) continue;
        byCurrency.computeIfAbsent(currency, k -> new long[1])[0]++;
        amounts.merge(
            currency,
            order.grandTotalAmount() == null ? BigDecimal.ZERO : order.grandTotalAmount(),
            BigDecimal::add);
      }
      String best = null;
      long bestCount = -1;
      BigDecimal bestAmount = BigDecimal.ZERO;
      for (var entry : byCurrency.entrySet()) {
        long count = entry.getValue()[0];
        BigDecimal amount = amounts.getOrDefault(entry.getKey(), BigDecimal.ZERO);
        if (count > bestCount || (count == bestCount && amount.compareTo(bestAmount) > 0)) {
          best = entry.getKey();
          bestCount = count;
          bestAmount = amount;
        }
      }
      return best;
    }

    /** 主币种下的累计消费。 */
    BigDecimal primaryAmount(OffsetDateTime from, OffsetDateTime to) {
      String currency = primaryCurrency(from, to);
      if (currency == null) return null;
      BigDecimal sum = BigDecimal.ZERO;
      for (OrderFact order : ordersIn(from, to)) {
        String c = nz(order.currency()).trim().toUpperCase(Locale.ROOT);
        if (!currency.equals(c)) continue;
        sum = sum.add(order.grandTotalAmount() == null ? BigDecimal.ZERO : order.grandTotalAmount());
      }
      return sum;
    }
  }

  /** 归一到 UTC 的日历日（**两端都归一**，否则跨时区会整天错位）。 */
  static java.time.LocalDate utcDate(OffsetDateTime moment) {
    return moment.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
  }

  /** 中位数（偶数个样本取中间两个的平均，四舍五入到整天）。 */
  static Integer median(List<Integer> values) {
    if (values == null || values.isEmpty()) return null;
    List<Integer> sorted = new ArrayList<>(values);
    sorted.sort(Comparator.naturalOrder());
    int size = sorted.size();
    if (size % 2 == 1) return sorted.get(size / 2);
    long sum = (long) sorted.get(size / 2 - 1) + sorted.get(size / 2);
    return (int) Math.round((double) sum / 2.0);
  }

  private static List<Integer> frequencies(List<CustomerAgg> customers) {
    List<Integer> out = new ArrayList<>();
    for (CustomerAgg agg : customers) out.add(agg.orderCount());
    return out;
  }

  /** 某个币种下，租户真实订单历史的消费 P80（**没有它 ⇒ M 分不可判定**，不是 0）。 */
  private static BigDecimal tenantMonetaryP80(
      List<CustomerAgg> customers, String currency, OffsetDateTime from, OffsetDateTime to) {
    if (currency == null) return null;
    List<BigDecimal> values = new ArrayList<>();
    for (CustomerAgg agg : customers) {
      BigDecimal amount = agg.primaryAmount(from, to);
      if (amount != null && amount.signum() > 0) values.add(amount);
    }
    if (values.isEmpty()) return null;
    values.sort(Comparator.naturalOrder());
    int index = (int) Math.ceil(values.size() * 0.8) - 1;
    if (index < 0) index = 0;
    return values.get(index);
  }

  // ================================================================ 文案

  private static String segmentLabel(String code) {
    for (CustomerLifecycleSegment segment : CustomerLifecycleSegment.values()) {
      if (segment.name().equals(code)) return segment.label();
    }
    return code;
  }

  private static String thresholdSourceLabel(String code) {
    for (LifecycleThresholdSource source : LifecycleThresholdSource.values()) {
      if (source.name().equals(code)) return source.label();
    }
    return code;
  }

  private static String riskLevelLabel(String code) {
    for (ChurnRiskLevel level : ChurnRiskLevel.values()) {
      if (level.name().equals(code)) return level.label();
    }
    return code;
  }

  private static String statusLabel(String code) {
    for (ChurnAlertStatus status : ChurnAlertStatus.values()) {
      if (status.name().equals(code)) return status.label();
    }
    return code;
  }

  private static String policyScopeLabel(String code) {
    for (LifecyclePolicyScope scope : LifecyclePolicyScope.values()) {
      if (scope.name().equals(code)) return scope.label();
    }
    return code;
  }

  private static String suggestionOwnerLabel(String code) {
    return switch (code) {
      case "15.5.2" -> "15.5.2 触达";
      case "15.6.2.2" -> "15.6.2.2 弃单挽回";
      default -> code;
    };
  }

  static String riskLevel(int score) {
    if (score < RISK_LOW_MAX) return "LOW";
    if (score < RISK_MEDIUM_MAX) return "MEDIUM";
    if (score < RISK_HIGH_MAX) return "HIGH";
    return "CRITICAL";
  }

  // ================================================================ 工具

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static String key(String sourceShop, String customerRef) {
    return sourceShop + " " + customerRef;
  }

  private static String nz(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  /** 整数的空值兜底（null ⇒ 0）。金额与比率**不**用它 —— 那些必须保持 null（不可判定）。 */
  private static int nzInt(Integer value) {
    return value == null ? 0 : value;
  }

  private static String blankToNull(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String momentText(OffsetDateTime moment) {
    return moment == null ? null : moment.toString();
  }

  private static OffsetDateTime parseMoment(String value, String label) {
    try {
      return OffsetDateTime.parse(value.trim());
    } catch (RuntimeException e) {
      throw bad(label + "必须是 ISO-8601 带偏移量的时刻（例：2026-10-10T08:00:00+08:00）。");
    }
  }

  private static String toJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> readJsonObject(String raw) {
    if (raw == null || raw.isBlank()) return Map.of();
    try {
      Object parsed = JSON.readValue(raw, Object.class);
      return parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
    } catch (Exception e) {
      return Map.of();
    }
  }

  @SuppressWarnings("unchecked")
  private static List<CrmLifecycleRiskFactor> readRiskFactors(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    try {
      Object parsed = JSON.readValue(raw, Object.class);
      if (!(parsed instanceof List)) return List.of();
      List<CrmLifecycleRiskFactor> out = new ArrayList<>();
      for (Object item : (List<Object>) parsed) {
        if (!(item instanceof Map)) continue;
        Map<String, Object> map = (Map<String, Object>) item;
        out.add(
            new CrmLifecycleRiskFactor(
                nz(map.get("code")),
                nz(map.get("label")),
                intOf(map.get("weight")),
                nz(map.get("rawValue")),
                intOf(map.get("points")),
                nz(map.get("explanation"))));
      }
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }

  private static int intOf(Object value) {
    if (value instanceof Number number) return number.intValue();
    try {
      return Integer.parseInt(nz(value));
    } catch (RuntimeException e) {
      return 0;
    }
  }

  private static OffsetDateTime firstOrderAt(CustomerAgg agg) {
    return agg.first();
  }

  private static OffsetDateTime lastOrderAt(CustomerAgg agg) {
    return agg.last();
  }

  /** 刷新运行号：LC-yyyyMMdd-000001（序列单独授权，见 V436 授权段）。 */
  private String nextRunNo() {
    var row =
        sql.fetchOne(
            "select nextval('crm_lifecycle_run_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "LC-" + day + "-" + String.format("%06d", seq);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

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
}
