package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.2.1.2 分时与分周模式的**拉取适配器**（上游授权只读 API → 店铺时区档案 + 分时投影事实）。
 *
 * <p>⚠️ <b>本类只写投影</b>（{@code reporting_time_pattern_shop_profile} /
 * {@code reporting_time_pattern_fact} / {@code reporting_time_pattern_slot} /
 * {@code reporting_time_pattern_metric_definition}），
 * 不产生任何业务事实 —— 成交金额与销量仍然在 trade，店铺归属在 trade 的平台单，
 * 店铺时区在 channel 的站点参数。
 *
 * <p><b>🔴 三条纪律</b>：
 *
 * <ol>
 *   <li><b>幂等</b>：先删窗口内的事实再写 ⇒ 重复刷新是<b>覆盖</b>而不是翻倍；
 *       而且店铺时区改了，同一条订单会<b>移动</b>到新的本地桶（不会留下旧桶的残影）。
 *   <li><b>口径唯一</b>：事实的指标码必须已登记（13.1.1.1 的公司口径表 **或** 本模块的口径表），
 *       DB 触发器钉死 ⇒ 事实不能自己发明指标码。
 *   <li><b>读不到 ≠ 没有数据</b>：上游故障一律翻成失败（{@code FAILED} / 503），
 *       <b>不用空集合冒充</b>。
 * </ol>
 *
 * <p><b>🔴 时区是本模块的命门</b>：本地小时 / 本地星期几都用
 * {@link ZoneId#of(String)}（<b>IANA 标识</b>，自带夏令时规则）换算，
 * <b>不用固定 UTC 偏移</b> —— 固定偏移会整整差一小时（夏令时切换那两周）。
 *
 * <p><b>🔴 时钟域</b>：落库时刻一律用 PG 的 {@code now()}（DB 钟域），
 * 订单时刻用 {@code ?::timestamptz} 绑定 {@link java.time.OffsetDateTime}
 * —— 用 {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s。
 */
@Service
public class ReportingTimePatternRefreshService {

  /** 取数窗口两侧多留几天（**时区余量**）。 */
  static final int MARGIN_DAYS = 2;

  /**
   * 一个窗口最多取多少张订单的<b>明细行</b>（销量只存在于明细里 ⇒ 必须逐单取）。
   *
   * <p>⚠️ 触到上限时<b>如实报 truncated</b>（不悄悄少算），并在报表上给出明细覆盖率。
   */
  static final int MAX_DETAIL_FETCHES = 200;

  private final ReportingTimePatternPort port;
  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingTimePatternRefreshService(
      ReportingTimePatternPort port, DSLContext sql, TenantTransactions tenants) {
    this.port = port;
    this.sql = sql;
    this.tenants = tenants;
  }

  // ==========================================================================
  // 刷新
  // ==========================================================================

  /**
   * 刷新店铺时区档案与分时投影事实。
   *
   * @param from 当前窗口起始日
   * @param to 当前窗口结束日
   */
  public List<ReportingTimePatternModels.TimePatternRefreshResult> refresh(
      UUID tenantId, String bearer, LocalDate from, LocalDate to) {
    var results = new ArrayList<ReportingTimePatternModels.TimePatternRefreshResult>();
    results.add(refreshDefinitions(tenantId));
    results.add(refreshSlots(tenantId));
    results.add(refreshShopProfiles(tenantId, bearer));
    results.add(refreshFacts(tenantId, bearer, from, to));
    // 🔴 **上游读不到 ⇒ 整个刷新失败（503）**：不能返回一份「看起来成功、其实少了一半」的投影。
    var failed = new ArrayList<String>();
    for (var result : results) {
      if ("FAILED".equals(result.status())) {
        failed.add(result.source() + "：" + result.note());
      }
    }
    if (!failed.isEmpty()) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "分时与分周模式投影失败（上游读不到，不是「没有数据」）：" + String.join("；", failed));
    }
    return results;
  }

  // ==========================================================================
  // 口径与时段配置的播种
  // ==========================================================================

  /**
   * 播种口径（<b>幂等</b>，查询侧也会调它 —— 口径必须先于事实存在）。
   *
   * <p>⚠️ <b>公司维度的三个基础口径在这里只做「确保存在」</b>
   * （{@code on conflict do nothing}）—— 它们<b>唯一</b>的定义处是 13.1.1.1 的
   * {@code reporting_metric_definition}，本模块<b>引用</b>，不重定义。
   */
  void seedDefinitions(UUID tenantId) {
    for (var definition : ReportingMetricCatalog.STANDARD) {
      sql.execute(
          """
          insert into reporting_metric_definition
            (id,tenant_id,metric_code,display_name,unit,aggregation,numerator_metric_code,
             denominator_metric_code,currency_aware,description,owner_module,enabled,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,?,true,now(),now())
          on conflict (tenant_id, metric_code) do nothing
          """,
          Ids.next(),
          tenantId,
          definition.metricCode(),
          definition.displayName(),
          definition.unit().name(),
          definition.aggregation().name(),
          definition.numeratorMetricCode(),
          definition.denominatorMetricCode(),
          definition.currencyAware(),
          definition.description(),
          definition.ownerModule());
    }
    for (var definition : ReportingTimePatternCatalog.STANDARD) {
      sql.execute(
          """
          insert into reporting_time_pattern_metric_definition
            (id,tenant_id,metric_code,display_name,unit,aggregation,base_metric_code,
             currency_aware,description,owner_module,enabled,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,true,now(),now())
          on conflict (tenant_id, metric_code) do nothing
          """,
          Ids.next(),
          tenantId,
          definition.metricCode(),
          definition.displayName(),
          definition.unit().name(),
          definition.aggregation().name(),
          definition.baseMetricCode(),
          definition.currencyAware(),
          definition.description(),
          definition.ownerModule());
    }
  }

  /**
   * 播种**时段配置**（幂等；租户改过的配置<b>不会被覆盖</b>）。
   *
   * <p>⚠️ <b>一次多行 INSERT</b>：AFTER 行触发器在语句结束才逐行触发 ⇒
   * 整套时段一次成型，触发器看到的是<b>完整覆盖 0..24</b>的集合 ⇒ 不会误判成「留了空洞」。
   *
   * <p>🔴 <b>只在「这个租户一条时段配置都没有」时才播种</b> ——
   * 「有配置就当它是自己定的」是本模块的纪律：
   * 否则租户用「整体替换」换成了自己的时段划分之后，<b>每一次读报表</b>
   * 都会把初始四段再插回去 ⇒ 它们和租户自己的时段<b>重叠</b> ⇒
   * 数据层的「不可重叠」约束当场把那次读拒掉（报表直接报错）。
   */
  void seedSlots(UUID tenantId) {
    // 🔴 **先看有没有**（FORCE RLS：必须在**这个租户的事务里**读，
    //    事务外读出来是空集 ⇒ 会误判成「一条都没有」⇒ 又把初始四段插一遍）。
    var existing =
        sql.fetchOne(
            "select count(*) from reporting_time_pattern_slot where tenant_id = ?", tenantId);
    if (existing != null && existing.get(0, Integer.class) > 0) return;

    for (var seed : ReportingTimePatternCatalog.DEFAULT_SLOTS) {
      sql.execute(
          """
          insert into reporting_time_pattern_slot
            (id,tenant_id,slot_code,display_name,start_hour,end_hour,level,qualify_basis,
             qualify_comparison,qualify_threshold,sort_order,enabled,description,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,?,true,?,now(),now())
          on conflict (tenant_id, slot_code) do nothing
          """,
          Ids.next(),
          tenantId,
          seed.slotCode(),
          seed.displayName(),
          seed.startHour(),
          seed.endHour(),
          seed.level().name(),
          seed.qualifyBasis().name(),
          seed.qualifyComparison().name(),
          new BigDecimal(seed.qualifyThreshold()),
          seed.sortOrder(),
          seed.description());
    }
  }

  private ReportingTimePatternModels.TimePatternRefreshResult refreshDefinitions(UUID tenantId) {
    var count =
        tenants.run(
            tenantId,
            () -> {
              seedDefinitions(tenantId);
              return ReportingMetricCatalog.STANDARD.size()
                  + ReportingTimePatternCatalog.STANDARD.size();
            });
    return new ReportingTimePatternModels.TimePatternRefreshResult(
        "metric-definitions",
        ReportingMetricCatalog.STANDARD.size() + ReportingTimePatternCatalog.STANDARD.size(),
        count,
        "SUCCEEDED",
        "已确保口径登记：公司维度的三个基础口径（SALES_AMOUNT / UNIT_SOLD / ORDER_COUNT）"
            + "**引用** 13.1.1.1 的口径表，不重定义；本模块另登记 "
            + ReportingTimePatternCatalog.STANDARD.size()
            + " 条新口径（时段占比 / P90 阈值）。");
  }

  /**
   * 播种**时段配置**（租户可配置的数据）。
   *
   * <p>⚠️ <b>一次多行 INSERT</b>：AFTER 行触发器在语句结束才逐行触发 ⇒
   * 整套时段一次成型，触发器看到的是**完整覆盖 0..24** 的集合 ⇒ 不会误判成「留了空洞」。
   * 逐条插入会在中间态被拒（那正是我们要的：配置必须一次成型）。
   *
   * <p>⚠️ <b>已配置过的租户不会被覆盖</b>（{@code on conflict do nothing}）——
   * 租户改过的配置是<b>租户的数据</b>，刷新投影不许把它冲掉。
   */
  private ReportingTimePatternModels.TimePatternRefreshResult refreshSlots(UUID tenantId) {
    var count =
        tenants.run(
            tenantId,
            () -> {
              seedSlots(tenantId);
              return ReportingTimePatternCatalog.DEFAULT_SLOTS.size();
            });
    return new ReportingTimePatternModels.TimePatternRefreshResult(
        "time-pattern-slots",
        ReportingTimePatternCatalog.DEFAULT_SLOTS.size(),
        count,
        "SUCCEEDED",
        "已确保时段配置存在：初始四段（凌晨 / 上午 / 下午 / 晚间，6 小时一段）**完整覆盖 0..24**。"
            + "⚠️ 这是**初始配置**，租户随时可改（增删改查端点都在）；"
            + "「某个小时属于哪个时段」的判定**一律读表**，不读常量。");
  }

  // ==========================================================================
  // 店铺时区档案
  // ==========================================================================

  /**
   * 刷新店铺时区档案（全量替换）。
   *
   * <p>⚠️ <b>定不了时区的店铺也落一行</b>（UNRESOLVED + 原因）——
   * 少一个店铺就会让人以为数据缺失。
   */
  private ReportingTimePatternModels.TimePatternRefreshResult refreshShopProfiles(
      UUID tenantId, String bearer) {
    List<ReportingTimePatternPort.ShopTimezone> rows;
    try {
      rows = port.shopTimezones(bearer);
    } catch (org.springframework.web.server.ResponseStatusException e) {
      return new ReportingTimePatternModels.TimePatternRefreshResult(
          "channel-shops",
          0,
          0,
          "FAILED",
          e.getReason() == null ? "上游读不到" : e.getReason());
    }
    if (rows == null) rows = List.of();

    // 🔴 **时区必须是 `pg_timezone_names` 里真实存在的 IANA 标识**：
    //   固定 UTC 偏移会错过夏令时 ⇒ 本地小时会整整差一小时。
    //   上游给了一个不认得的标识 ⇒ 该店铺**定不了时区**（不硬塞一个错的值）。
    var knownZones = knownTimezones();

    var prepared = new ArrayList<ReportingTimePatternPort.ShopTimezone>();
    for (var row : rows) {
      if ("RESOLVED".equals(row.status()) && !knownZones.contains(row.timezone())) {
        prepared.add(
            new ReportingTimePatternPort.ShopTimezone(
                row.shopCode(),
                row.shopName(),
                "",
                "UNRESOLVED",
                "上游给的时区标识「"
                    + row.timezone()
                    + "」不是有效的 IANA 时区（pg_timezone_names 里没有）⇒ 该店铺的订单"
                    + "**进不了任何本地小时 / 星期桶**，单列成「未能按店铺本地时分桶」。"));
        continue;
      }
      prepared.add(row);
    }

    var written =
        tenants.run(
            tenantId,
            () -> {
              sql.execute("delete from reporting_time_pattern_shop_profile where tenant_id = ?", tenantId);
              var count = 0;
              for (var row : prepared) {
                sql.execute(
                    """
                    insert into reporting_time_pattern_shop_profile
                      (id,tenant_id,shop_code,shop_name,shop_timezone,timezone_status,timezone_reason,
                       source_service,source_reference,created_at,updated_at)
                    values (?,?,?,?,?,?,?,'channel',?,now(),now())
                    """,
                    Ids.next(),
                    tenantId,
                    row.shopCode(),
                    row.shopName(),
                    row.timezone(),
                    row.status(),
                    row.reason(),
                    "shop:" + row.shopCode());
                count++;
              }
              return count;
            });
    var unresolved = prepared.stream().filter(r -> "UNRESOLVED".equals(r.status())).count();
    return new ReportingTimePatternModels.TimePatternRefreshResult(
        "channel-shops",
        prepared.size(),
        written,
        "SUCCEEDED",
        "已投影 " + prepared.size() + " 个店铺的时区档案，其中 " + unresolved
            + " 个**定不了时区**（它们的订单不进任何本地小时 / 星期桶，报表里单列）。");
  }

  /** PG 里真实存在的 IANA 时区标识集合。 */
  private java.util.Set<String> knownTimezones() {
    var out = new java.util.LinkedHashSet<String>();
    for (var row : sql.fetch("select name from pg_timezone_names")) {
      out.add(row.get(0, String.class));
    }
    return out;
  }

  // ==========================================================================
  // 分时事实
  // ==========================================================================

  /**
   * 一个窗口的销售订单 → 分时投影事实。
   *
   * <p>⚠️ **金额与订单量全量，销量按明细上限截断** —— 销量只存在于订单明细里，
   * 而明细要逐单取（有上限）。截断<b>如实报出</b>，不悄悄少算。
   */
  private ReportingTimePatternModels.TimePatternRefreshResult refreshFacts(
      UUID tenantId, String bearer, LocalDate from, LocalDate to) {
    Map<UUID, String> attribution;
    try {
      attribution = port.orderShopAttribution(bearer);
    } catch (org.springframework.web.server.ResponseStatusException e) {
      return new ReportingTimePatternModels.TimePatternRefreshResult(
          "trade-platform-orders",
          0,
          0,
          "FAILED",
          e.getReason() == null ? "上游读不到" : e.getReason());
    }

    // 店铺 → 时区（解析不出来的店铺**没有时区**，它的订单进不了本地桶）。
    //
    // 🔴 **必须在租户事务里读**：档案表带 FORCE RLS，事务外没有 `app.current_tenant`
    //    ⇒ 读出来是**空集** ⇒ 看起来「所有店铺都定不了时区」，
    //    而真相是「根本没读到」。这是本仓 RLS 最容易咬人的一种形态。
    var profile = tenants.run(tenantId, () -> loadShopProfile(tenantId));
    var zones = profile.zones();
    var zoneStatus = profile.statuses();

    var fetchFrom = from.minusDays(MARGIN_DAYS);
    var fetchTo = to.plusDays(MARGIN_DAYS);
    var facts = new ArrayList<Fact>();
    int fetched = 0;
    int detailFetches = 0;
    int withDetail = 0;
    boolean truncated = false;
    int unattributed = 0;
    int missingCurrency = 0;
    int noZone = 0;
    try {
      for (int page = 0; page < ReportingTimePatternPort.MAX_PAGES; page++) {
        var pageData = port.salesOrders(fetchFrom, fetchTo, page, bearer);
        if (pageData == null || pageData.rows() == null || pageData.rows().isEmpty()) break;
        for (var row : pageData.rows()) {
          fetched++;
          if (row.submittedAt() == null) continue;
          var currency = row.currency() == null ? "" : row.currency().trim().toUpperCase();
          // 🔴 **币种缺失的订单不计入任何币种**：算进某个币种是编造。
          if (!currency.matches("^[A-Z]{3}$")) {
            missingCurrency++;
            continue;
          }
          var shop = row.id() == null ? null : attribution.get(row.id());
          if (shop == null || shop.isBlank()) {
            // ⚠️ **挂不上店铺的订单不计入任何店铺**（平台单缺 sales_order_id 关联时）。
            unattributed++;
            continue;
          }
          var shopCode = shop.trim();
          var reference = row.orderNo();
          if (reference == null || reference.isBlank()) continue;

          var timezone = zones.getOrDefault(shopCode, "");
          var resolved = "RESOLVED".equals(zoneStatus.getOrDefault(shopCode, "UNRESOLVED"))
              && !timezone.isBlank();
          LocalDate localDate = null;
          Integer localHour = null;
          Integer localWeekday = null;
          if (resolved) {
            // 🔴 **IANA 时区换算**（自带夏令时规则），不是固定 UTC 偏移。
            var local = row.submittedAt().atZoneSameInstant(ZoneId.of(timezone));
            localDate = local.toLocalDate();
            localHour = local.getHour();
            // 🔴 **ISO 星期几**：1 = 周一 … 7 = 周日（店铺**本地**的星期几，不是周数）。
            localWeekday = local.getDayOfWeek().getValue();
          } else {
            noZone++;
          }
          var amount = row.grandTotalAmount() == null ? BigDecimal.ZERO : row.grandTotalAmount();
          var status = resolved ? "RESOLVED" : "UNRESOLVED";

          facts.add(
              new Fact(
                  shopCode,
                  timezone,
                  status,
                  localDate,
                  localHour,
                  localWeekday,
                  row.submittedAt(),
                  currency,
                  ReportingMetricCatalog.SALES_AMOUNT,
                  amount,
                  BigDecimal.ZERO,
                  reference));
          facts.add(
              new Fact(
                  shopCode,
                  timezone,
                  status,
                  localDate,
                  localHour,
                  localWeekday,
                  row.submittedAt(),
                  currency,
                  ReportingMetricCatalog.ORDER_COUNT,
                  BigDecimal.ONE,
                  BigDecimal.ZERO,
                  reference));

          // 销量只在**订单明细**里 ⇒ 逐单取（有上限）。
          if (detailFetches < MAX_DETAIL_FETCHES && row.id() != null) {
            detailFetches++;
            var detail = port.salesOrderDetail(row.id(), bearer);
            if (detail != null && detail.lines() != null && !detail.lines().isEmpty()) {
              withDetail++;
              var total = BigDecimal.ZERO;
              for (var line : detail.lines()) {
                if (line.quantity() == null) continue;
                total = total.add(line.quantity());
              }
              facts.add(
                  new Fact(
                      shopCode,
                      timezone,
                      status,
                      localDate,
                      localHour,
                      localWeekday,
                      row.submittedAt(),
                      currency,
                      ReportingMetricCatalog.UNIT_SOLD,
                      BigDecimal.ZERO,
                      total,
                      reference));
            }
          } else if (row.id() != null) {
            truncated = true;
          }
        }
        if (pageData.rows().size() < ReportingTimePatternPort.PAGE_SIZE) break;
      }
    } catch (org.springframework.web.server.ResponseStatusException e) {
      return new ReportingTimePatternModels.TimePatternRefreshResult(
          "trade-orders",
          fetched,
          0,
          "FAILED",
          e.getReason() == null ? "上游读不到" : e.getReason());
    }
    var orderTruncated = fetched >= ReportingTimePatternPort.PAGE_SIZE * ReportingTimePatternPort.MAX_PAGES;

    // 🔴 **先删窗口内的事实再写** ⇒ 幂等（重复刷新是覆盖不是翻倍），
    //    而且店铺时区改了以后，同一条订单**移动**到新的本地桶（不留旧桶残影）。
    var written =
        tenants.run(
            tenantId,
            () -> {
              sql.execute(
                  """
                  delete from reporting_time_pattern_fact
                   where tenant_id = ? and occurred_at >= ?::timestamptz and occurred_at < ?::timestamptz
                  """,
                  tenantId,
                  from.minusDays(MARGIN_DAYS).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime(),
                  to.plusDays(MARGIN_DAYS).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime());
              var count = 0;
              for (var fact : facts) {
                sql.execute(
                    """
                    insert into reporting_time_pattern_fact
                      (id,tenant_id,shop_code,shop_timezone,timezone_status,local_date,local_hour,
                       local_weekday,occurred_at,currency,metric_code,value,quantity,source_service,
                       source_reference,updated_at)
                    values (?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,'trade',?,now())
                    on conflict (tenant_id, shop_code, metric_code, currency, source_service, source_reference)
                    do update set shop_timezone = excluded.shop_timezone,
                                  timezone_status = excluded.timezone_status,
                                  local_date = excluded.local_date,
                                  local_hour = excluded.local_hour,
                                  local_weekday = excluded.local_weekday,
                                  occurred_at = excluded.occurred_at,
                                  value = excluded.value,
                                  quantity = excluded.quantity,
                                  updated_at = now()
                    """,
                    Ids.next(),
                    tenantId,
                    fact.shopCode(),
                    fact.shopTimezone(),
                    fact.timezoneStatus(),
                    fact.localDate(),
                    fact.localHour(),
                    fact.localWeekday(),
                    fact.occurredAt(),
                    fact.currency(),
                    fact.metricCode(),
                    fact.value(),
                    fact.quantity(),
                    fact.reference());
                count++;
              }
              return count;
            });

    var note = new StringBuilder();
    note.append("窗口 ")
        .append(from)
        .append(" ~ ")
        .append(to)
        .append("：订单 ")
        .append(fetched)
        .append(" 张，其中 ")
        .append(unattributed)
        .append(" 张挂不上店铺（不计入任何店铺）");
    if (missingCurrency > 0) {
      note.append("，").append(missingCurrency).append(" 张币种缺失（不计入任何币种）");
    }
    if (noZone > 0) {
      note.append("，").append(noZone).append(" 张订单的店铺**定不了时区**（不进任何本地桶，单列）");
    }
    note.append("；销量明细覆盖 ").append(withDetail).append(" 张订单");
    if (truncated) {
      note.append(
          " ⚠️ 订单明细取到 ")
          .append(MAX_DETAIL_FETCHES)
          .append(" 张为止（超过部分**未计入销量**）");
    }
    if (orderTruncated) {
      note.append(" ⚠️ 订单超过单次上限，超出部分**未计入**");
    }
    return new ReportingTimePatternModels.TimePatternRefreshResult(
        "trade-orders",
        fetched,
        written,
        (truncated || orderTruncated) ? "TRUNCATED" : "SUCCEEDED",
        note.toString());
  }

  /** 一个店铺的时区档案（读出来的形态）。 */
  private record ShopProfile(Map<String, String> zones, Map<String, String> statuses) {}

  /**
   * 读该租户的店铺时区档案。
   *
   * <p>⚠️ **必须在租户事务里调用**（FORCE RLS：事务外读出来是空集）。
   */
  private ShopProfile loadShopProfile(UUID tenantId) {
    var zones = new LinkedHashMap<String, String>();
    var statuses = new LinkedHashMap<String, String>();
    for (var row :
        sql.fetch(
            """
            select shop_code, shop_timezone, timezone_status
              from reporting_time_pattern_shop_profile
             where tenant_id = ?
            """,
            tenantId)) {
      zones.put(row.get(0, String.class), row.get(1, String.class));
      statuses.put(row.get(0, String.class), row.get(2, String.class));
    }
    return new ShopProfile(zones, statuses);
  }

  /** 一行事实（写库前的内存形态）。 */
  private record Fact(
      String shopCode,
      String shopTimezone,
      String timezoneStatus,
      LocalDate localDate,
      Integer localHour,
      Integer localWeekday,
      java.time.OffsetDateTime occurredAt,
      String currency,
      String metricCode,
      BigDecimal value,
      BigDecimal quantity,
      String reference) {}

  /** 占比定标（服务端算，前端只展示）。 */
  static BigDecimal scale(BigDecimal value) {
    return value == null ? null : value.setScale(6, RoundingMode.HALF_UP);
  }
}
