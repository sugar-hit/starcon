package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 9.3.1.2 合单的传输模型。
 *
 * <p>蓝图原话：「把同一买家的多笔订单合并成一个包裹发货，降低运费与包材成本； 同时识别跨店铺同买家订单（需分别回传平台）。」输入是
 * 「候选订单集合（同一买家 + 同一收货地址）；合单规则」，输出是「合并履约请求 + 各原订单的关联记录」。
 *
 * <p><b>三条建模纪律</b>：
 *
 * <ul>
 *   <li><b>平台归属逐单保留</b>：{@link ConsolidationMember} 一行一个原订单并逐单带
 *       platformCode / shopCode / platformOrderRef。蓝图加粗句说「合单后每个平台订单仍要单独回传物流号」——
 *       合并成��个包裹之后如果只留包裹，就再也不知道哪件货属于哪个平台。
 *   <li><b>「没报过价」是 null 不是 0</b>：{@code freightSavingAmount} 在租户没给合并运费报价时是
 *       {@code null}。给 0 会被读成「合了单运费一点没省」，那是结论而不是缺口。
 *   <li><b>金额与数量全程 BigDecimal</b>，数量对齐 DB 的 numeric(19,6)、金额对齐 numeric(19,4)。
 * </ul>
 */
public final class OrderConsolidationModels {

  private OrderConsolidationModels() {}

  // ------------------------------------------------------------ 枚举

  /** 合并单状态机（DB 词表，一字不改）。CANCELLED 是终态，不可离开。 */
  public enum ConsolidationStatus {
    PLANNED("已合并"),
    CONFIRMED("已确认"),
    DISPATCHED("已下发打包"),
    CANCELLED("已作废");

    private final String label;

    ConsolidationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 成员行状态（与合并头同向，成员不许比头更靠前）。 */
  public enum MemberStatus {
    PLANNED("待确认"),
    CONSOLIDATED("已合并"),
    DISPATCHED("已下发"),
    CANCELLED("已作废");

    private final String label;

    MemberStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 物流号回传状态（与 V119 order_platform_writeback_task 同一词表）。 */
  public enum WritebackStatus {
    PENDING("待回传"),
    SUBMITTED("已提交"),
    CONFIRMED("已回传"),
    REJECTED("平台拒绝"),
    PENDING_VERIFICATION("结果待查证");

    private final String label;

    WritebackStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 候选订单被排除的原因。
   *
   * <p>「为什么这张单不进候选集」是这一页最常被问的问题，所以每一条被排除的订单都要带原因返回，
   * 而不是让调用方从「结果里没有它」去猜。
   */
  public enum ExclusionReason {
    NO_SHIPPING_ADDRESS("没有收货地址快照"),
    BUYER_MISMATCH("同一地址下的另一位买家"),
    ALREADY_CONSOLIDATED("已在其他合并里"),
    PARTIALLY_SHIPPED("已发货部分数量"),
    OUTSIDE_TIME_WINDOW("超出可合单时间窗"),
    CURRENCY_MISMATCH("币种不一致"),
    PAYMENT_METHOD_MISMATCH("支付方式不一致"),
    CROSS_SHOP_NOT_ALLOWED("规则不允许跨店铺"),
    NOT_SUBMITTED("订单未提交");

    private final String label;

    ExclusionReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 能力声明

  @Schema(description = "9.3.1.2 合单能力与上下游可用性")
  public record OrderConsolidationCapabilities(
      @Schema(description = "能力维度清单") List<Dimension> dimensions,
      @Schema(description = "合并状态机取值") List<ConsolidationStatus> statuses,
      @Schema(description = "回传状态取值") List<WritebackStatus> writebackStatuses,
      @Schema(description = "候选排除原因词表") List<ExclusionReason> exclusionReasons,
      @Schema(description = "本租户是否已配置合单规则（false 时不能落合并单）") boolean ruleConfigured,
      @Schema(description = "是否已接入 D5 平台单同步（决定 shop_code 能否真正区分店铺）") boolean platformShopResolved,
      @Schema(description = "口径与交接说明") String notes,
      @Schema(description = "下游缺口说明") String downstreamGaps) {

    @Schema(description = "能力维度：依赖什么、上游是否可用、不可用时怎么办")
    public record Dimension(
        @Schema(description = "维度码") String code,
        @Schema(description = "维度名") String label,
        @Schema(description = "是否真的可用") boolean available,
        @Schema(description = "上游来源与归属") String source,
        @Schema(description = "不可用时的原因与交接方式；可用时为 null") String unavailableReason) {}
  }

