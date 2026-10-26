package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 1.5.2.1 服务与任务状态 —— channel 侧只读投影的响应形状。
 *
 * <p>🔴 <b>本模块不产生任何新的业务事实</b>：这里的一切都是 channel 自己库里的
 * {@code sync_run} / {@code sync_failure} / {@code backfill_task} /
 * {@code listing_migration_batch} / {@code publish_task} / {@code listing_collect_task}
 * 的<b>只读投影</b>。写入所有权仍然在 02 渠道与店铺、04 刊登侧那些模块手里。
 *
 * <p>🔴 <b>形状设计的三条硬纪律</b>：
 *
 * <ol>
 *   <li><b>每张表的形状都不一样，所以每张表都带自己的口径说明</b>：
 *       {@code rawState} 是那张表自己的状态值，{@code state} 是本模块规范化后的状态，
 *       {@code timingNote} 说清耗时是从哪几列算出来的。规范化**不抹掉**原始值 ——
 *       抹掉之后就没法核对「这张表到底是怎么记状态的」。</li>
 *   <li><b>「停滞」与「失败」是两类提示，分开</b>：一个是「没在跑」，一个是「跑挂了」。
 *       蓝图要的正是这两类分开，所以 {@code ServiceTaskStatusAlertList} 里是两个独立列表。</li>
 *   <li><b>「系统故障」与「业务数据问题」必须在数据形态上分得开</b>：
 *       {@code failureClass} 是分类字段（BUSINESS / SYSTEM / MIXED / UNCLASSIFIED /
 *       UNDETERMINED），不是一句说明文案。</li>
 * </ol>
 */
public final class ServiceTaskStatusModels {

  private ServiceTaskStatusModels() {}

  // ================================================================ 依据

  /**
   * 一条判定依据。
   *
   * @param code 规则码（与能力声明里的规则表一一对应）
   * @param severity 严重度 RED / YELLOW / GREEN
   * @param label 规则的人话名
   * @param detail 带上了真实数字的人话理由
   */
  public record ServiceTaskStatusReason(
      String code, String severity, String label, String detail) {}

  // ================================================================ 任务卡片

  /**
   * 一条任务状态。
   *
   * @param taskId 任务 id（那张表自己的主键）
   * @param sourceKey 任务来源键（= 事实表名）
   * @param sourceLabel 任务来源的人话名
   * @param ownerModule 归属模块号（处置要去哪个模块）
   * @param title 人话标题（尽量带上平台 / 店铺 / 资源类型）
   * @param detail 补充信息（资源类型、平台状态码等）
   * @param rawState 🔴 那张表**自己**的状态值（未经规范化）
   * @param state 规范化后的状态 RUNNING / PENDING / SUCCEEDED / PARTIAL / FAILED / CANCELLED / UNKNOWN
   * @param inFlight 这条任务是否处于「执行中」（决定它会不会被判停滞）
   * @param failed 这条任务是否失败
   * @param failureClass 失败分类 NONE / BUSINESS / SYSTEM / MIXED / UNCLASSIFIED / UNDETERMINED
   * @param failureClassNote 分类依据的人话说明（**判不了时必须写清为什么判不了**）
   * @param stallDeterminable 停滞是否**判得了**（false = 在跑但拿不到开始时刻 ⇒ 判不了）
   * @param stalled 是否已判为停滞
   * @param stallSeverity 停滞的严重度 RED / YELLOW（未停滞时为 null）
   * @param stallMinutes 已经跑了多少分钟（服务端按数据库 now() 算好）
   * @param severity 本条任务的综合严重度 RED / YELLOW / GREEN
   * @param reasons 逐条依据
   * @param occurredAt 这件事发生/开始的时刻（按这张表能拿到的那个时间戳）
   * @param startedAt 真正的「开始执行时刻」（这张表没有这列时为 null）
   * @param finishedAt 真正的「结束时刻」（这张表没有这列时为 null）
   * @param durationMs 耗时毫秒（服务端算好，前端不重算）
   * @param triggerSource 触发来源（这张表没有这列时为 null）
   * @param timingNote 耗时是从哪几列算出来的（逐字写清）
   * @param targetModule 处置归属模块
   * @param targetRoute 前端路由（**必须是真实存在的路由**；没有独立页面时为 null）
   * @param targetHint 落点提示
   * @param targetAvailable 该跳转目标是否真的存在
   * @param targetNote 跳转目标的真实状况说明
   */
  public record ServiceTaskStatusTaskCard(
      String taskId,
      String sourceKey,
      String sourceLabel,
      String ownerModule,
      String title,
      String detail,
      String rawState,
      String state,
      boolean inFlight,
      boolean failed,
      String failureClass,
      String failureClassNote,
      boolean stallDeterminable,
      boolean stalled,
      @Schema(nullable = true) String stallSeverity,
      @Schema(nullable = true) Long stallMinutes,
      String severity,
      List<ServiceTaskStatusReason> reasons,
      @Schema(nullable = true) OffsetDateTime occurredAt,
      @Schema(nullable = true) OffsetDateTime startedAt,
      @Schema(nullable = true) OffsetDateTime finishedAt,
      @Schema(nullable = true) Long durationMs,
      @Schema(nullable = true) String triggerSource,
      String timingNote,
      String targetModule,
      @Schema(nullable = true) String targetRoute,
      @Schema(nullable = true) String targetHint,
      boolean targetAvailable,
      String targetNote) {}

