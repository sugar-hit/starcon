package com.starcon.platform;

import static com.starcon.platform.AutomationRuleEffectModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
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
 * 18.3.2.2「规则效果」的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/automation/effect}</b>：与兄弟模块的
 * {@code /platform/automation/runs}（18.3.2.1 执行日志）、
 * {@code /platform/automation-rules}（18.3.1.1 规则档案）、
 * {@code /platform/automation-rule-editor}（18.3.1.2 规则编辑器）
 * <b>首 token 就不同</b> ⇒ 四个模块互不命中、proxy 白名单分得开。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>权限码 {@code platform.automation_rule_effect.read / .feedback / .configure}</b>：
 * 「看报表」「登记误触发反馈」「配置口径与建议」是**三个独立的码** ——
 * 能随手点两下登记反馈的人，不该因此就能改掉整个租户的节省工时口径。
 *
 * <p>🔴 <b>本模块不做「关闭规则」动作</b>：蓝图说「误触发率高的规则要能快速定位并关闭」——
 * 「<b>关闭</b>」这个动作归**下游 18.3.1.1 规则档案**（那里有 enable / disable 端点）。
 * 本模块只负责「<b>快速定位</b>」：返回规则 id + 规则名快照 + 指向规则档案的引用，
 * 并在响应与页面上写清「关闭动作归 18.3.1.1，本模块不做」。
 */
@RestController
@RequestMapping("/platform/automation/effect")
public class AutomationRuleEffectController {

  private final AccessService access;
  private final AutomationRuleEffectService effects;

  public AutomationRuleEffectController(AccessService access, AutomationRuleEffectService effects) {
    this.access = access;
    this.effects = effects;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.automation_rule_effect." + permission);
    return access.run(actor, "platform.automation_rule_effect." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与概览

  /**
   * 能力声明：哪些真交付、哪些还没交付、归谁。
   *
   * <p>🔴 规则执行引擎如实答「<b>未交付</b>」 —— 本模块统计的是<b>记录</b>不是<b>真实执行</b>。
   */
  @GetMapping("/capabilities")
  public AutomationRuleEffectCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> effects.capabilities());
  }

