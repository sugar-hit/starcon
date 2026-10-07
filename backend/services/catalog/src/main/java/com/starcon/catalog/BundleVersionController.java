package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.5.1.1 套装版本管理：套装档案、版本（草稿/已发布）、生效区间、组件清单。
 *
 * <p>路由挂在 {@code /catalog/bundles} 与 {@code /catalog/versions} 之下，与
 * {@code /catalog/translations}（3.4.3.1）、{@code /catalog/descriptions}（3.4.2.2）**并列**：
 * 那几个模块的多语言是「商品档案的多语言」，这里的多语言语义根本不同，合并会让两边的字段被当成同一个。
 * 台账里的 {@code /products/bundles} 是**前端页面路径**。
 *
 * <p>权限沿用 {@code catalog.spu.read} / {@code catalog.spu.update}：套装挂在商品域下
 * （下游是 3.1.2.2 的可售 SKU 与 07/09 的组件展开），复用已有词表而不新造 ——
 * 新词表要改 identity 服务的迁移，属于另一个服务的写入所有权。
 *
 * <p><b>两条读接口是下游的权威入口</b>，形状与蓝图点名的一致：
 *
 * <ul>
 *   <li>{@code GET /catalog/bundles/{id}/effective?at=} —— 规则 R4 的时刻查询，04 刊登与 09 订单取版本走它；
 *   <li>{@code GET /catalog/versions/{id}/components} —— 09 订单冻结展开结果前读组件清单。
 * </ul>
 */
@RestController
@RequestMapping("/catalog")
public class BundleVersionController {
  private final AccessService access;
  private final BundleVersionService bundles;

  public BundleVersionController(AccessService access, BundleVersionService bundles) {
    this.access = access;
    this.bundles = bundles;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 概览

  /**
   * 套装版本概览：套装存量、版本存量、草稿/已发布、此刻生效数、开放区间数、无组件草稿数。
   *
   * <p>「开放区间」是需要每天盯的那个数字：已发布且永远有效的版本如果没被接替，
   * 这个套装会永远停在一个旧版本上，而页面上不会有任何提示。
   */
  @GetMapping("/bundles/overview")
  public BundleOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> bundles.overview());
  }

  // ------------------------------------------------------------ 套装档案

  /** 套装列表：分页 + 关键词 + 状态。每行自带版本存量与此刻生效的版本号。 */
  @GetMapping("/bundles")
  public BundlePage bundles(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth, "spu.read", a -> bundles.bundles(new BundleQuery(keyword, status, page, size)));
  }

  @GetMapping("/bundles/{id}")
  public BundleDetail bundle(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> bundles.bundle(id));
  }

  /** 建套装或改套装（id 为空即新建）。套装编码建后不可改。 */
  @PostMapping("/bundles")
  public BundleRow saveBundle(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BundleInput input) {
    return call(auth, "spu.update", a -> bundles.saveBundle(a, null, input));
  }

  @PutMapping("/bundles/{id}")
  public BundleRow updateBundle(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody BundleInput input) {
    return call(auth, "spu.update", a -> bundles.saveBundle(a, id, input));
  }

  /** 归档/启用套装。归档只表示「不再新建版本」，历史版本照常可读（09 订单要读）。 */
  @PutMapping("/bundles/{id}/status")
  public BundleRow setBundleStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam String status) {
    return call(auth, "spu.update", a -> bundles.setBundleStatus(a, id, status));
  }

  /** 删除套装。含任何版本时拒绝：草稿是待办但仍记录了规划，已发布版本更是历史凭据。 */
  @DeleteMapping("/bundles/{id}")
  public Map<String, Boolean> deleteBundle(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          bundles.deleteBundle(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  /**
   * 当前生效版本（规则 R4）。
   *
   * <p>不传 {@code at} 就是查「此刻」。这是 04 刊登与 09 订单取版本的权威入口：
   * 生效区间是左闭右开的 [起点, 终点)，重叠一律拒绝，所以一个时刻至多命中一个版本。
   */
  @GetMapping("/bundles/{id}/effective")
  public BundleEffectiveView effectiveVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String at) {
    return call(auth, "spu.read", a -> bundles.effectiveVersion(id, at));
  }

  // ------------------------------------------------------------ 版本

  /** 版本列表（跨套装）：分页 + 关键词 + 状态 + 具体套装。 */
  @GetMapping("/versions")
  public BundleVersionPage versions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID bundleId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        a -> bundles.versions(new BundleVersionQuery(keyword, status, bundleId, page, size)));
  }

  /** 某个套装的版本列表（套装详情页里用，带「此刻生效」标记）。 */
  @GetMapping("/bundles/{id}/versions")
  public List<BundleVersionSummary> versionsOf(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> bundles.versionsOf(id));
  }

  @GetMapping("/versions/{id}")
  public BundleVersionDetail version(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> bundles.version(id));
  }

  /** 建草稿版本。名称、版本号、生效区间都是版本自己的属性（V23 文件头第一节）。 */
  @PostMapping("/bundles/{id}/versions")
  public BundleVersionRow createVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody BundleVersionInput input) {
    return call(auth, "spu.update", a -> bundles.createVersion(a, id, input));
  }

  /** 改草稿版本。已发布版本一律拒绝——改内容要新建版本。 */
  @PutMapping("/versions/{id}")
  public BundleVersionRow updateVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody BundleVersionInput input) {
    return call(auth, "spu.update", a -> bundles.updateVersion(a, id, input));
  }

  /**
   * 发布版本。
   *
   * <p>三道关：组件齐备、生效区间不与已发布版本重叠（重叠一律拒绝，不做裁决）、记发布留痕。
   * 返回的是「已发布」状态，不是「已受理」。
   */
  @PostMapping("/versions/{id}/publish")
  public BundleVersionRow publishVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.update", a -> bundles.publishVersion(a, id));
  }

  /**
   * 提前停用本版（把生效终点收紧到某个仍在未来的时刻）。
   *
   * <p>这是已发布版本上<b>唯一</b>合法的写操作：它不改内容、不改历史，只让本版从那一刻起失效，
   * 由下一个版本接替。若连这一条都禁掉，第一个开放区间的版本发布后就永远无法被接替。
   */
  @PutMapping("/versions/{id}/close")
  public BundleVersionRow closeVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam String effectiveTo) {
    return call(auth, "spu.update", a -> bundles.closeVersion(a, id, effectiveTo));
  }

  /** 删草稿版本。已发布版本由 V23 触发器拒绝（并说明为什么不能删）。 */
  @DeleteMapping("/versions/{id}")
  public Map<String, Boolean> deleteVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          bundles.deleteVersion(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ 组件

  /** 某个版本的组件清单。09 订单冻结展开结果前读的就是这个。 */
  @GetMapping("/versions/{id}/components")
  public List<BundleComponentRow> components(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> bundles.components(id));
  }

  /**
   * 整体替换组件清单。
   *
   * <p>整体替换而不是增删式：保存后清单就是提交的那份，不会剩「幽灵组件」。
   * 已发布版本一律拒绝（V23 触发器同口径兜底）。
   */
  @PutMapping("/versions/{id}/components")
  public BundleVersionDetail replaceComponents(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody BundleComponentsInput input) {
    return call(auth, "spu.update", a -> bundles.replaceComponents(a, id, input));
  }
}