package com.starcon.finance;

import static com.starcon.finance.TaxRateModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 12.6.1.1 税率配置的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/tax-rates}</b>：与同服务的 12.4.1.1
 * （{@code /finance/cost-valuation}）、12.5.2.1（{@code /finance/vouchers}）刻意不共用前缀 ——
 * 首 token 也刻意不同（{@code tax-rates} ≠ {@code cost-valuation} ≠ {@code vouchers}），
 * 免得「税率配置」被别的模块的前缀吃掉。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.tax_rate.read / .create / .update}：
 * 改税率不是读权限能带过去的动作。
 */
@RestController
@RequestMapping("/finance/tax-rates")
public class TaxRateController {

  private final AccessService access;
  private final TaxRateService taxRates;

  public TaxRateController(AccessService access, TaxRateService taxRates) {
    this.access = access;
    this.taxRates = taxRates;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.tax_rate." + permission);
    return access.run(actor, "finance.tax_rate." + permission, () -> work.apply(actor));
  }

  /** 需要把调用方自己的会话转发给上游（channel）时用。 */
  private <T> T callWithBearer(
      String bearer, String permission, TwoArgs<AccessService.Access, String, T> work) {
    var actor = access.require(bearer, "finance.tax_rate." + permission);
    return access.run(
        actor, "finance.tax_rate." + permission, () -> work.apply(actor, bearer));
  }

  /** 两个入参的函数（避免在这里引 java.util.function.BiFunction 的类型噪声）。 */
  @FunctionalInterface
  interface TwoArgs<A, B, T> {
    T apply(A first, B second);
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些上游真接通、职责怎么切、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceTaxRateCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> taxRates.capabilities());
  }

  // ================================================================ 选项

  /** 筛选项 + 四个税种选项（系统提供选项，不替用户决定税率）。 */
  @GetMapping("/options")
  public FinanceTaxRateOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> taxRates.options());
  }

  /**
   * 辖区选项：实时读 channel 的受控辖区码表，读不到一律 503（如实说读不到）。
   *
   * <p>🔴 不可用的辖区（美国辖区归 channel 16.3.2.3）**仍然列出来**并写明原因。
   */
  @GetMapping("/jurisdictions")
  public FinanceTaxJurisdictionOptions jurisdictions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return callWithBearer(auth, "read", (actor, bearer) -> taxRates.jurisdictionOptions(bearer));
  }

  /** 店铺站点选项：实时读 channel 2.3.1.1 店铺目录，读不到一律 503。 */
  @GetMapping("/shops")
  public FinanceTaxShopOptions shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return callWithBearer(auth, "read", (actor, bearer) -> taxRates.shopOptions(bearer));
  }

  // ================================================================ 税率记录

  /** 税率记录列表（真分页 + 真筛选）。 */
  @GetMapping("/rates")
  public FinanceTaxRatePage rates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String taxType,
      @RequestParam(required = false) String jurisdictionCode,
      @RequestParam(required = false) String taxCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            taxRates.rates(taxType, jurisdictionCode, taxCode, status, keyword, page, size));
  }

  /**
   * 登记 / 换税率（同链已有生效版本 ⇒ **追加新版本**）。
   *
   * <p>⚠️ 换税率 = <b>追加版本</b>，旧版本被收口并留作版本对比；
   * 新版本生效前，历史订单仍按旧税率解释 —— 历史订单不会被追溯改写。
   */
  @PostMapping("/rates")
  public FinanceTaxRateRow upsertRate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceTaxRateInput input) {
    return callWithBearer(
        auth, "update", (actor, bearer) -> taxRates.upsertRate(input, actor, bearer));
  }

  /** 版本历史（新到旧）。 */
  @GetMapping("/rate-versions")
  public List<FinanceTaxRateRow> versions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String taxType,
      @RequestParam String jurisdictionCode,
      @RequestParam String taxCode) {
    return call(
        auth, "read", actor -> taxRates.versions(taxType, jurisdictionCode, taxCode));
  }

  /**
   * 版本对比（换税率前 / 后）。
   *
   * <p>⚠️ 对比的是<b>税率配置本身</b>，<b>不是</b>税额 —— 税额属 9 订单与 12.6.1.2 申报。
   */
  @GetMapping("/rate-compare")
  public FinanceTaxRateComparison compare(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String taxType,
      @RequestParam String jurisdictionCode,
      @RequestParam String taxCode,
      @RequestParam int fromVersion,
      @RequestParam int toVersion) {
    return call(
        auth,
        "read",
        actor ->
            taxRates.compare(taxType, jurisdictionCode, taxCode, fromVersion, toVersion));
  }

  /**
   * 按生效时间取税率：给定（订单日期 + 店铺站点 + 辖区）⇒ 唯一命中一条生效税率。
   *
   * <p>🔴 <b>命中不到一律报错</b>（404），不退回最新一条、不按 0% 处理。
   */
  @GetMapping("/resolve")
  public FinanceTaxResolution resolve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String taxType,
      @RequestParam(required = false) OffsetDateTime asOf,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String jurisdictionCode,
      @RequestParam(required = false) String taxCode) {
    return call(
        auth,
        "read",
        actor -> taxRates.resolve(taxType, asOf, shopId, jurisdictionCode, taxCode));
  }

  // ================================================================ 店铺绑定

  /** 店铺绑定列表（真分页 + 真筛选）。 */
  @GetMapping("/bindings")
  public FinanceTaxBindingPage bindings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String taxType,
      @RequestParam(required = false) String jurisdictionCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            taxRates.bindings(
                shopId, taxType, jurisdictionCode, status, keyword, page, size));
  }

  /**
   * 绑定 / 换绑（同链已有生效绑定 ⇒ **追加新版本**）。
   *
   * <p>⚠️ 换绑 = <b>追加版本</b>，旧绑定被收口 —— 换绑同样不追溯。
   */
  @PostMapping("/bindings")
  public FinanceTaxBindingRow upsertBinding(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceTaxBindingInput input) {
    return callWithBearer(
        auth, "update", (actor, bearer) -> taxRates.upsertBinding(input, actor, bearer));
  }

  /** 绑定版本历史（新到旧）。 */
  @GetMapping("/binding-versions")
  public List<FinanceTaxBindingRow> bindingVersions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID shopId,
      @RequestParam String taxType,
      @RequestParam String jurisdictionCode) {
    return call(
        auth, "read", actor -> taxRates.bindingVersions(shopId, taxType, jurisdictionCode));
  }

  // ================================================================ 州销售税（channel 只读引用）

  /**
   * 美国州级销售税规则（🔴 **归 channel 16.3.2.3**，本模块只读引用）。
   *
   * <p>channel 读不到一律 503 并写明「读不到」—— 绝不返回空集合冒充「没有州销售税」。
   */
  @GetMapping("/state-sales-tax")
  public FinanceTaxStateRateRules stateSalesTax(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String stateCode,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return callWithBearer(
        auth,
        "read",
        (actor, bearer) -> taxRates.stateSalesTax(bearer, stateCode, status, page, size));
  }
}
