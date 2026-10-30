package com.starcon.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 18.1.2.1 事件订阅的传输模型。
 *
 * <p>蓝图 18-integration-and-automation.md 原文：
 *
 * <blockquote>
 *
 * 职责：对外提供事件订阅（webhook）：可订阅事件列表、订阅端点配置、签名密钥、事件过滤、
 * 重试与死信策略。 输入：订阅配置；事件定义（schema 版本）。 输出：订阅记录；
 * 事件投递（带签名）。 关系：上游 = 业务事件（outbox）；下游 = 外部端点。
 * <b>事件 schema 必须版本化，破坏性变更要新增版本而非原地修改。</b>
 *
 * </blockquote>
 *
 * <p><b>🔴 契约类型前缀必须是 {@code EventSubscription*}。</b>channel 服务那一族
 * {@code Webhook*} 已经占了「webhook」这个词（那是**电商平台**回传商品 / 订单的通道，
 * 18 卷里归 channel），本模块说的是「**本系统对外提供的**事件订阅」，
 * 两件事在契约的扁平命名空间里必须能一眼分开。
 *
 * <p><b>🔴 本模块一个 {@code double} 都没有</b>：退避倍数是比率，用 {@code BigDecimal}
 * （库里的 NUMERIC(8,2)）；其余计数用 int。本模块不落任何金额。
 *
 * <p><b>🔴 明文只出现在 {@link EventSubscriptionSecretIssued} 这一个模型里</b> ——
 * 它是「生成 / 轮换」那一次响应。其余所有模型**永远不含明文**。
 */
public final class EventSubscriptionModels {

  private EventSubscriptionModels() {}

  /** 事件定义状态。 */
  public enum EventTypeStatus {
    /** 当前可被新订阅选中。 */
    ACTIVE("可用"),
    /** 已被新版本取代：不再可被新订阅选中，已订阅的仍按老版本收。 */
    DEPRECATED("已废弃");

    private final String label;

    EventTypeStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析状态；非法取值返回 {@code null}（由服务层给一句能直接展示的话）。 */
    public static EventTypeStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /** 订阅状态。 */
  public enum SubscriptionStatus {
    /** 生效。 */
    ACTIVE("生效"),
    /** 暂停（配置保留，暂时不投递）。 */
    PAUSED("暂停"),
    /** 已停用（终态；不可再改）。 */
    DISABLED("已停用");

    private final String label;

    SubscriptionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static SubscriptionStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /** 签名密钥状态。 */
  public enum SecretStatus {
    /** 当前生效（验签用这一把）。 */
    ACTIVE("生效"),
    /** 已轮换（历史痕迹，保留）。 */
    ROTATED("已轮换"),
    /** 已吊销（泄露止血）。 */
    REVOKED("已吊销");

    private final String label;

    SecretStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static SecretStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  // ================================================================ 能力声明

  /** 能力条目：每一维说清「有没有、依据是什么、缺了会怎样」。 */
  public record EventSubscriptionCapability(
      String module, String name, boolean available, String basis, String ownerModule, String impact) {}

  /** 下游缺口：谁没交付、归谁。 */
  public record EventSubscriptionGap(
      String module, String name, String ownerService, boolean available, String group, String detail) {}

  /** 18.1.2.1 的能力声明。 */
  public record EventSubscriptionCapabilities(
      List<EventSubscriptionCapability> capabilities, List<EventSubscriptionGap> downstreamGaps) {}

  // ================================================================ 概览

  /** 概览：几个数（真查库，不是估算）。 */
  public record EventSubscriptionOverview(
      long eventTypeCount,
      long activeEventTypeCount,
      long subscriptionCount,
      long activeSubscriptionCount,
      long filterCount,
      long signingSecretCount,
      long activeSigningSecretCount,
      long policyCount) {}

  // ================================================================ 事件定义（可订阅事件列表）

  /** 一条事件定义（= 可订阅事件列表的一行）。 */
  public record EventSubscriptionEventTypeRow(
      UUID id,
      String eventType,
      int schemaVersion,
      String status,
      String statusLabel,
      String payloadSchema,
      String sourceService,
      String sourceArtifact,
      OffsetDateTime registeredAt) {}

  /** 事件定义分页结果。 */
  public record EventSubscriptionEventTypePage(
      List<EventSubscriptionEventTypeRow> items, int page, int size, long total) {}

  /** 登记一个事件定义（= 一个 event_type 的一个 schema 版本）。 */
  public record EventSubscriptionEventTypeInput(
      String eventType,
      Integer schemaVersion,
      String status,
      String payloadSchema,
      String sourceService,
      String sourceArtifact) {}

  /** 改事件定义的状态（**只能改状态**：结构不可原地修改）。 */
  public record EventSubscriptionEventTypeStatusInput(String status) {}

  // ================================================================ 订阅

