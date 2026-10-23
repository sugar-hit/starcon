package com.starcon.finance;

import static com.starcon.finance.SupplierReconciliationModels.*;

import com.starcon.finance.SupplierReconciliationModels.DifferenceResolution;
import com.starcon.finance.SupplierReconciliationModels.DifferenceStatus;
import com.starcon.finance.SupplierReconciliationModels.DifferenceType;
import com.starcon.finance.SupplierReconciliationModels.ReconciliationStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 12.1.2.3 供应商对账（财务侧）。
 *
 * <p>蓝图 12-finance.md：「财务侧供应商对账：核对采购侧对账单（6.5.1）与收货/质检数据，差异处理与确认，最终生成应付依据。」输出：「对账确认结果 + 差异清单 + 应付生成依据。」
 * 加粗句：「<b>财务是最终确认方，采购不能自行确认应付</b>——这是职责分离。」
 *
 * <p><b>本模块的四条设计主线</b>：
 *
 * <ol>
 *   <li><b>采购侧的数据在财务确认前不得变成已确认应付。</b> 上游 6.5.1.1 的采购对账单在本服务里
 *       只有<b>快照</b>；应付单（12.1.2.1）只能由本模块的「财务确认」动作产生，
 *       且必须挂在一条<b>差异已全部裁决</b>的对账单上（DB 触发器钉死）。
 *   <li><b>采购发起 / 财务确认是两个动作、两个权限。</b>
 *       {@code finance.suppliereconciliation.update}（导入 / 核对 / 登记说明）与
 *       {@code finance.suppliereconciliation.confirm}（裁决差异 / 最终确认 / 驳回）分开。
 *   <li><b>依据链自证。</b> 读到的关键值（单号 / 金额 / 数量 / 单价 / 汇率 / 状态 / 收货单号 / 质检三量）
 *       一律<b>快照落库</b>，不靠事后去查上游表里的当前值。
 *   <li><b>币种分开。</b> 汇总一律按币种分组；对账单币种是本模块唯一可加总的币种。
 * </ol>
 *
 * <p><b>跨服务边界</b>：上游事实一律经<b>授权 HTTP API</b>读
 * （{@link SupplierStatementPort} / {@link ArrivalEvidencePort} / {@link QualityInspectionPort}），
 * 不跨库不跨服务 SQL。租户由上游按已认证成员关系解析，端口只转发调用方自己的 bearer
 * ⇒ 隔离是<b>两道</b>（finance 侧 RLS + 上游侧 RLS）。
 */
@Service
public class SupplierReconciliationService {

  private static final Logger logger = LoggerFactory.getLogger(SupplierReconciliationService.class);

  /** 一页最大条数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 金额比较容差（0.01 分）：差不到一分不算差异。 */
  private static final BigDecimal MONEY_EPSILON = new BigDecimal("0.01");

  /** 数量比较容差：差不到 0.000001 不算差异。 */
  private static final BigDecimal QUANTITY_EPSILON = new BigDecimal("0.000001");

  private final DSLContext sql;
  private final SupplierStatementPort statements;
  private final ArrivalEvidencePort arrivals;
  private final QualityInspectionPort inspections;

