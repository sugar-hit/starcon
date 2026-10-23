import type {
  FinanceSupplierReconciliationCapabilities,
  FinanceSupplierReconciliationDetail,
  FinanceSupplierReconciliationDifferencePage,
  FinanceSupplierReconciliationDifferenceRow,
  FinanceSupplierReconciliationExplanationInput,
  FinanceSupplierReconciliationImportResult,
  FinanceSupplierReconciliationLinePage,
  FinanceSupplierReconciliationMatchResult,
  FinanceSupplierReconciliationOptions,
  FinanceSupplierReconciliationPage,
  FinanceSupplierReconciliationResolveInput,
  FinanceSupplierReconciliationRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.1.2.3 供应商对账（财务侧）的前端模型。
 *
 * <p>蓝图 12-finance.md：「财务侧供应商对账：核对采购侧对账单（6.5.1）与收货/质检数据，
 * 差异处理与确认，最终生成应付依据。」输出：「对账确认结果 + 差异清单 + 应付生成依据。」
 * 加粗句：「<b>财务是最终确认方</b>，采购不能自行确认应付——这是职责分离。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>金额只原样展示</b>：采购侧口径、财务侧口径、调整额、应付净额都由**服务端**算好，
 *       页面**不做任何前端算术**（USD 与 EUR 加起来不是任何一个金额）。</li>
 *   <li><b>币种分开</b>：汇总按币种分组渲染，**绝不**把不同币种摆进同一列相加。</li>
 *   <li><b>「采购侧口径」与「财务侧口径」必须分开显示</b>：这两个数不一样的时候，
 *       差额由**差异清单**解释 —— 页面不允许只显示一个数让人以为「就差这么多，没别的了」。</li>
 *   <li><b>差异未裁决就不能确认</b>：页面显示每条差异的状态（未处理 / 已说明待裁决 / 已裁决），
 *       并把「按财务口径调整 / 沿用采购侧口径」两种裁决方式分开摆出来。</li>
 *   <li><b>「已导入」不等于「已核对」，「已核对」不等于「已确认应付」</b>：
 *       三个状态在页面上是三个不同的徽章，措辞不许混。</li>
 *   <li><b>上游没交付的事就说没交付</b>：付款（12.2.2.1）未交付 ⇒ 页面**不提供**任何
 *       「标记已付」「执行付款」的按钮。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有对账单」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code finance/supplier-reconciliation}，
 * 与 12.1.2.1 的 {@code finance/payables}、12.1.2.2 的 {@code finance/payment-plans}
 * 以及同服务的其余模块各自独立前缀。
 */

const base = 'finance/supplier-reconciliation';

/**
 * 筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface SupplierReconciliationParams {
  status?: string | undefined;
  upstreamStatus?: string | undefined;
  supplierRefId?: string | undefined;
  currency?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 差异清单的筛选条件。 */
