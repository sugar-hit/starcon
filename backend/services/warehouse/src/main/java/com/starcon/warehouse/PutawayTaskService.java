package com.starcon.warehouse;

import static com.starcon.warehouse.PutawayTaskModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
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
 * 8.2.2.1 上架任务：把已收货物从收货暂存位上架到正式库位。
 *
 * <p>蓝图 08 卷 8.2.2.1：「把已收货物从收货暂存位上架到正式库位，生成上架任务派发给作业人员 （支持 PDA/扫码）。输出：上架任务（待执行/执行中/完成）；库存 bucket
 * 位置变更。」 关系段上游 = 8.2.1.1、8.1.2.2，下游 = 8.3.2.1 拣货。
 *
 * <h2>蓝图加粗句就是这个类的全部技术内容</h2>
 *
 * <p>「<b>上架只改位置不改数量</b>，数量在收货时已确定。」它在代码里落成四处：
 *
 * <ol>
 *   <li><b>数量不是入参</b>：{@link #create} 的行输入里没有数量字段，应上架量取自 {@code
 *       goods_receipt_line}（8.2.1.1）并在建单时冻结成 {@code planned_quantity}。 调用方只能「指哪一行搬到哪」，不能「说搬多少」。<br>
 *   <li><b>只动 bucket，不动 balance</b>：一次扫码在同一 {@code inventory_balance} 行上写 <b>一对</b>反向分录（源位 -Q、目标位
 *       +Q）。balance.on_hand 一个数都没变， 变的是 {@code stock_bucket} 上的分布 —— 这正是 V2「balance 的键不含
 *       location_id」 分层设计下的唯一正确表达。<br>
 *   <li><b>净额恒为 0</b>：上面那对分录的代数和是 0，{@link PutawayTaskScanned} 把两个 delta
 *       都返回给前端，让「只改位置」在单次作业上可读，而不只是文档里的一句承诺。<br>
 *   <li><b>不变量由数据库裁决</b>：V109 的 {@code guard_putaway_task()} 在任务转 COMPLETED 时 回读 {@code
 *       inventory_entry} 核对 P1（净额 = 0）、P3（出库分录之和 = 实上架量）， 并逐行核对 P2（实上架量 = 应上架量）。绕过服务层直连 SQL 也绕不过。
 * </ol>
 *
 * <h2>与 8.1.2.2 库位自动分配的分工</h2>
 *
 * <p>8.1.2.2 负责<b>算</b>推荐库位（纯计算不落库），本模块负责<b>搬</b>。所以本类 <b>不重写推荐算法</b>：需要推荐时调用方先打 8.1.2.2 的 {@code
 * /recommend}，人选定后 （V106 的 {@code release}）再建本模块的任务；建单时可带上 {@code allocationId} 作追溯。
 * 人工直接指派库位同样是合法形态 —— 蓝图原文是「库位推荐<b>或</b>指定库位」。
 *
 * <h2>同一事务</h2>
 *
 * <p>{@link #scan} 标注 {@code @Transactional}：源位扣、目标位加、两组分录、扫码台账、
 * 明细实量与单头合计，<b>要么全成、要么全滚</b>。绝不允许出现「货已从源位拿走但没进目标位」 或反之 —— 那正是 P1 要挡的错账，而半截账连 P1 都核对不出来（它连分录都只有一条）。
 */
@Service
public class PutawayTaskService {

  private static final int MAX_PAGE_SIZE = 200;
  private static final int QTY_SCALE = 6;
  private static final int STOCK_SCALE = 4;

  /** 库存分录的 ref_type。V109 的 P1/P3 守卫按这个字面量回读，**不可改**。 */
  private static final String REF_TYPE = "PUTAWAY_TASK";

  /** 排序白名单。与既有 warehouse 模块同一纪律：不接受任意排序键。 */
  private static final List<String> SORTS =
      List.of("CREATED_AT", "TASK_CODE", "PLANNED_QUANTITY", "STATUS", "COMPLETED_AT");

  /** 状态词表，与 V109 的 CHECK 逐字一致。 */
  private static final List<String> STATUSES =
      List.of("PENDING", "IN_PROGRESS", "COMPLETED", "CANCELLED");

  /** 设备类型词表，与 V109 的 CHECK 逐字一致。 */
  private static final List<String> DEVICES = List.of("PDA", "MOBILE", "DESKTOP");

  private final DSLContext sql;

  public PutawayTaskService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：把「数量不是输入」「只改位置」「硬规则」「上下游可用性」显式报出来。
   *
   * <p>存在的理由与既有 warehouse 模块一致：让调用方从一次 GET 就能知道 「能不能指定库位」「能不能改数量」「8.3.2.1 拣货能不能直接消费我的结果」， 而不是从 400
   * 或空数组里猜。
   */
  public PutawayCapabilities capabilities() {
    return new PutawayCapabilities(
        REF_TYPE,
        true,
        true,
        "8.2.1.1 收货单与收货明细行是**本服务的表**（V70），所以这里用复合外键真钉死："
            + "一张上架任务的每一行都必须来自一张真实的收货明细行，"
            + "「凭空造一个上架清单」在 DB 层就插不进来。蓝图说「数量在收货时已确定」，"
            + "而那条数量的来源就是 goods_receipt_line.received_quantity。",
        "8.1.2.2 库位自动分配（V106）负责**算**推荐库位，本模块负责**搬**，两者不重复："
            + "本模块不重写推荐算法，也不在本表复制一份推荐结果。需要推荐时先打 8.1.2.2 的 "
            + "/recommend，人选定（release）后建本模块的任务并带上 allocationId 追溯。"
            + "人工直接指派库位同样合法 —— 蓝图原文是「库位推荐**或**指定库位」。"
            + "质量状态→库位类型的硬规则**复用 V106 的 location_allocation_required_type(role)**，"
            + "不抄第二份 CASE：抄一份就等于将来改一处忘一处，而残次品混放是蓝图点名的红线。",
        "蓝图加粗句「上架只改位置不改数量」在本模块落成三件事："
            + "① 建单行**没有数量字段**，应上架量取自收货明细行并在建单时冻结成 planned_quantity；"
            + "② 一次扫码在同一 balance 行上写一对反向分录（源位 -Q、目标位 +Q），"
            + "balance.on_hand 一个数都不变，变的是 stock_bucket 的分布；"
            + "③ 完成时数据库回读账本核对净额恰好为 0。"
            + "本模块**不接受**任何形式的数量修正 —— 收货时定错数量的出口是收货差异（8.2.1.2），"
            + "不是在上架单上悄悄改数。",
        List.of(
            new PutawayCapabilities.PutawayUpstreamModule(
                "8.2.1.1",
                "收货单",
                "warehouse",
                true,
                "待上架清单的来源：每一行都指向一条 goods_receipt_line，"
                    + "应上架量取它的实收量并冻结建单。收货方事后改单不影响已建的任务 —— "
                    + "在场的作业单不该因为后台改了个数就凭空变形。"),
            new PutawayCapabilities.PutawayUpstreamModule(
                "8.1.2.2",
                "库位自动分配",
                "warehouse",
                true,
                "推荐库位的生产者。本模块消费**已选定/已释放**的那一条决策（allocationId）作追溯，"
                    + "但不重算推荐、不复制推荐结果。需要推荐请先打它的 /recommend。")),
        List.of(
            new PutawayCapabilities.PutawayDownstreamModule(
                "8.3.2.1",
                "拣货任务",
                "warehouse",
                false,
                "蓝图下游。完成的货已经落在正式库位上，拣货可以按新库位取货 —— "
                    + "但 8.3.2.1 尚未交付，所以本模块**不**声称下游可用，"
                    + "也不假装已经推了事件（推事件是下游交付时的接线工作，不是现在写个空投递）。"),
            new PutawayCapabilities.PutawayDownstreamModule(
                "8.5.2.1",
                "库位利用率",
                "warehouse",
                true,
                "已交付。本模块的 targetLocationStats（多少货正在往哪些库位搬）"
                    + "与它的占用口径互补：8.5.2.1 看的是**现状**，本模块看的是**正在搬的承诺**。"),
            new PutawayCapabilities.PutawayDownstreamModule(
                "7",
                "库存",
                "warehouse",
                true,
                "复用了 V1 既有 inventory_transaction / inventory_entry 与 V2 的 stock_bucket。"
                    + "一次上架 = 一条 inventory_transaction（ref_type=PUTAWAY_TASK）"
                    + " + 每次扫码一对反向分录。本服务是这两张表在**这条路径**上的唯一写入者。")),
        "P1：状态 COMPLETED 时，该任务那条 inventory_transaction 的全部 inventory_entry 的 "
            + "on_hand_delta 之和 **= 0**（这就是「只改位置不改数量」的字面形态）；"
            + "P2：状态 COMPLETED 时，每一行 putaway_quantity **恰好等于** planned_quantity；"
            + "P3：状态 COMPLETED 时，账上负向分录绝对值之和 = 明细实上架量之和"
            + "（P1 抓不到「少搬」：源位减 90、目标位加 90 净额仍是 0，所以必须独立核对）。"
            + "三条都落在 V109 的行级 CHECK + 合计回写触发器 + 回读账本分录的守卫触发器上，"
            + "绕过服务层直连 SQL 也绕不过。",
        "一张任务一条 inventory_transaction：UNIQUE(tenant_id,ref_type,ref_id) 顺带给整张任务单幂等，"
            + "而「每扫一次就新建一条交易」会让「这批货的完整上架账」散成 N 条互不相干的交易，"
            + "P1 也就无从核对（净额只在单条交易内才说得清）。"
            + "扫码台账 putaway_task_scan 是 append-only：UPDATE/DELETE 被权限与触发器两道挡下。");
  }

  // ================================================================ 建单

  /**
   * 建上架任务（PENDING 待执行）。
   *
   * <p>建单<b>只落单据与明细，不动库存</b>：货还在收货暂存位。真正的移动发生在 {@link #scan}。两者分开是为了让「任务先生成、班后再派工」这类现场节奏成立，
   * 也为了让一张没开工的任务可以被 {@link #cancel} 作废而<b>不留任何账</b>。
   */
  @Transactional
  public PutawayTaskCreated create(PutawayTaskCreateInput input, UUID actorId) {
    Record receipt =
        sql.fetchOne(
            "select id,receipt_code,warehouse_id,legal_entity_id,status from goods_receipt where id = ?",
            input.receiptId());
    if (receipt == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "收货单 " + input.receiptId() + " 不存在或无权查看");
    }
    String receiptStatus = receipt.get("status", String.class);
    // 上架已收的货。草稿收货单还没有实收数字，收货方改单也不该改掉已建的上架任务，
    // 但一张**从未收过货**的收货单上根本不该长出上架任务。
    if ("DRAFT".equals(receiptStatus)) {
      throw bad(
          "收货单 "
              + receipt.get("receipt_code", String.class)
              + " 当前是 DRAFT（草稿，尚未实收）："
              + "上架是「把已收货物搬到正式库位」，一张没收过货的单子上没有可上架的货");
    }

    UUID warehouseId = receipt.get("warehouse_id", UUID.class);
    UUID legalEntityId = receipt.get("legal_entity_id", UUID.class);

    if (input.allocationId() != null) {
      // 8.1.2.2 的决策：存在、属于本租户，且确实是这张收货单的。
      // 刻意不建外键（V109 文件头第四节：上游决策可被撤销，已建任务不应随之失效）。
      Record allocation =
          sql.fetchOne(
              "select receipt_id,status from location_allocation where id = ?",
              input.allocationId());
      if (allocation == null) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "上架决策 " + input.allocationId() + " 不存在或无权查看：" + "库位推荐属 8.1.2.2，本模块只消费**已选定**的那一条");
      }
      if (!input.receiptId().equals(allocation.get("receipt_id", UUID.class))) {
        throw bad(
            "上架决策 "
                + input.allocationId()
                + " 属于另一张收货单，而本次建单用的是 "
                + input.receiptId()
                + "：决策与任务必须追溯到同一批货");
      }
      if ("CANCELLED".equals(allocation.get("status", String.class))) {
        throw bad(
            "上架决策 " + input.allocationId() + " 已被撤销（CANCELLED）：" + "撤销之后要重新指派库位再建任务，而不是沿用一个已作废的决策");
      }
    }

    // ---- 逐行校验：这一段只报中文人话，权威判据在 V109 的触发器（那里绕不过）
    var seenReceiptLines = new HashSet<UUID>();
    var rows = new ArrayList<PutawayTaskModels.PutawayTaskLineInput>();
    for (var line : input.lines()) {
      if (!seenReceiptLines.add(line.receiptLineId())) {
        throw bad(
            "收货明细行 " + line.receiptLineId() + " 在这张任务单里出现了两次：" + "同一批货同时指向两个目标库位，搬的时候会有一半无处可放");
      }
      if (line.requiredCapacity() != null && line.requiredCapacity().signum() < 0) {
        throw bad("需求容量不能为负（可空表示「没量过」，但不能是负数）");
      }
      if (line.requiredWeight() != null && line.requiredWeight().signum() < 0) {
        throw bad("需求承重不能为负（可空表示「没量过」，但不能是负数）");
      }
      rows.add(line);
    }

    UUID taskId = UUID.randomUUID();
    String taskCode = String.format("PUT-%06d", nextCode());
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "insert into putaway_task(id,tenant_id,task_code,receipt_id,receipt_code,warehouse_id,"
            + "legal_entity_id,allocation_id,status,notes,created_by)"
            + " values(?,?,?,?,?,?,?,?,'PENDING',?,?)",
        taskId,
        currentTenant(),
        taskCode,
        input.receiptId(),
        receipt.get("receipt_code", String.class),
        warehouseId,
        legalEntityId,
        input.allocationId(),
        nz(input.notes()),
        actorId);

    for (var line : rows) {
      // 收货明细行：真外键（同一服务）。这里的显式校验只为给出可读的 404/400。
      ReceiptLineInfo info = requireReceiptLine(line.receiptLineId(), input.receiptId());
      requireSku(info.skuId(), "上架 SKU");
      requireLocation(warehouseId, line.targetLocationId(), "目标库位");
      if (line.lotId() != null
          && sql.fetchOne("select 1 from inventory_lot where id = ?", line.lotId()) == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批次 " + line.lotId() + " 不存在或无权使用");
      }

      // 质量状态、源 bucket、余额行、容量/承重、混放规则 —— 全部由 V109 的
      // guard_putaway_task_line_placement() 在 BEFORE INSERT 裁决，这里不重复实现，
      // 只在插入后把触发器算出来的 source_bucket_id / balance_id 读回来做回报。
      // **应上架量传 1 让触发器先跑通**，再按收货行的实收量重写 —— 不这样做的话
      // 「源位手数 ≥ 应上架量」这条守卫会在我们还没写对数量之前就先拒掉，
      // 报错信息会指向一个调用方根本没填错的字段。
      UUID lineId = UUID.randomUUID();
      sql.execute(
          "insert into putaway_task_line(id,tenant_id,task_id,receipt_line_id,sku_id,sku_code,"
              + "quality_status_id,source_location_id,target_location_id,lot_id,planned_quantity,"
              + "required_capacity,required_weight,note)"
              + " values(?,?,?,?,?,?,?,?,?,?,1,?,?,?)",
          lineId,
          currentTenant(),
          taskId,
          line.receiptLineId(),
          info.skuId(),
          info.skuCode(),
          info.qualityStatusId(),
          info.receivingLocationId(),
          line.targetLocationId(),
          line.lotId(),
          line.requiredCapacity(),
          line.requiredWeight(),
          nz(line.note()));

      // 触发器已定位 source_bucket_id / balance_id；把冻结的实收量写进去。
      // planned_quantity 是本次**唯一**一次从收货行取数的位置，之后一律不再回读它。
      BigDecimal planned = info.receivedQuantity();
      sql.execute(
          "update putaway_task_line set planned_quantity=? where id=? and tenant_id=?",
          planned,
          lineId,
          currentTenant());
    }

    // 合计列的唯一写入者是 V109 的 refresh_putaway_task_totals() 触发器，
    // 所以这里**读回来**而不是自己算 —— 服务层算的数与账上的数可能差一位。
    Record totals =
        sql.fetchOne(
            "select detail_line_count,planned_quantity from putaway_task where id = ?", taskId);

    return new PutawayTaskCreated(
        taskId,
        taskCode,
        "PENDING",
        longOf(totals, "detail_line_count"),
        decimal(totals, "planned_quantity"),
        receipt.get("receipt_code", String.class),
        now,
        "上架任务已生成（待执行），**货还没动**：货仍在收货暂存位，真正搬动发生在 PDA 扫码时。"
            + "应上架量取自收货明细行的实收量并在建单时冻结 —— 本模块**不接受数量修正**，"
            + "收货时定错数量的出口是收货差异（8.2.1.2），不是在上架单上悄悄改数。"
            + "目标库位的类型由质量状态决定（可售→存储 / 待检→待检 / 残次→残次），"
            + "这条硬规则由数据库裁决，不接受混放。");
  }

  // ================================================================ 派工

  /**
   * 派工：把任务指给一位作业人员（蓝图输入第三项「作业人员分配」）。
   *
   * <p>派工<b>不动库存</b>，且允许改派：现场换人顶班是常态。 只有终态（PENDING 之前 / IN_PROGRESS 都可以）之外的才拒绝 —— 已完成或已取消的
   * 单子再改派工会让「这批货是谁搬的」有了第二个答案。
   */
  @Transactional
  public PutawayTaskRow assign(UUID taskId, PutawayTaskAssignInput input, UUID actorId) {
    Record task = requireNotTerminal(taskId);
    OffsetDateTime at = OffsetDateTime.now();
    sql.execute(
        "update putaway_task set assignee_id=?,assigned_at=?::timestamptz,assigned_by=?,"
            + "version=version+1,updated_at=now() where id=?",
        input.assigneeId(),
        at,
        actorId,
        taskId);
    return detail(taskId).task();
  }

  // ================================================================ 开始作业

  /**
   * 开始作业：PENDING → IN_PROGRESS。
   *
   * <p>「开始」本身<b>不动库存</b>，只是把路由字段锁死：V109 的明细守卫一旦看到 IN_PROGRESS 就不再允许改目标库位/源位/应上架量 —— 因为部分扫码已经把货搬到
   * 目标库位了，此时改路由会让已入账的分录与单据上的「搬到哪」对不上。
   */
  @Transactional
  public PutawayTaskRow start(UUID taskId, PutawayTaskStartInput input, UUID actorId) {
    Record task = lockTask(taskId);
    String status = task.get("status", String.class);
    if ("IN_PROGRESS".equals(status)) {
      return detail(taskId).task();
    }
    if (!"PENDING".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "上架任务 " + task.get("task_code", String.class) + " 当前是 " + status + "，只有待执行的任务可以开始作业");
    }
    OffsetDateTime at = OffsetDateTime.now();
    sql.execute(
        "update putaway_task set status='IN_PROGRESS',started_at=?::timestamptz,started_by=?,"
            + "version=version+1,updated_at=now() where id=?",
        at,
        actorId,
        taskId);
    return detail(taskId).task();
  }

  // ================================================================ PDA 扫码（核心）

  /**
   * 一次 PDA 扫码上架：<b>源位扣、目标位加、一个本地事务</b>。
   *
   * <p>这是本模块唯一动库存的地方，也是「只改位置不改数量」真正发生的一步。 一次扫码在<b>同一条 inventory_balance 行</b>上写<b>一对</b>反向分录： 源
   * bucket -Q、目标 bucket +Q。balance.on_hand 全程一个数都没动， 变的只有 stock_bucket 的分布 —— 这是 V2「balance 的键刻意不含
   * location_id」 分层设计下唯一正确的表达。
   *
   * <p><b>顺序</b>：先锁任务与明细（防并发重复扫），再扣源位、加目标位、记两条分录。 源位手数不足会在扣之前就抛异常回滚，此时目标位<b>还没有</b>被加 ——
   * 反过来的话，加完目标位再发现源位不够，货就凭空多了一份。
   *
   * <p><b>幂等</b>：同一次扫码带同一个 {@code scanReference} 重发（PDA 网络抖动是现场常态） 会撞 {@code putaway_task_scan}
   * 的部分唯一索引。少了这道，同一批货会被搬两遍 （源位扣两次、目标位加两次）<b>而 P1 的净额仍然是 0</b> —— 这是「只改位置」这条不变量 唯一抓不到的 bug
   * 形态，所以幂等必须单独立一道。
   */
  @Transactional
  public PutawayTaskScanned scan(UUID taskId, PutawayTaskScanInput input, UUID actorId) {
    if (!DEVICES.contains(input.deviceType())) {
      throw bad("设备类型只能是 " + DEVICES + " 之一，收到的是 " + input.deviceType());
    }
    Record task = lockTask(taskId);
    String status = task.get("status", String.class);
    if ("COMPLETED".equals(status) || "CANCELLED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "上架任务 "
              + task.get("task_code", String.class)
              + " 当前是 "
              + status
              + "，不能继续扫码："
              + ("COMPLETED".equals(status) ? "货已经在正式库位上了，再扫一次等于凭空多搬一批" : "任务已取消，货还在收货暂存位"));
    }

    // 明细：锁住它，让「累计实上架量」在并发扫码下不会两个人各加各的。
    Record line =
        sql.fetchOne(
            "select * from putaway_task_line where id = ? and task_id = ? for update",
            input.lineId(),
            taskId);
    if (line == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "上架明细 " + input.lineId() + " 不属于任务 " + taskId + "或不存在");
    }

    BigDecimal planned = decimal(line, "planned_quantity");
    BigDecimal already = decimal(line, "putaway_quantity");
    BigDecimal want = stockExact(qty(input.quantity(), "本次扫码量"), "本次扫码量");
    BigDecimal after = already.add(want);
    if (after.compareTo(planned) > 0) {
      throw bad("这一行累计扫码会达到 " + after + "，超过应上架量 " + planned + "：" + "上架只改位置不改数量，超量上架等于凭空把这批货搬出库外");
    }
    // 源位必须真的还有这么多货。定位用 stock_bucket 的唯一六元组
    // （V2 的 UNIQUE NULLS NOT DISTINCT 保证它唯一），而不是相信建单时记下的那个 id ——
    // 中间可能有人动过源位。
    UUID sourceBucketId = line.get("source_bucket_id", UUID.class);
    UUID balanceId = line.get("balance_id", UUID.class);
    UUID skuId = line.get("sku_id", UUID.class);
    UUID sourceLocationId = line.get("source_location_id", UUID.class);
    UUID targetLocationId = line.get("target_location_id", UUID.class);
    UUID lotId = line.get("lot_id", UUID.class);

    BigDecimal sourceOnHand =
        sql.fetchOne("select on_hand from stock_bucket where id = ?", sourceBucketId)
            .get("on_hand", BigDecimal.class);
    if (sourceOnHand.compareTo(want) < 0) {
      throw bad(
          "源库位上只有 "
              + sourceOnHand
              + " 在手，本次要搬 "
              + want
              + "："
              + "超源位库存上架等于凭空多出这批货（P1 会让净额仍为 0 而抓不到，只有这里能抓）");
    }

    OffsetDateTime at = input.scannedAt() == null ? OffsetDateTime.now() : input.scannedAt();
    String scanReference = nz(input.scanReference());

    // 第一次扫码时建那条唯一的 inventory_transaction（ref_type/ref_id 唯一约束顺带给
    // 整张任务单幂等）。之后每次扫码都往这条交易上继续追加分录。
    // 单号存在**单头**上（一张任务一条交易），明细行里没有这一列 —— 这里刻意不冗余，
    // 免得两处各存一份而其中一份漂了。
    UUID transactionId = task.get("inventory_transaction_id", UUID.class);
    if (transactionId == null) {
      transactionId = UUID.randomUUID();
      sql.execute(
          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,"
              + "actor_id,notes) values(?,?,?,?,?,?::timestamptz,?,?)",
          transactionId,
          currentTenant(),
          // 上架不动在手总量，所以它不是收货也不是发货 —— 归 ADJUSTMENT，
          // 但净额恒 0（P1），与真正的调整单在账上形状完全不同。
          "ADJUSTMENT",
          REF_TYPE,
          taskId,
          at,
          actorId,
          "上架任务扫码");
      sql.execute(
          "update putaway_task set inventory_transaction_id=?,version=version+1,"
              + "updated_at=now() where id=?",
          transactionId,
          taskId);
    }

    // ---- 目标位 bucket：没有就**直接以本次搬入的量建行**（不是先建 0 再 UPDATE）。
    // 先建 0 再 UPDATE 会撞 V2 的 guard_bucket_movement_time：建行与搬入用同一个业务时刻，
    // 两次 UPDATE 的 last_movement_at 相等，触发器按「必须晚于原值」直接拒。
    var target =
        ensureTargetBucket(
            warehouseOf(task),
            skuId,
            balanceId,
            targetLocationId,
            lotId,
            line.get("quality_status_id", UUID.class),
            want,
            at);
    UUID targetBucketId = target.id();

    // ---- 源位 -Q。V2 的 guard_bucket_movement_time 要求业务时刻**严格前进**，
    // 所以取「作业员填的时刻」与「原值 +1 微秒」中较晚的一个（见 nextMovementAfter）。
    sql.execute(
        "update stock_bucket set on_hand = on_hand - ?,"
            + "last_movement_at = ?::timestamptz where id = ?",
        want,
        nextMovementAfter(sourceBucketId, at),
        sourceBucketId);
    // ---- 目标位 +Q。**同一条 balance、同一时刻**，所以 P1 的净额恰好为 0。
    // 只有**已存在**的目标行才需要 UPDATE —— 新建的那行已经带着 want 建好了，
    // 再加一次就是凭空多出这批货（守恒触发器会在提交时当场掀掉整个事务）。
    if (!target.created() && !targetBucketId.equals(sourceBucketId)) {
      sql.execute(
          "update stock_bucket set on_hand = on_hand + ?,"
              + "last_movement_at = ?::timestamptz where id = ?",
          want,
          nextMovementAfter(targetBucketId, at),
          targetBucketId);
    }

    // ---- 两条分录，**同一条 balance**：这正是 P1「净额 = 0」能被核对的前提。
    // 顺序是「先出后进」，与 8.4.2.1 同一纪律：出库失败时目标位还没被加，
    // 不会出现「货已进目标位但源位没扣」的凭空多货。
    UUID entryOut = UUID.randomUUID();
    sql.execute(
        "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
            + "reserved_delta) values(?,?,?,?,?,0)",
        entryOut,
        currentTenant(),
        transactionId,
        balanceId,
        want.negate());
    UUID entryIn = UUID.randomUUID();
    sql.execute(
        "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
            + "reserved_delta) values(?,?,?,?,?,0)",
        entryIn,
        currentTenant(),
        transactionId,
        balanceId,
        want);

    // ---- 明细实上架量。V109 的行级 CHECK 钉住 0 ≤ putaway_quantity ≤ planned_quantity，
    // 且 IN_PROGRESS 下守卫禁止把它往回改。
    sql.execute(
        "update putaway_task_line set putaway_quantity=?,target_bucket_id=?,"
            + "completed_at=case when ? = ? then ?::timestamptz else completed_at end,"
            + "completed_by=case when ? = ? then ?::uuid else completed_by end,"
            + "updated_at=now() where id=?",
        after,
        targetBucketId,
        after,
        planned,
        at,
        after,
        planned,
        actorId,
        input.lineId());

    // ---- 扫码台账（append-only）。两条分录的 id 记在这里，
    // 于是「这条扫码在账上对应哪两条分录」可以逐条回溯。
    sql.execute(
        "insert into putaway_task_scan(id,tenant_id,task_id,line_id,scanned_quantity,"
            + "source_location_id,target_location_id,scan_reference,device_type,scanned_at,"
            + "scanner_id,inventory_entry_out_id,inventory_entry_in_id,note)"
            + " values(?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?)",
        UUID.randomUUID(),
        currentTenant(),
        taskId,
        input.lineId(),
        want,
        sourceLocationId,
        targetLocationId,
        scanReference,
        input.deviceType(),
        at,
        actorId,
        entryOut,
        entryIn,
        nz(input.note()));

    // 第一次扫码把任务推进到执行中（没开始就扫码不可能，先扫码再回填 started_at 会失真）。
    String taskStatus = status;
    if ("PENDING".equals(status)) {
      sql.execute(
          "update putaway_task set status='IN_PROGRESS',started_at=coalesce(started_at,"
              + "?::timestamptz),started_by=coalesce(started_by,?::uuid),version=version+1,"
              + "updated_at=now() where id=? and status='PENDING'",
          at,
          actorId,
          taskId);
      taskStatus = "IN_PROGRESS";
    }
    boolean lineCompleted = after.compareTo(planned) == 0;

    return new PutawayTaskScanned(
        taskId,
        task.get("task_code", String.class),
        input.lineId(),
        skuId,
        line.get("sku_code", String.class),
        sourceLocationId,
        targetLocationId,
        want,
        planned,
        after,
        lineCompleted,
        transactionId,
        entryOut,
        entryIn,
        stock(want.negate()),
        want,
        at,
        taskStatus,
        statusLabel(taskStatus),
        lineCompleted
            ? "这一行搬完了（在库位上：源位扣了、目标位加上了，账上是一对反向分录，净额 0 —— "
                + "上架只改位置，数量一个没动）。整张任务单搬完后还需要点「完成上架」，"
                + "数据库会在那一刻回读账本核对 P1/P2/P3。"
            : "这一行还差 " + planned.subtract(after) + " 没搬。继续扫就行；单据可以一直停在执行中，直到所有行都搬完。");
  }

  // ================================================================ 完成

  /**
   * 完成上架：IN_PROGRESS → COMPLETED。
   *
   * <p>本方法<b>自己几乎不校验</b>：P1/P2/P3 三条不变量由 V109 的 {@code guard_putaway_task()} 在这条 UPDATE 上回读账本分录裁决。
   * 「半张单搬到一半就说完成」在这里一定会被拒（23514），而不是靠应用层记得判断。
   */
  @Transactional
  public PutawayTaskRow complete(UUID taskId, PutawayTaskCompleteInput input, UUID actorId) {
    Record task = lockTask(taskId);
    String status = task.get("status", String.class);
    if ("COMPLETED".equals(status)) {
      return detail(taskId).task();
    }
    if (!"IN_PROGRESS".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "上架任务 " + task.get("task_code", String.class) + " 当前是 " + status + "，只有执行中的任务可以完成");
    }
    sql.execute(
        "update putaway_task set status='COMPLETED',completed_at=?::timestamptz,completed_by=?,"
            + "version=version+1,updated_at=now(),notes=case when ? = '' then notes "
            + "else ? end where id=?",
        OffsetDateTime.now(),
        actorId,
        nz(input == null ? null : input.note()),
        nz(input == null ? null : input.note()),
        taskId);
    return detail(taskId).task();
  }

  // ================================================================ 取消

  /**
   * 取消任务（<b>不动库存</b>）。
   *
   * <p>取消只对 PENDING / IN_PROGRESS 开放，且**必须还没有搬过货**： 一旦有扫码分录，货已经在目标库位上了，取消就等于宣布「这批货还在收货暂存位」——
   * 那是一张比「重复搬运」严重得多的错账。
   */
  @Transactional
  public PutawayTaskRow cancel(UUID taskId, PutawayTaskCancelInput input, UUID actorId) {
    Record task = lockTask(taskId);
    String status = task.get("status", String.class);
    if ("COMPLETED".equals(status) || "CANCELLED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "上架任务 " + task.get("task_code", String.class) + " 当前是 " + status + "，不能取消");
    }
    Long scans =
        sql.fetchOne("select count(*) from putaway_task_scan where task_id = ?", taskId)
            .get(0, Long.class);
    if (scans != null && scans > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "上架任务 "
              + task.get("task_code", String.class)
              + " 已经扫了 "
              + scans
              + " 次，货已经在往"
              + "目标库位搬了：取消等于宣布「这批货还在收货暂存位」，而库里它已经不在那儿了。"
              + "如果只是搬错了地方，那是一次新的上架/调拨，不是取消");
    }
    sql.execute(
        "update putaway_task set status='CANCELLED',cancelled_at=?::timestamptz,cancelled_by=?,"
            + "cancel_reason=?,version=version+1,updated_at=now() where id=?",
        OffsetDateTime.now(),
        actorId,
        nz(input.reason()),
        taskId);
    return detail(taskId).task();
  }

  // ================================================================ 查询

  /**
   * 上架任务列表（真分页 + 真筛选 + 排序白名单 + 汇总 + 目标库位分布）。
   *
   * <p>汇总与明细计数用<b>标量子查询</b>而不是 join 展开：明细 join 上来会让「单头一行 × N 条明细」 相乘，于是 {@code
   * sum(planned_quantity)} 被放大 N 倍 —— 聚合数字对不上是这类页面最常见的 bug，而且静态检查全绿（与 8.4.2.1 同一纪律）。
   */
  public PutawayTaskPage page(
      String status,
      UUID warehouseId,
      UUID receiptId,
      UUID assigneeId,
      UUID skuId,
      String keyword,
      String sort,
      int page,
      int size) {
    int safePage = Math.max(0, page);
    int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    String sortKey = sort == null ? "CREATED_AT" : sort.trim().toUpperCase(Locale.ROOT);
    if (!SORTS.contains(sortKey)) {
      throw new IllegalArgumentException("不支持的排序键：" + sort + "（可选 " + SORTS + "）");
    }
    if (status != null && !status.isBlank() && !STATUSES.contains(status.trim().toUpperCase())) {
      throw new IllegalArgumentException("不支持的状态：" + status + "（可选 " + STATUSES + "）");
    }

    var binds = new ArrayList<Object>();
    var where = new StringBuilder();
    if (status != null && !status.isBlank()) {
      where.append(" and t.status = ?");
      binds.add(status.trim().toUpperCase());
    }
    if (warehouseId != null) {
      where.append(" and t.warehouse_id = ?");
      binds.add(warehouseId);
    }
    if (receiptId != null) {
      where.append(" and t.receipt_id = ?");
      binds.add(receiptId);
    }
    if (assigneeId != null) {
      where.append(" and t.assignee_id = ?");
      binds.add(assigneeId);
    }
    // 按 SKU 筛必须落到明细上 —— 单头没有 sku_id（一张任务单可以有多个 SKU）。
    String lineJoin = "";
    if (skuId != null) {
      lineJoin =
          " and exists (select 1 from putaway_task_line sl"
              + " where sl.tenant_id = t.tenant_id and sl.task_id = t.id and sl.sku_id = ?)";
      binds.add(skuId);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (t.task_code ilike ? or t.receipt_code ilike ?)");
      String pattern = "%" + keyword.trim().replace("%", "\\%").replace("_", "\\_") + "%";
      for (int i = 0; i < 2; i++) {
        binds.add(pattern);
      }
    }
    String clause = where + lineJoin;

    Record summary =
        sql.fetchOne(
            "select count(*) as total,"
                + " coalesce(sum(t.planned_quantity),0) as total_planned,"
                + " coalesce(sum(t.putaway_quantity),0) as total_putaway,"
                + " count(*) filter (where t.status='PENDING') as pending_count,"
                + " count(*) filter (where t.status='IN_PROGRESS') as in_progress_count,"
                + " count(*) filter (where t.status='COMPLETED') as completed_count,"
                + " count(*) filter (where t.status='CANCELLED') as cancelled_count,"
                + " count(*) filter (where t.assignee_id is null) as unassigned_count"
                + " from putaway_task t where true"
                + clause,
            binds.toArray());

    List<PutawayTaskRow> items =
        sql.fetch(
                "select t.id,t.task_code,t.status,t.receipt_id,t.receipt_code,t.warehouse_id,"
                    + "t.legal_entity_id,t.allocation_id,t.assignee_id,t.assigned_at,t.started_at,"
                    + "t.completed_at,t.cancelled_at,t.cancel_reason,t.detail_line_count,"
                    + "t.planned_quantity,t.putaway_quantity,t.scan_count,"
                    + "t.inventory_transaction_id,t.created_at,t.created_by,t.notes"
                    + " from putaway_task t where true"
                    + clause
                    + orderBy(sortKey)
                    + " limit ? offset ?",
                append(binds, safeSize, safePage * safeSize).toArray())
            .map(this::row);

    // 目标库位分布：单独一条 GROUP BY，不 join 进分页查询（否则合计被明细行数放大）。
    List<PutawayTargetStat> targetStats =
        sql.fetch(
                "select l.id as location_id, l.code as location_code, l.type as location_type,"
                    + " count(*) as line_count,"
                    + " coalesce(sum(sl.planned_quantity),0) as planned_quantity,"
                    + " coalesce(sum(sl.putaway_quantity),0) as putaway_quantity"
                    + " from putaway_task_line sl"
                    + " join putaway_task t on t.tenant_id = sl.tenant_id and t.id = sl.task_id"
                    + " join warehouse_location l on l.tenant_id = sl.tenant_id"
                    + "   and l.id = sl.target_location_id"
                    + " where true"
                    + clause
                    + " group by l.id, l.code, l.type"
                    + " order by sum(sl.planned_quantity) desc, l.code",
                binds.toArray())
            .map(
                d ->
                    new PutawayTargetStat(
                        d.get("location_id", UUID.class),
                        d.get("location_code", String.class),
                        d.get("location_type", String.class),
                        longOf(d, "line_count"),
                        decimal(d, "planned_quantity"),
                        decimal(d, "putaway_quantity")));

    return new PutawayTaskPage(
        items,
        longOf(summary, "total"),
        safePage,
        safeSize,
        new PutawayTaskSummary(
            longOf(summary, "total"),
            decimal(summary, "total_planned"),
            decimal(summary, "total_putaway"),
            longOf(summary, "pending_count"),
            longOf(summary, "in_progress_count"),
            longOf(summary, "completed_count"),
            longOf(summary, "cancelled_count"),
            longOf(summary, "unassigned_count")),
        targetStats);
  }

  /** 任务详情：单头 + 明细（源位/目标位/两量）+ 扫码台账 + 账本分录。 */
  public PutawayTaskDetail detail(UUID taskId) {
    Record task = requireTask(taskId);

    List<PutawayTaskLineRow> lines =
        sql.fetch(
                "select sl.id,sl.receipt_line_id,sl.sku_id,sl.sku_code,sl.quality_status_id,"
                    + " q.code as quality_code, q.role as quality_role,"
                    + " sl.source_location_id, src.code as source_location_code,"
                    + " sl.source_bucket_id, sl.target_location_id,"
                    + " tgt.code as target_location_code, tgt.type as target_location_type,"
                    + " sl.target_bucket_id, sl.lot_id, sl.planned_quantity, sl.putaway_quantity,"
                    + " sl.required_capacity, sl.required_weight, sl.balance_id, sl.completed_at,"
                    + " sl.note,"
                    + " tgt.capacity as target_capacity, tgt.weight_limit as target_weight"
                    + " from putaway_task_line sl"
                    + " join inventory_quality_status q on q.tenant_id = sl.tenant_id"
                    + "   and q.id = sl.quality_status_id"
                    + " join warehouse_location src on src.tenant_id = sl.tenant_id"
                    + "   and src.id = sl.source_location_id"
                    + " join warehouse_location tgt on tgt.tenant_id = sl.tenant_id"
                    + "   and tgt.id = sl.target_location_id"
                    + " where sl.tenant_id = ? and sl.task_id = ?"
                    + " order by sl.sku_code, sl.id",
                currentTenant(),
                taskId)
            .map(
                l -> {
                  BigDecimal planned = decimal(l, "planned_quantity");
                  BigDecimal putaway = decimal(l, "putaway_quantity");
                  BigDecimal needCap = decimalOrNull(l, "required_capacity");
                  BigDecimal needWt = decimalOrNull(l, "required_weight");
                  BigDecimal capLimit = decimalOrNull(l, "target_capacity");
                  BigDecimal wtLimit = decimalOrNull(l, "target_weight");
                  return new PutawayTaskLineRow(
                      l.get("id", UUID.class),
                      l.get("receipt_line_id", UUID.class),
                      l.get("sku_id", UUID.class),
                      l.get("sku_code", String.class),
                      l.get("quality_status_id", UUID.class),
                      l.get("quality_code", String.class),
                      l.get("quality_role", String.class),
                      l.get("source_location_id", UUID.class),
                      l.get("source_location_code", String.class),
                      l.get("source_bucket_id", UUID.class),
                      l.get("target_location_id", UUID.class),
                      l.get("target_location_code", String.class),
                      l.get("target_location_type", String.class),
                      l.get("target_bucket_id", UUID.class),
                      l.get("lot_id", UUID.class),
                      planned,
                      putaway,
                      planned.subtract(putaway),
                      putaway.compareTo(planned) == 0,
                      needCap,
                      needWt,
                      // 「没量过」与「量出来够」必须能分开（与 8.1.2.2 同一口径）：
                      // 任一为空就标 false，并让前端显示「未评估」而不是一个假的百分比。
                      needCap != null && capLimit != null,
                      needWt != null && wtLimit != null,
                      l.get("balance_id", UUID.class),
                      timestamp(l, "completed_at"),
                      nz(l.get("note", String.class)));
                });

    List<PutawayTaskScanRow> scans =
        sql.fetch(
                "select sc.id,sc.line_id,sc.scanned_quantity,sc.source_location_id,"
                    + "sc.target_location_id,sc.scan_reference,sc.device_type,sc.scanned_at,"
                    + "sc.scanner_id,sc.inventory_entry_out_id,sc.inventory_entry_in_id,sc.note"
                    + " from putaway_task_scan sc"
                    + " where sc.tenant_id = ? and sc.task_id = ? order by sc.scanned_at, sc.id",
                currentTenant(),
                taskId)
            .map(
                sc ->
                    new PutawayTaskScanRow(
                        sc.get("id", UUID.class),
                        sc.get("line_id", UUID.class),
                        decimal(sc, "scanned_quantity"),
                        sc.get("source_location_id", UUID.class),
                        sc.get("target_location_id", UUID.class),
                        sc.get("scan_reference", String.class),
                        sc.get("device_type", String.class),
                        timestamp(sc, "scanned_at"),
                        sc.get("scanner_id", UUID.class),
                        sc.get("inventory_entry_out_id", UUID.class),
                        sc.get("inventory_entry_in_id", UUID.class),
                        nz(sc.get("note", String.class))));

    List<PutawayTaskEntry> entries = entriesOf(task).stream().map(this::entry).toList();
    // 净额直接摆出来。**它在任何时刻都应当是 0**：一次扫码写的就是一对反向分录，
    // 成对的两条加起来恒为 0，所以整条交易的净额无论任务做完没做完都是 0。
    // P1 抓的不是「做完了吗」，而是「有人往这条上架交易里塞了一条不成对的分录」
    // —— 那种情况下净额才会非 0。判断做完没做完的是 P2/P3 与明细的 putaway_quantity。
    BigDecimal net =
        entries.stream()
            .map(PutawayTaskEntry::onHandDelta)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (net.signum() != 0) {
      throw new IllegalStateException(
          "上架任务 "
              + task.get("task_code", String.class)
              + " 的账上净额是 "
              + net
              + "：一次扫码必须写成对的反向分录（P1），净额非 0 意味着有人往这条交易里"
              + "塞了不成对的分录，账已经不对了");
    }

    return new PutawayTaskDetail(
        row(task),
        lines,
        scans,
        entries,
        net,
        "上架只改位置不改数量：一次扫码在**同一条余额行**上写一对反向分录（源位 -Q、目标位 +Q）。"
            + "所以账上净额在任何时刻都必须是 0 —— 当前净额 "
            + net
            + "。注意净额为 0 **不代表**任务做完了：它只说明每一对分录是成对的。"
            + "做没做完看明细的实上架量（plannedQuantity vs putawayQuantity），"
            + "完成时数据库会逐行核对 P2/P3。");
  }

  /**
   * 分录（成对出现，净额恒 0）。方向与库位码一起给，前端不必自己猜。
   *
   * <p>库位码从 <b>扫码台账</b>取而不是从 stock_bucket 反查：一条分录只对应一次扫码的 一端（扫码行把两条分录的 id 都记下来了），所以这是 1:1 的。反查不行 ——
   * 一条 balance 上可能挂着许多个库位的 bucket，用 balance_id 去 join 会把一条分录 复制成 N 行，「净额」也会被放大 N
   * 次，正是本类列表聚合刻意避开的那种错。
   */
  private List<Record> entriesOf(Record task) {
    UUID transactionId = task.get("inventory_transaction_id", UUID.class);
    if (transactionId == null) {
      return List.of();
    }
    return sql.fetch(
        "select e.id,e.balance_id,e.on_hand_delta,e.reserved_delta,"
            + " case when e.on_hand_delta < 0 then out_l.code else in_l.code end as location_code"
            + " from inventory_entry e"
            + " left join putaway_task_scan sc_out"
            + "   on sc_out.tenant_id = e.tenant_id"
            + "  and sc_out.inventory_entry_out_id = e.id"
            + " left join warehouse_location out_l"
            + "   on out_l.tenant_id = sc_out.tenant_id and out_l.id = sc_out.source_location_id"
            + " left join putaway_task_scan sc_in"
            + "   on sc_in.tenant_id = e.tenant_id"
            + "  and sc_in.inventory_entry_in_id = e.id"
            + " left join warehouse_location in_l"
            + "   on in_l.tenant_id = sc_in.tenant_id and in_l.id = sc_in.target_location_id"
            + " where e.tenant_id = ? and e.transaction_id = ? order by e.created_at, e.id",
        currentTenant(),
        transactionId);
  }

  private PutawayTaskEntry entry(Record e) {
    BigDecimal delta = stock(decimal(e, "on_hand_delta"));
    return new PutawayTaskEntry(
        e.get("id", UUID.class),
        e.get("balance_id", UUID.class),
        delta,
        stock(decimal(e, "reserved_delta")),
        delta.signum() < 0 ? "SOURCE_OUT" : "TARGET_IN",
        nz(e.get("location_code", String.class)));
  }

  // ================================================================ 主数据校验

  /** 收货明细行：必须存在、属于本租户，且**确实属于本单**（不是同一租户的别的收货单的行）。 */
  private record ReceiptLineInfo(
      UUID skuId,
      String skuCode,
      UUID qualityStatusId,
      BigDecimal receivedQuantity,
      UUID receivingLocationId) {}

  /**
   * 取一条收货明细行，并定位它实收时的收货暂存位。
   *
   * <p>源位**不是调用方给的**：蓝图说「从收货暂存位上架到正式库位」，源位是事实。 取最近一次实收事件（{@code goods_receipt_event}）上的
   * location_id， 也就是说「货现在压在哪儿」由 8.2.1.1 当时留下的记录回答，而不是由建单的人编。
   */
  private ReceiptLineInfo requireReceiptLine(UUID receiptLineId, UUID receiptId) {
    Record line =
        sql.fetchOne(
            "select sku_id,quality_status_id,received_quantity,receipt_id from goods_receipt_line"
                + " where id = ?",
            receiptLineId);
    if (line == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "收货明细行 " + receiptLineId + " 不存在或无权查看");
    }
    if (!receiptId.equals(line.get("receipt_id", UUID.class))) {
      throw bad("收货明细行 " + receiptLineId + " 不属于收货单 " + receiptId + "：上架任务与它的明细行必须追溯到同一批货");
    }
    UUID skuId = line.get("sku_id", UUID.class);
    BigDecimal received = decimal(line, "received_quantity");
    if (received.signum() <= 0) {
      throw bad("收货明细行 " + receiptLineId + " 的实收量为 " + received + "：没有实收就没有可上架的货");
    }
    // 最近一次实收事件上的库位 = 这批货当前压着的收货暂存位。
    UUID locationId =
        sql.fetchOne(
                "select e.location_id from goods_receipt_event e"
                    + " join goods_receipt_event_line el on el.tenant_id = e.tenant_id"
                    + "   and el.event_id = e.id"
                    + " where el.tenant_id = ? and el.line_id = ?"
                    + " order by e.received_at desc, e.id limit 1",
                currentTenant(),
                receiptLineId)
            .get("location_id", UUID.class);
    if (locationId == null) {
      throw bad(
          "收货明细行 "
              + receiptLineId
              + " 没有任何实收事件，找不到它压在哪个收货暂存位："
              + "上架的源位由 8.2.1.1 当时的实收记录回答，不由建单的人指定");
    }
    return new ReceiptLineInfo(
        skuId, skuCode(skuId), line.get("quality_status_id", UUID.class), received, locationId);
  }

  private void requireSku(UUID skuId, String label) {
    if (sql.fetchOne("select 1 from sku_ref where id = ?", skuId) == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, label + " " + skuId + " 没有在本服务登记（sku_ref）：库存分录要落到真实存在的 SKU 上");
    }
  }

  private String skuCode(UUID skuId) {
    Record row = sql.fetchOne("select code from sku_ref where id = ?", skuId);
    return row == null ? "" : row.get("code", String.class);
  }

  private void requireLocation(UUID warehouseId, UUID locationId, String label) {
    if (sql.fetchOne(
            "select 1 from warehouse_location where id = ? and warehouse_id = ?"
                + " and status = 'ACTIVE'",
            locationId,
            warehouseId)
        == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, label + " " + locationId + " 不存在、不属于该仓或已停用");
    }
  }

  /**
   * 目标位的 bucket：没有就**直接以本次搬入的量建行**，有就加量。
   *
   * <p>新建时 balance 行是<b>既有</b>的那一条（货本来就在这个 balance 里，只是换了库位）， 所以这里<b>不</b>动 balance.on_hand ——
   * 动了就等于凭空多货/少货， 而 P1 的净额核对会当场把整个事务掀掉。
   *
   * <p>为什么不是「先建 0 行再 UPDATE 加量」：V2 的 {@code guard_bucket_movement_time} 要求每次数量变动时 {@code
   * last_movement_at} **严格前进**，而建行与本次搬入是同一个 业务时刻 —— 走「先 0 再加」会让第二次 UPDATE 的时刻与建行时刻相等，当场被拒。 一次
   * INSERT 把「这一行的诞生就是这次上架」表达清楚，也少一次写。
   */
  private record TargetBucket(UUID id, boolean created) {}

  private TargetBucket ensureTargetBucket(
      UUID warehouseId,
      UUID skuId,
      UUID balanceId,
      UUID targetLocationId,
      UUID lotId,
      UUID qualityStatusId,
      BigDecimal quantity,
      OffsetDateTime at) {
    // lot_id 可空，而 stock_bucket 用的是 UNIQUE NULLS NOT DISTINCT —— 所以定位必须写
    // `is not distinct from`，用 `= null` 会永远匹配不到无批次行（SQL 里 null = null 是未知）。
    Record found =
        sql.fetchOne(
            "select id from stock_bucket where tenant_id = ? and balance_id = ? and sku_id = ?"
                + " and location_id = ? and lot_id is not distinct from ?",
            currentTenant(),
            balanceId,
            skuId,
            targetLocationId,
            lotId);
    if (found != null) {
      return new TargetBucket(found.get("id", UUID.class), false);
    }
    UUID legalEntityId =
        sql.fetchOne("select legal_entity_id from inventory_balance where id = ?", balanceId)
            .get("legal_entity_id", UUID.class);
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
            + "lot_id,quality_status_id,balance_id,on_hand,reserved,last_movement_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,0,?::timestamptz)",
        id,
        currentTenant(),
        legalEntityId,
        skuId,
        warehouseId,
        targetLocationId,
        lotId,
        qualityStatusId,
        balanceId,
        quantity,
        at);
    return new TargetBucket(id, true);
  }

  /**
   * bucket 的变动时刻：作业员填的业务时刻，但**必须严格晚于该 bucket 的原值**。
   *
   * <p>为什么不能直接用作业员填的时刻：V2 的 {@code guard_bucket_movement_time} 要求 {@code last_movement_at >
   * 原值}，而现实里有两种合法情况会违反它 —— ① 事后补录的扫码，业务时刻早于上次扫码； ② PDA 在同一秒内连扫两次，两次时刻相等。 这两种都不是「忘了填时间」，把字段停在旧值才是 V2
   * 真正要防的事。
   *
   * <p>所以这里取两者中较晚的一个：正常情况下原样返回作业员填的时刻； 补录 / 同秒连扫时用「原值 + 1 微秒」—— 列的契约（严格单调）因此永远成立，
   * 而<b>作业员填的业务时刻本身不会丢</b>：它原样落在 {@code putaway_task_scan.scanned_at} 与 {@code
   * inventory_transaction.occurred_at} 上， 集成测试正是断言写回的那两个值，而不是这个缓存列。
   */
  private OffsetDateTime nextMovementAfter(UUID bucketId, OffsetDateTime requested) {
    Record row = sql.fetchOne("select last_movement_at from stock_bucket where id = ?", bucketId);
    OffsetDateTime current = row == null ? null : row.get("last_movement_at", OffsetDateTime.class);
    if (current == null || requested.isAfter(current)) {
      return requested;
    }
    return current.plusNanos(1000);
  }

  private Record requireTask(UUID taskId) {
    Record task = sql.fetchOne("select * from putaway_task where id = ?", taskId);
    if (task == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "上架任务 " + taskId + " 不存在或无权操作");
    }
    return task;
  }

  /** 取任务并锁住它（防并发重复扫码/重复完成）。 */
  private Record lockTask(UUID taskId) {
    Record task = sql.fetchOne("select * from putaway_task where id = ? for update", taskId);
    if (task == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "上架任务 " + taskId + " 不存在或无权操作");
    }
    return task;
  }

  private Record requireNotTerminal(UUID taskId) {
    Record task = requireTask(taskId);
    String status = task.get("status", String.class);
    if ("COMPLETED".equals(status) || "CANCELLED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "上架任务 "
              + task.get("task_code", String.class)
              + " 当前是 "
              + status
              + "，不能改派工：已完成或已取消的单子再改派工，会让「这批货是谁搬的」有两个答案");
    }
    return task;
  }

  private UUID warehouseOf(Record task) {
    return task.get("warehouse_id", UUID.class);
  }

  // ================================================================ 工具

  private PutawayTaskRow row(Record r) {
    String status = r.get("status", String.class);
    return new PutawayTaskRow(
        r.get("id", UUID.class),
        r.get("task_code", String.class),
        status,
        statusLabel(status),
        r.get("receipt_id", UUID.class),
        r.get("receipt_code", String.class),
        r.get("warehouse_id", UUID.class),
        r.get("legal_entity_id", UUID.class),
        r.get("allocation_id", UUID.class),
        r.get("assignee_id", UUID.class),
        timestamp(r, "assigned_at"),
        timestamp(r, "started_at"),
        timestamp(r, "completed_at"),
        timestamp(r, "cancelled_at"),
        nz(r.get("cancel_reason", String.class)),
        longOf(r, "detail_line_count"),
        decimal(r, "planned_quantity"),
        decimal(r, "putaway_quantity"),
        longOf(r, "scan_count"),
        r.get("inventory_transaction_id", UUID.class),
        timestamp(r, "created_at"),
        r.get("created_by", UUID.class),
        nz(r.get("notes", String.class)));
  }

  private static String statusLabel(String status) {
    return switch (status) {
      case "PENDING" -> "待执行";
      case "IN_PROGRESS" -> "执行中";
      case "COMPLETED" -> "已完成";
      default -> "已取消";
    };
  }

  private String orderBy(String key) {
    // 一律以 task_code 收尾：排序键相同时必须有确定次序，否则同一页两次请求可能给出不同顺序。
    return switch (key) {
      case "TASK_CODE" -> " order by t.task_code, t.id";
      case "PLANNED_QUANTITY" -> " order by t.planned_quantity desc, t.task_code";
      case "STATUS" -> " order by t.status, t.task_code";
      case "COMPLETED_AT" -> " order by t.completed_at desc nulls last, t.task_code";
      default -> " order by t.created_at desc, t.task_code";
    };
  }

  private long nextCode() {
    Long value = sql.fetchOne("select nextval('putaway_task_code_seq')").get(0, Long.class);
    return value == null ? 1L : value;
  }

  /**
   * 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。
   *
   * <p>表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC <b>复位成空串</b>而不是「回到未设置」，直接 cast
   * 会报 uuid 语法错 （与 warehouse V1 / V108 同一句写法）。
   */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static BigDecimal decimal(Record r, String field) {
    Object value = r.get(field);
    if (value == null) {
      return BigDecimal.ZERO;
    }
    return value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
  }

  private static BigDecimal decimalOrNull(Record r, String field) {
    Object value = r.get(field);
    if (value == null) {
      return null;
    }
    return value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
  }

  private static long longOf(Record r, String field) {
    Long value = r.get(field, Long.class);
    return value == null ? 0L : value;
  }

  private static String timestamp(Record r, String field) {
    OffsetDateTime value = r.get(field, OffsetDateTime.class);
    return value == null ? null : value.toString();
  }

  /** 数量刻度：与 V109 的 NUMERIC(19,6) 对齐。 */
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
   * <p>为什么不静默取整：向下取整等于凭空吞掉这批货（账上少了、实体还在收货暂存位）， 向上取整等于凭空多搬。两种都破坏账实相符，而且都恰好能让 P1「净额 = 0」 <b>看起来成立</b>
   * —— 源位扣 90、目标位加 90，净额当然是 0，可实物搬的是 100。 所以宁可拒绝并说清楚是哪一行。
   */
  private static BigDecimal stockExact(BigDecimal value, String label) {
    try {
      return value.setScale(STOCK_SCALE, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw bad(
          label
              + " "
              + value
              + " 有超过 4 位小数，无法在库存刻度上精确表示："
              + "向下取整等于凭空吞掉这批货、向上取整等于凭空多搬，两种都会让"
              + "「只改位置不改数量」这条不变量看起来成立而实物对不上。"
              + "请调整数量，让它能被 4 位小数精确表示");
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
