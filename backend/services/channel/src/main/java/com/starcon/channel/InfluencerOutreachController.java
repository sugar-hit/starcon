package com.starcon.channel;

import static com.starcon.channel.InfluencerOutreachModels.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 14.5.1.2 建联与样品。
 *
 * <p>蓝图：「达人建联流程：按规则筛选达人 → 模板化私信邀约（走官方接口）→
 * 寄样管理（样品单、物流、签收）→ 合作跟进 → 佣金设置。输入：达人筛选条件；邀约模板；
 * 样品寄送信息；佣金比例。输出：建联记录 + 样品单 + 合作状态跟踪。关系：上游 = 14.5.1.1；
 * 下游 = 8 仓库（样品出库）、10 物流（样品物流）、12 财务（佣金计提）。
 * <b>建联必须走官方接口</b>，批量私信是平台风控重点，第三方工具易导致账号风险。」
 * 端点按蓝图模块号标注，方便对照 14-ads-and-growth.md。
 *
 * <p>🔴 入口分成六组，各自对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /capabilities}、{@code GET /options}、{@code GET /summary} ——
 *       能力声明与下拉项。能力声明里<b>平台私信接口</b>、<b>8 仓库</b>、<b>10 物流</b>、
 *       <b>12 财务</b>四个维度显式 {@code available=false}（各自带 ownerModule 与影响说明）——
 *       界面上必须让人看见这些状态，不许用假数字顶替。
 *   <li>{@code /segments}、{@code /segments/{uuid}/candidates} —— 「按规则筛选达人」：
 *       筛选条件配置 + **真筛选**的候选预览。
 *   <li>{@code /templates}、{@code /templates/{uuid}/preview} —— 「模板化私信邀约」：
 *       模板配置 + 真渲染预览。
 *   <li>{@code /outreachs} —— 「建联记录 + 合作状态跟踪」：
 *       真分页 + 真筛选 + 状态机 + <b>幂等键</b> + <b>频率上限</b> + 逐条登记平台回执。
 *   <li>{@code /samples}、{@code /shipments} —— 「寄样管理（样品单、物流、签收）」。
 *   <li>{@code /commission-rules}、{@code /commission-accruals} —— 「佣金设置」。
 * </ul>
 *
 * <p>🔴 <b>本模块刻意没有「批量群发」端点</b>：批量能力只到
 * {@code POST /outreachs/batch}（批量生成**草稿**），每一条仍要逐条登记平台回执
 * 才变成已发送 —— 批量私信是平台风控重点。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 14.1.1.1 活动列表、
 * 14.5.1.1 达人库同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/outreach")
public class InfluencerOutreachController {

  private final AccessService access;
  private final InfluencerOutreachService service;

