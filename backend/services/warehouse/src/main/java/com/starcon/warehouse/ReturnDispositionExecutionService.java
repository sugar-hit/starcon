package com.starcon.warehouse;

import static com.starcon.warehouse.ReturnDispositionExecutionModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 11.2.3.2 转可售/残次/报废 —— warehouse 侧**执行**。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「按质检结论把退货转入对应状态：可再销售转可售库存、
 * 残次转残次库存（可用于清仓）、报废则核销并计入损耗。」输出：「库存状态变更分录；可售量更新；
 * 报废损耗记录。」
 *
 * <p><b>本服务只做「执行」这一段</b>：处置决定、审批与报废损耗记录归 trade
 * （{@code V337__trade_return_inventory_disposition.sql}）。库存事实的写入所有权在本服务，
 * 所以改库存这一步必须在这里发生 —— AGENTS.md「模块边界纪律」禁止跨服务 SQL / FK / 共享视图。
 *
 * <p><b>入账口径照抄 8.2.2.2 待检处置（PendingInspectionService.dispose）的成熟做法</b>：
 * 一个事务里做五件事，顺序与它逐条一致：
 *
 * <ol>
 *   <li>写业务单 {@code inventory_transaction(kind='ADJUSTMENT', ref_type='RETURN_DISPOSITION',
 *       ref_id=<处置单 id>)}；
 *   <li>从待检 bucket 减（{@code moveOut}）并同步减它的 balance 行；
 *   <li>写一条带符号的 {@code inventory_entry}（-qty）；
 *   <li>转可售 / 转残次时：取（或建）目标 balance 与目标 bucket（{@code moveIn}），加数量、
 *       加 balance，再写一条带符号的 {@code inventory_entry}（+qty）；
 *   <li>落执行回执 {@code inventory_return_disposition_execution}。
 * </ol>
 *
 * <p><b>不绕过触发器直接改余额</b>：{@code inventory_entry} 的 append-only、
 * {@code inventory_balance} / {@code stock_bucket} 的守恒触发器与
 * {@code CHECK(on_hand>=0)} 全部照旧生效；「不许把库存做成负数」在服务层先判一次给出能读懂的话，
 * DB 层再兜一道。
 *
 * <p><b>幂等</b>：一条处置单只执行一次（{@code UNIQUE(tenant_id, disposition_id)} +
 * {@code inventory_transaction} 的 {@code UNIQUE(tenant_id, ref_type, ref_id)}）。
 * 重复提交返回既有回执并标 {@code idempotentReplay=true}，**不再动一次库存** ——
 * 报废不可逆，扣两次就是把货扣没了。
 */
@Service
public class ReturnDispositionExecutionService {

  /** 数量精度：与既有 bucket / balance 口径一致（NUMERIC(19,4)）。 */
  private static final int QUANTITY_SCALE = 4;

  /** 执行单号前缀。 */
  private static final String EXECUTION_NO_PREFIX = "RDE";

  /** 跨服务定位键：库存台账（7.1.2.1）按 ref_type 归集。 */
  static final String REF_TYPE = "RETURN_DISPOSITION";

  private final DSLContext sql;

