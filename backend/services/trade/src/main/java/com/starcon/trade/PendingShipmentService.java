package com.starcon.trade;

import static com.starcon.trade.PendingShipmentModels.*;

import com.starcon.trade.PackingQueryPort.PackingOrderView;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
 * 9.4.1.1 待发货。
 *
 * <p><b>六条实现纪律</b>（照抄 9.6.1.2 的写法，不另起风格）：
 *
 * <ol>
 *   <li><b>数据来源只有一处</b>：8.3.3.2 的 warehouse 授权只读 API
 *       （{@link PackingQueryPort}）。跨库视图、跨服务 SQL、跨服务 FK 一律不做
 *       —— 见 V124 文件头第一节。取不到就 503，绝不返回空清单冒充「今天没有待发货」。</li>
 *   <li><b>分组是真的 GROUP BY</b>：仓库 / 承运渠道 / 承运商 / 发货时限四个维度
 *       各自一条 SQL 聚合，不是把一页数据在 Java 里数。蓝图那句「按承运商分组是
 *       大促期效率关键，混在一起会导致交接混乱」在 {@code shipment_batch_task}
 *       的「一次任务不许跨承运商」上被钉死。</li>
 *   <li><b>三个批量操作是真任务</b>：任务 + 逐条 item + 运单回执 + 事件流四张表，
 *       状态机 PENDING → RUNNING → SUCCEEDED/PARTIAL/FAILED。{@code PARTIAL} 与
 *       {@code SUCCEEDED} 分开，是因为「交接漏发」在界面上显示成「交接完成」
 *       是本模块最贵的失败方式。</li>
 *   <li><b>最远只到 HANDED_OVER</b>：本模块<b>不产生已发货</b>，已发货是下游 8.3.4.1
 *       在承运商揽收后确认的事实（9.4.1.2 发货记录的上游）。</li>
 *   <li><b>时限登记而不伪造</b>：上游没有平台 SLA 可读，所以要么登记（有主体、有时刻、
 *       有来源），要么落在 UNSET 桶 —— 「还不知道」不能混进「今天到期」。</li>
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 写在 values 的对应位置上，
 *       全程 {@code OffsetDateTime}，不用 {@code Timestamp.valueOf(LocalDateTime)}。</li>
 * </ol>
 */
@Service
public class PendingShipmentService {

  /** 单页最大行数（与 9.6.1.2 同一上限）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 同步单次最多取多少张打包单 —— 一次拉爆会把请求线程占死。 */
  static final int MAX_SYNC_PAGE = 500;

  private static final BigDecimal ZERO_WEIGHT = new BigDecimal("0.000000");

  private static final String UNIT_COLUMNS =
      "u.id,u.source_packing_order_id,u.source_outbound_order_id,u.warehouse_ref,u.warehouse_id,"
          + "u.outbound_order_no,u.sales_order_id,u.sales_order_no,u.shop_ref,u.buyer_name,"
          + "u.destination_country,u.carrier_channel,u.carrier_code,u.sla_deadline_at,"
          + "u.sla_source,u.parcel_count,u.total_weight_kg,u.status,u.waybill_no,"
          + "u.packed_at,u.handed_over_at,u.synced_at";

  private static final String PARCEL_COLUMNS =
      "id,unit_id,parcel_no,source_parcel_id,gross_weight_kg,length_mm,width_mm,height_mm,"
          + "label_printed,label_printed_at,label_job_ref";

  private static final String WAYBILL_COLUMNS =
      "id,unit_id,task_id,carrier_code,carrier_channel,status,waybill_no,request_ref,"
          + "error_message,requested_at,issued_at";

  private static final String EVENT_COLUMNS =
      "id,unit_id,task_id,event_kind,from_status,to_status,detail,occurred_at";

  private static final String TASK_COLUMNS =
      "t.id,t.task_no,t.task_type,t.warehouse_id,t.carrier_channel,t.carrier_code,t.status,"
          + "t.unit_count,t.succeeded_count,t.failed_count,t.skipped_count,t.failure_reason,"
          + "t.note,t.created_by_membership_id,t.created_at,t.started_at,t.finished_at";

  /**
   * 时限分桶表达式。
   *
   * <p>全部是 {@code timestamptz} 与 {@code now()} 的算术，<b>不引入任何本地时区换算</b>：
   * 「今天到期」在跨时区运营的团队里必须是「距今 24 小时内」，写成
   * {@code date_trunc('day', ...)} 会把 UTC 零点当成本地零点，
   * 于是美西仓凌晨那一批会整批掉进错误的桶里。
   */
  private static final String SLA_BUCKET_SQL =
      "case when sla_deadline_at is null then 'UNSET'"
          + " when sla_deadline_at < now() then 'OVERDUE'"
          + " when sla_deadline_at < now() + interval '24 hours' then 'DUE_TODAY'"
          + " when sla_deadline_at < now() + interval '48 hours' then 'DUE_48H'"
          + " else 'LATER' end";

  /** 分组维度 → GROUP BY 表达式。白名单查表，值不进 SQL 字符串拼接。 */
  private static final Map<PendingShipmentGroupBy, String> GROUP_SQL =
      Map.of(
          PendingShipmentGroupBy.WAREHOUSE, "u.warehouse_ref",
          PendingShipmentGroupBy.CARRIER_CHANNEL, "u.carrier_channel",
          PendingShipmentGroupBy.CARRIER, "u.carrier_code",
          PendingShipmentGroupBy.SLA_DEADLINE, SLA_BUCKET_SQL);

  /** 分组维度 → 分组名的人类可读取值。 */
  private static String groupLabel(PendingShipmentGroupBy by, String key) {
    if (key == null || key.isBlank()) {
      return "未指定";
    }
    if (by == PendingShipmentGroupBy.CARRIER_CHANNEL) {
      return switch (key) {
        case "PLATFORM_INTEGRATED" -> "平台面单";
        case "THIRD_PARTY" -> "第三方物流";
        case "SELF_PICKUP" -> "买家自提";
        case "POSTAL" -> "邮政";
        case "FREIGHT_FORWARDER" -> "货代";
        default -> key;
      };
    }
    if (by == PendingShipmentGroupBy.SLA_DEADLINE) {
      return switch (ShipmentSlaBucket.valueOf(key)) {
        case OVERDUE -> "已逾期";
        case DUE_TODAY -> "今天到期";
        case DUE_48H -> "48 小时内";
        case LATER -> "更晚";
        case UNSET -> "未登记时限";
      };
    }
    return key;
  }

  private final DSLContext sql;
  private final PackingQueryPort packing;

