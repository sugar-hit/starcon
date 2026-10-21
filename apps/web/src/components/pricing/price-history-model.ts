import { api } from '../inventory/api';

/**
 * 5.5.1.1 价格历史前端模型。
 *
 * <p>蓝图加粗句是本模块唯一不可让步的一条：
 *
 * <blockquote>
 *
 * **价格历史 + 销量 + 利润三者对齐，才能回答"降价到底有没有用"**。
 *
 * </blockquote>
 *
 * <h2>🔴 null 绝不能渲染成 0</h2>
 *
 * <p>三个维度里，每一个「取不到」在界面上都必须与「确实是 0」分得开：
 *
 * <ul>
 *   <li><b>销量未接线</b> ⇒ 显示「销量维度未接线」，<b>不是</b>「销量 0」。
 *       显示 0 会让每一次降价都变成「降价后一件没卖出去」——
 *       那是一个凭空造出来的结论，比「读不到」危险得多。</li>
 *   <li><b>利润没测准</b>（成本不完整）⇒ 显示「未测准」，<b>不是</b>「毛利 0」。
 *       5.3.1.1 的决策就绪闸把「没测准」与「毛利刚好 0」分开，本页必须继承这个区分。</li>
 *   <li><b>没有旧价</b>（首次定价）⇒ 显示「首次定价」，<b>不是</b>「从 0 涨到新价」。</li>
 * </ul>
 *
 * <h2>🔴 「变更」与「观测」不是一回事</h2>
 *
 * <p>5.5.2.1 的价格观测台账是「某时刻看到的价」，本模块的时间轴是「价被改了」。
 * 页面上不会把观测混进变更事实 —— 混进去就等于凭空造出一堆没发生过的调价。
 */

/** 变更来源。与后端 {@code ChangeSource} 逐字对应。 */
export type ChangeSource =
  | 'MANUAL_ADJUSTMENT'
  | 'BATCH_ADJUSTMENT'
  | 'PRICE_RULE'
  | 'PRICE_LIST'
  | 'PLATFORM_FEEDBACK'
  | 'IMPORT'
  | 'OTHER';

/** 价格变动方向。与后端 {@code Direction} 逐字对应。 */
export type Direction = 'INITIAL' | 'UP' | 'DOWN' | 'UNCHANGED';

/** 变更原因的粗分类。与后端 {@code ReasonCategory} 逐字对应。 */
export type ReasonCategory =
  'COMPETITION' | 'COST' | 'STOCK' | 'LIFECYCLE' | 'PROMOTION' | 'SEASONAL' | 'MANUAL' | 'OTHER';

// ================================================================== 能力声明

/** 一个输入维度的能力声明。 */
export interface InputCapability {
  code: string;
  label: string;
  source: string;
  available: boolean;
  ownerModule: string;
  impact: string;
  basis: string;
  note: string;
}

/** 能力声明。页面顶部固定显示它。 */
export interface PriceHistoryCapabilities {
  inputs: InputCapability[];
  listingNote: string;
  changeSourceNote: string;
  profitNote: string;
  salesNote: string;
  upstreamNote: string;
  downstreamNote: string;
  sourceNote: string;
  actionsNote: string;
}

// ================================================================== 变更事实

/** 时间轴的一行 = 一次价格变更。 */
export interface PriceChangeRow {
  id: string;
  skuId: string;
  skuCode: string | null;
  skuName: string | null;
  platformCode: string;
  siteCode: string;
  currency: string;
  oldPrice: string | null;
  newPrice: string;
  priceDelta: string | null;
  changeRatio: string | null;
  direction: string;
  directionLabel: string;
  changeSource: string;
  changeSourceLabel: string;
  reasonCode: string;
  reasonText: string;
  operatorId: string | null;
  sourceModule: string;
  sourceReference: string;
  eventKey: string;
  effectiveAt: string;
  recordedAt: string;
  grossProfitBefore: string | null;
  grossMarginBefore: string | null;
  grossProfitAfter: string | null;
  grossMarginAfter: string | null;
  decisionReady: boolean;
  profitNote: string;
  createdAt: string;
}

/** 时间轴的查询条件。 */
export interface ChangeFilters {
  keyword: string;
  skuId: string;
  platformCode: string;
  siteCode: string;
  changeSource: string;
  reasonCode: string;
  direction: string;
  windowFrom: string;
  windowTo: string;
  sort: string;
  page: number;
  size: number;
}

