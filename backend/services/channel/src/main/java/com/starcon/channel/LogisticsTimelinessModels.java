package com.starcon.channel;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.6.2.1 时效分析 的传输模型。
 *
 * <p>蓝图：「分析时效表现：各渠道各国家的<b>妥投率</b>、<b>超时率</b>、<b>平均时效波动</b>、
 * <b>旺季变化</b>，用于渠道评分与时效承诺设置。」输出「时效报表 + 渠道评分 + 异常集中分析」。
 *
 * <p>🔴 载荷一律用具名 {@code record} 而不是 {@code Map<String,Object>}：Map 的值类型会被
 * springdoc 推成 object，OpenAPI codegen 出来是空对象 {@code {}}（已踩过）。
 *
 * <p>🔴 <b>所有比率与时长都是 {@link BigDecimal}</b>：妥投率 / 超时率 / 达成率按比例
 * （scale 4，HALF_UP 钉死），平均时效与偏差按<b>小时</b>。本页一个 double 都没有 ——
 * 0.1 + 0.2 那类误差一旦进报表，「超时率 33.33%」和「超时率 33.34%」会随引擎而变。
 *
 * <p>⚠️ 名字带 {@code Timeliness} 前缀不是啰嗦，是**必需**：trade 服务已有
 * {@code fulfillment/timeliness} 形状（9.5.1.1 订单时效），warehouse / channel 也各有
 * 同名字段。前端契约是**跨服务合并**的：同名不同结构会变成字段级覆盖，消费端拿到
 * 「凭空多出的字段」而编译期不报错。契约 codegen 会直接以「跨服务同名 DTO」中止。
 */
public final class LogisticsTimelinessModels {
  private LogisticsTimelinessModels() {}

  // ================================================================ 能力声明

  /**
   * 一个能力维度。
   *
   * <p>{@code available=false} 时必须同时给出 {@code ownerModule}（缺口归属模块）与
   * {@code impact}（缺了会怎样）。**不用空列表或 0 冒充「这一项没有数据」**。
   */
  public record TimelinessCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  public record LogisticsTimelinessCapabilities(
      List<TimelinessCapabilityDimension> dimensions,
      List<String> timelinessStatuses,
      List<String> scoreGrades,
      int defaultWindowDays,
      int defaultMinSampleThreshold,
      String windowTimeZone,
      String boundaryNote,
      String highlight,
      String note) {}

  // ================================================================ 统计窗口

  /**
   * 统计窗口（左闭右开 {@code [from, to)}）。
   *
   * <p>{@code timeZone} 写死 {@code UTC}：月份分桶用 UTC 是为了让「旺季」这件事
   * 在任何一台数据库机器上都是同一个答案（用会话时区分桶的话，换台机器换时区，
   * 报表上个月份会整个错位）。
   */
  public record TimelinessWindow(
      OffsetDateTime from, OffsetDateTime to, int days, String timeZone, String note) {}

  // ================================================================ 交叉报表（渠道 × 国家）

  /**
   * 一格 = <b>一个渠道 × 一个国家</b>。
   *
   * <p>🔴 这里没有「总体」那一行：蓝图加粗句说的就是它。总体平均会掩盖某些渠道在
   * 某些国家的系统性延误 —— 所以本模型里根本没有承载总体行的字段。
   */
  public record TimelinessMatrixRow(
      UUID channelId,
      String channelCode,
      String channelName,
      String countryCode,
      int shippedCount,
      int deliveredCount,
      int comparableCount,
      int onTimeCount,
      int overdueCount,
      int noPromiseCount,
      int pendingCount,
      int returnedCount,
      /** 妥投率 = 签投数 / 发出数（null = 没有发出过一票）。 */
      BigDecimal deliveryRate,
      /** 达成率 = 准点数 / **可比数**（null = 没有可比的签收票）。 */
      BigDecimal onTimeRate,
      /** 超时率 = 超时数 / **可比数**（null = 没有可比的签收票）。 */
      BigDecimal overdueRate,
      /** 平均实际时效（小时）。 */
      BigDecimal avgTransitHours,
      /** 平均时效偏差（小时，带符号：正 = 晚于承诺，负 = 早于承诺）。 */
      BigDecimal avgDeviationHours,
      /** 实际时效的样本标准差（小时）= 时效波动。 */
      BigDecimal stddevTransitHours,
      /** P90 实际时效（小时）= 长尾那一截。 */
      BigDecimal p90TransitHours,
      /** 最大超时小时数。 */
      BigDecimal maxOverdueHours,
      /** 0~100 的时效评分（null = 不可比，不是 0 分）。 */
      BigDecimal score,
      /** 评分等级（EXCELLENT / GOOD / FAIR / POOR / INSUFFICIENT）。 */
      String scoreGrade,
      /** 样本是否够（不够则 score 为 null）。 */
      boolean sampleSufficient) {}

