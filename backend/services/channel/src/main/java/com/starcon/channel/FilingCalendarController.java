package com.starcon.channel;

import static com.starcon.channel.FilingCalendarModels.*;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 16.3.1.2 申报日历。
 *
 * <p>蓝图：「管理各辖区申报日历：申报周期、截止日、所需数据、状态（未开始/数据就绪/已提交/已确认），
 * 到期提醒与责任人。输入：申报周期配置；辖区要求；12.6.1.2 数据就绪状态。
 * 输出：申报日历视图 + 提醒 + 状态跟踪。关系：上游 = 16.3.1.1、12.6.1.2；下游 = 1 工作台待办。
 * <b>漏报罚款远高于系统能提供的价值</b>，日历提醒是最低成本的合规保障。」
 *
 * <p>🔴 端点清单：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：上游真读得到吗、下游归谁、判定口径是什么。</li>
 *   <li>{@code GET /options} —— 筛选项（辖区选项走受控码表 + 责任人候选走 identity 成员目录）。</li>
 *   <li>{@code GET /cycle-configs} —— 申报周期配置清单（**真分页 + 真筛选**）。</li>
 *   <li>{@code POST /cycle-configs} —— 登记一份周期配置。</li>
 *   <li>{@code PUT /cycle-configs/{id}} —— 改一份配置（含撤下 status=ARCHIVED）。</li>
 *   <li>{@code GET /entries} —— 申报日历清单（**真分页 + 真筛选**，逾期/临近截止排最前）。</li>
 *   <li>{@code POST /entries} —— 登记一条申报义务。</li>
 *   <li>{@code POST /entries/generate} —— 按周期配置**批量生成**当前申报期的条目。</li>
 *   <li>{@code GET /entries/{id}} —— 单条详情。</li>
 *   <li>{@code PUT /entries/{id}} —— 改条目（责任人 / 所需数据 / 截止日 / 备注）。</li>
 *   <li>{@code POST /entries/{id}/advance} —— 推进状态（已提交 / 已确认**必须带依据**）。</li>
 *   <li>{@code POST /entries/readiness} —— 刷新数据就绪投影（读 12.6.1.2 的生效底稿）。</li>
 *   <li>{@code GET /reminders} —— 到期提醒清单（**真分页 + 真筛选 + 如实报总数**）。</li>
 *   <li>{@code POST /reminders/{id}/acknowledge} —— 确认提醒已知悉。</li>
 * </ul>
 *
 * <p>🔴 <b>刻意没有的形状</b>：没有「提交申报」端点（<b>本系统不代用户向税务机构申报</b>）、
 * 没有「生成受理号」端点（受理号是用户从税务机构拿到的**外部凭据**）、
 * 没有「工作台待办」端点（下游 1.1.1.x 未接线）——
 * <b>放行一个不存在的入口比 404 更糟</b>（界面会以为功能已交付）。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（读 / 录 / 改），
 * identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/filing-calendar")
public class FilingCalendarController {

  private final AccessService access;
  private final FilingCalendarService service;

