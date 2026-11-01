import type {
  AiForecastCapabilities,
  AiForecastElasticityRow,
  AiForecastFactorRow,
  AiForecastHistoryInput,
  AiForecastHistoryPage,
  AiForecastHistoryRow,
  AiForecastHistorySyncResult,
  AiForecastHotItemRow,
  AiForecastHotItemView,
  AiForecastOpportunityRow,
  AiForecastOpportunityView,
  AiForecastOverview,
  AiForecastPriceElasticityView,
  AiForecastPromotionInput,
  AiForecastPromotionPage,
  AiForecastPromotionRow,
  AiForecastResultDetail,
  AiForecastResultPage,
  AiForecastResultRow,
  AiForecastRunDetail,
  AiForecastRunInput,
  AiForecastRunPage,
  AiForecastRunRow,
  AiForecastSuggestionPage,
  AiForecastSuggestionRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 18.4.2.2「选品与需求预测」前端模型。
 *
 * 蓝图：「**AI 预测能力**：销量预测（含促销与大促影响）、补货量建议、价格弹性与调价建议、
 * 爆品识别与机会品发现。输入：历史销量、库存、在途、价格、促销日历；预测参数。
 * 输出：预测结果 + 置信区间 + 关键影响因素说明。关系：上游 = 13 分析、6 采购、7 库存；
 * 下游 = 6.2.1.2 补货建议、5.4 调价。**预测必须给置信区间和影响因素**，单点数字会让用户
 * 过度信任；建议只作为参考输入，不自动执行。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **区间与影响因素必须真的显示出来**。只显示一个点估计的页面，等于把「模型说多少」
 *    当成事实 —— 蓝图加粗句防的正是这个。`intervalGeometry()` 把点估计在区间里的位置算出来，
 *    页面据此画出区间条。
 * 2. **影响因素不是标签，是「方向 + 取值 + 口径说明」**。`factorRows()` 逐条摊开，
 *    口径说明直接用后端给的原文，前端**不自己编**。
 * 3. **建议只作参考，不自动执行**：采纳 / 驳回只是**记录人的决定**，
 *    页面上的按钮文案与提示都写明「不会替你下单 / 改价」。
 * 4. **未接通上游 ≠ 没有数据**：能力声明里 `available=false` 的项按原样显示并写明归谁，
 *    不显示成「暂无」。
 * 5. **加载 / 空 / 错误三态齐全**：空态区分「筛选无结果」与「还没有输入历史」。
 *
 * <p><b>类型全部从契约派生</b>（`packages/api-contracts` 的 codegen 产物），
 * 前端**不手抄**字符串联合 —— 枚举值由后端的字典列给出。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有分支）。 */
const base = 'platform/ai/forecast';

/**
 * 拼查询串：空值不拼（拼 `undefined=` 上游会当成「筛选了这个值」）。
 */
function query(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  return search.toString();
}

// ================================================================ 读取

/** 能力声明：算法口径、阈值、来源与边界逐条报出。 */
export function fetchCapabilities(): Promise<AiForecastCapabilities> {
  return api<AiForecastCapabilities>(`${base}/capabilities`);
}

/** 概览：几个数。 */
export function fetchOverview(): Promise<AiForecastOverview> {
  return api<AiForecastOverview>(`${base}/overview`);
}

/** 促销日历列表（真分页 + 真筛选）。 */
export function fetchPromotions(params: {
  keyword?: string | undefined;
  promotionType?: string | undefined;
  scopeType?: string | undefined;
  activeOnly?: boolean | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<AiForecastPromotionPage> {
  return api<AiForecastPromotionPage>(`${base}/promotions?${query(params)}`);
}

/** 登记或修改一条促销。 */
export function savePromotion(input: AiForecastPromotionInput): Promise<AiForecastPromotionRow> {
  return api<AiForecastPromotionRow>(`${base}/promotions`, 'POST', input);
}

/** 删除一条促销。 */
export function deletePromotion(id: string): Promise<void> {
  return api<void>(`${base}/promotions/${id}`, 'DELETE');
}

/** 需求历史台账（真分页 + 真筛选）。 */
export function fetchHistory(params: {
  skuCode?: string | undefined;
  from?: string | undefined;
  to?: string | undefined;
  source?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<AiForecastHistoryPage> {
  return api<AiForecastHistoryPage>(`${base}/history?${query(params)}`);
}

/** 录入一条需求历史（**人工录入**）。 */
export function saveHistory(input: AiForecastHistoryInput): Promise<AiForecastHistoryRow> {
  return api<AiForecastHistoryRow>(`${base}/history`, 'POST', input);
}

/** 删除一条需求历史。 */
export function deleteHistory(id: string): Promise<void> {
  return api<void>(`${base}/history/${id}`, 'DELETE');
}

/**
 * 从 13 分析同步历史销量。
 *
 * 🔴 上游读不到时后端如实报 503 且**一个数据都不写** —— 这里**不**做任何本地兜底，
 * 那等于伪造一份「已经同步过了」。
 */
export function syncHistory(from?: string, to?: string): Promise<AiForecastHistorySyncResult> {
  return api<AiForecastHistorySyncResult>(`${base}/history/sync?${query({ from, to })}`, 'POST');
}

/** 运行列表（真分页 + 真筛选）。 */
export function fetchRuns(params: {
  status?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<AiForecastRunPage> {
  return api<AiForecastRunPage>(`${base}/runs?${query(params)}`);
}

/** 运行详情（结果 + 影响因素）。 */
export function fetchRunDetail(id: string): Promise<AiForecastRunDetail> {
  return api<AiForecastRunDetail>(`${base}/runs/${id}`);
}

/** 发起一次预测运行（**只产出建议，不执行任何下游动作**）。 */
export function runForecast(input: AiForecastRunInput): Promise<AiForecastRunDetail> {
  return api<AiForecastRunDetail>(`${base}/runs`, 'POST', input);
}

/** 预测结果列表（真分页 + 真筛选）。 */
export function fetchForecasts(params: {
  skuCode?: string | undefined;
  runId?: string | undefined;
  historySource?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<AiForecastResultPage> {
  return api<AiForecastResultPage>(`${base}/forecasts?${query(params)}`);
}

/** 预测详情（**点估计 + 置信区间 + 影响因素**）。 */
export function fetchForecastDetail(id: string): Promise<AiForecastResultDetail> {
  return api<AiForecastResultDetail>(`${base}/forecasts/${id}`);
}

/** 建议列表（补货 / 调价）。 */
export function fetchSuggestions(params: {
  suggestionType?: string | undefined;
  status?: string | undefined;
  skuCode?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<AiForecastSuggestionPage> {
  return api<AiForecastSuggestionPage>(`${base}/replenish-suggestions?${query(params)}`);
}

/** 人工采纳一条建议（**只记录决定，不执行**）。 */
export function adoptSuggestion(id: string, note?: string): Promise<AiForecastSuggestionRow> {
  return api<AiForecastSuggestionRow>(`${base}/suggestions/${id}/adopt`, 'POST', { note });
}

/** 人工驳回一条建议。 */
export function rejectSuggestion(id: string, note?: string): Promise<AiForecastSuggestionRow> {
  return api<AiForecastSuggestionRow>(`${base}/suggestions/${id}/reject`, 'POST', { note });
}

/** 价格弹性（**算不出来的 SKU 也在列表里，并写明差在哪**）。 */
export function fetchPriceElasticity(params: {
  skuCode?: string | undefined;
  windowDays?: number | undefined;
}): Promise<AiForecastPriceElasticityView> {
  return api<AiForecastPriceElasticityView>(`${base}/price-elasticity?${query(params)}`);
}

/** 爆品识别（阈值 + 统计窗口随响应一起给出）。 */
export function fetchHotItems(params: {
  windowDays?: number | undefined;
  limit?: number | undefined;
}): Promise<AiForecastHotItemView> {
  return api<AiForecastHotItemView>(`${base}/hot-items?${query(params)}`);
}

/** 机会品发现（阈值 + 统计窗口随响应一起给出）。 */
export function fetchOpportunities(params: {
  windowDays?: number | undefined;
  limit?: number | undefined;
}): Promise<AiForecastOpportunityView> {
  return api<AiForecastOpportunityView>(`${base}/opportunities?${query(params)}`);
}

// ================================================================ 展示口径

/** 语气档位。 */
export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

/**
 * 一条影响因素的语气。
 *
 * 🔴 **UP / DOWN / NEUTRAL 是三件不同的事**：NEUTRAL 不是「没影响」，
 * 它是「说明性因素」（例如样本覆盖度只影响可信度，不改变点估计）。
 */
export function factorTone(direction: string): Tone {
  if (direction === 'UP') return 'ok';
  if (direction === 'DOWN') return 'danger';
  return 'muted';
}

/** 因素方向的中文说法。 */
export function factorDirectionLabel(direction: string): string {
  if (direction === 'UP') return '推高预测';
  if (direction === 'DOWN') return '压低预测';
  return '不影响点估计';
}

/** 建议状态的语气。 */
export function suggestionTone(status: string): Tone {
  if (status === 'ADOPTED') return 'ok';
  if (status === 'REJECTED') return 'danger';
  return 'muted';
}

/** 建议状态的中文说法。 */
export function suggestionStatusLabel(status: string): string {
  if (status === 'ADOPTED') return '已采纳（仅记录决定，未执行）';
  if (status === 'REJECTED') return '已驳回';
  if (status === 'SUGGESTED') return '建议已生成';
  return status;
}

/** 历史来源的中文说法（来源必须显示 —— 「预测用的销量是哪来的」）。 */
export function historySourceLabel(source: string): string {
  if (source === 'REPORTING') return '13 分析同步';
  if (source === 'MANUAL') return '人工录入';
  if (source === 'MIXED') return '人工 + 上游混合';
  return source;
}

/** 算法名的中文说法。 */
export function algorithmLabel(code: string): string {
  if (code === 'MOVING_AVERAGE') return '移动平均';
  if (code === 'EXPONENTIAL_SMOOTHING') return '指数平滑';
  return code;
}

/** 数量（件）。 */
export function formatUnits(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const numeric = Number(value);
  if (Number.isNaN(numeric)) return String(value);
  return numeric.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

/** 比率（百分比）。 */
export function formatRate(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const numeric = Number(value);
  if (Number.isNaN(numeric)) return String(value);
  return `${(numeric * 100).toFixed(2).replace(/\.?0+$/, '')}%`;
}

/**
 * 区间的几何位置（页面据此画区间条）。
 *
 * <p>🔴 **只给一个点估计的页面 = 未完成**：这个函数把「点估计落在区间的哪里」算出来，
 * 区间宽度相对点估计的比例也给出来 —— 区间越宽，说明这个预测越不可信。
 *
 * @param lower 区间下界
 * @param point 点估计
 * @param upper 区间上界
 */
export function intervalGeometry(
  lower: number | string | null | undefined,
  point: number | string | null | undefined,
  upper: number | string | null | undefined,
): {
  lowerPct: number;
  upperPct: number;
  pointPct: number;
  /** 区间半宽相对点估计的比例（越大越不可信）。 */
  widthRatio: number;
  valid: boolean;
} {
  // ⚠️ `Number(null)` 是 0（不是 NaN）—— 直接转换会把「没有区间」当成「区间宽度为 0」，
  //   那正好是本函数要防的事。所以先判空。
  const missing = (value: number | string | null | undefined) =>
    value === null || value === undefined || value === '';
  if (missing(lower) || missing(point) || missing(upper)) {
    return { lowerPct: 0, upperPct: 0, pointPct: 0, widthRatio: 0, valid: false };
  }
  const l = Number(lower);
  const p = Number(point);
  const u = Number(upper);
  if (Number.isNaN(l) || Number.isNaN(p) || Number.isNaN(u)) {
    return { lowerPct: 0, upperPct: 0, pointPct: 0, widthRatio: 0, valid: false };
  }
  if (u <= l) {
    return { lowerPct: 0, upperPct: 100, pointPct: 50, widthRatio: 0, valid: true };
  }
  const pointPct = Math.min(100, Math.max(0, ((p - l) / (u - l)) * 100));
  const widthRatio = p === 0 ? 0 : (u - l) / 2 / Math.abs(p);
  return { lowerPct: 0, upperPct: 100, pointPct, widthRatio, valid: true };
}

/** 区间宽度的语气（相对点估计）。 */
export function intervalTone(widthRatio: number): Tone {
  if (widthRatio <= 0.15) return 'ok';
  if (widthRatio <= 0.4) return 'warn';
  return 'danger';
}

/** 一条影响因素的展示行（方向 + 取值 + 口径说明）。 */
export function factorRows(factors: AiForecastFactorRow[]): {
  label: string;
  direction: string;
  tone: Tone;
  value: string;
  explanation: string;
}[] {
  return (factors ?? []).map((factor) => ({
    label: factor.factorLabel,
    direction: factorDirectionLabel(factor.direction),
    tone: factorTone(factor.direction),
    value: factor.valueText
      ? factor.valueText
      : factor.value === null || factor.value === undefined
        ? '不适用'
        : formatUnits(factor.value),
    explanation: factor.explanation,
  }));
}

/**
 * 一条价格弹性的展示行。
 *
 * 🔴 **算不出来就是算不出来**：{@code available=false} 时显示「算不出来 + 原因」，
 * 不显示成「弹性 0」—— 那会把「没数据」说成「没有弹性」。
 */
export function elasticityRows(items: AiForecastElasticityRow[]): {
  skuCode: string;
  skuName: string;
  available: boolean;
  value: string;
  tone: Tone;
  reason: string;
  basis: string;
}[] {
  return (items ?? []).map((item) => ({
    skuCode: item.skuCode,
    skuName: item.skuName,
    available: item.available,
    value: item.available ? Number(item.elasticity ?? 0).toFixed(3) : '算不出来',
    tone: item.available ? (Number(item.elasticity ?? 0) < 0 ? 'ok' : 'warn') : 'muted',
    reason: item.reason,
    basis: item.basis,
  }));
}

/**
 * 爆品行（判定口径随行一起显示 —— 不是黑盒标签）。
 */
export function hotItemRows(items: AiForecastHotItemRow[]): {
  skuCode: string;
  skuName: string;
  detected: boolean;
  units: string;
  growth: string;
  reason: string;
  basis: string;
}[] {
  return (items ?? []).map((item) => ({
    skuCode: item.skuCode,
    skuName: item.skuName,
    detected: item.detected,
    units: formatUnits(item.windowUnits),
    growth:
      item.growthRate === null || item.growthRate === undefined ? '—' : formatRate(item.growthRate),
    reason: item.reason,
    basis: item.basis,
  }));
}

/**
 * 机会品行（趋势比 + 缺口 + 原因）。
 */
export function opportunityRows(items: AiForecastOpportunityRow[]): {
  skuCode: string;
  skuName: string;
  detected: boolean;
  trend: string;
  forecast: string;
  gap: string;
  reason: string;
  basis: string;
}[] {
  return (items ?? []).map((item) => ({
    skuCode: item.skuCode,
    skuName: item.skuName,
    detected: item.detected,
    trend:
      item.trendRatio === null || item.trendRatio === undefined
        ? '—'
        : `${Number(item.trendRatio).toFixed(2)} 倍`,
    forecast: formatUnits(item.forecastPoint),
    gap: item.gapQty === null || item.gapQty === undefined ? '—' : formatUnits(item.gapQty),
    reason: item.reason,
    basis: item.basis,
  }));
}

/**
 * 一条建议的**动作提示**（页面上写清楚「采纳不会发生什么」）。
 *
 * 🔴 蓝图加粗句「建议只作为参考输入，不自动执行」—— 页面必须把这句话变成**界面上的事实**，
 * 而不是藏在文档里。
 */
export function adviceNotice(type: string): string {
  if (type === 'REPLENISH') {
    return '仅建议，不自动执行：采纳只记录「人决定了采纳」，不会创建补货单（那是 6 采购的写入所有权）。';
  }
  if (type === 'PRICE') {
    return '仅建议，不自动执行：采纳只记录「人决定了采纳」，不会改价（那是 5 定价的写入所有权）。';
  }
  return '仅建议，不自动执行。';
}

/** 一次运行的上游连通性提示（读不到就说读不到）。 */
export function runConnectivityNote(run: AiForecastRunRow | null): string {
  if (!run) return '还没有运行过预测 —— 上游是否可读要跑一次才知道。';
  const notes: string[] = [];
  notes.push(
    run.inventoryConnected ? '在手库存已接通（7 库存）' : '在手库存**未接通**（归 7 库存）',
  );
  notes.push(
    run.inTransitConnected ? '在途净额已接通（7 库存）' : '在途净额**未接通**（归 7 库存）',
  );
  notes.push(
    run.purchaseConnected ? '采购事实已接通（6 采购）' : '采购事实**未接通**（归 6 采购）',
  );
  return notes.join(' · ');
}
