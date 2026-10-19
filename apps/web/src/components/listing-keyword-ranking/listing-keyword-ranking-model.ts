import { api } from '../../lib/api';

/**
 * 4.5.1.2 关键词排名 前端模型。
 *
 * 蓝图：「监控商品在核心关键词下的自然排名与广告排名，记录历史变化，低于阈值时预警。
 * 输入：关键词集合（按商品维护）；排名抓取任务；阈值设置。
 * 输出：排名历史表 + 变化预警 + 关键词维度报表。
 * 关系：上游 = 抓取任务（02 通道）；下游 = 1 工作台预警、14 广告。
 * **排名数据依赖抓取频率与平台限制，必须明确标注抓取时间与可能的不准确窗口**。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **抓取时间与不准确窗口必须显示在界面上**。每个名次都要带「什么时候看到的」与
 *    「可能有多不准」。蓝图加粗句「必须明确标注抓取时间与可能的不准确窗口」是
 *    **契约级**的要求（后端每个响应都带 `crawledAt` / `accuracyWindowMinutes`），
 *    前端把它显示在排名区顶部 + 每行 —— 不显示就等于没有这条约束。
 *
 * 2. **名次没有就显示「—」，绝不显示成 0**。`naturalRank` 为 null 表示
 *    「这次平台没报这个维度的名次」，显示成 0 会被读成「排第一」或「完全没排名」。
 *
 * 3. **变化量 null 显示成「—」而不是 0**。没有上一次抓取时是 null，
 *    那是「没有可比的上次」，不是「没变化」。
 *
 * 4. **趋势没有数据时是空态，不是平线**。一条水平的假曲线会让人以为
 *    「这段时间排名没变」，而真相是「没抓到数据」。
 *
 * 5. **未交付的下游显示「未接入」并写明归属模块**（1 工作台预警 / 14 广告 /
 *    02 抓取调度器），不显示假数据、也不给一个必然 404 的按钮。
 *
 * 6. **预警是记录，不发通知**。页面上说清楚这一点，并提供「确认」而不是「推送」。
 */

export interface ListingKeywordRankingCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface ListingKeywordRankingCapabilities {
  dimensions: ListingKeywordRankingCapabilityDimension[];
  sources: string[];
  ingestChannels: string[];
  alertTypes: string[];
  granularities: string[];
  /** 🔴 抓取时间与不准确窗口的说明（页面顶部固定显示）。 */
  accuracyNote: string;
  /** 🔴 排名字段的口径（名次 / null 的含义 / 变化量怎么算）。 */
  rankDefinitions: string;
  relationNote: string;
}

export interface ListingKeywordRankingFilterOption {
  code: string;
  name: string;
  platformCode: string;
}

export interface ListingKeywordRankingFilterOptions {
  platforms: ListingKeywordRankingFilterOption[];
  shops: ListingKeywordRankingFilterOption[];
  sites: ListingKeywordRankingFilterOption[];
}

/** 一条关键词（列表行 / 明细共用）。 */
export interface ListingKeywordRankingKeywordRow {
  keywordId: string;
  listingId: string;
  externalItemId: string;
  title: string;
  shopCode: string;
  platformCode: string;
  siteCode: string;
  keyword: string;
  naturalRankThreshold: number | null;
  adRankThreshold: number | null;
  rankDropThreshold: number | null;
  active: boolean;
  note: string;
  /** 🔴 最近一次自然排名（null = 平台没报，不是 0）。 */
  latestNaturalRank: number | null;
  /** 🔴 最近一次广告排名（null = 平台没报，不是 0）。 */
  latestAdRank: number | null;
  /** 🔴 较上次的变化（null = 没有可比的上次）。 */
  naturalRankChange: number | null;
  adRankChange: number | null;
  /** 🔴 抓取时间（这个名次是什么时候看到的）。 */
  latestCrawledAt: string | null;
  /** 🔴 可能存在的不准确窗口（分钟）。 */
  latestAccuracyWindowMinutes: number | null;
  crawlCount: number;
  snapshotCount: number;
  openAlertCount: number;
  totalAlertCount: number;
}

