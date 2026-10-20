package com.starcon.trade;

import static com.starcon.trade.OrderPendingReviewModels.*;

import com.starcon.trade.OrderReviewRuleModels.ReviewDetail;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
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
 * 9.2.1.1 待审队列。
 *
 * <p>命名空间 {@code /trade/order-pending-review}：与 9.2.1.2 的审单规则（{@code /trade/order-review}）、
 * 9.2.1.3 的异常拦截（{@code /trade/order-intercept}）、9.2.2.2 的风控校验（{@code /trade/orders/risk-validation}）
 * 分属不同命名空间 —— 「把待审单排出来给人看并推进结论」是本模块的主权，
 * 「配规则」「判异常」「算风险分」各有各的主人，权限与日志都该分得开。
 *
 * <p>权限码用 {@code trade.orders.read} / {@code trade.orders.update}：批量通过/驳回/挂起
 * 直接决定一单发不发，不是读权限能带过去的动作。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>刻意不提供的能力</b>：没有任何端点能「不带规则地批量通过」。
 * {@code POST /batches} 在 action=APPROVE 时必须带 {@code ruleCode + ruleVersion}，
 * 缺了就 400 —— 蓝图加粗句「批量通过必须基于明确的规则，而不是默认全过」在接口层就已经成立。
 * 同样<b>刻意不提供</b>「一键全过」端点与「改队列状态」的裸端点。
 */
@RestController
@RequestMapping("/trade/order-pending-review")
public class OrderPendingReviewController {

  private final AccessService access;
  private final OrderPendingReviewService pending;

  public OrderPendingReviewController(AccessService access, OrderPendingReviewService pending) {
    this.access = access;
    this.pending = pending;
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
   * <p>存在的理由：审单是发货前的最后人工闸门，「我这一步凭什么放行」必须能当面说清。
   * 哪些数据真的接通了、哪些没接通，由接口自己说，而不是让界面从空数组里猜。
   */
  @GetMapping("/capabilities")
  public PendingReviewCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "orders.read", a -> pending.capabilities());
  }

  /** 队列概览：待审 / 已挂起 / 已通过 / 已驳回 / 已超时 / 未评估 / 高风险。 */
  @GetMapping("/summary")
  public PendingReviewSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "orders.read", a -> pending.summary());
  }

  // ============================================================ 待审队列

  /**
   * 待审列表：真分页 + 真筛选 + **DB 层真排序**。
   *
   * <p>排序键是风险分 → 时效分 → 提交时刻 → 订单 id。前端拿到列表再排一次是错的：
   * 那样只能对当前这一页生效，翻页会乱序。
   */
  @GetMapping("/queue")
  public PendingReviewPage queue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String orderSource,
      @RequestParam(required = false) String queueStatus,
      @RequestParam(required = false) String reviewDecision,
      @RequestParam(required = false) Boolean riskEvaluated,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "orders.read",
        a ->
            pending.page(
                new QueueQuery(
                    keyword, orderSource, queueStatus, reviewDecision, riskEvaluated, page, size)));
  }

  /** 单条详情：订单事实 + 当前结论 + 风控五维明细 + 完整命中链 + 最近几次批量结果。 */
  @GetMapping("/queue/{orderId}")
  public PendingReviewDetail queueItem(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId) {
    return call(auth, "orders.read", a -> pending.detail(orderId));
  }

  /**
   * 试算：按此刻生效中的规则跑一次判定，标成 DRY_RUN，不冒充生产结论。
   *
   * <p>委托 9.2.1.2 的判定引擎（同一服务、同一库，不跨服务读）——
   * 规则命中链是 9.2.1.2 的主权，本模块只负责把链摆到队列上给人看。
   * 权限用读：试跑不推进任何订单状态（与 9.2.1.2 的 /trial 同一口径）。
   */
  @PostMapping("/queue/{orderId}/evaluate")
  public ReviewDetail evaluate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @RequestBody(required = false) EvaluateInput input) {
    OffsetDateTime at =
        input == null || input.evaluatedAt() == null ? OffsetDateTime.now() : input.evaluatedAt();
    return call(auth, "orders.read", a -> pending.evaluate(orderId, at, a.membershipId()));
  }

  // ============================================================ 批量审单

  /**
   * 批量通过 / 驳回 / 挂起。
   *
   * <p><b>必须带幂等键</b>：同一个键重复提交只生效一次，第二次回放上一次的回执，
   * <b>不再改任何状态</b>。
   *
   * <p><b>通过必须带规则</b>：{@code ruleCode + ruleVersion} 缺一不可，
   * 且逐单要由那条规则真的判出来 —— 判成转人工的、或命中的是别的规则的，一律跳过并写清原因。
   */
  @PostMapping("/batches")
  public BatchReceipt decide(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BatchDecisionInput input) {
    return call(auth, "orders.update", a -> pending.decide(input, a.membershipId()));
  }

  /** 批量回执列表（真分页 + 真搜索）。 */
  @GetMapping("/batches")
  public BatchPage batches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "orders.read", a -> pending.pageBatches(new BatchQuery(action, keyword, page, size)));
  }

  /** 批量回执详情（含逐条结果）。跨租户在 RLS 下查不到 → 404。 */
  @GetMapping("/batches/{batchId}")
  public BatchReceipt batch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID batchId) {
    return call(auth, "orders.read", a -> pending.batch(batchId));
  }
}