  public ReturnDispositionExecutionService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力与边界声明：职责句的每个动词逐块报可用性，边界逐条说明归属。
   *
   * <p><b>不做假</b>：本服务只报自己真的做得了的事，处置决定 / 审批 / 损耗记录明确报
   * 「归 trade」，12 财务明确报「未交付」。
   */
  public ReturnDispositionExecutionCapabilities capabilities() {
    return new ReturnDispositionExecutionCapabilities(
        List.of(
            new ReturnDispositionExecutionCapability(
                "按质检结论执行库存状态变更（转可售 / 转残次 / 报废核销）",
                true,
                "11.2.3.2",
                "本服务是库存事实的写入所有者：改 bucket / balance、落 inventory_transaction + "
                    + "inventory_entry 分录"),
            new ReturnDispositionExecutionCapability(
                "库存状态变更分录", true, "11.2.3.2",
                "写 inventory_transaction(ADJUSTMENT, ref_type='RETURN_DISPOSITION') + inventory_entry，"
                    + "库存台账（7.1.2.1）读的就是 inventory_entry，本服务不另建第二张流水表"),
            new ReturnDispositionExecutionCapability(
                "可售量更新", true, "11.2.3.2",
                "转可售后 7.1.1.3 可用量随之变化；执行回执记录执行前后的可售量作为口径证据"),
            new ReturnDispositionExecutionCapability(
                "报废损耗记录", false, "11.2.3.2 / 12 财务",
                "损耗记录归 trade 侧处置单（V337__trade_return_inventory_disposition.sql）；"
                    + "12 财务服务尚未交付，财务侧字段恒为 null 并标注归属模块，不填 0 冒充无损耗"),
            new ReturnDispositionExecutionCapability(
                "处置决定与审批", false, "11.2.3.2 / trade",
                "报废不可逆，需审批与原因 —— 决定、审批与理由都在 trade 侧，"
                    + "本服务只接受已批准的处置命令，不提供任何绕过审批的写入口")),
        List.of(
            new ReturnDispositionExecutionBoundary(
                "不从售后单据猜「货在哪个库位」",
                "11.2.3.2",
                "来源库位必须显式指定并在本服务侧校验（质量状态必须是待检、SKU 必须一致、"
                    + "在手量必须够）；自动挑一条会把货从 A 位挪到 B 位"),
            new ReturnDispositionExecutionBoundary(
                "报废不建「报废 bucket」",
                "11.2.3.2",
                "质量状态词表只有 SELLABLE / PENDING_INSPECTION / DEFECTIVE 三值，没有 SCRAP："
                    + "报废的货已经核销，往一个报废 bucket 记数量等于让这批货永远占着一个库位"),
            new ReturnDispositionExecutionBoundary(
                "不提供反执行端点",
                "11.2.3.2",
                "报废不可逆：已执行的分录不删不改，冲正要走 7.4.1.1 调整单并另附原因"),
            new ReturnDispositionExecutionBoundary(
                "不代 12 财务入账",
                "12 财务",
                "12 财务服务尚不存在，本服务不写任何财务表、不建跨库 FK")));
  }

  // ================================================================ 处置选项

