import { api } from '../../lib/api';

/**
 * 4.5.2.2 上新与变更监控 前端模型。
 *
 * 蓝图：「监控目标 ASIN/竞品的上下架、价格、排名、评论数变化，识别市场机会（如竞品断货涨价）。
 * 输入：监控对象列表；抓取频率。输出：变更事件流 + 历史对比。关系：上游 = 抓取任务；
 * 下游 = 13 分析、1 工作台。**监控对象与自有商品分离**，竞品数据不进自有商品模型，只做参考指标。」
 *
 * 🔴 这个页面上最容易做丢的六件事，这里都挡住了：
 *
 * 1. **「参考数据」必须标在界面上**。页面顶部固定显示「平台侧抓取观测值，仅供参考，
 *    不构成市场结论」，并且**每个价格**都带「平台抓取」角标。蓝图加粗句
 *    「竞品数据不进自有商品模型，**只做参考指标**」是硬要求。
 *
 * 2. **系统不提供抢占 / 调价动作，界面上也不给**。没有「一键抢占」按钮 ——
 *    竞品数据只做参考指标，动作在平台侧或自有定价流程里完成。
 *
 * 3. **前端不重算比率**。`priceChangeRatio` / `changeRatio` 是服务端算好的结论，
 *    **分母为 0 时是 null**。null 显示成「—」，**绝不显示成 0%** ——
 *    「没有基准」与「价格没变」是两件事。
 *
 * 4. **方向不等于好坏**。`direction` 只说「朝哪变」：
 *    `RANK + INCREASED` 是「名次数字变大」= **竞品表现变差**（对我方有利），
 *    但页面必须按维度翻译，不能一律显示「上升」。见 `directionLabel`。
 *
 * 5. **平台没报 ≠ 变成 0**。`price` / `rankPosition` / `reviewCount` 为 null 时
 *    显示「—」，不显示 0。
 *
 * 6. **未交付的下游显示「未接入」并写明归属模块**（02 抓取调度器 / 13 分析 / 1 工作台），
 *    不显示假数据、也不给一个必然 404 的按钮。
 */

export interface ListingChangeCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface ListingChangeCapabilities {
  dimensions: ListingChangeCapabilityDimension[];
  targetKinds: string[];
  listingStatuses: string[];
  changeTypes: string[];
  directions: string[];
  opportunityTypes: string[];
  severities: string[];
  sources: string[];
  ingestChannels: string[];
  watchStatuses: string[];
  opportunityStatuses: string[];
  rankBases: string[];
  /** 🔴 判定规则的口径（服务端算，前端原样显示）。 */
  ruleDefinitions: string;
  /** 🔴 数据来源标识（PLATFORM_SCRAPED）。 */
  dataSource: string;
  /** 🔴 数据来源标注（仅供参考，不构成市场结论）。 */
  sourceNote: string;
  /** 🔴 动作声明（本模块不提供抢占/调价等动作）。 */
  actionsNote: string;
  relationNote: string;
}

export interface ListingChangePlatformOption {
  code: string;
  name: string;
}

export interface ListingChangeMarketplaceOption {
  code: string;
  name: string;
  platformCode: string;
}

export interface ListingChangeFilterOptions {
  platforms: ListingChangePlatformOption[];
  marketplaces: ListingChangeMarketplaceOption[];
  targetKinds: string[];
  listingStatuses: string[];
  changeTypes: string[];
  directions: string[];
  opportunityTypes: string[];
  severities: string[];
  sources: string[];
  watchStatuses: string[];
  opportunityStatuses: string[];
  rankBases: string[];
}