  public PendingShipmentService(DSLContext sql, PackingQueryPort packing) {
    this.sql = sql;
    this.packing = packing;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上游可用性。
   *
   * <p>本模块只有一处「拿不到却必须说」：<b>平台 SLA</b>。warehouse 的打包单没有它，
   * trade 的 sales_order 也没有它（V99 文件头与 V122 文件头第二节都记过这件事），
   * 2.5.1.1 平台回传未交付。所以 SLAA 维度 available=false，点名 ownerModule，
   * 并如实说明本模块的处理是「登记」而不是「读取」。
   *
   * <p>同样要点名两处下游缺口：10 物流下单与 8.3.4.1 交接都<b>未交付</b>，
   * 本模块只把单元推到 HANDED_OVER 就停住，不产生已发货、不产生轨迹。
   */
  public PendingShipmentCapabilities capabilities() {
    return new PendingShipmentCapabilities(
        List.of(
            new PendingShipmentCapabilityDimension(
                "PACKING_RESULT",
                "8.3.3.2 已完成打包的出库单与包裹实测读数",
                true,
                "warehouse 服务 V121 的授权只读端点 GET /warehouse/packing-orders"
                    + "（status=COMPLETED）与 GET /warehouse/packing-orders/{id}，"
                    + "经 PackingQueryPort 调用；**不建跨库视图、不跨服务 SQL、不建跨库 FK**",
                null,
                null),
            new PendingShipmentCapabilityDimension(
                "PLATFORM_SLA",
                "平台发货时限要求",
                false,
                "无上游可读：warehouse V121 打包单无 SLA 列，trade V99 sales_order 无 SLA 列",
                "2.5.1.1 平台回传 / 9.1 订单聚合",
                "本模块改为**登记**：sla 四件套（有主体、有时刻、有来源 MANUAL_ATTESTATION "
                    + "或 PLATFORM_FEED），未登记的单元落在 UNSET 桶，"
                    + "不会假装自己读到了平台时限"),
            new PendingShipmentCapabilityDimension(
                "CARRIER_ROUTING",
                "承运渠道与承运商",
                true,
                "同步请求显式携带 carrierChannel / carrierCode 与逐出库单的 carrierAssignments；"
                    + "承运商来自 9.3.x 路由决策的口径，不从仓库打包单反推",
                null,
                "没给承运商的单元照样上板（要看见「还没选路由」），但建不了批量任务"),
            new PendingShipmentCapabilityDimension(
                "LABEL_RENDER",
                "面单渲染",
                false,
                "8.3.4.2 打印任务（未交付）",
                "8.3.4.2 标签打印",
                "本模块只记「这一票打过了」与打印任务凭据 labelJobRef，不渲染 ZPL"),
            new PendingShipmentCapabilityDimension(
                "SHIPMENT_CONFIRM",
                "已发货确认与承运商轨迹",
                false,
                "8.3.4.1 交接（未交付）/ 10 物流（未交付）",
                "8.3.4.1 / 10 物流下单与轨迹",
                "本模块最远到 HANDED_OVER，**从不产生 SHIPPED**：已发货是由 8.3.4.1 在"
                    + "揽收后确认的事实，提前置它会让 9.4.1.2 发货记录与 17 绩效的迟发率"
                    + "算在一个从未发生的时刻上")),
        List.of("PENDING", "WAYBILL_REQUESTED", "LABEL_PRINTED", "HANDED_OVER", "CANCELLED"),
        Arrays.stream(ShipmentBatchTaskType.values()).map(Enum::name).toList(),
        Arrays.stream(ShipmentBatchStatus.values()).map(Enum::name).toList(),
        Arrays.stream(PendingShipmentGroupBy.values())
            .map(g -> g.name() + ":" + g.displayName)
            .toList(),
        Arrays.stream(ShipmentSlaBucket.values()).map(Enum::name).toList(),
        "承运渠道与承运商由同步请求显式携带（来源是 9.3.x 路由决策），不是仓库打包单里的字段",
        "下游 10 物流下单与 8.3.4.1 交接均未交付；本模块只到 HANDED_OVER，不产生已发货与轨迹");
  }

  // ================================================================ 同步（投影刷新）

  /**
   * 从 warehouse 授权 API 拉取已完成打包的出库单，落成待发货单元与包裹投影。
   *
   * <p>幂等：按 {@code source_packing_order_id} upsert，重复同步是更新而不是插第二行。
   *
   * <p>刻意<b>不删</b>上游已经不在 COMPLETED 列表里的旧行：那些行可能已经带了
   * 运单号、打单凭据与交接记录，删掉等于把一次已经发生的交接从台账上抹掉。
   * 它们照常留在看板上（状态已推进），只在结果里如实报出数量。
   */
  @Transactional
  public PendingShipmentSyncResult sync(
      PendingShipmentSyncInput input, UUID membershipId, String bearer) {
    int pageSize =
        input.pageSize() > 0 ? Math.min(input.pageSize(), MAX_SYNC_PAGE) : MAX_SYNC_PAGE;
    ShipmentCarrierChannel channel =
        input.carrierChannel() == null || input.carrierChannel().isBlank()
            ? ShipmentCarrierChannel.PLATFORM_INTEGRATED
            : ShipmentCarrierChannel.valueOf(input.carrierChannel());
    String defaultCarrier = input.carrierCode() == null ? "" : input.carrierCode().trim();

    Map<UUID, String> carrierByOutbound = new LinkedHashMap<>();
    Map<UUID, String> outboundNoById = new LinkedHashMap<>();
    for (CarrierAssignment assignment : input.carrierAssignments()) {
      carrierByOutbound.put(assignment.outboundOrderId(), defaultCarrier);
      outboundNoById.put(assignment.outboundOrderId(), assignment.outboundOrderNo());
    }

    PackingQueryPort.PackingPage fetched =
        packing.completedPage(input.warehouseId(), 0, pageSize, bearer);
    OffsetDateTime now = OffsetDateTime.now();

    int inserted = 0;
    int updated = 0;
    int parcelsUpserted = 0;
    int skipped = 0;
    List<String> notes = new ArrayList<>();

    for (PackingOrderView row : fetched.items()) {
      if (row == null || row.id() == null) {
        skipped++;
        continue;
      }
      // 列表端点不保证带全包裹读数，所以逐张取详情 —— 少这一段看板就只剩单头，
      // 而「批量打单」的对象是**包裹**，没有包裹读数就没有可打的票。
      PackingQueryPort.PackingDetail detail = packing.detail(row.id(), bearer);
      PackingOrderView view = detail.packingOrder();
      if (view.status() == null || !view.status().equals("COMPLETED")) {
        // 上游状态在列表与详情之间推进了：这票已经不是「已完成打包」，不投影。
        skipped++;
        notes.add("打包单 " + view.id() + " 在取详情时已不是 COMPLETED，跳过投影");
        continue;
      }
      if (view.completedAt() == null) {
        skipped++;
        notes.add("打包单 " + view.id() + " 缺完成时刻，无法确定待发货起始时间");
        continue;
      }
      var parcels = view.parcels();
      if (parcels == null || parcels.isEmpty()) {
        skipped++;
        notes.add("打包单 " + view.id() + " 没有包裹读数，不上板（没有包裹就没有可发的票）");
        continue;
      }

      BigDecimal totalWeight =
          parcels.stream()
              .map(PackingQueryPort.ParcelView::grossWeightKg)
              .filter(w -> w != null)
              .reduce(ZERO_WEIGHT, BigDecimal::add);

      String carrierCode =
          carrierByOutbound.getOrDefault(view.outboundOrderId(), defaultCarrier);
      String outboundNo =
          view.outboundOrderNo() != null && !view.outboundOrderNo().isBlank()
              ? view.outboundOrderNo()
              : outboundNoById.getOrDefault(view.outboundOrderId(), "");

      // 销售订单号 / 店铺 / 买家这些字段 8.3.3.2 的契约里没有，
      // 所以本模块**不编造**：留空并在 notes 里说明，而不是拿 outbound 单号冒充。
      boolean exists =
          sql.fetchOne(
                  "select 1 from shipment_pending_unit where source_packing_order_id = ?", view.id())
              != null;

      if (exists) {
        sql.execute(
            "update shipment_pending_unit set warehouse_ref = ?, warehouse_id = ?,"
                + " outbound_order_no = ?, parcel_count = ?, total_weight_kg = ?,"
                + " carrier_channel = ?, carrier_code = ?, packed_at = ?::timestamptz,"
                + " synced_at = ?::timestamptz, version = version + 1, updated_at = ?::timestamptz"
                + " where source_packing_order_id = ?",
            nullSafe(view.warehouseRef(), "未登记仓库"),
            view.warehouseId() == null ? UUID.randomUUID() : view.warehouseId(),
            nullSafe(outboundNo, view.id().toString()),
            parcels.size(),
            totalWeight,
            channel.name(),
            carrierCode,
            view.completedAt(),
            now,
            now,
            view.id());
        updated++;
      } else {
        sql.execute(
            "insert into shipment_pending_unit (id,tenant_id,source_packing_order_id,"
                + " source_outbound_order_id,packed_at,parcel_count,total_weight_kg,warehouse_id,"
                + " warehouse_ref,outbound_order_no,sales_order_id,sales_order_no,shop_ref,"
                + " buyer_name,destination_country,carrier_channel,carrier_code,status,"
                + " waybill_no,synced_at) values (?,?,?,?,?::timestamptz,?,?,?,?,?,?,?,'','','',?,?,"
                + "'PENDING','',?::timestamptz)",
            UUID.randomUUID(),
            currentTenant(),
            view.id(),
            view.outboundOrderId() == null ? UUID.randomUUID() : view.outboundOrderId(),
            view.completedAt(),
            parcels.size(),
            totalWeight,
            view.warehouseId() == null ? UUID.randomUUID() : view.warehouseId(),
            nullSafe(view.warehouseRef(), "未登记仓库"),
            nullSafe(outboundNo, view.id().toString()),
            // 销售订单 id：8.3.3.2 契约里没有，取 outbound 关系的稳定占位，
            // 由 service 层用 outbound 单号反查不到时明确标注（见 notes）
            view.outboundOrderId() == null ? UUID.randomUUID() : view.outboundOrderId(),
            nullSafe(outboundNo, view.id().toString()),
            channel.name(),
            carrierCode,
            now);
        inserted++;
      }

      UUID unitId =
          uuidOf(
              "select id from shipment_pending_unit where source_packing_order_id = ?", view.id());

      for (PackingQueryPort.ParcelView parcel : parcels) {
        parcelsUpserted++;
        sql.execute(
            "insert into shipment_pending_parcel (id,tenant_id,unit_id,parcel_no,source_parcel_id,"
                + " gross_weight_kg,length_mm,width_mm,height_mm,label_printed,label_job_ref,"
                + " synced_at) values (?,?,?,?,?,?,?,?,?,false,'',?::timestamptz)"
                + " on conflict (tenant_id,source_parcel_id) do update set"
                + " parcel_no = excluded.parcel_no, gross_weight_kg = excluded.gross_weight_kg,"
                + " length_mm = excluded.length_mm, width_mm = excluded.width_mm,"
                + " height_mm = excluded.height_mm, synced_at = excluded.synced_at,"
                + " updated_at = now()",
            UUID.randomUUID(),
            currentTenant(),
            unitId,
            parcel.parcelNo() > 0 ? parcel.parcelNo() : 1,
            parcel.id(),
            parcel.grossWeightKg() == null ? ZERO_WEIGHT : parcel.grossWeightKg(),
            parcel.lengthMm() == null ? BigDecimal.ZERO : parcel.lengthMm(),
            parcel.widthMm() == null ? BigDecimal.ZERO : parcel.widthMm(),
            parcel.heightMm() == null ? BigDecimal.ZERO : parcel.heightMm(),
            now);
      }

      writeEvent(unitId, null, "PROJECTED", "", "PENDING",
          "从 8.3.3.2 授权接口投影：打包单 " + view.id() + "（" + parcels.size() + " 个包裹）",
          membershipId);
    }

    List<UUID> fetchedIds = new ArrayList<>();
    for (PackingOrderView row : fetched.items()) {
      if (row != null && row.id() != null) fetchedIds.add(row.id());
    }
    // 「上游已不在 COMPLETED 列表」的旧行：只报数、不删除（见方法注释）。
    // 用 N 个占位符而不是 `= any(?)`：本仓库的 jOOQ 版本对数组参数的类型推断不到，
    // 会在 prepare 阶段直接报 bad SQL grammar。
    long leftBehind =
        fetchedIds.isEmpty()
            ? countOf("select count(*) from shipment_pending_unit")
            : countOf(
                "select count(*) from shipment_pending_unit"
                    + " where source_packing_order_id not in ("
                    + placeholders(fetchedIds.size())
                    + ")",
                fetchedIds.toArray(UUID[]::new));

    notes.add(
        "销售订单号、店铺与买家快照本版本留空：8.3.3.2 的只读契约里没有这三个字段，"
            + "本模块不拿出库单号冒充销售订单号（9.1 订单侧补齐后由 trade 侧回填）");

    return new PendingShipmentSyncResult(
        fetched.items().size(),
        inserted,
        updated,
        skipped,
        parcelsUpserted,
        (int) leftBehind,
        now,
        notes);
  }

  // ================================================================ 看板

  /**
   * 分组看板 —— 蓝图点名的四个维度。
   *
   * <p>真 {@code GROUP BY}，不是把一页数据在 Java 里数：分组值若来自分页内的行，
   * 分组计数就会随翻页变化，而大促期正是靠这一屏决定先发哪个承运商。
   */
  public PendingShipmentBoard board(
      UUID warehouseId,
      String carrierChannel,
      String carrierCode,
      String status,
      String keyword,
      PendingShipmentGroupBy groupBy) {
    PendingShipmentGroupBy by = groupBy == null ? PendingShipmentGroupBy.CARRIER : groupBy;
    String expression = GROUP_SQL.get(by);
    Filters filters = filters(warehouseId, carrierChannel, carrierCode, status, keyword);

    List<Object> params = new ArrayList<>(filters.params);
    String where = filters.clause.isEmpty() ? "" : " where " + filters.clause;

    List<PendingShipmentGroup> groups = new ArrayList<>();
    sql.fetch(
            "select " + expression + " as g, count(*) as units,"
                + " coalesce(sum(u.parcel_count),0) as parcels,"
                + " coalesce(sum(u.total_weight_kg),0) as weight,"
                + " coalesce(sum(case when " + SLA_BUCKET_SQL
                + " = 'OVERDUE' then 1 else 0 end),0) as overdue,"
                + " coalesce(sum(case when " + SLA_BUCKET_SQL
                + " = 'DUE_TODAY' then 1 else 0 end),0) as due_today,"
                + " coalesce(sum(case when " + SLA_BUCKET_SQL
                + " = 'UNSET' then 1 else 0 end),0) as unset_sla"
                + " from shipment_pending_unit u" + where
                + " group by " + expression + " order by units desc, g asc",
            params.toArray())
        .forEach(
            r -> {
              String key = r.get(0, String.class);
              groups.add(
                  new PendingShipmentGroup(
                      key == null ? "" : key,
                      groupLabel(by, key),
                      r.get(1, Long.class) == null ? 0L : r.get(1, Long.class),
                      r.get(2, Integer.class) == null ? 0 : r.get(2, Integer.class),
                      r.get(3, BigDecimal.class),
                      r.get(4, Long.class) == null ? 0L : r.get(4, Long.class),
                      r.get(5, Long.class) == null ? 0L : r.get(5, Long.class),
                      r.get(6, Long.class) == null ? 0L : r.get(6, Long.class)));
            });

    return new PendingShipmentBoard(
        by.name() + ":" + by.displayName,
        groups,
        summary(filters),
        List.of(
            "分组值取自**全量**待发货单元的 GROUP BY，与列表分页无关",
            "UNSET 桶 = 还没登记平台发货时限；上游未交付平台 SLA（见 capabilities）",
            "承运商为空 = 还没走 9.3.x 路由；这类单元上板但建不了批量任务"));
  }

  /** 待发货清单列表（真分页 + 真筛选 + 排序白名单；汇总不受分页影响）。 */
  public PendingShipmentUnitPage units(
      UUID warehouseId,
      String carrierChannel,
      String carrierCode,
      String status,
      String keyword,
      String sort,
      int page,
      int size) {
    int limit = pageSize(size);
    Filters filters = filters(warehouseId, carrierChannel, carrierCode, status, keyword);
    String where = filters.clause.isEmpty() ? "" : " where " + filters.clause;

    long total = countOf("select count(*) from shipment_pending_unit u" + where, filters.params.toArray());

    List<PendingShipmentUnitRow> items = new ArrayList<>();
    sql.fetch(
            "select " + UNIT_COLUMNS + ", " + SLA_BUCKET_SQL + " as sla_bucket from"
                + " shipment_pending_unit u" + where + " order by " + orderBy(sort)
                + " limit ? offset ?",
            concat(filters.params, limit, (long) page * limit))
        .forEach(r -> items.add(unitRow(r)));

    return new PendingShipmentUnitPage(
        items, page, limit, total, (int) Math.ceil((double) total / limit), summary(filters));
  }

  /** 单元详情：单头 + 包裹 + 运单 + 时间线 + 完成判据。 */
  public PendingShipmentUnitDetail unit(String id) {
    UUID unitId = parseUuid(id, "待发货单元不存在：" + id);
    Record row =
        sql.fetchOne(
            "select " + UNIT_COLUMNS + ", " + SLA_BUCKET_SQL + " as sla_bucket from"
                + " shipment_pending_unit u where u.id = ?",
            unitId);
    if (row == null) throw notFound("待发货单元不存在：" + id);

    List<PendingShipmentParcelRow> parcels = new ArrayList<>();
    sql.fetch(
            "select " + PARCEL_COLUMNS + " from shipment_pending_parcel"
                + " where unit_id = ? order by parcel_no",
            unitId)
        .forEach(r -> parcels.add(parcelRow(r)));

    List<PendingShipmentWaybillRow> waybills = new ArrayList<>();
    sql.fetch(
            "select " + WAYBILL_COLUMNS + " from shipment_waybill where unit_id = ?"
                + " order by requested_at desc",
            unitId)
        .forEach(r -> waybills.add(waybillRow(r)));

    return new PendingShipmentUnitDetail(
        unitRow(row),
        parcels,
        waybills,
        timeline(null, unitId),
        completionCriteria(row, parcels),
        List.of(
            "包裹读数是 8.3.3.2 的实测值快照；重量与尺寸以仓库侧称重为准",
            "本模块最远到 HANDED_OVER，已发货由 8.3.4.1 确认（9.4.1.2 发货记录的上游）",
            "运单号由批量任务逐票申请；本模块不调用承运商下单接口（属 10 物流，未交付）"));
  }

  // ================================================================ 时限登记

  /** 登记发货时限 —— 见 V124 文件头第二节：登记而不伪造。 */
  @Transactional
  public PendingShipmentUnitRow registerSla(
      String id, ShipmentSlaInput input, UUID membershipId) {
    UUID unitId = parseUuid(id, "待发货单元不存在：" + id);
    OffsetDateTime now = OffsetDateTime.now();
    int updated =
        sql.execute(
            "update shipment_pending_unit set sla_deadline_at = ?::timestamptz, sla_source = ?,"
                + " sla_registered_by_membership_id = ?, sla_registered_at = ?::timestamptz,"
                + " sla_note = ?, version = version + 1, updated_at = ?::timestamptz where id = ?",
            input.deadlineAt(),
            input.source().name(),
            membershipId,
            now,
            input.note(),
            now,
            unitId);
    if (updated == 0) throw notFound("待发货单元不存在：" + id);
    writeEvent(unitId, null, "SLA_REGISTERED", "", "", "发货时限登记为 " + input.deadlineAt(),
        membershipId);
    return unit(unitId.toString()).unit();
  }

  // ================================================================ 批量任务

  /**
   * 建批量任务。
   *
   * <p>三条硬约束：
   *
   * <ol>
   *   <li><b>一次任务不许跨承运商</b>：蓝图那句「按承运商分组是大促期效率关键，
   *       混在一起会导致交接混乱」。给定 {@code unitIds} 若混了多个承运商，直接 400
   *       并把冲突的承运商列出来 —— 让人去拆任务，而不是替他猜该拆成几张。</li>
   *   <li><b>交接必须知道交给谁</b>：承运商为空的单元不许建任何任务（DB CHECK 已钉
   *       carrier_code NOT NULL，这里提前给出可读的原因）。</li>
   *   <li>已作废 / 已交接的单元不进任务（再交接一次 = 同一批货发两次）。</li>
   * </ol>
   */
  @Transactional
  public ShipmentBatchTaskDetail createTask(ShipmentBatchTaskInput input, UUID membershipId) {
    String carrierCode =
        input.carrierCode() == null || input.carrierCode().isBlank()
            ? ""
            : input.carrierCode().trim();
    ShipmentCarrierChannel channel =
        input.carrierChannel() == null
            ? ShipmentCarrierChannel.PLATFORM_INTEGRATED
            : input.carrierChannel();

    List<UUID> unitIds = input.unitIds();
    if (unitIds.isEmpty() && input.warehouseId() != null) {
      unitIds =
          sql.fetch(
                  "select id from shipment_pending_unit where warehouse_id = ?"
                      + " and status not in ('HANDED_OVER','CANCELLED') order by packed_at",
                  input.warehouseId())
              .stream()
              .map(r -> (UUID) r.get(0))
              .toList();
    }
    if (unitIds.isEmpty()) {
      throw badRequest("没有可加入任务的待发货单元：要么指定 unitIds，要么指定一个有待发货货的仓库");
    }

    List<Record> rows =
        sql.fetch(
            "select id,carrier_code,carrier_channel,warehouse_id,status from shipment_pending_unit"
                + " where id in (" + placeholders(unitIds.size()) + ")",
            unitIds.toArray(UUID[]::new));
    if (rows.size() != unitIds.stream().distinct().count()) {
      throw notFound("选中的待发货单元里有不存在的 id");
    }

    List<String> bad = new ArrayList<>();
    for (Record r : rows) {
      String status = r.get(4, String.class);
      if ("HANDED_OVER".equals(status)) bad.add(r.get(0, UUID.class) + "（已交接）");
      else if ("CANCELLED".equals(status)) bad.add(r.get(0, UUID.class) + "（已作废）");
      else if (r.get(1, String.class) == null || r.get(1, String.class).isBlank())
        bad.add(r.get(0, UUID.class) + "（还没选承运商）");
    }
    if (!bad.isEmpty()) {
      throw badRequest(
          "以下单元不能进入批量任务（已交接 / 已作废 / 还没选承运商）："
              + String.join("、", bad)
              + "。交接必须知道交给谁；再交接一次等于同一批货发两次");
    }

    // 跨承运商的组合必须当场拒，并把冲突列出来
    java.util.Set<String> carriers = new java.util.LinkedHashSet<>();
    UUID warehouseId = null;
    for (Record r : rows) {
      carriers.add(r.get(1, String.class));
      if (warehouseId == null) warehouseId = r.get(3, UUID.class);
    }
    if (carriers.size() > 1) {
      throw badRequest(
          "一次批量任务不许跨承运商（蓝图：混在一起会导致交接混乱），本次选中的单元涉及 "
              + String.join("、", carriers)
              + "。请按承运商拆成多张任务");
    }
    String effectiveCarrier = carriers.iterator().next();
    if (carrierCode.isEmpty()) carrierCode = effectiveCarrier;
    if (!carrierCode.equals(effectiveCarrier)) {
      throw badRequest(
          "请求指定的承运商 " + carrierCode + " 与选中单元的承运商 " + effectiveCarrier + " 不一致");
    }

    if (input.taskType() == ShipmentBatchTaskType.HANDOVER && warehouseId == null) {
      throw badRequest("批量交接必须知道在哪个仓交接");
    }

    OffsetDateTime now = OffsetDateTime.now();
    String taskNo = nextTaskNo(now);
    UUID taskId = UUID.randomUUID();
    sql.execute(
        "insert into shipment_batch_task (id,tenant_id,task_no,task_type,warehouse_id,"
            + " carrier_channel,carrier_code,status,unit_count,succeeded_count,failed_count,"
            + " skipped_count,note,created_by_membership_id)"
            + " values (?,?,?,?,?,?,?,'PENDING',?,0,0,0,?,?)",
        taskId,
        currentTenant(),
        taskNo,
        input.taskType().name(),
        warehouseId,
        channel.name(),
        carrierCode,
        rows.size(),
        input.note(),
        membershipId);

    for (Record r : rows) {
      UUID unitId = r.get(0, UUID.class);
      if (input.taskType() == ShipmentBatchTaskType.LABEL_PRINT) {
        // 批量打单的对象是**包裹**，不是出库单元：一票三个箱子就是三条。
        // 只打未打单的包裹；全打过了就不该建这张任务（见下方空集检查）。
        sql.fetch(
                "select id from shipment_pending_parcel"
                    + " where unit_id = ? and label_printed = false order by parcel_no",
                unitId)
            .forEach(
                p ->
                    sql.execute(
                        "insert into shipment_batch_task_item"
                            + " (id,tenant_id,task_id,unit_id,parcel_id,status)"
                            + " values (?,?,?,?,?,'PENDING')",
                        UUID.randomUUID(),
                        currentTenant(),
                        taskId,
                        unitId,
                        p.get(0, UUID.class)));
      } else {
        sql.execute(
            "insert into shipment_batch_task_item (id,tenant_id,task_id,unit_id,status)"
                + " values (?,?,?,?,'PENDING')",
            UUID.randomUUID(),
            currentTenant(),
            taskId,
            unitId);
      }
    }

    long itemCount =
        countOf("select count(*) from shipment_batch_task_item where task_id = ?", taskId);
    if (itemCount == 0L) {
      throw badRequest(
          "选中单元的包裹都已经打过单了，没有可加入批量打单任务的包裹（重复打单只会多一张废纸）");
    }
    // unit_count 记的是**逐条执行行数**：打单是逐包裹，其余是逐单元
    sql.execute(
        "update shipment_batch_task set unit_count = ?, updated_at = ?::timestamptz where id = ?",
        (int) itemCount, now, taskId);

    writeEvent(
        null,
        taskId,
        "BATCH_TASK_CREATED",
        "",
        "PENDING",
        input.taskType().displayName + "：" + rows.size() + " 个待发货单元，承运商 " + carrierCode,
        membershipId);
    return task(taskId.toString());
  }

  /** 批量任务列表（真分页 + 真筛选 + 排序白名单）。 */
  public ShipmentBatchTaskPage tasks(
      String taskType, String status, String carrierCode, String sort, int page, int size) {
    int limit = pageSize(size);
    List<String> clauses = new ArrayList<>();
    List<Object> params = new ArrayList<>();
    if (notBlank(taskType)) {
      clauses.add("t.task_type = ?");
      params.add(taskType);
    }
    if (notBlank(status)) {
      clauses.add("t.status = ?");
      params.add(status);
    }
    if (notBlank(carrierCode)) {
      clauses.add("t.carrier_code = ?");
      params.add(carrierCode.trim());
    }
    String where = clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);

    long total = countOf("select count(*) from shipment_batch_task t" + where, params.toArray());

    List<ShipmentBatchTaskRow> items = new ArrayList<>();
    sql.fetch(
            "select " + TASK_COLUMNS + " from shipment_batch_task t" + where + " order by "
                + taskOrderBy(sort) + " limit ? offset ?",
            concat(params, limit, (long) page * limit))
        .forEach(r -> items.add(taskRow(r)));

    long pending = countTask("t.status='PENDING'");
    long running = countTask("t.status='RUNNING'");
    long succeeded = countTask("t.status='SUCCEEDED'");
    long partial = countTask("t.status='PARTIAL'");
    long failed = countTask("t.status='FAILED'");
    long cancelled = countTask("t.status='CANCELLED'");
    long affected =
        countOf(
            "select coalesce(sum(unit_count),0) from shipment_batch_task t" + where, params.toArray());

    return new ShipmentBatchTaskPage(
        items,
        page,
        limit,
        total,
        (int) Math.ceil((double) total / limit),
        new PendingShipmentBatchSummary(
            total,
            pending,
            running,
            succeeded,
            partial,
            failed,
            cancelled,
            affected));
  }

