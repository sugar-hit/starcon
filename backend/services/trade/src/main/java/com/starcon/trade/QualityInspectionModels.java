package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 6.4.2.1 质检单的传输模型。
 *
 * <p>蓝图原话：「来料/成品质检：抽样比例、检验项（外观、功能、规格、包装、认证标识）、检验结果记录、
 * 不合格判定与扣量。」输入「收货事件；质检模板（按类目）；抽样规则；质检结果录入」。
 * 输出「质检单（合格量/不合格量/让步接收量）；触发 7 库存的质量状态分流」。
 *
 * <p><b>命名纪律（硬要求）</b>：springdoc 用 record 的<b>简单类名</b>做 {@code components.schemas} 的键，
 * 重名会被<b>静默覆盖</b> —— 别人的字段形状被改掉、单测全绿、只有跨服务契约比对才红。
 * 本仓 trade 服务**已经**有一整套 {@code QualityInspection*}（11.x 的<b>退货质检</b>，
 * {@code /trade/return-quality}，V179），所以本模块的 record / enum 一律以
 * {@code IncomingQualityInspection} 开头（蓝图 06 卷 L3 锚点 6.4.2 的名字就叫「来料质检」）。
 *
 * <p><b>六条建模纪律</b>：
 *
 * <ul>
 *   <li><b>三量互斥且完备</b>：合格量 + 不合格量 + 让步接收量 = 本次质检覆盖的批量。
 *       缺一个数就答不出「这批货到底去了哪」。</li>
 *   <li><b>抽样是服务端算的</b>：应抽数量由模板的抽样比例 + 批量 + 夹逼 + 舍入口径算出，
 *       算式原文随单落库（{@code samplingBasis}），前端不能传。</li>
 *   <li><b>逐项结论驱动扣量</b>：没有一个「拍脑袋的总分」；不合格判定与扣量都落在逐项结论上。</li>
 *   <li><b>让步接收要权限 + 原因</b>：两者缺一拒绝（蓝图 6.4.2.2 加粗句的纪律）。</li>
 *   <li><b>「答不出来」与「不需要」不折叠</b>：没有安全属性档案时状态是 {@code NOT_COLLECTED}，
 *       不是 0 也不是 true。</li>
 *   <li><b>数量全程 BigDecimal</b>，DB 侧 numeric(19,6)；金额 numeric(19,4)。</li>
 * </ul>
 */
public final class QualityInspectionModels {

  private QualityInspectionModels() {}

  /** 中文名。服务层与前端都拿得到 label，不必各写一遍 switch —— 各写一遍迟早漂移。 */
  public interface Labelled {
    String label();
  }

  // ================================================================ 枚举

  /** 质检类型：INCOMING 来料质检 / FINISHED 成品质检。 */
  public enum IncomingInspectionType implements Labelled {
    INCOMING("来料质检"),
    FINISHED("成品质检");

    private final String label;

