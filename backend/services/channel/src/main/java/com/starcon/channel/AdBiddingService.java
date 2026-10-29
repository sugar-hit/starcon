package com.starcon.channel;

import static com.starcon.channel.AdBiddingModels.*;
import static com.starcon.channel.AdKeywordModels.AdBulkActionItemRow;
import static com.starcon.channel.AdKeywordModels.AdBulkActionRequest;
import static com.starcon.channel.AdKeywordModels.AdBulkActionResult;
import static com.starcon.channel.AdKeywordModels.AdBulkBidTarget;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 14.2.1.1 竞价管理。
 *
 * <p>蓝图：「管理竞价：动态竞价策略选择（只增只降/降低/提高）、手动竞价调整、竞价范围上限、
 * 按商品/SKU 的目标 ACOS 反推竞价建议。输入：竞价设置；目标 ACOS/ROAS；历史表现。
 * 输出：竞价建议（目标 ACOS → 建议竞价）；批量调整任务。关系：上游 = 14.1.3.1、14.3.2.1；
 * 下游 = 平台广告 API。<b>目标 ACOS 反推竞价是广告与利润打通的接口</b>——没有利润数据，
 * 投放只能看平台给的建议，无法判断是否真的赚钱。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>动态竞价策略选择</b> → {@link #createPolicy(...)}：三档枚举
 *       （只增只降 / 只降低 / 只提高），写在策略上，随激活进待下发队列。
 *   <li><b>手动竞价调整</b> → {@link #adjust(...)}：<b>一律走 14.1.3.1 已交付的写路径</b>
 *       （{@link AdKeywordService#bulkAction}），护栏 / 幂等 / 操作审计全部继承。
 *   <li><b>竞价范围上限</b> → 策略上的 {@code min_bid} / {@code max_bid}（<b>闸①</b>）。
 *   <li><b>目标 ACOS 反推竞价建议</b> → {@link #generateSuggestion(...)}：
 *       SKU 级口径（花费 ÷ 归因销售额）→ 倍率 → 落到每个关键词自己的当前竞价上。
 * </ul>
 *
 * <p>🔴 本模块最容易犯的五个错，这里逐个挡住：
 *
 * <ol>
 *   <li><b>把两道闸混成一道</b>。「竞价范围上限」是**允许区间**（账户/活动级），
 *       「调整上限」是**单次额度**。前者越界 ⇒ 结果落在区间外；后者越界 ⇒ 这次调整太大。
 *       越限**逐条拒绝并留痕**（写明被哪道闸拦下 + 原因），<b>绝不静默截断</b>。
 *   <li><b>把「算不出来」写成 0</b>。ACOS = 花费 ÷ 归因销售额，<b>分母为 0 ⇒ NULL（不可比）</b>；
 *       「压根算不出来」与「算出来是零」在调价里是<b>相反</b>的两件事 ——
 *       拿 0 冒充会让「没归因到 / 没同步到」看起来像「效率极高」。
 *   <li><b>把「本地已改」说成「平台已改」</b>。平台广告 API 未交付 ⇒ 调整只落
 *       <b>待下发队列</b>，界面写「待下发」。本模块<b>唯一</b>能改队列的动作是「撤销」，
 *       <b>刻意不提供</b>「标记已下发」这种假动作。
 *   <li><b>绕过 14.1.3.1 的护栏另开一条写路径</b>。本模块<b>不另写 UPDATE</b>：
 *       一律走 {@link AdKeywordService#bulkAction}，因此 ±50% / 1000.00 / 总上调 5000.00
 *       三条护栏、幂等与操作审计<b>全部继承</b>。
 *   <li><b>拿不到利润就算「建议」</b>。目标 ACOS 反推的**前提**是「这个价投下去赚不赚钱」，
 *       而那要靠 13.4.1.2 的 SKU 利润 ⇒ 利润侧读不到 ⇒ <b>503</b>，
 *       而不是给一批「看起来很专业」的、其实没校验过盈亏的建议。
 * </ol>
 *
 * <p>🔴 <b>数据来源纪律</b>：
 *
 * <ul>
 *   <li>14.1.3.1（V274，<b>已交付</b>，同服务同库）：{@code ad_keyword} / {@code ad_keyword_metric}
 *       —— 关键词与它的花费 / 销售额，<b>只读</b>。
 *   <li>14.3.2.1（V404，<b>已交付</b>，同服务同库）：{@code ad_attribution_fact} ——
 *       <b>归因销售额</b>，只读，且只取<b>生效规则当前版本</b>的那一批（口径固定并公示）。
 *   <li>13.4.1.2（reporting 服务，<b>已交付</b>）：SKU 利润，<b>只经授权只读 API</b>
 *       （{@link AdProductProfitPort}），转发调用方自己的 bearer、<b>不传租户号</b>。
 *   <li>03 商品主数据（catalog 服务）：内部 SKU → SKU 编码，<b>只经授权只读 API</b>
 *       （{@link AdProductCatalogPort}）。
 * </ul>
 */
@Service
public class AdBiddingService {

  // ==================================================================
  // 口径常量（**口径说明**随响应返回，界面表头与跨部门沟通用同一份定义）
  // ==================================================================

  static final String STRATEGY_NOTE =
      "🔴 **动态竞价策略是三档枚举，不是自由文本**："
          + "UP_AND_DOWN 只增只降 / DOWN_ONLY 只降低 / UP_ONLY 只提高。"
          + "写成自由文本的后果不是「多一点选择」，而是**平台侧收到一个它不认识的策略**，"
          + "然后整个投放静默按平台默认值跑 —— 界面上却显示「已设置」。"
          + "🔴 策略档位与激活都会进**待下发队列**（平台广告 API 未交付 ⇒ 只落队列，不改平台）。";

  static final String GUARDRAIL_NOTE =
      "🔴 **两道不同的闸**（蓝图里这是两件事，混成一件就会烧钱）："
          + "① **竞价范围上限**（策略上的 min_bid / max_bid）= 账户/活动级的**允许区间**，"
          + "结果落在区间外就是不许；"
          + "② **调整上限** = 单次调整的**额度**（策略自定的比例/金额 + 14.1.3.1 已交付写路径的"
          + "±50% / 1000.00 / 单次总上调 5000.00），两边**取更严的那个**。"
          + "🔴 **越限的那几条被逐条拒绝并留痕**（REJECTED + 被哪道闸 + 原因），"
          + "**绝不静默截断** —— 静默截断会让人以为「按预期工作」。"
          + "🔴 14.1.3.1 的比例上限只在**百分比模式**下生效，而本模块用的是**绝对值模式** ⇒ "
          + "本模块在预检里**同样执行 ±50%**，否则那条护栏在绝对值模式下就是空的。";

  static final String RATIO_NOTE =
      "🔴 **口径写死，不给第二种解释**："
          + "ACOS = 花费 ÷ **归因销售额**；ROAS = 归因销售额 ÷ 花费。"
          + "🔴 **分母为 0 ⇒ 一律 NULL（不可比），不是 0**："
          + "「压根算不出来」与「算出来是零」在调价里是**相反**的两件事 —— "
          + "拿 0 冒充会让「没归因到 / 没同步到 / 暂停中」看起来像「效率极高」。"
          + "两列都是 **GENERATED 列**（服务层写不进去，绕过服务层也编不出对不上的口径）。"
          + "🔴 建议竞价 = **该关键词自己的当前竞价** × (目标 ACOS ÷ 当前 ACOS) —— "
          + "倍率是 SKU 级效率的修正，逐条落到每个词自己的价上，不是把 SKU 的均价抄给所有词。"
          + "🔴 目标 ACOS / ROAS 是**比例不是百分数**（0.25 = 25%），两者互为倒数、"
          + "**二选一**（同时给两个就必须自洽，不自洽等于给了两个互相矛盾的答案）。";

  static final String PROFIT_NOTE =
      "🔴 **目标 ACOS 反推竞价是广告与利润打通的接口** —— "
          + "没有利润数据，投放只能看平台给的建议，无法判断是否真的赚钱。"
          + "本模块据此用 13.4.1.2（reporting 服务）的 SKU 利润算出**盈亏平衡 ACOS**"
          + "（=（净利 + 广告费）÷ 单品收入；净利已经扣过广告费，加回来才是「广告前」），"
          + "并用它**否决**那些「按这个价投下去是亏的」的建议。"
          + "🔴 利润侧**只经授权只读 API**读（零跨库 SQL / 零 FK / 零影子表 / 零共享视图），"
          + "转发调用方自己的 bearer、**不传租户号**；"
          + "🔴 **读不到一律 503** —— 返回一批「看起来很专业、其实没校验过盈亏」的建议，"
          + "比直接报错危险得多。";

  static final String UPSTREAM_NOTE =
      "上游 = **14.1.3.1 关键词管理**（V274，已交付：关键词与花费/销售额）、"
          + "**14.3.2.1 归因分析**（V404，已交付：归因销售额）、"
          + "**13.4.1.2 SKU 利润**（reporting，已交付：盈亏平衡 ACOS）、"
          + "**03 商品主数据**（catalog：SKU 编码）。"
          + "🔴 归因销售额只取**生效规则当前版本**的那一批（口径固定并公示，"
          + "规则改了历史报表不可比 —— 这一条不许含糊）。"
          + "🔴 listing → 内部 SKU 按**当前登记的 SKU 变体数等分**"
          + "（照抄 14.3.1.3 的两级等分口径），广告花费按等分落到每个 SKU 上。";

  static final String DISPATCH_NOTE =
      "🔴 **平台广告 API 未交付** ⇒ 竞价调整与动态竞价策略**只落待下发队列**，"
          + "界面写「**待下发**」而不是「已生效」—— 把「本地已改」说成「平台侧已改」是假事实。"
          + "🔴 本模块**唯一**能改变队列的动作是「**撤销**」；"
          + "**刻意不提供**「标记已下发」这种假动作（平台回写真的发生了才有 DISPATCHED，"
          + "而那要由平台适配器写）。"
          + "🔴 **撤销 = 停止下发，不等于回滚**：本地竞价已经通过 14.1.3.1 的写路径改过了，"
          + "撤销**不会**把本地竞价改回去（那是一次新的调价，要走同一套护栏）。";

  static final String BOUNDARY_NOTE =
      "🔴 **改竞价一律走 14.1.3.1 已交付的写路径**（{@code AdKeywordService.bulkAction}）"
          + "⇒ 护栏 / 幂等 / 操作审计**全部继承**，本模块**不另开绕过护栏的写路径**。"
          + "🔴 **活动状态不在本模块范围** —— 本模块只改竞价，暂停/启用归 14.1.1.1。"
          + "🔴 **平台回写队列不在本模块范围** —— 真正的下发由平台适配器负责。";

  /** 一次建议窗口最多多少天（逐 SKU 取上游利润，窗口太长会把上游压垮）。 */
  static final int MAX_WINDOW_DAYS = 92;

  /** 建议窗口默认天数。 */
  static final int DEFAULT_WINDOW_DAYS = 30;

  /** 一次批量调整最多多少条（与 14.1.3.1 的单次批量条目上限同量级）。 */
  static final int MAX_ADJUST_ITEMS = 200;

  // ==================================================================
  // 枚举
  // ==================================================================

  static final List<String> STATUSES = List.of("DRAFT", "ACTIVE", "PAUSED", "ARCHIVED");
  static final List<String> SCOPES = List.of("SHOP", "CAMPAIGN");
  static final List<String> DYNAMIC_STRATEGIES = List.of("UP_AND_DOWN", "DOWN_ONLY", "UP_ONLY");
  static final List<String> RUN_MODES = List.of("DRY_RUN", "APPLY");
  static final List<String> DISPATCH_STATUSES =
      List.of("PENDING", "DISPATCHED", "FAILED", "CANCELLED");

  /** 闸门码（被哪一道闸拦下的）。 */
  static final List<String> GUARDRAIL_CODES =
      List.of(
          "RANGE_MIN",
          "RANGE_MAX",
          "ADJUST_RATIO",
          "ADJUST_AMOUNT",
          "UPSTREAM_RATIO",
          "UPSTREAM_ABSOLUTE",
          "UPSTREAM_TOTAL",
          "UPSTREAM_REJECTED",
          "CURRENCY",
          "NO_BASIS",
          "ABOVE_BREAK_EVEN");

  // ==================================================================
  // 依赖
  // ==================================================================

  private final DSLContext sql;
  private final ObjectMapper json;
  private final AdKeywordService keywordService;
  private final AdProductCatalogPort catalog;
  private final AdProductProfitPort profit;

  public AdBiddingService(
      DSLContext sql,
      ObjectMapper json,
      AdKeywordService keywordService,
      AdProductCatalogPort catalog,
      AdProductProfitPort profit) {
    this.sql = sql;
    this.json = json;
    this.keywordService = keywordService;
    this.catalog = catalog;
    this.profit = profit;
  }

  // ==================================================================
  // 小工具（照抄 AdDaypartService / AdKeywordService 的同名写法）
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

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private static ResponseStatusException upstreamUnavailable(String message) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
  }

  private static UUID parseId(String id) {
    try {
      return UUID.fromString(text(id));
    } catch (RuntimeException e) {
      throw badRequest("id 不是合法 UUID");
    }
  }

  private static int clampSize(int size) {
    return Math.min(Math.max(size, 1), 200);
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public AdBiddingCapabilities capabilities() {
    return new AdBiddingCapabilities(
        List.of(
            new AdBiddingCapabilityDimension(
                "dynamic_bidding_strategy",
                "🔴 动态竞价策略选择（**只增只降 / 只降低 / 只提高**，三档枚举）",
                true,
                "ad_bidding_policy.dynamic_bidding_strategy（V407）+ 待下发队列",
                "",
                "写成自由文本会让平台侧收到一个它不认识的策略，然后静默按默认值跑"),
            new AdBiddingCapabilityDimension(
                "manual_bid_adjustment",
                "手动竞价调整（**走 14.1.3.1 已交付的写路径**，护栏 / 幂等 / 审计全继承）",
                true,
                "ad_bidding_adjustment + ad_bidding_adjustment_item（V407）"
                    + " → AdKeywordService.bulkAction",
                "",
                "另开一条写路径就会绕过 ±50% / 1000.00 / 总上调 5000.00 三条护栏"),
            new AdBiddingCapabilityDimension(
                "bid_range_cap",
                "🔴 竞价范围上限（账户/活动级的**允许区间**，与单次调整额度是**两道不同的闸**）",
                true,
                "ad_bidding_policy.min_bid / max_bid（V407）",
                "",
                "没有区间闸，一次手滑就能把竞价拉到平台不接受的值"),
            new AdBiddingCapabilityDimension(
                "target_acos_bid_suggestion",
                "🔴 按商品/SKU 的目标 ACOS 反推竞价建议（目标 ACOS → 建议竞价）",
                true,
                "ad_bidding_suggestion + ad_bidding_suggestion_sku + ad_bidding_suggestion_item（V407）",
                "",
                "没有它，运营只能照着平台给的建议点「采纳」，无法按自己的目标 ACOS 反推"),
            new AdBiddingCapabilityDimension(
                "profit_breakeven",
                "🔴 用 SKU 利润算**盈亏平衡 ACOS**，否决「按这个价投是亏的」的建议",
                true,
                "13.4.1.2（reporting 服务，只读 API）",
                "",
                "**没有利润数据，投放只能看平台给的建议，无法判断是否真的赚钱**"),
            new AdBiddingCapabilityDimension(
                "attributed_sales_caliber",
                "归因销售额（**口径固定并公示**：只取生效规则当前版本的那一批）",
                true,
                "14.3.2.1 ad_attribution_fact（V404，已交付，同库只读）",
                "",
                "口径不明 ⇒ 算出来的 ACOS 没法跟财务对账"),
            new AdBiddingCapabilityDimension(
                "pending_dispatch_queue",
                "待下发队列（平台广告 API 未交付 ⇒ 调整只落队列）",
                true,
                "ad_bidding_dispatch（V407）",
                "",
                "把「本地已改」说成「平台已改」是假事实"),
            new AdBiddingCapabilityDimension(
                "dispatch_cancel",
                "撤销一条待下发（**本模块唯一**能改队列的动作）",
                true,
                "POST /channel/ad-bidding/dispatch/{uuid}/cancel",
                "",
                "没有撤销，误操作就得等平台回写之后才能纠正"),
            new AdBiddingCapabilityDimension(
                "platform_writeback",
                "把待下发的调整**真的回写平台**",
                false,
                "—",
                "平台广告 API 适配器（未交付）",
                "队列里的记录停在「待下发」；本地竞价已改，平台侧还没改 —— 界面显式标出"),
            new AdBiddingCapabilityDimension(
                "mark_as_dispatched",
                "手动把队列记录标记成「已下发」",
                false,
                "—",
                "平台适配器（未交付）",
                "**刻意不提供**：平台回写真的发生了才有 DISPATCHED，手动标会让假事实进账"),
            new AdBiddingCapabilityDimension(
                "campaign_state_toggle",
                "把活动状态改成暂停 / 启用",
                false,
                "—",
                "14.1.1.1 活动列表（已有状态字段）",
                "本模块只改竞价；「停投」不是调价的事"),
            new AdBiddingCapabilityDimension(
                "bulk_budget_adjustment",
                "批量调整预算",
                false,
                "—",
                "14.2.2.1 预算分配 / 14.2.2.2 自动规则（已交付）",
                "预算与竞价是**两道不同的闸**；本模块只管竞价，预算走它自己的模块")),
        STATUSES,
        SCOPES,
        DYNAMIC_STRATEGIES,
        RUN_MODES,
        DISPATCH_STATUSES,
        GUARDRAIL_CODES,
        STRATEGY_NOTE,
        GUARDRAIL_NOTE,
        RATIO_NOTE,
        PROFIT_NOTE,
        UPSTREAM_NOTE,
        DISPATCH_NOTE,
        BOUNDARY_NOTE);
  }

  // ==================================================================
  // 下拉项
  // ==================================================================

  public AdBiddingOptions options() {
    UUID tenant = currentTenant();
    List<AdBiddingOption> platforms = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select p.id, p.code, p.name from platform p"
                + " join shop s on s.tenant_id=? and s.platform_id=p.id"
                + " group by p.id,p.code,p.name order by p.code",
            tenant)) {
      platforms.add(new AdBiddingOption(r.get("id", UUID.class).toString(), r.get("code", String.class),
          r.get("name", String.class)));
    }
    List<AdBiddingOption> shops = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select code, name, platform_id from shop where tenant_id=? order by code", tenant)) {
      shops.add(new AdBiddingOption(r.get("code", String.class), r.get("name", String.class),
          r.get("platform_id", UUID.class).toString()));
    }
    List<AdBiddingOption> strategies = new ArrayList<>();
    strategies.add(new AdBiddingOption("UP_AND_DOWN", "只增只降", "平台可上调也可下调"));
    strategies.add(new AdBiddingOption("DOWN_ONLY", "只降低", "只降不升"));
    strategies.add(new AdBiddingOption("UP_ONLY", "只提高", "只升不降"));
    List<AdBiddingOption> statuses = new ArrayList<>();
    for (String s : STATUSES) {
      statuses.add(new AdBiddingOption(s, s, ""));
    }
    return new AdBiddingOptions(
        platforms,
        shops,
        strategies,
        statuses,
        new AdBiddingUpstreamGuardrails(
            AdKeywordService.MAX_BID_DELTA_RATIO,
            AdKeywordService.MAX_BID_ABSOLUTE,
            AdKeywordService.MAX_TOTAL_BID_INCREASE),
        STRATEGY_NOTE + " " + GUARDRAIL_NOTE);
  }

  // ==================================================================
  // 竞价策略（增删改查）
  // ==================================================================

  private static final String POLICY_SELECT =
      """
      select p.id, p.policy_code, p.policy_name, p.platform_id,
             f.code platform_code, f.name platform_name,
             p.shop_code, s.name shop_name, s.id shop_id,
             p.dynamic_bidding_strategy, p.currency, p.scope, p.target_campaign_id,
             coalesce(c.campaign_name,'') target_campaign_name,
             p.status, p.min_bid, p.max_bid, p.single_adjust_cap_ratio, p.single_adjust_cap_amount,
             p.target_acos, p.target_roas, p.dry_run, p.note, p.version, p.created_at, p.updated_at
        from ad_bidding_policy p
        join platform f on f.id = p.platform_id
        join shop s on s.tenant_id = p.tenant_id and s.code = p.shop_code
        left join ad_campaign c on c.tenant_id = p.tenant_id and c.id = p.target_campaign_id
      """;

  public AdBiddingPolicyPage policies(
      String shopCode, String status, String keyword, int page, int size) {
    UUID tenant = currentTenant();
    int sizeBound = clampSize(size);
    int offset = Math.max(page, 0) * sizeBound;

    StringBuilder where = new StringBuilder(" where p.tenant_id = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    if (!text(shopCode).isEmpty()) {
      where.append(" and p.shop_code = ?");
      binds.add(text(shopCode));
    }
    if (!text(status).isEmpty()) {
      where.append(" and p.status = ?");
      binds.add(upper(status));
    }
    if (!text(keyword).isEmpty()) {
      where.append(" and (p.policy_code ilike ? or p.policy_name ilike ?)");
      binds.add("%" + text(keyword) + "%");
      binds.add("%" + text(keyword) + "%");
    }

    long total =
        count("select count(*) from ad_bidding_policy p" + where, binds.toArray());

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(sizeBound);
    pageBinds.add(offset);
    List<AdBiddingPolicySummary> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            POLICY_SELECT
                + where
                + " order by p.created_at desc, p.id limit ? offset ?",
            pageBinds.toArray())) {
      items.add(policySummary(r));
    }
    return new AdBiddingPolicyPage(items, total, Math.max(page, 0), sizeBound);
  }

  public AdBiddingPolicyView policy(String id) {
    UUID tenant = currentTenant();
    Record row =
        sql.fetchOne(POLICY_SELECT + " where p.tenant_id = ? and p.id = ?", tenant, parseId(id));
    if (row == null) {
      throw notFound("竞价策略");
    }
    return policyView(row);
  }

  public AdBiddingPolicyView createPolicy(AdBiddingPolicyRequest request) {
    UUID tenant = currentTenant();
    String policyCode = text(request.policyCode());
    String policyName = text(request.policyName());
    if (policyCode.isEmpty()) {
      throw badRequest("策略编码不能为空");
    }
    if (policyName.isEmpty()) {
      throw badRequest("策略名不能为空");
    }
    String strategy = upper(request.dynamicBiddingStrategy());
    if (!DYNAMIC_STRATEGIES.contains(strategy)) {
      throw badRequest(
          "动态竞价策略只能是 "
              + String.join(" / ", DYNAMIC_STRATEGIES)
              + "（收到「"
              + strategy
              + "」）—— 它是三档枚举，不是自由文本");
    }
    String currency = upper(request.currency());
    if (!currency.matches("^[A-Z]{3}$")) {
      throw badRequest("币种必须是三位大写字母（收到「" + currency + "」）");
    }
    String shopCode = text(request.shopCode());
    UUID shopId = shopIdOf(tenant, shopCode);
    if (shopId == null) {
      throw notFound("店铺");
    }
    UUID platformId = parseId(request.platformId());
    String platformOfShop = platformIdOfShop(tenant, shopCode);
    if (platformOfShop == null || !platformOfShop.equals(platformId.toString())) {
      throw badRequest("平台与店铺不匹配（店铺归属的平台与所选平台不一致）");
    }
    String scope = upper(request.scope()).isEmpty() ? "SHOP" : upper(request.scope());
    if (!SCOPES.contains(scope)) {
      throw badRequest("作用范围只能是 SHOP / CAMPAIGN");
    }
    UUID targetCampaignId = null;
    if ("CAMPAIGN".equals(scope)) {
      if (text(request.targetCampaignId()).isEmpty()) {
        throw badRequest("作用范围是 CAMPAIGN 时必须给出目标活动 id");
      }
      targetCampaignId = parseId(request.targetCampaignId());
      String campaignShop = campaignShopOf(tenant, targetCampaignId);
      if (campaignShop == null) {
        throw notFound("目标活动");
      }
      if (!campaignShop.equals(shopCode)) {
        throw badRequest("目标活动不属于这条策略的店铺");
      }
    }
    BigDecimal[] targets = normalizeTargets(request.targetAcos(), request.targetRoas());
    BigDecimal minBid = optionalMoney(request.minBid(), "竞价范围下限");
    BigDecimal maxBid = optionalMoney(request.maxBid(), "竞价范围上限");
    if (minBid != null && maxBid != null && maxBid.compareTo(minBid) < 0) {
      throw badRequest("竞价范围上限不能低于下限");
    }
    BigDecimal capRatio = optionalRatio(request.singleAdjustCapRatio(), "单次调整比例额度");
    BigDecimal capAmount = optionalMoney(request.singleAdjustCapAmount(), "单次调整金额额度");
    boolean dryRun = request.dryRun() == null || request.dryRun();

    UUID id = Ids.next();
    OffsetDateTime now = dbNow();
    sql.execute(
        "insert into ad_bidding_policy (id, tenant_id, policy_code, policy_name, platform_id,"
            + " shop_code, dynamic_bidding_strategy, currency, scope, target_campaign_id, status,"
            + " min_bid, max_bid, single_adjust_cap_ratio, single_adjust_cap_amount,"
            + " target_acos, target_roas, dry_run, note, created_at, updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        tenant,
        policyCode,
        policyName,
        platformId,
        shopCode,
        strategy,
        currency,
        scope,
        targetCampaignId,
        minBid,
        maxBid,
        capRatio == null ? BigDecimal.ZERO : capRatio,
        capAmount == null ? BigDecimal.ZERO : capAmount,
        targets[0],
        targets[1],
        dryRun,
        text(request.note()),
        now,
        now);
    return policy(id.toString());
  }

  public AdBiddingPolicyView updatePolicy(String id, AdBiddingPolicyRequest request) {
    UUID tenant = currentTenant();
    Record before =
        sql.fetchOne("select * from ad_bidding_policy where tenant_id=? and id=?", tenant, parseId(id));
    if (before == null) {
      throw notFound("竞价策略");
    }
    if ("ARCHIVED".equals(text(before.get("status", String.class)))) {
      throw unprocessable("已归档的策略不能再改（归档是软删除：要重新配就建一条新的）");
    }
    String strategy = upper(request.dynamicBiddingStrategy());
    if (!DYNAMIC_STRATEGIES.contains(strategy)) {
      throw badRequest(
          "动态竞价策略只能是 "
              + String.join(" / ", DYNAMIC_STRATEGIES)
              + "（收到「"
              + strategy
              + "」）—— 它是三档枚举，不是自由文本");
    }
    String currency = upper(request.currency());
    if (!currency.matches("^[A-Z]{3}$")) {
      throw badRequest("币种必须是三位大写字母（收到「" + currency + "」）");
    }
    String scope = upper(request.scope()).isEmpty() ? "SHOP" : upper(request.scope());
    if (!SCOPES.contains(scope)) {
      throw badRequest("作用范围只能是 SHOP / CAMPAIGN");
    }
    UUID targetCampaignId = null;
    if ("CAMPAIGN".equals(scope)) {
      if (text(request.targetCampaignId()).isEmpty()) {
        throw badRequest("作用范围是 CAMPAIGN 时必须给出目标活动 id");
      }
      targetCampaignId = parseId(request.targetCampaignId());
    }
    BigDecimal[] targets = normalizeTargets(request.targetAcos(), request.targetRoas());
    BigDecimal minBid = optionalMoney(request.minBid(), "竞价范围下限");
    BigDecimal maxBid = optionalMoney(request.maxBid(), "竞价范围上限");
    if (minBid != null && maxBid != null && maxBid.compareTo(minBid) < 0) {
      throw badRequest("竞价范围上限不能低于下限");
    }
    BigDecimal capRatio = optionalRatio(request.singleAdjustCapRatio(), "单次调整比例额度");
    BigDecimal capAmount = optionalMoney(request.singleAdjustCapAmount(), "单次调整金额额度");
    boolean dryRun = request.dryRun() == null || request.dryRun();

    sql.execute(
        "update ad_bidding_policy set policy_name=?, dynamic_bidding_strategy=?, currency=?,"
            + " scope=?, target_campaign_id=?, min_bid=?, max_bid=?, single_adjust_cap_ratio=?,"
            + " single_adjust_cap_amount=?, target_acos=?, target_roas=?, dry_run=?, note=?,"
            + " version=version+1, updated_at=?::timestamptz where tenant_id=? and id=?",
        text(request.policyName()),
        strategy,
        currency,
        scope,
        targetCampaignId,
        minBid,
        maxBid,
        capRatio == null ? BigDecimal.ZERO : capRatio,
        capAmount == null ? BigDecimal.ZERO : capAmount,
        targets[0],
        targets[1],
        dryRun,
        text(request.note()),
        dbNow(),
        tenant,
        parseId(id));

    // 🔴 策略档位变了 ⇒ 平台侧要重新知道 ⇒ 进待下发队列。
    String strategyBefore = upper(text(before.get("dynamic_bidding_strategy", String.class)));
    if (!strategyBefore.equals(strategy)) {
      enqueueStrategyDispatch(tenant, parseId(id));
    }
    return policy(id);
  }

  public AdBiddingPolicyView changeStatus(String id, AdBiddingStatusRequest request) {
    UUID tenant = currentTenant();
    UUID policyId = parseId(id);
    Record row =
        sql.fetchOne("select * from ad_bidding_policy where tenant_id=? and id=?", tenant, policyId);
    if (row == null) {
      throw notFound("竞价策略");
    }
    String target = upper(request.status());
    if (!STATUSES.contains(target)) {
      throw badRequest("策略状态只能是 " + String.join(" / ", STATUSES));
    }
    if ("ACTIVE".equals(target)) {
      // 🔴 激活前自检：目标必须有值（没有目标就反推不出建议），区间必须自洽。
      BigDecimal acos = row.get("target_acos", BigDecimal.class);
      BigDecimal roas = row.get("target_roas", BigDecimal.class);
      if (acos == null && roas == null) {
        throw unprocessable("激活前必须给一个目标（目标 ACOS 或目标 ROAS，二选一）");
      }
      BigDecimal minBid = row.get("min_bid", BigDecimal.class);
      BigDecimal maxBid = row.get("max_bid", BigDecimal.class);
      if (minBid != null && maxBid != null && maxBid.compareTo(minBid) < 0) {
        throw unprocessable("竞价范围上限低于下限，先把区间改对再激活");
      }
    }
    sql.execute(
        "update ad_bidding_policy set status=?, note=?, version=version+1, updated_at=?::timestamptz"
            + " where tenant_id=? and id=?",
        target,
        text(request.note()),
        dbNow(),
        tenant,
        policyId);
    if ("ACTIVE".equals(target)) {
      // 🔴 激活 = 平台侧要知道这条策略的档位 ⇒ 进待下发队列。
      enqueueStrategyDispatch(tenant, policyId);
    }
    return policy(id);
  }

  /** 动态竞价策略进待下发队列（**不猜平台的具体字段名**）。 */
  private void enqueueStrategyDispatch(UUID tenant, UUID policyId) {
    Record p =
        sql.fetchOne(
            "select p.*, f.code platform_code, s.id shop_id from ad_bidding_policy p"
                + " join platform f on f.id=p.platform_id"
                + " join shop s on s.tenant_id=p.tenant_id and s.code=p.shop_code"
                + " where p.tenant_id=? and p.id=?",
            tenant,
            policyId);
    if (p == null) {
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("policyCode", text(p.get("policy_code", String.class)));
    payload.put("shopCode", text(p.get("shop_code", String.class)));
    payload.put("platformCode", text(p.get("platform_code", String.class)));
    payload.put("dynamicBiddingStrategy", text(p.get("dynamic_bidding_strategy", String.class)));
    payload.put("scope", text(p.get("scope", String.class)));
    payload.put("targetCampaignId", textOrEmpty(p.get("target_campaign_id", UUID.class)));
    String externalCampaignId = "";
    UUID campaignId = p.get("target_campaign_id", UUID.class);
    if (campaignId != null) {
      String raw =
          one(
              String.class,
              "select external_campaign_id from ad_campaign where tenant_id=? and id=?",
              tenant,
              campaignId);
      externalCampaignId = raw == null ? "" : raw;
    }
    OffsetDateTime now = dbNow();
    sql.execute(
        "insert into ad_bidding_dispatch (id, tenant_id, policy_id, platform_id, shop_code,"
            + " external_campaign_id, external_keyword_id, action_type, action_payload,"
            + " dispatch_status, note, created_at, updated_at)"
            + " values (?,?,?,?,?,?,'','SET_BIDDING_STRATEGY',?::jsonb,'PENDING',?,"
            + "?::timestamptz,?::timestamptz)",
        Ids.next(),
        tenant,
        policyId,
        p.get("platform_id", UUID.class),
        text(p.get("shop_code", String.class)),
        externalCampaignId,
        writeJson(payload),
        "动态竞价策略变更待下发（平台广告 API 未交付）",
        now,
        now);
  }

  // ==================================================================
  // 竞价候选清单（关键词）
  // ==================================================================

  /** 🔴 空 UUID（没有生效的归因规则时占位 ⇒ 匹配不到任何归因事实）。 */
  private static final UUID ZERO_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

  public AdBiddingKeywordPage keywordCandidates(
      String shopCode, String keyword, String status, LocalDate from, LocalDate to, int page, int size) {
    UUID tenant = currentTenant();
    int sizeBound = clampSize(size);
    int offset = Math.max(page, 0) * sizeBound;
    LocalDate today = dbToday();
    LocalDate windowFrom = from == null ? today.minusDays(DEFAULT_WINDOW_DAYS - 1) : from;
    LocalDate windowTo = to == null ? today : to;

    StringBuilder where = new StringBuilder(" where k.tenant_id = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    if (!text(shopCode).isEmpty()) {
      where.append(" and sh.code = ?");
      binds.add(text(shopCode));
    }
    if (!text(status).isEmpty()) {
      where.append(" and k.status = ?");
      binds.add(upper(status));
    }
    if (!text(keyword).isEmpty()) {
      where.append(" and lower(k.keyword) like ?");
      binds.add("%" + text(keyword).toLowerCase(Locale.ROOT) + "%");
    }

    // 🔴 CTE 与主查询共用同一套绑定顺序（ls 的租户 → kwmap 的租户 → lateral 的窗口 → where）。
    String cte =
        """
        with ls as (
          select lv.listing_id, lv.internal_sku_id,
                 count(*) over (partition by lv.listing_id) sku_count
            from (select distinct listing_id, internal_sku_id
                    from listing_variant
                   where tenant_id = ? and internal_sku_id is not null) lv
        ),
        kwmap as (
          select lk2.id listing_keyword_id, ls.listing_id, ls.internal_sku_id, ls.sku_count
            from listing_keyword lk2
            join ls on ls.listing_id = lk2.listing_id
           where lk2.tenant_id = ?
        )
        """;

    String fromBody =
        """
          from ad_keyword k
          join shop sh on sh.tenant_id = k.tenant_id and sh.id = k.shop_id
          join platform f on f.id = k.platform_id
          left join lateral (
            select sum(mt.spend) spend, sum(mt.sales) sales,
                   count(distinct mt.bucket_date) days, max(mt.synced_at) synced_at
              from ad_keyword_metric mt
             where mt.tenant_id = k.tenant_id and mt.keyword_id = k.id
               and mt.bucket_date between ? and ?
          ) m on true
          left join kwmap kwm on kwm.listing_keyword_id = k.listing_keyword_id
        """
            + where;

    List<Object> countBinds = new ArrayList<>();
    countBinds.add(tenant);
    countBinds.add(tenant);
    countBinds.add(windowFrom);
    countBinds.add(windowTo);
    countBinds.addAll(binds);
    long total = count(cte + " select count(*)" + fromBody, countBinds.toArray());

    List<Object> pageBinds = new ArrayList<>(countBinds);
    pageBinds.add(sizeBound);
    pageBinds.add(offset);

    List<Record> rows =
        sql.fetch(
            cte
                + """
                select k.id, k.keyword, k.shop_id, sh.code shop_code, f.code platform_code, k.status,
                       k.bid, k.bid_currency, k.metric_currency,
                       coalesce(m.spend,0) window_spend, coalesce(m.sales,0) window_sales,
                       coalesce(m.days,0) window_days, m.synced_at,
                       k.external_campaign_id, k.external_keyword_id,
                       kwm.listing_keyword_id, kwm.internal_sku_id, kwm.sku_count
                """
                + fromBody
                + " order by k.keyword, k.id limit ? offset ?",
            pageBinds.toArray());

    // 🔴 SKU 编码要经 catalog 解析（只读 API；**不传租户号**）。
    Set<UUID> skuIds = new LinkedHashSet<>();
    for (Record r : rows) {
      UUID skuId = r.get("internal_sku_id", UUID.class);
      if (skuId != null) {
        skuIds.add(skuId);
      }
    }
    AdProductCatalogPort.CatalogSnapshot snapshot = catalog.resolveAll(skuIds, "");

    List<AdBiddingKeywordRow> items = new ArrayList<>();
    for (Record r : rows) {
      UUID skuId = r.get("internal_sku_id", UUID.class);
      Integer skuCount = r.get("sku_count", Integer.class);
      String skuCode = "";
      String spuCode = "";
      boolean mapped = skuId != null;
      String skuNote;
      if (skuId == null) {
        skuNote = "该关键词还没关联到商品（listing_keyword_id 为空）⇒ 归不到 SKU ⇒ 按 SKU 反推目标 ACOS 这一路走不通";
      } else if (!snapshot.available()) {
        skuNote = "🔴 商品主数据（catalog）这次读不到 ⇒ SKU 编码取不到：" + snapshot.unavailableReason();
      } else {
        AdProductCatalogPort.SkuIdentity identity = snapshot.bySkuId().get(skuId);
        if (identity == null) {
          skuNote = "catalog 里没有这个 SKU ⇒ SKU 编码取不到（**不是**「catalog 挂了」）";
        } else {
          skuCode = identity.skuCode();
          spuCode = identity.spuCode();
          skuNote =
              "该商品挂了 "
                  + (skuCount == null ? 1 : skuCount)
                  + " 个内部 SKU ⇒ 广告花费按该数量**等分**摊到每个 SKU"
                  + "（照抄 14.3.1.3 的两级等分口径）";
        }
      }
      items.add(
          new AdBiddingKeywordRow(
              r.get("id", UUID.class).toString(),
              r.get("keyword", String.class),
              r.get("shop_code", String.class),
              r.get("platform_code", String.class),
              r.get("status", String.class),
              r.get("bid", BigDecimal.class),
              text(r.get("bid_currency", String.class)),
              text(r.get("metric_currency", String.class)),
              r.get("window_spend", BigDecimal.class),
              r.get("window_sales", BigDecimal.class),
              r.get("window_days", Integer.class) == null ? 0 : r.get("window_days", Integer.class),
              r.get("external_campaign_id", String.class),
              r.get("external_keyword_id", String.class),
              skuId == null ? "" : skuId.toString(),
              skuCode,
              spuCode,
              mapped,
              skuNote,
              r.get("synced_at", OffsetDateTime.class)));
    }
    return new AdBiddingKeywordPage(items, total, Math.max(page, 0), sizeBound);
  }

  // ==================================================================
  // 目标 ACOS 反推竞价建议
  // ==================================================================

  public AdBiddingSuggestionView generateSuggestion(
      AdBiddingSuggestionRequest request, String bearer) {
    UUID tenant = currentTenant();
    String idempotencyKey = text(request.idempotencyKey());
    if (idempotencyKey.isEmpty()) {
      throw badRequest("幂等键不能为空 —— 建议批次要能追溯，重算一次就得看得见");
    }
    UUID policyId = parseId(request.policyId());
    Record policyRow =
        sql.fetchOne("select * from ad_bidding_policy where tenant_id=? and id=?", tenant, policyId);
    if (policyRow == null) {
      throw notFound("竞价策略");
    }
    if ("ARCHIVED".equals(text(policyRow.get("status", String.class)))) {
      throw unprocessable("已归档的策略不能再生成建议");
    }

    // 🔴 幂等：这一批已经生成过了 ⇒ 返回既有批次，不重算。
    Record existing =
        sql.fetchOne(
            "select id from ad_bidding_suggestion where tenant_id=? and idempotency_key=?",
            tenant,
            idempotencyKey);
    if (existing != null) {
      return suggestion(existing.get("id", UUID.class).toString());
    }

    LocalDate today = dbToday();
    LocalDate windowFrom = request.from() == null ? today.minusDays(DEFAULT_WINDOW_DAYS - 1) : request.from();
    LocalDate windowTo = request.to() == null ? today : request.to();
    if (windowTo.isBefore(windowFrom)) {
      throw badRequest("口径窗口的止不能早于起");
    }
    if (windowFrom.plusDays(MAX_WINDOW_DAYS).isBefore(windowTo)) {
      throw badRequest("口径窗口最多 " + MAX_WINDOW_DAYS + " 天（收到 " + (windowTo.toEpochDay() - windowFrom.toEpochDay() + 1) + " 天）");
    }

    BigDecimal storedAcos = policyRow.get("target_acos", BigDecimal.class);
    BigDecimal storedRoas = policyRow.get("target_roas", BigDecimal.class);
    BigDecimal targetAcos = storedAcos != null ? storedAcos : oneOver(storedRoas);
    BigDecimal targetRoas = storedRoas != null ? storedRoas : oneOver(storedAcos);
    if (targetAcos == null || targetAcos.signum() <= 0) {
      throw unprocessable("策略上没有目标（目标 ACOS 或目标 ROAS，二选一）⇒ 反推不出建议竞价");
    }
    String currency = text(policyRow.get("currency", String.class));
    String shopCode = text(policyRow.get("shop_code", String.class));
    UUID shopId = shopIdOf(tenant, shopCode);

    // ---- 归因口径（14.3.2.1：口径固定并公示，只取生效规则当前版本）----
    Record rule =
        sql.fetchOne(
            "select id, rule_code, version, attribution_model, attribution_window_days"
                + " from ad_attribution_rule where tenant_id=? and status='ACTIVE'",
            tenant);
    UUID ruleId = rule == null ? null : rule.get("id", UUID.class);
    Long ruleVersion = rule == null ? null : rule.get("version", Long.class);

    // ---- 关键词 → SKU 的映射 + 窗口内事实 ----
    StringBuilder campaignFilter = new StringBuilder();
    Object campaignId = policyRow.get("target_campaign_id", UUID.class);
    if (campaignId != null) {
      String external =
          one(
              String.class,
              "select external_campaign_id from ad_campaign where tenant_id=? and id=?",
              tenant,
              campaignId);
      if (external != null && !external.isBlank()) {
        campaignFilter.append(" and k.external_campaign_id = ?");
      }
    }
    String externalCampaign =
        campaignId == null
            ? null
            : one(
                String.class,
                "select external_campaign_id from ad_campaign where tenant_id=? and id=?",
                tenant,
                campaignId);

    List<Object> binds = new ArrayList<>();
    binds.add(tenant); // ls 的租户
    binds.add(tenant); // kw 的租户
    binds.add(shopId);
    if (externalCampaign != null && !externalCampaign.isBlank()) {
      binds.add(externalCampaign);
    }
    binds.add(tenant); // metric 的租户
    binds.add(windowFrom);
    binds.add(windowTo);
    binds.add(tenant); // attr 的租户
    // 🔴 没有生效的归因规则 ⇒ 归因口径未定 ⇒ 用零 UUID 占位（匹配不到任何事实）。
    binds.add(ruleId == null ? ZERO_UUID : ruleId);
    binds.add(ruleVersion == null ? -1L : ruleVersion);
    binds.add(windowFrom.atStartOfDay().atOffset(ZoneOffset.UTC));
    binds.add(windowTo.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));

    String sqlText =
        """
        with ls as (
          select lv.listing_id, lv.internal_sku_id,
                 count(*) over (partition by lv.listing_id) sku_count
            from (select distinct listing_id, internal_sku_id
                    from listing_variant
                   where tenant_id = ? and internal_sku_id is not null) lv
        ),
        kw as (
          select k.id, k.keyword, k.bid, k.bid_currency, k.metric_currency,
                 k.external_campaign_id, k.external_keyword_id,
                 ls.internal_sku_id, ls.sku_count
            from ad_keyword k
            join listing_keyword lk on lk.tenant_id = k.tenant_id and lk.id = k.listing_keyword_id
            join ls on ls.listing_id = lk.listing_id
           where k.tenant_id = ? and k.shop_id = ? and k.status <> 'ARCHIVED'
        """
            + campaignFilter.toString()
            + """
          ),
        metric as (
          select mt.keyword_id, sum(mt.spend) spend, sum(mt.sales) sales,
                 count(distinct mt.bucket_date) days, max(mt.synced_at) synced_at
            from ad_keyword_metric mt
           where mt.tenant_id = ? and mt.bucket_date between ? and ?
             and mt.keyword_id in (select id from kw)
           group by mt.keyword_id
        ),
        attr as (
          select f.internal_sku_id, sum(f.attributed_sales) attributed_sales,
                 max(f.attributed_at) attributed_at, count(*) fact_count
            from ad_attribution_fact f
           where f.tenant_id = ? and f.attributed = true
             and f.rule_id = ? and f.rule_version = ?
             and f.order_at >= ?::timestamptz and f.order_at < ?::timestamptz
             and f.internal_sku_id in (select distinct internal_sku_id from kw)
           group by f.internal_sku_id
        )
        select kw.id keyword_id, kw.keyword, kw.bid, kw.bid_currency, kw.metric_currency,
               kw.external_campaign_id, kw.external_keyword_id,
               kw.internal_sku_id, kw.sku_count,
               coalesce(metric.spend,0) spend, coalesce(metric.sales,0) sales,
               coalesce(metric.days,0) days, metric.synced_at,
               coalesce(attr.attributed_sales,0) attributed_sales,
               attr.attributed_at, coalesce(attr.fact_count,0) fact_count
          from kw
          left join metric on metric.keyword_id = kw.id
          left join attr on attr.internal_sku_id = kw.internal_sku_id
        """;

    List<Record> rows = sql.fetch(sqlText, binds.toArray());

    // ---- 币种筛选（策略一个币种；不一致的关键词**不在本策略的口径内**）----
    List<Record> eligible = new ArrayList<>();
    int skippedByCurrency = 0;
    for (Record r : rows) {
      String metricCurrency = text(r.get("metric_currency", String.class));
      String bidCurrency = text(r.get("bid_currency", String.class));
      boolean ok =
          (metricCurrency.isEmpty() || metricCurrency.equals(currency))
              && (bidCurrency.isEmpty() || bidCurrency.equals(currency));
      if (ok) {
        eligible.add(r);
      } else {
        skippedByCurrency++;
      }
    }

    // ---- 按 SKU 归集（listing → SKU 等分）----
    Map<UUID, SkuBasis> skuBasis = new LinkedHashMap<>();
    Map<UUID, List<Record>> skuKeywords = new LinkedHashMap<>();
    for (Record r : eligible) {
      UUID skuId = r.get("internal_sku_id", UUID.class);
      if (skuId == null) {
        continue;
      }
      SkuBasis basis = skuBasis.computeIfAbsent(skuId, k -> new SkuBasis());
      Integer skuCount = r.get("sku_count", Integer.class);
      int divisor = skuCount == null || skuCount <= 0 ? 1 : skuCount;
      BigDecimal spend = nz(r.get("spend", BigDecimal.class));
      basis.spend = basis.spend.add(spend.divide(BigDecimal.valueOf(divisor), 4, RoundingMode.HALF_UP));
      basis.platformSales =
          basis.platformSales.add(
              nz(r.get("sales", BigDecimal.class)).divide(BigDecimal.valueOf(divisor), 4, RoundingMode.HALF_UP));
      basis.attributedSales = basis.attributedSales.add(nz(r.get("attributed_sales", BigDecimal.class)));
      basis.keywordCount++;
      OffsetDateTime synced = r.get("synced_at", OffsetDateTime.class);
      if (synced != null && (basis.dataAsOf == null || synced.isAfter(basis.dataAsOf))) {
        basis.dataAsOf = synced;
      }
      OffsetDateTime attributedAt = r.get("attributed_at", OffsetDateTime.class);
      if (attributedAt != null && (basis.dataAsOf == null || attributedAt.isAfter(basis.dataAsOf))) {
        basis.dataAsOf = attributedAt;
      }
      skuKeywords.computeIfAbsent(skuId, k -> new ArrayList<>()).add(r);
    }

    // ---- 利润侧（13.4.1.2，只经授权只读 API；读不到 ⇒ 503）----
    AdProductProfitPort.ProfitSnapshot snapshot =
        profit.fetch(windowFrom, windowTo, bearer);
    if (!snapshot.available()) {
      throw upstreamUnavailable(
          "13.4.1.2 SKU 利润（reporting 服务）读不到："
              + snapshot.unavailableReason()
              + " —— 目标 ACOS 反推竞价**必须**先知道「这个价投下去赚不赚钱」，"
              + "此时给出一批没校验过盈亏的建议，比直接报错危险得多。");
    }

    // ---- SKU 编码（catalog，只读 API；读不到 ⇒ 503）----
    Set<UUID> skuIds = new LinkedHashSet<>(skuBasis.keySet());
    AdProductCatalogPort.CatalogSnapshot identities = catalog.resolveAll(skuIds, bearer);
    if (!identities.available()) {
      throw upstreamUnavailable(
          "03 商品主数据（catalog 服务）读不到："
              + identities.unavailableReason()
              + " —— 没有 SKU 编码就取不到 13.4.1.2 的 SKU 利润 ⇒ 算不出盈亏平衡 ACOS。");
    }

    // ---- 落批次 ----
    UUID suggestionId = Ids.next();
    OffsetDateTime now = dbNow();
    Map<String, Object> basis = new LinkedHashMap<>();
    basis.put("formula", "建议竞价 = 该关键词的当前竞价 × (目标 ACOS ÷ 当前 ACOS)；当前 ACOS = 花费 ÷ 归因销售额");
    basis.put("windowFrom", windowFrom.toString());
    basis.put("windowTo", windowTo.toString());
    basis.put("targetAcos", ratio(targetAcos));
    basis.put("targetRoas", ratio(targetRoas));
    basis.put("allocation", "listing → 内部 SKU 按当前登记的 SKU 变体数等分（照抄 14.3.1.3 的两级等分口径）");
    Map<String, Object> gates = new LinkedHashMap<>();
    gates.put("rangeMinBid", policyRow.get("min_bid", BigDecimal.class));
    gates.put("rangeMaxBid", policyRow.get("max_bid", BigDecimal.class));
    gates.put("policyAdjustRatio", policyRow.get("single_adjust_cap_ratio", BigDecimal.class));
    gates.put("policyAdjustAmount", policyRow.get("single_adjust_cap_amount", BigDecimal.class));
    gates.put("upstreamAdjustRatio", AdKeywordService.MAX_BID_DELTA_RATIO);
    gates.put("upstreamBidAbsolute", AdKeywordService.MAX_BID_ABSOLUTE);
    gates.put("upstreamTotalIncrease", AdKeywordService.MAX_TOTAL_BID_INCREASE);
    basis.put("gates", gates);
    Map<String, Object> attribution = new LinkedHashMap<>();
    attribution.put("ruleCode", rule == null ? "" : text(rule.get("rule_code", String.class)));
    attribution.put("ruleVersion", ruleVersion);
    attribution.put("model", rule == null ? "" : text(rule.get("attribution_model", String.class)));
    attribution.put("windowDays", rule == null ? null : rule.get("attribution_window_days", Integer.class));
    attribution.put(
        "note",
        rule == null
            ? "本租户没有生效的归因规则 ⇒ 归因口径未定 ⇒ ACOS 不可比 ⇒ 算不出建议竞价"
            : "只取生效规则**当前版本**的归因事实（口径固定并公示）");
    basis.put("attribution", attribution);
    basis.put("skippedByCurrencyCount", skippedByCurrency);
    basis.put(
        "skippedByCurrencyNote",
        skippedByCurrency == 0
            ? ""
            : "有 " + skippedByCurrency + " 条关键词的币种与策略币种不一致 ⇒ 不在本策略口径内"
                + "（**不跨币种相加**）");
    basis.put("profitAvailable", true);
    basis.put("profitDataUpdatedAt", snapshot.dataUpdatedAt());

    OffsetDateTime dataAsOf = null;
    for (SkuBasis b : skuBasis.values()) {
      if (b.dataAsOf != null && (dataAsOf == null || b.dataAsOf.isAfter(dataAsOf))) {
        dataAsOf = b.dataAsOf;
      }
    }
    if (dataAsOf == null) {
      dataAsOf = now;
      basis.put("dataNote", "窗口内没有事实 ⇒ 数据截至时刻取生成时刻");
    }

    sql.execute(
        "insert into ad_bidding_suggestion (id, tenant_id, policy_id, window_from, window_to,"
            + " target_acos, target_roas, basis, attribution_rule_code, attribution_rule_version,"
            + " attribution_model, attribution_window_days, profit_available,"
            + " profit_unavailable_reason, data_as_of, status, sku_count, suggested_count,"
            + " rejected_count, idempotency_key, note, created_at, updated_at)"
            + " values (?,?,?,?,?,?,?,?::jsonb,?,?,?,?,true,'',?::timestamptz,'READY',0,0,0,?,?,"
            + "?::timestamptz,?::timestamptz)",
        suggestionId,
        tenant,
        policyId,
        windowFrom,
        windowTo,
        ratio(targetAcos),
        ratio(targetRoas),
        writeJson(basis),
        rule == null ? "" : text(rule.get("rule_code", String.class)),
        ruleVersion,
        rule == null ? "" : text(rule.get("attribution_model", String.class)),
        rule == null ? null : rule.get("attribution_window_days", Integer.class),
        dataAsOf,
        idempotencyKey,
        text(request.note()),
        now,
        now);

    // ---- SKU 级口径行 ----
    Map<UUID, UUID> skuRowId = new LinkedHashMap<>();
    for (Map.Entry<UUID, SkuBasis> entry : skuBasis.entrySet()) {
      UUID skuId = entry.getKey();
      SkuBasis b = entry.getValue();
      AdProductCatalogPort.SkuIdentity identity = identities.bySkuId().get(skuId);
      String skuCode = identity == null ? "" : identity.skuCode();

      BigDecimal breakEven = null;
      String profitStatus;
      if (identity == null || skuCode.isBlank()) {
        profitStatus = "NO_DATA";
      } else {
        AdProductProfitPort.SkuProfit p = snapshot.bySkuAndCurrency().get(skuCode + "|" + currency);
        if (p == null) {
          profitStatus = "NO_DATA";
        } else if (!p.netProfitComparable() || p.netProfit() == null || p.revenue() == null) {
          profitStatus = "NOT_COMPARABLE";
        } else {
          BigDecimal revenue = p.revenue();
          BigDecimal adFee = p.adFee() == null ? BigDecimal.ZERO : p.adFee();
          if (revenue.signum() <= 0) {
            profitStatus = "NOT_COMPARABLE";
          } else {
            // 🔴 盈亏平衡 ACOS =（净利 + 广告费）÷ 单品收入
            //   （净利已经扣过广告费，加回来才是「广告前」的利润率）
            breakEven =
                p.netProfit()
                    .add(adFee)
                    .divide(revenue, 6, RoundingMode.HALF_UP);
            if (breakEven.signum() < 0) {
              breakEven = BigDecimal.ZERO;
            }
            profitStatus = "AVAILABLE";
          }
        }
      }

      UUID rowId = Ids.next();
      skuRowId.put(skuId, rowId);
      sql.execute(
          "insert into ad_bidding_suggestion_sku (id, tenant_id, suggestion_id, sku_id, sku_code,"
              + " spend, attributed_sales, currency, break_even_acos, profit_status, bid_ratio,"
              + " status, guardrail_code, reason, created_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,null,'SUGGESTED','','',?::timestamptz)",
          rowId,
          tenant,
          suggestionId,
          skuId,
          skuCode,
          money(b.spend),
          money(b.attributedSales),
          currency,
          breakEven,
          profitStatus,
          now);
    }

    // 🔴 倍率与结论**用 DB 自己算出来的 GENERATED 列**（current_acos）来定 ⇒
    //    页面上的倍率与展示的 ACOS **必然自洽**（不会差最后一位）。
    sql.execute(
        "update ad_bidding_suggestion_sku s"
            + " set bid_ratio = case when s.current_acos > 0 then round(?::numeric / s.current_acos, 6) end,"
            + "     status = case"
            + "       when s.current_acos is null or s.current_acos <= 0 then 'REJECTED'"
            + "       when s.break_even_acos is not null and ?::numeric > s.break_even_acos then 'REJECTED'"
            + "       else 'SUGGESTED' end,"
            + "     guardrail_code = case"
            + "       when s.current_acos is null or s.current_acos <= 0 then 'NO_BASIS'"
            + "       when s.break_even_acos is not null and ?::numeric > s.break_even_acos then 'ABOVE_BREAK_EVEN'"
            + "       else '' end,"
            + "     reason = case"
            + "       when s.current_acos is null or s.current_acos <= 0 then"
            + "         '归因销售额为 0 ⇒ ACOS 不可比 ⇒ 算不出建议竞价（**不是**「效率为零」）'"
            + "       when s.break_even_acos is not null and ?::numeric > s.break_even_acos then"
            + "         '目标 ACOS 高于盈亏平衡 ACOS ⇒ 按这个价投下去是亏的'"
            + "       when s.break_even_acos is null then"
            + "         '利润侧这次算不出盈亏平衡 ACOS ⇒ 本次**未做**「会不会亏」的校验'"
            + "       else '' end"
            + " where s.tenant_id = ? and s.suggestion_id = ?",
        ratio(targetAcos),
        ratio(targetAcos),
        ratio(targetAcos),
        ratio(targetAcos),
        tenant,
        suggestionId);

    // ---- 关键词级建议 ----
    List<Record> skuRows =
        sql.fetch(
            "select id, sku_id, bid_ratio, status, guardrail_code, reason"
                + " from ad_bidding_suggestion_sku where tenant_id=? and suggestion_id=?",
            tenant,
            suggestionId);
    Map<UUID, Record> skuRowBySku = new LinkedHashMap<>();
    for (Record r : skuRows) {
      skuRowBySku.put(r.get("sku_id", UUID.class), r);
    }

    BigDecimal minBid = policyRow.get("min_bid", BigDecimal.class);
    BigDecimal maxBid = policyRow.get("max_bid", BigDecimal.class);
    BigDecimal capRatio = effectiveRatioCap(policyRow);
    BigDecimal capAmount = effectiveAmountCap(policyRow);

    for (Map.Entry<UUID, List<Record>> entry : skuKeywords.entrySet()) {
      UUID skuId = entry.getKey();
      Record skuRow = skuRowBySku.get(skuId);
      if (skuRow == null) {
        continue;
      }
      BigDecimal bidRatio = skuRow.get("bid_ratio", BigDecimal.class);
      String skuStatus = text(skuRow.get("status", String.class));
      String skuGuardrail = text(skuRow.get("guardrail_code", String.class));
      String skuReason = text(skuRow.get("reason", String.class));
      for (Record kw : entry.getValue()) {
        BigDecimal currentBid = nz(kw.get("bid", BigDecimal.class));
        BigDecimal suggestedBid = null;
        BigDecimal delta = null;
        String status = "SUGGESTED";
        String guardrail = "";
        String reason = "";
        if (bidRatio != null && bidRatio.signum() > 0) {
          suggestedBid = money(currentBid.multiply(bidRatio));
          delta = money(suggestedBid.subtract(currentBid));
          // 🔴 两道闸逐条判（**绝不静默截断**）。
          Gate gate = rangeGate(suggestedBid, minBid, maxBid);
          if (gate == null) {
            gate = adjustGate(currentBid, suggestedBid, capRatio, capAmount);
          }
          if (gate != null) {
            status = "REJECTED";
            guardrail = gate.code();
            reason = gate.reason();
          }
        } else {
          status = "REJECTED";
          guardrail = skuGuardrail.isEmpty() ? "NO_BASIS" : skuGuardrail;
          reason = skuReason.isEmpty() ? "算不出建议竞价" : skuReason;
        }
        sql.execute(
            "insert into ad_bidding_suggestion_item (id, tenant_id, suggestion_id,"
                + " suggestion_sku_id, keyword_id, keyword_text, current_bid, bid_currency,"
                + " suggested_bid, bid_delta, status, guardrail_code, reason, created_at)"
                + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
            Ids.next(),
            tenant,
            suggestionId,
            skuRow.get("id", UUID.class),
            kw.get("keyword_id", UUID.class),
            text(kw.get("keyword", String.class)),
            money(currentBid),
            text(kw.get("bid_currency", String.class)),
            suggestedBid,
            delta,
            status,
            guardrail,
            reason,
            now);
      }
      // SKU 级被否掉的（盈亏平衡 / 不可比），这一 SKU 下的关键词**逐条**也标否决并写明原因。
      if (!"SUGGESTED".equals(skuStatus)) {
        sql.execute(
            "update ad_bidding_suggestion_item i"
                + " set status='REJECTED', guardrail_code=?, reason=?"
                + " where i.tenant_id=? and i.suggestion_sku_id=? and i.status='SUGGESTED'",
            skuGuardrail.isEmpty() ? "NO_BASIS" : skuGuardrail,
            skuReason.isEmpty() ? "这个 SKU 算不出建议竞价" : skuReason,
            tenant,
            skuRow.get("id", UUID.class));
      }
    }

    // ---- 批次统计 ----
    sql.execute(
        "update ad_bidding_suggestion g"
            + " set sku_count = (select count(*) from ad_bidding_suggestion_sku s"
            + "                   where s.tenant_id=g.tenant_id and s.suggestion_id=g.id),"
            + "     suggested_count = (select count(*) from ad_bidding_suggestion_sku s"
            + "                   where s.tenant_id=g.tenant_id and s.suggestion_id=g.id"
            + "                   and s.status='SUGGESTED'),"
            + "     rejected_count = (select count(*) from ad_bidding_suggestion_sku s"
            + "                   where s.tenant_id=g.tenant_id and s.suggestion_id=g.id"
            + "                   and s.status='REJECTED'),"
            + "     status = case"
            + "       when (select count(*) from ad_bidding_suggestion_sku s"
            + "              where s.tenant_id=g.tenant_id and s.suggestion_id=g.id) = 0 then 'EMPTY'"
            + "       when (select count(*) from ad_bidding_suggestion_sku s"
            + "              where s.tenant_id=g.tenant_id and s.suggestion_id=g.id"
            + "              and s.status='REJECTED') > 0 then 'PARTIAL'"
            + "       else 'READY' end,"
            + "     updated_at=?::timestamptz"
            + " where g.tenant_id=? and g.id=?",
        dbNow(),
        tenant,
        suggestionId);

    return suggestion(suggestionId.toString());
  }

  public AdBiddingSuggestionPage suggestions(String policyId, int page, int size) {
    UUID tenant = currentTenant();
    int sizeBound = clampSize(size);
    int offset = Math.max(page, 0) * sizeBound;
    StringBuilder where = new StringBuilder(" where g.tenant_id = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    if (!text(policyId).isEmpty()) {
      where.append(" and g.policy_id = ?");
      binds.add(parseId(policyId));
    }
    long total = count("select count(*) from ad_bidding_suggestion g" + where, binds.toArray());
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(sizeBound);
    pageBinds.add(offset);
    List<AdBiddingSuggestionSummary> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select g.*, p.policy_name, p.shop_code, s.name shop_name from ad_bidding_suggestion g"
                + " join ad_bidding_policy p on p.tenant_id=g.tenant_id and p.id=g.policy_id"
                + " join shop s on s.tenant_id=g.tenant_id and s.code=p.shop_code"
                + where
                + " order by g.created_at desc, g.id limit ? offset ?",
            pageBinds.toArray())) {
      items.add(suggestionSummary(r));
    }
    return new AdBiddingSuggestionPage(items, total, Math.max(page, 0), sizeBound);
  }

  public AdBiddingSuggestionView suggestion(String id) {
    UUID tenant = currentTenant();
    Record head =
        sql.fetchOne(
            "select g.*, p.policy_name, p.shop_code, s.name shop_name from ad_bidding_suggestion g"
                + " join ad_bidding_policy p on p.tenant_id=g.tenant_id and p.id=g.policy_id"
                + " join shop s on s.tenant_id=g.tenant_id and s.code=p.shop_code"
                + " where g.tenant_id=? and g.id=?",
            tenant,
            parseId(id));
    if (head == null) {
      throw notFound("竞价建议批次");
    }
    List<AdBiddingSuggestionSkuView> skus = new ArrayList<>();
    for (Record sr :
        sql.fetch(
            "select * from ad_bidding_suggestion_sku where tenant_id=? and suggestion_id=? order by sku_code, id",
            tenant,
            parseId(id))) {
      List<AdBiddingSuggestionItemView> items = new ArrayList<>();
      for (Record ir :
          sql.fetch(
              "select * from ad_bidding_suggestion_item where tenant_id=? and suggestion_sku_id=? order by keyword_text, id",
              tenant,
              sr.get("id", UUID.class))) {
        items.add(
            new AdBiddingSuggestionItemView(
                ir.get("id", UUID.class).toString(),
                ir.get("keyword_id", UUID.class).toString(),
                text(ir.get("keyword_text", String.class)),
                ir.get("current_bid", BigDecimal.class),
                text(ir.get("bid_currency", String.class)),
                ir.get("suggested_bid", BigDecimal.class),
                ir.get("bid_delta", BigDecimal.class),
                text(ir.get("status", String.class)),
                text(ir.get("guardrail_code", String.class)),
                text(ir.get("reason", String.class))));
      }
      skus.add(
          new AdBiddingSuggestionSkuView(
              sr.get("id", UUID.class).toString(),
              sr.get("sku_id", UUID.class).toString(),
              text(sr.get("sku_code", String.class)),
              sr.get("spend", BigDecimal.class),
              sr.get("attributed_sales", BigDecimal.class),
              text(sr.get("currency", String.class)),
              sr.get("current_acos", BigDecimal.class),
              sr.get("current_roas", BigDecimal.class),
              sr.get("break_even_acos", BigDecimal.class),
              text(sr.get("profit_status", String.class)),
              sr.get("bid_ratio", BigDecimal.class),
              text(sr.get("status", String.class)),
              text(sr.get("guardrail_code", String.class)),
              text(sr.get("reason", String.class)),
              items));
    }
    AdBiddingSuggestionSummary summary = suggestionSummary(head);
    return new AdBiddingSuggestionView(
        summary.id(),
        summary.policyId(),
        summary.policyName(),
        summary.shopCode(),
        summary.shopName(),
        summary.windowFrom(),
        summary.windowTo(),
        summary.targetAcos(),
        summary.targetRoas(),
        summary.attributionRuleCode(),
        summary.attributionRuleVersion(),
        summary.attributionModel(),
        summary.attributionWindowDays(),
        summary.profitAvailable(),
        summary.profitUnavailableReason(),
        summary.dataAsOf(),
        summary.status(),
        summary.skuCount(),
        summary.suggestedCount(),
        summary.rejectedCount(),
        readJson(head.get("basis")),
        summary.note(),
        summary.createdAt(),
        skus);
  }

  // ==================================================================
  // 批量竞价调整（**走 14.1.3.1 已交付的写路径**）
  // ==================================================================

  public AdBiddingAdjustmentView adjust(AdBiddingAdjustmentRequest request, String bearer) {
    UUID tenant = currentTenant();
    String idempotencyKey = text(request.idempotencyKey());
    if (idempotencyKey.isEmpty()) {
      throw badRequest("幂等键不能为空 —— 调价是花钱的动作，重复执行就是事故");
    }
    UUID policyId = parseId(request.policyId());
    Record policyRow =
        sql.fetchOne("select * from ad_bidding_policy where tenant_id=? and id=?", tenant, policyId);
    if (policyRow == null) {
      throw notFound("竞价策略");
    }
    if ("ARCHIVED".equals(text(policyRow.get("status", String.class)))) {
      throw unprocessable("已归档的策略不能再调价");
    }
    String shopCode = text(policyRow.get("shop_code", String.class));
    UUID shopId = shopIdOf(tenant, shopCode);
    if (shopId == null) {
      throw notFound("店铺");
    }
    String policyCurrency = text(policyRow.get("currency", String.class));
    String currency = text(request.currency()).isEmpty() ? policyCurrency : upper(request.currency());
    if (!currency.equals(policyCurrency)) {
      throw badRequest(
          "调价币种与策略币种不一致（策略 "
              + policyCurrency
              + " vs 本次 "
              + currency
              + "）—— 不按同一个数把不同币种一起调");
    }

    // 🔴 幂等：这一批跑过没有
    Record existing =
        sql.fetchOne(
            "select id from ad_bidding_adjustment where tenant_id=? and idempotency_key=?",
            tenant,
            idempotencyKey);
    if (existing != null) {
      return adjustment(existing.get("id", UUID.class).toString());
    }

    // ---- 目标：手工给，或者从建议批次里取 ----
    List<AdBiddingAdjustTarget> targets = new ArrayList<>();
    String suggestionId = text(request.suggestionId());
    if (!suggestionId.isEmpty()) {
      Record suggestionRow =
          sql.fetchOne(
              "select id, policy_id from ad_bidding_suggestion where tenant_id=? and id=?",
              tenant,
              parseId(suggestionId));
      if (suggestionRow == null) {
        throw notFound("竞价建议批次");
      }
      if (!suggestionRow.get("policy_id", UUID.class).equals(policyId)) {
        throw badRequest("建议批次不属于这条策略");
      }
      for (Record r :
          sql.fetch(
              "select i.keyword_id, i.suggested_bid from ad_bidding_suggestion_item i"
                  + " join ad_bidding_suggestion_sku s"
                  + "   on s.tenant_id=i.tenant_id and s.id=i.suggestion_sku_id"
                  + " where i.tenant_id=? and i.suggestion_id=? and i.status='SUGGESTED'"
                  + "   and i.suggested_bid is not null order by i.keyword_text",
              tenant,
              parseId(suggestionId))) {
        targets.add(
            new AdBiddingAdjustTarget(
                r.get("keyword_id", UUID.class).toString(), r.get("suggested_bid", BigDecimal.class)));
      }
    } else {
      if (request.targets() != null) {
        targets.addAll(request.targets());
      }
    }
    if (targets.isEmpty()) {
      throw badRequest("批量调整必须给出目标（手工给关键词与期望竞价，或给一个建议批次）");
    }
    if (targets.size() > MAX_ADJUST_ITEMS) {
      throw badRequest(
          "单次批量条目上限 " + MAX_ADJUST_ITEMS + " 条（收到 " + targets.size() + " 条）—— 越线整批不动");
    }
    // 同一批次里不能有重复的关键词
    Map<UUID, Integer> seen = new LinkedHashMap<>();
    for (int i = 0; i < targets.size(); i++) {
      UUID keywordId = parseId(targets.get(i).keywordId());
      Integer at = seen.put(keywordId, i);
      if (at != null) {
        throw badRequest(
            "同一批次里第 "
                + (i + 1)
                + " 条目标与第 "
                + (at + 1)
                + " 条是同一个关键词 —— 一个关键词在一次批量操作里只能出现一次");
      }
    }

    boolean requestedDryRun = request.dryRun() == null || request.dryRun();
    boolean policyDryRun = policyRow.get("dry_run", Boolean.class) != null && policyRow.get("dry_run", Boolean.class);
    // 🔴 请求侧与策略侧**取更严的那个**（任一为试运行 ⇒ 就是试运行）。
    boolean dryRun = requestedDryRun || policyDryRun;

    BigDecimal minBid = policyRow.get("min_bid", BigDecimal.class);
    BigDecimal maxBid = policyRow.get("max_bid", BigDecimal.class);
    BigDecimal capRatio = effectiveRatioCap(policyRow);
    BigDecimal capAmount = effectiveAmountCap(policyRow);

    // ---- 逐条预检（两道闸 + 币种 + 关键词归属）----
    List<AdBiddingAdjustTarget> survivors = new ArrayList<>();
    List<Map<String, Object>> itemRows = new ArrayList<>();
    for (AdBiddingAdjustTarget target : targets) {
      UUID keywordId = parseId(target.keywordId());
      Record keyword =
          sql.fetchOne(
              "select id, keyword, shop_id, bid, bid_currency, status, external_campaign_id,"
                  + " external_keyword_id from ad_keyword where tenant_id=? and id=?",
              tenant,
              keywordId);
      if (keyword == null) {
        itemRows.add(itemRow(keywordId, "", null, null, target.bidValue(), "REJECTED",
            "UPSTREAM_REJECTED", "关键词不存在或不属于本租户"));
        continue;
      }
      if (!keyword.get("shop_id", UUID.class).equals(shopId)) {
        itemRows.add(itemRow(keywordId, text(keyword.get("keyword", String.class)), null, null,
            target.bidValue(), "REJECTED", "UPSTREAM_REJECTED",
            "关键词不属于这条策略的店铺"));
        continue;
      }
      if ("ARCHIVED".equals(text(keyword.get("status", String.class)))) {
        itemRows.add(itemRow(keywordId, text(keyword.get("keyword", String.class)), null, null,
            target.bidValue(), "REJECTED", "UPSTREAM_REJECTED", "关键词已归档，不能再调价"));
        continue;
      }
      String keywordCurrency = text(keyword.get("bid_currency", String.class));
      if (!keywordCurrency.isEmpty() && !keywordCurrency.equals(currency)) {
        itemRows.add(itemRow(keywordId, text(keyword.get("keyword", String.class)), null, null,
            target.bidValue(), "REJECTED", "CURRENCY",
            "币种不一致（关键词 " + keywordCurrency + " vs 本次 " + currency + "）"));
        continue;
      }
      BigDecimal before = nz(keyword.get("bid", BigDecimal.class));
      BigDecimal requested = target.bidValue();
      if (requested == null) {
        itemRows.add(itemRow(keywordId, text(keyword.get("keyword", String.class)), before, null, null,
            "REJECTED", "UPSTREAM_REJECTED", "没有给期望的竞价"));
        continue;
      }
      BigDecimal after = money(requested);
      Gate gate = rangeGate(after, minBid, maxBid);
      if (gate == null) {
        gate = adjustGate(before, after, capRatio, capAmount);
      }
      if (gate != null) {
        itemRows.add(itemRow(keywordId, text(keyword.get("keyword", String.class)), before, null, after,
            "REJECTED", gate.code(), gate.reason()));
        continue;
      }
      survivors.add(new AdBiddingAdjustTarget(keywordId.toString(), after));
      itemRows.add(itemRow(keywordId, text(keyword.get("keyword", String.class)), before, after,
          requested, "PENDING", "", ""));
    }

    // ---- 真调（走 14.1.3.1 的写路径）----
    AdBulkActionResult bulkResult = null;
    if (!dryRun && !survivors.isEmpty()) {
      List<AdBulkBidTarget> bulkTargets = new ArrayList<>();
      for (AdBiddingAdjustTarget t : survivors) {
        bulkTargets.add(new AdBulkBidTarget(parseId(t.keywordId()), t.bidValue()));
      }
      bulkResult =
          keywordService.bulkAction(
              new AdBulkActionRequest(
                  "BID_ADJUST",
                  shopId,
                  "ABSOLUTE",
                  currency,
                  bulkTargets,
                  null,
                  "ad-bidding:" + policyId + ":" + idempotencyKey,
                  text(request.note())));
    }

    // ---- 逐条结论（bulkAction 的结果覆盖预检的 PENDING）----
    if (bulkResult != null) {
      Map<UUID, AdBulkActionItemRow> bulkItems = new LinkedHashMap<>();
      for (AdBulkActionItemRow row : bulkResult.items()) {
        bulkItems.put(row.keywordId(), row);
      }
      for (Map<String, Object> row : itemRows) {
        if (!"PENDING".equals(row.get("status"))) {
          continue;
        }
        UUID keywordId = (UUID) row.get("keyword_id");
        AdBulkActionItemRow bulkRow = bulkItems.get(keywordId);
        if (bulkRow == null) {
          row.put("status", "REJECTED");
          row.put("guardrail_code", "UPSTREAM_REJECTED");
          row.put("reason", "写路径没有返回这一条的结果");
          continue;
        }
        if ("APPLIED".equals(bulkRow.status())) {
          row.put("status", "APPLIED");
          row.put("after_bid", bulkRow.afterBid());
          row.put("reason", bulkRow.reason());
        } else {
          row.put("status", "REJECTED");
          row.put("guardrail_code", classifyGuardrail(bulkRow.reason()));
          row.put("reason", bulkRow.reason());
        }
      }
    } else if (dryRun) {
      for (Map<String, Object> row : itemRows) {
        if ("PENDING".equals(row.get("status"))) {
          row.put("status", "APPLIED");
          row.put("reason", "试运行：本来会变成这个价（**没有真的改**）");
        }
      }
    }

    int applied = 0;
    int failed = 0;
    for (Map<String, Object> row : itemRows) {
      if ("APPLIED".equals(row.get("status"))) {
        applied++;
      } else {
        failed++;
      }
    }
    String status = applied == 0 ? "REJECTED" : (failed == 0 ? "APPLIED" : "PARTIAL");

    Map<String, Object> guardrail = new LinkedHashMap<>();
    guardrail.put("rangeMinBid", minBid);
    guardrail.put("rangeMaxBid", maxBid);
    guardrail.put("policyAdjustRatio", policyRow.get("single_adjust_cap_ratio", BigDecimal.class));
    guardrail.put("policyAdjustAmount", policyRow.get("single_adjust_cap_amount", BigDecimal.class));
    guardrail.put("effectiveAdjustRatio", capRatio);
    guardrail.put("effectiveAdjustAmount", capAmount);
    guardrail.put("upstreamMaxBidDeltaRatio", AdKeywordService.MAX_BID_DELTA_RATIO);
    guardrail.put("upstreamMaxBidAbsolute", AdKeywordService.MAX_BID_ABSOLUTE);
    guardrail.put("upstreamMaxTotalBidIncrease", AdKeywordService.MAX_TOTAL_BID_INCREASE);
    guardrail.put("runMode", dryRun ? "DRY_RUN" : "APPLY");
    if (bulkResult != null && bulkResult.guardrail() != null) {
      guardrail.put("upstreamGuardrail", bulkResult.guardrail());
    }
    guardrail.put(
        "note",
        "🔴 改竞价走的是 **14.1.3.1 已交付的写路径**（AdKeywordService.bulkAction）"
            + " ⇒ ±50% / 1000.00 / 单次总上调 5000.00 / 幂等 / 操作审计**全部继承**，"
            + "本模块**不另开绕过护栏的写路径**。");

    UUID adjustmentId = Ids.next();
    OffsetDateTime now = dbNow();
    sql.execute(
        "insert into ad_bidding_adjustment (id, tenant_id, policy_id, shop_id, action_type, run_mode,"
            + " status, currency, bid_mode, requested_count, applied_count, failed_count,"
            + " bulk_action_id, guardrail, idempotency_key, reason, note, created_at, updated_at)"
            + " values (?,?,?,?,'BID_ADJUST',?,?,?, 'ABSOLUTE',?,?,?,?,?::jsonb,?,?,?,"
            + "?::timestamptz,?::timestamptz)",
        adjustmentId,
        tenant,
        policyId,
        shopId,
        dryRun ? "DRY_RUN" : "APPLY",
        status,
        currency,
        targets.size(),
        applied,
        failed,
        bulkResult == null ? null : bulkResult.actionId(),
        writeJson(guardrail),
        idempotencyKey,
        bulkResult == null ? "" : text(bulkResult.reason()),
        text(request.note()),
        now,
        now);

    // ---- 逐条明细 + 待下发队列 ----
    for (Map<String, Object> row : itemRows) {
      UUID itemId = Ids.next();
      sql.execute(
          "insert into ad_bidding_adjustment_item (id, tenant_id, adjustment_id, keyword_id,"
              + " target_ref, before_bid, after_bid, requested_bid, status, guardrail_code, reason,"
              + " created_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
          itemId,
          tenant,
          adjustmentId,
          row.get("keyword_id"),
          textOrEmpty(row.get("target_ref")),
          row.get("before_bid"),
          row.get("after_bid"),
          row.get("requested_bid"),
          textOrEmpty(row.get("status")),
          textOrEmpty(row.get("guardrail_code")),
          textOrEmpty(row.get("reason")),
          now);

      if ("APPLIED".equals(row.get("status")) && !dryRun) {
        enqueueBidDispatch(tenant, policyId, adjustmentId, itemId, shopCode, row);
      }
    }
    return adjustment(adjustmentId.toString());
  }

  /** 一条改竞价的下发记录（**待下发**）。 */
  private void enqueueBidDispatch(
      UUID tenant,
      UUID policyId,
      UUID adjustmentId,
      UUID itemId,
      String shopCode,
      Map<String, Object> row) {
    UUID keywordId = (UUID) row.get("keyword_id");
    Record keyword =
        sql.fetchOne(
            "select keyword, external_campaign_id, external_keyword_id from ad_keyword"
                + " where tenant_id=? and id=?",
            tenant,
            keywordId);
    Record policy =
        sql.fetchOne(
            "select platform_id, currency, dynamic_bidding_strategy, policy_code from ad_bidding_policy"
                + " where tenant_id=? and id=?",
            tenant,
            policyId);
    if (policy == null) {
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("keyword", keyword == null ? "" : text(keyword.get("keyword", String.class)));
    payload.put("beforeBid", row.get("before_bid"));
    payload.put("afterBid", row.get("after_bid"));
    payload.put("currency", text(policy.get("currency", String.class)));
    payload.put("policyCode", text(policy.get("policy_code", String.class)));
    payload.put("dynamicBiddingStrategy", text(policy.get("dynamic_bidding_strategy", String.class)));
    OffsetDateTime now = dbNow();
    sql.execute(
        "insert into ad_bidding_dispatch (id, tenant_id, policy_id, adjustment_id,"
            + " adjustment_item_id, platform_id, shop_code, external_campaign_id, external_keyword_id,"
            + " action_type, action_payload, dispatch_status, note, created_at, updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,'SET_BID',?::jsonb,'PENDING',?,?::timestamptz,?::timestamptz)",
        Ids.next(),
        tenant,
        policyId,
        adjustmentId,
        itemId,
        policy.get("platform_id", UUID.class),
        shopCode,
        keyword == null ? "" : text(keyword.get("external_campaign_id", String.class)),
        keyword == null ? "" : text(keyword.get("external_keyword_id", String.class)),
        writeJson(payload),
        "竞价调整待下发（平台广告 API 未交付）",
        now,
        now);
  }

  public AdBiddingAdjustmentPage adjustments(String policyId, String status, int page, int size) {
    UUID tenant = currentTenant();
    int sizeBound = clampSize(size);
    int offset = Math.max(page, 0) * sizeBound;
    StringBuilder where = new StringBuilder(" where a.tenant_id = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    if (!text(policyId).isEmpty()) {
      where.append(" and a.policy_id = ?");
      binds.add(parseId(policyId));
    }
    if (!text(status).isEmpty()) {
      where.append(" and a.status = ?");
      binds.add(upper(status));
    }
    long total = count("select count(*) from ad_bidding_adjustment a" + where, binds.toArray());
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(sizeBound);
    pageBinds.add(offset);
    List<AdBiddingAdjustmentSummary> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select a.*, p.policy_name, s.code shop_code from ad_bidding_adjustment a"
                + " join ad_bidding_policy p on p.tenant_id=a.tenant_id and p.id=a.policy_id"
                + " join shop s on s.tenant_id=a.tenant_id and s.id=a.shop_id"
                + where
                + " order by a.created_at desc, a.id limit ? offset ?",
            pageBinds.toArray())) {
      items.add(adjustmentSummary(r));
    }
    return new AdBiddingAdjustmentPage(items, total, Math.max(page, 0), sizeBound);
  }

  public AdBiddingAdjustmentView adjustment(String id) {
    UUID tenant = currentTenant();
    Record head =
        sql.fetchOne(
            "select a.*, p.policy_name, s.code shop_code from ad_bidding_adjustment a"
                + " join ad_bidding_policy p on p.tenant_id=a.tenant_id and p.id=a.policy_id"
                + " join shop s on s.tenant_id=a.tenant_id and s.id=a.shop_id"
                + " where a.tenant_id=? and a.id=?",
            tenant,
            parseId(id));
    if (head == null) {
      throw notFound("竞价调整任务");
    }
    List<AdBiddingAdjustmentItemView> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select * from ad_bidding_adjustment_item where tenant_id=? and adjustment_id=? order by target_ref, id",
            tenant,
            parseId(id))) {
      items.add(
          new AdBiddingAdjustmentItemView(
              r.get("id", UUID.class).toString(),
              r.get("keyword_id", UUID.class).toString(),
              text(r.get("target_ref", String.class)),
              r.get("before_bid", BigDecimal.class),
              r.get("after_bid", BigDecimal.class),
              r.get("requested_bid", BigDecimal.class),
              text(r.get("status", String.class)),
              text(r.get("guardrail_code", String.class)),
              text(r.get("reason", String.class))));
    }
    AdBiddingAdjustmentSummary summary = adjustmentSummary(head);
    return new AdBiddingAdjustmentView(
        summary.id(),
        summary.policyId(),
        summary.policyName(),
        summary.shopId(),
        summary.shopCode(),
        summary.actionType(),
        summary.runMode(),
        summary.status(),
        summary.currency(),
        summary.bidMode(),
        summary.requestedCount(),
        summary.appliedCount(),
        summary.failedCount(),
        summary.bulkActionId(),
        summary.guardrail(),
        summary.idempotencyKey(),
        summary.reason(),
        summary.note(),
        summary.createdAt(),
        items);
  }

  // ==================================================================
  // 待下发队列
  // ==================================================================

  public AdBiddingDispatchPage dispatches(String status, String shopCode, int page, int size) {
    UUID tenant = currentTenant();
    int sizeBound = clampSize(size);
    int offset = Math.max(page, 0) * sizeBound;
    StringBuilder where = new StringBuilder(" where d.tenant_id = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    if (!text(status).isEmpty()) {
      where.append(" and d.dispatch_status = ?");
      binds.add(upper(status));
    }
    if (!text(shopCode).isEmpty()) {
      where.append(" and d.shop_code = ?");
      binds.add(text(shopCode));
    }
    long total = count("select count(*) from ad_bidding_dispatch d" + where, binds.toArray());
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(sizeBound);
    pageBinds.add(offset);
    List<AdBiddingDispatchView> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select d.*, f.code platform_code, i.target_ref keyword_text, i.before_bid, i.after_bid"
                + " from ad_bidding_dispatch d"
                + " join platform f on f.id = d.platform_id"
                + " left join ad_bidding_adjustment_item i"
                + "   on i.tenant_id = d.tenant_id and i.id = d.adjustment_item_id"
                + where
                + " order by d.created_at desc, d.id limit ? offset ?",
            pageBinds.toArray())) {
      items.add(dispatchView(r));
    }
    return new AdBiddingDispatchPage(items, total, Math.max(page, 0), sizeBound);
  }

  public AdBiddingDispatchView cancelDispatch(String id, AdBiddingDispatchCancelRequest request) {
    UUID tenant = currentTenant();
    String reason = text(request == null ? "" : request.reason());
    if (reason.isEmpty()) {
      throw badRequest("撤销必须写清原因 —— 不写清楚为什么撤销，下周就没人知道这条为什么没了");
    }
    Record row =
        sql.fetchOne("select * from ad_bidding_dispatch where tenant_id=? and id=?", tenant, parseId(id));
    if (row == null) {
      throw notFound("待下发记录");
    }
    String status = text(row.get("dispatch_status", String.class));
    if (!"PENDING".equals(status)) {
      throw unprocessable(
          "只有「待下发」的记录能撤销（当前状态 " + status + "）—— 已下发 / 已失败 / 已撤销的都不再重复动");
    }
    OffsetDateTime now = dbNow();
    sql.execute(
        "update ad_bidding_dispatch set dispatch_status='CANCELLED', cancelled_at=?::timestamptz,"
            + " cancel_reason=?, updated_at=?::timestamptz where tenant_id=? and id=?",
        now,
        reason,
        now,
        tenant,
        parseId(id));
    Record after =
        sql.fetchOne(
            "select d.*, f.code platform_code, i.target_ref keyword_text, i.before_bid, i.after_bid"
                + " from ad_bidding_dispatch d"
                + " join platform f on f.id = d.platform_id"
                + " left join ad_bidding_adjustment_item i"
                + "   on i.tenant_id = d.tenant_id and i.id = d.adjustment_item_id"
                + " where d.tenant_id=? and d.id=?",
            tenant,
            parseId(id));
    return dispatchView(after);
  }

  // ==================================================================
  // 闸门
  // ==================================================================

  /** 一道闸拦下了一条。 */
  private record Gate(String code, String reason) {}

  /**
   * 🔴 闸①：竞价范围上限（账户/活动级的**允许区间**）。
   *
   * <p>**不静默截断**：落在区间外就是**不许**，不是「截到边界」。
   */
  private static Gate rangeGate(BigDecimal after, BigDecimal minBid, BigDecimal maxBid) {
    if (minBid != null && after.compareTo(minBid) < 0) {
      return new Gate(
          "RANGE_MIN",
          "调价结果 " + after.toPlainString() + " 低于竞价范围下限 " + minBid.toPlainString()
              + " ⇒ 闸①（竞价范围上限）拦下");
    }
    if (maxBid != null && after.compareTo(maxBid) > 0) {
      return new Gate(
          "RANGE_MAX",
          "调价结果 " + after.toPlainString() + " 高于竞价范围上限 " + maxBid.toPlainString()
              + " ⇒ 闸①（竞价范围上限）拦下");
    }
    return null;
  }

  /**
   * 🔴 闸②：单次调整额度（比例 + 金额）。
   *
   * <p>🔴 14.1.3.1 的比例上限只在**百分比模式**下生效，而本模块用的是**绝对值模式**
   * ⇒ 这里**同样执行**那条上限（策略自定的更严值优先），否则那条护栏在绝对值模式下就是空的。
   */
  private static Gate adjustGate(
      BigDecimal before, BigDecimal after, BigDecimal capRatio, BigDecimal capAmount) {
    BigDecimal delta = after.subtract(before);
    BigDecimal abs = delta.abs();
    if (capRatio != null && capRatio.signum() > 0 && before.signum() > 0) {
      BigDecimal relative = abs.divide(before, 8, RoundingMode.HALF_UP);
      if (relative.compareTo(capRatio) > 0) {
        return new Gate(
            "ADJUST_RATIO",
            "单次调整幅度 "
                + relative.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP).toPlainString()
                + "% 超过额度 "
                + capRatio.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP).toPlainString()
                + "% ⇒ 闸②（单次调整额度）拦下");
      }
    }
    if (capAmount != null && capAmount.signum() > 0 && abs.compareTo(capAmount) > 0) {
      return new Gate(
          "ADJUST_AMOUNT",
          "单次调整金额 " + abs.toPlainString() + " 超过额度 " + capAmount.toPlainString()
              + " ⇒ 闸②（单次调整额度）拦下");
    }
    return null;
  }

  /** 单次比例额度：策略自定的更严值优先，没配就继承 14.1.3.1 的 ±50%。 */
  private static BigDecimal effectiveRatioCap(Record policyRow) {
    BigDecimal policy = policyRow.get("single_adjust_cap_ratio", BigDecimal.class);
    BigDecimal upstream = AdKeywordService.MAX_BID_DELTA_RATIO;
    if (policy == null || policy.signum() <= 0) {
      return upstream;
    }
    return policy.compareTo(upstream) < 0 ? policy : upstream;
  }

  /** 单次金额额度：策略自定的更严值优先，没配就用 14.1.3.1 的绝对上限。 */
  private static BigDecimal effectiveAmountCap(Record policyRow) {
    BigDecimal policy = policyRow.get("single_adjust_cap_amount", BigDecimal.class);
    BigDecimal upstream = AdKeywordService.MAX_BID_ABSOLUTE;
    if (policy == null || policy.signum() <= 0) {
      return upstream;
    }
    return policy.compareTo(upstream) < 0 ? policy : upstream;
  }

  /** 把 14.1.3.1 写路径的拒绝原因翻译成闸门码。 */
  private static String classifyGuardrail(String reason) {
    String r = text(reason);
    if (r.contains("总上调将超过上限")) {
      return "UPSTREAM_TOTAL";
    }
    if (r.contains("超过单条绝对上限")) {
      return "UPSTREAM_ABSOLUTE";
    }
    if (r.contains("超过上限")) {
      return "UPSTREAM_RATIO";
    }
    if (r.contains("币种不一致")) {
      return "CURRENCY";
    }
    return "UPSTREAM_REJECTED";
  }

  // ==================================================================
  // 内部形状与工具
  // ==================================================================

  /** 一个 SKU 的窗口口径。 */
  private static final class SkuBasis {
    BigDecimal spend = BigDecimal.ZERO;
    BigDecimal platformSales = BigDecimal.ZERO;
    BigDecimal attributedSales = BigDecimal.ZERO;
    int keywordCount = 0;
    OffsetDateTime dataAsOf;
  }

  private static Map<String, Object> itemRow(
      UUID keywordId,
      String targetRef,
      BigDecimal before,
      BigDecimal after,
      BigDecimal requested,
      String status,
      String guardrail,
      String reason) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("keyword_id", keywordId);
    row.put("target_ref", targetRef);
    row.put("before_bid", before);
    row.put("after_bid", after);
    row.put("requested_bid", requested);
    row.put("status", status);
    row.put("guardrail_code", guardrail);
    row.put("reason", reason);
    return row;
  }

  /** 目标 ACOS / ROAS 二选一归一（另一个由服务端换算，不落库）。 */
  private static BigDecimal[] normalizeTargets(BigDecimal targetAcos, BigDecimal targetRoas) {
    BigDecimal acos = targetAcos;
    BigDecimal roas = targetRoas;
    if (acos == null && roas == null) {
      throw badRequest("必须给一个目标（目标 ACOS 或目标 ROAS，二选一）");
    }
    if (acos != null && roas != null) {
      throw badRequest(
          "目标 ACOS 与目标 ROAS **二选一** —— 它们互为倒数，同时给两个就必须自洽，"
              + "不自洽等于给了两个互相矛盾的答案");
    }
    if (acos != null && acos.signum() <= 0) {
      throw badRequest("目标 ACOS 必须大于 0（它是比例：0.25 = 25%）");
    }
    if (roas != null && roas.signum() <= 0) {
      throw badRequest("目标 ROAS 必须大于 0（它是比例：4 = 400%）");
    }
    return new BigDecimal[] {acos, roas};
  }

  private static BigDecimal oneOver(BigDecimal value) {
    if (value == null || value.signum() == 0) {
      return null;
    }
    return BigDecimal.ONE.divide(value, 6, RoundingMode.HALF_UP);
  }

  private static BigDecimal optionalMoney(BigDecimal value, String label) {
    if (value == null) {
      return null;
    }
    if (value.signum() < 0) {
      throw badRequest(label + "不能是负数");
    }
    return money(value);
  }

  private static BigDecimal optionalRatio(BigDecimal value, String label) {
    if (value == null) {
      return null;
    }
    if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
      throw badRequest(label + "必须在 0 与 1 之间（0 = 不额外限制）");
    }
    return ratio(value);
  }

  private UUID shopIdOf(UUID tenant, String shopCode) {
    return one(UUID.class, "select id from shop where tenant_id=? and code=?", tenant, shopCode);
  }

  private String platformIdOfShop(UUID tenant, String shopCode) {
    return one(
        String.class, "select platform_id::text from shop where tenant_id=? and code=?", tenant, shopCode);
  }

  private String campaignShopOf(UUID tenant, UUID campaignId) {
    return one(
        String.class, "select shop_code from ad_campaign where tenant_id=? and id=?", tenant, campaignId);
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readJson(Object raw) {
    if (raw == null) {
      return Map.of();
    }
    try {
      return json.readValue(String.valueOf(raw), Map.class);
    } catch (Exception e) {
      return Map.of();
    }
  }

  // ==================================================================
  // 视图装配
  // ==================================================================

  private AdBiddingPolicySummary policySummary(Record r) {
    return new AdBiddingPolicySummary(
        r.get("id", UUID.class).toString(),
        text(r.get("policy_code", String.class)),
        text(r.get("policy_name", String.class)),
        text(r.get("platform_code", String.class)),
        text(r.get("shop_code", String.class)),
        text(r.get("shop_name", String.class)),
        text(r.get("dynamic_bidding_strategy", String.class)),
        text(r.get("currency", String.class)),
        text(r.get("scope", String.class)),
        text(r.get("status", String.class)),
        r.get("min_bid", BigDecimal.class),
        r.get("max_bid", BigDecimal.class),
        effectiveTarget(r, true),
        effectiveTarget(r, false),
        Boolean.TRUE.equals(r.get("dry_run", Boolean.class)),
        r.get("updated_at", OffsetDateTime.class));
  }

  private AdBiddingPolicyView policyView(Record r) {
    return new AdBiddingPolicyView(
        r.get("id", UUID.class).toString(),
        text(r.get("policy_code", String.class)),
        text(r.get("policy_name", String.class)),
        r.get("platform_id", UUID.class).toString(),
        text(r.get("platform_code", String.class)),
        text(r.get("platform_name", String.class)),
        text(r.get("shop_code", String.class)),
        text(r.get("shop_name", String.class)),
        r.get("shop_id", UUID.class).toString(),
        text(r.get("dynamic_bidding_strategy", String.class)),
        text(r.get("currency", String.class)),
        text(r.get("scope", String.class)),
        r.get("target_campaign_id", UUID.class) == null
            ? ""
            : r.get("target_campaign_id", UUID.class).toString(),
        text(r.get("target_campaign_name", String.class)),
        text(r.get("status", String.class)),
        r.get("min_bid", BigDecimal.class),
        r.get("max_bid", BigDecimal.class),
        r.get("single_adjust_cap_ratio", BigDecimal.class),
        r.get("single_adjust_cap_amount", BigDecimal.class),
        effectiveTarget(r, true),
        effectiveTarget(r, false),
        Boolean.TRUE.equals(r.get("dry_run", Boolean.class)),
        text(r.get("note", String.class)),
        r.get("version", Long.class) == null ? 0L : r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 目标 ACOS / ROAS：库里只存了给的那个，另一个由这里换算（互为倒数）。 */
  private static BigDecimal effectiveTarget(Record r, boolean acos) {
    BigDecimal storedAcos = r.get("target_acos", BigDecimal.class);
    BigDecimal storedRoas = r.get("target_roas", BigDecimal.class);
    if (acos) {
      return storedAcos != null ? ratio(storedAcos) : oneOver(storedRoas);
    }
    return storedRoas != null ? ratio(storedRoas) : oneOver(storedAcos);
  }

  private AdBiddingSuggestionSummary suggestionSummary(Record r) {
    return new AdBiddingSuggestionSummary(
        r.get("id", UUID.class).toString(),
        r.get("policy_id", UUID.class).toString(),
        text(r.get("policy_name", String.class)),
        text(r.get("shop_code", String.class)),
        text(r.get("shop_name", String.class)),
        r.get("window_from", LocalDate.class),
        r.get("window_to", LocalDate.class),
        r.get("target_acos", BigDecimal.class),
        r.get("target_roas", BigDecimal.class),
        text(r.get("attribution_rule_code", String.class)),
        r.get("attribution_rule_version", Long.class),
        text(r.get("attribution_model", String.class)),
        r.get("attribution_window_days", Integer.class),
        Boolean.TRUE.equals(r.get("profit_available", Boolean.class)),
        text(r.get("profit_unavailable_reason", String.class)),
        r.get("data_as_of", OffsetDateTime.class),
        text(r.get("status", String.class)),
        r.get("sku_count", Integer.class) == null ? 0 : r.get("sku_count", Integer.class),
        r.get("suggested_count", Integer.class) == null ? 0 : r.get("suggested_count", Integer.class),
        r.get("rejected_count", Integer.class) == null ? 0 : r.get("rejected_count", Integer.class),
        text(r.get("note", String.class)),
        r.get("created_at", OffsetDateTime.class));
  }

  private AdBiddingAdjustmentSummary adjustmentSummary(Record r) {
    return new AdBiddingAdjustmentSummary(
        r.get("id", UUID.class).toString(),
        r.get("policy_id", UUID.class).toString(),
        text(r.get("policy_name", String.class)),
        r.get("shop_id", UUID.class).toString(),
        text(r.get("shop_code", String.class)),
        text(r.get("action_type", String.class)),
        text(r.get("run_mode", String.class)),
        text(r.get("status", String.class)),
        text(r.get("currency", String.class)),
        text(r.get("bid_mode", String.class)),
        r.get("requested_count", Integer.class) == null ? 0 : r.get("requested_count", Integer.class),
        r.get("applied_count", Integer.class) == null ? 0 : r.get("applied_count", Integer.class),
        r.get("failed_count", Integer.class) == null ? 0 : r.get("failed_count", Integer.class),
        r.get("bulk_action_id", UUID.class) == null
            ? ""
            : r.get("bulk_action_id", UUID.class).toString(),
        readJson(r.get("guardrail")),
        text(r.get("idempotency_key", String.class)),
        text(r.get("reason", String.class)),
        text(r.get("note", String.class)),
        r.get("created_at", OffsetDateTime.class));
  }

  private AdBiddingDispatchView dispatchView(Record r) {
    return new AdBiddingDispatchView(
        r.get("id", UUID.class).toString(),
        r.get("policy_id", UUID.class) == null ? "" : r.get("policy_id", UUID.class).toString(),
        r.get("adjustment_id", UUID.class) == null
            ? ""
            : r.get("adjustment_id", UUID.class).toString(),
        r.get("adjustment_item_id", UUID.class) == null
            ? ""
            : r.get("adjustment_item_id", UUID.class).toString(),
        r.get("platform_id", UUID.class).toString(),
        text(r.get("platform_code", String.class)),
        text(r.get("shop_code", String.class)),
        text(r.get("external_campaign_id", String.class)),
        text(r.get("external_keyword_id", String.class)),
        text(r.get("keyword_text", String.class)),
        r.get("before_bid", BigDecimal.class),
        r.get("after_bid", BigDecimal.class),
        text(r.get("action_type", String.class)),
        readJson(r.get("action_payload")),
        text(r.get("dispatch_status", String.class)),
        r.get("dispatched_at", OffsetDateTime.class),
        text(r.get("dispatch_ref", String.class)),
        r.get("cancelled_at", OffsetDateTime.class),
        text(r.get("cancel_reason", String.class)),
        text(r.get("note", String.class)),
        r.get("created_at", OffsetDateTime.class));
  }
}
