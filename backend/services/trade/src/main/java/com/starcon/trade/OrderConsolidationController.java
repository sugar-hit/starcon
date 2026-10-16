package com.starcon.trade;

import static com.starcon.trade.OrderConsolidationModels.*;

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
 * 9.3.1.2 合单。
 *
 * <p>命名空间 {@code /trade/fulfillment/consolidation}：与 9.3.2.2 的 {@code /trade/fulfillment/routing}、
 * 9.3.2.1 的 {@code /trade/fulfillment/allocation} 并列 —— 三件事（合、合到什么仓、走什么履约路径）
 * 权限与日志都分得开。
 *
 * <p>权限码用 {@code trade.fulfillment.read} / {@code trade.fulfillment.update}：落合并与登记物流号
 * 直接改变包裹形态与平台可见的履约状态，不是读权限能带过去的动作。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/fulfillment/consolidation")
public class OrderConsolidationController {

  private final AccessService access;
  private final OrderConsolidationService consolidation;

  public OrderConsolidationController(AccessService access, OrderConsolidationService consolidation) {
    this.access = access;
    this.consolidation = consolidation;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ============================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：合单的上游是 9.1.1.1（已交付），但 D5 平台单同步、
   * 8.3.3.2 打包、9.3.3.1 履约请求、9.4.2.1 回传**都还没交付**，
   * 而「shop_code 能不能真正区分店铺」直接决定「跨店铺同买家」这个职责做到了什么程度 ——
   * 这些必须由接口自己说，而不是让界面从空数组里猜。
   */
  @GetMapping("/capabilities")
  public OrderConsolidationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> consolidation.capabilities());
  }

  // ============================================================ 合单规则

  /** 合单规则读取。没登记过就是 404 —— 不是一份带默认值的规则。 */
  @GetMapping("/rule")
  public ConsolidationRule rule(@RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> consolidation.rule());
  }

  /** 登记 / 更新合单规则（upsert，一租户一条）。 */
  @PutMapping("/rule")
  public ConsolidationRule saveRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ConsolidationRuleInput input) {
    return call(auth, "fulfillment.update", a -> consolidation.saveRule(input, a.membershipId()));
  }

  // ============================================================ 候选检索

  /**
   * 候选订单检索（只读，不落库）：同一买家 + 同一收货地址 + 可合单状态。
   *
   * <p>返回候选**与被排除项**，后者带原因码 —— 「为什么只出来两张」不需要猜。
   */
  @PostMapping("/candidates")
  public CandidateSet candidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CandidateQuery query) {
    return call(auth, "fulfillment.read", a -> consolidation.candidates(query));
  }

  // ============================================================ 合并单

  /** 合并单列表（真分页；三组汇总额不受分页影响）。 */
  @GetMapping("/consolidations")
  public ConsolidationPage consolidations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String buyerName,
      @RequestParam(required = false, defaultValue = "false") Boolean crossShopOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            consolidation.pageConsolidations(
                new ConsolidationQuery(keyword, status, buyerName, crossShopOnly, page, size)));
  }

  /**
   * 落一次合并（头 + 成员行 + 逐单回传任务）。
   *
   * <p>「同一买家 + 同一收货地址」在这一刻对**真实订单**重新判定一遍，
   * 不相信调用方传来的指纹；跨店铺是否放行按本租户规则。
   */
  @PostMapping("/consolidations")
  public ConsolidationSaved consolidate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ConsolidationInput input) {
    return call(auth, "fulfillment.update", a -> consolidation.consolidate(input, a.membershipId()));
  }

  /** 合并单详情：头 + 成员 + 回传任务 + 事件流。跨租户在 RLS 下查不到 → 404。 */
  @GetMapping("/consolidations/{consolidationId}")
  public ConsolidationDetail consolidation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID consolidationId) {
    return call(auth, "fulfillment.read", a -> consolidation.consolidation(consolidationId));
  }

  /**
   * 推进合并单状态（PLANNED → CONFIRMED → DISPATCHED，或 → CANCELLED）。
   *
   * <p>终态不可离开；作废必须给原因。确认时若成员不足两笔，会被 DB 拒绝（23514）。
   */
  @PostMapping("/consolidations/{consolidationId}/status")
  public ConsolidationSaved advanceStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID consolidationId,
      @Valid @RequestBody ConsolidationStatusInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> consolidation.advanceStatus(consolidationId, input, a.membershipId()));
  }

  // ============================================================ 物流号回传

  /** 回传任务列表（真分页）：这是「哪张平台单还没回传物流号」的查询入口。 */
  @GetMapping("/writeback-tasks")
  public WritebackPage writebacks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID consolidationId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String platformOrderRef,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            consolidation.pageWritebacks(
                new WritebackQuery(
                    consolidationId, platformCode, status, platformOrderRef, page, size)));
  }

  /**
   * 登记**某个平台订单**的物流号回传结果。
   *
   * <p>蓝图加粗句「合单后每个平台订单仍要单独回传物流号」的落点：
   * 按成员行逐单登记，不存在「给整个包裹记一个物流号」的形状。
   */
  @PostMapping("/consolidations/{consolidationId}/members/{memberId}/writeback")
  public WritebackTask recordWriteback(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID consolidationId,
      @PathVariable UUID memberId,
      @Valid @RequestBody WritebackInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> consolidation.recordWriteback(consolidationId, memberId, input, a.membershipId()));
  }
}
