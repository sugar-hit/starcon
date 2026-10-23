package com.starcon.trade;

import static com.starcon.trade.ReturnInventoryDispositionModels.*;

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
 * 11.2.3.2 转可售/残次/报废。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「按质检结论把退货转入对应状态：可再销售转可售库存、
 * 残次转残次库存（可用于清仓）、报废则核销并计入损耗。」输入：「质检结论；处置决定；
 * {@code expected_version}。」输出：「库存状态变更分录；可售量更新；报废损耗记录。」
 * 以及红线：「<b>报废是不可逆的</b>，需审批与原因，不能由客服随手处理。」
 *
 * <p><b>本类是 V337 三张新表的写入所有者</b>
 * （return_inventory_disposition / return_inventory_disposition_event /
 * return_inventory_disposition_loss），对 11.2.3.1 的
 * {@code return_quality_disposition_instruction} 的唯一写入是**把它标成已消费**
 * （column 级授权 + 触发器，见迁移第五节）。
 *
 * <p><b>六条硬纪律</b>（照抄 11.2.3.1 的写法）：
 *
 * <ol>
 *   <li><b>每一条查询都显式带 {@code tenant_id} 谓词</b>，不依赖 RLS 当唯一防线。</li>
 *   <li><b>处置决定不能改判质检结论</b>：目标状态与数量都从 11.2.3.1 的指令读，
 *       DB 触发器在写入时刻真查一次比对（V337 守卫①）。</li>
 *   <li><b>来源库位与目标库位是真的问 warehouse 要的</b>：本服务读不到 warehouse 的库，
 *       所以候选库位走 {@link ReturnDispositionWarehousePort}，
 *       <b>不猜、不写死</b>；warehouse 不可达时建单直接失败并说明原因，
 *       而不是收下一张「反正执行时会发现」的处置单。</li>
 *   <li><b>报废不可逆</b>：审批 + 原因码 + 说明，提交人 ≠ 审批人，执行后整行冻结。</li>
 *   <li><b>报废损耗归 12 财务而 12 财务尚不存在</b>：本模块算自己的口径
 *       （原值 − 残值，由 DB 重算比对），<b>财务侧字段恒为 null</b>，
 *       <b>绝不填 0 冒充「这批报废货没产生任何损耗」</b>。</li>
 *   <li><b>时间绑定</b>：一律 {@code ?::timestamptz} + {@link OffsetDateTime}；
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s。</li>
 * </ol>
 */
@Service
public class ReturnDispositionService {

  /** 列表单页上限。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 数量刻度：NUMERIC(19,6)（与 V172 / V179 的退货侧口径一致）。 */
  private static final int QUANTITY_SCALE = 6;

  /** 金额刻度：NUMERIC(19,4)。 */
  private static final int MONEY_SCALE = 4;

  private final DSLContext sql;
  private final ReturnDispositionWarehousePort warehouse;

  public ReturnDispositionService(DSLContext sql, ReturnDispositionWarehousePort warehouse) {
    this.sql = sql;
    this.warehouse = warehouse;
  }

  // ================================================================ 能力声明

  /**
   * 能力与边界声明：职责句的四个动词逐块报可用性，下游逐个报 delivered 与归属模块。
   */
  public ReturnInventoryDispositionCapabilities capabilities() {
    return new ReturnInventoryDispositionCapabilities(
        List.of(
            new ReturnInventoryDispositionCapability(
                "按质检结论把退货转入对应状态（可售 / 残次 / 报废）",
                true,
                "11.2.3.2",
                "消费 11.2.3.1 的质量状态指令：目标状态与数量都由指令决定，处置单不能改判"),
            new ReturnInventoryDispositionCapability(
                "处置决定（单据 + 审批）",
                true,
                "11.2.3.2",
                "处置决定单 + 审批留痕；报废需独立权限码 + 原因码 + 说明，提交人 ≠ 审批人"),
            new ReturnInventoryDispositionCapability(
                "库存状态变更分录；可售量更新",
                true,
                "11.2.3.2 / warehouse",
                "本服务发执行命令，warehouse 在自己的事务里写 inventory_transaction + "
                    + "inventory_entry 并更新 bucket / balance（库存事实的写入所有权在 warehouse）"),
            new ReturnInventoryDispositionCapability(
                "报废损耗记录",
                true,
                "11.2.3.2 / 12 财务",
                "本模块记自己的口径（原值 − 残值，可审阅公式）；12 财务侧字段恒为 null 并标注归属模块，"
                    + "不填 0 冒充无损耗")),
        List.of(
            new ReturnInventoryDispositionBoundary(
                "不提供反执行端点",
                "11.2.3.2",
                "报废不可逆：已执行的分录不删不改，要冲正走 7.4.1.1 调整单并另附原因"),
            new ReturnInventoryDispositionBoundary(
                "不猜「货在哪个库位」",
                "11.2.3.2",
                "来源库位与目标库位都从 warehouse 的候选清单里选，并在 warehouse 侧二次校验"),
            new ReturnInventoryDispositionBoundary(
                "不代 12 财务入账",
                "12 财务",
                "12 财务服务尚存在：损耗只留位 + 标注归属模块，不建跨服务 FK")),
        List.of(
            new ReturnInventoryDispositionDownstream(
                "7.1.1.3 可用量",
                true,
                "warehouse",
                "转可售后可售量随之变化；执行回执记录执行前后的可售量作为口径证据"),
            new ReturnInventoryDispositionDownstream(
                "5.4 调价（残次品清仓）",
                false,
                "5.4",
                "残次库存由本模块产出，清仓定价属 5.4 的职责"),
            new ReturnInventoryDispositionDownstream(
                "12 财务（损耗）",
                false,
                "12 财务",
                "12 财务服务尚未交付：损耗记录已在本模块留好，财务侧字段恒为 null")));
  }

