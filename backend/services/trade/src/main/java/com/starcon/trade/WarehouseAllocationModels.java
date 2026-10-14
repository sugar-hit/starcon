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
 * 9.3.2.1 分仓规则的传输模型。
 *
 * <p>蓝图原话：「配置并执行仓库分配：按库存可用性、运费成本、时效承诺、货权归属、 买家地区匹配度选择发货仓；支持优先级与兜底策略。」输出是 「分配结果（发货仓 +
 * 分配数量）」与「无法分配的异常挂起」—— 后者是硬输出， 所以 {@link WarehouseAllocationDecisionDetail} 永远把挂起记录一起带出来， 且 {@link
 * WarehouseAllocationSimulation} 里每个候选仓都带自己的排除原因。
 *
 * <p><b>命名纪律（这条是硬要求，不是风格偏好）</b>：springdoc 用 record 的 <b>简单类名</b>做 {@code components.schemas}
 * 的键，而本仓已有 {@code WarehouseRow}（warehouse）、{@code AllocationRequest} / {@code
 * AllocationResult}（warehouse 7.1.3.1）等同名 record，同名会互相覆盖。 因此本文件所有 record 与 enum 一律以 {@code
 * WarehouseAllocation} 开头， 嵌套的维度声明同样起名 {@link WarehouseAllocationDimension} 而不是 {@code Dimension}
 * —— 9.3.2.2 的嵌套 {@code Dimension} 就已经占了这个键。
 *
 * <p><b>三条建模纪律</b>：
 *
 * <ul>
 *   <li><b>词表与数量/成本口径只有一个来源</b>：各枚举一字不改地对应 DB 的 CHECK 词表， Java 侧不维护第二份可写别的取值的枚举。
 *   <li><b>货权是参与判定的维度，不是附注</b>：候选行上 {@code legalEntityCode} / {@code legalEntityMatch} / {@code
 *       scoreLegalEntity} 各占一列，决策行上 {@code crossLegalEntity} 如实标记是否跨法人。
 *   <li><b>金额全程 BigDecimal</b>，结算口径 HALF_UP 到分（与 10.5.1.3 同一约定）。
 * </ul>
 */
public final class WarehouseAllocationModels {

  private WarehouseAllocationModels() {}

  // ------------------------------------------------------------ 枚举

  /** 选仓主排序口径（租户口径，落库存档而不是代码常量）。 */
  public enum WarehouseSelectionPolicy {
    HIGHEST_SCORE("综合分最高"),
    LOWEST_COST("运费最低"),
    CLOSEST_LEAD_TIME("时效最短");

    private final String label;