  public InfluencerOutreachController(AccessService access, InfluencerOutreachService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  /** 需要把调用方 bearer 透传给下游端口的入口（转发的是调用方自己的令牌，不传租户号）。 */
  private <T> T callWithBearer(
      String bearer, String permission, BiFunction<AccessService.Access, String, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor, bearer));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：逐维度说清「哪些能算、哪些拿不到」。
   *
   * <p>🔴 四个不可用维度分别是：平台私信接口（适配层未交付）、8 仓库（样品出库）、
   * 10 物流（样品轨迹）、12 财务（佣金入账）。
   */
  @GetMapping("/capabilities")
  public InfluencerOutreachCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 下拉项（状态 / 跟进方式 / 佣金形态 / 平台 / 店铺 / 模板变量）。 */
  @GetMapping("/options")
  public InfluencerOutreachOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.options());
  }

  /** 概览统计。 */
  @GetMapping("/summary")
  public InfluencerOutreachSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.summary());
  }

  // ---- 筛选条件 ----

  /** 筛选条件列表：真分页 + 真筛选。 */
  @GetMapping("/segments")
  public InfluencerOutreachSegmentPage segments(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.segments(status, keyword, page, size));
  }

  /** 登记一个筛选条件。 */
  @PostMapping("/segments")
  public InfluencerOutreachSegmentRow saveSegment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveInfluencerOutreachSegmentRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveSegment(request));
  }

  /** 改一个筛选条件。 */
  @PostMapping("/segments/{id}")
  public InfluencerOutreachSegmentRow updateSegment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody SaveInfluencerOutreachSegmentRequest request) {
    return call(auth, "channel.operation.update", a -> service.updateSegment(id, request));
  }

  /** 删一个筛选条件（被引用时返回 409）。 */
  @DeleteMapping("/segments/{id}")
  public Map<String, Object> deleteSegment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(
        auth,
        "channel.operation.delete",
        a -> {
          service.deleteSegment(id);
          return Map.<String, Object>of("deleted", true);
        });
  }

  /**
   * 🔴 按规则筛选达人（蓝图第一步）：真筛选，不是写死的候选列表。
   */
  @GetMapping("/segments/{id}/candidates")
  public InfluencerOutreachCandidatePage segmentCandidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.segmentCandidates(id, keyword, page, size));
  }

  // ---- 邀约模板 ----

  /** 邀约模板列表：真分页 + 真筛选。 */
  @GetMapping("/templates")
  public InfluencerOutreachTemplatePage templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.templates(platformCode, status, keyword, page, size));
  }

  /** 登记一个邀约模板（占位符必须在闭合词表里）。 */
  @PostMapping("/templates")
  public InfluencerOutreachTemplateRow saveTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveInfluencerOutreachTemplateRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveTemplate(request));
  }

  /** 改一个邀约模板。 */
  @PostMapping("/templates/{id}")
  public InfluencerOutreachTemplateRow updateTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody SaveInfluencerOutreachTemplateRequest request) {
    return call(auth, "channel.operation.update", a -> service.updateTemplate(id, request));
  }

  /** 删一个邀约模板（被建联记录引用时返回 409）。 */
  @DeleteMapping("/templates/{id}")
  public Map<String, Object> deleteTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(
        auth,
        "channel.operation.delete",
        a -> {
          service.deleteTemplate(id);
          return Map.<String, Object>of("deleted", true);
        });
  }

  /** 模板预览（真渲染；变量取自真实达人档案）。 */
  @GetMapping("/templates/{id}/preview")
  public InfluencerOutreachTemplatePreview templatePreview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false) String influencerId) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.templatePreview(id, blankUuid(influencerId)));
  }

  // ---- 风控策略 ----

  /** 风控策略列表（按平台）。 */
  @GetMapping("/policies")
  public InfluencerOutreachPolicyPage policies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.policies(platformCode, page, size));
  }

  /** 保存风控策略（按平台 upsert：一个平台一条）。 */
  @PostMapping("/policies")
  public InfluencerOutreachPolicyRow savePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveInfluencerOutreachPolicyRequest request) {
    return call(auth, "channel.operation.update", a -> service.savePolicy(request));
  }

  // ---- 建联记录 ----

  /** 建联记录列表：真分页 + 真筛选 + 真排序。 */
  @GetMapping("/outreachs")
  public InfluencerOutreachPage outreachs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String influencerId,
      @RequestParam(required = false) String segmentId,
      @RequestParam(required = false) String templateId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "CREATED_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.outreachs(
                blankUuid(influencerId),
                blankUuid(segmentId),
                blankUuid(templateId),
                platformCode,
                shopCode,
                status,
                keyword,
                sort,
                page,
                size));
  }

  /** 建联记录详情：记录 + 跟进 + 样品单 + 佣金计提。 */
  @GetMapping("/outreachs/{id}")
  public InfluencerOutreachDetail outreach(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.outreach(id));
  }

  /** 新建一条建联记录（草稿）。 */
  @PostMapping("/outreachs")
  public InfluencerOutreachRow saveOutreach(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveInfluencerOutreachRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveOutreach(request));
  }

  /** 改一条建联记录（**只允许改草稿**；已发出的私信不可改口）。 */
  @PostMapping("/outreachs/{id}")
  public InfluencerOutreachRow updateOutreach(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody SaveInfluencerOutreachRequest request) {
    return call(auth, "channel.operation.update", a -> service.updateOutreach(id, request));
  }

  /** 删一条建联记录（**只有草稿可以删**）。 */
  @DeleteMapping("/outreachs/{id}")
  public Map<String, Object> deleteOutreach(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(
        auth,
        "channel.operation.delete",
        a -> {
          service.deleteOutreach(id);
          return Map.<String, Object>of("deleted", true);
        });
  }

  /**
   * 🔴 手动登记「邀约已发送 + 平台回执」。
   *
   * <p>登记时过**幂等键 + 每日发送上限 + 最小间隔**三道闸。
   */
  @PostMapping("/outreachs/{id}/receipt")
  public InfluencerOutreachRow registerReceipt(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody RegisterInfluencerOutreachReceiptRequest request) {
    return call(auth, "channel.operation.update", a -> service.registerReceipt(id, request));
  }

  /**
   * 🔴 走官方接口发送一条私信。
   *
   * <p>当前必然返回「未接入」并且**不写任何数据** —— 平台适配层没有达人私信适配器。
   */
  @PostMapping("/outreachs/{id}/send")
  public InfluencerOutreachSendResult send(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return callWithBearer(
        auth, "channel.operation.create", (a, bearer) -> service.sendViaPlatform(id, bearer));
  }

  /** 推进建联状态（状态机 + DB CHECK 双钉）。 */
  @PostMapping("/outreachs/{id}/status")
  public InfluencerOutreachRow transitionOutreach(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody TransitionInfluencerOutreachRequest request) {
    return call(auth, "channel.operation.update", a -> service.transitionOutreach(id, request));
  }

  /**
   * 🔴 批量建联：按规则挑达人，<b>批量生成草稿</b>（不是批量群发）。
   *
   * <p>每一条仍要逐条「登记平台回执」才会真正发出。
   */
  @PostMapping("/outreachs/batch")
  public InfluencerOutreachBatchResult batchCreate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BatchInfluencerOutreachRequest request) {
    return call(auth, "channel.operation.create", a -> service.batchCreate(request));
  }

  /** 逐平台说清私信接口是否已接入。 */
  @GetMapping("/outreach-availability")
  public InfluencerOutreachAvailability availability(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.availability());
  }

  // ---- 合作跟进 ----

  /** 跟进列表：真分页。 */
  @GetMapping("/followups")
  public InfluencerOutreachFollowupPage followups(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String outreachId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.followups(blankUuid(outreachId), page, size));
  }

  /** 登记一次合作跟进。 */
  @PostMapping("/outreachs/{id}/followups")
  public InfluencerOutreachFollowupRow createFollowup(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody CreateInfluencerOutreachFollowupRequest request) {
    return call(auth, "channel.operation.create", a -> service.createFollowup(id, request));
  }

  // ---- 样品单 ----

  /** 样品单列表：真分页 + 真筛选 + 真排序。 */
  @GetMapping("/samples")
  public InfluencerSampleOrderPage samples(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String outreachId,
      @RequestParam(required = false) String influencerId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "CREATED_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.sampleOrders(
                blankUuid(outreachId),
                blankUuid(influencerId),
                status,
                shopCode,
                skuCode,
                keyword,
                sort,
                page,
                size));
  }

  /** 新建一张样品单。 */
  @PostMapping("/samples")
  public InfluencerSampleOrderRow createSampleOrder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveInfluencerSampleOrderRequest request) {
    return call(auth, "channel.operation.create", a -> service.createSampleOrder(request));
  }

  /** 改一张样品单。 */
  @PostMapping("/samples/{id}")
  public InfluencerSampleOrderRow updateSampleOrder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody SaveInfluencerSampleOrderRequest request) {
    return call(auth, "channel.operation.update", a -> service.updateSampleOrder(id, request));
  }

  /**
   * 手动推进样品单状态。
   *
   * <p>🔴 只能 REQUESTED → OUTBOUNDED / CANCELLED；DELIVERED / SIGNED 由包裹推导。
   */
  @PostMapping("/samples/{id}/status")
  public InfluencerSampleOrderRow transitionSampleOrder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody TransitionInfluencerSampleOrderRequest request) {
    return call(
        auth, "channel.operation.update", a -> service.transitionSampleOrder(id, request));
  }

  /** 包裹列表：真分页。 */
  @GetMapping("/shipments")
  public InfluencerSampleShipmentPage shipments(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String sampleOrderId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.shipments(blankUuid(sampleOrderId), page, size));
  }

  /** 登记一个包裹（发货）。 */
  @PostMapping("/samples/{id}/shipments")
  public InfluencerSampleShipmentRow createShipment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody CreateInfluencerSampleShipmentRequest request) {
    return call(auth, "channel.operation.create", a -> service.createShipment(id, request));
  }

  /** 推进包裹状态（送达 / 签收 / 丢件 / 退回）。 */
  @PostMapping("/shipments/{id}/status")
  public InfluencerSampleShipmentRow transitionShipment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody TransitionInfluencerSampleShipmentRequest request) {
    return call(auth, "channel.operation.update", a -> service.transitionShipment(id, request));
  }

  // ---- 佣金 ----

  /** 佣金规则列表：真分页 + 真筛选。 */
  @GetMapping("/commission-rules")
  public InfluencerCommissionRulePage commissionRules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.commissionRules(scopeType, status, keyword, page, size));
  }

  /** 登记一条佣金规则。 */
  @PostMapping("/commission-rules")
  public InfluencerCommissionRuleRow saveCommissionRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveInfluencerCommissionRuleRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveCommissionRule(request));
  }

  /** 改一条佣金规则。 */
  @PostMapping("/commission-rules/{id}")
  public InfluencerCommissionRuleRow updateCommissionRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody SaveInfluencerCommissionRuleRequest request) {
    return call(
        auth, "channel.operation.update", a -> service.updateCommissionRule(id, request));
  }

  /** 删一条佣金规则（被计提引用时返回 409）。 */
  @DeleteMapping("/commission-rules/{id}")
  public Map<String, Object> deleteCommissionRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(
        auth,
        "channel.operation.delete",
        a -> {
          service.deleteCommissionRule(id);
          return Map.<String, Object>of("deleted", true);
        });
  }

  /** 佣金计提列表：真分页 + 真筛选 + 真排序。 */
  @GetMapping("/commission-accruals")
  public InfluencerCommissionAccrualPage accruals(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String outreachId,
      @RequestParam(required = false) String influencerId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "ACCRUED_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.accruals(
                blankUuid(outreachId),
                blankUuid(influencerId),
                status,
                sort,
                page,
                size));
  }

  /** 登记一笔佣金计提（金额由服务层按规则算；**未进财务**）。 */
  @PostMapping("/commission-accruals")
  public InfluencerCommissionAccrualRow createAccrual(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CreateInfluencerCommissionAccrualRequest request) {
    return call(auth, "channel.operation.create", a -> service.createAccrual(request));
  }

  /** 空串与 null 同义，避免把 {@code ?influencerId=} 当成「筛选空字符串」。 */
  private static UUID blankUuid(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "id 不是合法 UUID");
    }
  }
}
