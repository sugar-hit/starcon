package com.starcon.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
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
}
