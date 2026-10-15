package com.starcon.channel;

import static com.starcon.channel.LogisticsChannelModels.*;

import com.starcon.channel.LogisticsChannelModels.ChannelScopeItem;
import com.starcon.channel.LogisticsChannelModels.ChannelSurchargeItem;
import com.starcon.channel.LogisticsChannelModels.ChannelRestrictionItem;
import com.starcon.channel.LogisticsChannelModels.RestrictionVerdict;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 10.1.1.2 渠道配置。
 *
 * <p>蓝图：「配置具体物流渠道：所属承运商、渠道代码、适用店铺/国家/品类、计费方式
 * （按重/按件/按体积）、附加费（燃油、偏远、旺季）、申报限制、保价规则」，
 * 输出「渠道配置；供运费试算与自动匹配使用」。
 *
 * <p>🔴 本类唯一不能妥协的约束是那条加粗句：**渠道限制（禁限品类、电池液体、磁力）
 * 要硬拦截，否则会被承运商拒收产生额外费用。** 它的落地方式是三件事同时做：
 *
 * <ol>
 *   <li><b>写入路径冲突校验</b>（{@link #save}）：适用品类与禁限品类不得命中同一个
 *       品类。V136 的两个触发器从 DB 侧双向兜底，所以绕过服务层直接写 SQL 造出
 *       一条「自己和自己打架」的渠道配置同样会被拒 —— 这正是「硬」与「前端判断」
 *       的分界线。
 *   <li><b>服务端判定端点</b>（{@link #eligibility}）：下游下单前问它「这个包裹能不能
 *       走这条渠道」，三档结论 PASS / REVIEW / BLOCK，理由逐条给出。判断规则集中在
 *       这一个方法里，**不散落到每个调用方** —— 散落就意味着总有一个调用方会漏掉
 *       「磁力」这一条。
 *   <li><b>承运商不可用时也不放行</b>：承运商侧 PARTY_BLOCKED（10.1.1.1 判定）时
 *       直接给 BLOCK，因为对一个已拉黑的对手方下单，代价与被拒收是同一种。
 * </ol>
 *
 * <p>数据来源纪律：承运商档案<b>同库同服务</b>读（10.1.1.1 已落在 channel，V134 建表），
 * 但本服务仍然只持有 {@code carrier_profile_id} 一个外键，**不复制承运商任何字段** ——
 * 复制一份就会造出「同一个承运商在两处各改一次」。跨服务方向（本模块是 trade 的
 * 上游数据源）一律经授权 HTTP 端点提供，**本类一条 SQL 都不碰别的服务的库**。
 */
@Service
public class LogisticsChannelService {

  private final DSLContext sql;

  public LogisticsChannelService(DSLContext sql) {
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

  /**
   * 跨租户与不存在返回<b>同一个 404 且理由逐字相同</b>（照抄 10.1.1.1 同款口径）。
   */
  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "渠道配置不存在");
  }

  private static final String SELECT_COLUMNS =
      "c.id,c.carrier_profile_id,c.channel_code,c.channel_name,c.status,c.billing_mode,"
          + "c.billing_currency,c.base_rate,c.charge_divisor,c.min_charge,"
          + "c.declaration_min_value,c.declaration_max_value,c.declaration_currency,"
          + "c.insurance_mode,c.insurance_rate_pct,c.insurance_min_fee,c.insurance_max_value,"
          + "c.readiness,c.note,c.version,c.created_at,c.updated_at,"
          + "p.party_code as carrier_code,p.party_name as carrier_name,"
          + "p.party_usable as carrier_usable,p.readiness as carrier_readiness,"
          + "p.interface_type as carrier_interface_type";

  private static final String FROM_CLAUSE =
      " from logistics_channel_config c"
          + " join carrier_logistics_profile p"
          + "   on p.tenant_id = c.tenant_id and p.id = c.carrier_profile_id";

  // ==================================================================
  // 能力声明：未交付的段显式 available=false + ownerModule + 影响
  // ==================================================================

  public LogisticsChannelCapabilities capabilities() {
    List<ChannelCapabilityDimension> dimensions =
        List.of(
            new ChannelCapabilityDimension(
                "carrier_binding",
                "所属承运商（挂在 10.1.1.1 承运商档案之下）",
                true,
                "本服务 V136 logistics_channel_config.carrier_profile_id（复合 FK 到 V134）",
                "",
                "承运商不可用时判定端点直接 BLOCK —— 对已拉黑的对手方下单代价与被拒收相同"),
            new ChannelCapabilityDimension(
                "applicable_scope",
                "适用店铺 / 国家 / 品类（多值，可按其筛选）",
                true,
                "本服务 V136 logistics_channel_scope（每值一行，不是逗号串）",
                "",
                "缺了适用范围，渠道对谁都不可用，readiness 停在 NO_SCOPE"),
            new ChannelCapabilityDimension(
                "billing_mode",
                "计费方式（按重 / 按件 / 按体积）",
                true,
                "本服务 V136 billing_mode + base_rate + charge_divisor",
                "",
                "「按件」时折算基数被 DB 钉死为 1，避免出现按件却按半公斤算"),
            new ChannelCapabilityDimension(
                "surcharges",
                "附加费（燃油 / 偏远 / 旺季，旺季带生效区间）",
                true,
                "本服务 V136 logistics_channel_surcharge",
                "",
                "旺季费按时刻区间生效，判定端点按读取时刻给出当前生效附加费"),
            new ChannelCapabilityDimension(
                "declaration_limits",
                "申报限制（申报价值上下限 + 申报币种）",
                true,
                "本服务 V136 declaration_min_value / declaration_max_value / declaration_currency",
                "",
                "超上限的货件会被判 REVIEW —— 多数渠道对申报价值有上下限"),
            new ChannelCapabilityDimension(
                "insurance_rules",
                "保价规则（保价方式 / 费率 / 最低保费 / 保价上限）",
                true,
                "本服务 V136 insurance_*（保价上限独立成列，是一道硬边界）",
                "",
                "保价金额超上限判 BLOCK —— 超过上限承运商不接保，寄丢了赔不到"),
            new ChannelCapabilityDimension(
                "restriction_enforcement",
                "🔴 渠道限制硬拦截（禁限品类 / 电池 / 液体 / 磁力）",
                true,
                "本服务 V136 logistics_channel_restriction + 双向触发器 + POST eligibility 判定端点",
                "",
                "判定在服务端做，规则只有一份；调用方拿不到限制字段就无从自己判断"),
            new ChannelCapabilityDimension(
                "rate_estimation",
                "运费试算（渠道 × 运费 × 时效 对照表）",
                false,
                "下游 10.1.2.1 运费计算器未开发",
                "10.1.2.1",
                "渠道配置齐备但算不出运费，渠道选择仍靠人工询价"),
            new ChannelCapabilityDimension(
                "channel_comparison",
                "渠道比价",
                false,
                "下游 10.1.2.2 渠道比价未开发",
                "10.1.2.2",
                "无法在多个渠道之间自动选最优，只能人工比"),
            new ChannelCapabilityDimension(
                "transit_time",
                "渠道时效与限制（预计到达 / 截单时间）",
                false,
                "10.1.1.3 时效与限制未开发",
                "10.1.1.3",
                "无法提示某渠道是否会错过截单，判定只覆盖限制不覆盖时效"),
            new ChannelCapabilityDimension(
                "carrier_connection",
                "承运商系统对接（渠道真接通、能自动取运单号与轨迹）",
                false,
                "10.1.1.1 承运商档案已交付，但承运商接口适配器未接通",
                "10.1.1.1",
                "渠道可配可判定，但仍不会自动下单 —— 判定通过不等于单子已下"));
    return new LogisticsChannelCapabilities(
        dimensions,
        List.of("BY_WEIGHT", "BY_PIECE", "BY_VOLUME"),
        List.of("FUEL", "REMOTE_AREA", "PEAK_SEASON"),
        List.of("PROHIBITED_CATEGORY", "BATTERY", "LIQUID", "MAGNET"),
        List.of("BLOCK", "MANUAL_REVIEW"),
        List.of("DRAFT", "ACTIVE", "SUSPENDED"),
        List.of("READY", "NO_BASE_RATE", "NO_SCOPE", "SUSPENDED"),
        List.of("SHOP", "COUNTRY", "CATEGORY"),
        "渠道配置挂在 10.1.1.1 承运商档案之下：承运商身份（名称 / 编码 / 接口类型 / 账号）"
            + "是上游只读，本模块的写请求一个承运商字段都不接受，改身份要回 /logistics/carriers。",
        "🔴 渠道限制由**服务端**判定（POST eligibility），规则只有一份；写入时"
            + "「适用品类 ⇄ 禁限品类」冲突在 DB 层双向拒绝，绕过服务层写 SQL 同样被拒。");
  }

  // ==================================================================
  // 读：真分页 + 真筛选 + 不受分页影响的真聚合
  // ==================================================================

  public LogisticsChannelPage channels(
      String keyword,
      String carrierProfileId,
      String billingMode,
      String status,
      String readiness,
      String scopeKind,
      String scopeValue,
      String restrictionKind,
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
      where.append(" and (c.channel_code ilike ? or c.channel_name ilike ?)");
      String like = "%" + escapeLike(kw) + "%";
      filters.add(like);
      filters.add(like);
    }
    String carrier = text(carrierProfileId);
    if (!carrier.isEmpty()) {
      where.append(" and c.carrier_profile_id = ?");
      filters.add(UUID.fromString(carrier));
    }
    String mode = text(billingMode);
    if (!mode.isEmpty()) {
      where.append(" and c.billing_mode = ?");
      filters.add(mode);
    }
    String st = text(status);
    if (!st.isEmpty()) {
      where.append(" and c.status = ?");
      filters.add(st);
    }
    String rdy = text(readiness);
    if (!rdy.isEmpty()) {
      where.append(" and c.readiness = ?");
      filters.add(rdy);
    }
    // 适用维度是真多值关系，走 EXISTS 而不是 like 逗号串。
    String kind = text(scopeKind);
    String value = text(scopeValue);
    if (!kind.isEmpty() && !value.isEmpty()) {
      where.append(
          " and exists (select 1 from logistics_channel_scope s"
              + " where s.tenant_id = c.tenant_id and s.channel_id = c.id"
              + " and s.scope_kind = ? and s.scope_value = ?)");
      filters.add(kind);
      filters.add(kind.equals("COUNTRY") ? value.toUpperCase(Locale.ROOT) : value);
    }
    String rKind = text(restrictionKind);
    if (!rKind.isEmpty()) {
      where.append(
          " and exists (select 1 from logistics_channel_restriction r"
              + " where r.tenant_id = c.tenant_id and r.channel_id = c.id"
              + " and r.restriction_kind = ?)");
      filters.add(rKind);
    }

    long total =
        sql.fetchOne(
                "select count(*) from logistics_channel_config c" + where, filters.toArray())
            .get(0, Long.class);

    List<Object> binds = new ArrayList<>(filters);
    binds.add(s);
    binds.add(p * s);
    var rows =
        sql.fetch(
            "select " + SELECT_COLUMNS + FROM_CLAUSE + where
                + " order by " + orderBy(sort) + " limit ? offset ?",
            binds.toArray());

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return new LogisticsChannelPage(
        hydrate(rows, now).values().stream().toList(),
        p,
        s,
        total,
        (int) Math.ceil(total / (double) s),
        // 汇总走**独立查询**：与 limit/offset 无关。
        summary(where.toString(), filters, now));
  }

  /** 逐批取子表，批量装配。刻意<b>不做 N+1</b>：一页 50 行就是 150 次查询。 */
  private Map<UUID, LogisticsChannelRow> hydrate(
      List<? extends Record> rows, OffsetDateTime at) {
    Map<UUID, LogisticsChannelRow> out = new LinkedHashMap<>();
    if (rows.isEmpty()) {
      return out;
    }
    List<UUID> ids = rows.stream().map(r -> r.get("id", UUID.class)).toList();
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));

    Map<UUID, List<ChannelScopeItem>> scopes = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select channel_id, scope_kind, scope_value from logistics_channel_scope"
                + " where channel_id in (" + placeholders + ") order by scope_kind, scope_value",
            ids.toArray(new UUID[0]))) {
      scopes
          .computeIfAbsent(r.get(0, UUID.class), k -> new ArrayList<>())
          .add(new ChannelScopeItem(r.get("scope_kind", String.class), r.get("scope_value", String.class)));
    }

    Map<UUID, List<ChannelSurchargeItem>> surcharges = new LinkedHashMap<>();
    Map<UUID, List<ChannelSurchargeItem>> active = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select channel_id, surcharge_kind, charge_mode, amount, effective_from, effective_to,"
                + " note from logistics_channel_surcharge"
                + " where channel_id in (" + placeholders + ") order by surcharge_kind",
            ids.toArray(new UUID[0]))) {
      var item =
          new ChannelSurchargeItem(
              r.get("surcharge_kind", String.class),
              r.get("charge_mode", String.class),
              r.get("amount", BigDecimal.class),
              r.get("effective_from", OffsetDateTime.class),
              r.get("effective_to", OffsetDateTime.class),
              r.get("note", String.class));
      UUID id = r.get(0, UUID.class);
      surcharges.computeIfAbsent(id, k -> new ArrayList<>()).add(item);
      // 旺季费只有落在生效区间内才算当前生效 —— 这就是它必须存区间的原因。
      if (item.effectiveFrom() == null
          || (!at.isBefore(item.effectiveFrom()) && at.isBefore(item.effectiveTo()))) {
        active.computeIfAbsent(id, k -> new ArrayList<>()).add(item);
      }
    }

    Map<UUID, List<ChannelRestrictionItem>> restrictions = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select channel_id, restriction_kind, subject, restriction_mode, carrier_reason"
                + " from logistics_channel_restriction"
                + " where channel_id in (" + placeholders + ") order by restriction_kind, subject",
            ids.toArray(new UUID[0]))) {
      restrictions
          .computeIfAbsent(r.get(0, UUID.class), k -> new ArrayList<>())
          .add(
              new ChannelRestrictionItem(
                  r.get("restriction_kind", String.class),
                  r.get("subject", String.class),
                  r.get("restriction_mode", String.class),
                  r.get("carrier_reason", String.class)));
    }

    for (Record r : rows) {
      UUID id = r.get("id", UUID.class);
      out.put(id, toRow(r, scopes, surcharges, active, restrictions));
    }
    return out;
  }

  private LogisticsChannelRow toRow(
      Record r,
      Map<UUID, List<ChannelScopeItem>> scopes,
      Map<UUID, List<ChannelSurchargeItem>> surcharges,
      Map<UUID, List<ChannelSurchargeItem>> active,
      Map<UUID, List<ChannelRestrictionItem>> restrictions) {
    UUID id = r.get("id", UUID.class);
    List<ChannelSurchargeItem> activeList = active.getOrDefault(id, List.of());
    String carrierAvailability = carrierAvailability(r);
    return new LogisticsChannelRow(
        id,
        r.get("carrier_profile_id", UUID.class),
        r.get("carrier_code", String.class),
        r.get("carrier_name", String.class),
        carrierAvailability,
        r.get("channel_code", String.class),
        r.get("channel_name", String.class),
        r.get("status", String.class),
        r.get("billing_mode", String.class),
        r.get("billing_currency", String.class),
        r.get("base_rate", BigDecimal.class),
        r.get("charge_divisor", BigDecimal.class),
        r.get("min_charge", BigDecimal.class),
        r.get("declaration_min_value", BigDecimal.class),
        r.get("declaration_max_value", BigDecimal.class),
        r.get("declaration_currency", String.class),
        r.get("insurance_mode", String.class),
        r.get("insurance_rate_pct", BigDecimal.class),
        r.get("insurance_min_fee", BigDecimal.class),
        r.get("insurance_max_value", BigDecimal.class),
        scopes.getOrDefault(id, List.of()),
        surcharges.getOrDefault(id, List.of()),
        activeList,
        restrictions.getOrDefault(id, List.of()),
        r.get("readiness", String.class),
        readinessMessage(r.get("readiness", String.class)),
        activeList.stream().anyMatch(s -> "PEAK_SEASON".equals(s.surchargeKind())),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /**
   * 承运商侧的可用性（10.1.1.1 的合成口径），在这里被复用一次。
   *
   * <p>刻意<b>不再实现一遍</b>：两处各写一份判定，早晚会在某一处漏掉一个分支，
   * 然后「渠道可判定」与「承运商可判定」给出互相矛盾的答案。
   */
  private static String carrierAvailability(Record r) {
    boolean usable = r.get("carrier_usable", Boolean.class);
    String readiness = r.get("carrier_readiness", String.class);
    String interfaceType = r.get("carrier_interface_type", String.class);
    return CarrierModels.CarrierAvailability.of(usable, readiness, interfaceType);
  }

  private static String readinessMessage(String readiness) {
    return switch (readiness == null ? "" : readiness) {
      case "READY" -> "配置齐备，可参与运费试算与自动匹配";
      case "NO_BASE_RATE" -> "基础单价为 0：试算算不出运费";
      case "NO_SCOPE" -> "一条适用维度都没登记：这个渠道对谁都不可用";
      case "SUSPENDED" -> "渠道已停用";
      default -> readiness;
    };
  }

  private static String escapeLike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static String orderBy(String sort) {
    return switch (text(sort)) {
      case "CODE_ASC" -> "c.channel_code asc";
      case "RATE_ASC" -> "c.base_rate asc, c.channel_code asc";
      case "UPDATED_AT_ASC" -> "c.updated_at asc";
      default -> "c.channel_name asc, c.channel_code asc";
    };
  }

  /** 汇总：独立查询，覆盖 where 条件下的<b>全部</b>行，与分页无关。 */
  private LogisticsChannelSummary summary(String where, List<Object> filters, OffsetDateTime at) {
    // 旺季命中数按**读取时刻**算，与列表里的 activeSurcharges 同一口径。
    String peak =
        " exists (select 1 from logistics_channel_surcharge ps"
            + " where ps.tenant_id = c.tenant_id and ps.channel_id = c.id"
            + " and ps.surcharge_kind = 'PEAK_SEASON'"
            + " and ?::timestamptz >= ps.effective_from and ?::timestamptz < ps.effective_to)";
    // ⚠️ 占位符顺序：PG 按**SQL 文本里 ? 出现的先后**绑定，不是按 values 顺序。
    //    peak 表达式写在 SELECT 列表里、where 在它后面，所以两个时刻参数必须排在
    //    filters **之前**。顺序反了就会把 'COUNTRY' 当成 timestamptz 绑进去 ——
    //    症状是一句莫名其妙的 "invalid input syntax for type timestamp"。
    List<Object> binds = new ArrayList<>();
    binds.add(at);
    binds.add(at);
    binds.addAll(filters);
    Record r =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where c.readiness = 'READY') as ready,"
                + " count(*) filter (where c.readiness = 'NO_BASE_RATE') as no_base_rate,"
                + " count(*) filter (where c.readiness = 'NO_SCOPE') as no_scope,"
                + " count(*) filter (where c.readiness = 'SUSPENDED') as suspended,"
                + " count(*) filter (where c.status = 'DRAFT') as draft,"
                + " count(*) filter (where exists (select 1 from logistics_channel_restriction rr"
                + "   where rr.tenant_id = c.tenant_id and rr.channel_id = c.id"
                + "   and rr.restriction_mode <> 'ALLOWED')) as with_restrictions,"
                + " count(*) filter (where " + peak + ") as peak_active"
                + " from logistics_channel_config c" + where,
            binds.toArray());
    return new LogisticsChannelSummary(
        r.get("total", Long.class),
        r.get("ready", Long.class),
        r.get("no_base_rate", Long.class),
        r.get("no_scope", Long.class),
        r.get("suspended", Long.class),
        r.get("draft", Long.class),
        r.get("with_restrictions", Long.class),
        r.get("peak_active", Long.class));
  }

  /** 概览：全量口径（不受列表筛选影响）的计费方式 / 限制分布。 */
  public LogisticsChannelOverview overview() {
    LogisticsChannelSummary all = summary(" where 1=1", List.of(), OffsetDateTime.now(ZoneOffset.UTC));
    return new LogisticsChannelOverview(
        all,
        distribution("billing_mode", List.of(
            new String[] {"BY_WEIGHT", "按重"},
            new String[] {"BY_PIECE", "按件"},
            new String[] {"BY_VOLUME", "按体积"})),
        restrictionDistribution(),
        "统计覆盖本租户全部渠道配置，不随列表筛选或分页变化；"
            + "旺季生效数按读取时刻计算，与列表行里的 activeSurcharges 同一口径。");
  }

  private List<LogisticsChannelCount> distribution(String column, List<String[]> options) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select " + column + " as k, count(*) as n from logistics_channel_config"
                + " where " + column + " is not null group by " + column)) {
      counts.put(r.get("k", String.class), r.get("n", Long.class));
    }
    List<LogisticsChannelCount> out = new ArrayList<>();
    for (String[] option : options) {
      out.add(new LogisticsChannelCount(option[0], option[1], counts.getOrDefault(option[0], 0L)));
    }
    return out;
  }

  /** 限制分布按**种类**统计（禁限品类算一条，电池/液体/磁力各算一条）。 */
  private List<LogisticsChannelCount> restrictionDistribution() {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select restriction_kind as k, count(*) as n from logistics_channel_restriction"
                + " where restriction_mode <> 'ALLOWED' group by restriction_kind")) {
      counts.put(r.get("k", String.class), r.get("n", Long.class));
    }
    return List.of(
        new LogisticsChannelCount("PROHIBITED_CATEGORY", "禁限品类", counts.getOrDefault("PROHIBITED_CATEGORY", 0L)),
        new LogisticsChannelCount("BATTERY", "电池", counts.getOrDefault("BATTERY", 0L)),
        new LogisticsChannelCount("LIQUID", "液体", counts.getOrDefault("LIQUID", 0L)),
        new LogisticsChannelCount("MAGNET", "磁力", counts.getOrDefault("MAGNET", 0L)));
  }

  public LogisticsChannelRow detail(UUID id) {
    var row = sql.fetchOne("select " + SELECT_COLUMNS + FROM_CLAUSE + " where c.id = ?", id);
    if (row == null) {
      throw notFound();
    }
    return hydrate(List.of(row), OffsetDateTime.now(ZoneOffset.UTC)).get(id);
  }

  // ==================================================================
  // 写：渠道配置
  // ==================================================================

  /**
   * 登记 / 改一个渠道配置。
   *
   * <p>⚠️ 写入顺序有讲究，与 10.1.1.1 同款原因：先把 {@code readiness} 按新值算出来
   * 写进去，**再**换适用维度 —— 因为 DB 触发器是在每一行写入时按当时的子表重算比对的。
   * 顺序反了，触发器会在中间那次写入上拒掉一个其实正确的中间态。
   *
   * <p>限制（restrictions）**先写**，适用维度（scopes）**后写**：反向触发器会在
   * 写子表那一刻检查对面那张表，两张表先后顺序不重要，但**冲突必须在这一对写入里
   * 一次都不发生** —— 所以这里先做一次显式冲突检查，把错误说清楚是哪个品类撞了。
   */
  public LogisticsChannelRow save(SaveChannelRequest request) {
    UUID tenant = currentTenant();
    String code = text(request.channelCode());
    String name = text(request.channelName());
    String mode = text(request.billingMode());
    String currency = text(request.billingCurrency()).toUpperCase(Locale.ROOT);
    BigDecimal baseRate = request.baseRate();
    BigDecimal divisor =
        request.chargeDivisor() == null
            ? defaultDivisor(mode)
            : request.chargeDivisor();
    BigDecimal minCharge = request.minCharge() == null ? BigDecimal.ZERO : request.minCharge();

    List<ChannelScopeItem> scopes = request.scopes();
    List<ChannelSurchargeItem> surcharges = request.surcharges();
    List<ChannelRestrictionItem> restrictions = request.restrictions();

    // 🔴 适用品类 ⇄ 禁限品类：写入前显式校验，把「哪个品类撞了哪条限制」说清楚。
    //     V136 的两个触发器在 DB 侧兜底同一个规则（双向），这里是为了给出可读的理由。
    Map<String, ChannelRestrictionItem> blockedCategories = new LinkedHashMap<>();
    for (ChannelRestrictionItem restriction : restrictions) {
      if ("PROHIBITED_CATEGORY".equals(restriction.restrictionKind())
          && !"ALLOWED".equals(restriction.restrictionMode())) {
        blockedCategories.put(text(restriction.subject()), restriction);
      }
    }
    for (ChannelScopeItem scope : scopes) {
      if (!"CATEGORY".equals(scope.scopeKind())) {
        continue;
      }
      ChannelRestrictionItem hit = blockedCategories.get(text(scope.scopeValue()));
      if (hit != null) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "品类「"
                + text(scope.scopeValue())
                + "」既被登记为适用品类，又被列为禁限（"
                + hit.restrictionMode()
                + "）—— 下单会被承运商拒收并产生额外费用，两项必须去掉一项");
      }
    }

    UUID id = request.channelId();
    boolean creating = id == null;
    if (creating) {
      // 新建：确认承运商档案在本租户下真的存在（跨租户挂载由 FK 兜，这里给可读理由）
      if (request.carrierProfileId() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新建渠道配置必须指定所属承运商");
      }
      var carrier =
          sql.fetchOne(
              "select id from carrier_logistics_profile where tenant_id=? and id=?",
              tenant,
              request.carrierProfileId());
      if (carrier == null) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "承运商档案不存在：请先在 /logistics/carriers 登记物流侧档案");
      }
      id = Ids.next();
      // 新建时子表还是空的，触发器看到的就是「NO_SCOPE」—— 那正是正确的中间态：
      // 渠道刚建出来还没登记适用范围，确实不可用。
      sql.execute(
          "insert into logistics_channel_config"
              + " (id,tenant_id,carrier_profile_id,channel_code,channel_name,status,billing_mode,"
              + "  billing_currency,base_rate,charge_divisor,min_charge,"
              + "  declaration_min_value,declaration_max_value,declaration_currency,"
              + "  insurance_mode,insurance_rate_pct,insurance_min_fee,insurance_max_value,"
              + "  readiness,note)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          id,
          tenant,
          request.carrierProfileId(),
          code,
          name,
          text(request.status()),
          mode,
          currency,
          baseRate,
          divisor,
          minCharge,
          request.declarationMinValue(),
          request.declarationMaxValue(),
          blankToNull(request.declarationCurrency()),
          text(request.insuranceMode()),
          request.insuranceRatePct() == null ? BigDecimal.ZERO : request.insuranceRatePct(),
          request.insuranceMinFee() == null ? BigDecimal.ZERO : request.insuranceMinFee(),
          request.insuranceMaxValue(),
          readiness(baseRate, List.of(), text(request.status())),
          text(request.note()));
    } else {
      // 改配置：乐观锁 + 渠道代码与所属承运商不可改（照抄 10.1.1.1 的口径）
      var existing =
          sql.fetchOne(
              "select version, channel_code, carrier_profile_id from logistics_channel_config"
                  + " where tenant_id=? and id=?",
              tenant,
              id);
      if (existing == null) {
        throw notFound();
      }
      long currentVersion = existing.get("version", Long.class);
      if (request.version() != currentVersion) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "渠道配置已被他人修改（当前版本 " + currentVersion + "），请重新载入后再保存");
      }
      if (!code.equals(existing.get("channel_code", String.class))) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "渠道代码建后不可改（"
                + existing.get("channel_code", String.class)
                + " → "
                + code
                + "）：渠道代码是下游运单与交运批次对齐这条渠道配置的口径，"
                + "改代码会让已有单据找不到渠道配置");
      }
      if (request.carrierProfileId() != null
          && !request.carrierProfileId().equals(existing.get("carrier_profile_id", UUID.class))) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "所属承运商不可改：渠道归属另一个承运商会让费率对不上账");
      }
    }

    // 🔴 子表整体替换。**必须先换子表再写主表**：readiness 的触发器是在主表每一行
    //    写入时按**当时的**子表重算比对的，所以主表那次写入看到的必须是最终形态。
    //    （10.1.1.1 的 save() 有同样的顺序要求。）
    //    三张子表都先清空再逐张写入，所以「先加适用品类、后加禁限」这条路径上，
    //    反向触发器比对的是**本次提交的**适用品类集合，不会撞上上一版的残留。
    sql.execute("delete from logistics_channel_scope where tenant_id=? and channel_id=?", tenant, id);
    sql.execute(
        "delete from logistics_channel_surcharge where tenant_id=? and channel_id=?", tenant, id);
    sql.execute(
        "delete from logistics_channel_restriction where tenant_id=? and channel_id=?", tenant, id);

    writeChildren(tenant, id, scopes, surcharges, restrictions);

    // 主表最后一次写入：子表已是最终形态，触发器重算出来的就是这里提交的值。
    // 新建时 version 保持 0（INSERT 已写过一遍，这里不再自增，免得新建就 version=1）。
    sql.execute(
        "update logistics_channel_config set channel_name=?, status=?, billing_mode=?,"
            + " billing_currency=?, base_rate=?, charge_divisor=?, min_charge=?,"
            + " declaration_min_value=?, declaration_max_value=?, declaration_currency=?,"
            + " insurance_mode=?, insurance_rate_pct=?, insurance_min_fee=?, insurance_max_value=?,"
            + " readiness=?, note=?, updated_at=now(), version=version+?"
            + " where tenant_id=? and id=?",
        name,
        text(request.status()),
        mode,
        currency,
        baseRate,
        divisor,
        minCharge,
        request.declarationMinValue(),
        request.declarationMaxValue(),
        blankToNull(request.declarationCurrency()),
        text(request.insuranceMode()),
        request.insuranceRatePct() == null ? BigDecimal.ZERO : request.insuranceRatePct(),
        request.insuranceMinFee() == null ? BigDecimal.ZERO : request.insuranceMinFee(),
        request.insuranceMaxValue(),
        readiness(baseRate, scopes, text(request.status())),
        text(request.note()),
        creating ? 0 : 1,
        tenant,
        id);

    return detail(id);
  }

  /** 折算基数缺省值：按体积默认 6000（行业通用 cm³/kg 折算），其余默认 1。 */
  private static BigDecimal defaultDivisor(String mode) {
    return "BY_VOLUME".equals(mode) ? new BigDecimal("6000") : BigDecimal.ONE;
  }

  private static String blankToNull(String value) {
    String v = text(value);
    return v.isEmpty() ? null : v.toUpperCase(Locale.ROOT);
  }

  /**
   * readiness 的<b>单一实现</b>（与 V136 的 trigger a 规则逐条对应，注释互相指向）。
   *
   * <p>新建时子表还是空的，所以 {@code scopes} 传空列表 ⇒ 结果是 NO_SCOPE ——
   * 这是**正确的中间态**：渠道刚建出来还没登记适用范围，确实不可用。
   */
  private static String readiness(BigDecimal baseRate, List<ChannelScopeItem> scopes, String status) {
    if (baseRate == null || baseRate.signum() <= 0) {
      return "NO_BASE_RATE";
    }
    boolean hasScope = scopes != null && scopes.stream().anyMatch(s -> !text(s.scopeValue()).isEmpty());
    if (!hasScope) {
      return "NO_SCOPE";
    }
    return "SUSPENDED".equals(status) ? "SUSPENDED" : "READY";
  }

  /** 子表写入。调用方负责先清空（三张子表都按整份替换语义）。 */
  private void writeChildren(
      UUID tenant,
      UUID id,
      List<ChannelScopeItem> scopes,
      List<ChannelSurchargeItem> surcharges,
      List<ChannelRestrictionItem> restrictions) {
    for (ChannelScopeItem scope : scopes) {
      String value = text(scope.scopeValue());
      if (value.isEmpty()) {
        continue;
      }
      sql.execute(
          "insert into logistics_channel_scope (tenant_id,channel_id,scope_kind,scope_value)"
              + " values (?,?,?,?) on conflict do nothing",
          tenant,
          id,
          text(scope.scopeKind()),
          "COUNTRY".equals(scope.scopeKind()) ? value.toUpperCase(Locale.ROOT) : value);
    }

    for (ChannelSurchargeItem surcharge : surcharges) {
      String kind = text(surcharge.surchargeKind());
      String chargeMode = text(surcharge.chargeMode());
      // ⚠️ timestamptz 必须显式写 ?::timestamptz 并绑 OffsetDateTime。
      //    绑 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s —— 旺季费区间
      //    偏 8 小时会让「旺季当天」判错，而且不报任何错。
      boolean peak = "PEAK_SEASON".equals(kind);
      sql.execute(
          "insert into logistics_channel_surcharge (tenant_id,channel_id,surcharge_kind,"
              + " charge_mode,amount,effective_from,effective_to,note)"
              + " values (?,?,?,?,?,?::timestamptz,?::timestamptz,?)",
          tenant,
          id,
          kind,
          chargeMode.isEmpty() ? "FIXED" : chargeMode,
          surcharge.amount() == null ? BigDecimal.ZERO : surcharge.amount(),
          peak ? surcharge.effectiveFrom() : null,
          peak ? surcharge.effectiveTo() : null,
          text(surcharge.note()));
    }

    for (ChannelRestrictionItem restriction : restrictions) {
      sql.execute(
          "insert into logistics_channel_restriction (tenant_id,channel_id,restriction_kind,"
              + " subject,restriction_mode,carrier_reason) values (?,?,?,?,?,?)",
          tenant,
          id,
          text(restriction.restrictionKind()),
          text(restriction.subject()),
          text(restriction.restrictionMode()),
          text(restriction.carrierReason()));
    }
  }

  /** 撤下渠道配置。承运商档案**不受影响**（那是 10.1.1.1 的主权）。 */
  public void delete(UUID id) {
    UUID tenant = currentTenant();
    int rows =
        sql.execute("delete from logistics_channel_config where tenant_id=? and id=?", tenant, id);
    if (rows == 0) {
      throw notFound();
    }
  }

  // ==================================================================
  // 🔴 渠道限制判定：下游下单前问的那个问题
  // ==================================================================

  /**
   * 判定一批渠道能否承运这个包裹。
   *
   * <p>这是蓝图加粗句的服务端落点。规则<b>全部收在这一个方法里</b>，不散落到调用方：
   *
   * <ol>
   *   <li>渠道本身不可用（DRAFT / SUSPENDED / readiness 非 READY）⇒ REVIEW，
   *       不拦（人看一眼就能过）但<b>不允许自动放行</b>；
   *   <li>承运商不可用（10.1.1.1 判 PARTY_BLOCKED）⇒ **BLOCK** ——
   *       对一个已拉黑的对手方下单，代价与被拒收是同一种；
   *   <li>命中任一 {@code BLOCK} 级限制 ⇒ **BLOCK**，承运商侧理由逐条给出；
   *   <li>命中任一 {@code MANUAL_REVIEW} 级限制 ⇒ REVIEW；
   *   <li>保价金额超 {@code insurance_max_value} ⇒ **BLOCK** ——
   *       超过上限承运商不接保，寄丢了赔不到，这不是「可选项」是硬边界；
   *   <li>申报价值超 {@code declaration_max_value} 或低于 min ⇒ REVIEW。
   * </ol>
   *
   * <p>三档而不是「是/否」：把「需要人看一眼」混进「可以自动放行」，等于把拒收风险
   * 交给运气。
   */
  public ChannelEligibilityResult eligibility(ChannelEligibilityRequest request) {
    List<Object> filters = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where c.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid");

    if (request.channelIds() != null && !request.channelIds().isEmpty()) {
      String placeholders = String.join(",", Collections.nCopies(request.channelIds().size(), "?"));
      where.append(" and c.id in (" + placeholders + ")");
      filters.addAll(request.channelIds());
    } else {
      // 留空 = 判定本租户全部**启用中**的渠道（草稿与停用的不该被当成候选）
      where.append(" and c.status = 'ACTIVE'");
    }

    String country = text(request.countryCode()).toUpperCase(Locale.ROOT);
    if (!country.isEmpty()) {
      where.append(
          " and exists (select 1 from logistics_channel_scope s"
              + " where s.tenant_id = c.tenant_id and s.channel_id = c.id"
              + " and s.scope_kind = 'COUNTRY' and s.scope_value = ?)");
      filters.add(country);
    }
    String shop = text(request.shopCode());
    if (!shop.isEmpty()) {
      where.append(
          " and exists (select 1 from logistics_channel_scope s"
              + " where s.tenant_id = c.tenant_id and s.channel_id = c.id"
              + " and s.scope_kind = 'SHOP' and s.scope_value = ?)");
      filters.add(shop);
    }
    where.append(" order by c.channel_code asc");

    var rows = sql.fetch("select " + SELECT_COLUMNS + FROM_CLAUSE + where, filters.toArray());
    if (rows.isEmpty()) {
      return new ChannelEligibilityResult(
          List.of(), 0, 0, 0,
          "本租户没有匹配这些条件的渠道：渠道要么没启用，要么适用范围不含目的国 / 店铺。"
              + "空结果**不代表**可以随便选一条 —— 它代表「这些条件下没有候选渠道」。");
    }

    List<UUID> ids = rows.stream().map(r -> r.get("id", UUID.class)).toList();
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));

    // 一次性把限制全取回来，避免逐条查（N+1）。
    Map<UUID, List<ChannelRestrictionItem>> byChannel = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select channel_id, restriction_kind, subject, restriction_mode, carrier_reason"
                + " from logistics_channel_restriction"
                + " where channel_id in (" + placeholders + ") and restriction_mode <> 'ALLOWED'"
                + " order by restriction_kind, subject",
            ids.toArray(new UUID[0]))) {
      byChannel
          .computeIfAbsent(r.get(0, UUID.class), k -> new ArrayList<>())
          .add(
              new ChannelRestrictionItem(
                  r.get("restriction_kind", String.class),
                  r.get("subject", String.class),
                  r.get("restriction_mode", String.class),
                  r.get("carrier_reason", String.class)));
    }

    List<String> categories =
        request.categoryCodes() == null
            ? List.of()
            : request.categoryCodes().stream().map(LogisticsChannelService::text)
                .filter(v -> !v.isEmpty()).map(v -> v.toUpperCase(Locale.ROOT)).distinct().toList();

    List<ChannelEligibilityVerdict> verdicts = new ArrayList<>();
    long pass = 0;
    long review = 0;
    long block = 0;

    for (Record r : rows) {
      UUID id = r.get("id", UUID.class);
      List<String> blocking = new ArrayList<>();
      List<String> reviewing = new ArrayList<>();

      // (1) 渠道本身可用性 → REVIEW（不拦，但不允许自动放行）
      String readiness = r.get("readiness", String.class);
      if (!"READY".equals(readiness)) {
        reviewing.add(readinessMessage(readiness));
      }
      if (!"ACTIVE".equals(r.get("status", String.class))) {
        reviewing.add("渠道状态为 " + r.get("status", String.class) + "：只有 ACTIVE 才参与承运");
      }

      // (2) 承运商不可用 → BLOCK（对已拉黑的对手方下单，代价与被拒收同一种）
      String carrierAvailability = carrierAvailability(r);
      if ("PARTY_BLOCKED".equals(carrierAvailability)) {
        blocking.add("所属承运商已停用 / 拉黑 / 删除（上游 10.1.1.1 判定）：对它下单代价与被拒收相同");
      }

      // (3) 逐条命中渠道限制
      for (ChannelRestrictionItem restriction : byChannel.getOrDefault(id, List.of())) {
        String hit = restrictionHit(restriction, categories, request);
        if (hit == null) {
          continue;
        }
        String reason =
            hit
                + (text(restriction.carrierReason()).isEmpty()
                    ? ""
                    : "（承运商侧：" + text(restriction.carrierReason()) + "）");
        if ("BLOCK".equals(restriction.restrictionMode())) {
          blocking.add(reason);
        } else {
          reviewing.add(reason);
        }
      }

      // (4) 保价上限 → BLOCK（超过上限承运商不接保，寄丢了赔不到）
      BigDecimal insured = request.insuredValue();
      BigDecimal maxInsured = r.get("insurance_max_value", BigDecimal.class);
      if (insured != null && maxInsured != null && insured.compareTo(maxInsured) > 0) {
        blocking.add(
            "保价金额 "
                + insured
                + " 超过保价上限 "
                + maxInsured
                + "：超过上限承运商不接保，寄丢了赔不到");
      }
      if (insured != null && "NONE".equals(r.get("insurance_mode", String.class))) {
        reviewing.add("这条渠道不承保（insurance_mode=NONE），保价值需走别的渠道");
      }

      // (5) 申报价值上下限 → REVIEW
      BigDecimal declared = request.declaredValue();
      BigDecimal declaredMax = r.get("declaration_max_value", BigDecimal.class);
      BigDecimal declaredMin = r.get("declaration_min_value", BigDecimal.class);
      if (declared != null && declaredMax != null && declared.compareTo(declaredMax) > 0) {
        reviewing.add("申报价值 " + declared + " 超过渠道申报上限 " + declaredMax);
      }
      if (declared != null && declaredMin != null && declared.compareTo(declaredMin) < 0) {
        reviewing.add("申报价值 " + declared + " 低于渠道申报下限 " + declaredMin + "，需人工确认");
      }

      String verdict =
          blocking.isEmpty()
              ? (reviewing.isEmpty() ? RestrictionVerdict.PASS : RestrictionVerdict.REVIEW)
              : RestrictionVerdict.BLOCK;
      switch (verdict) {
        case RestrictionVerdict.BLOCK -> block++;
        case RestrictionVerdict.REVIEW -> review++;
        default -> pass++;
      }
      verdicts.add(
          new ChannelEligibilityVerdict(
              id,
              r.get("channel_code", String.class),
              r.get("channel_name", String.class),
              r.get("carrier_name", String.class),
              verdict,
              blocking,
              reviewing));
    }

    return new ChannelEligibilityResult(
        verdicts,
        pass,
        review,
        block,
        "判定在本服务端完成，规则只有一份：下游（10.1.2.1 运费试算 / 10.1.2.2 渠道比价 / "
            + "9.3.2.1 分仓 / 10.2.1.2 运单申请）拿到的结论与理由即可，不需要也不应该"
            + "自己拿限制字段再判断一遍。");
  }

  /**
   * 这条限制是否被这个包裹命中；没命中返回 {@code null}。
   *
   * <p>三类限制的命中口径各不相同，混成一条 if 正是「漏掉磁力」那类 bug 的来源：
   * <ul>
   *   <li>{@code PROHIBITED_CATEGORY} —— 包裹任一品类落在禁限清单里
   *   <li>{@code BATTERY} / {@code LIQUID} / {@code MAGNET} —— 货件带电 / 带液 / 带磁
   *       （不看品类：带电是**包裹属性**，一个带电件在「电器」品类下和在「配件」品类下
   *       一样会被拒收）
   * </ul>
   */
  private static String restrictionHit(
      ChannelRestrictionItem restriction, List<String> categories, ChannelEligibilityRequest request) {
    return switch (restriction.restrictionKind()) {
      case "PROHIBITED_CATEGORY" ->
          categories.contains(text(restriction.subject()).toUpperCase(Locale.ROOT))
              ? "品类「" + text(restriction.subject()) + "」被渠道列为禁限品类"
              : null;
      case "BATTERY" -> request.containsBattery() ? "货件带电池：渠道限制带电" : null;
      case "LIQUID" -> request.containsLiquid() ? "货件带液体：渠道限制液体" : null;
      case "MAGNET" -> request.containsMagnet() ? "货件带磁力件：渠道限制磁力" : null;
      default -> null;
    };
  }
}
