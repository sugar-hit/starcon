import type {
  ReconciliationDiffAdjustmentInput,
  ReconciliationDiffAdjustmentRow,
  ReconciliationDiffAssignInput,
  ReconciliationDiffCapabilities,
  ReconciliationDiffClaimInput,
  ReconciliationDiffClassifyInput,
  ReconciliationDiffDecideInput,
  ReconciliationDiffDetail,
  ReconciliationDiffEventPage,
  ReconciliationDiffHandling,
  ReconciliationDiffOptions,
  ReconciliationDiffQueuePage,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.3.2.2 差异处理的前端模型。
 *
 * <p>蓝图 12-finance.md：「处理对账差异：分类（我方多记/对方多记/时间差/汇率差/手续费遗漏）、
 * 指定责任人、人工判定与调整、闭环跟踪。」输入：「差异明细；处理结论；调整凭证或说明。」
 * 输出：「差异处理记录 + 调整分录 + 闭环状态。」加粗句：「<b>差异分类是改进闭环的输入</b>：
 * 某类差异反复出现说明上游流程有系统性问题。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>金额只原样展示</b>：上游差额、调整金额都由**服务端**算好，页面**不做任何
 *       前端算术**（USD 与 EUR 加起来不是任何一个金额）。</li>
 *   <li><b>按币种分组</b>：汇总**按币种分开呈现**，<b>绝不</b>把不同币种摆进同一列相加；
 *       汇率不可得时差额显示「—」而**不是 0**（写 0 会被读成「账是平的」）。</li>
 *   <li><b>分类是改进闭环的输入</b>：页面把「分类 × 币种」的分布摆在最显眼处 ——
 *       某一类反复出现，就是上游流程有系统性问题。</li>
 *   <li><b>「为什么不能点」必须说清楚</b>：认领 / 分类 / 登记分录 / 判定闭环各有各的闸门
 *       （没有责任人、没有分类、没有调整分录都不许闭环），页面据此禁用按钮并把
 *       **阻断理由**写在旁边。</li>
 *   <li><b>闭环即冻结</b>：已调整 / 已核销是终态，页面不再提供任何处理动作。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「还没有差异」与「筛选无结果」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code finance/reconciliation-differences}，
 * 与 12.3.2.1 的 {@code finance/reconciliation-tasks}、
 * 12.7.1.2 的 {@code finance/bank-reconciliation}、
 * 12.1.2.3 的 {@code finance/supplier-reconciliation} 各自独立前缀。
 */

const base = 'finance/reconciliation-differences';

