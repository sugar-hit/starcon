package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.3.1.1 分类树管理：多棵独立的分类树、多级层级、拖拽、防环、启用停用、 SPU 主分类与辅助分类、多语言分类名。
 *
 * <p>路由挂在 {@code /catalog/categories} 之下，**不挂 {@code /products}**：分类树是租户级的 全局配置（所有商品共用同一批类目），而
 * {@code /catalog/products/{id}} 是单个商品的档案页。 蓝图台账里的 {@code /products/categories} 是**前端页面路径**，与
 * {@code /products/attributes}、 {@code /products/sku/spec-axis} 同一性质——后端前缀一律不带 products。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。 分类是 SPU 的主数据，不新造权限词表（新词表要改
 * identity 服务的迁移，属于另一个服务的写入所有权）。
 */
@RestController
@RequestMapping("/catalog/categories")
public class CategoryTreeController {
  private final AccessService access;
  private final CategoryTreeService service;

  public CategoryTreeController(AccessService access, CategoryTreeService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 概览与树

  /** 概览：规模与几个治理缺口指标。 */
  @GetMapping("/overview")
  public CategoryOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.overview());
  }

  /** 树列表：页面顶部选树的那一排（内部管理分类与各平台类目映射是独立的树）。 */
  @GetMapping("/trees")
  public List<CategoryTree> trees(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.trees());
  }

  @PostMapping("/trees")
  public CategoryTree createTree(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CategoryTreeInput input) {
    return call(auth, "spu.update", a -> service.saveTree(a, null, input));
  }

  @PutMapping("/trees/{id}")
  public CategoryTree updateTree(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CategoryTreeInput input) {
    return call(auth, "spu.update", a -> service.saveTree(a, id, input));
  }

  /** 树上还有商品时拒绝删除——那种情况应该停用整棵树。 */
  @DeleteMapping("/trees/{id}")
  public Map<String, Boolean> deleteTree(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          service.deleteTree(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ 分类节点

  /**
   * 一棵树的完整结构：节点 + 路径串 + 每个节点的商品数统计。
   *
   * <p>{@code includeDisabled=false}（默认）时停用类目不进结果，它的子孙也不再返回—— 停用一个中间类目却把子类目留着，界面上会出现一条没有父亲的孤儿子树。
   */
  @GetMapping("/trees/{treeId}/nodes")
  public List<CategoryNode> nodes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID treeId,
      @RequestParam(defaultValue = "false") boolean includeDisabled) {
    return call(auth, "spu.read", a -> service.tree(treeId, includeDisabled));
  }

  /** 在指定树下新建分类；父节点为空即根节点。 */
  @PostMapping("/trees/{treeId}/nodes")
  public CategorySaved createNode(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID treeId,
      @Valid @RequestBody CategoryInput input) {
    return call(auth, "spu.update", a -> service.save(a, treeId, null, input));
  }

  /** 改分类：名称、排序位、启用状态与多语言名。编码建后不可改。 */
  @PutMapping("/nodes/{id}")
  public CategorySaved updateNode(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CategoryInput input) {
    return call(auth, "spu.update", a -> service.save(a, service.treeOf(id).id(), id, input));
  }

  /**
   * 拖拽落点：改父节点与同级位置。
   *
   * <p>与保存分成两个端点：拖拽只该改层级与顺序，不该顺带提交名称与启用状态 （那两个字段可能正被别人编辑）。成环的落点会被拒绝——服务层给可读原因，DB 触发器兜底。
   */
  @PutMapping("/nodes/{id}/move")
  public CategorySaved moveNode(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CategoryMoveInput input) {
    return call(auth, "spu.update", a -> service.move(a, id, input));
  }

  /** 启用停用。停用不级联子分类，也不摘掉已挂的商品，只是不再出现在树与选择候选里。 */
  @PutMapping("/nodes/{id}/enabled")
  public Map<String, Boolean> setEnabled(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam boolean enabled) {
    call(
        auth,
        "spu.update",
        a -> {
          service.setEnabled(a, id, enabled);
          return Boolean.TRUE;
        });
    return Map.of("enabled", enabled);
  }

  /** 有子分类或还有商品挂在上面时拒绝删除——那两种情况应该停用。 */
  @DeleteMapping("/nodes/{id}")
  public Map<String, Boolean> deleteNode(
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

  // ------------------------------------------------------------ SPU 主分类 / 辅助分类

  /** 读某个 SPU 的分类关联（主分类 + 辅助分类）。 */
  @GetMapping("/spus/{spuId}")
  public SpuCategoryAssignment spuCategories(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID spuId) {
    return call(auth, "spu.read", a -> service.spuAssignment(spuId));
  }

  /** 整体替换 SPU 的分类关联：一个主分类 + 一组辅助分类。 */
  @PutMapping("/spus/{spuId}")
  public SpuCategoryAssignment saveSpuCategories(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID spuId,
      @Valid @RequestBody SpuCategoryInput input) {
    return call(auth, "spu.update", a -> service.saveSpuCategories(a, spuId, input));
  }
}
