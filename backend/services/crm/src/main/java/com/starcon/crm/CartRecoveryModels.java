package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 15.6.2.2 购物车与弃单挽回的传输模型。
 *
 * <p>蓝图 15 卷 15.6.2.2 原文：
 *
 * <blockquote>
 *
 * 职责：管理独立站购物车与弃单挽回：采集加购/结账未完成事件（Shopify 等提供 cart/checkout 事件），
 * 按超时策略触发挽回（邮件/短信/站内信），跟踪挽回率；平台订单不适用此项。
 * 输入：购物车/结账事件（2 渠道 Shopify 等）；挽回规则与话术；客户联系方式与退订状态。
 * 输出：弃单清单 + 挽回触达记录 + 挽回率报表。
 * 关系：上游 = 2 渠道（独立站事件）、15.5.2 触达；下游 = 9 订单（挽回成交）、13 分析。
 * <b>此项是独立站(DTC)特有能力</b>，平台订单无购物车概念；触达须遵守退订与频次上限，
 * 挽回话术需人工确认后启用。
 *
 * </blockquote>
 *
 * <p><b>建模纪律</b>：
 *
 * <ul>
 *   <li><b>平台类型是快照</b>：每条弃单都带 {@code platformCode} + {@code platformType}，
 *       平台店铺的事件**根本进不来**（服务层拒绝 + DB 层 CHECK）。</li>
 *   <li><b>话术是状态机</b>：{@code DRAFT → APPROVED → ENABLED → DISABLED}，
 *       只有 {@code ENABLED} 能被触达流程选用；审核留痕（谁 / 何时 / 哪个版本）。</li>
 *   <li><b>触达记录只登记不假装</b>：15.5.2.1 触达未交付 ⇒ 状态只有
 *       {@code REGISTERED} / {@code SENT} / {@code FAILED}，没有「自动发成功」这条路。</li>
 *   <li><b>金额与数量一律 BigDecimal</b>：{@code numeric(19,6)}，<b>禁浮点</b>。</li>
 *   <li><b>时间一律带偏移量的 ISO-8601 字符串</b>：用 {@code Timestamp.valueOf(LocalDateTime)}
 *       会在东八区静默偏 −28800 秒，本仓库反复踩过。</li>
 * </ul>
 */
public final class CartRecoveryModels {

  private CartRecoveryModels() {}

  // ------------------------------------------------------------ 输入

  /**
   * 登记一条弃单（购物车 / 结账未完成）。
   *
   * <p>🔴 <b>平台店铺的弃单登不进来</b>：服务层按店铺的平台类型判定，
   * 判定为平台店铺一律 <b>409</b>（平台订单无购物车概念）且**不写任何数据**。
   *
   * <p>{@code abandonedAt} 是**事件时刻**（可以是过去）：它决定挽回时点
   * （= 事件时刻 + 租户策略的超时小时数），也决定触达是否已经「到期」。
   */
  public record CrmCartAbandonmentInput(
      @NotNull UUID shopId,
      @Size(max = 200) String cartRef,
      @Size(max = 200) String externalEventId,
      @NotBlank @Pattern(regexp = "CART|CHECKOUT", message = "阶段只能是 CART（只加购）或 CHECKOUT（进了结账）")
          String stage,
      @NotBlank @Size(max = 128) String buyerRef,
      @Size(max = 320) String buyerEmail,
      @Size(max = 64) String buyerPhone,
      @Size(max = 8) String currency,
      @DecimalMin(value = "0") BigDecimal amount,
      @DecimalMin(value = "0") BigDecimal itemCount,
      @Size(max = 500) String itemSummary,
      @NotBlank
          @Schema(description = "购物车被放弃的时刻，例如 2026-10-10T08:00:00+08:00")
          String abandonedAt,
      @Size(max = 500) String note) {}

  /**
   * 登记一次挽回触达。
   *
   * <p>🔴 收件人**不由客户端填**：服务层从弃单上的联系方式按话术通道派生 ——
   * 客户端另填会让「退订硬拦」查错对象。
   *
   * <p>🔴 {@code messageId} 指向的话术必须<b>已启用</b>；否则服务端 409（未审核 / 未启用
   * 的话术不允许被触达流程选用）。
   */
  public record CrmCartTouchInput(
      @NotNull UUID messageId, @Size(max = 500) String note) {}

