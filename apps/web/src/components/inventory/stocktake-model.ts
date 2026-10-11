import type {
  StocktakeDetail,
  StocktakeLine,
  StocktakeLinePage,
  StocktakeSaved,
  StocktakeScopeCapability,
  StocktakeSheet,
  StocktakeSheetPage,
  StocktakeTask,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 7.4.2.1 盘点单前端模型。
 *
 * <p>界面上最容易出错、这个模块都显式处理了几处：
 *
 * <ul>
 *   <li>**账面数"对不上"是正常的，不能报警**。冻结之后仓库照常收发，所以账面合计与今天的
 *       库存余额本来就不一样。这一列回答的是「冻结那一刻有多少」，拿它去和当前余额比，
 *       会把每一张盘点单都误报成异常。
 *   <li>**未录入不能画成差异 0**。`countedQty` / `variance` 为 null 是「还没盘」，
 *       显示成 0 会让盘点员以为已经盘完且账实相符 —— 这是最危险的一种误显示。
 *   <li>**差异的符号要讲人话**。正数是盘盈、负数是盘亏，只显示 `-5` 没人敢确认方向。
 *   <li>**ABC 分类不能凭空消失**。它不可用的原因要能展示给用户，而不是让选项不见了。
 * </ul>
 */

// ------------------------------------------------------------ 接口

export interface StocktakeListParams {
  status?: string;
  scopeType?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface StocktakeLineParams {
  filter?: string;
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

export function fetchStocktakes(params: StocktakeListParams): Promise<StocktakeSheetPage> {
  return api<StocktakeSheetPage>(`warehouse/inventory/stocktakes${query(params)}`);
}

export function fetchStocktake(id: string): Promise<StocktakeDetail> {
  return api<StocktakeDetail>(`warehouse/inventory/stocktakes/${id}`);
}

export function fetchStocktakeCapabilities(): Promise<StocktakeScopeCapability[]> {
  return api<StocktakeScopeCapability[]>('warehouse/inventory/stocktakes/capabilities');
}

export function fetchStocktakeLines(
  id: string,
  params: StocktakeLineParams,
): Promise<StocktakeLinePage> {
  return api<StocktakeLinePage>(`warehouse/inventory/stocktakes/${id}/lines${query(params)}`);
}

export function fetchStocktakeTasks(id: string, onlyOpen?: boolean): Promise<StocktakeTask[]> {
  return api<StocktakeTask[]>(`warehouse/inventory/stocktakes/${id}/tasks${query({ onlyOpen })}`);
}

export function createStocktake(input: {
  scopeType: string;
  scopeKey: string;
  taskUnit: string;
  note?: string;
}): Promise<StocktakeSaved> {
  return api<StocktakeSaved>('warehouse/inventory/stocktakes', 'POST', input);
}

/** 录入实盘数。传 null 即撤销录入（复盘的前置动作）。 */
export function countStocktakeLine(
  sheetId: string,
  lineId: string,
  input: { countedQty: number | null; barcode?: string; note?: string },
): Promise<StocktakeLine> {
  return api<StocktakeLine>(
    `warehouse/inventory/stocktakes/${sheetId}/lines/${lineId}/count`,
    'POST',
    input,
  );
}

export function assignStocktakeTask(
  sheetId: string,
  taskId: string,
  assigneeUserId: string | null,
): Promise<StocktakeTask> {
  return api<StocktakeTask>(
    `warehouse/inventory/stocktakes/${sheetId}/tasks/${taskId}/assign`,
    'POST',
    {
      assigneeUserId,
    },
  );
}

// ------------------------------------------------------------ 标签

export const sheetStatusLabels: Record<string, string> = {
  OPEN: '待录入',
  COUNTING: '录入中',
  COUNTED: '已录完',
};

export const taskStatusLabels: Record<string, string> = {
  PENDING: '未开始',
  COUNTING: '盘点中',
  DONE: '已完成',
};

export const lineFilterLabels: Record<string, string> = {
  ALL: '全部',
  PENDING: '未录入',
  MATCH: '账实相符',
  GAIN: '盘盈',
  LOSS: '盘亏',
};

export const taskUnitLabels: Record<string, string> = {
  BY_LOCATION: '按库位',
  BY_SKU: '按 SKU',
};

export const lineFilterOptions = Object.entries(lineFilterLabels).map(([value, label]) => ({
  value,
  label,
}));

export const taskUnitOptions = Object.entries(taskUnitLabels).map(([value, label]) => ({
  value,
  label,
}));

// ------------------------------------------------------------ 展示

/**
 * 数量格式化：null 显示「—」而不是 0。
 *
 * <p>「未录入」显示成 0 是这个模块最危险的误显示 —— 它同时说错了两件事
 * （盘过了、账实相符）。破折号把「还没盘」如实说出来。
 */
export function qty(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 4 });
}

/** 账面数永远有值（冻结下来的），所以不走 qty 的 null 分支。 */
export function bookText(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : qty(value);
}

/**
 * 差异文案：带方向与性质。
 *
 * <p>{@code -5} 显示成「盘亏 5」而不是「−5」：盘点差异是要走差异处理、可能追责的数，
 * 只给一个带符号的数字，录入的人不敢确认自己填对了没有。
 */
export function varianceText(variance: number | null | undefined): string {
  if (variance === null || variance === undefined) return '未录入';
  if (variance > 0) return `盘盈 ${qty(variance)}`;
  if (variance < 0) return `盘亏 ${qty(Math.abs(variance))}`;
  return '账实相符';
}

/** 差异列的语义色：盘亏最重，盘盈其次，相符最轻。 */
export function varianceTone(variance: number | null | undefined): 'ok' | 'warn' | 'bad' {
  if (variance === null || variance === undefined) return 'ok';
  if (variance < 0) return 'bad';
  if (variance > 0) return 'warn';
  return 'ok';
}

/**
 * 单据状态色：已录完是绿的，录入中是黄的，待录入是中性。
 *
 * <p>状态码在契约里是内联联合类型（没有单独导出的 type），所以这里收字符串：
 * 万一后端加了第四个状态，未知值会落到中性而不是渲染出错。
 */
export function sheetTone(status: string): 'ok' | 'warn' | 'bad' {
  if (status === 'COUNTING') return 'warn';
  return 'ok';
}

/** 录入进度「已录 / 总量」；总量为 0 时不显示成 100%。 */
export function progressText(counted: number, total: number): string {
  if (total <= 0) return '0 / 0';
  return `${counted} / ${total}`;
}

/** 进度百分比；总量为 0 时返回 0 而不是除零得到 NaN。 */
export function progressPercent(counted: number, total: number): number {
  if (total <= 0) return 0;
  return Math.min(100, Math.round((counted / total) * 100));
}

/** 冻结时刻格式化。解析失败时原样返回，不让一个坏时间戳把整页打崩。 */
export function formatMoment(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/**
 * 可用的盘点范围（只看已接入的）。
 *
 * <p>不可用的范围**不隐藏**而是禁用并保留原因：`/capabilities` 已经如实说明
 * ABC 分类库里没有字段、类目投影未接入。让选项凭空消失只会让用户以为系统没这个功能。
 */
export function availableScopes(
  capabilities: StocktakeScopeCapability[],
): StocktakeScopeCapability[] {
  return capabilities.filter((c) => c.scopeType !== 'ABC' && c.available);
}

/** 不可用范围的说明文字，用于在表单下方如实告诉用户为什么。 */
export function unavailableNote(
  capabilities: StocktakeScopeCapability[],
  scopeType: string,
): string | null {
  return capabilities.find((c) => c.scopeType === scopeType && !c.available)?.note ?? null;
}

/**
 * 输入校验：实盘数只能是非负数。
 *
 * <p>返回错误文案而不是静默纠正：盘出负数没有物理含义，它是录入错误，
 * 悄悄改成 0 会让盘点员以为自己记的是「零」。
 */
export function countedQtyProblem(raw: string): string | null {
  const text = raw.trim();
  if (text === '') return '实盘数不能为空（清空输入框请用「撤销录入」）';
  const value = Number(text);
  if (!Number.isFinite(value)) return '实盘数必须是数字';
  if (value < 0) return '实盘数不能为负：盘出负数没有物理含义，它是录入错误';
  if (value > 1e9) return '实盘数超出可信范围，请确认是否多打了一位数';
  return null;
}

/** 实盘数输入串 → 提交值；校验不通过时返回 null（调用方此时应已展示 problem）。 */
export function toCountedQty(raw: string): number | null {
  if (countedQtyProblem(raw) !== null) return null;
  return Number(raw.trim());
}

/** 统一错误文案。422（依赖未接入）单独处理，不与「你填错了」混成一类。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 422) return error.message;
    if (error.status === 403) return '当前角色没有此操作权限';
    if (error.status === 404) return '找不到该盘点单（可能不属于当前租户）';
    return error.message || `请求失败（${error.status}）`;
  }
  if (error instanceof Error) return error.message;
  return '未知错误';
}
