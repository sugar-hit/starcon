package com.starcon.crm;

import static com.starcon.crm.EmailRuleModels.*;

import com.starcon.crm.EmailRuleModels.CrmEmailRuleAssignmentPage;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleAssignmentRow;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleCancelAssignmentInput;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleCapabilities;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleEvaluateBatchInput;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleEvaluateBatchResult;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleEvaluateInput;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleEvaluateResult;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleHitDetail;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleHitPage;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleInput;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleOptions;
import com.starcon.crm.EmailRuleModels.CrmEmailRulePage;
import com.starcon.crm.EmailRuleModels.CrmEmailRulePreviewResult;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleResult;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleRow;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleStatistics;
import com.starcon.crm.EmailRuleModels.CrmEmailTagAssignmentInput;
import com.starcon.crm.EmailRuleModels.CrmEmailTagAssignmentRow;
import com.starcon.crm.EmailRuleModels.CrmEmailTicketTagInput;
import com.starcon.crm.EmailRuleModels.CrmEmailTicketTagPage;
import com.starcon.crm.EmailRuleModels.CrmEmailTicketTagResult;
import com.starcon.crm.EmailRuleModels.CrmEmailTicketTagRow;
import jakarta.validation.Valid;
import java.util.List;
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
 * 15.1.2.2 邮件规则。
 *
 * <p>命名空间 {@code /crm/email-rules}：前端入口同名（页面 {@code /crm/email-rules}）。
 *
 * <p>⚠️ 与兄弟模块 {@code /crm/buyer-emails}（15.1.2.1 邮件收发）**刻意不共用命名空间**：
 * 那一个管**邮件本身**（收 / 发、附件、模板、发送记录），本模块管**收到邮件之后按什么规则处理**
 * （自动分配 / 自动回复 / 静默）。首 token 也因此不撞车。
 *
 * <p>集合与动作的挂法：
 *
 * <ul>
 *   <li>{@code /rules} —— 邮件规则（GET 真分页真筛选 / POST 新建 / PUT 修改 / DELETE 删除）
 *       与 {@code /rules/{uuid}}；{@code /rules/{uuid}/preview} —— 试算（**不写命中记录**）；</li>
 *   <li>{@code /tags} —— 工单标签字典（规则的第三个条件维度引用它）；</li>
 *   <li>{@code /emails/{emailId}/tags} —— 某封邮件当前挂的工单标签（GET / POST）；</li>
 *   <li>{@code /emails/{emailId}/evaluate} —— 评估一封邮件；{@code /evaluate-batch} —— 批量评估；</li>
 *   <li>{@code /hits} —— 🔴 规则命中记录（只追加）；</li>
 *   <li>{@code /assignments} —— 自动分配结果（15.4.1.1 工单未交付 ⇒ 只到「待下游取」为止）；</li>
 *   <li>{@code /capabilities} / {@code /options} / {@code /statistics} —— 能力声明、词表与统计。</li>
 * </ul>
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code crm.emailrule.read} / {@code crm.emailrule.update}：
 * 与同服务兄弟模块（{@code crm.email.*} / {@code crm.classification.*} / {@code crm.contact.*}）
 * 同一命名法，identity 服务<b>零改动</b>。
 */
@RestController
@RequestMapping("/crm/email-rules")
public class EmailRuleController {

  private final AccessService access;
  private final EmailRuleService rules;

