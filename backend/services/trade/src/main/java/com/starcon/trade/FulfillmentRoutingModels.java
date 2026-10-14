package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 9.3.2.2 FBA 与自发货选择的传输模型。
 *
 * <p>蓝图原话：「在平台仓（FBA/MFN）与自有/第三方仓之间选择履约路径：优先用平台仓（买家体验好）、 平台仓缺货时切自发货（需符合平台的多渠道配送要求）、或按成本优先。」输出是
 * 「履约路径决策 + 缺货时切换记录」—— 后者是硬输出，所以模型里 {@link RoutingDecisionDetail} 永远把切换记录一起带出来。
 *
 * <p><b>三条建模纪律</b>：
 *
 * <ul>
 *   <li><b>路径与数量/成本口径只有一个来源</b>：{@link PathKind} 与 {@link SwitchTriggerReason} 一字不改地对应 DB 的 CHECK
 *       词表， Java 侧不维护第二份可写别的取值的枚举。
 *   <li><b>「不可用」必须带原因</b>：{@link RoutingCandidate} 的 {@code infeasibleReasonCode} 与 DB CHECK
 *       配对，界面上「没选它」永远能答出为什么。
 *   <li><b>金额全程 BigDecimal</b>，结算口径 HALF_UP 到分（与 10.5.1.3 同一约定）。
 * </ul>
 */
public final class FulfillmentRoutingModels {

  private FulfillmentRoutingModels() {}

  // ------------------------------------------------------------ 枚举

  /** 履约路径（DB 词表，一字不改）。 */
  public enum PathKind {
    PLATFORM_FBA("平台仓 FBA"),
    MERCHANT_OWN("自有仓自发货"),
    THIRD_PARTY("第三方仓自发货");

    private final String label;

    PathKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 决策策略（租户口径，落库存档而不是代码常量）。 */
  public enum RoutingPolicy {
    PLATFORM_FIRST("优先平台仓"),
    COST_FIRST("成本优先"),
    MERCHANT_FIRST("优先自发货");

    private final String label;

