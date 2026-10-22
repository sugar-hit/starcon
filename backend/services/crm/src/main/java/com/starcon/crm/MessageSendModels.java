package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 15.1.1.2 消息发送的传输模型。
 *
 * <p>蓝图 15 卷 15.1.1.2 原文：
 *
 * <blockquote>
 *
 * 职责：回复买家消息：调用模板、手工编辑、附件支持、发送记录、失败重试；支持批量相同回复（同类问题）。 输入：回复内容（模板或手工）；目标消息；附件；idempotency_key。 输出：发送记录 + 平台回执 + 失败原因。 关系：上游 = 15.1.1.1、15.4.2 知识库；下游 = 平台消息接口。 <b>发送失败要保留原消息状态</b>，不能让客服以为已回复。
 *
 * </blockquote>
 *
 * <p><b>建模纪律</b>：
 *
 * <ul>
 *   <li><b>幂等键必填</b>（蓝图输入句里点名了 {@code idempotency_key}）：
 *       服务端在 DB 层用 {@code UNIQUE (tenant_id, idempotency_key)} 兜底，
 *       重放同一条键**不会**产生第二条发送记录 —— 而「重复回复买家」的代价是
 *       平台违规与买家投诉。</li>
 *   <li><b>正文来源要留痕</b>：{@code bodySource} 回答「这句到底是模板原话、
 *       模板改过的、还是手写的」，事后复核时不必猜。</li>
 *   <li><b>失败原因必填</b>（蓝图输出句里点名了失败原因）：
 *       只写「失败」而不写为什么，事后无法判断该不该重试。</li>
 *   <li><b>「已回复」不由发送模块写回源消息</b>：它由「存在一条 SENT 的发送记录」
 *       推导。这样发送失败时源消息的状态**天然不变** ——
 *       没有那一列可以被改，也就没有「忘了改回来」这种事故。</li>
 * </ul>
 */
public final class MessageSendModels {

  private MessageSendModels() {}

  // ------------------------------------------------------------ 输入

  /** 一个附件的登记信息（附件字节归平台侧与 8 仓储，本服务只记元数据）。 */
  public record CrmMessageSendAttachmentInput(
      @NotBlank @Size(max = 300)
          @Schema(description = "文件名（必填）：附件没有名字就点不开")
          String fileName,
      @Size(max = 1000) @Schema(description = "附件链接（对象存储地址等），可空") String fileUrl,
      @Size(max = 120) @Schema(description = "MIME 类型，可空") String contentType,
      @Schema(nullable = true, description = "字节数（0 表示未登记）；与本仓其它字节列同精度 numeric(19,6)")
          BigDecimal sizeBytes,
      @Size(max = 500) String note) {}

  /**
   * 登记一次发送（蓝图输入句：回复内容（模板或手工）；目标消息；附件；idempotency_key）。
   *
   * <p>正文解析规则（服务端唯一判定，前端不自己解释）：
   *
   * <ul>
   *   <li>给了 {@code templateId} → 取模板并渲染；<b>同时给了非空 {@code body}</b>
   *       则以请求里的正文为准（= 模板起稿 + 手工编辑），来源记 TEMPLATE_EDITED；
   *   <li>给了 {@code knowledgeVersionId} → 取知识版本正文，来源记 KNOWLEDGE；
   *   <li>都没有 → 用请求里的 {@code subject} / {@code body}，来源记 MANUAL；
   *   <li>三者都没有可用正文 → 拒绝（400），不留一条空回复的发送记录。</li>
   * </ul>
   */
  public record CrmMessageSendInput(
      @NotNull @Schema(description = "回复的目标消息（15.1.1.1 的 crm_message.id）")
          UUID targetMessageId,
      @Schema(nullable = true, description = "回复模板 id（15.1.1.2 自有模板）") UUID templateId,
      @Schema(nullable = true, description = "知识库版本 id（15.4.2 上游）") UUID knowledgeVersionId,
      @Size(max = 500) String subject,
      @Size(max = 20000) String body,
      @NotBlank
          @Pattern(
              regexp = "MANUAL_HANDOFF|PLATFORM_API",
              message = "触达通道只允许 MANUAL_HANDOFF（人工交接）；PLATFORM_API 未接入，服务端会拒绝")
          String transport,
      @Pattern(regexp = "QUEUED|SENT|FAILED")
          @Schema(description = "登记结果：QUEUED 待发（默认）· SENT 已发出 · FAILED 失败")
          String status,
      @Size(max = 2000) @Schema(description = "失败原因（status=FAILED 时必填）") String failureReason,
      @Size(max = 2000) @Schema(description = "平台回执（人工交接时由运营登记）") String platformReceipt,
      @Size(max = 128) @Schema(description = "平台侧消息号（人工交接时由运营登记）")
          String platformMessageId,
      @Valid List<CrmMessageSendAttachmentInput> attachments,
      @NotBlank
          @Size(max = 200)
          @Schema(description = "幂等键（同租户内唯一）：重放同一条键不会产生第二条发送记录")
          String idempotencyKey,
      @Size(max = 500) String note) {}