  /**
   * 回填一次触达的结果（人工交接之后）。
   *
   * <p>🔴 15.5.2.1 触达未交付 ⇒ 本模块**没有**自动发送通道。
   * 这条端点是「运营在别处发出之后回来登记结果」，不是「让本服务去发」。
   */
  public record CrmCartTouchResultInput(
      @NotBlank @Pattern(regexp = "SENT|FAILED", message = "结果只能是 SENT（已发出）或 FAILED（失败）")
          String status,
      @Size(max = 600) String failureReason,
      @Schema(
              nullable = true,
              description = "实际发出时刻，例如 2026-10-10T09:00:00+08:00；留空即取登记时刻")
          String sentAt,
      @Size(max = 500) String note) {}

  /**
   * 登记一次挽回成交。
   *
   * <p>🔴 9 订单（挽回成交）**未交付** ⇒ 订单号由运营**人工登记**，
   * 本模块**不编造**下游订单 id。
   */
  public record CrmCartRecoveryInput(
      @NotBlank @Size(max = 64) String salesOrderNo,
      @DecimalMin(value = "0") BigDecimal recoveredAmount,
      @NotBlank
          @Schema(description = "成交时刻，例如 2026-10-12T20:30:00+08:00")
          String recoveredAt,
      @Size(max = 500) String note) {}

