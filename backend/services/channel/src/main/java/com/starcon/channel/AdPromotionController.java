package com.starcon.channel;

import static com.starcon.channel.AdPromotionModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 14.4.1.1 促销列表。
 *
 * <p>蓝图：「管理平台促销活动：促销类型、适用商品、折扣幅度、起止时间、预算上限、参与店铺，
 * 同步状态与效果。输入：促销定义；平台促销接口；适用商品选择。输出：促销记录 + 平台同步状态。
 * 关系：上游 = 5 定价（促销价计算）；下游 = 平台促销 API、12 财务（费用）。
 * <b>促销的毛利必须实时测算</b>——折扣叠加后亏损是常态，需在下单前拦截或预警。」
 * 端点按蓝图模块号标注，方便对照 14-ads-and-growth.md。
 *
 * <p>🔴 入口分成四组，各自对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /capabilities}、{@code GET /options} —— 能力声明与下拉项。
 *       能力声明里**毛利 / 利润、平台效果、平台回写、促销费用四个维度显式 available=false**
 *       （各自带 ownerModule 与影响说明）—— 界面上必须让人看见这些状态，
 *       不许用假数字顶替。
 *   <li>{@code GET /promotions}、{@code GET /promotions/{uuid}} —— 「促销记录 + 平台同步状态」，
 *       真分页 + 真筛选；详情含适用商品、折后价、叠加与最近一次拦截 / 预警结论。
 *   <li>{@code POST /promotions} —— 「促销定义」+「适用商品选择」的真实写入口。
 *   <li>{@code POST /promotions/{uuid}/status|check}、{@code GET|POST /guardrails} ——
 *       「起止时间」之外的生命周期动作与**真拦截 / 真预警**的规则与结论。
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 14.1.1.1 活动列表、
 * 14.2.2.1 预算分配同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/ad-promotions")
public class AdPromotionController {

  private final AccessService access;
  private final AdPromotionService service;

  public AdPromotionController(AccessService access, AdPromotionService service) {
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
   * <p>🔴 四个不可用维度分别是：毛利 / 利润（上游 5 定价在 catalog，跨服务不可读且无端点）、
   * 平台效果（无平台促销同步源）、平台回写（平台促销 API 未交付）、促销费用（12 财务不在本仓）。
   */
  @GetMapping("/capabilities")
  public AdPromotionCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 下拉项 + 折后价 / 毛利口径说明（界面据此预填表单与表头）。 */
  @GetMapping("/options")
  public AdPromotionOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.options());
  }

  // ---- 促销记录 ----

  /** 促销列表：真分页 + 真筛选（状态 / 类型 / 平台 / 店铺 / 同步状态 / 关键字）。 */
  @GetMapping("/promotions")
  public AdPromotionPage promotions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String promotionType,
      @RequestParam(required = false) String platformId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String syncStatus,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.promotions(
                status,
                promotionType,
                blankUuid(platformId),
                shopCode,
                syncStatus,
                keyword,
                page,
                size));
  }

  /**
   * 促销详情：适用商品 + 折后价 + 叠加 + 最近一次拦截 / 预警结论。
   *
   * <p>🔴 响应里 {@code marginNote} 必须在场：毛利维度不可用这件事要让界面看得见，
   * 而不是让界面显示一个假的 0。
   */
  @GetMapping("/promotions/{id}")
  public AdPromotionDetail promotion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.promotion(id));
  }

  /**
   * 登记一条促销（促销定义 + 适用商品选择）。
   *
   * <p>🔴 建完**立刻跑一次拦截 / 预警检查**：新建的促销当场就该看见结论，
   * 而不是让人以为「建好了就没问题」。
   */
  @PostMapping("/promotions")
  public AdPromotionDetail savePromotion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SavePromotionRequest request) {
    return call(auth, "channel.operation.create", a -> service.savePromotion(request));
  }

  /**
   * 改促销定义（折扣幅度 / 起止时间 / 预算上限 / 适用商品）。
   *
   * <p>🔴 语义是**替换**：载荷里没出现的适用商品会被移出这条促销。
   * 促销编码创建后不可更改。
   */
  @PostMapping("/promotions/{id}")
  public AdPromotionDetail updatePromotion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody SavePromotionRequest request) {
    return call(auth, "channel.operation.update", a -> service.updatePromotion(id, request));
  }

  // ---- 生命周期与拦截 ----

  /**
   * 改本地状态（激活 / 暂停 / 结束 / 取消）。
   *
   * <p>🔴 激活被拦截时**拒绝并说清是哪条规则拦下的** —— 这才是蓝图加粗句
   * 「需在下单前拦截或预警」的落点，光有预警等于没拦。
   */
  @PostMapping("/promotions/{id}/status")
  public AdPromotionDetail changeStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ChangePromotionStatusRequest request) {
    return call(auth, "channel.operation.update", a -> service.changeStatus(id, request));
  }

  /** 重跑一次拦截 / 预警检查（改完折扣 / 基准价后必须重跑，旧结论不会自动消失）。 */
  @PostMapping("/promotions/{id}/check")
  public AdPromotionDetail check(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.update", a -> service.check(id));
  }

  // ---- 护栏规则 ----

  /** 护栏规则列表：真分页 + 真筛选。 */
  @GetMapping("/guardrails")
  public AdPromotionGuardrailPage guardrails(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.guardrails(status, page, size));
  }

  /**
   * 登记 / 修改护栏规则（折扣幅度上限、折后价下限、叠加是否拦截）。
   *
   * <p>🔴 一个租户**同一时刻只能有一套生效的护栏**：启用新的一套 ⇒ 旧的自动停用。
   */
  @PostMapping("/guardrails")
  public AdPromotionGuardrailRow saveGuardrail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveGuardrailRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveGuardrail(request));
  }

  /** 空串与 null 同义，避免把 {@code ?platformId=} 当成「筛选空字符串」。 */
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
