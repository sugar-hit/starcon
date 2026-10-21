package com.starcon.trade;

import static com.starcon.trade.PurchaseReconciliationModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.5.1.1 采购对账。
 *
 * <p><b>本类是 V250 五张新表的写入所有者</b>（purchase_reconciliation /
 * purchase_reconciliation_line / purchase_reconciliation_discrepancy /
 * purchase_reconciliation_confirmation / purchase_reconciliation_event），
 * 对 6.3.2.2 的 {@code purchase_arrival_tracking} / {@code purchase_arrival_receipt}
 * 与 11.2.3.3 的 {@code supplier_claim} / {@code supplier_claim_item} <b>只读</b>。
 * 刻意不加对外外键、不 ALTER 既有表 ⇒ 跨车道 DDL 回归面 = 0。
 *
 * <p><b>六条硬纪律</b>：
 *
 * <ol>
 *   <li><b>对账基数只取自已交付事实</b>：应收取 6.3.2.2 到货流水（同库只读），
 *       索赔取 11.2.3.3（同库只读）。<b>不建收货影子表、不建采购单影子表</b> ——
 *       6.3.1.x 采购单未交付，对账基数不以它为准。</li>
 *   <li><b>采购侧不确认应付</b>：本模块<b>没有</b>「应付已确认 / 已付款」的状态、
 *       字段与端点。汇总里的 {@code suggestedPayableAmount} 是<b>建议</b>数，
 *       交接给 12 财务的载体就是这张对账单。</li>
 *   <li><b>质检扣量未接线就写「未接线」</b>（6.4.2.1 与本模块同批并行开发，此刻不存在）：
 *       明细行标 {@code qualityDeductionStatus='UNAVAILABLE'}、
 *       {@code qualityDeductionQuantity=NULL}（<b>不是 0</b>），并写明影响。</li>
 *   <li><b>币种 + 汇率快照 + 单位换算必录</b>：跨币种不给汇率 ⇒ 400，
 *       DB 触发器另行拒收「币种不同却按 1:1 折算」的行。</li>
 *   <li><b>差异成行可查</b>：与供应商回执不一致的差异<b>逐条</b>落一行
 *       （差在哪个字段、我方值、供应商值、差额），可挂起（HELD）后处理。</li>
 *   <li><b>期间由服务端裁决</b>：月度 = 某月 1 号到月末；季度 = 季首月 1 号到季末月末。
 *       调用方给的期间对不上 ⇒ 400，<b>不悄悄改写</b>。</li>
 * </ol>
 */
@Service
public class PurchaseReconciliationService {

  /** 列表单页上限。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 金额刻度：4 位（与 sales_order_line.unit_price、supplier_claim 同刻度）。 */
  private static final int MONEY_SCALE = 4;

  /** 数量刻度：6 位（AGENTS.md：数量 NUMERIC(19,6)）。 */
  private static final int QTY_SCALE = 6;

  /** 汇率刻度：8 位（汇率是小数点后很多位的东西，4 位会把它砍成另一个汇率）。 */
  private static final int FX_SCALE = 8;

  /**
   * 期间边界所用的时区。
   *
   * <p>⚠️ 对账期间是**业务日历**，而本仓当前没有「租户时区」这个事实（catalog / identity
   * 都没有登记），所以这里**显式钉死为 UTC** 并在口径说明里写明 ——
   * 留一个「按服务器本地时区算」的隐式行为，只会让同一张对账单在不同机器上算出不同的期间。
   */
  private static final ZoneOffset PERIOD_ZONE = ZoneOffset.UTC;

  private final DSLContext sql;
  private final QualityDeductionPort quality;

