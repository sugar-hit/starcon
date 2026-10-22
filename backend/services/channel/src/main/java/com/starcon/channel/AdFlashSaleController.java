package com.starcon.channel;

import static com.starcon.channel.AdFlashSaleModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 14.4.1.2 优惠券与秒杀（秒杀这一半）。
 *
 * <p>蓝图：「管理优惠券（折扣码、满减、买赠）与**秒杀活动**：生成码、设置库存与限领、
 * 投放渠道（站内/站外/社媒），跟踪领取与核销。……<b>券的核销要与订单绑定</b>，
 * 否则无法评估活动真实效果与补贴成本。」
 * 端点按蓝图模块号标注，方便对照 14-ads-and-growth.md。
 *
 * <p>🔴 入口分成四组：
 *
 * <ul>
 *   <li>{@code GET /capabilities}、{@code GET /options} —— 能力声明与下拉项。
 *       能力声明里**平台营销回写与毛利两个维度显式 available=false**。</li>
 *   <li>{@code GET /flash-sales}、{@code GET /flash-sales/{uuid}} —— 秒杀记录，真分页 + 真筛选。</li>
 *   <li>{@code POST /flash-sales}、{@code POST /flash-sales/{uuid}}、
 *       {@code POST /flash-sales/{uuid}/status} —— 秒杀定义的创建 / 修改 / 停用。</li>
 *   <li>{@code POST /flash-sales/{uuid}/claims}、{@code POST /flash-sales/{uuid}/redemptions}、
 *       {@code GET /flash-sales/{uuid}/statistics} —— 领取（库存真扣 + 限领）、
 *       核销（绑定订单标识）与统计（真聚合）。</li>
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 14.4.1.1 促销列表同族），
 * identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/ad-flash-sales")
public class AdFlashSaleController {

  private final AccessService access;
  private final AdFlashSaleService service;

  public AdFlashSaleController(AccessService access, AdFlashSaleService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：逐维度说清「哪些能算、哪些拿不到」。 */
  @GetMapping("/capabilities")
  public AdFlashSaleCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 下拉项 + 秒杀价 / 原价口径说明。 */
  @GetMapping("/options")
  public AdFlashSaleOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.options());
  }

  // ---- 秒杀记录 ----

  /** 秒杀列表：真分页 + 真筛选（状态 / 平台 / 店铺 / 渠道 / 同步状态 / 关键字）。 */
  @GetMapping("/flash-sales")
  public AdFlashSalePage flashSales(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
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
            service.flashSales(
                status,
                blankUuid(platformId),
                shopCode,
                channelType,
                syncStatus,
                keyword,
                page,
                size));
  }

  /** 秒杀详情：最近领取 + 统计（真聚合）。 */
  @GetMapping("/flash-sales/{id}")
  public AdFlashSaleDetail flashSale(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.flashSale(id));
  }

  /** 登记一个秒杀活动。 */
  @PostMapping("/flash-sales")
  public AdFlashSaleDetail saveFlashSale(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveFlashSaleRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveFlashSale(request));
  }

  /** 改秒杀定义（秒杀价 / 库存 / 限领 / 限时 / 投放渠道）。秒杀编码创建后不可改。 */
  @PostMapping("/flash-sales/{id}")
  public AdFlashSaleDetail updateFlashSale(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody SaveFlashSaleRequest request) {
    return call(auth, "channel.operation.update", a -> service.updateFlashSale(id, request));
  }

  /** 改本地状态（上线 / 暂停 / 结束 / 停用）。终态不可回退。 */
  @PostMapping("/flash-sales/{id}/status")
  public AdFlashSaleDetail changeStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ChangeFlashSaleStatusRequest request) {
    return call(auth, "channel.operation.update", a -> service.changeStatus(id, request));
  }

  // ---- 领取与核销 ----

  /** 领取秒杀（库存真扣 + 按人限领）。 */
  @PostMapping("/flash-sales/{id}/claims")
  public AdFlashSaleDetail claim(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ClaimFlashSaleRequest request) {
    return call(auth, "channel.operation.update", a -> service.claim(id, request));
  }

  /**
   * 核销一次秒杀领取。
   *
   * <p>🔴 蓝图加粗句：**订单标识为空直接拒绝** —— 没订单标识的核销写不进来。
   */
  @PostMapping("/flash-sales/{id}/redemptions")
  public AdFlashSaleDetail redeem(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody RedeemFlashSaleRequest request) {
    return call(auth, "channel.operation.update", a -> service.redeem(id, request));
  }

  /** 秒杀统计（真聚合）：核销率 / 补贴成本 / 按日拆分。 */
  @GetMapping("/flash-sales/{id}/statistics")
  public AdFlashSaleStatistics statistics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.statistics(id));
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