/** 🔴 一个监控对象（竞品 ASIN）。 */
export interface ListingChangeWatchRow {
  watchId: string;
  platformCode: string;
  marketplaceCode: string;
  externalItemId: string;
  /** COMPETITOR 竞品 / OWN_ASIN 自有 ASIN。 */
  targetKind: string;
  title: string;
  brand: string;
  crawlIntervalMinutes: number;
  status: string;
  outOfStockOpportunity: boolean;
  listedOpportunity: boolean;
  /** 🔴 涨价幅度阈值（null = 该维度不判定）。 */
  priceIncreaseRatioThreshold: number | null;
  /** 🔴 排名变差阈值（null = 该维度不判定）。 */
  rankWorsenThreshold: number | null;
  note: string;
  snapshotCount: number;
  eventCount: number;
  opportunityCount: number;
  openOpportunityCount: number;
  /** 🔴 最近一次**已知**上下架状态（null = 从未观测到）。 */
  lastListingStatus: string | null;
  /** 🔴 最近一次**已知**价格（null = 从未观测到；不是 0）。 */
  lastPrice: number | null;
  /** 🔴 最近一次**已知**排名（null = 从未观测到；不是 0）。 */
  lastRankPosition: number | null;
  /** 🔴 最近一次**已知**评论数（null = 从未观测到）。 */
  lastReviewCount: number | null;
  currency: string;
  lastObservedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ListingChangeWatchPage {
  items: ListingChangeWatchRow[];
  page: number;
  size: number;
  total: number;
  dataSource: string;
  sourceNote: string;
}

/** 一条观测快照。 */
export interface ListingChangeSnapshotRow {
  snapshotId: string;
  watchId: string;
  externalItemId: string;
  observedAt: string;
  accuracyWindowMinutes: number;
  listingStatus: string;
  price: number | null;
  currency: string;
  rankPosition: number | null;
  rankBasis: string;
  rankBasisKey: string;
  reviewCount: number | null;
  source: string;
  ingestChannel: string;
  revision: number;
  dataSource: string;
  sourceNote: string;
}

export interface ListingChangeSnapshotPage {
  items: ListingChangeSnapshotRow[];
  page: number;
  size: number;
  total: number;
  dataSource: string;
  sourceNote: string;
}

/** 🔴 一条变更事件。 */
export interface ListingChangeEventRow {
  eventId: string;
  watchId: string;
  snapshotId: string;
  previousSnapshotId: string | null;
  externalItemId: string;
  title: string;
  changeType: string;
  direction: string;
  previousValue: number | null;
  currentValue: number | null;
  previousState: string | null;
  currentState: string | null;
  /** 🔴 价格变化量（现算；无基准时 null）。 */
  priceChangeAmount: number | null;
  /** 🔴 价格变化比例（现算；**分母为 0 时是 null**）。 */
  priceChangeRatio: number | null;
  /** 🔴 排名变化（本次名次 − 上次名次；名次变大 = 竞品变差）。 */
  rankChange: number | null;
  /** 🔴 评论数变化（现算）。 */
  reviewCountChange: number | null;
  observedPrice: number | null;
  currency: string;
  detectedAt: string;
  reason: string;
  dataSource: string;
  sourceNote: string;
}

export interface ListingChangeEventPage {
  items: ListingChangeEventRow[];
  page: number;
  size: number;
  total: number;
  watchCount: number;
  dataSource: string;
  sourceNote: string;
}

/** 🔴 一条市场机会。 */
export interface ListingChangeOpportunityRow {
  opportunityId: string;
  watchId: string;
  eventId: string;
  externalItemId: string;
  title: string;
  opportunityType: string;
  severity: string;
  observedPrice: number | null;
  observedRank: number | null;
  observedReviewCount: number | null;
  currency: string;
  status: string;
  reason: string;
  detectedAt: string;
  acknowledgedAt: string | null;
  dataSource: string;
  sourceNote: string;
}

export interface ListingChangeOpportunityPage {
  items: ListingChangeOpportunityRow[];
  page: number;
  size: number;
  total: number;
  openCount: number;
  dataSource: string;
  sourceNote: string;
}

/** 价格维度的一段历史对比（全部服务端现算）。 */
export interface ListingChangePriceComparison {
  firstPrice: number | null;
  lastPrice: number | null;
  minPrice: number | null;
  maxPrice: number | null;
  avgPrice: number | null;
  changeAmount: number | null;
  /** 🔴 变化比例（**分母为 0 时是 null**）。 */
  changeRatio: number | null;
  currency: string;
  sampleCount: number;
}

/** 排名维度的一段历史对比。 */
export interface ListingChangeRankComparison {
  firstRank: number | null;
  lastRank: number | null;
  bestRank: number | null;
  worstRank: number | null;
  changeAmount: number | null;
  rankBasis: string;
  sampleCount: number;
}

/** 评论数维度的一段历史对比。 */
export interface ListingChangeReviewComparison {
  firstCount: number | null;
  lastCount: number | null;
  changeAmount: number | null;
  sampleCount: number;
}

/** 上下架维度的一段历史对比。 */
export interface ListingChangeStatusComparison {
  currentStatus: string | null;
  listedCount: number;
  outOfStockCount: number;
  delistedCount: number;
  transitionCount: number;
  sampleCount: number;
}

/** 🔴 单个监控对象的历史对比。 */
export interface ListingChangeComparison {
  watchId: string;
  platformCode: string;
  marketplaceCode: string;
  externalItemId: string;
  targetKind: string;
  title: string;
  from: string;
  to: string;
  sampleCount: number;
  eventCount: number;
  opportunityCount: number;
  price: ListingChangePriceComparison;
  rank: ListingChangeRankComparison;
  reviewCount: ListingChangeReviewComparison;
  listingStatus: ListingChangeStatusComparison;
  hasData: boolean;
  emptyReason: string;
  dataSource: string;
  sourceNote: string;
}

// ================================================================ 取数

export async function fetchCapabilities(): Promise<ListingChangeCapabilities> {
  return api<ListingChangeCapabilities>('channel/listing-changes/capabilities');
}

export async function fetchFilterOptions(): Promise<ListingChangeFilterOptions> {
  return api<ListingChangeFilterOptions>('channel/listing-changes/filter-options');
}

export async function fetchWatches(params: {
  status?: string;
  platformCode?: string;
  targetKind?: string;
  keyword?: string;
  page?: number;
  size?: number;
}): Promise<ListingChangeWatchPage> {
  const query = new URLSearchParams();
  if (params.status) query.set('status', params.status);
  if (params.platformCode) query.set('platformCode', params.platformCode);
  if (params.targetKind) query.set('targetKind', params.targetKind);
  if (params.keyword) query.set('keyword', params.keyword);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<ListingChangeWatchPage>(`channel/listing-changes/watches?${query.toString()}`);
}

export async function fetchEvents(params: {
  watchId?: string;
  changeType?: string;
  direction?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}): Promise<ListingChangeEventPage> {
  const query = new URLSearchParams();
  if (params.watchId) query.set('watchId', params.watchId);
  if (params.changeType) query.set('changeType', params.changeType);
  if (params.direction) query.set('direction', params.direction);
  if (params.from) query.set('from', params.from);
  if (params.to) query.set('to', params.to);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<ListingChangeEventPage>(`channel/listing-changes/events?${query.toString()}`);
}

export async function fetchOpportunities(params: {
  watchId?: string;
  opportunityType?: string;
  severity?: string;
  status?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}): Promise<ListingChangeOpportunityPage> {
  const query = new URLSearchParams();
  if (params.watchId) query.set('watchId', params.watchId);
  if (params.opportunityType) query.set('opportunityType', params.opportunityType);
  if (params.severity) query.set('severity', params.severity);
  if (params.status) query.set('status', params.status);
  if (params.from) query.set('from', params.from);
  if (params.to) query.set('to', params.to);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<ListingChangeOpportunityPage>(`channel/listing-changes/opportunities?${query.toString()}`);
}

export async function fetchCrawls(params: {
  watchId?: string;
  page?: number;
  size?: number;
}): Promise<ListingChangeCrawlPage> {
  const query = new URLSearchParams();
  if (params.watchId) query.set('watchId', params.watchId);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<ListingChangeCrawlPage>(`channel/listing-changes/crawls?${query.toString()}`);
}

export interface ListingChangeCrawlRow {
  crawlId: string;
  watchId: string;
  externalItemId: string;
  source: string;
  ingestChannel: string;
  crawledAt: string;
  accuracyWindowMinutes: number;
  status: string;
  receivedCount: number;
  appliedCount: number;
  skippedCount: number;
  eventCount: number;
  opportunityCount: number;
  idempotencyKey: string;
  note: string;
  receivedAt: string;
}

export interface ListingChangeCrawlPage {
  items: ListingChangeCrawlRow[];
  page: number;
  size: number;
  total: number;
  dataSource: string;
  sourceNote: string;
}

export async function fetchHistory(params: {
  watchId: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}): Promise<ListingChangeSnapshotPage> {
  const query = new URLSearchParams();
  query.set('watchId', params.watchId);
  if (params.from) query.set('from', params.from);
  if (params.to) query.set('to', params.to);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<ListingChangeSnapshotPage>(
    `channel/listing-changes/watches/${params.watchId}/history?${query.toString()}`,
  );
}

export async function fetchComparison(params: {
  watchId: string;
  from?: string;
  to?: string;
}): Promise<ListingChangeComparison> {
  const query = new URLSearchParams();
  query.set('watchId', params.watchId);
  if (params.from) query.set('from', params.from);
  if (params.to) query.set('to', params.to);
  return api<ListingChangeComparison>(
    `channel/listing-changes/watches/${params.watchId}/comparison?${query.toString()}`,
  );
}

export async function acknowledgeOpportunity(opportunityId: string, note?: string): Promise<void> {
  await api(
    `channel/listing-changes/opportunities/${opportunityId}/acknowledge`,
    'PUT',
    { note: note ?? '' },
  );
}

// ================================================================ 纯函数（可单测）

/** 变化维度 → 中文名。 */
export const CHANGE_TYPE_LABELS: Record<string, string> = {
  LISTING_STATUS: '上下架',
  PRICE: '价格',
  RANK: '排名',
  REVIEW_COUNT: '评论数',
};

/** 上下架状态 → 中文名。 */
export const LISTING_STATUS_LABELS: Record<string, string> = {
  LISTED: '在售',
  OUT_OF_STOCK: '断货',
  DELISTED: '已下架',
  UNKNOWN: '未上报',
};

/** 目标性质 → 中文名。 */
export const TARGET_KIND_LABELS: Record<string, string> = {
  COMPETITOR: '竞品',
  OWN_ASIN: '自有 ASIN',
};

/** 监控对象状态 → 中文名。 */
export const WATCH_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '监控中',
  PAUSED: '已暂停',
};

