package com.starcon.warehouse;

import static com.starcon.warehouse.DistributionModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * 7.1.3.1 多仓分布视图：把同一 SKU（或 SPU）在所有仓库的库存并列成一张对照矩阵。
 *
 * <p>蓝图原文：「行=仓库，列=在手/预占/可用/在途/周转天数」，用途是「调拨决策与缺货判断」。
 *
 * <h2>本模块的三条硬纪律</h2>
 *
 * <ol>
 *   <li><b>自有账本量与平台观测量永不相加。</b>FBA 库存是平台仓观测值，不是自有账本 （蓝图原话，10 号卷同款要求）。所以 {@link DistributionRow}
 *       里两组列互斥有值， {@link DistributionTotals} 里两组合计也分开。合成一个「总库存」列看着方便， 但那个数没有任何一个来源背书 ——
 *       它既是账又是快照，出问题时无法判断该信谁。
 *   <li><b>在途不是「在某仓的在手」。</b>自有仓的在途取 {@code inventory_transfer} 中 {@code status='IN_TRANSIT'} 且
 *       {@code to_warehouse_id} 为该仓的部分 （货权已离开原仓、尚未入该仓），与该仓 {@code on_hand} 天然不重叠； 平台仓的在途是平台报出来的
 *       {@code observed_inbound}，与上面那批货不是同一批。
 *   <li><b>周转天数算不出就返回 null 并说明原因</b>，不用 0 也不用假数。
 * </ol>
 *
 * <h2>数量来源与读表选择</h2>
 *
 * <p>自有仓的四个数量列聚合自 {@code inventory_balance}（V1 注释：「权威可用量」）， <b>不是</b> {@code
 * stock_bucket}。原因：7.1.1.2 的桶层守恒允许一条 balance 还没有任何 bucket（「尚未拆到库位」是合法状态）。矩阵若只从 bucket 聚合，这类仓会在调拨决策里
 * 凭空消失 —— 那正是最不该消失的一类（明明有货、只是没拆货位）。 bucket 仍然被读，但只用于给出分解度（bucket / 库位 / 批次条数）与最近变动时间， 并以 {@code
 * bucketCount=0} 如实标出未分解状态。
 *
 * <p>行数 = 仓库数（有界，几十到几百），因此这里<b>不做分页</b>，排序在 Java 侧做。 分页一个长度等于仓库数的列表只会让「翻页后某仓不见了」看起来像丢数据。
 *
 * <p>本服务是 Spring 单例，因此**不持有任何请求态字段**：仓库档案缓存是 {@code distribution()} 内的局部变量。把它提成字段会在并发的两个租户请求之间串数据。
 */
@Service
public class InventoryDistributionService {

  /** 周转天数的回看窗口默认天数。 */
  private static final int DEFAULT_LOOKBACK_DAYS = 30;

  /** 回看窗口上下限。上限防止一次查询扫掉整本流水台账。 */
  private static final int MIN_LOOKBACK_DAYS = 1;

  private static final int MAX_LOOKBACK_DAYS = 180;

  /**
   * 单次 SPU 查询最多覆盖多少 SKU。
   *
   * <p>超限时**显式报错**而不是截断：截断会让用户以为看全了，实际少了一部分 SKU 的仓。
   */
  private static final int MAX_SPU_SKUS = 2000;

  /** 计入「出库」的业务类型。只有发货，调拨出库不算（见 outboundByWarehouse 的说明）。 */
  private static final List<String> OUTBOUND_KINDS = List.of("SHIPMENT");

  /** 平台仓的 kind 取值。 */
  private static final String PLATFORM = "PLATFORM_FBA";

  private final DSLContext sql;

  public InventoryDistributionService(DSLContext sql) {
    this.sql = sql;
  }

  private static String placeholders(int n) {
    return String.join(",", Collections.nCopies(n, "?"));
  }

