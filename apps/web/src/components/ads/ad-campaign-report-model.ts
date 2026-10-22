import { api } from '../inventory/api';

/**
 * 14.3.1.1 活动报表 前端模型。
 *
 * 蓝图：「活动维度效果报表：花费、曝光、点击、CTR、CPC、订单、转化率、销售额、ACOS/ROAS/TACOS，
 * 按日/周/月与多维对比，支持导出。输入：时间范围；维度；广告数据。
 * 输出：报表表 + 趋势图 + 口径说明。关系：上游 = 14.1.1.1；下游 = 14.3.2.1。
 * **ACOS/ROAS/TACOS 三个口径要在表头写明定义**，否则跨部门沟通会各说各话。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **口径写在表头，不是写在注释里**。`metricDefinitions` 随报表一起返回，每一项带
 *    `formula`（口径定义）。表头直接渲染它 —— 跨部门沟通时「ACOS 到底是哪个除哪个」
 *    必须是同一份定义，而不是各人自己解释一遍。
 * 2. **TACOS 的「没有」是一个被显式声明的事实**。`available=false` 的指标渲染成
 *    「未接入（原因）」，**不是** 0、也不是空白。没有分母却给一个数，比不给更糟。
 * 3. **不可比 ≠ 0**。比率列的 `null` 渲染成「不可比」，因为分母为 0 时压根算不出来；
 *    `0` 才是「可比但真的是零」。把两者渲染成同一个 0.00% 就是把「算不出来」说成「真的是零」。
 * 4. **不同币种不合并**。汇总按币种拆开（服务端没有汇率源），界面上**按币种分块展示**，
 *    不做跨币种相加 —— 前端一相加就造出一个服务端从没算过的假总数。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * ACOS / ROAS / CTR / CPC / 转化率与汇总都是服务端 `BigDecimal` 的事，
 * 前端算一遍就会引入二进制浮点误差，而且会让「界面上的 ACOS」和「报表里的 ACOS」对不上。
 */

/** 金额与数量一律 number（JSON number），只原样展示。 */
export type AdReportMoney = number | null;

/** 🔴 null = 不可比（分母为 0），与 `0`（可比但真的是零）含义相反。 */
export type AdReportRatio = number | null;

export interface AdReportCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

/** 🔴 单个指标的口径定义 —— 表头就是从这里渲染的。 */
export interface AdReportMetricDefinition {
  metric: string;
  label: string;
  /** false = 当前不出数值（没有分母），不是「这一列是 0」。 */
  available: boolean;
  formula: string;
  source: string;
  ownerModule: string;
  unavailableReason: string;
}

