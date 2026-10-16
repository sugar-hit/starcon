package com.starcon.channel;

import static com.starcon.channel.ChannelScoringEngine.Candidate;
import static com.starcon.channel.ChannelScoringEngine.CostSpan;
import static com.starcon.channel.ChannelScoringEngine.Span;
import static com.starcon.channel.ChannelScoringEngine.Weights;
import static com.starcon.channel.RateComparisonModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 10.1.2.2 渠道比价。
 *
 * <p>蓝图：「在满足时效与限制的渠道中按成本/时效/成功率多目标排序，支持按店铺预设优先级自动选择。
 * 输入：比价条件；渠道评分（历史成功率、时效达成率、投诉率）。输出：排序后的推荐渠道 + 评分明细。
 * 关系：上游 = 10.1.2.1、10.6 时效分析；下游 = 9.3.2.1 分仓、18 自动化规则。
 * <b>只看价格选渠道是常见错误</b>：便宜但高丢件的渠道会推高售后与差评成本。」
 *
 * <p>本类做的事，按职责句逐项落点：
 *
 * <ul>
 *   <li><b>成本 / 时效 / 可承运性</b>：<b>不重算</b>。直接用上游 10.1.2.1 的
 *       {@link FreightCalculatorService}（引用已落库的试算单）或再走一次无副作用试算。
 *       限制规则与费率规则各只有一份 —— 本类再写一份，就意味着总有一份会漏掉「磁力」这类限制。
 *   <li><b>排序</b>：{@link ChannelScoringEngine} —— 纯函数，无数据库、无策略常量。
 *   <li><b>渠道评分</b>：{@code channel_rating_score}（租户可维护的数据，不是代码里的常量数组）。
 *   <li><b>店铺预设优先级</b>：{@code channel_shop_priority}。
 * </ul>
 *
 * <p>🔴 蓝图加粗句「只看价格选渠道是常见错误」在这里是<b>三个独立的闸</b>（投诉率红线、
 * 最小样本量、评分新鲜度），任何一个都足以拦住「便宜但高丢件」：
 * 被闸拦下的渠道<b>照样出现在对照表里</b>并给出完整得分，只是不许被自动选中 ——
 * 从表里删掉它会让人以为「它不存在」，而它明明最便宜。
 *
 * <p>🔴 数据来源纪律：渠道配置、费率、承运商<b>同库同服务</b>读（10.1.1.2 / 10.1.2.1 已落在
 * channel），本类**不复制**它们的任何一列；跨服务方向一律经授权 API，
 * <b>一条 SQL 都不碰别的服务的库</b>。
 */
@Service
public class RateComparisonService {

  /** 默认权重（成本 0.4 / 时效 0.3 / 可靠性 0.3）。落库后可复算，接口自述里也写着。 */
  private static final BigDecimal DEFAULT_COST = new BigDecimal("0.4");
  private static final BigDecimal DEFAULT_TRANSIT = new BigDecimal("0.3");
  private static final BigDecimal DEFAULT_RELIABILITY = new BigDecimal("0.3");

  /** 可靠性维度内部默认子权重：成功率 0.4 / 时效达成率 0.4 / 无投诉 0.2。 */
  private static final BigDecimal DEFAULT_SUCCESS = new BigDecimal("0.4");
  private static final BigDecimal DEFAULT_ON_TIME = new BigDecimal("0.4");
  private static final BigDecimal DEFAULT_COMPLAINT = new BigDecimal("0.2");

  private static final int DEFAULT_MAX_RATING_AGE_DAYS = 90;

  private final DSLContext sql;
  private final FreightCalculatorService freight;

  public RateComparisonService(DSLContext sql, FreightCalculatorService freight) {
    this.sql = sql;
    this.freight = freight;
  }

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String upper(String value) {
    return text(value).toUpperCase(Locale.ROOT);
  }

  private static long nz(Number value) {
    return value == null ? 0L : value.longValue();
  }

