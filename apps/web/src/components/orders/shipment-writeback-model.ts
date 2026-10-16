import type {
  ShipmentWritebackBatchRetryInput,
  ShipmentWritebackBatchRetryResult,
  ShipmentWritebackBoard,
  ShipmentWritebackCapabilities,
  ShipmentWritebackCorrectionInput,
  ShipmentWritebackCreateInput,
  ShipmentWritebackCreateResult,
  ShipmentWritebackEventPage,
  ShipmentWritebackOutcomeInput,
  ShipmentWritebackTaskDetail,
  ShipmentWritebackTaskPage,
  ShipmentWritebackVerifyInput,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 9.4.2.1 平台回传前端模型。
 *
 * 蓝图：「把发货信息（运单号、承运商、发货时间）回传平台并跟踪结果：成功 / 失败 /
 * **结果未知**；支持批量重试与人工修正。」
 *
 * 关系里那句加粗是本模块的全部要害：
 * 「**回传失败会导致平台显示未发货并可能判定迟发**，因此回传状态必须可监控可补偿；
 * **结果未知要先查证再重发**。」
 *
 * <b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>三态不可退化成两态。</b>「结果未知」在标签、配色、可执行动作上都是
 *       <b>独立的一套</b>，既不并进「失败」（平台可能已经收到，重发 = 平台侧重复发货），
 *       也不并进「成功」（平台压根没收到，迟发判定成立而无人知晓）。
 *       未知态在界面上<b>唯一</b>可执行的动作是「登记查证结论」。</li>
 *   <li><b>「不可重发」的理由要逐条显示。</b>终态 / 达重试上限 / 退避未到点 /
 *       结果未知待查证是四种不同的「现在不能发」，合并成一句「暂不可用」会让
 *       运营看不出到底该等、该改，还是该去平台侧查。</li>
 *   <li><b>分组与汇总是服务端算的。</b>本模块不自算任何统计，避免翻页后数字对不上。</li>
 *   <li><b>数量一律是字符串。</b>payload 里 totalQuantity 是定点数字符串，
 *       渲染时不做浮点运算，也不 <code>Number()</code> 转换。</li>
 * </ul>
 */

const base = 'trade/fulfillment/shipment-writeback';

// ------------------------------------------------------------ 参数

export interface ShipmentWritebackListParams {
  status?: string;
  platformCode?: string;
  carrierCode?: string;
  verification?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export interface ShipmentWritebackBoardParams {
  status?: string;
  platformCode?: string;
  carrierCode?: string;
  groupBy?: string;
}

export interface ShipmentWritebackEventParams {
  eventKind?: string;
  taskId?: string;
  page?: number;
  size?: number;
}

function query(params: object): string {
  const parts = Object.entries(params as Record<string, string | number | undefined>)
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ------------------------------------------------------------ 读

export function fetchShipmentWritebackCapabilities(): Promise<ShipmentWritebackCapabilities> {
  return api<ShipmentWritebackCapabilities>(`${base}/capabilities`);
}

export function fetchShipmentWritebackBoard(
  params: ShipmentWritebackBoardParams,
): Promise<ShipmentWritebackBoard> {
  return api<ShipmentWritebackBoard>(`${base}/board${query(params)}`);
}

export function fetchShipmentWritebackTasks(
  params: ShipmentWritebackListParams,
): Promise<ShipmentWritebackTaskPage> {
  return api<ShipmentWritebackTaskPage>(`${base}/tasks${query(params)}`);
}

export function fetchShipmentWritebackTask(taskId: string): Promise<ShipmentWritebackTaskDetail> {
  return api<ShipmentWritebackTaskDetail>(`${base}/tasks/${taskId}`);
}

export function fetchShipmentWritebackEvents(
  params: ShipmentWritebackEventParams,
): Promise<ShipmentWritebackEventPage> {
  return api<ShipmentWritebackEventPage>(`${base}/events${query(params)}`);
}

// ------------------------------------------------------------ 写

/** 从 9.4.1.2 的发货记录建回传任务（幂等键由服务端构造，调用方不能自拟）。 */
export function createShipmentWritebackTasks(
  input: ShipmentWritebackCreateInput,
): Promise<ShipmentWritebackCreateResult> {
  return api<ShipmentWritebackCreateResult>(`${base}/tasks`, 'POST', input);
}

export function submitShipmentWriteback(
  taskId: string,
  note?: string,
): Promise<ShipmentWritebackTaskDetail> {
  return api<ShipmentWritebackTaskDetail>(`${base}/tasks/${taskId}/submit`, 'POST', {
    note: note ?? '',
  });
}

/** 登记平台侧结果三态。`status` 只能是 CONFIRMED / REJECTED / PENDING_VERIFICATION。 */
export function registerShipmentWritebackOutcome(
  taskId: string,
  input: ShipmentWritebackOutcomeInput,
): Promise<ShipmentWritebackTaskDetail> {
  return api<ShipmentWritebackTaskDetail>(`${base}/tasks/${taskId}/outcome`, 'POST', input);
}

/** 登记查证结论 —— 结果未知态**必须**先走这里才谈得上重发。 */
export function verifyShipmentWriteback(
  taskId: string,
  input: ShipmentWritebackVerifyInput,
): Promise<ShipmentWritebackTaskDetail> {
  return api<ShipmentWritebackTaskDetail>(`${base}/tasks/${taskId}/verify`, 'POST', input);
}

export function retryShipmentWriteback(
  taskId: string,
  note?: string,
): Promise<ShipmentWritebackTaskDetail> {
  return api<ShipmentWritebackTaskDetail>(`${base}/tasks/${taskId}/retry`, 'POST', {
    note: note ?? '',
  });
}

export function batchRetryShipmentWriteback(
  input: ShipmentWritebackBatchRetryInput,
): Promise<ShipmentWritebackBatchRetryResult> {
  return api<ShipmentWritebackBatchRetryResult>(`${base}/retry-batch`, 'POST', input);
}

/** 人工修正：新建一版（幂等键随之变化），原任务转 SUPERSEDED 留作证据。 */
export function correctShipmentWriteback(
  taskId: string,
  input: ShipmentWritebackCorrectionInput,
): Promise<ShipmentWritebackTaskDetail> {
  return api<ShipmentWritebackTaskDetail>(`${base}/tasks/${taskId}/corrections`, 'POST', input);
}

// ------------------------------------------------------------ 呈现口径

/**
 * 三态词汇。**顺序即重要性**：「结果未知」排在「平台拒绝」之后但在视觉分组上
 * 单列，界面上永远不与失败/成功合并。
 */
export const shipmentWritebackStatusLabels: Record<string, string> = {
  PENDING: '待回传',
  SUBMITTED: '已提交平台',
  CONFIRMED: '平台已受理',
  REJECTED: '平台拒绝',
  PENDING_VERIFICATION: '结果未知·待查证',
  SKIPPED: '决定不回传',
  SUPERSEDED: '已被修正版取代',
};

export function shipmentWritebackStatusLabel(status: string | undefined): string {
  if (!status) return '未知';
  return shipmentWritebackStatusLabels[status] ?? status;
}

/** 视觉分档。三态各自独立成档，这是「不可退化成两态」在样式上的落点。 */
export type ShipmentWritebackTone = 'pending' | 'inflight' | 'ok' | 'bad' | 'unknown' | 'muted';

export function shipmentWritebackTone(status: string | undefined): ShipmentWritebackTone {
  switch (status) {
    case 'PENDING':
      return 'pending';
    case 'SUBMITTED':
      return 'inflight';
    case 'CONFIRMED':
      return 'ok';
    case 'REJECTED':
      return 'bad';
    case 'PENDING_VERIFICATION':
      // 与 bad / ok 都不同档：这是「不知道」，不是「不行」也不是「行了」。
      return 'unknown';
    case 'SKIPPED':
    case 'SUPERSEDED':
    default:
      return 'muted';
  }
}

export const shipmentWritebackVerificationLabels: Record<string, string> = {
  NOT_REQUIRED: '无需查证',
  CONFIRMED_APPLIED: '查证确认已应用',
  CONFIRMED_NOT_APPLIED: '查证确认未应用',
  STILL_UNKNOWN: '查证后仍不确定',
};

export function shipmentWritebackVerificationLabel(v: string | undefined): string {
  if (!v) return '无需查证';
  return shipmentWritebackVerificationLabels[v] ?? v;
}

export const shipmentWritebackGroupByOptions: { value: string; label: string }[] = [
  { value: 'STATUS', label: '按回传状态' },
  { value: 'PLATFORM', label: '按平台' },
  { value: 'CARRIER', label: '按承运商' },
  { value: 'VERIFICATION', label: '按查证结论' },
];

// ------------------------------------------------------------ 动作可用性

/**
 * 一条任务当前**允许**做哪些动作。
 *
 * <p>刻意与后端 `resendGate` 同口径：前端据此禁用按钮，后端仍会独立拒一次 ——
 * 前端这道只为省一次往返，**不构成安全或正确性边界**。
 */
export interface ShipmentWritebackActions {
  canSubmit: boolean;
  canRegisterOutcome: boolean;
  canVerify: boolean;
  canRetry: boolean;
  canCorrect: boolean;
}

export function shipmentWritebackActions(
  status: string | undefined,
  verification: string | undefined,
): ShipmentWritebackActions {
  const unknown = status === 'PENDING_VERIFICATION';
  const terminal = status === 'CONFIRMED' || status === 'SKIPPED' || status === 'SUPERSEDED';
  return {
    // 已提交、结果未回时不允许重复提交：那一次动作已经在平台侧路上。
    canSubmit: status === 'PENDING' || status === 'REJECTED',
    canRegisterOutcome: status === 'SUBMITTED',
    // 查证只在未知态有意义，且查证过仍不确定时可以换个途径再查一次。
    canVerify: unknown || verification === 'STILL_UNKNOWN',
    // 「查证后仍不确定」挂着时一律不给重试，哪怕状态已经落回可重试。
    canRetry: !unknown && verification !== 'STILL_UNKNOWN' && !terminal,
    // 已受理 / 已被取代改不了内容；SKIPPED 是终态证据，也不改写。
    canCorrect: status !== 'CONFIRMED' && status !== 'SUPERSEDED' && status !== 'SUBMITTED',
  };
}

/**
 * 结果未知态的处置提示 —— 这一态唯一正确的下一步是「去查证」。
 *
 * <p>刻意**不**给「重试」按钮：未知直接重发就是平台侧重复发货，正是蓝图那句
 * 加粗要否掉的实现。查证确认未应用之后，重试才会重新可用。
 */
export const UNKNOWN_NEXT_STEP =
  '结果未知：平台可能已收到也可能没收到。**必须先查证**，只有「确认未应用」才允许重发 —— 直接重发会造成平台侧重复发货。';

export function shipmentWritebackBlockedHint(
  status: string | undefined,
  verification: string | undefined,
): string {
  if (status === 'PENDING_VERIFICATION') {
    return verification === 'STILL_UNKNOWN'
      ? '上一次查证结论仍是「仍不确定」：换一个查证途径再来一次，不要重发。'
      : UNKNOWN_NEXT_STEP;
  }
  return '';
}

/** 汇总条：把「可监控」落成一眼能看完的几个数字。计数由服务端算，不受分页影响。 */
export interface ShipmentWritebackSummaryView {
  total: number;
  pending: number;
  submitted: number;
  confirmed: number;
  rejected: number;
  unknown: number;
  due: number;
  resendAllowed: number;
  atCap: number;
}

export function shipmentWritebackSummaryView(s: {
  totalTasks: number;
  pendingTasks: number;
  submittedTasks: number;
  confirmedTasks: number;
  rejectedTasks: number;
  unknownTasks: number;
  dueTasks: number;
  resendAllowedTasks: number;
  atCapTasks: number;
}): ShipmentWritebackSummaryView {
  return {
    total: s.totalTasks,
    pending: s.pendingTasks,
    submitted: s.submittedTasks,
    confirmed: s.confirmedTasks,
    rejected: s.rejectedTasks,
    unknown: s.unknownTasks,
    due: s.dueTasks,
    resendAllowed: s.resendAllowedTasks,
    atCap: s.atCapTasks,
  };
}

/** 批量重试结果的逐条说法：谁被重投、谁被哪道闸门拦下。 */ export function shipmentWritebackBatchSummary(r: {
  submitted: number;
  rescheduled: number;
  blockedUnknown: number;
  blockedAtCap: number;
  blockedBackoff: number;
  blockedTerminal: number;
}): string[] {
  const out: string[] = [];
  if (r.submitted) out.push(`已重投 ${r.submitted} 条`);
  if (r.rescheduled) out.push(`${r.rescheduled} 条退避未到点，已改排下一次（不算失败）`);
  if (r.blockedUnknown) out.push(`${r.blockedUnknown} 条结果未知待查证，按规矩不放行`);
  if (r.blockedAtCap) out.push(`${r.blockedAtCap} 条已达重试上限，需人工修正或人工核实平台侧`);
  if (r.blockedTerminal) out.push(`${r.blockedTerminal} 条已定论，改内容请走人工修正`);
  if (!out.length) out.push('没有可重投的任务');
  return out;
}

/** 错误信息统一出口：网络失败与业务错误都要说清「为什么」。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '请求平台回传失败，请稍后重试';
}
