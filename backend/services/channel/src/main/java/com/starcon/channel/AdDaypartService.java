package com.starcon.channel;

import static com.starcon.channel.AdDaypartModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
 * 14.2.1.2 分时策略。
 *
 * <p>蓝图：「按小时设置不同竞价与预算（高峰提价提预算、低谷降价停投），
 * 支持按历史出单时段自动推荐分时曲线。输入：分时规则；13.2.1.2 时段分布数据。
 * 输出：分时策略配置 + 定时调整任务。关系：上游 = 13.2.1.2；下游 = 14.2.2.2 自动规则。
 * <b>分时策略需按店铺时区配置</b>，跨时区店铺用错时区会完全失效。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>分时规则</b> → {@link #createStrategy(...)} / {@link #replaceCurve(...)}：
 *       一个店铺一条策略 + <b>24 个店铺本地小时</b>的曲线（竞价系数 + 预算系数 + 动作）。
 *   <li><b>13.2.1.2 时段分布数据</b> → {@link #generateRecommendation(...)}：
 *       经授权只读 API 读上游的<b>逐店铺本地小时分布</b>，按租户自己的判定参数算出推荐曲线。
 *   <li><b>分时策略配置</b> → {@link #strategies(...)} / {@link #strategy(...)}。
 *   <li><b>定时调整任务</b> → {@link #run(...)}：外部调度触发的一轮执行（<b>本服务不装
 *       {@code @Scheduled}</b>，与 V212 / V317 / identity 外部推送同一纪律）。
 * </ul>
 *
 * <p>🔴 本模块最容易犯的四个错，这里逐个挡住：
 *
 * <ol>
 *   <li><b>用错时区</b>。「按小时」= <b>店铺本地时区</b>的小时。店铺时区来自 channel 的
 *       <b>站点参数</b>（{@code shop_marketplace → marketplace.default_timezone}），
 *       <b>不硬编码时区表、不假设所有店铺同一时区</b>。启用多个站点且时区不同、或没启用站点的
 *       店铺 ⇒ <b>定不了时区</b>，它的策略<b>不许激活</b>（DB CHECK 钉死），
 *       推荐结果里<b>单列</b>成「未能按店铺本地时区归属」，<b>不摊给任何默认时区</b>。
 *       <b>夏令时</b>由 IANA 时区 + PostgreSQL 的 tzdata 负责（固定 UTC 偏移会冬夏差一小时）。
 *   <li><b>复利</b>。每小时拿<b>当前值</b>再乘一次系数，17:00 乘 1.5、18:00 再乘 1.5 就成了
 *       2.25 倍 ⇒ 一天下来把预算烧穿。⇒ 目标值 = <b>基准</b> × 系数，基准在首次用到时
 *       <b>快照一次</b>（{@code ad_daypart_baseline}）⇒ 幂等、不复利。
 *   <li><b>越线不吭声</b>。单次上限 / 总额上限 / 次数上限 / 上下限<b>逐条拒绝并留痕</b>，
 *       绝不静默截断 —— 静默截断会让人以为「按预期工作」。
 *   <li><b>跨币种相加</b>。策略一个币种；活动日预算币种或关键词竞价币种不一致 ⇒
 *       <b>逐条拒绝</b>并写明原因，不按同一个数把不同币种一起调。
 * </ol>
 *
 * <p>🔴 <b>数据来源纪律</b>：本模块<b>只读</b> 14.1.1.1 的 {@code ad_campaign} 与
 * 14.1.3.1 的 {@code ad_keyword}；<b>改预算</b>走 14.1.1.1 已交付的写路径
 * （{@link AdCampaignService#bulkOperation}），<b>改竞价</b>走 14.1.3.1 已交付的写路径
 * （{@link AdKeywordService#bulkAction}）—— 那两条路径自带护栏、幂等与操作审计，
 * 本模块<b>不另开一条绕过护栏的写路径</b>。
 */
@Service
public class AdDaypartService {

  // ==================================================================
  // 口径常量（**口径说明**随响应返回，界面表头与跨部门沟通用同一份定义）
  // ==================================================================

  static final String TIMEZONE_NOTE =
      "🔴 **「按小时」= 店铺本地时区的小时**（0..23），不是 UTC 小时，也不是服务器时区小时。"
          + "店铺时区来自 channel 的**站点参数**（shop_marketplace → marketplace.default_timezone）："
          + "启用 1 个站点 ⇒ 定得了；启用多个站点但**时区相同** ⇒ 定得了；"
          + "启用多个站点且**时区不同**、或没启用站点 ⇒ **定不了**（不许挑一个凑数）。"
          + "定不了的店铺：策略**不许激活**（DB 层 CHECK 钉死），推荐结果里**单列**成"
          + "「未能按店铺本地时区归属」，**不摊给任何默认时区**。"
          + "时区用 **IANA 标识**（如 America/Los_Angeles）⇒ **夏令时对得上**；"
          + "固定 UTC 偏移会冬夏整整差一小时，分时策略会整体错位。";

  static final String MULTIPLIER_NOTE =
      "曲线每小时两个**系数**：竞价系数（相对基准竞价）与预算系数（相对基准日预算），"
          + "外加一个动作档（KEEP 不动 / BOOST 提价提预算 / REDUCE 降价 / PAUSE 停投）。"
          + "🔴 **目标值 = 基准 × 系数**，基准在首次用到时快照一次 ⇒ 同一小时重复跑结果相同（幂等），"
          + "**不会复利**（拿当前值再乘一次，1.5 × 1.5 = 2.25，一天就把预算烧穿了）。"
          + "🔴 **「停投」= 预算系数置 0**（该小时不再花钱），**不是**把活动状态改成 PAUSED："
          + "活动状态是活动级的，个状态表达不了「只在 15:00 停投」；"
          + "而且把活动置 PAUSED 会**覆盖人工暂停**（那是人的决定，机器不该替他撤销）。"
          + "🔴 本模块**只改预算与竞价两个数字**，**不碰活动状态**。"
          + "🔴 系数是**比例**（numeric(19,6) + BigDecimal），金额是 numeric(19,4)，**全程无浮点**。";

  static final String GUARDRAIL_NOTE =
      "🔴 **四道闸**（与 14.2.2.2 自动规则同一套语义 ⇒ 下游可以直接取用）："
          + "① 单次上限（金额）—— 目标值离基准的幅度不得超过上限；"
          + "② 单次上限（比例）—— 离基准的**相对偏离**不得超过该比例（0 = 不额外限制）；"
          + "③ 总额上限 —— 窗口内**累计上调**金额不得超过上限（**下调不占额度**：少花钱不是超支）；"
          + "④ 次数上限 —— 窗口内累计调整**次数**不得超过上限。"
          + "另有**上下限保护**：调整后的预算 / 竞价不得越界。"
          + "🔴 **越限的那几条被逐条拒绝并留痕**（REJECTED + 原因 + 被哪道闸拦下），"
          + "**绝不静默截断** —— 静默截断会让人以为「按预期工作」。";

  static final String EXECUTION_NOTE =
      "🔴 **本服务不装 @Scheduled**：多副本下自带定时器要做主节点选举与分布式锁，"
          + "那是平台能力不是领域能力。**外部调度**（K8s CronJob）按策略上的 "
          + "schedule_enabled + run_interval_minutes 调**运行入口**"
          + "（POST /channel/ad-dayparts/tasks/run），天然单次、可重试、可审计。"
          + "🔴 **「现在是几个小时」由 DB 的 now() 按 IANA 时区换算**（不是 JVM 墙钟）："
          + "落库时刻与运行时刻一律取 PG 的 now()（DB 钟域）。"
          + "🔴 **幂等**：同一策略 + 同一本地小时 + 同一运行口径**只有一条任务**，"
          + "调度重试不会调两次钱。";

  static final String UPSTREAM_NOTE =
      "上游 = **13.2.1.2 分时与分周模式**（reporting 服务，V392，已交付）："
          + "本模块**只经授权只读 HTTP API**读它（零跨库 SQL / 零 FK / 零影子表），"
          + "转发的是**调用方自己的** bearer，**不传租户号**。"
          + "🔴 **上游读不到一律 503** —— 返回空集合会被读成「这个店铺没出过单」，那是撒谎。"
          + "🔴 **必须逐店铺取分布**：上游的小时桶按**各自店铺的本地时区**对齐，"
          + "一次拉全部店铺再聚合，会把**不同时区的小时混在一起** —— "
          + "那正是蓝图加粗句说的「用错时区会完全失效」。";

  static final String BOUNDARY_NOTE =
      "下游 = **14.2.2.2 自动规则**（V275，已交付）：它**消费本模块的调整上限** —— "
          + "本模块提供读取形状 GET /channel/ad-dayparts/caps（某店铺当前生效的上限 + "
          + "适用策略清单），两边**取更严的那个**。本模块**不改它的表、不 import 它的类**。"
          + "🔴 **平台回写队列未交付** ⇒ 改预算 / 改竞价只落**本地**并标记「待回写平台」，"
          + "界面显式标出 —— 不把「本地已改」说成「平台侧已改」。"
          + "🔴 本模块**不碰活动状态**（见「系数与停投」说明）。";

  static final String UPSTREAM_MODULES =
      "13.2.1.2 分时与分周模式（reporting 服务 /reporting/time-pattern/**shops**"
          + " 与 /reporting/time-pattern：店铺时区档案 + 逐店铺本地小时分布）";

  /** 一次推荐窗口最多多少天（逐店铺拉上游，窗口太长会把上游压垮）。 */
  static final int MAX_WINDOW_DAYS = 92;

  /** 推荐窗口默认天数。 */
  static final int DEFAULT_WINDOW_DAYS = 30;

  // ==================================================================
  // 枚举
  // ==================================================================

  static final List<String> STATUSES = List.of("DRAFT", "ACTIVE", "PAUSED", "ARCHIVED");
  static final List<String> SCOPES = List.of("ALL_CAMPAIGNS", "CAMPAIGN");
  static final List<String> ACTIONS = List.of("KEEP", "BOOST", "REDUCE", "PAUSE");
  static final List<String> RUN_MODES = List.of("DRY_RUN", "APPLY");
  static final List<String> TRIGGER_SOURCES = List.of("MANUAL", "SCHEDULED");
  static final List<String> UPSTREAM_STATUSES =
      List.of("OK", "SHOP_UNRESOLVED", "CONFLICT", "NO_FACT");

  /** 参与分配的活动状态（已结束 / 已归档的活动不该被分时策略调预算）。 */
  static final List<String> ELIGIBLE_CAMPAIGN_STATUSES = List.of("ENABLED", "PAUSED");

  /** 护栏码（被哪一道闸拦下的）。 */
  static final String GUARDRAIL_SINGLE_AMOUNT = "SINGLE_AMOUNT";
  static final String GUARDRAIL_SINGLE_PERCENT = "SINGLE_PERCENT";
  static final String GUARDRAIL_TOTAL_AMOUNT = "TOTAL_AMOUNT";
  static final String GUARDRAIL_TOTAL_COUNT = "TOTAL_COUNT";
  static final String GUARDRAIL_BOUND = "BOUND";
  static final String GUARDRAIL_CURRENCY = "CURRENCY";

  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 空 UUID（唯一索引里给 keyword_id 归一化用）。 */
  private static final String ZERO_UUID = "00000000-0000-0000-0000-000000000000";

  /**
   * 🔴 **均匀分布下每个小时的订单占比**（= 1/24）。
   *
   * <p>占比之和恒为 1 ⇒ 如果一天里每个小时都出单得一样多，每个小时就是 1/24。
   * 「高于均值的多少倍算高峰 / 低于均值的多少倍算低谷」都以此为基准刻度，
   * 这让判定参数（倍数）与「订单规模」**无关** —— 一万单和一百单的店铺用同一套倍数。
   */
  private static final BigDecimal AVERAGE_SHARE =
      BigDecimal.ONE.divide(new BigDecimal("24"), 6, RoundingMode.HALF_UP);

  // ==================================================================
  // 依赖
  // ==================================================================

  private final DSLContext sql;
  private final com.fasterxml.jackson.databind.ObjectMapper json;
  private final AdDaypartSourcePort source;
  private final AdCampaignService campaigns;
  private final AdKeywordService keywords;

  public AdDaypartService(
      DSLContext sql,
      com.fasterxml.jackson.databind.ObjectMapper json,
      AdDaypartSourcePort source,
      AdCampaignService campaigns,
      AdKeywordService keywords) {
    this.sql = sql;
    this.json = json;
    this.source = source;
    this.campaigns = campaigns;
    this.keywords = keywords;
  }

  // ==================================================================
  // 小工具（照抄 AdCampaignService / AdBudgetAllocationService 的同名写法）
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

  private static String textOrEmpty(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static BigDecimal money(BigDecimal value) {
    return nz(value).setScale(4, RoundingMode.HALF_UP);
  }

  private static BigDecimal ratio(BigDecimal value) {
    return nz(value).setScale(6, RoundingMode.HALF_UP);
  }

  private static String upper(String value) {
    return text(value).toUpperCase(Locale.ROOT);
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

  /** 🔴 **DB 的 now()**（DB 钟域）—— 落库时刻一律用它，不用 JVM 墙钟。 */
  private OffsetDateTime dbNow() {
    OffsetDateTime now = one(OffsetDateTime.class, "select now()");
    return now == null ? OffsetDateTime.now() : now;
  }

  /** DB 的今天（DB 钟域的当前日期）。 */
  private LocalDate dbToday() {
    LocalDate today = one(LocalDate.class, "select (now() at time zone cast('UTC' as text))::date");
    return today == null ? LocalDate.now() : today;
  }

  /** 跨租户与不存在返回**逐字相同**的 404（照抄 10.1.1.2 / 10.1.2.1 同款口径）。 */
  private ResponseStatusException notFound(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在");
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static UUID parseId(String id) {
    try {
      return UUID.fromString(text(id));
    } catch (RuntimeException e) {
      throw badRequest("id 不是合法 UUID");
    }
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public AdDaypartCapabilities capabilities() {
    return new AdDaypartCapabilities(
        List.of(
            new AdDaypartCapabilityDimension(
                "daypart_strategy",
                "分时策略配置：一个店铺一条策略 + 24 个**店铺本地小时**的曲线",
                true,
                "ad_daypart_strategy + ad_daypart_strategy_hour（V393）",
                "",
                "没有曲线就没有「按小时设置」这件事"),
            new AdDaypartCapabilityDimension(
                "shop_timezone_binding",
                "🔴 **按店铺时区生效**（站点参数 → shop_marketplace → 店铺）",
                true,
                "shop_marketplace + marketplace.default_timezone（V1）",
                "",
                "时区定不了的店铺**不许激活策略** —— 用错时区会完全失效"),
            new AdDaypartCapabilityDimension(
                "recommended_curve",
                "按历史出单时段**自动推荐**分时曲线（上游 13.2.1.2 的本地小时分布）",
                true,
                "ad_daypart_recommendation（V393）+ reporting /reporting/time-pattern",
                "",
                "没有推荐曲线，运营只能凭感觉配 24 个小时"),
            new AdDaypartCapabilityDimension(
                "scheduled_adjustment_task",
                "定时调整任务：外部调度触发的一轮执行 + 逐条留痕",
                true,
                "ad_daypart_task + ad_daypart_task_line（V393）",
                "",
                "没有执行任务，曲线只是一张纸"),
            new AdDaypartCapabilityDimension(
                "guardrails",
                "调整上限（单次 / 总额 / 次数）+ 上下限保护 + 逐条拒绝留痕",
                true,
                "ad_daypart_strategy 上的五列上限 + ad_daypart_task_line.guardrail_code",
                "",
                "没有上限，一次异常就能烧掉大量预算"),
            new AdDaypartCapabilityDimension(
                "dry_run",
                "试运行：只算「本来会变成多少」，不真调",
                true,
                "ad_daypart_task.run_mode = DRY_RUN",
                "",
                "没有试运行，第一次跑就真调钱"),
            new AdDaypartCapabilityDimension(
                "downstream_cap_handoff",
                "把调整上限交给下游 14.2.2.2 自动规则（两边取更严的）",
                true,
                "GET /channel/ad-dayparts/caps",
                "",
                "没有这条通道，两边各有一套上限，谁也不服谁"),
            new AdDaypartCapabilityDimension(
                "campaign_state_toggle",
                "把活动状态改成 PAUSED / ENABLED（表达「只在某个小时停投」）",
                false,
                "—",
                "14.1.1.1 活动列表（已有状态字段）",
                "「停投」在本模块里 = **预算系数置 0**（该小时不再花钱），**不改活动状态**："
                    + "活动状态是活动级的，个状态表达不了「只在 15:00 停投」；"
                    + "而且把活动置 PAUSED 会**覆盖人工暂停**（那是人的决定，机器不该替他撤销）"),
            new AdDaypartCapabilityDimension(
                "platform_writeback",
                "把改后的预算 / 竞价**回写平台**",
                false,
                "—",
                "平台广告 API 回写队列（未交付）",
                "执行任务只落**本地**并标记「待回写平台」；"
                    + "界面上不把「本地已改」说成「平台侧已改」"),
            new AdDaypartCapabilityDimension(
                "weekday_dimension",
                "按星期几再分一层曲线（工作日 / 周末不同价）",
                false,
                "—",
                "14.2.1.3（未交付，卷 14 的下一项）",
                "本模块的曲线是**按小时的**；蓝图的分时职责句只说「按小时」。"
                    + "要按星期分，需要先有「周几」这一层配置 —— 本模块**不编一个出来充数**"),
            new AdDaypartCapabilityDimension(
                "per_platform_curve",
                "跨平台统一曲线（不同平台的竞价机制不同）",
                false,
                "—",
                "14.3.1.1 活动报表（口径未统一）",
                "当前曲线按**店铺**配置，不按平台；"
                    + "跨平台混用同一套系数会把「竞价机制不同」这件事糊掉")),
        STATUSES,
        SCOPES,
        ACTIONS,
        RUN_MODES,
        TRIGGER_SOURCES,
        UPSTREAM_STATUSES,
        TIMEZONE_NOTE,
        MULTIPLIER_NOTE,
        GUARDRAIL_NOTE,
        EXECUTION_NOTE,
        UPSTREAM_NOTE,
        BOUNDARY_NOTE);
  }

  // ==================================================================
  // 店铺时区（🔴 本模块的命门）
  // ==================================================================

  /**
   * 解析某个店铺的时区归属。
   *
   * <p>🔴 唯一口径：<b>该店铺启用的站点的 default_timezone</b>。
   * 启用 1 个 ⇒ 定得了；多个但时区相同 ⇒ 定得了；多个且时区不同 / 没启用 ⇒ <b>定不了</b>。
   */
  public AdDaypartShopTimezone shopTimezone(String shopCode) {
    UUID tenant = currentTenant();
    Record row =
        sql.fetchOne(
            "select s.id as shop_id, s.code as shop_code, s.name as shop_name,"
                + " count(m.id) as site_count,"
                + " count(distinct m.default_timezone) as tz_count,"
                + " min(m.default_timezone) as tz,"
                + " coalesce(string_agg(distinct m.default_timezone, ', ' order by m.default_timezone),'') as tz_list"
                + " from shop s"
                + " left join shop_marketplace sm"
                + "   on sm.tenant_id = s.tenant_id and sm.shop_id = s.id and sm.status = 'ACTIVE'"
                + " left join marketplace m on m.id = sm.marketplace_id"
                + " where s.tenant_id = "
                + TENANT
                + " and s.code = ?"
                + " group by s.id, s.code, s.name",
            shopCode);
    if (row == null) {
      throw notFound("店铺");
    }
    return toShopTimezone(row);
  }

  /** 把一行店铺 + 站点聚合结果翻译成时区归属结论。 */
  private static AdDaypartShopTimezone toShopTimezone(Record row) {
    int siteCount = row.get("site_count", Integer.class) == null ? 0 : row.get("site_count", Integer.class);
    int tzCount = row.get("tz_count", Integer.class) == null ? 0 : row.get("tz_count", Integer.class);
    String tz = textOrEmpty(row.get("tz", String.class));
    String tzList = textOrEmpty(row.get("tz_list", String.class));
    String status;
    String reason;
    if (siteCount == 0) {
      status = "UNRESOLVED";
      reason = "该店铺没有启用任何站点（shop_marketplace 为空）⇒ 定不了时区";
    } else if (tzCount > 1) {
      status = "UNRESOLVED";
      reason =
          "该店铺启用了 "
              + siteCount
              + " 个站点，但它们的默认时区不一致（"
              + tzList
              + "）⇒ 定不了时区，不能挑一个凑数";
    } else {
      status = "RESOLVED";
      reason = "";
    }
    return new AdDaypartShopTimezone(
        textOrEmpty(row.get("shop_code", String.class)),
        textOrEmpty(row.get("shop_name", String.class)),
        row.get("shop_id", UUID.class) == null ? "" : row.get("shop_id", UUID.class).toString(),
        status.equals("RESOLVED") ? tz : "",
        status,
        reason,
        siteCount,
        tzList.isEmpty() ? List.of() : List.of(tzList.split(", ")));
  }

  /** 列出本租户的店铺 + 它们的时区归属结论（选项下拉用；**定不了的也列出来**）。 */
  public List<AdDaypartShopTimezone> shopTimezones() {
    UUID tenant = currentTenant();
    return sql.fetch(
            "select s.id as shop_id, s.code as shop_code, s.name as shop_name,"
                + " count(m.id) as site_count,"
                + " count(distinct m.default_timezone) as tz_count,"
                + " min(m.default_timezone) as tz,"
                + " coalesce(string_agg(distinct m.default_timezone, ', ' order by m.default_timezone),'') as tz_list"
                + " from shop s"
                + " left join shop_marketplace sm"
                + "   on sm.tenant_id = s.tenant_id and sm.shop_id = s.id and sm.status = 'ACTIVE'"
                + " left join marketplace m on m.id = sm.marketplace_id"
                + " where s.tenant_id = "
                + TENANT
                + " group by s.id, s.code, s.name"
                + " order by s.code")
        .stream()
        .map(AdDaypartService::toShopTimezone)
        .toList();
  }

  // ==================================================================
  // 策略：增删改查
  // ==================================================================

  /** 策略列表（真分页 + 真筛选 + 真排序）。 */
  public AdDaypartStrategyPage strategies(
      String shopCode, String status, String keyword, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size <= 0 ? 50 : size, 200));
    int offset = Math.max(0, page) * limit;
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where st.tenant_id = " + TENANT);
    if (shopCode != null && !shopCode.isBlank()) {
      where.append(" and st.shop_code = ?");
      binds.add(shopCode.trim());
    }
    if (status != null && !status.isBlank()) {
      where.append(" and st.status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (st.strategy_code ilike ? or st.strategy_name ilike ?)");
      binds.add("%" + keyword.trim() + "%");
      binds.add("%" + keyword.trim() + "%");
    }
    long total = count("select count(*) from ad_daypart_strategy st" + where, binds.toArray());
    List<AdDaypartStrategySummary> items =
        sql.fetch(
                "select st.id, st.strategy_code, st.strategy_name, st.shop_code, st.shop_timezone,"
                    + " st.timezone_status, st.timezone_reason, st.scope, st.currency, st.status,"
                    + " st.dry_run, st.schedule_enabled, st.last_run_at, st.updated_at,"
                    + " s.name as shop_name"
                    + " from ad_daypart_strategy st"
                    + " left join shop s on s.tenant_id = st.tenant_id and s.code = st.shop_code"
                    + where
                    + " order by st.shop_code asc, st.strategy_code asc"
                    + " limit ? offset ?",
                concat(binds, limit, offset))
            .stream()
            .map(r -> toStrategySummary(r, effectiveHourCount(r.get("id", UUID.class))))
            .toList();
    return new AdDaypartStrategyPage(items, total, Math.max(0, page), limit);
  }

  /** 一条策略的详情（带 24 小时曲线）。 */
  public AdDaypartStrategyView strategy(String id) {
    UUID tenant = currentTenant();
    Record row = loadStrategyRow(parseId(id));
    return toStrategyView(row);
  }

  /** 新建一条策略（顺带把 24 个小时的曲线按 KEEP×1 铺好）。 */
  public AdDaypartStrategyView createStrategy(AdDaypartStrategyRequest request) {
    UUID tenant = currentTenant();
    String code = text(request.strategyCode());
    String name = text(request.strategyName());
    String shopCode = text(request.shopCode());
    if (code.isEmpty() || name.isEmpty() || shopCode.isEmpty()) {
      throw badRequest("策略编码、策略名、店铺编码都不能为空");
    }
    if (count("select count(*) from ad_daypart_strategy where tenant_id = " + TENANT + " and strategy_code = ?", code) > 0) {
      throw badRequest("策略编码已存在：" + code);
    }
    if (count("select count(*) from ad_daypart_strategy where tenant_id = " + TENANT + " and shop_code = ?", shopCode) > 0) {
      throw badRequest(
          "该店铺已经有一条分时策略（"
              + shopCode
              + "）—— 分时策略按店铺时区生效，跨店铺合并会让其中一部分的小时错位");
    }
    String currency = upper(request.currency());
    if (!currency.matches("^[A-Z]{3}$")) {
      throw badRequest("币种必须是 3 位大写字母");
    }
    String scope = request.scope() == null || request.scope().isBlank() ? "ALL_CAMPAIGNS" : upper(request.scope());
    if (!SCOPES.contains(scope)) {
      throw badRequest("作用范围只能是 ALL_CAMPAIGNS / CAMPAIGN");
    }
    UUID targetCampaignId = null;
    if (scope.equals("CAMPAIGN")) {
      if (request.targetCampaignId() == null || request.targetCampaignId().isBlank()) {
        throw badRequest("作用范围是 CAMPAIGN 时必须点名活动");
      }
      targetCampaignId = parseId(request.targetCampaignId());
      Record campaign =
          sql.fetchOne(
              "select id, shop_code from ad_campaign where tenant_id = " + TENANT + " and id = ?",
              targetCampaignId);
      if (campaign == null) {
        throw notFound("活动");
      }
      if (!shopCode.equals(campaign.get("shop_code", String.class))) {
        throw badRequest("指定的活动不属于该店铺");
      }
    }

    // 🔴 时区从**站点参数**解析（不硬编码、不猜）。
    var tz = shopTimezone(shopCode);

    UUID id = Ids.next();
    sql.execute(
        "insert into ad_daypart_strategy"
            + " (id, tenant_id, strategy_code, strategy_name, shop_code, shop_timezone,"
            + "  timezone_status, timezone_reason, scope, target_campaign_id, currency, status,"
            + "  single_adjust_cap_amount, single_adjust_cap_percent, total_cap_amount,"
            + "  total_cap_count, total_cap_window_hours, min_budget, max_budget, min_bid, max_bid,"
            + "  dry_run, schedule_enabled, run_interval_minutes,"
            + "  recommend_peak_share_multiple, recommend_trough_share_multiple, recommend_pause_share_multiple,"
            + "  recommend_boost_bid_multiplier, recommend_boost_budget_multiplier,"
            + "  recommend_reduce_bid_multiplier, recommend_reduce_budget_multiplier,"
            + "  recommend_pause_bid_multiplier, recommend_pause_budget_multiplier,"
            + "  note, version, created_at, updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),now())",
        id,
        tenant,
        code,
        name,
        shopCode,
        tz.timezoneStatus().equals("RESOLVED") ? tz.shopTimezone() : "",
        tz.timezoneStatus(),
        tz.timezoneReason(),
        scope,
        targetCampaignId,
        currency,
        "DRAFT",
        requirePositive(request.singleAdjustCapAmount(), "单次调整上限（金额）"),
        request.singleAdjustCapPercent() == null ? BigDecimal.ZERO : ratio(request.singleAdjustCapPercent()),
        requirePositive(request.totalCapAmount(), "总额上限"),
        request.totalCapCount() == null ? 50 : Math.max(1, request.totalCapCount()),
        request.totalCapWindowHours() == null ? 24 : Math.max(1, request.totalCapWindowHours()),
        request.minBudget(),
        request.maxBudget(),
        request.minBid(),
        request.maxBid(),
        request.dryRun() == null ? Boolean.TRUE : request.dryRun(),
        request.scheduleEnabled() == null ? Boolean.FALSE : request.scheduleEnabled(),
        request.runIntervalMinutes() == null ? 60 : Math.max(1, request.runIntervalMinutes()),
        request.recommendPeakShareMultiple() == null
            ? new BigDecimal("2.000000")
            : ratio(request.recommendPeakShareMultiple()),
        request.recommendTroughShareMultiple() == null
            ? new BigDecimal("0.500000")
            : ratio(request.recommendTroughShareMultiple()),
        request.recommendPauseShareMultiple() == null
            ? new BigDecimal("0.250000")
            : ratio(request.recommendPauseShareMultiple()),
        request.recommendBoostBidMultiplier() == null
            ? new BigDecimal("1.500000")
            : ratio(request.recommendBoostBidMultiplier()),
        request.recommendBoostBudgetMultiplier() == null
            ? new BigDecimal("1.500000")
            : ratio(request.recommendBoostBudgetMultiplier()),
        request.recommendReduceBidMultiplier() == null
            ? new BigDecimal("0.700000")
            : ratio(request.recommendReduceBidMultiplier()),
        request.recommendReduceBudgetMultiplier() == null
            ? new BigDecimal("0.700000")
            : ratio(request.recommendReduceBudgetMultiplier()),
        request.recommendPauseBidMultiplier() == null
            ? new BigDecimal("0.500000")
            : ratio(request.recommendPauseBidMultiplier()),
        request.recommendPauseBudgetMultiplier() == null
            ? BigDecimal.ZERO : ratio(request.recommendPauseBudgetMultiplier()),
        text(request.note()),
        0L);

    // 🔴 24 个小时的曲线铺好（缺一个小时的策略在 DB 层就建不出来）。
    for (int hour = 0; hour < 24; hour++) {
      sql.execute(
          "insert into ad_daypart_strategy_hour"
              + " (id, tenant_id, strategy_id, local_hour, action, bid_multiplier, budget_multiplier,"
              + "  source, basis_note, updated_at)"
              + " values (?,?,?,?,'KEEP',1,1,'MANUAL','',now())",
          Ids.next(),
          tenant,
          id,
          hour);
    }
    return strategy(id.toString());
  }

  /** 改一条策略（**曲线不在这里**，曲线走整体替换端点）。 */
  public AdDaypartStrategyView updateStrategy(String id, AdDaypartStrategyRequest request) {
    UUID tenant = currentTenant();
    UUID strategyId = parseId(id);
    loadStrategyRow(strategyId);
    String name = text(request.strategyName());
    String currency = upper(request.currency());
    if (name.isEmpty()) {
      throw badRequest("策略名不能为空");
    }
    if (!currency.matches("^[A-Z]{3}$")) {
      throw badRequest("币种必须是 3 位大写字母");
    }
    String scope = request.scope() == null || request.scope().isBlank() ? "ALL_CAMPAIGNS" : upper(request.scope());
    if (!SCOPES.contains(scope)) {
      throw badRequest("作用范围只能是 ALL_CAMPAIGNS / CAMPAIGN");
    }
    UUID targetCampaignId = null;
    if (scope.equals("CAMPAIGN")) {
      if (request.targetCampaignId() == null || request.targetCampaignId().isBlank()) {
        throw badRequest("作用范围是 CAMPAIGN 时必须点名活动");
      }
      targetCampaignId = parseId(request.targetCampaignId());
      Record campaign =
          sql.fetchOne(
              "select id, shop_code from ad_campaign where tenant_id = " + TENANT + " and id = ?",
              targetCampaignId);
      if (campaign == null) {
        throw notFound("活动");
      }
    }
    // 🔴 每次保存都**重新解析店铺时区**（站点的配置会变，策略不能拿着旧时区跑）。
    Record shopRow = loadStrategyRow(strategyId);
    var tz = shopTimezone(shopRow.get("shop_code", String.class));
    sql.execute(
        "update ad_daypart_strategy set strategy_name=?, currency=?, scope=?, target_campaign_id=?,"
            + " shop_timezone=?, timezone_status=?, timezone_reason=?,"
            + " single_adjust_cap_amount=?, single_adjust_cap_percent=?, total_cap_amount=?,"
            + " total_cap_count=?, total_cap_window_hours=?, min_budget=?, max_budget=?, min_bid=?, max_bid=?,"
            + " dry_run=?, schedule_enabled=?, run_interval_minutes=?,"
            + " recommend_peak_share_multiple=?, recommend_trough_share_multiple=?, recommend_pause_share_multiple=?,"
            + " recommend_boost_bid_multiplier=?, recommend_boost_budget_multiplier=?,"
            + " recommend_reduce_bid_multiplier=?, recommend_reduce_budget_multiplier=?,"
            + " recommend_pause_bid_multiplier=?, recommend_pause_budget_multiplier=?,"
            + " note=?, version=version+1, updated_at=now()"
            + " where tenant_id = " + TENANT + " and id = ?",
        name,
        currency,
        scope,
        targetCampaignId,
        tz.timezoneStatus().equals("RESOLVED") ? tz.shopTimezone() : "",
        tz.timezoneStatus(),
        tz.timezoneReason(),
        requirePositive(request.singleAdjustCapAmount(), "单次调整上限（金额）"),
        request.singleAdjustCapPercent() == null ? BigDecimal.ZERO : ratio(request.singleAdjustCapPercent()),
        requirePositive(request.totalCapAmount(), "总额上限"),
        request.totalCapCount() == null ? 50 : Math.max(1, request.totalCapCount()),
        request.totalCapWindowHours() == null ? 24 : Math.max(1, request.totalCapWindowHours()),
        request.minBudget(),
        request.maxBudget(),
        request.minBid(),
        request.maxBid(),
        request.dryRun() == null ? Boolean.TRUE : request.dryRun(),
        request.scheduleEnabled() == null ? Boolean.FALSE : request.scheduleEnabled(),
        request.runIntervalMinutes() == null ? 60 : Math.max(1, request.runIntervalMinutes()),
        request.recommendPeakShareMultiple() == null
            ? new BigDecimal("2.000000")
            : ratio(request.recommendPeakShareMultiple()),
        request.recommendTroughShareMultiple() == null
            ? new BigDecimal("0.500000")
            : ratio(request.recommendTroughShareMultiple()),
        request.recommendPauseShareMultiple() == null
            ? new BigDecimal("0.250000")
            : ratio(request.recommendPauseShareMultiple()),
        request.recommendBoostBidMultiplier() == null
            ? new BigDecimal("1.500000")
            : ratio(request.recommendBoostBidMultiplier()),
        request.recommendBoostBudgetMultiplier() == null
            ? new BigDecimal("1.500000")
            : ratio(request.recommendBoostBudgetMultiplier()),
        request.recommendReduceBidMultiplier() == null
            ? new BigDecimal("0.700000")
            : ratio(request.recommendReduceBidMultiplier()),
        request.recommendReduceBudgetMultiplier() == null
            ? new BigDecimal("0.700000")
            : ratio(request.recommendReduceBudgetMultiplier()),
        request.recommendPauseBidMultiplier() == null
            ? new BigDecimal("0.500000")
            : ratio(request.recommendPauseBidMultiplier()),
        request.recommendPauseBudgetMultiplier() == null
            ? BigDecimal.ZERO : ratio(request.recommendPauseBudgetMultiplier()),
        text(request.note()),
        strategyId);
    return strategy(id);
  }

  /** 整体替换曲线（一次成型，避免逐条改时中间态缺小时）。 */
  public AdDaypartStrategyView replaceCurve(String id, AdDaypartCurveRequest request) {
    UUID tenant = currentTenant();
    UUID strategyId = parseId(id);
    loadStrategyRow(strategyId);
    List<AdDaypartHourInput> hours = request.hours() == null ? List.of() : request.hours();
    if (hours.isEmpty()) {
      throw badRequest("曲线不能为空：缺一个小时，那个小时就落进「没有配置」的空洞");
    }
    Map<Integer, AdDaypartHourInput> byHour = new LinkedHashMap<>();
    for (AdDaypartHourInput input : hours) {
      if (input.localHour() == null || input.localHour() < 0 || input.localHour() > 23) {
        throw badRequest("小时必须在 0..23 之间");
      }
      if (byHour.put(input.localHour(), input) != null) {
        throw badRequest("小时 " + input.localHour() + " 重复了");
      }
    }
    for (int hour = 0; hour < 24; hour++) {
      if (!byHour.containsKey(hour)) {
        throw badRequest(
            "曲线必须覆盖 0..23 全部 24 个小时（缺 " + hour + "）—— "
                + "缺一个小时，那个小时就落进「没有配置」的空洞");
      }
    }
    for (AdDaypartHourInput input : hours) {
      String action = upper(input.action());
      if (!ACTIONS.contains(action)) {
        throw badRequest("动作只能是 KEEP / BOOST / REDUCE / PAUSE（收到「" + action + "」）");
      }
      BigDecimal bid = input.bidMultiplier() == null ? BigDecimal.ONE : ratio(input.bidMultiplier());
      BigDecimal budget =
          input.budgetMultiplier() == null ? BigDecimal.ONE : ratio(input.budgetMultiplier());
      if (bid.signum() <= 0) {
        throw badRequest("竞价系数必须大于 0（小时 " + input.localHour() + "）");
      }
      if (budget.signum() < 0) {
        throw badRequest("预算系数不能为负（小时 " + input.localHour() + "）");
      }
      if (action.equals("PAUSE") && budget.signum() != 0) {
        throw badRequest(
            "动作是 PAUSE（停投）时预算系数必须是 0（小时 " + input.localHour() + "）");
      }
      sql.execute(
          "update ad_daypart_strategy_hour set action=?, bid_multiplier=?, budget_multiplier=?,"
              + " source='MANUAL', basis_note=?, updated_at=now()"
              + " where tenant_id = " + TENANT + " and strategy_id = ? and local_hour = ?",
          action,
          bid,
          budget,
          text(input.basisNote()),
          strategyId,
          input.localHour());
    }
    sql.execute(
        "update ad_daypart_strategy set version=version+1, updated_at=now()"
            + " where tenant_id = " + TENANT + " and id = ?",
        strategyId);
    return strategy(id);
  }

  /** 改策略状态（激活 / 暂停 / 归档）。 */
  public AdDaypartStrategyView changeStatus(String id, AdDaypartStatusRequest request) {
    UUID tenant = currentTenant();
    UUID strategyId = parseId(id);
    loadStrategyRow(strategyId);
    String status = upper(request.status());
    if (!STATUSES.contains(status)) {
      throw badRequest("状态只能是 DRAFT / ACTIVE / PAUSED / ARCHIVED");
    }
    if (status.equals("ACTIVE")) {
      Record row = loadStrategyRow(strategyId);
      String tzStatus = row.get("timezone_status", String.class);
      if (!"RESOLVED".equals(tzStatus) || text(row.get("shop_timezone", String.class)).isEmpty()) {
        throw badRequest(
            "策略激活不了："
                + row.get("shop_code", String.class)
                + " 的店铺时区**定不了**（"
                + text(row.get("timezone_reason", String.class))
                + "）—— "
                + "跨时区店铺用错时区会完全失效，宁可不激活");
      }
      int effective =
          effectiveHourCount(strategyId);
      if (effective == 0) {
        throw badRequest("曲线 24 个小时全是「不动」，激活它等于什么都没配");
      }
    }
    sql.execute(
        "update ad_daypart_strategy set status=?, note=case when ?<>'' then ? else note end,"
            + " version=version+1, updated_at=now()"
            + " where tenant_id = " + TENANT + " and id = ?",
        status,
        text(request.note()),
        text(request.note()),
        strategyId);
    return strategy(id);
  }

  /** 归档一条策略（软删除；归档后曲线与任务历史都还在，可复盘）。 */
  public AdDaypartStrategyView archiveStrategy(String id) {
    return changeStatus(id, new AdDaypartStatusRequest("ARCHIVED", "归档"));
  }

  // ==================================================================
  // 推荐曲线（上游 13.2.1.2）
  // ==================================================================

  /** 生成一批推荐曲线（**逐店铺**取上游的本地小时分布）。 */
  public AdDaypartRecommendationView generateRecommendation(
      AdDaypartRecommendationRequest request, String bearer) {
    UUID tenant = currentTenant();
    if (request.strategyId() == null || request.strategyId().isBlank()) {
      throw badRequest("必须指定策略 id（推荐按店铺生成）");
    }
    UUID strategyId = parseId(request.strategyId());
    Record strategyRow = loadStrategyRow(strategyId);
    String shopCode = strategyRow.get("shop_code", String.class);

    LocalDate to = request.to() == null ? dbToday() : request.to();
    LocalDate from = request.from() == null ? to.minusDays(DEFAULT_WINDOW_DAYS - 1L) : request.from();
    if (from.isAfter(to)) {
      throw badRequest("起始日期不能晚于结束日期（from=" + from + "、to=" + to + "）");
    }
    int days = (int) (to.toEpochDay() - from.toEpochDay() + 1);
    if (days > MAX_WINDOW_DAYS) {
      throw badRequest(
          "窗口最多 " + MAX_WINDOW_DAYS + " 天（当前 " + days + " 天）—— "
              + "一次推荐要逐店拉上游，窗口太长会把上游压垮");
    }

    // 🔴 本服务这一侧的时区结论（站点参数 → shop_marketplace）。
    var localTz = shopTimezone(shopCode);

    // 🔴 上游那一侧的时区档案（**上游读不到 ⇒ 503**，由端口抛）。
    var upstreamShops = source.shops(bearer);
    var upstreamProfile =
        upstreamShops.stream().filter(s -> shopCode.equals(s.shopCode())).findFirst().orElse(null);

    String status;
    String reason;
    String timezone = "";
    if (!"RESOLVED".equals(localTz.timezoneStatus())) {
      status = "SHOP_UNRESOLVED";
      reason = localTz.timezoneReason();
    } else if (upstreamProfile == null) {
      status = "SHOP_UNRESOLVED";
      reason = "上游没有该店铺的时区档案 ⇒ 无法核对时区归属，不产出曲线";
    } else if (!"RESOLVED".equals(upstreamProfile.timezoneStatus())) {
      status = "SHOP_UNRESOLVED";
      reason =
          "上游也定不了该店铺的时区（"
              + upstreamProfile.timezoneReason()
              + "）⇒ 不产出曲线";
    } else if (!localTz.shopTimezone().equals(upstreamProfile.shopTimezone())) {
      status = "CONFLICT";
      reason =
          "两侧时区不一致（本服务 "
              + localTz.shopTimezone()
              + " / 上游 "
              + upstreamProfile.shopTimezone()
              + "）⇒ 上游的小时桶在本服务这边会被读成错误的小时，不产出曲线";
    } else {
      status = "OK";
      reason = "";
      timezone = localTz.shopTimezone();
    }

    var profile = profileSnapshot(strategyRow);

    if (!"OK".equals(status)) {
      return upsertRecommendation(
          tenant,
          strategyId,
          shopCode,
          status,
          reason,
          "",
          from,
          to,
          profile,
          List.of(),
          BigDecimal.ZERO,
          0,
          0,
          null,
          "");
    }

    var distribution = source.distribution(shopCode, from, to, bearer);
    if (!distribution.available()) {
      return upsertRecommendation(
          tenant,
          strategyId,
          shopCode,
          "NO_FACT",
          distribution.message(),
          timezone,
          from,
          to,
          profile,
          List.of(),
          BigDecimal.ZERO,
          0,
          0,
          null,
          distribution.message());
    }

    var hours = computeRecommendation(strategyRow, distribution);
    return upsertRecommendation(
        tenant,
        strategyId,
        shopCode,
        "OK",
        "",
        timezone,
        from,
        to,
        profile,
        hours,
        distribution.projectedOrderCount(),
        distribution.projectedDays(),
        distribution.expectedDays(),
        distribution.dataUpdatedAt(),
        "");
  }

  /**
   * 按判定参数算出 24 个小时的建议。
   *
   * <p>🔴 判定口径（参数在策略上，可改；生成时快照进批次 ⇒ 可复算）：
   *
   * <ul>
   *   <li>均值占比 = 1/24（占比之和恒为 1 ⇒ 均匀分布下每个小时就是 1/24）。
   *   <li><b>BOOST</b>：占比 ≥ 峰值倍数 × 均值占比，**或**上游的 P90 高峰刻度命中。
   *   <li><b>PAUSE</b>：占比 ≤ 停投倍数 × 均值占比（比 REDUCE 更深的一档）。
   *   <li><b>REDUCE</b>：占比 ≤ 低谷倍数 × 均值占比。
   *   <li>其余 <b>KEEP</b>。
   * </ul>
   *
   * <p>🔴 占比<b>不可比</b>（null，分母为 0）时**不判高低**，一律 KEEP —— 「判不了」不是「低」。
   */
  private List<AdDaypartRecommendationHourView> computeRecommendation(
      Record strategyRow, AdDaypartSourcePort.ShopDistribution distribution) {
    BigDecimal peakMultiple = strategyRow.get("recommend_peak_share_multiple", BigDecimal.class);
    BigDecimal troughMultiple = strategyRow.get("recommend_trough_share_multiple", BigDecimal.class);
    BigDecimal pauseMultiple = strategyRow.get("recommend_pause_share_multiple", BigDecimal.class);
    BigDecimal boostBid = strategyRow.get("recommend_boost_bid_multiplier", BigDecimal.class);
    BigDecimal boostBudget = strategyRow.get("recommend_boost_budget_multiplier", BigDecimal.class);
    BigDecimal reduceBid = strategyRow.get("recommend_reduce_bid_multiplier", BigDecimal.class);
    BigDecimal reduceBudget = strategyRow.get("recommend_reduce_budget_multiplier", BigDecimal.class);
    BigDecimal pauseBid = strategyRow.get("recommend_pause_bid_multiplier", BigDecimal.class);
    BigDecimal pauseBudget = strategyRow.get("recommend_pause_budget_multiplier", BigDecimal.class);

    BigDecimal average = AVERAGE_SHARE;
    BigDecimal peakThreshold = average.multiply(nz(peakMultiple));
    BigDecimal troughThreshold = average.multiply(nz(troughMultiple));
    BigDecimal pauseThreshold = average.multiply(nz(pauseMultiple));

    Map<Integer, AdDaypartSourcePort.HourBucket> byHour = new LinkedHashMap<>();
    for (var bucket : distribution.hourDistribution()) {
      byHour.put(bucket.hour(), bucket);
    }

    List<AdDaypartRecommendationHourView> hours = new ArrayList<>();
    for (int hour = 0; hour < 24; hour++) {
      var bucket = byHour.get(hour);
      BigDecimal orderCount =
          bucket == null ? BigDecimal.ZERO : nz(bucket.orderCount());
      BigDecimal orderShare = bucket == null ? null : bucket.orderShare();
      boolean p90 = bucket != null && bucket.p90Peak();

      String action = "KEEP";
      BigDecimal bid = BigDecimal.ONE;
      BigDecimal budget = BigDecimal.ONE;
      String basis;

      if (orderShare == null) {
        // 🔴 不可比 ⇒ 不判高低（「判不了」不是「低」）。
        basis = "该小时的订单占比不可比（分母为 0）⇒ 不判高低，按「不动」处理";
      } else {
        int cmpPeak = orderShare.compareTo(peakThreshold);
        int cmpPause = orderShare.compareTo(pauseThreshold);
        int cmpTrough = orderShare.compareTo(troughThreshold);
        if (cmpPeak >= 0 || p90) {
          action = "BOOST";
          bid = nz(boostBid);
          budget = nz(boostBudget);
          basis =
              "订单占比 "
                  + orderShare.stripTrailingZeros().toPlainString()
                  + "（阈值 "
                  + peakThreshold.stripTrailingZeros().toPlainString()
                  + "）"
                  + (p90 ? "，且命中上游 P90 高峰刻度" : "")
                  + " ⇒ 高峰：提价提预算";
        } else if (cmpPause <= 0) {
          action = "PAUSE";
          bid = nz(pauseBid);
          budget = nz(pauseBudget);
          basis =
              "订单占比 "
                  + orderShare.stripTrailingZeros().toPlainString()
                  + "（停投阈值 "
                  + pauseThreshold.stripTrailingZeros().toPlainString()
                  + "）⇒ 最深的低谷：停投";
        } else if (cmpTrough <= 0) {
          action = "REDUCE";
          bid = nz(reduceBid);
          budget = nz(reduceBudget);
          basis =
              "订单占比 "
                  + orderShare.stripTrailingZeros().toPlainString()
                  + "（低谷阈值 "
                  + troughThreshold.stripTrailingZeros().toPlainString()
                  + "）⇒ 低谷：降价";
        } else {
          basis =
              "订单占比 "
                  + orderShare.stripTrailingZeros().toPlainString()
                  + "落在峰值 "
                  + peakThreshold.stripTrailingZeros().toPlainString()
                  + " 与低谷 "
                  + troughThreshold.stripTrailingZeros().toPlainString()
                  + " 之间 ⇒ 不动";
        }
      }
      hours.add(
          new AdDaypartRecommendationHourView(
              hour,
              orderCount,
              orderShare,
              p90,
              action,
              ratio(bid),
              ratio(budget),
              basis));
    }
    return hours;
  }

  /** 判定参数快照（jsonb）：生成这一批时用的**每一个**参数。 */
  private static Map<String, Object> profileSnapshot(Record strategyRow) {
    Map<String, Object> profile = new LinkedHashMap<>();
    profile.put("peakShareMultiple", strategyRow.get("recommend_peak_share_multiple", BigDecimal.class));
    profile.put("troughShareMultiple", strategyRow.get("recommend_trough_share_multiple", BigDecimal.class));
    profile.put("pauseShareMultiple", strategyRow.get("recommend_pause_share_multiple", BigDecimal.class));
    profile.put("boostBidMultiplier", strategyRow.get("recommend_boost_bid_multiplier", BigDecimal.class));
    profile.put("boostBudgetMultiplier", strategyRow.get("recommend_boost_budget_multiplier", BigDecimal.class));
    profile.put("reduceBidMultiplier", strategyRow.get("recommend_reduce_bid_multiplier", BigDecimal.class));
    profile.put("reduceBudgetMultiplier", strategyRow.get("recommend_reduce_budget_multiplier", BigDecimal.class));
    profile.put("pauseBidMultiplier", strategyRow.get("recommend_pause_bid_multiplier", BigDecimal.class));
    profile.put("pauseBudgetMultiplier", strategyRow.get("recommend_pause_budget_multiplier", BigDecimal.class));
    profile.put("averageShare", AVERAGE_SHARE);
    profile.put(
        "peakThreshold",
        AVERAGE_SHARE.multiply(nz(strategyRow.get("recommend_peak_share_multiple", BigDecimal.class))));
    profile.put(
        "troughThreshold",
        AVERAGE_SHARE.multiply(nz(strategyRow.get("recommend_trough_share_multiple", BigDecimal.class))));
    profile.put(
        "pauseThreshold",
        AVERAGE_SHARE.multiply(nz(strategyRow.get("recommend_pause_share_multiple", BigDecimal.class))));
    return profile;
  }

  /** 落一批推荐（同策略同窗口走 upsert）。 */
  private AdDaypartRecommendationView upsertRecommendation(
      UUID tenant,
      UUID strategyId,
      String shopCode,
      String upstreamStatus,
      String upstreamMessage,
      String timezone,
      LocalDate from,
      LocalDate to,
      Map<String, Object> profile,
      List<AdDaypartRecommendationHourView> hours,
      BigDecimal projectedOrderCount,
      int projectedDays,
      int expectedDays,
      OffsetDateTime dataUpdatedAt,
      String note) {
    Record existingBatch =
        sql.fetchOne(
            "select id from ad_daypart_recommendation where tenant_id = "
                + TENANT
                + " and strategy_id = ? and window_from = ? and window_to = ?",
            strategyId,
            from,
            to);
    UUID id = existingBatch == null ? Ids.next() : existingBatch.get("id", UUID.class);
    sql.execute(
        "insert into ad_daypart_recommendation"
            + " (id, tenant_id, strategy_id, shop_code, shop_timezone, timezone_status, timezone_reason,"
            + "  window_from, window_to, alignment, profile, upstream_status, upstream_data_updated_at,"
            + "  upstream_message, projected_order_count, projected_days, expected_days, generated_at)"
            + " values (?,?,?,?,?,?,?,?,?,'LOCAL_TIME',?::jsonb,?,?::timestamptz,?,?,?,?,now())"
            + " on conflict (tenant_id, strategy_id, window_from, window_to) do update set"
            + "  shop_timezone=excluded.shop_timezone, timezone_status=excluded.timezone_status,"
            + "  timezone_reason=excluded.timezone_reason, profile=excluded.profile,"
            + "  upstream_status=excluded.upstream_status,"
            + "  upstream_data_updated_at=excluded.upstream_data_updated_at,"
            + "  upstream_message=excluded.upstream_message,"
            + "  projected_order_count=excluded.projected_order_count,"
            + "  projected_days=excluded.projected_days, expected_days=excluded.expected_days,"
            + "  generated_at=now()",
        id,
        tenant,
        strategyId,
        shopCode,
        timezone,
        upstreamStatus.equals("OK") ? "RESOLVED" : (upstreamStatus.equals("CONFLICT") ? "CONFLICT" : "UNRESOLVED"),
        upstreamMessage,
        from,
        to,
        writeJson(profile),
        upstreamStatus,
        dataUpdatedAt,
        note == null ? upstreamMessage : note,
        projectedOrderCount,
        projectedDays,
        expectedDays);
    sql.execute(
        "delete from ad_daypart_recommendation_hour where tenant_id = " + TENANT + " and recommendation_id = ?",
        id);
    for (AdDaypartRecommendationHourView hour : hours) {
      sql.execute(
          "insert into ad_daypart_recommendation_hour"
              + " (id, tenant_id, recommendation_id, local_hour, order_count, order_share, p90_peak,"
              + "  suggested_action, suggested_bid_multiplier, suggested_budget_multiplier, basis_note)"
              + " values (?,?,?,?,?,?,?,?,?,?,?)",
          Ids.next(),
          tenant,
          id,
          hour.localHour(),
          hour.orderCount(),
          hour.orderShare(),
          hour.p90Peak(),
          hour.suggestedAction(),
          hour.suggestedBidMultiplier(),
          hour.suggestedBudgetMultiplier(),
          hour.basisNote());
    }
    return recommendation(id.toString());
  }

  /** 一批推荐的详情。 */
  public AdDaypartRecommendationView recommendation(String id) {
    UUID tenant = currentTenant();
    UUID recommendationId = parseId(id);
    Record row =
        sql.fetchOne(
            "select r.*, s.name as shop_name from ad_daypart_recommendation r"
                + " left join shop s on s.tenant_id = r.tenant_id and s.code = r.shop_code"
                + " where r.tenant_id = " + TENANT + " and r.id = ?",
            recommendationId);
    if (row == null) {
      throw notFound("推荐批次");
    }
    List<AdDaypartRecommendationHourView> hours =
        sql.fetch(
                "select local_hour, order_count, order_share, p90_peak, suggested_action,"
                    + " suggested_bid_multiplier, suggested_budget_multiplier, basis_note"
                    + " from ad_daypart_recommendation_hour"
                    + " where tenant_id = " + TENANT + " and recommendation_id = ?"
                    + " order by local_hour",
                recommendationId)
            .stream()
            .map(
                h ->
                    new AdDaypartRecommendationHourView(
                        h.get("local_hour", Integer.class),
                        h.get("order_count", BigDecimal.class),
                        h.get("order_share", BigDecimal.class),
                        Boolean.TRUE.equals(h.get("p90_peak", Boolean.class)),
                        h.get("suggested_action", String.class),
                        h.get("suggested_bid_multiplier", BigDecimal.class),
                        h.get("suggested_budget_multiplier", BigDecimal.class),
                        h.get("basis_note", String.class)))
            .toList();
    return new AdDaypartRecommendationView(
        recommendationId.toString(),
        text(row.get("strategy_id", UUID.class).toString()),
        text(row.get("shop_code", String.class)),
        textOrEmpty(row.get("shop_name", String.class)),
        text(row.get("shop_timezone", String.class)),
        text(row.get("timezone_status", String.class)),
        text(row.get("timezone_reason", String.class)),
        row.get("window_from", LocalDate.class),
        row.get("window_to", LocalDate.class),
        text(row.get("alignment", String.class)),
        readJson(row.get("profile", String.class)),
        text(row.get("upstream_status", String.class)),
        row.get("upstream_data_updated_at", OffsetDateTime.class),
        text(row.get("upstream_message", String.class)),
        nz(row.get("projected_order_count", BigDecimal.class)),
        row.get("projected_days", Integer.class) == null ? 0 : row.get("projected_days", Integer.class),
        row.get("expected_days", Integer.class) == null ? 0 : row.get("expected_days", Integer.class),
        hours,
        row.get("generated_at", OffsetDateTime.class));
  }

  /** 列出某条策略的推荐批次。 */
  public List<AdDaypartRecommendationView> recommendations(String strategyId) {
    UUID tenant = currentTenant();
    UUID id = parseId(strategyId);
    loadStrategyRow(id);
    return sql.fetch(
            "select id from ad_daypart_recommendation where tenant_id = "
                + TENANT
                + " and strategy_id = ? order by generated_at desc",
            id)
        .stream()
        .map(r -> recommendation(r.get("id", UUID.class).toString()))
        .toList();
  }

  /** 把推荐曲线套到策略上（**逐小时**写，来源标成 RECOMMENDED）。 */
  public AdDaypartStrategyView applyRecommendation(
      String recommendationId, AdDaypartApplyRecommendationRequest request) {
    UUID tenant = currentTenant();
    UUID id = parseId(recommendationId);
    Record batch =
        sql.fetchOne(
            "select * from ad_daypart_recommendation where tenant_id = " + TENANT + " and id = ?",
            id);
    if (batch == null) {
      throw notFound("推荐批次");
    }
    String upstreamStatus = text(batch.get("upstream_status", String.class));
    if (!"OK".equals(upstreamStatus)) {
      throw badRequest(
          "这一批没有曲线可套（上游结论："
              + upstreamStatus
              + "，"
              + text(batch.get("upstream_message", String.class))
              + "）");
    }
    UUID strategyId = batch.get("strategy_id", UUID.class);
    List<Integer> wanted =
        request.hours() == null || request.hours().isEmpty()
            ? java.util.stream.IntStream.range(0, 24).boxed().toList()
            : request.hours();
    int changed = 0;
    for (Integer hour : wanted) {
      if (hour == null || hour < 0 || hour > 23) {
        throw badRequest("小时必须在 0..23 之间");
      }
      Record h =
          sql.fetchOne(
              "select suggested_action, suggested_bid_multiplier, suggested_budget_multiplier, basis_note"
                  + " from ad_daypart_recommendation_hour"
                  + " where tenant_id = " + TENANT + " and recommendation_id = ? and local_hour = ?",
              id,
              hour);
      if (h == null) {
        throw notFound("推荐批次的小时 " + hour);
      }
      sql.execute(
          "update ad_daypart_strategy_hour set action=?, bid_multiplier=?, budget_multiplier=?,"
              + " source='RECOMMENDED', basis_note=?, updated_at=now()"
              + " where tenant_id = " + TENANT + " and strategy_id = ? and local_hour = ?",
          h.get("suggested_action", String.class),
          h.get("suggested_bid_multiplier", BigDecimal.class),
          h.get("suggested_budget_multiplier", BigDecimal.class),
          text(h.get("basis_note", String.class)),
          strategyId,
          hour);
      changed++;
    }
    sql.execute(
        "update ad_daypart_strategy set version=version+1, updated_at=now()"
            + " where tenant_id = " + TENANT + " and id = ?",
        strategyId);
    var view = strategy(strategyId.toString());
    return view;
  }

  // ==================================================================
  // 定时调整任务（外部调度触发的一次运行入口）
  // ==================================================================

  /** 跑一轮：给定的策略，或所有「参与调度且生效中」的策略。 */
  public AdDaypartRunResult run(AdDaypartRunRequest request, String bearer) {
    UUID tenant = currentTenant();
    String runMode = request.runMode() == null || request.runMode().isBlank() ? "DRY_RUN" : upper(request.runMode());
    if (!RUN_MODES.contains(runMode)) {
      throw badRequest("运行口径只能是 DRY_RUN / APPLY");
    }
    String triggerSource =
        request.triggerSource() == null || request.triggerSource().isBlank()
            ? "MANUAL"
            : upper(request.triggerSource());
    if (!TRIGGER_SOURCES.contains(triggerSource)) {
      throw badRequest("触发来源只能是 MANUAL / SCHEDULED");
    }
    List<Record> targets;
    if (request.strategyId() != null && !request.strategyId().isBlank()) {
      targets = List.of(loadStrategyRow(parseId(request.strategyId())));
    } else {
      targets =
          sql.fetch(
              "select * from ad_daypart_strategy where tenant_id = "
                  + TENANT
                  + " and status = 'ACTIVE' and schedule_enabled = true"
                  + " order by shop_code");
    }

    List<AdDaypartTaskView> tasks = new ArrayList<>();
    List<AdDaypartRunSkip> skips = new ArrayList<>();
    for (Record strategyRow : targets) {
      String outcome = runOne(tenant, strategyRow, runMode, triggerSource, text(request.note()), tasks, skips);
      if (outcome == null) {
        // 已经在 tasks 里了
        continue;
      }
    }
    return new AdDaypartRunResult(
        targets.size(),
        (int) tasks.stream().filter(t -> !"SKIPPED".equals(t.status())).count(),
        skips.size(),
        tasks,
        skips);
  }
  /** 跑一条策略（返回 "DONE" 表示已经落进 tasks，"SKIPPED" 表示进了 skips）。 */
  private String runOne(
      UUID tenant,
      Record strategyRow,
      String runMode,
      String triggerSource,
      String note,
      List<AdDaypartTaskView> tasks,
      List<AdDaypartRunSkip> skips) {
    UUID strategyId = strategyRow.get("id", UUID.class);
    String strategyCode = strategyRow.get("strategy_code", String.class);
    String shopCode = strategyRow.get("shop_code", String.class);
    String status = strategyRow.get("status", String.class);

    if (!"ACTIVE".equals(status)) {
      // 🔴 **试运行允许对草稿 / 已暂停的策略跑**（预览「本来会变成多少」是它的全部意义）；
      //    **真调（APPLY）只对生效中的策略跑** —— 没生效的策略不该动钱。
      if (!"DRY_RUN".equals(runMode)) {
        skips.add(
            new AdDaypartRunSkip(
                strategyId.toString(),
                strategyCode,
                shopCode,
                "策略不是生效中（当前 " + status + "）⇒ 本轮跳过（试运行可以预览，真调不行）"));
        return "SKIPPED";
      }
    }

    // 🔴 时区重新解析：站点的配置会变，策略不能拿着旧时区跑。
    var tz = shopTimezone(shopCode);
    if (!"RESOLVED".equals(tz.timezoneStatus())) {
      // 🔴 前提已经不成立（店铺定不了时区）⇒ 策略不许继续跑。
      //    自动暂停并写清原因（DB CHECK 要求 ACTIVE ⇒ RESOLVED，所以必须先暂停）。
      sql.execute(
          "update ad_daypart_strategy set status='PAUSED', shop_timezone='', timezone_status='UNRESOLVED',"
              + " timezone_reason=?, note=?, version=version+1, updated_at=now()"
              + " where tenant_id = " + TENANT + " and id = ?",
          tz.timezoneReason(),
          "自动暂停：" + tz.timezoneReason(),
          strategyId);
      skips.add(
          new AdDaypartRunSkip(
              strategyId.toString(),
              strategyCode,
              shopCode,
              "店铺时区已定不了（"
                  + tz.timezoneReason()
                  + "）⇒ 策略已自动暂停，本轮跳过"));
      return "SKIPPED";
    }
    String timezone = tz.shopTimezone();
    if (!timezone.equals(strategyRow.get("shop_timezone", String.class))) {
      // 站点的时区变了 ⇒ 策略跟着改（否则曲线会整体错位一小时）。
      sql.execute(
          "update ad_daypart_strategy set shop_timezone=?, timezone_status='RESOLVED', timezone_reason='',"
              + " version=version+1, updated_at=now()"
              + " where tenant_id = " + TENANT + " and id = ?",
          timezone,
          strategyId);
    }

    // 🔴 **当前是几个小时由 DB 的 now() 按 IANA 时区换算**（不是 JVM 墙钟）。
    int localHour = localHour(timezone);
    OffsetDateTime now = dbNow();

    // 🔴 幂等：同一策略 + 同一小时 + 同一口径 ⇒ 已经有任务了就返回它（不重跑）。
    Record existing =
        sql.fetchOne(
            "select id from ad_daypart_task where tenant_id = "
                + TENANT
                + " and strategy_id = ? and local_hour = ? and run_mode = ?",
            strategyId,
            localHour,
            runMode);
    if (existing != null) {
      tasks.add(taskView(existing.get("id", UUID.class)));
      return "DONE";
    }

    Record hourRow =
        sql.fetchOne(
            "select action, bid_multiplier, budget_multiplier from ad_daypart_strategy_hour"
                + " where tenant_id = " + TENANT + " and strategy_id = ? and local_hour = ?",
            strategyId,
            localHour);
    if (hourRow == null) {
      skips.add(
          new AdDaypartRunSkip(
              strategyId.toString(),
              strategyCode,
              shopCode,
              "本地第 " + localHour + " 小时没有曲线配置 ⇒ 本轮跳过"));
      return "SKIPPED";
    }
    String action = hourRow.get("action", String.class);
    BigDecimal bidMultiplier = nz(hourRow.get("bid_multiplier", BigDecimal.class));
    BigDecimal budgetMultiplier = nz(hourRow.get("budget_multiplier", BigDecimal.class));
    if (action.equals("KEEP") && bidMultiplier.compareTo(BigDecimal.ONE) == 0
        && budgetMultiplier.compareTo(BigDecimal.ONE) == 0) {
      skips.add(
          new AdDaypartRunSkip(
              strategyId.toString(),
              strategyCode,
              shopCode,
              "本地第 " + localHour + " 小时的曲线是「不动」⇒ 本轮跳过"));
      return "SKIPPED";
    }

    // ---- 建任务 ----
    UUID taskId = Ids.next();
    String currency = strategyRow.get("currency", String.class);
    BigDecimal singleAmount = strategyRow.get("single_adjust_cap_amount", BigDecimal.class);
    BigDecimal singlePercent = strategyRow.get("single_adjust_cap_percent", BigDecimal.class);
    BigDecimal totalAmount = strategyRow.get("total_cap_amount", BigDecimal.class);
    int totalCount = strategyRow.get("total_cap_count", Integer.class);
    int totalWindowHours = strategyRow.get("total_cap_window_hours", Integer.class);
    OffsetDateTime windowTo = now;
    OffsetDateTime windowFrom = now.minusHours(totalWindowHours);
    boolean dryRun = "DRY_RUN".equals(runMode) || strategyRow.get("dry_run", Boolean.class);

    sql.execute(
        "insert into ad_daypart_task"
            + " (id, tenant_id, strategy_id, shop_code, shop_timezone, timezone_status, local_hour,"
            + "  run_mode, trigger_source, status, currency,"
            + "  single_adjust_cap_amount, single_adjust_cap_percent, total_cap_amount,"
            + "  total_cap_count, total_cap_window_hours, window_from, window_to, note,"
            + "  created_at, updated_at)"
            + " values (?,?,?,?,?,'RESOLVED',?,?,?,'PENDING',?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,now(),now())",
        taskId,
        tenant,
        strategyId,
        shopCode,
        timezone,
        localHour,
        runMode,
        triggerSource,
        currency,
        singleAmount,
        singlePercent,
        totalAmount,
        totalCount,
        totalWindowHours,
        windowFrom,
        windowTo,
        note);

    // ---- 逐条调整 ----
    List<LineDraft> lines = new ArrayList<>();
    BigDecimal usedAmount = usedAmount(tenant, strategyId, windowFrom, windowTo);
    int usedCount = usedCount(tenant, strategyId, windowFrom, windowTo);

    String scope = strategyRow.get("scope", String.class);
    UUID targetCampaignId = strategyRow.get("target_campaign_id", UUID.class);
    List<Record> scoped =
        sql.fetch(
            "select id, campaign_name, status, currency, daily_budget, external_campaign_id"
                + " from ad_campaign where tenant_id = "
                + TENANT
                + " and shop_code = ? and status in ('ENABLED','PAUSED')"
                + (targetCampaignId == null ? "" : " and id = ?")
                + " order by id",
            targetCampaignId == null
                ? new Object[] {shopCode}
                : new Object[] {shopCode, targetCampaignId});

    BigDecimal minBudget = strategyRow.get("min_budget", BigDecimal.class);
    BigDecimal maxBudget = strategyRow.get("max_budget", BigDecimal.class);
    BigDecimal minBid = strategyRow.get("min_bid", BigDecimal.class);
    BigDecimal maxBid = strategyRow.get("max_bid", BigDecimal.class);

    for (Record campaign : scoped) {
      UUID campaignId = campaign.get("id", UUID.class);
      String campaignName = textOrEmpty(campaign.get("campaign_name", String.class));
      String campaignCurrency = upper(campaign.get("currency", String.class));
      String campaignStatus = text(campaign.get("status", String.class));

      // ---- 活动日预算 ----
      if (!campaignCurrency.equals(currency)) {
        lines.add(
            LineDraft.rejected(
                "CAMPAIGN_BUDGET",
                campaignId,
                null,
                campaignName,
                "",
                localHour,
                action,
                null,
                null,
                BigDecimal.ZERO,
                currency,
                GUARDRAIL_CURRENCY,
                "币种不一致（活动 "
                    + (campaignCurrency.isEmpty() ? "空" : campaignCurrency)
                    + " vs 策略 "
                    + currency
                    + "）—— 不按同一个数把不同币种一起调"));
        continue;
      }
      if (!"ENABLED".equals(campaignStatus)) {
        lines.add(
            LineDraft.skipped(
                "CAMPAIGN_BUDGET",
                campaignId,
                null,
                campaignName,
                "",
                localHour,
                action,
                null,
                null,
                BigDecimal.ZERO,
                currency,
                "活动当前是 "
                    + campaignStatus
                    + "（不在投放）⇒ 本轮不调整它的预算"));
        continue;
      }
      BigDecimal currentBudget = campaign.get("daily_budget", BigDecimal.class);
      if (currentBudget == null) {
        lines.add(
            LineDraft.skipped(
                "CAMPAIGN_BUDGET",
                campaignId,
                null,
                campaignName,
                "",
                localHour,
                action,
                null,
                null,
                BigDecimal.ZERO,
                currency,
                "该活动没有日预算（平台侧可能是固定价）⇒ 预算系数不适用"));
        continue;
      }
      BigDecimal baselineBudget =
          baselineFor(tenant, strategyId, "CAMPAIGN", campaignId, null, currentBudget, currency);
      BigDecimal targetBudget = money(baselineBudget.multiply(budgetMultiplier));
      BigDecimal deltaBudget = targetBudget.subtract(currentBudget);

      String reject = null;
      String guardrail = "";
      if (minBudget != null && targetBudget.compareTo(minBudget) < 0) {
        reject = "调整后的预算 " + targetBudget.toPlainString() + " 低于下限 " + minBudget.toPlainString();
        guardrail = GUARDRAIL_BOUND;
      } else if (maxBudget != null && targetBudget.compareTo(maxBudget) > 0) {
        reject = "调整后的预算 " + targetBudget.toPlainString() + " 超过上限 " + maxBudget.toPlainString();
        guardrail = GUARDRAIL_BOUND;
      } else {
        BigDecimal deviation = targetBudget.subtract(baselineBudget).abs();
        if (deviation.compareTo(singleAmount) > 0) {
          reject =
              "离基准的幅度 "
                  + deviation.toPlainString()
                  + " 超过单次上限（金额）"
                  + singleAmount.toPlainString();
          guardrail = GUARDRAIL_SINGLE_AMOUNT;
        } else if (singlePercent.signum() > 0
            && deviation.compareTo(baselineBudget.multiply(singlePercent)) > 0) {
          reject =
              "离基准的相对偏离 "
                  + ratio(deviation.divide(baselineBudget.max(BigDecimal.ONE), 6, RoundingMode.HALF_UP))
                      .toPlainString()
                  + " 超过单次上限（比例）"
                  + singlePercent.toPlainString();
          guardrail = GUARDRAIL_SINGLE_PERCENT;
        } else if (deltaBudget.signum() > 0
            && usedAmount.add(deltaBudget).compareTo(totalAmount) > 0) {
          reject =
              "窗口内累计上调将超过总额上限 "
                  + totalAmount.toPlainString()
                  + "（已用 "
                  + usedAmount.toPlainString()
                  + "）";
          guardrail = GUARDRAIL_TOTAL_AMOUNT;
        } else if (usedCount + 1 > totalCount) {
          reject =
              "窗口内累计调整次数将超过上限 " + totalCount + "（已用 " + usedCount + "）";
          guardrail = GUARDRAIL_TOTAL_COUNT;
        }
      }

      if (reject != null) {
        lines.add(
            LineDraft.rejected(
                "CAMPAIGN_BUDGET",
                campaignId,
                null,
                campaignName,
                "",
                localHour,
                action,
                currentBudget,
                targetBudget,
                deltaBudget,
                currency,
                guardrail,
                reject));
        continue;
      }
      if (deltaBudget.signum() == 0) {
        lines.add(
            LineDraft.skipped(
                "CAMPAIGN_BUDGET",
                campaignId,
                null,
                campaignName,
                "",
                localHour,
                action,
                currentBudget,
                targetBudget,
                deltaBudget,
                currency,
                "已经是目标值（幂等：同一小时重复跑结果相同）"));
        continue;
      }

      LineDraft line =
          LineDraft.pending(
              "CAMPAIGN_BUDGET",
              campaignId,
              null,
              campaignName,
              "",
              localHour,
              action,
              currentBudget,
              targetBudget,
              deltaBudget,
              currency,
              "");
      if (!dryRun) {
        applyBudget(line, campaignId, targetBudget, campaignName, localHour);
        if (line.result.equals("APPLIED") && deltaBudget.signum() > 0) {
          usedAmount = usedAmount.add(deltaBudget);
        }
        if (line.result.equals("APPLIED")) {
          usedCount = usedCount + 1;
        }
      } else {
        line.result = "DRY_RUN";
        line.message = "试运行：只算不调（本来会变成 " + targetBudget.toPlainString() + " " + currency + "）";
      }
      lines.add(line);
    }

    // ---- 关键词竞价（走 14.1.3.1 已交付的批量调价路径）----
    UUID shopId = shopIdOf(shopCode);
    if (shopId != null) {
      for (Record campaign : scoped) {
        UUID campaignId = campaign.get("id", UUID.class);
        String externalId = text(campaign.get("external_campaign_id", String.class));
        String campaignName = textOrEmpty(campaign.get("campaign_name", String.class));
        List<Record> keywordRows =
            sql.fetch(
                "select id, keyword, bid, bid_currency, status from ad_keyword"
                    + " where tenant_id = " + TENANT + " and shop_id = ? and external_campaign_id = ?"
                    + " and status <> 'ARCHIVED' order by id",
                shopId,
                externalId);
        if (keywordRows.isEmpty()) {
          continue;
        }
        List<AdKeywordModels.AdBulkBidTarget> targets = new ArrayList<>();
        Map<UUID, LineDraft> drafts = new LinkedHashMap<>();
        for (Record keyword : keywordRows) {
          UUID keywordId = keyword.get("id", UUID.class);
          String keywordText = textOrEmpty(keyword.get("keyword", String.class));
          String keywordCurrency = upper(keyword.get("bid_currency", String.class));
          BigDecimal currentBid = nz(keyword.get("bid", BigDecimal.class));
          if (!keywordCurrency.isEmpty() && !keywordCurrency.equals(currency)) {
            lines.add(
                LineDraft.rejected(
                    "KEYWORD_BID",
                    campaignId,
                    keywordId,
                    campaignName,
                    keywordText,
                    localHour,
                    action,
                    currentBid,
                    null,
                    BigDecimal.ZERO,
                    currency,
                    GUARDRAIL_CURRENCY,
                    "币种不一致（关键词 "
                        + keywordCurrency
                        + " vs 策略 "
                        + currency
                        + "）—— 不按同一个数把不同币种一起调"));
            continue;
          }
          BigDecimal baselineBid =
              baselineFor(tenant, strategyId, "KEYWORD", campaignId, keywordId, currentBid, currency);
          BigDecimal targetBid = money(baselineBid.multiply(bidMultiplier));
          BigDecimal deltaBid = targetBid.subtract(currentBid);
          String reject = null;
          String guardrail = "";
          if (minBid != null && targetBid.compareTo(minBid) < 0) {
            reject = "调整后的竞价 " + targetBid.toPlainString() + " 低于下限 " + minBid.toPlainString();
            guardrail = GUARDRAIL_BOUND;
          } else if (maxBid != null && targetBid.compareTo(maxBid) > 0) {
            reject = "调整后的竞价 " + targetBid.toPlainString() + " 超过上限 " + maxBid.toPlainString();
            guardrail = GUARDRAIL_BOUND;
          } else {
            BigDecimal deviation = targetBid.subtract(baselineBid).abs();
            if (deviation.compareTo(singleAmount) > 0) {
              reject =
                  "离基准的幅度 "
                      + deviation.toPlainString()
                      + " 超过单次上限（金额）"
                      + singleAmount.toPlainString();
              guardrail = GUARDRAIL_SINGLE_AMOUNT;
            } else if (singlePercent.signum() > 0
                && deviation.compareTo(baselineBid.multiply(singlePercent)) > 0) {
              reject =
                  "离基准的相对偏离超过单次上限（比例）" + singlePercent.toPlainString();
              guardrail = GUARDRAIL_SINGLE_PERCENT;
            } else if (usedCount + 1 > totalCount) {
              reject =
                  "窗口内累计调整次数将超过上限 " + totalCount + "（已用 " + usedCount + "）";
              guardrail = GUARDRAIL_TOTAL_COUNT;
            }
          }
          if (reject != null) {
            lines.add(
                LineDraft.rejected(
                    "KEYWORD_BID",
                    campaignId,
                    keywordId,
                    campaignName,
                    keywordText,
                    localHour,
                    action,
                    currentBid,
                    targetBid,
                    deltaBid,
                    currency,
                    guardrail,
                    reject));
            continue;
          }
          if (deltaBid.signum() == 0) {
            lines.add(
                LineDraft.skipped(
                    "KEYWORD_BID",
                    campaignId,
                    keywordId,
                    campaignName,
                    keywordText,
                    localHour,
                    action,
                    currentBid,
                    targetBid,
                    deltaBid,
                    currency,
                    "已经是目标值（幂等：同一小时重复跑结果相同）"));
            continue;
          }
          if (!dryRun && usedCount + 1 > totalCount) {
            lines.add(
                LineDraft.rejected(
                    "KEYWORD_BID",
                    campaignId,
                    keywordId,
                    campaignName,
                    keywordText,
                    localHour,
                    action,
                    currentBid,
                    targetBid,
                    deltaBid,
                    currency,
                    GUARDRAIL_TOTAL_COUNT,
                    "窗口内累计调整次数将超过上限 " + totalCount + "（已用 " + usedCount + "）"));
            continue;
          }
          targets.add(new AdKeywordModels.AdBulkBidTarget(keywordId, targetBid));
          drafts.put(
              keywordId,
              LineDraft.pending(
                  "KEYWORD_BID",
                  campaignId,
                  keywordId,
                  campaignName,
                  keywordText,
                  localHour,
                  action,
                  currentBid,
                  targetBid,
                  deltaBid,
                  currency,
                  ""));
        }
        if (targets.isEmpty()) {
          continue;
        }
        if (dryRun) {
          for (LineDraft draft : drafts.values()) {
            draft.result = "DRY_RUN";
            draft.message =
                "试运行：只算不调（本来会变成 "
                    + draft.afterValue.toPlainString()
                    + " "
                    + currency
                    + "）";
            lines.add(draft);
          }
          continue;
        }
        applyBids(
            tenant,
            taskId,
            shopId,
            currency,
            targets,
            drafts,
            lines,
            campaignName,
            localHour);
        for (LineDraft draft : drafts.values()) {
          if (draft.result.equals("APPLIED")) {
            usedCount = usedCount + 1;
          }
        }
      }
    }

    // ---- 落明细 + 汇总 ----
    int applied = 0;
    int rejected = 0;
    int skipped = 0;
    BigDecimal totalIncrease = BigDecimal.ZERO;
    for (LineDraft line : lines) {
      sql.execute(
          "insert into ad_daypart_task_line"
              + " (id, tenant_id, task_id, target_type, campaign_id, keyword_id, campaign_name,"
              + "  target_ref, local_hour, action, before_value, after_value, delta, currency,"
              + "  result, guardrail_code, message, created_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())",
          Ids.next(),
          tenant,
          taskId,
          line.targetType,
          line.campaignId,
          line.keywordId,
          line.campaignName,
          line.targetRef,
          line.localHour,
          line.action,
          line.beforeValue,
          line.afterValue,
          line.delta,
          line.currency,
          line.result,
          line.guardrailCode,
          line.message);
      switch (line.result) {
        case "APPLIED" -> {
          applied++;
          if (line.delta.signum() > 0 && line.targetType.equals("CAMPAIGN_BUDGET")) {
            totalIncrease = totalIncrease.add(line.delta);
          }
        }
        case "REJECTED" -> rejected++;
        default -> skipped++;
      }
    }
    String taskStatus;
    if (applied > 0 && (rejected > 0 || skipped > 0)) {
      taskStatus = "PARTIALLY_APPLIED";
    } else if (applied > 0) {
      taskStatus = "APPLIED";
    } else if (rejected > 0) {
      taskStatus = "FAILED";
    } else {
      taskStatus = "SKIPPED";
    }
    String message =
        "本地第 "
            + localHour
            + " 小时（"
            + timezone
            + "）：生效 "
            + applied
            + " 条 / 被拒 "
            + rejected
            + " 条 / 跳过 "
            + skipped
            + " 条"
            + (dryRun ? "（试运行：只算不调）" : "");
    sql.execute(
        "update ad_daypart_task set status=?, applied_count=?, rejected_count=?, skipped_count=?,"
            + " total_increase=?, executed_at=now(), message=?, updated_at=now()"
            + " where tenant_id = " + TENANT + " and id = ?",
        taskStatus,
        applied,
        rejected,
        skipped,
        totalIncrease,
        message,
        taskId);
    sql.execute(
        "update ad_daypart_strategy set last_run_at=now(),"
            + " last_applied_at=case when ?='APPLY' then now() else last_applied_at end,"
            + " updated_at=now()"
            + " where tenant_id = " + TENANT + " and id = ?",
        runMode,
        strategyId);
    tasks.add(taskView(taskId));
    return "DONE";
  }

  /** 改预算：走 14.1.1.1 已交付的写路径（护栏 + 幂等 + 操作审计都在那边）。 */
  private void applyBudget(
      LineDraft line, UUID campaignId, BigDecimal target, String campaignName, int localHour) {
    try {
      var result =
          campaigns.bulkOperation(
              new AdCampaignModels.BulkOperationRequest(
                  List.of(campaignId),
                  "SET_BUDGET",
                  target,
                  null,
                  "分时策略（14.2.1.2）本地第 " + localHour + " 小时调整预算"));
      if (result.items() == null || result.items().isEmpty()) {
        line.result = "REJECTED";
        line.message = "14.1.1.1 的写路径没有返回逐条结果";
        return;
      }
      var item = result.items().get(0);
      line.result = item.result();
      line.message = item.message() == null || item.message().isBlank() ? "" : item.message();
      if ("REJECTED".equals(line.result)) {
        line.guardrailCode = GUARDRAIL_BOUND;
      }
    } catch (ResponseStatusException e) {
      line.result = "REJECTED";
      line.guardrailCode = GUARDRAIL_BOUND;
      line.message = e.getReason() == null ? "14.1.1.1 的写路径拒绝了这个调整" : e.getReason();
    }
  }

  /** 改竞价：走 14.1.3.1 已交付的批量调价路径。 */
  private void applyBids(
      UUID tenant,
      UUID taskId,
      UUID shopId,
      String currency,
      List<AdKeywordModels.AdBulkBidTarget> targets,
      Map<UUID, LineDraft> drafts,
      List<LineDraft> lines,
      String campaignName,
      int localHour) {
    try {
      var result =
          keywords.bulkAction(
              new AdKeywordModels.AdBulkActionRequest(
                  "BID_ADJUST",
                  shopId,
                  "ABSOLUTE",
                  currency,
                  targets,
                  null,
                  "daypart-task-" + taskId,
                  "分时策略（14.2.1.2）本地第 " + localHour + " 小时调整竞价"));
      if (result.items() == null) {
        for (LineDraft draft : drafts.values()) {
          draft.result = "REJECTED";
          draft.message = "14.1.3.1 的写路径没有返回逐条结果";
          lines.add(draft);
        }
        return;
      }
      for (var item : result.items()) {
        UUID keywordId = item.keywordId();
        LineDraft draft = keywordId == null ? null : drafts.get(keywordId);
        if (draft == null) {
          continue;
        }
        if ("APPLIED".equals(item.status())) {
          draft.result = "APPLIED";
          draft.message = item.reason() == null ? "" : item.reason();
        } else {
          draft.result = "REJECTED";
          draft.guardrailCode = GUARDRAIL_BOUND;
          draft.message =
              item.reason() == null || item.reason().isBlank()
                  ? ("整批被护栏拒绝（状态 " + result.status() + "）")
                  : item.reason();
        }
        lines.add(draft);
      }
      // 批量调价路径可能没有为某些目标返回行 —— 那也要有记录（不静默丢弃）。
      for (LineDraft draft : drafts.values()) {
        boolean present =
            lines.stream().anyMatch(l -> l.keywordId != null && l.keywordId.equals(draft.keywordId));
        if (!present) {
          draft.result = "REJECTED";
          draft.message = "14.1.3.1 的写路径没有返回这一条的果（整批状态 " + result.status() + "）";
          lines.add(draft);
        }
      }
    } catch (ResponseStatusException e) {
      for (LineDraft draft : drafts.values()) {
        draft.result = "REJECTED";
        draft.guardrailCode = GUARDRAIL_BOUND;
        draft.message = e.getReason() == null ? "14.1.3.1 的写路径拒绝了这个调整" : e.getReason();
        lines.add(draft);
      }
    }
  }

  /** 任务列表（真分页 + 真筛选）。 */
  public AdDaypartTaskPage tasks(String strategyId, String status, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size <= 0 ? 50 : size, 200));
    int offset = Math.max(0, page) * limit;
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where t.tenant_id = " + TENANT);
    if (strategyId != null && !strategyId.isBlank()) {
      where.append(" and t.strategy_id = ?");
      binds.add(parseId(strategyId));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and t.status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    long total = count("select count(*) from ad_daypart_task t" + where, binds.toArray());
    List<AdDaypartTaskSummary> items =
        sql.fetch(
                "select t.id, t.strategy_id, t.shop_code, t.shop_timezone, t.local_hour, t.run_mode,"
                    + " t.trigger_source, t.status, t.currency, t.applied_count, t.rejected_count,"
                    + " t.skipped_count, t.total_increase, t.executed_at, t.created_at,"
                    + " st.strategy_code"
                    + " from ad_daypart_task t"
                    + " left join ad_daypart_strategy st"
                    + "   on st.tenant_id = t.tenant_id and st.id = t.strategy_id"
                    + where
                    + " order by t.created_at desc, t.id desc limit ? offset ?",
                concat(binds, limit, offset))
            .stream()
            .map(
                r ->
                    new AdDaypartTaskSummary(
                        r.get("id", UUID.class).toString(),
                        r.get("strategy_id", UUID.class).toString(),
                        textOrEmpty(r.get("strategy_code", String.class)),
                        text(r.get("shop_code", String.class)),
                        text(r.get("shop_timezone", String.class)),
                        r.get("local_hour", Integer.class) == null ? 0 : r.get("local_hour", Integer.class),
                        text(r.get("run_mode", String.class)),
                        text(r.get("trigger_source", String.class)),
                        text(r.get("status", String.class)),
                        text(r.get("currency", String.class)),
                        r.get("applied_count", Integer.class) == null ? 0 : r.get("applied_count", Integer.class),
                        r.get("rejected_count", Integer.class) == null ? 0 : r.get("rejected_count", Integer.class),
                        r.get("skipped_count", Integer.class) == null ? 0 : r.get("skipped_count", Integer.class),
                        nz(r.get("total_increase", BigDecimal.class)),
                        r.get("executed_at", OffsetDateTime.class),
                        r.get("created_at", OffsetDateTime.class)))
            .toList();
    return new AdDaypartTaskPage(items, total, Math.max(0, page), limit);
  }

  /** 任务详情（含逐条明细）。 */
  public AdDaypartTaskView task(String id) {
    currentTenant();
    return taskView(parseId(id));
  }

  private AdDaypartTaskView taskView(UUID taskId) {
    UUID tenant = currentTenant();
    Record row =
        sql.fetchOne(
            "select t.*, st.strategy_code, st.strategy_name"
                + " from ad_daypart_task t"
                + " left join ad_daypart_strategy st"
                + "   on st.tenant_id = t.tenant_id and st.id = t.strategy_id"
                + " where t.tenant_id = " + TENANT + " and t.id = ?",
            taskId);
    if (row == null) {
      throw notFound("任务");
    }
    List<AdDaypartTaskLineView> lines =
        sql.fetch(
                "select id, target_type, campaign_id, keyword_id, campaign_name, target_ref, local_hour,"
                    + " action, before_value, after_value, delta, currency, result, guardrail_code, message"
                    + " from ad_daypart_task_line where tenant_id = " + TENANT + " and task_id = ?"
                    + " order by target_type, campaign_id, keyword_id nulls first",
                taskId)
            .stream()
            .map(
                l ->
                    new AdDaypartTaskLineView(
                        l.get("id", UUID.class).toString(),
                        text(l.get("target_type", String.class)),
                        l.get("campaign_id", UUID.class).toString(),
                        textOrEmpty(l.get("campaign_name", String.class)),
                        l.get("keyword_id", UUID.class) == null ? "" : l.get("keyword_id", UUID.class).toString(),
                        text(l.get("target_ref", String.class)),
                        l.get("local_hour", Integer.class) == null ? 0 : l.get("local_hour", Integer.class),
                        text(l.get("action", String.class)),
                        l.get("before_value", BigDecimal.class),
                        l.get("after_value", BigDecimal.class),
                        nz(l.get("delta", BigDecimal.class)),
                        text(l.get("currency", String.class)),
                        text(l.get("result", String.class)),
                        text(l.get("guardrail_code", String.class)),
                        text(l.get("message", String.class))))
            .toList();
    return new AdDaypartTaskView(
        taskId.toString(),
        row.get("strategy_id", UUID.class).toString(),
        textOrEmpty(row.get("strategy_code", String.class)),
        textOrEmpty(row.get("strategy_name", String.class)),
        text(row.get("shop_code", String.class)),
        text(row.get("shop_timezone", String.class)),
        text(row.get("timezone_status", String.class)),
        row.get("local_hour", Integer.class) == null ? 0 : row.get("local_hour", Integer.class),
        text(row.get("run_mode", String.class)),
        text(row.get("trigger_source", String.class)),
        text(row.get("status", String.class)),
        text(row.get("currency", String.class)),
        row.get("single_adjust_cap_amount", BigDecimal.class),
        row.get("single_adjust_cap_percent", BigDecimal.class),
        row.get("total_cap_amount", BigDecimal.class),
        row.get("total_cap_count", Integer.class) == null ? 0 : row.get("total_cap_count", Integer.class),
        row.get("total_cap_window_hours", Integer.class) == null
            ? 0
            : row.get("total_cap_window_hours", Integer.class),
        row.get("window_from", OffsetDateTime.class),
        row.get("window_to", OffsetDateTime.class),
        row.get("applied_count", Integer.class) == null ? 0 : row.get("applied_count", Integer.class),
        row.get("rejected_count", Integer.class) == null ? 0 : row.get("rejected_count", Integer.class),
        row.get("skipped_count", Integer.class) == null ? 0 : row.get("skipped_count", Integer.class),
        nz(row.get("total_increase", BigDecimal.class)),
        row.get("executed_at", OffsetDateTime.class),
        text(row.get("message", String.class)),
        text(row.get("note", String.class)),
        lines,
        row.get("created_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 给下游 14.2.2.2 自动规则的读取形状
  // ==================================================================

  /** 某店铺当前生效的调整上限（下游自动规则取用；两边取更严的）。 */
  public AdDaypartShopCapsView shopCaps(String shopCode) {
    UUID tenant = currentTenant();
    var tz = shopTimezone(shopCode);
    List<Record> rows =
        sql.fetch(
            "select id, strategy_code, strategy_name, status, single_adjust_cap_amount,"
                + " single_adjust_cap_percent, total_cap_amount, total_cap_count, total_cap_window_hours,"
                + " min_budget, max_budget, min_bid, max_bid, dry_run"
                + " from ad_daypart_strategy where tenant_id = " + TENANT + " and shop_code = ?"
                + " order by strategy_code",
            shopCode);
    List<AdDaypartCapView> caps = new ArrayList<>();
    for (Record r : rows) {
      caps.add(
          new AdDaypartCapView(
              r.get("id", UUID.class).toString(),
              text(r.get("strategy_code", String.class)),
              text(r.get("strategy_name", String.class)),
              text(r.get("status", String.class)),
              r.get("single_adjust_cap_amount", BigDecimal.class),
              r.get("single_adjust_cap_percent", BigDecimal.class),
              r.get("total_cap_amount", BigDecimal.class),
              r.get("total_cap_count", Integer.class) == null ? 0 : r.get("total_cap_count", Integer.class),
              r.get("total_cap_window_hours", Integer.class) == null
                  ? 0
                  : r.get("total_cap_window_hours", Integer.class),
              r.get("min_budget", BigDecimal.class),
              r.get("max_budget", BigDecimal.class),
              r.get("min_bid", BigDecimal.class),
              r.get("max_bid", BigDecimal.class),
              Boolean.TRUE.equals(r.get("dry_run", Boolean.class))));
    }
    AdDaypartCapView effective = null;
    for (AdDaypartCapView cap : caps) {
      if (!"ACTIVE".equals(cap.status())) {
        continue;
      }
      if (effective == null) {
        effective = cap;
        continue;
      }
      effective =
          stricter(
              effective,
              cap,
              (a, b) -> a.compareTo(b) <= 0 ? a : b,
              (a, b) -> a <= b ? a : b);
    }
    return new AdDaypartShopCapsView(
        shopCode,
        tz.shopName(),
        tz.shopTimezone(),
        tz.timezoneStatus(),
        tz.timezoneReason(),
        caps,
        effective,
        "🔴 这是**分时策略这一侧**的上限。14.2.2.2 自动规则执行调整时，"
            + "应把**自己的上限**与这里的上限**取更严的那个** —— "
            + "两边各用各的，谁也不服谁，最后谁也说不清一次超支是谁的责任。"
            + (effective == null
                ? "⚠️ 该店铺**没有生效中**的分时策略 ⇒ 当前**没有生效的分时上限**；"
                    + "清单里仍列出已配置的上限（草稿 / 暂停 / 归档），方便对照。"
                : "合并后的有效上限 = 各条**生效中**策略里**最严**的那个。"));
  }

  /** 两个上限取更严的。 */
  private static AdDaypartCapView stricter(
      AdDaypartCapView a,
      AdDaypartCapView b,
      java.util.function.BinaryOperator<BigDecimal> amount,
      java.util.function.BinaryOperator<Integer> count) {
    return new AdDaypartCapView(
        a.strategyId(),
        a.strategyCode(),
        a.strategyName(),
        a.status(),
        amount.apply(a.singleAdjustCapAmount(), b.singleAdjustCapAmount()),
        amount.apply(a.singleAdjustCapPercent(), b.singleAdjustCapPercent()),
        amount.apply(a.totalCapAmount(), b.totalCapAmount()),
        count.apply(a.totalCapCount(), b.totalCapCount()),
        Math.min(a.totalCapWindowHours(), b.totalCapWindowHours()),
        a.minBudget() == null ? b.minBudget() : a.minBudget(),
        a.maxBudget() == null ? b.maxBudget() : a.maxBudget(),
        a.minBid() == null ? b.minBid() : a.minBid(),
        a.maxBid() == null ? b.maxBid() : a.maxBid(),
        a.dryRun() && b.dryRun());
  }

  // ==================================================================
  // 内部工具
  // ==================================================================

  /** 一条策略的行（不存在 ⇒ 404）。 */
  private Record loadStrategyRow(UUID id) {
    UUID tenant = currentTenant();
    Record row =
        sql.fetchOne("select * from ad_daypart_strategy where tenant_id = " + TENANT + " and id = ?", id);
    if (row == null) {
      throw notFound("分时策略");
    }
    return row;
  }

  /** 曲线里真正在动的小时数。 */
  private int effectiveHourCount(UUID strategyId) {
    UUID tenant = currentTenant();
    Long value =
        one(
            Long.class,
            "select count(*) from ad_daypart_strategy_hour"
                + " where tenant_id = " + TENANT + " and strategy_id = ?"
                + " and not (action = 'KEEP' and bid_multiplier = 1 and budget_multiplier = 1)",
            strategyId);
    return value == null ? 0 : value.intValue();
  }

  /** 🔴 **当前是店铺本地的第几个小时**（DB 的 now() + IANA 时区 ⇒ 夏令时对得上）。 */
  private int localHour(String timezone) {
    Integer hour =
        one(
            Integer.class,
            "select extract(hour from (now() at time zone cast(? as text)))::int",
            timezone);
    return hour == null ? 0 : hour;
  }

  /** 基准快照：取或建（**只在第一次快照**，之后不变 ⇒ 幂等、不复利）。 */
  private BigDecimal baselineFor(
      UUID tenant,
      UUID strategyId,
      String targetType,
      UUID campaignId,
      UUID keywordId,
      BigDecimal currentValue,
      String currency) {
    Record existing =
        sql.fetchOne(
            "select id, baseline_budget, baseline_bid from ad_daypart_baseline"
                + " where tenant_id = " + TENANT + " and strategy_id = ? and campaign_id = ?"
                + " and coalesce(keyword_id, '" + ZERO_UUID + "'::uuid) ="
                + " coalesce(?, '" + ZERO_UUID + "'::uuid)",
            strategyId,
            campaignId,
            keywordId);
    if (existing != null) {
      return targetType.equals("CAMPAIGN")
          ? nz(existing.get("baseline_budget", BigDecimal.class))
          : nz(existing.get("baseline_bid", BigDecimal.class));
    }
    sql.execute(
        "insert into ad_daypart_baseline"
            + " (id, tenant_id, strategy_id, target_type, campaign_id, keyword_id,"
            + "  baseline_budget, baseline_bid, currency, captured_at)"
            + " values (?,?,?,?,?,?,?,?,?,now())"
            + " on conflict do nothing",
        Ids.next(),
        tenant,
        strategyId,
        targetType,
        campaignId,
        keywordId,
        targetType.equals("CAMPAIGN") ? money(currentValue) : BigDecimal.ZERO,
        targetType.equals("KEYWORD") ? money(currentValue) : BigDecimal.ZERO,
        currency);
    Record created =
        sql.fetchOne(
            "select baseline_budget, baseline_bid from ad_daypart_baseline"
                + " where tenant_id = " + TENANT + " and strategy_id = ? and campaign_id = ?"
                + " and coalesce(keyword_id, '" + ZERO_UUID + "'::uuid) ="
                + " coalesce(?, '" + ZERO_UUID + "'::uuid)",
            strategyId,
            campaignId,
            keywordId);
    if (created == null) {
      return money(currentValue);
    }
    return targetType.equals("CAMPAIGN")
        ? nz(created.get("baseline_budget", BigDecimal.class))
        : nz(created.get("baseline_bid", BigDecimal.class));
  }

  /** 窗口内已用的上调金额（总额上限）。 */
  private BigDecimal usedAmount(UUID tenant, UUID strategyId, OffsetDateTime from, OffsetDateTime to) {
    BigDecimal value =
        one(
            BigDecimal.class,
            "select coalesce(sum(case when l.delta > 0 then l.delta else 0 end), 0)"
                + " from ad_daypart_task_line l"
                + " join ad_daypart_task t on t.tenant_id = l.tenant_id and t.id = l.task_id"
                + " where l.tenant_id = " + TENANT + " and t.strategy_id = ?"
                + " and t.run_mode = 'APPLY' and t.executed_at >= ?::timestamptz"
                + " and t.executed_at < ?::timestamptz and l.result = 'APPLIED'",
            strategyId,
            from,
            to);
    return nz(value);
  }

  /** 窗口内已用的调整次数（次数上限）。 */
  private int usedCount(UUID tenant, UUID strategyId, OffsetDateTime from, OffsetDateTime to) {
    Long value =
        one(
            Long.class,
            "select count(*) from ad_daypart_task_line l"
                + " join ad_daypart_task t on t.tenant_id = l.tenant_id and t.id = l.task_id"
                + " where l.tenant_id = " + TENANT + " and t.strategy_id = ?"
                + " and t.run_mode = 'APPLY' and t.executed_at >= ?::timestamptz"
                + " and t.executed_at < ?::timestamptz and l.result = 'APPLIED'",
            strategyId,
            from,
            to);
    return value == null ? 0 : value.intValue();
  }

  /** 店铺 id（按编码）。 */
  private UUID shopIdOf(String shopCode) {
    return sql.fetchOne(
            "select id from shop where tenant_id = " + TENANT + " and code = ?", shopCode)
        == null
        ? null
        : sql.fetchOne("select id from shop where tenant_id = " + TENANT + " and code = ?", shopCode)
            .get("id", UUID.class);
  }

  private static BigDecimal requirePositive(BigDecimal value, String what) {
    if (value == null || value.signum() <= 0) {
      throw badRequest(what + "必须大于 0");
    }
    return money(value);
  }

  private static Object[] concat(List<Object> binds, Object... more) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(more));
    return all.toArray();
  }

  /**
   * jsonb 参数快照（用**仓库既有的 Jackson**，不手搓 JSON 字符串 ——
   * 手搓转义在遇到引号 / 反斜杠时会拼出非法 JSON，而这份快照是**判定口径的可复算依据**，
   * 拼坏了就等于口径丢了）。
   */
  private String writeJson(Map<String, Object> profile) {
    try {
      return json.writeValueAsString(profile);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("判定参数快照写不成 JSON", e);
    }
  }

  /** 读回 jsonb 参数快照。 */
  private Map<String, Object> readJson(String raw) {
    if (raw == null || raw.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      return json.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // 快照读不出来就**如实说读不出来**，不要折成一个空 Map（那会让口径看起来是「空的」而不是「坏的」）。
      Map<String, Object> broken = new LinkedHashMap<>();
      broken.put("_unreadable", raw);
      return broken;
    }
  }

  // ==================================================================
  // 视图翻译
  // ==================================================================

  private AdDaypartStrategySummary toStrategySummary(Record r, int effectiveHours) {
    return new AdDaypartStrategySummary(
        r.get("id", UUID.class).toString(),
        text(r.get("strategy_code", String.class)),
        text(r.get("strategy_name", String.class)),
        text(r.get("shop_code", String.class)),
        textOrEmpty(r.get("shop_name", String.class)),
        text(r.get("shop_timezone", String.class)),
        text(r.get("timezone_status", String.class)),
        text(r.get("timezone_reason", String.class)),
        text(r.get("scope", String.class)),
        text(r.get("currency", String.class)),
        text(r.get("status", String.class)),
        Boolean.TRUE.equals(r.get("dry_run", Boolean.class)),
        Boolean.TRUE.equals(r.get("schedule_enabled", Boolean.class)),
        effectiveHours,
        r.get("last_run_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private AdDaypartStrategyView toStrategyView(Record r) {
    UUID id = r.get("id", UUID.class);
    List<AdDaypartHourConfig> hours =
        sql.fetch(
                "select local_hour, action, bid_multiplier, budget_multiplier, source, basis_note"
                    + " from ad_daypart_strategy_hour where tenant_id = " + TENANT
                    + " and strategy_id = ? order by local_hour",
                id)
            .stream()
            .map(
                h ->
                    new AdDaypartHourConfig(
                        h.get("local_hour", Integer.class),
                        text(h.get("action", String.class)),
                        h.get("bid_multiplier", BigDecimal.class),
                        h.get("budget_multiplier", BigDecimal.class),
                        text(h.get("source", String.class)),
                        text(h.get("basis_note", String.class))))
            .toList();
    String targetCampaignId = r.get("target_campaign_id", UUID.class) == null
        ? ""
        : r.get("target_campaign_id", UUID.class).toString();
    String targetCampaignName = "";
    if (!targetCampaignId.isEmpty()) {
      Record campaign =
          sql.fetchOne(
              "select campaign_name from ad_campaign where tenant_id = " + TENANT + " and id = ?",
              r.get("target_campaign_id", UUID.class));
      targetCampaignName = campaign == null ? "" : textOrEmpty(campaign.get("campaign_name", String.class));
    }
    String shopCode = text(r.get("shop_code", String.class));
    String shopName = "";
    Record shop =
        sql.fetchOne("select name from shop where tenant_id = " + TENANT + " and code = ?", shopCode);
    if (shop != null) {
      shopName = textOrEmpty(shop.get("name", String.class));
    }
    return new AdDaypartStrategyView(
        id.toString(),
        text(r.get("strategy_code", String.class)),
        text(r.get("strategy_name", String.class)),
        shopCode,
        shopName,
        text(r.get("shop_timezone", String.class)),
        text(r.get("timezone_status", String.class)),
        text(r.get("timezone_reason", String.class)),
        text(r.get("scope", String.class)),
        targetCampaignId,
        targetCampaignName,
        text(r.get("currency", String.class)),
        text(r.get("status", String.class)),
        r.get("single_adjust_cap_amount", BigDecimal.class),
        r.get("single_adjust_cap_percent", BigDecimal.class),
        r.get("total_cap_amount", BigDecimal.class),
        r.get("total_cap_count", Integer.class) == null ? 0 : r.get("total_cap_count", Integer.class),
        r.get("total_cap_window_hours", Integer.class) == null
            ? 0
            : r.get("total_cap_window_hours", Integer.class),
        r.get("min_budget", BigDecimal.class),
        r.get("max_budget", BigDecimal.class),
        r.get("min_bid", BigDecimal.class),
        r.get("max_bid", BigDecimal.class),
        Boolean.TRUE.equals(r.get("dry_run", Boolean.class)),
        Boolean.TRUE.equals(r.get("schedule_enabled", Boolean.class)),
        r.get("run_interval_minutes", Integer.class) == null ? 0 : r.get("run_interval_minutes", Integer.class),
        r.get("recommend_peak_share_multiple", BigDecimal.class),
        r.get("recommend_trough_share_multiple", BigDecimal.class),
        r.get("recommend_pause_share_multiple", BigDecimal.class),
        r.get("recommend_boost_bid_multiplier", BigDecimal.class),
        r.get("recommend_boost_budget_multiplier", BigDecimal.class),
        r.get("recommend_reduce_bid_multiplier", BigDecimal.class),
        r.get("recommend_reduce_budget_multiplier", BigDecimal.class),
        r.get("recommend_pause_bid_multiplier", BigDecimal.class),
        r.get("recommend_pause_budget_multiplier", BigDecimal.class),
        r.get("last_run_at", OffsetDateTime.class),
        r.get("last_applied_at", OffsetDateTime.class),
        hours,
        text(r.get("note", String.class)),
        r.get("version", Long.class) == null ? 0L : r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 内部草稿
  // ==================================================================

  /** 一条待落库的调整明细。 */
  private static final class LineDraft {
    String targetType;
    UUID campaignId;
    UUID keywordId;
    String campaignName;
    String targetRef;
    int localHour;
    String action;
    BigDecimal beforeValue;
    BigDecimal afterValue;
    BigDecimal delta;
    String currency;
    String result;
    String guardrailCode;
    String message;

    static LineDraft pending(
        String targetType,
        UUID campaignId,
        UUID keywordId,
        String campaignName,
        String targetRef,
        int localHour,
        String action,
        BigDecimal beforeValue,
        BigDecimal afterValue,
        BigDecimal delta,
        String currency,
        String guardrailCode) {
      LineDraft d = new LineDraft();
      d.targetType = targetType;
      d.campaignId = campaignId;
      d.keywordId = keywordId;
      d.campaignName = campaignName;
      d.targetRef = targetRef;
      d.localHour = localHour;
      d.action = action;
      d.beforeValue = beforeValue;
      d.afterValue = afterValue;
      d.delta = delta;
      d.currency = currency;
      d.result = "PENDING";
      d.guardrailCode = guardrailCode;
      d.message = "";
      return d;
    }

    static LineDraft rejected(
        String targetType,
        UUID campaignId,
        UUID keywordId,
        String campaignName,
        String targetRef,
        int localHour,
        String action,
        BigDecimal beforeValue,
        BigDecimal afterValue,
        BigDecimal delta,
        String currency,
        String guardrailCode,
        String message) {
      LineDraft d =
          pending(
              targetType,
              campaignId,
              keywordId,
              campaignName,
              targetRef,
              localHour,
              action,
              beforeValue,
              afterValue,
              delta,
              currency,
              guardrailCode);
      d.result = "REJECTED";
      d.message = message;
      return d;
    }

    static LineDraft skipped(
        String targetType,
        UUID campaignId,
        UUID keywordId,
        String campaignName,
        String targetRef,
        int localHour,
        String action,
        BigDecimal beforeValue,
        BigDecimal afterValue,
        BigDecimal delta,
        String currency,
        String message) {
      LineDraft d =
          pending(
              targetType,
              campaignId,
              keywordId,
              campaignName,
              targetRef,
              localHour,
              action,
              beforeValue,
              afterValue,
              delta,
              currency,
              "");
      d.result = "SKIPPED";
      d.message = message;
      return d;
    }
  }
}
