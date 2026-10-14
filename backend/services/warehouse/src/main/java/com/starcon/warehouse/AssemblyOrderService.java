package com.starcon.warehouse;

import static com.starcon.warehouse.AssemblyOrderModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 8.4.1.1 组装单：实物套装组装的仓库侧单据与库存动作。
 *
 * <p>蓝图 08 卷 8.4.1.1 加粗的那句是本类的全部理由：
 * 「按 BOM 领用组件、消耗组件库存、产生成品 SKU 库存，**同一事务完成**。」
 * 所以 {@link #issue} 标注 {@code @Transactional}，组件出库与成品入库要么都成、
 * 要么都回滚 —— 不允许出现「组件扣了成品没进」或反之。
 *
 * <p><b>与 6.4.1.1 加工单（ProcessingOrderService）的关系</b>：那是 trade 单据的一次库存消费，
 * 仓库侧**不建单据**；组装单是 warehouse <b>自己拥有</b>的现场作业单据（业务可以不等加工单，
 * 直接按成品 SKU + BOM 版本发起）。两者因此各有各的单据模型，但库存动作的纪律完全一致：
 * 显式 SQL、同一个本地事务、先出料后入货。
 *
 * <p><b>跨服务边界</b>：BOM 在 catalog 3.5.2、加工单在 trade 6.4.1.1，都不是本服务的表。
 * 本服务**不 join、不建外键**，而是把组装要用的组件明细在自己的
 * {@code assembly_order_component} 上落成冻结快照（单位用量 / 损耗率 / 应领量），
 * 并用 {@code bomId / bomVersionId / bomComponentId} 作引用键追溯。
 *
 * <p><b>虚拟套装</b>（蓝图点名「虚拟套装走不到这里」）：虚拟套装是 catalog 的 {@code bundle}，
 * 它**不占 SKU 行**（V25 原文：「bundle 没有任何 sku 外键 —— 虚拟组合不占 SKU 行」），
 * 所以填不出一个落在 {@code sku_ref} 里的成品 SKU —— 结构上进不来，不需要额外 if。
 */
@Service
public class AssemblyOrderService {

  private static final int MAX_PAGE_SIZE = 200;

  /** 排序白名单。与 WarehouseDetailService 同一纪律：不接受任意排序键。 */
  private static final List<String> SORTS =
      List.of("CREATED_AT", "ORDER_CODE", "ASSEMBLY_QUANTITY", "STATUS");

  /** 状态词表，与 V103 的 CHECK 逐字一致。 */
  private static final List<String> STATUSES = List.of("DRAFT", "COMPLETED", "CANCELLED");

  /** 上游来源词表，与 V103 的 CHECK 逐字一致。 */
  private static final List<String> SOURCES = List.of(SOURCE_PROCESSING_ORDER, SOURCE_DIRECT);

  private final DSLContext sql;

  public AssemblyOrderService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：跨服务边界、虚拟套装边界与上下游可用性。
   *
   * <p>存在的理由与既有 warehouse 模块一致：把「哪些输入真有依据」显式报出来，
   * 而不是让调用方从 404 或空数组里猜。
   */
  public AssemblyCapabilities capabilities() {
    return new AssemblyCapabilities(
        REF_TYPE,
        true,
        true,
        "BOM 属 catalog 3.5.2（V25/V26）、加工单属 trade 6.4.1.1（V97/V98），都不是本服务的表。"
            + "本服务不 join、不建外键：bomId / bomVersionId / bomComponentId 只作引用键追溯，"
            + "组装要用的组件明细在自己的 assembly_order_component 上落成冻结快照 —— "
            + "这是跨服务快照的常规做法，也满足「每张事实表只有一个写入所有者」。",
        "虚拟套装（catalog bundle）结构上进不来：它不占 SKU 行（V25 原文「bundle 没有任何 sku "
            + "外键 —— 虚拟组合不占 SKU 行」），而本模块的成品 SKU 必须落在本服务 sku_ref 里。"
            + "虚拟套装不持有库存，出库时由 09 订单直接展开组件扣减（蓝图 8.4.1.1 关系段加粗句）。",
        List.of(
            new AssemblyCapabilities.UpstreamModule(
                "3.5.2", "BOM 版本管理与展开", "catalog", true,
                "按 bomId / bomVersionId 引用键追溯；组件明细由调用方按规则 R7 的结果冻结传入，"
                    + "本服务不重算、不取整、不跨库回查 BOM。"),
            new AssemblyCapabilities.UpstreamModule(
                "6.4.1.1", "加工单", "trade", true,
                "可选上游：sourceSystem=TRADE_PROCESSING_ORDER 时用 referenceId 作追溯引用键"
                    + "（跨服务无外键）。也支持 sourceSystem=DIRECT 由仓库现场直接发起组装。")),
        List.of(
            new AssemblyCapabilities.DownstreamModule(
                "7", "库存", "warehouse", true,
                "组件出库与成品入库复用 V1 既有 inventory_transaction / inventory_entry，"
                    + "本服务是这两张表在这条路径上的唯一写入者。"),
            new AssemblyCapabilities.DownstreamModule(
                "8.4.2.1", "拆解单", "warehouse", false,
                "同组兄弟模块未开发：成品回拆组件（消耗成品库存、产生组件库存）尚无单据模型。"),
            new AssemblyCapabilities.DownstreamModule(
                "8.4.1.1 现场作业", "拣货 / 复核 / 上架", "warehouse", false,
                "组装单只到「组件出库 + 成品入库」为止；现场任务流未建模。")),
        "I1：状态 COMPLETED 时，组件出库**分录**之和 = 按 BOM 展开的应领量之和；"
            + "I2：状态 COMPLETED 时，成品入库量 = 组装数量。"
            + "两条不变量落在 V103 的行级 CHECK + 合计回写触发器 + 回读账本分录的守卫触发器上，"
            + "绕过服务层直连 SQL 也绕不过。",
        "组件出库与成品入库在**同一个本地事务**里完成，任一环节失败整体回滚；"
            + "一次组装 = 一条 inventory_transaction（ref_type=ASSEMBLY_ORDER，ref_id=组装单 ID）"
            + " + N 条组件负向分录 + 1 条成品正向分录。"
            + "UNIQUE(tenant_id,ref_type,ref_id) 顺带给同一张组装单幂等。");
  }

  // ================================================================ 建单

  /**
   * 建组装单（DRAFT）。
   *
   * <p>建单只落单据与组件快照，**不动库存**：库存动作在 {@link #issue} 里。
   * 两者分开是为了让「先备料、后组装」这类现场节奏成立，也为了让一笔没执行的组装单
   * 可以被 {@link #cancel} 作废而不留任何账。
   */
  @Transactional
  public AssemblyOrderCreated create(AssemblyOrderCreateInput input, UUID actorId) {
    if (!SOURCES.contains(input.sourceSystem())) {
      throw bad("上游来源只能是 " + SOURCES + " 之一，收到的是 " + input.sourceSystem());
    }
    // DB 的 CHECK 也钉了这一条，但服务层先说人话：DIRECT 带上游引用键，
    // 「这张组装单的上游是谁」就有两个答案（其中一个是空气）。
    if (SOURCE_DIRECT.equals(input.sourceSystem()) && input.referenceId() != null) {
      throw bad("sourceSystem=DIRECT（仓库现场直接发起）不该带 referenceId：没有上游单据可引用");
    }
    if (SOURCE_PROCESSING_ORDER.equals(input.sourceSystem()) && input.referenceId() == null) {
      throw bad(
          "sourceSystem=TRADE_PROCESSING_ORDER 必须给 referenceId（6.4.1.1 加工单的逻辑 ID）："
              + "跨服务不建外键，但这张组装单是凭它追溯到加工单的");
    }

    requireWarehouse(input.warehouseId());
    requireLegalEntity(input.legalEntityId());
    // 成品 SKU 必须在 sku_ref 登记：虚拟套装在这里就被挡掉（它没有 SKU 行）。
    String productCode = requireSku(input.productSkuId(), "成品 SKU");
    BigDecimal assemblyQuantity = stockExact(qty(input.assemblyQuantity(), "组装数量"), "组装数量");

    UUID outboundQuality = resolveQuality(input.outboundQualityStatusId(), "组件出库质量状态");
    UUID inboundQuality = resolveQuality(input.inboundQualityStatusId(), "成品入库质量状态");
    requireLocation(input.warehouseId(), input.outboundLocationId(), "组件出库库位");
    requireLocation(input.warehouseId(), input.inboundLocationId(), "成品入库库位");

    List<AssemblyComponentInput> components = input.components();
    var seen = new java.util.HashSet<UUID>();
    for (AssemblyComponentInput c : components) {
      if (!seen.add(c.skuId())) {
        throw bad(
            "组件 SKU " + c.skuId() + " 在组装单里出现了两次：同一组件只能有一行，"
                + "两行时「这个组件要几件」无法回答，账上也会出现两条同向分录");
      }
      requireSku(c.skuId(), "组件 SKU");
      stockExact(qty(c.requiredQuantity(), "组件应领量"), "组件 " + c.skuId() + " 的应领量");
      if (c.unitQuantity() != null && c.unitQuantity().signum() <= 0) {
        throw bad("单位用量必须大于 0（只作追溯展示，但 0 或负数会让人以为「不用这个料」）");
      }
      if (c.scrapRate() != null
          && (c.scrapRate().signum() < 0 || c.scrapRate().compareTo(new BigDecimal("100")) > 0)) {
        throw bad("损耗率是百分数，取值 [0, 100]（5 表示 5%），收到的是 " + c.scrapRate());
      }
    }

    UUID orderId = UUID.randomUUID();
    // 单号取序列：同一租户内可排序、可口述，且不会撞（V103 UNIQUE(tenant_id,order_code) 兜底）。
    String orderCode = String.format("ASM-%06d", nextCode());
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "insert into assembly_order(id,tenant_id,order_code,product_sku_id,product_sku_code,"
            + "bom_id,bom_code,bom_version_id,bom_version_no,source_system,reference_id,reference_no,"
            + "warehouse_id,legal_entity_id,assembly_quantity,status,outbound_location_id,"
            + "outbound_quality_status_id,inbound_location_id,inbound_quality_status_id,"
            + "outbound_lot_id,inbound_lot_id,created_by,notes)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?,?,?,?,?,?)",
        orderId,
        currentTenant(),
        orderCode,
        input.productSkuId(),
        productCode,
        input.bomId(),
        nz(input.bomCode()),
        input.bomVersionId(),
        nz(input.bomVersionNo()),
        input.sourceSystem(),
        input.referenceId(),
        nz(input.referenceNo()),
        input.warehouseId(),
        input.legalEntityId(),
        assemblyQuantity,
        input.outboundLocationId(),
        outboundQuality,
        input.inboundLocationId(),
        inboundQuality,
        input.outboundLotId(),
        input.inboundLotId(),
        actorId,
        nz(input.notes()));

    for (AssemblyComponentInput c : components) {
      sql.execute(
          "insert into assembly_order_component(id,tenant_id,order_id,sku_id,sku_code,"
              + "bom_component_id,unit_quantity,scrap_rate,required_quantity,role,note)"
              + " values(?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          currentTenant(),
          orderId,
          c.skuId(),
          skuCode(c.skuId()),
          c.bomComponentId(),
          c.unitQuantity() == null
              ? null
              : c.unitQuantity().setScale(QTY_SCALE, RoundingMode.HALF_UP),
          c.scrapRate() == null ? new BigDecimal("0.0000") : c.scrapRate(),
          qty(c.requiredQuantity(), "组件应领量"),
          nz(c.role()),
          nz(c.note()));
    }

    // 合计列的唯一写入者是 V103 的 refresh_assembly_order_totals() 触发器，
    // 所以这里**读回来**而不是自己算 —— 服务层算的数与账上的数可能差一位。
    Record totals =
        sql.fetchOne(
            "select required_component_quantity, issued_component_quantity"
                + " from assembly_order where id = ?",
            orderId);

    return new AssemblyOrderCreated(
        orderId,
        orderCode,
        "DRAFT",
        input.productSkuId(),
        assemblyQuantity,
        decimal(totals, "required_component_quantity"),
        components.size(),
        now.toString(),
        "组装单已建（草稿），**尚未动库存**：组件出库与成品入库在执行组装时才发生，"
            + "两者在同一个本地事务内完成。"
            + "组件明细是 BOM 的冻结快照（catalog 3.5.2 规则 R7 的结果），本服务不重算、不跨库回查 BOM。");
  }

  // ================================================================ 执行组装（核心）

  /**
   * 执行组装：组件出库 + 成品入库，**一个本地事务**。
   *
   * <p>顺序刻意是「先出料、后入货」：任何一个组件不足就抛异常回滚，此时成品**还没有**入账，
   * 账上不会出现「凭空多出来的成品」。反过来先入货再出料的话，出料失败时货已入库，
   * 账上凭空多出成品。
   *
   * <p>幂等锚点：{@code UNIQUE(tenant_id,ref_type,ref_id)}。同一张组装单重复投递会撞唯一约束，
   * 而先被 {@code select ... for update} 锁住的那次已经把状态推进到 COMPLETED，
   * 重复请求在这里就被挡成 409。
   */
  @Transactional
  public AssemblyOrderIssued issue(UUID orderId, AssemblyOrderIssueInput input, UUID actorId) {
    // 请求体是可选的（HTTP 侧没有 body 也是一个合法请求），所以这里不能直接 input.notes()。
    String noteOverride = input == null ? null : input.notes();
    Record order = lockDraft(orderId);
    UUID warehouseId = order.get("warehouse_id", UUID.class);
    UUID legalEntityId = order.get("legal_entity_id", UUID.class);
    UUID productSkuId = order.get("product_sku_id", UUID.class);
    BigDecimal assemblyQuantity = stock(order.get("assembly_quantity", BigDecimal.class));

    OffsetDateTime at = OffsetDateTime.now();
    UUID transactionId = UUID.randomUUID();
    sql.execute(
        "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,"
            + "notes) values(?,?,?,?,?,?::timestamptz,?,?)",
        transactionId,
        currentTenant(),
        "ADJUSTMENT",
        REF_TYPE,
        orderId,
        at,
        actorId,
        noteOverride == null || noteOverride.isBlank()
            ? nz(order.get("notes", String.class))
            : nz(noteOverride));

    List<Record> lines =
        sql.fetch(
            "select id,sku_id,sku_code,required_quantity from assembly_order_component"
                + " where order_id = ? order by sku_code, id",
            orderId);

    // ---- 第一步：组件出库（负向分录）。任何一条失败都整体回滚。
    var issued = new ArrayList<AssemblyComponentIssued>();
    UUID locationId = order.get("outbound_location_id", UUID.class);
    UUID qualityId = order.get("outbound_quality_status_id", UUID.class);
    UUID lotId = order.get("outbound_lot_id", UUID.class);
    for (Record line : lines) {
      UUID skuId = line.get("sku_id", UUID.class);
      BigDecimal want =
          stockExact(decimal(line, "required_quantity"), "组件应领量");
      UUID balance = balanceId(skuId, warehouseId, legalEntityId, qualityId);
      postOutbound(balance, locationId, lotId, want, transactionId, at);
      // 实发量落回明细：V103 的行级 CHECK 会核对它「要么 0、要么恰好等于应领量」，
      // refresh 触发器再把合计回写单头 —— 应用层自己写不出一个对不上的数。
      sql.execute(
          "update assembly_order_component set issued_quantity=?,balance_id=?,location_id=?,"
              + "lot_id=?,updated_at=now() where id=?",
          want, balance, locationId, lotId, line.get("id", UUID.class));
      issued.add(
          new AssemblyComponentIssued(
              skuId,
              line.get("sku_code", String.class),
              decimal(line, "required_quantity"),
              want,
              balance,
              locationId,
              lotId));
    }

    // ---- 第二步：成品入库（正向分录）。与上面的出库同属这一个事务。
    UUID productBalance = balanceId(productSkuId, warehouseId, legalEntityId,
        order.get("inbound_quality_status_id", UUID.class));
    postInbound(
        productBalance,
        order.get("inbound_location_id", UUID.class),
        order.get("inbound_lot_id", UUID.class),
        assemblyQuantity,
        transactionId,
        at);

    // ---- 第三步：推进单据到 COMPLETED。守卫触发器在这里回读账本分录核对 I1 / I2，
    // 任何一条对不上就整体回滚 —— 这是「不变量在数据库层」的落点。
    sql.execute(
        "update assembly_order set status='COMPLETED',inventory_transaction_id=?,"
            + "inbound_product_quantity=?,issued_at=?::timestamptz,issued_by=?,version=version+1,"
            + "updated_at=now() where id=?",
        transactionId,
        assemblyQuantity,
        at,
        actorId,
        orderId);

    List<AssemblyOrderEntry> entries = entriesOf(transactionId);
    return new AssemblyOrderIssued(
        orderId,
        order.get("order_code", String.class),
        transactionId,
        at.toString(),
        productSkuId,
        assemblyQuantity,
        sumRequired(issued),
        sumIssued(issued),
        assemblyQuantity,
        issued,
        entries,
        entries.size(),
        "组件出库与成品入库已在 warehouse 的同一个本地事务内完成（分录 " + transactionId
            + "，ref_type=" + REF_TYPE + "）：任一环节失败都会整体回滚，"
            + "不会出现「组件扣了成品没进」或反之。"
            + "不变量已在数据库层核对：组件出库分录之和 = 按 BOM 展开的应领量之和（I1）、"
            + "成品入库量 = 组装数量（I2）。");
  }

  // ================================================================ 取消

  /**
   * 取消组装单（只有 DRAFT 可取消）。
   *
   * <p>取消**不动库存**：草稿本来就没发过料。要取消已完成的组装单是另一回事 ——
   * 那需要把已出的料退回、已入的货取出，是 8.4.2.1 拆解单的职责，不在本模块。
   */
  @Transactional
  public AssemblyOrderRow cancel(UUID orderId, AssemblyOrderCancelInput input, UUID actorId) {
    Record order = lockDraft(orderId);
    sql.execute(
        "update assembly_order set status='CANCELLED',cancelled_at=?::timestamptz,cancelled_by=?,"
            + "cancel_reason=?,version=version+1,updated_at=now() where id=?",
        OffsetDateTime.now(), actorId, nz(input.reason()), orderId);
    return detail(orderId).order();
  }

  // ================================================================ 查询

  /**
   * 组装单列表（真分页 + 真筛选 + 排序白名单 + 汇总）。
   *
   * <p>汇总与件数用**标量子查询**而不是 join 展开：明细 join 上来会让「单头一行 × N 条组件」
   * 相乘，于是 {@code sum(assembly_quantity)} 被放大 N 倍 —— 聚合数字对不上是这类页面最常见的
   * bug，而且静态检查全绿（与 WarehouseDetailService 同一纪律）。
   */
  public AssemblyOrderPage page(
      String status,
      UUID productSkuId,
      UUID warehouseId,
      String sourceSystem,
      UUID referenceId,
      String keyword,
      String sort,
      int page,
      int size) {
    int safePage = Math.max(0, page);
    int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    String sortKey = sort == null ? "CREATED_AT" : sort.trim().toUpperCase(java.util.Locale.ROOT);
    if (!SORTS.contains(sortKey)) {
      throw new IllegalArgumentException("不支持的排序键：" + sort + "（可选 " + SORTS + "）");
    }
    if (status != null && !status.isBlank() && !STATUSES.contains(status.trim().toUpperCase())) {
      throw new IllegalArgumentException("不支持的状态：" + status + "（可选 " + STATUSES + "）");
    }

    var binds = new ArrayList<Object>();
    var where = new StringBuilder();
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      binds.add(status.trim().toUpperCase());
    }
    if (productSkuId != null) {
      where.append(" and product_sku_id = ?");
      binds.add(productSkuId);
    }
    if (warehouseId != null) {
      where.append(" and warehouse_id = ?");
      binds.add(warehouseId);
    }
    if (sourceSystem != null && !sourceSystem.isBlank()) {
      String source = sourceSystem.trim().toUpperCase();
      if (!SOURCES.contains(source)) {
        throw new IllegalArgumentException(
            "不支持的来源：" + sourceSystem + "（可选 " + SOURCES + "）");
      }
      where.append(" and source_system = ?");
      binds.add(source);
    }
    if (referenceId != null) {
      where.append(" and reference_id = ?");
      binds.add(referenceId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(
          " and (order_code ilike ? or product_sku_code ilike ? or bom_code ilike ?"
              + " or bom_version_no ilike ? or reference_no ilike ?)");
      String pattern = "%" + keyword.trim().replace("%", "\\%").replace("_", "\\_") + "%";
      for (int i = 0; i < 5; i++) {
        binds.add(pattern);
      }
    }
    String clause = where.toString();

    Record summary =
        sql.fetchOne(
            "select count(*) as total,"
                + " coalesce(sum(assembly_quantity),0) as total_assembly,"
                + " coalesce(sum(required_component_quantity),0) as total_required,"
                + " coalesce(sum(issued_component_quantity),0) as total_issued,"
                + " count(*) filter (where status='DRAFT') as draft_count,"
                + " count(*) filter (where status='COMPLETED') as completed_count,"
                + " count(*) filter (where status='CANCELLED') as cancelled_count"
                + " from assembly_order where true" + clause,
            binds.toArray());

    List<AssemblyOrderRow> items =
        sql.fetch(
                "select id,order_code,status,product_sku_id,product_sku_code,bom_id,bom_code,"
                    + "bom_version_id,bom_version_no,source_system,reference_id,reference_no,"
                    + "warehouse_id,assembly_quantity,required_component_quantity,"
                    + "issued_component_quantity,inbound_product_quantity,inventory_transaction_id,"
                    + "issued_at,cancelled_at,cancel_reason,created_at,created_by,notes,"
                    + " (select count(*) from assembly_order_component c"
                    + "   where c.tenant_id = assembly_order.tenant_id and c.order_id = assembly_order.id)"
                    + " as component_count"
                    + " from assembly_order where true" + clause + orderBy(sortKey)
                    + " limit ? offset ?",
                append(binds, safeSize, safePage * safeSize).toArray())
            .map(this::row);

    return new AssemblyOrderPage(
        items,
        safePage,
        safeSize,
        longOf(summary, "total"),
        decimal(summary, "total_assembly"),
        decimal(summary, "total_required"),
        decimal(summary, "total_issued"),
        longOf(summary, "draft_count"),
        longOf(summary, "completed_count"),
        longOf(summary, "cancelled_count"),
        sortKey);
  }

  /** 组装单详情（单据 + 组件快照明细 + 账本分录）。 */
  public AssemblyOrderDetail detail(UUID orderId) {
    Record one =
        sql.fetchOne(
            "select id,order_code,status,product_sku_id,product_sku_code,bom_id,bom_code,"
                + "bom_version_id,bom_version_no,source_system,reference_id,reference_no,"
                + "warehouse_id,assembly_quantity,required_component_quantity,"
                + "issued_component_quantity,inbound_product_quantity,inventory_transaction_id,"
                + "issued_at,cancelled_at,cancel_reason,created_at,created_by,notes,"
                + " (select count(*) from assembly_order_component c"
                + "   where c.tenant_id = assembly_order.tenant_id and c.order_id = assembly_order.id)"
                + " as component_count"
                + " from assembly_order where id = ?",
            orderId);
    if (one == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "组装单 " + orderId + " 不存在或无权查看");
    }
    List<AssemblyComponentRow> components =
        sql.fetch(
                "select id,sku_id,sku_code,bom_component_id,unit_quantity,scrap_rate,"
                    + "required_quantity,issued_quantity,balance_id,location_id,lot_id,role,note"
                    + " from assembly_order_component where order_id = ? order by sku_code, id",
                orderId)
            .map(
                c ->
                    new AssemblyComponentRow(
                        c.get("id", UUID.class),
                        c.get("sku_id", UUID.class),
                        c.get("sku_code", String.class),
                        c.get("bom_component_id", UUID.class),
                        c.get("unit_quantity", BigDecimal.class),
                        c.get("scrap_rate", BigDecimal.class),
                        c.get("required_quantity", BigDecimal.class),
                        c.get("issued_quantity", BigDecimal.class),
                        c.get("balance_id", UUID.class),
                        c.get("location_id", UUID.class),
                        c.get("lot_id", UUID.class),
                        c.get("role", String.class),
                        c.get("note", String.class)));
    UUID transactionId = one.get("inventory_transaction_id", UUID.class);
    return new AssemblyOrderDetail(
        row(one), components, transactionId == null ? List.of() : entriesOf(transactionId));
  }

  // ================================================================ 库存写入

  /**
   * 出库：减余额 + 同步减库位明细（V2 守恒触发器要求两处同动）。
   *
   * <p>先查库位上的实际在手量并给出可读报错：V1 / V2 的 {@code CHECK(available>=0)} 确实会挡住
   * 扣成负数，但那条报错只说「违反检查约束」，完全不指到是哪批货、在哪个库位、差多少 ——
   * 而组装领料失败正是「组件不够」这个业务事实。
   */
  private void postOutbound(
      UUID balanceId,
      UUID locationId,
      UUID lotId,
      BigDecimal want,
      UUID transactionId,
      OffsetDateTime at) {
    BigDecimal qty = stock(want);
    Record bucket =
        sql.fetchOne(
            "select id,on_hand,reserved from stock_bucket where balance_id=? and location_id=?"
                + " and lot_id is not distinct from ?",
            balanceId, locationId, lotId);
    if (bucket == null) {
      throw bad(
          "出库失败：库位上没有这个组件的库存行。组装领料必须从真实存在的库位发出，"
              + "不能凭空扣一个 SKU 的库存");
    }
    BigDecimal onHand = stock(decimal(bucket, "on_hand"));
    BigDecimal reserved = stock(decimal(bucket, "reserved"));
    if (onHand.compareTo(qty) < 0) {
      throw bad(
          "出库失败：库位上只有 " + onHand + "，本次组装要发 " + qty
              + (reserved.signum() > 0 ? "（其中 " + reserved + " 已被销售预占）" : "")
              + "。组件不足会让组装做不完 —— 请先补料或调整本单的组装数量");
    }
    sql.execute(
        "update stock_bucket set on_hand=on_hand-?,"
            + " last_movement_at=greatest(?::timestamptz,last_movement_at"
            + " + interval '1 microsecond') where id=?",
        qty, at, bucket.get("id", UUID.class));
    sql.execute(
        "update inventory_balance set on_hand=on_hand-?,version=version+1,updated_at=now()"
            + " where id=?",
        qty, balanceId);
    sql.execute(
        "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
            + "reserved_delta) values(?,?,?,?,?,0)",
        UUID.randomUUID(), currentTenant(), transactionId, balanceId, qty.negate());
  }

  /** 入库：加余额 + 同步加库位明细（V2 守恒触发器要求两处同动）。 */
  private void postInbound(
      UUID balanceId, UUID locationId, UUID lotId, BigDecimal qty, UUID transactionId,
      OffsetDateTime at) {
    Record existing =
        sql.fetchOne(
            "select id from stock_bucket where balance_id=? and location_id=?"
                + " and lot_id is not distinct from ?",
            balanceId, locationId, lotId);
    if (existing != null) {
      sql.execute(
          "update stock_bucket set on_hand=on_hand+?,"
              + " last_movement_at=greatest(?::timestamptz,last_movement_at"
              + " + interval '1 microsecond') where id=?",
          qty, at, existing.get("id", UUID.class));
    } else {
      Record bal = sql.fetchOne("select * from inventory_balance where id=?", balanceId);
      sql.execute(
          "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
              + "lot_id,quality_status_id,balance_id,on_hand,reserved,last_movement_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,0,?::timestamptz)",
          UUID.randomUUID(),
          bal.get("tenant_id", UUID.class),
          bal.get("legal_entity_id", UUID.class),
          bal.get("sku_id", UUID.class),
          bal.get("warehouse_id", UUID.class),
          locationId,
          lotId,
          bal.get("quality_status_id", UUID.class),
          balanceId,
          qty,
          at);
    }
    sql.execute(
        "update inventory_balance set on_hand=on_hand+?,version=version+1,updated_at=now()"
            + " where id=?",
        qty, balanceId);
    sql.execute(
        "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
            + "reserved_delta) values(?,?,?,?,?,0)",
        UUID.randomUUID(), currentTenant(), transactionId, balanceId, qty);
  }

  private List<AssemblyOrderEntry> entriesOf(UUID transactionId) {
    return sql.fetch(
            "select e.id,e.balance_id,b.sku_id,e.on_hand_delta,e.reserved_delta"
                + " from inventory_entry e join inventory_balance b"
                + " on b.tenant_id = e.tenant_id and b.id = e.balance_id"
                + " where e.transaction_id = ? order by e.on_hand_delta, e.id",
            transactionId)
        .map(
            e ->
                new AssemblyOrderEntry(
                    e.get("id", UUID.class),
                    e.get("balance_id", UUID.class),
                    e.get("sku_id", UUID.class),
                    stock(decimal(e, "on_hand_delta")),
                    stock(decimal(e, "reserved_delta")),
                    decimal(e, "on_hand_delta").signum() < 0 ? "COMPONENT_OUT" : "PRODUCT_IN"));
  }

  // ================================================================ 主数据校验

  /** 取一张处于 DRAFT 的组装单并锁住它（防并发重复执行）。 */
  private Record lockDraft(UUID orderId) {
    Record order = sql.fetchOne("select * from assembly_order where id = ? for update", orderId);
    if (order == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "组装单 " + orderId + " 不存在或无权操作");
    }
    String status = order.get("status", String.class);
    if (!"DRAFT".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "组装单 " + order.get("order_code", String.class) + " 当前是 " + status
              + "，只有草稿可以执行或取消：已完成组装单的组件已经出库、成品已经入库，"
              + "重复执行会重复扣料重复入货");
    }
    return order;
  }

  private void requireWarehouse(UUID warehouseId) {
    if (sql.fetchOne("select 1 from warehouse where id = ?", warehouseId) == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "仓库 " + warehouseId + " 不存在或无权使用");
    }
  }

  private void requireLegalEntity(UUID legalEntityId) {
    if (sql.fetchOne("select 1 from legal_entity_ref where id = ?", legalEntityId) == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "货权法人 " + legalEntityId + " 不存在或无权使用");
    }
  }

  /** SKU 必须在 sku_ref 登记：这是虚拟套装被挡在门外的**唯一**判据。 */
  private String requireSku(UUID skuId, String label) {
    Record row = sql.fetchOne("select code from sku_ref where id = ?", skuId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          label + " " + skuId + " 没有在本服务登记（sku_ref）：库存分录要落到真实存在的 SKU 上。"
              + "如果它是虚拟套装，那本来就该走 09 订单展开扣组件，而不是组装单"
              + "（虚拟套装不占 SKU 行、也不持有库存）");
    }
    return row.get("code", String.class);
  }

  private String skuCode(UUID skuId) {
    Record row = sql.fetchOne("select code from sku_ref where id = ?", skuId);
    return row == null ? "" : row.get("code", String.class);
  }

  /** 质量状态：不传则取本租户唯一启用的可售状态（组装成品默认入可售）。 */
  private UUID resolveQuality(UUID qualityStatusId, String label) {
    if (qualityStatusId == null) {
      Record row =
          sql.fetchOne(
              "select id from inventory_quality_status where role = 'SELLABLE' and enabled"
                  + " order by code limit 1");
      if (row == null) {
        throw bad(
            "本租户没有启用的可售质量状态，无法确定「"
                + label
                + "。请先在质量状态字典里启用一个 role=SELLABLE 的状态");
      }
      return row.get("id", UUID.class);
    }
    Record row =
        sql.fetchOne(
            "select id,code,enabled from inventory_quality_status where id = ?", qualityStatusId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, label + " " + qualityStatusId + " 不存在或无权使用");
    }
    if (!Boolean.TRUE.equals(row.get("enabled", Boolean.class))) {
      throw bad(label + "「" + row.get("code", String.class) + "」已停用，不能作为组装的库存去向");
    }
    return qualityStatusId;
  }

  private void requireLocation(UUID warehouseId, UUID locationId, String label) {
    if (sql.fetchOne(
            "select 1 from warehouse_location where id = ? and warehouse_id = ?"
                + " and status = 'ACTIVE'",
            locationId, warehouseId)
        == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, label + " " + locationId + " 不存在、不属于该仓或已停用");
    }
  }

  /** 取（或建）一条余额行。跨租户塞进来的仓/法人/质量状态会被 UNIQUE 或 RLS 挡住。 */
  private UUID balanceId(UUID skuId, UUID warehouseId, UUID legalEntityId, UUID qualityStatusId) {
    var found =
        sql.fetchOne(
            "select id from inventory_balance where sku_id=? and warehouse_id=?"
                + " and legal_entity_id=? and quality_status_id=?",
            skuId, warehouseId, legalEntityId, qualityStatusId);
    if (found != null) {
      return found.get("id", UUID.class);
    }
    var id = UUID.randomUUID();
    sql.execute(
        "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
            + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,0,0)",
        id, currentTenant(), skuId, warehouseId, legalEntityId, qualityStatusId);
    return id;
  }

  // ================================================================ 工具

  private AssemblyOrderRow row(Record r) {
    return new AssemblyOrderRow(
        r.get("id", UUID.class),
        r.get("order_code", String.class),
        r.get("status", String.class),
        r.get("product_sku_id", UUID.class),
        r.get("product_sku_code", String.class),
        r.get("bom_id", UUID.class),
        r.get("bom_code", String.class),
        r.get("bom_version_id", UUID.class),
        r.get("bom_version_no", String.class),
        r.get("source_system", String.class),
        r.get("reference_id", UUID.class),
        r.get("reference_no", String.class),
        r.get("warehouse_id", UUID.class),
        decimal(r, "assembly_quantity"),
        decimal(r, "required_component_quantity"),
        decimal(r, "issued_component_quantity"),
        decimal(r, "inbound_product_quantity"),
        intOf(r, "component_count"),
        r.get("inventory_transaction_id", UUID.class),
        timestamp(r, "issued_at"),
        timestamp(r, "cancelled_at"),
        r.get("cancel_reason", String.class),
        timestamp(r, "created_at"),
        r.get("created_by", UUID.class),
        r.get("notes", String.class));
  }

  private String orderBy(String key) {
    // 一律以 order_code 收尾：排序键相同时必须有确定次序，否则同一页两次请求可能给出不同顺序。
    return switch (key) {
      case "ORDER_CODE" -> " order by order_code, id";
      case "ASSEMBLY_QUANTITY" -> " order by assembly_quantity desc, order_code";
      case "STATUS" -> " order by status, order_code";
      default -> " order by created_at desc, order_code";
    };
  }

  private long nextCode() {
    Long value = sql.fetchOne("select nextval('assembly_order_code_seq')").get(0, Long.class);
    return value == null ? 1L : value;
  }

  private static BigDecimal sumRequired(List<AssemblyComponentIssued> items) {
    BigDecimal total = BigDecimal.ZERO;
    for (AssemblyComponentIssued item : items) {
      total = total.add(item.requiredQuantity());
    }
    return total;
  }

  private static BigDecimal sumIssued(List<AssemblyComponentIssued> items) {
    BigDecimal total = BigDecimal.ZERO;
    for (AssemblyComponentIssued item : items) {
      total = total.add(item.issuedQuantity());
    }
    return total;
  }

  private static String timestamp(Record r, String field) {
    OffsetDateTime value = r.get(field, OffsetDateTime.class);
    return value == null ? null : value.toString();
  }

  /**
   * 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。
   *
   * <p>表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义
   * GUC <b>复位成空串</b>而不是「回到未设置」，直接 cast 会报 uuid 语法错
   * （与 warehouse V1 / trade V92 同一句写法）。
   */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  /** 读数量。纯文本 SQL 里的表达式列 jOOQ 拿不到列类型（7.3.1.1 同一个坑）。 */
  private static BigDecimal decimal(Record r, String field) {
    Object value = r.get(field);
    if (value == null) {
      return BigDecimal.ZERO;
    }
    return value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
  }

  private static long longOf(Record r, String field) {
    Long value = r.get(field, Long.class);
    return value == null ? 0L : value;
  }

  private static int intOf(Record r, String field) {
    Long value = r.get(field, Long.class);
    return value == null ? 0 : value.intValue();
  }

  /** 数量刻度：与 V103 的 NUMERIC(19,6) 及 catalog 规则 R7 对齐。 */
  private static BigDecimal qty(BigDecimal value, String label) {
    if (value == null || value.signum() <= 0) {
      throw bad(label + "必须大于 0");
    }
    if (value.scale() > QTY_SCALE) {
      throw bad(label + "最多 6 位小数（与 catalog 规则 R7 的口径一致）");
    }
    return value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 要真正过账的量必须能被<b>库存刻度（4 位小数）精确表示</b>，否则显式拒绝。
   *
   * <p>为什么不静默取整：向下取整等于凭空吞掉材料（账上少领、实体多丢），向上取整等于凭空
   * 多领材料。两种都破坏账实相符，而且都恰好能让「组件出库 = 应领量」这条不变量**看起来成立**
   * —— 那才是最糟的：数字对上了，物料对不上。所以宁可拒绝并说清楚是哪一行。
   */
  private static BigDecimal stockExact(BigDecimal value, String label) {
    try {
      return value.setScale(STOCK_SCALE, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw bad(
          label + " " + value + " 有超过 4 位小数，无法在库存刻度上精确表示："
              + "向下取整等于凭空吞掉材料、向上取整等于凭空多领，两种都会让"
              + "「组件出库 = 应领量」这条不变量看起来成立而物料对不上。"
              + "请调整组装数量或 BOM 的用量，让结果能被 4 位小数精确表示");
    }
  }

  /** 库存刻度读数：与 V1 的 numeric(19,4) 对齐。 */
  private static BigDecimal stock(BigDecimal value) {
    return value == null
        ? BigDecimal.ZERO.setScale(STOCK_SCALE)
        : value.setScale(STOCK_SCALE, RoundingMode.HALF_UP);
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    var all = new ArrayList<>(binds);
    all.addAll(Arrays.asList(more));
    return all;
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}