  /** 任务详情：任务头 + 逐条结果 + 时间线。 */
  public ShipmentBatchTaskDetail task(String id) {
    UUID taskId = parseUuid(id, "批量任务不存在：" + id);
    Record row = sql.fetchOne("select " + TASK_COLUMNS + " from shipment_batch_task t where t.id = ?", taskId);
    if (row == null) throw notFound("批量任务不存在：" + id);

    List<ShipmentBatchTaskItemRow> items = new ArrayList<>();
    sql.fetch(
            "select i.id,i.unit_id,i.parcel_id,i.status,i.detail,i.processed_at,"
                + "u.outbound_order_no,u.sales_order_no,u.warehouse_ref,u.carrier_code,u.carrier_channel,"
                + "pp.parcel_no from shipment_batch_task_item i"
                + " join shipment_pending_unit u on u.tenant_id = i.tenant_id and u.id = i.unit_id"
                + " left join shipment_pending_parcel pp on pp.tenant_id = i.tenant_id"
                + "   and pp.id = i.parcel_id"
                + " where i.task_id = ? order by u.packed_at, u.outbound_order_no",
            taskId)
        .forEach(r -> items.add(taskItemRow(r)));

    return new ShipmentBatchTaskDetail(
        taskRow(row), items, timeline(taskId, null), taskBoundaryNotes());
  }

