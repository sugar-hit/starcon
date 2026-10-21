import type {
  PurchaseArrivalAlertBoard,
  PurchaseArrivalCapabilities,
  PurchaseArrivalDetail,
  PurchaseArrivalDispositionResolve,
  PurchaseArrivalPage,
  PurchaseArrivalRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 6.3.2.2 到货与逾期前端模型。
 *
 * <p>蓝图：「跟踪采购单的到货状态：已发货（在途）、部分到货、全部到货、逾期未到、取消。
 * 逾期自动预警并给出处置建议（催货/改供应商/调库存/延期售卖）。」
 * 加粗句：「<b>部分到货必须立即更新已收数量并触发应付部分确认</b>，
 * 否则采购与财务三方数据对不上。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>可收 / 已收 / 未收三个数并排显示</b>，还带单位与百分比 ——
 *       只给一个「已收数量」的话，界面上的「还差多少」要靠减法自己算，
 *       而减法在列表和详情各写一次就会算出两个数。表格里**不出现裸数字</b>。</li>
 *   <li><b>逾期天数带判定时刻</b>：{@link PurchaseArrivalPage.evaluatedAt} 是后端按
 *       **数据库时钟**算出来的判定时刻，页面必须把它显示出来 ——
 *       「按哪个时刻算的」不说清楚，清单上的天数就没有意义。</li>
 *   <li><b>在途不是逾期</b>：在途单的逾期天数恒为 0，页面用不同的徽标与文案区分，
 *       不让「已发货（在途）」的单子出现在逾期筛选的结果里又被标成逾期。</li>
 *   <li><b>处置建议必须带依据</b>：{@link PurchaseArrivalDispositionRow.basisNote} 是人话，
 *       {@link PurchaseArrivalDispositionRow.basisFacts} 是同一句话里的数字 ——
 *       页面把两者一起显示，不只显示一个动作名。</li>
 *   <li><b>应付部分确认不假装已进财务</b>：状态是「待财务确认」时，
 *       生效金额显示 0 并写明下游 12 财务尚未交付。</li>
 *   <li>加载 / 空 / 错误三态齐全。
 * </ul>
 */

const base = 'trade/purchase-arrivals';

export interface PurchaseArrivalParams {
  page?: number;
  size?: number;
  status?: string;
  supplierRefId?: string;
  overdueOnly?: boolean;
  inTransitOnly?: boolean;
  keyword?: string;
  promisedBefore?: string;
  sort?: string;
}

export interface PurchaseArrivalAlertParams {
  alertType?: string;
  supplierRefId?: string;
  minOverdueDays?: number;
  page?: number;
  size?: number;
}

export function purchaseArrivalQuery(params: PurchaseArrivalParams): string {
  const parts: string[] = [];
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.supplierRefId) parts.push(`supplierRefId=${encodeURIComponent(params.supplierRefId)}`);
  if (params.overdueOnly) parts.push('overdueOnly=true');
  if (params.inTransitOnly) parts.push('inTransitOnly=true');
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.promisedBefore)
    parts.push(`promisedBefore=${encodeURIComponent(params.promisedBefore)}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function purchaseArrivalAlertQuery(params: PurchaseArrivalAlertParams): string {
  const parts: string[] = [];
  if (params.alertType) parts.push(`alertType=${encodeURIComponent(params.alertType)}`);
  if (params.supplierRefId) parts.push(`supplierRefId=${encodeURIComponent(params.supplierRefId)}`);
  if (params.minOverdueDays !== undefined) parts.push(`minOverdueDays=${params.minOverdueDays}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchCapabilities() {
  return api<PurchaseArrivalCapabilities>(`${base}/capabilities`);
}

export function fetchArrivals(params: PurchaseArrivalParams) {
  return api<PurchaseArrivalPage>(`${base}${purchaseArrivalQuery(params)}`);
}

export function fetchArrival(id: string) {
  return api<PurchaseArrivalDetail>(`${base}/${encodeURIComponent(id)}`);
}

export function fetchAlertBoard(params: PurchaseArrivalAlertParams) {
  return api<PurchaseArrivalAlertBoard>(
    `${base}/overdue-board${purchaseArrivalAlertQuery(params)}`,
  );
}

export function createArrival(input: {
  purchaseOrderNo: string;
  sourceDocNo: string;
  sourceDocType: string;
  supplierRefId: string;
  supplierCode: string;
  supplierName: string;
  receivableQty: number;
  unitPrice: number;
  currency: string;
  promisedArrivalDate: string;
  promiseSource: string;
}) {
  return api<PurchaseArrivalDetail>(`${base}`, 'POST', input);
}

export function markShipped(
  id: string,
  input: {
    expectedVersion: number;
    shipmentCode: string;
    waybillNo: string;
    shippedAt: string;
    promisedArrivalDate?: string | null;
    promiseSource?: string;
  },
) {
  return api<PurchaseArrivalDetail>(`${base}/${encodeURIComponent(id)}/ship`, 'POST', input);
}

export function registerArrival(
  id: string,
  input: {
    expectedVersion: number;
    arrivedQty: number;
    arrivedAt: string;
    warehouseReceiptCode?: string | null;
    waybillNo: string;
    note: string;
  },
) {
  return api<PurchaseArrivalDetail>(`${base}/${encodeURIComponent(id)}/arrivals`, 'POST', input);
}

export function cancelArrival(id: string, input: { expectedVersion: number; reason: string }) {
  return api<PurchaseArrivalDetail>(`${base}/${encodeURIComponent(id)}/cancel`, 'POST', input);
}

export function syncInTransit(id: string) {
  return api<PurchaseArrivalDetail>(`${base}/${encodeURIComponent(id)}/sync-in-transit`, 'POST');
}

export function confirmPayable(
  id: string,
  confirmationId: string,
  input: { financeReference: string },
) {
  return api<PurchaseArrivalDetail>(
    `${base}/${encodeURIComponent(id)}/payable-confirmations/${encodeURIComponent(confirmationId)}/confirm`,
    'POST',
    input,
  );
}

export function rejectPayable(id: string, confirmationId: string, input: { reason: string }) {
  return api<PurchaseArrivalDetail>(
    `${base}/${encodeURIComponent(id)}/payable-confirmations/${encodeURIComponent(confirmationId)}/reject`,
    'POST',
    input,
  );
}

export function resolveDisposition(
  id: string,
  dispositionId: string,
  input: PurchaseArrivalDispositionResolve,
) {
  return api<PurchaseArrivalDetail>(
    `${base}/${encodeURIComponent(id)}/dispositions/${encodeURIComponent(dispositionId)}/resolve`,
    'POST',
    input,
  );
}

// ================================================================ 展示口径

/** 到货状态的中文名（后端也给了 statusLabel，这份是兜底与离线渲染用）。 */
export const arrivalStatusLabels: Record<string, string> = {
  PENDING_SHIPMENT: '待发货',
  IN_TRANSIT: '已发货（在途）',
  PARTIALLY_RECEIVED: '部分到货',
  FULLY_RECEIVED: '全部到货',
  OVERDUE: '逾期未到',
  CANCELLED: '取消',
};

export function arrivalStatusLabel(status: string | null | undefined): string {
  if (!status) return '—';
  return arrivalStatusLabels[status] ?? status;
}

/** 状态徽标的色调：跟语义挂钩，不跟颜色挂钩。 */
export function arrivalStatusTone(
  status: string | null | undefined,
): 'success' | 'warning' | 'danger' | 'info' | 'neutral' {
  switch (status) {
    case 'FULLY_RECEIVED':
      return 'success';
    case 'PARTIALLY_RECEIVED':
      return 'info';
    case 'OVERDUE':
      return 'danger';
    case 'IN_TRANSIT':
      return 'warning';
    default:
      return 'neutral';
  }
}

export const alertTypeLabels: Record<string, string> = {
  OVERDUE: '逾期未到',
  IN_TRANSIT_STALLED: '在途滞留',
};

export function alertTypeLabel(type: string | null | undefined): string {
  if (!type) return '—';
  return alertTypeLabels[type] ?? type;
}

export const dispositionActionLabels: Record<string, string> = {
  URGE_SUPPLIER: '催货',
  SWITCH_SUPPLIER: '改供应商',
  ADJUST_STOCK: '调库存',
  DELAY_SALE: '延期售卖',
};

export function dispositionActionLabel(type: string | null | undefined): string {
  if (!type) return '—';
  return dispositionActionLabels[type] ?? type;
}

export function severityLabel(severity: string | null | undefined): string {
  switch (severity) {
    case 'CRITICAL':
      return '严重';
    case 'WARN':
      return '警告';
    case 'INFO':
      return '提示';
    default:
      return '—';
  }
}

export function severityTone(severity: string | null | undefined): 'danger' | 'warning' | 'info' {
  switch (severity) {
    case 'CRITICAL':
      return 'danger';
    case 'WARN':
      return 'warning';
    default:
      return 'info';
  }
}

export const payableStatusLabels: Record<string, string> = {
  PENDING_FINANCE_CONFIRM: '待财务确认',
  CONFIRMED: '财务已确认',
  REJECTED: '财务已驳回',
};

export function payableStatusLabel(status: string | null | undefined): string {
  if (!status) return '—';
  return payableStatusLabels[status] ?? status;
}

/**
 * 数量：带单位，**不出现裸数字**。
 *
 * <p>后端给的是 NUMERIC(19,6) 的精确十进制；这里去掉无意义的尾零，
 * 但**保留至少一位小数**以外的整数形态（100 而不是 100.000000），
 * 并统一带「件」。
 */
export function fmtQty(value: number | string | null | undefined, unit = '件'): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(n)) return '—';
  const rounded = Math.round(n * 1e6) / 1e6;
  return `${rounded.toLocaleString('zh-CN', { maximumFractionDigits: 6 })} ${unit}`;
}

