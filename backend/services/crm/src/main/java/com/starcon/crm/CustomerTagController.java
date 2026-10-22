package com.starcon.crm;

import static com.starcon.crm.CustomerTagModels.*;

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
 * 15.3.2.1 客户标签。
 *
 * <p>命名空间 {@code /crm/customer-tags}：前端入口同名（页面 {@code /crm/tags}），
 * 浏览器只经 {@code /api/crm} 代理访问；后端路径按<b>服务</b>命名空间走，
 * 与 catalog（/catalog/...）、trade（/trade/...）同一纪律。
 *
 * <p>⚠️ 与同卷兄弟模块（{@code /crm/knowledge} 15.4.2.1 知识库、
 * {@code /crm/review-requests} 15.2.2.1 索评、{@code /crm/review-rules} 15.2.2.2 索评规则、
 * {@code /crm/buyer-emails} 15.1.2.1 买家邮件）**刻意不共用命名空间**：
 * 那些回答的是「这句话怎么答」「要不要索评」「买家说了什么」，
 * 本模块回答的是「这位买家属于哪一类人」。四者不共用任何一张表。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>🔴 权限码用 {@code crm.tag.*}（与兄弟模块的 {@code crm.knowledge.*} /
 * {@code crm.review.*} / {@code crm.rule.*} / {@code crm.email.*} 同一族命名），
 * <b>identity 服务零改动</b>。
 */
@RestController
@RequestMapping("/crm/customer-tags")
public class CustomerTagController {

  private final AccessService access;
  private final CustomerTagService tags;

  public CustomerTagController(AccessService access, CustomerTagService tags) {
    this.access = access;
    this.tags = tags;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm.tag." + permission);
    return access.run(actor, "crm.tag." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。
   *
   * <p>🔴 里面显式说明「标签**不触发**任何对买家的动作」，以及
   * 15.3.1.1 筛选 / 14.4 促销定向 / 15.5.2.1 关怀邮件<b>未交付</b>。
   */
  @GetMapping("/capabilities")
  public CrmCustomerTagCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> tags.capabilities());
  }

  // ================================================================ 标签定义