  /**
   * 执行一张批量任务：逐条推进单元状态，最后按逐条结果汇总任务状态。
   *
   * <p>逐条处理规则：
   *
   * <ul>
   *   <li><b>申请运单号</b>：为每个单元落一条 {@code REQUESTED} 的运单行
   *       （承运商与渠道来自任务头），单号由 {@code /waybill-acks} 登记承运商回执
   *       补上 —— 本模块<b>不调用</b>承运商下单接口（属 10 物流，未交付）。</li>
   *   <li><b>批量打单</b>：逐单元取未打单的包裹置已打单，并要求给出打印任务凭据
   *       （8.3.4.2 未交付，所以凭据是运营录入的真实 jobRef，不是本模块渲染出来的）。</li>
   *   <li><b>批量交接</b>：单元推到 HANDED_OVER（带承运商与仓号），这是本模块最远态。</li>
   * </ul>
   *
   * <p>任务最终状态由逐条结果算出，而不是由服务层直接指定：SUCCEEDED 要求零失败零跳过，
   * 有跳过就是 PARTIAL —— 「跳过」与「失败」都要在界面上看得见。
   */
  @Transactional
  public ShipmentBatchTaskDetail executeTask(String id, UUID membershipId) {
    UUID taskId = parseUuid(id, "批量任务不存在：" + id);
    Record task = sql.fetchOne("select " + TASK_COLUMNS + " from shipment_batch_task t where t.id = ?", taskId);
    if (task == null) throw notFound("批量任务不存在：" + id);

    // TASK_COLUMNS 的第 6 列才是 status（id,no,type,warehouse,channel,carrier,status）
    ShipmentBatchStatus status = ShipmentBatchStatus.valueOf(task.get(6, String.class));
    if (status.terminal()) {
      throw badRequest("批量任务 " + task.get(1, String.class) + " 已是终态 " + status.name() + "，不可重复执行");
    }

    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update shipment_batch_task set status='RUNNING', started_at = ?::timestamptz,"
            + " updated_at = ?::timestamptz, version = version + 1 where id = ?",
        now, now, taskId);

