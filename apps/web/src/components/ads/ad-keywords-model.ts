import { api } from '../inventory/api';

/**
 * 14.1.3.1 关键词管理 前端模型。
 *
 * 蓝图：「关键词管理与分析：搜索词报告、关键词表现（展示、点击、转化、CPC、ACOS）、否定词建议、词频与排名、批量调价与否定。输入：搜索词与关键词数据同步；批量操作参数。输出：关键词报表 + 否定建议 + 批量调整任务。关系：上游 = 平台搜索词报告；下游 = 14.2.1.1 竞价、4.5.1.2 排名。**搜索词报告是发现浪费的入口**：高曝光零转化词应尽快否定，否则预算持续流失。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **ACOS / CPC 不在浏览器里算**。比率全部由服务端 `BigDecimal` 现算，页面只渲染。
 *    浏览器里算一遍就意味着「界面上的 ACOS」与「报表里的 ACOS」会有两个算法。
 *    ���`null` 的含义是**分母为 0（不可比）**，与 `0`（可比但真的是零）相反，
 *    渲染时必须分开。
 * 2. **「没跑这个词」与「还没同步到数据」分开**。`syncStatus` 与 `dataAsOf` 必须显示在
 *    页面顶部 —— 页面上的 0 到底是哪个意思，答案在这两个字段里。
 * 3. **否定必须带依据**。建议行与否定记录都显示曝光 / 点击 / 转化 / 花费 / 销售额 / ACOS
 *    的**实际数值**（服务端现算的快照），不是一个光秃秃的词表。
 * 4. **归一类型与原始类型并存**。每一行都同时显示「原始标记 → 归一类型」；
 *    `UNMAPPED` 是显式值（上游没给归一口径），不是「没有这种广告」。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * 派生比率是服务端 `BigDecimal` 的事。
 */

/** 金额与数量一律 number（JSON number），只原样展示。 */
export type AdMoney = number | null;

/** 🔴 null = 不可比（分母为 0），与 `0`（可比但真的是零）含义相反。 */
export type AdRatio = number | null;

export interface AdCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface AdKeywordCapabilities {
  dimensions: AdCapabilityDimension[];
  keywordStatuses: string[];
  negationTypes: string[];
  negationStatuses: string[];
  bulkActionTypes: string[];
  bulkActionStatuses: string[];
  metricSources: string[];
  metricDefinitions: string;
  frequencyDefinitions: string;
  negativeEvidenceNote: string;
  guardrailNote: string;
  guardrailLimits: Record<string, number | string>;
  relationNote: string;
}

export interface AdKeywordFilterOption {
  code: string;
  name: string;
  platformCode: string;
}

export interface AdKeywordFilterOptions {
  platforms: AdKeywordFilterOption[];
  shops: AdKeywordFilterOption[];
  campaigns: AdKeywordFilterOption[];
  adTypes: AdKeywordFilterOption[];
  currencies: AdKeywordFilterOption[];
}

