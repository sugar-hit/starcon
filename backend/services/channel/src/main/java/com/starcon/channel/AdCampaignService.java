package com.starcon.channel;

import static com.starcon.channel.AdCampaignModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * 14.1.1.1 广告活动列表。
 *
 * <p>蓝图：「广告活动主列表：平台、店铺、活动名、类型、状态、预算、消耗、曝光、点击、订单、销售额、ACOS/ROAS，
 * 按维度筛选与排序，支持批量暂停/启用/调预算。输入：筛选条件（平台、店铺、状态、日期）；广告数据同步结果；用户数据范围。
 * 输出：分页列表 + 汇总；批量操作任务。关系：上游 = 平台广告 API 同步；下游 = 14.1.1.2 详情、14.2.2.1 预算。
 * <b>广告数据同步是本系统的地基</b>：同步失败时页面要明确显示「数据截至 X 时间」，不能用空数据假装没有投放。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>广告活动主列表</b> → {@link #campaigns(...)}：平台、店铺、活动名、类型、状态、预算、
 *       消耗、曝光、点击、订单、销售额、ACOS/ROAS 全部来自真实表，按维度筛选与排序。
 *   <li><b>分页列表 + 汇总</b> → {@link AdCampaignPage}：列表分页，<b>汇总统计的是筛选后的全集</b>，
 *       不是当前页 —— 否则翻页会让「总消耗」跳来跳去。
 *   <li><b>批量暂停 / 启用 / 调预算</b> → {@link #bulkOperation(BulkOperationRequest)}：只接受
 *       <b>显式选中的一批 id</b>，逐条返回执行结果，带单次与总额护栏。
 *   <li><b>广告数据同步结果</b> → {@link #importCampaigns(ImportRequest)}：本模块**自带真实写入口**
 *       （平台同步器尚未交付），同步运行落 {@code ad_campaign_sync_run}，活动与每日指标落库。
 *   <li><b>用户数据范围</b> → 由 DB 的 {@code data_scope}（RESTRICTIVE）策略按店铺 owner / 部门收敛，
 *       本类不自己实现范围判断。
 * </ul>
 *
 * <p>🔴 本模块最容易犯的错是把「没有数据」和「没有投放」渲染成同一种东西。这里用三段式的空态
 * （{@code NO_SYNCED_DATA} / {@code NO_CAMPAIGNS} / {@code FILTERED_EMPTY}）把它们分开：
 * 没有一次成功的同步 ⇒ 只知道「还没同步到数据」，**不能**说「本租户没有投放」。
 *
 * <p>🔴 归一类型与原始类型**同时落库、同时返回**：{@code platform_ad_type} 是平台原文，
 * {@code internal_ad_type} 是归一后的内部类型（{@code UNMAPPED} 是显式合法值，不是兜底）。
 * 两列都由 DB 触发器从 14.1.2.2 的档案复制，服务层写什么都不作数。
 *
 * <p>🔴 数据来源纪律：平台码表（V1 {@code platform}）、店铺（V1 {@code shop}）、
 * 广告类型档案（V146 {@code ad_type_profile}）**同库同服务**只读引用，本类不复制它们的任何一列，
 * 也不碰 trade / listing 的表。
 */
@Service
public class AdCampaignService {

  // ==================================================================
  // 护栏：批量调预算的上限（蓝图 14 章关键约束「必须有上限保护」）
  // ==================================================================

  /** 单个活动一次允许调整的预算上限（绝对值）。 */
  static final BigDecimal MAX_SINGLE_BUDGET_DELTA = new BigDecimal("5000.0000");

  /** 一次批量请求允许的总预算调整上限（绝对值）。 */
  static final BigDecimal MAX_TOTAL_BUDGET_DELTA = new BigDecimal("20000.0000");

  /** 单个活动的预算上限（调预算后的结果值不得超过）。 */
  static final BigDecimal MAX_CAMPAIGN_BUDGET = new BigDecimal("100000.0000");

  /** 一次批量请求最多允许的活动条数。 */
  static final int MAX_CAMPAIGNS_PER_REQUEST = 200;

  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 归一后的状态全集（与 V146 的 ad_status 同口径）。 */
  static final List<String> STATUSES =
      List.of("ENABLED", "PAUSED", "ENDED", "ARCHIVED", "PENDING_REVIEW", "UNKNOWN");

  /** 归一后的内部类型全集（UNMAPPED 是显式值，不是兜底）。 */
  static final List<String> INTERNAL_AD_TYPES =
      List.of(
          "AUTO_DISCOVERY",
          "SPONSORED_BRAND",
          "SPONSORED_DISPLAY",
          "BRAND_AD",
          "DSP_PROGRAMMATIC",
          "PROMOTION_EVENT",
          "UNMAPPED");

  /** 排序方式 → ORDER BY 子句。**白名单映射**：排序串绝不拼进 SQL。 */
  private static final Map<String, String> SORTS =
      Map.ofEntries(
          Map.entry("SPEND_DESC", "order by coalesce(m.spend,0) desc, c.id desc"),
          Map.entry("SPEND_ASC", "order by coalesce(m.spend,0) asc, c.id desc"),
          Map.entry("SALES_DESC", "order by coalesce(m.sales,0) desc, c.id desc"),
          Map.entry(
              "ACOS_ASC",
              "order by (case when coalesce(m.sales,0) > 0 then coalesce(m.spend,0)/m.sales end)"
                  + " asc nulls last, c.id desc"),
          Map.entry(
              "ROAS_DESC",
              "order by (case when coalesce(m.spend,0) > 0 then coalesce(m.sales,0)/m.spend end)"
                  + " desc nulls last, c.id desc"),
          Map.entry("IMPRESSIONS_DESC", "order by coalesce(m.impressions,0) desc, c.id desc"),
          Map.entry("CLICKS_DESC", "order by coalesce(m.clicks,0) desc, c.id desc"),
          Map.entry("ORDERS_DESC", "order by coalesce(m.orders,0) desc, c.id desc"),
          Map.entry("BUDGET_DESC", "order by coalesce(c.daily_budget,0) desc, c.id desc"),
          Map.entry("NAME_ASC", "order by c.campaign_name asc, c.id asc"),
          Map.entry("RECENT", "order by c.data_as_of desc, c.id desc"));

  static final String DEFAULT_SORT = "SPEND_DESC";

  /** ACOS / ROAS 的口径定义：随汇总一起返回，让界面表头与跨部门沟通用同一份定义。 */
  static final String METRIC_DEFINITION_NOTE =
      "ACOS = 消耗 / 销售额，ROAS = 销售额 / 消耗，两者互为倒数；分母为 0 时返回空值（不可比），"
          + "不是 0。汇总的 ACOS / ROAS 是**先汇总再相除**（总消耗 / 总销售额），"
          + "不是各活动比率的平均 —— 后者是数学错误，会把两条 ACOS 100% 的活动说成整体 50%。";

  private final DSLContext sql;

  public AdCampaignService(DSLContext sql) {
    this.sql = sql;
  }

  // ==================================================================
  // 小工具（照抄 AdTypeDiffService 的同名写法）
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

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
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

  /** 当前操作者（已认证成员）。 */
  private UUID currentActor() {
    String raw = one(String.class, "select nullif(current_setting('app.actor',true),'')");
    return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
  }

  /** 跨租户与不存在返回**逐字相同**的 404（照抄 10.1.1.2 / 10.1.2.1 同款口径）。 */
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

  public AdCampaignCapabilities capabilities() {
    return new AdCampaignCapabilities(
        List.of(
            new AdCampaignCapabilityDimension(
                "campaign_list",
                "广告活动主列表（平台、店铺、活动名、类型、状态、预算、消耗、曝光、点击、订单、销售额、ACOS/ROAS）",
                true,
                "ad_campaign（V273）",
                "",
                "没有它就没有广告投放的入口页面，14.1.1.2 / 14.2.2.1 都没有落点"),
            new AdCampaignCapabilityDimension(
                "filter_and_sort",
                "按平台 / 店铺 / 状态 / 类型 / 日期筛选与按维度排序",
                true,
                "ad_campaign + ad_campaign_daily_metric（V273）",
                "",
                "不能筛不能排的列表在活动上百条之后就等于没用"),
            new AdCampaignCapabilityDimension(
                "summary",
                "筛选后全集的汇总（消耗、销售额、ACOS/ROAS 等，不受分页影响）",
                true,
                "ad_campaign_daily_metric 聚合（V273）",
                "",
                "只看当前页会让人把「这一页的消耗」当成「总消耗」"),
            new AdCampaignCapabilityDimension(
                "data_as_of",
                "「数据截至 X 时间」：同步水位 + 逐平台/店铺的同步状态与失败原因",
                true,
                "ad_campaign_sync_run.data_as_of（V273）",
                "",
                "没有它就分不清「没投放」与「还没同步到数据」"),
            new AdCampaignCapabilityDimension(
                "campaign_ingest",
                "广告活动与每日指标的真实写入口（同步结果落库）",
                true,
                "POST /imports → ad_campaign + ad_campaign_daily_metric（V273）",
                "",
                "平台同步器尚未交付；没有这个入口，列表只能靠假数据撑着"),
            new AdCampaignCapabilityDimension(
                "bulk_pause_enable_budget",
                "对**显式选中的一批活动 id** 直接批量暂停 / 启用 / 调预算，逐条返回执行结果",
                true,
                "ad_campaign_operation_log（V273）",
                "",
                "没有它，改一次预算状态只能一条一条点"),
            new AdCampaignCapabilityDimension(
                "bulk_select_by_filter",
                "按筛选结果全选并预览变更",
                false,
                "—",
                "14.1.1.3 批量操作",
                "本模块只接受显式选中的 id；「按筛选全选」会让人误改到没看过的活动"),
            new AdCampaignCapabilityDimension(
                "bulk_async_task",
                "批量调整的异步任务与逐条执行结果（后台队列）",
                false,
                "—",
                "14.1.1.3 批量操作",
                "本模块的批量操作是同步返回逐条结果的；大规模调整的排队与重试归 14.1.1.3"),
            new AdCampaignCapabilityDimension(
                "platform_sync_scheduler",
                "平台广告 API 的定时同步器",
                false,
                "—",
                "平台适配层（channel/vendor）",
                "同步只能由人工或外部调度触发；广告数据的新鲜度取决于谁来调这个写入口"),
            new AdCampaignCapabilityDimension(
                "platform_writeback",
                "暂停 / 启用 / 调预算回写平台（队列 + 重试）",
                false,
                "—",
                "平台适配层（channel/vendor）",
                "本模块的改动只落本地并标记 pending_platform_writeback，界面上会显式说明「还没回写平台」"),
            new AdCampaignCapabilityDimension(
                "campaign_detail",
                "单活动全景（预算与消耗曲线、广告组、投放商品、关键词、创意、平台侧修改历史）",
                false,
                "—",
                "14.1.1.2 活动详情",
                "本模块只给列表与当前状态；单活动全景要等 14.1.1.2"),
            new AdCampaignCapabilityDimension(
                "budget_allocation",
                "跨活动预算分配",
                false,
                "—",
                "14.2.2.1 预算分配",
                "本模块能给出各活动当前预算与消耗，供预算分配模块消费"),
            new AdCampaignCapabilityDimension(
                "auto_budget_rules",
                "自动调预算 / 竞价规则引擎",
                false,
                "—",
                "14.2.2.2 自动规则",
                "规则引擎可调用本模块的「按外部 id 取活动当前状态与预算/消耗」读取形状，本模块不实现规则"),
            new AdCampaignCapabilityDimension(
                "ad_group_and_keyword",
                "广告组 / 关键词 / 投放定位",
                false,
                "—",
                "14.1.2.1 / 14.1.3.1 / 14.1.3.2",
                "本模块的活动只到活动级；广告组与词的明细归那些模块")),
        STATUSES,
        List.copyOf(SORTS.keySet()),
        List.of("PAUSE", "ENABLE", "SET_BUDGET", "ADJUST_BUDGET"),
        List.of("NO_SYNCED_DATA", "NO_CAMPAIGNS", "FILTERED_EMPTY"),
        guardrails(),
        METRIC_DEFINITION_NOTE,
        "「数据截至 X 时间」= 各次**成功/部分成功**的同步运行里最大的 data_as_of；"
            + "失败的同步**不推进水位**（失败就是没拿到数据），并把失败原因一起显示 —— "
            + "只给时间不给原因等于让人猜。",
        "🔴 照抄 14.1.2.2 的加粗句：归一类型与**原始类型标记必须并存**。"
            + "本模块的 platform_ad_type（平台原文）与 internal_ad_type（归一后）由 DB 触发器从档案复制，"
            + "服务层写什么都不作数。UNMAPPED 是**显式合法值**：这类活动照样在列表里、"
            + "照样带完整原始标记，只是不参与按内部类型的横向比较 —— "
            + "「没有可比口径」与「没有这种广告」是相反的两件事。",
        "上游 = 平台广告 API 同步（**未交付**，本模块自带真实写入口代替）与 14.1.2.2 广告类型差异"
            + "（已交付，ad_type_profile）；平台目录（2.1.1.1）与店铺（2.3.1.1）同库只读引用。",
        "下游 = 14.1.1.2 活动详情、14.2.2.1 预算分配（均未交付）；"
            + "14.2.2.2 自动规则可调用本模块的按外部 id 读取形状（by-external）。"
            + "🔴 本模块**不做**「按筛选结果全选」「变更预览」「异步批量任务」—— 那是 14.1.1.3 的职责。");
  }

  private static AdBudgetGuardrails guardrails() {
    return new AdBudgetGuardrails(
        MAX_SINGLE_BUDGET_DELTA,
        MAX_TOTAL_BUDGET_DELTA,
        MAX_CAMPAIGN_BUDGET,
        MAX_CAMPAIGNS_PER_REQUEST,
        "🔴 单次上限与总额上限一起给：单次上限挡住「把一个活动改成天价」，"
            + "总额上限挡住「一次误操作烧掉整月预算」。"
            + "超限的那几条会被**逐条拒绝**并写进审计（REJECTED 也要留痕，否则查不出为什么没生效）。");
  }

  // ==================================================================
  // 选项（筛选项下拉）
  // ==================================================================

  public AdCampaignOptions options() {
    List<AdCampaignOption> platforms =
        sql.fetch("select id, code, name from platform order by position, code")
            .stream()
            .map(r -> new AdCampaignOption(r.get("code", String.class), r.get("name", String.class)))
            .toList();

    List<AdCampaignOption> shops =
        sql.fetch(
                "select code, name from shop where tenant_id = "
                    + TENANT
                    + " order by code")
            .stream()
            .map(r -> new AdCampaignOption(r.get("code", String.class), r.get("name", String.class)))
            .toList();

    List<AdCampaignOption> statuses =
        STATUSES.stream()
            .map(s -> new AdCampaignOption(s, statusLabel(s)))
            .toList();

    List<AdCampaignOption> types =
        INTERNAL_AD_TYPES.stream()
            .map(t -> new AdCampaignOption(t, internalTypeLabel(t)))
            .toList();

    List<AdCampaignOption> sorts =
        List.of(
            new AdCampaignOption("SPEND_DESC", "消耗高到低"),
            new AdCampaignOption("SPEND_ASC", "消耗低到高"),
            new AdCampaignOption("SALES_DESC", "销售额高到低"),
            new AdCampaignOption("ACOS_ASC", "ACOS 低到高（不可比的排最后）"),
            new AdCampaignOption("ROAS_DESC", "ROAS 高到低（不可比的排最后）"),
            new AdCampaignOption("IMPRESSIONS_DESC", "曝光高到低"),
            new AdCampaignOption("CLICKS_DESC", "点击高到低"),
            new AdCampaignOption("ORDERS_DESC", "订单高到低"),
            new AdCampaignOption("BUDGET_DESC", "预算高到低"),
            new AdCampaignOption("NAME_ASC", "活动名"),
            new AdCampaignOption("RECENT", "数据最新"));

    return new AdCampaignOptions(
        platforms, shops, statuses, types, sorts, guardrails(), METRIC_DEFINITION_NOTE);
  }

  // ==================================================================
  // 列表
  // ==================================================================

  /** 一条活动的筛选条件。 */
  private record Filters(String where, List<Object> binds) {}

  /**
   * 构造活动筛选条件。
   *
   * <p>🔴 日期筛选的语义：它**既是统计区间，也是可见性条件** ——
   * 给了日期区间时，只保留「在该区间内有每日指标」的活动。
   * 否则运营会看到一堆「这个区间完全没消耗」的活动，误以为系统坏了。
   */
  private Filters buildFilters(
      UUID platformId,
      String shopCode,
      String status,
      String internalAdType,
      String platformAdType,
      String keyword,
      LocalDate from,
      LocalDate to) {
    StringBuilder where = new StringBuilder(" where c.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();

    if (platformId != null) {
      where.append(" and c.platform_id = ?");
      binds.add(platformId);
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and c.shop_code = ?");
      binds.add(shop);
    }
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and c.status = ?");
      binds.add(state);
    }
    String internal = blankToNull(internalAdType);
    if (internal != null) {
      where.append(" and c.internal_ad_type = ?");
      binds.add(internal);
    }
    String rawType = blankToNull(platformAdType);
    if (rawType != null) {
      where.append(" and c.platform_ad_type = ?");
      binds.add(rawType);
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (c.campaign_name ilike ? or c.external_campaign_id ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
    }
    if (from != null || to != null) {
      where.append(" and exists (select 1 from ad_campaign_daily_metric dm"
          + " where dm.tenant_id = c.tenant_id and dm.campaign_id = c.id");
      if (from != null) {
        where.append(" and dm.metric_date >= ?::date");
        binds.add(from);
      }
      if (to != null) {
        where.append(" and dm.metric_date <= ?::date");
        binds.add(to);
      }
      where.append(")");
    }
    return new Filters(where.toString(), binds);
  }

  /** LIKE 里的通配符要转义，否则用户搜 "50%" 会匹配到一切。 */
  private static String escapeLike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /** 每日指标聚合子查询（按租户 + 统计区间）。 */
  private static String metricAggregate(LocalDate from, LocalDate to) {
    StringBuilder sb = new StringBuilder("select campaign_id,"
        + " sum(impressions) impressions, sum(clicks) clicks, sum(orders) orders,"
        + " sum(spend) spend, sum(sales) sales,"
        + " min(metric_date) metric_from, max(metric_date) metric_to"
        + " from ad_campaign_daily_metric"
        + " where tenant_id = " + TENANT);
    if (from != null) {
      sb.append(" and metric_date >= ?::date");
    }
    if (to != null) {
      sb.append(" and metric_date <= ?::date");
    }
    sb.append(" group by campaign_id");
    return sb.toString();
  }

  private static List<Object> metricBinds(LocalDate from, LocalDate to) {
    List<Object> binds = new ArrayList<>();
    if (from != null) {
      binds.add(from);
    }
    if (to != null) {
      binds.add(to);
    }
    return binds;
  }

  private static final String CAMPAIGN_COLUMNS =
      """
       select c.id, c.platform_id, pf.code platform_code, pf.name platform_name,
              c.shop_code, s.name shop_name, c.external_campaign_id, c.campaign_name,
              c.platform_ad_type, c.internal_ad_type, c.ad_type_mapping_source,
              c.status, c.currency, c.daily_budget,
              coalesce(m.impressions,0) impressions, coalesce(m.clicks,0) clicks,
              coalesce(m.orders,0) orders, coalesce(m.spend,0) spend, coalesce(m.sales,0) sales,
              case when coalesce(m.impressions,0) > 0
                   then round(coalesce(m.clicks,0) / m.impressions, 6) end ctr,
              case when coalesce(m.sales,0) > 0
                   then round(coalesce(m.spend,0) / m.sales, 6) end acos,
              case when coalesce(m.spend,0) > 0
                   then round(coalesce(m.sales,0) / m.spend, 6) end roas,
              c.start_at, c.end_at, c.pending_platform_writeback, c.local_changed_at,
              c.data_as_of, c.last_synced_at, c.note, c.version, c.created_at, c.updated_at
         from ad_campaign c
         join platform pf on pf.id = c.platform_id
         join shop s on s.tenant_id = c.tenant_id and s.code = c.shop_code
         left join (
      """;

  private AdCampaignRow campaignRow(Record r) {
    return new AdCampaignRow(
        r.get("id", UUID.class),
        r.get("platform_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("platform_name", String.class),
        r.get("shop_code", String.class),
        r.get("shop_name", String.class),
        r.get("external_campaign_id", String.class),
        r.get("campaign_name", String.class),
        r.get("platform_ad_type", String.class),
        r.get("internal_ad_type", String.class),
        r.get("ad_type_mapping_source", String.class),
        // 🔴 未映射 = **没有可比口径**，不是「没有这种广告」：这类活动照样在列表里，
        //   只是不参与按内部类型的横向比较。
        !"UNMAPPED".equals(r.get("internal_ad_type", String.class)),
        r.get("status", String.class),
        r.get("currency", String.class),
        r.get("daily_budget", BigDecimal.class),
        r.get("impressions", BigDecimal.class),
        r.get("clicks", BigDecimal.class),
        r.get("orders", BigDecimal.class),
        r.get("spend", BigDecimal.class),
        r.get("sales", BigDecimal.class),
        r.get("ctr", BigDecimal.class),
        r.get("acos", BigDecimal.class),
        r.get("roas", BigDecimal.class),
        r.get("start_at", OffsetDateTime.class),
        r.get("end_at", OffsetDateTime.class),
        r.get("pending_platform_writeback", Boolean.class),
        r.get("local_changed_at", OffsetDateTime.class),
        r.get("data_as_of", OffsetDateTime.class),
        r.get("last_synced_at", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 活动列表：真分页 + 真筛选 + 真排序 + 汇总 + 「数据截至 X 时间」。 */
  public AdCampaignPage campaigns(
      UUID platformId,
      String shopCode,
      String status,
      String internalAdType,
      String platformAdType,
      String keyword,
      LocalDate from,
      LocalDate to,
      String sort,
      int page,
      int size) {

    Filters filters =
        buildFilters(platformId, shopCode, status, internalAdType, platformAdType, keyword, from, to);
    List<Object> windowBinds = metricBinds(from, to);

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    String orderBy = SORTS.getOrDefault(text(sort).toUpperCase(Locale.ROOT), SORTS.get(DEFAULT_SORT));

    // ---- 列表本体 ----
    String sqlText =
        CAMPAIGN_COLUMNS
            + metricAggregate(from, to)
            + " ) m on m.campaign_id = c.id"
            + filters.where()
            + " "
            + orderBy
            + " limit ? offset ?";

    List<Object> binds = new ArrayList<>(windowBinds);
    binds.addAll(filters.binds());
    binds.add(safeSize);
    binds.add(safePage * safeSize);

    List<AdCampaignRow> items =
        sql.fetch(sqlText, binds.toArray()).stream().map(this::campaignRow).toList();

    // ---- 总条数 ----
    long total =
        count(
            "select count(*) from ad_campaign c"
                + filters.where(),
            filters.binds().toArray());

    // ---- 汇总（筛选后的全集，不受分页影响）----
    String summarySql =
        """
        select count(*) campaign_count,
               count(*) filter (where c.status = 'ENABLED') enabled_count,
               count(*) filter (where c.status = 'PAUSED') paused_count,
               count(*) filter (where c.status not in ('ENABLED','PAUSED')) other_status_count,
               coalesce(sum(c.daily_budget),0) total_budget,
               coalesce(sum(coalesce(m.impressions,0)),0) total_impressions,
               coalesce(sum(coalesce(m.clicks,0)),0) total_clicks,
               coalesce(sum(coalesce(m.orders,0)),0) total_orders,
               coalesce(sum(coalesce(m.spend,0)),0) total_spend,
               coalesce(sum(coalesce(m.sales,0)),0) total_sales,
               case when sum(coalesce(m.impressions,0)) > 0
                    then round(sum(coalesce(m.clicks,0)) / sum(coalesce(m.impressions,0)), 6) end ctr,
               case when sum(coalesce(m.sales,0)) > 0
                    then round(sum(coalesce(m.spend,0)) / sum(coalesce(m.sales,0)), 6) end acos,
               case when sum(coalesce(m.spend,0)) > 0
                    then round(sum(coalesce(m.sales,0)) / sum(coalesce(m.spend,0)), 6) end roas,
               min(m.metric_from) metric_from, max(m.metric_to) metric_to
          from ad_campaign c
          left join (
        """
            + metricAggregate(from, to)
            + " ) m on m.campaign_id = c.id"
            + filters.where();

    List<Object> summaryBinds = new ArrayList<>(windowBinds);
    summaryBinds.addAll(filters.binds());
    Record summary = sql.fetchOne(summarySql, summaryBinds.toArray());

    AdCampaignSummary sum =
        new AdCampaignSummary(
            summary.get("campaign_count", Long.class),
            summary.get("enabled_count", Long.class),
            summary.get("paused_count", Long.class),
            summary.get("other_status_count", Long.class),
            summary.get("total_budget", BigDecimal.class),
            summary.get("total_impressions", BigDecimal.class),
            summary.get("total_clicks", BigDecimal.class),
            summary.get("total_orders", BigDecimal.class),
            summary.get("total_spend", BigDecimal.class),
            summary.get("total_sales", BigDecimal.class),
            summary.get("ctr", BigDecimal.class),
            summary.get("acos", BigDecimal.class),
            summary.get("roas", BigDecimal.class),
            summary.get("metric_from", LocalDate.class),
            summary.get("metric_to", LocalDate.class),
            METRIC_DEFINITION_NOTE);

    // ---- 数据截至 X 时间 + 逐范围同步状态 ----
    List<AdSyncState> syncStates = syncStates(platformId, shopCode);
    OffsetDateTime dataAsOf = syncStates.stream()
        .map(AdSyncState::dataAsOf)
        .filter(java.util.Objects::nonNull)
        .max(OffsetDateTime::compareTo)
        .orElse(null);

    // ---- 空态：把「没投放」与「还没同步到数据」分开 ----
    String emptyReason = null;
    String emptyNote = "";
    if (total == 0) {
      if (!hasSuccessfulSync()) {
        // 🔴 从来没成功同步过 ⇒ 只知道「还没同步到数据」，**不能**说「本租户没有投放」。
        emptyReason = "NO_SYNCED_DATA";
        emptyNote =
            "本租户还没有任何一次**成功的广告数据同步**。"
                + "这不等于「没有投放」—— 只是还没同步到数据。";
      } else if (countInScope(platformId, shopCode) > 0) {
        // 🔴 范围内有活动，但被状态 / 类型 / 关键词 / 日期这些条件全部排除了。
        emptyReason = "FILTERED_EMPTY";
        emptyNote = "活动存在，但被当前的筛选条件全部排除了。放宽条件再试。";
      } else {
        // 🔴 同步成功过，而这个范围里确实一条活动都没有 —— 这次是真的「没有投放」。
        emptyReason = "NO_CAMPAIGNS";
        emptyNote = "同步成功过，但这个范围里确实一条广告活动都没有 —— 这次是真的「没有投放」。";
      }
    }

    return new AdCampaignPage(
        items,
        total,
        safePage,
        safeSize,
        sum,
        dataAsOf,
        "数据截至 X 时间 = 各次成功同步里最大的水位；失败的同步不推进水位，失败原因见下方同步状态。",
        syncStates,
        emptyReason,
        emptyNote,
        METRIC_DEFINITION_NOTE);
  }

  /** 本租户是否有过一次成功的同步（决定「没投放」还是「没同步」）。 */
  private boolean hasSuccessfulSync() {
    Long n =
        one(
            Long.class,
            "select count(*) from ad_campaign_sync_run where tenant_id = "
                + TENANT
                + " and data_as_of is not null");
    return n != null && n > 0;
  }

  /**
   * 平台 / 店铺这个**范围内**的活动条数（不带状态、类型、关键词、日期这些条件）。
   *
   * <p>🔴 空态判定要用它而不是「本租户有没有活动」：范围里一条都没有 ⇒ 这个范围真的没投放；
   * 范围里有、只是被别的条件筛掉了 ⇒ FILTERED_EMPTY。两者不是一回事。
   */
  private long countInScope(UUID platformId, String shopCode) {
    StringBuilder sqlText = new StringBuilder("select count(*) from ad_campaign where tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    if (platformId != null) {
      sqlText.append(" and platform_id = ?");
      binds.add(platformId);
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      sqlText.append(" and shop_code = ?");
      binds.add(shop);
    }
    return count(sqlText.toString(), binds.toArray());
  }

  /** 逐平台 + 店铺的同步状态（每个范围取最近一次运行）。 */
  private List<AdSyncState> syncStates(UUID platformId, String shopCode) {
    StringBuilder sqlText =
        new StringBuilder(
            """
            select distinct on (r.platform_id, r.shop_code)
                   r.platform_id, pf.code platform_code, pf.name platform_name,
                   r.shop_code, s.name shop_name, r.status, r.data_as_of, r.created_at,
                   r.message, r.campaign_count,
                   (select count(*) from ad_campaign c
                     where c.tenant_id = r.tenant_id and c.platform_id = r.platform_id
                       and c.shop_code = r.shop_code) campaign_total
              from ad_campaign_sync_run r
              join platform pf on pf.id = r.platform_id
              join shop s on s.tenant_id = r.tenant_id and s.code = r.shop_code
             where r.tenant_id = %s
            """.formatted(TENANT));
    List<Object> binds = new ArrayList<>();
    if (platformId != null) {
      sqlText.append(" and r.platform_id = ?");
      binds.add(platformId);
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      sqlText.append(" and r.shop_code = ?");
      binds.add(shop);
    }
    sqlText.append(" order by r.platform_id, r.shop_code, r.created_at desc, r.id desc");

    return sql.fetch(sqlText.toString(), binds.toArray()).stream()
        .map(
            r ->
                new AdSyncState(
                    r.get("platform_id", UUID.class),
                    r.get("platform_code", String.class),
                    r.get("platform_name", String.class),
                    r.get("shop_code", String.class),
                    r.get("shop_name", String.class),
                    r.get("status", String.class),
                    r.get("data_as_of", OffsetDateTime.class),
                    r.get("created_at", OffsetDateTime.class),
                    r.get("campaign_total", Long.class),
                    r.get("message", String.class)))
        .toList();
  }

  // ==================================================================
  // 详情与明细
  // ==================================================================

  public AdCampaignDetail campaign(String id) {
    UUID campaignId = parseId(id);
    Record row =
        sql.fetchOne(
            CAMPAIGN_COLUMNS
                + metricAggregate(null, null)
                + " ) m on m.campaign_id = c.id"
                + " where c.tenant_id = "
                + TENANT
                + " and c.id = ?",
            campaignId);
    if (row == null) {
      throw notFound("广告活动");
    }
    AdCampaignRow campaign = campaignRow(row);

    List<AdCampaignDailyMetricRow> metrics =
        sql.fetch(
                "select metric_date, impressions, clicks, orders, spend, sales, ctr, acos, roas,"
                    + " synced_at from ad_campaign_daily_metric"
                    + " where tenant_id = "
                    + TENANT
                    + " and campaign_id = ? order by metric_date desc",
                campaignId)
            .stream()
            .map(
                r ->
                    new AdCampaignDailyMetricRow(
                        r.get("metric_date", LocalDate.class),
                        r.get("impressions", BigDecimal.class),
                        r.get("clicks", BigDecimal.class),
                        r.get("orders", BigDecimal.class),
                        r.get("spend", BigDecimal.class),
                        r.get("sales", BigDecimal.class),
                        r.get("ctr", BigDecimal.class),
                        r.get("acos", BigDecimal.class),
                        r.get("roas", BigDecimal.class),
                        r.get("synced_at", OffsetDateTime.class)))
            .toList();

    List<AdCampaignOperationLogRow> logs =
        sql.fetch(
                "select id, campaign_id, operation, before_status, after_status, before_budget,"
                    + " after_budget, requested_by, reason, result, message, created_at"
                    + " from ad_campaign_operation_log where tenant_id = "
                    + TENANT
                    + " and campaign_id = ? order by created_at desc, id desc limit 100",
                campaignId)
            .stream()
            .map(
                r ->
                    new AdCampaignOperationLogRow(
                        r.get("id", UUID.class),
                        r.get("campaign_id", UUID.class),
                        r.get("operation", String.class),
                        r.get("before_status", String.class),
                        r.get("after_status", String.class),
                        r.get("before_budget", BigDecimal.class),
                        r.get("after_budget", BigDecimal.class),
                        r.get("requested_by", UUID.class),
                        r.get("reason", String.class),
                        r.get("result", String.class),
                        r.get("message", String.class),
                        r.get("created_at", OffsetDateTime.class)))
            .toList();

    return new AdCampaignDetail(
        campaign,
        metrics,
        logs,
        "每日明细按平台当地日切分；ACOS = 消耗 / 销售额、ROAS = 销售额 / 消耗，"
            + "分母为 0 时显示「不可比」而不是 0。");
  }

  /**
   * 按平台侧活动 id 取活动的当前状态与预算 / 消耗。
   *
   * <p>🔴 这是给 14.2.2.2 自动规则（与 14.2.2.1 预算分配）用的读取形状：
   * 规则引擎要判断「这条活动现在什么状态、预算多少、花了多少」才能决定要不要调。
   * 形状刻意做窄：**只读、只给当前值**，不改任何东西。
   */
  public AdCampaignExternalView externalView(UUID platformId, String shopCode, String externalId) {
    if (platformId == null || blankToNull(shopCode) == null || blankToNull(externalId) == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "平台、店铺与平台侧活动 id 都不能为空");
    }
    Record row =
        sql.fetchOne(
            """
            select c.id, c.external_campaign_id, c.campaign_name, pf.code platform_code,
                   c.shop_code, c.platform_ad_type, c.internal_ad_type, c.status, c.currency,
                   c.daily_budget, c.data_as_of, c.pending_platform_writeback, c.note,
                   coalesce(m.spend,0) spend, coalesce(m.sales,0) sales, coalesce(m.orders,0) orders
              from ad_campaign c
              join platform pf on pf.id = c.platform_id
              left join (
            """
                + metricAggregate(null, null)
                + " ) m on m.campaign_id = c.id"
                + " where c.tenant_id = "
                + TENANT
                + " and c.platform_id = ? and c.shop_code = ? and c.external_campaign_id = ?",
            platformId,
            shopCode.trim(),
            externalId.trim());
    if (row == null) {
      throw notFound("广告活动");
    }
    BigDecimal spend = row.get("spend", BigDecimal.class);
    BigDecimal sales = row.get("sales", BigDecimal.class);
    return new AdCampaignExternalView(
        row.get("id", UUID.class),
        row.get("external_campaign_id", String.class),
        row.get("campaign_name", String.class),
        row.get("platform_code", String.class),
        row.get("shop_code", String.class),
        row.get("platform_ad_type", String.class),
        row.get("internal_ad_type", String.class),
        row.get("status", String.class),
        row.get("currency", String.class),
        row.get("daily_budget", BigDecimal.class),
        spend,
        sales,
        row.get("orders", BigDecimal.class),
        sales.signum() > 0 ? spend.divide(sales, 6, java.math.RoundingMode.HALF_UP) : null,
        spend.signum() > 0 ? sales.divide(spend, 6, java.math.RoundingMode.HALF_UP) : null,
        row.get("data_as_of", OffsetDateTime.class),
        row.get("pending_platform_writeback", Boolean.class),
        row.get("note", String.class));
  }

  // ==================================================================
  // 真实写入口：登记同步结果
  // ==================================================================

  /**
   * 登记一次广告数据同步的结果（活动 + 每日指标 + 同步运行）。
   *
   * <p>🔴 这是本模块的**真实写入口**：平台广告 API 同步器尚未交付，但「广告数据同步是本系统的地基」
   * 意味着活动与表现数据必须真的有落点 —— 绝不能用空数组或硬编码常量假装有投放。
   *
   * <p>🔴 {@code syncRun.status = FAILED} 时**只登记失败，不写任何活动数据**：
   * 失败的同步意味着没拿到数据，写进去就是脏数据；而且失败**不推进水位**
   * （{@code data_as_of} 为空），页面上「数据截至 X 时间」因此不会虚高。
   */
  public ImportResult importCampaigns(ImportRequest request) {
    String status = text(request.syncRun().status()).toUpperCase(Locale.ROOT);
    if (!List.of("SUCCESS", "PARTIAL", "FAILED").contains(status)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "同步状态只能是 SUCCESS / PARTIAL / FAILED");
    }
    String shop = text(request.shopCode());
    if (shop.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "店铺编码不能为空");
    }
    String message = text(request.syncRun().message());

    if ("FAILED".equals(status)) {
      if (!message.isEmpty() && request.syncRun().dataAsOf() != null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "失败的同步不能带数据截至时刻：失败就是没拿到数据，水位不许前进");
      }
      if (message.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "失败的同步必须写明失败原因");
      }
    } else if (request.syncRun().dataAsOf() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "成功的同步必须给出数据截至时刻（水位就是它）");
    }

    // 平台与店铺必须真实存在（外键会兜底，但这里给出可读的错误）
    String platformCode =
        one(String.class, "select code from platform where id = ?", request.platformId());
    if (platformCode == null) {
      throw notFound("平台");
    }
    String shopName =
        one(
            String.class,
            "select name from shop where tenant_id = " + TENANT + " and code = ?",
            shop);
    if (shopName == null) {
      throw notFound("店铺");
    }

    UUID syncRunId = Ids.next();
    OffsetDateTime startedAt = request.syncRun().startedAt();
    OffsetDateTime finishedAt = request.syncRun().finishedAt();
    OffsetDateTime dataAsOf = request.syncRun().dataAsOf();
    OffsetDateTime lastSyncedAt = finishedAt == null ? startedAt : finishedAt;

    // ---- 失败的同步：只留失败记录 ----
    if ("FAILED".equals(status)) {
      sql.execute(
          "insert into ad_campaign_sync_run (tenant_id,id,platform_id,shop_code,status,data_as_of,"
              + " started_at,finished_at,campaign_count,metric_row_count,message,source_ref,created_at)"
              + " values (?,?,?,?,?,?,?::timestamptz,?::timestamptz,0,0,?,?,now())",
          currentTenant(),
          syncRunId,
          request.platformId(),
          shop,
          status,
          null,
          startedAt,
          finishedAt,
          message,
          text(request.syncRun().sourceRef()));
      return new ImportResult(
          syncRunId.toString(),
          status,
          null,
          0,
          0,
          0,
          0,
          List.of(),
          "同步失败：本次**没有写入任何活动数据**，数据截至时间保持不变 —— "
              + "失败原因是：" + message);
    }

    // ---- 成功的同步：写活动与每日指标 ----
    int created = 0;
    int updated = 0;
    int metricRows = 0;
    List<AdCampaignRow> campaigns = new ArrayList<>();

    for (ImportCampaign input : request.campaigns()) {
      String externalId = text(input.externalCampaignId());
      if (externalId.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "平台侧活动 id 不能为空");
      }
      if (!STATUSES.contains(text(input.status()).toUpperCase(Locale.ROOT))) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "活动状态不合法：" + input.status());
      }
      if (input.dailyBudget() != null && input.dailyBudget().signum() < 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日预算不能为负");
      }
      // 广告类型档案必须属于本租户（外键兜底，这里给出可读的错误）
      String profileType =
          one(
              String.class,
              "select platform_ad_type from ad_type_profile where tenant_id = "
                  + TENANT
                  + " and id = ?",
              input.adTypeProfileId());
      if (profileType == null) {
        throw notFound("广告类型档案");
      }

      UUID campaignId = Ids.next();
      Record inserted =
          sql.fetchOne(
              """
              insert into ad_campaign (tenant_id,id,platform_id,shop_code,ad_type_profile_id,
                                       external_campaign_id,campaign_name,status,currency,
                                       daily_budget,start_at,end_at,data_as_of,last_synced_at,
                                       note,created_at,updated_at)
              values (?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz,?,now(),now())
              on conflict (tenant_id,platform_id,shop_code,external_campaign_id) do update
                 set ad_type_profile_id = excluded.ad_type_profile_id,
                     campaign_name = excluded.campaign_name,
                     -- 🔴 本地有未回写的改动时，平台回读**不覆盖**这些字段：
                     --   平台回写队列未交付，本地改动还挂着，静默覆盖等于把用户的改动抹掉。
                     status = case when ad_campaign.pending_platform_writeback
                                   then ad_campaign.status else excluded.status end,
                     currency = excluded.currency,
                     daily_budget = case when ad_campaign.pending_platform_writeback
                                         then ad_campaign.daily_budget else excluded.daily_budget end,
                     start_at = excluded.start_at,
                     end_at = excluded.end_at,
                     data_as_of = excluded.data_as_of,
                     last_synced_at = excluded.last_synced_at,
                     note = excluded.note,
                     version = ad_campaign.version + 1,
                     updated_at = now()
              returning id, (xmax = 0) as fresh
              """,
              currentTenant(),
              campaignId,
              request.platformId(),
              shop,
              input.adTypeProfileId(),
              externalId,
              text(input.campaignName()),
              text(input.status()).toUpperCase(Locale.ROOT),
              text(input.currency()),
              input.dailyBudget(),
              input.startAt(),
              input.endAt(),
              dataAsOf,
              lastSyncedAt,
              text(input.note()));
      UUID actualId = inserted.get("id", UUID.class);
      if (Boolean.TRUE.equals(inserted.get("fresh", Boolean.class))) {
        created++;
      } else {
        updated++;
      }

      // 每日指标
      if (input.metrics() != null) {
        for (ImportDailyMetric metric : input.metrics()) {
          if (metric.metricDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每日指标的日期不能为空");
          }
          sql.execute(
              "insert into ad_campaign_daily_metric (tenant_id,id,campaign_id,metric_date,"
                  + " impressions,clicks,orders,spend,sales,synced_at)"
                  + " values (?,?,?,?::date,?,?,?,?,?,?::timestamptz)"
                  + " on conflict (tenant_id,campaign_id,metric_date) do update"
                  + " set impressions=excluded.impressions, clicks=excluded.clicks,"
                  + " orders=excluded.orders, spend=excluded.spend, sales=excluded.sales,"
                  + " synced_at=excluded.synced_at",
              currentTenant(),
              Ids.next(),
              actualId,
              metric.metricDate(),
              nz(metric.impressions()),
              nz(metric.clicks()),
              nz(metric.orders()),
              nz(metric.spend()),
              nz(metric.sales()),
              lastSyncedAt);
          metricRows++;
        }
      }

      campaigns.add(campaign(actualId.toString()).campaign());
    }

    sql.execute(
        "insert into ad_campaign_sync_run (tenant_id,id,platform_id,shop_code,status,data_as_of,"
            + " started_at,finished_at,campaign_count,metric_row_count,message,source_ref,created_at)"
            + " values (?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz,?,?,?,?,now())",
        currentTenant(),
        syncRunId,
        request.platformId(),
        shop,
        status,
        dataAsOf,
        startedAt,
        finishedAt,
        request.campaigns().size(),
        metricRows,
        message,
        text(request.syncRun().sourceRef()));

    return new ImportResult(
        syncRunId.toString(),
        status,
        dataAsOf,
        request.campaigns().size(),
        metricRows,
        created,
        updated,
        campaigns,
        "已登记 " + request.campaigns().size() + " 条活动、" + metricRows + " 行每日指标；"
            + "数据截至 " + dataAsOf + "。本地有未回写改动的活动，其状态与预算未被平台回读覆盖。");
  }

  // ==================================================================
  // 批量操作：暂停 / 启用 / 调预算
  // ==================================================================

  /**
   * 对**显式选中的一批活动 id** 批量暂停 / 启用 / 调预算，逐条返回执行结果。
   *
   * <p>🔴 三条边界，缺一不可：
   *
   * <ol>
   *   <li><b>只认显式选中的 id</b>：「按筛选结果全选 + 变更预览 + 异步任务」是 14.1.1.3 的职责，
   *       本模块不做，也不假装做了。
   *   <li><b>护栏</b>：单次调整上限 + 总额调整上限 + 单活动预算上限 + 单次条数上限。
   *       超限的那几条**逐条拒绝**并写进审计。
   *   <li><b>逐条结果</b>：一条失败不影响其余条目 —— 批量操作的「批量」指的是一次提交多条，
   *       不是「全成功或全失败」。
   * </ol>
   */
  public BulkOperationResult bulkOperation(BulkOperationRequest request) {
    String operation = text(request.operation()).toUpperCase(Locale.ROOT);
    if (!List.of("PAUSE", "ENABLE", "SET_BUDGET", "ADJUST_BUDGET").contains(operation)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "操作类型只能是 PAUSE / ENABLE / SET_BUDGET / ADJUST_BUDGET");
    }
    if (request.campaignIds().size() > MAX_CAMPAIGNS_PER_REQUEST) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "一次最多批量操作 " + MAX_CAMPAIGNS_PER_REQUEST + " 条活动，本次提交了 "
              + request.campaignIds().size() + " 条");
    }
    if ("SET_BUDGET".equals(operation)) {
      if (request.budget() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "调预算必须给出目标预算");
      }
      if (request.budget().signum() < 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标预算不能为负");
      }
      if (request.budget().compareTo(MAX_CAMPAIGN_BUDGET) > 0) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "目标预算超过单活动预算上限 " + MAX_CAMPAIGN_BUDGET);
      }
    }
    if ("ADJUST_BUDGET".equals(operation)) {
      if (request.delta() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "调整预算必须给出增减额");
      }
      if (request.delta().abs().compareTo(MAX_SINGLE_BUDGET_DELTA) > 0) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "单次调整额超过单活动上限 " + MAX_SINGLE_BUDGET_DELTA);
      }
    }

    String reason = text(request.reason());
    UUID actor = currentActor();
    OffsetDateTime now = OffsetDateTime.now();
    BigDecimal appliedTotal = BigDecimal.ZERO;

    List<BulkOperationItem> items = new ArrayList<>();
    int applied = 0;
    int rejected = 0;
    int skipped = 0;

    for (UUID campaignId : request.campaignIds()) {
      Record row =
          sql.fetchOne(
              "select id, campaign_name, status, daily_budget from ad_campaign"
                  + " where tenant_id = "
                  + TENANT
                  + " and id = ?",
              campaignId);
      if (row == null) {
        rejected++;
        items.add(
            new BulkOperationItem(
                campaignId,
                "",
                "REJECTED",
                "",
                "",
                null,
                null,
                "活动不存在或不属于当前租户"));
        continue;
      }

      String name = row.get("campaign_name", String.class);
      String beforeStatus = row.get("status", String.class);
      BigDecimal beforeBudget = row.get("daily_budget", BigDecimal.class);

      String targetStatus = beforeStatus;
      BigDecimal targetBudget = beforeBudget;
      String rejection = null;
      String skip = null;
      BigDecimal delta = BigDecimal.ZERO;

      switch (operation) {
        case "PAUSE" -> {
          if ("PAUSED".equals(beforeStatus)) {
            skip = "已处于暂停状态";
          } else if ("ENDED".equals(beforeStatus) || "ARCHIVED".equals(beforeStatus)) {
            rejection = "已结束或已归档的活动不能暂停";
          } else {
            targetStatus = "PAUSED";
          }
        }
        case "ENABLE" -> {
          if ("ENABLED".equals(beforeStatus)) {
            skip = "已处于投放中状态";
          } else if ("ENDED".equals(beforeStatus) || "ARCHIVED".equals(beforeStatus)) {
            rejection = "已结束或已归档的活动不能启用";
          } else {
            targetStatus = "ENABLED";
          }
        }
        case "SET_BUDGET" -> {
          BigDecimal target = request.budget();
          delta = target.subtract(nz(beforeBudget)).abs();
          if (target.compareTo(MAX_CAMPAIGN_BUDGET) > 0) {
            rejection = "目标预算超过单活动预算上限 " + MAX_CAMPAIGN_BUDGET;
          } else if (delta.compareTo(MAX_SINGLE_BUDGET_DELTA) > 0) {
            rejection =
                "单个活动一次预算调整超过上限 "
                    + MAX_SINGLE_BUDGET_DELTA
                    + "（本次调整 "
                    + delta
                    + "）";
          } else if (appliedTotal.add(delta).compareTo(MAX_TOTAL_BUDGET_DELTA) > 0) {
            rejection =
                "本次批量总预算调整上限 "
                    + MAX_TOTAL_BUDGET_DELTA
                    + " 已用尽（已用 "
                    + appliedTotal
                    + "）";
          } else {
            targetBudget = target;
          }
        }
        case "ADJUST_BUDGET" -> {
          BigDecimal current = nz(beforeBudget);
          BigDecimal target = current.add(request.delta());
          delta = request.delta().abs();
          if (target.signum() < 0) {
            rejection = "调整后预算不能为负";
          } else if (target.compareTo(MAX_CAMPAIGN_BUDGET) > 0) {
            rejection = "调整后预算超过单活动预算上限 " + MAX_CAMPAIGN_BUDGET;
          } else if (delta.compareTo(MAX_SINGLE_BUDGET_DELTA) > 0) {
            rejection =
                "单个活动一次预算调整超过上限 " + MAX_SINGLE_BUDGET_DELTA + "（本次调整 " + delta + "）";
          } else if (appliedTotal.add(delta).compareTo(MAX_TOTAL_BUDGET_DELTA) > 0) {
            rejection =
                "本次批量总预算调整上限 "
                    + MAX_TOTAL_BUDGET_DELTA
                    + " 已用尽（已用 "
                    + appliedTotal
                    + "）";
          } else {
            targetBudget = target;
          }
        }
        default -> throw new IllegalStateException("unreachable operation " + operation);
      }

      if (rejection != null) {
        rejected++;
        logOperation(campaignId, operation, beforeStatus, beforeStatus, beforeBudget, beforeBudget,
            actor, reason, "REJECTED", rejection);
        items.add(
            new BulkOperationItem(
                campaignId, name, "REJECTED", beforeStatus, beforeStatus, beforeBudget, beforeBudget,
                rejection));
        continue;
      }
      if (skip != null) {
        skipped++;
        logOperation(campaignId, operation, beforeStatus, beforeStatus, beforeBudget, beforeBudget,
            actor, reason, "SKIPPED", skip);
        items.add(
            new BulkOperationItem(
                campaignId, name, "SKIPPED", beforeStatus, beforeStatus, beforeBudget, beforeBudget,
                skip));
        continue;
      }

      sql.execute(
          "update ad_campaign set status = ?, daily_budget = ?,"
              + " pending_platform_writeback = true, local_changed_at = ?::timestamptz,"
              + " version = version + 1, updated_at = now()"
              + " where tenant_id = "
              + TENANT
              + " and id = ?",
          targetStatus,
          targetBudget,
          now,
          campaignId);
      logOperation(campaignId, operation, beforeStatus, targetStatus, beforeBudget, targetBudget,
          actor, reason, "APPLIED", "");
      appliedTotal = appliedTotal.add(delta);
      applied++;
      items.add(
          new BulkOperationItem(
              campaignId,
              name,
              "APPLIED",
              beforeStatus,
              targetStatus,
              beforeBudget,
              targetBudget,
              "已生效（本地），尚未回写平台"));
    }

    return new BulkOperationResult(
        request.campaignIds().size(),
        applied,
        rejected,
        skipped,
        guardrails(),
        items,
        "🔴 本次改动只落本地并标记「待回写平台」：平台回写队列尚未交付（平台适配层），"
            + "界面上会显式标出，避免把「本地已暂停」当成「平台侧已暂停」。"
            + "「按筛选结果全选」「变更预览」「异步批量任务」属于 14.1.1.3，本模块未交付。");
  }

  private void logOperation(
      UUID campaignId,
      String operation,
      String beforeStatus,
      String afterStatus,
      BigDecimal beforeBudget,
      BigDecimal afterBudget,
      UUID actor,
      String reason,
      String result,
      String message) {
    sql.execute(
        "insert into ad_campaign_operation_log (tenant_id,id,campaign_id,operation,before_status,"
            + " after_status,before_budget,after_budget,requested_by,reason,result,message,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,now())",
        currentTenant(),
        Ids.next(),
        campaignId,
        operation,
        beforeStatus,
        afterStatus,
        beforeBudget,
        afterBudget,
        actor == null ? currentTenant() : actor,
        reason,
        result,
        message);
  }

  // ==================================================================
  // 同步运行历史
  // ==================================================================

  public AdSyncRunPage syncRuns(UUID platformId, String shopCode, int page, int size) {
    StringBuilder where =
        new StringBuilder(" where r.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    if (platformId != null) {
      where.append(" and r.platform_id = ?");
      binds.add(platformId);
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and r.shop_code = ?");
      binds.add(shop);
    }

    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);

    long total =
        count("select count(*) from ad_campaign_sync_run r" + where, binds.toArray());

    List<AdSyncRunRow> items =
        sql.fetch(
                "select r.id, r.platform_id, pf.code platform_code, r.shop_code, s.name shop_name,"
                    + " r.status, r.data_as_of, r.started_at, r.finished_at, r.campaign_count,"
                    + " r.metric_row_count, r.message, r.source_ref, r.created_at"
                    + " from ad_campaign_sync_run r"
                    + " join platform pf on pf.id = r.platform_id"
                    + " join shop s on s.tenant_id = r.tenant_id and s.code = r.shop_code"
                    + where
                    + " order by r.created_at desc, r.id desc limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(
                r ->
                    new AdSyncRunRow(
                        r.get("id", UUID.class),
                        r.get("platform_id", UUID.class),
                        r.get("platform_code", String.class),
                        r.get("shop_code", String.class),
                        r.get("shop_name", String.class),
                        r.get("status", String.class),
                        r.get("data_as_of", OffsetDateTime.class),
                        r.get("started_at", OffsetDateTime.class),
                        r.get("finished_at", OffsetDateTime.class),
                        r.get("campaign_count", Integer.class),
                        r.get("metric_row_count", Integer.class),
                        r.get("message", String.class),
                        r.get("source_ref", String.class),
                        r.get("created_at", OffsetDateTime.class)))
            .toList();

    return new AdSyncRunPage(items, total, safePage, safeSize);
  }

  // ==================================================================
  // 展示用的标签（服务端与前端口径一致，避免两边各写一套）
  // ==================================================================

  static String statusLabel(String status) {
    return switch (status) {
      case "ENABLED" -> "投放中";
      case "PAUSED" -> "已暂停";
      case "ENDED" -> "已结束";
      case "ARCHIVED" -> "已归档";
      case "PENDING_REVIEW" -> "审核中";
      default -> "状态未知";
    };
  }

  static String internalTypeLabel(String internalAdType) {
    return switch (internalAdType) {
      case "AUTO_DISCOVERY" -> "自动广告";
      case "SPONSORED_BRAND" -> "SB 品牌广告";
      case "SPONSORED_DISPLAY" -> "SD 展示型品牌广告";
      case "BRAND_AD" -> "品牌广告";
      case "DSP_PROGRAMMATIC" -> "DSP 程序化";
      case "PROMOTION_EVENT" -> "大促广告";
      case "UNMAPPED" -> "未映射（无归一口径）";
      default -> internalAdType;
    };
  }

  /** 供测试与其它模块复用的空态文案。 */
  static Map<String, String> emptyNotes() {
    Map<String, String> notes = new LinkedHashMap<>();
    notes.put(
        "NO_SYNCED_DATA",
        "还没有任何一次成功的广告数据同步 —— 这不是「没有投放」，是还没同步到数据。");
    notes.put("NO_CAMPAIGNS", "同步成功过，这个范围里确实一条广告活动都没有。");
    notes.put("FILTERED_EMPTY", "活动存在，但被当前的筛选条件全部排除了。");
    return notes;
  }
}