  /**
   * 批量相同回复（蓝图：「支持批量相同回复（同类问题）」）。
   *
   * <p>同一个正文发给多条消息；每条消息各有一条发送记录，共享一个批次号。
   */
  public record CrmMessageSendBatchInput(
      @NotEmpty @Size(max = 200) List<UUID> messageIds,
      @Schema(nullable = true) UUID templateId,
      @Schema(nullable = true) UUID knowledgeVersionId,
      @Size(max = 500) String subject,
      @Size(max = 20000) String body,
      @NotBlank @Pattern(regexp = "MANUAL_HANDOFF|PLATFORM_API") String transport,
      @Valid List<CrmMessageSendAttachmentInput> attachments,
      @NotBlank
          @Size(max = 200)
          @Schema(description = "批次幂等键；每条消息的幂等键 = 批次键 + '#' + 消息 id")
          String idempotencyKey,
      @Size(max = 500) String note) {}

  /** 登记一次投递的结果（人工交接：运营在平台侧实际发出/失败之后回登记）。 */
  public record CrmMessageSendResultInput(
      @NotBlank @Pattern(regexp = "SENT|FAILED") String status,
      @Size(max = 2000) @Schema(description = "失败原因（status=FAILED 时必填）") String failureReason,
      @Size(max = 2000) String platformReceipt,
      @Size(max = 128) String platformMessageId,
      @Size(max = 500) String operatorNote) {}

  /** 重试（蓝图：失败重试）。不改正文，只开一次新的尝试。 */
  public record CrmMessageSendRetryInput(@Size(max = 500) String operatorNote) {}

  /** 回复模板的登记内容（调用模板 / 手工编辑成模板）。 */
  public record CrmMessageSendTemplateInput(
      @NotBlank @Size(max = 64)
          @Schema(description = "业务键（同租户内唯一）")
          String templateCode,
      @NotBlank @Size(max = 200) String name,
      @NotBlank
          @Pattern(
              regexp = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$",
              message = "语言要用 BCP-47 简写，例如 zh-CN / en-US")
          String locale,
      @Size(max = 500) String subject,
      @Size(max = 20000) String body,
      @Schema(nullable = true, description = "适用店铺；空 = 本租户全部店铺") UUID shopId,
      @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") String platformCode,
      @Pattern(regexp = "ACTIVE|DISABLED") String status,
      @Size(max = 500) String note) {}

  // ------------------------------------------------------------ 输出

  /** 能力维度：每一维都要说清「有没有、依据是什么、缺了会怎样」。 */
  public record CrmMessageSendCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  public record CrmMessageSendCapabilities(
      List<CrmMessageSendCapabilityDimension> dimensions,
      List<String> transports,
      List<String> statuses,
      List<String> bodySources,
      List<String> templateVariables,
      String boundaryNote,
      String highlight) {}

