import { api } from '../../lib/api';

/**
 * 16.1.1.2 绩效指标监控 前端模型。
 *
 * 蓝图：「监控各平台核心绩效指标的趋势与阈值：订单缺陷率、迟发率、取消率、有效追踪率、
 * 响应时间、账户评级，支持自定义阈值预警与趋势预测（是否会跌破阈值）。
 * 输入：指标时间序列；阈值配置；预测模型参数。
 * 输出：指标趋势图 + 阈值预警 + 跌破预测。
 * 关系：上游 = 16.1.1.1；下游 = 1 工作台预警、9 订单履约（迟发预警前置）。
 * **预测跌破比已跌破更有价值**：提前几天知道就能提前改。
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「量不到」显示成「未知」，不显示成「0%」**。后端给 `null` 就是 `null`，
 *    前端**不重算**、不把 `null` 渲染成 `0.00%`。
 * 2. **「量纲不混」**：比值（0..1）、小时、星级是三种不同的量，
 *    渲染时按各自的 `unit` 格式化，绝不把「24 小时」显示成「2400.00%」。
 * 3. **双来源如实表达**：四项指标来自 16.1.1.1、两项指标来自本模块自有表，
 *    每条序列都显示它的来源，**不假装**六项指标来自同一张表。
 * 4. **区间口径不是瞬时点**：趋势图上的每个点带 `period_start ~ period_end`。
 * 5. **未交付的下游灰掉**：1 工作台预警、9 订单履约（迟发预警前置）当前**都未交付**，
 *    界面上如实标出，**不编**推送、**不编**履约联动。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有 channel 分支）。 */
export const BASE = 'channel/performance-metrics';

/** 六项指标（顺序与后端一致）。 */
export const METRIC_CODES = [
  'ODR',
  'LATE_SHIPMENT_RATE',
  'CANCELLATION_RATE',
  'VALID_TRACKING_RATE',
  'RESPONSE_TIME',
  'ACCOUNT_RATING',
] as const;

export type MetricCode = (typeof METRIC_CODES)[number];

/** 🔴 上游 16.1.1.1 拥有的四个码（时间序列在 account_health_metric）。 */
export const UPSTREAM_METRIC_CODES = [
  'ODR',
  'LATE_SHIPMENT_RATE',
  'CANCELLATION_RATE',
  'VALID_TRACKING_RATE',
] as const;

/** 🔴 本模块自有的两个码（时间序列在 account_health_performance_metric）。 */
export const OWN_METRIC_CODES = ['RESPONSE_TIME', 'ACCOUNT_RATING'] as const;

/** 量纲。 */
export const UNITS = {
  RATIO: '比值（0..1）',
  HOURS: '小时',
  SCORE: '星级（0..5）',
} as const;

/** 预警等级筛选的可选项。 */
export const SEVERITY_OPTIONS = [
  { value: '', label: '全部等级' },
  { value: 'CRITICAL', label: '严重（命中严重阈值）' },
  { value: 'WARNING', label: '预警（命中警告阈值）' },
  { value: 'UNKNOWN', label: '量不到（不算通过）' },
] as const;

/** 预测结论筛选的可选项。 */
export const FORECAST_RESULT_OPTIONS = [
  { value: '', label: '全部结论' },
  { value: 'WILL_BREACH', label: '会跌破阈值' },
  { value: 'ALREADY_BREACHED', label: '已经越线（现状）' },
  { value: 'NO_BREACH', label: '按当前趋势不会跌破' },
  { value: 'CANNOT_PREDICT', label: '无法预测' },
] as const;

// ==================================================================
// 类型
// ==================================================================

/** 一个未交付 / 已交付的下游或维度。 */
export interface PerformanceMetricCapabilityGap {
  moduleId: string;
  moduleName: string;
  ownerModule: string;
  available: boolean;
  impact: string;
}

/** 一项指标的归属说明（**双来源事实**的载体）。 */
export interface PerformanceMetricDescriptor {
  metricCode: string;
  metricLabel: string;
  unit: string;
  unitLabel: string;
  direction: string;
  directionLabel: string;
  ownerModule: string;
  sourceModule: string;
  sourceLabel: string;
  available: boolean;
  note: string;
}

/** 能力声明。 */
export interface PerformanceMetricCapabilities {
  gaps: PerformanceMetricCapabilityGap[];
  metricCatalog: PerformanceMetricDescriptor[];
  metricCodes: string[];
  metricSources: string[];
  severities: string[];
  units: string[];
  granularitySemantics: string;
  unknownSemantics: string;
  thresholdSemantics: string;
  trendSemantics: string;
  forecastSemantics: string;
  sourceNote: string;
  upstreamNote: string;
  downstreamNote: string;
}