/** 金额：带币种。 */
export function fmtMoney(
  value: number | string | null | undefined,
  currency?: string | null,
): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(n)) return '—';
  const rounded = Math.round(n * 1e6) / 1e6;
  return `${rounded.toLocaleString('zh-CN', { maximumFractionDigits: 6 })}${
    currency ? ` ${currency}` : ''
  }`;
}

/** 比例：百分比，保留一位小数。 */
export function fmtPercent(ratio: number | string | null | undefined): string {
  if (ratio === null || ratio === undefined || ratio === '') return '—';
  const n = typeof ratio === 'number' ? ratio : Number(ratio);
  if (!Number.isFinite(n)) return '—';
  return `${(n * 100).toFixed(1)}%`;
}

/** 天数：带单位。 */
export function fmtDays(days: number | null | undefined): string {
  if (days === null || days === undefined) return '—';
  return `${days} 天`;
}

/**
 * 逾期天数的展示文案。
 *
 * <p>⚠️ **在途恒为「不适用」而不是 0 天**：0 天会被读成「刚好没逾期」，
 * 而在途的真实口径是「不参与逾期判定」—— 两件事必须区分开。
 */
export function overdueDaysText(row: { status: string; overdueDays: number }): string {
  if (row.status === 'IN_TRANSIT') return '不适用（在途不判逾期）';
  if (row.overdueDays > 0) return `已逾期 ${row.overdueDays} 天`;
  return '未逾期';
}

