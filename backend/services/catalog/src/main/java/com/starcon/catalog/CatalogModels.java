package com.starcon.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
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

  // ------------------------------------------------ 3.1.2.4 单位与换算

  /**
   * 单位字典写入请求。
   *
   * <p>{@code code} 允许中文与拉丁字母数字：单位码不是 SKU 内部编码，历史数据里就是
   * '件' / '箱' / '千克'，套 [A-Z0-9] 词表会把既有数据判成非法。
   */
  public record UnitOfMeasureInput(
      @NotBlank @Size(max = 32) String code,
      @NotBlank @Size(max = 100) String name,
      @NotBlank String dimension,
      @Min(0) @Max(6) int scale,
      boolean allowPurchase,
      boolean allowSale,
      boolean allowStorage,
      boolean enabled,
      @PositiveOrZero long version) {}

  /**
   * 单位字典一行。
   *
   * <p>{@code conversionCount} 是"有多少条换算版本引用了这个单位"，删除前必须看见它。
   * {@code scale} 是数量精度：决定单据上该单位数量显示几位小数，与存储精度分开。
   */
  public record UnitOfMeasure(
      UUID id,
      String code,
      String name,
      String dimension,
      String dimensionLabel,
      int scale,
      boolean allowPurchase,
      boolean allowSale,
      boolean allowStorage,
      boolean enabled,
      long conversionCount,
      long version,
      String createdAt,
      String updatedAt) {}

  public record UnitOfMeasureSaved(UUID id, long version) {}

  /**
   * 新增一条换算版本。
   *
   * <p>{@code quantityPerBase} 是「1 个该单位等于多少个基础单位」（1 箱 = 12 件 → 12）。
   * 生效区间由 V6 的触发器保证不重叠：与已有版本重叠时由 DB 拒绝，应用层转成可读的 409。
   */
  public record UnitConversionInput(
      @NotBlank @Size(max = 32) String unitCode,
      @NotBlank String role,
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 13, fraction = 6)
          BigDecimal quantityPerBase,
      @NotBlank String effectiveFrom,
      @Schema(nullable = true) String effectiveTo) {}

  public record UnitConversionSaved(
      UUID id, UUID skuId, String unitCode, String role, String effectiveFrom,
      @Schema(nullable = true) String effectiveTo) {}

  /** 换算表里的一行（一个生效版本）。 */
  public record UnitConversionVersion(
      UUID id,
      String unitCode,
      String role,
      String roleLabel,
      BigDecimal quantityPerBase,
      String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      boolean current,
      boolean registered,
      boolean snapshotted,
      String createdAt) {}

  /**
   * 一个「单位 + 角色」下的全部版本，按生效起始日倒序。
   *
   * <p>把版本聚成一组而不是拉平成一长串：运营问的是"箱这个采购单位现在几件一箱、
   * 以前是几件"，聚在一起才看得出比例改过几次。
   */
  public record UnitConversionGroup(
      String unitCode,
      @Schema(nullable = true) String unitName,
      String role,
      String roleLabel,
      boolean registered,
      @Schema(nullable = true) UUID currentVersionId,
      @Schema(nullable = true) BigDecimal currentQuantityPerBase,
      List<UnitConversionVersion> versions) {}

  /**
   * 单个 SKU 的单位与换算面板。
   *
   * <p>{@code baseUnit} 来自 {@code sku.base_unit}（V1 的列），**不在换算表里**：
   * 基础单位是 SKU 自身的属性，换算表只放其他单位相对它的比例。
   * {@code baseUnitRegistered} 为 false 表示这个基础单位还没进字典——这是本模块要提示的治理缺口，
   * 不是错误：换算与冻结仍可进行，只是量纲校验对它放行。
   */
  public record UnitPanel(
      UUID skuId,
      String skuCode,
      String skuName,
      String baseUnit,
      @Schema(nullable = true) String baseUnitName,
      boolean baseUnitRegistered,
      List<UnitConversionGroup> groups,
      List<UnitSnapshotLine> snapshots,
      long snapshotCount) {}

  /** 换算计算请求。asOf 留空取今天；quantity 是单据上的原始数量。 */
  public record UnitConvertInput(
      @NotNull UUID skuId,
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 13, fraction = 6)
          BigDecimal quantity,
      @NotBlank @Size(max = 32) String unitCode,
      @Schema(nullable = true) String role,
      @Schema(nullable = true) String asOf) {}

  /**
   * 换算结果。
   *
   * <p>{@code conversionId} 为空表示命中的是基础单位本身（比例恒为 1）。
   * {@code effectiveFrom}/{@code effectiveTo} 是这一版的生效区间——单据据此回显"用的是哪一版"。
   */
  public record UnitConvertResult(
      UUID skuId,
      String skuCode,
      String unitCode,
      @Schema(nullable = true) String unitName,
      String role,
      String roleLabel,
      BigDecimal quantity,
      BigDecimal quantityPerBase,
      String baseUnit,
      BigDecimal baseQuantity,
      String asOf,
      @Schema(nullable = true) UUID conversionId,
      @Schema(nullable = true) String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      String note) {}

  /** 冻结快照的一行。{@code created=false} 表示该单据行此前已冻结，本次返回原行未覆盖。 */
  public record UnitSnapshotLine(
      int lineNo,
      UUID skuId,
      String skuCode,
      String role,
      String roleLabel,
      String unitCode,
      String baseUnit,
      BigDecimal quantity,
      BigDecimal quantityPerBase,
      BigDecimal baseQuantity,
      @Schema(nullable = true) UUID sourceConversionId,
      String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      boolean created,
      String occurredAt) {}

  /** 冻结快照请求。lines 一次提交一张单据的明细，行号由 lineNo 显式给出以便对齐单据。 */
  public record UnitSnapshotLineInput(
      @Positive int lineNo,
      @NotNull UUID skuId,
      @NotBlank @Size(max = 32) String unitCode,
      @NotBlank String role,
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 13, fraction = 6)
          BigDecimal quantity) {}

  public record UnitSnapshotInput(
      @NotBlank String documentType,
      @NotBlank @Size(max = 100) String documentRef,
      @Schema(nullable = true) String occurredAt,
      @NotNull @Size(max = 500) List<@jakarta.validation.Valid UnitSnapshotLineInput> lines) {}

  /** 一张单据的冻结快照。 */
  public record UnitSnapshot(
      UUID id,
      String documentType,
      String documentTypeLabel,
      String documentRef,
      String occurredAt,
      String createdAt,
      int requestedCount,
      int createdCount,
      int reusedCount,
      List<UnitSnapshotLine> lines) {}

  /** 各量纲下的单位数量，用于概览显示"字典覆盖了哪些量纲"。 */
  public record UnitDimensionCount(String dimension, String dimensionLabel, long count) {}

  public record UnitOverview(
      long unitCount,
      long enabledUnitCount,
      long unregisteredUnitCount,
      long conversionCount,
      long snapshotCount,
      List<UnitDimensionCount> dimensions,
      String note) {}

  // ------------------------------------------------ 3.1.3.1 规格轴定义

  /**
   * 规格轴字典写入请求。
   *
   * <p>{@code code} 是轴的机器标识，且**必须与承载它的 variant_axis 属性定义同 code**
   * （V9 的 guard_spu_spec_axis 触发器强制）：两边各叫各的会让值源永远对不齐。
   */
  public record SpecAxisInput(
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 100) String name,
      boolean enabled,
      @PositiveOrZero long version) {}

  /**
   * 规格轴字典一行。
   *
   * <p>{@code optionCount} 是可用选项数（不含停用），{@code boundSpuCount} 是引用它的 SPU 数。
   * 两个计数都是"删除前必须看见"的信息：被引用时删轴会让那些 SPU 的规格凭空消失。
   */
  public record SpecAxis(
      UUID id,
      String code,
      String name,
      boolean enabled,
      long optionCount,
      long enabledOptionCount,
      long boundSpuCount,
      long version,
      String createdAt,
      String updatedAt) {}

  public record SpecAxisSaved(UUID id, long version) {}

  /**
   * 规格轴的一个选项。
   *
   * <p>{@code sourceAttributeCode} 是**这个值在属性选项值源里的承载属性**：
   * 规格选项不得独立于属性选项存在（蓝图硬约束），所以界面上必须把"它同时也是一个属性选项"
   * 讲出来，而不是让人以为规格轴自己存了一份值。
   */
  public record SpecAxisOption(
      String value,
      int position,
      boolean enabled,
      UUID sourceAttributeId,
      String sourceAttributeCode,
      String sourceAttributeName,
      String createdAt) {}

  /** 规格轴选项写入请求。value 会同时落到属性选项值源与规格轴选项，两处同事务。 */
  public record SpecAxisOptionInput(
      @NotBlank @Size(max = 100) String value,
      @PositiveOrZero int position,
      boolean enabled) {}

  public record SpecAxisOptionSaved(UUID specAxisId, String value, int position) {}

  /**
   * 绑定请求。
   *
   * <p>{@code createAttribute=true} 允许在 SPU 上还没有同 code 的 variant_axis 属性时自动建一条
   * 承载属性——3.2.1.1 属性定义列表未开发，规格轴必须能在自己的端点里自洽地走完，
   * 不能因为"属性管理页面不存在"就让链路断掉。
   */
  public record SpecAxisBindingInput(
      @NotNull UUID specAxisId,
      @PositiveOrZero int position,
      boolean createAttribute,
      @Schema(nullable = true) @Size(max = 100) String attributeName) {}

  public record SpecAxisBindingSaved(
      UUID id, UUID spuId, UUID specAxisId, UUID attributeDefinitionId, int position) {}

  /** 类目允许的规格轴规则写入请求。category 是 spu.category 的自由文本（3.3.1.1 未开发）。 */
  public record CategorySpecAxisInput(
      @NotBlank @Size(max = 100) String category,
      boolean required,
      @PositiveOrZero int position) {}

  /** 一条类目规则。{@code boundSpuCount} 让运营看见这条规则实际覆盖了多少 SPU。 */
  public record CategorySpecAxisRule(
      String category,
      UUID specAxisId,
      String specAxisCode,
      String specAxisName,
      boolean required,
      int position,
      long boundSpuCount,
      String createdAt) {}

  public record CategorySpecAxisSaved(String category, UUID specAxisId) {}

  /**
   * 单个 SPU 的规格轴面板。
   *
   * <p>{@code matrixRowCount} 是各轴可用选项的笛卡尔积行数——即"按当前规格轴展开能生成多少个 SKU"。
   * 它是 {@code long}：四条各 50 个选项已经是 6.25M 行，{@code int} 会溢出成负数。
   */
  public record SpecAxisPanel(
      UUID spuId,
      String spuCode,
      String spuName,
      String spuCategory,
      List<SpecAxisBinding> axes,
      long matrixRowCount,
      long boundAxisCount,
      long missingRequiredAxisCount,
      List<String> requiredAxisNotes) {}

  /** SPU 上一条已绑定的规格轴，连同它的选项与承载属性。 */
  public record SpecAxisBinding(
      UUID specAxisId,
      String specAxisCode,
      String specAxisName,
      UUID attributeDefinitionId,
      String attributeCode,
      String attributeName,
      int position,
      long skuCount,
      long enabledOptionCount,
      long totalOptionCount,
      List<SpecAxisOption> options) {}

  /**
   * 规格轴概览。
   *
   * <p>{@code unboundVariantAxisAttributes} 是本模块的治理缺口指标：V1 允许
   * {@code variant_axis=true} 的属性定义独立存在（3.1.1/3.1.2 的既有测试就这么写），
   * 本迁移**不加"必须挂到轴上"的触发器**（那会打碎已交付模块的写入口），改为把缺口显式暴露出来。
   * 0 与"没查过"含义不同，界面上要分开写。
   */
  public record SpecAxisOverview(
      long axisCount,
      long enabledAxisCount,
      long optionCount,
      long boundSpuCount,
      long categoryRuleCount,
      long unboundVariantAxisAttributes,
      List<SpecAxisUsageCount> topAxes,
      String note) {}

  /** 各轴被 SPU 复用的次数，用于概览里看"哪些轴真正在用"。 */
  public record SpecAxisUsageCount(
      UUID specAxisId, String code, String name, long boundSpuCount, long optionCount) {}

  // ------------------------------------------------ 3.1.3.2 组合唯一性校验

  /** 组合里的一对「规格轴 + 取值」输入。取值最终落到 spec_axis_option 里的真实取值上。 */
  public record CombinationValueInput(
      @NotNull UUID specAxisId, @NotBlank @Size(max = 100) String value) {}

  /** 一个待建组合：必须给本 SPU 每条已绑定轴各一个取值。 */
  public record CombinationInput(@NotEmpty @Size(max = 100) List<@Valid CombinationValueInput> values) {}

  /** 批量校验请求。整批要么全部通过、要么给出完整冲突清单，不做部分成功。 */
  public record CombinationCheckInput(
      @NotEmpty @Size(max = 2000) List<@Valid CombinationInput> combinations) {}

  /**
   * 生成待建 SKU 草稿的请求。
   *
   * <p>{@code mode=MATRIX} 时按各轴启用选项展开笛卡尔积，{@code combinations} 留空；
   * {@code mode=EXPLICIT} 时按提交的组合集合生成。两种模式都先过同一套预校验。
   */
  public record VariantDraftInput(
      @NotBlank String mode,
      @Schema(nullable = true) @Size(max = 2000) List<@Valid CombinationInput> combinations,
      @Schema(nullable = true) @Size(max = 500) String notes,
      @Schema(nullable = true) String occurredAt) {}

  /**
   * 组合里的一对「轴 + 取值」，已解析出轴编码与轴名称。
   *
   * <p>{@code specAxisId} 可空：按 SKU 轴值实时解析出来的组合（来源 {@code source=SKU}）
   * 只能定位到轴编码——那个 SKU 的属性值行上并没有规格轴的 id，硬凑一个会骗人。
   */
  public record CombinationValue(
      @Schema(nullable = true) UUID specAxisId,
      String axisCode,
      String axisName,
      String value,
      int position) {}

  /** 某条轴上的一个取值。停用取值仍在列里——历史组合要能解析出来。 */
  public record AxisOption(String value, int position, boolean enabled) {}

  /** 本 SPU 绑定的一条轴，连同它的取值集合。预校验就是拿它当基准。 */
  public record BoundAxis(
      UUID specAxisId,
      String code,
      String name,
      int position,
      boolean enabled,
      List<AxisOption> options) {}

  /** 预校验通过的组合：签名 + 轴值。生成草稿时按它落库。 */
  public record PlannedCombination(String signature, List<CombinationValue> values) {}

  /**
   * 一条冲突。
   *
   * <p>蓝图要求输出是**结构化冲突清单**而不是一个布尔值，理由很实际：
   * 运营看到"重复 3 个组合"没法干活，他需要知道是哪几个组合、分别撞了谁
   * （撞已存在的 SKU？撞上一次生成的草稿？同一批里自己撞自己？）、出现在第几条。
   */
  public record CombinationConflict(
      String reasonCode,
      String reason,
      String message,
      @Schema(nullable = true) String signature,
      @Schema(nullable = true) List<CombinationValue> values,
      List<Integer> occurrenceIndexes,
      @Schema(nullable = true) UUID existingCombinationId,
      @Schema(nullable = true) UUID existingSkuId,
      @Schema(nullable = true) UUID existingDraftId) {}

  /**
   * 校验结果。
   *
   * <p>{@code planned} 是**去重后**的合法组合，不是提交原样回显：
   * "提交 10 条、其中 3 条自己撞自己、合法 7 条"这件事必须在结构里说清楚。
   */
  public record CombinationCheckResult(
      boolean valid,
      UUID spuId,
      List<BoundAxis> axes,
      long submittedCount,
      long plannedCount,
      long conflictCount,
      List<CombinationConflict> conflicts,
      List<PlannedCombination> planned,
      List<String> notes) {}

  /** 已存在的组合：来源是草稿占用（RESERVED）还是已兑现的真实 SKU（SKU）。 */
  public record ExistingCombination(
      String signature,
      List<CombinationValue> values,
      String source,
      @Schema(nullable = true) UUID combinationId,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) UUID draftId) {}

  /** 草稿摘要（列表态）。明细在 {@link SkuMatrixDraft#combinations()}。 */
  public record SkuMatrixDraftSummary(
      UUID id,
      UUID spuId,
      String mode,
      String status,
      int axisCount,
      int combinationCount,
      @Schema(nullable = true) String notes,
      String occurredAt,
      String createdAt,
      @Schema(nullable = true) String discardedAt) {}

  /** 一份待建 SKU 草稿。它的组合已在组合台账里占住位——草稿就是"占位"的凭据。 */
  public record SkuMatrixDraft(
      UUID id,
      UUID spuId,
      String spuCode,
      String spuName,
      String mode,
      String status,
      int axisCount,
      int combinationCount,
      @Schema(nullable = true) String notes,
      List<PlannedCombination> combinations,
      String occurredAt,
      String createdAt,
      @Schema(nullable = true) String discardedAt) {}

  public record SkuMatrixDraftDiscarded(UUID draftId, int releasedCombinationCount) {}

  /**
   * 单 SPU 的组合校验面板。
   *
   * <p>{@code availableMatrixCount} 是"矩阵里还没被占用的组合数"：
   * 拿它减 {@code matrixRowCount} 就知道矩阵被占了多少，这是运营决定
   * "还能不能再生成一批草稿"的唯一依据。
   */
  public record VariantCombinationPanel(
      UUID spuId,
      String spuCode,
      String spuName,
      List<BoundAxis> axes,
      long matrixRowCount,
      long reservedCombinationCount,
      long existingSkuCount,
      long conflictMatrixCount,
      long availableMatrixCount,
      boolean matrixTooLarge,
      long matrixCap,
      List<ExistingCombination> combinations,
      List<SkuMatrixDraftSummary> drafts,
      String note) {}

  /**
   * 组合唯一性概览。
   *
   * <p>{@code blockedMatrixSpuCount} 是治理缺口指标：有已存在 SKU 组合的 SPU 数量——
   * 这些 SPU 再生成草稿时会与历史 SKU 逐条比对，缺口大说明历史 SKU 多，
   * 拿它跟 {@code pendingDraftCount} 一起看才知道草稿这条路走不走得通。
   */
  public record VariantCombinationOverview(
      long axisBoundSpuCount,
      long reservedCombinationCount,
      long draftCount,
      long pendingDraftCount,
      long discardedDraftCount,
      long blockedSpuCount,
      long blockedMatrixSpuCount,
      List<CombinationBlockage> topBlockedSpus,
      String note) {}

  /** 被既有 SKU 组合卡住的 SPU（按组合数倒序）。 */
  public record CombinationBlockage(
      UUID spuId, String spuCode, String spuName, long existingSkuCombinationCount) {}

  // ------------------------------------------------ 3.2.1.1 属性定义列表（租户级属性字典）

  /**
   * 属性定义写入请求（租户级字典条目）。
   *
   * <p>对应 {@code attribute_definition} 里 {@code spu_id IS NULL} 的那些行。SPU 级定义
   * （{@code spu_id IS NOT NULL}）由 3.2.2.1 的商品属性端点维护，两者在同一张表里但作用域不同，
   * 见 V11 迁移注释里的模型决策。
   *
   * <p>{@code dataType} 决定 {@code options} 与类目规则的形态：只有 OPTION 能配选项集合，
   * 只有 DECIMAL 能配 {@code minValue}/{@code maxValue} 范围。服务层会拒绝不匹配的组合。
   *
   * <p>{@code variantAxis} 沿用 V1 的蕴含式（规格轴必须是按 SKU 填写的必填单选枚举属性），
   * V11 追加了一条 `variant_axis → usage='SPECIFICATION'`，让蓝图里的「用途=规格」真的生效。
   */
  public record AttributeDefinitionInput(
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 100) String name,
      @NotBlank String dataType,
      @NotBlank String valueMode,
      @NotBlank String usage,
      boolean multiple,
      boolean required,
      boolean filterable,
      boolean variantAxis,
      @Size(max = 32) String unit,
      @Size(max = 100) String groupName,
      @PositiveOrZero int position,
      boolean enabled,
      @Size(max = 50) List<@Size(max = 100) String> options,
      @PositiveOrZero long version) {}

  /**
   * 列表页的一行。
   *
   * <p>三个计数是「删除或停用前必须看见」的信息：选项数、类目规则数（这条属性被哪些类目要求了）、
   * 以及 {@code spuUsageCount}（有多少 SPU 已经有同 code 的属性定义在用这个编码）。
   * 最后一个不是外键计数——字典条目与 SPU 级定义不在同一个命名空间里，只能按 code 对齐统计。
   */
  public record AttributeDefinitionRow(
      UUID id,
      String code,
      String name,
      String dataType,
      String valueMode,
      String usage,
      boolean multiple,
      boolean required,
      boolean filterable,
      boolean variantAxis,
      boolean enabled,
      String unit,
      String groupName,
      int position,
      long optionCount,
      long enabledOptionCount,
      long categoryRuleCount,
      long spuUsageCount,
      long version,
      String createdAt,
      String updatedAt) {}

  public record AttributeDefinitionPage(
      List<AttributeDefinitionRow> items, long total, int page, int size, long enabledCount) {}

  /** 保存结果。{@code optionCount} 让前端不必为了刷新计数再打一次详情接口。 */
  public record AttributeDefinitionSaved(UUID id, long version, int optionCount) {}

  /** 列表页筛选条件。null 表示不筛；空串按「不过滤」处理。 */
  public record AttributeDefinitionQuery(
      @Schema(nullable = true) @Size(max = 200) String keyword,
      @Schema(nullable = true) @Size(max = 16) String dataType,
      @Schema(nullable = true) @Size(max = 24) String usage,
      @Schema(nullable = true) Boolean filterable,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) @Size(max = 50) String groupName,
      @PositiveOrZero int page,
      @Positive int size) {}

  /**
   * 属性选项（枚举取值）。
   *
   * <p>三个计数字段都是「删除或停用前必须看见」的信息：
   *
   * <ul>
   *   <li>{@code productValueCount} = 已有多少处商品属性值取用了它（命中
   *       {@code attribute_value_item}）。蓝图要求「有商品引用的选项只能停用」，
   *       所以这个数 &gt; 0 时删除会被拒。
   *   <li>{@code categoryRuleCount} = 有多少条类目规则把它列为可选值。删掉它会连带改掉那些规则，
   *       所以同样先拒，并提示先改规则。
   *   <li>{@code inUse} 是上面两者的合成，界面上用它决定给「删除」还是给「停用」。
   * </ul>
   *
   * <p>字典条目目前还不会被赋属性值（{@code attribute_value} 的复合外键仍锁在 SPU 作用域，
   * 那是下游 3.2.2.x 的接线），所以 {@code productValueCount} 现在恒为 0——
   * 它照实反映数据库现状，接上赋值链路后会自动开始计数，界面上不需要改。
   */
  public record AttributeOptionRow(
      String value,
      int position,
      boolean enabled,
      long productValueCount,
      long categoryRuleCount,
      boolean inUse,
      String createdAt) {}

  /** 选项写入请求。value 落在 {@code attribute_option}，也就是规格轴共享的那个值源。 */
  public record AttributeOptionInput(
      @NotBlank @Size(max = 100) String value,
      @PositiveOrZero int position,
      boolean enabled) {}

  public record AttributeOptionSaved(UUID attributeId, String value, int position, boolean enabled) {}

  // ============================================================ 3.2.1.2 属性选项管理

  /**
   * 跨属性的选项总览一行：把租户下所有枚举属性的选项拍平成一张可筛选、可排序、可分页的表。
   *
   * <p>3.2.1.1 只能按单个 attributeId 取选项，回答不了「哪些选项没人用」「哪些属性还没配值」。
   * 本行因此额外带出归属信息（{@code attributeId} / {@code attributeCode} / {@code attributeName}），
   * 让筛选、排序与批量操作都能在**不回到字典页**的前提下完成。
   *
   * <p>两个引用计数沿用 3.2.1.1 的口径，语义不变：
   *
   * <ul>
   *   <li>{@code productValueCount} = 有多少处商品属性值取用了这个选项。
   *       字典条目目前恒为 0——{@code attribute_value} 的复合外键仍锁在 SPU 作用域，
   *       那是数据库现状而非 bug，本字段照实反映，不伪造。
   *   <li>{@code categoryRuleCount} = 有多少条类目规则把它列为可选值。
   *   <li>{@code deletable} = 「此刻能不能删」。它是上面两个计数的合成，与 inUse 同源，
   *       单独给出是为了让界面不必自己重算一遍「删还是停用」。
   * </ul>
   */
  public record AttributeOptionOverviewRow(
      UUID attributeId,
      String attributeCode,
      String attributeName,
      String value,
      int position,
      boolean enabled,
      long productValueCount,
      long categoryRuleCount,
      boolean inUse,
      boolean deletable,
      String createdAt) {}

  /**
   * 跨属性选项总览的分页结果。
   *
   * <p>{@code enabledCount} 与 {@code inUseCount} 都**不受筛选条件影响**：
   * 界面上把它们放在筛选结果旁边，用户才知道「筛出来的 3 条」之外还有多少停用/被引用的条目。
   * 0 与「没查过」含义不同，所以单独返回而不是从当前页 items 推算。
   */
  public record AttributeOptionPage(
      List<AttributeOptionOverviewRow> items,
      long total,
      int page,
      int size,
      long enabledCount,
      long inUseCount,
      String note) {}

  /**
   * 选项总览的筛选与排序条件。三个布尔用 {@link Boolean} 收参保持三态（不筛 / 只要满足 / 只要不满足）。
   *
   * <p>{@code referenced} 表达的是「有没有被引用」而不是引用来源——商品取用与类目规则两条来源
   * 在 {@code productValueCount} / {@code categoryRuleCount} 上分别可见。
   *
   * <p>{@code sort} 是白名单值而不是任意列名：排序串直接拼进 SQL，
   * 只有映射到固定片段的取值才允许出库（见 AttributeOptionService.ORDER_BY）。
   */
  public record AttributeOptionQuery(
      @Schema(nullable = true) @Size(max = 200) String keyword,
      @Schema(nullable = true) UUID attributeId,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) Boolean referenced,
      @Schema(nullable = true) @Size(max = 16) String sort,
      int page,
      int size) {}

  /** 启用/停用切换请求。停用不删除：历史单据仍要能展示那个值。 */
  public record AttributeOptionToggleInput(@NotNull Boolean enabled) {}

  /** 启用/停用的结果，回带合成字段，界面不必为了判断状态再查一次列表。 */
  public record AttributeOptionToggled(
      UUID attributeId, String value, boolean enabled, boolean inUse, boolean deletable) {}

  /** 批量重排的一行：某个选项该排到第几位。 */
  public record AttributeOptionOrderItem(@NotBlank @Size(max = 100) String value, @PositiveOrZero int position) {}

  /**
   * 批量重排请求：一次提交某个属性的全部（或部分）选项及其新位置。
   *
   * <p>整体覆盖式提交而不是「逐条 update」：拖拽排序天然是一组位置，
   * 逐条提交会在中途失败时留下半套顺序——那时用户看到的是一个既不是旧顺序也不是新顺序的列表，
   * 比整批失败更难判断发生了什么。服务端在一个事务里逐条校验后统一写入。
   */
  public record AttributeOptionOrderInput(
      @NotNull UUID attributeId,
      @NotNull @Size(min = 1, max = 50) List<@Valid AttributeOptionOrderItem> items) {}

  /** 批量重排结果。{@code applied} 是真正改动的行数，0 表示提交的位置与现状一致。 */
  public record AttributeOptionOrderSaved(UUID attributeId, int submitted, int applied) {}

  /**
   * 类目属性规则一行：某类目对某属性的要求（必填 + 可选范围）。
   *
   * <p>{@code allowedOptionCount} 与 {@code allowedOptions} 只对枚举属性有意义；
   * {@code minValue}/{@code maxValue} 只对数值属性有意义。两者互斥由服务层校验。
   */
  public record CategoryAttributeRule(
      String category,
      UUID attributeId,
      String attributeCode,
      String attributeName,
      String dataType,
      boolean required,
      // 数字属性的上下限；非数字属性恒为 null。注解标注可空，否则生成的前端契约
      // 会把它们写成非空 number，界面上读 null.minValue 直接崩。
      @Schema(nullable = true) java.math.BigDecimal minValue,
      @Schema(nullable = true) java.math.BigDecimal maxValue,
      int position,
      long productCount,
      List<String> allowedOptions,
      String createdAt) {}

  /**
   * 类目属性规则写入请求。
   *
   * <p>{@code category} 是 {@code spu.category} 的自由文本（3.3.1.1 分类树未开发，无可引用的主键），
   * 与 V9 的 {@code category_spec_axis} 同一口径。
   *
   * <p>{@code allowedOptions} 是该类目允许取用的枚举值子集，必须是属性值源里已有的值
   * （复合外键兜底）。传空数组表示「不设子集，全部选项都允许」。
   */
  public record CategoryAttributeRuleInput(
      @NotBlank @Size(max = 100) String category,
      boolean required,
      @Schema(nullable = true) java.math.BigDecimal minValue,
      @Schema(nullable = true) java.math.BigDecimal maxValue,
      @PositiveOrZero int position,
      @Size(max = 50) List<@Size(max = 100) String> allowedOptions) {}

  public record CategoryAttributeRuleSaved(String category, UUID attributeId, int allowedOptionCount) {}

  /**
   * 属性字典概览。
   *
   * <p>{@code dictionaryAttributeCount} 与 SPU 级定义分开统计：V11 之后两者同表，
   * 不分开就会把「这个租户定义了几条属性字典」与「有多少商品各自建了属性」混成一个数。
   *
   * <p>{@code categoryWithoutRuleCount} 是治理缺口：已有商品的类目里，
   * 一个属性要求都没配的类目数——这些类目的录入表单不会做必填校验。
   */
  public record AttributeDictionaryOverview(
      long dictionaryAttributeCount,
      long enabledAttributeCount,
      long optionCount,
      long disabledOptionCount,
      long categoryRuleCount,
      long spuLevelDefinitionCount,
      long filterableCount,
      long specUsageCount,
      long categoryWithoutRuleCount,
      List<AttributeUsageCount> topUsedAttributes,
      String note) {}

  /** 按类目规则数倒序的属性，用来回答「哪些属性被最多类目要求了」。 */
  public record AttributeUsageCount(UUID id, String code, String name, long categoryRuleCount) {}

  // ------------------------------------------------ 3.2.2.2 SKU 属性覆写

  /**
   * 设置一条 SKU 覆写的请求。
   *
   * <p>{@code values} 为空即拒绝，**不用空数组表达"清空"**：清空是一个有后果的动作
   * （它会让这条属性不再退回 SPU 公共值），必须走独立的清空端点，否则界面上一个"保存空值"
   * 的手滑就会静默改掉解析结果。删除覆写（取消覆写、回落公共值）则是第三个动作，同样独立。
   */
  public record SkuOverrideInput(
      @NotNull @Size(min = 1, max = 50) List<@NotNull @Size(max = 2000) String> values) {}

  /**
   * 单个属性在某个 SKU 上的覆写视图。
   *
   * <p>{@code overrideState} 与 {@code source} 分开：前者是**操作事实**
   * （SET / CLEAR / INHERIT，即 SKU 侧那一行现在是什么），后者是**生效值来源**
   * （SPU_COMMON / SKU_OVERRIDE / SKU_CLEARED / UNSET，即解析结果是谁的值在起作用）。
   * 两者在"覆写了一个与公共值相同的值"时仍然不同——那是 SKU_OVERRIDE，但语义上等于没改。
   *
   * <p>{@code effectiveValues} 就是界面上要显示的**最终有效值**；空数组与"值是空串"不是一回事，
   * 界面据此显示"未设置 / 已显式清空"而不是留白。
   *
   * <p>{@code overridable=false} 时 {@code blockReason} 必非空，说明为什么这条属性改不动
   * （公共固定 / 属性已停用 / 该 SKU 已停用）。界面据此禁用控件并显示原因，
   * 而不是让人点了之后才吃一个 400。
   */
  public record SkuOverrideAttribute(
      UUID attributeId,
      String code,
      String name,
      String dataType,
      String valueMode,
      String valueModeLabel,
      boolean multiple,
      boolean required,
      boolean categoryRequired,
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
      boolean missingRequired,
      boolean overridable,
      @Schema(nullable = true) String blockReason,
      @Schema(nullable = true) String overrideUpdatedAt) {}

  /** 覆写面板顶部的汇总：四态各多少条，以及必填补全了几项。 */
  public record SkuOverrideSummary(
      int total,
      int overridden,
      int cleared,
      int inherited,
      int unset,
      int missingRequired,
      int blocked) {}

  /**
   * SKU 属性覆写面板的聚合体。
   *
   * <p>{@code skuVersion} 是 3.1.2.x 的乐观锁凭据，本模块**只读不改**它（见
   * {@code SkuAttributeOverrideService} 的类注释），所以这里回传它只是为了让界面能显示
   * 这个 SKU 的版本，不是给本模块做并发控制用的。
   */
  public record SkuOverrideView(
      UUID skuId,
      String skuCode,
      String skuName,
      String skuStatus,
      long skuVersion,
      UUID spuId,
      String spuCode,
      String spuName,
      String category,
      List<SkuOverrideAttribute> attributes,
      SkuOverrideSummary summary,
      String note) {}

  /** 必填校验里的一条缺失项：界面上按条显示"缺什么、为什么算缺"。 */
  public record SkuOverrideMissing(
      UUID attributeId,
      String code,
      String name,
      String source,
      String reason) {}

  // ============================================================ 3.3.1.1 分类树管理

  /**
   * 一棵分类树。
   *
   * <p>{@code treeKind} 区分「内部管理分类」与「平台类目映射」——蓝图明确要求这是**多棵独立的树**：
   * 两者的维护节奏完全不同（平台类目跟着平台改版走，内部类目跟着商品结构走），
   * 混成一棵会让任一方改动都牵动另一方。
   */
  public record CategoryTree(
      UUID id,
      String code,
      String name,
      String treeKind,
      @Schema(nullable = true) String platformCode,
      boolean enabled,
      long version,
      String createdAt,
      String updatedAt) {}

  /** 建树 / 改树的请求。treeKind 建后不可改——它决定这棵树能不能当内部类目用。 */
  public record CategoryTreeInput(
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 100) String name,
      @NotBlank String treeKind,
      @Schema(nullable = true) @Size(max = 32) String platformCode,
      boolean enabled,
      @PositiveOrZero long version) {}

  /** 单条多语言分类名。基础名在 {@code product_category.name}，这里存的是补充。 */
  public record CategoryNameInput(
      @NotBlank @Size(max = 16) String locale, @NotBlank @Size(max = 200) String name) {}

  /**
   * 分类树上的一个节点。
   *
   * <p>{@code path} 是从根到自己的名称路径串（"服饰 / 上装 / T恤"），供 13 分析按层级下钻；
   * {@code directProductCount} 是**直接**挂在这一节点上的商品数，
   * {@code subtreeProductCount} 含所有子孙——两者含义不同，界面上分开显示，
   * 否则「把商品挂到子类目」会让父类目的数字看起来莫名变少。
   */
  public record CategoryNode(
      UUID id,
      UUID treeId,
      String treeCode,
      String treeKind,
      @Schema(nullable = true) String platformCode,
      @Schema(nullable = true) UUID parentId,
      String code,
      String name,
      int position,
      boolean enabled,
      int depth,
      String path,
      long directProductCount,
      long subtreeProductCount,
      long secondaryProductCount,
      long attributeRuleCount,
      long specAxisRuleCount,
      Map<String, String> names,
      long version,
      String createdAt,
      String updatedAt) {}

  /**
   * 建 / 改分类的请求。
   *
   * <p>{@code parentId} 为空即根节点。拖拽改层级走 {@code CategoryMoveInput}（单独的端点），
   * 不混在这个保存里——保存表单顺手把层级改了，用户很难察觉自己动到了整棵子树的位置。
   */
  public record CategoryInput(
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 100) String name,
      @Schema(nullable = true) UUID parentId,
      @PositiveOrZero Integer position,
      boolean enabled,
      @Valid @Schema(nullable = true) List<CategoryNameInput> names,
      @PositiveOrZero long version) {}

  /**
   * 拖拽落点：新的父节点与同级位置。
   *
   * <p>单独一个 record 而不是复用 {@code CategoryInput}：拖拽只关心落点，
   * 不该顺带把名称、启用状态一起提交（那两个字段可能正在被别人编辑）。
   */
  public record CategoryMoveInput(
      @Schema(nullable = true) UUID parentId,
      @PositiveOrZero Integer position,
      @PositiveOrZero long version) {}

  /** 分类树保存结果。 */
  public record CategorySaved(UUID id, long version, int depth, String path) {}

  /** SPU 的主分类与辅助分类。 */
  public record SpuCategoryAssignment(
      UUID spuId, UUID primaryCategoryId, String primaryCategoryName, List<SpuCategoryLink> secondary) {}

  /** 一个辅助分类。 */
  public record SpuCategoryLink(UUID categoryId, String code, String name, int position) {}

  /** 写入 SPU 分类关联的请求：主分类 + 辅助分类。 */
  public record SpuCategoryInput(
      @Schema(nullable = true) UUID primaryCategoryId,
      @Schema(nullable = true) @Valid List<SpuCategoryLinkInput> secondary) {}

  /** 单个辅助分类的写入项。 */
  public record SpuCategoryLinkInput(@NotNull UUID categoryId, @PositiveOrZero Integer position) {}

  /**
   * 分类树概览：规模与几个治理缺口指标。
   *
   * <p>{@code uncategorizedSpuCount} 是「有商品但既没有主分类也没有历史类目文本」的 SPU 数——
   * 0 与「没查过」含义不同，所以单独返回（与 V8 的 unregisteredSkus 同一口径）。
   */
  public record CategoryOverview(
      long treeCount,
      long internalTreeCount,
      long platformTreeCount,
      long disabledTreeCount,
      long categoryCount,
      long disabledCategoryCount,
      long maxDepth,
      long productCount,
      long categorizedSpuCount,
      long uncategorizedSpuCount,
      long orphanedRuleCount,
      String note) {}

  // ============================================================ 3.3.1.2 类目属性要求

  /**
   * 一条类目属性要求（V15 的 {@code category_attribute_rule} 读出口径）。
   *
   * <p>三个字段共同说明「这条要求来自哪里」，缺一个运营就无法自查继承：
   * <ul>
   *   <li>{@code sourceCategoryId} / {@code sourceCategoryName} —— 规则实际挂着的那个类目节点，
   *       可能是祖先（继承而来），也可能是自己；
   *   <li>{@code depth} —— 0 表示本节点自己配的；大于 0 是从第几层祖先继承来的。
   *       页面据此把继承来的规则标注出来，并在「只显示本节点规则」与「显示全部生效规则」之间切换。
   * </ul>
   *
   * <p>{@code allowedOptions} 为空表示「不限定子集，属性值源里的全部选项都可选」，
   * 与 {@code allowFreeText}（允许提交不在选项集内的值）是两个不同方向的放宽，不要混用。
   */
  public record CategoryAttributeRequirement(
      UUID categoryId,
      String categoryName,
      UUID attributeId,
      String attributeCode,
      String attributeName,
      String dataType,
      String requirement,
      boolean enabled,
      boolean inherited,
      int depth,
      UUID sourceCategoryId,
      String sourceCategoryName,
      @Schema(nullable = true) BigDecimal minValue,
      @Schema(nullable = true) BigDecimal maxValue,
      @Schema(nullable = true) Integer maxLength,
      boolean allowFreeText,
      int position,
      List<String> allowedOptions,
      @Schema(nullable = true) CategoryAttributeCondition condition,
      // 条件取值的文本形式。与 condition 成对出现：非条件必填恒为空列表。
      // 单列一份而不是让配置页再发一次请求，是因为「当【颜色】等于红色时必填」这句话
      // 必须能在规则列表里直接看到——看不到就没法自查条件配对不对。
      List<String> conditionValues,
      long version,
      @Schema(nullable = true) String updatedAt) {}

  /**
   * 条件必填依赖的前置属性。
   *
   * <p>存的是**属性 id + 算子**，取值另表存（{@link CategoryAttributeConditionValue}）——
   * 与 V11 的 {@code category_attribute_option} 同一形态。id 而非 code 是权威：
   * 属性改 code 时条件不会跟着断（code 是运营可改的展示标识，id 才是引用）。
   */
  public record CategoryAttributeCondition(
      @NotNull UUID attributeId,
      String attributeCode,
      String attributeName,
      String dataType,
      @NotBlank String operator) {}

  /** 条件必填的一个取值。 */
  public record CategoryAttributeConditionValue(
      @NotBlank @Size(max = 200) String value, @PositiveOrZero int position) {}

  /**
   * 类目属性要求写入请求。
   *
   * <p>{@code requirement} 与 {@code condition} / {@code conditionValues} 必须成套出现：
   * 条件必填必须给出前置属性、算子与至少一个取值；非条件必填则三者都必须为空
   * （一条永远不生效的条件留在配置里，比没有条件更危险——运营会以为它生效了）。
   */
  public record CategoryAttributeRequirementInput(
      @NotNull UUID attributeId,
      @NotBlank String requirement,
      @Schema(nullable = true) BigDecimal minValue,
      @Schema(nullable = true) BigDecimal maxValue,
      @Schema(nullable = true) Integer maxLength,
      boolean allowFreeText,
      @PositiveOrZero int position,
      @Size(max = 50) List<@Size(max = 100) String> allowedOptions,
      @Schema(nullable = true) @Valid CategoryAttributeCondition condition,
      @Schema(nullable = true) @Size(max = 20) List<@Valid CategoryAttributeConditionValue> conditionValues,
      boolean enabled,
      @PositiveOrZero long version) {}

  public record CategoryAttributeRequirementSaved(
      UUID categoryId, UUID attributeId, String requirement, int conditionValueCount) {}

  /** 类目下的属性要求：生效规则集（默认）+ 本节点直接配置的规则，供配置页切换视图。 */
  public record CategoryRequirementsView(
      UUID categoryId,
      String categoryCode,
      String categoryName,
      String categoryPath,
      List<CategoryAttributeRequirement> effective,
      List<CategoryAttributeRequirement> own,
      CategoryRequirementsSummary summary) {}

  /**
   * 规则集统计。
   *
   * <p>{@code inheritedCount} 单独给出：祖先改了规则会同时影响这一层的所有子类目，
   * 「有多少条是继承来的」决定了这个类目的配置自主度，也决定改祖先时要评估多大的影响面。
   */
  public record CategoryRequirementsSummary(
      long effectiveCount,
      long requiredCount,
      long conditionalCount,
      long optionalCount,
      long ownCount,
      long inheritedCount,
      long enabledCount,
      long disabledCount) {}

  /**
   * 录入表单 schema 的一个字段。
   *
   * <p>这是 3.3.1.2 交给 3.1.1.2 商品编辑与 04 刊登表单的**核心输出**，
   * 字段设计目标是「前端不需要再问后端任何规则问题」：
   *
   * <ul>
   *   <li>{@code control} —— 控件类型（TEXT / NUMBER / SELECT / BOOLEAN / DATE），
   *       由属性数据类型 + 规则推导，前端不再自己映射一遍；
   *   <li>{@code required} —— **求值之后**的必填性。条件必填在条件成立时为 {@code true}，
   *       不成立时为 {@code false}，前端直接绑到控件的 required 属性上即可；
   *   <li>{@code options} —— 可选值列表，每项带 {@code enabled}：
   *       选项被 3.2.1.2 停用后前端要能显示为禁用而不是直接消失（消失会让运营以为自己配错了）；
   *   <li>{@code constraints} —— 校验约束（数值区间 / 长度 / 选项白名单 / 自由填写）。
   * </ul>
   */
  public record EntryFormField(
      String key,
      UUID attributeId,
      String attributeCode,
      String label,
      String dataType,
      String control,
      boolean required,
      String requirement,
      @Schema(nullable = true) EntryFormDependency dependency,
      List<EntryFormOption> options,
      EntryFormConstraints constraints,
      int position,
      boolean inherited,
      @Schema(nullable = true) UUID sourceCategoryId,
      @Schema(nullable = true) String sourceCategoryName) {}

  /**
   * 条件必填在 schema 上的依赖声明。
   *
   * <p>{@code active} 是「按本次传入的当前值，条件是否已成立」。前端拿到这个就能
   * 直接决定「字段要不要亮必填星号」，不需要自己实现一遍求值逻辑——
   * 那正是「条件必填要真正参与 schema 生成」的落点。
   */
  public record EntryFormDependency(
      UUID attributeId, String attributeCode, String label, String operator, List<String> values, boolean active) {}

  /** 可选值。停用的选项仍然返回，由前端按 {@code enabled} 置灰。 */
  public record EntryFormOption(String value, boolean enabled) {}

  /**
   * 取值约束。全部可空：非空字段的语义是「这条约束存在」，具体值看对应字段。
   *
   * <p>用嵌套 record 而不是把 5 个字段平铺进 {@link EntryFormField}：
   * 前端 {@code field.constraints?.maxLength} 这种写法能一眼看出哪些约束可空，
   * 平铺则要逐个判 null，写错一个就是「undefined 参与比较恒为 false」的静默漏校验。
   */
  public record EntryFormConstraints(
      @Schema(nullable = true) BigDecimal minValue,
      @Schema(nullable = true) BigDecimal maxValue,
      @Schema(nullable = true) Integer maxLength,
      @Schema(nullable = true) Boolean allowFreeText,
      @Schema(nullable = true) List<String> allowedOptions) {}

  /**
   * 录入表单 schema 整体输出。
   *
   * <p>{@code requiredFieldCount} 与 {@code conditionalFieldCount} 让前端不必遍历就能
   * 显示「共 N 项，其中 M 项必填」这类提示。
   */
  public record EntryFormSchema(
      UUID categoryId,
      String categoryCode,
      String categoryName,
      String categoryPath,
      int fieldCount,
      int requiredFieldCount,
      int conditionalFieldCount,
      List<EntryFormField> fields,
      String note) {}

  /**
   * 提交的属性值（保存前校验的入参）。
   *
   * <p>一律按**字符串**收，而不是按属性数据类型分字段：提交来源是 HTTP 表单，
   * JSON 里的 12 与 "12" 对 TEXT 属性都是合法输入。先按字符串到、再按目标数据类型解析，
   * 类型不符才判 {@code TYPE_MISMATCH}——这比让前端按类型拼不同形状的载荷更贴合实际录入。
   *
   * <p>{@code attributeId} 与 {@code attributeCode} 二选一：前端拿到的是 schema，
   * 用 key（= code）最顺手；批量脚本按 id 提交更稳。两者都给时以 id 为准。
   */
  public record AttributeValueInput(
      @Schema(nullable = true) UUID attributeId,
      @Schema(nullable = true) String attributeCode,
      @Schema(nullable = true) String value) {}

  /** 保存前校验请求。 */
  public record AttributeValidationInput(
      @NotNull @Size(min = 1, max = 200) List<@Valid AttributeValueInput> values) {}

  /**
   * 单个字段的校验结果。
   *
   * <p>{@code code} 用词表而不是自由文本：前端要按 code 决定提示位置与样式，
   * 而 {@code message} 是人话、可以直接显示。这与 {@link ApiErrorHandler} 的错误口径一致。
   *
   * <p>词表：{@code PASSED} / {@code MISSING} / {@code TYPE_MISMATCH} / {@code OUT_OF_RANGE} /
   * {@code LENGTH_EXCEEDED} / {@code OPTION_NOT_ALLOWED} / {@code CONDITION_UNMET} /
   * {@code UNKNOWN_ATTRIBUTE}。
   */
  public record AttributeValidationResult(
      UUID attributeId,
      String attributeCode,
      String attributeName,
      String code,
      String message,
      boolean passed,
      boolean required,
      String requirement,
      @Schema(nullable = true) Boolean conditionActive,
      @Schema(nullable = true) String submittedValue) {}

  /**
   * 保存前校验报告。
   *
   * <p>{@code valid} 是整体结论：全部 {@code PASSED} 才为 true。
   * {@code results} 按录入表单的字段顺序返回（含未被提交的字段，前端可据此逐项高亮）。
   */
  public record AttributeValidationReport(
      UUID categoryId,
      boolean valid,
      int checkedCount,
      int failedCount,
      List<AttributeValidationResult> results,
      String note) {}

  /** 类目属性要求概览：规则覆盖率。 */
  public record CategoryRequirementsOverview(
      long categoryCount,
      long categoriesWithRequirement,
      long categoriesWithoutRequirement,
      long requirementCount,
      long conditionalRequirementCount,
      long disabledRequirementCount,
      String note) {}

  // ============================================================ 3.3.2.1 品牌管理

  /** 单条多语言品牌名。基础名在 {@code brand.name}，这里存的是补充。 */
  public record BrandNameInput(
      @NotBlank @Size(max = 16) String locale, @NotBlank @Size(max = 200) String name) {}

  /** 品牌关联的一个类目（写入用）。 */
  public record BrandCategoryLinkInput(@NotNull UUID categoryId, @PositiveOrZero Integer position) {}

  /**
   * 建 / 改品牌的请求。
   *
   * <p>{@code categories} 是**整体替换**语义（提交什么就等于什么，不是增量追加）：
   * 关联类目是「这个品牌属于哪几个类目」的一组完整描述，增量语义下
   * 前端必须先取回当前全集再算差集，少发一条就是静默的关联丢失。
   *
   * <p>{@code authorizationStatus} 与凭证的关系不在这里校验成「必须同时有凭证」：
   * 允许「先登记成需授权、凭证稍后补」是正常业务流（见 V16 迁移第 6 节），
   * 被拒的应该是刊登而不是建档。
   */
  public record BrandInput(
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 100) String name,
      @Schema(nullable = true) @Size(max = 500) String logoUri,
      @NotBlank String authorizationStatus,
      boolean enabled,
      @Schema(nullable = true) @Valid List<BrandNameInput> names,
      @Schema(nullable = true) @Size(max = 50) List<@Valid BrandCategoryLinkInput> categories,
      @PositiveOrZero long version) {}

  /** 品牌保存结果。带刊登判定，前端不必为了刷新状态再打一次判定端点。 */
  public record BrandSaved(
      UUID id, long version, String authorizationStatus, boolean publishable, String publishBlockCode) {}

  /** 一条授权凭证的读出口径。 */
  public record BrandAuthorization(
      UUID id,
      UUID brandId,
      String certificateNo,
      String grantor,
      String validFrom,
      @Schema(nullable = true) String validTo,
      @Schema(nullable = true) String evidenceUri,
      String note,
      boolean enabled,
      boolean validOn,
      int daysToExpiry,
      long version,
      String createdAt,
      String updatedAt) {}

  /**
   * 凭证写入请求。
   *
   * <p>{@code validTo} 可空 = 长期有效（不是漏填）；{@code enabled=false} 是撤销，
   * 行仍在——删了就查不到「曾经有过授权、后来被撤销」，而 16 侵权检测要这个事实。
   */
  public record BrandAuthorizationInput(
      @NotBlank @Size(max = 64) String certificateNo,
      @NotBlank @Size(max = 150) String grantor,
      @NotNull String validFrom,
      @Schema(nullable = true) String validTo,
      @Schema(nullable = true) @Size(max = 500) String evidenceUri,
      @Schema(nullable = true) @Size(max = 2000) String note,
      boolean enabled,
      @PositiveOrZero long version) {}

  /** 品牌关联的一个类目（读出口径，带路径串方便界面上直接显示）。 */
  public record BrandCategoryLink(UUID categoryId, String categoryCode, String categoryName, String categoryPath, int position) {}

  /**
   * 品牌详情：品牌本体 + 多语言名 + 关联类目 + 凭证 + 刊登判定。
   *
   * <p>合成在一个 record 里而不是拆成四个端点：详情页要显示的就是这四块的**同一瞬间**，
   * 拆开的话运营在「凭证刚被别人撤销」之后看到的页面会是拼接出来的旧数据，
   * 而「凭证还在但已过期」这种状态恰恰是靠页面当场发现才不至于误刊登。
   */
  public record BrandDetail(
      UUID id,
      String code,
      String name,
      @Schema(nullable = true) String logoUri,
      String authorizationStatus,
      boolean enabled,
      long version,
      Map<String, String> names,
      List<BrandCategoryLink> categories,
      List<BrandAuthorization> authorizations,
      boolean publishable,
      @Schema(nullable = true) String publishBlockCode,
      @Schema(nullable = true) String publishBlockMessage,
      int validAuthorizationCount,
      String asOf,
      String createdAt,
      String updatedAt) {}

  /**
   * 刊登判定结果（蓝图点名的业务规则）。
   *
   * <p>04 刊登与 16 侵权检测消费这个形状：{@code publishable} 是能不能发，
   * {@code blockCode} 是机器可读的原因码（两者处理动作不同：等生效 vs 续约），
   * {@code blockMessage} 是可以直接显示的人话。
   */
  public record BrandPublishEligibility(
      UUID brandId,
      String brandName,
      String authorizationStatus,
      boolean enabled,
      boolean publishable,
      @Schema(nullable = true) String blockCode,
      @Schema(nullable = true) String blockLabel,
      @Schema(nullable = true) String blockMessage,
      int authorizationCount,
      int validAuthorizationCount,
      String asOf) {}

  /**
   * 列表页的一行。
   *
   * <p>{@code publishable} / {@code publishBlockCode} 在**列表查询里就一起算出来**，
   * 不是让前端点开详情才知道：列表页的用途就是「找出不能刊登的品牌」，
   * 要逐个点开才知道的话这个筛选就没有意义了。
   */
  public record BrandRow(
      UUID id,
      String code,
      String name,
      @Schema(nullable = true) String logoUri,
      String authorizationStatus,
      boolean enabled,
      boolean publishable,
      @Schema(nullable = true) String publishBlockCode,
      @Schema(nullable = true) String publishBlockLabel,
      int authorizationCount,
      int validAuthorizationCount,
      long categoryCount,
      String categoryNames,
      Map<String, String> names,
      long version,
      String createdAt,
      String updatedAt) {}

  /**
   * 品牌列表的分页结果。
   *
   * <p>三个统计都**不受筛选条件影响**：界面上把它们放在筛选结果旁边，
   * 用户才知道「筛出来的 3 条」之外还有多少停用 / 被阻止刊登的条目。
   * 0 与「没查过」含义不同，所以单独返回而不是从当前页 items 推算。
   */
  public record BrandPage(
      List<BrandRow> items, long total, int page, int size, long enabledCount, long blockedCount, String note) {}

  /** 列表页筛选条件。null 表示不筛；空串按「不过滤」处理。 */
  public record BrandQuery(
      @Schema(nullable = true) @Size(max = 200) String keyword,
      @Schema(nullable = true) @Size(max = 16) String authorizationStatus,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) Boolean publishable,
      @Schema(nullable = true) UUID categoryId,
      int page,
      int size) {}

  /**
   * 批量刊登判定的请求。
   *
   * <p>上限 500 是服务层的硬约束（{@code BrandService.eligibility}）：一次判定几千个品牌
   * 会把结果行数推到不可控，而刊登侧真正要判的是「这批货涉及的品牌」，
   * 那个集合天然在几十的量级。
   */
  public record BrandEligibilityBatchInput(
      @NotNull @Size(min = 1, max = 500) List<@NotNull UUID> brandIds) {}

  /**
   * 品牌概览：规模与几个治理缺口指标。
   *
   * <p>{@code blockedListingCount} 是本模块最该被盯住的数字——它就是
   * 「现在有多少品牌处于不能刊登状态」。{@code licensedWithoutCredentialCount}
   * 是更窄的一个：需授权却一条凭证都没有，属于「必然登不了」的那一类。
   */
  public record BrandOverview(
      long brandCount,
      long enabledCount,
      long disabledCount,
      long ownedCount,
      long agencyCount,
      long licensedCount,
      long blockedListingCount,
      long licensedWithoutCredentialCount,
      long expiringWithin30DaysCount,
      long categoryLinkCount,
      String note) {}

  // ============================================================ 3.3.3.1 计量单位（字典视角）

  /** 单位组成员的写入（整体替换用）。position 决定组内展示顺序。 */
  public record UnitGroupMemberInput(@NotBlank @Size(max = 32) String unitCode, @PositiveOrZero Integer position) {}

  /**
   * 建 / 改单位组的请求。
   *
   * <p>{@code members} 是**整体替换**语义（提交什么就等于什么），与 {@link BrandInput} 的
   * {@code categories} 同一口径：成员关系是「这个组有哪些单位」的完整描述，
   * 增量语义下前端必须先取回当前全集再算差集，少发一条就是静默的成员丢失。
   *
   * <p>{@code baseUnitCode} 必须是本次提交成员里的一个：组基础单位不占换算行，
   * 移出组会让已配置的换算版本失去基准。
   */
  public record UnitGroupInput(
      @NotBlank @Size(max = 32) String code,
      @NotBlank @Size(max = 100) String name,
      @NotBlank String dimension,
      @NotBlank @Size(max = 32) String baseUnitCode,
      @Schema(nullable = true) @Size(max = 500) String note,
      boolean enabled,
      @Schema(nullable = true) @Size(max = 50) List<@Valid UnitGroupMemberInput> members,
      @PositiveOrZero long version) {}

  /** 单位组保存结果。带成员数，前端不必为了刷新计数再打一次详情。 */
  public record UnitGroupSaved(UUID id, long version, int memberCount) {}

  /**
   * 组内换算版本的写入。
   *
   * <p>{@code quantityPerBase} 是「1 个该单位等于多少个**组基础单位**」
   * （组基础单位为克时，1 千克 → 1000）。区间不重叠由 V17 触发器保证，
   * 与已有版本重叠时由 DB 拒绝，应用层转成可读的 409。
   */
  public record UnitGroupConversionInput(
      @NotBlank @Size(max = 32) String unitCode,
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 13, fraction = 6)
          BigDecimal quantityPerBase,
      @NotBlank String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 组内换算版本保存结果。 */
  public record UnitGroupConversionSaved(
      UUID id, UUID groupId, String unitCode, BigDecimal quantityPerBase,
      String effectiveFrom, @Schema(nullable = true) String effectiveTo) {}

  /** 单位组一行。列表页每行自带成员数与当前生效版本数，不点开详情也看得出行不行。 */
  public record UnitGroupRow(
      UUID id,
      String code,
      String name,
      String dimension,
      String dimensionLabel,
      String baseUnitCode,
      @Schema(nullable = true) String baseUnitName,
      int memberCount,
      long conversionVersionCount,
      long openConversionCount,
      /** 组内至少有一个成员缺当前生效换算版本 → 换算查询会报 NO_EFFECTIVE_VERSION。 */
      boolean allMembersConvertible,
      String note,
      boolean enabled,
      long version,
      String createdAt,
      String updatedAt) {}

  /** 组内一个成员的当前状态。{@code currentQuantityPerBase} 为空表示这一天没有生效版本。 */
  public record UnitGroupMember(
      String unitCode,
      @Schema(nullable = true) String unitName,
      int scale,
      int position,
      /** 是否是组基础单位：基础单位比例恒为 1，不占换算行。 */
      boolean base,
      boolean unitEnabled,
      @Schema(nullable = true) BigDecimal currentQuantityPerBase,
      @Schema(nullable = true) String currentEffectiveFrom,
      @Schema(nullable = true) String currentEffectiveTo,
      long versionCount) {}

  /** 某个成员单位的一条换算版本。 */
  public record UnitGroupConversionVersion(
      UUID id,
      String unitCode,
      BigDecimal quantityPerBase,
      String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      boolean current,
      String note,
      String createdAt) {}

  /**
   * 单位组详情：组本体 + 成员 + 组内换算版本。
   *
   * <p>{@code asOf} 是这批「当前生效」标记的基准日，按它求值而不是按服务器当天——
   * 补录一条 2024 年的比例时，"哪一版当时生效"与"今天哪一版生效"必须能分开看。
   */
  public record UnitGroupDetail(
      UnitGroupRow group,
      String asOf,
      List<UnitGroupMember> members,
      List<UnitGroupConversionVersion> conversions,
      long conversionVersionCount) {}

  /** 单位组分页结果。三个统计不受筛选影响，让用户知道筛出来的之外还有什么。 */
  public record UnitGroupPage(
      List<UnitGroupRow> items, long total, int page, int size,
      long enabledCount, long disabledCount, String note) {}

  /** 单位组列表筛选条件。null 表示不筛；空串按不过滤处理。 */
  public record UnitGroupQuery(
      @Schema(nullable = true) @Size(max = 200) String keyword,
      @Schema(nullable = true) @Size(max = 16) String dimension,
      @Schema(nullable = true) Boolean enabled,
      int page,
      int size) {}

  /**
   * 换算查询的请求（蓝图的输出「换算查询能力」）。
   *
   * <p>{@code asOf} 留空取 UTC 当天。它决定用哪一版比例，也决定"这一版生效了吗"。
   */
  public record UnitGroupConvertInput(
      @NotBlank @Size(max = 32) String fromUnitCode,
      @NotBlank @Size(max = 32) String toUnitCode,
      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 13, fraction = 6)
          BigDecimal quantity,
      @Schema(nullable = true) String asOf) {}

  /**
   * 换算查询的结果。
   *
   * <p>换不动时 {@code denied=true} + {@code denyCode} + {@code denyMessage}，**不**是 HTTP 错误：
   * 「件换算不成千克」是业务判定不是调用失败，调用方要拿到结构化结果去提示用户，
   * 而不是接一个异常。同一条 SQL 一次算完，不在应用层拼。
   */
  public record UnitGroupConvertResult(
      boolean denied,
      @Schema(nullable = true) String denyCode,
      @Schema(nullable = true) String denyMessage,
      String fromUnitCode,
      @Schema(nullable = true) String fromUnitName,
      String toUnitCode,
      @Schema(nullable = true) String toUnitName,
      BigDecimal quantity,
      @Schema(nullable = true) BigDecimal convertedQuantity,
      @Schema(nullable = true) String fromDimension,
      @Schema(nullable = true) String toDimension,
      @Schema(nullable = true) UUID groupId,
      @Schema(nullable = true) String groupCode,
      @Schema(nullable = true) String groupName,
      @Schema(nullable = true) String baseUnitCode,
      @Schema(nullable = true) BigDecimal fromQuantityPerBase,
      @Schema(nullable = true) BigDecimal toQuantityPerBase,
      String asOf,
      String note) {}

  /** 各量纲下的单位组数与成员数，用于概览显示"换算组覆盖了哪些量纲"。 */
  public record UnitGroupDimensionCount(String dimension, String dimensionLabel, long groupCount, long memberCount) {}

  /**
   * 计量单位概览：字典规模 + 组规模 + 治理缺口指标。
   *
   * <p>{@code unregisteredUnitCount} 是「有多少单位还没进任何单位组」——
   * 这些单位能配 SKU 换算，但组级换算查不到它们。0 与"没查过"含义不同，界面上要分开写。
   * {@code incompleteGroupCount} 是「有多少组存在没有当前生效换算版本的成员」，
   * 这类组在换算查询里会直接报 NO_EFFECTIVE_VERSION。
   */
  public record UnitGroupOverview(
      long unitCount,
      long enabledUnitCount,
      long groupCount,
      long enabledGroupCount,
      long memberCount,
      long conversionVersionCount,
      long openConversionCount,
      long ungroupedUnitCount,
      long incompleteGroupCount,
      List<UnitGroupDimensionCount> dimensions,
      String note) {}

  // ============================================================ 3.4.1.1 媒体库

  /**
   * 上传媒体的**元数据**部分（文件本体走 multipart 的 file 字段）。
   *
   * <p>这里刻意**没有** format / mimeType / byteSize / width / height / duration / sha256：
   * 那些是「这份文件是什么」的客观事实，由 {@code MediaRules.probe} 从字节里探测。
   * 让调用方填就等于允许它声称「一个 1KB 的 jpg 有 4000×3000」，而刊登侧会拿这个尺寸
   * 去校验平台规格（3.4.1.2 的职责），错的尺寸会静默导致刊登失败。
   */
  public record MediaUpload(
      @NotBlank @Size(max = 200) String name,
      @NotBlank String mediaType,
      @NotBlank String purpose,
      @Schema(nullable = true) @Size(max = 2000) String description,
      @Schema(nullable = true) @Size(max = 500) String altText,
      /** 标签编码列表（整体替换语义，与 BrandInput.names 同口径）。 */
      @Schema(nullable = true) @Size(max = 50) List<@NotBlank String> tagCodes,
      boolean enabled) {}

  /** 新增一个版本的元数据。文件本体同样是 multipart。 */
  public record MediaVersionUpload(@Schema(nullable = true) @Size(max = 2000) String note) {}

  /** 改素材：名称 / 用途 / 描述 / 替代文本 / 标签 / 启停（媒体类型与文件本体建后不可改）。 */
  public record MediaUpdate(
      @NotBlank @Size(max = 200) String name,
      @NotBlank String purpose,
      @Schema(nullable = true) @Size(max = 2000) String description,
      @Schema(nullable = true) @Size(max = 500) String altText,
      @Schema(nullable = true) @Size(max = 50) List<@NotBlank String> tagCodes,
      boolean enabled,
      @PositiveOrZero long version) {}

  /**
   * 上传 / 保存结果。
   *
   * <p>{@code deduplicated=true} 表示这份字节在当前租户已经有一份素材，
   * 这次没有新建行、也没有再写一份对象——返回的是既有素材。
   * 「素材库复用」的字面含义就在这里：重复上传同一张图不会让库多一条。
   */
  public record MediaSaved(
      UUID id,
      long version,
      int currentVersion,
      boolean deduplicated,
      long referenceCount,
      String integrityStatus,
      String integrityLabel) {}

  /** 一条版本记录。{@code current} 标记它是不是当前版本。 */
  public record MediaVersion(
      UUID id,
      int versionNo,
      String storageKey,
      String originalFilename,
      String fileFormat,
      String mimeType,
      java.math.BigDecimal byteSize,
      @Schema(nullable = true) java.math.BigDecimal widthPx,
      @Schema(nullable = true) java.math.BigDecimal heightPx,
      @Schema(nullable = true) java.math.BigDecimal durationSeconds,
      String contentSha256,
      String note,
      boolean current,
      String createdAt) {}

  /** 一条引用（被哪个 SPU / SKU 用着）。删除保护与「谁在用」都靠它。 */
  public record MediaReference(
      UUID id,
      UUID assetId,
      String ownerType,
      String ownerTypeLabel,
      @Schema(nullable = true) UUID spuId,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) String ownerCode,
      @Schema(nullable = true) String ownerName,
      String note,
      String createdAt) {}

  /** 素材详情：本体 + 版本 + 标签 + 引用 + 完整性历史，同一时刻的一致快照。 */
  public record MediaDetail(
      MediaRow asset,
      List<MediaVersion> versions,
      Map<String, String> tags,
      List<MediaReference> references,
      List<MediaIntegrityCheck> integrityChecks,
      /** 可删除 = 没有引用且未停用之外的额外条件；false 时给出 deletableReason。 */
      boolean deletable,
      @Schema(nullable = true) String deletableReason,
      String note) {}

  /** 一次完整性校验的记录。哈希只在真的重算过才有值。 */
  public record MediaIntegrityCheck(
      UUID id,
      UUID assetId,
      String integrityStatus,
      String integrityLabel,
      @Schema(nullable = true) java.math.BigDecimal byteSizeExpected,
      @Schema(nullable = true) java.math.BigDecimal byteSizeActual,
      @Schema(nullable = true) String sha256Expected,
      @Schema(nullable = true) String sha256Actual,
      String detail,
      String checkedAt) {}

  /**
   * 列表页的一行。
   *
   * <p>{@code referenceCount} / {@code tagCodes} / {@code integrityStatus} 在**列表查询里
   * 就一起算出来**：删除保护要让人在列表上就看见「这个素材被引用着、删不掉」，
   * 逐个点开详情才知道的话，用户会先点删除、再吃一个 409。
   */
  public record MediaRow(
      UUID id,
      String name,
      String description,
      String mediaType,
      String mediaTypeLabel,
      String purpose,
      String purposeLabel,
      String fileFormat,
      String mimeType,
      java.math.BigDecimal byteSize,
      @Schema(nullable = true) java.math.BigDecimal widthPx,
      @Schema(nullable = true) java.math.BigDecimal heightPx,
      @Schema(nullable = true) java.math.BigDecimal durationSeconds,
      String contentSha256,
      String storageKey,
      String originalFilename,
      @Schema(nullable = true) String altText,
      int currentVersion,
      int versionCount,
      long referenceCount,
      List<String> tagCodes,
      String integrityStatus,
      String integrityLabel,
      @Schema(nullable = true) String integrityDetail,
      @Schema(nullable = true) String integrityCheckedAt,
      boolean enabled,
      long version,
      String createdAt,
      String updatedAt) {}

  /** 分页结果。统计数不受筛选影响，用户才知道筛选之外还有什么。 */
  public record MediaPage(
      List<MediaRow> items,
      long total,
      int page,
      int size,
      long imageCount,
      long videoCount,
      long failingIntegrityCount,
      long unreferencedCount,
      long disabledCount,
      String note) {}

  /** 列表筛选条件。null 表示不筛；空串按不过滤处理。 */
  public record MediaQuery(
      @Schema(nullable = true) @Size(max = 200) String keyword,
      @Schema(nullable = true) @Size(max = 16) String mediaType,
      @Schema(nullable = true) @Size(max = 16) String purpose,
      @Schema(nullable = true) @Size(max = 32) String tagCode,
      @Schema(nullable = true) @Size(max = 16) String integrityStatus,
      /** true=只看还被引用着的，false=只看无引用（可清理的），null=全部。 */
      @Schema(nullable = true) Boolean referenced,
      @Schema(nullable = true) Boolean enabled,
      int page,
      int size) {}

  /** 标签写入（新建或改，id 为空即新建）。 */
  public record MediaTagInput(
      @NotBlank @Size(max = 32) String code,
      @NotBlank @Size(max = 100) String name,
      @Schema(nullable = true) @Size(max = 500) String note,
      boolean enabled,
      @PositiveOrZero long version) {}

  /**
   * 标签保存结果。
   *
   * <p>回带 {@code code}：上传/编辑素材时提交的是**编码**而不是 id，
   * 存完立刻就能拿它继续打标签，不必再查一次字典。
   */
  public record MediaTagSaved(UUID id, String code, long version, long usageCount) {}

  /** 标签字典一行，带使用数（按标签筛素材的候选来源）。 */
  public record MediaTagRow(
      UUID id,
      String code,
      String name,
      String note,
      boolean enabled,
      long usageCount,
      long version,
      String createdAt,
      String updatedAt) {}

  /** 新增引用的请求。listing 引用刻意不在这里：04 刊登交付后走 event 写入。 */
  public record MediaReferenceInput(
      @NotBlank String ownerType,
      @Schema(nullable = true) UUID spuId,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 引用保存结果（带删除保护要用的计数）。 */
  public record MediaReferenceSaved(UUID id, UUID assetId, long referenceCount, boolean deletable, @Schema(nullable = true) String deletableReason) {}

  /** 完整性校验结果：一次探测的结论 + 新记录。 */
  public record MediaIntegrityResult(
      UUID assetId,
      String integrityStatus,
      String integrityLabel,
      long referenceCount,
      boolean healthy,
      String detail,
      MediaIntegrityCheck check) {}

  /** 批量校验的汇总结果（素材库巡检）。 */
  public record MediaIntegritySweep(
      long checkedCount,
      long healthyCount,
      long missingCount,
      long sizeMismatchCount,
      long contentMismatchCount,
      List<MediaIntegrityResult> results,
      String note) {}

  /** 媒体概览：规模 + 治理缺口指标。 */
  public record MediaOverview(
      long assetCount,
      long enabledCount,
      long disabledCount,
      long imageCount,
      long videoCount,
      long byteSizeTotal,
      long referenceCount,
      long referencedAssetCount,
      long unreferencedAssetCount,
      long failingIntegrityCount,
      long missingCount,
      long tagCount,
      long versionCount,
      long storageHealthy,
      String note) {}
}
