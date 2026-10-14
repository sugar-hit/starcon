package com.starcon.warehouse;

import static com.starcon.warehouse.PickTaskModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
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
 * 8.3.2.1 拣货任务：生成（路径优化）、执行（实拣）、异常上报。
 *
 * <p>蓝图 08 卷 8.3.2.1 的四问逐条对应：
 *
 * <ul>
 *   <li><b>职责</b>：按库位顺序排序优化路径 → {@link #generate}；记录实际拣货库位/批次/
 *       序列号/数量 → {@link #recordPick}；异常（缺货、破损）上报 → {@link #reportException}。
 *   <li><b>输入</b>：出库单明细 → 读 {@code outbound_order_line}（V72）；库存分配结果
 *       → 读 {@code stock_bucket.available}（V2）；作业人员或 PDA 扫码 → {@link #assign}
 *       （接单）与 {@link #recordPick}（{@code pickedAt} 走作业端时钟，不是 DB 的 now()）。
 *   <li><b>输出</b>：拣货任务与拣货明细（实际库位/批次/序列号）→ 六张表；
 *       缺货异常记录 → {@code pick_task_exception}。
 *   <li><b>关系</b>：上游 8.3.1.2 的交接请求（{@code outbound_pick_request}）；
 *       下游 8.3.3.1 复核（完成时把出库单推到「待复核」）、7 库存（本模块只搬库位、
 *       <b>不动 balance、不释放预占</b>）。
 * </ul>
 *
 * <p><b>拣货对库存做了什么</b>（文件头第 2 节的完整论证在 V110 里）：把 on_hand 与
 * reserved 从取货库位搬到打包区，两侧属于<b>同一条 balance</b>（balance 的六元组
 * 不含库位与批次），所以 balance 层什么都没发生 —— 既不改 {@code inventory_balance}，
 * 也不写 {@code inventory_entry}（它的 CHECK 根本不允许 delta 为 0 的分录，
 * 而写两条 ±q 互相抵消只会把守恒式变成噪音）。预占<b>跟着货走</b>而不是被释放：
 * 蓝图关系段写明「库存仍占用未释放」，货被本单预占了就该跟着走到打包区。
 *
 * <p><b>路径优化不是「按编码排」</b>，是三件事叠加，缺一不可：
 *
 * <ol>
 *   <li><b>排序</b>：按库位树（8.1.2.1 的 {@code parent_id}）算出的层级路径升序，
 *       同层按库位编码。与 8.1.2.1 出树（{@code order by path}）逐字一致 ——
 *       拣货单上的行走顺序与仓库现场的层级认知是同一套。
 *   <li><b>收敛</b>：同一 SKU 散在多个库位时，<b>优先在一个库位一次拣齐</b>，
 *       拣不齐才拆到下一个库位，拆出来的每段各占一行、各占一个序号。
 *       这一条是 8.5.2.1「SKU 散在多库位」那条建议（ownerModule=8.3.2.1）的落地。
 *   <li><b>避开不可拣</b>：优先可拣且 ACTIVE 的库位；只有在没有可拣来源时才退到
 *       不可拣库位，并把那一行 {@code nonPickableSource=true} 顶出来。
 *       这是 8.5.2.1「高频货在不可拣库位」的落地 —— 静默从一个不可拣位取货
 *       等于让作业员白跑一趟，或者让他在待检/冻结位上动手。
 * </ol>
 *
 * <p><b>跨服务边界</b>：SKU 是 catalog 的逻辑 id，{@code sku_ref} 只是本服务的登记副本，
 * 拣货不跨库回查商品中心；7.2.1.2 的预占分配在本服务里读到的是
 * {@code stock_bucket.reserved}，本模块<b>只搬不释放</b>，不写预占相关表。
 */
@Service
public class PickTaskService {

  private static final int MAX_PAGE_SIZE = 200;

  /** 排序白名单。与 WarehouseDetailService / DisassemblyOrderService 同一纪律。 */
  private static final List<String> SORTS =
      List.of("CREATED_AT", "TASK_CODE", "PLANNED_QUANTITY", "PICKED_QUANTITY", "STATUS");

  /** 状态词表，与 V110 的 CHECK 逐字一致。 */
  private static final List<String> STATUSES =
      List.of("CREATED", "IN_PROGRESS", "COMPLETED", "CANCELLED");

  /** 异常种类词表，与 V110 的 CHECK 逐字一致。 */
  private static final List<String> EXCEPTION_KINDS =
      List.of("SHORTAGE", "DAMAGED", "MISSING_SERIAL", "WRONG_LOCATION", "OTHER");

  /** 处置词表。 */
  private static final List<String> RESOLUTIONS =
      List.of("REPLACED", "SPLIT", "WRITTEN_OFF", "IGNORED");

  /** 幂等锚点：V1 的 inventory_transaction.ref_type 语义（这里不进 inventory_transaction）。 */
  private static final String REF_TYPE = "PICK_TASK";

  /** 打包区库位类型（V2 的六值词表之一：存储/收货/发货/待检/残次/冻结）。 */
  private static final String STAGING_TYPE = "SHIPPING";

  /**
   * 路径口径原文。随响应返回（{@code PickTaskCapabilities.pathBasis} 与
   * {@code PickTaskPathReport.basis}），不只写在注释里 —— 看拣货单的人有权
   * 知道顺序是怎么来的。
   */
  public static final String PATH_BASIS =
      "按库位树层级路径升序（父先于子，同层按库位编码），与 8.1.2.1 库位树的出树顺序"
          + "逐字一致；同一库位内按批次升序（先到期先出）。"
          + "同一 SKU 散在多个库位时优先在同一个库位一次拣齐，拣不齐才拆到下一段；"
          + "优先可拣且启用的库位，没有可拣来源时才退到不可拣库位并在该行标出。";

  private final DSLContext sql;

  public PickTaskService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明。
   *
   * <p>与既有 warehouse 模块一致：把「哪些输入真有依据」「哪条不能填默认值」
   * 显式报出来，而不是让调用方从 400 或空数组里猜。
   */
  public PickTaskCapabilities capabilities() {
    return new PickTaskCapabilities(
        REF_TYPE,
        true,
        true,
        true,
        true,
        true,
        true,
        true,
        // 8.3.2.2 波次与合并拣货已交付（V113 + /warehouse/pick-waves）：本模块仍然
        // 一个出库单一张任务、不做合并 —— 合并是波次那一层的排班，本模块的工单粒度不变。
        true,
        "8.3.2.2 波次与合并拣货（已交付）",
        false,
        "8.3.3.1 复核扫描",
        List.of(
            new PickTaskUpstreamModule(
                "8.3.1.1", "出库单列表", "warehouse", true,
                "生成任务时按 (tenant_id, outbound_order_id) 读 outbound_pick_request 接手："
                    + "该表上有 UNIQUE(tenant_id,order_id)，一张出库单只交接一次。"
                    + "没有交接请求就没有拣货任务 —— 凭空生成一张在账上说不通（拣什么？按哪个计划？）。"),
            new PickTaskUpstreamModule(
                "8.3.1.2", "出库单详情", "warehouse", true,
                "明细行（sku_code / sku_name / quantity）是计划快照，V72 §4.3 已钉成不可改；"
                    + "本模块逐行读它并按 SKU 归集，不会、也不允许回头改计划。"),
            new PickTaskUpstreamModule(
                "7.2.1.2", "预占分配明细", "warehouse", true,
                "分配输入是 stock_bucket.available（= on_hand − reserved，V2 的生成列）。"
                    + "本模块**只读**预占：reserved 跟着货一起从取货位搬到打包位，"
                    + "既不改预占单也不释放它 —— 释放是发货（8.3.4.1）或取消时下游 7 库存的动作。"),
            new PickTaskUpstreamModule(
                "8.1.2.1", "库位树", "warehouse", true,
                "路径顺序按库位树的 parent_id 算层级路径，与 8.1.2.1 出树（order by path）"
                    + "同一口径，所以拣货单上的顺序与现场层级认知一致。")),
        List.of(
            new PickTaskDownstreamModule(
                "8.3.3.1", "复核扫描", "warehouse", false,
                "完成拣货时把出库单从「拣货中」推到「待复核」并留下实拣明细；"
                    + "复核本身未交付，本模块只交到交接为止，不替它判通过与否。"),
            new PickTaskDownstreamModule(
                "7", "库存（已拣未发的占用状态）", "warehouse", true,
                "拣货把 on_hand 与 reserved 从取货库位搬到同仓的打包区（type=SHIPPING）。"
                    + "两侧同属一条 balance，所以 inventory_balance 不变、"
                    + "inventory_entry 不写（它的 CHECK 不允许 delta 为 0 的分录）。"
                    + "预占仍在，货仍占库 —— 「拣货完不等于已发货」在这一层是数据事实而不是口号。")),
        STATUSES,
        EXCEPTION_KINDS,
        SORTS,
        PATH_BASIS,
        "拣货= 库位间搬移（同 balance）：on_hand 与 reserved 一起从取货位搬到打包区，"
            + "balance 层净变化为 0，因此不写 inventory_transaction / inventory_entry。"
            + "序列号同步改 current_location_id，status 保持 IN_STOCK（货还在仓里）。",
        "拣货完 ≠ 已发货：本模块只把出库单推到「待复核」，SHIPPED 由 8.3.4.1 写。");
  }

  // ================================================================ 生成

  /**
   * 生成拣货任务：从出库单明细 + 库存分配结果排出拣货路径。
   *
   * <p>顺序刻意是「先排路径、再落库」：路径序���是计划段的序号，它决定了拣货单
   * 打印出来的行走顺序，中途改顺序等于改已经发给作业员的单子。
   *
   * <p>三条前置校验任一不过就 4xx，<b>绝不静默降级</b>：出库单不在「拣货中」
   * （没有交接请求）、没有交接请求、这个仓没有 ACTIVE 的发货暂存库位当打包区。
   */
  @Transactional
  public PickTaskCreated generate(PickTaskGenerateInput input, UUID actorId) {
    UUID tenant = currentTenant();
    if (input.outboundOrderId() == null) {
      throw bad("必须给 outboundOrderId：拣货任务是从出库单的交接请求接手的，没有出库单就没有任务");
    }

    // ---- 出库单与交接请求
    Record order =
        sql.fetchOne(
            "select id, order_code, status, warehouse_id from outbound_order"
                + " where tenant_id = ? and id = ?",
            tenant, input.outboundOrderId());
    if (order == null) {
      throw notFound("出库单 " + input.outboundOrderId() + " 不存在");
    }
    Record request =
        sql.fetchOne(
            "select id, line_count, total_piece_count, note from outbound_pick_request"
                + " where tenant_id = ? and order_id = ?",
            tenant, input.outboundOrderId());
    if (request == null) {
      throw bad(
          "出库单 " + order.get("order_code", String.class)
              + " 还没有拣货交接请求：先在 8.3.1.1 出库单列表上点「生成拣货任务」，"
              + "本模块是接手方而不是发起方");
    }
    String orderStatus = order.get("status", String.class);
    if (!"PICKING".equals(orderStatus)) {
      throw bad(
          "出库单 " + order.get("order_code", String.class) + " 当前状态是 " + orderStatus
              + "，只有「拣货中」的单才能生成拣货任务（V72 的状态机：待拣 → 拣货中 这一步由"
              + " 8.3.1.1 的交接请求完成）");
    }
    UUID warehouseId = order.get("warehouse_id", UUID.class);

    // ---- 打包区
    // 一张出库单只生成一次拣货任务（outbound_pick_request 上的 UNIQUE(tenant_id,order_id)
    // 是同一纪律）。这里先查一次只是为了说人话 —— 并发下真正挡住的是那个唯一约束。
    if (sql.fetchOne(
            "select 1 from pick_task where tenant_id = ? and outbound_order_id = ?",
            tenant, input.outboundOrderId())
        != null) {
      throw conflict(
          "出库单 " + order.get("order_code", String.class)
              + " 已经生成过拣货任务了：一张出库单只生成一次拣货任务。"
              + "复核返工请走「重开」（POST /warehouse/pick-tasks/{id}/reopen），"
              + "不要新建第二张 —— 两张任务会各拣一遍，货就多拣了");
    }

    UUID staging = resolveStaging(warehouseId, input.stagingLocationId());

    // ---- 库位树层级路径（一次算全，路径顺序与 8.1.2.1 同一口径）
    Map<UUID, String> paths = locationPaths(warehouseId);
    Map<UUID, LocationMeta> locations = locationMeta(warehouseId);

    // ---- 出库单明细逐行分配
    List<Record> lines =
        sql.fetch(
            "select id, sku_id, sku_code, sku_name, quantity from outbound_order_line"
                + " where tenant_id = ? and order_id = ? order by line_no",
            tenant, input.outboundOrderId());

    var allocations = new ArrayList<Allocation>();
    BigDecimal totalPlanned = BigDecimal.ZERO;
    for (Record line : lines) {
      UUID skuId = line.get("sku_id", UUID.class);
      BigDecimal want = qty(line.get("quantity", BigDecimal.class), "计划出库量");
      var segments = allocate(skuId, warehouseId, want);
      BigDecimal allocated = BigDecimal.ZERO;
      for (var segment : segments) {
        if (segment.remaining().signum() <= 0) continue;
        segment.outboundLineId(line.get("id", UUID.class));
        segment.skuId(skuId);
        segment.skuCode(line.get("sku_code", String.class));
        segment.skuName(line.get("sku_name", String.class));
        segment.locationPath(paths.getOrDefault(segment.locationId, ""));
        segment.location(locations.get(segment.locationId));
        allocations.add(segment);
        allocated = allocated.add(segment.remaining());
      }
      // 分配不齐不是错误，是一个**要人知道的事实**：缺口会以「未分配」行落库，
      // 由作业台显示「本行无货可拣」，而不是让任务少一段看起来拣齐了。
      if (allocated.compareTo(want) < 0) {
        var shortfall = new Allocation();
        shortfall.unallocated = true;
        shortfall.outboundLineId(line.get("id", UUID.class));
        shortfall.skuId(skuId);
        shortfall.skuCode(line.get("sku_code", String.class));
        shortfall.skuName(line.get("sku_name", String.class));
        shortfall.quantity(want.subtract(allocated));
        shortfall.locationPath("");
        allocations.add(shortfall);
      }
      totalPlanned = totalPlanned.add(want);
    }

    // ---- 排路径：先按库位层级路径，同层按库位编码；同库位内按批次。
    allocations.sort(
        Comparator.comparing((Allocation a) -> a.locationPath)
            .thenComparing(a -> a.lotId == null ? "" : a.lotId.toString())
            .thenComparing(a -> a.outboundLineId == null ? "" : a.outboundLineId.toString()));

    UUID taskId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    String taskCode = String.format("PT-%s-%08x", now.withOffsetSameInstant(ZoneOffset.UTC)
        .toLocalDate().toString().replace("-", ""), taskId.hashCode() & 0xffffffff);
    sql.execute(
        "insert into pick_task(id,tenant_id,task_code,outbound_order_id,pick_request_id,"
            + "warehouse_id,staging_location_id,status,path_basis,created_by)"
            + " values(?,?,?,?,?,?,?,'CREATED',?,?)",
        taskId, tenant, taskCode, input.outboundOrderId(), request.get("id", UUID.class),
        warehouseId, staging, PATH_BASIS, actorId);

    int sequence = 0;
    int nonPickable = 0;
    for (Allocation a : allocations) {
      sequence++;
      if (a.nonPickable) nonPickable++;
      if (a.unallocated) {
        // 无货可拣：仍然占一个序号（拣货单上要看得见「这一行没货」），
        // 但没有取货库位。location_id 在 DB 上是 NOT NULL，所以落成打包区之外的
        // 一个明确的「无源」表示 —— 用打包区库位 + available_at_source = 0，
        // 由 source_available=false 表达，**不**用 NULL 糊过去。
        // non_pickable_source 刻意是 false：「没货可拣」与「只能退到不可拣库位取货」是
        // 两件不同的事 —— 后者对应 8.5.2.1 的「高频货在不可拣库位」，混成一个标志会让
        // 作业台把「这个 SKU 一个库位都没有」误报成「库位摆错了」。
        sql.execute(
            "insert into pick_task_line(id,tenant_id,task_id,sequence_no,outbound_line_id,"
                + "sku_id,sku_code,sku_name,quality_status_id,source_location_id,source_lot_id,"
                + "location_path,on_hand_at_source,sku_location_count,non_pickable_source,"
                + "planned_quantity) values(?,?,?,?,?,?,?,?,?,?,?,?,0,1,false,?)",
            UUID.randomUUID(), tenant, taskId, sequence, a.outboundLineId, a.skuId,
            a.skuCode, a.skuName, qualitySellable(tenant), staging, null,
            "（无库存可分配）", a.quantity);
      } else {
        sql.execute(
            "insert into pick_task_line(id,tenant_id,task_id,sequence_no,outbound_line_id,"
                + "sku_id,sku_code,sku_name,quality_status_id,source_location_id,source_lot_id,"
                + "location_path,on_hand_at_source,sku_location_count,non_pickable_source,"
                + "planned_quantity) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), tenant, taskId, sequence, a.outboundLineId, a.skuId,
            a.skuCode, a.skuName, a.qualityStatusId, a.locationId, a.lotId,
            a.locationPath, a.onHand, a.locationCount, a.nonPickable, a.quantity);
      }
    }

    event(taskId, PickTaskEventType.GENERATED, null, "CREATED", actorId, now,
        "由出库单 " + order.get("order_code", String.class) + " 的拣货交接请求生成："
            + allocations.size() + " 段、计划 " + plain(totalPlanned) + " 件"
            + (nonPickable > 0 ? "，其中 " + nonPickable + " 段只能退到不可拣库位" : "") + "。");

    // 合计由 V110 的 refresh_pick_task_totals 触发器维护，这里**读回来**而不是自己算。
    Record totals =
        sql.fetchOne(
            "select planned_line_count, planned_quantity from pick_task where id = ?", taskId);

    return new PickTaskCreated(
        taskId,
        taskCode,
        "CREATED",
        input.outboundOrderId(),
        order.get("order_code", String.class),
        warehouseId,
        staging,
        locations.get(staging) == null ? null : locations.get(staging).code(),
        (int) totals.get("planned_line_count", Integer.class),
        decimal(totals, "planned_quantity"),
        nonPickable,
        PATH_BASIS,
        "拣货任务已生成（尚未分配作业人员，也未动库存）：路径与计划段按库位层级路径排好，"
            + "拣货单照 sequenceNo 打印。实拣在接单之后登记，登记时才把货从取货库位搬到打包区。"
            + "拣货完不等于已发货 —— 完成时出库单只会推到「待复核」。");
  }

  // ================================================================ 接单

  /** 接单 / 分配作业人员（PDA 扫码接单同一条动作）。 */
  @Transactional
  public PickTaskRow assign(UUID taskId, PickTaskAssignInput input, UUID actorId) {
    if (input.assigneeUserId() == null) {
      throw bad("必须给 assigneeUserId：拣货要有明确的人（否则 8.5.1.1 作业效率无法归因工时）");
    }
    Record task = lock(taskId);
    String status = task.get("status", String.class);
    if (!PickTaskStatus.CREATED.name().equals(status)
        && !PickTaskStatus.IN_PROGRESS.name().equals(status)) {
      throw bad("拣货任务当前状态为 " + status + "，只有「已生成」或「拣货中」可以改接单人");
    }
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update pick_task set status='IN_PROGRESS', assignee_user_id=?,"
            + " started_at=coalesce(started_at,?::timestamptz), updated_at=now()"
            + " where tenant_id=? and id=?",
        input.assigneeUserId(), now, currentTenant(), taskId);
    event(taskId, PickTaskEventType.ASSIGNED, status, "IN_PROGRESS", actorId, now,
        "作业人员 " + input.assigneeUserId()
            + " 接单（拣货任务 " + task.get("task_code", String.class) + "）。");
    return row(taskId);
  }

  // ================================================================ 实拣

  /**
   * 登记一次实拣：把货从**实际**取货库位搬到打包区。
   *
   * <p>一个本地事务里发生四件事，缺一不可（蓝图卷级约束「所有作业动作与库存分录
   * 在同一本地事务完成」）：写实拣行 → 搬 bucket 的 on_hand 与 reserved →
   * 累加明细行实拣量 → 挪序列号的 current_location_id。
   *
   * <p>允许<b>实际库位与计划不同</b>：分配是建议，现场是事实。真碰上计划的那个
   * 库位已经空了，作业员必须能在别处取到并把真实库位记下来，而不是被系统逼着
   * 报一个缺货。真正被拒的是「那个库位上根本没有这件货」。
   */
  @Transactional
  public PickTaskPickRow recordPick(UUID taskId, PickTaskPickInput input, UUID actorId) {
    UUID tenant = currentTenant();
    Record task = lock(taskId);
    if (!PickTaskStatus.IN_PROGRESS.name().equals(task.get("status", String.class))) {
      throw bad(
          "拣货任务当前状态为 " + task.get("status", String.class)
              + "，只有在「拣货中」才能登记实拣");
    }
    UUID warehouseId = task.get("warehouse_id", UUID.class);
    UUID staging = task.get("staging_location_id", UUID.class);

    Record line = lockLine(taskId, input.lineId());
    BigDecimal qty = qty(input.quantity(), "实拣数量");
    BigDecimal already = atLeastZero(line, "picked_quantity");
    BigDecimal planned = atLeastZero(line, "planned_quantity");
    if (already.add(qty).compareTo(planned) > 0) {
      throw bad(
          "超拣：本段计划 " + plain(planned) + "、已拣 " + plain(already) + "，本次再拣 "
              + plain(qty) + " 就超了。超拣要么变成多发、要么变成损耗，两件都不是本动作该顺手做的事。");
    }
    if (input.serialIds() != null && !input.serialIds().isEmpty()) {
      BigDecimal expected = new BigDecimal(input.serialIds().size());
      if (expected.compareTo(qty) != 0) {
        throw bad(
            "序列号条数（" + input.serialIds().size() + "）必须与本次实拣数量（" + plain(qty)
                + "）一致：逐件核对的含义就是「一件一号」，对不上说明这次实拣里混了不该拣的东西");
      }
    }

    UUID skuId = line.get("sku_id", UUID.class);
    UUID qualityStatusId = line.get("quality_status_id", UUID.class);
    OffsetDateTime at = instant(input.pickedAt(), "实拣时刻");

    // ---- 找实际取货库位上的 bucket（同 balance、同批次）
    Record source =
        sql.fetchOne(
            "select k.id, k.balance_id, k.on_hand, k.reserved from stock_bucket k"
                + " where k.tenant_id=? and k.warehouse_id=? and k.location_id=? and k.sku_id=?"
                + " and k.quality_status_id=? and k.lot_id is not distinct from ?",
            tenant, warehouseId, input.locationId(), skuId, qualityStatusId, input.lotId());
    if (source == null) {
      throw bad(
          "库位 " + input.locationId() + " 上没有这件货的库存行（SKU " + skuId
              + "）：拣货必须从真实存在的库存取出，不能凭空拣");
    }
    // 在手与预占**允许是 0**（未预占的货很常见），这里不是写入侧校验，
    // 用 atLeastZero 而不是会拒掉 0 的 qty()。
    BigDecimal onHand = atLeastZero(source, "on_hand");
    BigDecimal reserved = atLeastZero(source, "reserved");
    if (onHand.compareTo(qty) < 0) {
      throw bad(
          "库位 " + input.locationId() + " 上只有 " + plain(onHand) + "，本次要拣 " + plain(qty)
              + (reserved.signum() > 0 ? "（其中 " + plain(reserved) + " 已被预占）" : "")
              + "。拣不动就报缺货异常（缺货要写缺几件），别把数量凑上去");
    }
    UUID balanceId = source.get("balance_id", UUID.class);
    if (input.locationId().equals(staging)) {
      throw bad("打包区不是取货位：拣货就是把货从拣货位搬到打包区，不能原地取");
    }

    // ---- 搬运：on_hand 与 reserved 一起走（预占不释放，蓝图关系段）
    //
    // 预占搬多少取 min(reserved, 本次实拣量)：拣走 6 件而该库位只预占了 4 件时，
    // 就搬 4 件 —— 不能把「这一件被谁预占了」说成不知道的事，也不能搬成 0
    // （那样预占会留在一个已经没有这批货的库位上）。
    BigDecimal reservedToMove = reserved.min(qty);
    sql.execute(
        "update stock_bucket set on_hand=on_hand-?, reserved=reserved-?,"
            + " last_movement_at=greatest(?::timestamptz,last_movement_at"
            + " + interval '1 microsecond') where id=?",
        qty, reservedToMove, at, source.get("id", UUID.class));
    upsertBucket(tenant, balanceId, warehouseId, staging, input.lotId(), qualityStatusId, qty,
        reservedToMove, at);

    // ---- 序列号：先建实拣行，再逐个登记并挪 current_location_id（status 保持 IN_STOCK）
    UUID pickId = UUID.randomUUID();
    sql.execute(
        "insert into pick_task_pick(id,tenant_id,task_id,line_id,location_id,lot_id,quantity,"
            + "serial_count,picked_by,picked_at,note) values(?,?,?,?,?,?,?,?,?,?::timestamptz,?)",
        pickId, tenant, taskId, input.lineId(), input.locationId(), input.lotId(), qty,
        input.serialIds() == null ? 0 : input.serialIds().size(), actorId, at,
        text(input.note()));
    if (input.serialIds() != null) {
      for (UUID serialId : input.serialIds()) {
        sql.execute(
            "insert into pick_task_serial(id,tenant_id,task_id,pick_id,line_id,serial_id,"
                + "picked_by,picked_at) values(?,?,?,?,?,?,?,?::timestamptz)",
            UUID.randomUUID(), tenant, taskId, pickId, input.lineId(), serialId, actorId, at);
        sql.execute(
            "update inventory_serial set current_location_id=?,"
                + " last_movement_at=greatest(?::timestamptz,last_movement_at)"
                + " where tenant_id=? and id=?",
            staging, at, tenant, serialId);
      }
    }

    sql.execute(
        "update pick_task_line set picked_quantity=picked_quantity+? where tenant_id=? and id=?",
        qty, tenant, input.lineId());

    event(taskId, PickTaskEventType.PICKED, "IN_PROGRESS", "IN_PROGRESS", actorId, at,
        "SKU " + line.get("sku_code", String.class) + " 实拣 " + plain(qty) + " 件"
            + "（取货库位 " + input.locationId()
            + (input.serialIds() == null || input.serialIds().isEmpty()
                ? ""
                : "，" + input.serialIds().size() + " 个序列号")
            + "），已搬到打包区。实拣累计 " + plain(already.add(qty)) + " / 计划 " + plain(planned) + "。");

    return pick(pickId);
  }

  // ================================================================ 异常

  /**
   * 上报异常（缺货 / 破损 / 序列号缺失 / 错位 / 其它）。
   *
   * <p><b>缺货必须写缺几件</b>：报一个「缺货」而不写数量，仓库只知道要补货、
   * 不知道要补多少，那张异常单在作业台上没法用。其余四类<b>不许</b>填数量
   * （V110 的 CHECK 直接拒）—— 它们报的是「这件不能按原样发」，数量由
   * 实拣与计划之差体现，两个地方各记一遍只会对不上。
   */
  @Transactional
  public PickTaskExceptionRow reportException(
      UUID taskId, PickTaskExceptionInput input, UUID actorId) {
    UUID tenant = currentTenant();
    if (input.kind() == null || !EXCEPTION_KINDS.contains(input.kind())) {
      throw bad("异常种类只能是 " + EXCEPTION_KINDS + " 之一，收到的是 " + input.kind());
    }
    Record task = lock(taskId);
    if (!PickTaskStatus.valueOf(task.get("status", String.class)).acceptsExceptions()) {
      throw bad(
          "拣货任务已 " + task.get("status", String.class) + "，不能再上报异常：它已经交给下游了");
    }
    if (input.detail() == null || input.detail().isBlank()) {
      throw bad("必须写 detail：异常单上没有一句人读得懂的话，作业台就不知道要人去做什么");
    }
    boolean shortage = "SHORTAGE".equals(input.kind());
    BigDecimal shortageQty = shortage ? qty(input.shortageQuantity(), "缺货数量") : null;
    if (shortage && shortageQty.signum() <= 0) {
      throw bad("缺货数量必须大于 0：报「缺货」而不写缺几件，仓库只知道要补货不知道要补多少");
    }
    if (!shortage && input.shortageQuantity() != null) {
      throw bad(
          "只有缺货（SHORTAGE）才填 shortageQuantity，其余四类报的是「这件不能按原样发」，"
              + "填了数量会与实拣/计划之差对不上");
    }
    if (input.lineId() != null) {
      lockLine(taskId, input.lineId());
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime at = instant(input.reportedAt(), "上报时刻");
    sql.execute(
        "insert into pick_task_exception(id,tenant_id,task_id,line_id,kind,shortage_quantity,"
            + "location_id,detail,reported_by,reported_at) values(?,?,?,?,?,?,?,?,?,?::timestamptz)",
        id, tenant, taskId, input.lineId(), input.kind(), shortageQty, input.locationId(),
        input.detail().trim(), actorId, at);
    event(taskId, PickTaskEventType.EXCEPTION_REPORTED, null, null, actorId, at,
        "上报异常 " + input.kind() + (shortageQty == null ? "" : "（缺 " + plain(shortageQty) + " 件）")
            + "：" + input.detail().trim());
    return exception(id);
  }

  /** 处置异常。<b>只能写一次</b>（V110 的守卫）。 */
  @Transactional
  public PickTaskExceptionRow resolveException(
      UUID taskId, UUID exceptionId, PickTaskResolveInput input, UUID actorId) {
    UUID tenant = currentTenant();
    if (input.resolution() == null || !RESOLUTIONS.contains(input.resolution())) {
      throw bad("处置结论只能是 " + RESOLUTIONS + " 之一，收到的是 " + input.resolution());
    }
    if (input.note() == null || input.note().isBlank()) {
      throw bad("必须写 note：处置说明是异常单上唯一能解释「后来怎么解决的」的地方");
    }
    Record row =
        sql.fetchOne(
            "select id, task_id, resolved_at from pick_task_exception"
                + " where tenant_id = ? and id = ?",
            tenant, exceptionId);
    if (row == null) throw notFound("异常 " + exceptionId + " 不存在");
    if (!taskId.equals(row.get("task_id", UUID.class))) {
      throw notFound("异常 " + exceptionId + " 不属于这张拣货任务");
    }
    if (row.get("resolved_at", OffsetDateTime.class) != null) {
      throw conflict("这条异常已经处理过，处置结论不可再改：异常处置是审计对象");
    }
    sql.execute(
        "update pick_task_exception set resolution=?, resolve_note=?, resolved_by=?,"
            + " resolved_at=?::timestamptz where tenant_id=? and id=?",
        input.resolution(), input.note().trim(), actorId, OffsetDateTime.now(), tenant,
        exceptionId);
    return exception(exceptionId);
  }

  // ================================================================ 完成 / 取消 / 重开

  /**
   * 完成拣货：把出库单交给 8.3.3.1 复核。
   *
   * <p><b>判据只有一条：每一段都拣齐，或每一段未拣齐的都已按缺货登记了异常。</b>
   * 不给「强制完成」：一个 9/10 的任务被强行提交到复核，复核那里看到的仍然是
   * 缺件，而缺件的原因（本该缺货、还是有人漏拣）在那一刻已经分不清了。
   *
   * <p>出库单只推到「待复核」。<b>SHIPPED 由 8.3.4.1 写</b> —— 蓝图关系段点名
   * 「拣货完不等于已发货」，这里在代码层也是分开的两步。
   */
  @Transactional
  public PickTaskDetail complete(UUID taskId, UUID actorId) {
    UUID tenant = currentTenant();
    Record task = lock(taskId);
    if (!PickTaskStatus.IN_PROGRESS.name().equals(task.get("status", String.class))) {
      throw bad("拣货任务当前状态为 " + task.get("status", String.class)
          + "，只有「拣货中」可以完成");
    }
    List<Record> lines =
        sql.fetch(
            "select id, sequence_no, picked_quantity, planned_quantity, line_status"
                + " from pick_task_line where tenant_id=? and task_id=? order by sequence_no",
            tenant, taskId);
    if (lines.isEmpty()) {
      throw bad("这张拣货任务没有任何拣货明细：空任务完成之后会交给复核一个不存在的东西");
    }
    var unresolved = new ArrayList<String>();
    for (Record line : lines) {
      if ("PICKED".equals(line.get("line_status", String.class))) continue;
      long reported =
          countOf(
              "select count(*) from pick_task_exception"
                  + " where tenant_id=? and line_id=? and kind='SHORTAGE'",
              tenant, line.get("id", UUID.class));
      if (reported == 0) {
        unresolved.add(
            "第 " + line.get("sequence_no", Integer.class) + " 段计划 "
                + plain(line.get("planned_quantity", BigDecimal.class)) + "、已拣 "
                + plain(line.get("picked_quantity", BigDecimal.class)));
      }
    }
    if (!unresolved.isEmpty()) {
      throw bad(
          "还有 " + unresolved.size() + " 段没拣齐、也没按缺货登记异常："
              + String.join("；", unresolved)
              + "。要么补拣，要么按缺货报一条（缺货要写缺几件）——"
              + "一个 9/10 的单子提交到复核，复核看到的仍然是缺件，而缺件的原因那一刻已分不清");
    }

    UUID orderId = task.get("outbound_order_id", UUID.class);
    String orderStatus =
        textOf("select status from outbound_order where tenant_id=? and id=?", tenant, orderId);
    if (!"PICKING".equals(orderStatus)) {
      throw conflict(
          "出库单当前状态是 " + orderStatus
              + "，不是「拣货中」：V72 的状态机不允许从别的状态推到待复核");
    }

    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update outbound_order set status='PENDING_RECHECK' where tenant_id=? and id=?"
            + " and status='PICKING'",
        tenant, orderId);
    sql.execute(
        "update pick_task set status='COMPLETED', completed_at=?::timestamptz where tenant_id=?"
            + " and id=?",
        now, tenant, taskId);
    event(taskId, PickTaskEventType.COMPLETED, "IN_PROGRESS", "COMPLETED", actorId, now,
        "拣货完成，出库单已推到「待复核」（8.3.3.1）。**拣货完不等于已发货**：货在打包区，"
            + "预占仍在，发货由 8.3.4.1 写。");
    return detail(taskId);
  }

  /** 取消拣货任务。货仍在原库位，占用不变 —— 取消不动库存。 */
  @Transactional
  public PickTaskRow cancel(UUID taskId, PickTaskCancelInput input, UUID actorId) {
    UUID tenant = currentTenant();
    Record task = lock(taskId);
    PickTaskStatus status = PickTaskStatus.valueOf(task.get("status", String.class));
    if (status.terminal()) {
      throw bad("拣货任务已 " + status.label() + "，取消它没有意义");
    }
    if (input.reason() == null || input.reason().isBlank()) {
      throw bad("取消必须写原因：取消是最需要事后解释的一个动作，没有原因的取消等于没留痕");
    }
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update pick_task set status='CANCELLED', cancelled_at=?::timestamptz, cancel_reason=?"
            + " where tenant_id=? and id=?",
        now, input.reason().trim(), tenant, taskId);
    event(taskId, PickTaskEventType.CANCELLED, status.name(), "CANCELLED", actorId, now,
        "取消拣货任务：" + input.reason().trim() + "。**未动库存**：货仍在原库位，预占不变。");
    return row(taskId);
  }

  /**
   * 重开（复核返工）。
   *
   * <p>存在的理由：8.3.1.1 的 {@code outbound_pick_request} 有
   * {@code UNIQUE(tenant_id,order_id)}（一张出库单只交接一次），而它的状态机里
   * 又留着「待复核 → 拣货中」的返工边。没有重开，被复核退回的订单就会永远停在
   * 「拣货中」而没有作业入口。
   *
   * <p>实拣量<b>累加</b>不覆盖：覆盖等于抹掉第一次的事实，而复核退回的原因
   * 恰恰就在第一次的记录里。
   */
  @Transactional
  public PickTaskRow reopen(UUID taskId, PickTaskReopenInput input, UUID actorId) {
    UUID tenant = currentTenant();
    Record task = lock(taskId);
    PickTaskStatus status = PickTaskStatus.valueOf(task.get("status", String.class));
    if (!status.terminal()) {
      throw bad("拣货任务还没结束（" + status.label() + "），不用重开");
    }
    if (input.reason() == null || input.reason().isBlank()) {
      throw bad("重开必须写原因：它是唯一一条会让已完成任务的实拣数继续增长的边");
    }
    String orderStatus =
        textOf(
            "select status from outbound_order where tenant_id=? and id=?",
            tenant, task.get("outbound_order_id", UUID.class));
    if (!"PICKING".equals(orderStatus)) {
      throw bad(
          "出库单当前状态是 " + orderStatus + "，不是「拣货中」：只有 8.3.3.1 复核把它退回"
              + "「拣货中」之后才谈得上重开（V72 的状态机：待复核 → 拣货中）");
    }
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update pick_task set status='IN_PROGRESS', completed_at=null, reopen_count=reopen_count+1,"
            + " reopen_reason=?, started_at=coalesce(started_at,?::timestamptz), updated_at=now()"
            + " where tenant_id=? and id=?",
        input.reason().trim(), now, tenant, taskId);
    if (input.assigneeUserId() != null) {
      sql.execute(
          "update pick_task set assignee_user_id=? where tenant_id=? and id=?",
          input.assigneeUserId(), tenant, taskId);
    }
    event(taskId, PickTaskEventType.REOPENED, status.name(), "IN_PROGRESS", actorId, now,
        "复核返工重开：" + input.reason().trim()
            + "。实拣量在原有基础上**累加**，第一次拣了什么仍然查得到。");
    return row(taskId);
  }

  // ================================================================ 查询

  /** 列表：真分页 + 真筛选 + 排序白名单 + 汇总（汇总不受筛选影响）。 */
  public PickTaskPage page(
      UUID warehouseId, String status, UUID assigneeUserId, String keyword, String sort, int page,
      int size) {
    UUID tenant = currentTenant();
    // List.of() 返回的不可变列表对 null 是敌意的（contains(null) 直接 NPE），
    // 而 status / sort 在 Controller 上都是 required=false —— 漏判就是 500。
    if (status != null && !status.isBlank() && !STATUSES.contains(status)) {
      throw bad("状态只能是 " + STATUSES + " 之一，收到的是 " + status);
    }
    if (sort != null && !SORTS.contains(sort)) {
      throw bad("排序只能是 " + SORTS + " 之一，收到的是 " + sort);
    }
    int limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int offset = Math.max(page, 0) * limit;
    String column =
        switch (sort) {
          case "TASK_CODE" -> "t.task_code";
          case "PLANNED_QUANTITY" -> "t.planned_quantity";
          case "PICKED_QUANTITY" -> "t.picked_quantity";
          case "STATUS" -> "t.status";
          default -> "t.created_at";
        };

    var where = new ArrayList<String>();
    var args = new ArrayList<Object>();
    args.add(tenant);
    if (warehouseId != null) {
      where.add("t.warehouse_id = ?");
      args.add(warehouseId);
    }
    if (status != null && !status.isBlank()) {
      where.add("t.status = ?");
      args.add(status);
    }
    if (assigneeUserId != null) {
      where.add("t.assignee_user_id = ?");
      args.add(assigneeUserId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.add("(t.task_code ilike ? or exists (select 1 from outbound_order o"
          + " where o.tenant_id=t.tenant_id and o.id=t.outbound_order_id and o.order_code ilike ?))");
      args.add("%" + keyword.trim() + "%");
      args.add("%" + keyword.trim() + "%");
    }
    String clause = where.isEmpty() ? "" : " and " + String.join(" and ", where);

    long total = countOf("select count(*) from pick_task t where t.tenant_id = ?" + clause,
        args.toArray());

    var items =
        sql.fetch(
                TASK_SELECT
                    + " where t.tenant_id = ?" + clause
                    + " order by " + column + " desc, t.id limit ? offset ?",
                all(args, limit, offset))
            .map(this::taskRow);

    return new PickTaskPage(items, Math.max(page, 0), limit, total, summary(warehouseId));
  }

  /** 详情 = 单头 + 路径计划段 + 实拣 + 异常 + 时间线 + 路径报告。 */
  public PickTaskDetail detail(UUID taskId) {
    UUID tenant = currentTenant();
    Record task =
        sql.fetchOne(
            TASK_SELECT + " where t.tenant_id = ? and t.id = ?",
            tenant, taskId);
    if (task == null) throw notFound("拣货任务 " + taskId + " 不存在");

    var lines =
        sql.fetch(
                "select ln.*, l.code as location_code, lo.lot_code,"
                    + " l.pickable as location_pickable, l.status as location_status"
                    + " from pick_task_line ln"
                    + " join warehouse_location l on l.tenant_id=ln.tenant_id"
                    + " and l.id=ln.source_location_id"
                    + " left join inventory_lot lo on lo.tenant_id=ln.tenant_id"
                    + " and lo.id=ln.source_lot_id"
                    + " where ln.tenant_id=? and ln.task_id=? order by ln.sequence_no",
                tenant, taskId)
            .map(this::lineRow);

    var picks = picksOf(taskId);
    var exceptions =
        sql.fetch(
                "select e.*, l.code as location_code, ln.sku_code from pick_task_exception e"
                    + " left join warehouse_location l on l.tenant_id=e.tenant_id"
                    + " and l.id=e.location_id"
                    + " left join pick_task_line ln on ln.tenant_id=e.tenant_id"
                    + " and ln.id=e.line_id"
                    + " where e.tenant_id=? and e.task_id=? order by e.reported_at, e.id",
                tenant, taskId)
            .map(this::exceptionRow);

    var events =
        sql.fetch(
                "select * from pick_task_event where tenant_id=? and task_id=?"
                    + " order by occurred_at, id",
                tenant, taskId)
            .map(
                e ->
                    new PickTaskEventRow(
                        e.get("id", UUID.class),
                        e.get("event_type", String.class),
                        eventLabel(e.get("event_type", String.class)),
                        e.get("from_status", String.class),
                        e.get("to_status", String.class),
                        e.get("actor_user_id", UUID.class),
                        e.get("occurred_at", OffsetDateTime.class),
                        e.get("detail", String.class)));

    String orderStatus = task.get("order_status", String.class);
    return new PickTaskDetail(
        taskRow(task),
        task.get("outbound_order_id", UUID.class),
        task.get("order_code", String.class),
        orderStatus,
        outboundStatusLabel(orderStatus),
        lines,
        picks,
        exceptions,
        events,
        pathReport(taskId, lines),
        "拣货完 ≠ 已发货：本任务只把出库单交给「待复核」（8.3.3.1），货在打包区、"
            + "预占仍在，发货由 8.3.4.1 写。");
  }

  /**
   * 汇总。<b>不受筛选影响</b>（与 LocationUtilization 的合计同一纪律）：
   * 筛完只看得到一部分行时，顶部合计仍是全仓真相。
   */
  private PickTaskSummary summary(UUID warehouseId) {
    UUID tenant = currentTenant();
    var where = warehouseId == null ? "" : " and warehouse_id = ?";
    Object[] args = warehouseId == null ? new Object[] {tenant} : new Object[] {tenant, warehouseId};
    Record totals =
        sql.fetchOne(
            "select count(*) as cnt, coalesce(sum(planned_quantity),0) as planned,"
                + " coalesce(sum(picked_quantity),0) as picked"
                + " from pick_task where tenant_id = ?" + where,
            args);
    long nonPickable =
        countOf(
            "select count(distinct t.id) from pick_task t where t.tenant_id = ?" + where
                + " and exists (select 1 from pick_task_line ln where ln.tenant_id=t.tenant_id"
                + " and ln.task_id=t.id and ln.non_pickable_source)",
            args);
    long dispersion =
        countOf(
            "select count(distinct t.id) from pick_task t where t.tenant_id = ?" + where
                + " and exists (select 1 from pick_task_line ln where ln.tenant_id=t.tenant_id"
                + " and ln.task_id=t.id and ln.sku_location_count > 1)",
            args);
    long openExceptions =
        countOf(
            "select count(*) from pick_task_exception e join pick_task t"
                + " on t.tenant_id=e.tenant_id and t.id=e.task_id"
                + " where e.tenant_id = ?" + where + " and e.resolved_at is null",
            args);
    var byStatus = new LinkedHashMap<String, Long>();
    for (PickTaskStatus s : PickTaskStatus.values()) byStatus.put(s.name(), 0L);
    sql.fetch(
            "select status, count(*) as cnt from pick_task where tenant_id = ?" + where
                + " group by status",
            args)
        .forEach(
            r -> byStatus.put(r.get("status", String.class), r.get("cnt", Long.class)));
    return new PickTaskSummary(
        totals.get("cnt", Long.class),
        atLeastZero(totals, "planned"),
        atLeastZero(totals, "picked"),
        nonPickable,
        dispersion,
        openExceptions,
        byStatus);
  }

  // ================================================================ 路径

  /**
   * 路径报告：把「顺序是怎么来的」摊开给人看。
   *
   * <p>{@code walks}（有实拣的库位数）是「拣一次拿齐」这个目标的真实读数；
   * {@code splitLineCount} 是同一个出库单行被拆成几段的真实读数。
   */
  private PickTaskPathReport pathReport(UUID taskId, List<PickTaskLine> lines) {
    var stops = new ArrayList<PickTaskPathStop>();
    int dispersion = 0;
    int nonPickable = 0;
    var skuLineCounts = new LinkedHashMap<UUID, Integer>();
    for (PickTaskLine line : lines) {
      skuLineCounts.merge(line.skuId(), 1, Integer::sum);
      if (line.skuLocationCount() > 1) dispersion++;
      if (line.nonPickableSource()) nonPickable++;
    }
    int split = 0;
    for (var count : skuLineCounts.values()) if (count > 1) split += count - 1;

    // 同一库位的多段合并成一次停靠 —— 「拣货要走几趟」问的是库位数不是行数。
    PickTaskLine current = null;
    for (PickTaskLine line : lines) {
      if (current == null || !current.sourceLocationId().equals(line.sourceLocationId())) {
        if (current != null) stops.add(stop(current, lines));
        current = line;
      }
    }
    if (current != null) stops.add(stop(current, lines));

    int walks =
        (int)
            lines.stream()
                .filter(l -> l.pickedQuantity().signum() > 0)
                .map(PickTaskLine::sourceLocationId)
                .distinct()
                .count();
    return new PickTaskPathReport(
        PATH_BASIS,
        lines.size(),
        stops.size(),
        nonPickable,
        dispersion,
        split,
        stops,
        "排序：库位层级路径升序（父先于子、同层按编码），与 8.1.2.1 库位树出树同一口径。"
            + "收敛：同一 SKU 优先在一个库位拣齐，拣不齐才拆段。"
            + "避让：优先可拣且启用的库位，没有可拣来源才退到不可拣位并在该行标出"
            + "（对应 8.5.2.1 的两类建议 ownerModule=8.3.2.1）。已走 " + walks + " 个库位。");
  }

  private PickTaskPathStop stop(PickTaskLine first, List<PickTaskLine> all) {
    int from = first.sequenceNo();
    int to = from;
    int count = 0;
    BigDecimal qty = BigDecimal.ZERO;
    for (PickTaskLine line : all) {
      if (line.sourceLocationId().equals(first.sourceLocationId())) {
        to = Math.max(to, line.sequenceNo());
        count++;
        qty = qty.add(line.plannedQuantity());
      }
    }
    return new PickTaskPathStop(
        first.sourceLocationId(), first.sourceLocationCode(), first.locationPath(),
        first.locationDepth(), first.sourcePickable(), count, qty, from, to);
  }

  /**
   * 分配：把一个 SKU 的计划量摊到库位上。
   *
   * <p>三条规则的落点（顺序即优先级）：
   *
   * <ol>
   *   <li><b>可拣优先</b>：可拣且 ACTIVE 的库位排在前面；没有可拣来源时才退到
   *       不可拣库位并标 {@code nonPickable}（8.5.2.1「高频货在不可拣库位」的落地）。
   *   <li><b>收敛</b>：同一库位内<b>一次拣齐</b>（8.5.2.1「SKU 散在多库位」的落地）——
   *       所以排序不是「谁多谁先」而是<b>路径优先</b>：走库位树的层级路径，
   *       走到哪一站就在那一站尽量拣齐，拣不齐才拆到下一站。
   *   <li><b>批次优先</b>：同一库位内按批次 id 升序（先到期先出，与 FEFO 常识一致）。
   * </ol>
   *
   * <p>质量状态只取<b>可售</b>：V2 的 {@code guard_bucket_reservable} 规定预占只能
   * 落在 SELLABLE 上，待检品不可售是硬规则（8.2.2.2）。所以拣货不碰待检与残次。
   */
  private List<Allocation> allocate(UUID skuId, UUID warehouseId, BigDecimal want) {
    UUID tenant = currentTenant();
    UUID sellable = qualitySellable(tenant);
    var buckets =
        sql.fetch(
            "select k.location_id, k.lot_id, k.quality_status_id, k.on_hand,"
                + " l.pickable, l.status as location_status"
                + " from stock_bucket k join warehouse_location l"
                + " on l.tenant_id = k.tenant_id and l.id = k.location_id"
                + " where k.tenant_id = ? and k.warehouse_id = ? and k.sku_id = ?"
                + " and k.quality_status_id = ? and k.on_hand > 0 and l.status = 'ACTIVE'"
                + " order by l.pickable desc, k.location_id, k.lot_id",
            tenant, warehouseId, skuId, sellable);

    // 真实的跨库位计数（8.5.2.1 的同一口径）：这个 SKU 散在几个库位上。
    int locationCount =
        (int) buckets.stream().map(r -> r.get("location_id", UUID.class)).distinct().count();

    Map<UUID, String> paths = locationPaths(warehouseId);
    var pickableBuckets = buckets.stream().filter(r -> r.get("pickable", Boolean.class)).toList();
    var fallback = buckets.stream().filter(r -> !r.get("pickable", Boolean.class)).toList();

    var out = new ArrayList<Allocation>();
    BigDecimal remaining = want;
    // 第一轮：只在可拣库位上分配（按路径顺序，走到哪拣到哪）。
    remaining = spread(pickableBuckets, remaining, out, locationCount, false, paths);
    // 第二轮：可拣库位不够（或没有）才退到不可拣库位，并逐段标记。
    if (remaining.signum() > 0) {
      spread(fallback, remaining, out, locationCount, true, paths);
    }
    return out;
  }

  private BigDecimal spread(
      List<Record> buckets, BigDecimal remaining, List<Allocation> out, int locationCount,
      boolean nonPickable, Map<UUID, String> paths) {
    var sorted = buckets.stream()
        .sorted(
            Comparator.comparing((Record r) -> paths.getOrDefault(r.get("location_id", UUID.class), ""))
                .thenComparing(r -> r.get("lot_id", UUID.class) == null ? "" : r.get("lot_id").toString()))
        .toList();
    BigDecimal left = remaining;
    for (Record bucket : sorted) {
      if (left.signum() <= 0) break;
      BigDecimal onHand = atLeastZero(bucket, "on_hand");
      if (onHand.signum() <= 0) continue;
      BigDecimal take = onHand.min(left);
      var allocation = new Allocation();
      allocation.locationId(bucket.get("location_id", UUID.class));
      allocation.lotId(bucket.get("lot_id", UUID.class));
      allocation.qualityStatusId(bucket.get("quality_status_id", UUID.class));
      allocation.onHand(onHand);
      allocation.locationCount(locationCount);
      allocation.nonPickable(nonPickable);
      allocation.quantity(take);
      out.add(allocation);
      left = left.subtract(take);
    }
    return left;
  }

  // ================================================================ 库存搬运

  /**
   * 打包区 bucket 的写入。
   *
   * <p>存在就加，不存在就建 —— 建行时六个维度逐列取自 {@code inventory_balance}
   * （V2 的复合外键会当场核对），{@code reserved} 跟着货一起过来。
   *
   * <p>{@code balance} 一行都没动：V2 §4.4 的守恒式（balance = Σ bucket）在事务
   * 提交时成立，因为两侧 bucket 的合计没变。
   */
  private void upsertBucket(
      UUID tenant, UUID balanceId, UUID warehouseId, UUID staging, UUID lotId,
      UUID qualityStatusId, BigDecimal qty, BigDecimal reserved, OffsetDateTime at) {
    Record existing =
        sql.fetchOne(
            "select id from stock_bucket where tenant_id=? and balance_id=? and location_id=?"
                + " and lot_id is not distinct from ?",
            tenant, balanceId, staging, lotId);
    if (existing != null) {
      sql.execute(
          "update stock_bucket set on_hand=on_hand+?, reserved=reserved+?,"
              + " last_movement_at=greatest(?::timestamptz,last_movement_at"
              + " + interval '1 microsecond') where id=?",
          qty, reserved, at, existing.get("id", UUID.class));
      return;
    }
    Record balance = sql.fetchOne("select * from inventory_balance where tenant_id=? and id=?",
        tenant, balanceId);
    sql.execute(
        "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
            + "lot_id,quality_status_id,balance_id,on_hand,reserved,last_movement_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(), tenant, balance.get("legal_entity_id", UUID.class),
        balance.get("sku_id", UUID.class), warehouseId, staging, lotId, qualityStatusId, balanceId,
        qty, reserved, at);
  }

  // ================================================================ 读模型

  /** 任务行的公共 SELECT 片段：三个查询共用，保证读到的列一模一样。 */
  private static final String TASK_SELECT =
      "select t.*, o.order_code, o.status as order_status, l.code as staging_code,"
          + " (select count(*) from pick_task_line ln where ln.tenant_id=t.tenant_id"
          + "  and ln.task_id=t.id and ln.non_pickable_source) as non_pickable_line_count,"
          + " (select count(*) from pick_task_exception e where e.tenant_id=t.tenant_id"
          + "  and e.task_id=t.id and e.resolved_at is null) as open_exception_count"
          + " from pick_task t"
          + " join outbound_order o on o.tenant_id=t.tenant_id and o.id=t.outbound_order_id"
          + " join warehouse_location l on l.tenant_id=t.tenant_id"
          + " and l.id=t.staging_location_id";

  private PickTaskRow row(UUID taskId) {
    Record task =
        sql.fetchOne(TASK_SELECT + " where t.tenant_id=? and t.id=?", currentTenant(), taskId);
    if (task == null) throw notFound("拣货任务 " + taskId + " 不存在");
    return taskRow(task);
  }

  private PickTaskRow taskRow(Record t) {
    BigDecimal planned = atLeastZero(t, "planned_quantity");
    BigDecimal picked = atLeastZero(t, "picked_quantity");
    int plannedLines = t.get("planned_line_count", Integer.class);
    int pickedLines = t.get("picked_line_count", Integer.class);
    int exceptions = t.get("exception_count", Integer.class);
    // 这两个计数由**查询里的标量子查询**给出（见 row()/page()/detail() 三处 SELECT），
    // 不在这里逐行查：列表一页 50 行就是 100 次额外往返，大仓上足以让作业台转圈。
    int nonPickable = t.get("non_pickable_line_count", Integer.class);
    boolean unresolved = t.get("open_exception_count", Integer.class) > 0;
    boolean fullyPicked = plannedLines > 0 && pickedLines == plannedLines;
    String status = t.get("status", String.class);
    // 进度分母为 0 时给 null 而不是 0%：「还没排计划」与「计划了但一件没拣」不是一回事
    // （与 8.5.2.1 的三处分母边界同一纪律）。
    BigDecimal progress =
        planned.signum() == 0
            ? null
            : picked.min(planned).divide(planned, 6, RoundingMode.HALF_UP);
    return new PickTaskRow(
        t.get("id", UUID.class),
        t.get("task_code", String.class),
        status,
        label(status),
        t.get("outbound_order_id", UUID.class),
        t.get("order_code", String.class),
        t.get("warehouse_id", UUID.class),
        t.get("staging_location_id", UUID.class),
        t.get("staging_code", String.class),
        t.get("assignee_user_id", UUID.class),
        null,
        plannedLines,
        planned,
        pickedLines,
        picked,
        exceptions,
        nonPickable,
        progress,
        fullyPicked,
        unresolved,
        t.get("reopen_count", Integer.class),
        t.get("started_at", OffsetDateTime.class),
        t.get("completed_at", OffsetDateTime.class),
        t.get("created_at", OffsetDateTime.class));
  }

  private PickTaskLine lineRow(Record r) {
    BigDecimal planned = atLeastZero(r, "planned_quantity");
    BigDecimal picked = atLeastZero(r, "picked_quantity");
    BigDecimal onHand = atLeastZero(r, "on_hand_at_source");
    String status = r.get("line_status", String.class);
    String path = r.get("location_path", String.class);
    return new PickTaskLine(
        r.get("id", UUID.class),
        r.get("sequence_no", Integer.class),
        r.get("outbound_line_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("quality_status_id", UUID.class),
        r.get("source_location_id", UUID.class),
        r.get("location_code", String.class),
        r.get("source_lot_id", UUID.class),
        r.get("lot_code", String.class),
        path,
        (int) path.chars().filter(ch -> ch == '|').count(),
        Boolean.TRUE.equals(r.get("location_pickable", Boolean.class)),
        "ACTIVE".equals(r.get("location_status", String.class)),
        onHand,
        onHand.signum() > 0,
        r.get("sku_location_count", Integer.class),
        r.get("non_pickable_source", Boolean.class),
        planned,
        picked,
        planned.subtract(picked),
        PickTaskLineStatus.valueOf(status),
        lineLabel(status));
  }

  private List<PickTaskPickRow> picksOf(UUID taskId) {
    UUID tenant = currentTenant();
    var picks =
        sql.fetch(
                "select p.*, l.code as location_code, lo.lot_code from pick_task_pick p"
                    + " join warehouse_location l on l.tenant_id=p.tenant_id and l.id=p.location_id"
                    + " left join inventory_lot lo on lo.tenant_id=p.tenant_id and lo.id=p.lot_id"
                    + " where p.tenant_id=? and p.task_id=? order by p.picked_at, p.id",
                tenant, taskId)
            .map(
                p ->
                    new PickTaskPickRow(
                        p.get("id", UUID.class),
                        p.get("line_id", UUID.class),
                        p.get("location_id", UUID.class),
                        p.get("location_code", String.class),
                        p.get("lot_id", UUID.class),
                        p.get("lot_code", String.class),
                        atLeastZero(p, "quantity"),
                        p.get("serial_count", Integer.class),
                        p.get("picked_by", UUID.class),
                        p.get("picked_at", OffsetDateTime.class),
                        p.get("note", String.class),
                        serialsOf(p.get("id", UUID.class))));
    return picks;
  }

  private List<PickTaskSerialRow> serialsOf(UUID pickId) {
    return sql.fetch(
            "select s.serial_id, n.serial_number, s.picked_at from pick_task_serial s"
                + " join inventory_serial n on n.tenant_id=s.tenant_id and n.id=s.serial_id"
                + " where s.tenant_id=? and s.pick_id=? order by n.serial_number",
            currentTenant(), pickId)
        .map(
            s ->
                new PickTaskSerialRow(
                    s.get("serial_id", UUID.class),
                    s.get("serial_number", String.class),
                    s.get("picked_at", OffsetDateTime.class)));
  }

  private PickTaskPickRow pick(UUID pickId) {
    return picksOf(
            uuidOf(
                "select task_id from pick_task_pick where tenant_id=? and id=?",
                currentTenant(), pickId))
        .stream()
        .filter(p -> p.id().equals(pickId))
        .findFirst()
        .orElseThrow(() -> notFound("实拣记录 " + pickId + " 不存在"));
  }

  private PickTaskExceptionRow exception(UUID id) {
    Record r =
        sql.fetchOne(
            "select e.*, l.code as location_code, ln.sku_code from pick_task_exception e"
                + " left join warehouse_location l on l.tenant_id=e.tenant_id and l.id=e.location_id"
                + " left join pick_task_line ln on ln.tenant_id=e.tenant_id and ln.id=e.line_id"
                + " where e.tenant_id=? and e.id=?",
            currentTenant(), id);
    if (r == null) throw notFound("异常 " + id + " 不存在");
    return exceptionRow(r);
  }

  private PickTaskExceptionRow exceptionRow(Record r) {
    String kind = r.get("kind", String.class);
    OffsetDateTime resolvedAt = r.get("resolved_at", OffsetDateTime.class);
    return new PickTaskExceptionRow(
        r.get("id", UUID.class),
        kind,
        exceptionLabel(kind),
        r.get("line_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("shortage_quantity", BigDecimal.class),
        r.get("location_id", UUID.class),
        r.get("location_code", String.class),
        r.get("detail", String.class),
        r.get("reported_by", UUID.class),
        r.get("reported_at", OffsetDateTime.class),
        r.get("resolution", String.class),
        r.get("resolved_by", UUID.class),
        resolvedAt,
        r.get("resolve_note", String.class),
        resolvedAt != null);
  }

  // ================================================================ 主数据校验

  /** 打包区解析：显式指定就校验它；没指定就取本仓唯一一个 ACTIVE 的发货暂存位。 */
  private UUID resolveStaging(UUID warehouseId, UUID explicit) {
    UUID tenant = currentTenant();
    if (explicit != null) {
      Record row =
          sql.fetchOne(
              "select id, type, status from warehouse_location"
                  + " where tenant_id=? and warehouse_id=? and id=?",
              tenant, warehouseId, explicit);
      if (row == null) throw notFound("打包区库位 " + explicit + " 不在本任务的仓库里");
      requireStaging(row);
      return explicit;
    }
    var candidates =
        sql.fetch(
            "select id from warehouse_location where tenant_id=? and warehouse_id=?"
                + " and type=? and status='ACTIVE' order by code",
            tenant, warehouseId, STAGING_TYPE);
    if (candidates.isEmpty()) {
      throw bad(
          "这个仓没有启用的发货暂存库位（type=SHIPPING）可以当打包区："
              + "拣完的货必须有一个明确去处，先在本仓建一个发货暂存库位再生成拣货任务");
    }
    if (candidates.size() > 1) {
      throw bad(
          "这个仓有 " + candidates.size()
              + " 个启用的发货暂存库位，不猜哪个是打包区：请显式指定 stagingLocationId");
    }
    return candidates.get(0).get("id", UUID.class);
  }

  private void requireStaging(Record row) {
    String type = row.get("type", String.class);
    String status = row.get("status", String.class);
    if (!STAGING_TYPE.equals(type)) {
      throw bad(
          "打包区必须是发货暂存库位（type=SHIPPING），当前是 " + type
              + "。拣完的货要有一个明确的去处，搬到普通存储位等于没搬");
    }
    if (!"ACTIVE".equals(status)) {
      throw bad("打包区库位已停用（status=" + status + "）");
    }
  }

  /** 库位树层级路径。与 8.1.2.1 出树同一口径（order by path），只出结构字段。 */
  private Map<UUID, String> locationPaths(UUID warehouseId) {
    UUID tenant = currentTenant();
    var rows =
        sql.fetch(
            """
            with recursive t as (
              select l.id, l.code::text as code, 0 as depth
                from warehouse_location l
               where l.tenant_id = ? and l.warehouse_id = ? and l.parent_id is null
              union all
              select c.id, t.code || '|' || c.code, t.depth + 1
                from warehouse_location c
                join t on t.id = c.parent_id
               where c.tenant_id = ? and c.warehouse_id = ?
            )
            select id, code from t
            """,
            tenant, warehouseId, tenant, warehouseId);
    var out = new LinkedHashMap<UUID, String>();
    rows.forEach(r -> out.put(r.get("id", UUID.class), r.get("code", String.class)));
    return out;
  }

  /** 库位元数据（编码 / 类型 / 是否可拣）。 */
  private Map<UUID, LocationMeta> locationMeta(UUID warehouseId) {
    UUID tenant = currentTenant();
    var rows =
        sql.fetch(
            "select id, code, type, pickable, status from warehouse_location"
                + " where tenant_id=? and warehouse_id=?",
            tenant, warehouseId);
    var out = new LinkedHashMap<UUID, LocationMeta>();
    rows.forEach(
        r ->
            out.put(
                r.get("id", UUID.class),
                new LocationMeta(
                    r.get("code", String.class), r.get("type", String.class),
                    r.get("pickable", Boolean.class), r.get("status", String.class))));
    return out;
  }

  /** 租户级的可售质量状态。预占与拣货都只认它（V2 §4.1 / 蓝图「待检品不可售」）。 */
  private UUID qualitySellable(UUID tenant) {
    UUID id =
        uuidOf(
            "select id from inventory_quality_status where tenant_id=? and role='SELLABLE'"
                + " and enabled order by code limit 1",
            tenant);
    if (id == null) {
      throw bad(
          "本租户没有启用中的可售质量状态（inventory_quality_status.role='SELLABLE'）："
              + "拣货只能取可售货，没有可售档就无从拣起");
    }
    return id;
  }

  private Record lock(UUID taskId) {
    Record task =
        sql.fetchOne(
            "select * from pick_task where tenant_id = ? and id = ? for update",
            currentTenant(), taskId);
    if (task == null) throw notFound("拣货任务 " + taskId + " 不存在");
    return task;
  }

  private Record lockLine(UUID taskId, UUID lineId) {
    if (lineId == null) throw bad("必须给 lineId：实拣与异常都要落到具体的拣货段上");
    Record line =
        sql.fetchOne(
            "select * from pick_task_line where tenant_id=? and task_id=? and id=? for update",
            currentTenant(), taskId, lineId);
    if (line == null) throw notFound("拣货段 " + lineId + " 不属于这张拣货任务");
    return line;
  }

  private void event(
      UUID taskId, PickTaskEventType type, String from, String to, UUID actor, OffsetDateTime at,
      String detail) {
    sql.execute(
        "insert into pick_task_event(id,tenant_id,task_id,event_type,from_status,to_status,"
            + "actor_user_id,occurred_at,detail) values(?,?,?,?,?,?,?,?::timestamptz,?)",
        UUID.randomUUID(), currentTenant(), taskId, type.name(), from, to, actor, at, detail);
  }

  // ================================================================ 工具

  private record LocationMeta(String code, String type, boolean pickable, String status) {}

  /** 一段分配。unallocated = 这一行完全没有可分配库存（作业台要看得见）。 */
  private static final class Allocation {
    UUID outboundLineId;
    UUID skuId;
    String skuCode;
    String skuName;
    UUID qualityStatusId;
    UUID locationId;
    UUID lotId;
    String locationPath = "";
    LocationMeta location;
    BigDecimal onHand = BigDecimal.ZERO;
    int locationCount = 1;
    boolean nonPickable;
    boolean unallocated;
    BigDecimal quantity = BigDecimal.ZERO;

    Allocation outboundLineId(UUID v) { this.outboundLineId = v; return this; }
    Allocation skuId(UUID v) { this.skuId = v; return this; }
    Allocation skuCode(String v) { this.skuCode = v; return this; }
    Allocation skuName(String v) { this.skuName = v; return this; }
    Allocation locationId(UUID v) { this.locationId = v; return this; }
    Allocation qualityStatusId(UUID v) { this.qualityStatusId = v; return this; }
    Allocation lotId(UUID v) { this.lotId = v; return this; }
    Allocation locationPath(String v) { this.locationPath = v; return this; }
    Allocation location(LocationMeta v) { this.location = v; return this; }
    Allocation onHand(BigDecimal v) { this.onHand = v; return this; }
    Allocation locationCount(int v) { this.locationCount = v; return this; }
    Allocation nonPickable(boolean v) { this.nonPickable = v; return this; }
    Allocation quantity(BigDecimal v) { this.quantity = v; return this; }

    BigDecimal remaining() { return quantity; }
  }

  // ================================================================ 标量读

  // 本仓库的 jOOQ 版本上，fetchValue(sql, Class, args) 会把那个 Class 当成绑定值
  // （报 "Type class java.lang.Class is not supported"，见 WarehouseLocationTreeService
  // 第 507 行的同一处记录）。所以一律 fetchOne + get(0, Type.class)，
  // 三个小助手把这件事收口，改的时候只改一处。

  /** 计数类标量。查不到当 0 —— 「没有这种行」在计数语境下的答案就是 0。 */
  private long countOf(String query, Object... args) {
    Record row = sql.fetchOne(query, args);
    return row == null ? 0L : row.get(0, Long.class);
  }

  private String textOf(String query, Object... args) {
    Record row = sql.fetchOne(query, args);
    return row == null ? null : row.get(0, String.class);
  }

  private UUID uuidOf(String query, Object... args) {
    Record row = sql.fetchOne(query, args);
    return row == null ? null : row.get(0, UUID.class);
  }

  private static Object[] all(List<Object> args, Object... extra) {
    var out = new ArrayList<Object>(args);
    out.addAll(List.of(extra));
    return out.toArray();
  }

  private UUID currentTenant() {
    return UUID.fromString(sql.fetchOne("select current_setting('app.current_tenant', true)")
        .get(0, String.class));
  }

  private static BigDecimal decimal(Record r, String field) {
    BigDecimal v = r.get(field, BigDecimal.class);
    return v == null ? BigDecimal.ZERO : v;
  }

  /**
   * 数量：NUMERIC(19,6) + BigDecimal，禁浮点；非正数当场拒（「拣 0 件」没有含义）。
   *
   * <p>只用于<b>写入侧</b>校验。读模型上的数量走 {@link #atLeastZero} ——
   * 「实拣 0 件」「这一段还没开始拣」是完全正常的读数，不是非法值。
   */
  private static BigDecimal qty(BigDecimal value, String what) {
    if (value == null) throw bad(what + "不能为空");
    if (value.signum() <= 0) throw bad(what + "必须大于 0，收到的是 " + value.toPlainString());
    return value;
  }

  /** 读模型上的非负数量。null 当 0 —— 这些列都是 NOT NULL DEFAULT 0。 */
  private static BigDecimal atLeastZero(Record r, String field) {
    BigDecimal v = r.get(field, BigDecimal.class);
    return v == null ? BigDecimal.ZERO : v;
  }

  private static String plain(BigDecimal v) {
    return v == null ? "0" : v.stripTrailingZeros().toPlainString();
  }

  private static String text(String s) {
    return s == null ? "" : s.trim();
  }

  /**
   * 时刻解析：<b>显式带偏移量</b>，不走 {@code Timestamp.valueOf(LocalDateTime)} ——
   * 那条路会把东八区的时间静默偏 −28800 秒（PDA 时钟比 DB 时钟靠前几个小时时，
   * 拣货时间会「早到凌晨」，且没有任何报错）。
   */
  private static OffsetDateTime instant(String raw, String what) {
    if (raw == null || raw.isBlank()) return OffsetDateTime.now();
    try {
      OffsetDateTime parsed = OffsetDateTime.parse(raw.trim());
      if (parsed.getOffset().getTotalSeconds() == 0 && raw.trim().length() <= 19) {
        return parsed;
      }
      return parsed;
    } catch (DateTimeParseException e) {
      try {
        return java.time.LocalDateTime.parse(raw.trim())
            .atZone(java.time.ZoneId.systemDefault())
            .toOffsetDateTime();
      } catch (DateTimeParseException e2) {
        throw bad(what + "不是合法时刻：" + raw);
      }
    }
  }

  private static String label(String status) {
    for (PickTaskStatus s : PickTaskStatus.values()) if (s.name().equals(status)) return s.label();
    return status;
  }

  /**
   * 出库单状态的中文标签。
   *
   * <p>刻意复用 {@link OutboundOrderStatus} 这个既有词表（8.3.1.1 交付的），
   * 而不是在本类里再抄一份七态字符串：抄一份的话，哪一边改了中文名，
   * 「拣货完成」页与「出库单列表」页就会对同一个状态说两种话。
   */
  private static String outboundStatusLabel(String status) {
    for (OutboundOrderModels.OutboundOrderStatus s : OutboundOrderModels.OutboundOrderStatus.values()) {
      if (s.name().equals(status)) return s.label();
    }
    return status;
  }

  private static String lineLabel(String status) {
    for (PickTaskLineStatus s : PickTaskLineStatus.values())
      if (s.name().equals(status)) return s.label();
    return status;
  }

  private static String exceptionLabel(String kind) {
    for (PickTaskExceptionKind k : PickTaskExceptionKind.values())
      if (k.name().equals(kind)) return k.label();
    return kind;
  }

  private static String eventLabel(String type) {
    for (PickTaskEventType t : PickTaskEventType.values())
      if (t.name().equals(type)) return t.label();
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