  public SupplierReconciliationService(
      DSLContext sql,
      SupplierStatementPort statements,
      ArrivalEvidencePort arrivals,
      QualityInspectionPort inspections) {
    this.sql = sql;
    this.statements = statements;
    this.arrivals = arrivals;
    this.inspections = inspections;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：逐条报「有没有、依据是什么、缺了会怎样」。
   *
   * <p>⚠️ 页面必须能分开两件事：<b>「上游未交付」</b>与<b>「上游已交付但本模块没接」</b>。
   * 把两者都写成「不可用」，运维会以为要去催上游。
   */
  public FinanceSupplierReconciliationCapabilities capabilities() {
    var capabilities = new ArrayList<FinanceSupplierReconciliationCapability>();
    var gaps = new ArrayList<FinanceSupplierReconciliationGap>();

    capabilities.add(
        new FinanceSupplierReconciliationCapability(
            "12.1.2.3",
            "财务侧供应商对账（导入 · 核对 · 差异处理 · 确认）",
            true,
            "finance_supplier_reconciliation 等七张表（V349）",
            "",
            "数据落 PostgreSQL 16，查询走 jOOQ 显式 SQL，全链路 BigDecimal + NUMERIC，无浮点。"
                + "对账单头 / 明细行 / 质检证据 / 差异清单 / 差异说明 / 事件流 / 应付依据七张表"
                + "全部 tenant_id NOT NULL + ENABLE/FORCE RLS。"));
    capabilities.add(
        new FinanceSupplierReconciliationCapability(
            "12.1.2.3",
            "职责分离：财务是最终确认方",
            true,
            "两个动作 + 两个权限码 + 数据层守卫",
            "",
            "**采购侧不能自行确认应付**：① 导入 / 核对 / 登记差异说明走"
                + " finance.suppliereconciliation.update；② 裁决差异 / 最终确认 / 驳回走"
                + "**独立权限** finance.suppliereconciliation.confirm。"
                + "数据层再钉一层：对账单置为 CONFIRMED 必须恰好有一条应付依据、"
                + "金额一致、且没有未裁决差异（V349 触发器）。"
                + "⇒ 采购侧的对账单在财务确认前**不可能**变成已确认应付。"));
    capabilities.add(
        new FinanceSupplierReconciliationCapability(
            "6.5.1.1",
            "采购侧对账单 → 财务侧对账（上游）",
            true,
            "经 trade 的 GET /trade/purchase-reconciliations 读（授权 HTTP API，不跨库）",
            "",
            "上游对账单带供应商、期间、对账单币种、逐行数量 / 单价 / 汇率快照、"
                + "代扣代缴费用与索赔、供应商确认回执。"
                + "**只导入供应商已确认（CONFIRMED / CLOSED）的对账单** —— "
                + "草稿与「已发送未回执」都不是一份最终的对账单，导进来只会让财务对着草稿做核对。"));
    capabilities.add(
        new FinanceSupplierReconciliationCapability(
            "6.3.2.2",
            "到货流水 → 收货核对（上游）",
            true,
            "经 trade 的 GET /trade/purchase-arrivals/{id} 读（授权 HTTP API，不跨库）",
            "",
            "对账单每一行都指着一条到货流水（source_ref_id / source_line_ref_id）："
                + "拿它核「实到数量」与「仓库收货单号」。"
                + "**找不到对应流水 ⇒ 记一条「到货依据缺失」差异**，"
                + "绝不按采购侧数量直接认定一致。"));
    capabilities.add(
        new FinanceSupplierReconciliationCapability(
            "6.4.2.1",
            "质检单 → 质检扣量核对（上游）",
            true,
            "经 trade 的 GET /trade/quality-inspections 读（授权 HTTP API，不跨库）",
            "",
            "质检单按**仓库收货单号**关联到货流水。扣量口径用上游自己的"
                + " grossDeductedQuantity（= 不合格量 + 让步接收量，DB 层有 CHECK 保证），"
                + "**不自己发明一套扣量规则**。"
                + "⚠️ 采购侧 6.5.1.1 自己**没有**接入质检（它的 qualityDeductionStatus 恒为 UNAVAILABLE），"
                + "所以质检扣量差异**由财务侧第一次算出来** —— 这正是本模块存在的意义。"));
    capabilities.add(
        new FinanceSupplierReconciliationCapability(
            "12.1.2.1",
            "应付单生成（下游）",
            true,
            "写本服务的 finance_payable（V340 的表与触发器全部生效）",
            "",
            "财务确认时按「对账单口径 + 裁决调整」生成一张应付单，"
                + "来源 = SUPPLIER_STATEMENT、来源单号 = **本对账单号**（V340 的幂等键）"
                + "⇒ 同一份对账单重复确认**不会造出第二张应付**。"
                + "写入严格遵守 V340 守卫：先建 DRAFT 单头（**不填单头金额**）→ 落明细行"
                + "（金额由触发器 guard_finance_payable_amount 求和派生）→ DRAFT→OPEN 确认。"));
    capabilities.add(
        new FinanceSupplierReconciliationCapability(
            "12.1.2.1",
            "应付确认政策（生成应付的前置闸门）",
            true,
            "读 finance_payable_policy（来源 = SUPPLIER_STATEMENT）",
            "",
            "**没有启用的（SUPPLIER_STATEMENT × PAYABLE）政策 ⇒ 一张应付都产生不了**，"
                + "错误消息写清「先在 12.1.2.1 的应付政策里启用它」。"
                + "什么业务确认为应付由租户决定，系统不替用户默认一个。"));
    capabilities.add(
        new FinanceSupplierReconciliationCapability(
            "12.2.2.1",
            "付款（应付的下游）",
            false,
            "写路径未开放：本模块只生成应付单，不付款",
            "12.2.2.1",
            "付款属 12.2.2.1（未交付）。本模块**不提供**任何改已付金额的端点，"
                + "应付单的已付金额恒为 0（这是事实：钱确实还没付）。"));
    capabilities.add(
        new FinanceSupplierReconciliationCapability(
            "12.3.2",
            "平台结算对账（12.3 结算与对账）",
            false,
            "不接：本模块只管供应商对账",
            "12.3.2",
            "平台结算与交易明细的自动匹配属 12.3.2（未交付），"
                + "与本模块的供应商对账是**两回事**，不共用一套口径。"));

    gaps.add(
        new FinanceSupplierReconciliationGap(
            "12.2.2.1",
            "付款单与审批",
            "finance",
            false,
            "12.2 收付款",
            "对账确认后生成的应付单要靠 12.2.2.1 付出去；该模块未交付 ⇒ 应付停在「未付」。"));
    gaps.add(
        new FinanceSupplierReconciliationGap(
            "6.5.1.1",
            "上游对账单的「财务已确认」回写",
            "trade",
            false,
            "6 采购与供应",
            "上游的 finance_handoff_status 恒为 PENDING_FINANCE_SERVICE（表级 CHECK 只允许这一个值），"
                + "上游**没有任何路径**能自己写成「财务已确认」。"
                + "本模块**不改上游的表**：财务侧的确认结果留在本服务，"
                + "上游要看到它需另开授权 API / event（未交付）。"));
    gaps.add(
        new FinanceSupplierReconciliationGap(
            "12.1.2.1",
            "12.1.2.1 能力声明里的「12.1.2.3 未交付」",
            "finance",
            false,
            "12.1 应收与资金",
            "12.1.2.1 的应付政策视图里 SUPPLIER_STATEMENT 仍标着「未交付 / 不可人工录入」—— "
                + "那是 12.1.2.1 自己写的文案，本模块交付后它已过期；"
                + "**该文案由 12.1.2.1 的 owner 更新**，本模块不改别人的端点与断言。"
                + "事实口径以本模块的能力声明为准。"));

    return new FinanceSupplierReconciliationCapabilities(List.copyOf(capabilities), List.copyOf(gaps));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉：全部来自**真实数据**（distinct 查询），不是写死的词表。 */
  public FinanceSupplierReconciliationOptions options() {
    var statuses =
        sql.fetch("select distinct status from finance_supplier_reconciliation order by status")
            .map(r -> r.get(0, String.class));
    var upstreamStatuses =
        sql.fetch(
                "select distinct upstream_status from finance_supplier_reconciliation"
                    + " where upstream_status <> '' order by upstream_status")
            .map(r -> r.get(0, String.class));
    var currencies =
        sql.fetch(
                "select distinct statement_currency from finance_supplier_reconciliation order by statement_currency")
            .map(r -> r.get(0, String.class));
    var suppliers =
        sql.fetch(
                "select distinct supplier_ref_id, supplier_code, supplier_name"
                    + " from finance_supplier_reconciliation order by supplier_name, supplier_ref_id")
            .map(
                r ->
                    new FinanceSupplierReconciliationSupplierOption(
                        r.get(0, UUID.class),
                        r.get(1, String.class),
                        r.get(2, String.class)));
    return new FinanceSupplierReconciliationOptions(
        statuses, upstreamStatuses, currencies, suppliers);
  }

  // ================================================================ 列表

  /**
   * 对账单列表：真分页 + 真筛选 + 真排序。
   *
   * <p>⚠️ **汇总覆盖的是整个筛选结果集，不是当前页**：分页一变汇总就变的话，
   * 「翻到第二页看到总额变小了」会被读成「数据丢了」。
   */
  public FinanceSupplierReconciliationPage page(
      String status, String upstreamStatus, UUID supplierRefId, String currency, String keyword,
      int page, int size) {
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    if (status != null && !status.isBlank()) {
      where.add("status = ?");
      binds.add(status);
    }
    if (upstreamStatus != null && !upstreamStatus.isBlank()) {
      where.add("upstream_status = ?");
      binds.add(upstreamStatus);
    }
    if (supplierRefId != null) {
      where.add("supplier_ref_id = ?");
      binds.add(supplierRefId);
    }
    if (currency != null && !currency.isBlank()) {
      where.add("statement_currency = ?");
      binds.add(currency);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.add(
          "(recon_no ilike ? or upstream_recon_no ilike ? or supplier_name ilike ?"
              + " or supplier_code ilike ? or payable_no ilike ?)");
      String like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from finance_supplier_reconciliation" + clause, binds.toArray())
            .get(0, Long.class);
    List<FinanceSupplierReconciliationRow> items =
        sql.fetch(
                "select * from finance_supplier_reconciliation"
                    + clause
                    + " order by period_end desc, id limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::row);

    var summaries = summaryByCurrency(where, binds);
    long openDifferenceCount =
        summaries.stream().mapToLong(FinanceSupplierReconciliationCurrencySummary::openDifferenceCount).sum();
    return new FinanceSupplierReconciliationPage(
        items, p, s, total, summaries, openDifferenceCount,
        "汇总覆盖整个筛选结果集（不是当前页）；金额一律按币种分开，跨币种不相加。");
  }

  /** 按币种分开的汇总。 */
  private List<FinanceSupplierReconciliationCurrencySummary> summaryByCurrency(
      List<String> where, List<Object> binds) {
    var clauses = new ArrayList<String>();
    clauses.add("status <> 'REJECTED'");
    clauses.addAll(where);
    return sql.fetch(
            "select statement_currency,"
                + " sum(statement_received_amount - statement_deduction_amount - statement_claim_amount) as statement_amount,"
                + " sum(finance_confirmed_amount) as finance_amount,"
                + " sum(finance_adjusted_amount) as adjustment_amount,"
                + " count(*) as cnt,"
                + " sum(open_difference_count) as open_diff"
                + " from finance_supplier_reconciliation where "
                + String.join(" and ", clauses)
                + " group by statement_currency order by statement_currency",
            binds.toArray())
        .map(
            r ->
                new FinanceSupplierReconciliationCurrencySummary(
                    r.get("statement_currency", String.class),
                    r.get("statement_amount", BigDecimal.class),
                    r.get("finance_amount", BigDecimal.class),
                    r.get("adjustment_amount", BigDecimal.class),
                    r.get("cnt", Long.class),
                    r.get("open_diff", Long.class)));
  }

  // ================================================================ 详情

  /** 对账单详情：头 + 明细行 + 质检证据 + 差异 + 说明 + 事件流 + 应付依据。 */
  public FinanceSupplierReconciliationDetail detail(UUID id) {
    var head = requireHead(id);
    var lines =
        sql.fetch(
                "select * from finance_supplier_reconciliation_line where recon_id = ? order by line_no, id",
                id)
            .map(this::lineRow);
    var inspectionRows =
        sql.fetch(
                "select * from finance_supplier_reconciliation_inspection where recon_id = ?"
                    + " order by line_id, inspection_no, id",
                id)
            .map(
                r ->
                    new FinanceSupplierReconciliationInspectionRow(
                        r.get("id", UUID.class),
                        r.get("inspection_id", UUID.class),
                        r.get("inspection_no", String.class),
                        r.get("receipt_code", String.class),
                        r.get("sku_code", String.class),
                        r.get("arrived_quantity", BigDecimal.class),
                        r.get("qualified_quantity", BigDecimal.class),
                        r.get("defective_quantity", BigDecimal.class),
                        r.get("concession_quantity", BigDecimal.class),
                        r.get("deducted_quantity", BigDecimal.class),
                        r.get("deducted_amount", BigDecimal.class),
                        r.get("currency_code", String.class),
                        r.get("inspection_status", String.class),
                        r.get("inspection_result", String.class),
                        r.get("completed_at", OffsetDateTime.class)));
    var differences =
        sql.fetch(
                "select * from finance_supplier_reconciliation_difference where recon_id = ?"
                    + " order by line_no, id",
                id)
            .map(this::differenceRow);
    var explanations =
        sql.fetch(
                "select * from finance_supplier_reconciliation_explanation where recon_id = ?"
                    + " order by created_at, id",
                id)
            .map(
                r ->
                    new FinanceSupplierReconciliationExplanationRow(
                        r.get("id", UUID.class),
                        r.get("difference_id", UUID.class),
                        r.get("explanation", String.class),
                        r.get("provided_role", String.class),
                        r.get("provided_by", UUID.class),
                        r.get("evidence_ref", String.class),
                        r.get("created_at", OffsetDateTime.class)));
    var events =
        sql.fetch(
                "select * from finance_supplier_reconciliation_event where recon_id = ?"
                    + " order by occurred_at, id",
                id)
            .map(
                r ->
                    new FinanceSupplierReconciliationEventRow(
                        r.get("id", UUID.class),
                        r.get("event_type", String.class),
                        r.get("from_status", String.class),
                        r.get("to_status", String.class),
                        r.get("reason", String.class),
                        r.get("actor_id", UUID.class),
                        r.get("actor_role", String.class),
                        r.get("occurred_at", OffsetDateTime.class)));
    var basis =
        sql.fetchOne(
            "select * from finance_supplier_reconciliation_payable where recon_id = ?", id);
    var payableBasis =
        basis == null
            ? null
            : new FinanceSupplierReconciliationPayableBasisRow(
                basis.get("id", UUID.class),
                basis.get("payable_id", UUID.class),
                basis.get("payable_no", String.class),
                basis.get("amount", BigDecimal.class),
                basis.get("currency", String.class),
                basis.get("statement_amount", BigDecimal.class),
                basis.get("adjustment_amount", BigDecimal.class),
                basis.get("basis_note", String.class),
                basis.get("generated_by", UUID.class),
                basis.get("generated_at", OffsetDateTime.class));
    return new FinanceSupplierReconciliationDetail(
        row(head),
        lines,
        inspectionRows,
        differences,
        explanations,
        events,
        payableBasis,
        actions(head.get("status", String.class)),
        "对账单里同时留着**采购侧口径**与**财务侧口径**：差额由差异清单解释，"
            + "确认金额 = 采购侧口径 + 已裁决的调整额。");
  }

  /** 当前可执行动作（每个动作都带为什么不能点，界面要能说清）。 */
  private List<String> actions(String status) {
    var out = new ArrayList<String>();
    switch (status) {
      case "IMPORTED" -> {
        out.add("核对收货与质检（POST /{id}/match）");
        out.add("登记差异说明（POST /{id}/differences/{differenceId}/explanations）");
        out.add("财务驳回（POST /{id}/reject，需要 finance.suppliereconciliation.confirm）");
      }
      case "MATCHED", "DIFFERENCES" -> {
        out.add("登记差异说明（POST /{id}/differences/{differenceId}/explanations）");
        out.add("裁决差异（POST /{id}/differences/{differenceId}/resolve，需要 finance.suppliereconciliation.confirm）");
        out.add("财务确认并生成应付（POST /{id}/confirm，需要 finance.suppliereconciliation.confirm）");
        out.add("财务驳回（POST /{id}/reject，需要 finance.suppliereconciliation.confirm）");
      }
      case "CONFIRMED" -> out.add("已生成应付依据并冻结：只能新建一张对账单");
      case "REJECTED" -> out.add("已驳回并冻结：只能新建一张对账单");
      default -> out.add("状态未知，请联系管理员");
    }
    return out;
  }

  /** 明细行列表（真分页 + 行类型筛选）。 */
  public FinanceSupplierReconciliationLinePage lines(UUID id, String lineType, int page, int size) {
    requireHead(id);
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("recon_id = ?");
    binds.add(id);
    if (lineType != null && !lineType.isBlank()) {
      where.add("line_type = ?");
      binds.add(lineType);
    }
    String clause = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne(
                "select count(*) from finance_supplier_reconciliation_line" + clause, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
                "select * from finance_supplier_reconciliation_line"
                    + clause
                    + " order by line_no, id limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::lineRow);
    var summary =
        sql.fetchOne(
            "select count(*) filter (where line_type = 'RECEIPT') as receipt_lines,"
                + " count(*) filter (where line_type = 'CLAIM') as claim_lines,"
                + " count(*) filter (where line_type = 'DEDUCTION') as deduction_lines,"
                + " coalesce(sum(statement_amount),0) as statement_amount,"
                + " sum(finance_amount) as finance_amount"
                + " from finance_supplier_reconciliation_line where recon_id = ?",
            id);
    return new FinanceSupplierReconciliationLinePage(
        rows,
        p,
        s,
        total,
        summary.get("receipt_lines", Long.class),
        summary.get("claim_lines", Long.class),
        summary.get("deduction_lines", Long.class),
        summary.get("statement_amount", BigDecimal.class),
        summary.get("finance_amount", BigDecimal.class),
        "statement_amount 是采购侧口径（对账单币种），finance_amount 是财务侧核对值；"
            + "两者之差由差异清单解释。");
  }

  /** 差异清单（真分页 + 状态筛选）。 */
  public FinanceSupplierReconciliationDifferencePage differences(
      UUID id, String status, int page, int size) {
    requireHead(id);
    int p = Math.max(0, page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("recon_id = ?");
    binds.add(id);
    if (status != null && !status.isBlank()) {
      where.add("status = ?");
      binds.add(status);
    }
    String clause = " where " + String.join(" and ", where);
    long total =
        sql.fetchOne(
                "select count(*) from finance_supplier_reconciliation_difference" + clause,
                binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
                "select * from finance_supplier_reconciliation_difference"
                    + clause
                    + " order by line_no, id limit ? offset ?",
                concat(binds, s, p * s))
            .map(this::differenceRow);
    var counts =
        sql.fetchOne(
            "select count(*) filter (where status = 'OPEN') as open_count,"
                + " count(*) filter (where status = 'EXPLAINED') as explained_count,"
                + " count(*) filter (where status = 'RESOLVED') as resolved_count"
                + " from finance_supplier_reconciliation_difference where recon_id = ?",
            id);
    return new FinanceSupplierReconciliationDifferencePage(
        rows,
        p,
        s,
        total,
        counts.get("open_count", Long.class),
        counts.get("explained_count", Long.class),
        counts.get("resolved_count", Long.class),
        "差异必须**逐条裁决**才能确认对账单：APPLIED = 按财务口径调整应付，KEPT = 沿用采购侧口径。");
  }

  // ================================================================ 导入（采购发起）

  /**
   * 从上游 **6.5.1.1 采购对账**导入对账单（幂等）。
   *
   * <p>⚠️ **幂等**：幂等键 = 上游对账单 id（{@code UNIQUE(tenant_id, upstream_recon_id)}），
   * 重复导入命中同一条，不会长出第二张对账单。
   *
   * <p>⚠️ **只导入供应商已确认（CONFIRMED / CLOSED）的对账单**：草稿与「已发送未回执」
   * 都不是最终对账单，导进来只会让财务对着草稿做核对。
   *
   * <p>⚠️ **上游读不到就整体失败**（503），绝不返回「导入 0 条」冒充「上游没有对账单」。
   */
  @Transactional
  public FinanceSupplierReconciliationImportResult importFromPurchase(
      String bearer, AccessService.Access actor) {
    long scanned = 0;
    long imported = 0;
    long refreshed = 0;
    long skipped = 0;
    boolean truncated = false;
    var rows = new ArrayList<FinanceSupplierReconciliationRow>();
    var warnings = new ArrayList<String>();

    for (int page = 0; page < SupplierStatementPort.MAX_PAGES; page++) {
      var upstream = statements.statements(page, bearer);
      if (upstream.rows().isEmpty()) break;
      scanned += upstream.rows().size();
      for (var statement : upstream.rows()) {
        if (!SupplierStatementPort.importable(statement.status())) {
          skipped++;
          continue;
        }
        var existing =
            sql.fetchOne(
                "select * from finance_supplier_reconciliation where upstream_recon_id = ?",
                statement.id());
        if (existing != null) {
          var status = existing.get("status", String.class);
          if (ReconciliationStatus.valueOf(status).terminal()) {
            skipped++;
            warnings.add(
                "上游对账单 "
                    + statement.reconNo()
                    + " 已经"
                    + ReconciliationStatus.valueOf(status).label()
                    + "，不覆盖（终态不可改）");
            continue;
          }
          refresh(existing.get("id", UUID.class), statement, bearer, actor, warnings);
          refreshed++;
          rows.add(row(sql.fetchOne("select * from finance_supplier_reconciliation where id = ?", existing.get("id", UUID.class))));
          continue;
        }
        var detail = statements.statement(statement.id(), bearer);
        var id = createFromUpstream(detail, actor);
        imported++;
        rows.add(row(sql.fetchOne("select * from finance_supplier_reconciliation where id = ?", id)));
      }
      if (upstream.rows().size() < SupplierStatementPort.PAGE_SIZE) break;
      if (page == SupplierStatementPort.MAX_PAGES - 1) truncated = true;
    }
    if (truncated) {
      warnings.add(
          "上游翻页触到上限（"
              + (SupplierStatementPort.MAX_PAGES * SupplierStatementPort.PAGE_SIZE)
              + " 张），本次只扫描了前 "
              + scanned
              + " 张");
    }
    if (!warnings.isEmpty()) {
      logger.warn("[finance] 导入供应商对账有 {} 条未进入：{}", warnings.size(), warnings);
    }
    return new FinanceSupplierReconciliationImportResult(
        scanned, imported, refreshed, skipped, truncated, rows, warnings);
  }

  /** 新建一张财务侧对账单（头 + 行快照）。 */
  private UUID createFromUpstream(
      SupplierStatementPort.StatementDetail detail, AccessService.Access actor) {
    var head = detail.head();
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    String reconNo = "SR-" + head.periodEnd().toString().replace("-", "") + "-" + shortId();
    sql.execute(
        "insert into finance_supplier_reconciliation(id,tenant_id,recon_no,upstream_recon_id,"
            + " upstream_recon_no,supplier_ref_id,supplier_code,supplier_name,period_type,"
            + " period_start,period_end,statement_currency,status,upstream_status,"
            + " statement_received_amount,statement_deduction_amount,statement_claim_amount,"
            + " statement_suggested_amount,statement_total_quantity,upstream_supplier_confirmed_at,"
            + " upstream_supplier_reference,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?::date,?::date,?,'IMPORTED',?,?,?,?,?,?,?::timestamptz,?,"
            + " ?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        reconNo,
        head.id(),
        nullSafe(head.reconNo()),
        head.supplierRefId(),
        nullSafe(head.supplierCode()),
        nullSafe(head.supplierName()),
        head.periodType(),
        head.periodStart(),
        head.periodEnd(),
        head.statementCurrency(),
        nullSafe(head.status()),
        money(head.receivedAmount()),
        money(head.deductionAmount()),
        money(head.claimAmount()),
        money(head.suggestedPayableAmount()),
        quantity(head.totalQuantity()),
        head.supplierConfirmedAt(),
        nullSafe(head.supplierReference()),
        now,
        now);
    writeEvent(actor.tenantId(), id, "IMPORTED", "", "IMPORTED",
        "导入采购侧对账单 " + nullSafe(head.reconNo()) + "（上游状态 " + nullSafe(head.status()) + "）",
        actor.userId(), "FINANCE", now);
    upsertLines(id, detail.lines(), actor, new ArrayList<>());
    updateLineCounts(id);
    return id;
  }

  /**
   * 刷新一张已导入但尚未确认的对账单（上游变了才刷）。
   *
   * <p>⚠️ 刷新**不删行**：已被差异引用的行删了会连带删掉差异与说明（举证凭据）。
   * 上游真的删了行时，那一行留在本地并标一句「上游已无此行」。
   */
  private void refresh(
      UUID id,
      SupplierStatementPort.StatementRow statement,
      String bearer,
      AccessService.Access actor,
      List<String> warnings) {
    var detail = statements.statement(statement.id(), bearer);
    sql.execute(
        "update finance_supplier_reconciliation set upstream_recon_no=?,supplier_code=?,"
            + " supplier_name=?,upstream_status=?,statement_received_amount=?,"
            + " statement_deduction_amount=?,statement_claim_amount=?,statement_suggested_amount=?,"
            + " statement_total_quantity=?,upstream_supplier_confirmed_at=?::timestamptz,"
            + " upstream_supplier_reference=?,updated_at=?::timestamptz,version=version+1"
            + " where id=?",
        nullSafe(statement.reconNo()),
        nullSafe(statement.supplierCode()),
        nullSafe(statement.supplierName()),
        nullSafe(statement.status()),
        money(statement.receivedAmount()),
        money(statement.deductionAmount()),
        money(statement.claimAmount()),
        money(statement.suggestedPayableAmount()),
        quantity(statement.totalQuantity()),
        statement.supplierConfirmedAt(),
        nullSafe(statement.supplierReference()),
        OffsetDateTime.now(ZoneOffset.UTC),
        id);
    upsertLines(id, detail.lines(), actor, warnings);
    updateLineCounts(id);
    writeEvent(actor.tenantId(), id, "REFRESHED", "IMPORTED", "IMPORTED",
        "刷新上游对账单快照 " + nullSafe(statement.reconNo()), actor.userId(), "FINANCE",
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  /** 落 / 更新上游明细行（按上游行 id 对齐）。 */
  private void upsertLines(
      UUID reconId,
      List<SupplierStatementPort.StatementLine> lines,
      AccessService.Access actor,
      List<String> warnings) {
    for (var line : lines) {
      var existing =
          sql.fetchOne(
              "select id, line_no from finance_supplier_reconciliation_line"
                  + " where recon_id = ? and upstream_line_id = ?",
              reconId,
              line.id());
      if (existing != null) {
        sql.execute(
            "update finance_supplier_reconciliation_line set line_type=?,source_type=?,"
                + " source_ref_id=?,source_ref_code=?,source_line_ref_id=?,source_line_ref_code=?,"
                + " occurred_at=?::timestamptz,quantity=?,unit_code=?,unit_conversion=?,base_quantity=?,"
                + " unit_price=?,currency=?,fx_rate=?,fx_rate_source=?,fx_rate_at=?::timestamptz,"
                + " amount=?,statement_amount=?,deduction_type=?,claim_id=?,upstream_quality_status=?,"
                + " upstream_quality_quantity=?,upstream_quality_note=?,updated_at=?::timestamptz"
                + " where id=?",
            line.lineType(),
            nullSafe(line.sourceType()),
            line.sourceRefId(),
            nullSafe(line.sourceRefCode()),
            line.sourceLineRefId(),
            nullSafe(line.sourceLineRefCode()),
            line.occurredAt(),
            quantityOrNull(line.quantity()),
            line.unitCode(),
            line.unitConversion(),
            line.baseQuantity(),
            line.unitPrice(),
            line.currency(),
            line.fxRate(),
            nullSafe(line.fxRateSource()),
            line.fxRateAt(),
            money(line.amount()),
            money(line.statementAmount()),
            line.deductionType(),
            line.claimId(),
            nullSafe(line.qualityDeductionStatus()),
            line.qualityDeductionQuantity(),
            nullSafe(line.qualityDeductionNote()),
            OffsetDateTime.now(ZoneOffset.UTC),
            existing.get("id", UUID.class));
        continue;
      }
      // 行号冲突（上游改过行号）⇒ 另起一个序号，并如实报出来。
      int lineNo = line.lineNo();
      var clash =
          sql.fetchOne(
              "select id from finance_supplier_reconciliation_line where recon_id = ? and line_no = ?",
              reconId,
              lineNo);
      if (clash != null) {
        int next =
            sql.fetchOne(
                    "select coalesce(max(line_no),0) as m from finance_supplier_reconciliation_line"
                        + " where recon_id = ?",
                    reconId)
                .get("m", Integer.class);
        lineNo = next + 1;
        warnings.add(
            "上游对账单的行号 " + line.lineNo() + " 与本地行号冲突，本地改用 " + lineNo + " 号");
      }
      sql.execute(
          "insert into finance_supplier_reconciliation_line(id,tenant_id,recon_id,line_no,"
              + " upstream_line_id,line_type,source_type,source_ref_id,source_ref_code,"
              + " source_line_ref_id,source_line_ref_code,occurred_at,quantity,unit_code,"
              + " unit_conversion,base_quantity,unit_price,currency,fx_rate,fx_rate_source,fx_rate_at,"
              + " amount,statement_amount,deduction_type,claim_id,upstream_quality_status,"
              + " upstream_quality_quantity,upstream_quality_note,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,"
              + " ?,?,?,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          actor.tenantId(),
          reconId,
          lineNo,
          line.id(),
          line.lineType(),
          nullSafe(line.sourceType()),
          line.sourceRefId(),
          nullSafe(line.sourceRefCode()),
          line.sourceLineRefId(),
          nullSafe(line.sourceLineRefCode()),
          line.occurredAt(),
          quantityOrNull(line.quantity()),
          line.unitCode(),
          line.unitConversion(),
          line.baseQuantity(),
          line.unitPrice(),
          line.currency(),
          line.fxRate(),
          nullSafe(line.fxRateSource()),
          line.fxRateAt(),
          money(line.amount()),
          money(line.statementAmount()),
          line.deductionType(),
          line.claimId(),
          nullSafe(line.qualityDeductionStatus()),
          line.qualityDeductionQuantity(),
          nullSafe(line.qualityDeductionNote()),
          OffsetDateTime.now(ZoneOffset.UTC),
          OffsetDateTime.now(ZoneOffset.UTC));
    }
  }

  // ================================================================ 核对（收货 + 质检）

  /**
   * 核对收货与质检，产出差异清单。
   *
   * <p>⚠️ **只对「到货应收行」核**（{@code line_type='RECEIPT'}）：索赔行与代扣代缴费用行
   * 没有收货事实可核，财务侧按采购侧口径认（它们的差异由上游采购侧自己处理）。
   *
   * <p>⚠️ **已有裁决的差异不重算**：裁决是举证凭据，改了它就改了已发生的财务决定。
   * 有已裁决差异时本动作直接拒绝，并提示新建对账单。
   */
  @Transactional
  public FinanceSupplierReconciliationMatchResult match(
      UUID id, String bearer, AccessService.Access actor) {
    var head = requireHead(id);
    var status = ReconciliationStatus.valueOf(head.get("status", String.class));
    if (status.terminal()) {
      throw badRequest("对账单已是终态（" + status.label() + "），不可再核对");
    }
    var resolved =
        sql.fetchOne(
                "select count(*) from finance_supplier_reconciliation_difference"
                    + " where recon_id = ? and status = 'RESOLVED'",
                id)
            .get(0, Long.class);
    if (resolved > 0) {
      throw badRequest(
          "该对账单已有 "
              + resolved
              + " 条裁决过的差异，裁决是举证凭据不能重算：如需重新核对请让采购侧重开一张对账单"
              + "（6.5.1.1 的 reopen 流程）");
    }

    var notes = new ArrayList<String>();
    int matched = 0;
    int unmatched = 0;
    int raised = 0;

    var lines =
        sql.fetch(
            "select * from finance_supplier_reconciliation_line where recon_id = ? order by line_no, id",
            id);
    for (var line : lines) {
      String lineType = line.get("line_type", String.class);
      if (!"RECEIPT".equals(lineType)) {
        // 索赔 / 代扣代缴：财务侧按采购侧口径认。
        sql.execute(
            "update finance_supplier_reconciliation_line set receipt_matched=false,"
                + " quality_matched=false, quality_status='NOT_APPLICABLE',"
                + " finance_quantity=?, finance_amount=?, variance_quantity=0, variance_amount=0,"
                + " updated_at=?::timestamptz where id=?",
            quantityOrNull(line.get("quantity", BigDecimal.class)),
            money(line.get("statement_amount", BigDecimal.class)),
            OffsetDateTime.now(ZoneOffset.UTC),
            line.get("id", UUID.class));
        continue;
      }
      var outcome = matchReceiptLine(head, line, bearer, actor, notes);
      if (outcome.matched()) {
        matched++;
      } else {
        unmatched++;
      }
      raised += outcome.differences();
    }

    updateLineCounts(id);
    var diffCount =
        sql.fetchOne(
                "select count(*) from finance_supplier_reconciliation_difference where recon_id = ?", id)
            .get(0, Long.class);
    var openCount =
        sql.fetchOne(
                "select count(*) from finance_supplier_reconciliation_difference"
                    + " where recon_id = ? and status <> 'RESOLVED'",
                id)
            .get(0, Long.class);
    var nextStatus = openCount > 0 ? "DIFFERENCES" : "MATCHED";
    sql.execute(
        "update finance_supplier_reconciliation set status=?, updated_at=?::timestamptz, version=version+1"
            + " where id=?",
        nextStatus,
        OffsetDateTime.now(ZoneOffset.UTC),
        id);
    writeEvent(
        actor.tenantId(),
        id,
        "MATCHED",
        status.name(),
        nextStatus,
        "核对收货与质检：匹配 " + matched + " 行 / 未匹配 " + unmatched + " 行，差异 " + diffCount + " 条",
        actor.userId(),
        "FINANCE",
        OffsetDateTime.now(ZoneOffset.UTC));
    return new FinanceSupplierReconciliationMatchResult(
        id, matched, unmatched, diffCount.intValue(), openCount.intValue(), notes);
  }

  /** 一行到货应收的核对结果。 */
  private record LineOutcome(boolean matched, int differences) {}

  /**
   * 核一行到货应收：收货数量 + 质检扣量。
   *
   * <p>⚠️ 上游读不到时**不吞掉**：找不到到货流水 ⇒ 记一条「到货依据缺失」差异，
   * 这一行的财务数量与金额**留 NULL**（不是 0）—— 写 0 会被读成「这行金额就是 0」。
   */
  private LineOutcome matchReceiptLine(
      Record head, Record line, String bearer, AccessService.Access actor, List<String> notes) {
    UUID lineId = line.get("id", UUID.class);
    UUID tenantId = actor.tenantId();
    UUID reconId = head.get("id", UUID.class);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    int raised = 0;

    UUID trackingId = line.get("source_ref_id", UUID.class);
    UUID receiptId = line.get("source_line_ref_id", UUID.class);
    BigDecimal statementQty = line.get("quantity", BigDecimal.class);
    BigDecimal statementAmount = money(line.get("statement_amount", BigDecimal.class));
    BigDecimal unitPrice = line.get("unit_price", BigDecimal.class);
    BigDecimal fxRate = line.get("fx_rate", BigDecimal.class);
    String currency = line.get("currency", String.class);

    // ---- 到货事实 ----
    ArrivalEvidencePort.ArrivalReceipt receipt = null;
    if (trackingId != null && receiptId != null) {
      var arrival = arrivals.arrival(trackingId, bearer);
      var receipts = arrival.receipts() == null ? List.<ArrivalEvidencePort.ArrivalReceipt>of() : arrival.receipts();
      for (var r : receipts) {
        if (r.id() != null && r.id().equals(receiptId)) {
          receipt = r;
          break;
        }
      }
    }

    if (receipt == null) {
      sql.execute(
          "update finance_supplier_reconciliation_line set receipt_matched=false,"
              + " receipt_arrived_qty=null, receipt_warehouse_code='', quality_matched=false,"
              + " quality_status='NOT_INSPECTED', quality_deducted_qty=null,"
              + " finance_quantity=null, finance_amount=null, variance_quantity=0, variance_amount=0,"
              + " updated_at=?::timestamptz where id=?",
          now,
          lineId);
      raiseDifference(tenantId, reconId, lineId, line.get("line_no", Integer.class),
          DifferenceType.RECEIPT_EVIDENCE_MISSING,
          statementQty, null, BigDecimal.ZERO, BigDecimal.ZERO, null,
          "对账单这一行指着的到货流水在上游 6.3.2.2 读不到 ⇒ 收货数量无法核对，"
              + "本行金额留空（不是 0）。");
      notes.add("第 " + line.get("line_no", Integer.class) + " 行：到货依据缺失");
      return new LineOutcome(false, 1);
    }

    BigDecimal arrivedQty = quantity(receipt.arrivedQty());
    String warehouseCode = nullSafe(receipt.warehouseReceiptCode());

    // ---- 质检事实 ----
    BigDecimal deductedQty = BigDecimal.ZERO;
    boolean qualityMatched = false;
    String qualityStatus = "NOT_APPLICABLE";
    if (!warehouseCode.isBlank()) {
      var page = inspections.inspections(warehouseCode, bearer);
      var matchedRows =
          page.rows().stream()
              .filter(r -> "COMPLETED".equals(r.status()))
              .filter(
                  r ->
                      r.supplierId() == null
                          || head.get("supplier_ref_id", UUID.class).equals(r.supplierId()))
              .toList();
      if (matchedRows.isEmpty()) {
        qualityStatus = "NOT_INSPECTED";
        notes.add(
            "第 "
                + line.get("line_no", Integer.class)
                + " 行：仓库收货单 "
                + warehouseCode
                + " 没有已完成的质检单 ⇒ 质检依据缺失");
      } else {
        qualityMatched = true;
        qualityStatus = "INSPECTED";
        deductedQty =
            quantityOrNull(
                matchedRows.stream()
                    .map(QualityInspectionPort.InspectionRow::grossDeductedQuantity)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        for (var inspection : matchedRows) {
          sql.execute(
              "delete from finance_supplier_reconciliation_inspection"
                  + " where tenant_id = ? and line_id = ? and inspection_id = ?",
              tenantId,
              lineId,
              inspection.id());
          sql.execute(
              "insert into finance_supplier_reconciliation_inspection(id,tenant_id,recon_id,line_id,"
                  + " inspection_id,inspection_no,receipt_code,sku_code,arrived_quantity,"
                  + " qualified_quantity,defective_quantity,concession_quantity,deducted_quantity,"
                  + " deducted_amount,currency_code,inspection_status,inspection_result,completed_at,"
                  + " created_at)"
                  + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenantId,
              head.get("id", UUID.class),
              lineId,
              inspection.id(),
              nullSafe(inspection.inspectionNo()),
              warehouseCode,
              nullSafe(inspection.skuCode()),
              quantityOrNull(inspection.arrivedQuantity()),
              quantityOrNull(inspection.qualifiedQuantity()),
              quantityOrNull(inspection.defectiveQuantity()),
              quantityOrNull(inspection.concessionQuantity()),
              quantityOrNull(inspection.grossDeductedQuantity()),
              moneyOrZero(inspection.deductedAmount()),
              nullSafe(inspection.currencyCode()),
              nullSafe(inspection.status()),
              nullSafe(inspection.result()),
              inspection.completedAt(),
              now);
        }
      }
    }

    // ---- 财务侧认定的数量与金额 ----
    BigDecimal financeQty = arrivedQty.subtract(deductedQty);
    if (financeQty.signum() < 0) financeQty = BigDecimal.ZERO;
    BigDecimal financeAmount = null;
    if (unitPrice != null && fxRate != null) {
      financeAmount = money(financeQty.multiply(unitPrice).multiply(fxRate));
    }
    BigDecimal varianceQty = statementQty == null ? BigDecimal.ZERO : arrivedQty.subtract(statementQty);
    BigDecimal varianceAmount =
        financeAmount == null ? BigDecimal.ZERO : financeAmount.subtract(statementAmount);

    sql.execute(
        "update finance_supplier_reconciliation_line set receipt_matched=true,"
            + " receipt_arrived_qty=?, receipt_warehouse_code=?, quality_matched=?, quality_status=?,"
            + " quality_deducted_qty=?, finance_quantity=?, finance_amount=?, variance_quantity=?,"
            + " variance_amount=?, updated_at=?::timestamptz where id=?",
        arrivedQty,
        warehouseCode,
        qualityMatched,
        qualityStatus,
        qualityMatched ? deductedQty : null,
        financeQty,
        financeAmount,
        varianceQty,
        varianceAmount,
        now,
        lineId);

    // ---- 差异：数量不符 ----
    BigDecimal qtyDifferenceAmount = BigDecimal.ZERO;
    if (statementQty != null && varianceQty.abs().compareTo(QUANTITY_EPSILON) > 0) {
      qtyDifferenceAmount =
          unitPrice == null || fxRate == null
              ? BigDecimal.ZERO
              : money(varianceQty.multiply(unitPrice).multiply(fxRate));
      raiseDifference(
          tenantId, reconId, lineId, line.get("line_no", Integer.class),
          DifferenceType.RECEIPT_QUANTITY_MISMATCH,
          statementQty, arrivedQty, varianceQty, qtyDifferenceAmount, currency,
          "对账单记 " + plain(statementQty) + "，上游 6.3.2.2 的到货流水记 " + plain(arrivedQty)
              + "（收货单号 " + warehouseCode + "）。");
      raised++;
    }

    // ---- 差异：质检扣量未在对账单中扣除 ----
    BigDecimal qualityDifferenceAmount = BigDecimal.ZERO;
    if (qualityMatched && deductedQty.signum() > 0) {
      qualityDifferenceAmount =
          unitPrice == null || fxRate == null
              ? BigDecimal.ZERO
              : money(deductedQty.negate().multiply(unitPrice).multiply(fxRate));
      raiseDifference(
          tenantId, reconId, lineId, line.get("line_no", Integer.class),
          DifferenceType.QUALITY_DEDUCTION_NOT_APPLIED,
          statementQty, financeQty, deductedQty.negate(), qualityDifferenceAmount, currency,
          "6.4.2.1 质检单判定扣量 " + plain(deductedQty)
              + "，采购侧对账单**未扣除**（上游 qualityDeductionStatus=UNAVAILABLE）"
              + " ⇒ 应付按扣量后数量计算。");
      raised++;
    }

    // ---- 差异：有收货单号但没有质检记录 ----
    if (!warehouseCode.isBlank() && !qualityMatched) {
      raiseDifference(
          tenantId, reconId, lineId, line.get("line_no", Integer.class),
          DifferenceType.QUALITY_EVIDENCE_MISSING,
          statementQty, null, BigDecimal.ZERO, BigDecimal.ZERO, currency,
          "仓库收货单 " + warehouseCode + " 没有已完成的质检单 ⇒ 质检依据缺失（**不等于**「这批没有扣量」）。");
      raised++;
    }

    // ---- 差异：金额残差（数量与单价都解释不了的那部分）----
    BigDecimal residual = varianceAmount.subtract(qtyDifferenceAmount).subtract(qualityDifferenceAmount);
    if (residual.abs().compareTo(MONEY_EPSILON) > 0) {
      raiseDifference(
          tenantId, reconId, lineId, line.get("line_no", Integer.class), DifferenceType.AMOUNT_RESIDUAL,
          statementAmount, financeAmount, BigDecimal.ZERO, residual, currency,
          "数量与单价的差额解释不了这 " + plain(residual) + "（对账单币种口径）⇒ 金额残差。");
      raised++;
    }

    return new LineOutcome(true, raised);
  }

  /** 落一条差异（已存在同类型的未裁决差异 ⇒ 更新数值，不重复落）。 */
  private void raiseDifference(
      UUID tenantId,
      UUID reconId,
      UUID lineId,
      int lineNo,
      DifferenceType type,
      BigDecimal statementValue,
      BigDecimal financeValue,
      BigDecimal varianceQuantity,
      BigDecimal varianceAmount,
      String currency,
      String note) {
    var existing =
        sql.fetchOne(
            "select id, status from finance_supplier_reconciliation_difference"
                + " where line_id = ? and difference_type = ?",
            lineId,
            type.name());
    if (existing != null) {
      if ("RESOLVED".equals(existing.get("status", String.class))) return;
      sql.execute(
          "update finance_supplier_reconciliation_difference set statement_value=?,finance_value=?,"
              + " variance_quantity=?,variance_amount=?,currency=?,note=?,updated_at=?::timestamptz"
              + " where id=?",
          statementValue,
          financeValue,
          varianceQuantity,
          varianceAmount,
          currency,
          note,
          OffsetDateTime.now(ZoneOffset.UTC),
          existing.get("id", UUID.class));
      return;
    }
    sql.execute(
        "insert into finance_supplier_reconciliation_difference(id,tenant_id,recon_id,line_id,line_no,"
            + " difference_type,status,statement_value,finance_value,variance_quantity,variance_amount,"
            + " currency,note,created_at,updated_at)"
            + " values(?,?,?,?,?,?,'OPEN',?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        reconId,
        lineId,
        lineNo,
        type.name(),
        statementValue,
        financeValue,
        varianceQuantity,
        varianceAmount,
        currency,
        note,
        OffsetDateTime.now(ZoneOffset.UTC),
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  // ================================================================ 差异说明与裁决

  /**
   * 登记一条差异说明（采购侧或供应商给出）。
   *
   * <p>⚠️ 说明是**举证**：只能追加。追加后差异状态变为「已说明待裁决」，
   * 但**不裁决**金额 —— 裁决是财务侧的独立动作（独立权限）。
   */
  @Transactional
  public FinanceSupplierReconciliationDetail addExplanation(
      UUID id, UUID differenceId, FinanceSupplierReconciliationExplanationInput input,
      AccessService.Access actor) {
    var head = requireHead(id);
    var status = ReconciliationStatus.valueOf(head.get("status", String.class));
    if (status.terminal()) {
      throw badRequest("对账单已是终态（" + status.label() + "），不可再登记差异说明");
    }
    var difference = requireDifference(id, differenceId);
    if ("RESOLVED".equals(difference.get("status", String.class))) {
      throw badRequest("这条差异已裁决，裁决结果是举证凭据，不可再补说明");
    }
    String text = input == null ? "" : nullSafe(input.explanation());
    if (text.isBlank()) {
      throw badRequest("差异说明不能为空：没有说明就没有裁决的依据");
    }
    String role =
        input.providedRole() == null || input.providedRole().isBlank()
            ? "FINANCE"
            : input.providedRole().trim().toUpperCase();
    if (!List.of("PURCHASE", "FINANCE", "SUPPLIER").contains(role)) {
      throw badRequest("说明方只能是 PURCHASE / FINANCE / SUPPLIER");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "insert into finance_supplier_reconciliation_explanation(id,tenant_id,recon_id,difference_id,"
            + " explanation,provided_role,provided_by,evidence_ref,created_at)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        actor.tenantId(),
        id,
        differenceId,
        text,
        role,
        actor.userId(),
        input.evidenceRef() == null ? "" : input.evidenceRef().trim(),
        now);
    sql.execute(
        "update finance_supplier_reconciliation_difference set status='EXPLAINED',explanation=?,"
            + " explained_by=?,explained_at=?::timestamptz,updated_at=?::timestamptz where id=?",
        text,
        actor.userId(),
        now,
        now,
        differenceId);
    writeEvent(actor.tenantId(), id, "EXPLANATION_ADDED", "", "",
        "登记差异说明：" + text, actor.userId(), role.equals("FINANCE") ? "FINANCE" : "PURCHASE", now);
    return detail(id);
  }

  /**
   * 裁决一条差异（**财务侧专属动作**）。
   *
   * <p>APPLIED = 采纳财务侧口径（应付按差额调整）；KEPT = 沿用采购侧口径（不调整）。
   * 调整额是**生成列** ⇒ 裁决与调整额不可能对不上。
   */
  @Transactional
  public FinanceSupplierReconciliationDetail resolveDifference(
      UUID id, UUID differenceId, FinanceSupplierReconciliationResolveInput input,
      AccessService.Access actor) {
    var head = requireHead(id);
    var status = ReconciliationStatus.valueOf(head.get("status", String.class));
    if (status.terminal()) {
      throw badRequest("对账单已是终态（" + status.label() + "），不可再裁决");
    }
    var difference = requireDifference(id, differenceId);
    if ("RESOLVED".equals(difference.get("status", String.class))) {
      throw badRequest("这条差异已裁决过：裁决是举证凭据，不可改判");
    }
    String raw = input == null ? "" : nullSafe(input.resolution());
    DifferenceResolution resolution;
    try {
      resolution = DifferenceResolution.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw badRequest("裁决方式只能是 APPLIED（按财务口径调整）或 KEPT（沿用采购侧口径）");
    }
    String note = input == null ? "" : nullSafe(input.note());
    if (note.isBlank()) {
      throw badRequest("裁决必须写明说明：凭什么这么判");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_supplier_reconciliation_difference set status='RESOLVED',resolution=?,"
            + " resolution_note=?,resolved_by=?,resolved_at=?::timestamptz,updated_at=?::timestamptz"
            + " where id=?",
        resolution.name(),
        note,
        actor.userId(),
        now,
        now,
        differenceId);
    writeEvent(actor.tenantId(), id, "DIFFERENCE_RESOLVED", "", "",
        "裁决差异 " + difference.get("difference_type", String.class) + "：" + resolution.label() + "（" + note + "）",
        actor.userId(), "FINANCE", now);
    refreshHeadStatus(id);
    return detail(id);
  }

  // ================================================================ 财务确认 / 驳回

  /**
   * **财务最终确认**：裁决完差异 ⇒ 生成应付依据 ⇒ 落一张应付单（12.1.2.1）。
   *
   * <p>⚠️ **这是本模块唯一能产生应付的入口**（采购侧不能自行确认应付 —— 职责分离）。
   *
   * <p>⚠️ **应付单走 V340 的守卫**：先建 DRAFT 单头（**不填单头金额**）→ 落明细行
   * （金额由触发器从明细求和派生）→ DRAFT→OPEN 确认。
   *
   * <p>⚠️ **幂等**：应付单的来源单号 = 本对账单号（V340 的 UNIQUE(tenant_id, source, source_reference)）
   * ⇒ 同一份对账单重复确认**不会造出第二张应付**。
   */
  @Transactional
  public FinanceSupplierReconciliationDetail confirm(UUID id, AccessService.Access actor) {
    var head = requireHead(id);
    var status = ReconciliationStatus.valueOf(head.get("status", String.class));
    if (status.terminal()) {
      throw badRequest("对账单已是终态（" + status.label() + "），不可重复确认");
    }
    if (!"MATCHED".equals(status.name()) && !"DIFFERENCES".equals(status.name())) {
      throw badRequest(
          "对账单还没核对（当前状态 " + status.label() + "）：先跑一次核对，再确认");
    }
    var open =
        sql.fetchOne(
                "select count(*) from finance_supplier_reconciliation_difference"
                    + " where recon_id = ? and status <> 'RESOLVED'",
                id)
            .get(0, Long.class);
    if (open > 0) {
      throw badRequest(
          "还有 " + open + " 条差异未裁决：差异必须逐条处理完才能确认（职责分离：采购不能自行确认应付）");
    }

    // ---- 应付确认政策闸门（12.1.2.1 的 policy 表）----
    int dueDays = requireEnabledStatementPolicy();

    // ---- 逐行算出最终应付口径：采购侧口径 + 已裁决的调整额 ----
    var lines =
        sql.fetch(
            "select * from finance_supplier_reconciliation_line where recon_id = ? order by line_no, id",
            id);
    BigDecimal statementNet = BigDecimal.ZERO;
    BigDecimal adjustmentNet = BigDecimal.ZERO;
    BigDecimal confirmedQuantity = BigDecimal.ZERO;
    var basisLines = new ArrayList<String>();
    for (var line : lines) {
      String lineType = line.get("line_type", String.class);
      int sign = "RECEIPT".equals(lineType) ? 1 : -1;
      BigDecimal statementAmount = money(line.get("statement_amount", BigDecimal.class));
      BigDecimal applied =
          moneyOrZero(
              sql.fetchOne(
                      "select coalesce(sum(adjustment_amount),0) as a"
                          + " from finance_supplier_reconciliation_difference"
                          + " where line_id = ? and resolution = 'APPLIED'",
                      line.get("id", UUID.class))
                  .get("a", BigDecimal.class));
      statementNet = statementNet.add(statementAmount.multiply(BigDecimal.valueOf(sign)));
      adjustmentNet = adjustmentNet.add(applied.multiply(BigDecimal.valueOf(sign)));
      if ("RECEIPT".equals(lineType)) {
        BigDecimal qty = line.get("finance_quantity", BigDecimal.class);
        if (qty != null) confirmedQuantity = confirmedQuantity.add(qty);
      }
      basisLines.add(
          "第 "
              + line.get("line_no", Integer.class)
              + " 行 "
              + lineType
              + "：采购侧 "
              + plain(statementAmount)
              + "，调整 "
              + plain(applied));
    }
    BigDecimal net = statementNet.add(adjustmentNet);
    if (net.signum() <= 0) {
      throw badRequest(
          "按当前裁决算出来的应付金额是 "
              + plain(net)
              + "（不大于 0）：没有应付可生成。"
              + "若核对结论是「这单不该付」，请用「财务驳回」并写明理由");
    }

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    var payableId = createPayable(head, net, statementNet, adjustmentNet, basisLines, dueDays, actor, now);
    var payableNo =
        sql.fetchOne("select payable_no from finance_payable where id = ?", payableId)
            .get("payable_no", String.class);

    // ---- 应付依据（先落依据，再把对账单置为 CONFIRMED：触发器要求二者齐备）----
    sql.execute(
        "insert into finance_supplier_reconciliation_payable(id,tenant_id,recon_id,payable_id,"
            + " payable_no,amount,currency,statement_amount,adjustment_amount,basis_note,"
            + " generated_by,generated_at,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        actor.tenantId(),
        id,
        payableId,
        payableNo,
        net,
        head.get("statement_currency", String.class),
        statementNet,
        adjustmentNet,
        "应付依据：采购侧口径 " + plain(statementNet) + " + 裁决调整 " + plain(adjustmentNet)
            + " = " + plain(net) + " " + head.get("statement_currency", String.class)
            + "；逐行：" + String.join("；", basisLines),
        actor.userId(),
        now,
        now);
    writeEvent(actor.tenantId(), id, "PAYABLE_GENERATED", "", "",
        "生成应付依据：" + payableNo + "（" + plain(net) + " " + head.get("statement_currency", String.class) + "）",
        actor.userId(), "FINANCE", now);

    sql.execute(
        "update finance_supplier_reconciliation set status='CONFIRMED',confirmed_by=?,confirmed_at=?::timestamptz,"
            + " finance_confirmed_amount=?,finance_confirmed_quantity=?,finance_adjusted_amount=?,"
            + " payable_id=?,payable_no=?,updated_at=?::timestamptz,version=version+1 where id=?",
        actor.userId(),
        now,
        net,
        quantity(confirmedQuantity),
        adjustmentNet,
        payableId,
        payableNo,
        now,
        id);
    writeEvent(actor.tenantId(), id, "CONFIRMED", status.name(), "CONFIRMED",
        "财务确认：应付 " + payableNo + "，金额 " + plain(net) + " " + head.get("statement_currency", String.class),
        actor.userId(), "FINANCE", now);
    return detail(id);
  }

  /** 财务驳回（终态）。理由必填。 */
  @Transactional
  public FinanceSupplierReconciliationDetail reject(
      UUID id, FinanceSupplierReconciliationRejectInput input, AccessService.Access actor) {
    var head = requireHead(id);
    var status = ReconciliationStatus.valueOf(head.get("status", String.class));
    if (status.terminal()) {
      throw badRequest("对账单已是终态（" + status.label() + "），不可重复驳回");
    }
    String reason = input == null ? "" : nullSafe(input.reason());
    if (reason.isBlank()) {
      throw badRequest("驳回必须写明理由：驳回掉的是一份对账单，痕迹要留着");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update finance_supplier_reconciliation set status='REJECTED',rejected_by=?,rejected_at=?::timestamptz,"
            + " reject_reason=?,updated_at=?::timestamptz,version=version+1 where id=?",
        actor.userId(),
        now,
        reason,
        now,
        id);
    writeEvent(actor.tenantId(), id, "REJECTED", status.name(), "REJECTED",
        "财务驳回：" + reason, actor.userId(), "FINANCE", now);
    return detail(id);
  }

  /**
   * 生成应付单（12.1.2.1 的 {@code finance_payable}）。
   *
   * <p>⚠️ <b>不手填单头金额</b>：V340 的触发器 {@code guard_finance_payable_amount}
   * 从明细行求和派生单头金额 ⇒ 这里**连 amount 字段都不写**。
   *
   * <p>⚠️ <b>幂等</b>：来源 = SUPPLIER_STATEMENT、来源单号 = 对账单号，
   * 命中 V340 的 UNIQUE(tenant_id, source, source_reference) ⇒ 重复确认不会长出第二张。
   */
  private UUID createPayable(
      Record head,
      BigDecimal net,
      BigDecimal statementNet,
      BigDecimal adjustmentNet,
      List<String> basisLines,
      int dueDays,
      AccessService.Access actor,
      OffsetDateTime now) {
    UUID tenantId = actor.tenantId();
    String reconNo = head.get("recon_no", String.class);
    var existing =
        sql.fetchOne(
            "select id, payable_no from finance_payable where source = 'SUPPLIER_STATEMENT'"
                + " and source_reference = ?",
            reconNo);
    if (existing != null) {
      return existing.get("id", UUID.class);
    }
    UUID payableId = UUID.randomUUID();
    String payableNo = nextReference("AP");
    LocalDate dueDate = now.toLocalDate().plusDays(dueDays);
    sql.execute(
        "insert into finance_payable(id,tenant_id,payable_no,source,source_system,source_reference,"
            + " counterparty_type,counterparty_ref,counterparty_name,currency,"
            + " exchange_rate,base_currency,due_date,confirmed_at,note,status,created_at,updated_at)"
            + " values(?,?,?,'SUPPLIER_STATEMENT','finance',?,'SUPPLIER',?,?,?,null,null,?::date,"
            + " ?::timestamptz,?,'DRAFT',?::timestamptz,?::timestamptz)",
        payableId,
        tenantId,
        payableNo,
        reconNo,
        head.get("supplier_ref_id", UUID.class),
        nullSafe(head.get("supplier_name", String.class)),
        head.get("statement_currency", String.class),
        dueDate,
        now,
        "供应商对账 " + reconNo + " 财务确认净额（采购侧 " + plain(statementNet) + " + 调整 "
            + plain(adjustmentNet) + " = " + plain(net) + "）；明细见对账行 " + basisLines.size() + " 行",
        now,
        now);
    writePayableEvent(tenantId, payableId, "CREATED", "", "DRAFT",
        "由 12.1.2.3 供应商对账 " + reconNo + " 生成", actor.userId(), now);
    sql.execute(
        "insert into finance_payable_line(id,tenant_id,payable_id,line_no,description,sku_code,"
            + " source_reference,quantity,unit_price,amount)"
            + " values(?,?,?,1,?,'',?,null,null,?)",
        UUID.randomUUID(),
        tenantId,
        payableId,
        "供应商对账 " + reconNo + " 财务确认净额（采购侧 " + plain(statementNet) + " + 调整 "
            + plain(adjustmentNet) + "）",
        reconNo,
        net);
    sql.execute(
        "update finance_payable set status='OPEN',updated_at=?::timestamptz,version=version+1 where id=?",
        now,
        payableId);
    writePayableEvent(tenantId, payableId, "CONFIRMED", "DRAFT", "OPEN",
        "财务确认（12.1.2.3）", actor.userId(), now);
    return payableId;
  }

  /** 12.1.2.1 的应付确认政策闸门：没有启用的政策 ⇒ 一张应付都产生不了。 */
  private int requireEnabledStatementPolicy() {
    var row =
        sql.fetchOne(
            "select * from finance_payable_policy where source = 'SUPPLIER_STATEMENT'"
                + " and obligation_kind = 'PAYABLE'");
    if (row == null) {
      throw badRequest(
          "「供应商对账单」还没有配置应付确认政策：这个来源**现在一张应付都产生不了**。"
              + "请先在 12.1.2.1 的应付政策里启用 SUPPLIER_STATEMENT 并设定账期天数 ——"
              + " 什么业务确认为应付由租户决定，系统不会替用户默认一个");
    }
    if (!row.get("enabled", Boolean.class)) {
      throw badRequest("「供应商对账单」的应付确认政策当前是**禁用**的，这个来源不产生应付");
    }
    return row.get("due_days", Integer.class);
  }

  // ================================================================ 工具

  /** 头行读取（RLS 保证只看得到本租户的）。 */
  private Record requireHead(UUID id) {
    var head = sql.fetchOne("select * from finance_supplier_reconciliation where id = ?", id);
    if (head == null) throw notFound("对账单 " + id + " 不存在或无权查看");
    return head;
  }

  private Record requireDifference(UUID reconId, UUID differenceId) {
    var difference =
        sql.fetchOne(
            "select * from finance_supplier_reconciliation_difference where id = ? and recon_id = ?",
            differenceId,
            reconId);
    if (difference == null) throw notFound("差异 " + differenceId + " 不存在或无权查看");
    return difference;
  }

  /** 落一条事件。 */
  private void writeEvent(
      UUID tenantId,
      UUID reconId,
      String type,
      String from,
      String to,
      String reason,
      UUID actorId,
      String actorRole,
      OffsetDateTime at) {
    sql.execute(
        "insert into finance_supplier_reconciliation_event(id,tenant_id,recon_id,event_type,"
            + " from_status,to_status,reason,actor_id,actor_role,occurred_at)"
            + " values(?,?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        reconId,
        type,
        from == null ? "" : from,
        to == null ? "" : to,
        reason == null ? "" : reason,
        actorId,
        actorRole,
        at);
  }

  /** 落一条应付单事件（12.1.2.1 的事件流，本模块只追加）。 */
  private void writePayableEvent(
      UUID tenantId, UUID payableId, String type, String from, String to, String reason,
      UUID actorId, OffsetDateTime at) {
    sql.execute(
        "insert into finance_payable_event(id,tenant_id,payable_id,event_type,from_status,"
            + " to_status,reason,actor_id,occurred_at) values(?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        tenantId,
        payableId,
        type,
        from,
        to,
        reason == null ? "" : reason,
        actorId,
        at);
  }

  /** 更新头部的行数 / 匹配数（运营字段）。 */
  private void updateLineCounts(UUID reconId) {
    sql.execute(
        "update finance_supplier_reconciliation set line_count=("
            + " select count(*) from finance_supplier_reconciliation_line l where l.recon_id=?),"
            + " matched_line_count=("
            + " select count(*) from finance_supplier_reconciliation_line l"
            + " where l.recon_id=? and l.receipt_matched),"
            + " unmatched_line_count=("
            + " select count(*) from finance_supplier_reconciliation_line l"
            + " where l.recon_id=? and not l.receipt_matched and l.line_type='RECEIPT'),"
            + " updated_at=?::timestamptz where id=?",
        reconId,
        reconId,
        reconId,
        OffsetDateTime.now(ZoneOffset.UTC),
        reconId);
  }

  /** 裁决之后刷新头部状态（有未裁决差异 ⇒ DIFFERENCES，否则 MATCHED）。 */
  private void refreshHeadStatus(UUID reconId) {
    var open =
        sql.fetchOne(
                "select count(*) from finance_supplier_reconciliation_difference"
                    + " where recon_id = ? and status <> 'RESOLVED'",
                reconId)
            .get(0, Long.class);
    var head = sql.fetchOne("select status from finance_supplier_reconciliation where id = ?", reconId);
    if (head == null) return;
    String current = head.get("status", String.class);
    if (ReconciliationStatus.valueOf(current).terminal()) return;
    String next = open > 0 ? "DIFFERENCES" : "MATCHED";
    if (!next.equals(current)) {
      sql.execute(
          "update finance_supplier_reconciliation set status=?,updated_at=?::timestamptz,"
              + " version=version+1 where id=?",
          next,
          OffsetDateTime.now(ZoneOffset.UTC),
          reconId);
    }
  }

  private FinanceSupplierReconciliationRow row(Record r) {
    var status = ReconciliationStatus.valueOf(r.get("status", String.class));
    return new FinanceSupplierReconciliationRow(
        r.get("id", UUID.class),
        r.get("recon_no", String.class),
        r.get("upstream_recon_id", UUID.class),
        r.get("upstream_recon_no", String.class),
        r.get("supplier_ref_id", UUID.class),
        r.get("supplier_code", String.class),
        r.get("supplier_name", String.class),
        r.get("period_type", String.class),
        r.get("period_start", LocalDate.class),
        r.get("period_end", LocalDate.class),
        r.get("statement_currency", String.class),
        status,
        status.label(),
        r.get("upstream_status", String.class),
        r.get("statement_received_amount", BigDecimal.class),
        r.get("statement_deduction_amount", BigDecimal.class),
        r.get("statement_claim_amount", BigDecimal.class),
        r.get("statement_suggested_amount", BigDecimal.class),
        r.get("statement_total_quantity", BigDecimal.class),
        r.get("finance_confirmed_amount", BigDecimal.class),
        r.get("finance_confirmed_quantity", BigDecimal.class),
        r.get("finance_adjusted_amount", BigDecimal.class),
        r.get("line_count", Integer.class),
        r.get("difference_count", Integer.class),
        r.get("open_difference_count", Integer.class),
        r.get("matched_line_count", Integer.class),
        r.get("unmatched_line_count", Integer.class),
        r.get("upstream_supplier_confirmed_at", OffsetDateTime.class),
        r.get("upstream_supplier_reference", String.class),
        r.get("confirmed_by", UUID.class),
        r.get("confirmed_at", OffsetDateTime.class),
        r.get("rejected_by", UUID.class),
        r.get("rejected_at", OffsetDateTime.class),
        r.get("reject_reason", String.class),
        r.get("payable_id", UUID.class),
        r.get("payable_no", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private FinanceSupplierReconciliationLineRow lineRow(Record r) {
    return new FinanceSupplierReconciliationLineRow(
        r.get("id", UUID.class),
        r.get("line_no", Integer.class),
        r.get("line_type", String.class),
        lineTypeLabel(r.get("line_type", String.class)),
        r.get("source_type", String.class),
        r.get("source_ref_code", String.class),
        r.get("source_line_ref_code", String.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("quantity", BigDecimal.class),
        r.get("unit_code", String.class),
        r.get("base_quantity", BigDecimal.class),
        r.get("unit_price", BigDecimal.class),
        r.get("currency", String.class),
        r.get("fx_rate", BigDecimal.class),
        r.get("fx_rate_source", String.class),
        r.get("amount", BigDecimal.class),
        r.get("statement_amount", BigDecimal.class),
        r.get("upstream_quality_status", String.class),
        r.get("upstream_quality_quantity", BigDecimal.class),
        r.get("receipt_matched", Boolean.class),
        r.get("receipt_arrived_qty", BigDecimal.class),
        r.get("receipt_warehouse_code", String.class),
        r.get("quality_matched", Boolean.class),
        r.get("quality_status", String.class),
        r.get("quality_deducted_qty", BigDecimal.class),
        r.get("finance_quantity", BigDecimal.class),
        r.get("finance_amount", BigDecimal.class),
        r.get("variance_quantity", BigDecimal.class),
        r.get("variance_amount", BigDecimal.class),
        r.get("note", String.class));
  }

  private FinanceSupplierReconciliationDifferenceRow differenceRow(Record r) {
    var type = DifferenceType.valueOf(r.get("difference_type", String.class));
    var status = DifferenceStatus.valueOf(r.get("status", String.class));
    var resolution = r.get("resolution", String.class);
    return new FinanceSupplierReconciliationDifferenceRow(
        r.get("id", UUID.class),
        r.get("line_id", UUID.class),
        r.get("line_no", Integer.class),
        type,
        type.label(),
        status,
        status.label(),
        r.get("statement_value", BigDecimal.class),
        r.get("finance_value", BigDecimal.class),
        r.get("variance_quantity", BigDecimal.class),
        r.get("variance_amount", BigDecimal.class),
        r.get("currency", String.class),
        resolution == null ? null : DifferenceResolution.valueOf(resolution),
        resolution == null ? "" : DifferenceResolution.valueOf(resolution).label(),
        r.get("adjustment_amount", BigDecimal.class),
        r.get("resolution_note", String.class),
        r.get("resolved_by", UUID.class),
        r.get("resolved_at", OffsetDateTime.class),
        r.get("explanation", String.class),
        r.get("explained_by", UUID.class),
        r.get("explained_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static String lineTypeLabel(String lineType) {
    return switch (lineType) {
      case "RECEIPT" -> "已到货应收";
      case "CLAIM" -> "索赔冲减";
      case "DEDUCTION" -> "代扣代缴费用";
      default -> lineType;
    };
  }

  private static String nextReference(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
  }

  private static String shortId() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal moneyOrZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal quantity(BigDecimal value) {
    return value == null ? null : value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal quantityOrNull(BigDecimal value) {
    return value == null ? null : value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  private static String plain(BigDecimal value) {
    return value == null ? "—" : value.toPlainString();
  }

  private static ResponseStatusException badRequest(String detail) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
  }

  private static ResponseStatusException notFound(String detail) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, detail);
  }

  private static Object[] concat(List<Object> binds, Object... tail) {
    var out = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) out[i] = binds.get(i);
    System.arraycopy(tail, 0, out, binds.size(), tail.length);
    return out;
  }
}
