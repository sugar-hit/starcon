package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.2.1.1 属性定义列表：租户级属性字典、选项集合与类目绑定规则。
 *
 * <p>路由挂在 {@code /catalog/attributes} 之下，**不挂 {@code /products}**：属性字典是租户级
 * 的全局配置（所有商品共用同一套字典），而 {@code /catalog/products/{id}} 是单个商品的档案页。
 * 两者混在一个前缀下会让「这是谁的属性」在 URL 上就分不清，运营很容易把商品属性页当成字典页。
 * 蓝图台账里的 {@code /products/attributes} 是**前端页面路径**，与 {@code /products/sku/spec-axis}
 * 同一性质——它的后端同样挂在 {@code /catalog/spec-axis}。
 *
 * <p>权限沿用属性域：读 {@code catalog.attribute.read}，写 {@code catalog.attribute.update}。
 */
@RestController
@RequestMapping("/catalog/attributes")
public class AttributeDictionaryController {
  private final AccessService access;
  private final AttributeDictionaryService service;

  public AttributeDictionaryController(AccessService access, AttributeDictionaryService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 字典

  /**
   * 字典列表（真实分页：返回 total 与当前页，筛选后 total 同步变化）。
   *
   * <p>「参与筛选」与「启用」用 {@link Boolean} 收参：三态（不筛 / 只要启用的 / 只要停用的）
   * 用基本类型表达不出来，硬塞成枚举字符串只会让前端更容易传错。
   */
  @GetMapping
  public AttributeDefinitionPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String dataType,
      @RequestParam(required = false) String usage,
      @RequestParam(required = false) Boolean filterable,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) String groupName,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "attribute.read",
        a ->
            service.list(
                new AttributeDefinitionQuery(
                    q, dataType, usage, filterable, enabled, groupName, page, size)));
  }

  /** 字典概览：规模、选项、类目规则与几个治理缺口指标。 */
  @GetMapping("/overview")
  public AttributeDictionaryOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "attribute.read", a -> service.overview());
  }

  @PostMapping
  public AttributeDefinitionSaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AttributeDefinitionInput input) {
    return call(auth, "attribute.update", a -> service.save(a, null, input));
  }

  /**
   * 更新字典条目，走 version 乐观锁。
   *
   * <p>编码、数据类型与是否规格轴建后不可改——它们是 SPU 级定义与规格轴按 code 对齐的锚点，
   * 改动会让已有对齐在下一次写入时突然报「不一致」。这里给 400 并说明原因，
   * 比让用户改完存不进去再猜要好。
   */
  @PutMapping("/{id}")
  public AttributeDefinitionSaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody AttributeDefinitionInput input) {
    return call(auth, "attribute.update", a -> service.save(a, id, input));
  }

  /** 被规格轴引用或已被商品用在 SPU 级定义上时拒绝删除——那种情况应该停用。 */
  @DeleteMapping("/{id}")
  public Map<String, Boolean> delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "attribute.update",
        a -> {
          service.delete(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ 选项集合

  /** 选项列表，每项带「是否已被商品取用」与取用次数——删除前必须看得见。 */
  @GetMapping("/{id}/options")
  public List<AttributeOptionRow> options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "attribute.read", a -> service.options(id));
  }

  /** 登记一个选项。同值重复提交按幂等处理（更新位置与启用状态）。 */
  @PostMapping("/{id}/options")
  public AttributeOptionSaved addOption(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody AttributeOptionInput input) {
    return call(auth, "attribute.update", a -> service.addOption(a, id, input));
  }

  /** 改一个选项的位置与启用状态；值本身不可改（已被取值引用，改值等于换了一个属性值）。 */
  @PutMapping("/{id}/options")
  public AttributeOptionSaved updateOption(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam String value,
      @Valid @RequestBody AttributeOptionInput input) {
    return call(auth, "attribute.update", a -> service.updateOption(a, id, value, input));
  }

  /** 删除选项。已被商品取用过的拒绝，提示改为停用。 */
  @DeleteMapping("/{id}/options")
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

  // ------------------------------------------------------------ 类目绑定规则

  /**
   * 类目属性规则（按类目配置必填与可选范围）。
   *
   * <p>只交付规则本身：类目树（3.3.1.1）与「按规则生成录入表单 schema」（3.3.1.2 的输出）
   * 不在本模块内，所以 {@code category} 是 {@code spu.category} 的自由文本而非外键。
   */
  @GetMapping("/category-rules")
  public List<CategoryAttributeRule> categoryRules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) UUID attributeId) {
    return call(auth, "attribute.read", a -> service.categoryRules(category, attributeId));
  }

  @PostMapping("/{id}/category-rules")
  public CategoryAttributeRuleSaved saveCategoryRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CategoryAttributeRuleInput input) {
    return call(auth, "attribute.update", a -> service.saveCategoryRule(a, id, input));
  }

  /** 删规则不解绑也不改动任何已录入的属性值。 */
  @DeleteMapping("/{id}/category-rules")
  public Map<String, Boolean> deleteCategoryRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam String category) {
    call(
        auth,
        "attribute.update",
        a -> {
          service.deleteCategoryRule(a, id, category);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }
}