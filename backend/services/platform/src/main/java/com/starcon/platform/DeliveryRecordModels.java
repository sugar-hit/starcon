package com.starcon.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 18.1.2.2「投递记录」的传输模型。
 *
 * <p>蓝图 18-integration-and-automation.md 原文：
 *
 * <blockquote>
 *
 * 职责：记录事件投递明细：事件 ID、订阅方、投递状态、响应码、重试次数；提供手动重投与死信重放。
 * 输入：投递记录；重放请求。 输出：投递日志 + 重放任务。
 * 关系：上游 = 18.1.2.1；下游 = 外部端点。
 * <b>接收方应按 event_id 幂等去重</b>——投递保证是"至少一次"，不是"恰好一次"。
 *
 * </blockquote>
 *
 * <p><b>🔴 契约类型前缀必须是 {@code DeliveryRecord*}。</b>18.1.2.1 占用了
 * {@code EventSubscription*}（订阅配置侧），本模块是**投递执行与明细侧**，
 * 契约的扁平命名空间里两者必须能一眼分开。
 *
 * <p><b>🔴 本模块一个 {@code double} 都没有</b>：耗时是**毫秒计数**（整数），
 * 其余计数用 int。本模块不落任何金额，也不落任何数量。
 *
 * <p><b>🔴 状态码 0 是一个有意义的值</b>：它表示「**根本没拿到响应**」
 * （连接被拒 / 超时 / 没有发出去）。把它翻成 200 或 -1 都是在编造事实，
 * 所以 {@code statusCode = 0} 在契约里是合法取值，不是「没填」。
 */
public final class DeliveryRecordModels {

  private DeliveryRecordModels() {}

  /** 投递状态。 */
  public enum DeliveryStatus {
    /** 未成功投递，且**还会自动重试**（含刚建好还没发出去的）。 */
    PENDING("待重试"),
    /** 投递成功（2xx）。 */
    DELIVERED("已送达"),
    /** 失败且**不会自动重试**（永久性错误：订阅停用 / 没有生效中的签名密钥 / 地址非法）。 */
    FAILED("失败"),
    /** 重试次数用尽，转入死信箱（终态，可人工重放）。 */
    DEAD_LETTER("已转死信");

    private final String label;

    DeliveryStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析状态；非法取值返回 {@code null}（由服务层给一句能直接展示的话）。 */
    public static DeliveryStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /** 一次投递尝试的来源。 */
  public enum AttemptSource {
    /** 首次投递。 */
    INITIAL("首次"),
    /** 策略驱动的自动重试。 */
    RETRY("自动重试"),
    /** 人工手动重投。 */
    MANUAL("手动重投"),
    /** 死信重放。 */
    DEAD_LETTER_REPLAY("死信重放");

    private final String label;

    AttemptSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static AttemptSource parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /** 死信状态。 */
  public enum DeadLetterStatus {
    /** 还没人处理（可重放）。 */
    PENDING("待处理"),
    /** 已经重放过（终态）。 */
    REPLAYED("已重放"),
    /** 超过保留期（终态）。 */
    EXPIRED("已过期");

    private final String label;

    DeadLetterStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static DeadLetterStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /** 重放任务状态。 */
  public enum ReplayStatus {
    /** 已受理。 */
    PENDING("已受理"),
    /** 重放成功。 */
    SUCCEEDED("重放成功"),
    /** 重放失败。 */
    FAILED("重放失败");

    private final String label;

    ReplayStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static ReplayStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record DeliveryRecordCapability(
      String module, String name, boolean available, String basis, String ownerModule, String impact) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record DeliveryRecordGap(
      String module, String name, String ownerService, boolean available, String group, String detail) {}

  /** 18.1.2.2 的能力声明。 */
  public record DeliveryRecordCapabilities(
      List<DeliveryRecordCapability> capabilities, List<DeliveryRecordGap> downstreamGaps) {}

  // ================================================================ 概览