export interface AdKeywordRow {
  keywordId: string;
  platformId: string;
  platformCode: string;
  shopId: string;
  shopCode: string;
  externalCampaignId: string;
  externalKeywordId: string;
  keyword: string;
  /** 🔴 归一后内部类型；UNMAPPED = 上游没给归一口径。 */
  internalAdType: string;
  /** 🔴 原始平台类型标记。 */
  platformAdType: string;
  bid: AdMoney;
  bidCurrency: string;
  status: string;
  listingKeywordId: string | null;
  note: string;
  impressions: number;
  clicks: number;
  conversions: number;
  spend: AdMoney;
  sales: AdMoney;
  currency: string;
  /** 🔴 ACOS = 花费/销售额；销售额为 0 时是 null（不可比），不是 0。 */
  acos: AdRatio;
  cpc: AdRatio;
  ctr: AdRatio;
  cvr: AdRatio;
  activeDays: number;
  metricAsOf: string | null;
  hasMetrics: boolean;
  /** 🔴 4.5.1.2 口径的名次；null = 平台没报或没关联，不是 0。 */
  naturalRank: number | null;
  adRank: number | null;
  rankingCrawledAt: string | null;
  rankingAccuracyWindowMinutes: number | null;
  frequency: number;
  frequencyListingCount: number;
  negated: boolean;
  latestNegativeId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AdKeywordPage {
  items: AdKeywordRow[];
  page: number;
  size: number;
  total: number;
  /** 🔴 数据截至时间（null = 从未同步过）。 */
  dataAsOf: string | null;
  syncStatus: string;
  syncNote: string;
}

export interface AdKeywordDetail {
  keywordId: string;
  platformId: string;
  platformCode: string;
  shopId: string;
  shopCode: string;
  externalCampaignId: string;
  externalKeywordId: string;
  keyword: string;
  internalAdType: string;
  platformAdType: string;
  bid: AdMoney;
  bidCurrency: string;
  status: string;
  listingKeywordId: string | null;
  note: string;
  impressions: number;
  clicks: number;
  conversions: number;
  spend: AdMoney;
  sales: AdMoney;
  currency: string;
  acos: AdRatio;
  cpc: AdRatio;
  ctr: AdRatio;
  cvr: AdRatio;
  activeDays: number;
  metricAsOf: string | null;
  hasMetrics: boolean;
  naturalRank: number | null;
  adRank: number | null;
  rankingCrawledAt: string | null;
  rankingAccuracyWindowMinutes: number | null;
  frequency: number;
  frequencyListingCount: number;
  searchTermCount: number;
  wastedSearchTermCount: number;
  negativeCount: number;
  hasData: boolean;
  emptyReason: string;
  dataAsOf: string | null;
  syncStatus: string;
  syncNote: string;
}

export interface AdKeywordInput {
  platformId: string;
  shopId: string;
  externalCampaignId: string;
  externalKeywordId: string;
  keyword: string;
  platformAdType: string;
  // 🔴 `exactOptionalPropertyTypes: true` ⇒ 可空字段显式写 `| undefined`，
  //    否则「清空这个字段」在类型上就不成立（表单清空是常态，不是例外）。
  internalAdType?: string | undefined;
  bid?: number | undefined;
  bidCurrency?: string | undefined;
  status?: string | undefined;
  listingKeywordId?: string | null | undefined;
  note?: string | undefined;
}

export interface AdKeywordMetricInput {
  keywordId: string;
  bucketDate: string;
  impressions: number;
  clicks: number;
  conversions: number;
  spend: number;
  sales: number;
  currency: string;
  syncedAt: string;
}

export interface AdKeywordMetricBatchRequest {
  source: string;
  sourceRef: string;
  idempotencyKey: string;
  note: string;
  metrics: AdKeywordMetricInput[];
}

export interface AdKeywordMetricBatchResult {
  replayed: boolean;
  idempotencyKey: string;
  receivedCount: number;
  appliedCount: number;
  skippedCount: number;
  skipped: string[];
  dataAsOf: string | null;
}

export interface AdSearchTermInput {
  keywordId: string;
  bucketDate: string;
  searchTerm: string;
  externalCampaignId: string;
  impressions: number;
  clicks: number;
  conversions: number;
  spend: number;
  sales: number;
  currency: string;
  syncedAt: string;
}

export interface AdSearchTermBatchRequest {
  source: string;
  sourceRef: string;
  idempotencyKey: string;
  note: string;
  terms: AdSearchTermInput[];
}

export interface AdSearchTermBatchResult {
  replayed: boolean;
  idempotencyKey: string;
  receivedCount: number;
  appliedCount: number;
  skippedCount: number;
  skipped: string[];
  dataAsOf: string | null;
}

export interface AdSearchTermRow {
  searchTermId: string | null;
  keywordId: string;
  searchTerm: string;
  keyword: string;
  externalCampaignId: string;
  platformCode: string;
  shopCode: string;
  impressions: number;
  clicks: number;
  conversions: number;
  spend: AdMoney;
  sales: AdMoney;
  currency: string;
  acos: AdRatio;
  cpc: AdRatio;
  ctr: AdRatio;
  cvr: AdRatio;
  /** 🔴 服务端判定的高曝光零转化（浪费）。 */
  wasted: boolean;
  negated: boolean;
  syncedAt: string | null;
  source: string;
}

export interface AdSearchTermPage {
  items: AdSearchTermRow[];
  page: number;
  size: number;
  total: number;
  wastedCount: number;
  dataAsOf: string | null;
  syncStatus: string;
  syncNote: string;
}

export interface AdNegativeSuggestion {
  keywordId: string;
  searchTerm: string;
  keyword: string;
  externalCampaignId: string;
  platformCode: string;
  shopCode: string;
  shopId: string;
  rule: string;
  reason: string;
  impressions: number;
  clicks: number;
  conversions: number;
  spend: AdMoney;
  sales: AdMoney;
  currency: string;
  acos: AdRatio;
  cpc: AdRatio;
  ctr: AdRatio;
  from: string | null;
  to: string | null;
  dataAsOf: string | null;
  suggestedNegationType: string;
  negated: boolean;
}

export interface AdNegativeSuggestionPage {
  items: AdNegativeSuggestion[];
  page: number;
  size: number;
  total: number;
  ruleNote: string;
  from: string | null;
  to: string | null;
  dataAsOf: string | null;
}

export interface AdNegativeInput {
  keywordId?: string | null;
  negationText: string;
  negationType?: string;
  externalCampaignId: string;
  reason: string;
  claimedEvidence?: Record<string, unknown>;
  evidenceFrom?: string;
  evidenceTo?: string;
  suggestedBy?: string;
}

export interface AdNegativeRow {
  negativeId: string;
  keywordId: string | null;
  negationText: string;
  negationType: string;
  status: string;
  externalCampaignId: string;
  shopId: string;
  shopCode: string;
  platformCode: string;
  reason: string;
  /** 🔴 服务端现算的权威依据。 */
  evidence: Record<string, unknown>;
  /** 🔴 调用方自述的依据（只作审计留痕）。 */
  claimedEvidence: Record<string, unknown>;
  evidenceFrom: string | null;
  evidenceTo: string | null;
  suggestedBy: string;
  appliedAt: string | null;
  platformNote: string;
  createdAt: string;
}

export interface AdNegativePage {
  items: AdNegativeRow[];
  page: number;
  size: number;
  total: number;
}

export interface AdBulkBidTarget {
  keywordId: string;
  bidValue?: number | null;
}

export interface AdBulkActionRequest {
  actionType: string;
  shopId: string;
  bidMode?: string;
  currency?: string;
  targets: AdBulkBidTarget[];
  negationType?: string;
  idempotencyKey: string;
  note: string;
}

export interface AdBulkActionItemRow {
  itemId: string | null;
  keywordId: string;
  targetRef: string;
  beforeBid: AdMoney;
  afterBid: AdMoney;
  status: string;
  reason: string;
  result: Record<string, unknown>;
}

export interface AdBulkActionResult {
  actionId: string;
  actionType: string;
  status: string;
  idempotencyKey: string;
  replayed: boolean;
  requestedCount: number;
  appliedCount: number;
  failedCount: number;
  reason: string;
  guardrail: Record<string, unknown>;
  items: AdBulkActionItemRow[];
}

export interface AdBulkActionRow {
  actionId: string;
  actionType: string;
  status: string;
  shopId: string;
  shopCode: string;
  currency: string;
  requestedCount: number;
  appliedCount: number;
  failedCount: number;
  reason: string;
  guardrail: Record<string, unknown>;
  idempotencyKey: string;
  note: string;
  createdAt: string;
}

export interface AdBulkActionPage {
  items: AdBulkActionRow[];
  page: number;
  size: number;
  total: number;
}

export interface AdKeywordReportRow {
  dimensionKey: string | null;
  dimensionValue: string;
  currency: string;
  internalAdType: string;
  platformAdType: string;
  keywordCount: number;
  impressions: number;
  clicks: number;
  conversions: number;
  spend: AdMoney;
  sales: AdMoney;
  acos: AdRatio;
  cpc: AdRatio;
  ctr: AdRatio;
  cvr: AdRatio;
  negatedCount: number;
}

export interface AdKeywordReport {
  dimension: string;
  rows: AdKeywordReportRow[];
  metricDefinitions: string;
  dataAsOf: string | null;
  from: string | null;
  to: string | null;
  syncStatus: string;
  syncNote: string;
  scopeNote: string;
}

export interface AdKeywordFrequencyRow {
  platformCode: string;
  keyword: string;
  currency: string;
  internalAdType: string;
  platformAdType: string;
  keywordCount: number;
  campaignCount: number;
  frequency: number;
  frequencyListingCount: number;
  naturalRank: number | null;
  adRank: number | null;
  rankingCrawledAt: string | null;
  rankingAccuracyWindowMinutes: number | null;
  impressions: number;
  clicks: number;
  conversions: number;
  spend: AdMoney;
  sales: AdMoney;
  acos: AdRatio;
  frequencyDefinitions: string;
}

export interface AdKeywordFrequencyPage {
  items: AdKeywordFrequencyRow[];
  page: number;
  size: number;
  total: number;
  frequencyDefinitions: string;
  dataAsOf: string | null;
}

// ==================================================================== API

export function fetchAdKeywordCapabilities(): Promise<AdKeywordCapabilities> {
  return api<AdKeywordCapabilities>('channel/ad-keywords/capabilities');
}

export function fetchAdKeywordFilterOptions(): Promise<AdKeywordFilterOptions> {
  return api<AdKeywordFilterOptions>('channel/ad-keywords/filter-options');
}

export interface AdKeywordQuery {
  platformCode?: string;
  shopCode?: string;
  externalCampaignId?: string;
  internalAdType?: string;
  platformAdType?: string;
  status?: string;
  keyword?: string;
  negated?: string;
  listingKeywordId?: string;
  from?: string;
  to?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function fetchAdKeywords(query: AdKeywordQuery): Promise<AdKeywordPage> {
  const params = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return;
    params.set(key, String(value));
  });
  const suffix = params.toString();
  return api<AdKeywordPage>(`channel/ad-keywords${suffix ? `?${suffix}` : ''}`);
}

