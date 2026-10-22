package com.starcon.crm;

import static com.starcon.crm.AutoReplyModels.*;

import com.starcon.crm.AutoReplyModels.CrmAutoReplyCapabilities;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyDutyCalendarInput;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyDutyCalendarPage;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyDutyCalendarRow;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyEscalateInput;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyEscalationPage;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyEscalationRow;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyRecordPage;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyRecordRow;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyRuleInput;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyRulePage;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyRuleRow;
import com.starcon.crm.AutoReplyModels.CrmAutoReplySettingRow;
import com.starcon.crm.AutoReplyModels.CrmAutoReplySettingToggleInput;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyStats;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyTakeoverInput;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyTakeoverPage;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyTakeoverRow;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyTriggerInput;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyTriggerResult;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 15.5.1.1 自动回复。
 *
 * <p>命名空间 {@code /crm/auto-replies}：前端入口同名。
 * 与同服务的 {@code /crm/message-sends}（15.1.1.2 发送）、{@code /crm/message-classifications}
 * （15.1.1.3 分类）、{@code /crm/care-reminders}（15.5.2.1 关怀催办）<b>刻意不共用命名空间</b>：
 * 本模块管「自动发出去的那一条」与「要不要转人工」，不碰发送与分类本身。
 *
 * <p><b>裸路径 {@code /crm/auto-replies} 刻意不是一个端点</b>，所以白名单里不放行它。
 * 浏览器只经 {@code /api/crm} 代理访问；后端路径按<b>服务</b>命名空间走，
 * 与 catalog（/catalog/...）、trade（/trade/...）同一纪律。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code crm.auto_reply.read} / {@code crm.auto_reply.update}：
 * 与同服务兄弟模块（{@code crm.message.*} / {@code crm.care_reminder.*}）同一命名法，
 * identity 服务<b>零改动</b>。
 */
@RestController
@RequestMapping("/crm/auto-replies")
public class AutoReplyController {

  private final AccessService access;
  private final AutoReplyService autoReplies;

  public AutoReplyController(AccessService access, AutoReplyService autoReplies) {
    this.access = access;
    this.autoReplies = autoReplies;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm.auto_reply." + permission);
    return access.run(actor, "crm.auto_reply." + permission, () -> work.apply(actor));
  }

