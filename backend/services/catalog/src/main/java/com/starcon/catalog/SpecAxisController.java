package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.1.3.1 规格轴定义：租户级规格轴字典、类目允许规则、SPU 绑定与规格选项。
 *
 * <p>路由挂在 {@code /catalog/spec-axis} 之下，与 3.2 属性体系的端点分开： 规格轴虽然值源就是属性选项，但它管的是**变体结构**（哪些 SPU
 * 有哪些轴、矩阵多大）， 与「给某个 SPU 赋属性值」是两种不同的操作，混在 {@code /products} 下会让两条读路径互相干扰。
 *
 * <p>权限沿用属性域：读 {@code catalog.attribute.read}，写 {@code catalog.attribute.update}。
 * 规格轴落在属性域内（值源就是属性选项），按属性授权而不是按 SKU 授权。
 */
@RestController
@RequestMapping("/catalog/spec-axis")
public class SpecAxisController {
  private final AccessService access;
  private final SpecAxisService service;

  public SpecAxisController(AccessService access, SpecAxisService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 规格轴字典

  /** 规格轴字典列表，含停用轴与选项数、绑定 SPU 数。 */
  @GetMapping("/axes")
  public List<SpecAxis> axes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "attribute.read", a -> service.axes());
  }

  @PostMapping("/axes")
  public SpecAxisSaved createAxis(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SpecAxisInput input) {
    return call(auth, "attribute.update", a -> service.saveAxis(a, null, input));
  }

  /** 更新轴走 version 乐观锁；轴编码建后不可改，改动直接 400 并说明原因。 */
  @PutMapping("/axes/{id}")
  public SpecAxisSaved updateAxis(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody SpecAxisInput input) {
    return call(auth, "attribute.update", a -> service.saveAxis(a, id, input));
  }

  /** 被 SPU 绑定或挂着选项时拒绝删除——那种情况应该停用。 */
  @DeleteMapping("/axes/{id}")
  public Map<String, Boolean> deleteAxis(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "attribute.update",
        a -> {
          service.deleteAxis(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ 概览与面板

  @GetMapping("/overview")
  public SpecAxisOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "attribute.read", a -> service.overview());
  }

  /** 单个 SPU 的规格轴面板：已绑定轴、各轴选项、矩阵行数与类目必绑缺口。 */
  @GetMapping("/spus/{id}")
  public SpecAxisPanel panel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "attribute.read", a -> service.panel(id));
  }

  /** 某条轴的选项列表，每项带它的承载属性（值源那一侧）。 */
  @GetMapping("/axes/{id}/options")
  public List<SpecAxisOption> options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "attribute.read", a -> service.options(id));
  }

  // ------------------------------------------------------------ 绑定

  /**
   * 把规格轴绑到 SPU 上。
   *
   * <p>{@code createAttribute=true} 时自动建立承载用的 variant_axis 属性（3.2.1.1 未开发， 规格轴要能自洽地走完链路）；类目规则不允许该轴时
   * 409 并说明原因。
   */
  @PostMapping("/spus/{id}/axes")
  public SpecAxisBindingSaved bindAxis(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody SpecAxisBindingInput input) {
    return call(auth, "attribute.update", a -> service.bindAxis(a, id, input));
  }

  @DeleteMapping("/spus/{id}/axes/{axisId}")
  public Map<String, Boolean> unbindAxis(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID axisId) {
    call(
        auth,
        "attribute.update",
        a -> {
          service.unbindAxis(a, id, axisId);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ 规格选项

  /** 登记一个规格选项。同事务内先写属性选项值源再写规格轴选项，两者共享同一个值。 同一个值重复登记按幂等处理（更新位置与启用状态）。 */
  @PostMapping("/axes/{id}/options")
  public SpecAxisOptionSaved addOption(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody SpecAxisOptionInput input) {
    return call(auth, "attribute.update", a -> service.addOption(a, id, input));
  }

  /** 删除规格选项（连带它在承载属性上的属性选项行）。已有 SKU 取用过该值时拒绝并提示改为停用。 */
  @DeleteMapping("/axes/{id}/options")
  public Map<String, Boolean> deleteOption(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam String value) {
    call(
        auth,
        "attribute.update",
        a -> {
          service.deleteOption(a, id, value);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ 类目规则

  /** 类目允许的规格轴规则；category 留空返回全部。 */
  @GetMapping("/category-rules")
  public List<CategorySpecAxisRule> categoryRules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String category) {
    return call(auth, "attribute.read", a -> service.categoryRules(category));
  }

  @PostMapping("/category-rules")
  public CategorySpecAxisSaved saveCategoryRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID specAxisId,
      @Valid @RequestBody CategorySpecAxisInput input) {
    return call(auth, "attribute.update", a -> service.saveCategoryRule(a, input, specAxisId));
  }

  /** 删规则不解绑任何 SPU：已存在的绑定本来就合法，不能被一条新规则连坐改掉。 */
  @DeleteMapping("/category-rules")
  public Map<String, Boolean> deleteCategoryRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String category,
      @RequestParam UUID specAxisId) {
    call(
        auth,
        "attribute.update",
        a -> {
          service.deleteCategoryRule(a, category, specAxisId);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }
}