  private static Object[] concat(List<Object> binds, Object... tail) {
    Object[] all = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) {
      all[i] = binds.get(i);
    }
    System.arraycopy(tail, 0, all, binds.size(), tail.length);
    return all;
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 在进入事务时绑定）。 */
  private UUID currentTenant() {
    String raw = one(String.class, "select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /** 跨租户与不存在返回**同一个 404 且理由逐字相同**（照抄 10.1.1.2 / 10.1.2.1 同款口径）。 */
  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "比价单不存在");
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public RateComparisonCapabilities capabilities() {
    return new RateComparisonCapabilities(
        List.of(
            new RateCapabilityDimension(
                "cost_basis",
                "成本（上游 10.1.2.1 的运费试算对照表，本模块不重算钱）",
                true,
                "freight_quote / freight_quote_line（V140）",
                "",
                "重算一遍就会有两个算法，界面上与落库上的名次对不上"),
            new RateCapabilityDimension(
                "transit_basis",
                "时效（本次报价预计天数，优先取区间上界）",
                true,
                "freight_quote_line.transit_min_days / transit_max_days（V140）",
                "",
                "用渠道对某国的承诺时效代替本次报价时效，比价筛的就不是这一票了"),
            new RateCapabilityDimension(
                "eligibility",
                "限制与可承运性（禁限品类、电池/液体/磁力）",
                true,
                "上游 10.1.1.2 服务端判定 /channel/logistics-channels/eligibility",
                "",
                "重写限制规则就意味着总有一份会漏掉「磁力」这类限制"),
            new RateCapabilityDimension(
                "multi_objective",
                "多目标权重（成本 / 时效 / 可靠性）",
                true,
                "channel_comparison.cost_weight 等六列（V143）",
                "",
                "权重只写在前端不落库，事后无法复算当时为什么是这条排第一"),
            new RateCapabilityDimension(
                "channel_rating",
                "渠道评分（历史成功率 / 时效达成率 / 投诉率）",
                true,
                "channel_rating_score 原始计数 + DB 生成列比率（V143）",
                "",
                "比率是生成列，服务层写不进去 —— 缺了评分就只剩「按价格选」，正是蓝图加粗句点名的错误"),
            new RateCapabilityDimension(
                "rating_producer",
                "渠道评分的**自动**生产者（按渠道×国家交叉统计）",
                false,
                "待 10.6.2.1 时效分析（docs/product/capability-blueprint/10-logistics-and-freight.md）",
                "10.6.2.1",
                "当前只能人工登记（score_source=MANUAL_ENTERED）；10.6.2.1 上线后可直接回灌，"
                    + "表结构与排序逻辑都不需要改"),
            new RateCapabilityDimension(
                "auto_selection",
                "按店铺预设优先级自动选择",
                true,
                "channel_shop_priority（V143）",
                "",
                "预设里排第一但被风险闸拦下的渠道不自动选中 —— 预设是运营偏好，不是对差渠道的豁免"),
            new RateCapabilityDimension(
                "risk_gate",
                "投诉率红线闸",
                true,
                "channel_rating_score.max_complaint_rate_pct（V143）",
                "",
                "没有这道闸，「便宜但高丢件」会被直接选进 9.3.2.1 分仓"),
            new RateCapabilityDimension(
                "sample_gate",
                "最小样本量闸",
                true,
                "channel_rating_score.min_sample_threshold（V143）",
                "",
                "只发过 3 单全到不该和发过 3000 单的 98% 同等参与自动选择"),
            new RateCapabilityDimension(
                "freshness_gate",
                "评分新鲜度闸",
                true,
                "channel_rating_score.observed_at + 比价的 max_rating_age_days（V143）",
                "",
                "三个月前的评分不代表今天这条渠道"),
            new RateCapabilityDimension(
                "downstream_consumers",
                "下游消费方（分仓决策 / 自动化规则）",
                false,
                "待 9.3.2.1 分仓、18 自动化规则（docs/product/capability-blueprint/）",
                "9.3.2.1 / 18",
                "本模块把推荐结果落库并可引用；下游尚未按它建规则，比价单暂不会被自动消费")),
        List.of("SCORE_RANKED", "SHOP_PRESET_PRIORITY", "NO_ELIGIBLE_CHANNEL"),
        List.of(
            "RANKED",
            "EXCLUDED_INELIGIBLE",
            "EXCLUDED_TRANSIT",
            "EXCLUDED_CURRENCY",
            "EXCLUDED_NO_PRICE",
            "EXCLUDED_NO_TRANSIT",
            "EXCLUDED_NO_SCORE"),
        List.of("MANUAL_ENTERED", "TRANSIT_ANALYSIS", "HANDOVER_RECEIPT", "CARRIER_REPORT"),
        List.of("PASS", "REVIEW", "BLOCK"),
        "默认权重：成本 0.4 / 时效 0.3 / 可靠性 0.3；可靠性内部：成功率 0.4 / 时效达成率 0.4 / 无投诉 0.2。"
            + "权重是调用方的输入，不是代码里的常量 —— 归一化后的实际值随每张比价单落库，事后可复算。"
            + "「只看价格选渠道是常见错误」的直接对策：成本权重只是一个目标，可承运性、时效与"
            + "三个可靠性闸都能否决一条最便宜的渠道。",
        "三个闸（投诉率红线 / 最小样本量 / 评分新鲜度）**只挡自动选择，不挡评分**：被闸拦下的渠道"
            + "照样出现在对照表里并给出成本分 / 时效分 / 可靠性分与综合分，只是不允许被自动选中。"
            + "从表里删掉它会让人以为「它不存在」，而它明明最便宜 —— 那样运营就再也查不出"
            + "「为什么这条便宜的渠道没被选」。",
        "比价的成本来自上游 10.1.2.1 的**试算**，不是承运商账单：costBasis 恒为 RULE_ESTIMATE，"
            + "DB 层写不进实际结算口径。试算与实际的差异要进下游费用差异项，不能拿它当实际成本。",
        "数据来源：成本/时效/可承运性全部读上游 10.1.2.1 已落库的试算单（freight_quote*，V140）；"
            + "渠道评分读本服务的 channel_rating_score（V143，原始计数 + 生成列比率）；"
            + "店铺预设读 channel_shop_priority（V143）。本模块**不复制**渠道、费率与承运商的"
            + "任何一列，也不碰别的服务的库。");
  }

  // ==================================================================
  // 渠道评分（比价的输入之一）
  // ==================================================================

  private static final String RATING_COLUMNS =
      "r.id,r.channel_id,r.country_code,r.dispatched,r.delivered,r.on_time,r.complaint,"
          + "r.delivery_success_rate,r.on_time_rate,r.complaint_rate,r.window_from,r.window_to,"
          + "r.observed_at,r.score_source,r.source_ref,r.max_complaint_rate_pct,"
          + "r.min_sample_threshold,r.status,r.note,r.version,r.created_at,r.updated_at,"
          + "c.channel_code,c.channel_name,p.party_name";

  private static final String RATING_FROM =
      " from channel_rating_score r"
          + " join logistics_channel_config c on c.tenant_id = r.tenant_id and c.id = r.channel_id"
          + " join carrier_logistics_profile p on p.tenant_id = c.tenant_id and p.id = c.carrier_profile_id"
          + " where r.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid";

  private static final List<String> RATING_SORTS = List.of("CHANNEL", "COUNTRY", "RATE_DESC", "RECENT");

  public RatingOverview ratings(String channelId, String countryCode, String status, String sort) {
    if (!RATING_SORTS.contains(sort)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "排序方式只能是 " + String.join(" / ", RATING_SORTS));
    }
    StringBuilder where = new StringBuilder(RATING_FROM);
    List<Object> binds = new ArrayList<>();
    if (!text(channelId).isEmpty()) {
      where.append(" and r.channel_id = ?");
      binds.add(parseUuid(channelId));
    }
    String country = upper(countryCode);
    if (!country.isEmpty()) {
      where.append(" and r.country_code = ?");
      binds.add(country);
    }
    if (!text(status).isEmpty()) {
      where.append(" and r.status = ?");
      binds.add(text(status));
    }

    String orderBy =
        switch (sort) {
          case "COUNTRY" -> " order by r.country_code asc, c.channel_code asc";
          case "RATE_DESC" -> " order by r.delivery_success_rate desc nulls last, c.channel_code asc";
          case "RECENT" -> " order by r.observed_at desc, c.channel_code asc";
          default -> " order by c.channel_code asc, r.country_code asc";
        };

    List<ChannelRatingRow> rows =
        sql.fetch("select " + RATING_COLUMNS + where + orderBy, binds.toArray()).stream()
            .map(RateComparisonService::ratingRow)
            .toList();

    // 🔴 没有任何评分的渠道**显式列出来**（而不是让使用者在对照表里自己发现「怎么少了一条」）
    List<String> unrated =
        sql.fetch(
                "select c.channel_code from logistics_channel_config c"
                    + " join carrier_logistics_profile p on p.tenant_id = c.tenant_id"
                    + " and p.id = c.carrier_profile_id"
                    + " where c.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid"
                    + " and c.status = 'ACTIVE'"
                    + " and not exists (select 1 from channel_rating_score r"
                    + "   where r.tenant_id = c.tenant_id and r.channel_id = c.id and r.status = 'ACTIVE')"
                    + " order by c.channel_code asc")
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();

    return new RatingOverview(
        rows,
        (int) rows.stream().filter(r -> "ACTIVE".equals(r.status())).count(),
        (int) rows.stream().filter(r -> "MANUAL_ENTERED".equals(r.scoreSource())).count(),
        unrated,
        rows.isEmpty()
            ? "本租户还没有任何渠道评分。渠道评分是多目标排序的可靠性维度来源，"
                + "**没有它就只剩「按价格选」** —— 而按价格选渠道正是本模块要挡住的做法。"
                + "请先按渠道 × 目的国登记成功率 / 时效达成率 / 投诉率。"
            : "比率由原始计数在 DB 层派生（生成列），服务层写不进去；生效中的评分不允许就地改计数，"
                + "要改请先置为 SUPERSEDED 再登记一条新的，否则已发出的比价单再也复算不出来。");
  }

