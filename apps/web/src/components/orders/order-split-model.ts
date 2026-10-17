import type {
  CandidateRow,
  OrderSplitCapabilities,
  SplitReservationCommand,
  SplitReservationInput,
  SplitReservationPage,
  SplitDetail,
  SplitInput,
  SplitPage,
  SplitPreview,
  SplitRow,
  SplitRule,
  SplitRuleInput,
  SplitSaved,
  SplitStatusInput,
  SplitStockSnapshotInput,
  SplitStockSnapshotRow,
  UnitLine,
  SplitWarehouseInput,
  SplitWarehouseRow,
  SplitWritebackInput,
  SplitWritebackPage,
  SplitWritebackTask,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 9.3.1.1 拆单前端模型。
 *
 * <p>蓝图：「把一个订单拆成多个履约单元：按仓库拆、按承运商拆、按发货时效拆（如部分现货先发）、
 * 按平台限制拆。」输出是「多个履约请求 + 库存预占指令 + 发货回传按拆分执行」。
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>🔴 金额守恒要一眼看得见。</b>后端把「各单元分摊合计 + 尾差 == 订单总额」
 *       做成 DB 延迟约束触发器，每次落拆单都在提交时独立复核。界面上仍然把它显式画出来：
 *       分摊 + 尾差 = 订单总额，三行摆在一起，让人能自己验算，而不是只相信一个绿勾。</li>
 *   <li><b>🔴 尾差必须显示，不许四舍五入掉。</b>分摊按 HALF_UP 到 4 位会产生几分钱的尾差，
 *       它落在某一行上（{@code residualAmount}）。界面上不显示它，就等于让每一行都「差一点点」
 *       而没人知道差在哪 —— 那比不给分摊更糟。</li>
 *   <li><b>🔴 「不预占上游库存」必须写在页面上。</b>后端 capabilities 里
 *       {@code reservesUpstreamStock=false} 是如实报出的：预占住在 warehouse 独立库，
 *       本模块只登记指令。运营在这里看到「已预占」三个字就会以为货被锁了，
 *       而实际上 warehouse 侧一条预占记录都还没有。</li>
 *   <li><b>🔴 「审单未接通」也要写出来。</b>9.2 审单整条线未交付，本模块不校验审单结论，
 *       拆的是不是一张过了审的单，**本模块答不了**。</li>
 *   <li><b>落选候选与被选候选同等重要。</b>「为什么这批货没从上海仓发」是这一页最常被问的问题，
 *       所以后端逐条返回了原因码，界面必须原样显示而不是过滤掉。</li>
 *   <li><b>金额与数量不再二次四舍五入。</b>后端已按 numeric(19,4) / numeric(19,6) 落库并实算汇总，
 *       前端只做展示格式化，不再改值，否则界面上会比账面多出或少掉一分钱。</li>
 * </ul>
 */

const base = 'trade/fulfillment/split';

// ------------------------------------------------------------ 参数

export interface SplitListParams {
  keyword?: string;
  status?: string;
  strategy?: string;
  orderRef?: string;
  warehouseRef?: string;
  page?: number;
  size?: number;
}

export interface ReservationListParams {
  splitId?: string;
  unitId?: string;
  status?: string;
  skuRef?: string;
  page?: number;
  size?: number;
}

export interface WritebackListParams {
  splitId?: string;
  platformCode?: string;
  status?: string;
  platformOrderRef?: string;
  page?: number;
  size?: number;
}

function query(params: Record<string, unknown>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

// ------------------------------------------------------------ 能力与规则

export function fetchCapabilities(): Promise<OrderSplitCapabilities> {
  return api<OrderSplitCapabilities>(`${base}/capabilities`);
}

export function fetchRule(): Promise<SplitRule> {
  return api<SplitRule>(`${base}/rule`);
}

export function saveRule(input: SplitRuleInput): Promise<SplitRule> {
  return api<SplitRule>(`${base}/rule`, 'PUT', input);
}

// ------------------------------------------------------------ 仓与库存分布

export function fetchWarehouses(activeOnly = true): Promise<SplitWarehouseRow[]> {
  return api<SplitWarehouseRow[]>(`${base}/warehouses${query({ activeOnly })}`);
}

export function saveWarehouse(input: SplitWarehouseInput): Promise<SplitWarehouseRow> {
  return api<SplitWarehouseRow>(`${base}/warehouses`, 'POST', input);
}

export function fetchStockSnapshots(
  warehouseRef?: string,
  skuRef?: string,
): Promise<SplitStockSnapshotRow[]> {
  return api<SplitStockSnapshotRow[]>(`${base}/stock-snapshots${query({ warehouseRef, skuRef })}`);
}

export function saveStockSnapshot(input: SplitStockSnapshotInput): Promise<SplitStockSnapshotRow> {
  return api<SplitStockSnapshotRow>(`${base}/stock-snapshots`, 'POST', input);
}

// ------------------------------------------------------------ 拆单

/** 拆单预览（无副作用）。与落库走同一段判定逻辑，所以两边结果必然一致。 */
export function previewSplit(input: SplitInput): Promise<SplitPreview> {
  return api<SplitPreview>(`${base}/preview`, 'POST', input);
}

export function fetchSplits(params: SplitListParams): Promise<SplitPage> {
  return api<SplitPage>(`${base}/splits${query(params as Record<string, unknown>)}`);
}

export function createSplit(input: SplitInput): Promise<SplitSaved> {
  return api<SplitSaved>(`${base}/splits`, 'POST', input);
}

export function fetchSplit(splitId: string): Promise<SplitDetail> {
  return api<SplitDetail>(`${base}/splits/${splitId}`);
}

export function advanceSplitStatus(
  splitId: string,
  input: SplitStatusInput,
): Promise<SplitSaved> {
  return api<SplitSaved>(`${base}/splits/${splitId}/status`, 'POST', input);
}

// ------------------------------------------------------------ 预占指令

export function fetchReservationCommands(
  params: ReservationListParams,
): Promise<SplitReservationPage> {
  return api<SplitReservationPage>(
    `${base}/reservation-commands${query(params as Record<string, unknown>)}`,
  );
}

export function recordReservation(
  splitId: string,
  unitId: string,
  input: SplitReservationInput,
): Promise<SplitReservationCommand> {
  return api<SplitReservationCommand>(`${base}/splits/${splitId}/units/${unitId}/reservation`, 'POST', input);
}

// ------------------------------------------------------------ 发货回传

export function fetchWritebackTasks(params: WritebackListParams): Promise<SplitWritebackPage> {
  return api<SplitWritebackPage>(`${base}/writeback-tasks${query(params as Record<string, unknown>)}`);
}

export function recordWriteback(
  splitId: string,
  unitId: string,
  input: SplitWritebackInput,
): Promise<SplitWritebackTask> {
  return api<SplitWritebackTask>(`${base}/splits/${splitId}/units/${unitId}/writeback`, 'POST', input);
}

// ------------------------------------------------------------ 展示格式化

/**
 * 金额展示。
 *
 * <p>🔴 **不再做第二次四舍五入**：后端已经按 numeric(19,4) 落库并由 DB 实算汇总，
 * 前端再做一次 round 会让界面上的分摊合计与账面差出一分钱 —— 那正是拆单最不能出的错。
 * 这里只补齐显示用的固定位数，不改值。
 */
export function money(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const text = String(value);
  const dot = text.indexOf('.');
  if (dot < 0) return `${text}.0000`;
  const decimals = text.length - dot - 1;
  if (decimals >= 4) return text;
  return text.padEnd(text.length + (4 - decimals), '0');
}

/** 数量展示（numeric(19,6)），同样只补位不改值。 */
export function qty(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const text = String(value);
  const dot = text.indexOf('.');
  if (dot < 0) return `${text}.000000`;
  const decimals = text.length - dot - 1;
  if (decimals >= 6) return text;
  return text.padEnd(text.length + (6 - decimals), '0');
}

/**
 * 尾差展示。
 *
 * <p>尾差是 0 时显示「无尾差」而不是「0.0000」：0.0000 会被读成
 * 「有一笔 0 元的尾差」，而实际含义是「这次分摊正好除得尽」。
 */
export function residualText(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const numeric = Number(value);
  if (Number.isFinite(numeric) && numeric === 0) return '无尾差';
  return money(value);
}

export function isoText(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  // 直接用 UTC 字符串切片，避免受浏览器本地时区影响：
  // 「按发货时效拆」里 ship_by 的先后顺序是业务判断，显示成当地时间会把它排错。
  return parsed.toISOString().replace('T', ' ').slice(0, 19) + 'Z';
}

export function strategyText(strategy: string | null | undefined): string {
  switch (strategy) {
    case 'WAREHOUSE':
      return '按仓库拆';
    case 'CARRIER':
      return '按承运商拆';
    case 'TIMELINESS':
      return '按发货时效拆';
    case 'PLATFORM':
      return '按平台限制拆';
    default:
      return strategy || '—';
  }
}

export function unitStatusText(status: string | null | undefined): string {
  switch (status) {
    case 'PLANNED':
      return '待下发';
    case 'ALLOCATED':
      return '预占已登记';
    case 'DISPATCHED':
      return '已下发';
    case 'CANCELLED':
      return '已作废';
    default:
      return status || '—';
  }
}

export function splitStatusText(status: string | null | undefined): string {
  switch (status) {
    case 'PLANNED':
      return '已拆分';
    case 'CONFIRMED':
      return '已确认';
    case 'DISPATCHED':
      return '已下发';
    case 'CANCELLED':
      return '已作废';
    default:
      return status || '—';
  }
}

export function reservationStatusText(status: string | null | undefined): string {
  switch (status) {
    case 'REQUESTED':
      return '待下游认领';
    case 'ACKNOWLEDGED':
      return '已预占';
    case 'REJECTED':
      return '下游拒绝';
    case 'CANCELLED':
      return '已撤销';
    default:
      return status || '—';
  }
}

export function writebackStatusText(status: string | null | undefined): string {
  switch (status) {
    case 'PENDING':
      return '待回传';
    case 'SUBMITTED':
      return '已提交';
    case 'CONFIRMED':
      return '已回传';
    case 'REJECTED':
      return '平台拒绝';
    case 'PENDING_VERIFICATION':
      return '结果待查证';
    default:
      return status || '—';
  }
}

/** 落选原因的中文说明；被选中时返回空串（而不是「无原因」—— 它是被选中了，不是没有原因）。 */
export function rejectText(
  candidate: Pick<CandidateRow, 'selected' | 'rejectReasonLabel' | 'rejectReason'>,
): string {
  return candidate.selected ? '已选中' : candidate.rejectReasonLabel || candidate.rejectReason || '未说明';
}

/** 落选原因对应的配色（界面据此把「事实类落选」和「规则类落选」区分开）。 */
export function rejectTone(candidate: Pick<CandidateRow, 'selected' | 'rejectReason'>): string {
  if (candidate.selected) return 'ok';
  switch (candidate.rejectReason) {
    case 'INSUFFICIENT_STOCK':
    case 'STOCK_TOO_STALE':
      return 'warn';
    case 'PLATFORM_RESTRICTED':
    case 'SHIP_BY_TOO_SLOW':
    case 'NO_CARRIER':
    case 'WAREHOUSE_INACTIVE':
      return 'block';
    default:
      return 'muted';
  }
}

/** 守恒是否成立（前端独立复算一遍，而不是只信后端给的布尔）。 */
export function conserved(split: Pick<
  SplitRow,
  'orderTotalAmount' | 'allocatedAmountTotal' | 'residualAmountTotal'
>): boolean {
  const total = Number(split.orderTotalAmount);
  const alloc = Number(split.allocatedAmountTotal);
  const resid = Number(split.residualAmountTotal);
  if (!Number.isFinite(total) || !Number.isFinite(alloc) || !Number.isFinite(resid)) return false;
  return Math.round((alloc + resid) * 1e4) === Math.round(total * 1e4);
}

export function errorText(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return '拆单服务暂时不可用，请稍后重试';
}

/** 供筛选用的状态选项（与后端枚举同源，不在前端另立一份词表）。 */
export const STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: '全部状态' },
  { value: 'PLANNED', label: '已拆分' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'DISPATCHED', label: '已下发' },
  { value: 'CANCELLED', label: '已作废' },
];