/** 市场机会类型 → 中文名。 */
export const OPPORTUNITY_TYPE_LABELS: Record<string, string> = {
  COMPETITOR_OUT_OF_STOCK: '竞品断货',
  COMPETITOR_PRICE_INCREASE: '竞品涨价',
  COMPETITOR_RANK_WORSENED: '竞品排名变差',
  COMPETITOR_LISTED: '竞品上新',
};

/** 严重度 → 中文名。 */
export const SEVERITY_LABELS: Record<string, string> = {
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
};

/** 排名口径 → 中文名。 */
export const RANK_BASIS_LABELS: Record<string, string> = {
  BSR: '平台销量榜',
  CATEGORY: '类目榜',
  KEYWORD: '关键词下',
};

export function changeTypeLabel(type: string): string {
  return CHANGE_TYPE_LABELS[type] ?? type;
}

export function listingStatusLabel(status: string | null | undefined): string {
  if (!status) return '—';
  return LISTING_STATUS_LABELS[status] ?? status;
}

export function targetKindLabel(kind: string): string {
  return TARGET_KIND_LABELS[kind] ?? kind;
}

export function watchStatusLabel(status: string): string {
  return WATCH_STATUS_LABELS[status] ?? status;
}

export function opportunityTypeLabel(type: string): string {
  return OPPORTUNITY_TYPE_LABELS[type] ?? type;
}

