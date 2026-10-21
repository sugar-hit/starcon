import { api } from '../../lib/api';

/**
 * 4.5.2.1 跟卖监控 前端模型。
 *
 * 蓝图：「监控他人/己方其他店铺对本 ASIN 的跟卖行为，记录跟卖者、价格、店铺，
 * 发现异常跟卖或恶意压价时告警。输出：跟卖记录 + 预警通知 + 对比视图。
 * **跟卖数据只能作为参考**，处置动作（投诉/举报）在平台侧完成，系统只负责发现与提醒。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「仅供参考」必须标在界面上**。页面顶部固定显示「平台抓取观测值，仅供参考，
 *    不构成处置结论」，并且**每个价格**都带「平台抓取」角标。蓝图加粗句
 *    「跟卖数据只能作为参考」是硬要求 —— 抓取会滞后、会漏抓、也会抓到已下架的报价。
 *
 * 2. **系统不提供处置动作，界面上也不给**。没有「投诉」「举报」按钮 ——
 *    那些在平台侧完成。给一个必然 404 的按钮，比不给更糟。
 *
 * 3. **前端不重算比率**。`priceGapRatio` 是服务端按固定分子分母算好的结论，
 *    **分母（自家参考价）为 0 时是 null**。null 显示成「—」，
 *    **绝不显示成 0%** —— 「没有参考价」与「跟卖价和自家价一样」是两件事。
 *
 * 4. **预警是记录，不是通知**。页面上写明「本模块不主动发通知」，
 *    「已阅」只是内部状态，**不代表已在平台侧投诉或举报**。
 *
 * 5. **未交付的下游显示「未接入」并写明归属模块**（16 合规品牌保护 / 1 工作台预警 /
 *    02 抓取调度器 / 05 定价），不显示假数据、也不给一个必然 404 的按钮。
 *
 * 6. **空态分得清**：「还没配监控对象」「配了但还没抓到跟卖」「抓到了但这个筛选下没有」
 *    是三件事，不能共用一句话。
 */

export interface ListingHijackerCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface ListingHijackerCapabilities {
  dimensions: ListingHijackerCapabilityDimension[];
  alertTypes: string[];
  severities: string[];
  sources: string[];
  ingestChannels: string[];
  watchStatuses: string[];
  hijackerKinds: string[];
  alertStatuses: string[];
  /** 🔴 判定规则的口径（服务端算，前端原样显示）。 */
  ruleDefinitions: string;
  /** 🔴 数据来源标识（PLATFORM_SCRAPED）。 */
  dataSource: string;
  /** 🔴 数据来源标注（仅供参考，不构成处置结论）。 */
  sourceNote: string;
  /** 🔴 处置动作声明（投诉/举报在平台侧完成）。 */
  actionsNote: string;
  relationNote: string;
}

export interface ListingHijackerPlatformOption {
  code: string;
  name: string;
}

export interface ListingHijackerShopOption {
  id: string;
  code: string;
  name: string;
  platformCode: string;
}

export interface ListingHijackerFilterOptions {
  platforms: ListingHijackerPlatformOption[];
  shops: ListingHijackerShopOption[];
  alertTypes: string[];
  severities: string[];
  sources: string[];
  watchStatuses: string[];
  hijackerKinds: string[];
  alertStatuses: string[];
}

/** 🔴 一条跟卖记录（列表行）。 */
export interface ListingHijackerRecordRow {
  recordId: string;
  watchId: string;
  listingId: string;
  externalItemId: string;
  title: string;
  shopCode: string;
  /** 🔴 所在平台（由 listing → shop → platform 派生）。 */
  platformCode: string;
  hijackerSellerId: string;
  hijackerShopName: string;
  hijackerShopIdentifier: string;
  /** 🔴 OWN_SHOP 己方其他店铺 / EXTERNAL 外部跟卖者（服务端算出来的）。 */
  hijackerKind: string;
  /** 🔴 跟卖价格（平台侧观测值）。 */
  hijackerPrice: number | null;
  currency: string;
  /** 🔴 自家参考售价（人工维护，非平台权威价）。 */
  referencePrice: number | null;
  /** 🔴 价格差 = 自家参考价 − 跟卖价（现算）。 */
  priceGap: number | null;
  /** 🔴 价格差比例（现算；分母为 0 时是 null）。 */
  priceGapRatio: number | null;
  observedAt: string;
  source: string;
  ingestChannel: string;
  batchIdempotencyKey: string;
  receivedAt: string;
  dataSource: string;
  sourceNote: string;
}

