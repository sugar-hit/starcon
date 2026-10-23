package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 1.5.1.1 同步与 API 健康 —— channel 侧只读投影的响应形状。
 *
 * <p>🔴 <b>本模块不产生任何新的业务事实</b>：这里的一切都是 channel 自己库里
 * {@code channel_connection} / {@code shop} / {@code sync_cursor} / {@code sync_run} /
 * {@code sync_failure} / {@code outbox_event} 与三张任务队列的<b>只读投影</b>。
 * 写入所有权仍然在 02 渠道与店铺、02.4 同步运行那些模块手里。
 *
 * <p>🔴 <b>形状设计的两条硬纪律</b>：
 *
 * <ol>
 *   <li><b>「读不到」与「没有」必须分得开</b>：{@code platformsWithoutConnection} 说的是
 *       「这个平台**还没有**连接」，不是「连接健康」；{@code lastSyncAtSource} 说清
 *       「最近同步时间」到底取自哪一列；读不到上游时整个端点报 503 而不是回一个空集合。</li>
 *   <li><b>两个「最近同步时间」分开报</b>：{@code lastSyncAt}（连接级声明值）与
 *       {@code effectiveLastSyncAt}（真正被写到的运行级完成时刻）不是一个东西，
 *       页面上必须标明用的是哪一个。</li>
 * </ol>
 */
public final class SyncHealthModels {

  private SyncHealthModels() {}

  // ================================================================ 依据

  /**
   * 一条判定依据。
   *
   * @param code 规则码（与能力声明里的规则表一一对应）
   * @param severity 严重度 RED / YELLOW / GREEN
   * @param label 规则的人话名
   * @param detail 带上了真实数字的人话理由
   */
  public record SyncHealthReason(
      String code, String severity, String label, String detail) {}

  /**
   * 一条失败原因计数。
   *
   * @param reason 失败原因码（sync_failure.reason 的取值域）
   * @param windowCount 近窗口内（默认 24 小时）条数
   * @param totalCount 累计条数
   */
  public record SyncHealthFailureReasonCount(
      String reason, long windowCount, long totalCount) {}

  /**
   * 一个店铺的引用（挂在它所属的连接卡片下）。
   *
   * @param shopId 店铺 id
   * @param shopCode 店铺编码
   * @param shopName 店铺名
   */
  public record SyncHealthShopRef(String shopId, String shopCode, String shopName) {}

  // ================================================================ 卡片

  /**
   * 一张连接健康卡片。
   *
   * @param connectionId 连接 id
   * @param platformCode 平台码
   * @param platformName 平台名
   * @param authorizedAccount 平台侧账号
   * @param displayName 连接显示名
   * @param status 连接状态 ACTIVE / EXPIRING / EXPIRED / REVOKED / PAUSED
   * @param syncPaused 同步是否被暂停
   * @param severity 判定结果 RED / YELLOW / GREEN
   * @param reasons 逐条依据
   * @param shops 这条连接下的店铺
   * @param shopCount 店铺数
   * @param lastSyncAt 连接级「最后同步时刻」（{@code channel_connection.last_sync_at}）——🔴 本仓当前**没有任何写入方**，恒为 null
   * @param lastSyncAtSource 生效的「最近同步时刻」来源：CONNECTION_DECLARED / RUN_FINISHED / NONE
   * @param effectiveLastSyncAt 生效的最近同步时刻（真的被写到的那个）
   * @param staleHours 距最近同步时刻的小时数（null = 从来没有过）
   * @param lastRunStatus 最近一次运行的状态（null = 这条连接还没有任何运行记录）
   * @param lastRunTriggerSource 最近一次运行的触发来源
   * @param lastRunStartedAt 最近一次运行的开始时刻
   * @param lastRunFinishedAt 最近一次运行的完成时刻
   * @param lastRunDurationMs 最近一次运行的耗时（毫秒）
   * @param failureCountWindow 近窗口失败条数
   * @param failureCountTotal 累计失败条数
   * @param rateLimitedCountWindow 近窗口限流命中条数
   * @param rateLimitedCountTotal 累计限流命中条数
   * @param openFailureCount 待处理（status='OPEN'）失败条数
   * @param failureByReason 逐原因计数
   */
  public record SyncHealthConnectionCard(
      String connectionId,
      String platformCode,
      String platformName,
      String authorizedAccount,
      String displayName,
      String status,
      boolean syncPaused,
      String severity,
      List<SyncHealthReason> reasons,
      List<SyncHealthShopRef> shops,
      int shopCount,
      @Schema(nullable = true) OffsetDateTime lastSyncAt,
      String lastSyncAtSource,
      @Schema(nullable = true) OffsetDateTime effectiveLastSyncAt,
      @Schema(nullable = true) Long staleHours,
      @Schema(nullable = true) String lastRunStatus,
      @Schema(nullable = true) String lastRunTriggerSource,
      @Schema(nullable = true) OffsetDateTime lastRunStartedAt,
      @Schema(nullable = true) OffsetDateTime lastRunFinishedAt,
      @Schema(nullable = true) Integer lastRunDurationMs,
      long failureCountWindow,
      long failureCountTotal,
      long rateLimitedCountWindow,
      long rateLimitedCountTotal,
      long openFailureCount,
      List<SyncHealthFailureReasonCount> failureByReason) {}