export function severityLabel(severity: string): string {
  return SEVERITY_LABELS[severity] ?? severity;
}

export function rankBasisLabel(basis: string): string {
  return RANK_BASIS_LABELS[basis] ?? basis;
}

/**
 * 🔴🔴 方向文案：**按维度翻译**，不把 direction 直接翻成「上升 / 下降」。
 *
 * <p>`direction` 只说该维度数值/状态本身朝哪变，**不含好坏判断**：
 *
 * <ul>
 *   <li>价格 INCREASED = **涨价**（对我方是机会）；DECREASED = 降价</li>
 *   <li>🔴 排名 INCREASED = 名次数字变大 = **竞品表现变差**（对我方有利）——
 *       直接显示成「上升」会把意思读反</li>
 *   <li>评论数 INCREASED = 评论数增加</li>
 *   <li>上下架 CHANGED = 状态迁移</li>
 * </ul>
 */
export function directionLabel(changeType: string, direction: string): string {
  if (changeType === 'LISTING_STATUS') return '状态迁移';
  if (changeType === 'PRICE') return direction === 'INCREASED' ? '涨价' : '降价';
  if (changeType === 'RANK') return direction === 'INCREASED' ? '名次下滑（竞品变差）' : '名次上升（竞品变好）';
  if (changeType === 'REVIEW_COUNT') return direction === 'INCREASED' ? '评论数增加' : '评论数减少';
  return direction;
}

/**
 * 🔴 排名变化的界面文案。
 *
 * <p>名次数字**变大 = 竞品表现变差**（对我方有利）；**变小 = 竞品表现变好**。
 * null 表示「没有基准」—— 不是「没变」。
 */
export function rankChangeText(change: number | null | undefined): string {
  if (change === null || change === undefined || Number.isNaN(change)) return '—（无基准）';
  if (change > 0) return `下滑 ${change} 位（竞品变差）`;
  if (change < 0) return `上升 ${Math.abs(change)} 位（竞品变好）`;
  return '持平';
}