  /** 交叉报表分页（真分页 + 真筛选）。 */
  public record TimelinessMatrixPage(
      List<TimelinessMatrixRow> rows,
      int page,
      int size,
      long totalElements,
      int totalPages,
      TimelinessWindow window,
      TimelinessTotals totals,
      String note) {}

  /**
   * 全量合计（<b>不受分页影响</b>）。
   *
   * <p>这是「总体」口径唯一的合法位置：它只能出现在合计里，<b>不能</b>被当成
   * 某个渠道或某个国家的表现来用。
   */
  public record TimelinessTotals(
      int shippedCount,
      int deliveredCount,
      int comparableCount,
      int onTimeCount,
      int overdueCount,
      int noPromiseCount,
      int pendingCount,
      int returnedCount,
      BigDecimal deliveryRate,
      BigDecimal onTimeRate,
      BigDecimal overdueRate,
      BigDecimal avgTransitHours,
      BigDecimal avgDeviationHours,
      BigDecimal stddevTransitHours,
      BigDecimal p90TransitHours,
      BigDecimal maxOverdueHours) {}

  // ================================================================ 旺季与趋势

  /** 一个月度分桶（UTC 月）。 */
  public record TimelinessMonthlyRow(
      String monthKey,
      int shippedCount,
      int deliveredCount,
      int comparableCount,
      int onTimeCount,
      int overdueCount,
      int noPromiseCount,
      int pendingCount,
      int returnedCount,
      BigDecimal deliveryRate,
      BigDecimal onTimeRate,
      BigDecimal overdueRate,
      BigDecimal avgTransitHours,
      BigDecimal avgDeviationHours,
      BigDecimal stddevTransitHours,
      BigDecimal p90TransitHours,
      /** 该月相对基线的时效偏差（小时，正 = 比基线慢）。 */
      BigDecimal vsBaselineTransitHours,
      /** 是否为「旺季」：该月超时率高于窗口基线。 */
      boolean peakMonth) {}

  /** 月度报表：行 + 基线（基线是窗口内加权平均，不是各月平均值的平均）。 */
  public record TimelinessMonthlyReport(
      List<TimelinessMonthlyRow> rows,
      TimelinessWindow window,
      TimelinessTotals baseline,
      int peakMonthCount,
      String note) {}

  // ================================================================ 异常集中分析

  /** 一格在超时总量里的贡献（帕累托）。 */
  public record TimelinessAnomalyCell(
      int rank,
      UUID channelId,
      String channelCode,
      String channelName,
      String countryCode,
      int overdueCount,
      int deliveredCount,
      BigDecimal overdueRate,
      BigDecimal avgTransitHours,
      BigDecimal avgDeviationHours,
      BigDecimal maxOverdueHours,
      /** 这一格占全窗口超时总量的比例（0~1，scale 4）。 */
      BigDecimal contributionRate,
      /** 累计贡献比例（0~1，scale 4）= 帕累托曲线。 */
      BigDecimal cumulativeRate,
      /** 是否落在「贡献了大部分超时」的那批格子里。 */
      boolean inPareto) {}

  /** 渠道维度 / 国家维度的集中度。 */
  public record TimelinessAnomalyGroup(
      String key, String label, int overdueCount, BigDecimal share) {}

  public record TimelinessAnomalyReport(
      List<TimelinessAnomalyCell> cells,
      List<TimelinessAnomalyGroup> byChannel,
      List<TimelinessAnomalyGroup> byCountry,
      int totalOverdue,
      BigDecimal topOneShare,
      BigDecimal topThreeShare,
      BigDecimal paretoThreshold,
      int paretoCellCount,
      int paretoOverdueCount,
      TimelinessWindow window,
      String note) {}

  // ================================================================ 渠道时效评分

  /** 实时算出来的渠道时效评分（一格 = 渠道 × 国家）。 */
  public record TimelinessScoreRow(
      UUID channelId,
      String channelCode,
      String channelName,
      String countryCode,
      int shippedCount,
      int deliveredCount,
      int comparableCount,
      int onTimeCount,
      int overdueCount,
      BigDecimal deliveryRate,
      BigDecimal onTimeRate,
      BigDecimal overdueRate,
      BigDecimal avgTransitHours,
      BigDecimal avgDeviationHours,
      BigDecimal score,
      String scoreGrade,
      boolean sampleSufficient,
      int minSampleThreshold,
      /** 该格最近一次**落库**快照的计算时刻（null = 还没落过库）。 */
      OffsetDateTime snapshotComputedAt) {}

