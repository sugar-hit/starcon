package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 15.1.1.1 消息收件箱的传输模型。
 *
 * <p>蓝图 15 卷 15.1.1.1 原文：
 *
 * <blockquote>
 *
 * 职责：集中展示各店铺的平台站内信与买家消息：店铺、买家、主题、正文、附件、平台时间、收发方向、已读状态、关联订单，支持按店铺/类型/优先级/未读筛选与批量处理。 输入：平台消息同步（webhook + 拉取）；用户筛选条件；数据范围（店铺维度）。 输出：消息列表 + 详情视图 + 关联订单上下文。 关系：上游 = 2 渠道与店铺（同步）；下游 = 15.1.1.2 回复、11 售后（转售后）、15.4 工单。 <b>消息必须关联到买家与订单</b>，否则客服看到消息还要去猜是哪一单。
 *
 * </blockquote>
 *
 * <p><b>建模纪律</b>：
 *
 * <ul>
 *   <li><b>买家与订单是必填，不是可选</b>：{@code buyerReference} / {@code orderReference}
 *       在 V291 里是 NOT NULL + CHECK（空串也不行），模型层用 {@code @NotBlank} 与之一致 ——
 *       蓝图加粗句说「消息必须关联到买家与订单」，那是<b>硬要求</b>，
 *       做成可选输入框等于把它降级成「建议填」。</li>
 *   <li><b>金额与数量原样透传</b>：订单上下文里的金额是 {@link BigDecimal}（trade 侧 numeric），
 *       前端<b>只原样展示</b>，不做任何 JS 侧算术。</li>
 *   <li><b>时间一律带时区偏移的 ISO-8601 字符串</b>：用 {@code Timestamp.valueOf(LocalDateTime)}
 *       会在东八区静默偏 −28800 秒，本仓库反复踩过。</li>
 *   <li><b>「查不到」也是一种结论</b>：{@link CrmMessageOrderContext} 带 {@code available} + {@code reason}，
 *       查不到订单上下文时页面显示「不可用 + 原因」，<b>不显示空白</b>。</li>
 * </ul>
 */
public final class MessageInboxModels {

  private MessageInboxModels() {}

  // ------------------------------------------------------------ 输入

  /** 一个附件的登记信息。 */
  public record CrmMessageAttachmentInput(
      @NotBlank @Size(max = 300)
          @Schema(description = "文件名（必填）：附件没有名字就点不开")
          String fileName,
      @Size(max = 1000) @Schema(description = "附件链接（对象存储地址等），可空") String fileUrl,
      @Size(max = 120) @Schema(description = "MIME 类型，可空") String contentType,
      @Schema(nullable = true, description = "字节数（0 表示未登记）；与本仓其它字节列同精度 numeric(19,6)")
          BigDecimal sizeBytes,
      @Size(max = 500) String note) {}

  /**
   * 登记一条平台消息 / 买家消息（蓝图输入句里的「平台消息同步」在当前基线下的**人工入口**）。
   *
   * <p>🔴 {@code buyerReference} 与 {@code orderReference} **必填**（蓝图加粗句）。
   *
   * <p>{@code source} 只允许 {@code MANUAL_ENTRY}：平台侧买家站内信适配器**未交付**
   * （channel/vendor 下只有 OAuth 适配器），写 {@code PLATFORM_SYNC} 会被服务端拒绝。
   */
  public record CrmMessageCreateInput(
      @NotNull UUID shopId,
      @NotBlank @Size(max = 128)
          @Schema(description = "买家标识（平台买家号等）；本仓没有买家档案，只作逻辑引用")
          String buyerReference,
      @NotBlank @Size(max = 64)
          @Schema(description = "订单标识（平台订单号或本仓销售单号）；蓝图要求消息必须关联订单")
          String orderReference,
      @Size(max = 400) String subject,
      @Size(max = 20000) String body,
      @NotBlank @Pattern(regexp = "INBOUND|OUTBOUND") String direction,
      @NotBlank
          @Pattern(regexp = "PLATFORM_INBOX|BUYER_MESSAGE|SYSTEM_NOTICE|COMPLIANCE")
          String messageType,
      @Pattern(regexp = "LOW|NORMAL|HIGH|URGENT") String priority,
      @NotBlank
          @Schema(description = "平台侧时刻，例如 2026-10-10T08:00:00+08:00")
          String platformTime,
      @Valid List<CrmMessageAttachmentInput> attachments,
      @Schema(nullable = true)
          @Size(max = 128)
          @Pattern(regexp = "^$|^[A-Za-z0-9._:-]+$")
          String externalMessageId,
      @Size(max = 500) String note) {}

  /** 批量处理的动作（蓝图：「支持按店铺/类型/优先级/未读筛选与批量处理」）。 */
  public record CrmMessageBatchInput(
      @NotBlank
          @Pattern(regexp = "MARK_READ|MARK_UNREAD|ARCHIVE|SET_PRIORITY|RECORD_INTENT|CANCEL_INTENT")
          String action,
      @NotEmpty @Size(max = 200) List<UUID> messageIds,
      @Pattern(regexp = "LOW|NORMAL|HIGH|URGENT") String priority,
      @Pattern(regexp = "AFTERSALE_CASE|SERVICE_TICKET") String intentType,
      @Size(max = 600) String reason) {}

  /** 平台消息同步的一次尝试（当前**未接入**，逐平台答「未接入」且**不写任何数据**）。 */
  public record CrmMessageSyncInput(
      @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") String platformCode,
      @NotNull UUID shopId,
      @Schema(nullable = true) String since) {}

