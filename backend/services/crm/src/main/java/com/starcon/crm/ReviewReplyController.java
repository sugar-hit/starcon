package com.starcon.crm;

import static com.starcon.crm.ReviewReplyModels.*;

import com.starcon.crm.ReviewReplyModels.CrmReviewReplyBatchDetail;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyBatchInput;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyBatchPage;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyBatchResult;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyCapabilities;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyCreateInput;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyDeliveryResult;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyDetail;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyPage;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyPolicyInput;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyPolicyPage;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyPolicyRow;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyRow;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyTemplateDetail;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyTemplateInput;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyTemplatePage;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyTemplateUpdateInput;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.2.1.2 评价回复。
 *
 * <p>命名空间 {@code /crm/review-replies}：与同服务的 {@code /crm/reviews}（15.2.1.1 评价列表）、
 * {@code /crm/review-requests}（15.2.2.1 索评）、{@code /crm/review-rules}（15.2.2.2 索评规则）
 * <b>刻意不共用命名空间</b>：那三个分别是「收到的评价长什么样」「要不要向买家发起一次索评」
 * 「怎么发起索评」，本模块是「**已经收到的评价怎么回**」。
 *
 * <p>⚠️ 首 token 用 {@code review-replies} 而不是 {@code replies}：
 * {@code replies} 单独看太泛，而 {@code reviews} 已经被 15.2.1.1 占用 ——
 * 两条白名单首 token 不同才能各自独立登记，互不吃掉对方。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码用 {@code crm.review_reply.read} / {@code crm.review_reply.update}：
 * 与同服务的 {@code crm.product_review.*}（15.2.1.1）/ {@code crm.knowledge.*} /
 * {@code crm.review.*}（15.2.2.1）/ {@code crm.rule.*} 同一族命名；
 * <b>刻意不叫 {@code crm.review.*}</b> —— 那个前缀归 15.2.2.1 索评，两件事共用会让
 * 「索评」与「评价回复」在权限上混成一团。
 */
@RestController
@RequestMapping("/crm/review-replies")
public class ReviewReplyController {

  private final AccessService access;
  private final ReviewReplyService replies;

  public ReviewReplyController(AccessService access, ReviewReplyService replies) {
    this.access = access;
    this.replies = replies;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm.review_reply." + permission);
    return access.run(actor, "crm.review_reply." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  @GetMapping("/capabilities")
  public CrmReviewReplyCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> replies.capabilities());
  }

  // ================================================================ 措辞规范