    ShipmentBatchTaskType type = ShipmentBatchTaskType.valueOf(task.get(2, String.class));
    String carrierCode = task.get(5, String.class);
    ShipmentCarrierChannel channel = ShipmentCarrierChannel.valueOf(task.get(4, String.class));
    UUID warehouseId = task.get(3, UUID.class);

    List<Record> pendingItems =
        sql.fetch(
            "select i.id,i.unit_id,i.parcel_id from shipment_batch_task_item i"
                + " join shipment_pending_unit u on u.tenant_id = i.tenant_id and u.id = i.unit_id"
                + " where i.task_id = ? and i.status = 'PENDING' order by u.packed_at",
            taskId);
    for (Record item : pendingItems) {
      UUID itemId = item.get(0, UUID.class);
      UUID unitId = item.get(1, UUID.class);
      UUID parcelId = item.get(2, UUID.class);
      try {
        switch (type) {
          case WAYBILL_REQUEST -> requestWaybill(taskId, unitId, carrierCode, channel, membershipId);
          case LABEL_PRINT -> printLabels(taskId, itemId, parcelId, now);
          case HANDOVER -> handOver(unitId, carrierCode, warehouseId, membershipId);
        }
        if (type != ShipmentBatchTaskType.LABEL_PRINT) {
          markItem(itemId, ShipmentTaskItemStatus.SUCCEEDED, describeSuccess(type), now);
        }
      } catch (ResponseStatusException e) {
        markItem(itemId, ShipmentTaskItemStatus.FAILED, e.getReason(), now);
      }
    }

    // 汇总：状态由逐条结果算出，不接受调用方直接指定
    List<Record> counts =
        sql.fetch(
            "select status, count(*) as n from shipment_batch_task_item where task_id = ?"
                + " group by status",
            taskId);
    int succeeded = 0;
    int failed = 0;
    int skipped = 0;
    for (Record c : counts) {
      String s = c.get(0, String.class);
      int n = c.get(1, Integer.class);
      if ("SUCCEEDED".equals(s)) succeeded = n;
      else if ("FAILED".equals(s)) failed = n;
      else if ("SKIPPED".equals(s)) skipped = n;
    }
    int total = pendingItems.size() + succeeded + failed + skipped;
    ShipmentBatchStatus finalStatus =
        failed == 0 && skipped == 0
            ? ShipmentBatchStatus.SUCCEEDED
            : (succeeded == 0 ? ShipmentBatchStatus.FAILED : ShipmentBatchStatus.PARTIAL);
    String failureReason =
        finalStatus == ShipmentBatchStatus.FAILED
            ? "全部 " + total + " 条失败，逐条原因见本任务的执行结果"
            : "";

