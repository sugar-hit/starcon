package com.starcon.reporting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 13.1.1.2 多店对比的**出入参模型**。
 *
 * <p>⚠️ **「算不出来」与「等于 0」必须分开**（与 13.1.1.1 同一纪律）：
 * 分母为 0、事实不存在、指标未登记，三种情况一律返回 {@code null} / {@code NO_DATA}，
 * **不用 0 冒充**。销售额 0 与「这一格没算出来」在页面上长得一样，含义却相反。
 *
 * <p>⚠️ **不跨币种相加**：涉币指标按币种分别报。只有调用方**显式**指定本位币、
 * 且 {@code reporting_currency_rate_snapshot} 里有生效日不晚于窗口末的汇率时，
 * 才额外给出本位币合计，并把**汇率与时点**一起返回。
 */
public final class ReportingStoreComparisonModels {

  private ReportingStoreComparisonModels() {}

  // ==================================================================
  // 指标口径目录
  // ==================================================================

  /** 一个指标的口径定义（口径唯一的展示形态）。 */
  public record MetricDefinitionView(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      String numeratorMetricCode,
      String denominatorMetricCode,
      String baseMetricCode,
      boolean currencyAware,
      String description,
      String ownerModule) {}

  // ==================================================================
  // 对比矩阵
  // ==================================================================

  /** 一个指标在某个店铺、某个币种下的取值。 */
  public record StoreMetricValue(
      String metricCode,
      String currency,
      BigDecimal value,
      /** 折算到本位币后的值（给了本位币且有汇率时才有值）。 */
      BigDecimal baseValue,
      /** 折算用的本位币（没折算时为空串）。 */
      String baseCurrency,
      /** 该值在本次对比里的排名（1 = 最靠前）。null = 该指标不可比，没有排名。 */
      Integer rank,
      /** 该值的可用性：AVAILABLE / NO_DATA / NOT_COMPARABLE。 */
      String availability,
      /** 不可比或没有数据的原因（availability != AVAILABLE 时必填）。 */
      String availabilityNote) {}

  /** 对比矩阵里的一行 = 一个店铺。 */
  public record StoreComparisonRow(
      String shopCode,
      String shopName,
      String platformCode,
      String platformName,
      /** 该店铺在这个窗口里出现过的币种。 */
      List<String> currencies,
      /** 该店铺的指标取值（按指标码分组，一个指标多个币种就是多条）。 */
      List<StoreMetricValue> metrics,
      /** 规模排名（按平台侧成交金额，1 = 最大）。null = 没有可比数据。 */
      Integer scaleRank,
      /** 增长排名（按成交金额环比增长率，1 = 增长最快）。null = 没有可比数据。 */
      Integer growthRank,
      /** 分类：GROWTH（增长店铺）/ LAGGARD（掉队店铺）/ STABLE（其余）/ UNCLASSIFIED（不可比）。 */
      String classification,
      /** 分类依据（为什么判成这一类）。 */
      String classificationNote,
      /** 差异归因提示（这一行相对同平台中位数差在哪）。 */
      List<String> attributionHints) {}

  /** 按平台分组的一组店铺。 */
  public record PlatformGroup(
      String platformCode,
      String platformName,
      List<StoreComparisonRow> stores,
      /** 该组内的店铺数。 */
      int storeCount,
      /** 该组的口径说明（跨平台对比时必须标注）。 */
      String caliberNote) {}

  /** 排名榜（一个指标一张）。 */
  public record MetricRanking(
      String metricCode,
      String displayName,
      String unit,
      /** 排名条目（按值倒序；没有可比值的店铺不进榜）。 */
      List<RankingEntry> entries,
      /** 该指标为什么不能排（全部不可比时给出原因），否则空串。 */
      String note) {}

  /** 排名条目。 */
  public record RankingEntry(
      int rank, String shopCode, String shopName, String platformCode, String currency, BigDecimal value) {}

  /** 找出的一批店铺（掉队店铺 / 增长店铺）。 */
  public record StoreFlag(String shopCode, String shopName, String platformCode, String reason) {}

