package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 14.5.1.2 建联与样品 的传输模型。
 *
 * <p>蓝图：「达人建联流程：按规则筛选达人 → 模板化私信邀约（走官方接口）→
 * 寄样管理（样品单、物流、签收）→ 合作跟进 → 佣金设置。输入：达人筛选条件；邀约模板；
 * 样品寄送信息；佣金比例。输出：建联记录 + 样品单 + 合作状态跟踪。关系：上游 = 14.5.1.1；
 * 下游 = 8 仓库（样品出库）、10 物流（样品物流）、12 财务（佣金计提）。
 * <b>建联必须走官方接口</b>，批量私信是平台风控重点，第三方工具易导致账号风险。」
 *
 * <p>🔴 这个模型层钉死本模块最容易做丢的五件事：
 *
 * <ol>
 *   <li><b>「没录」不是 0</b>。{@code sampleValue} / {@code commissionRate} /
 *       {@code baseAmount} 为 {@code null} 表示<b>不可比</b>（还没录 / 没配口径），
 *       与「真的是零」含义相反。界面把 null 渲染成 0.00 就等于宣称
 *       「这个达人一件样品成本都没有」。
 *   <li><b>下游缺口必须看得见</b>。{@code trackingAvailable} 恒为 false、
 *       {@code financePosted} 恒为 false，各自带 {@code ownerModule} ——
 *       界面必须显示「轨迹不可查（归 10 物流 管）」「未进财务（归 12 财务 管）」，
 *       而不是显示一个假状态。
 *   <li><b>样品单状态不是人填的</b>。{@code status} 的 DELIVERED / SIGNED
 *       由包裹推导（{@code InfluencerOutreachRules.deriveSampleOrderStatus}），
 *       响应里额外给 {@code statusDerived=true} 说明这一点。
 *   <li><b>建联渠道只能是官方接口</b>。{@code contactChannel} 恒为
 *       {@code OFFICIAL_API}，{@code idempotencyKey} 是必填的幂等键。
 *   <li><b>佣金计提 ≠ 已入账</b>。{@code accruedAmount} 是本地计提事实，
 *       {@code financePosted=false} + {@code financeOwnerModule} 说清它归 12 财务。
 * </ol>
 *
 * <p>🔴 金额与数量一律 {@link BigDecimal}，**没有 double**：佣金比例、计提金额、
 * 样品成本、样品数量都是钱或比率，它们直接决定「这一单该给多少」。
 */
public final class InfluencerOutreachModels {
  private InfluencerOutreachModels() {}

  // ================================================================ 能力声明

  public record InfluencerOutreachCapabilityDimension(
      @Schema(description = "维度键") String dimension,
      @Schema(description = "维度说明") String description,
      @Schema(description = "本模块是否已交付") boolean available,
      @Schema(description = "数据来源（表 / 上游模块）") String source,
      @Schema(description = "未交付段的责任模块") String ownerModule,
      @Schema(description = "不交付的影响") String impact) {}

  /**
   * 能力声明：逐维度说清「哪些能算、哪些拿不到」。
   *
   * <p>🔴 四个不可用维度分别是：<b>平台私信接口</b>（适配层未交付）、
   * <b>样品出库</b>（8 仓库未交付）、<b>样品物流轨迹</b>（10 物流未交付）、
   * <b>佣金入账</b>（12 财务未交付）。四个都必须显式说清，不能用一个假数字顶替。
   */
  public record InfluencerOutreachCapabilities(
      @Schema(description = "逐维度能力声明；未交付段 available=false")
          List<InfluencerOutreachCapabilityDimension> dimensions,
      @Schema(description = "建联状态取值") List<String> outreachStatuses,
      @Schema(description = "样品单状态取值") List<String> sampleOrderStatuses,
      @Schema(description = "包裹状态取值") List<String> shipmentStatuses,
      @Schema(description = "跟进方式取值") List<String> followupTypes,
      @Schema(description = "佣金形态取值") List<String> commissionTypes,
      @Schema(description = "模板变量（闭合词表）") List<InfluencerOutreachTemplateVariable> templateVariables,
      @Schema(description = "🔴 官方接口红线说明") String officialApiNote,
      @Schema(description = "🔴 频率上限说明") String rateLimitNote,
      @Schema(description = "🔴 下游缺口说明") String downstreamNote,
      @Schema(description = "上游与边界说明") String upstreamNote,
      @Schema(description = "下游与边界说明") String boundaryNote) {}

