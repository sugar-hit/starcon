package com.starcon.channel;

import static com.starcon.channel.ComplianceInterceptModels.*;

import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 16.2.1.2 拦截执行。
 *
 * <p>蓝图：「在关键业务节点执行合规校验并阻断：刊登发布前、发货前、结算提现前；
 * 展示拦截原因与解除方式（提交资质、申请豁免）。输入：合规规则集；业务动作触发的校验请求（商品/订单/店铺）。
 * 输出：校验结论（通过/警告/阻断）+ 原因 + 解除入口；豁免申请与审批。
 * 关系：上游 = 16.2.1.1；下游 = 4.3.1.1 发布、8.3.4.1 发货、12 提现。
 * <b>豁免必须有审批与理由</b>，否则阻断会被随意绕过。」
 *
 * <p>🔴 端点清单：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：值域、结论口径、未交付下游逐条列出。
 *   <li>{@code GET /policy} —— 拦截策略清单（三个拦截点各一条）。
 *   <li>{@code PUT /policy/{scene}} —— 🔴 改策略（**拦截默认不生效**，这里才开）。
 *   <li>{@code POST /decisions} —— 🔴 <b>判定端点</b>：跑一次校验，返回
 *       {@code blocked} + {@code releaseGranted} + 逐条原因 + 解除方式。
 *   <li>{@code GET /decisions} —— 判定历史（**真分页 + 真筛选**）。
 *   <li>{@code GET /decisions/{id}} —— 单次判定详情（含逐条原因）。
 *   <li>{@code POST /exemptions} —— 申请豁免（**理由必填 + 有效期必填**）。
 *   <li>{@code GET /exemptions} —— 豁免单清单（**真分页 + 真筛选**）。
 *   <li>{@code GET /exemptions/{id}} —— 豁免单详情。
 *   <li>{@code POST /exemptions/{id}/approve} —— 🔴 审批（**申请人 ≠ 审批人**）。
 *   <li>{@code POST /exemptions/{id}/reject} —— 🔴 驳回（**申请人 ≠ 审批人**）。
 * </ul>
 *
 * <p>🔴 <b>刻意没有的形状</b>：没有「删除豁免」「撤回豁免」「改判定结果」的端点 ——
 * 判定凭据只追加，豁免单是终态不可回退的证据。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（读 / 录 / 改），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/compliance-intercepts")
public class ComplianceInterceptController {

  private final AccessService access;
  private final ComplianceInterceptService service;

  public ComplianceInterceptController(AccessService access, ComplianceInterceptService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：值域、结论口径（**三态 + 一个「判不了」**）、未交付下游逐条列出。 */
  @GetMapping("/capabilities")
  public ComplianceInterceptCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 策略 ----

  /** 拦截策略清单（三个拦截点各一条；没配置过的按默认值返回）。 */
  @GetMapping("/policy")
  public ComplianceInterceptPolicySet policy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.policy());
  }

  /** 🔴 改拦截策略（**拦截默认不生效**，这里才开；「判不了」时失败关闭还是放行也在这里定）。 */
  @PutMapping("/policy/{scene}")
  public ComplianceInterceptPolicyRow savePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String scene,
      @RequestBody ComplianceInterceptPolicyInput input) {
    return call(
        auth,
        "channel.operation.update",
        a -> service.savePolicy(scene, input, a.userId()));
  }

  // ---- 判定 ----

  /**
   * 🔴 <b>判定端点</b>：跑一次合规校验并落库判定凭据。
   *
   * <p>返回 {@code blocked}（是不是被阻断）+ {@code releaseGranted}（能不能放行）
   * + 逐条原因 + 解除方式。
   */
  @PostMapping("/decisions")
  public ComplianceInterceptDecision evaluate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ComplianceInterceptEvaluateInput input) {
    return call(auth, "channel.operation.create", a -> service.evaluate(input, a.userId()));
  }

  /** 判定历史（**真分页 + 真筛选**）。 */
  @GetMapping("/decisions")
  public ComplianceInterceptDecisionPage decisions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scene,
      @RequestParam(required = false) String subjectType,
      @RequestParam(required = false) String conclusion,
      @RequestParam(required = false) String subjectId,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.decisions(scene, subjectType, conclusion, subjectId, shopId, page, size));
  }

  /** 单次判定详情（含逐条原因 + 解除方式）。 */
  @GetMapping("/decisions/{id}")
  public ComplianceInterceptDecision decision(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.decision(id));
  }

  // ---- 豁免 ----

  /** 🔴 申请豁免（**理由必填 + 有效期必填**；一张豁免只针对一条规则）。 */
  @PostMapping("/exemptions")
  public ComplianceInterceptExemptionRow applyExemption(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ComplianceInterceptExemptionInput input) {
    return call(auth, "channel.operation.create", a -> service.applyExemption(input, a.userId()));
  }

  /** 豁免单清单（**真分页 + 真筛选**）。 */
  @GetMapping("/exemptions")
  public ComplianceInterceptExemptionPage exemptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scene,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String subjectType,
      @RequestParam(required = false) String subjectId,
      @RequestParam(required = false) String ruleCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.exemptions(scene, status, subjectType, subjectId, ruleCode, page, size));
  }

  /** 豁免单详情。 */
  @GetMapping("/exemptions/{id}")
  public ComplianceInterceptExemptionRow exemption(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.exemption(id));
  }

  /** 🔴 审批豁免（**申请人 ≠ 审批人**；审批意见必填；不能批一张已过期的豁免）。 */
  @PostMapping("/exemptions/{id}/approve")
  public ComplianceInterceptExemptionRow approveExemption(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody ComplianceInterceptDecisionInput input) {
    return call(
        auth,
        "channel.operation.update",
        a -> service.approveExemption(id, input == null ? null : input.note(), a.userId()));
  }

  /** 🔴 驳回豁免（**申请人 ≠ 审批人**；审批意见必填）。 */
  @PostMapping("/exemptions/{id}/reject")
  public ComplianceInterceptExemptionRow rejectExemption(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody ComplianceInterceptDecisionInput input) {
    return call(
        auth,
        "channel.operation.update",
        a -> service.rejectExemption(id, input == null ? null : input.note(), a.userId()));
  }
}