  /** 一次对比的完整结果。 */
  public record ComparisonResponse(
      LocalDate from,
      LocalDate to,
      /** 上一等长窗口（增长口径的比对基准）。 */
      LocalDate previousFrom,
      LocalDate previousTo,
      /** 对比矩阵（按平台分组后拍平，按成交金额倒序）。 */
      List<StoreComparisonRow> rows,
      /** 按平台分组。 */
      List<PlatformGroup> platformGroups,
      /** 各指标的排名。 */
      List<MetricRanking> rankings,
      /** 掉队店铺。 */
      List<StoreFlag> laggards,
      /** 增长店铺。 */
      List<StoreFlag> growthStores,
      /** 本次对比用到的指标口径（页面上原样显示）。 */
      List<MetricDefinitionView> metricDefinitions,
      /** 跨平台口径差异说明（蓝图标名「跨平台对比要处理口径差异」）。 */
      List<String> caliberNotes,
      /** 数据更新时间（最近一次投影落库时刻）—— 卷 13 卷首「数据延迟要显示」。 */
      OffsetDateTime dataUpdatedAt,
      /** 数据更新时间的口径说明。 */
      String dataUpdatedNote,
      /** 本次对比整体状态：PROJECTED / NOT_PROJECTED / PARTIAL。 */
      String status,
      /** 状态说明（为什么是这个状态）。 */
      String statusNote,
      /** 本位币（给了且折算成功时才有值）。 */
      String baseCurrency,
      /** 本位币折算用的汇率与时点（每种币种一条；「今天的汇率变了不能让上月的报表变」）。 */
      List<CurrencyRate> baseRates,
      /** 空态原因：NO_STORES / NOT_PROJECTED / null。 */
      String emptyReason,
      /** 空态说明。 */
      String emptyNote) {}

  /** 一个币种对本位币的汇率与时点。 */
  public record CurrencyRate(String currency, String baseCurrency, BigDecimal rate, OffsetDateTime asOf) {}

  // ==================================================================
  // 店铺清单
  // ==================================================================

  /** 一个店铺（用于筛选器与平台分组）。 */
  public record StoreSummary(
      String shopCode,
      String shopName,
      String platformCode,
      String platformName,
      /** 该店铺在本次窗口里是否有事实。 */
      boolean hasFacts,
      /** 该店铺最近一次投影时刻。 */
      OffsetDateTime updatedAt) {}

  /** 店铺清单 + 平台清单。 */
  public record StoreListResponse(List<StoreSummary> stores, List<PlatformOption> platforms) {}

  /** 一个平台。 */
  public record PlatformOption(String platformCode, String platformName, int storeCount) {}

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明。
   *
   * @param upstreamConnected 上游是否真接通（本模块的三个上游全部已交付）
   * @param comparisonAvailable 对比矩阵是否可用
   * @param dataUpdatedAtVisible 是否常驻显示数据更新时间
   * @param upstreamModules 本服务**真的读过**的上游模块
   * @param notDelivered **确实拿不到**的部分（每条都写清原因与影响）
   * @param rules 判定规则（增长店铺 / 掉队店铺的阈值与依据）
   */
  public record ComparisonCapabilities(
      boolean upstreamConnected,
      boolean comparisonAvailable,
      boolean dataUpdatedAtVisible,
      List<String> upstreamModules,
      List<String> notDelivered,
      List<RuleNote> rules) {}

  /** 一条判定规则。 */
  public record RuleNote(String ruleCode, String description) {}

  // ==================================================================
  // 刷新
  // ==================================================================

  /** 一路刷新结果。 */
  public record StoreRefreshResult(
      String source,
      /** 拉到的上游行数。 */
      int fetched,
      /** 落库的事实行数。 */
      int projected,
      /** 结论：SUCCEEDED / FAILED / TRUNCATED。 */
      String status,
      /** 说明（失败原因 / 截断原因）。 */
      String note) {}
}
