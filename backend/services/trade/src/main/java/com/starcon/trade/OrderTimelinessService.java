package com.starcon.trade;

import static com.starcon.trade.OrderTimelinessModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.5.1.1 时效分析。
 *
 * <p><b>六条实现纪律</b>（照抄同服务 9.4.1.2 已交付的写法，不另起风格）：
 *
 * <ol>
 *   <li><b>本模块一次都不自己造时刻</b>。下单 / 出库 / 交接三个端点全部来自
 *       同服务既有投影：{@code sales_order.submitted_at}、
 *       {@code shipment_pending_unit.packed_at}、
 *       {@code shipment_record.shipped_at}。读不到就是 NULL，
 *       落 UNSET 桶，<b>绝不按 0 算</b> —— 0 秒的履约是一个比「算不出来」
 *       严重得多的结论。</li>
 *   <li><b>时长由 DB 复算</b>。五个时长列都是 GENERATED ... STORED 的 bigint，
 *       服务层只读不算；应用层算错也改不动（V131 第二节）。</li>
 *   <li><b>「漏了」与「没有」分开</b>。承诺到货没登记（NO_PROMISE）、实际到货
 *       没登记（NO_ACTUAL）、发货时限没登记（UNSET）三个桶互不吞并 ——
 *       这三样在平台申诉时要提交的举证材料完全不同（V131 第三、四节）。</li>
 *   <li><b>同服务只读 join</b>。{@code shipment_pending_unit} / {@code sales_order}
 *       / {@code sales_order_line} 全在 trade 库里，纯 SELECT，无 FK、无触发器、
 *       无写入、不 ALTER 对方表。一条 SQL 都不碰 warehouse 库（V131 第五、六节）。</li>
 *   <li><b>租户隔离只靠 RLS</b>，WHERE 里**不写** {@code tenant_id} 谓词 ——
 *       与 9.4.1.2 / 9.4.1.1 同一写法。手写一层谓词等于把「哪些查询漏了」
 *       变成需要逐条审计的问题，而 FORCE RLS 已经把这件事在 DB 层做完了。</li>
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 写在 values 的对应位置上，
 *       全程 {@code OffsetDateTime}，不用 {@code Timestamp.valueOf(LocalDateTime)}
 *       （那会静默偏 −28800s）。筛选值一律走 {@code ?} 绑定，不拼字面量。</li>
 * </ol>
 *
 * <p><b>没有跨服务调用</b>：10.3 物流轨迹未交付，本模块的「实际到货」只能登记。
 * 上游全在同一个库里，因此本服务<b>不存在</b>「上游不可达」这条路径 ——
 * 上游缺失的表达方式只有一种：那一行读出来就是空的，如实报数。
 */
@Service
public class OrderTimelinessService {

  /** 单页最大行数（与 9.4.1.2 / 9.4.1.1 同一上限）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 同步单次最多扫多少张发货记录 —— 一次拉爆会把请求线程占死。 */
  static final int MAX_SYNC_PAGE = 500;

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /** 事实号按业务日（Asia/Shanghai）编号，与 V127 的 SR-yyyyMMdd 同口径。 */
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  private static final String FACT_COLUMNS =
      "f.id,f.fact_no,f.source_record_id,f.source_record_no,f.sales_order_id,f.sales_order_no,"
          + "f.shop_ref,f.warehouse_ref,f.carrier_code,f.carrier_name,"
          + "f.ordered_at,f.packed_at,f.handed_over_at,"
          + "f.order_to_pack_seconds,f.pack_to_handover_seconds,f.order_to_handover_seconds,"
          + "f.ship_sla_deadline_at,f.ship_sla_source,f.ship_overdue_seconds,f.ship_sla_state,"
          + "f.promised_delivery_at,f.promise_source,f.actual_delivered_at,f.delivery_state,"
          + "f.promise_vs_delivery_seconds,f.promise_vs_delivery_state,f.synced_at";

  private static final String FACT_FROM = " from order_timeliness_fact f";

  /** 排序白名单 → ORDER BY 片段。刻意不接受调用方指定列名。 */
  private static final Map<TimelinessSort, String> SORT_SQL =
      Map.of(
          TimelinessSort.HANDOVER_AT_DESC, "f.handed_over_at desc, f.fact_no desc",
          TimelinessSort.HANDOVER_AT_ASC, "f.handed_over_at asc, f.fact_no asc",
          TimelinessSort.OVERDUE_DESC,
              "f.ship_overdue_seconds desc nulls last, f.handed_over_at desc",
          TimelinessSort.OVERDUE_ASC,
              "f.ship_overdue_seconds asc nulls last, f.handed_over_at desc",
          TimelinessSort.ORDER_TO_HANDOVER_DESC,
              "f.order_to_handover_seconds desc nulls last, f.handed_over_at desc",
          TimelinessSort.FACT_NO_ASC, "f.fact_no asc");

  /**
   * 同步用的只读 join。
   *
   * <p>三张同服务表全部<b>只读</b>：{@code shipment_record} 是锚点，
   * {@code shipment_pending_unit} 按 {@code source_packing_order_id} 取出库时刻
   * （与 9.4.1.2 取订单快照同一把钥匙），{@code sales_order} 按 id 取下单时刻。
   * 刻意全部 LEFT JOIN：缺一个端点就是那一段 UNSET，不是这一行消失。
   */
  private static final String SOURCE_JOIN_SQL =
      " from shipment_record r"
          + " left join shipment_pending_unit u"
          + "   on u.source_packing_order_id = r.source_packing_order_id"
          + " left join sales_order o on o.id = r.sales_order_id";

  private final DSLContext sql;

