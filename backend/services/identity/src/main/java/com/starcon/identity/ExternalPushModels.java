package com.starcon.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 1.3.2.2 外部渠道推送 —— identity 侧的形状。
 *
 * <p>蓝图原文：「把选定的通知推送到企业 IM（企业微信/钉钉/飞书）或邮件，
 * 支持卡片化消息与点击跳转。」
 *
 * <p>🔴 <b>这一组模型里没有「凭据」这个概念，只有「凭据引用」。</b>
 *
 * <p>蓝图加粗句「**凭据不落库**，只存 secret manager 引用」在模型层的体现是：
 * 渠道的形状里只有 {@code secretRef}（引用串），**没有任何**「Webhook 地址」
 * 「access token」「密钥」字段。加一个这样的字段等于在契约里邀请别人把凭据本体传进来。
 *
 * <p>🔴 <b>投递结果有四个受控码，不是两个。</b>
 * 「成功 / 失败」两个码不够用：还有「没投，但该等」与「投了，没成，且不用再投了」——
 * 它们与「投失败还能重试」是**三件不同的事**，混成一个状态就既说不出
 * 「什么时候会再投」，也说不清「要不要告警」。
 */
public final class ExternalPushModels {

  private ExternalPushModels() {}

  // ==========================================================================
  // 一、渠道
  // ==========================================================================

  /**
   * 受控词表里的一条外部渠道。
   *
   * @param code 渠道码（WECHAT_WORK / DINGTALK / FEISHU / EMAIL）
   * @param label 中文名
   * @param note 这个渠道能干什么
   * @param connected 这个渠道**有没有已接通的投递通道**
   * @param unavailableReason 没接通时写清缺什么（connected=true 时为 null）
   */
  public record ExternalChannelOption(
      String code,
      String label,
      String note,
      boolean connected,
      @Schema(nullable = true) String unavailableReason) {}

