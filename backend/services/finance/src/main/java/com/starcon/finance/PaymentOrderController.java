package com.starcon.finance;

import static com.starcon.finance.PaymentOrderModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.2.2.1 付款单与审批的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/payment-orders}</b>：与 12.1.2.1 的 {@code /finance/payables}、
 * 12.1.2.2 的 {@code /finance/payment-plans} 以及卷 12 的其余模块各自独立前缀 ——
 * 「哪个模块的哪个端点」必须说得清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.paymentorder.read / .create / .update}：
 * 建单、改单、提交审批都不是读权限能带过去的动作。
 *
 * <p>⚠️ <b>本前缀下没有任何「执行付款」「标记已付」的端点</b>：付款属 12.2.2.2（未交付）。
 * 这里是「发起付款申请 + 提交审批 + 读回审批结果」——
 * <b>审批通过 ≠ 钱已付出</b>。
 *
 * <p>⚠️ <b>本前缀下也没有「审批通过 / 审批驳回」的端点</b>：审批属 17 审批（identity）。
 * 这里是「提交审批」与「同步审批结果」两件事，**不造第二套审批引擎**。
 */
@RestController
@RequestMapping("/finance/payment-orders")
public class PaymentOrderController {

  private final AccessService access;
  private final PaymentOrderService orders;

  public PaymentOrderController(AccessService access, PaymentOrderService orders) {
    this.access = access;
    this.orders = orders;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String scope, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, scope + "." + permission);
    return access.run(actor, scope + "." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与筛选项

  /** 能力声明：哪些真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinancePaymentOrderCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.paymentorder", "read", actor -> orders.capabilities());
  }

  /** 筛选项下拉（全部来自真实数据）。 */
  @GetMapping("/options")
  public FinancePaymentOrderOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.paymentorder", "read", actor -> orders.options());
  }

  // ================================================================ 付款单

  /** 付款单列表：真分页 + 真筛选 + 真排序 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。 */
  @GetMapping
  public FinancePaymentOrderPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String approvalStatus,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String counterpartyType,
      @RequestParam(required = false) UUID counterpartyRef,
      @RequestParam(required = false) String paymentMethod,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "finance.paymentorder",
        "read",
        actor ->
            orders.page(
                new PaymentOrderQuery(
                    enumOf(status, PaymentOrderStatus.class, "付款单状态"),
                    enumOf(approvalStatus, ApprovalStatus.class, "审批状态"),
                    currency,
                    enumOf(counterpartyType, PaymentCounterpartyType.class, "付款对象类型"),
                    counterpartyRef,
                    enumOf(paymentMethod, PaymentMethod.class, "付款方式"),
                    keyword,
                    page,
                    size)));
  }

  /** 付款单详情：单头 + 明细 + 分批 + 事件流 + 审批记录。 */
  @GetMapping("/{id}")
  public FinancePaymentOrderDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "finance.paymentorder", "read", actor -> orders.detail(id));
  }

  /** 建一张付款单（草稿）。 */
  @PostMapping
  public FinancePaymentOrderRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinancePaymentOrderInput input) {
    return call(auth, "finance.paymentorder", "create", actor -> orders.create(input, actor));
  }

  /** 改一张**草稿**付款单（明细与分批整体替换）。 */
  @PutMapping("/{id}")
  public FinancePaymentOrderRow update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinancePaymentOrderInput input) {
    return call(auth, "finance.paymentorder", "update", actor -> orders.update(id, input, actor));
  }

  /**
   * 提交审批（草稿 → 待审）。
   *
   * <p>🔴 **真正的审批判定在 identity 侧**：起始节点由它按金额阈值分支选出来。
   * 单据类型「PAYMENT_ORDER」未注册生效审批流时，本端点**拒绝提交**（400/404）——
   * 付款是资金动作，不能按「未注册即默认放行」处理。
   */
  @PostMapping("/{id}/submit")
  public FinancePaymentOrderSubmitResult submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth, "finance.paymentorder", "update", actor -> orders.submit(id, actor, auth));
  }

  /**
   * 同步审批结果（读回 identity 的审批链路）。
   *
   * <p>已批 ⇒ 付款单转「已批」；已驳回 ⇒ **退回草稿**（可改可再提交）；
   * 还在审 ⇒ 刷新当前节点，状态不动。
   */
  @PostMapping("/{id}/sync-approval")
  public FinancePaymentOrderSyncResult syncApproval(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth, "finance.paymentorder", "update", actor -> orders.sync(id, actor, auth));
  }

  // ================================================================ 应付明细 / 汇率

  /**
   * 某张应付单下**可被选中**的明细行（只读 12.1.2.1）。
   *
   * <p>每行带未付余额与「是否已被在册付款单占用」⇒ 前端据此告诉用户哪一行能选。
   */
  @GetMapping("/payable-lines/{payableId}")
  public List<FinancePaymentPayableLineOption> payableLines(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID payableId) {
    return call(
        auth, "finance.paymentorder", "read", actor -> orders.payableLines(payableId));
  }

  /**
   * 汇率快照（**读** catalog 5.2.2.1）。
   *
   * <p>口径：「1 baseCurrency = rate quoteCurrency」，与单头汇率快照一致。
   */
  @GetMapping("/exchange-rate")
  public FinancePaymentExchangeRateView exchangeRate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String baseCurrency,
      @RequestParam String quoteCurrency) {
    return call(
        auth, "finance.paymentorder", "read", actor -> orders.exchangeRate(baseCurrency, quoteCurrency, auth));
  }

  // ================================================================ 工具

  /** 词表解析：非法取值给一句能直接展示的话，而不是一个枚举名。 */
  private static <T extends Enum<T>> T enumOf(String raw, Class<T> type, String label) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return Enum.valueOf(type, raw.trim());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "取值不合法：" + raw);
    }
  }
}