  public OrderTimelinessService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>本模块有<b>两处</b>「拿不到却必须说」：轨迹实际时间与实际到货
   * （10.3 未交付）。两处一律 available=false 并点名 ownerModule，同时说清
   * 本模块的处理是「登记」而不是「读取」—— 界面上不能把「登记了」
   * 画成「系统自动抓到轨迹了」。
   *
   * <p>另有两处「可能为空」：下单时刻与出库时刻。它们不是缺口而是数据现状
   * （草稿单没有提交时刻、9.4.1.1 还没同步过这票货），所以 available=true
   * 但如实说明「读不到时落 UNSET 独立桶」。
   */
  public OrderTimelinessCapabilities capabilities() {
    return new OrderTimelinessCapabilities(
        List.of(
            new OrderTimelinessCapabilityDimension(
                "SHIPMENT_FACT",
                "交运事实与发货时限（交接时刻、承运商、店铺、仓库、发货时限快照）",
                true,
                null,
                null,
                "同服务 9.4.1.2 的 shipment_record（trade 库内**只读 join**，按 source_record_id；"
                    + "无 FK、无触发器、无写入）"),
            new OrderTimelinessCapabilityDimension(
                "ORDER_PLACED_AT",
                "下单时刻（下单到出库段的起点）",
                true,
                "不是缺口而是数据现状：sales_order.status='DRAFT' 的单还没有 submitted_at",
                null,
                "取 submitted_at（提交时刻）而不是 created_at（建单时刻）—— "
                    + "把草稿停留算进履约时效会把人的录入时长当成履约时长；"
                    + "**读不到时该段落 UNSET 独立桶，绝不按 0 算**"),
            new OrderTimelinessCapabilityDimension(
                "OUTBOUND_AT",
                "出库时刻（下单到出库的终点、出库到交接的起点）",
                true,
                "不是缺口而是数据现状：9.4.1.1 还没同步过这票货时没有这一行",
                null,
                "同服务 9.4.1.1 的 shipment_pending_unit.packed_at（只读 join，按 "
                    + "source_packing_order_id）。出库单在 warehouse 服务自己的库里，"
                    + "AGENTS.md 禁止跨服务 SQL，所以本模块**一条 SELECT 都不碰 warehouse 库**；"
                    + "读不到时该段落 UNSET 独立桶"),
            new OrderTimelinessCapabilityDimension(
                "TRACKING_ACTUAL_TIME",
                "轨迹实际时间",
                false,
                "10.3 物流轨迹未交付，集成头里没有任何承运商轨迹数据源",
                "10.3 物流轨迹 / 10 物流下单",
                "本模块**不抓取**。承诺 vs 实际两个时刻只能由 POST /facts/{id}/promise 与 "
                    + "/delivery **登记**（有主体、有登记时刻、有来源、凭据 append-only）；"
                    + "**未登记落 NO_PROMISE / NO_ACTUAL 两个独立桶**，"
                    + "界面上不会画成「准时到货」"),
            new OrderTimelinessCapabilityDimension(
                "DELIVERY_PROMISE_CONFIG",
                "承诺时效配置",
                false,
                "平台 SLA 面属 2.5.1.1 平台回传（未交付），本车道集成头没有承诺到货配置来源",
                "2.5.1.1 平台回传 / 2.5 平台对接",
                "本模块改为**逐票登记**（POST /facts/{id}/promise），而不是一套全局配置表："
                    + "平台 SLA 是按店铺/承运商/区域逐单下发的，用一张全局配置表去套"
                    + "会把不同店铺的承诺混成同一个数"),
            new OrderTimelinessCapabilityDimension(
                "ORDER_LINE_SNAPSHOT",
                "商品维度（订单行 SKU）",
                true,
                null,
                null,
                "同服务 sales_order_line 的**只读 join**（按 sales_order_id）。"
                    + "刻意不冗余进事实表：订单行是订单的属性，冻结成货票的属性会在订单改行后"
                    + "出现两套 SKU 口径；sales_order_id 为空的票计入 withoutOrderSnapshot 并在该维度上查不到"),
            new OrderTimelinessCapabilityDimension(
                "DOWNSTREAM_ANALYTICS",
                "下游消费（13 分析、16 合规绩效指标）",
                false,
                "13 分析与 16 合规均未交付，本车道集成头没有消费方",
                "13 销量与运营分析 / 16 合规",
                "本模块只出 trade 侧口径的时效事实与报表，**不冒充下游指标**，"
                    + "也不跨服务写对方库；下游接上时按授权 API 读本模块的事实表")),
        Arrays.stream(TimelinessSegment.values())
            .map(s -> s.name() + ":" + s.displayName)
            .toList(),
        Arrays.stream(TimelinessDimension.values())
            .map(d -> d.name() + ":" + d.displayName)
            .toList(),
        Arrays.stream(PromiseVsDeliveryState.values()).map(Enum::name).toList(),
        Arrays.stream(ShipSlaState.values()).map(Enum::name).toList(),
        Arrays.stream(TimelinessSort.values())
            .map(s -> s.name() + ":" + s.displayName)
            .toList(),
        "秒（整数，bigint）。刻意不用浮点小时：时效是平台算法权重项，"
            + "浮点累加误差会一路带到阈值判定。均值是 numeric（DB 的 avg(bigint)），"
            + "中位数与 90 分位用 percentile_disc 取离散精确值",
        "下单→出库 = packed_at − submitted_at；出库→交接 = shipped_at − packed_at；"
            + "下单→交接 = shipped_at − submitted_at（直接相减而不是两段相加，"
            + "一端缺失时整段 UNSET）；承诺→实际 = actual_delivered_at − promised_delivery_at。"
            + "发货超时 = shipped_at − ship_sla_deadline_at。"
            + "**任何一段的两个端点只要有一个缺失，该段就是 UNSET 而不是 0**",
        "下游 13 分析与 16 合规（绩效指标）均未交付；本模块只出 trade 侧口径的时效事实，"
            + "不冒充下游指标，也不跨服务写对方库");
  }

  // ================================================================ 同步（投影刷新）