export function fetchAdKeywordDetail(id: string): Promise<AdKeywordDetail> {
  return api<AdKeywordDetail>(`channel/ad-keywords/${id}`);
}

export function saveAdKeyword(id: string | null, input: AdKeywordInput): Promise<AdKeywordRow> {
  return id
    ? api<AdKeywordRow>(`channel/ad-keywords/${id}`, 'PUT', input)
    : api<AdKeywordRow>('channel/ad-keywords', 'POST', input);
}

export function archiveAdKeyword(id: string): Promise<AdKeywordRow> {
  return api<AdKeywordRow>(`channel/ad-keywords/${id}`, 'DELETE');
}

export function ingestAdKeywordMetrics(
  request: AdKeywordMetricBatchRequest,
): Promise<AdKeywordMetricBatchResult> {
  return api<AdKeywordMetricBatchResult>('channel/ad-keywords/metrics', 'POST', request);
}

export function ingestAdSearchTerms(
  request: AdSearchTermBatchRequest,
): Promise<AdSearchTermBatchResult> {
  return api<AdSearchTermBatchResult>('channel/ad-keywords/search-terms', 'POST', request);
}

export interface AdSearchTermQuery {
  platformCode?: string;
  shopCode?: string;
  externalCampaignId?: string;
  searchTerm?: string;
  wasted?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export function fetchAdSearchTerms(query: AdSearchTermQuery): Promise<AdSearchTermPage> {
  const params = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return;
    params.set(key, String(value));
  });
  const suffix = params.toString();
  return api<AdSearchTermPage>(`channel/ad-keywords/search-terms${suffix ? `?${suffix}` : ''}`);
}

