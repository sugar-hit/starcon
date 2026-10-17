import type {
  FulfillmentMonitorCapabilities,
  FulfillmentMonitorCaseDetail,
  FulfillmentMonitorCasePage,
  FulfillmentMonitorCaseRow,
  FulfillmentMonitorCaseSaved,
  FulfillmentMonitorCloseInput,
  FulfillmentMonitorDispositionInput,
  FulfillmentMonitorDispositionRow,
  FulfillmentMonitorDispositionSaved,
  FulfillmentMonitorDispositionStatusInput,
  FulfillmentMonitorEffectiveThresholds,
  FulfillmentMonitorEventRow,
  FulfillmentMonitorReleaseAckInput,
  FulfillmentMonitorReleaseRow,
  FulfillmentMonitorScanInput,
  FulfillmentMonitorScanResult,
  FulfillmentMonitorThresholdInput,
  FulfillmentMonitorThresholdRow,
  FulfillmentMonitorTodoActionInput,
  FulfillmentMonitorTodoInput,
  FulfillmentMonitorTodoPage,
  FulfillmentMonitorTodoRow,
  FulfillmentMonitorVerificationRow,
  FulfillmentMonitorVerifyInput,
  FulfillmentMonitorVerifySaved,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 9.3.3.2 履约流程监控前端模型。
 *
 * <p>蓝图：「监控履约流程的异常与超时：预占失败、库存不足、流程卡在某步、超时未发货，
 * 提供重试、切换路径、人工介入入口。」输出是「异常流程清单 + 处置操作 + 处置记录」。
 * 关系句是全部重点：「**超时不是失败**：流程卡住时先查证再决定重试或释放预占，
 * 否则会出现"重试造成重复发货"。」
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>「重试」按钮的可用性由后端说了算，不由前端猜。</b>
 *       详情响应带 {@code retryAllowed} 与 {@code retryBlockedReason}，界面上
 *       把后者原样显示出来 —— 让操作员知道「差一条查证记录」，而不是点一下撞 409。
 *       前端不自己推断「有没有 CONFIRMED 的查证」，那种推断迟早与服务层口径漂移。</li>
 *   <li><b>查证不是点一下按钮。</b>必须选查证范围（查了哪一侧）、写下看到的凭据、
 *       写下结论。范围选「只看本地状态」时明确说明它<b>不足以</b>支持重试。</li>
 *   <li><b>「待回执」不能显示成「已释放」。</b>释放预占只是<b>登记指令</b>
 *       （capabilities 里 {@code reservationReleaseDispatched=false}），
 *       warehouse 那边还没收到任何东西 —— 界面把它显示成已释放，
 *       就会有人以为货已经回池、于是又发一次。</li>
 *   <li><b>阈值必须带来源。</b>界面上「卡了多久 / 阈值多久 / 谁定的」三句话要一起出现，
 *       缺了「谁定的」这条，阈值争议就无从追。</li>
 *   <li><b>超时不是失败。</b>异常行的状态是「待查证 / 查证中 / 已查证 / 已处置 / 已证伪」，
 *       与流程状态分开显示，避免把「卡住」读成「这单坏了」。</li>
 * </ul>
 */

export interface FulfillmentMonitorCaseListParams {
  caseNo?: string;
  requestCode?: string;
  orderRef?: string;
  skuRef?: string;
  warehouseRef?: string;
  stepKind?: string;
  anomalyKind?: string;
  severity?: string;
  status?: string;
  verifiedOnly?: boolean;
  pendingVerificationOnly?: boolean;
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

/** 与后端 @RequestMapping("/trade/fulfillment/monitor") 一一对应。 */
const base = 'trade/fulfillment/monitor';

// ================================================================ 能力 / 清单

export function fetchCapabilities(): Promise<FulfillmentMonitorCapabilities> {
  return api<FulfillmentMonitorCapabilities>(`${base}/capabilities`);
}

export function fetchCases(
  params: FulfillmentMonitorCaseListParams,
): Promise<FulfillmentMonitorCasePage> {
  return api<FulfillmentMonitorCasePage>(`${base}/cases${query(params)}`);
}

export function fetchCase(caseId: string): Promise<FulfillmentMonitorCaseDetail> {
  return api<FulfillmentMonitorCaseDetail>(`${base}/cases/${caseId}`);
}

export function scan(input: FulfillmentMonitorScanInput): Promise<FulfillmentMonitorScanResult> {
  return api<FulfillmentMonitorScanResult>(`${base}/scan`, 'POST', input);
}

// ================================================================ 查证 / 处置

export function verify(
  caseId: string,
  input: FulfillmentMonitorVerifyInput,
): Promise<FulfillmentMonitorVerifySaved> {
  return api<FulfillmentMonitorVerifySaved>(`${base}/cases/${caseId}/verify`, 'POST', input);
}

export function dispose(
  caseId: string,
  input: FulfillmentMonitorDispositionInput,
): Promise<FulfillmentMonitorDispositionSaved> {
  return api<FulfillmentMonitorDispositionSaved>(
    `${base}/cases/${caseId}/disposition`,
    'POST',
    input,
  );
}

export function advanceDisposition(
  caseId: string,
  dispositionId: string,
  input: FulfillmentMonitorDispositionStatusInput,
): Promise<FulfillmentMonitorDispositionRow> {
  return api<FulfillmentMonitorDispositionRow>(
    `${base}/cases/${caseId}/disposition/${dispositionId}/status`,
    'POST',
    input,
  );
}

export function closeCase(
  caseId: string,
  input: FulfillmentMonitorCloseInput,
): Promise<FulfillmentMonitorCaseSaved> {
  return api<FulfillmentMonitorCaseSaved>(`${base}/cases/${caseId}/close`, 'POST', input);
}

export function ackRelease(
  caseId: string,
  releaseId: string,
  input: FulfillmentMonitorReleaseAckInput,
): Promise<FulfillmentMonitorReleaseRow> {
  return api<FulfillmentMonitorReleaseRow>(
    `${base}/cases/${caseId}/release/${releaseId}/ack`,
    'POST',
    input,
  );
}

// ================================================================ 阈值 / 待办

export function fetchThresholds(
  warehouseRef?: string,
): Promise<FulfillmentMonitorEffectiveThresholds> {
  return api<FulfillmentMonitorEffectiveThresholds>(`${base}/thresholds${query({ warehouseRef })}`);
}

export function putThreshold(
  input: FulfillmentMonitorThresholdInput,
): Promise<FulfillmentMonitorThresholdRow> {
  return api<FulfillmentMonitorThresholdRow>(`${base}/thresholds`, 'PUT', input);
}

export function fetchTodos(params: {
  caseId?: string;
  status?: string;
  severity?: string;
  actionKind?: string;
  page?: number;
  size?: number;
}): Promise<FulfillmentMonitorTodoPage> {
  return api<FulfillmentMonitorTodoPage>(`${base}/todos${query(params)}`);
}

export function createTodo(
  caseId: string,
  input: FulfillmentMonitorTodoInput,
): Promise<FulfillmentMonitorTodoRow> {
  return api<FulfillmentMonitorTodoRow>(`${base}/cases/${caseId}/todos`, 'POST', input);
}

export function actionTodo(
  todoId: string,
  input: FulfillmentMonitorTodoActionInput,
): Promise<FulfillmentMonitorTodoRow> {
  return api<FulfillmentMonitorTodoRow>(`${base}/todos/${todoId}/action`, 'POST', input);
}

export function fetchEvents(params: {
  caseId?: string;
  size?: number;
}): Promise<FulfillmentMonitorEventRow[]> {
  return api<FulfillmentMonitorEventRow[]>(`${base}/events${query(params)}`);
}

// ================================================================ 展示辅助

/** 后端返回的可空列在契约里是非可空类型，运行期可能是 null —— 统一在这里收口。 */
function text(value: string | null | undefined): string {
  return value || '';
}

export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '未知错误';
}

/**
 * 异常四类的处置建议。
 *
 * <p>刻意**不**写成「自动执行」的建议：蓝图要人处置，界面只提示最该考虑的动作，
 * 绝不代替操作员点下去 —— 「超时不是失败」这句话的落点就是「人来决定」。
 */
export const ANOMALY_HINT: Record<string, string> = {
  RESERVATION_FAILED:
    '预占被下游拒了。先查 7.2 的回执理由：缺货要走补货或换仓，被其它原因拒了要走人工。',
  INSUFFICIENT_STOCK:
    '库存不足。先查 warehouse 的库存快照确认是不是真没货，再决定补货、换仓还是换路径。',
  STEP_STUCK: '流程卡在某步没推进。先查那一步的下游到底做没做 —— 做了就不能重试，重试会重复发货。',
  SHIPMENT_TIMEOUT:
    '过了发货时限仍未完成。先查承运轨迹与出库单：货已发只是没回传轨迹的，重试会造成重复发货。',
};

/** 查证范围的中文说明；「只看本地状态」明确标出不足以支持重试。 */
export const VERIFIED_SCOPE_HINT: Record<string, string> = {
  DOWNSTREAM_ACK: '已向下游查证回执（查过下游才允许重试）',
  WAREHOUSE_STOCK: '已查 warehouse 库存',
  CARRIER_TRACE: '已查承运轨迹',
  LOCAL_STATE_ONLY: '只看本地状态（**不足以**支持重试：本地没变不代表下游没做）',
};

/**
 * 秒数 → 「2 小时 5 分」。超时是本模块的主指标，不能只给一个裸秒数。
 *
 * <p>余数为 0 的档位**不显示**（「1 小时」而不是「1 小时 0 分」）：
 * 阈值大多是整小时/整天，写成「阈值 1 小时 0 分」会让两句话对不齐、也更像在凑数。
 */
export function overdueText(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds));
  if (total < 60) return `${total} 秒`;
  const minutes = Math.floor(total / 60);
  if (minutes < 60) {
    return total % 60 === 0 ? `${minutes} 分` : `${minutes} 分 ${total % 60} 秒`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return minutes % 60 === 0 ? `${hours} 小时` : `${hours} 小时 ${minutes % 60} 分`;
  }
  const days = Math.floor(hours / 24);
  return hours % 24 === 0 ? `${days} 天` : `${days} 天 ${hours % 24} 小时`;
}