  // ------------------------------------------------------------ 输出

  /** 能力维度：每一维都要说清「有没有、依据是什么、缺了会怎样」。 */
  public record CrmMessageCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  public record CrmMessageCapabilities(
      List<CrmMessageCapabilityDimension> dimensions,
      List<String> readStatuses,
      List<String> directions,
      List<String> messageTypes,
      List<String> priorities,
      List<String> sources,
      List<String> batchActions,
      List<String> intentTypes,
      String boundaryNote,
      String highlight) {}

  /** 店铺目录的一行（来自 channel 的真实清单，不是前端写死的常量）。 */
  public record CrmMessageShopView(
      UUID id, String code, String name, String platformCode, String platformName, String status) {}

  /** 一条消息（列表行 / 详情本体）。 */
  public record CrmMessageRow(
      UUID id,
      String messageNo,
      UUID shopId,
      String shopName,
      String platformCode,
      String buyerReference,
      String orderReference,
      String subject,
      String body,
      String direction,
      String messageType,
      String priority,
      String readStatus,
      @Schema(nullable = true) String readAt,
      @Schema(nullable = true) UUID readBy,
      String platformTime,
      Integer attachmentCount,
      String source,
      String externalMessageId,
      UUID createdBy,
      String note,
      String createdAt,
      String updatedAt) {}

  /** 一个附件。 */
  public record CrmMessageAttachment(
      UUID id,
      UUID messageId,
      String fileName,
      String fileUrl,
      String contentType,
      BigDecimal sizeBytes,
      String createdAt) {}

  /**
   * 关联订单的上下文（来自 trade 服务的只读端口）。
   *
   * <p>🔴 {@code available=false} 时 {@code reason} **必填**：页面必须显示
   * 「订单上下文不可用 + 原因」，**不显示空白** —— 空白会被客服读成
   * 「这单没有信息」，而不是「我们没查到」。
   */
  public record CrmMessageOrderContext(
      boolean available,
      @Schema(nullable = true) String orderNo,
      @Schema(nullable = true) String externalOrderNo,
      @Schema(nullable = true) String orderSource,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) BigDecimal grandTotalAmount,
      @Schema(nullable = true) Integer lineCount,
      @Schema(nullable = true) String buyerName,
      @Schema(nullable = true) List<String> skuCodes,
      @Schema(nullable = true) String documentStatus,
      @Schema(nullable = true) String platformStatus,
      @Schema(nullable = true) String paymentStatus,
      @Schema(nullable = true) String fulfillmentStatus,
      @Schema(nullable = true) String shippingWarehouse,
      @Schema(nullable = true) String aftersalesStatus,
      @Schema(nullable = true) String submittedAt,
      @Schema(nullable = true) String reason) {}

  /** 一条处置意图（转售后 / 建工单）。⚠️ **不含下游单据 id** —— 下游未交付就没有 id。 */
  public record CrmMessageIntent(
      UUID id,
      UUID messageId,
      String intentType,
      String status,
      String reason,
      UUID requestedBy,
      String createdAt,
      String updatedAt) {}

  /** 单条消息详情 = 消息本体 + 附件 + 关联订单上下文 + 处置意图。 */
  public record CrmMessageDetail(
      CrmMessageRow message,
      List<CrmMessageAttachment> attachments,
      CrmMessageOrderContext orderContext,
      List<CrmMessageIntent> intents) {}

  /** 一档统计（优先级 / 类型分组）。 */
  public record CrmMessageBucket(String code, String label, long count) {}

  /** 按店铺分组的统计。 */
  public record CrmMessageShopBucket(
      UUID shopId, String shopName, String platformCode, long count, long unreadCount) {}

  /**
   * 收件箱汇总（与分页无关的全量口径）。
   *
   * <p>⚠️ 全部计数由**服务端**算好：前端只原样展示，不做 JS 侧算术
   * （AGENTS.md：金额与数量在 JS 侧只原样展示）。
   */
  public record CrmMessageSummary(
      long totalCount,
      long unreadCount,
      long readCount,
      long archivedCount,
      long withAttachmentCount,
      List<CrmMessageBucket> byPriority,
      List<CrmMessageBucket> byType,
      List<CrmMessageShopBucket> byShop) {}

  /** 消息列表页：真分页（LIMIT/OFFSET + 独立 COUNT(*)）+ 全量汇总。 */
  public record CrmMessagePage(
      List<CrmMessageRow> items, int page, int size, long total, CrmMessageSummary summary) {}

  /** 批量处理的逐条结果。 */
  public record CrmMessageBatchItem(UUID messageId, String messageNo, boolean success, String reason) {}

  /** 批量处理的汇总。 */
  public record CrmMessageBatchResult(
      String action,
      int requestedCount,
      int succeededCount,
      int failedCount,
      List<CrmMessageBatchItem> items,
      String boundaryNote) {}

  /** 筛选下拉的词表（平台 / 类型 / 优先级 / 方向 / 状态 / 店铺，**来自本租户真实数据**）。 */
  public record CrmMessageOptions(
      List<String> platformCodes,
      List<String> messageTypes,
      List<String> priorities,
      List<String> directions,
      List<String> readStatuses,
      List<CrmMessageShopBucket> shops,
      String note) {}

  /** 平台消息同步的一次尝试结果。 */
  public record CrmMessageSyncAttempt(
      String platformCode,
      UUID shopId,
      boolean connected,
      int fetchedCount,
      int writtenCount,
      String message,
      String ownerModule) {}
}
