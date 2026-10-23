package com.starcon.channel;

import static com.starcon.channel.SalesTaxModels.*;

import java.util.List;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 16.3.2.3 销售税与 Marketplace Facilitator。
 *
 * <p>蓝图：「处理美国各州销售税与 Marketplace Facilitator 制度：应缴/代缴状态、州级阈值、免征额、
 * 按平台的代扣代缴说明与对账。输入：店铺所在州数据；平台代扣代缴报表；自营应税销售额。
 * 输出：各州税务状态 + 代扣代缴对账结果。关系：上游 = 2 店铺、12.3.1.2；下游 = 12.6 申报。
 * <b>平台代扣代缴不等于免税</b>，卖家的申报义务依然存在，只是由平台代收代缴，需要正确对账。」
 *
 * <p>🔴 端点清单：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：哪些下游未交付、上游读不读得到、判定的口径是什么。
 *   <li>{@code GET /states} —— 🔴 **各州税务状态**（真分页 + 真筛选）。
 *   <li>{@code GET /shops} —— 店铺目录（**读本服务自己的 `shop` 表**）+ 它在哪些州有销售税联系。
 *   <li>{@code GET /rate-rules} / {@code POST /rate-rules} —— 税率规则清单（真分页 + 真筛选）/ 录入一版。
 *   <li>{@code GET /rate-rules/effective} —— 某一刻生效的是哪一版（**可解释性**）。
 *   <li>{@code GET /rate-rules/{id}} / {@code PUT /rate-rules/{id}} / {@code PUT /rate-rules/{id}/archive} ——
 *       规则详情 / <b>发新版本</b>（封旧版 + 建新版，**不回填**）/ 撤下（不物理删除）。
 *   <li>{@code GET /shop-states} / {@code POST /shop-states} / {@code PUT /shop-states/{id}} ——
 *       店铺所在州（nexus）清单 / 登记 / 改。
 *   <li>{@code GET /facilitator-scopes} / {@code POST} / {@code PUT /{id}} ——
 *       **按平台的代扣代缴说明**（州 × 平台）。
 *   <li>{@code GET /facilitator-reports} / {@code POST /facilitator-reports/import} /
 *       {@code GET /facilitator-reports/{id}} —— 代扣代缴报表清单 / <b>上传 + 真解析 + 真落库</b> / 详情。
 *   <li>{@code GET /reconciliations} / {@code POST /reconciliations} —— 对账结果清单 /
 *       <b>真算一次对账</b>（应缴 vs 平台已代缴，两条线）。
 *   <li>{@code GET /obligations} —— 申报义务视图（下游 12.6 接手用）。
 * </ul>
 *
 * <p>🔴 <b>刻意没有的形状</b>：没有「申报提交」端点（12.6 申报未交付）、
 * 没有「VAT/IOSS」端点（16.3.2.1 未交付）、没有「申报日历」端点（16.3.1.2 未交付）——
 * <b>放行一个不存在的入口比 404 更糟</b>（界面会以为功能已交付）。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（读 / 录 / 改），
 * identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/sales-tax")
public class SalesTaxController {

  private final AccessService access;
  private final SalesTaxService service;