export interface ListingHijackerRecordPage {
  items: ListingHijackerRecordRow[];
  page: number;
  size: number;
  total: number;
  alertCount: number;
  watchCount: number;
  dataSource: string;
  sourceNote: string;
}

/** 🔴 一个监控对象（列表行）。 */
export interface ListingHijackerWatchRow {
  watchId: string;
  listingId: string;
  externalItemId: string;
  title: string;
  shopCode: string;
  platformCode: string;
  referencePrice: number | null;
  currency: string;
  priceCutThreshold: number | null;
  priceGapRatioThreshold: number | null;
  status: string;
  note: string;
  recordCount: number;
  openAlertCount: number;
  lastObservedAt: string | null;
  createdAt: string;
}

export interface ListingHijackerWatchPage {
  items: ListingHijackerWatchRow[];
  page: number;
  size: number;
  total: number;
  dataSource: string;
  sourceNote: string;
}

/** 🔴 一条预警记录。 */
export interface ListingHijackerAlertRow {
  alertId: string;
  watchId: string;
  recordId: string;
  listingId: string;
  externalItemId: string;
  title: string;
  /** MALICIOUS_PRICE_CUT 恶意压价 / ANOMALOUS_HIJACK 异常跟卖。 */
  alertType: string;
  severity: string;
  hijackerSellerId: string;
  hijackerShopName: string;
  /** OWN_SHOP / EXTERNAL。 */
  hijackerKind: string;
  observedPrice: number | null;
  currency: string;
  /** 🔴 判定时刻的自家参考售价快照（不是当前值）。 */
  referencePrice: number | null;
  /** 🔴 价格差比例（现算；分母为 0 时是 null）。 */
  priceGapRatio: number | null;
  /** OPEN 未处理 / ACKNOWLEDGED 已阅（内部状态，非平台侧处置）。 */
  status: string;
  reason: string;
  observedAt: string;
  detectedAt: string;
  acknowledgedAt: string | null;
  dataSource: string;
  sourceNote: string;
}

export interface ListingHijackerAlertPage {
  items: ListingHijackerAlertRow[];
  page: number;
  size: number;
  total: number;
  openCount: number;
  dataSource: string;
  sourceNote: string;
}

/** 🔴 对比视图的一行（一个跟卖者一行，服务端聚合）。 */
export interface ListingHijackerComparisonRow {
  hijackerSellerId: string;
  hijackerShopName: string;
  hijackerShopIdentifier: string;
  hijackerKind: string;
  platformCode: string;
  observationCount: number;
  latestPrice: number | null;
  minPrice: number | null;
  maxPrice: number | null;
  avgPrice: number | null;
  referencePrice: number | null;
  currency: string;
  priceGap: number | null;
  priceGapRatio: number | null;
  lastObservedAt: string;
}

export interface ListingHijackerComparisonPage {
  items: ListingHijackerComparisonRow[];
  page: number;
  size: number;
  total: number;
  watchId: string;
  listingId: string;
  externalItemId: string;
  title: string;
  referencePrice: number | null;
  currency: string;
  hijackerCount: number;
  observationCount: number;
  lowestHijackerPrice: number | null;
  averageHijackerPrice: number | null;
  /** 🔴 跟卖最狠的那一个的价格差比例（区间内最大）。 */
  maxPriceGapRatio: number | null;
  openAlertCount: number;
  from: string;
  to: string;
  hasData: boolean;
  emptyReason: string;
  dataSource: string;
  sourceNote: string;
}

// ================================================================ 取数

export async function fetchCapabilities(): Promise<ListingHijackerCapabilities> {
  return api<ListingHijackerCapabilities>('channel/listing-hijackers/capabilities');
}

export async function fetchFilterOptions(): Promise<ListingHijackerFilterOptions> {
  return api<ListingHijackerFilterOptions>('channel/listing-hijackers/filter-options');
}

