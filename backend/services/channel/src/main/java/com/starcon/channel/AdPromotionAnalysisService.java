package com.starcon.channel;

import static com.starcon.channel.AdPromotionAnalysisModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 14.4.2.1 促销效果分析。
 *
 * <p>蓝图：「促销效果分析：促销期间销量/销售额/毛利变化、增量判断、补贴成本、ROI，与活动前的基期对比。
 * 输入：促销数据；历史基线；利润数据。输出：效果报表 + 增量估算 + ROI。
 * 关系：上游 = 14.4.1；下游 = 13.4 利润、14.2.2.1 预算。
 * <strong>促销期间毛利可能大幅下滑</strong>，评估必须看毛利而不是销售额。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>促销期间销量/销售额变化</b> → {@link #report}：事实源是同库
 *       {@code listing_performance_snapshot}（平台回报口径），本模块<b>只读不复制</b>。
 *   <li><b>促销期间毛利变化</b> → {@link #report}：毛利 = 销售额 − 单位成本 × 销量，
 *       单位成本来自<b>本服务自己的成本快照表</b>（catalog 的成本被复制进来，不跨服务读）。
 *   <li><b>增量判断</b> → {@link #report} 的 {@code incrementVerdict}：判据是<b>增量毛利</b>。
 *   <li><b>补贴成本</b> → {@link #runAnalysis} 写入的 {@code subsidy_cost}。
 *   <li><b>ROI</b> → {@link #report} 的 {@code roiNumerator} / {@code roiDenominator} / {@code roiValue}。
 *   <li><b>与活动前的基期对比</b> → {@link #runAnalysis} 落的 {@code ad_promotion_effect_baseline}。
 *   <li><b>效果报表 + 增量估算 + ROI</b> → {@link #report}（真聚合 / 真分页 / 真筛选 / 真排序）。
 * </ul>
 *
 * <p>🔴 本类最容易做丢的五件事，这里都挡住了：
 *
 * <ol>
 *   <li><b>判定看毛利，不看销售额</b>。蓝图那句是加粗的：「评估必须看毛利而不是销售额」。
 *       所以 {@code incrementVerdict} 的判据是<b>增量毛利</b>；销售额增量只作并列指标；
 *       {@code marginAlert} 专门标「放量不赚」（销售额涨了但毛利率跌了）。</li>
 *   <li><b>不可比不是 0</b>。没登记成本快照 ⇒ 毛利为 null（不可比）；
 *       百分比促销没登记基准价 ⇒ 补贴为 null；促销类型没有可折算价格系数 ⇒ 补贴为 null。
 *       0 会被渲染成「毛利是 0」「没花钱」，那与「拿不到」在运营决策上完全相反。</li>
 *   <li><b>活动维度是真聚合</b>。报表的活动维度用 SQL {@code GROUP BY} 现算，
 *       <b>不在库里再存一份活动级汇总行</b> —— 两份数会漂移，而漂移的那份没人会发现。
 *       聚合口径是「先汇总再相除」（Σ毛利 / Σ销售额），不是各商品毛利率的平均。</li>
 *   <li><b>同一天的平台快照不相加</b>。{@code listing_performance_snapshot} 的 UNIQUE 是
 *       {@code (tenant_id, listing_id, stat_date, source)}，同一天可能有 PLATFORM 与 API 两条来源，
 *       直接 {@code sum()} 会把同一天的成交算两遍 ⇒ 取<b>最近收到的那一条</b>。</li>
 *   <li><b>分子分母各自落库</b>。{@code roi_numerator} / {@code roi_denominator} /
 *       {@code increment_gross_profit} 都在库里，事后谁都能自己复核，
 *       不必相信服务端算出来的比率。</li>
 * </ol>
 *
 * <p>🔴 跨服务纪律：<b>成本与售价在 catalog 服务</b>，channel 永不 join catalog 的表。
 * 本模块把它们<b>复制进自己的表</b>（{@code ad_promotion_cost_snapshot}，V287），
 * 写入口是 {@link #saveCostSnapshot} —— 这是「毛利」能落库的唯一通道。
 * 13.4 利润服务（reporting）未交付 ⇒ 毛利 / 增量 / ROI 由本模块用显式 SQL 自算。
 */
@Service
public class AdPromotionAnalysisService {

  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 报表维度。 */
  static final List<String> DIMENSIONS = List.of("PROMOTION", "PRODUCT");

  /** 🔴 增量判定取值。判据是**增量毛利**，不是增量销售额。 */
  static final List<String> INCREMENT_VERDICTS =
      List.of("INCREMENTAL", "FLAT", "DECREMENTAL", "INSUFFICIENT_DATA");

  /** 🔴 默认排序 = **按毛利排序**（蓝图加粗句的直接落点：先看赚不赚钱）。 */
  static final String DEFAULT_SORT = "GROSS_PROFIT_DESC";

  /** 🔴 分页上限：报表一次最多取这么多行。 */
  static final int MAX_SIZE = 200;

  /** 🔴 导出的批大小（每批在流式线程里重开一次租户事务）。 */
  private static final int EXPORT_BATCH = 200;

  /** 有可折算价格系数的促销类型（其余类型 ⇒ 让利单价不可比）。 */
  private static final List<String> PRICE_BEARING_TYPES =
      List.of("PERCENT_OFF", "FIXED_AMOUNT_OFF");

  // ==========================================================================
  // 口径定义：写在返回值里，不写在注释里
  // ==========================================================================

  /** 销量与销售额的口径。 */
  static final String SALES_NOTE =
      "销量 / 销售额 = listing_performance_snapshot 的**平台回报口径**（按 listing × 自然日），"
          + "本模块只读不复制这张表。"
          + "🔴 同一天若同时存在 PLATFORM 与 API 两条来源，**取最近收到的那一条**，"
          + "不相加 —— 相加会把同一天的成交算两遍。"
          + "促销期与基期都按**站点当地自然日**切分（marketplace.default_timezone，"
          + "时区名认不到时退回 UTC，不让一条脏码表把整次分析打挂）。"
          + "销售额只统计与**促销币种一致**的快照行；本仓没有汇率源，跨币种不能相加。";

  /** 毛利口径。 */
  static final String MARGIN_NOTE =
      "毛利 = 销售额 − 单位成本 × 销量。单位成本来自 **ad_promotion_cost_snapshot**"
          + "（catalog 的成本被**复制**进 channel 自己的表，channel 永不 join catalog）。"
          + "🔴 没登记成本快照 ⇒ 毛利为**空值（不可比）**，不是 0。"
          + "促销期没有平台快照的天数会记进「覆盖天数」：覆盖 0 天 ⇒ 毛利不可比，"
          + "「没有数据」与「有数据但销量是 0」是两件事，不许混。";

  /** 基期口径。 */
  static final String BASELINE_NOTE =
      "基期 = 促销首日之前**等长且紧邻**的一段：基期起 = 促销首日 − 促销天数，基期止 = 促销首日 − 1。"
          + "两窗口等长 ⇒ 增量可以直接相减，是**总量口径**而不是日均口径；"
          + "同时落日均值供按天口径复核。"
          + "基期与促销期共用**同一份单位成本快照**（本表快照粒度是促销 × 商品，不是按天）；"
          + "成本随时间变化的影响要靠多次登记快照覆盖。";

  /** 增量判定口径。 */
  static final String INCREMENT_NOTE =
      "🔴 增量判定看**毛利**不看销售额（蓝图加粗句：促销期间毛利可能大幅下滑，"
          + "评估必须看毛利而不是销售额）。判据是**增量毛利** = 促销期毛利 − 基期毛利："
          + "INCREMENTAL（> 0）/ FLAT（= 0）/ DECREMENTAL（< 0）/ "
          + "INSUFFICIENT_DATA（缺成本快照，或促销期 / 基期任一侧没有平台快照）。"
          + "销售额增量只作**并列指标**出现，不参与判定。";

  /** 补贴成本口径。 */
  static final String SUBSIDY_NOTE =
      "补贴成本 = 单件让利 × **实际促销期销量**。"
          + "（14.4.1.1 的预算上限算的是「最坏情况」让利额，那是**事前拦截**口径；"
          + "本模块是**事后**分析，知道实际销量。）"
          + "单件让利与 14.4.1.1 的折后价口径逐字一致："
          + "PERCENT_OFF = 基准价 × 折扣率；FIXED_AMOUNT_OFF = 固定减免额；"
          + "BOGO / BUNDLE / FREE_SHIPPING **没有可折算的价格系数 ⇒ 让利单价不可比（空值）**。"
          + "基准价取自成本快照；百分比促销缺基准价 ⇒ 补贴不可比（固定额促销不需要基准价）。"
          + "🔴 补贴不可比时是**空值**，不是 0 —— 0 会被读成「这次没花钱」。";

  /** ROI 口径。 */
  static final String ROI_NOTE =
      "ROI = 增量毛利 ÷ 补贴成本。**分子分母各自落库**（roi_numerator / roi_denominator），"
          + "事后任何人自己除一遍都能复核，不必相信本模块。"
          + "分母 ≤ 0 或任一端不可比 ⇒ ROI 为**空值（不可比）**，不是 0。"
          + "🔴 负 ROI 是「促销把毛利做没了」最直接的读数，所以本模块**不拦负数**。";

  /** 上游边界说明。 */
  static final String UPSTREAM_NOTE =
      "上游 = 14.4.1 促销列表（同库同服务：ad_promotion / ad_promotion_product）+ "
          + "4.1.x listing_performance_snapshot（同库同服务，平台回报口径）+ "
          + "catalog 的成本 / 售价（**复制**进本服务，不跨服务读）。";

  /** 下游边界说明。 */
  static final String BOUNDARY_NOTE =
      "下游 = 13.4 利润（reporting 服务**未交付** ⇒ 毛利与增量由本模块自算，"
          + "将来与 13.4 的口径可能不一致，对齐时要以本模块的口径说明为准）、"
          + "14.2.2.1 预算（补贴成本可作为预算复盘的输入）。"
          + "平台促销归因接口未交付 ⇒ 本模块的增量是「同商品活动前 vs 活动期」的对比，"
          + "**不是**平台口径的促销归因。";

  /** 报表整体口径（随报表与导出一起返回）。 */
  static final String METRIC_DEFINITION_NOTE =
      SALES_NOTE
          + " "
          + MARGIN_NOTE
          + " "
          + BASELINE_NOTE
          + " "
          + INCREMENT_NOTE
          + " "
          + SUBSIDY_NOTE
          + " "
          + ROI_NOTE
          + " 🔴 活动维度是**真聚合**（SQL GROUP BY）：毛利率按「先汇总再相除」算，"
          + "不是各商品毛利率的平均（对比率求平均是数学错误）。"
          + "只要有一个商品不可比，该活动的对应指标就是**空值**而不是部分和 —— "
          + "部分和会被读成「这个活动就是赚这么少」。";

  // ==========================================================================
  // 排序白名单（排序串绝不拼进 SQL）
  // ==========================================================================

  private static final Map<String, String> SORTS =
      Map.ofEntries(
          Map.entry(
              "GROSS_PROFIT_DESC",
              "order by f.promo_gross_profit desc nulls last, f.promotion_code asc, f.listing_id asc"),
          Map.entry(
              "GROSS_PROFIT_ASC",
              "order by f.promo_gross_profit asc nulls last, f.promotion_code asc, f.listing_id asc"),
          Map.entry(
              "INCREMENT_DESC",
              "order by f.increment_gross_profit desc nulls last, f.promotion_code asc, f.listing_id asc"),
          Map.entry(
              "INCREMENT_ASC",
              "order by f.increment_gross_profit asc nulls last, f.promotion_code asc, f.listing_id asc"),
          Map.entry(
              "SALES_DESC", "order by f.promo_sales desc, f.promotion_code asc, f.listing_id asc"),
          Map.entry(
              "SALES_ASC", "order by f.promo_sales asc, f.promotion_code asc, f.listing_id asc"),
          Map.entry(
              "ROI_DESC",
              "order by (case when f.roi_denominator is not null and f.roi_denominator > 0"
                  + " then f.roi_numerator/f.roi_denominator end) desc nulls last,"
                  + " f.promotion_code asc, f.listing_id asc"),
          Map.entry(
              "ALERT_FIRST",
              "order by (case when f.computed_margin_alert then 0 else 1 end) asc,"
                  + " f.promo_gross_profit desc nulls last, f.promotion_code asc, f.listing_id asc"),
          Map.entry("NAME_ASC", "order by f.promotion_code asc, f.listing_id asc"));

  private final DSLContext sql;
  private final ObjectMapper json;
  private final AccessService access;

  public AdPromotionAnalysisService(DSLContext sql, ObjectMapper json, AccessService access) {
    this.sql = sql;
    this.json = json;
    this.access = access;
  }

  // ==========================================================================
  // 小工具
  // ==========================================================================

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private long countOf(String query, Object... binds) {
    Long v = one(Long.class, query, binds);
    return v == null ? 0L : v;
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 绑定，不来自任何客户端头）。 */
  private UUID currentTenant() {
    String raw = one(String.class, "select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /** 当前操作者（已认证成员）。 */
  private UUID currentActor() {
    String raw = one(String.class, "select nullif(current_setting('app.actor',true),'')");
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少操作者上下文");
    }
    return UUID.fromString(raw);
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  /**
   * 能力声明：逐维度说清「哪些能算、哪些拿不到」。
   *
   * <p>🔴 三个<b>不可比</b>维度必须显式 available=false，各自带 ownerModule 与影响说明：
   * 平台归因（平台促销 API 未交付）、13.4 利润（reporting 服务未交付）、汇率换算（12 财务未交付）。
   */
  public AdPromotionAnalysisCapabilities capabilities() {
    List<AdPromotionAnalysisCapabilityDimension> dimensions =
        List.of(
            new AdPromotionAnalysisCapabilityDimension(
                "sales",
                "促销期销量 / 销售额（含基期对照）",
                true,
                "listing_performance_snapshot（同库同服务，平台回报口径）",
                "",
                "同一天多来源取最近收到的一条；跨币种不合并。"),
            new AdPromotionAnalysisCapabilityDimension(
                "margin",
                "促销期毛利 / 毛利率",
                true,
                "本模块显式 SQL：销售额 − 单位成本 × 销量",
                "",
                "单位成本来自成本快照（复制自 catalog）；没登记 ⇒ 空值（不可比），不是 0。"),
            new AdPromotionAnalysisCapabilityDimension(
                "baseline",
                "与活动前的基期对比",
                true,
                "ad_promotion_effect_baseline（V287）+ listing_performance_snapshot",
                "",
                "基期 = 促销首日之前等长且紧邻的一段，增量为总量口径。"),
            new AdPromotionAnalysisCapabilityDimension(
                "increment",
                "增量判断",
                true,
                "本模块显式 SQL：促销期 − 基期",
                "",
                "🔴 判据是**增量毛利**，不是增量销售额。"),
            new AdPromotionAnalysisCapabilityDimension(
                "subsidy",
                "补贴成本",
                true,
                "单件让利（照抄 14.4.1.1 折后价口径）× 实际促销期销量",
                "",
                "BOGO / BUNDLE / FREE_SHIPPING 无可折算价格系数 ⇒ 不可比（空值）。"),
            new AdPromotionAnalysisCapabilityDimension(
                "roi",
                "ROI（增量毛利 ÷ 补贴成本）",
                true,
                "roi_numerator / roi_denominator 各自落库，比率为生成列",
                "",
                "分母 ≤ 0 ⇒ 空值（不可比）；负 ROI 不拦。"),
            new AdPromotionAnalysisCapabilityDimension(
                "platform_attribution",
                "平台口径的促销归因",
                false,
                "—",
                "平台促销 API（未交付）",
                "平台侧把成交归因到具体促销的接口没有 ⇒ "
                    + "本模块的增量是「同商品活动前 vs 活动期」的对比，**不是**平台口径的促销归因。"),
            new AdPromotionAnalysisCapabilityDimension(
                "profit_report",
                "13.4 利润报表口径",
                false,
                "—",
                "13.4 利润（reporting 服务未交付）",
                "本模块自算毛利与增量（写死口径），将来与 13.4 的报表口径可能不一致，"
                    + "对齐时要以本模块的口径说明为准。"),
            new AdPromotionAnalysisCapabilityDimension(
                "fx",
                "跨币种汇率换算",
                false,
                "—",
                "12 财务（未交付）",
                "本仓没有汇率源 ⇒ 销售额只统计与促销币种一致的快照行，跨币种不合并。"),
            new AdPromotionAnalysisCapabilityDimension(
                "unit_cost",
                "单位成本 / 基准价快照",
                true,
                "ad_promotion_cost_snapshot（V287，写入口 = 本模块的登记端点）",
                "",
                "🔴 catalog 的成本**复制**进本服务（不跨服务读）。"
                    + "没登记 ⇒ 毛利不可比；百分比促销没登记基准价 ⇒ 补贴不可比。"));
    return new AdPromotionAnalysisCapabilities(
        dimensions,
        INCREMENT_VERDICTS,
        DIMENSIONS,
        List.copyOf(SORTS.keySet()),
        SALES_NOTE,
        MARGIN_NOTE,
        BASELINE_NOTE,
        INCREMENT_NOTE,
        SUBSIDY_NOTE,
        ROI_NOTE,
        UPSTREAM_NOTE,
        BOUNDARY_NOTE);
  }

  // ==========================================================================
  // 下拉项
  // ==========================================================================

  /** 下拉项 + 口径说明（界面据此预填筛选与表头）。 */
  public AdPromotionAnalysisOptions options() {
    List<AdPromotionAnalysisOption> verdicts =
        List.of(
            new AdPromotionAnalysisOption("INCREMENTAL", "增量（毛利增加）"),
            new AdPromotionAnalysisOption("FLAT", "持平（毛利不变）"),
            new AdPromotionAnalysisOption("DECREMENTAL", "下滑（毛利减少）"),
            new AdPromotionAnalysisOption("INSUFFICIENT_DATA", "数据不足（不可判定）"));

    List<AdPromotionAnalysisOption> dimensions =
        List.of(
            new AdPromotionAnalysisOption("PROMOTION", "活动维度"),
            new AdPromotionAnalysisOption("PRODUCT", "商品维度"));

    List<AdPromotionAnalysisOption> sorts =
        List.of(
            new AdPromotionAnalysisOption("GROSS_PROFIT_DESC", "促销期毛利：高 → 低"),
            new AdPromotionAnalysisOption("GROSS_PROFIT_ASC", "促销期毛利：低 → 高"),
            new AdPromotionAnalysisOption("INCREMENT_DESC", "增量毛利：高 → 低"),
            new AdPromotionAnalysisOption("INCREMENT_ASC", "增量毛利：低 → 高"),
            new AdPromotionAnalysisOption("SALES_DESC", "销售额：高 → 低"),
            new AdPromotionAnalysisOption("SALES_ASC", "销售额：低 → 高"),
            new AdPromotionAnalysisOption("ROI_DESC", "ROI：高 → 低"),
            new AdPromotionAnalysisOption("ALERT_FIRST", "放量不赚优先"),
            new AdPromotionAnalysisOption("NAME_ASC", "促销编码"));

    List<AdPromotionAnalysisPromotionOption> promotions =
        sql.fetch(
                "select id, promotion_code, promotion_name, promotion_type, currency, start_at, end_at,"
                    + " status from ad_promotion where tenant_id = "
                    + TENANT
                    + " order by promotion_code limit 500")
            .stream()
            .map(
                r ->
                    new AdPromotionAnalysisPromotionOption(
                        r.get("id", UUID.class).toString(),
                        r.get("promotion_code", String.class),
                        r.get("promotion_name", String.class),
                        r.get("promotion_type", String.class),
                        r.get("currency", String.class),
                        r.get("start_at", OffsetDateTime.class),
                        r.get("end_at", OffsetDateTime.class),
                        r.get("status", String.class)))
            .toList();

    List<AdPromotionAnalysisPlatformOption> platforms =
        sql.fetch("select id, code, name from platform order by position, code").stream()
            .map(
                r ->
                    new AdPromotionAnalysisPlatformOption(
                        r.get("id", UUID.class).toString(),
                        r.get("code", String.class),
                        r.get("name", String.class)))
            .toList();

    List<AdPromotionAnalysisShopOption> shops =
        sql.fetch(
                "select code, name from shop where tenant_id = "
                    + TENANT
                    + " and status <> 'ARCHIVED' order by code")
            .stream()
            .map(
                r ->
                    new AdPromotionAnalysisShopOption(
                        r.get("code", String.class), r.get("name", String.class)))
            .toList();

    List<String> currencies =
        sql.fetch(
                "select distinct currency from ad_promotion where tenant_id = "
                    + TENANT
                    + " and btrim(currency) <> '' order by currency")
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();

    return new AdPromotionAnalysisOptions(
        verdicts,
        dimensions,
        sorts,
        promotions,
        platforms,
        shops,
        currencies,
        SALES_NOTE,
        MARGIN_NOTE,
        BASELINE_NOTE);
  }

  // ==========================================================================
  // 分析运行
  // ==========================================================================

  /**
   * 跑一次分析：算促销期与基期，落基期快照与效果结果表，留运行痕迹。
   *
   * <p>🔴 **跳过的促销不许静默消失**：没起止时间 / 没有适用商品的促销都要记进
   * {@code skipped}（jsonb），并把运行状态标成 PARTIAL。
   */
  public AdPromotionAnalysisRunRow runAnalysis(RunAnalysisRequest request) {
    UUID tenant = currentTenant();
    UUID actor = currentActor();

    List<Object> binds = new ArrayList<>();
    String sqlText =
        "select id, promotion_code, promotion_name, promotion_type, discount_rate, discount_amount,"
            + " currency, start_at, end_at from ad_promotion where tenant_id = "
            + TENANT
            + " and status in ('SCHEDULED','ACTIVE','PAUSED','ENDED')";
    if (request.promotionId() != null) {
      sqlText += " and id = ?";
      binds.add(request.promotionId());
    }
    sqlText += " order by promotion_code";

    List<Record> promotions = sql.fetch(sqlText, binds.toArray());

    List<Map<String, String>> skipped = new ArrayList<>();
    int analyzed = 0;
    OffsetDateTime dataAsOf = null;

    for (Record p : promotions) {
      UUID promotionId = p.get("id", UUID.class);
      String code = p.get("promotion_code", String.class);
      OffsetDateTime startAt = p.get("start_at", OffsetDateTime.class);
      OffsetDateTime endAt = p.get("end_at", OffsetDateTime.class);

      if (startAt == null || endAt == null) {
        skipped.add(skip(promotionId, code, "促销未设置起止时间，无法界定促销期与基期"));
        continue;
      }
      if (!endAt.isAfter(startAt)) {
        skipped.add(skip(promotionId, code, "促销结束时刻不晚于开始时刻"));
        continue;
      }

      List<Record> products =
          sql.fetch(
              "select op.listing_id, l.title, l.external_item_id"
                  + " from ad_promotion_product op"
                  + " join listing l on l.tenant_id = op.tenant_id and l.id = op.listing_id"
                  + " where op.tenant_id = "
                  + TENANT
                  + " and op.promotion_id = ? order by op.listing_id",
              promotionId);
      if (products.isEmpty()) {
        skipped.add(skip(promotionId, code, "促销未配置适用商品"));
        continue;
      }

      for (Record product : products) {
        UUID listingId = product.get("listing_id", UUID.class);
        OffsetDateTime windowAsOf = analyzeListing(tenant, p, promotionId, listingId, startAt, endAt);
        if (windowAsOf != null && (dataAsOf == null || windowAsOf.isAfter(dataAsOf))) {
          dataAsOf = windowAsOf;
        }
      }
      analyzed++;
    }

    String status = skipped.isEmpty() ? "SUCCESS" : "PARTIAL";
    String skippedJson;
    try {
      skippedJson = json.writeValueAsString(skipped);
    } catch (Exception e) {
      skippedJson = "[]";
    }
    OffsetDateTime asOf = dataAsOf == null ? OffsetDateTime.now() : dataAsOf;
    UUID runId = Ids.next();
    sql.execute(
        "insert into ad_promotion_analysis_run"
            + " (tenant_id, id, requested_by, status, promotion_id, analyzed_count, skipped_count,"
            + "  skipped, data_as_of, metric_definition_note, note, created_at)"
            + " values (?,?,?,?,?,?,?,?::jsonb,?::timestamptz,?,?,now())",
        tenant,
        runId,
        actor,
        status,
        request.promotionId(),
        analyzed,
        skipped.size(),
        skippedJson,
        asOf,
        METRIC_DEFINITION_NOTE,
        text(request.note()));

    return new AdPromotionAnalysisRunRow(
        runId,
        status,
        request.promotionId(),
        analyzed,
        skipped.size(),
        skippedJson,
        asOf,
        METRIC_DEFINITION_NOTE,
        text(request.note()),
        OffsetDateTime.now());
  }

  private static Map<String, String> skip(UUID promotionId, String code, String reason) {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("promotionId", promotionId == null ? "" : promotionId.toString());
    m.put("promotionCode", code == null ? "" : code);
    m.put("reason", reason);
    return m;
  }

  /**
   * 算一个（促销 × 商品）的促销期与基期，并落库。
   *
   * @return 本次用到的平台快照最新接收时刻
   */
  private OffsetDateTime analyzeListing(
      UUID tenant,
      Record promotion,
      UUID promotionId,
      UUID listingId,
      OffsetDateTime startAt,
      OffsetDateTime endAt) {
    String promotionType = promotion.get("promotion_type", String.class);
    BigDecimal discountRate = promotion.get("discount_rate", BigDecimal.class);
    BigDecimal discountAmount = promotion.get("discount_amount", BigDecimal.class);
    String currency = text(promotion.get("currency", String.class));

    // 🔴 站点时区：只认 pg_timezone_names 里真实存在的时区，认不到退回 UTC。
    String tz =
        one(
            String.class,
            "select coalesce(t.name,'UTC') from listing l"
                + " left join marketplace m on m.id = l.marketplace_id"
                + " left join pg_timezone_names t on t.name = m.default_timezone"
                + " where l.tenant_id = "
                + TENANT
                + " and l.id = ?",
            listingId);
    if (tz == null || tz.isBlank()) tz = "UTC";

    // 🔴 自然日切分：促销起止时刻按站点当地自然日取。
    Record dates =
        sql.fetchOne(
            "select (cast(? as timestamptz) at time zone cast(? as text))::date as promo_start_date,"
                + " (cast(? as timestamptz) at time zone cast(? as text))::date as promo_end_date",
            startAt,
            tz,
            endAt,
            tz);
    LocalDate promoStart = dates.get("promo_start_date", LocalDate.class);
    LocalDate promoEnd = dates.get("promo_end_date", LocalDate.class);
    int periodDays = (int) ChronoUnit.DAYS.between(promoStart, promoEnd) + 1;
    if (periodDays <= 0) {
      // 当地自然日下结束不晚于开始（跨时区或数据脏）⇒ 按 1 天算，走「不可比」。
      periodDays = 1;
    }

    // 🔴 基期 = 促销首日之前等长且紧邻的一段。
    LocalDate baselineFrom = promoStart.minusDays(periodDays);
    LocalDate baselineTo = promoStart.minusDays(1);

    Window promo = aggregateWindow(listingId, promoStart, promoEnd, currency);
    Window baseline = aggregateWindow(listingId, baselineFrom, baselineTo, currency);

    // 🔴 成本快照（catalog 的成本被复制进来的唯一通道）。
    Record cost =
        sql.fetchOne(
            "select unit_cost, base_price from ad_promotion_cost_snapshot"
                + " where tenant_id = "
                + TENANT
                + " and promotion_id = ? and listing_id = ?",
            promotionId,
            listingId);
    BigDecimal unitCost = cost == null ? null : cost.get("unit_cost", BigDecimal.class);
    BigDecimal basePrice = cost == null ? null : cost.get("base_price", BigDecimal.class);

    boolean promoHasData = promo.coveredDays > 0;
    boolean baselineHasData = baseline.coveredDays > 0;

    BigDecimal promoUnits = promo.units;
    BigDecimal promoSales = promo.sales;
    BigDecimal baselineUnits = baseline.units;
    BigDecimal baselineSales = baseline.sales;

    // 🔴 没有平台快照 ⇒ 成本与毛利**不可比**（空值），不是 0。
    BigDecimal promoCost =
        unitCost != null && promoHasData
            ? unitCost.multiply(promoUnits).setScale(4, RoundingMode.HALF_UP)
            : null;
    BigDecimal promoGrossProfit =
        promoCost == null ? null : promoSales.subtract(promoCost).setScale(4, RoundingMode.HALF_UP);
    BigDecimal baselineCost =
        unitCost != null && baselineHasData
            ? unitCost.multiply(baselineUnits).setScale(4, RoundingMode.HALF_UP)
            : null;
    BigDecimal baselineGrossProfit =
        baselineCost == null ? null : baselineSales.subtract(baselineCost).setScale(4, RoundingMode.HALF_UP);

    BigDecimal incrementUnits = promoUnits.subtract(baselineUnits).setScale(6, RoundingMode.HALF_UP);
    BigDecimal incrementSales = promoSales.subtract(baselineSales).setScale(4, RoundingMode.HALF_UP);
    BigDecimal incrementGrossProfit =
        promoGrossProfit == null || baselineGrossProfit == null
            ? null
            : promoGrossProfit.subtract(baselineGrossProfit).setScale(4, RoundingMode.HALF_UP);

    BigDecimal subsidyUnit = subsidyUnit(promotionType, discountRate, discountAmount, basePrice);
    BigDecimal subsidyCost =
        subsidyUnit == null ? null : subsidyUnit.multiply(promoUnits).setScale(4, RoundingMode.HALF_UP);

    // 🔴 判定看**增量毛利**。
    String verdict;
    if (!promoHasData || !baselineHasData || incrementGrossProfit == null) {
      verdict = "INSUFFICIENT_DATA";
    } else if (incrementGrossProfit.signum() > 0) {
      verdict = "INCREMENTAL";
    } else if (incrementGrossProfit.signum() == 0) {
      verdict = "FLAT";
    } else {
      verdict = "DECREMENTAL";
    }

    BigDecimal promoMarginRate =
        promoSales.signum() > 0 && promoGrossProfit != null
            ? promoGrossProfit.divide(promoSales, 6, RoundingMode.HALF_UP)
            : null;
    BigDecimal baselineMarginRate =
        baselineSales.signum() > 0 && baselineGrossProfit != null
            ? baselineGrossProfit.divide(baselineSales, 6, RoundingMode.HALF_UP)
            : null;
    boolean marginAlert =
        (incrementSales.signum() > 0 || incrementUnits.signum() > 0)
            && promoMarginRate != null
            && baselineMarginRate != null
            && promoMarginRate.compareTo(baselineMarginRate) < 0;

    // 🔴 促销期 / 基期窗口按**站点当地自然日**对齐（起 = 首日当地 00:00，止 = 末日次日当地 00:00，开区间）。
    OffsetDateTime periodFrom = localDayStart(promoStart, tz);
    OffsetDateTime periodTo = localDayStart(promoEnd.plusDays(1), tz);
    OffsetDateTime baselineFromAt = localDayStart(baselineFrom, tz);
    OffsetDateTime baselineToAt = localDayStart(baselineTo.plusDays(1), tz);

    OffsetDateTime asOf = latest(promo.lastAt, baseline.lastAt);
    if (asOf == null) asOf = OffsetDateTime.now();

    // ---- 基期快照 ----
    UUID baselineId = Ids.next();
    sql.execute(
        "insert into ad_promotion_effect_baseline"
            + " (tenant_id, id, promotion_id, listing_id, currency, window_from_at, window_to_at,"
            + "  window_days, baseline_units, baseline_sales, baseline_cost, baseline_gross_profit,"
            + "  baseline_daily_units, baseline_daily_sales, baseline_covered_days, data_as_of,"
            + "  computed_at, created_at, updated_at)"
            + " values (?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?,?,?,?,?::timestamptz,"
            + "now(),now(),now())"
            + " on conflict (tenant_id, promotion_id, listing_id) do update set"
            + "  currency = excluded.currency, window_from_at = excluded.window_from_at,"
            + "  window_to_at = excluded.window_to_at, window_days = excluded.window_days,"
            + "  baseline_units = excluded.baseline_units, baseline_sales = excluded.baseline_sales,"
            + "  baseline_cost = excluded.baseline_cost,"
            + "  baseline_gross_profit = excluded.baseline_gross_profit,"
            + "  baseline_daily_units = excluded.baseline_daily_units,"
            + "  baseline_daily_sales = excluded.baseline_daily_sales,"
            + "  baseline_covered_days = excluded.baseline_covered_days,"
            + "  data_as_of = excluded.data_as_of, computed_at = now(), updated_at = now()",
        tenant,
        baselineId,
        promotionId,
        listingId,
        currency,
        baselineFromAt,
        baselineToAt,
        periodDays,
        baseline.units,
        baseline.sales,
        baselineCost,
        baselineGrossProfit,
        baseline.units.divide(BigDecimal.valueOf(periodDays), 6, RoundingMode.HALF_UP),
        baseline.sales.divide(BigDecimal.valueOf(periodDays), 4, RoundingMode.HALF_UP),
        baseline.coveredDays,
        asOf);

    UUID storedBaselineId =
        one(
            UUID.class,
            "select id from ad_promotion_effect_baseline where tenant_id = "
                + TENANT
                + " and promotion_id = ? and listing_id = ?",
            promotionId,
            listingId);
    if (storedBaselineId == null) storedBaselineId = baselineId;

    // ---- 效果结果 ----
    sql.execute(
        "insert into ad_promotion_effect"
            + " (tenant_id, id, promotion_id, listing_id, currency, period_from_at, period_to_at,"
            + "  period_days, period_covered_days, promo_units, promo_sales, promo_cost,"
            + "  promo_gross_profit, baseline_id, increment_units, increment_sales,"
            + "  increment_gross_profit, subsidy_unit, subsidy_cost, roi_numerator, roi_denominator,"
            + "  increment_verdict, margin_alert, data_as_of, computed_at, created_at, updated_at)"
            + " values (?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
            + "?::timestamptz,now(),now(),now())"
            + " on conflict (tenant_id, promotion_id, listing_id) do update set"
            + "  currency = excluded.currency, period_from_at = excluded.period_from_at,"
            + "  period_to_at = excluded.period_to_at, period_days = excluded.period_days,"
            + "  period_covered_days = excluded.period_covered_days,"
            + "  promo_units = excluded.promo_units, promo_sales = excluded.promo_sales,"
            + "  promo_cost = excluded.promo_cost, promo_gross_profit = excluded.promo_gross_profit,"
            + "  baseline_id = excluded.baseline_id, increment_units = excluded.increment_units,"
            + "  increment_sales = excluded.increment_sales,"
            + "  increment_gross_profit = excluded.increment_gross_profit,"
            + "  subsidy_unit = excluded.subsidy_unit, subsidy_cost = excluded.subsidy_cost,"
            + "  roi_numerator = excluded.roi_numerator, roi_denominator = excluded.roi_denominator,"
            + "  increment_verdict = excluded.increment_verdict,"
            + "  margin_alert = excluded.margin_alert, data_as_of = excluded.data_as_of,"
            + "  computed_at = now(), updated_at = now()",
        tenant,
        Ids.next(),
        promotionId,
        listingId,
        currency,
        periodFrom,
        periodTo,
        periodDays,
        promo.coveredDays,
        promoUnits,
        promoSales,
        promoCost,
        promoGrossProfit,
        storedBaselineId,
        incrementUnits,
        incrementSales,
        incrementGrossProfit,
        subsidyUnit,
        subsidyCost,
        incrementGrossProfit,
        subsidyCost,
        verdict,
        marginAlert,
        asOf);

    return asOf;
  }

  /**
   * 站点当地某一天 00:00 对应的时刻（自然日窗口的对齐点）。
   *
   * <p>🔴 用 {@code timezone(tz, date::timestamp)} 而不是 {@code date AT TIME ZONE tz}：
   * 后者在 PG 里返回的是 <b>timestamp without time zone</b>（当地午夜对应的 UTC 墙钟），
   * 再 {@code ::timestamptz} 会被**会话时区**二次解释 ⇒ 窗口整体偏移。
   * {@code timezone()} 直接返回 timestamptz，没有这一步歧义。
   */
  private OffsetDateTime localDayStart(LocalDate day, String tz) {
    return one(
        OffsetDateTime.class,
        "select timezone(cast(? as text), cast(? as date)::timestamp)",
        tz,
        day.toString());
  }

  /** 一个窗口的汇总（平台回报口径；同一天多来源取最近收到的一条）。 */
  private record Window(
      BigDecimal units, BigDecimal sales, int coveredDays, OffsetDateTime lastAt) {}

  /**
   * 汇总一个自然日窗口。
   *
   * <p>🔴 <b>同一天不相加</b>：{@code listing_performance_snapshot} 同一天可能有 PLATFORM 与 API
   * 两条来源，取 {@code received_at desc, id desc} 的最近一条。
   */
  private Window aggregateWindow(
      UUID listingId, LocalDate from, LocalDate to, String promotionCurrency) {
    Record r =
        sql.fetchOne(
            "with daily as ("
                + "  select s.stat_date, s.units_ordered, s.sales_amount, s.currency, s.received_at,"
                + "         row_number() over (partition by s.stat_date"
                + "           order by s.received_at desc, s.id desc) as rn"
                + "  from listing_performance_snapshot s"
                + "  where s.tenant_id = "
                + TENANT
                + "    and s.listing_id = ?"
                + "    and s.stat_date between cast(? as date) and cast(? as date)"
                + "    and (cast(? as text) = '' or s.currency = cast(? as text))"
                + ")"
                + "select coalesce(sum(units_ordered),0) as units,"
                + "       coalesce(sum(sales_amount),0) as sales,"
                + "       count(*) as days,"
                + "       max(received_at) as last_at"
                + " from daily where rn = 1",
            listingId,
            from.toString(),
            to.toString(),
            promotionCurrency,
            promotionCurrency);
    if (r == null) {
      return new Window(BigDecimal.ZERO.setScale(6), BigDecimal.ZERO.setScale(4), 0, null);
    }
    Long days = r.get("days", Long.class);
    return new Window(
        r.get("units", BigDecimal.class).setScale(6, RoundingMode.HALF_UP),
        r.get("sales", BigDecimal.class).setScale(4, RoundingMode.HALF_UP),
        days == null ? 0 : days.intValue(),
        r.get("last_at", OffsetDateTime.class));
  }

  /**
   * 单件让利（照抄 14.4.1.1 的折后价口径）。
   *
   * <p>🔴 没有可折算价格系数的促销类型（BOGO / BUNDLE / FREE_SHIPPING）⇒ 返回 null
   * （不可比），**不是 0** —— 0 会被读成「这次一分钱没让」。
   */
  static BigDecimal subsidyUnit(
      String promotionType, BigDecimal discountRate, BigDecimal discountAmount, BigDecimal basePrice) {
    if (!PRICE_BEARING_TYPES.contains(promotionType)) return null;
    if ("PERCENT_OFF".equals(promotionType)) {
      if (discountRate == null || basePrice == null) return null;
      return basePrice.multiply(discountRate).setScale(4, RoundingMode.HALF_UP);
    }
    if ("FIXED_AMOUNT_OFF".equals(promotionType)) {
      if (discountAmount == null) return null;
      return discountAmount.setScale(4, RoundingMode.HALF_UP);
    }
    return null;
  }

  private static OffsetDateTime latest(OffsetDateTime a, OffsetDateTime b) {
    if (a == null) return b;
    if (b == null) return a;
    return a.isAfter(b) ? a : b;
  }

  // ==========================================================================
  // 效果报表
  // ==========================================================================

  /** 效果报表：真聚合 / 真分页 / 真筛选 / 真排序（默认按毛利排序）。 */
  public AdPromotionAnalysisPage report(AdPromotionAnalysisQuery query) {
    AdPromotionAnalysisQuery q = normalize(query);
    List<Object> binds = new ArrayList<>();
    String where = baseWhere(q, binds);
    boolean aggregate = "PROMOTION".equals(q.dimension());

    String sqlText =
        cteSql(where, aggregate)
            + " select f.promotion_id, f.promotion_code, f.promotion_name, f.promotion_type,"
            + "        f.platform_code, f.shop_code, f.listing_id, f.listing_title, f.external_item_id,"
            + "        f.currency, f.period_from_at, f.period_to_at, f.period_days, f.period_covered_days,"
            + "        f.promo_units, f.promo_sales, f.promo_cost, f.promo_gross_profit,"
            + "        f.promo_gross_margin_rate,"
            + "        f.baseline_from_at, f.baseline_to_at, f.baseline_days, f.baseline_covered_days,"
            + "        f.baseline_units, f.baseline_sales, f.baseline_cost, f.baseline_gross_profit,"
            + "        f.baseline_gross_margin_rate, f.increment_units, f.increment_sales,"
            + "        f.increment_gross_profit, f.subsidy_cost, f.roi_numerator, f.roi_denominator,"
            + subsidyUnitSelect(aggregate)
            + "        (case when f.roi_denominator is not null and f.roi_denominator > 0"
            + " and f.roi_numerator is not null"
            + " then round(f.roi_numerator/f.roi_denominator,6) else null end) as roi_value,"
            + "        f.computed_verdict as increment_verdict, f.computed_margin_alert as margin_alert,"
            + "        f.product_count, f.gross_profit_product_count, f.comparable_product_count,"
            + "        f.data_as_of, count(*) over () as total_count"
            + " from filtered f where 1 = 1"
            + verdictFilter(q, binds)
            + marginAlertFilter(q)
            + " "
            + SORTS.getOrDefault(q.sort(), SORTS.get(DEFAULT_SORT))
            + " limit ? offset ?";
    binds.add(q.size());
    binds.add(q.page() * q.size());

    List<Record> rows = sql.fetch(sqlText, binds.toArray());
    long total = 0;
    List<AdPromotionAnalysisRow> items = new ArrayList<>();
    for (Record r : rows) {
      Long c = r.get("total_count", Long.class);
      if (c != null) total = c;
      items.add(toRow(r));
    }
    if (rows.isEmpty()) {
      total = filteredCount(where, aggregate, q);
    }
    return new AdPromotionAnalysisPage(items, total, q.page(), q.size(), METRIC_DEFINITION_NOTE);
  }

  /** 活动维度的单件让利 = 总补贴 ÷ 总销量（加权平均）；单件直接加总没有意义。 */
  private static String subsidyUnitSelect(boolean aggregate) {
    return aggregate
        ? "        (case when f.subsidy_cost is not null and f.promo_units > 0"
            + " then round(f.subsidy_cost/f.promo_units,4) else null end) as subsidy_unit,"
        : "        f.subsidy_unit,";
  }

  /** 报表的 CTE 前缀（base → rolled → filtered）。 */
  private String cteSql(String where, boolean aggregate) {
    StringBuilder sb = new StringBuilder();
    sb.append("with base as (");
    sb.append(
        " select e.id as effect_id, e.promotion_id, p.promotion_code, p.promotion_name, p.promotion_type,");
    sb.append("        p.platform_id, pl.code as platform_code, p.shop_code,");
    sb.append("        e.listing_id, l.title as listing_title, l.external_item_id, e.currency,");
    sb.append("        e.period_from_at, e.period_to_at, e.period_days, e.period_covered_days,");
    sb.append(
        "        e.promo_units, e.promo_sales, e.promo_cost, e.promo_gross_profit,"
            + " e.promo_gross_margin_rate,");
    sb.append("        b.window_from_at as baseline_from_at, b.window_to_at as baseline_to_at,");
    sb.append(
        "        b.window_days as baseline_days, b.baseline_covered_days,"
            + " b.baseline_units, b.baseline_sales, b.baseline_cost,"
            + " b.baseline_gross_profit, b.baseline_gross_margin_rate,");
    sb.append("        e.increment_units, e.increment_sales, e.increment_gross_profit,");
    sb.append(
        "        e.subsidy_unit, e.subsidy_cost, e.roi_numerator, e.roi_denominator, e.roi_value,");
    sb.append("        e.margin_alert, e.data_as_of");
    sb.append(" from ad_promotion_effect e");
    sb.append(" join ad_promotion p on p.tenant_id = e.tenant_id and p.id = e.promotion_id");
    sb.append(" join platform pl on pl.id = p.platform_id");
    sb.append(" join listing l on l.tenant_id = e.tenant_id and l.id = e.listing_id");
    sb.append(
        " join ad_promotion_effect_baseline b on b.tenant_id = e.tenant_id and b.id = e.baseline_id");
    sb.append(" where ").append(where);
    sb.append("), rolled as (");
    if (aggregate) {
      sb.append(
          " select base.promotion_id, base.promotion_code, base.promotion_name, base.promotion_type,");
      sb.append("        base.platform_id, base.platform_code, base.shop_code, base.currency,");
      sb.append(
          "        null::uuid as listing_id, null::text as listing_title, null::text as external_item_id,");
      sb.append(
          "        min(base.period_from_at) as period_from_at, max(base.period_to_at) as period_to_at,");
      sb.append(
          "        max(base.period_days) as period_days,"
              + " sum(base.period_covered_days)::int as period_covered_days,");
      sb.append("        sum(base.promo_units) as promo_units, sum(base.promo_sales) as promo_sales,");
      sb.append(
          "        case when count(base.promo_cost) = count(*) then sum(base.promo_cost) else null end"
              + " as promo_cost,");
      sb.append(
          "        case when count(base.promo_gross_profit) = count(*)"
              + " then sum(base.promo_gross_profit) else null end as promo_gross_profit,");
      sb.append(
          "        case when sum(base.promo_sales) > 0 and count(base.promo_gross_profit) = count(*)"
              + " then round(sum(base.promo_gross_profit)/sum(base.promo_sales),6) else null end"
              + " as promo_gross_margin_rate,");
      sb.append(
          "        min(base.baseline_from_at) as baseline_from_at,"
              + " max(base.baseline_to_at) as baseline_to_at,");
      sb.append(
          "        max(base.baseline_days) as baseline_days,"
              + " sum(base.baseline_covered_days)::int as baseline_covered_days,");
      sb.append(
          "        sum(base.baseline_units) as baseline_units, sum(base.baseline_sales) as baseline_sales,");
      sb.append(
          "        case when count(base.baseline_cost) = count(*) then sum(base.baseline_cost) else null end"
              + " as baseline_cost,");
      sb.append(
          "        case when count(base.baseline_gross_profit) = count(*)"
              + " then sum(base.baseline_gross_profit) else null end as baseline_gross_profit,");
      sb.append(
          "        case when sum(base.baseline_sales) > 0"
              + " and count(base.baseline_gross_profit) = count(*)"
              + " then round(sum(base.baseline_gross_profit)/sum(base.baseline_sales),6) else null end"
              + " as baseline_gross_margin_rate,");
      sb.append(
          "        sum(base.increment_units) as increment_units,"
              + " sum(base.increment_sales) as increment_sales,");
      sb.append(
          "        case when count(base.increment_gross_profit) = count(*)"
              + " then sum(base.increment_gross_profit) else null end as increment_gross_profit,");
      sb.append(
          "        case when count(base.subsidy_cost) = count(*) then sum(base.subsidy_cost) else null end"
              + " as subsidy_cost,");
      sb.append(
          "        case when count(base.roi_numerator) = count(*) then sum(base.roi_numerator) else null end"
              + " as roi_numerator,");
      sb.append(
          "        case when count(base.roi_denominator) = count(*)"
              + " then sum(base.roi_denominator) else null end as roi_denominator,");
      sb.append("        bool_or(base.margin_alert) as margin_alert, max(base.data_as_of) as data_as_of,");
      sb.append("        count(*)::int as product_count,");
      sb.append("        count(base.promo_gross_profit)::int as gross_profit_product_count,");
      sb.append("        count(base.subsidy_cost)::int as comparable_product_count");
      sb.append(" from base");
      sb.append(
          " group by base.promotion_id, base.promotion_code, base.promotion_name, base.promotion_type,");
      sb.append("          base.platform_id, base.platform_code, base.shop_code, base.currency");
    } else {
      sb.append(" select base.*, 1::int as product_count,");
      sb.append(
          "        (case when base.promo_gross_profit is not null then 1 else 0 end)::int"
              + " as gross_profit_product_count,");
      sb.append(
          "        (case when base.subsidy_cost is not null then 1 else 0 end)::int"
              + " as comparable_product_count");
      sb.append(" from base");
    }
    sb.append("), filtered as (");
    sb.append(" select r.*,");
    sb.append(
        "   (case when r.promo_gross_profit is null or r.increment_gross_profit is null"
            + "      or r.period_covered_days = 0 or r.baseline_covered_days = 0"
            + "         then 'INSUFFICIENT_DATA'"
            + "      when r.increment_gross_profit > 0 then 'INCREMENTAL'"
            + "      when r.increment_gross_profit = 0 then 'FLAT'"
            + "      else 'DECREMENTAL' end) as computed_verdict,");
    sb.append(
        "   (case when (r.increment_sales > 0 or r.increment_units > 0)"
            + "      and r.promo_gross_margin_rate is not null"
            + "      and r.baseline_gross_margin_rate is not null"
            + "      and r.promo_gross_margin_rate < r.baseline_gross_margin_rate"
            + "         then true else false end) as computed_margin_alert");
    sb.append(" from rolled r)");
    return sb.toString();
  }

  /** 筛选后的总条数（页为空时的回退计数）。 */
  private long filteredCount(String where, boolean aggregate, AdPromotionAnalysisQuery q) {
    List<Object> binds = new ArrayList<>();
    baseWhere(q, binds);
    String sqlText = cteSql(where, aggregate) + " select count(*) from filtered f where 1 = 1"
        + verdictFilter(q, binds)
        + marginAlertFilter(q);
    return countOf(sqlText, binds.toArray());
  }

  /** 筛选后的数据截至（水位 = 用到的平台快照里最新的一条接收时刻）。 */
  private OffsetDateTime dataAsOf(AdPromotionAnalysisQuery q) {
    AdPromotionAnalysisQuery n = normalize(q);
    List<Object> binds = new ArrayList<>();
    String where = baseWhere(n, binds);
    boolean aggregate = "PROMOTION".equals(n.dimension());
    String sqlText = cteSql(where, aggregate) + " select max(f.data_as_of) from filtered f where 1 = 1"
        + verdictFilter(n, binds)
        + marginAlertFilter(n);
    return one(OffsetDateTime.class, sqlText, binds.toArray());
  }

  /** base 层的筛选条件（真筛选：全部下推到 SQL）。 */
  private String baseWhere(AdPromotionAnalysisQuery q, List<Object> binds) {
    StringBuilder w = new StringBuilder();
    w.append(" e.tenant_id = ").append(TENANT);
    if (q.promotionId() != null) {
      w.append(" and e.promotion_id = ?");
      binds.add(q.promotionId());
    }
    if (q.platformId() != null) {
      w.append(" and p.platform_id = ?");
      binds.add(q.platformId());
    }
    if (q.shopCode() != null) {
      w.append(" and p.shop_code = ?");
      binds.add(q.shopCode());
    }
    if (q.currency() != null) {
      w.append(" and e.currency = ?");
      binds.add(q.currency());
    }
    // 🔴 日期筛选按促销期与区间在**时刻轴**上是否重叠判定
    //   （促销期起止是按站点当地自然日对齐的绝对时刻）。
    if (q.dateFrom() != null) {
      w.append(" and e.period_to_at >= (cast(? as date)::timestamp at time zone 'UTC')");
      binds.add(q.dateFrom().toString());
    }
    if (q.dateTo() != null) {
      w.append(
          " and e.period_from_at <="
              + " ((cast(? as date)::timestamp at time zone 'UTC') + interval '1 day')");
      binds.add(q.dateTo().toString());
    }
    if (q.keyword() != null) {
      w.append(
          " and (p.promotion_code ilike ? or p.promotion_name ilike ?"
              + " or l.title ilike ? or l.external_item_id ilike ?)");
      String kw = "%" + q.keyword() + "%";
      binds.add(kw);
      binds.add(kw);
      binds.add(kw);
      binds.add(kw);
    }
    return w.toString();
  }

  private String verdictFilter(AdPromotionAnalysisQuery q, List<Object> binds) {
    if (q.incrementVerdict() == null) return "";
    if (!INCREMENT_VERDICTS.contains(q.incrementVerdict())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "增量判定取值不合法");
    }
    binds.add(q.incrementVerdict());
    return " and f.computed_verdict = ?";
  }

  private String marginAlertFilter(AdPromotionAnalysisQuery q) {
    if (q.marginAlertOnly() == null) return "";
    return q.marginAlertOnly()
        ? " and f.computed_margin_alert = true"
        : " and f.computed_margin_alert = false";
  }

  private AdPromotionAnalysisRow toRow(Record r) {
    return new AdPromotionAnalysisRow(
        r.get("promotion_id", UUID.class),
        r.get("promotion_code", String.class),
        r.get("promotion_name", String.class),
        r.get("promotion_type", String.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("listing_id", UUID.class),
        r.get("listing_title", String.class),
        r.get("external_item_id", String.class),
        r.get("currency", String.class),
        r.get("period_from_at", OffsetDateTime.class),
        r.get("period_to_at", OffsetDateTime.class),
        r.get("period_days", Integer.class),
        r.get("period_covered_days", Integer.class),
        r.get("promo_units", BigDecimal.class),
        r.get("promo_sales", BigDecimal.class),
        r.get("promo_cost", BigDecimal.class),
        r.get("promo_gross_profit", BigDecimal.class),
        r.get("promo_gross_margin_rate", BigDecimal.class),
        r.get("baseline_from_at", OffsetDateTime.class),
        r.get("baseline_to_at", OffsetDateTime.class),
        r.get("baseline_days", Integer.class),
        r.get("baseline_covered_days", Integer.class),
        r.get("baseline_units", BigDecimal.class),
        r.get("baseline_sales", BigDecimal.class),
        r.get("baseline_cost", BigDecimal.class),
        r.get("baseline_gross_profit", BigDecimal.class),
        r.get("baseline_gross_margin_rate", BigDecimal.class),
        r.get("increment_units", BigDecimal.class),
        r.get("increment_sales", BigDecimal.class),
        r.get("increment_gross_profit", BigDecimal.class),
        r.get("subsidy_unit", BigDecimal.class),
        r.get("subsidy_cost", BigDecimal.class),
        r.get("roi_numerator", BigDecimal.class),
        r.get("roi_denominator", BigDecimal.class),
        r.get("roi_value", BigDecimal.class),
        r.get("increment_verdict", String.class),
        Boolean.TRUE.equals(r.get("margin_alert", Boolean.class)),
        r.get("product_count", Integer.class),
        r.get("gross_profit_product_count", Integer.class),
        r.get("comparable_product_count", Integer.class),
        r.get("data_as_of", OffsetDateTime.class),
        METRIC_DEFINITION_NOTE);
  }

  /** 规范化查询参数（非法值直接拒，不静默兜底）。 */
  public AdPromotionAnalysisQuery normalize(AdPromotionAnalysisQuery q) {
    String dimension =
        q.dimension() == null || q.dimension().isBlank() ? "PROMOTION" : q.dimension().trim();
    if (!DIMENSIONS.contains(dimension)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "报表维度不合法");
    }
    String sort = q.sort() == null || q.sort().isBlank() ? DEFAULT_SORT : q.sort().trim();
    if (!SORTS.containsKey(sort)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "排序取值不合法");
    }
    int page = Math.max(0, q.page());
    int size = q.size() <= 0 ? 50 : Math.min(q.size(), MAX_SIZE);
    return new AdPromotionAnalysisQuery(
        dimension,
        sort,
        q.promotionId(),
        q.platformId(),
        blankToNull(q.shopCode()),
        blankToNull(q.currency()),
        blankToNull(q.incrementVerdict()),
        q.marginAlertOnly(),
        blankToNull(q.keyword()),
        q.dateFrom(),
        q.dateTo(),
        page,
        size);
  }

  // ==========================================================================
  // 成本与售价快照（跨服务纪律的物理落点）
  // ==========================================================================

  /** 成本快照列表：真分页 + 真筛选。 */
  public AdPromotionAnalysisCostSnapshotPage costSnapshots(
      UUID promotionId, UUID listingId, int page, int size) {
    List<Object> binds = new ArrayList<>();
    String where = " c.tenant_id = " + TENANT;
    if (promotionId != null) {
      where += " and c.promotion_id = ?";
      binds.add(promotionId);
    }
    if (listingId != null) {
      where += " and c.listing_id = ?";
      binds.add(listingId);
    }
    long total = countOf("select count(*) from ad_promotion_cost_snapshot c where " + where,
        binds.toArray());
    int p = Math.max(0, page);
    int s = size <= 0 ? 50 : Math.min(size, MAX_SIZE);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<AdPromotionAnalysisCostSnapshotRow> items =
        sql.fetch(
                "select c.id, c.promotion_id, p.promotion_code, p.promotion_name, c.listing_id,"
                    + " l.title, l.external_item_id, c.currency, c.unit_cost, c.base_price,"
                    + " c.source_ref, c.captured_at, c.note, c.version, c.updated_at"
                    + " from ad_promotion_cost_snapshot c"
                    + " join ad_promotion p on p.tenant_id = c.tenant_id and p.id = c.promotion_id"
                    + " join listing l on l.tenant_id = c.tenant_id and l.id = c.listing_id"
                    + " where "
                    + where
                    + " order by p.promotion_code, l.title, c.id limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(
                r ->
                    new AdPromotionAnalysisCostSnapshotRow(
                        r.get("id", UUID.class),
                        r.get("promotion_id", UUID.class),
                        r.get("promotion_code", String.class),
                        r.get("promotion_name", String.class),
                        r.get("listing_id", UUID.class),
                        r.get("title", String.class),
                        r.get("external_item_id", String.class),
                        r.get("currency", String.class),
                        r.get("unit_cost", BigDecimal.class),
                        r.get("base_price", BigDecimal.class),
                        r.get("source_ref", String.class),
                        r.get("captured_at", OffsetDateTime.class),
                        r.get("note", String.class),
                        r.get("version", Long.class),
                        r.get("updated_at", OffsetDateTime.class)))
            .toList();
    return new AdPromotionAnalysisCostSnapshotPage(items, total, p, s);
  }

  /**
   * 登记成本与售价快照。
   *
   * <p>🔴 这是**成本进入本服务的唯一通道**：catalog 的成本被复制进来，channel 永不 join catalog。
   * 重登记 = 覆盖（version + 1），历史留在 version / updated_at 里。
   */
  public AdPromotionAnalysisCostSnapshotRow saveCostSnapshot(SaveCostSnapshotRequest request) {
    UUID tenant = currentTenant();

    String currency = blankToNull(request.currency());
    if (currency == null) {
      currency =
          one(
              String.class,
              "select currency from ad_promotion where tenant_id = " + TENANT + " and id = ?",
              request.promotionId());
    }
    if (currency == null || currency.isBlank()) currency = "";

    // 🔴 商品必须真的在这个促销的适用范围内，否则快照与促销无关。
    long attached =
        countOf(
            "select count(*) from ad_promotion_product where tenant_id = "
                + TENANT
                + " and promotion_id = ? and listing_id = ?",
            request.promotionId(),
            request.listingId());
    if (attached == 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "该商品不在此促销的适用范围内，成本快照无处安放");
    }

    OffsetDateTime capturedAt =
        request.capturedAt() == null ? OffsetDateTime.now() : request.capturedAt();

    sql.execute(
        "insert into ad_promotion_cost_snapshot"
            + " (tenant_id, id, promotion_id, listing_id, currency, unit_cost, base_price,"
            + "  source_ref, captured_at, note, version, created_at, updated_at)"
            + " values (?,?,?,?,?,?,?,?,?::timestamptz,?,0,now(),now())"
            + " on conflict (tenant_id, promotion_id, listing_id) do update set"
            + "  currency = excluded.currency, unit_cost = excluded.unit_cost,"
            + "  base_price = excluded.base_price, source_ref = excluded.source_ref,"
            + "  captured_at = excluded.captured_at, note = excluded.note,"
            + "  version = ad_promotion_cost_snapshot.version + 1, updated_at = now()",
        tenant,
        Ids.next(),
        request.promotionId(),
        request.listingId(),
        currency,
        request.unitCost(),
        request.basePrice(),
        text(request.sourceRef()),
        capturedAt,
        text(request.note()));

    Record row =
        sql.fetchOne(
            "select c.id, c.promotion_id, p.promotion_code, p.promotion_name, c.listing_id,"
                + " l.title, l.external_item_id, c.currency, c.unit_cost, c.base_price,"
                + " c.source_ref, c.captured_at, c.note, c.version, c.updated_at"
                + " from ad_promotion_cost_snapshot c"
                + " join ad_promotion p on p.tenant_id = c.tenant_id and p.id = c.promotion_id"
                + " join listing l on l.tenant_id = c.tenant_id and l.id = c.listing_id"
                + " where c.tenant_id = "
                + TENANT
                + " and c.promotion_id = ? and c.listing_id = ?",
            request.promotionId(),
            request.listingId());
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "成本快照写入后读不回来");
    }
    return new AdPromotionAnalysisCostSnapshotRow(
        row.get("id", UUID.class),
        row.get("promotion_id", UUID.class),
        row.get("promotion_code", String.class),
        row.get("promotion_name", String.class),
        row.get("listing_id", UUID.class),
        row.get("title", String.class),
        row.get("external_item_id", String.class),
        row.get("currency", String.class),
        row.get("unit_cost", BigDecimal.class),
        row.get("base_price", BigDecimal.class),
        row.get("source_ref", String.class),
        row.get("captured_at", OffsetDateTime.class),
        row.get("note", String.class),
        row.get("version", Long.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  // ==========================================================================
  // 导出留痕
  // ==========================================================================

  /** 登记一次导出（留痕：谁、什么时候、什么筛选条件、导出了多少行）。 */
  public AdPromotionAnalysisExportRow recordExport(AdPromotionAnalysisQuery query) {
    AdPromotionAnalysisQuery q = normalize(query);
    long rows = filteredCount(baseWhere(q, new ArrayList<>()), "PROMOTION".equals(q.dimension()), q);
    UUID id = Ids.next();
    OffsetDateTime now = OffsetDateTime.now();
    OffsetDateTime asOf = dataAsOf(q);
    sql.execute(
        "insert into ad_promotion_effect_export"
            + " (tenant_id, id, requested_by, dimension, promotion_id, platform_id, shop_code,"
            + "  currency, increment_verdict, date_from, date_to, row_count, metric_definition_note,"
            + "  data_as_of, created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?::date,?::date,?,?,?::timestamptz,now())",
        currentTenant(),
        id,
        currentActor(),
        q.dimension(),
        q.promotionId(),
        q.platformId(),
        q.shopCode(),
        q.currency(),
        q.incrementVerdict(),
        q.dateFrom() == null ? null : q.dateFrom().toString(),
        q.dateTo() == null ? null : q.dateTo().toString(),
        (int) Math.min(rows, Integer.MAX_VALUE),
        METRIC_DEFINITION_NOTE,
        asOf);
    return new AdPromotionAnalysisExportRow(
        id,
        currentActor(),
        q.dimension(),
        q.promotionId(),
        q.platformId(),
        q.shopCode(),
        q.currency(),
        q.incrementVerdict(),
        q.dateFrom(),
        q.dateTo(),
        (int) Math.min(rows, Integer.MAX_VALUE),
        asOf,
        METRIC_DEFINITION_NOTE,
        now);
  }

  /** 导出留痕历史。 */
  public AdPromotionAnalysisExportPage exports(int page, int size) {
    int p = Math.max(0, page);
    int s = size <= 0 ? 50 : Math.min(size, MAX_SIZE);
    long total =
        countOf("select count(*) from ad_promotion_effect_export where tenant_id = " + TENANT);
    List<AdPromotionAnalysisExportRow> items =
        sql.fetch(
                "select id, requested_by, dimension, promotion_id, platform_id, shop_code, currency,"
                    + " increment_verdict, date_from, date_to, row_count, data_as_of,"
                    + " metric_definition_note, created_at"
                    + " from ad_promotion_effect_export where tenant_id = "
                    + TENANT
                    + " order by created_at desc, id limit ? offset ?",
                s,
                p * s)
            .stream()
            .map(this::toExportRow)
            .toList();
    return new AdPromotionAnalysisExportPage(items, total, p, s);
  }

  /** 分析运行历史（跑了什么、算了几条、跳过几条、为什么跳过）。 */
  public List<AdPromotionAnalysisRunRow> runs(int page, int size) {
    int p = Math.max(0, page);
    int s = size <= 0 ? 50 : Math.min(size, MAX_SIZE);
    return sql.fetch(
            "select id, status, promotion_id, analyzed_count, skipped_count, skipped, data_as_of,"
                + " metric_definition_note, note, created_at"
                + " from ad_promotion_analysis_run where tenant_id = "
                + TENANT
                + " order by created_at desc, id limit ? offset ?",
            s,
            p * s)
        .stream()
        .map(
            r ->
                new AdPromotionAnalysisRunRow(
                    r.get("id", UUID.class),
                    r.get("status", String.class),
                    r.get("promotion_id", UUID.class),
                    r.get("analyzed_count", Integer.class),
                    r.get("skipped_count", Integer.class),
                    r.get("skipped", String.class),
                    r.get("data_as_of", OffsetDateTime.class),
                    r.get("metric_definition_note", String.class),
                    r.get("note", String.class),
                    r.get("created_at", OffsetDateTime.class)))
        .toList();
  }

  /** 单条导出留痕详情。 */
  public AdPromotionAnalysisExportRow exportById(UUID exportId) {
    Record r =
        sql.fetchOne(
            "select id, requested_by, dimension, promotion_id, platform_id, shop_code, currency,"
                + " increment_verdict, date_from, date_to, row_count, data_as_of,"
                + " metric_definition_note, created_at"
                + " from ad_promotion_effect_export where tenant_id = "
                + TENANT
                + " and id = ?",
            exportId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "导出留痕不存在");
    }
    return toExportRow(r);
  }

  /**
   * 从导出留痕**重建**查询条件（用于按 id 重新导出 CSV）。
   *
   * <p>🔴 重建的是**筛选条件**，不是报表数据快照 —— 与 14.3.1.1 同一口径：
   * 留一份数据快照会在下一次同步后与「当时的报表」对不上，那反而是假事实。
   */
  public AdPromotionAnalysisQuery queryOf(AdPromotionAnalysisExportRow row) {
    return normalize(
        new AdPromotionAnalysisQuery(
            row.dimension(),
            DEFAULT_SORT,
            row.promotionId(),
            row.platformId(),
            row.shopCode(),
            row.currency(),
            row.incrementVerdict(),
            null,
            null,
            row.dateFrom(),
            row.dateTo(),
            0,
            MAX_SIZE));
  }

  private AdPromotionAnalysisExportRow toExportRow(Record r) {    return new AdPromotionAnalysisExportRow(
        r.get("id", UUID.class),
        r.get("requested_by", UUID.class),
        r.get("dimension", String.class),
        r.get("promotion_id", UUID.class),
        r.get("platform_id", UUID.class),
        r.get("shop_code", String.class),
        r.get("currency", String.class),
        r.get("increment_verdict", String.class),
        r.get("date_from", LocalDate.class),
        r.get("date_to", LocalDate.class),
        r.get("row_count", Integer.class),
        r.get("data_as_of", OffsetDateTime.class),
        r.get("metric_definition_note", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /**
   * 流式导出 CSV。
   *
   * <p>🔴 <b>每批在流式线程里重新开租户事务</b>：租户上下文是事务级 GUC，
   * controller 那个事务在响应开始前就结束了 —— 那条连接上没有
   * {@code app.current_tenant}，FORCE RLS 会把所有行藏起来。
   *
   * <p>🔴 <b>口径写进表头</b>：列名里带着公式，拿去做跨部门沟通时 CSV 自己就说明了口径。
   * 不可比的比率写「不可比」（不是 0，也不是空）。
   */
  public void streamCsv(
      AccessService.Access actor, AdPromotionAnalysisQuery query, Consumer<String> emit) {
    AdPromotionAnalysisQuery q = normalize(query);
    int offset = 0;

    emit.accept(csvHeader());

    while (true) {
      final int at = offset;
      List<AdPromotionAnalysisRow> batch =
          access.run(
              actor,
              "channel.operation.read",
              () ->
                  report(
                          new AdPromotionAnalysisQuery(
                              q.dimension(),
                              q.sort(),
                              q.promotionId(),
                              q.platformId(),
                              q.shopCode(),
                              q.currency(),
                              q.incrementVerdict(),
                              q.marginAlertOnly(),
                              q.keyword(),
                              q.dateFrom(),
                              q.dateTo(),
                              at / EXPORT_BATCH,
                              EXPORT_BATCH))
                      .items());
      if (batch.isEmpty()) break;

      StringBuilder sb = new StringBuilder();
      for (AdPromotionAnalysisRow row : batch) {
        List<String> cells = new ArrayList<>();
        cells.add(nullTo(row.promotionCode()));
        cells.add(nullTo(row.promotionName()));
        cells.add(nullTo(row.promotionType()));
        cells.add(nullTo(row.platformCode()));
        cells.add(nullTo(row.shopCode()));
        cells.add(nullTo(row.currency()));
        cells.add(nullTo(row.listingTitle()));
        cells.add(nullTo(row.externalItemId()));
        cells.add(nullTo(row.periodFromAt()));
        cells.add(nullTo(row.periodToAt()));
        cells.add(String.valueOf(row.periodDays()));
        cells.add(String.valueOf(row.periodCoveredDays()));
        cells.add(plain(row.promoUnits()));
        cells.add(plain(row.promoSales()));
        cells.add(plain(row.promoCost()));
        cells.add(plain(row.promoGrossProfit()));
        cells.add(plain(row.promoGrossMarginRate()));
        cells.add(nullTo(row.baselineFromAt()));
        cells.add(nullTo(row.baselineToAt()));
        cells.add(String.valueOf(row.baselineDays()));
        cells.add(String.valueOf(row.baselineCoveredDays()));
        cells.add(plain(row.baselineUnits()));
        cells.add(plain(row.baselineSales()));
        cells.add(plain(row.baselineCost()));
        cells.add(plain(row.baselineGrossProfit()));
        cells.add(plain(row.baselineGrossMarginRate()));
        cells.add(plain(row.incrementUnits()));
        cells.add(plain(row.incrementSales()));
        cells.add(plain(row.incrementGrossProfit()));
        cells.add(plain(row.subsidyUnit()));
        cells.add(plain(row.subsidyCost()));
        cells.add(plain(row.roiNumerator()));
        cells.add(plain(row.roiDenominator()));
        cells.add(plain(row.roiValue()));
        cells.add(nullTo(row.incrementVerdict()));
        cells.add(row.marginAlert() ? "是" : "否");
        cells.add(String.valueOf(row.productCount()));
        cells.add(String.valueOf(row.grossProfitProductCount()));
        cells.add(String.valueOf(row.comparableProductCount()));
        cells.add(nullTo(row.dataAsOf()));
        sb.append(String.join(",", cells)).append('\n');
      }
      emit.accept(sb.toString());
      offset += EXPORT_BATCH;
      if (batch.size() < EXPORT_BATCH) break;
    }
  }

  /** CSV 表头：口径写进列名，跨部门沟通时 CSV 自己说得清。 */
  private static String csvHeader() {
    List<String> cols =
        List.of(
            "促销编码",
            "促销名称",
            "促销类型",
            "平台",
            "店铺",
            "币种",
            "商品标题",
            "商品编码",
            "促销期起(站点当地自然日)",
            "促销期止(不含)",
            "促销期天数",
            "促销期覆盖天数(有平台快照)",
            "促销期销量",
            "促销期销售额",
            "促销期成本",
            "促销期毛利",
            "促销期毛利率",
            "基期起(站点当地自然日)",
            "基期止(不含)",
            "基期天数",
            "基期覆盖天数(有平台快照)",
            "基期销量",
            "基期销售额",
            "基期成本",
            "基期毛利",
            "基期毛利率",
            "增量销量(促销期-基期)",
            "增量销售额(促销期-基期)",
            "增量毛利(促销期-基期)",
            "单件让利",
            "补贴成本(单件让利×实际销量)",
            "ROI分子(增量毛利)",
            "ROI分母(补贴成本)",
            "ROI(增量毛利÷补贴成本)",
            "增量判定(判据=增量毛利)",
            "放量不赚(销售额涨且毛利率跌)",
            "商品数",
            "毛利可比商品数",
            "毛利与补贴均可比商品数",
            "数据截至");
    return String.join(",", cols) + "\n";
  }

  /** 金额 / 数量：不可比写「不可比」（不是 0，也不是空）。 */
  private static String plain(BigDecimal value) {
    return value == null ? "不可比" : value.toPlainString();
  }

  private static String nullTo(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
