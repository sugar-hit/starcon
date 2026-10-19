package com.starcon.channel;

import static com.starcon.channel.ListingCollectModels.*;

import jakarta.validation.Valid;

import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.4.1.1 链接采集。
 *
 * <p>蓝图：「粘贴外部商品链接（1688/淘宝/供应商站/同行 listing），抓取标题、主图、规格、属性、描述等，生成采集草稿供认领为内部商品。输入：商品
 * URL（支持平台白名单）；抓取任务。输出：采集到的商品草稿（图片下载入媒体库）；来源 URL 与抓取时间。关系：上游 = 外部页面；下游 =
 * 4.4.1.3 采集箱、3.1.1.1（认领为 SPU）。<b>采集只产生草稿</b>，进入正式商品库必须走 03 的建档流程并补全属性与合规信息。」
 *
 * <p>端点按职责句逐项对应：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 逐维度能力声明（未交付段 available=false + ownerModule + impact），
 *       外加**当前白名单**（前端据此告诉用户哪些站点可以采）。
 *   <li>{@code POST /} —— 🔴 采集本身：粘贴一个商品 URL ⇒ 真实抓取 + 真实解析 ⇒ 采集草稿。
 *   <li>{@code GET /} —— 采集历史（真分页 + 状态/来源筛选 + 时间倒序）。
 *   <li>{@code GET /{id}} —— 单次采集详情（任务 + 草稿）。
 * </ul>
 *
 * <p>🔴 路径前缀刻意是 {@code /channel/listing-collects}：4.1.1.1 把 {@code listings/{uuid}/...}
 * 那一段路径登记成「不放行」，在同一棵路径树上开子资源要同时改别人已交付的白名单与拒绝清单；
 * 而「一次链接采集」本来就不是 listing 的一种状态，是**一次操作**（与 4.4.2.1 商品认领同一手法）。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 列表、4.4.2.1 商品认领、
 * 4.3.1.2 批量发布同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/listing-collects")
public class ListingCollectController {

  private final AccessService access;
  private final ListingCollectService service;

  public ListingCollectController(AccessService access, ListingCollectService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：未交付的维度 available=false + ownerModule + impact，外加当前平台白名单。
   *
   * <p>前端据此显示「图片只记 URL 不入媒体库」「采集只产生草稿」「来源域名白名单」，
   * <b>不把未交付的步骤画成已完成</b>。
   */
  @GetMapping("/capabilities")
  public ListingCollectCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 采集 ----

  /**
   * 🔴 采集一次外部商品链接。
   *
   * <p>白名单外的域名 → <b>400</b> 并说明原因（一个 HTTP 请求都不发）。
   * 同 URL 已采过 → 直接返回既有草稿（<b>不重复抓</b>）。同 host 抓得太频繁 → <b>429</b> 并写明还需等多少秒。
   */
  @PostMapping
  public CollectListingResult collect(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CollectListingRequest request) {
    return call(auth, "channel.operation.create", a -> service.collect(request, a.userId()));
  }

  // ---- 读取 ----

  /** 采集历史（真分页 + 状态与来源筛选 + 时间倒序）。 */
  @GetMapping
  public ListingCollectPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String sourceCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.list(state, sourceCode, page, size));
  }

  /** 单次采集详情（任务 + 采集草稿）。 */
  @GetMapping("/{id}")
  public CollectListingResult collect(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.detail(id));
  }
}
