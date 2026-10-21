package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 15.1.2.1 邮件收发（买家邮件）的传输模型。
 *
 * <p>蓝图 15 卷 15.1.2.1 原文：
 *
 * <blockquote>
 *
 * 职责：管理买家邮件（Amazon Buyer-Seller Messaging、eBay 邮件等）：收发、附件、模板、与站内信的双向同步或标记为同源。
 * 输入：邮件同步；发送请求。 输出：邮件列表与详情 + 发送记录。
 * 关系：上游 = 2 同步；下游 = 15.1.1.2 发送。 <b>部分平台要求邮件与站内信在同一会话处理</b>，分开展示会导致重复回复买家。
 *
 * </blockquote>
 *
 * <p><b>建模纪律</b>：
 *
 * <ul>
 *   <li><b>会话键是本模块的主心骨</b>：{@code conversationKey} 是「同一买家 + 同一订单 + 同一店铺」
 *       的自然键。它是「邮件与站内信<b>标记为同源</b>」的落点，也是会话视图的查询键。
 *       15.1.1.1 消息收件箱同批并行开发，它的表**不在本模块基线里** ⇒
 *       {@code sourceMessageId} 是<b>软引用</b>（<b>不加外键</b>），同源靠会话键相等来表达。</li>
 *   <li><b>收发两个方向都在邮件表里</b>：加粗句要求「同一会话处理」，会话里只有收到的邮件、
 *       没有发出的邮件，客服照样会以为「还没回过」⇒ 照样重复回复。</li>
 *   <li><b>数量一律 BigDecimal</b>：附件字节数用 {@code numeric(19,6)}（与 channel V247 / V255、
 *       trade V239 的 {@code size_bytes} 同一形状），<b>禁浮点</b>。</li>
 *   <li><b>时间一律带偏移量的 ISO-8601 字符串</b>：用 {@code Timestamp.valueOf(LocalDateTime)}
 *       会在东八区静默偏 −28800 秒，本仓库反复踩过。</li>
 * </ul>
 */
public final class BuyerEmailModels {

  private BuyerEmailModels() {}

  // ------------------------------------------------------------ 输入

  /**
   * 登记一封**收到的**买家邮件。
   *
   * <p>方向不在输入里：本端点只登记**收到**的邮件，发出的邮件由「发送记录」在
   * {@code status='SENT'} 时自动落成（与收到的那封共用同一个会话键）。
   *
   * <p>{@code conversationKey} 留空时由服务端按
   * {@code <shopId>|<buyerRef 小写>|<salesOrderNo 小写>} 推导（无订单号时第三段写 {@code '-'}）；
   * 平台给了线程号时可以显式传入，**以显式值为准**。
   */
  public record CrmBuyerEmailCreateInput(
      @NotNull UUID shopId,
      @NotBlank @Size(max = 128) String buyerRef,
      @Size(max = 200) String buyerName,
      @Size(max = 320) String buyerEmail,
      @Size(max = 500) String subject,
      @Size(max = 20000) String body,
      @Size(max = 64) String salesOrderNo,
      @Size(max = 200) String externalMessageId,
      @Size(max = 300) String conversationKey,
      @Schema(
              nullable = true,
              description = "站内信主键（15.1.1.1，未交付）：软引用，**不建外键**")
          UUID sourceMessageId,
      @NotBlank
          @Schema(description = "买家在平台上的邮件时刻，例如 2026-10-10T08:00:00+08:00")
          String receivedAt,
      @Size(max = 500) String note) {}

  /** 登记一个附件的**元数据**（本服务不持有文件字节）。 */
  public record CrmBuyerEmailAttachmentInput(
      @NotBlank @Size(max = 300) String fileName,
      @Size(max = 120) String contentType,
      @NotNull @DecimalMin(value = "0") BigDecimal sizeBytes,
      @Size(max = 400) String storageKey,
      @Pattern(regexp = "^[0-9a-fA-F]{0,64}$", message = "校验和必须是十六进制 sha256（最多 64 位）")
          String checksumSha256) {}