    RoutingPolicy(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 多渠道配送资质状态。UNKNOWN 是缺省值 —— 没登记就是不知道，不是可以。 */
  public enum McfStatus {
    AUTHORIZED("已授权"),
    DENIED("平台无权限"),
    PENDING("审核中"),
    UNKNOWN("未核验");

    private final String label;

    McfStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 缺货切换的触发原因。 */
  public enum SwitchTriggerReason {
    PLATFORM_STOCK_SHORTAGE("平台仓可用量不足"),
    PLATFORM_STOCK_MISSING("平台仓无库存快照"),
    MCF_NOT_AUTHORIZED("无多渠道配送资质"),
    COST_ADVANTAGE("自发货成本更优"),
    LEAD_TIME_ADVANTAGE("自发货时效更优"),
    MANUAL_OVERRIDE("人工改判");

    private final String label;

    SwitchTriggerReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 候选不可用的原因码。空串表示可用。 */
  public enum CandidateBlockReason {
    NO_STOCK_RECORD("无库存快照"),
    STOCK_ZERO("库存为 0"),
    INSUFFICIENT_STOCK("库存不足"),
    NO_COST_PROFILE("未配成本参数"),
    MCF_NOT_AUTHORIZED("店铺无多渠道配送资质");

    private final String label;

    CandidateBlockReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 决策状态。CONFIRMED / CANCELLED 是终态。 */
  public enum DecisionStatus {
    DECIDED("已决策"),
    CONFIRMED("已确认"),
    CANCELLED("已作废");

    private final String label;

    DecisionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ MCF 资质

  /** MCF 资质列表查询。 */
  public record McfQuery(
      String platformCode, String shopCode, String status, String keyword, int page, int size) {}

  /**
   * 一个店铺在一个平台上的多渠道配送资质。
   *
   * @param expired 已过期（{@code expiresAt <= now}）。与服务层状态分列， 因为「有资质但昨天过期了」与「从没核验」在决策上答案不同：前者仍不许切自发货。
   */
  public record McfAuthorizationRow(
      UUID id,
      String platformCode,
      String shopCode,
      McfStatus status,
      boolean expired,
      String authorizationSource,
      String sourceReference,
      @Schema(nullable = true) OffsetDateTime verifiedAt,
      @Schema(nullable = true) OffsetDateTime expiresAt,
      String note,
      UUID createdByMembershipId,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** MCF 资质列表（真分页，total 不受分页影响）。 */
  public record McfPage(List<McfAuthorizationRow> items, long total, int page, int size) {}

  /**
   * 登记 / 更新 MCF 资质。
   *
   * <p>{@code status=AUTHORIZED} 时 {@code verifiedAt} 与 {@code sourceReference} 或 {@code note} 必填
   * —— 没有出处的「已授权」会让缺货自动切自发货失去合规依据。
   */
  public record McfAuthorizationInput(
      @NotBlank @Size(max = 32) String platformCode,
      @NotBlank @Size(max = 64) String shopCode,
      @NotBlank @Size(max = 16) String status,
      @Schema(nullable = true) OffsetDateTime verifiedAt,
      @Schema(nullable = true) OffsetDateTime expiresAt,
      @Size(max = 128) String sourceReference,
      @Size(max = 400) String note) {}

  /** MCF 资质写入结果。 */
  public record McfSaved(
      UUID id, String platformCode, String shopCode, McfStatus status, String message) {}

  // ------------------------------------------------------------ 成本与时效参数

  /** 成本参数列表查询。 */
  public record CostProfileQuery(
      String platformCode,
      String pathKind,
      String destinationCountryCode,
      Boolean effectiveOnly,
      int page,
      int size) {}

  /** 一条履约路径的成本与时效参数。 */
  public record CostProfileRow(
      UUID id,
      String platformCode,
      PathKind pathKind,
      String destinationCountryCode,
      BigDecimal unitCost,
      @Schema(nullable = true) BigDecimal leadTimeDays,
      BigDecimal platformFee,
      String currencyCode,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String notes,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 成本参数列表（真分页）。 */
  public record CostProfilePage(List<CostProfileRow> items, long total, int page, int size) {}

  /** 维护一条成本与时效参数。 */
  public record CostProfileInput(
      @NotBlank @Size(max = 32) String platformCode,
      @NotBlank @Size(max = 24) String pathKind,
      @NotBlank @Size(max = 2) String destinationCountryCode,
      @NotNull BigDecimal unitCost,
      @Schema(nullable = true) BigDecimal leadTimeDays,
      BigDecimal platformFee,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      @Size(max = 300) String notes) {}

  /** 成本参数写入结果。 */
  public record CostProfileSaved(UUID id, String platformCode, PathKind pathKind, String message) {}

  // ------------------------------------------------------------ 渠道库存快照

  /** 库存快照列表查询。 */
  public record StockSnapshotQuery(
      String platformCode,
      String shopCode,
      String skuRef,
      String pathKind,
      String stockSource,
      int page,
      int size) {}

  /** 一条渠道库存快照。 */
  public record StockSnapshotRow(
      UUID id,
      String platformCode,
      String shopCode,
      String skuRef,
      PathKind pathKind,
      String warehouseRef,
      String warehouseName,
      BigDecimal availableQty,
      OffsetDateTime collectedAt,
      String stockSource,
      String sourceReference,
      String notes,
      OffsetDateTime createdAt) {}

  /** 库存快照列表（真分页）。 */
  public record StockSnapshotPage(List<StockSnapshotRow> items, long total, int page, int size) {}

  /** 登记一条渠道库存快照。 */
  public record StockSnapshotInput(
      @NotBlank @Size(max = 32) String platformCode,
      @NotBlank @Size(max = 64) String shopCode,
      @NotBlank @Size(max = 64) String skuRef,
      @NotBlank @Size(max = 24) String pathKind,
      @Size(max = 64) String warehouseRef,
      @Size(max = 150) String warehouseName,
      @NotNull BigDecimal availableQty,
      @NotNull OffsetDateTime collectedAt,
      @Size(max = 24) String stockSource,
      @Size(max = 128) String sourceReference,
      @Size(max = 300) String notes) {}

  /** 库存快照写入结果。 */
  public record StockSnapshotSaved(UUID id, String skuRef, PathKind pathKind, String message) {}

  // ------------------------------------------------------------ 路径评估与决策

  /**
   * 路径评估请求：给一组输入，问「应该走哪条路、为什么」。
   *
   * <p>刻意**不落库**：评估是只读试算，页面用它做「如果按现状会怎么判」的预演； 真正下决策走 {@link RoutingDecisionRequest}（会写决策 + 候选 +
   * 切换记录）。 把两者合成一个端点会导致「看一眼」也在库里留下决策行。
   */
  public record RoutingEvaluationRequest(
      @NotBlank @Size(max = 64) String orderRef,
      @Size(max = 64) String orderLineRef,
      @NotBlank @Size(max = 32) String platformCode,
      @NotBlank @Size(max = 64) String shopCode,
      @NotBlank @Size(max = 64) String skuRef,
      @NotNull BigDecimal requestedQty,
      @Schema(nullable = true) BigDecimal platformAvailableQty,
      @Schema(nullable = true) OffsetDateTime at) {}

  /** 落一条路径决策的请求。{@code reason} 留空时由服务层按判定结果生成一句人话。 */
  public record RoutingDecisionRequest(
      @NotBlank @Size(max = 64) String orderRef,
      @Size(max = 64) String orderLineRef,
      @NotBlank @Size(max = 32) String platformCode,
      @NotBlank @Size(max = 64) String shopCode,
      @NotBlank @Size(max = 64) String skuRef,
      @NotNull BigDecimal requestedQty,
      @NotBlank @Size(max = 16) String selectedPolicy,
      @Schema(nullable = true) String policySource,
      @Schema(nullable = true) Boolean forceMerchantPath,
      @Size(max = 500) String reason,
      @Schema(nullable = true) OffsetDateTime at) {}

  /** 一条候选路径的评估结果。 */
  public record RoutingCandidateRow(
      PathKind pathKind,
      @Schema(nullable = true) BigDecimal availableQty,
      @Schema(nullable = true) BigDecimal unitCost,
      @Schema(nullable = true) BigDecimal leadTimeDays,
      boolean feasible,
      String infeasibleReasonCode,
      String infeasibleReason,
      boolean chosen) {}

  /**
   * 路径评估结果（只读，不落库）。
   *
   * @param mcfStatus 该店铺当前资质 —— {@code UNKNOWN} 时任何自发货路径都不可行， 这是蓝图「必须先验证该店铺的多渠道配送资质」的落点。
   * @param mcfUsable 资质**此刻**是否真的可用（AUTHORIZED 且未过期）， 与 {@code mcfStatus} 分列：「有资质但已过期」两列答案不同。
   */
  public record RoutingEvaluation(
      String platformCode,
      String shopCode,
      String skuRef,
      BigDecimal requestedQty,
      RoutingPolicy policy,
      McfStatus mcfStatus,
      boolean mcfUsable,
      boolean platformEligible,
      BigDecimal platformAvailableQty,
      @Schema(nullable = true) BigDecimal platformUnitCost,
      @Schema(nullable = true) BigDecimal merchantUnitCost,
      @Schema(nullable = true) BigDecimal selectedUnitCost,
      PathKind chosenPath,
      String reasonCode,
      String reason,
      List<RoutingCandidateRow> candidates,
      List<String> warnings,
      OffsetDateTime at) {}

  /** 决策列表查询。 */
  public record DecisionQuery(
      @Schema(nullable = true) String orderRef,
      String platformCode,
      String shopCode,
      String skuRef,
      String chosenPath,
      String reasonCode,
      String status,
      String keyword,
      int page,
      int size) {}

  /** 一条履约路径决策（列表行与单条同形状）。 */
  public record RoutingDecisionRow(
      UUID id,
      String orderRef,
      String orderLineRef,
      String platformCode,
      String shopCode,
      String skuRef,
      BigDecimal requestedQty,
      RoutingPolicy selectedPolicy,
      String policySource,
      PathKind chosenPath,
      String reasonCode,
      String reason,
      BigDecimal platformAvailableQty,
      @Schema(nullable = true) BigDecimal platformUnitCost,
      @Schema(nullable = true) BigDecimal merchantUnitCost,
      @Schema(nullable = true) BigDecimal selectedUnitCost,
      String currencyCode,
      McfStatus mcfStatus,
      DecisionStatus status,
      OffsetDateTime decidedAt,
      @Schema(nullable = true) OffsetDateTime confirmedAt,
      OffsetDateTime createdAt) {}

  /** 决策列表（真分页；汇总不受分页影响）。 */
  public record DecisionPage(
      List<RoutingDecisionRow> items,
      long total,
      int page,
      int size,
      long platformPathCount,
      long merchantPathCount,
      long switchedCount,
      long pendingCount) {}

  /** 一次缺货切换的留痕。 */
  public record RoutingSwitchRow(
      UUID id,
      UUID decisionId,
      String orderRef,
      PathKind fromPath,
      PathKind toPath,
      SwitchTriggerReason triggerReason,
      String reason,
      BigDecimal platformAvailableQty,
      BigDecimal requestedQty,
      OffsetDateTime switchedAt,
      String switchSource,
      String sourceReference) {}

  /** 切换记录列表（真分页）。 */
  public record SwitchPage(List<RoutingSwitchRow> items, long total, int page, int size) {}

  /** 切换记录查询。 */
  public record SwitchQuery(
      @Schema(nullable = true) String orderRef,
      @Schema(nullable = true) UUID decisionId,
      String platformCode,
      String triggerReason,
      int page,
      int size) {}

  /** 决策详情：决策 + 候选 + 切换记录 + 事件流一次给全。 */
  public record RoutingDecisionDetail(
      RoutingDecisionRow decision,
      List<RoutingCandidateRow> candidates,
      List<RoutingSwitchRow> switches,
      List<RoutingEventRow> events,
      String note) {}

  /** 事件时间线的一行。 */
  public record RoutingEventRow(
      UUID id,
      String eventKind,
      String fromStatus,
      String toStatus,
      String detail,
      @Schema(nullable = true) UUID actorId,
      OffsetDateTime occurredAt) {}

  /** 决策写入结果（含切换记录 id —— 缺货切换必须可见，不只是「内部记了一笔」）。 */
  public record RoutingDecisionSaved(
      UUID decisionId,
      String orderRef,
      PathKind chosenPath,
      String reasonCode,
      String reason,
      @Schema(nullable = true) UUID switchId,
      SwitchTriggerReason switchTrigger,
      long candidateCount,
      String message) {}

  /** 状态推进请求。 */
  public record DecisionStatusInput(
      @NotBlank @Size(max = 16) String status, @Size(max = 300) String reason) {}

  // ------------------------------------------------------------ 能力声明

  /**
   * 本模块的能力与上游可用性声明。
   *
   * <p>存在的理由与 7.7.1.1 一致：把「哪些输入真有依据」显式报出来， 而不是让调用方从一个 404 或空数组里去猜是哪一环没通。
   *
   * @param upstreamNote 上游依赖实查结论的对外说明（7.1.3.1 / 2.1.1.2 已交付但跨服务， 本模块自维护快照与资质登记；10
   *     运费试算未开发，成本口径由租户维护）
   */
  public record FulfillmentRoutingCapabilities(
      List<Dimension> dimensions,
      List<PathKind> paths,
      List<RoutingPolicy> policies,
      List<SwitchTriggerReason> switchTriggers,
      boolean crossServiceReadAvailable,
      String upstreamNote,
      String note) {

    /**
     * 一个输入维度是否可用。
     *
     * @param source 该维度的上游归属（服务 + 模块），便于调用方知道去哪补
     */
    public record Dimension(
        String code,
        String label,
        boolean available,
        String source,
        @Schema(nullable = true) String unavailableReason) {}
  }
}
