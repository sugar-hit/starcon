package com.starcon.trade;

import static com.starcon.trade.StockoutRiskModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.2.1.1 缺货与断货风险。
 *
 * <p>蓝图原话：「识别断货风险 SKU，输出补货优先级与缺口量。」输入「销售速度（日均销量）、
 * 当前可用库存、在途量、备货周期」。输出「断货风险清单（SKU、可用量、日均销、可支撑天数、
 * 预计断货日、缺口量、涉及店铺），按紧急度排序」。
 *
 * <p><b>本模块只做一件事：把「会断货」变成一条有凭据、有结论、可排序的记录。</b>
 *
 * <p><b>七条实现纪律</b>：
 *
 * <ol>
 *   <li><b>时钟用数据库的</b>：评估基准日取 {@code (current_timestamp at time zone 'UTC')::date}，
 *       计算时刻取 {@code clock_timestamp()}，不用 Java 的 {@code Instant.now()}。
 *       全部日期口径统一按 <b>UTC</b>，免得「评估日」与「ETA 落在哪一天」各按各的时区算，
 *       差一天就足以把「今天断货」说成「明天断货」。</li>
 *   <li><b>有效可用量不重算</b>：直接取 warehouse 7.1.1.3 的<b>可售量</b>（{@code sellable}）。
 *       可售量是全系统的单一口径来源，本模块再实现一遍 {@code available − buffer}，
 *       必然与防超卖的那个数漂移，而漂移的方向正好是「看起来比实际多」。</li>
 *   <li><b>「无数据」是一等状态</b>：没有销售速度登记时，日均销 / 可支撑天数 / 预计断货日
 *       全部是 <b>null</b>，紧急度是 {@code NO_DATA}。<b>绝不写 0</b> ——
 *       0 日均销量会让可支撑天数变成无穷、健康度被高估。</li>
 *   <li><b>可支撑天数是整数天</b>（向下取整）。天数是「几天」这个口径，
 *       不是「几天零几小时」；向下取整是不高估（{@code 10 ÷ 3 = 3 天}，不是 3.33 天）。</li>
 *   <li><b>在途只扣「备货周期内能到」的那部分</b>：没有预计到达日的在途<b>不</b>计入 ——
 *       它救不了确定的那一天。缺口量 = 备货周期内预期消耗 − 有效可用量 − 备货周期内可到达在途。</li>
 *   <li><b>结论落库成快照</b>：一次计算 = 一份快照 + 逐行结论 + 逐项拆解 + 店铺明细。
 *       「可支撑天数 / 预计断货日 / 缺口量 / 涉及店铺」要能被展开复算，就不能只在内存里算一遍。</li>
 *   <li><b>排序依据随结论一起返回</b>：{@code urgency_basis} 记下分档与排序的原文，
 *       清单页直接显示这行就能复现判定。</li>
 * </ol>
 */
@Service
public class StockoutRiskService {

  /** 数量刻度：与 DB 的 numeric(19,6) 对齐。 */
  private static final int QUANTITY_SCALE = 6;