/** 时间轴分页结果。 */
export interface ChangeListPage {
  items: PriceChangeRow[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 单条变更详情。 */
export interface ChangeDetail {
  change: PriceChangeRow;
  previous: PriceChangeRow | null;
  next: PriceChangeRow | null;
  basis: string;
  note: string;
}

// ================================================================== 时间轴

/** 时间轴上的一格。 */
export interface TimelineEntry {
  change: PriceChangeRow;
  chainConsistent: boolean;
  chainNote: string;
  cumulativeDelta: string | null;
}

/** 5.3.2.2 价格规则的待执行建议（**尚未生效**，只读引用）。 */
export interface PendingSuggestion {
  id: string;
  ruleId: string;
  ruleName: string | null;
  skuId: string;
  platformCode: string;
  siteCode: string;
  currency: string;
  actionType: string;
  currentPrice: string | null;
  suggestedPrice: string | null;
  priceChangeRate: string | null;
  status: string;
  requiresConfirmation: boolean;
  grossMarginRate: string | null;
  reason: string;
  createdAt: string;
}

/** 一个 listing（SKU × 店铺）的时间轴。 */
export interface Timeline {
  skuId: string;
  skuCode: string | null;
  skuName: string | null;
  platformCode: string;
  siteCode: string;
  currency: string | null;
  currentPrice: string | null;
  currentPriceAt: string | null;
  changeCount: number;
  entries: TimelineEntry[];
  pendingSuggestions: PendingSuggestion[];
  note: string;
}

// ================================================================== 对比视图

/** 「降价到底有没有用」的一行对比。 */
export interface ComparisonRow {
  id: string;
  skuId: string;
  skuCode: string | null;
  skuName: string | null;
  platformCode: string;
  siteCode: string;
  currency: string;
  effectiveAt: string;
  oldPrice: string | null;
  newPrice: string;
  priceDelta: string | null;
  changeRatio: string | null;
  direction: string;
  changeSource: string;
  reasonCode: string;
  reasonText: string;
  grossProfitBefore: string | null;
  grossProfitAfter: string | null;
  grossProfitDelta: string | null;
  grossMarginBefore: string | null;
  grossMarginAfter: string | null;
  decisionReady: boolean;
  profitNote: string;
  /** 销量侧取到了吗？false 时下面三个销量列一律是 null（**不是 0**）。 */
  salesAvailable: boolean;
  salesNote: string;
  salesQuantityBefore: string | null;
  salesQuantityAfter: string | null;
  salesQuantityDelta: string | null;
}

/** 对比视图分页结果。 */
export interface ComparisonPage {
  items: ComparisonRow[];
  total: number;
  page: number;
  size: number;
  salesAvailable: boolean;
  note: string;
}

/** 对比视图的查询条件。 */
export interface ComparisonFilters {
  keyword: string;
  skuId: string;
  platformCode: string;
  siteCode: string;
  windowFrom: string;
  windowTo: string;
  salesWindowDays: number;
  page: number;
  size: number;
}

// ================================================================== 原因字典

/** 一条变更原因。 */
export interface ReasonRow {
  id: string;
  code: string;
  label: string;
  category: string;
  categoryLabel: string;
  enabled: boolean;
  sortOrder: number;
  note: string;
  updatedAt: string | null;
}

/** 保存原因字典项。 */
export interface ReasonInput {
  code: string;
  label: string;
  category: string;
  enabled: boolean;
  sortOrder: number;
  note: string;
}

// ================================================================== 登记

/** 人工登记一次价格变更。 */
export interface ChangeInput {
  skuId: string;
  platformCode: string;
  siteCode: string;
  currency: string;
  /** 旧价。留空 = **首次定价**（不是「从 0 涨到新价」）。 */
  oldPrice: string | null;
  newPrice: string;
  changeSource: string;
  reasonCode: string;
  reasonText: string;
  effectiveAt: string;
  /** 利润口径（5.3.1.1 的输入）。全空 ⇒ 不测算利润。 */
  quantity: string | null;
  costCurrency: string | null;
  fxRate: string | null;
  platformFeeRate: string | null;
  platformFeeFixed: string | null;
  logisticsCostPerUnit: string | null;
  monthlyFixedCost: string | null;
  costWindowFrom: string | null;
  costWindowTo: string | null;
  note: string;
}

/** 登记结果。 */
export interface ChangeSaved {
  id: string;
  skuId: string;
  platformCode: string;
  siteCode: string;
  duplicated: boolean;
  note: string;
}

// ================================================================== 纯函数

export const EMPTY_CHANGE_FILTERS: ChangeFilters = {
  keyword: '',
  skuId: '',
  platformCode: '',
  siteCode: '',
  changeSource: '',
  reasonCode: '',
  direction: '',
  windowFrom: '',
  windowTo: '',
  sort: 'EFFECTIVE_AT_DESC',
  page: 0,
  size: 20,
};

export const EMPTY_COMPARISON_FILTERS: ComparisonFilters = {
  keyword: '',
  skuId: '',
  platformCode: '',
  siteCode: '',
  windowFrom: '',
  windowTo: '',
  salesWindowDays: 14,
  page: 0,
  size: 20,
};

export const CHANGE_SOURCES: readonly { code: string; label: string }[] = [
  { code: 'MANUAL_ADJUSTMENT', label: '人工调价' },
  { code: 'BATCH_ADJUSTMENT', label: '批量调价' },
  { code: 'PRICE_RULE', label: '价格规则自动调价' },
  { code: 'PRICE_LIST', label: '价目表改价' },
  { code: 'PLATFORM_FEEDBACK', label: '平台价格回流' },
  { code: 'IMPORT', label: '批量导入' },
  { code: 'OTHER', label: '其它来源' },
];

export const DIRECTIONS: readonly { code: string; label: string }[] = [
  { code: 'INITIAL', label: '首次定价' },
  { code: 'UP', label: '涨价' },
  { code: 'DOWN', label: '降价' },
  { code: 'UNCHANGED', label: '价格未变' },
];

export const REASON_CATEGORIES: readonly { code: string; label: string }[] = [
  { code: 'COMPETITION', label: '竞品' },
  { code: 'COST', label: '成本变动' },
  { code: 'STOCK', label: '库存' },
  { code: 'LIFECYCLE', label: '生命周期' },
  { code: 'PROMOTION', label: '促销' },
  { code: 'SEASONAL', label: '季节' },
  { code: 'MANUAL', label: '其它人工原因' },
  { code: 'OTHER', label: '其它' },
];

export const SORT_OPTIONS: readonly { code: string; label: string }[] = [
  { code: 'EFFECTIVE_AT_DESC', label: '生效时刻（新→旧）' },
  { code: 'EFFECTIVE_AT_ASC', label: '生效时刻（旧→新）' },
  { code: 'PRICE_DELTA_DESC', label: '价差（大→小）' },
  { code: 'PRICE_DELTA_ASC', label: '价差（小→大）' },
  { code: 'CREATED_AT_DESC', label: '入账时刻（新→旧）' },
  { code: 'SKU_ASC', label: 'SKU 编码' },
];

/** 变更来源 → 展示名。 */
export function changeSourceLabel(value: string | null | undefined): string {
  const found = CHANGE_SOURCES.find((s) => s.code === value);
  return found ? found.label : value || '未标注';
}

/** 方向 → 展示名。 */
export function directionLabel(value: string | null | undefined): string {
  const found = DIRECTIONS.find((s) => s.code === value);
  return found ? found.label : value || '未标注';
}

/** 方向 → 语气（涨价 / 降价 的视觉要一眼可辨）。 */
export function directionTone(value: string | null | undefined): 'danger' | 'info' | 'neutral' {
  if (value === 'UP') return 'danger';
  if (value === 'DOWN') return 'info';
  return 'neutral';
}

/**
 * 金额 → 展示串。
 *
 * <p>🔴 null 显示「未登记」而**不是 0.00**：后端把「没有这个价 / 读不到」表达成
 * {@code null}，渲染成 0.00 会让「降价到底有没有用」这个问题答错方向。
 */
export function moneyText(value: string | null | undefined, unavailable = '未登记'): string {
  if (value === null || value === undefined || value === '') return unavailable;
  return value;
}

/** 带币种的金额串。 */
export function moneyWithCurrency(
  value: string | null | undefined,
  currency: string | null | undefined,
  unavailable = '未登记',
): string {
  const text = moneyText(value, unavailable);
  if (text === unavailable) return text;
  return currency ? `${currency} ${text}` : text;
}

/** ISO 时刻 → 只到秒的串。 */
export function timeText(value: string | null | undefined, empty = '—'): string {
  if (!value) return empty;
  return value.replace('T', ' ').replace('Z', 'Z');
}

/** ISO 时刻 → 只到日的串。 */
export function dayText(value: string | null | undefined, empty = '—'): string {
  if (!value) return empty;
  return value.slice(0, 10);
}

/** 比例 → 百分比串（后端给的是 0.05 这种小数）。 */
export function ratioText(value: string | null | undefined, empty = '—'): string {
  if (value === null || value === undefined || value === '') return empty;
  const parsed = Number(value);
  if (Number.isNaN(parsed)) return value;
  return `${(parsed * 100).toFixed(2)}%`;
}

/** 带符号的比例串（涨价带 +，降价带 −）。 */
export function signedRatioText(value: string | number | null | undefined, empty = '—'): string {
  if (value === null || value === undefined || value === '') return empty;
  const parsed = Number(value);
  if (Number.isNaN(parsed)) return String(value);
  const sign = parsed > 0 ? '+' : '';
  return `${sign}${(parsed * 100).toFixed(2)}%`;
}

/** 带符号的金额串（价差）。 */
export function signedMoneyText(value: string | number | null | undefined, empty = '—'): string {
  if (value === null || value === undefined || value === '') return empty;
  const parsed = Number(value);
  if (Number.isNaN(parsed)) return String(value);
  const sign = parsed > 0 ? '+' : '';
  return `${sign}${value}`;
}

/**
 * 利润的展示串。
 *
 * <p>🔴 **没测准**与**毛利刚好 0** 必须分得开：前者显示「未测准」，
 * 后者显示 0.00。混为一谈会让一个可能巨亏的商品显示成「毛利 0」。
 */
export function profitText(
  value: string | null | undefined,
  decisionReady: boolean,
  note: string | null | undefined,
): string {
  if (!decisionReady) return note && note.trim() ? `未测准（${note.trim()}）` : '未测准';
  return moneyText(value, '未测准');
}

/**
 * 利润差额的展示串。
 *
 * <p>🔴 差额是**后端算好的**（{@code grossProfitDelta}），前端**不自己减** ——
 * 金额的算术留在服务端，前端只负责显示。前端减一次浮点金额，
 * 就会把 4 位小数的口径悄悄变成 JS number 的口径。
 */
export function profitDeltaText(delta: string | null | undefined, decisionReady: boolean): string {
  if (!decisionReady) return '未测准';
  return signedMoneyText(delta, '未测准');
}

/**
 * 销量的展示串。
 *
 * <p>🔴 **未接线**显示「未接线」，**不是** 0 —— 页面上必须让「没接上游」与
 * 「这段时间真的一件没卖出去」分得开。
 */
export function salesText(value: string | null | undefined, salesAvailable: boolean): string {
  if (!salesAvailable) return '未接线';
  return moneyText(value, '未接线');
}

/**
 * 销量差额的展示串。
 *
 * <p>🔴 差额同样是**后端算好的**（{@code salesQuantityDelta}）。
 */
export function salesDeltaText(delta: string | null | undefined, salesAvailable: boolean): string {
  if (!salesAvailable) return '未接线';
  return signedMoneyText(delta, '未接线');
}

/** 时间轴上「SKU × 店铺」的展示串。 */
export function listingText(row: {
  skuCode?: string | null;
  platformCode: string;
  siteCode: string;
}): string {
  const sku = row.skuCode || '（SKU 未命名）';
  return `${sku} @ ${row.platformCode}:${row.siteCode}`;
}

/** 「旧价 → 新价」的展示串。首次定价只有一个价。 */
export function priceMoveText(row: {
  oldPrice: string | null;
  newPrice: string;
  currency: string;
}): string {
  if (row.oldPrice === null) return `首次定价 ${row.currency} ${row.newPrice}`;
  return `${row.currency} ${row.oldPrice} → ${row.currency} ${row.newPrice}`;
}

/** 变更时间的展示串（生效时刻 + 入账时刻，两者不同）。 */
export function effectiveText(row: { effectiveAt: string; recordedAt: string }): string {
  const effective = timeText(row.effectiveAt);
  const recorded = timeText(row.recordedAt);
  if (effective === recorded) return effective;
  return `${effective}（入账 ${recorded}）`;
}

// ================================================================== 校验

/**
 * 登记一次价格变更的前端校验。
 *
 * <p>🔴 「旧价」留空是**合法**的（首次定价）；但**新价必须大于 0**，
 * 且**原因必须写**（蓝图要求记「原因」）。
 */
export function validateChange(input: ChangeInput): string[] {
  const problems: string[] = [];
  if (!input.skuId.trim()) problems.push('必须指定 SKU');
  if (!input.platformCode.trim()) problems.push('必须指定平台码');
  if (!input.siteCode.trim()) problems.push('必须指定站点码');
  if (!/^[A-Z]{3}$/.test(input.currency.trim().toUpperCase())) {
    problems.push('币种必须是三位大写字母（ISO 4217）');
  }
  const newPrice = Number(input.newPrice);
  if (!input.newPrice.trim()) {
    problems.push('必须给出新价');
  } else if (Number.isNaN(newPrice) || newPrice <= 0) {
    problems.push('新价必须大于 0（0 价不是价 —— 它会被算成「免费」并污染后面每一次毛利率）');
  }
  if (input.oldPrice !== null && input.oldPrice !== '') {
    const oldPrice = Number(input.oldPrice);
    if (Number.isNaN(oldPrice) || oldPrice < 0) problems.push('旧价不能为负');
  }
  if (!input.changeSource.trim()) problems.push('必须选择变更来源');
  if (!input.reasonText.trim()) problems.push('必须写明变更原因');
  if (!input.effectiveAt.trim()) problems.push('必须给出变更生效时刻');
  return problems;
}

/** 原因字典项的前端校验。 */
export function validateReason(input: ReasonInput): string[] {
  const problems: string[] = [];
  if (!input.code.trim()) problems.push('变更原因编码不能为空');
  if (!input.label.trim()) problems.push('变更原因名称不能为空');
  if (!input.category.trim()) problems.push('必须选择变更原因分类');
  return problems;
}

/** 现在的时间（本地时区的 {@code datetime-local} 格式）。 */
export function nowLocal(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T${pad(now.getHours())}:${pad(
    now.getMinutes(),
  )}`;
}

// ================================================================== 端点

export function fetchPriceHistoryCapabilities(): Promise<PriceHistoryCapabilities> {
  return api<PriceHistoryCapabilities>('catalog/price-history/capabilities');
}

export function fetchPriceChanges(filters: ChangeFilters): Promise<ChangeListPage> {
  const query = new URLSearchParams();
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.skuId) query.set('skuId', filters.skuId);
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.siteCode) query.set('siteCode', filters.siteCode);
  if (filters.changeSource) query.set('changeSource', filters.changeSource);
  if (filters.reasonCode) query.set('reasonCode', filters.reasonCode);
  if (filters.direction) query.set('direction', filters.direction);
  if (filters.windowFrom) query.set('windowFrom', filters.windowFrom);
  if (filters.windowTo) query.set('windowTo', filters.windowTo);
  if (filters.sort) query.set('sort', filters.sort);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<ChangeListPage>(`catalog/price-history/changes?${query.toString()}`);
}

export function fetchPriceChange(changeId: string): Promise<ChangeDetail> {
  return api<ChangeDetail>(`catalog/price-history/changes/${changeId}`);
}

export function registerPriceChange(input: ChangeInput): Promise<ChangeSaved> {
  return api<ChangeSaved>('catalog/price-history/changes', 'POST', input);
}

export function fetchPriceTimeline(
  skuId: string,
  platformCode: string,
  siteCode: string,
  windowFrom?: string,
  windowTo?: string,
): Promise<Timeline> {
  const query = new URLSearchParams();
  query.set('skuId', skuId);
  query.set('platformCode', platformCode);
  query.set('siteCode', siteCode);
  if (windowFrom) query.set('windowFrom', windowFrom);
  if (windowTo) query.set('windowTo', windowTo);
  return api<Timeline>(`catalog/price-history/timeline?${query.toString()}`);
}

export function fetchPriceComparison(filters: ComparisonFilters): Promise<ComparisonPage> {
  const query = new URLSearchParams();
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.skuId) query.set('skuId', filters.skuId);
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.siteCode) query.set('siteCode', filters.siteCode);
  if (filters.windowFrom) query.set('windowFrom', filters.windowFrom);
  if (filters.windowTo) query.set('windowTo', filters.windowTo);
  if (filters.salesWindowDays > 0) query.set('salesWindowDays', String(filters.salesWindowDays));
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<ComparisonPage>(`catalog/price-history/comparison?${query.toString()}`);
}

export function fetchPriceChangeReasons(): Promise<ReasonRow[]> {
  return api<ReasonRow[]>('catalog/price-history/reasons');
}

export function savePriceChangeReason(input: ReasonInput): Promise<ReasonRow> {
  return api<ReasonRow>('catalog/price-history/reasons', 'POST', input);
}