    WarehouseSelectionPolicy(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 兜底策略：首选找不到可行仓时怎么办。SUSPEND 是缺省值 —— 不静默兜底。 */
  public enum WarehouseFallbackPolicy {
    SUSPEND("挂起不分配"),
    ALLOW_PARTIAL("允许部分分配"),
    CROSS_LEGAL_ENTITY("允许跨法人发货");

    private final String label;

    WarehouseFallbackPolicy(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 货权维度的打分口径。 */
  public enum WarehouseLegalEntityPolicy {
    NEUTRAL("不偏好"),
    ORDER_OWNER_FIRST("订单货权主体优先"),
    PREFERRED_FIRST("指定法人优先");

    private final String label;

    WarehouseLegalEntityPolicy(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 分配状态。SUSPENDED 与 RELEASED 是终态。 */
  public enum WarehouseAllocationStatus {
    ALLOCATED("已分配"),
    PARTIAL("部分分配"),
    SUSPENDED("已挂起"),
    CONFIRMED("已确认"),
    RELEASED("已下发");

    private final String label;

    WarehouseAllocationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 候选仓被排除的原因码。空串表示可行。 */
  public enum WarehouseCandidateBlockReason {
    WAREHOUSE_INACTIVE("仓库已停用"),
    WAREHOUSE_EXCLUDED("规则排除该仓"),
    LEGAL_ENTITY_MISMATCH("货权法人不符"),
    NO_STOCK_RECORD("无库存估算"),
    STOCK_ZERO("库存为 0"),
    INSUFFICIENT_STOCK("库存不足"),
    NO_SHIPPING_ESTIMATE("无运费估算"),
    LEAD_TIME_EXCEEDED("时效超出承诺");

    private final String label;

    WarehouseCandidateBlockReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 分配决策的原因码。 */
  public enum WarehouseAllocationReasonCode {
    SCORE_BASED("按综合分选定"),
    LOWEST_COST("按运费最低选定"),
    CLOSEST_LEAD_TIME("按时效最短选定"),
    PARTIAL_ALLOCATION("部分分配"),
    NO_ELIGIBLE_WAREHOUSE("无可行仓，已挂起");

    private final String label;

    WarehouseAllocationReasonCode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 挂起原因。 */
  public enum WarehouseHoldReason {
    NO_ELIGIBLE_WAREHOUSE("无可行仓"),
    NO_STOCK_RECORD("全部仓无库存估算"),
    INSUFFICIENT_STOCK("全部仓库存不足"),
    NO_SHIPPING_ESTIMATE("全部仓无运费估算"),
    BLOCKED_BY_LEGAL_ENTITY("全部仓货权法人不符"),
    BLOCKED_BY_RULE("全部仓被规则排除"),
    PARTIAL_SHORTFALL("部分分配差额未满足");

    private final String label;

    WarehouseHoldReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 规则配置

  /** 规则列表查询。 */
  public record WarehouseAllocationRuleQuery(
      @Schema(nullable = true) String ruleCode,
      @Schema(nullable = true) String selectionPolicy,
      @Schema(nullable = true) String fallbackPolicy,
      @Schema(nullable = true) Boolean effectiveOnly,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}

  /** 一条分仓规则。权重合计恒为 1.0000（DB CHECK 钉死）。 */
  public record WarehouseAllocationRuleRow(
      UUID id,
      String ruleCode,
      String ruleName,
      int priority,
      String scopePlatformCode,
      String scopeDestinationCountryCode,
      WarehouseSelectionPolicy selectionPolicy,
      WarehouseFallbackPolicy fallbackPolicy,
      BigDecimal weightStock,
      BigDecimal weightCost,
      BigDecimal weightLeadTime,
      BigDecimal weightLegalEntity,
      BigDecimal weightRegion,
      boolean requireLegalEntityMatch,
      WarehouseLegalEntityPolicy legalEntityPolicy,
      String preferredLegalEntityCode,
      @Schema(nullable = true) BigDecimal maxLeadTimeDays,
      String currencyCode,
      String excludedWarehouseRefs,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String notes,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 规则列表（真分页，total 不受分页影响）。 */
  public record WarehouseAllocationRulePage(
      List<WarehouseAllocationRuleRow> items, long total, int page, int size) {}

  /**
   * 维护一条分仓规则。
   *
   * <p>五个权重必须合计 1.0000：权重不是「随便给的大数」，合计不为 1 时 同样输入在不同租户口径下不可比，也说不清谁更重要。服务层与 DB 双向校验。
   */
  public record WarehouseAllocationRuleInput(
      @NotBlank @Size(max = 64) String ruleCode,
      @NotBlank @Size(max = 150) String ruleName,
      @NotNull Integer priority,
      @Size(max = 32) String scopePlatformCode,
      @Size(max = 2) String scopeDestinationCountryCode,
      @NotBlank @Size(max = 24) String selectionPolicy,
      @Size(max = 24) String fallbackPolicy,
      @NotNull BigDecimal weightStock,
      @NotNull BigDecimal weightCost,
      @NotNull BigDecimal weightLeadTime,
      BigDecimal weightLegalEntity,
      BigDecimal weightRegion,
      @Schema(nullable = true) Boolean requireLegalEntityMatch,
      @Size(max = 24) String legalEntityPolicy,
      @Size(max = 64) String preferredLegalEntityCode,
      @Schema(nullable = true) BigDecimal maxLeadTimeDays,
      @Size(max = 1000) String excludedWarehouseRefs,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      @Size(max = 400) String notes) {}

  /** 规则写入结果。 */
  public record WarehouseAllocationRuleSaved(
      UUID id, String ruleCode, int priority, String message) {}

  // ------------------------------------------------------------ 仓库档案（货权在这里）

  /** 仓库档案列表查询。 */
  public record WarehouseAllocationWarehouseQuery(
      @Schema(nullable = true) String legalEntityCode,
      @Schema(nullable = true) String regionCode,
      @Schema(nullable = true) Boolean activeOnly,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}

  /**
   * 一个发货仓的档案。
   *
   * @param legalEntityCode 货权归属：该仓的货记在哪个法人名下，收入按此法人记账。 这是**仓库的属性**（蓝图的加粗句所在），不是分配结果里的附注。
   */
  public record WarehouseAllocationWarehouseRow(
      UUID id,
      String warehouseRef,
      String warehouseName,
      String legalEntityCode,
      String regionCode,
      int defaultPriority,
      boolean active,
      String notes,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 仓库档案列表（真分页）。 */
  public record WarehouseAllocationWarehousePage(
      List<WarehouseAllocationWarehouseRow> items, long total, int page, int size) {}

  /** 维护一个发货仓的档案。货权必填 —— 不知道货记在谁名下时不允许建档。 */
  public record WarehouseAllocationWarehouseInput(
      @NotBlank @Size(max = 64) String warehouseRef,
      @Size(max = 150) String warehouseName,
      @NotBlank @Size(max = 64) String legalEntityCode,
      @Size(max = 32) String regionCode,
      @Schema(nullable = true) Integer defaultPriority,
      @Schema(nullable = true) Boolean active,
      @Size(max = 300) String notes) {}

  /** 仓库档案写入结果。 */
  public record WarehouseAllocationWarehouseSaved(
      UUID id, String warehouseRef, String legalEntityCode, String message) {}

  // ------------------------------------------------------------ 库存估算

  /** 库存估算列表查询。 */
  public record WarehouseAllocationStockQuery(
      @Schema(nullable = true) String warehouseRef,
      @Schema(nullable = true) String skuRef,
      @Schema(nullable = true) String stockSource,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}

  /** 一个仓一个 SKU 的可用量估算。 */
  public record WarehouseAllocationStockRow(
      UUID id,
      String warehouseRef,
      String skuRef,
      BigDecimal availableQty,
      OffsetDateTime collectedAt,
      String stockSource,
      String sourceReference,
      String notes,
      OffsetDateTime createdAt) {}

  /** 库存估算列表（真分页）。 */
  public record WarehouseAllocationStockPage(
      List<WarehouseAllocationStockRow> items, long total, int page, int size) {}

  /** 登记一个仓的可用量估算（强制带来源与采集时间）。 */
  public record WarehouseAllocationStockInput(
      @NotBlank @Size(max = 64) String warehouseRef,
      @NotBlank @Size(max = 64) String skuRef,
      @NotNull BigDecimal availableQty,
      @NotNull OffsetDateTime collectedAt,
      @Size(max = 24) String stockSource,
      @Size(max = 128) String sourceReference,
      @Size(max = 300) String notes) {}

  /** 库存估算写入结果。 */
  public record WarehouseAllocationStockSaved(
      UUID id, String warehouseRef, String skuRef, BigDecimal availableQty, String message) {}

  // ------------------------------------------------------------ 运费与时效估算

  /** 运费估算列表查询。 */
  public record WarehouseAllocationShippingQuery(
      @Schema(nullable = true) String warehouseRef,
      @Schema(nullable = true) String skuRef,
      @Schema(nullable = true) String destinationCountryCode,
      @Schema(nullable = true) String costSource,
      @Schema(nullable = true) Boolean effectiveOnly,
      int page,
      int size) {}

  /** 一个仓发往某目的国、某 SKU 的单票运费与时效估算。 */
  public record WarehouseAllocationShippingRow(
      UUID id,
      String warehouseRef,
      String skuRef,
      String destinationCountryCode,
      BigDecimal shippingCost,
      @Schema(nullable = true) BigDecimal leadTimeDays,
      String currencyCode,
      String costSource,
      String sourceReference,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String notes,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 运费估算列表（真分页）。 */
  public record WarehouseAllocationShippingPage(
      List<WarehouseAllocationShippingRow> items, long total, int page, int size) {}

  /** 维护一条运费与时效估算。 */
  public record WarehouseAllocationShippingInput(
      @NotBlank @Size(max = 64) String warehouseRef,
      @NotBlank @Size(max = 64) String skuRef,
      @NotBlank @Size(max = 2) String destinationCountryCode,
      @NotNull BigDecimal shippingCost,
      @Schema(nullable = true) BigDecimal leadTimeDays,
      @Size(max = 24) String costSource,
      @Size(max = 128) String sourceReference,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      @Size(max = 300) String notes) {}

  /** 运费估算写入结果。 */
  public record WarehouseAllocationShippingSaved(
      UUID id,
      String warehouseRef,
      String destinationCountryCode,
      BigDecimal shippingCost,
      String message) {}

  // ------------------------------------------------------------ 试算与分配

  /**
   * 试算请求：给一组输入，问「应该从哪个仓发、为什么、每个仓分别如何」。
   *
   * <p>刻意**不落库**：试算是只读预演，页面用它做「如果按现状会怎么判」； 真正执行分配走 {@link WarehouseAllocationDecisionRequest}（会写决策
   * + 候选 + 挂起）。 把两者合成一个端点会导致「看一眼」也在库里留下分配行。
   */
  public record WarehouseAllocationSimulationRequest(
      @NotBlank @Size(max = 64) String orderRef,
      @Size(max = 64) String orderLineRef,
      @Size(max = 64) String platformCode,
      @NotBlank @Size(max = 64) String skuRef,
      @NotNull BigDecimal requestedQty,
      @NotBlank @Size(max = 2) String destinationCountryCode,
      @Size(max = 32) String destinationRegionCode,
      @NotBlank @Size(max = 64) String orderLegalEntityCode,
      @Schema(nullable = true) String ruleCode,
      @Schema(nullable = true) OffsetDateTime at) {}

  /**
   * 执行一次分仓分配的请求。
   *
   * @param ruleCode 指定规则；留空则按适用范围 + 优先级自动命中（数字小者优先）。
   */
  public record WarehouseAllocationDecisionRequest(
      @NotBlank @Size(max = 64) String orderRef,
      @Size(max = 64) String orderLineRef,
      @Size(max = 64) String platformCode,
      @NotBlank @Size(max = 64) String skuRef,
      @NotNull BigDecimal requestedQty,
      @NotBlank @Size(max = 2) String destinationCountryCode,
      @Size(max = 32) String destinationRegionCode,
      @NotBlank @Size(max = 64) String orderLegalEntityCode,
      @Schema(nullable = true) String ruleCode,
      @Size(max = 500) String reason,
      @Schema(nullable = true) OffsetDateTime at) {}

  /** 一个候选仓的评估结果（五维得分 + 总分 + 是否可行 + 为什么被排除）。 */
  public record WarehouseAllocationCandidateRow(
      String warehouseRef,
      String warehouseName,
      String legalEntityCode,
      String regionCode,
      int warehousePriority,
      @Schema(nullable = true) BigDecimal availableQty,
      @Schema(nullable = true) BigDecimal shippingCost,
      @Schema(nullable = true) BigDecimal leadTimeDays,
      BigDecimal scoreStock,
      BigDecimal scoreCost,
      BigDecimal scoreLeadTime,
      BigDecimal scoreLegalEntity,
      BigDecimal scoreRegion,
      BigDecimal totalScore,
      boolean legalEntityMatch,
      boolean feasible,
      String infeasibleReasonCode,
      String infeasibleReason,
      boolean chosen,
      @Schema(nullable = true) BigDecimal allocatedQty) {}

  /**
   * 试算结果（只读，不落库）。
   *
   * @param orderLegalEntityCode 订单货权主体 —— 加粗句里「收入应该记在谁名下」的那个「应该」。
   * @param chosenLegalEntityCode 选中仓的法人；两者不同即跨法人发货。
   * @param crossLegalEntity 是否真的跨了法人。只有兜底策略显式放开货权硬闸才可能为 true。
   * @param feasibleCount 可行候选数。为 0 时 {@code chosenWarehouseRef} 为空， 且 {@code candidates} 里逐条带排除原因。
   */
  public record WarehouseAllocationSimulation(
      String orderRef,
      String skuRef,
      BigDecimal requestedQty,
      String destinationCountryCode,
      String destinationRegionCode,
      String orderLegalEntityCode,
      @Schema(nullable = true) String ruleCode,
      WarehouseSelectionPolicy selectionPolicy,
      WarehouseFallbackPolicy fallbackPolicy,
      WarehouseLegalEntityPolicy legalEntityPolicy,
      boolean requireLegalEntityMatch,
      BigDecimal weightStock,
      BigDecimal weightCost,
      BigDecimal weightLeadTime,
      BigDecimal weightLegalEntity,
      BigDecimal weightRegion,
      int feasibleCount,
      @Schema(nullable = true) String chosenWarehouseRef,
      @Schema(nullable = true) String chosenLegalEntityCode,
      boolean crossLegalEntity,
      @Schema(nullable = true) BigDecimal allocatedQty,
      @Schema(nullable = true) BigDecimal unitShippingCost,
      @Schema(nullable = true) BigDecimal leadTimeDays,
      @Schema(nullable = true) BigDecimal chosenTotalScore,
      WarehouseAllocationReasonCode reasonCode,
      String reason,
      @Schema(nullable = true) WarehouseHoldReason holdReason,
      List<WarehouseAllocationCandidateRow> candidates,
      List<String> warnings,
      OffsetDateTime at) {}

  /** 分配列表查询。 */
  public record WarehouseAllocationDecisionQuery(
      @Schema(nullable = true) String orderRef,
      @Schema(nullable = true) String skuRef,
      @Schema(nullable = true) String chosenWarehouseRef,
      @Schema(nullable = true) String chosenLegalEntityCode,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String reasonCode,
      @Schema(nullable = true) Boolean crossLegalEntity,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}

  /** 一次分仓分配（列表行与单条同形状）。 */
  public record WarehouseAllocationDecisionRow(
      UUID id,
      String orderRef,
      String orderLineRef,
      String skuRef,
      BigDecimal requestedQty,
      @Schema(nullable = true) String destinationCountryCode,
      String destinationRegionCode,
      String orderLegalEntityCode,
      @Schema(nullable = true) String chosenWarehouseRef,
      @Schema(nullable = true) String chosenLegalEntityCode,
      boolean crossLegalEntity,
      @Schema(nullable = true) BigDecimal allocatedQty,
      @Schema(nullable = true) BigDecimal unitShippingCost,
      @Schema(nullable = true) BigDecimal leadTimeDays,
      @Schema(nullable = true) BigDecimal chosenTotalScore,
      String currencyCode,
      WarehouseSelectionPolicy selectionPolicy,
      WarehouseFallbackPolicy fallbackPolicy,
      WarehouseLegalEntityPolicy legalEntityPolicy,
      WarehouseAllocationReasonCode reasonCode,
      String reason,
      WarehouseAllocationStatus status,
      OffsetDateTime decidedAt,
      @Schema(nullable = true) OffsetDateTime confirmedAt,
      @Schema(nullable = true) OffsetDateTime releasedAt,
      OffsetDateTime createdAt) {}

  /** 分配列表（真分页；四组汇总额不受分页影响）。 */
  public record WarehouseAllocationDecisionPage(
      List<WarehouseAllocationDecisionRow> items,
      long total,
      int page,
      int size,
      long allocatedCount,
      long suspendedCount,
      long crossLegalEntityCount,
      long partialCount) {}

  /** 分配详情：决策 + 候选 + 挂起 + 事件流一次给全。 */
  public record WarehouseAllocationDecisionDetail(
      WarehouseAllocationDecisionRow decision,
      List<WarehouseAllocationCandidateRow> candidates,
      List<WarehouseAllocationHoldRow> holds,
      List<WarehouseAllocationEventRow> events,
      String note) {}

  /** 一次挂起 / 一次差额的留痕。 */
  public record WarehouseAllocationHoldRow(
      UUID id,
      UUID decisionId,
      String orderRef,
      WarehouseHoldReason holdReason,
      String reason,
      String exclusionDetail,
      int excludedWarehouseCount,
      @Schema(nullable = true) UUID holdCandidateId,
      @Schema(nullable = true) BigDecimal shortfallQty,
      OffsetDateTime heldAt,
      String holdSource) {}

  /** 挂起清单查询。 */
  public record WarehouseAllocationHoldQuery(
      @Schema(nullable = true) String orderRef,
      @Schema(nullable = true) UUID decisionId,
      @Schema(nullable = true) String holdReason,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}

  /** 挂起清单（真分页）。这是「哪几单发不出去」与「该去补什么」的查询入口。 */
  public record WarehouseAllocationHoldPage(
      List<WarehouseAllocationHoldRow> items, long total, int page, int size) {}

  /** 事件时间线的一行。 */
  public record WarehouseAllocationEventRow(
      UUID id,
      String eventKind,
      String fromStatus,
      String toStatus,
      String detail,
      @Schema(nullable = true) UUID actorId,
      OffsetDateTime occurredAt) {}

  /**
   * 分配写入结果（含挂起记录 id —— 挂起是硬输出，不只是「内部记了一笔」）。
   *
   * @param status 落库后的状态。SUSPENDED 时 {@code chosenWarehouseRef} 为空， {@code holdId} 非空且 {@code
   *     holdReason} 报出整体挂起原因。
   */
  public record WarehouseAllocationDecisionSaved(
      UUID decisionId,
      String orderRef,
      WarehouseAllocationStatus status,
      WarehouseAllocationReasonCode reasonCode,
      @Schema(nullable = true) String chosenWarehouseRef,
      @Schema(nullable = true) String chosenLegalEntityCode,
      boolean crossLegalEntity,
      @Schema(nullable = true) BigDecimal allocatedQty,
      @Schema(nullable = true) UUID holdId,
      @Schema(nullable = true) WarehouseHoldReason holdReason,
      long candidateCount,
      String message) {}

  /** 状态推进请求。 */
  public record WarehouseAllocationStatusInput(
      @NotBlank @Size(max = 16) String status, @Size(max = 32) String releasedToModule) {}

  // ------------------------------------------------------------ 能力声明

  /**
   * 本模块的能力与上游可用性。
   *
   * <p>存在的理由：把「哪些输入真有依据」显式报出来，而不是让调用方从 404 或空数组里猜 哪一环没通。
   */
  public record WarehouseAllocationCapabilities(
      List<WarehouseAllocationDimension> dimensions,
      List<WarehouseSelectionPolicy> selectionPolicies,
      List<WarehouseFallbackPolicy> fallbackPolicies,
      List<WarehouseLegalEntityPolicy> legalEntityPolicies,
      List<WarehouseHoldReason> holdReasons,
      boolean crossServiceReadAvailable,
      boolean reservesUpstreamStock,
      String upstreamNote,
      String legalEntityNote,
      String note) {

    /**
     * 一个输入维度是否可用。
     *
     * @param source 该维度的上游归属（服务 + 模块），便于调用方知道去哪补
     */
    public record WarehouseAllocationDimension(
        String code,
        String label,
        boolean available,
        String source,
        @Schema(nullable = true) String unavailableReason) {}
  }
}
