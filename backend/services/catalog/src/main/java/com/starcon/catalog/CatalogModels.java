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

}
