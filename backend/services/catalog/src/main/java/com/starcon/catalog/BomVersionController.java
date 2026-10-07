package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.5.2.1 BOM 版本管理：BOM 档案、版本（草稿/已发布）、生效区间、组件清单、单层需求量、倒排查询。
 *
 * <p>路由挂在 {@code /catalog/boms} 与 {@code /catalog/bom-versions} 之下，
 * 与 {@code /catalog/bundles}、{@code /catalog/versions}（3.5.1.1）**并列**：
 * 名字都叫「版本」但语义不同 —— 那边是「几个 SKU 捆在一起卖」，
 * 这边是「这个成品要领哪些料」。合并会让「v2 指哪个版本」在 URL 上无法回答。
 * 台账里的 {@code /products/bom} 是**前端页面路径**。
 *
 * <p>权限沿用 {@code catalog.spu.read} / {@code catalog.spu.update}：BOM 挂在商品域下
 * （下游是 06 采购建议与 08 加工单领料），复用已有词表而不新造 ——
 * 新词表要改 identity 服务的迁移，属于另一个服务的写入所有权。
 *
 * <p><b>三条是下游的权威入口</b>，形状与蓝图点名的一致：
 *
 * <ul>
 *   <li>{@code GET /catalog/boms/{id}/effective?at=} —— 规则 R4 的时刻查询，06 采购建议与 08 加工单取版本走它；
 *   <li>{@code GET /catalog/bom-versions/{id}/requirement?outputQuantity=} —— 规则 R7 的<b>单层</b>需求量；
 *   <li>{@code GET /catalog/bom-versions/{id}/components} —— 08 加工单冻结领料明细前读组件清单。
 * </ul>
 */
@RestController
@RequestMapping("/catalog")
public class BomVersionController {
  private final AccessService access;
  private final BomVersionService boms;

