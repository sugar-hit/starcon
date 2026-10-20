package com.starcon.channel;

import static com.starcon.channel.LogisticsTimelinessModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
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
 * 10.6.2.1 时效分析。
 *
 * <p>蓝图：「分析时效表现：各渠道各国家的妥投率、超时率、平均时效波动、旺季变化，
 * 用于渠道评分与时效承诺设置。」输入：轨迹数据；承运商承诺时效。输出：时效报表 +
 * 渠道评分 + 异常集中分析。并加粗
 * 「<b>时效数据要按渠道和国家交叉分析，总体平均会掩盖某些渠道在某些国家的系统性延误</b>」。
 *
 * <p>🔴 本类唯一不能妥协的约束是那条加粗句，它的落地是三件事同时做：
 *
 * <ol>
 *   <li><b>交叉维度进 SQL</b>：{@code group by channel_id, country_code} ——
 *       没有一条查询是「只按渠道」或「只按国家」的，总体口径只出现在合计里。</li>
 *   <li><b>存储形状也是交叉的</b>：{@code logistics_timeliness_score} 的唯一键是
 *       {@code (tenant_id, channel_id, country_code, window_start, window_end)} ——
 *       物理上就存不下「这条渠道总体表现」那一行。</li>
 *   <li><b>异常集中分析按格排序</b>：帕累托是在「渠道 × 国家」的格上算的，
 *       所以「哪个渠道在哪个国家拖后腿」这件事能被直接读出来，
 *       而不是只剩一句「某渠道表现差」。</li>
 * </ol>
 *
 * <p>🔴 第二个不能妥协的约束是**数字口径**：
 *
 * <ul>
 *   <li>比率一律 {@link BigDecimal}，scale 4，{@link RoundingMode#HALF_UP} 钉死；
 *       页面上一个 double 都没有。</li>
 *   <li>时长一律以<b>小时</b>为单位，{@link BigDecimal} scale 4。</li>
 *   <li><b>除法分母可能为 0</b>（该格没有任何已签收票 / 没有任何可比的票）⇒
 *       返回 {@code null}（不可比）而不是抛 ArithmeticException，也<strong>不是 0</strong>
 *       —— 0 会被读成「比过了，确实最差」。</li>
 *   <li>时效差值（实际 − 承诺）是 <b>timestamptz 相减</b>：PostgreSQL 的
 *       {@code timestamptz - timestamptz} 是绝对时刻差，与会话时区无关 ——
 *       跨时区渠道的时效不会因为数据库跑在哪个时区而变。</li>
 * </ul>
 *
 * <p>数据来源纪律：<b>一条 SQL 都不碰别的服务</b>。轨迹数据来自同库
 * {@code logistics_tracking_subject}（10.3.1.1，V212），承运商承诺时效是那张表上的
 * <b>发货当时快照</b>（{@code promised_eta_at}）—— 本类**不去 join**
 * {@code logistics_transit_rule} 重算：事后改档期不能让一笔已经延误的货变成准点。
 *
 * <p>边界：本模块只做<b>时效维度</b>。成本分析（10.6.1.1，未交付）不在这里；
 * 下游 10.1.2.2 比价与 9.5.1 订单时效**尚未接本模块的产出**（capabilities 里
 * 显式报 available=false + ownerModule + 影响），本模块不越界去改它们的表。
 */
@Service
public class LogisticsTimelinessService {

  // ================================================================
  // 常量：口径与策略（全部是**可解释的业务口径**，不是随手定的魔数）
  // ================================================================

  /** 默认统计窗口天数（没有指定起止时的回退）。 */
  static final int DEFAULT_WINDOW_DAYS = 90;

  /** 窗口上限：两年。再长下去单次聚合会把报表拖垮，而且没人看得懂两年平均。 */
  static final int MAX_WINDOW_DAYS = 730;

  /** 默认最小样本量：可比（已签收且有承诺）的票数不足这个数 ⇒ 评分不可比。 */
  static final int DEFAULT_MIN_SAMPLE = 10;

  /** 评分权重：妥投率 / 达成率 / 时效偏差。 */
  static final BigDecimal WEIGHT_DELIVERY = new BigDecimal("30");
  static final BigDecimal WEIGHT_ON_TIME = new BigDecimal("50");
  static final BigDecimal WEIGHT_PUNCTUALITY = new BigDecimal("20");

  /**
   * 时效偏差的容忍度（小时）：平均比承诺晚这么多小时 ⇒ 这一项得 0 分。
   *
   * <p>为什么是 1 天：承诺时效本身就是按天给的（{@code promised_transit_days_max}），
   * 平均晚 1 天已经是「比承诺整整晚了一天」，再宽松就等于没有时效要求。
   */
  static final BigDecimal PUNCTUALITY_TOLERANCE_HOURS = new BigDecimal("24");

  /** 帕累托阈值：累计贡献到这个比例以内的格子算「贡献了大部分超时」。 */
  static final BigDecimal PARETO_THRESHOLD = new BigDecimal("0.8000");

  /** 时效状态。 */
  static final List<String> TIMELINESS_STATUSES =
      List.of("ON_TIME", "OVERDUE", "NO_PROMISE", "IN_TRANSIT");

  /** 评分等级。 */
  static final List<String> SCORE_GRADES =
      List.of("EXCELLENT", "GOOD", "FAIR", "POOR", "INSUFFICIENT");

  // ================================================================
  // 依赖
  // ================================================================

  private final DSLContext sql;

  public LogisticsTimelinessService(DSLContext sql) {
    this.sql = sql;
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 在进入事务时绑定）。 */
  private UUID currentTenant() {
    Record row = sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')");
    String raw = row == null ? null : row.get(0, String.class);
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  // ================================================================
  // 数字口径：全部 BigDecimal，全部 scale 4 / HALF_UP
  // ================================================================

  /**
   * 比率：分子 / 分母，scale 4，HALF_UP。
   *
   * <p>🔴 <b>分母为 0 ⇒ 返回 null（不可比），不是 0</b>。0 会被读成「比过了，
   * 确实最差」；null 是「压根没资格比」。本仓在 10.1.2.2 比价里已经用过同一口径。
   *
   * <p>实现走「先乘 10^4 再按整数除」而不是先算小数再 {@code setScale} ——
   * 后者会引入二次舍入（先舍到 10 位再舍到 4 位），在正好落在半值上时会与
   * PostgreSQL 的 {@code round(numeric, 4)} 差最后一位。本仓的落库快照用
   * 生成列 {@code round(...)} 算比率，实时报表与落库口径必须逐位相同。
   */
  static BigDecimal rate(int numerator, int denominator) {
    if (denominator <= 0) {
      return null;
    }
    BigDecimal scaled =
        BigDecimal.valueOf(numerator)
            .multiply(new BigDecimal("10000"))
            .divide(BigDecimal.valueOf(denominator), 0, RoundingMode.HALF_UP);
    return scaled.movePointLeft(4).setScale(4, RoundingMode.HALF_UP);
  }

  /** 比率：分子 / 分母（BigDecimal 分子，可能为 null）。 */
  static BigDecimal rate(BigDecimal numerator, BigDecimal denominator) {
    if (numerator == null || denominator == null || denominator.signum() == 0) {
      return null;
    }
    BigDecimal scaled =
        numerator
            .multiply(new BigDecimal("10000"))
            .divide(denominator, 0, RoundingMode.HALF_UP);
    return scaled.movePointLeft(4).setScale(4, RoundingMode.HALF_UP);
  }

  /** 小时数：统一 scale 4（数据库列是 numeric(19,4)），null 透传。 */
  static BigDecimal hours(BigDecimal value) {
    return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
  }

  // ================================================================
  // 能力声明
  // ================================================================

  public LogisticsTimelinessCapabilities capabilities() {
    List<TimelinessCapabilityDimension> dimensions =
        List.of(
            new TimelinessCapabilityDimension(
                "delivery_rate",
                "🔴 妥投率（渠道 × 国家交叉）",
                true,
                "上游 10.3.1.1 logistics_tracking_subject（V212）实时聚合",
                "",
                "妥投率 = 签投数 / 发出数；退回算**未妥投**，不假装它还在路上"),
            new TimelinessCapabilityDimension(
                "overdue_rate",
                "🔴 超时率（渠道 × 国家交叉）",
                true,
                "上游 10.3.1.1 logistics_tracking_subject（V212）实时聚合",
                "",
                "超时率 = 超时数 / **可比数**；「已签收但没有承诺时效可比」的那批票"
                    + "单列，不算准点也不算超时"),
            new TimelinessCapabilityDimension(
                "transit_variance",
                "🔴 平均时效波动（均值 / 标准差 / P90 / 最大超时）",
                true,
                "上游 10.3.1.1 logistics_tracking_subject（V212）实时聚合",
                "",
                "时长一律以**小时**为单位；平均时效与偏差都是 BigDecimal，"
                    + "差值走 timestamptz 相减（带时区）"),
            new TimelinessCapabilityDimension(
                "peak_season",
                "🔴 旺季变化（按 UTC 月分桶）",
                true,
                "上游 10.3.1.1 logistics_tracking_subject（V212）按月聚合",
                "",
                "月份分桶固定用 UTC：换台数据库换时区，报表上的月份不会整个错位"),
            new TimelinessCapabilityDimension(
                "anomaly_concentration",
                "🔴 异常集中分析（渠道 × 国家 帕累托）",
                true,
                "本模块在交叉格上算贡献率与累计贡献率",
                "",
                "「哪些渠道在哪些国家拖后腿」能被直接读出来，而不是只剩"
                    + "一句「某渠道表现差」"),
            new TimelinessCapabilityDimension(
                "channel_score",
                "🔴 渠道时效评分（0~100，按渠道 × 国家）",
                true,
                "本模块 logistics_timeliness_score（V218）",
                "",
                "样本不足时评分是**不可比（null）而不是 0 分**；"
                    + "0 分是「比过了确实最差」，null 是「压根没资格比」"),
            new TimelinessCapabilityDimension(
                "promised_transit_source",
                "承运商承诺时效（超时判定的口径来源）",
                true,
                "上游 10.3.1.1 主体表上的**发货当时快照** promised_eta_at（V212）",
                "",
                "承诺是快照而不是实时 join：事后改档期不能让一笔已经延误的货变成准点"),
            new TimelinessCapabilityDimension(
                "rating_backfill",
                "把时效评分回灌成 10.1.2.2 比价用的渠道评分",
                false,
                "10.1.2.2 比价的 channel_rating_score 当前仍是人工登记"
                    + "（score_source=MANUAL_ENTERED）",
                "10.1.2.2",
                "比价那条车道改数据来源是它自己的事；本模块**不写**它的表"
                    + "（每张事实表只有一个写入所有者）"),
            new TimelinessCapabilityDimension(
                "cost_analysis_link",
                "成本侧联动（成本分析）",
                false,
                "10.6.1.1 成本分析未交付",
                "10.6.1.1",
                "本页只做**时效**维度，不含任何成本；成本侧结论要等 10.6.1.1"),
            new TimelinessCapabilityDimension(
                "order_timeliness_link",
                "订单级时效联动（订单时效）",
                false,
                "9.5.1.1 订单时效未接本模块产出",
                "9.5.1.1",
                "本页只到**渠道时效**；订单级时效（逐订单 SLA 达成）在 trade 服务"),
            new TimelinessCapabilityDimension(
                "transit_promise_edit",
                "时效承诺设置（改承诺档期）",
                false,
                "承诺档期的写入权在 10.1.1.3 时效与限制",
                "10.1.1.3",
                "本模块**只读**承诺快照，不提供改承诺的入口 —— "
                    + "改承诺会改变「已延误」的判定口径，那属于另一个模块"));
    return new LogisticsTimelinessCapabilities(
        dimensions,
        TIMELINESS_STATUSES,
        SCORE_GRADES,
        DEFAULT_WINDOW_DAYS,
        DEFAULT_MIN_SAMPLE,
        "UTC",
        "本模块只做**时效**维度：成本归 10.6.1.1（未交付），"
            + "比价用的渠道评分仍由 10.1.2.2 人工登记，"
            + "订单级时效在 trade 的 9.5.1.1。",
        "🔴 报表按「渠道 × 国家」交叉给，不是一个总体平均 —— "
            + "总体平均会掩盖某些渠道在某些国家的系统性延误。",
        "事实源是上游 10.3.1.1 的轨迹主体表；本页不新建事实表，"
            + "只把算出来的时效评分落一份台账（V218）。");
  }

  // ================================================================
  // 统计窗口
  // ================================================================

  /**
   * 解析统计窗口。
   *
   * <p>口径：{@code from} / {@code to} 都是 <b>yyyy-MM-dd 日期</b>（UTC 自然日），
   * <b>含首含尾</b>；内部转成左闭右开的 {@code [from 00:00, to+1 00:00)}。
   *
   * <p>⚠️ 没给 {@code to} 时，右边界取<b>明天 00:00 UTC</b>（而不是「此刻」）：
   * 窗口右边界必须**在同一天里保持稳定**，否则「重算并落库」每次都会开出一个
   * 新的窗口键，幂等重算就变成了堆历史快照。
   */
  TimelinessWindow window(String from, String to) {
    OffsetDateTime end;
    if (text(to).isEmpty()) {
      end = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
    } else {
      end =
          parseDate(to, "to").plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
    }
    OffsetDateTime start;
    if (text(from).isEmpty()) {
      start = end.minusDays(DEFAULT_WINDOW_DAYS);
    } else {
      start = parseDate(from, "from").atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
    }
    if (!start.isBefore(end)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "统计窗口的起止日期不对：from 必须早于 to");
    }
    int days = (int) ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate());
    if (days > MAX_WINDOW_DAYS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "统计窗口不能超过 " + MAX_WINDOW_DAYS + " 天");
    }
    return new TimelinessWindow(
        start,
        end,
        days,
        "UTC",
        "窗口为左闭右开 [" + start + ", " + end + ")，日期入参按 UTC 自然日解释"
            + "（to 传 2026-09-30 表示覆盖到 2026-09-30 当天结束）。");
  }

  private static LocalDate parseDate(String raw, String field) {
    try {
      return LocalDate.parse(text(raw));
    } catch (DateTimeParseException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, field + " 必须是 yyyy-MM-dd 格式的日期");
    }
  }

  // ================================================================
  // SQL 片段
  // ================================================================

  /**
   * 基础 CTE：一票已发货的轨迹主体 + 逐票算好的时效量。
   *
   * <p>🔴 <b>时效差在 SQL 里算，而且是 timestamptz 相减</b>：
   * {@code extract(epoch from (latest_occurred_at - promised_eta_at)) / 3600}。
   * timestamptz 相减是<b>绝对时刻差</b>，与会话时区无关；反过来用
   * {@code LocalDateTime} 相减会丢掉时区，跨时区渠道的时效会整体算错
   * （在本仓库里这正是被点名过的坑）。
   *
   * <p>逐票先算好再聚合，是为了「平均 / 标准差 / P90 / 最大值」这四件事
   * 算的是<b>同一批样本</b>：如果每个指标各写一段 SQL，口径漂移是迟早的事。
   */
  private static final String BASE_CTE =
      "with base as ("
          + " select s.tenant_id, s.id, s.channel_id, s.destination_country, s.master_waybill_no,"
          + "        s.carrier_code, s.service_level, s.status, s.shipped_at, s.latest_occurred_at,"
          + "        s.promised_eta_at,"
          + "        (s.status = 'DELIVERED' and s.latest_occurred_at is not null) as delivered,"
          + "        (s.status = 'DELIVERED' and s.latest_occurred_at is not null"
          + "         and s.promised_eta_at is not null"
          + "         and s.latest_occurred_at <= s.promised_eta_at) as on_time,"
          + "        (s.status = 'DELIVERED' and s.latest_occurred_at is not null"
          + "         and s.promised_eta_at is not null"
          + "         and s.latest_occurred_at > s.promised_eta_at) as overdue,"
          + "        (s.status = 'DELIVERED' and s.latest_occurred_at is not null"
          + "         and s.promised_eta_at is null) as no_promise,"
          + "        ((s.status <> 'DELIVERED' or s.latest_occurred_at is null)"
          + "         and s.status <> 'RETURNED') as pending,"
          + "        (s.status = 'RETURNED') as returned,"
          + "        case when s.status = 'DELIVERED' and s.latest_occurred_at is not null"
          + "             and s.shipped_at is not null"
          + "             then extract(epoch from (s.latest_occurred_at - s.shipped_at)) / 3600"
          + "        end as transit_hours,"
          + "        case when s.status = 'DELIVERED' and s.latest_occurred_at is not null"
          + "             and s.promised_eta_at is not null"
          + "             then extract(epoch from (s.latest_occurred_at - s.promised_eta_at)) / 3600"
          + "        end as deviation_hours"
          + "   from logistics_tracking_subject s"
          + "  where s.tenant_id = ? and s.status <> 'VOID'"
          + "    and s.shipped_at is not null"
          + "    and s.shipped_at >= ?::timestamptz and s.shipped_at < ?::timestamptz";

  /** 交叉格聚合（渠道 × 国家）。 */
  private static final String CELL_AGGREGATE =
      " select b.channel_id, c.channel_code, c.channel_name,"
          + "        b.destination_country as country_code,"
          + "        count(*) as shipped_count,"
          + "        count(*) filter (where b.delivered) as delivered_count,"
          + "        count(*) filter (where b.on_time or b.overdue) as comparable_count,"
          + "        count(*) filter (where b.on_time) as on_time_count,"
          + "        count(*) filter (where b.overdue) as overdue_count,"
          + "        count(*) filter (where b.no_promise) as no_promise_count,"
          + "        count(*) filter (where b.pending) as pending_count,"
          + "        count(*) filter (where b.returned) as returned_count,"
          + "        avg(b.transit_hours) as avg_transit_hours,"
          + "        avg(b.deviation_hours) as avg_deviation_hours,"
          + "        stddev_samp(b.transit_hours) as stddev_transit_hours,"
          + "        percentile_disc(0.9) within group (order by b.transit_hours)"
          + "          filter (where b.transit_hours is not null) as p90_transit_hours,"
          + "        max(b.deviation_hours) filter (where b.overdue) as max_overdue_hours"
          + "   from base b"
          + "   left join logistics_channel_config c"
          + "     on c.tenant_id = b.tenant_id and c.id = b.channel_id"
          + "  where b.channel_id is not null and b.destination_country <> ''";

  /** 全量合计（总体口径只出现在这里）。 */
  private static final String TOTAL_AGGREGATE =
      " select count(*) as shipped_count,"
          + "        count(*) filter (where b.delivered) as delivered_count,"
          + "        count(*) filter (where b.on_time or b.overdue) as comparable_count,"
          + "        count(*) filter (where b.on_time) as on_time_count,"
          + "        count(*) filter (where b.overdue) as overdue_count,"
          + "        count(*) filter (where b.no_promise) as no_promise_count,"
          + "        count(*) filter (where b.pending) as pending_count,"
          + "        count(*) filter (where b.returned) as returned_count,"
          + "        count(*) filter (where b.channel_id is null) as unattributed_count,"
          + "        count(*) filter (where b.destination_country = '') as unclassified_count,"
          + "        avg(b.transit_hours) as avg_transit_hours,"
          + "        avg(b.deviation_hours) as avg_deviation_hours,"
          + "        stddev_samp(b.transit_hours) as stddev_transit_hours,"
          + "        percentile_disc(0.9) within group (order by b.transit_hours)"
          + "          filter (where b.transit_hours is not null) as p90_transit_hours,"
          + "        max(b.deviation_hours) filter (where b.overdue) as max_overdue_hours"
          + "   from base b";

  // ================================================================
  // 内部：一格（渠道 × 国家）的原始量
  // ================================================================

  /** 一格的原始计数与统计量（比率与评分在 Java 侧按统一口径算）。 */
  record Cell(
      UUID channelId,
      String channelCode,
      String channelName,
      String countryCode,
      int shippedCount,
      int deliveredCount,
      int comparableCount,
      int onTimeCount,
      int overdueCount,
      int noPromiseCount,
      int pendingCount,
      int returnedCount,
      BigDecimal avgTransitHours,
      BigDecimal avgDeviationHours,
      BigDecimal stddevTransitHours,
      BigDecimal p90TransitHours,
      BigDecimal maxOverdueHours) {

    BigDecimal deliveryRate() {
      return rate(deliveredCount, shippedCount);
    }

    BigDecimal onTimeRate() {
      return rate(onTimeCount, comparableCount);
    }

    BigDecimal overdueRate() {
      return rate(overdueCount, comparableCount);
    }
  }

  /** 全量合计的原始量。 */
  record Totals(
      int shippedCount,
      int deliveredCount,
      int comparableCount,
      int onTimeCount,
      int overdueCount,
      int noPromiseCount,
      int pendingCount,
      int returnedCount,
      BigDecimal avgTransitHours,
      BigDecimal avgDeviationHours,
      BigDecimal stddevTransitHours,
      BigDecimal p90TransitHours,
      BigDecimal maxOverdueHours) {

    TimelinessTotals toModel() {
      return new TimelinessTotals(
          shippedCount,
          deliveredCount,
          comparableCount,
          onTimeCount,
          overdueCount,
          noPromiseCount,
          pendingCount,
          returnedCount,
          rate(deliveredCount, shippedCount),
          rate(onTimeCount, comparableCount),
          rate(overdueCount, comparableCount),
          hours(avgTransitHours),
          hours(avgDeviationHours),
          hours(stddevTransitHours),
          hours(p90TransitHours),
          hours(maxOverdueHours));
    }
  }

  private static Cell toCell(Record r) {
    return new Cell(
        r.get("channel_id", UUID.class),
        r.get("channel_code", String.class),
        r.get("channel_name", String.class),
        r.get("country_code", String.class),
        intOf(r, "shipped_count", 0),
        intOf(r, "delivered_count", 0),
        intOf(r, "comparable_count", 0),
        intOf(r, "on_time_count", 0),
        intOf(r, "overdue_count", 0),
        intOf(r, "no_promise_count", 0),
        intOf(r, "pending_count", 0),
        intOf(r, "returned_count", 0),
        r.get("avg_transit_hours", BigDecimal.class),
        r.get("avg_deviation_hours", BigDecimal.class),
        r.get("stddev_transit_hours", BigDecimal.class),
        r.get("p90_transit_hours", BigDecimal.class),
        r.get("max_overdue_hours", BigDecimal.class));
  }

  private static int intOf(Record r, String column, int fallback) {
    Integer value = r.get(column, Integer.class);
    return value == null ? fallback : value;
  }

  /** 基础 CTE 的绑定：租户 + 窗口左右边界。 */
  private List<Object> baseBinds(TimelinessWindow w) {
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    binds.add(w.from());
    binds.add(w.to());
    return binds;
  }

  /** CTE 内的可选筛选（渠道 / 国家）。 */
  private String baseFilters(UUID channelId, String countryCode, List<Object> binds) {
    StringBuilder out = new StringBuilder();
    if (channelId != null) {
      out.append(" and s.channel_id = ?");
      binds.add(channelId);
    }
    String country = text(countryCode).toUpperCase(Locale.ROOT);
    if (!country.isEmpty()) {
      out.append(" and s.destination_country = ?");
      binds.add(country);
    }
    return out.toString();
  }

  /** 全部交叉格（不分页）：异常集中分析与评分都用它。 */
  private List<Cell> allCells(TimelinessWindow w, UUID channelId, String countryCode) {
    List<Object> binds = baseBinds(w);
    String filters = baseFilters(channelId, countryCode, binds);
    List<Cell> out = new ArrayList<>();
    for (Record r :
        sql.fetch(
            BASE_CTE
                + filters
                + " ) "
                + CELL_AGGREGATE
                + " group by b.channel_id, c.channel_code, c.channel_name, b.destination_country"
                + " order by count(*) filter (where b.overdue) desc,"
                + "          count(*) desc, c.channel_code asc, b.destination_country asc",
            binds.toArray())) {
      out.add(toCell(r));
    }
    return out;
  }

  /** 交叉格分页（真分页 + 真筛选）。 */
  private TimelinessMatrixPage matrixPage(
      TimelinessWindow w, UUID channelId, String countryCode, String sort, int page, int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);
    List<Object> binds = baseBinds(w);
    String filters = baseFilters(channelId, countryCode, binds);
    String tail =
        " group by b.channel_id, c.channel_code, c.channel_name, b.destination_country";

    long total =
        sql.fetchOne(
                "select count(*) from ("
                    + BASE_CTE
                    + filters
                    + " ) "
                    + CELL_AGGREGATE
                    + tail
                    + ") t",
                binds.toArray())
            .get(0, Long.class);

    String order = cellOrderBy(sort);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<TimelinessMatrixRow> rows = new ArrayList<>();
    for (Record r :
        sql.fetch(
            BASE_CTE
                + filters
                + " ) "
                + CELL_AGGREGATE
                + tail
                + order
                + " limit ? offset ?",
            pageBinds.toArray())) {
      Cell cell = toCell(r);
      rows.add(toMatrixRow(cell, DEFAULT_MIN_SAMPLE));
    }
    return new TimelinessMatrixPage(
        rows,
        p,
        s,
        total,
        (int) Math.ceil(total / (double) s),
        w,
        totals(w, channelId, countryCode).toModel(),
        "报表按「渠道 × 国家」交叉给；合计行只是总体口径，"
            + "不能拿来代替某一个渠道在某一个国家的表现。");
  }

  private static String cellOrderBy(String sort) {
    return switch (text(sort)) {
      case "SHIPPED_DESC" -> " order by count(*) desc, c.channel_code asc, b.destination_country asc";
      case "RATE_DESC" ->
          " order by (case when count(*) filter (where b.on_time or b.overdue) > 0"
              + " then (count(*) filter (where b.overdue))::numeric"
              + " / (count(*) filter (where b.on_time or b.overdue))::numeric"
              + " else 0 end) desc, count(*) desc";
      case "CHANNEL_ASC" -> " order by c.channel_code asc, b.destination_country asc";
      default ->
          " order by count(*) filter (where b.overdue) desc, count(*) desc,"
              + "          c.channel_code asc, b.destination_country asc";
    };
  }

  private TimelinessMatrixRow toMatrixRow(Cell cell, int minSample) {
    Score score = scoreOf(cell, minSample);
    return new TimelinessMatrixRow(
        cell.channelId(),
        cell.channelCode(),
        cell.channelName(),
        cell.countryCode(),
        cell.shippedCount(),
        cell.deliveredCount(),
        cell.comparableCount(),
        cell.onTimeCount(),
        cell.overdueCount(),
        cell.noPromiseCount(),
        cell.pendingCount(),
        cell.returnedCount(),
        cell.deliveryRate(),
        cell.onTimeRate(),
        cell.overdueRate(),
        hours(cell.avgTransitHours()),
        hours(cell.avgDeviationHours()),
        hours(cell.stddevTransitHours()),
        hours(cell.p90TransitHours()),
        hours(cell.maxOverdueHours()),
        score.value(),
        score.grade(),
        score.sufficient());
  }

  /** 评分：0~100，样本不足 ⇒ 不可比（null）而不是 0 分。 */
  record Score(BigDecimal value, String grade, boolean sufficient) {}

  /**
   * 评分公式（口径写在代码里，不焊在 DDL 上）。
   *
   * <p>{@code 30 × 妥投率 + 50 × 达成率 + 20 × 准时因子}：
   *
   * <ul>
   *   <li>妥投率（发出后签投的比例）占 30；</li>
   *   <li>达成率（有承诺可比的票里准点的比例）占 50 —— <b>最高权重</b>，
   *       因为这个模块的职责就是时效；</li>
   *   <li>准时因子 = {@code clamp(1 − 平均偏差小时 / 24, 0, 1)} 占 20：
   *       平均比承诺晚一天 ⇒ 这一项 0 分；平均提前 ⇒ 满分封顶。</li>
   * </ul>
   *
   * <p>🔴 <b>样本不足 ⇒ 分数为 null</b>：可比票数（已签收且有承诺）不足门槛时，
   * 这一格<b>不可比</b>。0 分是「比过了，确实最差」，null 是「压根没资格比」，
   * 两者混起来会把「样本太少」误读成「表现极差」。
   */
  static Score scoreOf(Cell cell, int minSample) {
    if (cell.comparableCount() < minSample) {
      return new Score(null, "INSUFFICIENT", false);
    }
    BigDecimal delivery = cell.deliveryRate() == null ? BigDecimal.ZERO : cell.deliveryRate();
    BigDecimal onTime = cell.onTimeRate() == null ? BigDecimal.ZERO : cell.onTimeRate();
    BigDecimal deviation =
        cell.avgDeviationHours() == null
            ? BigDecimal.ZERO
            : cell.avgDeviationHours().max(BigDecimal.ZERO);
    BigDecimal punctuality =
        BigDecimal.ONE
            .subtract(
                deviation.divide(PUNCTUALITY_TOLERANCE_HOURS, 8, RoundingMode.HALF_UP))
            .max(BigDecimal.ZERO)
            .min(BigDecimal.ONE);
    BigDecimal raw =
        delivery
            .multiply(WEIGHT_DELIVERY)
            .add(onTime.multiply(WEIGHT_ON_TIME))
            .add(punctuality.multiply(WEIGHT_PUNCTUALITY));
    BigDecimal value = raw.setScale(4, RoundingMode.HALF_UP);
    String grade =
        value.compareTo(new BigDecimal("90")) >= 0
            ? "EXCELLENT"
            : value.compareTo(new BigDecimal("75")) >= 0
                ? "GOOD"
                : value.compareTo(new BigDecimal("60")) >= 0 ? "FAIR" : "POOR";
    return new Score(value, grade, true);
  }

  /** 全量合计（可带渠道 / 国家筛选）。 */
  private Totals totals(TimelinessWindow w, UUID channelId, String countryCode) {
    List<Object> binds = baseBinds(w);
    String filters = baseFilters(channelId, countryCode, binds);
    Record r =
        sql.fetchOne(BASE_CTE + filters + " ) " + TOTAL_AGGREGATE, binds.toArray());
    return new Totals(
        intOf(r, "shipped_count", 0),
        intOf(r, "delivered_count", 0),
        intOf(r, "comparable_count", 0),
        intOf(r, "on_time_count", 0),
        intOf(r, "overdue_count", 0),
        intOf(r, "no_promise_count", 0),
        intOf(r, "pending_count", 0),
        intOf(r, "returned_count", 0),
        r.get("avg_transit_hours", BigDecimal.class),
        r.get("avg_deviation_hours", BigDecimal.class),
        r.get("stddev_transit_hours", BigDecimal.class),
        r.get("p90_transit_hours", BigDecimal.class),
        r.get("max_overdue_hours", BigDecimal.class));
  }

  /** 概览里用的「没归到渠道 / 没国家」的票数。 */
  private int[] attributionCounts(TimelinessWindow w) {
    List<Object> binds = baseBinds(w);
    Record r =
        sql.fetchOne(
            BASE_CTE
                + " ) select"
                + " count(*) filter (where b.channel_id is null) as unattributed_count,"
                + " count(*) filter (where b.destination_country = '') as unclassified_count"
                + " from base b",
            binds.toArray());
    return new int[] {
      intOf(r, "unattributed_count", 0), intOf(r, "unclassified_count", 0)
    };
  }

  // ================================================================
  // 概览
  // ================================================================

  public LogisticsTimelinessOverview overview(String from, String to) {
    TimelinessWindow w = window(from, to);
    Totals totals = totals(w, null, null);
    int[] attribution = attributionCounts(w);
    List<Cell> cells = allCells(w, null, null);
    Cell worst = null;
    for (Cell cell : cells) {
      if (cell.overdueCount() == 0) {
        continue;
      }
      if (worst == null || cell.overdueCount() > worst.overdueCount()) {
        worst = cell;
      }
    }
    return new LogisticsTimelinessOverview(
        w,
        totals.toModel(),
        (int) cells.stream().map(c -> c.channelId()).distinct().count(),
        (int) cells.stream().map(c -> c.countryCode()).distinct().count(),
        cells.size(),
        attribution[0],
        attribution[1],
        totals.shippedCount(),
        worst == null ? "" : worst.channelCode(),
        worst == null ? "" : worst.countryCode(),
        worst == null ? null : worst.overdueRate(),
        "概览是**全量口径**（不受列表筛选与分页影响）；"
            + "要看「某个渠道在某个国家」的时效，去交叉报表页。",
        "事实源是上游 10.3.1.1 的轨迹主体表（渠道服务同库），不跨服务读表。",
        "下游 10.1.2.2 比价与 9.5.1.1 订单时效尚未接本模块产出（capabilities 里标了）。");
  }

  // ================================================================
  // 交叉报表
  // ================================================================

  public TimelinessMatrixPage matrix(
      String from,
      String to,
      String channelId,
      String countryCode,
      String sort,
      int page,
      int size) {
    TimelinessWindow w = window(from, to);
    UUID channel = parseUuid(channelId);
    return matrixPage(w, channel, text(countryCode).toUpperCase(Locale.ROOT), sort, page, size);
  }

  private static UUID parseUuid(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "渠道 ID 必须是 UUID");
    }
  }

  // ================================================================
  // 旺季与趋势
  // ================================================================

  public TimelinessMonthlyReport monthly(
      String from, String to, String channelId, String countryCode) {
    TimelinessWindow w = window(from, to);
    UUID channel = parseUuid(channelId);
    List<Object> binds = baseBinds(w);
    String filters = baseFilters(channel, text(countryCode).toUpperCase(Locale.ROOT), binds);

    List<Record> rows =
        sql.fetch(
            BASE_CTE
                + filters
                + " ) select"
                + " to_char(date_trunc('month', b.shipped_at at time zone 'UTC'), 'YYYY-MM')"
                + "   as month_key,"
                + " count(*) as shipped_count,"
                + " count(*) filter (where b.delivered) as delivered_count,"
                + " count(*) filter (where b.on_time or b.overdue) as comparable_count,"
                + " count(*) filter (where b.on_time) as on_time_count,"
                + " count(*) filter (where b.overdue) as overdue_count,"
                + " count(*) filter (where b.no_promise) as no_promise_count,"
                + " count(*) filter (where b.pending) as pending_count,"
                + " count(*) filter (where b.returned) as returned_count,"
                + " avg(b.transit_hours) as avg_transit_hours,"
                + " avg(b.deviation_hours) as avg_deviation_hours,"
                + " stddev_samp(b.transit_hours) as stddev_transit_hours,"
                + " percentile_disc(0.9) within group (order by b.transit_hours)"
                + "   filter (where b.transit_hours is not null) as p90_transit_hours"
                + " from base b group by 1 order by 1",
            binds.toArray());

    Totals baseline = totals(w, channel, text(countryCode).toUpperCase(Locale.ROOT));
    List<TimelinessMonthlyRow> out = new ArrayList<>();
    int peakMonths = 0;
    for (Record r : rows) {
      BigDecimal monthOverdue = rate(intOf(r, "overdue_count", 0), intOf(r, "comparable_count", 0));
      BigDecimal baselineOverdue = rate(baseline.overdueCount(), baseline.comparableCount());
      boolean peak =
          monthOverdue != null
              && baselineOverdue != null
              && monthOverdue.compareTo(baselineOverdue) > 0;
      if (peak) {
        peakMonths++;
      }
      BigDecimal monthTransit = r.get("avg_transit_hours", BigDecimal.class);
      BigDecimal vsBaseline =
          monthTransit == null || baseline.avgTransitHours() == null
              ? null
              : monthTransit.subtract(baseline.avgTransitHours());
      out.add(
          new TimelinessMonthlyRow(
              r.get("month_key", String.class),
              intOf(r, "shipped_count", 0),
              intOf(r, "delivered_count", 0),
              intOf(r, "comparable_count", 0),
              intOf(r, "on_time_count", 0),
              intOf(r, "overdue_count", 0),
              intOf(r, "no_promise_count", 0),
              intOf(r, "pending_count", 0),
              intOf(r, "returned_count", 0),
              rate(intOf(r, "delivered_count", 0), intOf(r, "shipped_count", 0)),
              rate(intOf(r, "on_time_count", 0), intOf(r, "comparable_count", 0)),
              monthOverdue,
              hours(monthTransit),
              hours(r.get("avg_deviation_hours", BigDecimal.class)),
              hours(r.get("stddev_transit_hours", BigDecimal.class)),
              hours(r.get("p90_transit_hours", BigDecimal.class)),
              hours(vsBaseline),
              peak));
    }
    return new TimelinessMonthlyReport(
        out,
        w,
        baseline.toModel(),
        peakMonths,
        "月份按 **UTC** 分桶（换台数据库换时区，报表上的月份不会整个错位）；"
            + "「旺季」= 该月超时率高于窗口基线（基线是窗口内加权口径，不是各月平均的平均）。");
  }

  // ================================================================
  // 异常集中分析
  // ================================================================

  public TimelinessAnomalyReport anomalies(String from, String to) {
    TimelinessWindow w = window(from, to);
    List<Cell> cells = allCells(w, null, null);
    List<Cell> withOverdue = new ArrayList<>();
    int totalOverdue = 0;
    for (Cell cell : cells) {
      if (cell.overdueCount() > 0) {
        withOverdue.add(cell);
        totalOverdue += cell.overdueCount();
      }
    }
    withOverdue.sort(
        (a, b) -> {
          int byCount = Integer.compare(b.overdueCount(), a.overdueCount());
          return byCount != 0 ? byCount : a.countryCode().compareTo(b.countryCode());
        });

    List<TimelinessAnomalyCell> out = new ArrayList<>();
    int running = 0;
    int paretoCellCount = 0;
    int paretoOverdueCount = 0;
    for (int i = 0; i < withOverdue.size(); i++) {
      Cell cell = withOverdue.get(i);
      // 帕累托前沿的判据是「**加这一格之前**累计还没到阈值」——
      // 也就是说跨过阈值的那一格**必须留在前沿里**（把它切掉，前沿就不是
      // 「贡献了大部分超时的那些格」而是「差一点点就到阈值的那些格」）。
      BigDecimal before = rate(running, totalOverdue);
      boolean inPareto = before.compareTo(PARETO_THRESHOLD) < 0;
      running += cell.overdueCount();
      BigDecimal contribution = rate(cell.overdueCount(), totalOverdue);
      BigDecimal cumulative = rate(running, totalOverdue);
      if (inPareto) {
        paretoCellCount++;
        paretoOverdueCount += cell.overdueCount();
      }
      out.add(
          new TimelinessAnomalyCell(
              i + 1,
              cell.channelId(),
              cell.channelCode(),
              cell.channelName(),
              cell.countryCode(),
              cell.overdueCount(),
              cell.deliveredCount(),
              cell.overdueRate(),
              hours(cell.avgTransitHours()),
              hours(cell.avgDeviationHours()),
              hours(cell.maxOverdueHours()),
              contribution,
              cumulative,
              inPareto));
    }

    Map<String, Integer> byChannel = new LinkedHashMap<>();
    Map<String, Integer> byCountry = new LinkedHashMap<>();
    for (Cell cell : withOverdue) {
      byChannel.merge(cell.channelCode(), cell.overdueCount(), Integer::sum);
      byCountry.merge(cell.countryCode(), cell.overdueCount(), Integer::sum);
    }
    List<TimelinessAnomalyGroup> channelGroups = anomalyGroups(byChannel, totalOverdue);
    List<TimelinessAnomalyGroup> countryGroups = anomalyGroups(byCountry, totalOverdue);

    BigDecimal topOne = out.isEmpty() ? null : out.get(0).contributionRate();
    BigDecimal topThree =
        out.isEmpty()
            ? null
            : rate(
                out.stream().limit(3).mapToInt(c -> c.overdueCount()).sum(), totalOverdue);

    return new TimelinessAnomalyReport(
        out,
        channelGroups,
        countryGroups,
        totalOverdue,
        topOne,
        topThree,
        PARETO_THRESHOLD,
        paretoCellCount,
        paretoOverdueCount,
        w,
        "贡献率与累计贡献率都是在**渠道 × 国家**的格上算的 —— "
            + "所以「哪个渠道在哪个国家拖后腿」能被直接读出来，"
            + "而不是只剩一句「某渠道表现差」。");
  }

  private static List<TimelinessAnomalyGroup> anomalyGroups(
      Map<String, Integer> source, int total) {
    List<TimelinessAnomalyGroup> out = new ArrayList<>();
    List<Map.Entry<String, Integer>> entries = new ArrayList<>(source.entrySet());
    entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
    for (Map.Entry<String, Integer> entry : entries) {
      out.add(
          new TimelinessAnomalyGroup(
              entry.getKey(),
              entry.getKey(),
              entry.getValue(),
              rate(entry.getValue(), total)));
    }
    return out;
  }

  // ================================================================
  // 渠道时效评分（实时计算）
  // ================================================================

  public TimelinessScorePage scores(
      String from, String to, String channelId, String countryCode, int page, int size) {
    TimelinessWindow w = window(from, to);
    UUID channel = parseUuid(channelId);
    String country = text(countryCode).toUpperCase(Locale.ROOT);
    List<Cell> cells = allCells(w, channel, country);
    Map<String, OffsetDateTime> snapshots = snapshotTimes(w, channel, country);

    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);
    List<TimelinessScoreRow> all = new ArrayList<>();
    for (Cell cell : cells) {
      Score score = scoreOf(cell, DEFAULT_MIN_SAMPLE);
      all.add(
          new TimelinessScoreRow(
              cell.channelId(),
              cell.channelCode(),
              cell.channelName(),
              cell.countryCode(),
              cell.shippedCount(),
              cell.deliveredCount(),
              cell.comparableCount(),
              cell.onTimeCount(),
              cell.overdueCount(),
              cell.deliveryRate(),
              cell.onTimeRate(),
              cell.overdueRate(),
              hours(cell.avgTransitHours()),
              hours(cell.avgDeviationHours()),
              score.value(),
              score.grade(),
              score.sufficient(),
              DEFAULT_MIN_SAMPLE,
              snapshots.get(cell.channelId() + "|" + cell.countryCode())));
    }
    int offset = Math.min(p * s, all.size());
    int end = Math.min(offset + s, all.size());
    return new TimelinessScorePage(
        all.subList(offset, end),
        p,
        s,
        all.size(),
        (int) Math.ceil(all.size() / (double) s),
        w,
        snapshots.size(),
        "评分是**实时算**的；「最近落库时刻」为空表示这一格还没进过台账。"
            + "样本不足时评分是**不可比（null）而不是 0 分**。");
  }

  /** 某一档窗口下各格的最近落库时刻。 */
  private Map<String, OffsetDateTime> snapshotTimes(
      TimelinessWindow w, UUID channelId, String countryCode) {
    Map<String, OffsetDateTime> out = new LinkedHashMap<>();
    StringBuilder where =
        new StringBuilder(
            " where tenant_id = ? and window_start = ?::timestamptz and window_end = ?::timestamptz");
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    binds.add(w.from());
    binds.add(w.to());
    if (channelId != null) {
      where.append(" and channel_id = ?");
      binds.add(channelId);
    }
    String country = text(countryCode).toUpperCase(Locale.ROOT);
    if (!country.isEmpty()) {
      where.append(" and country_code = ?");
      binds.add(country);
    }
    for (Record r :
        sql.fetch(
            "select channel_id, country_code, max(computed_at) as computed_at"
                + " from logistics_timeliness_score"
                + where
                + " group by channel_id, country_code",
            binds.toArray())) {
      out.put(
          r.get("channel_id", UUID.class) + "|" + r.get("country_code", String.class),
          r.get("computed_at", OffsetDateTime.class));
    }
    return out;
  }

  // ================================================================
  // 评分快照台账（读）
  // ================================================================

  public TimelinessScoreSnapshotPage scoreSnapshots(
      String from, String to, String channelId, String countryCode, int page, int size) {
    TimelinessWindow w = window(from, to);
    UUID channel = parseUuid(channelId);
    String country = text(countryCode).toUpperCase(Locale.ROOT);
    StringBuilder where =
        new StringBuilder(
            " where t.tenant_id = ? and t.window_start = ?::timestamptz"
                + " and t.window_end = ?::timestamptz");
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    binds.add(w.from());
    binds.add(w.to());
    if (channelId != null) {
      where.append(" and t.channel_id = ?");
      binds.add(channelId);
    }
    if (!country.isEmpty()) {
      where.append(" and t.country_code = ?");
      binds.add(country);
    }
    long total =
        sql.fetchOne(
                "select count(*) from logistics_timeliness_score t" + where, binds.toArray())
            .get(0, Long.class);
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<TimelinessScoreSnapshotRow> rows = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select t.id, t.channel_id, c.channel_code, c.channel_name, t.country_code,"
                + "       t.window_start, t.window_end, t.window_days,"
                + "       t.shipped_count, t.delivered_count, t.comparable_count,"
                + "       t.on_time_count, t.overdue_count, t.no_promise_count,"
                + "       t.pending_count, t.returned_count,"
                + "       t.delivery_rate, t.on_time_rate, t.overdue_rate,"
                + "       t.avg_transit_hours, t.avg_deviation_hours, t.stddev_transit_hours,"
                + "       t.p90_transit_hours, t.max_overdue_hours,"
                + "       t.timeliness_score, t.score_grade, t.sample_sufficient,"
                + "       t.min_sample_threshold, t.computed_at"
                + "  from logistics_timeliness_score t"
                + "  left join logistics_channel_config c"
                + "    on c.tenant_id = t.tenant_id and c.id = t.channel_id"
                + where
                + " order by t.computed_at desc, c.channel_code asc, t.country_code asc"
                + " limit ? offset ?",
            pageBinds.toArray())) {
      rows.add(
          new TimelinessScoreSnapshotRow(
              r.get("id", UUID.class),
              r.get("channel_id", UUID.class),
              r.get("channel_code", String.class),
              r.get("channel_name", String.class),
              r.get("country_code", String.class),
              r.get("window_start", OffsetDateTime.class),
              r.get("window_end", OffsetDateTime.class),
              intOf(r, "window_days", 0),
              intOf(r, "shipped_count", 0),
              intOf(r, "delivered_count", 0),
              intOf(r, "comparable_count", 0),
              intOf(r, "on_time_count", 0),
              intOf(r, "overdue_count", 0),
              intOf(r, "no_promise_count", 0),
              intOf(r, "pending_count", 0),
              intOf(r, "returned_count", 0),
              r.get("delivery_rate", BigDecimal.class),
              r.get("on_time_rate", BigDecimal.class),
              r.get("overdue_rate", BigDecimal.class),
              r.get("avg_transit_hours", BigDecimal.class),
              r.get("avg_deviation_hours", BigDecimal.class),
              r.get("stddev_transit_hours", BigDecimal.class),
              r.get("p90_transit_hours", BigDecimal.class),
              r.get("max_overdue_hours", BigDecimal.class),
              r.get("timeliness_score", BigDecimal.class),
              r.get("score_grade", String.class),
              Boolean.TRUE.equals(r.get("sample_sufficient", Boolean.class)),
              intOf(r, "min_sample_threshold", 0),
              r.get("computed_at", OffsetDateTime.class)));
    }
    return new TimelinessScoreSnapshotPage(
        rows, p, s, total, (int) Math.ceil(total / (double) s));
  }

  // ================================================================
  // 重算并落库（唯一写入口）
  // ================================================================

  /**
   * 重算当前窗口的时效评分并落库。
   *
   * <p>幂等：同一档窗口重算一次 = <b>先删后插</b>（同一事务里），不会堆出第二份
   * 快照；窗口右边界固定到 UTC 自然日，所以同一天内反复重算落在同一个窗口键上。
   *
   * <p>🔴 落库的是<b>原始计数 + 统计量 + 评分</b>，比率由数据库的生成列算 ——
   * 绕过服务层直接写 SQL 也编不出一个与计数对不上的比率（见 V218）。
   */
  public TimelinessScoreSnapshotResult snapshotScores(
      String from, String to, Integer minSampleThreshold) {
    TimelinessWindow w = window(from, to);
    int minSample =
        minSampleThreshold == null || minSampleThreshold <= 0
            ? DEFAULT_MIN_SAMPLE
            : Math.min(minSampleThreshold, 100000);
    List<Cell> cells = allCells(w, null, null);
    List<String> notes = new ArrayList<>();

    sql.execute(
        "delete from logistics_timeliness_score"
            + " where tenant_id = ? and window_start = ?::timestamptz and window_end = ?::timestamptz",
        currentTenant(),
        w.from(),
        w.to());

    int written = 0;
    int insufficient = 0;
    OffsetDateTime computedAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    for (Cell cell : cells) {
      Score score = scoreOf(cell, minSample);
      if (!score.sufficient()) {
        insufficient++;
      }
      sql.execute(
          "insert into logistics_timeliness_score"
              + " (id,tenant_id,channel_id,country_code,window_start,window_end,window_days,"
              + "  shipped_count,delivered_count,comparable_count,on_time_count,overdue_count,"
              + "  no_promise_count,pending_count,returned_count,"
              + "  avg_transit_hours,avg_deviation_hours,stddev_transit_hours,p90_transit_hours,"
              + "  max_overdue_hours,timeliness_score,score_grade,min_sample_threshold,"
              + "  sample_sufficient,computed_at,created_at)"
              + " values (?,?,?,?,?::timestamptz,?::timestamptz,?,"
              + "  ?,?,?,?,?,?,?,?,"
              + "  ?::numeric,?::numeric,?::numeric,?::numeric,?::numeric,"
              + "  ?::numeric,?,?,?,?::timestamptz,now())",
          Ids.next(),
          currentTenant(),
          cell.channelId(),
          cell.countryCode(),
          w.from(),
          w.to(),
          w.days(),
          cell.shippedCount(),
          cell.deliveredCount(),
          cell.comparableCount(),
          cell.onTimeCount(),
          cell.overdueCount(),
          cell.noPromiseCount(),
          cell.pendingCount(),
          cell.returnedCount(),
          hours(cell.avgTransitHours()),
          hours(cell.avgDeviationHours()),
          hours(cell.stddevTransitHours()),
          hours(cell.p90TransitHours()),
          hours(cell.maxOverdueHours()),
          score.value(),
          score.grade(),
          minSample,
          score.sufficient(),
          computedAt);
      written++;
    }
    if (cells.isEmpty()) {
      notes.add("窗口内没有任何「有渠道 + 有国家」的已发货轨迹，评分台账为空 —— "
          + "这不是「全部准时」，是「没有可分析的样本」。");
    }
    if (insufficient > 0) {
      notes.add(
          insufficient + " 格样本不足（可比票数 < " + minSample + "），评分为**不可比（null）**而不是 0 分。");
    }
    return new TimelinessScoreSnapshotResult(
        w, cells.size(), written, insufficient, cells.size(), minSample, notes);
  }

  // ================================================================
  // 逐票明细
  // ================================================================

  public TimelinessSubjectPage subjects(
      String from,
      String to,
      String channelId,
      String countryCode,
      String status,
      int page,
      int size) {
    TimelinessWindow w = window(from, to);
    UUID channel = parseUuid(channelId);
    String country = text(countryCode).toUpperCase(Locale.ROOT);
    String state = text(status).toUpperCase(Locale.ROOT);
    if (!state.isEmpty() && !TIMELINESS_STATUSES.contains(state)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "时效状态只能是 ON_TIME / OVERDUE / NO_PROMISE / IN_TRANSIT");
    }

    List<Object> binds = baseBinds(w);
    String filters = baseFilters(channel, country, binds);
    StringBuilder where = new StringBuilder(" where 1=1");
    switch (state) {
      case "ON_TIME" -> where.append(" and b.on_time");
      case "OVERDUE" -> where.append(" and b.overdue");
      case "NO_PROMISE" -> where.append(" and b.no_promise");
      case "IN_TRANSIT" -> where.append(" and b.pending");
      default -> {
        // 不过滤
      }
    }

    long total =
        sql.fetchOne(
                BASE_CTE + filters + " ) select count(*) from base b" + where, binds.toArray())
            .get(0, Long.class);
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    List<TimelinessSubjectRow> rows = new ArrayList<>();
    for (Record r :
        sql.fetch(
            BASE_CTE
                + filters
                + " ) select b.id, b.master_waybill_no, b.carrier_code, b.channel_id,"
                + "       c.channel_code, c.channel_name, b.destination_country, b.service_level,"
                + "       b.shipped_at, b.latest_occurred_at, b.promised_eta_at,"
                + "       b.transit_hours, b.deviation_hours,"
                + "       b.on_time, b.overdue, b.no_promise, b.pending"
                + "  from base b"
                + "  left join logistics_channel_config c"
                + "    on c.tenant_id = b.tenant_id and c.id = b.channel_id"
                + where
                + " order by case when b.overdue then 0 when b.on_time then 2 else 1 end,"
                + "          b.deviation_hours desc nulls last, b.shipped_at desc"
                + " limit ? offset ?",
            pageBinds.toArray())) {
      rows.add(
          new TimelinessSubjectRow(
              r.get("id", UUID.class),
              r.get("master_waybill_no", String.class),
              r.get("carrier_code", String.class),
              r.get("channel_id", UUID.class),
              r.get("channel_code", String.class),
              r.get("channel_name", String.class),
              r.get("destination_country", String.class),
              r.get("service_level", String.class),
              r.get("shipped_at", OffsetDateTime.class),
              r.get("latest_occurred_at", OffsetDateTime.class),
              r.get("promised_eta_at", OffsetDateTime.class),
              hours(r.get("transit_hours", BigDecimal.class)),
              hours(r.get("deviation_hours", BigDecimal.class)),
              subjectStatus(r),
              timelinessStatusLabel(subjectStatus(r))));
    }
    return new TimelinessSubjectPage(
        rows,
        p,
        s,
        total,
        (int) Math.ceil(total / (double) s),
        w,
        "逐票明细是**交叉报表的下一层**：想核实某一格为什么超时，往这里看。");
  }

  private static String subjectStatus(Record r) {
    if (Boolean.TRUE.equals(r.get("on_time", Boolean.class))) {
      return "ON_TIME";
    }
    if (Boolean.TRUE.equals(r.get("overdue", Boolean.class))) {
      return "OVERDUE";
    }
    if (Boolean.TRUE.equals(r.get("no_promise", Boolean.class))) {
      return "NO_PROMISE";
    }
    return "IN_TRANSIT";
  }

  // ================================================================
  // 展示文案
  // ================================================================

  static String timelinessStatusLabel(String status) {
    return switch (text(status)) {
      case "ON_TIME" -> "准点送达";
      case "OVERDUE" -> "超时送达";
      case "NO_PROMISE" -> "无承诺可比";
      case "IN_TRANSIT" -> "在途未签收";
      default -> "未知状态";
    };
  }

  static String scoreGradeLabel(String grade) {
    return switch (text(grade)) {
      case "EXCELLENT" -> "优秀";
      case "GOOD" -> "良好";
      case "FAIR" -> "一般";
      case "POOR" -> "较差";
      case "INSUFFICIENT" -> "样本不足";
      default -> "未评级";
    };
  }
}
