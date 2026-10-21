import type {
  CurrencyBucket,
  PurchaseReconciliationConfirmationCreate,
  PurchaseReconciliationCreate,
  PurchaseReconciliationDeductionCreate,
  PurchaseReconciliationDiscrepancyCreate,
  PurchaseReconciliationDiscrepancyResolve,
  PurchaseReconciliationReopen,
  ReconciliationAction,
  ReconciliationBasis,
  ReconciliationCapabilities,
  ReconciliationConfirmationRow,
  ReconciliationDetail,
  ReconciliationDiscrepancyPage,
  ReconciliationDiscrepancyRow,
  ReconciliationEventRow,
  ReconciliationLinePage,
  ReconciliationLineRow,
  ReconciliationOptions,
  ReconciliationPage,
  ReconciliationRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 6.5.1.1 采购对账前端模型。
 *
 * <p>蓝图：「按期间汇总应收数量、单价、金额、代扣代缴费用、索赔，生成对账单发给供应商确认，
 * 差异挂起。」加粗句：「<b>对账由采购发起、12 财务执行付款</b>，两侧数据以对账单为交接点，
 * 避免采购擅自确认应付。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>建议应付数就写「建议」</b>：{@link ReconciliationRow.suggestedPayableAmount} 是
 *       交给 12 财务的建议数，页面**不**把它标成「应付」——采购侧确认应付正是蓝图要防的事。</li>
 *   <li><b>质检扣量未接线要看得见</b>：明细行的质检扣量状态显示「上游未接线」，
 *       扣量数量显示「无数据」而不是 0 —— 0 会被读成「这批没有扣量」。</li>
 *   <li><b>差异逐条显示</b>：差异清单按行号列出「差在哪个字段 / 我方值 / 供应商值 / 差额」，
 *       不给一个聚合成��的差额总数。</li>
 *   <li><b>跨币种不相加</b>：金额分币种显示，折算金额带汇率与汇率来源。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */

const base = 'trade/purchase-reconciliations';

export interface ReconciliationParams {
  page?: number;
  size?: number;
  status?: string;
  supplierRefId?: string;
  periodType?: string;
  currency?: string;
  keyword?: string;
  sort?: string;
}

/** 把筛选参数拼成 query string（空值一律不拼，避免发出无意义参数）。 */
export function reconciliationQuery(params: ReconciliationParams): string {
  const parts: string[] = [];
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.supplierRefId)
    parts.push(`supplierRefId=${encodeURIComponent(params.supplierRefId)}`);
  if (params.periodType) parts.push(`periodType=${encodeURIComponent(params.periodType)}`);
  if (params.currency) parts.push(`currency=${encodeURIComponent(params.currency)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchReconciliationCapabilities() {
  return api<ReconciliationCapabilities>(`${base}/capabilities`);
}

export function fetchReconciliationOptions() {
  return api<ReconciliationOptions>(`${base}/options`);
}

export function fetchReconciliations(params: ReconciliationParams) {
  return api<ReconciliationPage>(`${base}${reconciliationQuery(params)}`);
}

export function fetchReconciliation(id: string) {
  return api<ReconciliationDetail>(`${base}/${encodeURIComponent(id)}`);
}

export function fetchReconciliationLines(
  id: string,
  params: { lineType?: string; page?: number; size?: number },
) {
  const parts: string[] = [];
  if (params.lineType) parts.push(`lineType=${encodeURIComponent(params.lineType)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  const query = parts.length ? `?${parts.join('&')}` : '';
  return api<ReconciliationLinePage>(
    `${base}/${encodeURIComponent(id)}/lines${query}`,
  );
}

export function fetchReconciliationDiscrepancies(
  id: string,
  params: { status?: string; page?: number; size?: number },
) {
  const parts: string[] = [];
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  const query = parts.length ? `?${parts.join('&')}` : '';
  return api<ReconciliationDiscrepancyPage>(
    `${base}/${encodeURIComponent(id)}/discrepancies${query}`,
  );
}

export function createReconciliation(input: PurchaseReconciliationCreate) {
  return api<ReconciliationDetail>(`${base}`, 'POST', input);
}

export function addReconciliationDeduction(
  id: string,
  input: PurchaseReconciliationDeductionCreate,
) {
  return api<ReconciliationDetail>(
    `${base}/${encodeURIComponent(id)}/deductions`,
    'POST',
    input,
  );
}

export function sendReconciliation(id: string) {
  return api<ReconciliationDetail>(`${base}/${encodeURIComponent(id)}/send`, 'POST');
}

export function recordReconciliationConfirmation(
  id: string,
  input: PurchaseReconciliationConfirmationCreate,
) {
  return api<ReconciliationDetail>(
    `${base}/${encodeURIComponent(id)}/confirmations`,
    'POST',
    input,
  );
}

export function addReconciliationDiscrepancy(
  id: string,
  input: PurchaseReconciliationDiscrepancyCreate,
) {
  return api<ReconciliationDetail>(
    `${base}/${encodeURIComponent(id)}/discrepancies`,
    'POST',
    input,
  );
}

export function resolveReconciliationDiscrepancy(
  id: string,
  discrepancyId: string,
  input: PurchaseReconciliationDiscrepancyResolve,
) {
  return api<ReconciliationDetail>(
    `${base}/${encodeURIComponent(id)}/discrepancies/${encodeURIComponent(discrepancyId)}/resolve`,
    'POST',
    input,
  );
}

export function reopenReconciliation(id: string, input: PurchaseReconciliationReopen) {
  return api<ReconciliationDetail>(`${base}/${encodeURIComponent(id)}/reopen`, 'POST', input);
}

export function closeReconciliation(id: string) {
  return api<ReconciliationDetail>(`${base}/${encodeURIComponent(id)}/close`, 'POST');
}

/**
 * 导出 CSV 的下载地址（给「导出给供应商」按钮用，浏览器直接下载）。
 *
 * <p>刻意用 `<a download>` 而不是 fetch + Blob：导出的是**发给供应商的凭据**，
 * 走浏览器下载能保留「文件来自哪个对账单」的直觉，也不必在前端拼 CSV。
 */
export function reconciliationExportUrl(id: string) {
  return `/api/trade/${base}/${encodeURIComponent(id)}/export`;
}

// ================================================================ 展示口径

/** 金额：null 一律给「无数据」—— 空格会被读成「金额是 0」。 */
export function fmtMoney(value: number | null | undefined): string {
  if (value === null || value === undefined) return '无数据';
  return value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 });
}