  /**
   * 处置选项：候选来源库位（待检库存行）+ 候选目标库位（按目标状态的库位类型）。
   *
   * <p><b>两个候选集同时给</b>，因为它们是同一个决定的两个维度：
   * 来源库位 = 货现在在哪（待检位），目标库位 = 货要去哪（可售进 STORAGE 位、残次进 DEFECTIVE 位）。
   *
   * <p>目标库位类型用**权威函数** {@code location_allocation_required_type} 判定（V106），
   * 不在 Java 侧另抄一份映射 —— 两份映射迟早会漂移。
   */
  public ReturnDispositionExecutionOptions options(UUID skuId, String targetState) {
    TargetInventoryState state = parseState(targetState);
    UUID tenantId = currentTenant();

    var buckets = new ArrayList<ReturnDispositionExecutionPendingBucket>();
    if (skuId != null) {
      for (Record r :
          sql.fetch(
              "select b.id,b.sku_id,b.legal_entity_id,b.warehouse_id,b.location_id,b.lot_id,"
                  + "b.quality_status_id,b.on_hand,b.reserved,b.available,"
                  + "s.code as sku_code,s.name as sku_name,"
                  + "w.code as warehouse_code,w.name as warehouse_name,"
                  + "loc.code as location_code,loc.name as location_name,"
                  + "lot.lot_code,q.code as quality_status_code"
                  + " from stock_bucket b"
                  + " join sku_ref s on s.tenant_id=b.tenant_id and s.id=b.sku_id"
                  + " join warehouse w on w.tenant_id=b.tenant_id and w.id=b.warehouse_id"
                  + " join warehouse_location loc on loc.tenant_id=b.tenant_id"
                  + " and loc.warehouse_id=b.warehouse_id and loc.id=b.location_id"
                  + " left join inventory_lot lot on lot.tenant_id=b.tenant_id and lot.id=b.lot_id"
                  + " join inventory_quality_status q on q.tenant_id=b.tenant_id"
                  + " and q.id=b.quality_status_id"
                  + " where b.tenant_id=? and b.sku_id=? and q.role='PENDING_INSPECTION'"
                  + " and b.on_hand>0"
                  + " order by loc.code, lot.lot_code nulls first, b.id",
              tenantId,
              skuId)) {
        buckets.add(
            new ReturnDispositionExecutionPendingBucket(
                r.get("id", UUID.class),
                r.get("warehouse_id", UUID.class),
                r.get("warehouse_code", String.class),
                r.get("warehouse_name", String.class),
                r.get("location_id", UUID.class),
                r.get("location_code", String.class),
                r.get("location_name", String.class),
                r.get("lot_id", UUID.class),
                r.get("lot_code", String.class),
                r.get("legal_entity_id", UUID.class),
                r.get("quality_status_id", UUID.class),
                r.get("quality_status_code", String.class),
                r.get("sku_id", UUID.class),
                r.get("sku_code", String.class),
                r.get("sku_name", String.class),
                decimal(r, "on_hand"),
                decimal(r, "reserved"),
                decimal(r, "available")));
      }
    }

    String requiredType = null;
    var locations = new ArrayList<ReturnDispositionExecutionTargetLocation>();
    if (state != TargetInventoryState.SCRAP) {
      requiredType = requiredLocationType(state.name());
      for (Record r :
          sql.fetch(
              "select loc.id,loc.code,loc.name,loc.type,loc.warehouse_id,loc.pickable,loc.status,"
                  + "w.code as warehouse_code"
                  + " from warehouse_location loc"
                  + " join warehouse w on w.tenant_id=loc.tenant_id and w.id=loc.warehouse_id"
                  + " where loc.tenant_id=? and loc.type=? and loc.status='ACTIVE'"
                  + " order by w.code, loc.code",
              tenantId,
              requiredType)) {
        locations.add(
            new ReturnDispositionExecutionTargetLocation(
                r.get("id", UUID.class),
                r.get("code", String.class),
                r.get("name", String.class),
                r.get("type", String.class),
                r.get("warehouse_id", UUID.class),
                r.get("warehouse_code", String.class),
                Boolean.TRUE.equals(r.get("pickable", Boolean.class)),
                r.get("status", String.class)));
      }
    }

    return new ReturnDispositionExecutionOptions(state.name(), requiredType, buckets, locations);
  }

  // ================================================================ 执行