  /**
   * 从 9.4.1.2 的发货记录刷新时效事实。
   *
   * <p>幂等：按 {@code source_record_id} upsert，重复同步是更新而不是插第二行 ——
   * 时效统计的分母被翻倍，报出来的准时率与超时数当场失真。
   *
   * <p>刻意<b>不删</b>已经不在上游的旧行：那些行可能已经带了承诺与实际到货登记，
   * 删掉等于把一笔已经发生的时效从台账上抹掉。它们照常留在清单上，
   * 只在结果里如实报出数量。
   *
   * <p>本模块<b>没有跨服务调用</b>：上游三张表全在 trade 库里，
   * 因此不存在「上游不可达 ⇒ 返回 503」这条路径。缺一个端点就是该段 UNSET。
   */
  @Transactional
  public TimelinessSyncResult sync(TimelinessSyncInput input) {
    int pageSize =
        input.pageSize() != null && input.pageSize() > 0
            ? Math.min(input.pageSize(), MAX_SYNC_PAGE)
            : MAX_SYNC_PAGE;
    OffsetDateTime now = OffsetDateTime.now();

    List<Record> sources =
        sql.fetch(
            // 列名一律带表别名：三张表都有 id/created_at，不加别名 PostgreSQL
            // 会直接报「column reference id is ambiguous」
            "select r.id, r.record_no, r.sales_order_id, r.sales_order_no, r.shop_ref,"
                + " r.warehouse_ref, r.carrier_code, r.carrier_name, r.shipped_at,"
                + " r.sla_deadline_at, r.sla_source, u.packed_at, o.submitted_at"
                + SOURCE_JOIN_SQL
                + " order by r.shipped_at desc, r.id desc"
                + " limit ?",
            pageSize);

    int scanned = sources.size();
    int inserted = 0;
    int updated = 0;
    int unchanged = 0;
    int withOrderedAt = 0;
    int withoutOrderedAt = 0;
    int withPackedAt = 0;
    int withoutPackedAt = 0;
    int withoutOrderSnapshot = 0;
    int skippedTimeInversion = 0;

    for (Record r : sources) {
      OffsetDateTime handedOverAt = r.get("shipped_at", OffsetDateTime.class);
      OffsetDateTime packedAt = r.get("packed_at", OffsetDateTime.class);
      OffsetDateTime orderedAt = r.get("submitted_at", OffsetDateTime.class);
      UUID salesOrderId = r.get("sales_order_id", UUID.class);
      UUID sourceRecordId = r.get("id", UUID.class);

      if (salesOrderId == null) {
        withoutOrderSnapshot++;
      } else if (orderedAt != null) {
        withOrderedAt++;
      } else {
        withoutOrderedAt++;
      }
      if (packedAt != null) {
        withPackedAt++;
      } else {
        withoutPackedAt++;
      }

      // 时间倒流的行**跳过并报数**，不硬写：写进去会让时长变负，
      // 而负时长不是「极快的履约」，是数据问题。DB 触发器也会独立拦一道。
      if ((packedAt != null && orderedAt != null && packedAt.isBefore(orderedAt))
          || (packedAt != null && handedOverAt.isBefore(packedAt))) {
        skippedTimeInversion++;
        continue;
      }

      Record existing =
          sql.fetchOne(
              "select id, ordered_at, packed_at, handed_over_at, shop_ref, warehouse_ref,"
                  + " carrier_code, carrier_name, ship_sla_deadline_at, ship_sla_source,"
                  + " sales_order_id, sales_order_no"
                  + " from order_timeliness_fact where source_record_id = ?",
              sourceRecordId);

      boolean same =
          existing != null
              && eqTs(existing.get("ordered_at", OffsetDateTime.class), orderedAt)
              && eqTs(existing.get("packed_at", OffsetDateTime.class), packedAt)
              && eqTs(existing.get("handed_over_at", OffsetDateTime.class), handedOverAt)
              && eqText(existing.get("shop_ref"), r.get("shop_ref"))
              && eqText(existing.get("warehouse_ref"), r.get("warehouse_ref"))
              && eqText(existing.get("carrier_code"), r.get("carrier_code"))
              && eqTs(
                  existing.get("ship_sla_deadline_at", OffsetDateTime.class),
                  r.get("sla_deadline_at", OffsetDateTime.class))
              && eqUuid(existing.get("sales_order_id", UUID.class), salesOrderId);
      if (same) {
        unchanged++;
        continue;
      }

      UUID factId;
      if (existing == null) {
        factId = UUID.randomUUID();
        sql.execute(
            "insert into order_timeliness_fact"
                + " (id,tenant_id,fact_no,source_record_id,source_record_no,"
                + "  sales_order_id,sales_order_no,shop_ref,warehouse_ref,carrier_code,carrier_name,"
                + "  ordered_at,packed_at,handed_over_at,"
                + "  ship_sla_deadline_at,ship_sla_source,synced_at)"
                // 四个时刻列一律写 ?::timestamptz：其中三个可空，
                // 裸 ? 在值为 NULL 时 PostgreSQL 推不出类型会直接报错
                // 五个时刻列（ordered/packed/handed_over/ship_sla_deadline/synced）
                // 一律写 ?::timestamptz：其中四个可空，裸 ? 在值为 NULL 时
                // PostgreSQL 推不出类型；synced_at 则是 jOOQ 会按 varchar 发，
                // 不加转换报「timestamp with time zone but expression is character varying」
                + " values (?,?,?,?,?,?,?,?,?,?,?,"
                + "?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz,?,?::timestamptz)",
            factId,
            currentTenant(),
            nextFactNo(),
            sourceRecordId,
            text(r.get("record_no")),
            salesOrderId,
            text(r.get("sales_order_no")),
            text(r.get("shop_ref")),
            text(r.get("warehouse_ref")),
            text(r.get("carrier_code")),
            text(r.get("carrier_name")),
            orderedAt,
            packedAt,
            handedOverAt,
            r.get("sla_deadline_at", OffsetDateTime.class),
            text(r.get("sla_source")),
            now);
        inserted++;
      } else {
        factId = existing.get("id", UUID.class);
        // ⚠️ 刻意**不覆盖**承诺到货 / 实际到货两列：它们是本模块登记的凭据快照，
        // 上游重同步没有理由改掉一条人工登记。同一事务里其它列照常刷新。
        sql.execute(
            "update order_timeliness_fact set"
                + " source_record_no=?::varchar,sales_order_id=?,sales_order_no=?,"
                + " shop_ref=?,warehouse_ref=?,carrier_code=?,carrier_name=?,"
                + " ordered_at=?::timestamptz,packed_at=?::timestamptz,handed_over_at=?::timestamptz,"
                + " ship_sla_deadline_at=?::timestamptz,ship_sla_source=?,synced_at=?,"
                + " updated_at=now()"
                + " where id=?",
            text(r.get("record_no")),
            salesOrderId,
            text(r.get("sales_order_no")),
            text(r.get("shop_ref")),
            text(r.get("warehouse_ref")),
            text(r.get("carrier_code")),
            text(r.get("carrier_name")),
            orderedAt,
            packedAt,
            handedOverAt,
            r.get("sla_deadline_at", OffsetDateTime.class),
            text(r.get("sla_source")),
            now,
            factId);
        updated++;
      }

      appendEvent(
          factId,
          "FACT_SYNCED",
          "同步发货记录 " + text(r.get("record_no")) + " 的时效事实",
          null,
          now);
    }

    return new TimelinessSyncResult(
        true,
        scanned,
        inserted,
        updated,
        unchanged,
        withOrderedAt,
        withoutOrderedAt,
        withPackedAt,
        withoutPackedAt,
        withoutOrderSnapshot,
        skippedTimeInversion,
        "来源全部在 trade 库内（9.4.1.2 发货记录 + 9.4.1.1 待发货 + V99 销售订单），"
            + "本模块**没有跨服务调用**，所以没有「上游不可达」这条路径；"
            + "缺一个端点就如实落 UNSET 桶。旧行只报数不删除（可能已带承诺/实际到货登记）");
  }

