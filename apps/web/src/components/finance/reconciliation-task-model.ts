import type {
  FinanceReconCapabilities,
  FinanceReconDifferencePage,
  FinanceReconEventPage,
  FinanceReconMatchPage,
  FinanceReconMatchRunResult,
  FinanceReconOptions,
  FinanceReconTaskCreateInput,
  FinanceReconTaskDetail,
  FinanceReconTaskPage,
  FinanceReconTaskRow,
  FinanceReconTaskUpdateInput,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.3.2.1 对账任务的前端模型。
 *
 * <p>蓝图 12-finance.md：「发起与执行对账任务：选择对账范围（店铺 + 周期 + 账户）、
 * 运行自动匹配、输出匹配结果与差异。」输出：「匹配结果（一对一、一对多、多对一）+ 差异清单。」
 * 加粗句：「<b>不允许『金额相同即匹配』</b>——金额相同只是候选，必须结合订单号、日期、类型。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>金额只原样展示</b>：平台侧合计、我方侧合计、差额都由**服务端**算好，
 *       页面**不做任何前端算术**（USD 与 EUR 加起来不是任何一个金额）。</li>
 *   <li><b>按币种分组</b>：汇总与差异清单**按币种分开呈现**，
 *       <b>绝不</b>把不同币种摆进同一列相加；汇率不可得时差额显示「—」而**不是 0**
 *       （写 0 会被读成「账是平的」）。</li>
 *   <li><b>匹配形态必须看得见</b>：一对一 / 一对多 / 多对一 / 多对多是四个不同的徽章，
 *       页面同时显示「比对了哪几个维度」（人话说明），让人知道**为什么**这两行配成一对。</li>
 *   <li><b>「差一点点」也要看得见</b>：差异类型里的「维度对不上」= 两笔金额一样、
 *       但只对上 1 个维度 ⇒ **不构成匹配**。这是「金额相同只是候选」的可观测证据。</li>
 *   <li><b>已执行的任务不可改写</b>：页面在 MATCHED 态**不提供**「改范围」「重跑匹配」，
 *       并写清「要重来请新建一个任务」。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「还没建任务」与「筛选无结果」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code finance/reconciliation-tasks}，
 * 与 12.7.1.2 的 {@code finance/bank-reconciliation}、
 * 12.1.2.3 的 {@code finance/supplier-reconciliation} 各自独立前缀。
 */

const base = 'finance/reconciliation-tasks';

