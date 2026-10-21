import type {
  StockoutCapabilities,
  StockoutComputeInput,
  StockoutComputeResult,
  StockoutRiskItemDetail,
  StockoutRiskItemPage,
  StockoutSnapshotPage,
  StockoutVelocityInput,
  StockoutVelocityPage,
  StockoutVelocityRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 6.2.1.1 缺货与断货风险前端模型。
 *
 * <p>蓝图：「识别断货风险 SKU，输出补货优先级与缺口量。」输出「断货风险清单
 * （SKU、可用量、日均销、可支撑天数、预计断货日、缺口量、涉及店铺），按紧急度排序」。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>「无数据」是一等状态，绝不显示成 0</b>：没有销售速度登记时，日均销 /
 *       可支撑天数 / 预计断货日都是 <b>null</b>。页面显示「无数据」而不是 0 ——
 *       0 日均销量会让可支撑天数变成无穷、健康度被高估，那正是要防的事。</li>
 *   <li><b>紧急度是一等维度</b>，而且带「依据」：清单行直接显示后端给的
 *       {@code urgencyBasis}，让「为什么这一条排在前面」不用点进去再猜。</li>
 *   <li><b>缺口量与可用量分开看</b>：缺口量是「备货周期内还差多少」，可用量是「现在手上还有多少」。
 *       合成一个数字就看不出该补多少。</li>
 *   <li><b>涉及店铺用编码显示</b>：店铺主数据属 channel 2.3.1.1，本模块只拿到 shop_code，
 *       页面不编造店铺名。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */

const base = 'trade/stockout-risk';

// ================================================================ 查询参数

export interface StockoutRiskParams {
  snapshotId?: string;
  urgency?: string;
  skuCode?: string;
  keyword?: string;
  onlyShortage?: boolean;
  onlyWithoutVelocity?: boolean;
  page?: number;
  size?: number;
}

export function stockoutRiskQuery(params: StockoutRiskParams): string {
  const parts: string[] = [];
  if (params.snapshotId) parts.push(`snapshotId=${encodeURIComponent(params.snapshotId)}`);
  if (params.urgency) parts.push(`urgency=${encodeURIComponent(params.urgency)}`);
  if (params.skuCode) parts.push(`skuCode=${encodeURIComponent(params.skuCode)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.onlyShortage) parts.push('onlyShortage=true');
  if (params.onlyWithoutVelocity) parts.push('onlyWithoutVelocity=true');
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export interface StockoutVelocityParams {
  skuCode?: string;
  keyword?: string;
  effectiveOnly?: boolean;
  page?: number;
  size?: number;
}

export function stockoutVelocityQuery(params: StockoutVelocityParams): string {
  const parts: string[] = [];
  if (params.skuCode) parts.push(`skuCode=${encodeURIComponent(params.skuCode)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.effectiveOnly) parts.push('effectiveOnly=true');
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export interface StockoutSnapshotParams {
  page?: number;
  size?: number;
}

export function stockoutSnapshotQuery(params: StockoutSnapshotParams): string {
  const parts: string[] = [];
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 调用点

export function fetchCapabilities() {
  return api<StockoutCapabilities>(`${base}/capabilities`);
}

export function fetchItems(params: StockoutRiskParams) {
  return api<StockoutRiskItemPage>(`${base}/items${stockoutRiskQuery(params)}`);
}

export function fetchItem(itemId: string) {
  return api<StockoutRiskItemDetail>(`${base}/items/${encodeURIComponent(itemId)}`);
}

export function fetchVelocities(params: StockoutVelocityParams) {
  return api<StockoutVelocityPage>(`${base}/velocities${stockoutVelocityQuery(params)}`);
}

export function registerVelocity(input: StockoutVelocityInput) {
  return api<StockoutVelocityRow>(`${base}/velocities`, 'POST', input);
}

export function fetchSnapshots(params: StockoutSnapshotParams) {
  return api<StockoutSnapshotPage>(`${base}/snapshots${stockoutSnapshotQuery(params)}`);
}

/** 发起一次计算（后端落成一份快照）。 */
export function computeSnapshot(input: StockoutComputeInput) {
  return api<StockoutComputeResult>(`${base}/snapshots`, 'POST', input);
}

// ================================================================ 展示口径

/** 紧急度标签（与后端 StockoutUrgency 一一对应）。 */
export const stockoutUrgencyLabels: Record<string, string> = {
  CRITICAL: '紧急',
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
  NO_DATA: '无数据',
};

/**
 * 紧急度的视觉分级。
 *
 * <p>NO_DATA 用中性灰而不是红色 —— 它不是「危险」，是「答不出来」，
 * 用红色会让人以为那是断货，把真正紧急的那几条淹掉。
 */
export function urgencyTone(urgency: string): 'danger' | 'warning' | 'info' | 'neutral' {
  switch (urgency) {
    case 'CRITICAL':
      return 'danger';
    case 'HIGH':
      return 'danger';
    case 'MEDIUM':
      return 'warning';
    case 'LOW':
      return 'info';
    default:
      return 'neutral';
  }
}

export function urgencyLabel(urgency: string): string {
  return stockoutUrgencyLabels[urgency] ?? urgency;
}

/** 销售速度登记的来源标签。 */
export const stockoutVelocitySourceLabels: Record<string, string> = {
  MANUAL: '人工登记',
  IMPORT: '批量导入',
  EXTERNAL_SYNC: '外部同步',
};

export function velocitySourceLabel(sourceKind: string): string {
  return stockoutVelocitySourceLabels[sourceKind] ?? sourceKind;
}

/** 「无数据」的统一文案。 */
export const NO_DATA_TEXT = '无数据';

/**
 * 日均销量的展示。
 *
 * <p>null（没有登记销售速度）显示「无数据」，**不显示 0**。
 */
export function dailySalesText(dailySalesQuantity: number | null): string {
  if (dailySalesQuantity === null || dailySalesQuantity === undefined) return NO_DATA_TEXT;
  return fmt(dailySalesQuantity);
}

/** 可支撑天数的展示（null = 算不出来）。 */
export function coverageText(coverageDays: number | null): string {
  if (coverageDays === null || coverageDays === undefined) return NO_DATA_TEXT;
  return `${coverageDays} 天`;
}

/** 预计断货日的展示（null = 算不出来）。 */
export function stockoutDateText(expectedStockoutDate: string | null): string {
  if (!expectedStockoutDate) return NO_DATA_TEXT;
  return expectedStockoutDate;
}

/**
 * 涉及店铺的展示。
 *
 * <p>空列表是「这个 SKU 还没挂到任何店铺」，不是「查不到」—— 所以显示「未挂店铺」，
 * 而不是一个空格子（空格子会被读成「没查」）。
 */
export function shopText(shopCodes: string[]): string {
  if (!shopCodes || shopCodes.length === 0) return '未挂店铺';
  return shopCodes.join('、');
}

/** 缺口量的展示。0 是「不构成缺口」，不是「没查出来」。 */
export function shortageText(shortageQuantity: number): string {
  return fmt(shortageQuantity);
}

/**
 * 是否是「答不出来」的行。
 *
 * <p>页面用它把无数据档单独挑出来提示：那一档要人去补销售速度登记，
 * 混在紧急档里会被当成「不紧急」而永远没人管。
 */
export function isNoData(urgency: string): boolean {
  return urgency === 'NO_DATA';
}

/** 数量格式化：去掉无意义的尾零，null 一律给「无数据」。 */
export function fmt(value: number | null | undefined): string {
  if (value === null || value === undefined) return NO_DATA_TEXT;
  const rounded = Number(value.toFixed(6));
  return rounded.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/** 时间格式化（timestamptz 带偏移的 ISO 串）。 */
export function moment(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}
