package com.starcon.trade;

import static com.starcon.trade.RefundStatusModels.*;

import com.starcon.trade.RefundStatusModels.DiscrepancyKind;
import com.starcon.trade.RefundStatusModels.RefundDiscrepancyCreate;
import com.starcon.trade.RefundStatusModels.RefundDiscrepancyResolutionRequest;
import com.starcon.trade.RefundStatusModels.RefundDispatchRequest;
import com.starcon.trade.RefundStatusModels.RefundExecutionCreate;
import com.starcon.trade.RefundStatusModels.RefundExecutionDetail;
import com.starcon.trade.RefundStatusModels.RefundExecutionPage;
import com.starcon.trade.RefundStatusModels.RefundExecutionQuery;
import com.starcon.trade.RefundStatusModels.RefundExecutionRow;
import com.starcon.trade.RefundStatusModels.RefundFilterOptions;
import com.starcon.trade.RefundStatusModels.RefundPlatformSyncRequest;
import com.starcon.trade.RefundStatusModels.RefundReportRequest;
import com.starcon.trade.RefundStatusModels.RefundStatusCapabilities;
import com.starcon.trade.RefundStatusModels.RefundVerificationRequest;
import com.starcon.trade.RefundStatusModels.RefundVerificationRow;
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
 * 11.3.1.2 退款状态。
 *
 * <p><b>命名空间是 {@code /trade/refunds}</b>，与 {@code /trade/aftersales}（11.1.1.1 售后单列表）
 * 并列。前端路由是 {@code /aftersales/refunds} —— 蓝图 11 卷 11.3「退款与换货」的 L3 入口，
 * 此前挂的是一个 {@code implemented: false} 的任务页占位。
 *
 * <p>刻意<b>不</b>占用 {@code /trade/orders}（9.1.1.1 车道的地盘，
 * {@code TradeTenancyIntegrationTest} 显式断言它必须 404）。
 *
 * <p><b>权限码</b>：读 {@code trade.refunds.read}，写 {@code trade.refunds.write}。
 * 写权限只覆盖本模块自己的六张表（V159）。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} 先向 identity 确认已认证成员关系
 * 与权限，再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/refunds")
public class RefundStatusController {

  private static final String READ = "refunds.read";
  private static final String WRITE = "refunds.write";

  private final AccessService access;
  private final RefundStatusService refunds;

  public RefundStatusController(AccessService access, RefundStatusService refunds) {
    this.access = access;
    this.refunds = refunds;
  }