export async function fetchWatches(params: {
  status?: string;
  platformCode?: string;
  shopCode?: string;
  keyword?: string;
  page?: number;
  size?: number;
}): Promise<ListingHijackerWatchPage> {
  const query = new URLSearchParams();
  if (params.status) query.set('status', params.status);
  if (params.platformCode) query.set('platformCode', params.platformCode);
  if (params.shopCode) query.set('shopCode', params.shopCode);
  if (params.keyword) query.set('keyword', params.keyword);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<ListingHijackerWatchPage>(`channel/listing-hijackers/watches?${query.toString()}`);
}

export async function fetchRecords(params: {
  watchId?: string;
  listingId?: string;
  sellerId?: string;
  hijackerKind?: string;
  platformCode?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}): Promise<ListingHijackerRecordPage> {
  const query = new URLSearchParams();
  if (params.watchId) query.set('watchId', params.watchId);
  if (params.listingId) query.set('listingId', params.listingId);
  if (params.sellerId) query.set('sellerId', params.sellerId);
  if (params.hijackerKind) query.set('hijackerKind', params.hijackerKind);
  if (params.platformCode) query.set('platformCode', params.platformCode);
  if (params.from) query.set('from', params.from);
  if (params.to) query.set('to', params.to);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<ListingHijackerRecordPage>(`channel/listing-hijackers/records?${query.toString()}`);
}

export async function fetchAlerts(params: {
  watchId?: string;
  alertType?: string;
  severity?: string;
  status?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}): Promise<ListingHijackerAlertPage> {
  const query = new URLSearchParams();
  if (params.watchId) query.set('watchId', params.watchId);
  if (params.alertType) query.set('alertType', params.alertType);
  if (params.severity) query.set('severity', params.severity);
  if (params.status) query.set('status', params.status);
  if (params.from) query.set('from', params.from);
  if (params.to) query.set('to', params.to);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<ListingHijackerAlertPage>(`channel/listing-hijackers/alerts?${query.toString()}`);
}

export async function fetchComparison(params: {
  watchId: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}): Promise<ListingHijackerComparisonPage> {
  const query = new URLSearchParams();
  query.set('watchId', params.watchId);
  if (params.from) query.set('from', params.from);
  if (params.to) query.set('to', params.to);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<ListingHijackerComparisonPage>(
    `channel/listing-hijackers/comparison?${query.toString()}`,
  );
}

export async function acknowledgeAlert(alertId: string, note?: string): Promise<void> {
  await api(`channel/listing-hijackers/alerts/${alertId}/acknowledge`, 'PUT', { note: note ?? '' });
}

// ================================================================ 纯函数（可单测）

/** 预警类型 → 中文名。 */
export const ALERT_TYPE_LABELS: Record<string, string> = {
  MALICIOUS_PRICE_CUT: '恶意压价',
  ANOMALOUS_HIJACK: '异常跟卖',
};

/** 跟卖者性质 → 中文名。 */
export const HIJACKER_KIND_LABELS: Record<string, string> = {
  OWN_SHOP: '己方其他店铺',
  EXTERNAL: '外部跟卖者',
};

/** 监控对象状态 → 中文名。 */
export const WATCH_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '监控中',
  PAUSED: '已暂停',
};

export function alertTypeLabel(type: string): string {
  return ALERT_TYPE_LABELS[type] ?? type;
}

export function hijackerKindLabel(kind: string): string {
  return HIJACKER_KIND_LABELS[kind] ?? kind;
}

export function watchStatusLabel(status: string): string {
  return WATCH_STATUS_LABELS[status] ?? status;
}

/**
 * 🔴 金额格式化：两位小数 + 币种。
 *
 * <p>没有值显示「—」—— **不是 0**。跟卖价缺失与跟卖价为 0 是两件事。
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

/**
 * 🔴 比率格式化：**null 显示成「—」，绝不显示成 0%**。
 *
 * <p>服务端在分母（自家参考价）为 0 时返回 null —— 那是「没有参考价」，
 * 不是「跟卖价和自家价一样」。显示成 0% 会让人以为没有人在压价，
 * 那是结论，不是缺口。
 */
