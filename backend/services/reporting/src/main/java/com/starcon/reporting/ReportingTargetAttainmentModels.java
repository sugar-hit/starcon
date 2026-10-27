package com.starcon.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 13.1.2.2 达成追踪的**出入参模型**（蓝图「输出」句：达成率表 + 预测 + 预警）。
 *
 * <p>🔴 **「算不出来」与「等于 0」必须分开**（与 13.1.1.1 / 13.1.1.2 / 13.1.2.1 同纪律）：
 * 凡是可以「算不出来」的数值一律用 {@code null} 表示，**不用 0 冒充**。
 * <b>0% 达成率是有业务含义的断言</b>（确实一件都没做成），<b>「不可得」不是</b>
 * （我根本拿不到这个维度的实际值）—— 把后者写成前者，管理者会去追一个不存在的问题。
 *
 * <p>🔴 **多币种绝不相加**：目标带币种，实际值按币种取数，排名不跨币种。
 * 一个 USD 目标和一个 EUR 目标的大小本来就不可比。
 *
 * <p>⚠️ **名字不与既有类型重名**（codegen 的同名闸会中止契约生成）：
 * 13.1.1.1 已有 {@code MetricCard} / {@code CurrencyValue}，
 * 13.1.2.1 已有 {@code Target*} 前缀的一整套，
 * 13.2.2.2 已有 {@code ProductSalesResponse} 等。
 * 本文件统一用 {@code Attainment*} 前缀。
 */
public final class ReportingTargetAttainmentModels {

  private ReportingTargetAttainmentModels() {}

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明。
   *
   * @param upstreamConnected 上游是否真接通（目标表与实际值事实表都在本服务库里）
   * @param boardAvailable 达成率表是否可用
   * @param upstreamModules 本服务**真的读过**的上游模块
   * @param notDelivered **确实拿不到**的部分（每条都写清原因与影响）
   * @param rules 判定规则与阈值（页面上照着显示，用户看得懂「为什么这条是 BEHIND」）
   */
  public record AttainmentCapabilities(
      boolean upstreamConnected,
      boolean boardAvailable,
      List<String> upstreamModules,
      List<String> notDelivered,
      List<AttainmentRuleNote> rules) {}

  /** 一条判定规则 / 口径说明。 */
  public record AttainmentRuleNote(String ruleCode, String description) {}

  // ==================================================================
  // 达成率表
  // ==================================================================

  /**
   * 一个分解项（一个月 / 一周）的达成情况。
   *
   * @param bucketStart 分解项起
   * @param bucketEnd 分解项止
   * @param granularity 分解粒度（MONTHLY / WEEKLY）
   * @param currency 币种（**与目标同币种** ⇒ 不会跨币种）
   * @param targetValue 该分解项的目标金额
   * @param currentValue 该分解项区间内的实际值（**{@code actualStatus == AVAILABLE} 时才有值**）
   * @param attainmentRate 该分解项的达成率（**分母不是正数 ⇒ null**）
   * @param elapsedRatio 该分解项的时间进度（0..1）
   * @param forecastValue 按当前速度推算的该分解项期末值（**时间进度为 0 ⇒ null**）
   * @param forecastRate 预测达成率
   * @param actualStatus 实际值状态（AVAILABLE / NO_DATA / NOT_AVAILABLE）
   * @param status 达成状态（见 {@code AttainmentCapabilities} 的规则说明）
   * @param note 这个分解项的状态说明
   */
  public record AttainmentBucket(
      LocalDate bucketStart,
      LocalDate bucketEnd,
      String granularity,
      String currency,
      BigDecimal targetValue,
      @Schema(nullable = true) BigDecimal currentValue,
      @Schema(nullable = true) BigDecimal attainmentRate,
      BigDecimal elapsedRatio,
      @Schema(nullable = true) BigDecimal forecastValue,
      @Schema(nullable = true) BigDecimal forecastRate,
      String actualStatus,
      String status,
      String note) {}