export interface AdNegativeSuggestionQuery {
  platformCode?: string;
  shopCode?: string;
  externalCampaignId?: string;
  from?: string;
  to?: string;
  minImpressions?: number;
  minClicks?: number;
  targetAcos?: string;
  page?: number;
  size?: number;
}

export function fetchAdNegativeSuggestions(
  query: AdNegativeSuggestionQuery,
): Promise<AdNegativeSuggestionPage> {
  const params = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return;
    params.set(key, String(value));
  });
  const suffix = params.toString();
  return api<AdNegativeSuggestionPage>(
    `channel/ad-keywords/negative-suggestions${suffix ? `?${suffix}` : ''}`,
  );
}

export function fetchAdNegatives(query: {
  shopCode?: string;
  externalCampaignId?: string;
  negationText?: string;
  status?: string;
}): Promise<AdNegativePage> {
  const params = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return;
    params.set(key, String(value));
  });
  const suffix = params.toString();
  return api<AdNegativePage>(`channel/ad-keywords/negatives${suffix ? `?${suffix}` : ''}`);
}

export function createAdNegative(input: AdNegativeInput): Promise<AdNegativeRow> {
  return api<AdNegativeRow>('channel/ad-keywords/negatives', 'POST', input);
}

export function recordAdNegativeResult(
  id: string,
  applied: boolean,
  platformNote: string,
): Promise<AdNegativeRow> {
  return api<AdNegativeRow>(`channel/ad-keywords/negatives/${id}/result`, 'PUT', {
    applied,
    platformNote,
  });
}