  // ------------------------------------------------------------ 合单规则

  @Schema(description = "合单规则（每租户一条）")
  public record ConsolidationRule(
      @Schema(description = "规则 id") UUID id,
      @Schema(description = "单批最多合几笔订单（下限 2）") int maxOrdersPerConsolidation,
      @Schema(description = "是否允许跨店铺合并") boolean allowCrossShop,
      @Schema(description = "可合单时间窗（小时）；null = 不限，不是 0") Integer maxWindowHours,
      @Schema(description = "币种是否必须一致") boolean requireSameCurrency,
      @Schema(description = "支付方式是否必须一致") boolean requireSamePaymentMethod,
      @Schema(description = "是否要求成员订单都有收货地址快照") boolean requireShippingAddress,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "更新时间") OffsetDateTime updatedAt) {}

  @Schema(description = "保存合单规则")
  public record ConsolidationRuleInput(
      @NotNull @Min(2) @Max(100) @Schema(description = "单批最多合几笔订单（下限 2：合单是 N→1）")
          Integer maxOrdersPerConsolidation,
      @Schema(description = "是否允许跨店铺合并（默认 true）") Boolean allowCrossShop,
      @Schema(description = "可合单时间窗（小时）；null = 不限") Integer maxWindowHours,
      @Schema(description = "币种是否必须一致（默认 true）") Boolean requireSameCurrency,
      @Schema(description = "支付方式是否必须一致（默认 true）") Boolean requireSamePaymentMethod,
      @Schema(description = "是否要求成员订单都有收货地址快照（默认 true）") Boolean requireShippingAddress) {}

  // ------------------------------------------------------------ 候选检索

  /**
   * 候选订单检索入参。
   *
   * <p>买家与地址用**收货地址快照**给出的字段表达，而不是指纹 —— 让调用方读订单详情、
   * 确认是「这个人这几张单」之后才发起检索，比让它复制一串 md5 更不容易用错。
   */
  @Schema(description = "候选订单检索：同一买家 + 同一收货地址 + 可合单状态")
  public record CandidateQuery(
      @NotBlank @Size(max = 120) @Schema(description = "收件人姓名（收货地址快照）") String recipientName,
      @Schema(description = "收件人电话（收货地址快照）") String recipientPhone,
      @Schema(description = "收件人邮箱（收货地址快照）") String recipientEmail,
      @NotBlank @Size(max = 300) @Schema(description = "地址第一行（收货地址快照）") String addressLine1,
      @Schema(description = "地址第二行") String addressLine2,
      @Schema(description = "国家码（ISO 3166-1 alpha-2）") String countryCode,
      @Schema(description = "省") String province,
      @Schema(description = "市") String city,
      @Schema(description = "区") String district,
      @Schema(description = "邮编") String postcode,
      @Schema(description = "按最早提交时间倒序（默认 true）") Boolean newestFirst) {}

