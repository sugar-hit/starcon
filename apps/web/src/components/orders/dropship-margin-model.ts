import type {
  DropshipFeeRateInput,
  DropshipFeeRatePage,
  DropshipFeeRateRow,
  DropshipFxRateInput,
  DropshipFxRatePage,
  DropshipFxRateRow,
  DropshipLossAlertAckInput,
  DropshipLossAlertDismissInput,
  DropshipLossAlertPage,
  DropshipLossAlertRow,
  DropshipMarginCapabilities,
  DropshipMarginEvaluateResult,
  DropshipMarginFactPage,
  DropshipMarginFactRow,
  DropshipMarginReport,
  DropshipPayableTaskPage,
  DropshipPayableTaskResultInput,
  DropshipPayableTaskRow,
  DropshipReconciliationDetail,
  DropshipReconciliationInput,
  DropshipReconciliationPage,
  DropshipReconciliationRow,
  DropshipReconciliationStatusInput,
  DropshipSettlementCycleInput,
  DropshipSettlementCyclePage,
  DropshipSettlementCycleRow,
  DropshipMarginEventPage,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 9.6.2.1 代发差价与对账前端模型。
 *
 * <p>蓝图：「管理代发业务的成本与结算：代发采购成本（供应商结算价 + 供应商运费）、
 * 我方售价、差价毛利；按周期与供应商对账，识别亏损代发单（售价低于代发成本+平台费）
 * 并预警。输出：代发差价与毛利报表（按店铺/供应商/SKU）；供应商对账单（→ 12 应付）；
 * 亏损单预警。」
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>「无法判定」不是「不亏」，也不是「差价 0」。</b>
 *       {@link spreadCells} 在平台费未登记时返回 <b>null</b> 的差价单元格并附
 *       {@link UNDETERMINED_HINT}，而不是把 null 格式化成 0.00 ——
 *       界面上「差价 0.00」与「差价算不出来」必须长得不一样。</li>
 *   <li><b>亏损三态三种颜色。</b>{@link lossVerdictBadge} 把
 *       {@code LOSS_CONFIRMED}（danger）/{@code NOT_LOSS}（ok）/
 *       {@code UNDETERMINED_NO_FEE}（unknown）分开，其中第三种**刻意不是**
 *       warning —— 它不是一个已确认的问题，画成警告会让人去查供应商，
 *       而真正该做的是把平台费登记补上。</li>
 *   <li><b>跨币种不相加。</b>{@link groupTotals} 按币种分别汇总；
 *       同一家供应商同时用 USD 与 CNY 结算时会出来两行，而不是一个假数。</li>
 *   <li><b>未登记桶不并进合计。</b>{@link reconciliationBuckets} 把
 *       「汇率未登记」「平台费未登记」各自单列，让 total_platform_fee = 0
 *       这种「其实只是没人登记过」的合计不被当成真实结论读出去。</li>
 *   <li><b>「已交给下游」≠「下游已受理」。</b>{@link payableTaskBadge} 只有
 *       {@code ACKNOWLEDGED} 才是完成态；{@code SENT} 显示「已发出（待受理）」，
 *       12.1.2.1 未交付时 {@code OPEN} 显示「待应付（12.1.2.1 未交付）」，
 *       **不画成「已生成应付单」**。</li>
 *   <li><b>代发与自营不是一套口径。</b>{@link basisBadge} 每行都带
 *       「代发 · 采购差价口径」，下游利润报表不得并入自营成本口径。</li>
 * </ul>
 */

// ------------------------------------------------------------ 参数

export interface DropshipMarginPageParams {
  page?: number;
  size?: number;
}

export interface DropshipMarginFactParams extends DropshipMarginPageParams {
  supplierCode?: string;
  shopCode?: string;
  orderNo?: string;
  lossVerdict?: string;
  keyword?: string;
}

export interface DropshipLossAlertParams extends DropshipMarginPageParams {
  supplierCode?: string;
  alertKind?: string;
  status?: string;
  severity?: string;
}

export interface DropshipReconciliationParams extends DropshipMarginPageParams {
  supplierCode?: string;
  status?: string;
  currencyCode?: string;
}

export interface DropshipPayableTaskParams extends DropshipMarginPageParams {
  taskStatus?: string;
  supplierCode?: string;
}

export interface DropshipMarginReportParams {
  groupBy?: string;
  periodStart: string;
  periodEnd: string;
  supplierCode?: string;
  shopCode?: string;
  currencyCode?: string;
}

function query<T extends object>(params: T): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params as Record<string, unknown>)) {
    if (value === undefined || value === null || value === '') continue;
    search.append(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

// ------------------------------------------------------------ 接口

const base = 'trade/fulfillment/dropship-margin';

export function fetchDropshipMarginCapabilities(): Promise<DropshipMarginCapabilities> {
  return api<DropshipMarginCapabilities>(`${base}/capabilities`);
}

export function fetchDropshipFeeRates(params: {
  keyword?: string;
} & DropshipMarginPageParams): Promise<DropshipFeeRatePage> {
  return api<DropshipFeeRatePage>(`${base}/fee-rates${query(params)}`);
}

export function saveDropshipFeeRate(input: DropshipFeeRateInput): Promise<DropshipFeeRateRow> {
  return api<DropshipFeeRateRow>(`${base}/fee-rates`, 'POST', input);
}

export function fetchDropshipFxRates(params: {
  baseCurrency?: string;
} & DropshipMarginPageParams): Promise<DropshipFxRatePage> {
  return api<DropshipFxRatePage>(`${base}/fx-rates${query(params)}`);
}

export function saveDropshipFxRate(input: DropshipFxRateInput): Promise<DropshipFxRateRow> {
  return api<DropshipFxRateRow>(`${base}/fx-rates`, 'POST', input);
}

export function fetchDropshipCycles(params: {
  supplierCode?: string;
} & DropshipMarginPageParams): Promise<DropshipSettlementCyclePage> {
  return api<DropshipSettlementCyclePage>(`${base}/cycles${query(params)}`);
}

export function saveDropshipCycle(
  input: DropshipSettlementCycleInput,
): Promise<DropshipSettlementCycleRow> {
  return api<DropshipSettlementCycleRow>(`${base}/cycles`, 'POST', input);
}

export function evaluateDropshipMarginFacts(params: {
  periodStart: string;
  periodEnd: string;
}): Promise<DropshipMarginEvaluateResult> {
  return api<DropshipMarginEvaluateResult>(
    `${base}/facts/evaluate${query(params)}`,
    'POST',
  );
}

export function fetchDropshipMarginFacts(
  params: DropshipMarginFactParams,
): Promise<DropshipMarginFactPage> {
  return api<DropshipMarginFactPage>(`${base}/facts${query(params)}`);
}

export function fetchDropshipMarginReport(
  params: DropshipMarginReportParams,
): Promise<DropshipMarginReport> {
  return api<DropshipMarginReport>(`${base}/report${query(params)}`);
}

export function evaluateDropshipLossAlerts(params: {
  periodStart: string;
  periodEnd: string;
}): Promise<DropshipMarginEvaluateResult> {
  return api<DropshipMarginEvaluateResult>(
    `${base}/alerts/evaluate${query(params)}`,
    'POST',
  );
}

export function fetchDropshipLossAlerts(
  params: DropshipLossAlertParams,
): Promise<DropshipLossAlertPage> {
  return api<DropshipLossAlertPage>(`${base}/alerts${query(params)}`);
}

export function acknowledgeDropshipLossAlert(
  alertId: string,
  input: DropshipLossAlertAckInput,
): Promise<DropshipLossAlertRow> {
  return api<DropshipLossAlertRow>(`${base}/alerts/${alertId}/acknowledge`, 'POST', input);
}

export function dismissDropshipLossAlert(
  alertId: string,
  input: DropshipLossAlertDismissInput,
): Promise<DropshipLossAlertRow> {
  return api<DropshipLossAlertRow>(`${base}/alerts/${alertId}/dismiss`, 'POST', input);
}

export function buildDropshipReconciliation(
  input: DropshipReconciliationInput,
): Promise<DropshipReconciliationRow> {
  return api<DropshipReconciliationRow>(`${base}/reconciliations`, 'POST', input);
}

export function fetchDropshipReconciliations(
  params: DropshipReconciliationParams,
): Promise<DropshipReconciliationPage> {
  return api<DropshipReconciliationPage>(`${base}/reconciliations${query(params)}`);
}

export function fetchDropshipReconciliation(
  statementId: string,
): Promise<DropshipReconciliationDetail> {
  return api<DropshipReconciliationDetail>(`${base}/reconciliations/${statementId}`);
}

export function changeDropshipReconciliationStatus(
  statementId: string,
  input: DropshipReconciliationStatusInput,
): Promise<DropshipReconciliationRow> {
  return api<DropshipReconciliationRow>(
    `${base}/reconciliations/${statementId}/status`,
    'POST',
    input,
  );
}

export function fetchDropshipPayableTasks(
  params: DropshipPayableTaskParams,
): Promise<DropshipPayableTaskPage> {
  return api<DropshipPayableTaskPage>(`${base}/tasks${query(params)}`);
}

export function claimDropshipPayableTask(taskId: string): Promise<DropshipPayableTaskRow> {
  return api<DropshipPayableTaskRow>(`${base}/tasks/${taskId}/claim`, 'POST');
}

export function completeDropshipPayableTask(
  taskId: string,
  input: DropshipPayableTaskResultInput,
): Promise<DropshipPayableTaskRow> {
  return api<DropshipPayableTaskRow>(`${base}/tasks/${taskId}/complete`, 'POST', input);
}

export function fetchDropshipMarginEvents(params: {
  statementId?: string;
} & DropshipMarginPageParams): Promise<DropshipMarginEventPage> {
  return api<DropshipMarginEventPage>(`${base}/events${query(params)}`);
}

// ------------------------------------------------------------ 词表与标签

export const LOSS_VERDICTS = [
  'LOSS_CONFIRMED',
  'NOT_LOSS',
  'UNDETERMINED_NO_FEE',
] as const;

export const REPORT_GROUP_BYS = ['SHOP', 'SUPPLIER', 'SKU', 'ORDER'] as const;

export const STATEMENT_STATUSES = ['DRAFT', 'FINALIZED', 'DISPUTED', 'CLOSED'] as const;

export const PAYABLE_TASK_STATUSES = [
  'OPEN',
  'CLAIMED',
  'SENT',
  'ACKNOWLEDGED',
  'FAILED',
] as const;

export const ALERT_STATUSES = ['OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'DISMISSED'] as const;

export const UNDETERMINED_HINT = '平台费未登记，无法判定（5.2.3.1 未交付）';
export const FX_UNSET_HINT = '汇率未登记，无法折算';
export const PAYABLE_PENDING_HINT = '待应付（12.1.2.1 未交付）';
export const SENT_NOT_ACKNOWLEDGED_HINT = '已发出（下游尚未受理）';

export function lossVerdictLabel(value: string): string {
  switch (value) {
    case 'LOSS_CONFIRMED':
      return '已确认亏损';
    case 'NOT_LOSS':
      return '已含平台费，不亏';
    case 'UNDETERMINED_NO_FEE':
      return '平台费未登记，无法判定';
    default:
      return value;
  }
}

export function alertStatusLabel(value: string): string {
  switch (value) {
    case 'OPEN':
      return '待处理';
    case 'ACKNOWLEDGED':
      return '已确认待处置';
    case 'RESOLVED':
      return '已结案';
    case 'DISMISSED':
      return '已判误报';
    default:
      return value;
  }
}

export function statementStatusLabel(value: string): string {
  switch (value) {
    case 'DRAFT':
      return '草稿';
    case 'FINALIZED':
      return '已定稿';
    case 'DISPUTED':
      return '有争议';
    case 'CLOSED':
      return '已关闭';
    default:
      return value;
  }
}

export function fxStatusLabel(value: string): string {
  switch (value) {
    case 'SAME_CURRENCY':
      return '原币即结算币';
    case 'APPLIED':
      return '已按登记汇率折算';
    case 'UNSET':
      return FX_UNSET_HINT;
    default:
      return value;
  }
}

export function groupByLabel(value: string): string {
  switch (value) {
    case 'SHOP':
      return '按店铺';
    case 'SUPPLIER':
      return '按供应商';
    case 'SKU':
      return '按 SKU';
    case 'ORDER':
      return '按代发单';
    default:
      return value;
  }
}

// ------------------------------------------------------------ 展示口径

/**
 * 亏损结论的徽章。
 *
 * <p>三态给三种色调，其中 {@code UNDETERMINED_NO_FEE} 刻意用 {@code unknown}
 * 而不是 {@code caution}：它**不是一个已确认的问题**，画成警告会把人引去查供应商，
 * 而真正该做的动作是「去登记平台费」。
 */
export interface VerdictBadge {
  tone: 'danger' | 'ok' | 'unknown';
  label: string;
  hint: string;
}

export function lossVerdictBadge(verdict: string): VerdictBadge {
  switch (verdict) {
    case 'LOSS_CONFIRMED':
      return {
        tone: 'danger',
        label: '已确认亏损',
        hint: '售价低于代发成本（已登记平台费时含平台费）',
      };
    case 'NOT_LOSS':
      return { tone: 'ok', label: '已含平台费，不亏', hint: '售价 ≥ 代发成本 + 平台费' };
    case 'UNDETERMINED_NO_FEE':
      return {
        tone: 'unknown',
        label: '无法判定',
        hint: UNDETERMINED_HINT + '；不等于不亏，也不按 0 算',
      };
    default:
      return { tone: 'unknown', label: verdict, hint: '' };
  }
}

/** 口径徽章：代发的成本是采购差价，与自营口径隔离，逐行都带。 */
export function basisBadge(row: { businessModel?: string; costBasis?: string }): string {
  if (row.businessModel === 'DROPSHIP' && row.costBasis === 'PURCHASE_SPREAD') {
    return '代发 · 采购差价口径';
  }
  return `${row.businessModel ?? '—'} · ${row.costBasis ?? '—'}`;
}

export interface SpreadCells {
  /** 含平台费的差价；null 表示**算不出来**（平台费未登记），不是 0。 */
  spreadAfterFee: number | null;
  /** 不含平台费的差价：任何时候都有值。 */
  spreadBeforeFee: number;
  feeUnregistered: boolean;
  note: string;
}

/**
 * 差价单元格 —— **null 不许被格式化成 0**。
 *
 * <p>平台费未登记时含平台费的差价是 {@code null}；把它显示成 0.00 会让界面给出
 * 一个「差价刚好等于不含费差价」的假结论，而这恰恰是本模块要防的那种误读。
 */
export function spreadCells(row: DropshipMarginFactRow): SpreadCells {
  const feeUnregistered = row.feeRegistrationStatus === 'UNREGISTERED';
  return {
    spreadAfterFee: row.spreadAfterFee ?? null,
    spreadBeforeFee: Number(row.spreadBeforeFee),
    feeUnregistered,
    note: feeUnregistered
      ? `${UNDETERMINED_HINT}：未含平台费的差价 ${row.spreadBeforeFee} ${row.currencyCode}`
      : `已按登记费率 ${row.platformFeeAmount ?? 0} ${row.currencyCode} 计入平台费`,
  };
}

/** 金额展示：null 一律显示「—」而不是 0，币种必须跟着数字。 */
export function moneyText(amount: number | string | null | undefined, currency?: string): string {
  if (amount === null || amount === undefined || amount === '') return '—';
  const value = typeof amount === 'string' ? Number(amount) : amount;
  if (!Number.isFinite(value)) return '—';
  const text = value.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 4,
  });
  return currency ? `${text} ${currency}` : text;
}

export interface GroupTotal {
  currencyCode: string;
  orderCount: number;
  sellAmount: number;
  spreadBeforeFee: number;
  spreadAfterFee: number | null;
  lossConfirmedOrderCount: number;
  undeterminedOrderCount: number;
}

/**
 * 报表按**币种**分别汇总 —— 跨币种不相加。
 *
 * <p>同一家供应商同时用 USD 与 CNY 结算时会得到两条；把它们加成一个数字会让
 * 「这个供应商赚了多少」看起来有答案，而那个答案是错的。
 */
export function groupTotals(report: DropshipMarginReport): GroupTotal[] {
  const byCurrency = new Map<string, GroupTotal>();
  for (const group of report.groups ?? []) {
    const key = group.currencyCode;
    const current =
      byCurrency.get(key) ??
      ({
        currencyCode: key,
        orderCount: 0,
        sellAmount: 0,
        spreadBeforeFee: 0,
        spreadAfterFee: 0,
        lossConfirmedOrderCount: 0,
        undeterminedOrderCount: 0,
      } satisfies GroupTotal);
    current.orderCount += group.orderCount ?? 0;
    current.sellAmount += Number(group.sellAmount ?? 0);
    current.spreadBeforeFee += Number(group.spreadBeforeFee ?? 0);
    // 含平台费的差价只要有一行算不出来，整组就不能给一个数
    current.spreadAfterFee =
      current.spreadAfterFee === null || group.spreadAfterFee === null
        ? null
        : current.spreadAfterFee + Number(group.spreadAfterFee);
    current.lossConfirmedOrderCount += group.lossConfirmedOrderCount ?? 0;
    current.undeterminedOrderCount += group.undeterminedOrderCount ?? 0;
    byCurrency.set(key, current);
  }
  return [...byCurrency.values()].sort((a, b) => a.currencyCode.localeCompare(b.currencyCode));
}

export interface ReconciliationBuckets {
  /** 计入合计的部分（汇率已折算或原币即结算币）。 */
  included: {
    orderCount: number;
    payable: number;
    payableText: string;
    feeText: string;
  };
  /** 汇率未登记：单列，不并进合计。 */
  fxUnset: { orderCount: number; payable: number; note: string };
  /** 平台费未登记：单列，让 total_platform_fee 不被当成完整结论。 */
  feeUnregistered: { orderCount: number; note: string };
}

/**
 * 对账单的三个桶 —— 合计 / 汇率未登记 / 平台费未登记，**三者互不吞并**。
 *
 * <p>把后两个并进第一个，界面上就会出现一个「应付 12.00 USD、平台费 0.00」
 * 的对账单，而它其实只是「汇率与费率都没登记过」。
 */
export function reconciliationBuckets(row: DropshipReconciliationRow): ReconciliationBuckets {
  const currency = row.currencyCode;
  return {
    included: {
      orderCount: row.orderCount,
      payable: Number(row.totalPayable),
      payableText: moneyText(row.totalPayable, currency),
      feeText: moneyText(row.totalPlatformFee, currency),
    },
    fxUnset: {
      orderCount: row.undeterminedOrderCount,
      payable: Number(row.undeterminedPayable),
      note:
        row.undeterminedOrderCount > 0
          ? `${row.undeterminedOrderCount} 行${FX_UNSET_HINT}，未登记应付 `
            + `${moneyText(row.undeterminedPayable, '各自原币')}，不并入上方合计`
          : '本单全部行都已折算（或原币即结算币），没有未登记桶',
    },
    feeUnregistered: {
      orderCount: row.feeUnregisteredOrderCount,
      note:
        row.feeUnregisteredOrderCount > 0
          ? `${row.feeUnregisteredOrderCount} 行${UNDETERMINED_HINT}，合计里的平台费 `
            + `${moneyText(row.totalPlatformFee, currency)} 不含这部分的平台费（未登记不等于 0）`
          : '本单全部行都已登记平台费率',
    },
  };
}

export interface PayableTaskBadge {
  tone: 'pending' | 'progress' | 'done' | 'failed';
  label: string;
  hint: string;
}

/**
 * 应付待办的徽章 —— **只有下游受理才算完成**。
 *
 * <p>{@code SENT} 是「本服务发出去了」，{@code ACKNOWLEDGED} 才是「12 财务接手了」。
 * 两者合成一个状态，界面就会把一个没人接的待办画成「应付单已生成」。
 */
export function payableTaskBadge(task: DropshipPayableTaskRow): PayableTaskBadge {
  switch (task.taskStatus) {
    case 'ACKNOWLEDGED':
      return { tone: 'done', label: '下游已受理', hint: '12 财务已接手这张应付待办' };
    case 'SENT':
      return {
        tone: 'progress',
        label: '已交给下游',
        hint: `${SENT_NOT_ACKNOWLEDGED_HINT}，还没人接手`,
      };
    case 'CLAIMED':
      return { tone: 'progress', label: '已领取', hint: '下游已领取，尚未回执' };
    case 'FAILED':
      return { tone: 'failed', label: '失败', hint: task.lastError || '下游回执失败' };
    default:
      return { tone: 'pending', label: PAYABLE_PENDING_HINT, hint: '真待办，尚未交给下游' };
  }
}

/** 预警工作台的两个计数必须分开显示 —— 后者才是被漏掉的亏损。 */
export function alertCounters(page: DropshipLossAlertPage): {
  lossConfirmed: number;
  undetermined: number;
  note: string;
} {
  return {
    lossConfirmed: page.lossConfirmedTotal ?? 0,
    undetermined: page.undeterminedTotal ?? 0,
    note:
      `已确认亏损 ${page.lossConfirmedTotal ?? 0} 条；` +
      `另有 ${page.undeterminedTotal ?? 0} 条因为平台费未登记而**无法判定** —— ` +
      '这些不是「不亏」，真实亏损可能就藏在里面',
  };
}

/** SKU 维度不摊运费与平台费时，界面上必须写明原因，否则会被当成漏算。 */
export function skuGroupNote(group: { note?: string }): string {
  return (
    group.note ||
    'SKU 维度只出该 SKU 自己的售价与采购差价：供应商运费与平台费是单级金额，摊到行上必须假设分摊规则'
  );
}
