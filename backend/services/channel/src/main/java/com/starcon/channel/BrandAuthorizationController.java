package com.starcon.channel;

import static com.starcon.channel.BrandAuthorizationModels.*;

import java.util.List;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 16.4.2.1 品牌授权。
 *
 * <p>蓝图：「管理品牌授权：授权方、授权范围（店铺/国家/类目/期限）、授权文件、被授权主体，
 * 校验刊登时的品牌使用是否在授权范围内。输入：授权录入；文件上传；有效期。输出：授权档案 + 校验结果。
 * 关系：上游 = 6.1.2.3 供应商资质；下游 = 3.3.2 品牌状态、4.2.3.2 预检。
 * <b>授权过期是「平时没事、上新时出事」的典型</b>，需到期提醒与刊登阻断。」
 *
 * <p>🔴 端点清单：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：哪些下游未交付、量不到归谁、结论与阻断的口径。
 *   <li>{@code GET /authorizations} —— 授权档案列表（**真分页 + 真筛选**：按品牌 / 关键词 / 到期状态）。
 *   <li>{@code POST /authorizations} —— 录入授权（授权方 / 范围 / 文件 / 被授权主体 / 期限）。
 *   <li>{@code GET /authorizations/{id}} —— 单条授权（档案 + 范围 + 文件）。
 *   <li>{@code PUT /authorizations/{id}} —— 修改授权（范围与文件整体替换；历史判定不受影响）。
 *   <li>{@code PUT /authorizations/{id}/enabled} —— 停用 / 启用（**撤销走这里，不做物理删除**）。
 *   <li>{@code GET /expiry-reminders} —— 到期提醒清单（临期 / 已过期，**真筛选 + 真分页**）。
 *   <li>{@code POST /checks} —— 跑一次授权范围校验（落一条不可改写的判定凭据）。
 *   <li>{@code POST /publish-gate} —— 🔴 刊登闸门（服务端算出来的阻断结论）。
 *   <li>{@code GET /checks} —— 校验历史（**真分页**）。
 *   <li>{@code GET /candidates/brands} / {@code /candidates/suppliers} / {@code /candidates/qualifications}
 *       —— 候选（03 品牌 / 6.1.2.3 供应商资质，**跨服务只读**）。
 *   <li>{@code GET /shops} —— 可选店铺（连同它覆盖的国家）。
 * </ul>
 *
 * <p>🔴 <b>刻意没有 DELETE</b>：合规台账不做物理删除，撤销走
 * {@code PUT /authorizations/{id}/enabled}（停用）。历史判定凭据
 * （{@code brand_authz_check}）是「当时为什么拦」的证据，更不能删。
 *
 * <p>🔴 与 catalog 3.2.1.2「品牌授权」是<b>两个模块</b>：catalog 那套是品牌档案自带的授权凭证
 * （{@code /catalog/brands/{id}/authorizations}），本模块是合规口径的授权档案
 * （{@code /channel/brand-authorizations}）。两边不共用表、不共用端点、不共用页面。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 listing 列表、
 * 4.2.1.1 刊登编辑器、4.2.3.2 合规预检同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/brand-authorizations")
public class BrandAuthorizationController {

  private final AccessService access;
  private final BrandAuthorizationService service;

  public BrandAuthorizationController(AccessService access, BrandAuthorizationService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：未交付的下游逐条列出（available=false + ownerModule + 影响说明）。 */
  @GetMapping("/capabilities")
  public BrandAuthorizationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 授权档案 ----

  /** 授权档案列表（真分页 + 真筛选）。 */
  @GetMapping("/authorizations")
  public BrandAuthorizationPage authorizations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String brandId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String state,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.authorizations(brandId, keyword, state, page, size));
  }

  /** 录入一条授权。 */
  @PostMapping("/authorizations")
  public BrandAuthorizationDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody BrandAuthorizationRegistrationInput input) {
    return call(auth, "channel.operation.create", a -> service.create(input));
  }

  /** 单条授权（档案 + 范围 + 文件）。跨租户与不存在返回逐字相同的 404。 */
  @GetMapping("/authorizations/{id}")
  public BrandAuthorizationDetail authorization(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.authorization(id));
  }

  /** 修改一条授权（范围与文件整体替换）。 */
  @PutMapping("/authorizations/{id}")
  public BrandAuthorizationDetail update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody BrandAuthorizationRegistrationInput input) {
    return call(auth, "channel.operation.update", a -> service.update(id, input));
  }

  /** 停用 / 启用一条授权（撤销走这里，不做物理删除）。 */
  @PutMapping("/authorizations/{id}/enabled")
  public BrandAuthorizationDetail setEnabled(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false, defaultValue = "false") boolean enabled) {
    return call(auth, "channel.operation.update", a -> service.setEnabled(id, enabled));
  }

  // ---- 到期提醒 ----

  /** 到期提醒清单（临期 / 已过期，真筛选 + 真分页）。 */
  @GetMapping("/expiry-reminders")
  public BrandAuthorizationExpiryPage expiryReminders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) Integer withinDays,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth, "channel.operation.read", a -> service.expiryReminders(state, withinDays, page, size));
  }

  // ---- 校验 ----

  /** 跑一次授权范围校验（落一条不可改写的判定凭据）。 */
  @PostMapping("/checks")
  public BrandAuthorizationCheckResult check(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody BrandAuthorizationCheckRequest request) {
    return call(auth, "channel.operation.create", a -> service.check(request, auth));
  }

  /**
   * 🔴 刊登闸门：服务端算出来的阻断结论。
   *
   * <p>只有 {@code IN_SCOPE} 才 {@code allowed=true}。页面上的红字只是显示，不是防线。
   */
  @PostMapping("/publish-gate")
  public BrandAuthorizationPublishGate publishGate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody BrandAuthorizationCheckRequest request) {
    return call(auth, "channel.operation.create", a -> service.publishGate(request, auth));
  }

  /** 校验历史（真分页）。 */
  @GetMapping("/checks")
  public BrandAuthorizationCheckPage checks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String brandId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.checks(brandId, page, size));
  }

  // ---- 候选（跨服务只读） ----

  /** 03 品牌候选（catalog，只读）。 */
  @GetMapping("/candidates/brands")
  public BrandAuthorizationCandidatePage brandCandidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword) {
    return call(auth, "channel.operation.read", a -> service.brandCandidates(keyword, auth));
  }

  /** 被授权主体候选（6.1.2.3 供应商资质，trade 服务，只读）。 */
  @GetMapping("/candidates/suppliers")
  public BrandAuthorizationCandidatePage supplierCandidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword) {
    return call(auth, "channel.operation.read", a -> service.supplierCandidates(keyword, auth));
  }

  /** 某个品牌下已登记的供应商资质（被授权主体该选谁）。 */
  @GetMapping("/candidates/qualifications")
  public BrandAuthorizationCandidatePage qualificationCandidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String brandId) {
    return call(auth, "channel.operation.read", a -> service.qualificationCandidates(brandId, auth));
  }

  /** 可选店铺（连同它覆盖的国家）。 */
  @GetMapping("/shops")
  public List<BrandAuthorizationShopOption> shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.shops(page, size));
  }
}
