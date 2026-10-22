package com.starcon.crm;

import static com.starcon.crm.CareReminderModels.*;

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
 * 15.5.2.1 关怀与催办。
 *
 * <p>命名空间 {@code /crm/care-reminders}：浏览器只经 {@code /api/crm} 代理访问；
 * 后端路径按<b>服务</b>命名空间走，与 catalog（/catalog/...）、trade（/trade/...）同一纪律。
 *
 * <p>⚠️ 与同卷兄弟模块（{@code /crm/customer-contacts} 15.3.2.2 接触记录、
 * {@code /crm/customer-tags} 15.3.2.1 客户标签、{@code /crm/message-inbox} 15.1.1.1 消息收件箱、
 * {@code /crm/buyer-emails} 15.1.2.1 买家邮件）<b>刻意不共用命名空间</b>：
 * 那些回答的是「我们与这位客户接触过没有」「这位买家属于哪一类人」，
 * 本模块回答的是「<b>什么时候、对谁、发什么、频次是否超限、退订是否拦截</b>」。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>🔴 权限码用 {@code crm.care_reminder.*}，与兄弟族的 {@code crm.contact.*} /
 * {@code crm.tag.*} / {@code crm.message.*} 同一命名习惯，<b>identity 服务零改动</b>。
 */
@RestController
@RequestMapping("/crm/care-reminders")
public class CareReminderController {

  private final AccessService access;
  private final CareReminderService reminders;

  public CareReminderController(AccessService access, CareReminderService reminders) {
    this.access = access;
    this.reminders = reminders;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm.care_reminder." + permission);
    return access.run(actor, "crm.care_reminder." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。
   *
   * <p>🔴 里面显式说明「本模块<b>不重造发送</b>」、平台打开/点击回执<b>未接入</b>、
   * 12 财务（催付加速回款）<b>未交付</b>，以及催付的平台白名单。
   */
  @GetMapping("/capabilities")
  public CrmCareReminderCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> reminders.capabilities());
  }

  // ================================================================ 触达规则

  /** 触达规则列表（真分页 + 真筛选）。 */
  @GetMapping("/rules")
  public CrmCareReminderRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String reminderType,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        a -> reminders.rulePage(reminderType, platformCode, status, keyword, page, size));
  }

  /** 单条触达规则。 */
  @GetMapping("/rules/{ruleId}")
  public CrmCareReminderRuleRow rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId) {
    return call(auth, "read", a -> reminders.rule(ruleId));
  }

  /**
   * 新建一条触达规则。
   *
   * <p>🔴 催付规则的平台必须在白名单里 —— 不在白名单 ⇒ 400「该平台不支持催付」，
   * 且<b>一行都不写</b>。
   */
  @PostMapping("/rules")
  public CrmCareReminderRuleRow createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmCareReminderRuleInput input) {
    return call(auth, "update", a -> reminders.createRule(input, a.userId()));
  }

  /** 修改一条触达规则（不回写历史任务）。 */
  @PutMapping("/rules/{ruleId}")
  public CrmCareReminderRuleRow updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody CrmCareReminderRuleInput input) {
    return call(auth, "update", a -> reminders.updateRule(ruleId, input, a.userId()));
  }

  /** 启用 / 停用一条触达规则。 */
  @PostMapping("/rules/{ruleId}/status")
  public CrmCareReminderRuleRow setRuleStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @RequestParam String status) {
    return call(auth, "update", a -> reminders.setRuleStatus(ruleId, status, a.userId()));
  }

  // ================================================================ 触达任务

  /** 触达任务列表（真分页 + 真筛选）。 */
  @GetMapping("/tasks")
  public CrmCareReminderTaskPage tasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID ruleId,
      @RequestParam(required = false) String reminderType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String blockCode,
      @RequestParam(required = false) String customerRef,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        a ->
            reminders.taskPage(
                ruleId, reminderType, status, blockCode, customerRef, platformCode, from, to, page,
                size));
  }

  /** 单条触达任务（含它自己的打开数与点击数）。 */
  @GetMapping("/tasks/{taskId}")
  public CrmCareReminderTaskRow task(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId) {
    return call(auth, "read", a -> reminders.task(taskId));
  }

  /**
   * 按规则生成触达任务。
   *
   * <p>🔴 每一行都过**两道闸**（退订 / 频次），任一命中 ⇒ 落成一条
   * {@code BLOCKED} 任务并写明**明确原因**（不是静默跳过）。
   */
  @PostMapping("/rules/{ruleId}/generate")
  public CrmCareReminderGenerateResult generate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @RequestBody(required = false) CrmCareReminderGenerateInput input) {
    return call(
        auth,
        "update",
        a -> reminders.generate(ruleId, input == null ? new CrmCareReminderGenerateInput(null, null, null, null, null) : input, a.userId(), auth));
  }

  /**
   * 人工交接登记发送结果（**当前唯一可用的发送路径**）。
   *
   * <p>🔴 平台侧发送接口未接入 ⇒ 本模块**不重造发送**；
   * 运营在平台侧实际发出后回本服务登记「已发出 / 失败原因 / 平台回执」。
   */
  @PostMapping("/tasks/{taskId}/dispatch")
  public CrmCareReminderTaskRow dispatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId,
      @Valid @RequestBody CrmCareReminderDispatchInput input) {
    return call(auth, "update", a -> reminders.dispatch(taskId, input, a.userId()));
  }

  // ================================================================ 统计

  /** 统计汇总（**服务端算**：总数 / 成功 / 失败 / 打开 / 点击 / 两类拦截数）。 */
  @GetMapping("/stats")
  public CrmCareReminderStats stats(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID ruleId,
      @RequestParam(required = false) String reminderType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return call(auth, "read", a -> reminders.stats(ruleId, reminderType, status, from, to));
  }

  /**
   * 某条触达任务的打开 / 点击事件。
   *
   * <p>⚠️ 平台回执**未接入** ⇒ 这里的数据只能来自 webhook 或人工录入。
   */
  @GetMapping("/stats/tasks/{taskId}/events")
  public CrmCareReminderStatEventPage statEvents(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID taskId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> reminders.statEvents(taskId, page, size));
  }

  /**
   * 录入一条打开 / 点击事件（**平台 webhook 或人工录入**）。
   *
   * <p>⚠️ 平台接口未接入时**不伪造统计数据** —— 只有显式录入才会落库。
   */
  @PostMapping("/stats/events")
  public CrmCareReminderStatEventRow recordStatEvent(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmCareReminderStatEventInput input) {
    return call(auth, "update", a -> reminders.recordStatEvent(input, a.userId()));
  }

  /**
   * 平台打开 / 点击回执的**接入状态** —— 恒「未接入」。
   *
   * <p>🔴 如实答「未接入」且<b>不写任何数据</b>。
   *
   * <p>⚠️ 刻意**不抛异常**：抛异常会走 Spring 的 {@code sendError}，响应体是空的，
   * 原因就丢了 —— 页面只剩一句「保存失败」，等于没答「未接入」。
   */
  @GetMapping("/stats/platform-events")
  public CrmCareReminderPlatformReceiptStatus platformEvents(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> reminders.platformEvents());
  }
}