  /** 无返回值的统一入口（与上面同一个守卫，只是没有结果）。 */
  private void callVoid(String bearer, String permission, java.util.function.Consumer<AccessService.Access> work) {
    var actor = access.require(bearer, "crm.auto_reply." + permission);
    access.run(
        actor,
        "crm.auto_reply." + permission,
        () -> {
          work.accept(actor);
          return null;
        });
  }

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  @GetMapping("/capabilities")
  public CrmAutoReplyCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> autoReplies.capabilities());
  }

  // ------------------------------------------------------------ 全局开关

  /** 全局开关（🔴 一键关闭的现状）。 */
  @GetMapping("/settings")
  public CrmAutoReplySettingRow settings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> autoReplies.settings());
  }

  /**
   * 🔴 一键开关（蓝图加粗句「必须可一键关闭」）。
   *
   * <p>关闭时服务端会**同时**把已排队待发的记录逐条撤销 —— 不得在关闭后继续发。
   * 关闭必须带原因（服务端强制）。
   */
  @PostMapping("/settings/toggle")
  public CrmAutoReplySettingRow toggleSetting(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmAutoReplySettingToggleInput input) {
    return call(auth, "update", a -> autoReplies.toggleSetting(input, a.userId()));
  }

  // ------------------------------------------------------------ 自动回复规则

  /** 规则列表（真分页 + 真筛选）。 */
  @GetMapping("/rules")
  public CrmAutoReplyRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String triggerType,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) String ladderCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        a ->
            autoReplies.rules(
                triggerType, platformCode, enabled, ladderCode, keyword, page, size));
  }

  /** 单条规则。 */
  @GetMapping("/rules/{ruleId}")
  public CrmAutoReplyRuleRow rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId) {
    return call(auth, "read", a -> autoReplies.rule(ruleId));
  }

  /** 新建一条规则（= 阶梯的一级）。 */
  @PostMapping("/rules")
  public CrmAutoReplyRuleRow createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmAutoReplyRuleInput input) {
    return call(auth, "update", a -> autoReplies.createRule(input, a.userId()));
  }

  /** 改规则（不回写历史记录 —— 记录只追加，且快照了当时的话术）。 */
  @PutMapping("/rules/{ruleId}")
  public CrmAutoReplyRuleRow updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody CrmAutoReplyRuleInput input) {
    return call(auth, "update", a -> autoReplies.updateRule(ruleId, input, a.userId()));
  }

  // ------------------------------------------------------------ 值班日历

  /** 值班日历列表（真分页 + 真筛选）。 */
  @GetMapping("/calendar")
  public CrmAutoReplyDutyCalendarPage calendar(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scope,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> autoReplies.calendar(scope, platformCode, from, to, page, size));
  }

  /** 新建一条值班日历（节假日 / 非营业时段安排）。 */
  @PostMapping("/calendar")
  public CrmAutoReplyDutyCalendarRow createCalendar(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmAutoReplyDutyCalendarInput input) {
    return call(auth, "update", a -> autoReplies.createCalendar(input, a.userId()));
  }

  /** 删除一条值班日历（它是配置不是事实，可以删）。 */
  @DeleteMapping("/calendar/{entryId}")
  public void deleteCalendar(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID entryId) {
    callVoid(auth, "update", a -> autoReplies.deleteCalendar(entryId));
  }

  // ------------------------------------------------------------ 触发

  /**
   * 触发一次自动回复评估。
   *
   * <p>⚠️ 平台站内信同步（15.1.1.1 的 PLATFORM_SYNC）<b>未接入</b> ⇒ 本入口由调用方
   * <b>显式</b>调用；平台适配器接入后由它调用<b>同一个</b>入口。
   *
   * <p>🔴 全局关闭时服务端**不评估、不写任何记录**，只在结果里说清关闭原因。
   */
  @PostMapping("/trigger")
  public CrmAutoReplyTriggerResult trigger(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmAutoReplyTriggerInput input) {
    return call(auth, "update", a -> autoReplies.trigger(input, a.userId(), auth));
  }

  // ------------------------------------------------------------ 自动回复记录

  /** 自动回复记录列表（真分页 + 真筛选）。 */
  @GetMapping("/records")
  public CrmAutoReplyRecordPage records(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String outcome,
      @RequestParam(required = false) String triggerType,
      @RequestParam(required = false) UUID ruleId,
      @RequestParam(required = false) UUID messageId,
      @RequestParam(required = false) String ladderCode,
      @RequestParam(required = false) Boolean escalated,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        a ->
            autoReplies.records(
                outcome,
                triggerType,
                ruleId,
                messageId,
                ladderCode,
                escalated,
                keyword,
                from,
                to,
                page,
                size));
  }

  /** 单条自动回复记录。 */
  @GetMapping("/records/{recordId}")
  public CrmAutoReplyRecordRow record(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID recordId) {
    return call(auth, "read", a -> autoReplies.record(recordId));
  }

  // ------------------------------------------------------------ 转人工标记

  /** 转人工标记列表（真分页 + 真筛选）。 */
  @GetMapping("/escalations")
  public CrmAutoReplyEscalationPage escalations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String reasonCode,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) Boolean takenOver,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "read", a -> autoReplies.escalations(reasonCode, shopId, takenOver, keyword, page, size));
  }

  /** 人工手动标记转人工（原因必填；一条消息只转人工一次）。 */
  @PostMapping("/records/{recordId}/escalate")
  public CrmAutoReplyEscalationRow escalate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID recordId,
      @Valid @RequestBody CrmAutoReplyEscalateInput input) {
    return call(auth, "update", a -> autoReplies.escalate(recordId, input, a.userId()));
  }

  // ------------------------------------------------------------ 人工接管

  /** 人工接管标记列表（真分页 + 真筛选）。 */
  @GetMapping("/takeovers")
  public CrmAutoReplyTakeoverPage takeovers(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String buyerReference,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "read", a -> autoReplies.takeovers(shopId, buyerReference, keyword, page, size));
  }

  /**
   * 🔴 标记人工接管（蓝图加粗句「留出人工接管」）。
   *
   * <p>接管后**同一会话不再自动回复**；原因必填；同一个会话重复接管返回既有行。
   */
  @PostMapping("/takeovers")
  public CrmAutoReplyTakeoverRow takeover(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmAutoReplyTakeoverInput input) {
    return call(auth, "update", a -> autoReplies.takeover(input, a.userId()));
  }

  // ------------------------------------------------------------ 统计

  /** 统计汇总（**服务端算**：页面不重算、不做算术）。 */
  @GetMapping("/stats")
  public CrmAutoReplyStats stats(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return call(auth, "read", a -> autoReplies.stats(from, to));
  }
}
