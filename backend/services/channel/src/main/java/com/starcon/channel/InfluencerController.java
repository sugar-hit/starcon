package com.starcon.channel;

import static com.starcon.channel.InfluencerModels.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 14.5.1.1 达人库。
 *
 * <p>蓝图：「管理达人/联盟资源：达人档案（类目、粉丝层级、带货 GMV、互动率、合作历史）、
 * 标签分组、评级与黑名单。输入：达人数据同步（平台接口或手动录入）；标签与评级。
 * 输出：达人库 + 筛选检索。关系：上游 = 平台数据；下游 = 14.5.1.2 建联、15 客户与客服。
 * <b>达人效果数据（GMV/退款率）必须入库</b>，否则无法筛选优质达人。」
 * 端点按蓝图模块号标注，方便对照 14-ads-and-growth.md。
 *
 * <p>🔴 入口分成五组，各自对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /capabilities}、{@code GET /options} —— 能力声明与下拉项。
 *       能力声明里<b>平台达人数据同步</b>与<b>带货归因</b>两个维度显式
 *       {@code available=false}（各自带 ownerModule 与影响说明）——
 *       界面上必须让人看见这些状态，不许用假数字顶替。
 *   <li>{@code GET /profiles}、{@code GET /profiles/{uuid}} —— 「达人库 + 筛选检索」，
 *       真分页 + 真筛选（类目 / 粉丝层级 / 标签 / 标签分组 / 评级档位 / GMV 区间 /
 *       互动率区间 / 平台 / 店铺 / 关键字）+ 真排序。
 *   <li>{@code POST /profiles}、{@code POST /profiles/{uuid}}、{@code DELETE /profiles/{uuid}}
 *       —— 达人档案的真写入口（手动录入）。
 *   <li>{@code /tag-groups}、{@code /tags}、{@code /profiles/{uuid}/tags} ——
 *       标签分组与打标（批量打标与按标签检索）。
 *   <li>{@code /rating-tiers}、{@code /rating-rules}、{@code /profiles/{uuid}/rating}、
 *       {@code /blacklist}、{@code /cooperations} —— 评级、黑名单与合作历史。
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 14.1.1.1 活动列表、
 * 14.4.1.1 促销列表同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/influencers")
public class InfluencerController {

  private final AccessService access;
  private final InfluencerService service;