  /**
   * 任务状态列表（真分页）。
   *
   * @param items 当前页的任务
   * @param total 命中筛选的总条数
   * @param page 页号（从 0 开始）
   * @param size 每页行数
   */
  public record ServiceTaskStatusTaskPage(
      List<ServiceTaskStatusTaskCard> items, int total, int page, int size) {}

  // ================================================================ 失败分类

  /**
   * 逐失败原因的计数。
   *
   * @param reason 失败原因码（sync_failure.reason 的取值域）
   * @param label 人话名
   * @param failureClass 该原因属哪一类 BUSINESS / SYSTEM
   * @param windowCount 近窗口内条数
   * @param totalCount 累计条数
   */
  public record ServiceTaskStatusFailureReasonCount(
      String reason, String label, String failureClass, long windowCount, long totalCount) {}

  /**
   * 一个失败分类的计数。
   *
   * @param failureClass BUSINESS / SYSTEM / MIXED / UNCLASSIFIED / UNDETERMINED
   * @param label 人话名
   * @param windowCount 近窗口内条数
   * @param totalCount 累计条数
   * @param byReason 逐原因计数
   */
  public record ServiceTaskStatusFailureClassCount(
      String failureClass,
      String label,
      long windowCount,
      long totalCount,
      List<ServiceTaskStatusFailureReasonCount> byReason) {}

  /**
   * 失败分类的说明（能力声明原样吐出）。
   *
   * @param failureClass 分类码
   * @param label 人话名
   * @param reasons 归到这一类的失败原因码
   * @param meaning 这一类意味着什么
   * @param actionHint 该去找谁处理
   */
  public record ServiceTaskStatusFailureClassDoc(
      String failureClass, String label, List<String> reasons, String meaning, String actionHint) {}

  // ================================================================ 任务来源

