package com.starcon.crm;

import static com.starcon.crm.ReviewRequestModels.*;

import com.starcon.crm.ReviewRequestModels.CrmReviewBatchInput;
import com.starcon.crm.ReviewRequestModels.CrmReviewBatchResult;
import com.starcon.crm.ReviewRequestModels.CrmReviewCandidatePage;
import com.starcon.crm.ReviewRequestModels.CrmReviewCapabilities;
import com.starcon.crm.ReviewRequestModels.CrmReviewCreateInput;
import com.starcon.crm.ReviewRequestModels.CrmReviewFrequencyPolicy;
import com.starcon.crm.ReviewRequestModels.CrmReviewPolicyInput;
import com.starcon.crm.ReviewRequestModels.CrmReviewRecovery;
import com.starcon.crm.ReviewRequestModels.CrmReviewRecoveryInput;
import com.starcon.crm.ReviewRequestModels.CrmReviewRecoveryStats;
import com.starcon.crm.ReviewRequestModels.CrmReviewRequestDetail;
import com.starcon.crm.ReviewRequestModels.CrmReviewRequestRow;
import com.starcon.crm.ReviewRequestModels.CrmReviewSendRecord;
import com.starcon.crm.ReviewRequestModels.CrmReviewSendRecordInput;
import com.starcon.crm.ReviewRequestModels.CrmReviewShopView;
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
 * 15.2.2.1 Request a Review（索评）。
 *
 * <p>命名空间 {@code /crm/review-requests}：前端入口同名（页面 {@code /crm/review-requests}），
 * 浏览器只经 {@code /api/crm} 代理访问；后端路径按<b>服务</b>命名空间走，
 * 与 catalog（/catalog/...）、trade（/trade/...）同一纪律。
 *
 * <p>⚠️ 与同服务的 {@code /crm/knowledge}（15.4.2.1 客服知识库）**刻意不共用命名空间**：
 * 那是「这个格子此刻该给买家答哪一条」，本模块是「这一位买家、这一张已签收的单子，
 * 要不要向他发起一次索评」。两者一个管内容、一个管触达。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/crm/review-requests")
public class ReviewRequestController {

  private final AccessService access;
  private final ReviewRequestService reviews;

  public ReviewRequestController(AccessService access, ReviewRequestService reviews) {
    this.access = access;
    this.reviews = reviews;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm." + permission);
    return access.run(actor, "crm." + permission, () -> work.apply(actor));
  }

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  @GetMapping("/capabilities")
  public CrmReviewCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "review.read", a -> reviews.capabilities());
  }

  /**
   * 本租户的索评频次硬上限。
   *
   * <p>没配置过时返回 <b>404</b>（不是「返回一份默认值」）：默认上限是**没有**的，
   * 返回一份看起来正常的默认值会让页面以为频次已经受控 —— 而实际上一次索评都发不出去。
   */
  @GetMapping("/frequency-policy")
  public CrmReviewFrequencyPolicy frequencyPolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "review.read",
        a -> {
          var policy = reviews.frequencyPolicy();
          if (policy == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "本租户尚未配置索评频次策略：索评频次必须受限，没有硬上限就不允许发起索评。");
          }
          return policy;
        });
  }

  /** 配置 / 更新频次硬上限（幂等 upsert，一租户一行）。 */
  @PutMapping("/frequency-policy")
  public CrmReviewFrequencyPolicy savePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmReviewPolicyInput input) {
    return call(auth, "review.update", a -> reviews.savePolicy(input));
  }

  /** 店铺目录（来自上游 channel 的真实清单，不是前端写死的常量）。 */
  @GetMapping("/shops")
  public List<CrmReviewShopView> shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "review.read", a -> reviews.shops(auth));
  }

  /**
   * 已签收候选单（上游 channel 的确认单清单）。
   *
   * <p>上游读不到一律 503 —— **绝不返回空集合冒充「没有已签收订单」**。
   */
  @GetMapping("/candidates")
  public CrmReviewCandidatePage candidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String batchNo,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "review.read",
        a -> reviews.candidates(carrierCode, batchNo, keyword, page, size, auth));
  }

  /** 索评任务列表（真分页 + 真筛选）。 */
  @GetMapping
  public List<CrmReviewRequestRow> requests(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String buyerRef,
      @RequestParam(required = false) String salesOrderNo,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String createdFrom,
      @RequestParam(required = false) String createdTo,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "review.read",
        a ->
            reviews.list(
                shopId,
                platformCode,
                status,
                buyerRef,
                salesOrderNo,
                carrierCode,
                createdFrom,
                createdTo,
                page,
                size));
  }

  /** 单条索评任务详情 = 请求本体 + 发送记录 + 评价回收。 */
  @GetMapping("/{requestId}")
  public CrmReviewRequestDetail request(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID requestId) {
    return call(auth, "review.read", a -> reviews.detail(requestId));
  }

  /**
   * 单条发起索评。
   *
   * <p>返回的行里 {@code status} 可能是 {@code REJECTED}：那次索评被频次规则挡住了，
   * 原因写在 {@code rejectReason} 里，**这一行已经落库**（留痕）。
   */
  @PostMapping
  public CrmReviewRequestRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmReviewCreateInput input) {
    return call(auth, "review.update", a -> reviews.create(input, a.userId(), auth));
  }

  /** 批量执行：按店铺 / 时间 / 物流状态批量索评，逐条给出结论与原因。 */
  @PostMapping("/batch")
  public CrmReviewBatchResult batch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmReviewBatchInput input) {
    return call(auth, "review.update", a -> reviews.batch(input, a.userId(), auth));
  }

  /**
   * 登记一次触达（发送记录）。
   *
   * <p>🔴 {@code transport=PLATFORM_API} 会被服务端拒绝：平台侧索评接口**未接入**。
   */
  @PostMapping("/{requestId}/send-records")
  public CrmReviewSendRecord sendRecord(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID requestId,
      @Valid @RequestBody CrmReviewSendRecordInput input) {
    return call(auth, "review.update", a -> reviews.addSendRecord(requestId, input, a.userId()));
  }

  /**
   * 登记一条评价回收（收到的评价原文 + 关联到索评请求）。
   *
   * <p>⚠️ 下游 15.2.1.1 评价列表**未交付** ⇒ 本模块只落**回收桶**。
   */
  @PostMapping("/{requestId}/recoveries")
  public CrmReviewRecovery recovery(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID requestId,
      @Valid @RequestBody CrmReviewRecoveryInput input) {
    return call(auth, "review.update", a -> reviews.addRecovery(requestId, input, a.userId()));
  }

  /** 评价回收统计（蓝图输出句的第三项）。 */
  @GetMapping("/recovery-stats")
  public CrmReviewRecoveryStats recoveryStats(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Integer days,
      @RequestParam(required = false) UUID shopId) {
    return call(auth, "review.read", a -> reviews.recoveryStats(days, shopId));
  }
}
