package com.starcon.trade;

import static com.starcon.trade.OrderChangeCancelModels.*;

import jakarta.validation.Valid;
import java.util.List;
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
 * 9.1.1.3 订单变更与取消。端点 13 个，前缀 {@code /trade/sales-orders/change}。
 *
 * <p><b>为什么前缀是 {@code /change} 而不是裸 {@code /{id}}</b>（照 9.1.1.2 同一纪律）： {@code GET
 * /trade/sales-orders/{id}} 已被 9.1.2.2 的手工订单详情占用， 在同一个
 * {@code @RequestMapping("/trade/sales-orders")} 下再加一个 {@code @GetMapping("/{id}")} 会让 Spring
 * 在<b>启动时</b>抛 ambiguous mapping， 整个 trade 服务起不来。已有的 {@code /list}（9.1.1.1）与 {@code
 * /detail}（9.1.1.2） 都是同一纪律的产物，本模块取第三个并列子资源 {@code /change}。
 *
 * <p>权限码用 {@code trade.order-change.*}（read / create / update）。 刻意<b>没有</b> delete：变更与取消的证据链是「申请 +
 * 回执 + 事件」， 删掉一条就等于让复盘可以对不上的账。V119 也 REVOKE 了事件表的 UPDATE/DELETE。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限， 再在一个事务里绑定 {@code
 * app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>跨租户与不存在返回同一个 404</b>（8.3.1.2 已确立的约定）。
 */
@RestController
@RequestMapping("/trade/sales-orders/change")
public class OrderChangeCancelController {

  private static final String RESOURCE = "order-change";

  private final AccessService access;
  private final OrderChangeCancelService changes;

  public OrderChangeCancelController(AccessService access, OrderChangeCancelService changes) {
    this.access = access;
    this.changes = changes;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String op, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + RESOURCE + "." + op);
    return access.run(actor, "trade." + RESOURCE + "." + op, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 本模块哪些能力有数据、哪些还没接上。
   *
   * <p>与 9.1.1.1 的 {@code /list/capabilities}（列表页筛选边界）、 9.1.1.2 的 {@code
   * /detail/capabilities}（详情页分区边界）三者分开： 并排读才不会把「本模块能改什么」当成「订单详情里有什么」。
   */
  @GetMapping("/capabilities")
  public OrderChangeCancelCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> changes.capabilities());
  }

  // ================================================================ 平台可变更规则

  /** 平台可变更规则集合读。这些是<b>数据</b>不是代码常量：各平台的可改窗口本来就不一样。 */
  @GetMapping("/platform-rules")
  public OrderChangeCancelRulePage platformRules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) OrderChangeKind changeKind,
      @RequestParam(required = false) Boolean allowed,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            changes.pageRules(
                new OrderChangeCancelRuleQuery(
                    platformCode, shopCode, changeKind, allowed, page, size)));
  }

  @PostMapping("/platform-rules")
  public OrderChangeCancelRuleRow createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody OrderChangeCancelRuleInput input) {
    return call(auth, "create", actor -> changes.createRule(input, actor.membershipId()));
  }

  /** 更新规则，带 version 乐观锁：改的是商务政策，并发覆盖必须是显式失败而不是后写赢。 */
  @PutMapping("/platform-rules/{id}")
  public OrderChangeCancelRuleRow updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody OrderChangeCancelRuleInput input) {
    return call(auth, "update", actor -> changes.updateRule(id, input, actor.membershipId()));
  }

  // ================================================================ 履约投影

  /**
   * 录入履约投影（预留 / 拣货 / 已发）。
   *
   * <p>9.3.3.1 履约请求与 8 出库发货事件落地后要调的授权入口。 现在没有上游调用方，所以也接受 {@code TENANT_MAINTAINED} 人工登记 ——
   * 让「已发货不可取消」这条硬规则在 9.3.3.1 交付前就已经是真的、可测的。
   */
  @PostMapping("/fulfillment-progress")
  public OrderChangeCancelProgressRow recordProgress(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody OrderChangeCancelProgressInput input) {
    return call(auth, "update", actor -> changes.recordProgress(input, actor.membershipId()));
  }

  @GetMapping("/fulfillment-progress")
  public List<OrderChangeCancelProgressRow> progress(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID orderId) {
    return call(auth, "read", actor -> changes.progress(orderId));
  }

  // ================================================================ 变更请求

  @GetMapping("/requests")
  public OrderChangeCancelPage<OrderChangeCancelChangeRow> requests(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID orderId,
      @RequestParam(required = false) String orderNo,
      @RequestParam(required = false) OrderChangeStatus status,
      @RequestParam(required = false) String changeKind,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            changes.pageChanges(
                new OrderChangeCancelQuery(orderId, orderNo, status, changeKind, page, size)));
  }

  @PostMapping("/requests")
  public OrderChangeCancelChangeRow createRequest(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody OrderChangeCancelChangeInput input) {
    return call(auth, "create", actor -> changes.createChange(input, actor.membershipId()));
  }

  @GetMapping("/requests/{id}")
  public OrderChangeCancelChangeDetail request(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> changes.changeDetail(id));
  }

  /**
   * 推进变更状态。
   *
   * <p>平台规则要求平台确认时，{@code CONFIRMED} 会先核对回写任务是否已被平台接受 —— 本模块不替平台做这个决定。
   */
  @PostMapping("/requests/{id}/status")
  public OrderChangeCancelChangeRow advanceRequest(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody OrderChangeCancelStatusInput input) {
    return call(auth, "update", actor -> changes.advanceChange(id, input, actor.membershipId()));
  }

  // ================================================================ 取消请求

  @GetMapping("/cancellations")
  public OrderChangeCancelPage<OrderChangeCancelCancellationRow> cancellations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID orderId,
      @RequestParam(required = false) String orderNo,
      @RequestParam(required = false) OrderChangeStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            changes.pageCancellations(
                new OrderChangeCancelQuery(orderId, orderNo, status, null, page, size)));
  }

  /** 建取消请求。同步下达仓库停止作业与释放占用任务 —— 蓝图要求「先确认仓库停止作业并释放占用」，任务必须在申请时就存在。 */
  @PostMapping("/cancellations")
  public OrderChangeCancelCancellationRow createCancellation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody OrderChangeCancelCancellationInput input) {
    return call(auth, "create", actor -> changes.createCancellation(input, actor.membershipId()));
  }

  /** 取消详情：逐行可取消量 + 仓库任务 + 平台回写 + 「还差什么才能确认」的阻塞项。 */
  @GetMapping("/cancellations/{id}")
  public OrderChangeCancelCancellationDetail cancellation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> changes.cancellationDetail(id));
  }

  @PostMapping("/cancellations/{id}/status")
  public OrderChangeCancelCancellationRow advanceCancellation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody OrderChangeCancelStatusInput input) {
    return call(
        auth, "update", actor -> changes.advanceCancellation(id, input, actor.membershipId()));
  }

  // ================================================================ 仓库回执（7.2.1.3）

  /** 仓库任务清单 —— 蓝图输出里的「仓库停止作业及释放结果」。 */
  @GetMapping("/cancellations/{id}/warehouse-tasks")
  public List<OrderChangeCancelHoldTaskRow> warehouseTasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> changes.holdTasks(id));
  }

  /** 登记仓库回执。两个阶段都 COMPLETED 之前，取消单不允许确认（V119 守卫 9.2）。 */
  @PostMapping("/cancellations/{id}/warehouse-tasks/{taskId}/status")
  public OrderChangeCancelHoldTaskRow warehouseTaskStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID taskId,
      @Valid @RequestBody OrderChangeCancelHoldTaskInput input) {
    return call(
        auth, "update", actor -> changes.updateHoldTask(id, taskId, input, actor.membershipId()));
  }

  // ================================================================ 平台回写（2.5.1.1）

  @GetMapping("/cancellations/{id}/writeback-tasks")
  public List<OrderChangeCancelWritebackRow> cancellationWritebacks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> changes.writebacks(id, true));
  }

  @GetMapping("/requests/{id}/writeback-tasks")
  public List<OrderChangeCancelWritebackRow> changeWritebacks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> changes.writebacks(id, false));
  }

  /** 登记平台回写结果。平台拒绝必须带原因，否则界面与审计都无法解释这次变更为什么没生效。 */
  @PostMapping("/writeback-tasks/{taskId}/status")
  public OrderChangeCancelWritebackRow writebackStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId,
      @Valid @RequestBody OrderChangeCancelWritebackInput input) {
    return call(
        auth, "update", actor -> changes.updateWriteback(taskId, input, actor.membershipId()));
  }
}
