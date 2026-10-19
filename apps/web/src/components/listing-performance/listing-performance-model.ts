import { api } from '../../lib/api';

/**
 * 4.5.1.1 Listing 表现 前端模型。
 *
 * 蓝图：「单个 listing 的表现明细：曝光、点击、转化率、销量、销售额、Session、退货率、
 * 评分变化，支持时间范围对比。输出：表现指标 + 趋势图；下钻到 13 产品表现。
 * **表现数据来自平台而非内部订单**，与内部订单口径不同，展示时需标注来源。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **数据来源必须标在界面上**。页面顶部固定显示「平台口径（平台回报数据，非内部订单）」，
 *    并且**每个指标卡**都标了「平台口径」。蓝图加粗句「展示时需标注来源」是硬要求。
 *    运营把平台销量当成内部订单行数，出来的选品结论就是错的。
 *
 * 2. **前端不重算比率**。`ctr` / `conversionRate` / `returnRate` 是服务端按固定
 *    分子分母算好的结论，**分母为 0 时是 null**。null 显示成「—」，
 *    **绝不显示成 0%** —— 「没有样本」与「真的是 0」是两件事。
 *
 * 3. **变化率同理**。上期为 0 时 `changeRate` 是 null（不是 0、不是 +∞），
 *    页面显示「—」而不是「0%」或「∞%」。
 *
 * 4. **趋势没有数据时是空态，不是平线**。一条水平的假曲线会让人以为
 *    「这段时间表现平稳」，而真相是「平台没报数据」。
 *
 * 5. **两种空态文案不同**：「还没同步过」与「同步过但这个区间没数据」是**两回事**，
 *    不能共用一句话，也不能共用一个假图表。
 *
 * 6. **未交付的下钻显示「未接入」并写明归属模块**（13 产品表现 / 14 广告），
 *    不显示假数据、也不给一个必然 404 的按钮。
 */

export interface ListingPerformanceCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface ListingPerformanceCapabilities {
  dimensions: ListingPerformanceCapabilityDimension[];
  sources: string[];
  ingestChannels: string[];
  granularities: string[];
  metricDefinitions: string;
  dataSourceNote: string;
  relationNote: string;
}

/** 🔴 一段区间的表现指标（**平台口径**，非内部订单）。 */
export interface ListingPerformanceMetrics {
  impressions: number | null;
  clicks: number | null;
  ctr: number | null;
  sessions: number | null;
  unitsOrdered: number | null;
  salesAmount: number | null;
  conversionRate: number | null;
  returnsCount: number | null;
  returnAmount: number | null;
  returnRate: number | null;
  rating: number | null;
  ratingCount: number | null;
  ratingChange: number | null;
  snapshotCount: number;
  currency: string;
}

/** 一个指标的本期 vs 上期对比。 */
export interface ListingPerformanceComparisonEntry {
  metric: string;
  current: number | null;
  previous: number | null;
  change: number | null;
  /** 🔴 上期为 0 时是 null（不是 0、不是 +∞）。 */
  changeRate: number | null;
  unit: 'COUNT' | 'AMOUNT' | 'RATE' | 'SCORE';
}

export interface ListingPerformanceComparison {
  entries: ListingPerformanceComparisonEntry[];
}

export interface ListingPerformanceDetail {
  listingId: string;
  externalItemId: string;
  title: string;
  shopCode: string;
  platformCode: string;
  /** 🔴 平台口径标识（PLATFORM_REPORTED）。 */
  dataSource: string;
  /** 🔴 来源说明（页面顶部显示）。 */
  sourceNote: string;
  periodFrom: string;
  periodTo: string;
  comparePeriodFrom: string;
  comparePeriodTo: string;
  current: ListingPerformanceMetrics;
  previous: ListingPerformanceMetrics;
  comparison: ListingPerformanceComparison;
  hasData: boolean;
  emptyReason: string;
  syncCount: number;
  lastSyncAt: string | null;
}

export interface ListingPerformanceSnapshotRow {
  snapshotId: string;
  listingId: string;
  externalItemId: string;
  title: string;
  shopCode: string;
  platformCode: string;
  statDate: string;
  impressions: number | null;
  clicks: number | null;
  ctr: number | null;
  sessions: number | null;
  unitsOrdered: number | null;
  salesAmount: number | null;
  conversionRate: number | null;
  returnsCount: number | null;
  returnAmount: number | null;
  returnRate: number | null;
  rating: number | null;
  ratingCount: number | null;
  source: string;
  ingestChannel: string;
  currency: string;
  revision: number;
  receivedAt: string;
  updatedAt: string;
}

export interface ListingPerformanceSnapshotPage {
  items: ListingPerformanceSnapshotRow[];
  page: number;
  size: number;
  total: number;
  /** 🔴 本筛选范围内的同步结果条数（区分两种空态用）。 */
  syncCount: number;
}

export interface ListingPerformanceTrendPoint {
  bucket: string;
  impressions: number | null;
  clicks: number | null;
  ctr: number | null;
  sessions: number | null;
  unitsOrdered: number | null;
  salesAmount: number | null;
  conversionRate: number | null;
  returnsCount: number | null;
  returnAmount: number | null;
  returnRate: number | null;
  rating: number | null;
  ratingCount: number | null;
}