  public SalesTaxController(AccessService access, SalesTaxService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  /**
   * 需要把<b>调用方自己的 bearer</b> 转发给上游服务时用这个重载。
   *
   * <p>转发的是完整的 {@code Authorization} 头（已经带 {@code Bearer } 前缀），
   * <b>不追加前缀、不传租户号</b> —— 租户由上游按已认证成员关系解析。
   */
  private <T> T call(
      String bearer, String permission, java.util.function.BiFunction<AccessService.Access, String, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor, bearer));
  }

  // ---- 能力声明 ----

  /** 能力声明：未交付的下游逐条列出（available=false + ownerModule + 影响说明）。 */
  @GetMapping("/capabilities")
  public SalesTaxCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 各州税务状态 ----

  /** 🔴 各州税务状态（真分页 + 真筛选）。 */
  @GetMapping("/states")
  public SalesTaxStatePage states(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String stateCode,
      @RequestParam(required = false) Boolean registered,
      @RequestParam(required = false) String ruleStatus,
      @RequestParam(required = false) String asOf,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.states(stateCode, registered, ruleStatus, asOf, page, size));
  }

  // ---- 店铺目录 ----

  /** 店铺目录（读本服务自己的 shop 表）+ 它在哪些州有销售税联系。 */
  @GetMapping("/shops")
  public SalesTaxShopPage shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.shops(keyword, platformCode, page, size));
  }

  // ---- 税率规则 ----

  /** 税率规则清单（真分页 + 真筛选）。 */
  @GetMapping("/rate-rules")
  public SalesTaxRateRulePage rateRules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String stateCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.rateRules(stateCode, status, page, size));
  }

  /** 某一刻生效的是哪一版规则（**可解释性**：事后能说清「当时按哪版算的」）。 */
  @GetMapping("/rate-rules/effective")
  public SalesTaxEffectiveRule effectiveRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String stateCode,
      @RequestParam(required = false) String asOf) {
    return call(auth, "channel.operation.read", a -> service.effectiveRule(stateCode, asOf));
  }

  /** 单条规则详情。跨租户与不存在返回逐字相同的 404。 */
  @GetMapping("/rate-rules/{id}")
  public SalesTaxRateRuleView rateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.rateRule(id));
  }

  /** 录入一版税率规则（税率 / 阈值 / 免征额是**数据**，不是代码常量）。 */
  @PostMapping("/rate-rules")
  public SalesTaxRateRuleView createRateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody SalesTaxRateRuleInput input) {
    return call(auth, "channel.operation.create", a -> service.createRateRule(input));
  }

  /**
   * 改规则 = <b>发新版本</b>：封掉当前版本 + 插入新版本，<b>不回填</b>。
   *
   * <p>「改一个数字就等于说谎」的防线：历史版本的内容在 DB 层被触发器直接拒掉。
   */
  @PutMapping("/rate-rules/{id}")
  public SalesTaxRateRuleView updateRateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody SalesTaxRateRuleInput input) {
    return call(auth, "channel.operation.update", a -> service.updateRateRule(id, input));
  }

  /** 撤下一版规则（**不物理删除**：规则是审计凭据）。 */
  @PutMapping("/rate-rules/{id}/archive")
  public SalesTaxRateRuleView archiveRateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.update", a -> service.archiveRateRule(id));
  }

  // ---- 店铺所在州 ----

  /** 店铺所在州（nexus）清单（真分页 + 真筛选）。 */
  @GetMapping("/shop-states")
  public SalesTaxShopStatePage shopStates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false) String stateCode,
      @RequestParam(required = false) String nexusType,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.shopStates(shopId, stateCode, nexusType, page, size));
  }

  /** 登记一家店在一个州的销售税联系。 */
  @PostMapping("/shop-states")
  public SalesTaxShopStateView createShopState(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody SalesTaxShopStateInput input) {
    return call(auth, "channel.operation.create", a -> service.createShopState(input));
  }

  /** 改一条店铺州联系。 */
  @PutMapping("/shop-states/{id}")
  public SalesTaxShopStateView updateShopState(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody SalesTaxShopStateInput input) {
    return call(auth, "channel.operation.update", a -> service.updateShopState(id, input));
  }

  // ---- 按平台的代扣代缴说明 ----

  /** 代扣代缴说明清单（真分页 + 真筛选）。 */
  @GetMapping("/facilitator-scopes")
  public SalesTaxFacilitatorScopePage facilitatorScopes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String stateCode,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.facilitatorScopes(stateCode, platformCode, page, size));
  }

  /** 登记一条代扣代缴说明。 */
  @PostMapping("/facilitator-scopes")
  public SalesTaxFacilitatorScopeView createFacilitatorScope(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody SalesTaxFacilitatorScopeInput input) {
    return call(auth, "channel.operation.create", a -> service.createFacilitatorScope(input));
  }

  /** 改一条代扣代缴说明。 */
  @PutMapping("/facilitator-scopes/{id}")
  public SalesTaxFacilitatorScopeView updateFacilitatorScope(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody SalesTaxFacilitatorScopeInput input) {
    return call(auth, "channel.operation.update", a -> service.updateFacilitatorScope(id, input));
  }

  // ---- 代扣代缴报表 ----

  /** 代扣代缴报表清单（真分页 + 真筛选）。 */
  @GetMapping("/facilitator-reports")
  public SalesTaxFacilitatorReportPage facilitatorReports(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String periodStart,
      @RequestParam(required = false) String periodEnd,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.facilitatorReports(platformCode, null, periodStart, periodEnd, page, size));
  }

  /**
   * 🔴 上传一份平台代扣代缴报表：**真上传 + 真解析 + 真落库**。
   *
   * <p>文件级错误（CSV 缺必需列 / JSON 不是数组）⇒ 整个请求失败，一条都不导入；
   * 行级错误 ⇒ 只跳过那一行，并逐条写明「第几行 + 为什么」。
   */
  @PostMapping("/facilitator-reports/import")
  public SalesTaxFacilitatorImportResult importFacilitatorReport(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody SalesTaxFacilitatorImportInput input) {
    return call(
        auth,
        "channel.operation.create",
        a -> service.importFacilitatorReport(input));
  }

  /** 一份报表的详情（报表本体 + 逐行解析结果）。 */
  @GetMapping("/facilitator-reports/{id}")
  public SalesTaxFacilitatorReportDetail facilitatorReport(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.facilitatorReport(id));
  }

  // ---- 对账 ----

  /** 对账结果清单（真分页 + 真筛选）。 */
  @GetMapping("/reconciliations")
  public SalesTaxReconciliationPage reconciliations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String stateCode,
      @RequestParam(required = false) String periodStart,
      @RequestParam(required = false) String periodEnd,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.reconciliations(stateCode, periodStart, periodEnd, currency, page, size));
  }

  /**
   * 🔴 <b>真算一次对账</b>：自营应税销售额从 12.3.1.2 拉，平台代扣代缴从已上传的报表读，
   * 税率从生效规则读 —— <b>应缴与已代缴两条线分开</b>，差异单独给出。
   *
   * <p>上游读不到 ⇒ 整个请求失败（503），一条都不写。
   */
  @PostMapping("/reconciliations")
  public List<SalesTaxReconciliationView> computeReconciliations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody SalesTaxReconciliationRequest request) {
    return call(
        auth,
        "channel.operation.create",
        (a, bearer) -> service.computeReconciliations(request, bearer));
  }

  // ---- 申报义务 ----

  /** 申报义务视图（下游 12.6 申报接手用；本模块**不做申报**）。 */
  @GetMapping("/obligations")
  public SalesTaxObligationPage obligations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String stateCode,
      @RequestParam(required = false) String periodStart,
      @RequestParam(required = false) String periodEnd,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.obligations(stateCode, periodStart, periodEnd, currency, page, size));
  }
}
