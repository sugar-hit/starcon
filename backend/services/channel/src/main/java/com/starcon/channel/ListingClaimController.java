package com.starcon.channel;

import static com.starcon.channel.ListingClaimModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.4.2.1 商品认领。
 *
 * <p>蓝图：「把已在 A 店铺的 listing 认领到 B 店铺（跨店搬家），复用原商品内容与映射，生成 B 店铺的新 listing 草稿。输入：源
 * listing；目标店铺与站点；保留/重写的内容字段选择。输出：目标店铺的 listing 草稿 + 待发布任务。关系：上游 = 4.1.1.2；下游 =
 * 4.3.1.1。<b>新店铺 listing 是全新平台资源</b>，需重新过平台审核；<b>源 listing 不受影响</b>。」
 *
 * <p>端点按职责句逐项对应：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 逐维度能力声明（售价 / 内部可售量 / 平台审核 / 内部内容草稿 /
 *       跨平台认领 available=false + ownerModule + impact），以及「源不受影响」「目标恒为草稿态」
 *       两条边界的界面措辞。
 *   <li>{@code POST /} —— 🔴 认领本身：源 listing + 目标店铺与站点 + 逐字段的保留 / 重写决策
 *       ⇒ 目标店铺的 listing 草稿 + 待发布任务。
 *   <li>{@code GET /} / {@code GET /{id}} —— 认领历史与单次认领详情（含逐字段决策）。
 *   <li>{@code GET /listings/{listingId}/claims} —— 某个商品的认领历史（作为源或作为目标）。
 * </ul>
 *
 * <p>🔴 路径前缀刻意是 {@code /channel/listing-claims}：4.1.1.1 把 {@code listings/{uuid}/...} 那一段
 * 路径登记成「不放行」，在同一棵路径树上开子资源要同时改别人已交付的白名单与拒绝清单；而
 * 「跨店搬家」本来就不是 listing 的一种状态，是**一次操作**。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 列表、4.1.1.2 详情、
 * 4.2.1.2 批量编辑、4.3.1.2 批量发布同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/listing-claims")
public class ListingClaimController {

  private final AccessService access;
  private final ListingClaimService service;

  public ListingClaimController(AccessService access, ListingClaimService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：未交付的维度 available=false + ownerModule + impact。
   *
   * <p>前端据此显示「售价归 05 定价」「目标尚未过平台审核」「内部内容草稿未交付」，
   * <b>不把未交付的步骤画成已完成</b>。
   */
  @GetMapping("/capabilities")
  public ListingClaimCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 认领 ----

  /**
   * 🔴 认领：把源 listing 认领到目标店铺，生成目标草稿 + 待发布任务。
   *
   * <p>🔴 这次调用<b>不改源 listing</b>（只读），产出的目标 listing 恒为草稿态
   * （{@code status=DRAFT} / {@code review_status=NOT_SUBMITTED}），并且**不写发布记录** ——
   * 新店铺 listing 是全新平台资源，需重新过平台审核。
   */
  @PostMapping
  public ListingClaimRow claim(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ClaimListingRequest request) {
    return call(auth, "channel.operation.create", a -> service.claim(request, a.userId()));
  }

  // ---- 读取 ----

  /** 认领历史（真分页 + 状态与目标店铺筛选）。 */
  @GetMapping
  public List<ListingClaimRow> claims(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String targetShopCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "channel.operation.read", a -> service.list(state, targetShopCode, page, size));
  }

  /** 单次认领的详情（含逐字段的保留 / 重写决策与源侧「认领时 vs 现在」的对照）。 */
  @GetMapping("/{id}")
  public ListingClaimRow claim(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth, @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.detail(id));
  }

  /** 某个商品的认领历史（作为源或作为目标都算）。 */
  @GetMapping("/listings/{listingId}/claims")
  public List<ListingClaimRow> forListing(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String listingId) {
    return call(auth, "channel.operation.read", a -> service.forListing(listingId));
  }
}
