package com.starcon.channel;

import static com.starcon.channel.InfluencerModels.*;
import static com.starcon.channel.InfluencerRules.*;

import com.starcon.channel.vendor.InfluencerDataPort;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 14.5.1.1 达人库。
 *
 * <p>蓝图：「管理达人/联盟资源：达人档案（类目、粉丝层级、带货 GMV、互动率、合作历史）、
 * 标签分组、评级与黑名单。输入：达人数据同步（平台接口或手动录入）；标签与评级。
 * 输出：达人库 + 筛选检索。关系：上游 = 平台数据；下游 = 14.5.1.2 建联、15 客户与客服。
 * <b>达人效果数据（GMV/退款率）必须入库</b>，否则无法筛选优质达人。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>达人档案</b>（类目 / 粉丝层级 / 带货 GMV / 互动率）→ {@link #saveProfile} +
 *       {@link #profiles}：真写库、真筛选、真分页、真排序。
 *   <li><b>合作历史</b> → {@link #saveCooperation}：达人 × 活动（{@code ad_campaign}），
 *       带效果数据（GMV / 订单量 / 佣金 / 退款率）。
 *   <li><b>标签分组</b> → {@link #tagGroups} / {@link #tags} / {@link #assignTags}：
 *       分组 → 标签 → 达人，多对多挂载，按标签反查达人。
 *   <li><b>评级</b> → {@link #ratingRule}（评分口径）+ {@link #ratingTiers}（档位）
 *       + {@link #recomputeRating}：**由效果数据算出来**，不是手填标签。
 *   <li><b>黑名单</b> → {@link #blacklist} / {@link #releaseBlacklist}：
 *       拉黑后**从正常检索里排除**，解除留痕。
 * </ul>
 *
 * <p>🔴 本模块最容易犯的四个错，这里逐个挡住：
 *
 * <ol>
 *   <li><b>把「没录」写成 0</b>。GMV / 互动率 / 退款率没录就是 null（不可比），
 *       0 会被读成「这个达人一单没带过货」。同理，评分算不出来时是 null 而不是 0 分。
 *   <li><b>编一个平台达人接口</b>。平台适配层只有 OAuth 适配器，**没有达人数据适配器**
 *       ⇒ 同步端口对所有平台答「未接入」，同步入口据此<b>不写任何数据</b>。
 *   <li><b>把「本地已录」说成「平台侧已录」</b>。{@code syncSource} 与
 *       {@code platformSyncStatus} 表达「本地手动录入、平台侧还没有这个达人」。
 *   <li><b>让拉黑悄悄消失</b>。黑名单是独立事实表，解除是**留痕**（解除时刻 + 理由），
 *       不是删行；黑名单记录还在的达人**不许被物理删除**。
 * </ol>
 *
 * <p>🔴 数据来源纪律：本模块**只读**同库的 {@code shop} / {@code platform} / {@code ad_campaign}，
 * 写入一律落在自己的八张表上。达人**不塞进 {@code ad_campaign}** ——
 * 广告活动与达人档案是两件事（一个达人可以参与多个活动，一个活动可以有多个达人）。
 */
@Service
public class InfluencerService {

  // ==================================================================
  // 常量：取值域与口径
  // ==================================================================

  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 拉黑原因封闭词表（与 V288 的 CHECK 一致）。 */
  static final List<String> BLACKLIST_REASON_CODES = InfluencerRules.BLACKLIST_REASON_CODES;

  /** 排序白名单：**只认这几个键**，其余一律回落到默认排序（防注入）。 */
  private static final Map<String, String> SORTS =
      Map.of(
          "GMV_DESC", "i.gmv DESC NULLS LAST, i.id",
          "GMV_ASC", "i.gmv ASC NULLS LAST, i.id",
          "FOLLOWERS_DESC", "i.follower_count DESC, i.id",
          "ENGAGEMENT_DESC", "i.engagement_rate DESC NULLS LAST, i.id",
          "RATING_DESC", "i.rating_score DESC NULLS LAST, i.id",
          "CREATED_DESC", "i.created_at DESC, i.id");

  // ==================================================================
  // 口径说明（随响应返回，界面上的表头与跨部门沟通用同一份定义）
  // ==================================================================

  static final String EFFECT_NOTE =
      "🔴 **「没录」与「是 0」不是一回事**：GMV / 互动率 / 退款率没录就是 null（不可比），"
          + "界面显示「未录入」，**不是 0.00** —— 0 会被读成「这个达人一单没带过货」。"
          + "两个 GMV 口径也分开：**档案 GMV** 是入库的档案值；**合作历史合计** 是本地按合作记录行"
          + "求和（合作口径）。**归因口径**（订单归因 + 佣金成本）归 14.5.2.1 带货归因，未交付。";

  static final String RATING_NOTE =
      "🔴 **评级是算出来的，不是手填的**：评分 = GMV 权重 × min(GMV/上限, 1) + 互动率权重 × "
          + "min(互动率/上限, 1) + 退款率权重 × (1 − min(退款率/上限, 1))，三个权重之和必须为 1"
          + "（DB CHECK 钉死）。**必需的效果数据缺任何一项 ⇒ 该达人暂无评分（null，不是 0 分）**。"
          + "评分口径（权重与上限）没配置 ⇒ 全库暂无评分。档位由分数落区间得到，"
          + "「按评级排」用的是档位排序位（不靠档位编码的字典序 —— 字典序会把 A 排在 S 前面）。";

  static final String SYNC_NOTE =
      "🔴 **平台达人数据同步未接入**：channel 的平台适配层（channel/vendor）目前**只有 OAuth "
          + "授权适配器**，**没有任何平台的达人数据接口**。所以达人数据目前只能**手动录入**；"
          + "同步入口会明确返回「未接入」并**不写任何数据** —— "
          + "本模块**不编造一个假的平台接口，也不返回硬编码的达人列表**。"
          + "平台适配器接上后，同一个端点自动放行，前端不用改。";

  static final String UPSTREAM_NOTE =
      "上游 = 2.1.1.1 platform（全局码表）+ 2.3.1.1 shop（达人挂店铺下，复合 FK 带 tenant_id "
          + "⇒ 跨租户挂载物理上不可能）+ 14.1.1.1 ad_campaign（合作历史的「活动」，同库同服务）；"
          + "平台达人数据适配器**未交付** ⇒ 同步维度 available=false。";

  static final String BOUNDARY_NOTE =
      "下游 = 14.5.1.2 建联与样品（**未交付**）与 15 客户与客服；"
          + "带货归因（14.5.2.1）**未交付** ⇒ GMV / 佣金只有本地口径，没有归因口径。"
          + "本模块**不实现**建联流程（14.5.1.2）与带货归因（14.5.2.1）。";

  private final DSLContext sql;
  private final InfluencerDataPort platformData;

  public InfluencerService(DSLContext sql, InfluencerDataPort platformData) {
    this.sql = sql;
    this.platformData = platformData;
  }

  // ==================================================================
  // 小工具（照抄 AdPromotionService 的同名写法）
  // ==================================================================

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private long count(String query, Object... binds) {
    Long v = one(Long.class, query, binds);
    return v == null ? 0L : v;
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String escapeLike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static Object[] concat(List<Object> binds, Object... tail) {
    Object[] all = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) {
      all[i] = binds.get(i);
    }
    System.arraycopy(tail, 0, all, binds.size(), tail.length);
    return all;
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 绑定，不来自任何客户端头）。 */
  private UUID currentTenant() {
    String raw = one(String.class, "select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /** 跨租户与不存在返回**逐字相同**的 404（照抄 10.1.1.2 / 10.1.2.1 同款口径）。 */
  private ResponseStatusException notFound(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在");
  }

  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static UUID parseId(String id) {
    try {
      return UUID.fromString(id.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 不是合法 UUID");
    }
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public InfluencerCapabilities capabilities() {
    return new InfluencerCapabilities(
        List.of(
            new InfluencerCapabilityDimension(
                "influencer_profile",
                "达人档案：类目 / 粉丝层级 / 粉丝数 / 互动率 / 带货 GMV / 退款率",
                true,
                "influencer（V288）",
                "",
                "没有档案就没有达人库；粉丝层级由 DB 触发器从粉丝数推导，写不错"),
            new InfluencerCapabilityDimension(
                "effect_data",
                "🔴 **达人效果数据（GMV / 退款率）入库**",
                true,
                "influencer.gmv / refund_rate（V288）",
                "",
                "蓝图加粗句：效果数据不入库就筛不出优质达人。没录是 null（不可比），不是 0"),
            new InfluencerCapabilityDimension(
                "tag_group",
                "标签分组：分组 → 标签 → 达人（多对多）",
                true,
                "influencer_tag_group / influencer_tag / influencer_tag_assignment（V288）",
                "",
                "没有标签就只剩「按类目筛」，运营攒不出「高退货达人」这种可复用的筛选口径"),
            new InfluencerCapabilityDimension(
                "rating",
                "评级：评分口径 + 档位，**由效果数据算出来**",
                true,
                "influencer_rating_rule / influencer_rating_tier（V288）",
                "",
                "评级是「优质」的判定口径；手填的评级只是主观标签，筛不出稳定结果"),
            new InfluencerCapabilityDimension(
                "blacklist",
                "黑名单：拉黑原因 / 生效时间 / 解除记录",
                true,
                "influencer_blacklist（V288）",
                "",
                "拉黑后必须能从正常检索里排除；解除要留痕，误拉黑才能复盘"),
            new InfluencerCapabilityDimension(
                "cooperation_history",
                "合作历史：达人 × 活动（14.1.1.1 广告活动）+ 效果数据",
                true,
                "influencer_cooperation（V288，复合 FK 挂 ad_campaign）",
                "",
                "没有合作历史就答不出「这个达人跟谁合作过、效果如何」"),
            new InfluencerCapabilityDimension(
                "platform_sync",
                "🔴 **平台达人数据同步（平台接口那一半）**",
                false,
                "—",
                "平台适配层（channel/vendor：只有 OAuth 适配器，没有达人数据适配器）",
                "达人数据目前只能**手动录入**。同步端口对所有平台返回「未接入」，"
                    + "同步入口**不写任何数据**；平台适配器接上后同一个端点自动放行"),
            new InfluencerCapabilityDimension(
                "attribution",
                "🔴 **带货归因（订单归因 + 佣金成本的归因口径）**",
                false,
                "—",
                "14.5.2.1 带货归因（未交付）",
                "GMV / 佣金只有本地口径（档案值 + 合作记录合计），"
                    + "**没有归因口径**；两个口径分开显示，不合并成一个数")),
        FOLLOWER_TIERS,
        BLACKLIST_REASON_CODES,
        COOPERATION_STATUSES,
        SYNC_SOURCES,
        EFFECT_NOTE,
        RATING_NOTE,
        SYNC_NOTE,
        UPSTREAM_NOTE,
        BOUNDARY_NOTE);
  }

  // ==================================================================
  // 选项
  // ==================================================================

  public InfluencerOptions options() {
    List<InfluencerPlatformOption> platforms =
        sql.fetch(
                "select distinct pf.id, pf.code, pf.name from shop s"
                    + " join platform pf on pf.id = s.platform_id"
                    + " where s.tenant_id = "
                    + TENANT
                    + " order by pf.code")
            .stream()
            .map(
                r ->
                    new InfluencerPlatformOption(
                        r.get("id", UUID.class),
                        r.get("code", String.class),
                        r.get("name", String.class)))
            .toList();

    List<String> platformCodes = platforms.stream().map(InfluencerPlatformOption::code).toList();

    List<String> shopCodes =
        sql.fetch("select distinct code from shop where tenant_id = " + TENANT + " order by code")
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();

    List<String> currencies =
        sql.fetch(
                "select distinct gmv_currency from influencer where tenant_id = "
                    + TENANT
                    + " and gmv_currency <> '' order by gmv_currency")
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();

    List<String> categories =
        sql.fetch(
                "select distinct category from influencer where tenant_id = "
                    + TENANT
                    + " order by category")
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();

    return new InfluencerOptions(
        followerTierOptions(),
        blacklistReasonOptions(),
        cooperationStatusOptions(),
        platforms,
        platformCodes,
        shopCodes,
        currencies,
        categories,
        EFFECT_NOTE,
        RATING_NOTE);
  }

  private static List<InfluencerOption> followerTierOptions() {
    return List.of(
        new InfluencerOption("NANO", "素人（1 万以下）"),
        new InfluencerOption("MICRO", "微型（1 万 ~ 10 万）"),
        new InfluencerOption("MID", "中型（10 万 ~ 50 万）"),
        new InfluencerOption("MACRO", "头部（50 万 ~ 100 万）"),
        new InfluencerOption("MEGA", "超头（100 万 ~ 500 万）"),
        new InfluencerOption("TOP", "顶级（500 万以上）"));
  }

  private static List<InfluencerOption> blacklistReasonOptions() {
    return List.of(
        new InfluencerOption("FRAUD", "欺诈（虚假数据 / 刷量）"),
        new InfluencerOption("INFRINGEMENT", "侵权（抄袭 / 冒用品牌）"),
        new InfluencerOption("POOR_QUALITY", "效果差（合作后无产出）"),
        new InfluencerOption("NON_COMPLIANCE", "违规（平台规则 / 广告法）"),
        new InfluencerOption("BRAND_SAFETY", "品牌安全（不当言论 / 争议）"),
        new InfluencerOption("DATA_QUALITY", "数据质量（档案信息不可信）"));
  }

  private static List<InfluencerOption> cooperationStatusOptions() {
    return List.of(
        new InfluencerOption("PLANNED", "筹备中"),
        new InfluencerOption("ACTIVE", "合作中"),
        new InfluencerOption("COMPLETED", "已完成"),
        new InfluencerOption("CANCELLED", "已取消"));
  }

  // ==================================================================
  // 平台同步能力
  // ==================================================================

  /** 逐平台说清达人数据同步是否已接入（**未接入就说未接入**，不假装已接入）。 */
  public InfluencerSyncStatus syncStatus() {
    List<Record> rows =
        sql.fetch(
            "select pf.code platform_code, pf.name platform_name,"
                + " (select count(*) from influencer i where i.tenant_id = "
                + TENANT
                + " and i.platform_id = pf.id) registered_count"
                + " from platform pf"
                + " where exists (select 1 from shop s where s.tenant_id = "
                + TENANT
                + " and s.platform_id = pf.id)"
                + " order by pf.code");

    List<InfluencerPlatformSyncAvailability> items = new ArrayList<>();
    for (Record r : rows) {
      String code = r.get("platform_code", String.class);
      InfluencerDataPort.Availability availability = platformData.availability(code);
      items.add(
          new InfluencerPlatformSyncAvailability(
              code,
              r.get("platform_name", String.class),
              availability.available(),
              availability.status(),
              availability.reason(),
              r.get("registered_count", Integer.class) == null
                  ? 0
                  : r.get("registered_count", Integer.class)));
    }
    return new InfluencerSyncStatus(items, SYNC_NOTE);
  }

  /**
   * 触发一次平台达人数据同步。
   *
   * <p>🔴 **当前所有平台的结论都是「未接入」**：端口返回 {@code available=false} 时
   * 本方法<b>不写任何数据</b>，并把端口给的原因原样返回（409）。
   * 平台适配器接上之后，这段代码会真的落库 —— 落库逻辑在服务层，不在端口里。
   */
  public InfluencerSyncResult sync(SyncInfluencersRequest request) {
    String platformCode = text(request.platformCode()).toUpperCase(Locale.ROOT);
    String shopCode = text(request.shopCode());

    InfluencerDataPort.Availability availability = platformData.availability(platformCode);
    if (!availability.available()) {
      throw conflict(availability.reason());
    }

    // 🔴 端口只取数，落库归服务层（写进 influencer 表并触发评级重算）。
    InfluencerDataPort.FetchResult fetched = platformData.fetch(platformCode, shopCode, "");
    if (!fetched.available()) {
      throw conflict(fetched.reason());
    }

    int synced = 0;
    for (InfluencerDataPort.RawProfile raw : fetched.profiles()) {
      upsertSyncedProfile(platformCode, shopCode, raw);
      synced++;
    }
    return new InfluencerSyncResult(true, "已同步 " + synced + " 条达人档案", synced, 0);
  }

  /** 把平台侧原始档案落成一条达人档案（同步路径专用；手动录入走 saveProfile）。 */
  private void upsertSyncedProfile(
      String platformCode, String shopCode, InfluencerDataPort.RawProfile raw) {
    UUID platformId =
        one(
            UUID.class,
            "select pf.id from platform pf join shop s on s.platform_id = pf.id"
                + " where s.tenant_id = "
                + TENANT
                + " and s.code = ? and pf.code = ?",
            shopCode,
            platformCode);
    if (platformId == null) {
      throw conflict("店铺 " + shopCode + " 不属于平台 " + platformCode);
    }
    UUID id =
        one(
            UUID.class,
            "select id from influencer where tenant_id = "
                + TENANT
                + " and platform_id = ? and shop_code = ? and external_influencer_id = ?",
            platformId,
            shopCode,
            text(raw.externalInfluencerId()));
    if (id != null) {
      sql.execute(
          "update influencer set display_name = ?, handle = ?, category = ?, follower_count = ?,"
              + " engagement_rate = ?, gmv = ?, gmv_currency = ?, refund_rate = ?,"
              + " sync_source = 'PLATFORM_API', platform_sync_status = 'SYNCED',"
              + " updated_at = now(), version = version + 1"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          text(raw.displayName()),
          text(raw.handle()),
          text(raw.category()),
          raw.followerCount(),
          raw.engagementRate(),
          raw.gmv(),
          text(raw.gmvCurrency()),
          raw.refundRate(),
          currentTenant(),
          id);
      applyRating(id);
      return;
    }
    UUID newId = Ids.next();
    sql.execute(
        "insert into influencer (tenant_id,id,platform_id,shop_code,external_influencer_id,"
            + " display_name,handle,category,follower_count,engagement_rate,gmv,gmv_currency,"
            + " refund_rate,sync_source,platform_sync_status,status,note,version,"
            + " created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,'PLATFORM_API','SYNCED','ACTIVE','',0,now(),now())",
        currentTenant(),
        newId,
        platformId,
        shopCode,
        text(raw.externalInfluencerId()),
        text(raw.displayName()),
        text(raw.handle()),
        text(raw.category()),
        raw.followerCount(),
        raw.engagementRate(),
        raw.gmv(),
        text(raw.gmvCurrency()),
        raw.refundRate());
    applyRating(newId);
  }

  // ==================================================================
  // 达人列表：真分页 + 真筛选 + 真排序
  // ==================================================================

  public InfluencerPage profiles(
      String category,
      String followerTier,
      UUID tagId,
      String tagCode,
      UUID tagGroupId,
      String tagGroupCode,
      String ratingTierCode,
      BigDecimal minGmv,
      BigDecimal maxGmv,
      BigDecimal minEngagement,
      BigDecimal maxEngagement,
      UUID platformId,
      String shopCode,
      String keyword,
      boolean includeBlacklisted,
      String sort,
      int page,
      int size) {
    StringBuilder where = new StringBuilder(" where i.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();

    String cat = blankToNull(category);
    if (cat != null) {
      where.append(" and i.category = ?");
      binds.add(cat);
    }
    String tier = blankToNull(followerTier);
    if (tier != null) {
      where.append(" and i.follower_tier = ?");
      binds.add(tier.toUpperCase(Locale.ROOT));
    }
    if (tagId != null || tagCode != null || tagGroupId != null || tagGroupCode != null) {
      where.append(
          " and exists (select 1 from influencer_tag_assignment a"
              + " join influencer_tag t on t.tenant_id = a.tenant_id and t.id = a.tag_id"
              + " join influencer_tag_group g on g.tenant_id = t.tenant_id and g.id = t.group_id"
              + " where a.tenant_id = i.tenant_id and a.influencer_id = i.id");
      if (tagId != null) {
        where.append(" and a.tag_id = ?");
        binds.add(tagId);
      }
      if (tagCode != null) {
        where.append(" and t.tag_code = ?");
        binds.add(tagCode);
      }
      if (tagGroupId != null) {
        where.append(" and t.group_id = ?");
        binds.add(tagGroupId);
      }
      if (tagGroupCode != null) {
        where.append(" and g.group_code = ?");
        binds.add(tagGroupCode);
      }
      where.append(")");
    }
    String tierCode = blankToNull(ratingTierCode);
    if (tierCode != null) {
      where.append(
          " and exists (select 1 from influencer_rating_tier rt where rt.tenant_id = i.tenant_id"
              + " and rt.id = i.rating_tier_id and rt.tier_code = ?)");
      binds.add(tierCode.toUpperCase(Locale.ROOT));
    }
    if (minGmv != null) {
      where.append(" and i.gmv >= ?");
      binds.add(minGmv);
    }
    if (maxGmv != null) {
      where.append(" and i.gmv <= ?");
      binds.add(maxGmv);
    }
    if (minEngagement != null) {
      where.append(" and i.engagement_rate >= ?");
      binds.add(minEngagement);
    }
    if (maxEngagement != null) {
      where.append(" and i.engagement_rate <= ?");
      binds.add(maxEngagement);
    }
    if (platformId != null) {
      where.append(" and i.platform_id = ?");
      binds.add(platformId);
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and i.shop_code = ?");
      binds.add(shop);
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(
          " and (i.display_name ilike ? or i.handle ilike ? or i.external_influencer_id ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    // 🔴 拉黑后**从正常检索里排除**（黑名单是独立事实，不是把达人从库里删掉）。
    if (!includeBlacklisted) {
      where.append(
          " and not exists (select 1 from influencer_blacklist b"
              + " where b.tenant_id = i.tenant_id and b.influencer_id = i.id and b.status = 'ACTIVE')");
    }

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    String order = SORTS.getOrDefault(text(sort).toUpperCase(Locale.ROOT), SORTS.get("GMV_DESC"));

    long total = count("select count(*) from influencer i" + where, binds.toArray());

    List<InfluencerRow> items =
        sql.fetch(
                influencerSelect() + where + " order by " + order + " limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::influencerRow)
            .toList();

    return new InfluencerPage(items, total, safePage, safeSize);
  }

  /** 达人档案的查询片段（列表与详情共用，避免两处口径漂移）。 */
  private String influencerSelect() {
    return "select i.id, i.platform_id,"
        + " (select pf.code from platform pf where pf.id = i.platform_id) platform_code,"
        + " i.shop_code, i.external_influencer_id, i.display_name, i.handle, i.category,"
        + " i.follower_count, i.follower_tier, i.engagement_rate, i.gmv, i.gmv_currency,"
        + " i.refund_rate, i.rating_score, i.rating_tier_id,"
        + " rt.tier_code rating_tier_code, rt.tier_name rating_tier_name,"
        + " rt.sort_order rating_sort_order,"
        + " (select count(*) from influencer_cooperation c where c.tenant_id = i.tenant_id"
        + "   and c.influencer_id = i.id) cooperation_count,"
        + " (select sum(c.gmv) from influencer_cooperation c where c.tenant_id = i.tenant_id"
        + "   and c.influencer_id = i.id) gmv_from_cooperations,"
        + " i.sync_source, i.platform_sync_status, i.status,"
        + " b.id blacklist_id, b.reason_code blacklist_reason_code,"
        + " b.effective_at blacklist_effective_at,"
        + " (select string_agg(t.tag_code, ',' order by t.tag_code)"
        + "   from influencer_tag_assignment a join influencer_tag t"
        + "     on t.tenant_id = a.tenant_id and t.id = a.tag_id"
        + "  where a.tenant_id = i.tenant_id and a.influencer_id = i.id) tag_codes,"
        + " i.note, i.version, i.created_at, i.updated_at"
        + " from influencer i"
        + " left join influencer_rating_tier rt on rt.tenant_id = i.tenant_id and rt.id = i.rating_tier_id"
        + " left join lateral (select bl.id, bl.reason_code, bl.effective_at"
        + "   from influencer_blacklist bl where bl.tenant_id = i.tenant_id"
        + "   and bl.influencer_id = i.id and bl.status = 'ACTIVE' limit 1) b on true";
  }

  private InfluencerRow influencerRow(Record r) {
    UUID blacklistId = r.get("blacklist_id", UUID.class);
    return new InfluencerRow(
        r.get("id", UUID.class),
        r.get("platform_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("external_influencer_id", String.class),
        r.get("display_name", String.class),
        r.get("handle", String.class),
        r.get("category", String.class),
        r.get("follower_count", Long.class) == null ? 0L : r.get("follower_count", Long.class),
        r.get("follower_tier", String.class),
        r.get("engagement_rate", BigDecimal.class),
        r.get("gmv", BigDecimal.class),
        r.get("gmv_currency", String.class),
        r.get("refund_rate", BigDecimal.class),
        r.get("rating_score", BigDecimal.class),
        r.get("rating_tier_id", UUID.class),
        r.get("rating_tier_code", String.class),
        r.get("rating_tier_name", String.class),
        r.get("rating_sort_order", Integer.class),
        r.get("cooperation_count", Integer.class) == null
            ? 0
            : r.get("cooperation_count", Integer.class),
        r.get("gmv_from_cooperations", BigDecimal.class),
        r.get("sync_source", String.class),
        r.get("platform_sync_status", String.class),
        r.get("status", String.class),
        blacklistId != null,
        blacklistId,
        r.get("blacklist_reason_code", String.class),
        r.get("blacklist_effective_at", OffsetDateTime.class),
        r.get("tag_codes", String.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 达人详情
  // ==================================================================

  public InfluencerDetail profile(String id) {
    UUID influencerId = parseId(id);
    Record row =
        sql.fetchOne(
            influencerSelect() + " where i.tenant_id = " + TENANT + " and i.id = ?", influencerId);
    if (row == null) {
      throw notFound("达人");
    }
    return new InfluencerDetail(
        influencerRow(row),
        tagsOf(influencerId),
        cooperationsOf(influencerId),
        activeBlacklistOf(influencerId),
        EFFECT_NOTE,
        RATING_NOTE,
        SYNC_NOTE,
        "合作历史挂 14.1.1.1 广告活动（复合 FK）；带货归因口径归 14.5.2.1，未交付。");
  }

  // ==================================================================
  // 达人档案：增 / 改 / 删
  // ==================================================================

  /** 手动录入一个达人（蓝图的「手动录入」—— 平台接口那一半未交付，这是已交付的那一半）。 */
  public InfluencerDetail saveProfile(SaveInfluencerRequest request) {
    ValidatedProfile profile = validateProfile(request);
    UUID id = Ids.next();
    sql.execute(
        "insert into influencer (tenant_id,id,platform_id,shop_code,external_influencer_id,"
            + " display_name,handle,category,follower_count,engagement_rate,gmv,gmv_currency,"
            + " refund_rate,sync_source,platform_sync_status,status,note,version,"
            + " created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,'MANUAL','LOCAL_ONLY',?,?,0,now(),now())",
        currentTenant(),
        id,
        request.platformId(),
        profile.shopCode(),
        profile.externalInfluencerId(),
        profile.displayName(),
        profile.handle(),
        profile.category(),
        profile.followerCount(),
        profile.engagementRate(),
        profile.gmv(),
        profile.gmvCurrency(),
        profile.refundRate(),
        profile.status(),
        profile.note());
    applyRating(id);
    return profile(id.toString());
  }

  /**
   * 改达人档案。
   *
   * <p>🔴 改完**立刻重算评级** —— 效果数据变了，评分与档位必须跟着变，
   * 否则界面上的评级会停留在旧数据上（那是假事实）。
   */
  public InfluencerDetail updateProfile(String id, SaveInfluencerRequest request) {
    UUID influencerId = parseId(id);
    if (profileRecord(influencerId) == null) {
      throw notFound("达人");
    }
    ValidatedProfile profile = validateProfile(request);
    sql.execute(
        "update influencer set platform_id = ?, shop_code = ?, external_influencer_id = ?,"
            + " display_name = ?, handle = ?, category = ?, follower_count = ?,"
            + " engagement_rate = ?, gmv = ?, gmv_currency = ?, refund_rate = ?, status = ?,"
            + " note = ?, updated_at = now(), version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        request.platformId(),
        profile.shopCode(),
        profile.externalInfluencerId(),
        profile.displayName(),
        profile.handle(),
        profile.category(),
        profile.followerCount(),
        profile.engagementRate(),
        profile.gmv(),
        profile.gmvCurrency(),
        profile.refundRate(),
        profile.status(),
        profile.note(),
        currentTenant(),
        influencerId);
    applyRating(influencerId);
    return profile(influencerId.toString());
  }

  /**
   * 删除一个达人档案。
   *
   * <p>🔴 **有黑名单记录或合作历史的达人不许物理删除** —— 那两条是**举证凭据**
   * （「我们拉黑过这个达人」「这个达人跟我们合作过、带了 X 万 GMV」），
   * 悄悄删掉等于让这些事实无声消失。要停用请改 {@code status=INACTIVE}，
   * 要拉黑请走黑名单入口。
   */
  public void deleteProfile(String id) {
    UUID influencerId = parseId(id);
    if (profileRecord(influencerId) == null) {
      throw notFound("达人");
    }
    long blacklistCount =
        count(
            "select count(*) from influencer_blacklist where tenant_id = " + TENANT
                + " and influencer_id = ?",
            influencerId);
    if (blacklistCount > 0) {
      throw conflict(
          "该达人有黑名单记录（举证凭据），不能物理删除。请改为停用（status=INACTIVE）或先处理黑名单记录。");
    }
    long cooperationCount =
        count(
            "select count(*) from influencer_cooperation where tenant_id = " + TENANT
                + " and influencer_id = ?",
            influencerId);
    if (cooperationCount > 0) {
      throw conflict(
          "该达人有合作历史（举证凭据），不能物理删除。请改为停用（status=INACTIVE）。");
    }
    sql.execute(
        "delete from influencer where tenant_id = ?::uuid and id = ?::uuid",
        currentTenant(),
        influencerId);
  }

  private Record profileRecord(UUID influencerId) {
    return sql.fetchOne(
        "select id, display_name, shop_code from influencer where tenant_id = "
            + TENANT
            + " and id = ?",
        influencerId);
  }

  /** 校验后的达人档案（各字段都已被规范化）。 */
  private record ValidatedProfile(
      String shopCode,
      String externalInfluencerId,
      String displayName,
      String handle,
      String category,
      long followerCount,
      BigDecimal engagementRate,
      BigDecimal gmv,
      String gmvCurrency,
      BigDecimal refundRate,
      String status,
      String note) {}

  private ValidatedProfile validateProfile(SaveInfluencerRequest request) {
    String shopCode = text(request.shopCode());
    if (shopCode.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "店铺编码必填");
    }
    String displayName = text(request.displayName());
    if (displayName.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "达人名称必填");
    }
    String category = text(request.category());
    if (category.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "类目必填");
    }
    long followerCount = request.followerCount() == null ? 0L : request.followerCount();
    if (followerCount < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "粉丝数不能是负数");
    }
    if (request.engagementRate() != null
        && (request.engagementRate().signum() < 0
            || request.engagementRate().compareTo(BigDecimal.ONE) > 0)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "互动率必须在 0 ~ 1 之间");
    }
    if (request.refundRate() != null
        && (request.refundRate().signum() < 0
            || request.refundRate().compareTo(BigDecimal.ONE) > 0)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退款率必须在 0 ~ 1 之间");
    }
    if (request.gmv() != null && request.gmv().signum() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "带货 GMV 不能是负数");
    }
    String currency = text(request.gmvCurrency()).toUpperCase(Locale.ROOT);
    if (!currency.isEmpty() && currency.length() != 3) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是 3 位字母代码");
    }
    String status = blankToNull(request.status());
    if (status != null && !INFLUENCER_STATUSES.contains(status.toUpperCase(Locale.ROOT))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "状态只能是 ACTIVE 或 INACTIVE");
    }
    return new ValidatedProfile(
        shopCode,
        text(request.externalInfluencerId()),
        displayName,
        text(request.handle()),
        category,
        followerCount,
        request.engagementRate(),
        request.gmv(),
        currency,
        request.refundRate(),
        status == null ? "ACTIVE" : status.toUpperCase(Locale.ROOT),
        text(request.note()));
  }

  // ==================================================================
  // 打标
  // ==================================================================

  /**
   * 批量打标（打标与批量打标走同一个入口：给 1 个标签是打标，给 N 个是批量打标）。
   *
   * <p>🔴 重复打标是**幂等**的（不是报错）：同一个达人同一个标签挂两次没有意义，
   * 而「批量打标」天然会与已有标签重叠。
   */
  public InfluencerDetail assignTags(String id, TagInfluencersRequest request) {
    UUID influencerId = parseId(id);
    if (profileRecord(influencerId) == null) {
      throw notFound("达人");
    }
    List<UUID> tagIds = new ArrayList<>();
    if (request.tagIds() != null) {
      tagIds.addAll(request.tagIds());
    }
    if (request.tagCodes() != null) {
      for (String code : request.tagCodes()) {
        String trimmed = text(code);
        if (trimmed.isEmpty()) {
          continue;
        }
        UUID found =
            one(
                UUID.class,
                "select id from influencer_tag where tenant_id = " + TENANT + " and tag_code = ?",
                trimmed);
        if (found == null) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "标签不存在：" + trimmed);
        }
        tagIds.add(found);
      }
    }
    if (tagIds.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少要给一个标签");
    }
    for (UUID tagId : tagIds) {
      if (one(UUID.class, "select id from influencer_tag where tenant_id = " + TENANT + " and id = ?", tagId)
          == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标签不存在：" + tagId);
      }
      sql.execute(
          "insert into influencer_tag_assignment (tenant_id,id,influencer_id,tag_id,assigned_at,note)"
              + " values (?,?,?,?,now(),?)"
              + " on conflict (tenant_id,influencer_id,tag_id) do nothing",
          currentTenant(),
          Ids.next(),
          influencerId,
          tagId,
          text(request.note()));
    }
    return profile(influencerId.toString());
  }

  /** 移除一个标签（打错了要能撤 —— 打标不是不可逆的事实）。 */
  public InfluencerDetail removeTag(String id, String tagId) {
    UUID influencerId = parseId(id);
    UUID tag = parseId(tagId);
    if (profileRecord(influencerId) == null) {
      throw notFound("达人");
    }
    sql.execute(
        "delete from influencer_tag_assignment where tenant_id = ?::uuid and influencer_id = ?::uuid"
            + " and tag_id = ?::uuid",
        currentTenant(),
        influencerId,
        tag);
    return profile(influencerId.toString());
  }

  /** 这个达人挂了哪些标签。 */
  public List<InfluencerTagRow> tagsOf(UUID influencerId) {
    return sql.fetch(
            "select t.id, t.group_id, g.group_code, g.group_name, t.tag_code, t.tag_name,"
                + " t.description, t.status, t.note, t.version, t.created_at, t.updated_at,"
                + " (select count(*) from influencer_tag_assignment a where a.tenant_id = t.tenant_id"
                + "   and a.tag_id = t.id) influencer_count"
                + " from influencer_tag_assignment a"
                + " join influencer_tag t on t.tenant_id = a.tenant_id and t.id = a.tag_id"
                + " join influencer_tag_group g on g.tenant_id = t.tenant_id and g.id = t.group_id"
                + " where a.tenant_id = " + TENANT + " and a.influencer_id = ?"
                + " order by t.tag_code",
            influencerId)
        .stream()
        .map(this::tagRow)
        .toList();
  }

  // ==================================================================
  // 标签分组与标签（租户级配置）
  // ==================================================================

  public InfluencerTagGroupPage tagGroups(String status, String keyword, int page, int size) {
    StringBuilder where = new StringBuilder(" where g.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and g.status = ?");
      binds.add(state.toUpperCase(Locale.ROOT));
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (g.group_code ilike ? or g.group_name ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
    }
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    long total = count("select count(*) from influencer_tag_group g" + where, binds.toArray());
    List<InfluencerTagGroupRow> items =
        sql.fetch(
                "select g.id, g.group_code, g.group_name, g.description, g.status, g.note,"
                    + " g.version, g.created_at, g.updated_at,"
                    + " (select count(*) from influencer_tag t where t.tenant_id = g.tenant_id"
                    + "   and t.group_id = g.id) tag_count"
                    + " from influencer_tag_group g"
                    + where
                    + " order by g.group_code limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(
                r ->
                    new InfluencerTagGroupRow(
                        r.get("id", UUID.class),
                        r.get("group_code", String.class),
                        r.get("group_name", String.class),
                        r.get("description", String.class),
                        r.get("status", String.class),
                        r.get("tag_count", Integer.class) == null ? 0 : r.get("tag_count", Integer.class),
                        r.get("note", String.class),
                        r.get("version", Long.class),
                        r.get("created_at", OffsetDateTime.class),
                        r.get("updated_at", OffsetDateTime.class)))
            .toList();
    return new InfluencerTagGroupPage(items, total, safePage, safeSize);
  }

  public InfluencerTagGroupRow saveTagGroup(SaveInfluencerTagGroupRequest request) {
    String code = text(request.groupCode());
    String name = text(request.groupName());
    if (code.isEmpty() || name.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分组编码与分组名称必填");
    }
    String status = normalizeConfigStatus(request.status());
    UUID id = Ids.next();
    sql.execute(
        "insert into influencer_tag_group (tenant_id,id,group_code,group_name,description,status,"
            + " note,version,created_at,updated_at) values (?,?,?,?,?,?,?,0,now(),now())",
        currentTenant(),
        id,
        code,
        name,
        text(request.description()),
        status,
        text(request.note()));
    return tagGroupRow(id);
  }

  public InfluencerTagGroupRow updateTagGroup(String id, SaveInfluencerTagGroupRequest request) {
    UUID groupId = parseId(id);
    if (tagGroupRecord(groupId) == null) {
      throw notFound("标签分组");
    }
    String code = text(request.groupCode());
    String name = text(request.groupName());
    if (code.isEmpty() || name.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分组编码与分组名称必填");
    }
    sql.execute(
        "update influencer_tag_group set group_code = ?, group_name = ?, description = ?,"
            + " status = ?, note = ?, updated_at = now(), version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        code,
        name,
        text(request.description()),
        normalizeConfigStatus(request.status()),
        text(request.note()),
        currentTenant(),
        groupId);
    return tagGroupRow(groupId);
  }

  /** 删分组：分组下还有标签时**拒绝**（标签被连带删掉会悄悄改变筛选结果）。 */
  public void deleteTagGroup(String id) {
    UUID groupId = parseId(id);
    if (tagGroupRecord(groupId) == null) {
      throw notFound("标签分组");
    }
    long tagCount =
        count(
            "select count(*) from influencer_tag where tenant_id = " + TENANT + " and group_id = ?",
            groupId);
    if (tagCount > 0) {
      throw conflict("该分组下还有 " + tagCount + " 个标签，先处理标签再删分组。");
    }
    sql.execute(
        "delete from influencer_tag_group where tenant_id = ?::uuid and id = ?::uuid",
        currentTenant(),
        groupId);
  }

  private Record tagGroupRecord(UUID groupId) {
    return sql.fetchOne(
        "select id, group_code from influencer_tag_group where tenant_id = " + TENANT + " and id = ?",
        groupId);
  }

  private InfluencerTagGroupRow tagGroupRow(UUID groupId) {
    Record r =
        sql.fetchOne(
            "select g.id, g.group_code, g.group_name, g.description, g.status, g.note,"
                + " g.version, g.created_at, g.updated_at,"
                + " (select count(*) from influencer_tag t where t.tenant_id = g.tenant_id"
                + "   and t.group_id = g.id) tag_count"
                + " from influencer_tag_group g where g.tenant_id = " + TENANT + " and g.id = ?",
            groupId);
    if (r == null) {
      throw notFound("标签分组");
    }
    return new InfluencerTagGroupRow(
        r.get("id", UUID.class),
        r.get("group_code", String.class),
        r.get("group_name", String.class),
        r.get("description", String.class),
        r.get("status", String.class),
        r.get("tag_count", Integer.class) == null ? 0 : r.get("tag_count", Integer.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  public InfluencerTagPage tags(String groupCode, String status, String keyword, int page, int size) {
    StringBuilder where = new StringBuilder(" where t.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    String group = blankToNull(groupCode);
    if (group != null) {
      where.append(" and g.group_code = ?");
      binds.add(group);
    }
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and t.status = ?");
      binds.add(state.toUpperCase(Locale.ROOT));
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (t.tag_code ilike ? or t.tag_name ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
    }
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    long total =
        count(
            "select count(*) from influencer_tag t join influencer_tag_group g"
                + " on g.tenant_id = t.tenant_id and g.id = t.group_id"
                + where,
            binds.toArray());
    List<InfluencerTagRow> items =
        sql.fetch(
                "select t.id, t.group_id, g.group_code, g.group_name, t.tag_code, t.tag_name,"
                    + " t.description, t.status, t.note, t.version, t.created_at, t.updated_at,"
                    + " (select count(*) from influencer_tag_assignment a where a.tenant_id = t.tenant_id"
                    + "   and a.tag_id = t.id) influencer_count"
                    + " from influencer_tag t join influencer_tag_group g"
                    + " on g.tenant_id = t.tenant_id and g.id = t.group_id"
                    + where
                    + " order by t.tag_code limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::tagRow)
            .toList();
    return new InfluencerTagPage(items, total, safePage, safeSize);
  }

  public InfluencerTagRow saveTag(SaveInfluencerTagRequest request) {
    String code = text(request.tagCode());
    String name = text(request.tagName());
    if (code.isEmpty() || name.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标签编码与标签名称必填");
    }
    if (tagGroupRecord(request.groupId()) == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标签分组不存在");
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into influencer_tag (tenant_id,id,group_id,tag_code,tag_name,description,status,"
            + " note,version,created_at,updated_at) values (?,?,?,?,?,?,?,?,0,now(),now())",
        currentTenant(),
        id,
        request.groupId(),
        code,
        name,
        text(request.description()),
        normalizeConfigStatus(request.status()),
        text(request.note()));
    return tagRow(id);
  }

  public InfluencerTagRow updateTag(String id, SaveInfluencerTagRequest request) {
    UUID tagId = parseId(id);
    if (tagRecord(tagId) == null) {
      throw notFound("标签");
    }
    String code = text(request.tagCode());
    String name = text(request.tagName());
    if (code.isEmpty() || name.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标签编码与标签名称必填");
    }
    if (tagGroupRecord(request.groupId()) == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标签分组不存在");
    }
    sql.execute(
        "update influencer_tag set group_id = ?, tag_code = ?, tag_name = ?, description = ?,"
            + " status = ?, note = ?, updated_at = now(), version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        request.groupId(),
        code,
        name,
        text(request.description()),
        normalizeConfigStatus(request.status()),
        text(request.note()),
        currentTenant(),
        tagId);
    return tagRow(tagId);
  }

  /** 删标签：标签正被达人引用时**拒绝**（悄悄解绑会改变筛选结果而不留痕）。 */
  public void deleteTag(String id) {
    UUID tagId = parseId(id);
    if (tagRecord(tagId) == null) {
      throw notFound("标签");
    }
    long used =
        count(
            "select count(*) from influencer_tag_assignment where tenant_id = " + TENANT
                + " and tag_id = ?",
            tagId);
    if (used > 0) {
      throw conflict("该标签还挂在 " + used + " 个达人身上，先解除引用再删标签。");
    }
    sql.execute(
        "delete from influencer_tag where tenant_id = ?::uuid and id = ?::uuid",
        currentTenant(),
        tagId);
  }

  private Record tagRecord(UUID tagId) {
    return sql.fetchOne(
        "select id, tag_code from influencer_tag where tenant_id = " + TENANT + " and id = ?", tagId);
  }

  private InfluencerTagRow tagRow(Record r) {
    return new InfluencerTagRow(
        r.get("id", UUID.class),
        r.get("group_id", UUID.class),
        r.get("group_code", String.class),
        r.get("group_name", String.class),
        r.get("tag_code", String.class),
        r.get("tag_name", String.class),
        r.get("description", String.class),
        r.get("status", String.class),
        r.get("influencer_count", Integer.class) == null
            ? 0
            : r.get("influencer_count", Integer.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private InfluencerTagRow tagRow(UUID tagId) {
    Record r =
        sql.fetchOne(
            "select t.id, t.group_id, g.group_code, g.group_name, t.tag_code, t.tag_name,"
                + " t.description, t.status, t.note, t.version, t.created_at, t.updated_at,"
                + " (select count(*) from influencer_tag_assignment a where a.tenant_id = t.tenant_id"
                + "   and a.tag_id = t.id) influencer_count"
                + " from influencer_tag t join influencer_tag_group g"
                + " on g.tenant_id = t.tenant_id and g.id = t.group_id"
                + " where t.tenant_id = " + TENANT + " and t.id = ?",
            tagId);
    if (r == null) {
      throw notFound("标签");
    }
    return tagRow(r);
  }

  // ==================================================================
  // 评级：评分口径 + 档位
  // ==================================================================

  public InfluencerRatingTierPage ratingTiers(String status, int page, int size) {
    StringBuilder where = new StringBuilder(" where tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and status = ?");
      binds.add(state.toUpperCase(Locale.ROOT));
    }
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    long total = count("select count(*) from influencer_rating_tier" + where, binds.toArray());
    List<InfluencerRatingTierRow> items =
        sql.fetch(
                "select id, tier_code, tier_name, min_score, max_score, sort_order, status, note,"
                    + " version, created_at, updated_at from influencer_rating_tier"
                    + where
                    + " order by sort_order desc, tier_code limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::ratingTierRow)
            .toList();
    return new InfluencerRatingTierPage(items, total, safePage, safeSize);
  }

  public InfluencerRatingTierRow saveRatingTier(SaveInfluencerRatingTierRequest request) {
    String code = text(request.tierCode()).toUpperCase(Locale.ROOT);
    String name = text(request.tierName());
    if (code.isEmpty() || name.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "档位编码与档位名称必填");
    }
    BigDecimal min = request.minScore();
    BigDecimal max = request.maxScore();
    if (min == null || max == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "档位的分数下限与上限必填");
    }
    if (max.compareTo(min) <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "档位上限必须严格大于下限");
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into influencer_rating_tier (tenant_id,id,tier_code,tier_name,min_score,max_score,"
            + " sort_order,status,note,version,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,0,now(),now())",
        currentTenant(),
        id,
        code,
        name,
        min,
        max,
        request.sortOrder() == null ? 0 : request.sortOrder(),
        normalizeConfigStatus(request.status()),
        text(request.note()));
    return ratingTierRow(id);
  }

  public InfluencerRatingTierRow updateRatingTier(
      String id, SaveInfluencerRatingTierRequest request) {
    UUID tierId = parseId(id);
    if (ratingTierRecord(tierId) == null) {
      throw notFound("评级档位");
    }
    String code = text(request.tierCode()).toUpperCase(Locale.ROOT);
    String name = text(request.tierName());
    BigDecimal min = request.minScore();
    BigDecimal max = request.maxScore();
    if (code.isEmpty() || name.isEmpty() || min == null || max == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "档位编码、名称与分数区间必填");
    }
    if (max.compareTo(min) <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "档位上限必须严格大于下限");
    }
    sql.execute(
        "update influencer_rating_tier set tier_code = ?, tier_name = ?, min_score = ?,"
            + " max_score = ?, sort_order = ?, status = ?, note = ?, updated_at = now(),"
            + " version = version + 1 where tenant_id = ?::uuid and id = ?::uuid",
        code,
        name,
        min,
        max,
        request.sortOrder() == null ? 0 : request.sortOrder(),
        normalizeConfigStatus(request.status()),
        text(request.note()),
        currentTenant(),
        tierId);
    // 🔴 档位区间改了 ⇒ 已有达人的档位归属可能变 ⇒ 全库重算。
    recomputeAllRatings();
    return ratingTierRow(tierId);
  }

  /** 删档位：还有达人挂在这一档时**拒绝**（档位是评级的落点，删了评级就悬空）。 */
  public void deleteRatingTier(String id) {
    UUID tierId = parseId(id);
    if (ratingTierRecord(tierId) == null) {
      throw notFound("评级档位");
    }
    long used =
        count(
            "select count(*) from influencer where tenant_id = " + TENANT + " and rating_tier_id = ?",
            tierId);
    if (used > 0) {
      throw conflict("还有 " + used + " 个达人挂在这一档上，先改档位区间或把它们的评分重算掉。");
    }
    sql.execute(
        "delete from influencer_rating_tier where tenant_id = ?::uuid and id = ?::uuid",
        currentTenant(),
        tierId);
  }

  private Record ratingTierRecord(UUID tierId) {
    return sql.fetchOne(
        "select id, tier_code from influencer_rating_tier where tenant_id = " + TENANT + " and id = ?",
        tierId);
  }

  private InfluencerRatingTierRow ratingTierRow(Record r) {
    return new InfluencerRatingTierRow(
        r.get("id", UUID.class),
        r.get("tier_code", String.class),
        r.get("tier_name", String.class),
        r.get("min_score", BigDecimal.class),
        r.get("max_score", BigDecimal.class),
        r.get("sort_order", Integer.class) == null ? 0 : r.get("sort_order", Integer.class),
        r.get("status", String.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private InfluencerRatingTierRow ratingTierRow(UUID tierId) {
    Record r =
        sql.fetchOne(
            "select id, tier_code, tier_name, min_score, max_score, sort_order, status, note,"
                + " version, created_at, updated_at from influencer_rating_tier"
                + " where tenant_id = " + TENANT + " and id = ?",
            tierId);
    if (r == null) {
      throw notFound("评级档位");
    }
    return ratingTierRow(r);
  }

  /** 生效的评分口径（没有就返回 null —— 「没配口径」不是「口径是 0」）。 */
  public InfluencerRatingRuleRow ratingRule() {
    Record r =
        sql.fetchOne(
            "select id, rule_code, weight_gmv, weight_engagement, weight_refund, gmv_cap,"
                + " engagement_cap, refund_cap, status, note, version, created_at, updated_at"
                + " from influencer_rating_rule where tenant_id = " + TENANT + " and status = 'ACTIVE'");
    if (r == null) {
      return null;
    }
    return new InfluencerRatingRuleRow(
        r.get("id", UUID.class),
        r.get("rule_code", String.class),
        r.get("weight_gmv", BigDecimal.class),
        r.get("weight_engagement", BigDecimal.class),
        r.get("weight_refund", BigDecimal.class),
        r.get("gmv_cap", BigDecimal.class),
        r.get("engagement_cap", BigDecimal.class),
        r.get("refund_cap", BigDecimal.class),
        r.get("status", String.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /**
   * 配置评分口径。
   *
   * <p>🔴 一个租户**同一时刻只能有一套生效口径**：保存新口径时旧的自动停用。
   * 🔴 口径改了 ⇒ **全库重算评级** —— 否则已有达人挂着按旧口径算出来的分数，
   * 那会让「同一个达人在两页上是两个分」。
   */
  public InfluencerRatingRuleRow saveRatingRule(SaveInfluencerRatingRuleRequest request) {
    BigDecimal wGmv = request.weightGmv();
    BigDecimal wEngagement = request.weightEngagement();
    BigDecimal wRefund = request.weightRefund();
    if (wGmv == null || wEngagement == null || wRefund == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "三个权重必填");
    }
    BigDecimal sum = wGmv.add(wEngagement).add(wRefund);
    if (sum.compareTo(BigDecimal.ONE) != 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "三个权重之和必须等于 1（当前是 " + sum.toPlainString() + "）");
    }
    if (request.gmvCap() == null
        || request.engagementCap() == null
        || request.refundCap() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "三个归一化上限必填");
    }
    if (request.gmvCap().signum() <= 0
        || request.engagementCap().signum() <= 0
        || request.refundCap().signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "归一化上限必须大于 0");
    }
    String status = normalizeConfigStatus(request.status());
    String code = text(request.ruleCode()).isEmpty() ? "DEFAULT" : text(request.ruleCode());

    // 🔴 旧的生效口径先停用（partial unique index：同一租户只能有一套生效口径）。
    sql.execute(
        "update influencer_rating_rule set status = 'PAUSED', updated_at = now(),"
            + " version = version + 1 where tenant_id = ?::uuid and status = 'ACTIVE'",
        currentTenant());

    UUID id = Ids.next();
    sql.execute(
        "insert into influencer_rating_rule (tenant_id,id,rule_code,weight_gmv,weight_engagement,"
            + " weight_refund,gmv_cap,engagement_cap,refund_cap,status,note,version,"
            + " created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,0,now(),now())",
        currentTenant(),
        id,
        code,
        wGmv,
        wEngagement,
        wRefund,
        request.gmvCap(),
        request.engagementCap(),
        request.refundCap(),
        status,
        text(request.note()));

    if ("ACTIVE".equals(status)) {
      recomputeAllRatings();
    }
    return ratingRule();
  }

  /** 重算一个达人的评级。 */
  public InfluencerDetail recomputeRating(String id) {
    UUID influencerId = parseId(id);
    if (profileRecord(influencerId) == null) {
      throw notFound("达人");
    }
    applyRating(influencerId);
    return profile(influencerId.toString());
  }

  /**
   * 按生效的评分口径重算一个达人的评分与档位。
   *
   * <p>🔴 **口径未配置 ⇒ 评分与档位都留空**（null，不是 0 分）。
   * 🔴 **必需的效果数据缺一项 ⇒ 也留空**（「还没录」不是「录了而且很差」）。
   */
  void applyRating(UUID influencerId) {
    RatingRule rule = activeRule();
    BigDecimal score = null;
    UUID tierId = null;
    if (rule != null) {
      Record row =
          sql.fetchOne(
              "select gmv, engagement_rate, refund_rate from influencer where tenant_id = "
                  + TENANT
                  + " and id = ?",
              influencerId);
      if (row != null) {
        score =
            InfluencerRules.score(
                rule,
                row.get("gmv", BigDecimal.class),
                row.get("engagement_rate", BigDecimal.class),
                row.get("refund_rate", BigDecimal.class));
        if (score != null) {
          tierId = tierForScore(score);
        }
      }
    }
    sql.execute(
        "update influencer set rating_score = ?, rating_tier_id = ?, updated_at = now(),"
            + " version = version + 1 where tenant_id = ?::uuid and id = ?::uuid",
        score,
        tierId,
        currentTenant(),
        influencerId);
  }

  /** 生效的评分口径（读出来算给 {@link InfluencerRules} 用）。 */
  private RatingRule activeRule() {
    Record r =
        sql.fetchOne(
            "select weight_gmv, weight_engagement, weight_refund, gmv_cap, engagement_cap,"
                + " refund_cap from influencer_rating_rule where tenant_id = "
                + TENANT
                + " and status = 'ACTIVE'");
    if (r == null) {
      return null;
    }
    return new RatingRule(
        r.get("weight_gmv", BigDecimal.class),
        r.get("weight_engagement", BigDecimal.class),
        r.get("weight_refund", BigDecimal.class),
        r.get("gmv_cap", BigDecimal.class),
        r.get("engagement_cap", BigDecimal.class),
        r.get("refund_cap", BigDecimal.class));
  }

  /** 分数落在哪一档（区间闭区间；命中多档时取排序位最高的那个）。 */
  private UUID tierForScore(BigDecimal score) {
    return one(
        UUID.class,
        "select id from influencer_rating_tier where tenant_id = "
            + TENANT
            + " and status = 'ACTIVE' and ? >= min_score and ? <= max_score"
            + " order by sort_order desc limit 1",
        score,
        score);
  }

  /** 全库重算评级（口径或档位改动后调用）。 */
  private void recomputeAllRatings() {
    List<UUID> ids =
        sql.fetch("select id from influencer where tenant_id = " + TENANT)
            .stream()
            .map(r -> r.get("id", UUID.class))
            .toList();
    for (UUID id : ids) {
      applyRating(id);
    }
  }

  // ==================================================================
  // 黑名单
  // ==================================================================

  public InfluencerBlacklistPage blacklist(
      String status, String reasonCode, UUID influencerId, String keyword, int page, int size) {
    StringBuilder where = new StringBuilder(" where b.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and b.status = ?");
      binds.add(state.toUpperCase(Locale.ROOT));
    }
    String reason = blankToNull(reasonCode);
    if (reason != null) {
      where.append(" and b.reason_code = ?");
      binds.add(reason.toUpperCase(Locale.ROOT));
    }
    if (influencerId != null) {
      where.append(" and b.influencer_id = ?");
      binds.add(influencerId);
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (i.display_name ilike ? or b.reason ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
    }
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    long total =
        count(
            "select count(*) from influencer_blacklist b join influencer i"
                + " on i.tenant_id = b.tenant_id and i.id = b.influencer_id"
                + where,
            binds.toArray());
    List<InfluencerBlacklistRow> items =
        sql.fetch(
                blacklistSelect()
                    + where
                    + " order by b.effective_at desc, b.id desc limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::blacklistRow)
            .toList();
    return new InfluencerBlacklistPage(items, total, safePage, safeSize);
  }

  private String blacklistSelect() {
    return "select b.id, b.influencer_id, i.display_name influencer_name, i.category,"
        + " i.follower_tier, b.reason_code, b.reason, b.effective_at, b.released_at,"
        + " b.released_reason, b.status, b.note, b.created_at, b.updated_at"
        + " from influencer_blacklist b"
        + " join influencer i on i.tenant_id = b.tenant_id and i.id = b.influencer_id";
  }

  private InfluencerBlacklistRow blacklistRow(Record r) {
    return new InfluencerBlacklistRow(
        r.get("id", UUID.class),
        r.get("influencer_id", UUID.class),
        r.get("influencer_name", String.class),
        r.get("category", String.class),
        r.get("follower_tier", String.class),
        r.get("reason_code", String.class),
        r.get("reason", String.class),
        r.get("effective_at", OffsetDateTime.class),
        r.get("released_at", OffsetDateTime.class),
        r.get("released_reason", String.class),
        r.get("status", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private InfluencerBlacklistRow activeBlacklistOf(UUID influencerId) {
    Record r =
        sql.fetchOne(
            blacklistSelect()
                + " where b.tenant_id = "
                + TENANT
                + " and b.influencer_id = ? and b.status = 'ACTIVE'",
            influencerId);
    return r == null ? null : blacklistRow(r);
  }

  /**
   * 拉黑一个达人。
   *
   * <p>🔴 拉黑后**自动从正常检索里排除**（列表查询默认排除生效中的黑名单）。
   * 🔴 一个达人**同时只能有一条生效中的黑名单**（DB partial unique index 兜底），
   * 重复拉黑返回 409 而不是造出第二条。
   */
  public InfluencerBlacklistRow blacklistInfluencer(BlacklistInfluencerRequest request) {
    if (profileRecord(request.influencerId()) == null) {
      throw notFound("达人");
    }
    String reasonCode = text(request.reasonCode()).toUpperCase(Locale.ROOT);
    if (!BLACKLIST_REASON_CODES.contains(reasonCode)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "拉黑原因码不合法：" + request.reasonCode());
    }
    long active =
        count(
            "select count(*) from influencer_blacklist where tenant_id = " + TENANT
                + " and influencer_id = ? and status = 'ACTIVE'",
            request.influencerId());
    if (active > 0) {
      throw conflict("该达人已在黑名单里（生效中），要先解除才能重新拉黑。");
    }
    OffsetDateTime effectiveAt =
        request.effectiveAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : request.effectiveAt();
    UUID id = Ids.next();
    sql.execute(
        "insert into influencer_blacklist (tenant_id,id,influencer_id,reason_code,reason,"
            + " effective_at,released_at,released_reason,status,note,version,created_at,updated_at)"
            + " values (?,?,?,?,?,?::timestamptz,null,'','ACTIVE',?,0,now(),now())",
        currentTenant(),
        id,
        request.influencerId(),
        reasonCode,
        text(request.reason()),
        effectiveAt,
        text(request.note()));
    return blacklistRow(
        sql.fetchOne(
            blacklistSelect() + " where b.tenant_id = " + TENANT + " and b.id = ?", id));
  }

  /**
   * 解除黑名单。
   *
   * <p>🔴 **解除是留痕，不是删行**：解除时刻 + 解除理由写进同一条记录，
   * 误拉黑要能复盘（否则「什么时候解的、为什么解的」永远查不到）。
   */
  public InfluencerBlacklistRow releaseBlacklist(String id, ReleaseBlacklistRequest request) {
    UUID blacklistId = parseId(id);
    Record existing =
        sql.fetchOne(
            "select id, status from influencer_blacklist where tenant_id = " + TENANT + " and id = ?",
            blacklistId);
    if (existing == null) {
      throw notFound("黑名单记录");
    }
    if (!"ACTIVE".equals(existing.get("status", String.class))) {
      throw conflict("这条黑名单记录已经解除了，不能重复解除。");
    }
    sql.execute(
        "update influencer_blacklist set status = 'RELEASED', released_at = ?::timestamptz,"
            + " released_reason = ?, updated_at = now(), version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        OffsetDateTime.now(ZoneOffset.UTC),
        text(request.reason()),
        currentTenant(),
        blacklistId);
    return blacklistRow(
        sql.fetchOne(
            blacklistSelect() + " where b.tenant_id = " + TENANT + " and b.id = ?", blacklistId));
  }

  // ==================================================================
  // 合作历史
  // ==================================================================

  public InfluencerCooperationPage cooperations(
      UUID influencerId, UUID campaignId, String shopCode, String status, int page, int size) {
    StringBuilder where = new StringBuilder(" where c.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    if (influencerId != null) {
      where.append(" and c.influencer_id = ?");
      binds.add(influencerId);
    }
    if (campaignId != null) {
      where.append(" and c.campaign_id = ?");
      binds.add(campaignId);
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and c.shop_code = ?");
      binds.add(shop);
    }
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and c.status = ?");
      binds.add(state.toUpperCase(Locale.ROOT));
    }
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    long total = count("select count(*) from influencer_cooperation c" + where, binds.toArray());
    List<InfluencerCooperationRow> items =
        sql.fetch(
                cooperationSelect()
                    + where
                    + " order by c.started_at desc nulls last, c.id desc limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::cooperationRow)
            .toList();
    return new InfluencerCooperationPage(items, total, safePage, safeSize);
  }

  private String cooperationSelect() {
    return "select c.id, c.influencer_id, i.display_name influencer_name, c.campaign_id,"
        + " cp.campaign_name, cp.external_campaign_id, c.shop_code, c.status, c.started_at,"
        + " c.ended_at, c.gmv, c.order_count, c.commission, c.refund_rate, c.currency, c.note,"
        + " c.created_at, c.updated_at"
        + " from influencer_cooperation c"
        + " join influencer i on i.tenant_id = c.tenant_id and i.id = c.influencer_id"
        + " join ad_campaign cp on cp.tenant_id = c.tenant_id and cp.id = c.campaign_id";
  }

  private InfluencerCooperationRow cooperationRow(Record r) {
    return new InfluencerCooperationRow(
        r.get("id", UUID.class),
        r.get("influencer_id", UUID.class),
        r.get("influencer_name", String.class),
        r.get("campaign_id", UUID.class),
        r.get("campaign_name", String.class),
        r.get("external_campaign_id", String.class),
        r.get("shop_code", String.class),
        r.get("status", String.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("ended_at", OffsetDateTime.class),
        r.get("gmv", BigDecimal.class),
        r.get("order_count", BigDecimal.class),
        r.get("commission", BigDecimal.class),
        r.get("refund_rate", BigDecimal.class),
        r.get("currency", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private List<InfluencerCooperationRow> cooperationsOf(UUID influencerId) {
    return sql.fetch(
            cooperationSelect()
                + " where c.tenant_id = "
                + TENANT
                + " and c.influencer_id = ? order by c.started_at desc nulls last, c.id desc",
            influencerId)
        .stream()
        .map(this::cooperationRow)
        .toList();
  }

  /**
   * 登记一条合作记录（达人 × 活动）。
   *
   * <p>🔴 活动必须**真实存在**（复合 FK 挂 {@code ad_campaign}）——
   * 自由文本的活动名答不出「这个达人跟哪几个活动合作过」。
   * 🔴 **达人与活动必须在同一个店铺**：达人挂在店铺下，跨店铺挂合作会让
   * 「按店铺收敛的可见性」与事实对不上。
   */
  public InfluencerCooperationRow saveCooperation(SaveCooperationRequest request) {
    Record influencer = profileRecord(request.influencerId());
    if (influencer == null) {
      throw notFound("达人");
    }
    Record campaign = campaignRecord(request.campaignId());
    if (campaign == null) {
      throw notFound("广告活动");
    }
    String campaignShop = campaign.get("shop_code", String.class);
    String influencerShop = influencer.get("shop_code", String.class);
    if (!campaignShop.equals(influencerShop)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "达人与活动必须在同一个店铺下（达人挂 " + influencerShop + "，活动挂 " + campaignShop + "）");
    }
    String status = normalizeCooperationStatus(request.status());
    BigDecimal gmv = request.gmv();
    BigDecimal orderCount = request.orderCount();
    BigDecimal commission = request.commission();
    BigDecimal refundRate = request.refundRate();
    if (gmv != null && gmv.signum() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "合作 GMV 不能是负数");
    }
    if (orderCount != null && orderCount.signum() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "合作订单量不能是负数");
    }
    if (commission != null && commission.signum() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "佣金不能是负数");
    }
    if (refundRate != null
        && (refundRate.signum() < 0 || refundRate.compareTo(BigDecimal.ONE) > 0)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退款率必须在 0 ~ 1 之间");
    }
    if (request.startedAt() != null
        && request.endedAt() != null
        && !request.endedAt().isAfter(request.startedAt())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "结束时刻必须晚于开始时刻");
    }
    String currency = text(request.currency()).toUpperCase(Locale.ROOT);
    if (!currency.isEmpty() && currency.length() != 3) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是 3 位字母代码");
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into influencer_cooperation (tenant_id,id,influencer_id,campaign_id,shop_code,status,"
            + " started_at,ended_at,gmv,order_count,commission,refund_rate,currency,note,version,"
            + " created_at,updated_at)"
            + " values (?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?,?,0,now(),now())",
        currentTenant(),
        id,
        request.influencerId(),
        request.campaignId(),
        campaignShop,
        status,
        request.startedAt(),
        request.endedAt(),
        gmv,
        orderCount,
        commission,
        refundRate,
        currency,
        text(request.note()));
    return cooperationRow(
        sql.fetchOne(
            cooperationSelect() + " where c.tenant_id = " + TENANT + " and c.id = ?", id));
  }

  /**
   * 改一条合作记录。
   *
   * <p>🔴 **活动不可改**：合作记录的身份就是「达人 × 活动」，
   * 换活动等于换了一条记录（要换请新建，历史留痕比就地改更有用）。
   */
  public InfluencerCooperationRow updateCooperation(String id, SaveCooperationRequest request) {
    UUID cooperationId = parseId(id);
    Record existing =
        sql.fetchOne(
            "select id, campaign_id from influencer_cooperation where tenant_id = "
                + TENANT
                + " and id = ?",
            cooperationId);
    if (existing == null) {
      throw notFound("合作记录");
    }
    if (!existing.get("campaign_id", UUID.class).equals(request.campaignId())) {
      throw conflict("合作记录的活动不可就地改 —— 要换活动请新建一条（历史留痕比就地改更有用）。");
    }
    if (profileRecord(request.influencerId()) == null) {
      throw notFound("达人");
    }
    Record campaign = campaignRecord(request.campaignId());
    if (campaign == null) {
      throw notFound("广告活动");
    }
    String campaignShop = campaign.get("shop_code", String.class);
    if (!campaignShop.equals(profileRecord(request.influencerId()).get("shop_code", String.class))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "达人与活动必须在同一个店铺下");
    }
    String status = normalizeCooperationStatus(request.status());
    if (request.startedAt() != null
        && request.endedAt() != null
        && !request.endedAt().isAfter(request.startedAt())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "结束时刻必须晚于开始时刻");
    }
    String currency = text(request.currency()).toUpperCase(Locale.ROOT);
    if (!currency.isEmpty() && currency.length() != 3) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是 3 位字母代码");
    }
    sql.execute(
        "update influencer_cooperation set influencer_id = ?, status = ?, started_at = ?::timestamptz,"
            + " ended_at = ?::timestamptz, gmv = ?, order_count = ?, commission = ?, refund_rate = ?,"
            + " currency = ?, note = ?, updated_at = now(), version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        request.influencerId(),
        status,
        request.startedAt(),
        request.endedAt(),
        request.gmv(),
        request.orderCount(),
        request.commission(),
        request.refundRate(),
        currency,
        text(request.note()),
        currentTenant(),
        cooperationId);
    return cooperationRow(
        sql.fetchOne(
            cooperationSelect() + " where c.tenant_id = " + TENANT + " and c.id = ?", cooperationId));
  }

  private Record campaignRecord(UUID campaignId) {
    return sql.fetchOne(
        "select id, shop_code from ad_campaign where tenant_id = " + TENANT + " and id = ?",
        campaignId);
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  private static String normalizeConfigStatus(String status) {
    String state = blankToNull(status);
    if (state == null) {
      return "ACTIVE";
    }
    String upper = state.toUpperCase(Locale.ROOT);
    if (!"ACTIVE".equals(upper) && !"PAUSED".equals(upper)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态只能是 ACTIVE 或 PAUSED");
    }
    return upper;
  }

  private static String normalizeCooperationStatus(String status) {
    String state = blankToNull(status);
    if (state == null) {
      return "PLANNED";
    }
    String upper = state.toUpperCase(Locale.ROOT);
    if (!COOPERATION_STATUSES.contains(upper)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "合作状态只能是 PLANNED / ACTIVE / COMPLETED / CANCELLED");
    }
    return upper;
  }

  /** 给前端下拉用的「按标签分组归拢」视图（分组 → 标签）。 */
  public Map<String, List<String>> tagCodesByGroup() {
    Map<String, List<String>> grouped = new LinkedHashMap<>();
    sql.fetch(
            "select g.group_code, t.tag_code from influencer_tag t"
                + " join influencer_tag_group g on g.tenant_id = t.tenant_id and g.id = t.group_id"
                + " where t.tenant_id = "
                + TENANT
                + " order by g.group_code, t.tag_code")
        .forEach(
            r ->
                grouped
                    .computeIfAbsent(r.get("group_code", String.class), k -> new ArrayList<>())
                    .add(r.get("tag_code", String.class)));
    return grouped;
  }
}
