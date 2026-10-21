package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryAdjustmentModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.4.1.1 调整单管理：发起调整（批量）→ 原因与凭证 → 提交待审 → 批准 → 过账落分录。
 *
 * <p>蓝图原话：「**发起库存调整**（盘盈盘亏、损坏、报废、错发纠正、平台仓差异），填写原因与凭证，
 * **支持批量调整**。」输入：「调整请求（bucket、数量、原因码、附件）；{@code expected_version}。」
 * 输出：「调整单（草稿/待审/已批/已执行）；库存分录（反向或正向）。」
 * 以及红线：「**调整必须走单据**，不允许在明细页直接改余额——没有单据就没有责任人与原因。」
 *
 * <pre>
 *   读 7.1.1.2 stock_bucket（六维事实单位）
 *      └─ 建单（DRAFT，批量多行，reason + 附件 + expected_version）
 *           └─ submit  → PENDING_APPROVAL
 *                └─ approve（提交人 ≠ 审批人 + 必填理由）→ APPROVED
 *                     └─ post → inventory_transaction(ADJUSTMENT, ref_type='INVENTORY_ADJUSTMENT')
 *                               + inventory_entry（带符号分录）
 *                               + inventory_balance / stock_bucket
 *                          = POSTED
 * </pre>
 *
 * <p><b>本模块与 7.4.2.2 盘点差异处理（V62）是两套并行的调整单</b>：V62 的调整单要求先有盘点表，
 * 本模块不要求 —— 损坏、报废、错发纠正这些场景根本没有盘点表。两者落在各自的表与端点上，
 * 靠 {@code inventory_transaction.ref_type} 区分，不复用对方的表。
 *
 * <p><b>入账只有这一条路，每一段都被数据库检查过</b>：V260 的三个触发器分别保证
 * （1）状态机只认 {@code APPROVED → POSTED}，（2）{@code inventory_entry} 拒绝对未审批的
 * 调整单写分录（入账闸门），（3）Σ分录 必须等于 Σ调整单行。拿到连接直接 INSERT 也绕不过去。
 *
 * <p><b>乐观锁</b>：每行带 {@code expected_version}（建行时读到的 {@code stock_bucket.version}）。
 * 与当前版本不一致说明调用方看到的是过期数量 —— 那时拿旧数字改新余额就是在改一个已经不存在的数，
 * 所以一律 409，错误信息告诉调用方刷新后重来。
 *
 * <p><b>审批归属</b>：阈值审批规则（按金额/数量阈值、批量审批、17.4 审批中心）归 7.4.1.2。
 * 本模块实现的是「建单 + 提交待审 + 过账落分录」，批准这一步用**显式端点 + 必填理由 +
 * 提交人 ≠ 审批人 + DB 级状态机**的最小口径，**不实现审批人角色表**。
 *
 * <p><b>行序 = 加行顺序</b>：调整单明细按 {@code line_no}（V261）排序，而不是按主键排序 ——
 * 主键是随机 uuid(v4)，按它排序会让同一张单的明细行每次请求都可能换位置，
 * 「审批人看到的第 1 行」就不再是操作员提交的第 1 行。
 *
 * <p><b>时间绑定纪律</b>：{@code ?::timestamptz} 一律出现在语句最前面的 {@code t} CTE 里，
 * 后面全是普通绑定，cast 永远不在绑定序列末位。写入用 {@link OffsetDateTime} 绑定，
 * 不走 {@code Timestamp.valueOf} —— 后者按 JVM 本地时区解释裸本地时间，在东八区会静默偏 −28800s。
 */