/** 阈值 → 「1 小时」，与 {@link overdueText} 同一把尺子，两句话才能对比着读。 */
export function thresholdText(seconds: number): string {
  return overdueText(seconds);
}

export function severityTone(severity: string): 'danger' | 'warning' | 'muted' {
  if (severity === 'CRITICAL') return 'danger';
  if (severity === 'WARNING') return 'warning';
  return 'muted';
}

/**
 * 「卡了多久 / 阈值多久 / 谁定的」三句话。
 *
 * <p>三句缺一不可：只说「卡了 2 小时」答不出「为什么是 2 小时算卡」，
 * 只说「阈值 2 小时」答不出「到底超了没有」。阈值来源尤其不能省 ——
 * 租户默认 / 仓库覆盖 / 建单快照 / 内置兜底四种，改错了地方完全看不出来。
 */
export function thresholdSummary(row: FulfillmentMonitorCaseRow): string {
  return `${overdueText(row.overdueSeconds)} / 阈值 ${thresholdText(row.thresholdSeconds)}（${row.thresholdSourceLabel}）`;
}

export function anomalyLabel(row: FulfillmentMonitorCaseRow): string {
  return `${row.anomalyKindLabel} · ${row.stepKindLabel}`;
}

export function caseStatusTone(status: string): 'danger' | 'warning' | 'muted' | 'ok' {
  if (status === 'DISPOSED') return 'ok';
  if (status === 'OPEN') return 'danger';
  return 'warning';
}

