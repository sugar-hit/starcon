package com.starcon.channel;

import static com.starcon.channel.AdEfficiencyDiagnosisModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 14.3.2.2 效率诊断。
 *
 * <p>蓝图：「诊断投放效率问题：高花费零转化、ACOS 长期超标、预算受限、竞价过低曝光不足、创意衰退，
 * 给出可执行建议与一键调整。输入：14.3 报表指标；诊断规则。输出：诊断清单 + 建议动作。
 * 关系：上游 = 14.3.1；下游 = 14.2.1.1、14.1.3.1。<b>诊断要给动作而不是只报警</b>，否则用户看完还是要自己想怎么办。」
 * 端点按蓝图模块号标注，方便对照 14-ads-and-growth.md。
 *
 * <p>🔴 四个入口刻意分开，各自对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET/POST /rules} + {@code /rules/{id}/status} + {@code /rules/defaults} ——
 *       「诊断规则」（蓝图的输入之一）。阈值是**必填**的：没有阈值的规则永远判不出东西。
 *   <li>{@code POST /runs} + {@code GET /runs} + {@code GET /runs/{id}} ——
 *       「诊断」的执行入口。指标**全部从已交付的 14.1.1.1 事实表算**，
 *       <b>不引用 14.3.1.1 报表的任何代码</b>（它与本模块同批并行开发）。
 *   <li>{@code GET /diagnoses} + {@code GET /diagnoses/{id}} ——
 *       「诊断清单」：结论 + 观察量快照（可复算）+ 判定依据 + 可执行建议。
 *   <li>{@code GET /actions} + {@code POST /actions/{id}/apply} ——
 *       「建议动作」与「一键调整」。不可用的动作（依赖 14.2.1.1 竞价管理）**不提供**
 *       「直接执行」这条路：点了只会拿到一句「待 14.2.1.1 交付后开放」。
 * </ul>
 *
 * <p>🔴 刻意**不提供**的动作：把诊断结论直接批量推给平台（跳过 14.2.1.1 与平台回写队列）。
 * 调整只落本地并标记「待回写平台」—— 平台广告适配器未交付。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 14.1.1.1 活动列表、
 * 14.2.2.2 自动规则同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/ad-diagnostics")
public class AdEfficiencyDiagnosisController {

  private final AccessService access;
  private final AdEfficiencyDiagnosisService service;

  public AdEfficiencyDiagnosisController(AccessService access, AdEfficiencyDiagnosisService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：未交付的段（14.2.1.1 竞价管理 / 14.1.1.2 创意子资源 / 平台回写队列）
   * 显式 {@code available=false} + {@code ownerModule} + {@code impact}。
   */
  @GetMapping("/capabilities")
  public AdDiagnosisCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 诊断规则（「诊断规则」= 蓝图的输入之一）----

  /** 规则列表：真分页 + 真筛选。 */
  @GetMapping("/rules")
  public AdDiagnosisRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String ruleType,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.rules(blank(ruleType), enabled, page, size));
  }

  /**
   * 登记 / 改一条诊断规则。
   *
   * <p>🔔 阈值是**必填**的（按规则类型分别必填）：DB 层还有延迟约束触发器兜底，
   * 绕服务层直接写 SQL 同样被拒。
   */
  @PostMapping("/rules")
  public AdDiagnosisRuleRow saveRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveRuleRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveRule(request));
  }

  /** 规则详情。 */
  @GetMapping("/rules/{id}")
  public AdDiagnosisRuleRow rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.rule(id));
  }

  /** 启用 / 停用一条规则。 */
  @PostMapping("/rules/{id}/status")
  public AdDiagnosisRuleRow setRuleStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam String status) {
    return call(auth, "channel.operation.update", a -> service.setRuleStatus(id, status));
  }

  /**
   * 为本租户初始化**默认诊断规则**（幂等：已存在的不覆盖）。
   *
   * <p>🔔 默认阈值是**种子值**，落成真实库行后可以逐条改 —— 不是硬编码常量当数据源。
   */
  @PostMapping("/rules/defaults")
  public AdDiagnosisRuleDefaultsResult initDefaultRules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.create", a -> service.initDefaultRules());
  }

  // ---- 诊断运行 ----

  /** 运行历史：真分页。 */
  @GetMapping("/runs")
  public AdDiagnosisRunPage runs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.runs(page, size));
  }

  /**
   * 跑一次诊断。
   *
   * <p>🔔 指标全部从 14.1.1.1 的事实表算；观察窗口的末端取**本租户最新有数据的自然日**，
   * 不用墙钟 —— 同一份数据重复诊断结论稳定可比。
   */
  @PostMapping("/runs")
  public AdDiagnosisRunDetail run(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody(required = false) RunDiagnosisRequest request) {
    RunDiagnosisRequest payload = request == null ? new RunDiagnosisRequest(null, null, null) : request;
    return call(auth, "channel.operation.create", a -> service.run(payload));
  }

  /** 运行详情：运行头 + 逐条规则的评估结论 + 本次的诊断结论与建议动作。 */
  @GetMapping("/runs/{id}")
  public AdDiagnosisRunDetail runDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.runDetail(id));
  }

  // ---- 诊断清单 ----

  /** 诊断清单：真分页 + 真筛选。 */
  @GetMapping("/diagnoses")
  public AdDiagnosisPage diagnoses(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String ruleType,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String campaignId,
      @RequestParam(required = false) String runId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.diagnoses(
                blank(ruleType), blank(severity), blank(status), blank(campaignId), blank(runId), page, size));
  }

  /** 诊断详情：结论 + 建议动作。 */
  @GetMapping("/diagnoses/{id}")
  public AdDiagnosisDetail diagnosis(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.diagnosis(id));
  }

  // ---- 建议动作 ----

  /** 建议动作：真分页 + 真筛选（可执行的与明确不可用的都在）。 */
  @GetMapping("/actions")
  public AdDiagnosisActionPage actions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String outcome,
      @RequestParam(required = false) String actionMode,
      @RequestParam(required = false) Boolean available,
      @RequestParam(required = false) String diagnosisId,
      @RequestParam(required = false) String runId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.actions(
                blank(outcome), blank(actionMode), available, blank(diagnosisId), blank(runId), page, size));
  }

  /** 建议动作详情。 */
  @GetMapping("/actions/{id}")
  public AdDiagnosisActionRow action(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.action(id));
  }

  /**
   * 🔴 一键调整：执行一条建议动作。
   *
   * <p>🔔 预算 / 暂停走 **14.1.1.1 已交付的写路径**（单次 / 总额 / 单活动预算上限 + 操作审计 +
   * 幂等），本模块**不另写一套绕过护栏的写路径**。
   *
   * <p>🔔 不可用的动作（14.2.1.1 竞价管理未交付）**点不动**：返回 409 并写明责任模块。
   */
  @PostMapping("/actions/{id}/apply")
  public AdDiagnosisApplyResult applyAction(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody(required = false) ApplyActionRequest request) {
    ApplyActionRequest payload = request == null ? new ApplyActionRequest(null, null) : request;
    return call(auth, "channel.operation.update", a -> service.applyAction(id, payload));
  }

  // ---- 工具 ----

  /** 空串与 null 同义，避免把 {@code ?ruleType=} 当成「筛选空字符串」。 */
  private static String blank(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