  /**
   * 租户登记的一条外部渠道。
   *
   * @param secretRef **secret manager 引用**（不是凭据本体、不是 Webhook 地址）
   * @param credentialResolvable 这个引用现在**能不能解析出凭据**（secret manager 未接通 ⇒ 否）
   * @param credentialNote 解析不了时的说明（能解析时为 null）
   */
  public record ExternalChannel(
      UUID id,
      String channelCode,
      String displayName,
      String secretRef,
      boolean cardEnabled,
      boolean linkEnabled,
      String status,
      boolean credentialResolvable,
      @Schema(nullable = true) String credentialNote,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 渠道清单。
   *
   * @param channels 登记的渠道
   * @param enabledCount 启用中的条数
   * @param total 总条数
   */
  public record ExternalChannelList(List<ExternalChannel> channels, int enabledCount, int total) {}

  /**
   * 登记 / 改渠道的入参。
   *
   * <p>🔴 <b>刻意不接受 tenantId / userId / membershipId</b>：
   * 租户来自已认证的成员关系，请求里带 tenantId 就是把隔离边界交给客户端了。
   *
   * @param channelCode 渠道码（登记时必填）
   * @param displayName 显示名（登记时必填）
   * @param secretRef secret manager 引用（登记时必填）
   * @param cardEnabled 卡片化开关（不给 = 不改）
   * @param linkEnabled 点击跳转开关（不给 = 不改）
   * @param status 启用状态（不给 = 不改）
   */
  public record ExternalChannelInput(
      @Schema(nullable = true) String channelCode,
      @Schema(nullable = true) String displayName,
      @Schema(nullable = true) String secretRef,
      @Schema(nullable = true) Boolean cardEnabled,
      @Schema(nullable = true) Boolean linkEnabled,
      @Schema(nullable = true) String status) {}

  // ==========================================================================
  // 二、投递
  // ==========================================================================

  /**
   * 一条投递记录。
   *
   * @param sourceKind 来源：ALERT_HIT（1.3.2.1 命中）/ NOTIFICATION（1.3.1 通知投影）
   * @param status DELIVERED / FAILED / FAILED_EXHAUSTED / DEFERRED
   * @param attemptCount 已尝试次数
   * @param maxAttempts 最大尝试次数（含首次）
   * @param nextAttemptAt 下次可再试的时刻（null = 不会再自动重试）
   * @param alertState NONE / RAISED / ACKNOWLEDGED
   */
  public record ExternalDelivery(
      UUID id,
      UUID channelId,
      String channelCode,
      String channelName,
      UUID recipientMembershipId,
      String sourceKind,
      String sourceRef,
      String notificationType,
      String notificationRef,
      String title,
      @Schema(nullable = true) String body,
      @Schema(nullable = true) String linkUrl,
      String cardStyle,
      String status,
      int attemptCount,
      int maxAttempts,
      @Schema(nullable = true) String failureCode,
      @Schema(nullable = true) String failureMessage,
      @Schema(nullable = true) OffsetDateTime nextAttemptAt,
      @Schema(nullable = true) OffsetDateTime firstAttemptAt,
      @Schema(nullable = true) OffsetDateTime lastAttemptAt,
      @Schema(nullable = true) OffsetDateTime deliveredAt,
      String alertState,
      @Schema(nullable = true) OffsetDateTime alertedAt,
      @Schema(nullable = true) String alertMessage,
      OffsetDateTime createdAt) {}

  /**
   * 投递记录清单（**真分页 + 真筛选**，total 是筛选后的总数）。
   *
   * @param deliveries 当前页的记录
   * @param total 筛选后的总条数
   * @param limit 每页条数
   * @param offset 偏移量
   */
  public record ExternalDeliveryList(
      List<ExternalDelivery> deliveries, int total, int limit, int offset) {}

  /**
   * 一次推送的入参。
   *
   * <p>🔴 <b>刻意不接受 tenantId / userId</b>：租户来自已认证会话。
   *
   * @param sourceKind ALERT_HIT / NOTIFICATION
   * @param alertHitId 来源是命中时必填（服务端据此解析命中，**不允许调用方自带标题正文**）
   * @param notificationType 通知类型（受控词表，1.3.1.1 那一份）
   * @param notificationRef 上游主键（NOTIFICATION 来源必填）
   * @param title 标题（NOTIFICATION 来源必填；ALERT_HIT 来源不给则由服务端按命中事实生成）
   * @param body 正文（可空）
   * @param linkUrl 点击跳转目标（**只允许站内相对路径**）
   * @param cardStyle CARD / TEXT（不给 = CARD）
   * @param channelIds 目标渠道（**至少一个**）
   * @param recipientMembershipId 收件人（**必填**：推送要按这个人的偏好过滤）
   */
  public record ExternalPushRequest(
      @Schema(nullable = true) String sourceKind,
      @Schema(nullable = true) UUID alertHitId,
      @Schema(nullable = true) String notificationType,
      @Schema(nullable = true) String notificationRef,
      @Schema(nullable = true) String title,
      @Schema(nullable = true) String body,
      @Schema(nullable = true) String linkUrl,
      @Schema(nullable = true) String cardStyle,
      @Schema(nullable = true) List<UUID> channelIds,
      @Schema(nullable = true) UUID recipientMembershipId) {}

  /**
   * 一次推送的结果（**一个渠道一条记录**）。
   *
   * @param accepted 已受理（进了投递流程）的条数
   * @param deferred 因免打扰时段推迟的条数
   * @param failed 这次没投出去的条数
   */
  public record ExternalPushResult(
      List<ExternalDelivery> deliveries, int accepted, int deferred, int failed) {}

  /**
   * 一条可推送的候选通知。
   *
   * @param sourceKind ALERT_HIT / NOTIFICATION
   * @param sourceRef 来源定位（命中 id / 通知键）
   * @param notificationType 通知类型（**命中来源为 null** —— 规则命中不带通知类型，
   *     分类由推送时选定，见 {@code ExternalPushCandidate} 的类注释）
   * @param notificationRef 上游主键
   * @param title 标题（**服务端按真实事实生成**，不是调用方编的）
   * @param body 正文
   * @param linkUrl 站内跳转目标
   * @param objectType 对象类型（命中来源才有）
   * @param objectRef 对象标识（命中来源才有）
   * @param occurredAt 发生时刻
   */
  public record ExternalPushCandidate(
      String sourceKind,
      String sourceRef,
      @Schema(nullable = true) String notificationType,
      String notificationRef,
      String title,
      @Schema(nullable = true) String body,
      @Schema(nullable = true) String linkUrl,
      @Schema(nullable = true) String objectType,
      @Schema(nullable = true) String objectRef,
      OffsetDateTime occurredAt) {}

  /**
   * 候选清单。
   *
   * @param candidates 候选
   * @param total 候选总数
   * @param sourceNote 候选**从哪来**（NOTIFICATION 来源不在这里 —— 说明白）
   */
  public record ExternalPushCandidates(
      List<ExternalPushCandidate> candidates, int total, String sourceNote) {}

  /**
   * 到期扫描（**自动重试**）的结果。
   *
   * @param scanned 扫到的到期条数
   * @param retried 真的又试了一次的条数
   * @param delivered 这次投成功的条数
   * @param failed 这次没投出去的条数
   * @param deferred 这次仍被免打扰压制的条数
   */
  public record ExternalRetrySweep(
      int scanned, int retried, int delivered, int failed, int deferred) {}

  // ==========================================================================
  // 三、能力声明
  // ==========================================================================

  /** 能力声明的一维（做得到 / 没做到 / 归谁 / 缺什么）。 */
  public record ExternalPushCapabilityDimension(
      String code, boolean available, String ownerModule, String impact, List<String> missing) {}

  /**
   * 能力声明。
   *
   * @param channels 受控渠道词表（逐条标这个渠道有没有接通的投递通道）
   * @param secretManagerAvailable secret manager 是否已接通
   * @param secretManagerOwnerModule 归哪个模块
   * @param secretManagerNote 未接通时的说明
   * @param deliveryAvailable 外部投递通道是否已接通
   * @param deliveryOwnerModule 归哪个模块
   * @param deliveryNote 未接通时的说明
   * @param templateAvailable 消息模板管理是否已交付
   * @param templateOwnerModule 归哪个模块
   * @param templateNote 未交付时的说明
   * @param cardStyles 支持的卡片样式
   * @param dimensions 逐条能力声明
   * @param boundaries 本模块的边界（明说哪些不归这里）
   */
  public record ExternalPushCapabilities(
      String module,
      List<ExternalChannelOption> channels,
      boolean secretManagerAvailable,
      String secretManagerOwnerModule,
      String secretManagerNote,
      boolean deliveryAvailable,
      String deliveryOwnerModule,
      String deliveryNote,
      boolean templateAvailable,
      String templateOwnerModule,
      String templateNote,
      List<String> cardStyles,
      List<ExternalPushCapabilityDimension> dimensions,
      List<String> boundaries) {}
}