/** 一个店铺在本模块里的覆盖情况。 */
export interface PerformanceMetricShop {
  shopId: string;
  shopCode: string;
  shopName: string;
  platformCode: string;
  platformName: string;
  /** 有指标记录的指标数（有几条序列） */
  seriesCount: number;
  knownPointCount: number;
  unknownPointCount: number;
}

/** 店铺范围清单（真分页）。 */
export interface PerformanceMetricShopPage {
  items: PerformanceMetricShop[];
  total: number;
  page: number;
  size: number;
}

/** 趋势序列里的一个点。 */
export interface PerformanceMetricPoint {
  periodStart: string;
  periodEnd: string;
  /** null = 该周期量不到（**不是 0**） */
  value: number | null;
  known: boolean;
  source: string | null;
  sourceLabel: string | null;
  capturedAt: string | null;
  note: string | null;
}

/** 一条趋势序列（一个店铺 + 一个指标）。 */
export interface PerformanceMetricSeries {
  shopId: string;
  shopCode: string;
  shopName: string;
  platformCode: string;
  metricCode: string;
  metricLabel: string;
  unit: string;
  unitLabel: string;
  /** 时间序列来自哪个模块（**双来源事实**） */
  sourceModule: string;
  sourceLabel: string;
  /** PERIOD_RANGE = 区间口径 */
  granularity: string;
  points: PerformanceMetricPoint[];
  knownPointCount: number;
  unknownPointCount: number;
  /** 分母只算量到的点；一个都没量到时为 null */
  average: number | null;
  averageNote: string;
  latestValue: number | null;
  latestPeriodStart: string | null;
  latestPeriodEnd: string | null;
  /** 不可知时为 null */
  latestChange: number | null;
  changeNote: string;
  note: string;
}

/** 趋势序列清单（真分页）。 */
export interface PerformanceMetricTrendPage {
  items: PerformanceMetricSeries[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 一条生效中的预警阈值。 */
export interface PerformanceMetricAlertRule {
  metricCode: string;
  metricLabel: string;
  direction: string;
  directionLabel: string;
  unit: string;
  unitLabel: string;
  warnValue: number;
  criticalValue: number;
  ruleVersion: number;
  ruleSource: string;
  ruleSourceLabel: string;
  builtin: boolean;
  editable: boolean;
  effectiveFrom: string | null;
  note: string;
}

/** 预警阈值清单。 */
export interface PerformanceMetricAlertRuleList {
  items: PerformanceMetricAlertRule[];
  note: string;
}

/** 预警阈值修改的入参。 */
export interface PerformanceMetricAlertRuleInput {
  direction: string;
  warnValue: number;
  criticalValue: number;
  note: string;
}

/** 一条阈值预警凭据（带当时用到的阈值快照）。 */
export interface PerformanceMetricAlert {
  alertId: string;
  evaluationId: string;
  shopId: string;
  shopCode: string | null;
  shopName: string | null;
  metricCode: string;
  metricLabel: string;
  severity: string;
  severityLabel: string;
  unit: string;
  unitLabel: string;
  metricValue: number | null;
  metricValueText: string;
  thresholdKind: string | null;
  thresholdValue: number | null;
  thresholdValueText: string;
  direction: string;
  periodStart: string;
  periodEnd: string;
  evaluatedAt: string;
  ruleVersion: number;
  ruleSource: string;
  ruleSourceLabel: string;
  thresholds: Record<string, unknown>;
  message: string;
}

/** 预警凭据清单（真分页）。 */
export interface PerformanceMetricAlertPage {
  items: PerformanceMetricAlert[];
  total: number;
  page: number;
  size: number;
  /** 各等级计数覆盖整个筛选结果集（不是当前页） */
  criticalCount: number;
  warningCount: number;
  unknownCount: number;
  note: string;
}

/** 一次预警评估的结果。 */
export interface PerformanceMetricAlertEvaluation {
  evaluationId: string;
  shopId: string;
  shopCode: string | null;
  shopName: string | null;
  evaluatedAt: string;
  alerts: PerformanceMetricAlert[];
  evaluatedMetricCount: number;
  knownMetricCount: number;
  unknownMetricCount: number;
  note: string;
}

/** 预测里用到的那个周期。 */
export interface PerformanceMetricUsedPeriod {
  index: number;
  periodStart: string;
  periodEnd: string;
  value: number | null;
}

/** 一条跌破预测凭据。 */
export interface PerformanceMetricForecast {
  forecastId: string;
  shopId: string;
  shopCode: string | null;
  shopName: string | null;
  metricCode: string;
  metricLabel: string;
  unit: string;
  unitLabel: string;
  method: string;
  methodNote: string;
  result: string;
  resultLabel: string;
  resultReason: string;
  breachLevel: string | null;
  breachLevelLabel: string | null;
  breachInPeriods: number | null;
  predictedValue: number | null;
  predictedValueText: string;
  thresholdValue: number | null;
  thresholdValueText: string;
  thresholdKind: string | null;
  direction: string;
  directionLabel: string;
  knownPoints: number;
  unknownPoints: number;
  windowPoints: number;
  usedPeriods: PerformanceMetricUsedPeriod[];
  slope: number | null;
  ruleVersion: number;
  ruleSource: string;
  ruleSourceLabel: string;
  thresholds: Record<string, unknown>;
  forecastedAt: string;
}

/** 预测凭据清单（真分页）。 */
export interface PerformanceMetricForecastPage {
  items: PerformanceMetricForecast[];
  total: number;
  page: number;
  size: number;
  /** 各结论计数覆盖整个筛选结果集（不是当前页） */
  willBreachCount: number;
  alreadyBreachedCount: number;
  noBreachCount: number;
  cannotPredictCount: number;
  note: string;
}

/** 一次预测的结果（店铺 × 全部指标）。 */
export interface PerformanceMetricForecastRun {
  shopId: string;
  shopCode: string | null;
  shopName: string | null;
  forecastedAt: string;
  results: PerformanceMetricForecast[];
  note: string;
}

/** 预测的入参（**预测模型参数**）。 */
export interface PerformanceMetricForecastInput {
  windowPoints?: number;
  horizonPeriods?: number;
}

/** 手工录入一条本模块自有指标的入参。 */
export interface PerformanceMetricMetricInput {
  metricCode: string;
  metricValue: number | null;
  periodStart: string;
  periodEnd: string;
  note: string;
}

// ==================================================================
// API
// ==================================================================

export async function fetchCapabilities(): Promise<PerformanceMetricCapabilities> {
  return api<PerformanceMetricCapabilities>(`${BASE}/capabilities`);
}

export async function fetchShops(
  page = 0,
  size = 50,
  filters: { platformCode?: string; keyword?: string } = {},
): Promise<PerformanceMetricShopPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.keyword) query.set('keyword', filters.keyword);
  return api<PerformanceMetricShopPage>(`${BASE}/shops?${query.toString()}`);
}

