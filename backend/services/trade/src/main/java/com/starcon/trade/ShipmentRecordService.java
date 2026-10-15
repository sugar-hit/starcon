package com.starcon.trade;

import static com.starcon.trade.ShipmentRecordModels.*;

import com.starcon.trade.ShipmentHandoverQueryPort.HandoverParcelView;
import com.starcon.trade.ShipmentHandoverQueryPort.HandoverView;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
 * 9.4.1.2 发货记录。
 *
 * <p><b>七条实现纪律</b>（照抄同 L3 兄弟 9.4.1.1 的写法，不另起风格）：
 *
 * <ol>
 *   <li><b>「已发货」只有一个来源</b>：8.3.4.1 已确认交接的交接单
 *       （{@link ShipmentHandoverQueryPort}）。跨库视图、跨服务 SQL、跨服务 FK
 *       一律不做 —— 见 V127 文件头第一节。取不到就 503，绝不返回空清单冒充
 *       「今天没有发货」。</li>
 *   <li><b>不伪造发货时刻</b>：{@code shipped_at} 取上游 {@code handover_at}
 *       快照，同步入参**不接受**调用方指定发货时间 —— 那会让迟发率当场失去意义。</li>
 *   <li><b>三个状态列登记而不伪造</b>：轨迹（10.3 未交付）、买家可见（无可读上游）、
 *       平台回传（9.4.2.1 / 2.5.1.1 未交付）。未登记的落<b>独立 UNSET 桶</b>，
 *       绝不并进正常桶；快照列由 DB 触发器钉死「必须等于最新凭据行」。</li>
 *   <li><b>「跳过」与「漏了」分开</b>：平台回传的 SKIPPED 必须带原因，
 *       UNSET 是压根没登记。合并两者等于让「漏回传」伪装成「按策略不回传」。</li>
 *   <li><b>绩效口径写在服务端</b>：迟发率与有效追踪率的定义在
 *       {@link #performance} 一处，不散到前端或报表 —— 同一个词两处定义，
 *       事后没人说得清哪个是真的。两者都<b>不依赖墙上时钟</b>，只比对已落库的
 *       两个时刻。</li>
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 写在 values 的对应位置上，
 *       全程 {@code OffsetDateTime}，不用 {@code Timestamp.valueOf(LocalDateTime)}
 *       （那会静默偏 −28800s）。</li>
 * </ol>
 */
@Service
public class ShipmentRecordService {

  /** 单页最大行数（与 9.4.1.1 / 9.6.1.2 同一上限）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 同步单次最多取多少张交接单 —— 一次拉爆会把请求线程占死。 */
  static final int MAX_SYNC_PAGE = 500;

  private static final BigDecimal ZERO = new BigDecimal("0");

  private static final String RECORD_COLUMNS =
      "r.id,r.record_no,r.source_handover_id,r.source_handover_code,r.outbound_order_no,"
          + "r.sales_order_id,r.sales_order_no,r.shop_ref,r.buyer_name,r.destination_country,"
          + "r.warehouse_ref,r.carrier_code,r.carrier_name,r.master_waybill_no,r.shipped_at,"
          + "r.sla_deadline_at,r.parcel_count,r.total_quantity,r.total_gross_weight_g,r.status,"
          + "r.tracking_status,r.buyer_visible_status,r.platform_callback_status,r.synced_at";

  private static final String PARCEL_COLUMNS =
      "p.id,p.record_id,p.parcel_no,p.source_parcel_id,p.parcel_code,p.waybill_no,"
          + "p.gross_weight_g,p.volume_cbm,p.item_count,p.total_quantity,p.current_tracking_status";

  /**
   * 「有没有过轨迹观测」判据。
   *
   * <p>刻意同时看整票级与逐箱级观测：真实轨迹是按运单号走的，一票多箱里
   * 往往只有一箱有轨迹。只看整票级会把「已有一箱在途」误判成没追踪。
   */
  private static final String HAS_TRACKING_SQL =
      "exists (select 1 from shipment_record_tracking_observation o"
          + " where o.tenant_id = r.tenant_id and o.record_id = r.id)";

  /** 迟发判据：发货时刻晚于时限。两个都是已落库的列，不碰墙上时钟。 */
  private static final String LATE_SQL =
      "(r.sla_deadline_at is not null and r.shipped_at > r.sla_deadline_at)";

  private static final Map<ShipmentRecordGroupBy, String> GROUP_SQL =
      Map.of(
          ShipmentRecordGroupBy.CARRIER, "r.carrier_code",
          ShipmentRecordGroupBy.TRACKING, "r.tracking_status",
          ShipmentRecordGroupBy.PLATFORM_CALLBACK, "r.platform_callback_status");

  private final DSLContext sql;
  private final ShipmentHandoverQueryPort handover;

  public ShipmentRecordService(DSLContext sql, ShipmentHandoverQueryPort handover) {
    this.sql = sql;
    this.handover = handover;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>本模块有<b>三处</b>「拿不到却必须说」：轨迹状态（10.3 未交付）、
   * 买家可见状态（没有可读上游）、平台回传状态（9.4.2.1 与 2.5.1.1 都未交付）。
   * 三处一律 available=false 并点名 ownerModule，同时说清本模块的处理是
   * 「登记」而不是「读取」—— 界面上不能把「登记了」画成「系统自动拿到」。
   *
   * <p>另有一处「半可用」：订单与买家快照。8.3.4.1 的交接单**没有**销售订单 /
   * 店铺 / 买家 / 收货国，只能从<b>同服务</b> 9.4.1.1 的投影里顺带读到；
   * 9.4.1.1 还没同步过这批货时这些列就是空的，如实报出而不是编一个值。
   */
  public ShipmentRecordCapabilities capabilities() {
    return new ShipmentRecordCapabilities(
        List.of(
            new ShipmentRecordCapabilityDimension(
                "SHIPMENT_FACT",
                "已发货这个事实本身（承运商、运单号、发货时间、包裹）",
                true,
                "warehouse 服务 V123 的授权只读端点 GET /warehouse/shipment-handovers"
                    + "（status=HANDED_OVER）与 GET /warehouse/shipment-handovers/{id}，"
                    + "经 ShipmentHandoverQueryPort 调用；**不建跨库视图、不跨服务 SQL、不建跨库 FK**",
                null,
                null),
            new ShipmentRecordCapabilityDimension(
                "ORDER_SNAPSHOT",
                "销售订单号 / 店铺 / 买家 / 收货国 / 发货时限",
                true,
                "同服务 9.4.1.1 的 shipment_pending_unit（trade 库内**只读 join**，"
                    + "按 source_packing_order_id；无 FK、无触发器、无写入）",
                null,
                "9.4.1.1 还没同步过这批货时这些列**留空**并计入 recordsWithoutOrderSnapshot；"
                    + "发货事实本身不依赖它，同步不会因为读不到订单快照而失败"),
            new ShipmentRecordCapabilityDimension(
                "TRACKING_STATUS",
                "轨迹状态",
                false,
                "10.3 物流轨迹未开发，没有承运商轨迹数据源",
                "10.3 物流轨迹 / 10 物流下单",
                "本模块改为**登记**：append-only 观测行（有人工登记与平台回传两路来源），"
                    + "记录级快照取最近一条观测；**未登记落独立 UNSET 桶**，"
                    + "与 NO_TRACE（登记过但承运商说没有轨迹）严格分开"),
            new ShipmentRecordCapabilityDimension(
                "BUYER_VISIBLE_STATUS",
                "买家可见状态",
                false,
                "无可读上游：平台面「买家能否看到物流」属 2.5 平台对接域，本车道集成头无来源",
                "2.5.1.1 平台回传 / 2.3 店铺",
                "本模块改为**登记**：append-only 登记行，人工登记必须写依据；"
                    + "未登记落独立 UNSET 桶，界面上不会画成「买家不可见」"),
            new ShipmentRecordCapabilityDimension(
                "PLATFORM_CALLBACK_STATUS",
                "平台回传状态",
                false,
                "9.4.2.1 平台回传与 2.5.1.1 平台回传均未开发，没有平台回执可读",
                "9.4.2.1 / 2.5.1.1",
                "本模块改为**登记**：append-only 回传登记行，ACKNOWLEDGED 强制带平台回执凭据；"
                    + "**SKIPPED（决定不回传，必须写原因）与 UNSET（压根没登记 = 漏了）"
                    + "是两个独立桶**，清单上分得开")),
        List.of("SHIPPED", "IN_TRANSIT", "DELIVERED", "RETURNED", "VOID"),
        Arrays.stream(ShipmentTrackingStatus.values()).map(Enum::name).toList(),
        Arrays.stream(PlatformCallbackStatus.values()).map(Enum::name).toList(),
        Arrays.stream(BuyerVisibleStatus.values()).map(Enum::name).toList(),
        Arrays.stream(ShipmentRecordGroupBy.values())
            .map(g -> g.name() + ":" + g.displayName)
            .toList(),
        "已发货 = 8.3.4.1 已确认交接（status=HANDED_OVER）的交运动作，"
            + "发货时刻取上游 handover_at，同步入参不允许调用方指定",
        "迟发率 = shipped_at > sla_deadline_at 的比例（分母只算登记过时限的记录，"
            + "未登记时限落 UNSET 桶，既不算准时也不算迟发）；"
            + "有效追踪率 = 有主运单号**且**至少有一条轨迹观测的记录占比",
        "下游 10.3 轨迹与 9.4.2.1 / 2.5.1.1 回传均未交付；本模块登记三列状态，"
            + "不自动抓取轨迹、不自动回传");
  }

  // ================================================================ 同步（投影刷新）

  /**
   * 从 8.3.4.1 的授权只读接口拉取已确认交接的交接单，落成发货记录与包裹投影。
   *
   * <p>幂等：按 {@code source_handover_id} upsert，重复同步是更新而不是插第二行。
   * 这一条是迟发率的分母不被翻倍的关键 —— 同一批货同步两次就翻倍，
   * 绩效指标当场失真。
   *
   * <p>刻意<b>不删</b>已经不在上游列表里的旧行：那些行可能已经带了轨迹观测、
   * 回传登记与绩效历史，删掉等于把一笔已经发生的发货从台账上抹掉。
   * 它们照常留在清单上（状态已推进），只在结果里如实报出数量。
   */
  @Transactional
  public ShipmentRecordSyncResult sync(ShipmentRecordSyncInput input, String bearer) {
    int pageSize =
        input.pageSize() > 0 ? Math.min(input.pageSize(), MAX_SYNC_PAGE) : MAX_SYNC_PAGE;
    OffsetDateTime now = OffsetDateTime.now();

    int fetched = 0;
    int inserted = 0;
    int updated = 0;
    int skipped = 0;
    int parcelRows = 0;
    int withSnapshot = 0;
    int withoutSnapshot = 0;
    int withoutSla = 0;

    for (int page = 0; page < MAX_SYNC_PAGE / pageSize + 1; page++) {
      var upstream = handover.handedOverPage(page, pageSize, bearer);
      if (upstream.items().isEmpty()) break;
      for (HandoverView view : upstream.items()) {
        fetched++;
        // 上游只给 status=HANDED_OVER，但这一层仍然显式挡一道：
        // 「已发货」必须是被确认过的交运，OPEN / CANCELLED 一律不登记。
        if (!"HANDED_OVER".equals(view.status())) {
          skipped++;
          continue;
        }
        var detail = handover.detail(view.id(), bearer);
        List<HandoverParcelView> parcels = detail.parcels() == null ? List.of() : detail.parcels();
        boolean exists =
            countOf(
                "select count(*) from shipment_record where source_handover_id = ?",
                view.id())
                > 0;

        UUID recordId = exists ? recordIdOf(view.id()) : UUID.randomUUID();
        OrderSnapshot snapshot = orderSnapshot(view.packingOrderId());
        if (snapshot.present()) {
          withSnapshot++;
        } else {
          withoutSnapshot++;
        }
        if (!snapshot.present() || snapshot.slaDeadlineAt() == null) {
          withoutSla++;
        }

        sql.execute(
            "insert into shipment_record (id,tenant_id,record_no,source_handover_id,"
                + " source_handover_code,source_packing_order_id,source_outbound_order_id,"
                + " outbound_order_no,warehouse_id,warehouse_ref,carrier_code,carrier_name,"
                + " master_waybill_no,shipped_at,parcel_count,parcel_item_count,total_quantity,"
                + " total_gross_weight_g,total_volume_cbm,sales_order_id,sales_order_no,shop_ref,"
                + " buyer_name,destination_country,sla_deadline_at,sla_source,synced_at,created_at,"
                + " updated_at)"
                // 29 列 ↔ 29 个绑定值，逐列一一对应：14=shipped_at / 25=sla_deadline_at /
                // 27=synced_at / 28=created_at / 29=updated_at 五个时刻列写 ?::timestamptz。
                // 用 Timestamp.valueOf(LocalDateTime) 绑这些列会静默偏 −28800s。
                + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,?,?,?,?,?,?,"
                + "?::timestamptz,?,?::timestamptz,?::timestamptz,?::timestamptz)"
                + " on conflict (tenant_id,source_handover_id) do update set"
                + " source_handover_code = excluded.source_handover_code,"
                + " outbound_order_no = excluded.outbound_order_no,"
                + " warehouse_ref = excluded.warehouse_ref,carrier_code = excluded.carrier_code,"
                + " carrier_name = excluded.carrier_name,master_waybill_no = excluded.master_waybill_no,"
                + " shipped_at = excluded.shipped_at,parcel_count = excluded.parcel_count,"
                + " parcel_item_count = excluded.parcel_item_count,"
                + " total_quantity = excluded.total_quantity,"
                + " total_gross_weight_g = excluded.total_gross_weight_g,"
                + " total_volume_cbm = excluded.total_volume_cbm,"
                + " sales_order_id = excluded.sales_order_id,"
                + " sales_order_no = excluded.sales_order_no,shop_ref = excluded.shop_ref,"
                + " buyer_name = excluded.buyer_name,"
                + " destination_country = excluded.destination_country,"
                + " sla_deadline_at = excluded.sla_deadline_at,sla_source = excluded.sla_source,"
                + " synced_at = excluded.synced_at,updated_at = excluded.updated_at,"
                + " version = shipment_record.version + 1",
            recordId,
            currentTenant(),
            nextRecordNo(now),
            view.id(),
            nullSafe(view.handoverCode(), ""),
            view.packingOrderId(),
            view.outboundOrderId(),
            nullSafe(view.outboundOrderCode(), ""),
            view.warehouseId(),
            nullSafe(view.warehouseCode(), ""),
            view.carrierCode(),
            nullSafe(view.carrierName(), ""),
            view.masterWaybillNo(),
            view.handoverAt(),
            view.parcelCount(),
            view.parcelItemCount(),
            decimal(view.totalQuantity()),
            decimal(view.totalGrossWeightG()),
            decimal(view.totalVolumeCbm()),
            snapshot.salesOrderId(),
            snapshot.salesOrderNo(),
            snapshot.shopRef(),
            snapshot.buyerName(),
            snapshot.destinationCountry(),
            snapshot.slaDeadlineAt(),
            snapshot.slaSource(),
            now,
            now,
            now);

        for (HandoverParcelView parcel : parcels) {
          parcelRows += upsertParcel(recordId, parcel, now);
        }

        writeEvent(
            recordId,
            exists ? "SYNC_REFRESHED" : "SHIPMENT_REGISTERED",
            exists ? "SHIPPED" : "SHIPPED",
            "SHIPPED",
            exists
                ? "刷新 8.3.4.1 交接单 " + view.id() + " 的发货记录"
                : "登记 8.3.4.1 已确认交接 " + nullSafe(view.handoverCode(), view.id().toString()),
            null);
        if (exists) {
          updated++;
        } else {
          inserted++;
        }
      }
      if (upstream.items().size() < pageSize) break;
    }

    long totalRows = countOf("select count(*) from shipment_record");
    List<String> notes = new ArrayList<>();
    notes.add("已发货的唯一来源是 8.3.4.1 已确认交接（status=HANDED_OVER）的交接单");
    notes.add("发货时刻取上游 handover_at 快照，同步入参不允许指定 —— 迟发率不能靠手填时间");
    if (withoutSnapshot > 0) {
      notes.add(
          withoutSnapshot
              + " 条记录没有订单快照（8.3.4.1 交接单无销售订单/店铺/买家列，"
                  + "且同服务 9.4.1.1 还没同步过这批货）：相关列留空，不影响发货事实本身");
    }
    if (withoutSla > 0) {
      notes.add(
          withoutSla
              + " 条记录没有可用的发货时限：迟发率里落 UNSET 桶，"
                  + "**既不算准时也不算迟发**（上游 2.5.1.1 平台回传未交付）");
    }
    notes.add("已推进的旧记录不会被删除；上游列表里没有的行照常留在清单上");

    return new ShipmentRecordSyncResult(
        fetched,
        inserted,
        updated,
        skipped,
        parcelRows,
        withSnapshot,
        withoutSnapshot,
        withoutSla,
        now,
        notes);
  }

  /** 逐箱 upsert（返回 1 表示插了、0 表示更新过）。 */
  private int upsertParcel(UUID recordId, HandoverParcelView parcel, OffsetDateTime now) {
    // 逐箱运单号：上游给 effectiveWaybillNo（逐箱生效的号），为空时回落主运单号。
    // 回落而不是留空，是因为空号会让这一箱在买家查询与有效追踪率上凭空消失。
    String waybill = nullSafe(parcel.effectiveWaybillNo(), "");
    if (waybill.isBlank()) {
      String master = textOf("select master_waybill_no from shipment_record where id = ?", recordId);
      waybill = nullSafe(master, "");
    }
    sql.execute(
        "insert into shipment_record_parcel (id,tenant_id,record_id,parcel_no,source_parcel_id,"
            + " waybill_no,parcel_code,gross_weight_g,volume_cbm,item_count,total_quantity,"
            + " synced_at,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id,record_id,parcel_no) do update set"
            + " source_parcel_id = excluded.source_parcel_id,waybill_no = excluded.waybill_no,"
            + " parcel_code = excluded.parcel_code,gross_weight_g = excluded.gross_weight_g,"
            + " volume_cbm = excluded.volume_cbm,item_count = excluded.item_count,"
            + " total_quantity = excluded.total_quantity,synced_at = excluded.synced_at,"
            + " updated_at = excluded.updated_at",
        UUID.randomUUID(),
        currentTenant(),
        recordId,
        parcel.parcelNo(),
        parcel.parcelId(),
        waybill,
        nullSafe(parcel.parcelCode(), ""),
        decimal(parcel.grossWeightG()),
        decimal(parcel.volumeCbm()),
        parcel.itemCount(),
        decimal(parcel.totalQuantity()),
        now,
        now,
        now);
    return 1;
  }

  /**
   * 从<b>同服务</b> 9.4.1.1 的投影里顺带读到订单快照与发货时限。
   *
   * <p>同服务同库的**只读 join**：没有 FK、没有触发器、没有任何写入，
   * 也不 ALTER 对方表（V127 文件头第四节）。读不到就返回 {@code present=false}，
   * 调用方如实记数 —— 绝不用一个占位订单号把行补齐。
   */
  private OrderSnapshot orderSnapshot(UUID packingOrderId) {
    Record row =
        sql.fetchOne(
            "select u.sales_order_id,u.sales_order_no,u.shop_ref,u.buyer_name,"
                + " u.destination_country,u.sla_deadline_at,u.sla_source"
                + " from shipment_pending_unit u where u.source_packing_order_id = ?",
            packingOrderId);
    if (row == null) {
      return new OrderSnapshot(false, null, "", "", "", "", null, "");
    }
    return new OrderSnapshot(
        true,
        row.get("sales_order_id", UUID.class),
        nullSafe(row.get("sales_order_no", String.class), ""),
        nullSafe(row.get("shop_ref", String.class), ""),
        nullSafe(row.get("buyer_name", String.class), ""),
        nullSafe(row.get("destination_country", String.class), ""),
        row.get("sla_deadline_at", OffsetDateTime.class),
        nullSafe(row.get("sla_source", String.class), ""));
  }

  private record OrderSnapshot(
      boolean present,
      UUID salesOrderId,
      String salesOrderNo,
      String shopRef,
      String buyerName,
      String destinationCountry,
      OffsetDateTime slaDeadlineAt,
      String slaSource) {}

  // ================================================================ 看板

  /** 分组看板 —— 真 {@code GROUP BY}，不是把一页数据在 Java 里数。 */
  public ShipmentRecordBoard board(
      String carrierCode,
      String status,
      String trackingStatus,
      String platformCallbackStatus,
      ShipmentRecordGroupBy groupBy) {
    ShipmentRecordGroupBy by = groupBy == null ? ShipmentRecordGroupBy.CARRIER : groupBy;
    String expression = GROUP_SQL.get(by);
    Filters filters = filters(carrierCode, status, trackingStatus, platformCallbackStatus, null);
    String where = filters.clause.isEmpty() ? "" : " where " + filters.clause;

    List<ShipmentRecordGroup> groups = new ArrayList<>();
    sql.fetch(
            "select " + expression + " as g, count(*) as records,"
                + " coalesce(sum(r.parcel_count),0) as parcels,"
                + " coalesce(sum(case when r.tracking_status = 'EXCEPTION' then 1 else 0 end),0) as exc"
                + " from shipment_record r" + where
                + " group by " + expression + " order by records desc, g asc",
            filters.params.toArray())
        .forEach(
            r -> {
              String key = r.get(0, String.class);
              groups.add(
                  new ShipmentRecordGroup(
                      key == null ? "" : key,
                      groupLabel(by, key),
                      r.get(1, Long.class) == null ? 0L : r.get(1, Long.class),
                      r.get(2, Integer.class) == null ? 0 : r.get(2, Integer.class),
                      r.get(3, Long.class) == null ? 0L : r.get(3, Long.class)));
            });

    return new ShipmentRecordBoard(
        by.name() + ":" + by.displayName,
        groups,
        summary(filters),
        List.of(
            "分组值取自**全量**发货记录的 GROUP BY，与列表分页无关",
            "UNSET 桶 = 还没登记（10.3 轨迹 / 2.5 平台回传均未交付，见 capabilities）；"
                + "UNSET 绝不并进正常桶",
            "作废（VOID）的记录仍留在分组里 —— 删掉等于把一笔已经记进绩效的错发货抹掉"));
  }

  // ================================================================ 清单与明细

  /** 发货记录列表（真分页 + 真筛选 + 排序白名单；汇总不受分页影响）。 */
  public ShipmentRecordPage records(
      String carrierCode,
      String status,
      String trackingStatus,
      String platformCallbackStatus,
      String keyword,
      String sort,
      int page,
      int size) {
    int limit = pageSize(size);
    Filters filters =
        filters(carrierCode, status, trackingStatus, platformCallbackStatus, keyword);
    String where = filters.clause.isEmpty() ? "" : " where " + filters.clause;

    long total = countOf("select count(*) from shipment_record r" + where, filters.params.toArray());

    List<ShipmentRecordRow> items = new ArrayList<>();
    sql.fetch(
            "select " + RECORD_COLUMNS + ", " + HAS_TRACKING_SQL + " as has_tracking,"
                + " " + LATE_SQL + " as late_flag from shipment_record r" + where
                + " order by " + orderBy(sort) + " limit ? offset ?",
            concat(filters.params, limit, (long) page * limit))
        .forEach(r -> items.add(recordRow(r)));

    return new ShipmentRecordPage(
        items, page, limit, total, (int) Math.ceil((double) total / limit), summary(filters));
  }

  /** 记录详情：单头 + 包裹 + 轨迹 + 可见状态 + 回传 + 时间线 + 边界声明。 */
  public ShipmentRecordDetail record(String id) {
    UUID recordId = parseUuid(id, "发货记录不存在：" + id);
    Record row =
        sql.fetchOne(
            "select " + RECORD_COLUMNS + ", " + HAS_TRACKING_SQL + " as has_tracking,"
                + " " + LATE_SQL + " as late_flag from shipment_record r where r.id = ?",
            recordId);
    if (row == null) throw notFound("发货记录不存在：" + id);

    List<ShipmentRecordParcelRow> parcels = new ArrayList<>();
    sql.fetch(
            "select " + PARCEL_COLUMNS + ", (select count(*) from"
                + " shipment_record_tracking_observation o where o.tenant_id = p.tenant_id"
                + " and o.parcel_id = p.id) as obs from shipment_record_parcel p"
                + " where p.record_id = ? order by p.parcel_no",
            recordId)
        .forEach(r -> parcels.add(parcelRow(r)));

    List<ShipmentTrackingObservationRow> tracking = new ArrayList<>();
    sql.fetch(
            "select o.id,o.record_id,o.parcel_id,o.status,o.source,o.observation_ref,o.detail,"
                + " o.observed_at,o.created_at, coalesce(p.parcel_no,0) as parcel_no"
                + " from shipment_record_tracking_observation o"
                + " left join shipment_record_parcel p on p.tenant_id = o.tenant_id"
                + " and p.id = o.parcel_id where o.record_id = ?"
                + " order by o.observed_at desc, o.created_at desc",
            recordId)
        .forEach(
            r ->
                tracking.add(
                    new ShipmentTrackingObservationRow(
                        r.get("id", UUID.class),
                        r.get("record_id", UUID.class),
                        r.get("parcel_id", UUID.class),
                        r.get("parcel_no", Integer.class) == null
                            ? 0
                            : r.get("parcel_no", Integer.class),
                        ShipmentTrackingStatus.valueOf(r.get("status", String.class)),
                        ShipmentTrackingStatus.valueOf(r.get("status", String.class)).displayName,
                        r.get("source", String.class),
                        r.get("observation_ref", String.class),
                        r.get("detail", String.class),
                        r.get("observed_at", OffsetDateTime.class),
                        r.get("created_at", OffsetDateTime.class))));

    List<ShipmentVisibilityRow> visibility = new ArrayList<>();
    sql.fetch(
            "select id,record_id,status,source,note,registered_at,registered_by_membership_id"
                + " from shipment_record_visibility where record_id = ?"
                + " order by registered_at desc, created_at desc",
            recordId)
        .forEach(
            r ->
                visibility.add(
                    new ShipmentVisibilityRow(
                        r.get("id", UUID.class),
                        r.get("record_id", UUID.class),
                        BuyerVisibleStatus.valueOf(r.get("status", String.class)),
                        BuyerVisibleStatus.valueOf(r.get("status", String.class)).displayName,
                        r.get("source", String.class),
                        r.get("note", String.class),
                        r.get("registered_at", OffsetDateTime.class),
                        r.get("registered_by_membership_id", UUID.class))));

    List<ShipmentCallbackRow> callbacks = new ArrayList<>();
    sql.fetch(
            "select id,record_id,status,source,request_ref,response_ref,note,registered_at,"
                + " registered_by_membership_id from shipment_record_callback where record_id = ?"
                + " order by registered_at desc, created_at desc",
            recordId)
        .forEach(
            r ->
                callbacks.add(
                    new ShipmentCallbackRow(
                        r.get("id", UUID.class),
                        r.get("record_id", UUID.class),
                        PlatformCallbackStatus.valueOf(r.get("status", String.class)),
                        PlatformCallbackStatus.valueOf(r.get("status", String.class)).displayName,
                        r.get("source", String.class),
                        r.get("request_ref", String.class),
                        r.get("response_ref", String.class),
                        r.get("note", String.class),
                        r.get("registered_at", OffsetDateTime.class),
                        r.get("registered_by_membership_id", UUID.class))));

    return new ShipmentRecordDetail(
        recordRow(row),
        parcels,
        tracking,
        visibility,
        callbacks,
        timeline(recordId),
        boundaryNotes(row));
  }

  /** 边界声明：详情页必须原样展示，让「没登记」不被读成「正常」。 */
  private List<String> boundaryNotes(Record row) {
    List<String> notes = new ArrayList<>();
    notes.add(
        "已发货事实来自 8.3.4.1 已确认交接的交接单，发货时刻为上游 handover_at 快照");
    if (ShipmentTrackingStatus.valueOf(row.get("tracking_status", String.class))
        == ShipmentTrackingStatus.UNSET) {
      notes.add("轨迹状态未登记（10.3 未交付）：**没有轨迹 ≠ 轨迹正常**，它落在独立的 UNSET 桶里");
    }
    if (BuyerVisibleStatus.valueOf(row.get("buyer_visible_status", String.class))
        == BuyerVisibleStatus.UNSET) {
      notes.add("买家可见状态未登记（无可读上游）：界面上不得画成「买家不可见」");
    }
    if (PlatformCallbackStatus.valueOf(row.get("platform_callback_status", String.class))
        == PlatformCallbackStatus.UNSET) {
      notes.add("平台回传状态未登记（9.4.2.1 / 2.5.1.1 未交付）：这是「**漏了**」，"
          + "与 SKIPPED「决定不回传」是两个独立的桶");
    }
    if (row.get("sla_deadline_at", OffsetDateTime.class) == null) {
      notes.add("没有登记发货时限：这条记录在迟发率里落 UNSET 桶，既不算准时也不算迟发");
    }
    return notes;
  }

  // ================================================================ 轨迹登记（10.3 未交付）

  /**
   * 登记一条轨迹观测。
   *
   * <p>写序被 DB 触发器钉死：<b>先落 append-only 凭据行，再更新快照</b>，
   * 而且快照值取自与触发器<b>同一条</b>查询（最近一条观测），所以两者不可能
   * 对不上。快照改成触发器算不出来的值，绕过服务层直接写 SQL 也会被拒。
   */
  @Transactional
  public ShipmentRecordDetail registerTracking(
      String id, ShipmentTrackingObservationInput input, UUID membershipId) {
    UUID recordId = requireRecord(id);
    requireMutable(recordId);
    if (input.parcelId() != null) {
      String owner =
          textOf(
              "select record_id from shipment_record_parcel where id = ? and record_id = ?",
              input.parcelId(),
              recordId);
      if (owner == null) {
        throw badRequest("包裹不属于这条发货记录，无法登记轨迹");
      }
    }
    sql.execute(
        "insert into shipment_record_tracking_observation (id,tenant_id,record_id,parcel_id,"
            + " status,source,observation_ref,detail,observed_at,observed_by_membership_id)"
            + " values (?,?,?,?,?,?,?,?,?::timestamptz,?)",
        UUID.randomUUID(),
        currentTenant(),
        recordId,
        input.parcelId(),
        input.status().name(),
        input.source().name(),
        input.observationRef(),
        nullSafe(input.detail(), ""),
        input.observedAt(),
        input.source() == ShipmentObservationSource.MANUAL_ATTESTATION ? membershipId : null);
    if (input.parcelId() != null) {
      sql.execute(
          "update shipment_record_parcel set current_tracking_status = ?,"
              + " updated_at = ?::timestamptz where id = ?",
          input.status().name(),
          input.observedAt(),
          input.parcelId());
    }
    applyTrackingSnapshot(recordId);
    writeEvent(
        recordId,
        "TRACKING_OBSERVED",
        "",
        "",
        "登记轨迹观测 " + input.status().displayName + "（来源 " + input.source().displayName
            + "，观测时刻 " + input.observedAt() + "）",
        membershipId);
    return record(recordId.toString());
  }

  /** 记录级轨迹快照 = 该记录**最近一条**观测（整票级或逐箱级都算）。 */
  private void applyTrackingSnapshot(UUID recordId) {
    ShipmentTrackingStatus latest = latestTrackingStatus(recordId);
    sql.execute(
        "update shipment_record set tracking_status = ?, tracking_updated_at = ?::timestamptz,"
            + " updated_at = ?::timestamptz where id = ?",
        latest.name(),
        OffsetDateTime.now(),
        OffsetDateTime.now(),
        recordId);
  }

  private ShipmentTrackingStatus latestTrackingStatus(UUID recordId) {
    String status =
        textOf(
            "select status from shipment_record_tracking_observation where record_id = ?"
                + " order by observed_at desc, created_at desc limit 1",
            recordId);
    return status == null
        ? ShipmentTrackingStatus.UNSET
        : ShipmentTrackingStatus.valueOf(status);
  }

  // ================================================================ 买家可见状态登记

  /** 登记买家可见状态（无可读上游 ⇒ 登记而非读取）。写序同样被触发器钉死。 */
  @Transactional
  public ShipmentRecordDetail registerVisibility(
      String id, ShipmentVisibilityInput input, UUID membershipId) {
    UUID recordId = requireRecord(id);
    requireMutable(recordId);
    sql.execute(
        "insert into shipment_record_visibility (id,tenant_id,record_id,status,source,note,"
            + " registered_at,registered_by_membership_id)"
            + " values (?,?,?,?,?,?,?::timestamptz,?)",
        UUID.randomUUID(),
        currentTenant(),
        recordId,
        input.status().name(),
        input.source().name(),
        nullSafe(input.note(), ""),
        input.registeredAt(),
        membershipId);
    String latest =
        textOf(
            "select status from shipment_record_visibility where record_id = ?"
                + " order by registered_at desc, created_at desc limit 1",
            recordId);
    sql.execute(
        "update shipment_record set buyer_visible_status = ?, buyer_visible_source = ?,"
            + " buyer_visible_registered_at = ?::timestamptz, buyer_visible_by_membership_id = ?,"
            + " buyer_visible_note = ?, updated_at = ?::timestamptz where id = ?",
        latest,
        input.source().name(),
        input.registeredAt(),
        membershipId,
        nullSafe(input.note(), ""),
        OffsetDateTime.now(),
        recordId);
    writeEvent(
        recordId,
        "VISIBILITY_REGISTERED",
        "",
        "",
        "登记买家可见状态 " + input.status().displayName + "（来源 "
            + input.source().displayName + "）",
        membershipId);
    return record(recordId.toString());
  }

  // ================================================================ 平台回传登记

  /**
   * 登记平台回传状态（9.4.2.1 / 2.5.1.1 未交付 ⇒ 登记而非假装已回传）。
   *
   * <p>{@code SKIPPED} 必须带原因（DB CHECK 强制）：「决定不回传」与
   * 「压根没登记」在平台处罚上完全不同，合并就等于让漏回传伪装成策略跳过。
   */
  @Transactional
  public ShipmentRecordDetail registerCallback(
      String id, ShipmentCallbackInput input, UUID membershipId) {
    UUID recordId = requireRecord(id);
    requireMutable(recordId);
    if (input.status() == PlatformCallbackStatus.SKIPPED
        && (input.note() == null || input.note().isBlank())) {
      throw badRequest("决定不回传（SKIPPED）必须写明原因：否则「跳过」与「漏了」无法区分");
    }
    sql.execute(
        "insert into shipment_record_callback (id,tenant_id,record_id,status,source,request_ref,"
            + " response_ref,note,registered_at,registered_by_membership_id)"
            + " values (?,?,?,?,?,?,?,?,?::timestamptz,?)",
        UUID.randomUUID(),
        currentTenant(),
        recordId,
        input.status().name(),
        input.source().name(),
        nullSafe(input.requestRef(), ""),
        nullSafe(input.responseRef(), ""),
        nullSafe(input.note(), ""),
        input.registeredAt(),
        input.source() == ShipmentObservationSource.MANUAL_ATTESTATION ? membershipId : null);
    String latest =
        textOf(
            "select status from shipment_record_callback where record_id = ?"
                + " order by registered_at desc, created_at desc limit 1",
            recordId);
    sql.execute(
        "update shipment_record set platform_callback_status = ?, platform_callback_source = ?,"
            + " platform_callback_registered_at = ?::timestamptz, platform_callback_by_membership_id = ?,"
            + " platform_callback_ref = ?, platform_callback_note = ?, updated_at = ?::timestamptz"
            + " where id = ?",
        latest,
        input.source().name(),
        input.registeredAt(),
        input.source() == ShipmentObservationSource.MANUAL_ATTESTATION ? membershipId : null,
        // 快照只放最近一次的请求凭据与说明；平台回执凭据（responseRef）留在
        // append-only 的 shipment_record_callback 行上，明细页从那里读全。
        nullSafe(input.requestRef(), ""),
        nullSafe(input.note(), ""),
        OffsetDateTime.now(),
        recordId);
    writeEvent(
        recordId,
        "CALLBACK_REGISTERED",
        "",
        "",
        "登记平台回传状态 " + input.status().displayName + "（来源 "
            + input.source().displayName + "）",
        membershipId);
    return record(recordId.toString());
  }

  // ================================================================ 状态流转

  /**
   * 推进 / 更正发货记录状态。
   *
   * <p>终态（DELIVERED / RETURNED /VOID）不可再动，DB 触发器同样逐对白名单校验。
   * VOID 是唯一的逃生舱：上游交接作废、或发现这一票其实没发出去时必须有路可走，
   * 否则只能靠删行 —— 而删行等于把一笔已经记进绩效的错发货抹掉。
   */
  @Transactional
  public ShipmentRecordDetail changeStatus(
      String id, ShipmentStatusInput input, UUID membershipId) {
    UUID recordId = requireRecord(id);
    String from =
        textOf("select status from shipment_record where id = ?", recordId);
    if (ShipmentRecordStatus.valueOf(from).terminal()) {
      throw badRequest("发货记录已处于终态 " + from + "，不能再改：它已是发生过的发货事实");
    }
    // 刻意**不在**服务层再抄一份状态迁移白名单：唯一权威是 DB 触发器
    // guard_shipment_record_state（绕过服务层直接写 SQL 同样被拒）。
    // 服务层多抄一份，两处就会各自漂移，最后出现「界面点不动但脚本能写」的裂缝。
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update shipment_record set status = ?, status_changed_at = ?::timestamptz,"
            + " status_changed_by_membership_id = ?, status_reason = ?, updated_at = ?::timestamptz"
            + " where id = ?",
        input.status().name(),
        now,
        membershipId,
        input.reason(),
        now,
        recordId);
    writeEvent(recordId, "STATUS_CHANGED", from, input.status().name(), input.reason(), membershipId);
    return record(recordId.toString());
  }

  // ================================================================ 绩效口径

  /**
   * 迟发率与有效追踪率 —— 蓝图那句话「发货记录是平台绩效指标的计算依据」的落点。
   *
   * <p>三条口径纪律：
   *
   * <ol>
   *   <li><b>分母不含作废记录</b>：VOID 是被撤销的错发货，记进迟发率会反过来
   *       把指标做坏（凭空多出一次迟发，掩盖真实的准时发货）。</li>
   *   <li><b>迟发率的分母只算登记过时限的记录</b>：没有时限就算「准时」，迟发率会
   *       系统性偏低，时限越缺的那批货看起来越完美。</li>
   *   <li><b>全程不碰墙上时钟</b>：只比对已落库的 shipped_at 与 sla_deadline_at，
   *       所以同一个夹具在任何钟点跑出的口径都一样。</li>
   * </ol>
   */
  public ShipmentPerformanceReport performance(
      String carrierCode,
      String status,
      String trackingStatus,
      String platformCallbackStatus,
      String keyword) {
    Filters filters =
        filters(callerSafe(carrierCode), status, trackingStatus, platformCallbackStatus, keyword);
    // 筛选子句可能为空（没给任何筛选条件），拼 where 时必须跳过空串，
    // 否则会拼出 `where  and r.status <> 'VOID'` 这种语法错误。
    List<String> clauses = new ArrayList<>();
    List<Object> params = new ArrayList<>(filters.params);
    if (!filters.clause.isEmpty()) {
      clauses.add(filters.clause);
    }
    // 作废记录不进绩效：它是错发货，不是迟发货
    clauses.add("r.status <> 'VOID'");
    String where = " where " + String.join(" and ", clauses);

    Record row =
        sql.fetchOne(
            "select count(*) as evaluated,"
                + " coalesce(sum(case when " + LATE_SQL
                + " then 1 else 0 end),0) as late,"
                + " coalesce(sum(case when r.sla_deadline_at is not null and r.shipped_at <="
                + " r.sla_deadline_at then 1 else 0 end),0) as on_time,"
                + " coalesce(sum(case when r.sla_deadline_at is null then 1 else 0 end),0) as unset_sla,"
                + " coalesce(sum(case when " + HAS_TRACKING_SQL
                + " and btrim(r.master_waybill_no) <> '' then 1 else 0 end),0) as trackable"
                + " from shipment_record r" + where,
            params.toArray());

    long evaluated = row.get("evaluated", Long.class);
    long late = row.get("late", Long.class);
    long onTime = row.get("on_time", Long.class);
    long unsetSla = row.get("unset_sla", Long.class);
    long trackable = row.get("trackable", Long.class);

    long slaKnown = late + onTime;
    long untrackable = evaluated - trackable;
    // 保留 4 位小数：绩效指标是要拿去对账的，四舍五入到整数会让「9/100 迟发」
    // 显示成「0% 迟发」，等于把一个真实的处罚信号抹平。
    BigDecimal lateRate = rate(late, slaKnown);
    BigDecimal onTimeRate = rate(onTime, slaKnown);
    BigDecimal validTrackingRate = rate(trackable, evaluated);

    List<String> notes = new ArrayList<>();
    notes.add("作废（VOID）记录不计入任何绩效分母：撤销的是错发货，不是迟发货");
    notes.add(
        "迟发率分母 = " + slaKnown + " 条**登记过发货时限**的记录；另有 " + unsetSla
            + " 条时限未登记（落 UNSET 桶，既不算准时也不算迟发）");
    notes.add(
        "有效追踪率要求**同时**有主运单号与至少一条轨迹观测："
            + "只有运单号没有轨迹说明承运商没给到（平台照样扣分），"
            + "有观测但没单号则无法回传与向买家举证");
    notes.add("两项口径都不依赖墙上时钟：只比对已落库的发货时刻与时限");
    notes.add("轨迹与回传的上游（10.3 / 9.4.2.1 / 2.5.1.1）未交付，本模块只登记不算抓取");

    return new ShipmentPerformanceReport(
        evaluated,
        late,
        onTime,
        unsetSla,
        lateRate,
        onTimeRate,
        trackable,
        untrackable,
        validTrackingRate,
        "迟发 = shipped_at > sla_deadline_at；分母只算登记过时限且非作废的记录",
        "有效追踪 = 有主运单号且存在至少一条轨迹观测（整票级或逐箱级）；"
            + "分母为全部非作废记录",
        notes);
  }

  /** 比例。分母为 0 时返回 0.0000（**不**返回 null，界面不必处理 null）。 */
  private static BigDecimal rate(long numerator, long denominator) {
    if (denominator <= 0) return new BigDecimal("0.0000");
    return BigDecimal.valueOf(numerator)
        .divide(BigDecimal.valueOf(denominator), 4, java.math.RoundingMode.HALF_UP);
  }

  // ================================================================ 事件流

  public ShipmentRecordEventPage events(
      String eventKind, UUID recordId, int page, int size) {
    int limit = pageSize(size);
    List<String> clauses = new ArrayList<>();
    List<Object> params = new ArrayList<>();
    if (notBlank(eventKind)) {
      clauses.add("event_kind = ?");
      params.add(eventKind.trim());
    }
    if (recordId != null) {
      clauses.add("record_id = ?");
      params.add(recordId);
    }
    String where = clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);

    long total = countOf("select count(*) from shipment_record_event" + where, params.toArray());
    List<ShipmentRecordEventRow> items = new ArrayList<>();
    sql.fetch(
            "select id,record_id,event_kind,from_status,to_status,detail,occurred_at"
                + " from shipment_record_event" + where
                + " order by occurred_at desc, created_at desc limit ? offset ?",
            concat(params, limit, (long) page * limit))
        .forEach(
            r ->
                items.add(
                    new ShipmentRecordEventRow(
                        r.get("id", UUID.class),
                        r.get("record_id", UUID.class),
                        r.get("event_kind", String.class),
                        r.get("from_status", String.class),
                        r.get("to_status", String.class),
                        r.get("detail", String.class),
                        r.get("occurred_at", OffsetDateTime.class))));
    return new ShipmentRecordEventPage(items, page, limit, total, (int) Math.ceil((double) total / limit));
  }

  private List<ShipmentRecordEventRow> timeline(UUID recordId) {
    List<ShipmentRecordEventRow> rows = new ArrayList<>();
    sql.fetch(
            "select id,record_id,event_kind,from_status,to_status,detail,occurred_at"
                + " from shipment_record_event where record_id = ? order by occurred_at, created_at",
            recordId)
        .forEach(
            r ->
                rows.add(
                    new ShipmentRecordEventRow(
                        r.get("id", UUID.class),
                        r.get("record_id", UUID.class),
                        r.get("event_kind", String.class),
                        r.get("from_status", String.class),
                        r.get("to_status", String.class),
                        r.get("detail", String.class),
                        r.get("occurred_at", OffsetDateTime.class))));
    return rows;
  }

  private void writeEvent(
      UUID recordId,
      String kind,
      String from,
      String to,
      String detail,
      UUID actor) {
    sql.execute(
        "insert into shipment_record_event (id,tenant_id,record_id,event_kind,from_status,"
            + " to_status,detail,actor_membership_id) values (?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        currentTenant(),
        recordId,
        kind,
        nullSafe(from, ""),
        nullSafe(to, ""),
        nullSafe(detail, ""),
        actor);
  }

  // ================================================================ 行映射

  private ShipmentRecordRow recordRow(Record r) {
    ShipmentRecordStatus status = ShipmentRecordStatus.valueOf(r.get("status", String.class));
    ShipmentTrackingStatus tracking =
        ShipmentTrackingStatus.valueOf(r.get("tracking_status", String.class));
    BuyerVisibleStatus visibility =
        BuyerVisibleStatus.valueOf(r.get("buyer_visible_status", String.class));
    PlatformCallbackStatus callback =
        PlatformCallbackStatus.valueOf(r.get("platform_callback_status", String.class));
    return new ShipmentRecordRow(
        r.get("id", UUID.class),
        r.get("record_no", String.class),
        r.get("source_handover_id", UUID.class),
        r.get("source_handover_code", String.class),
        r.get("outbound_order_no", String.class),
        r.get("sales_order_id", UUID.class),
        r.get("sales_order_no", String.class),
        r.get("shop_ref", String.class),
        r.get("buyer_name", String.class),
        r.get("destination_country", String.class),
        r.get("warehouse_ref", String.class),
        r.get("carrier_code", String.class),
        r.get("carrier_name", String.class),
        r.get("master_waybill_no", String.class),
        r.get("shipped_at", OffsetDateTime.class),
        r.get("sla_deadline_at", OffsetDateTime.class),
        r.get("parcel_count", Integer.class) == null ? 0 : r.get("parcel_count", Integer.class),
        r.get("total_quantity", BigDecimal.class),
        r.get("total_gross_weight_g", BigDecimal.class),
        status,
        status.displayName,
        tracking,
        tracking.displayName,
        visibility,
        visibility.displayName,
        callback,
        callback.displayName,
        Boolean.TRUE.equals(r.get("has_tracking", Boolean.class)),
        Boolean.TRUE.equals(r.get("late_flag", Boolean.class)),
        r.get("synced_at", OffsetDateTime.class));
  }

  private ShipmentRecordParcelRow parcelRow(Record r) {
    ShipmentTrackingStatus tracking =
        ShipmentTrackingStatus.valueOf(r.get("current_tracking_status", String.class));
    return new ShipmentRecordParcelRow(
        r.get("id", UUID.class),
        r.get("record_id", UUID.class),
        r.get("parcel_no", Integer.class) == null ? 0 : r.get("parcel_no", Integer.class),
        r.get("source_parcel_id", UUID.class),
        r.get("parcel_code", String.class),
        r.get("waybill_no", String.class),
        r.get("gross_weight_g", BigDecimal.class),
        r.get("volume_cbm", BigDecimal.class),
        r.get("item_count", Integer.class) == null ? 0 : r.get("item_count", Integer.class),
        r.get("total_quantity", BigDecimal.class),
        tracking,
        tracking.displayName,
        r.get("obs", Integer.class) == null ? 0 : r.get("obs", Integer.class));
  }

  // ================================================================ 汇总

  private ShipmentRecordSummary summary(Filters filters) {
    String where = filters.clause.isEmpty() ? "" : " where " + filters.clause;
    Record r =
        sql.fetchOne(
            // 每列都 coalesce 到 0：sum() 在零行时是 NULL，直接塞进 long 会 NPE ——
            // 而「一条发货记录都没有」恰恰是空清单最正常的一种状态。
            "select count(*) as total,"
                + " coalesce(sum(case when status='SHIPPED' then 1 else 0 end),0) as shipped,"
                + " coalesce(sum(case when status='IN_TRANSIT' then 1 else 0 end),0) as transit,"
                + " coalesce(sum(case when status='DELIVERED' then 1 else 0 end),0) as delivered,"
                + " coalesce(sum(case when status='RETURNED' then 1 else 0 end),0) as returned,"
                + " coalesce(sum(case when status='VOID' then 1 else 0 end),0) as voided,"
                + " coalesce(sum(parcel_count),0) as parcels,"
                + " coalesce(sum(case when tracking_status='UNSET' then 1 else 0 end),0) as unset_tracking,"
                + " coalesce(sum(case when tracking_status='EXCEPTION' then 1 else 0 end),0) as exc_tracking,"
                + " coalesce(sum(case when platform_callback_status='UNSET' then 1 else 0 end),0) as unset_cb,"
                + " coalesce(sum(case when platform_callback_status='REJECTED' then 1 else 0 end),0) as rejected_cb,"
                + " coalesce(sum(case when platform_callback_status='SKIPPED' then 1 else 0 end),0) as skipped_cb,"
                + " coalesce(sum(case when buyer_visible_status='UNSET' then 1 else 0 end),0) as unset_vis,"
                + " coalesce(sum(case when sla_deadline_at is null then 1 else 0 end),0) as unset_sla"
                + " from shipment_record r" + where,
            filters.params.toArray());
    return new ShipmentRecordSummary(
        r.get("total", Long.class),
        r.get("shipped", Long.class),
        r.get("transit", Long.class),
        r.get("delivered", Long.class),
        r.get("returned", Long.class),
        r.get("voided", Long.class),
        r.get("parcels", Integer.class) == null ? 0 : r.get("parcels", Integer.class),
        r.get("unset_tracking", Long.class),
        r.get("exc_tracking", Long.class),
        r.get("unset_cb", Long.class),
        r.get("rejected_cb", Long.class),
        r.get("skipped_cb", Long.class),
        r.get("unset_vis", Long.class),
        r.get("unset_sla", Long.class));
  }

  // ================================================================ 筛选与工具

  private Filters filters(
      String carrierCode, String status, String trackingStatus, String callbackStatus, String keyword) {
    List<String> clauses = new ArrayList<>();
    List<Object> params = new ArrayList<>();
    if (notBlank(carrierCode)) {
      clauses.add("r.carrier_code = ?");
      params.add(carrierCode.trim());
    }
    if (notBlank(status)) {
      clauses.add("r.status = ?");
      params.add(status.trim());
    }
    if (notBlank(trackingStatus)) {
      clauses.add("r.tracking_status = ?");
      params.add(trackingStatus.trim());
    }
    if (notBlank(callbackStatus)) {
      clauses.add("r.platform_callback_status = ?");
      params.add(callbackStatus.trim());
    }
    if (notBlank(keyword)) {
      clauses.add(
          "(r.record_no ilike ? or r.source_handover_code ilike ? or r.master_waybill_no ilike ?"
              + " or r.sales_order_no ilike ? or r.outbound_order_no ilike ? or r.carrier_code ilike ?)");
      String like = "%" + keyword.trim() + "%";
      for (int i = 0; i < 6; i++) params.add(like);
    }
    return new Filters(String.join(" and ", clauses), params);
  }

  private record Filters(String clause, List<Object> params) {}

  private String groupLabel(ShipmentRecordGroupBy by, String key) {
    if (key == null || key.isBlank()) return "未指定";
    return switch (by) {
      case CARRIER -> key;
      case TRACKING -> ShipmentTrackingStatus.valueOf(key).displayName;
      case PLATFORM_CALLBACK -> PlatformCallbackStatus.valueOf(key).displayName;
    };
  }

  private UUID requireRecord(String id) {
    UUID recordId = parseUuid(id, "发货记录不存在：" + id);
    if (countOf("select count(*) from shipment_record where id = ?", recordId) == 0) {
      throw notFound("发货记录不存在：" + id);
    }
    return recordId;
  }

  /** 终态记录不再接受登记：它已是发生过的发货事实，改它等于改历史。 */
  private void requireMutable(UUID recordId) {
    String status = textOf("select status from shipment_record where id = ?", recordId);
    if (ShipmentRecordStatus.valueOf(status).terminal()) {
      throw badRequest(
          "发货记录已处于终态 " + status + "，不能再登记状态：它已是发生过的发货事实");
    }
  }

  private UUID recordIdOf(UUID handoverId) {
    return uuidOf("select id from shipment_record where source_handover_id = ?", handoverId);
  }

  /** 排序白名单：值不进 SQL 拼接。 */
  private String orderBy(String sort) {
    if (sort == null) return "r.shipped_at desc";
    return switch (sort) {
      case "SHIPPED_AT_ASC" -> "r.shipped_at asc";
      case "SHIPPED_AT_DESC" -> "r.shipped_at desc";
      case "RECORD_NO_ASC" -> "r.record_no asc";
      case "CARRIER_CODE_ASC" -> "r.carrier_code asc, r.shipped_at desc";
      case "PARCEL_COUNT_DESC" -> "r.parcel_count desc, r.shipped_at desc";
      case "WEIGHT_DESC" -> "r.total_gross_weight_g desc";
      default -> "r.shipped_at desc";
    };
  }

  private int pageSize(int size) {
    if (size <= 0) return 50;
    return Math.min(size, MAX_PAGE_SIZE);
  }

  /** 上游字符串转 BigDecimal；空 / 非法一律当 0，**不做静默的精度截断**。 */
  private static BigDecimal decimal(String value) {
    if (value == null || value.isBlank()) return ZERO;
    try {
      return new BigDecimal(value.trim());
    } catch (NumberFormatException e) {
      return ZERO;
    }
  }

  private static String callerSafe(String value) {
    return value == null ? null : value;
  }

  private long countOf(String text, Object... binds) {
    Record record = binds.length == 0 ? sql.fetchOne(text) : sql.fetchOne(text, binds);
    if (record == null) return 0L;
    Number value = (Number) record.get(0);
    return value == null ? 0L : value.longValue();
  }

  /** 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid as t")
        .get("t", UUID.class);
  }

  private UUID uuidOf(String text, Object... binds) {
    Record record = sql.fetchOne(text, binds);
    return record == null ? null : record.get(0, UUID.class);
  }

  private String textOf(String text, Object... binds) {
    Record record = sql.fetchOne(text, binds);
    return record == null ? null : record.get(0, String.class);
  }

  private String nextRecordNo(OffsetDateTime now) {
    Long n = sql.fetchOne("select nextval('shipment_record_no_seq')").get(0, Long.class);
    return String.format(
        Locale.ROOT, "SR-%s%06d", now.toLocalDate().toString().replace("-", ""), n);
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String nullSafe(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static Object[] concat(List<Object> base, Object... tail) {
    Object[] all = new Object[base.size() + tail.length];
    for (int i = 0; i < base.size(); i++) all[i] = base.get(i);
    System.arraycopy(tail, 0, all, base.size(), tail.length);
    return all;
  }

  private static UUID parseUuid(String value, String message) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw notFound(message);
    }
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
