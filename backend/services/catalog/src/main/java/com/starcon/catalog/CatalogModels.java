package com.starcon.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;

public final class CatalogModels {
  private CatalogModels() {}

  public record SpuInput(
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 200) String name,
      @NotNull @Size(max = 10000) String description,
      @NotNull @Size(max = 100) String brand,
      @NotNull @Size(max = 100) String category,
      @NotBlank String status,
      @PositiveOrZero long version) {}

  public record Product(
      UUID id,
      String code,
      String name,
      String description,
      String brand,
      String category,
      String status,
      long version,
      int skuCount,
      @Schema(nullable = true) UUID ownerId,
      @Schema(nullable = true) UUID departmentId) {}

  public record AttributeInput(
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 100) String name,
      @NotBlank String dataType,
      @NotBlank String valueMode,
      boolean multiple,
      boolean required,
      boolean variantAxis,
      @NotNull @Size(max = 32) String unit,
      @NotNull @Size(max = 100) String groupName,
      @NotNull @Size(max = 100) List<@NotBlank @Size(max = 100) String> options,
      @NotNull @Size(max = 50) List<@NotNull @Size(max = 2000) String> defaultValues,
      @PositiveOrZero long version) {}

  public record Attribute(
      UUID id,
      String code,
      String name,
      String dataType,
      String valueMode,
      boolean multiple,
      boolean required,
      boolean variantAxis,
      String unit,
      String groupName,
      List<String> options,
      List<String> defaultValues) {}

  public record ValueInput(
      @NotNull UUID attributeId,
      @NotBlank String state,
      @NotNull @Size(max = 50) List<@NotNull @Size(max = 2000) String> values) {}

  public record SkuInput(
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 200) String name,
      @NotNull @Size(max = 128) String barcode,
      @NotBlank @Size(max = 32) String baseUnit,
      @NotBlank String status,
      @NotNull @Size(max = 200) List<@jakarta.validation.Valid ValueInput> values,
      @PositiveOrZero long version) {}

  public record EffectiveValue(
      UUID attributeId,
      String state,
      List<String> values,
      List<String> effectiveValues,
      String source) {}

  public record Sku(
      UUID id,
      String code,
      String name,
      String barcode,
      String baseUnit,
      String status,
      long version,
      List<EffectiveValue> values) {}

  public record Detail(Product product, List<Attribute> attributes, List<Sku> skus) {}

  public record RecordInput(
      @Schema(nullable = true) UUID spuId,
      @NotBlank @Size(max = 200) String title,
      @NotNull @Size(max = 10000) String notes,
      @NotNull @Size(max = 2000) String reference,
      @NotBlank String status,
      @NotNull @Size(max = 35) String locale,
      @NotNull @Size(max = 2) String targetCountry,
      @Schema(nullable = true) String expiresOn,
      @PositiveOrZero long version) {}

  public record ProductRecord(
      UUID id,
      @Schema(nullable = true) UUID spuId,
      String title,
      String notes,
      String reference,
      String status,
      String locale,
      String targetCountry,
      @Schema(nullable = true) String expiresOn,
      long version,
      @Schema(nullable = true) UUID ownerId,
      @Schema(nullable = true) UUID departmentId) {}

  // ------------------------------------------------ 3.1.1.3 SPU 生命周期

  /** 一次状态流转请求。occurredAt 是业务时间，留空取当前时间；与系统时间分列存储。 */
  public record TransitionInput(
      @NotBlank String toStatus,
      @NotNull @Size(max = 500) String reason,
      @Schema(nullable = true) String occurredAt,
      @PositiveOrZero long version) {}

  /** 合法转换矩阵中当前状态的一条出边，前端据此渲染可执行操作。 */
  public record TransitionOption(
      String toStatus,
      String toLabel,
      String note,
      boolean reasonRequired,
      boolean requiresActiveSku) {}

  public record LifecycleEvent(
      UUID id,
      UUID spuId,
      String fromStatus,
      String toStatus,
      String reason,
      String source,
      @Schema(nullable = true) UUID ruleId,
      UUID actorId,
      String occurredAt,
      String createdAt) {}

  public record LifecycleView(
      UUID spuId,
      String code,
      String status,
      String statusLabel,
      List<TransitionOption> transitions,
      List<LifecycleEvent> history) {}

  public record RuleCondition(
      @NotBlank String metric,
      @NotBlank String operator,
      @NotNull BigDecimal threshold,
      @Schema(nullable = true) String label) {}

  public record RuleInput(
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 100) String name,
      @NotNull @Size(max = 2000) String description,
      boolean enabled,
      @NotBlank String targetStatus,
      @NotNull @Size(max = 500) String reasonTemplate,
      @NotNull @Size(max = 20) List<@jakarta.validation.Valid RuleCondition> conditions,
      @PositiveOrZero long version) {}

  public record LifecycleRule(
      UUID id,
      String code,
      String name,
      String description,
      boolean enabled,
      String targetStatus,
      String reasonTemplate,
      long version,
      List<RuleCondition> conditions) {}

  /** 指标可用性：available=false 表示依赖的上游投影尚未接入，规则引用它会被显式拒绝。 */
  public record MetricInfo(
      String code,
      String label,
      String source,
      String sourceLabel,
      boolean available,
      String note) {}

  public record RuleMatch(UUID spuId, String code, String name, String currentStatus) {}

  public record RulePreview(
      UUID ruleId,
      String ruleCode,
      String targetStatus,
      int scanned,
      int matchedCount,
      boolean hasMore,
      @Schema(nullable = true) String nextCursor,
      List<RuleMatch> matches) {}

  public record RuleInputScope(
      @NotNull UUID ruleId,
      @Schema(nullable = true) @Size(max = 500) String reason,
      @Schema(nullable = true) String occurredAt,
      @PositiveOrZero int limit) {}

  public record RuleRun(
      UUID ruleId,
      String ruleCode,
      String targetStatus,
      int matchedCount,
      int appliedCount,
      String reason) {}

  // ------------------------------------------------ 3.1.2.1 SKU 列表

  /**
   * SKU 列表筛选条件。区间条件分 min/max，min 与 max 同时为空表示不筛。
   *
   * <p>依赖未接入投影的条件（库存区间、价格区间、是否缺货、是否在售）不会静默生效：
   * {@link SkuListService} 在执行前显式拒绝并说明归哪个服务，见该类 {@code requireProjections}。
   */
  public record SkuListQuery(
      @Schema(nullable = true) UUID spuId,
      @Schema(nullable = true) String category,
      @Schema(nullable = true) List<String> statuses,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String barcode,
      @Schema(nullable = true) String spec,
      @Schema(nullable = true) BigDecimal onHandMin,
      @Schema(nullable = true) BigDecimal onHandMax,
      @Schema(nullable = true) BigDecimal priceMin,
      @Schema(nullable = true) BigDecimal priceMax,
      @Schema(nullable = true) Boolean outOfStock,
      @Schema(nullable = true) Boolean onSale,
      @Schema(nullable = true) String platform,
      String sort,
      String order,
      @PositiveOrZero int page,
      @PositiveOrZero int size) {}

  /** 一格投影字段的展示值：available=false 时界面显示"未接入"，绝不显示 0。 */
  public record SkuProjectionCell(
      String field,
      String label,
      String source,
      @Schema(nullable = true) BigDecimal value,
      @Schema(nullable = true) String text,
      boolean available,
      String note) {}

  /** 规格轴上的一组取值展示，例如「颜色=黑色」。 */
  public record SkuSpec(String axis, String value) {}

  public record SkuListRow(
      UUID id,
      UUID spuId,
      String spuCode,
      String spuName,
      String category,
      String code,
      String name,
      String barcode,
      String baseUnit,
      String status,
      long version,
      String variantSignature,
      List<SkuSpec> specs,
      List<SkuProjectionCell> projections,
      String updatedAt) {}

  public record SkuPage(List<SkuListRow> items, long total, int page, int size) {}

  /** 列表页顶部的能力声明：哪些筛选可用、哪些字段未接入，前端据此禁用控件而不是猜。 */
  public record SkuListCapabilities(List<SkuFieldInfo> fields, List<String> availableFilters) {}

  public record SkuFieldInfo(
      String code,
      String label,
      String source,
      String kind,
      boolean available,
      boolean rangeFilterable,
      String note) {}

  /** 批量操作请求。批量分配与批量生成刊登依赖 04 刊登，由端口范式显式拒绝，不假实现。 */
  public record SkuBatchInput(
      @NotBlank String action,
      @NotNull @Size(max = 500) List<@NotNull UUID> skuIds,
      @NotBlank @Size(max = 200) String targetStatus,
      @NotNull @Size(max = 500) String reason,
      @Schema(nullable = true) String occurredAt,
      boolean dryRun) {}

  /** 批量结果里的单个 SKU 条目；skipped 必须带原因，否则调用方无法判断该不该重试。 */
  public record SkuBatchItem(UUID skuId, String code, String outcome, String reason) {}

  public record SkuBatchResult(
      UUID batchId,
      String action,
      String targetStatus,
      boolean dryRun,
      int requestedCount,
      int matchedCount,
      int appliedCount,
      int skippedCount,
      String reason,
      List<SkuBatchItem> items) {}

  // ------------------------------------------------ 3.1.2.2 SKU 详情

  /**
   * 单个属性在某个 SKU 上的**有效值**解析结果。
   *
   * <p>{@code source} 区分「谁的值在生效」：SKU_OVERRIDE / SKU_CLEARED / SPU_COMMON / UNSET。
   * 只回一个 values 数组的话，运营分不清"没填"和"被清空了"，这两种都要补。
   */
  public record SkuEffectiveAttribute(
      UUID attributeId,
      String code,
      String name,
      String dataType,
      String valueMode,
      String valueModeLabel,
      boolean multiple,
      boolean required,
      boolean variantAxis,
      String unit,
      String groupName,
      List<String> options,
      String overrideState,
      String source,
      String sourceLabel,
      List<String> rawValues,
      List<String> effectiveValues,
      boolean inherited,
      boolean missingRequired) {}

  /** 属性区的汇总：详情页顶部直接用它显示"这个 SKU 的档案完整度"。 */
  public record SkuAttributeSummary(
      int total,
      int overridden,
      int cleared,
      int inherited,
      int unset,
      int variantAxes,
      int missingRequired) {}

  /** 包装尺寸重量。一行代表已维护；可空字段表示这一维度还没采集到，不是"等于 0"。 */
  public record SkuPackaging(
      long version,
      @Schema(nullable = true) BigDecimal lengthMm,
      @Schema(nullable = true) BigDecimal widthMm,
      @Schema(nullable = true) BigDecimal heightMm,
      @Schema(nullable = true) BigDecimal netWeightG,
      @Schema(nullable = true) BigDecimal grossWeightG,
      String packagingType,
      @Schema(nullable = true) BigDecimal volumeCm3,
      String updatedAt) {}

  /** 单位换算的一行。quantityPerBase 是「1 个该单位 = 多少个基础单位」。 */
  public record SkuUnitConversion(
      String unitCode,
      String role,
      String roleLabel,
      BigDecimal quantityPerBase,
      boolean current,
      String effectiveFrom,
      @Schema(nullable = true) String effectiveTo) {}

  /** 条码区：内部条码 + 外部多 scheme 条码的归属说明。seller_sku 是平台属性，不在这里。 */
  public record SkuBarcode(String primary, String identifierOwner, String identifierNote) {}

  /** 媒体区的一行。只取 product_asset 现有可能的列，文件键缺失如实标在 limitation 里。 */
  public record SkuAsset(
      UUID id,
      String title,
      String notes,
      String reference,
      String status,
      String locale,
      String targetCountry,
      @Schema(nullable = true) String expiresOn,
      String updatedAt) {}

  /** 状态变更历史（3.1.2.1 落的 sku_status_event，详情页可回溯）。 */
  public record SkuStatusChange(
      UUID id,
      String fromStatus,
      String toStatus,
      String reason,
      String source,
      UUID actorId,
      String occurredAt,
      String createdAt) {}

  /** 跨服务子分区的可用性声明，前端据此把整区标成「未接入」而不是显示 0。 */
  public record SkuDetailSource(String code, String label, String owner, boolean available, String note) {}

  /**
   * SKU 详情聚合体。
   *
   * <p>{@code version} 是编辑入口的乐观锁凭据：任何分区写回都要带上读取时的 version，
   * 版本不符直接 409，绝不覆盖别人的修改。
   */
  public record SkuDetail(
      UUID id,
      String code,
      String name,
      String status,
      long version,
      String variantSignature,
      String createdAt,
      String updatedAt,
      UUID spuId,
      String spuCode,
      String spuName,
      String brand,
      String category,
      String spuStatus,
      List<SkuSpec> specs,
      SkuAttributeSummary attributeSummary,
      List<SkuEffectiveAttribute> attributes,
      String baseUnit,
      List<SkuUnitConversion> unitConversions,
      SkuBarcode barcode,
      @Schema(nullable = true) SkuPackaging packaging,
      boolean mediaAvailable,
      List<SkuAsset> media,
      String mediaLimitation,
      List<SkuDetailSource> sources,
      List<SkuStatusChange> statusHistory) {}

  /**
   * SKU 详情编辑请求。
   *
   * <p>只覆盖本模块（3.1.2.2）拥有的档案字段：名称、条码、基础单位与包装尺寸重量。
   * 属性覆写归 3.2.2.2、编码规则与外部条码归 3.1.2.3、单位版本维护归 3.1.2.4，
   * 都在这里显式拒绝而不是偷偷代劳。
   */
  public record SkuDetailInput(
      @NotBlank @Size(max = 200) String name,
      @NotNull @Size(max = 128) String barcode,
      @NotBlank @Size(max = 32) String baseUnit,
      @Schema(nullable = true) @Size(max = 64) String packagingType,
      @Schema(nullable = true) BigDecimal lengthMm,
      @Schema(nullable = true) BigDecimal widthMm,
      @Schema(nullable = true) BigDecimal heightMm,
      @Schema(nullable = true) BigDecimal netWeightG,
      @Schema(nullable = true) BigDecimal grossWeightG,
      @PositiveOrZero long version) {}

  /** 编辑结果：回传新的 version，前端据此继续编辑而不必重新拉整页。 */
  public record SkuDetailSaved(UUID id, long version, boolean packagingChanged) {}

  // ------------------------------------------------ 3.1.2.3 编码与条码

  /** 编码规则定义。prefix/seqWidth/suffix 决定展开形状，scope 决定它管哪些 SKU。 */
  public record SkuCodeRuleInput(
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 100) String name,
      @NotNull @Size(max = 2000) String description,
      @NotBlank String scope,
      @Schema(nullable = true) UUID spuId,
      @NotNull @Size(max = 32) String prefix,
      @NotNull @Size(max = 32) String suffix,
      @Positive @Max(12) int seqWidth,
      @Positive long startFrom,
      boolean enabled,
      @PositiveOrZero long version) {}

  /**
   * 规则视图。
   *
   * <p>{@code nextValue} 是下一个将被分配的序号，{@code sampleCode} 是用它展开的样例——
   * 运营在保存前就能看到"这条规则会产出什么样的编码"，而不是先生成 500 个再看。
   */
  public record SkuCodeRule(
      UUID id,
      String code,
      String name,
      String description,
      String scope,
      String scopeLabel,
      @Schema(nullable = true) UUID spuId,
      @Schema(nullable = true) String spuCode,
      String prefix,
      String suffix,
      int seqWidth,
      long startFrom,
      long nextValue,
      String sampleCode,
      boolean enabled,
      long version,
      String updatedAt) {}

  public record SkuCodeRuleSaved(UUID id, long version, String sampleCode) {}

  /** 手工输入内部编码。version 是乐观锁凭据，不符直接 409。 */
  public record SkuCodeInput(@NotBlank @Size(max = 64) String code, @PositiveOrZero long version) {}

  /** 编码变更结果：回传 fromCode 便于界面显示"从 X 改成 Y"。 */
  public record SkuCodeSaved(UUID skuId, String fromCode, String toCode, long version) {}

  /** 单个外部条码（sku_identifier 的一行）。 */
  public record SkuIdentifier(
      UUID id,
      String scheme,
      String schemeLabel,
      String value,
      boolean isPrimary,
      String source,
      String occurredAt,
      String createdAt) {}

  /** 编码分配留痕（sku_code_assignment 的一行）。 */
  public record SkuCodeAssignment(
      UUID id,
      String fromCode,
      String toCode,
      String source,
      String sourceLabel,
      @Schema(nullable = true) String ruleCode,
      UUID actorId,
      String occurredAt,
      String createdAt) {}

  /**
   * 单个 SKU 的编码与条码面板。
   *
   * <p>{@code internalRuleCode} 标出当前编码是哪条规则生成的；为空表示手工输入或历史数据。
   */
  public record SkuCodePanel(
      UUID skuId,
      String skuCode,
      String skuName,
      String spuCode,
      String internalCode,
      String internalSource,
      @Schema(nullable = true) String internalRuleCode,
      long version,
      List<SkuIdentifier> identifiers,
      List<SkuCodeAssignment> assignments) {}

  /** 录入/扫码一个外部条码。source 留空按 MANUAL 处理。 */
  public record SkuIdentifierInput(
      @NotBlank @Size(max = 24) String scheme,
      @NotBlank @Size(max = 128) String value,
      boolean isPrimary,
      @Schema(nullable = true) String occurredAt,
      @Schema(nullable = true) @Size(max = 24) String source) {}

  public record SkuIdentifierSaved(UUID id, String scheme, String value, boolean isPrimary) {}

  /**
   * 批量补齐 / 按规则自动生成请求。
   *
   * <p>GENERATE 对 skuIds 指定的 SKU 逐个生成；BACKFILL 忽略 skuIds，扫该规则范围内的
   * 「尚无编码」的 SKU 补齐。两者共用同一套拒绝清单与留痕。
   */
  public record SkuCodeBatchInput(
      @NotBlank String action,
      @NotNull UUID ruleId,
      @Schema(nullable = true) @Size(max = 500) List<@NotNull UUID> skuIds,
      @Schema(nullable = true) String occurredAt,
      boolean dryRun) {}

  /**
   * 批量结果里的单行。
   *
   * <p>三个 outcome 必须分清：APPLIED 改成功；SKIPPED 跳过（如已有编码，规则不覆盖手工编码）；
   * REJECTED 被拒（如展开编码越界或与既有编码冲突）——后两者都带原因，供用户改完重传。
   */
  public record SkuCodeBatchItem(
      int rowNo,
      @Schema(nullable = true) UUID skuId,
      String skuCode,
      String outcome,
      String reason,
      @Schema(nullable = true) String codeBefore,
      @Schema(nullable = true) String codeAfter) {}

  public record SkuCodeBatchResult(
      UUID batchId,
      String action,
      String ruleCode,
      boolean dryRun,
      int requestedCount,
      int matchedCount,
      int appliedCount,
      int skippedCount,
      int rejectedCount,
      List<SkuCodeBatchItem> items) {}

  /** 条码批量导入的一行。skuId 指向条码归属的 SKU；冲突时进拒绝清单。 */
  public record SkuIdentifierRow(
      @NotNull UUID skuId,
      @NotBlank @Size(max = 24) String scheme,
      @NotBlank @Size(max = 128) String value,
      boolean isPrimary,
      @Schema(nullable = true) String occurredAt) {}

  public record SkuIdentifierImportInput(
      @NotNull @Size(max = 1000) List<@jakarta.validation.Valid SkuIdentifierRow> rows,
      @Schema(nullable = true) String occurredAt,
      boolean dryRun) {}

  public record SkuIdentifierImportItem(
      int rowNo, @Schema(nullable = true) UUID skuId, String scheme, String value, String outcome, String reason) {}

  public record SkuIdentifierImportResult(
      UUID batchId,
      boolean dryRun,
      int requestedCount,
      int appliedCount,
      int rejectedCount,
      List<SkuIdentifierImportItem> items) {}

  /** 各 scheme 的条码数量，用于概览页显示"我们都有什么条码"。 */
  public record SkuIdentifierSchemeCount(String scheme, String schemeLabel, long count) {}

  public record SkuCodeBatchSummary(
      UUID batchId,
      String action,
      String status,
      int requestedCount,
      int appliedCount,
      int skippedCount,
      int rejectedCount,
      String createdAt) {}

  /**
   * 编码与条码概览。
   *
   * <p>{@code sellerSkuNote} 显式写明边界：店铺 seller_sku 是 04 刊登的平台属性，不是内部编码。
   */
  public record SkuCodeOverview(
      long totalSkus,
      long uncodedSkus,
      long identifierCount,
      long ruleCount,
      long enabledRuleCount,
      List<SkuIdentifierSchemeCount> schemeCounts,
      List<SkuCodeBatchSummary> recentBatches,
      String sellerSkuNote) {}

}