  private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);

  /**
   * 纳入补货口径的在途类型。
   *
   * <p>调拨 / 头程 / 采购三类都是「往我方仓里补货」的路。刻意<b>排除</b> {@code FBA_OUTBOUND}：
   * 那是「从我方仓发往平台仓」，方向相反 —— 把它算成补货，会让缺口被一个
   * <b>正在离开</b>的量掩盖，那正是重复补货的另一个来源。
   */
  static final List<String> REPLENISH_TRANSIT_KINDS = List.of("TRANSFER", "FIRST_MILE", "PURCHASE");

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 单次计算最多逐个查多少个 SKU 的店铺映射（超出就如实报出截断）。 */
  private static final int MAX_SHOP_LOOKUPS = 500;

  private final DSLContext sql;
  private final StockoutInventoryPort inventory;
  private final StockoutChannelPort channel;

  public StockoutRiskService(
      DSLContext sql, StockoutInventoryPort inventory, StockoutChannelPort channel) {
    this.sql = sql;
    this.inventory = inventory;
    this.channel = channel;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>四个输入里有两个来自 warehouse、一个来自 channel、一个是本服务自建的登记；
   * 哪一个接通了必须由接口自己报出，而不是让调用方从空数组里猜。
   */
  public StockoutCapabilities capabilities() {
    return new StockoutCapabilities(
        true,
        "6.2.1.1 本模块自建（13 销量/报表服务在本仓不存在）",
        "日均销量是本模块四个输入里唯一一个没有其它服务可以给的，"
            + "所以它是本服务的一张**版本化、append-only、可追溯**的登记事实表（stockout_sales_velocity）。"
            + "「3 月 1 日那天用的日均销是多少」必须答得上来，所以登记带生效期与统计窗口。",
        true,
        true,
        true,
        false,
        "店铺主数据属 channel 2.3.1.1，本模块只落 shop_code 快照，不复制别人的主数据。",
        false,
        "6.3 采购单（6.3.1.x）在本仓**未交付**，所以在途只落来源单据号（sourceReference），不建跨库外键。",
        "紧急度分档：NO_DATA 没有销售速度登记；CRITICAL 可支撑天数为 0（今天就没有可售库存）；"
            + "HIGH 断货日落在备货周期内且缺口量 > 0；MEDIUM 断货日落在备货周期内但缺口为 0，"
            + "或落在评估窗口内且缺口量 > 0；LOW 其余（含日均销量登记为 0）。"
            + "清单按 紧急度 → 可支撑天数（升序）→ 缺口量（降序）→ SKU 编码 排序。",
        "可支撑天数 = floor(有效可用量 ÷ 日均销量)，整数天、向下取整；"
            + "有效可用量取 warehouse 7.1.1.3 的可售量（sellable），本模块不重算。",
        "缺口量 = max(0, 日均销量 × 备货周期 − 有效可用量 − 备货周期内可到达在途量)。"
            + "备货周期内可到达在途量只计 ETA 落在 [评估日, 评估日 + 备货周期] 内的在途腿；"
            + "没有 ETA 的在途不计入（它救不了确定的那一天）。",
        "日均销量缺登记时，日均销 / 可支撑天数 / 预计断货日一律是 null（不是 0），"
            + "紧急度是 NO_DATA —— 那是「要人补数据」的洞，不是「不紧急」。");
  }

  // ================================================================ 销售速度登记（输入事实）

  /**
   * 登记一条销售速度。
   *
   * <p><b>版本化</b>：同一 SKU 已有未收口的登记时，新登记会把旧的那条<b>收口</b>
   * （把它的 {@code effective_to} 设成新登记的 {@code effective_from}），而不是覆盖它。
   * 覆盖会让「当时按哪个数算的」永远查不回来 —— 而这正是本模块的可追溯性承诺。
   *
   * <p>收口与插入在同一个事务里：收口成功但插入失败会一起回滚，不会留下「两个当前生效版本」。
   */
  @Transactional
  public StockoutVelocityRow registerVelocity(StockoutVelocityInput input, UUID actorId) {
    if (input.windowEnd().isBefore(input.windowStart())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "统计窗口的结束日不能早于开始日");
    }
    if (input.effectiveTo() != null && input.effectiveTo().isBefore(input.effectiveFrom())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "失效日不能早于生效起点");
    }
    // 同一 SKU 已有未收口的登记 → 先收口旧的。
    var open =
        sql.fetchOne(
            "select id, effective_from from stockout_sales_velocity"
                + " where tenant_id = ?::uuid and sku_id = ?::uuid and effective_to is null"
                + " order by effective_from desc limit 1",
            currentTenant(),
            input.skuId());
    if (open != null) {
      UUID previousId = open.get("id", UUID.class);
      LocalDate previousFrom = open.get("effective_from", LocalDate.class);
      if (!input.effectiveFrom().isAfter(previousFrom)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "该 SKU 已有一条从 "
                + previousFrom
                + " 起生效的销售速度登记。新登记的生效起点必须晚于它 —— "
                + "否则同一时间点会有两个日均销，「当前日均销」就没有唯一答案了");
      }
      sql.execute(
          "update stockout_sales_velocity set effective_to = ?::date"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          input.effectiveFrom(),
          currentTenant(),
          previousId);
    }
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into stockout_sales_velocity(id,tenant_id,sku_id,sku_code,sku_name,"
            + " daily_sales_quantity,window_start,window_end,effective_from,effective_to,"
            + " source_kind,source_ref,note,created_by,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?,?,?::numeric,?::date,?::date,?::date,?::date,?::varchar,?,?,?::uuid,?::timestamptz)",
        id,
        currentTenant(),
        input.skuId(),
        input.skuCode(),
        input.skuName() == null ? "" : input.skuName(),
        q(input.dailySalesQuantity()),
        input.windowStart(),
        input.windowEnd(),
        input.effectiveFrom(),
        input.effectiveTo(),
        input.sourceKind().name(),
        input.sourceRef() == null ? "" : input.sourceRef(),
        input.note() == null ? "" : input.note(),
        actorId,
        OffsetDateTime.now(ZoneOffset.UTC));
    return velocityRow(id);
  }

  /** 销售速度登记列表（真分页 + 真筛选）。 */
  public StockoutVelocityPage pageVelocities(String skuCode, String keyword, boolean effectiveOnly, int page, int size) {
    int pageNo = Math.max(0, page);
    int pageSize = pageSize(size);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("tenant_id = ?::uuid");
    binds.add(currentTenant());
    if (skuCode != null && !skuCode.isBlank()) {
      where.add("sku_code ilike ?");
      binds.add("%" + skuCode.trim() + "%");
    }
    if (keyword != null && !keyword.isBlank()) {
      where.add("(sku_code ilike ? or sku_name ilike ?)");
      binds.add("%" + keyword.trim() + "%");
      binds.add("%" + keyword.trim() + "%");
    }
    if (effectiveOnly) {
      where.add("(effective_to is null or effective_to > current_date)");
    }
    String clause = String.join(" and ", where);
    long total =
        sql.fetchOne("select count(*) from stockout_sales_velocity where " + clause, binds.toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select id,sku_id,sku_code,sku_name,daily_sales_quantity,window_start,window_end,"
                + " effective_from,effective_to,source_kind,source_ref,note,created_at"
                + " from stockout_sales_velocity where "
                + clause
                + " order by effective_from desc, id desc limit ? offset ?",
            append(binds, pageSize, (long) pageNo * pageSize).toArray());
    List<StockoutVelocityRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(velocityItem(row));
    }
    return new StockoutVelocityPage(items, total, pageNo, pageSize);
  }

  private StockoutVelocityRow velocityRow(UUID id) {
    var row =
        sql.fetchOne(
            "select id,sku_id,sku_code,sku_name,daily_sales_quantity,window_start,window_end,"
                + " effective_from,effective_to,source_kind,source_ref,note,created_at"
                + " from stockout_sales_velocity where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "销售速度登记不存在");
    }
    return velocityItem(row);
  }

  private StockoutVelocityRow velocityItem(Record row) {
    LocalDate effectiveTo = row.get("effective_to", LocalDate.class);
    return new StockoutVelocityRow(
        row.get("id", UUID.class),
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("sku_name", String.class),
        row.get("daily_sales_quantity", BigDecimal.class),
        row.get("window_start", LocalDate.class),
        row.get("window_end", LocalDate.class),
        row.get("effective_from", LocalDate.class),
        effectiveTo,
        row.get("source_kind", String.class),
        sourceLabel(row.get("source_kind", String.class)),
        row.get("source_ref", String.class),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class),
        effectiveTo == null);
  }

  private static String sourceLabel(String kind) {
    for (var source : StockoutVelocitySource.values()) {
      if (source.name().equals(kind)) {
        return source.label();
      }
    }
    return kind;
  }

  // ================================================================ 计算（一次 = 一份快照）

  /**
   * 计算一次断货风险清单并落库。
   *
   * <p><b>SKU 全集</b> = 有可售量记录的 SKU ∪ 有销售速度登记的 SKU。
   *
   * <p>为什么「没登记销售速度」的 SKU 也要进清单：那一档是 {@code NO_DATA}，
   * 它要告诉运营「这个 SKU 答不出来，请去登记销售速度」。把它踢出清单等于让数据缺口隐形 ——
   * 而缺口一旦隐形，就永远没人补。
   */
  @Transactional
  public StockoutComputeResult compute(StockoutComputeInput input, UUID actorId, String bearer) {
    int leadTimeDays = input.leadTimeDays();
    int horizonDays = input.horizonDays() == null ? leadTimeDays * 2 : input.horizonDays();
    if (horizonDays < leadTimeDays) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "评估窗口不能短于备货周期 —— 缺口量是按备货周期算的");
    }

    // ---- 数据库时钟（不用 Java 时钟；日期口径统一 UTC）
    var clock =
        sql.fetchOne(
            "select (current_timestamp at time zone 'UTC')::date::text as d, clock_timestamp() as ts");
    LocalDate asOfDate =
        input.asOfDate() != null ? input.asOfDate() : LocalDate.parse(clock.get("d", String.class));
    OffsetDateTime computedAt = clock.get("ts", OffsetDateTime.class);
    LocalDate replenishmentArrivalDate = asOfDate.plusDays(leadTimeDays);
    LocalDate horizonEnd = asOfDate.plusDays(horizonDays);

    // ---- 上游读数（warehouse 授权只读接口）
    var sellable = inventory.sellable(bearer);
    var transit = inventory.transitLegs(REPLENISH_TRANSIT_KINDS, bearer);

    // ---- 销售速度事实（本服务的表；租户已在事务里绑定）
    Map<UUID, VelocityFact> velocities = effectiveVelocities(asOfDate);

    // ---- SKU 全集：有可售量记录的 SKU ∪ 有销售速度登记的 SKU
    //
    // 「没有销售速度登记」**不是**把 SKU 踢出清单的理由：那一档是 NO_DATA，
    // 它要告诉运营「这个 SKU 答不出来，请去登记销售速度」。把它踢掉等于让数据缺口隐形 ——
    // 而缺口一旦隐形，就永远没人补。
    Map<UUID, StockoutInventoryPort.SellableRow> stock = new LinkedHashMap<>();
    for (var row : sellable.rows()) {
      if (row.skuId() != null) {
        stock.put(row.skuId(), row);
      }
    }
    var transitBySku = groupTransit(transit.rows());

    List<UUID> universe = new ArrayList<>();
    for (var entry : stock.entrySet()) {
      universe.add(entry.getKey());
    }
    for (UUID skuId : velocities.keySet()) {
      if (!stock.containsKey(skuId)) {
        universe.add(skuId);
      }
    }

    // ---- 逐 SKU 计算（先算，再排，最后落库）
    List<RiskRow> computed = new ArrayList<>();
    int withoutVelocity = 0;
    for (UUID skuId : universe) {
      var sellableRow = stock.get(skuId);
      VelocityFact velocity = velocities.get(skuId);
      if (velocity == null) {
        withoutVelocity++;
      }
      computed.add(computeRow(skuId, sellableRow, velocity, transitBySku.get(skuId), asOfDate,
          leadTimeDays, horizonDays, replenishmentArrivalDate));
    }
    computed.sort(
        Comparator.comparingInt((RiskRow r) -> r.urgency().rank())
            .thenComparing(
                r -> r.coverageDays() == null ? Integer.MAX_VALUE : r.coverageDays())
            .thenComparing(r -> r.shortageQuantity().negate())
            .thenComparing(RiskRow::skuCode));

    // ---- 涉及店铺（逐 SKU 调 channel 的授权只读接口）
    boolean shopsTruncated = computed.size() > MAX_SHOP_LOOKUPS;
    int lookupCount = Math.min(computed.size(), MAX_SHOP_LOOKUPS);
    Map<UUID, List<ShopFact>> shops = new HashMap<>();
    for (int i = 0; i < lookupCount; i++) {
      UUID skuId = computed.get(i).skuId();
      var mappings = channel.mappingsForSku(skuId, bearer);
      Map<String, Integer> byShop = new LinkedHashMap<>();
      for (var mapping : mappings) {
        if (mapping.shopCode() == null || mapping.shopCode().isBlank()) {
          continue;
        }
        byShop.merge(mapping.shopCode(), 1, Integer::sum);
      }
      shops.put(skuId, new ArrayList<>(byShop.entrySet().stream()
          .map(e -> new ShopFact(e.getKey(), e.getValue()))
          .toList()));
    }

    // ---- 落库
    UUID tenantId = currentTenant();
    UUID snapshotId = UUID.randomUUID();
    String snapshotNo =
        "SR-"
            + asOfDate.toString().replace("-", "")
            + "-"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    int criticalCount = (int) computed.stream().filter(r -> r.urgency() == StockoutUrgency.CRITICAL).count();
    int noDataCount = (int) computed.stream().filter(r -> r.urgency() == StockoutUrgency.NO_DATA).count();
    sql.execute(
        "insert into stockout_risk_snapshot(id,tenant_id,snapshot_no,as_of_date,computed_at,"
            + " lead_time_days,horizon_days,item_count,critical_count,no_data_count,created_by,note)"
            + " values (?::uuid,?::uuid,?,?::date,?::timestamptz,?::int,?::int,?::int,?::int,?::int,?::uuid,?)",
        snapshotId,
        tenantId,
        snapshotNo,
        asOfDate,
        computedAt,
        leadTimeDays,
        horizonDays,
        computed.size(),
        criticalCount,
        noDataCount,
        actorId,
        input.note() == null ? "" : input.note());

    int rank = 0;
    for (RiskRow row : computed) {
      UUID itemId = UUID.randomUUID();
      sql.execute(
          "insert into stockout_risk_item(id,tenant_id,snapshot_id,sku_id,sku_code,sku_name,"
              + " on_hand_quantity,reserved_quantity,safety_buffer_quantity,available_quantity,"
              + " in_transit_quantity,in_transit_arriving_quantity,in_transit_no_eta_quantity,"
              + " daily_sales_quantity,coverage_days,expected_stockout_date,replenishment_arrival_date,"
              + " shortage_quantity,urgency,urgency_basis,shop_count,sort_rank)"
              + " values (?::uuid,?::uuid,?::uuid,?::uuid,?,?,?::numeric,?::numeric,?::numeric,?::numeric,"
              + " ?::numeric,?::numeric,?::numeric,?::numeric,?::int,?::date,?::date,?::numeric,?::varchar,?,?::int,?::int)",
          itemId,
          tenantId,
          snapshotId,
          row.skuId(),
          row.skuCode(),
          row.skuName(),
          row.onHand(),
          row.reserved(),
          row.safetyBuffer(),
          row.available(),
          row.inTransitTotal(),
          row.inTransitArriving(),
          row.inTransitNoEta(),
          row.dailySales(),
          row.coverageDays(),
          row.expectedStockoutDate(),
          row.replenishmentArrivalDate(),
          row.shortageQuantity(),
          row.urgency().name(),
          row.urgencyBasis(),
          shops.getOrDefault(row.skuId(), List.of()).size(),
          rank);
      for (ShopFact shop : shops.getOrDefault(row.skuId(), List.of())) {
        sql.execute(
            "insert into stockout_risk_item_shop(id,tenant_id,item_id,shop_code,listing_count)"
                + " values (?::uuid,?::uuid,?::uuid,?,?::int)",
            UUID.randomUUID(),
            tenantId,
            itemId,
            shop.shopCode(),
            shop.listingCount());
      }
      for (Deduction deduction : row.deductions()) {
        sql.execute(
            "insert into stockout_risk_item_deduction(id,tenant_id,item_id,step_order,step_kind,"
                + " quantity,basis,source_ref)"
                + " values (?::uuid,?::uuid,?::uuid,?::int,?::varchar,?::numeric,?,?)",
            UUID.randomUUID(),
            tenantId,
            itemId,
            deduction.stepOrder(),
            deduction.stepKind().name(),
            deduction.quantity(),
            deduction.basis(),
            deduction.sourceRef());
      }
      rank++;
    }

    List<String> notes = new ArrayList<>();
    if (withoutVelocity > 0) {
      notes.add(
          "有 "
              + withoutVelocity
              + " 个 SKU 未登记销售速度，已列入清单但判不出断货日（NO_DATA 档）—— 请补登记");
    }
    if (sellable.truncated() || transit.truncated()) {
      notes.add("上游分页被页数上限截断，本次清单**不完整**");
    }
    if (shopsTruncated) {
      notes.add("SKU 数超过店铺映射查询上限（" + MAX_SHOP_LOOKUPS + "），超出部分的「涉及店铺」为空");
    }
    String note = notes.isEmpty() ? "" : String.join("；", notes);
    return new StockoutComputeResult(
        snapshotId,
        snapshotNo,
        asOfDate,
        computedAt,
        leadTimeDays,
        horizonDays,
        computed.size(),
        criticalCount,
        noDataCount,
        sellable.truncated() || transit.truncated() || shopsTruncated,
        note,
        input.note() == null ? "" : input.note());
  }

  /** 读评估日生效的销售速度登记（每个 SKU 取生效起点最晚的那条）。 */
  private Map<UUID, VelocityFact> effectiveVelocities(LocalDate asOfDate) {
    List<Record> rows =
        sql.fetch(
            "select distinct on (sku_id) sku_id,sku_code,sku_name,daily_sales_quantity,"
                + " window_start,window_end,effective_from,source_kind,source_ref"
                + " from stockout_sales_velocity"
                + " where tenant_id = ?::uuid and effective_from <= ?::date"
                + "   and (effective_to is null or effective_to > ?::date)"
                + " order by sku_id, effective_from desc, created_at desc",
            currentTenant(),
            asOfDate,
            asOfDate);
    Map<UUID, VelocityFact> result = new LinkedHashMap<>();
    for (Record row : rows) {
      result.put(
          row.get("sku_id", UUID.class),
          new VelocityFact(
              row.get("sku_code", String.class),
              row.get("sku_name", String.class),
              row.get("daily_sales_quantity", BigDecimal.class),
              row.get("window_start", LocalDate.class),
              row.get("window_end", LocalDate.class),
              row.get("source_kind", String.class),
              row.get("source_ref", String.class)));
    }
    return result;
  }

  /** 在途腿按 SKU 分组（只留下仍在途、量大于 0 的腿）。 */
  private Map<UUID, List<StockoutInventoryPort.TransitLegRow>> groupTransit(
      List<StockoutInventoryPort.TransitLegRow> legs) {
    Map<UUID, List<StockoutInventoryPort.TransitLegRow>> result = new LinkedHashMap<>();
    for (var leg : legs) {
      if (leg.skuId() == null || leg.outstandingQuantity() == null) {
        continue;
      }
      if (leg.outstandingQuantity().compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      result.computeIfAbsent(leg.skuId(), k -> new ArrayList<>()).add(leg);
    }
    return result;
  }

  /** 逐 SKU 的结论（含逐项拆解）。 */
  private RiskRow computeRow(
      UUID skuId,
      StockoutInventoryPort.SellableRow sellableRow,
      VelocityFact velocity,
      List<StockoutInventoryPort.TransitLegRow> legs,
      LocalDate asOfDate,
      int leadTimeDays,
      int horizonDays,
      LocalDate replenishmentArrivalDate) {
    BigDecimal onHand = sellableRow == null ? ZERO : q(sellableRow.onHand());
    BigDecimal reserved = sellableRow == null ? ZERO : q(sellableRow.reserved());
    BigDecimal safetyBuffer = sellableRow == null ? ZERO : q(sellableRow.safetyBuffer());
    BigDecimal available = sellableRow == null ? ZERO : q(sellableRow.sellable());

    String skuCode = sellableRow != null && sellableRow.skuCode() != null
        ? sellableRow.skuCode()
        : (velocity == null ? skuId.toString() : velocity.skuCode());
    String skuName = sellableRow != null && sellableRow.skuName() != null
        ? sellableRow.skuName()
        : (velocity == null ? "" : velocity.skuName());

    // 在途：合计 / 备货周期内可到达 / 没有 ETA
    BigDecimal inTransitTotal = ZERO;
    BigDecimal inTransitArriving = ZERO;
    BigDecimal inTransitNoEta = ZERO;
    List<StockoutInventoryPort.TransitLegRow> transitLegs =
        legs == null ? List.of() : legs;
    for (var leg : transitLegs) {
      BigDecimal quantity = q(leg.outstandingQuantity());
      inTransitTotal = inTransitTotal.add(quantity);
      if (leg.expectedArriveAt() == null) {
        inTransitNoEta = inTransitNoEta.add(quantity);
        continue;
      }
      LocalDate etaDate = leg.expectedArriveAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
      if (!etaDate.isBefore(asOfDate) && !etaDate.isAfter(replenishmentArrivalDate)) {
        inTransitArriving = inTransitArriving.add(quantity);
      }
    }

    // 销售速度与结论
    BigDecimal dailySales = velocity == null ? null : q(velocity.dailySalesQuantity());
    Integer coverageDays = null;
    LocalDate expectedStockoutDate = null;
    BigDecimal shortage = ZERO;
    BigDecimal leadTimeConsumption = ZERO;
    if (dailySales != null && dailySales.compareTo(BigDecimal.ZERO) > 0) {
      // 可支撑天数 = floor(有效可用量 ÷ 日均销量)，向下取整（不高估）。
      coverageDays = available.divide(dailySales, 0, RoundingMode.FLOOR).intValue();
      expectedStockoutDate = asOfDate.plusDays(coverageDays);
      leadTimeConsumption = dailySales.multiply(BigDecimal.valueOf(leadTimeDays));
      shortage = leadTimeConsumption.subtract(available).subtract(inTransitArriving).max(ZERO);
    }

    StockoutUrgency urgency = urgency(coverageDays, dailySales, shortage, leadTimeDays, horizonDays);
    String basis = urgencyBasis(
        urgency, available, dailySales, coverageDays, expectedStockoutDate, shortage,
        inTransitArriving, leadTimeDays, horizonDays, asOfDate);

    List<Deduction> deductions = new ArrayList<>();
    deductions.add(new Deduction(
        1, StockoutDeductionStep.ON_HAND, onHand, "warehouse 7.1.1.3 可用量：在手合计", "warehouse/inventory/sellable/skus"));
    deductions.add(new Deduction(
        2, StockoutDeductionStep.RESERVED, reserved, "warehouse 7.1.1.3 可用量：预占合计", "warehouse/inventory/sellable/skus"));
    deductions.add(new Deduction(
        3, StockoutDeductionStep.SAFETY_BUFFER, safetyBuffer,
        "warehouse 7.1.1.3 可用量：安全缓冲（未配置时为 0）", "warehouse/inventory/sellable/skus"));
    deductions.add(new Deduction(
        4, StockoutDeductionStep.SELLABLE, available,
        "有效可用量 = warehouse 7.1.1.3 的可售量（sellable），本模块不重算", "warehouse/inventory/sellable/skus"));
    deductions.add(new Deduction(
        5, StockoutDeductionStep.IN_TRANSIT_TOTAL, inTransitTotal,
        "warehouse 7.5.1.2 在途：未结算在途量合计（调拨 / 头程 / 采购）", "warehouse/inventory/in-transit"));
    deductions.add(new Deduction(
        6, StockoutDeductionStep.IN_TRANSIT_ARRIVING, inTransitArriving,
        "其中预计到达日落在 [" + asOfDate + ", " + replenishmentArrivalDate + "] 内的在途量", "warehouse/inventory/in-transit"));
    deductions.add(new Deduction(
        7, StockoutDeductionStep.IN_TRANSIT_NO_ETA, inTransitNoEta,
        "没有预计到达日的在途量（救不了确定的那一天，不计入缺口）", "warehouse/inventory/in-transit"));
    deductions.add(new Deduction(
        8, StockoutDeductionStep.DAILY_SALES, dailySales,
        velocity == null
            ? "没有销售速度登记 —— 日均销量是「无数据」，不是 0"
            : "日均销量（来源 " + velocity.sourceKind() + (velocity.sourceRef().isBlank()
                ? "" : " / " + velocity.sourceRef())
                + "，统计窗口 " + velocity.windowStart() + " ~ " + velocity.windowEnd() + "）",
        "trade/stockout-risk/velocities"));
    deductions.add(new Deduction(
        9, StockoutDeductionStep.LEAD_TIME_CONSUMPTION, leadTimeConsumption,
        "备货周期内预期消耗 = 日均销量 × 备货周期 " + leadTimeDays + " 天", "本模块计算"));
    deductions.add(new Deduction(
        10, StockoutDeductionStep.SHORTAGE, shortage,
        "缺口量 = max(0, 备货周期内预期消耗 − 有效可用量 − 备货周期内可到达在途量)", "本模块计算"));

    return new RiskRow(
        skuId, skuCode, skuName, onHand, reserved, safetyBuffer, available,
        inTransitTotal, inTransitArriving, inTransitNoEta, dailySales, coverageDays,
        expectedStockoutDate, replenishmentArrivalDate, shortage, urgency, basis, deductions);
  }

  /** 紧急度分档（唯一实现处；迁移里由 CHECK 钉住取值域）。 */
  private static StockoutUrgency urgency(
      Integer coverageDays,
      BigDecimal dailySales,
      BigDecimal shortage,
      int leadTimeDays,
      int horizonDays) {
    if (dailySales == null) {
      return StockoutUrgency.NO_DATA;
    }
    if (dailySales.compareTo(BigDecimal.ZERO) == 0) {
      // 登记事实：本 SKU 没有消耗，不会因销量断货。
      return StockoutUrgency.LOW;
    }
    if (coverageDays == 0) {
      return StockoutUrgency.CRITICAL;
    }
    boolean hasShortage = shortage.compareTo(BigDecimal.ZERO) > 0;
    if (coverageDays <= leadTimeDays) {
      return hasShortage ? StockoutUrgency.HIGH : StockoutUrgency.MEDIUM;
    }
    if (coverageDays <= horizonDays) {
      return hasShortage ? StockoutUrgency.MEDIUM : StockoutUrgency.LOW;
    }
    return StockoutUrgency.LOW;
  }

  /** 分档与排序依据的原文（清单页直接显示这行就能复现判定）。 */
  private static String urgencyBasis(
      StockoutUrgency urgency,
      BigDecimal available,
      BigDecimal dailySales,
      Integer coverageDays,
      LocalDate expectedStockoutDate,
      BigDecimal shortage,
      BigDecimal inTransitArriving,
      int leadTimeDays,
      int horizonDays,
      LocalDate asOfDate) {
    return switch (urgency) {
      case NO_DATA ->
          "未登记销售速度：算不出日均销量、可支撑天数与预计断货日（有效可用量 "
              + plain(available)
              + "）。要判断这个 SKU 会不会断货，先登记它的销售速度";
      case LOW ->
          dailySales.compareTo(BigDecimal.ZERO) == 0
              ? "销售速度登记为日均 0 件（登记事实）：本 SKU 没有消耗，不会因销量断货；"
                  + "若这条登记已经过时，它会掩盖真实的断货"
              : "预计 " + expectedStockoutDate + " 断货（还可支撑 " + coverageDays
                  + " 天），超出备货周期 " + leadTimeDays + " 天与评估窗口 " + horizonDays + " 天";
      case MEDIUM ->
          coverageDays <= leadTimeDays
              ? "预计 " + expectedStockoutDate + " 断货（还可支撑 " + coverageDays
                  + " 天），断货日落在备货周期 " + leadTimeDays
                  + " 天内，但缺口量为 0 —— 备货周期内可到达在途 " + plain(inTransitArriving)
                  + " 足以顶上备货周期内的消耗（仍要盯在途是否准时到）"
              : "预计 " + expectedStockoutDate + " 断货（还可支撑 " + coverageDays
                  + " 天），落在评估窗口 " + horizonDays + " 天内，缺口量 " + plain(shortage);
      case HIGH ->
          "预计 " + expectedStockoutDate + " 断货（还可支撑 " + coverageDays + " 天），"
              + "断货日落在备货周期 " + leadTimeDays + " 天内，且缺口量 " + plain(shortage)
              + " —— 备货周期内可到达在途 " + plain(inTransitArriving) + " 顶不上，必须补货";
      case CRITICAL ->
          "有效可用量 " + plain(available) + " 不足一天的销量（日均 " + plain(dailySales)
              + "），" + asOfDate + " 就会断货（可支撑 0 天）";
    };
  }

  // ================================================================ 快照与清单（读）

  /** 快照列表（真分页）。 */
  public StockoutSnapshotPage pageSnapshots(int page, int size) {
    int pageNo = Math.max(0, page);
    int pageSize = pageSize(size);
    long total =
        sql.fetchOne("select count(*) from stockout_risk_snapshot where tenant_id = ?::uuid", currentTenant())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select id,snapshot_no,as_of_date,computed_at,lead_time_days,horizon_days,"
                + " item_count,critical_count,no_data_count,note,created_at"
                + " from stockout_risk_snapshot where tenant_id = ?::uuid"
                + " order by computed_at desc, id desc limit ? offset ?",
            currentTenant(),
            pageSize,
            (long) pageNo * pageSize);
    List<StockoutSnapshotRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(
          new StockoutSnapshotRow(
              row.get("id", UUID.class),
              row.get("snapshot_no", String.class),
              row.get("as_of_date", LocalDate.class),
              row.get("computed_at", OffsetDateTime.class),
              row.get("lead_time_days", Integer.class),
              row.get("horizon_days", Integer.class),
              row.get("item_count", Integer.class),
              row.get("critical_count", Integer.class),
              row.get("no_data_count", Integer.class),
              row.get("note", String.class),
              row.get("created_at", OffsetDateTime.class)));
    }
    return new StockoutSnapshotPage(items, total, pageNo, pageSize);
  }

  /** 最近一次快照。 */
  public UUID latestSnapshotId() {
    var row =
        sql.fetchOne(
            "select id from stockout_risk_snapshot where tenant_id = ?::uuid"
                + " order by computed_at desc, id desc limit 1",
            currentTenant());
    return row == null ? null : row.get(0, UUID.class);
  }

  /** 断货风险清单（真分页 + 真筛选 + 按紧急度排序）。 */
  public StockoutRiskItemPage pageItems(StockoutRiskItemQuery query) {
    int pageNo = Math.max(0, query.page());
    int pageSize = pageSize(query.size());
    UUID snapshotId = query.snapshotId() == null ? latestSnapshotId() : query.snapshotId();
    if (snapshotId == null) {
      return new StockoutRiskItemPage(List.of(), 0, pageNo, pageSize, null, null, null, 0, "");
    }
    var snapshot =
        sql.fetchOne(
            "select snapshot_no,as_of_date,lead_time_days from stockout_risk_snapshot"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            snapshotId);
    if (snapshot == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "断货风险快照不存在");
    }

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    where.add("i.tenant_id = ?::uuid");
    binds.add(currentTenant());
    where.add("i.snapshot_id = ?::uuid");
    binds.add(snapshotId);
    if (query.urgency() != null && !query.urgency().isBlank()) {
      where.add("i.urgency = ?::varchar");
      binds.add(query.urgency().trim());
    }
    if (query.skuCode() != null && !query.skuCode().isBlank()) {
      where.add("i.sku_code ilike ?");
      binds.add("%" + query.skuCode().trim() + "%");
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.add("(i.sku_code ilike ? or i.sku_name ilike ?)");
      binds.add("%" + query.keyword().trim() + "%");
      binds.add("%" + query.keyword().trim() + "%");
    }
    if (Boolean.TRUE.equals(query.onlyShortage())) {
      where.add("i.shortage_quantity > 0");
    }
    if (Boolean.TRUE.equals(query.onlyWithoutVelocity())) {
      where.add("i.daily_sales_quantity is null");
    }
    String clause = String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from stockout_risk_item i where " + clause, binds.toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select i.id,i.snapshot_id,i.sku_id,i.sku_code,i.sku_name,"
                + " i.on_hand_quantity,i.reserved_quantity,i.safety_buffer_quantity,i.available_quantity,"
                + " i.in_transit_quantity,i.in_transit_arriving_quantity,i.in_transit_no_eta_quantity,"
                + " i.daily_sales_quantity,i.coverage_days,i.expected_stockout_date,"
                + " i.replenishment_arrival_date,i.shortage_quantity,i.urgency,i.urgency_basis,"
                + " i.shop_count,i.sort_rank,"
                + " (select coalesce(string_agg(sh.shop_code, ',' order by sh.shop_code), '')"
                + "    from stockout_risk_item_shop sh"
                + "   where sh.tenant_id = i.tenant_id and sh.item_id = i.id) as shop_codes"
                + " from stockout_risk_item i where "
                + clause
                + " order by i.sort_rank, i.sku_code limit ? offset ?",
            append(binds, pageSize, (long) pageNo * pageSize).toArray());

    List<StockoutRiskItemRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(itemRow(row, snapshot.get("snapshot_no", String.class)));
    }
    String note =
        items.isEmpty()
            ? "这一页没有符合条件的 SKU —— 可能是筛选条件太窄，也可能是真的没有断货风险"
            : "";
    return new StockoutRiskItemPage(
        items,
        total,
        pageNo,
        pageSize,
        snapshotId,
        snapshot.get("snapshot_no", String.class),
        snapshot.get("as_of_date", LocalDate.class),
        snapshot.get("lead_time_days", Integer.class),
        note);
  }

  /** 风险行详情：结论 + 涉及店铺 + 计算过程。 */
  public StockoutRiskItemDetail itemDetail(UUID itemId) {
    var row =
        sql.fetchOne(
            "select i.id,i.snapshot_id,i.sku_id,i.sku_code,i.sku_name,"
                + " i.on_hand_quantity,i.reserved_quantity,i.safety_buffer_quantity,i.available_quantity,"
                + " i.in_transit_quantity,i.in_transit_arriving_quantity,i.in_transit_no_eta_quantity,"
                + " i.daily_sales_quantity,i.coverage_days,i.expected_stockout_date,"
                + " i.replenishment_arrival_date,i.shortage_quantity,i.urgency,i.urgency_basis,"
                + " i.shop_count,i.sort_rank,"
                + " (select coalesce(string_agg(sh.shop_code, ',' order by sh.shop_code), '')"
                + "    from stockout_risk_item_shop sh"
                + "   where sh.tenant_id = i.tenant_id and sh.item_id = i.id) as shop_codes"
                + " from stockout_risk_item i where i.tenant_id = ?::uuid and i.id = ?::uuid",
            currentTenant(),
            itemId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "断货风险条目不存在");
    }
    var snapshot =
        sql.fetchOne(
            "select snapshot_no from stockout_risk_snapshot where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            row.get("snapshot_id", UUID.class));
    List<StockoutShopRow> shops = new ArrayList<>();
    for (Record shop :
        sql.fetch(
            "select shop_code,listing_count from stockout_risk_item_shop"
                + " where tenant_id = ?::uuid and item_id = ?::uuid order by shop_code",
            currentTenant(),
            itemId)) {
      shops.add(new StockoutShopRow(shop.get("shop_code", String.class), shop.get("listing_count", Integer.class)));
    }
    List<StockoutDeductionRow> deductions = new ArrayList<>();
    for (Record deduction :
        sql.fetch(
            "select step_order,step_kind,quantity,basis,source_ref from stockout_risk_item_deduction"
                + " where tenant_id = ?::uuid and item_id = ?::uuid order by step_order",
            currentTenant(),
            itemId)) {
      deductions.add(
          new StockoutDeductionRow(
              deduction.get("step_order", Integer.class),
              deduction.get("step_kind", String.class),
              deductionLabel(deduction.get("step_kind", String.class)),
              deduction.get("quantity", BigDecimal.class),
              deduction.get("basis", String.class),
              deduction.get("source_ref", String.class)));
    }
    return new StockoutRiskItemDetail(
        itemRow(row, snapshot == null ? null : snapshot.get("snapshot_no", String.class)), shops, deductions);
  }

  private StockoutRiskItemRow itemRow(Record row, String snapshotNo) {
    String codes = row.get("shop_codes", String.class);
    List<String> shopCodes =
        codes == null || codes.isBlank()
            ? List.of()
            : List.of(codes.split(","));
    return new StockoutRiskItemRow(
        row.get("id", UUID.class),
        row.get("snapshot_id", UUID.class),
        snapshotNo,
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("sku_name", String.class),
        row.get("on_hand_quantity", BigDecimal.class),
        row.get("reserved_quantity", BigDecimal.class),
        row.get("safety_buffer_quantity", BigDecimal.class),
        row.get("available_quantity", BigDecimal.class),
        row.get("in_transit_quantity", BigDecimal.class),
        row.get("in_transit_arriving_quantity", BigDecimal.class),
        row.get("in_transit_no_eta_quantity", BigDecimal.class),
        row.get("daily_sales_quantity", BigDecimal.class),
        row.get("coverage_days", Integer.class),
        row.get("expected_stockout_date", LocalDate.class),
        row.get("replenishment_arrival_date", LocalDate.class),
        row.get("shortage_quantity", BigDecimal.class),
        row.get("urgency", String.class),
        urgencyLabel(row.get("urgency", String.class)),
        row.get("urgency_basis", String.class),
        row.get("shop_count", Integer.class),
        shopCodes,
        row.get("sort_rank", Integer.class));
  }

  private static String urgencyLabel(String urgency) {
    for (var value : StockoutUrgency.values()) {
      if (value.name().equals(urgency)) {
        return value.label();
      }
    }
    return urgency;
  }

  private static String deductionLabel(String kind) {
    for (var value : StockoutDeductionStep.values()) {
      if (value.name().equals(kind)) {
        return value.label();
      }
    }
    return kind;
  }

  // ================================================================ 内部工具

  /**
   * 当前租户（来自数据库里绑定的 {@code app.current_tenant}）。
   *
   * <p>刻意<b>不</b>从请求头或参数取租户：写入的 {@code tenant_id} 必须与 RLS 绑定的租户是同一个，
   * 否则会出现「写进了 A 租户的行，但 RLS 以为在 B 租户」这种夹生数据。
   */
  private UUID currentTenant() {
    var row =
        sql.fetchOne("select nullif(current_setting('app.current_tenant', true), '')::uuid as t");
    UUID tenant = row == null ? null : row.get("t", UUID.class);
    if (tenant == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    return tenant;
  }

  private static int pageSize(int size) {
    if (size <= 0) {
      return 50;
    }
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private static List<Object> append(List<Object> binds, Object... extra) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(extra));
    return all;
  }

  /** 数量口径：null 一律当 0，刻度对齐 numeric(19,6)。 */
  private static BigDecimal q(BigDecimal value) {
    return value == null ? ZERO : value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  /** 展示口径：去掉尾零。 */
  private static String plain(BigDecimal value) {
    return value == null ? "—" : value.stripTrailingZeros().toPlainString();
  }

  // ------------------------------------------------------------ 内部结构

  /** 销售速度事实（一次计算里的中间量）。 */
  private record VelocityFact(
      String skuCode,
      String skuName,
      BigDecimal dailySalesQuantity,
      LocalDate windowStart,
      LocalDate windowEnd,
      String sourceKind,
      String sourceRef) {}

  /** 涉及店铺事实。 */
  private record ShopFact(String shopCode, int listingCount) {}

  /** 计算过程的一步。 */
  private record Deduction(
      int stepOrder,
      StockoutDeductionStep stepKind,
      BigDecimal quantity,
      String basis,
      String sourceRef) {}

  /** 逐 SKU 的结论。 */
  private record RiskRow(
      UUID skuId,
      String skuCode,
      String skuName,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal safetyBuffer,
      BigDecimal available,
      BigDecimal inTransitTotal,
      BigDecimal inTransitArriving,
      BigDecimal inTransitNoEta,
      BigDecimal dailySales,
      Integer coverageDays,
      LocalDate expectedStockoutDate,
      LocalDate replenishmentArrivalDate,
      BigDecimal shortageQuantity,
      StockoutUrgency urgency,
      String urgencyBasis,
      List<Deduction> deductions) {}
}
