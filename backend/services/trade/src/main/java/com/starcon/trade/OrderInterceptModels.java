package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 9.2.1.3 异常拦截的传输模型。
 *
 * <p>蓝图原话：「识别并拦截异常订单：地址不可达/信息不全、黑名单买家、重复下单、异常折扣（低于成本）、
 * 疑似欺诈、超出收货国家限制、平台合规风险。」输出：「拦截清单 + 拦截原因 + 处置建议（取消/转人工/放行）。」
 * 蓝图加粗句：「<b>拦截不自动取消</b>，默认转人工——误拦真实订单的代价通常高于人工看一眼。」
 *
 * <p><b>五条建模纪律</b>：
 *
 * <ul>
 *   <li><b>建议处置与最终处置是两个字段。</b>{@link InterceptCheck#recommendedDisposition}
 *       （放行 / 转人工 / 建议取消）由 V153 的触发器从八行命中明细实算；
 *       {@link InterceptCheck#finalDisposition} 只能来自 {@link InterceptDisposition} ——
 *       加粗句在传输层就分得清清楚楚。
 *   <li><b>词表里没有「自动」。</b>{@link FinalDisposition} 只有 RELEASE / HOLD / CANCEL 三值，
 *       没有 {@code AUTO_CANCEL}：DB 与传输层都不存在「系统自己取消了这一单」这个状态。
 *   <li><b>八项命中恒定各一行</b>（{@link InterceptDetail#findings} 恒 8 行）：
 *       少一行的话「这单为什么没按预期拦截」就只能从计数反推，而计数不保留是谁缺席的。
 *   <li><b>「没量到」不是 0。</b>{@link InterceptFinding#observedValue} 在该项不可用时是 {@code null}，
 *       {@link InterceptFinding#status} 说明为什么。文本型比对（收货国码、平台来源）走
 *       {@link InterceptFinding#evidenceReference}，不进数值列。
 *   <li><b>金额与数量全程 BigDecimal</b>，分别对齐 DB 的 numeric(19,4) 与 numeric(19,6)。
 * </ul>
 *
 * <p><b>record 简单类名全局唯一</b>：springdoc 用简单类名做 components.schemas 键，codegen 又把
 * 各服务的 schema 合并进同一个桶文件，重名会被<b>静默覆盖</b>、单测全绿而契约形状被改。
 * 本模块所有 record 一律带 {@code Intercept} 前缀（V150 已用
 * {@code RiskCapabilityDimension} 避开 warehouse 的 {@code Dimension}，同一手法），
 * 汇总类也不叫 {@code Summary}（V150 已占用）。
 */
public final class OrderInterceptModels {

  private OrderInterceptModels() {}

  // ------------------------------------------------------------ 枚举

  /**
   * 八个命中项（DB 词表，一字不改）。
   *
   * <p>蓝图职责句里是七类异常，本枚举按<b>可独立评估的最小单元</b>拆成八项：
   * 「地址不可达/信息不全」拆成 {@link #ADDRESS_INCOMPLETE}（现在就能判）与
   * {@link #ADDRESS_DELIVERABILITY}（依赖未交付的 9.2.2.1，现在判不了）。
   * 合成一行会让能判的那半也被一起标成「不可用」，等于把已经接通的能力也报成缺口。
   */
  public enum InterceptCheckCode {
    ADDRESS_INCOMPLETE("地址信息不全"),
    ADDRESS_DELIVERABILITY("地址不可达"),
    BLACKLISTED_BUYER("黑名单买家"),
    DUPLICATE_ORDER("重复下单"),
    BELOW_COST_DISCOUNT("异常折扣（低于成本）"),
    SUSPECTED_FRAUD("疑似欺诈"),
    COUNTRY_RESTRICTED("超出收货国家限制"),
    PLATFORM_COMPLIANCE("平台合规风险");

    private final String label;

    InterceptCheckCode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 单个命中项的可用状态。
   *
   * <p>把「不可用」拆成三种而不是一个 null，是为了让「这一项为什么没结论」在详情页上是三句不同的话：
   * 缺参照、上游没接通、这一单形态上不适用 —— 处置动作两两不同。
   */
  public enum FindingStatus {
    FLAGGED("命中"),
    CLEARED("已核对，未命中"),
    NO_REFERENCE("缺参照（无成本基线 / 无风控评估 / 买家无档案）"),
    UPSTREAM_UNAVAILABLE("上游未交付，本项判不了"),
    NOT_APPLICABLE("该单形态上不适用");

    private final String label;

    FindingStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 严重度：决定建议处置落在哪一档。
   *
   * <p>{@link #BLOCK} 是「建议取消」，<b>不是</b>「已取消」：加粗句在枚举这一层就已经生效。
   */
  public enum FindingSeverity {
    INFO("仅提示"),
    WARN("命中，建议转人工"),
    BLOCK("命中，建议取消（仍需人工）");

    private final String label;

    FindingSeverity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 取值来源：这一项的结论从哪来（DB 词表）。 */
  public enum EvidenceSource {
    DERIVED_FROM_ORDERS("本服务订单簇现算"),
    TENANT_MAINTAINED("租户自维护（限制清单 / 成本基线）"),
    MODULE_9_2_1_2_BUYER_PROFILE("9.2.1.2 买家档案（V149，同服务同库）"),
    MODULE_9_2_2_2_RISK_ASSESSMENT("9.2.2.2 风控评估（V150，同服务同库）"),
    UPSTREAM_NOT_DELIVERED("上游模块未交付");

    private final String label;

    EvidenceSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * **建议处置**（DB 词表）。
   *
   * <p>三个值都只是「建议」：{@link #CANCEL} 的语义是「建议取消，需人工复核」，
   * 它<b>不等于</b>已经取消 —— 真正的取消要等 {@link InterceptDisposition} 落一条人工结论。
   */
  public enum RecommendedDisposition {
    RELEASE("放行"),
    MANUAL_REVIEW("转人工"),
    CANCEL("建议取消（仍需人工）");

    private final String label;

    RecommendedDisposition(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * **最终处置**（DB 词表）—— 只可能来自人工处置留痕。
   *
   * <p>⚠️ 刻意<b>不含任何「自动」取值</b>：没有 AUTO_CANCEL、没有 AUTO_RELEASE。
   * 加粗句「拦截不自动取消」说的就是这件事不该发生，所以它连一个能表达「已自动取消」的状态都不给。
   */
  public enum FinalDisposition {
    RELEASE("人工确认放行（误拦）"),
    HOLD("人工确认挂起"),
    CANCEL("人工确认取消");

    private final String label;

    FinalDisposition(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 缺参照怎么办（租户可维护口径）。 */
  public enum MissingReferencePolicy {
    /** 按已评估项判定，缺参照项计入 missingCount 并点名缺什么。 */
    ASSESS_KNOWN_ONLY("按已知项判定"),
    /** 有任何一项缺参照就不出「放行」，只给「转人工」。 */
    BLOCK_DECISION("缺参照即不出放行");

    private final String label;

    MissingReferencePolicy(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 限制清单的两类限制。 */
  public enum RestrictionKind {
    SHIPPING_COUNTRY("收货国家限制"),
    PLATFORM_SOURCE("平台来源限制");

    private final String label;

    RestrictionKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 限制强度。刻意不给「放行」档：清单上写「放行」的行会让「有行 = 有问题」失效。 */
  public enum RestrictionEffect {
    BLOCK("不可发（建议取消，仍需人工）"),
    REVIEW("可疑（建议转人工）");

    private final String label;

    RestrictionEffect(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 限制来源（照抄 V150 signal_source / V116 stock_source 的既有解法）。
   *
   * <p>刻意是「模块 + 关注点」而不是伪造的 L4 模块号：这三处目前都没有任何已交付的 L4 模块
   * 是它们的交付方，编一个号会让后来的人去查一个不存在的模块。
   */
  public enum RestrictionSource {
    TENANT_MAINTAINED("租户自维护"),
    MODULE_9_2_2_1_SHIPPING("9.2.2.1 禁运国家清单（未交付）"),
    MODULE_16_PLATFORM_COMPLIANCE("16 合规平台合规清单（未交付）"),
    MODULE_2_PLATFORM_POLICY("2 平台来源合规策略（未交付）");

    private final String label;

    RestrictionSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 处置原因码。FALSE_POSITIVE 是「误拦真实订单」在数据里的正式名字。 */
  public enum DispositionReasonCode {
    CONFIRMED_ANOMALY("复核确认确有异常"),
    FALSE_POSITIVE("误拦：真实订单被错误命中"),
    PENDING_EVIDENCE("仍需材料"),
    ESCALATED_COMPLIANCE("升级 16 合规处理");

    private final String label;

    DispositionReasonCode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 能力声明

  /**
   * 能力与上下游可用性。
   *
   * <p>存在的理由：蓝图输入写的是「地址校验服务；16 合规与风控信号；5 定价；订单特征」，
   * 而其中三样在集成头**分别未开发、在别的服务的独立库、或没有任何数据源**。
   * <b>哪几项真接通、哪几项靠租户登记、哪几项现在根本判不了，必须由接口自己说</b>，
   * 而不是让调用方从空数组或 404 里猜哪一环没通。
   */
  @Schema(description = "9.2.1.3 异常拦截能力与上下游可用性")
  public record InterceptCapabilities(
      @Schema(description = "八个命中项逐项的可用性") List<InterceptCapabilityCheck> checks,
      @Schema(description = "蓝图加粗句原文与本模块的落点") String boldRule,
      @Schema(description = "判定顺序原文") String determinationOrder,
      @Schema(description = "是否已接入 15 客户 / 16 合规（跨服务读表一律禁止）")
          boolean crossServiceReadAvailable,
      @Schema(
              description = "地址可投递性校验是否可用（9.2.2.1 未交付 ⇒ ADDRESS_DELIVERABILITY 恒为 UPSTREAM_UNAVAILABLE）")
          boolean addressValidationAvailable,
      @Schema(description = "是否支持店铺（SHOP）维度 —— sales_order 无 shop_code 列，当前只支持平台级")
          boolean shopScopeSupported,
      @Schema(description = "本租户是否已登记拦截策略") boolean policyConfigured,
      @Schema(description = "上游缺口原文") String gaps) {}

  /** 单个命中项的可用性。 */
  @Schema(description = "单个命中项的可用性")
  public record InterceptCapabilityCheck(
      @Schema(description = "命中项码") String code,
      @Schema(description = "中文名") String label,
      @Schema(description = "判定输入来源原文") String source,
      @Schema(description = "是否真接通（false = 租户维护或上游未交付）") boolean upstreamDelivered,
      @Schema(description = "取哪一列阈值（真实 SQL / 租户维护 / 无阈值）") String thresholdSource,
      @Schema(description = "缺口原文；已接通为 null") String gap) {}

  // ------------------------------------------------------------ 策略

  /** 拦截策略读取形态。 */
  @Schema(description = "拦截策略（每租户一条）")
  public record InterceptPolicy(
      UUID id,
      String policyName,
      boolean enabled,
      int duplicateWindowHours,
      int duplicateMaxOrders,
      BigDecimal belowCostToleranceRate,
      BigDecimal fraudScoreThreshold,
      MissingReferencePolicy missingReferencePolicy,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 登记 / 更新拦截策略。 */
  @Schema(description = "登记拦截策略（upsert；不提供默认策略，必须显式登记）")
  public record InterceptPolicyInput(
      @NotBlank @Size(max = 120) String policyName,
      @Min(1) @Max(24 * 365) int duplicateWindowHours,
      @Min(2) @Max(1000000) int duplicateMaxOrders,
      @NotNull @DecimalMin("0.0000") @DecimalMax("1.0000") BigDecimal belowCostToleranceRate,
      @NotNull @DecimalMin(value = "0.0001", inclusive = false)
          @DecimalMax("100.0000")
          BigDecimal fraudScoreThreshold,
      @NotNull MissingReferencePolicy missingReferencePolicy) {}

  // ------------------------------------------------------------ 限制清单

  /** 限制清单行。 */
  @Schema(description = "限制清单（禁运收货国 / 平台来源合规风险）")
  public record InterceptRestriction(
      UUID id,
      RestrictionKind restrictionKind,
      String restrictionTarget,
      RestrictionEffect restrictionEffect,
      RestrictionSource restrictionSource,
      String sourceReference,
      String reason,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      boolean enabled,
      OffsetDateTime createdAt) {}

  /** 登记一条限制。 */
  @Schema(description = "登记一条限制（收货国用 ISO alpha-2 大写；平台来源必须在 sales_order 词表内）")
  public record InterceptRestrictionInput(
      @NotNull RestrictionKind restrictionKind,
      @NotBlank @Size(max = 64) String restrictionTarget,
      @NotNull RestrictionEffect restrictionEffect,
      @NotNull RestrictionSource restrictionSource,
      @Size(max = 128) String sourceReference,
      @Size(max = 400) String reason,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo) {}

  /** 限制清单分页（真分页 + 真搜索）。 */
  @Schema(description = "限制清单分页")
  public record InterceptRestrictionPage(
      List<InterceptRestriction> items, long total, int page, int size, int totalPages) {}

  // ------------------------------------------------------------ 成本基线

  /** SKU 成本基线行。 */
  @Schema(description = "SKU 成本基线（5 定价未交付期间「低于成本线」的唯一依据）")
  public record InterceptSkuCost(
      UUID id,
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal unitCost,
      String currency,
      OffsetDateTime capturedAt,
      String note,
      OffsetDateTime createdAt) {}

  /** 登记一条成本基线。 */
  @Schema(description = "登记一条 SKU 成本基线（采集时刻必填：换供应商涨价都会改它，历史结论必须可复算）")
  public record InterceptSkuCostInput(
      @NotNull UUID skuId,
      @Size(max = 64) String skuCode,
      @Size(max = 200) String skuName,
      @NotNull @DecimalMin("0.0000") BigDecimal unitCost,
      @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "币种必须是 ISO 4217 alpha-3 大写")
          String currency,
      @NotNull OffsetDateTime capturedAt,
      @Size(max = 600) String note) {}

  /** 成本基线分页（真分页 + 真搜索）。 */
  @Schema(description = "成本基线分页")
  public record InterceptSkuCostPage(
      List<InterceptSkuCost> items, long total, int page, int size, int totalPages) {}

  // ------------------------------------------------------------ 检查

  /** 一行一个命中项的明细。 */
  @Schema(description = "单个命中项明细（恒 8 行；缺参照与未接通也占行）")
  public record InterceptFinding(
      UUID id,
      InterceptCheckCode checkCode,
      FindingStatus status,
      FindingSeverity severity,
      EvidenceSource evidenceSource,
      @Schema(
              nullable = true,
              description = "数值观测值（计数 / 金额 / 风控分）；不可用为 null，不是 0。文本比对见 evidenceReference")
          BigDecimal observedValue,
      @Schema(nullable = true, description = "对照阈值") BigDecimal thresholdValue,
      @Schema(description = "在多少次比较里命中（多行同时低于成本与只有一行处置完全不同）")
          int matchCount,
      @Schema(
              nullable = true,
              description = "文本比对的实体：命中的收货国码 / 平台来源 / SKU 编码 / 档案 id / 风控评估 id")
          String evidenceReference,
      @Schema(description = "这一项为什么是现在这个结论") String explanation) {}

  /** 检查头。 */
  @Schema(description = "拦截检查头")
  public record InterceptCheck(
      UUID id,
      UUID orderId,
      String orderNo,
      String orderSource,
      @Schema(nullable = true) String buyerKey,
      String orderCurrency,
      BigDecimal orderAmount,
      OffsetDateTime orderSubmittedAt,
      @Schema(nullable = true) String shippingCountryCode,
      UUID policyId,
      String policyName,
      long policyVersion,
      OffsetDateTime assessedReferenceAt,
      @Schema(description = "命中的命中项数") int flaggedCount,
      @Schema(description = "BLOCK 级命中数（决定建议处置落在「建议取消」还是「转人工」）")
          int blockedCount,
      @Schema(description = "已核对且未命中的项数") int clearedCount,
      @Schema(description = "缺参照 / 上游未接通 / 不适用的项数") int missingCount,
      @Schema(description = "建议处置（只是建议；「建议取消」也带 manualReviewRequired=true）")
          RecommendedDisposition recommendedDisposition,
      @Schema(description = "是否需要人工处置（建议取消恒为 true —— 拦截不自动取消）")
          boolean manualReviewRequired,
      @Schema(
              nullable = true,
              description = "最终处置；只能来自人工处置留痕，未处置时为 null。词表不含任何「自动」取值")
          FinalDisposition finalDisposition,
      @Schema(nullable = true) OffsetDateTime disposedAt,
      @Schema(nullable = true) UUID disposedBy,
      @Schema(nullable = true) OffsetDateTime totalsComputedAt,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt) {}

  /** 检查详情：头 + 八行命中明细 + 人工处置留痕。 */
  @Schema(description = "拦截检查详情（头 + 八项明细 + 人工处置）")
  public record InterceptDetail(
      InterceptCheck check,
      List<InterceptFinding> findings,
      @Schema(nullable = true, description = "人工处置留痕；尚未处置时为 null")
          InterceptDisposition disposition) {}

  /** 检查列表分页（真分页 + 真搜索），带不受分页影响的汇总。 */
  @Schema(description = "拦截清单分页")
  public record InterceptCheckPage(
      List<InterceptCheck> items,
      long total,
      int page,
      int size,
      int totalPages,
      InterceptSummary summary) {}

  /** 列表页顶部的汇总数（不受筛选与分页影响）。 */
  @Schema(description = "列表汇总（不受分页影响）")
  public record InterceptSummary(
      long total,
      @Schema(description = "待人工处置数（建议取消 + 建议转人工且尚无最终处置）") long pendingDisposition,
      long releaseCount,
      long manualReviewCount,
      long cancelSuggestedCount,
      long cancelledCount,
      long heldCount,
      @Schema(description = "命中任一项的检查数") long flaggedCheckCount,
      @Schema(description = "有缺参照项的检查数") long incompleteCheckCount) {}

  /** 触发一次检查的请求体。 */
  @Schema(
      description =
          "对一张订单触发一次拦截检查（判定基准时刻恒等于订单提交时刻；"
              + "不接收「本次要不要自动取消」这类参数 —— 加粗句说拦截不自动取消）")
  public record InterceptCheckInput(@NotNull UUID orderId) {}

  /** 检查落库结果。 */
  @Schema(description = "检查落库结果（计数与建议处置由 DB 触发器实算后回读）")
  public record InterceptCheckSaved(
      InterceptCheck check, List<InterceptFinding> findings, String summary) {}

  // ------------------------------------------------------------ 人工处置

  /** 人工处置记录。 */
  @Schema(description = "人工处置记录（最终处置唯一的出口）")
  public record InterceptDisposition(
      UUID id,
      UUID checkId,
      FinalDisposition decision,
      UUID disposedBy,
      OffsetDateTime disposedAt,
      DispositionReasonCode reasonCode,
      String note) {}

  /** 提交人工处置。 */
  @Schema(description = "提交人工处置（取消的唯一合法入口；拦截本身不自动取消）")
  public record InterceptDispositionInput(
      @NotNull FinalDisposition decision,
      @NotNull DispositionReasonCode reasonCode,
      @Size(max = 2000) String note) {}

  /** 处置落库结果。 */
  @Schema(description = "处置落库结果（最终处置由 DB 触发器回填后回读）")
  public record InterceptDispositionSaved(
      InterceptCheck check, InterceptDisposition disposition, String summary) {}

  // ------------------------------------------------------------ 查询参数

  /** 检查列表查询条件。 */
  public record InterceptCheckQuery(
      String keyword,
      String recommendedDisposition,
      String checkCode,
      Boolean pendingDispositionOnly,
      String buyerKey,
      int page,
      int size) {}

  /** 限制清单查询条件。 */
  public record InterceptRestrictionQuery(
      String keyword, String restrictionKind, RestrictionEffect restrictionEffect, int page, int size) {}

  /** 成本基线查询条件。 */
  public record InterceptSkuCostQuery(String keyword, UUID skuId, int page, int size) {}
}