  public InfluencerController(AccessService access, InfluencerService service) {
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
   * <p>🔴 两个不可用维度分别是：平台达人数据同步（适配层未交付）与带货归因
   * （14.5.2.1 未交付）。
   */
  @GetMapping("/capabilities")
  public InfluencerCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 下拉项（粉丝层级 / 拉黑原因 / 合作状态 / 平台 / 店铺 / 币种 / 类目）。 */
  @GetMapping("/options")
  public InfluencerOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.options());
  }

  // ---- 平台同步能力 ----

  /**
   * 逐平台说清达人数据同步是否已接入。
   *
   * <p>🔴 当前所有平台都是「未接入」—— 平台适配层只有 OAuth 适配器。
   */
  @GetMapping("/sync-status")
  public InfluencerSyncStatus syncStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.syncStatus());
  }

  /**
   * 触发一次平台达人数据同步。
   *
   * <p>🔴 当前必然返回 409「平台达人数据适配器未交付」并且<b>不写任何数据</b> ——
   * 编一个假接口或返回硬编码达人列表都比这糟。
   */
  @PostMapping("/sync")
  public InfluencerSyncResult sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SyncInfluencersRequest request) {
    return call(auth, "channel.operation.create", a -> service.sync(request));
  }

  // ---- 达人档案 ----

  /** 达人库：真分页 + 真筛选 + 真排序（黑名单中的达人默认排除）。 */
  @GetMapping("/profiles")
  public InfluencerPage profiles(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String followerTier,
      @RequestParam(required = false) String tagId,
      @RequestParam(required = false) String tagCode,
      @RequestParam(required = false) String tagGroupId,
      @RequestParam(required = false) String tagGroupCode,
      @RequestParam(required = false) String ratingTierCode,
      @RequestParam(required = false) BigDecimal minGmv,
      @RequestParam(required = false) BigDecimal maxGmv,
      @RequestParam(required = false) BigDecimal minEngagementRate,
      @RequestParam(required = false) BigDecimal maxEngagementRate,
      @RequestParam(required = false) String platformId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "false") boolean includeBlacklisted,
      @RequestParam(required = false, defaultValue = "GMV_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.profiles(
                category,
                followerTier,
                blankUuid(tagId),
                tagCode,
                blankUuid(tagGroupId),
                tagGroupCode,
                ratingTierCode,
                minGmv,
                maxGmv,
                minEngagementRate,
                maxEngagementRate,
                blankUuid(platformId),
                shopCode,
                keyword,
                includeBlacklisted,
                sort,
                page,
                size));
  }

  /** 达人详情：档案 + 标签 + 合作历史 + 黑名单状态。 */
  @GetMapping("/profiles/{id}")
  public InfluencerDetail profile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.profile(id));
  }

  /** 手动录入一个达人（蓝图的「手动录入」—— 平台接口那一半未交付，这是已交付的那一半）。 */
  @PostMapping("/profiles")
  public InfluencerDetail saveProfile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveInfluencerRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveProfile(request));
  }

  /** 改达人档案（改完自动重算评级）。 */
  @PostMapping("/profiles/{id}")
  public InfluencerDetail updateProfile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody SaveInfluencerRequest request) {
    return call(auth, "channel.operation.update", a -> service.updateProfile(id, request));
  }

  /**
   * 删除一个达人档案。
   *
   * <p>🔴 有黑名单记录或合作历史的达人会返回 409 —— 那两条是举证凭据，不许物理删除。
   */
  @DeleteMapping("/profiles/{id}")
  public Map<String, Object> deleteProfile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(
        auth,
        "channel.operation.delete",
        a -> {
          service.deleteProfile(id);
          return Map.<String, Object>of("deleted", true);
        });
  }

  // ---- 打标 ----

  /** 批量打标（给一个标签是打标，给多个是批量打标；重复打标幂等）。 */
  @PostMapping("/profiles/{id}/tags")
  public InfluencerDetail assignTags(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody TagInfluencersRequest request) {
    return call(auth, "channel.operation.update", a -> service.assignTags(id, request));
  }

  /** 移除一个标签。 */
  @DeleteMapping("/profiles/{id}/tags/{tagId}")
  public InfluencerDetail removeTag(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @PathVariable String tagId) {
    return call(auth, "channel.operation.update", a -> service.removeTag(id, tagId));
  }

  // ---- 标签分组与标签 ----

  /** 标签分组列表：真分页 + 真筛选。 */
  @GetMapping("/tag-groups")
  public InfluencerTagGroupPage tagGroups(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.tagGroups(status, keyword, page, size));
  }

  /** 登记一个标签分组。 */
  @PostMapping("/tag-groups")
  public InfluencerTagGroupRow saveTagGroup(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveInfluencerTagGroupRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveTagGroup(request));
  }

  /** 改一个标签分组。 */
  @PostMapping("/tag-groups/{id}")
  public InfluencerTagGroupRow updateTagGroup(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody SaveInfluencerTagGroupRequest request) {
    return call(auth, "channel.operation.update", a -> service.updateTagGroup(id, request));
  }

  /** 删一个标签分组（分组下还有标签时返回 409）。 */
  @DeleteMapping("/tag-groups/{id}")
  public Map<String, Object> deleteTagGroup(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(
        auth,
        "channel.operation.delete",
        a -> {
          service.deleteTagGroup(id);
          return Map.<String, Object>of("deleted", true);
        });
  }

  /** 标签列表：真分页 + 真筛选（按分组 / 状态 / 关键字）。 */
  @GetMapping("/tags")
  public InfluencerTagPage tags(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String groupCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.tags(groupCode, status, keyword, page, size));
  }

  /** 登记一个标签。 */
  @PostMapping("/tags")
  public InfluencerTagRow saveTag(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveInfluencerTagRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveTag(request));
  }

  /** 改一个标签。 */
  @PostMapping("/tags/{id}")
  public InfluencerTagRow updateTag(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody SaveInfluencerTagRequest request) {
    return call(auth, "channel.operation.update", a -> service.updateTag(id, request));
  }

  /** 删一个标签（还被达人引用时返回 409）。 */
  @DeleteMapping("/tags/{id}")
  public Map<String, Object> deleteTag(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(
        auth,
        "channel.operation.delete",
        a -> {
          service.deleteTag(id);
          return Map.<String, Object>of("deleted", true);
        });
  }

  // ---- 评级 ----

  /** 评级档位列表：真分页 + 真筛选。 */
  @GetMapping("/rating-tiers")
  public InfluencerRatingTierPage ratingTiers(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.ratingTiers(status, page, size));
  }

  /** 登记一个评级档位。 */
  @PostMapping("/rating-tiers")
  public InfluencerRatingTierRow saveRatingTier(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveInfluencerRatingTierRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveRatingTier(request));
  }

  /** 改一个评级档位（区间改了会触发全库重算评级）。 */
  @PostMapping("/rating-tiers/{id}")
  public InfluencerRatingTierRow updateRatingTier(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody SaveInfluencerRatingTierRequest request) {
    return call(auth, "channel.operation.update", a -> service.updateRatingTier(id, request));
  }

  /** 删一个评级档位（还有达人挂在上面时返回 409）。 */
  @DeleteMapping("/rating-tiers/{id}")
  public Map<String, Object> deleteRatingTier(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(
        auth,
        "channel.operation.delete",
        a -> {
          service.deleteRatingTier(id);
          return Map.<String, Object>of("deleted", true);
        });
  }

  /** 生效的评分口径（没有配置返回 null —— 「没配口径」不是「口径是 0」）。 */
  @GetMapping("/rating-rules")
  public InfluencerRatingRuleRow ratingRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.ratingRule());
  }

  /** 配置评分口径（保存新口径时旧的自动停用，并全库重算评级）。 */
  @PostMapping("/rating-rules")
  public InfluencerRatingRuleRow saveRatingRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveInfluencerRatingRuleRequest request) {
    return call(auth, "channel.operation.update", a -> service.saveRatingRule(request));
  }

  /** 重算一个达人的评级。 */
  @PostMapping("/profiles/{id}/rating")
  public InfluencerDetail recomputeRating(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.update", a -> service.recomputeRating(id));
  }

  // ---- 黑名单 ----

  /** 黑名单列表（生效中的与已解除的都在这里查得到）：真分页 + 真筛选。 */
  @GetMapping("/blacklist")
  public InfluencerBlacklistPage blacklist(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String reasonCode,
      @RequestParam(required = false) String influencerId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.blacklist(
                status, reasonCode, blankUuid(influencerId), keyword, page, size));
  }

  /** 拉黑一个达人（拉黑后自动从正常检索里排除）。 */
  @PostMapping("/blacklist")
  public InfluencerBlacklistRow blacklistInfluencer(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BlacklistInfluencerRequest request) {
    return call(auth, "channel.operation.create", a -> service.blacklistInfluencer(request));
  }

  /** 解除黑名单（留痕：解除时刻 + 解除理由）。 */
  @PostMapping("/blacklist/{id}/release")
  public InfluencerBlacklistRow releaseBlacklist(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ReleaseBlacklistRequest request) {
    return call(auth, "channel.operation.update", a -> service.releaseBlacklist(id, request));
  }

  // ---- 合作历史 ----

  /** 合作历史列表（达人 × 活动）：真分页 + 真筛选。 */
  @GetMapping("/cooperations")
  public InfluencerCooperationPage cooperations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String influencerId,
      @RequestParam(required = false) String campaignId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.cooperations(
                blankUuid(influencerId),
                blankUuid(campaignId),
                shopCode,
                status,
                page,
                size));
  }

  /** 登记一条合作记录。 */
  @PostMapping("/cooperations")
  public InfluencerCooperationRow saveCooperation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveCooperationRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveCooperation(request));
  }

  /** 改一条合作记录（活动不可就地改）。 */
  @PostMapping("/cooperations/{id}")
  public InfluencerCooperationRow updateCooperation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody SaveCooperationRequest request) {
    return call(auth, "channel.operation.update", a -> service.updateCooperation(id, request));
  }

  /** 空串与 null 同义，避免把 {@code ?tagId=} 当成「筛选空字符串」。 */
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