  /**
   * 一条候选订单。
   *
   * <p>{@code buyerFingerprint} / {@code addressFingerprint} 是本服务用收货地址快照实算的
   * md5：它们是**判定结果**而不是入参，所以一并返回给界面做「为什么它在候选集里」的自证。
   */
  @Schema(description = "候选订单（真实 SQL 检索，不是内存过滤）")
  public record CandidateOrder(
      @Schema(description = "订单 id") UUID orderId,
      @Schema(description = "我方单号") String orderNo,
      @Schema(description = "外部/平台单号") String externalOrderNo,
      @Schema(description = "订单来源（MANUAL/PLATFORM/AMAZON/…）") String orderSource,
      @Schema(description = "推出的平台码；非平台单为空串") String platformCode,
      @Schema(description = "店铺码；D5 未交付时为空串") String shopCode,
      @Schema(description = "币种") String currency,
      @Schema(description = "支付方式") String paymentMethod,
      @Schema(description = "订单状态") String orderStatus,
      @Schema(description = "提交时刻") OffsetDateTime submittedAt,
      @Schema(description = "行数") int lineCount,
      @Schema(description = "总件数") BigDecimal totalQty,
      @Schema(description = "单独发时本来要付的运费（order_charge 的 SHIPPING 行实算）")
          BigDecimal separateShippingAmount,
      @Schema(description = "买家指纹（收货地址快照实算）") String buyerFingerprint,
      @Schema(description = "地址指纹（收货地址快照实算）") String addressFingerprint,
      @Schema(description = "实算出的运费合计") BigDecimal separateShippingTotal) {}

  /**
   * 被排除的订单与原因。
   *
   * <p>**排除项与候选集同等重要**：运营问「为什么只出来两张」时，答案必须在这里而不是靠猜。
   */
  @Schema(description = "被排除的订单及原因")
  public record ExcludedOrder(
      @Schema(description = "订单 id") UUID orderId,
      @Schema(description = "我方单号") String orderNo,
      @Schema(description = "排除原因码") ExclusionReason reason,
      @Schema(description = "排除原因中文说明") String reasonLabel) {}

  @Schema(description = "候选集合与排除项")
  public record CandidateSet(
      @Schema(description = "本批候选（已按规则过滤）") List<CandidateOrder> candidates,
      @Schema(description = "被排除的订单及原因") List<ExcludedOrder> excluded,
      @Schema(description = "同一买家指纹") String buyerFingerprint,
      @Schema(description = "同一地址指纹") String addressFingerprint,
      @Schema(description = "候选里的行数合计") int candidateCount,
      @Schema(description = "候选件数合计") BigDecimal candidateQty,
      @Schema(description = "候选运费合计（单独发本来要付的）") BigDecimal separateShippingTotal,
      @Schema(description = "合单规则是否已配置（false 时不能落合并单）") boolean ruleConfigured,
      @Schema(description = "合单规则阻断的原因；未阻断时为 null") String ruleBlockedReason) {}

  // ------------------------------------------------------------ 合并单

  @Schema(description = "落一次合并的入参")
  public record ConsolidationInput(
      @NotEmpty
          @Size(min = 2, max = 100)
          @Schema(description = "要合并的订单 id 列表；至少两笔（本模块是 N→1）")
          List<UUID> orderIds,
      @NotNull
          @Schema(description = "合并时刻（timestamptz）。由调用方显式给出，不取服务器当前时间")
          OffsetDateTime consolidatedAt,
      @Schema(description = "合并成一个包裹的运费报价；不填 = 运费节省为 null（不是 0）")
          BigDecimal consolidatedShippingAmount,
      @Schema(description = "备注") String note) {}

