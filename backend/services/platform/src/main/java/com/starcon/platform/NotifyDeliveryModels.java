package com.starcon.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 18.5.2.1「投递记录」的传输模型。
 *
 * <p>蓝图 18-integration-and-automation.md 原文：
 *
 * <blockquote>
 *
 * 职责：记录通知投递结果：模板、收件范围、渠道、状态、失败原因、重试；统计触达率与退订情况。
 * 输入：投递记录；重试请求。
 * 输出：投递日志 + 统计。
 * 关系：上游 = 18.5.1；下游 = 1.3.2.2 外部推送、15.5.2 触达。
 * <b>退订必须被尊重并生效</b>，反复触达退订用户是投诉与合规风险的主要来源。
 *
 * </blockquote>
 *
 * <p>🔴 <b>前缀是 {@code NotifyDelivery}**：契约被 codegen 合并进<b>同一个扁平命名空间</b>，
 * 而 platform 服务里已经有 18.1.2.2 的 {@code DeliveryRecord*} 一族（**webhook** 投递）。
 * 前缀撞车会让读代码的人以为是一件事 —— 那两张表在同一个库里。
 *
 * <p>🔴 <b>本模块一个 {@code double} 都没有</b>：人数 / 触达数 / 失败数 / 拦截数 /
 * 比率一律 {@link BigDecimal}（库里 {@code NUMERIC(19,6)} / {@code NUMERIC(7,4)}）。
 * 比率也是数量，同样不许用浮点。
 *
 * <p>🔴 <b>请求体不接受归属字段</b>：{@code tenantId} / {@code userId} / {@code membershipId}
 * 一律不在入参里 —— 租户与操作人来自已认证会话（Jackson 全局
 * {@code fail-on-unknown-properties} ⇒ 客户端自称的字段直接 400）。
 *
 * <p>🔴 <b>「已送达 / 失败 / 待出结果」是三态，不是布尔</b>：
 * {@code outcome} 把「已受理但结果没回」与「尝试过但失败」分开 ——
 * 否则触达率的分母会算错。
 */
@Schema(name = "NotifyDeliveryModels", description = "18.5.2.1 投递记录的传输模型")
public final class NotifyDeliveryModels {

  private NotifyDeliveryModels() {}

  /** 数量小数位（与本服务其它模块同一口径）。 */
  public static final int COUNT_SCALE = 6;

  // ================================================================ 枚举

  /** 投递渠道。 */
  public enum NotifyChannel {
    /** 邮件。 */
    EMAIL("邮件"),
    /** 短信。 */
    SMS("短信"),
    /** 移动推送。 */
    PUSH("移动推送"),
    /** 站内信。 */
    IN_APP("站内信"),
    /** Webhook。 */
    WEBHOOK("Webhook");

    private final String label;