  /** 新建 / 改一条挽回话术。 */
  public record CrmCartMessageInput(
      @NotBlank @Size(max = 64) String messageCode,
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Pattern(regexp = "EMAIL|SMS|IN_APP", message = "通道只能是 EMAIL / SMS / IN_APP")
          String channel,
      @Pattern(regexp = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$", message = "语言标签必须是 BCP-47 简写（如 zh-CN / en-US）")
          String locale,
      @Size(max = 500) String subject,
      @Size(max = 20000) String body,
      @Size(max = 500) String note) {}

  /** 登记一条退订。 */
  public record CrmCartUnsubscribeInput(
      @NotBlank @Pattern(regexp = "EMAIL|SMS|IN_APP", message = "退订通道只能是 EMAIL / SMS / IN_APP")
          String contactType,
      @NotBlank @Size(max = 320) String contactValue,
      @Schema(nullable = true) UUID shopId,
      @Size(max = 500) String reason,
      @Size(max = 500) String note) {}

  /** 声明店铺的平台类型（未知平台码才需要；已知平台码由系统判定，不可改）。 */
  public record CrmCartShopProfileInput(
      @NotBlank @Pattern(regexp = "DTC|MARKETPLACE", message = "平台类型只能是 DTC（独立站）或 MARKETPLACE（平台店铺）")
          String platformType,
      @Size(max = 500) String note) {}

  /** 租户级频次上限 + 弃单超时策略（一租户一份，PUT 是幂等替换）。 */
  public record CrmCartPolicyInput(
      @NotNull @DecimalMin(value = "1") Integer maxTouchesPerDay,
      @NotNull @DecimalMin(value = "1") Integer maxTouchesPerCycle,
      @NotNull @DecimalMin(value = "1") Integer cycleDays,
      @NotNull @DecimalMin(value = "1") Integer abandonTimeoutHours,
      @Size(max = 500) String note) {}

  /** 平台购物车事件同步的入参（当前恒为 409，因为平台适配器未交付）。 */
  public record CrmCartSyncInput(@NotNull UUID shopId) {}

  // ------------------------------------------------------------ 输出

  /** 能力维度：每一维都要说清「有没有、依据是什么、缺了会怎样」。 */
  public record CrmCartCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  public record CrmCartCapabilities(
      List<CrmCartCapabilityDimension> dimensions,
      List<String> platformTypes,
      List<String> stages,
      List<String> abandonmentStatuses,
      List<String> messageStatuses,
      List<String> channels,
      List<String> touchStatuses,
      List<String> transports,
      String platformTypeRule,
      String messageLifecycle,
      String boundaryNote,
      String highlight) {}

  /** 租户级频次上限 + 超时策略。 */
  public record CrmCartPolicy(
      int maxTouchesPerDay,
      int maxTouchesPerCycle,
      int cycleDays,
      int abandonTimeoutHours,
      String note,
      boolean customized,
      @Schema(nullable = true) String updatedAt) {}

  /** 一条弃单。 */
  public record CrmCartAbandonmentRow(
      UUID id,
      String cartNo,
      UUID shopId,
      String shopName,
      String platformCode,
      String platformType,
      String cartRef,
      String externalEventId,
      String stage,
      String buyerRef,
      String buyerEmail,
      String buyerPhone,
      String currency,
      BigDecimal amount,
      BigDecimal itemCount,
      String itemSummary,
      String abandonedAt,
      String recoverDeadlineAt,
      String status,
      @Schema(nullable = true) String recoveredAt,
      BigDecimal recoveredAmount,
      String recoveredOrderNo,
      String source,
      Long touchCount,
      UUID createdBy,
      String note,
      String createdAt,
      String updatedAt) {}

  /** 弃单清单分页（真分页：总数与页数都从真实查询来）。 */
  public record CrmCartAbandonmentPage(
      List<CrmCartAbandonmentRow> items, int page, int size, long totalElements, int totalPages) {}

  /** 一条挽回话术。 */
  public record CrmCartMessageRow(
      UUID id,
      String messageCode,
      String name,
      String channel,
      String locale,
      String subject,
      String body,
      String status,
      int version,
      UUID approvedBy,
      @Schema(nullable = true) String approvedAt,
      @Schema(nullable = true) Integer approvedVersion,
      UUID enabledBy,
      @Schema(nullable = true) String enabledAt,
      @Schema(nullable = true) Integer enabledVersion,
      UUID disabledBy,
      @Schema(nullable = true) String disabledAt,
      Long usageCount,
      UUID createdBy,
      String note,
      String createdAt,
      String updatedAt) {}

  /** 挽回话术分页。 */
  public record CrmCartMessagePage(
      List<CrmCartMessageRow> items, int page, int size, long totalElements, int totalPages) {}

  /** 一条触达记录。 */
  public record CrmCartTouchRow(
      UUID id,
      UUID cartId,
      String cartNo,
      @Schema(nullable = true) UUID messageId,
      String messageCode,
      String channel,
      String transport,
      String status,
      String recipient,
      String subject,
      String body,
      String failureReason,
      @Schema(nullable = true) String sentAt,
      UUID operatorId,
      String createdAt,
      String updatedAt) {}

  /** 一条退订记录。 */
  public record CrmCartUnsubscribeRow(
      UUID id,
      String contactType,
      String contactValue,
      @Schema(nullable = true) UUID shopId,
      String reason,
      String unsubscribedAt,
      UUID createdBy,
      String note,
      String createdAt) {}

  /** 退订名单分页。 */
  public record CrmCartUnsubscribePage(
      List<CrmCartUnsubscribeRow> items, int page, int size, long totalElements, int totalPages) {}

  /** 单条弃单详情 = 弃单本体 + 触达记录 + 恢复记录。 */
  public record CrmCartAbandonmentDetail(
      CrmCartAbandonmentRow abandonment, List<CrmCartTouchRow> touches) {}

  /**
   * 一次触达登记的结论。
   *
   * <p>🔴 走到这里就说明<b>四道闸全过了</b>（话术已启用、到了挽回时点、没退订、
   * 没超频次上限）：触达记录已落库，状态是「已登记待发」。
   * 任何一道闸没过时端点直接 <b>409</b> 并写明原因，<b>什么都不会写</b> ——
   * 静默跳过会让运营以为发过了。
   */
  public record CrmCartTouchResult(CrmCartTouchRow touch, String note) {}

  /**
   * 挽回率报表（**服务端真算**，前端只原样展示）。
   *
   * @param recoveryRate 挽回率（百分数，两位小数；分母为 0 时为 null 而不是 0）
   */
  public record CrmCartRecoveryRate(
      long totalAbandonments,
      long recoveredCount,
      long pendingCount,
      long expiredCount,
      @Schema(nullable = true) BigDecimal recoveryRate,
      BigDecimal totalAmount,
      BigDecimal recoveredAmount,
      long touchCount,
      long unsubscribedCount,
      List<CrmCartRecoveryRateBucket> byShop,
      List<CrmCartTouchChannelBucket> byChannel,
      String windowNote) {}

  /** 按店铺分组的挽回率桶。 */
  public record CrmCartRecoveryRateBucket(
      UUID shopId,
      String shopName,
      long total,
      long recovered,
      @Schema(nullable = true) BigDecimal recoveryRate,
      BigDecimal totalAmount,
      BigDecimal recoveredAmount) {}

  /** 按触达通道分组的触达量。 */
  public record CrmCartTouchChannelBucket(String channel, long touchCount) {}

  /** 店铺目录的一行（来自 channel 的真实清单）+ 平台类型 + 购物车事件接入状态。 */
  public record CrmCartShopView(
      UUID id,
      String code,
      String name,
      String platformCode,
      String platformName,
      String status,
      String platformType,
      String platformTypeSource,
      boolean cartEventAvailable,
      String cartEventNote) {}

  /**
   * 平台购物车事件同步的结果。
   *
   * <p>🔴 当前**恒为 409**：平台购物车事件适配器未交付（见 {@code CartEventPort}），
   * 同步请求**不写任何数据**。
   */
  public record CrmCartSyncResult(
      int synced, boolean available, String reason, String note) {}
}