  // ================================================================ 待处置指令

  /**
   * 待处置的质量状态指令（11.2.3.1 产出，本模块消费）。
   *
   * <p>「还没被处置」= 指令仍是 PENDING ∧ 没有一张在途处置单（撤销 / 驳回的不算在途）。
   */
  public ReturnInventoryDispositionInstructionPage pendingInstructions(
      String skuCode, int page, int size) {
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    int offset = Math.max(0, page) * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("i.tenant_id = ?");
    binds.add(currentTenant());
    where.add("i.instruction_status = 'PENDING'");
    where.add(
        "not exists (select 1 from return_inventory_disposition d"
            + " where d.tenant_id = i.tenant_id and d.instruction_id = i.id"
            + " and d.status not in ('CANCELLED','REJECTED'))");
    if (skuCode != null && !skuCode.isBlank()) {
      where.add("i.sku_code ilike ?");
      binds.add("%" + skuCode.trim() + "%");
    }
    String clause = " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from return_quality_disposition_instruction i" + clause, binds.toArray())
            .get(0, Long.class);

    var rows = new ArrayList<ReturnInventoryDispositionInstructionRow>();
    for (Record r :
        sql.fetch(
            "select i.id,i.inspection_id,i.receipt_id,i.receipt_line_id,i.instruction_type,"
                + "i.target_inventory_state,i.sku_id,i.sku_code,i.sku_name,i.batch_no,i.qty,"
                + "i.salvage_amount,i.salvage_currency,i.original_value,i.grade_coefficient,"
                + "i.instruction_status,i.issued_at,i.issued_by"
                + " from return_quality_disposition_instruction i"
                + clause
                + " order by i.issued_at desc, i.id desc limit ? offset ?",
            append(binds, limit, (long) Math.max(0, page) * limit).toArray())) {
      rows.add(instructionRow(r));
    }
    return new ReturnInventoryDispositionInstructionPage(rows, total, Math.max(0, page), limit);
  }

  // ================================================================ 处置选项

  /**
   * 处置选项：候选来源库位（待检库存行）+ 候选目标库位。
   *
   * <p>数据来自 warehouse（库存事实的所在地），本服务只透传：
   * 取不到就如实说取不到，**不返回空列表冒充「没有候选」**。
   */
  public ReturnInventoryDispositionOptions options(UUID skuId, String targetState, String bearer) {
    var result = warehouse.options(skuId, targetState, bearer);
    if (!result.available()) {
      return new ReturnInventoryDispositionOptions(
          null, null, List.of(), List.of(), "候选库位取不到：" + result.note());
    }
    var buckets = new ArrayList<ReturnInventoryDispositionPendingBucket>();
    for (var b : result.pendingBuckets()) {
      buckets.add(
          new ReturnInventoryDispositionPendingBucket(
              b.bucketId(),
              b.warehouseId(),
              b.warehouseCode(),
              b.warehouseName(),
              b.locationId(),
              b.locationCode(),
              b.locationName(),
              b.lotId(),
              b.lotCode(),
              b.legalEntityId(),
              b.qualityStatusCode(),
              b.skuId(),
              b.skuCode(),
              b.skuName(),
              b.onHand(),
              b.reserved(),
              b.available()));
    }
    var locations = new ArrayList<ReturnInventoryDispositionTargetLocation>();
    for (var l : result.targetLocations()) {
      locations.add(
          new ReturnInventoryDispositionTargetLocation(
              l.locationId(),
              l.code(),
              l.name(),
              l.type(),
              l.warehouseId(),
              l.warehouseCode(),
              l.pickable(),
              l.status()));
    }
    return new ReturnInventoryDispositionOptions(
        result.targetInventoryState(), result.requiredLocationType(), buckets, locations, "");
  }

  // ================================================================ 列表与详情

  /** 处置单列表（真分页 + 多维筛选 + 跨全表汇总）。 */
  public ReturnInventoryDispositionPage listDispositions(
      String status, String targetState, String skuCode, String keyword, int page, int size) {
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    int offset = Math.max(0, page) * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("d.tenant_id = ?");
    binds.add(currentTenant());
    if (status != null && !status.isBlank()) {
      where.add("d.status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (targetState != null && !targetState.isBlank()) {
      where.add("d.target_inventory_state = ?");
      binds.add(targetState.trim().toUpperCase(Locale.ROOT));
    }
    if (skuCode != null && !skuCode.isBlank()) {
      where.add("d.sku_code ilike ?");
      binds.add("%" + skuCode.trim() + "%");
    }
    if (keyword != null && !keyword.isBlank()) {
      where.add("(d.disposition_no ilike ? or d.sku_code ilike ? or d.sku_name ilike ?)");
      String like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String clause = " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from return_inventory_disposition d" + clause, binds.toArray())
            .get(0, Long.class);

    var rows = new ArrayList<ReturnInventoryDispositionRow>();
    for (Record r :
        sql.fetch(
            "select d.*, i.instruction_status from return_inventory_disposition d"
                + " left join return_quality_disposition_instruction i"
                + " on i.tenant_id = d.tenant_id and i.id = d.instruction_id"
                + clause
                + " order by d.created_at desc, d.id desc limit ? offset ?",
            append(binds, limit, (long) Math.max(0, page) * limit).toArray())) {
      rows.add(dispositionRow(r));
    }

    Record s =
        sql.fetchOne(
            "select count(*) as total_count,"
                + " count(*) filter (where status='DRAFT') as draft_count,"
                + " count(*) filter (where status='PENDING_APPROVAL') as pending_count,"
                + " count(*) filter (where status='APPROVED') as approved_count,"
                + " count(*) filter (where status='EXECUTED') as executed_count,"
                + " count(*) filter (where status='EXECUTION_FAILED') as failed_count,"
                + " count(*) filter (where status='EXECUTED' and target_inventory_state='SCRAP')"
                + "   as scrap_count,"
                + " coalesce(sum(qty) filter (where status='EXECUTED'"
                + "   and target_inventory_state='SELLABLE'),0) as executed_sellable,"
                + " coalesce(sum(qty) filter (where status='EXECUTED'"
                + "   and target_inventory_state='DEFECTIVE'),0) as executed_defective,"
                + " coalesce(sum(qty) filter (where status='EXECUTED'"
                + "   and target_inventory_state='SCRAP'),0) as executed_scrap"
                + " from return_inventory_disposition where tenant_id=?",
            currentTenant());
    var summary =
        new ReturnInventoryDispositionSummary(
            s.get("total_count", Long.class),
            s.get("draft_count", Long.class),
            s.get("pending_count", Long.class),
            s.get("approved_count", Long.class),
            s.get("executed_count", Long.class),
            s.get("failed_count", Long.class),
            s.get("scrap_count", Long.class),
            s.get("executed_sellable", BigDecimal.class),
            s.get("executed_defective", BigDecimal.class),
            s.get("executed_scrap", BigDecimal.class));

    return new ReturnInventoryDispositionPage(rows, total, Math.max(0, page), limit, summary);
  }

  /** 处置单详情：头 + 留痕 + 损耗记录 + 当前可执行动作 + 上游指令。 */
  public ReturnInventoryDispositionDetail detail(UUID id) {
    Record head = requireDisposition(id);
    var events = new ArrayList<ReturnInventoryDispositionEventRow>();
    for (Record r :
        sql.fetch(
            "select id,event_type,from_status,to_status,target_inventory_state,qty,reason_note,"
                + "actor_id,occurred_at from return_inventory_disposition_event"
                + " where tenant_id=? and disposition_id=? order by occurred_at, id",
            currentTenant(),
            id)) {
      events.add(
          new ReturnInventoryDispositionEventRow(
              r.get("id", UUID.class),
              r.get("event_type", String.class),
              r.get("from_status", String.class),
              r.get("to_status", String.class),
              r.get("target_inventory_state", String.class),
              r.get("qty", BigDecimal.class),
              r.get("reason_note", String.class),
              r.get("actor_id", UUID.class),
              r.get("occurred_at", OffsetDateTime.class)));
    }

    ReturnInventoryDispositionLossRow loss = null;
    Record l =
        sql.fetchOne(
            "select id,disposition_id,instruction_id,sku_id,sku_code,scrap_qty,original_value,"
                + "salvage_amount,salvage_currency,proposed_loss_amount,finance_loss_amount,"
                + "finance_currency,finance_reference,finance_confirmed_at,finance_owner_module,"
                + "loss_status,recorded_by,recorded_at,note"
                + " from return_inventory_disposition_loss where tenant_id=? and disposition_id=?",
            currentTenant(),
            id);
    if (l != null) {
      loss =
          new ReturnInventoryDispositionLossRow(
              l.get("id", UUID.class),
              l.get("disposition_id", UUID.class),
              l.get("instruction_id", UUID.class),
              l.get("sku_id", UUID.class),
              l.get("sku_code", String.class),
              l.get("scrap_qty", BigDecimal.class),
              l.get("original_value", BigDecimal.class),
              l.get("salvage_amount", BigDecimal.class),
              l.get("salvage_currency", String.class),
              l.get("proposed_loss_amount", BigDecimal.class),
              l.get("finance_loss_amount", BigDecimal.class),
              l.get("finance_currency", String.class),
              l.get("finance_reference", String.class),
              l.get("finance_confirmed_at", OffsetDateTime.class),
              l.get("finance_owner_module", String.class),
              l.get("loss_status", String.class),
              l.get("recorded_by", UUID.class),
              l.get("recorded_at", OffsetDateTime.class),
              l.get("note", String.class));
    }

    ReturnInventoryDispositionInstructionRow instruction = null;
    Record i =
        sql.fetchOne(
            "select id,inspection_id,receipt_id,receipt_line_id,instruction_type,"
                + "target_inventory_state,sku_id,sku_code,sku_name,batch_no,qty,salvage_amount,"
                + "salvage_currency,original_value,grade_coefficient,instruction_status,issued_at,issued_by"
                + " from return_quality_disposition_instruction where tenant_id=? and id=?",
            currentTenant(),
            head.get("instruction_id", UUID.class));
    if (i != null) {
      instruction = instructionRow(i);
    }

    return new ReturnInventoryDispositionDetail(
        dispositionRow(head), events, loss, actions(head.get("status", String.class)), instruction);
  }

  // ================================================================ 建单

  /**
   * 建处置决定单（草稿）。
   *
   * <p>目标状态与数量**从质检指令读**，调用方改了也会被拒（处置决定不能改判质检结论）。
   */
  public ReturnInventoryDispositionDetail create(
      ReturnInventoryDispositionCreate request, UUID actorId, String bearer) {
    if (request == null || request.instructionId() == null) {
      throw new IllegalArgumentException("缺少质量状态指令 id：处置必须挂在一条质检结论上");
    }
    Record instruction = requireInstruction(request.instructionId());
    if (!"PENDING".equals(instruction.get("instruction_status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这条质量状态指令已被消费，不能再对同一条指令重复处置");
    }
    String targetState = instruction.get("target_inventory_state", String.class);
    BigDecimal qty = quantity6(instruction.get("qty", BigDecimal.class), "质检结论数量");
    if (request.qty() != null && request.qty().compareTo(qty) != 0) {
      throw new IllegalArgumentException(
          "处置数量 " + request.qty() + " 与质检结论的数量 " + qty
              + " 不相等：一批退货必须被全部处置掉（处置一半意味着另一半永远没有结论）");
    }
    String reasonNote = fit(request.reasonNote(), 2000, "处置理由");
    if (reasonNote.isBlank()) {
      throw new IllegalArgumentException("处置理由必填：处置决定是一份有理由的决定");
    }
    String scrapReason = fit(request.scrapReasonCode(), 32, "报废原因码");
    if ("SCRAP".equals(targetState) && scrapReason.isBlank()) {
      throw new IllegalArgumentException("报废必须给损耗原因码：报废是不可逆的，要写清为什么报");
    }
    if (!"SCRAP".equals(targetState) && !scrapReason.isBlank()) {
      throw new IllegalArgumentException("只有报废才填损耗原因码");
    }
    if (!"SCRAP".equals(targetState) && request.targetLocationId() == null) {
      throw new IllegalArgumentException(
          "转可售 / 转残次必须指定目标库位：库位类型与质量状态一一对应"
              + "（可售进 STORAGE 位、残次进 DEFECTIVE 位）");
    }
    if ("SCRAP".equals(targetState) && request.targetLocationId() != null) {
      throw new IllegalArgumentException("报废核销的货不在任何库位上，不得指定目标库位");
    }
    if (request.sourceBucketId() == null) {
      throw new IllegalArgumentException("必须指定来源库位：这批货现在在哪，是仓库的事实，不该由售后单据猜");
    }

    UUID skuId = instruction.get("sku_id", UUID.class);
    var source = validateWarehouseSide(request.sourceBucketId(), request.targetLocationId(), skuId, targetState, bearer);

    long next =
        sql.fetchOne("select nextval('return_inventory_disposition_no_seq')").get(0, Long.class);
    UUID id = Ids.next();
    sql.execute(
        "insert into return_inventory_disposition(id,tenant_id,disposition_no,instruction_id,inspection_id,"
            + "receipt_id,receipt_line_id,instruction_type,target_inventory_state,sku_id,sku_code,sku_name,"
            + "batch_no,qty,source_bucket_id,source_warehouse_id,source_warehouse_code,"
            + "source_location_id,source_location_code,source_lot_id,source_lot_code,"
            + "source_legal_entity_id,source_quality_status_code,target_location_id,target_location_code,"
            + "reason_code,reason_note,scrap_reason_code,status,version,created_by,created_at,updated_at)"
            + " values(?::uuid,?::uuid,?,?::uuid,?::uuid,?::uuid,?::uuid,?,?,?::uuid,?,?,?,?,"
            + "?::uuid,?::uuid,?,?::uuid,?,?::uuid,?,?::uuid,?,?::uuid,?,"
            + "?,?,?,'DRAFT',0,?::uuid,now(),now())",
        id,
        currentTenant(),
        "RDP" + next,
        instruction.get("id", UUID.class),
        instruction.get("inspection_id", UUID.class),
        instruction.get("receipt_id", UUID.class),
        instruction.get("receipt_line_id", UUID.class),
        instruction.get("instruction_type", String.class),
        targetState,
        skuId,
        instruction.get("sku_code", String.class),
        instruction.get("sku_name", String.class),
        instruction.get("batch_no", String.class),
        qty,
        request.sourceBucketId(),
        source.warehouseId(),
        source.warehouseCode(),
        source.locationId(),
        source.locationCode(),
        source.lotId(),
        source.lotCode(),
        source.legalEntityId(),
        source.qualityStatusCode(),
        request.targetLocationId(),
        source.targetLocationCode(),
        reasonCodeFor(targetState),
        reasonNote,
        scrapReason,
        actorId);
    return detail(id);
  }

  /**
   * 改草稿要素（只有 DRAFT 可改）。
   *
   * <p>提交之后要素冻结 —— 审批意见是对着这些要素写的。
   */
  public ReturnInventoryDispositionDetail update(
      UUID id, ReturnInventoryDispositionUpdate request, UUID actorId, String bearer) {
    Record head = lockDisposition(id);
    if (!"DRAFT".equals(head.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "处置单当前是「" + label(head.get("status", String.class)) + "」，只有草稿能改要素");
    }
    checkVersion(head, request == null ? null : request.expectedVersion());

    UUID sourceBucketId =
        request.sourceBucketId() == null
            ? head.get("source_bucket_id", UUID.class)
            : request.sourceBucketId();
    UUID targetLocationId =
        request.targetLocationId() == null
            ? head.get("target_location_id", UUID.class)
            : request.targetLocationId();
    UUID skuId = head.get("sku_id", UUID.class);
    String targetState = head.get("target_inventory_state", String.class);
    var source = validateWarehouseSide(sourceBucketId, targetLocationId, skuId, targetState, bearer);

    String reasonNote =
        request.reasonNote() == null
            ? head.get("reason_note", String.class)
            : fit(request.reasonNote(), 2000, "处置理由");
    if (reasonNote.isBlank()) {
      throw new IllegalArgumentException("处置理由必填：处置决定是一份有理由的决定");
    }
    String scrapReason =
        request.scrapReasonCode() == null
            ? head.get("scrap_reason_code", String.class)
            : fit(request.scrapReasonCode(), 32, "报废原因码");
    if ("SCRAP".equals(targetState) && scrapReason.isBlank()) {
      throw new IllegalArgumentException("报废必须给损耗原因码：报废是不可逆的，要写清为什么报");
    }
    if (!"SCRAP".equals(targetState) && !scrapReason.isBlank()) {
      throw new IllegalArgumentException("只有报废才填损耗原因码");
    }
    if (!"SCRAP".equals(targetState) && targetLocationId == null) {
      throw new IllegalArgumentException("转可售 / 转残次必须指定目标库位");
    }
    if ("SCRAP".equals(targetState) && targetLocationId != null) {
      throw new IllegalArgumentException("报废核销的货不在任何库位上，不得指定目标库位");
    }

    sql.execute(
        "update return_inventory_disposition set source_bucket_id=?::uuid,source_warehouse_id=?::uuid,"
            + "source_warehouse_code=?,source_location_id=?::uuid,source_location_code=?,"
            + "source_lot_id=?::uuid,source_lot_code=?,source_legal_entity_id=?::uuid,"
            + "source_quality_status_code=?,target_location_id=?::uuid,target_location_code=?,"
            + "reason_note=?,scrap_reason_code=?,version=version+1,updated_at=now()"
            + " where tenant_id=? and id=?::uuid",
        sourceBucketId,
        source.warehouseId(),
        source.warehouseCode(),
        source.locationId(),
        source.locationCode(),
        source.lotId(),
        source.lotCode(),
        source.legalEntityId(),
        source.qualityStatusCode(),
        targetLocationId,
        source.targetLocationCode(),
        reasonNote,
        scrapReason,
        currentTenant(),
        id);
    return detail(id);
  }

  // ================================================================ 状态流转

  /** 提交待审。 */
  public ReturnInventoryDispositionDetail submit(UUID id, ReturnInventoryDispositionSubmit request, UUID actorId) {
    Record head = lockDisposition(id);
    if (!"DRAFT".equals(head.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "处置单当前是「" + label(head.get("status", String.class)) + "」，只有草稿能提交待审");
    }
    checkVersion(head, request == null ? null : request.expectedVersion());
    if (head.get("reason_note", String.class) == null
        || head.get("reason_note", String.class).isBlank()) {
      throw new IllegalArgumentException("处置理由必填：处置决定是一份有理由的决定");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update return_inventory_disposition set status='PENDING_APPROVAL',submitted_by=?::uuid,"
            + "submitted_at=?::timestamptz,version=version+1,updated_at=now()"
            + " where tenant_id=? and id=?::uuid",
        actorId,
        now,
        currentTenant(),
        id);
    return detail(id);
  }

  /**
   * 批准。
   *
   * <p><b>提交人 ≠ 审批人</b>（职责分离，蓝图「不能由客服随手处理」的落点）；
   * 审批意见必填。两道都由 DB 触发器再判一次。
   */
  public ReturnInventoryDispositionDetail approve(UUID id, ReturnInventoryDispositionDecision request, UUID actorId) {
    Record head = lockDisposition(id);
    if (!"PENDING_APPROVAL".equals(head.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "处置单当前是「" + label(head.get("status", String.class)) + "」，只有待审的单能批准");
    }
    checkVersion(head, request == null ? null : request.expectedVersion());
    String note = fit(request == null ? null : request.decisionNote(), 2000, "审批意见");
    if (note.isBlank()) {
      throw new IllegalArgumentException("审批意见必填：报废是不可逆的，必须写清为什么");
    }
    UUID submittedBy = head.get("submitted_by", UUID.class);
    if (submittedBy != null && submittedBy.equals(actorId)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "提交人不能审批自己提交的处置单（职责分离）");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update return_inventory_disposition set status='APPROVED',decided_by=?::uuid,"
            + "decided_at=?::timestamptz,decision_note=?,version=version+1,updated_at=now()"
            + " where tenant_id=? and id=?::uuid",
        actorId,
        now,
        note,
        currentTenant(),
        id);
    return detail(id);
  }

  /** 驳回（意见必填）。 */
  public ReturnInventoryDispositionDetail reject(UUID id, ReturnInventoryDispositionDecision request, UUID actorId) {
    Record head = lockDisposition(id);
    if (!"PENDING_APPROVAL".equals(head.get("status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "处置单当前是「" + label(head.get("status", String.class)) + "」，只有待审的单能驳回");
    }
    checkVersion(head, request == null ? null : request.expectedVersion());
    String note = fit(request == null ? null : request.decisionNote(), 2000, "驳回意见");
    if (note.isBlank()) {
      throw new IllegalArgumentException("驳回意见必填");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update return_inventory_disposition set status='REJECTED',decided_by=?::uuid,"
            + "decided_at=?::timestamptz,decision_note=?,version=version+1,updated_at=now()"
            + " where tenant_id=? and id=?::uuid",
        actorId,
        now,
        note,
        currentTenant(),
        id);
    return detail(id);
  }

  /** 撤销（还没执行过 —— 执行了就是不可逆的，不在这里撤）。 */
  public ReturnInventoryDispositionDetail cancel(UUID id, ReturnInventoryDispositionCancel request, UUID actorId) {
    Record head = lockDisposition(id);
    String status = head.get("status", String.class);
    if (status.equals("EXECUTED") || status.equals("REJECTED") || status.equals("CANCELLED")) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "处置单当前是「" + label(status) + "」，是终态不可撤销");
    }
    checkVersion(head, request == null ? null : request.expectedVersion());
    String reason = fit(request == null ? null : request.cancelReason(), 2000, "撤销理由");
    if (reason.isBlank()) {
      throw new IllegalArgumentException("撤销理由必填");
    }
    sql.execute(
        "update return_inventory_disposition set status='CANCELLED',cancel_reason=?,version=version+1,"
            + "updated_at=now() where tenant_id=? and id=?::uuid",
        reason,
        currentTenant(),
        id);
    return detail(id);
  }

  /**
   * 执行：真正动库存的那一步。
   *
   * <p>流程：调 warehouse 执行 → 成功则把处置单标成 EXECUTED + 记报废损耗 + 消费质检指令；
   * 失败则标成 EXECUTION_FAILED 并写明失败原因（<b>绝不标成已执行</b>）。
   */
  public ReturnInventoryDispositionDetail execute(
      UUID id, ReturnInventoryDispositionExecute request, UUID actorId, String bearer) {
    Record head = lockDisposition(id);
    String status = head.get("status", String.class);
    if (!"APPROVED".equals(status) && !"EXECUTION_FAILED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "处置单当前是「" + label(status) + "」，只有已批准的单能执行"
              + "（报废不可逆，没批就动库存等于绕过审批）");
    }
    checkVersion(head, request == null ? null : request.expectedVersion());
    Record instruction = requireInstruction(head.get("instruction_id", UUID.class));
    if (!"PENDING".equals(instruction.get("instruction_status", String.class))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这条质量状态指令已被消费，不能重复执行");
    }

    OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
    var outcome =
        warehouse.execute(
            new ReturnDispositionWarehousePort.ExecuteCommand(
                id,
                head.get("disposition_no", String.class),
                instruction.get("id", UUID.class),
                head.get("inspection_id", UUID.class),
                head.get("target_inventory_state", String.class),
                head.get("sku_id", UUID.class),
                head.get("sku_code", String.class),
                head.get("sku_name", String.class),
                head.get("batch_no", String.class),
                head.get("qty", BigDecimal.class),
                head.get("source_bucket_id", UUID.class),
                head.get("target_location_id", UUID.class),
                occurredAt),
            bearer);

    if (!outcome.executed()) {
      // ⚠️ **失败也要落库，而且不能抛错**：抛出去会让整个事务回滚 ——
      //   「执行失败」这条记录会跟着一起没了，货没动、账没动、单子还停在「已批」，
      //   下一班人看到的是一张「已批待执行」的单，既不知道刚才发生过什么，也不知道该不该重试。
      //   ⇒ 这里只把状态落成 EXECUTION_FAILED 并写明原因，由调用方从返回值里读。
      sql.execute(
          "update return_inventory_disposition set status='EXECUTION_FAILED',execution_failure_reason=?,"
              + "version=version+1,updated_at=now() where tenant_id=? and id=?::uuid",
          fit(outcome.failureReason(), 2000, "失败原因"),
          currentTenant(),
          id);
      return detail(id);
    }

    sql.execute(
        "update return_inventory_disposition set status='EXECUTED',executed_by=?::uuid,"
            + "executed_at=?::timestamptz,execution_transaction_id=?::uuid,execution_bucket_id=?::uuid,"
            + "execution_failure_reason='',version=version+1,updated_at=now()"
            + " where tenant_id=? and id=?::uuid",
        actorId,
        occurredAt,
        outcome.inventoryTransactionId(),
        outcome.targetBucketId(),
        currentTenant(),
        id);

    if ("SCRAP".equals(head.get("target_inventory_state", String.class))) {
      recordScrapLoss(head, instruction, actorId, occurredAt);
    }

    // 消费质检指令：DB 触发器会检查「必须有一张已执行的处置单顶着这条指令」。
    sql.execute(
        "update return_quality_disposition_instruction set instruction_status='CONSUMED'"
            + " where tenant_id=? and id=?::uuid and instruction_status='PENDING'",
        currentTenant(),
        instruction.get("id", UUID.class));

    return detail(id);
  }

  // ================================================================ 报废损耗

  /**
   * 记报废损耗（本模块口径：原值 − 残值；DB 触发器重算比对）。
   *
   * <p><b>12 财务侧的字段恒为 null</b>：12 财务服务尚不存在，
   * 这里只留位 + 标注归属模块，<b>不填 0 冒充「这批报废货没产生任何损耗」</b>。
   */
  private void recordScrapLoss(Record head, Record instruction, UUID actorId, OffsetDateTime at) {
    BigDecimal originalValue = instruction.get("original_value", BigDecimal.class);
    BigDecimal salvage = instruction.get("salvage_amount", BigDecimal.class);
    BigDecimal loss = originalValue.subtract(salvage).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    sql.execute(
        "insert into return_inventory_disposition_loss(id,tenant_id,disposition_id,instruction_id,"
            + "sku_id,sku_code,scrap_qty,original_value,salvage_amount,salvage_currency,"
            + "proposed_loss_amount,finance_loss_amount,finance_currency,finance_reference,"
            + "finance_confirmed_at,finance_owner_module,loss_status,recorded_by,recorded_at,note)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,null,null,null,null,"
            + "'12 财务','PENDING_FINANCE',?::uuid,?::timestamptz,?)",
        Ids.next(),
        currentTenant(),
        head.get("id", UUID.class),
        head.get("instruction_id", UUID.class),
        head.get("sku_id", UUID.class),
        head.get("sku_code", String.class),
        head.get("qty", BigDecimal.class),
        originalValue,
        salvage,
        instruction.get("salvage_currency", String.class),
        loss,
        actorId,
        at,
        "报废核销：原值 " + originalValue + " − 残值 " + salvage + " = 损耗 " + loss
            + "；财务侧字段待 12 财务确认");
  }

  // ================================================================ 跨服务校验

  /** 来源库位与目标库位的真数据（来自 warehouse）。 */
  private record SourceSide(
      UUID warehouseId,
      String warehouseCode,
      UUID locationId,
      String locationCode,
      UUID lotId,
      String lotCode,
      UUID legalEntityId,
      String qualityStatusCode,
      String targetLocationCode) {}

  /**
   * 向 warehouse 校验来源库位与目标库位，并取回快照。
   *
   * <p><b>为什么必须真的问</b>：本服务读不到 warehouse 的库，
   * 所以「这个库位上真有这批待检货吗」「这个库位类型对不对」只能问 warehouse。
   * warehouse 不可达 ⇒ 建单直接失败并说明原因（不收下一张「反正执行时会发现」的处置单）。
   */
  private SourceSide validateWarehouseSide(
      UUID sourceBucketId,
      UUID targetLocationId,
      UUID skuId,
      String targetState,
      String bearer) {
    var result = warehouse.options(skuId, targetState, bearer);
    if (!result.available()) {
      throw new IllegalStateException(
          "取不到候选库位，没法建处置单：" + result.note()
              + "（来源库位与目标库位是 warehouse 的事实，本服务不猜）");
    }
    ReturnDispositionWarehousePort.PendingBucket source = null;
    for (var b : result.pendingBuckets()) {
      if (b.bucketId().equals(sourceBucketId)) {
        source = b;
        break;
      }
    }
    if (source == null) {
      throw new IllegalArgumentException(
          "来源库位不在该 SKU 的待检库存清单里：它要么不是待检库位，要么已经不在库上了"
              + "（刷新候选清单后重选）");
    }
    if (skuId != null && !skuId.equals(source.skuId())) {
      throw new IllegalArgumentException("来源库位上的 SKU 与质检结论的 SKU 不是同一个");
    }
    if (source.onHand() == null || source.onHand().compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("来源库位上已经没有货了（在手量为 0）");
    }
    String targetLocationCode = "";
    if (!"SCRAP".equals(targetState)) {
      ReturnDispositionWarehousePort.TargetLocation target = null;
      for (var l : result.targetLocations()) {
        if (l.locationId().equals(targetLocationId)) {
          target = l;
          break;
        }
      }
      if (target == null) {
        throw new IllegalArgumentException(
            "目标库位不在候选清单里：库位类型必须与目标质量状态匹配"
                + "（可售进 STORAGE 位、残次进 DEFECTIVE 位）");
      }
      if (!target.warehouseId().equals(source.warehouseId())) {
        throw new IllegalArgumentException("目标库位与来源库位不在同一个仓库：不能跨仓处置");
      }
      targetLocationCode = target.code();
    }
    return new SourceSide(
        source.warehouseId(),
        source.warehouseCode(),
        source.locationId(),
        source.locationCode(),
        source.lotId(),
        source.lotCode(),
        source.legalEntityId(),
        source.qualityStatusCode(),
        targetLocationCode);
  }

  // ================================================================ 内部

  /** 取一张处置单（带租户谓词；查不到就是查不到，不泄露存在性）。 */
  private Record requireDisposition(UUID id) {
    Record r =
        sql.fetchOne(
            "select d.*, i.instruction_status from return_inventory_disposition d"
                + " left join return_quality_disposition_instruction i"
                + " on i.tenant_id = d.tenant_id and i.id = d.instruction_id"
                + " where d.tenant_id=? and d.id=?::uuid",
            currentTenant(),
            id);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "处置单不存在或无权访问");
    }
    return r;
  }

  /** 取一张处置单并加行锁（写操作专用）。 */
  private Record lockDisposition(UUID id) {
    Record r =
        sql.fetchOne(
            "select * from return_inventory_disposition where tenant_id=? and id=?::uuid for update",
            currentTenant(),
            id);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "处置单不存在或无权访问");
    }
    return r;
  }

  /** 取一条质量状态指令。 */
  private Record requireInstruction(UUID id) {
    Record r =
        sql.fetchOne(
            "select * from return_quality_disposition_instruction where tenant_id=? and id=?::uuid",
            currentTenant(),
            id);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "质量状态指令不存在或无权访问");
    }
    return r;
  }

  /** 乐观锁校验：expected_version 必填且必须与当前一致。 */
  private void checkVersion(Record head, Long expectedVersion) {
    if (expectedVersion == null) {
      throw new IllegalArgumentException("缺少 expectedVersion：写操作必须带上乐观锁版本号");
    }
    long actual = head.get("version", Long.class);
    if (actual != expectedVersion) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "处置单已被他人修改（当前版本 " + actual + "，你的版本 " + expectedVersion + "），请刷新后重试");
    }
  }

  /** 当前可执行动作。 */
  private List<ReturnInventoryDispositionAction> actions(String status) {
    var out = new ArrayList<ReturnInventoryDispositionAction>();
    boolean draft = "DRAFT".equals(status);
    boolean pending = "PENDING_APPROVAL".equals(status);
    boolean approved = "APPROVED".equals(status);
    boolean failed = "EXECUTION_FAILED".equals(status);
    out.add(
        new ReturnInventoryDispositionAction(
            "SUBMIT",
            "提交待审",
            draft,
            "11.2.3.2",
            draft ? "草稿可以提交待审" : "只有草稿能提交待审"));
    out.add(
        new ReturnInventoryDispositionAction(
            "APPROVE",
            "批准",
            pending,
            "11.2.3.2",
            pending
                ? "批准需独立权限码 trade.aftersales.approve + 必填审批意见，且提交人 ≠ 审批人"
                : "只有待审的单能批准"));
    out.add(
        new ReturnInventoryDispositionAction(
            "REJECT",
            "驳回",
            pending,
            "11.2.3.2",
            pending ? "驳回意见必填" : "只有待审的单能驳回"));
    out.add(
        new ReturnInventoryDispositionAction(
            "EXECUTE",
            "执行库存状态变更",
            approved || failed,
            "11.2.3.2",
            approved || failed
                ? "执行会真正改库存（报废即核销，不可逆）"
                : "只有已批准的单能执行"));
    out.add(
        new ReturnInventoryDispositionAction(
            "CANCEL",
            "撤销",
            draft || pending || approved || failed,
            "11.2.3.2",
            draft || pending || approved || failed
                ? "还没动过库存，可以撤销（撤销后同一条指令可重新处置）"
                : "已执行 / 已驳回 / 已撤销是终态"));
    return out;
  }

  private ReturnInventoryDispositionRow dispositionRow(Record r) {
    String status = r.get("status", String.class);
    String targetState = r.get("target_inventory_state", String.class);
    String scrapReason = r.get("scrap_reason_code", String.class);
    return new ReturnInventoryDispositionRow(
        r.get("id", UUID.class),
        r.get("disposition_no", String.class),
        r.get("instruction_id", UUID.class),
        r.get("inspection_id", UUID.class),
        r.get("receipt_id", UUID.class),
        targetState,
        stateLabel(targetState),
        r.get("instruction_type", String.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("batch_no", String.class),
        r.get("qty", BigDecimal.class),
        r.get("source_bucket_id", UUID.class),
        r.get("source_warehouse_id", UUID.class),
        r.get("source_warehouse_code", String.class),
        r.get("source_location_id", UUID.class),
        r.get("source_location_code", String.class),
        r.get("source_lot_id", UUID.class),
        r.get("source_lot_code", String.class),
        r.get("target_location_id", UUID.class),
        r.get("target_location_code", String.class),
        r.get("reason_note", String.class),
        scrapReason,
        scrapReasonLabel(scrapReason),
        status,
        label(status),
        r.get("version", Long.class),
        r.get("submitted_by", UUID.class),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("decided_by", UUID.class),
        r.get("decided_at", OffsetDateTime.class),
        r.get("decision_note", String.class),
        r.get("executed_by", UUID.class),
        r.get("executed_at", OffsetDateTime.class),
        r.get("execution_transaction_id", UUID.class),
        r.get("execution_bucket_id", UUID.class),
        r.get("execution_failure_reason", String.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        "CONSUMED".equals(r.get("instruction_status", String.class)));
  }

  private ReturnInventoryDispositionInstructionRow instructionRow(Record r) {
    String targetState = r.get("target_inventory_state", String.class);
    return new ReturnInventoryDispositionInstructionRow(
        r.get("id", UUID.class),
        r.get("inspection_id", UUID.class),
        r.get("receipt_id", UUID.class),
        r.get("receipt_line_id", UUID.class),
        r.get("instruction_type", String.class),
        targetState,
        stateLabel(targetState),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("batch_no", String.class),
        r.get("qty", BigDecimal.class),
        r.get("salvage_amount", BigDecimal.class),
        r.get("salvage_currency", String.class),
        r.get("original_value", BigDecimal.class),
        r.get("grade_coefficient", BigDecimal.class),
        r.get("instruction_status", String.class),
        r.get("issued_at", OffsetDateTime.class),
        r.get("issued_by", UUID.class),
        "CONSUMED".equals(r.get("instruction_status", String.class)));
  }

  /** 目标库存状态 → 处置理由码（判定词表：RESALABLE / DEFECTIVE / SCRAPPED）。 */
  static String reasonCodeFor(String targetState) {
    return switch (targetState) {
      case "SELLABLE" -> "RESALABLE";
      case "DEFECTIVE" -> "DEFECTIVE";
      default -> "SCRAPPED";
    };
  }

  private static String label(String status) {
    if (status == null) {
      return "";
    }
    for (ReturnInventoryDispositionStatus s : ReturnInventoryDispositionStatus.values()) {
      if (s.name().equals(status)) {
        return s.label();
      }
    }
    return status;
  }

  private static String stateLabel(String state) {
    if (state == null) {
      return "";
    }
    for (TargetInventoryState s : TargetInventoryState.values()) {
      if (s.name().equals(state)) {
        return s.label();
      }
    }
    return state;
  }

  private static String scrapReasonLabel(String code) {
    if (code == null || code.isBlank()) {
      return "";
    }
    for (ScrapReasonCode c : ScrapReasonCode.values()) {
      if (c.name().equals(code)) {
        return c.label();
      }
    }
    return code;
  }

  private static BigDecimal quantity6(BigDecimal value, String label) {
    if (value == null) {
      throw new IllegalStateException(label + "为空：质检结论的记录不完整");
    }
    return value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  /** 文本裁剪 + 长度校验。 */
  private static String fit(String value, int max, String label) {
    String v = value == null ? "" : value.trim();
    if (v.length() > max) {
      throw new IllegalArgumentException(label + "超长（最多 " + max + " 字）");
    }
    return v;
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    var out = new ArrayList<>(binds);
    out.addAll(List.of(more));
    return out;
  }

  private UUID currentTenant() {
    return UUID.fromString(sql.fetchOne("select current_setting('app.current_tenant')").get(0, String.class));
  }
}