export function createAdBulkAction(request: AdBulkActionRequest): Promise<AdBulkActionResult> {
  return api<AdBulkActionResult>('channel/ad-keywords/bulk-actions', 'POST', request);
}

export function fetchAdBulkActions(query: {
  actionType?: string;
  status?: string;
}): Promise<AdBulkActionPage> {
  const params = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return;
    params.set(key, String(value));
  });
  const suffix = params.toString();
  return api<AdBulkActionPage>(`channel/ad-keywords/bulk-actions${suffix ? `?${suffix}` : ''}`);
}

export function fetchAdKeywordReport(query: {
  dimension?: string;
  from?: string;
  to?: string;
  internalAdType?: string;
  page?: number;
  size?: number;
}): Promise<AdKeywordReport> {
  const params = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return;
    params.set(key, String(value));
  });
  const suffix = params.toString();
  return api<AdKeywordReport>(`channel/ad-keywords/report${suffix ? `?${suffix}` : ''}`);
}

export function fetchAdKeywordFrequency(query: {
  platformCode?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}): Promise<AdKeywordFrequencyPage> {
  const params = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return;
    params.set(key, String(value));
  });
  const suffix = params.toString();
  return api<AdKeywordFrequencyPage>(`channel/ad-keywords/frequency${suffix ? `?${suffix}` : ''}`);
}

// ==================================================================== 纯函数

/** 金额展示：null → 「—」（不可比），否则原样。 */
export function money(value: AdMoney, currency = ''): string {
  if (value === null || value === undefined) return '—';
  const text = value.toFixed(4).replace(/0+$/, '').replace(/\.$/, '');
  return currency ? `${text} ${currency}` : text;
}

/** 🔴 比率展示：null 是**不可比**（分母为 0），必须与 0 分开显示。 */
export function ratio(value: AdRatio, suffix = '%'): string {
  if (value === null || value === undefined) return '不可比';
  return `${(value * 100).toFixed(2)}${suffix}`;
}

/** 比率原值（已是百分数口径时用）。 */
export function ratioRaw(value: AdRatio): string {
  if (value === null || value === undefined) return '不可比';
  return value.toFixed(4);
}

/** 计数展示。 */
export function quantity(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('zh-CN');
}

/** 🔴 名次展示：null = 平台没报 / 没关联，**不是 0**。 */
export function rankText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return `第 ${value} 名`;
}

/** 时间展示（数据截至时间）。 */
export function timestampText(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('zh-CN', { hour12: false });
}

/** 关键词状态标签。 */
export function keywordStatusLabel(status: string): string {
  switch (status) {
    case 'ACTIVE':
      return '在投';
    case 'PAUSED':
      return '暂停';
    case 'ARCHIVED':
      return '已归档';
    default:
      return status || '—';
  }
}

/** 否定状态标签。 */
export function negativeStatusLabel(status: string): string {
  switch (status) {
    case 'DECIDED':
      return '已决定（待推平台）';
    case 'PUSHED':
      return '平台已确认';
    case 'REJECTED':
      return '平台已拒绝';
    default:
      return status || '—';
  }
}

