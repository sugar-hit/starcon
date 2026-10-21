package com.starcon.channel;

import static com.starcon.channel.AdGroupManagementModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 14.1.2.1 广告组管理。
 *
 * <p>蓝图：「管理广告组：关联投放商品、匹配类型、定向设置、默认竞价，查看组级表现。
 * 输入：广告组设置；关联商品选择（从 4 listing 中选）。
 * 输出：广告组记录 + 组级报表入口。
 * 关系：上游 = 14.1.1.2；下游 = 14.1.3.1 关键词、14.2.1.1 竞价。
 * <b>广告组与 SKU 的映射是利润归因的基础</b>——投放的是 listing，归因要落到内部 SKU。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>广告组记录</b> → 上游 14.1.1.2 已交付的 {@code ad_campaign_group}（V277），
 *       本模块**不重复建表**，只在它上面补齐蓝图点名的列（匹配类型 / 定向设置原文）
 *       与组级的「本地已改」标记（V281）。
 *   <li><b>关联投放商品</b> → {@code ad_campaign_group_product}（V277）+ {@code listing}（V171）。
 *       候选清单是**真的 listing**，且必须属于活动所在店铺。
 *   <li><b>匹配类型 / 定向设置</b> → **只存平台原文，不解读**（14.1.3.2 未交付）。
 *   <li><b>默认竞价</b> → 🔴 **复用**上游 14.1.1.2 的 {@code POST /bids}
 *       （带单次上限 200 / 绝对上限 1000 / 逐条审计），
 *       本模块**不另起同义端点** —— 两个端点改同一笔钱，审计就会分叉。
 *   <li><b>查看组级表现</b> → 🔴 **没有事实来源**：现有事实表里没有组级指标表
 *       （{@code ad_campaign_daily_metric} 是 campaign 维度，没有 group_id）。
 *       本模块**不把活动级指标冒充组级指标**，也**不建一张没人写的空表** ——
 *       能力声明里该维度显式 {@code available=false} + {@code ownerModule=14.3.1.2} + 影响说明，
 *       同时给出**组级报表入口**（指向 14.3.1.2 的路由标识）。
 * </ul>
 *
 * <p>🔴 本模块最容易做丢的四件事，这里都挡住了：
 *
 * <ol>
 *   <li><b>本地已改 ≠ 平台侧已改</b>。平台回写队列未交付 ⇒ 所有本地写都只落本地，
 *       并把 {@code pending_platform_writeback} 置 true —— 否则界面上的「已暂停」
 *       会被当成平台侧已暂停，那是假事实。
 *   <li><b>组级预算为空 = 继承活动预算</b>，不是 0；<b>默认竞价为空 = 平台侧没给</b>，不是 0。
 *   <li><b>SKU 只读带出，不做归因</b>。{@code listing_variant.internal_sku_id} 是映射**现状**，
 *       花费归因到 SKU 归 14.3.1.3（未交付）。
 *   <li><b>不猜同批车道的 URL</b>。14.3.1.2 广告组与词报表同批并行开发，
 *       本模块**不 import 它的类、不建它的表、不猜它的 URL 形状** ——
 *       只给出路由标识（模块号）与蓝图写死的前端入口。
 * </ol>
 */
@Service
public class AdGroupManagementService {

  // ==================================================================
  // 护栏：组级写操作的上限（14 章关键约束「AI/规则自动调整必须有上限保护」）
  // ==================================================================

  /** 单个广告组的组级日预算上限（绝对值）。与 14.1.1.1 的活动预算上限同口径。 */
  static final BigDecimal MAX_GROUP_DAILY_BUDGET = new BigDecimal("100000.0000");

  /** 单个广告组的默认竞价上限（绝对值）。与 14.1.1.2 改竞价的绝对上限同口径。 */
  static final BigDecimal MAX_DEFAULT_BID = new BigDecimal("1000.0000");

  /** 一次设置投放商品最多允许关联的 listing 条数。 */
  static final int MAX_PRODUCTS_PER_GROUP = 100;

  /** 广告组状态全集（与 V277 的 ad_campaign_group.status 同口径）。 */
  static final List<String> STATUSES =
      List.of("ENABLED", "PAUSED", "ENDED", "ARCHIVED", "PENDING_REVIEW", "UNKNOWN");

  /** 排序方式全集（白名单：排序字段不许由调用方拼进 SQL）。 */
  static final List<String> SORTS = List.of("updatedAtDesc", "nameAsc", "bidDesc", "statusAsc");

  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 🔴 组级报表入口的前端路由标识（蓝图 L2：14.3 广告分析 → /ads/reports）。 */
  static final String REPORT_ROUTE = "/ads/reports";

  /** 🔴 组级报表入口的稳定标识：责任模块号。 */
  static final String REPORT_OWNER_MODULE = "14.3.1.2 广告组与词报表";

  /** 🔴 定向与匹配类型只存原文的口径说明（14.1.3.2 未交付 ⇒ 不解读）。 */
  static final String TARGETING_NOTE =
      "🔴 匹配类型（matchType）、定向类型（targetingType）与定向设置（targetingSettings）"
          + "**只存平台原文，不解读、不归一、不据此给建议** —— "
          + "「投放定位解读」（地域 / 人群 / 设备 / 时段的定位效果与调整建议）归 14.1.3.2，尚未交付。"
          + "本模块只负责把平台返回的原文**如实存下来并可查**。";

  /** 🔴 平台回写未交付的影响说明。 */
  static final String PLATFORM_WRITEBACK_NOTE =
      "🔴 平台广告回写队列**未交付**（平台适配层 channel/vendor）⇒ "
          + "本模块的所有写动作**只落本地**，并把组标成「本地已改、尚未回写平台」。"
          + "界面会把「本地已改」与「平台侧已改」分开展示 —— "
          + "把本地状态当成平台侧状态就是在骗人（那会让运营以为投放真的停了，其实在烧钱）。";

  /** 🔴 组级指标为什么拿不到。 */
  static final String PERFORMANCE_NOTE =
      "🔴 组级表现**没有事实来源**：现有事实表里**没有组级指标表** —— "
          + "`ad_campaign_daily_metric`（V273）是 **campaign_id 维度**的，**没有 group_id**。"
          + "本模块**不把活动级指标冒充组级指标**（那是假事实，比缺功能严重得多），"
          + "也**不建一张没人写的组级指标表**（空表会被界面渲染成「没有数据」，"
          + "那比明说「拿不到」严重得多 —— 与 14.1.1.2 处理「平台侧修改历史」同一款做法）。"
          + "组级报表入口已给出（见 reportEntry），报表本体归 14.3.1.2。";

