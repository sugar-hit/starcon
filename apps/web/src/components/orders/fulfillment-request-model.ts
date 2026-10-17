import type {
  FulfillmentRequestCancelInput,
  FulfillmentRequestCapabilities,
  FulfillmentRequestDetail,
  FulfillmentRequestEventRow,
  FulfillmentRequestInput,
  FulfillmentRequestOutboundAckInput,
  FulfillmentRequestOutboundInput,
  FulfillmentRequestOutboundRow,
  FulfillmentRequestPage,
  FulfillmentRequestReservationAckInput,
  FulfillmentRequestReservationInput,
  FulfillmentRequestReservationRow,
  FulfillmentRequestResumablePage,
  FulfillmentRequestRetryInput,
  FulfillmentRequestRow,
  FulfillmentRequestSaved,
  FulfillmentRequestScanInput,
  FulfillmentRequestScanResult,
  FulfillmentRequestStatusInput,
  FulfillmentRequestTimeoutPage,
  FulfillmentRequestTimeoutRow,
  FulfillmentRequestTransitionResult,
  FulfillmentRequestWorkOrderInput,
  FulfillmentRequestWorkOrderRow,
  FulfillmentRequestWorkOrderStatusInput,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 9.3.3.1 履约请求前端模型。
 *
 * <p>蓝图：「编排一次履约：锁定预占 → 生成出库单 → 编排作业 → 跟踪完成；
 * 持久化流程状态，支持断点恢复与超时重试。」
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>「已锁定预占」必须带出处。</b>本模块**只登记**下游指令，没真的锁 warehouse 的库存
 *       （capabilities 里 {@code reservesUpstreamStock=false}）。所以列表与详情都要把
 *       「本模块不预占上游库存」摆在第一眼能看到的位置，而不是让运营以为货已经锁住了 ——
 *       这正是 9.3.2.1（V116）踩过并写进证据的坑。</li>
 *   <li><b>「待回执」与「已锁定」是两句话。</b>预占缺省是「待回执」，界面上绝不能显示成
 *       「已锁定」；已锁定时必须把 warehouse 的凭据号显示出来，否则复盘时无从追问。</li>
 *   <li><b>卡住要显示「卡在哪一步、卡了多久、为什么」。</b>超时不是失败：先查证再决定重试
 *       还是释放预占。所以挂起原因与已流逝秒数必须直接可读，而不是一个笼统的红色标签。</li>
 *   <li><b>重试要写理由。</b>无理由的重试会变成重复发货，所以 {@code resumeReason} 在界面上
 *       是必填，不是可选备注。</li>
 *   <li><b>金额与数量不再二次四舍五入。</b>后端已按 numeric 落库并返回刻度化值，
 *       前端只做展示格式化。</li>
 * </ul>
 */

export interface FulfillmentRequestListParams {
  requestCode?: string;
  orderRef?: string;
  warehouseRef?: string;
  skuRef?: string;
  platformCode?: string;
  splitUnitRef?: string;
  consolidationRef?: string;
  allocationDecisionRef?: string;
  status?: string;
  currentStep?: string;
  blockedOnly?: boolean;
  overdueOnly?: boolean;
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

/** 与后端 @RequestMapping("/trade/fulfillment/requests") 一一对应。 */
const base = 'trade/fulfillment/requests';

export function fetchCapabilities(): Promise<FulfillmentRequestCapabilities> {
  return api<FulfillmentRequestCapabilities>(`${base}/capabilities`);
}

export function fetchRequests(
  params: FulfillmentRequestListParams,
): Promise<FulfillmentRequestPage> {
  return api<FulfillmentRequestPage>(`${base}${query(params)}`);
}

export function fetchResumable(params: {
  page?: number;
  size?: number;
}): Promise<FulfillmentRequestResumablePage> {
  return api<FulfillmentRequestResumablePage>(`${base}/resumable${query(params)}`);
}

export function fetchTimeouts(params: {
  stepKind?: string;
  reasonCode?: string;
  page?: number;
  size?: number;
}): Promise<FulfillmentRequestTimeoutPage> {
  return api<FulfillmentRequestTimeoutPage>(`${base}/timeouts${query(params)}`);
}

export function createRequest(input: FulfillmentRequestInput): Promise<FulfillmentRequestSaved> {
  return api<FulfillmentRequestSaved>(`${base}`, 'POST', input);
}

export function fetchRequest(requestId: string): Promise<FulfillmentRequestDetail> {
  return api<FulfillmentRequestDetail>(`${base}/${requestId}`);
}

export function advanceRequest(
  requestId: string,
  input: FulfillmentRequestStatusInput,
): Promise<FulfillmentRequestTransitionResult> {
  return api<FulfillmentRequestTransitionResult>(`${base}/${requestId}/advance`, 'POST', input);
}

export function blockRequest(
  requestId: string,
  input: FulfillmentRequestBlockInput,
): Promise<FulfillmentRequestTransitionResult> {
  return api<FulfillmentRequestTransitionResult>(`${base}/${requestId}/block`, 'POST', input);
}

export function retryRequest(
  requestId: string,
  input: FulfillmentRequestRetryInput,
): Promise<FulfillmentRequestTransitionResult> {
  return api<FulfillmentRequestTransitionResult>(`${base}/${requestId}/retry`, 'POST', input);
}

export function cancelRequest(
  requestId: string,
  input: FulfillmentRequestCancelInput,
): Promise<FulfillmentRequestTransitionResult> {
  return api<FulfillmentRequestTransitionResult>(`${base}/${requestId}/cancel`, 'POST', input);
}

export function completeRequest(
  requestId: string,
  input: FulfillmentRequestRetryInput,
): Promise<FulfillmentRequestTransitionResult> {
  return api<FulfillmentRequestTransitionResult>(`${base}/${requestId}/complete`, 'POST', input);
}

export function scanTimeouts(
  input: FulfillmentRequestScanInput,
): Promise<FulfillmentRequestScanResult> {
  return api<FulfillmentRequestScanResult>(`${base}/scan-timeouts`, 'POST', input);
}

// ---------------------------------------------------------------- 下游指令

export function requestReservation(
  requestId: string,
  input: FulfillmentRequestReservationInput,
): Promise<FulfillmentRequestReservationRow> {
  return api<FulfillmentRequestReservationRow>(`${base}/${requestId}/reservation`, 'POST', input);
}

export function acknowledgeReservation(
  requestId: string,
  input: FulfillmentRequestReservationAckInput,
): Promise<FulfillmentRequestReservationRow> {
  return api<FulfillmentRequestReservationRow>(
    `${base}/${requestId}/reservation/ack`,
    'POST',
    input,
  );
}

export function requestOutboundOrder(
  requestId: string,
  input: FulfillmentRequestOutboundInput,
): Promise<FulfillmentRequestOutboundRow> {
  return api<FulfillmentRequestOutboundRow>(`${base}/${requestId}/outbound-order`, 'POST', input);
}

export function acknowledgeOutboundOrder(
  requestId: string,
  input: FulfillmentRequestOutboundAckInput,
): Promise<FulfillmentRequestOutboundRow> {
  return api<FulfillmentRequestOutboundRow>(
    `${base}/${requestId}/outbound-order/ack`,
    'POST',
    input,
  );
}

export function assignWorkOrders(
  requestId: string,
  input: FulfillmentRequestWorkOrderInput,
): Promise<FulfillmentRequestWorkOrderRow[]> {
  return api<FulfillmentRequestWorkOrderRow[]>(`${base}/${requestId}/work-orders`, 'POST', input);
}

export function updateWorkOrder(
  requestId: string,
  workOrderId: string,
  input: FulfillmentRequestWorkOrderStatusInput,
): Promise<FulfillmentRequestWorkOrderRow> {
  return api<FulfillmentRequestWorkOrderRow>(
    `${base}/${requestId}/work-orders/${workOrderId}/status`,
    'POST',
    input,
  );
}

// ---------------------------------------------------------------- 视图辅助

/** 本模型自己声明的入参类型（与后端 record 一一对应）。 */
export interface FulfillmentRequestBlockInput {
  stepKind: string;
  detectionKind: string;
  reasonCode: string;
  reason: string;
  expectedVersion: number;
}

/** 错误文案：把 ApiError / 普通 Error 统一成人话，未知异常给一句可重试的兜底。 */
export function errorText(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return '履约请求服务暂时不可用，请稍后重试';
}

/**
 * 版本冲突（409）在界面上是**可恢复**的：重新读一遍再让人决定，不是一句红字。
 */
export function isVersionConflict(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409;
}

/**
 * 「这票货现在到哪一步」的进度条。四步固定顺序，与后端 FulfillmentStepKind 同序。
 */
export const STEP_LADDER = ['RESERVE', 'OUTBOUND', 'WORK', 'TRACK'] as const;

export const STEP_LABELS: Record<string, string> = {
  RESERVE: '锁定预占',
  OUTBOUND: '生成出库单',
  WORK: '编排作业',
  TRACK: '跟踪完成',
};

/**
 * 已经走到第几步（0~4）。
 *
 * <p>只数「已完成」的步骤，不看当前状态 —— 这样「出库单待确认」与「出库单已确认」
 * 都显示「已到出库单」，而状态本身由另一处显示。两件事混在一起会让人以为
 * 「待确认」已经等于「已确认」。
 */
export function reachedStep(row: FulfillmentRequestRow, steps: FulfillmentRequestStep[]): number {
  const done = new Set(
    steps
      .filter((s) => s.status === 'COMPLETED')
      .map((s) => s.stepKind)
      .filter((kind): kind is (typeof STEP_LADDER)[number] =>
        (STEP_LADDER as readonly string[]).includes(kind),
      ),
  );
  let reached = 0;
  for (const kind of STEP_LADDER) {
    if (done.has(kind)) reached += 1;
    else break;
  }
  return reached;
}

/** 与上面配套的步骤行最小形状（避免前端依赖整个 detail 类型）。 */
export interface FulfillmentRequestStep {
  stepKind: string;
  status: string;
  attempt?: number;
}

/** 预占行的一句话：区分「待回执」与「已锁定」，两者在界面上必须是两句话。 */
export function reservationSummary(row: FulfillmentRequestReservationRow): string {
  switch (row.status) {
    case 'LOCKED':
      return `已锁定 ${row.lockedQty ?? '-'} 件（凭据 ${row.reservationRef}）`;
    case 'REJECTED':
      return `已被拒：${row.rejectReason || '未写明原因'}`;
    case 'RELEASED':
      return `已释放：${row.rejectReason || '未写明原因'}`;
    default:
      return '待 warehouse 回执（本模块尚未锁定任何库存）';
  }
}

/** 出库单行的一句话。 */
export function outboundSummary(row: FulfillmentRequestOutboundRow): string {
  switch (row.status) {
    case 'CONFIRMED':
      return `已确认（单号 ${row.outboundRef}）`;
    case 'REJECTED':
      return `被拒：${row.rejectReason || '未写明原因'}`;
    case 'CANCELLED':
      return `已取消：${row.rejectReason || '未写明原因'}`;
    default:
      return '待 8.3.1 回执（本模块尚未把单派出去）';
  }
}

/** 超时记录的一句话：卡在哪一步、卡了多久、为什么。 */
export function timeoutSummary(row: FulfillmentRequestTimeoutRow): string {
  const seconds = Number(row.overdueSeconds ?? 0);
  const elapsed =
    seconds >= 3600 ? `${(seconds / 3600).toFixed(1)} 小时` : `${seconds.toFixed(0)} 秒`;
  return `${row.stepKindLabel} 步 · 阈值 ${row.thresholdSeconds}s · 已过 ${elapsed} · ${row.reason}`;
}

/** 事件的一句话。 */
export function eventSummary(row: FulfillmentRequestEventRow): string {
  const flow =
    row.fromStatus && row.toStatus && row.fromStatus !== row.toStatus
      ? `（${row.fromStatus} → ${row.toStatus}）`
      : '';
  return `${row.eventKindLabel}${flow} · ${row.detail}`;
}