  /**
   * 一条达成率行（一个目标在当前评估日的达成情况）。
   *
   * @param targetId 目标 id
   * @param scopeType 归属维度（DEPARTMENT / MEMBER / SHOP / PRODUCT）
   * @param ownerId 归属对象标识
   * @param ownerCode 归属对象业务编码（**快照**）
   * @param ownerName 归属对象显示名（**快照**）
   * @param metricCode 引用的口径码
   * @param metricDisplayName 口径表里登记的显示名
   * @param unit 单位（CURRENCY / COUNT / QUANTITY / PERCENT / RATIO）
   * @param currency 币种
   * @param periodStart 周期起
   * @param periodEnd 周期止
   * @param targetValue 周期内目标总额
   * @param currentValue 当前值（**{@code actualStatus == AVAILABLE} 时才有值**；否则 null，**不是 0**）
   * @param attainmentRate 达成率（**分母不是正数 ⇒ null**）
   * @param forecastValue 预测期末值（按当前速度推算；**时间进度为 0 ⇒ null**）
   * @param forecastRate 预测达成率
   * @param elapsedRatio 时间进度（0..1）
   * @param remainingDays 距离周期结束还有几个日历日（周期已过完 ⇒ 0）
   * @param actualStatus 实际值状态
   * @param status 达成状态
   * @param statusNote 这个状态的理由（页面上照着显示）
   * @param underAchieved **未达标项高亮**标记（true ⇒ 页面高亮这一行）
   * @param rank 该归属对象在本排名分组里的位次（1 起）
   * @param rankTotal 本排名分组里的目标总数
   * @param rankGroup 排名分组键
   * @param buckets 分解计划的逐项达成情况（真数据，来自 13.1.2.1 的分解计划）
   */
  public record AttainmentRow(
      UUID targetId,
      String scopeType,
      String ownerId,
      String ownerCode,
      String ownerName,
      String metricCode,
      String metricDisplayName,
      String unit,
      String currency,
      LocalDate periodStart,
      LocalDate periodEnd,
      BigDecimal targetValue,
      @Schema(nullable = true) BigDecimal currentValue,
      @Schema(nullable = true) BigDecimal attainmentRate,
      @Schema(nullable = true) BigDecimal forecastValue,
      @Schema(nullable = true) BigDecimal forecastRate,
      BigDecimal elapsedRatio,
      int remainingDays,
      String actualStatus,
      String status,
      String statusNote,
      boolean underAchieved,
      int rank,
      int rankTotal,
      String rankGroup,
      List<AttainmentBucket> buckets) {}

  /**
   * 一个排名分组（**同归属维度 + 同口径 + 同币种 + 同周期**才可比）。
   *
   * @param scopeType 归属维度
   * @param metricCode 口径码
   * @param currency 币种
   * @param periodStart 周期起
   * @param periodEnd 周期止
   * @param groupKey 分组键
   * @param total 分组内的目标总数
   * @param available 分组内**算得出达成率**的目标数
   * @param unavailable 分组内**算不出达成率**的目标数（NOT_STARTED / NOT_COMPARABLE / UNAVAILABLE）
   * @param rows 达成率行（**本页切片**）
   */
  public record AttainmentGroup(
      String scopeType,
      String metricCode,
      String currency,
      LocalDate periodStart,
      LocalDate periodEnd,
      String groupKey,
      int total,
      int available,
      int unavailable,
      List<AttainmentRow> rows) {}

  /**
   * 一条预警。
   *
   * @param targetId 目标 id
   * @param scopeType 归属维度
   * @param ownerId 归属对象标识
   * @param ownerName 归属对象显示名（**快照**）
   * @param metricCode 口径码
   * @param currency 币种
   * @param level WARNING（差一点）/ CRITICAL（明显跟不上）
   * @param kind FORECAST_BELOW_TARGET（按当前速度达不到）/ NO_ACTUALS（算不出达成率）
   * @param message 人话（页面上直接显示）
   * @param attainmentRate 达成率（**不可得 ⇒ null**）
   * @param forecastRate 预测达成率（**不可得 ⇒ null**）
   * @param elapsedRatio 时间进度
   */
  public record AttainmentAlert(
      UUID targetId,
      String scopeType,
      String ownerId,
      String ownerName,
      String metricCode,
      String currency,
      String level,
      String kind,
      String message,
      @Schema(nullable = true) BigDecimal attainmentRate,
      @Schema(nullable = true) BigDecimal forecastRate,
      BigDecimal elapsedRatio) {}

  /**
   * 达成追踪看板（**一个目标的当前值 / 达成率 / 预测达成 + 未达标高亮 + 排名 + 预警**）。
   *
   * @param asOfDate 评估日（**按本租户时区的日历日**，取自数据库时钟）
   * @param timezone 评估所用的时区
   * @param timezoneSource PROJECTION（取自本租户投影事实的分桶时区）/ FALLBACK_UTC
   * @param timezoneNote 时区回退时写清原因
   * @param evaluatedAt 评估时刻（timestamptz）
   * @param page 页码（0 起）
   * @param size 每页条数
   * @param total 符合筛选条件的目标总数
   * @param groups 达成率分组（**本页切片**；排名位次在全量上算，见 {@code rankNote}）
   * @param alerts 预警清单（**覆盖全部符合条件的目标**，不随分页截断）
   * @param summary 概览计数
   * @param rankNote 排名口径说明
   * @param note 口径说明
   */
  public record AttainmentBoard(
      LocalDate asOfDate,
      String timezone,
      String timezoneSource,
      String timezoneNote,
      OffsetDateTime evaluatedAt,
      int page,
      int size,
      long total,
      List<AttainmentGroup> groups,
      List<AttainmentAlert> alerts,
      AttainmentSummary summary,
      String rankNote,
      String note) {}