  private final DSLContext sql;
  private final AdCampaignService campaigns;
  private final ObjectMapper json;

  public AdGroupManagementService(DSLContext sql, AdCampaignService campaigns, ObjectMapper json) {
    this.sql = sql;
    this.campaigns = campaigns;
    this.json = json;
  }

  // ==================================================================
  // 小工具（照抄 AdCampaignDetailService 的同名写法）
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

  /** LIKE 里的通配符要转义，否则用户搜 "50%" 会匹配到一切。 */
  private static String escapeLike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 绑定，不来自任何客户端头）。 */
  private UUID currentTenant() {
    String raw = one(String.class, "select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /** 当前操作者（已认证成员）。 */
  private UUID currentActor() {
    String raw = one(String.class, "select nullif(current_setting('app.actor',true),'')");
    return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
  }

  /** 跨租户与不存在返回**逐字相同**的 404（照抄上游 14.1.1.1 / 14.1.1.2 的同一款口径）。 */
  private ResponseStatusException notFound(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在");
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

  public AdGroupManagementCapabilities capabilities() {
    return new AdGroupManagementCapabilities(
        List.of(
            new AdGroupCapabilityDimension(
                "group_records",
                "广告组记录：组名 / 状态 / 默认竞价 / 组级预算 / 定向 / 匹配类型（真分页 + 真筛选）",
                true,
                "ad_campaign_group（V277 上游 14.1.1.2）+ V281 补列",
                "",
                "没有广告组记录，「投放到底投在哪个组上」这个问题就没有答案"),
            new AdGroupCapabilityDimension(
                "group_management",
                "广告组的完整管理：新建组 / 改组设置 / 设投放商品（单组粒度的写入口）",
                true,
                "ad_campaign_group + ad_campaign_group_product + ad_group_operation_log（V281）",
                "",
                "14.1.1.2 只给结构的读与「复制结构」这一个写动作；组的日常管理归本模块"),
            new AdGroupCapabilityDimension(
                "targeted_products",
                "关联投放商品：从该活动所在店铺的 listing 里选（替换语义）+ 只读带出内部 SKU 映射现状",
                true,
                "ad_campaign_group_product + listing + listing_variant（V277 / V171）",
                "",
                "「投的是哪个商品」是归因的起点，没有它就无法把花费落到内部 SKU"),
            new AdGroupCapabilityDimension(
                "match_type",
                "匹配类型（平台原文）",
                true,
                "ad_campaign_group.match_type（V281）",
                "",
                "匹配类型是投放结构的必备字段，缺了就没法判断一个组在投什么类型的流量"),
            new AdGroupCapabilityDimension(
                "targeting_settings",
                "定向设置（平台原文串，不拆解不归一）",
                true,
                "ad_campaign_group.targeting_settings + targeting_type（V281 / V277）",
                "",
                "定向原文必须存得下来，否则 14.1.3.2 落地时没有可解读的底稿"),
            new AdGroupCapabilityDimension(
                "default_bid",
                "默认竞价（金额，带绝对上限保护与审计）",
                true,
                "14.1.1.2 活动详情（POST /channel/ad-campaign-details/campaigns/{id}/bids）",
                "",
                "🔴 **复用**上游已有的改竞价入口（单次上限 200 / 绝对上限 1000 / 逐条留审计），"
                    + "本模块**不另起同义端点** —— 两个端点改同一笔钱，审计就会分叉"),
            new AdGroupCapabilityDimension(
                "group_report_entry",
                "🔴 组级报表入口（入口已交付；报表本体在 14.3.1.2）",
                true,
                "14.3.1.2 广告组与词报表（同批并行车道，下一批并入集成头）",
                REPORT_OWNER_MODULE,
                "入口与路由标识已给出；报表本体尚未并入集成头，界面上会标出这一点"),
            new AdGroupCapabilityDimension(
                "group_performance_metrics",
                "🔴 组级表现指标（曝光 / 点击 / 消耗 / ACOS / ROAS …）",
                false,
                "—",
                REPORT_OWNER_MODULE,
                "现有事实表里**没有组级指标表**（ad_campaign_daily_metric 是 campaign_id 维度，"
                    + "没有 group_id）⇒ 组级表现**拿不到真实指标**；"
                    + "本模块**不把活动级指标冒充组级指标**，也**不建一张没人写的空表**"),
            new AdGroupCapabilityDimension(
                "group_keyword_breakdown",
                "🔴 「这个组的关键词」拆分",
                false,
                "—",
                "14.1.3.1 关键词管理",
                "关键词表按 (platform, shop, external_campaign_id) 建，**没有 group_id** ⇒ "
                    + "拿不到组级关键词；活动级关键词可从 14.1.1.2 / 14.1.3.1 查"),
            new AdGroupCapabilityDimension(
                "bid_strategy",
                "竞价策略（动态竞价 / 目标 ACOS 反推竞价 / 竞价范围上限）",
                false,
                "—",
                "14.2.1.1 竞价管理",
                "本模块只提供「把这个组的默认竞价改成多少」这一个动作，不做任何策略"),
            new AdGroupCapabilityDimension(
                "targeting_interpretation",
                "投放定位的解读与调整建议（地域 / 人群 / 设备 / 时段的效果对比）",
                false,
                "—",
                "14.1.3.2 投放定位",
                "本模块只存平台原文的定向字段，不解读也不给建议"),
            new AdGroupCapabilityDimension(
                "sku_attribution",
                "🔴 广告花费归因到内部 SKU（SKU 维度归因报表）",
                false,
                "—",
                "14.3.1.3 商品维度报表",
                "本模块只把 listing → internal_sku_id 的**映射现状**只读带出；"
                    + "**花费归因到 SKU** 属 14.3.1.3（未交付），本模块不做归因计算"),
            new AdGroupCapabilityDimension(
                "platform_writeback",
                "🔴 本地写动作**回写平台**（状态 / 定向 / 投放商品 / 竞价）",
                false,
                "—",
                "平台适配层（channel/vendor）的平台回写队列",
                "本模块的动作只落本地并标记「待回写平台」—— 界面会显式标出，"
                    + "避免把「本地已暂停」当成「平台侧已暂停」"),
            new AdGroupCapabilityDimension(
                "platform_group_sync",
                "🔴 平台侧广告组结构同步（平台侧改了组，本系统自动跟上）",
                false,
                "—",
                "平台适配层（channel/vendor）的平台广告 API 同步",
                "没有平台同步源 ⇒ 组数据的新鲜度取决于谁来登记 / 谁来改；"
                    + "「结构截至 X 时间」必须一起显示，不能用旧结构假装是新的")),
        STATUSES,
        guardrails(),
        reportEntry(),
        performance(),
        TARGETING_NOTE,
        PLATFORM_WRITEBACK_NOTE,
        "上游 = 14.1.1.2 活动详情（V277：ad_campaign_group / ad_campaign_group_product / "
            + "ad_campaign_creative，本模块在其上补列并提供单组粒度的写路径）、"
            + "14.1.1.1 活动列表（V273：活动本体，只读引用）、"
            + "4.1.1.1 Listing（V171：投放商品指向的 listing 与 listing_variant 的 SKU 映射现状，只读）。",
        "下游 = 14.1.3.1 关键词管理（活动维度，组级拆分未交付）、"
            + "14.2.1.1 竞价管理（竞价策略未交付）、"
            + "14.3.1.2 广告组与词报表（组级报表，本批并行开发）、"
            + "14.3.1.3 商品维度报表（SKU 维度归因，未交付）。"
            + "🔴 本模块**不 import** 14.3.1.2 的任何类、**不建**它的表、**不猜**它的 URL 形状；"
            + "🔴 本模块**不实现**竞价策略、投放定位解读、平台回写与平台侧改历史 —— "
            + "能力声明里对应维度显式 available=false，不用空列表冒充已交付。");
  }

  private static AdGroupGuardrails guardrails() {
    return new AdGroupGuardrails(
        MAX_GROUP_DAILY_BUDGET,
        MAX_DEFAULT_BID,
        MAX_PRODUCTS_PER_GROUP,
        "🔴 组级预算是**钱**：一次手滑把组预算写成 100000 就是把活动预算烧光，"
            + "所以组级日预算有绝对上限（与 14.1.1.1 的活动预算上限同口径）。"
            + "🔴 默认竞价的绝对上限与上游 14.1.1.2 改竞价的绝对上限一致（1000）；"
            + "「单次调整幅度上限 200」由上游的改竞价入口裁决 —— "
            + "本模块**复用**那个入口，不另起一套护栏。");
  }

  private static AdGroupReportEntry reportEntry() {
    return new AdGroupReportEntry(
        true,
        REPORT_OWNER_MODULE,
        REPORT_ROUTE,
        "14.3.1.2",
        "🔴 入口已交付（路由标识与责任模块都写在这里）；"
            + "报表本体归 14.3.1.2，与本车道**同批并行开发**，下一批并入集成头。"
            + "本模块**不 import 它的类、不建它的表、不猜它的 URL 形状**。",
        "组级报表入口指向蓝图写死的 14.3 广告分析前端入口（" + REPORT_ROUTE + "），"
            + "组级维度由 14.3.1.2 自己落到那个入口下；"
            + "本模块给的稳定标识是责任模块号（14.3.1.2），并入后按该标识对齐。");
  }

  private static AdGroupPerformanceAvailability performance() {
    return new AdGroupPerformanceAvailability(
        false,
        REPORT_OWNER_MODULE,
        "组级表现**拿不到真实指标** —— 现有事实表里没有组级指标表"
            + "（ad_campaign_daily_metric 是 campaign_id 维度，没有 group_id）；"
            + "本模块**不把活动级指标冒充组级指标**。",
        PERFORMANCE_NOTE);
  }

  // ==================================================================
  // 选项
  // ==================================================================

  /** 筛选项下拉 + 护栏上限 + 报表路由标识。 */
  public AdGroupOptions options() {
    return new AdGroupOptions(
        List.of(
            new AdGroupOption("ENABLED", "投放中"),
            new AdGroupOption("PAUSED", "已暂停"),
            new AdGroupOption("ENDED", "已结束"),
            new AdGroupOption("ARCHIVED", "已归档"),
            new AdGroupOption("PENDING_REVIEW", "审核中"),
            new AdGroupOption("UNKNOWN", "状态未知")),
        List.of(
            new AdGroupOption("updatedAtDesc", "最近更新"),
            new AdGroupOption("nameAsc", "组名"),
            new AdGroupOption("bidDesc", "默认竞价从高到低"),
            new AdGroupOption("statusAsc", "状态")),
        guardrails(),
        reportEntry(),
        TARGETING_NOTE,
        PLATFORM_WRITEBACK_NOTE);
  }

  // ==================================================================
  // 广告组列表
  // ==================================================================

  /**
   * 广告组列表：真分页 + 真筛选 + 真排序（跨活动）。
   *
   * <p>🔴 汇总**不做跨币种金额相加**：广告组的竞价与预算按币种分列，
   * 跨币种相加没有意义（那是把美元和人民币加起来）。汇总只给**条数**。
   */
  public AdGroupPage groups(
      String campaignId,
      String status,
      String keyword,
      String matchType,
      String targetingType,
      String shopCode,
      String sort,
      int page,
      int size) {
    Filter filter = filter(campaignId, status, keyword, matchType, targetingType, shopCode);
    String where = filter.where();
    List<Object> binds = filter.binds();

    String from =
        " from ad_campaign_group g"
            + " join ad_campaign c on c.tenant_id = g.tenant_id and c.id = g.campaign_id";

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    long total = count("select count(*)" + from + where, binds.toArray());

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);

    List<AdGroupRow> items =
        sql.fetch(
                "select g.id, g.campaign_id, c.campaign_name, c.shop_code, c.currency,"
                    + " g.external_group_id, g.group_name, g.status, g.default_bid, g.bid_currency,"
                    + " g.daily_budget, g.targeting_type, g.match_type, g.targeting_settings,"
                    + " (select count(*) from ad_campaign_group_product p"
                    + "   where p.tenant_id = g.tenant_id and p.group_id = g.id) product_count,"
                    + " g.pending_platform_writeback, g.local_changed_at, g.data_as_of, g.updated_at"
                    + from
                    + where
                    + " order by "
                    + orderBy(sort)
                    + " limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(AdGroupManagementService::toRow)
            .toList();

    return new AdGroupPage(items, total, safePage, safeSize);
  }

  /** 排序字段白名单：排序表达式**绝不允许**由调用方拼进 SQL。 */
  private static String orderBy(String sort) {
    String key = blankToNull(sort);
    if (key == null) {
      key = "updatedAtDesc";
    }
    return switch (key) {
      case "nameAsc" -> "g.group_name asc, g.id asc";
      case "bidDesc" -> "g.default_bid desc nulls last, g.id asc";
      case "statusAsc" -> "g.status asc, g.group_name asc, g.id asc";
      case "updatedAtDesc" -> "g.updated_at desc, g.id asc";
      default -> {
        if (!SORTS.contains(key)) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "排序方式只能是 updatedAtDesc / nameAsc / bidDesc / statusAsc");
        }
        yield "g.updated_at desc, g.id asc";
      }
    };
  }

  /** 组级列表汇总（**不受分页影响**：统计的是筛选后的全集）。 */
  public AdGroupSummary summary(
      String campaignId,
      String status,
      String keyword,
      String matchType,
      String targetingType,
      String shopCode) {
    Filter filter = filter(campaignId, status, keyword, matchType, targetingType, shopCode);
    String where = filter.where();
    List<Object> binds = filter.binds();
    String from =
        " from ad_campaign_group g"
            + " join ad_campaign c on c.tenant_id = g.tenant_id and c.id = g.campaign_id";

    Map<String, Long> statusCounts = new LinkedHashMap<>();
    long total = 0L;
    for (Record r :
        sql.fetch("select g.status s, count(*) n" + from + where + " group by g.status", binds.toArray())) {
      statusCounts.put(r.get("s", String.class), r.get("n", Long.class));
      total += r.get("n", Long.class);
    }

    Record totals =
        sql.fetchOne(
            "select count(distinct g.campaign_id) cc,"
                + " count(*) filter (where g.pending_platform_writeback) pw"
                + from
                + where,
            binds.toArray());
    long campaignCount = totals == null ? 0L : totals.get("cc", Long.class);
    long pending = totals == null ? 0L : totals.get("pw", Long.class);

    return new AdGroupSummary(
        total,
        campaignCount,
        pending,
        statusCounts,
        "🔴 汇总只给**条数**：广告组的竞价与预算按**币种分列**，跨币种相加没有意义。"
            + "🔴 「待回写平台」的条数 = 本地已改、平台侧还没改的组数。");
  }

  /** 筛选条件（白名单字段 + 转义后的 LIKE 模式）。 */
  private record Filter(String where, List<Object> binds) {}

  private Filter filter(
      String campaignId,
      String status,
      String keyword,
      String matchType,
      String targetingType,
      String shopCode) {
    StringBuilder where = new StringBuilder(" where g.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();

    String campaign = blankToNull(campaignId);
    if (campaign != null) {
      where.append(" and g.campaign_id = ?");
      binds.add(parseId(campaign));
    }
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and g.status = ?");
      binds.add(state.toUpperCase(Locale.ROOT));
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (g.group_name ilike ? or g.external_group_id ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
    }
    String match = blankToNull(matchType);
    if (match != null) {
      where.append(" and g.match_type = ?");
      binds.add(match);
    }
    String targeting = blankToNull(targetingType);
    if (targeting != null) {
      where.append(" and g.targeting_type = ?");
      binds.add(targeting);
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and c.shop_code = ?");
      binds.add(shop);
    }
    return new Filter(where.toString(), binds);
  }

  // ==================================================================
  // 广告组详情
  // ==================================================================

  /** 广告组详情：组设置 + 投放商品（含只读带出的 SKU 映射现状）+ 下游入口 + 审计。 */
  public AdGroupDetail detail(String groupId) {
    UUID id = parseId(groupId);
    Record row = loadGroup(id);
    if (row == null) {
      throw notFound("广告组");
    }
    AdGroupRow group = toRow(row);
    UUID tenant = currentTenant();

    // ---- 投放商品（带 listing 状态）----
    List<AdGroupProductRow> products = new ArrayList<>();
    Map<UUID, List<UUID>> skuByListing = new LinkedHashMap<>();
    Map<UUID, Long> unmappedByListing = new LinkedHashMap<>();
    for (Record variant :
        sql.fetch(
            "select v.listing_id, v.internal_sku_id,"
                + " (v.internal_sku_id is null) unmapped"
                + " from listing_variant v"
                + " join ad_campaign_group_product p"
                + "   on p.tenant_id = v.tenant_id and p.listing_id = v.listing_id"
                + " where v.tenant_id = " + TENANT + " and p.group_id = ?",
            id)) {
      UUID listingId = variant.get("listing_id", UUID.class);
      UUID sku = variant.get("internal_sku_id", UUID.class);
      if (sku != null) {
        skuByListing.computeIfAbsent(listingId, k -> new ArrayList<>()).add(sku);
      } else {
        unmappedByListing.merge(listingId, 1L, Long::sum);
      }
    }
    for (Record p :
        sql.fetch(
            "select p.id, p.group_id, p.listing_id, p.external_item_id, p.title,"
                + " l.status listing_status, l.review_status listing_review_status"
                + " from ad_campaign_group_product p"
                + " left join listing l on l.tenant_id = p.tenant_id and l.id = p.listing_id"
                + " where p.tenant_id = "
                + TENANT
                + " and p.group_id = ? order by p.id",
            id)) {
      UUID listingId = p.get("listing_id", UUID.class);
      products.add(
          new AdGroupProductRow(
              p.get("id", UUID.class),
              p.get("group_id", UUID.class),
              listingId,
              p.get("external_item_id", String.class),
              p.get("title", String.class),
              p.get("listing_status", String.class),
              p.get("listing_review_status", String.class),
              skuByListing.getOrDefault(listingId, List.of()),
              unmappedByListing.getOrDefault(listingId, 0L),
              "🔴 这里带出的是 **listing → 内部 SKU 的映射现状**（只读），"
                  + "**不是归因结果**：花费归因到 SKU 归 14.3.1.3 商品维度报表，未交付。"));
    }

    long creativeCount =
        count(
            "select count(*) from ad_campaign_creative where tenant_id = " + TENANT + " and group_id = ?",
            id);

    List<AdGroupOperationRow> recent = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id, campaign_id, group_id, operation, before_value, after_value, result, message,"
                + " requested_by, reason, created_at"
                + " from ad_group_operation_log where tenant_id = "
                + TENANT
                + " and group_id = ? order by created_at desc, id desc limit 20",
            id)) {
      recent.add(toOperationRow(r));
    }

    return new AdGroupDetail(
        group,
        products,
        creativeCount,
        reportEntry(),
        performance(),
        entries(),
        recent,
        "🔴 组级表现**当前不可得**：没有组级指标事实表（活动级指标**不冒充**组级指标）；"
            + "组级报表入口见 reportEntry（报表本体归 14.3.1.2）。"
            + "🔴 定向与匹配类型是**平台原文**，本模块不解读。"
            + "🔴 本地写只落本地，组被标成「待回写平台」时，平台侧还没跟着改。");
  }