/** 批量任务状态标签。 */
export function bulkStatusLabel(status: string): string {
  switch (status) {
    case 'RECEIVED':
      return '已受理';
    case 'APPLIED':
      return '全部成功';
    case 'PARTIAL':
      return '部分成功';
    case 'REJECTED':
      return '整批拒绝';
    default:
      return status || '—';
  }
}

/** 逐条结果标签。 */
export function bulkItemStatusLabel(status: string): string {
  switch (status) {
    case 'APPLIED':
      return '成功';
    case 'FAILED':
      return '失败';
    case 'SKIPPED':
      return '跳过';
    default:
      return status || '—';
  }
}

/** 否定建议规则标签。 */
export function suggestionRuleLabel(rule: string): string {
  switch (rule) {
    case 'HIGH_IMPRESSION_ZERO_CONVERSION':
      return '高曝光零转化';
    case 'SPEND_NO_CONVERSION':
      return '花了钱没转化';
    case 'HIGH_ACOS':
      return 'ACOS 超标';
    default:
      return rule || '—';
  }
}

/** 归一类型标签（UNMAPPED 是显式值，不是「没有这种广告」）。 */
export function internalTypeLabel(internalAdType: string): string {
  switch (internalAdType) {
    case 'AUTO_DISCOVERY':
      return '自动广告';
    case 'SPONSORED_BRAND':
      return '品牌推广';
    case 'SPONSORED_DISPLAY':
      return '展示型推广';
    case 'BRAND_AD':
      return '品牌广告';
    case 'DSP_PROGRAMMATIC':
      return '程序化 DSP';
    case 'PROMOTION_EVENT':
      return '大促广告';
    case 'UNMAPPED':
      return '未映射（上游没给归一口径）';
    default:
      return internalAdType || '—';
  }
}

/** 🔴 同步状态文案：把「没跑这个词」与「还没同步到数据」分开。 */
export function syncStatusText(page: {
  syncStatus: string;
  dataAsOf: string | null;
  syncNote: string;
}): string {
  if (page.syncStatus === 'NEVER_SYNCED') {
    return '🔴 本租户还没有同步过任何关键词指标或搜索词报告 —— 页面上的 0 是「没数据」，不是「没花钱」。';
  }
  return `🔴 指标与搜索词数据截至 ${timestampText(page.dataAsOf)}（UTC）。`;
}

/** 关键词列表的空态文案（写清下一步做什么）。 */
export function emptyKeywordText(total: number, keyword: string): string {
  if (keyword) return `没有匹配「${keyword}」的关键词。换个词或清空筛选。`;
  if (total === 0) return '还没有登记任何投放关键词。先在下面「登记投放关键词」里建一条。';
  return '没有符合当前筛选的关键词。';
}

/** 搜索词报告的空态文案。 */
export function emptySearchTermText(wastedOnly: boolean): string {
  return wastedOnly
    ? '当前区间里没有「高曝光零转化」的搜索词 —— 这本身就是好消息：没有明显在烧钱的词。'
    : '还没有同步过搜索词报告。先在下面「同步搜索词报告」里导入一批。';
}

/** 否定建议的空态文案。 */
export function emptySuggestionText(): string {
  return '当前区间里没有命中否定规则的搜索词。可以调低「曝光阈值」或给出「目标 ACOS」再试。';
}

/** 否定记录的空态文案。 */
export function emptyNegativeText(): string {
  return '还没有否定记录。可以从上面的「否定词建议」里采纳一条。';
}

/** 批量任务的空态文案。 */
export function emptyBulkActionText(): string {
  return '还没有批量调整任务。';
}

/** 报表的空态文案。 */
export function emptyReportText(): string {
  return '当前区间里没有可聚合的指标数据（报表只统计区间内有指标数据的关键词）。';
}

/** 词频排名的空态文案。 */
export function emptyFrequencyText(): string {
  return '当前区间里没有可聚合的关键词。';
}

/** 🔴 ACOS 的档位（只做展示，不做计算）。 */
export function acosTone(acos: AdRatio, target: number | null): string {
  if (acos === null || acos === undefined) return 'text-muted-foreground';
  if (target === null || target === undefined) return 'text-muted-foreground';
  return acos > target ? 'text-destructive' : 'text-foreground';
}