export interface ListingKeywordRankingKeywordPage {
  items: ListingKeywordRankingKeywordRow[];
  page: number;
  size: number;
  total: number;
}

export interface ListingKeywordRankingDetail {
  keywordId: string;
  listingId: string;
  externalItemId: string;
  title: string;
  shopCode: string;
  platformCode: string;
  siteCode: string;
  keyword: string;
  naturalRankThreshold: number | null;
  adRankThreshold: number | null;
  rankDropThreshold: number | null;
  active: boolean;
  note: string;
  latestNaturalRank: number | null;
  latestAdRank: number | null;
  naturalRankChange: number | null;
  adRankChange: number | null;
  latestCrawledAt: string | null;
  latestAccuracyWindowMinutes: number | null;
  accuracyNote: string;
  crawlCount: number;
  snapshotCount: number;
  openAlertCount: number;
  totalAlertCount: number;
  hasData: boolean;
  emptyReason: string;
  lastCrawledAt: string | null;
}

/** 一条排名历史。 */
export interface ListingKeywordRankingHistoryRow {
  snapshotId: string;
  keywordId: string;
  listingId: string;
  keyword: string;
  platformCode: string;
  siteCode: string;
  naturalRank: number | null;
  adRank: number | null;
  naturalRankChange: number | null;
  adRankChange: number | null;
  crawledAt: string;
  accuracyWindowMinutes: number;
  accuracyNote: string;
  source: string;
  ingestChannel: string;
  revision: number;
  receivedAt: string;
}

export interface ListingKeywordRankingHistoryPage {
  items: ListingKeywordRankingHistoryRow[];
  page: number;
  size: number;
  total: number;
}

/** 趋势序列上的一个点。 */
export interface ListingKeywordRankingTrendPoint {
  bucket: string;
  lastNaturalRank: number | null;
  lastAdRank: number | null;
  bestNaturalRank: number | null;
  worstNaturalRank: number | null;
  bestAdRank: number | null;
  worstAdRank: number | null;
  sampleCount: number;
  lastCrawledAt: string | null;
}

export interface ListingKeywordRankingTrend {
  keywordId: string;
  keyword: string;
  granularity: string;
  from: string;
  to: string;
  accuracyNote: string;
  /** 🔴 没有数据时是**空数组**（不是假曲线）。 */
  points: ListingKeywordRankingTrendPoint[];
  hasData: boolean;
}

/** 一条变化预警。 */
export interface ListingKeywordRankingAlertRow {
  alertId: string;
  keywordId: string;
  listingId: string;
  keyword: string;
  title: string;
  shopCode: string;
  platformCode: string;
  siteCode: string;
  alertType: string;
  rankValue: number | null;
  previousRank: number | null;
  thresholdValue: number | null;
  detectedAt: string;
  acknowledgedAt: string | null;
  acknowledgedBy: string | null;
  crawlId: string;
  snapshotId: string | null;
}

export interface ListingKeywordRankingAlertPage {
  items: ListingKeywordRankingAlertRow[];
  page: number;
  size: number;
  total: number;
  openCount: number;
}

/** 关键词维度报表的一行。 */
export interface ListingKeywordRankingReportRow {
  keyword: string;
  platformCode: string;
  siteCode: string;
  listingCount: number;
  sampleCount: number;
  /** 🔴 平均名次（null = 没有样本，不是 0）。 */
  averageNaturalRank: number | null;
  bestNaturalRank: number | null;
  worstNaturalRank: number | null;
  averageAdRank: number | null;
  bestAdRank: number | null;
  worstAdRank: number | null;
  belowThresholdCount: number;
  openAlertCount: number;
  lastCrawledAt: string | null;
  accuracyWindowMinutes: number | null;
}