  public PurchaseReconciliationService(DSLContext sql, QualityDeductionPort quality) {
    this.sql = sql;
    this.quality = quality;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句的每个动词逐块报可用性，下游逐个报 delivered=false 与归属模块号、
   * 本模块<b>刻意不做</b>的事、三条硬规则与状态机的口径。
   *
   * <p>让界面能说清「为什么这行没有质检扣量」「为什么这张单没有应付确认状态」，
   * 而不是让它们显示成空白让人以为「这些能力没做」。
   */
  public ReconciliationCapabilities capabilities() {
    return new ReconciliationCapabilities(
        List.of(
            "到货流水 purchase_arrival_receipt ⋈ purchase_arrival_tracking（V235，6.3.2.2，同库只读）"
                + " —— **应收数量、单价、币种的唯一来源**",
            "供应商索赔 supplier_claim ⋈ supplier_claim_item（V188，11.2.3.3，同库只读）"
                + " —— **索赔金额的唯一来源**（不建影子表）",
            "对账单 purchase_reconciliation + 明细 purchase_reconciliation_line（V250）",
            "差异清单 purchase_reconciliation_discrepancy（V250，追加式，可挂起）",
            "供应商确认回执 purchase_reconciliation_confirmation（V250，追加式）",
            "对账留痕 purchase_reconciliation_event（V250，追加式，运行角色 UPDATE/DELETE 已收回）",
            "质检扣量 —— 走只读端口 QualityDeductionPort，**当前未接线**（6.4.2.1 未交付）"),
        List.of(
            section(
                "collectBasis",
                "按期间汇总基数（收货 + 索赔）",
                true,
                null,
                "应收基数取 **6.3.2.2 到货流水**（已交付，同库只读）："
                    + "数量 = 本次到货量，单价与币种 = 到货跟踪单上的快照。"
                    + "索赔基数取 **11.2.3.3 供应商索赔**（已交付，同库只读）："
                    + "按索赔明细逐行计入，币种取索赔行自己的币种。",
                "purchase_reconciliation_line"),
            section(
                "unitPrice",
                "单价",
                true,
                null,
                "单价**不由调用方给**：已到货应收行的单价与币种取自 6.3.2.2 到货跟踪单"
                    + "（那是采购登记到货时录下的采购单价快照），"
                    + "索赔行的金额取自索赔明细。⇒ 传输层没有「单价」入参。",
                "purchase_reconciliation_line"),
            section(
                "unitConversion",
                "单位换算",
                true,
                null,
                "每行带 `quantity` / `unit_code` / `unit_conversion`，"
                    + "基础单位数量 `base_quantity` 由 DB 触发器算（数量 × 换算比例）。"
                    + "⚠️ 到货流水的到货量与到货跟踪单的单价**同单位**"
                    + "（V235 的金额算式 unit_price × arrived_qty 即为凭据），"
                    + "故该批行的换算比例恒为 1 且换算基数与计价单位一致 —— "
                    + "记 1 是**口径**，不是把两个不同单位硬凑成一样。",
                "purchase_reconciliation_line + V250 触发器"),
            section(
                "deduction",
                "代扣代缴费用",
                true,
                null,
                "采购登记的费用行：类型（代扣代缴税费 / 代扣 / 代付运费 / 折让 / 其它）"
                    + "+ 金额 + 币种 + 汇率快照。⚠️ 币种与对账单币种不同而没给汇率 ⇒ 400"
                    + "（跨币种绝不按 1:1 折算）。",
                "purchase_reconciliation_line"),
            section(
                "claim",
                "索赔冲减",
                true,
                null,
                "索赔取 **trade 本库 11.2.3.3 的既有事实**（`supplier_claim_item`），"
                    + "**不建影子表**。只计已提交 / 协商中 / 已达成 / 已结算四种状态的索赔 —— "
                    + "草稿、已驳回、已撤回的索赔不是「已向供应商主张过」。",
                "purchase_reconciliation_line + supplier_claim_item"),
            section(
                "qualityDeduction",
                "质检扣量",
                false,
                "6.4.2.1",
                "**未交付**（与本模块同批并行开发，此刻在本仓不存在：既没有 /purchase/quality 端点，"
                    + "也没有质检扣量的事实表）。⇒ 走只读端口 `QualityDeductionPort`，"
                    + "**当前未接线**；明细行显式标 `qualityDeductionStatus='UNAVAILABLE'`、"
                    + "`qualityDeductionQuantity=NULL`，**绝不按 0 计入**。"
                    + "⚠️ 影响：本批次的应收数量**尚未扣除**质检不合格量，"
                    + "对账结论要在这条前提下读。",
                null),
            section(
                "sendToSupplier",
                "生成对账单发给供应商确认",
                true,
                null,
                "对账单草稿 → 发出（DRAFT → SENT）。发出的是**对账单本身**"
                    + "（明细 + 汇总 + 期间 + 币种），CSV 真导出。",
                "purchase_reconciliation.status"),
            section(
                "discrepancy",
                "差异挂起",
                true,
                null,
                "与供应商回执不一致的差异**逐条**成行：差在哪个字段、我方值、供应商值、差额；"
                    + "差异可**挂起**（HELD）后再处理，也可由采购主动追加。",
                "purchase_reconciliation_discrepancy"),
            section(
                "supplierConfirmation",
                "供应商确认回执",
                true,
                null,
                "回执里带**逐行**供应商值（数量 / 单价 / 金额）⇒ 差异由行对行比出来，"
                    + "而不是只比一个总额。回执是追加式的证据链。",
                "purchase_reconciliation_confirmation"),
            section(
                "payableHandoff",
                "应付核对与付款（12 财务）",
                false,
                "12 财务",
                "**finance 服务当前不存在**（backend/services/ 下只有 identity / catalog / "
                    + "channel / warehouse / trade / crm）⇒ 本模块**不确认应付、不谈付款**："
                    + "汇总里的 `suggestedPayableAmount` 是**采购侧建议数**，"
                    + "`financeHandoffStatus` 恒为 `PENDING_FINANCE_SERVICE`（表级 CHECK 只允许这一个值）。",
                null)),
        List.of(
            related(
                "MODULE_6_3_2_2",
                "6.3.2.2 到货与逾期",
                true,
                null,
                "已交付（V235，同库只读）：**对账应收基数的唯一来源** —— 到货流水给出到货量，"
                    + "到货跟踪单给出单价与币种。",
                "/purchase/arrivals"),
            related(
                "MODULE_11_2_3_3",
                "11.2.3.3 供应商索赔",
                true,
                null,
                "已交付（V188，同库只读）：**索赔基数的唯一来源** —— 按索赔明细逐行计入。",
                "/aftersales/returns/supplier-claims"),
            related(
                "MODULE_6_4_2_1",
                "6.4.2.1 质检单",
                false,
                "6.4.2.1",
                "**未交付**（同批并行开发）⇒ 质检扣量未接线，明细行显式标 UNAVAILABLE。",
                "/purchase/quality"),
            related(
                "MODULE_6_3_1",
                "6.3.1 采购单 / 6.3.1.3 采购单审批",
                false,
                "6.3.1",
                "**未交付** ⇒ 对账基数**不以采购单为准**：应收基数取已到货事实（6.3.2.2），"
                    + "本模块也不为采购单建影子表。",
                null),
            related(
                "MODULE_12_FINANCE",
                "12 财务（应付核对与付款）",
                false,
                "12 财务",
                "**未交付且服务不存在** ⇒ 对账单是采购与财务之间的**交接物**；"
                    + "采购侧不确认应付，等 finance 落地后经授权 API / event 衔接。",
                null),
            related(
                "MODULE_8_2_1_1",
                "8.2.1.1 收货单",
                false,
                "8.2.1.1",
                "已交付但**不接受 PURCHASE 来源**（warehouse 侧只有 TRANSFER 来源可用，"
                    + "因为 6.3.1 采购单未交付）⇒ 采购收货**不会**在 warehouse 侧产生收货单。"
                    + "本模块的收货事实取自 6.3.2.2 到货流水（trade 本库），"
                    + "并把 warehouse 收货单号作为**来源快照**带在明细行上。",
                null),
            related(
                "MODULE_1_CATALOG",
                "1 商品中心（供应商主数据）",
                false,
                "1 商品中心 / 10 接口",
                "供应商主数据在 catalog 服务的独立数据库里，跨库读不到 ⇒ "
                    + "本模块只存供应商 id + 名称快照（随到货跟踪单带过来），不建跨库 FK。",
                null)),
        List.of(
            action(
                "CREATE",
                "发起对账（按期间 + 供应商）",
                true,
                "POST",
                "/trade/purchase-reconciliations",
                false,
                "期间类型与起始日必须自洽；跨币种必须提供汇率快照",
                null,
                null,
                null),
            action(
                "ADD_DEDUCTION",
                "登记代扣代缴费用",
                true,
                "POST",
                "/trade/purchase-reconciliations/{id}/deductions",
                false,
                "仅草稿；跨币种必须提供汇率快照",
                null,
                null,
                null),
            action(
                "SEND",
                "发给供应商确认",
                true,
                "POST",
                "/trade/purchase-reconciliations/{id}/send",
                false,
                "仅草稿，且至少一条明细行",
                null,
                null,
                null),
            action(
                "RECORD_CONFIRMATION",
                "录入供应商确认回执",
                true,
                "POST",
                "/trade/purchase-reconciliations/{id}/confirmations",
                false,
                "仅已发送 / 有差异待处理；回执带逐行供应商值",
                null,
                null,
                null),
            action(
                "RESOLVE_DISCREPANCY",
                "处理差异（挂起 / 解决）",
                true,
                "POST",
                "/trade/purchase-reconciliations/{id}/discrepancies/{discrepancyId}/resolve",
                false,
                "解决必须写方式与说明；挂起不需要",
                null,
                null,
                null),
            action(
                "REOPEN",
                "重开对账（追加差异）",
                true,
                "POST",
                "/trade/purchase-reconciliations/{id}/reopen",
                false,
                "仅未关闭；必须写重开理由",
                null,
                null,
                null),
            action(
                "CLOSE",
                "关闭对账单",
                true,
                "POST",
                "/trade/purchase-reconciliations/{id}/close",
                false,
                "仅已确认",
                null,
                null,
                null),
            action(
                "CONFIRM_PAYABLE",
                "确认应付",
                false,
                null,
                null,
                true,
                null,
                null,
                "12 财务",
                "**刻意不提供**：蓝图加粗句「**对账由采购发起、12 财务执行付款**，"
                    + "两侧数据以对账单为交接点，避免采购擅自确认应付」。"
                    + "采购侧一旦有了「确认应付」的入口，双方数据就不再以对账单为唯一交接点。"),
            action(
                "PAY",
                "付款 / 查付款状态",
                false,
                null,
                null,
                true,
                null,
                null,
                "12 财务",
                "**刻意不提供**：finance 服务不存在，付款是 12 财务的执行动作。"),
            action(
                "QUALITY_DEDUCTION",
                "取质检扣量",
                false,
                null,
                null,
                true,
                null,
                null,
                "6.4.2.1",
                "**刻意未接线**：质检单与本模块同批并行开发，此刻在本仓不存在。"
                    + "本模块**不按 0 计入**质检扣量 —— 明细行显式标 UNAVAILABLE。"),
            action(
                "RECEIVE_PURCHASE",
                "登记采购收货单",
                false,
                null,
                null,
                true,
                null,
                null,
                "8.2.1.1 / 6.3.1",
                "**刻意不提供**：采购收货由 6.3.2.2 到货登记落地，"
                    + "warehouse 的 8.2.1.1 当前只接受 TRANSFER 来源（不接受 PURCHASE）——"
                    + "本模块不代 warehouse 收货，也不建收货影子表。")),
        List.of(
            boundary(
                "没有「确认应付 / 已付款」的状态、字段与端点",
                "12 财务",
                "对账由采购发起、12 财务执行付款。采购侧一旦能确认应付，"
                    + "双方数据就不再以对账单为唯一交接点。"),
            boundary(
                "传输层没有单价、没有应收金额、没有汇总字段",
                null,
                "单价取自 6.3.2.2 到货跟踪单，汇总由 DB 触发器从明细行重算 —— "
                    + "可填的金额等于把财务口径交给每个调用方自己编。"),
            boundary(
                "不建收货影子表、不建采购单影子表、不建跨服务外键或视图",
                "6.3.1 / 1 商品中心",
                "每张事实表只有一个写入所有者；跨服务只允许授权 API / event。"),
            boundary(
                "质检扣量未接线时写 NULL 而不是 0",
                "6.4.2.1",
                "0 会被读成「这批没有扣量」，据此付全款是最贵的一种错账。"),
            boundary(
                "关闭后的对账单不可重开；差异只能挂起或解决，不能删除",
                null,
                "对账单是要拿去和供应商对质的凭据；差异要么处理掉，要么挂着，不能删掉当没发生。")),
        "DRAFT → SENT → DISPUTED（供应商有差异）/ CONFIRMED（供应商确认）→ CLOSED（终态）。"
            + "重开（REOPEN）可把 SENT / DISPUTED / CONFIRMED 退回 DRAFT 追加差异。",
        "对账基数取自**已交付事实**：应收 = 6.3.2.2 到货流水（V235，同库只读），"
            + "索赔 = 11.2.3.3 供应商索赔明细（V188，同库只读），"
            + "代扣代缴费用 = 采购登记。⚠️ 6.3.1.x 采购单未交付 ⇒ **不以采购单为基数**；"
            + "8.2.1.1 收货单已交付但**不接受 PURCHASE 来源**（warehouse 侧只有 TRANSFER 可用）"
            + "⇒ 采购收货事实取自 6.3.2.2，warehouse 收货单号只作为来源快照带在明细行上。",
        "**采购侧不确认应付**：本模块没有「应付已确认 / 已付款」的状态与端点，"
            + "汇总里的 `suggestedPayableAmount` 是采购侧**建议**数（名字里的 Suggested 是刻意的），"
            + "交接给 12 财务的载体就是这张对账单。12 财务服务不存在 ⇒ "
            + "`financeHandoffStatus` 恒为 `PENDING_FINANCE_SERVICE`。",
        "差异**逐条成行**：差在哪个字段（数量 / 单价 / 金额 / 我方有对方没列 / 对方有我方没列）、"
            + "我方值、供应商值、差额各占一列；差异可**挂起**（HELD）后处理，"
            + "不聚合成一个差额总数 —— 聚合了就答不出「差在哪一行」。",
        "**币种 + 汇率快照 + 单位换算必录**：每行带 currency / fx_rate / fx_rate_source / fx_rate_at，"
            + "折算金额由 DB 触发器算（amount × fx_rate）；跨币种不给汇率 ⇒ 400，"
            + "DB 侧另行拒收「币种不同却按 1:1 折算」的行（fx_rate_source 必填）。"
            + "每行还带 quantity / unit_code / unit_conversion，基础单位数量由 DB 算。"
            + "NUMERIC(19,4) + BigDecimal，**禁浮点**。",
        "质检扣量（6.4.2.1）**未交付** ⇒ 明细行 `qualityDeductionStatus='UNAVAILABLE'`、"
            + "`qualityDeductionQuantity=NULL`（**不是 0**），并写明影响："
            + "本批次应收数量尚未扣除质检不合格量。");
  }

  // ================================================================ 下拉项

  /**
   * 下拉项：供应商（取自已交付事实里的真实供应商）、期间预设、币种、代扣代缴类型。
   *
   * <p>⚠️ 供应商下拉<b>不是</b>从 catalog 拉的（跨服务要走授权端口，而本模块不需要它），
   * 而是取自 <b>trade 本库已交付事实</b>里出现过的供应商 —— 那些才是真的能与采购对账的对象。
   */
  public ReconciliationOptions options() {
    UUID tenant = currentTenant();

    Map<UUID, ReconciliationSupplierOption> suppliers = new LinkedHashMap<>();
    var arrivals =
        sql.fetch(
            "select supplier_ref_id, supplier_code, supplier_name, count(*) as cnt"
                + " from purchase_arrival_tracking where tenant_id = ?::uuid"
                + " group by supplier_ref_id, supplier_code, supplier_name"
                + " order by supplier_code, supplier_name",
            tenant);
    for (Record r : arrivals) {
      suppliers.put(
          r.get("supplier_ref_id", UUID.class),
          new ReconciliationSupplierOption(
              r.get("supplier_ref_id", UUID.class),
              r.get("supplier_code", String.class),
              r.get("supplier_name", String.class),
              r.get("cnt", Integer.class) == null ? 0 : r.get("cnt", Integer.class)));
    }
    var claims =
        sql.fetch(
            "select supplier_ref_id, supplier_code, supplier_name, count(*) as cnt"
                + " from supplier_claim where tenant_id = ?::uuid"
                + " group by supplier_ref_id, supplier_code, supplier_name"
                + " order by supplier_code, supplier_name",
            tenant);
    for (Record r : claims) {
      suppliers.computeIfAbsent(
          r.get("supplier_ref_id", UUID.class),
          id ->
              new ReconciliationSupplierOption(
                  id,
                  r.get("supplier_code", String.class),
                  r.get("supplier_name", String.class),
                  0));
    }

    Map<UUID, Integer> statementCounts = new HashMap<>();
    var counts =
        sql.fetch(
            "select supplier_ref_id, count(*) as cnt from purchase_reconciliation"
                + " where tenant_id = ?::uuid group by supplier_ref_id",
            tenant);
    for (Record r : counts) {
      statementCounts.put(r.get("supplier_ref_id", UUID.class), r.get("cnt", Integer.class));
    }
    List<ReconciliationSupplierOption> supplierOptions =
        suppliers.values().stream()
            .map(
                o ->
                    new ReconciliationSupplierOption(
                        o.supplierRefId(),
                        o.supplierCode(),
                        o.supplierName(),
                        statementCounts.getOrDefault(o.supplierRefId(), 0)))
            .sorted(
                (a, b) -> {
                  int c = a.supplierCode().compareTo(b.supplierCode());
                  return c != 0 ? c : a.supplierName().compareTo(b.supplierName());
                })
            .toList();

    LocalDate today = LocalDate.now(PERIOD_ZONE);
    List<ReconciliationPeriodOption> periods =
        List.of(
            periodOption(ReconciliationPeriodType.MONTHLY, today),
            periodOption(ReconciliationPeriodType.QUARTERLY, today));

    List<String> currencies =
        sql.fetch(
                "select distinct currency from purchase_arrival_tracking where tenant_id = ?::uuid"
                    + " union select distinct claim_currency from supplier_claim where tenant_id = ?::uuid"
                    + " union select distinct statement_currency from purchase_reconciliation where tenant_id = ?::uuid"
                    + " order by 1",
                tenant,
                tenant,
                tenant)
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();

    List<ReconciliationDeductionOption> deductionTypes =
        java.util.Arrays.stream(DeductionType.values())
            .map(d -> new ReconciliationDeductionOption(d.name(), d.label()))
            .toList();

    return new ReconciliationOptions(
        supplierOptions,
        periods,
        currencies,
        deductionTypes,
        "供应商下拉取自 **trade 本库已交付事实**（6.3.2.2 到货跟踪单与 11.2.3.3 索赔单）里出现过的供应商，"
            + "不是 catalog 的全量供应商档案 —— 那些才是真的能与采购对账的对象。",
        "期间由**服务端裁决**：月度 = 某月 1 号到月末；季度 = 季首月 1 号到季末月末（UTC）。"
            + "调用方给的期间对不上会被 400 拒收，不会被悄悄改写。");
  }

  private ReconciliationPeriodOption periodOption(ReconciliationPeriodType type, LocalDate anchor) {
    Period period = adjudicate(type, anchor);
    return new ReconciliationPeriodOption(
        period.type(), type.label(), period.start(), period.end(), type.label() + "：" + period.start() + " ~ " + period.end());
  }

  // ================================================================ 列表

  /** 对账单列表：真分页 + 多维筛选 + 排序白名单 + 跨全表汇总。 */
  public ReconciliationPage list(PurchaseReconciliationListQuery query) {
    int p = query.page() == null || query.page() < 0 ? 0 : query.page();
    int s = query.size() == null || query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    String sort = sortSql(query.sort());

    List<Object> binds = new ArrayList<>();
    List<String> where = conditions(query, binds);
    String clause = " where " + String.join(" and ", where);

    String from = " from purchase_reconciliation r";
    long total = sql.fetchOne("select count(*)" + from + clause, binds.toArray()).get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<ReconciliationRow> rows =
        sql.fetch(
                "select r.*,"
                    + " (select count(*) from purchase_reconciliation_line l"
                    + "   where l.tenant_id = r.tenant_id and l.recon_id = r.id) as line_count,"
                    + " (select count(*) from purchase_reconciliation_discrepancy d"
                    + "   where d.tenant_id = r.tenant_id and d.recon_id = r.id) as discrepancy_count"
                    + from
                    + clause
                    + " order by "
                    + sort
                    + " limit ? offset ?",
                pageBinds.toArray())
            .map(r -> mapRow(r));

    return new ReconciliationPage(
        rows,
        p,
        s,
        total,
        summary(clause, binds),
        OffsetDateTime.now(ZoneOffset.UTC),
        "真分页（LIMIT/OFFSET + 独立 COUNT），汇总与排序不受分页影响。",
        "对账基数取自**已交付事实**：应收 = 6.3.2.2 到货流水，索赔 = 11.2.3.3 供应商索赔明细。"
            + "⚠️ 6.3.1.x 采购单未交付 ⇒ 不以采购单为基数；8.2.1.1 收货单不接受 PURCHASE 来源 "
            + "⇒ 采购收货事实取自 6.3.2.2。",
        "金额与数量按**币种**分桶，不跨币种相加（见明细页的 byCurrency）。"
            + "跨币种行按每行的汇率快照折算到对账单币种，折算金额由 DB 触发器算。",
        "排序白名单四个键（created_desc / period_desc / amount_desc / supplier_asc）；"
            + "其余取值落回 created_desc，不接受任意表达式。");
  }

  /** 列表的 where 条件与绑定。 */
  private List<String> conditions(PurchaseReconciliationListQuery query, List<Object> binds) {
    List<String> where = new ArrayList<>();
    where.add("r.tenant_id = ?::uuid");
    binds.add(currentTenant());

    if (isSet(query.status())) {
      where.add("r.status = ?");
      binds.add(parseStatus(query.status()));
    }
    if (query.supplierRefId() != null) {
      where.add("r.supplier_ref_id = ?::uuid");
      binds.add(query.supplierRefId());
    }
    if (isSet(query.periodType())) {
      where.add("r.period_type = ?");
      binds.add(
          parseEnum(ReconciliationPeriodType.class, query.periodType(), "期间类型").name());
    }
    if (query.periodStart() != null) {
      where.add("r.period_end >= ?::date");
      binds.add(query.periodStart());
    }
    if (query.periodEnd() != null) {
      where.add("r.period_start <= ?::date");
      binds.add(query.periodEnd());
    }
    if (isSet(query.currency())) {
      where.add("r.statement_currency = ?");
      binds.add(currency(query.currency()));
    }
    if (isSet(query.keyword())) {
      where.add("(r.recon_no ilike ? or r.supplier_code ilike ? or r.supplier_name ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    return where;
  }

  /** 页汇总：跨全表算，不随分页变小。 */
  private ReconciliationSummary summary(String clause, List<Object> binds) {
    Record r =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where r.status = 'DRAFT') as draft,"
                + " count(*) filter (where r.status = 'SENT') as sent,"
                + " count(*) filter (where r.status = 'DISPUTED') as disputed,"
                + " count(*) filter (where r.status = 'CONFIRMED') as confirmed,"
                + " count(*) filter (where r.status = 'CLOSED') as closed,"
                + " coalesce(sum(r.received_amount), 0) as total_received,"
                + " coalesce(sum(r.deduction_amount), 0) as total_deduction,"
                + " coalesce(sum(r.claim_amount), 0) as total_claim,"
                + " coalesce(sum(r.suggested_payable_amount), 0) as total_suggested,"
                + " coalesce(sum(r.open_discrepancy_count), 0) as open_diff"
                + " from purchase_reconciliation r" + clause,
            binds.toArray());
    return new ReconciliationSummary(
        r.get("total", Long.class),
        r.get("draft", Long.class),
        r.get("sent", Long.class),
        r.get("disputed", Long.class),
        r.get("confirmed", Long.class),
        r.get("closed", Long.class),
        r.get("total_received", BigDecimal.class),
        r.get("total_deduction", BigDecimal.class),
        r.get("total_claim", BigDecimal.class),
        r.get("total_suggested", BigDecimal.class),
        r.get("open_diff", Long.class));
  }

  private static String sortSql(String sort) {
    if (sort == null) {
      return "r.created_at desc, r.id desc";
    }
    return switch (sort.trim()) {
      case "period_desc" -> "r.period_start desc, r.created_at desc, r.id desc";
      case "amount_desc" -> "r.suggested_payable_amount desc, r.id desc";
      case "supplier_asc" -> "r.supplier_code asc, r.period_start desc, r.id desc";
      default -> "r.created_at desc, r.id desc";
    };
  }

  // ================================================================ 详情

  /** 对账单全景：头 + 明细 + 差异 + 回执 + 留痕 + 可执行动作 + 基数说明。 */
  public ReconciliationDetail detail(UUID id) {
    ReconciliationRow head = requireHead(id);
    return new ReconciliationDetail(
        head,
        allLines(id),
        allDiscrepancies(id),
        allConfirmations(id),
        allEvents(id),
        actions(head.status(), head.openDiscrepancyCount()),
        basis(head),
        OffsetDateTime.now(ZoneOffset.UTC),
        "采购侧对账：**不确认应付**。汇总里的 suggestedPayableAmount 是交给 12 财务的建议数，"
            + "付款由 12 财务执行。");
  }

  // ================================================================ 建单（采集基数）

  /**
   * 发起对账：按期间 + 供应商采集基数，生成对账单（草稿）。
   *
   * <p>基数采集顺序：到货流水（应收）→ 索赔明细（冲减）→ 采购登记的费用（建单时没有，
   * 建单后登记）。
   */
  public ReconciliationDetail create(PurchaseReconciliationCreate input, UUID actorId) {
    if (input == null) {
      throw badRequest("建单请求不能为空");
    }
    UUID tenant = currentTenant();
    if (input.supplierRefId() == null) {
      throw badRequest("供应商不能为空：对账是**按供应商**发起的");
    }
    String statementCurrency = currency(input.statementCurrency());
    Period period =
        adjudicate(
            parseEnum(ReconciliationPeriodType.class, input.periodType(), "期间类型"),
            input.periodStart());
    // ⚠️ 调用方给了结束日就必须与裁决口径一致：对不上就 400，不悄悄改写。
    if (input.periodEnd() != null && !input.periodEnd().equals(period.end())) {
      throw badRequest(
          "期间与口径不符："
              + period.type()
              + " 期间应为 "
              + period.start()
              + " ~ "
              + period.end()
              + "，收到 "
              + input.periodEnd()
              + "（本模块按服务端裁决口径，不接受被改写的期间）");
    }

    Map<String, FxRate> fxRates = parseFxRates(input.fxRates());
    SupplierSnapshot supplier = requireSupplier(input.supplierRefId());

    // ---- 采集应收基数：6.3.2.2 到货流水（同库只读）----
    List<Record> arrivals = arrivalBasis(tenant, input.supplierRefId(), period);
    // ---- 采集索赔基数：11.2.3.3 索赔明细（同库只读）----
    List<Record> claims = claimBasis(tenant, input.supplierRefId(), period);

    List<CollectedLine> collected = new ArrayList<>();
    for (Record r : arrivals) {
      collected.add(CollectedLine.receipt(r));
    }
    for (Record r : claims) {
      collected.add(CollectedLine.claim(r));
    }

    // ---- 跨币种必须给出汇率快照，否则 400（绝不按 1:1 折算）----
    for (CollectedLine line : collected) {
      if (!line.currency().equals(statementCurrency) && !fxRates.containsKey(line.currency())) {
        throw badRequest(
            "币种 "
                + line.currency()
                + " 的汇率快照未提供：跨币种不能按 1:1 折算（对账单币种 "
                + statementCurrency
                + "），请在 fxRates 里给出该币种的汇率与来源");
      }
    }

    // ---- 质检扣量：未接线 ⇒ 显式标注，不按 0 计入 ----
    boolean qualityAvailable = quality.available();
    String qualityNote =
        qualityAvailable ? "" : quality.unavailableNote();

    UUID reconId = UUID.randomUUID();
    String reconNo = "PR-" + period.start().toString().replace("-", "") + "-" + shortId();
    sql.execute(
        "insert into purchase_reconciliation (id, tenant_id, recon_no, supplier_ref_id,"
            + " supplier_code, supplier_name, period_type, period_start, period_end,"
            + " statement_currency, status, receipt_basis_source, claim_basis_source,"
            + " quality_basis_available, finance_handoff_status, note, created_by)"
            + " values (?::uuid, ?::uuid, ?, ?::uuid, ?, ?, ?, ?::date, ?::date, ?::char(3),"
            + " 'DRAFT', 'PURCHASE_ARRIVAL_6.3.2.2', 'SUPPLIER_CLAIM_11.2.3.3', ?::boolean,"
            + " 'PENDING_FINANCE_SERVICE', ?, ?::uuid)",
        reconId,
        tenant,
        reconNo,
        supplier.refId(),
        supplier.code(),
        supplier.name(),
        period.type(),
        period.start(),
        period.end(),
        statementCurrency,
        qualityAvailable,
        nullSafe(input.note()),
        actorId);

    int lineNo = 1;
    for (CollectedLine line : collected) {
      FxRate fx =
          line.currency().equals(statementCurrency)
              ? FxRate.identity()
              : fxRates.get(line.currency());
      insertLine(
          reconId,
          tenant,
          lineNo++,
          line,
          fx,
          qualityAvailable,
          qualityNote);
    }

    event(reconId, tenant, "CREATED", "", "DRAFT", null, "发起对账：" + reconNo, actorId);
    return detail(reconId);
  }

  /** 落一条明细行。 */
  private void insertLine(
      UUID reconId,
      UUID tenant,
      int lineNo,
      CollectedLine line,
      FxRate fx,
      boolean qualityAvailable,
      String qualityNote) {
    sql.execute(
        "insert into purchase_reconciliation_line (id, tenant_id, recon_id, line_no, line_type,"
            + " source_type, source_ref_id, source_ref_code, source_line_ref_id,"
            + " source_line_ref_code, occurred_at, quantity, unit_code, unit_conversion,"
            + " base_quantity, unit_price, currency, fx_rate, fx_rate_source, fx_rate_at,"
            + " amount, statement_amount, deduction_type, claim_id, quality_deduction_status,"
            + " quality_deduction_quantity, quality_deduction_note, note)"
            + " values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?, ?::uuid, ?, ?::timestamptz,"
            + " ?::numeric, ?, ?::numeric, ?::numeric, ?::numeric, ?::char(3), ?::numeric, ?,"
            + " ?::timestamptz, ?::numeric, 0, ?, ?::uuid, ?, ?::numeric, ?, ?)",
        UUID.randomUUID(),
        tenant,
        reconId,
        lineNo,
        line.lineType(),
        line.sourceType(),
        line.sourceRefId(),
        line.sourceRefCode(),
        line.sourceLineRefId(),
        line.sourceLineRefCode(),
        line.occurredAt(),
        line.quantity(),
        line.unitCode(),
        line.unitConversion(),
        line.baseQuantity(),
        line.unitPrice(),
        line.currency(),
        fx.rate(),
        fx.source(),
        OffsetDateTime.now(ZoneOffset.UTC),
        line.amount(),
        line.deductionType(),
        line.claimId(),
        line.qualityStatus(),
        line.qualityQuantity(),
        line.qualityNote(),
        nullSafe(line.note()));
  }

  /** 应收基数：6.3.2.2 到货流水（trade 本库只读）。 */
  private List<Record> arrivalBasis(UUID tenant, UUID supplierRefId, Period period) {
    OffsetDateTime from = period.start().atStartOfDay(PERIOD_ZONE).toOffsetDateTime();
    OffsetDateTime to = period.end().plusDays(1).atStartOfDay(PERIOD_ZONE).toOffsetDateTime();
    return sql.fetch(
        "select r.id as receipt_row_id, r.arrived_qty, r.arrived_at,"
            + " r.warehouse_receipt_code, r.arrival_seq,"
            + " t.id as tracking_id, t.tracking_no, t.purchase_order_no,"
            + " t.unit_price, t.currency, t.arrival_status"
            + " from purchase_arrival_receipt r"
            + " join purchase_arrival_tracking t"
            + "   on t.tenant_id = r.tenant_id and t.id = r.tracking_id"
            + " where r.tenant_id = ?::uuid"
            + "   and t.supplier_ref_id = ?::uuid"
            + "   and t.arrival_status <> 'CANCELLED'"
            + "   and r.arrived_at >= ?::timestamptz"
            + "   and r.arrived_at < ?::timestamptz"
            + " order by r.arrived_at, r.arrival_seq, r.id",
        tenant,
        supplierRefId,
        from,
        to);
  }

  /** 索赔基数：11.2.3.3 索赔明细（trade 本库只读）。 */
  private List<Record> claimBasis(UUID tenant, UUID supplierRefId, Period period) {
    OffsetDateTime from = period.start().atStartOfDay(PERIOD_ZONE).toOffsetDateTime();
    OffsetDateTime to = period.end().plusDays(1).atStartOfDay(PERIOD_ZONE).toOffsetDateTime();
    return sql.fetch(
        "select i.id as item_id, i.claim_qty, i.claim_amount, i.claim_currency,"
            + " c.id as claim_id, c.claim_no, c.created_at, c.claim_status"
            + " from supplier_claim_item i"
            + " join supplier_claim c on c.tenant_id = i.tenant_id and c.id = i.claim_id"
            + " where i.tenant_id = ?::uuid"
            + "   and c.supplier_ref_id = ?::uuid"
            + "   and c.claim_status in ('SUBMITTED','NEGOTIATING','AGREED','SETTLED')"
            + "   and c.created_at >= ?::timestamptz"
            + "   and c.created_at < ?::timestamptz"
            + " order by c.created_at, c.claim_no, i.id",
        tenant,
        supplierRefId,
        from,
        to);
  }

  // ================================================================ 代扣代缴费用

  /** 登记一笔代扣代缴费用（仅草稿）。 */
  public ReconciliationDetail addDeduction(
      UUID id, PurchaseReconciliationDeductionCreate input, UUID actorId) {
    if (input == null) {
      throw badRequest("代扣代缴费用请求不能为空");
    }
    UUID tenant = currentTenant();
    ReconciliationRow head = requireHead(id);
    requireStatus(head.status(), ReconciliationStatus.DRAFT, "登记代扣代缴费用");

    DeductionType deductionType =
        parseEnum(DeductionType.class, input.deductionType(), "代扣代缴费用类型");
    BigDecimal amount = money(input.amount(), "代扣代缴费用金额");
    if (amount.signum() <= 0) {
      throw badRequest("代扣代缴费用金额必须大于 0");
    }
    String currency = currency(input.currency());
    FxRate fx = resolveFx(currency, head.statementCurrency(), input.fxRate(), input.fxRateSource());

    int nextLineNo =
        sql.fetchOne(
                "select coalesce(max(line_no), 0) + 1 as n from purchase_reconciliation_line"
                    + " where tenant_id = ?::uuid and recon_id = ?::uuid",
                tenant,
                id)
            .get("n", Integer.class);

    CollectedLine line =
        new CollectedLine(
            "DEDUCTION",
            "MANUAL",
            null,
            "",
            null,
            "",
            OffsetDateTime.now(ZoneOffset.UTC),
            null,
            null,
            null,
            null,
            null,
            currency,
            amount,
            deductionType.name(),
            null,
            "NOT_APPLICABLE",
            null,
            "",
            nullSafe(input.note()));

    insertLine(id, tenant, nextLineNo, line, fx, true, "");
    event(
        id,
        tenant,
        "DEDUCTION_ADDED",
        head.status(),
        head.status(),
        amount,
        "登记代扣代缴费用：" + deductionType.label() + " " + amount + " " + currency,
        actorId);
    return detail(id);
  }

  // ================================================================ 发出

  /** 发出对账单给供应商确认（草稿 → 已发送）。 */
  public ReconciliationDetail send(UUID id, UUID actorId) {
    UUID tenant = currentTenant();
    ReconciliationRow head = requireHead(id);
    requireStatus(head.status(), ReconciliationStatus.DRAFT, "发出对账单");

    long lineCount =
        sql.fetchOne(
                "select count(*) as cnt from purchase_reconciliation_line"
                    + " where tenant_id = ?::uuid and recon_id = ?::uuid",
                tenant,
                id)
            .get(0, Long.class);
    if (lineCount == 0) {
      throw badRequest("对账单没有任何明细行，不能发出：一张没有明细的对账单发给供应商只会得到一句「对不上」");
    }
    transition(id, tenant, ReconciliationStatus.DRAFT, ReconciliationStatus.SENT, actorId, "发出对账单给供应商确认");
    return detail(id);
  }

  // ================================================================ 供应商回执

  /**
   * 录入供应商确认回执：逐行比对 → 逐条落差异 → 推进状态。
   *
   * <p>⚠️ 回执里的**逐行**供应商值是差异的来源：只给一个总额就答不出「差在哪一行」。
   */
  public ReconciliationDetail recordConfirmation(
      UUID id, PurchaseReconciliationConfirmationCreate input, UUID actorId) {
    if (input == null) {
      throw badRequest("供应商回执请求不能为空");
    }
    UUID tenant = currentTenant();
    ReconciliationRow head = requireHead(id);
    if (!head.status().equals(ReconciliationStatus.SENT.name())
        && !head.status().equals(ReconciliationStatus.DISPUTED.name())) {
      throw badRequest(
          "当前状态「"
              + head.statusLabel()
              + "」不能录入供应商回执：回执是针对「已发出」的对账单回来的");
    }
    if (input.supplierRepliedAt() == null) {
      throw badRequest("供应商答复时刻不能为空");
    }
    SupplierConfirmationResult result =
        parseEnum(
            SupplierConfirmationResult.class, input.supplierResult(), "供应商答复结果");

    Map<UUID, ReconciliationLineRow> lines = new LinkedHashMap<>();
    for (ReconciliationLineRow line : allLines(id)) {
      lines.put(line.id(), line);
    }

    String confirmationNo = "RC-" + shortId();
    int discrepancyCount = 0;
    List<SupplierLineValue> supplierLines =
        input.supplierLines() == null ? List.of() : input.supplierLines();
    for (SupplierLineValue value : supplierLines) {
      if (value == null) {
        continue;
      }
      if (value.lineId() == null) {
        if (!isSet(value.supplierLineRef())) {
          throw badRequest(
              "供应商行既没有指定我方行号，也没有给供应商自己的行标识："
                  + "这样的行无法落成差异（要么指到一行，要么说明是「供应商有我方未列」的那一行）");
        }
        insertDiscrepancy(
            id,
            tenant,
            null,
            null,
            DiscrepancyType.EXTRA_LINE,
            value.supplierLineRef(),
            null,
            value.quantity() == null && value.amount() == null ? null : value.quantity(),
            null,
            head.statementCurrency(),
            "供应商列了一行我方没有：" + value.supplierLineRef(),
            actorId);
        discrepancyCount++;
        continue;
      }
      ReconciliationLineRow ours = lines.get(value.lineId());
      if (ours == null) {
        throw badRequest("供应商回执引用了不属于本对账单的明细行：" + value.lineId());
      }
      discrepancyCount += compareSupplierLine(id, tenant, ours, value, actorId);
    }

    sql.execute(
        "insert into purchase_reconciliation_confirmation (id, tenant_id, recon_id,"
            + " confirmation_no, supplier_replied_at, supplier_replied_by, supplier_reference,"
            + " supplier_result, supplier_confirmed_amount, supplier_confirmed_currency,"
            + " discrepancy_count, note, created_by)"
            + " values (?::uuid, ?::uuid, ?::uuid, ?, ?::timestamptz, ?, ?, ?, ?::numeric, ?::char(3),"
            + " ?, ?, ?::uuid)",
        UUID.randomUUID(),
        tenant,
        id,
        confirmationNo,
        input.supplierRepliedAt(),
        nullSafe(input.supplierRepliedBy()),
        nullSafe(input.supplierReference()),
        result.name(),
        input.supplierConfirmedAmount(),
        input.supplierConfirmedCurrency() == null
            ? null
            : currency(input.supplierConfirmedCurrency()),
        discrepancyCount,
        nullSafe(input.note()),
        actorId);

    // 状态推进：有差异 ⇒ DISPUTED；供应商说确认且逐行比下来也没差异 ⇒ CONFIRMED。
    ReconciliationStatus next =
        discrepancyCount > 0 || result == SupplierConfirmationResult.DIFFERENCES
            ? ReconciliationStatus.DISPUTED
            : ReconciliationStatus.CONFIRMED;
    sql.execute(
        "update purchase_reconciliation set status = ?, supplier_confirmed_at = ?::timestamptz,"
            + " supplier_reference = ?, updated_at = now(), version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid and status in ('SENT','DISPUTED')",
        next.name(),
        input.supplierRepliedAt(),
        nullSafe(input.supplierReference()),
        tenant,
        id);
    event(
        id,
        tenant,
        "SUPPLIER_CONFIRMATION",
        head.status(),
        next.name(),
        input.supplierConfirmedAmount(),
        "录入供应商回执 "
            + confirmationNo
            + "："
            + result.label()
            + "，差异 "
            + discrepancyCount
            + " 条",
        actorId);
    return detail(id);
  }

  /** 逐行比对供应商值，返回产生的差异条数。 */
  private int compareSupplierLine(
      UUID reconId, UUID tenant, ReconciliationLineRow ours, SupplierLineValue value, UUID actorId) {
    int count = 0;
    if (value.quantity() != null && ours.quantity() != null) {
      BigDecimal diff = value.quantity().subtract(ours.quantity());
      if (diff.signum() != 0) {
        insertDiscrepancy(
            reconId,
            tenant,
            ours.id(),
            ours.lineNo(),
            DiscrepancyType.QUANTITY,
            "数量",
            ours.quantity(),
            value.quantity(),
            diff,
            ours.currency(),
            "供应商数量 " + value.quantity() + " 与我方 " + ours.quantity() + " 不符",
            actorId);
        count++;
      }
    }
    if (value.unitPrice() != null && ours.unitPrice() != null) {
      BigDecimal diff = value.unitPrice().subtract(ours.unitPrice());
      if (diff.signum() != 0) {
        insertDiscrepancy(
            reconId,
            tenant,
            ours.id(),
            ours.lineNo(),
            DiscrepancyType.UNIT_PRICE,
            "单价",
            ours.unitPrice(),
            value.unitPrice(),
            diff,
            ours.currency(),
            "供应商单价 " + value.unitPrice() + " 与我方 " + ours.unitPrice() + " 不符",
            actorId);
        count++;
      }
    }
    if (value.amount() != null && ours.amount() != null) {
      BigDecimal diff = value.amount().subtract(ours.amount());
      if (diff.signum() != 0) {
        insertDiscrepancy(
            reconId,
            tenant,
            ours.id(),
            ours.lineNo(),
            DiscrepancyType.AMOUNT,
            "金额",
            ours.amount(),
            value.amount(),
            diff,
            ours.currency(),
            "供应商金额 " + value.amount() + " 与我方 " + ours.amount() + " 不符",
            actorId);
        count++;
      }
    }
    return count;
  }

  // ================================================================ 差异

  /** 差异清单：真分页 + 状态筛选。 */
  public ReconciliationDiscrepancyPage discrepancies(
      UUID id, String status, int page, int size) {
    requireHead(id);
    int p = page < 0 ? 0 : page;
    int s = size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);

    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    binds.add(id);
    String where = " where d.tenant_id = ?::uuid and d.recon_id = ?::uuid";
    if (isSet(status)) {
      where += " and d.status = ?";
      binds.add(parseDiscrepancyStatus(status));
    }
    long total = sql.fetchOne("select count(*)" + where, binds.toArray()).get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<ReconciliationDiscrepancyRow> rows =
        sql.fetch(
                "select d.* from purchase_reconciliation_discrepancy d"
                    + where
                    + " order by d.line_no nulls last, d.created_at desc, d.id desc"
                    + " limit ? offset ?",
                pageBinds.toArray())
            .map(r -> mapDiscrepancy(r));

    Record counts =
        sql.fetchOne(
            "select count(*) filter (where d.status = 'OPEN') as open,"
                + " count(*) filter (where d.status = 'HELD') as held,"
                + " count(*) filter (where d.status = 'RESOLVED') as resolved"
                + " from purchase_reconciliation_discrepancy d"
                + " where d.tenant_id = ?::uuid and d.recon_id = ?::uuid",
            currentTenant(),
            id);

    return new ReconciliationDiscrepancyPage(
        rows,
        p,
        s,
        total,
        counts.get("open", Long.class),
        counts.get("held", Long.class),
        counts.get("resolved", Long.class),
        OffsetDateTime.now(ZoneOffset.UTC),
        "差异**逐条成行**：每条记清差在哪个字段、我方值、供应商值与差额；"
            + "差异可挂起（HELD）后再处理，不聚合成一个差额总数。");
  }