  /**
   * 连接健康卡片分页。
   *
   * @param items 当前页的卡片
   * @param total 总条数
   * @param page 页号（从 0 开始）
   * @param size 每页行数
   */
  public record SyncHealthConnectionPage(
      List<SyncHealthConnectionCard> items, int total, int page, int size) {}

  // ================================================================ 积压

  /**
   * 一个积压来源。
   *
   * @param table 真实表名
   * @param label 人话名
   * @param pendingCondition 「算作积压」的判定条件（逐字写清，接口后面跟的 SQL 与它一字不差）
   * @param pendingCount 待处理条数
   * @param semantics 口径说明
   */
  public record SyncHealthBacklogSource(
      String table, String label, String pendingCondition, long pendingCount, String semantics) {}

  /**
   * 一个平台没有连接的缺口。
   *
   * @param platformCode 平台码
   * @param platformName 平台名
   * @param supportStatus 平台接入状态 AVAILABLE / BETA / PLANNED
   * @param state NO_CONNECTION / NO_CONNECTION_BETA / NOT_INTEGRATED
   * @param message 人话说明
   */
  public record SyncHealthPlatformGap(
      String platformCode,
      String platformName,
      String supportStatus,
      String state,
      String message) {}

  // ================================================================ 总览

  /**
   * 总览。
   *
   * @param totalConnections 连接总数（真实总数，**不是**本次装载的条数）
   * @param cardsTruncated 🔴 红黄绿分布是否只覆盖了前 N 条连接（true = 还有连接没被判）
   * @param redCount 红（先处理）连接数
   * @param yellowCount 黄（预警）连接数
   * @param greenCount 绿（正常）连接数
   * @param failureCountWindow 近窗口失败条数
   * @param failureCountTotal 累计失败条数
   * @param rateLimitedCountWindow 近窗口限流命中条数
   * @param rateLimitedCountTotal 累计限流命中条数
   * @param openFailureCount 待处理失败条数
   * @param failureByReason 逐原因计数（全租户）
   * @param backlog 积压来源逐条计数（**只含真实表**，合计在 backlogTotal，不混进列表里）
   * @param backlogTotal 积压合计（= backlog 列表逐条相加）
   * @param platformsWithoutConnection 没有连接的平台（🔴 不是「连接健康」）
   * @param dataAsOf 数据截至时刻
   */
  public record SyncHealthSummary(
      int totalConnections,
      boolean cardsTruncated,
      int redCount,
      int yellowCount,
      int greenCount,
      long failureCountWindow,
      long failureCountTotal,
      long rateLimitedCountWindow,
      long rateLimitedCountTotal,
      long openFailureCount,
      List<SyncHealthFailureReasonCount> failureByReason,
      List<SyncHealthBacklogSource> backlog,
      long backlogTotal,
      List<SyncHealthPlatformGap> platformsWithoutConnection,
      OffsetDateTime dataAsOf) {}

