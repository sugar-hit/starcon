package com.starcon.warehouse;

import static com.starcon.warehouse.OutboundOrderModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 8.3.1.1 出库单列表：来源、SKU 行数、件数、七态、发货仓、承运渠道，以及三项批量操作
 * （生成拣货任务 / 打印 / 取消）。
 *
 * <p>蓝图原话：「出库作业主列表：来源（销售/采购退货/调拨/加工出库）、SKU 行数、件数、状态
 * （待拣/拣货中/待复核/待打包/待发货/已发货/取消）、发货仓、承运渠道。」输入：「筛选条件
 * （仓库、来源、状态、时间）；用户数据范围。」输出：「分页列表 + 批量操作（生成拣货任务、
 * 打印、取消）。」
 *
 * <p><b>出库单是履约动作的载体，不是订单的影子</b>（蓝图点名的那条关系）。一订单可多出库单
 * （分仓/分批），所以：外部单号存字符串、刻意不建唯一索引；调拨来源反而是真外键，因为
 * 7.5.1.1 已交付且同服务。
 *
 * <p><b>状态机只有一处真相，两处强制</b>：{@link OutboundOrderStatus#transitionsFrom()} 是
 * 转换矩阵（用来生成可读的拒绝理由与前端选项），V72 的 {@code outbound_order_status_guard}
 * 触发器是同一张白名单的 DB 实现。绕过服务层拿到连接也过不去触发器；集成测试里有一条
 * 断言专门比对两者没有分叉。
 *
 * <p><b>SKU 行数与件数现算，不存列</b>：存列就一定会与明细漂移，而列表上这两个数恰恰是
 * 作业员最先会对着明细核对的。V72 建了 {@code outbound_order_line(tenant_id,order_id)}
 * 索引给这个聚合用。
 *
 * <p><b>「生成拣货任务」只做到交接请求为止</b>：蓝图把 8.3.2.1 列为下游，而它拥有拣货任务
 * 表、拣货明细（实际库位/批次/序列号）与异常上报。本模块不建那张表、也不假装它存在 ——
 * 它做的是真实的三件事：校验待拣状态、落一行 {@code outbound_pick_request}、把状态从待拣
 * 推到拣货中。8.3.2.1 交付后按 (tenant_id, order_id) 接手。
 *
 * <p><b>时间绑定纪律</b>：时间筛选条件排在 WHERE 的最前面，{@code ?::timestamptz} 后面一定
 * 还跟着别的绑定（cast 不落在绑定序列末位，与 7.1.2.2 / 7.4.2.1 同一条）。写入用
 * {@link OffsetDateTime} 绑定，不用 {@code Timestamp.valueOf} —— 后者按 JVM 本地时区解释
 * 裸本地时间，在东八区会静默偏 −28800s。
 */
@Service
public class OutboundOrderService {

  /** 单页最大行数。出库作业台是「今天要做什么」的清单，量级可控但仍设上限。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 单张出库单最多收多少明细行。 */
  public static final int MAX_LINES = 500;

  /** 单次批量操作最多处理多少张单。批量无上限等于允许一次误点取消三千张。 */
  public static final int MAX_BATCH = 200;

  /** numeric(19,6) 口径下的零：避免同一列里混着 scale 不一样的 0。 */
  private static final BigDecimal ZERO = new BigDecimal("0.000000");

  private static final DateTimeFormatter CODE_DAY =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

  /** 列表主查询的 FROM：仓库名与调拨单号都是列表要显示的列，所以都 join 进来。 */
  private static final String FROM =
      " from outbound_order o "
          + " join warehouse w on w.tenant_id = o.tenant_id and w.id = o.warehouse_id "
          + " left join transfer_order t on t.tenant_id = o.tenant_id and t.id = o.source_transfer_order_id ";

  private static final String SUMMARY_COLUMNS =
      " o.id,o.order_code,o.source_type,o.source_transfer_order_id,o.source_external_ref,"
          + " o.warehouse_id,o.carrier_code,o.status,o.shipped_at,o.cancelled_at,o.cancel_reason,"
          + " o.created_by_user_id,o.created_at,"
          + " w.code as warehouse_code,w.name as warehouse_name,t.order_code as transfer_code,"
          // 行数与件数现算。刻意用相关标量子查询而不是 LATERAL 派生表：
          // jOOQ 无法为派生表的列命名（get("sku_line_count") 会抛
          // "Field is not contained in row type"），标量子查询则带得出别名。
          + " (select count(*)::int from outbound_order_line l"
          + "   where l.tenant_id = o.tenant_id and l.order_id = o.id) as sku_line_count,"
          + " (select coalesce(sum(l2.quantity),0) from outbound_order_line l2"
          + "   where l2.tenant_id = o.tenant_id and l2.order_id = o.id) as total_piece_count,"
          + " exists(select 1 from outbound_pick_request p"
          + "   where p.tenant_id = o.tenant_id and p.order_id = o.id) as pick_requested";

  private final DSLContext sql;

  public OutboundOrderService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 能力

  /**
   * 能力声明：四类来源、七态状态与转换矩阵、筛选维度、两种打印文档，以及四条必须如实讲
   * 清楚的说明。
   *
   * <p>前端据此渲染选项与提示，而不是把状态机硬编码在界面上 —— 规则改了就该只改一处。
   */
  public OutboundOrderCapabilities capabilities(AccessService.Access actor) {
    List<OutboundEnumOption> sources = new ArrayList<>();
    for (var s : OutboundSourceType.values()) {
      sources.add(new OutboundEnumOption(s.name(), s.label(), s.description()));
    }
    List<OutboundEnumOption> statuses = new ArrayList<>();
    for (var s : OutboundOrderStatus.values()) {
      statuses.add(new OutboundEnumOption(s.name(), s.label(), s.terminal() ? "终态" : "可继续流转"));
    }
    List<OutboundTransitionView> transitions = new ArrayList<>();
    for (var from : OutboundOrderStatus.values()) {
      for (var t : from.transitionsFrom()) {
        transitions.add(
            new OutboundTransitionView(
                from.name(),
                from.label(),
                t.to().name(),
                t.to().label(),
                t.reasonRequired(),
                t.requiresCarrier(),
                t.note()));
      }
    }
    List<OutboundFilterView> filters =
        List.of(
            new OutboundFilterView("warehouseId", "发货仓", true, List.of()),
            new OutboundFilterView("sourceType", "来源", true, names(OutboundSourceType.values())),
            new OutboundFilterView("status", "状态", true, names(OutboundOrderStatus.values())),
            new OutboundFilterView("createdFrom", "创建时间起", true, List.of()),
            new OutboundFilterView("createdTo", "创建时间止", true, List.of()),
            new OutboundFilterView("keyword", "关键字", true, List.of()));
    List<OutboundEnumOption> documents = new ArrayList<>();
    for (var d : OutboundPrintDocument.values()) {
      documents.add(new OutboundEnumOption(d.name(), d.label(), d.description()));
    }
    return new OutboundOrderCapabilities(
        sources,
        statuses,
        transitions,
        filters,
        documents,
        "**拣货任务归 8.3.2.1，本模块不建那张表。**「生成拣货任务」做的是真实的三件事："
            + "校验单子处于待拣、落一行 outbound_pick_request 交接请求、把状态推进到拣货中并留审计。"
            + "8.3.2.1 交付后按 (tenant_id, order_id) 读这张表接手，生成真正的拣货任务"
            + "（实际库位/批次/序列号/路径排序/异常上报都是它的职责）。所以现在看不到拣货明细，"
            + "这是模块边界，不是没做完。",
        "**9 订单履约尚未交付**，所以销售/采购退货/加工三类来源只存外部单号字符串，"
            + "不跨服务建外键、不查对方的表（AGENTS.md）。调拨来源不同：7.5.1.1 已交付且同在"
            + "warehouse 服务，所以是真复合外键到 transfer_order，建单时还会校验发货仓等于"
            + "调拨单的 from_warehouse_id、每个明细 SKU 都在该调拨单里。",
        "用户数据范围按已认证身份的授权生效（不接受任何请求参数）：命中的授权里有 ALL 就看全租户，"
            + "有 DEPARTMENT 看本租户全量（出库单没有部门维度，与 7.1.2.2 同一套退化口径），"
            + "否则按 SELF 收窄到本人创建的出库单。当前请求的数据范围："
            + (InventoryController.selfOnly(actor, "warehouse.inventory.read") ? "SELF（仅本人创建）" : "ALL/DEPARTMENT（全租户）"),
        "**打印只有出库单与拣货单两种**，都只包含本模块自己持有的数据。面单/箱唛/标签打印属于"
            + "8.3.4.2（并行车道），这里不列也不实现 —— 放一种就是抢它的表与端点。");
  }

  private static List<String> names(Enum<?>[] values) {
    List<String> out = new ArrayList<>();
    for (var v : values) out.add(v.name());
    return out;
  }

  // ============================================================ 列表

  /**
   * 出库单分页列表。
   *
   * <p>四个筛选维度（仓库、来源、状态、时间）全部真的落在 SQL 的 WHERE 上，
   * {@code keyword} 跨单号/外部单号/调拨单号/仓库名/承运渠道五处匹配。
   *
   * <p>时间条件**排在最前面**：{@code ?::timestamptz} 后面一定还跟着别的绑定，
   * cast 不落在绑定序列末位（与 7.1.2.2 / 7.4.2.1 同一条纪律）。
   *
   * <p>排序固定为创建时间倒序 —— 作业台永远先看最新的一批，允许前端随意改排序会让人
   * 漏看压在后面的旧单。
   */
  public OutboundOrderPage list(
      List<UUID> warehouseIds,
      List<String> sourceTypes,
      List<String> statuses,
      OffsetDateTime createdFrom,
      OffsetDateTime createdTo,
      String keyword,
      int pageNo,
      int size,
      AccessService.Access actor) {
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 50 : size));
    int offset = Math.max(0, pageNo) * limit;

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    // 1) 时间条件最前：cast 后面一定还有绑定。
    if (createdFrom != null) {
      where.add("o.created_at >= ?::timestamptz");
      binds.add(createdFrom);
    }
    if (createdTo != null) {
      where.add("o.created_at < ?::timestamptz");
      binds.add(createdTo);
    }
    // 2) 租户（RLS 之外的第一道显式条件，双保险）。
    where.add("o.tenant_id = ?");
    binds.add(tenant());
    if (warehouseIds != null && !warehouseIds.isEmpty()) {
      where.add(inClause("o.warehouse_id", warehouseIds.size()));
      binds.addAll(warehouseIds);
    }
    if (sourceTypes != null && !sourceTypes.isEmpty()) {
      List<String> values = upperAll(sourceTypes);
      where.add(inClause("o.source_type", values.size()));
      binds.addAll(values);
    }
    if (statuses != null && !statuses.isEmpty()) {
      List<String> values = upperAll(statuses);
      where.add(inClause("o.status", values.size()));
      binds.addAll(values);
    }
    if (notBlank(keyword)) {
      where.add(
          "(o.order_code ilike ? or o.source_external_ref ilike ? or t.order_code ilike ?"
              + " or w.code ilike ? or w.name ilike ? or o.carrier_code ilike ?)");
      String k = "%" + keyword.trim() + "%";
      for (int i = 0; i < 6; i++) binds.add(k);
    }
    // 用户数据范围（蓝图输入里的「用户数据范围」）：只从已认证身份推导，不接受请求参数。
    if (InventoryController.selfOnly(actor, "warehouse.inventory.read")) {
      where.add("o.created_by_user_id = ?");
      binds.add(actor.userId());
    }
    String sqlWhere = " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) " + FROM + sqlWhere, binds.toArray()).get(0, Long.class);
    var rows =
        sql.fetch(
            "select " + SUMMARY_COLUMNS + FROM  + sqlWhere
                + " order by o.created_at desc, o.id desc limit ? offset ?",
            append(binds, limit, (long) offset).toArray());
    return new OutboundOrderPage(
        rows.stream().map(OutboundOrderService::summary).toList(), total, Math.max(0, pageNo), limit);
  }

  private static String inClause(String column, int n) {
    return column + " in (" + String.join(",", java.util.Collections.nCopies(n, "?")) + ")";
  }

  private static List<String> upperAll(List<String> values) {
    List<String> out = new ArrayList<>();
    for (var v : values) if (notBlank(v)) out.add(v.trim().toUpperCase(Locale.ROOT));
    return out;
  }

  private static OutboundOrderSummary summary(Record r) {
    var source = OutboundSourceType.valueOf(r.get("source_type", String.class));
    var status = OutboundOrderStatus.valueOf(r.get("status", String.class));
    return new OutboundOrderSummary(
        r.get("id", UUID.class),
        r.get("order_code", String.class),
        source.name(),
        source.label(),
        r.get("source_transfer_order_id", UUID.class),
        r.get("transfer_code", String.class),
        r.get("source_external_ref", String.class),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("warehouse_name", String.class),
        r.get("carrier_code", String.class),
        status.name(),
        status.label(),
        r.get("sku_line_count", Integer.class) == null ? 0 : r.get("sku_line_count", Integer.class),
        scale(r.get("total_piece_count", BigDecimal.class)),
        moment(r, "shipped_at"),
        moment(r, "cancelled_at"),
        r.get("cancel_reason", String.class),
        r.get("created_by_user_id", UUID.class),
        moment(r, "created_at"),
        r.get("pick_requested", Boolean.class) != null && r.get("pick_requested", Boolean.class));
  }

  // ============================================================ 建单

  /**
   * 建立出库单（待拣）。
   *
   * <p>建单这件事本身不在蓝图给 8.3.1.1 的职责清单里，但它是这张列表唯一诚实的数据来源：
   * 不建单，列表永远是空的，而空列表撑不起任何一条能力。蓝图把 9 订单履约列为上游，
   * 而它尚未交付 —— 与其等一个没有上游的单据进来，不如把「履约动作的载体」这件事本身
   * 落地，并如实声明：由本模块建单不代表 9 订单已交付，销售来源仍只存外部单号。
   *
   * <p>三条硬校验：
   *
   * <ul>
   *   <li>明细至少一行且 SKU 不重复（库里也有 UNIQUE(tenant_id,order_id,sku_id) 兜底）——
   *       零行单据在台账上看起来像「处理过了」，那比报错危险得多。
   *   <li>调拨来源必须真的对得上：发货仓 = 调拨单 from_warehouse_id，且每个明细 SKU 都在
   *       该调拨单的明细里。发一个调拨单上没有的 SKU，在业务上不可能成立。
   *   <li>SKU 名称读 {@code sku_ref} 冻结，不接受调用方自填 —— 自填商品名等于允许台账
   *       上的货名与商品中心各说各话。
   * </ul>
   */
  @Transactional
  public OutboundOrderSummary create(OutboundOrderCreateInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    OutboundSourceType source = source(input.sourceType());
    if (input.warehouseId() == null) {
      throw bad("发货仓不能为空：出库单必须指明从哪个仓发。");
    }
    List<OutboundOrderLineInput> lines =
        input.lines() == null ? List.of() : input.lines().stream().filter(l -> l != null).toList();
    if (lines.isEmpty()) {
      throw bad("出库单至少要有一行明细：零行单据在台账上看起来像「已经处理过了」。");
    }
    if (lines.size() > MAX_LINES) {
      throw bad("单张出库单最多 " + MAX_LINES + " 行明细，本次 " + lines.size() + " 行。");
    }

    // 来源校验：调拨走真外键，其余三类必须有外部单号。
    String externalRef = null;
    if (source == OutboundSourceType.TRANSFER) {
      if (input.sourceTransferOrderId() == null) {
        throw bad("调拨来源必须指定调拨单（sourceTransferOrderId）。");
      }
      if (notBlank(input.sourceExternalRef())) {
        throw bad("调拨来源已经关联了调拨单，不能再给外部单号：两种表达互斥。");
      }
    } else {
      if (input.sourceTransferOrderId() != null) {
        throw bad(source.label() + "来源不能关联调拨单：只有调拨出库才关联 transfer_order。");
      }
      externalRef = requireText(input.sourceExternalRef(), source.label() + "来源必须给外部单号。");
      if (externalRef.length() > 64) {
        throw bad("外部单号最长 64 个字符。");
      }
    }

    // 明细校验：SKU 存在、数量为正、不重复。
    var skuIds = new LinkedHashSet<UUID>();
    for (var line : lines) {
      if (line.skuId() == null) {
        throw bad("明细行的 skuId 不能为空。");
      }
      if (!skuIds.add(line.skuId())) {
        throw bad("同一张出库单里同一个 SKU 只能有一行（skuId=" + line.skuId() + "）。");
      }
      if (line.quantity() == null || line.quantity().signum() <= 0) {
        throw bad("明细行数量必须大于 0（skuId=" + line.skuId() + "）。");
      }
    }

    // 发货仓必须是本租户的 ACTIVE 仓。
    var warehouse =
        sql.fetchOne(
            "select id,code,name from warehouse where tenant_id = ? and id = ?",
            tenant(),
            input.warehouseId());
    if (warehouse == null) {
      throw notFound("发货仓", input.warehouseId());
    }

    if (source == OutboundSourceType.TRANSFER) {
      verifyTransferSource(input.sourceTransferOrderId(), input.warehouseId(), lines);
    }

    // 读 SKU 冻结可读名。全部命中才算数：少一行就说明传了个不存在的 skuId。
    var skuBinds = new ArrayList<Object>();
    skuBinds.add(tenant());
    for (var line : lines) skuBinds.add(line.skuId());
    var skus =
        sql.fetch(
            "select id,code,name from sku_ref where tenant_id = ? and id in ("
                + String.join(",", java.util.Collections.nCopies(lines.size(), "?")) + ")",
            skuBinds.toArray());
    if (skus.size() != lines.size()) {
      throw bad("明细里有 " + (lines.size() - skus.size()) + " 个 SKU 在当前租户不存在。");
    }
    var names = new java.util.HashMap<UUID, Record>();
    for (var s : skus) names.put(s.get("id", UUID.class), s);

    OffsetDateTime now = OffsetDateTime.now();
    String code = "OB-" + CODE_DAY.format(now) + "-"
        + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    UUID orderId = UUID.randomUUID();
    String carrier = input.carrierCode() == null ? "" : input.carrierCode().trim();

    sql.execute(
        "insert into outbound_order(id,tenant_id,order_code,source_type,source_transfer_order_id,"
            + "source_external_ref,warehouse_id,carrier_code,status,created_by_user_id,created_at,updated_at) "
            + "values(?,?,?,?,?,?,?,?,'PENDING_PICKING',?,?::timestamptz,?::timestamptz)",
        orderId,
        tenant(),
        code,
        source.name(),
        source == OutboundSourceType.TRANSFER ? input.sourceTransferOrderId() : null,
        externalRef,
        input.warehouseId(),
        carrier,
        actorId,
        now,
        now);

    int lineNo = 1;
    var note = new StringBuilder();
    for (var line : lines) {
      var sku = names.get(line.skuId());
      int currentLine = lineNo++;
      sql.execute(
          "insert into outbound_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "quantity,created_at) values(?,?,?,?,?,?,?,?::numeric,default)",
          UUID.randomUUID(),
          tenant(),
          orderId,
          currentLine,
          line.skuId(),
          sku.get("code", String.class),
          sku.get("name", String.class),
          line.quantity().setScale(6, java.math.RoundingMode.HALF_UP),
          now);
      note.append(currentLine).append(". ").append(sku.get("code", String.class)).append(" × ")
          .append(line.quantity().stripTrailingZeros().toPlainString()).append("；");
    }

    event(
        orderId,
        "CREATED",
        null,
        OutboundOrderStatus.PENDING_PICKING,
        actorId,
        now,
        "建单：" + source.label()
            + (externalRef != null ? " · 外部单号 " + externalRef : " · 调拨单 " + input.sourceTransferOrderId())
            + " · 发货仓 " + warehouse.get("code", String.class)
            + " · " + lines.size() + " 个 SKU（" + note + "）"
            + (notBlank(input.note()) ? " · 备注 " + input.note().trim() : ""));

    return requireSummary(orderId);
  }

  /** 调拨来源的三条对齐校验：单存在、发货仓对得上、明细 SKU 在调拨单里。 */
  private void verifyTransferSource(UUID transferId, UUID warehouseId, List<OutboundOrderLineInput> lines) {
    var transfer =
        sql.fetchOne(
            "select id,order_code,from_warehouse_id from transfer_order where tenant_id = ? and id = ?",
            tenant(),
            transferId);
    if (transfer == null) {
      throw notFound("调拨单", transferId);
    }
    if (!transfer.get("from_warehouse_id", UUID.class).equals(warehouseId)) {
      throw bad(
          "调拨出库的发货仓必须是调拨单的发出仓：调拨单 "
              + transfer.get("order_code", String.class)
              + " 从另一个仓发出。");
    }
    var onTransfer =
        sql.fetch(
            "select sku_id from transfer_order_line where tenant_id = ? and order_id = ?",
            tenant(),
            transferId);
    var allowed = new LinkedHashSet<UUID>();
    for (var r : onTransfer) allowed.add(r.get("sku_id", UUID.class));
    for (var line : lines) {
      if (!allowed.contains(line.skuId())) {
        throw bad("SKU " + line.skuId() + " 不在调拨单 " + transfer.get("order_code", String.class) + " 的明细里。");
      }
    }
  }

  // ============================================================ 批量：生成拣货任务

  /**
   * 批量生成拣货任务（交接给 8.3.2.1）。
   *
   * <p>逐单独立处理：一单不合规不影响其它单，成功与失败都逐条列出。
   * 「批量操作」最危险的地方就是「部分成功」被当成「全部成功」，所以失败单必须带着
   * 可读原因回来。
   *
   * <p>每张成功单做三件事：校验当前是待拣 → 落一行 {@code outbound_pick_request}（含行数与
   * 件数快照）→ 把状态推进到拣货中并留审计。第二单开始若遇到同一张单会被 UNIQUE 挡住，
   * 服务层先查一次并给 409 风格的失败原因。
   */
  @Transactional
  public OutboundPickRequestResult requestPicking(List<UUID> orderIds, String note, UUID actorId) {
    List<UUID> ids = batch(orderIds, "生成拣货任务");
    OffsetDateTime now = OffsetDateTime.now();
    var orders = new ArrayList<OutboundOrderSummary>();
    var failures = new ArrayList<OutboundBulkFailure>();
    for (UUID id : ids) {
      try {
        var head = requireHead(id);
        var status = head.get("status", String.class);
        // 「已交接过」排在状态判定之前：重复请求最可能的成因就是上一笔已经成功了，
        // 报「当前状态是拣货中」会让用户以为要去改状态，而真正要做的是「别再发一次」。
        boolean requested =
            sql.fetchOne(
                    "select count(*) from outbound_pick_request where tenant_id = ? and order_id = ?",
                    tenant(),
                    id)
                .get(0, Integer.class)
                > 0;
        if (requested) {
          failures.add(failure(id, head, "这张出库单已经生成过拣货任务了（一张单只交接一次）。"));
          continue;
        }
        if (!OutboundOrderStatus.PENDING_PICKING.name().equals(status)) {
          failures.add(
              failure(id, head, "只有待拣的出库单能生成拣货任务，当前状态是 "
                  + OutboundOrderStatus.valueOf(status).label() + "。"));
          continue;
        }
        // 聚合先算：数量为 0 时还不能动状态，否则会把单子推进到一个交接不存在的状态。
        var agg =
            sql.fetchOne(
                "select count(*)::int as c, coalesce(sum(quantity),0) as q from outbound_order_line"
                    + " where tenant_id = ? and order_id = ?",
                tenant(),
                id);
        int lineCount = agg.get("c", Integer.class);
        BigDecimal pieces = scale(agg.get("q", BigDecimal.class));
        if (lineCount == 0) {
          failures.add(failure(id, head, "这张出库单没有明细行，无法生成拣货任务。"));
          continue;
        }

        // 顺序很重要：先推进状态（带旧状态作为条件的乐观更新），再落交接请求。
        // 反过来写的话，并发下两个请求会先各自插一行交接请求、再有一半状态更新失败，
        // 留下一条状态仍是待拣却已存在交接请求的脏数据。
        int moved =
            sql.execute(
                "update outbound_order set status = 'PICKING', updated_at = ?::timestamptz"
                    + " where tenant_id = ? and id = ? and status = 'PENDING_PICKING'",
                now,
                tenant(),
                id);
        if (moved == 0) {
          failures.add(failure(id, head, "这张出库单刚被另一笔操作接走，请重试。"));
          continue;
        }
        sql.execute(
            "insert into outbound_pick_request(id,tenant_id,order_id,line_count,total_piece_count,note,"
                + "requested_by_user_id,requested_at,created_at) "
                + "values(?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
            UUID.randomUUID(),
            tenant(),
            id,
            lineCount,
            pieces,
            note == null ? "" : note.trim(),
            actorId,
            now,
            now);
        event(
            id,
            "PICK_REQUESTED",
            OutboundOrderStatus.PENDING_PICKING,
            OutboundOrderStatus.PICKING,
            actorId,
            now,
            "生成拣货任务：待拣 → 拣货中 · " + lineCount + " 个 SKU / " + pieces.toPlainString()
                + " 件 · 交接给 8.3.2.1"
                + (notBlank(note) ? " · 说明 " + note.trim() : ""));
        orders.add(requireSummary(id));
      } catch (ResponseStatusException e) {
        // 单张失败不中断整批：批量操作里最忌讳的是「一半成功被当成全部成功」。
        var head = sql.fetchOne("select order_code from outbound_order where tenant_id = ? and id = ?", tenant(), id);
        failures.add(
            new OutboundBulkFailure(
                id, head == null ? null : head.get("order_code", String.class), e.getReason()));
      }
    }
    return new OutboundPickRequestResult(orders.size(), orders, failures);
  }

  // ============================================================ 批量：打印

  /**
   * 批量打印：返回**真正要印的内容**，而不是一个「已提交打印」的空壳。
   *
   * <p>两种文档（出库单 / 拣货单）都由本模块自己的数据拼出，每行明细都来自
   * {@code outbound_order_line}。同时逐单留一条 PRINTED 审计 —— 谁在什么时候印了哪张单
   * 是发货纠纷里第一个要回答的问题。
   *
   * <p>刻意不含面单/箱唛/标签：那是 8.3.4.2 的范围（并行车道），放一种就是抢它的端点。
   */
  @Transactional
  public OutboundPrintResult print(List<UUID> orderIds, String documentName, UUID actorId) {
    List<UUID> ids = batch(orderIds, "打印");
    OutboundPrintDocument document;
    try {
      document = OutboundPrintDocument.valueOf(documentName == null ? "" : documentName.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的打印文档类型：" + documentName + "。可选：" + documentNames());
    }
    OffsetDateTime now = OffsetDateTime.now();
    var orders = new ArrayList<OutboundPrintOrder>();
    for (UUID id : ids) {
      var head =
          sql.fetchOne(
              "select o.id,o.order_code,o.status,o.carrier_code,o.source_external_ref,"
                  + " w.code as warehouse_code,w.name as warehouse_name,"
                  + " coalesce((select sum(l.quantity) from outbound_order_line l"
                  + "   where l.tenant_id = o.tenant_id and l.order_id = o.id),0) as total_piece_count "
                  + " from outbound_order o join warehouse w on w.tenant_id = o.tenant_id and w.id = o.warehouse_id "
                  + " where o.tenant_id = ? and o.id = ?",
              tenant(),
              id);
      if (head == null) {
        throw notFound("出库单", id);
      }
      var status = OutboundOrderStatus.valueOf(head.get("status", String.class));
      var lineRows =
          sql.fetch(
              "select line_no,sku_code,sku_name,quantity from outbound_order_line"
                  + " where tenant_id = ? and order_id = ? order by line_no",
              tenant(),
              id);
      var lines = new ArrayList<OutboundPrintDocumentLine>();
      for (var l : lineRows) {
        lines.add(
            new OutboundPrintDocumentLine(
                l.get("line_no", Integer.class),
                l.get("sku_code", String.class),
                l.get("sku_name", String.class),
                scale(l.get("quantity", BigDecimal.class))));
      }
      orders.add(
          new OutboundPrintOrder(
              head.get("id", UUID.class),
              head.get("order_code", String.class),
              status.name(),
              status.label(),
              head.get("warehouse_code", String.class),
              head.get("warehouse_name", String.class),
              head.get("carrier_code", String.class),
              head.get("source_external_ref", String.class),
              scale(head.get("total_piece_count", BigDecimal.class)),
              lines));
      event(
          id,
          "PRINTED",
          status,
          status,
          actorId,
          now,
          "打印" + document.label() + "：" + lines.size() + " 行明细 / "
              + scale(head.get("total_piece_count", BigDecimal.class)).toPlainString() + " 件");
    }
    return new OutboundPrintResult(document.name(), document.label(), now, orders.size(), orders);
  }

  // ============================================================ 批量：取消

  /**
   * 批量取消。逐单独立处理，失败带可读原因。
   *
   * <p>已发货的单一律取消不了 —— 货已经交出去了，这不是一个能靠改状态挽回的操作，
   * DB 触发器与 CHECK 各挡一次。
   */
  @Transactional
  public OutboundCancelResult cancel(List<UUID> orderIds, String reason, UUID actorId) {
    String why = requireText(reason, "取消必须写原因：没有原因的取消在事后无法解释。");
    if (why.length() > 300) {
      throw bad("取消原因最长 300 个字符。");
    }
    List<UUID> ids = batch(orderIds, "取消");
    OffsetDateTime now = OffsetDateTime.now();
    var orders = new ArrayList<OutboundOrderSummary>();
    var failures = new ArrayList<OutboundBulkFailure>();
    for (UUID id : ids) {
      try {
        var head = requireHead(id);
        var status = OutboundOrderStatus.valueOf(head.get("status", String.class));
        if (!status.canTransitionTo(OutboundOrderStatus.CANCELLED)) {
          failures.add(
              failure(id, head, status.terminal() ? "出库单已" + status.label() + "，不能取消。"
                  : "不允许的状态流转：" + status.label() + " → 取消。"));
          continue;
        }
        int moved =
            sql.execute(
                "update outbound_order set status = 'CANCELLED', cancelled_at = ?::timestamptz,"
                    + " cancel_reason = ?, updated_at = ?::timestamptz"
                    + " where tenant_id = ? and id = ? and status = ?",
                now,
                why,
                now,
                tenant(),
                id,
                status.name());
        if (moved == 0) {
          throw conflict("出库单 " + head.get("order_code", String.class) + " 刚被另一笔操作改动，请重试。");
        }
        event(
            id,
            "CANCELLED",
            status,
            OutboundOrderStatus.CANCELLED,
            actorId,
            now,
            "取消出库单：" + status.label() + " → 取消 · 原因：" + why);
        orders.add(requireSummary(id));
      } catch (ResponseStatusException e) {
        var head = sql.fetchOne("select order_code from outbound_order where tenant_id = ? and id = ?", tenant(), id);
        failures.add(
            new OutboundBulkFailure(
                id, head == null ? null : head.get("order_code", String.class), e.getReason()));
      }
    }
    return new OutboundCancelResult(orders.size(), orders, failures);
  }

  // ============================================================ 状态推进

  /**
   * 状态推进（单张）。
   *
   * <p>本模块是 {@code outbound_order.status} 的所有者，所以这张主表的状态机由本端点驱动。
   * 合法性只有一处定义（{@link OutboundOrderStatus#transitionsFrom()}），非法跃迁在这里
   * 就被 409 挡掉，V72 的触发器在 DB 层再挡一次。
   *
   * <p><b>它不等于下游模块的工作</b>：把单子推到「待打包」不代表打过包，推到「已发货」
   * 不代表承运商已揽收。8.3.2.1 / 8.3.3.x / 8.3.4.1 交付后，它们的端点会在完成各自实体
   * 的同时调用同一套状态机；在此之前，本端点是唯一能把状态往前推的地方，而每一次推进
   * 都留下操作人、时刻与说明。
   *
   * <p>「已发货」额外要求承运渠道（{@code requiresCarrier}）—— 没有承运商就发不出货，
   * DB 的 CHECK 与触发器也各挡一次。
   */
  @Transactional
  public OutboundOrderSummary transition(UUID orderId, OutboundTransitionInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    var head = requireHead(orderId);
    var from = OutboundOrderStatus.valueOf(head.get("status", String.class));
    OutboundOrderStatus to;
    try {
      to =
          OutboundOrderStatus.valueOf(
              input.targetStatus() == null
                  ? ""
                  : input.targetStatus().trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("未知的出库单状态：" + input.targetStatus() + "。可选：" + statusNames());
    }
    if (!from.canTransitionTo(to)) {
      throw conflict(
          from.terminal()
              ? "出库单已" + from.label() + "，终态不能再流转。"
              : "不允许的状态流转：" + from.label() + " → " + to.label() + "。"
                  + (from.transitionsFrom().isEmpty() ? "" : "合法去向：" + nextLabels(from) + "。"));
    }
    var rule = from.transitionTo(to);
    // 作废走独立的批量端点，这里显式拒绝，避免同一个动作有两个入口。
    // 判据必须排在 reasonRequired 之前：取消永远要带原因（DB 的 CHECK 也要求），
    // 顺序反了的话这条专用提示会被「必须写说明」抢先，用户会拿到一个指向错误端点的提示。
    if (to == OutboundOrderStatus.CANCELLED) {
      throw bad("请用取消端点：取消需要单独提交原因，不接受状态推进里顺带带一句说明。");
    }
    String note = input.note() == null ? "" : input.note().trim();
    if (rule.reasonRequired() && note.isEmpty()) {
      throw bad("「" + from.label() + " → " + to.label() + "」必须写说明：这条跃迁要留给后来人看原因。");
    }

    OffsetDateTime now = OffsetDateTime.now();
    String carrier = head.get("carrier_code", String.class);
    if (rule.requiresCarrier() && (carrier == null || carrier.isBlank())) {
      throw bad(
          "推进到已发货必须先有承运渠道：没有承运商就发不出货。本模块不提供在这里改承运渠道的入口，"
              + "因为发货交接归 8.3.4.1。");
    }

    int moved;
    if (to == OutboundOrderStatus.SHIPPED) {
      moved =
          sql.execute(
              "update outbound_order set status = 'SHIPPED', shipped_at = ?::timestamptz,"
                  + " updated_at = ?::timestamptz where tenant_id = ? and id = ? and status = ?",
              now,
              now,
              tenant(),
              orderId,
              from.name());
    } else {
      moved =
          sql.execute(
              "update outbound_order set status = ?, updated_at = ?::timestamptz"
                  + " where tenant_id = ? and id = ? and status = ?",
              to.name(),
              now,
              tenant(),
              orderId,
              from.name());
    }
    if (moved == 0) {
      throw conflict("出库单 " + head.get("order_code", String.class) + " 刚被另一笔操作改动，请重试。");
    }
    event(
        orderId,
        "STATUS_CHANGED",
        from,
        to,
        actorId,
        now,
        rule.note() + (notBlank(note) ? " · " + note : ""));
    return requireSummary(orderId);
  }

  private static String nextLabels(OutboundOrderStatus from) {
    List<String> labels = new ArrayList<>();
    for (var t : from.transitionsFrom()) labels.add(t.to().label());
    return String.join(" / ", labels);
  }

  private static String statusNames() {
    List<String> out = new ArrayList<>();
    for (var s : OutboundOrderStatus.values()) out.add(s.name());
    return String.join(", ", out);
  }

  private static String documentNames() {
    List<String> out = new ArrayList<>();
    for (var d : OutboundPrintDocument.values()) out.add(d.name());
    return String.join(", ", out);
  }

  // ============================================================ 内部

  private Record requireHead(UUID orderId) {
    var head =
        sql.fetchOne(
            "select id,order_code,status,carrier_code from outbound_order where tenant_id = ? and id = ?",
            tenant(),
            orderId);
    if (head == null) {
      throw notFound("出库单", orderId);
    }
    return head;
  }

  /**
   * 按 id 取一行出库单摘要（批量操作返回体用）。
   *
   * <p>与 {@link #list} 共用同一段 SELECT 与同一个映射，所以列表页看到的和操作后回读的
   * 必然是同一份形状 —— 不另写一份 SQL 免得两处漂移。
   */
  private OutboundOrderSummary requireSummary(UUID orderId) {
    var rows =
        sql.fetch(
            "select " + SUMMARY_COLUMNS + FROM + " where o.tenant_id = ? and o.id = ?",
            tenant(),
            orderId);
    if (rows.isEmpty()) {
      throw notFound("出库单", orderId);
    }
    return summary(rows.get(0));
  }

  /** 批量入参规整：非空、去重、保序、限量。 */
  private static List<UUID> batch(List<UUID> ids, String what) {
    if (ids == null || ids.isEmpty()) {
      throw bad("请至少选择一张出库单再执行" + what + "。");
    }
    var out = new LinkedHashSet<UUID>();
    for (var id : ids) if (id != null) out.add(id);
    if (out.isEmpty()) {
      throw bad("请至少选择一张出库单再执行" + what + "。");
    }
    if (out.size() > MAX_BATCH) {
      throw bad("单次" + what + "最多 " + MAX_BATCH + " 张，本次 " + out.size() + " 张。");
    }
    return new ArrayList<UUID>(out);
  }

  private static OutboundBulkFailure failure(UUID id, Record head, String reason) {
    return new OutboundBulkFailure(id, head == null ? null : head.get("order_code", String.class), reason);
  }

  private OutboundSourceType source(String raw) {
    try {
      return OutboundSourceType.valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      List<String> out = new ArrayList<>();
      for (var s : OutboundSourceType.values()) out.add(s.name());
      throw bad("未知的出库来源：" + raw + "。可选：" + String.join(", ", out));
    }
  }

  private void event(
      UUID orderId,
      String type,
      OutboundOrderStatus from,
      OutboundOrderStatus to,
      UUID actorId,
      OffsetDateTime now,
      String detail) {
    sql.execute(
        "insert into outbound_order_event(id,tenant_id,order_id,event_type,from_status,to_status,"
            + "actor_user_id,occurred_at,detail,created_at) "
            + "values(?,?,?,?,?,?,?,?::timestamptz,?,?::timestamptz)",
        UUID.randomUUID(),
        tenant(),
        orderId,
        type,
        from == null ? null : from.name(),
        to == null ? null : to.name(),
        actorId,
        now,
        detail,
        now);
  }

  private UUID tenant() {
    return sql.fetchOne("select current_setting('app.current_tenant',true)::uuid as t").get("t", UUID.class);
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    binds.addAll(List.of(more));
    return binds;
  }

  /** numeric(19,6) 口径：统一 scale，避免同一列里混着 "2.5" 与 "2.500000"。 */
  private static BigDecimal scale(BigDecimal v) {
    return v == null ? ZERO : v.setScale(6, java.math.RoundingMode.HALF_UP);
  }

  /**
   * 读 timestamptz 列。
   *
   * <p>jOOQ 视驱动返回的类型可能是 {@link OffsetDateTime}、{@link java.time.Instant}
   * 或 {@link java.sql.Timestamp}，直接 {@code get(field, OffsetDateTime.class)} 会在拿到
   * Instant 时抛 ClassCastException（与 7.1.2.2 同一处收口）。
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

  private static boolean notBlank(String v) {
    return v != null && !v.isBlank();
  }

  private static String requireText(String raw, String message) {
    String v = raw == null ? "" : raw.trim();
    if (v.isEmpty()) {
      throw bad(message);
    }
    return v;
  }

  private static ResponseStatusException notFound(String what, UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在或不属于当前租户：" + id);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  /** 状态冲突用 409：它和「你填错了」(400) 是两类问题，混在一起前端无法给出不同提示。 */
  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}