  /** 模板变量（闭合词表，界面「插入变量」按钮用）。 */
  public record InfluencerOutreachTemplateVariable(
      @Schema(description = "变量名（不含大括号）") String name,
      @Schema(description = "中文说明") String label,
      @Schema(description = "是不是必填") boolean required) {}

  // ================================================================ 下拉项

  public record InfluencerOutreachOption(
      @Schema(description = "取值") String code, @Schema(description = "中文说明") String label) {}

  /** 平台下拉项（**带 id** —— 登记模板要的是平台 id，不只是编码）。 */
  public record InfluencerOutreachPlatformOption(
      @Schema(description = "平台 id") UUID id,
      @Schema(description = "平台编码") String code,
      @Schema(description = "平台名称") String name) {}

  public record InfluencerOutreachOptions(
      @Schema(description = "建联状态选项") List<InfluencerOutreachOption> outreachStatuses,
      @Schema(description = "样品单状态选项") List<InfluencerOutreachOption> sampleOrderStatuses,
      @Schema(description = "包裹状态选项") List<InfluencerOutreachOption> shipmentStatuses,
      @Schema(description = "跟进方式选项") List<InfluencerOutreachOption> followupTypes,
      @Schema(description = "佣金形态选项") List<InfluencerOutreachOption> commissionTypes,
      @Schema(description = "佣金适用范围选项") List<InfluencerOutreachOption> commissionScopes,
      @Schema(description = "计提状态选项") List<InfluencerOutreachOption> accrualStatuses,
      @Schema(description = "平台下拉项（带 id）") List<InfluencerOutreachPlatformOption> platforms,
      @Schema(description = "平台编码（租户内可用的）") List<String> platformCodes,
      @Schema(description = "店铺编码（租户内可用的）") List<String> shopCodes,
      @Schema(description = "模板变量（闭合词表）")
          List<InfluencerOutreachTemplateVariable> templateVariables,
      @Schema(description = "🔴 官方接口红线说明") String officialApiNote,
      @Schema(description = "🔴 频率上限说明") String rateLimitNote,
      @Schema(description = "🔴 下游缺口说明") String downstreamNote) {}

  // ================================================================ 筛选条件

