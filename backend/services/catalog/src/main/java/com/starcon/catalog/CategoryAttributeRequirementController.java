package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.3.1.2 类目属性要求：按类目配置必填 / 可选 / 条件必填与取值范围，
 * 并输出录入表单 schema 与保存前校验结果。
 *
 * <p>路由挂在 {@code /catalog/categories} 之下：规则**挂在类目上**，
 * 而不是挂在 {@code /catalog/attributes} 上——那是 3.2.1.1 的属性字典入口，
 * 「这个类目要求哪些属性」与「这个租户有哪些属性」是两个问题，两个入口也避免维护方分不清。
 *
 * <p>三个只读端点对应蓝图的三个输出（规则 / 表单 schema / 校验结果），
 * 三个写端点对应规则配置（保存 / 启停 / 删除）。
 *
 * <p>权限沿用属性域：读 {@code catalog.attribute.read}，写 {@code catalog.attribute.update}。
 */
@RestController
@RequestMapping("/catalog/categories")
public class CategoryAttributeRequirementController {
  private final AccessService access;
  private final CategoryAttributeRequirementService service;
  private final CategoryAttributeRequirementForms forms;

  public CategoryAttributeRequirementController(
      AccessService access,
      CategoryAttributeRequirementService service,
      CategoryAttributeRequirementForms forms) {
    this.access = access;
    this.service = service;
    this.forms = forms;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 概览

  /** 覆盖率概览：多少类目配了要求、多少还是空白。 */
  @GetMapping("/attribute-requirements/overview")
  public CategoryRequirementsOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "attribute.read", a -> forms.overview());
  }

  // ------------------------------------------------------------ 规则读取

  /**
   * 某个类目的属性要求视图：生效规则集（含继承）+ 本节点规则 + 统计。
   *
   * <p>两个数组都给而不是给一个开关：配置页需要并排显示「本节点配了什么」与
   * 「最终生效什么」，前者用于编辑，后者用于校验自查。
   */
  @GetMapping("/attribute-requirements/{categoryId}")
  public CategoryRequirementsView requirements(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID categoryId) {
    return call(auth, "attribute.read", a -> service.view(categoryId));
  }

  /**
   * 某个类目的生效规则集（含从祖先继承的）。
   *
   * <p>与上一个端点的区别：这个直接给数组，下游（3.1.1.2 商品编辑）拿它做校验时不需要
   * 先解开视图结构。
   */
  @GetMapping("/attribute-requirements/{categoryId}/effective")
  public List<CategoryAttributeRequirement> effective(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID categoryId) {
    return call(auth, "attribute.read", a -> service.effectiveRequirements(categoryId));
  }

  // ------------------------------------------------------------ 表单 schema（蓝图的核心输出）

  /**
   * 生成录入表单 schema：字段键、标签、控件类型、是否必填、选项、约束、依赖关系。
   *
   * <p>{@code values} 以重复的查询参数传入当前已填写的值（{@code values=COLOR:RED}），
   * 用于求值条件必填——不带它时条件字段一律按「尚未触发」渲染（不亮必填星号）。
   * 带上它就能拿到「用户刚把颜色改成红色，保修期立刻变必填」的效果。
   */
  @PostMapping("/attribute-requirements/{categoryId}/entry-form-schema")
  public EntryFormSchema entryFormSchema(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID categoryId,
      @RequestParam(required = false) List<String> values,
      @RequestParam(defaultValue = "false") boolean includeDisabled) {
    return call(
        auth,
        "attribute.read",
        a -> forms.schema(categoryId, parseValues(values), includeDisabled));
  }

  // ------------------------------------------------------------ 保存前校验（蓝图的另一个输出）

  /**
   * 按类目的有效规则集校验一份提交的属性值，逐字段返回结果。
   *
   * <p>不做「不合法就 400」：录入表单需要一次拿到所有不合法字段并同时高亮，
   * 逐次报错会让用户来回提交十几轮。整体结论在返回体的 {@code valid} 上。
   */
  @PostMapping("/attribute-requirements/{categoryId}/validate")
  public AttributeValidationReport validate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID categoryId,
      @Valid @RequestBody AttributeValidationInput input) {
    return call(auth, "attribute.read", a -> forms.validate(categoryId, input));
  }

  // ------------------------------------------------------------ 规则写入

  /**
   * 配置一条类目属性要求（同一类目同一属性唯一，重复提交即更新）。
   *
   * <p>更新走 {@code version} 乐观锁；新建时忽略该字段。
   */
  @PostMapping("/attribute-requirements/{categoryId}")
  public CategoryAttributeRequirementSaved save(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID categoryId,
      @Valid @RequestBody CategoryAttributeRequirementInput input) {
    return call(auth, "attribute.update", a -> service.save(a, categoryId, input));
  }

  /**
   * 启用停用一条要求。
   *
   * <p>与删除分开：停用是「本节点不再覆盖祖先」（属性回退到祖先链上一条），
   * 删除是「本层这条覆盖彻底不存在」（条件取值与允许选项一并清除）。两者都可撤销但语义不同。
   */
  @PutMapping("/attribute-requirements/{categoryId}/{attributeId}/enabled")
  public Map<String, Boolean> setEnabled(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID categoryId,
      @PathVariable UUID attributeId,
      @RequestParam boolean enabled) {
    call(
        auth,
        "attribute.update",
        a -> {
          service.setEnabled(a, categoryId, attributeId, enabled);
          return Boolean.TRUE;
        });
    return Map.of("enabled", enabled);
  }

  /** 删除一条要求。删除后该属性回退到祖先链上的上一条规则（如果有）。 */
  @DeleteMapping("/attribute-requirements/{categoryId}/{attributeId}")
  public Map<String, Boolean> delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID categoryId,
      @PathVariable UUID attributeId) {
    call(
        auth,
        "attribute.update",
        a -> {
          service.delete(a, categoryId, attributeId);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ 工具

  /**
   * 把 {@code COLOR:RED} 这样的查询参数拆成键值对。
   *
   * <p>用查询参数而不是请求体：schema 生成是**只读渲染**，下游（商品编辑页）每次改一个字段
   * 就要重算一次，用 GET 风格的语义更贴切，也让响应能被浏览器与 CDN 缓存。
   * 缺分隔符或值为空的项直接丢弃，不让一个手误的查询参数毁掉整次渲染。
   */
  private static Map<String, String> parseValues(List<String> values) {
    if (values == null || values.isEmpty()) return Map.of();
    Map<String, String> parsed = new java.util.LinkedHashMap<>();
    for (String entry : values) {
      int split = entry == null ? -1 : entry.indexOf(':');
      if (split <= 0) continue;
      String key = entry.substring(0, split).trim();
      String value = entry.substring(split + 1).trim();
      if (!key.isEmpty() && !value.isEmpty()) parsed.put(key, value);
    }
    return parsed;
  }
}