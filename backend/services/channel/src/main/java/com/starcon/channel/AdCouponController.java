package com.starcon.channel;

import static com.starcon.channel.AdCouponModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 14.4.1.2 优惠券与秒杀（券这一半）。
 *
 * <p>蓝图：「管理优惠券（折扣码、满减、买赠）与秒杀活动：生成码、设置库存与限领、
 * 投放渠道（站内/站外/社媒），跟踪领取与核销。输入：券规则；投放计划；核销数据同步。
 * 输出：券记录 + 核销统计。关系：上游 = 5 定价；下游 = 平台营销接口、15 客户（定向发放）。
 * <b>券的核销要与订单绑定</b>，否则无法评估活动真实效果与补贴成本。」
 * 端点按蓝图模块号标注，方便对照 14-ads-and-growth.md。
 *
 * <p>🔴 入口分成五组，各自对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /capabilities}、{@code GET /options} —— 能力声明与下拉项。
 *       能力声明里**平台营销回写 / 客户主数据 / 买赠补贴成本三个维度显式 available=false**
 *       （各自带 ownerModule 与影响说明）—— 界面上必须让人看见这些状态，
 *       不许用假数字顶替。</li>
 *   <li>{@code GET /coupons}、{@code GET /coupons/{uuid}} —— 「券记录」，真分页 + 真筛选；
 *       详情含投放渠道、最近领取、最近核销与核销统计。</li>
 *   <li>{@code POST /coupons}、{@code POST /coupons/{uuid}}、{@code POST /coupons/{uuid}/status}
 *       —— 「券规则」的创建 / 修改 / 停用。</li>
 *   <li>{@code POST /coupons/{uuid}/codes}、{@code GET /coupons/{uuid}/codes}、
 *       {@code POST /coupons/{uuid}/claims} —— 「生成码」「设置库存与限领」的落点：
 *       批量生成（真库存计数）+ 领取（限领校验真读真扣）+ 定向发放。</li>
 *   <li>{@code POST /coupons/{uuid}/redemptions}、{@code GET /coupons/{uuid}/statistics}、
 *       {@code GET|POST /coupons/{uuid}/channels} —— 「核销数据同步」（绑定订单标识 +
 *       补贴成本落库）、核销统计（真聚合）与投放渠道 / 投放计划。</li>
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 14.4.1.1 促销列表同族），
 * identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/ad-coupons")
public class AdCouponController {

  private final AccessService access;
  private final AdCouponService service;

  public AdCouponController(AccessService access, AdCouponService service) {
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
   * <p>🔴 三个不可用维度分别是：平台营销回写（平台营销接口未交付）、
   * 客户主数据（下游 15 客户未交付）、买赠补贴成本（成本价在 05 定价，跨服务不可读）。
   */
  @GetMapping("/capabilities")
  public AdCouponCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 下拉项 + 门槛 / 面额口径说明（界面据此预填表单与表头）。 */
  @GetMapping("/options")
  public AdCouponOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.options());
  }

  // ---- 券记录 ----

  /** 券列表：真分页 + 真筛选（状态 / 类型 / 平台 / 店铺 / 渠道 / 同步状态 / 关键字）。 */
  @GetMapping("/coupons")
  public AdCouponPage coupons(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String couponType,
      @RequestParam(required = false) String platformId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String channelType,
      @RequestParam(required = false) String syncStatus,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.coupons(
                status,
                couponType,
                blankUuid(platformId),
                shopCode,
                channelType,
                syncStatus,
                keyword,
                page,
                size));
  }

  /**
   * 券详情：投放渠道 + 最近领取 + 最近核销 + 核销统计。
   *
   * <p>🔴 响应里 {@code syncNote} 必须在场：平台营销接口未交付这件事要让界面看得见。
   */
  @GetMapping("/coupons/{id}")
  public AdCouponDetail coupon(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.coupon(id));
  }

  /** 登记一张券（券规则）。 */
  @PostMapping("/coupons")
  public AdCouponDetail saveCoupon(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveCouponRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveCoupon(request));
  }

  /** 改券规则（面额 / 门槛 / 库存 / 限领 / 生成规则 / 生效时间窗）。券编码创建后不可改。 */
  @PostMapping("/coupons/{id}")
  public AdCouponDetail updateCoupon(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody SaveCouponRequest request) {
    return call(auth, "channel.operation.update", a -> service.updateCoupon(id, request));
  }

  /** 改本地状态（上线 / 暂停 / 结束 / 停用）。终态不可回退。 */
  @PostMapping("/coupons/{id}/status")
  public AdCouponDetail changeStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ChangeCouponStatusRequest request) {
    return call(auth, "channel.operation.update", a -> service.changeStatus(id, request));
  }

  // ---- 生成码 ----

  /**
   * 批量生成券码。
   *
   * <p>🔴 库存是真扣的：请求数超过剩余库存直接 409（不做静默截断）。
   */
  @PostMapping("/coupons/{id}/codes")
  public AdCouponCodePage generateCodes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody GenerateCouponCodesRequest request) {
    return call(auth, "channel.operation.update", a -> service.generateCodes(id, request));
  }

  /** 券码列表：真分页 + 真筛选（按状态）。 */
  @GetMapping("/coupons/{id}/codes")
  public AdCouponCodePage codes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.codes(id, status, page, size));
  }

  // ---- 领取与定向发放 ----

  /**
   * 领取一张券（用户主动领取 / 定向发放 / 渠道投放领取）。
   *
   * <p>🔴 限领判据是「这个人在这张券上累计领了几张」，超出直接 409。
   */
  @PostMapping("/coupons/{id}/claims")
  public AdCouponDetail claim(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ClaimCouponRequest request) {
    return call(auth, "channel.operation.update", a -> service.claim(id, request));
  }

  // ---- 核销与统计 ----

  /**
   * 核销一张券。
   *
   * <p>🔴 蓝图加粗句：**订单标识为空直接拒绝** —— 没订单标识的核销写不进来。
   */
  @PostMapping("/coupons/{id}/redemptions")
  public AdCouponDetail redeem(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody RedeemCouponRequest request) {
    return call(auth, "channel.operation.update", a -> service.redeem(id, request));
  }

  /** 核销统计（真聚合）：核销率 / 补贴成本 / 渠道拆分 / 按日拆分。 */
  @GetMapping("/coupons/{id}/statistics")
  public AdCouponStatistics statistics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.statistics(id));
  }

  // ---- 投放渠道与投放计划 ----

  /** 投放渠道（站内 / 站外 / 社媒）+ 投放计划。 */
  @GetMapping("/coupons/{id}/channels")
  public java.util.List<AdCouponChannelRow> channels(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.channels(id));
  }

  /** 登记一条投放渠道与投放计划。 */
  @PostMapping("/coupons/{id}/channels")
  public AdCouponChannelRow saveChannel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody SaveCouponChannelRequest request) {
    return call(auth, "channel.operation.update", a -> service.saveChannel(id, request));
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
