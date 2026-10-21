package com.starcon.trade;

import static com.starcon.trade.DefectiveGoodsModels.*;
import static com.starcon.trade.DefectiveGoodsRules.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
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
 * 6.4.2.2 不良品处理。
 *
 * <p>蓝图原话：「不合格品的处置：退换货补货、返修、让步接收、报废、转为促销品，
 * 处置结论联动库存状态与供应商索赔。」输入「质检不合格明细；处置决策；索赔金额。」
 * 输出「处置单 + 库存状态变更指令 + 供应商索赔记录。」
 * 关系：上游 = 6.4.2.1；下游 = 7 库存（调整）、12 财务（索赔/退款）。
 * <b>让步接收需要权限与原因，不能由质检员随手放行。</b>
 *
 * <p><b>本模块只做一件事：把「一批不合格品最后怎么处置」变成一份有凭据的处置单，
 * 并据此发出库存指令与索赔记录。</b>
 *
 * <p><b>十一条实现纪律</b>：
 *
 * <ol>
 *   <li><b>质检结论只有一个来源</b>：处置单上的每一个数都从 6.4.2.1 质检单回读，
 *       入参里**没有**处置总量这一栏；DB 层还有一条触发器把四个数再核对一遍。</li>
 *   <li><b>处置量由明细行重算</b>：本单没有「直接写处置量」的入口。</li>
 *   <li><b>可售量上限被显式带到界面上</b>：合格量 + 让步接收量，处置不得侵占它。</li>
 *   <li><b>让步接收要权限 + 原因</b>：两者缺一拒绝；且记录人**必须真的持有**
 *       {@code trade.defective-disposition.concession}（比质检结论录入高一档）。</li>
 *   <li><b>库存只发指令、不建账</b>：warehouse 没有可复用的处置写入口 ⇒
 *       指令状态如实报出，绝不出现「已登记」却其实没动库存。</li>
 *   <li><b>索赔落在 trade 本库</b>：登记金额与已生效冲减金额是**两列**；
 *       12 财务在本仓不存在 ⇒ 已生效金额恒为 0。</li>
 *   <li><b>放行量由处置类型决定</b>：不让步接收 / 转促销品的货都离仓或不可售。</li>
 *   <li><b>终态不可改写</b>：已决策的处置单不再接受任何内容修改（DB 触发器 + 服务层）。</li>
 *   <li><b>跨服务只经授权 HTTP</b>：库存派发只经 {@link DefectiveInventoryPort}。</li>
 *   <li><b>时间一律 UTC 口径</b>：{@code timestamptz} 列用 {@code OffsetDateTime} 绑定，
 *       values 位置写 {@code ?::timestamptz}。</li>
 *   <li><b>数量全程 BigDecimal</b>，DB 侧 numeric(19,6)；金额 numeric(19,4)。</li>
 * </ol>
 */
@Service
public class DefectiveGoodsService {

  /**
   * 让步接收需要的权限码 —— 记录人必须真的持有它。
   *
   * <p><b>刻意与「质检结论录入」分开</b>：质检员手里的
   * {@code trade.quality-inspection.update} 放不了行一批不合格品 ——
   * 蓝图加粗句「让步接收需要权限与原因，不能由质检员随手放行」要落成一个**权限档**，
   * 而不是一句提示文案。
   */
  public static final String CONCESSION_PERMISSION = "trade.defective-disposition.concession";

  /** 库存分录的写入所有权归谁（如实标注在每一条指令上）。 */
  private static final String INVENTORY_OWNER = "7.1.1.2 库存明细 / 8.2.2.2 待检处理（warehouse）";

  /** 财务侧归谁（如实标注在每一条索赔上）。 */
  private static final String FINANCE_OWNER = "12 财务（finance 服务，本仓不存在）";

  private static final String QUANTITY_NOTE =      "处置量**不接受调用方自报**：建单时所有数量都从 6.4.2.1 质检单回读，"
          + "处置总量由明细行求和重算（DB 守卫），入参里没有处置总量这一栏。"
          + "质检单的合格量 + 不合格量 + 让步接收量 = 本次质检覆盖的批量（恒成立）；"
          + "本模块处置的是**不合格量**这一段，合格量与让步接收量（= 可售量上限）不得被侵占。";

  private static final String CONCESSION_NOTE =
      "让步接收（concession）**必须带原因**，且**必须校验权限**："
          + "记录人要真的持有 "
          + CONCESSION_PERMISSION
          + "（比「质检结论录入」trade.quality-inspection.update 高一档 —— "
          + "质检员不能随手放行）。原因与权限码都落进 append-only 的处置明细，"
          + "事后查得到是谁、凭什么、为什么放行了这一批。";

  private static final String INVENTORY_NOTE =
      "库存状态变更**只发指令、不建账**：库存分录的写入所有权在 warehouse，"
          + "跨库写入被 AGENTS.md 禁止。warehouse 当前没有「按处置单变更库存状态」的授权写入口"
          + "（已交付的 /warehouse/pending-inspections/** 全部挂在 warehouse 自有的待检处理单上，"
          + "要 stock_bucket.id / goods_receipt_line.id / 目标库位这些内部外键）"
          + "⇒ 指令状态如实报出：没派发出去就是 UNAVAILABLE，绝不出现「已登记」却其实没动库存。";

  private static final String CLAIM_NOTE =
      "供应商索赔记录落在 **trade 本库**（谁写谁负责）。"
          + "「登记的索赔金额」与「已生效的冲减金额」是**两列**："
          + "12 财务（finance 服务）在本仓不存在 ⇒ 已生效金额恒为 0、财务状态恒为 PENDING_FINANCE。"
          + "**不假装已经入账** —— 财务侧入账 / 退款执行归 12 财务。";

  /**
   * 处置单的查询列。
   *
   * <p>明细条数、指令条数、索赔条数与**去向摘要**全部用**子查询**取，
   * 而不是在 Java 里逐单再查一遍 —— 列表页 50 行就是 50 次额外往返，
   * 而且「列表显示的去向 = 详情显示的去向」这种不一致最难查。
   */
  private static final String ORDER_SELECT =
      "select o.*,"
          + " (select count(*) from defective_disposition_line l"
          + "  where l.tenant_id=o.tenant_id and l.order_id=o.id) as line_count,"
          + " (select count(*) from defective_inventory_instruction i"
          + "  where i.tenant_id=o.tenant_id and i.order_id=o.id) as instruction_count,"
          + " (select count(*) from defective_supplier_claim c"
          + "  where c.tenant_id=o.tenant_id and c.order_id=o.id) as claim_count,"
          + " (select string_agg(distinct l.disposition_type, ',' order by l.disposition_type)"
          + "    from defective_disposition_line l"
          + "   where l.tenant_id=o.tenant_id and l.order_id=o.id) as disposition_types,"
          + " (select string_agg(t.disposition_type || ' ' || rtrim(rtrim(t.qty::text,'0'),'.'),"
          + "                   '、' order by t.disposition_type)"
          + "    from (select l.disposition_type, sum(l.quantity) as qty"
          + "            from defective_disposition_line l"
          + "           where l.tenant_id=o.tenant_id and l.order_id=o.id"
          + "           group by l.disposition_type) t) as disposition_summary_label,"
          + " i.currency_code as inspection_currency_code,"
          + " i.unit_price as unit_price"
          + " from defective_disposition_order o"
          + " left join quality_inspection_order i"
          + "   on i.tenant_id = o.tenant_id and i.id = o.inspection_order_id";

  private final DSLContext sql;
  private final DefectiveInventoryPort inventory;