  /**
   * 达人筛选条件（租户级配置）。
   *
   * <p>🔴 条件之间是 <b>AND（交集）</b>，标签编码之间是 <b>OR</b>（池子的并集）。
   */
  public record InfluencerOutreachSegmentRow(
      @Schema(description = "筛选条件 id") UUID id,
      @Schema(description = "筛选条件编码（租户内唯一）") String segmentCode,
      @Schema(description = "筛选条件名称") String segmentName,
      @Schema(description = "说明") String description,
      @Schema(description = "类目（空串 = 不限）") String category,
      @Schema(description = "粉丝层级（空串 = 不限）") String followerTier,
      @Schema(description = "粉丝数下限（null = 不限）") Long minFollowerCount,
      @Schema(description = "互动率下限（null = 不限）", type = "string") BigDecimal minEngagementRate,
      @Schema(description = "GMV 下限（null = 不限）", type = "string") BigDecimal minGmv,
      @Schema(description = "评级档位编码（空串 = 不限）") String ratingTierCode,
      @Schema(description = "标签编码列表（逗号分隔；多个之间是 OR）") String tagCodes,
      @Schema(description = "平台 id（null = 不限）") UUID platformId,
      @Schema(description = "平台编码（null = 不限）") String platformCode,
      @Schema(description = "店铺编码（空串 = 不限）") String shopCode,
      @Schema(description = "是否排除黑名单里的达人") boolean excludeBlacklisted,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record InfluencerOutreachSegmentPage(
      @Schema(description = "当前页数据") List<InfluencerOutreachSegmentRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 筛选候选

  /**
   * 按筛选条件筛出来的达人候选（蓝图第一步「按规则筛选达人」）。
   *
   * <p>🔴 {@code engagementRate} / {@code gmv} 为 {@code null} 表示**还没录**（不可比），
   *   与「真的是零」含义相反。
   */
  public record InfluencerOutreachCandidate(
      @Schema(description = "达人 id") UUID id,
      @Schema(description = "达人名称") String displayName,
      @Schema(description = "账号名") String handle,
      @Schema(description = "类目") String category,
      @Schema(description = "粉丝数") long followerCount,
      @Schema(description = "粉丝层级") String followerTier,
      @Schema(description = "🔴 互动率（null = 还没录，不可比）", type = "string") BigDecimal engagementRate,
      @Schema(description = "🔴 带货 GMV（null = 还没录，不可比）", type = "string") BigDecimal gmv,
      @Schema(description = "GMV 币种") String gmvCurrency,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "🔴 是否在黑名单里（生效中）") boolean blacklisted,
      @Schema(description = "🔴 是否已有未走完的邀约") boolean hasOpenOutreach) {}

  public record InfluencerOutreachCandidatePage(
      @Schema(description = "当前页数据") List<InfluencerOutreachCandidate> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 邀约模板

  public record InfluencerOutreachTemplateRow(
      @Schema(description = "模板 id") UUID id,
      @Schema(description = "模板编码（租户内唯一）") String templateCode,
      @Schema(description = "模板名称") String templateName,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "平台名称") String platformName,
      @Schema(description = "标题模板") String subject,
      @Schema(description = "正文模板") String body,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record InfluencerOutreachTemplatePage(
      @Schema(description = "当前页数据") List<InfluencerOutreachTemplateRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  /** 模板预览（渲染结果）。 */
  public record InfluencerOutreachTemplatePreview(
      @Schema(description = "模板 id") UUID templateId,
      @Schema(description = "渲染后的标题") String subject,
      @Schema(description = "渲染后的正文") String messageBody,
      @Schema(description = "渲染用到的变量取值") java.util.Map<String, String> values,
      @Schema(description = "说明") String note) {}

  // ================================================================ 风控策略

  /** 风控策略（按平台；每日发送上限 + 最小间隔）。 */
  public record InfluencerOutreachPolicyRow(
      @Schema(description = "策略 id") UUID id,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "平台名称") String platformName,
      @Schema(description = "每日发送上限") int dailySendLimit,
      @Schema(description = "两条发送之间的最小间隔（秒）") int minIntervalSeconds,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record InfluencerOutreachPolicyPage(
      @Schema(description = "当前页数据") List<InfluencerOutreachPolicyRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 建联记录

  /**
   * 建联记录的一行。
   *
   * <p>🔴 {@code contactChannel} 恒为 {@code OFFICIAL_API}；
   * {@code idempotencyKey} 是幂等键（重放不会发出第二条私信）。
   */
  public record InfluencerOutreachRow(
      @Schema(description = "建联记录 id") UUID id,
      @Schema(description = "达人 id") UUID influencerId,
      @Schema(description = "达人名称") String influencerName,
      @Schema(description = "达人类目") String influencerCategory,
      @Schema(description = "筛选条件 id") UUID segmentId,
      @Schema(description = "筛选条件编码") String segmentCode,
      @Schema(description = "筛选条件名称") String segmentName,
      @Schema(description = "模板 id") UUID templateId,
      @Schema(description = "模板编码") String templateCode,
      @Schema(description = "模板名称") String templateName,
      @Schema(description = "平台 id") UUID platformId,
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "🔴 沟通渠道（恒为 OFFICIAL_API）") String contactChannel,
      @Schema(description = "幂等键") String idempotencyKey,
      @Schema(description = "渲染后的标题（快照）") String subject,
      @Schema(description = "渲染后的正文（快照）") String messageBody,
      @Schema(description = "建联状态") String status,
      @Schema(description = "平台消息 id（手动登记）") String platformMessageId,
      @Schema(description = "平台回执（手动登记）") String platformReceipt,
      @Schema(description = "发送时刻") OffsetDateTime sentAt,
      @Schema(description = "回复时刻") OffsetDateTime repliedAt,
      @Schema(description = "关闭时刻") OffsetDateTime closedAt,
      @Schema(description = "跟进条数") int followupCount,
      @Schema(description = "样品单条数") int sampleOrderCount,
      @Schema(description = "已登记的佣金计提条数") int accrualCount,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record InfluencerOutreachPage(
      @Schema(description = "当前页数据") List<InfluencerOutreachRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 批量建联

  /** 批量建联的一条结果。 */
  public record InfluencerOutreachBatchItem(
      @Schema(description = "达人 id") UUID influencerId,
      @Schema(description = "达人名称") String influencerName,
      @Schema(description = "建联记录 id（created=false 时为空）") UUID outreachId,
      @Schema(description = "幂等键") String idempotencyKey,
      @Schema(description = "是否已创建") boolean created,
      @Schema(description = "结果码：CREATED 已创建 / SKIPPED_OPEN 已有未走完的邀约 / SKIPPED_DUPLICATE 幂等键已存在 / SKIPPED_TEMPLATE 模板渲染失败")
          String result,
      @Schema(description = "人可读说明") String message) {}

  /** 批量建联的结论。 */
  public record InfluencerOutreachBatchResult(
      @Schema(description = "筛中的达人数") int matchedCount,
      @Schema(description = "实际新建的条数") int createdCount,
      @Schema(description = "跳过的条数") int skippedCount,
      @Schema(description = "逐条结果") List<InfluencerOutreachBatchItem> items,
      @Schema(description = "说明") String note) {}

  // ================================================================ 平台私信接口

  /** 一个平台的达人私信接口是否已接入。 */
  public record InfluencerOutreachPlatformAvailability(
      @Schema(description = "平台编码") String platformCode,
      @Schema(description = "平台名称") String platformName,
      @Schema(description = "是否已接入官方私信接口") boolean available,
      @Schema(description = "状态：NOT_INTEGRATED 未接入 / AVAILABLE 已接入") String status,
      @Schema(description = "未接入的原因（人能读，不是异常类名）") String reason,
      @Schema(description = "该平台今天已登记的发送条数") long sentToday,
      @Schema(description = "该平台的每日发送上限") int dailySendLimit) {}

  /** 私信接口接入情况总览。 */
  public record InfluencerOutreachAvailability(
      @Schema(description = "逐平台接入情况") List<InfluencerOutreachPlatformAvailability> platforms,
      @Schema(description = "🔴 官方接口红线说明") String officialApiNote,
      @Schema(description = "🔴 频率上限说明") String rateLimitNote) {}

  /**
   * 一次「走官方接口发送」的结论。
   *
   * <p>🔴 当前必然是 {@code sent=false} + 人可读原因，**不写任何数据** ——
   * 编一个假接口或返回硬编码列表都比这糟。
   */
  public record InfluencerOutreachSendResult(
      @Schema(description = "是否真的发出去了") boolean sent,
      @Schema(description = "结论说明") String message,
      @Schema(description = "平台消息 id（未发出时为空串）") String platformMessageId) {}

  // ================================================================ 跟进

  /** 合作跟进的一行。 */
  public record InfluencerOutreachFollowupRow(
      @Schema(description = "跟进 id") UUID id,
      @Schema(description = "建联记录 id") UUID outreachId,
      @Schema(description = "跟进方式") String followupType,
      @Schema(description = "跟进内容") String content,
      @Schema(description = "发生时刻") OffsetDateTime occurredAt,
      @Schema(description = "下次跟进时刻") OffsetDateTime nextFollowupAt,
      @Schema(description = "负责人 id") UUID ownerUserId,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record InfluencerOutreachFollowupPage(
      @Schema(description = "当前页数据") List<InfluencerOutreachFollowupRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 样品单

  /**
   * 样品单的一行。
   *
   * <p>🔴 {@code trackingAvailable} 恒为 false、{@code financePosted} 恒为 false：
   * 8 仓库 / 10 物流 / 12 财务三个下游在基线里都不可调用。
   */
  public record InfluencerSampleOrderRow(
      @Schema(description = "样品单 id") UUID id,
      @Schema(description = "建联记录 id") UUID outreachId,
      @Schema(description = "达人 id") UUID influencerId,
      @Schema(description = "达人名称") String influencerName,
      @Schema(description = "店铺编码") String shopCode,
      @Schema(description = "样品 SKU 编码（catalog 逻辑引用 + 快照）") String skuCode,
      @Schema(description = "样品名称快照") String skuName,
      @Schema(description = "样品数量", type = "string") BigDecimal quantity,
      @Schema(description = "🔴 样品成本（null = 还没录，不可比）", type = "string") BigDecimal sampleValue,
      @Schema(description = "样品成本币种") String sampleCurrency,
      @Schema(description = "收件人") String recipientName,
      @Schema(description = "收件电话") String recipientPhone,
      @Schema(description = "收件地址") String recipientAddress,
      @Schema(description = "样品单状态") String status,
      @Schema(description = "🔴 状态是否由包裹推导") boolean statusDerived,
      @Schema(description = "🔴 本地出库状态：LOCAL_PENDING / LOCAL_OUTBOUNDED") String outboundStatus,
      @Schema(description = "本地出库时刻") OffsetDateTime outboundAt,
      @Schema(description = "🔴 8 仓库的出库单号（未交付 ⇒ 空串）") String warehouseOutboundRef,
      @Schema(description = "🔴 出库责任模块") String warehouseOwnerModule,
      @Schema(description = "🔴 物流轨迹是否可查（恒为 false）") boolean trackingAvailable,
      @Schema(description = "🔴 物流责任模块") String trackingOwnerModule,
      @Schema(description = "签收时刻") OffsetDateTime signedAt,
      @Schema(description = "签收人") String signedBy,
      @Schema(description = "包裹数") int shipmentCount,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record InfluencerSampleOrderPage(
      @Schema(description = "当前页数据") List<InfluencerSampleOrderRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 包裹

  /** 样品包裹的一行。 */
  public record InfluencerSampleShipmentRow(
      @Schema(description = "包裹 id") UUID id,
      @Schema(description = "样品单 id") UUID sampleOrderId,
      @Schema(description = "承运商编码") String carrierCode,
      @Schema(description = "物流单号") String trackingNo,
      @Schema(description = "件数", type = "string") BigDecimal packageCount,
      @Schema(description = "包裹状态") String status,
      @Schema(description = "发货时刻") OffsetDateTime shippedAt,
      @Schema(description = "送达时刻") OffsetDateTime deliveredAt,
      @Schema(description = "签收时刻") OffsetDateTime signedAt,
      @Schema(description = "签收人") String signedBy,
      @Schema(description = "🔴 物流轨迹是否可查（恒为 false）") boolean trackingAvailable,
      @Schema(description = "🔴 物流责任模块") String trackingOwnerModule,
      @Schema(description = "备注") String note,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record InfluencerSampleShipmentPage(
      @Schema(description = "当前页数据") List<InfluencerSampleShipmentRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 佣金

  /** 佣金规则（租户级配置）。 */
  public record InfluencerCommissionRuleRow(
      @Schema(description = "规则 id") UUID id,
      @Schema(description = "规则编码（租户内唯一）") String ruleCode,
      @Schema(description = "规则名称") String ruleName,
      @Schema(description = "适用范围：GLOBAL / SEGMENT / INFLUENCER") String scopeType,
      @Schema(description = "筛选条件 id（SEGMENT 时有值）") UUID segmentId,
      @Schema(description = "筛选条件名称（SEGMENT 时有值）") String segmentName,
      @Schema(description = "达人 id（INFLUENCER 时有值）") UUID influencerId,
      @Schema(description = "达人名称（INFLUENCER 时有值）") String influencerName,
      @Schema(description = "佣金形态：RATE / FIXED") String commissionType,
      @Schema(description = "佣金比例（null = 该形态不用它）", type = "string") BigDecimal commissionRate,
      @Schema(description = "固定金额（null = 该形态不用它）", type = "string") BigDecimal fixedAmount,
      @Schema(description = "币种") String currency,
      @Schema(description = "生效起") OffsetDateTime effectiveFrom,
      @Schema(description = "生效止（null = 长期有效）") OffsetDateTime effectiveTo,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record InfluencerCommissionRulePage(
      @Schema(description = "当前页数据") List<InfluencerCommissionRuleRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  /** 佣金计提（本地事实；未进财务）。 */
  public record InfluencerCommissionAccrualRow(
      @Schema(description = "计提 id") UUID id,
      @Schema(description = "建联记录 id") UUID outreachId,
      @Schema(description = "达人 id") UUID influencerId,
      @Schema(description = "达人名称") String influencerName,
      @Schema(description = "规则 id（规则后来删了会为空）") UUID ruleId,
      @Schema(description = "规则编码") String ruleCode,
      @Schema(description = "佣金形态：RATE / FIXED") String commissionType,
      @Schema(description = "佣金比例快照（null = 该形态不用它）", type = "string") BigDecimal commissionRate,
      @Schema(description = "固定额快照（null = 该形态不用它）", type = "string") BigDecimal fixedAmount,
      @Schema(description = "计提基数（人工登记的归因 GMV）", type = "string") BigDecimal baseAmount,
      @Schema(description = "基数币种") String baseCurrency,
      @Schema(description = "计提金额", type = "string") BigDecimal accruedAmount,
      @Schema(description = "计提金额币种") String accruedCurrency,
      @Schema(description = "计提状态") String status,
      @Schema(description = "🔴 是否已入财务（恒为 false）") boolean financePosted,
      @Schema(description = "🔴 财务责任模块") String financeOwnerModule,
      @Schema(description = "计提时刻") OffsetDateTime accruedAt,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt,
      @Schema(description = "更新时刻") OffsetDateTime updatedAt) {}

  public record InfluencerCommissionAccrualPage(
      @Schema(description = "当前页数据") List<InfluencerCommissionAccrualRow> items,
      @Schema(description = "符合筛选条件的总条数") long total,
      @Schema(description = "页码，从 0 起") int page,
      @Schema(description = "每页条数") int size) {}

  // ================================================================ 详情

  /** 建联记录详情。 */
  public record InfluencerOutreachDetail(
      @Schema(description = "建联记录") InfluencerOutreachRow outreach,
      @Schema(description = "跟进（按发生时刻倒序）") List<InfluencerOutreachFollowupRow> followups,
      @Schema(description = "样品单") List<InfluencerSampleOrderRow> sampleOrders,
      @Schema(description = "佣金计提") List<InfluencerCommissionAccrualRow> accruals,
      @Schema(description = "🔴 官方接口红线说明") String officialApiNote,
      @Schema(description = "🔴 频率上限说明") String rateLimitNote,
      @Schema(description = "🔴 下游缺口说明") String downstreamNote,
      @Schema(description = "说明") String note) {}

  // ================================================================ 概览

  /** 概览统计。 */
  public record InfluencerOutreachSummary(
      @Schema(description = "建联记录总数") long totalOutreachs,
      @Schema(description = "草稿数") long draftCount,
      @Schema(description = "已发送数") long sentCount,
      @Schema(description = "已回复数") long repliedCount,
      @Schema(description = "合作中数") long cooperatingCount,
      @Schema(description = "已完成数") long completedCount,
      @Schema(description = "已拒绝 / 无回复数") long closedCount,
      @Schema(description = "样品单数") long sampleOrderCount,
      @Schema(description = "已签收样品单数") long signedSampleCount,
      @Schema(description = "待签收样品单数") long pendingSignCount,
      @Schema(description = "佣金计提条数") long accrualCount,
      @Schema(description = "本地计提金额合计（null = 一条都没计提）", type = "string")
          BigDecimal accruedAmountTotal,
      @Schema(description = "🔴 计提金额币种（只有一个币种才汇总）") String accruedCurrency,
      @Schema(description = "🔴 下游缺口说明") String downstreamNote,
      @Schema(description = "数据截至时刻") OffsetDateTime asOf) {}

  // ================================================================ 请求

  /** 登记 / 修改一个筛选条件。 */
  public record SaveInfluencerOutreachSegmentRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "筛选条件编码（租户内唯一）")
          @NotBlank
          @Size(max = 64)
          String segmentCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "筛选条件名称") @NotBlank
          @Size(max = 200)
          String segmentName,
      @Schema(description = "说明") String description,
      @Schema(description = "类目（留空 = 不限）") @Size(max = 64) String category,
      @Schema(description = "粉丝层级（留空 = 不限）") String followerTier,
      @Schema(description = "粉丝数下限（留空 = 不限）") Long minFollowerCount,
      @Schema(description = "互动率下限（0 ~ 1；留空 = 不限）", type = "string") @DecimalMin("0")
          @DecimalMax("1")
          BigDecimal minEngagementRate,
      @Schema(description = "GMV 下限（留空 = 不限）", type = "string") @DecimalMin("0") BigDecimal minGmv,
      @Schema(description = "评级档位编码（留空 = 不限）") @Size(max = 32) String ratingTierCode,
      @Schema(description = "标签编码列表（逗号分隔；多个之间是 OR）") String tagCodes,
      @Schema(description = "平台 id（留空 = 不限）") UUID platformId,
      @Schema(description = "店铺编码（留空 = 不限）") @Size(max = 64) String shopCode,
      @Schema(description = "是否排除黑名单里的达人（默认 true）") Boolean excludeBlacklisted,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "备注") String note) {}

  /** 登记 / 修改一个邀约模板。 */
  public record SaveInfluencerOutreachTemplateRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "模板编码（租户内唯一）")
          @NotBlank
          @Size(max = 64)
          String templateCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "模板名称") @NotBlank
          @Size(max = 200)
          String templateName,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "平台 id") @NotNull
          UUID platformId,
      @Schema(description = "标题模板（可含 {{变量}}）") @Size(max = 300) String subject,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "正文模板（可含 {{变量}}）")
          @NotBlank
          String body,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "备注") String note) {}

  /** 登记 / 修改一条风控策略（按平台 upsert）。 */
  public record SaveInfluencerOutreachPolicyRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "平台 id") @NotNull
          UUID platformId,
      @Schema(description = "每日发送上限（默认 20）") Integer dailySendLimit,
      @Schema(description = "两条发送之间的最小间隔秒数（默认 60）") Integer minIntervalSeconds,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "备注") String note) {}