  /** 概览：几个数（真查库，不是估算）。 */
  public record DeliveryRecordOverview(
      long deliveryCount,
      long pendingCount,
      long deliveredCount,
      long failedCount,
      long deadLetterCount,
      long attemptCount,
      long replayTaskCount,
      long deadLetterPendingCount) {}

  // ================================================================ 投递记录

  /**
   * 一条投递记录。
   *
   * <p>🔴 {@code statusCode = 0} 表示「**没拿到响应**」，不是「没填」。
   */
  public record DeliveryRecordRow(
      UUID id,
      UUID eventId,
      String eventType,
      int schemaVersion,
      UUID subscriptionId,
      String subscriptionName,
      UUID appId,
      String targetUrl,
      String status,
      String statusLabel,
      int statusCode,
      int attemptCount,
      int replayCount,
      String lastError,
      OffsetDateTime nextRetryAt,
      OffsetDateTime firstAttemptAt,
      OffsetDateTime lastAttemptAt,
      OffsetDateTime deliveredAt,
      OffsetDateTime deadLetteredAt,
      OffsetDateTime createdAt) {}

  /** 投递记录分页结果。 */
  public record DeliveryRecordPage(List<DeliveryRecordRow> items, int page, int size, long total) {}

  /** 投递详情：记录 + 投递日志 + 死信 + 重放任务。 */
  public record DeliveryRecordDetail(
      DeliveryRecordRow delivery,
      List<DeliveryRecordAttemptRow> attempts,
      DeliveryRecordDeadLetterRow deadLetter,
      List<DeliveryRecordReplayTaskRow> replayTasks) {}

  // ================================================================ 投递日志

  /**
   * 一次投递尝试（= 投递日志的一行）。
   *
   * <p>🔴 {@code statusCode = 0} 表示「**根本没拿到响应**」。
   */
  public record DeliveryRecordAttemptRow(
      UUID id,
      UUID deliveryId,
      int attemptNo,
      int statusCode,
      boolean success,
      String attemptSource,
      String attemptSourceLabel,
      long durationMs,
      String error,
      String responseSnippet,
      UUID replayTaskId,
      OffsetDateTime occurredAt) {}

  // ================================================================ 死信

  /** 一条死信。 */
  public record DeliveryRecordDeadLetterRow(
      UUID id,
      UUID deliveryId,
      UUID eventId,
      String eventType,
      String subscriptionName,
      String targetUrl,
      String reason,
      int failedAttemptNo,
      String status,
      String statusLabel,
      OffsetDateTime expiresAt,
      OffsetDateTime replayedAt,
      UUID replayTaskId,
      OffsetDateTime createdAt) {}

  /** 死信分页结果。 */
  public record DeliveryRecordDeadLetterPage(
      List<DeliveryRecordDeadLetterRow> items, int page, int size, long total) {}

  // ================================================================ 重放任务

  /** 一条重放任务。 */
  public record DeliveryRecordReplayTaskRow(
      UUID id,
      UUID deliveryId,
      UUID eventId,
      String eventType,
      String subscriptionName,
      String triggerType,
      String triggerTypeLabel,
      String status,
      String statusLabel,
      int resultStatusCode,
      String resultMessage,
      String reason,
      UUID requestedBy,
      OffsetDateTime createdAt,
      OffsetDateTime finishedAt) {}

  /** 重放任务分页结果。 */
  public record DeliveryRecordReplayTaskPage(
      List<DeliveryRecordReplayTaskRow> items, int page, int size, long total) {}

  // ================================================================ 发布 / 重放

  /**
   * 发布一个事件（= 触发一次真实投递）。
   *
   * <p>🔴 {@code eventId} **可空**：不传时平台生成一个（UUID）；
   * 传了就用调用方给的（上游 outbox 的事件 ID 就能直接驱动本模块）。
   * 传了同一个 eventId 再次发布 ⇒ **幂等**：不会产生第二条投递记录，
   * 已存在的记录原样返回（蓝图：「接收方应按 event_id 幂等去重」）。
   */
  public record DeliveryRecordPublishInput(
      String eventId, String eventType, Integer schemaVersion, String payload) {}