export interface TrendFilters {
  platformCode?: string;
  keyword?: string;
  shopId?: string;
  metricCode?: string;
  window?: number;
}

export async function fetchTrends(
  page = 0,
  size = 10,
  filters: TrendFilters = {},
): Promise<PerformanceMetricTrendPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.shopId) query.set('shopId', filters.shopId);
  if (filters.metricCode) query.set('metricCode', filters.metricCode);
  if (filters.window) query.set('window', String(filters.window));
  return api<PerformanceMetricTrendPage>(`${BASE}/trends?${query.toString()}`);
}

export async function fetchShopTrends(
  shopId: string,
  metricCode?: string,
  window?: number,
): Promise<PerformanceMetricSeries[]> {
  const query = new URLSearchParams();
  if (metricCode) query.set('metricCode', metricCode);
  if (window) query.set('window', String(window));
  const suffix = query.toString();
  return api<PerformanceMetricSeries[]>(
    suffix ? `${BASE}/shops/${shopId}/trends?${suffix}` : `${BASE}/shops/${shopId}/trends`,
  );
}

export async function fetchAlertRules(): Promise<PerformanceMetricAlertRuleList> {
  return api<PerformanceMetricAlertRuleList>(`${BASE}/alert-rules`);
}

export async function saveAlertRule(
  metricCode: string,
  input: PerformanceMetricAlertRuleInput,
): Promise<PerformanceMetricAlertRule> {
  return api<PerformanceMetricAlertRule>(`${BASE}/alert-rules/${metricCode}`, 'PUT', input);
}

export interface AlertFilters {
  shopId?: string;
  metricCode?: string;
  severity?: string;
}

export async function fetchAlerts(
  page = 0,
  size = 20,
  filters: AlertFilters = {},
): Promise<PerformanceMetricAlertPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.shopId) query.set('shopId', filters.shopId);
  if (filters.metricCode) query.set('metricCode', filters.metricCode);
  if (filters.severity) query.set('severity', filters.severity);
  return api<PerformanceMetricAlertPage>(`${BASE}/alerts?${query.toString()}`);
}

