package com.starcon.trade;

import static com.starcon.trade.OrderChangeCancelModels.*;

import com.starcon.trade.OrderChangeCancelModels.OrderAddressFieldCode;
import com.starcon.trade.OrderChangeCancelModels.OrderAddressRole;
import com.starcon.trade.OrderChangeCancelModels.OrderCancelScope;
import com.starcon.trade.OrderChangeCancelModels.OrderChangeKind;
import com.starcon.trade.OrderChangeCancelModels.OrderChangeStatus;
import com.starcon.trade.OrderChangeCancelModels.OrderHoldTaskStage;
import com.starcon.trade.OrderChangeCancelModels.OrderHoldTaskStatus;
import com.starcon.trade.OrderChangeCancelModels.OrderProgressSource;
import com.starcon.trade.OrderChangeCancelModels.OrderWritebackStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.1.1.3 订单变更与取消。
 *
 * <p>蓝图三条硬规则，本类逐条对应，<b>并在集成测试里逐条单独复验</b>：
 *
 * <ol>
 *   <li><b>已发货数量不可取消，只能转售后</b>（蓝图加粗句）。取消逐行申请时对照
 *       {@code order_fulfillment_line_progress} 的已发量核算可取消量；
 *       拒绝时逐行报出「已发多少、最多还能取消多少、该转售后（ownerModule 11）」。
 *       V119 守卫 9.3 是同一判据的 DB 层形态，绕过服务层写 SQL 同样被挡。</li>
 *   <li><b>取消前必须先确认仓库停止作业并释放占用</b>。建取消单时同步下达
 *       STOP_WORK 与 RELEASE_OCCUPANCY 两条任务；两个阶段都 COMPLETED 之前
 *       {@link #advanceCancellation} 不允许置 CONFIRMED，并把未完成项作为
 *       {@link OrderChangeCancelBlocker} 逐条报出。V119 守卫 9.2 同样在 DB 层复核。</li>
 *   <li><b>仅在责任方确认后才记录取消终态</b>。申请阶段 {@code cancelledQuantity}
 *       恒为 0（V119 守卫 9.1），终态量与退款金额由
 *       {@code confirm_order_cancellation} 在确认那一刻一并落库。</li>
 * </ol>
 *
 * <p><b>第四条：数量调整需平台允许</b>。平台可变更规则是租户可维护的真实表
 * （{@code order_platform_change_rule}），按平台 × 店铺 × 变更类型查；
 * 查不到规则、规则不允许、超出可改窗口、超出单次减量上限四种情况分别报出，
 * 不用一个布尔值糊过去 —— 它们的运营动作完全不同（配规则 / 换渠道 / 催平台 / 改走线下）。
 *
 * <p><b>本类不改写订单快照。</b>V99 第 8.3 节已把 sales_order_line 与
 * order_address_snapshot 在 SUBMITTED 后冻住，所以地址修改与数量调整都以
 * 「变更请求 + 前后值」的形式留痕，订单侧一行不动。
 *
 * <p><b>时间绑定</b>：一律 {@code ?::timestamptz} + {@link OffsetDateTime}。
 * {@code Timestamp.valueOf(LocalDateTime)} 在本项目所在时区静默偏 −28800s。
 *
 * <p><b>跨租户与不存在返回同一个 404</b>：RLS 已保证跨租户查不到，本类只负责让
 * 「查不到」和「不存在」走同一条出口，不给「这个 id 存在但不属于你」留出口。
 *
 * <p><b>租户过滤不写在 WHERE 里</b>：与 9.1.1.1 / 9.1.1.2 / 9.3.2.1 同一写法，
 * 靠 {@code AccessService.run} 绑定的 {@code app.current_tenant} + 表上的 FORCE RLS。
 */
@Service
public class OrderChangeCancelService {

  /** 状态机：服务层唯一一份矩阵。capabilities 把它原样报出去，集成测试逐状态比对。 */
  static final Map<OrderChangeStatus, List<OrderChangeStatus>> TRANSITIONS =
      Map.of(
          OrderChangeStatus.REQUESTED,
              List.of(
                  OrderChangeStatus.PROCESSING,
                  OrderChangeStatus.PENDING_VERIFICATION,
                  OrderChangeStatus.REJECTED,
                  OrderChangeStatus.CONFIRMED),
          OrderChangeStatus.PROCESSING,
              List.of(
                  OrderChangeStatus.PENDING_VERIFICATION,
                  OrderChangeStatus.REJECTED,
                  OrderChangeStatus.CONFIRMED),
          // 待核验不是死局：仓库回执或平台回写回来之后仍可确认。
          OrderChangeStatus.PENDING_VERIFICATION,
              List.of(OrderChangeStatus.CONFIRMED, OrderChangeStatus.REJECTED),
          OrderChangeStatus.CONFIRMED,
              List.of(),
          OrderChangeStatus.REJECTED,
              List.of());

  /** 下游 7.2.1.3 在 warehouse 服务里的真实释放入口（照抄 ReservationReleaseController 的映射）。 */
  private static final String WAREHOUSE_RELEASE_API =
      "/warehouse/inventory/reservations/{reservationId}/release";

  private final DSLContext sql;

  public OrderChangeCancelService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 本模块哪些能力有数据、哪些还没接上。
   *
   * <p>「已发货数量」这一段是本模块最要紧的边界：9.3.3.1 履约请求与 8 出库的发货事件
   * 都未接入，所以 {@code availableProgress} 只有在投影里真有数据时才是 true。
   * 界面据此把「未发货数量」显示成「未接入」而不是 0。
   */
  public OrderChangeCancelCapabilities capabilities() {
    Long projections =
        sql.fetchOne("select count(*) c from order_fulfillment_line_progress").get("c", Long.class);
    boolean projected = projections != null && projections.intValue() > 0;
    return new OrderChangeCancelCapabilities(
        List.of(
            "平台可变更规则 order_platform_change_rule（租户维护，按平台 × 店铺 × 变更类型判定）",
            "变更请求 order_change_request + order_change_request_line + order_change_address_field"
                + "（地址按字段级前后值留痕，**不改写**订单快照）",
            "取消请求 order_cancellation_request + order_cancellation_line（五态状态机，"
                + "终态数量仅在责任方确认后落库）",
            "仓库停止作业与释放占用 order_warehouse_hold_task（两个阶段，"
                + "都 COMPLETED 才允许确认取消）",
            "平台回写任务 order_platform_writeback_task（2.5.1.1 接手）",
            "事件时间线 order_change_event（append-only）"),
        List.of(
            new OrderChangeCancelBlocker(
                "fulfillmentProgress",
                "9.3.3.1 履约请求 / 8 出库单",
                "已发货数量投影"
                    + (projected
                        ? "已接入：本租户已有投影数据，取消的可取消量按投影核算。"
                        : "**尚未接入**：本租户没有任何履约投影记录，所以「未发货数量」"
                            + "这一栏在界面上必须显示「未接入」而不是 0 —— 显示 0 等于宣称"
                            + "「一件都没发」，那是 8.3.1.2 / 9.1.1.2 已经两次明令禁止的造假。"
                            + "9.3.3.1 落地后由同步器写入同一张表，取消核算立即自动生效。"),
                Map.<String, Object>of("projected", projected)),
            new OrderChangeCancelBlocker(
                "aftersalesTransfer",
                "11 售后（11.1.1.1 / 11.2.1.1 / 11.3.1.1 全部未开发）",
                "已发货数量转售后",
                Map.<String, Object>of(
                    "reason",
                    "已发货数量不可取消，只能转售后。11 售后一条模块都未开发，"
                        + "所以本模块**不建售后表**：取消申请被拒时逐行报出 ownerModule=11 与"
                        + "原因，让人走线下或等 11 交付，绝不在 trade 侧建一张没人写的表"
                        + "让界面显示「已转售后」。"))),
        java.util.Arrays.stream(OrderChangeStatus.values()).map(Enum::name).toList(),
        WAREHOUSE_RELEASE_API,
        "11",
        "租户来自已认证成员关系（AccessService.require → identity /internal/access），"
            + "在事务内绑定 app.current_tenant，由表上的 FORCE RLS 强制隔离；"
            + "客户端的 X-Tenant-Id 头进不来也改不动它。跨租户与不存在返回同一个 404。",
        "订单快照不改写：V99 第 8.3 节已把订单行与地址快照在 SUBMITTED 后冻住，"
            + "所以本模块的地址修改与数量调整都以独立变更单 + 前后值留痕。");
  }

  // ================================================================ 订单夹具

  record OrderHead(
      UUID id, String orderNo, String orderSource, String currency, String externalOrderNo) {}

  record OrderLine(UUID id, String skuCode, BigDecimal quantity, BigDecimal unitPrice) {}

  private OrderHead loadOrder(UUID orderId) {
    Record r =
        sql.fetchOne(
            "select id, order_no, order_source, currency, external_order_no"
                + " from sales_order where id = ?",
            orderId);
    if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在或不属于当前租户");
    return new OrderHead(
        r.get("id", UUID.class),
        r.get("order_no", String.class),
        r.get("order_source", String.class),
        r.get("currency", String.class),
        r.get("external_order_no", String.class));
  }

  private Map<UUID, OrderLine> loadLines(UUID orderId) {
    Map<UUID, OrderLine> lines = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select id, sku_code, quantity, unit_price from sales_order_line"
                + " where order_id = ? order by line_no",
            orderId)) {
      lines.put(
          r.get("id", UUID.class),
          new OrderLine(
              r.get("id", UUID.class),
              r.get("sku_code", String.class),
              r.get("quantity", BigDecimal.class),
              r.get("unit_price", BigDecimal.class)));
    }
    if (lines.isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该订单没有商品行，无法变更或取消");
    return lines;
  }

  // ================================================================ 履约投影

  /**
   * 录入履约投影（已发货数量）。
   *
   * <p>这是 9.3.3.1 履约请求 / 8 出库发货事件落地后要调的**授权入口**，
   * 也是本模块唯一能知道「已发多少」的地方。现在没有调用方，所以规则表里的
   * 来源枚举保留了 {@code TENANT_MAINTAINED}：运维可以手工登记，让取消核算可测、
   * 也让「已发货不可取消」这条硬规则在 9.3.3.1 交付前就已经是真的。
   */
  public OrderChangeCancelProgressRow recordProgress(
      OrderChangeCancelProgressInput input, UUID actorMembershipId) {
    OrderHead order = loadOrder(input.orderId());
    Map<UUID, OrderLine> lines = loadLines(order.id());
    OrderLine line = lines.get(input.orderLineId());
    if (line == null)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "订单行不属于该订单：" + input.orderLineId());

    BigDecimal reserved = nonNegative(input.reservedQuantity(), "reservedQuantity");
    BigDecimal picking = nonNegative(input.pickingQuantity(), "pickingQuantity");
    BigDecimal shipped = nonNegative(input.shippedQuantity(), "shippedQuantity");
    BigDecimal sum = reserved.add(picking).add(shipped);
    if (sum.compareTo(line.quantity()) > 0)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "预留 + 拣货 + 已发 = " + sum.stripTrailingZeros().toPlainString()
              + " 超过订单行数量 " + line.quantity().stripTrailingZeros().toPlainString()
              + "：三个口径相加不可能超过原量");
    if (shipped.signum() > 0 && input.shippedAt() == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已发数量大于 0 时必须给出 shippedAt");

    sql.execute(
        "insert into order_fulfillment_line_progress(id,tenant_id,order_id,order_line_id,"
            + "ordered_qty,reserved_qty,picking_qty,shipped_qty,shipped_at,progress_source,"
            + "observed_at,version,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz,?,?::timestamptz,0,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id,order_id,order_line_id) do update set"
            + " reserved_qty = excluded.reserved_qty, picking_qty = excluded.picking_qty,"
            + " shipped_qty = excluded.shipped_qty, shipped_at = excluded.shipped_at,"
            + " progress_source = excluded.progress_source, observed_at = excluded.observed_at,"
            + " version = order_fulfillment_line_progress.version + 1,"
            + " updated_at = excluded.updated_at",
        UUID.randomUUID(),
        currentTenant(),
        order.id(),
        line.id(),
        line.quantity(),
        reserved,
        picking,
        shipped,
        input.shippedAt(),
        input.progressSource().name(),
        input.observedAt(),
        OffsetDateTime.now(),
        OffsetDateTime.now());

    // 刻意**不**往 order_change_event 写 PROGRESS_RECORDED：
    // 该表按蓝图要求恰好挂一个父单（CHECK 两列异或），而履约投影不属于任何一次
    // 变更或取消请求，硬挂到订单号上会造出一条父单根本不存在的「时间线」。
    // 投影自己的留痕是 observed_at + updated_at + version。
    return progressRow(line, reserved, picking, shipped, input.shippedAt(), input.progressSource(), input.observedAt());
  }

  /** 按订单读投影；未投影的订单行不在返回里（界面对这些行显示「未接入」）。 */
  public List<OrderChangeCancelProgressRow> progress(UUID orderId) {
    OrderHead order = loadOrder(orderId);
    Map<UUID, OrderLine> lines = loadLines(order.id());
    List<OrderChangeCancelProgressRow> out = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select p.order_line_id, p.reserved_qty, p.picking_qty, p.shipped_qty, p.shipped_at,"
                + " p.progress_source, p.observed_at, l.sku_code"
                + " from order_fulfillment_line_progress p"
                + " join sales_order_line l on l.id = p.order_line_id and l.order_id = p.order_id"
                + " where p.order_id = ? order by l.line_no",
            order.id())) {
      BigDecimal shipped = r.get("shipped_qty", BigDecimal.class);
      OrderProgressSource source = OrderProgressSource.valueOf(r.get("progress_source", String.class));
      out.add(
          progressRow(
              lines.get(r.get("order_line_id", UUID.class)),
              r.get("reserved_qty", BigDecimal.class),
              r.get("picking_qty", BigDecimal.class),
              shipped,
              r.get("shipped_at", OffsetDateTime.class),
              source,
              r.get("observed_at", OffsetDateTime.class)));
    }
    return out;
  }

  private OrderChangeCancelProgressRow progressRow(
      OrderLine line,
      BigDecimal reserved,
      BigDecimal picking,
      BigDecimal shipped,
      OffsetDateTime shippedAt,
      OrderProgressSource source,
      OffsetDateTime observedAt) {
    return new OrderChangeCancelProgressRow(
        line.id(),
        line.skuCode(),
        line.quantity(),
        reserved,
        picking,
        shipped,
        shippedAt,
        source,
        observedAt,
        true);
  }

  /** 该订单是否至少有一行投影（决定界面「未发货数量」能不能显示数字）。 */
  boolean progressProjected(UUID orderId) {
    Long n =
        sql.fetchOne(
                "select count(*) c from order_fulfillment_line_progress where order_id = ?", orderId)
            .get("c", Long.class);
    return n != null && n > 0;
  }

  private Map<UUID, BigDecimal> shippedByLine(UUID orderId) {
    Map<UUID, BigDecimal> shipped = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select order_line_id, shipped_qty from order_fulfillment_line_progress"
                + " where order_id = ?",
            orderId)) {
      shipped.put(r.get("order_line_id", UUID.class), r.get("shipped_qty", BigDecimal.class));
    }
    return shipped;
  }

  // ================================================================ 平台可变更规则

  public OrderChangeCancelRulePage pageRules(OrderChangeCancelRuleQuery query) {
    int page = Math.max(query.page(), 0);
    int size = Math.min(Math.max(query.size(), 1), 200);
    StringBuilder where = new StringBuilder(" where 1=1");
    List<Object> params = new ArrayList<>();
    if (query.platformCode() != null && !query.platformCode().isBlank()) {
      where.append(" and platform_code = ?");
      params.add(query.platformCode().trim());
    }
    if (query.shopCode() != null && !query.shopCode().isBlank()) {
      where.append(" and shop_code = ?");
      params.add(query.shopCode().trim());
    }
    if (query.changeKind() != null) {
      where.append(" and change_kind = ?");
      params.add(query.changeKind().name());
    }
    if (query.allowed() != null) {
      where.append(" and allowed = ?");
      params.add(query.allowed());
    }
    Long total =
        sql.fetchOne("select count(*) c from order_platform_change_rule" + where, params.toArray())
            .get("c", Long.class);
    List<OrderChangeCancelRuleRow> items =
        sql.fetch(
                "select id, platform_code, shop_code, change_kind, allowed, deadline_hours,"
                    + " max_decrease_qty, requires_platform_ack, effective_from, effective_to,"
                    + " notes, version,"
                    + " (effective_from <= now() and (effective_to is null or effective_to > now())) as active"
                    + " from order_platform_change_rule"
                    + where
                    + " order by platform_code, shop_code, change_kind, id limit ? offset ?",
                bindings(params, size, offset(page, size)))
            .map(OrderChangeCancelService::ruleRow);
    return new OrderChangeCancelRulePage(items, total, page, size);
  }

  private static OrderChangeCancelRuleRow ruleRow(Record r) {
    return new OrderChangeCancelRuleRow(
        r.get("id", UUID.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        OrderChangeKind.valueOf(r.get("change_kind", String.class)),
        r.get("allowed", Boolean.class),
        r.get("deadline_hours", BigDecimal.class),
        r.get("max_decrease_qty", BigDecimal.class),
        r.get("requires_platform_ack", Boolean.class),
        r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class),
        r.get("notes", String.class),
        r.get("version", Long.class),
        r.get("active", Boolean.class));
  }

  /** 新建规则。手工单不适用平台规则，但规则表是租户维护的平台政策，不因某张单而存在。 */
  public OrderChangeCancelRuleRow createRule(OrderChangeCancelRuleInput input, UUID actor) {
    var kind = ruleKind(input);
    OffsetDateTime from =
        input.effectiveFrom() == null ? OffsetDateTime.now() : input.effectiveFrom();
    if (input.effectiveTo() != null && !input.effectiveTo().isAfter(from))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "生效结束时间必须晚于生效开始时间");
    BigDecimal deadline = input.deadlineHours();
    if (deadline != null && deadline.signum() < 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "可改窗口小时数不能为负");
    BigDecimal maxDecrease = input.maxDecreaseQty();
    if (maxDecrease != null && maxDecrease.signum() <= 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单次减量上限必须大于 0");

    UUID id = UUID.randomUUID();
    try {
      sql.execute(
          "insert into order_platform_change_rule(id,tenant_id,platform_code,shop_code,"
              + "change_kind,allowed,deadline_hours,max_decrease_qty,requires_platform_ack,"
              + "effective_from,effective_to,notes,version,created_by_membership_id,"
              + "created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,0,?,"
              + "?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          input.platformCode().trim(),
          shop(input.platformCode(), input.shopCode()),
          kind.name(),
          ruleAllowed(input),
          deadline,
          maxDecrease,
          input.requiresPlatformAck() == null || input.requiresPlatformAck(),
          from,
          input.effectiveTo(),
          text(input.notes()),
          actor,
          OffsetDateTime.now(),
          OffsetDateTime.now());
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该平台 × 店铺 × 变更类型已有一条规则：同一维度只允许一条，否则「两条都允许」时"
              + "选哪条取决于查询顺序，界面与审计会对不上");
    }
    return oneRule(id);
  }

  /** 更新规则，带 version 乐观锁。改的是政策本身，所以并发覆盖必须是显式失败而不是后写赢。 */
  public OrderChangeCancelRuleRow updateRule(
      UUID id, OrderChangeCancelRuleInput input, UUID actor) {
    OffsetDateTime from =
        input.effectiveFrom() == null ? OffsetDateTime.now() : input.effectiveFrom();
    if (input.effectiveTo() != null && !input.effectiveTo().isAfter(from))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "生效结束时间必须晚于生效开始时间");
    int updated =
        sql.execute(
            "update order_platform_change_rule set allowed = ?, deadline_hours = ?,"
                + " max_decrease_qty = ?, requires_platform_ack = ?, effective_from = ?::timestamptz,"
                + " effective_to = ?::timestamptz, notes = ?, version = version + 1,"
                + " updated_at = ?::timestamptz"
                + " where id = ? and version = ?",
            ruleAllowed(input),
            input.deadlineHours(),
            input.maxDecreaseQty(),
            input.requiresPlatformAck() == null || input.requiresPlatformAck(),
            from,
            input.effectiveTo(),
            text(input.notes()),
            OffsetDateTime.now(),
            id,
            input.version() == null ? -1L : input.version());
    if (updated == 0)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          input.version() == null
              ? "更新规则必须带 version：没有版本号的更新会静默覆盖别人刚改的商务政策"
              : "规则已被他人修改（version 不匹配），请刷新后重试");
    return oneRule(id);
  }

  private OrderChangeCancelRuleRow oneRule(UUID id) {
    Record r =
        sql.fetchOne(
            "select id, platform_code, shop_code, change_kind, allowed, deadline_hours,"
                + " max_decrease_qty, requires_platform_ack, effective_from, effective_to,"
                + " notes, version, true as active from order_platform_change_rule where id = ?",
            id);
    if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "规则不存在或不属于当前租户");
    return ruleRow(r);
  }

  private OrderChangeKind ruleKind(OrderChangeCancelRuleInput input) {
    return input.changeKind();
  }

  private boolean ruleAllowed(OrderChangeCancelRuleInput input) {
    // 缺省 false 而不是 true：「没填允许」按不允许处理，避免漏填就把平台策略放开。
    return input.allowed() != null && input.allowed();
  }

  private static String shop(String platformCode, String shopCode) {
    return shopCode == null || shopCode.isBlank() ? "*" : shopCode.trim();
  }

  // ================================================================ 平台规则判定

  record RuleDecision(
      UUID ruleId,
      boolean allowed,
      boolean requiresAck,
      String reason,
      BigDecimal maxDecreaseQty) {

    static RuleDecision manual(String reason) {
      return new RuleDecision(null, true, false, reason, null);
    }
  }

  /**
   * 判定「这张单能不能改、能不能减」。
   *
   * <p>四种结果分别报出，刻意不用一个布尔值糊过去：手工单（不适用）／无规则／
   * 规则不允许／超出可改窗口或减量上限 —— 它们的运营动作完全不同。
   */
  private RuleDecision evaluateRule(
      OrderHead order, String platformCode, String shopCode, OrderChangeKind kind) {
    if ("MANUAL".equals(order.orderSource()))
      return RuleDecision.manual(
          "手工单不经过平台，没有平台可变更规则：9.1.2.1 平台订单同步未交付，"
              + "只有 MANUAL 来源的单会走到这里");
    if (platformCode == null || platformCode.isBlank())
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "平台单必须给出 platformCode 才能判定平台是否允许变更：sales_order 上没有平台列"
              + "（9.1.2.1 未交付），平台代码只能由调用方传入");
    String shopValue = shop(platformCode, shopCode);
    Record r =
        sql.fetchOne(
            "select id, allowed, requires_platform_ack, deadline_hours, max_decrease_qty"
                + " from order_platform_change_rule"
                + " where platform_code = ? and shop_code in (?, '*') and change_kind = ?"
                + " and effective_from <= now() and (effective_to is null or effective_to > now())"
                + " order by case when shop_code = ? then 0 else 1 end, effective_from desc,"
                + " id limit 1",
            platformCode.trim(),
            shopValue,
            kind.name(),
            shopValue);
    if (r == null)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "平台 " + platformCode + " / 店铺 " + shopValue + " 没有登记「" + label(kind) + "」的可变更规则："
              + "没有规则不等于允许 —— 请先在平台可变更规则里登记该平台的政策（"
              + "各平台的可改窗口与是否允许改量本来就不一样，不能靠代码里的默认值猜）");
    boolean allowed = Boolean.TRUE.equals(r.get("allowed", Boolean.class));
    BigDecimal deadline = r.get("deadline_hours", BigDecimal.class);
    if (deadline != null && orderAgeHours(order) > deadline.doubleValue())
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "超过平台可改窗口：下单已 " + orderAgeHours(order) + " 小时，平台 "
              + platformCode + " 的「" + label(kind) + "」窗口是 " + deadline + " 小时");
    return new RuleDecision(
        r.get("id", UUID.class),
        allowed,
        Boolean.TRUE.equals(r.get("requires_platform_ack", Boolean.class)),
        allowed
            ? "平台允许" + label(kind)
            : "平台 " + platformCode + " 不允许" + label(kind) + "（政策登记结果）",
        r.get("max_decrease_qty", BigDecimal.class));
  }

  private static String label(OrderChangeKind kind) {
    return switch (kind) {
      case ADDRESS_MODIFY -> "修改地址";
      case QUANTITY_INCREASE -> "增加数量";
      case QUANTITY_DECREASE -> "减少数量";
      case CANCEL -> "取消订单";
    };
  }

  /** 订单下单至今的小时数。窗口判定用「下单时刻」而不是「建单时刻」：平台认的是买家下单时间。 */
  private double orderAgeHours(OrderHead order) {
    Record r =
        sql.fetchOne(
            "select coalesce(submitted_at, created_at) as anchor from sales_order where id = ?",
            order.id());
    OffsetDateTime anchor = r == null ? null : r.get("anchor", OffsetDateTime.class);
    if (anchor == null) return 0d;
    return Duration.between(anchor, OffsetDateTime.now()).toMinutes() / 60d;
  }

  // ================================================================ 变更请求

  public OrderChangeCancelPage<OrderChangeCancelChangeRow> pageChanges(OrderChangeCancelQuery q) {
    int page = Math.max(q.page(), 0);
    int size = Math.min(Math.max(q.size(), 1), 200);
    StringBuilder where = new StringBuilder(" where 1=1");
    List<Object> params = new ArrayList<>();
    if (q.orderId() != null) {
      where.append(" and order_id = ?");
      params.add(q.orderId());
    }
    if (q.orderNo() != null && !q.orderNo().isBlank()) {
      where.append(" and order_no ilike ?");
      params.add("%" + q.orderNo().trim() + "%");
    }
    if (q.status() != null) {
      where.append(" and status = ?");
      params.add(q.status().name());
    }
    if (q.changeKind() != null && !q.changeKind().isBlank()) {
      where.append(" and change_kind = ?");
      params.add(q.changeKind());
    }
    long total =
        sql.fetchOne("select count(*) c from order_change_request" + where, params.toArray())
            .get("c", Long.class);
    var items =
        sql.fetch(
                "select id, order_id, order_no, change_kind, status, reason, currency,"
                    + " refund_amount, platform_rule_id, requested_at, requested_by_membership_id,"
                    + " decided_at, rejection_reason, version, created_at"
                    + " from order_change_request"
                    + where
                    + " order by created_at desc, id limit ? offset ?",
                bindings(params, size, offset(page, size)))
            .map(OrderChangeCancelService::changeRow);
    return new OrderChangeCancelPage<>(items, total, page, size);
  }

  private static OrderChangeCancelChangeRow changeRow(Record r) {
    return new OrderChangeCancelChangeRow(
        r.get("id", UUID.class),
        r.get("order_id", UUID.class),
        r.get("order_no", String.class),
        OrderChangeKind.valueOf(r.get("change_kind", String.class)),
        OrderChangeStatus.valueOf(r.get("status", String.class)),
        r.get("reason", String.class),
        r.get("currency", String.class),
        r.get("refund_amount", BigDecimal.class),
        r.get("platform_rule_id", UUID.class),
        true,
        r.get("requested_at", OffsetDateTime.class),
        r.get("requested_by_membership_id", UUID.class),
        r.get("decided_at", OffsetDateTime.class),
        r.get("rejection_reason", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /**
   * 建变更请求。
   *
   * <p>三条校验按顺序：平台允许 → 行属于本单且方向合法 → 减量不超规则上限。
   * 顺序有讲究：先判平台规则，因为「平台根本不允许改量」比「你把第 3 行算错了」
   * 更根本，先报后者会让用户以为改对数量就能提交。
   */
  public OrderChangeCancelChangeRow createChange(
      OrderChangeCancelChangeInput input, UUID actor) {
    OrderHead order = loadOrder(input.orderId());
    Map<UUID, OrderLine> lines = loadLines(order.id());
    OrderChangeKind kind = input.changeKind();

    boolean needsLines = kind != OrderChangeKind.ADDRESS_MODIFY;
    boolean needsAddress = kind == OrderChangeKind.ADDRESS_MODIFY;
    if (needsLines && (input.lines() == null || input.lines().isEmpty()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label(kind) + "必须给出要调整的订单行");
    if (needsAddress && (input.addressFields() == null || input.addressFields().isEmpty()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "修改地址必须给出要变更的字段");

    RuleDecision rule = evaluateRule(order, input.platformCode(), input.shopCode(), kind);
    if (!rule.allowed())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, rule.reason());

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    BigDecimal refund = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

    if (needsLines) {
      for (var line : input.lines()) {
        OrderLine base = lines.get(line.orderLineId());
        if (base == null)
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "订单行不属于该订单：" + line.orderLineId());
        BigDecimal requested = positive(line.requestedQuantity(), "requestedQuantity");
        BigDecimal delta = requested.subtract(base.quantity());
        if (kind == OrderChangeKind.QUANTITY_INCREASE && delta.signum() <= 0)
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "增加数量必须大于原量 " + plain(base.quantity()) + "，收到 " + plain(requested));
        if (kind == OrderChangeKind.QUANTITY_DECREASE) {
          if (delta.signum() >= 0)
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "减少数量必须小于原量 " + plain(base.quantity()) + "，收到 " + plain(requested));
          BigDecimal decrease = delta.abs();
          BigDecimal shipped = shippedByLine(order.id()).getOrDefault(base.id(), BigDecimal.ZERO);
          BigDecimal cancelable = base.quantity().subtract(shipped);
          if (requested.compareTo(cancelable) > 0)
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "订单行 " + base.skuCode() + " 已发 " + plain(shipped)
                    + " 件，减少后不能低于已发数量：只能减到 " + plain(cancelable)
                    + " 件。已发货数量不可取消，只能转售后（ownerModule 11）");
          if (rule.maxDecreaseQty() != null && decrease.compareTo(rule.maxDecreaseQty()) > 0)
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "单次减量 " + plain(decrease) + " 超过平台规则上限 " + plain(rule.maxDecreaseQty()));
          refund = refund.add(base.unitPrice().multiply(decrease.abs()).setScale(4, RoundingMode.HALF_UP));
        }
      }
    }

    sql.execute(
        "insert into order_change_request(id,tenant_id,order_id,order_no,change_kind,status,reason,"
            + "currency,refund_amount,platform_rule_id,requested_by_membership_id,requested_at,"
            + "version,created_at,updated_at)"
            + " values(?,?,?,?,?,'REQUESTED',?,?,?,?,?,?::timestamptz,0,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        order.id(),
        order.orderNo(),
        kind.name(),
        text(input.reason()),
        order.currency(),
        refund,
        rule.ruleId(),
        actor,
        now,
        now,
        now);

    if (needsLines) {
      for (var line : input.lines()) {
        OrderLine base = lines.get(line.orderLineId());
        BigDecimal requested = positive(line.requestedQuantity(), "requestedQuantity");
        BigDecimal delta = requested.subtract(base.quantity());
        sql.execute(
            "insert into order_change_request_line(id,tenant_id,change_request_id,order_line_id,"
                + "sku_code,original_qty,requested_qty,delta_qty,unit_price,line_amount,created_at)"
                + " values(?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
            UUID.randomUUID(),
            currentTenant(),
            id,
            line.orderLineId(),
            base.skuCode(),
            base.quantity(),
            requested,
            delta,
            base.unitPrice(),
            base.unitPrice().multiply(delta).setScale(4, RoundingMode.HALF_UP),
            now);
      }
    }
    if (needsAddress) {
      for (var field : input.addressFields()) {
        String after = text(field.afterValue());
        String before = field.beforeValue() == null ? "" : field.beforeValue().trim();
        if (before.equals(after))
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "地址字段 " + field.fieldCode() + " 的变更前后值相同：不是变更的条目不落库");
        sql.execute(
            "insert into order_change_address_field(id,tenant_id,change_request_id,address_role,"
                + "field_code,before_value,after_value,created_at)"
                + " values(?,?,?,?,?,?,?,?::timestamptz)",
            UUID.randomUUID(),
            currentTenant(),
            id,
            field.addressRole().name(),
            field.fieldCode().name(),
            before,
            after,
            now);
      }
    }

    writeEvent(id, null, "REQUESTED", "", "REQUESTED", label(kind) + "申请：" + rule.reason(), actor, now);
    if (rule.ruleId() != null) {
      writeEvent(id, null, "RULE_MATCHED", "", "", "命中平台规则 " + rule.ruleId(), actor, now);
      createWriteback(
          id, null, kind, input.platformCode(), shop(input.platformCode(), input.shopCode()),
          order.externalOrderNo(), now);
      writeEvent(id, null, "WRITEBACK_CREATED", "", "",
          "已向 2.5.1.1 下达平台回写任务", actor, now);
    }
    return oneChange(id);
  }

  public OrderChangeCancelChangeDetail changeDetail(UUID id) {
    OrderChangeCancelChangeRow header = oneChange(id);
    var lines =
        sql.fetch(
                "select id, order_line_id, sku_code, original_qty, requested_qty, delta_qty,"
                    + " unit_price, line_amount from order_change_request_line"
                    + " where change_request_id = ? order by id",
                id)
            .map(
                r ->
                    new OrderChangeCancelChangeLineRow(
                        r.get("id", UUID.class),
                        r.get("order_line_id", UUID.class),
                        r.get("sku_code", String.class),
                        r.get("original_qty", BigDecimal.class),
                        r.get("requested_qty", BigDecimal.class),
                        r.get("delta_qty", BigDecimal.class),
                        r.get("unit_price", BigDecimal.class),
                        r.get("line_amount", BigDecimal.class)));
    var fields =
        sql.fetch(
                "select address_role, field_code, before_value, after_value"
                    + " from order_change_address_field where change_request_id = ? order by id",
                id)
            .map(
                r ->
                    new OrderChangeCancelAddressFieldRow(
                        OrderAddressRole.valueOf(r.get("address_role", String.class)),
                        OrderAddressFieldCode.valueOf(r.get("field_code", String.class)),
                        r.get("before_value", String.class),
                        r.get("after_value", String.class)));
    List<OrderChangeCancelWritebackRow> writeback = writebacksForChange(id);
    return new OrderChangeCancelChangeDetail(
        header, lines, fields, writeback, timelineForChange(id),
        "变更不改写订单快照：订单行与地址快照在 SUBMITTED 后已被 V99 第 8.3 节冻住，"
            + "所以这里记录的是「申请了什么、被确认了什么」，订单本身保持下单当时的样子。");
  }

  private OrderChangeCancelChangeRow oneChange(UUID id) {
    Record r =
        sql.fetchOne(
            "select id, order_id, order_no, change_kind, status, reason, currency, refund_amount,"
                + " platform_rule_id, requested_at, requested_by_membership_id, decided_at,"
                + " rejection_reason, version, created_at from order_change_request where id = ?",
            id);
    if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "变更请求不存在或不属于当前租户");
    return changeRow(r);
  }

  /** 推进变更状态。CONFIRMED 时校验平台回写是否已被平台接受（规则要求确认时）。 */
  public OrderChangeCancelChangeRow advanceChange(
      UUID id, OrderChangeCancelStatusInput input, UUID actor) {
    OrderChangeCancelChangeRow header = oneChange(id);
    requireTransition(header.status(), input.toStatus(), "变更请求 " + header.orderNo());
    if (input.toStatus() == OrderChangeStatus.REJECTED && text(input.reason()).isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "拒绝必须给出原因：没有原因的拒绝事后没人能追");

    if (input.toStatus() == OrderChangeStatus.CONFIRMED) {
      for (var task : writebacksForChange(id)) {
        if (task.status() == OrderWritebackStatus.REJECTED)
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "平台回写已被拒绝（" + text(task.lastError()) + "），不能把变更确认成生效");
        if (task.status() != OrderWritebackStatus.CONFIRMED)
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "平台回写尚未被平台接受（当前 " + task.status() + "），变更不能确认成生效："
                  + "平台规则要求平台确认，本模块不替平台做这个决定");
      }
    }

    int updated =
        sql.execute(
            "update order_change_request set status = ?,"
                + " decided_by_membership_id = case when ? in ('CONFIRMED','REJECTED',"
                + " 'PENDING_VERIFICATION') then ? else null end,"
                + " decided_at = case when ? in ('CONFIRMED','REJECTED','PENDING_VERIFICATION')"
                + " then ?::timestamptz else null end,"
                + " rejection_reason = case when ? = 'REJECTED' then ? else rejection_reason end,"
                + " version = version + 1, updated_at = ?::timestamptz"
                + " where id = ? and version = ?",
            input.toStatus().name(),
            input.toStatus().name(),
            actor,
            input.toStatus().name(),
            OffsetDateTime.now(),
            input.toStatus().name(),
            text(input.reason()),
            OffsetDateTime.now(),
            id,
            header.version());
    if (updated == 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "变更请求已被他人推进，请刷新后重试");
    writeEvent(
        id, null, input.toStatus().name(), header.status().name(), input.toStatus().name(),
        text(input.reason()), actor, OffsetDateTime.now());
    return oneChange(id);
  }

  // ================================================================ 取消请求

  public OrderChangeCancelPage<OrderChangeCancelCancellationRow> pageCancellations(
      OrderChangeCancelQuery q) {
    int page = Math.max(q.page(), 0);
    int size = Math.min(Math.max(q.size(), 1), 200);
    StringBuilder where = new StringBuilder(" where 1=1");
    List<Object> params = new ArrayList<>();
    if (q.orderId() != null) {
      where.append(" and order_id = ?");
      params.add(q.orderId());
    }
    if (q.orderNo() != null && !q.orderNo().isBlank()) {
      where.append(" and order_no ilike ?");
      params.add("%" + q.orderNo().trim() + "%");
    }
    if (q.status() != null) {
      where.append(" and status = ?");
      params.add(q.status().name());
    }
    long total =
        sql.fetchOne("select count(*) c from order_cancellation_request" + where, params.toArray())
            .get("c", Long.class);
    var items =
        sql.fetch(
                "select id, order_id, order_no, cancel_scope, status, reason, currency,"
                    + " refund_amount, platform_rule_id, shipped_quantity_verified, requested_at,"
                    + " requested_by_membership_id, decided_at, rejection_reason, version, created_at"
                    + " from order_cancellation_request"
                    + where
                    + " order by created_at desc, id limit ? offset ?",
                bindings(params, size, offset(page, size)))
            .map(OrderChangeCancelService::cancellationRow);
    return new OrderChangeCancelPage<>(items, total, page, size);
  }

  private static OrderChangeCancelCancellationRow cancellationRow(Record r) {
    return new OrderChangeCancelCancellationRow(
        r.get("id", UUID.class),
        r.get("order_id", UUID.class),
        r.get("order_no", String.class),
        OrderCancelScope.valueOf(r.get("cancel_scope", String.class)),
        OrderChangeStatus.valueOf(r.get("status", String.class)),
        r.get("reason", String.class),
        r.get("currency", String.class),
        r.get("refund_amount", BigDecimal.class),
        r.get("platform_rule_id", UUID.class),
        true,
        r.get("shipped_quantity_verified", Boolean.class),
        r.get("requested_at", OffsetDateTime.class),
        r.get("requested_by_membership_id", UUID.class),
        r.get("decided_at", OffsetDateTime.class),
        r.get("rejection_reason", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /**
   * 建取消请求：同步下达仓库停止作业与释放占用两条任务。
   *
   * <p>蓝图要求「先确认仓库停止作业并释放占用」—— 所以任务在**申请时**就下达，
   * 而不是等到确认那一刻才建。没有这一步，取消单会变成「先取消，释放以后再说」，
   * 那正是 V119 守卫 9.2 要挡的事。
   */
  public OrderChangeCancelCancellationRow createCancellation(
      OrderChangeCancelCancellationInput input, UUID actor) {
    OrderHead order = loadOrder(input.orderId());
    Map<UUID, OrderLine> lines = loadLines(order.id());
    Map<UUID, BigDecimal> shipped = shippedByLine(order.id());

    if (input.cancelScope() == OrderCancelScope.FULL_ORDER && input.lines().size() != lines.size())
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "整单取消必须覆盖全部 " + lines.size() + " 行，收到 " + input.lines().size() + " 行");

    // 已发货数量不可取消：先按行核对，报错要能指到具体是哪一行、差多少。
    for (var line : input.lines()) {
      OrderLine base = lines.get(line.orderLineId());
      if (base == null)
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "订单行不属于该订单：" + line.orderLineId());
      BigDecimal cancel = positive(line.cancelQuantity(), "cancelQuantity");
      BigDecimal shippedQty = shipped.getOrDefault(base.id(), BigDecimal.ZERO);
      BigDecimal cancelable = base.quantity().subtract(shippedQty);
      if (cancel.compareTo(cancelable) > 0) {
        if (shippedQty.signum() > 0)
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "订单行 " + base.skuCode() + "（" + base.id() + "）已发货 " + plain(shippedQty)
                  + " 件，原量 " + plain(base.quantity()) + " 件，最多只能取消 " + plain(cancelable)
                  + " 件，申请了 " + plain(cancel) + " 件。已发货订单不能取消，只能转售后"
                  + "（ownerModule 11，11 售后尚未开发，需走线下或等其交付）");
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "订单行 " + base.skuCode() + " 申请取消 " + plain(cancel) + " 件，超过原量 "
                + plain(base.quantity()) + " 件");
      }
    }

    RuleDecision rule = evaluateRule(order, input.platformCode(), input.shopCode(), OrderChangeKind.CANCEL);
    if (!rule.allowed())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, rule.reason());

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    boolean verified = !shipped.isEmpty();
    BigDecimal refundEstimate = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    for (var line : input.lines()) {
      OrderLine base = lines.get(line.orderLineId());
      refundEstimate =
          refundEstimate.add(
              base.unitPrice()
                  .multiply(positive(line.cancelQuantity(), "cancelQuantity"))
                  .setScale(4, RoundingMode.HALF_UP));
    }

    sql.execute(
        "insert into order_cancellation_request(id,tenant_id,order_id,order_no,cancel_scope,status,"
            + "reason,currency,refund_amount,platform_rule_id,shipped_quantity_verified,"
            + "requested_by_membership_id,requested_at,version,created_at,updated_at)"
            + " values(?,?,?,?,?,'REQUESTED',?,?,?,?,?,?,?::timestamptz,0,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        order.id(),
        order.orderNo(),
        input.cancelScope().name(),
        text(input.reason()),
        order.currency(),
        refundEstimate,
        rule.ruleId(),
        verified,
        actor,
        now,
        now,
        now);

    for (var line : input.lines()) {
      OrderLine base = lines.get(line.orderLineId());
      BigDecimal cancel = positive(line.cancelQuantity(), "cancelQuantity");
      BigDecimal shippedQty = shipped.getOrDefault(base.id(), BigDecimal.ZERO);
      // cancelled_qty 固定落 0：终态数量只能由 confirm_order_cancellation 在确认时写
      // （V119 守卫 9.1）。refund_amount 同理，申请阶段是 0 而不是预估值。
      sql.execute(
          "insert into order_cancellation_line(id,tenant_id,cancellation_request_id,order_line_id,"
              + "sku_code,ordered_qty,shipped_qty_at_request,cancel_qty,cancelled_qty,unit_price,"
              + "refund_amount,created_at)"
              + " values(?,?,?,?,?,?,?,?,0,?,0,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          id,
          line.orderLineId(),
          base.skuCode(),
          base.quantity(),
          shippedQty,
          cancel,
          base.unitPrice(),
          now);
    }

    // 仓库任务：每个仓两个阶段。
    for (String warehouseRef : input.warehouseRefs()) {
      String ref = warehouseRef == null ? "" : warehouseRef.trim();
      if (ref.isEmpty())
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仓库清单里有空白的仓库编码");
      for (var reservationRef : reservationRefsFor(input)) {
        for (OrderHoldTaskStage stage :
            List.of(OrderHoldTaskStage.STOP_WORK, OrderHoldTaskStage.RELEASE_OCCUPANCY)) {
          sql.execute(
              "insert into order_warehouse_hold_task(id,tenant_id,cancellation_request_id,order_id,"
                  + "order_no,warehouse_ref,reservation_ref,task_stage,status,target_api,"
                  + "owner_module,target_quantity,created_by_membership_id,created_at,updated_at)"
                  + " values(?,?,?,?,?,?,?,?,'PENDING',?,'7.2.1.3',?,?,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              currentTenant(),
              id,
              order.id(),
              order.orderNo(),
              ref,
              reservationRef,
              stage.name(),
              WAREHOUSE_RELEASE_API,
              totalCancelQuantity(input),
              actor,
              now,
              now);
        }
      }
    }
    writeEvent(
        null,
        id,
        "REQUESTED",
        "",
        "REQUESTED",
        "取消申请：涉及 " + input.warehouseRefs().size() + " 个仓，已下达停止作业与释放占用任务",
        actor,
        now);
    writeEvent(
        null,
        id,
        "WAREHOUSE_TASK_CREATED",
        "",
        "",
        "已向 " + input.warehouseRefs().size() + " 个仓下达 STOP_WORK 与 RELEASE_OCCUPANCY",
        actor,
        now);
    if (rule.ruleId() != null) {
      writeEvent(null, id, "RULE_MATCHED", "", "", "命中平台规则 " + rule.ruleId(), actor, now);
      createWriteback(
          null,
          id,
          OrderChangeKind.CANCEL,
          input.platformCode(),
          shop(input.platformCode(), input.shopCode()),
          order.externalOrderNo(),
          now);
      writeEvent(null, id, "WRITEBACK_CREATED", "", "", "已向 2.5.1.1 下达平台回写任务", actor, now);
    }
    return oneCancellation(id);
  }

  private static List<String> reservationRefsFor(OrderChangeCancelCancellationInput input) {
    List<String> refs = new ArrayList<>();
    for (var line : input.lines()) {
      String ref = line.reservationRef();
      if (ref != null && !ref.isBlank() && !refs.contains(ref.trim())) refs.add(ref.trim());
    }
    if (refs.isEmpty()) refs.add("");
    return refs;
  }

  private static BigDecimal totalCancelQuantity(OrderChangeCancelCancellationInput input) {
    BigDecimal total = BigDecimal.ZERO;
    for (var line : input.lines()) total = total.add(line.cancelQuantity());
    return total;
  }

  // 包可见：集成测试要断言「被拒的确认不得留下半成品状态」，
  // 那条断言必须读回真实落库状态而不是只看方法返回。
  OrderChangeCancelCancellationRow oneCancellation(UUID id) {
    Record r =
        sql.fetchOne(
            "select id, order_id, order_no, cancel_scope, status, reason, currency, refund_amount,"
                + " platform_rule_id, shipped_quantity_verified, requested_at,"
                + " requested_by_membership_id, decided_at, rejection_reason, version, created_at"
                + " from order_cancellation_request where id = ?",
            id);
    if (r == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "取消请求不存在或不属于当前租户");
    return cancellationRow(r);
  }

  public OrderChangeCancelCancellationDetail cancellationDetail(UUID id) {
    OrderChangeCancelCancellationRow header = oneCancellation(id);
    var lines =
        sql.fetch(
                "select id, order_line_id, sku_code, ordered_qty, shipped_qty_at_request, cancel_qty,"
                    + " cancelled_qty, unit_price, refund_amount from order_cancellation_line"
                    + " where cancellation_request_id = ? order by id",
                id)
            .map(
                r -> {
                  BigDecimal ordered = r.get("ordered_qty", BigDecimal.class);
                  BigDecimal shipped = r.get("shipped_qty_at_request", BigDecimal.class);
                  BigDecimal cancelable = ordered.subtract(shipped);
                  boolean blocking = cancelable.signum() <= 0;
                  return new OrderChangeCancelCancellationLineRow(
                      r.get("id", UUID.class),
                      r.get("order_line_id", UUID.class),
                      r.get("sku_code", String.class),
                      ordered,
                      shipped,
                      r.get("cancel_qty", BigDecimal.class),
                      r.get("cancelled_qty", BigDecimal.class),
                      cancelable,
                      r.get("unit_price", BigDecimal.class),
                      r.get("refund_amount", BigDecimal.class),
                      blocking,
                      blocking
                          ? "该行已全部发货，可取消数量为 0。已发货订单不能取消，只能转售后"
                              + "（ownerModule 11）"
                          : "");
                });
    return new OrderChangeCancelCancellationDetail(
        header,
        lines,
        holdTasks(id),
        writebacksForCancellation(id),
        progress(header.orderId()),
        timelineForCancellation(id),
        blockers(id),
        warnings(id),
        "取消终态数量与退款金额由确认时刻落库（V119 confirm_order_cancellation）："
            + "仓库停止作业与释放占用都确认之前，cancelledQuantity 恒为 0。");
  }

  /** 「现在还差什么才能确认」——逐条报出，不让操作人去翻两个服务的表。 */
  public List<OrderChangeCancelBlocker> blockers(UUID cancellationId) {
    OrderChangeCancelCancellationRow header = oneCancellation(cancellationId);
    if (header.status() == OrderChangeStatus.CONFIRMED || header.status() == OrderChangeStatus.REJECTED)
      return List.of();
    List<OrderChangeCancelBlocker> blockers = new ArrayList<>();
    for (var task : holdTasks(cancellationId)) {
      if (task.status() != OrderHoldTaskStatus.COMPLETED)
        blockers.add(
            new OrderChangeCancelBlocker(
                "WAREHOUSE_TASK_" + task.taskStage() + "_" + task.status(),
                "7.2.1.3",
                "仓库 " + task.warehouseRef() + " 的"
                    + (task.taskStage() == OrderHoldTaskStage.STOP_WORK ? "停止作业" : "释放占用")
                    + "尚未完成（当前 " + task.status() + "）",
                Map.of(
                    "taskId", task.id(),
                    "warehouseRef", task.warehouseRef(),
                    "taskStage", task.taskStage(),
                    "status", task.status(),
                    "targetApi", task.targetApi())));
    }
    for (var task : writebacksForCancellation(cancellationId)) {
      if (task.status() != OrderWritebackStatus.CONFIRMED)
        blockers.add(
            new OrderChangeCancelBlocker(
                "WRITEBACK_" + task.status(),
                "2.5.1.1",
                "平台回写尚未被平台接受（当前 " + task.status() + "）",
                Map.of("taskId", task.id(), "status", task.status())));
    }
    return blockers;
  }

  /**
   * 「数据完整性提示」：<b>不是</b>确认闸门。
   *
   * <p>刻意与 {@link #blockers} 分开：本单没有任何履约投影时，「未发货数量」是
   * <b>未接入</b>而不是 0，它要求界面明说这一点，但它<b>不</b>阻止确认 ——
   * 拿它当闸门会让本模块在 9.3.3.1 交付前对所有订单完全不可用，
   * 而真正要紧的那条规则（已发货数量不可取消）只要投影有数据就自动生效。
   * 所以：提示挂在 warnings 上并逐条说明该人工核对，硬闸门只留责任方回执。
   */
  public List<OrderChangeCancelBlocker> warnings(UUID cancellationId) {
    OrderChangeCancelCancellationRow header = oneCancellation(cancellationId);
    if (header.shippedQuantityVerified()) return List.of();
    return List.of(
        new OrderChangeCancelBlocker(
            "FULFILLMENT_PROGRESS_NOT_CONNECTED",
            "9.3.3.1 履约请求",
            "本单没有任何履约投影记录，「未发货数量」是**未接入**而不是 0："
                + "本次取消的可取消量核对不完整，确认前请人工确认已发数量",
            Map.of("orderId", header.orderId())));
  }

  /**
   * 推进取消状态。CONFIRMED 走 DB 侧的窄函数：终态数量、退款金额、事件与状态翻转
   * 在**同一事务**里完成，不存在「终态量已落而状态没翻」的中间态。
   */
  public OrderChangeCancelCancellationRow advanceCancellation(
      UUID id, OrderChangeCancelStatusInput input, UUID actor) {
    OrderChangeCancelCancellationRow header = oneCancellation(id);
    requireTransition(header.status(), input.toStatus(), "取消请求 " + header.orderNo());
    if (input.toStatus() == OrderChangeStatus.REJECTED && text(input.reason()).isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "拒绝必须给出原因：没有原因的拒绝事后没人能追");

    if (input.toStatus() == OrderChangeStatus.CONFIRMED) {
      var pending = blockers(id);
      if (!pending.isEmpty())
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "还不能确认取消，还有 " + pending.size() + " 项未完成：" + pending.get(0).description());
      sql.execute(
          "select confirm_order_cancellation(?,?,?,?::timestamptz,?)",
          currentTenant(),
          id,
          actor,
          OffsetDateTime.now(),
          UUID.randomUUID());
      return oneCancellation(id);
    }

    int updated =
        sql.execute(
            "update order_cancellation_request set status = ?,"
                + " decided_by_membership_id = case when ? in ('CONFIRMED','REJECTED',"
                + " 'PENDING_VERIFICATION') then ? else null end,"
                + " decided_at = case when ? in ('CONFIRMED','REJECTED','PENDING_VERIFICATION')"
                + " then ?::timestamptz else null end,"
                + " rejection_reason = case when ? = 'REJECTED' then ? else rejection_reason end,"
                + " version = version + 1, updated_at = ?::timestamptz"
                + " where id = ? and version = ?",
            input.toStatus().name(),
            input.toStatus().name(),
            actor,
            input.toStatus().name(),
            OffsetDateTime.now(),
            input.toStatus().name(),
            text(input.reason()),
            OffsetDateTime.now(),
            id,
            header.version());
    if (updated == 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "取消请求已被他人推进，请刷新后重试");
    writeEvent(
        id, null, input.toStatus().name(), header.status().name(), input.toStatus().name(),
        text(input.reason()), actor, OffsetDateTime.now());
    return oneCancellation(id);
  }

  // ================================================================ 仓库任务回执

  /** 仓库回执登记（7.2.1.3 释放与过期）。这是「仓库停止作业及释放结果」的落点。 */
  public OrderChangeCancelHoldTaskRow updateHoldTask(
      UUID cancellationId, UUID taskId, OrderChangeCancelHoldTaskInput input, UUID actor) {
    OrderChangeCancelHoldTaskRow current = oneHoldTask(cancellationId, taskId);
    if (current.status() == OrderHoldTaskStatus.COMPLETED)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "该任务已是 COMPLETED，回执不可改写：释放结果是举证凭据");
    if (input.status() == OrderHoldTaskStatus.COMPLETED && text(input.resultNote()).isEmpty())
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "完成必须给出结果说明：没有回执的释放事后无法核对");
    if ((input.status() == OrderHoldTaskStatus.FAILED
            || input.status() == OrderHoldTaskStatus.REJECTED)
        && text(input.failureReason()).isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "失败或拒绝必须给出原因");

    BigDecimal completed =
        input.completedQuantity() == null ? current.completedQuantity() : input.completedQuantity();
    if (completed.compareTo(current.targetQuantity()) > 0)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "实际完成数量 " + plain(completed) + " 超过任务目标 " + plain(current.targetQuantity()));

    int updated =
        sql.execute(
            "update order_warehouse_hold_task set status = ?, completed_quantity = ?,"
                + " result_note = ?, failure_reason = ?,"
                + " acknowledged_at = case when ? <> 'PENDING' and acknowledged_at is null"
                + " then ?::timestamptz else acknowledged_at end,"
                + " completed_at = case when ? = 'COMPLETED' then ?::timestamptz else completed_at end,"
                + " updated_at = ?::timestamptz where id = ? and status = ?",
            input.status().name(),
            completed,
            text(input.resultNote()),
            input.status() == OrderHoldTaskStatus.FAILED || input.status() == OrderHoldTaskStatus.REJECTED
                ? text(input.failureReason())
                : "",
            input.status().name(),
            OffsetDateTime.now(),
            input.status().name(),
            OffsetDateTime.now(),
            OffsetDateTime.now(),
            taskId,
            current.status().name());
    if (updated == 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "任务状态已被他人更新，请刷新后重试");
    writeEvent(
        null,
        cancellationId,
        "WAREHOUSE_TASK_" + input.status().name(),
        current.status().name(),
        input.status().name(),
        "仓库 " + current.warehouseRef() + " / " + current.taskStage() + "："
            + (text(input.resultNote()).isEmpty() ? text(input.failureReason()) : text(input.resultNote())),
        actor,
        OffsetDateTime.now());
    return oneHoldTask(cancellationId, taskId);
  }

  private OrderChangeCancelHoldTaskRow oneHoldTask(UUID cancellationId, UUID taskId) {
    Record r =
        sql.fetchOne(
            "select id, cancellation_request_id, warehouse_ref, reservation_ref, task_stage,"
                + " status, target_api, owner_module, target_quantity, completed_quantity,"
                + " result_note, failure_reason, acknowledged_at, completed_at"
                + " from order_warehouse_hold_task where id = ? and cancellation_request_id = ?",
            taskId,
            cancellationId);
    if (r == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓库任务不存在或不属于该取消请求");
    return new OrderChangeCancelHoldTaskRow(
        r.get("id", UUID.class),
        r.get("cancellation_request_id", UUID.class),
        r.get("warehouse_ref", String.class),
        r.get("reservation_ref", String.class),
        OrderHoldTaskStage.valueOf(r.get("task_stage", String.class)),
        OrderHoldTaskStatus.valueOf(r.get("status", String.class)),
        r.get("target_api", String.class),
        r.get("owner_module", String.class),
        r.get("target_quantity", BigDecimal.class),
        r.get("completed_quantity", BigDecimal.class),
        r.get("result_note", String.class),
        r.get("failure_reason", String.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class));
  }

  public List<OrderChangeCancelHoldTaskRow> holdTasks(UUID cancellationId) {
    return sql.fetch(
            "select id, cancellation_request_id, warehouse_ref, reservation_ref, task_stage, status,"
                + " target_api, owner_module, target_quantity, completed_quantity, result_note,"
                + " failure_reason, acknowledged_at, completed_at from order_warehouse_hold_task"
                + " where cancellation_request_id = ? order by warehouse_ref, task_stage",
            cancellationId)
        .map(
            r ->
                new OrderChangeCancelHoldTaskRow(
                    r.get("id", UUID.class),
                    r.get("cancellation_request_id", UUID.class),
                    r.get("warehouse_ref", String.class),
                    r.get("reservation_ref", String.class),
                    OrderHoldTaskStage.valueOf(r.get("task_stage", String.class)),
                    OrderHoldTaskStatus.valueOf(r.get("status", String.class)),
                    r.get("target_api", String.class),
                    r.get("owner_module", String.class),
                    r.get("target_quantity", BigDecimal.class),
                    r.get("completed_quantity", BigDecimal.class),
                    r.get("result_note", String.class),
                    r.get("failure_reason", String.class),
                    r.get("acknowledged_at", OffsetDateTime.class),
                    r.get("completed_at", OffsetDateTime.class)));
  }

  // ================================================================ 平台回写任务

  /**
   * 下达平台回写任务（下游 2.5.1.1）。
   *
   * <p><b>只为平台单建</b>：手工单不经过平台，没有可回写的对象。建了却永远没人处理，
   * 界面上就会挂一条永远「待回写」的任务，那是拿空任务冒充下游已接入。
   */
  private void createWriteback(
      UUID changeRequestId,
      UUID cancellationRequestId,
      OrderChangeKind kind,
      String platformCode,
      String shopCode,
      String platformOrderRef,
      OffsetDateTime now) {
    sql.execute(
        "insert into order_platform_writeback_task(id,tenant_id,change_request_id,"
            + "cancellation_request_id,platform_code,shop_code,platform_order_ref,change_kind,"
            + "status,owner_module,attempt_count,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,'PENDING','2.5.1.1',0,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        changeRequestId,
        cancellationRequestId,
        platformCode.trim(),
        shopCode,
        platformOrderRef == null ? "" : platformOrderRef,
        kind.name(),
        now,
        now);
  }

  public List<OrderChangeCancelWritebackRow> writebacks(UUID requestId, boolean cancellation) {
    return cancellation ? writebacksForCancellation(requestId) : writebacksForChange(requestId);
  }

  List<OrderChangeCancelWritebackRow> writebacksForChange(UUID changeRequestId) {
    return sql.fetch(
            "select id, change_request_id, cancellation_request_id, platform_code, shop_code,"
                + " platform_order_ref, change_kind, status, owner_module, attempt_count, last_error,"
                + " submitted_at, settled_at from order_platform_writeback_task"
                + " where change_request_id = ? order by created_at desc",
            changeRequestId)
        .map(OrderChangeCancelService::writebackRow);
  }

  List<OrderChangeCancelWritebackRow> writebacksForCancellation(UUID cancellationId) {
    return sql.fetch(
            "select id, change_request_id, cancellation_request_id, platform_code, shop_code,"
                + " platform_order_ref, change_kind, status, owner_module, attempt_count, last_error,"
                + " submitted_at, settled_at from order_platform_writeback_task"
                + " where cancellation_request_id = ? order by created_at desc",
            cancellationId)
        .map(OrderChangeCancelService::writebackRow);
  }

  private static OrderChangeCancelWritebackRow writebackRow(Record r) {
    return new OrderChangeCancelWritebackRow(
        r.get("id", UUID.class),
        r.get("change_request_id", UUID.class),
        r.get("cancellation_request_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("platform_order_ref", String.class),
        OrderChangeKind.valueOf(r.get("change_kind", String.class)),
        OrderWritebackStatus.valueOf(r.get("status", String.class)),
        r.get("owner_module", String.class),
        r.get("attempt_count", Integer.class),
        r.get("last_error", String.class),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("settled_at", OffsetDateTime.class));
  }

  /** 平台回写结果登记（2.5.1.1 回写）。 */
  public OrderChangeCancelWritebackRow updateWriteback(
      UUID taskId, OrderChangeCancelWritebackInput input, UUID actor) {
    Record current =
        sql.fetchOne(
            "select id, change_request_id, cancellation_request_id, platform_order_ref, change_kind,"
                + " status, attempt_count from order_platform_writeback_task where id = ?",
            taskId);
    if (current == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "回写任务不存在或不属于当前租户");
    if (OrderWritebackStatus.valueOf(current.get("status", String.class))
            == OrderWritebackStatus.CONFIRMED)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "回写任务已被平台接受，不可改写");
    if (input.status() == OrderWritebackStatus.REJECTED && text(input.lastError()).isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "平台拒绝必须给出原因");

    sql.execute(
        "update order_platform_writeback_task set status = ?,"
            + " attempt_count = attempt_count + 1, last_error = ?,"
            + " submitted_at = case when ? <> 'PENDING' and submitted_at is null"
            + " then ?::timestamptz else submitted_at end,"
            + " settled_at = case when ? in ('CONFIRMED','REJECTED','PENDING_VERIFICATION')"
            + " then ?::timestamptz else settled_at end,"
            + " updated_at = ?::timestamptz where id = ?",
        input.status().name(),
        text(input.lastError()),
        input.status().name(),
        OffsetDateTime.now(),
        input.status().name(),
        OffsetDateTime.now(),
        OffsetDateTime.now(),
        taskId);
    UUID changeId = current.get("change_request_id", UUID.class);
    UUID cancelId = current.get("cancellation_request_id", UUID.class);
    writeEvent(
        changeId,
        cancelId,
        "WRITEBACK_" + input.status().name(),
        current.get("status", String.class),
        input.status().name(),
        text(input.lastError()),
        actor,
        OffsetDateTime.now());
    Record after =
        sql.fetchOne(
            "select id, change_request_id, cancellation_request_id, platform_code, shop_code,"
                + " platform_order_ref, change_kind, status, owner_module, attempt_count, last_error,"
                + " submitted_at, settled_at from order_platform_writeback_task where id = ?",
            taskId);
    return writebackRow(after);
  }

  // ================================================================ 时间线

  private List<OrderChangeCancelEventRow> timelineForChange(UUID id) {
    return sql.fetch(
            "select id, event_type, from_status, to_status, detail, actor_membership_id, occurred_at"
                + " from order_change_event where change_request_id = ? order by occurred_at, id",
            id)
        .map(OrderChangeCancelService::eventRow);
  }

  private List<OrderChangeCancelEventRow> timelineForCancellation(UUID id) {
    return sql.fetch(
            "select id, event_type, from_status, to_status, detail, actor_membership_id, occurred_at"
                + " from order_change_event where cancellation_request_id = ?"
                + " order by occurred_at, id",
            id)
        .map(OrderChangeCancelService::eventRow);
  }

  private static OrderChangeCancelEventRow eventRow(Record r) {
    return new OrderChangeCancelEventRow(
        r.get("id", UUID.class),
        r.get("event_type", String.class),
        r.get("from_status", String.class),
        r.get("to_status", String.class),
        r.get("detail", String.class),
        r.get("actor_membership_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class));
  }

  private void writeEvent(
      UUID changeRequestId,
      UUID cancellationRequestId,
      String eventType,
      String fromStatus,
      String toStatus,
      String detail,
      UUID actor,
      OffsetDateTime occurredAt) {
    sql.execute(
        "insert into order_change_event(id,tenant_id,change_request_id,cancellation_request_id,"
            + "event_type,from_status,to_status,detail,actor_membership_id,occurred_at,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        changeRequestId,
        cancellationRequestId,
        eventType,
        text(fromStatus),
        text(toStatus),
        text(detail),
        actor,
        occurredAt,
        OffsetDateTime.now());
  }

  // ================================================================ 工具

  private UUID currentTenant() {
    String value =
        sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'') v").get("v", String.class);
    if (value == null)
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "租户上下文缺失，拒绝写入");
    return UUID.fromString(value);
  }

  private void requireTransition(
      OrderChangeStatus from, OrderChangeStatus to, String what) {
    List<OrderChangeStatus> allowed = TRANSITIONS.getOrDefault(from, List.of());
    if (!allowed.contains(to))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          what + " 当前状态「" + from + "」不能转到「" + to + "」，可选："
              + (allowed.isEmpty() ? "无（终态）" : allowed));
  }

  private static BigDecimal positive(BigDecimal value, String field) {
    if (value == null || value.signum() <= 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 必须大于 0");
    return value;
  }

  private static BigDecimal nonNegative(BigDecimal value, String field) {
    if (value == null || value.signum() < 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 不能为负");
    return value;
  }

  private static String plain(BigDecimal value) {
    return value == null ? "0" : value.stripTrailingZeros().toPlainString();
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static int offset(int page, int size) {
    return page * size;
  }

  /** 把筛选参数与分页参数合成一份绑定数组（jOOQ 的 bind 参数全是 varargs）。 */
  private static Object[] bindings(List<Object> params, int size, int offset) {
    List<Object> all = new ArrayList<>(params);
    all.add(size);
    all.add(offset);
    return all.toArray();
  }
}