  /**
   * 登记一次发送（= 蓝图输出句里的「发送记录」）。
   *
   * <p>🔴 {@code transport} 只能填 {@code MANUAL_HANDOFF}：平台侧邮件发送接口<b>未接入</b>
   * （capabilities 里 {@code platform_email_api} 维度 available=false），服务层会拒绝
   * {@code PLATFORM_API} —— 一个「返回成功回执」的假通道会让「买家已经收到回复了」
   * 在数据上看起来发生过，而重复回复的代价正是平台违规与买家投诉。
   *
   * <p>🔴 {@code status='FAILED'} 时**必须**带 {@code failureReason}；而且
   * <b>失败不改原邮件状态</b>（收到的那封邮件仍是 {@code RECEIVED}）——
   * 这与 15.1.1.2 蓝图里「发送失败要保留原消息状态」是同一条红线，邮件侧同样适用。
   * 失败由<b>发出去的那一行</b>（OUTBOUND + {@code status='FAILED'}）如实记着。
   *
   * <p>{@code subject} / {@code body} 留空时取所引用模板的同名内容；
   * 两者都留空且没有模板 ⇒ 这是一次没有正文的登记，服务层会拒（400）。
   *
   * <p>{@code attachments} 是**这次回复带出去的附件元数据**，它们挂在
   * **发出去的那封 OUTBOUND 邮件**上（不是挂在收到的那封上 ——
   * 买家寄来的附件与我们的附件不能混在一张清单里）。
   */
  public record CrmBuyerEmailSendInput(
      @NotBlank String transport,
      @NotBlank String status,
      @Size(max = 500) String subject,
      @Size(max = 20000) String body,
      @Schema(nullable = true) UUID templateId,
      @Size(max = 500) String platformReceipt,
      @Size(max = 600) String failureReason,
      @Schema(nullable = true) @Valid List<CrmBuyerEmailAttachmentInput> attachments,
      @Size(max = 500) String note) {}