  public BomVersionController(AccessService access, BomVersionService boms) {
    this.access = access;
    this.boms = boms;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 概览

  /**
   * BOM 版本概览：BOM 存量、版本存量、草稿/已发布、此刻生效数、开放区间数、无组件版本数。
   *
   * <p>「开放区间」是需要每天盯的那个数字；「已发布但没有组件」必须恒为 0。
   */
  @GetMapping("/boms/overview")
  public BomOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> boms.overview());
  }

  // ------------------------------------------------------------ BOM 档案

  /** BOM 列表：分页 + 关键词（编码或成品 SKU 编码/名称）+ 状态。 */
  @GetMapping("/boms")
  public BomPage boms(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth, "spu.read", a -> boms.boms(new BomQuery(keyword, status, page, size)));
  }

  @GetMapping("/boms/{id}")
  public BomDetail bom(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> boms.bom(id));
  }

  /** 建 BOM 或改 BOM（id 为空即新建）。编码与成品 SKU 建后都不可改。 */
  @PostMapping("/boms")
  public BomRow saveBom(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BomInput input) {
    return call(auth, "spu.update", a -> boms.saveBom(a, null, input));
  }

  @PutMapping("/boms/{id}")
  public BomRow updateBom(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody BomInput input) {
    return call(auth, "spu.update", a -> boms.saveBom(a, id, input));
  }

  /** 归档/启用 BOM。归档只表示「不再新建版本」，历史版本照常可读（08 加工单要读）。 */
  @PutMapping("/boms/{id}/status")
  public BomRow setBomStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam String status) {
    return call(auth, "spu.update", a -> boms.setBomStatus(a, id, status));
  }

  /** 删除 BOM。含任何版本时拒绝：草稿是待办但仍记录了规划，已发布版本更是实物领料的凭据。 */
  @DeleteMapping("/boms/{id}")
  public Map<String, Boolean> deleteBom(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          boms.deleteBom(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  /**
   * 当前生效版本（规则 R4）。
   *
   * <p>不传 {@code at} 就是查「此刻」。这是 06 采购建议与 08 加工单取版本的权威入口：
   * 生效区间是左闭右开的 [起点, 终点)，重叠一律拒绝，所以一个时刻至多命中一个版本。
   */
  @GetMapping("/boms/{id}/effective")
  public BomEffectiveView effectiveVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String at) {
    return call(auth, "spu.read", a -> boms.effectiveVersion(id, at));
  }

  // ------------------------------------------------------------ 版本

  /** BOM 版本列表（跨 BOM）：分页 + 关键词 + 状态 + 具体 BOM。 */
  @GetMapping("/bom-versions")
  public BomVersionPage versions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID bomId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        a -> boms.versions(new BomVersionQuery(keyword, status, bomId, page, size)));
  }

  /** 某个 BOM 的版本列表（BOM 详情页里用，带「此刻生效」标记）。 */
  @GetMapping("/boms/{id}/versions")
  public List<BomVersionSummary> versionsOf(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> boms.versionsOf(id));
  }

  @GetMapping("/bom-versions/{id}")
  public BomVersionDetail version(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> boms.version(id));
  }

  /** 建草稿版本。名称、版本号、生效区间都是版本自己的属性。 */
  @PostMapping("/boms/{id}/versions")
  public BomVersionRow createVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody BomVersionInput input) {
    return call(auth, "spu.update", a -> boms.createVersion(a, id, input));
  }

  /** 改草稿版本。已发布版本一律拒绝——改内容要新建版本。 */
  @PutMapping("/bom-versions/{id}")
  public BomVersionRow updateVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody BomVersionInput input) {
    return call(auth, "spu.update", a -> boms.updateVersion(a, id, input));
  }

  /**
   * 发布版本。
   *
   * <p>三道关：组件齐备、生效区间不与已发布版本重叠（重叠一律拒绝，不做裁决）、记发布留痕。
   * 返回的是「已发布」状态，不是「已受理」。
   */
  @PostMapping("/bom-versions/{id}/publish")
  public BomVersionRow publishVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.update", a -> boms.publishVersion(a, id));
  }

  /**
   * 提前停用本版（把生效终点收紧到某个仍在未来的时刻）。
   *
   * <p>这是已发布版本上<b>唯一</b>合法的写操作：它不改内容、不改历史，只让本版从那一刻起失效，
   * 由下一个版本接替。若连这一条都禁掉，第一个开放区间的版本发布后就永远无法被接替。
   */
  @PutMapping("/bom-versions/{id}/close")
  public BomVersionRow closeVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam String effectiveTo) {
    return call(auth, "spu.update", a -> boms.closeVersion(a, id, effectiveTo));
  }

  /**
   * 版本复制：把一个已有版本整份复制成新草稿（组件、单位用量、损耗率都带走）。
   *
   * <p>{@code versionNo} 必填（复制品要有自己的身份）；{@code effectiveFrom} 不传就是「此刻」——
   * 沿用源版本的起点会直接撞上规则 R2，而且运营要的是「改完这一版从现在起接替」。
   */
  @PostMapping("/bom-versions/{id}/copy")
  public BomVersionRow copyVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam String versionNo,
      @RequestParam(required = false) String effectiveFrom) {
    return call(auth, "spu.update", a -> boms.copyVersion(a, id, versionNo, effectiveFrom));
  }

  /** 删草稿版本。已发布版本由 V25 触发器拒绝（并说明为什么不能删）。 */
  @DeleteMapping("/bom-versions/{id}")
  public Map<String, Boolean> deleteVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          boms.deleteVersion(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ 组件与需求量

  /** 某个版本的组件清单。08 加工单冻结领料明细前读的就是这个。 */
  @GetMapping("/bom-versions/{id}/components")
  public List<BomComponentRow> components(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> boms.components(id));
  }

  /**
   * 整体替换组件清单。
   *
   * <p>整体替换而不是增删式：保存后清单就是提交的那份，不会剩「幽灵组件」。
   * 已发布版本一律拒绝（V25 触发器同口径兜底）。
   */
  @PutMapping("/bom-versions/{id}/components")
  public BomVersionDetail replaceComponents(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody BomComponentsInput input) {
    return call(auth, "spu.update", a -> boms.replaceComponents(a, id, input));
  }

  /**
   * 单层需求量：生产 {@code outputQuantity} 个成品需要哪些料（规则 R7）。
   *
   * <p><b>单层</b>：多级递归展开是 3.5.2.2 的职责。返回里 {@code singleLevel=true}，
   * 页面据此把「这里不含子 BOM 里的料」写在结果上方。
   *
   * <p><b>不取整</b>：结果是 6 位小数的精确需求量。取整、补安全库存、判缺口是
   * 3.5.2.2 与 06 的决定。
   */
  @GetMapping("/bom-versions/{id}/requirement")
  public BomRequirement requirement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam String outputQuantity) {
    return call(auth, "spu.read", a -> boms.requirement(id, outputQuantity));
  }

  // ------------------------------------------------------------ 倒排查询

  /**
   * 倒排：某个 SKU 被哪些 BOM 版本当组件用着。
   *
   * <p>改组件用量之前要先看这个方向（改了会影响谁），06 采购建议算「这批料要买给谁」也读它。
   * {@code onlyEffective=true} 时只列此刻正在生效的版本。
   */
  @GetMapping("/bom-components/usage")
  public BomUsagePage usage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID skuId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Boolean onlyEffective,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth, "spu.read", a -> boms.usage(new BomUsageQuery(skuId, keyword, onlyEffective, page, size)));
  }
}