  /** 追加一条差异（采购主动记的，不等供应商回执）。 */
  public ReconciliationDetail addDiscrepancy(
      UUID id, PurchaseReconciliationDiscrepancyCreate input, UUID actorId) {
    if (input == null) {
      throw badRequest("差异请求不能为空");
    }
    UUID tenant = currentTenant();
    ReconciliationRow head = requireHead(id);
    if (head.status().equals(ReconciliationStatus.CLOSED.name())) {
      throw badRequest("已关闭的对账单不能再追加差异");
    }
    DiscrepancyType type =
        parseEnum(DiscrepancyType.class, input.discrepancyType(), "差异类型");
    Integer lineNo = null;
    if (input.lineId() != null) {
      ReconciliationLineRow line = requireLine(id, input.lineId());
      lineNo = line.lineNo();
    }
    BigDecimal ourValue = input.ourValue();
    BigDecimal supplierValue = input.supplierValue();
    BigDecimal difference =
        (supplierValue == null ? BigDecimal.ZERO : supplierValue)
            .subtract(ourValue == null ? BigDecimal.ZERO : ourValue);
    if (difference.signum() == 0) {
      throw badRequest("差异不能为 0：差 0 不叫差异");
    }
    insertDiscrepancy(
        id,
        tenant,
        input.lineId(),
        lineNo,
        type,
        nullSafe(input.fieldName()),
        ourValue,
        supplierValue,
        difference,
        currency(input.currency()),
        nullSafe(input.note()),
        actorId);
    event(id, tenant, "DISCREPANCY_ADDED", head.status(), head.status(), difference, "追加差异：" + type.label(), actorId);
    return detail(id);
  }

