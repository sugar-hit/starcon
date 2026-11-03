package com.starcon.platform;

import static com.starcon.platform.AutomationRuleRunModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 18.3.2.1「执行日志」的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/automation/runs}</b>：与兄弟模块的
 * {@code /platform/automation-rules}（18.3.1.1 规则档案）、
 * {@code /platform/automation-rule-editor}（18.3.1.2 规则编辑器）
 * <b>首 token 就不同</b>（{@code automation/} vs {@code automation-rules} vs
 * {@code automation-rule-editor}）⇒ 三个模块互不命中、proxy 白名单分得开。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>权限码 {@code platform.automation_rule_run.read / .record / .rerun}</b>：
 * 「看执行日志」「登记一次执行」「手动重跑」是**三个独立的码** ——
 * 能看台账的人不该因此就能让系统替他改价、建单。
 *
 * <p>🔴 <b>本模块不执行规则</b>：18.3.2「执行」尚未交付 ⇒
 * {@code /runs/{id}/rerun} 只**落成一条待执行记录**，
 * {@code outcome = NOT_ATTEMPTED}，**绝不伪造「已重跑成功」**。
 */
@RestController
@RequestMapping("/platform/automation/runs")
public class AutomationRuleRunController {

  private final AccessService access;
  private final AutomationRuleRunService runs;

  public AutomationRuleRunController(AccessService access, AutomationRuleRunService runs) {
    this.access = access;
    this.runs = runs;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.automation_rule_run." + permission);
    return access.run(actor, "platform.automation_rule_run." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与概览

  /** 能力声明：哪些真交付、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public AutomationRuleRunCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> runs.capabilities());
  }

  /** 概览：几个数（含「未执行」这一档 —— 执行器未接通 ⇒ 重跑请求都落在这里）。 */
  @GetMapping("/overview")
  public AutomationRuleRunOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> runs.overview());
  }

  // ================================================================ 执行日志

  /**
   * 执行日志列表（**真分页 + 真筛选**）。
   *
   * <p>筛选维度：规则 / 结论 / 来源 / 事件类型 / 事件 id / 试运行 / 关键词 / 时间区间。
   */
  @GetMapping
  public AutomationRuleRunPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID ruleId,
      @RequestParam(required = false) String outcome,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) String eventType,
      @RequestParam(required = false) String eventId,
      @RequestParam(required = false) Boolean dryRun,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        actor ->
            runs.runs(
                ruleId, outcome, source, eventType, eventId, dryRun, keyword, from, to, page,
                size));
  }

  /**
   * 一次执行的详情（**含逐步明细** ⇒ 失败原因可以从 run 直接下钻到 step）。
   */
  @GetMapping("/{id}")
  public AutomationRuleRunDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> runs.runDetail(id));
  }

  /**
   * 🔴 **登记一次执行** —— 执行日志的唯一写入口。
   *
   * <p>🔴 **幂等**：同一规则 + 同一 {@code eventId} 已经登记过 ⇒ <b>409</b>，
   * 消息里点名那一条既有 run 的 id。**绝不**凭空多出一条 run 并宣称已执行。
   */
  @PostMapping
  public ResponseEntity<AutomationRuleRunDetail> record(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AutomationRuleRunInput input) {
    return call(auth, "record", actor -> ResponseEntity.ok(runs.recordRun(input, actor)));
  }

  /**
   * 🔴 **手动重跑** —— 落成**新的一条**执行记录，与原 run 显式关联。
   *
   * <p>🔴 本仓**没有**规则执行引擎 ⇒ 新记录落成 {@code outcome = NOT_ATTEMPTED}
   * + 写清未接通原因，**绝不伪造「已重跑成功」**。
   */
  @PostMapping("/{id}/rerun")
  public ResponseEntity<AutomationRuleRunRerunResult> rerun(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody AutomationRuleRunRerunInput input) {
    return call(auth, "rerun", actor -> ResponseEntity.ok(runs.rerun(id, input, actor)));
  }
}
