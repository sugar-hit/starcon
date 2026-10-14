package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryAgingModels.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * 7.6.1.1 库龄与呆滞。
 *
 * <p>蓝图：「按入库时间计算库龄分布，识别滞销、临期、残次、呆滞库存，给出处置建议 （降价促销、清仓、退仓、报废）」，输出「库龄分桶报表（0-30/31-60/61-90/90+ 天）+
 * 呆滞清单 + 占用资金估算」。
 *
 * <p>五条实现纪律：
 *
 * <ol>
 *   <li><b>库龄按真实入库日算，绝不用创建时间糊弄</b>。口径是 {@code coalesce(inventory_lot.received_on,
 *       stock_bucket.created_at::date)}： 挂了批次的取真实收货业务日（V2 定义），非批次管理品才退回库位建档日。 每一行都带 {@code
 *       agingBasis} 说明它取自哪一列，报表汇总也报出 「其中多少行是代理值」—— 一个全部由代理值算出来的库龄必须能被看见。 <b>刻意不用 {@code
 *       last_movement_at}</b>：那是最近一次数量变动的业务时间， 每天出 10 件的滞销品会被算成库龄 0 天，永远不进呆滞清单。
 *   <li><b>四个分桶互不重叠且合起来等于总量</b>：0-30 / 31-60 / 61-90 / 91+。 「90+」的下界是 91 而不是 90 —— 否则 90
 *       天这批货会同时落进两个桶， 报表各桶相加就会大于真实库存，而一个对不上账的库龄报表没人敢用来定清仓价。
 *   <li><b>「临期」与 7.3.1.2 同一口径</b>：直接复用 {@code inventory_expiry_policy.lead_days} 与它的档位判定（{@code
 *       expires_on<current_date} 为过期， {@code expires_on-current_date<=lead_days} 为临期），不另起一套阈值。
 *   <li><b>销售速度尚未交付</b>（13 销量卷，且跨服务禁止读对方表）， 所以滞销判定**只用库龄 + 可用量 + 质量状态 + 效期**四个已落地维度， 并在响应 {@code
 *       salesVelocityNote} 与每行 {@code basis} 里如实说明缺了速度因子。 不编一个「周转天数」—— 那需要一个本服务查不到的表。
 *   <li><b>占用资金如实报不可用</b>。本服务库存事实层没有任何成本列 （已 grep 全仓确认），成本属 finance 卷。所以 {@link AgingCapitalTie}
 *       恒返回 {@code available=false} + 原因，<b>而不是 0 元</b> —— 0 元会被读成 「这些呆滞货不占资金」，那比报错危险得多。
 * </ol>
 *
 * <p>本模块<b>只读不写</b>：处置建议是给人看的依据，不是动作。 调价属 5.4.1、退仓报废属 6.4.2.2，二者未交付，故每行 {@code automatable=false}。
 *
 * <p>租户来自已认证成员关系（控制器传入），不接受任何客户端租户参数；RLS 在数据库层强制隔离。
 */
@Service
public class InventoryAgingService {

  /** 单页最大行数。呆滞清单是 bucket 粒度，量级中等。 */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * 没配策略时的兜底滞销线 / 呆滞线。
   *
   * <p>是**有名字的默认值而不是魔法数字**：响应里 {@code defaultSlowMovingDays} 与 {@code slowMovingDaysSource=DEFAULT}
   * 会如实标出「这个 60 不是谁调出来的」， 界面上「配成 60 天」与「没人配」必须分得开。
   */
  public static final int DEFAULT_SLOW_MOVING_DAYS = 60;

  public static final int DEFAULT_OBSOLETE_DAYS = 90;

  /** 阈值合法区间。与 DB 的 CHECK 一致，避免只在应用层拒绝而直接落库时被打回。 */
  static final int MAX_THRESHOLD_DAYS = 3650;

  /**
   * 临期提前天数的兜底值 —— **必须与 7.3.1.2 的 {@code DEFAULT_LEAD_DAYS} 同值**。
   *
   * <p>两个模块各自引用对方会是循环依赖，所以在这里显式写死同一个常量并在注释里绑定： 「临期」的口径必须全局唯一，两个模块各配各的会让同一批货在一处显示临期、另一处显示正常。
   */
  private static final int DEFAULT_LEAD_DAYS = 30;

  private static final List<String> SLOW_MOVING_STATUSES =
      List.of("OBSOLETE", "SLOW_MOVING", "NORMAL");

  private static final List<String> DISPOSITIONS =
      List.of("SCRAP", "RETURN_TO_VENDOR", "MARKDOWN", "CLEARANCE", "HOLD");

  private static final List<String> AGING_BASES = List.of("LOT_RECEIVED_ON", "BUCKET_CREATED_AT");

  private static final List<String> SORTS =
      List.of("AGING_DAYS", "AVAILABLE", "ON_HAND", "SKU", "WAREHOUSE", "RECEIVED_ON");

  private static final String AGING_BASIS_NOTE =
      "库龄 = 今天 - 入库日。挂了批次的取批次收货业务日（真实入库日期）；"
          + "非批次管理品退回库位建档日，属代理值。刻意不用「最近变动时间」——"
          + "它每出一票就前移，会把天天出货的滞销品算成库龄 0 天。";

