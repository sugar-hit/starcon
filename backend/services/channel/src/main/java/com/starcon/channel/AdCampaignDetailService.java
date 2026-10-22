package com.starcon.channel;

import static com.starcon.channel.AdCampaignDetailModels.*;
import static com.starcon.channel.AdCampaignModels.AdCampaignDailyMetricRow;
import static com.starcon.channel.AdCampaignModels.AdCampaignOperationLogRow;
import static com.starcon.channel.AdCampaignModels.AdCampaignRow;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 14.1.1.2 活动详情。
 *
 * <p>蓝图：「单活动全景：基本设置、预算与消耗曲线、广告组列表、投放商品、关键词、创意（视频/图片）、
 * 每日表现明细、平台侧修改历史。输入：campaign_id；聚合子资源。
 * 输出：详情视图 + 操作入口（调预算、改竞价、暂停、复制结构）。
 * 关系：上游 = 14.1.1.1；下游 = 14.1.2.1 广告组、14.3.1.1 报表。
 * <b>"复制结构"是高价值操作</b>：把跑得好的活动结构复制到新店铺/新 ASIN，是广告提效的核心动作。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>基本设置 / 每日表现明细</b> → 复用上游 14.1.1.1 的 {@code AdCampaignRow} 与
 *       {@code AdCampaignDailyMetricRow}（**不重写**：活动本体只有一份真相）。
 *   <li><b>预算与消耗曲线</b> → {@link #panorama(String, LocalDate, LocalDate)}：
 *       逐日消耗与当日预算的对照 + 预算使用率。
 *   <li><b>广告组列表 / 投放商品 / 创意</b> → 本模块自己的表（V277）：
 *       14.1.2.1 广告组管理未交付 ⇒ 结构必须在本模块有真的落点。
 *   <li><b>关键词</b> → **读上游 14.1.3.1 的 {@code ad_keyword}**，本模块不建第二张关键词表。
 *   <li><b>平台侧修改历史</b> → 🔴 **没有同步源**，显式标成不可用（{@code available=false} +
 *       {@code ownerModule} + {@code impact}），当前展示的是**本地**操作审计。
 *   <li><b>操作入口</b> → 调预算 / 暂停走上游 14.1.1.1 的批量操作入口（单条 id）；
 *       改竞价与复制结构是本模块自己的动作。
 * </ul>
 *
 * <p>🔴 本模块最容易做丢的三件事，这里都挡住了：
 *
 * <ol>
 *   <li><b>「平台侧修改历史」不能编</b>。上游 {@code ad_campaign_operation_log} 是**本地**操作审计。
 *       本模块**不建**「平台改历史」表 —— 没有同步源，表建出来也是空的，
 *       而空表会被界面渲染成「没有改过」——那比说「拿不到」严重得多。
 *   <li><b>「复制结构」的副本是真的，平台侧还不是</b>。复制真的落库（目标活动 + 广告组 + 商品 + 创意），
 *       同时明确标出 {@code pendingPlatformWriteback}：平台回写队列未交付。
 *   <li><b>关键词不复制</b>。平台侧还没建这个活动，关键词 id 无从分配；
 *       往 V274 的「平台原文」表里塞本地编的 id，比少复制一段严重得多。
 * </ol>
 */
@Service
public class AdCampaignDetailService {

  // ==================================================================
  // 护栏：改竞价的上限（蓝图 14 章关键约束「必须有上限保护」）
  // ==================================================================

  /** 单个广告组允许的竞价上限（绝对值）。 */
  static final BigDecimal MAX_BID = new BigDecimal("1000.0000");

  /** 单个广告组一次允许调整的竞价上限（绝对值）。 */
  static final BigDecimal MAX_SINGLE_BID_DELTA = new BigDecimal("200.0000");

  /** 一次请求最多允许调整的广告组条数。 */
  static final int MAX_GROUPS_PER_REQUEST = 100;

  /** 预算曲线最多支持的区间天数。 */
  static final int MAX_CURVE_DAYS = 400;

  /** 未指定 from/to 时，预算曲线默认取「有数据的最后 30 天」。 */
  static final int DEFAULT_CURVE_DAYS = 30;

  /** 归一后的状态全集（与 V273 的 ad_campaign.status 同口径）。 */
  static final List<String> STATUSES =
      List.of("ENABLED", "PAUSED", "ENDED", "ARCHIVED", "PENDING_REVIEW", "UNKNOWN");

  /** 创意类型全集。 */
  static final List<String> CREATIVE_TYPES = List.of("IMAGE", "VIDEO", "CAROUSEL", "TEXT");

  /** 复制结构时投放商品的处理方式全集。 */
  static final List<String> PRODUCT_MODES = List.of("KEEP_PRODUCTS", "TARGET_LISTING");

  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** ACOS / ROAS 的口径定义（照抄上游 14.1.1.1 的同一份定义，跨模块口径不许各说各话）。 */
  static final String METRIC_DEFINITION_NOTE =
      "ACOS = 消耗 / 销售额，ROAS = 销售额 / 消耗，两者互为倒数；分母为 0 时返回空值（不可比），"
          + "不是 0。区间的 ACOS / ROAS 是**先汇总再相除**（总消耗 / 总销售额），"
          + "不是各日比率的平均。";

  /** 🔴 平台侧修改历史为什么拿不到（这段话是给界面看的，不许被改写成「暂无修改」）。 */
  static final String PLATFORM_CHANGE_HISTORY_NOTE =
      "🔴 平台侧修改历史**依赖平台广告 API 同步**，而平台广告 API 同步器**尚未交付** ⇒ "
          + "本系统**没有平台侧的改历史数据源**。"
          + "当前展示的是**本地操作审计**（谁在本系统里改了预算 / 状态 / 竞价），"
          + "它**不等于**平台侧的改历史：平台侧被别人改过的事，这里一条都不会有。"
          + "本模块**不建**「平台改历史」表 —— 没有同步源的空表会被界面渲染成「没有改过」，"
          + "那比明说「拿不到」严重得多。";

  private final DSLContext sql;
  private final AdCampaignService campaigns;

  public AdCampaignDetailService(DSLContext sql, AdCampaignService campaigns) {
    this.sql = sql;
    this.campaigns = campaigns;
  }

  // ==================================================================
  // 小工具（照抄 AdCampaignService 的同名写法）
  // ==================================================================

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private long count(String query, Object... binds) {
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

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /** 比率：分母为 0 ⇒ null（不可比），不是 0。 */
  private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
    if (denominator == null || denominator.signum() == 0) {
      return null;
    }
    return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
  }

  /** LIKE 里的通配符要转义，否则用户搜 "50%" 会匹配到一切。 */
  private static String escapeLike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
    return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
  }

  /** 跨租户与不存在返回**逐字相同**的 404（照抄上游 14.1.1.1 的同一款口径）。 */
  private ResponseStatusException notFound(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在");
  }

  private static UUID parseId(String id) {
    try {
      return UUID.fromString(id.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 不是合法 UUID");
    }
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public AdCampaignDetailCapabilities capabilities() {
    return new AdCampaignDetailCapabilities(
        List.of(
            new AdCampaignDetailCapabilityDimension(
                "campaign_panorama",
                "单活动全景：基本设置 + 预算与消耗曲线 + 每日表现明细 + 各子资源条数",
                true,
                "ad_campaign + ad_campaign_daily_metric（V273，上游 14.1.1.1）",
                "",
                "没有全景就没有「这一条活动到底怎么了」这个问题的答案"),
            new AdCampaignDetailCapabilityDimension(
                "budget_curve",
                "预算与消耗曲线：逐日消耗 vs 当日预算 + 预算使用率",
                true,
                "ad_campaign_daily_metric + ad_campaign.daily_budget（V273）",
                "",
                "没有曲线就看不出「钱花在哪天」，调预算只能凭感觉"),
            new AdCampaignDetailCapabilityDimension(
                "ad_group_list",
                "广告组列表：默认竞价 / 组级预算 / 定向 / 状态 + 组内商品与创意条数",
                true,
                "ad_campaign_group（V277）",
                "",
                "14.1.2.1 广告组管理未交付 ⇒ 结构若不在本模块落点，详情页就只有一张空表"),
            new AdCampaignDetailCapabilityDimension(
                "targeted_products",
                "投放商品：广告组 → listing 的投放关系（带 listing 状态）",
                true,
                "ad_campaign_group_product + listing（V277 / V171 上游 4.1.1.1）",
                "",
                "「投的是哪个商品」是归因的起点，没有它就无法把花费落到内部 SKU"),
            new AdCampaignDetailCapabilityDimension(
                "creatives",
                "创意（视频 / 图片 / 图集 / 文本）",
                true,
                "ad_campaign_creative（V277）",
                "",
                "没有创意就看不出「同结构哪条素材更跑得动」"),
            new AdCampaignDetailCapabilityDimension(
                "keywords",
                "关键词：读上游 14.1.3.1 已交付的关键词表（本模块只读消费）",
                true,
                "ad_keyword（V274，上游 14.1.3.1）",
                "",
                "本模块**不建**第二张关键词表 —— 同一个词在两处各有一份，改价就会改到不同的地方"),
            new AdCampaignDetailCapabilityDimension(
                "daily_performance",
                "每日表现明细（曝光 / 点击 / 订单 / 消耗 / 销售额 / ACOS / ROAS）",
                true,
                "ad_campaign_daily_metric（V273，上游 14.1.1.1）",
                "",
                "只有累计值就没法按天看，也没法复算 14.3.1.1 报表"),
            new AdCampaignDetailCapabilityDimension(
                "platform_change_history",
                "🔴 平台侧修改历史（谁在平台侧改了这条活动）",
                false,
                "—",
                "平台适配层（channel/vendor）的平台广告 API 同步",
                "没有同步源 ⇒ 平台侧改历史**拿不到**；当前只展示本地操作审计，"
                    + "平台侧被别人改过的事这里一条都不会有"),
            new AdCampaignDetailCapabilityDimension(
                "platform_writeback",
                "🔴 暂停 / 启用 / 调预算 / 复制结构**回写平台**",
                false,
                "—",
                "平台适配层（channel/vendor）的平台回写队列",
                "本模块的动作只落本地并标记「待回写平台」—— 界面会显式标出，"
                    + "避免把「本地已暂停」当成「平台侧已暂停」"),
            new AdCampaignDetailCapabilityDimension(
                "bid_change",
                "改竞价（广告组默认竞价，带上限保护与审计）",
                true,
                "ad_campaign_group + ad_campaign_group_bid_log（V277）",
                "",
                "没有它，改一次默认竞价只能去平台后台"),
            new AdCampaignDetailCapabilityDimension(
                "copy_structure",
                "🔴 复制结构：把活动结构复制到新店铺 / 新 ASIN（本地真落库）",
                true,
                "ad_campaign_structure_copy + ad_campaign_group*（V277）",
                "",
                "这是广告提效的核心动作 —— 跑得好的结构不该每次都手工重建一遍"),
            new AdCampaignDetailCapabilityDimension(
                "group_management",
                "广告组的完整管理（定向设置、组级报表入口、组级增删改）",
                false,
                "—",
                "14.1.2.1 广告组管理",
                "本模块只提供结构的**读**与「复制结构」这一个写动作；"
                    + "组的日常管理归 14.1.2.1"),
            new AdCampaignDetailCapabilityDimension(
                "bid_strategy",
                "竞价策略（自动竞价 / 动态竞价 / 目标 ACOS 反推竞价）",
                false,
                "—",
                "14.2.1.1 竞价管理",
                "本模块只提供「把这个组的默认竞价改成多少」这一个动作，"
                    + "不做任何策略"),
            new AdCampaignDetailCapabilityDimension(
                "targeting",
                "投放定位的解读与调整建议（地域 / 人群 / 设备 / 时段）",
                false,
                "—",
                "14.1.3.2 投放定位",
                "本模块只存平台原文的定向字段，不解读也不给建议"),
            new AdCampaignDetailCapabilityDimension(
                "keyword_copy",
                "复制结构时**一并复制关键词**",
                false,
                "—",
                "平台适配层（channel/vendor）平台广告 API",
                "平台侧还没建这个活动，关键词 id 无从分配；"
                    + "往「平台原文」表里塞本地编的 id 比少复制一段严重得多"),
            new AdCampaignDetailCapabilityDimension(
                "report",
                "活动报表（可下钻到渠道 / 搜索词 / 归因）",
                false,
                "—",
                "14.3.1.1 活动报表",
                "本模块只给活动自身的每日明细；可下钻的报表归 14.3.1.1")),
        CREATIVE_TYPES,
        PRODUCT_MODES,
        bidGuardrails(),
        METRIC_DEFINITION_NOTE,
        PLATFORM_CHANGE_HISTORY_NOTE,
        "🔴 复制结构 = **结构副本真的落库**（目标活动 + 广告组 + 投放商品 + 创意），"
            + "同时明确标出「平台侧还没有这份结构」：平台回写队列未交付，"
            + "目标活动落成「待回写平台」且状态为暂停（复制出来不投放）。"
            + "🔴 关键词**不复制**：平台侧还没建这个活动，关键词 id 无从分配 —— "
            + "往 14.1.3.1 的「平台原文」表里塞本地编的 id，比少复制一段严重得多。"
            + "🔴 复制的是**结构**，不是钱：目标活动的预算与投放期**不随结构一起复制**，"
            + "预算要用「调预算」单独设。",
        "上游 = 14.1.1.1 活动列表（V273：活动本体 / 每日明细 / 本地操作审计）、"
            + "14.1.3.1 关键词管理（V274：ad_keyword，只读）、"
            + "4.1.1.1 Listing（V171：投放商品指向的 listing）、"
            + "2.1.1.1 平台目录与 2.3.1.1 店铺（V1，只读引用）。",
        "下游 = 14.1.2.1 广告组管理（接管广告组的日常管理）、"
            + "14.3.1.1 活动报表（按本模块的每日明细出图）。"
            + "🔴 本模块**不实现**竞价策略、投放定位解读、平台回写与平台侧改历史 —— "
            + "能力声明里对应维度显式 available=false，不用空列表冒充已交付。");
  }

  private static AdBidGuardrails bidGuardrails() {
    return new AdBidGuardrails(
        MAX_BID,
        MAX_SINGLE_BID_DELTA,
        MAX_GROUPS_PER_REQUEST,
        "🔴 单次上限与绝对上限一起给：单次上限挡住「手滑把竞价写成 1000」，"
            + "绝对上限挡住「一次调整直接越级」。"
            + "超限的那几条会被**逐条拒绝**并写进审计（被拒也要留痕，"
            + "否则「为什么这条没生效」查不出来）。");
  }

  // ==================================================================
  // 详情全景
  // ==================================================================

  /**
   * 单活动全景视图。
   *
   * <p>🔴 曲线窗口的规则是确定的：没给 from/to 时取「有数据的最后 30 天」；
   * 给了就按给的来，但跨度不得超过 {@value #MAX_CURVE_DAYS} 天。
   */
  public AdCampaignDetailPanorama panorama(String id, LocalDate from, LocalDate to) {
    UUID campaignId = parseId(id);
    // 🔴 复用上游的活动本体 + 每日明细 + 本地操作审计：活动真相只有一份。
    var upstream = campaigns.campaign(campaignId.toString());
    AdCampaignRow campaign = upstream.campaign();

    List<AdCampaignDailyMetricRow> windowed = windowMetrics(upstream.dailyMetrics(), from, to);
    AdCampaignBudgetCurve curve = budgetCurve(campaign, windowed);

    AdCampaignStructureSummary structure = structureSummary(campaign);
    AdCampaignChangeHistory changeHistory =
        new AdCampaignChangeHistory(
            false,
            "平台适配层（channel/vendor）的平台广告 API 同步",
            "没有同步源 ⇒ 平台侧改历史**拿不到**；当前只展示本地操作审计，"
                + "平台侧被别人改过的事这里一条都不会有",
            PLATFORM_CHANGE_HISTORY_NOTE,
            "当前展示的是**本地**操作审计（谁在本系统里改了预算 / 状态 / 竞价），"
                + "**不是**平台侧的改历史。",
            upstream.operationLogs());

    return new AdCampaignDetailPanorama(
        campaign,
        curve,
        windowed,
        structure,
        operations(),
        changeHistory,
        "每日明细按平台当地日切分；ACOS = 消耗 / 销售额、ROAS = 销售额 / 消耗，"
            + "分母为 0 时显示「不可比」而不是 0。"
            + "🔴 平台侧修改历史当前**不可得**（没有平台广告 API 同步源），"
            + "页面展示的是本地操作审计。");
  }

  /** 曲线窗口：没给 from/to ⇒ 取有数据的最后 30 天；给了就按给的来。 */
  private List<AdCampaignDailyMetricRow> windowMetrics(
      List<AdCampaignDailyMetricRow> all, LocalDate from, LocalDate to) {
    if (all.isEmpty()) {
      return List.of();
    }
    LocalDate latest =
        all.stream().map(AdCampaignDailyMetricRow::metricDate).max(LocalDate::compareTo).orElseThrow();
    final LocalDate effectiveTo = to == null ? latest : to;
    final LocalDate effectiveFrom =
        from == null ? effectiveTo.minusDays(DEFAULT_CURVE_DAYS - 1L) : from;
    if (effectiveTo.isBefore(effectiveFrom)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "统计截止日不能早于统计起始日");
    }
    long span = ChronoUnit.DAYS.between(effectiveFrom, effectiveTo) + 1;
    if (span > MAX_CURVE_DAYS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "预算曲线区间最多 " + MAX_CURVE_DAYS + " 天，本次是 " + span + " 天，请缩小 from/to 范围");
    }
    return all.stream()
        .filter(m -> !m.metricDate().isBefore(effectiveFrom) && !m.metricDate().isAfter(effectiveTo))
        .toList();
  }

  private AdCampaignBudgetCurve budgetCurve(
      AdCampaignRow campaign, List<AdCampaignDailyMetricRow> windowed) {
    BigDecimal dailyBudget = campaign.dailyBudget();
    BigDecimal totalSpend =
        windowed.stream()
            .map(AdCampaignDailyMetricRow::spend)
            .map(AdCampaignDetailService::nz)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalSales =
        windowed.stream()
            .map(AdCampaignDailyMetricRow::sales)
            .map(AdCampaignDetailService::nz)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    // 🔴 只画**有指标行**的天：没有指标行的一天是「没数据」，不是「花了 0」。
    //   把没数据画成 0 会让运营以为「那天没花钱」，而真相是「那天没同步到数据」。
    List<AdCampaignBudgetCurvePoint> points =
        windowed.stream()
            .sorted(java.util.Comparator.comparing(AdCampaignDailyMetricRow::metricDate))
            .map(
                m -> {
                  BigDecimal spend = nz(m.spend());
                  BigDecimal sales = nz(m.sales());
                  return new AdCampaignBudgetCurvePoint(
                      m.metricDate(),
                      dailyBudget,
                      spend,
                      sales,
                      m.acos(),
                      m.roas(),
                      // 🔴 预算使用率：没有日预算 ⇒ 不可比（null），不是 0
                      dailyBudget == null || dailyBudget.signum() == 0
                          ? null
                          : spend.divide(dailyBudget, 6, RoundingMode.HALF_UP));
                })
            .toList();

    LocalDate curveFrom = points.isEmpty() ? null : points.get(0).date();
    LocalDate curveTo = points.isEmpty() ? null : points.get(points.size() - 1).date();
    int windowDays = points.size();
    // 🔴 区间预算 = 日预算 × **有数据的天数**（不是整个跨度）：
    //   没有指标行的那天既没有消耗也没有预算可用，算进预算会虚高。
    BigDecimal windowBudget =
        dailyBudget == null ? null : dailyBudget.multiply(BigDecimal.valueOf(windowDays));

    return new AdCampaignBudgetCurve(
        dailyBudget,
        campaign.currency(),
        curveFrom,
        curveTo,
        windowDays,
        windowBudget,
        totalSpend,
        totalSales,
        ratio(totalSpend, totalSales),
        ratio(totalSales, totalSpend),
        points,
        "曲线上的点 = **有每日指标的天**。没有指标行的一天是「没数据」，不是「花了 0」，因此不画。"
            + "日预算为空 = 平台侧没有预算（如 DSP 固定价），此时预算使用率不可比（空值）而不是 0。"
            + "未指定 from/to 时取「有数据的最后 "
            + DEFAULT_CURVE_DAYS
            + " 天」。");
  }

  /** 结构资源条数与新鲜度。 */
  private AdCampaignStructureSummary structureSummary(AdCampaignRow campaign) {
    long groupCount =
        count(
            "select count(*) from ad_campaign_group where tenant_id = " + TENANT + " and campaign_id = ?",
            campaign.id());
    long productCount =
        count(
            "select count(*) from ad_campaign_group_product where tenant_id = " + TENANT + " and campaign_id = ?",
            campaign.id());
    long creativeCount =
        count(
            "select count(*) from ad_campaign_creative where tenant_id = " + TENANT + " and campaign_id = ?",
            campaign.id());
    long keywordCount = keywordCount(campaign);

    OffsetDateTime structureAsOf = null;
    for (Record r :
        sql.fetch(
            "select max(d) d from ("
                + "select max(data_as_of) d from ad_campaign_group where tenant_id = " + TENANT + " and campaign_id = ?"
                + " union all select max(data_as_of) d from ad_campaign_creative where tenant_id = " + TENANT + " and campaign_id = ?"
                + ") x",
            campaign.id(),
            campaign.id())) {
      OffsetDateTime value = r.get("d", OffsetDateTime.class);
      if (value != null && (structureAsOf == null || value.isAfter(structureAsOf))) {
        structureAsOf = value;
      }
    }

    return new AdCampaignStructureSummary(
        groupCount,
        productCount,
        creativeCount,
        keywordCount,
        structureAsOf,
        "广告组 / 投放商品 / 创意由本模块的结构登记入口落库（平台广告 API 同步器未交付）；"
            + "关键词**读**上游 14.1.3.1 已交付的关键词表。"
            + "结构条数为 0 且没有结构截至时刻 ⇒ 结构数据**还没登记进来**，"
            + "这**不等于**「这个活动没有广告组」。");
  }

  /** 操作入口：哪些能做、哪些不能、为什么不能。 */
  private AdCampaignDetailOperations operations() {
    return new AdCampaignDetailOperations(
        List.of(
            new AdCampaignOperationEntry(
                "SET_BUDGET",
                "调预算",
                true,
                "14.1.1.1 活动列表（POST /channel/ad-campaigns/bulk-operations，单条 id）",
                "",
                "",
                "🔴 调预算与暂停**复用上游 14.1.1.1 的批量操作入口**：护栏、审计与"
                    + "「本地已改、尚未回写平台」的标记都在那边，本模块不另起一套。"),
            new AdCampaignOperationEntry(
                "PAUSE",
                "暂停 / 启用",
                true,
                "14.1.1.1 活动列表（POST /channel/ad-campaigns/bulk-operations，单条 id）",
                "",
                "",
                "🔴 暂停只落本地并标记「待回写平台」—— 平台回写队列未交付。"),
            new AdCampaignOperationEntry(
                "ADJUST_BID",
                "改竞价（广告组默认竞价）",
                true,
                "本模块（ad_campaign_group + ad_campaign_group_bid_log）",
                "",
                "",
                "🔴 带单次上限与绝对上限，超限**逐条拒绝**并留审计。"
                    + "竞价策略（自动 / 动态 / 目标 ACOS）不归本模块。"),
            new AdCampaignOperationEntry(
                "COPY_STRUCTURE",
                "复制结构到新店铺 / 新 ASIN",
                true,
                "本模块（ad_campaign_structure_copy + ad_campaign_group*）",
                "",
                "",
                "🔴 复制的是**结构**（广告组 / 投放商品 / 创意），不是钱："
                    + "目标活动的预算与投放期不一起复制，预算要单独调。"),
            new AdCampaignOperationEntry(
                "PLATFORM_WRITEBACK",
                "回写平台",
                false,
                "—",
                "平台适配层（channel/vendor）的平台回写队列",
                "本模块的动作只落本地并标记「待回写平台」；"
                    + "界面会把「本地已改」与「平台侧已改」分开展示。",
                ""),
            new AdCampaignOperationEntry(
                "PLATFORM_CHANGE_HISTORY",
                "平台侧修改历史",
                false,
                "—",
                "平台适配层（channel/vendor）的平台广告 API 同步",
                "没有同步源 ⇒ 平台侧改历史拿不到；当前只展示本地操作审计。",
                ""),
            new AdCampaignOperationEntry(
                "GROUP_MANAGEMENT",
                "广告组管理（定向设置 / 组级报表入口）",
                false,
                "—",
                "14.1.2.1 广告组管理",
                "本模块只提供结构的读与「复制结构」；组的日常管理归 14.1.2.1。",
                ""),
            new AdCampaignOperationEntry(
                "BID_STRATEGY",
                "竞价策略（自动 / 动态 / 目标 ACOS）",
                false,
                "—",
                "14.2.1.1 竞价管理",
                "本模块只做「把默认竞价改成多少」这一个动作。",
                "")));
  }

  // ==================================================================
  // 子资源：广告组 / 投放商品 / 创意 / 关键词
  // ==================================================================

  /** 广告组列表：真分页 + 真筛选。 */
  public AdCampaignGroupPage groups(String id, String keyword, String status, int page, int size) {
    UUID campaignId = requireCampaign(id);
    StringBuilder where = new StringBuilder(" where g.tenant_id = " + TENANT + " and g.campaign_id = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(campaignId);

    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (g.group_name ilike ? or g.external_group_id ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
    }
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and g.status = ?");
      binds.add(state.toUpperCase(Locale.ROOT));
    }

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    long total =
        count("select count(*) from ad_campaign_group g" + where, binds.toArray());

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);

    List<AdCampaignGroupRow> items =
        sql.fetch(
                "select g.id, g.campaign_id, g.external_group_id, g.group_name, g.status,"
                    + " g.default_bid, g.bid_currency, g.daily_budget, g.targeting_type,"
                    + " (select count(*) from ad_campaign_group_product p"
                    + "   where p.tenant_id = g.tenant_id and p.group_id = g.id) product_count,"
                    + " (select count(*) from ad_campaign_creative cr"
                    + "   where cr.tenant_id = g.tenant_id and cr.group_id = g.id) creative_count,"
                    + " g.data_as_of, g.updated_at"
                    + " from ad_campaign_group g"
                    + where
                    + " order by g.id limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(
                r ->
                    new AdCampaignGroupRow(
                        r.get("id", UUID.class),
                        r.get("campaign_id", UUID.class),
                        r.get("external_group_id", String.class),
                        r.get("group_name", String.class),
                        r.get("status", String.class),
                        r.get("default_bid", BigDecimal.class),
                        r.get("bid_currency", String.class),
                        r.get("daily_budget", BigDecimal.class),
                        r.get("targeting_type", String.class),
                        r.get("product_count", Long.class),
                        r.get("creative_count", Long.class),
                        r.get("data_as_of", OffsetDateTime.class),
                        r.get("updated_at", OffsetDateTime.class)))
            .toList();

    return new AdCampaignGroupPage(items, total, safePage, safeSize);
  }

  /** 投放商品列表：真分页 + 真筛选（带 listing 状态）。 */
  public AdCampaignProductPage products(String id, String keyword, int page, int size) {
    UUID campaignId = requireCampaign(id);
    StringBuilder where =
        new StringBuilder(
            " where p.tenant_id = " + TENANT + " and p.campaign_id = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(campaignId);

    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (p.title ilike ? or p.external_item_id ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
    }

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    String from =
        " from ad_campaign_group_product p"
            + " join ad_campaign_group g on g.tenant_id = p.tenant_id and g.id = p.group_id"
            // 🔴 LEFT JOIN：listing 不在数据范围内时**不丢投放商品这一行** ——
            //   丢了的话「条数」与「列表」会对不上，界面就不知道自己漏了什么。
            + " left join listing l on l.tenant_id = p.tenant_id and l.id = p.listing_id";

    long total = count("select count(*)" + from + where, binds.toArray());

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);

    List<AdCampaignProductRow> items =
        sql.fetch(
                "select p.id, p.group_id, g.group_name, p.listing_id, p.external_item_id, p.title,"
                    + " l.status listing_status, l.review_status listing_review_status, p.created_at"
                    + from
                    + where
                    + " order by p.id limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(
                r ->
                    new AdCampaignProductRow(
                        r.get("id", UUID.class),
                        r.get("group_id", UUID.class),
                        r.get("group_name", String.class),
                        r.get("listing_id", UUID.class),
                        r.get("external_item_id", String.class),
                        r.get("title", String.class),
                        r.get("listing_status", String.class),
                        r.get("listing_review_status", String.class),
                        r.get("created_at", OffsetDateTime.class)))
            .toList();

    return new AdCampaignProductPage(items, total, safePage, safeSize);
  }

  /** 创意列表：真分页 + 按类型筛选。 */
  public AdCampaignCreativePage creatives(String id, String creativeType, int page, int size) {
    UUID campaignId = requireCampaign(id);
    StringBuilder where = new StringBuilder(" where cr.tenant_id = " + TENANT + " and cr.campaign_id = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(campaignId);

    String type = blankToNull(creativeType);
    if (type != null) {
      where.append(" and cr.creative_type = ?");
      binds.add(type.toUpperCase(Locale.ROOT));
    }

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    long total =
        count("select count(*) from ad_campaign_creative cr" + where, binds.toArray());

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);

    List<AdCampaignCreativeRow> items =
        sql.fetch(
                "select cr.id, cr.campaign_id, cr.group_id, g.group_name, cr.external_creative_id,"
                    + " cr.creative_name, cr.creative_type, cr.asset_url, cr.duration_seconds,"
                    + " cr.status, cr.data_as_of"
                    + " from ad_campaign_creative cr"
                    + " left join ad_campaign_group g on g.tenant_id = cr.tenant_id and g.id = cr.group_id"
                    + where
                    + " order by cr.id limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(
                r ->
                    new AdCampaignCreativeRow(
                        r.get("id", UUID.class),
                        r.get("campaign_id", UUID.class),
                        r.get("group_id", UUID.class),
                        r.get("group_name", String.class),
                        r.get("external_creative_id", String.class),
                        r.get("creative_name", String.class),
                        r.get("creative_type", String.class),
                        r.get("asset_url", String.class),
                        r.get("duration_seconds", Integer.class),
                        r.get("status", String.class),
                        r.get("data_as_of", OffsetDateTime.class)))
            .toList();

    return new AdCampaignCreativePage(items, total, safePage, safeSize);
  }

  /**
   * 关键词列表：**读上游 14.1.3.1 的 {@code ad_keyword}**。
   *
   * <p>🔴 定位方式与上游 14.1.1.1 自己的「按平台侧 id 取活动」同一口径：
   * (platform_id, shop_id, external_campaign_id) —— 上游的关键词表刻意不建跨车道 FK，
   * 本模块**照它的口径读**，不建第二份。
   */
  public AdCampaignKeywordPage keywords(String id, String keyword, int page, int size) {
    AdCampaignRow campaign = requireCampaignRow(id);
    UUID shopId = shopId(campaign.shopCode());
    if (shopId == null) {
      return new AdCampaignKeywordPage(List.of(), 0, Math.max(page, 0), Math.min(Math.max(size, 1), 200));
    }

    StringBuilder where =
        new StringBuilder(
            " where k.tenant_id = "
                + TENANT
                + " and k.platform_id = ? and k.shop_id = ? and k.external_campaign_id = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(campaign.platformId());
    binds.add(shopId);
    binds.add(campaign.externalCampaignId());

    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and k.keyword ilike ?");
      binds.add("%" + escapeLike(word) + "%");
    }

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    long total = count("select count(*) from ad_keyword k" + where, binds.toArray());

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);

    List<AdCampaignKeywordRow> items =
        sql.fetch(
                "select k.id, k.external_keyword_id, k.keyword, k.internal_ad_type, k.platform_ad_type,"
                    + " k.bid, k.bid_currency, k.status, k.listing_keyword_id, k.updated_at"
                    + " from ad_keyword k"
                    + where
                    + " order by k.id limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(
                r ->
                    new AdCampaignKeywordRow(
                        r.get("id", UUID.class),
                        r.get("external_keyword_id", String.class),
                        r.get("keyword", String.class),
                        r.get("internal_ad_type", String.class),
                        r.get("platform_ad_type", String.class),
                        r.get("bid", BigDecimal.class),
                        r.get("bid_currency", String.class),
                        r.get("status", String.class),
                        r.get("listing_keyword_id", UUID.class),
                        r.get("updated_at", OffsetDateTime.class)))
            .toList();

    return new AdCampaignKeywordPage(items, total, safePage, safeSize);
  }

  /** 平台侧修改历史：**显式不可用** + 本地操作审计。 */
  public AdCampaignChangeHistory changeHistory(String id) {
    var upstream = campaigns.campaign(parseId(id).toString());
    return new AdCampaignChangeHistory(
        false,
        "平台适配层（channel/vendor）的平台广告 API 同步",
        "没有同步源 ⇒ 平台侧改历史**拿不到**；当前只展示本地操作审计，"
            + "平台侧被别人改过的事这里一条都不会有",
        PLATFORM_CHANGE_HISTORY_NOTE,
        "当前展示的是**本地**操作审计（谁在本系统里改了预算 / 状态 / 竞价），"
            + "**不是**平台侧的改历史。",
        upstream.operationLogs());
  }

  // ==================================================================
  // 改竞价
  // ==================================================================

  /**
   * 改广告组的默认竞价：逐条返回执行结果，带上限保护与审计。
   *
   * <p>🔴 与上游 14.1.1.1 的批量预算操作同一套纪律：逐条结果、超限逐条拒绝、
   * 被拒的也留审计。竞价是钱，**一次手滑就能烧光预算**。
   */
  public AdCampaignBidUpdateResult updateBids(String id, AdCampaignBidUpdateRequest request) {
    UUID campaignId = requireCampaign(id);
    if (request.items().size() > MAX_GROUPS_PER_REQUEST) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "一次最多调整 " + MAX_GROUPS_PER_REQUEST + " 个广告组，本次提交了 "
              + request.items().size() + " 个");
    }

    UUID actor = currentActor();
    List<AdCampaignBidUpdateItemResult> items = new ArrayList<>();
    int applied = 0;
    int rejected = 0;
    int skipped = 0;

    // 🔴 同一次请求里同一个广告组只能出现一次：两次「改成多少」意图不明，
    //   逐条执行的结果取决于顺序 —— 那不是调用方要的语义。
    Set<UUID> seen = new LinkedHashSet<>();
    for (AdCampaignBidUpdateItem item : request.items()) {
      if (!seen.add(item.groupId())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "同一次请求里不能重复调整同一个广告组：" + item.groupId());
      }
    }

    for (AdCampaignBidUpdateItem item : request.items()) {
      Record row =
          sql.fetchOne(
              "select id, group_name, default_bid, bid_currency from ad_campaign_group"
                  + " where tenant_id = "
                  + TENANT
                  + " and id = ? and campaign_id = ?",
              item.groupId(),
              campaignId);
      if (row == null) {
        rejected++;
        logBid(campaignId, item, null, "REJECTED", "广告组不存在或不属于当前活动", actor);
        items.add(
            new AdCampaignBidUpdateItemResult(
                item.groupId(), "", null, item.bid(), "REJECTED",
                "广告组不存在或不属于当前活动"));
        continue;
      }

      String groupName = row.get("group_name", String.class);
      BigDecimal before = row.get("default_bid", BigDecimal.class);

      if (before != null && before.compareTo(item.bid()) == 0) {
        skipped++;
        logBid(campaignId, item, before, "SKIPPED", "已经是目标竞价", actor);
        items.add(
            new AdCampaignBidUpdateItemResult(
                item.groupId(), groupName, before, item.bid(), "SKIPPED", "已经是目标竞价"));
        continue;
      }
      if (item.bid().compareTo(MAX_BID) > 0) {
        rejected++;
        String message = "目标竞价超过单组竞价上限 " + MAX_BID;
        logBid(campaignId, item, before, "REJECTED", message, actor);
        items.add(
            new AdCampaignBidUpdateItemResult(
                item.groupId(), groupName, before, item.bid(), "REJECTED", message));
        continue;
      }
      BigDecimal delta = item.bid().subtract(nz(before)).abs();
      if (delta.compareTo(MAX_SINGLE_BID_DELTA) > 0) {
        rejected++;
        String message =
            "单个广告组一次竞价调整超过上限 " + MAX_SINGLE_BID_DELTA + "（本次调整 " + delta + "）";
        logBid(campaignId, item, before, "REJECTED", message, actor);
        items.add(
            new AdCampaignBidUpdateItemResult(
                item.groupId(), groupName, before, item.bid(), "REJECTED", message));
        continue;
      }

      sql.execute(
          "update ad_campaign_group set default_bid = ?, updated_at = now()"
              + " where tenant_id = " + TENANT + " and id = ?",
          item.bid(),
          item.groupId());
      logBid(campaignId, item, before, "APPLIED", "已生效（本地），尚未回写平台", actor);
      applied++;
      items.add(
          new AdCampaignBidUpdateItemResult(
              item.groupId(), groupName, before, item.bid(), "APPLIED", "已生效（本地），尚未回写平台"));
    }

    return new AdCampaignBidUpdateResult(
        request.items().size(),
        applied,
        rejected,
        skipped,
        bidGuardrails(),
        items,
        "🔴 竞价改动只落本地：平台回写队列尚未交付（平台适配层），"
            + "界面上会显式标出「尚未回写平台」，避免把「本地已改价」当成「平台侧已改价」。"
            + "「自动竞价 / 动态竞价 / 目标 ACOS 反推竞价」属 14.2.1.1，本模块未交付。");
  }

  /**
   * 改竞价的审计留痕。
   *
   * <p>🔴 三种结果都留：被拒的那几条同样要能复盘「为什么这条没生效」——
   * 只留成功的那几条，事后就查不出是护栏拒的、还是压根没提交。
   */
  private void logBid(
      UUID campaignId,
      AdCampaignBidUpdateItem item,
      BigDecimal before,
      String result,
      String message,
      UUID actor) {
    sql.execute(
        "insert into ad_campaign_group_bid_log (tenant_id,id,campaign_id,group_id,before_bid,"
            + " after_bid,bid_currency,result,message,requested_by,reason,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,now())",
        currentTenant(),
        Ids.next(),
        campaignId,
        item.groupId(),
        before,
        item.bid(),
        "",
        result,
        message,
        actor == null ? currentTenant() : actor,
        text(item.reason()));
  }

  // ==================================================================
  // 复制结构
  // ==================================================================
  /**
   * 把活动结构复制到新店铺 / 新 ASIN。
   *
   * <p>🔴 三条边界，缺一不可：
   *
   * <ol>
   *   <li><b>结构副本真的落库</b>：目标活动 + 广告组 + 投放商品 + 创意全部写进库里，
   *       并记一条 {@code ad_campaign_structure_copy}。
   *   <li><b>平台侧还没有这份结构</b>：目标活动标「待回写平台」、状态落暂停、
   *       平台侧 id 落成 {@code LOCAL-COPY-{uuid}}（**本地标记，不是平台 id**）。
   *   <li><b>关键词不复制</b>：平台侧还没建这个活动，关键词 id 无从分配。
   * </ol>
   */
  public AdCampaignCopyResult copy(String id, AdCampaignCopyRequest request) {
    AdCampaignRow source = requireCampaignRow(id);
    String mode = text(request.productMode()).toUpperCase(Locale.ROOT);
    if (!PRODUCT_MODES.contains(mode)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "投放商品处理方式只能是 KEEP_PRODUCTS / TARGET_LISTING");
    }

    String targetShopCode = text(request.targetShopCode());
    if (targetShopCode.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标店铺编码不能为空");
    }
    Record targetShop =
        sql.fetchOne(
            "select id, platform_id, name from shop where tenant_id = " + TENANT + " and code = ?",
            targetShopCode);
    if (targetShop == null) {
      throw notFound("目标店铺");
    }
    // 🔴 目标店铺必须与源活动**同平台**：广告类型档案与店铺平台都要与活动平台一致
    //   （ad_campaign 的触发器会校验），跨平台复制在物理上就不成立。
    if (!targetShop.get("platform_id", UUID.class).equals(source.platformId())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "目标店铺必须与源活动属于同一平台（广告类型档案按平台归一）");
    }

    UUID targetListingId = null;
    if ("TARGET_LISTING".equals(mode)) {
      if (request.targetListingId() == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "改投指定 ASIN 时必须给出目标 listing id");
      }
      Record listing =
          sql.fetchOne(
              "select id, shop_id, external_item_id, title from listing"
                  + " where tenant_id = " + TENANT + " and id = ?",
              request.targetListingId());
      if (listing == null) {
        throw notFound("目标 listing");
      }
      if (!listing.get("shop_id", UUID.class).equals(targetShop.get("id", UUID.class))) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "目标 listing 必须属于目标店铺（投放的商品不能跨店铺挂）");
      }
      targetListingId = listing.get("id", UUID.class);
    } else {
      // 🔴 沿用源投放商品只允许同店铺：listing 属于店铺，跨店铺沿用在物理上就不成立。
      if (!targetShopCode.equals(source.shopCode())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "沿用源活动的投放商品只允许复制到**同一个店铺**（投放商品属于店铺）—— "
                + "复制到别的店铺请用「改投指定 ASIN」");
      }
    }

    UUID tenant = currentTenant();
    UUID actor = currentActor();
    OffsetDateTime now = OffsetDateTime.now();
    UUID targetCampaignId = Ids.next();
    String targetName =
        blankToNull(request.newCampaignName()) == null
            ? source.campaignName() + "（结构副本）"
            : text(request.newCampaignName());

    // 🔴 广告类型档案 id：活动本体的形状（AdCampaignRow）里没有这一列，
    //   而 ad_campaign 的触发器要求档案的平台与活动平台一致 ⇒ 复制必须沿用同一份档案，
    //   归一口径才不会被复制成另一套。
    UUID profileId =
        one(
            UUID.class,
            "select ad_type_profile_id from ad_campaign where tenant_id = " + TENANT + " and id = ?",
            source.id());
    if (profileId == null) {
      throw notFound("广告活动");
    }

    // 🔴 目标活动：本地真落库，但**平台侧还没有** ⇒ 状态暂停 + 待回写 + 本地 id 标记。
    sql.execute(
        "insert into ad_campaign (tenant_id,id,platform_id,shop_code,ad_type_profile_id,"
            + " external_campaign_id,campaign_name,status,currency,daily_budget,start_at,end_at,"
            + " pending_platform_writeback,local_changed_at,data_as_of,last_synced_at,note,"
            + " created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz,?,now(),now())",
        tenant,
        targetCampaignId,
        source.platformId(),
        targetShopCode,
        profileId,
        "LOCAL-COPY-" + targetCampaignId,
        targetName,
        "PAUSED",
        source.currency(),
        // 🔴 复制的是**结构**，不是钱：预算与投放期不跟着复制（目标活动默认没有预算）
        null,
        null,
        null,
        true,
        now,
        now,
        now,
        "由活动 " + source.id() + " 复制结构而来（本地草稿，平台侧尚未创建）；"
            + "复制的是结构，预算与投放期不一起复制。");

    int groupCount = 0;
    int productCount = 0;
    int creativeCount = 0;

    for (Record group :
        sql.fetch(
            "select id, external_group_id, group_name, status, default_bid, bid_currency,"
                + " daily_budget, targeting_type, data_as_of"
                + " from ad_campaign_group where tenant_id = "
                + TENANT
                + " and campaign_id = ? order by id",
            source.id())) {
      UUID newGroupId = Ids.next();
      sql.execute(
          "insert into ad_campaign_group (tenant_id,id,campaign_id,external_group_id,group_name,"
              + " status,default_bid,bid_currency,daily_budget,targeting_type,data_as_of,"
              + " created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?::timestamptz,now(),now())",
          tenant,
          newGroupId,
          targetCampaignId,
          group.get("external_group_id", String.class),
          group.get("group_name", String.class),
          group.get("status", String.class),
          group.get("default_bid", BigDecimal.class),
          group.get("bid_currency", String.class),
          group.get("daily_budget", BigDecimal.class),
          group.get("targeting_type", String.class),
          group.get("data_as_of", OffsetDateTime.class));
      groupCount++;

      // 投放商品
      if ("TARGET_LISTING".equals(mode)) {
        Record listing =
            sql.fetchOne(
                "select id, external_item_id, title from listing where tenant_id = " + TENANT + " and id = ?",
                targetListingId);
        sql.execute(
            "insert into ad_campaign_group_product (tenant_id,id,campaign_id,group_id,listing_id,"
                + " external_item_id,title,created_at,updated_at)"
                + " values (?,?,?,?,?,?,?,now(),now())",
            tenant,
            Ids.next(),
            targetCampaignId,
            newGroupId,
            targetListingId,
            listing == null ? "" : listing.get("external_item_id", String.class),
            listing == null ? "" : listing.get("title", String.class));
        productCount++;
      } else {
        for (Record product :
            sql.fetch(
                "select listing_id, external_item_id, title from ad_campaign_group_product"
                    + " where tenant_id = " + TENANT + " and group_id = ? order by id",
                group.get("id", UUID.class))) {
          sql.execute(
              "insert into ad_campaign_group_product (tenant_id,id,campaign_id,group_id,listing_id,"
                  + " external_item_id,title,created_at,updated_at)"
                  + " values (?,?,?,?,?,?,?,now(),now())",
              tenant,
              Ids.next(),
              targetCampaignId,
              newGroupId,
              product.get("listing_id", UUID.class),
              product.get("external_item_id", String.class),
              product.get("title", String.class));
          productCount++;
        }
      }

      // 组内创意
      for (Record creative :
          sql.fetch(
              "select external_creative_id, creative_name, creative_type, asset_url,"
                  + " duration_seconds, status, data_as_of"
                  + " from ad_campaign_creative where tenant_id = "
                  + TENANT
                  + " and group_id = ? order by id",
              group.get("id", UUID.class))) {
        sql.execute(
            "insert into ad_campaign_creative (tenant_id,id,campaign_id,group_id,external_creative_id,"
                + " creative_name,creative_type,asset_url,duration_seconds,status,data_as_of,"
                + " created_at,updated_at)"
                + " values (?,?,?,?,?,?,?,?,?,?,?::timestamptz,now(),now())",
            tenant,
            Ids.next(),
            targetCampaignId,
            newGroupId,
            creative.get("external_creative_id", String.class),
            creative.get("creative_name", String.class),
            creative.get("creative_type", String.class),
            creative.get("asset_url", String.class),
            creative.get("duration_seconds", Integer.class),
            creative.get("status", String.class),
            creative.get("data_as_of", OffsetDateTime.class));
        creativeCount++;
      }
    }

    // 活动级创意（不挂在任何组下的）
    for (Record creative :
        sql.fetch(
            "select external_creative_id, creative_name, creative_type, asset_url,"
                + " duration_seconds, status, data_as_of"
                + " from ad_campaign_creative where tenant_id = "
                + TENANT
                + " and campaign_id = ? and group_id is null order by id",
            source.id())) {
      sql.execute(
          "insert into ad_campaign_creative (tenant_id,id,campaign_id,group_id,external_creative_id,"
              + " creative_name,creative_type,asset_url,duration_seconds,status,data_as_of,"
              + " created_at,updated_at)"
              + " values (?,?,?,null,?,?,?,?,?,?,?::timestamptz,now(),now())",
          tenant,
          Ids.next(),
          targetCampaignId,
          creative.get("external_creative_id", String.class),
          creative.get("creative_name", String.class),
          creative.get("creative_type", String.class),
          creative.get("asset_url", String.class),
          creative.get("duration_seconds", Integer.class),
          creative.get("status", String.class),
          creative.get("data_as_of", OffsetDateTime.class));
      creativeCount++;
    }

    sql.execute(
        "insert into ad_campaign_structure_copy (tenant_id,id,source_campaign_id,target_campaign_id,"
            + " target_shop_code,target_listing_id,product_mode,group_count,product_count,"
            + " creative_count,keyword_count,requested_by,reason,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,0,?,?,now())",
        tenant,
        Ids.next(),
        source.id(),
        targetCampaignId,
        targetShopCode,
        targetListingId,
        mode,
        groupCount,
        productCount,
        creativeCount,
        actor == null ? tenant : actor,
        text(request.reason()));

    return new AdCampaignCopyResult(
        source.id(),
        targetCampaignId,
        targetName,
        "PAUSED",
        targetShopCode,
        mode,
        targetListingId,
        groupCount,
        productCount,
        creativeCount,
        // 🔴 恒为 0：平台侧还没建这个活动，关键词 id 无从分配。
        0,
        true,
        "🔴 结构副本**真的落库**了：目标活动 + "
            + groupCount
            + " 个广告组 + "
            + productCount
            + " 个投放商品 + "
            + creativeCount
            + " 个创意。🔴 但**平台侧还没有这份结构**：平台回写队列未交付，"
            + "目标活动标「待回写平台」且状态为暂停（复制出来不投放），"
            + "平台侧 id 落成 LOCAL-COPY- 前缀的本地标记。🔴 关键词**没有**复制："
            + "平台侧还没建这个活动，关键词 id 无从分配 —— 往「平台原文」表里塞本地编的 id "
            + "比少复制一段严重得多。🔴 复制的是**结构**，不是钱："
            + "目标活动的预算与投放期没有一起复制，预算要用「调预算」单独设。");
  }

  // ==================================================================
  // 结构登记（真实写入口）
  // ==================================================================

  /**
   * 登记一个活动的结构（广告组 / 投放商品 / 创意）。
   *
   * <p>🔴 平台广告 API 同步器**尚未交付**，但结构必须有真的落点 ——
   * 这个端点就是那个落点，**绝不用空数组或硬编码常量假装有广告组**。
   *
   * <p>🔴 语义是**替换**：一次登记代表「这是平台返回的该活动当前完整结构」，
   * 载荷里没出现的组内商品 / 创意 / 组本身会被删掉。
   * 增量追加会让「平台已经下架了这个商品」永远删不掉。
   */
  public AdCampaignStructureImportResult importStructure(
      String id, AdCampaignStructureImportRequest request) {
    AdCampaignRow campaign = requireCampaignRow(id);
    UUID tenant = currentTenant();
    UUID campaignId = campaign.id();
    OffsetDateTime dataAsOf = request.dataAsOf();

    // 店铺 → 投放商品的完整性：投放的商品必须属于活动所在店铺
    UUID shopId = shopId(campaign.shopCode());
    if (shopId == null) {
      throw notFound("活动所在店铺");
    }

    Set<String> keptGroups = new LinkedHashSet<>();
    int groupCount = 0;
    int productCount = 0;
    int creativeCount = 0;

    for (AdCampaignGroupImport groupImport : request.groups()) {
      String externalGroupId = text(groupImport.externalGroupId());
      if (externalGroupId.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "平台侧广告组 id 不能为空");
      }
      String status = text(groupImport.status()).toUpperCase(Locale.ROOT);
      if (status.isEmpty()) {
        status = "UNKNOWN";
      }
      if (!STATUSES.contains(status)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "广告组状态不合法：" + groupImport.status());
      }
      if (groupImport.defaultBid() != null && groupImport.defaultBid().signum() < 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "默认竞价不能为负");
      }
      if (groupImport.dailyBudget() != null && groupImport.dailyBudget().signum() < 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "组级日预算不能为负");
      }

      UUID groupId = Ids.next();
      UUID actualGroupId =
          sql.fetchOne(
                  "insert into ad_campaign_group (tenant_id,id,campaign_id,external_group_id,group_name,"
                      + " status,default_bid,bid_currency,daily_budget,targeting_type,data_as_of,"
                      + " created_at,updated_at)"
                      + " values (?,?,?,?,?,?,?,?,?,?,?::timestamptz,now(),now())"
                      + " on conflict (tenant_id,campaign_id,external_group_id) do update"
                      + " set group_name = excluded.group_name, status = excluded.status,"
                      + " default_bid = excluded.default_bid, bid_currency = excluded.bid_currency,"
                      + " daily_budget = excluded.daily_budget, targeting_type = excluded.targeting_type,"
                      + " data_as_of = excluded.data_as_of, updated_at = now()"
                      + " returning id",
                  tenant,
                  groupId,
                  campaignId,
                  externalGroupId,
                  text(groupImport.groupName()),
                  status,
                  groupImport.defaultBid(),
                  text(groupImport.bidCurrency()),
                  groupImport.dailyBudget(),
                  text(groupImport.targetingType()),
                  dataAsOf)
              .get("id", UUID.class);
      keptGroups.add(externalGroupId);
      groupCount++;

      // ---- 投放商品（替换语义）----
      Set<UUID> keptProducts = new LinkedHashSet<>();
      if (groupImport.products() != null) {
        for (AdCampaignProductImport productImport : groupImport.products()) {
          Record listing =
              sql.fetchOne(
                  "select id, shop_id, external_item_id, title from listing where tenant_id = "
                      + TENANT
                      + " and id = ?",
                  productImport.listingId());
          if (listing == null) {
            throw notFound("投放商品对应的 listing");
          }
          if (!listing.get("shop_id", UUID.class).equals(shopId)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "投放商品必须属于活动所在店铺（listing 属于店铺，跨店铺投放不成立）");
          }
          UUID productId = Ids.next();
          sql.execute(
              "insert into ad_campaign_group_product (tenant_id,id,campaign_id,group_id,listing_id,"
                  + " external_item_id,title,created_at,updated_at)"
                  + " values (?,?,?,?,?,?,?,now(),now())"
                  + " on conflict (tenant_id,group_id,listing_id) do update"
                  + " set external_item_id = excluded.external_item_id, title = excluded.title,"
                  + " updated_at = now()",
              tenant,
              productId,
              campaignId,
              actualGroupId,
              productImport.listingId(),
              // 🔴 没给就**从 listing 读真实值**，不编
              blankToNull(productImport.externalItemId()) == null
                  ? listing.get("external_item_id", String.class)
                  : text(productImport.externalItemId()),
              blankToNull(productImport.title()) == null
                  ? listing.get("title", String.class)
                  : text(productImport.title()));
          keptProducts.add(productImport.listingId());
          productCount++;
        }
      }
      for (Record stale :
          sql.fetch(
              "select id, listing_id from ad_campaign_group_product where tenant_id = "
                  + TENANT
                  + " and group_id = ?",
              actualGroupId)) {
        if (!keptProducts.contains(stale.get("listing_id", UUID.class))) {
          sql.execute(
              "delete from ad_campaign_group_product where tenant_id = " + TENANT + " and id = ?",
              stale.get("id", UUID.class));
        }
      }

      // ---- 组内创意（替换语义）----
      Set<String> keptCreatives = new LinkedHashSet<>();
      if (groupImport.creatives() != null) {
        for (AdCampaignCreativeImport creativeImport : groupImport.creatives()) {
          creativeCount += upsertCreative(tenant, campaignId, actualGroupId, creativeImport, dataAsOf);
          keptCreatives.add(text(creativeImport.externalCreativeId()));
        }
      }
      for (Record stale :
          sql.fetch(
              "select id, external_creative_id from ad_campaign_creative where tenant_id = "
                  + TENANT
                  + " and group_id = ?",
              actualGroupId)) {
        if (!keptCreatives.contains(stale.get("external_creative_id", String.class))) {
          sql.execute(
              "delete from ad_campaign_creative where tenant_id = " + TENANT + " and id = ?",
              stale.get("id", UUID.class));
        }
      }
    }

    // ---- 活动级创意（不挂在任何组下的）----
    if (request.creatives() != null) {
      for (AdCampaignCreativeImport creativeImport : request.creatives()) {
        creativeCount += upsertCreative(tenant, campaignId, null, creativeImport, dataAsOf);
      }
    }
    for (Record stale :
        sql.fetch(
            "select id, external_creative_id from ad_campaign_creative where tenant_id = "
                + TENANT
                + " and campaign_id = ? and group_id is null",
            campaignId)) {
      boolean kept = false;
      if (request.creatives() != null) {
        for (AdCampaignCreativeImport creativeImport : request.creatives()) {
          if (text(creativeImport.externalCreativeId())
              .equals(stale.get("external_creative_id", String.class))) {
            kept = true;
            break;
          }
        }
      }
      if (!kept) {
        sql.execute(
            "delete from ad_campaign_creative where tenant_id = " + TENANT + " and id = ?",
            stale.get("id", UUID.class));
      }
    }

    // ---- 删掉载荷里没出现的广告组（替换语义）----
    int removed = 0;
    for (Record stale :
        sql.fetch(
            "select id, external_group_id from ad_campaign_group where tenant_id = "
                + TENANT
                + " and campaign_id = ?",
            campaignId)) {
      if (!keptGroups.contains(stale.get("external_group_id", String.class))) {
        sql.execute(
            "delete from ad_campaign_group where tenant_id = " + TENANT + " and id = ?",
            stale.get("id", UUID.class));
        removed++;
      }
    }

    return new AdCampaignStructureImportResult(
        groupCount,
        productCount,
        creativeCount,
        removed,
        dataAsOf,
        "已登记 " + groupCount + " 个广告组、" + productCount + " 个投放商品、" + creativeCount
            + " 个创意；结构数据截至 " + dataAsOf + "。"
            + "🔴 语义是**替换**：载荷里没出现的广告组 / 组内商品 / 组内创意已被删掉（"
            + removed + " 个广告组）。"
            + "🔴 平台广告 API 同步器尚未交付 —— 这个入口是结构的真实落点，"
            + "结构数据的新鲜度取决于谁来调它。");
  }

  /** 写入一条创意（幂等 upsert），返回 1。 */
  private int upsertCreative(
      UUID tenant,
      UUID campaignId,
      UUID groupId,
      AdCampaignCreativeImport creativeImport,
      OffsetDateTime dataAsOf) {
    String externalCreativeId = text(creativeImport.externalCreativeId());
    if (externalCreativeId.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "平台侧创意 id 不能为空");
    }
    String creativeType = text(creativeImport.creativeType()).toUpperCase(Locale.ROOT);
    if (!CREATIVE_TYPES.contains(creativeType)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "创意类型只能是 IMAGE / VIDEO / CAROUSEL / TEXT");
    }
    String status = text(creativeImport.status()).toUpperCase(Locale.ROOT);
    if (status.isEmpty()) {
      status = "UNKNOWN";
    }
    if (!STATUSES.contains(status)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "创意状态不合法：" + creativeImport.status());
    }
    if (creativeImport.durationSeconds() != null && creativeImport.durationSeconds() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "视频时长不能为负");
    }
    sql.execute(
        "insert into ad_campaign_creative (tenant_id,id,campaign_id,group_id,external_creative_id,"
            + " creative_name,creative_type,asset_url,duration_seconds,status,data_as_of,"
            + " created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?::timestamptz,now(),now())"
            + " on conflict (tenant_id,campaign_id,external_creative_id) do update"
            + " set group_id = excluded.group_id, creative_name = excluded.creative_name,"
            + " creative_type = excluded.creative_type, asset_url = excluded.asset_url,"
            + " duration_seconds = excluded.duration_seconds, status = excluded.status,"
            + " data_as_of = excluded.data_as_of, updated_at = now()",
        tenant,
        Ids.next(),
        campaignId,
        groupId,
        externalCreativeId,
        text(creativeImport.creativeName()),
        creativeType,
        text(creativeImport.assetUrl()),
        creativeImport.durationSeconds(),
        status,
        dataAsOf);
    return 1;
  }

  // ==================================================================
  // 公共校验
  // ==================================================================

  /** 活动必须属于当前租户（跨租户与不存在返回逐字相同的 404）。 */
  private UUID requireCampaign(String id) {
    return requireCampaignRow(id).id();
  }

  private AdCampaignRow requireCampaignRow(String id) {
    return campaigns.campaign(parseId(id).toString()).campaign();
  }

  /** 店铺 id（按租户 + 编码）。 */
  private UUID shopId(String shopCode) {
    return one(UUID.class, "select id from shop where tenant_id = " + TENANT + " and code = ?", shopCode);
  }

  /** 关键词条数（读上游 14.1.3.1 的 ad_keyword）。 */
  private long keywordCount(AdCampaignRow campaign) {
    UUID shopId = shopId(campaign.shopCode());
    if (shopId == null) {
      return 0L;
    }
    Long value =
        one(
            Long.class,
            "select count(*) from ad_keyword where tenant_id = "
                + TENANT
                + " and platform_id = ? and shop_id = ? and external_campaign_id = ?",
            campaign.platformId(),
            shopId,
            campaign.externalCampaignId());
    return value == null ? 0L : value;
  }
}