  /**
   * 执行一次库存状态变更。
   *
   * <p><b>幂等</b>：这张处置单此前执行过 ⇒ 直接返回既有回执（{@code idempotentReplay=true}），
   * <b>不再动一次库存</b>。
   */
  public ReturnDispositionExecutionResult execute(
      ReturnDispositionExecuteRequest request, UUID actorId) {
    if (request == null || request.dispositionId() == null || request.sourceBucketId() == null) {
      throw new IllegalArgumentException("执行命令缺少处置单 id 或来源库位 id");
    }
    if (request.skuId() == null) {
      throw new IllegalArgumentException("执行命令缺少 SKU");
    }
    if (request.occurredAt() == null) {
      throw new IllegalArgumentException("执行命令缺少业务时间（occurredAt）：库存业务时间不能由服务端糊弄");
    }
    TargetInventoryState state = parseState(request.targetInventoryState());
    BigDecimal qty = quantity4(request.qty(), "处置数量");
    UUID tenantId = currentTenant();

    // ---- 幂等：这张处置单执行过没有
    Record existing =
        sql.fetchOne(
            "select id,execution_no,inventory_transaction_id,target_bucket_id,"
                + "sellable_quantity_before,sellable_quantity_after,occurred_at,source_bucket_id,"
                + "target_inventory_state,sku_id,sku_code,qty"
                + " from inventory_return_disposition_execution"
                + " where tenant_id=? and disposition_id=?",
            tenantId,
            request.dispositionId());
    if (existing != null) {
      return new ReturnDispositionExecutionResult(
          existing.get("id", UUID.class),
          existing.get("execution_no", String.class),
          request.dispositionId(),
          request.dispositionNo(),
          existing.get("target_inventory_state", String.class),
          existing.get("sku_id", UUID.class),
          existing.get("sku_code", String.class),
          decimal(existing, "qty"),
          existing.get("source_bucket_id", UUID.class),
          existing.get("target_bucket_id", UUID.class),
          existing.get("inventory_transaction_id", UUID.class),
          decimal(existing, "sellable_quantity_before"),
          decimal(existing, "sellable_quantity_after"),
          existing.get("occurred_at", OffsetDateTime.class),
          true,
          "这张处置单此前已经执行过（幂等重放）：库存没有再动一次");
    }

    // ---- 来源 bucket：必须存在、必须是待检、SKU 必须一致、在手量必须够
    Record source =
        sql.fetchOne(
            "select b.id,b.balance_id,b.legal_entity_id,b.warehouse_id,b.location_id,b.lot_id,"
                + "b.quality_status_id,b.on_hand,b.reserved,q.role,q.code as quality_status_code,"
                + "loc.type as location_type,loc.warehouse_id as location_warehouse_id"
                + " from stock_bucket b"
                + " join inventory_quality_status q on q.tenant_id=b.tenant_id"
                + " and q.id=b.quality_status_id"
                + " join warehouse_location loc on loc.tenant_id=b.tenant_id"
                + " and loc.warehouse_id=b.warehouse_id and loc.id=b.location_id"
                + " where b.tenant_id=? and b.id=?",
            tenantId,
            request.sourceBucketId());
    if (source == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "来源库位不存在或无权访问：" + request.sourceBucketId());
    }
    String role = source.get("role", String.class);
    if (!"PENDING_INSPECTION".equals(role)) {
      throw new IllegalArgumentException(
          "来源库位的质量状态是「" + role + "」，只有待检库存才能被处置"
              + "（已处置过的货不能再处置一次）");
    }
    UUID sourceSkuId =
        sql.fetchOne("select sku_id from stock_bucket where tenant_id=? and id=?", tenantId, request.sourceBucketId())
            .get("sku_id", UUID.class);
    if (!request.skuId().equals(sourceSkuId)) {
      throw new IllegalArgumentException(
          "处置的 SKU 与来源库位上的 SKU 不是同一个：处置单说的货和库里摆的货对不上");
    }
    BigDecimal onHand = decimal(source, "on_hand");
    if (onHand.compareTo(qty) < 0) {
      throw new IllegalArgumentException(
          "来源库位上只有 " + onHand + "，要处置 " + qty + "：货已经不在待检区了，请刷新候选清单");
    }
    String sourceLocationType = source.get("location_type", String.class);
    String requiredSourceType = requiredLocationType("PENDING_INSPECTION");
    if (!requiredSourceType.equals(sourceLocationType)) {
      throw new IllegalArgumentException(
          "待检货必须在 " + requiredSourceType + " 类库位上，来源库位是 " + sourceLocationType + " 类");
    }

    // ---- 目标质量状态 / 目标库位
    UUID targetQualityStatusId = null;
    String targetQualityStatusCode = "";
    UUID targetLocationId = request.targetLocationId();
    if (state != TargetInventoryState.SCRAP) {
      Record quality =
          sql.fetchOne(
              "select id,code from inventory_quality_status"
                  + " where tenant_id=? and role=? and enabled"
                  + " order by code limit 1",
              tenantId,
              state.name());
      if (quality == null) {
        throw new IllegalStateException(
            "本租户没有可用的「" + state.label() + "」质量状态（inventory_quality_status）："
                + "目标质量状态是词表里的一项，缺了就不能转");
      }
      targetQualityStatusId = quality.get("id", UUID.class);
      targetQualityStatusCode = quality.get("code", String.class);
      if (targetLocationId == null) {
        throw new IllegalArgumentException(
            "转" + state.label() + "必须指定目标库位：库位类型与质量状态一一对应"
                + "（可售进 STORAGE 位、残次进 DEFECTIVE 位）");
      }
      requireLocationMatches(tenantId, targetLocationId, state.name(), source.get("warehouse_id", UUID.class));
    } else if (targetLocationId != null) {
      throw new IllegalArgumentException("报废核销的货不在任何库位上，不得指定目标库位");
    }