  /** 新建 / 修改一条建联记录（DRAFT）。 */
  public record SaveInfluencerOutreachRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "达人 id") @NotNull
          UUID influencerId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "筛选条件 id") @NotNull
          UUID segmentId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "模板 id") @NotNull
          UUID templateId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "幂等键（租户内唯一）")
          @NotBlank
          @Size(max = 128)
          String idempotencyKey,
      @Schema(description = "备注") String note) {}

  /**
   * 手动登记「邀约已发送 + 平台回执」。
   *
   * <p>🔴 这是**官方私信接口未交付时唯一的发送登记入口**：运营在平台官方后台
   * 发出私信后，把平台消息 id / 回执 / 发送时刻登记回来。
   * 登记时服务层会做**幂等键查重**与**频率上限判定**。
   */
  public record RegisterInfluencerOutreachReceiptRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "平台消息 id") @NotBlank
          @Size(max = 128)
          String platformMessageId,
      @Schema(description = "平台回执原文") String platformReceipt,
      @Schema(description = "发送时刻（留空 = 现在）") OffsetDateTime sentAt,
      @Schema(description = "备注") String note) {}

  /** 推进建联状态。 */
  public record TransitionInfluencerOutreachRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "目标状态") @NotBlank
          String status,
      @Schema(description = "发生时刻（留空 = 现在）") OffsetDateTime occurredAt,
      @Schema(description = "备注") String note) {}

  /** 登记一次合作跟进。 */
  public record CreateInfluencerOutreachFollowupRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "跟进方式") @NotBlank
          String followupType,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "跟进内容") @NotBlank
          String content,
      @Schema(description = "发生时刻（留空 = 现在）") OffsetDateTime occurredAt,
      @Schema(description = "下次跟进时刻") OffsetDateTime nextFollowupAt,
      @Schema(description = "负责人 id") UUID ownerUserId,
      @Schema(description = "备注") String note) {}

  /** 批量建联：按筛选条件挑达人，用模板批量生成草稿。 */
  public record BatchInfluencerOutreachRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "筛选条件 id") @NotNull
          UUID segmentId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "模板 id") @NotNull
          UUID templateId,
      @Schema(description = "批次键（幂等键的组成部分；同一批次重复跑不会重复建）")
          @Size(max = 96)
          String batchKey,
      @Schema(description = "最多建多少条（默认 50，上限 200）") Integer limit,
      @Schema(description = "备注") String note) {}

  /** 新建 / 修改一张样品单。 */
  public record SaveInfluencerSampleOrderRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "建联记录 id") @NotNull
          UUID outreachId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "样品 SKU 编码") @NotBlank
          @Size(max = 64)
          String skuCode,
      @Schema(description = "样品名称快照") @Size(max = 200) String skuName,
      @Schema(description = "样品数量（默认 1）", type = "string") @DecimalMin(value = "0", inclusive = false)
          BigDecimal quantity,
      @Schema(description = "样品成本（留空 = 还没录，不可比）", type = "string") @DecimalMin("0")
          BigDecimal sampleValue,
      @Schema(description = "样品成本币种（3 位字母）") @Size(max = 3) String sampleCurrency,
      @Schema(description = "收件人") @Size(max = 200) String recipientName,
      @Schema(description = "收件电话") @Size(max = 64) String recipientPhone,
      @Schema(description = "收件地址") String recipientAddress,
      @Schema(description = "备注") String note) {}

  /** 登记一个包裹（发货）。 */
  public record CreateInfluencerSampleShipmentRequest(
      @Schema(description = "承运商编码") @Size(max = 64) String carrierCode,
      @Schema(description = "物流单号") @Size(max = 128) String trackingNo,
      @Schema(description = "件数（默认 1）", type = "string") @DecimalMin(value = "0", inclusive = false)
          BigDecimal packageCount,
      @Schema(description = "发货时刻（留空 = 现在）") OffsetDateTime shippedAt,
      @Schema(description = "备注") String note) {}

  /** 推进包裹状态（送达 / 签收 / 丢件 / 退回）。 */
  public record TransitionInfluencerSampleShipmentRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "目标状态") @NotBlank
          String status,
      @Schema(description = "发生时刻（留空 = 现在）") OffsetDateTime occurredAt,
      @Schema(description = "签收人") @Size(max = 200) String signedBy,
      @Schema(description = "备注") String note) {}

  /** 手动推进样品单状态（只能 REQUESTED → OUTBOUNDED / CANCELLED）。 */
  public record TransitionInfluencerSampleOrderRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "目标状态") @NotBlank
          String status,
      @Schema(description = "本地出库时刻（OUTBOUNDED 时必填）") OffsetDateTime outboundAt,
      @Schema(description = "备注") String note) {}

  /** 登记 / 修改一条佣金规则。 */
  public record SaveInfluencerCommissionRuleRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "规则编码（租户内唯一）")
          @NotBlank
          @Size(max = 64)
          String ruleCode,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "规则名称") @NotBlank
          @Size(max = 200)
          String ruleName,
      @Schema(description = "适用范围：GLOBAL / SEGMENT / INFLUENCER") String scopeType,
      @Schema(description = "筛选条件 id（SEGMENT 时必填）") UUID segmentId,
      @Schema(description = "达人 id（INFLUENCER 时必填）") UUID influencerId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "佣金形态：RATE / FIXED")
          @NotBlank
          String commissionType,
      @Schema(description = "佣金比例（RATE 形态必填，0 ~ 1）", type = "string") @DecimalMin("0")
          @DecimalMax("1")
          BigDecimal commissionRate,
      @Schema(description = "固定金额（FIXED 形态必填）", type = "string") @DecimalMin("0")
          BigDecimal fixedAmount,
      @Schema(description = "币种（3 位字母）") @Size(max = 3) String currency,
      @Schema(description = "生效起（留空 = 现在）") OffsetDateTime effectiveFrom,
      @Schema(description = "生效止（留空 = 长期有效）") OffsetDateTime effectiveTo,
      @Schema(description = "状态：ACTIVE / PAUSED") String status,
      @Schema(description = "备注") String note) {}

  /** 登记一笔佣金计提。 */
  public record CreateInfluencerCommissionAccrualRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "建联记录 id") @NotNull
          UUID outreachId,
      @Schema(description = "规则 id（留空 = 用服务层解析出的生效规则）") UUID ruleId,
      @Schema(description = "计提基数（人工登记的归因 GMV；留空 = 0）", type = "string")
          @DecimalMin("0")
          BigDecimal baseAmount,
      @Schema(description = "基数币种（3 位字母）") @Size(max = 3) String baseCurrency,
      @Schema(description = "备注") String note) {}

  /** 样品选品候选（跨服务只读：catalog SKU）。 */
  public record InfluencerSampleSkuCandidate(
      @Schema(description = "SKU id") UUID id,
      @Schema(description = "SKU 编码") String code,
      @Schema(description = "SKU 名称") String name,
      @Schema(description = "条码") String barcode,
      @Schema(description = "状态") String status) {}
}