  /**
   * 一个任务来源（= 一张事实表）的口径说明。
   *
   * @param sourceKey 来源键（= 表名）
   * @param table 事实表名
   * @param label 人话名
   * @param ownerModule 归属模块号
   * @param ownerModuleName 归属模块名
   * @param stateColumn 状态列名
   * @param startColumn 「开始执行时刻」列名（这张表没有则 null）
   * @param finishColumn 「结束时刻」列名（这张表没有则 null）
   * @param durationColumn 耗时列名（这张表没有则 null）
   * @param stateValues 该表的状态取值域（原文）
   * @param canonicalStates 这些取值被规范化成了什么
   * @param inFlightValues 哪个取值算「执行中」
   * @param stallSupported 这张表能不能判停滞
   * @param stallNote 能不能判停滞的**逐字**理由
   * @param timingNote 耗时是从哪几列算出来的
   * @param failureClassNote 这张表的失败能不能分类（判不了时写清为什么）
   */
  public record ServiceTaskStatusSourceDoc(
      String sourceKey,
      String table,
      String label,
      String ownerModule,
      String ownerModuleName,
      String stateColumn,
      @Schema(nullable = true) String startColumn,
      @Schema(nullable = true) String finishColumn,
      @Schema(nullable = true) String durationColumn,
      String stateValues,
      String canonicalStates,
      String inFlightValues,
      boolean stallSupported,
      String stallNote,
      String timingNote,
      String failureClassNote) {}

  /**
   * 一个任务来源的运行时统计。
   *
   * @param sourceKey 来源键
   * @param sourceLabel 人话名
   * @param ownerModule 归属模块号
   * @param total 该来源下的任务总数
   * @param running 执行中的条数
   * @param failed 失败的条数
   * @param partial 部分完成的条数
   * @param stalled 已判为停滞的条数
   * @param stallUndeterminable 判不了停滞的条数（在跑但没有开始时刻）
   */
  public record ServiceTaskStatusSourceStat(
      String sourceKey,
      String sourceLabel,
      String ownerModule,
      long total,
      long running,
      long failed,
      long partial,
      long stalled,
      long stallUndeterminable) {}

  // ================================================================ 总览

  /**
   * 总览。
   *
   * @param totalTasks 任务总数（**当前可见范围内**的总数，不是本次装载的条数）
   * @param runningCount 执行中的任务数
   * @param failedCount 状态为 FAILED 的任务数
   * @param partialCount 状态为 PARTIAL 的任务数
   * @param stalledCount 已判为停滞的任务数
   * @param stallUndeterminableCount 判不了停滞的任务数
   * @param failedRecordTotal 失败队列里的失败记录总数（**记录口径**，与任务口径不是一回事）
   * @param businessFailureTotal 失败记录里属「业务/配置问题」的条数
   * @param systemFailureTotal 失败记录里属「系统/外部问题」的条数
   * @param failureByClass 逐分类计数
   * @param sources 逐来源统计
   * @param gaps 未接通 / 本模块未接入的上游
   * @param dataAsOf 数据截至时刻
   */
  public record ServiceTaskStatusSummary(
      long totalTasks,
      long runningCount,
      long failedCount,
      long partialCount,
      long stalledCount,
      long stallUndeterminableCount,
      long failedRecordTotal,
      long businessFailureTotal,
      long systemFailureTotal,
      List<ServiceTaskStatusFailureClassCount> failureByClass,
      List<ServiceTaskStatusSourceStat> sources,
      List<ServiceTaskStatusCapabilityGap> gaps,
      OffsetDateTime dataAsOf) {}

  // ================================================================ 提示

  /**
   * 一条提示（停滞提示 / 失败提示）。
   *
   * @param kind STALLED / FAILED
   * @param severity 严重度 RED / YELLOW
   * @param title 人话标题
   * @param detail 带真实数字的人话说明
   * @param sourceKey 任务来源键
   * @param sourceLabel 任务来源的人话名
   * @param ownerModule 归属模块号
   * @param taskId 任务 id
   * @param failureClass 失败分类（STALLED 类型的这一格为 null）
   * @param targetModule 处置归属模块
   * @param targetRoute 前端路由（**必须是真实存在的路由**；没有独立页面时为 null）
   * @param targetHint 落点提示
   * @param targetAvailable 该跳转目标是否真的存在
   * @param targetNote 跳转目标的真实状况说明
   */
  public record ServiceTaskStatusAlert(
      String kind,
      String severity,
      String title,
      String detail,
      String sourceKey,
      String sourceLabel,
      String ownerModule,
      String taskId,
      @Schema(nullable = true) String failureClass,
      String targetModule,
      @Schema(nullable = true) String targetRoute,
      @Schema(nullable = true) String targetHint,
      boolean targetAvailable,
      String targetNote) {}