  /** 标签定义列表（真分页 + 真筛选）。 */
  @GetMapping("/tags")
  public CrmCustomerTagPage tags(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> tags.tagPage(category, status, keyword, page, size));
  }

  /** 单枚标签定义。 */
  @GetMapping("/tags/{tagId}")
  public CrmCustomerTagRow tag(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID tagId) {
    return call(auth, "read", a -> tags.tag(tagId));
  }

  /** 新建标签定义。 */
  @PostMapping("/tags")
  public CrmCustomerTagRow createTag(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmCustomerTagInput input) {
    return call(auth, "update", a -> tags.createTag(input, a.userId()));
  }

  /** 更新标签定义（标签编码建后不可改）。 */
  @PutMapping("/tags/{tagId}")
  public CrmCustomerTagRow updateTag(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID tagId,
      @Valid @RequestBody CrmCustomerTagInput input) {
    return call(auth, "update", a -> tags.updateTag(tagId, input, a.userId()));
  }

  /**
   * 删除标签定义。
   *
   * <p>已被规则或标签记录引用过的<b>删不掉</b>（DB 触发器会拒）——
   * 删掉之后「这位客户身上有哪枚标签」在历史里就只剩一个孤零零的 tag_id。
   */
  @DeleteMapping("/tags/{tagId}")
  public void deleteTag(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID tagId) {
    call(
        auth,
        "update",
        a -> {
          tags.deleteTag(tagId);
          return null;
        });
  }

  // ================================================================ 自动打标规则

  /** 规则列表（真分页 + 真筛选）。 */
  @GetMapping("/rules")
  public CrmCustomerTagRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID tagId,
      @RequestParam(required = false) String metric,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> tags.rulePage(tagId, metric, status, keyword, page, size));
  }

  /** 单条规则（含最近一次运行结论）。 */
  @GetMapping("/rules/{ruleId}")
  public CrmCustomerTagRuleRow rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId) {
    return call(auth, "read", a -> tags.rule(ruleId));
  }

  /** 新建规则。 */
  @PostMapping("/rules")
  public CrmCustomerTagRuleRow createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmCustomerTagRuleInput input) {
    return call(auth, "update", a -> tags.createRule(input, a.userId()));
  }

  /** 更新规则（规则编码建后不可改）。 */
  @PutMapping("/rules/{ruleId}")
  public CrmCustomerTagRuleRow updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody CrmCustomerTagRuleInput input) {
    return call(auth, "update", a -> tags.updateRule(ruleId, input, a.userId()));
  }

  /** 删除规则（已有标签记录或运行记录的删不掉，DB 触发器会拒）。 */
  @DeleteMapping("/rules/{ruleId}")
  public void deleteRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId) {
    call(
        auth,
        "update",
        a -> {
          tags.deleteRule(ruleId);
          return null;
        });
  }

  /**
   * 跑一次规则（= 蓝图「自动规则打标（按订单行为）」）。
   *
   * <p>🔴 上游（trade）读不到 ⇒ 运行记录记 FAILED，<b>一条标签都不打</b>。
   * 同批事实重跑 ⇒ 幂等（不重复打标），且<b>不覆盖人工标的标签</b>。
   */
  @PostMapping("/rules/{ruleId}/run")
  public CrmCustomerTagRunRow runRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @RequestBody(required = false) CrmCustomerTagRunInput input) {
    return call(auth, "update", a -> tags.executeRule(ruleId, input, a.userId(), auth));
  }

  // ================================================================ 运行记录

  /** 运行记录列表（真分页 + 真筛选）。 */
  @GetMapping("/runs")
  public CrmCustomerTagRunPage runs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID ruleId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> tags.runPage(ruleId, status, page, size));
  }

  /** 单次运行记录。 */
  @GetMapping("/runs/{runId}")
  public CrmCustomerTagRunRow run(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID runId) {
    return call(auth, "read", a -> tags.run(runId));
  }

  /**
   * 逐客户判定结论（真分页 + 真筛选）。
   *
   * <p>🔴 {@code outcome=INDETERMINATE} 能筛出「样本不足 ⇒ 不可判定」的那些客户 ——
   * 「为什么他没被打上退货率高的标签」是运营要问的第一个问题。
   */
  @GetMapping("/runs/{runId}/evaluations")
  public CrmCustomerTagEvaluationPage evaluations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID runId,
      @RequestParam(required = false) String outcome,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> tags.evaluations(runId, outcome, page, size));
  }

  // ================================================================ 标签记录

  /** 标签记录列表（真分页 + 真筛选）。 */
  @GetMapping("/assignments")
  public CrmCustomerTagAssignmentPage assignments(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID tagId,
      @RequestParam(required = false) String customerRef,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        a -> tags.assignmentPage(tagId, customerRef, source, state, keyword, page, size));
  }

  /** 单条标签记录（含判定依据快照）。 */
  @GetMapping("/assignments/{assignmentId}")
  public CrmCustomerTagAssignmentRow assignment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID assignmentId) {
    return call(auth, "read", a -> tags.assignment(assignmentId));
  }

  /**
   * 手工打标。
   *
   * <p>🔴 可争议标签（退货率高 / 风险）**必须带原因**，否则 400。
   */
  @PostMapping("/assignments")
  public CrmCustomerTagAssignmentRow manualTag(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmCustomerTagAssignmentInput input) {
    return call(auth, "update", a -> tags.manualTag(input, a.userId()));
  }

  /**
   * 人工复核一条标签记录（确认 / 撤销）。
   *
   * <p>🔴 撤销**必须**写原因；撤销之后自动规则重跑<b>不会</b>把它恢复。
   */
  @PostMapping("/assignments/{assignmentId}/review")
  public CrmCustomerTagAssignmentRow review(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID assignmentId,
      @Valid @RequestBody CrmCustomerTagReviewInput input) {
    return call(auth, "update", a -> tags.review(assignmentId, input, a.userId()));
  }

  // ================================================================ 分布统计

  /**
   * 标签分布统计（蓝图输出句的第二项）。
   *
   * <p>占比在服务端算好；分母为 0 时占比为 null（页面显示「不计算」而不是 0%）。
   */
  @GetMapping("/distribution")
  public CrmCustomerTagDistribution distribution(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> tags.distribution());
  }

  // ================================================================ 候选客户

  /**
   * 候选客户清单（手工打标的「选谁」入口）。
   *
   * <p>数据来自**上游订单事实的真实聚合**（经 trade 授权 HTTP API）。
   * 上游读不到 ⇒ 503，**绝不**返回空名单冒充「没有客户」。
   */
  @GetMapping("/customer-candidates")
  public CrmCustomerTagCustomerCandidatePage customerCandidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> tags.customerCandidates(keyword, page, size, auth));
  }
}
