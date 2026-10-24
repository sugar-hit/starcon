package com.starcon.finance;

import static com.starcon.finance.AccountingPeriodModels.*;

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
 * 12.5.1.2 会计期间的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/accounting-periods}</b>：首 token 与既有 finance 端点
 * （{@code receivables} / {@code settlement} / {@code transactions} / {@code vouchers} …）全不相同，
 * 免得「期间」被别的前缀吃掉。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.accounting_period.read / .create / .update}：
 * 关账与重开都不是读权限能带过去的动作。
 *
 * <p>🔴 <b>没有 DELETE 端点</b>：期间是会计事实，错了只能关账或重开（DB 触发器也钉死了）。
 */
@RestController
@RequestMapping("/finance/accounting-periods")
public class AccountingPeriodController {

  private final AccessService access;
  private final AccountingPeriodService periods;

  public AccountingPeriodController(AccessService access, AccountingPeriodService periods) {
    this.access = access;
    this.periods = periods;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.accounting_period." + permission);
    return access.run(actor, "finance.accounting_period." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceAccountingPeriodCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> periods.capabilities());
  }

  /** 筛选项下拉（全部来自真实数据）。 */
  @GetMapping("/options")
  public FinanceAccountingPeriodOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> periods.options());
  }

  // ================================================================ 会计日历

  /** 会计日历清单。 */
  @GetMapping("/calendars")
  public List<FinanceAccountingCalendarRow> calendars(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> periods.calendars());
  }

  /** 日历详情：日历 + 它生成的期间。 */
  @GetMapping("/calendars/{id}")
  public FinanceAccountingCalendarDetail calendarDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> periods.calendarDetail(id));
  }

  /** 建会计日历（自然月 / 4-4-5 / 自定义）。 */
  @PostMapping("/calendars")
  public FinanceAccountingCalendarRow createCalendar(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceAccountingCalendarInput input) {
    return call(auth, "create", actor -> periods.createCalendar(input, actor));
  }

  /** 改会计日历（⚠️ 账期形状不可改 —— 只有名称 / 状态 / 备注可改）。 */
  @PutMapping("/calendars/{id}")
  public FinanceAccountingCalendarRow updateCalendar(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceAccountingCalendarUpdateInput input) {
    return call(auth, "update", actor -> periods.updateCalendar(id, input, actor));
  }

  /**
   * 从会计日历**生成**一个财年的期间（自然月 12 期 / 4-4-5 12 个 13 周周期）。
   *
   * <p>⚠️ 已存在的期间编码被跳过而不是覆盖，被跳过的编码逐个报出来。
   */
  @PostMapping("/calendars/{id}/generate-periods")
  public FinanceAccountingPeriodGenerateResult generatePeriods(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceAccountingPeriodGenerateInput input) {
    return call(auth, "create", actor -> periods.generatePeriods(id, input, actor));
  }

  // ================================================================ 期间

  /** 期间列表：真分页 + 真筛选 + 状态汇总（汇总覆盖整个筛选结果集）。 */
  @GetMapping
  public FinanceAccountingPeriodPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String periodType,
      @RequestParam(required = false) UUID calendarId,
      @RequestParam(required = false) Integer fiscalYear,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            periods.page(
                new AccountingPeriodQuery(
                    enumOf(status, AccountingPeriodStatus.class, "期间状态"),
                    enumOf(periodType, AccountingPeriodType.class, "期间类型"),
                    calendarId,
                    fiscalYear,
                    keyword,
                    page,
                    size)));
  }

  /** 手工登记一个自定义期间（不绑定会计日历 ⇒ 没有 fiscal 周期）。 */
  @PostMapping
  public FinanceAccountingPeriodRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceAccountingPeriodInput input) {
    return call(auth, "create", actor -> periods.create(input, actor));
  }

  /** 期间详情：期间 + 关账记录（事件留痕）+ 审批链路。 */
  @GetMapping("/{id}")
  public FinanceAccountingPeriodDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> periods.detail(id));
  }

  /** 改期间备注（⚠️ 期间身份字段不可改）。 */
  @PutMapping("/{id}")
  public FinanceAccountingPeriodRow update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceAccountingPeriodUpdateInput input) {
    return call(auth, "update", actor -> periods.update(id, input, actor));
  }

  /**
   * **关闭**一个会计期间。
   *
   * <p>🔴 必须写明关闭人 / 关闭时刻 / 关闭原因（DB 守卫钉死）。
   */
  @PostMapping("/{id}/close")
  public FinanceAccountingPeriodRow close(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceAccountingPeriodCloseInput input) {
    return call(auth, "update", actor -> periods.close(id, input, actor));
  }

  /**
   * **重开**一个已关闭的会计期间 —— 高风险操作，必须审批留痕。
   *
   * <p>🔴 审批属 17 审批（identity）：本模块**不造第二套审批引擎**，只通过授权 HTTP API
   * 申请与读取审批状态。审批不可达时本调用**失败**、期间保持关闭 —— 绝不假装批过了。
   */
  @PostMapping("/{id}/reopen")
  public FinanceAccountingPeriodReopenResult reopen(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceAccountingPeriodReopenInput input) {
    var actor = access.require(auth, "finance.accounting_period.update");
    return access.run(
        actor, "finance.accounting_period.update", () -> periods.reopen(id, input, actor, auth));
  }

  /** 期间事件留痕（＝ 蓝图输出里的「关账记录」）。 */
  @GetMapping("/{id}/events")
  public List<FinanceAccountingPeriodEventRow> events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> periods.events(id));
  }

  /** 期间的审批链路（identity 链路的本地镜像）。 */
  @GetMapping("/{id}/approvals")
  public List<FinanceAccountingPeriodApprovalRow> approvals(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> periods.approvals(id));
  }

  // ================================================================ 工具

  /** 词表解析：非法取值给一句能直接展示的话，而不是一个枚举名。 */
  private static <T extends Enum<T>> T enumOf(String raw, Class<T> type, String label) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return Enum.valueOf(type, raw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, label + "取值不合法：" + raw);
    }
  }
}
