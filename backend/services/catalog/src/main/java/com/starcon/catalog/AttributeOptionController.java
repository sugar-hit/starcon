package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.2.1.2 属性选项管理：跨属性的选项总览、启用/停用、批量重排。
 *
 * <p>路由挂在 {@code /catalog/attributes} 之下，与 3.2.1.1 同源（选项本来就是字典条目的子资源）， 但**不复用 {@code
 * /{id}/options}**：那条路径只能按单个属性取选项， 回答不了本模块的「租户级选项面」。
 *
 * <p>三条路径分工明确：
 *
 * <ul>
 *   <li>{@code GET /options} —— 聚合列表（筛选 / 排序 / 分页），本模块的主视图。
 *   <li>{@code PUT /options/lifecycle} —— 启用/停用，与「改位置」分开： 停用是一个**有引用就不能撤销语义**的治理动作，不该混在「编辑选项」里。
 *   <li>{@code PUT /options/order} —— 批量重排，覆盖式提交。
 * </ul>
 *
 * <p>删除仍然走 3.2.1.1 的 {@code DELETE /{id}/options}：那处已经有引用守卫， 本模块不再开第二个删除入口——两个删除入口迟早会有一处守卫被绕过。
 *
 * <p>权限沿用属性域：读 {@code catalog.attribute.read}，写 {@code catalog.attribute.update}。
 */
@RestController
@RequestMapping("/catalog/attributes")
public class AttributeOptionController {
  private final AccessService access;
  private final AttributeOptionService service;

  public AttributeOptionController(AccessService access, AttributeOptionService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 跨属性的选项总览：租户下所有枚举属性的选项拍平成一张表。
   *
   * <p>{@code enabled} 与 {@code referenced} 用 {@link Boolean} 收参保持三态： 「不筛 / 只要启用的 /
   * 只要停用的」用基本类型表达不出来。 {@code sort} 是白名单值，未命中时服务层退回默认排序。
   */
  @GetMapping("/options")
  public AttributeOptionPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) UUID attributeId,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) Boolean referenced,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "attribute.read",
        a ->
            service.list(
                new AttributeOptionQuery(q, attributeId, enabled, referenced, sort, page, size)));
  }

  /**
   * 启用/停用单个选项。
   *
   * <p>停用永远允许；有引用的选项只能停用不能删（删除守卫在 3.2.1.1 的删除端点上）。 启用时若父属性已停用会被拒——那种状态在界面上永远选不到，留着只会误导。
   */
  @PutMapping("/options/lifecycle")
  public AttributeOptionToggled toggle(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID attributeId,
      @RequestParam String value,
      @Valid @RequestBody AttributeOptionToggleInput input) {
    return call(auth, "attribute.update", a -> service.toggle(a, attributeId, value, input));
  }

  /**
   * 批量重排：一次提交某个属性的若干选项及其新位置。
   *
   * <p>整批在一个事务里写入，中途失败不会留下半套顺序。 校验失败返回 400，并说明是哪个选项不属于该属性。
   */
  @PutMapping("/options/order")
  public AttributeOptionOrderSaved reorder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AttributeOptionOrderInput input) {
    return call(auth, "attribute.update", a -> service.reorder(a, input));
  }
}
