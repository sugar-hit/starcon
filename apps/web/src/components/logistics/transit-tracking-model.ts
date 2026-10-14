import type {
  DelayAlertAckRequest,
  DelayAlertCloseRequest,
  DelayAlertPage,
  DelayAlertRow,
  DelayThresholdRow,
  DelayThresholdUpdateRequest,
  TrackingCreateRequest,
  TrackingDimension,
  TrackingEventRequest,
  TrackingReceiptRequest,
  TrackingRow,
  TransitTrackCapabilities,
  TransitTrackDetail,
  TransitTrackPage,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../inventory/api';

/**
 * 10.4.1.2 在途跟踪前端模型。
 *
 * <p>蓝图关系段原文：**头程延误是断货的主要外因，预警必须提前到预计到港前若干天**。
 * 于是界面上最容易读错的三处，这个模块都显式处理了：
 *
 * <ul>
 *   <li>**「延误」不等于「已经晚了」**。最该被看见的是提前进区的那一批
 *       （默认到港前 7 天就进 WATCH），所以筛选口径与档位标签都跟着后端同一套阈值走，
 *       前端**不**自己写死「几天算延误」—— 阈值行可在界面上改，前端抄一份就静默漂移。
 *   <li>**剩余窗口而不是「还有几天」这种模糊说法**：负数 = 已经过了预计到港仍未到货，
 *       这是两种完全不同严重度的事，界面上必须分开显示而不是都写成「延期中」。
 *   <li>**在途余量是借来的数**。发运 / 已了结 / 未达三个数都读 7.5.1.2 的在途腿，
 *       本模块不持有余额，所以界面上要把「原发 = 已了结 + 未达」的守恒等式算出来显示，
 *       对不上时直接说对不上，而不是把三个数并排一摆让人自己心算。
 * </ul>
 */

/** 列表查询条件。与后端 `TrackingQuery` 一一对应。 */
export interface TransitTrackingParams {
  status?: string[];
  stage?: string[];
  customsStatus?: string[];
  delayLevel?: string[];
  shipmentId?: string;
  destinationWarehouseId?: string;
  destinationCountryCode?: string;
  transportMode?: string;
  /** 只要有任何延误信号（含 WATCH 提前提示区），与后端 delayedOnly 同口径。 */
  delayedOnly?: boolean;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function transitTrackingQuery(params: TransitTrackingParams): string {
  const parts: string[] = [];
  for (const s of params.status ?? []) parts.push(`status=${encodeURIComponent(s)}`);
  for (const s of params.stage ?? []) parts.push(`stage=${encodeURIComponent(s)}`);
  for (const s of params.customsStatus ?? []) parts.push(`customsStatus=${encodeURIComponent(s)}`);
  for (const s of params.delayLevel ?? []) parts.push(`delayLevel=${encodeURIComponent(s)}`);
  if (params.shipmentId) parts.push(`shipmentId=${encodeURIComponent(params.shipmentId)}`);
  if (params.destinationWarehouseId) {
    parts.push(`destinationWarehouseId=${encodeURIComponent(params.destinationWarehouseId)}`);
  }
  if (params.destinationCountryCode?.trim()) {
    parts.push(
      `destinationCountryCode=${encodeURIComponent(params.destinationCountryCode.trim())}`,
    );
  }
  if (params.transportMode) parts.push(`transportMode=${encodeURIComponent(params.transportMode)}`);
  if (params.delayedOnly) parts.push('delayedOnly=true');
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchTransitTrackings(params: TransitTrackingParams) {
  return api<TransitTrackPage>(`warehouse/transit-trackings${transitTrackingQuery(params)}`);
}

export function fetchTransitTrackingCapabilities() {
  return api<TransitTrackCapabilities>('warehouse/transit-trackings/capabilities');
}

export function fetchTransitTrackingDetail(trackingId: string) {
  return api<TransitTrackDetail>(`warehouse/transit-trackings/${encodeURIComponent(trackingId)}`);
}

export function createTransitTracking(input: TrackingCreateRequest) {
  return api<TrackingRow>('warehouse/transit-trackings', 'POST', input);
}

/** 登记节点：位置 / 改期 / 清关 / 阶段推进 / 备注，五合一入口。 */
export function recordTransitTrackingEvent(trackingId: string, input: TrackingEventRequest) {
  return api<TrackingRow>(
    `warehouse/transit-trackings/${encodeURIComponent(trackingId)}/events`,
    'POST',
    input,
  );
}

/** 目的仓收货确认：只记确认事实，入库落账归 7.5.1.2。 */
export function confirmTransitTrackingReceipt(trackingId: string, input: TrackingReceiptRequest) {
  return api<TrackingRow>(
    `warehouse/transit-trackings/${encodeURIComponent(trackingId)}/receipt`,
    'POST',
    input,
  );
}

/** 关闭跟踪。必须给原因：没有原因的闭环事后没人能解释这批货为什么停止跟踪。 */
export function closeTransitTracking(trackingId: string, reason: string) {
  return api<TrackingRow>(
    `warehouse/transit-trackings/${encodeURIComponent(trackingId)}/close`,
    'POST',
    { reason },
  );
}

export function fetchDelayAlerts(params: {
  level?: string[];
  status?: string[];
  keyword?: string;
  page?: number;
  size?: number;
}) {
  const parts: string[] = [];
  for (const s of params.level ?? []) parts.push(`level=${encodeURIComponent(s)}`);
  for (const s of params.status ?? []) parts.push(`status=${encodeURIComponent(s)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return api<DelayAlertPage>(
    `warehouse/transit-trackings/alerts${parts.length ? `?${parts.join('&')}` : ''}`,
  );
}

export function evaluateOneAlert(trackingId: string) {
  return api<{ raised: number }>(
    `warehouse/transit-trackings/${encodeURIComponent(trackingId)}/alerts/evaluate`,
    'POST',
  );
}

/** 批量重算。**没有定时器**：外部同步与常驻调度均未交付，由运营手工触发。 */
export function evaluateAllAlerts() {
  return api<{ raised: number }>('warehouse/transit-trackings/alerts/evaluate-all', 'POST');
}

export function acknowledgeDelayAlert(alertId: string, input: DelayAlertAckRequest) {
  return api<DelayAlertRow>(
    `warehouse/transit-trackings/alerts/${encodeURIComponent(alertId)}/acknowledge`,
    'POST',
    input,
  );
}

export function closeDelayAlert(alertId: string, input: DelayAlertCloseRequest) {
  return api<DelayAlertRow>(
    `warehouse/transit-trackings/alerts/${encodeURIComponent(alertId)}/close`,
    'POST',
    input,
  );
}

export function fetchDelayThresholds() {
  return api<DelayThresholdRow[]>('warehouse/transit-trackings/thresholds');
}

/** 改预警口径：运营换「提前几天」不发版。PUT 是整行三档一起换。 */
export function updateDelayThreshold(thresholdId: string, input: DelayThresholdUpdateRequest) {
  return api<DelayThresholdRow>(
    `warehouse/transit-trackings/thresholds/${encodeURIComponent(thresholdId)}`,
    'PUT',
    input,
  );
}

/**
 * 档位标签与配色语义。
 *
 * <p>颜色只按严重度给，**不按剩余天数自己判** —— 判档的后端阈值行是可改的，
 * 前端再判一次就会出现「行上写着 WATCH、卡片染成红色」这种自相矛盾。
 */
export const delayLevelLabels: Record<string, string> = {
  ON_TRACK: '正常在途',
  WATCH: '临近到港',
  WARNING: '延误预警',
  CRITICAL: '严重延误',
};

/** 档位严重度排序，列表按档位排时用。ON_TRACK 最低。 */
export const delayLevelSeverity: Record<string, number> = {
  ON_TRACK: 0,
  WATCH: 1,
  WARNING: 2,
  CRITICAL: 3,
};

export function delayLevelLabel(code: string): string {
  return delayLevelLabels[code] ?? code;
}

/**
 * 档位 → Badge 色调。
 *
 * <p>**按档位映射，不按剩余天数自己判**：判档的后端阈值行是可改的，
 * 前端再判一次就会出现「行上写着 WATCH、卡片染成红色」这种自相矛盾。
 * 未收录的档位给中性色而不是猜一个红色 —— 猜错等于把未知状态说成最严重。
 */
export function delayTone(code: string): 'neutral' | 'warning' | 'danger' | 'info' {
  switch (code) {
    case 'CRITICAL':
      return 'danger';
    case 'WARNING':
      return 'warning';
    case 'WATCH':
      return 'info';
    default:
      return 'neutral';
  }
}

/** 排序白名单。后端对非法值静默回落到默认键，前端不给非法选项。 */
export const transitTrackingSortOptions = [
  { value: 'LEVEL_DESC', label: '按严重度' },
  { value: 'REMAINING_ASC', label: '剩余窗口最短' },
  { value: 'ETA_ASC', label: '预计到港最近' },
  { value: 'UPDATED_AT', label: '最近更新' },
  { value: 'CREATED_AT', label: '建跟踪时间' },
];

/**
 * 剩余窗口的展示：**负数是「已经晚了」，不是「提前」**。
 *
 * <p>这两种情况严重度完全不同（一个是正常在途，一个是严重延误），混成一句
 * 「延期中」会把最该被处理的那批货显示成最正常的样子。
 */
export function remainingWindow(row: { remainingDays: number | null; overdue: boolean }): {
  text: string;
  late: boolean;
} {
  if (row.remainingDays === null || row.remainingDays === undefined) {
    return { text: '未给 ETA，无法分级', late: false };
  }
  const days = Number(row.remainingDays);
  if (row.overdue || days < 0) {
    return { text: `已过预计到港 ${Math.abs(days).toFixed(1)} 天仍未到货`, late: true };
  }
  return { text: `距预计到港还有 ${days.toFixed(1)} 天`, late: false };
}

/**
 * 在途腿守恒等式：原发 = 已了结 + 未达。
 *
 * <p>这三个数来自 7.5.1.2 的权威腿，本模块只是 join 出来。算一遍等式是为了在界面上
 * 说出「货权与在途有没有脱节」——对不上时直接告诉人刷新，而不是并排摆三个数字。
 */
export function legQuantityText(leg: {
  shippedQuantity: number;
  settledQuantity: number;
  remainingQuantity: number;
}): { ok: boolean; text: string } {
  const shipped = Number(leg.shippedQuantity) || 0;
  const settled = Number(leg.settledQuantity) || 0;
  const remaining = Number(leg.remainingQuantity) || 0;
  const ok = Math.abs(shipped - settled - remaining) < 0.0001;
  return {
    ok,
    text: `原发 ${fmt(shipped)} = 已了结 ${fmt(settled)} + 未达 ${fmt(remaining)}${ok ? '' : '（对不上，请刷新）'}`,
  };
}

/** 一次可以推进的动作，按跟踪状态给出。终态不给动作。 */
export function trackingActions(row: {
  status: string;
  trackingStage: string;
  customsStatus: string;
}): string[] {
  if (row.status !== 'TRACKING') return [];
  const out = ['event'];
  // 清关未放行就不给「目的仓已收货」：那会让「货被扣在关口却已入库」同时成立。
  if (row.customsStatus === 'CLEARED' || row.customsStatus === 'NOT_STARTED') {
    if (row.trackingStage !== 'RECEIVED') out.push('receipt');
  }
  out.push('close');
  return out;
}

/** 数量展示。 */
export function fmt(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return Number(value).toLocaleString('zh-CN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 6,
  });
}

/** 时间展示，**带偏移量**：跨时区看单子时把到港时刻读错是最贵的错误。 */
export function moment(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/** 下拉项展示。未交付的依赖带归属模块，界面上直接写明，不假装可用。 */
export function dimensionCaption(dimension: TrackingDimension): string {
  return dimension.available
    ? dimension.label
    : `${dimension.label}（${dimension.ownerModule} 未交付）`;
}

/** 事实来源展示：外部来源的单号必须看得见，否则「谁报的」永远答不出来。 */
export function sourceCaption(event: { sourceSystem: string; referenceId: string | null }): string {
  const label =
    event.sourceSystem === 'MANUAL'
      ? '人工登记'
      : event.sourceSystem === 'FORWARDER'
        ? '货代'
        : event.sourceSystem === 'CARRIER'
          ? '船司/航空公司'
          : '清关行/报关行';
  return event.referenceId ? `${label} · ${event.referenceId}` : label;
}