export const STRATEGY_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: '全部维度' },
  { value: 'WAREHOUSE', label: '按仓库拆' },
  { value: 'CARRIER', label: '按承运商拆' },
  { value: 'TIMELINESS', label: '按发货时效拆' },
  { value: 'PLATFORM', label: '按平台限制拆' },
];

export const RESERVATION_STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: '全部状态' },
  { value: 'REQUESTED', label: '待下游认领' },
  { value: 'ACKNOWLEDGED', label: '已预占' },
  { value: 'REJECTED', label: '下游拒绝' },
  { value: 'CANCELLED', label: '已撤销' },
];

export const WRITEBACK_STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: '全部状态' },
  { value: 'PENDING', label: '待回传' },
  { value: 'SUBMITTED', label: '已提交' },
  { value: 'CONFIRMED', label: '已回传' },
  { value: 'REJECTED', label: '平台拒绝' },
  { value: 'PENDING_VERIFICATION', label: '结果待查证' },
];

/** 展示用类型再导出，页面不必直接引契约包。 */
export type {
  CandidateRow,
  OrderSplitCapabilities,
  SplitReservationCommand,
  SplitReservationPage,
  SplitDetail,
  SplitPreview,
  SplitRow,
  SplitRule,
  SplitSaved,
  SplitStockSnapshotRow,
  UnitLine,
  SplitWarehouseRow,
  SplitWritebackPage,
  SplitWritebackTask,
};