  /**
   * 规则效果概览：触发次数 / 五档结论 / 成功率 / 平均耗时 / 节省工时预估 / 误触发 / 建议。
   *
   * <p>🔴 「未执行」单列一档，既不算成功也不算失败，<b>不进</b>成功率的母。
   */
  @GetMapping("/overview")
  public AutomationRuleEffectOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> effects.overview());
  }

  // ================================================================ 按规则的效果明细

  /**
   * 按规则的效果明细（**真分页 + 真筛选 + 真排序**）。
   *
   * <p>筛选维度：关键词 / 只看有误触发的 / 只看有待处理建议的 / 误触发率门槛 /
   * 只看建议关闭的 / 排序。
   */
  @GetMapping("/rules")
  public AutomationRuleEffectRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Boolean onlyFalseTrigger,
      @RequestParam(required = false) Boolean onlyWithAdvice,
      @RequestParam(required = false) String falseTriggerRateAtLeast,
      @RequestParam(required = false) String adviseDisableOnly,
      @RequestParam(required = false) String order,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        actor ->
            effects.rules(
                keyword,
                onlyFalseTrigger,
                onlyWithAdvice,
                falseTriggerRateAtLeast,
                adviseDisableOnly,
                order,
                page,
                size));
  }

  /**
   * 单条规则的效果详情（聚合 + 口径 + 反馈 + 建议 + 可下钻的执行记录）。
   *
   * <p>🔴 详情里带 {@code targetRef}（指向 18.3.1.1 规则档案那条规则）——
   * 「快速定位」靠它，但**关闭动作不在这里**。
   */
  @GetMapping("/rules/{ruleId}")
  public AutomationRuleEffectRuleDetail ruleDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId) {
    return call(auth, "read", actor -> effects.ruleDetail(ruleId));
  }

  // ================================================================ 执行记录下钻

  /**
   * 执行记录下钻（**真分页 + 真筛选**）—— 报表里的每个数字都能追到这里。
   *
   * <p>🔴 这是**只读** 18.3.2.1 的台账：本模块**一个字都不写**那两张表。
   */
  @GetMapping("/runs")
  public AutomationRuleEffectRunPage runs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID ruleId,
      @RequestParam(required = false) String outcome,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> effects.runs(ruleId, outcome, keyword, from, to, page, size));
  }

  // ================================================================ 节省工时口径

  /** 口径列表（**真分页**）。 */
  @GetMapping("/baselines")
  public AutomationRuleEffectBaselinePage baselines(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID ruleId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> effects.baselines(ruleId, page, size));
  }

  /**
   * 🔴 **配置 / 更新一条规则的节省工时口径**（人工基线）。
   *
   * <p>🔴 口径必须写清来源与算法 —— 只填一个分钟数不说怎么来的，这个数不可用。
   * <p>🔴 一条规则一条**当前**口径：重复提交是 upsert，不会堆出一串历史行。
   */
  @PutMapping("/baselines")
  public ResponseEntity<AutomationRuleEffectBaselineRow> upsertBaseline(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AutomationRuleEffectBaselineInput input) {
    return call(auth, "configure", actor -> ResponseEntity.ok(effects.upsertBaseline(input, actor)));
  }

  // ================================================================ 误触发反馈

  /** 误触发反馈列表（**真分页 + 真筛选**）。 */
  @GetMapping("/feedbacks")
  public AutomationRuleEffectFeedbackPage feedbacks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID ruleId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String falseTriggerType,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        actor -> effects.feedbacks(ruleId, status, falseTriggerType, keyword, page, size));
  }

  /**
   * 🔴 **登记一条误触发反馈** —— 误触发反馈的唯一写入口。
   *
   * <p>🔴 反馈**必须能定位**：带 {@code ruleId}；带 {@code runId} 时会校验那次执行
   * **确实属于这条规则**（否则反馈会算到别的规则头上）。
   */
  @PostMapping("/feedbacks")
  public ResponseEntity<AutomationRuleEffectFeedbackRow> recordFeedback(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AutomationRuleEffectFeedbackInput input) {
    return call(auth, "feedback", actor -> ResponseEntity.ok(effects.recordFeedback(input, actor)));
  }

  /**
   * 🔴 **处理一条误触发反馈**（标成「已采纳」或「已忽略」）。
   *
   * <p>🔴 反馈**不是只追加台账** —— 它会被人处理掉，处理结果要能落下来。
   * <p>🔴 **状态不影响误触发率**：一条被「已忽略」的反馈仍然是发生过的误触发，
   * 把它从误触发率里抹掉等于让报表说谎。
   */
  @PutMapping("/feedbacks/{id}/status")
  public ResponseEntity<AutomationRuleEffectFeedbackRow> setFeedbackStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam String status) {
    return call(auth, "feedback", actor -> ResponseEntity.ok(effects.setFeedbackStatus(id, status)));
  }

  // ================================================================ 规则优化建议

  /** 优化建议列表（**真分页 + 真筛选**）。 */
  @GetMapping("/advices")
  public AutomationRuleEffectAdvicePage advices(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID ruleId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String adviceType,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) Boolean onlyActive,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        actor ->
            effects.advices(
                ruleId, status, adviceType, severity, source, onlyActive, keyword, page, size));
  }

  /**
   * 🔴 **登记一条人工优化建议**。
   *
   * <p>🔴 系统重新生成建议时**不动**人工建议（{@code source = 'MANUAL'} 的行）。
   */
  @PostMapping("/advices")
  public ResponseEntity<AutomationRuleEffectAdviceRow> recordAdvice(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AutomationRuleEffectAdviceInput input) {
    return call(auth, "configure", actor -> ResponseEntity.ok(effects.recordAdvice(input, actor)));
  }

  /**
   * 🔴 **处理一条优化建议**（标成「已采纳」或「已忽略」）。
   *
   * <p>🔴 系统重新生成建议时**不会**覆盖 {@code status}（人的决定不该被重新计算推翻）
   * —— 但如果没有人能改 {@code status}，那句话就是空话。
   */
  @PutMapping("/advices/{id}/status")
  public ResponseEntity<AutomationRuleEffectAdviceRow> setAdviceStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam String status) {
    return call(auth, "configure", actor -> ResponseEntity.ok(effects.setAdviceStatus(id, status)));
  }

  /**
   * 🔴 **依当前聚合重新生成系统建议**（**幂等**：同一条规则 + 同一个建议类型 ⇒ 至多一条）。
   *
   * <p>🔴 已经被 人处理过（ADOPTED / IGNORED）的建议**不会被覆盖状态** ——
   * 系统重新算一遍不该把人的决定推翻。
   * <p>🔴 不再成立的系统建议被置成 {@code active = false}（**不删**：历史建议要能复现）。
   */
  @PostMapping("/advices/refresh")
  public ResponseEntity<AutomationRuleEffectAdviceRefreshResult> refreshAdvices(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth, "configure", actor -> ResponseEntity.ok(effects.refreshAdvices(actor)));
  }
}
