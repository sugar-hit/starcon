package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.1.2.2 SKU 详情：分区档案读取与编辑入口。
 *
 * <p>读权限是 {@code catalog.sku.read}——3.1.2.1 已经把这页定为仓储、采购、财务共用的事实入口， 详情页沿用同一权限，不另开一个更宽的口子。写权限是
 * {@code catalog.sku.update}。
 *
 * <p>路由挂在 {@code /catalog/skus/{id}}，与 3.1.2.1 的 {@code /catalog/skus} 同一族， 但**刻意**不与 {@code
 * /catalog/skus/export}、{@code /catalog/skus/capabilities} 冲突： Spring 按字面量优先匹配固定段，详情通配不会吃掉这两个静态路径。
 */
@RestController
@RequestMapping("/catalog")
public class SkuDetailController {
  private final AccessService access;
  private final SkuDetailService service;

  public SkuDetailController(AccessService access, SkuDetailService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 读取分区详情。
   *
   * <p>取不到返回 404「记录不存在或没有访问权限」——RLS 已经过滤过跨租户的行， 再用 403 去区分"存在但没权限"等于给探测的人一个存在性预言机。
   */
  @GetMapping("/skus/{id}")
  public SkuDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "sku.read", a -> service.detail(a, id));
  }

  /**
   * 编辑入口：名称 / 条码 / 基础单位 / 包装尺寸重量。
   *
   * <p>{@code version} 是乐观锁凭据，不符直接 409。属性覆写（3.2.2.2）、编码规则与外部条码
   * （3.1.2.3）、单位换算版本维护（3.1.2.4）都不在这里代劳，详情页只编辑本模块真正拥有的字段。
   */
  @PutMapping("/skus/{id}")
  public SkuDetailSaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody SkuDetailInput input) {
    return call(auth, "sku.update", a -> service.update(a, id, input));
  }
}
