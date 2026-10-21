package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 6.2.2.1 采购申请的传输模型。
 *
 * <p>蓝图原话：「把计划行/补货建议转成采购申请，走审批，确认供应商与数量后转采购单。」
 * 输入「计划行或建议；供应商选择；数量与交期调整；审批提交」。
 * 输出「采购申请单（草稿/待审/已批/已转单/已驳回）；审批记录」。
 *
 * <p><b>命名纪律（硬要求）</b>：springdoc 用 record 的<b>简单类名</b>做 {@code components.schemas} 的键，
 * 重名会被静默覆盖 —— 别人的字段形状被改掉、单测全绿、只有跨服务契约比对才红。本文件所有 record /
 * enum 一律以 {@code PurchaseRequest} 开头。
 *
 * <p><b>五条建模纪律</b>：
 *
 * <ul>
 *   <li><b>申请单与采购单分离</b>：蓝图加粗句「申请是内部决策单据，采购单是对供应商的正式承诺」。
 *       本文件里的 {@link PurchaseRequestConversionRow} 记的是**转单事实**
 *       （供应商 + 数量 + 交期 + 目标仓 + 源计划行），<b>不是</b>一张采购单 ——
 *       6.3.1.2 采购单未交付，本模块绝不造一张假采购单表占位。</li>
 *   <li><b>审批结论由 identity 裁定</b>：{@code approvalDecision} / {@code approvalSource} /
 *       {@code approvalInstanceNo} 是 17 审批的结论，本服务只如实记录，
 *       <b>没有</b>「本地批准」这个形状。</li>
 *   <li><b>「答不出来」与「不需要」不折叠</b>：{@code estimatedAmount} 为 null = 金额算不出来
 *       （有行没有预估单价），<b>不是</b> 0 —— 0 会被读成「这笔采购不要钱」。</li>
 *   <li><b>数量全程 BigDecimal</b>，DB 侧 numeric(19,6)；金额 numeric(19,4)。</li>
 *   <li><b>同步失败如实报</b>：{@code approvalSyncError} 非空 = 审批服务没给出结论，
 *       这张单<b>仍停在待审</b>，页面必须把这句话显示出来。</li>
 * </ul>
 */
public final class PurchaseRequestModels {

  private PurchaseRequestModels() {}

  /** 中文名。服务层与前端都拿得到 label，不必各写一遍 switch —— 各写一遍迟早漂移。 */
  public interface Labelled {
    String label();
  }

  // ================================================================ 枚举

  /**
   * 申请单状态（蓝图输出句逐字对应：草稿 / 待审 / 已批 / 已转单 / 已驳回）。
   *
   * <p>🔴 合法流转由 <b>DB 层触发器</b>钉死（{@code guard_purchase_request_status}）：
   * 草稿 → 待审 / 已批，待审 → 已批 / 已驳回，已批 → 已转单。
   * 别的组合一律被拒 —— 已转单退回草稿、或草稿直接跳已驳回，都是「审批留痕被抹掉」的形状。
   */
  public enum PurchaseRequestStatus implements Labelled {
    DRAFT("草稿"),
    PENDING_APPROVAL("待审"),
    APPROVED("已批"),
    CONVERTED("已转单"),
    REJECTED("已驳回");

    private final String label;

    PurchaseRequestStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }

    /** 离开草稿之后内容就冻结了（数量 / 交期 / 供应商都不许再改）。 */
    public boolean frozen() {
      return this != DRAFT;
    }
  }

  /** 优先级。 */
  public enum PurchaseRequestPriority implements Labelled {
    CRITICAL("紧急"),
    HIGH("高"),
    NORMAL("普通"),
    LOW("低");

    private final String label;

    PurchaseRequestPriority(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /**
   * 来源类型：这批申请行是从哪来的。
   *
   * <p>{@code PLAN} = 6.2.1.3 采购计划行；{@code SUGGESTION} = 6.2.1.2 补货建议；
   * {@code MANUAL} = 手工建单（没有上游行）。
   */
  public enum PurchaseRequestSourceType implements Labelled {
    PLAN("采购计划行"),
    SUGGESTION("补货建议"),
    MANUAL("手工建单");

    private final String label;

    PurchaseRequestSourceType(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：计划行在 6.2.1.3、建议在 6.2.1.2、供应商主数据在 catalog（party）、
   * 目标仓与法人在 warehouse、审批结论在 17（identity）、**采购单在 6.3.1.2（未交付）**。
   * 哪一个接通了必须由接口自己报出 —— 特别是采购单：它没交付，所以本模块的「已转单」
   * 只是一个**事实**，不是一张采购单。
   */
  public record PurchaseRequestCapabilities(
      boolean planLineAvailable,
      String planLineOwnerModule,
      String planLineNote,
      boolean suggestionAvailable,
      String suggestionOwnerModule,
      String suggestionNote,
      boolean supplierReferenceAvailable,
      String supplierReferenceOwnerModule,
      String supplierReferenceNote,
      boolean warehouseReferenceAvailable,
      String warehouseReferenceOwnerModule,
      String warehouseReferenceNote,
      boolean approvalServiceAvailable,
      String approvalContract,
      String approvalOwnerModule,
      String approvalNote,
      boolean purchaseOrderDelivered,
      String purchaseOrderOwnerModule,
      String purchaseOrderNote,
      String documentTypeCode,
      String sourceSystem,
      String stateMachine,
      String note) {}

  // ================================================================ 参照下拉

  /** 供应商候选（catalog 的 party，跨服务只读；上游不可达时整份 options 报 503，不返回空清单）。 */
  public record PurchaseRequestSupplierOption(
      UUID id, String code, String name, String status, boolean selected) {}

  /** 目标仓候选（warehouse 授权只读）。 */
  public record PurchaseRequestWarehouseOption(
      UUID id, String code, String name, String countryCode, String status) {}

  /** 法人候选（warehouse 授权只读）。 */
  public record PurchaseRequestLegalEntityOption(UUID id, String code, String name, String status) {}

  /** 建申请单要用的下拉项。 */
  public record PurchaseRequestOptions(
      List<PurchaseRequestSupplierOption> suppliers,
      List<PurchaseRequestWarehouseOption> warehouses,
      List<PurchaseRequestLegalEntityOption> legalEntities,
      String supplierNote,
      String warehouseNote,
      String note) {}

  // ================================================================ 申请单

  /**
   * 采购申请单（头）的一行。
   *
   * @param status 状态（草稿 / 待审 / 已批 / 已转单 / 已驳回）
   * @param statusLabel 状态中文名
   * @param sourceTypeLabel 来源类型中文名
   * @param estimatedAmount 预估采购金额。null = 金额算不出来（有行没有预估单价），**不是 0**
   * @param currencyCode 币种。与 estimatedAmount 同时为空 / 同时非空
   * @param approvalDecision identity 的判定结论（SUBMIT_FOR_APPROVAL / EXEMPT）
   * @param approvalSource identity 的判定来源（RULE / DEFAULT / UNCONTROLLED）
   * @param approvalSyncError 审批同步失败原因。非空 = 这张单<b>仍停在待审</b>
   */
  public record PurchaseRequestRow(
      UUID id,
      String requestNo,
      String sourceType,
      String sourceTypeLabel,
      @Schema(nullable = true) UUID sourcePlanId,
      @Schema(nullable = true) UUID sourceBatchId,
      UUID targetWarehouseId,
      String targetWarehouseCode,
      String targetWarehouseName,
      UUID legalEntityId,
      String legalEntityCode,
      String legalEntityName,
      @Schema(nullable = true) String currencyCode,
      @Schema(nullable = true) BigDecimal estimatedAmount,
      String status,
      String statusLabel,
      boolean approvalRequired,
      String approvalDecision,
      String approvalSource,
      @Schema(nullable = true) UUID approvalRuleId,
      String approvalRuleName,
      @Schema(nullable = true) UUID approvalFlowId,
      String approvalFlowName,
      String approvalReason,
      @Schema(nullable = true) UUID approvalInstanceId,
      String approvalInstanceNo,
      @Schema(nullable = true) OffsetDateTime approvalSubmittedAt,
      @Schema(nullable = true) OffsetDateTime approvalDecidedAt,
      @Schema(nullable = true) UUID approvalDecidedBy,
      String approvalSyncError,
      @Schema(nullable = true) OffsetDateTime convertedAt,
      @Schema(nullable = true) UUID convertedBy,
      int lineCount,
      BigDecimal totalQuantity,
      long supplierCount,
      String note,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 申请行的一行。
   *
   * @param supplierId 供应商（catalog 的 SUPPLIER 角色 id）。null = 还没选供应商
   * @param unitPrice 预估单价。null = 没填（采购单价是采购单环节的事）
   * @param lineAmount 行金额 = 单价 × 数量。null = 单价没填
   * @param sourcePlanLineId 源计划行（6.2.1.3）。可追溯到「这行是从哪来的」
   * @param sourceSuggestionId 源补货建议（6.2.1.2）
   */
  public record PurchaseRequestLineRow(
      UUID id,
      UUID requestId,
      UUID skuId,
      String skuCode,
      String skuName,
      @Schema(nullable = true) UUID sourcePlanLineId,
      @Schema(nullable = true) UUID sourcePlanId,
      @Schema(nullable = true) UUID sourceSuggestionId,
      BigDecimal requestedQuantity,
      @Schema(nullable = true) BigDecimal unitPrice,
      @Schema(nullable = true) BigDecimal lineAmount,
      @Schema(nullable = true) UUID supplierId,
      String supplierCode,
      String supplierName,
      // 交期可以为空 = **还没定**（补货建议的预计到货日本来就可能取不到）。
      @Schema(nullable = true) LocalDate requestedArrivalDate,
      String priority,
      String priorityLabel,
      String note,
      int sortRank,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 调整留痕（append-only）的一行。 */
  public record PurchaseRequestAdjustmentRow(
      UUID id,
      UUID requestLineId,
      String skuCode,
      String field,
      @Schema(nullable = true) String beforeValue,
      @Schema(nullable = true) String afterValue,
      String reason,
      UUID adjustedBy,
      OffsetDateTime adjustedAt) {}

  /**
   * 转单事实（append-only）的一行。
   *
   * <p>🔴 这是「转成采购单时的参数」，<b>不是</b>采购单 —— 6.3.1.2 未交付。
   */
  public record PurchaseRequestConversionRow(
      UUID id,
      UUID requestId,
      UUID requestLineId,
      String skuCode,
      UUID supplierId,
      String supplierCode,
      String supplierName,
      BigDecimal convertedQuantity,
      LocalDate convertedArrivalDate,
      UUID targetWarehouseId,
      String targetWarehouseCode,
      String targetWarehouseName,
      @Schema(nullable = true) UUID sourcePlanLineId,
      @Schema(nullable = true) UUID sourcePlanId,
      @Schema(nullable = true) UUID sourceSuggestionId,
      String note,
      UUID convertedBy,
      OffsetDateTime convertedAt) {}

  /** 申请单详情：头 + 行 + 调整留痕 + 转单事实。 */
  public record PurchaseRequestDetail(
      PurchaseRequestRow request,
      List<PurchaseRequestLineRow> lines,
      List<PurchaseRequestAdjustmentRow> adjustments,
      List<PurchaseRequestConversionRow> conversions) {}

  /** 真分页结果。 */
  public record PurchaseRequestPage(List<PurchaseRequestRow> items, long total, int page, int size) {}

  /** 申请行真分页结果。 */
  public record PurchaseRequestLinePage(
      List<PurchaseRequestLineRow> items, long total, int page, int size) {}

  // ================================================================ 输入

  /**
   * 建一张采购申请单。
   *
   * @param sourceType 来源类型（PLAN / SUGGESTION / MANUAL）
   * @param sourcePlanId 源采购计划（sourceType=PLAN 时必填）
   * @param sourceBatchId 源补货建议批次（sourceType=SUGGESTION 时必填）
   * @param sourceLineIds 要转成申请行的计划行 / 建议 id 列表。
   *     为空且 sourceType=MANUAL 时，改用 {@code lines} 里的手工行。
   * @param lines 手工建的申请行（sourceType=MANUAL 时用）
   */
  public record PurchaseRequestCreateInput(
      @NotBlank @Pattern(regexp = "PLAN|SUGGESTION|MANUAL") String sourceType,
      @Schema(nullable = true) UUID sourcePlanId,
      @Schema(nullable = true) UUID sourceBatchId,
      @Schema(nullable = true) List<UUID> sourceLineIds,
      @Schema(nullable = true) List<@Valid PurchaseRequestLineInput> lines,
      // 目标仓与法人：sourceType=PLAN 时**继承计划头**（可以不传），
      // 其余来源必须由调用方给（服务层校验）。
      @Schema(nullable = true) UUID targetWarehouseId,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) @Size(min = 3, max = 3) String currencyCode,
      @Schema(nullable = true) @Size(max = 1000) String note) {}

  /**
   * 手工建的一行。
   *
   * @param skuId SKU id（catalog 拥有商品档案）
   * @param skuCode SKU 编码（单据上的展示快照）
   * @param requestedQuantity 申请数量（基础单位）
   * @param unitPrice 预估单价。为空 = 不填
   */
  public record PurchaseRequestLineInput(
      @NotNull UUID skuId,
      @NotBlank @Size(max = 64) String skuCode,
      @Schema(nullable = true) @Size(max = 200) String skuName,
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal requestedQuantity,
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = true) BigDecimal unitPrice,
      // 交期可以为空 = **还没定**（补货建议的预计到货日本来就可能取不到）。
      @Schema(nullable = true) LocalDate requestedArrivalDate,
      @Schema(nullable = true) @Pattern(regexp = "CRITICAL|HIGH|NORMAL|LOW") String priority,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 更新申请单草稿（只有草稿可改）。 */
  public record PurchaseRequestUpdateInput(
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) @Size(min = 3, max = 3) String currencyCode,
      @Schema(nullable = true) @Size(max = 1000) String note) {}

  /**
   * 调整一行的数量 / 交期 / 单价 / 优先级。理由必填。
   *
   * <p>每次改动都会落一条 append-only 的调整留痕（{@code purchase_request_adjustment}）。
   */
  public record PurchaseRequestLineAdjustInput(
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal requestedQuantity,
      @Schema(nullable = true) LocalDate requestedArrivalDate,
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = true) BigDecimal unitPrice,
      @Schema(nullable = true) @Pattern(regexp = "CRITICAL|HIGH|NORMAL|LOW") String priority,
      @Schema(nullable = true) @Size(max = 500) String note,
      @NotBlank @Size(max = 500) String reason) {}

  /**
   * 给一行选供应商。
   *
   * <p>供应商主数据在 catalog（party）—— 跨服务，走授权只读接口；未接线时显式报「未接线」，
   * <b>绝不</b>降级成一份假供应商清单。
   */
  public record PurchaseRequestSupplierInput(
      @NotNull UUID supplierId, @Schema(nullable = true) @Size(max = 500) String reason) {}

  /**
   * 转单（已批 → 已转单）。
   *
   * @param lines 要转的行。为空 = 整张申请的所有行都转
   */
  public record PurchaseRequestConvertInput(
      @Schema(nullable = true) List<@Valid PurchaseRequestConvertLineInput> lines,
      @Schema(nullable = true) @Size(max = 1000) String note) {}

  /**
   * 一行的转单参数。
   *
   * @param supplierId 供应商。为空 = 用申请行上已选的供应商
   * @param quantity 数量。为空 = 用申请行上的申请数量
   * @param arrivalDate 交期。为空 = 用申请行上的期望到货日
   */
  public record PurchaseRequestConvertLineInput(
      @NotNull UUID lineId,
      @Schema(nullable = true) UUID supplierId,
      @Schema(nullable = true) @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
      @Schema(nullable = true) LocalDate arrivalDate,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 提交审批的入参（可留空体）。 */
  public record PurchaseRequestSubmitInput(@Schema(nullable = true) @Size(max = 1000) String note) {}

  /**
   * 提交 / 刷新审批的结果。
   *
   * <p>🔴 {@code failed=true} 时**不是**抛异常 —— 抛异常会把「审批同步失败」这条痕迹
   * 一起回滚掉（整条链路在一个事务里）。服务层<b>先记下痕迹再返回</b>，
   * 由控制器在<b>事务提交之后</b>翻成对应的 HTTP 状态码。
   */
  public record PurchaseRequestApprovalResult(
      PurchaseRequestRow request, boolean failed, int errorStatus, String message) {}
}