  public record TimelinessScorePage(
      List<TimelinessScoreRow> rows,
      int page,
      int size,
      long totalElements,
      int totalPages,
      TimelinessWindow window,
      int snapshotCount,
      String note) {}

  /** 落库的评分快照（台账行）。 */
  public record TimelinessScoreSnapshotRow(
      UUID id,
      UUID channelId,
      String channelCode,
      String channelName,
      String countryCode,
      OffsetDateTime windowStart,
      OffsetDateTime windowEnd,
      int windowDays,
      int shippedCount,
      int deliveredCount,
      int comparableCount,
      int onTimeCount,
      int overdueCount,
      int noPromiseCount,
      int pendingCount,
      int returnedCount,
      BigDecimal deliveryRate,
      BigDecimal onTimeRate,
      BigDecimal overdueRate,
      BigDecimal avgTransitHours,
      BigDecimal avgDeviationHours,
      BigDecimal stddevTransitHours,
      BigDecimal p90TransitHours,
      BigDecimal maxOverdueHours,
      BigDecimal score,
      String scoreGrade,
      boolean sampleSufficient,
      int minSampleThreshold,
      OffsetDateTime computedAt) {}

  public record TimelinessScoreSnapshotPage(
      List<TimelinessScoreSnapshotRow> rows,
      int page,
      int size,
      long totalElements,
      int totalPages) {}

  /** 重算并落库的结果。 */
  public record TimelinessScoreSnapshotResult(
      TimelinessWindow window,
      int computed,
      int written,
      int insufficient,
      int removed,
      int minSampleThreshold,
      List<String> notes) {}

  // ================================================================ 明细

  /** 逐票时效明细。 */
  public record TimelinessSubjectRow(
      UUID id,
      String masterWaybillNo,
      String carrierCode,
      UUID channelId,
      String channelCode,
      String channelName,
      String countryCode,
      String serviceLevel,
      OffsetDateTime shippedAt,
      OffsetDateTime deliveredAt,
      OffsetDateTime promisedEtaAt,
      /** 实际时效（小时）；未签收为 null。 */
      BigDecimal transitHours,
      /** 时效偏差（小时，带符号）；未签收或没有承诺为 null。 */
      BigDecimal deviationHours,
      /** ON_TIME / OVERDUE / NO_PROMISE / IN_TRANSIT。 */
      String timelinessStatus,
      String timelinessStatusLabel) {}

  public record TimelinessSubjectPage(
      List<TimelinessSubjectRow> rows,
      int page,
      int size,
      long totalElements,
      int totalPages,
      TimelinessWindow window,
      String note) {}

  // ================================================================ 输入

  /**
   * 重算并落库的请求。
   *
   * <p>{@code from} / {@code to} 是 {@code yyyy-MM-dd} 日期（UTC 自然日，含首含尾）。
   */
  public record TimelinessSnapshotRequest(
      @jakarta.validation.constraints.Pattern(
              regexp = "^$|^\\d{4}-\\d{2}-\\d{2}$",
              message = "from 必须是 yyyy-MM-dd 格式的日期")
          String from,
      @jakarta.validation.constraints.Pattern(
              regexp = "^$|^\\d{4}-\\d{2}-\\d{2}$",
              message = "to 必须是 yyyy-MM-dd 格式的日期")
          String to,
      @jakarta.validation.constraints.Min(value = 1, message = "最小样本量至少为 1")
          Integer minSampleThreshold) {}

  // ================================================================ 概览

  /**
   * 全量概览（<b>不受筛选与分页影响</b>）。
   *
   * <p>{@code unattributedShippedCount}（没有归到任何渠道）与
   * {@code unclassifiedShippedCount}（没有国家）如实单列 —— 它们不进交叉矩阵，
   * 但也不能从报告里消失：**矩阵只覆盖有渠道有国家的票，剩下那部分要看得见**。
   */
  public record LogisticsTimelinessOverview(
      TimelinessWindow window,
      TimelinessTotals totals,
      int channelCount,
      int countryCount,
      int cellCount,
      int unattributedShippedCount,
      int unclassifiedShippedCount,
      int activeWindowSubjectCount,
      String worstCellChannelCode,
      String worstCellCountryCode,
      BigDecimal worstCellOverdueRate,
      String note,
      String upstreamNote,
      String downstreamNote) {}
}