/** 距承诺到货日：负数表示已过承诺日。 */
export function promiseCountdownText(row: { status: string; daysUntilPromise: number }): string {
  if (row.status === 'FULLY_RECEIVED' || row.status === 'CANCELLED') return '—';
  if (row.daysUntilPromise < 0) return `已过承诺日 ${Math.abs(row.daysUntilPromise)} 天`;
  if (row.daysUntilPromise === 0) return '今天就是承诺到货日';
  return `还有 ${row.daysUntilPromise} 天到期`;
}

/** 判定时刻：把 ISO 瞬间显示成可读时间（含时区偏移）。 */
export function fmtEvaluatedAt(value: string | null | undefined): string {
  if (!value) return '—';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleString('zh-CN', { hour12: false });
}

/** 日期（YYYY-MM-DD）原样显示 —— 承诺到货日是日历日，不做时区换算。 */
export function fmtDate(value: string | null | undefined): string {
  if (!value) return '—';
  return value;
}

/** 在途滞留天数：有快照才显示数字。 */
export function dwellText(row: {
  inTransitDwellDays: number;
  inTransitSyncedAt: string | null;
}): string {
  if (!row.inTransitSyncedAt) return '未同步在途状态';
  return `滞留 ${row.inTransitDwellDays} 天`;
}

/** 行的主标识：采购单号（跟单时人认的是它，不是 uuid）。 */
export function rowTitle(row: PurchaseArrivalRow): string {
  return row.purchaseOrderNo || row.trackingNo;
}

/** 表格里展示的供应商名：没有名字就退回编码，再没有就明说「未填」。 */
export function supplierText(row: { supplierCode: string; supplierName: string }): string {
  if (row.supplierName) return row.supplierName;
  if (row.supplierCode) return row.supplierCode;
  return '未填供应商';
}

/**
 * 处置建议的依据事实：把 basis_facts（jsonb 原文）解析成键值对。
 *
 * <p>解析失败就返回空数组 —— 页面照样显示人话依据，不因为附加信息解析不了就崩。
 *
 * <p>⚠️ **每个值都带单位**（件 / 天 / 单 / %）：依据面板上也不出现裸数字 ——
 * 「未收数量 100」看不出是 100 件还是 100 箱。
 */
export function basisFactEntries(basisFacts: string | null | undefined): {
  key: string;
  label: string;
  value: string;
}[] {
  if (!basisFacts) return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(basisFacts);
  } catch {
    return [];
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return [];
  const labels: Record<string, string> = {
    overdueDays: '逾期天数',
    inTransitDwellDays: '在途滞留天数',
    receivableQty: '可收数量',
    receivedQty: '已收数量',
    outstandingQty: '未收数量',
    gapRatio: '缺口率',
    promisedArrivalDate: '承诺到货日',
    arrivalStatus: '当前状态',
    supplierOverdueCount: '该供应商逾期单数',
    supplierOverdueDays: '该供应商累计逾期天数',
    supplierMaxOverdueDays: '该供应商最长逾期天数',
  };
  return Object.entries(parsed as Record<string, unknown>).map(([key, value]) => ({
    key,
    label: labels[key] ?? key,
    value: formatBasisValue(key, value),
  }));
}

/** 依据事实的取值口径：数量带件、天数带天、单数带单、比率带 %。 */
function formatBasisValue(key: string, value: unknown): string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'string') return value;
  if (typeof value !== 'number') return String(value);
  if (key.endsWith('Qty')) return fmtQty(value);
  if (key.endsWith('Ratio')) return fmtPercent(value);
  if (key.endsWith('Days')) return fmtDays(value);
  if (key.endsWith('Count')) return `${value} 单`;
  return String(value);
}