/** 列表筛选条件（可选属性显式带 `| undefined`：仓库开了 `exactOptionalPropertyTypes`）。 */
export interface ReconciliationTaskParams {
  accountId?: string | undefined;
  shopId?: string | undefined;
  status?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 匹配结果的筛选条件。 */
export interface ReconciliationMatchParams {
  shape?: string | undefined;
  bookSource?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 差异清单的筛选条件。 */
export interface ReconciliationDifferenceParams {
  differenceType?: string | undefined;
  currency?: string | undefined;
  side?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 建 / 改任务时的入参（**直接用生成契约的类型**，不另起一份 —— 少一份漂移源）。 */
export type ReconciliationTaskInput = FinanceReconTaskCreateInput;
/** 改范围入参。 */
export type ReconciliationTaskUpdateInput = FinanceReconTaskUpdateInput;

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

/** 能力声明：哪些上游真接通、哪些还没交付、归谁。 */
export function fetchReconciliationCapabilities() {
  return api<FinanceReconCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（全部来自真实数据）。 */
export function fetchReconciliationOptions() {
  return api<FinanceReconOptions>(`${base}/options`);
}

/** 对账任务列表（真分页 + 真筛选 + 真搜索）。 */
export function fetchReconciliationTasks(params: ReconciliationTaskParams = {}) {
  return api<FinanceReconTaskPage>(`${base}${query({ ...params })}`);
}

/** 对账任务详情（任务 + 按币种分组的汇总）。 */
export function fetchReconciliationTask(id: string) {
  return api<FinanceReconTaskDetail>(`${base}/${encodeURIComponent(id)}`);
}

/** 建对账任务（选择对账范围：店铺 + 周期 + 账户）。 */
export function createReconciliationTask(input: ReconciliationTaskInput) {
  return api<FinanceReconTaskRow>(base, 'POST', input);
}

/** 改对账范围（**只限 DRAFT 态**）。 */
export function updateReconciliationTask(id: string, input: ReconciliationTaskUpdateInput) {
  return api<FinanceReconTaskRow>(`${base}/${encodeURIComponent(id)}`, 'PUT', input);
}

/**
 * 跑自动匹配。
 *
 * <p>⚠️ 匹配由**服务端**按「至少两个独立维度」跑，页面**不**自己配对，
 * 也**不**提供「手动把这两行连起来」的按钮。
 */
export function runReconciliationMatch(id: string) {
  return api<FinanceReconMatchRunResult>(`${base}/${encodeURIComponent(id)}/match`, 'POST');
}

/** 匹配结果（真分页 + 真筛选：形态 / 我方账来源）。 */
export function fetchReconciliationMatches(id: string, params: ReconciliationMatchParams = {}) {
  return api<FinanceReconMatchPage>(
    `${base}/${encodeURIComponent(id)}/matches${query({ ...params })}`,
  );
}

/** 差异清单（真分页 + 真筛选：类型 / 币种 / 侧）。 */
export function fetchReconciliationDifferences(
  id: string,
  params: ReconciliationDifferenceParams = {},
) {
  return api<FinanceReconDifferencePage>(
    `${base}/${encodeURIComponent(id)}/differences${query({ ...params })}`,
  );
}

/** 事件流（真分页；append-only 举证）。 */
export function fetchReconciliationEvents(id: string, params: { page?: number; size?: number } = {}) {
  return api<FinanceReconEventPage>(
    `${base}/${encodeURIComponent(id)}/events${query({ ...params })}`,
  );
}

// ================================================================ 展示口径

/** 任务状态文案。 */
export function reconciliationStatusLabel(status: string): string {
  switch (status) {
    case 'DRAFT':
      return '待执行匹配';
    case 'MATCHED':
      return '已匹配（结果不可改写）';
    default:
      return status;
  }
}

/** 任务状态色调。 */
export function reconciliationStatusTone(
  status: string,
): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'MATCHED':
      return 'success';
    case 'DRAFT':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 匹配形态文案（一对一 / 一对多 / 多对一 必须一眼分得清）。 */
export function reconciliationShapeLabel(shape: string): string {
  switch (shape) {
    case 'ONE_TO_ONE':
      return '一对一';
    case 'ONE_TO_MANY':
      return '一对多';
    case 'MANY_TO_ONE':
      return '多对一';
    case 'MANY_TO_MANY':
      return '多对多';
    default:
      return shape;
  }
}

/** 匹配形态色调。 */
export function reconciliationShapeTone(
  shape: string,
): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (shape) {
    case 'ONE_TO_ONE':
      return 'success';
    case 'ONE_TO_MANY':
    case 'MANY_TO_ONE':
      return 'warning';
    case 'MANY_TO_MANY':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 差异类型文案。 */
export function reconciliationDifferenceTypeLabel(type: string): string {
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

/** 差异类型色调。 */
export function reconciliationDifferenceTone(
  type: string,
): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (type) {
    case 'EXTERNAL_ONLY':
    case 'BOOK_ONLY':
    case 'AMOUNT_MISMATCH':
      return 'warning';
    case 'CURRENCY_MISMATCH':
    case 'DIMENSION_MISMATCH':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 我方账来源文案。 */
export function reconciliationBookSourceLabel(source: string): string {
  switch (source) {
    case 'RECEIVABLE':
      return '应收明细（12.1.1.1）';
    case 'PAYMENT_ORDER':
      return '付款单明细（12.2.2.1）';
    case 'BANK_STATEMENT':
      return '银行流水（12.7.1.2）';
    default:
      return source;
  }
}

/** 平台账单侧来源文案。 */
export function reconciliationExternalKindLabel(kind: string): string {
  switch (kind) {
    case 'SETTLEMENT':
      return '结算单（12.3.1.1）';
    case 'SETTLEMENT_LINE':
      return '结算明细行（12.3.1.2）';
    default:
      return kind;
  }
}

/** 资金方向文案。 */
export function reconciliationDirectionLabel(direction: string): string {
  switch (direction) {
    case 'IN':
      return '收入';
    case 'OUT':
      return '支出';
    default:
      return direction;
  }
}

/** 金额展示：原样显示服务端给的数（**页面不做任何前端算术**）。 */
export function formatReconciliationMoney(
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
export function formatReconciliationDifference(
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

/** 匹配维度的中文标签（页面据此渲染「比对了哪几个维度」）。 */
export function reconciliationDimensionLabel(dimension: string): string {
  switch (dimension) {
    case 'REFERENCE':
      return '订单号';
    case 'DATE':
      return '日期';
    case 'TYPE':
      return '类型';
    case 'AMOUNT':
      return '金额';
    default:
      return dimension;
  }
}

/** 匹配维度列表的文案。 */
export function reconciliationDimensionsLabel(dimensions: string[]): string {
  return dimensions.map(reconciliationDimensionLabel).join(' + ');
}

/**
 * 一条差异的一行结论（人话）。
 *
 * <p>⚠️ 这里**只做文案拼装**，不做任何金额加减 —— 差额由服务端算好。
 */
export function reconciliationDifferenceHint(difference: {
  differenceType: string;
  externalAmount: number | null;
  bookAmount: number | null;
  currency: string;
  differenceAmount: number | null;
  exchangeRateAvailable: boolean;
}): string {
  const external = formatReconciliationMoney(difference.externalAmount, difference.currency);
  const book = formatReconciliationMoney(difference.bookAmount, difference.currency);
  const delta = formatReconciliationDifference(
    difference.differenceAmount,
    difference.currency,
    difference.exchangeRateAvailable,
  );
  return `平台 ${external} / 我方 ${book} / 差额 ${delta}`;
}

/** 一个币种汇总的一行结论（**按币种分开，绝不相加**）。 */
export function reconciliationCurrencySummaryLabel(summary: {
  currency: string;
  externalAmount: number;
  bookAmount: number;
  differenceAmount: number | null;
  matchedGroupCount: number;
  differenceCount: number;
  exchangeRateAvailable: boolean;
}): string {
  const delta = formatReconciliationDifference(
    summary.differenceAmount,
    summary.currency,
    summary.exchangeRateAvailable,
  );
  return `平台 ${formatReconciliationMoney(summary.externalAmount, summary.currency)} / 我方 ${formatReconciliationMoney(
    summary.bookAmount,
    summary.currency,
  )} / 差额 ${delta} · 匹配组 ${summary.matchedGroupCount} 个 · 差异 ${summary.differenceCount} 条`;
}

/**
 * 任务的「能不能跑匹配」提示。
 *
 * <p>⚠️ 页面据此**禁用**按钮并说明原因 —— 「为什么不能点」必须说清楚。
 */
export function reconciliationMatchBlockReason(row: { status: string }): string {
  if (row.status === 'MATCHED') {
    return '已执行完成：匹配结果与差异清单不可改写（要重来请新建一个任务）';
  }
  return '';
}

/** 差异的「能不能处理」提示 —— 12.3.2.2 未交付 ⇒ 一律不能处理。 */
export function reconciliationDifferenceActionHint(row: { status: string }): string {
  if (row.status === 'OPEN') {
    return '差异的认领 / 调整 / 核销属 12.3.2.2（未交付）：本页只输出差异清单，不提供处理动作';
  }
  return '';
}

/** 差异处理状态文案（12.3.2.2 未交付 ⇒ 只有「待处理」一种真实状态）。 */
export function reconciliationDifferenceStatusLabel(status: string): string {
  switch (status) {
    case 'OPEN':
      return '待处理（12.3.2.2 未交付）';
    default:
      return status;
  }
}

/** 差异所在侧文案。 */
export function reconciliationSideLabel(side: string): string {
  switch (side) {
    case 'EXTERNAL':
      return '平台账单多出';
    case 'BOOK':
      return '我方账多出';
    default:
      return side;
  }
}

/** 事件类型文案。 */
export function reconciliationEventLabel(eventType: string): string {
  switch (eventType) {
    case 'TASK_CREATED':
      return '建立对账任务';
    case 'TASK_SCOPE_UPDATED':
      return '改动对账范围';
    case 'MATCH_COMPLETED':
      return '完成自动匹配';
    default:
      return eventType;
  }
}

/**
 * 对账范围的一行说明（店铺 + 周期 + 账户 + 我方账来源）。
 *
 * <p>⚠️ 店铺**可能为空**（= 该账户下全部授权店铺）—— 契约里 {@code shopId} 标了可空，
 * 页面不许把它渲染成 {@code null} 或 {@code undefined}。
 */
export function reconciliationScopeLabel(row: {
  accountNo: string;
  accountName: string;
  shopName: string;
  shopId: string | null;
  platformCode: string;
  periodStart: string;
  periodEnd: string;
  bookSources: string[];
}): string {
  const shop = row.shopId ? `${row.shopName}（${row.platformCode || '未标注平台'}）` : '全部授权店铺';
  const sources = row.bookSources.map(reconciliationBookSourceLabel).join(' + ');
  return `${row.accountNo} ${row.accountName} · ${shop} · ${row.periodStart} → ${row.periodEnd} · 我方账：${sources}`;
}

/**
 * 一个任务「为什么还不能改范围」的说明。
 *
 * <p>⚠️ 已执行的任务**范围被冻结**（DB 触发器钉死）⇒ 页面在 MATCHED 态**不提供**
 * 「改范围」，并把「要重来请新建一个任务」写在页面上，而不是让人点了才报错。
 */
export function reconciliationScopeFrozenHint(row: { status: string }): string {
  if (row.status === 'MATCHED') {
    return '该任务已执行完成：对账范围已冻结，匹配结果与差异清单不可改写。要重来请新建一个任务。';
  }
  return '';
}

/**
 * 一条匹配结果的「为什么配成一对」说明（人话）。
 *
 * <p>⚠️ 这里**只做文案拼装**，不做任何金额加减 —— 差额由服务端算好。
 */
export function reconciliationMatchHint(match: {
  shape: string;
  matchedDimensions: string[];
  dimensionCount: number;
  matchBasis: string;
  dayDifference: number;
}): string {
  const dims = reconciliationDimensionsLabel(match.matchedDimensions);
  const day = match.dayDifference === 0 ? '同一天' : `相差 ${match.dayDifference} 天`;
  return `${reconciliationShapeLabel(match.shape)} · 比对维度：${dims}（${match.dimensionCount} 个）· ${day} · ${match.matchBasis}`;
}