  public FilingCalendarController(AccessService access, FilingCalendarService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  /** 需要把调用方自己的会话转发给上游（finance / identity）时用。 */
  private <T> T callWithBearer(
      String bearer, String permission, TwoArgs<AccessService.Access, String, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor, bearer));
  }

  /** 两个入参的函数（避免在这里引 java.util.function.BiFunction 的类型噪声）。 */
  @FunctionalInterface
  interface TwoArgs<A, B, T> {
    T apply(A first, B second);
  }

  // ================================================================ 能力声明

  /** 能力声明：上游真读得到吗、下游归谁、判定口径是什么。 */
  @GetMapping("/capabilities")
  public FilingCalendarCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return callWithBearer(auth, "channel.operation.read", (actor, bearer) -> service.capabilities(bearer));
  }

  // ================================================================ 选项

  /** 筛选项：辖区选项（受控码表）+ 责任人候选（identity 成员目录）+ 词表。 */
  @GetMapping("/options")
  public FilingCalendarOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return callWithBearer(auth, "channel.operation.read", (actor, bearer) -> service.options(bearer));
  }

  // ================================================================ 申报周期配置

  /** 申报周期配置清单（真分页 + 真筛选）。 */
  @GetMapping("/cycle-configs")
  public FilingCalendarCycleConfigPage cycleConfigs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String jurisdictionCode,
      @RequestParam(required = false) String taxType,
      @RequestParam(required = false) String filingCycle,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        actor -> service.cycleConfigs(jurisdictionCode, taxType, filingCycle, status, page, size));
  }

  /** 登记一份申报周期配置。 */
  @PostMapping("/cycle-configs")
  public FilingCalendarCycleConfigView createCycleConfig(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody FilingCalendarCycleConfigInput input) {
    return call(auth, "channel.operation.create", actor -> service.createCycleConfig(input));
  }

  /** 改一份申报周期配置（含撤下 status=ARCHIVED；**不做物理删除**）。 */
  @PutMapping("/cycle-configs/{id}")
  public FilingCalendarCycleConfigView updateCycleConfig(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FilingCalendarCycleConfigInput input) {
    return call(auth, "channel.operation.update", actor -> service.updateCycleConfig(id, input));
  }

  // ================================================================ 申报日历

  /** 申报日历清单（真分页 + 真筛选；**逾期 / 临近截止排最前**）。 */
  @GetMapping("/entries")
  public FilingCalendarEntryPage entries(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String jurisdictionCode,
      @RequestParam(required = false) String taxType,
      @RequestParam(required = false) String filingCycle,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String responsibleMembershipId,
      @RequestParam(required = false) Integer dueWithinDays,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return callWithBearer(
        auth,
        "channel.operation.read",
        (actor, bearer) ->
            service.entries(
                jurisdictionCode,
                taxType,
                filingCycle,
                status,
                keyword,
                responsibleMembershipId,
                dueWithinDays,
                page,
                size,
                bearer));
  }

  /** 登记一条申报义务。 */
  @PostMapping("/entries")
  public FilingCalendarEntryView createEntry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody FilingCalendarEntryInput input) {
    return callWithBearer(
        auth, "channel.operation.create", (actor, bearer) -> service.createEntry(input, actor, bearer));
  }

  /** 按周期配置批量生成当前申报期的条目。 */
  @PostMapping("/entries/generate")
  public FilingCalendarGenerateResult generateEntries(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String referenceDate) {
    return callWithBearer(
        auth,
        "channel.operation.create",
        (actor, bearer) -> service.generateEntries(referenceDate, actor, bearer));
  }

  /** 单条申报义务详情。 */
  @GetMapping("/entries/{id}")
  public FilingCalendarEntryView entry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return callWithBearer(
        auth, "channel.operation.read", (actor, bearer) -> service.entry(id, bearer));
  }

  /** 改一条申报义务（状态推进走 advance，不在这里）。 */
  @PutMapping("/entries/{id}")
  public FilingCalendarEntryView updateEntry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FilingCalendarEntryInput input) {
    return callWithBearer(
        auth, "channel.operation.update", (actor, bearer) -> service.updateEntry(id, input, actor, bearer));
  }

  /** 推进状态（**已提交 / 已确认必须带依据**：谁、何时、凭据号）。 */
  @PostMapping("/entries/{id}/advance")
  public FilingCalendarEntryView advance(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FilingCalendarAdvanceInput input) {
    return callWithBearer(
        auth, "channel.operation.update", (actor, bearer) -> service.advance(id, input, actor, bearer));
  }

  /** 刷新数据就绪投影（读 12.6.1.2 的生效底稿）。 */
  @PostMapping("/entries/readiness")
  public Map<String, Object> refreshReadiness(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return callWithBearer(
        auth,
        "channel.operation.update",
        (actor, bearer) ->
            Map.of(
                "refreshed",
                service.refreshReadiness(bearer),
                "note",
                "已按 12.6.1.2 的**生效底稿**刷新数据就绪状态。"
                    + "🔴 **「读不到」与「没就绪」是两个状态**：上游读不到的条目会标 available=false，"
                    + "界面上显示「读不到」而不是「数据没就绪」。"));
  }

  // ================================================================ 到期提醒

  /** 到期提醒清单（真分页 + 真筛选 + **如实报总数**）。 */
  @GetMapping("/reminders")
  public FilingCalendarReminderPage reminders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String jurisdictionCode,
      @RequestParam(required = false) Boolean acknowledged,
      @RequestParam(required = false) Integer withinDays,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return callWithBearer(
        auth,
        "channel.operation.read",
        (actor, bearer) ->
            service.reminders(kind, jurisdictionCode, acknowledged, withinDays, page, size, bearer));
  }

  /** 确认一条提醒「已知悉」。 */
  @PostMapping("/reminders/{id}/acknowledge")
  public FilingCalendarReminderView acknowledgeReminder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return callWithBearer(
        auth,
        "channel.operation.update",
        (actor, bearer) -> service.acknowledgeReminder(id, actor, bearer));
  }
}