export interface ListingPerformanceTrend {
  listingId: string;
  granularity: string;
  from: string;
  to: string;
  dataSource: string;
  sourceNote: string;
  /** 🔴 没有数据时是**空数组**（不是假曲线）。 */
  points: ListingPerformanceTrendPoint[];
  hasData: boolean;
}

// ================================================================ 取数

export async function fetchCapabilities(): Promise<ListingPerformanceCapabilities> {
  return api<ListingPerformanceCapabilities>('channel/listing-performance/capabilities');
}

export async function fetchSnapshots(params: {
  listingId?: string;
  shopCode?: string;
  platformCode?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}): Promise<ListingPerformanceSnapshotPage> {
  const query = new URLSearchParams();
  if (params.listingId) query.set('listingId', params.listingId);
  if (params.shopCode) query.set('shopCode', params.shopCode);
  if (params.platformCode) query.set('platformCode', params.platformCode);
  if (params.from) query.set('from', params.from);
  if (params.to) query.set('to', params.to);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<ListingPerformanceSnapshotPage>(`channel/listing-performance?${query.toString()}`);
}

export async function fetchDetail(
  listingId: string,
  params: { from?: string; to?: string; compareFrom?: string; compareTo?: string },
): Promise<ListingPerformanceDetail> {
  const query = new URLSearchParams();
  if (params.from) query.set('from', params.from);
  if (params.to) query.set('to', params.to);
  if (params.compareFrom) query.set('compareFrom', params.compareFrom);
  if (params.compareTo) query.set('compareTo', params.compareTo);
  const suffix = query.toString() ? `?${query.toString()}` : '';
  return api<ListingPerformanceDetail>(`channel/listing-performance/${listingId}${suffix}`);
}

export async function fetchTrend(
  listingId: string,
  params: { from?: string; to?: string; granularity?: string },
): Promise<ListingPerformanceTrend> {
  const query = new URLSearchParams();
  if (params.from) query.set('from', params.from);
  if (params.to) query.set('to', params.to);
  if (params.granularity) query.set('granularity', params.granularity);
  const suffix = query.toString() ? `?${query.toString()}` : '';
  return api<ListingPerformanceTrend>(`channel/listing-performance/${listingId}/trend${suffix}`);
}

// ================================================================ 纯函数（可单测）

/** 指标键 → 中文名与单位。顺序与后端对比表一致。 */
export const METRIC_OPTIONS = [
  { value: 'impressions', label: '曝光', unit: 'COUNT' },
  { value: 'clicks', label: '点击', unit: 'COUNT' },
  { value: 'ctr', label: '点击率', unit: 'RATE' },
  { value: 'sessions', label: 'Session', unit: 'COUNT' },
  { value: 'unitsOrdered', label: '销量', unit: 'COUNT' },
  { value: 'salesAmount', label: '销售额', unit: 'AMOUNT' },
  { value: 'conversionRate', label: '转化率', unit: 'RATE' },
  { value: 'returnsCount', label: '退货件数', unit: 'COUNT' },
  { value: 'returnAmount', label: '退货金额', unit: 'AMOUNT' },
  { value: 'returnRate', label: '退货率', unit: 'RATE' },
  { value: 'rating', label: '评分', unit: 'SCORE' },
  { value: 'ratingCount', label: '评价数', unit: 'COUNT' },
] as const;

export type MetricKey = (typeof METRIC_OPTIONS)[number]['value'];

const METRIC_LABELS = new Map<string, string>(METRIC_OPTIONS.map((o) => [o.value, o.label]));

export function metricLabel(metric: string): string {
  return METRIC_LABELS.get(metric) ?? metric;
}

export function metricUnit(metric: string): 'COUNT' | 'AMOUNT' | 'RATE' | 'SCORE' {
  return METRIC_OPTIONS.find((o) => o.value === metric)?.unit ?? 'COUNT';
}

/**
 * 🔴 数量格式化。
 *
 * <p>小数位按量级自适应：曝光 / 点击这类是大数，销量 / 退货件数是小数。
 */
export function formatCount(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  if (Number.isInteger(value)) return value.toLocaleString('zh-CN');
  return value.toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 2 });
}

/** 🔴 金额格式化：两位小数 + 币种。 */
export function formatAmount(
  value: number | null | undefined,
  currency?: string | null,
): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  const body = value.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  return currency ? `${body} ${currency}` : body;
}

/**
 * 🔴 比率格式化：**null 显示成「—」，绝不显示成 0%**。
 *
 * <p>服务端在分母为 0 时返回 null —— 那是「没有样本」，不是「比率是 0」。
 * 显示成 0% 会让人以为「这个商品一点转化都没有」，那是结论，不是缺口。
 */
export function formatRate(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return `${(value * 100).toFixed(2)}%`;
}

/** 评分格式化：两位小数。 */
export function formatScore(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return value.toFixed(2);
}

