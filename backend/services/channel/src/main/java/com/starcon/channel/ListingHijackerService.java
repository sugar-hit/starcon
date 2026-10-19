package com.starcon.channel;

import static com.starcon.channel.ListingHijackerModels.*;

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
 * 4.5.2.1 跟卖监控。
 *
 * <p>蓝图：「监控他人/己方其他店铺对本 ASIN 的跟卖行为，记录跟卖者、价格、店铺，发现异常跟卖或恶意压价时告警。输入：定时抓取或平台接口返回的跟卖信息。输出：跟卖记录 + 预警通知 + 对比视图。关系：上游 = 抓取任务；下游 = 16 合规（品牌保护）、1 工作台预警。<b>跟卖数据只能作为参考</b>，处置动作（投诉/举报）在平台侧完成，系统只负责发现与提醒。」
 *
 * <p>🔴 本类的五个核心不变量，每一个都有集成测试逐条钉住：
 *
 * <ol>
 *   <li><b>🔴🔴 只发现与提醒，不处置</b> —— 本类**没有**任何投诉 / 举报 / 申诉的动作，
 *       {@link #capabilities()} 里 {@code brand_protection_enforcement} 与
 *       {@code workbench_alert_notification} 两维 available=false + ownerModule + impact。
 *       预警记录上的「已阅」只是内部状态，<b>不代表已在平台侧发起处置</b>。
 *   <li><b>🔴🔴 比率一律现算，分母为 0 时是 null</b> —— 价格差比例
 *       （价格差 ÷ 自家参考价）由 SQL 现算，<b>分母为 0 时返回 null</b>，
 *       不是 0 也不是 100：报 0 会被读成「跟卖价与自家价一样」，那是结论不是缺口。
 *   <li><b>🔴 己方其他店铺跟卖是算出来的</b> —— {@code hijackerKind} 由本类按跟卖者的
 *       <b>平台侧店铺标识</b>匹配本租户自有店铺得出（OWN_SHOP / EXTERNAL），
 *       调用方<b>不能</b>直接指定这个性质 —— 否则一个抓取适配器报错类型，整条数据的性质就错了。
 *   <li><b>🔴 幂等与跳过是两件事</b> —— 批次级幂等键让「平台重投同一批」<b>整批不落库</b>；
 *       同一条 (监控对象, 跟卖者, 发现时刻) 的记录靠 UNIQUE 约束保证只有一行，
 *       冲突时**跳过并逐条写明原因**（不静默丢弃）。
 *   <li><b>🔴 未交付的下游显式声明</b> —— 16 合规品牌保护、1 工作台预警、02 抓取调度器、
 *       05 定价（自家权威售价）都没交付，{@link #capabilities()} 里 available=false +
 *       ownerModule + impact，前端显示「未接入」，<b>不显示假数据</b>。
 * </ol>
 *
 * <p>🔴 时间口径：发现时间是 {@code timestamptz}，用 {@code OffsetDateTime} 绑定、
 * values 位置写 {@code ?::timestamptz} —— 用 {@code Timestamp.valueOf(LocalDateTime...)}
 * 会静默偏 −28800s（AGENTS.md 与 4.1.1.1 实测踩过）。筛选区间按 <b>UTC 自然日</b> 归集
 * （半开区间 {@code [from, to+1天)}），所以「10 月 1 日」包含 10 月 1 日 UTC 全天。
 */
@Service
public class ListingHijackerService {

  /** 🔴 对比视图的**服务端聚合**（GROUP BY 跟卖者 + 现算价格差）。前端不拼、不自己算。 */
  private static final String COMPARISON_AGG =
      """
      select r.hijacker_seller_id as hijacker_seller_id,
             max(r.hijacker_shop_name) as hijacker_shop_name,
             max(r.hijacker_shop_identifier) as hijacker_shop_identifier,
             (bool_or(r.hijacker_shop_id is not null)) as own_shop,
             p.code as platform_code,
             count(*) as observation_count,
             (array_agg(r.hijacker_price order by r.observed_at desc, r.id desc))[1] as latest_price,
             min(r.hijacker_price) as min_price,
             max(r.hijacker_price) as max_price,
             round(avg(r.hijacker_price), 4) as avg_price,
             sum(r.hijacker_price) as price_sum,
             max(r.observed_at) as last_observed_at,
             max(w.reference_price) as reference_price,
             max(w.currency) as currency,
             case when max(w.reference_price) is not null
                  then max(w.reference_price)
                       - (array_agg(r.hijacker_price order by r.observed_at desc, r.id desc))[1]
                  else null end as price_gap,
             case when max(w.reference_price) is not null and max(w.reference_price) <> 0
                  then round(
                    (max(w.reference_price)
                     - (array_agg(r.hijacker_price order by r.observed_at desc, r.id desc))[1])
                    / max(w.reference_price), 6)
                  else null end as price_gap_ratio
      from listing_hijacker_record r
      join listing_hijacker_watch w on w.tenant_id=r.tenant_id and w.id=r.watch_id
      join listing l on l.tenant_id=r.tenant_id and l.id=r.listing_id
      join shop sh on sh.tenant_id=r.tenant_id and sh.id=l.shop_id
      join platform p on p.id=sh.platform_id
      %s
      group by r.hijacker_seller_id, p.code
      """;

  private final DSLContext sql;
  private final ObjectMapper json;

  public ListingHijackerService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ==================================================================
  // 小工具（照抄 ListingPerformanceService 的同名写法）
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

  /** 百分比展示（比例 0.15 ⇒ "15.00%"）。 */
  private static String percent(BigDecimal ratio) {
    if (ratio == null) {
      return "—";
    }
    return ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString()
        + "%";
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

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 🔴 逐维度能力声明。
   *
   * <p>本模块在本仓能**真实交付**的是「监控对象的维护 + 跟卖信息的接收与入库 +
   * 异常跟卖 / 恶意压价的判定 + 预警记录 + 价格对比视图 + 全程标注「仅供参考」。
   *
   * <p>它<b>不能</b>在本批真实交付的有四段，逐条在下面声明而不是假装具备：
   * 16 合规品牌保护的处置动作、1 工作台预警的通知、02 的抓取调度器、05 的平台权威售价。
   */
  public ListingHijackerCapabilities capabilities() {
    return new ListingHijackerCapabilities(
        List.of(
            new ListingHijackerCapabilityDimension(
                "hijack_watch_management",
                "🔴 监控对象维护：按商品 / ASIN 配置要监控的跟卖，维护自家参考售价、"
                    + "恶意压价阈值、异常跟卖阈值与启停状态（租户内可维护）",
                true, "listing_hijacker_watch（V206）", "",
                "一个 listing 一个监控对象（UNIQUE(tenant_id, listing_id)）—— "
                    + "「同一个 ASIN 被配了两套阈值」是配置错误，不允许出现"),
            new ListingHijackerCapabilityDimension(
                "hijack_record_ingest",
                "🔴 接收抓取 / 平台接口返回的跟卖信息并入库：跟卖者、跟卖者店铺、"
                    + "跟卖价格、所在平台（派生）、发现时间",
                true, "listing_hijacker_record（V206 新表）", "",
                "批次级幂等键让「平台重投同一批」与「同一条跟卖被重复观察」变成两件可分辨的事："
                    + "前者整批跳过，后者按 (监控对象, 跟卖者, 发现时刻) 跳过并写明原因"),
            new ListingHijackerCapabilityDimension(
                "own_shop_detection",
                "🔴 识别**己方其他店铺**跟自家 listing：按跟卖者的平台侧店铺标识"
                    + "匹配本租户自有店铺，命中即 OWN_SHOP",
                true, "listing_hijacker_record.hijacker_shop_id + V1 shop.platform_shop_id", "",
                "这个性质是**算出来的**，调用方不能直接声明 —— "
                    + "否则一个抓取适配器报错了类型，整条数据的性质就错了"),
            new ListingHijackerCapabilityDimension(
                "anomaly_detection",
                "🔴 异常跟卖 / 恶意压价判定：跟卖价 ≤ 恶意压价阈值 ⇒ 恶意压价；"
                    + "跟卖价比自家参考价低出配置比例 ⇒ 异常跟卖；跟卖者是己方店铺 ⇒ 异常跟卖",
                true, "listing_hijacker_alert（V206 新表） + 服务层判定", "",
                "阈值是**配置**（运营自己定的门槛），判定结果**不落比率** —— "
                    + "价格差比例一律现算，分母为 0 时是 null"),
            new ListingHijackerCapabilityDimension(
                "alert_record",
                "🔴 预警记录：一条跟卖记录最多一条预警，带类型 / 严重度 / 判定依据 / 触发时刻的价格快照",
                true, "listing_hijacker_alert（V206 新表）", "",
                "🔴 **这是记录，不是通知** —— 本模块不主动发通知（1 工作台预警未交付），"
                    + "status=ACKNOWLEDGED 只是内部「已阅」，**不代表已在平台侧投诉或举报**"),
            new ListingHijackerCapabilityDimension(
                "price_comparison_view",
                "🔴 对比视图：自家 listing 的参考售价 × 每个跟卖者的最新 / 最低 / 最高 / 平均价，"
                    + "服务端聚合 + 真分页",
                true, "listing_hijacker_record + 服务层现算的价格差比例", "",
                "一个跟卖者一行（GROUP BY 跟卖者），**前端不拼表、不自己算比例**"),
            new ListingHijackerCapabilityDimension(
                "reference_only_labeling",
                "🔴 全程标注「仅供参考、不构成处置结论」：每个响应都带 "
                    + "dataSource='PLATFORM_SCRAPED' 与 sourceNote / actionsNote",
                true, "服务层常量 DATA_SOURCE / SOURCE_NOTE / ACTIONS_NOTE", "",
                "蓝图加粗句「跟卖数据只能作为参考」因此是**契约级**的硬要求，"
                    + "不是页面上的装饰 —— 抓取会滞后、会漏抓、也会抓到已下架的报价"),
            new ListingHijackerCapabilityDimension(
                "brand_protection_enforcement",
                "🔴 **16 合规（品牌保护）**：投诉 / 举报 / 申诉等处置动作",
                false, "—", "16 合规与风险（整卷未交付）",
                "本模块**只负责发现与提醒**。处置动作在**平台侧**完成 —— "
                    + "系统不提供、也不代为发起这些动作；在平台侧做完之后，"
                    + "由 16 侧来消费本模块的预警记录并跟进"),
            new ListingHijackerCapabilityDimension(
                "workbench_alert_notification",
                "🔴 **1 工作台预警**：把预警推送到工作台的通知中心",
                false, "—", "01 工作台与门户（整卷未交付）",
                "本模块产出的是**预警记录**，**不主动发通知** —— "
                    + "工作台的通知投递由 1 侧交付后由它来读；"
                    + "在这里伪造一个「已通知」字段比不通知更危险"),
            new ListingHijackerCapabilityDimension(
                "real_hijack_crawl_scheduler",
                "🔴 真实抓取跟卖信息的**调度器**（定时去平台拉跟卖报价的那个任务）",
                false, "—", "02 渠道与店铺（同步运行时）",
                "本模块提供**入库端点**（通道 WEBHOOK / POLL / API 都记下来），"
                    + "但**没有**那个定时调度器 —— 平台限流与店铺配额由 02 统一裁决，本模块不自建轮询"),
            new ListingHijackerCapabilityDimension(
                "authoritative_own_price",
                "🔴 **05 定价**：自家 listing 的平台权威售价（用来跟跟卖价做对比）",
                false, "—", "05 定价与价目（整卷未交付）",
                "系统内**不存在**任何平台售价事实表，所以对比视图里的「自家参考售价」是"
                    + "**租户人工维护的参考值**，不是平台权威价；05 交付后由它来取代或与之对账，"
                    + "在那之前本模块**不伪造**第二个价格事实来源")),
        ALERT_TYPES,
        SEVERITIES,
        SOURCES,
        INGEST_CHANNELS,
        WATCH_STATUSES,
        HIJACKER_KINDS,
        ALERT_STATUSES,
        "判定口径（服务端算，前端原样显示）：\n"
            + "· 恶意压价 MALICIOUS_PRICE_CUT：跟卖价 ≤ 监控对象配置的恶意压价阈值"
            + "（阈值不给 ⇒ **不判定**这一条，不猜一个默认门槛）。\n"
            + "· 异常跟卖 ANOMALOUS_HIJACK：跟卖价比自家参考售价低出的比例 > 配置阈值"
            + "（比例阈值不给 ⇒ **不判定**这一条）；跟卖者命中本租户自有店铺时"
            + "**一律**判为异常跟卖（自家店铺跟自家 listing 抢生意是渠道冲突）。\n"
            + "· 两条同时命中时，类型取更严重的**恶意压价**，判定依据里写明全部命中的规则。\n"
            + "· 🔴 **价格差比例一律现算**（价格差 ÷ 自家参考售价），**分母为 0 时是 null**"
            + "（不是 0、也不是 100）。前端原样显示「—」，不要自己重算。\n"
            + "· 自家参考售价是**租户人工维护的参考值**（05 定价未交付，系统内没有平台权威售价）。",
        DATA_SOURCE,
        SOURCE_NOTE,
        ACTIONS_NOTE,
        "上游 = 抓取任务（真实抓取调度器归 02，本模块只提供入库端点）· "
            + "下游 = 16 合规（品牌保护，整卷未交付）、1 工作台预警（整卷未交付）—— "
            + "两段都在本能力表里 available=false + ownerModule，页面上显示「未接入」。"
            + "本模块**不提供任何投诉 / 举报 / 申诉动作**：处置在平台侧完成。");
  }

  // ==================================================================
  // 筛选项词表
  // ==================================================================

  /** 筛选词表：平台、店铺与各枚举取值域。 */
  public ListingHijackerFilterOptions filterOptions() {
    UUID tenant = currentTenant();
    List<ListingHijackerPlatformOption> platforms =
        sql.fetch("select code,name from platform order by position, code").stream()
            .map(r -> new ListingHijackerPlatformOption(r.get("code", String.class), r.get("name", String.class)))
            .toList();
    List<ListingHijackerShopOption> shops =
        sql.fetch(
                "select s.id,s.code,s.name,p.code as platform_code from shop s"
                    + " join platform p on p.id=s.platform_id where s.tenant_id=? order by s.code",
                tenant)
            .stream()
            .map(
                r ->
                    new ListingHijackerShopOption(
                        r.get("id", UUID.class),
                        r.get("code", String.class),
                        r.get("name", String.class),
                        r.get("platform_code", String.class)))
            .toList();
    return new ListingHijackerFilterOptions(
        platforms,
        shops,
        ALERT_TYPES,
        SEVERITIES,
        SOURCES,
        WATCH_STATUSES,
        HIJACKER_KINDS,
        ALERT_STATUSES);
  }

  // ==================================================================
  // 监控对象
  // ==================================================================

  /** 监控对象列表（真分页 + 筛选）。 */
  public ListingHijackerWatchPage watches(
      String status, String platformCode, String shopCode, String keyword, int page, int size) {
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
    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and sh.code=?");
      binds.add(shop);
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (l.external_item_id ilike ? or l.title ilike ?)");
      String pattern = likePattern(word);
      binds.add(pattern);
      binds.add(pattern);
    }

    String from =
        """
        select w.id,w.listing_id,l.external_item_id,l.title,sh.code as shop_code,p.code as platform_code,
               w.reference_price,w.currency,w.price_cut_threshold,w.price_gap_ratio_threshold,
               w.status,w.note,w.created_at,
               (select count(*) from listing_hijacker_record r
                 where r.tenant_id=w.tenant_id and r.watch_id=w.id) as record_count,
               (select count(*) from listing_hijacker_alert a
                 where a.tenant_id=w.tenant_id and a.watch_id=w.id and a.status='OPEN') as open_alert_count,
               (select max(r.observed_at) from listing_hijacker_record r
                 where r.tenant_id=w.tenant_id and r.watch_id=w.id) as last_observed_at
        from listing_hijacker_watch w
        join listing l on l.tenant_id=w.tenant_id and l.id=w.listing_id
        join shop sh on sh.tenant_id=w.tenant_id and sh.id=l.shop_id
        join platform p on p.id=sh.platform_id
        """
            + where;

    long total = count(from, binds.toArray());
    int lim = clampSize(size);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(lim);
    pageBinds.add(Math.max(0, page) * lim);
    List<ListingHijackerWatchRow> items =
        sql.fetch(
                from + " order by w.created_at desc, w.id desc limit ? offset ?", pageBinds.toArray())
            .stream()
            .map(this::watchRow)
            .toList();

    return new ListingHijackerWatchPage(
        items, Math.max(0, page), lim, total, DATA_SOURCE, SOURCE_NOTE);
  }

  private ListingHijackerWatchRow watchRow(Record r) {
    return new ListingHijackerWatchRow(
        r.get("id", UUID.class),
        r.get("listing_id", UUID.class),
        nullToEmpty(r.get("external_item_id", String.class)),
        nullToEmpty(r.get("title", String.class)),
        nullToEmpty(r.get("shop_code", String.class)),
        nullToEmpty(r.get("platform_code", String.class)),
        r.get("reference_price", BigDecimal.class),
        nullToEmpty(r.get("currency", String.class)),
        r.get("price_cut_threshold", BigDecimal.class),
        r.get("price_gap_ratio_threshold", BigDecimal.class),
        nullToEmpty(r.get("status", String.class)),
        nullToEmpty(r.get("note", String.class)),
        longOf(r, "record_count"),
        longOf(r, "open_alert_count"),
        r.get("last_observed_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /** 新建监控对象。 */
  public ListingHijackerWatchRow createWatch(SaveHijackerWatchRequest request) {
    UUID tenant = currentTenant();
    Record listing = resolveListing(tenant, request);
    if (listing == null) {
      throw unprocessable(
          "定位不到商品：请给 listingId，或给 (店铺编码 shopCode + 平台商品 ID externalItemId)。"
              + "监控的是哪个 ASIN 说不知道的话，这条监控就没有意义");
    }
    UUID listingId = listing.get("id", UUID.class);

    BigDecimal referencePrice = request.referencePrice();
    if (referencePrice != null && referencePrice.signum() < 0) {
      throw unprocessable("自家参考售价不能是负数");
    }
    BigDecimal cut = request.priceCutThreshold();
    if (cut != null && cut.signum() < 0) {
      throw unprocessable("恶意压价阈值不能是负数");
    }
    BigDecimal gap = request.priceGapRatioThreshold();
    if (gap != null && (gap.signum() < 0 || gap.compareTo(BigDecimal.ONE) >= 0)) {
      throw unprocessable("异常跟卖的价格差比例阈值必须落在 [0, 1) 之间（收到 " + gap + "）");
    }
    String currency = nullToEmpty(text(request.currency())).toUpperCase(Locale.ROOT);
    if (referencePrice != null && currency.isEmpty()) {
      throw unprocessable("给了自家参考售价就必须给币种（currency）—— 没有币种的价格没法跟跟卖价比");
    }
    String status = blankToNull(request.status());
    if (status == null) {
      status = "ACTIVE";
    } else {
      status = status.toUpperCase(Locale.ROOT);
      if (!WATCH_STATUSES.contains(status)) {
        throw unprocessable(
            "未知的监控对象状态「" + request.status() + "」。只认这两条：" + String.join(" / ", WATCH_STATUSES));
      }
    }

    // 一个 listing 一个监控对象 —— 重复配置要在服务层就说清楚，而不是让 DB 抛 23505。
    if (sql.fetchOne(
            "select 1 from listing_hijacker_watch where tenant_id=? and listing_id=?",
            tenant,
            listingId)
        != null) {
      throw conflict("这个商品已经有监控对象了 —— 同一个 ASIN 配两套阈值是配置错误，请改现有那条");
    }

    UUID id = Ids.next();
    sql.execute(
        """
        insert into listing_hijacker_watch
          (id,tenant_id,listing_id,external_item_id,reference_price,currency,
           price_cut_threshold,price_gap_ratio_threshold,status,note,created_at,updated_at)
        values (?,?,?,?,?,?,?,?,?,?,now(),now())
        """,
        id,
        tenant,
        listingId,
        listing.get("external_item_id", String.class),
        referencePrice,
        currency,
        cut,
        gap,
        status,
        note(request.note()));
    return loadWatch(tenant, id);
  }

  /**
   * 更新监控对象（<b>PUT = 全量替换</b>：字段不给 = 清空 / 复位）。
   *
   * <p>🔴 被监控的商品**不可改**（改了会让历史跟卖记录挂到别的 ASIN 上）——
   * 给了不同的 listingId 直接 422。
   */
  public ListingHijackerWatchRow updateWatch(String watchId, SaveHijackerWatchRequest request) {
    UUID tenant = currentTenant();
    UUID id = parseId(watchId);
    Record current = loadWatchRecord(tenant, id);
    if (current == null) {
      throw notFound();
    }

    if (request.listingId() != null && !request.listingId().equals(current.get("listing_id", UUID.class))) {
      throw unprocessable(
          "不能改监控对象指向的商品 —— 历史跟卖记录是挂在原来那个 ASIN 上的。"
              + "要换监控目标请新建一条监控对象");
    }

    BigDecimal referencePrice = request.referencePrice();
    if (referencePrice != null && referencePrice.signum() < 0) {
      throw unprocessable("自家参考售价不能是负数");
    }
    BigDecimal cut = request.priceCutThreshold();
    if (cut != null && cut.signum() < 0) {
      throw unprocessable("恶意压价阈值不能是负数");
    }
    BigDecimal gap = request.priceGapRatioThreshold();
    if (gap != null && (gap.signum() < 0 || gap.compareTo(BigDecimal.ONE) >= 0)) {
      throw unprocessable("异常跟卖的价格差比例阈值必须落在 [0, 1) 之间（收到 " + gap + "）");
    }
    String currency = nullToEmpty(text(request.currency())).toUpperCase(Locale.ROOT);
    if (referencePrice != null && currency.isEmpty()) {
      throw unprocessable("给了自家参考售价就必须给币种（currency）—— 没有币种的价格没法跟跟卖价比");
    }
    String status = blankToNull(request.status());
    if (status == null) {
      status = "ACTIVE";
    } else {
      status = status.toUpperCase(Locale.ROOT);
      if (!WATCH_STATUSES.contains(status)) {
        throw unprocessable(
            "未知的监控对象状态「" + request.status() + "」。只认这两条：" + String.join(" / ", WATCH_STATUSES));
      }
    }

    sql.execute(
        """
        update listing_hijacker_watch
           set reference_price=?, currency=?, price_cut_threshold=?, price_gap_ratio_threshold=?,
               status=?, note=?, updated_at=now()
         where tenant_id=? and id=?
        """,
        referencePrice,
        currency,
        cut,
        gap,
        status,
        note(request.note()),
        tenant,
        id);
    return loadWatch(tenant, id);
  }

  /** 删除监控对象（连同它的跟卖记录与预警 —— 删之前先告诉调用方会删掉多少）。 */
  public ListingHijackerWatchDeleteResult deleteWatch(String watchId) {
    UUID tenant = currentTenant();
    UUID id = parseId(watchId);
    if (loadWatchRecord(tenant, id) == null) {
      throw notFound();
    }
    long records = countOf(
        "select count(*) from listing_hijacker_record where tenant_id=? and watch_id=?", tenant, id);
    long alerts = countOf(
        "select count(*) from listing_hijacker_alert where tenant_id=? and watch_id=?", tenant, id);
    sql.execute("delete from listing_hijacker_watch where tenant_id=? and id=?", tenant, id);
    return new ListingHijackerWatchDeleteResult(id, records, alerts);
  }

  private Record loadWatchRecord(UUID tenant, UUID id) {
    return sql.fetchOne(
        """
        select w.id,w.listing_id,w.external_item_id,w.reference_price,w.currency,
               w.price_cut_threshold,w.price_gap_ratio_threshold,w.status,w.note,w.created_at,
               l.title, sh.code as shop_code, p.code as platform_code, l.shop_id
        from listing_hijacker_watch w
        join listing l on l.tenant_id=w.tenant_id and l.id=w.listing_id
        join shop sh on sh.tenant_id=w.tenant_id and sh.id=l.shop_id
        join platform p on p.id=sh.platform_id
        where w.tenant_id=? and w.id=?
        """,
        tenant,
        id);
  }

  private ListingHijackerWatchRow loadWatch(UUID tenant, UUID id) {
    Record r = loadWatchRecord(tenant, id);
    if (r == null) {
      throw notFound();
    }
    return watchRow(
        sql.fetchOne(
            """
            select w.id,w.listing_id,l.external_item_id,l.title,sh.code as shop_code,p.code as platform_code,
                   w.reference_price,w.currency,w.price_cut_threshold,w.price_gap_ratio_threshold,
                   w.status,w.note,w.created_at,
                   (select count(*) from listing_hijacker_record r2
                     where r2.tenant_id=w.tenant_id and r2.watch_id=w.id) as record_count,
                   (select count(*) from listing_hijacker_alert a
                     where a.tenant_id=w.tenant_id and a.watch_id=w.id and a.status='OPEN') as open_alert_count,
                   (select max(r3.observed_at) from listing_hijacker_record r3
                     where r3.tenant_id=w.tenant_id and r3.watch_id=w.id) as last_observed_at
            from listing_hijacker_watch w
            join listing l on l.tenant_id=w.tenant_id and l.id=w.listing_id
            join shop sh on sh.tenant_id=w.tenant_id and sh.id=l.shop_id
            join platform p on p.id=sh.platform_id
            where w.tenant_id=? and w.id=?
            """,
            tenant,
            id));
  }

  /** 定位商品：给 listingId 就用它；否则按 (店铺编码, 平台商品 ID) 定位。 */
  private Record resolveListing(UUID tenant, SaveHijackerWatchRequest request) {
    if (request.listingId() != null) {
      return sql.fetchOne(
          "select l.id,l.external_item_id,l.shop_id from listing l where l.tenant_id=? and l.id=?",
          tenant,
          request.listingId());
    }
    String shopCode = blankToNull(request.shopCode());
    String externalItemId = blankToNull(request.externalItemId());
    if (shopCode == null || externalItemId == null) {
      throw unprocessable("请给 listingId，或给 (店铺编码 shopCode + 平台商品 ID externalItemId)");
    }
    return sql.fetchOne(
        """
        select l.id,l.external_item_id,l.shop_id
        from listing l
        join shop sh on sh.tenant_id=l.tenant_id and sh.id=l.shop_id
        where l.tenant_id=? and l.external_item_id=? and sh.code=?
        """,
        tenant,
        externalItemId,
        shopCode);
  }

  private static String note(String raw) {
    String v = text(raw);
    return v.length() > 500 ? v.substring(0, 500) : v;
  }

  // ==================================================================
  // 🔴 回流入库
  // ==================================================================

  /**
   * 🔴🔴 接收一次抓取 / 平台接口返回的跟卖信息。
   *
   * <p>五件事，逐条都有理由：
   *
   * <ol>
   *   <li><b>定位监控对象</b>：定位不到 ⇒ 404；监控对象已暂停 ⇒ 422（暂停就是"不收"，含糊地收下会让人以为还在盯）。
   *   <li><b>幂等</b>：同一个批次幂等键再次投递 ⇒ <b>整批不再落库</b>，直接返回 {@code replayed=true}。
   *   <li><b>校验</b>：缺卖家标识 / 缺店铺标识 / 负价格 ⇒ 整批拒绝（422），<b>不落半批</b>。
   *   <li><b>落库</b>：跟卖者是被监控 listing 自己所在店铺 ⇒ <b>跳过并写明原因</b>（那不是跟卖）；
   *       同 (监控对象, 跟卖者, 发现时刻) 已有记录 ⇒ 跳过并写明原因。
   *   <li><b>判定</b>：落进去的每一条都跑一遍判定规则，命中就落一条预警。
   * </ol>
   */
  public ListingHijackerIngestResult ingest(IngestHijackerRecordsRequest request) {
    UUID tenant = currentTenant();

    String source = text(request.source()).toUpperCase(Locale.ROOT);
    if (source.isEmpty()) {
      source = "PLATFORM";
    }
    if (!SOURCES.contains(source)) {
      throw unprocessable(
          "未知的来源「" + text(request.source()) + "」。只认这三条：" + String.join(" / ", SOURCES));
    }
    String channel = text(request.ingestChannel()).toUpperCase(Locale.ROOT);
    if (channel.isEmpty()) {
      channel = "API";
    }
    if (!INGEST_CHANNELS.contains(channel)) {
      throw unprocessable(
          "未知的通道「" + text(request.ingestChannel()) + "」。只认这三条："
              + String.join(" / ", INGEST_CHANNELS));
    }
    String key = text(request.idempotencyKey());
    if (key.isEmpty()) {
      throw unprocessable(
          "必须给出幂等键（idempotencyKey）—— 平台重投同一批时靠它整批跳过。"
              + "没有它就分不清「重投」与「新一轮抓取」");
    }
    if (request.observedAt() == null) {
      throw unprocessable("必须给出发现时间（observedAt）—— 发现时间就是这条跟卖事实的一部分");
    }

    Record watch = loadWatchRecord(tenant, request.watchId());
    if (watch == null) {
      throw notFound();
    }
    if (!"ACTIVE".equals(watch.get("status", String.class))) {
      throw unprocessable(
          "监控对象已暂停（PAUSED），不接收新的跟卖信息。要恢复请把状态改回 ACTIVE —— "
              + "暂停只停「接收与判定」，已有记录与预警都还在");
    }

    // ---- 🔴 幂等：同一个批次再次投递 ⇒ 整批不再落库
    Long already =
        (Long)
            sql.fetchValue(
                "select count(*) from listing_hijacker_record"
                    + " where tenant_id=? and watch_id=? and observed_at=?::timestamptz"
                    + " and batch_idempotency_key=?",
                tenant,
                request.watchId(),
                Timestamp.from(request.observedAt().toInstant()),
                key);
    if (already != null && already > 0) {
      return new ListingHijackerIngestResult(
          request.watchId(),
          request.observedAt(),
          "REPLAYED",
          request.records().size(),
          0,
          request.records().size(),
          true,
          key,
          List.of(),
          List.of(),
          DATA_SOURCE,
          SOURCE_NOTE);
    }

    // ---- 🔴 先把整批校验完（不落半批）
    for (HijackerRecordInput input : request.records()) {
      if (text(input.hijackerSellerId()).isEmpty()) {
        throw unprocessable("每一条跟卖信息都必须给出跟卖者（hijackerSellerId）");
      }
      if (text(input.hijackerShopIdentifier()).isEmpty()) {
        throw unprocessable(
            "每一条跟卖信息都必须给出跟卖者店铺的平台侧标识（hijackerShopIdentifier）—— "
                + "它是我们判断「这是不是我们自己人」的唯一依据");
      }
      if (input.hijackerPrice() == null || input.hijackerPrice().signum() < 0) {
        throw unprocessable(
            "跟卖价不能是负数（跟卖者 " + text(input.hijackerSellerId()) + " 收到 "
                + input.hijackerPrice() + "）");
      }
    }

    // ---- 自有店铺：按**平台侧店铺标识**匹配（不是按店铺编码，编码是本系统的内部命名）
    Map<String, UUID> ownShops = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select id,platform_shop_id from shop where tenant_id=? and platform_shop_id <> ''",
            tenant)) {
      ownShops.put(r.get("platform_shop_id", String.class), r.get("id", UUID.class));
    }
    UUID watchedShopId = watch.get("shop_id", UUID.class);

    BigDecimal referencePrice = watch.get("reference_price", BigDecimal.class);
    BigDecimal cutThreshold = watch.get("price_cut_threshold", BigDecimal.class);
    BigDecimal gapThreshold = watch.get("price_gap_ratio_threshold", BigDecimal.class);
    String watchCurrency = nullToEmpty(watch.get("currency", String.class));

    int applied = 0;
    List<String> skipped = new ArrayList<>();
    List<ListingHijackerAlertRow> alerts = new ArrayList<>();

    for (HijackerRecordInput input : request.records()) {
      String sellerId = text(input.hijackerSellerId());
      String shopIdentifier = text(input.hijackerShopIdentifier());
      UUID ownShop = ownShops.get(shopIdentifier);

      // 🔴 跟卖者就是被监控 listing 自己所在的店铺 ⇒ 那不是跟卖
      if (ownShop != null && ownShop.equals(watchedShopId)) {
        skipped.add(
            sellerId
                + "：这是被监控 listing 自己所在店铺（"
                + watch.get("shop_code", String.class)
                + "），不是跟卖");
        continue;
      }

      UUID recordId = Ids.next();
      int rows =
          sql.execute(
              """
              insert into listing_hijacker_record
                (id,tenant_id,watch_id,listing_id,hijacker_seller_id,hijacker_shop_identifier,
                 hijacker_shop_name,hijacker_shop_id,hijacker_price,currency,observed_at,source,
                 ingest_channel,batch_idempotency_key,detail,received_at,created_at,updated_at)
              values (?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?::jsonb,now(),now(),now())
              on conflict (tenant_id, watch_id, hijacker_seller_id, observed_at) do nothing
              """,
              recordId,
              tenant,
              request.watchId(),
              watch.get("listing_id", UUID.class),
              sellerId,
              shopIdentifier,
              text(input.hijackerShopName()),
              ownShop,
              input.hijackerPrice(),
              blankToNull(input.currency()) == null ? watchCurrency : blankToNull(input.currency()),
              Timestamp.from(request.observedAt().toInstant()),
              source,
              channel,
              key,
              writeJson(input.detail() == null ? Map.of() : input.detail()));
      if (rows == 0) {
        skipped.add(
            sellerId + "：该发现时刻已有同一跟卖者的记录（本批未重复落库）");
        continue;
      }
      applied++;

      // ---- 🔴 判定：异常跟卖 / 恶意压价
      ListingHijackerAlertRow alert =
          evaluate(
              tenant,
              request.watchId(),
              watch.get("listing_id", UUID.class),
              recordId,
              sellerId,
              text(input.hijackerShopName()),
              ownShop != null ? "OWN_SHOP" : "EXTERNAL",
              input.hijackerPrice(),
              blankToNull(input.currency()) == null ? watchCurrency : blankToNull(input.currency()),
              referencePrice,
              cutThreshold,
              gapThreshold,
              request.observedAt());
      if (alert != null) {
        alerts.add(alert);
      }
    }

    String status = skipped.isEmpty() ? "APPLIED" : "PARTIAL";
    return new ListingHijackerIngestResult(
        request.watchId(),
        request.observedAt(),
        status,
        request.records().size(),
        applied,
        skipped.size(),
        false,
        key,
        skipped,
        alerts,
        DATA_SOURCE,
        SOURCE_NOTE);
  }

  /**
   * 🔴 判定规则：异常跟卖 / 恶意压价。
   *
   * <p>三条规则，逐条写明（前端展示的「判定依据」就是这里拼出来的字符串）：
   *
   * <ul>
   *   <li><b>恶意压价</b>：跟卖价 ≤ 恶意压价阈值（阈值不给 ⇒ <b>不判定</b>，不猜默认门槛）。
   *   <li><b>异常跟卖（价格差）</b>：跟卖价比自家参考价低出的比例 > 比例阈值（阈值不给 ⇒ <b>不判定</b>）。
   *   <li><b>异常跟卖（自家店铺）</b>：跟卖者命中本租户自有店铺 ⇒ <b>一律</b>判为异常跟卖。
   * </ul>
   */
  private ListingHijackerAlertRow evaluate(
      UUID tenant,
      UUID watchId,
      UUID listingId,
      UUID recordId,
      String sellerId,
      String shopName,
      String hijackerKind,
      BigDecimal hijackerPrice,
      String currency,
      BigDecimal referencePrice,
      BigDecimal cutThreshold,
      BigDecimal gapThreshold,
      OffsetDateTime observedAt) {

    boolean malicious = cutThreshold != null && hijackerPrice.compareTo(cutThreshold) <= 0;
    // 🔴 价格差比例：现算。分母为 0 / 没有参考价 ⇒ null（不是 0、不是 100）。
    BigDecimal gapRatio =
        ratio(
            referencePrice == null ? null : referencePrice.subtract(hijackerPrice),
            referencePrice);
    boolean anomalousByPrice = gapThreshold != null && gapRatio != null && gapRatio.compareTo(gapThreshold) > 0;
    boolean ownShopConflict = "OWN_SHOP".equals(hijackerKind);
    boolean anomalous = anomalousByPrice || ownShopConflict;

    if (!malicious && !anomalous) {
      return null;
    }

    String type = malicious ? "MALICIOUS_PRICE_CUT" : "ANOMALOUS_HIJACK";
    String severity = (malicious || ownShopConflict) ? "HIGH" : "MEDIUM";

    List<String> reasons = new ArrayList<>();
    if (malicious) {
      reasons.add(
          "跟卖价 "
              + hijackerPrice.toPlainString()
              + " ≤ 恶意压价阈值 "
              + cutThreshold.toPlainString());
    }
    if (anomalousByPrice) {
      reasons.add(
          "跟卖价 "
              + hijackerPrice.toPlainString()
              + " 比自家参考价 "
              + referencePrice.toPlainString()
              + " 低 "
              + percent(gapRatio)
              + "（阈值 "
              + percent(gapThreshold)
              + "）");
    }
    if (ownShopConflict) {
      reasons.add("跟卖者是**己方其他店铺**（" + shopName + "），与自家 listing 存在渠道冲突");
    }

    UUID alertId = Ids.next();
    sql.execute(
        """
        insert into listing_hijacker_alert
          (id,tenant_id,watch_id,record_id,listing_id,alert_type,severity,observed_price,currency,
           reference_price,status,reason,detail,detected_at,created_at,updated_at)
        values (?,?,?,?,?,?,?,?,?,?,'OPEN',?,?::jsonb,now(),now(),now())
        on conflict (tenant_id, record_id) do nothing
        """,
        alertId,
        tenant,
        watchId,
        recordId,
        listingId,
        type,
        severity,
        hijackerPrice,
        currency,
        referencePrice,
        String.join("；", reasons),
        writeJson(Map.of("hijackerKind", hijackerKind, "sellerId", sellerId)));

    return loadAlertByRecord(tenant, recordId);
  }

  // ==================================================================
  // 跟卖记录列表
  // ==================================================================

  /** 跟卖记录列表（真分页 + 筛选）。 */
  public ListingHijackerRecordPage records(
      String watchId,
      String listingId,
      String sellerId,
      String hijackerKind,
      String platformCode,
      String from,
      String to,
      int page,
      int size) {
    UUID tenant = currentTenant();
    StringBuilder where = new StringBuilder(" where r.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);

    String watch = blankToNull(watchId);
    if (watch != null) {
      where.append(" and r.watch_id=?");
      binds.add(parseId(watch));
    }
    String listing = blankToNull(listingId);
    if (listing != null) {
      where.append(" and r.listing_id=?");
      binds.add(parseId(listing));
    }
    String seller = blankToNull(sellerId);
    if (seller != null) {
      where.append(" and r.hijacker_seller_id=?");
      binds.add(seller);
    }
    String kind = blankToNull(hijackerKind);
    if (kind != null) {
      String upper = kind.toUpperCase(Locale.ROOT);
      if (!HIJACKER_KINDS.contains(upper)) {
        throw badRequest(
            "未知的跟卖者性质「" + hijackerKind + "」。只认这两条：" + String.join(" / ", HIJACKER_KINDS));
      }
      where.append(
          upper.equals("OWN_SHOP")
              ? " and r.hijacker_shop_id is not null"
              : " and r.hijacker_shop_id is null");
    }
    String platform = blankToNull(platformCode);
    if (platform != null) {
      where.append(" and p.code=?");
      binds.add(platform);
    }
    LocalDate fromDate = parseDate(from, "起始日期");
    if (fromDate != null) {
      where.append(" and r.observed_at >= ?::timestamptz");
      binds.add(utcStart(fromDate));
    }
    LocalDate toDate = parseDate(to, "结束日期");
    if (toDate != null) {
      where.append(" and r.observed_at < ?::timestamptz");
      binds.add(utcDayAfter(toDate));
    }

    String whereSql = where.toString();
    String fromSql =
        """
        select r.id,r.watch_id,r.listing_id,l.external_item_id,l.title,sh.code as shop_code,
               p.code as platform_code,r.hijacker_seller_id,r.hijacker_shop_name,
               r.hijacker_shop_identifier,r.hijacker_shop_id,r.hijacker_price,r.currency,
               r.observed_at,r.source,r.ingest_channel,r.batch_idempotency_key,r.received_at,
               w.reference_price,
               case when w.reference_price is not null
                    then w.reference_price - r.hijacker_price else null end as price_gap,
               case when w.reference_price is not null and w.reference_price <> 0
                    then round((w.reference_price - r.hijacker_price) / w.reference_price, 6)
                    else null end as price_gap_ratio
        from listing_hijacker_record r
        join listing_hijacker_watch w on w.tenant_id=r.tenant_id and w.id=r.watch_id
        join listing l on l.tenant_id=r.tenant_id and l.id=r.listing_id
        join shop sh on sh.tenant_id=r.tenant_id and sh.id=l.shop_id
        join platform p on p.id=sh.platform_id
        """
            + whereSql;

    long total = count(fromSql, binds.toArray());
    int lim = clampSize(size);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(lim);
    pageBinds.add(Math.max(0, page) * lim);
    List<ListingHijackerRecordRow> items =
        sql.fetch(
                fromSql + " order by r.observed_at desc, r.id desc limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(this::recordRow)
            .toList();

    // 🔴 预警条数：与本列表**同一个筛选口径**（同 r / p 别名，同 binds）——
    //    否则「本筛选范围内有 3 条预警」这句话就是假的。
    long alertCount =
        count(
            """
            select a.id
            from listing_hijacker_alert a
            join listing_hijacker_record r on r.tenant_id=a.tenant_id and r.id=a.record_id
            join listing l on l.tenant_id=r.tenant_id and l.id=r.listing_id
            join shop sh on sh.tenant_id=r.tenant_id and sh.id=l.shop_id
            join platform p on p.id=sh.platform_id
            """
                + whereSql,
            binds.toArray());
    long watchCount =
        countOf(
            "select count(distinct watch_id) from listing_hijacker_record where tenant_id=?", tenant);

    return new ListingHijackerRecordPage(
        items, Math.max(0, page), lim, total, alertCount, watchCount, DATA_SOURCE, SOURCE_NOTE);
  }

  private ListingHijackerRecordRow recordRow(Record r) {
    BigDecimal referencePrice = r.get("reference_price", BigDecimal.class);
    return new ListingHijackerRecordRow(
        r.get("id", UUID.class),
        r.get("watch_id", UUID.class),
        r.get("listing_id", UUID.class),
        nullToEmpty(r.get("external_item_id", String.class)),
        nullToEmpty(r.get("title", String.class)),
        nullToEmpty(r.get("shop_code", String.class)),
        nullToEmpty(r.get("platform_code", String.class)),
        nullToEmpty(r.get("hijacker_seller_id", String.class)),
        nullToEmpty(r.get("hijacker_shop_name", String.class)),
        nullToEmpty(r.get("hijacker_shop_identifier", String.class)),
        r.get("hijacker_shop_id", UUID.class) == null ? "EXTERNAL" : "OWN_SHOP",
        r.get("hijacker_price", BigDecimal.class),
        nullToEmpty(r.get("currency", String.class)),
        referencePrice,
        r.get("price_gap", BigDecimal.class),
        r.get("price_gap_ratio", BigDecimal.class),
        r.get("observed_at", OffsetDateTime.class),
        nullToEmpty(r.get("source", String.class)),
        nullToEmpty(r.get("ingest_channel", String.class)),
        nullToEmpty(r.get("batch_idempotency_key", String.class)),
        r.get("received_at", OffsetDateTime.class),
        DATA_SOURCE,
        SOURCE_NOTE);
  }

  // ==================================================================
  // 预警记录
  // ==================================================================

  /** 预警记录列表（真分页 + 筛选）。 */
  public ListingHijackerAlertPage alerts(
      String watchId,
      String alertType,
      String severity,
      String status,
      String from,
      String to,
      int page,
      int size) {
    UUID tenant = currentTenant();
    StringBuilder where = new StringBuilder(" where a.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);

    String watch = blankToNull(watchId);
    if (watch != null) {
      where.append(" and a.watch_id=?");
      binds.add(parseId(watch));
    }
    String type = blankToNull(alertType);
    if (type != null) {
      String upper = type.toUpperCase(Locale.ROOT);
      if (!ALERT_TYPES.contains(upper)) {
        throw badRequest(
            "未知的预警类型「" + alertType + "」。只认这两条：" + String.join(" / ", ALERT_TYPES));
      }
      where.append(" and a.alert_type=?");
      binds.add(upper);
    }
    String sev = blankToNull(severity);
    if (sev != null) {
      String upper = sev.toUpperCase(Locale.ROOT);
      if (!SEVERITIES.contains(upper)) {
        throw badRequest(
            "未知的严重度「" + severity + "」。只认这两条：" + String.join(" / ", SEVERITIES));
      }
      where.append(" and a.severity=?");
      binds.add(upper);
    }
    String st = blankToNull(status);
    if (st != null) {
      String upper = st.toUpperCase(Locale.ROOT);
      if (!ALERT_STATUSES.contains(upper)) {
        throw badRequest(
            "未知的预警状态「" + status + "」。只认这两条：" + String.join(" / ", ALERT_STATUSES));
      }
      where.append(" and a.status=?");
      binds.add(upper);
    }
    LocalDate fromDate = parseDate(from, "起始日期");
    if (fromDate != null) {
      where.append(" and a.detected_at >= ?::timestamptz");
      binds.add(utcStart(fromDate));
    }
    LocalDate toDate = parseDate(to, "结束日期");
    if (toDate != null) {
      where.append(" and a.detected_at < ?::timestamptz");
      binds.add(utcDayAfter(toDate));
    }

    String whereSql = where.toString();
    String fromSql =
        """
        select a.id,a.watch_id,a.record_id,a.listing_id,l.external_item_id,l.title,
               a.alert_type,a.severity,a.observed_price,a.currency,a.reference_price,
               a.status,a.reason,a.detected_at,a.acknowledged_at,
               r.observed_at,r.hijacker_seller_id,r.hijacker_shop_name,r.hijacker_shop_id,
               case when a.reference_price is not null and a.reference_price <> 0
                    then round((a.reference_price - a.observed_price) / a.reference_price, 6)
                    else null end as price_gap_ratio
        from listing_hijacker_alert a
        join listing_hijacker_watch w on w.tenant_id=a.tenant_id and w.id=a.watch_id
        join listing_hijacker_record r on r.tenant_id=a.tenant_id and r.id=a.record_id
        join listing l on l.tenant_id=a.tenant_id and l.id=a.listing_id
        """
            + whereSql;

    long total = count(fromSql, binds.toArray());
    int lim = clampSize(size);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(lim);
    pageBinds.add(Math.max(0, page) * lim);
    List<ListingHijackerAlertRow> items =
        sql.fetch(
                fromSql + " order by a.detected_at desc, a.id desc limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(this::alertRow)
            .toList();

    long openCount =
        countOf(
            "select count(*) from listing_hijacker_alert a where a.tenant_id=? and a.status='OPEN'",
            tenant);

    return new ListingHijackerAlertPage(
        items, Math.max(0, page), lim, total, openCount, DATA_SOURCE, SOURCE_NOTE);
  }

  private ListingHijackerAlertRow alertRow(Record r) {
    return new ListingHijackerAlertRow(
        r.get("id", UUID.class),
        r.get("watch_id", UUID.class),
        r.get("record_id", UUID.class),
        r.get("listing_id", UUID.class),
        nullToEmpty(r.get("external_item_id", String.class)),
        nullToEmpty(r.get("title", String.class)),
        nullToEmpty(r.get("alert_type", String.class)),
        nullToEmpty(r.get("severity", String.class)),
        nullToEmpty(r.get("hijacker_seller_id", String.class)),
        nullToEmpty(r.get("hijacker_shop_name", String.class)),
        r.get("hijacker_shop_id", UUID.class) == null ? "EXTERNAL" : "OWN_SHOP",
        r.get("observed_price", BigDecimal.class),
        nullToEmpty(r.get("currency", String.class)),
        r.get("reference_price", BigDecimal.class),
        r.get("price_gap_ratio", BigDecimal.class),
        nullToEmpty(r.get("status", String.class)),
        nullToEmpty(r.get("reason", String.class)),
        r.get("observed_at", OffsetDateTime.class),
        r.get("detected_at", OffsetDateTime.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        DATA_SOURCE,
        SOURCE_NOTE);
  }

  private ListingHijackerAlertRow loadAlert(UUID tenant, UUID alertId) {
    Record r =
        sql.fetchOne(
            """
            select a.id,a.watch_id,a.record_id,a.listing_id,l.external_item_id,l.title,
                   a.alert_type,a.severity,a.observed_price,a.currency,a.reference_price,
                   a.status,a.reason,a.detected_at,a.acknowledged_at,
                   r.observed_at,r.hijacker_seller_id,r.hijacker_shop_name,r.hijacker_shop_id,
                   case when a.reference_price is not null and a.reference_price <> 0
                        then round((a.reference_price - a.observed_price) / a.reference_price, 6)
                        else null end as price_gap_ratio
            from listing_hijacker_alert a
            join listing_hijacker_watch w on w.tenant_id=a.tenant_id and w.id=a.watch_id
            join listing_hijacker_record r on r.tenant_id=a.tenant_id and r.id=a.record_id
            join listing l on l.tenant_id=a.tenant_id and l.id=a.listing_id
            where a.tenant_id=? and a.id=?
            """,
            tenant,
            alertId);
    if (r == null) {
      throw notFound();
    }
    return alertRow(r);
  }

  private ListingHijackerAlertRow loadAlertByRecord(UUID tenant, UUID recordId) {
    Record r =
        sql.fetchOne(
            """
            select a.id,a.watch_id,a.record_id,a.listing_id,l.external_item_id,l.title,
                   a.alert_type,a.severity,a.observed_price,a.currency,a.reference_price,
                   a.status,a.reason,a.detected_at,a.acknowledged_at,
                   r.observed_at,r.hijacker_seller_id,r.hijacker_shop_name,r.hijacker_shop_id,
                   case when a.reference_price is not null and a.reference_price <> 0
                        then round((a.reference_price - a.observed_price) / a.reference_price, 6)
                        else null end as price_gap_ratio
            from listing_hijacker_alert a
            join listing_hijacker_watch w on w.tenant_id=a.tenant_id and w.id=a.watch_id
            join listing_hijacker_record r on r.tenant_id=a.tenant_id and r.id=a.record_id
            join listing l on l.tenant_id=a.tenant_id and l.id=a.listing_id
            where a.tenant_id=? and a.record_id=?
            """,
            tenant,
            recordId);
    if (r == null) {
      return null;
    }
    return alertRow(r);
  }

  /**
   * 标记预警为「已阅」。
   *
   * <p>🔴 这是**内部记录状态**，不是平台侧的处置：投诉 / 举报 / 申诉在平台侧完成，
   * 本模块连入口都不提供。已阅之后判定结论与触发价格**不可再改**（DB 触发器钉着）。
   */
  public ListingHijackerAlertRow acknowledgeAlert(
      String alertId, AcknowledgeHijackerAlertRequest request) {
    UUID tenant = currentTenant();
    UUID id = parseId(alertId);
    Record current =
        sql.fetchOne(
            "select id,status from listing_hijacker_alert where tenant_id=? and id=?", tenant, id);
    if (current == null) {
      throw notFound();
    }
    if (!"OPEN".equals(current.get("status", String.class))) {
      // 已经阅过了 —— 幂等地返回同一条，不报错也不重复写。
      return loadAlert(tenant, id);
    }
    sql.execute(
        """
        update listing_hijacker_alert
           set status='ACKNOWLEDGED', acknowledged_at=now(), acknowledged_by=?, updated_at=now(),
               detail = detail || ?::jsonb
         where tenant_id=? and id=?
        """,
        currentActor(),
        writeJson(Map.of("acknowledgeNote", text(request == null ? "" : request.note()))),
        tenant,
        id);
    return loadAlert(tenant, id);
  }

  private UUID currentActor() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.actor',true),'')");
    if (raw == null) {
      return null;
    }
    try {
      return UUID.fromString(String.valueOf(raw));
    } catch (RuntimeException e) {
      return null;
    }
  }

  // ==================================================================
  // 对比视图
  // ==================================================================

  /**
   * 🔴 对比视图：自家 listing 的参考售价 × 每个跟卖者的价格。
   *
   * <p>🔴 这是**服务端聚合**（GROUP BY 跟卖者）+ **真分页**：
   * 前端拿到的是一页「一个跟卖者一行」的结论，价格差比例也是服务端现算的。
   */
  public ListingHijackerComparisonPage comparison(
      String watchId, String from, String to, int page, int size) {
    UUID tenant = currentTenant();
    if (blankToNull(watchId) == null) {
      throw unprocessable("必须给出监控对象（watchId）—— 对比视图是「某个 ASIN 的自家价 vs 跟卖价」");
    }
    UUID watchUuid = parseId(watchId);
    Record watch = loadWatchRecord(tenant, watchUuid);
    if (watch == null) {
      throw notFound();
    }

    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate toDate = parseDate(to, "结束日期");
    if (toDate == null) {
      toDate = today;
    }
    LocalDate fromDate = parseDate(from, "起始日期");
    if (fromDate == null) {
      fromDate = toDate.minusDays(29);
    }
    if (fromDate.isAfter(toDate)) {
      throw badRequest("起始日期不能晚于结束日期（" + fromDate + " > " + toDate + "）");
    }

    StringBuilder where = new StringBuilder(" where r.tenant_id=? and r.watch_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    binds.add(watchUuid);
    where.append(" and r.observed_at >= ?::timestamptz and r.observed_at < ?::timestamptz");
    binds.add(utcStart(fromDate));
    binds.add(utcDayAfter(toDate));

    String agg = COMPARISON_AGG.formatted(where.toString());

    // 🔴 total 是**分组后的行数**（一个跟卖者一行）—— 不能用 count(fromSql) 那条包装，
    //    那条会把已经聚合好的子查询再包一层，结果恒等于 1。
    long total = countOf("select count(*) from (" + agg + ") t", binds.toArray());
    int lim = clampSize(size);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(lim);
    pageBinds.add(Math.max(0, page) * lim);

    // 🔴 排序：**压得最狠的排前面**（价格差比例降序）——
    //    跟卖监控最该先看到的就是「谁压得最狠」，不是「谁最新出现」。
    List<ListingHijackerComparisonRow> items =
        sql.fetch(
                "select t.* from ("
                    + agg
                    + ") t order by t.price_gap_ratio desc nulls last, t.latest_price asc nulls last,"
                    + " t.hijacker_seller_id limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(this::comparisonRow)
            .toList();

    Record summary =
        sql.fetchOne(
            """
            select count(*) as hijacker_count,
                   coalesce(sum(observation_count),0) as observation_count,
                   min(latest_price) as lowest_price,
                   case when coalesce(sum(observation_count),0) <> 0
                        then round(sum(price_sum) / sum(observation_count), 4) else null end as average_price,
                   max(price_gap_ratio) as max_gap_ratio
            from (
            """
                + agg
                + ") t",
            binds.toArray());

    long openAlerts =
        countOf(
            "select count(*) from listing_hijacker_alert where tenant_id=? and watch_id=? and status='OPEN'",
            tenant,
            watchUuid);

    boolean hasData = summary != null && longOf(summary, "hijacker_count") > 0;
    String emptyReason = "";
    if (!hasData) {
      emptyReason =
          "这个监控对象在区间（"
              + fromDate
              + " ~ "
              + toDate
              + "）内还没有跟卖数据。跟卖信息来自平台抓取 / 平台接口推送，"
              + "抓取到了才会出现在这里 —— 本模块不自己轮询平台。";
    }

    return new ListingHijackerComparisonPage(
        items,
        Math.max(0, page),
        lim,
        total,
        watchUuid,
        watch.get("listing_id", UUID.class),
        nullToEmpty(watch.get("external_item_id", String.class)),
        nullToEmpty(watch.get("title", String.class)),
        watch.get("reference_price", BigDecimal.class),
        nullToEmpty(watch.get("currency", String.class)),
        summary == null ? 0L : longOf(summary, "hijacker_count"),
        summary == null ? 0L : longOf(summary, "observation_count"),
        summary == null ? null : summary.get("lowest_price", BigDecimal.class),
        summary == null ? null : summary.get("average_price", BigDecimal.class),
        summary == null ? null : summary.get("max_gap_ratio", BigDecimal.class),
        openAlerts,
        fromDate.toString(),
        toDate.toString(),
        hasData,
        emptyReason,
        DATA_SOURCE,
        SOURCE_NOTE);
  }

  private ListingHijackerComparisonRow comparisonRow(Record r) {
    return new ListingHijackerComparisonRow(
        nullToEmpty(r.get("hijacker_seller_id", String.class)),
        nullToEmpty(r.get("hijacker_shop_name", String.class)),
        nullToEmpty(r.get("hijacker_shop_identifier", String.class)),
        Boolean.TRUE.equals(r.get("own_shop", Boolean.class)) ? "OWN_SHOP" : "EXTERNAL",
        nullToEmpty(r.get("platform_code", String.class)),
        longOf(r, "observation_count"),
        r.get("latest_price", BigDecimal.class),
        r.get("min_price", BigDecimal.class),
        r.get("max_price", BigDecimal.class),
        r.get("avg_price", BigDecimal.class),
        r.get("reference_price", BigDecimal.class),
        nullToEmpty(r.get("currency", String.class)),
        r.get("price_gap", BigDecimal.class),
        r.get("price_gap_ratio", BigDecimal.class),
        r.get("last_observed_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 内部
  // ==================================================================

  private long count(String from, Object[] binds) {
    Record r = sql.fetchOne("select count(*) as n from (" + from + ") t", binds);
    return r == null ? 0L : longOf(r, "n");
  }

  private long countOf(String sqlText, Object... binds) {
    Long v = (Long) sql.fetchValue(sqlText, binds);
    return v == null ? 0L : v;
  }
}