  /**
   * 提示清单 —— 🔴 <b>停滞与失败是两个独立列表</b>。
   *
   * <p>「没在跑」与「跑挂了」是两件事：前者是调度没起来，后者是任务执行中挂掉。
   * 混在一个列表里，运维就分不清该去看调度器还是去看任务日志。
   *
   * @param stalled 停滞提示
   * @param failed 失败提示
   * @param stalledTotal 停滞提示总数
   * @param failedTotal 失败提示总数
   * @param truncated 🔴 是否有任务因为超出装载上限而**没有**被判（true = 提示不完整）
   */
  public record ServiceTaskStatusAlertList(
      List<ServiceTaskStatusAlert> stalled,
      List<ServiceTaskStatusAlert> failed,
      int stalledTotal,
      int failedTotal,
      boolean truncated) {}

  // ================================================================ 能力声明

  /**
   * 规则表的一行。
   *
   * @param code 规则码
   * @param severity 该规则命中时的严重度
   * @param condition 命中条件（逐字写清，含阈值与时间基准）
   * @param reason 人话理由
   * @param source 事实来源（表 + 列）
   */
  public record ServiceTaskStatusRuleDoc(
      String code, String severity, String condition, String reason, String source) {}

  /**
   * 阈值。
   *
   * @param stallWarnMinutes 超过多少分钟还在跑 ⇒ 停滞预警（黄）
   * @param stallCriticalMinutes 超过多少分钟还在跑 ⇒ 停滞严重（红）
   * @param failureWindowHours 失败计数窗口（小时）
   * @param maxPageSize 每页行数上限
   */
  public record ServiceTaskStatusThresholds(
      int stallWarnMinutes, int stallCriticalMinutes, int failureWindowHours, int maxPageSize) {}

  /**
   * 一个未接通 / 未开发的上游。
   *
   * @param module 模块号
   * @param name 模块名
   * @param available 本模块是否真的接通
   * @param ownerModule 归属模块
   * @param impact 影响说明
   */
  public record ServiceTaskStatusCapabilityGap(
      String module, String name, boolean available, String ownerModule, String impact) {}

  /**
   * 能力声明。
   *
   * @param rules 规则表
   * @param thresholds 生效阈值
   * @param severityLevels 严重度词表
   * @param sources 任务来源清单（**逐张表**说清形状差异）
   * @param gaps 未接通 / 未开发的上游
   * @param failureClasses 失败分类说明
   * @param stallSemantics 「停滞」的判定口径（含时间基准与阈值可配方式）
   * @param failureClassSemantics 「系统故障 vs 业务数据问题」的判定口径
   * @param readFailureSemantics 「读不到」与「没有」的口径
   * @param upstreamNote 上游说明
   * @param downstreamNote 下游说明
   * @param dataAsOf 数据截至时刻
   */
  public record ServiceTaskStatusCapabilities(
      List<ServiceTaskStatusRuleDoc> rules,
      ServiceTaskStatusThresholds thresholds,
      List<String> severityLevels,
      List<ServiceTaskStatusSourceDoc> sources,
      List<ServiceTaskStatusCapabilityGap> gaps,
      List<ServiceTaskStatusFailureClassDoc> failureClasses,
      String stallSemantics,
      String failureClassSemantics,
      String readFailureSemantics,
      String upstreamNote,
      String downstreamNote,
      OffsetDateTime dataAsOf) {}

  // ================================================================ 筛选项

  /**
   * 筛选项。
   *
   * @param sources 任务来源键
   * @param states 规范化后的状态词表
   * @param severities 严重度词表
   * @param failureClasses 失败分类词表
   * @param triggerSources 触发来源词表
   */
  public record ServiceTaskStatusOptions(
      List<String> sources,
      List<String> states,
      List<String> severities,
      List<String> failureClasses,
      List<String> triggerSources) {}
}