export interface AdCampaignReportCapabilities {
  dimensions: AdReportCapabilityDimension[];
  metricDefinitions: AdReportMetricDefinition[];
  granularities: string[];
  dimensions_: string[];
  sortOptions: string[];
  emptyReasons: string[];
  metricDefinitionNote: string;
  dataAsOfNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface AdCampaignReportOption {
  value: string;
  label: string;
}

export interface AdCampaignReportOptions {
  granularities: AdCampaignReportOption[];
  dimensions: AdCampaignReportOption[];
  platforms: AdCampaignReportOption[];
  shops: AdCampaignReportOption[];
  internalAdTypes: AdCampaignReportOption[];
  statuses: AdCampaignReportOption[];
  sorts: AdCampaignReportOption[];
  metricDefinitions: AdReportMetricDefinition[];
  metricDefinitionNote: string;
  rangeLimitNote: string;
}

export interface AdCampaignReportRow {
  dimension: string;
  dimensionKey: string;
  dimensionLabel: string;
  currency: string;
  platformName: string | null;
  shopName: string | null;
  externalCampaignId: string | null;
  impressions: AdReportMoney;
  clicks: AdReportMoney;
  orders: AdReportMoney;
  spend: AdReportMoney;
  sales: AdReportMoney;
  ctr: AdReportRatio;
  cpc: AdReportRatio;
  cvr: AdReportRatio;
  acos: AdReportRatio;
  roas: AdReportRatio;
  metricFrom: string | null;
  metricTo: string | null;
}

export interface AdCampaignReportSummary {
  currency: string;
  groupCount: number;
  totalImpressions: AdReportMoney;
  totalClicks: AdReportMoney;
  totalOrders: AdReportMoney;
  totalSpend: AdReportMoney;
  totalSales: AdReportMoney;
  ctr: AdReportRatio;
  cpc: AdReportRatio;
  cvr: AdReportRatio;
  acos: AdReportRatio;
  roas: AdReportRatio;
  metricFrom: string | null;
  metricTo: string | null;
  metricDefinitionNote: string;
}

export interface AdCampaignReportPage {
  items: AdCampaignReportRow[];
  total: number;
  page: number;
  size: number;
  granularity: string;
  dimension: string;
  /** 🔴 按币种拆开：没有汇率源，不同币种不合并。 */
  summaries: AdCampaignReportSummary[];
  dataAsOf: string | null;
  dataAsOfNote: string;
  metricDefinitions: AdReportMetricDefinition[];
  emptyReason: string | null;
  emptyNote: string;
  metricDefinitionNote: string;
}

export interface AdCampaignReportTrendPoint {
  periodStart: string;
  impressions: AdReportMoney;
  clicks: AdReportMoney;
  orders: AdReportMoney;
  spend: AdReportMoney;
  sales: AdReportMoney;
  ctr: AdReportRatio;
  cpc: AdReportRatio;
  cvr: AdReportRatio;
  acos: AdReportRatio;
  roas: AdReportRatio;
}

/** 一条趋势线（按币种拆开）。 */
export interface AdCampaignReportTrendSeries {
  currency: string;
  points: AdCampaignReportTrendPoint[];
}

export interface AdCampaignReportTrend {
  granularity: string;
  series: AdCampaignReportTrendSeries[];
  dataAsOf: string | null;
  metricDefinitions: AdReportMetricDefinition[];
  emptyReason: string | null;
  emptyNote: string;
  metricDefinitionNote: string;
}

export interface AdCampaignReportExportRow {
  id: string;
  requestedBy: string;
  granularity: string;
  dimension: string;
  platformId: string | null;
  shopCode: string | null;
  internalAdType: string | null;
  status: string | null;
  dateFrom: string | null;
  dateTo: string | null;
  rowCount: number;
  dataAsOf: string | null;
  metricDefinitionNote: string;
  createdAt: string;
}

export interface AdCampaignReportExportPage {
  items: AdCampaignReportExportRow[];
  total: number;
  page: number;
  size: number;
}

/**
 * 导出链接的查询形状。
 *
 * 🔴 与 {@link AdCampaignReportQuery} 分开是因为 `exactOptionalPropertyTypes`：
 * 「这个筛选项没填」是 `undefined`，而可选属性不接受显式的 `undefined`。
 * 导出链接从当前筛选拼出来，每一个键都可能是空的。
 */
export interface AdCampaignReportExportQuery {
  from?: string | undefined;
  to?: string | undefined;
  granularity?: string | undefined;
  dimension?: string | undefined;
  platformId?: string | undefined;
  shopCode?: string | undefined;
  internalAdType?: string | undefined;
  status?: string | undefined;
}

export interface AdCampaignReportQuery {
  from?: string;
  to?: string;
  granularity?: string;
  dimension?: string;
  sort?: string;
  platformId?: string;
  shopCode?: string;
  internalAdType?: string;
  status?: string;
  page?: number;
  size?: number;
}

function queryString(query: object): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : '';
}

export function fetchAdCampaignReportCapabilities(): Promise<AdCampaignReportCapabilities> {
  return api<AdCampaignReportCapabilities>('channel/ad-reports/capabilities');
}

export function fetchAdCampaignReportOptions(): Promise<AdCampaignReportOptions> {
  return api<AdCampaignReportOptions>('channel/ad-reports/options');
}

export function fetchAdCampaignReportRows(
  query: AdCampaignReportQuery = {},
): Promise<AdCampaignReportPage> {
  return api<AdCampaignReportPage>(`channel/ad-reports/rows${queryString(query)}`);
}

export function fetchAdCampaignReportTrend(
  query: AdCampaignReportQuery = {},
): Promise<AdCampaignReportTrend> {
  return api<AdCampaignReportTrend>(`channel/ad-reports/trend${queryString(query)}`);
}

export function fetchAdCampaignReportExports(
  page = 0,
  size = 20,
): Promise<AdCampaignReportExportPage> {
  return api<AdCampaignReportExportPage>(
    `channel/ad-reports/exports${queryString({ page, size })}`,
  );
}

/**
 * 导出链接。
 *
 * 🔴 导出走 GET + query（导出结果与当前筛选完全一致），浏览器同源代理会把
 * `text/csv` 原样透传（不走 JSON.parse，否则 CSV 会被重新序列化成一串带引号的转义文本）。
 */
