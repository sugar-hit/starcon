package com.starcon.channel;

import static com.starcon.channel.ListingChangeModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
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
 * 4.5.2.2 上新与变更监控。
 *
 * <p>蓝图：「监控目标 ASIN/竞品的上下架、价格、排名、评论数变化，识别市场机会（如竞品断货涨价）。输入：监控对象列表；抓取频率。输出：变更事件流 + 历史对比。关系：上游 = 抓取任务；下游 = 13 分析、1 工作台。<b>监控对象与自有商品分离</b>，竞品数据不进自有商品模型，只做参考指标。」
 *
 * <p>🔴 本类的六个核心不变量，每一个都有集成测试逐条钉住：
 *
 * <ol>
 *   <li><b>🔴🔴 竞品数据不进自有商品模型</b> —— 监控对象只认「平台 + 平台商品 ID（ASIN）原文」
 *       这一对身份，<b>本类任何一处 SQL 都不引用 {@code listing} / {@code sku} / 商品主数据</b>。
 *       蓝图加粗句「监控对象与自有商品分离，竞品数据不进自有商品模型，只做参考指标」因此是
 *       <b>契约级 + 数据级</b>的硬要求，不是页面上的装饰。
 *   <li><b>🔴🔴 变化量不落表、SQL 现算，分母为 0 时是 null</b> —— 价格变化量 / 变化比例、
 *       排名变化、评论数变化都是「本次观测 − 上次**已知**观测」，由 SQL 现算；
 *       <b>分母（上次价格）为 0 时返回 null</b>，不是 0 也不是 100：
 *       报 0 会被读成「价格没变」，那是结论不是缺口。
 *   <li><b>🔴🔴 平台没报 ≠ 变成 0</b> —— 价格 / 排名 / 评论数为 null 表示「这次没报这个维度」。
 *       判定基准回退到<b>上一个已知值</b>（不是上一次观测），
 *       所以「平台这次没报价格」不会吃掉下一次真正的价格变化。
 *   <li><b>🔴 变化方向只说方向</b> —— {@code direction} 一律指该维度数值/状态本身的变化，
 *       不含好坏判断（{@code RANK + INCREASED} = 名次数字变大 = 竞品表现变差）。
 *       好坏由市场机会判定决定。
 *   <li><b>🔴 幂等与跳过是两件事</b> —— 批次级幂等键让「平台重投同一批」<b>整批不落库</b>；
 *       同一次观测（同监控对象 × 同观测时刻 × 同来源）只有一行，修正时覆盖并递增 revision；
 *       冲突时**跳过并逐条写明原因**（不静默丢弃）。
 *   <li><b>🔴 未交付的下游显式声明</b> —— 02 抓取调度器、13 分析、1 工作台都没交付，
 *       {@link #capabilities()} 里 available=false + ownerModule + impact，前端显示「未接入」，
 *       <b>不显示假数据</b>。
 * </ol>
 *
 * <p>🔴 时间口径：观测时刻 / 抓取时刻 / 判定时刻都是 {@code timestamptz}，用 {@code OffsetDateTime}
 * 绑定、values 位置写 {@code ?::timestamptz} —— 用 {@code Timestamp.valueOf(LocalDateTime...)}
 * 会静默偏 −28800s（AGENTS.md 与 4.1.1.1 实测踩过）。筛选区间按 <b>UTC 自然日</b> 归集
 * （半开区间 {@code [from, to+1天)}），所以「10 月 1 日」包含 10 月 1 日 UTC 全天。
 */
@Service
public class ListingChangeService {

  /** 🔴 监控对象的取数 SQL —— **不 join `listing`**（竞品与自有商品零引用关系）。 */
  private static final String WATCH_FROM =
      """
      select w.id,w.platform_id,w.marketplace_id,w.external_item_id,w.target_kind,w.title,w.brand,
             w.crawl_interval_minutes,w.status,w.out_of_stock_opportunity,w.listed_opportunity,
             w.price_increase_ratio_threshold,w.rank_worsen_threshold,w.note,w.created_at,w.updated_at,
             p.code as platform_code, coalesce(m.code,'') as marketplace_code,
             (select count(*) from listing_change_snapshot s
               where s.tenant_id=w.tenant_id and s.watch_id=w.id) as snapshot_count,
             (select count(*) from listing_change_event e
               where e.tenant_id=w.tenant_id and e.watch_id=w.id) as event_count,
             (select count(*) from listing_change_opportunity o
               where o.tenant_id=w.tenant_id and o.watch_id=w.id) as opportunity_count,
             (select count(*) from listing_change_opportunity o
               where o.tenant_id=w.tenant_id and o.watch_id=w.id and o.status='OPEN') as open_opportunity_count,
             (select s.listing_status from listing_change_snapshot s
               where s.tenant_id=w.tenant_id and s.watch_id=w.id and s.listing_status<>'UNKNOWN'
               order by s.observed_at desc, s.received_at desc limit 1) as last_listing_status,
             (select s.price from listing_change_snapshot s
               where s.tenant_id=w.tenant_id and s.watch_id=w.id and s.price is not null
               order by s.observed_at desc, s.received_at desc limit 1) as last_price,
             (select s.rank_position from listing_change_snapshot s
               where s.tenant_id=w.tenant_id and s.watch_id=w.id and s.rank_position is not null
               order by s.observed_at desc, s.received_at desc limit 1) as last_rank_position,
             (select s.review_count from listing_change_snapshot s
               where s.tenant_id=w.tenant_id and s.watch_id=w.id and s.review_count is not null
               order by s.observed_at desc, s.received_at desc limit 1) as last_review_count,
             (select s.currency from listing_change_snapshot s
               where s.tenant_id=w.tenant_id and s.watch_id=w.id and s.currency<>''
               order by s.observed_at desc, s.received_at desc limit 1) as currency,
             (select s.observed_at from listing_change_snapshot s
               where s.tenant_id=w.tenant_id and s.watch_id=w.id
               order by s.observed_at desc, s.received_at desc limit 1) as last_observed_at
      from listing_change_watch w
      join platform p on p.id=w.platform_id
      left join marketplace m on m.id=w.marketplace_id
      """;

  private final DSLContext sql;
  private final ObjectMapper json;

  public ListingChangeService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ==================================================================
  // 小工具（照抄 ListingHijackerService 的同名写法）
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value == null ? Map.of() : value);
    } catch (Exception e) {
      return "{}";
    }
  }

  private static int clampSize(int size) {
    if (size <= 0) {
      return 50;
    }
    return Math.min(size, 200);
  }

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(text(raw));
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 格式不正确");
    }
  }

  /** 日期解析：只认 {@code yyyy-MM-dd}。 */
  private static LocalDate parseDate(String raw, String label) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (RuntimeException e) {
      throw badRequest(label + "必须是 yyyy-MM-dd 格式的日期（收到「" + value + "」）");
    }
  }

  /** UTC 自然日的起点（timestamptz 绑定值）。 */
  private static Timestamp utcStart(LocalDate date) {
    return Timestamp.from(OffsetDateTime.of(date, LocalTime.MIN, ZoneOffset.UTC).toInstant());
  }

  /** UTC 自然日的**次日**起点 —— 区间用半开区间 {@code [from, to+1天)}。 */
  private static Timestamp utcDayAfter(LocalDate date) {
    return Timestamp.from(
        OffsetDateTime.of(date.plusDays(1), LocalTime.MIN, ZoneOffset.UTC).toInstant());
  }

  /** 🔴 比率：分子 ÷ 分母；<b>分母为 0 时是 null</b>（不是 0、也不是 100）。 */
  private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
    if (numerator == null || denominator == null || denominator.signum() == 0) {
      return null;
    }
    return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
  }

  private static int intOf(Record r, String field) {
    Long v = r.get(field, Long.class);
    return v == null ? 0 : v.intValue();
  }

  private static long longOf(Record r, String field) {
    Long v = r.get(field, Long.class);
    return v == null ? 0L : v;
  }

  /** LIKE 的转义：% 与 _ 不能被用户输入当成通配符。 */
  private static String likePattern(String keyword) {
    String escaped = text(keyword).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }

  private static String note(String raw) {
    String v = text(raw);
    return v.length() > 500 ? v.substring(0, 500) : v;
  }

  private long count(String from, Object... binds) {
    Record r = sql.fetchOne("select count(*) as n from (" + from + ") t", binds);
    return r == null ? 0L : longOf(r, "n");
  }

  private long countOf(String sqlText, Object... binds) {
    Long v = (Long) sql.fetchValue(sqlText, binds);
    return v == null ? 0L : v;
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 🔴 逐维度能力声明。
   *
   * <p>本模块在本仓能**真实交付**的是「监控对象的维护 + 抓取结果的接收与入库 +
   * 四类变化的识别与落成事件 + 市场机会判定与记录 + 历史对比 + 全程标注「参考数据」。
   *
   * <p>它<b>不能</b>在本批真实交付的有三段，逐条在下面声明而不是假装具备：
   * 02 的抓取调度器、13 的分析消费、1 工作台的通知。
   */
  public ListingChangeCapabilities capabilities() {
    return new ListingChangeCapabilities(
        List.of(
            new ListingChangeCapabilityDimension(
                "change_watch_management",
                "🔴 监控对象维护：配置要盯的目标（平台 + ASIN）、目标性质、抓取频率、"
                    + "机会判定阈值与启停状态（租户内可维护）",
                true, "listing_change_watch（V211）", "",
                "同一个平台的同一个 ASIN 只能配一个监控对象"
                    + "（UNIQUE(tenant_id, platform_id, external_item_id)）—— "
                    + "「同一个竞品配了两套抓取频率」是配置错误，不允许出现"),
            new ListingChangeCapabilityDimension(
                "competitor_data_isolation",
                "🔴 竞品数据不进自有商品模型：目标身份只有「平台 + 平台商品 ID（ASIN）原文」，"
                    + "监控对象 / 快照 / 事件 / 机会**四张表都没有**任何自有商品列",
                true, "listing_change_watch / listing_change_snapshot / V211", "",
                "蓝图加粗句「监控对象与自有商品分离，竞品数据不进自有商品模型，只做参考指标」"
                    + "因此是**数据级 + 契约级**的硬要求：把竞品观测写进我方商品表，"
                    + "下游就会把它当成我方商品的事实读走"),
            new ListingChangeCapabilityDimension(
                "observation_ingest",
                "🔴 接收抓取任务的结果并入库：上下架状态、价格、排名（含口径）、评论数、"
                    + "观测时刻与不准确窗口",
                true, "listing_change_crawl + listing_change_snapshot（V211 新表）", "",
                "批次级幂等键让「平台重投同一批」与「同一次观测被重复上报」变成两件可分辨的事："
                    + "前者整批跳过，后者按 (监控对象, 观测时刻, 来源) 覆盖并递增 revision"),
            new ListingChangeCapabilityDimension(
                "change_detection",
                "🔴 四类变化的识别与落成事件：上下架（LISTING_STATUS）、价格（PRICE）、"
                    + "排名（RANK）、评论数（REVIEW_COUNT）",
                true, "listing_change_event（V211 新表） + 服务层判定", "",
                "🔴 判定基准是**上一个已知值**，不是「上一次观测」—— 平台这次没报价格，"
                    + "不会吃掉下一次真正的价格变化；平台没报 ≠ 变成 0"),
            new ListingChangeCapabilityDimension(
                "market_opportunity_detection",
                "🔴 市场机会判定：竞品断货 / 涨价 / 排名变差 / 上新（重新上架）",
                true, "listing_change_opportunity（V211 新表）", "",
                "🔴 阈值是**配置**（不给 = 该维度**不判定**），判定结果**不落比率** —— "
                    + "价格变化比例一律现算，分母为 0 时是 null"),
            new ListingChangeCapabilityDimension(
                "change_event_stream",
                "🔴 变更事件流：按监控对象 / 变化维度 / 方向 / 时间范围查询（真分页 + 真筛选）",
                true, "listing_change_event（V211 新表）", "",
                "一个 atomic 变化一条事件；同一观测时刻的同一维度最多一条"
                    + "（UNIQUE(tenant_id, watch_id, change_type, snapshot_id)）"),
            new ListingChangeCapabilityDimension(
                "history_comparison",
                "🔴 历史对比：单个监控对象在区间内的价格 / 排名 / 评论数 / 上下架四维对比"
                    + "+ 变更事件与市场机会计数",
                true, "listing_change_snapshot + 服务层现算的 SQL 聚合", "",
                "🔴 **对比视图不落表** —— 它是观测快照的聚合，落了表就会与事实漂移"),
            new ListingChangeCapabilityDimension(
                "reference_only_labeling",
                "🔴 全程标注「参考数据」：每个响应都带 dataSource='PLATFORM_SCRAPED' 与 sourceNote / actionsNote",
                true, "服务层常量 DATA_SOURCE / SOURCE_NOTE / ACTIONS_NOTE", "",
                "蓝图加粗句「只做参考指标」因此是**契约级**的硬要求，不是页面上的装饰 —— "
                    + "抓取会滞后、会漏抓、也会抓到已下架 / 已断货的商品"),
            new ListingChangeCapabilityDimension(
                "real_crawl_scheduler",
                "🔴 真实抓取变更数据的**调度器**（按抓取频率定时去平台拉竞品的那个任务）",
                false, "—", "02 渠道与店铺（同步运行时）",
                "本模块提供**入库端点**（通道 WEBHOOK / POLL / API 都记下来），"
                    + "监控对象上的 crawlIntervalMinutes 是给 02 抓取器读的配置，"
                    + "但**没有**那个定时调度器 —— 平台限流与店铺配额由 02 统一裁决，本模块不自建轮询"),
            new ListingChangeCapabilityDimension(
                "analytics_consumption",
                "🔴 **13 分析**：把变更事件流与历史对比接进分析口径",
                false, "—", "13 利润与分析（整卷未交付）",
                "本模块产出的是**事实与事件**，分析口径（竞品份额、价格带分布、机会排序）由 13 侧交付后"
                    + "由它来消费；在这里伪造一个「竞品份额」数字比不提供更危险"),
            new ListingChangeCapabilityDimension(
                "workbench_opportunity_delivery",
                "🔴 **1 工作台**：把市场机会推送到工作台的通知中心",
                false, "—", "01 工作台与门户（整卷未交付）",
                "本模块产出的是**机会记录**，**不主动发通知** —— 工作台的通知投递由 1 侧交付后由它来读；"
                    + "在这里伪造一个「已通知」字段比不通知更危险")),
        TARGET_KINDS,
        LISTING_STATUSES,
        CHANGE_TYPES,
        DIRECTIONS,
        OPPORTUNITY_TYPES,
        SEVERITIES,
        SOURCES,
        INGEST_CHANNELS,
        WATCH_STATUSES,
        OPPORTUNITY_STATUSES,
        RANK_BASES,
        "判定口径（服务端算，前端原样显示）：\n"
            + "· 上下架 LISTING_STATUS：平台报的状态与**上一个已知状态**不同 ⇒ 一条事件；"
            + "平台报 UNKNOWN（没报）⇒ **不产生事件**，「没报」不是「变了」。\n"
            + "· 价格 PRICE / 排名 RANK / 评论数 REVIEW_COUNT：与**上一个已知值**不同 ⇒ 一条事件；"
            + "平台没报该维度（null）⇒ **不产生事件**，也**不参与**下一次的比较基准。\n"
            + "· 🔴 首次观测（此前没有任何观测）**不是**一次变化；但上下架维度会留一条事件"
            + "（旧状态 UNKNOWN → 新状态），因为模块名里的「**上新**」正是这件事。"
            + "价格 / 排名 / 评论数在首次观测时**不产生事件**（没有基准就算不出变化）。\n"
            + "· 🔴 **变化量与比例一律现算**（本次 − 上次已知值），**分母为 0 时是 null**"
            + "（不是 0、也不是 100）。前端原样显示「—」，不要自己重算。\n"
            + "· 🔴 **direction 只说方向，不含好坏**：RANK + INCREASED = 名次数字变大 = 竞品表现变差；"
            + "PRICE + INCREASED = 竞品涨价。好坏由市场机会判定决定。\n"
            + "· 市场机会的四个开关都是**配置**：断货（默认开）、上新（默认关）、"
            + "涨价幅度阈值（**不给 = 不判定**）、排名变差位数（**不给 = 不判定**）。\n"
            + "· 「没配阈值」与「阈值是 0」是两件事：0 意味着「涨 0% 就算涨价」，"
            + "那是一个**永远命中**的配置，不是「没配」。",
        DATA_SOURCE,
        SOURCE_NOTE,
        ACTIONS_NOTE,
        "上游 = 抓取任务（真实抓取调度器归 02，本模块只提供入库端点）· "
            + "下游 = 13 分析（整卷未交付）、1 工作台（整卷未交付）—— "
            + "两段都在本能力表里 available=false + ownerModule，页面上显示「未接入」。"
            + "本模块**不提供任何抢占 / 调价 / 铺货动作**：竞品数据只做参考指标。");
  }

  // ==================================================================
  // 筛选项词表
  // ==================================================================

  /** 筛选词表：平台、站点与各枚举取值域。 */
  public ListingChangeFilterOptions filterOptions() {
    List<ListingChangePlatformOption> platforms =
        sql.fetch("select code,name from platform order by position, code").stream()
            .map(r -> new ListingChangePlatformOption(r.get("code", String.class), r.get("name", String.class)))
            .toList();
    List<ListingChangeMarketplaceOption> marketplaces =
        sql.fetch("select code,name,platform_id from marketplace order by position, code").stream()
            .map(
                r ->
                    new ListingChangeMarketplaceOption(
                        r.get("code", String.class),
                        r.get("name", String.class),
                        platformCodeOf(r.get("platform_id", UUID.class))))
            .toList();
    return new ListingChangeFilterOptions(
        platforms,
        marketplaces,
        TARGET_KINDS,
        LISTING_STATUSES,
        CHANGE_TYPES,
        DIRECTIONS,
        OPPORTUNITY_TYPES,
        SEVERITIES,
        SOURCES,
        WATCH_STATUSES,
        OPPORTUNITY_STATUSES,
        RANK_BASES);
  }

  private String platformCodeOf(UUID platformId) {
    if (platformId == null) {
      return "";
    }
    Object code = sql.fetchValue("select code from platform where id=?", platformId);
    return code == null ? "" : String.valueOf(code);
  }

  // ==================================================================
  // 监控对象
  // ==================================================================

  /** 监控对象列表（真分页 + 筛选）。 */
  public ListingChangeWatchPage watches(
      String status, String platformCode, String targetKind, String keyword, int page, int size) {
    UUID tenant = currentTenant();
    StringBuilder where = new StringBuilder(" where w.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);

    String watchStatus = blankToNull(status);
    if (watchStatus != null) {
      String upper = watchStatus.toUpperCase(Locale.ROOT);
      if (!WATCH_STATUSES.contains(upper)) {
        throw badRequest(
            "未知的监控对象状态「" + watchStatus + "」。只认这两条：" + String.join(" / ", WATCH_STATUSES));
      }
      where.append(" and w.status=?");
      binds.add(upper);
    }
    String platform = blankToNull(platformCode);
    if (platform != null) {
      where.append(" and p.code=?");
      binds.add(platform);
    }
    String kind = blankToNull(targetKind);
    if (kind != null) {
      String upper = kind.toUpperCase(Locale.ROOT);
      if (!TARGET_KINDS.contains(upper)) {
        throw badRequest(
            "未知的目标性质「" + targetKind + "」。只认这两条：" + String.join(" / ", TARGET_KINDS));
      }
      where.append(" and w.target_kind=?");
      binds.add(upper);
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (w.external_item_id ilike ? or w.title ilike ? or w.brand ilike ?)");
      String pattern = likePattern(word);
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }

    String from = WATCH_FROM + where;
    long total = count(from, binds.toArray());
    int lim = clampSize(size);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(lim);
    pageBinds.add(Math.max(0, page) * lim);
    List<ListingChangeWatchRow> items =
        sql.fetch(from + " order by w.created_at desc, w.id desc limit ? offset ?", pageBinds.toArray())
            .stream()
            .map(this::watchRow)
            .toList();

    return new ListingChangeWatchPage(items, Math.max(0, page), lim, total, DATA_SOURCE, SOURCE_NOTE);
  }

  private ListingChangeWatchRow watchRow(Record r) {
    return new ListingChangeWatchRow(
        r.get("id", UUID.class),
        nullToEmpty(r.get("platform_code", String.class)),
        nullToEmpty(r.get("marketplace_code", String.class)),
        nullToEmpty(r.get("external_item_id", String.class)),
        nullToEmpty(r.get("target_kind", String.class)),
        nullToEmpty(r.get("title", String.class)),
        nullToEmpty(r.get("brand", String.class)),
        intOf(r, "crawl_interval_minutes"),
        nullToEmpty(r.get("status", String.class)),
        Boolean.TRUE.equals(r.get("out_of_stock_opportunity", Boolean.class)),
        Boolean.TRUE.equals(r.get("listed_opportunity", Boolean.class)),
        r.get("price_increase_ratio_threshold", BigDecimal.class),
        r.get("rank_worsen_threshold", Integer.class),
        nullToEmpty(r.get("note", String.class)),
        longOf(r, "snapshot_count"),
        longOf(r, "event_count"),
        longOf(r, "opportunity_count"),
        longOf(r, "open_opportunity_count"),
        r.get("last_listing_status", String.class),
        r.get("last_price", BigDecimal.class),
        r.get("last_rank_position", Integer.class),
        r.get("last_review_count", BigDecimal.class),
        nullToEmpty(r.get("currency", String.class)),
        r.get("last_observed_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 新建监控对象。 */
  public ListingChangeWatchRow createWatch(SaveListingChangeWatchRequest request) {
    UUID tenant = currentTenant();
    UUID platformId = requirePlatform(request.platformCode());
    UUID marketplaceId = resolveMarketplace(platformId, request.marketplaceCode());
    String externalItemId = text(request.externalItemId());
    if (externalItemId.isEmpty()) {
      throw unprocessable("必须给平台商品 ID 原文（externalItemId）—— 没有它就不知道盯的是哪个 ASIN");
    }
    String targetKind = normalizeEnum(request.targetKind(), TARGET_KINDS, "目标性质", "COMPETITOR");
    String status = normalizeEnum(request.status(), WATCH_STATUSES, "监控对象状态", "ACTIVE");
    BigDecimal ratio = request.priceIncreaseRatioThreshold();
    if (ratio != null && (ratio.signum() < 0 || ratio.compareTo(BigDecimal.ONE) >= 0)) {
      throw unprocessable("涨价幅度阈值必须落在 [0, 1) 之间（收到 " + ratio + "）");
    }
    Integer rankWorsen = request.rankWorsenThreshold();
    if (rankWorsen != null && rankWorsen <= 0) {
      throw unprocessable("排名变差阈值必须是正整数（名次位数，收到 " + rankWorsen + "）");
    }
    int interval = request.crawlIntervalMinutes() == null ? 720 : request.crawlIntervalMinutes();
    if (interval <= 0) {
      throw unprocessable("抓取频率必须是正整数分钟（收到 " + interval + "）");
    }

    if (sql.fetchOne(
            "select 1 from listing_change_watch where tenant_id=? and platform_id=? and external_item_id=?",
            tenant,
            platformId,
            externalItemId)
        != null) {
      throw conflict("这个平台上的这个 ASIN 已经有监控对象了 —— 同一个竞品配两套抓取频率是配置错误，请改现有那条");
    }

    UUID id = Ids.next();
    sql.execute(
        """
        insert into listing_change_watch
          (id,tenant_id,platform_id,marketplace_id,external_item_id,target_kind,title,brand,
           crawl_interval_minutes,status,out_of_stock_opportunity,listed_opportunity,
           price_increase_ratio_threshold,rank_worsen_threshold,note,created_at,updated_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),now())
        """,
        id,
        tenant,
        platformId,
        marketplaceId,
        externalItemId,
        targetKind,
        note(request.title() == null ? "" : request.title()),
        note(request.brand() == null ? "" : request.brand()),
        interval,
        status,
        request.outOfStockOpportunity() == null ? Boolean.TRUE : request.outOfStockOpportunity(),
        request.listedOpportunity() == null ? Boolean.FALSE : request.listedOpportunity(),
        ratio,
        rankWorsen,
        note(request.note()));
    return loadWatch(tenant, id);
  }

  /**
   * 更新监控对象（<b>PUT = 全量替换</b>：字段不给 = 清空 / 复位）。
   *
   * <p>🔴 目标身份（平台 + ASIN）**不可改**（改了会让历史事件挂到别的竞品上）——
   * 给了不同的 externalItemId 直接 422。
   */
  public ListingChangeWatchRow updateWatch(String watchId, SaveListingChangeWatchRequest request) {
    UUID tenant = currentTenant();
    UUID id = parseId(watchId);
    Record current = loadWatchRecord(tenant, id);
    if (current == null) {
      throw notFound();
    }
    UUID platformId = requirePlatform(request.platformCode());
    UUID marketplaceId = resolveMarketplace(platformId, request.marketplaceCode());
    String externalItemId = text(request.externalItemId());
    if (!externalItemId.equals(nullToEmpty(current.get("external_item_id", String.class)))) {
      throw unprocessable(
          "不能改监控对象指向的 ASIN —— 历史观测与事件是挂在原来那个目标上的。"
              + "要换监控目标请新建一条监控对象");
    }
    String targetKind = normalizeEnum(request.targetKind(), TARGET_KINDS, "目标性质", "COMPETITOR");
    String status = normalizeEnum(request.status(), WATCH_STATUSES, "监控对象状态", "ACTIVE");
    BigDecimal ratio = request.priceIncreaseRatioThreshold();
    if (ratio != null && (ratio.signum() < 0 || ratio.compareTo(BigDecimal.ONE) >= 0)) {
      throw unprocessable("涨价幅度阈值必须落在 [0, 1) 之间（收到 " + ratio + "）");
    }
    Integer rankWorsen = request.rankWorsenThreshold();
    if (rankWorsen != null && rankWorsen <= 0) {
      throw unprocessable("排名变差阈值必须是正整数（名次位数，收到 " + rankWorsen + "）");
    }
    int interval = request.crawlIntervalMinutes() == null ? 720 : request.crawlIntervalMinutes();
    if (interval <= 0) {
      throw unprocessable("抓取频率必须是正整数分钟（收到 " + interval + "）");
    }

    if (sql.fetchOne(
            "select 1 from listing_change_watch where tenant_id=? and platform_id=? and external_item_id=? and id<>?",
            tenant,
            platformId,
            externalItemId,
            id)
        != null) {
      throw conflict("这个平台上的这个 ASIN 已经有监控对象了 —— 同一个竞品配两套抓取频率是配置错误");
    }

    sql.execute(
        """
        update listing_change_watch
           set platform_id=?, marketplace_id=?, target_kind=?, title=?, brand=?,
               crawl_interval_minutes=?, status=?, out_of_stock_opportunity=?, listed_opportunity=?,
               price_increase_ratio_threshold=?, rank_worsen_threshold=?, note=?, updated_at=now()
         where tenant_id=? and id=?
        """,
        platformId,
        marketplaceId,
        targetKind,
        note(request.title() == null ? "" : request.title()),
        note(request.brand() == null ? "" : request.brand()),
        interval,
        status,
        request.outOfStockOpportunity() == null ? Boolean.TRUE : request.outOfStockOpportunity(),
        request.listedOpportunity() == null ? Boolean.FALSE : request.listedOpportunity(),
        ratio,
        rankWorsen,
        note(request.note()),
        tenant,
        id);
    return loadWatch(tenant, id);
  }

  /**
   * 删除监控对象（连同它的抓取批次、观测快照、变更事件与市场机会）。
   *
   * <p>🔴 回执写明一并删掉了多少条 —— 删掉的是事实，调用方必须知道。
   */
  public ListingChangeWatchDeleteResult deleteWatch(String watchId) {
    UUID tenant = currentTenant();
    UUID id = parseId(watchId);
    Record current = loadWatchRecord(tenant, id);
    if (current == null) {
      throw notFound();
    }
    long snapshots = countOf("select count(*) from listing_change_snapshot where tenant_id=? and watch_id=?", tenant, id);
    long events = countOf("select count(*) from listing_change_event where tenant_id=? and watch_id=?", tenant, id);
    long opportunities = countOf("select count(*) from listing_change_opportunity where tenant_id=? and watch_id=?", tenant, id);
    long crawls = countOf("select count(*) from listing_change_crawl where tenant_id=? and watch_id=?", tenant, id);
    sql.execute("delete from listing_change_watch where tenant_id=? and id=?", tenant, id);
    return new ListingChangeWatchDeleteResult(
        id,
        nullToEmpty(current.get("external_item_id", String.class)),
        (int) crawls,
        (int) snapshots,
        (int) events,
        (int) opportunities);
  }

  private UUID requirePlatform(String platformCode) {
    String code = text(platformCode);
    Object id = sql.fetchValue("select id from platform where code=?", code);
    if (id == null) {
      throw unprocessable("定位不到平台「" + code + "」—— 平台码表是只读码表，请先在 02 里登记平台");
    }
    return (UUID) id;
  }

  private UUID resolveMarketplace(UUID platformId, String marketplaceCode) {
    String code = blankToNull(marketplaceCode);
    if (code == null) {
      return null;
    }
    Object id =
        sql.fetchValue(
            "select id from marketplace where platform_id=? and code=?", platformId, code);
    if (id == null) {
      throw unprocessable(
          "这个平台上没有站点「" + code + "」—— 站点码只在平台内唯一（平台才是父级）");
    }
    return (UUID) id;
  }

  private static String normalizeEnum(
      String raw, List<String> allowed, String label, String fallback) {
    String value = blankToNull(raw);
    if (value == null) {
      return fallback;
    }
    String upper = value.toUpperCase(Locale.ROOT);
    if (!allowed.contains(upper)) {
      throw badRequest("未知的" + label + "「" + raw + "」。只认：" + String.join(" / ", allowed));
    }
    return upper;
  }

  private ListingChangeWatchRow loadWatch(UUID tenant, UUID id) {
    Record r = loadWatchRecord(tenant, id);
    if (r == null) {
      throw notFound();
    }
    return watchRow(r);
  }

  private Record loadWatchRecord(UUID tenant, UUID id) {
    return sql.fetchOne(
        WATCH_FROM + " where w.tenant_id=? and w.id=?", tenant, id);
  }

  // ==================================================================
  // 🔴 抓取结果入库 + 变化识别 + 机会判定
  // ==================================================================

  /**
   * 🔴🔴 接收一次抓取任务的结果。
   *
   * <p>五件事，逐条都有理由：
   *
   * <ol>
   *   <li><b>批次级幂等</b>：同一个 idempotencyKey 再次投递 ⇒ <b>整批不落库</b>，
   *       直接返回既有那条抓取记录并把 {@code replayed} 置 true。
   *       平台重投同一批是很常见的（重试、补偿），不挡住就会把事件算两遍。
   *   <li><b>同刻覆盖</b>：同一个 (监控对象, 观测时刻, 来源) 只有一行；
   *       抓取方修正同一次抓取的结果时用**新的**批次 key 推进来 ⇒ 该行被覆盖，revision 递增。
   *   <li><b>四维变化识别</b>：上下架 / 价格 / 排名 / 评论数各自与
   *       <b>上一个已知值</b>比较，变了就落成事件；<b>平台没报（null / UNKNOWN）不产生事件</b>，
   *       也不参与下一次的比较基准。
   *   <li><b>机会判定</b>：事件产生后按监控对象上的四个配置开关判定市场机会；
   *       阈值不给 ⇒ <b>不判定</b>这一条（不猜一个默认门槛）。
   *   <li><b>逐条写明跳过原因</b>：批内观测时刻重复、数值越界的条目被跳过并写明原因，
   *       <b>不静默丢弃</b>。
   * </ol>
   */
  public ListingChangeCrawlResult ingest(IngestListingChangeCrawlRequest request) {
    UUID tenant = currentTenant();
    UUID watchId = request.watchId();
    Record watch = loadWatchRecord(tenant, watchId);
    if (watch == null) {
      throw notFound();
    }
    if ("PAUSED".equals(watch.get("status", String.class))) {
      throw unprocessable(
          "这个监控对象已暂停 —— 暂停期间不接收也不判定。历史观测与事件不会被删，"
              + "恢复（status=ACTIVE）后继续接收");
    }
    String source = normalizeEnum(request.source(), SOURCES, "来源", "PLATFORM");
    String channel = normalizeEnum(request.ingestChannel(), INGEST_CHANNELS, "进来的通道", "API");
    String idempotencyKey = text(request.idempotencyKey());
    if (idempotencyKey.isEmpty()) {
      throw unprocessable("必须给幂等键（idempotencyKey）—— 批次级幂等靠它");
    }

    // ---- 批次级幂等：整批不落库
    Record existing =
        sql.fetchOne(
            "select id,status,received_count,applied_count,skipped_count,event_count,"
                + "opportunity_count,crawled_at,accuracy_window_minutes "
                + "from listing_change_crawl where tenant_id=? and idempotency_key=?",
            tenant,
            idempotencyKey);
    if (existing != null) {
      return new ListingChangeCrawlResult(
          existing.get("id", UUID.class),
          watchId,
          nullToEmpty(existing.get("status", String.class)),
          intOf(existing, "received_count"),
          intOf(existing, "applied_count"),
          intOf(existing, "skipped_count"),
          true,
          idempotencyKey,
          List.of("这个幂等键之前已经落过整批，本次未再落库（平台重投同一批）"),
          existing.get("crawled_at", OffsetDateTime.class),
          intOf(existing, "accuracy_window_minutes"),
          intOf(existing, "event_count"),
          intOf(existing, "opportunity_count"));
    }

    // ---- 枚举值先整批校验：枚举写错是适配器的 bug，应该响亮地失败，而不是悄悄跳过
    List<ListingChangeObservationInput> observations = request.observations();
    for (ListingChangeObservationInput o : observations) {
      normalizeEnum(o.listingStatus(), LISTING_STATUSES, "上下架状态", "UNKNOWN");
      normalizeEnum(o.rankBasis(), RANK_BASES, "排名口径", "BSR");
    }

    UUID crawlId = Ids.next();
    sql.execute(
        """
        insert into listing_change_crawl
          (id,tenant_id,watch_id,source,ingest_channel,crawled_at,accuracy_window_minutes,
           idempotency_key,status,received_count,applied_count,skipped_count,event_count,
           opportunity_count,note,detail,created_at,updated_at)
        values (?,?,?,?,?,?::timestamptz,?,?,'RECEIVED',?,0,0,0,0,?,?::jsonb,now(),now())
        """,
        crawlId,
        tenant,
        watchId,
        source,
        channel,
        Timestamp.from(request.crawledAt().toInstant()),
        request.accuracyWindowMinutes(),
        idempotencyKey,
        observations.size(),
        note(request.note()),
        writeJson(request.detail()));

    List<String> skipped = new ArrayList<>();
    int applied = 0;
    int eventCount = 0;
    int opportunityCount = 0;
    Set<OffsetDateTime> seenObservedAt = new HashSet<>();

    for (ListingChangeObservationInput observation : observations) {
      OffsetDateTime observedAt = observation.observedAt();
      // 批内同一观测时刻重复 ⇒ 后一条会覆盖前一条。不能让数据悄悄消失，写明原因。
      if (!seenObservedAt.add(observedAt)) {
        skipped.add("观测时刻 " + observedAt + " 在本批内重复（后一条会覆盖前一条，已跳过）");
        continue;
      }
      String listingStatus = normalizeEnum(observation.listingStatus(), LISTING_STATUSES, "上下架状态", "UNKNOWN");
      String rankBasis = normalizeEnum(observation.rankBasis(), RANK_BASES, "排名口径", "BSR");
      BigDecimal price = observation.price();
      if (price != null && price.signum() < 0) {
        skipped.add("观测时刻 " + observedAt + "：价格不能是负数（已跳过）");
        continue;
      }
      BigDecimal reviewCount = observation.reviewCount();
      if (reviewCount != null && reviewCount.signum() < 0) {
        skipped.add("观测时刻 " + observedAt + "：评论数不能是负数（已跳过）");
        continue;
      }
      Integer rankPosition = observation.rankPosition();
      if (rankPosition != null && rankPosition <= 0) {
        skipped.add("观测时刻 " + observedAt + "：排名必须是正整数名次（已跳过）");
        continue;
      }
      String currency = nullToEmpty(text(observation.currency())).toUpperCase(Locale.ROOT);
      String rankBasisKey = note(observation.rankBasisKey());

      // ---- 同刻覆盖：修正同一次抓取 ⇒ 该行被覆盖，revision 递增
      sql.execute(
          """
          insert into listing_change_snapshot
            (id,tenant_id,watch_id,crawl_id,observed_at,accuracy_window_minutes,listing_status,
             price,currency,rank_position,rank_basis,rank_basis_key,review_count,source,
             ingest_channel,revision,detail,received_at,created_at,updated_at)
          values (?,?,?,?,?::timestamptz,?,?,?,?,?,?,?,?,?,?,1,?::jsonb,now(),now(),now())
          on conflict (tenant_id,watch_id,observed_at,source) do update set
            crawl_id=excluded.crawl_id,
            accuracy_window_minutes=excluded.accuracy_window_minutes,
            listing_status=excluded.listing_status,
            price=excluded.price,
            currency=excluded.currency,
            rank_position=excluded.rank_position,
            rank_basis=excluded.rank_basis,
            rank_basis_key=excluded.rank_basis_key,
            review_count=excluded.review_count,
            ingest_channel=excluded.ingest_channel,
            revision=listing_change_snapshot.revision+1,
            detail=excluded.detail,
            received_at=now(),
            updated_at=now()
          """,
          Ids.next(),
          tenant,
          watchId,
          crawlId,
          Timestamp.from(observedAt.toInstant()),
          request.accuracyWindowMinutes(),
          listingStatus,
          price,
          currency,
          rankPosition,
          rankBasis,
          rankBasisKey,
          reviewCount,
          source,
          channel,
          writeJson(observation.detail()));
      applied++;

      Record snapshot =
          sql.fetchOne(
              "select id,observed_at,listing_status,price,currency,rank_position,rank_basis,"
                  + "rank_basis_key,review_count from listing_change_snapshot "
                  + "where tenant_id=? and watch_id=? and observed_at=?::timestamptz and source=?",
              tenant,
              watchId,
              Timestamp.from(observedAt.toInstant()),
              source);
      if (snapshot == null) {
        skipped.add("观测时刻 " + observedAt + "：快照写入后读不回来（已跳过）");
        continue;
      }
      UUID snapshotId = snapshot.get("id", UUID.class);

      // ---- 四维变化识别（基准 = 上一个**已知**值）
      int produced = detectChanges(tenant, watch, watchId, snapshot, crawlId, source);
      eventCount += produced;
      opportunityCount += judgeOpportunities(tenant, watch, watchId, snapshot, crawlId, produced);
    }

    String status = applied == 0 ? "RECEIVED" : (skipped.isEmpty() ? "APPLIED" : "PARTIAL");
    sql.execute(
        "update listing_change_crawl set status=?, applied_count=?, skipped_count=?, event_count=?,"
            + " opportunity_count=?, updated_at=now() where tenant_id=? and id=?",
        status,
        applied,
        skipped.size(),
        eventCount,
        opportunityCount,
        tenant,
        crawlId);

    return new ListingChangeCrawlResult(
        crawlId,
        watchId,
        status,
        observations.size(),
        applied,
        skipped.size(),
        false,
        idempotencyKey,
        skipped,
        request.crawledAt(),
        request.accuracyWindowMinutes(),
        eventCount,
        opportunityCount);
  }

  /**
   * 🔴 四维变化识别：把「本次观测」与「上一个已知值」比较，变了就落成事件。
   *
   * <p>🔴 基准是**上一个已知值**而不是「上一次观测」——
   * 平台这次没报价格时，比较基准回退到上一个报过价格的那次，
   * 否则「平台没报」会吃掉下一次真正的价格变化。
   *
   * <p>🔴 首次观测：上下架维度会留一条事件（旧状态 UNKNOWN → 新状态），因为模块名里的
   * 「上新」正是这件事；价格 / 排名 / 评论数**不产生事件**（没有基准就算不出变化）。
   *
   * @return 本次产生的事件条数
   */
  private int detectChanges(
      UUID tenant,
      Record watch,
      UUID watchId,
      Record snapshot,
      UUID crawlId,
      String source) {
    UUID snapshotId = snapshot.get("id", UUID.class);
    OffsetDateTime observedAt = snapshot.get("observed_at", OffsetDateTime.class);
    Timestamp at = Timestamp.from(observedAt.toInstant());
    String listingStatus = nullToEmpty(snapshot.get("listing_status", String.class));
    BigDecimal price = snapshot.get("price", BigDecimal.class);
    Integer rankPosition = snapshot.get("rank_position", Integer.class);
    String rankBasis = nullToEmpty(snapshot.get("rank_basis", String.class));
    BigDecimal reviewCount = snapshot.get("review_count", BigDecimal.class);
    String currency = nullToEmpty(snapshot.get("currency", String.class));

    // ---- 上下架：基准 = 上一个**已知**状态（UNKNOWN 不算已知）
    Record prevStatus =
        sql.fetchOne(
            "select id,listing_status from listing_change_snapshot "
                + "where tenant_id=? and watch_id=? and observed_at < ?::timestamptz "
                + "and listing_status <> 'UNKNOWN' order by observed_at desc, received_at desc limit 1",
            tenant,
            watchId,
            at);
    String previousStatus = prevStatus == null ? null : prevStatus.get("listing_status", String.class);
    UUID previousStatusSnapshot = prevStatus == null ? null : prevStatus.get("id", UUID.class);

    // ---- 价格 / 排名 / 评论数：基准 = 上一个**已知**值
    Record prevPrice =
        sql.fetchOne(
            "select id,price from listing_change_snapshot "
                + "where tenant_id=? and watch_id=? and observed_at < ?::timestamptz and price is not null "
                + "order by observed_at desc, received_at desc limit 1",
            tenant,
            watchId,
            at);
    Record prevRank =
        sql.fetchOne(
            "select id,rank_position from listing_change_snapshot "
                + "where tenant_id=? and watch_id=? and observed_at < ?::timestamptz and rank_position is not null "
                + "order by observed_at desc, received_at desc limit 1",
            tenant,
            watchId,
            at);
    Record prevReview =
        sql.fetchOne(
            "select id,review_count from listing_change_snapshot "
                + "where tenant_id=? and watch_id=? and observed_at < ?::timestamptz and review_count is not null "
                + "order by observed_at desc, received_at desc limit 1",
            tenant,
            watchId,
            at);

    int count = 0;
    // ---- 上下架事件
    if (!"UNKNOWN".equals(listingStatus)
        && (previousStatus == null || !previousStatus.equals(listingStatus))) {
      String reason =
          previousStatus == null
              ? "首次观测到该目标：上下架状态 → " + listingStatus + "（此前无观测）"
              : "上下架状态：" + previousStatus + " → " + listingStatus;
      upsertEvent(
          tenant,
          watchId,
          snapshotId,
          previousStatusSnapshot,
          crawlId,
          "LISTING_STATUS",
          "CHANGED",
          null,
          null,
          previousStatus == null ? "UNKNOWN" : previousStatus,
          listingStatus,
          price,
          currency,
          observedAt,
          reason);
      count++;
    }
    // ---- 价格事件
    if (price != null && prevPrice != null) {
      BigDecimal previous = prevPrice.get("price", BigDecimal.class);
      if (previous != null && previous.compareTo(price) != 0) {
        String direction = price.compareTo(previous) > 0 ? "INCREASED" : "DECREASED";
        upsertEvent(
            tenant,
            watchId,
            snapshotId,
            prevPrice.get("id", UUID.class),
            crawlId,
            "PRICE",
            direction,
            previous,
            price,
            null,
            null,
            price,
            currency,
            observedAt,
            "价格：" + previous + " → " + price + (currency.isEmpty() ? "" : " " + currency));
        count++;
      }
    }
    // ---- 排名事件（名次数字变大 = 竞品表现变差）
    if (rankPosition != null && prevRank != null) {
      Integer previous = prevRank.get("rank_position", Integer.class);
      if (previous != null && previous != rankPosition) {
        String direction = rankPosition > previous ? "INCREASED" : "DECREASED";
        upsertEvent(
            tenant,
            watchId,
            snapshotId,
            prevRank.get("id", UUID.class),
            crawlId,
            "RANK",
            direction,
            new BigDecimal(previous),
            new BigDecimal(rankPosition),
            null,
            null,
            price,
            currency,
            observedAt,
            "排名（" + rankBasis + "）：" + previous + " → " + rankPosition
                + "（名次数字变大 = 竞品表现变差）");
        count++;
      }
    }
    // ---- 评论数事件
    if (reviewCount != null && prevReview != null) {
      BigDecimal previous = prevReview.get("review_count", BigDecimal.class);
      if (previous != null && previous.compareTo(reviewCount) != 0) {
        String direction = reviewCount.compareTo(previous) > 0 ? "INCREASED" : "DECREASED";
        upsertEvent(
            tenant,
            watchId,
            snapshotId,
            prevReview.get("id", UUID.class),
            crawlId,
            "REVIEW_COUNT",
            direction,
            previous,
            reviewCount,
            null,
            null,
            price,
            currency,
            observedAt,
            "评论数：" + previous + " → " + reviewCount);
        count++;
      }
    }
    return count;
  }

  /**
   * 落一条变更事件（同一次观测的同一维度只有一行：修正同一次抓取时覆盖）。
   *
   * <p>🔴 事件里存的是**当时那一对观测值的快照**（previous_value / current_value），
   * <b>不存变化量与比例</b> —— 那是查询时现算的量。
   */
  private void upsertEvent(
      UUID tenant,
      UUID watchId,
      UUID snapshotId,
      UUID previousSnapshotId,
      UUID crawlId,
      String changeType,
      String direction,
      BigDecimal previousValue,
      BigDecimal currentValue,
      String previousState,
      String currentState,
      BigDecimal observedPrice,
      String currency,
      OffsetDateTime detectedAt,
      String reason) {
    sql.execute(
        """
        insert into listing_change_event
          (id,tenant_id,watch_id,snapshot_id,previous_snapshot_id,crawl_id,change_type,direction,
           previous_value,current_value,previous_state,current_state,observed_price,currency,
           detected_at,reason,detail,created_at,updated_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,'{}'::jsonb,now(),now())
        on conflict (tenant_id,watch_id,change_type,snapshot_id) do update set
          previous_snapshot_id=excluded.previous_snapshot_id,
          crawl_id=excluded.crawl_id,
          direction=excluded.direction,
          previous_value=excluded.previous_value,
          current_value=excluded.current_value,
          previous_state=excluded.previous_state,
          current_state=excluded.current_state,
          observed_price=excluded.observed_price,
          currency=excluded.currency,
          detected_at=excluded.detected_at,
          reason=excluded.reason,
          updated_at=now()
        """,
        Ids.next(),
        tenant,
        watchId,
        snapshotId,
        previousSnapshotId,
        crawlId,
        changeType,
        direction,
        previousValue,
        currentValue,
        previousState,
        currentState,
        observedPrice,
        currency,
        Timestamp.from(detectedAt.toInstant()),
        reason);
  }

  /**
   * 🔴 市场机会判定：事件产生后按监控对象上的四个配置开关判定。
   *
   * <p>🔴 阈值是**配置**，不给 = 该维度**不判定**（不猜一个默认门槛）。
   * 🔴 涨价比例的分母（上次价格）为 0 时比例是 null ⇒ **不判定**这一条。
   *
   * @param produced 本次观测产生的事件条数（用于限定只判定本次产生的事件）
   * @return 本次产生的市场机会条数
   */
  private int judgeOpportunities(
      UUID tenant,
      Record watch,
      UUID watchId,
      Record snapshot,
      UUID crawlId,
      int produced) {
    if (produced <= 0) {
      return 0;
    }
    UUID snapshotId = snapshot.get("id", UUID.class);
    OffsetDateTime observedAt = snapshot.get("observed_at", OffsetDateTime.class);
    BigDecimal price = snapshot.get("price", BigDecimal.class);
    Integer rankPosition = snapshot.get("rank_position", Integer.class);
    BigDecimal reviewCount = snapshot.get("review_count", BigDecimal.class);
    String currency = nullToEmpty(snapshot.get("currency", String.class));

    boolean outOfStockEnabled =
        Boolean.TRUE.equals(watch.get("out_of_stock_opportunity", Boolean.class));
    boolean listedEnabled = Boolean.TRUE.equals(watch.get("listed_opportunity", Boolean.class));
    BigDecimal priceThreshold = watch.get("price_increase_ratio_threshold", BigDecimal.class);
    Integer rankThreshold = watch.get("rank_worsen_threshold", Integer.class);

    List<Record> events =
        sql.fetch(
            "select id,change_type,direction,previous_value,current_value,previous_state,current_state "
                + "from listing_change_event where tenant_id=? and watch_id=? and snapshot_id=?",
            tenant,
            watchId,
            snapshotId);

    int count = 0;
    for (Record e : events) {
      String changeType = nullToEmpty(e.get("change_type", String.class));
      String direction = nullToEmpty(e.get("direction", String.class));
      BigDecimal previousValue = e.get("previous_value", BigDecimal.class);
      BigDecimal currentValue = e.get("current_value", BigDecimal.class);
      String previousState = e.get("previous_state", String.class);
      String currentState = e.get("current_state", String.class);
      UUID eventId = e.get("id", UUID.class);

      String type = null;
      String severity = null;
      String reason = null;

      if ("LISTING_STATUS".equals(changeType) && "OUT_OF_STOCK".equals(currentState)) {
        if (outOfStockEnabled) {
          type = "COMPETITOR_OUT_OF_STOCK";
          severity = "HIGH";
          reason = "竞品断货（上下架状态 → OUT_OF_STOCK）—— 断货期是进入该品类的窗口";
        }
      } else if ("LISTING_STATUS".equals(changeType) && "LISTED".equals(currentState)) {
        if (listedEnabled
            && (previousState == null
                || "DELISTED".equals(previousState)
                || "OUT_OF_STOCK".equals(previousState)
                || "UNKNOWN".equals(previousState))) {
          type = "COMPETITOR_LISTED";
          severity = "LOW";
          reason = "竞品上新 / 重新上架（"
              + (previousState == null ? "首次观测" : previousState + " → LISTED") + "）";
        }
      } else if ("PRICE".equals(changeType) && "INCREASED".equals(direction)) {
        if (priceThreshold != null && previousValue != null && currentValue != null) {
          BigDecimal changeRatio =
              ratio(currentValue.subtract(previousValue), previousValue);
          if (changeRatio != null && changeRatio.compareTo(priceThreshold) > 0) {
            type = "COMPETITOR_PRICE_INCREASE";
            severity =
                changeRatio.compareTo(priceThreshold.multiply(BigDecimal.valueOf(2))) >= 0
                    ? "HIGH"
                    : "MEDIUM";
            reason =
                "竞品涨价 " + changeRatio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP)
                    + "%，超过配置的阈值 "
                    + priceThreshold.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%";
          }
        }
      } else if ("RANK".equals(changeType) && "INCREASED".equals(direction)) {
        if (rankThreshold != null && previousValue != null && currentValue != null) {
          int drop = currentValue.subtract(previousValue).intValue();
          if (drop >= rankThreshold) {
            type = "COMPETITOR_RANK_WORSENED";
            severity = "MEDIUM";
            reason =
                "竞品排名变差 " + drop + " 位（名次 "
                    + previousValue.toPlainString() + " → " + currentValue.toPlainString()
                    + "），达到配置的阈值 " + rankThreshold + " 位";
          }
        }
      }

      if (type == null) {
        continue;
      }
      // 一个事件最多一条机会：重复投递（修正同一次抓取）时不重算机会。
      sql.execute(
          """
          insert into listing_change_opportunity
            (id,tenant_id,watch_id,event_id,opportunity_type,severity,observed_price,observed_rank,
             observed_review_count,currency,status,reason,detected_at,detail,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,'OPEN',?,?::timestamptz,'{}'::jsonb,now(),now())
          on conflict (tenant_id,event_id) do nothing
          """,
          Ids.next(),
          tenant,
          watchId,
          eventId,
          type,
          severity,
          price,
          rankPosition,
          reviewCount,
          currency,
          reason,
          Timestamp.from(observedAt.toInstant()));
      count++;
    }
    return count;
  }

  // ==================================================================
  // 抓取批次
  // ==================================================================

  /** 抓取批次列表（真分页 + 筛选）。 */
  public ListingChangeCrawlPage crawls(String watchId, int page, int size) {
    UUID tenant = currentTenant();
    StringBuilder where = new StringBuilder(" where c.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    String watch = blankToNull(watchId);
    if (watch != null) {
      where.append(" and c.watch_id=?");
      binds.add(parseId(watch));
    }
    String from =
        """
        select c.id,c.watch_id,w.external_item_id,c.source,c.ingest_channel,c.crawled_at,
               c.accuracy_window_minutes,c.status,c.received_count,c.applied_count,c.skipped_count,
               c.event_count,c.opportunity_count,c.idempotency_key,c.note,c.created_at
        from listing_change_crawl c
        join listing_change_watch w on w.tenant_id=c.tenant_id and w.id=c.watch_id
        """
            + where;
    long total = count(from, binds.toArray());
    int lim = clampSize(size);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(lim);
    pageBinds.add(Math.max(0, page) * lim);
    List<ListingChangeCrawlRow> items =
        sql.fetch(from + " order by c.crawled_at desc, c.id desc limit ? offset ?", pageBinds.toArray())
            .stream()
            .map(
                r ->
                    new ListingChangeCrawlRow(
                        r.get("id", UUID.class),
                        r.get("watch_id", UUID.class),
                        nullToEmpty(r.get("external_item_id", String.class)),
                        nullToEmpty(r.get("source", String.class)),
                        nullToEmpty(r.get("ingest_channel", String.class)),
                        r.get("crawled_at", OffsetDateTime.class),
                        intOf(r, "accuracy_window_minutes"),
                        nullToEmpty(r.get("status", String.class)),
                        intOf(r, "received_count"),
                        intOf(r, "applied_count"),
                        intOf(r, "skipped_count"),
                        intOf(r, "event_count"),
                        intOf(r, "opportunity_count"),
                        nullToEmpty(r.get("idempotency_key", String.class)),
                        nullToEmpty(r.get("note", String.class)),
                        r.get("created_at", OffsetDateTime.class)))
            .toList();
    return new ListingChangeCrawlPage(items, Math.max(0, page), lim, total, DATA_SOURCE, SOURCE_NOTE);
  }

  // ==================================================================
  // 观测快照（历史对比的事实源）
  // ==================================================================

  /** 某个监控对象的历史观测（真分页 + 时间范围筛选）。 */
  public ListingChangeSnapshotPage history(
      String watchId, String from, String to, int page, int size) {
    UUID tenant = currentTenant();
    UUID watch = parseId(watchId);
    if (loadWatchRecord(tenant, watch) == null) {
      throw notFound();
    }
    LocalDate fromDate = parseDate(from, "区间起");
    LocalDate toDate = parseDate(to, "区间止");
    StringBuilder where = new StringBuilder(" where s.tenant_id=? and s.watch_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    binds.add(watch);
    if (fromDate != null) {
      where.append(" and s.observed_at >= ?::timestamptz");
      binds.add(utcStart(fromDate));
    }
    if (toDate != null) {
      where.append(" and s.observed_at < ?::timestamptz");
      binds.add(utcDayAfter(toDate));
    }
    String fromSql =
        """
        select s.id,s.watch_id,w.external_item_id,s.observed_at,s.accuracy_window_minutes,
               s.listing_status,s.price,s.currency,s.rank_position,s.rank_basis,s.rank_basis_key,
               s.review_count,s.source,s.ingest_channel,s.revision,s.received_at
        from listing_change_snapshot s
        join listing_change_watch w on w.tenant_id=s.tenant_id and w.id=s.watch_id
        """
            + where;
    long total = count(fromSql, binds.toArray());
    int lim = clampSize(size);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(lim);
    pageBinds.add(Math.max(0, page) * lim);
    List<ListingChangeSnapshotRow> items =
        sql.fetch(
                fromSql + " order by s.observed_at desc, s.received_at desc limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(
                r ->
                    new ListingChangeSnapshotRow(
                        r.get("id", UUID.class),
                        r.get("watch_id", UUID.class),
                        nullToEmpty(r.get("external_item_id", String.class)),
                        r.get("observed_at", OffsetDateTime.class),
                        intOf(r, "accuracy_window_minutes"),
                        nullToEmpty(r.get("listing_status", String.class)),
                        r.get("price", BigDecimal.class),
                        nullToEmpty(r.get("currency", String.class)),
                        r.get("rank_position", Integer.class),
                        nullToEmpty(r.get("rank_basis", String.class)),
                        nullToEmpty(r.get("rank_basis_key", String.class)),
                        r.get("review_count", BigDecimal.class),
                        nullToEmpty(r.get("source", String.class)),
                        nullToEmpty(r.get("ingest_channel", String.class)),
                        intOf(r, "revision"),
                        DATA_SOURCE,
                        SOURCE_NOTE))
            .toList();
    return new ListingChangeSnapshotPage(items, Math.max(0, page), lim, total, DATA_SOURCE, SOURCE_NOTE);
  }

  // ==================================================================
  // 变更事件流
  // ==================================================================

  /**
   * 变更事件流（真分页 + 真筛选）。
   *
   * <p>🔴 变化量与比例在 SQL 里**现算**，分母为 0 时是 null。
   */
  public ListingChangeEventPage events(
      String watchId, String changeType, String direction, String from, String to, int page, int size) {
    UUID tenant = currentTenant();
    StringBuilder where = new StringBuilder(" where e.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    String watch = blankToNull(watchId);
    if (watch != null) {
      where.append(" and e.watch_id=?");
      binds.add(parseId(watch));
    }
    String type = blankToNull(changeType);
    if (type != null) {
      String upper = type.toUpperCase(Locale.ROOT);
      if (!CHANGE_TYPES.contains(upper)) {
        throw badRequest(
            "未知的变化维度「" + changeType + "」。只认：" + String.join(" / ", CHANGE_TYPES));
      }
      where.append(" and e.change_type=?");
      binds.add(upper);
    }
    String dir = blankToNull(direction);
    if (dir != null) {
      String upper = dir.toUpperCase(Locale.ROOT);
      if (!DIRECTIONS.contains(upper)) {
        throw badRequest(
            "未知的变化方向「" + direction + "」。只认：" + String.join(" / ", DIRECTIONS));
      }
      where.append(" and e.direction=?");
      binds.add(upper);
    }
    LocalDate fromDate = parseDate(from, "区间起");
    LocalDate toDate = parseDate(to, "区间止");
    if (fromDate != null) {
      where.append(" and e.detected_at >= ?::timestamptz");
      binds.add(utcStart(fromDate));
    }
    if (toDate != null) {
      where.append(" and e.detected_at < ?::timestamptz");
      binds.add(utcDayAfter(toDate));
    }

    String fromSql =
        """
        select e.id,e.watch_id,e.snapshot_id,e.previous_snapshot_id,w.external_item_id,w.title,
               e.change_type,e.direction,e.previous_value,e.current_value,e.previous_state,e.current_state,
               e.observed_price,e.currency,e.detected_at,e.reason,
               case when e.change_type='PRICE' and e.previous_value is not null and e.current_value is not null
                    then e.current_value - e.previous_value end as price_change_amount,
               case when e.change_type='PRICE' and e.previous_value is not null and e.current_value is not null
                     and e.previous_value <> 0
                    then round((e.current_value - e.previous_value) / e.previous_value, 6) end
                    as price_change_ratio,
               case when e.change_type='RANK' and e.previous_value is not null and e.current_value is not null
                    then (e.current_value - e.previous_value)::int end as rank_change,
               case when e.change_type='REVIEW_COUNT' and e.previous_value is not null and e.current_value is not null
                    then e.current_value - e.previous_value end as review_count_change
        from listing_change_event e
        join listing_change_watch w on w.tenant_id=e.tenant_id and w.id=e.watch_id
        """
            + where;
    long total = count(fromSql, binds.toArray());
    int lim = clampSize(size);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(lim);
    pageBinds.add(Math.max(0, page) * lim);
    List<ListingChangeEventRow> items =
        sql.fetch(fromSql + " order by e.detected_at desc, e.id desc limit ? offset ?", pageBinds.toArray())
            .stream()
            .map(
                r ->
                    new ListingChangeEventRow(
                        r.get("id", UUID.class),
                        r.get("watch_id", UUID.class),
                        r.get("snapshot_id", UUID.class),
                        r.get("previous_snapshot_id", UUID.class),
                        nullToEmpty(r.get("external_item_id", String.class)),
                        nullToEmpty(r.get("title", String.class)),
                        nullToEmpty(r.get("change_type", String.class)),
                        nullToEmpty(r.get("direction", String.class)),
                        r.get("previous_value", BigDecimal.class),
                        r.get("current_value", BigDecimal.class),
                        r.get("previous_state", String.class),
                        r.get("current_state", String.class),
                        r.get("price_change_amount", BigDecimal.class),
                        r.get("price_change_ratio", BigDecimal.class),
                        r.get("rank_change", Integer.class),
                        r.get("review_count_change", BigDecimal.class),
                        r.get("observed_price", BigDecimal.class),
                        nullToEmpty(r.get("currency", String.class)),
                        r.get("detected_at", OffsetDateTime.class),
                        nullToEmpty(r.get("reason", String.class)),
                        DATA_SOURCE,
                        SOURCE_NOTE))
            .toList();
    long watchCount = countOf("select count(*) from listing_change_watch where tenant_id=?", tenant);
    return new ListingChangeEventPage(
        items, Math.max(0, page), lim, total, watchCount, DATA_SOURCE, SOURCE_NOTE);
  }

  // ==================================================================
  // 市场机会
  // ==================================================================

  /** 市场机会列表（真分页 + 筛选）。 */
  public ListingChangeOpportunityPage opportunities(
      String watchId,
      String opportunityType,
      String severity,
      String status,
      String from,
      String to,
      int page,
      int size) {
    UUID tenant = currentTenant();
    StringBuilder where = new StringBuilder(" where o.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    String watch = blankToNull(watchId);
    if (watch != null) {
      where.append(" and o.watch_id=?");
      binds.add(parseId(watch));
    }
    String type = blankToNull(opportunityType);
    if (type != null) {
      String upper = type.toUpperCase(Locale.ROOT);
      if (!OPPORTUNITY_TYPES.contains(upper)) {
        throw badRequest(
            "未知的市场机会类型「" + opportunityType + "」。只认：" + String.join(" / ", OPPORTUNITY_TYPES));
      }
      where.append(" and o.opportunity_type=?");
      binds.add(upper);
    }
    String sev = blankToNull(severity);
    if (sev != null) {
      String upper = sev.toUpperCase(Locale.ROOT);
      if (!SEVERITIES.contains(upper)) {
        throw badRequest("未知的严重度「" + severity + "」。只认：" + String.join(" / ", SEVERITIES));
      }
      where.append(" and o.severity=?");
      binds.add(upper);
    }
    String st = blankToNull(status);
    if (st != null) {
      String upper = st.toUpperCase(Locale.ROOT);
      if (!OPPORTUNITY_STATUSES.contains(upper)) {
        throw badRequest(
            "未知的机会状态「" + status + "」。只认：" + String.join(" / ", OPPORTUNITY_STATUSES));
      }
      where.append(" and o.status=?");
      binds.add(upper);
    }
    LocalDate fromDate = parseDate(from, "区间起");
    LocalDate toDate = parseDate(to, "区间止");
    if (fromDate != null) {
      where.append(" and o.detected_at >= ?::timestamptz");
      binds.add(utcStart(fromDate));
    }
    if (toDate != null) {
      where.append(" and o.detected_at < ?::timestamptz");
      binds.add(utcDayAfter(toDate));
    }

    String fromSql =
        """
        select o.id,o.watch_id,o.event_id,w.external_item_id,w.title,o.opportunity_type,o.severity,
               o.observed_price,o.observed_rank,o.observed_review_count,o.currency,o.status,o.reason,
               o.detected_at,o.acknowledged_at
        from listing_change_opportunity o
        join listing_change_watch w on w.tenant_id=o.tenant_id and w.id=o.watch_id
        """
            + where;
    long total = count(fromSql, binds.toArray());
    long openCount =
        countOf(
            "select count(*) from listing_change_opportunity where tenant_id=? and status='OPEN'", tenant);
    int lim = clampSize(size);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(lim);
    pageBinds.add(Math.max(0, page) * lim);
    List<ListingChangeOpportunityRow> items =
        sql.fetch(
                fromSql + " order by o.detected_at desc, o.id desc limit ? offset ?", pageBinds.toArray())
            .stream()
            .map(
                r ->
                    new ListingChangeOpportunityRow(
                        r.get("id", UUID.class),
                        r.get("watch_id", UUID.class),
                        r.get("event_id", UUID.class),
                        nullToEmpty(r.get("external_item_id", String.class)),
                        nullToEmpty(r.get("title", String.class)),
                        nullToEmpty(r.get("opportunity_type", String.class)),
                        nullToEmpty(r.get("severity", String.class)),
                        r.get("observed_price", BigDecimal.class),
                        r.get("observed_rank", Integer.class),
                        r.get("observed_review_count", BigDecimal.class),
                        nullToEmpty(r.get("currency", String.class)),
                        nullToEmpty(r.get("status", String.class)),
                        nullToEmpty(r.get("reason", String.class)),
                        r.get("detected_at", OffsetDateTime.class),
                        r.get("acknowledged_at", OffsetDateTime.class),
                        DATA_SOURCE,
                        SOURCE_NOTE))
            .toList();
    return new ListingChangeOpportunityPage(
        items, Math.max(0, page), lim, total, openCount, DATA_SOURCE, SOURCE_NOTE);
  }

  /**
   * 标记市场机会为「已阅」。
   *
   * <p>🔴 这是**内部记录状态**，**不是任何外部动作** —— 本模块不提供抢占 / 调价 / 铺货入口，
   * 机会需要人工判断后再在平台侧或自有定价流程里执行。已经阅过的再阅一次是幂等的。
   */
  public ListingChangeOpportunityRow acknowledgeOpportunity(
      String opportunityId, AcknowledgeListingChangeOpportunityRequest request) {
    UUID tenant = currentTenant();
    UUID id = parseId(opportunityId);
    Record current =
        sql.fetchOne(
            "select id,status from listing_change_opportunity where tenant_id=? and id=?", tenant, id);
    if (current == null) {
      throw notFound();
    }
    sql.execute(
        "update listing_change_opportunity set status='ACKNOWLEDGED',"
            + " acknowledged_at=now(), acknowledged_by=nullif(current_setting('app.actor',true),'')::uuid,"
            + " updated_at=now() where tenant_id=? and id=? and status<>'ACKNOWLEDGED'",
        tenant,
        id);
    return loadOpportunity(tenant, id);
  }

  private ListingChangeOpportunityRow loadOpportunity(UUID tenant, UUID id) {
    Record r =
        sql.fetchOne(
            """
            select o.id,o.watch_id,o.event_id,w.external_item_id,w.title,o.opportunity_type,o.severity,
                   o.observed_price,o.observed_rank,o.observed_review_count,o.currency,o.status,o.reason,
                   o.detected_at,o.acknowledged_at
            from listing_change_opportunity o
            join listing_change_watch w on w.tenant_id=o.tenant_id and w.id=o.watch_id
            where o.tenant_id=? and o.id=?
            """,
            tenant,
            id);
    if (r == null) {
      throw notFound();
    }
    return new ListingChangeOpportunityRow(
        r.get("id", UUID.class),
        r.get("watch_id", UUID.class),
        r.get("event_id", UUID.class),
        nullToEmpty(r.get("external_item_id", String.class)),
        nullToEmpty(r.get("title", String.class)),
        nullToEmpty(r.get("opportunity_type", String.class)),
        nullToEmpty(r.get("severity", String.class)),
        r.get("observed_price", BigDecimal.class),
        r.get("observed_rank", Integer.class),
        r.get("observed_review_count", BigDecimal.class),
        nullToEmpty(r.get("currency", String.class)),
        nullToEmpty(r.get("status", String.class)),
        nullToEmpty(r.get("reason", String.class)),
        r.get("detected_at", OffsetDateTime.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        DATA_SOURCE,
        SOURCE_NOTE);
  }

  // ==================================================================
  // 🔴 历史对比（SQL 聚合，不落表）
  // ==================================================================

  /**
   * 🔴 单个监控对象的历史对比。
   *
   * <p>🔴 **对比视图不落表** —— 它是观测快照与变更事件的 SQL 聚合。
   * 落了表就会与事实漂移（改了阈值、修正了同一次抓取，旧的比例不会跟着变）。
   *
   * <p>🔴 变化比例现算，**分母（区间内第一个价格）为 0 时是 null**，不是 0。
   */
  public ListingChangeComparison comparison(String watchId, String from, String to) {
    UUID tenant = currentTenant();
    UUID watch = parseId(watchId);
    Record w = loadWatchRecord(tenant, watch);
    if (w == null) {
      throw notFound();
    }
    LocalDate fromDate = parseDate(from, "区间起");
    LocalDate toDate = parseDate(to, "区间止");
    if (fromDate == null) {
      fromDate = LocalDate.now(ZoneOffset.UTC).minusDays(30);
    }
    if (toDate == null) {
      toDate = LocalDate.now(ZoneOffset.UTC);
    }
    if (toDate.isBefore(fromDate)) {
      throw unprocessable("区间止不能早于区间起");
    }
    Timestamp start = utcStart(fromDate);
    Timestamp end = utcDayAfter(toDate);

    Record agg =
        sql.fetchOne(
            """
            with s as (
              select price,currency,rank_position,rank_basis,listing_status,review_count,
                     observed_at,received_at
              from listing_change_snapshot
              where tenant_id=? and watch_id=? and observed_at >= ?::timestamptz
                and observed_at < ?::timestamptz
            ),
            price_agg as (
              select (array_agg(price order by observed_at asc, received_at asc)
                        filter (where price is not null))[1] as first_price,
                     (array_agg(price order by observed_at desc, received_at desc)
                        filter (where price is not null))[1] as last_price,
                     min(price) as min_price, max(price) as max_price, avg(price) as avg_price,
                     count(price) as price_samples,
                     (array_agg(currency order by observed_at desc, received_at desc)
                        filter (where currency <> ''))[1] as currency
              from s
            ),
            rank_agg as (
              select (array_agg(rank_position order by observed_at asc, received_at asc)
                        filter (where rank_position is not null))[1] as first_rank,
                     (array_agg(rank_position order by observed_at desc, received_at desc)
                        filter (where rank_position is not null))[1] as last_rank,
                     min(rank_position) as best_rank, max(rank_position) as worst_rank,
                     count(rank_position) as rank_samples,
                     (array_agg(rank_basis order by observed_at desc, received_at desc)
                        filter (where rank_position is not null))[1] as rank_basis
              from s
            ),
            review_agg as (
              select (array_agg(review_count order by observed_at asc, received_at asc)
                        filter (where review_count is not null))[1] as first_count,
                     (array_agg(review_count order by observed_at desc, received_at desc)
                        filter (where review_count is not null))[1] as last_count,
                     count(review_count) as review_samples
              from s
            ),
            status_agg as (
              select (array_agg(listing_status order by observed_at desc, received_at desc)
                        filter (where listing_status <> 'UNKNOWN'))[1] as current_status,
                     count(*) filter (where listing_status='LISTED') as listed_count,
                     count(*) filter (where listing_status='OUT_OF_STOCK') as out_of_stock_count,
                     count(*) filter (where listing_status='DELISTED') as delisted_count,
                     count(*) filter (where listing_status <> 'UNKNOWN') as status_samples
              from s
            )
            select (select count(*) from s) as sample_count,
                   price_agg.*, rank_agg.*, review_agg.*, status_agg.*,
                   (select count(*) from listing_change_event e
                     where e.tenant_id=? and e.watch_id=?
                       and e.detected_at >= ?::timestamptz and e.detected_at < ?::timestamptz) as event_count,
                   (select count(*) from listing_change_event e
                     where e.tenant_id=? and e.watch_id=? and e.change_type='LISTING_STATUS'
                       and e.detected_at >= ?::timestamptz and e.detected_at < ?::timestamptz) as transition_count,
                   (select count(*) from listing_change_opportunity o
                     where o.tenant_id=? and o.watch_id=?
                       and o.detected_at >= ?::timestamptz and o.detected_at < ?::timestamptz) as opportunity_count
            from price_agg, rank_agg, review_agg, status_agg
            """,
            tenant,
            watch,
            start,
            end,
            tenant,
            watch,
            start,
            end,
            tenant,
            watch,
            start,
            end,
            tenant,
            watch,
            start,
            end);
    if (agg == null) {
      throw notFound();
    }

    long sampleCount = longOf(agg, "sample_count");
    BigDecimal firstPrice = agg.get("first_price", BigDecimal.class);
    BigDecimal lastPrice = agg.get("last_price", BigDecimal.class);
    BigDecimal changeAmount = null;
    BigDecimal changeRatio = null;
    if (firstPrice != null && lastPrice != null) {
      changeAmount = lastPrice.subtract(firstPrice);
      // 🔴 分母为 0 时是 null（不是 0）：报 0 会被读成「价格没变」，那是结论不是缺口。
      changeRatio = ratio(changeAmount, firstPrice);
    }
    Integer firstRank = agg.get("first_rank", Integer.class);
    Integer lastRank = agg.get("last_rank", Integer.class);
    Integer rankChange = (firstRank != null && lastRank != null) ? lastRank - firstRank : null;
    BigDecimal firstCount = agg.get("first_count", BigDecimal.class);
    BigDecimal lastCount = agg.get("last_count", BigDecimal.class);
    BigDecimal reviewChange =
        (firstCount != null && lastCount != null) ? lastCount.subtract(firstCount) : null;

    boolean hasData = sampleCount > 0;
    String emptyReason =
        hasData
            ? ""
            : "这个监控对象在所选区间内还没有观测数据 —— 抓取结果进来之后才会有。";

    return new ListingChangeComparison(
        watch,
        nullToEmpty(w.get("platform_code", String.class)),
        nullToEmpty(w.get("marketplace_code", String.class)),
        nullToEmpty(w.get("external_item_id", String.class)),
        nullToEmpty(w.get("target_kind", String.class)),
        nullToEmpty(w.get("title", String.class)),
        fromDate.toString(),
        toDate.toString(),
        sampleCount,
        longOf(agg, "event_count"),
        longOf(agg, "opportunity_count"),
        new ListingChangePriceComparison(
            firstPrice,
            lastPrice,
            agg.get("min_price", BigDecimal.class),
            agg.get("max_price", BigDecimal.class),
            agg.get("avg_price", BigDecimal.class),
            changeAmount,
            changeRatio,
            nullToEmpty(agg.get("currency", String.class)),
            longOf(agg, "price_samples")),
        new ListingChangeRankComparison(
            firstRank,
            lastRank,
            agg.get("best_rank", Integer.class),
            agg.get("worst_rank", Integer.class),
            rankChange,
            nullToEmpty(agg.get("rank_basis", String.class)),
            longOf(agg, "rank_samples")),
        new ListingChangeReviewComparison(
            firstCount, lastCount, reviewChange, longOf(agg, "review_samples")),
        new ListingChangeStatusComparison(
            agg.get("current_status", String.class),
            longOf(agg, "listed_count"),
            longOf(agg, "out_of_stock_count"),
            longOf(agg, "delisted_count"),
            longOf(agg, "transition_count"),
            longOf(agg, "status_samples")),
        hasData,
        emptyReason,
        DATA_SOURCE,
        SOURCE_NOTE);
  }
}
