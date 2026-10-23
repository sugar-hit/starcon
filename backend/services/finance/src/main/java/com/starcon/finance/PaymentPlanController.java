package com.starcon.finance;

import static com.starcon.finance.PaymentPlanModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
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
 * 12.1.2.2 付款计划的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/payment-plans}</b>：与 12.1.2.1 的 {@code /finance/payables}、
 * 12.1.1.1 的 {@code /finance/receivables} 以及卷 12 的其余模块各自独立前缀 ——
 * 「哪个模块的哪个端点」必须说得清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.paymentplan.read / .create / .update}：
 * 生成计划、批量安排、改规则都不是读权限能带过去的动作。
 *
 * <p>⚠️ **本前缀下没有任何「执行付款」的端点**：付款属 12.2.2.1（未交付）。
 * 这里只有「生成计划」「安排付款窗口」「取消计划」「判定与提醒」——
 * **不造付款单、不写「已付款」**。
 */
@RestController
public class PaymentPlanController {

  private final AccessService access;
  private final PaymentPlanService plans;

  public PaymentPlanController(AccessService access, PaymentPlanService plans) {
    this.access = access;
    this.plans = plans;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String scope, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, scope + "." + permission);
    return access.run(actor, scope + "." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与筛选项

  /** 能力声明：哪些真接通、哪些还没交付、归谁。 */
  @GetMapping("/finance/payment-plans/capabilities")
  public FinancePaymentPlanCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.paymentplan", "read", actor -> plans.capabilities());
  }