    // ---- 可售量前后值（7.1.1.3 口径：可售质量状态 + 可拣且启用的库位）
    UUID warehouseId = source.get("warehouse_id", UUID.class);
    BigDecimal sellableBefore = sellableQuantity(tenantId, request.skuId(), warehouseId);

    OffsetDateTime occurredAt = request.occurredAt();
    UUID transactionId = Ids.next();
    sql.execute(
        "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,notes)"
            + " values(?::uuid,?::uuid,'ADJUSTMENT',?,?::uuid,?::timestamptz,?::uuid,?)",
        transactionId,
        tenantId,
        REF_TYPE,
        request.dispositionId(),
        occurredAt,
        actorId,
        "退货处置：" + state.label() + " " + qty + "（处置单 " + request.dispositionNo() + "）");

    // ---- 从待检 bucket 减
    UUID sourceBalanceId = source.get("balance_id", UUID.class);
    sql.execute(
        "update stock_bucket set on_hand=on_hand-?,"
            + "last_movement_at=greatest(?::timestamptz,last_movement_at + interval '1 microsecond')"
            + " where tenant_id=? and id=?",
        qty,
        occurredAt,
        tenantId,
        request.sourceBucketId());
    sql.execute(
        "update inventory_balance set on_hand=on_hand-?,version=version+1,updated_at=now()"
            + " where tenant_id=? and id=?",
        qty,
        tenantId,
        sourceBalanceId);
    sql.execute(
        "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,reserved_delta)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,0)",
        Ids.next(),
        tenantId,
        transactionId,
        sourceBalanceId,
        qty.negate());

