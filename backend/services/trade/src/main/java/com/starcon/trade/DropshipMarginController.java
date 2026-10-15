package com.starcon.trade;

import static com.starcon.trade.DropshipMarginModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 9.6.2.1 代发差价与对账。
 *
 * <p>命名空间 {@code /trade/fulfillment/dropship-margin}：与 9.6.1.1 的
 * {@code /trade/fulfillment/dropship}、9.6.1.2 的
 * {@code /trade/fulfillment/dropship-orders}、9.6.1.3 的
 * {@code /trade/fulfillment/dropship-tracking} <b>并列</b>，而不是挂在其中任何一个下面 ——
 * 配置说「走不走代发」，代发单说「怎么下的」，跟踪说「到哪了、卡在哪」，
 * 本模块说「这一票赚没赚、这个周期该付供应商多少」。四件事混在一个命名空间里，
 * 「改一次配置」与「做一次对账」在日志与权限上就分不开了。
 *
 * <p>权限码沿用 {@code trade.fulfillment.read} / {@code trade.fulfillment.update}：
 * 登记费率与生成对账单是要花钱、要对外承诺的动作，不是读权限能带过去的。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/fulfillment/dropship-margin")
public class DropshipMarginController {

  private final AccessService access;
  private final DropshipMarginService margin;