export interface ListingKeywordRankingReport {
  accuracyNote: string;
  rankDefinitions: string;
  rows: ListingKeywordRankingReportRow[];
  keywordCount: number;
}

// ================================================================ 取数

export async function fetchCapabilities(): Promise<ListingKeywordRankingCapabilities> {
  return api<ListingKeywordRankingCapabilities>('channel/listing-keyword-rankings/capabilities');
}

export async function fetchFilterOptions(): Promise<ListingKeywordRankingFilterOptions> {
  return api<ListingKeywordRankingFilterOptions>('channel/listing-keyword-rankings/filter-options');
}

export async function fetchKeywords(params: {
  listingId?: string;
  shopCode?: string;
  platformCode?: string;
  siteCode?: string;
  keyword?: string;
  status?: string;
  page?: number;
  size?: number;
}): Promise<ListingKeywordRankingKeywordPage> {
  const query = new URLSearchParams();
  if (params.listingId) query.set('listingId', params.listingId);
  if (params.shopCode) query.set('shopCode', params.shopCode);
  if (params.platformCode) query.set('platformCode', params.platformCode);
  if (params.siteCode) query.set('siteCode', params.siteCode);
  if (params.keyword) query.set('keyword', params.keyword);
  if (params.status) query.set('status', params.status);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<ListingKeywordRankingKeywordPage>(`channel/listing-keyword-rankings?${query.toString()}`);
}

export async function fetchDetail(keywordId: string): Promise<ListingKeywordRankingDetail> {
  return api<ListingKeywordRankingDetail>(`channel/listing-keyword-rankings/${keywordId}`);
}

export async function fetchHistory(
  keywordId: string,
  params: { from?: string; to?: string; page?: number; size?: number },
): Promise<ListingKeywordRankingHistoryPage> {
  const query = new URLSearchParams();
  if (params.from) query.set('from', params.from);
  if (params.to) query.set('to', params.to);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<ListingKeywordRankingHistoryPage>(
    `channel/listing-keyword-rankings/${keywordId}/history?${query.toString()}`,
  );
}

export async function fetchTrend(
  keywordId: string,
  params: { from?: string; to?: string; granularity?: string },
): Promise<ListingKeywordRankingTrend> {
  const query = new URLSearchParams();
  if (params.from) query.set('from', params.from);
  if (params.to) query.set('to', params.to);
  if (params.granularity) query.set('granularity', params.granularity);
  const suffix = query.toString() ? `?${query.toString()}` : '';
  return api<ListingKeywordRankingTrend>(`channel/listing-keyword-rankings/${keywordId}/trend${suffix}`);
}

export async function fetchAlerts(params: {
  keywordId?: string;
  listingId?: string;
  alertType?: string;
  acknowledged?: string;
  page?: number;
  size?: number;
}): Promise<ListingKeywordRankingAlertPage> {
  const query = new URLSearchParams();
  if (params.keywordId) query.set('keywordId', params.keywordId);
  if (params.listingId) query.set('listingId', params.listingId);
  if (params.alertType) query.set('alertType', params.alertType);
  if (params.acknowledged) query.set('acknowledged', params.acknowledged);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<ListingKeywordRankingAlertPage>(`channel/listing-keyword-rankings/alerts?${query.toString()}`);
}

export async function fetchReport(params: {
  platformCode?: string;
  siteCode?: string;
  keyword?: string;
  from?: string;
  to?: string;
}): Promise<ListingKeywordRankingReport> {
  const query = new URLSearchParams();
  if (params.platformCode) query.set('platformCode', params.platformCode);
  if (params.siteCode) query.set('siteCode', params.siteCode);
  if (params.keyword) query.set('keyword', params.keyword);
  if (params.from) query.set('from', params.from);
  if (params.to) query.set('to', params.to);
  const suffix = query.toString() ? `?${query.toString()}` : '';
  return api<ListingKeywordRankingReport>(`channel/listing-keyword-rankings/report${suffix}`);
}

// ================================================================ 纯函数（可单测）