  private static BigDecimal decimal(Record r, String field) {
    var v = r.get(field, BigDecimal.class);
    return v == null ? BigDecimal.ZERO : v;
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  /** 租户取自 RLS 会话变量（AccessService 在事务内设置），客户端头进不来也改不动。 */
  private UUID tenantId() {
    var v = sql.fetchOne("select current_setting('app.current_tenant',true) as t");
    String raw = v == null ? null : v.get("t", String.class);
    if (raw == null || raw.isBlank()) {
      throw new IllegalStateException("未绑定租户上下文：app.current_tenant 为空，拒绝执行查询");
    }
    return UUID.fromString(raw.trim());
  }

  // ================================================================ 矩阵

  /** 多仓对照矩阵。纯读视图：不写任何表。 */
  public MultiWarehouseDistribution distribution(DistributionQuery query) {
    validate(query);
    int lookback = lookbackDays(query.lookbackDays());
    OffsetDateTime to = OffsetDateTime.now();
    OffsetDateTime from = to.minusDays(lookback);

    List<UUID> skuIds = resolveSkuIds(query);
    UUID tenant = tenantId();

    Map<UUID, Totals3> own = ownLedgerRows(tenant, skuIds, query.legalEntityId());
    Map<UUID, BigDecimal> transit = inTransitByWarehouse(tenant, skuIds, query.legalEntityId());
    Map<UUID, BucketStats> buckets = bucketStats(tenant, skuIds);
    Map<UUID, BigDecimal> outbound =
        outboundByWarehouse(tenant, skuIds, query.legalEntityId(), from, to);
    Map<PlatformKey, PlatformQuantities> platform = platformLatest(tenant, skuIds);
    Map<UUID, Map<String, PlatformHistory>> history = platformHistory(tenant, skuIds);

    // 仓库档案一次取齐（自有侧与平台侧的仓都要），随方法栈走，不落字段。
    var warehouseIds = new ArrayList<UUID>();
    own.keySet().forEach(warehouseIds::add);
    platform.keySet().forEach(k -> warehouseIds.add(k.warehouseId()));
    Map<UUID, WarehouseInfo> warehouses = loadWarehouses(tenant, warehouseIds);

    var rows = new ArrayList<DistributionRow>();
    var totals = new Totals();

    // 自有仓行：准入条件是「有在手 / 有预占 / 有在途」。三者都为 0 的仓与本 SKU 无关，
    // 列出来只会稀释矩阵 —— 但**零库存的仓要留**（缺货判断正是本模块的用途之一）。
    for (var e : own.entrySet()) {
      BigDecimal onHand = nz(e.getValue().onHand());
      BigDecimal reserved = nz(e.getValue().reserved());
      BigDecimal available = nz(e.getValue().available());
      BigDecimal inTransit = transit.getOrDefault(e.getKey(), BigDecimal.ZERO);
      if (onHand.signum() == 0 && reserved.signum() == 0 && inTransit.signum() == 0) {
        continue;
      }
      WarehouseInfo w = warehouses.get(e.getKey());
      if (w == null) {
        continue;
      }
      BucketStats stats = buckets.get(e.getKey());
      Turnover turnover = ownTurnover(onHand, outbound.get(e.getKey()), lookback);
      var row =
          new DistributionRow(
              w.id(),
              w.code(),
              w.name(),
              w.kind(),
              w.countryCode(),
              w.timezone(),
              onHand,
              reserved,
              available,
              inTransit,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              turnover.days(),
              turnover.computable(),
              turnover.basis(),
              turnover.note(),
              stats == null ? 0 : stats.buckets(),
              stats == null ? 0 : stats.locations(),
              stats == null ? 0 : stats.lots(),
              stats == null ? null : stats.lastMovementAt());
      rows.add(row);
      totals.addOwn(row);
    }

    // 平台仓行：一行 = 一个（仓库, 观测来源）。
    //
    // 刻意不把同一仓的多个来源加在一起：两个来源各报一个「这个仓有多少」，
    // 加总出来的数没有任何一个来源背书，真出现分歧时无法判断该信谁。
    // 因此分行展示，让冲突暴露而不是被求和掩盖。
    for (var e : platform.entrySet()) {
      PlatformKey key = e.getKey();
      WarehouseInfo w = warehouses.get(key.warehouseId());
      if (w == null) {
        continue;
      }
      PlatformQuantities q = e.getValue();
      Turnover turnover = platformTurnover(key, q, history.get(key.warehouseId()));
      var row =
          new DistributionRow(
              w.id(),
              w.code(),
              w.name(),
              w.kind(),
              w.countryCode(),
              w.timezone(),
              null,
              null,
              null,
              null,
              q.onHand(),
              q.reserved(),
              q.available(),
              q.inbound(),
              key.source(),
              q.collectedAt(),
              q.collectedAt() == null
                  ? null
                  : Duration.between(q.collectedAt(), OffsetDateTime.now()).getSeconds(),
              turnover.days(),
              turnover.computable(),
              turnover.basis(),
              turnover.note(),
              0,
              0,
              0,
              null);
      rows.add(row);
      totals.addPlatform(row);
    }

    rows.sort(
        (a, b) -> {
          // 自有仓在前、平台仓在后：平台量不是账本量，混排会让「按量排序」变成
          // 按两种不可比的量排序。组内各按本组的主数量倒序，恒定追加 code 做决胜键。
          int group =
              Boolean.compare(
                  PLATFORM.equals(a.warehouseKind()), PLATFORM.equals(b.warehouseKind()));
          if (group != 0) {
            return group;
          }
          int byQty = primary(b).compareTo(primary(a));
          return byQty != 0 ? byQty : a.warehouseCode().compareTo(b.warehouseCode());
        });

    return new MultiWarehouseDistribution(
        subject(query),
        new DistributionWindow(lookback, OUTBOUND_KINDS, from, to),
        totals.build(),
        rows,
        new DistributionSkuScope(skuIds.size(), unregisteredSkuCount(tenant)),
        actions(query));
  }

  private void validate(DistributionQuery query) {
    if (query == null) {
      throw new IllegalArgumentException("查询条件不能为空");
    }
    boolean hasSku = query.skuId() != null;
    boolean hasSpu = query.spuId() != null;
    if (!hasSku && !hasSpu) {
      throw new IllegalArgumentException("必须指定 skuId 或 spuId 之一：矩阵按「SKU 或 SPU」查看，不提供全租户视图");
    }
    if (hasSku && hasSpu) {
      throw new IllegalArgumentException("skuId 与 spuId 只能给一个：同时给会让「是这两个 SKU 还是这个 SPU」产生歧义");
    }
  }

  private static int lookbackDays(Integer requested) {
    if (requested == null) {
      return DEFAULT_LOOKBACK_DAYS;
    }
    if (requested < MIN_LOOKBACK_DAYS || requested > MAX_LOOKBACK_DAYS) {
      throw new IllegalArgumentException(
          "lookbackDays 需在 " + MIN_LOOKBACK_DAYS + "–" + MAX_LOOKBACK_DAYS + " 之间：" + requested);
    }
    return requested;
  }

  /** 排序用的主数量：自有仓读可用，平台仓读平台可售。两侧各自内部排序，不跨侧比较。 */
  private static BigDecimal primary(DistributionRow r) {
    return PLATFORM.equals(r.warehouseKind()) ? nz(r.platformAvailable()) : nz(r.available());
  }

  /**
   * 本租户登记过 SPU 的 SPU 列表（矩阵的 SPU 下拉）。
   *
   * <p>返回的是 {@code sku_ref.spu_id} 上的登记值，不是 catalog 的 SPU 档案 —— 权威在 catalog， 这里只是把"我这边哪些 SKU 挂在哪个
   * SPU 下"摆出来供选择。
   *
   * <p>没有登记 SPU 的 SKU 不会出现在这里，那正是 {@code unregisteredSpuCount} 要报的事。
   */
  public List<DistributionSpuRef> spus() {
    var rows =
        sql.fetch(
            "select spu_id,count(*) as sku_count from sku_ref"
                + " where tenant_id=? and spu_id is not null group by spu_id order by spu_id",
            tenantId());
    return rows.stream()
        .map(
            r ->
                new DistributionSpuRef(r.get("spu_id", UUID.class), r.get("sku_count", Long.class)))
        .toList();
  }

  // ================================================================ SKU 口径

  /**
   * 解析本次查询覆盖的 SKU 集合。
   *
   * <p>{@code sku_ref.spu_id} 是 V55 新增的**引用登记**列（catalog 才是 SPU 归属的权威）。 存量 SKU 没有这一列，所以按 SPU
   * 查时天然覆盖不到它们 —— 这一点由 {@link DistributionSkuScope#unregisteredSpuCount()} 如实报出，而不是让筛选看起来是完整的。
   */
  private List<UUID> resolveSkuIds(DistributionQuery query) {
    UUID tenant = tenantId();
    if (query.skuId() != null) {
      // 精确到 SKU 时**不校验存在性**：跨租户的 skuId 在 RLS 下查不到，
      // 直接返回空矩阵才是正确行为；报 404 反而会泄露「这个 id 在别的租户存在」。
      return List.of(query.skuId());
    }
    var rows =
        sql.fetch("select id from sku_ref where tenant_id=? and spu_id=?", tenant, query.spuId());
    if (rows.size() > MAX_SPU_SKUS) {
      throw new IllegalArgumentException(
          "该 SPU 下有 "
              + rows.size()
              + " 个 SKU，超过单次矩阵查询上限 "
              + MAX_SPU_SKUS
              + "：已显式拒绝而不是截断，否则你会看到一部分 SKU 的仓却以为看全了");
    }
    var ids = new ArrayList<UUID>();
    rows.forEach(r -> ids.add(r.get("id", UUID.class)));
    return ids;
  }

  /** 本租户内有库存余额、但没有登记 spu_id 的 SKU 数：SPU 口径覆盖不到的存量部分。 */
  private long unregisteredSkuCount(UUID tenant) {
    var r =
        sql.fetchOne(
            "select count(distinct b.sku_id) as n from inventory_balance b"
                + " join sku_ref s on s.tenant_id=b.tenant_id and s.id=b.sku_id"
                + " where b.tenant_id=? and s.spu_id is null and (b.on_hand<>0 or b.reserved<>0)",
            tenant);
    return r == null ? 0 : r.get("n", Long.class);
  }

  private DistributionSubject subject(DistributionQuery query) {
    if (query.skuId() != null) {
      var r =
          sql.fetchOne(
              "select code,name,spu_id from sku_ref where tenant_id=? and id=?",
              tenantId(),
              query.skuId());
      return new DistributionSubject(
          query.skuId(),
          r == null ? null : r.get("code", String.class),
          r == null ? null : r.get("name", String.class),
          r == null ? null : r.get("spu_id", UUID.class));
    }
    // SPU 口径：主体是 SPU 本身；SKU 清单由 matchedSkuCount 与各行聚合表达。
    return new DistributionSubject(null, null, null, query.spuId());
  }

  // ================================================================ 仓库参照

  private record WarehouseInfo(
      UUID id, String code, String name, String kind, String countryCode, String timezone) {}

  /**
   * 取仓库档案。ARCHIVED 的仓不列入矩阵 —— 它已停用，把它算进「这批货在几个仓」会虚增分布面。
   *
   * <p>按 id 列表取而不是全租户取：矩阵只需要本次命中的这几个仓。
   */
  private Map<UUID, WarehouseInfo> loadWarehouses(UUID tenant, List<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    var unique = new ArrayList<>(new java.util.LinkedHashSet<>(ids));
    var binds = new ArrayList<Object>();
    binds.add(tenant);
    binds.addAll(unique);
    var rows =
        sql.fetch(
            "select id,code,name,kind,country_code,timezone from warehouse"
                + " where tenant_id=? and status<>'ARCHIVED' and id in ("
                + placeholders(unique.size())
                + ")",
            binds.toArray());
    var out = new LinkedHashMap<UUID, WarehouseInfo>();
    rows.forEach(
        r ->
            out.put(
                r.get("id", UUID.class),
                new WarehouseInfo(
                    r.get("id", UUID.class),
                    r.get("code", String.class),
                    r.get("name", String.class),
                    r.get("kind", String.class),
                    r.get("country_code", String.class),
                    r.get("timezone", String.class))));
    return out;
  }

  // ================================================================ 自有账本

  private record Totals3(BigDecimal onHand, BigDecimal reserved, BigDecimal available) {}

  private Map<UUID, Totals3> ownLedgerRows(UUID tenant, List<UUID> skuIds, UUID legalEntityId) {
    if (skuIds.isEmpty()) {
      return Map.of();
    }
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("b.tenant_id=?");
    where.add("b.sku_id in (" + placeholders(skuIds.size()) + ")");
    if (legalEntityId != null) {
      where.add("b.legal_entity_id=?");
    }
    binds.add(tenant);
    binds.addAll(skuIds);
    if (legalEntityId != null) {
      binds.add(legalEntityId);
    }
    var rows =
        sql.fetch(
            "select b.warehouse_id,sum(b.on_hand) as on_hand,sum(b.reserved) as reserved,"
                + "sum(b.available) as available from inventory_balance b where "
                + String.join(" and ", where)
                + " group by b.warehouse_id",
            binds.toArray());
    var out = new LinkedHashMap<UUID, Totals3>();
    rows.forEach(
        r ->
            out.put(
                r.get("warehouse_id", UUID.class),
                new Totals3(
                    decimal(r, "on_hand"), decimal(r, "reserved"), decimal(r, "available"))));
    return out;
  }

  /**
   * 在途按**收货仓**归属，与 7.1.1.1 同一口径：已从发货仓出库、尚未在收货仓入库的部分。
   *
   * <p>因此在途与该仓 {@code on_hand} 天然不重叠（货还没到，不在收货仓的在手里）。
   */
  private Map<UUID, BigDecimal> inTransitByWarehouse(
      UUID tenant, List<UUID> skuIds, UUID legalEntityId) {
    if (skuIds.isEmpty()) {
      return Map.of();
    }
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("t.tenant_id=?");
    where.add("t.status='IN_TRANSIT'");
    where.add("t.sku_id in (" + placeholders(skuIds.size()) + ")");
    if (legalEntityId != null) {
      where.add("t.legal_entity_id=?");
    }
    binds.add(tenant);
    binds.addAll(skuIds);
    if (legalEntityId != null) {
      binds.add(legalEntityId);
    }
    var rows =
        sql.fetch(
            "select t.to_warehouse_id,sum(t.quantity) as in_transit from inventory_transfer t where "
                + String.join(" and ", where)
                + " group by t.to_warehouse_id",
            binds.toArray());
    var out = new LinkedHashMap<UUID, BigDecimal>();
    rows.forEach(r -> out.put(r.get("to_warehouse_id", UUID.class), decimal(r, "in_transit")));
    return out;
  }

  private record BucketStats(
      long buckets, long locations, long lots, OffsetDateTime lastMovementAt) {}

  /** bucket 分解度统计。只用于说明「拆得有多细」，不参与任何数量合计。 */
  private Map<UUID, BucketStats> bucketStats(UUID tenant, List<UUID> skuIds) {
    if (skuIds.isEmpty()) {
      return Map.of();
    }
    var binds = new ArrayList<Object>();
    binds.add(tenant);
    binds.addAll(skuIds);
    var rows =
        sql.fetch(
            "select k.warehouse_id,count(*) as buckets,count(distinct k.location_id) as locations,"
                + "count(distinct k.lot_id) as lots,max(k.last_movement_at) as last_movement"
                + " from stock_bucket k where k.tenant_id=? and k.sku_id in ("
                + placeholders(skuIds.size())
                + ") group by k.warehouse_id",
            binds.toArray());
    var out = new LinkedHashMap<UUID, BucketStats>();
    rows.forEach(
        r ->
            out.put(
                r.get("warehouse_id", UUID.class),
                new BucketStats(
                    r.get("buckets", Long.class),
                    r.get("locations", Long.class),
                    r.get("lots", Long.class),
                    r.get("last_movement", OffsetDateTime.class))));
    return out;
  }

  /**
   * 回看窗口内的自有出库量按仓聚合 —— 周转天数的分母来源。
   *
   * <p>只取 {@code kind='SHIPMENT'}：调拨出库（TRANSFER_OUT）不是消耗，把货从一个自有仓 挪到另一个自有仓不该让这个仓看起来「卖得快」。而本模块的下游正是
   * 7.5.1.1 调拨， 它要拿周转天数决定往哪调 —— 把调拨混进出库速度，会让调拨决策把自己的依据算错。
   *
   * <p>两个易错点都在这里被显式避开：
   *
   * <ul>
   *   <li>{@code ?::timestamptz} 的位置排在 SKU 列表绑定**之前**。cast 落在绑定序列末位会让 jOOQ 整体绑定错位一格（历史踩过的坑）。
   *   <li>绑定值用 {@code OffsetDateTime}，不用 {@code Timestamp.valueOf(LocalDateTime)} —— 后者按 JVM
   *       默认时区静默偏 8 小时，而 {@code timestamptz} 存的是带偏移的时刻。
   * </ul>
   */
  private Map<UUID, BigDecimal> outboundByWarehouse(
      UUID tenant, List<UUID> skuIds, UUID legalEntityId, OffsetDateTime from, OffsetDateTime to) {
    if (skuIds.isEmpty()) {
      return Map.of();
    }
    var where = new ArrayList<String>();
    where.add("e.tenant_id=?");
    where.add("e.on_hand_delta<0");
    where.add("t.kind='SHIPMENT'");
    where.add("t.occurred_at>=?::timestamptz");
    where.add("t.occurred_at<?::timestamptz");
    where.add("b.sku_id in (" + placeholders(skuIds.size()) + ")");
    if (legalEntityId != null) {
      where.add("b.legal_entity_id=?");
    }
    var binds = new ArrayList<Object>();
    binds.add(tenant);
    binds.add(from);
    binds.add(to);
    binds.addAll(skuIds);
    if (legalEntityId != null) {
      binds.add(legalEntityId);
    }
    var rows =
        sql.fetch(
            "select b.warehouse_id,-sum(e.on_hand_delta) as outbound from inventory_entry e"
                + " join inventory_transaction t on t.tenant_id=e.tenant_id and t.id=e.transaction_id"
                + " join inventory_balance b on b.tenant_id=e.tenant_id and b.id=e.balance_id where "
                + String.join(" and ", where)
                + " group by b.warehouse_id",
            binds.toArray());
    var out = new LinkedHashMap<UUID, BigDecimal>();
    rows.forEach(r -> out.put(r.get("warehouse_id", UUID.class), decimal(r, "outbound")));
    return out;
  }

  // ================================================================ 平台观测

  /** 平台观测的取数键：一个仓 + 一个观测来源。 */
  private record PlatformKey(UUID warehouseId, String source) {}

  private record PlatformQuantities(
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      BigDecimal inbound,
      OffsetDateTime collectedAt,
      Map<UUID, BigDecimal> perSkuOnHand) {}

  /**
   * 每个（仓, SKU, 来源）的**最新一条**观测。
   *
   * <p>观测表 append-only（历史保留供断货风险回溯），矩阵取当前值用 {@code distinct on ... order by collected_at desc}。
   *
   * <p>同一（仓, 来源）的多个 SKU 相加：它们都是同一来源的同类观测量，加总是同质的。 {@code collectedAt} 取各 SKU 中**最旧**的一条 ——
   * 整体新鲜度由最不鲜的那部分决定。
   */
  private Map<PlatformKey, PlatformQuantities> platformLatest(UUID tenant, List<UUID> skuIds) {
    if (skuIds.isEmpty()) {
      return Map.of();
    }
    var binds = new ArrayList<Object>();
    binds.add(tenant);
    binds.addAll(skuIds);
    var rows =
        sql.fetch(
            "with latest as (select distinct on (warehouse_id,sku_id,source)"
                + " warehouse_id,sku_id,source,collected_at,observed_on_hand,observed_reserved,"
                + " observed_available,observed_inbound from platform_stock_observation"
                + " where tenant_id=? and sku_id in ("
                + placeholders(skuIds.size())
                + ") order by warehouse_id,sku_id,source,collected_at desc) select * from latest",
            binds.toArray());

    var grouped = new LinkedHashMap<PlatformKey, PlatformQuantities>();
    for (Record r : rows) {
      var key = new PlatformKey(r.get("warehouse_id", UUID.class), r.get("source", String.class));
      BigDecimal onHand = decimal(r, "observed_on_hand");
      OffsetDateTime at = r.get("collected_at", OffsetDateTime.class);
      PlatformQuantities existing = grouped.get(key);
      if (existing == null) {
        grouped.put(
            key,
            new PlatformQuantities(
                onHand,
                decimal(r, "observed_reserved"),
                decimal(r, "observed_available"),
                decimal(r, "observed_inbound"),
                at,
                new LinkedHashMap<>(Map.of(r.get("sku_id", UUID.class), onHand))));
        continue;
      }
      var perSku = new LinkedHashMap<>(existing.perSkuOnHand());
      perSku.put(r.get("sku_id", UUID.class), onHand);
      grouped.put(
          key,
          new PlatformQuantities(
              existing.onHand().add(onHand),
              existing.reserved().add(decimal(r, "observed_reserved")),
              existing.available().add(decimal(r, "observed_available")),
              existing.inbound().add(decimal(r, "observed_inbound")),
              older(existing.collectedAt(), at),
              perSku));
    }
    return grouped;
  }

  private static OffsetDateTime older(OffsetDateTime a, OffsetDateTime b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return b.isBefore(a) ? b : a;
  }

  private record PlatformHistory(
      long samples, OffsetDateTime firstAt, OffsetDateTime lastAt, BigDecimal peakOnHand) {}

  /** 观测历史统计：样本数、首末采集时刻、峰值在手。平台周转天数靠它做差分。 */
  private Map<UUID, Map<String, PlatformHistory>> platformHistory(UUID tenant, List<UUID> skuIds) {
    if (skuIds.isEmpty()) {
      return Map.of();
    }
    var binds = new ArrayList<Object>();
    binds.add(tenant);
    binds.addAll(skuIds);
    var rows =
        sql.fetch(
            "select warehouse_id,sku_id,source,count(*) as samples,"
                + "min(collected_at) as first_at,max(collected_at) as last_at,"
                + "max(observed_on_hand) as peak from platform_stock_observation"
                + " where tenant_id=? and sku_id in ("
                + placeholders(skuIds.size())
                + ") group by warehouse_id,sku_id,source",
            binds.toArray());
    var out = new LinkedHashMap<UUID, Map<String, PlatformHistory>>();
    rows.forEach(
        r ->
            out.computeIfAbsent(r.get("warehouse_id", UUID.class), k -> new LinkedHashMap<>())
                .put(
                    r.get("source", String.class),
                    new PlatformHistory(
                        r.get("samples", Long.class),
                        r.get("first_at", OffsetDateTime.class),
                        r.get("last_at", OffsetDateTime.class),
                        decimal(r, "peak"))));
    return out;
  }

  // ================================================================ 周转天数

  /** 周转天数的计算结果与依据。days 为 null 时 computable 必为 false。 */
  private record Turnover(BigDecimal days, boolean computable, String basis, String note) {}

  /**
   * 自有仓周转天数 = 在手 ÷ 日均出库速度。
   *
   * <p>出库速度来自真实的 {@code inventory_entry} 发货分录（回看窗口内 Σ 负向 on_hand_delta）， 不是任何估计值。出库量为 0
   * 时**不返回天数**：此时真实结论是「没有出库速度」， 0 会被读成「瞬间卖完」，无穷大没人看得懂。
   */
  private Turnover ownTurnover(BigDecimal onHand, BigDecimal outbound, int lookback) {
    if (outbound == null || outbound.signum() <= 0) {
      return new Turnover(
          null,
          false,
          "NONE",
          "回看 "
              + lookback
              + " 天内该仓该 SKU 没有发货（SHIPMENT）出库记录，出库速度为 0，周转天数无法计算"
              + "（返回 0 会被读成「瞬间卖完」，所以这里留空并说明）");
    }
    BigDecimal daily = outbound.divide(BigDecimal.valueOf(lookback), 8, RoundingMode.HALF_UP);
    BigDecimal days = onHand.divide(daily, 1, RoundingMode.HALF_UP);
    return new Turnover(
        days,
        true,
        "OWN_OUTBOUND",
        "在手 ÷（近 "
            + lookback
            + " 天发货出库 "
            + outbound.stripTrailingZeros().toPlainString()
            + " ÷ "
            + lookback
            + "）；调拨出库不计入，因为它不是消耗");
  }

  /**
   * 平台仓周转天数 = 最新观测在手 ÷ 日均减少速度（观测快照差分）。
   *
   * <p>平台仓没有自有出库流水，只能用观测序列的**净减少**近似消耗速度。这与自有仓口径不同， 因此 {@code basis} 明确标成 {@code
   * PLATFORM_OBSERVATION_DIFF}，界面上必须与自有仓的行 分开解读 —— 两个口径的数不能直接比大小。
   *
   * <p>需要至少两条观测、跨度大于 0、且净减少为正；任何一条不满足就留空并说明原因。
   */
  private Turnover platformTurnover(
      PlatformKey key, PlatformQuantities quantities, Map<String, PlatformHistory> bySource) {
    PlatformHistory stats = bySource == null ? null : bySource.get(key.source());
    if (stats == null || stats.samples() < 2 || stats.firstAt() == null || stats.lastAt() == null) {
      return new Turnover(null, false, "NONE", "该仓该来源的观测样本少于 2 条或缺少采集时间，无法做快照差分，周转天数不可计算");
    }
    BigDecimal depletion = BigDecimal.ZERO;
    for (BigDecimal latest : quantities.perSkuOnHand().values()) {
      BigDecimal drop = stats.peakOnHand().subtract(latest);
      if (drop.signum() > 0) {
        depletion = depletion.add(drop);
      }
    }
    if (depletion.signum() <= 0) {
      return new Turnover(null, false, "NONE", "该仓该来源的观测序列里在手量没有净减少（可能是补货或调仓），无法据此估算周转天数");
    }
    long spanSeconds = Duration.between(stats.firstAt(), stats.lastAt()).getSeconds();
    if (spanSeconds <= 0) {
      return new Turnover(null, false, "NONE", "该仓该来源的多条观测采集时间相同，无法做差分，周转天数不可计算");
    }
    BigDecimal spanDays =
        BigDecimal.valueOf(spanSeconds).divide(BigDecimal.valueOf(86400), 8, RoundingMode.HALF_UP);
    BigDecimal daily = depletion.divide(spanDays, 8, RoundingMode.HALF_UP);
    BigDecimal days = quantities.onHand().divide(daily, 1, RoundingMode.HALF_UP);
    String skuNote =
        quantities.perSkuOnHand().size() > 1
            ? "（按该 SPU 下 " + quantities.perSkuOnHand().size() + " 个 SKU 的观测合计）"
            : "";
    return new Turnover(
        days,
        true,
        "PLATFORM_OBSERVATION_DIFF",
        "观测快照差分：跨 "
            + stats.firstAt().toLocalDate()
            + "–"
            + stats.lastAt().toLocalDate()
            + " 共 "
            + stats.samples()
            + " 条观测，在手净减 "
            + depletion.stripTrailingZeros().toPlainString()
            + skuNote
            + "。这是**平台观测口径**，与自有仓的发货速度口径不可直接比较");
  }

  // ================================================================ 合计

  /** 矩阵合计：自有账本与平台观测两组分开累加，两者永不相加。 */
  private static final class Totals {
    private BigDecimal ownOnHand = BigDecimal.ZERO;
    private BigDecimal ownReserved = BigDecimal.ZERO;
    private BigDecimal ownAvailable = BigDecimal.ZERO;
    private BigDecimal ownInTransit = BigDecimal.ZERO;
    private int ownWarehouses;
    private int ownZeroWarehouses;
    private BigDecimal platformOnHand = BigDecimal.ZERO;
    private BigDecimal platformReserved = BigDecimal.ZERO;
    private BigDecimal platformAvailable = BigDecimal.ZERO;
    private BigDecimal platformInbound = BigDecimal.ZERO;
    private int platformWarehouses;
    private OffsetDateTime earliestCollectedAt;

    void addOwn(DistributionRow r) {
      ownOnHand = ownOnHand.add(r.onHand());
      ownReserved = ownReserved.add(r.reserved());
      ownAvailable = ownAvailable.add(r.available());
      ownInTransit = ownInTransit.add(r.inTransit());
      ownWarehouses++;
      if (r.available().signum() <= 0) {
        ownZeroWarehouses++;
      }
    }

    void addPlatform(DistributionRow r) {
      platformOnHand = platformOnHand.add(r.platformOnHand());
      platformReserved = platformReserved.add(r.platformReserved());
      platformAvailable = platformAvailable.add(r.platformAvailable());
      platformInbound = platformInbound.add(r.platformInbound());
      platformWarehouses++;
      if (earliestCollectedAt == null || r.collectedAt().isBefore(earliestCollectedAt)) {
        earliestCollectedAt = r.collectedAt();
      }
    }

    DistributionTotals build() {
      return new DistributionTotals(
          ownOnHand,
          ownReserved,
          ownAvailable,
          ownInTransit,
          ownWarehouses,
          ownZeroWarehouses,
          platformOnHand,
          platformReserved,
          platformAvailable,
          platformInbound,
          platformWarehouses,
          earliestCollectedAt);
    }
  }

  // ================================================================ 下游入口

  /**
   * 下游操作入口。
   *
   * <p>与 7.1.1.2 同一套语义：{@code available=false} 时 href 仍是**路由契约**，
   * 说明这个操作落在哪个模块、带哪些定位维度。调拨（7.5.1.1）尚未交付，只有它不可用； 下钻明细（7.1.1.2）与流水（7.1.2.1）都已交付，如实标为可用 ——
   * 把已交付的入口也标成"未交付"，就是在页面上说一个假状态。
   */
  private List<DistributionAction> actions(DistributionQuery query) {
    String drill = query.skuId() != null ? "skuId={skuId}" : "spuId={spuId}";
    return List.of(
        new DistributionAction(
            "TRANSFER",
            "发起调拨",
            "/inventory/transfer?" + drill + "&warehouseId={warehouseId}",
            false,
            "7.5.1.1",
            "目标模块 7.5.1.1 调拨尚未交付，入口契约已就位但暂不可用"),
        new DistributionAction(
            "BUCKET",
            "查看库存明细",
            "/inventory?" + drill + "&warehouseId={warehouseId}",
            true,
            "7.1.1.2",
            "7.1.1.2 已交付，可下钻到该仓的 bucket 明细"),
        new DistributionAction(
            "LEDGER",
            "查看库存流水",
            "/inventory/ledger?" + drill + "&warehouseId={warehouseId}",
            true,
            "7.1.2.1",
            "7.1.2.1 已交付，可查看该仓该 SKU 的流水台账"));
  }

  // ================================================================ 平台观测写入

  /**
   * 记录一条平台仓观测。
   *
   * <p>这是矩阵里平台行数量的**唯一**来源。它是显式的写入面而不是 mock 数据源： 10 号卷的采集器交付后会调它；在此之前它也可被人工录入，于是页面上的平台数量
   * 永远来自真实落库的行，而不是代码里的常量。
   *
   * <p>不覆盖历史：同一来源同一时点重复投递会撞唯一约束（让调用方知道重试没生效）， 不同时点则各存一条 —— 序列本身就是断货风险分析的依据。
   */
  public PlatformObservationSaved recordObservation(PlatformObservationInput input) {
    if (input == null) {
      throw new IllegalArgumentException("观测内容不能为空");
    }
    if (input.warehouseId() == null || input.skuId() == null || input.legalEntityId() == null) {
      throw new IllegalArgumentException("warehouseId、skuId、legalEntityId 均不能为空");
    }
    if (input.source() == null || input.source().isBlank()) {
      throw new IllegalArgumentException("source 不能为空：平台仓数量必须能标注来源");
    }
    if (input.collectedAt() == null) {
      throw new IllegalArgumentException("collectedAt 不能为空：平台仓数量必须能标注采集时间");
    }
    requireNonNegative(input.observedOnHand(), "observedOnHand");
    requireNonNegative(input.observedReserved(), "observedReserved");
    requireNonNegative(input.observedAvailable(), "observedAvailable");
    requireNonNegative(input.observedInbound(), "observedInbound");

    UUID tenant = tenantId();
    String source = input.source().trim().toUpperCase(Locale.ROOT);
    UUID id = UUID.randomUUID();

    // cast 不在绑定序列末位：后面还有 5 个绑定，位置安全。
    sql.execute(
        "insert into platform_stock_observation(id,tenant_id,warehouse_id,sku_id,legal_entity_id,"
            + "source,collected_at,observed_on_hand,observed_reserved,observed_available,"
            + "observed_inbound,raw_reference)"
            + " values(?,?,?,?,?,?,?::timestamptz,?,?,?,?,?)",
        id,
        tenant,
        input.warehouseId(),
        input.skuId(),
        input.legalEntityId(),
        source,
        input.collectedAt(),
        nz(input.observedOnHand()),
        nz(input.observedReserved()),
        nz(input.observedAvailable()),
        nz(input.observedInbound()),
        input.rawReference() == null ? "" : input.rawReference());

    var w =
        sql.fetchOne(
            "select kind from warehouse where tenant_id=? and id=?", tenant, input.warehouseId());
    return new PlatformObservationSaved(
        id,
        input.warehouseId(),
        input.skuId(),
        w == null ? null : w.get("kind", String.class),
        source,
        input.collectedAt(),
        OffsetDateTime.now());
  }

  private static void requireNonNegative(BigDecimal value, String field) {
    if (value != null && value.signum() < 0) {
      throw new IllegalArgumentException(field + " 不能为负：" + value.toPlainString());
    }
  }
}