@Service
public class InventoryAdjustmentService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 单张调整单最多收多少行。批量调整是本模块的卖点，但一张单上万行会让审批变成点一下。 */
  public static final int MAX_LINES = 2000;

  /** numeric(19,4) 口径下的零：避免同一列里混着 scale 不一样的 0。 */
  private static final BigDecimal ZERO = new BigDecimal("0.0000");

  /** 数量精度：与 {@code stock_bucket.on_hand} 同一口径（不是 19,6）。 */
  private static final int QTY_SCALE = 4;

  private static final DateTimeFormatter CODE_DAY =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

  /**
   * 定位键：本模块入账时写进 {@code inventory_transaction} 的 ref_type。
   *
   * <p>与 7.4.2.2 的 {@code STOCKTAKE_VARIANCE} 区分开：两套调整单在同一张流水台账里，
   * 靠这个键能分清「这笔账是盘点差异带出来的，还是通用调整单带出来的」。
   */
  static final String REF_TYPE = "INVENTORY_ADJUSTMENT";

  private final DSLContext sql;

  private final DefectiveDispositionTradePort defective;

  public InventoryAdjustmentService(DSLContext sql, DefectiveDispositionTradePort defective) {
    this.sql = sql;
    this.defective = defective;
  }

  // ============================================================ 能力

  /**
   * 调整单能力：原因码词表、状态机、合法转移，以及**必须如实讲清楚**的三件事。
   *
   * <p>前端据此渲染选项与提示，而不是把规则硬编码在界面上。
   */
  public InventoryAdjustmentCapabilities capabilities(String bearer) {
    List<InventoryAdjustmentReasonOption> reasons = new ArrayList<>();
    for (var row :
        sql.fetch(
            "select code,label,description,available,owner_module,note"
                + " from inventory_adjustment_reason order by code")) {
      reasons.add(
          new InventoryAdjustmentReasonOption(
              row.get("code", String.class),
              row.get("label", String.class),
              row.get("description", String.class),
              Boolean.TRUE.equals(row.get("available", Boolean.class)),
              nullToEmpty(row.get("owner_module", String.class)),
              nullToEmpty(row.get("note", String.class))));
    }
    List<InventoryAdjustmentStatus> statuses = new ArrayList<>();
    for (var s : InventoryAdjustmentStatus.values()) {
      statuses.add(s);
    }
    var tradeStatus = defective.status(bearer);
    List<InventoryAdjustmentCapability> dependencies = new ArrayList<>();
    dependencies.add(
        new InventoryAdjustmentCapability(
            "defectiveDisposition",
            "6.4.2.2 不良品处理",
            tradeStatus.available(),
            DefectiveDispositionTradePort.OWNER_MODULE,
            "/trade/defective-dispositions",
            tradeStatus.note()));
    dependencies.add(
        new InventoryAdjustmentCapability(
            "thresholdApproval",
            "按金额/数量阈值的审批规则",
            false,
            "7.4.1.2",
            "/inventory/adjustments",
            "阈值审批、批量审批与 17.4 审批中心的接入归 7.4.1.2："
                + "本模块不实现审批人角色表，也不自己算阈值。"
                + "本模块的批准动作是**显式端点 + 必填理由 + 提交人≠审批人 + 数据库级状态机**的最小口径，"
                + "7.4.1.2 交付后只需把批准判定换成调用审批中心，表结构与状态机不动。"));
    dependencies.add(
        new InventoryAdjustmentCapability(
            "mediaLibrary",
            "3.4.1.1 媒体库（凭证附件）",
            true,
            "3.4.1.1",
            "/catalog/media",
            "凭证附件是**逻辑引用 + 快照元数据**（媒体 id + 文件名 + 类型 + 大小 + 挂载时刻）："
                + "媒体库归 catalog 且是独立库，本模块不建跨库外键、不跨库写，也不在本地复制文件。"));
    dependencies.add(
        new InventoryAdjustmentCapability(
            "platformVariance",
            "平台仓差异（PLATFORM_VARIANCE）",
            false,
            "10.1.1.1 / 8.1.1.1",
            "/inventory/distribution",
            "平台仓（FBA）库存是**观测值**不是自有账本：V55 的 guard_platform_warehouse_no_ledger "
                + "禁止给平台仓写 balance / bucket 行，平台仓根本没有可调的 stock_bucket 行。"
                + "把平台观测值当成自货调整掉会制造出「平台仓也有自有账本」的第二个真相。"
                + "平台仓差异请走 10 外部仓对账与 7.1.3.1 多仓分布；本模块建行时显式拒绝该原因码。"));

    return new InventoryAdjustmentCapabilities(
        reasons,
        statuses,
        transitions(),
        dependencies,
        "审批动作分两层：**阈值规则**（按金额/数量阈值、批量审批、驳回工作流、17.4 审批中心接入）"
            + "归 7.4.1.2；**执行批准这一动作**在本模块，用显式端点 + 必填理由 + 提交人≠审批人的口径，"
            + "并由数据库级状态机钉死「未 APPROVED 不可能变成 POSTED」。本模块不实现审批人角色表。",
        "过账（post）是本模块**唯一**会改库存余额的端点：它写一条 inventory_transaction(ADJUSTMENT) "
            + "与逐行带符号分录，同时改 balance 与 bucket。数据库的入账闸门会拒绝对未审批的调整单写分录，"
            + "入账数量守恒会拒绝少记/多记/只入一部分。分录一落库，7.1.2.1 库存流水台账自然可见 —— "
            + "本模块**不另建调整流水表**。",
        "数量精度与库存一致：numeric(19,4)，Java 侧 BigDecimal，全程禁浮点。"
            + "带符号：正数增加在手，负数减少在手。「盘盈」必须是正数、「盘亏」必须是负数 —— "
            + "方向写反的调整单在审批时是看不出来的。",
        "凭证附件是 catalog 媒体库（3.4.1.1）的**逻辑引用 + 快照元数据**：本模块不提供上传端点，"
            + "也不在本地复制文件；上传动作在 catalog 侧完成后，这里登记媒体 id 与文件名/类型/大小，"
            + "并记下挂载时刻与挂载人。");
  }

  /** 状态机的合法转移（前端据此只给合法的按钮）。 */
  private List<InventoryAdjustmentTransition> transitions() {
    List<InventoryAdjustmentTransition> out = new ArrayList<>();
    out.add(
        new InventoryAdjustmentTransition(
            "submit",
            "DRAFT",
            "PENDING_APPROVAL",
            true,
            "warehouse.inventory.update",
            "提交待审：此刻余额仍未改动。"));
    out.add(
        new InventoryAdjustmentTransition(
            "approve",
            "PENDING_APPROVAL",
            "APPROVED",
            true,
            "warehouse.inventory.update",
            "批准：**仍然不改余额** —— 它只是允许入账，入账是独立的 post 一步。"
                + "必须写理由，且提交人不能批自己提的单。"));
    out.add(
        new InventoryAdjustmentTransition(
            "reject",
            "PENDING_APPROVAL",
            "REJECTED",
            true,
            "warehouse.inventory.update",
            "驳回：意见必填，可改完重提。"));
    out.add(
        new InventoryAdjustmentTransition(
            "cancel",
            "DRAFT/PENDING_APPROVAL/REJECTED/APPROVED",
            "CANCELLED",
            true,
            "warehouse.inventory.update",
            "作废：留下痕迹而不是删单。已过账的单据不能作废（账已经动了）。"));
    out.add(
        new InventoryAdjustmentTransition(
            "post",
            "APPROVED",
            "POSTED",
            true,
            "warehouse.inventory.update",
            "过账：**唯一会改库存余额的动作**。需要已批；数据库入账闸门还会再挡一次。"));
    return out;
  }

  // ============================================================ 查询

  /** 调整单列表（真分页；行数与三个数量由明细现算，不另存列）。 */
  public InventoryAdjustmentOrderPage orders(
      String status, String reasonCode, String keyword, int pageNo, int size) {
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 20 : size));
    int offset = Math.max(0, pageNo) * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("a.tenant_id = ?");
    binds.add(tenant());
    if (notBlank(status)) {
      where.add("a.status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(reasonCode)) {
      where.add("a.reason_code = ?");
      binds.add(reasonCode.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(keyword)) {
      where.add(
          "(a.code ilike ? or a.note ilike ? or a.source_reference ilike ?"
              + " or exists (select 1 from inventory_adjustment_order_line l"
              + "   where l.tenant_id = a.tenant_id and l.order_id = a.id"
              + "     and (l.sku_code ilike ? or l.location_code ilike ? or l.lot_code ilike ?)))");
      String k = "%" + keyword.trim() + "%";
      // 6 个占位符：单号/备注/来源单号 + SKU/库位/批次
      for (int i = 0; i < 6; i++) {
        binds.add(k);
      }
    }
    String sqlWhere = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne(
                "select count(distinct a.id) from "
                    + ORDER_FROM
                    + sqlWhere,
                binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select "
                + ORDER_COLUMNS
                + ", "
                + ORDER_AGG
                + " from "
                + ORDER_FROM
                + sqlWhere
                + " group by a.id, r.label"
                + " order by a.created_at desc, a.id"
                + " limit ? offset ?",
            append(binds, limit, (long) offset).toArray());
    return new InventoryAdjustmentOrderPage(
        rows.stream().map(InventoryAdjustmentService::order).toList(), total, Math.max(0, pageNo), limit);
  }

  /** 调整单详情 = 头部 + 全部行 + 凭证清单 + 完整审计时间线。 */
  public InventoryAdjustmentOrderDetail detail(UUID orderId) {
    var header =
        sql.fetchOne(
            "select "
                + ORDER_COLUMNS
                + ", "
                + ORDER_AGG
                + " from "
                + ORDER_FROM
                + " where a.tenant_id = ? and a.id = ? group by a.id, r.label",
            tenant(),
            orderId);
    if (header == null) {
      throw notFound("调整单", orderId);
    }
    var lines =
        sql.fetch(
            "select l.id,l.order_id,l.bucket_id,l.balance_id,l.legal_entity_id,l.sku_id,l.sku_code,"
                + "l.sku_name,l.warehouse_id,l.warehouse_code,l.location_id,l.location_code,l.lot_id,"
                + "l.lot_code,l.quality_status_id,l.quality_status_code,l.qty_delta,l.reason_code,"
                + "l.note,l.expected_version,l.posted_at,l.posted_by_user_id,l.entry_id,l.transaction_id,"
                + "r.label as reason_label,"
                + "k.version as current_bucket_version,k.on_hand as bucket_on_hand"
                + " from inventory_adjustment_order_line l"
                + " left join stock_bucket k on k.tenant_id = l.tenant_id and k.id = l.bucket_id"
                + " left join inventory_adjustment_reason r on r.code = l.reason_code"
                // 行序 = **加行顺序**（V261 的 line_no），不是随机 UUID 的大小顺序：
                // 主键是随机 uuid(v4)，按它排序会让同一张单的明细行每次请求都可能换位置。
                + " where l.tenant_id = ? and l.order_id = ? order by l.line_no",
            tenant(),
            orderId);
    var attachments =
        sql.fetch(
            "select id,order_id,media_id,file_name,media_type,size_bytes,mounted_at,mounted_by_user_id"
                + " from inventory_adjustment_attachment where tenant_id = ? and order_id = ?"
                + " order by mounted_at, id",
            tenant(),
            orderId);
    var audit =
        sql.fetch(
            "select id,order_id,action,actor_user_id,occurred_at,detail"
                + " from inventory_adjustment_audit where tenant_id = ? and order_id = ?"
                + " order by occurred_at, id",
            tenant(),
            orderId);
    return new InventoryAdjustmentOrderDetail(
        order(header),
        lines.stream()
            .map(
                r ->
                    new InventoryAdjustmentOrderLine(
                        r.get("id", UUID.class),
                        r.get("order_id", UUID.class),
                        r.get("bucket_id", UUID.class),
                        r.get("balance_id", UUID.class),
                        r.get("legal_entity_id", UUID.class),
                        r.get("sku_id", UUID.class),
                        r.get("sku_code", String.class),
                        r.get("sku_name", String.class),
                        r.get("warehouse_id", UUID.class),
                        r.get("warehouse_code", String.class),
                        r.get("location_id", UUID.class),
                        r.get("location_code", String.class),
                        r.get("lot_id", UUID.class),
                        r.get("lot_code", String.class),
                        r.get("quality_status_id", UUID.class),
                        r.get("quality_status_code", String.class),
                        r.get("qty_delta", BigDecimal.class),
                        r.get("reason_code", String.class),
                        nullToEmpty(r.get("reason_label", String.class)),
                        nullToEmpty(r.get("note", String.class)),
                        r.get("expected_version", Long.class) == null
                            ? 0L
                            : r.get("expected_version", Long.class),
                        r.get("current_bucket_version", Long.class),
                        r.get("bucket_on_hand", BigDecimal.class),
                        r.get("posted_at", OffsetDateTime.class),
                        r.get("posted_by_user_id", UUID.class),
                        r.get("entry_id", UUID.class),
                        r.get("transaction_id", UUID.class)))
            .toList(),
        attachments.stream()
            .map(
                r ->
                    new InventoryAdjustmentAttachment(
                        r.get("id", UUID.class),
                        r.get("order_id", UUID.class),
                        r.get("media_id", UUID.class),
                        r.get("file_name", String.class),
                        nullToEmpty(r.get("media_type", String.class)),
                        r.get("size_bytes", Long.class),
                        r.get("mounted_at", OffsetDateTime.class),
                        r.get("mounted_by_user_id", UUID.class)))
            .toList(),
        audit.stream().map(InventoryAdjustmentService::auditEntry).toList());
  }

  // ============================================================ 建单

  /**
   * 发起调整单（草稿）：批量多行 + 原因 + 凭证。
   *
   * <p>它是**草稿**：此刻一行余额都还没动。批量调整是本模块的卖点（蓝图：「支持批量调整」），
   * 所以一次给多行落一张单；零行会被拒绝 —— 悄悄建一张零行单据会让台账上看起来像「处理过了」。
   *
   * <p>每行都按 {@code expected_version} 做乐观锁校验：建单时 bucket 的版本与调用方读到的不一致，
   * 说明调用方看到的是过期数量，返回 409。
   */
  @Transactional
  public InventoryAdjustmentOrderDetail create(
      InventoryAdjustmentCreateInput input, UUID actorId, String bearer) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    InventoryAdjustmentReason reason = reason(input.reasonCode());
    var lines = input.lines() == null ? List.<InventoryAdjustmentLineInput>of() : input.lines();
    if (lines.isEmpty()) {
      throw bad("调整单至少要有一行：零行的调整单在台账上看起来像「已经处理过了」。");
    }
    if (lines.size() > MAX_LINES) {
      throw bad("一张调整单最多 " + MAX_LINES + " 行（本次 " + lines.size() + " 行）："
          + "批量调整是给人审的，不是让人点一下就过账的。");
    }
    var attachments =
        input.attachments() == null ? List.<InventoryAdjustmentAttachmentInput>of() : input.attachments();
    if (attachments.size() > 200) {
      throw bad("一张调整单最多挂 200 份凭证（本次 " + attachments.size() + " 份）。");
    }

    // 6.4.2.2 来源：跨服务，只能经授权 HTTP 读；引用之前先核对这条指令确实属于那张处置单。
    String sourceReference = "";
    if (input.sourceOrderId() != null) {
      if (input.sourceInstructionId() == null) {
        throw bad("引用 6.4.2.2 不良品处置单时必须同时指定处置指令 id："
            + "处置单头上只有汇总数量，指令才是「要调哪批货」的直接来源。");
      }
      DefectiveDispositionTradePort.DefectiveDetail detail;
      try {
        detail = defective.detail(input.sourceOrderId(), bearer);
      } catch (RuntimeException e) {
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "读取 6.4.2.2 不良品处置单失败：" + e.getMessage()
                + "。调整单仍可独立发起（不引用来源），但本模块不会凭空编一条处置指令出来。");
      }
      boolean found =
          detail.instructions().stream()
              .anyMatch(i -> input.sourceInstructionId().equals(i.id()));
      if (!found) {
        throw bad("处置指令 " + input.sourceInstructionId() + " 不属于处置单 " + input.sourceOrderId()
            + "：把一条指令挂到另一张单上，这条因果链就断了。");
      }
      sourceReference = detail.order() == null ? "" : nullToEmpty(detail.order().dispositionNo());
    }

    OffsetDateTime now = OffsetDateTime.now();
    UUID orderId = UUID.randomUUID();
    sql.execute(
        "insert into inventory_adjustment_order(id,tenant_id,code,status,reason_code,note,"
            + "source_type,source_order_id,source_instruction_id,source_reference,"
            + "created_by_user_id,created_at) "
            + "values(?,?,?,'DRAFT',?,?,?,?,?,?,?,?::timestamptz)",
        orderId,
        tenant(),
        nextCode(now),
        reason.name(),
        optionalText(input.note(), 500),
        input.sourceOrderId() == null ? "" : "DEFECTIVE_DISPOSITION",
        input.sourceOrderId(),
        input.sourceInstructionId(),
        sourceReference,
        actorId,
        now);

    BigDecimal net = ZERO;
    int lineNo = 0;
    for (var line : lines) {
      var resolved = resolveLine(line, reason);
      // 行号 = 加行顺序（同一张单内从 1 递增，V261）。
      // 建单时这张单还没提交，别的会话看不到它 ⇒ 行号不可能撞（并发说明见 V261 第五节）。
      lineNo++;
      insertLine(orderId, resolved, reason, now, lineNo);
      net = net.add(resolved.qtyDelta());
    }
    for (var attachment : attachments) {
      insertAttachment(orderId, attachment, actorId, now);
    }
    audit(
        orderId,
        "CREATED",
        actorId,
        now,
        "建单："
            + lines.size()
            + " 行，净调整 "
            + net.toPlainString()
            + (sourceReference.isEmpty() ? "" : "，来源 6.4.2.2 处置单 " + sourceReference)
            + "。此刻余额尚未改动。");
    return detail(orderId);
  }

  /** 往草稿单上加一行（草稿还可以改主意；已提交的行是证据，不许动）。 */
  @Transactional
  public InventoryAdjustmentOrderDetail addLine(
      UUID orderId, InventoryAdjustmentLineInput input, UUID actorId) {
    // 🔴 先锁住单头，再取行号、再判状态：
    //   ① 并发加行必须排队 —— 否则两个事务会算出同一个 max(line_no)+1 ⇒ 撞 UNIQUE ⇒ 500；
    //   ② 锁住之后单据状态不会再被别的事务改掉（提交/作废会等我们），
    //      「这一行加在草稿上」这件事才是真的。
    lockOrderHeader(orderId);
    var order = requireDraft(orderId, "加行");
    if (input == null) {
      throw bad("请求体不能为空");
    }
    InventoryAdjustmentReason reason =
        notBlank(input.reasonCode())
            ? reason(input.reasonCode())
            : reason(order.reasonCode());
    var resolved = resolveLine(input, reason);
    insertLine(orderId, resolved, reason, OffsetDateTime.now(), nextLineNo(orderId));
    audit(
        orderId,
        "LINE_ADDED",
        actorId,
        OffsetDateTime.now(),
        "加行：" + resolved.skuCode() + " @ " + resolved.locationCode() + "，调整 "
            + resolved.qtyDelta().toPlainString() + "。");
    return detail(orderId);
  }

  /** 从草稿单上摘掉一行（只有草稿能摘；已提交的行是证据）。 */
  @Transactional
  public InventoryAdjustmentOrderDetail removeLine(UUID orderId, UUID lineId, UUID actorId) {
    requireDraft(orderId, "删行");
    var line =
        sql.fetchOne(
            "select id,sku_code,location_code,qty_delta from inventory_adjustment_order_line"
                + " where tenant_id = ? and order_id = ? and id = ?",
            tenant(),
            orderId,
            lineId);
    if (line == null) {
      throw notFound("调整单行", lineId);
    }
    sql.execute(
        "delete from inventory_adjustment_order_line where tenant_id = ? and order_id = ? and id = ?",
        tenant(),
        orderId,
        lineId);
    audit(
        orderId,
        "LINE_REMOVED",
        actorId,
        OffsetDateTime.now(),
        "删行：" + line.get("sku_code", String.class) + " @ " + line.get("location_code", String.class)
            + "，调整 " + line.get("qty_delta", BigDecimal.class).toPlainString() + "。");
    return detail(orderId);
  }

  /** 给草稿单挂一份凭证（catalog 媒体库的逻辑引用 + 快照元数据）。 */
  @Transactional
  public InventoryAdjustmentOrderDetail attach(
      UUID orderId, InventoryAdjustmentAttachmentInput input, UUID actorId) {
    requireDraft(orderId, "挂凭证");
    if (input == null) {
      throw bad("请求体不能为空");
    }
    OffsetDateTime now = OffsetDateTime.now();
    insertAttachment(orderId, input, actorId, now);
    audit(orderId, "ATTACHED", actorId, now, "挂凭证：" + input.fileName() + "。");
    return detail(orderId);
  }

  /** 从草稿单上摘掉一份凭证。 */
  @Transactional
  public InventoryAdjustmentOrderDetail detach(UUID orderId, UUID attachmentId, UUID actorId) {
    requireDraft(orderId, "摘凭证");
    var attachment =
        sql.fetchOne(
            "select id,file_name from inventory_adjustment_attachment"
                + " where tenant_id = ? and order_id = ? and id = ?",
            tenant(),
            orderId,
            attachmentId);
    if (attachment == null) {
      throw notFound("凭证附件", attachmentId);
    }
    sql.execute(
        "delete from inventory_adjustment_attachment where tenant_id = ? and order_id = ? and id = ?",
        tenant(),
        orderId,
        attachmentId);
    audit(
        orderId,
        "ATTACHMENT_REMOVED",
        actorId,
        OffsetDateTime.now(),
        "摘凭证：" + attachment.get("file_name", String.class) + "。");
    return detail(orderId);
  }

  // ============================================================ 状态流转

  /** 提交待审：DRAFT → PENDING_APPROVAL。此刻余额仍未改动。 */
  @Transactional
  public InventoryAdjustmentOrder submit(UUID orderId, InventoryAdjustmentNoteInput input, UUID actorId) {
    var order = requireOrder(orderId);
    if (order.status() != InventoryAdjustmentStatus.DRAFT) {
      throw conflict(
          "调整单 " + order.code() + " 当前是「" + order.status().label() + "」，只有草稿能提交待审。");
    }
    requireLines(orderId);
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update inventory_adjustment_order set status = 'PENDING_APPROVAL', submitted_by_user_id = ?,"
            + " submitted_at = ?::timestamptz where tenant_id = ? and id = ? and status = 'DRAFT'",
        actorId,
        now,
        tenant(),
        orderId);
    audit(
        orderId,
        "SUBMITTED",
        actorId,
        now,
        "提交待审："
            + order.lineCount()
            + " 行，净调整 "
            + order.netQty().toPlainString()
            + "。**余额此刻仍未改动** —— 调整必须走单据，审批通过并过账后才动账。");
    return requireOrder(orderId);
  }

  /**
   * 批准：PENDING_APPROVAL → APPROVED。
   *
   * <p><b>提交人不能批自己提的单</b>：自提自批的审批与没有审批是同一件事，而库存调整正是舞弊高发区。
   * 数据库的 CHECK 也钉死了这一条（不是只靠应用层自律）。
   *
   * <p><b>必须写理由</b>：只留一个 APPROVED 状态，事后没人说得清批的是哪一条判断。
   *
   * <p>批准**仍然不改余额** —— 它只是允许入账，入账是独立的 {@code /post} 一步。
   */
  @Transactional
  public InventoryAdjustmentOrder approve(
      UUID orderId, InventoryAdjustmentNoteInput input, UUID actorId) {
    var order = requireOrder(orderId);
    if (order.status() != InventoryAdjustmentStatus.PENDING_APPROVAL) {
      throw conflict(
          "调整单 " + order.code() + " 当前是「" + order.status().label() + "」，只有待审批的单据才能批准。");
    }
    String note = requireText(input == null ? null : input.note(), "批准必须写理由："
        + "只留一个「已批」状态，事后没人说得清批的是哪一条判断。");
    if (order.submittedByUserId() != null && order.submittedByUserId().equals(actorId)) {
      throw conflict(
          "提交人不能批自己提的单（自提自批的审批与没有审批是同一件事，"
              + "而库存调整正是舞弊高发区）。请换一个审批人。");
    }
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update inventory_adjustment_order set status = 'APPROVED', decided_by_user_id = ?,"
            + " decided_at = ?::timestamptz, decision_note = ?"
            + " where tenant_id = ? and id = ? and status = 'PENDING_APPROVAL'",
        actorId,
        now,
        note,
        tenant(),
        orderId);
    audit(
        orderId,
        "APPROVED",
        actorId,
        now,
        "批准调整单 " + order.code() + " · " + note + "。注意：批准只是允许入账，**余额此刻仍未改动**。");
    return requireOrder(orderId);
  }

  /** 驳回：PENDING_APPROVAL → REJECTED，意见必填；可改完重提。 */
  @Transactional
  public InventoryAdjustmentOrder reject(
      UUID orderId, InventoryAdjustmentNoteInput input, UUID actorId) {
    var order = requireOrder(orderId);
    if (order.status() != InventoryAdjustmentStatus.PENDING_APPROVAL) {
      throw conflict(
          "调整单 " + order.code() + " 当前是「" + order.status().label() + "」，只有待审批的单据才能驳回。");
    }
    String note =
        requireText(input == null ? null : input.note(), "驳回必须写意见：只给一个 REJECTED 状态，提交的人无法知道该改什么。");
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update inventory_adjustment_order set status = 'REJECTED', decided_by_user_id = ?,"
            + " decided_at = ?::timestamptz, decision_note = ?"
            + " where tenant_id = ? and id = ? and status = 'PENDING_APPROVAL'",
        actorId,
        now,
        note,
        tenant(),
        orderId);
    audit(
        orderId,
        "REJECTED",
        actorId,
        now,
        "驳回调整单 " + order.code() + " · " + note + "。可改完重提。");
    return requireOrder(orderId);
  }

  /** 作废：任何未过账状态 → CANCELLED。已过账的单据不能作废（账已经动了）。 */
  @Transactional
  public InventoryAdjustmentOrder cancel(
      UUID orderId, InventoryAdjustmentNoteInput input, UUID actorId) {
    var order = requireOrder(orderId);
    if (order.status() == InventoryAdjustmentStatus.POSTED) {
      throw conflict("调整单 " + order.code() + " 已经过账入账，不能作废：账已经动了。冲正请发起新的调整单。");
    }
    String note =
        optionalText(input == null ? null : input.note(), 500);
    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update inventory_adjustment_order set status = 'CANCELLED', cancelled_by_user_id = ?,"
            + " cancelled_at = ?::timestamptz, decision_note = coalesce(nullif(decision_note,''), ?)"
            + " where tenant_id = ? and id = ? and status <> 'POSTED'",
        actorId,
        now,
        note.isEmpty() ? "未写原因" : note,
        tenant(),
        orderId);
    audit(
        orderId,
        "CANCELLED",
        actorId,
        now,
        "作废调整单 " + order.code() + (note.isEmpty() ? "（未写原因）" : " · " + note) + "。");
    return requireOrder(orderId);
  }

  // ============================================================ 入账（红线所在）

  /**
   * 过账：APPROVED → POSTED。**这是本模块唯一会改库存余额的路径。**
   *
   * <p>一个事务里做五件事，写入顺序与事务边界**照抄 warehouse 既有的成熟做法**
   * （7.4.2.2 的 {@code StocktakeVarianceService.post}）：
   *
   * <ol>
   *   <li>写业务单 {@code inventory_transaction(kind='ADJUSTMENT', ref_type='INVENTORY_ADJUSTMENT')}
   *   <li>按 balance 聚合后更新 {@code inventory_balance}（同一余额行只 UPDATE 一次）
   *   <li>逐行更新 {@code stock_bucket}（{@code on_hand += delta, last_movement_at = 业务时间}）
   *   <li>逐行写 {@code inventory_entry}（带符号分录，一行一条）
   *   <li>标记行已过账 + 单据转 POSTED + 写 append-only 审计
   * </ol>
   *
   * <p><b>乐观锁在过账时再校一次</b>：建单到过账之间这批货可能又动过（正常收发），
   * 那时按建单时的数量去改就是改一个已经不存在的数，所以版本不一致一律 409，
   * 错误信息告诉调用方刷新后重新发起调整。
   */
  @Transactional
  public InventoryAdjustmentPostResult post(
      UUID orderId, InventoryAdjustmentNoteInput input, UUID actorId) {
    var order = requireOrder(orderId);
    if (order.status() != InventoryAdjustmentStatus.APPROVED) {
      throw conflict(
          "调整单 "
              + order.code()
              + " 当前是「"
              + order.status().label()
              + "」，只有**已批待入账**的单据才能过账。"
              + "未审批的调整单不动余额 —— 调整必须走单据，不允许在明细页直接改余额。");
    }
    var lines =
        sql.fetch(
            "select l.id,l.bucket_id,l.balance_id,l.qty_delta,l.expected_version,l.sku_code,l.location_code,"
                + "k.version as current_version,k.on_hand,k.reserved"
                + " from inventory_adjustment_order_line l"
                + " left join stock_bucket k on k.tenant_id = l.tenant_id and k.id = l.bucket_id"
                // 行序 = 加行顺序（V261 的 line_no）：过账写分录的先后必须与单据上的行序一致，
                // 不能让随机 UUID 决定「先记哪一行」。
                + " where l.tenant_id = ? and l.order_id = ? order by l.line_no",
            tenant(),
            orderId);
    if (lines.isEmpty()) {
      throw bad("调整单 " + order.code() + " 没有任何行，过账会导致「入账数量 0 = 明细合计 0」的空过账。");
    }

    // 乐观锁：先全部校一遍，冲突就整单不落账（事务回滚），错误信息指出是哪一行过期了。
    for (var r : lines) {
      Long current = r.get("current_version", Long.class);
      long expected = r.get("expected_version", Long.class) == null ? 0L : r.get("expected_version", Long.class);
      if (current == null) {
        throw conflict(
            "调整单 " + order.code() + " 引用的库位明细 " + r.get("bucket_id", UUID.class)
                + " 不存在了：这行已经调不到账。");
      }
      if (current != expected) {
        throw conflict(
            "调整单 " + order.code() + " 的一行已经过期：" + r.get("sku_code", String.class) + " @ "
                + r.get("location_code", String.class) + " 的库位明细在建单之后又动过"
                + "（版本 " + expected + " → " + current + "）。"
                + "请刷新后按当前数量重新发起调整单 —— 拿旧数字改新余额就是在改一个不存在的数。");
      }
    }

    OffsetDateTime now = OffsetDateTime.now();
    UUID transactionId = UUID.randomUUID();
    String note =
        input != null && input.note() != null && !input.note().isBlank()
            ? input.note().trim()
            : (order.note() == null || order.note().isBlank() ? "库存调整单 " + order.code() : order.note());
    // t CTE 让 ?::timestamptz 成为第 1 个绑定，cast 不落在绑定序列末位。
    sql.execute(
        "with t as (select ?::timestamptz as at) "
            + "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,"
            + "notes,created_at) select ?,?,'ADJUSTMENT',?,?,t.at,?,?,t.at from t",
        now,
        transactionId,
        tenant(),
        REF_TYPE,
        orderId,
        actorId,
        note);

    // 同一余额行可能被本单的多个库位行命中：按余额聚合一次 UPDATE，
    // 逐行 UPDATE 会让同一行 on_hand 被反复读写，中途的守恒核对也失去意义。
    var byBalance = new LinkedHashMap<UUID, BigDecimal>();
    for (var r : lines) {
      byBalance.merge(r.get("balance_id", UUID.class), r.get("qty_delta", BigDecimal.class), BigDecimal::add);
    }
    for (var e : byBalance.entrySet()) {
      guardNotNegative("inventory_balance", e.getKey(), e.getValue());
      sql.execute(
          "update inventory_balance set on_hand = on_hand + ?::numeric, version = version + 1"
              + " where tenant_id = ? and id = ?",
          e.getValue(),
          tenant(),
          e.getKey());
    }

    int entries = 0;
    for (var r : lines) {
      UUID balanceId = r.get("balance_id", UUID.class);
      BigDecimal delta = r.get("qty_delta", BigDecimal.class);
      UUID bucketId = r.get("bucket_id", UUID.class);
      guardNotNegative("stock_bucket", bucketId, delta);
      sql.execute(
          "update stock_bucket set on_hand = on_hand + ?::numeric, last_movement_at = ?::timestamptz"
              + " where tenant_id = ? and id = ?",
          delta,
          now,
          tenant(),
          bucketId);
      // 一行一条分录：Σ分录 == Σ调整单行 是 V260 的约束，所以分行写才对得上账。
      UUID entryId = UUID.randomUUID();
      sql.execute(
          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,reserved_delta)"
              + " values(?,?,?,?,?,0::numeric)",
          entryId,
          tenant(),
          transactionId,
          balanceId,
          delta);
      sql.execute(
          "update inventory_adjustment_order_line set posted_at = ?::timestamptz, posted_by_user_id = ?,"
              + " entry_id = ?, transaction_id = ? where tenant_id = ? and id = ?",
          now,
          actorId,
          entryId,
          transactionId,
          tenant(),
          r.get("id", UUID.class));
      entries++;
    }

    sql.execute(
        "update inventory_adjustment_order set status = 'POSTED', posted_by_user_id = ?,"
            + " posted_at = ?::timestamptz where tenant_id = ? and id = ? and status = 'APPROVED'",
        actorId,
        now,
        tenant(),
        orderId);
    audit(
        orderId,
        "POSTED",
        actorId,
        now,
        "过账：写 " + entries + " 条 ADJUSTMENT 分录（ref_type=" + REF_TYPE + "），净调整 "
            + order.netQty().toPlainString() + "。调整至此才真正改变库存余额，"
            + "并立即出现在 7.1.2.1 库存流水台账里。");

    return new InventoryAdjustmentPostResult(
        requireOrder(orderId),
        transactionId,
        entries,
        List.copyOf(byBalance.keySet()),
        order.netQty(),
        "已过账：写 " + entries + " 条库存分录，净调整 " + order.netQty().toPlainString()
            + "。业务单 " + transactionId + " 的 ref_type=" + REF_TYPE + "、ref_id=" + orderId + "。");
  }

  /**
   * 余额 / 库位不许被调成负数。
   *
   * <p>DB 有 CHECK，但那句报错完全指向不到「是这张调整单把它扣成负的」。这里先判一次，把该说的话说清楚：
   * 盘亏的量超过了现在的在手，说明这批货已经卖出/发出去了 —— 这不是调整单能解决的问题。
   *
   * <p>同时也判「可用」：{@code available = on_hand − reserved} 是生成列，把在手扣到低于预占
   * 同样会让可用量变负（超卖）。DB 的 CHECK(available>=0) 会拦，但那句话说不出是哪张单干的。
   */
  private void guardNotNegative(String table, UUID id, BigDecimal delta) {
    if (delta.signum() >= 0) {
      return;
    }
    var row =
        sql.fetchOne(
            "select on_hand, reserved from " + table + " where tenant_id = ? and id = ?", tenant(), id);
    if (row == null) {
      throw bad("入账目标 " + table + " " + id + " 不存在（或不属于当前租户）。");
    }
    BigDecimal onHand = row.get("on_hand", BigDecimal.class);
    BigDecimal reserved = row.get("reserved", BigDecimal.class) == null ? BigDecimal.ZERO : row.get("reserved", BigDecimal.class);
    BigDecimal after = onHand.add(delta);
    if (after.signum() < 0) {
      throw conflict(
          "这笔减调会把 "
              + table
              + " 的在手从 "
              + onHand.toPlainString()
              + " 扣成 "
              + after.toPlainString()
              + "（负数），已阻止过账。"
              + "这批货已经卖出或发出去了：请先核对该库位最近的流水，"
              + "或把原因归成「盘亏」之外的原因并说明依据。");
    }
    if (after.subtract(reserved).signum() < 0) {
      throw conflict(
          "这笔减调会把 "
              + table
              + " 的可用量扣成负数：在手 "
              + onHand.toPlainString()
              + "、预占 "
              + reserved.toPlainString()
              + "，减调之后可用为 "
              + after.subtract(reserved).toPlainString()
              + "。已阻止过账 —— 先释放预占，或把数量改小。");
    }
  }

  // ============================================================ 6.4.2.2 来源

  /**
   * 6.4.2.2 不良品处置单候选（跨服务只读）。
   *
   * <p>上游不可达时**如实报错**而不是返回空清单：空清单会被读成「这批货还没登记不良品」，
   * 而真实情况是 trade 服务挂了 —— 那两件事的处理方式完全相反。
   */
  public InventoryAdjustmentDefectiveSourcePage defectiveSources(
      String bearer, String keyword, int page, int size) {
    int limit = Math.min(100, Math.max(1, size <= 0 ? 20 : size));
    int offset = Math.max(0, page) * limit;
    List<InventoryAdjustmentDefectiveSource> orders;
    try {
      orders = defective.dispositions(bearer, keyword, offset / limit, limit);
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "读取 6.4.2.2 不良品处置单失败：" + e.getMessage()
              + "。6.4.2.2 的事实归 trade 服务所有（跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），"
              + "本服务不会凭空生成一张处置单或一条处置指令。");
    }
    return new InventoryAdjustmentDefectiveSourcePage(
        orders, List.of(), orders.size(), true, "6.4.2.2 不良品处置单（trade）可读。");
  }

  /** 一张 6.4.2.2 处置单的库存处置指令（调整单可引用其中一条作为上游来源）。 */
  public List<InventoryAdjustmentDefectiveInstruction> defectiveInstructions(
      UUID orderId, String bearer) {
    try {
      return defective.detail(orderId, bearer).instructions();
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "读取 6.4.2.2 不良品处置指令失败：" + e.getMessage()
              + "。6.4.2.2 的事实归 trade 服务所有，本服务不会凭空生成一条处置指令。");
    }
  }

  // ============================================================ SQL 与派生

  private static final String ORDER_COLUMNS =
      "a.id,a.code,a.status,a.reason_code,r.label as label,a.note,a.source_type,a.source_order_id,"
          + "a.source_instruction_id,a.source_reference,a.submitted_by_user_id,a.submitted_at,"
          + "a.decided_by_user_id,a.decided_at,a.decision_note,a.posted_by_user_id,a.posted_at,"
          + "a.cancelled_by_user_id,a.cancelled_at,a.created_by_user_id,a.created_at";

  /** 调整单查询的 FROM：头 + 原因词表 + 明细行（行数与三个数量都由它聚合出来）。 */
  private static final String ORDER_FROM =
      "inventory_adjustment_order a "
          + "left join inventory_adjustment_reason r on r.code = a.reason_code "
          + "left join inventory_adjustment_order_line l on l.tenant_id = a.tenant_id and l.order_id = a.id ";

  /** 调整单的数量全部现算，表里没有对应列（漏同步一次就会出现「显示已入账却还差几行」）。 */
  private static final String ORDER_AGG =
      "count(l.id) as line_count,"
          + " count(l.id) filter (where l.qty_delta > 0) as gain_line_count,"
          + " count(l.id) filter (where l.qty_delta < 0) as loss_line_count,"
          + " coalesce(sum(l.qty_delta) filter (where l.qty_delta > 0),0)::numeric(19,4) as gain_qty,"
          + " coalesce(-sum(l.qty_delta) filter (where l.qty_delta < 0),0)::numeric(19,4) as loss_qty,"
          + " coalesce(sum(l.qty_delta),0)::numeric(19,4) as net_qty";

  private static InventoryAdjustmentOrder order(org.jooq.Record r) {
    return new InventoryAdjustmentOrder(
        r.get("id", UUID.class),
        r.get("code", String.class),
        InventoryAdjustmentStatus.valueOf(r.get("status", String.class)),
        r.get("reason_code", String.class),
        nullToEmpty(r.get("label", String.class)),
        nullToEmpty(r.get("note", String.class)),
        nullToEmpty(r.get("source_type", String.class)),
        r.get("source_order_id", UUID.class),
        r.get("source_instruction_id", UUID.class),
        nullToEmpty(r.get("source_reference", String.class)),
        r.get("line_count", Long.class) == null ? 0L : r.get("line_count", Long.class),
        r.get("gain_line_count", Long.class) == null ? 0L : r.get("gain_line_count", Long.class),
        r.get("loss_line_count", Long.class) == null ? 0L : r.get("loss_line_count", Long.class),
        scale(r.get("gain_qty", BigDecimal.class)),
        scale(r.get("loss_qty", BigDecimal.class)),
        scale(r.get("net_qty", BigDecimal.class)),
        r.get("submitted_by_user_id", UUID.class),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("decided_by_user_id", UUID.class),
        r.get("decided_at", OffsetDateTime.class),
        r.get("decision_note", String.class),
        r.get("posted_by_user_id", UUID.class),
        r.get("posted_at", OffsetDateTime.class),
        r.get("cancelled_by_user_id", UUID.class),
        r.get("cancelled_at", OffsetDateTime.class),
        r.get("created_by_user_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static InventoryAdjustmentAuditEntry auditEntry(org.jooq.Record r) {
    String action = r.get("action", String.class);
    return new InventoryAdjustmentAuditEntry(
        r.get("id", UUID.class),
        r.get("order_id", UUID.class),
        action,
        actionLabel(action),
        r.get("actor_user_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("detail", String.class));
  }

  private static String actionLabel(String action) {
    return switch (action) {
      case "CREATED" -> "建单";
      case "LINE_ADDED" -> "加行";
      case "LINE_REMOVED" -> "删行";
      case "ATTACHED" -> "挂凭证";
      case "ATTACHMENT_REMOVED" -> "摘凭证";
      case "SUBMITTED" -> "提交审批";
      case "APPROVED" -> "批准";
      case "REJECTED" -> "驳回";
      case "POSTED" -> "过账";
      case "CANCELLED" -> "作废";
      default -> action;
    };
  }

  // ============================================================ 行解析

  /** 一行解析结果：bucket 的六维 + 可读名 + 当前版本 + 归一化后的带符号数量。 */
  private record ResolvedLine(
      UUID bucketId,
      UUID balanceId,
      UUID legalEntityId,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID warehouseId,
      String warehouseCode,
      String warehouseKind,
      UUID locationId,
      String locationCode,
      UUID lotId,
      String lotCode,
      UUID qualityStatusId,
      String qualityStatusCode,
      BigDecimal qtyDelta,
      long expectedVersion) {}

  /**
   * 解析一行调整请求：定位 bucket、核数量、核原因码、核乐观锁。
   *
   * <p>六维**从 bucket 读出来**，不接受调用方自报 —— 调用方自报六维就会出现
   * 「行声称自己属于 A 仓的 balance，却定位 B 仓的 bucket」这种张冠李戴。
   */
  private ResolvedLine resolveLine(InventoryAdjustmentLineInput input, InventoryAdjustmentReason fallback) {
    if (input == null) {
      throw bad("调整行不能为空");
    }
    if (input.bucketId() == null) {
      throw bad("调整行必须指定库位明细（bucket）：调整必须落在具体的事实单位上，"
          + "不能只给一个 SKU 和一个数量。");
    }
    InventoryAdjustmentReason reason =
        notBlank(input.reasonCode()) ? reason(input.reasonCode()) : fallback;
    BigDecimal qty = normalizeQty(input.qtyDelta());
    if (reason == InventoryAdjustmentReason.OVERAGE && qty.signum() <= 0) {
      throw bad("「盘盈」的调整数量必须是正数（当前 " + qty.toPlainString() + "）："
          + "方向写反的调整单在审批时是看不出来的。");
    }
    if (reason == InventoryAdjustmentReason.SHORTAGE && qty.signum() >= 0) {
      throw bad("「盘亏」的调整数量必须是负数（当前 " + qty.toPlainString() + "）："
          + "方向写反的调整单在审批时是看不出来的。");
    }
    if (input.expectedVersion() == null) {
      throw bad("必须带 expected_version（读这行时的库位明细版本）："
          + "没有它就无法判断调用方看到的是不是过期数量。");
    }
    var bucket =
        sql.fetchOne(
            "select k.id,k.balance_id,k.legal_entity_id,k.sku_id,k.warehouse_id,k.location_id,k.lot_id,"
                + "k.quality_status_id,k.version,k.on_hand,"
                + "s.code as sku_code,s.name as sku_name,"
                + "w.code as warehouse_code,w.kind as warehouse_kind,"
                + "loc.code as location_code,coalesce(lot.lot_code,'') as lot_code,"
                + "q.code as quality_status_code"
                + " from stock_bucket k"
                + " left join sku_ref s on s.tenant_id = k.tenant_id and s.id = k.sku_id"
                + " left join warehouse w on w.tenant_id = k.tenant_id and w.id = k.warehouse_id"
                + " left join warehouse_location loc on loc.tenant_id = k.tenant_id and loc.id = k.location_id"
                + " left join inventory_lot lot on lot.tenant_id = k.tenant_id and lot.id = k.lot_id"
                + " left join inventory_quality_status q on q.tenant_id = k.tenant_id and q.id = k.quality_status_id"
                + " where k.tenant_id = ? and k.id = ?",
            tenant(),
            input.bucketId());
    if (bucket == null) {
      throw notFound("库位明细", input.bucketId());
    }
    String warehouseKind = nullToEmpty(bucket.get("warehouse_kind", String.class));
    if ("PLATFORM_FBA".equals(warehouseKind)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "平台仓（FBA）没有自有账本余额，因此没有可调的库位明细："
              + "平台仓数量是**观测值**，只在 platform_stock_observation 里，"
              + "把它当成自货调整掉会制造出「平台仓也有自有账本」的第二个真相。"
              + "平台仓差异请走 10 外部仓对账与 7.1.3.1 多仓分布。");
    }
    long current = bucket.get("version", Long.class) == null ? 0L : bucket.get("version", Long.class);
    if (input.expectedVersion() != current) {
      throw conflict(
          "库位明细 " + input.bucketId() + " 在你读取之后已经变动（版本 "
              + input.expectedVersion() + " → " + current + "）："
              + "请刷新后按当前数量重新发起调整单 —— 拿旧数字改新余额就是在改一个不存在的数。");
    }
    return new ResolvedLine(
        bucket.get("id", UUID.class),
        bucket.get("balance_id", UUID.class),
        bucket.get("legal_entity_id", UUID.class),
        bucket.get("sku_id", UUID.class),
        nullToEmpty(bucket.get("sku_code", String.class)),
        nullToEmpty(bucket.get("sku_name", String.class)),
        bucket.get("warehouse_id", UUID.class),
        nullToEmpty(bucket.get("warehouse_code", String.class)),
        warehouseKind,
        bucket.get("location_id", UUID.class),
        nullToEmpty(bucket.get("location_code", String.class)),
        bucket.get("lot_id", UUID.class),
        nullToEmpty(bucket.get("lot_code", String.class)),
        bucket.get("quality_status_id", UUID.class),
        nullToEmpty(bucket.get("quality_status_code", String.class)),
        qty,
        current);
  }

  private void insertLine(
      UUID orderId,
      ResolvedLine line,
      InventoryAdjustmentReason reason,
      OffsetDateTime now,
      int lineNo) {
    sql.execute(
        "insert into inventory_adjustment_order_line(id,tenant_id,order_id,bucket_id,balance_id,"
            + "legal_entity_id,sku_id,warehouse_id,location_id,lot_id,quality_status_id,"
            + "sku_code,sku_name,warehouse_code,location_code,lot_code,quality_status_code,"
            + "qty_delta,reason_code,note,expected_version,line_no,created_at) "
            + "values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        tenant(),
        orderId,
        line.bucketId(),
        line.balanceId(),
        line.legalEntityId(),
        line.skuId(),
        line.warehouseId(),
        line.locationId(),
        line.lotId(),
        line.qualityStatusId(),
        line.skuCode(),
        line.skuName(),
        line.warehouseCode(),
        line.locationCode(),
        line.lotCode(),
        line.qualityStatusCode(),
        line.qtyDelta(),
        reason.name(),
        "",
        line.expectedVersion(),
        lineNo,
        now);
  }

  /**
   * 下一个行号：同一张单内 {@code max(line_no) + 1}。
   *
   * <p><b>调用方必须已经锁住单头</b>（{@link #lockOrderHeader}）⇒ 同一张单的并发加行排队
   * ⇒ 两笔不会算出同一个行号。DB 层还有 {@code UNIQUE(tenant_id, order_id, line_no)} 兜底
   * （V261）：绕过服务层直接 INSERT 也撞不动。
   */
  private int nextLineNo(UUID orderId) {
    return sql.fetchOne(
            "select coalesce(max(line_no), 0) + 1 as n from inventory_adjustment_order_line"
                + " where tenant_id = ? and order_id = ?",
            tenant(),
            orderId)
        .get("n", Integer.class);
  }

  /**
   * 锁住调整单头（{@code FOR UPDATE}）。
   *
   * <p>加行前必须先锁：行号取自「单内已有多少行」，不锁住单头的话两个并发加行会算出
   * 同一个 {@code max(line_no)+1}。顺带把「加行期间单据被别人提交/作废」这条竞态也关上了 ——
   * 提交与作废都会去改这一行，锁住之后它们只能等我们加完这一行。
   */
  private void lockOrderHeader(UUID orderId) {
    var row =
        sql.fetchOne(
            "select id from inventory_adjustment_order where tenant_id = ? and id = ? for update",
            tenant(),
            orderId);
    if (row == null) {
      throw notFound("调整单", orderId);
    }
  }

  private void insertAttachment(
      UUID orderId, InventoryAdjustmentAttachmentInput input, UUID actorId, OffsetDateTime now) {
    if (input.mediaId() == null) {
      throw bad("凭证附件必须带 media_id（catalog 媒体库里的媒体 id）：本模块只登记引用，不存文件本身。");
    }
    String fileName = requireText(input.fileName(), "凭证附件必须带文件名：没有文件名的凭证在单据上认不出来。");
    if (fileName.length() > 255) {
      throw bad("凭证文件名超过 255 字。");
    }
    if (input.sizeBytes() != null && input.sizeBytes() < 0) {
      throw bad("凭证文件大小不能是负数。");
    }
    sql.execute(
        "insert into inventory_adjustment_attachment(id,tenant_id,order_id,media_id,file_name,media_type,"
            + "size_bytes,mounted_at,mounted_by_user_id,created_at) "
            + "values(?,?,?,?,?,?,?,?::timestamptz,?,?::timestamptz)",
        UUID.randomUUID(),
        tenant(),
        orderId,
        input.mediaId(),
        fileName,
        optionalText(input.mediaType(), 128),
        input.sizeBytes(),
        now,
        actorId,
        now);
  }

  private void audit(UUID orderId, String action, UUID actorId, OffsetDateTime at, String detail) {
    // occurred_at 在第 5 位，created_at 用同一次绑定（同一个数组派生两列，不手数占位符）。
    sql.execute(
        "insert into inventory_adjustment_audit(id,tenant_id,order_id,action,actor_user_id,"
            + "occurred_at,detail,created_at) values(?,?,?,?,?,?::timestamptz,?,?::timestamptz)",
        UUID.randomUUID(),
        tenant(),
        orderId,
        action,
        actorId,
        at,
        detail,
        at);
  }

  private InventoryAdjustmentOrder requireOrder(UUID orderId) {
    var row =
        sql.fetchOne(
            "select "
                + ORDER_COLUMNS
                + ", "
                + ORDER_AGG
                + " from "
                + ORDER_FROM
                + " where a.tenant_id = ? and a.id = ? group by a.id, r.label",
            tenant(),
            orderId);
    if (row == null) {
      throw notFound("调整单", orderId);
    }
    return order(row);
  }

  private InventoryAdjustmentOrder requireDraft(UUID orderId, String action) {
    var order = requireOrder(orderId);
    if (order.status() != InventoryAdjustmentStatus.DRAFT) {
      throw conflict(
          "调整单 " + order.code() + " 当前是「" + order.status().label() + "」，只有草稿能" + action
              + "：已提交的单据是证据，不许事后改写。");
    }
    return order;
  }

  private void requireLines(UUID orderId) {
    long count =
        sql.fetchOne(
                "select count(*) from inventory_adjustment_order_line where tenant_id = ? and order_id = ?",
                tenant(),
                orderId)
            .get(0, Long.class);
    if (count == 0) {
      throw bad("这张调整单没有任何行，不能提交待审：空单提交上去只会占住审批队列。");
    }
  }

  // ============================================================ 小工具

  /** 租户来自 RLS 的同一个来源，绝不来自任何请求参数或客户端头。 */
  private UUID tenant() {
    return sql.fetchOne("select current_setting('app.current_tenant',true)::uuid as t")
        .get("t", UUID.class);
  }

  /**
   * 数量归一化：精度与库存一致（4 位小数），**不做静默四舍五入**。
   *
   * <p>0.00005 被悄悄四舍五入成 0.0001 还好（多调了 0.00005），被悄悄截成 0 则是
   * 「这一行调整被静默抹掉了」—— 静默抹掉一行比报错危险得多。
   */
  private static BigDecimal normalizeQty(BigDecimal raw) {
    if (raw == null) {
      throw bad("调整数量不能为空。");
    }
    BigDecimal scaled;
    try {
      scaled = raw.setScale(QTY_SCALE, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw bad(
          "调整数量最多 4 位小数（库存数量精度是 numeric(19,4)）："
              + raw.toPlainString()
              + "。系统不会替你四舍五入 —— 被悄悄改掉的数字会让人以为调的是那个数。");
    }
    if (scaled.signum() == 0) {
      throw bad("调整数量取整到 4 位小数后是 0：这一行等于没调。");
    }
    return scaled;
  }

  private static InventoryAdjustmentReason reason(String raw) {
    if (!notBlank(raw)) {
      throw bad(
          "必须给出调整原因码："
              + java.util.Arrays.stream(InventoryAdjustmentReason.values())
                  .map(Enum::name)
                  .reduce((x, y) -> x + " / " + y)
                  .orElse(""));
    }
    InventoryAdjustmentReason parsed;
    try {
      parsed = InventoryAdjustmentReason.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad(
          "未知的调整原因码："
              + raw
              + "。可用值 "
              + java.util.Arrays.stream(InventoryAdjustmentReason.values()).map(Enum::name).toList());
    }
    if (!parsed.available()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "调整原因码「"
              + parsed.name()
              + "」当前不可用："
              + "平台仓（FBA）库存是**观测值**不是自有账本，"
              + "V55 的数据库触发器直接禁止给平台仓写 balance / bucket 行 —— "
              + "平台仓根本没有可调的库位明细。把平台观测值当成自货调整掉，"
              + "等于制造出「平台仓也有自有账本」的第二个真相。"
              + "平台仓差异请走 10 外部仓对账与 7.1.3.1 多仓分布（归属 "
              + parsed.ownerModule()
              + "）。");
    }
    return parsed;
  }

  private String nextCode(OffsetDateTime now) {
    return "ADJ-INV-"
        + CODE_DAY.format(now)
        + "-"
        + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    binds.addAll(List.of(more));
    return binds;
  }

  private static BigDecimal scale(BigDecimal v) {
    return v == null ? ZERO : v.setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  private static boolean notBlank(String v) {
    return v != null && !v.isBlank();
  }

  private static String nullToEmpty(String v) {
    return v == null ? "" : v;
  }

  private static String optionalText(String raw, int max) {
    String v = raw == null ? "" : raw.trim();
    if (v.length() > max) {
      throw bad("内容超过 " + max + " 字，请写结论与依据，不要整段粘贴。");
    }
    return v;
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