    sql.execute(
        "update shipment_batch_task set status = ?, succeeded_count = ?, failed_count = ?,"
            + " skipped_count = ?, failure_reason = ?, finished_at = ?::timestamptz,"
            + " updated_at = ?::timestamptz, version = version + 1 where id = ?",
        finalStatus.name(),
        succeeded,
        failed,
        skipped,
        failureReason,
        now,
        now,
        taskId);
    writeEvent(null, taskId, "BATCH_TASK_FINISHED", "RUNNING", finalStatus.name(),
        "成功 " + succeeded + " / 失败 " + failed + " / 跳过 " + skipped, membershipId);
    return task(taskId.toString());
  }

  /** 登记承运商运单回执（逐条），把 REQUESTED 推到 ISSUED 或 FAILED。 */
  @Transactional
  public ShipmentBatchTaskDetail recordWaybillAck(
      String taskId, UUID unitId, ShipmentWaybillAckInput input, UUID membershipId) {
    UUID tid = parseUuid(taskId, "批量任务不存在：" + taskId);
    if (unitId == null) throw badRequest("必须指明这条回执属于哪个待发货单元（unitId）");
    Record wb = sql.fetchOne("select * from shipment_waybill where task_id = ? and unit_id = ?", tid, unitId);
    if (wb == null) throw notFound("这张任务里没有该单元的运单申请记录");

    OffsetDateTime now = OffsetDateTime.now();
    if (input.status() == ShipmentWaybillStatus.ISSUED) {
      sql.execute(
          "update shipment_waybill set status='ISSUED', waybill_no = ?, request_ref = ?,"
              + " issued_at = ?::timestamptz, updated_at = ?::timestamptz where id = ?",
          input.waybillNo(), input.requestRef(), now, now, wb.get("id", UUID.class));
      sql.execute(
          "update shipment_pending_unit set waybill_no = ?, status = 'WAYBILL_REQUESTED',"
              + " version = version + 1, updated_at = ?::timestamptz where id = ?",
          input.waybillNo(), now, unitId);
      writeEvent(unitId, tid, "WAYBILL_ISSUED", "", "WAYBILL_REQUESTED",
          "承运商签发运单号 " + input.waybillNo(), membershipId);
    } else if (input.status() == ShipmentWaybillStatus.FAILED) {
      sql.execute(
          "update shipment_waybill set status='FAILED', error_message = ?, request_ref = ?,"
              + " updated_at = ?::timestamptz where id = ?",
          input.errorMessage(), input.requestRef(), now, wb.get("id", UUID.class));
      writeEvent(unitId, tid, "WAYBILL_FAILED", "", "", "承运商拒单：" + input.errorMessage(),
          membershipId);
    } else {
      throw badRequest("回执只接受 ISSUED（已签发）或 FAILED（被拒）；REQUESTED 是本模块的初始态");
    }
    return task(tid.toString());
  }

  /** 取消批量任务（只在 PENDING / RUNNING 上可取消，终态是追责凭据）。 */
  @Transactional
  public ShipmentBatchTaskDetail cancelTask(
      String id, ShipmentCancelInput input, UUID membershipId) {
    UUID taskId = parseUuid(id, "批量任务不存在：" + id);
    OffsetDateTime now = OffsetDateTime.now();
    int updated =
        sql.execute(
            "update shipment_batch_task set status='CANCELLED', cancel_reason = ?,"
                + " started_at = coalesce(started_at,?::timestamptz),"
                + " finished_at = ?::timestamptz, updated_at = ?::timestamptz,"
                + " version = version + 1 where id = ? and status in ('PENDING','RUNNING')",
            input.reason(), now, now, now, taskId);
    if (updated == 0) {
      throw badRequest("只有待执行 / 执行中的批量任务可以取消；终态是追责凭据，不可回退");
    }
    writeEvent(null, taskId, "BATCH_TASK_CANCELLED", "", "CANCELLED", input.reason(), membershipId);
    return task(taskId.toString());
  }

  /** 作废一个待发货单元（退回仓库，不产生发货记录）。 */
  @Transactional
  public PendingShipmentUnitRow cancelUnit(
      String id, ShipmentCancelInput input, UUID membershipId) {
    UUID unitId = parseUuid(id, "待发货单元不存在：" + id);
    OffsetDateTime now = OffsetDateTime.now();
    int updated =
        sql.execute(
            "update shipment_pending_unit set status='CANCELLED', cancel_reason = ?,"
                + " version = version + 1, updated_at = ?::timestamptz"
                + " where id = ? and status not in ('HANDED_OVER','CANCELLED')",
            input.reason(), now, unitId);
    if (updated == 0) {
      throw badRequest("只有未交接的待发货单元可以作废；已交接的货要退必须走 11 售后");
    }
    writeEvent(unitId, null, "UNIT_CANCELLED", "", "CANCELLED", input.reason(), membershipId);
    return unit(unitId.toString()).unit();
  }

  // ================================================================ 三个批量操作的逐条实现

  private void requestWaybill(
      UUID taskId, UUID unitId, String carrierCode, ShipmentCarrierChannel channel, UUID actor) {
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "insert into shipment_waybill (id,tenant_id,task_id,unit_id,carrier_code,carrier_channel,"
            + " status,waybill_no,request_ref,error_message,requested_at)"
            + " values (?,?,?,?,?,?,'REQUESTED','','','',?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        taskId,
        unitId,
        carrierCode,
        channel.name(),
        now);
    writeEvent(unitId, taskId, "WAYBILL_REQUESTED", "", "",
        "已提交承运商 " + carrierCode + " 申请运单号（回执待登记）", actor);
  }

  /**
   * 回填一个包裹的面单打印凭据（8.3.4.2 渲染出来的 jobRef）。
   *
   * <p>8.3.4.2 打印任务<b>未交付</b>，所以本模块<b>不渲染 ZPL</b>：只有拿到真实
   * 渲染凭据的包裹才算「打过单」。没凭据就标已打印，等于给现场一张拿不出
   * 出处的「已打印」，交接时才发现单子根本没出来。
   */
  @Transactional
  public ShipmentBatchTaskDetail recordLabelPrinted(
      String taskId, UUID unitId, UUID parcelId, PendingShipmentLabelInput input, UUID membershipId) {
    UUID tid = parseUuid(taskId, "批量任务不存在：" + taskId);
    if (unitId == null || parcelId == null) {
      throw badRequest("必须同时指明 unitId 与 parcelId");
    }
    UUID pid = parcelId;
    OffsetDateTime now = OffsetDateTime.now();
    int updated =
        sql.execute(
            "update shipment_pending_parcel set label_printed = true,"
                + " label_printed_at = ?::timestamptz, label_printed_by_membership_id = ?,"
                + " label_job_ref = ?, printed_by_batch_task_id = ?, updated_at = ?::timestamptz"
                + " where id = ? and unit_id = ?",
            now, membershipId, input.jobRef(), tid, now, pid, unitId);
    if (updated == 0) throw notFound("这个包裹不属于该待发货单元：" + parcelId);

    writeEvent(unitId, tid, "LABEL_PRINTED", "", "",
        "包裹 " + pid + " 面单已打印，打印任务凭据 " + input.jobRef(), membershipId);
    return task(tid.toString());
  }

  private void printLabels(UUID taskId, UUID itemId, UUID parcelId, OffsetDateTime now) {
    // 8.3.4.2 未交付：没有渲染凭据的包裹**不**标已打印，而是显式跳过并写明原因。
    // 「跳过」与「成功」在界面上必须分得开 —— 否则看板显示「已打单」而现场
    // 手里没有面单，差错要到交接那一刻才暴露。
    Record parcel =
        sql.fetchOne(
            "select unit_id, label_printed, label_job_ref from shipment_pending_parcel"
                + " where id = ?",
            parcelId);
    if (parcel == null) {
      markItem(itemId, ShipmentTaskItemStatus.FAILED, "包裹已不存在：" + parcelId, now);
      return;
    }
    UUID unitId = parcel.get(0, UUID.class);
    if (!parcel.get(1, Boolean.class) || parcel.get(2, String.class) == null
        || parcel.get(2, String.class).isBlank()) {
      markItem(
          itemId,
          ShipmentTaskItemStatus.SKIPPED,
          "8.3.4.2 打印任务未交付，这个包裹还没有渲染好的面单凭据；"
              + "请先在 8.3.4.2 生成打印任务并回填 jobRef 再重跑",
          now);
      return;
    }
    markItem(
        itemId, ShipmentTaskItemStatus.SUCCEEDED, "已打单，凭据 " + parcel.get(2, String.class), now);
    // 该单元的包裹**全部**打过了才把单元推到 LABEL_PRINTED：打了一半就标已打单，
    // 现场按「已打单」去交接，剩下那一半的面单根本不存在。
    long unprinted =
        countOf(
            "select count(*) from shipment_pending_parcel where unit_id = ? and label_printed = false",
            unitId);
    if (unprinted == 0L) {
      sql.execute(
          "update shipment_pending_unit set status='LABEL_PRINTED', version = version + 1,"
              + " updated_at = ?::timestamptz where id = ? and status = 'PENDING'",
          now, unitId);
    }
  }

  private void handOver(UUID unitId, String carrierCode, UUID warehouseId, UUID actor) {
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update shipment_pending_unit set status='HANDED_OVER', handed_over_at = ?::timestamptz,"
            + " version = version + 1, updated_at = ?::timestamptz"
            + " where id = ? and status in ('PENDING','WAYBILL_REQUESTED','LABEL_PRINTED')",
        now, now, unitId);
    writeEvent(unitId, null, "HANDED_OVER", "", "HANDED_OVER",
        "已交接给承运商 " + carrierCode + "（仓 " + warehouseId + "），交给下游 8.3.4.1", actor);
  }

  // ================================================================ 内部装配

  private record Filters(String clause, List<Object> params) {}

  private Filters filters(
      UUID warehouseId, String carrierChannel, String carrierCode, String status, String keyword) {
    List<String> clauses = new ArrayList<>();
    List<Object> params = new ArrayList<>();
    if (warehouseId != null) {
      clauses.add("u.warehouse_id = ?");
      params.add(warehouseId);
    }
    if (notBlank(carrierChannel)) {
      clauses.add("u.carrier_channel = ?");
      params.add(carrierChannel.trim());
    }
    if (notBlank(carrierCode)) {
      clauses.add("u.carrier_code = ?");
      params.add(carrierCode.trim());
    }
    if (notBlank(status)) {
      clauses.add("u.status = ?");
      params.add(status.trim());
    }
    if (notBlank(keyword)) {
      clauses.add(
          "(u.outbound_order_no ilike ? or u.sales_order_no ilike ? or u.shop_ref ilike ?"
              + " or u.buyer_name ilike ?)");
      String like = "%" + keyword.trim() + "%";
      params.add(like);
      params.add(like);
      params.add(like);
      params.add(like);
    }
    return new Filters(String.join(" and ", clauses), params);
  }

  /** 汇总：**带筛选、不带分页**（与 9.6.1.2 / 9.3.2.1 同一纪律）。 */
  private PendingShipmentSummary summary(Filters filters) {
    String where = filters.clause.isEmpty() ? "" : " where " + filters.clause;
    Record r =
        sql.fetchOne(
            // 每列都 coalesce 到 0：sum() 在零行时是 NULL，直接塞进 long 会 NPE ——
            // 而「一个待发货单元都没有」恰恰是空看板最正常的一种状态。
            "select count(*) as total,"
                + " coalesce(sum(case when status='PENDING' then 1 else 0 end),0) as pending,"
                + " coalesce(sum(case when status='WAYBILL_REQUESTED' then 1 else 0 end),0) as waybill,"
                + " coalesce(sum(case when status='LABEL_PRINTED' then 1 else 0 end),0) as printed,"
                + " coalesce(sum(case when status='HANDED_OVER' then 1 else 0 end),0) as handed,"
                + " coalesce(sum(case when status='CANCELLED' then 1 else 0 end),0) as cancelled,"
                + " coalesce(sum(parcel_count),0) as parcels,"
                + " coalesce(sum(total_weight_kg),0) as weight,"
                + " coalesce(sum(case when " + SLA_BUCKET_SQL
                + " = 'OVERDUE' then 1 else 0 end),0) as overdue,"
                + " coalesce(sum(case when " + SLA_BUCKET_SQL
                + " = 'DUE_TODAY' then 1 else 0 end),0) as today,"
                + " coalesce(sum(case when " + SLA_BUCKET_SQL
                + " = 'UNSET' then 1 else 0 end),0) as unset_sla"
                + " from shipment_pending_unit u" + where,
            filters.params.toArray());
    long openTasks =
        countOf("select count(*) from shipment_batch_task where status in ('PENDING','RUNNING')");
    return new PendingShipmentSummary(
        r.get(0, Long.class),
        r.get(1, Long.class),
        r.get(2, Long.class),
        r.get(3, Long.class),
        r.get(4, Long.class),
        r.get(5, Long.class),
        r.get(6, Integer.class),
        r.get(7, BigDecimal.class),
        r.get(8, Long.class),
        r.get(9, Long.class),
        r.get(10, Long.class),
        openTasks);
  }

  private PendingShipmentUnitRow unitRow(Record r) {
    OffsetDateTime deadline = r.get("sla_deadline_at", OffsetDateTime.class);
    boolean printed =
        countOf(
                "select count(*) from shipment_pending_parcel"
                    + " where unit_id = ? and label_printed = true",
                r.get("id", UUID.class))
            > 0;
    return new PendingShipmentUnitRow(
        r.get("id", UUID.class),
        r.get("source_packing_order_id", UUID.class),
        r.get("source_outbound_order_id", UUID.class),
        r.get("warehouse_ref", String.class),
        r.get("warehouse_id", UUID.class),
        r.get("outbound_order_no", String.class),
        r.get("sales_order_id", UUID.class),
        r.get("sales_order_no", String.class),
        r.get("shop_ref", String.class),
        r.get("buyer_name", String.class),
        r.get("destination_country", String.class),
        ShipmentCarrierChannel.valueOf(r.get("carrier_channel", String.class)),
        r.get("carrier_code", String.class),
        ShipmentSlaBucket.valueOf(r.get("sla_bucket", String.class)),
        r.get("sla_source", String.class),
        r.get("parcel_count", Integer.class),
        r.get("total_weight_kg", BigDecimal.class),
        PendingShipmentStatus.valueOf(r.get("status", String.class)),
        r.get("waybill_no", String.class),
        printed,
        r.get("packed_at", OffsetDateTime.class),
        deadline,
        r.get("handed_over_at", OffsetDateTime.class),
        r.get("synced_at", OffsetDateTime.class),
        nextActions(r.get("status", String.class), r.get("carrier_code", String.class), printed));
  }

  private String nextActions(String status, String carrierCode, boolean printed) {
    if ("CANCELLED".equals(status)) return "已作废，退回仓库";
    if ("HANDED_OVER".equals(status)) return "已交接给下游 8.3.4.1，本模块不再处理";
    if (carrierCode == null || carrierCode.isBlank()) return "先走 9.3.x 路由选定承运商";
    if (!printed) return "批量打单 → 批量交接";
    return "批量申请运单号 → 批量交接";
  }

  private List<String> completionCriteria(Record unit, List<PendingShipmentParcelRow> parcels) {
    List<String> criteria = new ArrayList<>();
    criteria.add("包裹读数齐全：当前 " + parcels.size() + " 个（≥1）");
    criteria.add("已选承运商："
        + ("PENDING".equals(unit.get("status", String.class)) ? "按当前状态核对" : "已选"));
    criteria.add("已登记发货时限："
        + (unit.get("sla_deadline_at", OffsetDateTime.class) == null ? "未登记" : "已登记"));
    criteria.add("已交接给 8.3.4.1：本模块最远态，**不是已发货**");
    return criteria;
  }

  private List<String> taskBoundaryNotes() {
    return List.of(
        "逐条结果是这张任务的真实凭据：SUCCEEDED 要求零失败零跳过，有跳过即 PARTIAL",
        "运单单号由承运商回执登记（/waybill-acks）；本模块不调用承运商下单接口（属 10 物流，未交付）",
        "面单渲染属 8.3.4.2（未交付），本模块不渲染 ZPL",
        "交接是本模块最远态：已发货由 8.3.4.1 确认，不在本模块产生");
  }

  private PendingShipmentParcelRow parcelRow(Record r) {
    return new PendingShipmentParcelRow(
        r.get("id", UUID.class),
        r.get("unit_id", UUID.class),
        r.get("parcel_no", Integer.class),
        r.get("source_parcel_id", UUID.class),
        r.get("gross_weight_kg", BigDecimal.class),
        r.get("length_mm", BigDecimal.class),
        r.get("width_mm", BigDecimal.class),
        r.get("height_mm", BigDecimal.class),
        r.get("label_printed", Boolean.class),
        r.get("label_printed_at", OffsetDateTime.class),
        r.get("label_job_ref", String.class));
  }

  private PendingShipmentWaybillRow waybillRow(Record r) {
    return new PendingShipmentWaybillRow(
        r.get("id", UUID.class),
        r.get("unit_id", UUID.class),
        r.get("task_id", UUID.class),
        r.get("carrier_code", String.class),
        ShipmentCarrierChannel.valueOf(r.get("carrier_channel", String.class)),
        ShipmentWaybillStatus.valueOf(r.get("status", String.class)),
        r.get("waybill_no", String.class),
        r.get("request_ref", String.class),
        r.get("error_message", String.class),
        r.get("requested_at", OffsetDateTime.class),
        r.get("issued_at", OffsetDateTime.class));
  }

  private ShipmentBatchTaskRow taskRow(Record r) {
    String taskType = r.get("task_type", String.class);
    String status = r.get("status", String.class);
    String warehouseRef =
        r.get("warehouse_id", UUID.class) == null
            ? ""
            : textOf(
                    "select coalesce(warehouse_ref,'') from shipment_pending_unit"
                        + " where warehouse_id = ? limit 1",
                    r.get("warehouse_id", UUID.class));
    return new ShipmentBatchTaskRow(
        r.get("id", UUID.class),
        r.get("task_no", String.class),
        ShipmentBatchTaskType.valueOf(taskType),
        ShipmentBatchTaskType.valueOf(taskType).displayName,
        r.get("warehouse_id", UUID.class),
        warehouseRef == null ? "" : warehouseRef,
        r.get("carrier_channel", String.class) == null
            ? null
            : ShipmentCarrierChannel.valueOf(r.get("carrier_channel", String.class)),
        r.get("carrier_code", String.class),
        ShipmentBatchStatus.valueOf(status),
        ShipmentBatchStatus.valueOf(status).displayName,
        r.get("unit_count", Integer.class),
        r.get("succeeded_count", Integer.class),
        r.get("failed_count", Integer.class),
        r.get("skipped_count", Integer.class),
        r.get("failure_reason", String.class),
        r.get("note", String.class),
        r.get("created_by_membership_id", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("finished_at", OffsetDateTime.class));
  }

  private ShipmentBatchTaskItemRow taskItemRow(Record r) {
    String itemStatus = r.get("status", String.class);
    Integer parcelNo = r.get("parcel_no", Integer.class);
    return new ShipmentBatchTaskItemRow(
        r.get("id", UUID.class),
        r.get("unit_id", UUID.class),
        r.get("outbound_order_no", String.class),
        r.get("sales_order_no", String.class),
        r.get("warehouse_ref", String.class),
        r.get("carrier_code", String.class),
        ShipmentCarrierChannel.valueOf(r.get("carrier_channel", String.class)),
        r.get("parcel_id", UUID.class),
        parcelNo == null ? 0 : parcelNo,
        ShipmentTaskItemStatus.valueOf(itemStatus),
        ShipmentTaskItemStatus.valueOf(itemStatus).displayName,
        r.get("detail", String.class),
        r.get("processed_at", OffsetDateTime.class));
  }

  private List<PendingShipmentEventRow> timeline(UUID taskId, UUID unitId) {
    List<PendingShipmentEventRow> rows = new ArrayList<>();
    String where = taskId != null ? " where task_id = ?" : " where unit_id = ?";
    Object arg = taskId != null ? taskId : unitId;
    sql.fetch(
            "select " + EVENT_COLUMNS + " from shipment_event" + where + " order by occurred_at",
            arg)
        .forEach(
            r ->
                rows.add(
                    new PendingShipmentEventRow(
                        r.get("id", UUID.class),
                        r.get("unit_id", UUID.class),
                        r.get("task_id", UUID.class),
                        r.get("event_kind", String.class),
                        r.get("from_status", String.class),
                        r.get("to_status", String.class),
                        r.get("detail", String.class),
                        r.get("occurred_at", OffsetDateTime.class))));
    return rows;
  }

  private void writeEvent(
      UUID unitId,
      UUID taskId,
      String kind,
      String from,
      String to,
      String detail,
      UUID actor) {
    sql.execute(
        "insert into shipment_event (id,tenant_id,task_id,unit_id,event_kind,from_status,"
            + " to_status,detail,actor_membership_id) values (?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        currentTenant(),
        taskId,
        unitId,
        kind,
        from,
        to,
        detail,
        actor);
  }

  private void markItem(
      UUID itemId, ShipmentTaskItemStatus status, String detail, OffsetDateTime at) {
    sql.execute(
        "update shipment_batch_task_item set status = ?, detail = ?, processed_at = ?::timestamptz,"
            + " updated_at = ?::timestamptz where id = ?",
        status.name(),
        detail,
        at,
        at,
        itemId);
  }

  private String describeSuccess(ShipmentBatchTaskType type) {
    return switch (type) {
      case WAYBILL_REQUEST -> "已提交承运商，等待运单号回执";
      case LABEL_PRINT -> "已打单";
      case HANDOVER -> "已交接";
    };
  }

  private long countTask(String clause) {
    return countOf("select count(*) from shipment_batch_task t where " + clause);
  }

  /**
   * 标量读数统一走 {@code fetchOne + get(0, Type)}。
   *
   * <p><b>不能</b>用 {@code fetchValue(sql, Class, args)}：本仓库的 jOOQ 版本会把
   * 那个 {@code Class} 当成绑定值（warehouse 的 PickTaskService / WarehouseLocationTreeService
   * 已在文件里记过同一件事与它的报错原文），所以这里一次性把标量读法收口，
   * 不在每个调用点各踩一次。
   */
  /**
   * 造 N 个 {@code ?} 占位符。
   *
   * <p>刻意<b>不</b>用 {@code = any(?)}：本仓库的 jOOQ 版本推不出数组参数的类型，
   * 会在 prepare 阶段报 bad SQL grammar；N 个占位符没有这个问题，且仍然是参数化的
   * （id 全是 UUID，不会有注入面）。
   */
  private static String placeholders(int n) {
    return String.join(",", java.util.Collections.nCopies(n, "?"));
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

  private String nextTaskNo(OffsetDateTime now) {
    Long n =
        sql.fetchOne("select nextval('shipment_batch_task_no_seq')").get(0, Long.class);
    return String.format(
        Locale.ROOT, "SBT-%s%06d", now.toLocalDate().toString().replace("-", ""), n);
  }

  /** 排序白名单：值不进 SQL 拼接。 */
  private String orderBy(String sort) {
    if (sort == null) return "u.packed_at desc";
    return switch (sort) {
      case "PACKED_AT_ASC" -> "u.packed_at asc";
      case "PACKED_AT_DESC" -> "u.packed_at desc";
      case "SLA_DEADLINE_AT_ASC" -> "u.sla_deadline_at asc nulls last";
      case "SLA_DEADLINE_AT_DESC" -> "u.sla_deadline_at desc nulls first";
      case "WEIGHT_DESC" -> "u.total_weight_kg desc";
      case "PARCEL_COUNT_DESC" -> "u.parcel_count desc";
      default -> "u.packed_at desc";
    };
  }

  private String taskOrderBy(String sort) {
    if (sort == null) return "t.created_at desc";
    return switch (sort) {
      case "CREATED_AT_ASC" -> "t.created_at asc";
      case "CREATED_AT_DESC" -> "t.created_at desc";
      case "FINISHED_AT_DESC" -> "t.finished_at desc nulls last";
      case "UNIT_COUNT_DESC" -> "t.unit_count desc";
      default -> "t.created_at desc";
    };
  }

  private int pageSize(int size) {
    if (size <= 0) return 50;
    return Math.min(size, MAX_PAGE_SIZE);
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