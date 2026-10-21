package com.starcon.trade;

import static com.starcon.trade.ReplenishmentSuggestionModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 6.2.1.2 补货建议。
 *
 * <p>命名空间 {@code /trade/replenishment-suggestions}：后端命名空间**按服务走**（与 6.2.1.1 的
 * {@code /trade/stockout-risk}、9.3.2.2 的 {@code /trade/fulfillment/routing} 同一纪律）。
 * 前端路由是 {@code /purchase/demand/replenishment} —— 那是**前端**路由，与后端命名空间是两件事。
 *
 * <p>权限码沿用 {@code trade.<page>.<action>}：{@code trade.replenishment.read} /
 * {@code trade.replenishment.update}，与 trade 服务既有的 {@code trade.stockout.*}、
 * {@code trade.processing.*} 同一套命名。本车道**不改 identity 的权限真相源**
 * （那是另一个服务的迁移版本号），而是沿用 trade 服务既有的权限码体系。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/replenishment-suggestions")
public class ReplenishmentSuggestionController {

  private final AccessService access;
  private final ReplenishmentSuggestionService service;

  public ReplenishmentSuggestionController(
      AccessService access, ReplenishmentSuggestionService service) {
    this.access = access;
    this.service = service;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：输入分属四个服务 / 模块（日均销量在 6.2.1.1、可用量与在途在 warehouse、
   * MOQ / 箱规 / 交期 / 价格在 catalog、头程时效在 10）。哪一个接通了必须由接口自己报出。
   */
  @GetMapping("/capabilities")
  public ReplenishmentCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "replenishment.read", a -> service.capabilities());
  }

  // ================================================================ 覆盖天数目标配置

  /** 覆盖天数目标配置列表（真分页 + 真筛选）。 */
  @GetMapping("/coverage-policies")
  public ReplenishmentCoveragePolicyPage coveragePolicies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scope,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String categoryCode,
      @RequestParam(required = false, defaultValue = "false") Boolean effectiveOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "replenishment.read",
        a ->
            service.pageCoveragePolicies(
                scope, skuId, categoryCode, Boolean.TRUE.equals(effectiveOnly), page, size));
  }

  /**
   * 登记一条覆盖天数目标（版本化：同一作用范围已有未收口的配置时，新配置把旧的那条**收口**）。
   *
   * <p>覆盖不是覆盖 —— 改配置会让「当时按哪个覆盖天数算的」永远查不回来。
   */
  @PostMapping("/coverage-policies")
  public ReplenishmentCoveragePolicyRow upsertCoveragePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ReplenishmentCoveragePolicyInput input) {
    return call(
        auth, "replenishment.update", a -> service.upsertCoveragePolicy(input, a.userId()));
  }

  // ================================================================ 采购起订额 / 生产周期配置

  /** 采购起订额 / 生产周期配置列表（真分页 + 真筛选）。 */
  @GetMapping("/order-policies")
  public ReplenishmentOrderPolicyPage orderPolicies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID supplierId,
      @RequestParam(required = false, defaultValue = "false") Boolean effectiveOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "replenishment.read",
        a -> service.pageOrderPolicies(supplierId, Boolean.TRUE.equals(effectiveOnly), page, size));
  }

  /** 登记一条采购起订额 / 生产周期配置（版本化：新配置收口旧配置）。 */
  @PostMapping("/order-policies")
  public ReplenishmentOrderPolicyRow upsertOrderPolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ReplenishmentOrderPolicyInput input) {
    return call(
        auth, "replenishment.update", a -> service.upsertOrderPolicy(input, a.userId()));
  }

  // ================================================================ 计算批次

  /**
   * 计算一次补货建议（落成一份批次）。
   *
   * <p>这是一次<b>读上游 + 写本地</b>的动作：可用量与在途来自 warehouse 的授权只读接口，
   * 报价与箱规来自 catalog 的授权只读接口，日均销量来自 6.2.1.1 的销售速度登记。
   * 跨服务一律走端口，禁跨库 SQL / FK / 视图。
   */
  @PostMapping("/batches")
  public ReplenishmentComputeResult compute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ReplenishmentComputeInput input) {
    return call(
        auth, "replenishment.update", a -> service.compute(input, a.userId(), auth));
  }

  /** 批次列表（真分页）。 */
  @GetMapping("/batches")
  public ReplenishmentBatchPage batches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "replenishment.read", a -> service.pageBatches(page, size));
  }

  // ================================================================ 建议清单

  /**
   * 补货建议清单（真分页 + 真筛选 + 按建议类别排序）。
   *
   * <p>不传 {@code batchId} 时取最近一次批次 —— 界面默认看到的是「最新的那份结论」。
   */
  @GetMapping("/suggestions")
  public ReplenishmentSuggestionPage suggestions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID batchId,
      @RequestParam(required = false) String advice,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "false") Boolean onlyActionable,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "replenishment.read",
        a ->
            service.pageSuggestions(
                new ReplenishmentSuggestionQuery(
                    batchId, advice, status, skuCode, keyword, onlyActionable, page, size)));
  }

  /** 建议详情：结论 + 计算过程（逐项拆解）+ 处置留痕。 */
  @GetMapping("/suggestions/{id}")
  public ReplenishmentSuggestionDetail suggestion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "replenishment.read", a -> service.suggestionDetail(id));
  }

  /**
   * 处置一条建议：接受 / 调整 / 忽略。
   *
   * <p>三种处置真的落库（处置人、处置时间、处置说明），并追加一条不可改写的处置留痕。
   * 只有「建议补货」能处置 —— 给「答不出来」的行点忽略，会把一个数据缺口伪装成一次业务决定。
   */
  @PostMapping("/suggestions/{id}/decision")
  public ReplenishmentSuggestionRow decide(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ReplenishmentDecisionInput input) {
    return call(
        auth, "replenishment.update", a -> service.decide(id, input, a.userId()));
  }
}
