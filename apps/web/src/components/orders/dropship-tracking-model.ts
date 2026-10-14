import type {
  DropshipTrackingCapabilities,
  DropshipTrackingDetail,
  DropshipTrackingEventPage,
  DropshipTrackingEventRow,
  DropshipTrackingEvaluateResult,
  DropshipTrackingExceptionPage,
  DropshipTrackingExceptionRow,
  DropshipTrackingPage,
  DropshipTrackingPolicyInput,
  DropshipTrackingPolicyRow,
  DropshipTrackingRow,
  DropshipTrackingSaved,
  DropshipDisposeInput,
  DropshipDismissInput,
  DropshipDispositionRow,
  DropshipDispositionSaved,
  DropshipDispositionStatusInput,
  DropshipSupplierLegInput,
  DropshipOwnLegInput,
  DropshipTaskPage,
  DropshipTaskResultInput,
  DropshipTaskRow,
  DropshipTaskSaved,
  DropshipTrackingInput,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 9.6.1.3 代发履约跟踪与异常前端模型。
 *
 * <p>蓝图：「跟踪代发订单的供应商发货与妥投状态，同步供应商运单号回传平台，
 * 处理代发异常（延迟、丢件、供应商跑单），并把『我方』与『供应商』两段物流在订单上
 * 分开呈现。输出：代发履约状态；异常清单 + 处置（催供应商/改自营发货/退款）。」
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>两段物流必须画成两块，不能合并。</b>{@code legSummary} 返回**两行**而不是
 *       一行拼接 —— 合成一列在「改自营发货」那一刻就会把供应商发过货这件事覆盖掉，
 *       而跑单纠纷问的恰好是这个。</li>
 *   <li><b>异常要带判据，不只是标签。</b>每条异常把 {@code ruleCode} /
 *       {@code ruleThresholdHours} / {@code ruleEvidence} 原样显示，让人能复核
 *       「为什么这条算延迟」而不是只能相信一个红色标签。</li>
 *   <li><b>未回传就是未回传。</b>{@code callbackStatus} 停在 PENDING/QUEUED 时显示
 *       「待回传平台」，**不允许**出现「已回传」—— 2.5.1.1 还没交付，
 *       画成已回传就是在骗人。同理退款只显示「待办已生成（11 售后未交付）」。</li>
 *   <li><b>已废弃 ≠ 已删除。</b>改自营发货后供应商段显示「已废弃」并保留运单号，
 *       让人看得出「供应商确实发过这一票」，而不是像凭空消失一样。</li>
 *   <li><b>承诺时限是延迟判定的唯一基准。</b>没登记时限的单没有延迟检测，
 *       界面必须显式提示这一点，而不是显示「正常」。</li>
 * </ul>
 */

// ------------------------------------------------------------ 参数

export interface DropshipTrackingParams {
  orderNo?: string;
  salesOrderNo?: string;
  supplierCode?: string;
  activeLeg?: string;
  supplierLegStatus?: string;
  ownLegStatus?: string;
  exceptionOnly?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface DropshipTrackingExceptionParams {
  orderId?: string;
  exceptionKind?: string;
  status?: string;
  severity?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface DropshipTaskParams {
  taskKind?: string;
  taskStatus?: string;
  targetSystem?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

function query<T extends object>(params: T): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params as Record<string, unknown>)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

// ------------------------------------------------------------ 接口

const base = 'trade/fulfillment/dropship-tracking';

export function fetchDropshipTrackingCapabilities(): Promise<DropshipTrackingCapabilities> {
  return api<DropshipTrackingCapabilities>(`${base}/capabilities`);
}

export function fetchDropshipTrackingPolicy(): Promise<DropshipTrackingPolicyRow> {
  return api<DropshipTrackingPolicyRow>(`${base}/policy`);
}

export function saveDropshipTrackingPolicy(
  input: DropshipTrackingPolicyInput,
): Promise<DropshipTrackingPolicyRow> {
  return api<DropshipTrackingPolicyRow>(`${base}/policy`, 'POST', input);
}

export function fetchDropshipTrackings(
  params: DropshipTrackingParams,
): Promise<DropshipTrackingPage> {
  return api<DropshipTrackingPage>(`${base}/trackings${query(params)}`);
}

export function createDropshipTracking(
  input: DropshipTrackingInput,
): Promise<DropshipTrackingSaved> {
  return api<DropshipTrackingSaved>(`${base}/trackings`, 'POST', input);
}

export function fetchDropshipTracking(trackingId: string): Promise<DropshipTrackingDetail> {
  return api<DropshipTrackingDetail>(`${base}/trackings/${trackingId}`);
}

export function recordDropshipSupplierLeg(
  trackingId: string,
  input: DropshipSupplierLegInput,
): Promise<DropshipTrackingSaved> {
  return api<DropshipTrackingSaved>(`${base}/trackings/${trackingId}/supplier-leg`, 'POST', input);
}

export function recordDropshipOwnLeg(
  trackingId: string,
  input: DropshipOwnLegInput,
): Promise<DropshipTrackingSaved> {
  return api<DropshipTrackingSaved>(`${base}/trackings/${trackingId}/own-leg`, 'POST', input);
}

export function evaluateDropshipTrackingExceptions(): Promise<DropshipTrackingEvaluateResult> {
  return api<DropshipTrackingEvaluateResult>(`${base}/exceptions/evaluate`, 'POST', {});
}

export function fetchDropshipTrackingExceptions(
  params: DropshipTrackingExceptionParams,
): Promise<DropshipTrackingExceptionPage> {
  return api<DropshipTrackingExceptionPage>(`${base}/exceptions${query(params)}`);
}

export function disposeDropshipException(
  exceptionId: string,
  input: DropshipDisposeInput,
): Promise<DropshipDispositionSaved> {
  return api<DropshipDispositionSaved>(`${base}/exceptions/${exceptionId}/dispose`, 'POST', input);
}

export function dismissDropshipException(
  exceptionId: string,
  input: DropshipDismissInput,
): Promise<DropshipTrackingExceptionRow> {
  return api<DropshipTrackingExceptionRow>(
    `${base}/exceptions/${exceptionId}/dismiss`,
    'POST',
    input,
  );
}

export function changeDropshipDispositionStatus(
  dispositionId: string,
  input: DropshipDispositionStatusInput,
): Promise<DropshipDispositionRow> {
  return api<DropshipDispositionRow>(`${base}/dispositions/${dispositionId}/status`, 'POST', input);
}

export function fetchDropshipTasks(params: DropshipTaskParams): Promise<DropshipTaskPage> {
  return api<DropshipTaskPage>(`${base}/tasks${query(params)}`);
}

export function claimDropshipTask(taskId: string): Promise<DropshipTaskSaved> {
  return api<DropshipTaskSaved>(`${base}/tasks/${taskId}/claim`, 'POST', {});
}

export function completeDropshipTask(
  taskId: string,
  input: DropshipTaskResultInput,
): Promise<DropshipTaskSaved> {
  return api<DropshipTaskSaved>(`${base}/tasks/${taskId}/complete`, 'POST', input);
}

export function fetchDropshipTrackingEvents(params: {
  trackingId?: string;
  page?: number;
  size?: number;
}): Promise<DropshipTrackingEventPage> {
  return api<DropshipTrackingEventPage>(`${base}/events${query(params)}`);
}

// ------------------------------------------------------------ 枚举标签

export const SUPPLIER_LEG_STATUSES = [
  'NOT_SHIPMENT',
  'SHIPPED',
  'IN_TRANSIT',
  'DELIVERED',
  'LOST',
  'ABANDONED',
] as const;

export const OWN_LEG_STATUSES = [
  'NOT_HANDED_OVER',
  'HANDED_OVER',
  'IN_TRANSIT',
  'DELIVERED',
  'RETURNED',
] as const;

export const EXCEPTION_KINDS = [
  'DELAYED_SHIPMENT',
  'PARCEL_LOST',
  'SUPPLIER_RUNNING_AWAY',
] as const;

export const EXCEPTION_STATUSES = ['OPEN', 'ACKNOWLEDGED', 'DISPOSED', 'DISMISSED'] as const;

export const DISPOSITION_ACTIONS = ['URGE_SUPPLIER', 'SWITCH_TO_SELF', 'REQUEST_REFUND'] as const;

export const DISPOSITION_STATUSES = [
  'PENDING',
  'SENT',
  'ACKNOWLEDGED',
  'COMPLETED',
  'CANCELLED',
] as const;

export const TASK_KINDS = [
  'PLATFORM_CALLBACK',
  'SUPPLIER_URGE',
  'SELF_FULFILL_SWITCH',
  'REFUND_REQUEST',
] as const;

export const TASK_STATUSES = [
  'OPEN',
  'CLAIMED',
  'SENT',
  'ACKNOWLEDGED',
  'FAILED',
  'CANCELLED',
] as const;

const SUPPLIER_LEG_LABELS: Record<string, string> = {
  NOT_SHIPMENT: '未发货',
  SHIPPED: '供应商已发货',
  IN_TRANSIT: '在途',
  DELIVERED: '已妥投',
  LOST: '丢件',
  ABANDONED: '已废弃（改自营发货）',
};

const OWN_LEG_LABELS: Record<string, string> = {
  NOT_HANDED_OVER: '我方未交运',
  HANDED_OVER: '我方已交运',
  IN_TRANSIT: '在途',
  DELIVERED: '已妥投',
  RETURNED: '已退回',
};

const EXCEPTION_KIND_LABELS: Record<string, string> = {
  DELAYED_SHIPMENT: '延迟发货',
  PARCEL_LOST: '丢件（轨迹停滞）',
  SUPPLIER_RUNNING_AWAY: '供应商跑单（无运单号）',
};

const EXCEPTION_STATUS_LABELS: Record<string, string> = {
  OPEN: '未处置',
  ACKNOWLEDGED: '已确认',
  DISPOSED: '已处置',
  DISMISSED: '判为误报',
};

const DISPOSITION_ACTION_LABELS: Record<string, string> = {
  URGE_SUPPLIER: '催供应商',
  SWITCH_TO_SELF: '改自营发货',
  REQUEST_REFUND: '发起退款',
};

const DISPOSITION_STATUS_LABELS: Record<string, string> = {
  PENDING: '待发出',
  SENT: '已发出',
  ACKNOWLEDGED: '已受理',
  COMPLETED: '已办结',
  CANCELLED: '已撤销',
};

const TASK_KIND_LABELS: Record<string, string> = {
  PLATFORM_CALLBACK: '运单号回传平台',
  SUPPLIER_URGE: '催供应商',
  SELF_FULFILL_SWITCH: '改自营发货',
  REFUND_REQUEST: '退款待办',
};

const TASK_STATUS_LABELS: Record<string, string> = {
  OPEN: '待领取',
  CLAIMED: '已领取',
  SENT: '已提交下游',
  ACKNOWLEDGED: '下游已受理',
  FAILED: '失败待重试',
  CANCELLED: '已取消',
};

const SEVERITY_LABELS: Record<string, string> = {
  INFO: '提示',
  WARNING: '警告',
  CRITICAL: '严重',
};

export function supplierLegLabel(value: string): string {
  return SUPPLIER_LEG_LABELS[value] ?? value;
}

export function ownLegLabel(value: string): string {
  return OWN_LEG_LABELS[value] ?? value;
}

export function exceptionKindLabel(value: string): string {
  return EXCEPTION_KIND_LABELS[value] ?? value;
}

export function exceptionStatusLabel(value: string): string {
  return EXCEPTION_STATUS_LABELS[value] ?? value;
}

export function dispositionActionLabel(value: string): string {
  return DISPOSITION_ACTION_LABELS[value] ?? value;
}

export function dispositionStatusLabel(value: string): string {
  return DISPOSITION_STATUS_LABELS[value] ?? value;
}

export function taskKindLabel(value: string): string {
  return TASK_KIND_LABELS[value] ?? value;
}

export function taskStatusLabel(value: string): string {
  return TASK_STATUS_LABELS[value] ?? value;
}

export function severityLabel(value: string): string {
  return SEVERITY_LABELS[value] ?? value;
}

// ------------------------------------------------------------ 派生判断

/** 生效段中文。 */
export function activeLegLabel(value: string): string {
  return value === 'OWN' ? '我方段（我方 → 买家）' : '供应商段（供应商 → 买家）';
}

/**
 * 两段物流各一行 —— **不合并**。
 *
 * <p>刻意返回数组而不是一个拼接好的字符串：拼接会让「供应商运单号」和
 * 「我方运单号」在界面上共享同一行，而切段之后两者的含义完全相反
 * （一个是凭据，一个是当前承运）。分行才看得出这票货到底谁在送。
 */
export interface LegLine {
  leg: 'supplier' | 'own';
  legLabel: string;
  status: string;
  statusLabel: string;
  carrierCode: string;
  trackingNo: string;
  lastEventAt: string | null;
  terminalAt: string | null;
  active: boolean;
  note: string;
}

export function legLines(row: DropshipTrackingRow): LegLine[] {
  return [
    {
      leg: 'supplier',
      legLabel: '供应商段（供应商 → 买家）',
      status: row.supplierLegStatus,
      statusLabel: supplierLegLabel(row.supplierLegStatus),
      carrierCode: row.supplierCarrierCode,
      trackingNo: row.supplierTrackingNo,
      lastEventAt: row.supplierLastEventAt,
      terminalAt: row.supplierDeliveredAt ?? row.supplierShippedAt,
      active: row.activeLeg === 'SUPPLIER',
      note: row.supplierExceptionNote,
    },
    {
      leg: 'own',
      legLabel: '我方段（我方 → 买家）',
      status: row.ownLegStatus,
      statusLabel: ownLegLabel(row.ownLegStatus),
      carrierCode: row.ownCarrierCode,
      trackingNo: row.ownTrackingNo,
      lastEventAt: row.ownLastEventAt,
      terminalAt: row.ownDeliveredAt ?? row.ownHandedOverAt,
      active: row.activeLeg === 'OWN',
      note: row.ownDeliveryNote,
    },
  ];
}

/** 未结案的异常数（判为误报的不算 —— 误报已经被人复核过了）。 */
export function openExceptionCount(row: DropshipTrackingRow): number {
  return row.activeLeg === 'SUPPLIER'
    ? ['NOT_SHIPMENT', 'SHIPPED', 'IN_TRANSIT'].includes(row.supplierLegStatus)
      ? 1
      : 0
    : 0;
}

/** 是否已过期未发货（前端即时提示，真正的判定在后端规则里）。 */
export function overdueNotShipped(row: DropshipTrackingRow): boolean {
  return (
    row.activeLeg === 'SUPPLIER' &&
    row.supplierLegStatus === 'NOT_SHIPMENT' &&
    new Date(row.supplierPromisedShipAt).getTime() < Date.now()
  );
}

/** 回传平台状态：2.5.1.1 未交付，未被下游受理前一律显示「待回传」。 */
export function callbackText(row: DropshipTrackingRow): string {
  switch (row.callbackStatus) {
    case 'NOT_REQUIRED':
      return '无需回传';
    case 'PENDING':
    case 'QUEUED':
      return '待回传平台（2.5.1.1 未交付）';
    case 'SENT':
      return '已提交平台';
    case 'ACKNOWLEDGED':
      return '平台已受理';
    case 'FAILED':
      return `回传失败：${row.callbackLastError || '未记录原因'}`;
    default:
      return row.callbackStatus;
  }
}

/** 我方段还没开始：代发模式下本就没有这一段，不是异常。 */
export function ownLegIdle(row: DropshipTrackingRow): boolean {
  return row.activeLeg === 'SUPPLIER' && row.ownLegStatus === 'NOT_HANDED_OVER';
}

/** 切换到自营后，供应商段是「保留的凭据」而不是当前承运。 */
export function supplierLegAbandoned(row: DropshipTrackingRow): boolean {
  return row.activeLeg === 'OWN' && row.supplierLegStatus === 'ABANDONED';
}

// ------------------------------------------------------------ 处置表单校验

/** 未结案异常才能处置：已处置/已判误报的再处置一遍只会造出一条假动作。 */
export function canDispose(row: DropshipTrackingExceptionRow): boolean {
  return row.status === 'OPEN' || row.status === 'ACKNOWLEDGED';
}

/** 能否判误报：只对未结案的开放。 */
export function canDismiss(row: DropshipTrackingExceptionRow): boolean {
  return row.status === 'OPEN' || row.status === 'ACKNOWLEDGED';
}

/** 退款必须给金额与币种，缺一不可（后端也会拒绝，前端先说清楚）。 */
export function disposeFormError(
  action: string,
  amount: string,
  currency: string,
  note: string,
): string {
  if (!action) return '请选择处置动作';
  if (!note.trim()) return '处置必须写明依据：催什么、为什么改自营、退多少';
  if (action === 'REQUEST_REFUND') {
    if (!amount.trim()) return '发起退款必须填写退款金额';
    const value = Number(amount);
    if (!Number.isFinite(value) || value <= 0) return '退款金额必须大于 0';
    if (!currency.trim()) return '发起退款必须选择币种';
    if (!/^[A-Za-z]{3}$/.test(currency.trim())) return '币种必须是三位字母（如 USD）';
  }
  return '';
}

/** 供表单把退款金额转成后端要的 numeric 字符串。 */
export function refundAmountText(value: string): string {
  return Number(value).toFixed(4);
}

// ------------------------------------------------------------ 格式化

export function moneyText(value: string | number | null | undefined, currency?: string): string {
  if (value === null || value === undefined || value === '') return '—';
  const suffix = currency ? ` ${currency}` : '';
  return `${value}${suffix}`;
}

export function timeText(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('zh-CN', { hour12: false });
}

/** 距今多久（用于「逾期 3 天」「静默 100 小时」这类提示）。 */
export function sinceText(value: string | null | undefined): string {
  if (!value) return '—';
  const ms = Date.now() - new Date(value).getTime();
  if (Number.isNaN(ms)) return '—';
  const hours = Math.floor(ms / 3_600_000);
  if (hours < 1) return `${Math.max(0, Math.floor(ms / 60_000))} 分钟前`;
  if (hours < 48) return `${hours} 小时前`;
  return `${Math.floor(hours / 24)} 天前`;
}

/** 承诺时限还剩多久；已过返回负值前缀「已逾期」。 */
export function promisedText(value: string): string {
  const ms = new Date(value).getTime() - Date.now();
  const hours = Math.floor(ms / 3_600_000);
  if (hours < 0) return `已逾期 ${Math.abs(hours)} 小时`;
  if (hours < 48) return `还有 ${hours} 小时`;
  return `还有 ${Math.floor(hours / 24)} 天`;
}

export function dimensionNote(
  capabilities: DropshipTrackingCapabilities | null,
  code: string,
): string {
  const found = capabilities?.dimensions.find((d) => d.code === code);
  if (!found) return '';
  return found.note ?? found.source;
}

/** 下游是否真的接得住某一类动作（未交付就说未交付）。 */
export function targetReady(
  capabilities: DropshipTrackingCapabilities | null,
  code: string,
): boolean {
  return capabilities?.dimensions.find((d) => d.code === code)?.available ?? false;
}

/** 把后端错误翻译成人话（含 404 与网络抖动）。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 404) return '找不到这条记录（可能已被其他成员删除，或不属于当前租户）';
    if (error.status === 403) return '当前角色没有此操作权限';
    if (error.status === 0) return '网络异常或服务暂不可用，请稍后重试';
    return error.message || '操作失败';
  }
  if (error instanceof Error) return error.message;
  return '操作失败';
}
