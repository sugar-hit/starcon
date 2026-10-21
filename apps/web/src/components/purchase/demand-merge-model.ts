import type {
  PurchaseDemandBatchAdjustmentRow,
  PurchaseDemandBatchQuantityInput,
  PurchaseDemandBatchSupplierInput,
  PurchaseDemandMergeBatchPage,
  PurchaseDemandMergeBatchRow,
  PurchaseDemandMergeCapabilities,
  PurchaseDemandMergeCreateInput,
  PurchaseDemandMergeDetail,
  PurchaseDemandMergeDraftDetail,
  PurchaseDemandMergeDraftPage,
  PurchaseDemandMergeDraftRow,
  PurchaseDemandMergeLinePage,
  PurchaseDemandMergeLineRow,
  PurchaseDemandMergeOptions,
  PurchaseDemandMergeUnmergedPage,
  PurchaseDemandMergeUnmergedRow,
  PurchaseDemandPrintInput,
  PurchaseDemandPrintResult,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 6.2.2.2 需求合并与批量前端模型。
 *
 * <p>蓝图：「把多个 SKU 的需求合并成一张采购单（同一供应商），支持批量生成、批量修改数量与
 * 供应商、批量打印。」输入「多行需求；供应商选择（相同供应商才能合并）」。
 * 输出「合并后的采购单草稿；未合并行说明原因」。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>合并只在同一供应商内进行</b>：一张草稿只属于一个供应商。跨供应商的需求走
 *       「未合并行」并带**明确原因** —— 页面必须把那句原因**原样**显示出来，
 *       既不静默丢弃，也不偷偷归到第一个供应商。</li>
 *   <li><b>草稿不是采购单</b>：6.3.1.2 采购单未交付，本模块只产出**采购单草稿**。
 *       页面**不出现**「采购单已创建」这类字样。</li>
 *   <li><b>「答不出来」不是 0</b>：预估金额取不到时显示「无数据」，
 *       绝不显示 0 —— 0 会被读成「这笔采购不要钱」。</li>
 *   <li><b>批量动作整体生效或整体拒绝</b>：页面不提供「改一半」的中间操作，
 *       批量动作失败时要把「什么都没改」这件事说清楚。</li>
 *   <li><b>不重算后端的结论</b>：状态标签、原因码、未合并原因、金额一律原样显示后端给的。</li>
 *   <li>加载 / 空 / 错误三态齐全，且三态文案各不相同。</li>
 * </ul>
 */

const base = 'trade/demand-merges';

// ================================================================ 查询参数

export interface DemandMergeParams {
  // `| undefined` 是 `exactOptionalPropertyTypes` 下的必需写法：
  // 筛选器常常把「可能没有」的值直接透传，不显式声明 undefined 的话
  // TS 会拒绝「显式传 undefined」。
  status?: string | undefined;
  keyword?: string | undefined;
  sort?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export function demandMergeQuery(params: DemandMergeParams): string {
  const parts: string[] = [];
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export interface DemandMergeDraftParams {
  supplierId?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export function demandMergeDraftQuery(params: DemandMergeDraftParams): string {
  const parts: string[] = [];
  if (params.supplierId) parts.push(`supplierId=${encodeURIComponent(params.supplierId)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export interface DemandMergeLineParams {
  draftId?: string | undefined;
  supplierId?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export function demandMergeLineQuery(params: DemandMergeLineParams): string {
  const parts: string[] = [];
  if (params.draftId) parts.push(`draftId=${encodeURIComponent(params.draftId)}`);
  if (params.supplierId) parts.push(`supplierId=${encodeURIComponent(params.supplierId)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export interface DemandMergeUnmergedParams {
  reasonCode?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export function demandMergeUnmergedQuery(params: DemandMergeUnmergedParams): string {
  const parts: string[] = [];
  if (params.reasonCode) parts.push(`reasonCode=${encodeURIComponent(params.reasonCode)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 调用点

export function fetchDemandMergeCapabilities() {
  return api<PurchaseDemandMergeCapabilities>(`${base}/capabilities`);
}

export function fetchDemandMergeOptions() {
  return api<PurchaseDemandMergeOptions>(`${base}/options`);
}

export function fetchDemandMergeBatches(params: DemandMergeParams) {
  return api<PurchaseDemandMergeBatchPage>(`${base}${demandMergeQuery(params)}`);
}

export function fetchDemandMergeBatch(id: string) {
  return api<PurchaseDemandMergeDetail>(`${base}/${encodeURIComponent(id)}`);
}

/** 批量生成：把多行需求按供应商合并成采购单**草稿**。 */
export function createDemandMerge(input: PurchaseDemandMergeCreateInput) {
  return api<PurchaseDemandMergeDetail>(`${base}`, 'POST', input);
}

export function fetchDemandMergeDrafts(batchId: string, params: DemandMergeDraftParams) {
  return api<PurchaseDemandMergeDraftPage>(
    `${base}/${encodeURIComponent(batchId)}/drafts${demandMergeDraftQuery(params)}`,
  );
}

export function fetchDemandMergeDraft(batchId: string, draftId: string) {
  return api<PurchaseDemandMergeDraftDetail>(
    `${base}/${encodeURIComponent(batchId)}/drafts/${encodeURIComponent(draftId)}`,
  );
}

export function fetchDemandMergeLines(batchId: string, params: DemandMergeLineParams) {
  return api<PurchaseDemandMergeLinePage>(
    `${base}/${encodeURIComponent(batchId)}/lines${demandMergeLineQuery(params)}`,
  );
}

export function fetchDemandMergeUnmerged(batchId: string, params: DemandMergeUnmergedParams) {
  return api<PurchaseDemandMergeUnmergedPage>(
    `${base}/${encodeURIComponent(batchId)}/unmerged${demandMergeUnmergedQuery(params)}`,
  );
}

/** 批量改数量：整体生效或整体拒绝。 */
export function adjustDemandMergeQuantity(
  batchId: string,
  input: PurchaseDemandBatchQuantityInput,
) {
  return api<PurchaseDemandMergeDetail>(
    `${base}/${encodeURIComponent(batchId)}/quantity`,
    'POST',
    input,
  );
}

/** 批量改供应商：搬行之后重新校验分组，不满足就整批拒绝。 */
export function adjustDemandMergeSupplier(
  batchId: string,
  input: PurchaseDemandBatchSupplierInput,
) {
  return api<PurchaseDemandMergeDetail>(
    `${base}/${encodeURIComponent(batchId)}/supplier`,
    'POST',
    input,
  );
}

export function finalizeDemandMergeBatch(batchId: string) {
  return api<PurchaseDemandMergeBatchRow>(
    `${base}/${encodeURIComponent(batchId)}/finalize`,
    'POST',
    {},
  );
}

/** 批量打印：把选中的采购单草稿渲染成可打印的文本。 */
export function printDemandMerges(input: PurchaseDemandPrintInput) {
  return api<PurchaseDemandPrintResult>(`${base}/print`, 'POST', input);
}

// ================================================================ 展示口径

/** 「无数据」的统一文案。null 一律用它 —— 0 会被读成「不用订 / 不要钱」。 */
export const NO_DATA_TEXT = '无数据';

/** 数量的展示。null 一律给「无数据」。 */
export function fmt(value: number | null | undefined): string {
  if (value === null || value === undefined) return NO_DATA_TEXT;
  const rounded = Number(value.toFixed(6));
  return rounded.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/** 金额的展示。null = 金额**算不出来**（有行没填预估单价），**不是 0**。 */
export function fmtAmount(value: number | null | undefined): string {
  if (value === null || value === undefined) return NO_DATA_TEXT;
  const rounded = Number(value.toFixed(4));
  return rounded.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 });
}

/** 到货日的展示。null = 交期还没定，**不是**「不需要交期」。 */
export function fmtDate(value: string | null | undefined): string {
  if (!value) return '未定';
  return value;
}

/** 合并批次状态的标签（与后端 PurchaseDemandMergeBatchStatus 一一对应）。 */
export const demandMergeBatchStatusLabels: Record<string, string> = {
  OPEN: '可批量修改',
  FINALIZED: '已定稿',
};

export function batchStatusLabel(status: string): string {
  return demandMergeBatchStatusLabels[status] ?? status;
}

export function batchStatusTone(status: string): 'success' | 'info' | 'neutral' {
  switch (status) {
    case 'OPEN':
      return 'info';
    case 'FINALIZED':
      return 'success';
    default:
      return 'neutral';
  }
}

/** 采购单草稿状态的标签（与后端 PurchaseDemandMergeDraftStatus 一一对应）。 */
export const demandMergeDraftStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  CONVERTED: '已转采购单',
};

export function draftStatusLabel(status: string): string {
  return demandMergeDraftStatusLabels[status] ?? status;
}

/**
 * 未合并原因码的标签（与后端 PurchaseDemandMergeReasonCode 一一对应）。
 *
 * <p>🔴 这张表**必须**与后端枚举同增同减：原因码是「为什么这行没合上」的凭据，
 * 页面上出现一个后端没定义的码（后端先加了、页面没跟上）会让运营看不懂。
 */
export const demandMergeReasonLabels: Record<string, string> = {
  REQUEST_NOT_APPROVED: '源申请单还没审',
  REQUEST_REJECTED: '源申请单已被驳回',
  REQUEST_ALREADY_CONVERTED: '源申请单已转单',
  SUPPLIER_MISSING: '还没选供应商',
  SUPPLIER_MISMATCH: '跨供应商，不能合并',
  TARGET_MISMATCH: '目标仓 / 法人不一致',
  CURRENCY_MISMATCH: '币种不一致',
  ALREADY_MERGED: '已被合并过',
  PRICE_CONFLICT: '同一 SKU 单价冲突',
};

export function reasonCodeLabel(code: string): string {
  return demandMergeReasonLabels[code] ?? code;
}

/**
 * 原因码的视觉分级。
 *
 * <p>跨供应商与单价冲突是**跨供应商合并会导致对账与付款错误**那类问题的直接体现，
 * 用警示色；已驳回是危险色；其余是中性色。
 */
export function reasonTone(code: string): 'danger' | 'warning' | 'neutral' {
  switch (code) {
    case 'REQUEST_REJECTED':
      return 'danger';
    case 'SUPPLIER_MISMATCH':
    case 'PRICE_CONFLICT':
    case 'CURRENCY_MISMATCH':
    case 'TARGET_MISMATCH':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 这一批还能不能改内容（定稿后内容冻结）。 */
export function isBatchEditable(status: string): boolean {
  return status === 'OPEN';
}

/**
 * 这一批能不能定稿。
 *
 * <p>🔴 有未合并行时**不许**定稿 —— 把没合上的需求悄悄带过去，比定不了稿更糟。
 */
export function canFinalize(status: string, unmergedCount: number): boolean {
  return isBatchEditable(status) && unmergedCount === 0;
}

/**
 * 按草稿把行分组（一张草稿只属于一个供应商）。
 *
 * <p>前端**不重算**分组 —— 后端已经按供应商分好并写进了草稿；这里只是把同一草稿的行
 * 摆在一起显示。分组键取自草稿 id，而不是行的 supplier_id：
 * 万一后端返回了分组异常的行，显示的是**后端的事实**，前端不替它纠正。
 */
export function groupLinesByDraft(
  lines: PurchaseDemandMergeLineRow[],
): Map<string, PurchaseDemandMergeLineRow[]> {
  const grouped = new Map<string, PurchaseDemandMergeLineRow[]>();
  for (const line of lines) {
    const bucket = grouped.get(line.draftId);
    if (bucket) bucket.push(line);
    else grouped.set(line.draftId, [line]);
  }
  return grouped;
}

/** 未合并行按原因码的计数（页面上给出「哪一类原因最多」）。 */
export function countByReason(rows: PurchaseDemandMergeUnmergedRow[]): Record<string, number> {
  const counts: Record<string, number> = {};
  for (const row of rows) counts[row.reasonCode] = (counts[row.reasonCode] ?? 0) + 1;
  return counts;
}

/**
 * 批量动作选了哪些行 —— 只允许选**同一批次**里的行。
 *
 * <p>跨批次的行混进一次批量动作里会得到一句含混的拒绝，这里提前挡住。
 */
export function selectableLineIds(
  lines: PurchaseDemandMergeLineRow[],
  selected: readonly string[],
): string[] {
  const known = new Set(lines.map((line) => line.id));
  return selected.filter((id) => known.has(id));
}

/** 批量修改留痕的可读摘要。 */
export function adjustmentSummary(adjustment: PurchaseDemandBatchAdjustmentRow): string {
  const field = adjustment.field === 'merged_quantity' ? '数量' : '供应商';
  const before = adjustment.beforeValue ?? '无';
  const after = adjustment.afterValue ?? '无';
  return `${adjustment.skuCode} · ${field} ${before} → ${after}`;
}
