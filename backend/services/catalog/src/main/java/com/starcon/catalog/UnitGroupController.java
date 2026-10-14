package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.3.3.1 计量单位（字典视角）：单位组、组内换算关系与换算查询。
 *
 * <p>路由挂在 {@code /catalog/unit-groups} 之下，**与 3.1.2.4 的 {@code /catalog/unit-conversion} 并存而不合并**：
 *
 * <ul>
 *   <li>{@code /unit-conversion} 是 **SKU 视角**——「这个 SKU 的箱与件几比一」，服务 06 / 07 / 08 的单据生成与冻结快照，是
 *       3.1.2.4 已交付的写入所有权；
 *   <li>{@code /unit-groups} 是**字典视角**——「千克与克几比一」，与哪个 SKU 无关， 是本模块（3.3.3.1）新交付的部分。
 * </ul>
 *
 * 两者共用同一张 {@code unit_of_measure} 单位字典，但**不共用写入口**： 单位字典行的增删改仍然只有 {@code UnitConversionController}
 * 一处， 合并路由会让「谁是这个字段的写入所有者」这件事在代码里说不清。
 *
 * <p>台账里的 {@code /products/units} 是**前端页面路径**，与 {@code /products/brands} 同一性质。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}， 与同 L3
 * 的分类树（3.3.1.1）、类目属性要求（3.3.1.2）、品牌（3.3.2.1）一致。 单位组是这三份租户级字典里的一份，不新造权限词表—— 新词表要改 identity
 * 服务的迁移，属于另一个服务的写入所有权。
 */
@RestController
@RequestMapping("/catalog/unit-groups")
public class UnitGroupController {
  private final AccessService access;
  private final UnitGroupService service;

  public UnitGroupController(AccessService access, UnitGroupService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 概览与列表

  /** 概览：字典规模、组规模与三个治理缺口指标（未归组单位 / 不完整组 / 开放换算版本）。 */
  @GetMapping("/overview")
  public UnitGroupOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.overview());
  }

  /**
   * 单位组列表：关键词（编码 / 名称 / 基准单位 / 成员单位名）+ 量纲 + 启用状态筛选，分页。 每行自带成员数、换算版本数与「组内是否全部可换算」，不用点开详情才知道这个组行不行。
   */
  @GetMapping
  public UnitGroupPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        a -> service.list(new UnitGroupQuery(keyword, dimension, enabled, page, size)));
  }

  /**
   * 单位组详情：组本体 + 成员（带当天生效比例）+ 组内换算版本。
   *
   * <p>{@code asOf} 决定"当前生效"是按哪一天判的：补录一条 2024 年的比例时， "哪一版当时生效"与"今天哪一版生效"必须能分开看。
   */
  @GetMapping("/{id}")
  public UnitGroupDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String asOf) {
    return call(auth, "spu.read", a -> service.detail(id, asOf));
  }

  // ------------------------------------------------------------ 换算查询

  /**
   * 换算查询（蓝图的输出「换算查询能力」）：源单位 + 目标单位 + 数量 + 基准日 → 换算结果。
   *
   * <p>换不动时返回 200 + {@code denied=true} + 原因码，**不是** 4xx： 「件换不成千克」是业务判定不是调用失败，调用方要拿到结构化结果去提示用户。 真正
   * malformed 的输入（数量 ≤0、日期格式错）才走 400。
   */
  @PostMapping("/convert")
  public UnitGroupConvertResult convert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody UnitGroupConvertInput input) {
    return call(auth, "spu.read", a -> service.convert(input));
  }

  // ------------------------------------------------------------ 单位组读写

  @PostMapping
  public UnitGroupSaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody UnitGroupInput input) {
    return call(auth, "spu.update", a -> service.save(a, null, input));
  }

  /** 改单位组：名称、基准单位、备注、启用状态与成员（整体替换）。 编码与量纲建后不可改——下游按编码引用换算关系，改量纲会让已配置的换算突然不合法。 */
  @PutMapping("/{id}")
  public UnitGroupSaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody UnitGroupInput input) {
    return call(auth, "spu.update", a -> service.save(a, id, input));
  }

  /** 启用 / 停用单位组。停用挡的是「新建换算版本」与「用这个组换算」， 已配置的组内换算版本照常可读。 */
  @PutMapping("/{id}/enabled")
  public Map<String, Boolean> setEnabled(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam boolean enabled,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          service.setEnabled(a, id, enabled, version);
          return Boolean.TRUE;
        });
    return Map.of("enabled", enabled);
  }

  /** 删除单位组（成员与组内换算版本随之级联删除）。还有换算版本时拒绝，提示改为停用。 */
  @DeleteMapping("/{id}")
  public Map<String, Boolean> delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          service.delete(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ 组内换算版本

  /**
   * 新增一条组内换算版本：「1 个该单位 = 多少个组基础单位」。
   *
   * <p>生效区间与已有版本重叠时 409 并列出冲突区间；量纲不匹配、非组成员、 基础单位占行都在应用层被拒并给出可执行的原因。
   */
  @PostMapping("/{id}/conversions")
  public UnitGroupConversionSaved addConversion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody UnitGroupConversionInput input) {
    return call(auth, "spu.update", a -> service.addConversion(a, id, input));
  }

  /**
   * 删除一条组内换算版本（已闭合的历史版本）。
   *
   * <p>与 3.1.2.4 的单据快照口径相反：组内换算是字典配置，写错了就该删； 单据快照是已发生事实，DB 层禁止删除。当前仍开放生效的那一版也删不掉——
   * 删了等于静默把这个单位变成不可换算，正确做法是先闭合它的生效区间。
   */
  @DeleteMapping("/{id}/conversions/{conversionId}")
  public Map<String, Boolean> deleteConversion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID conversionId) {
    call(
        auth,
        "spu.update",
        a -> {
          service.deleteConversion(a, id, conversionId);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }
}