export function reportExportUrl(query: AdCampaignReportExportQuery = {}): string {
  return `/api/channel/ad-reports/export${queryString(query)}`;
}

// ==================================================================
// 展示辅助
// ==================================================================

export function reportMoney(value: AdReportMoney | undefined, currency?: string): string {
  if (value === null || value === undefined) return '—';
  const fixed = value.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

export function reportQuantity(value: AdReportMoney | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

/**
 * 派生比率展示：**不可比显示「不可比」，不是 0.00%**。
 *
 * 🔴 0 是「可比，但真的是零」；null 是「压根算不出来」（分母为 0）。
 * 渲染成 0% 会让人以为「这条活动一次点击都没有」，而真相是「没有展示量，比率无从计算」。
 */
export function reportRatio(value: AdReportRatio | undefined, asPercent = true): string {
  if (value === null || value === undefined) return '不可比';
  return asPercent ? `${(value * 100).toFixed(2)}%` : value.toFixed(4);
}

export function reportRatioTone(value: AdReportRatio | undefined): string {
  return value === null || value === undefined ? 'text-muted-foreground' : 'text-foreground';
}

/** 指标的口径定义（找不到就返回 undefined）。 */
export function metricDefinitionOf(
  definitions: AdReportMetricDefinition[],
  metric: string,
): AdReportMetricDefinition | undefined {
  return definitions.find((d) => d.metric === metric);
}

/**
 * 表头文字：**指标名 + 口径定义**。
 *
 * 🔴 蓝图原话「三个口径要在表头写明定义」—— 定义就写在表头里，
 * 不用另找一份文档、不用问人。
 */
export function metricHeader(definition: AdReportMetricDefinition | undefined): string {
  if (!definition) return '';
  return `${definition.label}（${definition.formula}）`;
}

/**
 * 未接入指标在表头 / 单元格里的文字。
 *
 * 🔴 「未接入（原因）」而不是 0、也不是空白：没有分母却给一个数，比不给更糟。
 */
export function unavailableMetricText(definition: AdReportMetricDefinition | undefined): string {
  if (!definition) return '未接入';
  return `未接入（${definition.ownerModule}：${definition.unavailableReason}）`;
}

/** 分组维度的显示名。 */
export function reportDimensionLabel(dimension: string): string {
  if (dimension === 'PLATFORM') return '按平台';
  if (dimension === 'SHOP') return '按店铺';
  if (dimension === 'AD_TYPE') return '按归一后广告类型';
  if (dimension === 'CURRENCY') return '按币种';
  return '按活动';
}

/**
 * 空态文案：**三种「空」必须分开说**。
 *
 * 🔴 「还没同步到数据」与「这个区间没有投放」与「被筛掉了」是三件不同的事，
 * 渲染成同一种空态就是把「没数据」说成「没投放」。
 */
export function reportEmptyText(reason: string | null, note: string): string {
  if (!reason) return '';
  if (reason === 'NO_SYNCED_DATA') {
    return note || '还没有任何一次成功的广告数据同步 —— 这不是「没有投放」，是还没同步到数据。';
  }
  if (reason === 'NO_METRICS_IN_RANGE') {
    return (
      note || '同步成功过，但所选时间区间里没有任何投放指标 —— 这次是真的「这段时间没有投放数据」。'
    );
  }
  if (reason === 'FILTERED_EMPTY') {
    return note || '这个时间区间里确实有广告数据，但被当前的筛选条件全部排除了。放宽条件再试。';
  }
  return note;
}

export function reportTimestampText(value: string | null): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

// ==================================================================
// 表单校验
// ==================================================================

export interface AdReportRangeValidation {
  ok: boolean;
  errors: string[];
}

/**
 * 时间范围校验。
 *
 * 🔴 时间范围是**必填**的：没有区间就是全表扫，而蓝图加粗句点名「不能实时全表扫」。
 * 前端只做形状校验（缺不填、起止颠倒），区间跨度的上限由服务端判。
 */
export function validateReportRange(from: string, to: string): AdReportRangeValidation {
  const errors: string[] = [];
  if (!from.trim()) errors.push('请选择统计起始日');
  if (!to.trim()) errors.push('请选择统计截止日');
  if (from.trim() && to.trim() && from > to) errors.push('统计起始日不能晚于截止日');
  return { ok: errors.length === 0, errors };
}
