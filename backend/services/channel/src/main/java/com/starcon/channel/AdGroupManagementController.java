package com.starcon.channel;

import static com.starcon.channel.AdGroupManagementModels.*;

import jakarta.validation.Valid;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 14.1.2.1 广告组管理。
 *
 * <p>蓝图：「管理广告组：关联投放商品、匹配类型、定向设置、默认竞价，查看组级表现。
 * 输入：广告组设置；关联商品选择（从 4 listing 中选）。
 * 输出：广告组记录 + 组级报表入口。
 * 关系：上游 = 14.1.1.2；下游 = 14.1.3.1 关键词、14.2.1.1 竞价。
 * <b>广告组与 SKU 的映射是利润归因的基础</b>——投放的是 listing，归因要落到内部 SKU。」
 * 端点按蓝图模块号标注，方便对照 14-ads-and-growth.md。
 *
 * <p>🔴 入口分成三组，各自对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明。未交付的段（组级指标 / 组级关键词 /
 *       竞价策略 / 投放定位解读 / SKU 维度归因 / 平台回写）显式 {@code available=false}
 *       + {@code ownerModule} + 影响；组级报表入口显式 {@code available=true} 并给出
 *       14.3.1.2 的路由标识。
 *   <li>{@code GET /groups**} —— 广告组记录：列表（真分页 + 真筛选 + 真排序）、详情、
 *       组级操作审计。
 *   <li>{@code POST /groups} · {@code PATCH /groups/{groupId}} ·
 *       {@code PUT /groups/{groupId}/products} —— 「输入：广告组设置；关联商品选择」的
 *       **真实写入口**：新建一组 / 改一组设置 / 设这一组的投放商品。
 * </ul>
 *
 * <p>🔴 本模块**不另起「改竞价」的同义端点**：改竞价是钱，上游 14.1.1.2 已有带护栏与审计的
 * {@code POST /channel/ad-campaign-details/campaigns/{id}/bids}，本模块**复用**它 ——
 * 两个端点改同一笔钱，审计就会分叉。能力声明里 {@code default_bid} 维度写明了这一点。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 14.1.1.1 / 14.1.1.2 /
 * 14.1.2.2 同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/ad-groups")
public class AdGroupManagementController {

  private final AccessService access;
  private final AdGroupManagementService service;

  public AdGroupManagementController(AccessService access, AdGroupManagementService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：未交付的段（组级指标 / 竞价策略 / 投放定位解读 / SKU 归因 / 平台回写）显式 available=false。 */
  @GetMapping("/capabilities")
  public AdGroupManagementCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 筛选项下拉 + 护栏上限 + 报表路由标识。 */
  @GetMapping("/options")
  public AdGroupOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.options());
  }

  // ---- 广告组记录 ----

  /**
   * 广告组列表：真分页 + 真筛选 + 真排序（可跨活动）。
   *
   * <p>🔴 汇总**不做跨币种金额相加**：广告组的竞价与预算按币种分列。
   */
  @GetMapping
  public AdGroupPage groups(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String campaignId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String matchType,
      @RequestParam(required = false) String targetingType,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.groups(
                campaignId, status, keyword, matchType, targetingType, shopCode, sort, page, size));
  }

  /** 组级列表汇总（不受分页影响；只给条数，不做跨币种金额相加）。 */
  @GetMapping("/summary")
  public AdGroupSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String campaignId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String matchType,
      @RequestParam(required = false) String targetingType,
      @RequestParam(required = false) String shopCode) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.summary(campaignId, status, keyword, matchType, targetingType, shopCode));
  }

  /**
   * 广告组详情：组设置 + 投放商品（含只读带出的内部 SKU 映射现状）+ 下游入口 + 审计。
   *
   * <p>🔴 详情里**没有组级指标**：现有事实表里没有组级指标表 ⇒ 组级表现拿不到真实指标。
   * 页面必须显示「拿不到」，不许拿活动级指标冒充。
   */
  @GetMapping("/{groupId}")
  public AdGroupDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String groupId) {
    return call(auth, "channel.operation.read", a -> service.detail(groupId));
  }

  /** 组级操作审计：真分页（按时刻倒序）。 */
  @GetMapping("/{groupId}/operations")
  public AdGroupOperationPage operations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String groupId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.operations(groupId, page, size));
  }

  /**
   * 可选投放商品候选：该活动**所在店铺**的 listing（真分页 + 真筛选）。
   *
   * <p>蓝图的输入写着「关联商品选择（从 4 listing 中选）」⇒ 候选必须是**真的 listing**。
   */
  @GetMapping("/product-candidates")
  public AdGroupProductCandidatePage productCandidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String campaignId,
      @RequestParam(required = false) String groupId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.productCandidates(campaignId, groupId, keyword, page, size));
  }

  // ---- 写入口 ----

  /**
   * 新建广告组。
   *
   * <p>🔴 本地真落库，但**平台侧还没有这个组**：平台侧 id 落成 {@code LOCAL-{uuid}}
   * （本地标记，不是平台 id）、状态落暂停、并标上「待回写平台」。
   */
  @PostMapping
  public AdGroupCreateResult create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AdGroupCreateRequest request) {
    return call(auth, "channel.operation.create", a -> service.create(request));
  }

  /**
   * 改组设置（部分更新：没给的字段不改）。
   *
   * <p>🔴 **默认竞价不在这里**：改竞价是钱，走上游 14.1.1.2 的改竞价入口（带护栏与审计）。
   * 🔴 把组级预算恢复成「继承活动预算」要显式给 {@code clearDailyBudget=true}。
   */
  @PatchMapping("/{groupId}")
  public AdGroupSettingsUpdateResult updateSettings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String groupId,
      @Valid @RequestBody AdGroupSettingsUpdateRequest request) {
    return call(auth, "channel.operation.update", a -> service.updateSettings(groupId, request));
  }

  /**
   * 设投放商品（**替换**语义：没列出的会被解除关联）。
   *
   * <p>🔴 投放的商品必须属于活动所在店铺 —— listing 属于店铺，跨店铺投放不成立。
   */
  @PutMapping("/{groupId}/products")
  public AdGroupProductUpdateResult setProducts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String groupId,
      @Valid @RequestBody AdGroupProductUpdateRequest request) {
    return call(auth, "channel.operation.update", a -> service.setProducts(groupId, request));
  }
}