  /** 筛选项下拉（全部来自真实数据）。 */
  @GetMapping("/finance/payment-plans/options")
  public FinancePaymentPlanOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.paymentplan", "read", actor -> plans.options());
  }

  // ================================================================ 付款计划

  /** 付款计划列表：真分页 + 真筛选 + 真排序 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。 */
  @GetMapping("/finance/payment-plans")
  public FinancePaymentPlanPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String paymentCondition,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) UUID counterpartyRef,
      @RequestParam(required = false) UUID batchId,
      @RequestParam(required = false) Boolean overdueOnly,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate paidFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate paidTo,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "finance.paymentplan",
        "read",
        actor ->
            plans.page(
                new PaymentPlanQuery(
                    enumOf(status, PaymentPlanStatus.class, "计划状态"),
                    enumOf(paymentCondition, PaymentCondition.class, "付款条件"),
                    currency,
                    counterpartyRef,
                    batchId,
                    overdueOnly,
                    paidFrom,
                    paidTo,
                    keyword,
                    page,
                    size)));
  }

  /** 付款计划详情：计划 + 事件流 + 当前提醒。 */
  @GetMapping("/finance/payment-plans/{id}")
  public FinancePaymentPlanDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "finance.paymentplan", "read", actor -> plans.detail(id));
  }

  /**
   * 生成付款计划（**只生成，不排批次**）。
   *
   * <p>⚠️ 不产生任何付款动作：付款属 12.2.2.1（未交付）。
   */
  @PostMapping("/finance/payment-plans/generate")
  public FinancePaymentPlanScheduleResult generate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinancePaymentPlanGenerateInput input) {
    return call(
        auth, "finance.paymentplan", "create", actor -> plans.generate(input, actor));
  }

  /**
   * 按供应商与到期窗口**批量安排付款**。
   *
   * <p>⚠️ 安排 = 把应付排进某个付款窗口 / 批次，**不是执行付款**。
   */
  @PostMapping("/finance/payment-plans/schedule")
  public FinancePaymentPlanScheduleResult schedule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinancePaymentPlanScheduleInput input) {
    return call(
        auth, "finance.paymentplan", "create", actor -> plans.schedule(input, actor));
  }

  /** 取消一条计划。理由必填。取消不是删除：痕迹要留着。 */
  @PostMapping("/finance/payment-plans/{id}/cancel")
  public FinancePaymentPlanRow cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FinancePaymentPlanCancelInput input) {
    return call(
        auth,
        "finance.paymentplan",
        "update",
        actor -> plans.cancel(id, input == null ? null : input.reason(), actor));
  }

  // ================================================================ 付款批次

  /** 付款批次列表（真分页 + 真筛选）。 */
  @GetMapping("/finance/payment-plans/batches")
  public FinancePaymentBatchPage batches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID counterpartyRef,
      @RequestParam(required = false) String currency,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "finance.paymentplan",
        "read",
        actor -> plans.batches(status, counterpartyRef, currency, page, size));
  }

  // ================================================================ 资金需求预测

  /**
   * 资金需求预测（**实时聚合**：按币种 × 到期窗口，流入 − 流出）。
   *
   * <p>⚠️ 计划改了预测当场跟着变 —— 它不是一张静态表。
   */
  @GetMapping("/finance/payment-plans/forecast")
  public FinancePaymentForecast forecast(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) Integer windowDays) {
    return call(
        auth,
        "finance.paymentplan",
        "read",
        actor -> plans.forecast(from, to, windowDays));
  }

  // ================================================================ 到期提醒

  /** 到期提醒清单（真分页 + 真筛选）。 */
  @GetMapping("/finance/payment-plans/reminders")
  public FinancePaymentReminderPage reminders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String reminderType,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) UUID counterpartyRef,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "finance.paymentplan",
        "read",
        actor ->
            plans.reminders(
                new PaymentReminderQuery(
                    enumOf(status, ReminderStatus.class, "提醒状态"),
                    enumOf(reminderType, ReminderType.class, "提醒类型"),
                    currency,
                    counterpartyRef,
                    keyword,
                    page,
                    size)));
  }

  /**
   * 扫描在册计划，生成 / 更新 / 失效到期提醒。
   *
   * <p>⚠️ **不推送任何东西**：推送通道属 1.3（未交付），提醒的 push_status 恒为 NOT_PUSHED。
   */
  @PostMapping("/finance/payment-plans/reminders/scan")
  public FinancePaymentReminderScanResult scanReminders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
      @RequestParam(required = false) Integer horizonDays) {
    return call(
        auth,
        "finance.paymentplan",
        "update",
        actor -> plans.scanReminders(asOf, horizonDays, actor));
  }

  /** 确认一条到期提醒。 */
  @PostMapping("/finance/payment-plans/reminders/{id}/acknowledge")
  public FinancePaymentReminderRow acknowledgeReminder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) FinancePaymentReminderAcknowledgeInput input) {
    return call(
        auth,
        "finance.paymentplan",
        "update",
        actor ->
            plans.acknowledgeReminder(id, input == null ? null : input.reason(), actor));
  }

  /** 忽略一条到期提醒。理由必填。 */
  @PostMapping("/finance/payment-plans/reminders/{id}/dismiss")
  public FinancePaymentReminderRow dismissReminder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FinancePaymentReminderDismissInput input) {
    return call(
        auth,
        "finance.paymentplan",
        "update",
        actor -> plans.dismissReminder(id, input == null ? null : input.reason(), actor));
  }

  // ================================================================ 账期规则

  /** 账期规则清单（租户自己维护；系统不预置任何一行）。 */
  @GetMapping("/finance/payment-plans/term-rules")
  public List<FinancePaymentTermRuleRow> termRules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.paymentplan", "read", actor -> plans.termRules());
  }

  /** 新建一条账期规则。 */
  @PostMapping("/finance/payment-plans/term-rules")
  public FinancePaymentTermRuleRow createTermRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinancePaymentTermRuleInput input) {
    return call(
        auth, "finance.paymentplan", "update", actor -> plans.createTermRule(input, actor));
  }

  /** 改一条账期规则。 */
  @PutMapping("/finance/payment-plans/term-rules/{id}")
  public FinancePaymentTermRuleRow updateTermRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinancePaymentTermRuleInput input) {
    return call(
        auth, "finance.paymentplan", "update", actor -> plans.updateTermRule(id, input, actor));
  }

  // ================================================================ 里程碑规则

  /** 里程碑规则清单（含里程碑明细）。 */
  @GetMapping("/finance/payment-plans/milestone-rules")
  public List<FinancePaymentMilestoneRuleRow> milestoneRules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "finance.paymentplan", "read", actor -> plans.milestoneRules());
  }

  /** 新建一套里程碑规则（整套一起落；比例之和必须为 1）。 */
  @PostMapping("/finance/payment-plans/milestone-rules")
  public FinancePaymentMilestoneRuleRow createMilestoneRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinancePaymentMilestoneRuleInput input) {
    return call(
        auth,
        "finance.paymentplan",
        "update",
        actor -> plans.createMilestoneRule(input, actor));
  }

  /** 改一套里程碑规则（里程碑整套替换）。 */
  @PutMapping("/finance/payment-plans/milestone-rules/{id}")
  public FinancePaymentMilestoneRuleRow updateMilestoneRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinancePaymentMilestoneRuleInput input) {
    return call(
        auth,
        "finance.paymentplan",
        "update",
        actor -> plans.updateMilestoneRule(id, input, actor));
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