  /** 措辞规范列表（真分页 + 真筛选）。 */
  @GetMapping("/policies")
  public CrmReviewReplyPolicyPage policies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String replyType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        a -> replies.listPolicies(platformCode, replyType, status, page, size));
  }

  /** 单条措辞规范。 */
  @GetMapping("/policies/{policyId}")
  public CrmReviewReplyPolicyRow policy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID policyId) {
    return call(auth, "read", a -> replies.policy(policyId));
  }

  /**
   * 配置平台措辞规范 —— <b>落成新的一版</b>。
   *
   * <p>规范不原地改：平台政策会变，改规范 = 发新版本，旧版本留在历史里。
   */
  @PutMapping("/policies")
  public CrmReviewReplyPolicyRow savePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmReviewReplyPolicyInput input) {
    return call(auth, "update", a -> replies.savePolicy(input, a.userId()));
  }

  // ================================================================ 模板

  /** 模板列表（真分页 + 真筛选）。 */
  @GetMapping("/templates")
  public CrmReviewReplyTemplatePage templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String replyType,
      @RequestParam(required = false) String ratingScope,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        a -> replies.listTemplates(platformCode, replyType, ratingScope, status, keyword, page, size));
  }

  /** 新建回复模板（落地为草稿；改内容要发新版本）。 */
  @PostMapping("/templates")
  public CrmReviewReplyTemplateDetail createTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmReviewReplyTemplateInput input) {
    return call(auth, "update", a -> replies.createTemplate(input, a.userId()));
  }

  /** 模板详情 = 模板本体 + 多语言条目。 */
  @GetMapping("/templates/{templateId}")
  public CrmReviewReplyTemplateDetail template(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId) {
    return call(auth, "read", a -> replies.templateDetail(templateId));
  }

  /** 改草稿模板（已生效的模板内容冻结）。 */
  @PutMapping("/templates/{templateId}")
  public CrmReviewReplyTemplateDetail updateTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId,
      @Valid @RequestBody CrmReviewReplyTemplateUpdateInput input) {
    return call(auth, "update", a -> replies.updateTemplate(templateId, input));
  }

  /** 发布模板（草稿 → 已生效）。发布时才强制校验措辞规范。 */
  @PostMapping("/templates/{templateId}/publish")
  public CrmReviewReplyTemplateDetail publishTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId) {
    return call(auth, "update", a -> replies.publishTemplate(templateId));
  }

  /** 下架模板（已生效 → 已停用）。 */
  @PostMapping("/templates/{templateId}/archive")
  public CrmReviewReplyTemplateDetail archiveTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId) {
    return call(auth, "update", a -> replies.archiveTemplate(templateId));
  }

  // ================================================================ 回复记录

  /** 回复记录列表（真分页 + 真筛选）。 */
  @GetMapping
  public CrmReviewReplyPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID reviewId,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String reviewScope,
      @RequestParam(required = false) String replyType,
      @RequestParam(required = false) String locale,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String templateKey,
      @RequestParam(required = false) UUID batchId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        a ->
            replies.listReplies(
                reviewId,
                shopId,
                platformCode,
                reviewScope,
                replyType,
                locale,
                status,
                templateKey,
                batchId,
                keyword,
                page,
                size));
  }

  /**
   * 写一条回复（公开回复 / 卖家评论追加）。
   *
   * <p>不合规的正文直接 <b>400</b>；同一条评价同一种形态已有在途回复时 <b>409</b>。
   */
  @PostMapping
  public CrmReviewReplyRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmReviewReplyCreateInput input) {
    return call(auth, "update", a -> replies.createReply(input, a.userId()));
  }

  /** 单条回复详情 = 回复本体 + 投递历史。 */
  @GetMapping("/{replyId}")
  public CrmReviewReplyDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID replyId) {
    return call(auth, "read", a -> replies.replyDetail(replyId));
  }

  /**
   * 把回复投递到平台。
   *
   * <p>🔴 当前恒为 <b>409</b>：平台评价回复适配器未交付。投递尝试**已经留痕**
   * （{@code outcome=NOT_INTEGRATED}），回复状态停在 {@code PENDING}，
   * 平台回执恒为空 —— 绝不返回假成功回执。
   *
   * <p>⚠️ 409 是在事务提交之后才抛的：抛在事务里会把刚记下的那次尝试一起回滚掉，
   * 而「我们试过、没发出去、为什么」恰恰是必须查得到的东西。
   */
  @PostMapping("/{replyId}/deliver")
  public CrmReviewReplyDeliveryResult deliver(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID replyId) {
    var result = call(auth, "update", a -> replies.deliver(replyId, auth));
    if (!result.available()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, result.reason());
    }
    return result;
  }

  /** 撤回一条待投递的回复（撤回了可以再回一次）。 */
  @PostMapping("/{replyId}/cancel")
  public CrmReviewReplyRow cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID replyId,
      @RequestParam(required = false) String note) {
    return call(auth, "update", a -> replies.cancelReply(replyId, note));
  }

  // ================================================================ 批量回复

  /** 批次列表（真分页 + 真筛选）。 */
  @GetMapping("/batches")
  public CrmReviewReplyBatchPage batches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String replyType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "read", a -> replies.listBatches(platformCode, replyType, status, page, size));
  }

  /** 批量回复「同类评价」。 */
  @PostMapping("/batches")
  public CrmReviewReplyBatchResult createBatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmReviewReplyBatchInput input) {
    return call(auth, "update", a -> replies.createBatch(input, a.userId()));
  }

  /** 批次详情 = 批次本体 + 该批次下的回复 + 被跳过的评价与原因。 */
  @GetMapping("/batches/{batchId}")
  public CrmReviewReplyBatchDetail batch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID batchId) {
    return call(auth, "read", a -> replies.batchDetail(batchId));
  }
}