/** 浪费标记的样式。 */
export function wasteTone(wasted: boolean): string {
  return wasted ? 'text-destructive font-medium' : 'text-muted-foreground';
}

/** 🔴 护栏摘要（从能力声明的 guardrailLimits 取，前端不重算）。 */
export function guardrailSummary(limits: Record<string, number | string> | undefined): string {
  if (!limits) return '';
  const items = limits.maxBatchItems;
  const delta = limits.maxBidDeltaRatio;
  const absolute = limits.maxBidAbsolute;
  const total = limits.maxTotalBidIncrease;
  return [
    `单次最多 ${items ?? '—'} 条`,
    `单条调整幅度 ±${delta === undefined ? '—' : `${Number(delta) * 100}%`}`,
    `单条竞价上限 ${absolute ?? '—'}`,
    `单次总上调上限 ${total ?? '—'}`,
  ].join('；');
}

/** 从依据快照里取一个数值（快照是服务端现算的 Map<string, unknown>）。 */
export function evidenceNumber(
  evidence: Record<string, unknown> | undefined,
  key: string,
): number | null {
  if (!evidence) return null;
  const value = evidence[key];
  if (value === null || value === undefined) return null;
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : null;
}

/** 🔴 依据快照的展示行（否定要能追溯到依据）。 */
export function evidenceLines(evidence: Record<string, unknown> | undefined): string[] {
  if (!evidence) return [];
  const hasData = evidence.hasData === true;
  const currency = typeof evidence.currency === 'string' ? evidence.currency : '';
  const lines = [
    `曝光 ${quantity(evidenceNumber(evidence, 'impressions'))}`,
    `点击 ${quantity(evidenceNumber(evidence, 'clicks'))}`,
    `转化 ${quantity(evidenceNumber(evidence, 'conversions'))}`,
    `花费 ${money(evidenceNumber(evidence, 'spend'), currency)}`,
    `销售额 ${money(evidenceNumber(evidence, 'sales'), currency)}`,
    `ACOS ${ratioRaw(evidenceNumber(evidence, 'acos') === null ? null : Number(evidence.acos))}`,
  ];
  if (!hasData) lines.push('⚠️ 决定那一刻系统没有看到这个词的数据（hasData=false）');
  const asOf = evidence.dataAsOf;
  if (typeof asOf === 'string') lines.push(`数据截至 ${timestampText(asOf)}`);
  return lines;
}

/** 校验登记投放关键词的输入。 */
export function validateKeywordInput(input: AdKeywordInput): { ok: boolean; errors: string[] } {
  const errors: string[] = [];
  if (!input.platformId) errors.push('必须选择平台');
  if (!input.shopId) errors.push('必须选择店铺');
  if (!input.externalCampaignId.trim())
    errors.push('必须填外部活动 id（14.1.1.1 活动列表未交付，用外部引用表达）');
  if (!input.externalKeywordId.trim()) errors.push('必须填外部关键词 id');
  if (!input.keyword.trim()) errors.push('必须填关键词原文');
  if (!input.platformAdType.trim())
    errors.push('必须填原始平台类型标记（归一类型由服务端解析带出）');
  // 🔴 竞价与币种成对：给了竞价没给币种，后端的批量调价护栏就失去意义
  if (input.bid !== undefined && input.bid !== null && !input.bidCurrency?.trim()) {
    errors.push('给了竞价就必须给竞价币种（批量调价按币种计价）');
  }
  if (input.bid !== undefined && input.bid !== null && input.bid < 0) {
    errors.push('竞价不能为负');
  }
  return { ok: errors.length === 0, errors };
}

/** 校验一批指标同步的输入。 */
export function validateMetricBatch(request: AdKeywordMetricBatchRequest): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!request.idempotencyKey.trim()) errors.push('必须填幂等键（重投同一批不重复累加）');
  if (request.metrics.length === 0) errors.push('至少要有一行指标');
  request.metrics.forEach((metric, index) => {
    if (!metric.keywordId) errors.push(`第 ${index + 1} 行：必须选关键词`);
    if (!metric.bucketDate) errors.push(`第 ${index + 1} 行：必须填指标日（UTC 日）`);
    if (!metric.currency.trim()) errors.push(`第 ${index + 1} 行：必须填币种`);
    if (!metric.syncedAt) errors.push(`第 ${index + 1} 行：必须填数据截至时间`);
    if (metric.impressions < 0 || metric.clicks < 0 || metric.conversions < 0) {
      errors.push(`第 ${index + 1} 行：展示 / 点击 / 转化不能为负`);
    }
    if (metric.spend < 0 || metric.sales < 0)
      errors.push(`第 ${index + 1} 行：花费 / 销售额不能为负`);
  });
  return { ok: errors.length === 0, errors };
}

