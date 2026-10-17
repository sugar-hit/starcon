package com.starcon.trade;

import static com.starcon.trade.OrderInterceptModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 9.2.1.3 异常拦截。
 *
 * <p>命名空间 {@code /trade/order-intercept}：与 9.2.1.2 的审单规则（{@code /trade/order-review}）
 * 和 9.2.2.2 的风控校验（{@code /trade/orders/risk-validation}）分属不同命名空间 ——
 * 「判定这单有没有异常」「按规则决定放行还是转人工」「算出风险分并建议拒发」是三件事，
 * 权限与日志都该分得开。
 *
 * <p>权限码用 {@code trade.orders.read} / {@code trade.orders.update}：登记限制清单与成本基线、
 * 触发检查、落人工处置结论都直接改变一单发不发，不是读权限能带过去的动作。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>刻意不提供的能力</b>：没有任何端点能只凭拦截结果取消订单。
 * 改 {@code finalDisposition} 的唯一入口是 {@code POST /checks/{id}/disposition}，
 * 它要求一条已认证的人工成员身份 —— 蓝图加粗句「拦截不自动取消，默认转人工」在接口层就已经成立。
 * 同样<b>刻意不提供</b>批量处置与「一键放行」端点：批量处置正是加粗句点名要避免的那种动作。
 */
@RestController
@RequestMapping("/trade/order-intercept")
public class OrderInterceptController {

  private final AccessService access;
  private final OrderInterceptService intercept;

  public OrderInterceptController(AccessService access, OrderInterceptService intercept) {
    this.access = access;
    this.intercept = intercept;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ============================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：蓝图输入里的「地址校验服务；16 合规与风控信号；5 定价（低于成本线）」
   * 在集成头分别是未开发、在别的服务的独立库、没有任何数据源。
   * <b>哪几项真接通、哪几项靠租户登记、哪几项现在根本判不了，必须由接口自己说</b>，
   * 而不是让界面从空数组或 404 里猜哪一环没通。
   */
  @GetMapping("/capabilities")
  public InterceptCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "orders.read", a -> intercept.capabilities());
  }

  /** 全量概览（不受筛选与分页影响）。 */
  @GetMapping("/overview")
  public InterceptSummary overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "orders.read", a -> intercept.overview());
  }

  // ============================================================ 拦截策略

  /** 拦截策略读取。没登记过就是 404 —— 不是一份带默认值的策略。 */
  @GetMapping("/policy")
  public InterceptPolicy policy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "orders.read", a -> intercept.policy());
  }

  /**
   * 登记 / 更新拦截策略（upsert，一租户一条）。
   *
   * <p>本模块<b>不提供</b>「未配置时的默认策略」：拦截结论会决定一单发不发，
   * 「没配 = 默认全放行」与蓝图加粗句是反的。
   */
  @PutMapping("/policy")
  public InterceptPolicy savePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody InterceptPolicyInput input) {
    return call(auth, "orders.update", a -> intercept.savePolicy(input));
  }

  // ============================================================ 限制清单

  /** 限制清单列表（真分页 + 真搜索）。 */
  @GetMapping("/restrictions")
  public InterceptRestrictionPage restrictions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String restrictionKind,
      @RequestParam(required = false) RestrictionEffect restrictionEffect,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "orders.read",
        a ->
            intercept.pageRestrictions(
                new InterceptRestrictionQuery(
                    keyword, restrictionKind, restrictionEffect, page, size)));
  }

  /**
   * 登记一条限制（禁运收货国 / 平台来源合规风险）。
   *
   * <p>这是 9.2.2.1 禁运清单与 16 合规平台清单未交付期间的<b>唯一数据入口</b>：它不是占位，
   * 是真实参与判定的业务数据。上游接口到位后由同步器按同一形状写入，
   * 只需把 restrictionSource 改成 MODULE_*，表结构与判定逻辑都不动。
   */
  @PostMapping("/restrictions")
  public InterceptRestriction saveRestriction(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody InterceptRestrictionInput input) {
    return call(auth, "orders.update", a -> intercept.saveRestriction(input));
  }

  // ============================================================ 成本基线

  /** 成本基线列表（真分页 + 真搜索）。 */
  @GetMapping("/sku-costs")
  public InterceptSkuCostPage skuCosts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "orders.read",
        a -> intercept.pageSkuCosts(new InterceptSkuCostQuery(keyword, skuId, page, size)));
  }

  /**
   * 登记一条 SKU 成本基线。
   *
   * <p>卷 05 定价未交付，「低于成本线」在本服务只能靠租户维护的成本基线。
   * 采集时刻必填：换供应商涨价都会改它，历史拦截结论必须能复算。
   */
  @PostMapping("/sku-costs")
  public InterceptSkuCost saveSkuCost(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody InterceptSkuCostInput input) {
    return call(auth, "orders.update", a -> intercept.saveSkuCost(input));
  }

  // ============================================================ 拦截清单

  /** 拦截清单列表（真分页 + 真搜索），带不受分页影响的汇总。 */
  @GetMapping("/checks")
  public InterceptCheckPage checks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String recommendedDisposition,
      @RequestParam(required = false) String checkCode,
      @RequestParam(required = false, defaultValue = "false") Boolean pendingDispositionOnly,
      @RequestParam(required = false) String buyerKey,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "orders.read",
        a ->
            intercept.pageChecks(
                new InterceptCheckQuery(
                    keyword,
                    recommendedDisposition,
                    checkCode,
                    pendingDispositionOnly,
                    buyerKey,
                    page,
                    size)));
  }

  /**
   * 对一张订单跑一次拦截检查。
   *
   * <p>判定基准时刻取<b>订单的提交时刻</b>而不是当前时间：同一张单重复检查结果恒定。
   * 请求体<b>不接收</b>「要不要自动取消」之类的参数 —— 加粗句说的就是这件事不该发生。
   */
  @PostMapping("/checks")
  public InterceptCheckSaved assess(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody InterceptCheckInput input) {
    return call(auth, "orders.update", a -> intercept.assess(input, a.membershipId()));
  }

  /** 检查详情：头 + 八行命中明细 + 人工处置留痕。跨租户在 RLS 下查不到 → 404。 */
  @GetMapping("/checks/{checkId}")
  public InterceptDetail check(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID checkId) {
    return call(auth, "orders.read", a -> intercept.check(checkId));
  }

  // ============================================================ 人工处置

  /**
   * 提交人工处置 —— <b>本模块唯一能产生「取消」的入口</b>。
   *
   * <p>蓝图加粗句「拦截不自动取消，默认转人工——误拦真实订单的代价通常高于人工看一眼」
   * 的接口级落点：拦截结果无论多严重都只产出 recommendedDisposition（建议），
   * 真正的 finalDisposition（最终处置）只能由这一行产生，且需要已认证的人工身份。
   *
   * <p>刻意<b>不提供</b>批量处置端点：批量处置正是加粗句点名要避免的那种动作。
   */
  @PostMapping("/checks/{checkId}/disposition")
  public InterceptDispositionSaved disposition(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID checkId,
      @Valid @RequestBody InterceptDispositionInput input) {
    return call(
        auth, "orders.update", a -> intercept.disposition(checkId, input, a.membershipId()));
  }
}