  public DropshipMarginController(AccessService access, DropshipMarginService margin) {
    this.access = access;
    this.margin = margin;
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
   * <p>四个维度 available=false 且点名 ownerModule：<b>平台费用规则</b>（5.2.3.1）、
   * <b>物流与头程费用</b>（5.2.3.2）、<b>应付单</b>（12.1.2.1）、
   * <b>利润报表</b>（13.4.1.1 / 13.4.1.2）。前两个在本模块里的真实形态是
   * **可登记的费率来源**，后两个是**真待办** —— 报成 true 会让人以为
   * 亏损判据已经完整、应付单已经通了。
   */
  @GetMapping("/capabilities")
  public DropshipMarginCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> margin.capabilities());
  }

  // ============================================================ 平台费率

  /** 平台费率登记列表（真分页）：5.2.3.1 未交付前，它是亏损判据的唯一来源。 */
  @GetMapping("/fee-rates")
  public DropshipFeeRatePage feeRates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "fulfillment.read", a -> margin.pageFeeRates(keyword, page, size));
  }

  /**
   * 登记平台费率（upsert）。
   *
   * <p>「按售价比例」与「按单固定额」两种计价形态**互斥**：两个都填的话，
   * 「这条费率到底怎么算」没有答案，而服务层只会安静地挑一个用。
   */
  @PostMapping("/fee-rates")
  public DropshipFeeRateRow saveFeeRate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DropshipFeeRateInput input) {
    return call(auth, "fulfillment.update", a -> margin.saveFeeRate(input, a.membershipId()));
  }

  // ============================================================ 汇率

  @GetMapping("/fx-rates")
  public DropshipFxRatePage fxRates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String baseCurrency,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "fulfillment.read", a -> margin.pageFxRates(baseCurrency, page, size));
  }

  /** 登记汇率（1 base = rateValue quote）。未登记时对账行标 UNSET，不用 1.0 假装。 */
  @PostMapping("/fx-rates")
  public DropshipFxRateRow saveFxRate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DropshipFxRateInput input) {
    return call(auth, "fulfillment.update", a -> margin.saveFxRate(input, a.membershipId()));
  }

  // ============================================================ 结算周期

  @GetMapping("/cycles")
  public DropshipSettlementCyclePage cycles(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String supplierCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "fulfillment.read", a -> margin.pageCycles(supplierCode, page, size));
  }

  /** 登记供应商结算周期（蓝图输入第三条）。 */
  @PostMapping("/cycles")
  public DropshipSettlementCycleRow saveCycle(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DropshipSettlementCycleInput input) {
    return call(auth, "fulfillment.update", a -> margin.saveCycle(input, a.membershipId()));
  }

  // ============================================================ 差价毛利

  /**
   * 跑一轮差价计算。
   *
   * <p>只有成本已落定（ACCEPTED / SHIPPED）的代发单才算差价；跳过的单数如实报出 ——
   * 把「没算」与「算了不亏」混成一个数字，正是「看不出来漏没漏」的开始。
   */
  @PostMapping("/facts/evaluate")
  public DropshipMarginEvaluateResult evaluateFacts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime periodStart,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime periodEnd) {
    return call(
        auth, "fulfillment.update", a -> margin.evaluateMarginFacts(periodStart, periodEnd,
            a.membershipId()));
  }

  /** 差价毛利明细（真分页；有问题的排在前面）。 */
  @GetMapping("/facts")
  public DropshipMarginFactPage facts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String supplierCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String orderNo,
      @RequestParam(required = false) String lossVerdict,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            margin.pageFacts(
                new DropshipMarginFactQuery(
                    supplierCode, shopCode, orderNo, lossVerdict, keyword, page, size)));
  }

  /**
   * 差价与毛利报表（按店铺 / 供应商 / SKU / 代发单）。
   *
   * <p>每行都带 {@code businessModel=DROPSHIP} 与 {@code costBasis=PURCHASE_SPREAD}：
   * 代发的成本是采购差价，与自营的自有库存成本不是同一口径，下游不得合并。
   * 金额按币种分组，跨币种不相加。
   */
  @GetMapping("/report")
  public DropshipMarginReport report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "SUPPLIER") String groupBy,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime periodStart,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime periodEnd,
      @RequestParam(required = false) String supplierCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String currencyCode) {
    return call(
        auth,
        "fulfillment.read",
        a -> margin.report(groupBy, periodStart, periodEnd, supplierCode, shopCode,
            currencyCode));
  }

  // ============================================================ 亏损预警

  /**
   * 跑一轮亏损预警检出（售价低于代发成本 + 平台费）。
   *
   * <p>「已确认亏损」与「平台费未登记、无法判定」<b>不合并</b>：处置动作不同
   * （前者停投 / 改售价 / 换供应商，后者去登记费率），合成一条「有异常」就没法派工。
   */
  @PostMapping("/alerts/evaluate")
  public DropshipMarginEvaluateResult evaluateAlerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime periodStart,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime periodEnd) {
    return call(
        auth, "fulfillment.update", a -> margin.evaluateLossAlerts(periodStart, periodEnd,
            a.membershipId()));
  }

  /** 亏损预警工作台（真分页）。两个未结案计数分列：确认亏了多少 / 多少条判不了。 */
  @GetMapping("/alerts")
  public DropshipLossAlertPage alerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String supplierCode,
      @RequestParam(required = false) String alertKind,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            margin.pageAlerts(
                new DropshipLossAlertQuery(supplierCode, alertKind, status, severity, page,
                    size)));
  }

  /** 处置一条预警（必须写明依据）；{@code resolved=true} 表示同时结案。 */
  @PostMapping("/alerts/{alertId}/acknowledge")
  public DropshipLossAlertRow acknowledgeAlert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID alertId,
      @Valid @RequestBody DropshipLossAlertAckInput input) {
    return call(
        auth, "fulfillment.update", a -> margin.acknowledgeAlert(alertId, input,
            a.membershipId()));
  }

  /** 判为误报（必须写明理由；解封后重新触发会被当作一条新事实重新检出）。 */
  @PostMapping("/alerts/{alertId}/dismiss")
  public DropshipLossAlertRow dismissAlert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID alertId,
      @Valid @RequestBody DropshipLossAlertDismissInput input) {
    return call(
        auth, "fulfillment.update", a -> margin.dismissAlert(alertId, input, a.membershipId()));
  }

  // ============================================================ 对账单

  /**
   * 生成一张供应商对账单（周期由调用方显式给出，不从墙上时钟推导）。
   *
   * <p>明细行是<b>冻结快照</b>：事后改费率改不动历史对账单。
   * 汇率未登记的行不计入合计，单独进未登记桶。
   */
  @PostMapping("/reconciliations")
  public DropshipReconciliationRow buildReconciliation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DropshipReconciliationInput input) {
    return call(
        auth, "fulfillment.update", a -> margin.buildReconciliation(input, a.membershipId()));
  }

  /** 对账单列表（真分页）。 */
  @GetMapping("/reconciliations")
  public DropshipReconciliationPage reconciliations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String supplierCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String currencyCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            margin.pageReconciliations(
                new DropshipReconciliationQuery(supplierCode, status, currencyCode, page, size)));
  }

  /** 对账单详情：头 + 冻结明细行 + 折算口径说明。跨租户 → 404。 */
  @GetMapping("/reconciliations/{statementId}")
  public DropshipReconciliationDetail reconciliation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID statementId) {
    return call(auth, "fulfillment.read", a -> margin.reconciliationDetail(statementId));
  }

  /**
   * 对账单状态流转（FINALIZED / DISPUTED / CLOSED）。
   *
   * <p>「有争议」可以再回到 FINALIZED，而 CLOSED 是终态 ——
   * 供应商说「这单我收 13」时要能把已关闭的单拉出来重开，而不是新建一张丢掉历史。
   */
  @PostMapping("/reconciliations/{statementId}/status")
  public DropshipReconciliationRow changeStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID statementId,
      @Valid @RequestBody DropshipReconciliationStatusInput input) {
    return call(
        auth, "fulfillment.update", a -> margin.changeStatementStatus(statementId, input,
            a.membershipId()));
  }

  // ============================================================ 应付待办

  /**
   * 应付待办工作台：12.1.2.1（finance）从这里接手。
   *
   * <p>「已交给下游」（SENT）与「下游已受理」（ACKNOWLEDGED）是<b>两个</b>状态，
   * 任务在下游接手前不会被画成已完成。
   */
  @GetMapping("/tasks")
  public DropshipPayableTaskPage tasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String taskStatus,
      @RequestParam(required = false) String supplierCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a -> margin.pageTasks(new DropshipPayableTaskQuery(taskStatus, supplierCode, page, size)));
  }

  /** 下游领取（OPEN → CLAIMED）。 */
  @PostMapping("/tasks/{taskId}/claim")
  public DropshipPayableTaskRow claimTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId) {
    return call(auth, "fulfillment.update", a -> margin.claimTask(taskId, a.membershipId()));
  }

  /**
   * 下游回执（CLAIMED → SENT / ACKNOWLEDGED / FAILED）。
   *
   * <p>只有走到 ACKNOWLEDGED，应付才算真的有人接了 —— 「已生成应付单」必须有一个
   * 下游真的接手过它，否则只能是待办。
   */
  @PostMapping("/tasks/{taskId}/complete")
  public DropshipPayableTaskRow completeTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId,
      @Valid @RequestBody DropshipPayableTaskResultInput input) {
    return call(
        auth, "fulfillment.update", a -> margin.completeTask(taskId, input, a.membershipId()));
  }

  // ============================================================ 事件流

  /** 事件流（只读；append-only，没有任何写形状）。 */
  @GetMapping("/events")
  public DropshipMarginEventPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID statementId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "fulfillment.read", a -> margin.pageEvents(statementId, page, size));
  }
}