/** 🔴 预警类型 → 中文名与含义。顺序与后端一致。 */
export const ALERT_TYPE_OPTIONS = [
  {
    value: 'NATURAL_BELOW_THRESHOLD',
    label: '自然排名低于阈值',
    hint: '名次数字大于阈值（掉出阈值之外）',
  },
  {
    value: 'AD_BELOW_THRESHOLD',
    label: '广告排名低于阈值',
    hint: '名次数字大于阈值（掉出阈值之外）',
  },
  {
    value: 'NATURAL_RANK_DROPPED',
    label: '自然排名退步',
    hint: '相对上一次抓取退步超过退步阈值',
  },
  {
    value: 'AD_RANK_DROPPED',
    label: '广告排名退步',
    hint: '相对上一次抓取退步超过退步阈值',
  },
] as const;

const ALERT_TYPE_LABELS = new Map<string, string>(ALERT_TYPE_OPTIONS.map((o) => [o.value, o.label]));

export function alertTypeLabel(alertType: string): string {
  return ALERT_TYPE_LABELS.get(alertType) ?? alertType;
}

export function alertTypeHint(alertType: string): string {
  return ALERT_TYPE_OPTIONS.find((o) => o.value === alertType)?.hint ?? '';
}

/**
 * 🔴 名次格式化：**null 显示成「—」，绝不显示成 0**。
 *
 * <p>服务端在平台没报名次时返回 null —— 那是「这次没进监测范围」，
 * 不是「排名第 0」。显示成 0 会让人以为「排第一」或「完全没排名」。
 */
export function formatRank(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  if (!Number.isInteger(value)) return value.toFixed(2);
  return value.toLocaleString('zh-CN');
}

/**
 * 🔴 平均名次格式化：**null 显示成「—」，绝不显示成 0**。
 *
 * <p>没有样本时是 null（那是「没有样本」，不是「平均第 0 名」）。
 */
export function formatAverageRank(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return value.toFixed(2);
}

/**
 * 🔴 名次变化。
 *
 * <p>`unknown` = 变化量是 null（没有上一次抓取，或上一次没报这个维度）——
 * 「不知道」和「持平」是两件事，页面必须分开显示。
 */
export function rankChangeDirection(change: number | null | undefined): 'up' | 'down' | 'flat' | 'unknown' {
  if (change === null || change === undefined || Number.isNaN(change)) return 'unknown';
  if (change > 0) return 'down';
  if (change < 0) return 'up';
  return 'flat';
}

/**
 * 🔴 名次变化的文案。
 *
 * <p>🔴 **名次数字变大 = 变差**（从第 5 名掉到第 20 名是变差，不是变好）。
 * 变化量是 null 时显示「—」，**不显示 0 位**。
 */
export function rankChangeText(change: number | null | undefined): string {
  if (change === null || change === undefined || Number.isNaN(change)) return '—';
  if (change === 0) return '持平（0 位）';
  return change > 0 ? `↓ 退了 ${change} 位` : `↑ 进了 ${Math.abs(change)} 位`;
}

/**
 * 🔴 抓取时间 + 不准确窗口的标注（**每个名次都要带**）。
 *
 * <p>这是蓝图加粗句「必须明确标注抓取时间与可能的不准确窗口」在前端的落点：
 * 没有它，界面上那个名次就看不出是什么时候的、可能有多不准。
 */
export function accuracyLabel(
  crawledAt: string | null | undefined,
  windowMinutes: number | null | undefined,
): string {
  if (!crawledAt) return '尚未抓取';
  const at = new Date(crawledAt);
  const when = Number.isNaN(at.getTime()) ? crawledAt : at.toLocaleString('zh-CN');
  if (windowMinutes === null || windowMinutes === undefined) {
    return `抓取于 ${when}`;
  }
  return `抓取于 ${when} · 可能有 ±${windowMinutes} 分钟偏差`;
}

