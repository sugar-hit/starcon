package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.5.1.2 组件维护：套装组件的逐行增删改、跨版本组件清单、循环检测与结构化校验结果。
 *
 * <p>路由挂在 {@code /catalog/components} 与 {@code /catalog/bundle-skus} 之下，
 * 与 3.5.1.1 的 {@code /catalog/bundles}、{@code /catalog/versions} <b>并列</b>：
 * 3.5.1.1 管的是「套装与版本」这两层档案，本模块管的是「组件」这一层。
 * 把组件端点挂在 {@code /versions/{id}/components} 下会和 3.5.1.1 的整体替换撞名，
 * 那是两条不同的语义（整体替换 vs 逐行改），必须能被 URL 区分开。
 *
 * <p><b>与 3.5.1.1 端点的分工</b>：
 *
 * <ul>
 *   <li>{@code PUT /catalog/versions/{id}/components}（3.5.1.1）：整体替换清单，本模块不动它；
 *   <li>{@code POST /catalog/versions/{id}/components}（本模块）：加<b>一</b>行；
 *   <li>{@code PUT|DELETE /catalog/components/{id}}（本模块）：改/删<b>一</b>行，带行级乐观锁。
 * </ul>
 *
 * <p>权限沿用 {@code catalog.spu.read} / {@code catalog.spu.update}：组件是套装版本的一部分，
 * 同一份商品数据不该有两套权限。
 */
@RestController
@RequestMapping("/catalog")
public class BundleComponentController {
  private final AccessService access;
  private final BundleComponentService components;

  public BundleComponentController(AccessService access, BundleComponentService components) {
    this.access = access;
    this.components = components;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 概览

  /**
   * 组件工作台概览。
   *
   * <p>「自引用」与「数量非法」正常恒为 0：它们是纪律的体检指标，
   * 摆出来是为了让「一条都没坏」可核对，而不是靠「约束建了就一定对」。
   */
  @GetMapping("/components/overview")
  public BundleComponentOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> components.overview());
  }

  // ------------------------------------------------------------ 组件清单

  /**
   * 跨版本组件清单：分页 + 关键词（SKU 编码/名称、套装编码、版本号、角色）+ 版本/套装/是否嵌套。
   *
   * <p>这是 3.5.1.1 没有的读形状：那边只能「按版本读」，这边是「按组件读」——
   * 运营问「这个 SKU 被哪些套装用着」时走的是反查方向，07 预占组件与 09 展开也读这个方向。
   */
  @GetMapping("/components")
  public BundleComponentPage components(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) UUID versionId,
      @RequestParam(required = false) UUID bundleId,
      @RequestParam(required = false) String nested,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        a -> components.components(new BundleComponentQuery(keyword, versionId, bundleId, nested, page, size)));
  }

  @GetMapping("/components/{id}")
  public BundleComponentItem component(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> components.component(id));
  }

  /**
   * 校验结果：把一个版本上的组件问题一次列全。
   *
   * <p>与保存时报错分开：保存被拒只给一条原因，而这个接口让运营在动手之前
   * 就看到「一个环 + 三个数量非法 + 一个 SKU 不存在」，改一次改完，
   * 不用反复提交试错。
   */
  @GetMapping("/versions/{id}/component-check")
  public BundleComponentCheck check(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> components.check(id));
  }

  // ------------------------------------------------------------ 逐行增删改

  /**
   * 给草稿版本加一个组件。
   *
   * <p>已发布版本一律拒绝（蓝图：「已发布版本的组件不可改，只能新建版本」），
   * 报错说清为什么，并指出唯一正确的动作是新建版本。
   */
  @PostMapping("/versions/{id}/components")
  public BundleComponentItem addComponent(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody BundleComponentInput input) {
    return call(auth, "spu.update", a -> components.addComponent(a, id, input));
  }

  /**
   * 改一行组件的数量 / 角色 / 备注（行级乐观锁）。
   *
   * <p>SKU 不可改：组件行的身份是「这个版本里的这个 SKU」，换 SKU 走「删一行 + 加一行」。
   */
  @PutMapping("/components/{id}")
  public BundleComponentItem editComponent(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody BundleComponentEditInput input) {
    return call(auth, "spu.update", a -> components.editComponent(a, id, input));
  }

  /** 删一行组件。已发布版本的组件由 V23 触发器拒绝（服务层先给人话原因）。 */
  @DeleteMapping("/components/{id}")
  public Map<String, Boolean> deleteComponent(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long rowVersion) {
    call(
        auth,
        "spu.update",
        a -> {
          components.deleteComponent(a, id, rowVersion);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ SKU ↔ 套装指向

  /**
   * 指向列表：「哪些 SKU 代表哪些套装」。
   *
   * <p>嵌套套装在数据上就靠这一层表达（V24 文件头第二节）：没有它，
   * 「套装 A 含套装 B」无法表达，循环也就无从发生。
   */
  @GetMapping("/bundle-skus")
  public BundleSkuPage links(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "spu.read", a -> components.links(new BundleSkuQuery(keyword, page, size)));
  }

  /**
   * 新建指向，或改一个 SKU 代表哪个套装。
   *
   * <p>改指向会一次性改掉所有把该 SKU 当组件的套装的出边，所以同样要过循环检测 ——
   * 否则「先建好 A 含 B，再把 B 的可售 SKU 改指回 A」是一条绕过组件侧检测的成环路径。
   */
  @PostMapping("/bundle-skus")
  public BundleSkuRow createLink(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BundleSkuInput input) {
    return call(auth, "spu.update", a -> components.saveLink(a, null, input));
  }

  @PutMapping("/bundle-skus/{id}")
  public BundleSkuRow updateLink(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody BundleSkuInput input) {
    return call(auth, "spu.update", a -> components.saveLink(a, id, input));
  }

  /**
   * 解开指向。
   *
   * <p>已发布版本里的组件引用过这个 SKU 时拒绝：解开后那些冻结的组件会从
   * 「套装 B 的可售 SKU」静默降级成普通 SKU，09 订单冻结的展开结果就变了。
   */
  @DeleteMapping("/bundle-skus/{id}")
  public Map<String, Boolean> deleteLink(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long rowVersion) {
    call(
        auth,
        "spu.update",
        a -> {
          components.deleteLink(a, id, rowVersion);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }
}
