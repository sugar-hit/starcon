package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.2.2.2 SKU 属性覆写。
 *
 * <p>路径沿用既有的「独立模块 + SKU 子资源」形状（与 {@code SkuCodeController} 的
 * {@code /catalog/sku-code/skus/{id}}、{@code UnitConversionController} 的
 * {@code /catalog/unit-conversion/skus/{id}} 同形），而不是把三个写端点挂进
 * {@code CatalogController} 的 {@code /products/{id}/skus/{sid}}：
 *
 * <ul>
 *   <li>本模块有自己的写端点语义（显式清空 / 取消覆写），塞进商品控制器会让那个已经很宽的
 *       控制器继续变宽，且这几个动作的必填校验口径与商品资料保存完全不同。
 *   <li>只按 SKU 寻址，不要求调用方同时传商品 id：SKU 的归属本来就唯一，
 *       让调用方再传一遍只会造出"路径与 SKU 不匹配"这种自相矛盾的调用。
 * </ul>
 *
 * <p>三个写动作**刻意分成三个端点**，而不是一个带 state 字段的 PUT：
 *
 * <ul>
 *   <li>{@code PUT /skus/{skuId}/attribute-overrides/{attributeId}} —— 覆写：填入新值。
 *   <li>{@code PUT /.../{attributeId}/clear} —— 显式清空：有效值变空且不退回公共值。
 *   <li>{@code DELETE /.../{attributeId}} —— 删除覆写：取消覆写，落回继承 SPU 公共值。
 * </ul>
 *
 * <p>把清空塞进 PUT 的 state 字段会留下一个真实的坑：界面只要传了一个空数组，
 * 就从「取消覆写」变成「显式清空」，解析结果从公共值变成空——两个方向相反的结果，
 * 一个手滑就能触发。清空是有后果的动作，必须让调用方在 URL 上就选出来。
 *
 * <p>每个写端点都回传**写入后**的完整解析结果，界面不需要为了看结果再拉一次。
 *
 * <p>权限沿用属性域：读 {@code catalog.attribute.read}，写 {@code catalog.attribute.update}
 * （与 3.2.2.1 / 3.2.1.1 / 3.2.1.2 同域，属性是同一个域）。
 */
@RestController
@RequestMapping("/catalog/sku-attribute-override")
public class SkuAttributeOverrideController {
  private final AccessService access;
  private final SkuAttributeOverrideService service;

  public SkuAttributeOverrideController(AccessService access, SkuAttributeOverrideService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /** 该 SKU 的有效属性解析结果：逐属性的最终有效值与来源（SPU / SKU 覆写 / 已清空 / 未设置）。 */
  @GetMapping("/skus/{skuId}")
  public SkuOverrideView read(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId) {
    return call(auth, "attribute.read", a -> service.view(skuId));
  }

  /** 设置覆写值。属性值类型校验与 SPU 公共值共用同一套；枚举取值必须是启用中的选项。 */
  @PutMapping("/skus/{skuId}/attribute-overrides/{attributeId}")
  public SkuOverrideView set(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @PathVariable UUID attributeId,
      @Valid @RequestBody SkuOverrideInput input) {
    return call(auth, "attribute.update", a -> service.set(a, skuId, attributeId, input));
  }

  /**
   * 显式清空：与「未设置」是两种状态，清空后不退回 SPU 公共值。
   *
   * <p>必填属性会被拒并说明原因（类目必填与定义必填都算）。
   */
  @PutMapping("/skus/{skuId}/attribute-overrides/{attributeId}/clear")
  public SkuOverrideView clear(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @PathVariable UUID attributeId) {
    return call(auth, "attribute.update", a -> service.clear(a, skuId, attributeId));
  }

  /** 删除覆写：移除 SKU 侧那一行，落回 SPU 公共值。没有覆写行时也是幂等成功。 */
  @DeleteMapping("/skus/{skuId}/attribute-overrides/{attributeId}")
  public SkuOverrideView remove(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @PathVariable UUID attributeId) {
    return call(auth, "attribute.update", a -> service.remove(a, skuId, attributeId));
  }
}