  /** 处理一条差异：挂起（HELD）或解决（RESOLVED）。 */
  public ReconciliationDetail resolveDiscrepancy(
      UUID id, UUID discrepancyId, PurchaseReconciliationDiscrepancyResolve input, UUID actorId) {
    if (input == null) {
      throw badRequest("差异处理请求不能为空");
    }
    UUID tenant = currentTenant();
    ReconciliationRow head = requireHead(id);
    if (head.status().equals(ReconciliationStatus.CLOSED.name())) {
      throw badRequest("已关闭的对账单不能再处理差异");
    }
    DiscrepancyStatus target =
        parseEnum(DiscrepancyStatus.class, input.status(), "差异处理结果");
    if (target == DiscrepancyStatus.OPEN) {
      throw badRequest("差异处理结果只能是「已挂起」或「已解决」：回到「待处理」等于什么都没做");
    }
    if (target == DiscrepancyStatus.RESOLVED) {
      if (!isSet(input.resolution()) || !isSet(input.resolutionNote())) {
        throw badRequest("解决差异必须写处理方式与说明：只标「已解决」的话，三个月后没人说得清怎么解决的");
      }
    }
    int updated =
        sql.execute(
            "update purchase_reconciliation_discrepancy set status = ?, resolution = ?,"
                + " resolution_note = ?, resolved_by = ?, resolved_at = ?::timestamptz"
                + " where tenant_id = ?::uuid and id = ?::uuid and recon_id = ?::uuid",
            target.name(),
            nullSafe(input.resolution()),
            nullSafe(input.resolutionNote()),
            target == DiscrepancyStatus.RESOLVED ? actorId : null,
            target == DiscrepancyStatus.RESOLVED ? OffsetDateTime.now(ZoneOffset.UTC) : null,
            tenant,
            discrepancyId,
            id);
    if (updated == 0) {
      throw notFound("差异不存在或不属于本对账单：" + discrepancyId);
    }
    event(
        id,
        tenant,
        "DISCREPANCY_" + target.name(),
        head.status(),
        head.status(),
        null,
        "处理差异 " + discrepancyId + " → " + target.label(),
        actorId);

    // 差异全部解决 ⇒ 自动推进到「已确认」（供应商回执已到才有确认资格）。
    long open =
        sql.fetchOne(
                "select count(*) as cnt from purchase_reconciliation_discrepancy"
                    + " where tenant_id = ?::uuid and recon_id = ?::uuid and status <> 'RESOLVED'",
                tenant,
                id)
            .get(0, Long.class);
    if (open == 0 && head.status().equals(ReconciliationStatus.DISPUTED.name())) {
      sql.execute(
          "update purchase_reconciliation set status = 'CONFIRMED', updated_at = now(),"
              + " version = version + 1"
              + " where tenant_id = ?::uuid and id = ?::uuid and status = 'DISPUTED'",
          tenant,
          id);
      event(id, tenant, "STATUS", "DISPUTED", "CONFIRMED", null, "差异全部解决 ⇒ 已确认", actorId);
    }
    return detail(id);
  }