  /** 一条回复模板。 */
  public record CrmMessageSendTemplateRow(
      UUID id,
      String templateCode,
      String name,
      String locale,
      String subject,
      String body,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) String platformCode,
      String status,
      String note,
      String createdAt,
      String updatedAt) {}

  /** 模板列表页：真分页（LIMIT/OFFSET + 独立 COUNT(*)）。 */
  public record CrmMessageSendTemplatePage(
      List<CrmMessageSendTemplateRow> items, int page, int size, long total) {}

  /**
   * 一条知识库条目（15.4.2 上游）可作为回复模板来源。
   *
   * <p>⚠️ 只列**此刻生效**的版本 —— 草稿版本没有生效过，
   * 拿它答复买家等于在引用一段从未给过买家的内容。
   */
  public record CrmMessageSendKnowledgeTemplate(
      UUID entryId,
      UUID versionId,
      int versionNo,
      String title,
      String topicKey,
      String locale,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) String platformCode,
      String question,
      String answer,
      String effectiveFrom) {}

  /** 一个发送附件。 */
  public record CrmMessageSendAttachment(
      UUID id,
      UUID sendId,
      String fileName,
      String fileUrl,
      String contentType,
      BigDecimal sizeBytes,
      String createdAt) {}

  /** 一次发送尝试（失败重试留痕）。 */
  public record CrmMessageSendAttempt(
      UUID id,
      UUID sendId,
      int attemptNo,
      String status,
      String failureReason,
      String platformReceipt,
      String platformMessageId,
      String attemptedAt,
      String operatorNote) {}

  /** 一条发送记录（列表行 / 详情本体）。 */
  public record CrmMessageSendRow(
      UUID id,
      String sendNo,
      UUID targetMessageId,
      UUID shopId,
      String shopName,
      String platformCode,
      String buyerReference,
      String orderReference,
      @Schema(nullable = true) UUID templateId,
      String templateCode,
      @Schema(nullable = true) UUID knowledgeEntryId,
      @Schema(nullable = true) UUID knowledgeVersionId,
      int knowledgeVersionNo,
      String subject,
      String body,
      String bodySource,
      String transport,
      String status,
      String failureReason,
      String platformReceipt,
      String platformMessageId,
      int attachmentCount,
      int attemptCount,
      @Schema(nullable = true) String lastAttemptAt,
      @Schema(nullable = true) String sentAt,
      String idempotencyKey,
      @Schema(nullable = true) UUID batchId,
      UUID createdBy,
      String note,
      String createdAt,
      String updatedAt) {}

  /**
   * 目标消息的**已回复状态**（由发送记录推导，不写在源消息上）。
   *
   * <p>🔴 {@code replied=false} 时表示「这条消息没有一条 SENT 的发送记录」——
   * 也就是说**还没真的回出去**。发送失败时它**保持 false**：
   * 源消息的状态没有被改过，客服不会看到「已回复」而实际没发出去。
   */
  public record CrmMessageTargetState(
      UUID messageId,
      String messageNo,
      String repliedState,
      @Schema(nullable = true) UUID repliedSendId,
      @Schema(nullable = true) String repliedSendNo,
      @Schema(nullable = true) String lastSendStatus,
      @Schema(nullable = true) String lastFailureReason,
      String note) {}

  /** 一次发送的结果（含幂等重放标记与目标消息的已回复状态）。 */
  public record CrmMessageSendOutcome(
      CrmMessageSendRow send,
      boolean replayed,
      CrmMessageTargetState target,
      String message) {}

  /** 发送记录详情 = 发送本体 + 附件 + 尝试留痕 + 目标消息状态。 */
  public record CrmMessageSendDetail(
      CrmMessageSendRow send,
      List<CrmMessageSendAttachment> attachments,
      List<CrmMessageSendAttempt> attempts,
      CrmMessageTargetState target) {}

  /** 可回复的目标消息（来自 15.1.1.1 收件箱，带推导出的已回复状态）。 */
  public record CrmMessageSendTarget(
      UUID messageId,
      String messageNo,
      UUID shopId,
      String shopName,
      String platformCode,
      String buyerReference,
      String orderReference,
      String subject,
      String platformTime,
      String repliedState,
      @Schema(nullable = true) UUID repliedSendId,
      @Schema(nullable = true) String repliedSendNo,
      @Schema(nullable = true) String lastSendStatus,
      @Schema(nullable = true) String lastFailureReason) {}

  /** 可回复目标列表页：真分页 + 真筛选。 */
  public record CrmMessageSendTargetPage(
      List<CrmMessageSendTarget> items, int page, int size, long total, long unrepliedCount) {}

  /** 一档统计（状态分组）。 */
  public record CrmMessageSendBucket(String code, String label, long count) {}

  /** 发送记录汇总（与分页无关的全量口径）。 */
  public record CrmMessageSendSummary(
      long totalCount,
      long sentCount,
      long failedCount,
      long queuedCount,
      long cancelledCount,
      List<CrmMessageSendBucket> byStatus,
      List<CrmMessageSendBucket> byTransport) {}

  /** 发送记录列表页：真分页 + 真筛选 + 与分页无关的全量汇总。 */
  public record CrmMessageSendPage(
      List<CrmMessageSendRow> items,
      int page,
      int size,
      long total,
      CrmMessageSendSummary summary) {}

  /** 批量相同回复的逐条结果。 */
  public record CrmMessageSendBatchItem(
      UUID messageId,
      String messageNo,
      boolean success,
      @Schema(nullable = true) UUID sendId,
      @Schema(nullable = true) String sendNo,
      String reason) {}

  /** 批量相同回复的汇总。 */
  public record CrmMessageSendBatchResult(
      UUID batchId,
      int requestedCount,
      int succeededCount,
      int failedCount,
      List<CrmMessageSendBatchItem> items,
      String boundaryNote) {}
}
