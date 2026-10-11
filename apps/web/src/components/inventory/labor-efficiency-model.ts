import type {
  LaborCapabilities,
  LaborEfficiencyRow,
  LaborRanking,
  LaborReport,
  LaborTrend,
  LaborWorkTask,
  LaborWorkTaskDetail,
  LaborWorkTaskPage,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 8.5.1.1 作业效率前端模型。
 *
 * <p>蓝图：「统计仓库人效：人均日拣货件数/单量、拣货行走距离、打包时效、复核准确率、差错率，
 * 按人按日统计。」输出：「人效报表 + 排名 + 趋势。」以及本模块被点名的红线：
 * 「**人效数据用于提成核算与排班，需明确口径避免争议**（ERP 人效报表是绩效争议高发区）。」
 *
 * <p>界面上最容易出错、本模块显式处理了几处：
 *
 * <ul>
 *   <li>**率与时效为 null 时显示「不可计算」而不是 0**。`null` 意味着「分母为 0 /
 *       没有可算的样本」，画成 0% 会被读成「复核全对」「时效为零」——
 *       真实含义是「今天没人复核」「这件事没有可算的时效」。这是本模块最容易出事的一处误显示。
 *   <li>**口径必须显示出来，不能只显示数字**。提成核算与绩效面谈时，用户需要看到的
 *       是「这个数怎么算出来的」，而不只是一个百分比。所以口径文本由后端随响应下发
 *       （{@code dayBasis} / {@code metricBasis} / {@code metricBasisNote}），
 *       界面原样展示，不在前端重写一遍。
 *   <li>**排名方向由指标决定**。差错率越低越好、准确率越高越好；把两个方向相反的指标
 *       塞进同一个榜再让人自己看，是绩效争议的标准起手式。方向由后端给出，
 *       界面据此决定箭头朝上还是朝下。
 *   <li>**差错 ≠ 异常**。差错进差错率，异常（缺货/设备/取消）不进。界面上两者分列，
 *       不让「今天异常 3 次」被读成「今天错了 3 次」。
 *   <li>**无作业的日子要显示为「当天无作业」而不是跳过**。趋势里跳过空白日，
 *       「停了两天」看起来就像「产量没变」。
 * </ul>
 */

export interface LaborTaskParams {
  status?: string | undefined;
  workType?: string | undefined;
  warehouseId?: string | undefined;
  operatorUserId?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface LaborReportParams {
  from?: string | undefined;
  to?: string | undefined;
  warehouseId?: string | undefined;
  workType?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface LaborRankingParams {
  from?: string | undefined;
  to?: string | undefined;
  warehouseId?: string | undefined;
  workType?: string | undefined;
  metric: string;
  size?: number | undefined;
}

export interface LaborTrendParams {
  from?: string | undefined;
  to?: string | undefined;
  operatorUserId?: string | undefined;
  warehouseId?: string | undefined;
  workType?: string | undefined;
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

const base = 'warehouse/labor';

export function fetchLaborCapabilities(): Promise<LaborCapabilities> {
  return api<LaborCapabilities>(`${base}/capabilities`);
}

export function fetchLaborTasks(params: LaborTaskParams): Promise<LaborWorkTaskPage> {
  return api<LaborWorkTaskPage>(`${base}/work-tasks${query(params)}`);
}

export function fetchLaborTask(id: string): Promise<LaborWorkTaskDetail> {
  return api<LaborWorkTaskDetail>(`${base}/work-tasks/${id}`);
}

export function fetchLaborReport(params: LaborReportParams): Promise<LaborReport> {
  return api<LaborReport>(`${base}/report${query(params)}`);
}

export function fetchLaborRanking(params: LaborRankingParams): Promise<LaborRanking> {
  return api<LaborRanking>(`${base}/ranking${query(params)}`);
}

export function fetchLaborTrend(params: LaborTrendParams): Promise<LaborTrend> {
  return api<LaborTrend>(`${base}/trend${query(params)}`);
}

export function createLaborTask(input: {
  workType: string;
  warehouseId: string;
  sourceModule: string;
  referenceNo?: string;
  orderCount?: number | null;
  lineCount?: number | null;
  pieceCount?: number | null;
  operatorUserId?: string | null;
  note?: string | null;
}): Promise<LaborWorkTask> {
  return api<LaborWorkTask>(`${base}/work-tasks`, 'POST', input);
}

export function assignLaborTask(id: string, operatorUserId: string, note?: string): Promise<LaborWorkTask> {
  return api<LaborWorkTask>(`${base}/work-tasks/${id}/assign`, 'POST', {
    operatorUserId,
    note: note ?? '',
  });
}

export function startLaborTask(id: string, note?: string): Promise<LaborWorkTask> {
  return api<LaborWorkTask>(`${base}/work-tasks/${id}/start`, 'POST', { note: note ?? null });
}

/** 完成即定稿：计量列在这一刻落定，之后数据库触发器冻结，所以界面上要提示不可反悔。 */
export function completeLaborTask(
  id: string,
  input: {
    orderCount?: number | null;
    lineCount?: number | null;
    pieceCount?: number | null;
    travelDistanceM?: number | null;
    errorPieceCount?: number | null;
    errorOrderCount?: number | null;
    note?: string | null;
  },
): Promise<LaborWorkTask> {
  return api<LaborWorkTask>(`${base}/work-tasks/${id}/complete`, 'POST', input);
}

export function reportLaborException(
  id: string,
  input: {
    exceptionCode: string;
    exceptionNote: string;
    orderCount?: number | null;
    lineCount?: number | null;
    pieceCount?: number | null;
    errorPieceCount?: number | null;
    errorOrderCount?: number | null;
  },
): Promise<LaborWorkTask> {
  return api<LaborWorkTask>(`${base}/work-tasks/${id}/exception`, 'POST', input);
}

// ------------------------------------------------------------ 标签

export const workTypeLabels: Record<string, string> = {
  PICKING: '拣货',
  PACKING: '打包',
  REVIEW: '复核',
};

export const taskStatusLabels: Record<string, string> = {
  PENDING: '待开工',
  RUNNING: '作业中',
  COMPLETED: '已完成',
  EXCEPTION: '异常终止',
};

export const exceptionLabels: Record<string, string> = {
  MISSING_STOCK: '缺货',
  EQUIPMENT_FAILURE: '设备故障',
  ORDER_CANCELLED: '单据取消',
  SAFETY_HOLD: '安全暂停',
  OTHER: '其他',
};

export const eventTypeLabels: Record<string, string> = {
  CREATED: '新建',
  ASSIGNED: '派工',
  STARTED: '开工',
  COMPLETED: '完成',
  EXCEPTION: '异常',
};

export const rankingMetricLabels: Record<string, string> = {
  PICKED_PIECES: '人均日拣货件数',
  PICKED_ORDERS: '人均日拣货单量',
  TRAVEL_DISTANCE: '人均日拣货行走距离',
  PACKING_SECONDS: '平均打包时效',
  REVIEW_ACCURACY: '复核准确率',
  ERROR_RATE_BY_PIECE: '差错率（按件数）',
  ERROR_RATE_BY_ORDER: '差错率（按单量）',
};

export const workTypeOptions = Object.entries(workTypeLabels).map(([value, label]) => ({ value, label }));

export const taskStatusOptions = Object.entries(taskStatusLabels).map(([value, label]) => ({ value, label }));

export const exceptionOptions = Object.entries(exceptionLabels).map(([value, label]) => ({ value, label }));

export const rankingMetricOptions = Object.entries(rankingMetricLabels).map(([value, label]) => ({ value, label }));

/** 任务状态色：待开工与作业中是「还没出结果」，异常终止要一眼看见。 */
export function taskStatusTone(status: string): 'ok' | 'warn' | 'bad' | 'neutral' {
  if (status === 'EXCEPTION') return 'bad';
  if (status === 'RUNNING') return 'warn';
  if (status === 'COMPLETED') return 'ok';
  return 'neutral';
}

// ------------------------------------------------------------ 展示

/** 数量格式化：null 显示「—」而不是 0（「不可计算」显示成 0 是最危险的误显示）。 */
export function qty(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 4 });
}

/**
 * 率的展示：**null 显示「不可计算（分母为 0）」而不是 0%**。
 *
 * <p>0% 会被读成「复核全对 / 零差错」，而分母为 0 的真实含义是「今天没人复核」。
 * 绩效争议里最贵的一次误显示就是这里。
 */
export function rateText(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '不可计算（分母为 0）';
  return `${rate.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })}%`;
}

/** 率的色调：null 是中性（不是「好」也不是「坏」）。 */
export function rateTone(rate: number | null | undefined): 'ok' | 'warn' | 'bad' | 'neutral' {
  if (rate === null || rate === undefined) return 'neutral';
  return rate === 0 ? 'warn' : 'bad';
}

/**
 * 时效展示：秒 → 人读的时分秒。null 显示「无时效数据」而不是 0 秒。
 *
 * <p>「没有可算的时效」（缺开工时刻、异常终止）与「时效为零」是两件事，
 * 显示上必须分开。
 */
export function durationText(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return '无时效数据';
  const total = Math.round(seconds);
  if (total < 60) return `${total} 秒`;
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const rest = total % 60;
  return hours > 0 ? `${hours} 小时 ${minutes} 分 ${rest} 秒` : `${minutes} 分 ${rest} 秒`;
}

/** 平均打包时效的短展示：报表表格里用，保留 1 位小数秒。 */
export function avgSecondsText(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return '不可计算（无样本）';
  return `${seconds.toLocaleString('zh-CN', { minimumFractionDigits: 1, maximumFractionDigits: 1 })} 秒`;
}

/** 距离（米）展示：走得更少不一定更好，所以不带上好坏判断。 */
export function distanceText(meters: number | null | undefined): string {
  if (meters === null || meters === undefined) return '—';
  return `${meters.toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 3 })} 米`;
}

export function formatMoment(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

export function formatDay(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleDateString('zh-CN');
}

/** 今天的日期串（yyyy-MM-dd），给日期输入框的默认值。 */
export function todayInput(now: Date = new Date()): string {
  const offset = now.getTimezoneOffset() * 60000;
  return new Date(now.getTime() - offset).toISOString().slice(0, 10);
}

/** 默认区间：最近 14 天（与后端的缺省一致，界面上显式给出而不是让用户猜）。 */
export function defaultRange(now: Date = new Date()): { from: string; to: string } {
  const to = todayInput(now);
  const fromDate = new Date(`${to}T00:00:00`);
  fromDate.setDate(fromDate.getDate() - 13);
  return { from: todayInput(fromDate), to };
}

/** 区间描述：把这个区间算出来的天数与日期说清楚，让用户知道自己看的是哪一段。 */
export function rangeText(from: string, to: string): string {
  const start = new Date(`${from}T00:00:00`);
  const end = new Date(`${to}T00:00:00`);
  const days = Math.round((end.getTime() - start.getTime()) / 86400000) + 1;
  return `${from} 至 ${to}（共 ${days} 天）`;
}

/**
 * 趋势里「当天没有作业」的判定。
 *
 * <p>不是 0 件而是「没有作业」：空白日与「做了一天但产出 0」是两件事，
 * 前者是排班问题，后者是效率问题。
 */
export function isIdleDay(point: { taskCount: number; pickedPieces: number }): boolean {
  return point.taskCount === 0 && point.pickedPieces === 0;
}

/** 趋势点的一句话：没作业的日子说出来，而不是让它看起来像产量没变。 */
export function trendPointText(point: {
  workDay: string;
  taskCount: number;
  activeOperators: number;
  pickedPieces: number;
}): string {
  if (isIdleDay(point)) return `${point.workDay}：当天无作业`;
  return `${point.workDay}：${point.taskCount} 张任务 / ${point.activeOperators} 人 / 拣货 ${point.pickedPieces} 件`;
}

/** 排名行的展示值：按指标方向给出箭头，避免「越高越好」的指标被当成绩效倒挂。 */
export function metricValueText(value: number | null | undefined, higherIsBetter: boolean, isRate: boolean): string {
  if (value === null || value === undefined) return '不可计算';
  const arrow = higherIsBetter ? '↑' : '↓';
  return isRate ? `${rateText(value)} ${arrow}` : `${qty(value)} ${arrow}`;
}

/** 数值列是否按「越大越好」排序（排名页表头用）。 */
export function metricIsRate(metric: string): boolean {
  return metric === 'REVIEW_ACCURACY' || metric === 'ERROR_RATE_BY_PIECE' || metric === 'ERROR_RATE_BY_ORDER';
}

// ------------------------------------------------------------ 校验

/** 派工校验：必须选操作人，否则这张任务算不出人效。 */
export function createTaskProblem(input: {
  workType: string;
  warehouseId: string;
  sourceModule: string;
}): string | null {
  if (!input.workType) return '必须选择作业类型：拣货 / 打包 / 复核，五个指标各自对应其中一类';
  if (!input.warehouseId) return '必须选择作业仓库：人效分母是「人 × 日」，没有仓库就无法按仓切口径';
  if (!input.sourceModule.trim()) return '必须填来源模块号：报表数字起争议时第一个要回答的是「这批数据从哪来」';
  return null;
}

/** 完成校验：与后端同一条纪律，界面上先拦住，用户就不用等一个 422。 */
export function completeTaskProblem(input: {
  workType: string;
  orderCount: number;
  pieceCount: number;
  travelDistanceM: number | null;
  errorPieceCount: number;
  errorOrderCount: number;
}): string | null {
  if (input.pieceCount < 0) return '作业件数不能为负数';
  if (input.errorPieceCount > input.pieceCount) {
    return '差错件数不能超过作业件数：超过它意味着口径错了，而不是这个人错得离谱';
  }
  if (input.errorOrderCount > input.orderCount) return '差错单量不能超过涉及单量';
  if (input.travelDistanceM !== null && input.workType !== 'PICKING') {
    return '行走距离只属于拣货作业：填到打包/复核上一定会算进人效报表的行走距离';
  }
  if (input.travelDistanceM !== null && input.travelDistanceM < 0) return '拣货行走距离不能为负数';
  return null;
}

/** 异常校验：原因至少 4 个字 —— 异常复盘要靠它决定该改排班、补货还是修设备。 */
export function exceptionProblem(exceptionCode: string, exceptionNote: string): string | null {
  if (!exceptionCode) return '必须选择异常类型：缺货 / 设备故障 / 单据取消 / 安全暂停 / 其他';
  const text = exceptionNote.trim();
  if (text === '') return '必须写清异常原因：复盘时要靠它决定该改什么';
  if (text.length < 4) return '异常原因至少 4 个字：「缺」「停」这样写等于没写';
  if (text.length > 500) return '异常原因超过 500 字，请写结论，不要整段粘贴';
  return null;
}

/** 统一错误文案。409（状态冲突）与 403（无权限）要分开说，混在一起用户不知道该找谁。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return '当前角色没有此操作权限（需要 warehouse.inventory.update）';
    if (error.status === 404) return '找不到该对象（可能不属于当前租户）';
    if (error.status === 409) return error.message || '状态冲突：这一步现在还不能做';
    return error.message || `请求失败（${error.status}）`;
  }
  if (error instanceof Error) return error.message;
  return '未知错误';
}

/** 报表行里「这个人这一天做了什么」的一句话，用于窄屏与无障碍标签。 */
export function rowSummary(row: LaborEfficiencyRow): string {
  const pieces = `${row.pickedPieces} 件`;
  const orders = `${row.pickedOrders} 单`;
  return `${row.taskCount} 张任务：拣货 ${pieces} / ${orders}`;
}