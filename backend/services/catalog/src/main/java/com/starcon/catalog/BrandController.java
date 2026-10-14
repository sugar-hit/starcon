package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.3.2.1 品牌管理：品牌字典、多语言名、Logo、授权状态、关联类目、授权凭证， 以及蓝图点名的刊登判定与引用候选。
 *
 * <p>路由挂在 {@code /catalog/brands} 之下，**不挂 {@code /products}**：品牌是租户级的 全局字典（所有商品共用同一批品牌），而 {@code
 * /catalog/products/{id}} 是单个商品的档案页。 台账里的 {@code /products/brands} 是**前端页面路径**，与 {@code
 * /products/categories} 同一性质。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。 品牌是 SPU 的主数据（{@code
 * spu.brand} 是品牌字段），不新造权限词表—— 新词表要改 identity 服务的迁移，属于另一个服务的写入所有权。
 */
@RestController
@RequestMapping("/catalog/brands")
public class BrandController {
  private final AccessService access;
  private final BrandService service;

  public BrandController(AccessService access, BrandService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 概览与列表

  /** 概览：规模与几个治理缺口指标，最要紧的是「现在有多少品牌不允许刊登」。 */
  @GetMapping("/overview")
  public BrandOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.overview());
  }

  /** 品牌列表：分页 + 关键词搜索（编码 / 名称 / 多语言名）+ 授权状态 / 启用状态 / 刊登可行性 / 关联类目筛选。列表行自带刊登判定，不用点开详情才知道能不能刊登。 */
  @GetMapping
  public BrandPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String authorizationStatus,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) Boolean publishable,
      @RequestParam(required = false) UUID categoryId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        a ->
            service.list(
                new BrandQuery(
                    keyword, authorizationStatus, enabled, publishable, categoryId, page, size)));
  }

  /**
   * 刊登 / 合规的品牌引用候选（蓝图的另一个输出）。
   *
   * <p>默认只给启用且可刊登的品牌：刊登表单的品牌选择器要的就是这批。 {@code includeBlocked=true} 时连不可刊登的也返回（带原因码），
   * 用于「为什么这个品牌选不了」要显示出来而不是让它凭空消失。
   */
  @GetMapping("/candidates")
  public List<BrandPublishEligibility> candidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID categoryId,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "false") boolean includeBlocked) {
    return call(auth, "spu.read", a -> service.candidates(categoryId, keyword, includeBlocked));
  }

  /**
   * 单个品牌的刊登判定。
   *
   * <p>04 刊登与 16 侵权检测消费它：{@code publishable} 决定能不能发， {@code blockCode} 让两个下游走不同的处理（等生效 vs 续约下架），
   * {@code blockMessage} 可以直接显示。
   */
  @GetMapping("/{id}/listing-eligibility")
  public BrandPublishEligibility eligibility(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> service.eligibility(id));
  }

  /**
   * 批量刊登判定：一次提交多个品牌（04 刊登批量发布时按品牌去重后调一次）。
   *
   * <p>走 POST + 请求体而不是 GET + 逗号分隔的 id 列表：id 数量由调用方决定， 拼进 URL 会撞长度上限，而且请求体里的 UUID 列表能直接做 schema
   * 校验与文档化。
   */
  @PostMapping("/listing-eligibility")
  public List<BrandPublishEligibility> eligibilityBatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BrandEligibilityBatchInput input) {
    return call(auth, "spu.read", a -> service.eligibility(input.brandIds()));
  }

  // ------------------------------------------------------------ 品牌读写

  /** 品牌详情：本体 + 多语言名 + 关联类目 + 凭证 + 刊登判定，同一时刻的一致快照。 */
  @GetMapping("/{id}")
  public BrandDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> service.detail(id));
  }

  @PostMapping
  public BrandSaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BrandInput input) {
    return call(auth, "spu.update", a -> service.save(a, null, input));
  }

  /** 改品牌：名称、Logo、授权状态、启用状态、多语言名与关联类目（整体替换）。 编码建后不可改——它是刊登的品牌字段与侵权检测的对齐锚点。 */
  @PutMapping("/{id}")
  public BrandSaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody BrandInput input) {
    return call(auth, "spu.update", a -> service.save(a, id, input));
  }

  /** 启用停用。停用会立刻让刊登判定变成 {@code BRAND_DISABLED}—— 停用与「不能刊登」在这里是同一个动作，不是两件事。 */
  @PutMapping("/{id}/enabled")
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

  /** 删除品牌：连同多语言名、凭证与类目关联一起级联删除。 品牌被商品与历史刊登引用着，所以日常应该是停用而不是删除。 */
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

  // ------------------------------------------------------------ 授权凭证

  /** 凭证列表：含当前有效性与剩余天数。 */
  @GetMapping("/{id}/authorizations")
  public List<BrandAuthorization> authorizations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> service.authorizations(id));
  }

  /** 登记一条授权凭证。一个品牌可以有多条（续签后新旧并存，各自按有效期判定）。 */
  @PostMapping("/{id}/authorizations")
  public BrandSaved createAuthorization(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody BrandAuthorizationInput input) {
    return call(auth, "spu.update", a -> service.saveAuthorization(a, id, null, input));
  }

  /** 改一条凭证：有效期、授权方、撤销状态。 */
  @PutMapping("/{id}/authorizations/{authorizationId}")
  public BrandSaved updateAuthorization(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID authorizationId,
      @Valid @RequestBody BrandAuthorizationInput input) {
    return call(auth, "spu.update", a -> service.saveAuthorization(a, id, authorizationId, input));
  }

  /**
   * 撤销 / 恢复一条凭证。
   *
   * <p>刻意没有「删除凭证」：撤销后行仍在，16 侵权检测要回溯 「曾经有过授权、后来被撤销」，删行就把这个事实抹掉了。
   */
  @PutMapping("/{id}/authorizations/{authorizationId}/enabled")
  public BrandSaved revokeAuthorization(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID authorizationId,
      @RequestParam boolean enabled) {
    return call(
        auth, "spu.update", a -> service.revokeAuthorization(a, id, authorizationId, enabled));
  }
}