  /** 统一读入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + READ);
    return access.run(actor, "trade." + READ, () -> work.apply(actor));
  }

  /** 写入口：需要 {@code trade.refunds.write}。租户来源与读入口完全一致。 */
  private <T> T write(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + WRITE);
    return access.run(actor, "trade." + WRITE, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：状态机逐条转换规则 + 上游 / 下游逐条 Delivered 与归属模块。
   *
   * <p>让界面能说清「12 财务没交付所以收不到它的回报」「9 订单的退款状态回写还没接」，
   * 而不是把它们显示成空白让人以为「这个租户没有退款」。
   */
  @GetMapping("/capabilities")
  public RefundStatusCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> refunds.capabilities());
  }

  /** 筛选项下拉用的租户级词表（全部来自真实数据 DISTINCT，不是硬编码常量列表）。 */
  @GetMapping("/filters")
  public RefundFilterOptions filters(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> refunds.filterOptions());
  }

  // ================================================================ 列表 / 详情 / 差异清单

  /**
   * 退款状态列表：真分页 + 多维筛选 + 与分页无关的全量汇总。
   *
   * <p>默认排序是 {@code ATTENTION_FIRST}：结果未知 → 明确失败 → 差异 → 其余。
   * 「待处置」的三种状态沉不到第 30 页。
   */
  @GetMapping
  public RefundExecutionPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String executionState,
      @RequestParam(required = false) String platformSyncState,
      @RequestParam(required = false) String platformRefundMethod,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) UUID orderId,
      @RequestParam(required = false) UUID caseId,
      @RequestParam(required = false) String orderNo,
      @RequestParam(required = false) String refundNo,
      @RequestParam(required = false) String failureCode,
      @RequestParam(required = false) String minAmount,
      @RequestParam(required = false) String maxAmount,
      @RequestParam(required = false) String onlyNeedsAttention,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    RefundExecutionQuery q =
        new RefundExecutionQuery(
            executionState, platformSyncState, platformRefundMethod, currency, orderId, caseId,
            orderNo, refundNo, failureCode, minAmount, maxAmount, onlyNeedsAttention, sort, page, size);
    return call(auth, actor -> refunds.list(q));
  }

  /**
   * 差异清单（蓝图输出句点名的清单）。
   *
   * <p>默认只看<b>未裁定</b>的 —— 运营每天要处理的是「还有什么没处理」。
   * 汇总里系统侧与平台侧金额<b>分别</b>给出：合成净额会把方向抹掉
   * （−100 与 +100 抵成 0，而真实情况是差了 200）。
   */
  @GetMapping("/discrepancies")
  public RefundDiscrepancyPage discrepancies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String resolutionState,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    final DiscrepancyKind parsed =
        kind == null || kind.isBlank() ? null : DiscrepancyKind.valueOf(kind.trim());
    return call(auth, actor -> refunds.listDiscrepancies(resolutionState, parsed, page, size));
  }

  /** 退款详情：头 + 派发历史 + 查证历史 + 外部回报 + 差异 + 状态事件 + <b>当前建议</b>。 */
  @GetMapping("/{id}")
  public RefundExecutionDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> refunds.detail(id));
  }

  // ================================================================ 写入口

  /**
   * 登记一笔退款执行（NOT_INITIATED 态）。
   *
   * <p>⚠️ 这是 <b>11.3.1.1 退款申请未交付</b>期间的入口（capabilities 的
   * {@code refundRequestDelivered=false} 如实报出）：只做「把退款登记成待发起」，
   * <b>不含</b>可退余额校验与审批（那两件依赖 11.3.1.1 与 12 财务）。
   *
   * <p><b>幂等</b>：同一 {@code idempotencyKey} 重复提交返回<b>同一笔</b>，不创建第二笔。
   */
  @PostMapping
  public RefundExecutionRow register(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody RefundExecutionCreate request) {
    return write(auth, actor -> refunds.register(request, actor.userId()));
  }

  /**
   * 派发 / 重试退款。
   *
   * <p><b>「先查证交易再重试」在这里生效</b>：处于 {@code UNKNOWN} 的退款必须带
   * {@code verificationId}，且那条查证的结论必须是 {@code CONFIRMED_NOT_SETTLED}。
   * 结论是 {@code CONFIRMED_SETTLED}（钱已经付出）时返回 409 并指出正确动作是推进到 SUCCEEDED；
   * 结论是 {@code INCONCLUSIVE}（还是不知道）时同样 409 —— 此时重发就是拿真实资损赌不确定。
   *
   * <p>DB 层还有一道 {@code guard_refund_attempt_retry} 触发器，
   * 绕过本服务直接 INSERT 也照样被挡。
   */
  @PostMapping("/{id}/dispatch")
  public RefundExecutionRow dispatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RefundDispatchRequest request) {
    return write(auth, actor -> refunds.dispatch(id, request, actor.userId()));
  }

  /** 登记一次查证交易（「先查证交易再重试」的那一步）。 */
  @PostMapping("/{id}/verifications")
  public RefundVerificationRow verify(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RefundVerificationRequest request) {
    return write(auth, actor -> refunds.verify(id, request, actor.userId()));
  }

  /**
   * 登记一条外部执行结果回报（12 财务回报 / 平台侧查询 / 人工登记）。
   *
   * <p><b>{@code TIMEOUT} 与 {@code UNKNOWN} 只把执行推进到 UNKNOWN，绝不推进到 FAILED</b> ——
   * 这是蓝图加粗句「外部退款超时不等于失败」的直接落点。
   *
   * <p>12 财务交付前 {@code reportSource=FINANCE_MODULE_12} 返回 503 并说明原因，
   * 而不是收下一条来源不明的记录。
   */
  @PostMapping("/{id}/reports")
  public RefundExecutionRow report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RefundReportRequest request) {
    return write(auth, actor -> refunds.report(id, request, actor.userId()));
  }

  /** 平台侧退款同步结果回报。只更新平台同步这条轴，不动执行状态。 */
  @PostMapping("/{id}/platform-sync")
  public RefundExecutionRow platformSync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RefundPlatformSyncRequest request) {
    return write(auth, actor -> refunds.syncPlatform(id, request, actor.userId()));
  }

  /**
   * 登记一条对账差异。<b>如实报，不自动抹平。</b>
   *
   * <p>按差异类型钉死该有的证据：金额不一致 ⇒ 两边金额都有且不等；币种不一致 ⇒ 两边币种都有且不等；
   * 时间不一致 ⇒ 两边时刻都有且不等；存在性不一致 ⇒ <b>恰好一边</b>有状态。
   */
  @PostMapping("/{id}/discrepancies")
  public RefundDiscrepancyRow detectDiscrepancy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody RefundDiscrepancyCreate request) {
    return write(auth, actor -> refunds.detectDiscrepancy(id, request, actor.userId()));
  }

  /**
   * 人工裁定差异。<b>只有人的动作能调用它</b> —— 系统不自动抹平。
   *
   * <p>DB 层 {@code refund_discrepancy} 的 UPDATE 是<b>列级白名单</b>
   * （只授回 resolution_state / resolution_note / resolved_at / resolved_by），
   * 检测事实改不动。
   */
  @PostMapping("/{id}/discrepancies/{discrepancyId}/resolve")
  public RefundDiscrepancyRow resolveDiscrepancy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID discrepancyId,
      @RequestBody RefundDiscrepancyResolutionRequest request) {
    return write(auth, actor -> refunds.resolveDiscrepancy(id, discrepancyId, request, actor.userId()));
  }
}