  private static final String SALES_VELOCITY_NOTE =
      "滞销判定当前只用库龄 + 可用量 + 质量状态 + 效期四个维度："
          + "销售速度（13 销量卷）尚未交付，且跨服务禁止读对方表。"
          + "因此本清单是「库龄维度」的呆滞识别，未做周转率交叉验证。";

  private final DSLContext sql;

  public InventoryAgingService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 库龄 CTE

  /**
   * 库龄事实 CTE —— 全模块唯一的库龄口径来源。
   *
   * <p>报表与呆滞清单**都从这里取数**，所以两个页面的「库龄」永远一致； 若各写各的 SQL，同一行货会在报表里是 45 天、在清单里是 10 天。
   *
   * <p>已交付但**未启用**的能力一律在注释里标明退化，而不是悄悄少算： {@code supplier_ref_id} 只用于「残次是否有供应商可退」的判断。
   */
  private static String agingCte(String where) {
    StringBuilder filter = new StringBuilder(where);
    // 参数顺序必须与下面 SQL 里 ? 的出现顺序**逐一对应**，这是纯文本 jOOQ 语句最容易错的地方。
    // 1) 默认滞销线 2) 默认呆滞线 3) 默认临期提前天数（前三者给 coalesce）
    // 4) 起 条件里的绑定
    String sql =
        """
        with aged as (
          select b.id as bucket_id,b.sku_id,s.code as sku_code,s.name as sku_name,\
         b.legal_entity_id,b.warehouse_id,w.code as warehouse_code,\
         b.location_id,loc.code as location_code,b.lot_id,l.lot_code,\
         b.quality_status_id,q.code as quality_status_code,q.role as quality_role,\
         b.on_hand,b.reserved,b.available,l.expires_on,\
         l.received_on,l.supplier_ref_id,\
         b.created_at::date as bucket_created_on,\
         coalesce(l.received_on,b.created_at::date) as inbound_date,\
         (l.received_on is not null) as lot_based,\
         (current_date-coalesce(l.received_on,b.created_at::date)) as aging_days,\
         coalesce(ap.slow_moving_days,?) as slow_moving_days,\
         coalesce(ap.obsolete_days,?) as obsolete_days,\
         coalesce(ep.lead_days,?) as lead_days,\
         (ap.id is not null) as has_policy\
         from stock_bucket b\
         join sku_ref s on s.tenant_id=b.tenant_id and s.id=b.sku_id\
         join warehouse w on w.tenant_id=b.tenant_id and w.id=b.warehouse_id\
         join warehouse_location loc on loc.tenant_id=b.tenant_id and loc.id=b.location_id\
         join inventory_quality_status q on q.tenant_id=b.tenant_id and q.id=b.quality_status_id\
         left join inventory_lot l on l.tenant_id=b.tenant_id and l.id=b.lot_id\
         left join inventory_aging_policy ap on ap.tenant_id=b.tenant_id and ap.sku_id=b.sku_id\
         and ap.enabled\
         left join inventory_expiry_policy ep on ep.tenant_id=b.tenant_id and ep.sku_id=b.sku_id\
         where b.on_hand>0"""
            + filter
            + """
        ), classified as (
          select aged.*,\
           case when aging_days<=30 then 'AGE_0_30' when aging_days<=60 then 'AGE_31_60'\
            when aging_days<=90 then 'AGE_61_90' else 'AGE_90_PLUS' end as aging_bucket,\
           case when expires_on is null then null else (expires_on-current_date) end\
            as days_to_expiry,\
           case when expires_on is null then 'NO_EXPIRY' when expires_on<current_date then 'EXPIRED'\
            when expires_on-current_date<=lead_days then 'EXPIRING' else 'HEALTHY' end\
            as expiry_status,\
           case when aging_days>=obsolete_days then 'OBSOLETE'\
            when aging_days>=slow_moving_days then 'SLOW_MOVING' else 'NORMAL' end\
            as slow_moving_status\
         from aged), disposed as (
          select classified.*,"""
            + DISPOSITION_CASE
            + """
           from classified)\
        """;
    return sql;
  }

  /**
   * CTE 里三个 {@code coalesce} 默认值的绑定前缀，**必须在任何筛选绑定之前**。
   *
   * <p>纯文本 jOOQ 语句的 {@code ?} 是按位置对应的，而这三个默认值出现在 SELECT 里 （比 WHERE 早）。漏掉它们时 PG 报「未设定参数值 1 的内容」，
   * 报错信息完全看不出是哪个绑定，所以收口成一处拼接。
   */
  private static List<Object> agingBinds(List<Object> filters) {
    var all = new ArrayList<Object>();
    all.add(DEFAULT_SLOW_MOVING_DAYS);
    all.add(DEFAULT_OBSOLETE_DAYS);
    all.add(DEFAULT_LEAD_DAYS);
    all.addAll(filters);
    return all;
  }

  // ================================================================ 库龄分桶报表

  /**
   * 库龄分桶报表。
   *
   * <p>四个桶全部由 SQL 的 CASE 真算（无兜底默认值），并额外报出 {@link AgingTotals} —— 「各桶之和 == 总量」这条不变式由集成测试直接断言。
   */
  public AgingBucketReport bucketReport(AgingBucketQuery query) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    reportConditions(query, where, binds);