  public EmailRuleController(AccessService access, EmailRuleService rules) {
    this.access = access;
    this.rules = rules;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm.emailrule." + permission);
    return access.run(actor, "crm.emailrule." + permission, () -> work.apply(actor));
  }

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  @GetMapping("/capabilities")
  public CrmEmailRuleCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> rules.capabilities());
  }

  /** 筛选词表（动作与命中方式是 DB 允许的取值域；标签码来自本租户真实数据）。 */
  @GetMapping("/options")
  public CrmEmailRuleOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> rules.options());
  }

  // ================================================================ 规则

  /** 规则列表（真分页 + 真筛选）。 */
  @GetMapping("/rules")
  public CrmEmailRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> rules.rules(keyword, action, enabled, page, size));
  }

  /** 单条规则。 */
  @GetMapping("/rules/{ruleId}")
  public CrmEmailRuleRow rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId) {
    return call(auth, "read", a -> rules.requireRule(ruleId));
  }

  /** 新建一条规则（三个条件维度至少要有一个非空）。 */
  @PostMapping("/rules")
  public CrmEmailRuleResult createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmEmailRuleInput input) {
    return call(auth, "update", a -> rules.createRule(input, a.userId()));
  }

  /** 修改一条规则（规则编码建后不可改；版本 +1，历史命中记录不变）。 */
  @PutMapping("/rules/{ruleId}")
  public CrmEmailRuleResult updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody CrmEmailRuleInput input) {
    return call(auth, "update", a -> rules.updateRule(ruleId, input, a.userId()));
  }

  /** 删除一条规则（已产生的命中记录与分配结果不受影响）。 */
  @DeleteMapping("/rules/{ruleId}")
  public CrmEmailRuleResult deleteRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId) {
    return call(auth, "update", a -> rules.deleteRule(ruleId));
  }

  /** 试算：这条规则对这封邮件会不会命中（**不写命中记录**）。 */
  @PostMapping("/rules/{ruleId}/preview")
  public CrmEmailRulePreviewResult preview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody CrmEmailRulePreviewInput input) {
    return call(auth, "read", a -> rules.preview(ruleId, input.emailId()));
  }

  // ================================================================ 工单标签

  /** 工单标签列表（真分页 + 真筛选）。 */
  @GetMapping("/tags")
  public CrmEmailTicketTagPage tags(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> rules.tags(keyword, status, page, size));
  }

  /** 新建一个工单标签。 */
  @PostMapping("/tags")
  public CrmEmailTicketTagResult createTag(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmEmailTicketTagInput input) {
    return call(auth, "update", a -> rules.createTag(input, a.userId()));
  }

  /** 修改一个工单标签（标签码建后不可改）。 */
  @PutMapping("/tags/{tagId}")
  public CrmEmailTicketTagResult updateTag(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID tagId,
      @Valid @RequestBody CrmEmailTicketTagInput input) {
    return call(auth, "update", a -> rules.updateTag(tagId, input, a.userId()));
  }

  /** 删除一个工单标签（被规则条件或打标记录引用时删不掉）。 */
  @DeleteMapping("/tags/{tagId}")
  public CrmEmailTicketTagResult deleteTag(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID tagId) {
    return call(auth, "update", a -> rules.deleteTag(tagId));
  }

  /** 某封邮件当前挂的工单标签。 */
  @GetMapping("/emails/{emailId}/tags")
  public List<CrmEmailTagAssignmentRow> emailTags(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID emailId) {
    return call(auth, "read", a -> rules.tagsOf(emailId));
  }

  /** 给一封邮件打上工单标签（幂等：同一标签重复打不重复记账）。 */
  @PostMapping("/emails/{emailId}/tags")
  public CrmEmailTagAssignmentRow assignTag(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID emailId,
      @Valid @RequestBody CrmEmailTagAssignmentInput input) {
    return call(auth, "update", a -> rules.assignTag(emailId, input, a.userId()));
  }

  // ================================================================ 评估

  /** 评估一封邮件（按顺序评估启用中的规则，第一条命中即生效）。 */
  @PostMapping("/emails/{emailId}/evaluate")
  public CrmEmailRuleEvaluateResult evaluate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID emailId,
      @RequestBody(required = false) CrmEmailRuleEvaluateInput input) {
    // ⚠️ 邮件 id 取**路径**上的：请求体里的 emailId 不是寻址依据（改了它就等于换了一封邮件）。
    CrmEmailRuleEvaluateInput payload =
        new CrmEmailRuleEvaluateInput(emailId, input == null ? null : input.ruleId());
    return call(auth, "update", a -> rules.evaluate(payload, a.userId()));
  }

  /** 批量评估：逐条给出结论与原因，失败的条目不会被悄悄跳过。 */
  @PostMapping("/evaluate-batch")
  public CrmEmailRuleEvaluateBatchResult evaluateBatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmEmailRuleEvaluateBatchInput input) {
    return call(auth, "update", a -> rules.evaluateBatch(input, a.userId()));
  }

  // ================================================================ 命中记录

  /** 命中记录列表（真分页 + 真筛选）。 */
  @GetMapping("/hits")
  public CrmEmailRuleHitPage hits(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID emailId,
      @RequestParam(required = false) String ruleCode,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String effectiveAction,
      @RequestParam(required = false) Boolean silenceRefused,
      @RequestParam(required = false) Boolean important,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        a ->
            rules.hits(
                emailId,
                ruleCode,
                action,
                effectiveAction,
                silenceRefused,
                important,
                keyword,
                page,
                size));
  }

  /** 单条命中详情 = 命中记录 + 邮件快照 + 当时的规则快照 + 分配结果。 */
  @GetMapping("/hits/{hitId}")
  public CrmEmailRuleHitDetail hit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID hitId) {
    return call(auth, "read", a -> rules.hitDetail(hitId));
  }

  // ================================================================ 自动分配结果

  /** 自动分配结果列表（真分页 + 真筛选）。 */
  @GetMapping("/assignments")
  public CrmEmailRuleAssignmentPage assignments(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID emailId,
      @RequestParam(required = false) String ruleCode,
      @RequestParam(required = false) String targetCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        a -> rules.assignments(emailId, ruleCode, targetCode, status, page, size));
  }

  /** 单条自动分配结果。 */
  @GetMapping("/assignments/{assignmentId}")
  public CrmEmailRuleAssignmentRow assignment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID assignmentId) {
    return call(auth, "read", a -> rules.assignmentDetail(assignmentId));
  }

  /** 取消一条自动分配结果（必须写理由）。 */
  @PostMapping("/assignments/{assignmentId}/cancel")
  public CrmEmailRuleAssignmentRow cancelAssignment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID assignmentId,
      @Valid @RequestBody CrmEmailRuleCancelAssignmentInput input) {
    return call(auth, "update", a -> rules.cancelAssignment(assignmentId, input, a.userId()));
  }

  // ================================================================ 统计

  /** 规则命中统计 + 动作分布 + 静默被拒数。 */
  @GetMapping("/statistics")
  public CrmEmailRuleStatistics statistics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> rules.statistics());
  }
}