export async function evaluateAlerts(shopId: string): Promise<PerformanceMetricAlertEvaluation> {
  return api<PerformanceMetricAlertEvaluation>(
    `${BASE}/shops/${shopId}/alert-evaluations`,
    'POST',
    {},
  );
}

export interface ForecastFilters {
  shopId?: string;
  metricCode?: string;
  result?: string;
}

export async function fetchForecasts(
  page = 0,
  size = 20,
  filters: ForecastFilters = {},
): Promise<PerformanceMetricForecastPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.shopId) query.set('shopId', filters.shopId);
  if (filters.metricCode) query.set('metricCode', filters.metricCode);
  if (filters.result) query.set('result', filters.result);
  return api<PerformanceMetricForecastPage>(`${BASE}/forecasts?${query.toString()}`);
}

export async function forecastShop(
  shopId: string,
  input: PerformanceMetricForecastInput = {},
): Promise<PerformanceMetricForecastRun> {
  return api<PerformanceMetricForecastRun>(`${BASE}/shops/${shopId}/forecasts`, 'POST', input);
}

export async function fetchLatestForecast(shopId: string): Promise<PerformanceMetricForecastRun> {
  return api<PerformanceMetricForecastRun>(`${BASE}/shops/${shopId}/forecast`);
}

export async function recordOwnMetric(
  shopId: string,
  input: PerformanceMetricMetricInput,
): Promise<PerformanceMetricSeries> {
  return api<PerformanceMetricSeries>(`${BASE}/shops/${shopId}/metrics`, 'POST', input);
}

// ==================================================================
// 展示口径（**不重算后端的结论**）
// ==================================================================

/** 指标的人话名（后端已给一份，这里给一份本地兜底）。 */
export function metricLabel(code: string): string {
  switch (code) {
    case 'ODR':
      return '订单缺陷率（ODR）';
    case 'LATE_SHIPMENT_RATE':
      return '迟发率';
    case 'CANCELLATION_RATE':
      return '取消率';
    case 'VALID_TRACKING_RATE':
      return '有效追踪率';
    case 'RESPONSE_TIME':
      return '响应时间';
    case 'ACCOUNT_RATING':
      return '账户评级';
    default:
      return code;
  }
}

/**
 * 🔴 数值展示：**按量纲格式化**，绝不把「24 小时」显示成「2400.00%」。
 *
 * <p>`null` 显示成「未知」，**绝不显示成 0** —— 后端给 `null` 就是「这个周期没测到」，
 * 渲染成 0% 等于把「没查」写成「这项是 0」。
 */
export function metricValueText(
  value: number | null | undefined,
  unit: string | null | undefined,
): string {
  if (value === null || value === undefined) return '未知';
  if (unit === 'HOURS') return `${value.toFixed(2)} 小时`;
  if (unit === 'SCORE') return `${value.toFixed(2)} 分`;
  return `${(value * 100).toFixed(2)}%`;
}

/** 量纲的人话。 */
export function unitLabel(unit: string | null | undefined): string {
  if (unit === 'HOURS') return UNITS.HOURS;
  if (unit === 'SCORE') return UNITS.SCORE;
  return UNITS.RATIO;
}

/** 指标来源的人话 + 是否手工。 */
export function sourceLabel(source: string | null | undefined): string {
  if (!source) return '未采集';
  if (source === 'MANUAL') return '人工录入';
  if (source === 'PLATFORM_SYNC') return '平台同步';
  return source;
}

/** 🔴 时间序列的来源（双来源事实）。 */
export function metricSourceLabel(metricCode: string): string {
  return (OWN_METRIC_CODES as readonly string[]).includes(metricCode)
    ? '本模块自有'
    : '上游 16.1.1.1';
}

/** 阈值来源的人话。 */
export function ruleSourceLabel(source: string | null | undefined): string {
  switch (source) {
    case 'BUILTIN_DEFAULT':
      return '模块内置默认阈值';
    case 'TENANT_CUSTOM':
      return '本模块租户自定义阈值';
    case 'UPSTREAM_TENANT_CUSTOM':
      return '16.1.1.1 租户阈值';
    case 'MIXED':
      return '部分自定义 / 部分默认';
    default:
      return source ?? '未声明';
  }
}

/** 方向的人话。 */
export function directionLabel(direction: string | null | undefined): string {
  if (direction === 'BELOW_IS_BAD') return '低于阈值即危险';
  if (direction === 'ABOVE_IS_BAD') return '高于阈值即危险';
  return direction ?? '未声明';
}