/** 数量：null 一律给「无数据」。 */
export function fmtQty(value: number | null | undefined): string {
  if (value === null || value === undefined) return '无数据';
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/** 日期：只取日期部分（对账期间是业务日历口径，不带时刻）。 */
export function fmtDate(value: string | null | undefined): string {
  if (!value) return '无数据';
  return value.slice(0, 10);
}

/** 时刻：到 ISO 串，页面直接显示（不按浏览器本地时区二次折算）。 */
export function fmtTime(value: string | null | undefined): string {
  if (!value) return '无数据';
  return value;
}

export function statusTone(
  status: string | null | undefined,
): 'primary' | 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'DRAFT':
      return 'neutral';
    case 'SENT':
      return 'warning';
    case 'DISPUTED':
      return 'danger';
    case 'CONFIRMED':
      return 'success';
    case 'CLOSED':
      return 'primary';
    default:
      return 'neutral';
  }
}

export function discrepancyStatusTone(
  status: string | null | undefined,
): 'primary' | 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'OPEN':
      return 'danger';
    case 'HELD':
      return 'warning';
    case 'RESOLVED':
      return 'success';
    default:
      return 'neutral';
  }
}

/**
 * 质检扣量状态的中文与配色。
 *
 * <p>⚠️ 「上游未接线」不是 0，也不是「不适用」—— 它意味着
 * 「这批的应收数量**尚未扣除**质检不合格量」，页面必须把它说清楚。
 */
export function qualityDeductionText(
  status: string | null | undefined,
): { label: string; tone: 'primary' | 'success' | 'warning' | 'danger' | 'neutral' } {
  switch (status) {
    case 'UNAVAILABLE':
      return { label: '上游未接线（未扣量）', tone: 'warning' };
    case 'APPLIED':
      return { label: '已取到扣量', tone: 'success' };
    case 'NOT_APPLICABLE':
      return { label: '不适用', tone: 'neutral' };
    default:
      return { label: '未知', tone: 'neutral' };
  }
}