export interface SupplierReconciliationDifferenceParams {
  status?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

function query(params: Record<string, string | number | boolean | undefined>): string {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    if (typeof value === 'string' && value === '') continue;
    parts.push(`${key}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 端点

/** 能力声明：哪些上游真接通、职责分离怎么落、哪些还没交付、归谁。 */
export function fetchSupplierReconciliationCapabilities() {
  return api<FinanceSupplierReconciliationCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（全部来自真实数据）。 */
export function fetchSupplierReconciliationOptions() {
  return api<FinanceSupplierReconciliationOptions>(`${base}/options`);
}

/** 对账单列表：真分页 + 真筛选 + 按币种分开的汇总。 */
export function fetchSupplierReconciliations(params: SupplierReconciliationParams = {}) {
  return api<FinanceSupplierReconciliationPage>(`${base}${query({ ...params })}`);
}

/** 对账单详情：头 + 明细行 + 质检证据 + 差异 + 说明 + 事件流 + 应付依据。 */
export function fetchSupplierReconciliation(id: string) {
  return api<FinanceSupplierReconciliationDetail>(`${base}/${encodeURIComponent(id)}`);
}

/** 明细行列表（真分页 + 行类型筛选）。 */
export function fetchSupplierReconciliationLines(
  id: string,
  params: SupplierReconciliationDifferenceParams & { lineType?: string | undefined } = {},
) {
  return api<FinanceSupplierReconciliationLinePage>(
    `${base}/${encodeURIComponent(id)}/lines${query({ ...params })}`,
  );
}

/** 差异清单（真分页 + 状态筛选）。 */
export function fetchSupplierReconciliationDifferences(
  id: string,
  params: SupplierReconciliationDifferenceParams = {},
) {
  return api<FinanceSupplierReconciliationDifferencePage>(
    `${base}/${encodeURIComponent(id)}/differences${query({ ...params })}`,
  );
}

/**
 * 从上游 6.5.1.1 导入采购侧对账单（幂等）。
 *
 * <p>⚠️ 它**只导入**采购侧的对账单，**不产生应付** —— 应付要等财务确认。
 */
export function importSupplierReconciliations() {
  return api<FinanceSupplierReconciliationImportResult>(`${base}/import`, 'POST');
}

/** 核对收货与质检，产出差异清单。⚠️ 核对**不等于**确认。 */
export function matchSupplierReconciliation(id: string) {
  return api<FinanceSupplierReconciliationMatchResult>(
    `${base}/${encodeURIComponent(id)}/match`,
    'POST',
  );
}

/** 登记一条差异说明（采购侧 / 供应商给出）。**不裁决金额** —— 裁决是财务侧动作。 */
export function addSupplierReconciliationExplanation(
  id: string,
  differenceId: string,
  input: FinanceSupplierReconciliationExplanationInput,
) {
  return api<FinanceSupplierReconciliationDetail>(
    `${base}/${encodeURIComponent(id)}/differences/${encodeURIComponent(differenceId)}/explanations`,
    'POST',
    input,
  );
}

/** 裁决一条差异（财务侧专属）：按财务口径调整 / 沿用采购侧口径。 */
export function resolveSupplierReconciliationDifference(
  id: string,
  differenceId: string,
  input: FinanceSupplierReconciliationResolveInput,
) {
  return api<FinanceSupplierReconciliationDetail>(
    `${base}/${encodeURIComponent(id)}/differences/${encodeURIComponent(differenceId)}/resolve`,
    'POST',
    input,
  );
}

/** 财务最终确认（生成应付依据 + 应付单）。⚠️ 本模块**唯一**能产生应付的入口。 */
export function confirmSupplierReconciliation(id: string) {
  return api<FinanceSupplierReconciliationDetail>(
    `${base}/${encodeURIComponent(id)}/confirm`,
    'POST',
  );
}

/** 财务驳回（终态，整单冻结）。理由必填。 */
export function rejectSupplierReconciliation(id: string, reason: string) {
  return api<FinanceSupplierReconciliationDetail>(
    `${base}/${encodeURIComponent(id)}/reject`,
    'POST',
    { reason },
  );
}

// ================================================================ 展示口径

/** 对账单状态文案。 */
export function reconciliationStatusLabel(status: string): string {
  switch (status) {
    case 'IMPORTED':
      return '已导入（待核对）';
    case 'MATCHED':
      return '已核对（待确认）';
    case 'DIFFERENCES':
      return '有差异待裁决';
    case 'CONFIRMED':
      return '财务已确认（已生成应付）';
    case 'REJECTED':
      return '财务已驳回';
    default:
      return status;
  }
}

/** 对账单状态色调。 */
export function reconciliationStatusTone(
  status: string,
): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'CONFIRMED':
      return 'success';
    case 'DIFFERENCES':
      return 'warning';
    case 'REJECTED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 差异类型文案。 */
export function differenceTypeLabel(type: string): string {
  switch (type) {
    case 'RECEIPT_QUANTITY_MISMATCH':
      return '收货数量不符';
    case 'RECEIPT_EVIDENCE_MISSING':
      return '到货依据缺失';
    case 'QUALITY_DEDUCTION_NOT_APPLIED':
      return '质检扣量未扣除';
    case 'QUALITY_EVIDENCE_MISSING':
      return '质检依据缺失';
    case 'AMOUNT_RESIDUAL':
      return '金额残差';
    default:
      return type;
  }
}

/** 差异处理状态文案。 */
export function differenceStatusLabel(status: string): string {
  switch (status) {
    case 'OPEN':
      return '未处理';
    case 'EXPLAINED':
      return '已说明待裁决';
    case 'RESOLVED':
      return '已裁决';
    default:
      return status;
  }
}

/** 差异处理状态色调。 */
export function differenceStatusTone(status: string): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'RESOLVED':
      return 'success';
    case 'EXPLAINED':
      return 'warning';
    case 'OPEN':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 裁决方式文案。 */
export function resolutionLabel(resolution: string | null | undefined): string {
  switch (resolution) {
    case 'APPLIED':
      return '按财务口径调整';
    case 'KEPT':
      return '沿用采购侧口径';
    default:
      return '未裁决';
  }
}

/** 上游对账单状态文案。 */
export function upstreamStatusLabel(status: string): string {
  switch (status) {
    case 'DRAFT':
      return '草稿';
    case 'SENT':
      return '已发送待回执';
    case 'DISPUTED':
      return '有差异';
    case 'CONFIRMED':
      return '供应商已确认';
    case 'CLOSED':
      return '已关闭';
    default:
      return status;
  }
}

/** 事件类型文案。 */
export function reconciliationEventLabel(eventType: string): string {
  switch (eventType) {
    case 'IMPORTED':
      return '导入采购侧对账单';
    case 'REFRESHED':
      return '刷新上游快照';
    case 'MATCHED':
      return '核对收货与质检';
    case 'DIFFERENCE_RAISED':
      return '产生差异';
    case 'EXPLANATION_ADDED':
      return '登记差异说明';
    case 'DIFFERENCE_RESOLVED':
      return '裁决差异';
    case 'CONFIRMED':
      return '财务确认';
    case 'REJECTED':
      return '财务驳回';
    case 'PAYABLE_GENERATED':
      return '生成应付依据';
    default:
      return eventType;
  }
}

/** 动作方文案（采购侧 / 财务侧 —— 职责分离在留痕里也要看得见）。 */
export function actorRoleLabel(role: string): string {
  switch (role) {
    case 'PURCHASE':
      return '采购侧';
    case 'FINANCE':
      return '财务侧';
    default:
      return role;
  }
}

/** 金额展示：原样显示服务端给的数（**页面不做任何前端算术**）。 */
export function formatSupplierMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '—';
  const text = amount.toFixed(4);
  return currency ? `${text} ${currency}` : text;
}

/** 数量展示。 */
export function formatSupplierQuantity(quantity: number | null | undefined): string {
  if (quantity === null || quantity === undefined) return '—';
  return quantity.toFixed(6);
}

/**
 * 对账单行的核对结论（一句话，说清「差在哪、为什么」）。
 *
 * <p>⚠️ 这里**只做文案拼装**，不做任何金额加减 —— 差额由服务端算好。
 */
export function lineVarianceHint(line: {
  receiptMatched: boolean;
  receiptArrivedQty: number | null;
  quantity: number | null;
  qualityStatus: string;
  qualityDeductedQty: number | null;
  financeQuantity: number | null;
  varianceQuantity: number;
  varianceAmount: number;
  currency: string;
}): string {
  if (!line.receiptMatched) {
    return '到货依据缺失：上游找不到对应的到货流水，本行金额留空（不是 0）';
  }
  const parts: string[] = [];
  parts.push(
    `实到 ${formatSupplierQuantity(line.receiptArrivedQty)} / 对账单 ${formatSupplierQuantity(line.quantity)}`,
  );
  if (line.qualityStatus === 'INSPECTED') {
    parts.push(`质检扣量 ${formatSupplierQuantity(line.qualityDeductedQty)}`);
  } else if (line.qualityStatus === 'NOT_INSPECTED') {
    parts.push('无质检记录（≠ 没有扣量）');
  }
  parts.push(`差额 ${formatSupplierMoney(line.varianceAmount, line.currency)}`);
  return parts.join('；');
}

/** 差异的一行结论（采购侧值 vs 财务侧值 vs 差额）。 */
export function differenceHint(difference: FinanceSupplierReconciliationDifferenceRow): string {
  const left = difference.statementValue === null ? '—' : String(difference.statementValue);
  const right = difference.financeValue === null ? '—' : String(difference.financeValue);
  return `采购侧 ${left} / 财务侧 ${right} / 差额 ${formatSupplierMoney(
    difference.varianceAmount,
    difference.currency,
  )}`;
}

/**
 * 对账单的「能不能确认」提示。
 *
 * <p>⚠️ 页面据此**禁用**确认按钮并说明原因 —— 「为什么不能点」必须说清楚，
 * 不能让财务对着一个不能点的按钮猜。
 */
export function confirmBlockReason(row: FinanceSupplierReconciliationRow): string {
  if (row.status === 'CONFIRMED') return '已确认：应付依据已生成，对账单已冻结';
  if (row.status === 'REJECTED') return '已驳回：对账单已冻结';
  if (row.status === 'IMPORTED') return '还没核对：先跑一次「核对收货与质检」';
  if (row.openDifferenceCount > 0) {
    return `还有 ${row.openDifferenceCount} 条差异未裁决：差异必须逐条处理完才能确认`;
  }
  return '';
}

/** 汇总一行的文案（按币种分开，绝不相加）。 */
export function reconciliationSummaryLabel(summary: {
  currency: string;
  statementAmount: number;
  financeAmount: number | null;
  adjustmentAmount: number;
  reconCount: number;
  openDifferenceCount: number;
}): string {
  const finance =
    summary.financeAmount === null
      ? '尚未确认'
      : formatSupplierMoney(summary.financeAmount, summary.currency);
  return `采购侧 ${formatSupplierMoney(summary.statementAmount, summary.currency)} → 财务侧 ${finance}（调整 ${formatSupplierMoney(
    summary.adjustmentAmount,
    summary.currency,
  )}）· ${summary.reconCount} 张 · 未裁决差异 ${summary.openDifferenceCount} 条`;
}