  // ================================================================ 重开 / 关闭

  /** 重开对账单（追加差异）。 */
  public ReconciliationDetail reopen(UUID id, PurchaseReconciliationReopen input, UUID actorId) {
    UUID tenant = currentTenant();
    ReconciliationRow head = requireHead(id);
    if (head.status().equals(ReconciliationStatus.CLOSED.name())) {
      throw badRequest("已关闭的对账单不可重开：它已经是与供应商对质的最终凭据");
    }
    if (head.status().equals(ReconciliationStatus.DRAFT.name())) {
      throw badRequest("对账单已经是草稿，无需重开");
    }
    if (input == null || !isSet(input.reason())) {
      throw badRequest("重开必须写理由：重开意味着之前发给供应商的那一版不作数了");
    }
    sql.execute(
        "update purchase_reconciliation set status = 'DRAFT', updated_at = now(),"
            + " version = version + 1 where tenant_id = ?::uuid and id = ?::uuid",
        tenant,
        id);
    event(id, tenant, "REOPENED", head.status(), "DRAFT", null, "重开对账：" + input.reason().trim(), actorId);
    return detail(id);
  }

  /** 关闭对账单（已确认 → 已关闭）。 */
  public ReconciliationDetail close(UUID id, UUID actorId) {
    UUID tenant = currentTenant();
    ReconciliationRow head = requireHead(id);
    requireStatus(head.status(), ReconciliationStatus.CONFIRMED, "关闭对账单");
    sql.execute(
        "update purchase_reconciliation set status = 'CLOSED', updated_at = now(),"
            + " version = version + 1 where tenant_id = ?::uuid and id = ?::uuid and status = 'CONFIRMED'",
        tenant,
        id);
    event(id, tenant, "CLOSED", head.status(), "CLOSED", null, "关闭对账单", actorId);
    return detail(id);
  }

  // ================================================================ 明细行分页

  /** 明细行列表：真分页 + 行类型筛选 + 按币种分桶的汇总。 */
  public ReconciliationLinePage lines(UUID id, String lineType, int page, int size) {
    requireHead(id);
    int p = page < 0 ? 0 : page;
    int s = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);

    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    binds.add(id);
    String where = " where l.tenant_id = ?::uuid and l.recon_id = ?::uuid";
    if (isSet(lineType)) {
      where += " and l.line_type = ?";
      binds.add(parseEnum(ReconciliationLineType.class, lineType, "明细行类型").name());
    }
    long total = sql.fetchOne("select count(*)" + where, binds.toArray()).get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<ReconciliationLineRow> rows =
        sql.fetch(
                "select l.* from purchase_reconciliation_line l"
                    + where
                    + " order by l.line_no limit ? offset ?",
                pageBinds.toArray())
            .map(r -> mapLine(r));

    Record agg =
        sql.fetchOne(
            "select count(*) filter (where l.line_type = 'RECEIPT') as receipt_lines,"
                + " count(*) filter (where l.line_type = 'CLAIM') as claim_lines,"
                + " count(*) filter (where l.line_type = 'DEDUCTION') as deduction_lines,"
                + " coalesce(sum(l.statement_amount) filter (where l.line_type = 'RECEIPT'), 0) as receipt_amount,"
                + " coalesce(sum(l.statement_amount) filter (where l.line_type = 'CLAIM'), 0) as claim_amount,"
                + " coalesce(sum(l.statement_amount) filter (where l.line_type = 'DEDUCTION'), 0) as deduction_amount,"
                + " coalesce(sum(l.base_quantity) filter (where l.line_type = 'RECEIPT'), 0) as qty"
                + " from purchase_reconciliation_line l"
                + " where l.tenant_id = ?::uuid and l.recon_id = ?::uuid",
            currentTenant(),
            id);

    List<CurrencyBucket> buckets =
        sql.fetch(
                "select l.currency, count(*) as cnt, coalesce(sum(l.statement_amount), 0) as amount"
                    + " from purchase_reconciliation_line l"
                    + " where l.tenant_id = ?::uuid and l.recon_id = ?::uuid"
                    + " group by l.currency order by l.currency",
                currentTenant(),
                id)
            .stream()
            .map(r -> new CurrencyBucket(r.get("currency", String.class), r.get("cnt", Long.class), r.get("amount", BigDecimal.class)))
            .toList();