  /** 邮件模板（本模块自己的，不动 15.4 知识库与 15.1.1.2）。 */
  public record CrmBuyerEmailTemplateInput(
      @NotBlank @Size(max = 64) String templateCode,
      @NotBlank @Size(max = 200) String name,
      @Pattern(regexp = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$", message = "语言标签必须是 BCP-47 简写（如 zh-CN / en-US）")
          String locale,
      @Size(max = 500) String subject,
      @Size(max = 20000) String body,
      @Schema(nullable = true) UUID shopId,
      String status,
      @Size(max = 500) String note) {}

  /** 平台邮件同步的入参（当前恒为 409，因为平台适配器未交付）。 */
  public record CrmBuyerEmailSyncInput(@NotNull UUID shopId) {}

  // ------------------------------------------------------------ 输出

  /** 能力维度：每一维都要说清「有没有、依据是什么、缺了会怎样」。 */
  public record CrmBuyerEmailCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  public record CrmBuyerEmailCapabilities(
      List<CrmBuyerEmailCapabilityDimension> dimensions,
      List<String> directions,
      List<String> emailStatuses,
      List<String> sources,
      List<String> transports,
      List<String> sendStatuses,
      String conversationKeySpec,
      String boundaryNote,
      String highlight) {}

  /** 一封邮件（收 / 发两个方向都在这里）。 */
  public record CrmBuyerEmailRow(
      UUID id,
      String emailNo,
      UUID shopId,
      String shopName,
      String platformCode,
      String direction,
      String source,
      String buyerRef,
      String buyerName,
      String buyerEmail,
      String subject,
      String body,
      String conversationKey,
      @Schema(nullable = true) UUID sourceMessageId,
      @Schema(nullable = true) UUID replyToEmailId,
      String salesOrderNo,
      String externalMessageId,
      String receivedAt,
      String status,
      Long attachmentCount,
      Long sendRecordCount,
      UUID createdBy,
      String note,
      String createdAt,
      String updatedAt) {}

  /** 邮件列表分页（真分页：总数与页数都从真实查询来）。 */
  public record CrmBuyerEmailPage(
      List<CrmBuyerEmailRow> items, int page, int size, long totalElements, int totalPages) {}

  /** 一个附件的元数据。 */
  public record CrmBuyerEmailAttachment(
      UUID id,
      UUID emailId,
      String fileName,
      String contentType,
      BigDecimal sizeBytes,
      String storageKey,
      String checksumSha256,
      String createdAt) {}

  /** 一条发送记录（= 蓝图输出句里的「发送记录」）。 */
  public record CrmBuyerEmailSendRecord(
      UUID id,
      UUID emailId,
      @Schema(nullable = true) UUID templateId,
      String templateCode,
      String transport,
      String status,
      String subject,
      String body,
      String platformReceipt,
      @Schema(nullable = true) String attemptedAt,
      String failureReason,
      UUID operatorId,
      String createdAt) {}

  /** 单封邮件详情 = 邮件本体 + 附件 + 发送记录 + 同会话邮件。 */
  public record CrmBuyerEmailDetail(
      CrmBuyerEmailRow email,
      List<CrmBuyerEmailAttachment> attachments,
      List<CrmBuyerEmailSendRecord> sendRecords,
      List<CrmBuyerEmailRow> conversation) {}

  /**
   * 会话视图（加粗句「同一会话处理」的落点）。
   *
   * <p>🔴 {@code inboxThread} 恒为 {@code available=false}：站内信原文属 15.1.1.1，
   * 同批并行开发，**不在本模块基线里**。本视图**不假装**能看到站内信 ——
   * 同源靠 {@code conversationKey} 相等来标记，等它落地后两边按同一公式对齐。
   */
  public record CrmBuyerEmailConversation(
      String conversationKey,
      String buyerRef,
      String salesOrderNo,
      List<CrmBuyerEmailRow> emails,
      List<CrmBuyerEmailSendRecord> sendRecords,
      CrmBuyerEmailCapabilityDimension inboxThread,
      String note) {}

  /** 一条邮件模板。 */
  public record CrmBuyerEmailTemplateRow(
      UUID id,
      String templateCode,
      String name,
      String locale,
      String subject,
      String body,
      @Schema(nullable = true) UUID shopId,
      String status,
      Long usageCount,
      UUID createdBy,
      String note,
      String createdAt,
      String updatedAt) {}

  /** 邮件模板分页。 */
  public record CrmBuyerEmailTemplatePage(
      List<CrmBuyerEmailTemplateRow> items, int page, int size, long totalElements, int totalPages) {}

  /** 店铺目录的一行（来自 channel 的真实清单）+ 该平台的买家邮件接入状态。 */
  public record CrmBuyerEmailShopView(
      UUID id,
      String code,
      String name,
      String platformCode,
      String platformName,
      String status,
      boolean platformEmailAvailable,
      String platformEmailNote) {}

  /**
   * 一次发送的结果 = 发送记录 + **发出去的那封邮件**。
   *
   * <p>为什么把 {@code outboundEmail} 一起返回：会话视图里「我们回过没有」靠的是
   * OUTBOUND 那一行；把它随发送记录一起返回，页面就不必再猜一次。
   */
  public record CrmBuyerEmailSendResult(
      CrmBuyerEmailSendRecord sendRecord,
      @Schema(nullable = true) CrmBuyerEmailRow outboundEmail,
      String note) {}

  /**
   * 平台邮件同步的结果。
   *
   * <p>🔴 当前**恒为 409**：平台买家邮件适配器未交付（见 {@code PlatformEmailPort}），
   * 同步请求**不写任何数据**。这个记录只在适配器接上之后才会有 {@code synced > 0}。
   */
  public record CrmBuyerEmailSyncResult(
      int synced,
      boolean available,
      String reason,
      String note) {}
}
