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
 * 6.2.2.2 需求合并与批量的传输模型。
 *
 * <p>蓝图原话：「把多个 SKU 的需求合并成一张采购单（同一供应商），支持批量生成、批量修改数量与供应商、批量打印。」
 * 输入「多行需求；供应商选择（相同供应商才能合并）」。
 * 输出「合并后的采购单草稿；未合并行说明原因」。
 *
 * <p><b>命名纪律（硬要求）</b>：springdoc 用 record 的<b>简单类名</b>做 {@code components.schemas} 的键，
 * 重名会被静默覆盖 —— 别人的字段形状被改掉、单测全绿、只有跨服务契约比对才红。本文件所有 record /
 * enum 一律以 {@code PurchaseDemandMerge} 开头。
 *
 * <p><b>六条建模纪律</b>：
 *
 * <ul>
 *   <li><b>合并只在同一供应商内进行</b>（蓝图加粗句）：一张草稿只属于一个供应商。
 *       跨供应商的需求走 {@link PurchaseDemandMergeUnmergedRow} 并带**明确原因**，
 *       既不静默丢弃，也不偷偷归到第一个供应商。</li>
 *   <li><b>草稿 ≠ 采购单</b>：6.3.1.2 采购单<b>未交付</b>，本模块交付的是
 *       <b>采购单草稿</b>（{@code purchase_demand_merge_draft}），
 *       6.3.1.2 交付后由它读这份草稿去建真正的采购单。</li>
 *   <li><b>「答不出来」与「不需要」不折叠</b>：{@code estimatedAmount} 为 null = 金额算不出来
 *       （有行没有预估单价），<b>不是</b> 0 —— 0 会被读成「这笔采购不要钱」。</li>
 *   <li><b>批量动作整体生效或整体拒绝</b>：{@code affectedLineCount} 与逐行留痕
 *       （{@link PurchaseDemandBatchAdjustmentRow}）是一份「改了多少」的凭据，
 *       改了一半的中间态不许留在库里。</li>
 *   <li><b>数量全程 BigDecimal</b>，DB 侧 numeric(19,6)；金额 numeric(19,4)。</li>
 *   <li><b>未合并原因必填</b>：{@code reason} 说清「为什么没合上」，光有原因码用户看不懂。</li>
 * </ul>
 */
public final class PurchaseDemandMergeModels {

  private PurchaseDemandMergeModels() {}

  /** 中文名。服务层与前端都拿得到 label，不必各写一遍 switch —— 各写一遍迟早漂移。 */
  public interface Labelled {
    String label();
  }

  // ================================================================ 枚举

  /**
   * 合并批次状态。
   *
   * <p>🔴 合法流转由 <b>DB 层触发器</b>钉死（{@code guard_demand_merge_batch_status}）：
   * {@code OPEN → FINALIZED}，其余一律拒。定稿后内容冻结 —— 6.3.1.2 要读一份不再变动的草稿。
   */
  public enum PurchaseDemandMergeBatchStatus implements Labelled {
    OPEN("可批量修改"),
    FINALIZED("已定稿");

    private final String label;

    PurchaseDemandMergeBatchStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /**
   * 采购单草稿状态。
   *
   * <p>本模块<b>只</b>产出 {@code DRAFT}；{@code CONVERTED} 由 6.3.1.2 置位
   * —— 本模块绝不自己造一张采购单出来。
   */
  public enum PurchaseDemandMergeDraftStatus implements Labelled {
    DRAFT("草稿"),
    CONVERTED("已转采购单");

    private final String label;

    PurchaseDemandMergeDraftStatus(String label) {
      this.label = label;
    }

    @Override
    public String label() {
      return label;
    }
  }

  /**
   * 未合并行的原因码。
   *
   * <p>🔴 每一行都必须能被解释：「为什么这行没进采购单草稿」。
   * 静默丢弃（用户以为合掉了）与偷偷归到第一个供应商（会付错钱）都是错的。
   */
  public enum PurchaseDemandMergeReasonCode implements Labelled {
    REQUEST_NOT_APPROVED("源申请单还没审"),
    REQUEST_REJECTED("源申请单已被驳回"),
    REQUEST_ALREADY_CONVERTED("源申请单已转单"),
    SUPPLIER_MISSING("还没选供应商"),
    SUPPLIER_MISMATCH("跨供应商，不能合并"),
    TARGET_MISMATCH("目标仓 / 法人不一致"),
    CURRENCY_MISMATCH("币种不一致"),
    ALREADY_MERGED("已被合并过"),
    PRICE_CONFLICT("同一 SKU 单价冲突");

