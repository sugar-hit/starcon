package com.starcon.channel;

import static com.starcon.channel.AdCampaignDetailModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/**
 * 14.1.1.2 活动详情。
 *
 * <p>蓝图：「单活动全景：基本设置、预算与消耗曲线、广告组列表、投放商品、关键词、创意（视频/图片）、
 * 每日表现明细、平台侧修改历史。输入：campaign_id；聚合子资源。
 * 输出：详情视图 + 操作入口（调预算、改竞价、暂停、复制结构）。
 * 关系：上游 = 14.1.1.1；下游 = 14.1.2.1 广告组、14.3.1.1 报表。
 * <b>"复制结构"是高价值操作</b>：把跑得好的活动结构复制到新店铺/新 ASIN，是广告提效的核心动作。」
 * 端点按蓝图模块号标注，方便对照 14-ads-and-growth.md。
 *
 * <p>🔴 入口分成四组，各自对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明。未交付的段（平台侧修改历史 / 平台回写 /
 *       广告组管理 / 竞价策略 / 投放定位）显式 {@code available=false} + {@code ownerModule} + 影响。
 *   <li>{@code GET /campaigns/{uuid}**} —— 详情全景与各子资源列表：基本设置、预算与消耗曲线、
 *       每日表现明细、广告组、投放商品、创意、关键词、修改历史。
 *   <li>{@code POST /campaigns/{uuid}/structure} —— 「输入：聚合子资源」的结构**真实写入口**：
 *       平台广告 API 同步器尚未交付，结构必须真的有落点。
 *   <li>{@code POST /campaigns/{uuid}/bids|copy} —— 「操作入口」里本模块自己实现的两个动作
 *       （改竞价、复制结构）。调预算与暂停**复用上游 14.1.1.1** 的批量操作入口，不另起一套。
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 14.1.1.1 / 14.1.2.2 同族），
 * identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/ad-campaign-details")
public class AdCampaignDetailController {

  private final AccessService access;
  private final AdCampaignDetailService service;

  public AdCampaignDetailController(AccessService access, AdCampaignDetailService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：未交付的段（平台侧修改历史 / 平台回写 / 广告组管理 / 竞价策略）显式 available=false。 */
  @GetMapping("/capabilities")
  public AdCampaignDetailCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 详情全景 ----

  /**
   * 单活动全景：基本设置 + 预算与消耗曲线 + 每日表现明细 + 各子资源条数。
   *
   * <p>🔴 响应里三样东西不能省：
   *
   * <ul>
   *   <li>{@code budgetCurve} —— 预算与消耗曲线：逐日消耗 vs 当日预算 + 预算使用率。
   *   <li>{@code structure} —— 广告组 / 投放商品 / 创意 / 关键词的条数与结构截至时刻；
   *       条数为 0 时要能区分「没结构」与「还没登记结构」。
   *   <li>{@code changeHistory} —— 平台侧修改历史**显式不可用**（没有平台广告 API 同步源），
   *       并把当前展示的「本地操作审计」写清楚。
   * </ul>
   */
  @GetMapping("/campaigns/{id}")
  public AdCampaignDetailPanorama panorama(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(auth, "channel.operation.read", a -> service.panorama(id, from, to));
  }

  /** 广告组列表：真分页 + 真筛选。 */
  @GetMapping("/campaigns/{id}/groups")
  public AdCampaignGroupPage groups(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.groups(id, keyword, status, page, size));
  }

  /** 投放商品列表：真分页 + 真筛选（带 listing 状态）。 */
  @GetMapping("/campaigns/{id}/products")
  public AdCampaignProductPage products(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.products(id, keyword, page, size));
  }

  /** 创意列表（视频 / 图片 / 图集 / 文本）：真分页 + 按类型筛选。 */
  @GetMapping("/campaigns/{id}/creatives")
  public AdCampaignCreativePage creatives(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false) String creativeType,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "channel.operation.read", a -> service.creatives(id, creativeType, page, size));
  }

  /**
   * 关键词列表：**读上游 14.1.3.1 已交付的关键词表**（本模块只读消费，不建第二张表）。
   */
  @GetMapping("/campaigns/{id}/keywords")
  public AdCampaignKeywordPage keywords(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.keywords(id, keyword, page, size));
  }

  /**
   * 平台侧修改历史。
   *
   * <p>🔴 这个端点**恒定**返回 {@code available=false}：上游没有平台广告 API 同步器 ⇒
   * 没有平台侧改历史的数据源。当前返回的是**本地**操作审计，
   * 两者在字段名与文案上分得开 —— 把本地审计说成平台侧改历史就是在骗人。
   */
  @GetMapping("/campaigns/{id}/change-history")
  public AdCampaignChangeHistory changeHistory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.changeHistory(id));
  }

  // ---- 输入：结构数据的真实写入口 ----

  /**
   * 登记一个活动的结构（广告组 / 投放商品 / 创意）。
   *
   * <p>🔴 平台广告 API 同步器**尚未交付**，但结构必须有真的落点 ——
   * 这个端点就是那个落点，**绝不用空数组或硬编码常量假装有广告组**。
   *
   * <p>🔴 语义是**替换**：一次登记代表「这是平台返回的该活动当前完整结构」，
   * 载荷里没出现的广告组 / 组内商品 / 组内创意会被删掉。
   */
  @PostMapping("/campaigns/{id}/structure")
  public AdCampaignStructureImportResult structure(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody AdCampaignStructureImportRequest request) {
    return call(
        auth, "channel.operation.create", a -> service.importStructure(id, request));
  }

  // ---- 操作入口 ----

  /**
   * 改广告组的默认竞价：逐条返回执行结果，带上限保护与审计。
   *
   * <p>🔴 竞价是钱 —— 超限的那几条**逐条拒绝**并写进审计（被拒也要留痕）。
   */
  @PostMapping("/campaigns/{id}/bids")
  public AdCampaignBidUpdateResult bids(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody AdCampaignBidUpdateRequest request) {
    return call(auth, "channel.operation.update", a -> service.updateBids(id, request));
  }

  /**
   * 把活动结构复制到新店铺 / 新 ASIN。
   *
   * <p>🔴 结构副本**真的落库**（目标活动 + 广告组 + 投放商品 + 创意），
   * 但**平台侧还没有这份结构**（平台回写队列未交付）⇒ 目标活动标「待回写平台」且状态暂停。
   * 🔴 关键词**不复制**：平台侧还没建这个活动，关键词 id 无从分配。
   */
  @PostMapping("/campaigns/{id}/copy")
  public AdCampaignCopyResult copy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody AdCampaignCopyRequest request) {
    return call(auth, "channel.operation.create", a -> service.copy(id, request));
  }
}