    NotifyChannel(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static NotifyChannel parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 投递记录的状态。 */
  public enum NotifyDeliveryStatus {
    /** 已受理，还有尝试没出结果。 */
    PENDING("待出结果"),
    /** 全部收件人触达成功。 */
    DELIVERED("已送达"),
    /** 一部分触达成功、一部分失败。 */
    PARTIAL("部分送达"),
    /** 投了但全部失败。 */
    FAILED("全部失败"),
    /** 全部收件人命中退订 ⇒ 一次都没发出去。 */
    BLOCKED("全部被退订拦截");

    private final String label;

    NotifyDeliveryStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static NotifyDeliveryStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 收件范围的类型。 */
  public enum NotifyScopeType {
    /** 单个收件人。 */
    SINGLE("单个收件人"),
    /** 某个分群。 */
    SEGMENT("指定分群"),
    /** 订阅者全集。 */
    SUBSCRIBERS("订阅者全集");

    private final String label;

    NotifyScopeType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static NotifyScopeType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 退订的范围类型。 */
  public enum NotifyUnsubscribeScopeType {
    /** 只退订这一个模板。 */
    TEMPLATE("单个模板"),
    /** 退订这个场景的全部模板。 */
    SCENE("整个场景"),
    /** 该租户在该渠道上不再接任何通知。 */
    GLOBAL("全部通知");

    private final String label;

    NotifyUnsubscribeScopeType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static NotifyUnsubscribeScopeType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 退订来源。 */
  public enum NotifyUnsubscribeSource {
    /** 收件人自己要求。 */
    USER_REQUEST("收件人要求"),
    /** 投诉导致。 */
    COMPLAINT("投诉"),
    /** 运营代为登记。 */
    ADMIN("运营登记");

    private final String label;

    NotifyUnsubscribeSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static NotifyUnsubscribeSource parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 单次尝试的结果。 */
  public enum NotifyAttemptOutcome {
    /** 触达成功。 */
    DELIVERED("已送达"),
    /** 尝试过但失败。 */
    FAILED("失败"),
    /** 已受理，结果还没回。 */
    PENDING("待出结果");

    private final String label;

    NotifyAttemptOutcome(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static NotifyAttemptOutcome parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  /** 一次尝试的来源。 */
  public enum NotifyAttemptSource {
    /** 首次投递。 */
    INITIAL("首次投递"),
    /** 重试请求触发。 */
    RETRY("重试"),
    /** 人工手动重投。 */
    MANUAL("人工重投");

    private final String label;

    NotifyAttemptSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static NotifyAttemptSource parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equalsIgnoreCase(raw.trim())) return v;
      }
      return null;
    }
  }

  // ================================================================ 行模型

  /** 投递记录的一行（列表与详情共用）。 */
  public record NotifyDeliveryRow(
      UUID id,
      UUID templateId,
      String templateKey,
      String scopeType,
      String scopeRef,
      String channel,
      String status,
      BigDecimal recipientCount,
      BigDecimal blockedCount,
      BigDecimal targetedCount,
      BigDecimal deliveredCount,
      BigDecimal failedCount,
      BigDecimal pendingCount,
      int attemptCount,
      String failureReason,
      BigDecimal deliveryRate,
      OffsetDateTime nextRetryAt,
      OffsetDateTime firstAttemptAt,
      OffsetDateTime lastAttemptAt,
      OffsetDateTime deliveredAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 一次投递尝试（投递日志的一行）。 */
  public record NotifyAttemptRow(
      UUID id,
      UUID deliveryId,
      int attemptNo,
      String recipientRef,
      String channel,
      int statusCode,
      String outcome,
      String attemptSource,
      long durationMs,
      String failureReason,
      OffsetDateTime occurredAt) {}

  /** 一次退订拦截。 */
  public record NotifyBlockRow(
      UUID id,
      UUID deliveryId,
      UUID unsubscribeId,
      String recipientRef,
      String channel,
      String unsubscribeScopeType,
      String unsubscribeScopeKey,
      OffsetDateTime blockedAt) {}

  /** 一条退订登记。 */
  public record NotifyUnsubscribeRow(
      UUID id,
      String recipientRef,
      String channel,
      String scopeType,
      String scopeKey,
      UUID templateId,
      String reason,
      String source,
      OffsetDateTime unsubscribedAt,
      OffsetDateTime revokedAt,
      OffsetDateTime createdAt) {}

  /** 投递记录详情（记录 + 逐次尝试 + 拦截留痕）。 */
  public record NotifyDeliveryDetail(
      NotifyDeliveryRow delivery, List<NotifyAttemptRow> attempts, List<NotifyBlockRow> blocks) {}

  /**
   * 投递统计（触达率 / 退订率 / 拦截数）。
   *
   * @param deliveryRate 触达率 = 已送达人数 / 实际投递人数（分母不含被退订拦下的）
   * @param unsubscribeRate 退订率 = 已退订收件人数 / 本租户投递过的收件人数
   */
  public record NotifyDeliveryStatistics(
      BigDecimal totalDeliveries,
      BigDecimal totalRecipients,
      BigDecimal totalBlocked,
      BigDecimal totalTargeted,
      BigDecimal totalDelivered,
      BigDecimal totalFailed,
      BigDecimal deliveryRate,
      BigDecimal unsubscribeRate,
      BigDecimal distinctRecipients,
      BigDecimal unsubscribedRecipients,
      BigDecimal blockedAttempts,
      OffsetDateTime generatedAt) {}

  // ================================================================ 能力声明

  /** 一项能力（或一项「还没接通」的能力）。 */
  public record NotifyDeliveryCapability(
      String moduleId,
      String name,
      boolean available,
      String evidence,
      String ownerModule,
      String note) {}

  /** 能力声明总览。 */
  public record NotifyDeliveryCapabilities(List<NotifyDeliveryCapability> capabilities) {}

  // ================================================================ 入参

  /**
   * 一个收件人及其投递结果。
   *
   * @param outcome DELIVERED / FAILED / PENDING；为空按 PENDING 处理（结果还没回）
   * @param statusCode 渠道返回码；0 = 没拿到响应（站内信等无 HTTP 码的渠道也记 0）
   */
  public record NotifyRecipientInput(
      @NotBlank String ref,
      String outcome,
      Integer statusCode,
      String failureReason,
      Long durationMs) {}

  /** 登记一次投递。 */
  public record NotifyDeliveryInput(
      @NotNull UUID templateId,
      UUID templateVersionId,
      @NotBlank String channel,
      @NotBlank String scopeType,
      String scopeRef,
      @NotEmpty List<NotifyRecipientInput> recipients) {}

  /**
   * 发起一次重试。
   *
   * @param recipients 要重试的收件人；为空 = 全部「失败 / 待出结果」的收件人
   * @param outcomeKnown 本次重试是否已有结果；false = 结果还没回（记 PENDING）
   */
  public record NotifyRetryInput(
      List<String> recipients,
      Boolean outcomeKnown,
      Boolean success,
      Integer statusCode,
      String failureReason,
      Long durationMs,
      OffsetDateTime nextRetryAt,
      String reason) {}

  /** 登记一条退订。 */
  public record NotifyUnsubscribeInput(
      @NotBlank String recipientRef,
      @NotBlank String channel,
      @NotBlank String scopeType,
      String scopeKey,
      UUID templateId,
      String reason,
      String source) {}
}
