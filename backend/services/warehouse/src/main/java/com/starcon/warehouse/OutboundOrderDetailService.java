package com.starcon.warehouse;

import static com.starcon.warehouse.OutboundOrderDetailModels.*;
import static com.starcon.warehouse.OutboundOrderModels.OutboundOrderStatus;
import static com.starcon.warehouse.OutboundOrderModels.OutboundSourceType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 8.3.1.2 出库单详情：把一屏之内该看到的东西聚到一处，**详情页串联整条出库链路**。
 *
 * <p>蓝图原话：「单出库单作业视图：明细行与已拣/已发量、关联销售订单与买家地址、预占引用、 包裹列表、作业时间线。」输出：「详情 + 作业操作入口。」
 *
 * <p><b>本模块不拥有任何事实表，是纯读投影</b>。8.3.1.1 的 V72 已经建好四张表（单头 / 明细计划快照 / 拣货交接请求 /
 * 作业留痕），蓝图给本模块列的六段内容逐段核对下来全部落在 那四张表里，所以这里**不建占位表** —— 预占引用归 7.2.1、包裹归 8.3.4.1、买家地址归 9 订单履约，
 * 在这里建一张「引用表」就是给别人的事实抢第二个写入所有者。上游交付后它们自己写入， 本端点立即读到，无需迁移。
 *
 * <p><b>两个数量口径必须分开说，这是本模块最要紧的地方：</b>
 *
 * <ul>
 *   <li><b>已拣量</b>：8.3.1.1 刻意没建 picking_task（归 8.3.2.1），它只做到 {@code outbound_pick_request} 交接为止 ——
 *       那是**请求量快照**，不是实拣量。所以逐行 {@code pickedQuantity} 恒为 {@code null}，<b>不是 0</b>。填 0
 *       会让作业员以为「一件都没拣」， 那是假数字，比不显示更危险。可给的最强信号是「是否已交接」与「是否已离开待拣」。
 *   <li><b>已发量</b>：归 8.3.4.1，但 {@code status = SHIPPED} 是终态且 DB 层冻结（V72 §4.2）， 而明细行是不可改的计划快照（V72
 *       §4.4）—— 所以在这张单上「已发量 = 计划件数」<b>成立</b>。 仅在 SHIPPED 时填值，其余状态一律 {@code null}：绝不按拣货进度推算发货进度。
 * </ul>
 *
 * <p><b>状态机只有一处真相</b>：作业操作入口直接取 {@link OutboundOrderStatus#transitionsFrom()}， 与 8.3.1.1
 * 共用同一个转换矩阵，**不另写一份**；V72 的触发器在库层是同一张白名单的 DB 实现。
 *
 * <p><b>时间绑定纪律</b>：{@code ?::timestamptz} 后面一定还跟着别的绑定，cast 不落在绑定序列末位 （与 7.1.2.2 / 8.3.1.1 同一条）。读
 * timestamptz 列走 {@link #moment}，它对驱动返回的三种类型 （OffsetDateTime / Instant / Timestamp）都收口 —— 直接
 * get(field, OffsetDateTime.class) 会在 拿到 Instant 时抛 ClassCastException。
 *
 * <p><b>多租户</b>：每条 SQL 都带 {@code tenant_id = ?} 显式条件（RLS 之外的第一道闸）， 租户由 {@code app.current_tenant} 取
 * —— 它由 AccessService 在事务内从**已认证身份的成员关系** 设置，客户端的 {@code X-Tenant-Id} 头进不来也改不动它。
 */
@Service
public class OutboundOrderDetailService {

  /** numeric(19,6) 口径下的零：避免同一列里混着 scale 不一样的 0。 */
  private static final BigDecimal ZERO = new BigDecimal("0.000000");

  private final DSLContext sql;

  public OutboundOrderDetailService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 详情

  /**
   * 出库单详情。
   *
   * <p>一次调用返回整屏：单头 + 明细行 + 数量口径 + 拣货交接 + 作业时间线 + 四段不可得能力 + 作业操作入口。作业员不需要在多个页面跳（蓝图点名的那条关系）。
   *
   * <p>单头查不到时 404 —— **跨租户与不存在返回同一个结果**，这是有意的：让 A 租户拿 B 租户的 id 探测存在性，本身就是一个信息泄露面。
   */
  public OutboundOrderDetail detail(UUID orderId) {
    var head =
        sql.fetchOne(
            "select o.id,o.order_code,o.source_type,o.source_transfer_order_id,"
                + "o.source_external_ref,o.warehouse_id,o.carrier_code,o.status,o.shipped_at,"
                + "o.cancelled_at,o.cancel_reason,o.created_by_user_id,o.created_at,"
                + "w.code as warehouse_code,w.name as warehouse_name,"
                + "t.order_code as transfer_code "
                + "from outbound_order o "
                + "join warehouse w on w.tenant_id = o.tenant_id and w.id = o.warehouse_id "
                + "left join transfer_order t on t.tenant_id = o.tenant_id "
                + "  and t.id = o.source_transfer_order_id "
                + "where o.tenant_id = ? and o.id = ?",
            tenant(),
            orderId);
    if (head == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "出库单不存在或不属于当前租户：" + orderId);
    }

    var source = OutboundSourceType.valueOf(head.get("source_type", String.class));
    var status = OutboundOrderStatus.valueOf(head.get("status", String.class));

    var lines = lines(orderId, status);
    int lineCount = lines.size();
    BigDecimal total =
        scale(
            lines.stream().map(OutboundDetailLine::plannedQuantity).reduce(ZERO, BigDecimal::add));

    var pickRequest = pickRequest(orderId);
    var timeline = timeline(orderId);

    return new OutboundOrderDetail(
        head.get("id", UUID.class),
        head.get("order_code", String.class),
        source.name(),
        source.label(),
        head.get("source_transfer_order_id", UUID.class),
        head.get("transfer_code", String.class),
        head.get("source_external_ref", String.class),
        head.get("warehouse_id", UUID.class),
        head.get("warehouse_code", String.class),
        head.get("warehouse_name", String.class),
        nullToEmpty(head.get("carrier_code", String.class)),
        status.name(),
        status.label(),
        status.terminal(),
        moment(head, "shipped_at"),
        moment(head, "cancelled_at"),
        head.get("cancel_reason", String.class),
        head.get("created_by_user_id", UUID.class),
        moment(head, "created_at"),
        lineCount,
        total,
        lines,
        quantities(status, total, pickRequest),
        pickRequest != null,
        pickRequest,
        timeline,
        timeline.size(),
        linkedOrder(source),
        shipToAddress(),
        reservationRefs(),
        packages(),
        actions(status));
  }

  // ------------------------------------------------------------ 明细行

  /**
   * 明细行（计划快照，8.3.1.1 的 outbound_order_line）。
   *
   * <p>按 line_no 升序 —— 拣货单与打印都按它排序，作业员心里的顺序也是这个。
   *
   * <p>两个数量列的口径见类注释：pickedQuantity 恒 null；shippedQuantity 仅 SHIPPED 时有值。
   */
  private List<OutboundDetailLine> lines(UUID orderId, OutboundOrderStatus status) {
    boolean shipped = status == OutboundOrderStatus.SHIPPED;
    return sql
        .fetch(
            "select line_no,sku_id,sku_code,sku_name,quantity "
                + "from outbound_order_line where tenant_id = ? and order_id = ? "
                + "order by line_no",
            tenant(),
            orderId)
        .stream()
        .map(
            r -> {
              BigDecimal qty = scale(r.get("quantity", BigDecimal.class));
              return new OutboundDetailLine(
                  r.get("line_no", Integer.class),
                  r.get("sku_id", UUID.class),
                  r.get("sku_code", String.class),
                  r.get("sku_name", String.class),
                  qty,
                  // 实拣量：8.3.2.1 未交付前拿不到。刻意 null 而不是 0。
                  null,
                  // 已发量：SHIPPED 终态 + 明细不可改 ⇒ 已发量 = 计划件数（成立）。
                  // 其余状态没有「已发」这个事实，一律 null，不按拣货进度推算。
                  shipped ? qty : null);
            })
        .toList();
  }

  // ------------------------------------------------------------ 数量口径

  /**
   * 已拣量与已发量**各自独立**的可用性说明。
   *
   * <p>两者放在一起返回而不是合成一个进度条：本模块**没有任何**一个真实数据能算出 「拣货完成百分比」，硬编一个百分比就是本模块最容易犯也最严重的错。
   */
  private List<OutboundQuantityAvailability> quantities(
      OutboundOrderStatus status, BigDecimal total, OutboundDetailPickRequest pickRequest) {
    BigDecimal picked = null;
    BigDecimal shipped = status == OutboundOrderStatus.SHIPPED ? total : null;
    String handover =
        pickRequest == null
            ? "尚未生成拣货任务（仍是待拣）"
            : "已交接给 8.3.2.1：请求 "
                + pickRequest.lineCount()
                + " 行 / "
                + pickRequest.totalPieceCount().stripTrailingZeros().toPlainString()
                + " 件，请注意这是**请求量快照**，不是实拣量";
    return List.of(
        new OutboundQuantityAvailability(
            "PICKED",
            "已拣量",
            picked,
            false,
            "8.3.2.1",
            "**8.3.2.1 拣货任务尚未交付**，因此没有 picking_task 可查：实拣量按实际库位/批次/"
                + "序列号记录在那张表里，不在本服务。当前只能给出作业进度信号 —— "
                + handover
                + "。"),
        new OutboundQuantityAvailability(
            "SHIPPED",
            "已发量",
            shipped,
            shipped != null,
            shipped != null ? null : "8.3.4.1",
            shipped != null
                ? "这张单已是终态「已发货」且 DB 层冻结（V72 §4.2），明细行是不可改的计划快照" + "（V72 §4.4），所以已发量等于计划件数。"
                : "**发货交接归 8.3.4.1，尚未交付**，因此没有发货单/运单可查。当前单据状态为「"
                    + status.label()
                    + "」，未到已发，所以已发量为空 —— 空是事实，不是「忘了查」。"));
  }

  // ------------------------------------------------------------ 拣货交接

  /** 拣货交接请求（8.3.2.1 的契约位）。未交接时返回 null 而不是空对象。 */
  private OutboundDetailPickRequest pickRequest(UUID orderId) {
    var r =
        sql.fetchOne(
            "select id,line_count,total_piece_count,note,requested_by_user_id,requested_at "
                + "from outbound_pick_request where tenant_id = ? and order_id = ?",
            tenant(),
            orderId);
    if (r == null) return null;
    return new OutboundDetailPickRequest(
        r.get("id", UUID.class),
        r.get("line_count", Integer.class),
        scale(r.get("total_piece_count", BigDecimal.class)),
        nullToEmpty(r.get("note", String.class)),
        r.get("requested_by_user_id", UUID.class),
        moment(r, "requested_at"));
  }

  // ------------------------------------------------------------ 作业时间线

  /**
   * 作业时间线（outbound_order_event 全量，append-only）。
   *
   * <p>按 {@code occurred_at, id} 升序 —— id 兜底是必要的：同一毫秒里发生的两条事件 （批量操作会连着写 CREATED + PICK_REQUESTED）光看
   * occurred_at 顺序是不确定的， 界面上时间线会随机跳行。
   */
  private List<OutboundDetailEvent> timeline(UUID orderId) {
    return sql
        .fetch(
            "select id,event_type,from_status,to_status,actor_user_id,occurred_at,detail "
                + "from outbound_order_event where tenant_id = ? and order_id = ? "
                + "order by occurred_at, id",
            tenant(),
            orderId)
        .stream()
        .map(OutboundOrderDetailService::event)
        .toList();
  }

  private static OutboundDetailEvent event(Record r) {
    String type = r.get("event_type", String.class);
    String from = r.get("from_status", String.class);
    String to = r.get("to_status", String.class);
    return new OutboundDetailEvent(
        r.get("id", UUID.class),
        type,
        eventLabel(type),
        from,
        statusLabel(from),
        to,
        statusLabel(to),
        r.get("actor_user_id", UUID.class),
        moment(r, "occurred_at"),
        r.get("detail", String.class));
  }

  /** 事件类型的人读标签。未列出的类型原样返回枚举名 —— 静默吞掉会让人以为没有这条事件。 */
  private static String eventLabel(String type) {
    return switch (type == null ? "" : type) {
      case "CREATED" -> "建单";
      case "PICK_REQUESTED" -> "生成拣货任务";
      case "STATUS_CHANGED" -> "状态推进";
      case "CANCELLED" -> "作废";
      case "PRINTED" -> "打印";
      default -> type;
    };
  }

  /** 状态的中文标签；未知状态原样返回，不抛异常（时间线要能显示出来而不是整页 500）。 */
  private static String statusLabel(String status) {
    if (status == null) return null;
    try {
      return OutboundOrderStatus.valueOf(status).label();
    } catch (IllegalArgumentException e) {
      return status;
    }
  }

  // ------------------------------------------------------------ 四段不可得能力

  /**
   * 关联销售订单。
   *
   * <p>TRANSFER 来源是真关联（7.5.1.1 已交付且同服务），单号能给出；其余三类只有外部单号 **字符串**，9 订单履约未交付、跨服务也不建
   * FK，所以「关联的订单详情」拿不到。
   */
  private OutboundUnavailableLink linkedOrder(OutboundSourceType source) {
    if (source == OutboundSourceType.TRANSFER) {
      return new OutboundUnavailableLink(
          "关联销售订单",
          false,
          "9.1.1.2",
          "/orders",
          "本单是调拨出库，关联的调拨单号在单头已显示且可真跳转。但**销售订单**本身" + "拿不到：9 订单履约尚未交付，出库单与销售订单之间没有跨服务外键。");
    }
    String owner =
        switch (source) {
          case PURCHASE_RETURN -> "6.4.1.1";
          case PRODUCTION -> "10.4.1.1";
          default -> "9.1.1.2";
        };
    return new OutboundUnavailableLink(
        "关联销售订单",
        false,
        owner,
        "/orders",
        "**上游订单服务尚未交付。**本单来源是「"
            + source.label()
            + "」，库里只有一个外部单号字符串（"
            + "source_external_ref），没有跨服务外键（AGENTS.md 明令禁止），"
            + "所以拿不到关联订单的详情、状态与买家信息。刻意**不建占位表** —— "
            + "一张永远填不满的关联表，只会让「这批货是哪张订单来的」看起来有答案。");
  }

  /** 买家地址：与关联订单同源（9 订单履约未交付）。 */
  private OutboundUnavailableLink shipToAddress() {
    return new OutboundUnavailableLink(
        "买家地址",
        false,
        "9.1.1.2",
        "/orders",
        "买家地址归 9 订单履约，**尚未交付**。本服务刻意不在出库单上抄一份地址快照："
            + "地址改过以后两份数据会分叉，而作业员分不清哪份是对的。真要留地址，应由 8.3.4.1 "
            + "发货交接在寄出那一刻冻结寄件快照。");
  }

  /** 预占引用：7.2.1 预占需求列表未交付。 */
  private OutboundUnavailableLink reservationRefs() {
    return new OutboundUnavailableLink(
        "预占引用",
        false,
        "7.2.1.1",
        "/inventory/reservation",
        "**7.2.1 预占需求列表尚未交付**（台账「未开发」）。warehouse 服务里有 "
            + "kind='RESERVE' 的预占流水，但它的引用键由预占侧自己定，当前没有任何 "
            + "指向出库单的引用形态 —— 猜一个 ref_type 去 join 一张没人写的表，"
            + "只是把「没数据」伪装成「查过了，确实没有」。");
  }

  /** 包裹列表：8.3.4.1 发货交接未交付。按张返回，逐段说明。 */
  private List<OutboundUnavailableLink> packages() {
    return List.of(
        new OutboundUnavailableLink(
            "包裹列表",
            false,
            "8.3.4.1",
            "/warehouse/outbound",
            "**8.3.4.1 发货交接尚未交付**，没有包裹/箱号/运单实体。8.3.4.2 打印"
                + "（已交付）里有 carton_code 与 package_count，但它按 external_ref 字符串索引、"
                + "不与出库单建外键，按出库单 id 反查是无根据的猜测。"),
        new OutboundUnavailableLink(
            "承运渠道",
            true,
            null,
            null,
            "承运渠道是本单已登记的事实（单头 carrier_code），在单头已显示；"
                + "但**承运单号与轨迹**归 10 物流，未交付。已发货必须有承运渠道（V72 的 CHECK 钉死）。"));
  }

  // ------------------------------------------------------------ 作业操作入口

  /**
   * 当前状态的合法下一步。
   *
   * <p>直接取 {@link OutboundOrderStatus#transitionsFrom()} —— 与 8.3.1.1 共用同一个转换矩阵，
   * **不另写一份**。规则改了一处就够，界面不会与库层白名单分叉。
   *
   * <p>每个去向标注归属模块：下游动作由下游模块在自己的作业界面完成，详情页只给出入口与前置条件。
   */
  private List<OutboundDetailAction> actions(OutboundOrderStatus status) {
    List<OutboundDetailAction> out = new ArrayList<>();
    for (var t : status.transitionsFrom()) {
      out.add(
          new OutboundDetailAction(
              "TRANSITION",
              t.to().name(),
              t.to().label(),
              t.reasonRequired(),
              t.requiresCarrier(),
              t.note(),
              actionOwner(t.to())));
    }
    return out;
  }

  /** 目标状态归谁做：状态是本服务的，但完成该状态的实体归下游模块。 */
  private static String actionOwner(OutboundOrderStatus to) {
    return switch (to) {
      case PICKING -> "8.3.2.1 拣货任务";
      case PENDING_RECHECK -> "8.3.3.1 复核扫描";
      case PENDING_PACKING -> "8.3.3.2 打包与称重";
      case PENDING_SHIPMENT -> "8.3.4.1 发货交接";
      case SHIPPED -> "8.3.4.1 发货交接";
      case CANCELLED -> "8.3.1.1 批量取消（需填原因）";
      default -> "8.3.1.1";
    };
  }

  // ============================================================ 能力声明

  /**
   * 详情页能力声明：六段内容的可用性、两个数量的口径，以及四条必须写在界面上的说明。
   *
   * <p>前端据此渲染「未接入」的灰块与提示，而不是把六段都画成空的详情表 —— 空的详情表会被读成「这单没有明细/没有包裹」，而真相是「这段能力还没接入」。
   */
  public OutboundOrderDetailCapabilities capabilities() {
    return new OutboundOrderDetailCapabilities(
        List.of(
            linkedOrder(OutboundSourceType.SALES),
            shipToAddress(),
            reservationRefs(),
            packages().get(0)),
        quantities(OutboundOrderStatus.PENDING_PICKING, null, null),
        List.of("8.3.1.1", "8.3.2.1", "8.3.3.1", "8.3.3.2", "8.3.4.1"),
        "**已拣量：8.3.2.1 拣货任务未交付，本服务没有 picking_task 可查。**"
            + "8.3.1.1 只做到 outbound_pick_request 交接为止，那是**请求量快照**，"
            + "不是实拣量。所以详情页的已拣量显示为「待 8.3.2.1 交付」而不是 0 —— "
            + "填 0 会让作业员以为一件都没拣。8.3.2.1 交付后按 (tenant_id, order_id) "
            + "读那张交接请求接手，逐行实拣量立即出现在同一个位置。",
        "**已发量：归 8.3.4.1 发货交接（未交付）。**但已发货的出库单可以给出真实数字："
            + "SHIPPED 是终态且 DB 层冻结、明细行是不可改的计划快照，"
            + "所以已发量 = 计划件数成立。未到已发货的状态一律显示为空 —— 空是事实。",
        "**关联销售订单与买家地址：9 订单履约未交付。**出库单里只有一个外部单号字符串，"
            + "跨服务不建外键（AGENTS.md）。刻意不建占位表，也不抄一份地址快照 —— "
            + "地址改过之后两份数据会分叉，而作业员分不清哪份是对的。",
        "**预占引用归 7.2.1，尚未交付。**warehouse 服务里有 kind='RESERVE' 的预占流水，"
            + "但引用键由预占侧自己定、当前没有任何指向出库单的形态。本模块只读不写："
            + "每张事实表只有一个写入所有者。",
        "**包裹列表归 8.3.4.1，尚未交付。**8.3.4.2 打印里有 carton_code 与 package_count，"
            + "但它按 external_ref 字符串索引、不与出库单建外键，按出库单 id 反查是无根据的猜测。");
  }

  // ============================================================ 内部

  private UUID tenant() {
    return sql.fetchOne("select current_setting('app.current_tenant',true)::uuid as t")
        .get("t", UUID.class);
  }

  /** numeric(19,6) 口径：统一 scale，避免同一列里混着 "2.5" 与 "2.500000"。 */
  private static BigDecimal scale(BigDecimal v) {
    return v == null ? ZERO : v.setScale(6, java.math.RoundingMode.HALF_UP);
  }

  /**
   * 读 timestamptz 列。
   *
   * <p>驱动返回的类型可能是 {@link OffsetDateTime}、{@link java.time.Instant} 或 {@link java.sql.Timestamp}，直接
   * get(field, OffsetDateTime.class) 会在拿到 Instant 时抛 ClassCastException（与 7.1.2.2 / 8.3.1.1 同一处收口）。
   */
  private static OffsetDateTime moment(Record r, String field) {
    Object value = r.get(field);
    if (value == null) return null;
    if (value instanceof OffsetDateTime offset) return offset;
    if (value instanceof java.time.Instant instant) {
      return instant.atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime();
    }
    if (value instanceof java.sql.Timestamp timestamp) {
      return timestamp.toInstant().atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime();
    }
    return OffsetDateTime.parse(value.toString());
  }

  private static String nullToEmpty(String v) {
    return v == null ? "" : v;
  }
}
