package com.starcon.channel;

import static com.starcon.channel.LogisticsTransitModels.*;

import com.starcon.channel.LogisticsChannelModels.ChannelRestrictionItem;
import com.starcon.channel.LogisticsTransitModels.LimitHit;
import com.starcon.channel.LogisticsTransitModels.LimitVerdict;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
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
 * 10.1.1.3 时效与限制。
 *
 * <p>蓝图：「维护渠道时效（分国家分重量段）与限制（尺寸上限、重量上限、禁限品类、
 * 电池/液体/危险品标记要求），并在选品与刊登阶段提示」，输出「时效估算；限制命中提示」。
 *
 * <p>🔴 本类唯一不能妥协的约束是那条加粗句：**渠道限制要硬拦截，否则会被承运商拒收
 * 产生额外费用。** 它的落地方式和 10.1.1.2 是同一套，但**覆盖面更大** ——
 * 本模块判的不只是品类与带电带液带磁，还包括尺寸 / 重量硬上限、危险品、以及
 * 「必须贴标识」这一类（后者不拦，但不允许自动放行）：
 *
 * <ol>
 *   <li><b>写入路径的 DB 层冲突拒绝</b>：重量段重叠、国家不适配、国家级上限比渠道级
 *       更宽、标记要求与上游 10.1.1.2 打架 —— V139 的五个触发器双向兜底，
 *       绕过服务层直接写 SQL 造出这些自相矛盾的规则同样被拒。
 *   <li><b>服务端判定端点</b>（{@link #precheck}）：把上游 10.1.1.2 的渠道限制、
 *       本模块的尺寸重量上限与标记要求**汇总成一个结论**。判断规则只在这一个方法里，
 *       **不散落到每个调用方** —— 散落就意味着总有一个调用方会漏掉「危险品」这一条。
 *   <li><b>时效承诺缺失也算一条 REVIEW</b>：刊登阶段承诺不出时效，转化会掉，
 *       而它和「限制命中」是同一个「能不能上架」的判断，一起给。
 * </ol>
 *
 * <p>数据来源纪律：渠道配置**同库同服务**读（10.1.1.2 已落在 channel，V136 建表），
 * 但本服务仍然只持有 {@code channel_id} 一个外键，**不复制渠道任何字段** ——
 * 复制一份就会造出「同一个渠道在两处各改一次」。上游 3.7.2 的 SKU 安全属性
 * （品类编码 / 带电 / 带液 / 带磁 / 危险品）是**入参**，本服务**一条 SQL 都不碰
 * catalog 的库**，跨服务方向一律经授权 HTTP 端点。
 */
@Service
public class LogisticsTransitService {

  private final DSLContext sql;

  public LogisticsTransitService(DSLContext sql) {
    this.sql = sql;
  }

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 在进入事务时绑定）。 */
  private UUID currentTenant() {
    String raw = one(String.class, "select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  private ResponseStatusException notFound(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在");
  }

  // ==================================================================
  // 能力声明：未交付的段显式 available=false + ownerModule + 影响
  // ==================================================================

  public LogisticsTransitCapabilities capabilities() {
    List<TransitCapabilityDimension> dimensions =
        List.of(
            new TransitCapabilityDimension(
                "transit_by_country_and_band",
                "渠道时效（分国家 × 分重量段 × 服务等级）",
                true,
                "本服务 V139 logistics_transit_rule（重量段闭合区间，DB 层禁止重叠）",
                "",
                "没有时效承诺，刊登阶段无法承诺送达天数，测算（5.3.1.1）也无从折算转化"),
            new TransitCapabilityDimension(
                "size_and_weight_limits",
                "尺寸上限 / 重量上限（渠道级 + 国家级取最严）",
                true,
                "本服务 V139 logistics_size_limit",
                "",
                "超限件会被承运商拒收并产生额外费用；缺了它只能等拒收通知才发现"),
            new TransitCapabilityDimension(
                "marking_requirements",
                "电池 / 液体 / 危险品 / 磁力 标记要求（含「必须贴标识」这一层）",
                true,
                "本服务 V139 logistics_marking_requirement（危险品是 V136 四种之外的第五类）",
                "",
                "标识漏打会在清关出问题，而 10.2.2.2 面单模板要靠这一层绑定打印规格"),
            new TransitCapabilityDimension(
                "restricted_categories",
                "禁限品类 / 电池 / 液体 / 磁力 的渠道限制",
                true,
                "上游 10.1.1.2 V136 logistics_channel_restriction（同库**只读**引用，不重建）",
                "10.1.1.2",
                "两处各建一张禁限品类表会导致同一件事存两份、改一份不改另一份，"
                    + "分歧的表现就是下单被拒收"),
            new TransitCapabilityDimension(
                "transit_estimation",
                "时效估算（分国家 + 重量 → 命中段 → 预计到达）",
                true,
                "本服务 POST /channel/logistics-transit-limits/estimate",
                "",
                "查询无命中时显式返回「没有时效承诺」，不用相邻段凑一个数"),
            new TransitCapabilityDimension(
                "limit_precheck",
                "🔴 限制命中提示（选品与刊登阶段）",
                true,
                "本服务 POST /channel/logistics-transit-limits/precheck",
                "",
                "判定在服务端汇总上游渠道限制 + 尺寸重量上限 + 标记要求，规则只有一份"),
            new TransitCapabilityDimension(
                "sku_safety_attributes",
                "SKU 安全属性（品类编码 / 带电 / 带液 / 带磁 / 危险品）",
                false,
                "上游 3.7.2 安全属性未交付；本模块以入参接收这些标记",
                "3.7.2",
                "调用方要自己从别处拼这几个布尔，刊登流程容易漏传某一个（比如漏传危险品）"),
            new TransitCapabilityDimension(
                "transit_analysis",
                "时效分析（实际时效达成率、渠道时效排名）",
                false,
                "下游 10.6.2.1 时效分析未开发",
                "10.6.2.1",
                "只能看到承诺时效，看不到实际达成，渠道优劣无从比较"),
            new TransitCapabilityDimension(
                "rate_estimation",
                "运费试算（渠道 × 运费 对照）",
                false,
                "下游 10.1.2.1 运费计算器未开发",
                "10.1.2.1",
                "算得出时效算不出运费，选渠道仍要人工询价"),
            new TransitCapabilityDimension(
                "label_templates",
                "面单模板按渠道与国家绑定打印规格",
                false,
                "下游 10.2.2.2 面单模板未开发",
                "10.2.2.2",
                "标识要求已经登记，但没有一个地方把「必须贴 UN38.3」变成打印规格"));
    return new LogisticsTransitCapabilities(
        dimensions,
        List.of("ECONOMY", "STANDARD", "EXPRESS"),
        List.of("BATTERY", "LIQUID", "DANGEROUS_GOODS", "MAGNET"),
        List.of("BLOCK", "MANUAL_REVIEW", "LABEL_REQUIRED", "ALLOWED"),
        List.of("READY", "NO_TRANSIT", "NO_BAND", "DISABLED"),
        List.of("ACTIVE", "DISABLED"),
        "时效与限制挂在 10.1.1.2 渠道配置之下：渠道身份（代码 / 名称 / 承运商 / 计费）"
            + "是上游只读，本模块的写请求一个渠道字段都不接受，改渠道要回 /logistics/channels。",
        "🔴 限制命中由**服务端**判定（POST precheck），它把上游 10.1.1.2 的渠道限制与"
            + "本模块的尺寸重量上限、标记要求汇总成一个结论；禁限品类不重建表，"
            + "因为 V136 已经是它的落点，两处各存一份必然分歧。");
  }

  // ==================================================================
  // 读：时效规则（真分页 + 真筛选 + 不受分页影响的真聚合）
  // ==================================================================

  private static final String RULE_COLUMNS =
      "r.id,r.channel_id,r.country_code,r.service_level,r.weight_min_kg,r.weight_max_kg,"
          + "r.transit_days_min,r.transit_days_max,r.cutoff_time,r.effective_from,r.effective_to,"
          + "r.status,r.readiness,r.note,r.version,r.created_at,r.updated_at,"
          + "c.channel_code,c.channel_name";

  private static final String RULE_FROM =
      " from logistics_transit_rule r"
          + " join logistics_channel_config c on c.tenant_id = r.tenant_id and c.id = r.channel_id";

  public TransitRulePage rules(
      String keyword,
      String channelId,
      String countryCode,
      String serviceLevel,
      String status,
      String readiness,
      String sort,
      int page,
      int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);

    List<Object> filters = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");

    // % _ \ 转义 —— 不转义的话运营搜 "100%" 会变成「匹配全部」而界面上看不出来。
    String kw = text(keyword);
    if (!kw.isEmpty()) {
      where.append(" and (r.country_code ilike ? or c.channel_code ilike ? or c.channel_name ilike ?)");
      String like = "%" + escapeLike(kw) + "%";
      filters.add(like);
      filters.add(like);
      filters.add(like);
    }
    String channel = text(channelId);
    if (!channel.isEmpty()) {
      where.append(" and r.channel_id = ?");
      filters.add(UUID.fromString(channel));
    }
    String country = text(countryCode).toUpperCase(Locale.ROOT);
    if (!country.isEmpty()) {
      where.append(" and r.country_code = ?");
      filters.add(country);
    }
    String level = text(serviceLevel);
    if (!level.isEmpty()) {
      where.append(" and r.service_level = ?");
      filters.add(level);
    }
    String st = text(status);
    if (!st.isEmpty()) {
      where.append(" and r.status = ?");
      filters.add(st);
    }
    String rdy = text(readiness);
    if (!rdy.isEmpty()) {
      where.append(" and r.readiness = ?");
      filters.add(rdy);
    }

    // ⚠️ 计数查询必须带**同样的 join**：关键词条件引用了 c.*，
    //    而 count(*) 的查询里少了 join 时症状是「一用搜索就报 bad SQL grammar」。
    long total =
        sql.fetchOne("select count(*)" + RULE_FROM + where, filters.toArray()).get(0, Long.class);

    List<Object> binds = new ArrayList<>(filters);
    binds.add(s);
    binds.add(p * s);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    var rows =
        sql.fetch(
            "select " + RULE_COLUMNS + RULE_FROM + where
                + " order by " + ruleOrderBy(sort) + " limit ? offset ?",
            binds.toArray());

    return new TransitRulePage(
        rows.stream().map(r -> toRuleRow(r, now)).toList(),
        p,
        s,
        total,
        (int) Math.ceil(total / (double) s),
        summary(where.toString(), filters, now));
  }

  private static String ruleOrderBy(String sort) {
    return switch (text(sort)) {
      case "COUNTRY_ASC" -> "r.country_code asc, r.weight_min_kg asc";
      case "BAND_DESC" -> "r.weight_max_kg desc, r.country_code asc";
      case "TRANSIT_ASC" -> "r.transit_days_min asc, r.country_code asc";
      case "UPDATED_AT_ASC" -> "r.updated_at asc";
      default -> "r.country_code asc, r.service_level asc, r.weight_min_kg asc";
    };
  }

  private TransitRuleRow toRuleRow(Record r, OffsetDateTime at) {
    OffsetDateTime from = r.get("effective_from", OffsetDateTime.class);
    OffsetDateTime to = r.get("effective_to", OffsetDateTime.class);
    String readiness = r.get("readiness", String.class);
    return new TransitRuleRow(
        r.get("id", UUID.class),
        r.get("channel_id", UUID.class),
        r.get("channel_code", String.class),
        r.get("channel_name", String.class),
        r.get("country_code", String.class),
        r.get("service_level", String.class),
        r.get("weight_min_kg", BigDecimal.class),
        r.get("weight_max_kg", BigDecimal.class),
        r.get("transit_days_min", Integer.class),
        r.get("transit_days_max", Integer.class),
        cutoffText(r.get("cutoff_time", LocalTime.class)),
        from,
        to,
        r.get("status", String.class),
        readiness,
        ruleReadinessMessage(readiness),
        effectiveNow(from, to, at),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 汇总：独立查询，覆盖 where 条件下的**全部**行，与分页无关。 */
  private TransitRuleSummary summary(String where, List<Object> filters, OffsetDateTime at) {
    // ⚠️ 占位符顺序：PG 按**SQL 文本里 ? 出现的先后**绑定，不是按 values 顺序。
    //    effective 表达式写在 SELECT 列表里、where 在它后面，所以时刻参数必须排在
    //    filters **之前**。顺序反了会把 'COUNTRY' 当成 timestamptz 绑进去。
    List<Object> binds = new ArrayList<>();
    binds.add(at);
    binds.add(at);
    binds.addAll(filters);
    Record r =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where r.readiness = 'READY') as ready,"
                + " count(*) filter (where r.readiness = 'NO_TRANSIT') as no_transit,"
                + " count(*) filter (where r.readiness = 'NO_BAND') as no_band,"
                + " count(*) filter (where r.readiness = 'DISABLED') as disabled,"
                + " count(*) filter (where (r.effective_from is null"
                + "   or ?::timestamptz >= r.effective_from)"
                + "   and (r.effective_to is null or ?::timestamptz < r.effective_to)) as effective_now,"
                + " count(distinct r.country_code) as countries,"
                + " count(distinct r.channel_id) as channels"
                + " from logistics_transit_rule r"
                + " join logistics_channel_config c"
                + "   on c.tenant_id = r.tenant_id and c.id = r.channel_id" + where,
            binds.toArray());
    return new TransitRuleSummary(
        r.get("total", Long.class),
        r.get("ready", Long.class),
        r.get("no_transit", Long.class),
        r.get("no_band", Long.class),
        r.get("disabled", Long.class),
        r.get("effective_now", Long.class),
        r.get("countries", Long.class),
        r.get("channels", Long.class));
  }

  /** 概览：全量口径（不受列表筛选影响）的服务等级 / flag 分布。 */
  public LogisticsTransitOverview overview() {
    TransitRuleSummary all =
        summary(" where 1=1", List.of(), OffsetDateTime.now(ZoneOffset.UTC));
    return new LogisticsTransitOverview(
        all,
        distribution(
            "service_level",
            List.of(
                new String[] {"ECONOMY", "经济"},
                new String[] {"STANDARD", "标准"},
                new String[] {"EXPRESS", "特快"}),
            "logistics_transit_rule"),
        distribution(
            "flag_kind",
            List.of(
                new String[] {"BATTERY", "电池"},
                new String[] {"LIQUID", "液体"},
                new String[] {"DANGEROUS_GOODS", "危险品"},
                new String[] {"MAGNET", "磁力"}),
            "logistics_marking_requirement"),
        count("logistics_size_limit"),
        count("logistics_marking_requirement"),
        "统计覆盖本租户全部时效与限制规则，不随列表筛选或分页变化；"
            + "「当前生效」按读取时刻计算，与列表行里的 effectiveNow 同一口径。");
  }

  private long count(String table) {
    return sql.fetchOne("select count(*) from " + table).get(0, Long.class);
  }

  private List<LogisticsTransitCount> distribution(
      String column, List<String[]> options, String table) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select " + column + " as k, count(*) as n from " + table
                + " where " + column + " is not null group by " + column)) {
      counts.put(r.get("k", String.class), r.get("n", Long.class));
    }
    List<LogisticsTransitCount> out = new ArrayList<>();
    for (String[] option : options) {
      out.add(new LogisticsTransitCount(option[0], option[1], counts.getOrDefault(option[0], 0L)));
    }
    return out;
  }

  public TransitRuleRow ruleDetail(UUID id) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    var row = sql.fetchOne("select " + RULE_COLUMNS + RULE_FROM + " where r.id = ?", id);
    if (row == null) {
      throw notFound("时效规则");
    }
    return toRuleRow(row, now);
  }

  // ==================================================================
  // 写：时效规则
  // ==================================================================

  /**
   * 登记 / 改一条时效规则。
   *
   * <p>⚠️ 写入前把 V139 触发器要拒的几件事说清楚（服务层给可读理由，DB 层兜底）：
   * 重量段必须是闭区间且非空、时效天数至少 1 天、生效区间要么都给要么都不给。
   * 重量段重叠 / 国家不适配由 V139 的触发器判（理由更准确：要指出与**哪一段**重叠）。
   */
  public TransitRuleRow saveRule(SaveTransitRuleRequest request) {
    UUID tenant = currentTenant();
    String country = text(request.countryCode()).toUpperCase(Locale.ROOT);
    String level = text(request.serviceLevel());
    BigDecimal min = request.weightMinKg();
    BigDecimal max = request.weightMaxKg();

    if (min == null || min.signum() < 0 || (max != null && min.compareTo(max) >= 0)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "重量段必须是半开区间 [下限, 上限)（当前 "
              + min
              + " ~ "
              + (max == null ? "无上限" : max)
              + " kg）：下限含、上限不含。用闭区间会让 30kg 这个包裹同时命中 [0.5,30] 与 [30,45]，"
              + "估算结果取决于查询顺序；留空上限表示最后一段（大于下限）");
    }
    if (request.transitDaysMin() < 1 || request.transitDaysMax() < request.transitDaysMin()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "时效天数至少 1 天，且上限不得小于下限（当前 "
              + request.transitDaysMin()
              + " ~ "
              + request.transitDaysMax()
              + " 天）：承诺不出时效的规则可用性会停在「缺时效」");
    }
    if ((request.effectiveFrom() == null) != (request.effectiveTo() == null)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "生效区间要么都给要么都不给：只填一端是半配置");
    }
    if (request.effectiveFrom() != null
        && !request.effectiveFrom().isBefore(request.effectiveTo())) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "生效区间的起点必须早于终点");
    }
    LocalTime cutoff = parseCutoff(request.cutoffTime());
    String status = text(request.status());

    UUID id = request.transitRuleId();
    boolean creating = id == null;
    if (creating) {
      if (request.channelId() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新建时效规则必须指定所属渠道");
      }
      requireChannel(tenant, request.channelId());
      id = Ids.next();
      sql.execute(
          "insert into logistics_transit_rule (id,tenant_id,channel_id,country_code,"
              + " service_level,weight_min_kg,weight_max_kg,transit_days_min,transit_days_max,"
              + " cutoff_time,effective_from,effective_to,status,readiness,note)"
              + " values (?,?,?,?,?,?,?,?,?,?::time,?::timestamptz,?::timestamptz,?,?,?)",
          id,
          tenant,
          request.channelId(),
          country,
          level,
          min,
          max,
          request.transitDaysMin(),
          request.transitDaysMax(),
          cutoff,
          request.effectiveFrom(),
          request.effectiveTo(),
          status,
          ruleReadiness(request.transitDaysMin(), request.transitDaysMax(), min, max, status),
          text(request.note()));
    } else {
      var existing =
          sql.fetchOne(
              "select version, channel_id from logistics_transit_rule where tenant_id=? and id=?",
              tenant,
              id);
      if (existing == null) {
        throw notFound("时效规则");
      }
      long currentVersion = existing.get("version", Long.class);
      if (request.version() != currentVersion) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "时效规则已被他人修改（当前版本 " + currentVersion + "），请重新载入后再保存");
      }
      if (request.channelId() != null
          && !request.channelId().equals(existing.get("channel_id", UUID.class))) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "所属渠道不可改：时效承诺是挂在某条渠道之上的");
      }
      // ⚠️ timestamptz 必须显式写 ?::timestamptz 并绑 OffsetDateTime。
      //    绑 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s —— 档期区间偏 8 小时
      //    会让「今天在不在这个档期里」判错，而且不报任何错。
      sql.execute(
          "update logistics_transit_rule set country_code=?, service_level=?,"
              + " weight_min_kg=?, weight_max_kg=?, transit_days_min=?, transit_days_max=?,"
              + " cutoff_time=?::time, effective_from=?::timestamptz, effective_to=?::timestamptz,"
              + " status=?, readiness=?, note=?, updated_at=now(), version=version+1"
              + " where tenant_id=? and id=?",
          country,
          level,
          min,
          max,
          request.transitDaysMin(),
          request.transitDaysMax(),
          cutoff,
          request.effectiveFrom(),
          request.effectiveTo(),
          status,
          ruleReadiness(request.transitDaysMin(), request.transitDaysMax(), min, max, status),
          text(request.note()),
          tenant,
          id);
    }

    try {
      return ruleDetail(id);
    } catch (org.jooq.exception.DataAccessException e) {
      // V139 的触发器（重量段重叠 / 国家不适配）用 check_violation 抛出，
      // 这里把它翻译成 422 并保留原文 —— 触发器里的理由比这里编得准。
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, triggerMessage(e, "时效规则"));
    }
  }

  private void requireChannel(UUID tenant, UUID channelId) {
    var carrier =
        sql.fetchOne(
            "select id from logistics_channel_config where tenant_id=? and id=?", tenant, channelId);
    if (carrier == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "渠道配置不存在：请先在 /logistics/channels 登记渠道");
    }
  }

  /** 撤下一条时效规则（子表是本模块自己的，不影响渠道配置与上游渠道限制）。 */
  public void deleteRule(UUID id) {
    UUID tenant = currentTenant();
    int rows =
        sql.execute("delete from logistics_transit_rule where tenant_id=? and id=?", tenant, id);
    if (rows == 0) {
      throw notFound("时效规则");
    }
  }

  /** readiness 的<b>单一实现</b>（与 V139 trigger a 逐条对应）。 */
  private static String ruleReadiness(
      int daysMin, int daysMax, BigDecimal min, BigDecimal max, String status) {
    if (daysMax < 1 || daysMin < 1) {
      return "NO_TRANSIT";
    }
    if (min == null || min.signum() < 0 || (max != null && min.compareTo(max) >= 0)) {
      return "NO_BAND";
    }
    return "DISABLED".equals(status) ? "DISABLED" : "READY";
  }

  private static String ruleReadinessMessage(String readiness) {
    return switch (readiness == null ? "" : readiness) {
      case "READY" -> "承诺齐备，可参与时效估算与刊登承诺";
      case "NO_TRANSIT" -> "时效天数没配：承诺不出送达天数";
      case "NO_BAND" -> "重量段为空：这个段永远命中不了";
      case "DISABLED" -> "规则已停用";
      default -> readiness;
    };
  }

  // ==================================================================
  // 读 / 写：尺寸与重量上限
  // ==================================================================

  private static final String SIZE_COLUMNS =
      "l.id,l.channel_id,l.country_code,l.max_length_cm,l.max_width_cm,l.max_height_cm,"
          + "l.max_girth_cm,l.max_weight_kg,l.note,l.version,l.created_at,l.updated_at,"
          + "c.channel_code,c.channel_name";

  private static final String SIZE_FROM =
      " from logistics_size_limit l"
          + " join logistics_channel_config c on c.tenant_id = l.tenant_id and c.id = l.channel_id";

  public SizeLimitPage sizeLimits(
      String keyword, String channelId, String countryCode, int page, int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);

    List<Object> filters = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");

    String kw = text(keyword);
    if (!kw.isEmpty()) {
      where.append(" and (c.channel_code ilike ? or c.channel_name ilike ? or coalesce(l.country_code,'') ilike ?)");
      String like = "%" + escapeLike(kw) + "%";
      filters.add(like);
      filters.add(like);
      filters.add(like);
    }
    String channel = text(channelId);
    if (!channel.isEmpty()) {
      where.append(" and l.channel_id = ?");
      filters.add(UUID.fromString(channel));
    }
    String country = text(countryCode).toUpperCase(Locale.ROOT);
    if (!country.isEmpty()) {
      where.append(" and l.country_code = ?");
      filters.add(country);
    }

    long total =
        sql.fetchOne("select count(*) from logistics_size_limit l" + where, filters.toArray())
            .get(0, Long.class);

    // 渠道级 / 国家级是列表上的两个真实口径：按**同一个筛选结果**再分一次类，
    // 两个计数之和恒等于 total（界面上不用解释为什么两个数加起来不是总数）。
    // 刻意**追加**条件而不是把 where 拼两遍：拼两遍必然会出现两处口径不一致的那一次。
    Object[] scopeBinds = filters.toArray();
    long channelLevel =
        sql.fetchOne(
                "select count(*) from logistics_size_limit l"
                    + where
                    + " and l.country_code is null",
                scopeBinds)
            .get(0, Long.class);
    long countryLevel =
        sql.fetchOne(
                "select count(*) from logistics_size_limit l"
                    + where
                    + " and l.country_code is not null",
                scopeBinds)
            .get(0, Long.class);

    List<Object> binds = new ArrayList<>(filters);
    binds.add(s);
    binds.add(p * s);
    var rows =
        sql.fetch(
            "select " + SIZE_COLUMNS + SIZE_FROM + where
                + " order by c.channel_code asc, l.country_code asc nulls first limit ? offset ?",
            binds.toArray());

    return new SizeLimitPage(
        rows.stream().map(r -> toSizeRow(r)).toList(),
        p,
        s,
        total,
        (int) Math.ceil(total / (double) s),
        channelLevel,
        countryLevel);
  }

  private SizeLimitRow toSizeRow(Record r) {
    String country = r.get("country_code", String.class);
    return new SizeLimitRow(
        r.get("id", UUID.class),
        r.get("channel_id", UUID.class),
        r.get("channel_code", String.class),
        r.get("channel_name", String.class),
        country,
        r.get("max_length_cm", BigDecimal.class),
        r.get("max_width_cm", BigDecimal.class),
        r.get("max_height_cm", BigDecimal.class),
        r.get("max_girth_cm", BigDecimal.class),
        r.get("max_weight_kg", BigDecimal.class),
        country == null || country.isEmpty() ? "渠道级" : "国家级 · " + country,
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  public SizeLimitRow sizeLimitDetail(UUID id) {
    var row = sql.fetchOne("select " + SIZE_COLUMNS + SIZE_FROM + " where l.id = ?", id);
    if (row == null) {
      throw notFound("尺寸 / 重量上限");
    }
    return toSizeRow(row);
  }

  /** 登记 / 改一条尺寸 / 重量上限。三维要么都给要么都不给（DB CHECK + 这里都拒）。 */
  public SizeLimitRow saveSizeLimit(SaveSizeLimitRequest request) {
    UUID tenant = currentTenant();
    String country = text(request.countryCode()).toUpperCase(Locale.ROOT);
    String countryOrNull = country.isEmpty() ? null : country;

    // ⚠️ 不能用 List.of(...)：它拒绝 null 元素，而「未填的维度」恰好就是 null。
    BigDecimal[] dimensions = {
      request.maxLengthCm(), request.maxWidthCm(), request.maxHeightCm(),
      request.maxGirthCm(), request.maxWeightKg()
    };
    int dims = 0;
    for (BigDecimal value : dimensions) {
      if (value != null) {
        dims++;
      }
    }
    if (dims == 0) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "至少要给一项上限：否则这是一行「没写限制」");
    }
    boolean hasLength = request.maxLengthCm() != null;
    if (hasLength != (request.maxWidthCm() != null)
        || (request.maxWidthCm() != null) != (request.maxHeightCm() != null)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "长 / 宽 / 高要么都给要么都不给：只给两维会造出「20×30×∞」这种谁也判不了的半配置，"
              + "而高恰恰是快递与航空最常卡的一维");
    }

    UUID id = request.sizeLimitId();
    boolean creating = id == null;
    if (creating) {
      if (request.channelId() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新建上限必须指定所属渠道");
      }
      requireChannel(tenant, request.channelId());
      id = Ids.next();
      sql.execute(
          "insert into logistics_size_limit (id,tenant_id,channel_id,country_code,max_length_cm,"
              + " max_width_cm,max_height_cm,max_girth_cm,max_weight_kg,note)"
              + " values (?,?,?,?,?,?,?,?,?,?)",
          id,
          tenant,
          request.channelId(),
          countryOrNull,
          request.maxLengthCm(),
          request.maxWidthCm(),
          request.maxHeightCm(),
          request.maxGirthCm(),
          request.maxWeightKg(),
          text(request.note()));
    } else {
      var existing =
          sql.fetchOne(
              "select version, channel_id from logistics_size_limit where tenant_id=? and id=?",
              tenant,
              id);
      if (existing == null) {
        throw notFound("尺寸 / 重量上限");
      }
      long currentVersion = existing.get("version", Long.class);
      if (request.version() != currentVersion) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "尺寸 / 重量上限已被他人修改（当前版本 " + currentVersion + "），请重新载入后再保存");
      }
      if (request.channelId() != null
          && !request.channelId().equals(existing.get("channel_id", UUID.class))) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "所属渠道不可改：上限是挂在某条渠道之上的");
      }
      sql.execute(
          "update logistics_size_limit set country_code=?, max_length_cm=?, max_width_cm=?,"
              + " max_height_cm=?, max_girth_cm=?, max_weight_kg=?, note=?,"
              + " updated_at=now(), version=version+1 where tenant_id=? and id=?",
          countryOrNull,
          request.maxLengthCm(),
          request.maxWidthCm(),
          request.maxHeightCm(),
          request.maxGirthCm(),
          request.maxWeightKg(),
          text(request.note()),
          tenant,
          id);
    }

    try {
      return sizeLimitDetail(id);
    } catch (org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, triggerMessage(e, "尺寸 / 重量上限"));
    }
  }

  public void deleteSizeLimit(UUID id) {
    UUID tenant = currentTenant();
    int rows = sql.execute("delete from logistics_size_limit where tenant_id=? and id=?", tenant, id);
    if (rows == 0) {
      throw notFound("尺寸 / 重量上限");
    }
  }

  // ==================================================================
  // 读 / 写：标记要求
  // ==================================================================

  private static final String MARK_COLUMNS =
      "m.id,m.channel_id,m.flag_kind,m.requirement_mode,m.marking_code,m.carrier_reason,"
          + "m.note,m.version,m.created_at,m.updated_at,c.channel_code,c.channel_name,"
          + "x.restriction_mode as upstream_restriction_mode";

  private static final String MARK_FROM =
      " from logistics_marking_requirement m"
          + " join logistics_channel_config c on c.tenant_id = m.tenant_id and c.id = m.channel_id"
          + " left join logistics_channel_restriction x on x.tenant_id = m.tenant_id"
          + "   and x.channel_id = m.channel_id and x.restriction_kind = m.flag_kind";

  public MarkingRequirementPage markings(
      String channelId, String flagKind, String requirementMode, int page, int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);

    List<Object> filters = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");

    String channel = text(channelId);
    if (!channel.isEmpty()) {
      where.append(" and m.channel_id = ?");
      filters.add(UUID.fromString(channel));
    }
    String kind = text(flagKind);
    if (!kind.isEmpty()) {
      where.append(" and m.flag_kind = ?");
      filters.add(kind);
    }
    String mode = text(requirementMode);
    if (!mode.isEmpty()) {
      where.append(" and m.requirement_mode = ?");
      filters.add(mode);
    }

    long total =
        sql.fetchOne("select count(*) from logistics_marking_requirement m" + where, filters.toArray())
            .get(0, Long.class);

    Record agg =
        sql.fetchOne(
            "select count(*) filter (where m.requirement_mode = 'LABEL_REQUIRED') as label_required,"
                + " count(*) filter (where m.requirement_mode = 'BLOCK') as blocked,"
                + " count(*) filter (where x.restriction_mode = 'BLOCK'"
                + "   and m.requirement_mode <> 'BLOCK') as inconsistent"
                + " from logistics_marking_requirement m"
                + " left join logistics_channel_restriction x on x.tenant_id = m.tenant_id"
                + "   and x.channel_id = m.channel_id and x.restriction_kind = m.flag_kind" + where,
            filters.toArray());

    List<Object> binds = new ArrayList<>(filters);
    binds.add(s);
    binds.add(p * s);
    var rows =
        sql.fetch(
            "select " + MARK_COLUMNS + MARK_FROM + where
                + " order by c.channel_code asc, m.flag_kind asc limit ? offset ?",
            binds.toArray());

    return new MarkingRequirementPage(
        rows.stream().map(r -> toMarkingRow(r)).toList(),
        p,
        s,
        total,
        (int) Math.ceil(total / (double) s),
        agg.get("label_required", Long.class),
        agg.get("blocked", Long.class),
        agg.get("inconsistent", Long.class));
  }

  private MarkingRequirementRow toMarkingRow(Record r) {
    String upstream = r.get("upstream_restriction_mode", String.class);
    String mode = r.get("requirement_mode", String.class);
    return new MarkingRequirementRow(
        r.get("id", UUID.class),
        r.get("channel_id", UUID.class),
        r.get("channel_code", String.class),
        r.get("channel_name", String.class),
        r.get("flag_kind", String.class),
        mode,
        r.get("marking_code", String.class),
        r.get("carrier_reason", String.class),
        upstream,
        !"BLOCK".equals(upstream) || "BLOCK".equals(mode),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  public MarkingRequirementRow markingDetail(UUID id) {
    var row = sql.fetchOne("select " + MARK_COLUMNS + MARK_FROM + " where m.id = ?", id);
    if (row == null) {
      throw notFound("标记要求");
    }
    return toMarkingRow(row);
  }

  /** 登记 / 改一条标记要求。上游 10.1.1.2 已判 BLOCK 的 flag 只允许 BLOCK（V139 trigger e）。 */
  public MarkingRequirementRow saveMarking(SaveMarkingRequirementRequest request) {
    UUID tenant = currentTenant();
    String kind = text(request.flagKind());
    String mode = text(request.requirementMode());
    String markingCode = text(request.markingCode());

    if ("LABEL_REQUIRED".equals(mode) && markingCode.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "要求贴标识就必须点名贴什么（如 UN38.3 / IATA 锂电池标）：不点名等于把"
              + "「漏打标识在清关出问题」这件事留给下一个人（10.2.2.2 面单模板要靠它绑定打印规格）");
    }

    UUID id = request.markingId();
    boolean creating = id == null;
    if (creating) {
      if (request.channelId() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新建标记要求必须指定所属渠道");
      }
      requireChannel(tenant, request.channelId());
      id = Ids.next();
      sql.execute(
          "insert into logistics_marking_requirement (id,tenant_id,channel_id,flag_kind,"
              + " requirement_mode,marking_code,carrier_reason,note) values (?,?,?,?,?,?,?,?)",
          id,
          tenant,
          request.channelId(),
          kind,
          mode,
          markingCode,
          text(request.carrierReason()),
          text(request.note()));
    } else {
      var existing =
          sql.fetchOne(
              "select version, channel_id, flag_kind from logistics_marking_requirement"
                  + " where tenant_id=? and id=?",
              tenant,
              id);
      if (existing == null) {
        throw notFound("标记要求");
      }
      long currentVersion = existing.get("version", Long.class);
      if (request.version() != currentVersion) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "标记要求已被他人修改（当前版本 " + currentVersion + "），请重新载入后再保存");
      }
      if (request.channelId() != null
          && !request.channelId().equals(existing.get("channel_id", UUID.class))) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "所属渠道不可改：标记要求是挂在某条渠道之上的");
      }
      // flag_kind 是主键的一部分：改了它等于换一条规则，走新建路径更清楚
      if (!kind.equals(existing.get("flag_kind", String.class))) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "标记种类不可改：一个渠道对一个标记只有一条要求，要换标记请新建一条");
      }
      sql.execute(
          "update logistics_marking_requirement set requirement_mode=?, marking_code=?,"
              + " carrier_reason=?, note=?, updated_at=now(), version=version+1"
              + " where tenant_id=? and id=?",
          mode,
          markingCode,
          text(request.carrierReason()),
          text(request.note()),
          tenant,
          id);
    }

    try {
      return markingDetail(id);
    } catch (org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, triggerMessage(e, "标记要求"));
    }
  }

  public void deleteMarking(UUID id) {
    UUID tenant = currentTenant();
    int rows =
        sql.execute("delete from logistics_marking_requirement where tenant_id=? and id=?", tenant, id);
    if (rows == 0) {
      throw notFound("标记要求");
    }
  }

  // ==================================================================
  // 🔴 时效估算（分国家 + 重量 → 命中重量段 → 预计到达）
  // ==================================================================

  /**
   * 估算一批渠道对某个国家 + 重量的时效。
   *
   * <p>🔴 匹配不到重量段时**返回 0 条并显式说明**，绝不用「最接近的一段」凑一个数 ——
   * 凑出来的时效会被刊登出去，然后买家投诉。而「这条渠道没有时效承诺」本身就是
   * 有用的信息（它意味着这条渠道此刻不能承诺时效）。
   *
   * <p>预计到达 = 发货时刻 + 时效天数（自然日）。刻意<b>不引入时区换算</b>：
   * 承运商的「7 个工作日」本身就是当地日历上的口径，而本模块没有承运商所在时区的
   * 数据源，凭空换算只会引入一个看起来更精确、实际是编出来的偏移。
   */
  public TransitEstimateResult estimate(TransitEstimateRequest request) {
    UUID tenant = currentTenant();
    String country = text(request.countryCode()).toUpperCase(Locale.ROOT);
    BigDecimal weight = request.weightKg();
    OffsetDateTime shipAt =
        request.shipAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : request.shipAt();
    String level = text(request.serviceLevel());

    List<Object> filters = new ArrayList<>();
    filters.add(tenant);
    filters.add(country);
    filters.add(weight);
    filters.add(weight);
    StringBuilder where =
        new StringBuilder(
            " from logistics_transit_rule r"
                + " join logistics_channel_config c"
                + "   on c.tenant_id = r.tenant_id and c.id = r.channel_id"
                + " where r.tenant_id = ?"
                + "   and r.country_code = ?"
                // 闭区间两端都含：0.5kg 恰好落在 [0,0.5] 上
                + "   and ?::numeric >= r.weight_min_kg"
                + "   and (r.weight_max_kg is null or ?::numeric < r.weight_max_kg)"
                + "   and r.readiness = 'READY' and r.status = 'ACTIVE'");
    if (!level.isEmpty()) {
      where.append(" and r.service_level = ?");
      filters.add(level);
    }
    if (request.channelIds() != null && !request.channelIds().isEmpty()) {
      String placeholders = String.join(",", Collections.nCopies(request.channelIds().size(), "?"));
      where.append(" and r.channel_id in (" + placeholders + ")");
      filters.addAll(request.channelIds());
    }
    // 只看**当前时刻生效**的档期：过期档期参与估算等于拿旧承诺对新货
    where.append(" and (r.effective_from is null or ?::timestamptz >= r.effective_from)");
    filters.add(shipAt);
    where.append(" and (r.effective_to is null or ?::timestamptz < r.effective_to)");
    filters.add(shipAt);
    where.append(" order by c.channel_code asc, r.transit_days_min asc, r.service_level asc");

    List<TransitEstimateItem> items = new ArrayList<>();
    for (Record r : sql.fetch("select " + RULE_COLUMNS + where, filters.toArray())) {
      int daysMin = r.get("transit_days_min", Integer.class);
      int daysMax = r.get("transit_days_max", Integer.class);
      OffsetDateTime from = r.get("effective_from", OffsetDateTime.class);
      OffsetDateTime to = r.get("effective_to", OffsetDateTime.class);
      items.add(
          new TransitEstimateItem(
              r.get("channel_id", UUID.class),
              r.get("channel_code", String.class),
              r.get("channel_name", String.class),
              r.get("country_code", String.class),
              r.get("service_level", String.class),
              weight,
              bandText(r.get("weight_min_kg", BigDecimal.class),
                  r.get("weight_max_kg", BigDecimal.class)),
              daysMin,
              daysMax,
              cutoffText(r.get("cutoff_time", LocalTime.class)),
              shipAt.plus(Duration.ofDays(daysMin)),
              shipAt.plus(Duration.ofDays(daysMax)),
              from == null && to == null ? "常驻（无生效区间）" : "生效区间内",
              r.get("readiness", String.class).equals("READY")
                  ? "命中这条时效承诺"
                  : "命中但不可用：" + r.get("readiness", String.class)));
    }

    // 候选渠道数（不含重量段条件）：「命中」与「未命中」都要能说清楚，
    // 只回命中条目的话 matched=2 无从判断是「有 2 条」还是「有 20 条只中 2 条」。
    List<Object> candidateFilters = new ArrayList<>();
    candidateFilters.add(tenant);
    candidateFilters.add(country);
    StringBuilder candidateWhere =
        new StringBuilder(
            " from logistics_channel_config c"
                + " where c.tenant_id = ?"
                + " and c.status = 'ACTIVE' and c.readiness = 'READY'"
                + " and exists (select 1 from logistics_channel_scope sc"
                + "   where sc.tenant_id = c.tenant_id and sc.channel_id = c.id"
                + "   and sc.scope_kind = 'COUNTRY' and sc.scope_value = ?)");
    if (!level.isEmpty()) {
      candidateWhere.append(
          " and exists (select 1 from logistics_transit_rule r"
              + "   where r.tenant_id = c.tenant_id and r.channel_id = c.id"
              + "   and r.service_level = ?)");
      candidateFilters.add(level);
    }
    if (request.channelIds() != null && !request.channelIds().isEmpty()) {
      String ids = String.join(",", Collections.nCopies(request.channelIds().size(), "?"));
      candidateWhere.append(" and c.id in (" + ids + ")");
      candidateFilters.addAll(request.channelIds());
    }
    long candidates =
        sql.fetchOne("select count(*)" + candidateWhere, candidateFilters.toArray()).get(0, Long.class);

    return new TransitEstimateResult(
        shipAt,
        items,
        items.size(),
        Math.max(candidates - items.stream().map(TransitEstimateItem::channelId).distinct().count(), 0),
        items.isEmpty()
            ? "没有任何一条启用中的时效承诺覆盖「"
                + country
                + " / "
                + weight
                + " kg」这个组合。**这不是「时效很短」，而是「这条线路此刻没有承诺」** —— "
                + "刊登阶段不要拿相邻段凑一个数，缺承诺应提示人工确认渠道。"
            : "命中的是**闭区间**重量段（两端都含）；只统计当前时刻生效的档期，"
                + "过期档期不参与估算。预计到达按自然日算，未做时区换算"
                + "（承运商口径是当地日历，缺承运商时区数据时换算只会引入假精度）。");
  }

  // ==================================================================
  // 🔴 限制命中提示（选品与刊登阶段）
  // ==================================================================

  /**
   * 判定一批渠道能否承运这个 SKU / 包裹，并在选品与刊登阶段给出命中提示。
   *
   * <p>这是蓝图「并在选品与刊登阶段提示」的落点，也是加粗句的服务端落点。规则
   * <b>全部收在这一个方法里</b>，不散落到调用方：
   *
   * <ol>
   *   <li>渠道本身不可用（DRAFT / SUSPENDED / readiness 非 READY）⇒ REVIEW；
   *   <li>上游 10.1.1.2 的渠道限制命中 ⇒ BLOCK（BATTERY/LIQUID/MAGNET/PROHIBITED_CATEGORY）；
   *   <li>本模块的标记要求命中 ⇒ BLOCK（requirement=BLOCK）；
   *   <li>超尺寸 / 重量上限（渠道级与国家级**取最严**）⇒ **BLOCK**；
   *   <li>要求贴标识 / 需人工确认 ⇒ REVIEW（不拦，但不允许自动放行）；
   *   <li>该国家 + 该重量段没有时效承诺 ⇒ REVIEW（刊登承诺不出时效，转化会掉）；
   *   <li>承运商不可用（10.1.1.1 判 PARTY_BLOCKED）⇒ **BLOCK** —— 对已拉黑的
   *       对手方下单，代价与被拒收是同一种。
   * </ol>
   */
  public LimitPrecheckResult precheck(LimitPrecheckRequest request) {
    OffsetDateTime at = request.shipAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : request.shipAt();

    // 候选渠道：显式给 id 就用它，否则取适配该国家 + 启用中 + 齐备的渠道
    List<Object> filters = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");
    if (request.channelIds() != null && !request.channelIds().isEmpty()) {
      String placeholders = String.join(",", Collections.nCopies(request.channelIds().size(), "?"));
      where.append(" and c.id in (" + placeholders + ")");
      filters.addAll(request.channelIds());
    } else {
      where.append(" and c.status = 'ACTIVE'");
    }
    String country = text(request.countryCode()).toUpperCase(Locale.ROOT);
    if (!country.isEmpty()) {
      where.append(
          " and exists (select 1 from logistics_channel_scope sc"
              + " where sc.tenant_id = c.tenant_id and sc.channel_id = c.id"
              + " and sc.scope_kind = 'COUNTRY' and sc.scope_value = ?)");
      filters.add(country);
    }
    where.append(" order by c.channel_code asc");

    var rows =
        sql.fetch(
            "select c.id,c.channel_code,c.channel_name,c.status,c.readiness,"
                + " p.party_usable as carrier_usable, p.readiness as carrier_readiness,"
                + " p.interface_type as carrier_interface_type"
                + " from logistics_channel_config c"
                + " join carrier_logistics_profile p"
                + "   on p.tenant_id = c.tenant_id and p.id = c.carrier_profile_id" + where,
            filters.toArray());

    if (rows.isEmpty()) {
      return new LimitPrecheckResult(
          at,
          List.of(),
          0,
          0,
          0,
          "本租户没有匹配这些条件的渠道：渠道要么没启用，要么适用范围不含这个国家。"
              + "空结果**不代表**可以随便选一条 —— 它代表「这些条件下没有候选渠道」。");
    }

    List<UUID> ids = rows.stream().map(r -> r.get("id", UUID.class)).toList();
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
    Object[] idBinds = ids.toArray(new UUID[0]);

    // 一次性把三张表 + 上游限制全取回来，避免逐条查（N+1）
    Map<UUID, List<ChannelRestrictionItem>> restrictions = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select channel_id, restriction_kind, subject, restriction_mode, carrier_reason"
                + " from logistics_channel_restriction"
                + " where channel_id in (" + placeholders + ") and restriction_mode <> 'ALLOWED'"
                + " order by restriction_kind, subject",
            idBinds)) {
      restrictions
          .computeIfAbsent(r.get(0, UUID.class), k -> new ArrayList<>())
          .add(
              new ChannelRestrictionItem(
                  r.get("restriction_kind", String.class),
                  r.get("subject", String.class),
                  r.get("restriction_mode", String.class),
                  r.get("carrier_reason", String.class)));
    }

    Map<UUID, List<SizeLimitRow>> limits = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select id,channel_id,country_code,max_length_cm,max_width_cm,max_height_cm,"
                + " max_girth_cm,max_weight_kg from logistics_size_limit"
                + " where channel_id in (" + placeholders + ")",
            idBinds)) {
      limits
          .computeIfAbsent(r.get("channel_id", UUID.class), k -> new ArrayList<>())
          .add(
              new SizeLimitRow(
                  r.get("id", UUID.class),
                  r.get("channel_id", UUID.class),
                  "",
                  "",
                  r.get("country_code", String.class),
                  r.get("max_length_cm", BigDecimal.class),
                  r.get("max_width_cm", BigDecimal.class),
                  r.get("max_height_cm", BigDecimal.class),
                  r.get("max_girth_cm", BigDecimal.class),
                  r.get("max_weight_kg", BigDecimal.class),
                  "",
                  "",
                  0,
                  null,
                  null));
    }

    Map<UUID, List<MarkingRequirementRow>> markings = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select id,channel_id,flag_kind,requirement_mode,marking_code,carrier_reason"
                + " from logistics_marking_requirement where channel_id in (" + placeholders + ")",
            idBinds)) {
      markings
          .computeIfAbsent(r.get("channel_id", UUID.class), k -> new ArrayList<>())
          .add(
              new MarkingRequirementRow(
                  r.get("id", UUID.class),
                  r.get("channel_id", UUID.class),
                  "",
                  "",
                  r.get("flag_kind", String.class),
                  r.get("requirement_mode", String.class),
                  r.get("marking_code", String.class),
                  r.get("carrier_reason", String.class),
                  null,
                  true,
                  "",
                  0,
                  null,
                  null));
    }

    List<String> categories = upperDistinct(request.categoryCodes());
    List<LimitPrecheckVerdict> verdicts = new ArrayList<>();
    long pass = 0;
    long review = 0;
    long block = 0;

    for (Record r : rows) {
      UUID id = r.get("id", UUID.class);
      List<LimitHit> hits = new ArrayList<>();

      // (1) 渠道本身可用性 → REVIEW（不拦，但不允许自动放行）
      String readiness = r.get("readiness", String.class);
      if (!"READY".equals(readiness)) {
        hits.add(
            hit("CHANNEL_NOT_READY", "REVIEW", readiness, channelReadinessMessage(readiness), ""));
      }
      if (!"ACTIVE".equals(r.get("status", String.class))) {
        hits.add(
            hit(
                "CHANNEL_NOT_READY",
                "REVIEW",
                r.get("status", String.class),
                "渠道状态为 " + r.get("status", String.class) + "：只有 ACTIVE 才参与承运",
                ""));
      }

      // (2) 承运商不可用 → BLOCK（对已拉黑的对手方下单，代价与被拒收同一种）
      String carrierAvailability =
          CarrierModels.CarrierAvailability.of(
              r.get("carrier_usable", Boolean.class),
              r.get("carrier_readiness", String.class),
              r.get("carrier_interface_type", String.class));
      if ("PARTY_BLOCKED".equals(carrierAvailability)) {
        hits.add(
            hit(
                "CHANNEL_NOT_READY",
                "BLOCK",
                "CARRIER_BLOCKED",
                "所属承运商已停用 / 拉黑 / 删除（上游 10.1.1.1 判定）：对它下单代价与被拒收相同",
                ""));
      }

      // (3) 上游 10.1.1.2 的渠道限制（禁限品类 / 电池 / 液体 / 磁力）
      for (ChannelRestrictionItem restriction : restrictions.getOrDefault(id, List.of())) {
        LimitHit matched = restrictionHit(restriction, categories, request);
        if (matched != null) {
          hits.add(matched);
        }
      }

      // (4) 本模块的标记要求（含危险品：V136 的四种里没有这一类）
      for (MarkingRequirementRow marking : markings.getOrDefault(id, List.of())) {
        LimitHit matched = markingHit(marking, request);
        if (matched != null) {
          hits.add(matched);
        }
      }

      // (5) 尺寸 / 重量上限（渠道级与国家级**取最严**）
      hits.addAll(sizeHits(limits.getOrDefault(id, List.of()), country, request));

      // (6) 该国家 + 该重量段没有时效承诺 → REVIEW（刊登承诺不出时效，转化会掉）
      Integer daysMin = null;
      Integer daysMax = null;
      if (request.weightKg() != null) {
        TransitEstimateResult matched = estimateFor(id, country, request.weightKg(), at);
        if (matched.items().isEmpty()) {
          hits.add(
              hit(
                  "TRANSIT_MISSING",
                  "REVIEW",
                  country.isEmpty() ? "" : country,
                  "该国家 + 该重量段没有时效承诺：刊登阶段无法承诺送达天数，需人工确认渠道",
                  ""));
        } else {
          daysMin = matched.items().get(0).transitDaysMin();
          daysMax = matched.items().get(0).transitDaysMax();
        }
      }

      String verdict = verdictOf(hits);
      switch (verdict) {
        case LimitVerdict.BLOCK -> block++;
        case LimitVerdict.REVIEW -> review++;
        default -> pass++;
      }
      verdicts.add(
          new LimitPrecheckVerdict(
              id,
              r.get("channel_code", String.class),
              r.get("channel_name", String.class),
              country,
              verdict,
              hits,
              daysMin,
              daysMax,
              LimitVerdict.message(verdict)));
    }

    return new LimitPrecheckResult(
        at,
        verdicts,
        pass,
        review,
        block,
        "判定在本服务端完成：上游 10.1.1.2 的渠道限制 + 本模块的尺寸重量上限与标记要求"
            + "汇总成一个结论，规则只有一份。刊登 / 选品阶段拿到的就是它，不需要也不应该"
            + "自己再拿规则比对一遍。");
  }

  /** 单渠道的时效命中（内部复用 estimate 的 SQL，避免为了拿天数再走一遍候选渠道）。 */
  private TransitEstimateResult estimateFor(
      UUID channelId, String country, BigDecimal weight, OffsetDateTime at) {
    if (country.isEmpty()) {
      return new TransitEstimateResult(at, List.of(), 0, 0, "未指定国家：不做时效匹配");
    }
    var rows =
        sql.fetch(
            "select transit_days_min, transit_days_max, weight_min_kg, weight_max_kg,"
                + " country_code, service_level, cutoff_time from logistics_transit_rule"
                + " where channel_id = ? and country_code = ?"
                + "   and ?::numeric >= weight_min_kg"
                + "   and (weight_max_kg is null or ?::numeric < weight_max_kg)"
                + "   and readiness = 'READY' and status = 'ACTIVE'"
                + "   and (effective_from is null or ?::timestamptz >= effective_from)"
                + "   and (effective_to is null or ?::timestamptz < effective_to)"
                + " order by transit_days_min asc, service_level asc",
            channelId,
            country,
            weight,
            weight,
            at,
            at);
    List<TransitEstimateItem> items = new ArrayList<>();
    for (Record r : rows) {
      int min = r.get("transit_days_min", Integer.class);
      int max = r.get("transit_days_max", Integer.class);
      items.add(
          new TransitEstimateItem(
              channelId,
              "",
              "",
              r.get("country_code", String.class),
              r.get("service_level", String.class),
              weight,
              bandText(r.get("weight_min_kg", BigDecimal.class),
                  r.get("weight_max_kg", BigDecimal.class)),
              min,
              max,
              cutoffText(r.get("cutoff_time", LocalTime.class)),
              at.plus(Duration.ofDays(min)),
              at.plus(Duration.ofDays(max)),
              "",
              "命中这条时效承诺"));
    }
    return new TransitEstimateResult(at, items, items.size(), 0, "");
  }

  /** 汇总一条渠道的命中，取最严的一档（BLOCK > REVIEW > PASS）。 */
  private static String verdictOf(List<LimitHit> hits) {
    String verdict = LimitVerdict.PASS;
    for (LimitHit h : hits) {
      verdict = LimitVerdict.worse(verdict, h.severity());
    }
    return verdict;
  }

  private static LimitHit hit(
      String kind, String severity, String subject, String message, String carrierReason) {
    return new LimitHit(kind, severity, subject, message, carrierReason == null ? "" : carrierReason);
  }

  /**
   * 这条上游渠道限制是否被这个 SKU 命中；没命中返回 {@code null}。
   *
   * <p>三类限制的命中口径各不相同，混成一条 if 正是「漏掉磁力」那类 bug 的来源：
   * <ul>
   *   <li>{@code PROHIBITED_CATEGORY} —— SKU 任一品类落在禁限清单里
   *   <li>{@code BATTERY} / {@code LIQUID} / {@code MAGNET} —— **包裹属性**，不看品类：
   *       一个带电件在「电器」品类下和在「配件」品类下一样会被拒收
   * </ul>
   */
  private static LimitHit restrictionHit(
      ChannelRestrictionItem restriction, List<String> categories, LimitPrecheckRequest request) {
    boolean matched =
        switch (restriction.restrictionKind()) {
          case "PROHIBITED_CATEGORY" ->
              categories.contains(text(restriction.subject()).toUpperCase(Locale.ROOT));
          case "BATTERY" -> request.containsBattery();
          case "LIQUID" -> request.containsLiquid();
          case "MAGNET" -> request.containsMagnet();
          default -> false;
        };
    if (!matched) {
      return null;
    }
    String severity =
        "BLOCK".equals(restriction.restrictionMode()) ? LimitVerdict.BLOCK : LimitVerdict.REVIEW;
    String subject =
        "PROHIBITED_CATEGORY".equals(restriction.restrictionKind())
            ? text(restriction.subject())
            : restriction.restrictionKind();
    String message =
        switch (restriction.restrictionKind()) {
          case "PROHIBITED_CATEGORY" -> "品类「" + subject + "」被渠道列为禁限品类";
          case "BATTERY" -> "货件带电池：渠道限制带电";
          case "LIQUID" -> "货件带液体：渠道限制液体";
          case "MAGNET" -> "货件带磁力件：渠道限制磁力";
          default -> restriction.restrictionKind();
        };
    return hit(restriction.restrictionKind(), severity, subject, message,
        text(restriction.carrierReason()));
  }

  /** 标记要求命中口径：带电 / 带液 / 带磁 / 带危险品各比自己的那一个布尔。 */
  private static LimitHit markingHit(
      MarkingRequirementRow marking, LimitPrecheckRequest request) {
    boolean matched =
        switch (marking.flagKind()) {
          case "BATTERY" -> request.containsBattery();
          case "LIQUID" -> request.containsLiquid();
          case "MAGNET" -> request.containsMagnet();
          case "DANGEROUS_GOODS" -> request.containsDangerousGoods();
          default -> false;
        };
    if (!matched) {
      return null;
    }
    String severity = LimitVerdict.severityForRequirement(marking.requirementMode());
    if ("PASS".equals(severity)) {
      return null;
    }
    String reason =
        text(marking.carrierReason()).isEmpty() ? "" : text(marking.carrierReason());
    String message =
        switch (marking.requirementMode()) {
          case "BLOCK" -> "渠道拒绝承运" + flagLabel(marking.flagKind());
          case "LABEL_REQUIRED" ->
              "可发但必须贴「" + marking.markingCode() + "」标识，漏打会在清关出问题";
          default -> "渠道对" + flagLabel(marking.flagKind()) + "要求人工确认";
        };
    return hit(marking.flagKind(), severity, marking.flagKind(), message, reason);
  }

  /**
   * 尺寸 / 重量上限命中。
   *
   * <p>🔴 判定**取最严**：渠道级与国家级两条都看，谁先超谁就是命中项。
   * 只取国家级会漏掉「渠道级更严」的情形，只取渠道级会漏掉「某国另有一条更严」。
   */
  private static List<LimitHit> sizeHits(
      List<SizeLimitRow> limits, String country, LimitPrecheckRequest request) {
    List<LimitHit> hits = new ArrayList<>();
    for (SizeLimitRow limit : limits) {
      String limitCountry = limit.countryCode() == null ? "" : limit.countryCode();
      // 国家级只在命中该国时生效；渠道级（limitCountry 为空）对所有国家生效
      if (!limitCountry.isEmpty() && !limitCountry.equals(country)) {
        continue;
      }
      String scope = limitCountry.isEmpty() ? "渠道级" : "国家级 · " + limitCountry;

      addSizeHit(hits, "LENGTH", "长", limit.maxLengthCm(), request.lengthCm(), scope);
      addSizeHit(hits, "WIDTH", "宽", limit.maxWidthCm(), request.widthCm(), scope);
      addSizeHit(hits, "HEIGHT", "高", limit.maxHeightCm(), request.heightCm(), scope);

      if (limit.maxGirthCm() != null
          && request.lengthCm() != null
          && request.widthCm() != null
          && request.heightCm() != null) {
        BigDecimal girth =
            request.lengthCm().add(request.widthCm()).add(request.heightCm().multiply(BigDecimal.valueOf(2)));
        if (girth.compareTo(limit.maxGirthCm()) > 0) {
          hits.add(
              hit(
                  "DIMENSION",
                  LimitVerdict.BLOCK,
                  scope,
                  "周长和（长+宽+高×2）" + girth + " cm 超过" + scope + "上限 " + limit.maxGirthCm()
                      + " cm：承运商会拒收",
                  ""));
        }
      }
      addSizeHit(hits, "WEIGHT", "重量", limit.maxWeightKg(), request.weightKg(), scope);
    }
    return hits;
  }

  private static void addSizeHit(
      List<LimitHit> hits,
      String kind,
      String label,
      BigDecimal max,
      BigDecimal actual,
      String scope) {
    if (max == null || actual == null || actual.compareTo(max) <= 0) {
      return;
    }
    hits.add(
        hit(
            kind,
            LimitVerdict.BLOCK,
            scope,
            label + " " + actual + " 超过" + scope + "上限 " + max + "：超限件会被承运商拒收并产生额外费用",
            ""));
  }

  private static String flagLabel(String flagKind) {
    return switch (flagKind == null ? "" : flagKind) {
      case "BATTERY" -> "带电池的货件";
      case "LIQUID" -> "带液体的货件";
      case "MAGNET" -> "带磁力件的货件";
      case "DANGEROUS_GOODS" -> "危险品";
      default -> flagKind;
    };
  }

  private static String channelReadinessMessage(String readiness) {
    return switch (readiness == null ? "" : readiness) {
      case "READY" -> "渠道配置齐备";
      case "NO_BASE_RATE" -> "渠道缺基础单价（上游 10.1.1.2 判定）：试算算不出运费";
      case "NO_SCOPE" -> "渠道一条适用维度都没登记（上游 10.1.1.2 判定）：对谁都不可用";
      case "SUSPENDED" -> "渠道已停用（上游 10.1.1.2 判定）";
      default -> readiness;
    };
  }

  private static List<String> upperDistinct(List<String> values) {
    if (values == null) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (String value : values) {
      String v = text(value).toUpperCase(Locale.ROOT);
      if (!v.isEmpty() && !out.contains(v)) {
        out.add(v);
      }
    }
    return out;
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  private static boolean effectiveNow(
      OffsetDateTime from, OffsetDateTime to, OffsetDateTime at) {
    return (from == null || !at.isBefore(from)) && (to == null || at.isBefore(to));
  }

  /** {@code time} 列统一按 HH:mm 展示（承运商的口径是当地墙钟，不带偏移）。 */
  /** 重量段的展示文案：半开区间，上限为空时显示「≥X kg」。 */
  private static String bandText(BigDecimal min, BigDecimal max) {
    // ⚠️ stripTrailingZeros 后必须 toPlainString：否则 30.000000 会变成 3E+1。
    String lo = min.stripTrailingZeros().toPlainString();
    return max == null ? "≥ " + lo + " kg" : lo + " ~ " + max.stripTrailingZeros().toPlainString() + " kg";
  }

  private static String cutoffText(LocalTime cutoff) {
    return cutoff == null ? "" : cutoff.format(DateTimeFormatter.ofPattern("HH:mm"));
  }

  private static LocalTime parseCutoff(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "截单时间必填（HH:mm）");
    }
    try {
      return LocalTime.parse(value.length() == 4 ? "0" + value : value);
    } catch (DateTimeParseException e) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "截单时间格式必须是 HH:mm（收到：" + value + "）");
    }
  }

  private static String escapeLike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /**
   * 把触发器的 check_violation 原文翻成 422 —— 触发器里的理由比服务层编得准
   * （它知道与**哪一段**重叠），所以这里不重写消息，只保留原文。
   */
  private static String triggerMessage(RuntimeException e, String what) {
    var cause = e.getCause() == null ? e : e.getCause();
    String message = cause.getMessage();
    if (message != null && message.contains(":")) {
      String detail = message.substring(message.indexOf(':') + 1).trim();
      if (!detail.isEmpty()) {
        return what + "：" + detail;
      }
    }
    return what + "被数据库规则拒绝（请检查约束是否自相矛盾）";
  }
}