  @Schema(description = "合并单一行（汇总数由 DB 从成员行实算回填）")
  public record ConsolidationRow(
      @Schema(description = "合单 id") UUID id,
      @Schema(description = "合单号") String consolidationNo,
      @Schema(description = "状态") ConsolidationStatus status,
      @Schema(description = "状态中文") String statusLabel,
      @Schema(description = "买家指纹") String buyerFingerprint,
      @Schema(description = "地址指纹") String addressFingerprint,
      @Schema(description = "收件人姓名") String buyerName,
      @Schema(description = "收件人电话") String buyerPhone,
      @Schema(description = "收件人邮箱") String buyerEmail,
      @Schema(description = "可读地址") String addressLine,
      @Schema(description = "国家码") String countryCode,
      @Schema(description = "币种") String currencyCode,
      @Schema(description = "支付方式") String paymentMethod,
      @Schema(description = "成员订单数（DB 实算）") int orderCount,
      @Schema(description = "跨平台数（DB 实算）") int distinctPlatformCount,
      @Schema(description = "跨店铺数（DB 实算）") int distinctShopCount,
      @Schema(description = "是否跨店铺（DB 实算）") boolean crossShop,
      @Schema(description = "行数合计") int totalLineCount,
      @Schema(description = "件数合计") BigDecimal totalQty,
      @Schema(description = "单独发本来要付的运费合计（order_charge 实算）")
          BigDecimal separateShippingAmount,
      @Schema(description = "合并运费报价；null = 未报价") BigDecimal consolidatedShippingAmount,
      @Schema(description = "运费节省；null = 未报价（不是 0）") BigDecimal freightSavingAmount,
      @Schema(description = "合并时刻") OffsetDateTime consolidatedAt,
      @Schema(description = "确认时刻") OffsetDateTime confirmedAt,
      @Schema(description = "下发时刻") OffsetDateTime dispatchedAt,
      @Schema(description = "作废时刻") OffsetDateTime cancelledAt,
      @Schema(description = "作废原因") String cancelReason,
      @Schema(description = "备注") String note,
      @Schema(description = "乐观锁版本") long version,
      @Schema(description = "创建时刻") OffsetDateTime createdAt) {}

  @Schema(description = "成员行：各原订单的关联记录（逐单保留平台归属）")
  public record ConsolidationMember(
      @Schema(description = "成员行 id") UUID id,
      @Schema(description = "订单 id") UUID orderId,
      @Schema(description = "我方单号") String orderNo,
      @Schema(description = "平台单号（external_order_no）") String platformOrderRef,
      @Schema(description = "订单来源") String orderSource,
      @Schema(description = "平台码；非平台单为空串") String platformCode,
      @Schema(description = "店铺码；D5 未交付时为空串") String shopCode,
      @Schema(description = "币种") String currencyCode,
      @Schema(description = "支付方式") String paymentMethod,
      @Schema(description = "行数（DB 实算）") int lineCount,
      @Schema(description = "件数（DB 实算）") BigDecimal totalQty,
      @Schema(description = "该单独发时的运费（DB 实算）") BigDecimal separateShippingAmount,
      @Schema(description = "成员状态") MemberStatus memberStatus,
      @Schema(description = "状态中文") String memberStatusLabel) {}

  @Schema(description = "物流号回传任务（一行一个平台订单）")
  public record WritebackTask(
      @Schema(description = "任务 id") UUID id,
      @Schema(description = "成员行 id") UUID memberId,
      @Schema(description = "合单 id") UUID consolidationId,
      @Schema(description = "平台码") String platformCode,
      @Schema(description = "店铺码") String shopCode,
      @Schema(description = "平台单号") String platformOrderRef,
      @Schema(description = "物流号") String trackingNumber,
      @Schema(description = "承运商码") String carrierCode,
      @Schema(description = "状态") WritebackStatus status,
      @Schema(description = "状态中文") String statusLabel,
      @Schema(description = "尝试次数") int attemptCount,
      @Schema(description = "最后一次错误") String lastError,
      @Schema(description = "提交时刻") OffsetDateTime submittedAt,
      @Schema(description = "结算时刻") OffsetDateTime settledAt) {}

  @Schema(description = "合并单事件（append-only）")
  public record ConsolidationEvent(
      @Schema(description = "事件 id") UUID id,
      @Schema(description = "事件类型") String eventType,
      @Schema(description = "原状态") String fromStatus,
      @Schema(description = "新状态") String toStatus,
      @Schema(description = "操作人成员 id") UUID actorMembershipId,
      @Schema(description = "说明") String detail,
      @Schema(description = "发生时刻") OffsetDateTime occurredAt) {}