  /**
   * 概览计数（**「算不出」的单独计数，不并进未达标**）。
   *
   * @param total 目标总数
   * @param achieved 已达标（达成率 ≥ 100%）
   * @param onTrack 按当前速度能达标
   * @param atRisk 差一点（预测达成率 80% ~ 100%）
   * @param behind 明显跟不上（预测达成率 < 80%）
   * @param notStarted 周期尚未开始
   * @param notComparable 目标值不是正数 ⇒ 达成率无定义
   * @param unavailable 实际值不可得
   * @param underAchieved 未达标项总数（**不含「算不出」的那几类**）
   */
  public record AttainmentSummary(
      int total,
      int achieved,
      int onTrack,
      int atRisk,
      int behind,
      int notStarted,
      int notComparable,
      int unavailable,
      int underAchieved) {}

  // ==================================================================
  // 排名
  // ==================================================================

  /**
   * 排名里的一条（**人员 / 产品维度排名**）。
   *
   * @param rank 位次（1 起）
   * @param rankTotal 本分组的目标总数
   * @param groupKey 排名分组键
   * @param scopeType 归属维度
   * @param ownerId 归属对象标识
   * @param ownerCode 归属对象业务编码（**快照**）
   * @param ownerName 归属对象显示名（**快照**）
   * @param metricCode 口径码
   * @param currency 币种
   * @param targetValue 目标值
   * @param currentValue 当前值（**不可得 ⇒ null**）
   * @param attainmentRate 达成率（**不可得 ⇒ null**）
   * @param forecastRate 预测达成率（**不可得 ⇒ null**）
   * @param status 达成状态
   */
  public record AttainmentRankEntry(
      int rank,
      int rankTotal,
      String groupKey,
      String scopeType,
      String ownerId,
      String ownerCode,
      String ownerName,
      String metricCode,
      String currency,
      BigDecimal targetValue,
      @Schema(nullable = true) BigDecimal currentValue,
      @Schema(nullable = true) BigDecimal attainmentRate,
      @Schema(nullable = true) BigDecimal forecastRate,
      String status) {}

  /**
   * 排名清单（真分页 + 真计数）。
   *
   * @param page 页码（0 起）
   * @param size 每页条数
   * @param total 总条数
   * @param entries 排名条目
   * @param note 口径说明
   */
  public record AttainmentRanking(
      int page, int size, long total, List<AttainmentRankEntry> entries, String note) {}

  // ==================================================================
  // 快照（落库的达成评估）
  // ==================================================================

  /**
   * 一条落库的达成快照（下游 17 绩效核算 / 1 工作台预警读的就是它）。
   *
   * @param id 快照 id
   * @param targetId 目标 id
   * @param evaluatedOn 评估日（本租户时区的日历日）
   * @param scopeType 归属维度
   * @param ownerId 归属对象标识
   * @param ownerCode 归属对象业务编码（**快照**）
   * @param ownerName 归属对象显示名（**快照**）
   * @param metricCode 口径码
   * @param currency 币种
   * @param periodStart 周期起
   * @param periodEnd 周期止
   * @param targetValue 目标值
   * @param currentValue 当前值（**不可得 ⇒ null**）
   * @param attainmentRate 达成率（**不可得 ⇒ null**）
   * @param forecastValue 预测期末值
   * @param forecastRate 预测达成率
   * @param elapsedRatio 时间进度
   * @param actualStatus 实际值状态
   * @param status 达成状态
   * @param actualNote 实际值侧说明
   * @param forecastNote 预测侧说明
   * @param rank 位次
   * @param rankTotal 分组总数
   * @param rankGroup 分组键
   * @param evaluatedAt 评估时刻（timestamptz）
   */
  public record AttainmentSnapshot(
      UUID id,
      UUID targetId,
      LocalDate evaluatedOn,
      String scopeType,
      String ownerId,
      String ownerCode,
      String ownerName,
      String metricCode,
      String currency,
      LocalDate periodStart,
      LocalDate periodEnd,
      BigDecimal targetValue,
      @Schema(nullable = true) BigDecimal currentValue,
      @Schema(nullable = true) BigDecimal attainmentRate,
      @Schema(nullable = true) BigDecimal forecastValue,
      @Schema(nullable = true) BigDecimal forecastRate,
      BigDecimal elapsedRatio,
      String actualStatus,
      String status,
      String actualNote,
      String forecastNote,
      int rank,
      int rankTotal,
      String rankGroup,
      OffsetDateTime evaluatedAt) {}

  /**
   * 快照清单（真分页 + 真计数）。
   *
   * @param page 页码（0 起）
   * @param size 每页条数
   * @param total 总条数
   * @param snapshots 快照
   * @param note 口径说明
   */
  public record AttainmentSnapshotPage(
      int page, int size, long total, List<AttainmentSnapshot> snapshots, String note) {}

  // ==================================================================
  // 评估（写派生读模型）
  // ==================================================================

  /**
   * 一次评估的结果。
   *
   * @param evaluated 评估了（=落库了）几条
   * @param asOfDate 评估日
   * @param evaluatedAt 评估时刻（timestamptz）
   * @param board 评估出来的达成率表（与 GET 看板同一套计算，只是这次**落了库**）
   */
  public record AttainmentEvaluateResult(
      int evaluated, LocalDate asOfDate, OffsetDateTime evaluatedAt, AttainmentBoard board) {}
}
