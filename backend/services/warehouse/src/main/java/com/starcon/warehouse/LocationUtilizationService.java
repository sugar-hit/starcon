package com.starcon.warehouse;

import static com.starcon.warehouse.LocationUtilizationModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 8.5.2.1 库位利用率。
 *
 * <p>蓝图：「分析库位占用：容量利用率、周转率、空置库位、爆满库位、低效库位，给出调整建议。 输入：库位与库存占用；出库频次。输出：利用率报表 + 热力图 + 建议。」以及关系段： 「上游 =
 * 8.1.2.1；下游 = 8.1.2.2 自动分配优化。库位热力数据同时优化拣货路径（高频商品放近出口）。」
 *
 * <p><b>本模块不建表。</b>三样输入全在既有事实表里：容量在 {@code warehouse_location} （8.1.2.1 的 V105 补的列）、占用在 {@code
 * stock_bucket}、出库频次在 {@code inventory_transaction} + {@code inventory_entry}（V1 建的 append-only
 * 事实）。 建议是<b>读时派生</b>的 —— 存一张「建议表」只会让建议比它描述的库存更先过期。 所以 V109 这个版本号<b>刻意留空</b>，不为了「有一张自己的表」而建。
 *
 * <p><b>为什么出库频次要摊分，以及摊分为什么不会重复计数。</b> {@code inventory_entry} 是 <b>balance 粒度</b>（仓库 × SKU × 法人 ×
 * 质量状态）， 表上没有 {@code location_id} —— 「这一件是从哪个库位拣走的」这件事，**系统里没有任何 一张表在记**。所以出库量只能在 <b>SKU
 * 粒度</b>是真的，库位粒度只能摊分：
 *
 * <pre>
 *   库位出库量 = Σ_SKU( 该 SKU 本仓窗口内出库量 × 该库位占该 SKU 全仓在手量的比例 )
 * </pre>
 *
 * 这个口径的关键性质是<b>可加</b>：同一个 SKU 在三个库位上的比例加起来恒为 1，所以 {@code Σ库位出库量 == 仓库真实出库量}，既不重复计数也不漏计（与
 * 8.1.1.1「平台仓观测值 不相加」是同一个坑的反面 —— 那里是会重复，这里必须配平）。比例的分母用 {@code nullif(...)} 兜住：SKU 全仓在手为 0 时分母是 0，那
 * 0/0 交回 SQL 得 NULL， 外层再 {@code coalesce} 成 0 —— <b>不</b>让它变成除零错误，也<b>不</b>编一个比例。
 *
 * <p><b>三处分母边界</b>（全部在 {@link #gradeOf} 与 {@link #utilizationOf} 里显式处理， 且都有测试钉住）：容量未量、容量为 0、在手为
 * 0。三处都给 null 而不是 0 或 100%， 理由与 {@link LocationUtilizationModels} 的文件头一致。
 *
 * <p><b>每条聚合各是一个独立查询 / 标量子查询，不 join 展开。</b>库位 × SKU × 出库流水 三张表两两 join 会成笛卡尔积，同一个库位的在手数会被流水条数放大 N 次
 * —— 那是 本仓库反复出现过的真实事故形状（见 8.1.1.1 / 8.1.2.1 的文件头）。
 *
 * <p><b>租户谓词每条查询都写。</b>RLS（{@code app.current_tenant}）是第一道防线， 显式 {@code tenant_id=}
 * 是第二道：忘绑上下文时第二道让查询当场返回空，而不是全表。
 */
@Service
public class LocationUtilizationService {

  /**
   * 「高效位」阈值：利用率 ≥ 此值算高位（≥85%），<b>≥1.0</b> 算爆满。
   *
   * <p>爆满的判据是 {@code > 1.0} 而不是 {@code >= 1.0}：正好装满是「刚好放不下下一件」， 仍属于高位；只有已经**超出**才是爆满。这条边界有测试。
   */
  private static final BigDecimal HIGH_THRESHOLD = new BigDecimal("0.85");

  /** 「适中」下限：利用率 ≥ 0.30 算适中，低于它算低位。 */
  private static final BigDecimal BALANCED_THRESHOLD = new BigDecimal("0.30");

  /** 出库频次取超过此值的 SKU 算「在动」。0 件出库的 SKU 与没有流水的 SKU 要分得开。 */
  private static final BigDecimal MOVING_EPSILON = new BigDecimal("0.0001");

  /** SKU 散在几个库位以上算「分散」，触发拣货路径建议。 */
  private static final long DISPERSION_THRESHOLD = 2;

  /** 出库频次达到「高频」的量级（窗口内件数），用于「高频货在不可拣库位」建议。 */
  private static final BigDecimal HIGH_FREQUENCY_QUANTITY = new BigDecimal("100");

  private final DSLContext sql;

  public LocationUtilizationService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 报表

  /**
   * 利用率报表 + 热力图 + 建议。
   *
   * <p>仓库不存在或不属于本租户一律 404 —— 与 8.1.2.1 同一套纪律：两者可观测结果必须相同， 否则「猜 id」就成了探测别的租户有没有仓库的侧信道。
   */
  public LocationUtilizationReport report(UUID warehouseId, LocationUtilizationQuery query) {
    requireWarehouse(warehouseId);
    LocationUtilizationQuery q = normalize(query);
    var window = window(q.windowDays());

    // 结构：递归 CTE 只带层级字段（8.1.2.1 同一纪律：递归项里 join 事实表会放大祖先汇总）。
    var rows = sql.fetch(structureSql(), warehouseId, warehouseId);
    // 每个库位上的 SKU 明细（含 SKU 的跨库位离散度与 SKU 级真实出库量）。
    var skuRows = skuRows(warehouseId, window.start(), window.end());
    // 摊分后的出库量：一次查询算出「每库位分到多少」，与结构查询分开。
    var flow = flowByLocation(skuRows);

    List<LocationUtilizationRow> all = new ArrayList<>(rows.size());
    for (var r : rows) {
      UUID id = r.get("id", UUID.class);
      var skus = skuRows.getOrDefault(id, List.of());
      var flowRow = flow.get(id);
      all.add(row(r, skus, flowRow));
    }

    // 热力强度依赖全仓最忙的库位作分母，所以先算完所有行再定 heat。
    BigDecimal maxOnHand = BigDecimal.ZERO;
    for (var row : all) {
      maxOnHand = maxOnHand.max(row.onHand());
    }
    List<LocationUtilizationRow> withHeat = new ArrayList<>(all.size());
    for (var row : all) {
      withHeat.add(withHeat(row, maxOnHand));
    }

    List<LocationUtilizationRow> shown =
        withHeat.stream().filter(r -> matches(r, q)).collect(java.util.stream.Collectors.toList());
    // 爆满的排最前 —— 要动的地方不该翻到最后一页去找。
    shown.sort(
        Comparator.comparing(LocationUtilizationRow::heat)
            .reversed()
            .thenComparing(LocationUtilizationRow::code));

    Map<UUID, LocationUtilizationRow> byId = new LinkedHashMap<>();
    for (var row : withHeat) {
      byId.put(row.id(), row);
    }
    List<LocationUtilizationHeatCell> heatmap = heatmap(rows, byId);

    return new LocationUtilizationReport(
        warehouseId,
        q.windowDays(),
        shown,
        // 合计**不受筛选影响**：筛完只看得到一部分行时，顶部那个数仍得是全仓真相。
        totals(warehouseId, all, window),
        heatmap,
        advice(withHeat, skuRows),
        note());
  }

  /**
   * 单库位详情：逐 SKU 拆分 + 该库位的建议。
   *
   * <p>拆到 SKU 是因为「高频商品放近出口」这条建议必须能落到具体商品上才有用 —— 只给一个库位级别的汇总数，用户看到「该库位出库量高」却不知道该挪哪个 SKU。
   */
  public LocationUtilizationDetail detail(
      UUID warehouseId, UUID locationId, LocationUtilizationQuery query) {
    requireWarehouse(warehouseId);
    LocationUtilizationQuery q = normalize(query);
    var window = window(q.windowDays());

    var node = sql.fetchOne(locationSql(), warehouseId, warehouseId, locationId);
    if (node == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "库位不存在");

    var grouped = skuRows(warehouseId, window.start(), window.end());
    var skus = grouped.getOrDefault(locationId, List.of());
    var flowRow = flowByLocation(grouped).get(locationId);
    LocationUtilizationRow row =
        withHeat(row(node, skus, flowRow), warehouseMaxOnHand(warehouseId));

    List<LocationUtilizationAdvice> myAdvice =
        advice(List.of(row), grouped).stream()
            .filter(a -> locationId.equals(a.locationId()))
            .toList();

    List<LocationUtilizationSkuRow> skuDetail = new ArrayList<>(skus.size());
    for (org.jooq.Record s : skus) {
      BigDecimal skuTotal = s.get("sku_on_hand", BigDecimal.class);
      BigDecimal here = nz(s.get("on_hand", BigDecimal.class));
      skuDetail.add(
          new LocationUtilizationSkuRow(
              s.get("sku_id", UUID.class),
              here,
              nz(s.get("reserved", BigDecimal.class)),
              nz(s.get("outbound_qty", BigDecimal.class)),
              count(s, "location_count"),
              count(s, "location_count") - 1,
              // 占该 SKU 全仓在手量的比例：分母为 0 时给 null，不给 0（0 会读成「这个库位
              // 一点货都没有」，而事实是「这个 SKU 全仓都没货」）。摊分用的正是它。
              skuTotal != null && skuTotal.signum() > 0
                  ? here.divide(skuTotal, 6, RoundingMode.HALF_UP)
                  : null,
              dwellOf(s.get("last_movement_at", OffsetDateTime.class))));
    }
    skuDetail.sort(
        Comparator.comparing(LocationUtilizationSkuRow::outboundQuantity)
            .reversed()
            .thenComparing(r -> r.skuId().toString()));

    return new LocationUtilizationDetail(row, skuDetail, myAdvice, detailNote());
  }

  /** 能力声明。8.1.2.2 自动分配与 8.3.2.1 拣货路径如实报未交付。 */
  public LocationUtilizationCapabilities capabilities() {
    return new LocationUtilizationCapabilities(
        true,
        true,
        true,
        true,
        true,
        false,
        "8.1.2.2 库位自动分配",
        false,
        "8.3.2.1 拣货路径优化",
        OUTBOUND_GRAIN,
        TURNOVER_BASIS,
        List.of(LocationUtilizationGrade.values()),
        DEFAULT_WINDOW_DAYS,
        MAX_WINDOW_DAYS,
        note());
  }

  // ================================================================ 口径

  /** 出库频次的归集粒度。<b>随响应返回</b>，不只写在注释里 —— 看报表的人有权知道自己看到的出库量是怎么来的。 */
  private static final String OUTBOUND_GRAIN =
      "出库量由 inventory_transaction(kind='SHIPMENT') + inventory_entry 的真实出库分录聚合到"
          + "「本仓 × SKU」粒度，再按该库位占此 SKU 全仓在手量的比例摊分到库位。"
          + "inventory_entry 是 balance 粒度、表上没有 location_id，所以「从哪个库位拣走」"
          + "系统未记录，这是摊分而不是逐库位实测。因比例可加，Σ库位出库量 == 仓库真实出库量。";

  /** 周转率口径。 */
  private static final String TURNOVER_BASIS =
      "周转率 = 本库位摊分出库量 ÷ 本库位在手量，单位为「窗口内翻了多少倍」。"
          + "在手量为 0 时无定义（turnoverComputable=false 且 turnover=null），"
          + "不给 0 也不给 100%。";

  /**
   * 容量利用率。
   *
   * <p><b>三种返回 null 的情况，理由各不相同：</b>
   *
   * <ul>
   *   <li>容量为 NULL（从没量过）—— 缺的是<b>知识</b>，给 0 会被读成「量过，是空的」；
   *   <li>容量为 0 —— 缺的是<b>定义</b>，除数本身是 0。给 0 会被读成「一个都没放」， 给 100% 是在替物理事实编数；
   *   <li>（不会发生）容量为负 —— V105 的 CHECK 已挡住，这里再兜一层是防御。
   * </ul>
   *
   * <p>保留 6 位小数（{@code numeric(19,6)} 的量级），比例用 BigDecimal 除法 + HALF_UP， 全程不经过 double。
   */
  private static BigDecimal utilizationOf(BigDecimal onHand, BigDecimal capacity) {
    if (capacity == null) return null;
    if (capacity.signum() <= 0) return null;
    return nz(onHand).divide(capacity, 6, RoundingMode.HALF_UP);
  }

  /**
   * 周转率：在手为 0 时<b>没有定义</b>，给 null。
   *
   * <p>出库 0 件 ÷ 在手 0 件不是「0 倍」也不是「无限倍」，是除以零。 返回 0 会说「这个库位周转很慢」，而事实是「这个库位压根没货」——
   * 那正是「空置库位」这条建议要处理的形态，不是周转率要表达的意思。
   */
  private static BigDecimal turnoverOf(BigDecimal outbound, BigDecimal onHand) {
    if (onHand == null || onHand.signum() <= 0) return null;
    return nz(outbound).divide(onHand, 6, RoundingMode.HALF_UP);
  }

  /**
   * 档位判定。<b>顺序是口径的一部分</b>，不许重排：
   *
   * <ol>
   *   <li>没货 → {@link LocationUtilizationGrade#IDLE}。先判这条是因为「空置」不依赖容量
   *       就成立：一个从没量过容量的空库位毫无疑问是空置，该被建议覆盖。 放在容量之后的话，大量 capacity 为 NULL 的存量库位会全落进 UNMEASURED，
   *       报表会因此看不见真正该处理的空库位；
   *   <li>有货但容量没量过 → {@link LocationUtilizationGrade#UNMEASURED}；
   *   <li>有货、容量量过 → 按利用率比值：&gt;1 爆满、≥0.85 高位、≥0.30 适中、否则低位。
   * </ol>
   */
  static LocationUtilizationGrade gradeOf(
      BigDecimal onHand, BigDecimal capacity, BigDecimal utilization) {
    if (nz(onHand).signum() <= 0) return LocationUtilizationGrade.IDLE;
    if (capacity == null || utilization == null) return LocationUtilizationGrade.UNMEASURED;
    if (utilization.compareTo(BigDecimal.ONE) > 0) return LocationUtilizationGrade.OVERFULL;
    if (utilization.compareTo(HIGH_THRESHOLD) >= 0) return LocationUtilizationGrade.HIGH;
    if (utilization.compareTo(BALANCED_THRESHOLD) >= 0) return LocationUtilizationGrade.BALANCED;
    return LocationUtilizationGrade.LOW;
  }

  /**
   * 热力强度 0..100。
   *
   * <p><b>不许画死色块</b>：强度必须由真数算出来，且 {@code metric} 说清它来自哪个指标。 容量量不出来时退回「在手量相对最忙库位」——那是一个真实存在的相对量，
   * 而不是随手编的颜色；空置库位强度恒为 0（没有货就没有可比的量）。
   */
  private static LocationUtilizationRow withHeat(LocationUtilizationRow row, BigDecimal maxOnHand) {
    LocationUtilizationMetric metric;
    int heat;
    if (row.capacityUtilization() != null) {
      metric = LocationUtilizationMetric.CAPACITY_UTILIZATION;
      BigDecimal scaled = row.capacityUtilization().multiply(new BigDecimal("100"));
      heat =
          scaled.compareTo(BigDecimal.ZERO) <= 0 ? 0 : scaled.min(new BigDecimal("100")).intValue();
    } else if (row.onHand().signum() <= 0) {
      metric = LocationUtilizationMetric.IDLE;
      heat = 0;
    } else {
      metric = LocationUtilizationMetric.ON_HAND_RELATIVE;
      heat =
          maxOnHand.signum() <= 0
              ? 0
              : row.onHand()
                  .multiply(new BigDecimal("100"))
                  .divide(maxOnHand, 0, RoundingMode.HALF_UP)
                  .intValue();
    }
    return new LocationUtilizationRow(
        row.id(),
        row.code(),
        row.name(),
        row.type(),
        row.typeLabel(),
        row.status(),
        row.pickable(),
        row.parentId(),
        row.capacity(),
        row.onHand(),
        row.reserved(),
        row.capacityUtilization(),
        row.capacityMeasured(),
        row.capacityZero(),
        row.capacityExceeded(),
        row.outboundQuantity(),
        row.outboundMovements(),
        row.turnover(),
        row.turnoverComputable(),
        row.skuCount(),
        row.skuDispersion(),
        row.dwellDays(),
        row.grade(),
        row.gradeLabel(),
        heat);
  }

  /** 筛选：利用率下限天然排除「未量/容量为 0」两类（它们的利用率恒为 null）。 */
  private static boolean matches(LocationUtilizationRow row, LocationUtilizationQuery q) {
    if (q.grade() != null && !q.grade().equals(row.grade().name())) return false;
    if (q.type() != null && !q.type().equals(row.type())) return false;
    if (Boolean.TRUE.equals(q.pickableOnly()) && !row.pickable()) return false;
    if (q.minUtilization() != null) {
      if (row.capacityUtilization() == null) return false;
      if (row.capacityUtilization().compareTo(q.minUtilization()) < 0) return false;
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      String k = q.keyword().toLowerCase(java.util.Locale.ROOT);
      boolean hit =
          (row.code() != null && row.code().toLowerCase(java.util.Locale.ROOT).contains(k))
              || (row.name() != null && row.name().toLowerCase(java.util.Locale.ROOT).contains(k));
      if (!hit) return false;
    }
    return true;
  }

  /** 建议生成。每一类都由真实数据触发，没有触发条件就一条都不出 —— 绝不为「凑齐六种类型」而硬造一条。 */
  private static List<LocationUtilizationAdvice> advice(
      List<LocationUtilizationRow> rows, Map<UUID, List<org.jooq.Record>> skuRows) {
    List<LocationUtilizationAdvice> out = new ArrayList<>();
    for (var row : rows) {
      switch (row.grade()) {
        case OVERFULL ->
            out.add(
                advice(
                    LocationUtilizationAdviceKind.OVERFULL_LOCATION,
                    LocationUtilizationSeverity.HIGH,
                    row,
                    null,
                    "库位 "
                        + row.code()
                        + " 已超容：在手 "
                        + plain(row.onHand())
                        + "，量得容量 "
                        + plain(row.capacity())
                        + "（"
                        + percent(row.capacityUtilization())
                        + "）。",
                    "扩容该库位，或把部分货分流到空置库位。",
                    "8.1.2.2 库位自动分配"));
        case IDLE ->
            out.add(
                advice(
                    LocationUtilizationAdviceKind.IDLE_LOCATION,
                    LocationUtilizationSeverity.MEDIUM,
                    row,
                    null,
                    "库位 " + row.code() + " 没有任何库存（可用容量 " + plain(row.capacity()) + "，若容量未量则为未量）。",
                    "把在堆的货上架到该库位，或下掉这个空库位。",
                    "8.1.2.2 库位自动分配"));
        case UNMEASURED ->
            out.add(
                advice(
                    LocationUtilizationAdviceKind.UNMEASURED_LOCATION,
                    LocationUtilizationSeverity.LOW,
                    row,
                    null,
                    "库位 " + row.code() + " 压着 " + plain(row.onHand()) + " 件，但容量从未量过，利用率算不出来。",
                    "补量该库位容量，利用率与爆满预警才有分母。",
                    null));
        default -> {
          // 低于阈值不算异常，不出建议 —— 报表的正常档位不该被「建议」淹没。
        }
      }

      // 低效库位：压着货但窗口内一件没出。这是「周转率低」的运营形态，
      // 与「空置」不同（有货、不动），也与利用率高低无关。
      if (row.onHand().signum() > 0 && row.outboundQuantity().compareTo(MOVING_EPSILON) < 0) {
        out.add(
            advice(
                LocationUtilizationAdviceKind.INEFFICIENT_LOCATION,
                LocationUtilizationSeverity.MEDIUM,
                row,
                null,
                "库位 "
                    + row.code()
                    + " 压着 "
                    + plain(row.onHand())
                    + " 件，统计窗口内出库 0 件（在手 "
                    + dwellText(row.dwellDays())
                    + "）。",
                "核实是否滞销，考虑调拨、降价或合并到更紧凑的库位。",
                null));
      }

      // 高频货放在不可拣库位：出库频次够高却拣不出来。
      if (!row.pickable() && row.outboundQuantity().compareTo(HIGH_FREQUENCY_QUANTITY) >= 0) {
        out.add(
            advice(
                LocationUtilizationAdviceKind.HIGH_FREQUENT_NON_PICKABLE,
                LocationUtilizationSeverity.HIGH,
                row,
                null,
                "库位 " + row.code() + " 不可拣，但窗口内出库量高达 " + plain(row.outboundQuantity()) + " 件。",
                "把高频商品移到可拣库位，缩短拣货行走距离。",
                "8.3.2.1 拣货路径优化"));
      }
    }

    // SKU 分散：一个 SKU 散在多个库位 = 一次拣货要多走几趟。这是蓝图点名的
    // 「库位热力数据同时优化拣货路径」的直接落点，所以它必须由真实的跨库位计数触发。
    // 先建 id→row 的索引：在 rows.stream().filter(...) 里逐条找会让这条路径变成 O(行数 × SKU 数)，
    // 大仓（几千个库位）上足以让一次报表请求超时。
    Map<UUID, LocationUtilizationRow> byId = new LinkedHashMap<>();
    for (var row : rows) {
      byId.put(row.id(), row);
    }
    for (var entry : skuRows.entrySet()) {
      var row = byId.get(entry.getKey());
      if (row == null) continue;
      for (var s : entry.getValue()) {
        long locations = count(s, "location_count");
        if (locations < DISPERSION_THRESHOLD) continue;
        out.add(
            advice(
                LocationUtilizationAdviceKind.SKU_DISPERSION,
                LocationUtilizationSeverity.MEDIUM,
                row,
                s.get("sku_id", UUID.class),
                "SKU "
                    + s.get("sku_id", UUID.class)
                    + " 散在 "
                    + locations
                    + " 个库位（含 "
                    + row.code()
                    + "），一次拣货要跨多个库位取同一种商品。",
                "把同一 SKU 收敛到尽量少的库位，拣一次拿齐。",
                "8.3.2.1 拣货路径优化"));
      }
    }

    out.sort(
        Comparator.comparingInt((LocationUtilizationAdvice a) -> a.severity().ordinal())
            .thenComparing(a -> a.locationCode() == null ? "" : a.locationCode()));
    return out;
  }

  private static LocationUtilizationAdvice advice(
      LocationUtilizationAdviceKind kind,
      LocationUtilizationSeverity severity,
      LocationUtilizationRow row,
      UUID skuId,
      String message,
      String action,
      String ownerModule) {
    return new LocationUtilizationAdvice(
        kind,
        kind.label(),
        severity,
        severity.label(),
        row.id(),
        row.code(),
        skuId,
        message,
        action,
        ownerModule);
  }

  // ================================================================ SQL

  /** 库位结构：递归 CTE 只带层级字段，占用用标量子查询（与 8.1.2.1 同一纪律）。 */
  private static String structureSql() {
    return """
        with recursive t as (
          select l.id, l.parent_id, l.tenant_id, l.code, l.name, l.type, l.status, l.pickable,
                 l.capacity, 0 as depth, l.code::text as path
            from warehouse_location l
           where l.tenant_id = current_setting('app.current_tenant', true)::uuid
             and l.warehouse_id = ? and l.parent_id is null
          union all
          select c.id, c.parent_id, c.tenant_id, c.code, c.name, c.type, c.status, c.pickable,
                 c.capacity, t.depth + 1, t.path || '|' || c.code
            from warehouse_location c
            join t on t.id = c.parent_id
           where c.tenant_id = current_setting('app.current_tenant', true)::uuid
             and c.warehouse_id = ?
        )
        select t.id, t.parent_id, t.code, t.name, t.type, t.status, t.pickable, t.capacity,
               t.depth,
               (select p.code from warehouse_location p
                 where p.tenant_id = t.tenant_id and p.id = t.parent_id) as parent_code,
               (select coalesce(sum(k.on_hand),0) from stock_bucket k
                 where k.tenant_id=t.tenant_id and k.location_id=t.id) as on_hand,
               (select coalesce(sum(k.reserved),0) from stock_bucket k
                 where k.tenant_id=t.tenant_id and k.location_id=t.id) as reserved,
               (select count(distinct k.sku_id) from stock_bucket k
                 where k.tenant_id=t.tenant_id and k.location_id=t.id) as sku_count,
               (select max(k.last_movement_at) from stock_bucket k
                 where k.tenant_id=t.tenant_id and k.location_id=t.id) as last_movement_at
          from t
         order by t.path
        """;
  }

  /** 单库位：同一个口径，只是收口到一个 id（给详情端点用）。 */
  private static String locationSql() {
    return """
        with recursive t as (
          select l.id, l.parent_id, l.tenant_id, l.code, l.name, l.type, l.status, l.pickable,
                 l.capacity, 0 as depth, l.code::text as path
            from warehouse_location l
           where l.tenant_id = current_setting('app.current_tenant', true)::uuid
             and l.warehouse_id = ? and l.parent_id is null
          union all
          select c.id, c.parent_id, c.tenant_id, c.code, c.name, c.type, c.status, c.pickable,
                 c.capacity, t.depth + 1, t.path || '|' || c.code
            from warehouse_location c
            join t on t.id = c.parent_id
           where c.tenant_id = current_setting('app.current_tenant', true)::uuid
             and c.warehouse_id = ?
        )
        select t.id, t.parent_id, t.code, t.name, t.type, t.status, t.pickable, t.capacity,
               t.depth,
               (select p.code from warehouse_location p
                 where p.tenant_id = t.tenant_id and p.id = t.parent_id) as parent_code,
               (select coalesce(sum(k.on_hand),0) from stock_bucket k
                 where k.tenant_id=t.tenant_id and k.location_id=t.id) as on_hand,
               (select coalesce(sum(k.reserved),0) from stock_bucket k
                 where k.tenant_id=t.tenant_id and k.location_id=t.id) as reserved,
               (select count(distinct k.sku_id) from stock_bucket k
                 where k.tenant_id=t.tenant_id and k.location_id=t.id) as sku_count,
               (select max(k.last_movement_at) from stock_bucket k
                 where k.tenant_id=t.tenant_id and k.location_id=t.id) as last_movement_at
          from t
         where t.id = ?
        """;
  }

  /**
   * 每个库位上的 SKU 明细 + 该 SKU 的跨库位离散度 + 真实出库量。
   *
   * <p>出库量在本查询里是 <b>SKU 级真实值</b>（未摊分），摊分在 {@link #flowByLocation} 里按在手占比做 ——
   * 两步分开是为了让「真实值」与「摊分值」各自可审阅。
   */
  private List<org.jooq.Record> skuDetailRows(
      UUID warehouseId, OffsetDateTime from, OffsetDateTime to) {
    return sql.fetch(
        """
        with loc_sku as (
          select k.location_id, k.sku_id,
                 sum(k.on_hand) as on_hand, sum(k.reserved) as reserved,
                 max(k.last_movement_at) as last_movement_at
            from stock_bucket k
           where k.tenant_id = current_setting('app.current_tenant', true)::uuid
             and k.warehouse_id = ?
           group by k.location_id, k.sku_id
        ),
        sku_total as (
          select sku_id, sum(on_hand) as sku_on_hand from loc_sku group by sku_id
        ),
        sku_out as (
          select b.sku_id,
                 -- greatest(-delta, 0)：只计出库方向的**递减**分录。
                 -- SHIPMENT 单里的分录若有正数（冲正/回加），直接求和会把它抵消掉，
                 -- 让一个真的出过库的 SKU 看起来「窗口内没动过」。
                 sum(greatest(-e.on_hand_delta, 0)) as outbound_qty,
                 count(distinct t.id) as movements
            from inventory_entry e
            join inventory_transaction t
              on t.tenant_id = e.tenant_id and t.id = e.transaction_id
            join inventory_balance b
              on b.tenant_id = e.tenant_id and b.id = e.balance_id
           where t.tenant_id = current_setting('app.current_tenant', true)::uuid
             and t.kind = 'SHIPMENT'
             and b.warehouse_id = ?
             and t.occurred_at >= ?::timestamptz and t.occurred_at < ?::timestamptz
           group by b.sku_id
        )
        select ls.location_id, ls.sku_id, ls.on_hand, ls.reserved,
               st.sku_on_hand,
               coalesce(so.outbound_qty, 0) as outbound_qty,
               coalesce(so.movements, 0) as movements,
               (select count(*) from loc_sku x where x.sku_id = ls.sku_id) as location_count,
               ls.last_movement_at
          from loc_sku ls
          left join sku_total st on st.sku_id = ls.sku_id
          left join sku_out so on so.sku_id = ls.sku_id
         order by ls.location_id, ls.sku_id
        """,
        warehouseId,
        warehouseId,
        from,
        to);
  }

  /** 库位 → SKU 明细分组。窗口显式传参（不写死默认值，否则调窗口长度会与行里的出库量脱节）。 */
  private Map<UUID, List<org.jooq.Record>> skuRows(
      UUID warehouseId, OffsetDateTime from, OffsetDateTime to) {
    Map<UUID, List<org.jooq.Record>> out = new LinkedHashMap<>();
    for (var r : skuDetailRows(warehouseId, from, to)) {
      out.computeIfAbsent(r.get("location_id", UUID.class), k -> new ArrayList<>()).add(r);
    }
    return out;
  }

  /** 全仓真实出库量（未摊分）—— 用来核对摊分是否配平。 */
  private BigDecimal warehouseOutbound(UUID warehouseId, OffsetDateTime from, OffsetDateTime to) {
    var r =
        sql.fetchOne(
            """
            select coalesce(sum(greatest(-e.on_hand_delta, 0)), 0) as qty
              from inventory_entry e
              join inventory_transaction t
                on t.tenant_id = e.tenant_id and t.id = e.transaction_id
              join inventory_balance b
                on b.tenant_id = e.tenant_id and b.id = e.balance_id
             where t.tenant_id = current_setting('app.current_tenant', true)::uuid
               and t.kind = 'SHIPMENT'
               and b.warehouse_id = ?
               and t.occurred_at >= ?::timestamptz and t.occurred_at < ?::timestamptz
            """,
            warehouseId,
            from,
            to);
    return r == null ? BigDecimal.ZERO : decimal(r, "qty");
  }

  /**
   * 把 SKU 级真实出库量<b>摊分</b>到库位。
   *
   * <p>{@code Σ(库位出库量) == 仓库真实出库量}：因为同一 SKU 在各库位的在手占比之和为 1。 摊分时 {@code nullif} 兜住「SKU 全仓在手为 0」这个
   * 0/0 —— 让它得 NULL 再 coalesce 成 0， 而不是让整个查询除零炸掉。
   */
  private Map<UUID, Flow> flowByLocation(Map<UUID, List<org.jooq.Record>> grouped) {
    Map<UUID, BigDecimal> allocated = new LinkedHashMap<>();
    Map<UUID, Long> movements = new LinkedHashMap<>();
    Map<UUID, Long> movingSkus = new LinkedHashMap<>();
    for (var records : grouped.values()) {
      for (var r : records) {
        UUID location = r.get("location_id", UUID.class);
        BigDecimal skuTotal = r.get("sku_on_hand", BigDecimal.class);
        BigDecimal here = nz(r.get("on_hand", BigDecimal.class));
        BigDecimal out = nz(r.get("outbound_qty", BigDecimal.class));
        BigDecimal share =
            skuTotal == null || skuTotal.signum() <= 0
                ? BigDecimal.ZERO
                : here.divide(skuTotal, 12, RoundingMode.HALF_UP);
        allocated.merge(location, out.multiply(share), BigDecimal::add);
        movements.merge(
            location,
            nz(r.get("movements", BigDecimal.class)).setScale(0, RoundingMode.HALF_UP).longValue(),
            Long::sum);
        if (out.compareTo(MOVING_EPSILON) >= 0) {
          movingSkus.merge(location, 1L, Long::sum);
        }
      }
    }
    Map<UUID, Flow> out = new LinkedHashMap<>();
    for (UUID id : allocated.keySet()) {
      out.put(
          id,
          new Flow(
              allocated.get(id).setScale(6, RoundingMode.HALF_UP),
              movements.getOrDefault(id, 0L),
              movingSkus.getOrDefault(id, 0L)));
    }
    return out;
  }

  /**
   * 一个库位摊分到的出库流量。
   *
   * @param outboundQuantity 按在手占比摊分到本库位的出库量
   * @param movements 窗口内触及本库位所放 SKU 的出库单数
   * @param movingSkus 本库位上「窗口内出过货」的 SKU 数
   */
  private record Flow(BigDecimal outboundQuantity, long movements, long movingSkus) {}

  // ================================================================ 组装

  /** 结构行 + SKU 明细 + 摊分流量 → 一个库位行。 */
  private static LocationUtilizationRow row(
      org.jooq.Record r, List<org.jooq.Record> skus, Flow flowRow) {
    BigDecimal capacity = r.get("capacity", BigDecimal.class);
    BigDecimal onHand = decimal(r, "on_hand");
    BigDecimal reserved = decimal(r, "reserved");
    BigDecimal utilization = utilizationOf(onHand, capacity);
    boolean capacityZero = capacity != null && capacity.signum() == 0;
    boolean capacityExceeded = utilization != null && utilization.compareTo(BigDecimal.ONE) > 0;

    BigDecimal outbound = flowRow == null ? BigDecimal.ZERO : flowRow.outboundQuantity();
    long movements = flowRow == null ? 0L : flowRow.movements();
    BigDecimal turnover = turnoverOf(outbound, onHand);

    long skuCount = count(r, "sku_count");
    long dispersion = 0;
    for (var s : skus) {
      dispersion = Math.max(dispersion, count(s, "location_count") - 1);
    }
    BigDecimal dwellDays = dwellOf(r.get("last_movement_at", OffsetDateTime.class));

    var type = WarehouseLocationTreeModels.LocationType.of(r.get("type", String.class));
    var grade = gradeOf(onHand, capacity, utilization);

    return new LocationUtilizationRow(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        type.name(),
        type.label(),
        r.get("status", String.class),
        Boolean.TRUE.equals(r.get("pickable", Boolean.class)),
        r.get("parent_id", UUID.class),
        capacity,
        onHand,
        reserved,
        utilization,
        capacity != null,
        capacityZero,
        capacityExceeded,
        outbound,
        movements,
        turnover,
        turnover != null,
        skuCount,
        dispersion,
        dwellDays,
        grade,
        grade.label(),
        0);
  }

  /** 热力图：与逐行报表同源，只是换一种呈现（不另算一套数）。 */
  private static List<LocationUtilizationHeatCell> heatmap(
      List<org.jooq.Record> structure, Map<UUID, LocationUtilizationRow> rows) {
    List<LocationUtilizationHeatCell> out = new ArrayList<>(structure.size());
    for (var r : structure) {
      UUID id = r.get("id", UUID.class);
      var row = rows.get(id);
      if (row == null) continue;
      LocationUtilizationMetric metric;
      BigDecimal value;
      if (row.capacityUtilization() != null) {
        metric = LocationUtilizationMetric.CAPACITY_UTILIZATION;
        value = row.capacityUtilization();
      } else if (row.onHand().signum() <= 0) {
        metric = LocationUtilizationMetric.IDLE;
        value = null;
      } else {
        metric = LocationUtilizationMetric.ON_HAND_RELATIVE;
        value = row.onHand();
      }
      out.add(
          new LocationUtilizationHeatCell(
              id,
              row.code(),
              row.name(),
              row.parentId(),
              r.get("parent_code", String.class),
              r.get("depth", Integer.class) == null ? 0 : r.get("depth", Integer.class),
              row.grade(),
              row.gradeLabel(),
              row.capacityUtilization(),
              metric,
              row.heat(),
              value));
    }
    return out;
  }

  /**
   * 全仓合计。
   *
   * <p><b>利用率合计只在全部库位都量过容量且容量总和 &gt; 0 时才给</b>，否则 null： 把已量的容量相加当分母，会算出一个没人测过的比例。前端据此显示「仅覆盖 N/M
   * 个库位」。
   *
   * <p>出库量这里用 <b>真实值</b>而不是把逐行摊分值加起来 —— 后者虽然配平， 但要读懂它必须先知道摊分规则；直接给真实值更不容易被误读。两者的相等性由测试断言。
   */
  private LocationUtilizationTotals totals(
      UUID warehouseId, List<LocationUtilizationRow> all, Window window) {
    long measured = 0,
        zero = 0,
        idle = 0,
        overfull = 0,
        high = 0,
        low = 0,
        balanced = 0,
        unmeasured = 0,
        pickable = 0,
        skuCount = 0;
    BigDecimal capacity = BigDecimal.ZERO, onHand = BigDecimal.ZERO, reserved = BigDecimal.ZERO;
    BigDecimal dwellSum = BigDecimal.ZERO;
    long dwellN = 0;
    for (var row : all) {
      if (row.capacityMeasured()) {
        measured++;
        if (row.capacityZero()) zero++;
      }
      if (row.pickable()) pickable++;
      skuCount += row.skuCount();
      capacity = capacity.add(nz(row.capacity()));
      onHand = onHand.add(row.onHand());
      reserved = reserved.add(row.reserved());
      if (row.dwellDays() != null) {
        dwellSum = dwellSum.add(row.dwellDays());
        dwellN++;
      }
      switch (row.grade()) {
        case IDLE -> idle++;
        case OVERFULL -> overfull++;
        case HIGH -> high++;
        case LOW -> low++;
        case BALANCED -> balanced++;
        case UNMEASURED -> unmeasured++;
      }
    }
    boolean utilizationComputable =
        !all.isEmpty() && measured == all.size() && capacity.signum() > 0;
    BigDecimal totalUtilization = utilizationComputable ? utilizationOf(onHand, capacity) : null;

    BigDecimal outbound = warehouseOutbound(warehouseId, window.start(), window.end());
    BigDecimal turnover = turnoverOf(outbound, onHand);

    return new LocationUtilizationTotals(
        all.size(),
        measured,
        zero,
        idle,
        overfull,
        high,
        low,
        balanced,
        unmeasured,
        pickable,
        capacity,
        onHand,
        reserved,
        totalUtilization,
        utilizationComputable,
        outbound,
        outboundMovements(window, warehouseId),
        turnover,
        turnover != null,
        skuCount);
  }

  private long outboundMovements(Window window, UUID warehouseId) {
    var r =
        sql.fetchOne(
            """
            select count(distinct t.id) as movements
              from inventory_transaction t
              join inventory_entry e on e.tenant_id = t.tenant_id and e.transaction_id = t.id
              join inventory_balance b on b.tenant_id = e.tenant_id and b.id = e.balance_id
             where t.tenant_id = current_setting('app.current_tenant', true)::uuid
               and t.kind = 'SHIPMENT' and b.warehouse_id = ?
               and t.occurred_at >= ?::timestamptz and t.occurred_at < ?::timestamptz
            """,
            warehouseId,
            window.start(),
            window.end());
    return r == null ? 0L : count(r, "movements");
  }

  private BigDecimal warehouseMaxOnHand(UUID warehouseId) {
    var r =
        sql.fetchOne(
            """
            select coalesce(max(l.on_hand), 0) as m
              from (select (select coalesce(sum(k.on_hand),0) from stock_bucket k
                             where k.tenant_id = l.tenant_id and k.location_id = l.id) as on_hand
                      from warehouse_location l
                     where l.tenant_id = current_setting('app.current_tenant', true)::uuid
                       and l.warehouse_id = ?) l
            """,
            warehouseId);
    return r == null ? BigDecimal.ZERO : decimal(r, "m");
  }

  // ================================================================ 入参归一

  /** 统计窗口。窗口长度被夹在 1..{@value #MAX_WINDOW_DAYS}，非法值 400 而不是静默改写。 */
  private record Window(OffsetDateTime start, OffsetDateTime end) {}

  private static Window window(int days) {
    var end = OffsetDateTime.now();
    return new Window(end.minusDays(days), end);
  }

  private static LocationUtilizationQuery normalize(LocationUtilizationQuery q) {
    LocationUtilizationQuery input =
        q == null ? new LocationUtilizationQuery(null, null, null, null, null, null) : q;
    int days = input.windowDays() == null ? DEFAULT_WINDOW_DAYS : input.windowDays();
    if (days < 1 || days > MAX_WINDOW_DAYS) {
      throw new IllegalArgumentException("统计窗口必须在 1 到 " + MAX_WINDOW_DAYS + " 天之间：" + days);
    }
    LocationUtilizationGrade grade = null;
    if (input.grade() != null && !input.grade().isBlank()) {
      grade = gradeOf(input.grade());
    }
    String type = null;
    if (input.type() != null && !input.type().isBlank()) {
      type = WarehouseLocationTreeModels.LocationType.of(input.type()).name();
    }
    BigDecimal min = input.minUtilization();
    if (min != null && min.signum() < 0) {
      throw new IllegalArgumentException("利用率下限不能为负数");
    }
    String keyword =
        input.keyword() == null || input.keyword().isBlank() ? null : input.keyword().trim();
    if (keyword != null && keyword.length() > 200) {
      throw new IllegalArgumentException("关键字长度不能超过 200");
    }
    return new LocationUtilizationQuery(
        days, grade == null ? null : grade.name(), type, keyword, min, input.pickableOnly());
  }

  private static LocationUtilizationGrade gradeOf(String raw) {
    for (var g : LocationUtilizationGrade.values()) {
      if (g.name().equalsIgnoreCase(raw.trim())) return g;
    }
    throw new IllegalArgumentException("利用率档位不在词表内：" + raw);
  }

  // ================================================================ 内部

  /** 仓库必须属于本租户，否则 404（与 8.1.1.2 / 8.1.2.1 同一套）。 */
  private void requireWarehouse(UUID warehouseId) {
    var row =
        sql.fetchOne(
            """
            select 1 from warehouse
             where tenant_id = current_setting('app.current_tenant', true)::uuid and id = ?
            """,
            warehouseId);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓库不存在");
  }

  private static String note() {
    return "容量来自 8.1.2.1 的 warehouse_location.capacity，占用来自 stock_bucket 实时聚合，"
        + "出库频次来自 inventory_transaction/entry 的真实出库分录并按在手占比摊分到库位；"
        + "容量未量、容量为 0、在手为 0 三处分母边界一律给 null，不给 NaN 也不假装 100%。";
  }

  private static String detailNote() {
    return "逐 SKU 拆分是「高频商品放近出口」这条建议的依据：出库量与跨库位离散度都是真数，" + "占比分母为 0 时给 null。";
  }

  private static String plain(BigDecimal v) {
    return v == null ? "—" : v.stripTrailingZeros().toPlainString();
  }

  private static String percent(BigDecimal ratio) {
    if (ratio == null) return "利用率未量";
    return ratio.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP) + "%";
  }

  private static String dwellText(BigDecimal days) {
    return days == null ? "无库存记录" : "已 " + days.setScale(1, RoundingMode.HALF_UP) + " 天未变动";
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  /**
   * 在手天数（距最近一次库存变动）。
   *
   * <p><b>没有库存行时给 null 而不是 0</b>：「从没放过货」与「放了很久没动」是两件事， 报成 0 天会让后者看起来像刚上架。走 Instant 比较而不是
   * LocalDateTime —— {@code Timestamp.valueOf(LocalDateTime)} 会静默偏掉时区。
   */
  private static BigDecimal dwellOf(OffsetDateTime lastMovement) {
    if (lastMovement == null) return null;
    long seconds =
        java.time.Duration.between(lastMovement.toInstant(), java.time.Instant.now()).getSeconds();
    return BigDecimal.valueOf(seconds).divide(new BigDecimal("86400"), 1, RoundingMode.HALF_UP);
  }

  private static BigDecimal decimal(org.jooq.Record r, String column) {
    return nz(r.get(column, BigDecimal.class));
  }

  private static long count(org.jooq.Record r, String column) {
    Long v = r.get(column, Long.class);
    return v == null ? 0L : v;
  }
}
