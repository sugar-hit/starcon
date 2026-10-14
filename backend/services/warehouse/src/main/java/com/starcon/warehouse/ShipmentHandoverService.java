package com.starcon.warehouse;

import static com.starcon.warehouse.ShipmentHandoverModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
 * 8.3.4.1 发货交接。
 *
 * <p>蓝图 08 卷 8.3.4.1：「<b>发货交接：确认承运商与运单号、登记交接时间与交接人、扣减库存完成出库、
 * 触发物流轨迹与平台回传。</b>输入：包裹与运单号；承运渠道；交接确认。
 * 输出：出库完成分录（预占转实扣）+ 发货事件 → 9 订单状态、10 轨迹、2 平台回写。」
 * 关系段：「上游 = 8.3.3.2；下游 = 7 库存、9 订单、10 物流、2 渠道回写。
 * <b>实发扣减与预占消费在同一事务</b>，避免预占悬空或重复扣减。」
 *
 * <h2>「预占转实扣」怎么保证在同一个事务</h2>
 *
 * <p>{@link #confirm} 是本类唯一带 {@code @Transactional} 的写路径，一次提交里按顺序做：
 *
 * <ol>
 *   <li>锁交接单（{@code for update}）+ 复核它还是 OPEN；</li>
 *   <li>写 <b>库存台账</b>：{@code inventory_transaction(kind='SHIPMENT', ref_type=
 *       'SHIPMENT_HANDOVER', ref_id=交接单)} —— ref_type/ref_id 是 V1 的幂等锚点，
 *       同一张交接单重复投递会撞 UNIQUE 而不是第二次扣库存；</li>
 *   <li>逐行明细扣 <b>实物侧</b>：{@code stock_bucket} 与 {@code inventory_balance} 的
 *       {@code on_hand} 与 {@code reserved} 一起减，并写 {@code inventory_entry}；
 *       <b>reserved 减 min(该行当前 reserved, 本次出库量)</b> —— 不减 reserved 的话
 *       {@code available = on_hand − reserved} 立刻变负，V1 的 ck_balance_available 当场拒，
 *       那正是「预占悬空」在 DB 层的形态；</li>
 *   <li>逐行明细消费 <b>承诺侧</b>：{@code reservation_allocation} COMMITTED → CONSUMED +
 *       {@code reservation_entry} 分录 + 父预占回写 + {@code shipment_handover_reservation}；
 *   </li>
 *   <li>带序列号的行把 {@code inventory_serial} 置 SOLD（清位置），并写 operation/movement；</li>
 *   <li>交接单落 HANDED_OVER（V123 的守卫在此逐行核对「实扣量恰好等于应发量」）；</li>
 *   <li>出库单推 SHIPPED（V72 状态机只认 PENDING_SHIPMENT → SHIPPED 这一条边）。</li>
 * </ol>
 *
 * <p>任何一步抛异常 → 整个事务回滚。所以不会出现「货扣了但预占还挂着」或「预占消费了但货没扣」。
 * 实扣量由 <b>DB 触发器</b>强制写成应发量（V123 的 deduction 守卫），应用层写不出一个更小的数。
 *
 * <h2>两本账（V123 §四）：预占不是一个东西</h2>
 *
 * <ul>
 *   <li>{@code inventory_balance.reserved} / {@code stock_bucket.reserved} 是 7.1.1.1 的<b>实物预占</b>；
 *   <li>{@code reservation_allocation}（V107）是 7.2.1.2 的<b>承诺账</b>，它不碰上面两张表。
 * </ul>
 *
 * <p>所以「预占转实扣」是<b>两个动作</b>，本类同事务里都做。货权划分上两者都在 warehouse
 * 服务同一个库里（V102 第零节已把归属论证清楚），所以这里用同库真外键与显式 SQL，
 * <b>不跨服务读表</b>（AGENTS.md 模块边界纪律）。
 *
 * <h2>状态怎么交出去</h2>
 *
 * <ul>
 *   <li><b>开单</b>：只接受一张 <b>PACKED</b> 的打包单，并把包裹与明细<b>冻结</b>到本单。
 *   <li><b>确认</b>：出库单 PENDING_SHIPMENT → <b>SHIPPED</b>，<b>库存此刻才动</b>。
 *   <li><b>作废</b>：货没交出去，<b>库存一分不动</b>（本来也没扣）。
 * </ul>
 *
 * <h2>哪些量是派生的（页面与下游都不要再算一遍）</h2>
 *
 * <p>包裹数、明细数、应发量、实测总重与总体积、以及扣减口径三个数，
 * 都是 V123 的 <b>触发器</b>写的。服务层只读回来。
 */
@Service
public class ShipmentHandoverService {

  private static final int MAX_PAGE_SIZE = 200;

  private static final List<String> SORTS = List.of("CREATED_AT", "HANDOVER_AT", "HANDOVER_CODE");

  private static final List<String> STATUSES = List.of("OPEN", "HANDED_OVER", "CANCELLED");

  /** 库存台账的来源单据类型（V1 inventory_transaction.ref_type 的幂等锚点的一半）。 */
  private static final String REF_TYPE = "SHIPMENT_HANDOVER";

  private static final String TRANSACTION_BASIS =
      "实发扣减与预占消费在**同一个事务**里完成：写一条 inventory_transaction"
          + "（kind=SHIPMENT，ref_type=SHIPMENT_HANDOVER，ref_id=交接单）作为幂等锚点，"
          + "再逐行写带符号的 inventory_entry。同一张交接单重复投递会撞 UNIQUE(tenant_id,ref_type,ref_id) "
          + "而不是第二次扣库存。中途失败整体回滚，不会出现「货扣了但预占还挂着」或反过来。";

  private static final String INVENTORY_BASIS =
      "reserved 与 on_hand **一起减**：减的是 min(该库存行当前 reserved, 本次出库量)。"
          + "只减 on_hand 不减 reserved 的话，available = on_hand − reserved 立刻为负，"
          + "V1 的 ck_balance_available 与 V2 的 available>=0 当场拒 —— 那正是「预占悬空」在 DB 层的形态。"
          + "拣 6 件只预占了 4 件时就减 4：剩下 2 件本来没被谁圈住，硬减成 6 是在编账。";

  private static final String CARRIER_BASIS =
      "承运渠道在本服务**不是目录表**：V72 的 outbound_order.carrier_code 是既有口径，"
          + "而承运商主数据（渠道代码、面单模板、体积重除数）归 10 物流。"
          + "本表只登记「这一次交接走了哪个渠道」，在建一份目录就会出现两个「有哪些承运商」的答案。";

  private static final String WAYBILL_BASIS =
      "运单号是**本单据的冻结值**（单头主运单号 + 每箱运单号，可各自登记）。"
          + "运单号的权威与轨迹归 10 物流（尚未交付），跨服务读表又被禁止；"
          + "交接凭据必须自带这个号，否则 10 物流上线前这一单永远答不出「运的是什么」。";

  private static final String SERIAL_BASIS =
      "带序列号的明细在交接确认时把 inventory_serial 置 SOLD 并清空当前位置，"
          + "同时写一条 OUTBOUND 的 operation + 逐件 movement（V58 的口径）。"
          + "货已经离开仓库而档案仍写着「在库」，那台机器会在售后环节被凭空多卖一次。";

  /** 重量口径：沿用 8.3.3.2 的克 / NUMERIC(19,3)，禁浮点（AGENTS.md「禁止浮点记账」）。 */
  private static final String WEIGHT_UNIT = "g（克），NUMERIC(19,3)；禁浮点";

  /** 体积口径：沿用 8.3.3.2 的立方米 / NUMERIC(19,6)。 */
  private static final String VOLUME_UNIT = "cbm（立方米），NUMERIC(19,6) = 长×宽×高/10⁶";

  private static final String VOLUME_UNIT_NOTE =
      "体积重（除数 6000/5000/…）不在本模块算：那是承运商渠道口径，归 10 物流。";

  private final DSLContext sql;

  public ShipmentHandoverService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力与边界声明。
   *
   * <p>四段「不由本模块负责 / 尚未接入」的事<b>显式报出来</b>（照 8.3.3.2 的灰块约定）：
   * 空列表会被读成「这件事不存在」，而不是「这段能力还没接入」。
   */
  public ShipmentHandoverCapabilities capabilities() {
    return new ShipmentHandoverCapabilities(
        List.of(
            "确认承运商与运单号（单头主运单号 + 每箱运单号）",
            "登记交接时间与交接人（+ 承运商侧揽收人与联系方式）",
            "扣减库存完成出库（预占转实扣，同一事务）",
            "出库完成分录（inventory_transaction + inventory_entry，可反查）",
            "发货事件（append-only 流水 + 出库单 SHIPPED 时间线）"),
        STATUSES,
        WEIGHT_UNIT,
        VOLUME_UNIT,
        TRANSACTION_BASIS,
        INVENTORY_BASIS,
        CARRIER_BASIS,
        WAYBILL_BASIS,
        SERIAL_BASIS,
        true,
        List.of(
            new HandoverUnavailableLink(
                "物流轨迹与签收", "10", "/logistics",
                "轨迹、签收与异常件归 10 物流（尚未交付）。本模块只产出 append-only 的交接流水"
                    + "与冻结的实测重量体积给它们消费，不建第二套轨迹表。"),
            new HandoverUnavailableLink(
                "平台回写（发货回传）", "2", "/channel",
                "发货回传平台归 2 渠道服务（尚未交付），跨服务调用不在本模块；"
                    + "本模块只把出库单推到 SHIPPED 作为下游可订阅的事实。"),
            new HandoverUnavailableLink(
                "9 订单状态推进", "9", "/trade",
                "订单侧的「已发货」由 9 订单服务自己订阅出库事实后推进；本服务跨服务写对方表"
                    + "被模块边界纪律禁止，所以这里只更新**本服务**的出库单状态。"),
            new HandoverUnavailableLink(
                "运费与体积重试算", "10", "/logistics",
                VOLUME_UNIT_NOTE + " 本模块给的是实测三边派生出的实测体积，10 物流按自己的除数去算。"),
            new HandoverUnavailableLink(
                "面单内容渲染与打印设备", "8.3.4.2",
                "/warehouse/inventory/printing",
                "面单内容与打印次数的权威是 8.3.4.2 已交付的 print_job / label_print_log；"
                    + "本模块只登记运单号，不建第二个打印引擎。")),
        "8.3.3.2",
        List.of("7", "9", "10", "2"));
  }

  // ================================================================ 开单

  /**
   * 开一张交接单：接一张 <b>已 PACKED</b> 的打包单，把包裹与明细<b>冻结</b>到本单。
   *
   * <p>冻结而不是每次去 join {@code parcel}/{@code parcel_item}：交接凭据必须自带
   * 「当时交出去的是哪几箱、每箱多重、装了什么」，将来 8.3.3.2 那张单被改动时不影响本凭据。
   *
   * <p>包裹行与明细行在同一个事务里灌完；V123 的守卫会核对包裹真的属于那张打包单、
   * 真的称过重量量过尺寸。
   */
  @Transactional
  public HandoverRow open(HandoverOpenInput input, UUID actorId) {
    UUID tenant = currentTenant();
    if (input.packingOrderId() == null) throw bad("packingOrderId 不能为空");

    Record packing =
        one(tenant, "select * from packing_order where tenant_id=? and id=?",
            input.packingOrderId());
    if (!"PACKED".equals(packing.get("status", String.class)))
      throw conflict(
          "打包单 " + packing.get("packing_code", String.class) + " 当前是 "
              + packing.get("status", String.class) + "，只有「已打包」（PACKED）才能开交接单 —— "
              + "货还没封箱就交不出去。");

    UUID orderId = packing.get("outbound_order_id", UUID.class);
    Record outbound = one(tenant, "select * from outbound_order where tenant_id=? and id=?", orderId);
    if (!"PENDING_SHIPMENT".equals(outbound.get("status", String.class)))
      throw conflict(
          "出库单 " + outbound.get("order_code", String.class) + " 当前是 "
              + outbound.get("status", String.class) + "，只有「待发货」（PENDING_SHIPMENT）才能开交接单");

    String carrier = trim(input.carrierCode(), 64);
    String waybill = trim(input.masterWaybillNo(), 64);
    if (waybill.length() > 0 && carrier.length() == 0)
      throw bad("填了运单号就必须同时填承运渠道：只有号没有渠道，就不知道是谁运的");

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "insert into shipment_handover(id,tenant_id,handover_code,packing_order_id,outbound_order_id,"
            + "warehouse_id,carrier_code,carrier_name,master_waybill_no,outbound_status_before,"
            + "created_by) values(?,?,?,?,?,?,?,?,?,'PENDING_SHIPMENT',?)",
        id, tenant, handoverCode(), input.packingOrderId(), orderId,
        packing.get("warehouse_id", UUID.class), carrier, trim(input.carrierName(), 100), waybill,
        actorId);
    event(tenant, id, "OPENED", null, "OPEN", null, actorId, now,
        "开交接单：接打包单 " + packing.get("packing_code", String.class) + "（已打包），"
            + "出库单 " + outbound.get("order_code", String.class) + "（待发货）。"
            + "此刻**库存尚未动过**。");

    // 冻结包裹：箱号、实测重量体积一律以 8.3.3.2 的 parcel 读数为准（触发器覆盖调用方写的值）。
    var parcelRows =
        sql.fetch(
            "select p.id,p.parcel_no,p.parcel_code from parcel p"
                + " where p.tenant_id=? and p.packing_order_id=? order by p.parcel_no,p.id",
            tenant, input.packingOrderId());
    if (parcelRows.isEmpty())
      throw conflict("打包单 " + packing.get("packing_code", String.class) + " 没有包裹，不能开交接单");
    for (Record p : parcelRows) {
      sql.execute(
          "insert into shipment_handover_parcel(id,tenant_id,handover_id,parcel_id,parcel_no,"
              + "parcel_code) values(?,?,?,?,?,?)",
          UUID.randomUUID(), tenant, id, p.get("id", UUID.class), p.get("parcel_no", Integer.class),
          p.get("parcel_code", String.class));
      event(tenant, id, "PARCEL_ADDED", null, null, p.get("id", UUID.class), actorId, now,
          "冻结包裹 " + p.get("parcel_code", String.class) + "（第 " + p.get("parcel_no", Integer.class)
              + " 箱）：实测重量体积以 8.3.3.2 的读数为准。");
    }

    // 冻结明细：SKU、件数、序列号一律以 parcel_item 读数为准。
    var items =
        sql.fetch(
            "select pi.id,pi.parcel_id,pi.item_no from parcel_item pi"
                + " join parcel p on p.tenant_id=pi.tenant_id and p.id=pi.parcel_id"
                + " where pi.tenant_id=? and p.packing_order_id=? order by pi.parcel_id,pi.item_no,pi.id",
            tenant, input.packingOrderId());
    if (items.isEmpty())
      throw conflict("打包单 " + packing.get("packing_code", String.class) + " 没有明细行，不能开交接单："
          + "只有箱号没有货，交出去就等于没扣减");
    for (Record it : items) {
      sql.execute(
          "insert into shipment_handover_item(id,tenant_id,handover_id,parcel_id,item_no,parcel_item_id)"
              + " values(?,?,?,?,?,?)",
          UUID.randomUUID(), tenant, id, it.get("parcel_id", UUID.class),
          it.get("item_no", Integer.class), it.get("id", UUID.class));
    }

    return row(headRecord(tenant, id));
  }

  // ================================================================ 列表 / 详情

  /** 交接单列表（真分页 + 真筛选 + 排序白名单 + 汇总，汇总不受筛选影响）。 */
  public HandoverPage page(UUID warehouseId, UUID outboundOrderId, UUID packingOrderId, String status,
      String carrierCode, UUID handoverUserId, String keyword, String sort, int page, int size) {
    UUID tenant = currentTenant();
    var where = new StringBuilder(" where h.tenant_id=?");
    var args = new ArrayList<Object>();
    args.add(tenant);
    if (warehouseId != null) {
      where.append(" and h.warehouse_id=?");
      args.add(warehouseId);
    }
    if (outboundOrderId != null) {
      where.append(" and h.outbound_order_id=?");
      args.add(outboundOrderId);
    }
    if (packingOrderId != null) {
      where.append(" and h.packing_order_id=?");
      args.add(packingOrderId);
    }
    if (text(status).length() > 0) {
      where.append(" and h.status=?");
      args.add(status);
    }
    if (text(carrierCode).length() > 0) {
      where.append(" and h.carrier_code=?");
      args.add(carrierCode);
    }
    if (handoverUserId != null) {
      where.append(" and h.handover_user_id=?");
      args.add(handoverUserId);
    }
    if (text(keyword).length() > 0) {
      where.append(" and (h.handover_code ilike ? or h.master_waybill_no ilike ?"
          + " or h.carrier_name ilike ?)");
      args.add("%" + keyword.trim() + "%");
      args.add("%" + keyword.trim() + "%");
      args.add("%" + keyword.trim() + "%");
    }
    long total = count("select count(*) from shipment_handover h" + where, args);

    var rows =
        sql.fetch(
            "select h.*,p.packing_code,o.order_code,o.status as outbound_status,w.code as warehouse_code"
                + " from shipment_handover h"
                + " join packing_order p on p.tenant_id=h.tenant_id and p.id=h.packing_order_id"
                + " join outbound_order o on o.tenant_id=h.tenant_id and o.id=h.outbound_order_id"
                + " join warehouse w on w.tenant_id=h.tenant_id and w.id=h.warehouse_id"
                + where
                + orderClause(sort)
                + " limit ? offset ?",
            all(args, pageSize(size), offset(page, size)));
    return new HandoverPage(rows.stream().map(this::row).toList(), page, pageSize(size), total,
        summary(warehouseId));
  }

  /**
   * 单头读数（带打包单号、出库单号与出库单当前状态、仓库编码）。
   *
   * <p>刻意<b>只有这一处</b>：{@link #row} 要读那四个来自 join 的字段，
   * 而裸 {@code select *} 上它们不存在，jOOQ 的 {@code get(field, type)} 会直接抛
   * IllegalArgumentException —— 「列表页好好的、详情页 500」就是这么来的。
   */
  private Record headRecord(UUID tenant, UUID id) {
    return one(
        tenant,
        "select h.*,p.packing_code,o.order_code,o.status as outbound_status,"
            + "w.code as warehouse_code from shipment_handover h"
            + " join packing_order p on p.tenant_id=h.tenant_id and p.id=h.packing_order_id"
            + " join outbound_order o on o.tenant_id=h.tenant_id and o.id=h.outbound_order_id"
            + " join warehouse w on w.tenant_id=h.tenant_id and w.id=h.warehouse_id"
            + " where h.tenant_id=? and h.id=?",
        id);
  }

  /** 列表汇总。刻意<b>不受筛选影响</b>（与 8.3.1.1 / 8.3.3.2 同一纪律）：汇总回答的是
   * 「这个仓今天交了多少」，按状态筛完再看汇总会得到一个没人需要的数。 */
  public HandoverSummary summary(UUID warehouseId) {
    UUID tenant = currentTenant();
    var where = new StringBuilder(" where tenant_id=?");
    var args = new ArrayList<Object>();
    args.add(tenant);
    if (warehouseId != null) {
      where.append(" and warehouse_id=?");
      args.add(warehouseId);
    }
    Record r =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where status='OPEN') as open_count,"
                + " count(*) filter (where status='HANDED_OVER') as handed_count,"
                + " count(*) filter (where status='CANCELLED') as cancelled_count,"
                + " coalesce(sum(parcel_count),0) as parcels,"
                + " coalesce(sum(parcel_item_count),0) as items,"
                + " coalesce(sum(total_quantity),0) as total_qty,"
                + " coalesce(sum(deducted_quantity),0) as deducted_qty,"
                + " coalesce(sum(reserved_deducted_quantity),0) as reserved_qty,"
                + " coalesce(sum(total_gross_weight_g),0) as gross,"
                + " coalesce(sum(total_volume_cbm),0) as cbm,"
                + " coalesce(sum(reservation_line_count),0) as reservation_lines"
                + " from shipment_handover" + where,
            args.toArray());
    return new HandoverSummary(
        n(r, "total"), n(r, "open_count"), n(r, "handed_count"), n(r, "cancelled_count"),
        n(r, "parcels"), n(r, "items"), decimalText(r, "total_qty"),
        decimalText(r, "deducted_qty"), decimalText(r, "reserved_qty"), decimalText(r, "gross"),
        decimalText(r, "cbm"), n(r, "reservation_lines"));
  }

  /** 详情：单头 + 包裹 + 明细 + 预占消费 + 时间线 + 判据 + 边界声明。 */
  public HandoverDetail detail(String handoverId) {
    UUID tenant = currentTenant();
    UUID id = uuid(handoverId);
    Record head = headRecord(tenant, id);
    return new HandoverDetail(
        row(head), parcels(tenant, id, nz(head.get("master_waybill_no", String.class))), items(tenant, id),
        reservations(tenant, id), timeline(tenant, id), reservationCandidates(tenant, id),
        capabilities().unavailable(), completionOf(id.toString()));
  }

  // ================================================================ 登记承运商与运单号

  /**
   * 登记承运商与运单号（蓝图输入「包裹与运单号；承运渠道」）。
   *
   * <p>承运渠道与主运单号写在单头，逐箱运单号写在包裹行；留空的箱跟随主运单号
   * （整车发运的常态）。<b>不碰库存</b> —— 货还没交。
   */
  @Transactional
  public HandoverRow registerWaybills(String handoverId, HandoverWaybillInput input, UUID actorId) {
    UUID tenant = currentTenant();
    UUID id = uuid(handoverId);
    Record head = openRecord(tenant, id);

    String carrier = trim(input.carrierCode(), 64);
    if (carrier.isEmpty())
      throw bad("carrierCode 不能为空：确认承运商正是本模块的职责");
    String waybill = trim(input.masterWaybillNo(), 64);
    if (waybill.isEmpty())
      throw bad("masterWaybillNo 不能为空：只有承运商没有运单号，就不知道运的是哪一单");

    sql.execute(
        "update shipment_handover set carrier_code=?,carrier_name=coalesce(nullif(?,''),carrier_name),"
            + "master_waybill_no=?,receiver_name=coalesce(nullif(?,''),receiver_name),"
            + "receiver_contact=coalesce(nullif(?,''),receiver_contact),updated_at=now()"
            + " where tenant_id=? and id=?",
        carrier, trim(input.carrierName(), 100), waybill, trim(input.receiverName(), 100),
        trim(input.receiverContact(), 64), tenant, id);

    if (input.parcelWaybillNos() != null) {
      for (ParcelWaybillEntry entry : input.parcelWaybillNos()) {
        if (entry == null || entry.parcelId() == null) continue;
        UUID parcelId = entry.parcelId();
        Record parcel =
            sql.fetchOne(
                "select id from shipment_handover_parcel where tenant_id=? and handover_id=? and parcel_id=?",
                tenant, id, parcelId);
        if (parcel == null)
          throw notFound("包裹 " + parcelId + " 不在本次交接里");
        sql.execute(
            "update shipment_handover_parcel set parcel_waybill_no=? where tenant_id=? and handover_id=?"
                + " and parcel_id=?",
            trim(entry.parcelWaybillNo(), 64), tenant, id, parcelId);
      }
    }

    event(tenant, id, "WAYBILL_REGISTERED", "OPEN", "OPEN", null, actorId, OffsetDateTime.now(),
        "登记承运商 " + carrier + "，主运单号 " + waybill
            + (input.parcelWaybillNos() == null || input.parcelWaybillNos().isEmpty()
                ? "（各箱跟随主运单号）"
                : "，逐箱运单号 " + input.parcelWaybillNos().size() + " 条")
            + "。库存仍未动 —— 扣减发生在交接确认那一个事务里。");

    return row(headRecord(tenant, id));
  }

  // ================================================================ 交接确认（预占转实扣）

  /**
   * 交接确认 —— <b>实发扣减与预占消费在这一个事务里完成</b>。
   *
   * <p>顺序见类注释。关键的不变量：
   *
   * <ul>
   *   <li>实扣量由 V123 的触发器强制写成应发量，服务层写不出一个更小的数；
   *   <li>{@code reserved} 减 {@code min(该行当前 reserved, 本次出库量)}；
   *   <li>预占按「先本库位、再本批次、最后按圈定先后」的顺序消费，消费掉的量逐条落
   *       {@code shipment_handover_reservation}，所以「哪几条预占被这次交接吃掉了」可查；
   *   <li>库存不足 → <b>整单失败</b>（抛异常回滚），不静默少扣。
   * </ul>
   */
  @Transactional
  public HandoverConfirmed confirm(String handoverId, HandoverConfirmInput input, UUID actorId) {
    UUID tenant = currentTenant();
    UUID id = uuid(handoverId);
    Record head = openRecord(tenant, id);

    String carrier = nz(head.get("carrier_code", String.class));
    String waybill = nz(head.get("master_waybill_no", String.class));
    if (carrier.isBlank() || waybill.isBlank())
      throw conflict("承运渠道与运单号还没登记：确认承运商与运单号是交接的前提");

    UUID handoverUser =
        input != null && input.handoverUserId() != null
            ? input.handoverUserId()
            : head.get("handover_user_id", UUID.class);
    if (handoverUser == null) throw bad("缺交接人：登记交接时间与交接人是本模块的职责");
    OffsetDateTime at =
        input == null ? OffsetDateTime.now() : instant(input.handoverAt(), "交接时刻");

    UUID warehouseId = head.get("warehouse_id", UUID.class);
    List<Record> itemRows = itemRecords(tenant, id);
    if (itemRows.isEmpty())
      throw conflict("交接单没有明细行，不能交接：只有箱号没有货，等于没扣减");

    // ---- 1. 出库台账（幂等锚点）。重复投递同一张交接单会撞 UNIQUE，而不是第二次扣库存。
    UUID txId = UUID.randomUUID();
    sql.execute(
        "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,notes)"
            + " values(?,?,?,?,?,?::timestamptz,?,?)",
        txId, tenant, "SHIPMENT", REF_TYPE, id, at, actorId,
        "发货交接 " + head.get("handover_code", String.class) + "：承运 " + carrier + "，运单号 " + waybill);

    // ---- 2. 逐行扣减 + 预占消费 + 序列号出库（同一事务）
    var ledger = new ArrayList<HandoverLedgerEntry>();
    var reservedTotal = BigDecimal.ZERO;
    int serialCount = 0;

    for (Record item : itemRows) {
      UUID itemId = item.get("id", UUID.class);
      UUID skuId = item.get("sku_id", UUID.class);
      BigDecimal want = nzDecimal(item.get("quantity", BigDecimal.class));

      // 2a. 实物侧：从打包区优先、否则任意库位，逐行吃掉需要的量。
      var buckets =
          sql.fetch(
              "select k.id,k.balance_id,k.location_id,k.lot_id,k.quality_status_id,k.on_hand,k.reserved,"
                  + "l.code as location_code,l.type as location_type"
                  + " from stock_bucket k"
                  + " left join warehouse_location l on l.tenant_id=k.tenant_id and l.id=k.location_id"
                  + " where k.tenant_id=? and k.warehouse_id=? and k.sku_id=? and k.on_hand>0"
                  + " order by (case when l.type='SHIPPING' then 0 else 1 end),k.id",
              tenant, warehouseId, skuId);
      BigDecimal left = want;
      BigDecimal itemReserved = BigDecimal.ZERO;
      Record landedOn = null;
      BigDecimal landedReserved = BigDecimal.ZERO;

      for (Record bucket : buckets) {
        if (left.signum() <= 0) break;
        BigDecimal onHand = nzDecimal(bucket.get("on_hand", BigDecimal.class));
        if (onHand.signum() <= 0) continue;
        BigDecimal take = onHand.min(left);
        BigDecimal reservedToMove = nzDecimal(bucket.get("reserved", BigDecimal.class)).min(take);

        sql.execute(
            "update stock_bucket set on_hand=on_hand-cast(? as numeric),"
                + "reserved=reserved-cast(? as numeric),"
                + "last_movement_at=greatest(?::timestamptz,last_movement_at"
                + " + interval '1 microsecond') where id=?",
            plain(take), plain(reservedToMove), at, bucket.get("id", UUID.class));
        UUID balanceId = bucket.get("balance_id", UUID.class);
        sql.execute(
            "update inventory_balance set on_hand=on_hand-cast(? as numeric),"
                + "reserved=reserved-cast(? as numeric),version=version+1,"
                + "updated_at=now() where id=?",
            plain(take), plain(reservedToMove), balanceId);

        UUID entryId = UUID.randomUUID();
        sql.execute(
            "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
                + "reserved_delta) values(?,?,?,?,cast(? as numeric),cast(? as numeric))",
            entryId, tenant, txId, balanceId, plain(take.negate()), plain(reservedToMove.negate()));

        ledger.add(
            new HandoverLedgerEntry(entryId, balanceId, bucket.get("id", UUID.class), skuId,
                item.get("sku_code", String.class), plain(take.negate()),
                plain(reservedToMove.negate()), bucket.get("location_code", String.class)));

        itemReserved = itemReserved.add(reservedToMove);
        left = left.subtract(take);
        if (landedOn == null) {
          landedOn = bucket;
          landedReserved = reservedToMove;
        }
      }

      if (left.signum() > 0)
        throw conflict(
            "SKU " + item.get("sku_code", String.class) + " 在本仓可用库存不足：应交 " + plain(want)
                + "，只找到 " + plain(want.subtract(left))
                + "。缺货要如实报出来，不能把数量凑上去 —— 本次交接整体不成立（同一事务已回滚）。");
      if (landedOn == null)
        throw conflict("SKU " + item.get("sku_code", String.class) + " 在本仓没有任何库存行，不能交接");

      // 2b. 承诺侧：消费预占分配（同库，7.2.1.2 的 reservation_allocation）。
      consumeReservations(tenant, id, itemId, skuId, warehouseId, landedOn, want, at, actorId);

      // 2c. 明细行落点。触发器强制 deducted_quantity := quantity。
      sql.execute(
          "update shipment_handover_item set deducted_quantity=cast(? as numeric),"
              + "reserved_deducted_quantity=cast(? as numeric),"
              + "balance_id=?,bucket_id=?,location_id=?,lot_id=?,quality_status_id=?"
              + " where tenant_id=? and id=?",
          plain(want), plain(itemReserved), landedOn.get("balance_id", UUID.class),
          landedOn.get("id", UUID.class), landedOn.get("location_id", UUID.class),
          landedOn.get("lot_id", UUID.class), landedOn.get("quality_status_id", UUID.class),
          tenant, itemId);

      reservedTotal = reservedTotal.add(itemReserved);
      if (item.get("serial_id", UUID.class) != null) serialCount += shipSerial(
          tenant, id, warehouseId, item, landedOn, at, actorId, carrier, waybill);
    }

    // ---- 3. 交接单落 HANDED_OVER（V123 守卫在此逐行核对实扣量）。
    sql.execute(
        "update shipment_handover set status='HANDED_OVER',handover_at=?::timestamptz,"
            + "handover_user_id=?,receiver_name=coalesce(nullif(?,''),receiver_name),"
            + "receiver_contact=coalesce(nullif(?,''),receiver_contact),"
            + "inventory_transaction_id=?,completed_at=?::timestamptz,updated_at=now()"
            + " where tenant_id=? and id=?",
        at, handoverUser, input == null ? "" : trim(input.receiverName(), 100),
        input == null ? "" : trim(input.receiverContact(), 64), txId, at, tenant, id);

    // ---- 4. 出库单推 SHIPPED（V72 状态机只认 PENDING_SHIPMENT → SHIPPED 这一条边）。
    UUID orderId = head.get("outbound_order_id", UUID.class);
    Record outbound = one(tenant, "select * from outbound_order where tenant_id=? and id=?", orderId);
    String before = outbound.get("status", String.class);
    sql.execute(
        "update outbound_order set status='SHIPPED',shipped_at=?::timestamptz,"
            + "carrier_code=coalesce(nullif(?,''),carrier_code),updated_at=now()"
            + " where tenant_id=? and id=?",
        at, carrier, tenant, orderId);
    sql.execute(
        "insert into outbound_order_event(id,tenant_id,order_id,event_type,from_status,to_status,"
            + "actor_user_id,occurred_at,detail) values(?,?,?,'STATUS_CHANGED',?,?,?,?::timestamptz,?)",
        UUID.randomUUID(), tenant, orderId, before, "SHIPPED", handoverUser, at,
        "发货交接 " + head.get("handover_code", String.class) + "：承运 " + carrier + "，运单号 "
            + waybill + "，出库分录 " + txId + "。");

    event(tenant, id, "HANDED_OVER", "OPEN", "HANDED_OVER", null, handoverUser, at,
        "交接确认：出库 " + plain(totalQty(tenant, id)) + " 件（其中预占转实扣 "
            + plain(reservedTotal) + "），出库流水 " + txId + "，出库单 " + orderId + " → SHIPPED。"
            + "物流轨迹与平台回传由 10 物流与 2 渠道消费本单流水。");

    return new HandoverConfirmed(
        row(headRecord(tenant, id)), txId, ledger,
        intOf(tenant,
            "select count(*) from shipment_handover_reservation where tenant_id=? and handover_id=?",
            id),
        plain(reservedTotal), serialCount);
  }

  /**
   * 消费预占分配（7.2.1.2 的承诺账）。
   *
   * <p>排序「先本库位、再本批次、最后按圈定先后」：同一批货如果圈在两个货位上，
   * 先吃掉「货真正在的那个库位」上的那条，预占账与实物账才对得上。
   * 消费不了的量（没有 COMMITTED 分配）<b>如实不记</b> —— 那是「未预占出库」，
   * 不是「预占已消耗」。
   */
  private void consumeReservations(UUID tenant, UUID handoverId, UUID itemId, UUID skuId,
      UUID warehouseId, Record bucket, BigDecimal want, OffsetDateTime at, UUID actorId) {
    var allocations =
        sql.fetch(
            "select a.id,a.reservation_id,r.reservation_no,a.location_id,a.lot_id,a.remaining"
                + " from reservation_allocation a"
                + " join inventory_reservation r on r.tenant_id=a.tenant_id and r.id=a.reservation_id"
                + " where a.tenant_id=? and a.warehouse_id=? and r.sku_id=?"
                + " and a.status='COMMITTED' and a.remaining>0 and r.status in ('HELD','COMMITTED')"
                + " order by (case when a.location_id is not distinct from ? then 0 else 1 end),"
                + " (case when a.lot_id is not distinct from ? then 0 else 1 end),"
                + " a.allocated_at,a.id",
            tenant, warehouseId, skuId, bucket.get("location_id", UUID.class),
            bucket.get("lot_id", UUID.class));

    BigDecimal left = want;
    for (Record a : allocations) {
      if (left.signum() <= 0) break;
      BigDecimal remaining = nzDecimal(a.get("remaining", BigDecimal.class));
      if (remaining.signum() <= 0) continue;
      BigDecimal take = remaining.min(left);
      UUID allocationId = a.get("id", UUID.class);
      UUID reservationId = a.get("reservation_id", UUID.class);

      sql.execute(
          "update reservation_allocation set status='CONSUMED',"
              + "consumed_quantity=consumed_quantity+?::numeric,settled_at=?::timestamptz,"
              + "version=version+1,updated_at=now()"
              + " where tenant_id=? and id=? and status='COMMITTED'",
          plain(take), at, tenant, allocationId);
      sql.execute(
          "insert into reservation_entry(id,tenant_id,reservation_id,allocation_id,transition,"
              + "from_status,to_status,quantity,occurred_at,actor_id,reason)"
              + " values(?,?,?,?,'CONSUMED','COMMITTED','CONSUMED',?::numeric,?::timestamptz,?,?)",
          UUID.randomUUID(), tenant, reservationId, allocationId, plain(take), at, actorId,
          "发货交接出库消耗");
      sql.execute(
          "insert into shipment_handover_reservation(id,tenant_id,handover_id,handover_item_id,"
              + "reservation_id,allocation_id,reservation_no,location_id,lot_id,quantity,occurred_at)"
              + " values(?,?,?,?,?,?,?,?,?,?::numeric,?::timestamptz)",
          UUID.randomUUID(), tenant, handoverId, itemId, reservationId, allocationId,
          nz(a.get("reservation_no", String.class)), a.get("location_id", UUID.class),
          a.get("lot_id", UUID.class), plain(take), at);

      settleReservation(tenant, reservationId, at);
      left = left.subtract(take);
    }
  }

  /**
   * 预占父行回写（与 7.2.1.2 同一口径：consumed_quantity 按分配之和<b>重算</b>而不是累加）。
   *
   * <p>重算而非累加是幂等的原因：累加在重试或并发时会翻倍。全部分配消耗完且覆盖需求量
   * 才把预占推进到 CONSUMED —— 部分消耗时只对齐已消费量，不动状态。
   */
  private void settleReservation(UUID tenant, UUID reservationId, OffsetDateTime at) {
    Record r =
        sql.fetchOne(
            "select count(*) as total_count,count(*) filter (where status<>'CONSUMED') as not_consumed,"
                + " coalesce(sum(consumed_quantity),0) as consumed_total,"
                + " (select requested_quantity from inventory_reservation"
                + "   where tenant_id=? and id=?) as requested"
                + " from reservation_allocation where tenant_id=? and reservation_id=?",
            tenant, reservationId, tenant, reservationId);
    BigDecimal consumed = nzDecimal(r.get("consumed_total", BigDecimal.class));
    BigDecimal requested = nzDecimal(r.get("requested", BigDecimal.class));
    boolean fully =
        n(r, "total_count") > 0
            && n(r, "not_consumed") == 0
            && consumed.compareTo(requested) >= 0;

    if (fully)
      sql.execute(
          "update inventory_reservation set consumed_quantity=?::numeric,status='CONSUMED',"
              + "settled_at=?::timestamptz,committed_at=coalesce(committed_at,?::timestamptz),"
              + "version=version+1,updated_at=now()"
              + " where tenant_id=? and id=? and status<>'CONSUMED'",
          plain(consumed), at, at, tenant, reservationId);
    else
      sql.execute(
          "update inventory_reservation set consumed_quantity=?::numeric,version=version+1,"
              + "updated_at=now() where tenant_id=? and id=? and status in ('HELD','COMMITTED')",
          plain(consumed), tenant, reservationId);
  }

  /**
   * 带序列号的件出库：SOLD + 清空当前位置 + 一条 OUTBOUND operation 与逐件 movement。
   *
   * <p>货已经离开仓库而档案仍写着「在库」，那台机器会在售后环节被凭空多卖一次。
   */
  private int shipSerial(UUID tenant, UUID handoverId, UUID warehouseId, Record item, Record bucket,
      OffsetDateTime at, UUID actorId, String carrier, String waybill) {
    UUID serialId = item.get("serial_id", UUID.class);
    Record serial =
        sql.fetchOne(
            "select id,serial_number,status,current_location_id,lot_id from inventory_serial"
                + " where tenant_id=? and id=?",
            tenant, serialId);
    if (serial == null) throw notFound("序列号档案不存在");
    if ("SOLD".equals(serial.get("status", String.class)))
      throw conflict("序列号 " + serial.get("serial_number", String.class) + " 已是已售状态，不能重复出库");
    if (!"IN_STOCK".equals(serial.get("status", String.class)))
      throw conflict(
          "序列号 " + serial.get("serial_number", String.class) + " 当前是 "
              + serial.get("status", String.class) + "，只有「在库」（IN_STOCK）能随货出库");
    if (!java.util.Objects.equals(serial.get("current_location_id", UUID.class),
        bucket.get("location_id", UUID.class)))
      throw conflict(
          "序列号 " + serial.get("serial_number", String.class) + " 当前在库位 "
              + serial.get("current_location_id", UUID.class) + "，而这批货在 "
              + bucket.get("location_id", UUID.class)
              + "：序列号所在库位与出货库位不一致，不能凭空出库");

    UUID operationId = UUID.randomUUID();
    sql.execute(
        "insert into inventory_serial_operation(id,tenant_id,direction,sku_id,lot_id,warehouse_id,"
            + "location_id,ref_type,ref_id,declared_quantity,serial_count,actor_id,note,occurred_at)"
            + " values(?,?,'OUTBOUND',?,?,?,?,?,?,?::numeric,1,?,?,?::timestamptz)",
        operationId, tenant, item.get("sku_id", UUID.class), serial.get("lot_id", UUID.class),
        warehouseId, bucket.get("location_id", UUID.class), REF_TYPE, handoverId,
        BigDecimal.ONE.toPlainString(), actorId,
        "发货交接出库：承运 " + carrier + "，运单号 " + waybill, at);
    sql.execute(
        "insert into inventory_serial_movement(id,tenant_id,serial_id,operation_id,movement_kind,"
            + "serial_number,from_status,to_status,from_location_id,to_location_id,from_lot_id,"
            + "ref_type,ref_id,actor_id,note,occurred_at)"
            + " values(?,?,?,?,'OUTBOUND',?,'IN_STOCK','SOLD',?,null,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(), tenant, serialId, operationId, serial.get("serial_number", String.class),
        serial.get("current_location_id", UUID.class), serial.get("lot_id", UUID.class), REF_TYPE,
        handoverId, actorId, "发货交接出库", at);
    sql.execute(
        "update inventory_serial set status='SOLD',current_location_id=null,"
            + "last_movement_at=greatest(?::timestamptz,last_movement_at),updated_at=now()"
            + " where tenant_id=? and id=?",
        at, tenant, serialId);
    return 1;
  }

  // ================================================================ 作废

  /** 作废交接单：货没交出去，<b>库存一分不动</b>（本来也没扣）。原因必填。 */
  @Transactional
  public HandoverRow cancel(String handoverId, HandoverCancelInput input, UUID actorId) {
    UUID tenant = currentTenant();
    UUID id = uuid(handoverId);
    openRecord(tenant, id);
    String reason = trim(input == null ? null : input.reason(), 300);
    if (reason.isEmpty()) throw bad("作废交接单必须写原因：货没交出去也要留痕");

    sql.execute(
        "update shipment_handover set status='CANCELLED',cancelled_at=?::timestamptz,cancel_reason=?,"
            + "updated_at=now() where tenant_id=? and id=?",
        OffsetDateTime.now(), reason, tenant, id);
    event(tenant, id, "CANCELLED", "OPEN", "CANCELLED", null, actorId, OffsetDateTime.now(),
        "作废交接单：" + reason + "。库存未动 —— 扣减只发生在交接确认那一个事务里。");
    return row(headRecord(tenant, id));
  }

  // ================================================================ 判据

  /**
   * 能不能交接确认 —— <b>页面的唯一判据来源</b>。
   *
   * <p>其中「库存够不够」在这里现查而不是让前端算：前端手上只有本单的数量，
   * 没有全仓可用量，在那边算必然与库存账差一位，而差的那一位就是超卖。
   */
  public HandoverCompletionReport completionOf(String handoverId) {
    UUID tenant = currentTenant();
    UUID id = uuid(handoverId);
    Record head = headRecord(tenant, id);

    List<String> blocking = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    boolean carrierOn = !nz(head.get("carrier_code", String.class)).isBlank();
    boolean waybillOn = !nz(head.get("master_waybill_no", String.class)).isBlank();
    boolean userSet = head.get("handover_user_id", UUID.class) != null;
    boolean atSet = head.get("handover_at", OffsetDateTime.class) != null;
    if (!carrierOn) blocking.add("尚未登记承运渠道");
    if (!waybillOn) blocking.add("尚未登记运单号");
    // 交接人与交接时刻是**确认那一步**的输入（confirm 入参），不是「开单时就得有」的前置，
    // 所以它们只作提示不作阻断 —— 否则登记完运单号后判据恒为 false，确认按钮永远点不动。
    if (!userSet)
      warnings.add("尚未登记交接人：确认交接时必须给（交接人与交接时刻是一对事实）");
    if (!atSet) warnings.add("交接时刻将在确认时取当前时刻（也可以传 PDA 时钟）");

    List<Record> parcels = sql.fetch(
        "select id from shipment_handover_parcel where tenant_id=? and handover_id=?", tenant, id);
    List<Record> items = itemRecords(tenant, id);
    if (parcels.isEmpty()) blocking.add("一个包裹都没有：交出去的是「哪几箱」答不出等于没交接");
    if (items.isEmpty()) blocking.add("没有明细行：只有箱号没有货，等于没扣减");

    BigDecimal shortQty = BigDecimal.ZERO;
    var shortSkus = new ArrayList<String>();
    UUID warehouseId = head.get("warehouse_id", UUID.class);
    for (Record item : items) {
      BigDecimal want = nzDecimal(item.get("quantity", BigDecimal.class));
      Record available = sql.fetchOne(
          "select coalesce(sum(on_hand),0) as q from stock_bucket"
              + " where tenant_id=? and warehouse_id=? and sku_id=?",
          tenant, warehouseId, item.get("sku_id", UUID.class));
      BigDecimal onHand = nzDecimal(available.get("q", BigDecimal.class));
      if (onHand.compareTo(want) < 0) {
        shortQty = shortQty.add(want.subtract(onHand));
        shortSkus.add(item.get("sku_code", String.class) + " 缺 " + plain(want.subtract(onHand)));
      }
    }
    if (shortSkus.isEmpty()) {
      var noReservation = new ArrayList<String>();
      for (Record item : items) {
        boolean has = !sql.fetch(
                "select 1 from reservation_allocation a join inventory_reservation r"
                    + " on r.tenant_id=a.tenant_id and r.id=a.reservation_id"
                    + " where a.tenant_id=? and a.warehouse_id=? and r.sku_id=?"
                    + " and a.status='COMMITTED' and a.remaining>0 limit 1",
                tenant, warehouseId, item.get("sku_id", UUID.class))
            .isEmpty();
        if (!has) noReservation.add(item.get("sku_code", String.class));
      }
      if (!noReservation.isEmpty())
        warnings.add(
            "以下 SKU 没有任何在册预占，本次是「未预占出库」，不会有预占被消费："
                + String.join("、", noReservation));
    } else {
      blocking.add("库存不足，差 " + plain(shortQty) + " 件：" + String.join("；", shortSkus));
    }

    boolean handable = blocking.isEmpty() && !"HANDED_OVER".equals(head.get("status", String.class))
        && !"CANCELLED".equals(head.get("status", String.class));
    if ("HANDED_OVER".equals(head.get("status", String.class)))
      blocking.add("这张交接单已经交接过了，实发扣减与预占消费已完成，不能重复确认");
    if ("CANCELLED".equals(head.get("status", String.class)))
      blocking.add("这张交接单已作废，请重新开一张");

    return new HandoverCompletionReport(handable, blocking, warnings, carrierOn, waybillOn, userSet,
        atSet, parcels.size(), items.size(), plain(totalQty(tenant, id)), plain(shortQty),
        shortSkus);
  }

  // ================================================================ 子表读取

  private List<HandoverParcelRow> parcels(UUID tenant, UUID handoverId, String masterWaybillNo) {
    // 「这一箱有没有被扣过」不落列：用明细行的实扣量现算，页面上不用信第二个真相来源。
    var deductedByParcel = new java.util.HashMap<UUID, Boolean>();
    for (Record it : itemRecords(tenant, handoverId)) {
      UUID parcelId = it.get("parcel_id", UUID.class);
      boolean wasDeducted = it.get("deducted_quantity", BigDecimal.class).signum() > 0;
      deductedByParcel.merge(parcelId, wasDeducted, (a, b) -> a || b);
    }
    return sql.fetch(
            "select * from shipment_handover_parcel where tenant_id=? and handover_id=?"
                + " order by parcel_no,id",
            tenant, handoverId)
        .stream()
        .map(
            r -> {
              String own = nz(r.get("parcel_waybill_no", String.class));
              UUID parcelId = r.get("parcel_id", UUID.class);
              return new HandoverParcelRow(
                  r.get("id", UUID.class), parcelId, r.get("parcel_no", Integer.class),
                  r.get("parcel_code", String.class), own,
                  own.isBlank() ? nz(masterWaybillNo) : own,
                  decimalText(r, "gross_weight_g"), decimalText(r, "net_content_weight_g"),
                  decimalText(r, "volume_cbm"), r.get("item_count", Integer.class),
                  decimalText(r, "total_quantity"),
                  Boolean.TRUE.equals(deductedByParcel.get(parcelId)));
            })
        .toList();
  }

  private List<Record> itemRecords(UUID tenant, UUID handoverId) {
    return sql.fetch(
        "select * from shipment_handover_item where tenant_id=? and handover_id=?"
            + " order by parcel_id,item_no,id",
        tenant, handoverId);
  }

  private List<HandoverItemRow> items(UUID tenant, UUID handoverId) {
    var locations = new HashMapLoc();
    return itemRecords(tenant, handoverId).stream()
        .map(
            r -> new HandoverItemRow(
                r.get("id", UUID.class), r.get("parcel_id", UUID.class),
                r.get("item_no", Integer.class), r.get("parcel_item_id", UUID.class),
                r.get("sku_id", UUID.class), r.get("sku_code", String.class),
                r.get("sku_name", String.class), decimalText(r, "quantity"),
                r.get("serial_id", UUID.class), r.get("serial_number", String.class),
                decimalText(r, "deducted_quantity"), decimalText(r, "reserved_deducted_quantity"),
                r.get("balance_id", UUID.class), r.get("bucket_id", UUID.class),
                r.get("location_id", UUID.class), locations.code(tenant, r.get("location_id", UUID.class)),
                r.get("lot_id", UUID.class), r.get("quality_status_id", UUID.class),
                r.get("deducted_quantity", BigDecimal.class).signum() > 0))
        .toList();
  }

  private List<HandoverReservationRow> reservations(UUID tenant, UUID handoverId) {
    var locations = new HashMapLoc();
    return sql.fetch(
            "select * from shipment_handover_reservation where tenant_id=? and handover_id=?"
                + " order by occurred_at,id",
            tenant, handoverId)
        .stream()
        .map(
            r -> new HandoverReservationRow(
                r.get("id", UUID.class), r.get("handover_item_id", UUID.class),
                r.get("reservation_id", UUID.class), r.get("reservation_no", String.class),
                r.get("allocation_id", UUID.class), r.get("location_id", UUID.class),
                locations.code(tenant, r.get("location_id", UUID.class)), r.get("lot_id", UUID.class),
                decimalText(r, "quantity"), r.get("occurred_at", OffsetDateTime.class)))
        .toList();
  }

  /**
   * 可消费的预占候选。
   *
   * <p>刻意<b>列出来</b>：交接时要回答「这批货原本被谁预占着」。空列表有明确含义 ——
   * 这个 SKU 没有在册预占，界面应显示「未预占出库」而不是「预占已全部消耗」。
   */
  private List<HandoverReservationOption> reservationCandidates(UUID tenant, UUID handoverId) {
    Record head =
        sql.fetchOne("select warehouse_id from shipment_handover where tenant_id=? and id=?",
            tenant, handoverId);
    if (head == null) return List.of();
    return sql.fetch(
            "select a.id as allocation_id,a.reservation_id,r.reservation_no,r.sku_id,s.code as sku_code,"
                + "a.location_id,l.code as location_code,a.lot_id,a.allocated_quantity,"
                + "a.consumed_quantity,a.remaining"
                + " from reservation_allocation a"
                + " join inventory_reservation r on r.tenant_id=a.tenant_id and r.id=a.reservation_id"
                + " join sku_ref s on s.tenant_id=r.tenant_id and s.id=r.sku_id"
                + " left join warehouse_location l on l.tenant_id=a.tenant_id and l.id=a.location_id"
                + " where a.tenant_id=? and a.warehouse_id=? and a.status='COMMITTED'"
                + " and a.remaining>0 and r.status in ('HELD','COMMITTED')"
                + " order by r.sku_id,a.allocated_at,a.id",
            tenant, head.get("warehouse_id", UUID.class))
        .stream()
        .map(
            r -> new HandoverReservationOption(
                r.get("allocation_id", UUID.class), r.get("reservation_id", UUID.class),
                r.get("reservation_no", String.class), r.get("sku_id", UUID.class),
                r.get("sku_code", String.class), r.get("location_id", UUID.class),
                r.get("location_code", String.class), r.get("lot_id", UUID.class),
                decimalText(r, "allocated_quantity"), decimalText(r, "consumed_quantity"),
                decimalText(r, "remaining")))
        .toList();
  }

  private List<HandoverEventRow> timeline(UUID tenant, UUID handoverId) {
    return sql.fetch(
            "select * from shipment_handover_event where tenant_id=? and handover_id=?"
                + " order by occurred_at,id",
            tenant, handoverId)
        .stream()
        .map(
            r -> new HandoverEventRow(
                r.get("id", UUID.class), r.get("event_type", String.class),
                eventLabel(r.get("event_type", String.class)), r.get("from_status", String.class),
                r.get("to_status", String.class), r.get("parcel_id", UUID.class),
                r.get("actor_user_id", UUID.class), r.get("occurred_at", OffsetDateTime.class),
                r.get("detail", String.class)))
        .toList();
  }

  /**
   * 库位编码小查表（详情里要把库位 id 翻成看得见的编码）。
   *
   * <p>刻意<b>非 static</b>：它要访问本类的 DSLContext 才能查库，写成 static 会连不上 sql。
   */
  private final class HashMapLoc {
    private final Map<UUID, String> cache = new LinkedHashMap<>();

    String code(UUID tenant, UUID locationId) {
      if (locationId == null) return null;
      return cache.computeIfAbsent(
          locationId,
          id -> {
            Record r = sql.fetchOne(
                "select code from warehouse_location where tenant_id=? and id=?", tenant, id);
            return r == null ? null : r.get("code", String.class);
          });
    }
  }

  // ================================================================ 行映射

  private HandoverRow row(Record r) {
    return new HandoverRow(
        r.get("id", UUID.class), r.get("handover_code", String.class),
        r.get("packing_order_id", UUID.class), r.get("packing_code", String.class),
        r.get("outbound_order_id", UUID.class), r.get("order_code", String.class),
        r.get("outbound_status", String.class),
        outboundStatusLabel(r.get("outbound_status", String.class)),
        r.get("warehouse_id", UUID.class), r.get("warehouse_code", String.class),
        r.get("carrier_code", String.class), r.get("carrier_name", String.class),
        r.get("master_waybill_no", String.class), r.get("handover_user_id", UUID.class),
        r.get("handover_at", OffsetDateTime.class), r.get("receiver_name", String.class),
        r.get("receiver_contact", String.class), r.get("status", String.class),
        handoverStatusLabel(r.get("status", String.class)), r.get("parcel_count", Integer.class),
        r.get("parcel_item_count", Integer.class), decimalText(r, "total_quantity"),
        decimalText(r, "total_gross_weight_g"), decimalText(r, "total_volume_cbm"),
        decimalText(r, "deducted_quantity"), decimalText(r, "reserved_deducted_quantity"),
        r.get("reservation_line_count", Integer.class),
        r.get("inventory_transaction_id", UUID.class), r.get("created_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class), r.get("cancelled_at", OffsetDateTime.class));
  }

  // ================================================================ 内部

  private String orderClause(String sort) {
    String key = text(sort).toUpperCase();
    if (!SORTS.contains(key)) key = "CREATED_AT";
    return switch (key) {
      case "HANDOVER_CODE" -> " order by h.handover_code,h.id";
      case "HANDOVER_AT" -> " order by h.handover_at desc nulls last,h.id desc";
      default -> " order by h.created_at desc,h.id desc";
    };
  }

  /** 锁交接单并确认它还是 OPEN。并发两次确认时后一个会被 DB 的状态守卫拒掉。 */
  private Record openRecord(UUID tenant, UUID id) {
    Record r =
        sql.fetchOne(
            "select * from shipment_handover where tenant_id=? and id=? for update", tenant, id);
    if (r == null) throw notFound("交接单不存在");
    String status = r.get("status", String.class);
    if (!"OPEN".equals(status))
      throw conflict("交接单 " + r.get("handover_code", String.class) + " 当前是 "
          + handoverStatusLabel(status) + "，不能再操作 —— 已交接的货改不得");
    return r;
  }

  private void event(UUID tenant, UUID handoverId, String type, String from, String to,
      UUID parcelId, UUID actorId, OffsetDateTime at, String detail) {
    sql.execute(
        "insert into shipment_handover_event(id,tenant_id,handover_id,event_type,from_status,"
            + "to_status,parcel_id,actor_user_id,occurred_at,detail)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz,?)",
        UUID.randomUUID(), tenant, handoverId, type, from, to, parcelId, actorId, at, detail);
  }

  private BigDecimal totalQty(UUID tenant, UUID handoverId) {
    Record r = sql.fetchOne(
        "select coalesce(sum(quantity),0) as q from shipment_handover_item"
            + " where tenant_id=? and handover_id=?",
        tenant, handoverId);
    return r == null ? BigDecimal.ZERO : nzDecimal(r.get("q", BigDecimal.class));
  }

  private String handoverCode() {
    return "SH-" + OffsetDateTime.now(ZoneOffset.UTC).toLocalDate().toString().replace("-", "")
        + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  // ================================================================ 查询helper

  private long count(String query, List<Object> args) {
    return ((Number) sql.fetchOne(query, args.toArray()).get(0)).longValue();
  }

  private int intOf(UUID tenant, String query, Object... args) {
    return ((Number) sql.fetchOne(query, all(new ArrayList<>(List.of(tenant)), args)).get(0))
        .intValue();
  }

  private Record one(UUID tenant, String query, Object... args) {
    Record r = sql.fetchOne(query, all(new ArrayList<>(List.of(tenant)), args));
    if (r == null) throw notFound("对象不存在");
    return r;
  }

  private UUID uuid(String raw) {
    try {
      return UUID.fromString(raw == null ? "" : raw.trim());
    } catch (IllegalArgumentException e) {
      throw bad("id 格式不合法：" + raw);
    }
  }

  private static Object[] all(List<Object> args, Object... extra) {
    var out = new ArrayList<Object>(args);
    out.addAll(List.of(extra));
    return out.toArray();
  }

  private int pageSize(int size) {
    if (size <= 0) throw bad("size 必须大于 0");
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private int offset(int page, int size) {
    return Math.max(page, 0) * pageSize(size);
  }

  private UUID currentTenant() {
    return UUID.fromString(
        sql.fetchOne("select current_setting('app.current_tenant', true)").get(0, String.class));
  }

  // ================================================================ 格式化

  /** 十进制串（不经 double）：NUMERIC 出参一律用 plain 文本回给前端。 */
  private static BigDecimal decimal(String raw, String field, BigDecimal fallback) {
    if (raw == null || raw.isBlank()) {
      if (fallback != null) return fallback;
      throw bad(field + " 不能为空");
    }
    try {
      return new BigDecimal(raw.trim());
    } catch (NumberFormatException e) {
      throw bad(field + " 不是合法数字：" + raw);
    }
  }

  private static String decimalText(Record r, String field) {
    BigDecimal v = r.get(field, BigDecimal.class);
    return v == null ? null : plain(v);
  }

  private static BigDecimal nzDecimal(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  private static long n(Record r, String field) {
    Number v = r.get(field, Number.class);
    return v == null ? 0L : v.longValue();
  }

  private static String plain(BigDecimal v) {
    return v == null ? "0" : v.stripTrailingZeros().toPlainString();
  }

  private static String text(String s) {
    return s == null ? "" : s.trim();
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }

  private static String trim(String s, int max) {
    if (s == null) return "";
    String t = s.trim();
    return t.length() <= max ? t : t.substring(0, max);
  }

  /**
   * 时刻解析：<b>显式带偏移量</b>，不走 {@code Timestamp.valueOf(LocalDateTime)} ——
   * 那条路会把东八区的时间静默偏 −28800 秒（与 8.3.3.2 同一纪律）。
   */
  private static OffsetDateTime instant(String raw, String what) {
    if (raw == null || raw.isBlank()) return OffsetDateTime.now();
    try {
      return OffsetDateTime.parse(raw.trim());
    } catch (DateTimeParseException e) {
      try {
        return java.time.LocalDateTime.parse(raw.trim())
            .atZone(java.time.ZoneId.systemDefault())
            .toOffsetDateTime();
      } catch (DateTimeParseException e2) {
        throw bad(what + " 不是合法时刻：" + raw);
      }
    }
  }

  private static String handoverStatusLabel(String status) {
    for (HandoverStatus s : HandoverStatus.values()) if (s.name().equals(status)) return s.label();
    return status;
  }

  /** 出库单状态的中文标签：复用 8.3.1.1 的既有词表，不在本类再抄一份七态。 */
  private static String outboundStatusLabel(String status) {
    if (status == null) return null;
    for (OutboundOrderModels.OutboundOrderStatus s :
        OutboundOrderModels.OutboundOrderStatus.values())
      if (s.name().equals(status)) return s.label();
    return status;
  }

  private static String eventLabel(String type) {
    for (HandoverEventType s : HandoverEventType.values())
      if (s.name().equals(type)) return s.label();
    return type;
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}