/** 预警等级的人话。 */
export function severityLabel(severity: string | null | undefined): string {
  switch (severity) {
    case 'CRITICAL':
      return '严重（命中严重阈值）';
    case 'WARNING':
      return '预警（命中警告阈值）';
    case 'UNKNOWN':
      return '量不到（不算通过）';
    default:
      return severity ?? '未声明';
  }
}

/** 预警等级的徽章色调。 */
export function severityTone(
  severity: string | null | undefined,
): 'danger' | 'warning' | 'success' | 'neutral' {
  switch (severity) {
    case 'CRITICAL':
      return 'danger';
    case 'WARNING':
      return 'warning';
    case 'UNKNOWN':
      return 'neutral';
    default:
      return 'success';
  }
}

/** 预测结论的人话。 */
export function forecastResultLabel(result: string | null | undefined): string {
  switch (result) {
    case 'WILL_BREACH':
      return '会跌破阈值';
    case 'NO_BREACH':
      return '按当前趋势不会跌破';
    case 'ALREADY_BREACHED':
      return '已经越线（现状）';
    case 'CANNOT_PREDICT':
      return '无法预测';
    default:
      return result ?? '未声明';
  }
}

/** 预测结论的徽章色调。 */
export function forecastResultTone(
  result: string | null | undefined,
): 'danger' | 'warning' | 'success' | 'neutral' {
  switch (result) {
    case 'WILL_BREACH':
      return 'danger';
    case 'ALREADY_BREACHED':
      return 'warning';
    case 'NO_BREACH':
      return 'success';
    case 'CANNOT_PREDICT':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 越线档位的人话。 */
export function breachLevelLabel(level: string | null | undefined): string {
  switch (level) {
    case 'CRITICAL':
      return '严重档（越过严重阈值）';
    case 'WARN':
      return '警告档（越过警告阈值）';
    default:
      return level ?? '';
  }
}

/** 阈值的档位人话。 */
export function thresholdKindLabel(kind: string | null | undefined): string {
  switch (kind) {
    case 'CRITICAL':
      return '命中严重阈值';
    case 'WARN':
      return '命中警告阈值';
    case 'UNKNOWN':
      return '量不到';
    default:
      return kind ?? '';
  }
}

/**
 * 🔴 空态文案：**三态不同**。
 *
 * @param kind 'NO_SHOP' 租户下还没有店铺 /
 *             'NO_FILTER_MATCH' 当前筛选条件下没有匹配 /
 *             'NO_SERIES' 有店铺但**还没有任何指标记录**
 */
export function emptyState(kind: 'NO_SHOP' | 'NO_FILTER_MATCH' | 'NO_SERIES'): {
  title: string;
  description: string;
} {
  switch (kind) {
    case 'NO_SHOP':
      return {
        title: '这个租户下还没有店铺',
        description:
          '绩效监控是**按店铺**展示的。先在「渠道与店铺」里建好店铺，这里才会有趋势与预警。',
      };
    case 'NO_FILTER_MATCH':
      return {
        title: '当前筛选条件下没有匹配的记录',
        description:
          '把筛选条件放宽再看看。**这不等于「没有数据」** —— 换个关键词或清掉筛选就能看到别的。',
      };
    case 'NO_SERIES':
      return {
        title: '还没有任何指标记录',
        description:
          '平台账号绩效同步**未接入**（归卷 02 同步运行），所以现在一个真实指标都没有。' +
          '可以先手工录入你从平台后台看到的数字 —— 录入的条目会标成「人工录入」，不会冒充平台同步。',
      };
    default:
      return { title: '', description: '' };
  }
}

/**
 * 🔴 空态的判定：**「筛选无结果」与「还没有指标」必须分开**。
 *
 * @param totalShops 当前筛选下租户里有多少家店
 * @param filteredTotal 当前筛选下有多少条趋势序列
 * @param anySeries 租户里**有没有**店铺带指标记录（不限当前筛选）
 * @param filtered 当前是否**带着筛选条件**
 */
export function emptyStateKind(
  totalShops: number,
  filteredTotal: number,
  anySeries: boolean,
  filtered: boolean,
): 'NO_SHOP' | 'NO_FILTER_MATCH' | 'NO_SERIES' {
  if (totalShops === 0) return filtered ? 'NO_FILTER_MATCH' : 'NO_SHOP';
  if (filteredTotal === 0) return anySeries ? 'NO_FILTER_MATCH' : 'NO_SERIES';
  return 'NO_FILTER_MATCH';
}