  // ================================================================ 需处理项

  /**
   * 一条需处理项。
   *
   * @param kind 处理项类型 REAUTHORIZE / VIEW_SYNC_RUNS / VIEW_FAILURE_QUEUE / VIEW_BACKLOG
   * @param severity 严重度 RED / YELLOW
   * @param title 人话标题
   * @param detail 带真实数字的人话说明
   * @param targetModule 处置归属模块
   * @param targetRoute 前端路由（**必须是真实存在的路由**；没有独立页面时为 null）
   * @param targetHint 落点提示（跳过去之后要看哪一块）
   * @param targetAvailable 该跳转目标是否真的存在
   * @param targetNote 跳转目标的真实状况说明
   */
  public record SyncHealthPendingItem(
      String kind,
      String severity,
      String title,
      String detail,
      String targetModule,
      @Schema(nullable = true) String targetRoute,
      @Schema(nullable = true) String targetHint,
      boolean targetAvailable,
      String targetNote) {}

  /**
   * 需处理项清单。
   *
   * @param items 处理项
   * @param total 条数
   * @param truncated 🔴 是否有连接因为超出装载上限而**没有**被判（true = 待处理项不完整）
   */
  public record SyncHealthPendingList(List<SyncHealthPendingItem> items, int total, boolean truncated) {}

  // ================================================================ 能力声明

  /**
   * 规则表的一行（红黄绿映射表）。
   *
   * @param code 规则码
   * @param severity 该规则命中时的严重度
   * @param condition 命中条件（枚举 → 条件的逐字映射）
   * @param reason 人话理由
   * @param source 事实来源（表 + 列）
   */
  public record SyncHealthRuleDoc(
      String code, String severity, String condition, String reason, String source) {}

  /**
   * 阈值。
   *
   * @param staleSyncWarnHours 超过多少小时没有同步 ⇒ 黄
   * @param staleSyncCriticalHours 超过多少小时没有同步 ⇒ 红
   * @param failureWindowHours 失败计数窗口（小时）
   */
  public record SyncHealthThresholds(
      int staleSyncWarnHours, int staleSyncCriticalHours, int failureWindowHours) {}

  /**
   * 一个未接通 / 未接入的下游。
   *
   * @param module 模块号
   * @param name 模块名
   * @param available 本模块是否真的接通
   * @param ownerModule 归属模块
   * @param impact 影响说明
   */
  public record SyncHealthCapabilityGap(
      String module, String name, boolean available, String ownerModule, String impact) {}

  /**
   * 能力声明。
   *
   * @param rules 红黄绿规则表（枚举 → 严重度）
   * @param thresholds 生效阈值
   * @param severityLevels 严重度词表
   * @param gaps 未接通 / 本模块未接入的下游
   * @param backlogSources 积压统计了哪几张表
   * @param backlogNote 积压口径说明
   * @param failureWindowSemantics 失败计数口径
   * @param lastSyncSemantics 「最近同步时间」口径（两个来源分开说）
   * @param readFailureSemantics 「读不到」与「没有」的口径
   * @param upstreamNote 上游说明
   * @param downstreamNote 下游说明
   * @param dataAsOf 数据截至时刻
   */
  public record SyncHealthCapabilities(
      List<SyncHealthRuleDoc> rules,
      SyncHealthThresholds thresholds,
      List<String> severityLevels,
      List<SyncHealthCapabilityGap> gaps,
      List<SyncHealthBacklogSource> backlogSources,
      String backlogNote,
      String failureWindowSemantics,
      String lastSyncSemantics,
      String readFailureSemantics,
      String upstreamNote,
      String downstreamNote,
      OffsetDateTime dataAsOf) {}

  // ================================================================ 筛选项

  /**
   * 筛选项。
   *
   * @param platforms 平台码（连接上真实出现过的）
   * @param statuses 连接状态词表
   * @param severities 严重度词表
   * @param failureReasons 失败原因词表
   */
  public record SyncHealthOptions(
      List<String> platforms,
      List<String> statuses,
      List<String> severities,
      List<String> failureReasons) {}
}