/** 按单位把指标值格式化（统一口径，避免各处各写一套）。 */
export function formatMetric(
  value: number | null | undefined,
  unit: 'COUNT' | 'AMOUNT' | 'RATE' | 'SCORE',
  currency?: string | null,
): string {
  switch (unit) {
    case 'RATE':
      return formatRate(value);
    case 'AMOUNT':
      return formatAmount(value, currency);
    case 'SCORE':
      return formatScore(value);
    default:
      return formatCount(value);
  }
}

/**
 * 🔴 变化方向。
 *
 * <p>`unknown` = 变化率是 null（上期为 0，或指标本身是 null）——
 * 「不知道」和「持平」是两件事，页面必须分开显示。
 */
export function changeDirection(
  entry: ListingPerformanceComparisonEntry | undefined,
): 'up' | 'down' | 'flat' | 'unknown' {
  if (!entry) return 'unknown';
  if (entry.changeRate === null || entry.changeRate === undefined) return 'unknown';
  if (entry.changeRate > 0) return 'up';
  if (entry.changeRate < 0) return 'down';
  return 'flat';
}

/**
 * 🔴 变化文案。
 *
 * <p>上期为 0 时变化率是 null ⇒ 显示「—」并附「上期为 0」，
 * **不显示 0% 或 +∞**。
 */
export function changeText(entry: ListingPerformanceComparisonEntry | undefined): string {
  if (!entry) return '—';
  if (entry.changeRate === null || entry.changeRate === undefined) {
    if (entry.previous === null || entry.previous === undefined) return '—';
    if (entry.previous === 0) return '—（上期为 0）';
    return '—';
  }
  const rate = `${Math.abs(entry.changeRate * 100).toFixed(2)}%`;
  switch (changeDirection(entry)) {
    case 'up':
      return `↑ ${rate}`;
    case 'down':
      return `↓ ${rate}`;
    default:
      return `持平（${rate}）`;
  }
}

/** 从对比表里取某个指标。 */
export function comparisonEntryOf(
  comparison: ListingPerformanceComparison | undefined,
  metric: string,
): ListingPerformanceComparisonEntry | undefined {
  return comparison?.entries.find((e) => e.metric === metric);
}

/** 区间文案。 */
export function periodLabel(from: string, to: string): string {
  if (!from && !to) return '未指定区间';
  return `${from || '…'} ~ ${to || '…'}`;
}

/** 真分页的页码摘要（total 来自服务端 count，不是前端算的）。 */
export function pageSummary(page: {
  page: number;
  size: number;
  total: number;
}): string {
  const from = page.total === 0 ? 0 : page.page * page.size + 1;
  const to = Math.min(page.total, (page.page + 1) * page.size);
  return `第 ${from}-${to} 条 / 共 ${page.total} 条`;
}

/**
 * 🔴 列表空态文案：区分「还没同步过」与「同步过但这个筛选条件下没数据」。
 *
 * <p>前者是**平台侧还没给数据**，后者是**筛选条件选窄了** ——
 * 两种情况给同一句提示，运营会以为数据丢了。
 */
export function emptySnapshotsMessage(syncCount: number): string {
  return syncCount === 0
    ? '还没有平台表现数据的同步结果。平台侧的表现报表到达后会出现在这里 —— 本模块只接收平台回报（平台口径），不从内部订单聚合。'
    : '这个筛选条件下没有平台表现数据（当前租户已有 ' +
        syncCount +
        ' 次同步结果）。换个统计日范围或清掉筛选条件再看看。';
}

/** 🔴 明细空态文案：优先用服务端写明的 emptyReason。 */
export function emptyDetailMessage(detail: ListingPerformanceDetail | null): string {
  if (!detail) return '';
  return detail.emptyReason || '这个商品在所选区间内没有平台表现数据。';
}

/**
 * 🔴 趋势序列 → 图表数据。
 *
 * <p>没有数据时返回**空数组**（不画平线）。`value` 为 null 的点保留在序列里
 * （那一天的比率没有分母），图表上就是一个断点 —— 断点比一条假的 0 线诚实。
 */
export function trendSeries(
  points: ListingPerformanceTrendPoint[],
  metric: string,
): { bucket: string; value: number | null }[] {
  return points.map((p) => {
    const raw = (p as unknown as Record<string, number | null>)[metric];
    return { bucket: p.bucket, value: raw === undefined ? null : raw };
  });
}

/** 未交付维度（13 / 14 等）。 */
export function unavailableDimensions(
  caps: ListingPerformanceCapabilities | null,
): ListingPerformanceCapabilityDimension[] {
  return (caps?.dimensions ?? []).filter((d) => !d.available);
}

/** 🔴 数据来源标注（页面顶部固定显示）。 */
export function sourceBanner(caps: ListingPerformanceCapabilities | null): string {
  return caps?.dataSourceNote ?? '平台口径（平台回报数据，非内部订单）。';
}

/** 🔴 指标卡上要标的来源角标 —— 每个数都是平台口径。 */
export const PLATFORM_SOURCE_BADGE = '平台口径';
