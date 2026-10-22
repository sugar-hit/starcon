package com.starcon.channel;

import static com.starcon.channel.AdBulkOperationModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 14.1.1.3 批量操作。
 *
 * <p>蓝图：「跨活动批量调整：预算增减、竞价调整、状态切换、结束时间设置，支持按筛选结果全选并预览变更。
 * 输入：选中活动集合；调整参数；预览确认。输出：批量调整任务（异步）+ 每条执行结果。
 * 关系：上游 = 14.1.1.1；下游 = 平台广告 API（经 2 队列）。
 * <b>批量调价要有单次上限与总消耗上限保护</b>，误操作一次可能烧掉整月预算。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>按筛选结果全选</b> → {@link #selection(...)}：**服务端**按筛选条件解析出 id 集合
 *       （分页 + 游标 + 硬上限）。🔴 绝不让前端把 id 全拉回来再循环 —— 那等于全表扫。
 *   <li><b>预览变更</b> → {@link #preview(AdBulkOperationRequest)}：**不落库**的预演，
 *       给出逐条「会变成什么样」+ 总预算增量 + 护栏裁决。
 *   <li><b>批量调整任务（异步）</b> → {@link #createTask(...)}：落一张任务 + 一批任务项，
 *       交给 {@link AdBulkOperationExecutor} 在请求之外跑完。
 *   <li><b>每条执行结果</b> → {@link #task(String, int, int)}：逐条结果 + 计数。
 *   <li><b>状态切换 / 结束时间设置</b> → 同一套任务机制（{@code PAUSE} / {@code ENABLE} /
 *       {@code SET_END_AT}）。
 *   <li><b>竞价调整</b> → 落点是**广告组**（{@code AD_GROUP}），不是活动。
 * </ul>
 *
 * <p>🔴 三个最容易做丢的地方：
 *
 * <ol>
 *   <li><b>护栏越限 ⇒ 整批拒绝</b>，不是逐条拒绝、不是「跳过超限的那几条」。
 *       蓝图那句「误操作一次可能烧掉整月预算」要的就是这个 —— 半个批次比零个批次更难解释。
 *   <li><b>总额上限必须跨条目累加</b>：一条一条看每条都没越限，整批却已经烧掉整月预算 ——
 *       「+1000 与 −1000 净变化是 0，但动的是 2000 的钱」是这条上限存在的全部理由。
 *   <li><b>「已生效」= 本地已生效</b>。平台回写队列未交付 ⇒ 改动只落本地并标记
 *       {@code pending_platform_writeback}，界面上必须显式写「还没回写平台」。
 * </ol>
 *
 * <p>🔴 数据来源纪律：活动（V273）、广告组（V277）、店铺（V1）**同库同服务**只读引用，
 * 本类不复制它们的任何一列，也不碰 trade / catalog / finance 的表。
 */
@Service
public class AdBulkOperationService {

  // ==================================================================
  // 护栏：批量调预算 / 调竞价的上限（蓝图 14 章关键约束「必须有上限保护」）
  // ==================================================================

  /** 单个活动一次允许调整的预算上限（绝对值）。与 14.1.1.1 同一口径。 */
  static final BigDecimal MAX_SINGLE_BUDGET_DELTA = new BigDecimal("5000.0000");

  /** 一次批量允许的**总**预算调整上限（绝对值，跨条目累加）。与 14.1.1.1 同一口径。 */
  static final BigDecimal MAX_TOTAL_BUDGET_DELTA = new BigDecimal("20000.0000");

  /** 单个活动的预算上限（调整后的结果值不得超过）。与 14.1.1.1 同一口径。 */
  static final BigDecimal MAX_CAMPAIGN_BUDGET = new BigDecimal("100000.0000");

  /** 单个广告组允许的竞价上限（绝对值）。与 14.1.1.2 同一口径。 */
  static final BigDecimal MAX_BID = new BigDecimal("1000.0000");

  /** 单个广告组一次允许调整的竞价上限（绝对值）。与 14.1.1.2 同一口径。 */
  static final BigDecimal MAX_SINGLE_BID_DELTA = new BigDecimal("200.0000");

  /**
   * 一次批量允许的**总**竞价调整上限（绝对值，跨条目累加）。
   *
   * <p>🔴 竞价的「总额上限」是本模块自有的：14.1.1.2 的单活动单组改竞价没有跨活动这一维。
   * 它与 {@link #MAX_TOTAL_BUDGET_DELTA} 是两个维度 —— 调价动的是**活动日预算**，
   * 改竞价动的是**单次点击价**，两个都可能被一次误操作放大。
   */
  static final BigDecimal MAX_TOTAL_BID_DELTA = new BigDecimal("20000.0000");

  /** 一个批量任务最多允许的活动条数。 */
  static final int MAX_CAMPAIGNS_PER_TASK = 200;

  /** 一个批量任务最多允许的广告组条数（竞价任务）。 */
  static final int MAX_GROUPS_PER_TASK = 500;

  /** 一次筛选最多允许解析出的条数。超过 ⇒ 整批拒绝（请缩小筛选范围）。 */
  static final int MAX_SELECTABLE_PER_FILTER = 1000;

  /** 预览最多返回多少条明细（超出部分只给计数）。 */
  static final int MAX_PREVIEW_ITEMS = 100;

  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 🔴 竞价调整没有消耗预估模型 —— 不编一个假的数字出来。 */
  static final String SPEND_IMPACT_NOTE =
      "🔴 本模块**没有**「竞价 → 曝光 / 消耗」的预估模型，所以预览只报**竞价增量**，"
          + "**不编**一个消耗预估数字出来 —— 编出来的预估会被当成事实，运营会照着它做决策。"
          + "竞价调整真正的钱袋子约束是**活动日预算**（那是 14.2.2.1 预算分配的职责）；"
          + "本模块对竞价的保护是「单组竞价上限 + 单次调整上限 + 跨条目的总额上限」三条。";

  /** 🔴 「本地已改、还没回写平台」的话术（界面必须显式写出来）。 */
  static final String WRITEBACK_NOTE =
      "🔴 平台广告 API 与回写队列**未交付** ⇒ 本模块的改动**只落本地**，"
          + "并把活动标记为「待回写平台」。"
          + "界面上必须显式写「本地已改、还没回写平台」—— "
          + "否则运营会把「本地已暂停」当成「平台侧已暂停」，那是假事实。";

  private final DSLContext sql;
  private final ObjectMapper json;

  public AdBulkOperationService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ==================================================================
  // 小工具
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

  private static UUID parseId(String value) {
    try {
      return UUID.fromString(value.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 不是合法 UUID");
    }
  }

  /** 当前租户（由 backend/libraries/tenancy 绑定，不来自任何客户端头）。 */
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

  /** LIKE 里的通配符要转义（照抄 14.1.1.1 的同名写法）。 */
  private static String escapeLike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public AdBulkCapabilities capabilities() {
    return new AdBulkCapabilities(
        List.of(
            new AdBulkCapabilityDimension(
                "bulk_select_by_filter",
                "按筛选结果全选：服务端把筛选条件解析成 id 集合（分页 + 游标 + 硬上限）",
                true,
                "ad_campaign + ad_campaign_group（V273 / V277）",
                "",
                "没有它就只能一条一条勾，前端把 id 全拉回来再循环等于全表扫"),
            new AdBulkCapabilityDimension(
                "bulk_preview",
                "预览变更：不落库的预演，给出逐条「会变成什么样」+ 总预算增量 + 护栏裁决",
                true,
                "本模块的服务层预演（不落库）",
                "",
                "没有它就只能在「不知道会变成什么」的状态下点提交"),
            new AdBulkCapabilityDimension(
                "bulk_async_task",
                "批量调整任务（异步）+ 每条执行结果：任务表 + 任务项表 + 执行器",
                true,
                "ad_campaign_bulk_task + ad_campaign_bulk_task_item（V282）",
                "",
                "没有它，几百条调整就得同步等一个请求返回"),
            new AdBulkCapabilityDimension(
                "bulk_budget_adjust",
                "跨活动预算增减 / 设为预算（带单次与总额护栏）",
                true,
                "ad_campaign.daily_budget（V273）",
                "",
                "没有它，跨店铺调预算只能一个一个来"),
            new AdBulkCapabilityDimension(
                "bulk_bid_adjust",
                "跨活动批量竞价调整（落点是广告组，不是活动）",
                true,
                "ad_campaign_group.default_bid + ad_campaign_group_bid_log（V277）",
                "",
                "没有它，改竞价只能单活动单组地改"),
            new AdBulkCapabilityDimension(
                "bulk_status_switch",
                "跨活动状态切换（暂停 / 启用）",
                true,
                "ad_campaign.status（V273）",
                "",
                "没有它，停掉一批超支活动要一个一个点"),
            new AdBulkCapabilityDimension(
                "bulk_set_end_at",
                "跨活动设置结束时间（之前没有任何端点写过 end_at）",
                true,
                "ad_campaign.end_at（V273）",
                "",
                "没有它，大促排期只能一条一条改"),
            new AdBulkCapabilityDimension(
                "spend_estimate",
                "竞价调整带来的曝光 / 消耗预估",
                false,
                "—",
                "14.3.1.1 活动报表 / 14.2.1.1 竞价管理",
                "本模块只报 Δ 竞价与活动日预算，**不编**消耗预估 —— 预览里的每个数都必须是真的"),
            new AdBulkCapabilityDimension(
                "platform_writeback",
                "批量调整回写平台（队列 + 重试）",
                false,
                "—",
                "平台适配层（channel/vendor）",
                "本模块的改动只落本地并标记 pending_platform_writeback，界面上会显式说明「还没回写平台」"),
            new AdBulkCapabilityDimension(
                "ad_group_management",
                "广告组的增删改（默认竞价以外的结构管理）",
                false,
                "—",
                "14.1.2.1 广告组管理",
                "本模块只读广告组的 default_bid 并只写它，不碰广告组的其它字段")),
        AdBulkOperationRules.OPERATIONS,
        AdBulkOperationRules.TARGET_TYPES,
        AdBulkOperationRules.STATUSES,
        guardrails(),
        SPEND_IMPACT_NOTE,
        WRITEBACK_NOTE,
        "上游 = 14.1.1.1 活动列表（V273）与 14.1.1.2 活动详情（V277）；"
            + "平台目录（2.1.1.1）与店铺（2.3.1.1）同库只读引用。",
        "🔴 本模块**不做**平台回写（队列未交付），**不做**消耗预估（没有模型），"
            + "**不做**广告组的增删改（归 14.1.2.1）；"
            + "跨服务一律禁止 —— 一切都在 channel 库内。");
  }

  static AdBulkGuardrails guardrails() {
    return new AdBulkGuardrails(
        MAX_SINGLE_BUDGET_DELTA,
        MAX_TOTAL_BUDGET_DELTA,
        MAX_CAMPAIGN_BUDGET,
        MAX_BID,
        MAX_SINGLE_BID_DELTA,
        MAX_TOTAL_BID_DELTA,
        MAX_CAMPAIGNS_PER_TASK,
        MAX_GROUPS_PER_TASK,
        MAX_SELECTABLE_PER_FILTER,
        MAX_PREVIEW_ITEMS,
        "🔴 单次上限与总额上限一起给：单次上限挡住「把一个活动改成天价」，"
            + "总额上限挡住「一次误操作烧掉整月预算」。"
            + "🔴 **总额上限是跨条目累加的**：一条一条看每条都没越限，"
            + "整批却已经烧掉整月预算 —— 只逐条判上限是拦不住这种的。"
            + "🔴 越上限时**整批拒绝**（不做部分执行），"
            + "而「已归档的活动不能暂停」这种逐条业务规则只拒那一条。");
  }

  // ==================================================================
  // 选项
  // ==================================================================

  public AdBulkOptions options() {
    List<AdBulkOption> platforms =
        sql.fetch("select id, code, name from platform order by position, code")
            .stream()
            .map(r -> new AdBulkOption(r.get("code", String.class), r.get("name", String.class)))
            .toList();

    List<AdBulkOption> shops =
        sql.fetch("select code, name from shop order by code").stream()
            .map(r -> new AdBulkOption(r.get("code", String.class), r.get("name", String.class)))
            .toList();

    List<AdBulkOption> statuses =
        AdBulkOperationRules.STATUSES.stream()
            .map(s -> new AdBulkOption(s, AdCampaignService.statusLabel(s)))
            .toList();

    List<AdBulkOption> types =
        AdCampaignService.INTERNAL_AD_TYPES.stream()
            .map(t -> new AdBulkOption(t, AdCampaignService.internalTypeLabel(t)))
            .toList();

    List<AdBulkOption> operations =
        List.of(
            new AdBulkOption("ADJUST_BUDGET", "预算增减"),
            new AdBulkOption("SET_BUDGET", "设为预算"),
            new AdBulkOption("PAUSE", "暂停"),
            new AdBulkOption("ENABLE", "启用"),
            new AdBulkOption("SET_END_AT", "设置结束时间"),
            new AdBulkOption("ADJUST_BID", "调整竞价（落在广告组）"));

    return new AdBulkOptions(
        platforms, shops, statuses, types, operations, guardrails(), SPEND_IMPACT_NOTE,
        "🔴 护栏上限随下拉一起返回：界面上要能写明「本次最多 N 个」，而不是让人撞墙才知道。");
  }

  // ==================================================================
  // 按筛选结果全选：服务端解析 id 集合
  // ==================================================================

  /** 筛选条件（与 14.1.1.1 的列表筛选同构）。 */
  private record Filters(String where, List<Object> binds) {}

  private Filters buildFilters(
      UUID platformId,
      String shopCode,
      String status,
      String internalAdType,
      String platformAdType,
      String keyword) {
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
    return new Filters(where.toString(), binds);
  }

  /**
   * 按筛选条件解析出目标集合的一页（服务端解析 + 游标 + 硬上限）。
   *
   * <p>🔴 这是「按筛选结果全选」**唯一**的入口形态：前端把筛选条件递过来，
   * 服务端在 SQL 里解析出 id 集合。前端**不**能把 id 全拉回来再循环。
   */
  public AdBulkSelection selection(
      UUID platformId,
      String shopCode,
      String status,
      String internalAdType,
      String platformAdType,
      String keyword,
      String targetType,
      String cursor,
      int size) {
    String target = text(targetType).toUpperCase(Locale.ROOT);
    if (target.isEmpty()) target = "CAMPAIGN";
    if (!AdBulkOperationRules.TARGET_TYPES.contains(target)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "调整落点只能是 CAMPAIGN / AD_GROUP");
    }

    int safeSize = Math.min(Math.max(size, 1), 200);
    Filters filters =
        buildFilters(platformId, shopCode, status, internalAdType, platformAdType, keyword);
    UUID after = blankToNull(cursor) == null ? null : parseId(cursor);

    long matched =
        count("select count(*) from ad_campaign c" + filters.where(), filters.binds().toArray());

    List<AdBulkSelectedRow> rows = new ArrayList<>();
    boolean groupTarget = "AD_GROUP".equals(target);
    if (groupTarget) {
      StringBuilder sqlText =
          new StringBuilder(
              "select g.id group_id, g.campaign_id, g.group_name, g.default_bid, g.bid_currency,"
                  + " c.campaign_name, c.shop_code, pf.code platform_code, c.status, c.daily_budget,"
                  + " c.currency, c.end_at"
                  + " from ad_campaign_group g"
                  + " join ad_campaign c on c.tenant_id = g.tenant_id and c.id = g.campaign_id"
                  + " join platform pf on pf.id = c.platform_id"
                  + filters.where());
      List<Object> binds = new ArrayList<>(filters.binds());
      if (after != null) {
        sqlText.append(" and g.id > ?::uuid");
        binds.add(after);
      }
      sqlText.append(" order by g.id limit ?");
      binds.add(safeSize + 1);
      for (Record r : sql.fetch(sqlText.toString(), binds.toArray())) {
        rows.add(
            new AdBulkSelectedRow(
                "AD_GROUP",
                r.get("campaign_id", UUID.class),
                r.get("campaign_name", String.class),
                r.get("shop_code", String.class),
                r.get("platform_code", String.class),
                r.get("status", String.class),
                r.get("daily_budget", BigDecimal.class),
                r.get("currency", String.class),
                r.get("end_at", OffsetDateTime.class),
                r.get("group_id", UUID.class),
                r.get("group_name", String.class),
                r.get("default_bid", BigDecimal.class),
                r.get("bid_currency", String.class)));
      }
    } else {
      StringBuilder sqlText =
          new StringBuilder(
              "select c.id, c.campaign_name, c.shop_code, pf.code platform_code, c.status,"
                  + " c.daily_budget, c.currency, c.end_at"
                  + " from ad_campaign c join platform pf on pf.id = c.platform_id"
                  + filters.where());
      List<Object> binds = new ArrayList<>(filters.binds());
      if (after != null) {
        sqlText.append(" and c.id > ?::uuid");
        binds.add(after);
      }
      sqlText.append(" order by c.id limit ?");
      binds.add(safeSize + 1);
      for (Record r : sql.fetch(sqlText.toString(), binds.toArray())) {
        rows.add(
            new AdBulkSelectedRow(
                "CAMPAIGN",
                r.get("id", UUID.class),
                r.get("campaign_name", String.class),
                r.get("shop_code", String.class),
                r.get("platform_code", String.class),
                r.get("status", String.class),
                r.get("daily_budget", BigDecimal.class),
                r.get("currency", String.class),
                r.get("end_at", OffsetDateTime.class),
                null,
                "",
                null,
                ""));
      }
    }

    boolean more = rows.size() > safeSize;
    if (more) rows = rows.subList(0, safeSize);
    String nextCursor = more && !rows.isEmpty() ? cursorOf(rows.get(rows.size() - 1), groupTarget) : null;

    return new AdBulkSelection(
        rows,
        matched,
        nextCursor,
        matched > MAX_SELECTABLE_PER_FILTER,
        MAX_SELECTABLE_PER_FILTER,
        "🔴 解析在**服务端**完成：前端只拿到一页 id 与一个计数，"
            + "不会把整张表拉回浏览器再循环。"
            + (matched > MAX_SELECTABLE_PER_FILTER
                ? "🔴 命中 " + matched + " 条已超过单次上限 " + MAX_SELECTABLE_PER_FILTER
                    + " 条 —— 批量任务会**整批拒绝**，请先缩小筛选范围。"
                : ""));
  }

  private static String cursorOf(AdBulkSelectedRow row, boolean groupTarget) {
    return (groupTarget ? row.groupId() : row.campaignId()).toString();
  }

  // ==================================================================
  // 解析：筛选 / 显式勾选 → 目标集合
  // ==================================================================

  private AdBulkResolution resolve(AdBulkOperationRequest raw, AdBulkValidatedRequest params) {
    boolean explicit = raw.campaignIds() != null && !raw.campaignIds().isEmpty();
    boolean groupTarget = "AD_GROUP".equals(params.targetType());

    if (explicit) {
      List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(raw.campaignIds()));
      if (ids.size() > MAX_SELECTABLE_PER_FILTER) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "一次最多选 " + MAX_SELECTABLE_PER_FILTER + " 条，本次提交了 " + ids.size() + " 条");
      }
      String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?::uuid"));
      List<Record> campaigns =
          sql.fetch(
              "select c.id, c.campaign_name, c.shop_code, pf.code platform_code, c.status,"
                  + " c.daily_budget, c.currency, c.end_at"
                  + " from ad_campaign c join platform pf on pf.id = c.platform_id"
                  + " where c.tenant_id = "
                  + TENANT
                  + " and c.id in ("
                  + placeholders
                  + ")",
              ids.toArray());
      // 🔴 显式勾选的 id 必须**全部**解析得到：页面可能过期（活动被删了），
      //   那是客户端错误（400），不是「这一条被业务规则拒了」。
      if (campaigns.size() != ids.size()) {
        Set<UUID> found = new LinkedHashSet<>();
        for (Record r : campaigns) found.add(r.get("id", UUID.class));
        List<String> missing = new ArrayList<>();
        for (UUID id : ids) {
          if (!found.contains(id)) missing.add(id.toString());
        }
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "选中的活动里有 " + missing.size() + " 条不存在或不属于当前租户：" + String.join("、", missing));
      }

      List<AdBulkTarget> targets = new ArrayList<>();
      if (groupTarget) {
        String groupPlaceholders =
            String.join(",", java.util.Collections.nCopies(ids.size(), "?::uuid"));
        List<Record> groups =
            sql.fetch(
                "select g.id group_id, g.campaign_id, g.group_name, g.default_bid, g.bid_currency"
                    + " from ad_campaign_group g where g.tenant_id = "
                    + TENANT
                    + " and g.campaign_id in ("
                    + groupPlaceholders
                    + ")",
                ids.toArray());
        Map<UUID, Record> byCampaign = new LinkedHashMap<>();
        for (Record r : campaigns) byCampaign.put(r.get("id", UUID.class), r);
        for (Record g : groups) {
          Record c = byCampaign.get(g.get("campaign_id", UUID.class));
          if (c == null) continue;
            targets.add(
              new AdBulkTarget(
                  "AD_GROUP",
                  c.get("id", UUID.class),
                  c.get("campaign_name", String.class),
                  c.get("shop_code", String.class),
                  c.get("status", String.class),
                  c.get("daily_budget", BigDecimal.class),
                  c.get("end_at", OffsetDateTime.class),
                  g.get("group_id", UUID.class),
                  g.get("group_name", String.class),
                  g.get("default_bid", BigDecimal.class),
                  g.get("bid_currency", String.class)));
        }
        return new AdBulkResolution(
            targets,
            campaigns.size(),
            false,
            null,
            "EXPLICIT",
            "显式勾选 " + ids.size() + " 个活动，其广告组 " + targets.size() + " 个");
      }
      for (Record c : campaigns) {
        targets.add(
            new AdBulkTarget(
                "CAMPAIGN",
                c.get("id", UUID.class),
                c.get("campaign_name", String.class),
                c.get("shop_code", String.class),
                c.get("status", String.class),
                c.get("daily_budget", BigDecimal.class),
                c.get("end_at", OffsetDateTime.class),
                null,
                "",
                null,
                ""));
      }
      return new AdBulkResolution(
          targets,
          targets.size(),
          false,
          null,
            "EXPLICIT",
          "显式勾选 " + targets.size() + " 个活动");
    }

    // ---- 按筛选结果全选：解析在 SQL 里 ----
    Filters filters =
        buildFilters(
            raw.platformId(),
            raw.shopCode(),
            raw.status(),
            raw.internalAdType(),
            raw.platformAdType(),
            raw.keyword());
    long matched = count("select count(*) from ad_campaign c" + filters.where(), filters.binds().toArray());

    if (matched > MAX_SELECTABLE_PER_FILTER) {
      return new AdBulkResolution(
          List.of(),
          matched,
          true,
          "筛选命中 " + matched + " 条，超过单次上限 " + MAX_SELECTABLE_PER_FILTER
              + " 条 —— 整批拒绝，请先缩小筛选范围",
          "FILTER",
          "按筛选条件命中 " + matched + " 条（超过单次上限 " + MAX_SELECTABLE_PER_FILTER + " 条）");
    }

    List<AdBulkTarget> targets = new ArrayList<>();
    if (groupTarget) {
      for (Record r :
          sql.fetch(
              "select g.id group_id, g.campaign_id, g.group_name, g.default_bid, g.bid_currency,"
                  + " c.campaign_name, c.shop_code, c.status, c.daily_budget, c.end_at"
                  + " from ad_campaign_group g"
                  + " join ad_campaign c on c.tenant_id = g.tenant_id and c.id = g.campaign_id"
                  + filters.where()
                  + " order by g.id",
              filters.binds().toArray())) {
        targets.add(
            new AdBulkTarget(
                "AD_GROUP",
                r.get("campaign_id", UUID.class),
                r.get("campaign_name", String.class),
                r.get("shop_code", String.class),
                r.get("status", String.class),
                r.get("daily_budget", BigDecimal.class),
                r.get("end_at", OffsetDateTime.class),
                r.get("group_id", UUID.class),
                r.get("group_name", String.class),
                r.get("default_bid", BigDecimal.class),
                r.get("bid_currency", String.class)));
      }
    } else {
      for (Record r :
          sql.fetch(
              "select c.id, c.campaign_name, c.shop_code, c.status, c.daily_budget, c.end_at"
                  + " from ad_campaign c"
                  + filters.where()
                  + " order by c.id",
              filters.binds().toArray())) {
        targets.add(
            new AdBulkTarget(
                "CAMPAIGN",
                r.get("id", UUID.class),
                r.get("campaign_name", String.class),
                r.get("shop_code", String.class),
                r.get("status", String.class),
                r.get("daily_budget", BigDecimal.class),
                r.get("end_at", OffsetDateTime.class),
                null,
                "",
                null,
                ""));
      }
    }

    return new AdBulkResolution(
        targets,
        matched,
        false,
        null,
        "FILTER",
        filterSummary(raw, matched, groupTarget, targets.size()));
  }

  /** 🔴 选择条件摘要（人话）：复盘时不用去猜 filter_snapshot 里那串 json。 */
  private String filterSummary(
      AdBulkOperationRequest raw, long matched, boolean groupTarget, int targetCount) {
    StringBuilder sb = new StringBuilder("按筛选条件命中 ").append(matched).append(" 个活动");
    if (groupTarget) sb.append("，展开为 ").append(targetCount).append(" 个广告组");
    List<String> parts = new ArrayList<>();
    if (raw.platformId() != null) parts.add("平台=" + raw.platformId());
    if (blankToNull(raw.shopCode()) != null) parts.add("店铺=" + blankToNull(raw.shopCode()));
    if (blankToNull(raw.status()) != null) parts.add("状态=" + blankToNull(raw.status()));
    if (blankToNull(raw.internalAdType()) != null)
      parts.add("内部类型=" + blankToNull(raw.internalAdType()));
    if (blankToNull(raw.platformAdType()) != null)
      parts.add("原始类型=" + blankToNull(raw.platformAdType()));
    if (blankToNull(raw.keyword()) != null) parts.add("关键词=" + blankToNull(raw.keyword()));
    if (!parts.isEmpty()) sb.append("（").append(String.join("，", parts)).append("）");
    return sb.toString();
  }

  // ==================================================================
  // 预览（不落库）
  // ==================================================================

  public AdBulkPreview preview(AdBulkOperationRequest request) {
    AdBulkValidatedRequest params = AdBulkOperationRules.validate(request);
    AdBulkResolution resolution = resolve(request, params);
    OffsetDateTime now = OffsetDateTime.now();

    List<AdBulkPlanItem> items = new ArrayList<>();
    for (AdBulkTarget target : resolution.targets()) {
      items.add(AdBulkOperationRules.plan(target, params, now));
    }
    AdBulkOperationRules.Totals totals = AdBulkOperationRules.summarize(items);

    String rejection = resolution.rejectionReason();
    if (rejection == null) {
      rejection =
          AdBulkOperationRules.guardrailRejection(
              totals,
              items.size(),
              MAX_CAMPAIGNS_PER_TASK,
              MAX_GROUPS_PER_TASK,
              "AD_GROUP".equals(params.targetType()));
    }

    boolean truncated = items.size() > MAX_PREVIEW_ITEMS;
    List<AdBulkPreviewItem> previewItems =
        items.stream()
            .limit(MAX_PREVIEW_ITEMS)
            .map(
                i ->
                    new AdBulkPreviewItem(
                        i.targetType(),
                        i.campaignId(),
                        i.campaignName(),
                        i.groupId(),
                        i.groupName(),
                        i.beforeStatus(),
                        i.afterStatus(),
                        i.beforeBudget(),
                        i.afterBudget(),
                        i.budgetDelta(),
                        i.beforeBid(),
                        i.afterBid(),
                        i.bidDelta(),
                        i.beforeEndAt(),
                        i.afterEndAt(),
                        i.verdict(),
                        i.message()))
            .toList();

    return new AdBulkPreview(
        rejection == null,
        rejection == null ? "" : rejection,
        params.operation(),
        params.targetType(),
        items.size(),
        resolution.matchedCount(),
        previewItems.size(),
        truncated,
        totals.budgetSigned(),
        totals.budgetAbs(),
        totals.bidSigned(),
        totals.bidAbs(),
        false,
        SPEND_IMPACT_NOTE,
        guardrails(),
        previewItems,
        rejection == null
            ? "🔴 预演**不落库**：这里看到的每一项都还没生效。"
                + (truncated
                    ? "（明细只展示前 " + MAX_PREVIEW_ITEMS + " 条，共 " + items.size() + " 条。）"
                    : "")
            : "🔴 **整批拒绝**：超上限时一条都不会执行（不做部分执行）。"
                + "明细仍然列出来，是为了让运营看到「正是哪一条越了上限」。");
  }

  // ==================================================================
  // 提交批量任务（异步）
  // ==================================================================

  /**
   * 受理一次批量调整：落一张任务 + 一批任务项，等执行器来跑。
   *
   * <p>🔴 护栏越限 ⇒ 任务**整批被拒**（status=REJECTED，任务项全部 REJECTED，一条都没执行）。
   * 任务行仍然写库 —— 「有人差点烧掉整月预算」这件事本身就是要留痕的。
   */
  public AdBulkTaskRow createTask(AdBulkOperationRequest request) {
    AdBulkValidatedRequest params = AdBulkOperationRules.validate(request);
    AdBulkResolution resolution = resolve(request, params);
    OffsetDateTime now = OffsetDateTime.now();

    List<AdBulkPlanItem> planned = new ArrayList<>();
    for (AdBulkTarget target : resolution.targets()) {
      planned.add(AdBulkOperationRules.plan(target, params, now));
    }
    AdBulkOperationRules.Totals totals = AdBulkOperationRules.summarize(planned);

    String rejection = resolution.rejectionReason();
    if (rejection == null) {
      rejection =
          AdBulkOperationRules.guardrailRejection(
              totals,
              planned.size(),
              MAX_CAMPAIGNS_PER_TASK,
              MAX_GROUPS_PER_TASK,
              "AD_GROUP".equals(params.targetType()));
    }
    boolean rejected = rejection != null;

    UUID tenant = currentTenant();
    UUID actor = currentActor();
    UUID taskId = Ids.next();

    // 🔴 **先写任务项，再写任务行**。
    //   任务的可见性靠任务项判定（V282 的 data_scope 策略），
    //   而任务项的外键是**延迟**的（DEFERRABLE INITIALLY DEFERRED）——
    //   所以「任务项先于任务行存在」在提交前是允许的。
    //   反过来（先写任务行）的话，任务行写入时一条任务项都还没有，
    //   可见性判定会把它拒掉。
    //
    // 🔴 任务项：创建时写入**计划值**（预览算出来的那份），
    //   执行时会被**实际值**覆盖 —— 执行前显示打算变成什么，执行后显示真的变成了什么。
    for (AdBulkPlanItem p : planned) {
      sql.execute(
          "insert into ad_campaign_bulk_task_item (tenant_id,id,task_id,target_type,campaign_id,"
              + " group_id,campaign_name,group_name,before_status,after_status,before_budget,"
              + " after_budget,before_bid,after_bid,before_end_at,after_end_at,result,message)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?)",
          tenant,
          Ids.next(),
          taskId,
          p.targetType(),
          p.campaignId(),
          p.groupId(),
          p.campaignName(),
          p.groupName(),
          p.beforeStatus(),
          p.afterStatus(),
          p.beforeBudget(),
          p.afterBudget(),
          p.beforeBid(),
          p.afterBid(),
          p.beforeEndAt(),
          p.afterEndAt(),
          rejected ? "REJECTED" : "PENDING",
          rejected ? rejection : p.message());
    }

    sql.execute(
        "insert into ad_campaign_bulk_task (tenant_id,id,operation,target_type,status,"
            + " selection_mode,selection_summary,filter_snapshot,explicit_ids,"
            + " param_budget,param_delta,param_bid,param_bid_delta,param_end_at,param_clear_end_at,reason,"
            + " target_count,applied_count,rejected_count,skipped_count,"
            + " total_budget_delta,total_budget_delta_abs,total_bid_delta,total_bid_delta_abs,"
            + " guardrail_max_campaigns,guardrail_max_groups,"
            + " guardrail_max_single_budget_delta,guardrail_max_total_budget_delta,"
            + " guardrail_max_campaign_budget,guardrail_max_bid,guardrail_max_single_bid_delta,"
            + " guardrail_max_total_bid_delta,"
            + " requested_by,message,created_at)"
            + " values (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?::timestamptz,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())",
        tenant,
        taskId,
        params.operation(),
        params.targetType(),
        rejected ? "REJECTED" : "PENDING",
        resolution.selectionMode(),
        resolution.selectionSummary(),
        json(resolution.selectionSummary()),
        json(explicitIds(request)),
        params.budget(),
        params.delta(),
        params.bid(),
        params.bidDelta(),
        params.endAt(),
        params.clearEndAt(),
        params.reason(),
        planned.size(),
        0,
        0,
        0,
        totals.budgetSigned(),
        totals.budgetAbs(),
        totals.bidSigned(),
        totals.bidAbs(),
        MAX_CAMPAIGNS_PER_TASK,
        MAX_GROUPS_PER_TASK,
        MAX_SINGLE_BUDGET_DELTA,
        MAX_TOTAL_BUDGET_DELTA,
        MAX_CAMPAIGN_BUDGET,
        MAX_BID,
        MAX_SINGLE_BID_DELTA,
        MAX_TOTAL_BID_DELTA,
        actor == null ? tenant : actor,
        rejected ? rejection : "");

    return taskRow(tenant, taskId);
  }

  private List<UUID> explicitIds(AdBulkOperationRequest request) {
    return request.campaignIds() == null ? List.of() : request.campaignIds();
  }

  private String json(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("无法序列化快照", e);
    }
  }

  // ==================================================================
  // 任务查询
  // ==================================================================

  public AdBulkTaskPage tasks(int page, int size) {
    int safeSize = Math.min(Math.max(size, 1), 100);
    int safePage = Math.max(page, 0);
    long total = count("select count(*) from ad_campaign_bulk_task where tenant_id = " + TENANT);
    List<AdBulkTaskRow> items =
        sql.fetch(
                "select * from ad_campaign_bulk_task where tenant_id = "
                    + TENANT
                    + " order by created_at desc, id desc limit ? offset ?",
                safeSize,
                safePage * safeSize)
            .stream()
            .map(r -> taskRowFrom(r))
            .toList();
    return new AdBulkTaskPage(items, total, safePage, safeSize);
  }

  public AdBulkTaskDetail task(String id, int page, int size) {
    UUID taskId = parseId(id);
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    AdBulkTaskRow task = taskRow(currentTenant(), taskId);
    long itemTotal =
        count(
            "select count(*) from ad_campaign_bulk_task_item where tenant_id = "
                + TENANT
                + " and task_id = ?",
            taskId);
    List<AdBulkTaskItemRow> items =
        sql.fetch(
                "select i.*, c.pending_platform_writeback"
                    + " from ad_campaign_bulk_task_item i"
                    + " left join ad_campaign c on c.tenant_id = i.tenant_id and c.id = i.campaign_id"
                    + " where i.tenant_id = "
                    + TENANT
                    + " and i.task_id = ? order by i.id limit ? offset ?",
                taskId,
                safeSize,
                safePage * safeSize)
            .stream()
            .map(r -> itemRow(r))
            .toList();
    return new AdBulkTaskDetail(
        task, items, itemTotal, safePage, safeSize,
        "🔴 任务状态是**本地**状态：跑完只代表本地已改，平台侧还没改（回写队列未交付）。");
  }

  private AdBulkTaskRow taskRow(UUID tenant, UUID taskId) {
    Record r =
        sql.fetchOne(
            "select * from ad_campaign_bulk_task where tenant_id = ? and id = ?", tenant, taskId);
    if (r == null) {
      // 🔴 跨租户与不存在返回**逐字相同**的 404（照抄 10.1.1.2 / 10.1.2.1 同款口径）。
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批量任务不存在");
    }
    return taskRowFrom(r);
  }

  private AdBulkTaskRow taskRowFrom(Record r) {
    return new AdBulkTaskRow(
        r.get("id", UUID.class),
        r.get("operation", String.class),
        r.get("target_type", String.class),
        r.get("status", String.class),
        r.get("target_count", Integer.class),
        r.get("applied_count", Integer.class),
        r.get("rejected_count", Integer.class),
        r.get("skipped_count", Integer.class),
        r.get("total_budget_delta", BigDecimal.class),
        r.get("total_budget_delta_abs", BigDecimal.class),
        r.get("total_bid_delta", BigDecimal.class),
        r.get("total_bid_delta_abs", BigDecimal.class),
        r.get("selection_mode", String.class),
        r.get("selection_summary", String.class),
        r.get("param_budget", BigDecimal.class),
        r.get("param_delta", BigDecimal.class),
        r.get("param_bid", BigDecimal.class),
        r.get("param_bid_delta", BigDecimal.class),
        r.get("param_end_at", OffsetDateTime.class),
        r.get("param_clear_end_at", Boolean.class),
        r.get("reason", String.class),
        r.get("requested_by", UUID.class),
        r.get("message", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("finished_at", OffsetDateTime.class));
  }

  private AdBulkTaskItemRow itemRow(Record r) {
    return new AdBulkTaskItemRow(
        r.get("id", UUID.class),
        r.get("target_type", String.class),
        r.get("campaign_id", UUID.class),
        r.get("campaign_name", String.class),
        r.get("group_id", UUID.class),
        r.get("group_name", String.class),
        r.get("before_status", String.class),
        r.get("after_status", String.class),
        r.get("before_budget", BigDecimal.class),
        r.get("after_budget", BigDecimal.class),
        r.get("before_bid", BigDecimal.class),
        r.get("after_bid", BigDecimal.class),
        r.get("before_end_at", OffsetDateTime.class),
        r.get("after_end_at", OffsetDateTime.class),
        r.get("result", String.class),
        r.get("message", String.class),
        Boolean.TRUE.equals(r.get("pending_platform_writeback", Boolean.class)),
        r.get("executed_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 给执行器用的读取形状
  // ==================================================================

  /** 读一次任务（执行器用）。 */
  AdBulkTaskExecution loadExecution(UUID tenant, UUID taskId) {
    Record r =
        sql.fetchOne(
            "select id,operation,target_type,param_budget,param_delta,param_bid,param_bid_delta,"
                + " param_end_at,param_clear_end_at,reason,requested_by,"
                + " guardrail_max_campaigns,guardrail_max_groups,"
                + " guardrail_max_single_budget_delta,guardrail_max_total_budget_delta,"
                + " guardrail_max_campaign_budget,guardrail_max_bid,"
                + " guardrail_max_single_bid_delta,guardrail_max_total_bid_delta"
                + " from ad_campaign_bulk_task where tenant_id = ? and id = ?",
            tenant,
            taskId);
    if (r == null) return null;
    return new AdBulkTaskExecution(
        r.get("id", UUID.class),
        r.get("operation", String.class),
        r.get("target_type", String.class),
        r.get("param_budget", BigDecimal.class),
        r.get("param_delta", BigDecimal.class),
        r.get("param_bid", BigDecimal.class),
        r.get("param_bid_delta", BigDecimal.class),
        r.get("param_end_at", OffsetDateTime.class),
        Boolean.TRUE.equals(r.get("param_clear_end_at", Boolean.class)),
        r.get("reason", String.class),
        r.get("requested_by", UUID.class),
        new AdBulkTaskGuardrailSnapshot(
            r.get("guardrail_max_single_budget_delta", BigDecimal.class),
            r.get("guardrail_max_total_budget_delta", BigDecimal.class),
            r.get("guardrail_max_campaign_budget", BigDecimal.class),
            r.get("guardrail_max_bid", BigDecimal.class),
            r.get("guardrail_max_single_bid_delta", BigDecimal.class),
            r.get("guardrail_max_total_bid_delta", BigDecimal.class),
            r.get("guardrail_max_campaigns", Integer.class),
            r.get("guardrail_max_groups", Integer.class)));
  }

  /** 护栏上限（供执行器复算时用任务快照里的那一份）。 */
  static AdBulkGuardrails guardrailsOf(AdBulkTaskGuardrailSnapshot snapshot) {
    return new AdBulkGuardrails(
        snapshot.maxSingleBudgetDelta(),
        snapshot.maxTotalBudgetDelta(),
        snapshot.maxCampaignBudget(),
        snapshot.maxBid(),
        snapshot.maxSingleBidDelta(),
        snapshot.maxTotalBidDelta(),
        snapshot.maxCampaigns(),
        snapshot.maxGroups(),
        MAX_SELECTABLE_PER_FILTER,
        MAX_PREVIEW_ITEMS,
        "任务创建时的护栏快照。");
  }

  /** 供执行器复算预算总额用。 */
  static BigDecimal totalOf(List<BigDecimal> values) {
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal v : values) sum = sum.add(nz(v));
    return sum;
  }

  /** 供执行器用：把「计划值」写回任务项。 */
  void writeItemPlan(UUID tenant, AdBulkPlanItem plan, UUID itemId) {
    sql.execute(
        "update ad_campaign_bulk_task_item set before_status = ?, after_status = ?,"
            + " before_budget = ?, after_budget = ?, before_bid = ?, after_bid = ?,"
            + " before_end_at = ?::timestamptz, after_end_at = ?::timestamptz"
            + " where tenant_id = ? and id = ?",
        plan.beforeStatus(),
        plan.afterStatus(),
        plan.beforeBudget(),
        plan.afterBudget(),
        plan.beforeBid(),
        plan.afterBid(),
        plan.beforeEndAt(),
        plan.afterEndAt(),
        tenant,
        itemId);
  }

  /** 供执行器用：写任务的绝对敞口。 */
  void writeTaskTotals(UUID tenant, UUID taskId, BigDecimal budgetAbs, BigDecimal bidAbs) {
    sql.execute(
        "update ad_campaign_bulk_task set total_budget_delta_abs = ?, total_bid_delta_abs = ?"
            + " where tenant_id = ? and id = ?",
        budgetAbs,
        bidAbs,
        tenant,
        taskId);
  }
}