/**
 * 已查证坐实的查证记录（重试必须引用它）。
 *
 * <p>前端只在这里做「有没有」的展示判断，**不做**「能不能重试」的判断 ——
 * 后端详情响应里的 {@code retryAllowed} 才是权威。
 */
export function confirmedVerification(
  verifications: FulfillmentMonitorVerificationRow[],
): FulfillmentMonitorVerificationRow | null {
  return verifications.find((v) => v.conclusion === 'CONFIRMED') || null;
}

/** 释放登记的中文说明：区分「已登记」与「真的释放了」。 */
export function releaseSummary(row: FulfillmentMonitorReleaseRow): string {
  if (row.status === 'RELEASED') {
    return `已释放（下游凭据 ${text(row.releaseRef)}）`;
  }
  if (row.status === 'REJECTED') {
    return `释放被拒：${text(row.rejectReason)}`;
  }
  return '已登记释放指令，待 7.2 回执 —— 货**尚未**释放';
}

export function eventSummary(row: FulfillmentMonitorEventRow): string {
  const from = text(row.fromStatus);
  const to = text(row.toStatus);
  const arrow = from && to ? `（${from} → ${to}）` : '';
  return `${row.eventKindLabel}${arrow}${text(row.detail) ? ` · ${text(row.detail)}` : ''}`;
}

export function todoSummary(row: FulfillmentMonitorTodoRow): string {
  const who = text(row.claimedByMembershipId)
    ? ` · 认领人 ${text(row.claimedByMembershipId).slice(0, 8)}`
    : '';
  return `${row.actionKindLabel} · ${row.statusLabel}${who}`;
}

/** 可选的处置动作：已结案的案例不再提供处置入口。 */
export function availableActions(status: string): string[] {
  if (status === 'DISPOSED' || status === 'DISMISSED') return [];
  return ['RETRY', 'SWITCH_PATH', 'MANUAL_INTERVENTION', 'RELEASE_RESERVATION'];
}