  @Schema(description = "合并单详情：头 + 成员 + 回传任务 + 事件流")
  public record ConsolidationDetail(
      @Schema(description = "合并单") ConsolidationRow consolidation,
      @Schema(description = "成员行（各原订单的关联记录）") List<ConsolidationMember> members,
      @Schema(description = "物流号回传任务（逐个平台订单）") List<WritebackTask> writebackTasks,
      @Schema(description = "事件流") List<ConsolidationEvent> events,
      @Schema(description = "读这一页时必须知道的事") String notes) {}

  @Schema(description = "合并单列表查询")
  public record ConsolidationQuery(
      @Schema(description = "合单号或我方单号模糊匹配") String keyword,
      @Schema(description = "状态筛选") String status,
      @Schema(description = "收件人姓名模糊匹配") String buyerName,
      @Schema(description = "只看跨店铺合并") Boolean crossShopOnly,
      @Schema(description = "页码（从 0 开始）") int page,
      @Schema(description = "每页行数") int size) {}

  @Schema(description = "合并单分页")
  public record ConsolidationPage(
      @Schema(description = "本页数据") List<ConsolidationRow> items,
      @Schema(description = "总条数（不受分页影响）") long total,
      @Schema(description = "当前页") int page,
      @Schema(description = "每页行数") int size,
      @Schema(description = "汇总：已下发批次数（不受分页影响）") long dispatchedCount,
      @Schema(description = "汇总：待回传任务数（不受分页影响）") long pendingWritebackCount,
      @Schema(description = "汇总：已实算的运费节省合计；null = 没有任何一单报过合并运费价")
          BigDecimal freightSavingTotal) {}

  @Schema(description = "推进合并单状态")
  public record ConsolidationStatusInput(
      @NotNull @Schema(description = "目标状态：CONFIRMED / DISPATCHED / CANCELLED") ConsolidationStatus status,
      @Schema(description = "作废原因；status=CANCELLED 时必填") String reason,
      @NotNull @Schema(description = "发生时刻（timestamptz）；CANCELLED 时也用它作作废时刻")
          OffsetDateTime occurredAt) {}

  @Schema(description = "登记物流号回传结果")
  public record WritebackInput(
      @NotNull @Schema(description = "回传状态") WritebackStatus status,
      @Schema(description = "物流号；status=CONFIRMED 时必填") String trackingNumber,
      @Schema(description = "承运商码") String carrierCode,
      @Schema(description = "失败原因；status=REJECTED 时给") String lastError,
      @Schema(description = "发生时刻（timestamptz）") OffsetDateTime occurredAt) {}

  @Schema(description = "操作结果")
  public record ConsolidationSaved(
      @Schema(description = "合单 id") UUID id,
      @Schema(description = "合单号") String consolidationNo,
      @Schema(description = "状态") ConsolidationStatus status,
      @Schema(description = "提示语（说清这一步实际做了什么，而不是「操作成功」）") String message) {}

  @Schema(description = "回传任务列表查询")
  public record WritebackQuery(
      @Schema(description = "合单 id") UUID consolidationId,
      @Schema(description = "平台码") String platformCode,
      @Schema(description = "状态筛选") String status,
      @Schema(description = "平台单号模糊匹配") String platformOrderRef,
      @Schema(description = "页码（从 0 开始）") int page,
      @Schema(description = "每页行数") int size) {}

  @Schema(description = "回传任务分页")
  public record WritebackPage(
      @Schema(description = "本页数据") List<WritebackTask> items,
      @Schema(description = "总条数") long total,
      @Schema(description = "当前页") int page,
      @Schema(description = "每页行数") int size,
      @Schema(description = "汇总：待回传（不含已回传）") long pendingCount,
      @Schema(description = "汇总：平台拒绝") long rejectedCount,
      @Schema(description = "汇总：结果待查证") long pendingVerificationCount) {}

  @Schema(description = "币种码校验（ISO 4217 alpha-3）")
  @Pattern(regexp = "^[A-Z]{3}$", message = "币种必须是 ISO 4217 三位大写码")
  public record CurrencyCode(String code) {}
}