  // ================================================================ 报表

  /**
   * 时效报表：四段逐段出统计 + 发货超时三桶 + 承诺 vs 实际四桶。
   *
   * <p>口径全部在这里<b>一处</b>定义，界面不自算。
   * 每一段都同时给 {@code measuredCount} 与 {@code unsetCount} ——
   * 只给一个「平均 3.2 天」而不说分母里有多少票根本没测出来，
   * 这个数字就没法用来做任何判断。
   */
  public OrderTimelinessReport report(
      String shopRef, String warehouseRef, String carrierCode, OffsetDateTime from, OffsetDateTime to) {
    Params f = baseFilters(shopRef, warehouseRef, carrierCode);
    if (from != null) {
      f.clause.append(" and f.handed_over_at >= ?::timestamptz");
      f.params.add(from);
    }
    if (to != null) {
      f.clause.append(" and f.handed_over_at < ?::timestamptz");
      f.params.add(to);
    }
    long total = count("select count(*)" + FACT_FROM + " where true" + f.clause, f.params);

    List<TimelinessSegmentStats> segments = new ArrayList<>();
    for (TimelinessSegment segment : TimelinessSegment.values()) {
      segments.add(segmentStats(segment, f, total));
    }

    return new OrderTimelinessReport(
        segments,
        total,
        from == null ? null : from.toString(),
        to == null ? null : to.toString(),
        slaBreakdown(f),
        promiseBreakdown(f),
        "「承诺到货 vs 实际到货」两端的真实来源是承运商轨迹（10.3 未交付），"
            + "本模块只能登记：未登记落 NO_PROMISE / NO_ACTUAL 两个独立桶，"
            + "**不并进达成桶**。达成率的分母只算两端都登记了的票 —— "
            + "把没登记的当准时，准时率会凭空变成 100%");
  }

  /** 单段统计。均值取 numeric，中位数/90 分位取 percentile_disc（离散精确值，无浮点）。 */
  private TimelinessSegmentStats segmentStats(TimelinessSegment segment, Params f, long total) {
    String col = segment.column;
    Record r =
        sql.fetchOne(
            "select count(*) filter (where f." + col + " is not null) as measured,"
                + " count(*) filter (where f." + col + " is null) as unset,"
                + " avg(f." + col + ") as avg_v,"
                + " percentile_disc(0.5) within group (order by f." + col + ") as p50,"
                + " percentile_disc(0.9) within group (order by f." + col + ") as p90,"
                + " min(f." + col + ") as min_v,"
                + " max(f." + col + ") as max_v,"
                + " count(*) filter (where f." + col + " < 0) as negatives"
                + " "
                + FACT_FROM
                + " where true"
                + f.clause,
            f.params.toArray());
    return new TimelinessSegmentStats(
        segment.name(),
        segment.displayName,
        r.get("measured", Long.class),
        r.get("unset", Long.class),
        r.get("avg_v", BigDecimal.class),
        r.get("p50", Long.class),
        r.get("p90", Long.class),
        r.get("min_v", Long.class),
        r.get("max_v", Long.class),
        r.get("negatives", Long.class));
  }

  private TimelinessSlaBreakdown slaBreakdown(Params f) {
    Record r =
        sql.fetchOne(
            "select count(*) filter (where f.ship_sla_state='UNSET') as unset,"
                + " count(*) filter (where f.ship_sla_state='ON_TIME') as on_time,"
                + " count(*) filter (where f.ship_sla_state='OVERDUE') as overdue"
                + FACT_FROM
                + " where true"
                + f.clause,
            f.params.toArray());
    long unset = r.get("unset", Long.class);
    long onTime = r.get("on_time", Long.class);
    long overdue = r.get("overdue", Long.class);
    return new TimelinessSlaBreakdown(
        unset,
        onTime,
        overdue,
        ratio(overdue, onTime + overdue),
        "超时率分母 = 按时 + 超时（**不含未登记时限**）。"
            + "把没时限的当准时，超时率会系统性偏低");
  }

  private TimelinessPromiseBreakdown promiseBreakdown(Params f) {
    Record r =
        sql.fetchOne(
            "select count(*) filter (where f.promise_vs_delivery_state='MET') as met,"
                + " count(*) filter (where f.promise_vs_delivery_state='LATE') as late,"
                + " count(*) filter (where f.promise_vs_delivery_state='NO_PROMISE') as no_promise,"
                + " count(*) filter (where f.promise_vs_delivery_state='NO_ACTUAL') as no_actual,"
                + " avg(f.promise_vs_delivery_seconds)"
                + "   filter (where f.promise_vs_delivery_state='LATE') as late_avg"
                + FACT_FROM
                + " where true"
                + f.clause,
            f.params.toArray());
    long met = r.get("met", Long.class);
    long late = r.get("late", Long.class);
    return new TimelinessPromiseBreakdown(
        met,
        late,
        r.get("no_promise", Long.class),
        r.get("no_actual", Long.class),
        ratio(met, met + late),
        r.get("late_avg", BigDecimal.class),
        "达成率分母 = 达成 + 超出（**不含两端任一没登记**）。"
            + "NO_PROMISE（我们没登记承诺）与 NO_ACTUAL（货还没签收）刻意分成两桶");
  }

  // ================================================================ 分维度对比