/** 🔴 抓取时间（不含窗口）的短文案。 */
export function crawledAtLabel(crawledAt: string | null | undefined): string {
  if (!crawledAt) return '尚未抓取';
  const at = new Date(crawledAt);
  return Number.isNaN(at.getTime()) ? crawledAt : at.toLocaleString('zh-CN');
}

/** 阈值文案：null = 该维度不预警。 */
export function thresholdLabel(value: number | null | undefined): string {
  if (value === null || value === undefined) return '未配置（不预警）';
  return `前 ${value} 名`;
}

/** 站点文案：没有站点时是「—」。 */
export function siteLabel(siteCode: string | null | undefined): string {
  if (!siteCode) return '—';
  return siteCode;
}

/** 真分页的页码摘要（total 来自服务端 count，不是前端算的）。 */
export function pageSummary(page: { page: number; size: number; total: number }): string {
  const from = page.total === 0 ? 0 : page.page * page.size + 1;
  const to = Math.min(page.total, (page.page + 1) * page.size);
  return `第 ${from}-${to} 条 / 共 ${page.total} 条`;
}

/**
 * 🔴 列表空态文案：区分「还没抓过」与「抓过但没有排名数据」。
 *
 * <p>前者是**平台侧还没抓到**，后者是**筛选条件选窄了** ——
 * 两种情况给同一句提示，运营会以为数据丢了。
 */
export function emptyKeywordsMessage(total: number): string {
  return total === 0
    ? '还没有按商品维护的关键词。先在上面新建一个关键词，它会开始监控这个商品在核心关键词下的自然排名与广告排名。'
    : '这个筛选条件下没有关键词。换个关键词或清掉筛选条件再看看。';
}

/** 🔴 明细空态文案：优先用服务端写明的 emptyReason。 */
export function emptyDetailMessage(detail: ListingKeywordRankingDetail | null): string {
  if (!detail) return '';
  return detail.emptyReason || '这个关键词还没有排名数据。';
}

/**
 * 🔴 预警列表空态文案：区分「没有预警」与「有预警但都确认过了」。
 */
export function emptyAlertsMessage(openCount: number): string {
  return openCount === 0
    ? '没有未确认的预警。低于阈值或退步超阈值时这里会出现一条**记录**（本模块不发通知，推送归 1 工作台预警）。'
    : '当前筛选条件下没有预警记录。';
}

/**
 * 🔴 趋势序列 → 图表数据。
 *
 * <p>没有数据时返回**空数组**（不画平线）。`value` 为 null 的点保留在序列里
 * （那个桶里平台没报名次），图表上就是一个断点 —— 断点比一条假的 0 线诚实。
 */
export function trendSeries(
  points: ListingKeywordRankingTrendPoint[],
  metric: 'lastNaturalRank' | 'lastAdRank' | 'bestNaturalRank' | 'worstNaturalRank',
): { bucket: string; value: number | null }[] {
  return points.map((p) => ({ bucket: p.bucket, value: p[metric] ?? null }));
}

/** 未交付维度（1 工作台 / 14 广告 / 02 抓取调度器）。 */
export function unavailableDimensions(
  caps: ListingKeywordRankingCapabilities | null,
): ListingKeywordRankingCapabilityDimension[] {
  return (caps?.dimensions ?? []).filter((d) => !d.available);
}

/** 🔴 抓取时间与不准确窗口的说明（页面顶部固定显示）。 */
export function accuracyBanner(caps: ListingKeywordRankingCapabilities | null): string {
  return (
    caps?.accuracyNote ??
    '🔴 排名依赖抓取频率与平台限制：每个数据点都带抓取时间与可能的不准确窗口。名次是抓取那一刻平台给出的位置。'
  );
}

/** 🔴 排名字段的口径说明（页面上的口径区）。 */
export function rankDefinitionBanner(caps: ListingKeywordRankingCapabilities | null): string {
  return caps?.rankDefinitions ?? '名次是位置（数字越小越好）；平台没报名次时是 null，不是 0。';
}
