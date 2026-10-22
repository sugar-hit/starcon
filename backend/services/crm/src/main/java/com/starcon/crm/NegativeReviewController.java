package com.starcon.crm;

import static com.starcon.crm.NegativeReviewModels.*;

import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewActionInput;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewActionRow;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewAppealCancelInput;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewAppealCreateInput;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewAppealOutcomeInput;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewAppealRow;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewAppealSubmittedInput;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewCapabilities;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewCandidatePage;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewCaseCreateInput;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewCasePage;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewCaseRow;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewClassifyInput;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewDetail;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewRuleInput;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewRulePage;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewRuleRow;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewRuleUpdateInput;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewShopView;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewStats;
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
 * 15.2.1.3 差评处理。
 *
 * <p>命名空间 {@code /crm/negative-reviews}：前端入口同名（页面 {@code /crm/negative-reviews}），
 * 浏览器只经 {@code /api/crm} 代理访问；后端路径按<b>服务</b>命名空间走，
 * 与 catalog（/catalog/...）、trade（/trade/...）同一纪律。
 *
 * <p>⚠️ 与同服务的 {@code /crm/reviews}（15.2.1.1 评价列表）、
 * {@code /crm/review-replies}（15.2.1.2 评价回复）、{@code /crm/review-requests}（15.2.2.1 索评）、
 * {@code /crm/review-rules}（15.2.2.2 索评规则）**刻意不共用命名空间**：
 * 那些是「评价本身 / 公开回复 / 索评 / 索评规则」，本模块是
 * 「<b>针对差评的处理流程</b>」—— 一条评价进来之后，
 * 我们怎么处理它、能不能申诉、解决率是多少。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码用 {@code crm.negative_review.read} / {@code crm.negative_review.update}：
 * 与同服务的 {@code crm.product_review.*} / {@code crm.review_reply.*} 同一族命名。
 */
@RestController
@RequestMapping("/crm/negative-reviews")
public class NegativeReviewController {

  private final AccessService access;
  private final NegativeReviewService negativeReviews;

