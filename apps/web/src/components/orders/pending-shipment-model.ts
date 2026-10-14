import type {
  PendingShipmentBoard,
  PendingShipmentCapabilities,
  PendingShipmentGroup,
  PendingShipmentSummary,
  PendingShipmentSyncInput,
  PendingShipmentSyncResult,
  PendingShipmentUnitDetail,
  PendingShipmentUnitPage,
  PendingShipmentUnitRow,
  PendingShipmentWaybillRow,
  ShipmentBatchTaskDetail,
  ShipmentBatchTaskInput,
  ShipmentBatchTaskItemRow,
  ShipmentBatchTaskPage,
  ShipmentBatchTaskRow,
  ShipmentSlaInput,
  ShipmentWaybillAckInput,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 9.4.1.1 待发货前端模型。
 *
 * <p>蓝图：「待发货订单看板：按仓库、承运渠道、承运商、发货时限分组展示，
 * 支持批量申请运单号、批量打单、批量交接。」
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>分组值不是页内统计。</b>四个分组维度都来自后端 <code>GROUP BY</code>，
 *       与当前翻到第几页无关。前端若拿当页行数自己数，大促期翻到第二页就会看到
 *       「DHL 组突然少了一半」—— 而真实结论是分页口径坏了。</li>
 *   <li><b>未登记时限 ≠ 今天到期。</b>上游没有平台 SLA 可读（见 capabilities），
 *       所以 UNSET 是独立一桶。把它并进「今天到期」等于把最该盯的一批货藏起来。</li>
 *   <li><b>部分成功必须看得见。</b>批量任务的 PARTIAL 与 SUCCEEDED 在界面上分色显示，
 *       且逐条原因直接展开 —— 「交接漏发」显示成「交接完成」是本模块最贵的失败方式。</li>
 *   <li><b>「还没选承运商」是一个状态，不是空白。</b>它上板、但建不了任务，
 *       所以按钮是禁用并给出原因，而不是点了之后报一个看不懂的错误。</li>
 *   <li><b>已交接 ≠ 已发货。</b>本模块最远到交接，页面上不出现「已发货」字样，
 *       避免现场以为货已经发出去了（真正确认发货的是 8.3.4.1）。</li>
 * </ul>
 */

const base = 'trade/fulfillment/pending-shipment';

// ------------------------------------------------------------ 参数

export interface PendingShipmentUnitParams {
  warehouseId?: string;
  carrierChannel?: string;
  carrierCode?: string;
  status?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export interface PendingShipmentBoardParams {
  warehouseId?: string;
  carrierChannel?: string;
  carrierCode?: string;
  status?: string;
  keyword?: string;
  groupBy?: string;
}

export interface ShipmentBatchTaskParams {
  taskType?: string;
  status?: string;
  carrierCode?: string;
  sort?: string;
  page?: number;
  size?: number;
}

/**
 * 把参数拼成 query string。
 *
 * <p>刻意把入参声明成 {@code Record<string, ...>} 而不是复用调用方的 interface：
 * TS 的 interface **没有**隐式索引签名，直接传进去会报
 * 「不能赋给 Record<string, string|number>」。展开成新对象而不是 `as` 断言，
 * 是为了让这条约束在编译期真的被检查到。
 */
function query(params: object): string {
  const parts = Object.entries(params as Record<string, string | number | undefined | null>)
    .filter(([, v]) => v !== undefined && v !== null && `${v}` !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ------------------------------------------------------------ 读

export function fetchPendingShipmentCapabilities(): Promise<PendingShipmentCapabilities> {
  return api<PendingShipmentCapabilities>(`${base}/capabilities`);
}

/** 分组看板。四个维度由后端 GROUP BY 出，不受列表分页影响。 */
export function fetchPendingShipmentBoard(
  params: PendingShipmentBoardParams,
): Promise<PendingShipmentBoard> {
  return api<PendingShipmentBoard>(`${base}/board${query(params)}`);
}

export function fetchPendingShipmentUnits(
  params: PendingShipmentUnitParams,
): Promise<PendingShipmentUnitPage> {
  return api<PendingShipmentUnitPage>(`${base}/units${query(params)}`);
}

export function fetchPendingShipmentUnit(unitId: string): Promise<PendingShipmentUnitDetail> {
  return api<PendingShipmentUnitDetail>(`${base}/units/${unitId}`);
}

export function fetchShipmentBatchTasks(
  params: ShipmentBatchTaskParams,
): Promise<ShipmentBatchTaskPage> {
  return api<ShipmentBatchTaskPage>(`${base}/batch-tasks${query(params)}`);
}

export function fetchShipmentBatchTask(taskId: string): Promise<ShipmentBatchTaskDetail> {
  return api<ShipmentBatchTaskDetail>(`${base}/batch-tasks/${taskId}`);
}

// ------------------------------------------------------------ 写

/** 从 8.3.3.2 的授权只读接口同步已完成的打包出库单（幂等投影）。 */
export function syncPendingShipment(
  input: PendingShipmentSyncInput,
): Promise<PendingShipmentSyncResult> {
  return api<PendingShipmentSyncResult>(`${base}/sync`, 'POST', input);
}

export function registerShipmentSla(
  unitId: string,
  input: ShipmentSlaInput,
): Promise<PendingShipmentUnitRow> {
  return api<PendingShipmentUnitRow>(`${base}/units/${unitId}/sla`, 'POST', input);
}

export function cancelPendingShipmentUnit(
  unitId: string,
  reason: string,
): Promise<PendingShipmentUnitRow> {
  return api<PendingShipmentUnitRow>(`${base}/units/${unitId}/cancel`, 'POST', { reason });
}

export function createShipmentBatchTask(
  input: ShipmentBatchTaskInput,
): Promise<ShipmentBatchTaskDetail> {
  return api<ShipmentBatchTaskDetail>(`${base}/batch-tasks`, 'POST', input);
}

export function executeShipmentBatchTask(taskId: string): Promise<ShipmentBatchTaskDetail> {
  return api<ShipmentBatchTaskDetail>(`${base}/batch-tasks/${taskId}/execute`, 'POST');
}

export function cancelShipmentBatchTask(
  taskId: string,
  reason: string,
): Promise<ShipmentBatchTaskDetail> {
  return api<ShipmentBatchTaskDetail>(`${base}/batch-tasks/${taskId}/cancel`, 'POST', { reason });
}

export function recordShipmentWaybillAck(
  taskId: string,
  unitId: string,
  input: ShipmentWaybillAckInput,
): Promise<ShipmentBatchTaskDetail> {
  return api<ShipmentBatchTaskDetail>(
    `${base}/batch-tasks/${taskId}/waybill-acks${query({ unitId })}`,
    'POST',
    input,
  );
}

export function recordParcelLabelPrinted(
  taskId: string,
  unitId: string,
  parcelId: string,
  jobRef: string,
): Promise<ShipmentBatchTaskDetail> {
  return api<ShipmentBatchTaskDetail>(
    `${base}/batch-tasks/${taskId}/labels${query({ unitId, parcelId })}`,
    'POST',
    { jobRef },
  );
}

// ------------------------------------------------------------ 呈现口径

export const pendingShipmentStatusLabels: Record<string, string> = {
  PENDING: '待发货',
  WAYBILL_REQUESTED: '已申请运单号',
  LABEL_PRINTED: '已打单',
  HANDED_OVER: '已交接',
  CANCELLED: '已作废',
};

export function pendingShipmentStatusLabel(status: string | undefined): string {
  if (!status) return '未知';
  return pendingShipmentStatusLabels[status] ?? status;
}

export const shipmentSlaBucketLabels: Record<string, string> = {
  OVERDUE: '已逾期',
  DUE_TODAY: '今天到期',
  DUE_48H: '48 小时内',
  LATER: '更晚',
  UNSET: '未登记时限',
};

export function shipmentSlaBucketLabel(bucket: string | undefined): string {
  if (!bucket) return shipmentSlaBucketLabels.UNSET as string;
  return shipmentSlaBucketLabels[bucket] ?? bucket;
}

/** 时限桶的严重度：逾期 / 今天到期是红的，未登记时限是**提醒色**而不是红色。 */
export function slaBucketSeverity(bucket: string | undefined): 'danger' | 'warn' | 'normal' {
  if (bucket === 'OVERDUE' || bucket === 'DUE_TODAY') return 'danger';
  if (bucket === 'DUE_48H') return 'warn';
  return 'normal';
}

export const carrierChannelLabels: Record<string, string> = {
  PLATFORM_INTEGRATED: '平台面单',
  THIRD_PARTY: '第三方物流',
  SELF_PICKUP: '买家自提',
  POSTAL: '邮政',
  FREIGHT_FORWARDER: '货代',
};

export function carrierChannelLabel(code: string | undefined): string {
  if (!code) return '—';
  return carrierChannelLabels[code] ?? code;
}

export const shipmentBatchStatusLabels: Record<string, string> = {
  PENDING: '待执行',
  RUNNING: '执行中',
  SUCCEEDED: '全部成功',
  PARTIAL: '部分成功',
  FAILED: '全部失败',
  CANCELLED: '已取消',
};

export function shipmentBatchStatusLabel(status: string | undefined): string {
  if (!status) return '未知';
  return shipmentBatchStatusLabels[status] ?? status;
}

/**
 * 批量任务状态的分色。
 *
 * <p>PARTIAL 与 SUCCEEDED **必须**不同色：这一屏是交接现场的看板，
 * 「部分成功」显示成和「全部成功」一样的绿，等于把漏发的票藏起来。
 */
export function batchStatusSeverity(status: string | undefined): 'ok' | 'warn' | 'danger' | 'normal' {
  if (status === 'SUCCEEDED') return 'ok';
  if (status === 'PARTIAL' || status === 'RUNNING' || status === 'PENDING') return 'warn';
  if (status === 'FAILED') return 'danger';
  return 'normal';
}

export const shipmentBatchTaskTypeLabels: Record<string, string> = {
  WAYBILL_REQUEST: '批量申请运单号',
  LABEL_PRINT: '批量打单',
  HANDOVER: '批量交接',
};

export function shipmentBatchTaskTypeLabel(taskType: string | undefined): string {
  if (!taskType) return '未知';
  return shipmentBatchTaskTypeLabels[taskType] ?? taskType;
}

export const shipmentTaskItemStatusLabels: Record<string, string> = {
  PENDING: '待处理',
  SUCCEEDED: '成功',
  FAILED: '失败',
  SKIPPED: '跳过',
};

export function shipmentTaskItemStatusLabel(status: string | undefined): string {
  if (!status) return '未知';
  return shipmentTaskItemStatusLabels[status] ?? status;
}

export const shipmentWaybillStatusLabels: Record<string, string> = {
  REQUESTED: '已提交承运商',
  ISSUED: '已签发',
  FAILED: '被拒',
};

export function shipmentWaybillStatusLabel(status: string | undefined): string {
  if (!status) return '未知';
  return shipmentWaybillStatusLabels[status] ?? status;
}

/**
 * codegen 把 Spring 的枚举展开成**内联联合类型**而不是导出的类型别名
 * （见 contracts/trade.ts 的 `carrierChannel` / `slaBucket` / `status`），
 * 所以下面这几支用从行类型上取出来的成员类型，而不是自己再抄一份枚举。
 */
type UnitStatus = PendingShipmentUnitRow['status'];
type TaskStatus = ShipmentBatchTaskRow['status'];
type TaskType = ShipmentBatchTaskInput['taskType'];
type ItemStatus = ShipmentBatchTaskItemRow['status'];
type WaybillStatus = PendingShipmentWaybillRow['status'];
type SlaBucket = PendingShipmentUnitRow['slaBucket'];
type SlaSource = ShipmentSlaInput['source'];

export function pendingShipmentStatusLabelOf(status: UnitStatus | undefined): string {
  return pendingShipmentStatusLabel(status);
}

export function shipmentBatchStatusOf(status: TaskStatus | undefined): string {
  return shipmentBatchStatusLabel(status);
}

export function shipmentBatchTaskTypeOf(type: TaskType | undefined): string {
  return shipmentBatchTaskTypeLabel(type);
}

export function shipmentTaskItemStatusOf(status: ItemStatus | undefined): string {
  return shipmentTaskItemStatusLabel(status);
}

export function shipmentWaybillStatusOf(status: WaybillStatus | undefined): string {
  return shipmentWaybillStatusLabel(status);
}

export function shipmentSlaSourceLabel(source: string | undefined): string {
  if (source === 'MANUAL_ATTESTATION') return '人工登记';
  if (source === 'PLATFORM_FEED') return '平台回传';
  return '未登记';
}

export function shipmentSlaBucketOf(bucket: SlaBucket | undefined): string {
  return shipmentSlaBucketLabel(bucket);
}

export function shipmentSlaSourceOf(source: SlaSource | undefined): string {
  return shipmentSlaSourceLabel(source);
}

// ------------------------------------------------------------ 判定

/** 承运商为空 = 还没走 9.3.x 路由。它上板但**建不了批量任务**。 */
export function carrierMissing(unit: Pick<PendingShipmentUnitRow, 'carrierCode'>): boolean {
  return !unit.carrierCode || unit.carrierCode.trim() === '';
}

/**
 * 能不能把这个单元放进批量任务，以及不能的话**为什么**。
 *
 * <p>返回原因字符串而不是布尔，是为了让禁用按钮能直接显示原因 ——
 * 只灰掉按钮不给理由，现场只能挨个点开猜。
 */
export function taskEligibility(
  unit: PendingShipmentUnitRow,
): { eligible: boolean; reason: string } {
  if (unit.status === 'HANDED_OVER') {
    return { eligible: false, reason: '已交接，再交接一次等于同一批货发两次' };
  }
  if (unit.status === 'CANCELLED') return { eligible: false, reason: '已作废，退回仓库了' };
  if (carrierMissing(unit)) return { eligible: false, reason: '还没选承运商，先走 9.3.x 路由' };
  return { eligible: true, reason: '' };
}

/** 终态的任务不可重复执行（DB 触发器同口径），按钮据此禁用。 */
export function batchTaskFinished(task: Pick<ShipmentBatchTaskRow, 'status'>): boolean {
  return (
    task.status === 'SUCCEEDED' ||
    task.status === 'PARTIAL' ||
    task.status === 'FAILED' ||
    task.status === 'CANCELLED'
  );
}

/** 部分成功 / 全部失败都要把逐条原因摊开 —— 这是本模块最需要被看见的失败。 */
export function needsAttention(task: Pick<ShipmentBatchTaskRow, 'status'>): boolean {
  return task.status === 'PARTIAL' || task.status === 'FAILED';
}

export function failedItems(items: ShipmentBatchTaskItemRow[]): ShipmentBatchTaskItemRow[] {
  return items.filter((i) => i.status === 'FAILED' || i.status === 'SKIPPED');
}

// ------------------------------------------------------------ 分组看板派生

export interface PendingShipmentGroupView extends PendingShipmentGroup {
  /** 该组里承运商为空（还没选路由）的单元数：这类单元上板但建不了任务。 */
  attention: boolean;
}

/**
 * 给分组行加上「需要盯」的标记。
 *
 * <p>已逾期 / 今天到期 / 有未选承运商的组都值得先看，所以这里的判定不是纯样式，
 * 而是把「先处理哪个承运商」这件事直接摆在看板第一眼。
 */
export function groupViews(groups: PendingShipmentGroup[]): PendingShipmentGroupView[] {
  return groups.map((group) => ({
    ...group,
    attention: group.overdueCount > 0 || group.dueTodayCount > 0,
  }));
}

/** 看板汇总的纯文本（筛选器里显示当前口径）。 */
export function summaryText(summary: PendingShipmentSummary | undefined): string {
  if (!summary) return '';
  return [
    `待发货 ${summary.pendingUnits}`,
    `已交接 ${summary.handedOverUnits}`,
    `逾期 ${summary.overdueUnits}`,
    `今天到期 ${summary.dueTodayUnits}`,
    `未登记时限 ${summary.unsetSlaUnits}`,
  ].join(' · ');
}

// ------------------------------------------------------------ 呈现工具

export function weightText(kg: number | string | undefined | null): string {
  if (kg === undefined || kg === null || kg === '') return '—';
  return `${Number(kg).toFixed(3)} kg`;
}

export function mmText(mm: number | string | undefined | null): string {
  if (mm === undefined || mm === null || mm === '') return '—';
  return `${Number(mm).toFixed(0)} mm`;
}

export function timeText(value: string | null | undefined): string {
  if (!value) return '—';
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}

/** 下一步动作（后端算好，前端原样显示，不自己猜状态机）。 */
export function nextActionText(unit: PendingShipmentUnitRow): string {
  return unit.nextActions || '—';
}

/** 运单号还没签发时明确写「待回执」，不显示成空白。 */
export function waybillText(waybill: Pick<PendingShipmentWaybillRow, 'status' | 'waybillNo'>): string {
  if (waybill.status === 'ISSUED' && waybill.waybillNo) return waybill.waybillNo;
  if (waybill.status === 'FAILED') return '被拒（见逐条原因）';
  return '待承运商回执';
}

/** 把 ApiError 翻成人能看的一句话：下游不可用时说清是谁不可用。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message || `请求失败（${error.status}）`;
  if (error instanceof Error) return error.message;
  return '未知错误';
}

export type {
  PendingShipmentBoard,
  PendingShipmentCapabilities,
  PendingShipmentGroup,
  PendingShipmentSummary,
  PendingShipmentUnitDetail,
  PendingShipmentUnitPage,
  PendingShipmentUnitRow,
  ShipmentBatchTaskDetail,
  ShipmentBatchTaskItemRow,
  ShipmentBatchTaskPage,
  ShipmentBatchTaskRow,
  ShipmentSlaInput,
  ShipmentWaybillAckInput,
};