/** 校验一批搜索词报告同步的输入。 */
export function validateSearchTermBatch(request: AdSearchTermBatchRequest): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!request.idempotencyKey.trim()) errors.push('必须填幂等键（重投同一批不重复累加）');
  if (request.terms.length === 0) errors.push('至少要有一行搜索词');
  request.terms.forEach((term, index) => {
    if (!term.keywordId) errors.push(`第 ${index + 1} 行：必须选关键词`);
    if (!term.searchTerm.trim()) errors.push(`第 ${index + 1} 行：必须填搜索词原文`);
    if (!term.externalCampaignId.trim()) errors.push(`第 ${index + 1} 行：必须填外部活动 id`);
    if (!term.currency.trim()) errors.push(`第 ${index + 1} 行：必须填币种`);
    if (!term.syncedAt) errors.push(`第 ${index + 1} 行：必须填数据截至时间`);
  });
  return { ok: errors.length === 0, errors };
}

/** 校验一条否定的输入。 */
export function validateNegative(input: AdNegativeInput): { ok: boolean; errors: string[] } {
  const errors: string[] = [];
  if (!input.keywordId) errors.push('必须选关键词（否定词要挂在具体的投放词下才能回溯）');
  if (!input.negationText.trim()) errors.push('必须填被否定的词原文');
  if (!input.externalCampaignId.trim()) errors.push('必须填外部活动 id（否定词是活动级的）');
  if (!input.reason.trim()) errors.push('必须写明原因（否定要能追溯到依据）');
  return { ok: errors.length === 0, errors };
}

/** 校验一次批量调价 / 否定的输入（护栏在服务端，这里只挡明显不完整的请求）。 */
export function validateBulkAction(request: AdBulkActionRequest): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!request.actionType) errors.push('必须选动作类型');
  if (!request.shopId) errors.push('必须选店铺（一次批量操作限定在同一店铺内）');
  if (!request.idempotencyKey.trim()) errors.push('必须填幂等键（调价是花钱的动作，重投就是事故）');
  if (request.targets.length === 0) errors.push('至少要有一个目标关键词');
  if (request.actionType === 'BID_ADJUST') {
    if (!request.bidMode) errors.push('必须选调价方式（百分比 / 直接设成某个竞价）');
    if (!request.currency?.trim()) errors.push('必须填币种（逐条按币种核对）');
    request.targets.forEach((target, index) => {
      if (target.bidValue === undefined || target.bidValue === null) {
        errors.push(`第 ${index + 1} 个目标：必须给期望的调价值`);
      }
    });
  }
  const seen = new Set<string>();
  request.targets.forEach((target, index) => {
    if (seen.has(target.keywordId)) {
      errors.push(`第 ${index + 1} 个目标：关键词重复（一个关键词在一次批量操作里只能出现一次）`);
    }
    seen.add(target.keywordId);
  });
  return { ok: errors.length === 0, errors };
}

/** 生成一个幂等键（前端只做「这次操作」的标识，不承担幂等语义）。 */
export function newIdempotencyKey(prefix: string): string {
  const random = Math.random().toString(36).slice(2, 10);
  return `${prefix}-${Date.now()}-${random}`;
}

/** 今天的 UTC 日期（指标日 / 证据区间的默认值）。 */
export function todayUtc(): string {
  return new Date().toISOString().slice(0, 10);
}

/** 近 N 天的起始 UTC 日期。 */
export function daysAgoUtc(days: number): string {
  const date = new Date();
  date.setUTCDate(date.getUTCDate() - days);
  return date.toISOString().slice(0, 10);
}
