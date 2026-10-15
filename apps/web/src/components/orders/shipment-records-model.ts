import type {
  ShipmentCallbackInput,
  ShipmentPerformanceReport,
  ShipmentRecordBoard,
  ShipmentRecordCapabilities,
  ShipmentRecordDetail,
  ShipmentRecordEventPage,
  ShipmentRecordPage,
  ShipmentRecordSummary,
  ShipmentRecordSyncInput,
  ShipmentRecordSyncResult,
  ShipmentStatusInput,
  ShipmentTrackingObservationInput,
  ShipmentVisibilityInput,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 9.4.1.2 发货记录前端模型。
 *
 * <p>蓝图：「查询已发货记录：包裹、运单号、承运商、发货时间、轨迹状态、买家可见状态、
 * 平台回传状态。」输入是「筛选条件；发货事件」，输出是「发货记录列表 + 明细」。
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>未登记 ≠ 正常。</b>轨迹、买家可见、平台回传三列在当前集成头里
 *       <b>都没有可读上游</b>（10.3 / 2.5 / 9.4.2.1 均未交付）。前端的 UNSET
 *       渲染成灰底「未登记」，<b>绝不</b>渲染成「正常」或留空 —— 留空会被读成
 *       「没问题」，而真实情况是这一列还没有数据源。</li>
 *   <li><b>「跳过」与「漏了」分开。</b>平台回传的 SKIPPED（决定不回传）与 UNSET
 *       （压根没登记）在标签、配色、明细措辞上都是两套说法。合并会让「漏回传」
 *       伪装成「按策略不回传」，而这两件事在平台处罚上完全不同。</li>
 *   <li><b>迟发标记只在有时限时出现。</b>没登记时限的记录**不是**准时，而是
 *       「无从判定」—— 前端不许给��种记录打「准时」标签。</li>
 *   <li><b>分组值不是页内统计。</b>三个分组维度都来自后端 GROUP BY，与翻页无关。</li>
 *   <li><b>已发货 ≠ 已交接。</b>待发货看板最远到交接；本模块的每一条都来自
 *       8.3.4.1 已确认交接的交接单，界面上不出现「待发货」字样，避免两边混淆。</li>
 * </ul>
 */

const base = 'trade/fulfillment/shipment-records';

// ------------------------------------------------------------ 参数

export interface ShipmentRecordListParams {
  carrierCode?: string;
  status?: string;
  trackingStatus?: string;
  platformCallbackStatus?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export interface ShipmentRecordBoardParams {
  carrierCode?: string;
  status?: string;
  trackingStatus?: string;
  platformCallbackStatus?: string;
  groupBy?: string;
}

export interface ShipmentRecordEventParams {
  eventKind?: string;
  recordId?: string;
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

export function fetchShipmentRecordCapabilities(): Promise<ShipmentRecordCapabilities> {
  return api<ShipmentRecordCapabilities>(`${base}/capabilities`);
}

/** 分组看板。三个维度由后端 GROUP BY 出，不受列表分页影响。 */
export function fetchShipmentRecordBoard(
  params: ShipmentRecordBoardParams,
): Promise<ShipmentRecordBoard> {
  return api<ShipmentRecordBoard>(`${base}/board${query(params)}`);
}

export function fetchShipmentRecords(params: ShipmentRecordListParams): Promise<ShipmentRecordPage> {
  return api<ShipmentRecordPage>(`${base}/records${query(params)}`);
}

export function fetchShipmentRecord(recordId: string): Promise<ShipmentRecordDetail> {
  return api<ShipmentRecordDetail>(`${base}/records/${recordId}`);
}

/** 绩效口径：迟发率与有效追踪率。定义在服务端一处，前端只读不自算。 */
export function fetchShipmentPerformance(
  params: Omit<ShipmentRecordListParams, 'sort' | 'page' | 'size'>,
): Promise<ShipmentPerformanceReport> {
  return api<ShipmentPerformanceReport>(`${base}/performance${query(params)}`);
}

export function fetchShipmentRecordEvents(
  params: ShipmentRecordEventParams,
): Promise<ShipmentRecordEventPage> {
  return api<ShipmentRecordEventPage>(`${base}/events${query(params)}`);
}

// ------------------------------------------------------------ 写

/** 从 8.3.4.1 的授权只读接口同步已确认交接的交接单（幂等投影）。 */
export function syncShipmentRecords(
  input: ShipmentRecordSyncInput,
): Promise<ShipmentRecordSyncResult> {
  return api<ShipmentRecordSyncResult>(`${base}/sync`, 'POST', input);
}

/** 登记轨迹观测 —— 10.3 未交付，本模块只登记不抓取。 */
export function registerTrackingObservation(
  recordId: string,
  input: ShipmentTrackingObservationInput,
): Promise<ShipmentRecordDetail> {
  return api<ShipmentRecordDetail>(`${base}/records/${recordId}/tracking`, 'POST', input);
}

/** 登记买家可见状态 —— 没有可读上游，所以登记。 */
export function registerBuyerVisibility(
  recordId: string,
  input: ShipmentVisibilityInput,
): Promise<ShipmentRecordDetail> {
  return api<ShipmentRecordDetail>(`${base}/records/${recordId}/visibility`, 'POST', input);
}

/** 登记平台回传状态 —— 9.4.2.1 / 2.5.1.1 未交付；SKIPPED 必须带原因。 */
export function registerPlatformCallback(
  recordId: string,
  input: ShipmentCallbackInput,
): Promise<ShipmentRecordDetail> {
  return api<ShipmentRecordDetail>(`${base}/records/${recordId}/callbacks`, 'POST', input);
}

/** 推进 / 更正记录状态。终态（DELIVERED / RETURNED / VOID）不可再动。 */
export function changeShipmentRecordStatus(
  recordId: string,
  input: ShipmentStatusInput,
): Promise<ShipmentRecordDetail> {
  return api<ShipmentRecordDetail>(`${base}/records/${recordId}/status`, 'POST', input);
}

// ------------------------------------------------------------ 呈现口径

export const shipmentRecordStatusLabels: Record<string, string> = {
  SHIPPED: '已发货',
  IN_TRANSIT: '在途',
  DELIVERED: '已妥投',
  RETURNED: '已退回',
  VOID: '已作废',
};

export function shipmentRecordStatusLabel(status: string | undefined): string {
  if (!status) return '未知';
  return shipmentRecordStatusLabels[status] ?? status;
}

export const trackingStatusLabels: Record<string, string> = {
  UNSET: '未登记轨迹',
  NO_TRACE: '承运商无轨迹',
  PICKED_UP: '已揽收',
  IN_TRANSIT: '运输中',
  CUSTOMS: '清关中',
  OUT_FOR_DELIVERY: '派送中',
  DELIVERED: '已送达',
  EXCEPTION: '轨迹异常',
  RETURNED: '已退回',
};

export function trackingStatusLabel(status: string | undefined): string {
  if (!status) return trackingStatusLabels.UNSET as string;
  return trackingStatusLabels[status] ?? status;
}

/**
 * 轨迹状态的严重度。
 *
 * <p>{@code UNSET}（含 undefined —— 字段缺失就是「没登记」）是**中性灰**而不是
 * 「正常」色：它不是好消息，是「还没有数据源」。把它涂成绿色，运营就会以为
 * 这一票的物流没问题，而真实情况是没人报过。
 */
export function trackingSeverity(status: string | undefined): 'danger' | 'warn' | 'muted' | 'normal' {
  if (status === 'EXCEPTION') return 'danger';
  if (status === 'RETURNED') return 'danger';
  if (status === 'NO_TRACE') return 'warn';
  // undefined 与 UNSET 同义：字段缺失就是「没登记」，不许退化成正常绿。
  if (status === undefined || status === 'UNSET' || status === '') return 'muted';
  return 'normal';
}

export const buyerVisibleLabels: Record<string, string> = {
  UNSET: '未登记',
  VISIBLE: '买家可见',
  HIDDEN: '对买家隐藏',
  PUBLISHED: '已向买家发布',
};

export function buyerVisibleLabel(status: string | undefined): string {
  if (!status) return buyerVisibleLabels.UNSET as string;
  return buyerVisibleLabels[status] ?? status;
}

export const platformCallbackLabels: Record<string, string> = {
  UNSET: '未登记',
  PENDING: '待回传',
  SUBMITTED: '已提交平台',
  ACKNOWLEDGED: '平台已受理',
  REJECTED: '平台拒绝',
  SKIPPED: '决定不回传',
};

export function platformCallbackLabel(status: string | undefined): string {
  if (!status) return platformCallbackLabels.UNSET as string;
  return platformCallbackLabels[status] ?? status;
}

/**
 * 平台回传状态的严重度。
 *
 * <p>刻意让 UNSET 与 SKIPPED **不同色**：UNSET 是「漏了」（提醒色），
 * SKIPPED 是「有意不回传」（中性）。两者都变成同一种灰，就再也分不出
 * 「我们决定不回传」和「我们忘了回传」。
 */
export function callbackSeverity(
  status: string | undefined,
): 'danger' | 'warn' | 'muted' | 'normal' {
  if (status === 'REJECTED') return 'danger';
  // undefined 与 UNSET 同义：字段缺失就是「压根没登记」= 漏了。
  if (status === undefined || status === 'UNSET' || status === '') return 'warn';
  if (status === 'PENDING') return 'warn';
  if (status === 'SKIPPED') return 'muted';
  return 'normal';
}

/**
 * 「跳过了」与「漏了」是不是同一件事 —— 供明细页给出明确措辞。
 *
 * <p>这个判断刻意写成一个可测的纯函数：它是本模块最容易被前端悄悄糊掉的一条，
 * 一旦糊掉，界面就会把「漏回传」画成「按策略不回传」。
 */
export function callbackGapNote(status: string | undefined, note: string | undefined): string | null {
  if (status === 'UNSET') {
    return '尚未登记平台回传状态（9.4.2.1 / 2.5.1.1 未交付）——这是「漏了」，与「决定不回传」不同';
  }
  if (status === 'SKIPPED') {
    return note
      ? `决定不回传，原因：${note}`
      : '决定不回传（未填原因，服务端会拒绝这种登记）';
  }
  return null;
}

/** 没登记时限时**不给**「准时」标签 —— 无从判定不是准时。 */
export function slaVerdict(
  slaDeadlineAt: string | undefined,
  lateAgainstSla: boolean | undefined,
): { label: string; severity: 'danger' | 'warn' | 'muted' | 'normal' } {
  if (!slaDeadlineAt) {
    return { label: '未登记时限 · 无从判定', severity: 'muted' };
  }
  if (lateAgainstSla) return { label: '迟发', severity: 'danger' };
  return { label: '准时', severity: 'normal' };
}

/** 绩效比例的呈现。分母为 0 时后端给 0.0000，前端如实显示 0% 而不是「—」。 */
export function percentText(rate: number | undefined): string {
  if (rate === undefined || rate === null) return '—';
  return `${(rate * 100).toFixed(2)}%`;
}

/** 有效追踪率低于多少算需要盯：低于 0.8 时提醒。 */
export function trackingRateSeverity(rate: number | undefined): 'danger' | 'warn' | 'normal' {
  if (rate === undefined) return 'normal';
  if (rate < 0.5) return 'danger';
  if (rate < 0.8) return 'warn';
  return 'normal';
}

/** 记录状态推进按钮：终态没有「下一步」，也不该给一个点了就报错的按钮。 */
export function nextStatuses(status: string | undefined): string[] {
  switch (status) {
    case 'SHIPPED':
      return ['IN_TRANSIT', 'RETURNED', 'VOID'];
    case 'IN_TRANSIT':
      return ['DELIVERED', 'RETURNED', 'VOID'];
    default:
      return [];
  }
}

/** 终态不再接受任何登记（服务端也会拒，这里先不让用户点）。 */
export function isTerminal(status: string | undefined): boolean {
  return status === 'DELIVERED' || status === 'RETURNED' || status === 'VOID';
}

export interface ShipmentRecordSummaryTile {
  label: string;
  value: string;
  severity: 'danger' | 'warn' | 'muted' | 'normal';
  /** 为什么这一格是「提醒」而不是「正常」—— 悬停与副标题都用它。 */
  hint: string;
}

/**
 * 汇总卡：把「未登记」的三列各自成卡，不并成一个「正常」。
 *
 * <p>字段名与 {@code StatCard} 的 label / value 对齐，好让工作区直接铺而不必再映射一遍。
 */
export function summaryTiles(summary: ShipmentRecordSummary): ShipmentRecordSummaryTile[] {
  return [
    {
      label: '发货记录',
      value: String(summary.totalRecords),
      severity: 'normal',
      hint: '含已作废；作废不计入绩效分母',
    },
    {
      label: '未登记轨迹',
      value: String(summary.unsetTrackingRecords),
      severity: summary.unsetTrackingRecords > 0 ? 'muted' : 'normal',
      hint: '10.3 未交付：无轨迹数据源，不是「轨迹正常」',
    },
    {
      label: '轨迹异常',
      value: String(summary.exceptionTrackingRecords),
      severity: summary.exceptionTrackingRecords > 0 ? 'danger' : 'normal',
      hint: '需要人工处置',
    },
    {
      label: '未登记回传',
      value: String(summary.unsetCallbackRecords),
      severity: summary.unsetCallbackRecords > 0 ? 'warn' : 'normal',
      hint: '这是「漏了」，与「决定不回传」分开',
    },
    {
      label: '决定不回传',
      value: String(summary.skippedCallbackRecords),
      severity: 'muted',
      hint: '有意跳过（必须写原因），与「漏了」不是一回事',
    },
    {
      label: '平台拒绝',
      value: String(summary.rejectedCallbackRecords),
      severity: summary.rejectedCallbackRecords > 0 ? 'danger' : 'normal',
      hint: '需要改单重传',
    },
    {
      label: '未登记买家可见',
      value: String(summary.unsetVisibilityRecords),
      severity: summary.unsetVisibilityRecords > 0 ? 'muted' : 'normal',
      hint: '无可读上游',
    },
    {
      label: '未登记发货时限',
      value: String(summary.noSlaRecords),
      severity: summary.noSlaRecords > 0 ? 'muted' : 'normal',
      hint: '迟发率里落 UNSET 桶，既不算准时也不算迟发',
    },
  ];
}

/** 错误信息统一出口：网络失败与业务错误都要说清「为什么」。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '请求发货记录失败，请稍后重试';
}