/**
 * 🔴 比率格式化：**null 显示成「—」，绝不显示成 0%**。
 *
 * <p>服务端在分母（上次价格）为 0 时返回 null —— 那是「没有基准 / 基准为 0」，
 * 不是「价格没变」。显示成 0% 会让人以为竞品价格一直没动，那是结论，不是缺口。
 */
export function formatRate(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return `${(value * 100).toFixed(2)}%`;
}

/**
 * 🔴 带符号的比率（变化量用）。
 *
 * <p>涨价显示成 `+12.00%`、降价显示成 `-5.00%`；null 仍是「—」。
 */
export function formatSignedRate(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  const body = `${(Math.abs(value) * 100).toFixed(2)}%`;
  return value > 0 ? `+${body}` : value < 0 ? `-${body}` : body;
}

/**
 * 🔴 金额格式化：两位小数 + 币种。
 *
 * <p>没有值显示「—」—— **不是 0**。平台没报价格与平台报了个 0 价是两件事。
 */
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

/** 数量格式化（计数这类整数）。 */
export function formatCount(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return value.toLocaleString('zh-CN');
}

/** 时刻格式化（显示到分钟）。 */
export function formatTime(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '—';
  return parsed.toLocaleString('zh-CN', { hour12: false });
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
 * 🔴 变更事件空态文案：区分三种「没有数据」。
 *
 * <p>「还没配监控对象」「配了但还没抓到数据」「抓到了但这个筛选下没有」
 * 是三件事 —— 共用一句话会让运营以为数据丢了。
 */
export function emptyEventsMessage(watchCount: number, total: number): string {
  if (watchCount === 0) {
    return '还没有监控任何 ASIN。先在上面建一个监控对象（平台 + ASIN），抓到竞品状态才会出现在这里。';
  }
  if (total === 0) {
    return '这个筛选条件下没有变更事件。数据来自平台抓取 / 平台接口推送，抓到了才会出现 —— 本模块不自己轮询平台（抓取调度器归 02）。';
  }
  return '没有变更事件。';
}

/**
 * 🔴 历史对比空态文案：优先用服务端写明的 emptyReason。
 */
export function emptyComparisonMessage(comparison: ListingChangeComparison | null): string {
  if (!comparison) return '';
  return comparison.emptyReason || '这个监控对象在所选区间内没有观测数据。';
}

/** 未交付维度（02 抓取调度器 / 13 分析 / 1 工作台）。 */
export function unavailableDimensions(
  caps: ListingChangeCapabilities | null,
): ListingChangeCapabilityDimension[] {
  return (caps?.dimensions ?? []).filter((d) => !d.available);
}

/** 🔴 数据来源标注（页面顶部固定显示）。 */
export function sourceBanner(caps: ListingChangeCapabilities | null): string {
  return (
    caps?.sourceNote ??
    '🔴 平台侧抓取观测值，仅供参考，不构成市场结论：抓取会滞后、会漏抓，也会抓到已下架 / 已断货的商品。'
  );
}

/** 🔴 动作声明（页面顶部固定显示，紧跟来源标注）。 */
export function actionsBanner(caps: ListingChangeCapabilities | null): string {
  return (
    caps?.actionsNote ??
    '🔴 本系统不提供抢占 / 调价 / 铺货等动作：竞品数据只做参考指标，机会需要人工判断后再执行。'
  );
}

/** 🔴 每个价格旁边的来源角标。 */
export const SCRAPED_SOURCE_BADGE = '平台抓取';

/** 🔴 竞品数据行的「参考数据」角标。 */
export const REFERENCE_DATA_BADGE = '参考数据';

/**
 * 🔴 机会状态的展示文案。
 *
 * <p>「已阅」**不代表已经采取任何动作** —— 这一点必须写在界面上，
 * 否则运营会以为点一下就完事了。
 */
export function opportunityStatusHint(status: string): string {
  return status === 'ACKNOWLEDGED'
    ? '已阅（内部状态，不代表已采取任何动作）'
    : '未处理（动作在平台侧或自有定价流程里完成）';
}

/**
 * 🔴 判断一个监控对象是不是「竞品」。
 *
 * <p>OWN_ASIN 是自家商品在平台上的**平台侧观测** —— 它仍然不写进自有商品模型，
 * 所以页面上对两者一视同仁地标「参考数据」。
 */
export function isCompetitorTarget(targetKind: string): boolean {
  return targetKind === 'COMPETITOR';
}