/** 供应商显示：编码 + 名称。 */
export function supplierText(row: {
  supplierCode?: string | null;
  supplierName?: string | null;
}): string {
  const code = row.supplierCode?.trim();
  const name = row.supplierName?.trim();
  if (code && name) return `${code} ${name}`;
  return code || name || '未命名供应商';
}

/** 差异一行的人话描述：差在哪个字段、我方多少、供应商多少、差多少。 */
export function discrepancyText(row: ReconciliationDiscrepancyRow): string {
  const field = row.fieldName?.trim() || row.discrepancyTypeLabel || '差异';
  const ours = fmtQty(row.ourValue);
  const theirs = fmtQty(row.supplierValue);
  const diff = fmtQty(row.difference);
  return `${field}：我方 ${ours} / 供应商 ${theirs} / 差 ${diff} ${row.currency}`;
}

/** 明细行的金额展示：原币 + 折算到对账单币种的金额（跨币种时两个都要给）。 */
export function lineAmountText(line: ReconciliationLineRow): string {
  const origin = `${fmtMoney(line.amount)} ${line.currency}`;
  if (line.currency && line.statementAmount !== null && line.statementAmount !== undefined) {
    const same = Math.abs(line.statementAmount - (line.amount ?? 0)) < 0.000001;
    if (same) return origin;
    return `${origin} → ${fmtMoney(line.statementAmount)}（汇率 ${fmtQty(line.fxRate)}）`;
  }
  return origin;
}

/** 币种分桶的中文说明（跨币种绝不相加）。 */
export function currencyBucketText(bucket: CurrencyBucket): string {
  return `${bucket.currency}：${bucket.lineCount} 行 / ${fmtMoney(bucket.amount)}`;
}

/** 能力声明里「未接线」的模块清单（页面顶部要显式提示，不让空白冒充「没做」）。 */
export function unavailableModules(basis: ReconciliationBasis | null | undefined) {
  if (!basis) return [] as ReconciliationBasis['unavailableSources'];
  return basis.unavailableSources.filter((s) => s.available === false);
}

/** 可执行动作：能不能点由后端给，前端只读。 */
export function actionAllowed(
  actions: ReconciliationAction[] | undefined,
  code: string,
): boolean {
  return (actions ?? []).some((a) => a.code === code && a.allowed);
}

/** 动作被拒的原因（灰掉时也要说清为什么）。 */
export function actionBlockedReason(
  actions: ReconciliationAction[] | undefined,
  code: string,
): string {
  const action = (actions ?? []).find((a) => a.code === code);
  if (!action) return '当前不可用';
  return action.blockedReason || '当前不可用';
}

/** 留痕行的类型中文。 */
export function eventTypeLabel(type: string | null | undefined): string {
  switch (type) {
    case 'CREATED':
      return '发起对账';
    case 'STATUS':
      return '状态变更';
    case 'SEND':
      return '发出对账单';
    case 'SUPPLIER_CONFIRMATION':
      return '录入供应商回执';
    case 'DEDUCTION_ADDED':
      return '登记代扣代缴费用';
    case 'DISCREPANCY_ADDED':
      return '追加差异';
    case 'DISCREPANCY_HELD':
      return '差异挂起';
    case 'DISCREPANCY_RESOLVED':
      return '差异解决';
    case 'REOPENED':
      return '重开对账';
    case 'CLOSED':
      return '关闭对账单';
    default:
      return type || '未知事件';
  }
}

/** 供应商回执行的类型中文。 */
export function confirmationText(row: ReconciliationConfirmationRow): string {
  return `${row.supplierResultLabel || row.supplierResult} · 差异 ${row.discrepancyCount} 条 · ${
    row.supplierReference || '无回执号'
  }`;
}

/** 事件行的展示文本。 */
export function eventText(row: ReconciliationEventRow): string {
  const from = row.fromStatus ? `${row.fromStatus} → ` : '';
  return `${eventTypeLabel(row.eventType)}${from}${row.toStatus}${
    row.reasonNote ? ` · ${row.reasonNote}` : ''
  }`;
}