  /** 一条订阅。 */
  public record EventSubscriptionRow(
      UUID id,
      UUID appId,
      String appName,
      String name,
      String targetUrl,
      String status,
      String statusLabel,
      String description,
      int filterCount,
      boolean hasActiveSecret,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 订阅分页结果。 */
  public record EventSubscriptionPage(List<EventSubscriptionRow> items, int page, int size, long total) {}

  /** 创建 / 修改订阅的入参。 */
  public record EventSubscriptionInput(
      UUID appId, String name, String targetUrl, String status, String description) {}

  /** 订阅详情：订阅 + 事件过滤 + 签名密钥元数据 + 生效策略。 */
  public record EventSubscriptionDetail(
      EventSubscriptionRow subscription,
      List<EventSubscriptionFilterRow> filters,
      List<EventSubscriptionSecretRow> signingSecrets,
      EventSubscriptionPolicyRow effectivePolicy) {}

  // ================================================================ 事件过滤

  /** 一条事件过滤（订阅 ↔ 事件 + 过滤条件）。 */
  public record EventSubscriptionFilterRow(
      UUID id,
      UUID subscriptionId,
      String eventType,
      int schemaVersion,
      String filter,
      boolean enabled,
      OffsetDateTime createdAt) {}

  /** 增删事件过滤的入参。 */
  public record EventSubscriptionFilterInput(
      String eventType, Integer schemaVersion, String filter, Boolean enabled) {}

  // ================================================================ 签名密钥

  /**
   * 一把签名密钥的**元数据**。
   *
   * <p>🔴 **没有明文字段**：读接口永远拿不到密钥原文。
   */
  public record EventSubscriptionSecretRow(
      UUID id,
      UUID subscriptionId,
      int version,
      String status,
      String statusLabel,
      String secretHint,
      OffsetDateTime createdAt,
      OffsetDateTime rotatedAt) {}

  /**
   * 生成 / 轮换签名密钥的**那一次**响应。
   *
   * <p>🔴 {@code plaintext} 只在这一条里出现。离开这个响应就再也拿不回来
   * （库里只有密文，且明文只在内存里短暂存在）。
   */
  public record EventSubscriptionSecretIssued(
      EventSubscriptionSecretRow secret, String plaintext, String warning) {}

  // ================================================================ 策略

  /** 重试与死信策略（**配置**，不是运行态）。 */
  public record EventSubscriptionPolicyRow(
      UUID id,
      UUID subscriptionId,
      int maxAttempts,
      int initialBackoffSeconds,
      BigDecimal backoffMultiplier,
      int maxBackoffSeconds,
      int requestTimeoutSeconds,
      int deadLetterRetentionDays,
      String signatureAlgorithm,
      String signatureHeader,
      OffsetDateTime updatedAt) {}

  /** 策略入参（null = 沿用当前值）。 */
  public record EventSubscriptionPolicyInput(
      UUID subscriptionId,
      Integer maxAttempts,
      Integer initialBackoffSeconds,
      BigDecimal backoffMultiplier,
      Integer maxBackoffSeconds,
      Integer requestTimeoutSeconds,
      Integer deadLetterRetentionDays) {}

  // ================================================================ 审计

  /** 一条订阅配置变更审计。 */
  public record EventSubscriptionAuditEventRow(
      UUID id,
      UUID subscriptionId,
      String action,
      UUID actorUserId,
      String targetType,
      UUID targetId,
      String detail,
      OffsetDateTime occurredAt) {}

  /** 审计分页结果。 */
  public record EventSubscriptionAuditEventPage(
      List<EventSubscriptionAuditEventRow> items, int page, int size, long total) {}

  // ================================================================ 签名试算

  /**
   * 签名试算结果：平台**会发**的签名头长什么样。
   *
   * <p>🔴 这条响应**不投递**、**不写任何投递记录** —— 它只是把「签名怎么算」这件事
   * 变成一个对接方可以当场核对的**事实**。
   */
  public record EventSubscriptionSignaturePreview(
      String signatureHeader,
      String signatureAlgorithm,
      String timestamp,
      String signedPayload,
      String signature,
      String headerValue,
      String samplePayload,
      String note) {}

  /** 签名试算入参（样例载荷 + 可选时间戳）。 */
  public record EventSubscriptionSignaturePreviewInput(String payload, String timestamp) {}

  // ================================================================ 投递（当前未接入）

  /** 投递入参（样例事件 + 载荷）。 */
  public record EventSubscriptionDeliveryInput(
      String eventType, Integer schemaVersion, String payload) {}

  // ================================================================ 工具

  /**
   * 把一段文本解析成**规范化的 JSON 对象**文本。
   *
   * <p>🔴 过滤条件与 payload schema 都必须是 **JSON 对象**（不是数组、不是标量）：
   * 「过滤条件」若不是对象，语义无从定义；数据库里也有 CHECK 兜底。
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