    String cte = agingCte(joinFilter(where));
    var rows =
        sql.fetch(
            cte
                + " select aging_bucket,count(distinct sku_id) as sku_count,count(*) as bucket_count,"
                + " count(*) filter (where lot_based) as lot_based_bucket_count,"
                + " sum(on_hand) as on_hand,sum(reserved) as reserved,sum(available) as available"
                + " from classified group by aging_bucket",
            agingBinds(binds).toArray());

    // 固定按四档输出，哪怕某一档命中 0 行 —— 一张少了空桶的报表会让人以为"这个区间没有这个桶"。
    Map<String, Record> byBucket = new LinkedHashMap<>();
    for (AgingBucket b : AgingBucket.values()) {
      byBucket.put(b.name(), null);
    }
    for (Record r : rows) {
      byBucket.put(r.get("aging_bucket", String.class), r);
    }

    List<AgingBucketRow> bucketRows =
        byBucket.entrySet().stream()
            .filter(e -> e.getKey() != AgingBucket.NO_INBOUND_DATE.name())
            .map(
                e -> {
                  AgingBucket bucket = AgingBucket.valueOf(e.getKey());
                  Record r = e.getValue();
                  return new AgingBucketRow(
                      bucket.name(),
                      bucket.label(),
                      bucket.lowerDays() < 0 ? null : bucket.lowerDays(),
                      bucket.upperDays() == Integer.MAX_VALUE ? null : bucket.upperDays(),
                      r == null ? 0L : longOrZero(r, "sku_count"),
                      r == null ? 0L : longOrZero(r, "bucket_count"),
                      r == null ? 0L : longOrZero(r, "lot_based_bucket_count"),
                      r == null ? BigDecimal.ZERO : decimalOrZero(r, "on_hand"),
                      r == null ? BigDecimal.ZERO : decimalOrZero(r, "reserved"),
                      r == null ? BigDecimal.ZERO : decimalOrZero(r, "available"));
                })
            .toList();

    Record totals =
        sql.fetchOne(
            cte
                + " select count(distinct sku_id) as sku_count,count(*) as bucket_count,"
                + " sum(on_hand) as on_hand,sum(reserved) as reserved,sum(available) as available"
                + " from classified",
            agingBinds(binds).toArray());

    Record thresholds =
        sql.fetchOne(
            "select count(distinct coalesce(ap.slow_moving_days,?)) as distinct_slow_days"
                + " from stock_bucket b"
                + " left join inventory_aging_policy ap on ap.tenant_id=b.tenant_id"
                + " and ap.sku_id=b.sku_id and ap.enabled where b.on_hand>0",
            new Object[] {DEFAULT_SLOW_MOVING_DAYS});