  /**
   * 发布结果。
   *
   * <p>🔴 {@code skipped} 里是**被跳过**的订阅（订阅不生效 / 事件没订阅 / 过滤条件不匹配 /
   * 同一事件已投过），{@code failures} 里是**这次没有送达**的投递
   * （响应码非 2xx，或压根没发出去）—— 两者不是一回事：
   * 混成一句「有几个失败了」会让人以为平台漏投。
   *
   * <p>⚠️ 「这次没有送达」**不等于**「没救了」：还在自动重试队列里的那些，
   * 重试扫描会接着处理；只有重试耗尽（或遇到永久性错误）的才会进死信箱。
   */
  public record DeliveryRecordPublishResult(
      UUID eventId,
      String eventType,
      int schemaVersion,
      int matchedCount,
      List<DeliveryRecordRow> deliveries,
      List<DeliveryRecordSkip> skipped,
      List<DeliveryRecordFailure> failures) {}

  /** 一条被跳过的订阅及原因。 */
  public record DeliveryRecordSkip(UUID subscriptionId, String subscriptionName, String reason) {}

  /** 一条失败的投递及原因。 */
  public record DeliveryRecordFailure(UUID deliveryId, UUID subscriptionId, String reason, int statusCode) {}

  /** 手动重投 / 死信重放的入参（备注可选）。 */
  public record DeliveryRecordReplayInput(String reason) {}

  /** 重放结果：那条重放任务 + 被重放的那条投递记录。 */
  public record DeliveryRecordReplayResult(
      DeliveryRecordReplayTaskRow task, DeliveryRecordRow delivery, DeliveryRecordAttemptRow attempt) {}

  /**
   * 到期重试扫描的结果（**外部调度触发**的一次入口）。
   *
   * @param scanned 扫到多少条「到期且待重试」的投递
   * @param retried 实际又投了多少次
   * @param delivered 其中成功多少条
   * @param failed 其中失败多少条
   * @param deadLettered 其中因重试耗尽转入死信多少条
   */
  public record DeliveryRecordRetrySweep(
      int scanned, int retried, int delivered, int failed, int deadLettered) {}

  /**
   * 「投一次」的结果（服务层 → {@link WebhookDeliveryPort}）。
   *
   * <p>🔴 <b>这三个布尔与响应码是一组不可拆的事实</b>：
   *
   * <ul>
   *   <li>{@code connected} —— 到底有没有连上对方；
   *   <li>{@code sent} —— 到底有没有**把包发出去**；
   *   <li>{@code statusCode} —— 对方回了什么；<b>0 = 根本没拿到响应</b>
   *       （连接被拒 / 超时 / 没发出去），不是「返回了 0」。
   * </ul>
   *
   * <p>一条假的成功回执会让「对方系统已经收到并处理了这个事件」在数据上看起来发生过，
   * 而现场一个包都没发出去 —— 所以这里**不提供**任何「构造一条成功」的便捷方法。
   *
   * @param skipped 是否因幂等而跳过（同一事件已投递给该订阅 ⇒ 不重复发包）
   */
  public record DeliveryRecordDirectResult(
      boolean connected,
      boolean sent,
      int statusCode,
      String reason,
      UUID deliveryId,
      boolean skipped) {}

  // ================================================================ 工具

  /**
   * 把一段文本解析成**规范化的 JSON 对象**文本。
   *
   * <p>事件载荷必须是 **JSON 对象**（不是数组、不是标量）：
   * 「载荷」若不是对象，订阅方的过滤条件与字段契约都无从定义。
   *
   * @return 规范化后的 JSON 文本；不是合法 JSON 对象时返回 {@code null}
   */
  static String canonicalJsonObject(String raw, ObjectMapper json) {
    if (raw == null || raw.isBlank()) return "{}";
    try {
      var node = json.readTree(raw);
      if (node == null || !node.isObject()) return null;
      return json.writeValueAsString(node);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return null;
    }
  }
}
