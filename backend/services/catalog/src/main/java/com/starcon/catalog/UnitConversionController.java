package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.1.2.4 单位与换算：单位字典、SKU↔单位的换算版本、基础数量换算与单据冻结快照。
 *
 * <p>路由挂在 {@code /catalog/unit-conversion} 之下，与 3.1.2.3 的 {@code /catalog/sku-code} 分开：
 * 两者都是 SKU 档案的附属维护域，但换算还服务于 06 采购 / 07 库存 / 08 拣货这三个下游，
 * 混在 {@code /skus} 下会让「查单据当时的比例」这条读路径挤进列表路由。
 *
 * <p>权限沿用 SKU 档案：读 {@code catalog.sku.read}，写 {@code catalog.sku.update}。
 * 单位字典是租户级共用数据，但改动它会直接改变所有 SKU 的换算可用性，
 * 权限口径与 SKU 档案保持一致，不另开更宽的口子。
 */
@RestController
@RequestMapping("/catalog/unit-conversion")
public class UnitConversionController {
  private final AccessService access;
  private final UnitConversionService service;

  public UnitConversionController(AccessService access, UnitConversionService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 单位字典

  /** 单位字典列表，含停用单位。 */
  @GetMapping("/units")
  public List<UnitOfMeasure> units(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "sku.read", a -> service.units());
  }

  @PostMapping("/units")
  public UnitOfMeasureSaved createUnit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody UnitOfMeasureInput input) {
    return call(auth, "sku.update", a -> service.saveUnit(a, null, input));
  }

  /** 更新单位走 version 乐观锁；单位编码与量纲建后不可改，改动直接 400 并说明原因。 */
  @PutMapping("/units/{id}")
  public UnitOfMeasureSaved updateUnit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody UnitOfMeasureInput input) {
    return call(auth, "sku.update", a -> service.saveUnit(a, id, input));
  }

  @DeleteMapping("/units/{id}")
  public Map<String, Boolean> deleteUnit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "sku.update",
        a -> {
          service.deleteUnit(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ 概览与面板

  @GetMapping("/overview")
  public UnitOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "sku.read", a -> service.overview());
  }

  /** 单个 SKU 的基础单位 + 换算版本分组 + 该 SKU 的快照留痕。 */
  @GetMapping("/skus/{id}")
  public UnitPanel panel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "sku.read", a -> service.panel(id));
  }

  // ------------------------------------------------------------ 换算版本

  /**
   * 新增一条换算版本。生效区间与已有版本重叠时 409 并列出冲突区间。
   * 基础单位不占换算行，用基础单位建行直接 400。
   */
  @PostMapping("/skus/{id}/conversions")
  public UnitConversionSaved addConversion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody UnitConversionInput input) {
    return call(auth, "sku.update", a -> service.addConversion(a, id, input));
  }

  // ------------------------------------------------------------ 换算计算与快照

  /** 基础数量换算：SKU + 数量 + 单位 + 生效日期 → 基础数量。纯计算，不写库。 */
  @PostMapping("/convert")
  public UnitConvertResult convert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody UnitConvertInput input) {
    return call(auth, "sku.read", a -> service.convert(input));
  }

  /**
   * 冻结单据的换算快照。同一 (单据类型, 单据号, 行号) 幂等：重跑生成返回原行，不重复计数。
   * 任何一行换算不到就整单拒绝。
   */
  @PostMapping("/snapshots")
  public UnitSnapshot freeze(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody UnitSnapshotInput input) {
    return call(auth, "sku.update", a -> service.freeze(a, input));
  }

  /** 读回一张单据冻结时的比例与换算结果（documentType 留空不限类型）。 */
  @GetMapping("/snapshots")
  public UnitSnapshot snapshot(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String documentRef,
      @RequestParam(required = false) String documentType) {
    return call(auth, "sku.read", a -> service.snapshot(documentRef, documentType));
  }
}
