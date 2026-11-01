package com.starcon.platform;

import static com.starcon.platform.AutomationRuleEditorModels.*;

import com.starcon.platform.AutomationRuleModels.AutomationRuleDetail;
import com.starcon.platform.AutomationRuleModels.AutomationRuleInput;
import jakarta.validation.Valid;
import java.util.List;
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
 * 18.3.1.2「规则编辑器」的 HTTP 端点。
 *
 * <p>蓝图 18-integration-and-automation.md 原文：
 *
 * <blockquote>
 *
 * <b>职责</b>：可视化编辑规则：选择触发事件 → 配置条件（字段/操作符/值）→
 * 编排动作（调接口/改状态/建任务/发通知/生成单据）→ 测试与保存。<br>
 * <b>输入</b>：规则配置；测试样本数据。<br>
 * <b>输出</b>：规则定义；测试运行结果。<br>
 * <b>关系</b>：上游 = 18.3.1.1；下游 = 18.3.2 执行。<b>规则必须支持用样本测试</b>，否则只能在生产上试错。
 *
 * </blockquote>
 *
 * <p><b>命名空间是 {@code /platform/automation-rule-editor}</b>：与同服务的
 * {@code /platform/automation-rules}（规则列表 / 档案管理）、{@code /platform/apps}、
 * {@code /platform/webhooks}、{@code /platform/external-integrations} 同服务、不同前缀 ——
 * 五者管的是完全不同的东西。
 *
 * <p>🔴 <b>本模块不执行规则</b>：18.3.2「执行」是**下游**、尚未交付 ⇒
 * {@code POST /test} 只**求值**条件并给出动作计划，
 * {@code sideEffectsApplied} 恒为 false。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>权限码 {@code platform.automation_rule_editor.read / .create / .update / .test}</b>：
 * 「读目录」「保存规则」「跑一次样本测试」是**三个独立的码** ——
 * 能配规则的人不该顺手就能往测试台账里写结论（与 18.3.1.1 的
 * 「配规则 / 改规则 / 登记执行结果」三码同一纪律）。
 */
@RestController
@RequestMapping("/platform/automation-rule-editor")
public class AutomationRuleEditorController {

  private final AccessService access;
  private final AutomationRuleEditorService editor;

  public AutomationRuleEditorController(AccessService access, AutomationRuleEditorService editor) {
    this.access = access;
    this.editor = editor;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.automation_rule_editor." + permission);
    return access.run(actor, "platform.automation_rule_editor." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与目录

  /** 能力声明：哪些真交付、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public AutomationRuleEditorCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> editor.capabilities());
  }

  /**
   * 🔴 **触发事件的字段目录**（每个已登记事件一份）。
   *
   * <p>字段来自事件注册表登记的 {@code payload_schema}（JSON Schema **摊平**）——
   * 不是本模块写死的一张清单：事件换了 schema，目录跟着变。
   */
  @GetMapping("/event-schemas")
  public List<AutomationRuleEditorEventSchema> eventSchemas(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> editor.eventSchemas());
  }

  /** 一个触发事件的字段目录（按事件类型 + 版本）。 */
  @GetMapping("/event-schemas/{eventType}")
  public AutomationRuleEditorEventSchema eventSchema(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String eventType,
      @RequestParam(required = false) Integer schemaVersion) {
    return call(auth, "read", actor -> editor.eventSchema(eventType, schemaVersion));
  }

  /** 🔴 **动作类型目录**（蓝图点名的五类：调接口 / 改状态 / 建任务 / 发通知 / 生成单据）。 */
  @GetMapping("/action-catalog")
  public AutomationRuleEditorActionCatalog actionCatalog(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> editor.actionCatalog());
  }

  /**
   * 🔴 **「调接口」动作可选的目标接口**（来自 18.1.1.2 的接口目录 = 各服务活 spec 的投影）。
   *
   * <p>目录读不到时 {@code available=false} 且 {@code note} 写明原因 ——
   * **不假装校验过**。
   */
  @GetMapping("/api-catalog")
  public AutomationRuleEditorApiCatalog apiCatalog(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> editor.apiCatalog());
  }

  // ================================================================ 保存（委托 18.3.1.1）

  /**
   * 🔴 **保存规则定义** —— 委托 18.3.1.1 的登记入口。
   *
   * <p>编辑器**不另建规则表**：条件 / 动作 / 启用范围三张表的写入所有者始终是 18.3.1.1。
   */
  @PostMapping("/save")
  public AutomationRuleDetail save(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AutomationRuleInput input) {
    return call(auth, "create", actor -> editor.save(input, actor));
  }

  // ================================================================ 样本测试

  /**
   * 🔴 **样本测试**：用样本载荷**真的**求值条件，给出动作计划，落测试台账。
   *
   * <p>蓝图加粗句：「<b>规则必须支持用样本测试</b>，否则只能在生产上试错。」
   *
   * <p>🔴 <b>不产生任何副作用</b>：18.3.2 执行引擎尚未交付 ⇒ 只求值、只给计划。
   */
  @PostMapping("/test")
  public AutomationRuleEditorTestResult test(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AutomationRuleEditorTestInput input) {
    return call(auth, "test", actor -> editor.test(input, actor));
  }

  /** 测试历史（只追加台账）；给 {@code ruleId} 时只看那一条规则的。 */
  @GetMapping("/test-runs")
  public List<AutomationRuleEditorTestRunRow> testRuns(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID ruleId,
      @RequestParam(required = false, defaultValue = "50") Integer limit) {
    return call(auth, "read", actor -> editor.testRuns(ruleId, limit == null ? 50 : limit));
  }
}
