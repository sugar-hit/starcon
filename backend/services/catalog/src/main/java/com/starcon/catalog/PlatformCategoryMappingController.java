package com.starcon.catalog;

import static com.starcon.catalog.PlatformCategoryMappingModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.2.3.1 平台类目映射：内部分类 ↔ 平台类目的多对多映射（带平台 + 站点两个维度），
 * 以及平台类目必填属性映射规则。
 *
 * <p>路由挂在 {@code /catalog/category-mappings} 之下，**不挂在 {@code /categories} 之下**：
 * 分类树（3.3.1.1）与属性要求（3.3.1.2）都是「类目本身是什么」，
 * 而本模块是「这个类目在某个平台某个站点对应哪个平台类目」——后者会随平台改版频繁变动，
 * 挂在同一个前缀下会让「改类目名」和「改平台映射」两件事挤进一个页面，彼此覆盖对方正在改的内容。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 刊登映射是 SPU 主数据的下游配置，与 SPU 同域；不新造权限词表（新词表要改 identity 服务的迁移，
 * 属于另一个服务的写入所有权）。
 */
@RestController
@RequestMapping("/catalog/category-mappings")
public class PlatformCategoryMappingController {
  private final AccessService access;
  private final PlatformCategoryMappingService service;

  public PlatformCategoryMappingController(AccessService access, PlatformCategoryMappingService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 概览与候选

  /** 概览：映射规模、内部分类覆盖率的分子分母、按平台 × 站点拆开的覆盖与缺口。 */
  @GetMapping("/overview")
  public PlatformCategoryMappingOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.overview());
  }

  /**
   * 平台与站点候选。
   *
   * <p>平台来自租户已建的 PLATFORM 分类树，站点来自该平台上已在用的映射——都是真实数据。
   * catalog 不跨服务读 channel 的平台码表（AGENTS.md），所以这里**允许录入新站点码**
   * 并由服务端做形态校验，而不是给一份硬编码的常量清单。
   */
  @GetMapping("/platform-sites")
  public List<PlatformSiteOption> platformSites(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.platformSites());
  }

  /** 内部属性字典候选：供「平台的材质对应内部哪个属性」下拉。只给租户级字典条目。 */
  @GetMapping("/attribute-options")
  public List<InternalAttributeOption> attributeOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "") String q) {
    return call(auth, "spu.read", a -> service.attributeOptions(q));
  }

  // ------------------------------------------------------------ 台账

  /**
   * 映射台账（真分页：筛选、排序、LIMIT/OFFSET 全在 SQL 上）。
   *
   * <p>{@code unmappedOnly=true} 只看「平台必填但没绑内部属性」的映射——
   * 这是运营补齐刊登表单前最该看的那一屏。
   */
  @GetMapping
  public PlatformCategoryMappingPage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID internalCategoryId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String siteCode,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(defaultValue = "false") boolean unmappedOnly,
      @RequestParam(defaultValue = "updatedAt,desc") String sort,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        a ->
            service.page(
                internalCategoryId, platformCode, siteCode, q, enabled, unmappedOnly, sort, page, size));
  }

  /** 映射详情：映射行 + 它的属性规则 + 同一平台类目上的其他内部分类（多对多的另一侧）。 */
  @GetMapping("/{id}")
  public PlatformCategoryMappingDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> service.detail(id));
  }

  // ------------------------------------------------------------ 映射维护

  @PostMapping
  public PlatformCategoryMappingRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PlatformCategoryMappingInput input) {
    return call(auth, "spu.update", a -> service.save(a, null, input));
  }

  @PutMapping("/{id}")
  public PlatformCategoryMappingRow update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PlatformCategoryMappingInput input) {
    return call(auth, "spu.update", a -> service.save(a, id, input));
  }

  /** 设为该「内部分类 × 平台 × 站点」的默认目标；同一组合下只允许一个默认。 */
  @PutMapping("/{id}/default")
  public Map<String, Boolean> setDefault(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          service.setDefault(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("isDefault", true);
  }

  /** 启用停用。停用不删属性规则：平台仍然要求那些属性，删掉等于让运营重新录一遍。 */
  @PutMapping("/{id}/enabled")
  public Map<String, Boolean> setEnabled(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam boolean enabled,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          service.setEnabled(a, id, enabled, version);
          return Boolean.TRUE;
        });
    return Map.of("enabled", enabled);
  }

  @DeleteMapping("/{id}")
  public Map<String, Boolean> delete(
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

  // ------------------------------------------------------------ 平台类目属性规则

  /** 某条映射下的平台属性规则列表（含已绑定 / 未绑定的内部属性）。 */
  @GetMapping("/{id}/attributes")
  public List<PlatformAttributeMappingRow> attributes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> service.attributeRows(List.of(id)));
  }

  @PostMapping("/{id}/attributes")
  public PlatformAttributeMappingRow createAttribute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PlatformAttributeMappingInput input) {
    return call(auth, "spu.update", a -> service.saveAttributeRule(a, id, null, input));
  }

  @PutMapping("/{id}/attributes/{ruleId}")
  public PlatformAttributeMappingRow updateAttribute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID ruleId,
      @Valid @RequestBody PlatformAttributeMappingInput input) {
    return call(auth, "spu.update", a -> service.saveAttributeRule(a, id, ruleId, input));
  }

  @PutMapping("/{id}/attributes/{ruleId}/enabled")
  public Map<String, Boolean> setAttributeEnabled(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID ruleId,
      @RequestParam boolean enabled,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          service.setAttributeEnabled(a, id, ruleId, enabled, version);
          return Boolean.TRUE;
        });
    return Map.of("enabled", enabled);
  }

  @DeleteMapping("/{id}/attributes/{ruleId}")
  public Map<String, Boolean> deleteAttribute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID ruleId) {
    call(
        auth,
        "spu.update",
        a -> {
          service.deleteAttributeRule(a, id, ruleId);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }

  // ------------------------------------------------------------ 下游解析（4.2.1.1）

  /**
   * 按「内部分类 × 平台 × 站点」解析刊登目标与必填属性清单。
   *
   * <p>取默认映射；同组合没有默认且有多条启用映射时**明确报需要指定**，不静默挑第一条——
   * 静默挑的后果是同一个商品两次刊登落到不同平台类目，而两边看起来都「成功」了。
   */
  @GetMapping("/resolve")
  public PlatformCategoryResolveResult resolve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID internalCategoryId,
      @RequestParam String platformCode,
      @RequestParam String siteCode) {
    return call(
        auth, "spu.read", a -> service.resolve(internalCategoryId, platformCode, siteCode));
  }
}