/** 队列筛选条件（可选属性显式带 `| undefined`：仓库开了 `exactOptionalPropertyTypes`）。 */
export interface ReconciliationDiffParams {
  taskId?: string | undefined;
  status?: string | undefined;
  category?: string | undefined;
  currency?: string | undefined;
  assigneeId?: string | undefined;
  differenceType?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 认领入参（**直接用生成契约的类型**，不另起一份 —— 少一份漂移源）。 */
export type ReconciliationDiffClaim = ReconciliationDiffClaimInput;
/** 改派责任人入参（**只限已认领、且未闭环**）。 */
export type ReconciliationDiffAssign = ReconciliationDiffAssignInput;
/** 分类归因入参。 */
export type ReconciliationDiffClassify = ReconciliationDiffClassifyInput;
/** 调整分录入参。 */
export type ReconciliationDiffAdjustment = ReconciliationDiffAdjustmentInput;
/** 人工判定入参（调整 / 核销共用）。 */
export type ReconciliationDiffDecide = ReconciliationDiffDecideInput;

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

/** 能力声明：哪些真接通、哪些还没交付、归谁。 */
export function fetchReconciliationDiffCapabilities() {
  return api<ReconciliationDiffCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（任务 / 币种 / 责任人都来自真实数据）。 */
export function fetchReconciliationDiffOptions() {
  return api<ReconciliationDiffOptions>(`${base}/options`);
}

/** 差异处理队列（真分页 + 真筛选 + 真搜索 + 按币种分组的汇总）。 */
export function fetchReconciliationDiffs(params: ReconciliationDiffParams = {}) {
  return api<ReconciliationDiffQueuePage>(`${base}${query({ ...params })}`);
}

/** 一条差异的处理详情（差异 + 调整分录 + 时间线 + 可执行动作）。 */
export function fetchReconciliationDiff(id: string) {
  return api<ReconciliationDiffDetail>(`${base}/${encodeURIComponent(id)}`);
}

/** 处理时间线（真分页；append-only 举证）。 */
export function fetchReconciliationDiffEvents(
  id: string,
  params: { page?: number; size?: number } = {},
) {
  return api<ReconciliationDiffEventPage>(
    `${base}/${encodeURIComponent(id)}/events${query({ ...params })}`,
  );
}

/** 认领这条差异（不传 assigneeId = 认领人就是操作者自己）。 */
export function claimReconciliationDiff(id: string, input: ReconciliationDiffClaim) {
  return api<ReconciliationDiffHandling>(`${base}/${encodeURIComponent(id)}/claim`, 'POST', input);
}

/** 改派责任人（**只限已认领、且未闭环**）。 */
export function assignReconciliationDiff(id: string, input: ReconciliationDiffAssign) {
  return api<ReconciliationDiffHandling>(`${base}/${encodeURIComponent(id)}/assign`, 'POST', input);
}

/** 分类归因（**分类 + 理由都必填**）。 */
export function classifyReconciliationDiff(id: string, input: ReconciliationDiffClassify) {
  return api<ReconciliationDiffHandling>(`${base}/${encodeURIComponent(id)}/classify`, 'POST', input);
}

/** 登记调整分录（**凭证或说明**至少有一个）。 */
export function recordReconciliationAdjustment(
  id: string,
  input: ReconciliationDiffAdjustment,
) {
  return api<ReconciliationDiffAdjustmentRow>(
    `${base}/${encodeURIComponent(id)}/adjustments`,
    'POST',
    input,
  );
}

/** 人工判定：**已调整**（终态；至少要有一条调整分录）。 */
export function adjustReconciliationDiff(id: string, input: ReconciliationDiffDecide) {
  return api<ReconciliationDiffHandling>(`${base}/${encodeURIComponent(id)}/adjust`, 'POST', input);
}

/** 人工判定：**已核销**（终态；= 判定不需要调整）。 */
export function writeOffReconciliationDiff(id: string, input: ReconciliationDiffDecide) {
  return api<ReconciliationDiffHandling>(`${base}/${encodeURIComponent(id)}/write-off`, 'POST', input);
}

// ================================================================ 展示口径

/** 处理状态文案。 */
export function reconciliationDiffStatusLabel(status: string): string {
  switch (status) {
    case 'OPEN':
      return '待处理';
    case 'CLAIMED':
      return '已认领';
    case 'ADJUSTED':
      return '已调整（终态）';
    case 'WRITTEN_OFF':
      return '已核销（终态）';
    default:
      return status;
  }
}

/** 处理状态色调。 */
export function reconciliationDiffStatusTone(
  status: string,
): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'ADJUSTED':
    case 'WRITTEN_OFF':
      return 'success';
    case 'CLAIMED':
      return 'warning';
    case 'OPEN':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 归因分类文案（**改进闭环的输入**）。 */
export function reconciliationDiffCategoryLabel(category: string): string {
  switch (category) {
    case 'BOOK_OVER_RECORDED':
      return '我方多记';
    case 'COUNTERPARTY_OVER_RECORDED':
      return '对方多记';
    case 'TIMING_DIFFERENCE':
      return '时间差';
    case 'FX_DIFFERENCE':
      return '汇率差';
    case 'FEE_OMISSION':
      return '手续费遗漏';
    default:
      return category;
  }
}

/** 上游差异类型文案（说的是「差在哪儿」，与归因分类不是一回事）。 */
export function reconciliationDiffTypeLabel(type: string): string {
  switch (type) {
    case 'EXTERNAL_ONLY':
      return '平台账单多出';
    case 'BOOK_ONLY':
      return '我方账多出';
    case 'AMOUNT_MISMATCH':
      return '金额对不齐';
    case 'CURRENCY_MISMATCH':
      return '币种不一致';
    case 'DIMENSION_MISMATCH':
      return '维度对不上';
    default:
      return type;
  }
}

/** 资金方向文案。 */
export function reconciliationDiffDirectionLabel(direction: string): string {
  switch (direction) {
    case 'IN':
      return '收入';
    case 'OUT':
      return '支出';
    default:
      return direction;
  }
}

/** 事件类型文案。 */
export function reconciliationDiffEventLabel(eventType: string): string {
  switch (eventType) {
    case 'HANDLING_CREATED':
      return '建立处理记录';
    case 'ASSIGNED':
      return '指定责任人';
    case 'CLASSIFIED':
      return '分类归因';
    case 'ADJUSTMENT_RECORDED':
      return '登记调整分录';
    case 'ADJUSTED':
      return '判定已调整';
    case 'WRITTEN_OFF':
      return '判定已核销';
    default:
      return eventType;
  }
}

/** 金额展示：原样显示服务端给的数（**页面不做任何前端算术**）。 */
export function formatReconciliationDiffMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '—';
  const text = amount.toFixed(4);
  return currency ? `${text} ${currency}` : text;
}

/**
 * 差额展示：**算不出来就说算不出来**。
 *
 * <p>⚠️ 汇率不可得时服务端给 {@code null} —— 页面显示「—（汇率不可得）」，
 * **不显示 0**（0 会被读成「账是平的」）。
 */
export function formatReconciliationDiffDifference(
  differenceAmount: number | null | undefined,
  currency: string | null | undefined,
  exchangeRateAvailable: boolean,
): string {
  if (differenceAmount === null || differenceAmount === undefined) {
    return exchangeRateAvailable ? '—' : '—（汇率不可得，不按 1:1 兜底）';
  }
  const text = differenceAmount.toFixed(4);
  return currency ? `${text} ${currency}` : text;
}

/** 一个币种汇总的一行结论（**按币种分开，绝不相加**）。 */
export function reconciliationDiffCurrencySummaryLabel(summary: {
  currency: string;
  differenceAmount: number | null;
  adjustedAmount: number | null;
  adjustmentCount: number;
  openCount: number;
  claimedCount: number;
  adjustedCount: number;
  writtenOffCount: number;
  exchangeRateAvailable: boolean;
}): string {
  const delta = formatReconciliationDiffDifference(
    summary.differenceAmount,
    summary.currency,
    summary.exchangeRateAvailable,
  );
  const adjusted =
    summary.adjustedAmount === null || summary.adjustedAmount === undefined
      ? '—'
      : formatReconciliationDiffMoney(summary.adjustedAmount, summary.currency);
  return (
    `上游差额 ${delta} / 调整额 ${adjusted}（${summary.adjustmentCount} 条）· ` +
    `待处理 ${summary.openCount} · 已认领 ${summary.claimedCount} · ` +
    `已调整 ${summary.adjustedCount} · 已核销 ${summary.writtenOffCount}`
  );
}

/** 一条差异的一行结论（人话）。 */
export function reconciliationDiffHint(row: {
  differenceType: string;
  externalAmount: number | null;
  bookAmount: number | null;
  currency: string;
  differenceAmount: number | null;
  exchangeRateAvailable: boolean;
}): string {
  const external = formatReconciliationDiffMoney(row.externalAmount, row.currency);
  const book = formatReconciliationDiffMoney(row.bookAmount, row.currency);
  const delta = formatReconciliationDiffDifference(
    row.differenceAmount,
    row.currency,
    row.exchangeRateAvailable,
  );
  return `平台 ${external} / 我方 ${book} / 差额 ${delta}`;
}

/**
 * 责任人的展示文案。
 *
 * <p>⚠️ 本仓**没有用户目录服务** ⇒ 后端**只存 uuid**。页面**不编造姓名**，
 * 只显示 uuid 的前 8 位（够认人），完整 id 放在 title 里。
 */
export function reconciliationDiffAssigneeLabel(assigneeId: string | null | undefined): string {
  if (!assigneeId) return '未指派';
  return assigneeId.slice(0, 8);
}

/**
 * 「为什么不能点」的说明 —— 页面据此禁用按钮并把理由写在旁边。
 *
 * <p>⚠️ 理由来自**服务端**（`actions.*BlockReason`），页面不自己推断规则。
 */
export function reconciliationDiffActionHints(actions: {
  canClaim: boolean;
  claimBlockReason: string;
  canAssign: boolean;
  assignBlockReason: string;
  canClassify: boolean;
  classifyBlockReason: string;
  canRecordAdjustment: boolean;
  recordAdjustmentBlockReason: string;
  canAdjust: boolean;
  adjustBlockReason: string;
  canWriteOff: boolean;
  writeOffBlockReason: string;
}): string[] {
  const hints: string[] = [];
  if (!actions.canClaim && actions.claimBlockReason) hints.push(actions.claimBlockReason);
  if (!actions.canAssign && actions.assignBlockReason) hints.push(actions.assignBlockReason);
  if (!actions.canClassify && actions.classifyBlockReason) hints.push(actions.classifyBlockReason);
  if (!actions.canRecordAdjustment && actions.recordAdjustmentBlockReason) {
    hints.push(actions.recordAdjustmentBlockReason);
  }
  if (!actions.canAdjust && actions.adjustBlockReason) hints.push(actions.adjustBlockReason);
  if (!actions.canWriteOff && actions.writeOffBlockReason) hints.push(actions.writeOffBlockReason);
  return hints;
}

/**
 * 调整分录的「凭证或说明」校验提示。
 *
 * <p>⚠️ 这是**界面提示**，服务端也会拒（同一条规则），页面提前说清省一次往返。
 */
export function reconciliationDiffAdjustmentBasisHint(input: {
  voucherNo?: string | null | undefined;
  explanation?: string | null | undefined;
}): string {
  const voucher = (input.voucherNo ?? '').trim();
  const explanation = (input.explanation ?? '').trim();
  if (voucher === '' && explanation === '') {
    return '调整凭证号与说明至少要有一个（两者都没有 = 这条分录什么都不是）。';
  }
  return '';
}

/** 分类理由是否为空（人工分类 + 理由是蓝图点名的输入）。 */
export function reconciliationDiffClassifyHint(input: { reason?: string | null | undefined }): string {
  return (input.reason ?? '').trim() === '' ? '分类理由不能为空（人工分类 + 理由）。' : '';
}