  /**
   * 分维度对比。四个维度共用一套 SQL 骨架，差别只在 GROUP BY 表达式。
   *
   * <p>商品维度是<b>唯一</b>走订单行 join 的一维（V131 第五节）。
   */
  public TimelinessDimensionPage dimensions(
      TimelinessDimension dimension,
      String shopRef,
      String warehouseRef,
      String carrierCode,
      OffsetDateTime from,
      OffsetDateTime to,
      int limit) {
    int cap = limit > 0 ? Math.min(limit, MAX_PAGE_SIZE) : 50;
    Params f = baseFilters(shopRef, warehouseRef, carrierCode);
    if (from != null) {
      f.clause.append(" and f.handed_over_at >= ?::timestamptz");
      f.params.add(from);
    }
    if (to != null) {
      f.clause.append(" and f.handed_over_at < ?::timestamptz");
      f.params.add(to);
    }

    boolean sku = dimension == TimelinessDimension.SKU;
    String dimKey;
    String dimLabel;
    String groupTail;
    if (sku) {
      // 左连接保留「没有订单快照」的票：它们进不了 SKU 维度，
      // 用 without_order_snapshot 如实报数，而不是用占位 SKU 把行补齐。
      dimKey = "l.sku_id::text";
      dimLabel = "coalesce(nullif(l.sku_name,''), l.sku_code)";
      groupTail = " group by l.sku_id, l.sku_code, l.sku_name";
    } else {
      dimKey =
          switch (dimension) {
            case SHOP -> "f.shop_ref";
            case WAREHOUSE -> "f.warehouse_ref";
            case CARRIER -> "f.carrier_code";
            case SKU -> throw new IllegalStateException();
          };
      dimLabel =
          switch (dimension) {
            case SHOP -> "f.shop_ref";
            case WAREHOUSE -> "f.warehouse_ref";
            case CARRIER -> "coalesce(nullif(f.carrier_name,''), f.carrier_code)";
            case SKU -> throw new IllegalStateException();
          };
      groupTail =
          dimension == TimelinessDimension.CARRIER
              ? " group by f.carrier_code, f.carrier_name"
              : " group by " + dimKey;
    }

    String sqlFrom =
        FACT_FROM
            + (sku ? " left join sales_order_line l on l.order_id = f.sales_order_id"
                    + " and l.tenant_id = f.tenant_id"
                : "")
            + " where true"
            + f.clause
            + groupTail;

    List<Record> rows =
        sql.fetch(
            "select " + dimKey + " as dim_key," + dimLabel + " as dim_label,"
                + " count(*) as fact_count,"
                + " count(*) filter (where f.order_to_handover_seconds is not null) as measured,"
                + " count(*) filter (where f.order_to_handover_seconds is null) as unset,"
                + " avg(f.order_to_pack_seconds) as avg_o2p,"
                + " avg(f.pack_to_handover_seconds) as avg_p2h,"
                + " avg(f.order_to_handover_seconds) as avg_o2h,"
                + " max(f.order_to_handover_seconds) as worst_o2h,"
                + " max(f.ship_overdue_seconds) as worst_overdue,"
                + " count(*) filter (where f.ship_sla_state='OVERDUE') as overdue_count,"
                + " count(*) filter (where f.sales_order_id is null) as without_snapshot"
                + " "
                + sqlFrom
                + " order by avg_o2h desc nulls last, fact_count desc"
                + " limit ?",
            concat(f.params, cap));

    List<TimelinessDimensionRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(
          new TimelinessDimensionRow(
              dimension.name(),
              text(r.get("dim_key")),
              text(r.get("dim_label")),
              num(r, "fact_count"),
              num(r, "measured"),
              num(r, "unset"),
              r.get("avg_o2p", BigDecimal.class),
              r.get("avg_p2h", BigDecimal.class),
              r.get("avg_o2h", BigDecimal.class),
              r.get("worst_o2h", Long.class),
              r.get("worst_overdue", Long.class),
              num(r, "overdue_count"),
              num(r, "without_snapshot")));
    }

    String caveat =
        sku
            ? "商品维度是同服务 sales_order_line 的**只读 join**（按 sales_order_id）。"
                + "sales_order_id 为空的票进不了这个维度，计入 without_order_snapshot —— "
                + "如实报数，不用占位 SKU 把行补齐"
            : "空值维度单独成组（如 shop_ref='' = 没有店铺归属的货），"
                + "**不并进任何有值分组**：混进真实店铺会让那个店铺的时效凭空变好";