  public NegativeReviewController(AccessService access, NegativeReviewService negativeReviews) {
    this.access = access;
    this.negativeReviews = negativeReviews;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm.negative_review." + permission);
    return access.run(actor, "crm.negative_review." + permission, () -> work.apply(actor));
  }

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  @GetMapping("/capabilities")
  public CrmNegativeReviewCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> negativeReviews.capabilities());
  }

  /** 店铺目录（来自上游 channel 的真实清单）+ 每个平台的差评处理策略状态。 */
  @GetMapping("/shops")
  public List<CrmNegativeReviewShopView> shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> negativeReviews.shops(auth));
  }

  // ------------------------------------------------------------ 规则

  /** 规则列表（真分页 + 真筛选）。 */
  @GetMapping("/rules")
  public CrmNegativeReviewRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String ruleScope,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String causeCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        a -> negativeReviews.rules(ruleScope, platformCode, causeCode, status, page, size));
  }

  /** 单条规则。 */
  @GetMapping("/rules/{ruleId}")
  public CrmNegativeReviewRuleRow rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId) {
    return call(auth, "read", a -> negativeReviews.rule(ruleId));
  }

  /** 新建规则（平台策略 / 原因规则）。 */
  @PostMapping("/rules")
  public CrmNegativeReviewRuleRow createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmNegativeReviewRuleInput input) {
    return call(auth, "update", a -> negativeReviews.createRule(input, a.userId()));
  }

  /** 更新规则（原地改 = 版本 +1）。 */
  @PutMapping("/rules/{ruleId}")
  public CrmNegativeReviewRuleRow updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody CrmNegativeReviewRuleUpdateInput input) {
    return call(auth, "update", a -> negativeReviews.updateRule(ruleId, input, a.userId()));
  }

  // ------------------------------------------------------------ 候选差评

  /**
   * 候选差评清单（还没建处理单的那些）。
   *
   * <p>默认只列**该平台阈值以内**的评价（{@code negativeOnly=true}）——
   * 差评阈值是**规则**，没有规则就没有「差评」这个口径。
   */
  @GetMapping("/candidates")
  public CrmNegativeReviewCandidatePage candidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) Integer rating,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "true") boolean negativeOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        a ->
            negativeReviews.candidates(
                shopId, platformCode, rating, keyword, negativeOnly, page, size));
  }

  // ------------------------------------------------------------ 处理单

  /** 处理单列表（真分页 + 真筛选）。 */
  @GetMapping
  public CrmNegativeReviewCasePage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String causeCode,
      @RequestParam(required = false) String caseStatus,
      @RequestParam(required = false) String resolutionStatus,
      @RequestParam(required = false) Boolean appealEligible,
      @RequestParam(required = false) Integer rating,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        a ->
            negativeReviews.cases(
                shopId,
                platformCode,
                causeCode,
                caseStatus,
                resolutionStatus,
                appealEligible,
                rating,
                keyword,
                page,
                size));
  }

  /** 为一条差评建处理单。 */
  @PostMapping
  public CrmNegativeReviewCaseRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmNegativeReviewCaseCreateInput input) {
    return call(auth, "update", a -> negativeReviews.createCase(input, a.userId(), auth));
  }

  /**
   * 解决率统计（分母 = 处理单数，分子 = 已解决数）。
   *
   * <p>🔴 全部由**服务端**现算，前端只原样展示。
   */
  @GetMapping("/stats")
  public CrmNegativeReviewStats stats(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String causeCode,
      @RequestParam(required = false) String caseStatus,
      @RequestParam(required = false) String resolutionStatus,
      @RequestParam(required = false) Boolean appealEligible,
      @RequestParam(required = false) Integer rating,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "30") int days) {
    return call(
        auth,
        "read",
        a ->
            negativeReviews.stats(
                shopId,
                platformCode,
                causeCode,
                caseStatus,
                resolutionStatus,
                appealEligible,
                rating,
                keyword,
                days));
  }

  /** 处理单详情 = 处理单 + 动作流水 + 申诉任务 + 关联订单上下文 + 当时用的规则。 */
  @GetMapping("/{caseId}")
  public CrmNegativeReviewDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID caseId) {
    return call(auth, "read", a -> negativeReviews.detail(caseId, auth));
  }

  // ------------------------------------------------------------ 分类与动作

  /** 标记原因分类（顺带按规则判定是否可申诉）。 */
  @PostMapping("/{caseId}/classify")
  public CrmNegativeReviewCaseRow classify(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID caseId,
      @Valid @RequestBody CrmNegativeReviewClassifyInput input) {
    return call(auth, "update", a -> negativeReviews.classify(caseId, input, a.userId()));
  }

  /** 处理单的动作流水。 */
  @GetMapping("/{caseId}/actions")
  public List<CrmNegativeReviewActionRow> actions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID caseId) {
    return call(auth, "read", a -> negativeReviews.actions(caseId));
  }

  /**
   * 记录一个处理动作。
   *
   * <p>🔴 走平台侧的动作（{@code transport=PLATFORM_API}）在适配器未接入时
   * 返回 <b>409</b> 且<b>一行都不写</b>。
   */
  @PostMapping("/{caseId}/actions")
  public CrmNegativeReviewActionRow recordAction(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID caseId,
      @Valid @RequestBody CrmNegativeReviewActionInput input) {
    return call(auth, "update", a -> negativeReviews.recordAction(caseId, input, a.userId(), auth));
  }

  // ------------------------------------------------------------ 申诉任务

  /** 处理单的申诉任务列表。 */
  @GetMapping("/{caseId}/appeals")
  public List<CrmNegativeReviewAppealRow> appeals(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID caseId) {
    return call(auth, "read", a -> negativeReviews.appeals(caseId));
  }

  /** 建一个申诉任务（配额即占用）。 */
  @PostMapping("/{caseId}/appeals")
  public CrmNegativeReviewAppealRow createAppeal(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID caseId,
      @Valid @RequestBody CrmNegativeReviewAppealCreateInput input) {
    return call(auth, "update", a -> negativeReviews.createAppeal(caseId, input, a.userId()));
  }

  /**
   * 向平台提交申诉。
   *
   * <p>🔴 当前恒为 <b>409</b>：平台差评申诉适配器未交付，<b>一行都不写</b>，
   * 申诉任务仍是「待提交」。
   */
  @PostMapping("/{caseId}/appeals/{appealId}/submit")
  public CrmNegativeReviewAppealRow submitAppeal(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID caseId,
      @PathVariable UUID appealId) {
    return call(auth, "update", a -> negativeReviews.submitAppeal(caseId, appealId, auth));
  }

  /** 人工交接登记：运营已在平台侧实际提交。 */
  @PostMapping("/{caseId}/appeals/{appealId}/submitted")
  public CrmNegativeReviewAppealRow markSubmitted(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID caseId,
      @PathVariable UUID appealId,
      @Valid @RequestBody CrmNegativeReviewAppealSubmittedInput input) {
    return call(
        auth,
        "update",
        a -> negativeReviews.markAppealSubmitted(caseId, appealId, input, a.userId()));
  }

  /** 人工交接登记：平台对申诉的判定（通过 / 驳回）。 */
  @PostMapping("/{caseId}/appeals/{appealId}/outcome")
  public CrmNegativeReviewAppealRow recordOutcome(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID caseId,
      @PathVariable UUID appealId,
      @Valid @RequestBody CrmNegativeReviewAppealOutcomeInput input) {
    return call(
        auth,
        "update",
        a -> negativeReviews.recordAppealOutcome(caseId, appealId, input, a.userId()));
  }

  /** 撤回申诉任务（撤回归还申诉配额）。 */
  @PostMapping("/{caseId}/appeals/{appealId}/cancel")
  public CrmNegativeReviewAppealRow cancelAppeal(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID caseId,
      @PathVariable UUID appealId,
      @Valid @RequestBody CrmNegativeReviewAppealCancelInput input) {
    return call(
        auth,
        "update",
        a -> negativeReviews.cancelAppeal(caseId, appealId, input, a.userId()));
  }
}