    private final String label;

    PurchaseDemandMergeReasonCode(String label) {
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
   * <p>存在的理由：需求行来自 6.2.2.1（trade 服务，已交付）、供应商主数据在 catalog（party）、
   * 目标仓与法人在 warehouse、**采购单在 6.3.1.2（未交付）**。
   * 哪一个接通了必须由接口自己报出 —— 特别是采购单：它没交付，所以本模块产出的是
   * <b>采购单草稿</b>，不是采购单。
   */
  public record PurchaseDemandMergeCapabilities(
      boolean requestAvailable,
      String requestOwnerModule,
      String requestNote,
      boolean supplierReferenceAvailable,
      String supplierReferenceOwnerModule,
      String supplierReferenceNote,
      boolean warehouseReferenceAvailable,
      String warehouseReferenceOwnerModule,
      String warehouseReferenceNote,
      boolean purchaseOrderDelivered,
      String purchaseOrderOwnerModule,
      String purchaseOrderNote,
      String mergeRule,
      String stateMachine,
      String note) {}

  // ================================================================ 参照下拉

  /** 供应商候选（catalog 的 party，跨服务只读；上游不可达时整份 options 报 503，不返回空清单）。 */
  public record PurchaseDemandMergeSupplierOption(
      UUID id, String code, String name, String status, boolean selected) {}

  /** 目标仓候选（warehouse 授权只读）。 */
  public record PurchaseDemandMergeWarehouseOption(
      UUID id, String code, String name, String countryCode, String status) {}

  /** 法人候选（warehouse 授权只读）。 */
  public record PurchaseDemandMergeLegalEntityOption(UUID id, String code, String name, String status) {}

  /** 合并要用的下拉项。 */
  public record PurchaseDemandMergeOptions(
      List<PurchaseDemandMergeSupplierOption> suppliers,
      List<PurchaseDemandMergeWarehouseOption> warehouses,
      List<PurchaseDemandMergeLegalEntityOption> legalEntities,
      String supplierNote,
      String warehouseNote,
      String note) {}

  // ================================================================ 合并批次

  /**
   * 合并批次（头）的一行。
   *
   * @param estimatedAmount 合并后的预估金额。null = 金额算不出来（有行没有预估单价），**不是 0**
   * @param draftCount 本批次产出的采购单草稿张数（= 供应商分组数）
   * @param unmergedCount 没合上的行数
   * @param sourceRequestIds 本批次由哪几张 6.2.2.1 申请单的需求合并而来
   */
  public record PurchaseDemandMergeBatchRow(
      UUID id,
      String batchNo,
      String status,
      String statusLabel,
      UUID targetWarehouseId,
      String targetWarehouseCode,
      String targetWarehouseName,
      UUID legalEntityId,
      String legalEntityCode,
      String legalEntityName,
      String currencyCode,
      int draftCount,
      int lineCount,
      int unmergedCount,
      BigDecimal totalQuantity,
      @Schema(nullable = true) BigDecimal estimatedAmount,
      List<UUID> sourceRequestIds,
      String note,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      @Schema(nullable = true) OffsetDateTime finalizedAt,
      @Schema(nullable = true) UUID finalizedBy) {}

  /**
   * 采购单草稿（头）的一行。
   *
   * @param supplierId 供应商。**一张草稿只属于一个供应商**（蓝图硬规则）
   * @param estimatedAmount 草稿金额。null = 算不出来，**不是 0**
   */
  public record PurchaseDemandMergeDraftRow(
      UUID id,
      UUID batchId,
      String draftNo,
      UUID supplierId,
      String supplierCode,
      String supplierName,
      UUID targetWarehouseId,
      String targetWarehouseCode,
      String targetWarehouseName,
      UUID legalEntityId,
      String legalEntityCode,
      String legalEntityName,
      String currencyCode,
      int lineCount,
      BigDecimal totalQuantity,
      @Schema(nullable = true) BigDecimal estimatedAmount,
      String status,
      String statusLabel,
      String note,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 草稿行的一行。
   *
   * @param mergedQuantity 合并后的下单量（批量改数量改的就是它）
   * @param sourceQuantity 源需求量的合计。批量改数量后它与 mergedQuantity 会分叉 ——
   *     分叉本身就是「改过」的证据
   * @param unitPrice 预估单价。null = 没填（采购单价是 6.3.1.2 采购单环节的事）
   * @param lineAmount 行金额 = 单价 × 合并后的数量。null = 单价没填
   */
  public record PurchaseDemandMergeLineRow(
      UUID id,
      UUID draftId,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID supplierId,
      String supplierCode,
      String supplierName,
      BigDecimal mergedQuantity,
      BigDecimal sourceQuantity,
      @Schema(nullable = true) BigDecimal unitPrice,
      @Schema(nullable = true) BigDecimal lineAmount,
      @Schema(nullable = true) LocalDate arrivalDate,
      int sourceRequestCount,
      int sortRank,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 行来源（append-only）的一行：这一行是由哪几条需求合成的。 */
  public record PurchaseDemandMergeLineSourceRow(
      UUID id,
      UUID draftLineId,
      UUID sourceRequestId,
      UUID sourceRequestLineId,
      UUID sourceSkuId,
      String sourceSkuCode,
      BigDecimal sourceQuantity,
      OffsetDateTime mergedAt) {}

  /**
   * 未合并行（append-only）的一行。
   *
   * @param reasonCode 原因码（见 {@link PurchaseDemandMergeReasonCode}）
   * @param reason 原因原文（中文，说清为什么没合上）
   */
  public record PurchaseDemandMergeUnmergedRow(
      UUID id,
      UUID batchId,
      UUID sourceRequestId,
      UUID sourceRequestLineId,
      UUID sourceSkuId,
      String sourceSkuCode,
      String sourceSkuName,
      @Schema(nullable = true) BigDecimal sourceQuantity,
      @Schema(nullable = true) UUID sourceSupplierId,
      String sourceSupplierName,
      String reasonCode,
      String reasonCodeLabel,
      String reason,
      OffsetDateTime createdAt) {}

  /** 批量修改留痕（append-only）的一行。 */
  public record PurchaseDemandBatchAdjustmentRow(
      UUID id,
      UUID batchId,
      String scope,
      UUID lineId,
      UUID draftId,
      String skuCode,
      String field,
      @Schema(nullable = true) String beforeValue,
      @Schema(nullable = true) String afterValue,
      String reason,
      int affectedLineCount,
      UUID adjustedBy,
      OffsetDateTime adjustedAt) {}

  /** 合并批次详情：批次 + 草稿 + 行 + 来源 + 未合并行 + 批量修改留痕。 */
  public record PurchaseDemandMergeDetail(
      PurchaseDemandMergeBatchRow batch,
      List<PurchaseDemandMergeDraftRow> drafts,
      List<PurchaseDemandMergeLineRow> lines,
      List<PurchaseDemandMergeLineSourceRow> sources,
      List<PurchaseDemandMergeUnmergedRow> unmerged,
      List<PurchaseDemandBatchAdjustmentRow> adjustments) {}

  /** 采购单草稿详情：草稿 + 行 + 行来源。 */
  public record PurchaseDemandMergeDraftDetail(
      PurchaseDemandMergeDraftRow draft,
      List<PurchaseDemandMergeLineRow> lines,
      List<PurchaseDemandMergeLineSourceRow> sources) {}

  /** 真分页结果（合并批次）。 */
  public record PurchaseDemandMergeBatchPage(
      List<PurchaseDemandMergeBatchRow> items, long total, int page, int size) {}

  /** 真分页结果（草稿）。 */
  public record PurchaseDemandMergeDraftPage(
      List<PurchaseDemandMergeDraftRow> items, long total, int page, int size) {}

  /** 真分页结果（草稿行）。 */
  public record PurchaseDemandMergeLinePage(
      List<PurchaseDemandMergeLineRow> items, long total, int page, int size) {}

  /** 真分页结果（未合并行）。 */
  public record PurchaseDemandMergeUnmergedPage(
      List<PurchaseDemandMergeUnmergedRow> items, long total, int page, int size) {}

  // ================================================================ 打印

  /** 打印页的一行（行级）。 */
  public record PurchaseDemandPrintLine(
      String skuCode,
      String skuName,
      String supplierName,
      BigDecimal mergedQuantity,
      @Schema(nullable = true) BigDecimal unitPrice,
      @Schema(nullable = true) BigDecimal lineAmount,
      @Schema(nullable = true) LocalDate arrivalDate,
      String note) {}

  /** 打印页（草稿级）：一张草稿一张纸。 */
  public record PurchaseDemandPrintSheet(
      UUID draftId,
      String draftNo,
      String supplierCode,
      String supplierName,
      String targetWarehouseCode,
      String targetWarehouseName,
      String legalEntityCode,
      String legalEntityName,
      String currencyCode,
      List<PurchaseDemandPrintLine> lines,
      int lineCount,
      BigDecimal totalQuantity,
      @Schema(nullable = true) BigDecimal totalAmount,
      String note,
      String textContent) {}

  /**
   * 批量打印的结果。
   *
   * @param printedAt 打印时刻（数据库时钟口径，由服务层取）
   * @param sheets 每张草稿一张纸
   */
  public record PurchaseDemandPrintResult(
      OffsetDateTime printedAt,
      int draftCount,
      int lineCount,
      BigDecimal totalQuantity,
      @Schema(nullable = true) BigDecimal totalAmount,
      List<PurchaseDemandPrintSheet> sheets,
      String note) {}

  // ================================================================ 输入

  /**
   * 批量生成（合并）。
   *
   * @param sourceRequestIds 要合并的需求来自哪几张 6.2.2.1 申请单
   * @param sourceLineIds 只合并这些需求行（为空 = 整单全部行）。
   *     为空时用请求单的全部行。
   * @param targetSupplierId 只合并到某个供应商（为空 = 按各自供应商分组，
   *     每个供应商一张草稿）。给了它之后，其它供应商的行进「未合并行」。
   * @param targetWarehouseId 采购单草稿的目标仓
   * @param legalEntityId 采购单草稿的法人
   * @param currencyCode 采购单草稿的币种
   */
  public record PurchaseDemandMergeCreateInput(
      @NotEmpty List<UUID> sourceRequestIds,
      @Schema(nullable = true) List<UUID> sourceLineIds,
      @Schema(nullable = true) UUID targetSupplierId,
      @NotNull UUID targetWarehouseId,
      @NotNull UUID legalEntityId,
      @NotBlank @Size(min = 3, max = 3) String currencyCode,
      @Schema(nullable = true) @Size(max = 1000) String note) {}

  /**
   * 批量改数量。
   *
   * @param lineIds 要改的行（必须都属于本批次）
   * @param mode {@code SET} 设为 {@code value}；{@code MULTIPLY} 乘 {@code value}
   * @param value 目标数量 / 乘数。乘数必须 > 0
   * @param reason 理由。必填：一句「调整一下」不是留痕
   */
  public record PurchaseDemandBatchQuantityInput(
      @NotEmpty List<UUID> lineIds,
      @NotBlank @Pattern(regexp = "SET|MULTIPLY") String mode,
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal value,
      @NotBlank @Size(max = 500) String reason) {}

  /**
   * 批量改供应商。
   *
   * <p>🔴 改完之后服务层会**重新校验分组**（每张草稿的供应商必须唯一），
   * 不满足就**整批拒绝** —— 不许出现「改了一半」的中间态留在库里。
   *
   * @param lineIds 要改的行（必须都属于本批次）
   * @param supplierId 新的供应商（必须真的存在于 catalog）
   * @param reason 理由。必填
   */
  public record PurchaseDemandBatchSupplierInput(
      @NotEmpty List<UUID> lineIds,
      @NotNull UUID supplierId,
      @NotBlank @Size(max = 500) String reason) {}

  /** 批量打印的入参。 */
  public record PurchaseDemandPrintInput(@NotEmpty List<UUID> draftIds) {}
}