    return new ReconciliationLinePage(
        rows,
        p,
        s,
        total,
        new ReconciliationLineSummary(
            agg.get("receipt_lines", Long.class),
            agg.get("claim_lines", Long.class),
            agg.get("deduction_lines", Long.class),
            agg.get("receipt_amount", BigDecimal.class),
            agg.get("claim_amount", BigDecimal.class),
            agg.get("deduction_amount", BigDecimal.class),
            agg.get("qty", BigDecimal.class),
            buckets),
        OffsetDateTime.now(ZoneOffset.UTC),
        "金额按**币种**分桶（byCurrency），不跨币种相加；"
            + "折算到对账单币种的金额由 DB 触发器按每行汇率快照算。"
            + "⚠️ 质检扣量未接入的行，quantity 口径尚未扣除质检不合格量（行上有 UNAVAILABLE 标注）。");
  }

  // ================================================================ 导出

  /**
   * 导出 CSV（发给供应商）。
   *
   * <p>真导出：内容就是这张对账单的**全部明细行** + 头部的期间 / 币种 / 汇总。
   * 质检扣量未接入的行在文件里也写明「未接入」—— 让供应商看到的和采购看到的是同一份口径。
   */
  public byte[] exportCsv(UUID id) {
    ReconciliationDetail detail = detail(id);
    ReconciliationRow head = detail.head();
    StringBuilder sb = new StringBuilder();
    sb.append("对账单号, ").append(head.reconNo()).append('\n');
    sb.append("供应商, ").append(head.supplierCode()).append(' ').append(head.supplierName()).append('\n');
    sb.append("期间, ").append(head.periodStart()).append(" ~ ").append(head.periodEnd())
        .append(" (").append(head.periodTypeLabel()).append(")\n");
    sb.append("对账单币种, ").append(head.statementCurrency()).append('\n');
    sb.append("状态, ").append(head.statusLabel()).append('\n');
    sb.append("已到货应收, ").append(head.receivedAmount()).append('\n');
    sb.append("代扣代缴费用, ").append(head.deductionAmount()).append('\n');
    sb.append("索赔冲减, ").append(head.claimAmount()).append('\n');
    sb.append("采购侧建议应付数, ").append(head.suggestedPayableAmount())
        .append(" (采购侧不确认应付，付款由 12 财务执行)\n");
    sb.append('\n');
    sb.append(
        "行号,类型,来源单据,来源行,发生时刻,数量,单位,单位换算,基础数量,单价,币种,汇率,汇率来源,原币金额,折算金额,质检扣量状态,质检扣量数量,备注\n");
    for (ReconciliationLineRow line : detail.lines()) {
      sb.append(line.lineNo()).append(',')
          .append(line.lineTypeLabel()).append(',')
          .append(line.sourceRefCode()).append(',')
          .append(line.sourceLineRefCode()).append(',')
          .append(line.occurredAt()).append(',')
          .append(quantityText(line.quantity())).append(',')
          .append(nullSafe(line.unitCode())).append(',')
          .append(quantityText(line.unitConversion())).append(',')
          .append(quantityText(line.baseQuantity())).append(',')
          .append(quantityText(line.unitPrice())).append(',')
          .append(line.currency()).append(',')
          .append(quantityText(line.fxRate())).append(',')
          .append(nullSafe(line.fxRateSource())).append(',')
          .append(quantityText(line.amount())).append(',')
          .append(quantityText(line.statementAmount())).append(',')
          .append(line.qualityDeductionStatusLabel()).append(',')
          .append(quantityText(line.qualityDeductionQuantity())).append(',')
          .append(nullSafe(line.note()))
          .append('\n');
    }
    if (!detail.discrepancies().isEmpty()) {
      sb.append('\n');
      sb.append("差异清单\n");
      sb.append("行号,差异类型,字段,我方值,供应商值,差额,币种,状态,处理方式,说明\n");
      for (ReconciliationDiscrepancyRow d : detail.discrepancies()) {
        sb.append(d.lineNo() == null ? "" : d.lineNo()).append(',')
            .append(d.discrepancyTypeLabel()).append(',')
            .append(nullSafe(d.fieldName())).append(',')
            .append(quantityText(d.ourValue())).append(',')
            .append(quantityText(d.supplierValue())).append(',')
            .append(quantityText(d.difference())).append(',')
            .append(d.currency()).append(',')
            .append(d.statusLabel()).append(',')
            .append(nullSafe(d.resolution())).append(',')
            .append(nullSafe(d.resolutionNote()))
            .append('\n');
      }
    }
    return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  // ================================================================ 读取

  private ReconciliationRow requireHead(UUID id) {
    UUID tenant = currentTenant();
    Record r =
        sql.fetchOne(
            "select r.*,"
                + " (select count(*) from purchase_reconciliation_line l"
                + "   where l.tenant_id = r.tenant_id and l.recon_id = r.id) as line_count,"
                + " (select count(*) from purchase_reconciliation_discrepancy d"
                + "   where d.tenant_id = r.tenant_id and d.recon_id = r.id) as discrepancy_count"
                + " from purchase_reconciliation r where r.tenant_id = ?::uuid and r.id = ?::uuid",
            tenant,
            id);
    if (r == null) {
      throw notFound("对账单不存在或无权访问：" + id);
    }
    return mapRow(r);
  }

  private ReconciliationLineRow requireLine(UUID reconId, UUID lineId) {
    Record r =
        sql.fetchOne(
            "select l.* from purchase_reconciliation_line l"
                + " where l.tenant_id = ?::uuid and l.recon_id = ?::uuid and l.id = ?::uuid",
            currentTenant(),
            reconId,
            lineId);
    if (r == null) {
      throw notFound("明细行不存在或不属于本对账单：" + lineId);
    }
    return mapLine(r);
  }

  private List<ReconciliationLineRow> allLines(UUID reconId) {
    return sql.fetch(
            "select l.* from purchase_reconciliation_line l"
                + " where l.tenant_id = ?::uuid and l.recon_id = ?::uuid order by l.line_no",
            currentTenant(),
            reconId)
        .map(r -> mapLine(r));
  }

  private List<ReconciliationDiscrepancyRow> allDiscrepancies(UUID reconId) {
    return sql.fetch(
            "select d.* from purchase_reconciliation_discrepancy d"
                + " where d.tenant_id = ?::uuid and d.recon_id = ?::uuid"
                + " order by d.line_no nulls last, d.created_at desc, d.id desc",
            currentTenant(),
            reconId)
        .map(r -> mapDiscrepancy(r));
  }

  private List<ReconciliationConfirmationRow> allConfirmations(UUID reconId) {
    return sql.fetch(
            "select * from purchase_reconciliation_confirmation"
                + " where tenant_id = ?::uuid and recon_id = ?::uuid"
                + " order by created_at desc, id desc",
            currentTenant(),
            reconId)
        .map(
            r ->
                new ReconciliationConfirmationRow(
                    r.get("id", UUID.class),
                    r.get("confirmation_no", String.class),
                    r.get("supplier_replied_at", OffsetDateTime.class),
                    r.get("supplier_replied_by", String.class),
                    r.get("supplier_reference", String.class),
                    r.get("supplier_result", String.class),
                    supplierResultLabel(r.get("supplier_result", String.class)),
                    r.get("supplier_confirmed_amount", BigDecimal.class),
                    r.get("supplier_confirmed_currency", String.class),
                    r.get("discrepancy_count", Integer.class) == null ? 0 : r.get("discrepancy_count", Integer.class),
                    r.get("note", String.class),
                    r.get("created_at", OffsetDateTime.class)));
  }

  private List<ReconciliationEventRow> allEvents(UUID reconId) {
    return sql.fetch(
            "select * from purchase_reconciliation_event"
                + " where tenant_id = ?::uuid and recon_id = ?::uuid order by occurred_at desc, id desc",
            currentTenant(),
            reconId)
        .map(
            r ->
                new ReconciliationEventRow(
                    r.get("id", UUID.class),
                    r.get("event_type", String.class),
                    eventTypeLabel(r.get("event_type", String.class)),
                    r.get("from_status", String.class),
                    r.get("to_status", String.class),
                    r.get("amount", BigDecimal.class),
                    r.get("reason_note", String.class),
                    r.get("occurred_at", OffsetDateTime.class)));
  }

  /** 基数说明：逐条报出基数取自哪个事实、接通了没有、未接线的影响是什么。 */
  private ReconciliationBasis basis(ReconciliationRow head) {
    return new ReconciliationBasis(
        List.of(
            new ReconciliationBasisSource(
                "PURCHASE_ARRIVAL",
                "6.3.2.2 到货流水（应收数量 / 单价 / 币种）",
                true,
                null,
                "已交付（V235，同库只读）：数量取本次到货量，单价与币种取到货跟踪单的采购单价快照。",
                "/purchase/arrivals"),
            new ReconciliationBasisSource(
                "SUPPLIER_CLAIM",
                "11.2.3.3 供应商索赔（索赔冲减）",
                true,
                null,
                "已交付（V188，同库只读）：按索赔明细逐行计入，只计已提交 / 协商中 / 已达成 / 已结算。",
                "/aftersales/returns/supplier-claims"),
            new ReconciliationBasisSource(
                "MANUAL_DEDUCTION",
                "采购登记的代扣代缴费用",
                true,
                null,
                "本模块自有事实：费用类型 + 金额 + 币种 + 汇率快照。",
                null)),
        List.of(
            new ReconciliationUnavailableSource(
                "QUALITY_DEDUCTION",
                "6.4.2.1 质检扣量",
                false,
                "6.4.2.1",
                "质检单与本模块同批并行开发，此刻在本仓不存在（无端点、无事实表）⇒ 端口未接线。",
                "本对账单的应收数量**尚未扣除**质检不合格量；"
                    + "明细行标 qualityDeductionStatus='UNAVAILABLE'、qualityDeductionQuantity=NULL（不是 0）。"),
            new ReconciliationUnavailableSource(
                "RECEIPT_IN_WAREHOUSE",
                "8.2.1.1 采购来源收货单",
                false,
                "8.2.1.1 / 6.3.1",
                "warehouse 的收货单**不接受 PURCHASE 来源**（只有 TRANSFER 来源可用，因为 6.3.1 采购单未交付）。",
                "采购收货事实取自 6.3.2.2 到货流水；warehouse 收货单号只作为来源快照带在明细行上。"),
            new ReconciliationUnavailableSource(
                "FINANCE_PAYABLE",
                "12 财务（应付核对与付款）",
                false,
                "12 财务",
                "finance 服务当前不存在（backend/services/ 下只有 identity / catalog / channel / warehouse / trade / crm）。",
                "本对账单是采购与财务之间的**交接物**：采购侧不确认应付，"
                    + "financeHandoffStatus 恒为 PENDING_FINANCE_SERVICE。"),
            new ReconciliationUnavailableSource(
                "PURCHASE_ORDER",
                "6.3.1 采购单",
                false,
                "6.3.1",
                "采购单与采购单审批未交付。",
                "对账基数**不以采购单为准**（取已到货事实）；本模块也不为采购单建影子表。")),
        "应收基数 = 6.3.2.2 到货流水（已交付，同库只读）；索赔 = 11.2.3.3 索赔明细（已交付，同库只读）；"
            + "代扣代缴费用 = 采购登记。⚠️ 6.3.1.x 采购单未交付 ⇒ 不以采购单为基数。",
        head.qualityBasisAvailable()
            ? "质检扣量已接入。"
            : "⚠️ 质检扣量（6.4.2.1）**未接线** ⇒ 本对账单的应收数量尚未扣除质检不合格量。"
                + "明细行标 UNAVAILABLE，扣量数量为 NULL（不是 0）。",
        "⚠️ 12 财务（finance 服务）**当前不存在** ⇒ 本模块不确认应付、不谈付款："
            + "汇总里的 suggestedPayableAmount 是采购侧**建议**数，financeHandoffStatus 恒为 "
            + "PENDING_FINANCE_SERVICE。交接载体就是这张对账单。",
        "期间由服务端裁决：月度 = 某月 1 号到月末；季度 = 季首月 1 号到季末月末（UTC）。"
            + "调用方给的期间对不上会被 400 拒收，不会被悄悄改写。",
        "每行带币种 + 汇率快照（fx_rate / fx_rate_source / fx_rate_at）；"
            + "折算金额由 DB 触发器按 amount × fx_rate 算。⚠️ 跨币种不给汇率 ⇒ 400；"
            + "DB 侧另行拒收「币种不同却按 1:1 折算」的行。",
        "每行带 quantity / unit_code / unit_conversion，基础数量 base_quantity 由 DB 算"
            + "（数量 × 换算比例）。到货流水的到货量与到货跟踪单的单价**同单位**"
            + "（V235 的金额算式为凭据），故该批行的换算比例恒为 1 且换算基数与计价单位一致。");
  }

  /** 当前可执行动作。 */
  private List<ReconciliationAction> actions(String status, long openDiscrepancies) {
    ReconciliationStatus current = ReconciliationStatus.valueOf(status);
    boolean draft = current == ReconciliationStatus.DRAFT;
    boolean sent = current == ReconciliationStatus.SENT;
    boolean disputed = current == ReconciliationStatus.DISPUTED;
    boolean confirmed = current == ReconciliationStatus.CONFIRMED;
    boolean closed = current == ReconciliationStatus.CLOSED;
    List<ReconciliationAction> list = new ArrayList<>();
    list.add(
        actionOf(
            "ADD_DEDUCTION",
            "登记代扣代缴费用",
            draft,
            "POST",
            "/trade/purchase-reconciliations/{id}/deductions",
            false,
            "仅草稿",
            closed ? "已关闭的对账单不可改" : draft ? null : "当前状态「" + current.label() + "」不能登记费用",
            null));
    list.add(
        actionOf(
            "SEND",
            "发给供应商确认",
            draft,
            "POST",
            "/trade/purchase-reconciliations/{id}/send",
            false,
            "仅草稿，且至少一条明细行",
            draft ? null : "当前状态「" + current.label() + "」不能发出",
            null));
    list.add(
        actionOf(
            "RECORD_CONFIRMATION",
            "录入供应商确认回执",
            sent || disputed,
            "POST",
            "/trade/purchase-reconciliations/{id}/confirmations",
            false,
            "仅已发送 / 有差异待处理",
            sent || disputed ? null : "当前状态「" + current.label() + "」不能录入回执",
            null));
    list.add(
        actionOf(
            "RESOLVE_DISCREPANCY",
            "处理差异（挂起 / 解决）",
            openDiscrepancies > 0 && !closed,
            "POST",
            "/trade/purchase-reconciliations/{id}/discrepancies/{discrepancyId}/resolve",
            false,
            "解决必须写方式与说明；挂起不需要",
            openDiscrepancies == 0 ? "当前没有待处理差异" : closed ? "已关闭的对账单不可处理差异" : null,
            null));
    list.add(
        actionOf(
            "REOPEN",
            "重开对账（追加差异）",
            sent || disputed || confirmed,
            "POST",
            "/trade/purchase-reconciliations/{id}/reopen",
            false,
            "仅未关闭；必须写重开理由",
            closed ? "已关闭的对账单不可重开" : draft ? "对账单已经是草稿" : null,
            null));
    list.add(
        actionOf(
            "CLOSE",
            "关闭对账单",
            confirmed,
            "POST",
            "/trade/purchase-reconciliations/{id}/close",
            false,
            "仅已确认",
            confirmed ? null : "当前状态「" + current.label() + "」不能关闭",
            null));
    return list;
  }

  // ================================================================ 落库辅助

  private void insertDiscrepancy(
      UUID reconId,
      UUID tenant,
      UUID lineId,
      Integer lineNo,
      DiscrepancyType type,
      String fieldName,
      BigDecimal ourValue,
      BigDecimal supplierValue,
      BigDecimal difference,
      String currency,
      String note,
      UUID actorId) {
    sql.execute(
        "insert into purchase_reconciliation_discrepancy (id, tenant_id, recon_id, line_id,"
            + " line_no, discrepancy_type, field_name, our_value, supplier_value, difference,"
            + " currency, status, note, created_by)"
            + " values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?::numeric, ?::numeric,"
            + " ?::numeric, ?::char(3), 'OPEN', ?, ?::uuid)",
        UUID.randomUUID(),
        tenant,
        reconId,
        lineId,
        lineNo,
        type.name(),
        nullSafe(fieldName),
        ourValue,
        supplierValue,
        difference,
        currency,
        nullSafe(note),
        actorId);
  }

  private void transition(
      UUID id,
      UUID tenant,
      ReconciliationStatus from,
      ReconciliationStatus to,
      UUID actorId,
      String reason) {
    int updated =
        sql.execute(
            "update purchase_reconciliation set status = ?, updated_at = now(),"
                + " version = version + 1"
                + " where tenant_id = ?::uuid and id = ?::uuid and status = ?",
            to.name(),
            tenant,
            id,
            from.name());
    if (updated == 0) {
      throw badRequest(
          "对账单状态已变（期望「" + from.label() + "」），请刷新后重试");
    }
    event(id, tenant, "STATUS", from.name(), to.name(), null, reason, actorId);
  }

  private void event(
      UUID reconId,
      UUID tenant,
      String type,
      String fromStatus,
      String toStatus,
      BigDecimal amount,
      String reason,
      UUID actorId) {
    sql.execute(
        "insert into purchase_reconciliation_event (id, tenant_id, recon_id, event_type,"
            + " from_status, to_status, amount, reason_note, actor_id, occurred_at)"
            + " values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?::numeric, ?, ?::uuid, ?::timestamptz)",
        UUID.randomUUID(),
        tenant,
        reconId,
        type,
        nullSafe(fromStatus),
        nullSafe(toStatus),
        amount,
        nullSafe(reason),
        actorId,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  // ================================================================ 映射

  private static ReconciliationRow mapRow(Record r) {
    String status = r.get("status", String.class);
    String periodType = r.get("period_type", String.class);
    return new ReconciliationRow(
        r.get("id", UUID.class),
        r.get("recon_no", String.class),
        r.get("supplier_ref_id", UUID.class),
        r.get("supplier_code", String.class),
        r.get("supplier_name", String.class),
        periodType,
        periodTypeLabel(periodType),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        r.get("statement_currency", String.class),
        status,
        statusLabel(status),
        r.get("received_amount", BigDecimal.class),
        r.get("deduction_amount", BigDecimal.class),
        r.get("claim_amount", BigDecimal.class),
        r.get("suggested_payable_amount", BigDecimal.class),
        r.get("total_quantity", BigDecimal.class),
        r.get("line_count", Long.class) == null ? 0 : r.get("line_count", Long.class),
        r.get("discrepancy_count", Long.class) == null ? 0 : r.get("discrepancy_count", Long.class),
        r.get("open_discrepancy_count", Integer.class) == null ? 0 : r.get("open_discrepancy_count", Integer.class),
        r.get("quality_basis_available", Boolean.class) != null && r.get("quality_basis_available", Boolean.class),
        r.get("finance_handoff_status", String.class),
        r.get("supplier_confirmed_at", OffsetDateTime.class),
        r.get("supplier_reference", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("version", Long.class) == null ? 0 : r.get("version", Long.class));
  }

  private static ReconciliationLineRow mapLine(Record r) {
    String lineType = r.get("line_type", String.class);
    String sourceType = r.get("source_type", String.class);
    String deductionType = r.get("deduction_type", String.class);
    String qualityStatus = r.get("quality_deduction_status", String.class);
    return new ReconciliationLineRow(
        r.get("id", UUID.class),
        r.get("line_no", Integer.class),
        lineType,
        lineTypeLabel(lineType),
        sourceType,
        sourceTypeLabel(sourceType),
        r.get("source_ref_id", UUID.class),
        r.get("source_ref_code", String.class),
        r.get("source_line_ref_id", UUID.class),
        r.get("source_line_ref_code", String.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("quantity", BigDecimal.class),
        r.get("unit_code", String.class),
        r.get("unit_conversion", BigDecimal.class),
        r.get("base_quantity", BigDecimal.class),
        r.get("unit_price", BigDecimal.class),
        r.get("currency", String.class),
        r.get("fx_rate", BigDecimal.class),
        r.get("fx_rate_source", String.class),
        r.get("fx_rate_at", OffsetDateTime.class),
        r.get("amount", BigDecimal.class),
        r.get("statement_amount", BigDecimal.class),
        deductionType,
        deductionType == null ? "" : deductionTypeLabel(deductionType),
        r.get("claim_id", UUID.class),
        qualityStatus,
        qualityStatusLabel(qualityStatus),
        r.get("quality_deduction_quantity", BigDecimal.class),
        r.get("quality_deduction_note", String.class),
        r.get("note", String.class));
  }

  private static ReconciliationDiscrepancyRow mapDiscrepancy(Record r) {
    String type = r.get("discrepancy_type", String.class);
    String status = r.get("status", String.class);
    return new ReconciliationDiscrepancyRow(
        r.get("id", UUID.class),
        r.get("line_id", UUID.class),
        r.get("line_no", Integer.class),
        type,
        discrepancyTypeLabel(type),
        r.get("field_name", String.class),
        r.get("our_value", BigDecimal.class),
        r.get("supplier_value", BigDecimal.class),
        r.get("difference", BigDecimal.class),
        r.get("currency", String.class),
        status,
        discrepancyStatusLabel(status),
        r.get("resolution", String.class),
        r.get("resolution_note", String.class),
        r.get("note", String.class),
        r.get("resolved_by", UUID.class),
        r.get("resolved_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  // ================================================================ 口径工具

  /** 当前租户（来自数据库里绑定的 {@code app.current_tenant}）。 */
  private UUID currentTenant() {
    var row =
        sql.fetchOne("select nullif(current_setting('app.current_tenant', true), '')::uuid as t");
    UUID tenant = row == null ? null : row.get("t", UUID.class);
    if (tenant == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    return tenant;
  }

  /** 期间裁决：月度 = 某月 1 号到月末；季度 = 季首月 1 号到季末月末。 */
  private Period adjudicate(ReconciliationPeriodType type, LocalDate anchor) {
    if (anchor == null) {
      throw badRequest("期间起始日不能为空");
    }
    if (type == ReconciliationPeriodType.MONTHLY) {
      if (anchor.getDayOfMonth() != 1) {
        throw badRequest(
            "月度期间必须从当月 1 号开始，收到 "
                + anchor
                + "（本模块按服务端裁决口径，不接受被改写的期间）");
      }
      LocalDate start = anchor;
      return new Period("MONTHLY", start, start.withDayOfMonth(start.lengthOfMonth()));
    }
    int quarterFirstMonth = ((anchor.getMonthValue() - 1) / 3) * 3 + 1;
    LocalDate start = LocalDate.of(anchor.getYear(), quarterFirstMonth, 1);
    if (!start.equals(anchor)) {
      throw badRequest(
          "季度期间必须从季首月 1 号开始，收到 "
              + anchor
              + "（本模块按服务端裁决口径，不接受被改写的期间）");
    }
    return new Period("QUARTERLY", start, start.plusMonths(3).minusDays(1));
  }

  /** 供应商快照：取自已交付事实里出现过的供应商。 */
  private SupplierSnapshot requireSupplier(UUID supplierRefId) {
    Record r =
        sql.fetchOne(
            "select supplier_ref_id, supplier_code, supplier_name from purchase_arrival_tracking"
                + " where tenant_id = ?::uuid and supplier_ref_id = ?::uuid"
                + " order by created_at desc limit 1",
            currentTenant(),
            supplierRefId);
    if (r == null) {
      r =
          sql.fetchOne(
              "select supplier_ref_id, supplier_code, supplier_name from supplier_claim"
                  + " where tenant_id = ?::uuid and supplier_ref_id = ?::uuid"
                  + " order by created_at desc limit 1",
              currentTenant(),
              supplierRefId);
    }
    if (r == null) {
      throw badRequest(
          "该供应商在**已交付事实**里没有任何记录（6.3.2.2 到货跟踪单 / 11.2.3.3 索赔单里都没有）："
              + "没有事实就没有对账基数，本模块不会替它编一份");
    }
    return new SupplierSnapshot(
        r.get("supplier_ref_id", UUID.class),
        nullSafe(r.get("supplier_code", String.class)),
        nullSafe(r.get("supplier_name", String.class)));
  }

  /** 解析汇率快照入参。 */
  private Map<String, FxRate> parseFxRates(List<ReconciliationFxRateInput> inputs) {
    Map<String, FxRate> rates = new HashMap<>();
    if (inputs == null) {
      return rates;
    }
    for (ReconciliationFxRateInput input : inputs) {
      if (input == null) {
        continue;
      }
      String code = currency(input.currency());
      BigDecimal rate = input.rate();
      if (rate == null || rate.signum() <= 0) {
        throw badRequest("汇率快照必须大于 0：" + code);
      }
      if (!isSet(input.source())) {
        throw badRequest("汇率快照必须写来源：" + code + "（快照要能追到出处）");
      }
      rates.put(code, new FxRate(rate.setScale(FX_SCALE, RoundingMode.HALF_UP), input.source().trim()));
    }
    return rates;
  }

  /** 解析一行要用的汇率：同币种 ⇒ 1:1（口径）；跨币种 ⇒ 必须给汇率与来源。 */
  private FxRate resolveFx(String code, String statementCurrency, BigDecimal rate, String source) {
    if (code.equals(statementCurrency)) {
      return FxRate.identity();
    }
    if (rate == null) {
      throw badRequest(
          "币种 "
              + code
              + " 与对账单币种 "
              + statementCurrency
              + " 不同，必须提供汇率快照：跨币种不能按 1:1 折算");
    }
    if (rate.signum() <= 0) {
      throw badRequest("汇率快照必须大于 0");
    }
    if (!isSet(source)) {
      throw badRequest("跨币种的汇率快照必须写来源");
    }
    return new FxRate(rate.setScale(FX_SCALE, RoundingMode.HALF_UP), source.trim());
  }

  private void requireStatus(String actual, ReconciliationStatus expected, String what) {
    if (!actual.equals(expected.name())) {
      throw badRequest(
          "当前状态「"
              + statusLabel(actual)
              + "」不能"
              + what
              + "（该动作要求「"
              + expected.label()
              + "」）");
    }
  }

  private static String statusLabel(String status) {
    if (status == null) {
      return "";
    }
    try {
      return ReconciliationStatus.valueOf(status).label();
    } catch (IllegalArgumentException e) {
      return status;
    }
  }

  private static String periodTypeLabel(String type) {
    if (type == null) {
      return "";
    }
    try {
      return ReconciliationPeriodType.valueOf(type).label();
    } catch (IllegalArgumentException e) {
      return type;
    }
  }

  private static String lineTypeLabel(String type) {    if (type == null) {
      return "";
    }
    try {
      return ReconciliationLineType.valueOf(type).label();
    } catch (IllegalArgumentException e) {
      return type;
    }
  }

  private static String sourceTypeLabel(String type) {
    if (type == null) {
      return "";
    }
    try {
      return ReconciliationSourceType.valueOf(type).label();
    } catch (IllegalArgumentException e) {
      return type;
    }
  }

  private static String deductionTypeLabel(String type) {
    if (type == null) {
      return "";
    }
    try {
      return DeductionType.valueOf(type).label();
    } catch (IllegalArgumentException e) {
      return type;
    }
  }

  private static String discrepancyTypeLabel(String type) {
    if (type == null) {
      return "";
    }
    try {
      return DiscrepancyType.valueOf(type).label();
    } catch (IllegalArgumentException e) {
      return type;
    }
  }

  private static String discrepancyStatusLabel(String status) {
    if (status == null) {
      return "";
    }
    try {
      return DiscrepancyStatus.valueOf(status).label();
    } catch (IllegalArgumentException e) {
      return status;
    }
  }

  private static String qualityStatusLabel(String status) {
    if (status == null) {
      return "";
    }
    try {
      return QualityDeductionStatus.valueOf(status).label();
    } catch (IllegalArgumentException e) {
      return status;
    }
  }

  private static String supplierResultLabel(String result) {
    if (result == null) {
      return "";
    }
    try {
      return SupplierConfirmationResult.valueOf(result).label();
    } catch (IllegalArgumentException e) {
      return result;
    }
  }

  private static String eventTypeLabel(String type) {
    if (type == null) {
      return "";
    }
    return switch (type) {
      case "CREATED" -> "发起对账";
      case "STATUS" -> "状态变更";
      case "SEND" -> "发出对账单";
      case "SUPPLIER_CONFIRMATION" -> "录入供应商回执";
      case "DEDUCTION_ADDED" -> "登记代扣代缴费用";
      case "DISCREPANCY_ADDED" -> "追加差异";
      case "DISCREPANCY_HELD" -> "差异挂起";
      case "DISCREPANCY_RESOLVED" -> "差异解决";
      case "REOPENED" -> "重开对账";
      case "CLOSED" -> "关闭对账单";
      default -> type;
    };
  }

  private static String parseStatus(String raw) {
    return parseEnum(ReconciliationStatus.class, raw, "对账单状态").name();
  }

  private static DiscrepancyStatus parseDiscrepancyStatus(String raw) {
    return parseEnum(DiscrepancyStatus.class, raw, "差异状态");
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label) {
    if (raw == null || raw.isBlank()) {
      throw badRequest(label + "不能为空");
    }
    try {
      return Enum.valueOf(type, raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw badRequest(
          label + "取值不合法：" + raw + "（可选："
              + java.util.Arrays.stream(type.getEnumConstants())
                  .map(Enum::name)
                  .reduce((a, b) -> a + " / " + b)
                  .orElse("")
              + "）");
    }
  }

  /** 币种：三位大写字母。 */
  private static String currency(String raw) {
    if (raw == null || raw.isBlank()) {
      throw badRequest("币种不能为空");
    }
    String code = raw.trim().toUpperCase();
    if (!code.matches("^[A-Z]{3}$")) {
      throw badRequest("币种必须是三位大写字母：" + raw);
    }
    return code;
  }

  private static BigDecimal money(BigDecimal value, String label) {
    if (value == null) {
      throw badRequest(label + "不能为空");
    }
    return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }

  /** 数量的 CSV 文本：null 一律给「无数据」—— 空格会被读成「数量是 0」。 */
  private static String quantityText(BigDecimal value) {
    return value == null ? "无数据" : value.stripTrailingZeros().toPlainString();
  }

  private static String shortId() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ReconciliationCapabilitySection section(
      String section, String label, boolean available, String ownerModule, String reason, String landsIn) {
    return new ReconciliationCapabilitySection(section, label, available, ownerModule, reason, landsIn);
  }

  private static ReconciliationRelatedModule related(
      String id, String label, boolean delivered, String ownerModule, String reason, String href) {
    return new ReconciliationRelatedModule(id, label, delivered, ownerModule, reason, href);
  }

  private static ReconciliationCapabilityAction action(
      String code,
      String label,
      boolean allowed,
      String method,
      String path,
      boolean required,
      String precondition,
      String blockingReason,
      String ownerModule,
      String unavailableReason) {
    return new ReconciliationCapabilityAction(
        code, label, allowed, method, path, required, precondition, blockingReason, ownerModule,
        unavailableReason);
  }

  private static ReconciliationBoundary boundary(String boundary, String ownerModule, String reason) {
    return new ReconciliationBoundary(boundary, ownerModule, reason);
  }

  private static ReconciliationAction actionOf(
      String code,
      String label,
      boolean allowed,
      String method,
      String path,
      boolean required,
      String precondition,
      String blockedReason,
      String ownerModule) {
    return new ReconciliationAction(code, label, allowed, method, path, required, precondition, blockedReason);
  }

  // ================================================================ 内部结构

  /** 裁决后的期间。 */
  private record Period(String type, LocalDate start, LocalDate end) {}

  /** 供应商快照。 */
  private record SupplierSnapshot(UUID refId, String code, String name) {}

  /** 汇率快照。 */
  private record FxRate(BigDecimal rate, String source) {
    static FxRate identity() {
      return new FxRate(new BigDecimal("1").setScale(FX_SCALE), "SAME_CURRENCY");
    }
  }

  /** 采集到的一条明细（落库前的中间形态）。 */
  private record CollectedLine(
      String lineType,
      String sourceType,
      UUID sourceRefId,
      String sourceRefCode,
      UUID sourceLineRefId,
      String sourceLineRefCode,
      OffsetDateTime occurredAt,
      BigDecimal quantity,
      String unitCode,
      BigDecimal unitConversion,
      BigDecimal baseQuantity,
      BigDecimal unitPrice,
      String currency,
      BigDecimal amount,
      String deductionType,
      UUID claimId,
      String qualityStatus,
      BigDecimal qualityQuantity,
      String qualityNote,
      String note) {

    /** 已到货应收行（6.3.2.2 到货流水）。 */
    static CollectedLine receipt(Record r) {
      BigDecimal qty = r.get("arrived_qty", BigDecimal.class);
      BigDecimal price = r.get("unit_price", BigDecimal.class);
      BigDecimal amount =
          price == null || qty == null
              ? BigDecimal.ZERO
              : qty.multiply(price).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
      return new CollectedLine(
          "RECEIPT",
          "PURCHASE_ARRIVAL",
          r.get("tracking_id", UUID.class),
          nullSafe(r.get("purchase_order_no", String.class)),
          r.get("receipt_row_id", UUID.class),
          nullSafe(r.get("warehouse_receipt_code", String.class)),
          r.get("arrived_at", OffsetDateTime.class),
          qty,
          // ⚠️ 到货流水的数量与到货跟踪单的单价**同单位**（V235 的金额算式为凭据），
          // 故换算比例恒为 1、换算基数与计价单位一致 —— 记 1 是口径，不是把两个单位凑成一样。
          "AS_RECORDED",
          BigDecimal.ONE,
          qty,
          price,
          r.get("currency", String.class),
          amount,
          null,
          null,
          "UNAVAILABLE",
          null,
          "6.4.2.1 质检单未交付 ⇒ 质检扣量未接入：本行应收数量**尚未扣除**质检不合格量"
              + "（扣量数量为 NULL，不是 0）",
          "到货流水（6.3.2.2）· 到货跟踪单 "
              + nullSafe(r.get("tracking_no", String.class))
              + " · 采购单号 "
              + nullSafe(r.get("purchase_order_no", String.class)));
    }

    /** 索赔冲减行（11.2.3.3 索赔明细）。 */
    static CollectedLine claim(Record r) {
      BigDecimal qty = r.get("claim_qty", BigDecimal.class);
      BigDecimal amount = r.get("claim_amount", BigDecimal.class);
      return new CollectedLine(
          "CLAIM",
          "SUPPLIER_CLAIM",
          r.get("claim_id", UUID.class),
          nullSafe(r.get("claim_no", String.class)),
          r.get("item_id", UUID.class),
          "",
          r.get("created_at", OffsetDateTime.class),
          qty,
          "AS_RECORDED",
          BigDecimal.ONE,
          qty,
          null,
          r.get("claim_currency", String.class),
          amount,
          null,
          r.get("claim_id", UUID.class),
          "NOT_APPLICABLE",
          null,
          "",
          "供应商索赔（11.2.3.3）· 索赔单 "
              + nullSafe(r.get("claim_no", String.class))
              + " · 索赔状态 "
              + nullSafe(r.get("claim_status", String.class)));
    }
  }
}