    return new AgingBucketReport(
        LocalDate.now(),
        bucketRows,
        new AgingTotals(
            longOrZero(totals, "sku_count"),
            longOrZero(totals, "bucket_count"),
            decimalOrZero(totals, "on_hand"),
            decimalOrZero(totals, "reserved"),
            decimalOrZero(totals, "available")),
        DEFAULT_SLOW_MOVING_DAYS,
        DEFAULT_OBSOLETE_DAYS,
        longOrZero(thresholds, "distinct_slow_days"),
        capitalTie(),
        AGING_BASIS_NOTE);
  }

  /**
   * 资金占用估算 —— **恒为不可用，且给出可执行的原因**。
   *
   * <p>warehouse 服务的库存事实层（V1 / V2 / V57）没有任何成本列，已 grep 全仓确认不存在 unit_cost / avg_cost / cost_amount /
   * unit_price / moving_average。成本口径属 finance 卷 （移动加权 / 批次计价 / 币种），而跨服务禁止读对方表。
   *
   * <p>所以返回 {@code available=false} + {@code occupiedAmount=null}： 宁可让界面显示「缺成本口径」，也不给一个猜出来的金额 ——
   * 蓝图自己说「呆滞占用的资金是最容易被忽略的利润黑洞」， 一个假成本会直接变成错误的清仓定价依据，比缺成本危险得多。
   */
  private static AgingCapitalTie capitalTie() {
    return new AgingCapitalTie(
        false,
        null,
        null,
        "本服务库存事实层无成本列，无法估算占用资金；" + "库龄报表目前只提供数量口径（在手/预占/可用）。",
        "finance 成本接口（移动加权或批次计价）落地后，" + "在本服务注入一个成本 Port 即可补齐，无需改动本表结构。");
  }

  private void reportConditions(AgingBucketQuery query, List<String> where, List<Object> binds) {
    if (query.skuId() != null) {
      where.add("b.sku_id=?");
      binds.add(query.skuId());
    }
    if (query.warehouseId() != null) {
      where.add("b.warehouse_id=?");
      binds.add(query.warehouseId());
    }
    if (query.legalEntityId() != null) {
      where.add("b.legal_entity_id=?");
      binds.add(query.legalEntityId());
    }
    if (query.agingBasis() != null && !query.agingBasis().isBlank()) {
      String basis = query.agingBasis().toUpperCase(Locale.ROOT);
      if (!AGING_BASES.contains(basis)) {
        throw new IllegalArgumentException(
            "不支持的库龄口径：" + query.agingBasis() + "，可选：" + String.join("、", AGING_BASES));
      }
      where.add(
          basis.equals("LOT_RECEIVED_ON") ? "l.received_on is not null" : "l.received_on is null");
      // 该条件**不需要绑定值**，但过滤写在 where 里，不占位。
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.add("(s.code ilike ? or s.name ilike ? or l.lot_code ilike ?)");
      var k = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 3; i++) binds.add(k);
    }
  }

  // ================================================================ 呆滞清单

  /** 呆滞清单（真分页 + 真筛选）。 */
  public SlowMovingPage slowMoving(SlowMovingQuery query) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() == null ? 50 : query.size()));
    int pageNumber = Math.max(0, query.page() == null ? 0 : query.page());

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    listConditions(query, where, binds);

    // 列表的筛选**只**作用于外层 disposed：listConditions 用的是 CTE 输出列名，
    // 若同时塞进 aged 的 WHERE，同一批绑定会在 SQL 里出现两次而只绑定一次。
    String cte = agingCte("");
    String whereSql = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne(cte + " select count(*) from disposed" + whereSql, agingBinds(binds).toArray())
            .get(0, Long.class);

    List<Record> rows =
        sql.fetch(
            cte
                + " select * from disposed"
                + whereSql
                + slowMovingOrder(query.sort())
                + " limit ? offset ?",
            append(agingBinds(binds), size, (long) pageNumber * size));

    return new SlowMovingPage(
        summary(cte, whereSql, binds),
        rows.stream().map(InventoryAgingService::slowMovingRow).toList(),
        statusOptions(cte, binds),
        dispositionOptions(cte, binds),
        DEFAULT_SLOW_MOVING_DAYS,
        DEFAULT_OBSOLETE_DAYS,
        AGING_BASIS_NOTE,
        SALES_VELOCITY_NOTE,
        total,
        pageNumber,
        size);
  }

  private void listConditions(SlowMovingQuery query, List<String> where, List<Object> binds) {
    // 这里的条件是套在**外层** `disposed` CTE 上的，所以只能用 CTE 的输出列名
    // （sku_id / warehouse_id / legal_entity_id / sku_code / sku_name / lot_code），
    // 不能沿用内层的 b.xxx / s.code / l.lot_code —— 那些别名出了 aged 就不存在了。
    if (query.skuId() != null) {
      where.add("sku_id=?");
      binds.add(query.skuId());
    }
    if (query.warehouseId() != null) {
      where.add("warehouse_id=?");
      binds.add(query.warehouseId());
    }
    if (query.legalEntityId() != null) {
      where.add("legal_entity_id=?");
      binds.add(query.legalEntityId());
    }
    // 未显式指定档位时只看需要处理的行（滞销/呆滞 + 临期/过期 + 残次）。
    var statusFilter = slowMovingStatusFilter(query.slowMovingStatus());
    if (statusFilter == null) {
      where.add(ACTIONABLE_SQL);
    } else {
      addEnumIn(where, binds, "slow_moving_status", statusFilter, SLOW_MOVING_STATUSES, "呆滞档位");
    }
    addEnumIn(
        where,
        binds,
        "disposition",
        query.disposition() == null || query.disposition().isBlank()
            ? null
            : List.of(query.disposition()),
        DISPOSITIONS,
        "处置建议");
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.add("(sku_code ilike ? or sku_name ilike ? or lot_code ilike ?)");
      var k = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 3; i++) binds.add(k);
    }
  }

  /**
   * 「需要处理」的判定式：**滞销/呆滞、临期/过期、残次** 任一命中即算待办。
   *
   * <p>不传档位时用它当默认筛选，与 7.3.1.2 的效期预警清单同一纪律： 待办清单的用途是「找出待办的货」。
   *
   * <p><b>刻意包含临期/过期与残次，而不只看库龄</b>：蓝图写的是 「识别滞销、临期、残次、呆滞库存」四类。一票只躺了 5 天但已经过期的货 若因为「库龄还不够 60
   * 天」而不出现在呆滞清单上，处置的人就永远看不到它 —— 那正是最不该被漏掉的一批货。
   */
  private static final String ACTIONABLE_SQL =
      "(slow_moving_status<>'NORMAL'"
          + " or expiry_status in ('EXPIRING','EXPIRED')"
          + " or quality_role<>'SELLABLE')";

  /**
   * 呆滞档位默认值：不传只看<b>需要处理</b>的行。
   *
   * <p>与 7.3.1.2 的效期预警清单同一纪律：预警/待办清单的用途是「找出待办的货」。 若默认把 NORMAL 也带出来，这个页面就退化成「全部库存列表」—— 而一个叫呆滞清单却显示
   * 100% 正常库存的页面，没人能拿它去派工。 需要看全量时显式传 {@code slowMovingStatus=NORMAL}。
   */
  private static List<String> slowMovingStatusFilter(List<String> requested) {
    if (requested == null) return null;
    var nonBlank =
        requested.stream()
            .filter(v -> v != null && !v.isBlank())
            .map(v -> v.toUpperCase(Locale.ROOT))
            .toList();
    return nonBlank.isEmpty() ? null : nonBlank;
  }

  /** 枚举多值筛选：把 {@code in (...)} 展开成等量占位符，避免把值拼进 SQL 文本。 */
  private static void addEnumIn(
      List<String> where,
      List<Object> binds,
      String column,
      List<String> values,
      List<String> allowed,
      String label) {
    if (values == null || values.isEmpty()) return;
    var normalized =
        values.stream()
            .filter(v -> v != null && !v.isBlank())
            .map(v -> v.toUpperCase(Locale.ROOT))
            .distinct()
            .toList();
    for (var v : normalized) {
      if (!allowed.contains(v)) {
        throw new IllegalArgumentException(
            "不支持的" + label + "：" + v + "，可选：" + String.join("、", allowed));
      }
    }
    if (normalized.isEmpty()) return;
    where.add(
        column
            + " in ("
            + String.join(",", java.util.Collections.nCopies(normalized.size(), "?"))
            + ")");
    binds.addAll(normalized);
  }

  private String slowMovingOrder(String sort) {
    String s = sort == null ? "AGING_DAYS" : sort.toUpperCase(Locale.ROOT);
    if (!SORTS.contains(s)) {
      throw new IllegalArgumentException("不支持的排序键：" + sort + "，可选：" + String.join("、", SORTS));
    }
    return switch (s) {
      case "AVAILABLE" -> " order by available desc,aging_days desc,sku_code,bucket_id";
      case "ON_HAND" -> " order by on_hand desc,aging_days desc,sku_code,bucket_id";
      case "SKU" -> " order by sku_code,bucket_id";
      case "WAREHOUSE" -> " order by warehouse_code,sku_code,bucket_id";
      case "RECEIVED_ON" -> " order by inbound_date,sku_code,bucket_id";
      default -> " order by aging_days desc,available desc,sku_code,bucket_id";
    };
  }

  /**
   * 呆滞清单汇总。
   *
   * <p><b>只统计「需要处理」的行</b>（滞销/呆滞 + 临期/过期 + 残次，见 {@link #ACTIONABLE_SQL}）， 且**不受档位筛选影响**（与 7.3.1.2
   * 汇总同一纪律）：默认只看待办两档时若汇总跟着收窄， 某些计数会恒为 0 —— 一个恒为 0 的字段等于没有这个字段。 另报 {@code obsoleteAndUpAvailable}（呆滞
   * + 临期 + 过期的可用量）—— 蓝图说的「利润黑洞」真正严重的是这批：既占资金又马上要报损。
   */
  private SlowMovingSummary summary(String cte, String whereSql, List<Object> binds) {
    Record r =
        sql.fetchOne(
            cte
                + " select count(distinct sku_id) as sku_count,count(*) as bucket_count,"
                + " count(*) filter (where slow_moving_status='OBSOLETE') as obsolete_count,"
                + " count(*) filter (where slow_moving_status='SLOW_MOVING') as slow_moving_count,"
                + " coalesce(sum(available) filter (where slow_moving_status='OBSOLETE'),0)"
                + "   as obsolete_available,"
                + " coalesce(sum(available) filter (where slow_moving_status='SLOW_MOVING'),0)"
                + "   as slow_moving_available,"
                + " coalesce(sum(available) filter (where slow_moving_status<>'NORMAL'"
                + "   or expiry_status in ('EXPIRING','EXPIRED')),0) as obsolete_and_up_available,"
                + " max(aging_days) as oldest_aging_days,"
                + " min(inbound_date) as oldest_inbound_date"
                + " from disposed"
                + (whereSql.isEmpty() ? " where" : whereSql + " and")
                + " "
                + ACTIONABLE_SQL,
            agingBinds(binds).toArray());
    return new SlowMovingSummary(
        longOrZero(r, "sku_count"),
        longOrZero(r, "bucket_count"),
        longOrZero(r, "obsolete_count"),
        longOrZero(r, "slow_moving_count"),
        decimalOrZero(r, "obsolete_available"),
        decimalOrZero(r, "slow_moving_available"),
        decimalOrZero(r, "obsolete_and_up_available"),
        intOrNull(r, "oldest_aging_days"),
        r.get("oldest_inbound_date", LocalDate.class));
  }

  private List<SlowMovingStatusOption> statusOptions(String cte, List<Object> binds) {
    return sql.fetch(
            cte
                + " select slow_moving_status as value,count(*) as bucket_count"
                + " from disposed group by slow_moving_status order by slow_moving_status",
            agingBinds(binds).toArray())
        .map(
            r ->
                new SlowMovingStatusOption(
                    r.get("value", String.class),
                    SlowMovingStatus.valueOf(r.get("value", String.class)).label(),
                    longOrZero(r, "bucket_count")));
  }

  private List<SlowMovingDispositionOption> dispositionOptions(String cte, List<Object> binds) {
    return sql.fetch(
            cte
                + " select disposition as value,count(*) as bucket_count"
                + " from disposed group by disposition order by disposition",
            agingBinds(binds).toArray())
        .map(
            r ->
                new SlowMovingDispositionOption(
                    r.get("value", String.class),
                    SlowMovingDisposition.valueOf(r.get("value", String.class)).label(),
                    longOrZero(r, "bucket_count")));
  }

  /**
   * 处置建议的判定 —— **在 SQL 里算，不在 Java 里补算**。
   *
   * <p>优先级（越不可挽回越靠前）：过期 > 残次 > 临期 > 呆滞 > 滞销。 一票已经过期的货若被归成「建议促销」，促销只会让它在货架上再烂 30 天。
   *
   * <p>残次品分两种：批次上有供应商引用的建议**退仓**（退货比报废少损失money）， 没有供应商信息的只能**报废** —— 不管有没有 supplier 都写「建议退仓」，
   * 就是在给一个查不到对手方的建议。
   */
  private static final String DISPOSITION_CASE =
      """
      case\
       when expiry_status='EXPIRED' then 'SCRAP'\
       when quality_role='DEFECTIVE' and supplier_ref_id is not null then 'RETURN_TO_VENDOR'\
       when quality_role='DEFECTIVE' then 'SCRAP'\
       when expiry_status='EXPIRING' then 'MARKDOWN'\
       when slow_moving_status='OBSOLETE' then 'CLEARANCE'\
       when slow_moving_status='SLOW_MOVING' then 'MARKDOWN'\
       else 'HOLD' end as disposition""";

  private static SlowMovingRow slowMovingRow(Record r) {
    int agingDays = intOrNull(r, "aging_days");
    AgingBucket bucket = AgingBucket.of(agingDays);
    SlowMovingStatus status = SlowMovingStatus.valueOf(r.get("slow_moving_status", String.class));
    String dispositionCode = r.get("disposition", String.class);
    SlowMovingDisposition disposition = SlowMovingDisposition.valueOf(dispositionCode);
    AgingDayBasis basis =
        Boolean.TRUE.equals(r.get("lot_based", Boolean.class))
            ? AgingDayBasis.LOT_RECEIVED_ON
            : AgingDayBasis.BUCKET_CREATED_AT;
    int slowDays = intOrNull(r, "slow_moving_days");
    int obsoleteDays = intOrNull(r, "obsolete_days");
    // 阈值来源直接读 SQL 的 has_policy，**不**靠「值碰巧等于默认值」反推 ——
    // 某个 SKU 真的配了 60/90 时，反推会把它误标成 DEFAULT，而那正是用户最需要看清的一行。
    String source = Boolean.TRUE.equals(r.get("has_policy", Boolean.class)) ? "POLICY" : "DEFAULT";

    return new SlowMovingRow(
        r.get("bucket_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("lot_id", UUID.class),
        r.get("lot_code", String.class),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("location_id", UUID.class),
        r.get("location_code", String.class),
        r.get("quality_status_id", UUID.class),
        r.get("quality_status_code", String.class),
        r.get("quality_role", String.class),
        r.get("received_on", LocalDate.class),
        r.get("inbound_date", LocalDate.class),
        basis.name(),
        basis.label(),
        agingDays,
        bucket.name(),
        bucket.label(),
        slowDays,
        obsoleteDays,
        source,
        status.name(),
        status.label(),
        intOrNull(r, "days_to_expiry"),
        r.get("expiry_status", String.class),
        decimalOrZero(r, "on_hand"),
        decimalOrZero(r, "reserved"),
        decimalOrZero(r, "available"),
        disposition.name(),
        disposition.label(),
        dispositionRationale(r, disposition),
        basisText(r, agingDays, slowDays, obsoleteDays, basis, status),
        false);
  }

  /**
   * 处置建议的**中文依据** —— 额外补上这一行独有的事实。
   *
   * <p>「为什么这一行建议退仓」要能独立读懂：仅靠 {@code dispositionRationale} 的通用理由， 处置的人还得自己回去看是不是真有供应商。所以这里把本行的失效日期、
   * 质量状态、库龄都写进依据里。
   */
  private static String dispositionRationale(Record r, SlowMovingDisposition disposition) {
    String expiry = r.get("expiry_status", String.class);
    String qualityRole = r.get("quality_role", String.class);
    StringBuilder sb = new StringBuilder(disposition.rationale());
    sb.append("；");
    sb.append("质量状态 ").append(r.get("quality_status_code", String.class));
    if (qualityRole != null && !"SELLABLE".equals(qualityRole)) {
      sb.append("（").append(qualityRole).append("）");
    }
    if (expiry != null && !"NO_EXPIRY".equals(expiry) && !"HEALTHY".equals(expiry)) {
      sb.append("；效期 ").append(expiry.equals("EXPIRED") ? "已过期" : "临期");
      LocalDate expiresOn = r.get("expires_on", LocalDate.class);
      if (expiresOn != null) sb.append("（").append(expiresOn).append("）");
    }
    return sb.toString();
  }

  /** 判定依据的自然语言表述：把本行用的阈值与口径都写出来。 */
  private static String basisText(
      Record r,
      Integer agingDays,
      int slowDays,
      int obsoleteDays,
      AgingDayBasis basis,
      SlowMovingStatus status) {
    StringBuilder sb = new StringBuilder();
    sb.append("库龄 ");
    sb.append(agingDays == null ? "不可得" : agingDays + " 天");
    sb.append("，按");
    sb.append(basis.label());
    LocalDate inbound = r.get("inbound_date", LocalDate.class);
    if (inbound != null) sb.append("（").append(inbound).append("）").append(" 计算");
    sb.append("；滞销线 ").append(slowDays).append(" 天、呆滞线 ").append(obsoleteDays).append(" 天");
    sb.append("，判定为").append(status.label());
    sb.append("。可用量 ").append(decimalOrZero(r, "available")).append("。");
    if (AgingDayBasis.BUCKET_CREATED_AT.equals(basis)) {
      sb.append("该行无批次收货日，库龄为建档日代理值，偏保守。");
    }
    sb.append("未纳入销售速度因子（13 销量卷未交付）。");
    return sb.toString();
  }

  // ================================================================ 阈值策略

  /** 阈值策略列表 + 全量汇总。 */
  public AgingPolicyPage policies(AgingPolicyQuery query) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() == null ? 50 : query.size()));
    int pageNumber = Math.max(0, query.page() == null ? 0 : query.page());

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (query.skuId() != null) {
      where.add("p.sku_id=?");
      binds.add(query.skuId());
    }
    if (query.enabled() != null) {
      where.add("p.enabled=?");
      binds.add(query.enabled());
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.add("(s.code ilike ? or s.name ilike ?)");
      var k = "%" + query.keyword().trim() + "%";
      binds.add(k);
      binds.add(k);
    }
    String whereSql = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from inventory_aging_policy p" + whereSql, binds.toArray())
            .get(0, Long.class);

    var rows =
        sql.fetch(
            """
            select p.id as policy_id,p.sku_id,s.code as sku_code,s.name as sku_name,\
             p.slow_moving_days,p.obsolete_days,p.enabled,p.notes,p.updated_at,\
             coalesce(agg.bucket_count,0) as bucket_count,coalesce(agg.on_hand,0) as on_hand\
             from inventory_aging_policy p\
             join sku_ref s on s.tenant_id=p.tenant_id and s.id=p.sku_id\
             left join lateral (select count(*) as bucket_count,sum(b.on_hand) as on_hand\
               from stock_bucket b where b.tenant_id=p.tenant_id and b.sku_id=p.sku_id\
               and b.on_hand>0) agg on true"""
                + whereSql
                + policyOrder(query.sort())
                + " limit ? offset ?",
            append(binds, size, (long) pageNumber * size));

    return new AgingPolicyPage(
        rows.stream()
            .map(
                r ->
                    new AgingPolicyRow(
                        r.get("policy_id", UUID.class),
                        r.get("sku_id", UUID.class),
                        r.get("sku_code", String.class),
                        r.get("sku_name", String.class),
                        intOrNull(r, "slow_moving_days"),
                        intOrNull(r, "obsolete_days"),
                        r.get("enabled", Boolean.class),
                        longOrZero(r, "bucket_count"),
                        decimalOrZero(r, "on_hand"),
                        r.get("notes", String.class),
                        r.get("updated_at", OffsetDateTime.class)))
            .toList(),
        DEFAULT_SLOW_MOVING_DAYS,
        DEFAULT_OBSOLETE_DAYS,
        total,
        pageNumber,
        size);
  }

  private static String policyOrder(String sort) {
    String s = sort == null ? "SKU" : sort.toUpperCase(Locale.ROOT);
    return switch (s) {
      case "SLOW_MOVING_DAYS" -> " order by p.slow_moving_days,s.code,p.id";
      case "OBSOLETE_DAYS" -> " order by p.obsolete_days,s.code,p.id";
      case "ON_HAND" -> " order by on_hand desc,s.code,p.id";
      case "UPDATED_AT" -> " order by p.updated_at desc,s.code,p.id";
      case "ENABLED" -> " order by p.enabled desc,s.code,p.id";
      default -> " order by s.code,p.id";
    };
  }

  /**
   * 单个 SKU 的生效策略：配了就用配的，没配用兜底默认值并如实标成 {@code configured=false}。
   *
   * <p>跨租户的 SKU 查不到 → 404，不泄露存在性。
   */
  public AgingPolicyEffective policy(UUID skuId) {
    Record r =
        sql.fetchOne(
            "select s.id as sku_id,s.code as sku_code,s.name as sku_name,p.id as policy_id,"
                + " p.slow_moving_days,p.obsolete_days,p.enabled,p.notes,"
                + " (select count(*) from stock_bucket b where b.tenant_id=s.tenant_id"
                + "  and b.sku_id=s.id and b.on_hand>0) as bucket_count"
                + " from sku_ref s"
                + " left join inventory_aging_policy p on p.tenant_id=s.tenant_id and p.sku_id=s.id"
                + " where s.id=?",
            skuId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SKU 不存在或无权查看");
    }
    boolean configured = r.get("policy_id", UUID.class) != null;
    return new AgingPolicyEffective(
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        configured ? intOrNull(r, "slow_moving_days") : DEFAULT_SLOW_MOVING_DAYS,
        configured ? intOrNull(r, "obsolete_days") : DEFAULT_OBSOLETE_DAYS,
        configured && Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        configured,
        configured ? "POLICY" : "DEFAULT",
        r.get("bucket_count", Long.class),
        r.get("notes", String.class));
  }

  /**
   * 建立或修改某 SKU 的库龄阈值策略（按 SKU 幂等 upsert）。
   *
   * <p>用 {@code INSERT ... ON CONFLICT (tenant_id,sku_id) DO UPDATE} 而不是「先查再决定」： 前者由 DB
   * 唯一索引在并发下裁决，后者的两次调用可能都走 INSERT 分支而撞 23505。
   *
   * @param tenantId 来自已认证成员关系（控制器传入），**不是**客户端参数。 RLS 的 WITH CHECK 会再用它复核一次，两道都不靠客户端。
   */
  public AgingPolicyEffective upsertPolicy(UUID tenantId, AgingPolicyUpsertRequest request) {
    if (request.skuId() == null) {
      throw new IllegalArgumentException("必须给出 skuId（阈值策略按 SKU 配置，不是按租户）");
    }
    UUID skuId = request.skuId();
    if (sql.fetchOne("select s.id from sku_ref s where s.id=?", skuId) == null) {
      throw new IllegalArgumentException("SKU 不存在或无权访问：" + skuId);
    }

    int slowDays =
        request.slowMovingDays() == null ? DEFAULT_SLOW_MOVING_DAYS : request.slowMovingDays();
    int obsoleteDays =
        request.obsoleteDays() == null ? DEFAULT_OBSOLETE_DAYS : request.obsoleteDays();
    for (var pair : List.of(Map.entry("滞销线", slowDays), Map.entry("呆滞线", obsoleteDays))) {
      int v = pair.getValue();
      if (v < 0 || v > MAX_THRESHOLD_DAYS) {
        throw new IllegalArgumentException(
            pair.getKey() + "必须在 0 ~ " + MAX_THRESHOLD_DAYS + " 天之间（当前 " + v + "）");
      }
    }
    if (obsoleteDays < slowDays) {
      throw new IllegalArgumentException(
          "呆滞线不能早于滞销线（呆滞 "
              + obsoleteDays
              + " 天 < 滞销 "
              + slowDays
              + " 天）：呆滞是滞销的更严重档，两条线倒过来会让同一条库存同时命中两档");
    }

    var notes = request.notes() == null ? "" : request.notes().trim();
    if (notes.length() > 500) {
      throw new IllegalArgumentException("策略备注长度不能超过 500 个字符");
    }

    try {
      sql.execute(
          "insert into inventory_aging_policy(id,tenant_id,sku_id,slow_moving_days,obsolete_days,"
              + "enabled,notes) values(?,?,?,?,?,?,?)"
              + " on conflict (tenant_id,sku_id) do update set"
              + " slow_moving_days=excluded.slow_moving_days,"
              + " obsolete_days=excluded.obsolete_days,enabled=excluded.enabled,"
              + " notes=excluded.notes,updated_at=now()",
          UUID.randomUUID(),
          tenantId,
          skuId,
          slowDays,
          obsoleteDays,
          request.enabled() != null && request.enabled(),
          notes);
    } catch (org.springframework.dao.DataAccessException e) {
      throw translateConflict(e);
    }
    return policy(skuId);
  }

  /** 唯一/检查冲突翻译成 409/400：不把 PG 的约束名原样抛给用户。 */
  private static RuntimeException translateConflict(org.springframework.dao.DataAccessException e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) {
        return new ResponseStatusException(
            HttpStatus.CONFLICT, "该 SKU 已存在库龄阈值策略，请改用修改（同一 SKU 只允许一条）");
      }
    }
    return e;
  }

  // ================================================================ 工具

  private static String joinFilter(List<String> where) {
    return where.isEmpty() ? "" : " and " + String.join(" and ", where);
  }

  private static Object[] append(List<Object> binds, Object... extra) {
    var all = new ArrayList<Object>(binds);
    all.addAll(java.util.Arrays.asList(extra));
    return all.toArray();
  }

  /**
   * 取整数列。
   *
   * <p>{@code current_date - 入库日} 与 {@code count(*)} 在 PG 里都是 integer， 但**纯文本语句里 jOOQ 拿不到列类型**，直接
   * {@code r.get(field, Integer.class)} 会因类型不匹配而返回 null —— 于是每一行都退化成「库龄为空」，
   * 而这恰恰是本模块唯一的主指标。这里收口成不受驱动返回类型影响的读法。
   */
  private static Integer intOrNull(Record r, String field) {
    var value = r.get(field);
    if (value == null) return null;
    if (value instanceof Number n) return n.intValue();
    try {
      return Integer.valueOf(value.toString());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** 取数量列。数量是 {@code numeric(19,4)}，全程 {@link BigDecimal}，禁浮点。 */
  private static BigDecimal decimalOrZero(Record r, String field) {
    BigDecimal v = r.get(field, BigDecimal.class);
    return v == null ? BigDecimal.ZERO : v;
  }

  private static long longOrZero(Record r, String field) {
    var v = r.get(field);
    if (v == null) return 0L;
    if (v instanceof Number n) return n.longValue();
    try {
      return Long.parseLong(v.toString());
    } catch (NumberFormatException e) {
      return 0L;
    }
  }
}
