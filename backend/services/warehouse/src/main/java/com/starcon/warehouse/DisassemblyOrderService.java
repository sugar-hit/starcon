package com.starcon.warehouse;

import static com.starcon.warehouse.DisassemblyOrderModels.*;

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
 * 8.4.2.1 拆解单：成品拆解回组件的仓库侧单据与库存动作。
 *
 * <p>蓝图 08 卷 8.4.2.1：「成品拆解回组件：消耗成品库存、产生组件库存（用于退换货拆解、样品回收）」
 * 输出「拆解单 + <b>反向</b>库存分录」。所以本类是 8.4.1.1 组装单
 * （{@link AssemblyOrderService}）的**逆操作**，但不是它的镜像：
 *
 * <ul>
 *   <li>组装：N 条组件<b>负向</b>分录 + 1 条成品<b>正向</b>分录；
 *       拆解：1 条成品<b>负向</b>分录 + M 条组件<b>正向</b>分录。
 *       两者在账上必须<b>成对且可对账</b>，所以共用同一套 inventory_transaction / inventory_entry，
 *       只是 ref_type 不同（ASSEMBLY_ORDER vs DISASSEMBLY_ORDER）。<br>
 *   <li>组装多一维<b>损耗率</b>（发料会损耗）；拆解多一维<b>回收率</b>（退件可能缺件、损坏）。
 *       这一维是拆解独有的，没有它就无法解释「BOM 上有 10 个螺丝，拆出来只有 8 个」。<br>
 *   <li>组装可以把成品<b>默认入可售</b>；拆解<b>绝不能</b>默认回可售 —— 蓝图点名。
 *       这不是风格差异，是业务事实：退回的货凭什么能直接卖？
 * </ul>
 *
 * <p><b>同一事务</b>：{@link #execute} 标注 {@code @Transactional}，成品出库与组件入库要么都成、
 * 要么都回滚 —— 不允许出现「成品扣了组件没进」或反之的半截账（与 8.4.1.1 同一原则）。
 *
 * <p><b>跨服务边界</b>：BOM 在 catalog 3.5.2、售后退件单在 11 售后，都不是本服务的表。
 * 本服务<b>不 join、不建外键</b>，而是把组件明细在自己的 {@code disassembly_order_component}
 * 上落成反向冻结快照（单位用量 / 回收率 / 应回量），并用 {@code bomId / bomVersionId /
 * bomComponentId} 作引用键追溯。8.4.1.1 的组装单虽在本服务内，同样用逻辑引用键 +
 * 服务层状态校验（外键表达不了「上游必须已完成」），不建外键。
 */
@Service
public class DisassemblyOrderService {

  private static final int MAX_PAGE_SIZE = 200;

  /** 排序白名单。与 WarehouseDetailService 同一纪律：不接受任意排序键。 */
  private static final List<String> SORTS =
      List.of("CREATED_AT", "ORDER_CODE", "DISASSEMBLY_QUANTITY", "STATUS");

  /** 状态词表，与 V108 的 CHECK 逐字一致。 */
  private static final List<String> STATUSES = List.of("DRAFT", "COMPLETED", "CANCELLED");

  /** 上游来源词表，与 V108 的 CHECK 逐字一致。 */
  private static final List<String> SOURCES =
      List.of(SOURCE_ASSEMBLY_ORDER, SOURCE_AFTERSALES_RETURN, SOURCE_DIRECT);

  private final DSLContext sql;

  public DisassemblyOrderService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：跨服务边界、去向必填这条硬约束与上下游可用性。
   *
   * <p>存在的理由与既有 warehouse 模块一致：把「哪些输入真有依据」「哪条不能填默认值」
   * 显式报出来，而不是让调用方从 400 或空数组里猜。
   */
  public DisassemblyCapabilities capabilities() {
    return new DisassemblyCapabilities(
        REF_TYPE,
        true,
        true,
        "BOM 属 catalog 3.5.2（V25/V26），售后退件单属 11 售后，都不是本服务的表。"
            + "本服务不 join、不建外键：bomId / bomVersionId / bomComponentId 只作引用键追溯，"
            + "拆解要用的组件明细在自己的 disassembly_order_component 上落成反向冻结快照 —— "
            + "这是跨服务快照的常规做法，也满足「每张事实表只有一个写入所有者」。",
        "8.4.1.1 组装单虽在本服务内，但引用关系刻意不建外键：外键表达不了「上游组装单必须已完成」。"
            + "sourceSystem=ASSEMBLY_ORDER 时本服务用显式 SQL 校验该组装单存在、属于本租户且已 COMPLETED，"
            + "校验不过就是 404 —— 拆一件没组装过的成品在账上说不通。",
        "组件入库去向（可用/待检/残次）**由接收方决定**，且是**逐行必填、没有默认值**的："
            + "destinationQualityStatusId 不传就是 400，服务层不会替你挑一个 —— "
            + "蓝图明确「不能默认全部回可售」。"
            + "这一条与 8.4.1.1 刻意相反 —— 组装的成品入库默认可售是对的，拆解的组件去向默认可售是错的："
            + "退回的货凭什么能直接卖。取值落在 V1 既有 inventory_quality_status.role："
            + "SELLABLE(可用) / PENDING_INSPECTION(待检) / DEFECTIVE(残次)。",
        List.of(
            new DisassemblyCapabilities.DisassemblyUpstreamModule(
                "8.4.1.1", "组装单", "warehouse", true,
                "sourceSystem=ASSEMBLY_ORDER 时用 referenceId 追溯到那张组装单，"
                    + "并校验它属于本租户且已 COMPLETED。拆解是组装的逆操作："
                    + "那边消耗组件产出成品，这边消耗成品产出组件，两者在账上成对且可对账。"),
            new DisassemblyCapabilities.DisassemblyUpstreamModule(
                "3.5.2", "BOM 版本管理与展开", "catalog", true,
                "按 bomId / bomVersionId 引用键追溯；组件明细由调用方按「单位用量 × 拆解数量 × "
                    + "回收率/100」冻结传入，本服务不重算、不取整、不跨库回查 BOM。")),
        List.of(
            new DisassemblyCapabilities.DisassemblyDownstreamModule(
                "7", "库存", "warehouse", true,
                "成品出库与组件入库复用 V1 既有 inventory_transaction / inventory_entry，"
                    + "本服务是这两张表在这条路径上的唯一写入者。"),
            new DisassemblyCapabilities.DisassemblyDownstreamModule(
                "11", "售后（退件拆解）", "trade", false,
                "退换货拆解的**发起方与接收方**：本模块只到「成品出库 + 组件入库（带去向）」为止。"
                    + "「拆解结果的组件质量状态由接收方决定」—— 判成可用还是残次是售后侧的判定，"
                    + "本模块只负责忠实执行、不替它决定。"),
            new DisassemblyCapabilities.DisassemblyDownstreamModule(
                "8.4.2.1 现场作业", "拆解工位 / 拆解扫码", "warehouse", false,
                "拆解单只到库存动作为止；现场任务流未建模。")),
        "D1：状态 COMPLETED 时，成品出库**分录**绝对值之和 = 拆解数量；"
            + "D2：状态 COMPLETED 时，组件入库**分录**之和 = 按 BOM 反推的应回量之和；"
            + "D3：成品 SKU 不得出现在自己的组件明细里（否则那叫复制不叫拆解）。"
            + "三条不变量落在 V108 的行级 CHECK + 合计回写触发器 + 回读账本分录的守卫触发器上，"
            + "绕过服务层直连 SQL 也绕不过。",
        "成品出库与组件入库在**同一个本地事务**里完成，任一环节失败整体回滚；"
            + "一次拆解 = 一条 inventory_transaction（ref_type=DISASSEMBLY_ORDER，ref_id=拆解单 ID）"
            + " + 1 条成品负向分录 + M 条组件正向分录。"
            + "UNIQUE(tenant_id,ref_type,ref_id) 顺带给同一张拆解单幂等。");
  }

  // ================================================================ 建单

  /**
   * 建拆解单（DRAFT）。
   *
   * <p>建单只落单据与组件快照，<b>不动库存</b>：库存动作在 {@link #execute} 里。
   * 两者分开是为了让「先备工位、后开拆」这类现场节奏成立，也为了让一张没执行的拆解单
   * 可以被 {@link #cancel} 作废而不留任何账。
   */
  @Transactional
  public DisassemblyOrderCreated create(DisassemblyOrderCreateInput input, UUID actorId) {
    if (!SOURCES.contains(input.sourceSystem())) {
      throw bad("上游来源只能是 " + SOURCES + " 之一，收到的是 " + input.sourceSystem());
    }
    // DB 的 CHECK 也钉了这一条，但服务层先说人话：DIRECT 带上游引用键，
    // 「这张拆解单的上游是谁」就有两个答案（其中一个是空气）。
    if (SOURCE_DIRECT.equals(input.sourceSystem()) && input.referenceId() != null) {
      throw bad("sourceSystem=DIRECT（仓库现场直接发起）不该带 referenceId：没有上游单据可引用");
    }
    if (!SOURCE_DIRECT.equals(input.sourceSystem()) && input.referenceId() == null) {
      throw bad(
          "sourceSystem=" + input.sourceSystem() + " 必须给 referenceId："
              + "跨服务不建外键，但这张拆解单是凭它追溯到上游单据的");
    }
    if (SOURCE_ASSEMBLY_ORDER.equals(input.sourceSystem())) {
      requireCompletedAssembly(input.referenceId());
    }

    requireWarehouse(input.warehouseId());
    requireLegalEntity(input.legalEntityId());
    String productCode = requireSku(input.productSkuId(), "成品 SKU");
    BigDecimal quantity = stockExact(qty(input.disassemblyQuantity(), "拆解数量"), "拆解数量");

    UUID outboundQuality = resolveOutboundQuality(input.outboundQualityStatusId());
    requireLocation(input.warehouseId(), input.outboundLocationId(), "成品出库库位");

    List<DisassemblyComponentInput> components = input.components();
    var seen = new java.util.HashSet<UUID>();
    for (DisassemblyComponentInput c : components) {
      if (!seen.add(c.skuId())) {
        throw bad(
            "组件 SKU " + c.skuId() + " 在拆解单里出现了两次：同一组件只能有一行，"
                + "两行时「这个组件能拆出几件」无法回答，账上也会出现两条同向分录");
      }
      if (c.skuId().equals(input.productSkuId())) {
        throw bad(
            "成品 SKU 不能出现在自己的组件明细里：那叫「复制」不叫「拆解」，"
                + "账上会凭空多出同款成品");
      }
      requireSku(c.skuId(), "组件 SKU");
      stockExact(qty(c.expectedQuantity(), "组件应回量"), "组件 " + c.skuId() + " 的应回量");
      if (c.unitQuantity() != null && c.unitQuantity().signum() <= 0) {
        throw bad("单位用量必须大于 0（只作追溯展示，但 0 或负数会让人以为「这个组件拆不出东西」）");
      }
      if (c.recoveryRate() != null
          && (c.recoveryRate().signum() < 0
              || c.recoveryRate().compareTo(new BigDecimal("100")) > 0)) {
        throw bad("回收率是百分数，取值 [0, 100]（95 表示 95%），收到的是 " + c.recoveryRate());
      }
      // 去向必填，且**不提供默认值** —— 见类注释与蓝图加粗句。
      requireDestinationQuality(c.destinationQualityStatusId(), c.skuId());
      requireLocation(input.warehouseId(), c.destinationLocationId(), "组件入库库位（组件 " + c.skuId() + "）");
    }

    UUID orderId = UUID.randomUUID();
    // 单号取序列：同一租户内可排序、可口述，且不会撞（V108 UNIQUE(tenant_id,order_code) 兜底）。
    String orderCode = String.format("DSA-%06d", nextCode());
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "insert into disassembly_order(id,tenant_id,order_code,product_sku_id,product_sku_code,"
            + "bom_id,bom_code,bom_version_id,bom_version_no,source_system,reference_id,reference_no,"
            + "warehouse_id,legal_entity_id,disassembly_quantity,status,outbound_location_id,"
            + "outbound_quality_status_id,outbound_lot_id,created_by,notes)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?,?,?)",
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
        quantity,
        input.outboundLocationId(),
        outboundQuality,
        input.outboundLotId(),
        actorId,
        nz(input.notes()));

    for (DisassemblyComponentInput c : components) {
      sql.execute(
          "insert into disassembly_order_component(id,tenant_id,order_id,sku_id,sku_code,"
              + "bom_component_id,unit_quantity,recovery_rate,expected_quantity,"
              + "destination_quality_status_id,destination_location_id,destination_lot_id,role,note)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          currentTenant(),
          orderId,
          c.skuId(),
          skuCode(c.skuId()),
          c.bomComponentId(),
          c.unitQuantity() == null
              ? null
              : c.unitQuantity().setScale(QTY_SCALE, RoundingMode.HALF_UP),
          c.recoveryRate() == null
              ? new BigDecimal("100.0000")
              : c.recoveryRate().setScale(4, RoundingMode.HALF_UP),
          qty(c.expectedQuantity(), "组件应回量"),
          c.destinationQualityStatusId(),
          c.destinationLocationId(),
          c.destinationLotId(),
          nz(c.role()),
          nz(c.note()));
    }

    // 合计列的唯一写入者是 V108 的 refresh_disassembly_order_totals() 触发器，
    // 所以这里**读回来**而不是自己算 —— 服务层算的数与账上的数可能差一位。
    Record totals =
        sql.fetchOne(
            "select expected_component_quantity from disassembly_order where id = ?", orderId);

    return new DisassemblyOrderCreated(
        orderId,
        orderCode,
        "DRAFT",
        input.productSkuId(),
        quantity,
        decimal(totals, "expected_component_quantity"),
        components.size(),
        now.toString(),
        "拆解单已建（草稿），**尚未动库存**：成品出库与组件入库在执行拆解时才发生，"
            + "两者在同一个本地事务内完成。"
            + "组件明细是 BOM 的反向冻结快照（含回收率），本服务不重算、不跨库回查 BOM。"
            + "每一行都必须显式写明组件去向（可用/待检/残次）—— 拆解的结果质量由接收方判定，"
            + "本模块不替它决定。");
  }

  // ================================================================ 执行拆解（核心）

  /**
   * 执行拆解：成品出库 + 组件入库，<b>一个本地事务</b>。
   *
   * <p>顺序刻意是「先出成品、后入组件」：成品不足就抛异常回滚，此时组件<b>还没有</b>入账，
   * 账上不会出现「凭空多出来的组件」。反过来先入组件再出成品的话，出库失败时货已入账，
   * 账上凭空多出一批来路不明的组件 —— 那正是最危险的一种错账。
   *
   * <p>幂等锚点：{@code UNIQUE(tenant_id,ref_type,ref_id)}。同一张拆解单重复投递会撞唯一约束，
   * 而先被 {@code select ... for update} 锁住的那次已经把状态推进到 COMPLETED，
   * 重复请求在这里就被挡成 409。
   */
  @Transactional
  public DisassemblyOrderExecuted execute(UUID orderId, DisassemblyOrderExecuteInput input,
      UUID actorId) {
    // 请求体是可选的（HTTP 侧没有 body 也是一个合法请求），所以这里不能直接 input.notes()。
    String noteOverride = input == null ? null : input.notes();
    Record order = lockDraft(orderId);
    UUID warehouseId = order.get("warehouse_id", UUID.class);
    UUID legalEntityId = order.get("legal_entity_id", UUID.class);
    UUID productSkuId = order.get("product_sku_id", UUID.class);
    BigDecimal quantity = stock(order.get("disassembly_quantity", BigDecimal.class));

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
            "select id,sku_id,sku_code,expected_quantity,destination_quality_status_id,"
                + "destination_location_id,destination_lot_id"
                + " from disassembly_order_component where order_id = ? order by sku_code, id",
            orderId);

    // ---- 第一步：成品出库（负向分录）。不足就抛异常，后面全部不会发生。
    UUID outboundLocationId = order.get("outbound_location_id", UUID.class);
    UUID outboundQualityId = order.get("outbound_quality_status_id", UUID.class);
    UUID outboundLotId = order.get("outbound_lot_id", UUID.class);
    UUID productBalance =
        balanceId(productSkuId, warehouseId, legalEntityId, outboundQualityId);
    postOutbound(
        productBalance, outboundLocationId, outboundLotId, quantity, transactionId, at);

    // ---- 第二步：组件入库（正向分录，逐行按**它自己的去向**落）。与上面的出库同属这一个事务。
    var recovered = new ArrayList<DisassemblyComponentRecovered>();
    for (Record line : lines) {
      UUID skuId = line.get("sku_id", UUID.class);
      BigDecimal want = stockExact(decimal(line, "expected_quantity"), "组件应回量");
      UUID destinationQualityId = line.get("destination_quality_status_id", UUID.class);
      UUID destinationLocationId = line.get("destination_location_id", UUID.class);
      UUID destinationLotId = line.get("destination_lot_id", UUID.class);
      UUID balance = balanceId(skuId, warehouseId, legalEntityId, destinationQualityId);
      postInbound(balance, destinationLocationId, destinationLotId, want, transactionId, at);
      // 实回量落回明细：V108 的行级 CHECK 会核对它「要么 0、要么恰好等于应回量」，
      // refresh 触发器再把合计回写单头 —— 应用层自己写不出一个对不上的数。
      sql.execute(
          "update disassembly_order_component set recovered_quantity=?,balance_id=?,"
              + "updated_at=now() where id=?",
          want, balance, line.get("id", UUID.class));
      Record quality =
          sql.fetchOne(
              "select code,role from inventory_quality_status where id = ?", destinationQualityId);
      recovered.add(
          new DisassemblyComponentRecovered(
              skuId,
              line.get("sku_code", String.class),
              decimal(line, "expected_quantity"),
              want,
              destinationQualityId,
              quality == null ? "" : quality.get("code", String.class),
              quality == null ? "" : quality.get("role", String.class),
              destinationLocationId,
              destinationLotId,
              balance));
    }

    // ---- 第三步：推进单据到 COMPLETED。守卫触发器在这里回读账本分录核对 D1 / D2 / D3，
    // 任何一条对不上就整体回滚 —— 这是「不变量在数据库层」的落点。
    sql.execute(
        "update disassembly_order set status='COMPLETED',inventory_transaction_id=?,"
            + "outbound_product_quantity=?,executed_at=?::timestamptz,executed_by=?,"
            + "version=version+1,updated_at=now() where id=?",
        transactionId,
        quantity,
        at,
        actorId,
        orderId);

    List<DisassemblyOrderEntry> entries = entriesOf(transactionId);
    return new DisassemblyOrderExecuted(
        orderId,
        order.get("order_code", String.class),
        transactionId,
        at.toString(),
        productSkuId,
        quantity,
        quantity,
        sumExpected(recovered),
        sumRecovered(recovered),
        recovered,
        entries,
        entries.size(),
        "成品出库与组件入库已在 warehouse 的同一个本地事务内完成（分录 " + transactionId
            + "，ref_type=" + REF_TYPE + "）：任一环节失败都会整体回滚，"
            + "不会出现「成品扣了组件没进」或反之。"
            + "不变量已在数据库层核对：成品出库分录 = 拆解数量（D1）、"
            + "组件入库分录之和 = 应回量之和（D2）、成品不出现在自己的组件明细里（D3）。"
            + "每个组件按它**建单时确定**的去向入库 —— 去向不在执行这一步改。");
  }

  // ================================================================ 取消

  /**
   * 取消拆解单（只有 DRAFT 可取消）。
   *
   * <p>取消<b>不动库存</b>：草稿本来就没拆过东西。要取消已完成的拆解单是另一回事 ——
   * 那需要把已出的成品退回、已入的组件取出，那本身就是一张新的拆解单/组装单，不在本模块。
   */
  @Transactional
  public DisassemblyOrderRow cancel(UUID orderId, DisassemblyOrderCancelInput input,
      UUID actorId) {
    Record order = lockDraft(orderId);
    sql.execute(
        "update disassembly_order set status='CANCELLED',cancelled_at=?::timestamptz,"
            + "cancelled_by=?,cancel_reason=?,version=version+1,updated_at=now() where id=?",
        OffsetDateTime.now(), actorId, nz(input.reason()), orderId);
    return detail(orderId).order();
  }

  // ================================================================ 查询

  /**
   * 拆解单列表（真分页 + 真筛选 + 排序白名单 + 汇总 + 去向分布）。
   *
   * <p>汇总与件数用<b>标量子查询</b>而不是 join 展开：明细 join 上来会让「单头一行 × N 条组件」
   * 相乘，于是 {@code sum(disassembly_quantity)} 被放大 N 倍 —— 聚合数字对不上是这类页面最常见的
   * bug，而且静态检查全绿（与 AssemblyOrderService 同一纪律）。去向分布同理，单独一条查询算。
   */
  public DisassemblyOrderPage page(
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
      where.append(" and o.status = ?");
      binds.add(status.trim().toUpperCase());
    }
    if (productSkuId != null) {
      where.append(" and o.product_sku_id = ?");
      binds.add(productSkuId);
    }
    if (warehouseId != null) {
      where.append(" and o.warehouse_id = ?");
      binds.add(warehouseId);
    }
    if (sourceSystem != null && !sourceSystem.isBlank()) {
      String source = sourceSystem.trim().toUpperCase();
      if (!SOURCES.contains(source)) {
        throw new IllegalArgumentException(
            "不支持的来源：" + sourceSystem + "（可选 " + SOURCES + "）");
      }
      where.append(" and o.source_system = ?");
      binds.add(source);
    }
    if (referenceId != null) {
      where.append(" and o.reference_id = ?");
      binds.add(referenceId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(
          " and (o.order_code ilike ? or o.product_sku_code ilike ? or o.bom_code ilike ?"
              + " or o.bom_version_no ilike ? or o.reference_no ilike ?)");
      String pattern = "%" + keyword.trim().replace("%", "\\%").replace("_", "\\_") + "%";
      for (int i = 0; i < 5; i++) {
        binds.add(pattern);
      }
    }
    String clause = where.toString();

    Record summary =
        sql.fetchOne(
            "select count(*) as total,"
                + " coalesce(sum(o.disassembly_quantity),0) as total_qty,"
                + " coalesce(sum(o.expected_component_quantity),0) as total_expected,"
                + " coalesce(sum(o.recovered_component_quantity),0) as total_recovered,"
                + " count(*) filter (where o.status='DRAFT') as draft_count,"
                + " count(*) filter (where o.status='COMPLETED') as completed_count,"
                + " count(*) filter (where o.status='CANCELLED') as cancelled_count"
                + " from disassembly_order o where true" + clause,
            binds.toArray());

    List<DisassemblyOrderRow> items =
        sql.fetch(
                "select o.id,o.order_code,o.status,o.product_sku_id,o.product_sku_code,o.bom_id,"
                    + "o.bom_code,o.bom_version_id,o.bom_version_no,o.source_system,o.reference_id,"
                    + "o.reference_no,o.warehouse_id,o.disassembly_quantity,"
                    + "o.expected_component_quantity,o.recovered_component_quantity,"
                    + "o.outbound_product_quantity,o.inventory_transaction_id,o.executed_at,"
                    + "o.cancelled_at,o.cancel_reason,o.created_at,o.created_by,o.notes,"
                    + " (select count(*) from disassembly_order_component c"
                    + "   where c.tenant_id = o.tenant_id and c.order_id = o.id)"
                    + " as component_count"
                    + " from disassembly_order o where true" + clause + orderBy(sortKey)
                    + " limit ? offset ?",
                append(binds, safeSize, safePage * safeSize).toArray())
            .map(this::row);

    // 去向分布：单独一条 GROUP BY，不 join 进上面的分页查询（否则合计被组件行数放大）。
    List<DisassemblyDestinationStat> destinations =
        sql.fetch(
                "select q.id as quality_status_id, q.code as quality_code, q.role as quality_role,"
                    + " count(*) as line_count,"
                    + " coalesce(sum(c.expected_quantity),0) as expected_quantity,"
                    + " coalesce(sum(c.recovered_quantity),0) as recovered_quantity"
                    + " from disassembly_order_component c"
                    + " join inventory_quality_status q"
                    + "   on q.tenant_id = c.tenant_id and q.id = c.destination_quality_status_id"
                    + " join disassembly_order o on o.tenant_id = c.tenant_id and o.id = c.order_id"
                    + " where true" + clause
                    + " group by q.id, q.code, q.role"
                    + " order by sum(c.expected_quantity) desc, q.code",
                binds.toArray())
            .map(
                d ->
                    new DisassemblyDestinationStat(
                        d.get("quality_status_id", UUID.class).toString(),
                        d.get("quality_code", String.class),
                        d.get("quality_role", String.class),
                        longOf(d, "line_count"),
                        decimal(d, "expected_quantity"),
                        decimal(d, "recovered_quantity")));

    return new DisassemblyOrderPage(
        items,
        safePage,
        safeSize,
        longOf(summary, "total"),
        decimal(summary, "total_qty"),
        decimal(summary, "total_expected"),
        decimal(summary, "total_recovered"),
        longOf(summary, "draft_count"),
        longOf(summary, "completed_count"),
        longOf(summary, "cancelled_count"),
        destinations,
        sortKey);
  }

  /** 拆解单详情（单据 + 组件反向快照明细 + 账本分录）。 */
  public DisassemblyOrderDetail detail(UUID orderId) {
    Record one =
        sql.fetchOne(
            "select o.id,o.order_code,o.status,o.product_sku_id,o.product_sku_code,o.bom_id,"
                + "o.bom_code,o.bom_version_id,o.bom_version_no,o.source_system,o.reference_id,"
                + "o.reference_no,o.warehouse_id,o.disassembly_quantity,"
                + "o.expected_component_quantity,o.recovered_component_quantity,"
                + "o.outbound_product_quantity,o.inventory_transaction_id,o.executed_at,"
                + "o.cancelled_at,o.cancel_reason,o.created_at,o.created_by,o.notes,"
                + " (select count(*) from disassembly_order_component c"
                + "   where c.tenant_id = o.tenant_id and c.order_id = o.id)"
                + " as component_count"
                + " from disassembly_order o where o.id = ?",
            orderId);
    if (one == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "拆解单 " + orderId + " 不存在或无权查看");
    }
    List<DisassemblyComponentRow> components =
        sql.fetch(
                "select c.id,c.sku_id,c.sku_code,c.bom_component_id,c.unit_quantity,c.recovery_rate,"
                    + "c.expected_quantity,c.recovered_quantity,c.destination_quality_status_id,"
                    + "c.destination_location_id,c.destination_lot_id,c.balance_id,c.role,c.note,"
                    + "q.code as quality_code,q.role as quality_role"
                    + " from disassembly_order_component c"
                    + " left join inventory_quality_status q"
                    + "   on q.tenant_id = c.tenant_id and q.id = c.destination_quality_status_id"
                    + " where c.order_id = ? order by c.sku_code, c.id",
                orderId)
            .map(
                c ->
                    new DisassemblyComponentRow(
                        c.get("id", UUID.class),
                        c.get("sku_id", UUID.class),
                        c.get("sku_code", String.class),
                        c.get("bom_component_id", UUID.class),
                        c.get("unit_quantity", BigDecimal.class),
                        c.get("recovery_rate", BigDecimal.class),
                        c.get("expected_quantity", BigDecimal.class),
                        c.get("recovered_quantity", BigDecimal.class),
                        c.get("destination_quality_status_id", UUID.class),
                        c.get("quality_code", String.class),
                        c.get("quality_role", String.class),
                        c.get("destination_location_id", UUID.class),
                        c.get("destination_lot_id", UUID.class),
                        c.get("balance_id", UUID.class),
                        c.get("role", String.class),
                        c.get("note", String.class)));
    UUID transactionId = one.get("inventory_transaction_id", UUID.class);
    return new DisassemblyOrderDetail(
        row(one), components, transactionId == null ? List.of() : entriesOf(transactionId));
  }

  // ================================================================ 库存写入

  /**
   * 出库：减余额 + 同步减库位明细（V2 守恒触发器要求两处同动）。
   *
   * <p>先查库位上的实际在手量并给出可读报错：V1 / V2 的 {@code CHECK(available>=0)} 确实会挡住
   * 扣成负数，但那条报错只说「违反检查约束」，完全不指到是哪批货、在哪个库位、差多少 ——
   * 而拆解失败正是「成品不够」这个业务事实。
   */
  private void postOutbound(
      UUID balanceId, UUID locationId, UUID lotId, BigDecimal want, UUID transactionId,
      OffsetDateTime at) {
    BigDecimal qty = stock(want);
    Record bucket =
        sql.fetchOne(
            "select id,on_hand,reserved from stock_bucket where balance_id=? and location_id=?"
                + " and lot_id is not distinct from ?",
            balanceId, locationId, lotId);
    if (bucket == null) {
      throw bad(
          "出库失败：库位上没有这件成品的库存行。拆解必须从真实存在的库位取出成品，"
              + "不能凭空扣一个 SKU 的库存");
    }
    BigDecimal onHand = stock(decimal(bucket, "on_hand"));
    BigDecimal reserved = stock(decimal(bucket, "reserved"));
    if (onHand.compareTo(qty) < 0) {
      throw bad(
          "出库失败：该库位上只有 " + onHand + "，本次要拆 " + qty
              + (reserved.signum() > 0 ? "（其中 " + reserved + " 已被销售预占）" : "")
              + "。成品不足会让拆解做不完 —— 请先补货或调整本单的拆解数量");
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

  private List<DisassemblyOrderEntry> entriesOf(UUID transactionId) {
    return sql.fetch(
            "select e.id,e.balance_id,b.sku_id,e.on_hand_delta,e.reserved_delta"
                + " from inventory_entry e join inventory_balance b"
                + " on b.tenant_id = e.tenant_id and b.id = e.balance_id"
                + " where e.transaction_id = ? order by e.on_hand_delta, e.id",
            transactionId)
        .map(
            e ->
                new DisassemblyOrderEntry(
                    e.get("id", UUID.class),
                    e.get("balance_id", UUID.class),
                    e.get("sku_id", UUID.class),
                    stock(decimal(e, "on_hand_delta")),
                    stock(decimal(e, "reserved_delta")),
                    decimal(e, "on_hand_delta").signum() < 0 ? "PRODUCT_OUT" : "COMPONENT_IN"));
  }

  // ================================================================ 主数据校验

  /** 取一张处于 DRAFT 的拆解单并锁住它（防并发重复执行）。 */
  private Record lockDraft(UUID orderId) {
    Record order = sql.fetchOne("select * from disassembly_order where id = ? for update", orderId);
    if (order == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "拆解单 " + orderId + " 不存在或无权操作");
    }
    String status = order.get("status", String.class);
    if (!"DRAFT".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "拆解单 " + order.get("order_code", String.class) + " 当前是 " + status
              + "，只有草稿可以执行或取消：已完成拆解单的成品已经出库、组件已经入库，"
              + "重复执行会重复扣成品重复入组件");
    }
    return order;
  }

  /**
   * 上游组装单必须存在、属于本租户且**已完成**。
   *
   * <p>刻意用显式 SQL 而不是外键：外键只能保证「那张组装单存在」，表达不了
   * 「它必须已经完成」。而拆一件没组装过的成品在账上说不通 —— 库里那件成品是谁放进来的？
   */
  private void requireCompletedAssembly(UUID referenceId) {
    Record assembly =
        sql.fetchOne("select order_code,status from assembly_order where id = ?", referenceId);
    if (assembly == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "上游组装单 " + referenceId + " 不存在或无权查看：拆解必须凭一张真实的组装单追溯");
    }
    if (!"COMPLETED".equals(assembly.get("status", String.class))) {
      throw bad(
          "上游组装单 " + assembly.get("order_code", String.class) + " 当前是 "
              + assembly.get("status", String.class) + "：一张还没执行的组装单没有成品可拆，"
              + "拆出来的组件会凭空出现");
    }
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

  /** SKU 必须在 sku_ref 登记：库存分录要落到真实存在的 SKU 上。 */
  private String requireSku(UUID skuId, String label) {
    Record row = sql.fetchOne("select code from sku_ref where id = ?", skuId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          label + " " + skuId + " 没有在本服务登记（sku_ref）：库存分录要落到真实存在的 SKU 上");
    }
    return row.get("code", String.class);
  }

  private String skuCode(UUID skuId) {
    Record row = sql.fetchOne("select code from sku_ref where id = ?", skuId);
    return row == null ? "" : row.get("code", String.class);
  }

  /**
   * 成品出库质量状态：不传则取本租户唯一启用的可售状态。
   *
   * <p>这里给默认是可售的 —— 被拆的成品本来就在售货架上，它的质量状态是<b>既成事实</b>，
   * 不是拆解要决定的。真正不能默认的是<b>组件去向</b>（见 {@link #requireDestinationQuality}）：
   * 那边是「接收方判定」，这边是「读现状」。
   */
  private UUID resolveOutboundQuality(UUID qualityStatusId) {
    if (qualityStatusId == null) {
      Record row =
          sql.fetchOne(
              "select id from inventory_quality_status where role = 'SELLABLE' and enabled"
                  + " order by code limit 1");
      if (row == null) {
        throw bad(
            "本租户没有启用的可售质量状态，无法确定成品从哪个质量状态出库。"
                + "请先在质量状态字典里启用一个 role=SELLABLE 的状态");
      }
      return row.get("id", UUID.class);
    }
    Record row =
        sql.fetchOne(
            "select id,code,enabled from inventory_quality_status where id = ?", qualityStatusId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "成品出库质量状态 " + qualityStatusId + " 不存在或无权使用");
    }
    if (!Boolean.TRUE.equals(row.get("enabled", Boolean.class))) {
      throw bad("成品出库质量状态「" + row.get("code", String.class) + "」已停用");
    }
    return qualityStatusId;
  }

  /**
   * 组件去向质量状态：<b>必填，且没有默认值</b>。
   *
   * <p>蓝图 8.4.2.1 关系段的加粗句：「拆解结果的组件质量状态由接收方决定，
   * <b>不能默认全部回可售</b>。」
   *
   * <p>所以这里刻意<b>不</b>像组装那样「不传就取 SELLABLE」—— 退回的货凭什么能直接卖？
   * 缺了去向就是调用方没判定，服务层必须把球传回去，而不是替接收方做主。
   * DB 层的 {@code destination_quality_status_id NOT NULL} 是第二道，绕不过。
   */
  private UUID requireDestinationQuality(UUID qualityStatusId, UUID skuId) {
    if (qualityStatusId == null) {
      throw bad(
          "组件 " + skuId + " 必须显式指定入库去向（可用 / 待检 / 残次），本模块不提供默认值："
              + "蓝图明确「拆解结果的组件质量状态由接收方决定，不能默认全部回可售」。"
              + "退回的货默认进可售货架，等于让没检验过的货直接可卖");
    }
    Record row =
        sql.fetchOne(
            "select id,code,role,enabled from inventory_quality_status where id = ?",
            qualityStatusId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "组件入库去向 " + qualityStatusId + " 不存在或无权使用");
    }
    if (!Boolean.TRUE.equals(row.get("enabled", Boolean.class))) {
      throw bad(
          "组件入库去向「" + row.get("code", String.class) + "」已停用，不能作为拆解的去向");
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

  private DisassemblyOrderRow row(Record r) {
    return new DisassemblyOrderRow(
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
        decimal(r, "disassembly_quantity"),
        decimal(r, "expected_component_quantity"),
        decimal(r, "recovered_component_quantity"),
        decimal(r, "outbound_product_quantity"),
        intOf(r, "component_count"),
        r.get("inventory_transaction_id", UUID.class),
        timestamp(r, "executed_at"),
        timestamp(r, "cancelled_at"),
        r.get("cancel_reason", String.class),
        timestamp(r, "created_at"),
        r.get("created_by", UUID.class),
        r.get("notes", String.class));
  }

  private String orderBy(String key) {
    // 一律以 order_code 收尾：排序键相同时必须有确定次序，否则同一页两次请求可能给出不同顺序。
    return switch (key) {
      case "ORDER_CODE" -> " order by o.order_code, o.id";
      case "DISASSEMBLY_QUANTITY" -> " order by o.disassembly_quantity desc, o.order_code";
      case "STATUS" -> " order by o.status, o.order_code";
      default -> " order by o.created_at desc, o.order_code";
    };
  }

  private long nextCode() {
    Long value =
        sql.fetchOne("select nextval('disassembly_order_code_seq')").get(0, Long.class);
    return value == null ? 1L : value;
  }

  private static BigDecimal sumExpected(List<DisassemblyComponentRecovered> items) {
    BigDecimal total = BigDecimal.ZERO;
    for (DisassemblyComponentRecovered item : items) {
      total = total.add(item.expectedQuantity());
    }
    return total;
  }

  private static BigDecimal sumRecovered(List<DisassemblyComponentRecovered> items) {
    BigDecimal total = BigDecimal.ZERO;
    for (DisassemblyComponentRecovered item : items) {
      total = total.add(item.recoveredQuantity());
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

  /** 数量刻度：与 V108 的 NUMERIC(19,6) 对齐。 */
  private static BigDecimal qty(BigDecimal value, String label) {
    if (value == null || value.signum() <= 0) {
      throw bad(label + "必须大于 0");
    }
    if (value.scale() > QTY_SCALE) {
      throw bad(label + "最多 6 位小数");
    }
    return value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 要真正过账的量必须能被<b>库存刻度（4 位小数）精确表示</b>，否则显式拒绝。
   *
   * <p>为什么不静默取整：向下取整等于凭空吞掉组件（账上少拆、实体多丢），向上取整等于凭空
   * 多产组件。两种都破坏账实相符，而且都恰好能让「组件入库 = 应回量」这条不变量
   * <b>看起来成立</b> —— 那才是最糟的：数字对上了，物料对不上。所以宁可拒绝并说清楚是哪一行。
   */
  private static BigDecimal stockExact(BigDecimal value, String label) {
    try {
      return value.setScale(STOCK_SCALE, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw bad(
          label + " " + value + " 有超过 4 位小数，无法在库存刻度上精确表示："
              + "向下取整等于凭空吞掉组件、向上取整等于凭空多产，两种都会让"
              + "「组件入库 = 应回量」这条不变量看起来成立而物料对不上。"
              + "请调整拆解数量或 BOM 的用量，让结果能被 4 位小数精确表示");
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