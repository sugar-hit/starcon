package com.starcon.warehouse;

import static com.starcon.warehouse.ProcessingOrderModels.*;

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
 * 6.4.1.1 加工单的**仓库侧消费**：组件出库 + 成品入库（**一个本地事务**）。
 *
 * <p><b>本类是蓝图那句加粗要求的落点</b>： 「加工是『消耗组件产生成品』的库存动作，**必须在 warehouse 本地事务完成**， 不能由 trade 直接改库存。」
 *
 * <p>因此加工单据在 trade（V97），库存动作在这里，且 {@link #issue} 标注 {@code @Transactional} —— 组件出库与成品入库
 * 要么都成、要么都回滚。<b>绝不允许</b>出现「组件扣了成品没进」或反之： 那正是「凭空多出成品」与「凭空少掉组件」两种账实不符的来源。
 *
 * <p><b>复用 V1 既有分录模型，不新建表</b>（与 8.2.1.2 收货差异处理同一纪律）： 一次加工 = 一个 {@code inventory_transaction}
 * （{@code kind='ADJUSTMENT'}、{@code ref_type='PROCESSING_ORDER'}、{@code ref_id=加工单 ID}） + N 条带符号
 * {@code inventory_entry}。 {@code UNIQUE(tenant_id, ref_type, ref_id)} 顺带给了**幂等**：
 * 同一张加工单重复投递时靠它去重，不会重复扣料重复入货。
 *
 * <p>组件出库方向是负向分录（{@code on_hand_delta} 为负）， 成品入库方向是正向分录，两者挂在**同一个 transaction_id** 下 ——
 * 账上「这一批料为什么消失了、这批货为什么出现了」永远指向同一条单据。
 */
@Service
public class ProcessingOrderService {

  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;

  public ProcessingOrderService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力与上游可用性。
   *
   * <p>存在的理由：把「库存动作归 warehouse」与「单据归 trade」这条边界显式报出来， 让调用方不会误以为应该由 trade 去改库存。
   */
  public ProcessingStockCapabilities capabilities() {
    return new ProcessingStockCapabilities(
        REF_TYPE,
        true,
        true,
        "上游 6.4.1.1 加工单属 trade（V97 已交付）：本服务只按加工单冻结的需求量出料，"
            + "需求量的算法（单位用量 × 产量 × (1 + 损耗率/100)，6 位小数 HALF_UP、损耗率是百分数）"
            + "由 catalog 3.5.2.1 的规则 R6/R7 定义并在 trade 侧冻结，本服务不重算、不取整。",
        List.of(
            new ProcessingStockCapabilities.DownstreamGap(
                "8.4.1.1",
                "组装单",
                "warehouse",
                false,
                "仓库现场作业单据未交付：当前只做库存层面的组件出库与成品入库，" + "拣货 / 复核 / 上架的现场任务流尚未建模。"),
            new ProcessingStockCapabilities.DownstreamGap(
                "6.4.1.2",
                "BOM 关联",
                "trade",
                false,
                "同组兄弟模块未交付：冻结展开结果由 trade 在下达时落到组件领用记录，" + "本服务消费的是已冻结的数量，不需要再回查 BOM。")),
        "组件出库与成品入库在**同一个本地事务**里完成，任一环节失败整体回滚；"
            + "库存分录挂在一条 inventory_transaction 上（ref_type=PROCESSING_ORDER），"
            + "同一张加工单重复投递靠 UNIQUE(tenant_id,ref_type,ref_id) 去重。");
  }

  /** 库存动作归属说明（供前端把「谁改库存」写在界面上）。 */
  public StockMovementBoundary boundary() {
    return new StockMovementBoundary(
        "warehouse",
        "组件出库与成品入库在 warehouse 的一个本地事务内完成",
        "trade 拥有加工单据与组件领用记录，不直接改库存",
        "这是蓝图 6.4.1.1 关系段的加粗要求：加工是『消耗组件产生成品』的库存动作，" + "必须在库存所有者服务内完成，不能由单据服务代劳。");
  }

  // ================================================================ 组件出库 + 成品入库

  /**
   * 执行一张加工单：组件出库 + 成品入库，**一个本地事务**。
   *
   * <p>顺序刻意是「先出料、后入货」：任何一个组件不足就抛异常回滚， 此时成品**还没有**入账，账上不会出现「凭空多出来的成品」。
   * 反过来先入货再出料的话，出料失败时货已经入库，账上凭空多出成品。
   */
  @Transactional
  public ProcessingOrderIssueSaved issue(ProcessingOrderIssueInput input, UUID actorId) {
    UUID warehouseId = input.warehouseId();
    UUID legalEntityId = input.legalEntityId();
    requireWarehouse(warehouseId);
    requireLegalEntity(legalEntityId);
    // 成品 SKU 必须在 sku_ref 登记过：领料与入库要落到具体 SKU 上，
    // 没登记就说明这个 SKU 从没进过本服务的档案，不能凭空记一笔。
    requireSku(input.outputSkuId(), "成品 SKU");
    BigDecimal completed = qty(input.completedQuantity(), "成品入库数量");

    UUID inboundQualityId =
        input.inboundQualityStatusId() == null
            ? requireSellableQuality()
            : requireQuality(input.inboundQualityStatusId(), "成品入库质量状态");
    UUID outboundQualityId =
        input.outboundQualityStatusId() == null
            ? requireSellableQuality()
            : requireQuality(input.outboundQualityStatusId(), "组件出库质量状态");
    if (input.outboundLocationId() != null) {
      requireLocation(warehouseId, input.outboundLocationId(), "组件出库库位");
    }
    if (input.inboundLocationId() != null) {
      requireLocation(warehouseId, input.inboundLocationId(), "成品入库库位");
    }

    // 幂等锚点：同一张加工单只能执行一次。重复投递在这里被 UNIQUE 挡下（409）。
    UUID existing = existingTransaction(input.processingOrderId());
    if (existing != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "加工单 " + input.processingOrderId() + " 已经执行过入库（分录 " + existing + "）：同一张加工单不能重复扣料重复入货");
    }

    OffsetDateTime at = OffsetDateTime.now();
    UUID transactionId = UUID.randomUUID();
    sql.execute(
        "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,"
            + "notes) values(?,?,?,?,?,?::timestamptz,?,?)",
        transactionId,
        currentTenant(),
        "ADJUSTMENT",
        REF_TYPE,
        input.processingOrderId(),
        at,
        actorId,
        nz(input.notes()));

    // ---- 第一步：组件出库（负向分录）。任何一条失败都整体回滚。
    List<ComponentIssued> issued = new ArrayList<>();
    for (ComponentIssue issue : input.componentIssues()) {
      requireSku(issue.skuId(), "组件 SKU");
      BigDecimal want = qty(issue.quantity(), "组件出库数量");
      // 去重：同一 SKU 出现两行时「这个组件要几件」无法回答，账上也会出现两条同向分录。
      if (issued.stream().anyMatch(done -> done.skuId().equals(issue.skuId()))) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "加工单里组件 SKU " + issue.skuId() + " 出现了两次：同一组件只能有一行，" + "两行时「这个组件要几件」无法回答");
      }
      UUID balanceId = balanceId(issue.skuId(), warehouseId, legalEntityId, outboundQualityId);
      BigDecimal posted =
          postOutbound(
              balanceId,
              input.outboundLocationId(),
              input.outboundLotId(),
              want,
              transactionId,
              at);
      issued.add(
          new ComponentIssued(issue.skuId(), want, balanceId, input.outboundLocationId(), posted));
    }

    // ---- 第二步：成品入库（正向分录）。与上面的出库同属这一个事务。
    UUID productBalance =
        balanceId(input.outputSkuId(), warehouseId, legalEntityId, inboundQualityId);
    postInbound(
        productBalance,
        input.inboundLocationId(),
        input.inboundLotId(),
        stock(completed),
        transactionId,
        at);

    return new ProcessingOrderIssueSaved(
        input.processingOrderId(),
        input.orderNo(),
        transactionId,
        at.toString(),
        input.outputSkuId(),
        completed,
        issued,
        issued.size() + 1,
        "组件出库与成品入库已在 warehouse 的同一个本地事务内完成（分录 "
            + transactionId
            + "）：任一环节失败都会整体回滚，不会出现「组件扣了成品没进」或反之。"
            + "成品入库质量状态是 "
            + qualityLabel(inboundQualityId)
            + "；入库数量按库存刻度（4 位小数）对齐后入账。");
  }

  // ================================================================ 执行记录查询

  /** 加工单在仓库的执行记录（真分页 + 全量汇总）。 */
  public ProcessingStockPage pageExecutions(UUID processingOrderId, int page, int size) {
    int safePage = Math.max(0, page);
    int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    List<Object> binds = new ArrayList<>();
    // ref_type 已经在语句里带了 where，所以可选条件只能用 and 续写 ——
    // 写成 " where ref_id = ?" 会拼出两个 where（SQL 直接语法错）。
    String clause = "";
    if (processingOrderId != null) {
      clause = " and ref_id = ?";
      binds.add(processingOrderId);
    }
    // 占位符顺序必须与语句里出现的顺序一致：ref_type →（可选）ref_id。
    var countBinds = prepend(binds, REF_TYPE);
    long total =
        sql.fetchOne(
                "select count(*) from inventory_transaction where ref_type = ?" + clause,
                countBinds.toArray())
            .get(0, Long.class);
    Record summary =
        sql.fetchOne(
            "select coalesce(sum(case when e.on_hand_delta > 0 then e.on_hand_delta end),0)"
                + " as total_completed,"
                + " coalesce(sum(case when e.on_hand_delta < 0 then -e.on_hand_delta end),0)"
                + " as total_issued"
                + " from inventory_entry e join inventory_transaction t"
                + " on t.tenant_id = e.tenant_id and t.id = e.transaction_id"
                + " where t.ref_type = ?",
            REF_TYPE);
    // 占位符顺序必须与语句里出现的顺序一致：ref_type →（可选）ref_id → limit → offset。
    var listBinds = prepend(binds, REF_TYPE);
    List<ProcessingStockRow> items =
        sql.fetch(
                "select id,ref_id,occurred_at,actor_id,notes from inventory_transaction"
                    + " where ref_type = ?"
                    + clause
                    + " order by occurred_at desc, id limit ? offset ?",
                append(listBinds, safeSize, safePage * safeSize).toArray())
            .map(this::executionRow);
    return new ProcessingStockPage(
        items,
        safePage,
        safeSize,
        total,
        stock(decimal(summary, "total_completed")),
        stock(decimal(summary, "total_issued")));
  }

  private ProcessingStockRow executionRow(Record t) {
    UUID transactionId = t.get("id", UUID.class);
    List<ProcessingStockEntry> entries =
        sql.fetch(
                "select e.id,e.balance_id,b.sku_id,e.on_hand_delta,e.reserved_delta"
                    + " from inventory_entry e join inventory_balance b"
                    + " on b.tenant_id = e.tenant_id and b.id = e.balance_id"
                    + " where e.transaction_id = ? order by e.on_hand_delta, e.id",
                transactionId)
            .map(
                e ->
                    new ProcessingStockEntry(
                        e.get("id", UUID.class),
                        e.get("balance_id", UUID.class),
                        e.get("sku_id", UUID.class),
                        stock(decimal(e, "on_hand_delta")),
                        stock(decimal(e, "reserved_delta"))));
    BigDecimal completed = BigDecimal.ZERO;
    BigDecimal issued = BigDecimal.ZERO;
    int components = 0;
    for (ProcessingStockEntry e : entries) {
      if (e.onHandDelta().signum() > 0) {
        completed = completed.add(e.onHandDelta());
      } else {
        issued = issued.add(e.onHandDelta().abs());
        components++;
      }
    }
    return new ProcessingStockRow(
        transactionId,
        t.get("ref_id", UUID.class),
        null,
        t.get("occurred_at", OffsetDateTime.class).toString(),
        null,
        completed,
        issued,
        components,
        entries.size(),
        nz(t.get("notes", String.class)),
        t.get("actor_id", UUID.class),
        entries);
  }

  // ================================================================ 库存写入

  /**
   * 出库：减余额 + 同步减库位明细（V2 守恒触发器要求两处同动）。
   *
   * <p>先查库位上的实际在手量并给出可读报错：V1 / V2 的 {@code CHECK(available>=0)}
   * 确实会挡住扣成负数，但那条报错只说「违反检查约束」，完全不指到是哪批货、 在哪个库位、差多少 —— 而加工出料失败正是「组件不够」这个业务事实。
   */
  private BigDecimal postOutbound(
      UUID balanceId,
      UUID locationId,
      UUID lotId,
      BigDecimal want,
      UUID transactionId,
      OffsetDateTime at) {
    BigDecimal qty = stock(want);
    if (qty.signum() <= 0) {
      throw bad("组件出库量 " + want + " 在库存刻度（4 位小数）下取整后为 0：" + "领料量必须是能被仓库真正发出的数");
    }
    if (locationId == null) {
      // 没给库位就无法定位 bucket：V2 的守恒触发器要求 bucket 与 balance 同步变动。
      throw bad(
          "组件出库必须给库位（outboundLocationId）：库存的最小事实单位是"
              + "「法人货权 × SKU × 仓库 × 库位 × 批次 × 质量状态」，没有库位就落不了账");
    }
    Record bucket =
        sql.fetchOne(
            "select id,on_hand,reserved from stock_bucket where balance_id=? and location_id=?"
                + " and lot_id is not distinct from ?",
            balanceId,
            locationId,
            lotId);
    if (bucket == null) {
      throw bad("出库失败：库位上没有这个组件的库存行。加工领料必须从真实存在的库位发出，" + "不能凭空扣一个 SKU 的库存");
    }
    BigDecimal onHand = stock(decimal(bucket, "on_hand"));
    BigDecimal reserved = stock(decimal(bucket, "reserved"));
    if (onHand.compareTo(qty) < 0) {
      throw bad(
          "出库失败：库位上只有 "
              + onHand
              + "，本次加工要发 "
              + qty
              + (reserved.signum() > 0 ? "（其中 " + reserved + " 已被销售预占）" : "")
              + "。组件不足会让加工做不完 —— 请先补料或调整本单的需求量");
    }
    sql.execute(
        "update stock_bucket set on_hand=on_hand-?,"
            + " last_movement_at=greatest(?::timestamptz,last_movement_at"
            + " + interval '1 microsecond') where id=?",
        qty,
        at,
        bucket.get("id", UUID.class));
    sql.execute(
        "update inventory_balance set on_hand=on_hand-?,version=version+1,updated_at=now()"
            + " where id=?",
        qty,
        balanceId);
    sql.execute(
        "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
            + "reserved_delta) values(?,?,?,?,?,0)",
        UUID.randomUUID(),
        currentTenant(),
        transactionId,
        balanceId,
        qty.negate());
    return qty;
  }

  /** 入库：加余额 + 同步加库位明细（V2 守恒触发器要求两处同动）。 */
  private void postInbound(
      UUID balanceId,
      UUID locationId,
      UUID lotId,
      BigDecimal qty,
      UUID transactionId,
      OffsetDateTime at) {
    Record existing =
        sql.fetchOne(
            "select id from stock_bucket where balance_id=? and location_id=?"
                + " and lot_id is not distinct from ?",
            balanceId,
            locationId,
            lotId);
    if (existing != null) {
      sql.execute(
          "update stock_bucket set on_hand=on_hand+?,"
              + " last_movement_at=greatest(?::timestamptz,last_movement_at"
              + " + interval '1 microsecond') where id=?",
          qty,
          at,
          existing.get("id", UUID.class));
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
        qty,
        balanceId);
    sql.execute(
        "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
            + "reserved_delta) values(?,?,?,?,?,0)",
        UUID.randomUUID(),
        currentTenant(),
        transactionId,
        balanceId,
        qty);
  }

  // ================================================================ 主数据校验

  private void requireWarehouse(UUID warehouseId) {
    if (sql.fetchOne("select 1 from warehouse where id = ?", warehouseId) == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓库 " + warehouseId + " 不存在或无权使用");
    }
  }

  private void requireLegalEntity(UUID legalEntityId) {
    if (sql.fetchOne("select 1 from legal_entity_ref where id = ?", legalEntityId) == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "货权法人 " + legalEntityId + " 不存在或无权使用");
    }
  }

  private void requireSku(UUID skuId, String label) {
    if (sql.fetchOne("select 1 from sku_ref where id = ?", skuId) == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, label + " " + skuId + " 没有在本服务登记（sku_ref）：库存分录要落到真实存在的 SKU 上");
    }
  }

  private UUID requireQuality(UUID qualityStatusId, String label) {
    Record row =
        sql.fetchOne(
            "select id,code,role,enabled from inventory_quality_status where id = ?",
            qualityStatusId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, label + " " + qualityStatusId + " 不存在或无权使用");
    }
    if (!Boolean.TRUE.equals(row.get("enabled", Boolean.class))) {
      throw bad("质量状态「" + row.get("code", String.class) + "」已停用，不能作为加工的库存去向");
    }
    return qualityStatusId;
  }

  /** 默认质量状态：租户唯一启用的可售状态（加工成品默认入可售）。 */
  private UUID requireSellableQuality() {
    Record row =
        sql.fetchOne(
            "select id from inventory_quality_status where role = 'SELLABLE' and enabled"
                + " order by code limit 1");
    if (row == null) {
      throw bad("本租户没有启用的可售质量状态，无法确定成品入库去向。" + "请先在质量状态字典里启用一个 role=SELLABLE 的状态");
    }
    return row.get("id", UUID.class);
  }

  private void requireLocation(UUID warehouseId, UUID locationId, String label) {
    Record row =
        sql.fetchOne(
            "select 1 from warehouse_location where id = ? and warehouse_id = ?"
                + " and status = 'ACTIVE'",
            locationId,
            warehouseId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, label + " " + locationId + " 不存在、不属于该仓或已停用");
    }
  }

  private String qualityLabel(UUID qualityStatusId) {
    return sql.fetchOne("select code from inventory_quality_status where id = ?", qualityStatusId)
        .get(0, String.class);
  }

  private UUID existingTransaction(UUID processingOrderId) {
    Record row =
        sql.fetchOne(
            "select id from inventory_transaction where ref_type = ? and ref_id = ?",
            REF_TYPE,
            processingOrderId);
    return row == null ? null : row.get("id", UUID.class);
  }

  /** 取（或建）一条余额行。跨租户塞进来的仓/法人/质量状态会被 UNIQUE 或 RLS 挡住。 */
  private UUID balanceId(UUID skuId, UUID warehouseId, UUID legalEntityId, UUID qualityStatusId) {
    var found =
        sql.fetchOne(
            "select id from inventory_balance where sku_id=? and warehouse_id=?"
                + " and legal_entity_id=? and quality_status_id=?",
            skuId,
            warehouseId,
            legalEntityId,
            qualityStatusId);
    if (found != null) {
      return found.get("id", UUID.class);
    }
    var id = UUID.randomUUID();
    sql.execute(
        "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
            + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,0,0)",
        id,
        currentTenant(),
        skuId,
        warehouseId,
        legalEntityId,
        qualityStatusId);
    return id;
  }

  // ================================================================ 工具

  /**
   * 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。
   *
   * <p>表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC **复位成空串**而不是「回到未设置」，直接 cast 会报
   * uuid 语法错 （与 warehouse V1 / trade V92 同一句写法）。
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

  /** 需求数量刻度：与 trade V97 / catalog R7 的 numeric(19,6) 对齐。 */
  private static BigDecimal qty(BigDecimal value, String label) {
    if (value == null || value.signum() <= 0) {
      throw bad(label + "必须大于 0");
    }
    if (value.scale() > QTY_SCALE) {
      throw bad(label + "最多 6 位小数（与 trade V97 与 catalog 规则 R7 的口径一致）");
    }
    return value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  /** 库存刻度：与 V1 的 numeric(19,4) 对齐。 */
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

  private static List<Object> prepend(List<Object> binds, Object first) {
    var all = new ArrayList<>();
    all.add(first);
    all.addAll(binds);
    return all;
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