  private static ChannelRatingRow ratingRow(Record r) {
    return new ChannelRatingRow(
        r.get("id", UUID.class),
        r.get("channel_id", UUID.class),
        r.get("channel_code", String.class),
        r.get("channel_name", String.class),
        r.get("party_name", String.class),
        r.get("country_code", String.class),
        r.get("dispatched", Integer.class),
        r.get("delivered", Integer.class),
        r.get("on_time", Integer.class),
        r.get("complaint", Integer.class),
        r.get("delivery_success_rate", BigDecimal.class),
        r.get("on_time_rate", BigDecimal.class),
        r.get("complaint_rate", BigDecimal.class),
        r.get("window_from", OffsetDateTime.class),
        r.get("window_to", OffsetDateTime.class),
        r.get("observed_at", OffsetDateTime.class),
        r.get("score_source", String.class),
        r.get("source_ref", String.class),
        r.get("max_complaint_rate_pct", BigDecimal.class),
        r.get("min_sample_threshold", Integer.class),
        r.get("status", String.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /**
   * 登记 / 改一条渠道评分。
   *
   * <p>🔔 {@code SUPERSEDED} 的旧行 + 一条新的 ACTIVE 行，是「重新统计一轮」的标准做法；
   * 直接改生效行的计数会被 V143 trigger b 拒（已发出的比价单里存着当时那组比率）。
   */
  public ChannelRatingRow saveRating(SaveRatingRequest request) {
    UUID tenant = currentTenant();
    String country = upper(request.countryCode());
    if (!country.matches("^[A-Z]{2}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "国家必须是两位大写字母（如 DE / US）");
    }
    if (request.dispatched() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "已发运样本量必须 ≥ 1 —— 0 单算不出任何比率");
    }
    if (request.delivered() > request.dispatched()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已妥投数不能大于已发运数");
    }
    if (request.onTime() > request.delivered()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "按时妥投数不能大于已妥投数 —— 时效达成率的分母是已妥投单");
    }
    if (request.complaint() > request.dispatched()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "投诉数不能大于已发运数");
    }
    if (!request.windowTo().isAfter(request.windowFrom())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "统计窗口的结束时刻必须晚于开始时刻");
    }

    UUID id = request.ratingId() == null ? Ids.next() : request.ratingId();
    long currentVersion = 0;
    if (request.ratingId() != null) {
      Record existing =
          sql.fetchOne(
              "select version from channel_rating_score where tenant_id = ? and id = ?",
              tenant,
              request.ratingId());
      if (existing == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "渠道评分不存在");
      }
      currentVersion = existing.get("version", Long.class);
    }
    if (request.version() != currentVersion) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "渠道评分已被他人改动（当前版本 " + currentVersion + "），请刷新后重试");
    }

    sql.execute(
        "insert into channel_rating_score"
            + " (tenant_id,id,channel_id,country_code,dispatched,delivered,on_time,complaint,"
            + "  window_from,window_to,observed_at,score_source,source_ref,max_complaint_rate_pct,"
            + "  min_sample_threshold,status,note,version)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            + " on conflict (id) do update set"
            + "  dispatched=excluded.dispatched,delivered=excluded.delivered,"
            + "  on_time=excluded.on_time,complaint=excluded.complaint,"
            + "  window_from=excluded.window_from,window_to=excluded.window_to,"
            + "  observed_at=excluded.observed_at,score_source=excluded.score_source,"
            + "  source_ref=excluded.source_ref,"
            + "  max_complaint_rate_pct=excluded.max_complaint_rate_pct,"
            + "  min_sample_threshold=excluded.min_sample_threshold,status=excluded.status,"
            + "  note=excluded.note,version=channel_rating_score.version+1,"
            + "  updated_at=now()"
            + " where channel_rating_score.tenant_id = ?",
        tenant, id, request.channelId(), country, request.dispatched(), request.delivered(),
        request.onTime(), request.complaint(), timestamp(request.windowFrom()),
        timestamp(request.windowTo()), timestamp(request.observedAt()), text(request.scoreSource()),
        text(request.sourceRef()),
        request.maxComplaintRatePct() == null ? new BigDecimal("5") : request.maxComplaintRatePct(),
        request.minSampleThreshold() == null ? 30 : request.minSampleThreshold(),
        text(request.status()), text(request.note()), currentVersion, tenant);

    return ratingRow(
        sql.fetchOne("select " + RATING_COLUMNS + RATING_FROM + " and r.id = ?", id));
  }

  // ==================================================================
  // 店铺预设优先级
  // ==================================================================

  private static final String PRIORITY_COLUMNS =
      "p.id,p.shop_code,p.channel_id,p.priority_no,p.auto_select_allowed,p.min_composite_score,"
          + "p.status,p.note,p.version,p.created_at,p.updated_at,c.channel_code,c.channel_name,"
          + "pr.party_name";

  private static final String PRIORITY_FROM =
      " from channel_shop_priority p"
          + " join logistics_channel_config c on c.tenant_id = p.tenant_id and c.id = p.channel_id"
          + " join carrier_logistics_profile pr on pr.tenant_id = c.tenant_id"
          + "   and pr.id = c.carrier_profile_id"
          + " where p.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid";

  public ShopPriorityOverview shopPriorities(String shopCode, String status) {
    StringBuilder where = new StringBuilder(PRIORITY_FROM);
    List<Object> binds = new ArrayList<>();
    String shop = upper(shopCode);
    if (!shop.isEmpty()) {
      where.append(" and p.shop_code = ?");
      binds.add(shop);
    }
    if (!text(status).isEmpty()) {
      where.append(" and p.status = ?");
      binds.add(text(status));
    }
    where.append(" order by p.shop_code asc, p.priority_no asc, c.channel_code asc");

    List<ShopPriorityRow> rows =
        sql.fetch("select " + PRIORITY_COLUMNS + where, binds.toArray()).stream()
            .map(
                r ->
                    new ShopPriorityRow(
                        r.get("id", UUID.class),
                        r.get("shop_code", String.class),
                        r.get("channel_id", UUID.class),
                        r.get("channel_code", String.class),
                        r.get("channel_name", String.class),
                        r.get("party_name", String.class),
                        r.get("priority_no", Integer.class),
                        r.get("auto_select_allowed", Boolean.class),
                        r.get("min_composite_score", BigDecimal.class),
                        r.get("status", String.class),
                        r.get("note", String.class),
                        r.get("version", Long.class),
                        r.get("created_at", OffsetDateTime.class),
                        r.get("updated_at", OffsetDateTime.class)))
            .toList();

    String firstShop = rows.isEmpty() ? shop : rows.get(0).shopCode();
    return new ShopPriorityOverview(
        firstShop,
        rows,
        (int) rows.stream().filter(r -> "ACTIVE".equals(r.status())).count(),
        rows.isEmpty()
            ? (shop.isEmpty()
                ? "本租户还没有任何店铺预设优先级。不配置预设时，比价按综合分排名自动选。"
                : "店铺 " + shop + " 没有预设优先级。不配置预设时，比价按综合分排名自动选。")
            : "生效中的序号必须连续覆盖 1..N —— 序号跳空说明中间那一位被删了，"
                + "而「第 2 位是谁」从此无人知晓。生效中的预设不能改店铺或渠道，要换请先停用再登记。");
  }

  public ShopPriorityRow saveShopPriority(SaveShopPriorityRequest request) {
    UUID tenant = currentTenant();
    String shop = upper(request.shopCode());
    if (!shop.matches("^[A-Z0-9_-]{1,64}$")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "店铺编码只接受字母数字下划线与短横线（大写，最多 64 位）");
    }
    long currentVersion = 0;
    if (request.priorityId() != null) {
      Record existing =
          sql.fetchOne(
              "select version from channel_shop_priority where tenant_id = ? and id = ?",
              tenant,
              request.priorityId());
      if (existing == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "店铺预设优先级不存在");
      }
      currentVersion = existing.get("version", Long.class);
    }
    if (request.version() != currentVersion) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "店铺预设优先级已被他人改动（当前版本 " + currentVersion + "），请刷新后重试");
    }

    UUID id = request.priorityId() == null ? Ids.next() : request.priorityId();
    sql.execute(
        "insert into channel_shop_priority"
            + " (tenant_id,id,shop_code,channel_id,priority_no,auto_select_allowed,"
            + "  min_composite_score,status,note,version)"
            + " values (?,?,?,?,?,?,?,?,?,?)"
            + " on conflict (id) do update set"
            + "  priority_no=excluded.priority_no,"
            + "  auto_select_allowed=excluded.auto_select_allowed,"
            + "  min_composite_score=excluded.min_composite_score,status=excluded.status,"
            + "  note=excluded.note,version=channel_shop_priority.version+1,"
            + "  updated_at=now()"
            + " where channel_shop_priority.tenant_id = ?",
        tenant, id, shop, request.channelId(), request.priorityNo(),
        request.autoSelectAllowed(),
        request.minCompositeScore() == null ? BigDecimal.ZERO : request.minCompositeScore(),
        text(request.status()), text(request.note()), currentVersion, tenant);

    return shopPriorities(shop, null).priorities().stream()
        .filter(r -> r.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "店铺预设优先级不存在"));
  }

  // ==================================================================
  // 比价
  // ==================================================================

  /** 比价（无副作用）：不落库。人工决策看一眼排序不该在库里留下单据。 */
  public CompareResult compare(CompareRequest request) {
    return compute(request, false);
  }

  /** 比价并落库：给下游 9.3.2.1 分仓与 18 自动化规则引用。 */
  public CompareResult saveComparison(CompareRequest request) {
    return compute(request, true);
  }

  private record Basis(
      UUID quoteId,
      FreightCalculatorModels.FreightEstimateResult estimate,
      String country,
      String shopCode,
      BigDecimal weightKg,
      int pieces,
      BigDecimal declaredValue,
      String declaredCurrency,
      List<String> categories) {}

  /**
   * 取本次比价的成本 / 时效 / 可承运性基线。
   *
   * <p>两条路，产出同构：给 {@code sourceQuoteId} 就读上游已落库的试算单；给
   * {@code parcel} 就走一次<b>无副作用</b>试算。两条路都不在本模块里重算钱。
   */
  private Basis basis(CompareRequest request) {
    if (request.sourceQuoteId() != null) {
      UUID quoteId = request.sourceQuoteId();
      FreightCalculatorModels.FreightQuoteDetail detail;
      try {
        detail = freight.quote(quoteId.toString());
      } catch (ResponseStatusException e) {
        if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
          throw new ResponseStatusException(HttpStatus.NOT_FOUND, "比价单不存在");
        }
        throw e;
      }
      var row = detail.quote();
      String country = row.destCountry();
      return new Basis(
          quoteId,
          new FreightCalculatorModels.FreightEstimateResult(
              row.quotedAt(),
              row.destCountry(),
              row.shopCode(),
              row.weightKg(),
              volumeOf(row),
              row.pieces(),
              // 引用上游试算单时逐渠道明细在 detail 里，这里用它重建对照表形状
              row.channelCount(),
              (int) detail.lines().stream().filter(FreightCalculatorModels.FreightQuoteLineRow::quoteable).count(),
              row.passCount(),
              row.reviewCount(),
              row.blockCount(),
              row.currency(),
              row.mixedCurrency(),
              row.cheapestTotal(),
              row.dearestTotal(),
              row.costBasis(),
              quoteId,
              detail.lines().stream()
                  .map(
                      l ->
                          new FreightCalculatorModels.FreightEstimateLine(
                              l.channelId(),
                              l.channelCode(),
                              l.channelName(),
                              l.carrierName(),
                              l.eligibility(),
                              List.of(),
                              l.rateSource(),
                              l.basis(),
                              l.chargeableQty(),
                              l.volumetricQty(),
                              l.baseCharge(),
                              l.fuelFee(),
                              l.remoteFee(),
                              l.peakFee(),
                              l.insuranceFee(),
                              l.totalCharge(),
                              l.currency(),
                              l.transitMinDays(),
                              l.transitMaxDays(),
                              l.quoteable(),
                              List.of(),
                              List.of()))
                  .toList(),
              ""),
          country,
          row.shopCode(),
          row.weightKg(),
          row.pieces(),
          row.declaredValue(),
          row.declaredCurrency(),
          row.categoryCodes());
    }

    if (request.parcel() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "比价条件要么给 sourceQuoteId（引用上游 10.1.2.1 的试算单），要么给 parcel（包裹参数）");
    }
    FreightCalculatorModels.FreightEstimateResult estimate = freight.estimate(request.parcel());
    return new Basis(
        null,
        estimate,
        estimate.destCountry(),
        estimate.shopCode(),
        estimate.weightKg(),
        estimate.pieces(),
        request.parcel().declaredValue(),
        request.parcel().declaredCurrency(),
        request.parcel().categoryCodes());
  }

  private static BigDecimal volumeOf(FreightCalculatorModels.FreightQuoteRow row) {
    if (row.lengthCm() == null || row.widthCm() == null || row.heightCm() == null) {
      return null;
    }
    return row.lengthCm().multiply(row.widthCm()).multiply(row.heightCm());
  }

  private CompareResult compute(CompareRequest request, boolean persist) {
    UUID tenant = currentTenant();

    Basis basis = basis(request);
    String country = basis.country();
    // 店铺编码：请求里给包裹参数时以它为准，否则以引用的试算单上的为准
    String shop = shopFrom(request, basis);

    OffsetDateTime comparedAt =
        request.comparedAt() == null ? OffsetDateTime.now() : request.comparedAt();
    int maxRatingAgeDays =
        request.maxRatingAgeDays() == null ? DEFAULT_MAX_RATING_AGE_DAYS : request.maxRatingAgeDays();
    boolean requirePassOnly = Boolean.TRUE.equals(request.requirePassOnly());
    boolean applyShopPriority = Boolean.TRUE.equals(request.applyShopPriority());

    Weights weights;
    try {
      Weights base =
          ChannelScoringEngine.INSTANCE
              .normalize(
                  request.costWeight() == null ? DEFAULT_COST : request.costWeight(),
                  request.transitWeight() == null ? DEFAULT_TRANSIT : request.transitWeight(),
                  request.reliabilityWeight() == null ? DEFAULT_RELIABILITY : request.reliabilityWeight());
      weights =
          ChannelScoringEngine.INSTANCE.withReliabilityWeights(
              base,
              request.successRateWeight() == null ? DEFAULT_SUCCESS : request.successRateWeight(),
              request.onTimeRateWeight() == null ? DEFAULT_ON_TIME : request.onTimeRateWeight(),
              request.complaintRateWeight() == null ? DEFAULT_COMPLAINT : request.complaintRateWeight());
    } catch (ChannelScoringEngine.ScoringException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    List<FreightCalculatorModels.FreightEstimateLine> quotes = basis.estimate().lines();

    // ---- ① 按请求限定候选渠道 ----
    List<FreightCalculatorModels.FreightEstimateLine> scoped = new ArrayList<>();
    for (FreightCalculatorModels.FreightEstimateLine q : quotes) {
      if (request.channelIds() != null
          && !request.channelIds().isEmpty()
          && !request.channelIds().contains(q.channelId())) {
        continue;
      }
      scoped.add(q);
    }

    // ---- ② 主币种：能算出钱的最便宜那条的币种（与 10.1.2.1 同款口径）----
    String primaryCurrency = primaryCurrencyOf(scoped);
    boolean mixedCurrency =
        primaryCurrency != null
            && scoped.stream()
                .anyMatch(q -> q.quoteable() && !primaryCurrency.equals(q.currency()));

    // ---- ③ 取评分与店铺预设（一次性取回，避免逐渠道查）----
    List<UUID> channelIds = scoped.stream().map(FreightCalculatorModels.FreightEstimateLine::channelId).toList();
    Map<UUID, Record> ratings = ratings(tenant, channelIds, country);
    Map<UUID, Record> priorities =
        applyShopPriority && !shop.isEmpty() ? priorities(tenant, shop, channelIds) : Map.of();

    // ---- ④ 逐渠道定性：可承运性 / 时效上限 / 币种 / 能否算钱 / 有没有评分 ----
    List<Candidate> candidates = new ArrayList<>();
    Map<UUID, String> rankBases = new LinkedHashMap<>();
    Map<UUID, String> exclusionReasons = new LinkedHashMap<>();

    for (FreightCalculatorModels.FreightEstimateLine q : scoped) {
      String reason = null;
      String basisCode = "RANKED";

      if ("BLOCK".equals(q.eligibility())) {
        basisCode = "EXCLUDED_INELIGIBLE";
        reason = "上游 10.1.1.2 硬拦截（承运商会拒收）："
            + (q.eligibilityReasons().isEmpty() ? "命中渠道限制" : String.join("；", q.eligibilityReasons()));
      } else if (requirePassOnly && !"PASS".equals(q.eligibility())) {
        basisCode = "EXCLUDED_INELIGIBLE";
        reason = "需人工确认（REVIEW），而本次比价要求只取 PASS："
            + String.join("；", q.eligibilityReasons());
      } else if (request.maxTransitDays() != null
          && q.transitMaxDays() != null
          && q.transitMaxDays() > request.maxTransitDays()) {
        basisCode = "EXCLUDED_TRANSIT";
        reason =
            "预计时效上界 "
                + q.transitMaxDays()
                + " 天，超过本次要求的 "
                + request.maxTransitDays()
                + " 天";
      } else if (primaryCurrency != null && q.quoteable() && !primaryCurrency.equals(q.currency())) {
        basisCode = "EXCLUDED_CURRENCY";
        reason =
            "币种 "
                + q.currency()
                + " 与主币种 "
                + primaryCurrency
                + " 不同，未参与排序（不同币种的金额直接比大小没有意义）";
      } else if (!q.quoteable()) {
        basisCode = "EXCLUDED_NO_PRICE";
        reason = "上游 10.1.2.1 算不出这条渠道的运费（rateSource=NO_RULE），不是 0 元";
      } else if (ratings.get(q.channelId()) == null) {
        basisCode = "EXCLUDED_NO_SCORE";
        reason =
            "该渠道没有 "
                + country
                + " 的渠道评分（成功率 / 时效达成率 / 投诉率），缺少可靠性维度，不参与多目标排序";
      } else if (transitBasisOf(q) == null) {
        basisCode = "EXCLUDED_NO_TRANSIT";
        reason =
            "上游 10.1.2.1 未登记该渠道在 "
                + country
                + " 的试算时效区间，无法按时效排序（不编天数）";
      }

      rankBases.put(q.channelId(), basisCode);
      if (reason != null) {
        exclusionReasons.put(q.channelId(), reason);
      }
      candidates.add(
          new Candidate(
              q.channelId(),
              q.totalCharge(),
              q.currency(),
              transitBasisOf(q),
              rate(ratings.get(q.channelId()), "delivery_success_rate"),
              rate(ratings.get(q.channelId()), "on_time_rate"),
              rate(ratings.get(q.channelId()), "complaint_rate"),
              basisCode.equals("RANKED")));
    }

    // ---- ⑤ 归一化：只在**参与排名**的同币种候选内比较 ----
    CostSpan costSpan = ChannelScoringEngine.INSTANCE.costScores(candidates);
    Span transitSpan = ChannelScoringEngine.INSTANCE.transitSpan(candidates);

    // ---- ⑥ 逐渠道算分 + 三个闸 ----
    List<Scored> scored = new ArrayList<>();
    for (int i = 0; i < scoped.size(); i++) {
      FreightCalculatorModels.FreightEstimateLine q = scoped.get(i);
      Candidate candidate = candidates.get(i);
      Record rating = ratings.get(q.channelId());
      Record priority = priorities.get(q.channelId());

      BigDecimal costScore = null;
      BigDecimal transitScore = null;
      BigDecimal reliabilityScore = null;
      BigDecimal compositeScore = null;

      if (candidate.comparable()) {
        costScore = ChannelScoringEngine.INSTANCE.costScore(candidate, costSpan.min(), costSpan.max());
        transitScore = ChannelScoringEngine.INSTANCE.transitScore(candidate, transitSpan);
        reliabilityScore = ChannelScoringEngine.INSTANCE.reliabilityScore(candidate, weights);
        compositeScore =
            ChannelScoringEngine.INSTANCE.composite(costScore, transitScore, reliabilityScore, weights);
      }

      String riskNote = ChannelScoringEngine.INSTANCE.riskGate(candidate, rate(rating, "max_complaint_rate_pct"));
      String sampleNote =
          ChannelScoringEngine.INSTANCE.sampleGate(
              rating == null ? null : rating.get("dispatched", Integer.class),
              rating == null ? 1 : rating.get("min_sample_threshold", Integer.class));
      String staleNote =
          ChannelScoringEngine.INSTANCE.stalenessGate(
              rating == null ? null : rating.get("observed_at", OffsetDateTime.class),
              comparedAt,
              maxRatingAgeDays);

      boolean riskFlagged = riskNote != null;
      boolean sampleSufficient = sampleNote == null;
      boolean ratingStale = staleNote != null;

      List<String> breakdown =
          ChannelScoringEngine.INSTANCE.breakdown(
              candidate, weights, costScore, transitScore, reliabilityScore, compositeScore,
              riskNote, sampleNote, staleNote);

      scored.add(
          new Scored(
              q,
              rating,
              priority,
              costScore,
              transitScore,
              reliabilityScore,
              compositeScore,
              riskFlagged,
              riskNote,
              sampleSufficient,
              sampleNote,
              ratingStale,
              staleNote,
              breakdown));
    }

    // ---- ⑦ 排名：综合分降序，同分按运费升序、再按渠道编码定序（保证结果可复现）----
    List<Scored> ranked =
        scored.stream()
            .filter(s -> rankBases.get(s.quote().channelId()).equals("RANKED"))
            .sorted(
                Comparator.comparing(
                        (Scored s) -> s.compositeScore() == null ? BigDecimal.ZERO : s.compositeScore(),
                        Comparator.reverseOrder())
                    .thenComparing(
                        s ->
                            s.quote().totalCharge() == null
                                ? BigDecimal.ZERO
                                : s.quote().totalCharge())
                    .thenComparing(s -> s.quote().channelCode()))
            .toList();
    Map<UUID, Integer> rankNos = new LinkedHashMap<>();
    for (int i = 0; i < ranked.size(); i++) {
      rankNos.put(ranked.get(i).quote().channelId(), i + 1);
    }

    // ---- ⑧ 推荐 ----
    Recommendation recommendation =
        recommend(ranked, rankNos, priorities, request, maxRatingAgeDays, comparedAt);

    long riskCount = scored.stream().filter(Scored::riskFlagged).count();
    long sampleShort = scored.stream().filter(s -> !s.sampleSufficient()).count();
    long staleCount = scored.stream().filter(Scored::ratingStale).count();

    BigDecimal cheapest = null;
    BigDecimal dearest = null;
    for (Scored s : scored) {
      BigDecimal total = s.quote().totalCharge();
      if (!s.quote().quoteable() || total == null || !primaryCurrency.equals(s.quote().currency())) {
        continue;
      }
      if (cheapest == null || total.compareTo(cheapest) < 0) {
        cheapest = total;
      }
      if (dearest == null || total.compareTo(dearest) > 0) {
        dearest = total;
      }
    }

    List<ComparisonLine> lines = new ArrayList<>();
    for (Scored s : scored) {
      String rankBasis = rankBases.get(s.quote().channelId());
      boolean selected = recommendation.channelId() != null
          && recommendation.channelId().equals(s.quote().channelId());
      lines.add(line(s, rankBasis, exclusionReasons.get(s.quote().channelId()), rankNos, selected, weights));
    }

    UUID savedId =
        persist
            ? persist(
                tenant, request, basis, country, shop, comparedAt, weights, maxRatingAgeDays,
                requirePassOnly, applyShopPriority, primaryCurrency, mixedCurrency, cheapest,
                dearest, lines, recommendation)
            : null;

    String selectedCode = null;
    String selectedName = null;
    if (recommendation.channelId() != null) {
      for (ComparisonLine l : lines) {
        if (l.channelId().equals(recommendation.channelId())) {
          selectedCode = l.channelCode();
          selectedName = l.channelName();
        }
      }
    }

    return new CompareResult(
        "RULE_ESTIMATE",
        basis.quoteId(),
        comparedAt,
        country,
        shop,
        basis.weightKg(),
        basis.pieces(),
        primaryCurrency == null ? "" : primaryCurrency,
        mixedCurrency,
        cheapest,
        dearest,
        scoped.size(),
        ranked.size(),
        (int) riskCount,
        (int) sampleShort,
        (int) staleCount,
        weights.cost(),
        weights.transit(),
        weights.reliability(),
        weights.successRate(),
        weights.onTimeRate(),
        weights.complaintRate(),
        request.maxTransitDays(),
        maxRatingAgeDays,
        requirePassOnly,
        applyShopPriority,
        request.minCompositeScore(),
        recommendation.channelId(),
        selectedCode,
        selectedName,
        recommendation.basis(),
        recommendation.note(),
        savedId,
        lines,
        summary(ranked.size(), riskCount, sampleShort, staleCount, mixedCurrency, primaryCurrency, recommendation));
  }

  private String summary(int ranked, long risk, long sampleShort, long stale, boolean mixedCurrency,
      String primaryCurrency, Recommendation recommendation) {
    StringBuilder sb = new StringBuilder();
    sb.append("在满足限制与时效的渠道里按成本 / 时效 / 成功率多目标排序，共 ")
        .append(ranked)
        .append(" 条参与排名");
    if (mixedCurrency) {
      sb.append("；主币种 ").append(primaryCurrency)
          .append("，其它币种的渠道未参与排序（不同币种的金额直接比大小没有意义）");
    }
    if (risk > 0) {
      sb.append("；").append(risk).append(" 条触发投诉率红线闸");
    }
    if (sampleShort > 0) {
      sb.append("；").append(sampleShort).append(" 条样本量不足");
    }
    if (stale > 0) {
      sb.append("；").append(stale).append(" 条评分已过期");
    }
    sb.append("。").append(recommendation.note());
    sb.append(" 成本来自上游 10.1.2.1 的试算（costBasis=RULE_ESTIMATE）：试算用规则、结算用实际，"
        + "与实际运费的差异要进下游费用差异项，不能拿它当实际成本。");
    return sb.toString();
  }

  // ==================================================================
  // 推荐
  // ==================================================================

  private record Recommendation(UUID channelId, String basis, String note) {}

  /**
   * 自动选择。
   *
   * <p>🔴 两个入口，都必须过**三个闸 + 预设最低分**：
   *
   * <ol>
   *   <li>有店铺预设且 {@code applyShopPriority=true}：按 {@code priorityNo} 升序取
   *       <b>第一条合格</b>的（合格 = 参与排名 ∧ 不触发风险闸 ∧ 样本足 ∧ 评分新鲜 ∧
   *       允许自动选 ∧ 综合分 ≥ 该渠道的预设最低分）。
   *   <li>否则：按综合分取第一条合格的。
   * </ol>
   *
   * <p>🔴 一条都不合格时返回 {@code NO_ELIGIBLE_CHANNEL}，**不静默回落**：
   * 挑一条被自己判为「高投诉」或「样本不足」的渠道，等于把蓝图加粗句点名的错误又犯一遍。
   */
  private Recommendation recommend(List<Scored> ranked, Map<UUID, Integer> rankNos,
      Map<UUID, Record> priorities, CompareRequest request, int maxRatingAgeDays,
      OffsetDateTime comparedAt) {

    Integer floor = request.minCompositeScore();
    List<Scored> qualified =
        ranked.stream()
            .filter(s -> !s.riskFlagged())
            .filter(Scored::sampleSufficient)
            .filter(s -> !s.ratingStale())
            .filter(s -> floor == null
                || (s.compositeScore() != null && s.compositeScore().compareTo(BigDecimal.valueOf(floor)) >= 0))
            .toList();

    Map<UUID, Integer> priorityNos = new LinkedHashMap<>();
    priorities.values()
        .forEach(r -> priorityNos.put(r.get("channel_id", UUID.class), r.get("priority_no", Integer.class)));

    if (!priorities.isEmpty()) {
      List<Scored> byPreset =
          qualified.stream()
              .filter(s -> {
                Record p = priorities.get(s.quote().channelId());
                return p != null
                    && Boolean.TRUE.equals(p.get("auto_select_allowed", Boolean.class))
                    && meetsFloor(s, p, floor);
              })
              .sorted(
                  Comparator.comparingInt(
                          (Scored s) -> priorityNos.getOrDefault(s.quote().channelId(), Integer.MAX_VALUE))
                      .thenComparing(s -> s.quote().channelCode()))
              .toList();

      if (!byPreset.isEmpty()) {
        Scored picked = byPreset.get(0);
        int no = priorityNos.get(picked.quote().channelId());
        return new Recommendation(
            picked.quote().channelId(),
            "SHOP_PRESET_PRIORITY",
            "按店铺预设优先级自动选中："
                + shopCodeOf(priorities)
                + " 的第 "
                + no
                + " 位 "
                + picked.quote().channelCode()
                + "（综合分 "
                + plain(picked.compositeScore())
                + "，名次 "
                + rankNos.get(picked.quote().channelId())
                + "）。"
                + (no > 1
                    ? "排在它前面的渠道都没通过闸（详见逐条 exclusionReason），不是被忽略了。"
                    : ""));
      }
    }

    if (!qualified.isEmpty()) {
      Scored picked = qualified.get(0);
      return new Recommendation(
          picked.quote().channelId(),
          "SCORE_RANKED",
          "按综合分排名自动选中 "
              + picked.quote().channelCode()
              + "（综合分 "
              + plain(picked.compositeScore())
              + "，成本分 "
              + plain(picked.costScore())
              + " / 时效分 "
              + plain(picked.transitScore())
              + " / 可靠性分 "
              + plain(picked.reliabilityScore())
              + "）。");
    }

    return new Recommendation(
        null,
        "NO_ELIGIBLE_CHANNEL",
        ranked.isEmpty()
            ? "没有任何渠道参与排名（详见逐条 exclusionReason）—— 本次比价不给出推荐，"
                + "而不是随便挑一条。"
            : ranked.stream()
                .map(s -> s.quote().channelCode() + "："
                    + (s.riskFlagged() ? s.riskNote() : s.sampleSufficient() ? s.staleNote() : s.sampleNote()))
                .collect(Collectors.joining("；"))
                + " —— 以上渠道全部未通过自动选择的闸，本次比价不给出推荐，而不是随便挑一条。");
  }

  private static boolean meetsFloor(Scored s, Record priority, Integer floor) {
    BigDecimal limit =
        priority.get("min_composite_score", BigDecimal.class);
    if (limit == null || limit.signum() <= 0) {
      return floor == null || (s.compositeScore() != null
          && s.compositeScore().compareTo(BigDecimal.valueOf(floor)) >= 0);
    }
    BigDecimal effective = floor == null ? limit : limit.max(BigDecimal.valueOf(floor));
    return s.compositeScore() != null && s.compositeScore().compareTo(effective) >= 0;
  }

  private static String shopCodeOf(Map<UUID, Record> priorities) {
    return priorities.values().stream()
        .map(r -> r.get("shop_code", String.class))
        .findFirst()
        .orElse("");
  }

  // ==================================================================
  // 比价单落库
  // ==================================================================

  private record Scored(
      FreightCalculatorModels.FreightEstimateLine quote,
      Record rating,
      Record priority,
      BigDecimal costScore,
      BigDecimal transitScore,
      BigDecimal reliabilityScore,
      BigDecimal compositeScore,
      boolean riskFlagged,
      String riskNote,
      boolean sampleSufficient,
      String sampleNote,
      boolean ratingStale,
      String staleNote,
      List<String> breakdown) {}

  private ComparisonLine line(Scored s, String rankBasis, String exclusionReason,
      Map<UUID, Integer> rankNos, boolean selected, Weights weights) {

    FreightCalculatorModels.FreightEstimateLine q = s.quote();
    Record rating = s.rating();
    Record priority = s.priority();
    List<String> notes = new ArrayList<>();
    if (!q.quoteable()) {
      notes.add("上游 10.1.2.1 算不出这条渠道的运费，不是 0 元");
    }
    if (selected) {
      notes.add("本次比价推荐选中的渠道");
    } else if (s.riskFlagged()) {
      notes.add("未自动选中：" + s.riskNote());
    } else if (!s.sampleSufficient()) {
      notes.add("未自动选中：" + s.sampleNote());
    } else if (s.ratingStale()) {
      notes.add("未自动选中：" + s.staleNote());
    }

    return new ComparisonLine(
        q.channelId(),
        q.channelCode(),
        q.channelName(),
        q.carrierName(),
        q.eligibility(),
        q.totalCharge(),
        q.currency(),
        q.transitMinDays(),
        q.transitMaxDays(),
        transitBasisOf(q),
        q.quoteable(),
        s.costScore(),
        s.transitScore(),
        s.reliabilityScore(),
        s.compositeScore(),
        rankNos.get(q.channelId()),
        rankBasis,
        exclusionReason == null ? "" : exclusionReason,
        rating == null ? null : rating.get("id", UUID.class),
        rating == null ? null : rating.get("window_from", OffsetDateTime.class),
        rating == null ? null : rating.get("window_to", OffsetDateTime.class),
        rating == null ? null : rating.get("observed_at", OffsetDateTime.class),
        rating == null ? null : rating.get("dispatched", Integer.class),
        rating == null ? null : rating.get("delivered", Integer.class),
        rating == null ? null : rating.get("on_time", Integer.class),
        rating == null ? null : rating.get("complaint", Integer.class),
        rate(rating, "delivery_success_rate"),
        rate(rating, "on_time_rate"),
        rate(rating, "complaint_rate"),
        rating == null ? null : rating.get("score_source", String.class),
        s.riskFlagged(),
        s.riskNote(),
        s.sampleSufficient(),
        s.sampleNote(),
        s.ratingStale(),
        s.staleNote(),
        priority == null ? null : priority.get("priority_no", Integer.class),
        priority == null || Boolean.TRUE.equals(priority.get("auto_select_allowed", Boolean.class)),
        priority == null ? null : priority.get("min_composite_score", BigDecimal.class),
        selected,
        selected ? "本次比价推荐选中" : "",
        s.breakdown(),
        notes);
  }

  private UUID persist(
      UUID tenant, CompareRequest request, Basis basis, String country, String shop,
      OffsetDateTime comparedAt, Weights weights, int maxRatingAgeDays, boolean requirePassOnly,
      boolean applyShopPriority, String primaryCurrency, boolean mixedCurrency, BigDecimal cheapest,
      BigDecimal dearest, List<ComparisonLine> lines, Recommendation recommendation) {

    UUID comparisonId = Ids.next();
    long rankedCount = lines.stream().filter(l -> "RANKED".equals(l.rankBasis())).count();

    sql.execute(
        "insert into channel_comparison"
            + " (tenant_id,id,cost_basis,source_quote_id,dest_country,shop_code,weight_kg,pieces,"
            + "  declared_value,declared_currency,category_codes,compared_at,"
            + "  cost_weight,transit_weight,reliability_weight,"
            + "  success_weight,on_time_weight,complaint_weight,"
            + "  max_transit_days,max_rating_age_days,require_pass_only,apply_shop_priority,"
            + "  candidate_count,ranked_count,selected_count,risk_flagged_count,"
            + "  insufficient_sample_count,stale_rating_count,"
            + "  currency,mixed_currency,cheapest_total,dearest_total,"
            + "  selected_channel_id,selection_basis,selection_note,note)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        tenant, comparisonId, "RULE_ESTIMATE", basis.quoteId(), country, shop, basis.weightKg(),
        basis.pieces(), basis.declaredValue(), blankToNull(upper(basis.declaredCurrency())),
        FreightCalculatorService.codeList(basis.categories()), timestamp(comparedAt),
        weights.cost(), weights.transit(), weights.reliability(),
        weights.successRate(), weights.onTimeRate(), weights.complaintRate(),
        request.maxTransitDays(), maxRatingAgeDays, requirePassOnly, applyShopPriority,
        lines.size(), (int) rankedCount, recommendation.channelId() == null ? 0 : 1,
        lines.stream().filter(ComparisonLine::riskFlagged).count(),
        lines.stream().filter(l -> !l.sampleSufficient()).count(),
        lines.stream().filter(ComparisonLine::ratingStale).count(),
        primaryCurrency == null ? "" : primaryCurrency, mixedCurrency, cheapest, dearest,
        recommendation.channelId(), recommendation.basis(), recommendation.note(),
        text(request.note()));

    for (ComparisonLine l : lines) {
      sql.execute(
          "insert into channel_comparison_line"
              + " (tenant_id,comparison_id,channel_id,channel_code,channel_name,carrier_name,"
              + "  eligibility,total_charge,currency,transit_min_days,transit_max_days,"
              + "  transit_basis_days,cost_score,transit_score,reliability_score,composite_score,"
              + "  rating_id,rating_window_from,rating_window_to,rating_observed_at,"
              + "  rating_dispatched,rating_delivered,rating_on_time,rating_complaint,"
              + "  rating_success_rate,rating_on_time_rate,rating_complaint_rate,rating_source,"
              + "  risk_flagged,risk_note,sample_sufficient,sample_note,rating_stale,stale_note,"
              + "  priority_no,auto_select_allowed,min_composite_score,"
              + "  rank_basis,rank_no,exclusion_reason,selected,score_breakdown)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          tenant, comparisonId, l.channelId(), l.channelCode(), l.channelName(), l.carrierName(),
          l.eligibility(), l.totalCharge(), l.currency(), l.transitMinDays(), l.transitMaxDays(),
          l.transitBasisDays(), l.costScore(), l.transitScore(), l.reliabilityScore(),
          l.compositeScore(), l.ratingId(), timestamp(l.ratingWindowFrom()),
          timestamp(l.ratingWindowTo()), timestamp(l.ratingObservedAt()),
          l.ratingDispatched(), l.ratingDelivered(), l.ratingOnTime(), l.ratingComplaint(),
          l.ratingSuccessRate(), l.ratingOnTimeRate(),
          l.ratingComplaintRate(), l.ratingSource(),
          l.riskFlagged(), emptyIfNull(l.riskNote()), l.sampleSufficient(), emptyIfNull(l.sampleNote()),
          l.ratingStale(), emptyIfNull(l.staleNote()), l.priorityNo(), l.autoSelectAllowed(),
          l.minCompositeScore(), l.rankBasis(), l.rankNo(), emptyIfNull(l.exclusionReason()), l.selected(),
          String.join("；", l.scoreBreakdown()));
    }
    return comparisonId;
  }

  // ==================================================================
  // 比价单查询
  // ==================================================================

  private static final String COMPARISON_COLUMNS =
      "c.id,c.cost_basis,c.source_quote_id,c.dest_country,c.shop_code,c.weight_kg,c.pieces,"
          + "c.compared_at,c.cost_weight,c.transit_weight,c.reliability_weight,"
          + "c.max_transit_days,c.max_rating_age_days,c.require_pass_only,c.apply_shop_priority,"
          + "c.candidate_count,c.ranked_count,c.risk_flagged_count,c.insufficient_sample_count,"
          + "c.stale_rating_count,c.currency,c.mixed_currency,c.cheapest_total,c.dearest_total,"
          + "c.selected_channel_id,c.selection_basis,c.selection_note,c.note,c.created_at,"
          + "coalesce(cc.channel_code,'') as selected_channel_code";

  private static final String COMPARISON_FROM =
      " from channel_comparison c"
          + " left join logistics_channel_config cc on cc.tenant_id = c.tenant_id"
          + "   and cc.id = c.selected_channel_id"
          + " where c.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid";

  private static final List<String> COMPARISON_SORTS =
      List.of("TIME_DESC", "TIME_ASC", "SCORE_DESC", "SELECTED_DESC");

  private record ComparisonFilter(String where, List<Object> binds) {}

  private ComparisonFilter comparisonFilter(
      String destCountry, String shopCode, String selectionBasis, String comparedFrom, String comparedTo) {
    StringBuilder where = new StringBuilder();
    List<Object> binds = new ArrayList<>();
    String country = upper(destCountry);
    if (!country.isEmpty()) {
      where.append(" and c.dest_country = ?");
      binds.add(country);
    }
    String shop = upper(shopCode);
    if (!shop.isEmpty()) {
      where.append(" and c.shop_code = ?");
      binds.add(shop);
    }
    if (!text(selectionBasis).isEmpty()) {
      where.append(" and c.selection_basis = ?");
      binds.add(text(selectionBasis));
    }
    OffsetDateTime from = parseInstant(comparedFrom, "comparedFrom");
    if (from != null) {
      where.append(" and c.compared_at >= ?");
      binds.add(from);
    }
    OffsetDateTime to = parseInstant(comparedTo, "comparedTo");
    if (to != null) {
      where.append(" and c.compared_at <= ?");
      binds.add(to);
    }
    return new ComparisonFilter(where.toString(), binds);
  }

  public ComparisonPage comparisons(String destCountry, String shopCode, String selectionBasis,
      String comparedFrom, String comparedTo, String sort, int page, int size) {

    if (!COMPARISON_SORTS.contains(sort)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "排序方式只能是 " + String.join(" / ", COMPARISON_SORTS));
    }
    ComparisonFilter filter = comparisonFilter(destCountry, shopCode, selectionBasis, comparedFrom, comparedTo);

    Record summary =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where selected_channel_id is not null) as with_selection,"
                + " count(*) filter (where selected_channel_id is null) as no_selection,"
                + " count(*) filter (where selection_basis = 'SHOP_PRESET_PRIORITY') as shop_preset,"
                + " count(*) filter (where risk_flagged_count > 0) as risk_flagged"
                + COMPARISON_FROM
                + filter.where(),
            filter.binds().toArray());
    long total = summary == null ? 0 : summary.get("total", Long.class);
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    int totalPages = (int) Math.ceil((double) total / safeSize);

    String orderBy =
        switch (sort) {
          case "TIME_ASC" -> " order by c.compared_at asc, c.id asc";
          case "SCORE_DESC" -> " order by c.ranked_count desc, c.compared_at desc";
          case "SELECTED_DESC" -> " order by (c.selected_channel_id is null), c.compared_at desc";
          default -> " order by c.compared_at desc, c.id desc";
        };

    List<ComparisonRow> items =
        sql.fetch(
                "select " + COMPARISON_COLUMNS + COMPARISON_FROM + filter.where() + orderBy
                    + " limit ? offset ?",
                concat(filter.binds(), safeSize, safePage * safeSize))
            .stream()
            .map(RateComparisonService::comparisonRow)
            .toList();

    return new ComparisonPage(
        items,
        safePage,
        safeSize,
        total,
        totalPages,
        new ComparisonSummary(
            total,
            summary == null ? 0 : nz(summary.get("with_selection", Long.class)),
            summary == null ? 0 : nz(summary.get("no_selection", Long.class)),
            summary == null ? 0 : nz(summary.get("shop_preset", Long.class)),
            summary == null ? 0 : nz(summary.get("risk_flagged", Long.class))));
  }

  public ComparisonDetail comparison(String id) {
    UUID tenant = currentTenant();
    UUID comparisonId = parseUuid(id);
    ComparisonRow row =
        comparisonRow(sql.fetchOne("select " + COMPARISON_COLUMNS + COMPARISON_FROM + " and c.id = ?",
            comparisonId));
    if (row == null) {
      throw notFound();
    }
    List<ComparisonLine> lines =
        sql.fetch(
                "select channel_id,channel_code,channel_name,carrier_name,eligibility,total_charge,"
                    + "currency,transit_min_days,transit_max_days,transit_basis_days,"
                    + "cost_score,transit_score,reliability_score,composite_score,rank_basis,rank_no,"
                    + "exclusion_reason,rating_id,rating_window_from,rating_window_to,rating_observed_at,"
                    + "rating_dispatched,rating_delivered,rating_on_time,rating_complaint,"
                    + "rating_success_rate,rating_on_time_rate,rating_complaint_rate,"
                    + "rating_source,risk_flagged,risk_note,sample_sufficient,sample_note,rating_stale,"
                    + "stale_note,priority_no,auto_select_allowed,min_composite_score,selected,"
                    + "score_breakdown"
                    + " from channel_comparison_line"
                    + " where tenant_id = ? and comparison_id = ?"
                    + " order by (rank_no is null), rank_no asc, channel_code asc",
                tenant, comparisonId)
            .stream()
            .map(r -> detailLine(r))
            .toList();
    return new ComparisonDetail(row, lines);
  }

  private static ComparisonLine detailLine(Record r) {
    return new ComparisonLine(
        r.get("channel_id", UUID.class),
        r.get("channel_code", String.class),
        r.get("channel_name", String.class),
        r.get("carrier_name", String.class),
        r.get("eligibility", String.class),
        r.get("total_charge", BigDecimal.class),
        r.get("currency", String.class),
        r.get("transit_min_days", Integer.class),
        r.get("transit_max_days", Integer.class),
        r.get("transit_basis_days", Integer.class),
        r.get("total_charge", BigDecimal.class) != null,
        r.get("cost_score", BigDecimal.class),
        r.get("transit_score", BigDecimal.class),
        r.get("reliability_score", BigDecimal.class),
        r.get("composite_score", BigDecimal.class),
        r.get("rank_no", Integer.class),
        r.get("rank_basis", String.class),
        r.get("exclusion_reason", String.class),
        r.get("rating_id", UUID.class),
        r.get("rating_window_from", OffsetDateTime.class),
        r.get("rating_window_to", OffsetDateTime.class),
        r.get("rating_observed_at", OffsetDateTime.class),
        r.get("rating_dispatched", Integer.class),
        r.get("rating_delivered", Integer.class),
        r.get("rating_on_time", Integer.class),
        r.get("rating_complaint", Integer.class),
        r.get("rating_success_rate", BigDecimal.class),
        r.get("rating_on_time_rate", BigDecimal.class),
        r.get("rating_complaint_rate", BigDecimal.class),
        r.get("rating_source", String.class),
        Boolean.TRUE.equals(r.get("risk_flagged", Boolean.class)),
        r.get("risk_note", String.class),
        Boolean.TRUE.equals(r.get("sample_sufficient", Boolean.class)),
        r.get("sample_note", String.class),
        Boolean.TRUE.equals(r.get("rating_stale", Boolean.class)),
        r.get("stale_note", String.class),
        r.get("priority_no", Integer.class),
        !Boolean.FALSE.equals(r.get("auto_select_allowed", Boolean.class)),
        r.get("min_composite_score", BigDecimal.class),
        Boolean.TRUE.equals(r.get("selected", Boolean.class)),
        r.get("selected", Boolean.class) == null ? "" : "",
        splitBreakdown(r.get("score_breakdown", String.class)),
        List.of());
  }

  private static List<String> splitBreakdown(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    return List.of(raw.split("；(?=成本分|时效分|可靠性分|综合分|风险闸|样本闸|新鲜度闸)"));
  }

  private static ComparisonRow comparisonRow(Record r) {
    if (r == null) {
      return null;
    }
    return new ComparisonRow(
        r.get("id", UUID.class),
        r.get("cost_basis", String.class),
        r.get("source_quote_id", UUID.class),
        r.get("dest_country", String.class),
        r.get("shop_code", String.class),
        r.get("weight_kg", BigDecimal.class),
        r.get("pieces", Integer.class),
        r.get("compared_at", OffsetDateTime.class),
        r.get("cost_weight", BigDecimal.class),
        r.get("transit_weight", BigDecimal.class),
        r.get("reliability_weight", BigDecimal.class),
        r.get("max_transit_days", Integer.class),
        r.get("max_rating_age_days", Integer.class),
        Boolean.TRUE.equals(r.get("require_pass_only", Boolean.class)),
        Boolean.TRUE.equals(r.get("apply_shop_priority", Boolean.class)),
        r.get("candidate_count", Integer.class),
        r.get("ranked_count", Integer.class),
        r.get("risk_flagged_count", Integer.class),
        r.get("insufficient_sample_count", Integer.class),
        r.get("stale_rating_count", Integer.class),
        r.get("currency", String.class),
        Boolean.TRUE.equals(r.get("mixed_currency", Boolean.class)),
        r.get("cheapest_total", BigDecimal.class),
        r.get("dearest_total", BigDecimal.class),
        r.get("selected_channel_id", UUID.class),
        r.get("selected_channel_code", String.class),
        r.get("selection_basis", String.class),
        r.get("selection_note", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 规则读取
  // ==================================================================

  /** 一次性取回这些渠道在 {@code country} 的生效评分（避免逐渠道查的 N+1）。 */
  private Map<UUID, Record> ratings(UUID tenant, List<UUID> channelIds, String country) {
    if (channelIds.isEmpty()) {
      return Map.of();
    }
    String sqlText =
        "select id,channel_id,window_from,window_to,observed_at,dispatched,delivered,on_time,"
            + "complaint,delivery_success_rate,"
            + "on_time_rate,complaint_rate,score_source,max_complaint_rate_pct,min_sample_threshold"
            + " from channel_rating_score"
            + " where tenant_id = ? and country_code = ? and status = 'ACTIVE'"
            + " and channel_id in ("
            + String.join(",", java.util.Collections.nCopies(channelIds.size(), "?"))
            + ")";
    Object[] binds = new Object[channelIds.size() + 2];
    binds[0] = tenant;
    binds[1] = country;
    for (int i = 0; i < channelIds.size(); i++) {
      binds[i + 2] = channelIds.get(i);
    }
    Map<UUID, Record> out = new LinkedHashMap<>();
    for (Record r : sql.fetch(sqlText, binds)) {
      out.put(r.get("channel_id", UUID.class), r);
    }
    return out;
  }

  private Map<UUID, Record> priorities(UUID tenant, String shop, List<UUID> channelIds) {
    if (channelIds.isEmpty()) {
      return Map.of();
    }
    String sqlText =
        "select id,shop_code,channel_id,priority_no,auto_select_allowed,min_composite_score"
            + " from channel_shop_priority"
            + " where tenant_id = ? and shop_code = ? and status = 'ACTIVE'"
            + " and channel_id in ("
            + String.join(",", java.util.Collections.nCopies(channelIds.size(), "?"))
            + ")";
    Object[] binds = new Object[channelIds.size() + 2];
    binds[0] = tenant;
    binds[1] = shop;
    for (int i = 0; i < channelIds.size(); i++) {
      binds[i + 2] = channelIds.get(i);
    }
    Map<UUID, Record> out = new LinkedHashMap<>();
    for (Record r : sql.fetch(sqlText, binds)) {
      out.put(r.get("channel_id", UUID.class), r);
    }
    return out;
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  private static String primaryCurrencyOf(List<FreightCalculatorModels.FreightEstimateLine> lines) {
    FreightCalculatorModels.FreightEstimateLine cheapest =
        lines.stream()
            .filter(l -> l.quoteable() && l.totalCharge() != null)
            .min(Comparator.comparing(FreightCalculatorModels.FreightEstimateLine::totalCharge))
            .orElse(null);
    return cheapest == null ? null : cheapest.currency();
  }

  /** 排序用时效：优先上界，缺失时退下界；皆 null = 未登记（不可比，不编天数）。 */
  private static Integer transitBasisOf(FreightCalculatorModels.FreightEstimateLine line) {
    if (line.transitMaxDays() != null) {
      return line.transitMaxDays();
    }
    return line.transitMinDays();
  }

  private static BigDecimal rate(Record rating, String column) {
    return rating == null ? null : rating.get(column, BigDecimal.class);
  }

  private static String plain(BigDecimal value) {
    return value == null ? "—" : value.stripTrailingZeros().toPlainString();
  }

  /**
   * 闸未触发时 note 是 {@code null}，而 DB 列是 {@code NOT NULL DEFAULT ''}。
   *
   * <p>🔴 落库前统一收敛成空串：三种状态（「没触发」/「触发了有理由」/「触发了没理由」）
   * 由<b>布尔列</b>与空串共同表达，而不是往 NOT NULL 列里塞 null —— 后者会被 jOOQ /
   * Spring 一路带到 409，症状是「登记评分莫名其妙冲突」。
   */
  private static String emptyIfNull(String value) {
    return value == null ? "" : value;
  }

  /**
   * timestamptz 列的绑定值。
   *
   * <p>🔴 刻意转成 {@link java.sql.Timestamp}：把 {@link OffsetDateTime} 直接交给
   * {@code setObject} 时，驱动会按 varchar 发送，PG 报类型不匹配 —— 而评分窗口
   * 与比价时刻算错会静默错「这条渠道的数据新鲜不新鲜」，所以这里一次性钉死。
   */
  private static java.sql.Timestamp timestamp(OffsetDateTime value) {
    return value == null ? null : java.sql.Timestamp.from(value.toInstant());
  }

  /** 路径参数不是 uuid 时给 400（而不是把 NumberFormatException 抛成 500）。 */
  private static UUID parseUuid(String raw) {
    try {
      return UUID.fromString(text(raw));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 必须是合法的 uuid：" + raw);
    }
  }

  private static OffsetDateTime parseInstant(String raw, String field) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value);
    } catch (java.time.format.DateTimeParseException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, field + " 必须是 ISO-8601 时间（如 2026-10-15T09:00:00+08:00）");
    }
  }

  private String shopFrom(CompareRequest request, Basis basis) {
    if (request.parcel() != null && !text(request.parcel().shopCode()).isEmpty()) {
      return upper(request.parcel().shopCode());
    }
    return upper(basis.shopCode());
  }
}