    return new TimelinessDimensionPage(
        dimension, dimension.displayName, items, items.size(), caveat);
  }

  // ================================================================ 超时明细

  /**
   * 超时明细：发货超时 + 承诺超时两个来源合成一张清单。
   *
   * <p>两个来源刻意<b>分列报数</b>：发货超时用的是 9.4.1.2 冻结的发货时限
   * （平台要求「几天内发出」），承诺超时用的是本模块登记的到货承诺
   * （平台要求「哪天到货」）。这是两件事，把它们混成一个「超时数」
   * 就没法回答「我们迟发了吗」。
   */
  public TimelinessOverduePage overdue(
      String shopRef, String warehouseRef, String carrierCode, int page, int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    Params f = baseFilters(shopRef, warehouseRef, carrierCode);

    String where = " where true" + f.clause + " and (f.ship_overdue_seconds is not null"
        + "      or f.promise_vs_delivery_state = 'LATE')";
    long total = count("select count(*)" + FACT_FROM + where, f.params);

    List<Record> rows =
        sql.fetch(
            "select * from ("
                + " select f.id,f.fact_no,f.sales_order_no,f.shop_ref,f.warehouse_ref,"
                + " f.carrier_code,f.carrier_name,f.handed_over_at,f.order_to_handover_seconds,"
                + " 'SHIP_SLA'::text as overdue_kind, f.ship_overdue_seconds as overdue_seconds,"
                + " f.ship_sla_deadline_at as deadline_at, f.promise_vs_delivery_state"
                + FACT_FROM + where + " and f.ship_overdue_seconds is not null"
                + " union all"
                + " select f.id,f.fact_no,f.sales_order_no,f.shop_ref,f.warehouse_ref,"
                + " f.carrier_code,f.carrier_name,f.handed_over_at,f.order_to_handover_seconds,"
                + " 'PROMISE'::text as overdue_kind,"
                + " f.promise_vs_delivery_seconds as overdue_seconds,"
                + " f.promised_delivery_at as deadline_at, f.promise_vs_delivery_state"
                + FACT_FROM + where + " and f.promise_vs_delivery_state = 'LATE'"
                + " ) u order by u.overdue_seconds desc nulls last, u.fact_no"
                + " limit ? offset ?",
            concat(merge(f.params, f.params), s, (long) p * s));

    List<TimelinessOverdueRow> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(
          new TimelinessOverdueRow(
              r.get("id", UUID.class),
              text(r.get("fact_no")),
              text(r.get("sales_order_no")),
              text(r.get("shop_ref")),
              text(r.get("warehouse_ref")),
              text(r.get("carrier_code")),
              text(r.get("carrier_name")),
              r.get("handed_over_at", OffsetDateTime.class),
              r.get("order_to_handover_seconds", Long.class),
              text(r.get("overdue_kind")),
              r.get("overdue_seconds", Long.class),
              r.get("deadline_at", OffsetDateTime.class),
              text(r.get("promise_vs_delivery_state"))));
    }

    Params c = baseFilters(shopRef, warehouseRef, carrierCode);
    Record counts =
        sql.fetchOne(
            "select count(*) filter (where f.ship_overdue_seconds is not null) as ship,"
                + " count(*) filter (where f.promise_vs_delivery_state='LATE') as late,"
                + " count(*) filter (where f.promise_vs_delivery_state in"
                + "   ('NO_PROMISE','NO_ACTUAL')) as unmeasured"
                + FACT_FROM
                + " where true"
                + c.clause,
            c.params.toArray());

    return new TimelinessOverduePage(
        items,
        p,
        s,
        total,
        totalPages(total, s),
        num(counts, "ship"),
        num(counts, "late"),
        num(counts, "unmeasured"));
  }

  // ================================================================ 事实清单

  /** 事实清单：真分页 + 真筛选 + 排序白名单 + 汇总（汇总不受分页影响）。 */
  public OrderTimelinessFactPage facts(
      String shopRef,
      String warehouseRef,
      String carrierCode,
      String shipSlaState,
      String promiseState,
      String keyword,
      TimelinessSort sort,
      int page,
      int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    Params f = baseFilters(shopRef, warehouseRef, carrierCode);
    if (!blank(shipSlaState)) {
      f.clause.append(" and f.ship_sla_state = ?::varchar");
      f.params.add(shipSlaState);
    }
    if (!blank(promiseState)) {
      f.clause.append(" and f.promise_vs_delivery_state = ?::varchar");
      f.params.add(promiseState);
    }
    keywordFilter(keyword, f);

    long total = count("select count(*)" + FACT_FROM + " where true" + f.clause, f.params);
    TimelinessSort srt = sort == null ? TimelinessSort.HANDOVER_AT_DESC : sort;

    List<Record> rows =
        sql.fetch(
            "select " + FACT_COLUMNS + FACT_FROM + " where true" + f.clause
                + " order by " + SORT_SQL.get(srt) + " limit ? offset ?",
            concat(f.params, s, (long) p * s));

    return new OrderTimelinessFactPage(
        rows.stream().map(OrderTimelinessService::factRow).toList(),
        p,
        s,
        total,
        totalPages(total, s),
        summary(f));
  }

  private TimelinessFactSummary summary(Params f) {
    Record r =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where f.ordered_at is not null) as with_ordered,"
                + " count(*) filter (where f.ordered_at is null) as without_ordered,"
                + " count(*) filter (where f.packed_at is not null) as with_packed,"
                + " count(*) filter (where f.packed_at is null) as without_packed,"
                + " count(*) filter (where f.sales_order_id is not null) as with_snapshot,"
                + " count(*) filter (where f.sales_order_id is null) as without_snapshot,"
                + " count(*) filter (where f.ship_sla_state='UNSET') as sla_unset,"
                + " count(*) filter (where f.ship_sla_state='ON_TIME') as on_time,"
                + " count(*) filter (where f.ship_sla_state='OVERDUE') as overdue,"
                + " count(*) filter (where f.promise_vs_delivery_state='NO_PROMISE') as no_promise,"
                + " count(*) filter (where f.promise_vs_delivery_state='NO_ACTUAL') as no_actual,"
                + " count(*) filter (where f.promise_vs_delivery_state='MET') as met,"
                + " count(*) filter (where f.promise_vs_delivery_state='LATE') as late,"
                + " count(*) filter (where f.order_to_pack_seconds is null) as un_o2p,"
                + " count(*) filter (where f.pack_to_handover_seconds is null) as un_p2h,"
                + " count(*) filter (where f.promise_vs_delivery_seconds is null) as un_p2d"
                + FACT_FROM
                + " where true"
                + f.clause,
            f.params.toArray());
    return new TimelinessFactSummary(
        num(r, "total"),
        num(r, "with_ordered"),
        num(r, "without_ordered"),
        num(r, "with_packed"),
        num(r, "without_packed"),
        num(r, "with_snapshot"),
        num(r, "without_snapshot"),
        num(r, "sla_unset"),
        num(r, "on_time"),
        num(r, "overdue"),
        num(r, "no_promise"),
        num(r, "no_actual"),
        num(r, "met"),
        num(r, "late"),
        num(r, "un_o2p"),
        num(r, "un_p2h"),
        num(r, "un_p2d"));
  }

  // ================================================================ 明细

  /** 明细：事实 + 订单行 + 全部凭据 + 时间线 + 边界声明。 */
  public OrderTimelinessDetail fact(String id) {
    UUID factId = parseUuid(id);
    Record row = sql.fetchOne("select " + FACT_COLUMNS + FACT_FROM + " where f.id = ?", factId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "时效事实不存在或不属于当前租户");
    }

    List<OrderTimelinessOrderLine> lines =
        sql.fetch(
                "select l.sku_id,l.sku_code,l.sku_name,l.spec_snapshot,l.quantity"
                    + " from sales_order_line l"
                    + " join order_timeliness_fact f"
                    + "   on f.sales_order_id = l.order_id and f.tenant_id = l.tenant_id"
                    + " where f.id = ? order by l.line_no",
                factId)
            .stream()
            .map(
                r ->
                    new OrderTimelinessOrderLine(
                        r.get("sku_id", UUID.class),
                        text(r.get("sku_code")),
                        text(r.get("sku_name")),
                        text(r.get("spec_snapshot")),
                        r.get("quantity", BigDecimal.class)))
            .toList();

    List<TimelinessPromiseCredential> promises =
        sql.fetch(
                "select id,promised_delivery_at,source,note,registered_by_membership_id,"
                    + "registered_at from order_timeliness_promise"
                    + " where fact_id=? order by promised_delivery_at desc, created_at desc",
                factId)
            .stream()
            .map(
                r ->
                    new TimelinessPromiseCredential(
                        r.get("id", UUID.class),
                        r.get("promised_delivery_at", OffsetDateTime.class),
                        text(r.get("source")),
                        text(r.get("note")),
                        r.get("registered_by_membership_id", UUID.class),
                        r.get("registered_at", OffsetDateTime.class)))
            .toList();

    List<TimelinessDeliveryCredential> deliveries =
        sql.fetch(
                "select id,delivered_at,state,source,note,registered_by_membership_id,"
                    + "registered_at from order_timeliness_delivery"
                    + " where fact_id=? order by delivered_at desc, created_at desc",
                factId)
            .stream()
            .map(
                r ->
                    new TimelinessDeliveryCredential(
                        r.get("id", UUID.class),
                        r.get("delivered_at", OffsetDateTime.class),
                        text(r.get("state")),
                        text(r.get("source")),
                        text(r.get("note")),
                        r.get("registered_by_membership_id", UUID.class),
                        r.get("registered_at", OffsetDateTime.class)))
            .toList();

    return new OrderTimelinessDetail(
        factRow(row),
        lines,
        promises,
        deliveries,
        timeline(factId, 0, 50),
        List.of(
            "下单时刻取 sales_order.submitted_at（提交时刻），不是 created_at（建单时刻）："
                + "把草稿停留算进履约时效会把人的录入时长当成履约时长",
            "出库时刻取同服务 shipment_pending_unit.packed_at；出库单在 warehouse 库里，"
                + "本模块一条 SELECT 都不碰 warehouse 库（AGENTS.md 禁止跨服务 SQL）",
            "承诺到货与实际到货是**登记**来的：真实来源是承运商轨迹（10.3 未交付），"
                + "本模块不抓取轨迹；凭据 append-only，改一次留一行",
            "时长是整数秒，由 DB 的 GENERATED 列复算，应用层算错也改不动；"
                + "**读不到就是 UNSET 而不是 0**"));
  }

  // ================================================================ 登记（append-only）

  /**
   * 登记承诺到货。
   *
   * <p>顺序被 DB 触发器钉死成「先落凭据行、再更新快照」，同一个事务里完成；
   * 绕过服务层直接写快照也会被 {@code guard_order_timeliness_snapshots} 拒。
   */
  @Transactional
  public OrderTimelinessDetail registerPromise(
      String id, TimelinessPromiseInput input, UUID membership) {
    UUID factId = requireFact(id);
    OffsetDateTime now = OffsetDateTime.now();

    sql.execute(
        "insert into order_timeliness_promise"
            + " (id,tenant_id,fact_id,promised_delivery_at,source,note,"
            + "  registered_by_membership_id,registered_at)"
            + " values (?,?,?,?::timestamptz,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        factId,
        input.promisedDeliveryAt(),
        input.source(),
        input.note() == null ? "" : input.note(),
        membership,
        now);

    sql.execute(
        "update order_timeliness_fact set"
            + " promised_delivery_at=?::timestamptz,delivery_promise_state='REGISTERED',"
            + " promise_source=?,promise_registered_at=?::timestamptz,"
            + " promise_by_membership_id=?,promise_note=?,updated_at=now()"
            + " where id=?",
        input.promisedDeliveryAt(),
        input.source(),
        now,
        membership,
        input.note() == null ? "" : input.note(),
        factId);

    appendEvent(
        factId,
        "PROMISE_REGISTERED",
        "登记承诺到货 " + input.promisedDeliveryAt() + "（来源 " + input.source() + "）",
        membership,
        now);
    return fact(id);
  }

  /**
   * 登记实际到货。
   *
   * <p>{@code NOT_DELIVERED} 强制带原因（拒收 / 退回 / 丢失是三件不同的事），
   * 且此时 {@code deliveredAt} 表示「已确认未送达的时刻」而不是签收时刻。
   */
  @Transactional
  public OrderTimelinessDetail registerDelivery(
      String id, TimelinessDeliveryInput input, UUID membership) {
    UUID factId = requireFact(id);
    OffsetDateTime now = OffsetDateTime.now();

    // 「未送达」缺原因在**服务层**就拒并说人话：DB 的 CHECK 只会回一个
    // 通用 409，而拒收 / 退回 / 丢失三者在平台申诉里要的举证完全不同，
    // 现场需要知道是哪一种，而不是「记录仍被引用」这种没有指向的话。
    if ("NOT_DELIVERED".equals(input.state())
        && (input.note() == null || input.note().isBlank())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "未送达必须写明原因：拒收 / 退回 / 丢失是三件不同的事");
    }

    sql.execute(
        "insert into order_timeliness_delivery"
            + " (id,tenant_id,fact_id,delivered_at,state,source,note,"
            + "  registered_by_membership_id,registered_at)"
            // 9 列 ↔ 9 个绑定值。delivered_at 与 registered_at 两个时刻列写
            // ?::timestamptz：前者可空（裸 ? 推不出类型），后者不加转换会被
            // jOOQ 按 varchar 绑定而报「uuid to timestamp with time zone」
            + " values (?,?,?,?::timestamptz,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        factId,
        input.deliveredAt(),
        input.state(),
        input.source(),
        input.note() == null ? "" : input.note(),
        membership,
        now);

    sql.execute(
        "update order_timeliness_fact set"
            + " actual_delivered_at=?::timestamptz,delivery_state=?,delivery_source=?,"
            + " delivery_registered_at=?::timestamptz,delivery_by_membership_id=?,"
            + " delivery_note=?,updated_at=now()"
            + " where id=?",
        input.deliveredAt(),
        input.state(),
        input.source(),
        now,
        membership,
        input.note() == null ? "" : input.note(),
        factId);

    appendEvent(
        factId,
        "DELIVERY_REGISTERED",
        "登记实际到货 " + input.deliveredAt() + "（" + input.state() + "）",
        membership,
        now);
    return fact(id);
  }

  // ================================================================ 事件流

  public OrderTimelinessEventPage events(String kind, String factId, int page, int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    String clause = "";
    List<Object> params = new ArrayList<>();
    if (!blank(kind)) {
      clause = clause + " and kind = ?::varchar";
      params.add(kind);
    }
    if (!blank(factId)) {
      clause = clause + " and fact_id = ?::uuid";
      params.add(parseUuid(factId));
    }
    long total =
        count("select count(*) from order_timeliness_event where true" + clause, params);

    List<OrderTimelinessEventRow> items =
        sql.fetch(
                "select seq,fact_id,kind,summary,actor_membership_id,occurred_at"
                    + " from order_timeliness_event where true" + clause
                    + " order by seq desc limit ? offset ?",
                concat(params, s, (long) p * s))
            .stream()
            .map(
                r ->
                    new OrderTimelinessEventRow(
                        num(r, "seq"),
                        r.get("fact_id", UUID.class),
                        text(r.get("kind")),
                        text(r.get("summary")),
                        r.get("actor_membership_id", UUID.class),
                        r.get("occurred_at", OffsetDateTime.class)))
            .toList();
    return new OrderTimelinessEventPage(items, p, s, total, totalPages(total, s));
  }

  private List<OrderTimelinessEventRow> timeline(UUID factId, int page, int size) {
    return events(null, factId.toString(), page, size).items();
  }

  // ================================================================ 筛选与小工具

  /** 筛选条件 + 绑定参数。**租户谓词刻意不写**：隔离由 FORCE RLS 在 DB 层完成。 */
  private static final class Params {
    final StringBuilder clause = new StringBuilder();
    final List<Object> params = new ArrayList<>();
  }

  private static Params baseFilters(String shopRef, String warehouseRef, String carrierCode) {
    Params f = new Params();
    if (!blank(shopRef)) {
      f.clause.append(" and f.shop_ref = ?::varchar");
      f.params.add(shopRef);
    }
    if (!blank(warehouseRef)) {
      f.clause.append(" and f.warehouse_ref = ?::varchar");
      f.params.add(warehouseRef);
    }
    if (!blank(carrierCode)) {
      f.clause.append(" and f.carrier_code = ?::varchar");
      f.params.add(carrierCode);
    }
    return f;
  }

  /** 关键字筛选：单号 / 店铺 / 仓库 / 承运商，六个可读列的 OR。 */
  private static void keywordFilter(String keyword, Params f) {
    if (blank(keyword)) {
      return;
    }
    String like = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
    f.clause.append(
        " and (lower(f.fact_no) like ?::varchar"
            + " or lower(f.sales_order_no) like ?::varchar"
            + " or lower(f.shop_ref) like ?::varchar"
            + " or lower(f.warehouse_ref) like ?::varchar"
            + " or lower(f.carrier_code) like ?::varchar"
            + " or lower(f.source_record_no) like ?::varchar)");
    for (int i = 0; i < 6; i++) {
      f.params.add(like);
    }
  }

  private static Object[] concat(List<Object> a, Object... rest) {
    Object[] out = new Object[a.size() + rest.length];
    int i = 0;
    for (Object o : a) {
      out[i++] = o;
    }
    for (Object o : rest) {
      out[i++] = o;
    }
    return out;
  }

  private static Object[] concat(List<Object> a, List<Object> b) {
    Object[] out = new Object[a.size() + b.size()];
    int i = 0;
    for (Object o : a) {
      out[i++] = o;
    }
    for (Object o : b) {
      out[i++] = o;
    }
    return out;
  }

  /** 把两段绑定参数首尾相接（超时明细的 UNION 两侧共用同一组筛选绑定）。 */
  private static List<Object> merge(List<Object> a, List<Object> b) {
    List<Object> out = new ArrayList<>(a.size() + b.size());
    out.addAll(a);
    out.addAll(b);
    return out;
  }

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private String nextFactNo() {
    String day = LocalDate.now(BUSINESS_ZONE).toString().replace("-", "");
    Long seq =
        sql.fetchOne("select nextval('order_timeliness_no_seq')").get(0, Long.class);
    return "TL-" + day + "-" + String.format("%06d", seq == null ? 0L : seq);
  }

  private long count(String sqlText, List<Object> params) {
    Long v = sql.fetchOne(sqlText, params.toArray()).get(0, Long.class);
    return v == null ? 0L : v;
  }

  /**
   * 标量读数统一走 {@code fetchOne + get(0, Type)}。
   *
   * <p><b>不能</b>用 {@code fetchValue(sql, Class, args)}：本仓库的 jOOQ 版本会把那个
   * {@code Class} 当成绑定值（PendingShipmentService 与 warehouse 的 PickTaskService
   * 已在文件里记过同一件事），所以这里一次性把标量读法收口，不在每个调用点各踩一次。
   */

  private static int totalPages(long total, int size) {
    return (int) Math.max(1, (total + size - 1) / size);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static String text(Object o) {
    return o == null ? "" : o.toString();
  }

  private static long num(Record r, String column) {
    Long v = r.get(column, Long.class);
    return v == null ? 0L : v;
  }

  private static boolean eqText(Object a, Object b) {
    return text(a).equals(text(b));
  }

  private static boolean eqUuid(UUID a, UUID b) {
    return a == null ? b == null : a.equals(b);
  }

  /** 时间比较按<b>瞬时</b>（epoch 秒）而不是对象相等，避免同一时刻两种 Offset 误判为不等。 */
  private static boolean eqTs(OffsetDateTime a, OffsetDateTime b) {
    return a == null ? b == null : a.toInstant().equals(b.toInstant());
  }

  private static BigDecimal ratio(long part, long basis) {
    return basis == 0 ? null : BigDecimal.valueOf(part).multiply(HUNDRED)
        .divide(BigDecimal.valueOf(basis), 2, RoundingMode.HALF_UP);
  }

  private static OrderTimelinessFactRow factRow(Record r) {
    return new OrderTimelinessFactRow(
        r.get("id", UUID.class),
        text(r.get("fact_no")),
        r.get("source_record_id", UUID.class),
        text(r.get("source_record_no")),
        r.get("sales_order_id", UUID.class),
        text(r.get("sales_order_no")),
        text(r.get("shop_ref")),
        text(r.get("warehouse_ref")),
        text(r.get("carrier_code")),
        text(r.get("carrier_name")),
        r.get("ordered_at", OffsetDateTime.class),
        r.get("packed_at", OffsetDateTime.class),
        r.get("handed_over_at", OffsetDateTime.class),
        r.get("order_to_pack_seconds", Long.class),
        r.get("pack_to_handover_seconds", Long.class),
        r.get("order_to_handover_seconds", Long.class),
        r.get("ship_sla_deadline_at", OffsetDateTime.class),
        text(r.get("ship_sla_source")),
        r.get("ship_overdue_seconds", Long.class),
        text(r.get("ship_sla_state")),
        r.get("promised_delivery_at", OffsetDateTime.class),
        text(r.get("promise_source")),
        r.get("actual_delivered_at", OffsetDateTime.class),
        text(r.get("delivery_state")),
        r.get("promise_vs_delivery_seconds", Long.class),
        text(r.get("promise_vs_delivery_state")),
        r.get("synced_at", OffsetDateTime.class));
  }

  private UUID requireFact(String id) {
    UUID factId = parseUuid(id);
    if (sql.fetchOne("select 1 from order_timeliness_fact where id = ?", factId) == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "时效事实不存在或不属于当前租户");
    }
    return factId;
  }

  private static UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 不是合法 UUID");
    }
  }

  private void appendEvent(
      UUID factId, String kind, String summary, UUID membership, OffsetDateTime when) {
    sql.execute(
        "insert into order_timeliness_event"
            + " (id,tenant_id,fact_id,kind,summary,actor_membership_id,occurred_at)"
            + " values (?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        factId,
        kind,
        summary.length() > 300 ? summary.substring(0, 300) : summary,
        membership,
        when);
  }
}