  /** 下游入口：哪些能走、哪些不能、为什么不能。 */
  private List<AdGroupEntry> entries() {
    return List.of(
        new AdGroupEntry(
            "ADJUST_BID",
            "改默认竞价",
            true,
            "14.1.1.2 活动详情（POST /channel/ad-campaign-details/campaigns/{id}/bids）",
            "",
            "",
            "🔴 **复用**上游的改竞价入口（单次上限 200 / 绝对上限 1000 / 超限逐条拒绝并留审计）—— "
                + "本模块**不另起同义端点**：两个端点改同一笔钱，审计就会分叉。"),
        new AdGroupEntry(
            "GROUP_REPORT",
            "组级报表",
            true,
            REPORT_OWNER_MODULE + "（同批并行开发，下一批并入集成头）",
            REPORT_OWNER_MODULE,
            "入口与路由标识已给出；报表本体尚未并入集成头。",
            "🔴 报表入口的路由标识是 " + REPORT_ROUTE + "，稳定标识是 " + REPORT_OWNER_MODULE + "。"),
        new AdGroupEntry(
            "KEYWORDS",
            "关键词",
            true,
            "14.1.3.1 关键词管理（**活动**维度）",
            "",
            "",
            "🔴 关键词表按 (platform, shop, external_campaign_id) 建，**没有 group_id** ⇒ "
                + "拿不到「这个组的关键词」，只能看活动级关键词。"),
        new AdGroupEntry(
            "BID_STRATEGY",
            "竞价策略（动态竞价 / 目标 ACOS 反推）",
            false,
            "—",
            "14.2.1.1 竞价管理",
            "本模块只做「把默认竞价改成多少」这一个动作，不做任何策略。",
            ""),
        new AdGroupEntry(
            "TARGETING_EFFECT",
            "投放定位效果与调整建议",
            false,
            "—",
            "14.1.3.2 投放定位",
            "本模块只存平台原文的定向字段，不解读也不给建议。",
            ""),
        new AdGroupEntry(
            "SKU_ATTRIBUTION",
            "广告花费归因到内部 SKU",
            false,
            "—",
            "14.3.1.3 商品维度报表",
            "本模块只把 listing → internal_sku_id 的映射现状只读带出，不做归因计算。",
            ""));
  }