export function formatRate(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return `${(value * 100).toFixed(2)}%`;
}

/** 数量格式化（观察次数这类整数）。 */
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

/**
 * 🔴 价格差方向。
 *
 * <p>`unknown` = 服务端给的是 null（没有参考价 / 分母为 0）——
 * 「不知道」和「持平」是两件事，页面必须分开显示。
 */
export function gapDirection(
  ratio: number | null | undefined,
): 'worse' | 'better' | 'flat' | 'unknown' {
  if (ratio === null || ratio === undefined || Number.isNaN(ratio)) return 'unknown';
  if (ratio > 0) return 'worse';
  if (ratio < 0) return 'better';
  return 'flat';
}

/**
 * 🔴 价格差文案。
 *
 * <p>null 时显示「—（没有参考价）」而不是 0% —— 服务端分母为 0 时给的就是 null。
 */
export function gapText(ratio: number | null | undefined): string {
  if (ratio === null || ratio === undefined || Number.isNaN(ratio)) {
    return '—（没有参考价）';
  }
  const rate = `${Math.abs(ratio * 100).toFixed(2)}%`;
  switch (gapDirection(ratio)) {
    case 'worse':
      return `低 ${rate}`;
    case 'better':
      return `高 ${rate}`;
    default:
      return `持平（${rate}）`;
  }
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
 * 🔴 跟卖记录空态文案：区分「没配监控对象」与「配了但没数据」。
 *
 * <p>前者是**还没开始盯**，后者是**盯了但平台还没报** ——
 * 两种情况给同一句提示，运营会以为数据丢了。
 */
export function emptyRecordsMessage(watchCount: number, total: number): string {
  if (watchCount === 0) {
    return '还没有监控任何商品。先在上面建一个监控对象（按 ASIN / listing 配），抓到的跟卖才会出现在这里。';
  }
  if (total === 0) {
    return '这个筛选条件下没有跟卖记录。跟卖信息来自平台抓取 / 平台接口推送，抓到了才会出现 —— 本模块不自己轮询平台。';
  }
  return '没有跟卖记录。';
}

/**
 * 🔴 对比视图空态文案：优先用服务端写明的 emptyReason。
 */
export function emptyComparisonMessage(
  comparison: ListingHijackerComparisonPage | null,
): string {
  if (!comparison) return '';
  return comparison.emptyReason || '这个监控对象在所选区间内没有跟卖数据。';
}

/** 未交付维度（16 合规 / 1 工作台 / 02 调度器 / 05 定价）。 */
export function unavailableDimensions(
  caps: ListingHijackerCapabilities | null,
): ListingHijackerCapabilityDimension[] {
  return (caps?.dimensions ?? []).filter((d) => !d.available);
}

/** 🔴 数据来源标注（页面顶部固定显示）。 */
export function sourceBanner(caps: ListingHijackerCapabilities | null): string {
  return (
    caps?.sourceNote ??
    '🔴 平台抓取 / 平台接口返回的观测值，仅供参考，不构成处置结论：投诉 / 举报 / 申诉在平台侧完成。'
  );
}

/** 🔴 处置动作声明（页面顶部固定显示，紧跟来源标注）。 */
export function actionsBanner(caps: ListingHijackerCapabilities | null): string {
  return (
    caps?.actionsNote ??
    '🔴 本系统不提供投诉 / 举报 / 申诉等处置动作：跟卖处置在平台侧完成，本模块只负责发现与提醒。'
  );
}

/** 🔴 每个价格旁边的来源角标。 */
export const SCRAPED_SOURCE_BADGE = '平台抓取';

/** 🔴 预警行的「仅供参考」角标。 */
export const REFERENCE_ONLY_BADGE = '仅供参考';

/**
 * 🔴 预警状态的展示文案。
 *
 * <p>「已阅」**不代表已在平台侧投诉或举报** —— 这一点必须写在界面上，
 * 否则运营会以为点一下就完事了。
 */
export function alertStatusHint(status: string): string {
  return status === 'ACKNOWLEDGED'
    ? '已阅（内部状态，不代表已在平台侧投诉或举报）'
    : '未处理（处置动作在平台侧完成）';
}