  public DefectiveGoodsService(DSLContext sql, DefectiveInventoryPort inventory) {
    this.sql = sql;
    this.inventory = inventory;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：本模块横跨两个**没有交付**的下游（7 库存的写入口、12 财务），
   * 而这两件事恰恰是处置结论的落点 —— 哪一个接通了必须由接口自己报出。
   */
  public DefectiveGoodsCapabilities capabilities() {
    List<DefectiveCapabilityItem> upstream =
        List.of(
            new DefectiveCapabilityItem(
                "QUALITY_INSPECTION",
                "6.4.2.1 质检单（trade 服务，同库）",
                true,
                null,
                null,
                "质检结论的唯一来源。处置单上的合格量 / 不合格量 / 让步接收量 / 扣量合计"
                    + "**全部从质检单回读**，入参里没有处置总量这一栏；"
                    + "DB 层还有一条触发器把四个数再核对一遍 —— 绕不过服务层直接写库也一样被挡。"),
            new DefectiveCapabilityItem(
                "DEFECTIVE_DETAIL",
                "质检不合格明细（6.4.2.1 的逐项检验结论）",
                true,
                null,
                null,
                "不合格明细在质检单上（逐项结论 + 扣量）。本模块按**不合格量**做处置，"
                    + "不重新判定哪些件不合格 —— 重新判定就是让同一个结论有两份。"));

    List<DefectiveCapabilityItem> downstream =
        List.of(
            new DefectiveCapabilityItem(
                "INVENTORY_ADJUSTMENT",
                "7 库存（调整）/ 8.2.2.2 待检处理",
                false,
                INVENTORY_OWNER,
                "处置结论**没有**真的动到库存：warehouse 当前没有「按处置单变更库存状态」的"
                    + "授权写入口（已交付的 /warehouse/pending-inspections/** 全部挂在 warehouse "
                    + "自有的待检处理单上，要 stock_bucket.id / goods_receipt_line.id / 目标库位这些"
                    + "内部外键，trade 侧没有可复用的入口）。"
                    + "影响：本模块只落**指令**（defective_inventory_instruction），"
                    + "指令状态如实报出；可售量口径（7.1.1.3）在本模块交付后仍看不到本模块的处置量 —— "
                    + "要等 warehouse 提供按处置单变更库存状态的写端点后，7 库存才会把报废 / 返修 / "
                    + "退换货的量从可售量里扣掉。",
                "warehouse 服务未提供可用的授权写端点。"),
            new DefectiveCapabilityItem(
                "FINANCE_CLAIM",
                "12 财务（索赔 / 退款）",
                false,
                FINANCE_OWNER,
                "索赔记录落在 trade 本库，但**财务侧入账 / 退款执行没有发生**："
                    + "本仓不存在 finance 服务（backend/services 下只有 catalog / channel / crm / "
                    + "identity / trade / warehouse）。"
                    + "影响：已生效冲减金额恒为 0、财务状态恒为 PENDING_FINANCE；"
                    + "要等 12 财务交付后由它接管入账与退款执行。",
                "finance 服务在本仓不存在。"),
            new DefectiveCapabilityItem(
                "SUPPLIER_RATING",
                "6.1.1.3 供应商评级",
                false,
                "6.1.1.3",
                "本模块产出的处置与索赔是评级的**输入之一**（某供应商的索赔率），"
                    + "但评级模块未交付 ⇒ 本模块不产出任何评级结论，也不代它算分。",
                "6.1.1.3 尚未交付。"),
            new DefectiveCapabilityItem(
                "PURCHASE_RECONCILIATION",
                "6.5.1 采购对账",
                false,
                "6.5.1",
                "处置扣量与索赔都会影响应付，但**对账由采购发起、12 财务执行付款** —— "
                    + "本模块只如实记录索赔金额与币种，不代 6.5.1 发起对账，也不代 12 财务确认应付。",
                "6.5.1 的索赔汇总口径以本模块的 defective_supplier_claim 为准。"));

    List<DefectiveCapabilityItem> actions =
        List.of(
            new DefectiveCapabilityItem(
                "CREATE_ORDER",
                "建处置单（数量从质检单回读）",
                true,
                null,
                null,
                "引用一张已完结的质检单，给处置理由与去向明细。"),
            new DefectiveCapabilityItem(
                "CONCESSION",
                "让步接收（放行）",
                true,
                null,
                null,
                "必须给原因与权限码，且记录人必须真的持有 " + CONCESSION_PERMISSION + "。缺一拒绝。"),
            new DefectiveCapabilityItem(
                "INVENTORY_INSTRUCTION",
                "发出库存状态变更指令",
                true,
                null,
                null,
                "指令**会真的发出去**（经授权 HTTP 端口）；但对端没有写端点时状态是 UNAVAILABLE —— "
                    + "本模块只发指令、不建账。"),
            new DefectiveCapabilityItem(
                "DISPATCH_INVENTORY",
                "把指令真正送到 warehouse",
                false,
                INVENTORY_OWNER,
                "本仓当前没有对端端点 ⇒ 派发结果是 UNAVAILABLE，"
                    + "处置单上如实写「上游无写端点」，不会出现「已登记」却其实没动库存。",
                "warehouse 服务未提供可用的授权写端点。"),
            new DefectiveCapabilityItem(
                "SUPPLIER_CLAIM",
                "供应商索赔记录",
                true,
                null,
                null,
                "登记金额与币种，对供应商走提交 / 认可 / 拒赔 / 撤回的过程；"
                    + "已生效冲减金额恒为 0（12 财务未交付）。"));

    return new DefectiveGoodsCapabilities(
        upstream,
        downstream,
        actions,
        dispositionTypeCodes(),
        dispositionTypeLabels(),
        QUANTITY_NOTE,
        CONCESSION_NOTE,
        INVENTORY_NOTE,
        CLAIM_NOTE,
        "本模块的职责边界：引用 6.4.2.1 质检单 → 填处置去向明细（退换货补货 / 返修 / "
            + "让步接收 / 报废 / 转为促销品）→ 决策定稿（生成库存指令与索赔记录）→ "
            + "派发库存指令 → 走索赔过程 → 完成 / 作废 → 导出 CSV。");
  }

  // ================================================================ 下拉

  /** 建单需要的下拉与词表。可处置的质检单 = 已完结且仍有不合格量。 */
  public DefectiveOptions options() {
    List<DefectiveInspectionOption> inspections = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select o.id,o.inspection_no,o.result,o.sku_id,o.sku_code,o.sku_name,"
                + " o.supplier_id,o.supplier_name,o.lot_size,o.defective_quantity,"
                + " o.qualified_quantity,o.concession_quantity,o.gross_deducted_quantity,o.completed_at,"
                + " (select coalesce(sum(d.disposed_quantity),0) from defective_disposition_order d"
                + "  where d.tenant_id=o.tenant_id and d.inspection_order_id=o.id"
                + "    and d.status <> 'CANCELLED') as disposed_quantity"
                + " from quality_inspection_order o"
                + " where o.tenant_id = ?::uuid and o.status = 'COMPLETED'"
                + "   and o.defective_quantity > 0"
                + " order by o.completed_at desc nulls last, o.id limit 200",
            currentTenant())) {
      BigDecimal defective = row.get("defective_quantity", BigDecimal.class);
      BigDecimal disposed = row.get("disposed_quantity", BigDecimal.class);
      inspections.add(
          new DefectiveInspectionOption(
              row.get("id", UUID.class),
              row.get("inspection_no", String.class),
              row.get("result", String.class),
              row.get("sku_id", UUID.class),
              row.get("sku_code", String.class),
              row.get("sku_name", String.class),
              row.get("supplier_id", UUID.class),
              row.get("supplier_name", String.class),
              row.get("lot_size", BigDecimal.class),
              defective,
              row.get("qualified_quantity", BigDecimal.class),
              row.get("concession_quantity", BigDecimal.class),
              sellableCeiling(
                  row.get("qualified_quantity", BigDecimal.class),
                  row.get("concession_quantity", BigDecimal.class)),
              row.get("gross_deducted_quantity", BigDecimal.class),
              disposed,
              remainingDefective(defective, disposed),
              row.get("completed_at", OffsetDateTime.class)));
    }
    return new DefectiveOptions(
        inspections,
        dispositionTypeCodes(),
        dispositionTypeLabels(),
        orderStatusCodes(),
        orderStatusLabels(),
        inventoryStatusCodes(),
        inventoryStatusLabels(),
        claimStatusCodes(),
        claimStatusLabels(),
        "下拉里只列**已完结且仍有不合格量**的质检单；一张质检单可以被分多张处置单处置，"
            + "但累计处置量不得超过它的不合格量（remainingQuantity 就是还能处置的量）。");
  }

  // ================================================================ 列表 / 详情

  /** 处置单列表（真分页 + 真筛选 + 真排序）。 */
  public DefectiveOrderPage pageOrders(
      String keyword,
      String status,
      String dispositionType,
      String inspectionNo,
      String skuCode,
      UUID supplierId,
      String sort,
      int page,
      int size) {
    int limit = pageSize(size);
    int offset = Math.max(page, 0) * limit;
    StringBuilder where = new StringBuilder(" where o.tenant_id = ?::uuid");
    List<Object> args = new ArrayList<>();
    args.add(currentTenant());
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (o.disposition_no ilike ? or o.inspection_no ilike ?)");
      args.add("%" + keyword.trim() + "%");
      args.add("%" + keyword.trim() + "%");
    }
    if (status != null && !status.isBlank()) {
      where.append(" and o.status = ?");
      args.add(status);
    }
    if (inspectionNo != null && !inspectionNo.isBlank()) {
      where.append(" and o.inspection_no = ?");
      args.add(inspectionNo.trim());
    }
    if (skuCode != null && !skuCode.isBlank()) {
      where.append(" and o.sku_code ilike ?");
      args.add("%" + skuCode.trim() + "%");
    }
    if (supplierId != null) {
      where.append(" and o.supplier_id = ?::uuid");
      args.add(supplierId);
    }
    if (dispositionType != null && !dispositionType.isBlank()) {
      // 处置类型在明细行上 ⇒ 用 EXISTS 子查询，不 JOIN（JOIN 会让同一单出现多行、
      // 分页的 total 与行数对不上）。
      where.append(
          " and exists (select 1 from defective_disposition_line l"
              + " where l.tenant_id=o.tenant_id and l.order_id=o.id and l.disposition_type = ?)");
      args.add(dispositionType);
    }
    String orderBy =
        switch (sort == null ? "created" : sort) {
          case "quantity" -> " o.disposed_quantity desc, o.id";
          case "decided" -> " o.decided_at desc nulls last, o.id";
          case "status" -> " o.status, o.created_at desc, o.id";
          default -> " o.created_at desc, o.id";
        };
    List<Record> rows =
        sql.fetch(
            ORDER_SELECT + where + " order by " + orderBy + " limit ? offset ?",
            argsOf(args, limit, offset));
    long total =
        sql.fetchOne(
                "select count(*) from defective_disposition_order o" + where, argsOf(args))
            .get(0, Long.class);
    List<DefectiveOrderRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(orderRow(row));
    }
    return new DefectiveOrderPage(
        items, Math.max(page, 0), limit, total, (int) Math.ceil(total / (double) limit), "");
  }
  /** 处置单详情：头 + 明细 + 库存指令 + 索赔 + 事件台账。 */
  public DefectiveOrderDetail orderDetail(UUID id) {
    Record order = orderRecord(id);
    List<DefectiveLineRow> lines = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select * from defective_disposition_line"
                + " where tenant_id=?::uuid and order_id=?::uuid order by sort_order, id",
            currentTenant(),
            id)) {
      lines.add(lineRow(row));
    }
    List<DefectiveInstructionRow> instructions = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select i.* from defective_inventory_instruction i"
                + " join defective_disposition_line l"
                + "   on l.tenant_id=i.tenant_id and l.id=i.line_id"
                + " where i.tenant_id=?::uuid and i.order_id=?::uuid"
                + " order by l.sort_order, i.id",
            currentTenant(),
            id)) {
      instructions.add(instructionRow(row));
    }
    List<DefectiveClaimRow> claims = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select * from defective_supplier_claim"
                + " where tenant_id=?::uuid and order_id=?::uuid order by created_at, id",
            currentTenant(),
            id)) {
      claims.add(claimRow(row));
    }
    List<DefectiveEventRow> events = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select * from defective_disposition_event"
                + " where tenant_id=?::uuid and order_id=?::uuid order by occurred_at, id",
            currentTenant(),
            id)) {
      events.add(eventRow(row));
    }
    return new DefectiveOrderDetail(
        orderRow(order),
        lines,
        instructions,
        claims,
        events,
        QUANTITY_NOTE,
        CONCESSION_NOTE,
        INVENTORY_NOTE,
        CLAIM_NOTE,
        availableActions(order.get("status", String.class)),
        "处置单里的每个数都能追到来源：数量来自质检单，库存指令与索赔单由「决策定稿」一次生成。");
  }

  // ================================================================ 写：建单 / 改单

  /**
   * 建处置单。
   *
   * <p>数量**全部从质检单回读**；入参里没有处置总量。
   */
  @Transactional
  public DefectiveOrderRow createOrder(
      DefectiveOrderInput input, UUID actorId, List<String> actorPermissions) {
    if (input.lines().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "处置单至少要有一条去向明细 —— 没有明细就没有处置结论");
    }
    Record inspection = inspectionRow(input.inspectionOrderId());
    DefectiveInspectionContext ctx = inspectionContext(inspection);

    BigDecimal planned = BigDecimal.ZERO.setScale(QUANTITY_SCALE, java.math.RoundingMode.HALF_UP);
    for (DefectiveLineInput line : input.lines()) {
      planned = planned.add(scale(line.quantity()));
    }
    BigDecimal already = disposedAcross(ctx.inspectionId());
    BigDecimal remaining = remainingDefective(ctx.defective(), already);
    if (planned.compareTo(remaining) > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "处置量 "
              + planned.toPlainString()
              + " 超过了这张质检单还能处置的量 "
              + remaining.toPlainString()
              + "（不合格量 "
              + ctx.defective().toPlainString()
              + " − 其它处置单已处置 "
              + already.toPlainString()
              + "）");
    }

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    String dispositionNo = "DG-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    insert(
        "defective_disposition_order",
        List.of(
            "id", "tenant_id", "disposition_no", "inspection_order_id", "inspection_no",
            "inspection_result", "sku_id", "sku_code", "sku_name", "supplier_id", "supplier_name",
            "receipt_code", "batch_no", "inspection_lot_size", "inspection_defective_quantity",
            "inspection_qualified_quantity", "inspection_concession_quantity", "sellable_ceiling",
            "gross_deducted_quantity", "disposed_quantity", "released_quantity", "status", "reason",
            "inventory_status", "claim_status", "currency_code", "claim_amount", "version", "note",
            "created_by", "created_at", "updated_at"),
        List.of(
            "?::uuid", "?::uuid", "?::varchar", "?::uuid", "?::varchar", "?::varchar", "?::uuid",
            "?::varchar", "?::varchar", "?::uuid", "?::varchar", "?::varchar", "?::varchar", "?",
            "?", "?", "?", "?", "?", "?", "?", "?::varchar", "?::varchar", "?::varchar", "?::varchar",
            "?::varchar", "?", "?", "?::varchar", "?::uuid", "?::timestamptz", "?::timestamptz"),
        Arrays.asList(
            (Object) id,
            currentTenant(),
            dispositionNo,
            ctx.inspectionId(),
            ctx.inspectionNo(),
            ctx.result(),
            ctx.skuId(),
            ctx.skuCode(),
            ctx.skuName(),
            ctx.supplierId(),
            ctx.supplierName(),
            ctx.receiptCode(),
            ctx.batchNo(),
            ctx.lotSize(),
            ctx.defective(),
            ctx.qualified(),
            ctx.concession(),
            sellableCeiling(ctx.qualified(), ctx.concession()),
            ctx.grossDeducted(),
            BigDecimal.ZERO.setScale(QUANTITY_SCALE, java.math.RoundingMode.HALF_UP),
            BigDecimal.ZERO.setScale(QUANTITY_SCALE, java.math.RoundingMode.HALF_UP),
            DefectiveOrderStatus.DRAFT.name(),
            input.reason().trim(),
            DefectiveInventoryStatus.NOT_DISPATCHED.name(),
            DefectiveClaimStatus.NONE.name(),
            input.currencyCode(),
            scaleAmount(input.claimAmount() == null ? BigDecimal.ZERO : input.claimAmount()),
            0L,
            input.note() == null ? "" : input.note(),
            actorId,
            now,
            now));
    insertLines(id, input.lines(), actorId, actorPermissions);
    event(id, "CREATED", actorId, "处置单已建（数量从质检单 " + ctx.inspectionNo() + " 回读）");
    return orderRow(orderRecord(id));
  }

  /** 改处置明细（只有 DRAFT 能改）。 */
  @Transactional
  public DefectiveOrderRow updateOrder(
      UUID id, DefectiveOrderInput input, UUID actorId, List<String> actorPermissions) {
    Record order = orderRecord(id);
    DefectiveOrderStatus status = DefectiveOrderStatus.valueOf(order.get("status", String.class));
    if (status != DefectiveOrderStatus.DRAFT) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "处置单已决策（" + status.label() + "），不能再改明细：处置结论是凭据，改掉就没有留痕价值");
    }
    if (input.lines().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "处置单至少要有一条去向明细 —— 没有明细就没有处置结论");
    }
    DefectiveInspectionContext ctx = inspectionContext(inspectionRow(order.get("inspection_order_id", UUID.class)));
    BigDecimal planned = BigDecimal.ZERO.setScale(QUANTITY_SCALE, java.math.RoundingMode.HALF_UP);
    for (DefectiveLineInput line : input.lines()) {
      planned = planned.add(scale(line.quantity()));
    }
    BigDecimal already = disposedAcross(ctx.inspectionId());
    BigDecimal remaining = remainingDefective(ctx.defective(), already);
    if (planned.compareTo(remaining) > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "处置量 "
              + planned.toPlainString()
              + " 超过了这张质检单还能处置的量 "
              + remaining.toPlainString());
    }
    sql.execute(
        "delete from defective_disposition_line where tenant_id=?::uuid and order_id=?::uuid",
        currentTenant(),
        id);
    insertLines(id, input.lines(), actorId, actorPermissions);
    sql.execute(
        "update defective_disposition_order set reason=?, currency_code=?, claim_amount=?, note=?,"
            + " updated_at=?::timestamptz, version=version+1"
            + " where tenant_id=?::uuid and id=?::uuid",
        input.reason().trim(),
        input.currencyCode(),
        scaleAmount(input.claimAmount() == null ? BigDecimal.ZERO : input.claimAmount()),
        input.note() == null ? "" : input.note(),
        OffsetDateTime.now(ZoneOffset.UTC),
        currentTenant(),
        id);
    event(id, "UPDATED", actorId, "处置明细已改（数量仍由质检单约束）");
    return orderRow(orderRecord(id));
  }

  // ================================================================ 写：决策定稿

  /**
   * 决策定稿：生成库存状态变更指令与供应商索赔记录。
   *
   * <p>这一步是「处置结论联动库存状态与供应商索赔」的落点：一张处置单只生成一次，
   * 重跑不会产生第二条指令（{@code UNIQUE(tenant_id,line_id,action)} 兜底）。
   */
  @Transactional
  public DefectiveOrderRow decide(UUID id, UUID actorId) {
    Record order = orderRecord(id);
    DefectiveOrderStatus status = DefectiveOrderStatus.valueOf(order.get("status", String.class));
    if (status != DefectiveOrderStatus.DRAFT) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "处置单已决策（" + status.label() + "），不能重复定稿");
    }
    BigDecimal disposed = order.get("disposed_quantity", BigDecimal.class);
    if (disposed == null || disposed.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "处置单没有处置明细，先填去向再定稿");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    List<Record> lines =
        sql.fetch(
            "select * from defective_disposition_line"
                + " where tenant_id=?::uuid and order_id=?::uuid order by sort_order, id",
            currentTenant(),
            id);
    for (Record line : lines) {
      DefectiveDispositionType type =
          DefectiveDispositionType.valueOf(line.get("disposition_type", String.class));
      BigDecimal quantity = line.get("quantity", BigDecimal.class);
      insert(
          "defective_inventory_instruction",
          List.of(
              "id", "tenant_id", "order_id", "line_id", "action", "quantity", "owner_module",
              "status", "reason", "external_reference", "attempt_count", "created_at", "updated_at"),
          List.of(
              "?::uuid", "?::uuid", "?::uuid", "?::uuid", "?::varchar", "?", "?::varchar", "?::varchar",
              "?::varchar", "?::varchar", "?", "?::timestamptz", "?::timestamptz"),
          Arrays.asList(
              (Object) UUID.randomUUID(),
              currentTenant(),
              id,
              line.get("id", UUID.class),
              actionFor(type).name(),
              quantity,
              INVENTORY_OWNER,
              DefectiveInventoryStatus.NOT_DISPATCHED.name(),
              "指令已生成，等待派发到 " + INVENTORY_OWNER,
              "",
              0,
              now,
              now));
    }

    // ---- 供应商索赔记录 ----
    BigDecimal claimed = resolveClaimAmount(order, lines);
    DefectiveClaimStatus claimStatus = DefectiveClaimStatus.NONE;
    if (claimed != null && claimed.signum() > 0) {
      String currency = order.get("currency_code", String.class);
      if (currency == null || currency.isBlank()) {
        currency = order.get("inspection_currency_code", String.class);
      }
      if (currency == null || currency.isBlank()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "登记索赔金额就必须给币种：索赔跨币种绝不合并，缺币种会让后面没法对账");
      }
      DefectiveClaimType claimType = claimTypeFor(firstType(lines));
      insert(
          "defective_supplier_claim",
          List.of(
              "id", "tenant_id", "claim_no", "order_id", "supplier_id", "supplier_name",
              "claim_type", "currency_code", "claimed_amount", "agreed_amount", "effective_amount",
              "finance_status", "finance_reference", "status", "decided_reason", "note",
              "created_by", "created_at", "updated_at"),
          List.of(
              "?::uuid", "?::uuid", "?::varchar", "?::uuid", "?::uuid", "?::varchar", "?::varchar",
              "?::varchar", "?", "?", "?", "?::varchar", "?::varchar", "?::varchar", "?::varchar",
              "?::varchar", "?::uuid", "?::timestamptz", "?::timestamptz"),
          Arrays.asList(
              (Object) UUID.randomUUID(),
              currentTenant(),
              "CL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
              id,
              order.get("supplier_id", UUID.class),
              order.get("supplier_name", String.class),
              claimType.name(),
              currency,
              scaleAmount(claimed),
              null,
              BigDecimal.ZERO.setScale(AMOUNT_SCALE, java.math.RoundingMode.HALF_UP),
              "PENDING_FINANCE",
              "",
              DefectiveClaimRecordStatus.DRAFT.name(),
              "",
              "由处置定稿生成；已生效冲减金额恒为 0（12 财务未交付）。",
              actorId,
              now,
              now));
      claimStatus = DefectiveClaimStatus.PENDING;
    }
    sql.execute(
        "update defective_disposition_order set status=?, decided_by=?, decided_at=?::timestamptz,"
            + " inventory_status=?, claim_status=?, updated_at=?::timestamptz, version=version+1"
            + " where tenant_id=?::uuid and id=?::uuid",
        DefectiveOrderStatus.DECIDED.name(),
        actorId,
        now,
        DefectiveInventoryStatus.NOT_DISPATCHED.name(),
        claimStatus.name(),
        now,
        currentTenant(),
        id);
    event(id, "DECIDED", actorId, "处置已定稿：生成 " + lines.size() + " 条库存指令"
        + (claimStatus == DefectiveClaimStatus.PENDING ? " + 1 条供应商索赔记录" : "，未登记索赔金额"));
    return orderRow(orderRecord(id));
  }

  // ================================================================ 写：派发库存指令

  /**
   * 把库存状态变更指令真正送到 warehouse。
   *
   * <p>对端没有写端点时，指令状态落 {@code UNAVAILABLE} 并把原因写清楚 ——
   * **绝不出现「已登记」却其实没动库存**。
   */
  @Transactional
  public DefectiveOrderRow dispatchInventory(UUID id, UUID actorId, String bearer) {
    Record order = orderRecord(id);
    DefectiveOrderStatus status = DefectiveOrderStatus.valueOf(order.get("status", String.class));
    if (status == DefectiveOrderStatus.DRAFT) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "处置单还没定稿，先定稿再派发库存指令");
    }
    if (status.terminal()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "处置单已处于终态（" + status.label() + "），不再派发库存指令");
    }
    List<Record> instructions =
        sql.fetch(
            "select * from defective_inventory_instruction"
                + " where tenant_id=?::uuid and order_id=?::uuid and status <> 'DISPATCHED'"
                + " order by created_at, id",
            currentTenant(),
            id);
    if (instructions.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "没有待派发的库存状态变更指令");
    }
    int dispatched = 0;
    int unavailable = 0;
    int failed = 0;
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    for (Record instruction : instructions) {
      DefectiveInventoryPort.DispatchOutcome outcome =
          inventory.dispatch(
              id,
              order.get("disposition_no", String.class),
              order.get("inspection_no", String.class),
              instruction.get("action", String.class),
              instruction.get("quantity", BigDecimal.class),
              bearer);
      sql.execute(
          "update defective_inventory_instruction set status=?, reason=?, external_reference=?,"
              + " attempt_count=attempt_count+1, dispatched_at=?::timestamptz, dispatched_by=?, updated_at=?::timestamptz"
              + " where tenant_id=?::uuid and id=?::uuid",
          outcome.status().name(),
          outcome.reason(),
          outcome.externalReference() == null ? "" : outcome.externalReference(),
          outcome.status() == DefectiveInventoryStatus.DISPATCHED ? now : null,
          outcome.status() == DefectiveInventoryStatus.DISPATCHED ? actorId : null,
          now,
          currentTenant(),
          instruction.get("id", UUID.class));
      switch (outcome.status()) {
        case DISPATCHED -> dispatched++;
        case UNAVAILABLE -> unavailable++;
        default -> failed++;
      }
      event(
          id,
          outcome.status() == DefectiveInventoryStatus.DISPATCHED
              ? "INVENTORY_DISPATCHED"
              : outcome.status() == DefectiveInventoryStatus.UNAVAILABLE
                  ? "INVENTORY_UNAVAILABLE"
                  : "INVENTORY_FAILED",
          actorId,
          instruction.get("action", String.class) + "：" + outcome.reason());
    }
    DefectiveInventoryStatus summary =
        dispatched == instructions.size()
            ? DefectiveInventoryStatus.DISPATCHED
            : dispatched > 0 ? DefectiveInventoryStatus.PARTIAL
                : unavailable == instructions.size() ? DefectiveInventoryStatus.UNAVAILABLE
                    : DefectiveInventoryStatus.FAILED;
    sql.execute(
        "update defective_disposition_order set inventory_status=?, status=?, updated_at=?::timestamptz,"
            + " version=version+1 where tenant_id=?::uuid and id=?::uuid",
        summary.name(),
        summary == DefectiveInventoryStatus.DISPATCHED
            ? DefectiveOrderStatus.EXECUTING.name()
            : DefectiveOrderStatus.DECIDED.name(),
        now,
        currentTenant(),
        id);
    return orderRow(orderRecord(id));
  }

  // ================================================================ 写：完成 / 作废

  /** 完成处置单。 */
  @Transactional
  public DefectiveOrderRow complete(UUID id, UUID actorId) {
    Record order = orderRecord(id);
    DefectiveOrderStatus status = DefectiveOrderStatus.valueOf(order.get("status", String.class));
    if (status != DefectiveOrderStatus.DECIDED && status != DefectiveOrderStatus.EXECUTING) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "只有已决策 / 执行中的处置单能完成（当前状态：" + status.label() + "）");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update defective_disposition_order set status=?, completed_at=?::timestamptz, completed_by=?, "
            + " updated_at=?::timestamptz, version=version+1"
            + " where tenant_id=?::uuid and id=?::uuid",
        DefectiveOrderStatus.COMPLETED.name(),
        now,
        actorId,
        now,
        currentTenant(),
        id);
    event(id, "COMPLETED", actorId, "处置单已完成");
    return orderRow(orderRecord(id));
  }

  /** 作废处置单。理由必填。 */
  @Transactional
  public DefectiveOrderRow cancel(UUID id, DefectiveCancelInput input, UUID actorId) {
    Record order = orderRecord(id);
    DefectiveOrderStatus status = DefectiveOrderStatus.valueOf(order.get("status", String.class));
    if (status.terminal()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "处置单已处于终态（" + status.label() + "），不能再作废");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update defective_disposition_order set status=?, cancelled_at=?::timestamptz, cancelled_by=?,"
            + " cancel_reason=?, updated_at=?::timestamptz, version=version+1"
            + " where tenant_id=?::uuid and id=?::uuid",
        DefectiveOrderStatus.CANCELLED.name(),
        now,
        actorId,
        input.cancelReason().trim(),
        now,
        currentTenant(),
        id);
    event(id, "CANCELLED", actorId, "处置单已作废：" + input.cancelReason().trim());
    return orderRow(orderRecord(id));
  }

  // ================================================================ 写：索赔过程

  /** 提交索赔。 */
  @Transactional
  public DefectiveClaimRow submitClaim(UUID orderId, UUID claimId, UUID actorId) {
    Record claim = claimRecord(orderId, claimId);
    DefectiveClaimRecordStatus status =
        DefectiveClaimRecordStatus.valueOf(claim.get("status", String.class));
    if (status != DefectiveClaimRecordStatus.DRAFT) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有草稿状态的索赔能提交（当前：" + status.label() + "）");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update defective_supplier_claim set status=?, submitted_at=?::timestamptz, updated_at=?::timestamptz"
            + " where tenant_id=?::uuid and id=?::uuid",
        DefectiveClaimRecordStatus.SUBMITTED.name(),
        now,
        now,
        currentTenant(),
        claimId);
    syncClaimSummary(orderId);
    event(orderId, "CLAIM_SUBMITTED", actorId, "索赔已提交给供应商");
    return claimRow(claimRecord(orderId, claimId));
  }

  /** 供应商认可赔付金额。 */
  @Transactional
  public DefectiveClaimRow agreeClaim(
      UUID orderId, UUID claimId, DefectiveClaimAgreeInput input, UUID actorId) {
    Record claim = claimRecord(orderId, claimId);
    DefectiveClaimRecordStatus status =
        DefectiveClaimRecordStatus.valueOf(claim.get("status", String.class));
    if (status != DefectiveClaimRecordStatus.SUBMITTED
        && status != DefectiveClaimRecordStatus.ACKNOWLEDGED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有已提交的索赔能确认赔付（当前：" + status.label() + "）");
    }
    BigDecimal agreed = scaleAmount(input.agreedAmount());
    BigDecimal claimed = claim.get("claimed_amount", BigDecimal.class);
    if (agreed.compareTo(claimed) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "供应商认可的赔付额 "
              + agreed.toPlainString()
              + " 超过了登记的索赔金额 "
              + claimed.toPlainString()
              + "：登记的是我们主张的金额，认可以外的部分要另开一笔");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update defective_supplier_claim set status=?, agreed_amount=?, settled_at=?::timestamptz,"
            + " decided_reason=?, updated_at=?::timestamptz"
            + " where tenant_id=?::uuid and id=?::uuid",
        DefectiveClaimRecordStatus.AGREED.name(),
        agreed,
        now,
        input.note() == null ? "" : input.note(),
        now,
        currentTenant(),
        claimId);
    syncClaimSummary(orderId);
    event(orderId, "CLAIM_AGREED", actorId, "供应商认可赔付 " + agreed.toPlainString()
        + "（已生效冲减金额仍为 0：12 财务未交付）");
    return claimRow(claimRecord(orderId, claimId));
  }

  /** 供应商拒赔。理由必填。 */
  @Transactional
  public DefectiveClaimRow rejectClaim(
      UUID orderId, UUID claimId, DefectiveClaimReasonInput input, UUID actorId) {
    Record claim = claimRecord(orderId, claimId);
    DefectiveClaimRecordStatus status =
        DefectiveClaimRecordStatus.valueOf(claim.get("status", String.class));
    if (status != DefectiveClaimRecordStatus.SUBMITTED
        && status != DefectiveClaimRecordStatus.ACKNOWLEDGED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有已提交的索赔能拒赔（当前：" + status.label() + "）");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update defective_supplier_claim set status=?, settled_at=?::timestamptz, decided_reason=?,"
            + " updated_at=?::timestamptz where tenant_id=?::uuid and id=?::uuid",
        DefectiveClaimRecordStatus.REJECTED.name(),
        now,
        input.reason().trim(),
        now,
        currentTenant(),
        claimId);
    syncClaimSummary(orderId);
    event(orderId, "CLAIM_REJECTED", actorId, "供应商拒赔：" + input.reason().trim());
    return claimRow(claimRecord(orderId, claimId));
  }

  /** 撤回索赔。理由必填。 */
  @Transactional
  public DefectiveClaimRow withdrawClaim(
      UUID orderId, UUID claimId, DefectiveClaimReasonInput input, UUID actorId) {
    Record claim = claimRecord(orderId, claimId);
    DefectiveClaimRecordStatus status =
        DefectiveClaimRecordStatus.valueOf(claim.get("status", String.class));
    if (status != DefectiveClaimRecordStatus.DRAFT
        && status != DefectiveClaimRecordStatus.SUBMITTED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有草稿 / 已提交的索赔能撤回（当前：" + status.label() + "）");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update defective_supplier_claim set status=?, settled_at=?::timestamptz, decided_reason=?,"
            + " updated_at=?::timestamptz where tenant_id=?::uuid and id=?::uuid",
        DefectiveClaimRecordStatus.WITHDRAWN.name(),
        now,
        input.reason().trim(),
        now,
        currentTenant(),
        claimId);
    syncClaimSummary(orderId);
    event(orderId, "CLAIM_WITHDRAWN", actorId, "索赔已撤回：" + input.reason().trim());
    return claimRow(claimRecord(orderId, claimId));
  }

  // ================================================================ 汇总

  /** 汇总视图。 */
  public DefectiveSummary summary(String currencyCode) {
    UUID tenant = currentTenant();
    long orderCount = count("select count(*) from defective_disposition_order where tenant_id=?::uuid", tenant);
    long draftCount = countBy(tenant, "status", DefectiveOrderStatus.DRAFT.name());
    long decidedCount = countBy(tenant, "status", DefectiveOrderStatus.DECIDED.name());
    long executingCount = countBy(tenant, "status", DefectiveOrderStatus.EXECUTING.name());
    long completedCount = countBy(tenant, "status", DefectiveOrderStatus.COMPLETED.name());
    long cancelledCount = countBy(tenant, "status", DefectiveOrderStatus.CANCELLED.name());

    long concessionOrderCount = 0;
    long scrapOrderCount = 0;
    long repairOrderCount = 0;
    long returnExchangeOrderCount = 0;
    long promotionOrderCount = 0;
    for (Record row :
        sql.fetch(
            "select l.disposition_type, count(distinct l.order_id) as orders"
                + " from defective_disposition_line l"
                + " join defective_disposition_order o on o.tenant_id=l.tenant_id and o.id=l.order_id"
                + " where l.tenant_id=?::uuid and o.status <> 'CANCELLED'"
                + " group by l.disposition_type",
            tenant)) {
      long orders = row.get("orders", Long.class) == null ? 0L : row.get("orders", Long.class);
      switch (DefectiveDispositionType.valueOf(row.get("disposition_type", String.class))) {
        case CONCESSION -> concessionOrderCount = orders;
        case SCRAP -> scrapOrderCount = orders;
        case REPAIR -> repairOrderCount = orders;
        case RETURN_EXCHANGE -> returnExchangeOrderCount = orders;
        case PROMOTION -> promotionOrderCount = orders;
      }
    }

    BigDecimal disposed =
        sum(
            "select coalesce(sum(disposed_quantity),0) from defective_disposition_order"
                + " where tenant_id=?::uuid and status <> 'CANCELLED'",
            tenant);
    BigDecimal released =
        sum(
            "select coalesce(sum(released_quantity),0) from defective_disposition_order"
                + " where tenant_id=?::uuid and status <> 'CANCELLED'",
            tenant);
    BigDecimal pendingClaim =
        sum(
            "select coalesce(sum(claimed_amount),0) from defective_supplier_claim"
                + " where tenant_id=?::uuid and status in ('DRAFT','SUBMITTED','ACKNOWLEDGED')",
            tenant);
    BigDecimal settledClaim =
        sum(
            "select coalesce(sum(agreed_amount),0) from defective_supplier_claim"
                + " where tenant_id=?::uuid and status = 'AGREED'",
            tenant);

    long dispatched = 0;
    long unavailable = 0;
    long failed = 0;
    for (Record row :
        sql.fetch(
            "select status, count(*) as c from defective_inventory_instruction"
                + " where tenant_id=?::uuid group by status",
            tenant)) {
      long c = row.get("c", Long.class) == null ? 0L : row.get("c", Long.class);
      String status = row.get("status", String.class);
      if (DefectiveInventoryStatus.DISPATCHED.name().equals(status)) {
        dispatched = c;
      } else if (DefectiveInventoryStatus.UNAVAILABLE.name().equals(status)) {
        unavailable = c;
      } else if (DefectiveInventoryStatus.FAILED.name().equals(status)) {
        failed = c;
      }
    }
    return new DefectiveSummary(
        orderCount,
        draftCount,
        decidedCount,
        executingCount,
        completedCount,
        cancelledCount,
        concessionOrderCount,
        scrapOrderCount,
        repairOrderCount,
        returnExchangeOrderCount,
        promotionOrderCount,
        disposed,
        released,
        pendingClaim,
        settledClaim,
        currencyCode,
        dispatched,
        unavailable,
        failed,
        "金额**按币种**统计（多个币种时汇总里的金额是**分币种**给的，跨币种绝不合并）；"
            + "「已生效冲减金额」恒为 0 —— 12 财务在本仓不存在，"
            + "供应商认可了赔付也不等于已经冲减了应付。");
  }

  // ================================================================ 导出

  /**
   * 导出 CSV。
   *
   * <p>真导出：处置单 + 明细 + 库存指令 + 索赔 + 事件台账，五段都在文件里 ——
   * 只导出处置量会把「凭什么让步、货到底动没动、供应商认不认」丢掉，
   * 而那正是争议时最需要的那几行。
   */
  public byte[] exportCsv(UUID id) {
    Record order = orderRecord(id);
    StringBuilder csv = new StringBuilder();
    csv.append("处置单号,质检单号,状态,SKU,供应商,处置量,放行量,可售量上限,不合格量,币种,索赔金额\n");
    csv.append(csvCell(order.get("disposition_no", String.class)))
        .append(',')
        .append(csvCell(order.get("inspection_no", String.class)))
        .append(',')
        .append(csvCell(order.get("status", String.class)))
        .append(',')
        .append(csvCell(order.get("sku_code", String.class)))
        .append(',')
        .append(csvCell(order.get("supplier_name", String.class)))
        .append(',')
        .append(order.get("disposed_quantity", BigDecimal.class))
        .append(',')
        .append(order.get("released_quantity", BigDecimal.class))
        .append(',')
        .append(order.get("sellable_ceiling", BigDecimal.class))
        .append(',')
        .append(order.get("inspection_defective_quantity", BigDecimal.class))
        .append(',')
        .append(csvCell(order.get("currency_code", String.class)))
        .append(',')
        .append(order.get("claim_amount", BigDecimal.class))
        .append('\n');

    csv.append("\n处置明细\n");
    csv.append("处置类型,数量,放行量,让步原因,权限码,返修方,促销方案,备注\n");
    for (Record row :
        sql.fetch(
            "select * from defective_disposition_line"
                + " where tenant_id=?::uuid and order_id=?::uuid order by sort_order, id",
            currentTenant(),
            id)) {
      csv.append(csvCell(row.get("disposition_type", String.class)))
          .append(',')
          .append(row.get("quantity", BigDecimal.class))
          .append(',')
          .append(row.get("released_quantity", BigDecimal.class))
          .append(',')
          .append(csvCell(row.get("concession_reason", String.class)))
          .append(',')
          .append(csvCell(row.get("permission_code", String.class)))
          .append(',')
          .append(csvCell(row.get("repair_vendor", String.class)))
          .append(',')
          .append(csvCell(row.get("promotion_plan", String.class)))
          .append(',')
          .append(csvCell(row.get("note", String.class)))
          .append('\n');
    }

    csv.append("\n库存状态变更指令\n");
    csv.append("动作,数量,写入所有权,状态,原因,对端回执,派发时间\n");
    for (Record row :
        sql.fetch(
            "select i.* from defective_inventory_instruction i"
                + " join defective_disposition_line l"
                + "   on l.tenant_id=i.tenant_id and l.id=i.line_id"
                + " where i.tenant_id=?::uuid and i.order_id=?::uuid"
                + " order by l.sort_order, i.id",
            currentTenant(),
            id)) {
      csv.append(csvCell(row.get("action", String.class)))
          .append(',')
          .append(row.get("quantity", BigDecimal.class))
          .append(',')
          .append(csvCell(row.get("owner_module", String.class)))
          .append(',')
          .append(csvCell(row.get("status", String.class)))
          .append(',')
          .append(csvCell(row.get("reason", String.class)))
          .append(',')
          .append(csvCell(row.get("external_reference", String.class)))
          .append(',')
          .append(csvCell(row.get("dispatched_at", OffsetDateTime.class)))
          .append('\n');
    }

    csv.append("\n供应商索赔\n");
    csv.append("索赔单号,类型,币种,登记金额,供应商认可金额,已生效冲减金额,状态,财务状态,结论理由\n");
    for (Record row :
        sql.fetch(
            "select * from defective_supplier_claim"
                + " where tenant_id=?::uuid and order_id=?::uuid order by created_at, id",
            currentTenant(),
            id)) {
      csv.append(csvCell(row.get("claim_no", String.class)))
          .append(',')
          .append(csvCell(row.get("claim_type", String.class)))
          .append(',')
          .append(csvCell(row.get("currency_code", String.class)))
          .append(',')
          .append(row.get("claimed_amount", BigDecimal.class))
          .append(',')
          .append(csvCell(row.get("agreed_amount", BigDecimal.class)))
          .append(',')
          .append(row.get("effective_amount", BigDecimal.class))
          .append(',')
          .append(csvCell(row.get("status", String.class)))
          .append(',')
          .append(csvCell(row.get("finance_status", String.class)))
          .append(',')
          .append(csvCell(row.get("decided_reason", String.class)))
          .append('\n');
    }

    csv.append("\n事件台账\n");
    csv.append("事件,发生时间,操作人,说明\n");
    for (Record row :
        sql.fetch(
            "select * from defective_disposition_event"
                + " where tenant_id=?::uuid and order_id=?::uuid order by occurred_at, id",
            currentTenant(),
            id)) {
      csv.append(csvCell(row.get("event_type", String.class)))
          .append(',')
          .append(csvCell(row.get("occurred_at", OffsetDateTime.class)))
          .append(',')
          .append(csvCell(row.get("actor_id", UUID.class)))
          .append(',')
          .append(csvCell(row.get("note", String.class)))
          .append('\n');
    }
    return csv.toString().getBytes(StandardCharsets.UTF_8);
  }

  // ================================================================ 内部：写

  private void insertLines(UUID orderId, List<DefectiveLineInput> lines, UUID actorId, List<String> actorPermissions) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    int sortOrder = 0;
    for (DefectiveLineInput line : lines) {
      DefectiveDispositionType type = line.dispositionType();
      BigDecimal quantity = scale(line.quantity());
      BigDecimal released = releaseFor(type, quantity);
      if (line.releasedQuantity() != null
          && scale(line.releasedQuantity()).compareTo(released) != 0) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "放行量由处置类型决定："
                + (isReleasing(type)
                    ? type.label() + "整行都回可售池，放行量 = 该行数量 " + quantity.toPlainString()
                    : type.label() + "的货离仓或不可售，放行量只能是 0")
                + "（你传的是 " + line.releasedQuantity().toPlainString() + "）");
      }
      String concessionReason = "";
      String permissionCode = "";
      if (type == DefectiveDispositionType.CONCESSION) {
        if (line.concessionReason() == null || line.concessionReason().isBlank()) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "让步接收必须写原因：蓝图加粗句「让步接收需要权限与原因，不能由质检员随手放行」");
        }
        if (!actorPermissions.contains(CONCESSION_PERMISSION)) {
          throw new ResponseStatusException(
              HttpStatus.FORBIDDEN,
              "当前身份没有权限码 "
                  + CONCESSION_PERMISSION
                  + "，不能放行让步接收"
                  + "（这一档比「质检结论录入」trade.quality-inspection.update 高，质检员不能随手放行）");
        }
        String code =
            line.permissionCode() == null || line.permissionCode().isBlank()
                ? CONCESSION_PERMISSION
                : line.permissionCode().trim();
        if (!actorPermissions.contains(code)) {
          throw new ResponseStatusException(
              HttpStatus.FORBIDDEN,
              "当前身份没有权限码 " + code + "，不能以这个权限码放行让步接收");
        }
        concessionReason = line.concessionReason().trim();
        permissionCode = code;
      }
      LocalDate expectedReturnDate = line.expectedReturnDate();
      insert(
          "defective_disposition_line",
          List.of(
              "id", "tenant_id", "order_id", "disposition_type", "sort_order", "quantity",
              "released_quantity", "concession_reason", "permission_code", "expected_return_date",
              "repair_vendor", "promotion_plan", "note", "created_by", "created_at"),
          List.of(
              "?::uuid", "?::uuid", "?::uuid", "?::varchar", "?", "?", "?", "?::varchar", "?::varchar",
              "?::date", "?::varchar", "?::varchar", "?::varchar", "?::uuid", "?::timestamptz"),
          Arrays.asList(
              (Object) UUID.randomUUID(),
              currentTenant(),
              orderId,
              type.name(),
              sortOrder,
              quantity,
              released,
              concessionReason,
              permissionCode,
              expectedReturnDate,
              line.repairVendor(),
              line.promotionPlan(),
              line.note() == null ? "" : line.note(),
              actorId,
              now));
      sortOrder++;
    }
  }

  /** 索赔金额：调用方登记了就用登记的；没登记就按质检单的单价快照 × 处置量推导。 */
  private BigDecimal resolveClaimAmount(Record order, List<Record> lines) {
    BigDecimal registered = order.get("claim_amount", BigDecimal.class);
    if (registered != null && registered.signum() > 0) {
      return registered;
    }
    BigDecimal unitPrice = order.get("unit_price", BigDecimal.class);
    BigDecimal disposed = order.get("disposed_quantity", BigDecimal.class);
    if (unitPrice == null || unitPrice.signum() <= 0 || disposed == null || disposed.signum() <= 0) {
      return BigDecimal.ZERO.setScale(AMOUNT_SCALE, java.math.RoundingMode.HALF_UP);
    }
    // 单价快照来自质检单（6.4.2.1），不认客户端另报的价。
    return scaleAmount(unitPrice.multiply(disposed));
  }

  private DefectiveDispositionType firstType(List<Record> lines) {
    return lines.isEmpty()
        ? DefectiveDispositionType.SCRAP
        : DefectiveDispositionType.valueOf(lines.get(0).get("disposition_type", String.class));
  }

  /** 把索赔状态回写到处置单的头上。 */
  private void syncClaimSummary(UUID orderId) {
    List<Record> rows =
        sql.fetch(
            "select status, count(*) as c from defective_supplier_claim"
                + " where tenant_id=?::uuid and order_id=?::uuid group by status",
            currentTenant(),
            orderId);
    boolean any = false;
    boolean allSettled = true;
    boolean anyRejected = false;
    boolean anyWithdrawn = false;
    boolean anySubmitted = false;
    for (Record row : rows) {
      any = true;
      String status = row.get("status", String.class);
      if (DefectiveClaimRecordStatus.AGREED.name().equals(status)) {
        continue;
      }
      allSettled = false;
      if (DefectiveClaimRecordStatus.REJECTED.name().equals(status)) {
        anyRejected = true;
      }
      if (DefectiveClaimRecordStatus.WITHDRAWN.name().equals(status)) {
        anyWithdrawn = true;
      }
      if (DefectiveClaimRecordStatus.SUBMITTED.name().equals(status)
          || DefectiveClaimRecordStatus.ACKNOWLEDGED.name().equals(status)) {
        anySubmitted = true;
      }
    }
    DefectiveClaimStatus summary;
    if (!any) {
      summary = DefectiveClaimStatus.NONE;
    } else if (allSettled) {
      summary = DefectiveClaimStatus.SETTLED;
    } else if (anyRejected && !anySubmitted) {
      summary = DefectiveClaimStatus.REJECTED;
    } else if (anyWithdrawn && !anySubmitted) {
      summary = DefectiveClaimStatus.WITHDRAWN;
    } else if (anySubmitted) {
      summary = DefectiveClaimStatus.SUBMITTED;
    } else {
      summary = DefectiveClaimStatus.PENDING;
    }
    sql.execute(
        "update defective_disposition_order set claim_status=?, updated_at=?::timestamptz,"
            + " version=version+1 where tenant_id=?::uuid and id=?::uuid",
        summary.name(),
        OffsetDateTime.now(ZoneOffset.UTC),
        currentTenant(),
        orderId);
  }

  private void event(UUID orderId, String eventType, UUID actorId, String note) {
    insert(
        "defective_disposition_event",
        List.of("id", "tenant_id", "order_id", "event_type", "occurred_at", "actor_id", "note", "created_at"),
        List.of(
            "?::uuid", "?::uuid", "?::uuid", "?::varchar", "?::timestamptz", "?::uuid", "?::varchar",
            "?::timestamptz"),
        Arrays.asList(
            (Object) UUID.randomUUID(),
            currentTenant(),
            orderId,
            eventType,
            OffsetDateTime.now(ZoneOffset.UTC),
            actorId,
            note == null ? "" : note,
            OffsetDateTime.now(ZoneOffset.UTC)));
  }

  private void insert(String table, List<String> columns, List<String> casts, List<Object> values) {
    if (columns.size() != casts.size() || columns.size() != values.size()) {
      throw new IllegalStateException(
          "INSERT "
              + table
              + " 的列/占位符/参数数量对不上："
              + columns.size()
              + "/"
              + casts.size()
              + "/"
              + values.size());
    }
    String statement =
        "insert into "
            + table
            + "("
            + String.join(",", columns)
            + ") values ("
            + String.join(",", casts)
            + ")";
    sql.execute(statement, values.toArray());
  }

  // ================================================================ 内部：读

  /** 质检单（6.4.2.1）—— 处置结论的唯一来源。 */
  private Record inspectionRow(UUID inspectionId) {
    Record row =
        sql.fetchOne(
            "select o.* from quality_inspection_order o"
                + " where o.tenant_id=?::uuid and o.id=?::uuid",
            currentTenant(),
            inspectionId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "质检单不存在或不属于当前租户：" + inspectionId);
    }
    return row;
  }

  /** 质检结论的上下文（全部从质检单回读）。 */
  private DefectiveInspectionContext inspectionContext(Record inspection) {
    String status = inspection.get("status", String.class);
    if (!"COMPLETED".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "质检单还没完结（当前状态 " + status + "），没有结论就没有可处置的不良品");
    }
    BigDecimal defective = inspection.get("defective_quantity", BigDecimal.class);
    if (defective == null || defective.signum() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "这张质检单的不合格量为 0，没有不良品可处置");
    }
    String result = inspection.get("result", String.class);
    if (result == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这张质检单没有质检结论，无法处置");
    }
    return new DefectiveInspectionContext(
        inspection.get("id", UUID.class),
        inspection.get("inspection_no", String.class),
        result,
        inspection.get("sku_id", UUID.class),
        inspection.get("sku_code", String.class),
        inspection.get("sku_name", String.class),
        inspection.get("supplier_id", UUID.class),
        inspection.get("supplier_name", String.class),
        inspection.get("receipt_code", String.class),
        inspection.get("batch_no", String.class),
        inspection.get("lot_size", BigDecimal.class),
        defective,
        inspection.get("qualified_quantity", BigDecimal.class),
        inspection.get("concession_quantity", BigDecimal.class),
        inspection.get("gross_deducted_quantity", BigDecimal.class),
        inspection.get("currency_code", String.class),
        inspection.get("unit_price", BigDecimal.class));
  }

  /** 质检结论的上下文（内部载体）。 */
  private record DefectiveInspectionContext(
      UUID inspectionId,
      String inspectionNo,
      String result,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID supplierId,
      String supplierName,
      String receiptCode,
      String batchNo,
      BigDecimal lotSize,
      BigDecimal defective,
      BigDecimal qualified,
      BigDecimal concession,
      BigDecimal grossDeducted,
      String currencyCode,
      BigDecimal unitPrice) {}

  /** 同一张质检单下**其它**处置单已处置的量（作废的不计入）。 */
  private BigDecimal disposedAcross(UUID inspectionId) {
    return sql.fetchOne(
            "select coalesce(sum(disposed_quantity),0) from defective_disposition_order"
                + " where tenant_id=?::uuid and inspection_order_id=?::uuid"
                + " and status <> 'CANCELLED'",
            currentTenant(),
            inspectionId)
        .get(0, BigDecimal.class);
  }

  private Record orderRecord(UUID id) {
    Record row =
        sql.fetchOne(
            ORDER_SELECT + " where o.tenant_id=?::uuid and o.id=?::uuid", currentTenant(), id);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "处置单不存在或不属于当前租户：" + id);
    }
    return row;
  }

  private Record claimRecord(UUID orderId, UUID claimId) {
    Record row =
        sql.fetchOne(
            "select * from defective_supplier_claim"
                + " where tenant_id=?::uuid and order_id=?::uuid and id=?::uuid",
            currentTenant(),
            orderId,
            claimId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "索赔记录不存在或不属于这张处置单：" + claimId);
    }
    return row;
  }

  private long count(String sqlText, UUID tenant) {
    return sql.fetchOne(sqlText, tenant).get(0, Long.class);
  }

  private long countBy(UUID tenant, String column, String value) {
    return sql.fetchOne(
            "select count(*) from defective_disposition_order where tenant_id=?::uuid and "
                + column
                + " = ?",
            tenant,
            value)
        .get(0, Long.class);
  }

  private BigDecimal sum(String sqlText, UUID tenant) {
    return sql.fetchOne(sqlText, tenant).get(0, BigDecimal.class);
  }

  // ================================================================ 内部：行映射

  private DefectiveOrderRow orderRow(Record row) {
    DefectiveOrderStatus status = DefectiveOrderStatus.valueOf(row.get("status", String.class));
    DefectiveInventoryStatus inventoryStatus =
        DefectiveInventoryStatus.valueOf(row.get("inventory_status", String.class));
    DefectiveClaimStatus claimStatus =
        DefectiveClaimStatus.valueOf(row.get("claim_status", String.class));
    BigDecimal defective = row.get("inspection_defective_quantity", BigDecimal.class);
    BigDecimal disposed = row.get("disposed_quantity", BigDecimal.class);
    long lineCount = row.get("line_count", Long.class) == null ? 0L : row.get("line_count", Long.class);
    long instructionCount =
        row.get("instruction_count", Long.class) == null ? 0L : row.get("instruction_count", Long.class);
    long claimCount = row.get("claim_count", Long.class) == null ? 0L : row.get("claim_count", Long.class);
    return new DefectiveOrderRow(
        row.get("id", UUID.class),
        row.get("disposition_no", String.class),
        status.name(),
        status.label(),
        row.get("inspection_order_id", UUID.class),
        row.get("inspection_no", String.class),
        row.get("inspection_result", String.class),
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("sku_name", String.class),
        row.get("supplier_id", UUID.class),
        row.get("supplier_name", String.class),
        row.get("receipt_code", String.class),
        row.get("batch_no", String.class),
        row.get("inspection_lot_size", BigDecimal.class),
        defective,
        row.get("inspection_qualified_quantity", BigDecimal.class),
        row.get("inspection_concession_quantity", BigDecimal.class),
        row.get("sellable_ceiling", BigDecimal.class),
        row.get("gross_deducted_quantity", BigDecimal.class),
        disposed,
        row.get("released_quantity", BigDecimal.class),
        remainingDefective(defective, disposed),
        row.get("disposition_types", String.class) == null ? "" : row.get("disposition_types", String.class),
        row.get("disposition_summary_label", String.class) == null
            ? ""
            : row.get("disposition_summary_label", String.class),
        inventoryStatus.name(),
        inventoryStatus.label(),
        claimStatus.name(),
        claimStatus.label(),
        row.get("currency_code", String.class),
        row.get("claim_amount", BigDecimal.class),
        lineCount,
        instructionCount,
        claimCount,
        row.get("decided_by", UUID.class),
        row.get("decided_at", OffsetDateTime.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("completed_at", OffsetDateTime.class));
  }

  private DefectiveLineRow lineRow(Record row) {
    DefectiveDispositionType type =
        DefectiveDispositionType.valueOf(row.get("disposition_type", String.class));
    return new DefectiveLineRow(
        row.get("id", UUID.class),
        type.name(),
        type.label(),
        row.get("quantity", BigDecimal.class),
        row.get("released_quantity", BigDecimal.class),
        row.get("concession_reason", String.class),
        row.get("permission_code", String.class),
        row.get("expected_return_date", LocalDate.class),
        row.get("repair_vendor", String.class),
        row.get("promotion_plan", String.class),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private DefectiveInstructionRow instructionRow(Record row) {
    DefectiveInventoryAction action =
        DefectiveInventoryAction.valueOf(row.get("action", String.class));
    DefectiveInventoryStatus status =
        DefectiveInventoryStatus.valueOf(row.get("status", String.class));
    return new DefectiveInstructionRow(
        row.get("id", UUID.class),
        row.get("line_id", UUID.class),
        action.name(),
        action.label(),
        row.get("quantity", BigDecimal.class),
        row.get("owner_module", String.class),
        status.name(),
        status.label(),
        row.get("reason", String.class),
        row.get("external_reference", String.class),
        row.get("attempt_count", Integer.class) == null ? 0 : row.get("attempt_count", Integer.class),
        row.get("dispatched_at", OffsetDateTime.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private DefectiveClaimRow claimRow(Record row) {
    DefectiveClaimType type = DefectiveClaimType.valueOf(row.get("claim_type", String.class));
    DefectiveClaimRecordStatus status =
        DefectiveClaimRecordStatus.valueOf(row.get("status", String.class));
    return new DefectiveClaimRow(
        row.get("id", UUID.class),
        row.get("claim_no", String.class),
        type.name(),
        type.label(),
        row.get("supplier_id", UUID.class),
        row.get("supplier_name", String.class),
        row.get("currency_code", String.class),
        row.get("claimed_amount", BigDecimal.class),
        row.get("agreed_amount", BigDecimal.class),
        row.get("effective_amount", BigDecimal.class),
        status.name(),
        status.label(),
        row.get("finance_status", String.class),
        "待财务处理（12 财务未交付）",
        row.get("finance_reference", String.class),
        row.get("submitted_at", OffsetDateTime.class),
        row.get("acknowledged_at", OffsetDateTime.class),
        row.get("settled_at", OffsetDateTime.class),
        row.get("decided_reason", String.class),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private DefectiveEventRow eventRow(Record row) {
    return new DefectiveEventRow(
        row.get("id", UUID.class),
        row.get("event_type", String.class),
        eventTypeLabel(row.get("event_type", String.class)),
        row.get("occurred_at", OffsetDateTime.class),
        row.get("actor_id", UUID.class),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private List<String> availableActions(String status) {
    List<String> actions = new ArrayList<>();
    DefectiveOrderStatus state = DefectiveOrderStatus.valueOf(status);
    if (state == DefectiveOrderStatus.DRAFT) {
      actions.add("UPDATE");
      actions.add("DECIDE");
      actions.add("CANCEL");
    } else if (state == DefectiveOrderStatus.DECIDED || state == DefectiveOrderStatus.EXECUTING) {
      actions.add("DISPATCH_INVENTORY");
      actions.add("COMPLETE");
      actions.add("CANCEL");
    }
    return actions;
  }

  // ================================================================ 小工具

  /** 当前租户（由 RLS 事务绑定，绝不来自客户端头）。 */
  private UUID currentTenant() {
    var row = sql.fetchOne("select nullif(current_setting('app.current_tenant', true), '')::uuid as t");
    UUID tenant = row == null ? null : row.get("t", UUID.class);
    if (tenant == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    return tenant;
  }

  private static int pageSize(int size) {
    return Math.min(Math.max(size, 1), 200);
  }

  /**
   * 绑定值数组。
   *
   * <p>用法：条件片段与绑定值**必须一起**加 —— 少加一段条件却留着它的绑定值，
   * jOOQ 会把值绑到别的 {@code ?} 上，**而且不报错**。
   */
  private static Object[] argsOf(List<Object> args, Object... extra) {
    List<Object> all = new ArrayList<>(args);
    all.addAll(List.of(extra));
    return all.toArray();
  }

  private static String csvCell(Object value) {
    if (value == null) {
      return "";
    }
    String text = String.valueOf(value).replace("\r", " ").replace("\n", " ");
    if (text.contains(",") || text.contains("\"")) {
      return "\"" + text.replace("\"", "\"\"") + "\"";
    }
    return text;
  }

  private static String eventTypeLabel(String type) {
    return switch (type) {
      case "CREATED" -> "已创建";
      case "UPDATED" -> "已修改";
      case "DECIDED" -> "已定稿";
      case "INVENTORY_DISPATCHED" -> "库存指令已登记";
      case "INVENTORY_UNAVAILABLE" -> "库存指令无处可发";
      case "INVENTORY_FAILED" -> "库存指令派发失败";
      case "CLAIM_SUBMITTED" -> "索赔已提交";
      case "CLAIM_AGREED" -> "索赔已达成共识";
      case "CLAIM_REJECTED" -> "索赔被拒";
      case "CLAIM_WITHDRAWN" -> "索赔已撤回";
      case "COMPLETED" -> "已完成";
      case "CANCELLED" -> "已作废";
      default -> type;
    };
  }

  private static List<String> dispositionTypeCodes() {
    List<String> values = new ArrayList<>();
    for (DefectiveDispositionType type : DefectiveDispositionType.values()) {
      values.add(type.name());
    }
    return values;
  }

  private static List<String> dispositionTypeLabels() {
    List<String> values = new ArrayList<>();
    for (DefectiveDispositionType type : DefectiveDispositionType.values()) {
      values.add(type.label());
    }
    return values;
  }

  private static List<String> orderStatusCodes() {
    List<String> values = new ArrayList<>();
    for (DefectiveOrderStatus status : DefectiveOrderStatus.values()) {
      values.add(status.name());
    }
    return values;
  }

  private static List<String> orderStatusLabels() {
    List<String> values = new ArrayList<>();
    for (DefectiveOrderStatus status : DefectiveOrderStatus.values()) {
      values.add(status.label());
    }
    return values;
  }

  private static List<String> inventoryStatusCodes() {
    List<String> values = new ArrayList<>();
    for (DefectiveInventoryStatus status : DefectiveInventoryStatus.values()) {
      values.add(status.name());
    }
    return values;
  }

  private static List<String> inventoryStatusLabels() {
    List<String> values = new ArrayList<>();
    for (DefectiveInventoryStatus status : DefectiveInventoryStatus.values()) {
      values.add(status.label());
    }
    return values;
  }

  private static List<String> claimStatusCodes() {
    List<String> values = new ArrayList<>();
    for (DefectiveClaimStatus status : DefectiveClaimStatus.values()) {
      values.add(status.name());
    }
    return values;
  }

  private static List<String> claimStatusLabels() {
    List<String> values = new ArrayList<>();
    for (DefectiveClaimStatus status : DefectiveClaimStatus.values()) {
      values.add(status.label());
    }
    return values;
  }
}