  // ==================================================================
  // 新建广告组
  // ==================================================================

  /**
   * 新建广告组（本地真落库）。
   *
   * <p>🔴 三条边界，缺一不可：
   *
   * <ol>
   *   <li><b>平台侧还没有这个组</b>：平台侧 id 落成 {@code LOCAL-{uuid}}
   *       —— 那是**本地生成的标记，不是平台 id**；编一个像平台 id 的字符串会让
   *       「同步回读」把这条本地草稿认成平台广告组。
   *   <li><b>状态一律 PAUSED</b>：平台侧还没有这个组，谈不上「投放中」。
   *   <li><b>标着「待回写平台」</b>：回写队列未交付，界面必须显式告诉运营
   *       「本地已建、还没回写平台」。
   * </ol>
   */
  public AdGroupCreateResult create(AdGroupCreateRequest request) {
    var campaign = campaigns.campaign(request.campaignId().toString()).campaign();
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    UUID groupId = Ids.next();

    String groupName = text(request.groupName());
    if (groupName.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "广告组名不能为空");
    }
    if (groupName.length() > 200) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "广告组名最长 200 个字符");
    }

    BigDecimal defaultBid = request.defaultBid();
    if (defaultBid != null) {
      if (defaultBid.signum() < 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "默认竞价不能为负");
      }
      if (defaultBid.compareTo(MAX_DEFAULT_BID) > 0) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "默认竞价超过单组竞价上限 " + MAX_DEFAULT_BID);
      }
    }

    BigDecimal dailyBudget = request.dailyBudget();
    if (dailyBudget != null) {
      if (dailyBudget.signum() < 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "组级日预算不能为负");
      }
      if (dailyBudget.compareTo(MAX_GROUP_DAILY_BUDGET) > 0) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "组级日预算超过单组预算上限 " + MAX_GROUP_DAILY_BUDGET);
      }
    }

    // 🔴 竞价币种必须与活动币种一致：跨币种的组级预算没法与活动预算放在一起看。
    String bidCurrency = campaign.currency();
    String requestedCurrency = blankToNull(request.bidCurrency());
    if (requestedCurrency != null && !requestedCurrency.equalsIgnoreCase(bidCurrency)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "竞价币种必须与活动币种一致（活动币种 " + bidCurrency + "）");
    }

    String targetingType = bounded(request.targetingType(), 64, "定向类型");
    String matchType = bounded(request.matchType(), 64, "匹配类型");
    String targetingSettings = text(request.targetingSettings());
    if (targetingSettings.length() > 4000) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "定向设置原文最长 4000 个字符");
    }

    OffsetDateTime now = OffsetDateTime.now();
    String externalGroupId = "LOCAL-" + groupId;

    sql.execute(
        "insert into ad_campaign_group (tenant_id,id,campaign_id,external_group_id,group_name,status,"
            + " default_bid,bid_currency,daily_budget,targeting_type,match_type,targeting_settings,"
            + " pending_platform_writeback,local_changed_at,data_as_of,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,true,?::timestamptz,?::timestamptz,now(),now())",
        tenant,
        groupId,
        campaign.id(),
        externalGroupId,
        groupName,
        "PAUSED",
        defaultBid,
        bidCurrency,
        dailyBudget,
        targetingType,
        matchType,
        targetingSettings,
        now,
        now);

    logOperation(
        tenant,
        actor,
        campaign.id(),
        groupId,
        "CREATE_GROUP",
        "",
        snapshot(
            groupName,
            "PAUSED",
            defaultBid,
            bidCurrency,
            dailyBudget,
            targetingType,
            matchType,
            targetingSettings,
            0),
        "APPLIED",
        "已新建（本地），尚未回写平台",
        request.reason());

    return new AdGroupCreateResult(
        groupId,
        campaign.id(),
        groupName,
        "PAUSED",
        externalGroupId,
        defaultBid,
        bidCurrency,
        dailyBudget,
        targetingType,
        matchType,
        targetingSettings,
        true,
        "🔴 广告组已**本地落库**，但**平台侧还没有这个组**：平台回写队列未交付，"
            + "组标着「待回写平台」、状态落成暂停（平台侧没建，谈不上投放）。"
            + "🔴 平台侧 id 是 " + externalGroupId + " —— **本地生成的标记，不是平台 id**，"
            + "同步回读时它不会被当成平台广告组。"
            + "🔴 改默认竞价走**上游 14.1.1.2 的改竞价入口**（带护栏与审计），本模块不另起同义端点。");
  }

  // ==================================================================
  // 改组设置
  // ==================================================================

  /**
   * 改组设置（部分更新）。
   *
   * <p>🔴 语义是**部分更新**：没给的字段**不改**；想把组级预算恢复成「继承活动预算」
   * 要显式给 {@code clearDailyBudget=true}（{@code null} 与「不改」在 JSON 里分不开）。
   * 🔴 每次真正的变更都会把组标成「待回写平台」—— 平台侧还没跟着改。
   */
  public AdGroupSettingsUpdateResult updateSettings(String groupId, AdGroupSettingsUpdateRequest request) {
    UUID id = parseId(groupId);
    Record row = loadGroup(id);
    if (row == null) {
      throw notFound("广告组");
    }
    AdGroupRow before = toRow(row);
    UUID tenant = currentTenant();
    UUID actor = currentActor();

    boolean clearBudget = Boolean.TRUE.equals(request.clearDailyBudget());
    if (clearBudget && request.dailyBudget() != null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "不能同时给组级预算数值与「恢复继承活动预算」");
    }

    // 🔴 默认竞价**不在**本端点里：改竞价是钱，走上游 14.1.1.2 的改竞价入口
    //   （带单次上限 200 / 绝对上限 1000 / 逐条留审计）。
    //   这里**明确拒绝**而不是静默忽略 —— 静默忽略会让调用方以为改成功了。
    if (request.defaultBid() != null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "默认竞价不在本端点里：改竞价走 14.1.1.2 的 "
              + "POST /channel/ad-campaign-details/campaigns/{id}/bids（带护栏与审计）");
    }

    // ---- 逐字段算出「改后值」----
    String groupName = request.groupName() == null ? before.groupName() : text(request.groupName());
    if (groupName.length() > 200) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "广告组名最长 200 个字符");
    }

    String status = before.status();
    if (request.status() != null) {
      String candidate = text(request.status()).toUpperCase(Locale.ROOT);
      if (!STATUSES.contains(candidate)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "广告组状态不合法：" + request.status());
      }
      status = candidate;
    }

    BigDecimal dailyBudget = before.dailyBudget();
    if (clearBudget) {
      dailyBudget = null;
    } else if (request.dailyBudget() != null) {
      if (request.dailyBudget().signum() < 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "组级日预算不能为负");
      }
      if (request.dailyBudget().compareTo(MAX_GROUP_DAILY_BUDGET) > 0) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "组级日预算超过单组预算上限 " + MAX_GROUP_DAILY_BUDGET);
      }
      dailyBudget = request.dailyBudget();
    }

    String targetingType =
        request.targetingType() == null
            ? before.targetingType()
            : bounded(request.targetingType(), 64, "定向类型");
    String matchType =
        request.matchType() == null ? before.matchType() : bounded(request.matchType(), 64, "匹配类型");
    String targetingSettings =
        request.targetingSettings() == null
            ? before.targetingSettings()
            : text(request.targetingSettings());
    if (targetingSettings.length() > 4000) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "定向设置原文最长 4000 个字符");
    }

    // ---- 到底哪些字段真的变了 ----
    List<String> changed = new ArrayList<>();
    if (!groupName.equals(before.groupName())) {
      changed.add("groupName");
    }
    if (!status.equals(before.status())) {
      changed.add("status");
    }
    if (!sameMoney(dailyBudget, before.dailyBudget())) {
      changed.add("dailyBudget");
    }
    if (!targetingType.equals(before.targetingType())) {
      changed.add("targetingType");
    }
    if (!matchType.equals(before.matchType())) {
      changed.add("matchType");
    }
    if (!targetingSettings.equals(before.targetingSettings())) {
      changed.add("targetingSettings");
    }

    if (changed.isEmpty()) {
      // 🔴 什么都没改也要留痕（SKIPPED）：事后查「为什么这次没生效」要查得到。
      logOperation(
          tenant,
          actor,
          before.campaignId(),
          id,
          "UPDATE_SETTINGS",
          snapshotOf(before),
          snapshotOf(before),
          "SKIPPED",
          "没有字段发生变化",
          request.reason());
      return new AdGroupSettingsUpdateResult(
          id,
          before,
          List.of(),
          before.pendingPlatformWriteback(),
          "没有任何字段发生变化 ⇒ 未落库、未标记待回写（SKIPPED 已留痕）。");
    }

    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update ad_campaign_group set group_name = ?, status = ?, daily_budget = ?,"
            + " targeting_type = ?, match_type = ?, targeting_settings = ?,"
            + " pending_platform_writeback = true, local_changed_at = ?::timestamptz,"
            + " updated_at = now()"
            + " where tenant_id = "
            + TENANT
            + " and id = ?",
        groupName,
        status,
        dailyBudget,
        targetingType,
        matchType,
        targetingSettings,
        now,
        id);

    AdGroupRow after = toRow(loadGroup(id));
    logOperation(
        tenant,
        actor,
        before.campaignId(),
        id,
        "UPDATE_SETTINGS",
        snapshotOf(before),
        snapshotOf(after),
        "APPLIED",
        "已生效（本地），尚未回写平台",
        request.reason());

    return new AdGroupSettingsUpdateResult(
        id,
        after,
        changed,
        true,
        "🔴 变更已**落本地**，组标着「本地已改、尚未回写平台」—— "
            + "平台回写队列未交付，界面上会显式标出，避免把「本地已暂停」当成「平台侧已暂停」。"
            + "🔴 默认竞价**不在**本端点里：改竞价是钱，走上游 14.1.1.2 的改竞价入口（带护栏与审计）。");
  }

  // ==================================================================
  // 设投放商品
  // ==================================================================

  /**
   * 设投放商品（**替换**语义：没列出的会被解除关联）。
   *
   * <p>🔴 投放的商品必须**属于活动所在店铺** —— listing 属于店铺，跨店铺投放不成立。
   */
  public AdGroupProductUpdateResult setProducts(String groupId, AdGroupProductUpdateRequest request) {
    UUID id = parseId(groupId);
    Record row = loadGroup(id);
    if (row == null) {
      throw notFound("广告组");
    }
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    UUID campaignId = row.get("campaign_id", UUID.class);
    String shopCode = row.get("shop_code", String.class);

    Set<UUID> wanted = new LinkedHashSet<>();
    for (UUID listingId : request.listingIds()) {
      if (listingId == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "投放商品 id 不能为空");
      }
      wanted.add(listingId);
    }
    if (wanted.size() > MAX_PRODUCTS_PER_GROUP) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "一个广告组最多关联 " + MAX_PRODUCTS_PER_GROUP + " 个投放商品，本次是 " + wanted.size() + " 个");
    }

    UUID shopId = shopId(shopCode);
    if (shopId == null) {
      throw notFound("活动所在店铺");
    }

    List<UUID> added = new ArrayList<>();
    List<UUID> removed = new ArrayList<>();

    Set<UUID> current = new LinkedHashSet<>();
    for (Record p :
        sql.fetch(
            "select listing_id from ad_campaign_group_product where tenant_id = " + TENANT + " and group_id = ?",
            id)) {
      current.add(p.get("listing_id", UUID.class));
    }

    for (UUID listingId : wanted) {
      Record listing =
          sql.fetchOne(
              "select id, shop_id, external_item_id, title from listing where tenant_id = " + TENANT + " and id = ?",
              listingId);
      if (listing == null) {
        throw notFound("投放商品对应的 listing");
      }
      if (!listing.get("shop_id", UUID.class).equals(shopId)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "投放商品必须属于活动所在店铺（listing 属于店铺，跨店铺投放不成立）");
      }
      if (!current.contains(listingId)) {
        added.add(listingId);
      }
      sql.execute(
          "insert into ad_campaign_group_product (tenant_id,id,campaign_id,group_id,listing_id,"
              + " external_item_id,title,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,now(),now())"
              + " on conflict (tenant_id,group_id,listing_id) do update"
              + " set external_item_id = excluded.external_item_id, title = excluded.title,"
              + " updated_at = now()",
          tenant,
          Ids.next(),
          campaignId,
          id,
          listingId,
          listing.get("external_item_id", String.class),
          listing.get("title", String.class));
    }

    for (Record stale :
        sql.fetch(
            "select id, listing_id from ad_campaign_group_product where tenant_id = " + TENANT + " and group_id = ?",
            id)) {
      UUID listingId = stale.get("listing_id", UUID.class);
      if (!wanted.contains(listingId)) {
        sql.execute(
            "delete from ad_campaign_group_product where tenant_id = " + TENANT + " and id = ?",
            stale.get("id", UUID.class));
        removed.add(listingId);
      }
    }

    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update ad_campaign_group set pending_platform_writeback = true,"
            + " local_changed_at = ?::timestamptz, updated_at = now()"
            + " where tenant_id = " + TENANT + " and id = ?",
        now,
        id);

    List<UUID> beforeIds = new ArrayList<>(current);
    List<UUID> afterIds = new ArrayList<>(wanted);
    logOperation(
        tenant,
        actor,
        campaignId,
        id,
        "SET_PRODUCTS",
        productSnapshot(beforeIds),
        productSnapshot(afterIds),
        "APPLIED",
        "已生效（本地），尚未回写平台",
        request.reason());

    long count =
        count(
            "select count(*) from ad_campaign_group_product where tenant_id = " + TENANT + " and group_id = ?",
            id);

    return new AdGroupProductUpdateResult(
        id,
        added,
        removed,
        count,
        true,
        "🔴 投放商品已**落本地**，组标着「本地已改、尚未回写平台」—— "
            + "投放商品是**平台侧事实**，平台回写队列未交付前，平台侧这个组的投放清单还是旧的。"
            + "🔴 语义是**替换**：没列出的 listing 已解除关联。");
  }

  // ==================================================================
  // 候选投放商品
  // ==================================================================

  /**
   * 可选投放商品候选：该活动**所在店铺**的 listing。
   *
   * <p>🔴 候选必须是**真的 listing**（不是硬编码常量），且必须属于活动所在店铺 ——
   * 跨店铺投放在物理上不成立。
   */
  public AdGroupProductCandidatePage productCandidates(
      String campaignId, String groupId, String keyword, int page, int size) {
    var campaign = campaigns.campaign(parseId(campaignId).toString()).campaign();
    UUID shopId = shopId(campaign.shopCode());
    if (shopId == null) {
      throw notFound("活动所在店铺");
    }

    Set<UUID> selected = new LinkedHashSet<>();
    if (groupId != null && !groupId.isBlank()) {
      UUID gid = parseId(groupId);
      for (Record r :
          sql.fetch(
              "select listing_id from ad_campaign_group_product where tenant_id = " + TENANT + " and group_id = ?",
              gid)) {
        selected.add(r.get("listing_id", UUID.class));
      }
    }

    StringBuilder where = new StringBuilder(" where l.tenant_id = " + TENANT + " and l.shop_id = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(shopId);
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (l.title ilike ? or l.external_item_id ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
    }

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    long total = count("select count(*) from listing l" + where, binds.toArray());

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);

    List<Record> rows =
        sql.fetch(
            "select l.id, l.external_item_id, l.title, l.status, l.review_status"
                + " from listing l"
                + where
                + " order by l.id limit ? offset ?",
            pageBinds.toArray());

    Map<UUID, List<UUID>> skuByListing = new LinkedHashMap<>();
    if (!rows.isEmpty()) {
      List<UUID> listingIds = rows.stream().map(r -> r.get("id", UUID.class)).toList();
      for (Record v :
          sql.fetch(
              "select listing_id, internal_sku_id from listing_variant"
                  + " where tenant_id = "
                  + TENANT
                  + " and internal_sku_id is not null and listing_id in ("
                  + placeholders(listingIds.size())
                  + ")",
              listingIds.toArray())) {
        skuByListing
            .computeIfAbsent(v.get("listing_id", UUID.class), k -> new ArrayList<>())
            .add(v.get("internal_sku_id", UUID.class));
      }
    }

    List<AdGroupProductCandidateRow> items = new ArrayList<>();
    for (Record r : rows) {
      UUID listingId = r.get("id", UUID.class);
      items.add(
          new AdGroupProductCandidateRow(
              listingId,
              r.get("external_item_id", String.class),
              r.get("title", String.class),
              r.get("status", String.class),
              r.get("review_status", String.class),
              selected.contains(listingId),
              skuByListing.getOrDefault(listingId, List.of())));
    }

    return new AdGroupProductCandidatePage(items, total, safePage, safeSize);
  }

  // ==================================================================
  // 操作审计
  // ==================================================================

  /** 组级操作审计：真分页（按时刻倒序）。 */
  public AdGroupOperationPage operations(String groupId, int page, int size) {
    UUID id = parseId(groupId);
    if (loadGroup(id) == null) {
      throw notFound("广告组");
    }
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    long total =
        count(
            "select count(*) from ad_group_operation_log where tenant_id = " + TENANT + " and group_id = ?",
            id);
    List<AdGroupOperationRow> items =
        sql.fetch(
                "select id, campaign_id, group_id, operation, before_value, after_value, result, message,"
                    + " requested_by, reason, created_at"
                    + " from ad_group_operation_log where tenant_id = "
                    + TENANT
                    + " and group_id = ? order by created_at desc, id desc limit ? offset ?",
                id,
                safeSize,
                safePage * safeSize)
            .stream()
            .map(AdGroupManagementService::toOperationRow)
            .toList();
    return new AdGroupOperationPage(items, total, safePage, safeSize);
  }

  // ==================================================================
  // 公共查询与审计写入
  // ==================================================================

  /** 取一个广告组（带活动上下文）。跨租户与不存在返回**逐字相同**的 404。 */
  private Record loadGroup(UUID groupId) {
    return sql.fetchOne(
        "select g.id, g.campaign_id, c.campaign_name, c.shop_code, c.currency,"
            + " g.external_group_id, g.group_name, g.status, g.default_bid, g.bid_currency,"
            + " g.daily_budget, g.targeting_type, g.match_type, g.targeting_settings,"
            + " (select count(*) from ad_campaign_group_product p"
            + "   where p.tenant_id = g.tenant_id and p.group_id = g.id) product_count,"
            + " g.pending_platform_writeback, g.local_changed_at, g.data_as_of, g.updated_at"
            + " from ad_campaign_group g"
            + " join ad_campaign c on c.tenant_id = g.tenant_id and c.id = g.campaign_id"
            + " where g.tenant_id = "
            + TENANT
            + " and g.id = ?",
        groupId);
  }

  private static AdGroupRow toRow(Record r) {
    return new AdGroupRow(
        r.get("id", UUID.class),
        r.get("campaign_id", UUID.class),
        r.get("campaign_name", String.class),
        r.get("shop_code", String.class),
        r.get("currency", String.class),
        r.get("external_group_id", String.class),
        r.get("group_name", String.class),
        r.get("status", String.class),
        r.get("default_bid", BigDecimal.class),
        r.get("bid_currency", String.class),
        r.get("daily_budget", BigDecimal.class),
        r.get("targeting_type", String.class),
        r.get("match_type", String.class),
        r.get("targeting_settings", String.class),
        r.get("product_count", Long.class),
        r.get("pending_platform_writeback", Boolean.class),
        r.get("local_changed_at", OffsetDateTime.class),
        r.get("data_as_of", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static AdGroupOperationRow toOperationRow(Record r) {
    return new AdGroupOperationRow(
        r.get("id", UUID.class),
        r.get("campaign_id", UUID.class),
        r.get("group_id", UUID.class),
        r.get("operation", String.class),
        r.get("before_value", String.class),
        r.get("after_value", String.class),
        r.get("result", String.class),
        r.get("message", String.class),
        r.get("requested_by", UUID.class),
        r.get("reason", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /** 留痕：组级每一次写都要有据可查（被拒 / 跳过的同样留）。 */
  private void logOperation(
      UUID tenant,
      UUID actor,
      UUID campaignId,
      UUID groupId,
      String operation,
      String beforeValue,
      String afterValue,
      String result,
      String message,
      String reason) {
    sql.execute(
        "insert into ad_group_operation_log (tenant_id,id,campaign_id,group_id,operation,"
            + " before_value,after_value,result,message,requested_by,reason,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,now())",
        tenant,
        Ids.next(),
        campaignId,
        groupId,
        operation,
        beforeValue,
        afterValue,
        result,
        message,
        actor == null ? tenant : actor,
        text(reason));
  }

  /** 设置快照（紧凑 JSON 原文）：变更前后都存一份，复盘时不用猜当时到底是什么值。 */
  private String snapshot(
      String groupName,
      String status,
      BigDecimal defaultBid,
      String bidCurrency,
      BigDecimal dailyBudget,
      String targetingType,
      String matchType,
      String targetingSettings,
      long productCount) {
    ObjectNode node = json.createObjectNode();
    node.put("groupName", groupName);
    node.put("status", status);
    node.put("defaultBid", defaultBid == null ? null : defaultBid.toPlainString());
    node.put("bidCurrency", bidCurrency);
    node.put("dailyBudget", dailyBudget == null ? null : dailyBudget.toPlainString());
    node.put("targetingType", targetingType);
    node.put("matchType", matchType);
    node.put("targetingSettings", targetingSettings);
    node.put("productCount", productCount);
    return node.toString();
  }

  private String snapshotOf(AdGroupRow row) {
    return snapshot(
        row.groupName(),
        row.status(),
        row.defaultBid(),
        row.bidCurrency(),
        row.dailyBudget(),
        row.targetingType(),
        row.matchType(),
        row.targetingSettings(),
        row.productCount());
  }

  /** 投放商品快照（listing id 列表）。 */
  private String productSnapshot(List<UUID> listingIds) {
    ObjectNode node = json.createObjectNode();
    ArrayNode array = node.putArray("listingIds");
    listingIds.forEach(id -> array.add(id.toString()));
    return node.toString();
  }

  /** 店铺 id（按租户 + 编码）。 */
  private UUID shopId(String shopCode) {
    return one(UUID.class, "select id from shop where tenant_id = " + TENANT + " and code = ?", shopCode);
  }

  /** 长度受限的原文字段。 */
  private static String bounded(String value, int max, String what) {
    String trimmed = text(value);
    if (trimmed.length() > max) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, what + "最长 " + max + " 个字符");
    }
    return trimmed;
  }

  /** 金额比较：null 与 0 含义相反，所以 null 只与 null 相等。 */
  private static boolean sameMoney(BigDecimal a, BigDecimal b) {
    if (a == null || b == null) {
      return a == null && b == null;
    }
    return a.compareTo(b) == 0;
  }

  /** IN (?, ?, ...) 的占位符。 */
  private static String placeholders(int n) {
    return String.join(",", java.util.Collections.nCopies(n, "?"));
  }
}
