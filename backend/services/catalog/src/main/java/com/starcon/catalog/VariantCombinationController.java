package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.1.3.2 组合唯一性校验：重复检测、矩阵生成前预校验、生成待建 SKU 草稿。
 *
 * <p>路由挂在 {@code /catalog/variant-combination} 之下，与 3.1.3.1 的 {@code /catalog/spec-axis}
 * 分开：规格轴管的是**结构**（有哪些轴、每个轴有哪些取值），本模块管的是**组合**（一组轴值 是否重复、能不能生成草稿）。混在一个前缀下会让"改一个取值"和"生成一批
 * SKU"变成同一个动作。
 *
 * <p>权限与规格轴同口径：读 {@code catalog.attribute.read}，写 {@code catalog.attribute.update}。 {@code POST
 * .../check} 是纯查询（不写库），所以按读授权——只读角色也要能看到冲突清单， 否则他会在点「生成」时才第一次知道自己填错了。
 */
@RestController
@RequestMapping("/catalog/variant-combination")
public class VariantCombinationController {
  private final AccessService access;
  private final VariantCombinationService service;

  public VariantCombinationController(AccessService access, VariantCombinationService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 读

  /** 单 SPU 的组合校验面板：矩阵规模、已被占用的组合数、还剩多少可生成、草稿列表。 */
  @GetMapping("/spus/{id}")
  public VariantCombinationPanel panel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "attribute.read", a -> service.panel(id));
  }

  /** 该 SPU 已存在的组合清单：草稿占用与已存在 SKU 合并，按签名排序。 */
  @GetMapping("/spus/{id}/combinations")
  public List<ExistingCombination> combinations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "attribute.read", a -> service.combinations(id));
  }

  /** 组合唯一性概览：占用数、草稿数、已有 SKU 组合的 SPU 数与治理缺口提示。 */
  @GetMapping("/overview")
  public VariantCombinationOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "attribute.read", a -> service.overview());
  }

  /** 某个 SPU 的矩阵草稿列表。 */
  @GetMapping("/spus/{id}/drafts")
  public List<SkuMatrixDraftSummary> listDrafts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "attribute.read", a -> service.drafts(id));
  }

  /** 一份草稿的明细：它占住了哪些组合、每个组合的轴值。 */
  @GetMapping("/spus/{id}/drafts/{draftId}")
  public SkuMatrixDraft draft(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID draftId) {
    return call(auth, "attribute.read", a -> service.draft(id, draftId));
  }

  // ------------------------------------------------------------ 写

  /**
   * 生成 SKU 矩阵前的预校验。**不写任何东西**：返回结构化冲突清单与合法组合， 供界面在点「生成草稿」之前就把问题显示出来。
   *
   * <p>返回的是清单不是布尔值：运营要按条改，"有重复"三个字没法干活。
   */
  @PostMapping("/spus/{id}/check")
  public CombinationCheckResult check(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody CombinationCheckInput input) {
    return call(auth, "attribute.read", a -> service.check(id, input));
  }

  /**
   * 生成**待建 SKU 草稿**：校验通过则把组合写进组合台账占住位，并留一份草稿明细。
   *
   * <p>不建 SKU 行——那是下游 3.1.2.1 的职责（还要按编码方案分配 code 与 barcode）。
   *
   * <p>任一条冲突即整份草稿不生成，并以 409 退回**完整冲突清单**（ProblemDetail 的 conflicts 属性）。
   */
  @PostMapping("/spus/{id}/drafts")
  public SkuMatrixDraft generateDraft(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody VariantDraftInput input) {
    return call(auth, "attribute.update", a -> service.generateDraft(a, id, input));
  }

  /** 作废草稿并释放它占住的组合（释放是物理删除：留着会一直占着唯一索引）。 */
  @DeleteMapping("/spus/{id}/drafts/{draftId}")
  public Map<String, Object> discardDraft(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID draftId) {
    SkuMatrixDraftDiscarded result =
        call(auth, "attribute.update", a -> service.discardDraft(a, id, draftId));
    return Map.of(
        "discarded", true,
        "draftId", result.draftId(),
        "releasedCombinationCount", result.releasedCombinationCount());
  }
}
