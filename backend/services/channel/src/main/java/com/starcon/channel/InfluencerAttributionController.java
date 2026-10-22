package com.starcon.channel;

import static com.starcon.channel.InfluencerAttributionModels.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 14.5.2.1 带货归因。
 *
 * <p>蓝图：「达人带货归因：达人带来的订单量、GMV、退款率、佣金成本、有效粉丝转化，
 * 对比达人投入产出，沉淀优质达人库。输入：达人带货数据同步；订单归因；佣金数据。
 * 输出：达人效果报表 + ROI + 评级更新。关系：上游 = 14.5.1.1、平台数据；
 * 下游 = 14.5.1.1 评级、13.4 利润（佣金成本）。
 * <b>达人佣金是真实成本，必须进利润表</b>，否则会高估达人带货的净收益。」
 * 端点按蓝图模块号标注，方便对照 14-ads-and-growth.md。
 *
 * <p>🔴 入口分成六组，各自对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /capabilities}、{@code GET /options} —— 能力声明与下拉项。
 *       能力声明里<b>平台带货数据同步</b>、<b>佣金成本进利润表（13.4）</b>与
 *       <b>订单级事实（跨服务）</b>三个维度显式 {@code available=false}
 *       （各自带 ownerModule 与影响说明）—— 界面上必须让人看见这些状态，
 *       不许用假数字顶替。
 *   <li>{@code GET /sync-status}、{@code POST /sync} —— 平台带货数据同步。
 *       适配器未交付 ⇒ 同步明确 409 且<b>不写任何数据</b>。
 *   <li>{@code GET /rules}、{@code GET /rules/current}、{@code POST /rules} ——
 *       归因规则（**一个租户一套生效**）：归因模型 / 分摊基准 / 归因窗口 /
 *       退款口径 / 佣金口径。
 *   <li>{@code POST /run} —— 归因批次：按生效规则把合作记录归因到达人，
 *       写完后触发一次评级重算（评级口径归 14.5.1.1）。
 *   <li>{@code GET /records}、{@code GET /records/{uuid}}、
 *       {@code POST /records/{uuid}/accrual} —— 归因记录、详情与佣金计提。
 *   <li>{@code GET /report} —— 达人效果报表（**三个 GMV 口径分列三栏** + ROI +
 *       有效粉丝转化）。
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 14.1.1.1 活动列表、
 * 14.5.1.1 达人库同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/attributions")
public class InfluencerAttributionController {

  private final AccessService access;
  private final InfluencerAttributionService service;

  public InfluencerAttributionController(AccessService access, InfluencerAttributionService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：逐维度说清「哪些能算、哪些拿不到」。
   *
   * <p>🔴 不可用维度分别是：平台带货数据同步（适配层未交付）、
   * 佣金成本进利润表（13.4 未交付）、订单级事实（订单在 trade 服务，跨服务不可读）。
   */
  @GetMapping("/capabilities")
  public InfluencerAttributionCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 下拉项（归因模型 / 分摊基准 / 退款口径 / 佣金口径 / 计提状态 / 平台 / 店铺 / 币种）。 */
  @GetMapping("/options")
  public InfluencerAttributionOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.options());
  }

  // ---- 平台同步能力 ----

  /**
   * 逐平台说清带货数据同步是否已接入。
   *
   * <p>🔴 当前所有平台都是「未接入」—— 平台适配层只有 OAuth 适配器。
   */
  @GetMapping("/sync-status")
  public InfluencerAttributionSyncStatus syncStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.syncStatus());
  }

  /**
   * 触发一次平台带货数据同步。
   *
   * <p>🔴 当前必然返回 409「平台带货数据适配器未交付」并且<b>不写任何数据</b> ——
   * 编一个假接口或返回硬编码归因列表都比这糟。
   */
  @PostMapping("/sync")
  public InfluencerAttributionSyncResult sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody InfluencerAttributionSyncRequest request) {
    return call(auth, "channel.operation.create", a -> service.sync(request));
  }

  // ---- 归因规则 ----

  /** 归因规则列表：真分页 + 真筛选。 */
  @GetMapping("/rules")
  public InfluencerAttributionRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.rules(status, keyword, page, size));
  }

  /** 生效的归因规则（没有配置返回 null —— 「没配口径」不是「口径是 0」）。 */
  @GetMapping("/rules/current")
  public InfluencerAttributionRuleRow currentRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.currentRule());
  }

  /** 配置归因规则（保存新规则时旧的自动停用）。 */
  @PostMapping("/rules")
  public InfluencerAttributionRuleRow saveRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveInfluencerAttributionRuleRequest request) {
    return call(auth, "channel.operation.update", a -> service.saveRule(request));
  }

  // ---- 归因批次 ----

  /**
   * 触发一次归因批次（按生效规则把合作记录归因到达人）。
   *
   * <p>🔴 没有生效规则 ⇒ 409；已归因过的记录不会被后续批次抹掉。
   */
  @PostMapping("/run")
  public InfluencerAttributionRunResult run(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody(required = false) InfluencerAttributionRunRequest request) {
    InfluencerAttributionRunRequest payload = request == null ? new InfluencerAttributionRunRequest(null, null, null, null, null) : request;
    return call(auth, "channel.operation.create", a -> service.run(payload));
  }

  // ---- 归因记录 ----

  /** 归因记录列表：真分页 + 真筛选 + 真排序。 */
  @GetMapping("/records")
  public InfluencerAttributionPage records(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String influencerId,
      @RequestParam(required = false) String campaignId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String ruleCode,
      @RequestParam(required = false) String accrualStatus,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) BigDecimal minAttributedGmv,
      @RequestParam(required = false) BigDecimal maxAttributedGmv,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "ATTRIBUTED_GMV_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.attributions(
                blankUuid(influencerId),
                blankUuid(campaignId),
                shopCode,
                ruleCode,
                accrualStatus,
                currency,
                minAttributedGmv,
                maxAttributedGmv,
                keyword,
                sort,
                page,
                size));
  }

  /** 归因详情：归因记录 + 达人的三个口径 + 源合作记录 + 规则快照。 */
  @GetMapping("/records/{id}")
  public InfluencerAttributionDetail record(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.attribution(id));
  }

  /** 变更佣金计提状态（计提 / 冲销 / 退回待计提）。 */
  @PostMapping("/records/{id}/accrual")
  public InfluencerAttributionDetail accrue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody InfluencerAttributionAccrualRequest request) {
    return call(auth, "channel.operation.update", a -> service.accrue(id, request));
  }

  // ---- 达人效果报表 ----

  /** 达人效果报表：三个 GMV 口径分列三栏 + ROI + 有效粉丝转化 + 评级。 */
  @GetMapping("/report")
  public InfluencerAttributionReportPage report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String influencerId,
      @RequestParam(required = false) String campaignId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String followerTier,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) BigDecimal minAttributedGmv,
      @RequestParam(required = false) BigDecimal maxAttributedGmv,
      @RequestParam(required = false, defaultValue = "ATTRIBUTED_GMV_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.report(
                blankUuid(influencerId),
                blankUuid(campaignId),
                shopCode,
                platformCode,
                category,
                followerTier,
                keyword,
                minAttributedGmv,
                maxAttributedGmv,
                sort,
                page,
                size));
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