    // ---- 转入目标（报废没有目标：货被核销）
    UUID targetBucketId = null;
    UUID targetBalanceId = null;
    if (state != TargetInventoryState.SCRAP) {
      UUID legalEntityId = source.get("legal_entity_id", UUID.class);
      UUID lotId = source.get("lot_id", UUID.class);
      targetBalanceId =
          balanceId(tenantId, request.skuId(), warehouseId, legalEntityId, targetQualityStatusId);
      targetBucketId =
          moveIn(
              tenantId,
              targetBalanceId,
              legalEntityId,
              request.skuId(),
              warehouseId,
              targetLocationId,
              lotId,
              targetQualityStatusId,
              qty,
              occurredAt);
      sql.execute(
          "update inventory_balance set on_hand=on_hand+?,version=version+1,updated_at=now()"
              + " where tenant_id=? and id=?",
          qty,
          tenantId,
          targetBalanceId);
      sql.execute(
          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,reserved_delta)"
              + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,0)",
          Ids.next(),
          tenantId,
          transactionId,
          targetBalanceId,
          qty);
    }

    BigDecimal sellableAfter = sellableQuantity(tenantId, request.skuId(), warehouseId);

    // ---- 执行回执
    long next =
        sql.fetchOne("select nextval('inventory_return_disposition_execution_no_seq')")
            .get(0, Long.class);
    UUID executionId = Ids.next();
    sql.execute(
        "insert into inventory_return_disposition_execution(id,tenant_id,execution_no,"
            + "disposition_id,disposition_no,instruction_id,inspection_id,target_inventory_state,"
            + "sku_id,sku_code,sku_name,batch_no,qty,"
            + "source_bucket_id,source_balance_id,source_legal_entity_id,source_warehouse_id,"
            + "source_location_id,source_lot_id,source_quality_status_id,source_quality_status_code,"
            + "target_bucket_id,target_balance_id,target_quality_status_id,target_quality_status_code,"
            + "inventory_transaction_id,sellable_quantity_before,sellable_quantity_after,"
            + "occurred_at,executed_by)"
            + " values(?::uuid,?::uuid,?,?::uuid,?,?::uuid,?::uuid,?,"
            + "?::uuid,?,?,?,?,"
            + "?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?,"
            + "?::uuid,?::uuid,?::uuid,?,"
            + "?::uuid,?,?,?::timestamptz,?::uuid)",
        executionId,
        tenantId,
        EXECUTION_NO_PREFIX + next,
        request.dispositionId(),
        request.dispositionNo() == null ? "" : request.dispositionNo(),
        request.instructionId(),
        request.inspectionId(),
        state.name(),
        request.skuId(),
        request.skuCode() == null ? "" : request.skuCode(),
        request.skuName() == null ? "" : request.skuName(),
        request.batchNo() == null ? "" : request.batchNo(),
        qty,
        request.sourceBucketId(),
        sourceBalanceId,
        source.get("legal_entity_id", UUID.class),
        warehouseId,
        source.get("location_id", UUID.class),
        source.get("lot_id", UUID.class),
        source.get("quality_status_id", UUID.class),
        source.get("quality_status_code", String.class),
        targetBucketId,
        targetBalanceId,
        targetQualityStatusId,
        targetQualityStatusCode,
        transactionId,
        sellableBefore,
        sellableAfter,
        occurredAt,
        actorId);

    return new ReturnDispositionExecutionResult(
        executionId,
        EXECUTION_NO_PREFIX + next,
        request.dispositionId(),
        request.dispositionNo(),
        state.name(),
        request.skuId(),
        request.skuCode(),
        qty,
        request.sourceBucketId(),
        targetBucketId,
        transactionId,
        sellableBefore,
        sellableAfter,
        occurredAt,
        false,
        state == TargetInventoryState.SCRAP
            ? "报废核销完成：货已从待检库位核销，损耗记录归 trade 侧处置单与 12 财务"
            : "库存状态变更完成：待检 → " + state.label());
  }

  // ================================================================ 执行回执查询

  /** 执行回执列表（真分页 + 目标状态筛选 + 关键字）。 */
  public ReturnDispositionExecutionPage executions(
      UUID dispositionId, String targetState, String keyword, int page, int size) {
    int limit = Math.min(100, Math.max(1, size));
    int offset = Math.max(0, page) * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("tenant_id = ?");
    binds.add(currentTenant());
    if (dispositionId != null) {
      where.add("disposition_id = ?");
      binds.add(dispositionId);
    }
    if (targetState != null && !targetState.isBlank()) {
      where.add("target_inventory_state = ?");
      binds.add(targetState.trim().toUpperCase(Locale.ROOT));
    }
    if (keyword != null && !keyword.isBlank()) {
      where.add("(disposition_no ilike ? or sku_code ilike ? or sku_name ilike ?)");
      String like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String clause = " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from inventory_return_disposition_execution" + clause, binds.toArray())
            .get(0, Long.class);

    var rows = new ArrayList<ReturnDispositionExecutionRow>();
    for (Record r :
        sql.fetch(
            "select id,execution_no,disposition_id,disposition_no,instruction_id,"
                + "target_inventory_state,sku_id,sku_code,sku_name,batch_no,qty,"
                + "source_bucket_id,source_location_id,target_bucket_id,inventory_transaction_id,"
                + "sellable_quantity_before,sellable_quantity_after,occurred_at,executed_by"
                + " from inventory_return_disposition_execution"
                + clause
                + " order by occurred_at desc, id desc limit ? offset ?",
            append(binds, limit, (long) Math.max(0, page) * limit).toArray())) {
      rows.add(
          new ReturnDispositionExecutionRow(
              r.get("id", UUID.class),
              r.get("execution_no", String.class),
              r.get("disposition_id", UUID.class),
              r.get("disposition_no", String.class),
              r.get("instruction_id", UUID.class),
              r.get("target_inventory_state", String.class),
              r.get("sku_id", UUID.class),
              r.get("sku_code", String.class),
              r.get("sku_name", String.class),
              r.get("batch_no", String.class),
              decimal(r, "qty"),
              r.get("source_bucket_id", UUID.class),
              r.get("source_location_id", UUID.class),
              r.get("target_bucket_id", UUID.class),
              r.get("inventory_transaction_id", UUID.class),
              decimal(r, "sellable_quantity_before"),
              decimal(r, "sellable_quantity_after"),
              r.get("occurred_at", OffsetDateTime.class),
              r.get("executed_by", UUID.class)));
    }
    return new ReturnDispositionExecutionPage(rows, total, Math.max(0, page), limit);
  }

  /** 单条执行回执。 */
  public ReturnDispositionExecutionRow execution(UUID executionId) {
    Record r =
        sql.fetchOne(
            "select id,execution_no,disposition_id,disposition_no,instruction_id,"
                + "target_inventory_state,sku_id,sku_code,sku_name,batch_no,qty,"
                + "source_bucket_id,source_location_id,target_bucket_id,inventory_transaction_id,"
                + "sellable_quantity_before,sellable_quantity_after,occurred_at,executed_by"
                + " from inventory_return_disposition_execution where tenant_id=? and id=?",
            currentTenant(),
            executionId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "执行回执不存在或无权访问");
    }
    return new ReturnDispositionExecutionRow(
        r.get("id", UUID.class),
        r.get("execution_no", String.class),
        r.get("disposition_id", UUID.class),
        r.get("disposition_no", String.class),
        r.get("instruction_id", UUID.class),
        r.get("target_inventory_state", String.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("batch_no", String.class),
        decimal(r, "qty"),
        r.get("source_bucket_id", UUID.class),
        r.get("source_location_id", UUID.class),
        r.get("target_bucket_id", UUID.class),
        r.get("inventory_transaction_id", UUID.class),
        decimal(r, "sellable_quantity_before"),
        decimal(r, "sellable_quantity_after"),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("executed_by", UUID.class));
  }

  // ================================================================ 内部

  /** 转入目标库位：找得到就加，找不到就新建一条 bucket（事实行永不可删，只增不减）。 */
  private UUID moveIn(
      UUID tenantId,
      UUID balanceId,
      UUID legalEntityId,
      UUID skuId,
      UUID warehouseId,
      UUID locationId,
      UUID lotId,
      UUID qualityStatusId,
      BigDecimal qty,
      OffsetDateTime at) {
    // ⚠️ **无批次（lot_id 为空）要单独一条 SQL**：把 null 绑给 `lot_id is not distinct from ?`
    //   时 Postgres 报「could not determine data type of parameter」——
    //   非批次管理品（本仓很常见）会在这里整个执行失败。
    Record existing =
        lotId == null
            ? sql.fetchOne(
                "select id from stock_bucket where tenant_id=? and balance_id=? and location_id=?"
                    + " and lot_id is null",
                tenantId,
                balanceId,
                locationId)
            : sql.fetchOne(
                "select id from stock_bucket where tenant_id=? and balance_id=? and location_id=?"
                    + " and lot_id=?",
                tenantId,
                balanceId,
                locationId,
                lotId);
    if (existing != null) {
      sql.execute(
          "update stock_bucket set on_hand=on_hand+?,"
              + "last_movement_at=greatest(?::timestamptz,last_movement_at + interval '1 microsecond')"
              + " where tenant_id=? and id=?",
          qty,
          at,
          tenantId,
          existing.get("id", UUID.class));
      return existing.get("id", UUID.class);
    }
    UUID bucketId = Ids.next();
    sql.execute(
        "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
            + "lot_id,quality_status_id,balance_id,on_hand,reserved,last_movement_at)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?,0,"
            + "?::timestamptz)",
        bucketId,
        tenantId,
        legalEntityId,
        skuId,
        warehouseId,
        locationId,
        lotId,
        qualityStatusId,
        balanceId,
        qty,
        at);
    return bucketId;
  }

  /** 取（或建）一条余额行。 */
  private UUID balanceId(
      UUID tenantId,
      UUID skuId,
      UUID warehouseId,
      UUID legalEntityId,
      UUID qualityStatusId) {
    Record found =
        sql.fetchOne(
            "select id from inventory_balance where tenant_id=? and sku_id=? and warehouse_id=?"
                + " and legal_entity_id=? and quality_status_id=?",
            tenantId,
            skuId,
            warehouseId,
            legalEntityId,
            qualityStatusId);
    if (found != null) {
      return found.get("id", UUID.class);
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
            + "quality_status_id,on_hand,reserved) values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,"
            + "?::uuid,0,0)",
        id,
        tenantId,
        skuId,
        warehouseId,
        legalEntityId,
        qualityStatusId);
    return id;
  }

  /**
   * 该 SKU 在本仓的<b>可售量</b>（7.1.1.3 口径：role='SELLABLE' ∧ 库位可拣 ∧ 库位启用）。
   *
   * <p>口径与 {@code AvailableQuantityService} 逐字一致：可售基数只认可售质量状态 + 可拣启用的库位。
   */
  private BigDecimal sellableQuantity(UUID tenantId, UUID skuId, UUID warehouseId) {
    Record r =
        sql.fetchOne(
            "select coalesce(sum(b.available),0) as sellable from stock_bucket b"
                + " join inventory_quality_status q on q.tenant_id=b.tenant_id"
                + " and q.id=b.quality_status_id"
                + " join warehouse_location loc on loc.tenant_id=b.tenant_id"
                + " and loc.warehouse_id=b.warehouse_id and loc.id=b.location_id"
                + " where b.tenant_id=? and b.sku_id=? and b.warehouse_id=?"
                + " and q.role='SELLABLE' and loc.pickable and loc.status='ACTIVE'",
            tenantId,
            skuId,
            warehouseId);
    return r == null ? BigDecimal.ZERO : decimal(r, "sellable");
  }

  /** 目标库位类型必须与质量角色匹配（复用 V106 的权威函数，Java 侧只做前置校验）。 */
  private void requireLocationMatches(
      UUID tenantId, UUID locationId, String role, UUID warehouseId) {
    Record loc =
        sql.fetchOne(
            "select loc.type,loc.warehouse_id from warehouse_location loc"
                + " where loc.tenant_id=? and loc.id=?",
            tenantId,
        locationId);
    if (loc == null) {
      throw new IllegalArgumentException("目标库位不存在或无权访问：" + locationId);
    }
    if (!warehouseId.equals(loc.get("warehouse_id", UUID.class))) {
      throw new IllegalArgumentException("目标库位属于另一个仓库：不能跨仓处置");
    }
    String required = requiredLocationType(role);
    String type = loc.get("type", String.class);
    if (!required.equals(type)) {
      throw new IllegalArgumentException(
          "质量状态「" + role + "」只能进 " + required + " 类库位，目标库位是 " + type + " 类");
    }
  }

  /** 质量角色 → 库位类型（权威定义在 V106 的 location_allocation_required_type）。 */
  private String requiredLocationType(String role) {
    Record r = sql.fetchOne("select location_allocation_required_type(?) as t", role);
    String t = r == null ? null : r.get("t", String.class);
    if (t == null) {
      throw new IllegalStateException("质量状态角色「" + role + "」没有对应的库位类型");
    }
    return t;
  }

  private static TargetInventoryState parseState(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("缺少处置目标状态（可售 / 残次 / 报废）");
    }
    try {
      return TargetInventoryState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "处置目标状态「" + raw + "」不认：只有可售（SELLABLE）/ 残次（DEFECTIVE）/ 报废（SCRAP）");
    }
  }

  /** 数量：NUMERIC(19,4) 口径，禁浮点。 */
  private static BigDecimal quantity4(BigDecimal value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("缺少" + label);
    }
    BigDecimal scaled = value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    if (scaled.compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException(label + "必须大于 0");
    }
    return scaled;
  }

  private static BigDecimal decimal(Record r, String column) {
    BigDecimal v = r.get(column, BigDecimal.class);
    return v == null ? BigDecimal.ZERO : v;
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    var out = new ArrayList<>(binds);
    out.addAll(List.of(more));
    return out;
  }

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  /** 业务时间的默认值（本方法只在调用方没给时兜底，正常路径由调用方给）。 */
  static OffsetDateTime defaultOccurredAt() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