    IncomingInspectionType(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 舍入口径 —— 「抽 10 件」到底怎么来的，三种口径能差 1 件。 */
  public enum IncomingRoundingMode implements Labelled {
    CEILING("向上取整"),
    ROUND_HALF_UP("四舍五入"),
    FLOOR("向下取整");

    private final String label;

    IncomingRoundingMode(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 关键检验项不合格时的动作。 */
  public enum IncomingCriticalFailureAction implements Labelled {
    REJECT_LOT("整批拒收"),
    DEDUCT_ONLY("仅扣量");

    private final String label;

    IncomingCriticalFailureAction(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 不合格量的去向（蓝图加粗句：必须进入待检或残次状态）。 */
  public enum IncomingDefectiveTarget implements Labelled {
    PENDING_INSPECTION("待检"),
    DEFECTIVE("残次");

    private final String label;

    IncomingDefectiveTarget(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 蓝图点名的五个检验项。 */
  public enum IncomingItemCode implements Labelled {
    APPEARANCE("外观"),
    FUNCTION("功能"),
    SPECIFICATION("规格"),
    PACKAGING("包装"),
    CERTIFICATION("认证标识");

    private final String label;

    IncomingItemCode(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 单个检验项的结论。 */
  public enum IncomingItemResult implements Labelled {
    PASS("合格"),
    FAIL("不合格"),
    CONCESSION("让步接收");

    private final String label;

    IncomingItemResult(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 质检单状态机。 */
  public enum IncomingOrderStatus implements Labelled {
    DRAFT("草稿"),
    INSPECTING("检验中"),
    COMPLETED("已完成"),
    CANCELLED("已作废");

    private final String label;

    IncomingOrderStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }

    /** 终态：已完成的质检单不再接受结论录入，三量也不再可改。 */
    public boolean terminal() {
      return this == COMPLETED || this == CANCELLED;
    }
  }

  /** 质检结论。 */
  public enum IncomingInspectionResult implements Labelled {
    PASSED("合格"),
    FAILED("整批不合格"),
    PARTIAL("部分合格"),
    CONCESSION_ACCEPTED("让步接收");

    private final String label;

    IncomingInspectionResult(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 库存质量状态分流目标。 */
  public enum IncomingDisposition implements Labelled {
    SELLABLE("可售"),
    PENDING_INSPECTION("待检"),
    DEFECTIVE("残次");

    private final String label;

    IncomingDisposition(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /**
   * 分流状态 —— 「这批货到底有没有真的被分流到待检 / 残次 bucket」。
   *
   * <p>{@code UNAVAILABLE} 是**如实值**：warehouse 当前没有可用的授权写端点，
   * 所以本模块只能把分流意图记下来，**绝不出现 {@code DISPATCHED} 却其实没分流**。
   */
  public enum IncomingDispositionStatus implements Labelled {
    NOT_DISPATCHED("未提交分流"),
    UNAVAILABLE("上游无写端点"),
    DISPATCHED("已登记分流"),
    FAILED("分流失败");

    private final String label;

    IncomingDispositionStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 3.7.2.1 物理安全属性的读取状态。 */
  public enum IncomingSafetyState implements Labelled {
    UNKNOWN("未知"),
    COLLECTED("已采集"),
    NOT_COLLECTED("未采集"),
    UNAVAILABLE("上游不可达");

    private final String label;

    IncomingSafetyState(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /** 模板状态。 */
  public enum IncomingTemplateStatus implements Labelled {
    ACTIVE("启用"),
    INACTIVE("停用");

    private final String label;

    IncomingTemplateStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /**
   * 一条上下游依赖的可用性。
   *
   * <p>{@code available=false} 时 {@code ownerModule} 与 {@code impact} **必须**有值 ——
   * 「没接通」要连同「归谁」和「因此少做了什么」一起报出来。
   */
  public record IncomingCapabilityItem(
      String code,
      String label,
      boolean available,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String impact,
      @Schema(nullable = true) String reason) {}

  /** 本模块的能力与上下游可用性。 */
  public record IncomingQualityInspectionCapabilities(
      List<IncomingCapabilityItem> upstream,
      List<IncomingCapabilityItem> downstream,
      List<IncomingCapabilityItem> actions,
      List<String> itemCodes,
      List<String> roundingModes,
      String samplingRuleNote,
      String quantityBalanceNote,
      String concessionRuleNote,
      String dispositionNote,
      String note) {}

  // ================================================================ 下拉

  /** 模板下拉项。 */
  public record IncomingTemplateOption(
      UUID id, String code, String name, String inspectionType, String inspectionTypeLabel, long itemCount) {}

  /** 下拉与词表。 */
  public record IncomingQualityInspectionOptions(
      List<IncomingTemplateOption> templates,
      List<String> itemCodes,
      List<String> itemCodeLabels,
      List<String> statuses,
      List<String> statusLabels,
      List<String> results,
      List<String> resultLabels,
      List<String> dispositions,
      List<String> dispositionLabels,
      List<String> inspectionTypes,
      List<String> inspectionTypeLabels,
      List<String> roundingModes,
      List<String> roundingModeLabels,
      String note) {}

  // ================================================================ 质检模板

  /** 模板里的一个检验项（录入用）。 */
  public record IncomingTemplateItemInput(
      @NotNull IncomingItemCode itemCode,
      @NotBlank @Size(max = 200) String name,
      @Schema(nullable = true) Boolean critical,
      @Schema(nullable = true) @Size(max = 2000) String acceptanceCriteria,
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") BigDecimal sampleCoverage,
      @Schema(nullable = true) Integer sortOrder) {}

  /** 建 / 改模板（录入用）。 */
  public record IncomingTemplateInput(
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 200) String name,
      @Schema(nullable = true) UUID categoryId,
      @Schema(nullable = true) @Size(max = 200) String categoryName,
      @NotNull IncomingInspectionType inspectionType,
      @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") BigDecimal samplingRatio,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal minSampleQuantity,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal maxSampleQuantity,
      @Schema(nullable = true) IncomingRoundingMode roundingMode,
      @Schema(nullable = true) IncomingCriticalFailureAction criticalFailureAction,
      @Schema(nullable = true) IncomingDefectiveTarget defectiveTarget,
      @Schema(nullable = true) IncomingTemplateStatus status,
      @Schema(nullable = true) @Size(max = 2000) String note,
      @NotNull @Valid List<IncomingTemplateItemInput> items) {}

  /** 模板行（列表）。 */
  public record IncomingTemplateRow(
      UUID id,
      String code,
      String name,
      @Schema(nullable = true) UUID categoryId,
      @Schema(nullable = true) String categoryName,
      String inspectionType,
      String inspectionTypeLabel,
      BigDecimal samplingRatio,
      BigDecimal minSampleQuantity,
      BigDecimal maxSampleQuantity,
      String roundingMode,
      String roundingModeLabel,
      String criticalFailureAction,
      String criticalFailureActionLabel,
      String defectiveTarget,
      String defectiveTargetLabel,
      long itemCount,
      long criticalItemCount,
      String status,
      String statusLabel,
      @Schema(nullable = true) String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 模板里的一个检验项（读出）。 */
  public record IncomingTemplateItemRow(
      UUID id,
      String itemCode,
      String itemCodeLabel,
      String name,
      boolean critical,
      String acceptanceCriteria,
      BigDecimal sampleCoverage,
      int sortOrder,
      boolean active) {}

  /** 模板详情：头 + 检验项。 */
  public record IncomingTemplateDetail(IncomingTemplateRow template, List<IncomingTemplateItemRow> items) {}

  /** 模板列表页。 */
  public record IncomingTemplatePage(
      List<IncomingTemplateRow> rows, int page, int size, long total, int totalPages, String note) {}

  // ================================================================ 质检单

  /** 建质检单（录入用）。 */
  public record IncomingOrderInput(
      @NotNull UUID templateId,
      @NotBlank @Size(max = 128) String skuCode,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) @Size(max = 200) String skuName,
      @Schema(nullable = true) UUID supplierId,
      @Schema(nullable = true) @Size(max = 200) String supplierName,
      /** 来源收货单号（8.2.1.1）。到货量由服务端读上游后写入，**不信客户端**。 */
      @Schema(nullable = true) @Size(max = 64) String receiptCode,
      @Schema(nullable = true) UUID receiptLineId,
      @Schema(nullable = true) @Size(max = 64) String sourceRefCode,
      @Schema(nullable = true) @Size(max = 64) String batchNo,
      /** 本次质检覆盖的批量（≤ 到货量）。留空 = 整批到货都检。 */
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal lotSize,
      @Schema(nullable = true) @Size(max = 8) String currencyCode,
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal exchangeRate,
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal unitConversionFactor,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal unitPrice,
      @Schema(nullable = true) @Size(max = 2000) String note) {}

  /** 质检单行（列表）。 */
  public record IncomingOrderRow(
      UUID id,
      String inspectionNo,
      String inspectionType,
      String inspectionTypeLabel,
      String status,
      String statusLabel,
      @Schema(nullable = true) String result,
      @Schema(nullable = true) String resultLabel,
      @Schema(nullable = true) UUID templateId,
      String templateCode,
      String templateName,
      @Schema(nullable = true) UUID categoryId,
      @Schema(nullable = true) String categoryName,
      @Schema(nullable = true) UUID skuId,
      String skuCode,
      @Schema(nullable = true) String skuName,
      @Schema(nullable = true) UUID supplierId,
      @Schema(nullable = true) String supplierName,
      @Schema(nullable = true) String receiptCode,
      @Schema(nullable = true) String sourceRefCode,
      @Schema(nullable = true) String batchNo,
      BigDecimal arrivedQuantity,
      BigDecimal lotSize,
      BigDecimal sampleQuantity,
      BigDecimal sampledQuantity,
      BigDecimal qualifiedQuantity,
      BigDecimal defectiveQuantity,
      BigDecimal concessionQuantity,
      BigDecimal grossDeductedQuantity,
      BigDecimal sellableQuantity,
      BigDecimal nonSellableQuantity,
      String disposition,
      String dispositionLabel,
      String dispositionStatus,
      String dispositionStatusLabel,
      @Schema(nullable = true) String currencyCode,
      @Schema(nullable = true) BigDecimal unitPrice,
      @Schema(nullable = true) BigDecimal deductedAmount,
      long itemCount,
      long concludedItemCount,
      long concessionCount,
      boolean criticalFailure,
      OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime completedAt) {}

  /** 逐项检验结论（录入用）。 */
  public record IncomingOrderItemInput(
      @NotNull UUID templateItemId,
      @NotNull IncomingItemResult result,
      /** 该项在抽样中判不合格的件数。 */
      @Schema(nullable = true) @DecimalMin("0") BigDecimal failedSampleQuantity,
      /** 该项造成的批次扣量。 */
      @Schema(nullable = true) @DecimalMin("0") BigDecimal deductedQuantity,
      @Schema(nullable = true) @Size(max = 64) String defectType,
      @Schema(nullable = true) @Size(max = 2000) String defectDescription,
      /** 实抽数量（只能比应抽数量少：到货不足时抽不满）。 */
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal sampledQuantity,
      @Schema(nullable = true) OffsetDateTime inspectedAt) {}

  /** 逐项检验结论（读出）。 */
  public record IncomingOrderItemRow(
      UUID id,
      UUID templateItemId,
      String itemCode,
      String itemCodeLabel,
      String itemName,
      boolean critical,
      String result,
      String resultLabel,
      BigDecimal failedSampleQuantity,
      BigDecimal deductedQuantity,
      @Schema(nullable = true) String defectType,
      String defectDescription,
      @Schema(nullable = true) OffsetDateTime inspectedAt,
      OffsetDateTime createdAt) {}

  /** 让步接收（录入用）。原因与权限码**缺一拒绝**。 */
  public record IncomingConcessionInput(
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
      @NotBlank @Size(max = 2000) String reason,
      @NotBlank @Size(max = 64) String permissionCode,
      @Schema(nullable = true) IncomingItemCode itemCode) {}

  /** 让步接收（读出）。 */
  public record IncomingConcessionRow(
      UUID id,
      @Schema(nullable = true) String itemCode,
      @Schema(nullable = true) String itemCodeLabel,
      BigDecimal quantity,
      String reason,
      String permissionCode,
      UUID approvedBy,
      OffsetDateTime createdAt) {}

  /** 完结质检单（录入用）。 */
  public record IncomingCompleteInput(
      @Schema(nullable = true) @Size(max = 2000) String conclusionNote,
      @Schema(nullable = true) OffsetDateTime inspectedAt) {}

  /** 作废质检单（录入用）。理由必填 —— 作废掉的是一份凭据。 */
  public record IncomingCancelInput(@NotBlank @Size(max = 2000) String cancelReason) {}

  /** 质检单详情：头 + 逐项结论 + 让步记录 + 缺项 + 分流。 */
  public record IncomingOrderDetail(
      IncomingOrderRow order,
      List<IncomingOrderItemRow> items,
      List<IncomingConcessionRow> concessions,
      List<String> missingItemCodes,
      List<String> missingItemLabels,
      List<IncomingTemplateItemRow> pendingItems,
      String samplingBasis,
      String balanceNote,
      String dispositionNote,
      String safetyNote,
      @Schema(nullable = true) String conclusionNote,
      List<String> actions,
      String note) {}

  /** 质检单列表页。 */
  public record IncomingOrderPage(
      List<IncomingOrderRow> rows, int page, int size, long total, int totalPages, String note) {}

  /**
   * 汇总视图。
   *
   * <p>刻意把<b>可售量</b>与<b>不可售量</b>分列：蓝图加粗句「质检不合格的货不能直接进可售库存」
   * 的日常读法就是「今天有多少货不能卖」。
   */
  public record IncomingInspectionSummary(
      long orderCount,
      long draftCount,
      long inspectingCount,
      long completedCount,
      long cancelledCount,
      long passedCount,
      long failedCount,
      long partialCount,
      long concessionCount,
      BigDecimal arrivedQuantity,
      BigDecimal inspectedQuantity,
      BigDecimal qualifiedQuantity,
      BigDecimal defectiveQuantity,
      BigDecimal concessionQuantity,
      BigDecimal sellableQuantity,
      BigDecimal nonSellableQuantity,
      BigDecimal deductedAmount,
      @Schema(nullable = true) String currencyCode,
      String note) {}

  /** 一个动作的执行结果（分流、完结等）。 */
  public record IncomingActionResult(
      boolean succeeded,
      String action,
      String status,
      String message,
      @Schema(nullable = true) UUID orderId,
      @Schema(nullable = true) IncomingOrderRow order) {}
}
