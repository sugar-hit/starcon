import { api } from '../inventory/api';

/**
 * 14.3.1.2 广告组与词报表 前端模型。
 *
 * 蓝图：「广告组与关键词维度报表：单组/单词效果、搜索词质量、否定效果评估，支持批量选中导出。
 * 输入：维度选择（组/词/搜索词）；时间范围。输出：明细报表 + 汇总。
 * 关系：上游 = 14.1.3.1；下游 = 14.1.3.1 否定决策。
 * **关键词级数据量极大**，查询必须分页与缓存，不能实时全表扫。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **组级指标的「没有」看得见**。组维度**只给结构事实**（组名 / 状态 / 默认竞价 /
 *    投放商品数 / 所属活动），指标列渲染成「未接入（原因）」，**不是 0、也不是空白** ——
 *    没有事实源却给一个数，比不给更糟。
 * 2. **口径写在表头，不是写在注释里**。`metricDefinitions` 随报表一起返回，每一项带
 *    `formula`（口径定义）。表头直接渲染它 —— 跨部门沟通时「ACOS 到底是哪个除哪个」
 *    必须是同一份定义，而不是各人自己解释一遍。
 * 3. **不可比 ≠ 0**。比率列的 `null` 渲染成「不可比」，因为分母为 0 时压根算不出来；
 *    `0` 才是「可比但真的是零」。把两者渲染成同一个 0.00% 就是把「算不出来」说成「真的是零」。
 * 4. **「否定后仍有数据」不是 0**。它是**识别出来的事实**，单独一列 +
 *    评估结论，绝不混进「否定后花费 = 0」里。
 * 5. **不同币种不合并**。汇总按币种拆开（服务端没有汇率源），界面上**按币种分块展示**，
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

export interface AdGroupReportCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

/** 🔴 单个指标的口径定义 —— 表头就是从这里渲染的。 */
export interface AdGroupReportMetricDefinition {
  metric: string;
  label: string;
  /** false = 当前不出数值（没有分母），不是「这一列是 0」。 */
  available: boolean;
  formula: string;
  source: string;
  ownerModule: string;
  unavailableReason: string;
}

export interface AdGroupKeywordReportCapabilities {
  dimensions: AdGroupReportCapabilityDimension[];
  metricDefinitions: AdGroupReportMetricDefinition[];
  dimensions_: string[];
  granularities: string[];
  /** 🔴 每个维度各自的排序白名单（维度不同，可排的列也不同）。 */
  sortOptionsByDimension: Record<string, string[]>;
  emptyReasons: string[];
  negativeEffectNote: string;
  metricDefinitionNote: string;
  dataAsOfNote: string;
  cacheNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface AdGroupKeywordReportOption {
  value: string;
  label: string;
}

export interface AdGroupKeywordReportOptions {
  dimensions: AdGroupKeywordReportOption[];
  granularities: AdGroupKeywordReportOption[];
  platforms: AdGroupKeywordReportOption[];
  shops: AdGroupKeywordReportOption[];
  internalAdTypes: AdGroupKeywordReportOption[];
  statuses: AdGroupKeywordReportOption[];
  sortsByDimension: Record<string, AdGroupKeywordReportOption[]>;
  metricDefinitions: AdGroupReportMetricDefinition[];
  metricDefinitionNote: string;
  negativeEffectNote: string;
  rangeLimitNote: string;
  cacheNote: string;
  statusNote: string;
}

export interface AdGroupKeywordReportRow {
  dimension: string;
  dimensionKey: string;
  dimensionLabel: string;
  currency: string;
  platformName: string | null;
  shopName: string | null;
  externalCampaignId: string | null;
  impressions: AdReportMoney;
  clicks: AdReportMoney;
  conversions: AdReportMoney;
  spend: AdReportMoney;
  sales: AdReportMoney;
  ctr: AdReportRatio;
  cpc: AdReportRatio;
  cvr: AdReportRatio;
  acos: AdReportRatio;
  roas: AdReportRatio;
  activeDays: number | null;
  periodStart: string | null;
  periodEnd: string | null;
  metricFrom: string | null;
  metricTo: string | null;
  groupStatus: string | null;
  externalGroupId: string | null;
  campaignName: string | null;
  campaignStatus: string | null;
  defaultBid: string | null;
  dailyBudget: string | null;
  bidCurrency: string | null;
  targetingType: string | null;
  productCount: number | null;
  keywordText: string | null;
  externalKeywordId: string | null;
  internalAdType: string | null;
  platformAdType: string | null;
  keywordStatus: string | null;
  bid: string | null;
  searchTerm: string | null;
  sourceKeywordCount: number | null;
  sourceCampaignCount: number | null;
  negationText: string | null;
  negationType: string | null;
  negationStatus: string | null;
  effectiveDate: string | null;
  evidenceFrom: string | null;
  evidenceTo: string | null;
  appliedAt: string | null;
  evaluationOutcome: string | null;
  evaluationNote: string | null;
  preImpressions: AdReportMoney;
  preClicks: AdReportMoney;
  preConversions: AdReportMoney;
  preSpend: AdReportMoney;
  preSales: AdReportMoney;
  postImpressions: AdReportMoney;
  postClicks: AdReportMoney;
  postConversions: AdReportMoney;
  postSpend: AdReportMoney;
  postSales: AdReportMoney;
  postStillReporting: boolean | null;
  zeroConversionWaste: boolean | null;
  dataAsOf: string | null;
  note: string;
}

export interface AdGroupKeywordReportSummary {
  currency: string;
  rowCount: number;
  entityCount: number;
  totalImpressions: AdReportMoney;
  totalClicks: AdReportMoney;
  totalConversions: AdReportMoney;
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

/** 🔴 缓存信息：命中状态 + 失效依据 + **明确的过期时刻**。 */
export interface AdGroupKeywordReportCacheInfo {
  status: string;
  cacheKey: string;
  dataAsOf: string | null;
  sourceBatchKey: string;
  sourceWatermark: string;
  expiresAt: string | null;
  note: string;
}

export interface AdGroupKeywordReportPage {
  items: AdGroupKeywordReportRow[];
  total: number;
  page: number;
  size: number;
  dimension: string;
  granularity: string;
  summaries: AdGroupKeywordReportSummary[];
  dataAsOf: string | null;
  dataAsOfNote: string;
  metricDefinitions: AdGroupReportMetricDefinition[];
  metricDefinitionNote: string;
  negativeEffectNote: string;
  emptyReason: string | null;
  emptyNote: string;
  cache: AdGroupKeywordReportCacheInfo;
  note: string;
}

export interface AdGroupKeywordReportExportRow {
  id: string;
  requestedBy: string;
  dimension: string;
  granularity: string;
  platformId: string | null;
  shopCode: string | null;
  internalAdType: string | null;
  status: string | null;
  externalCampaignId: string | null;
  keywordText: string | null;
  dateFrom: string | null;
  dateTo: string | null;
  rowCount: number;
  selection: string[];
  dataAsOf: string | null;
  metricDefinitionNote: string;
  createdAt: string;
}

export interface AdGroupKeywordReportExportPage {
  items: AdGroupKeywordReportExportRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdGroupKeywordReportQuery {
  dimension?: string;
  granularity?: string;
  sort?: string;
  from?: string;
  to?: string;
  platformId?: string;
  shopCode?: string;
  internalAdType?: string;
  status?: string;
  externalCampaignId?: string;
  keywordText?: string;
  page?: number;
  size?: number;
}

/**
 * 🔴 导出请求（批量选中导出：选了哪几条也进请求体）。
 *
 * <p>与 {@link AdGroupKeywordReportQuery} 分开是因为 `exactOptionalPropertyTypes`：
 * 「这个筛选项没填」是 `undefined`，而可选属性不接受显式的 `undefined`。
 * 导出请求从当前筛选拼出来，每一个键都可能是空的。
 */
export interface AdGroupKeywordReportExportRequest {
  dimension?: string | undefined;
  granularity?: string | undefined;
  sort?: string | undefined;
  from?: string | undefined;
  to?: string | undefined;
  platformId?: string | undefined;
  shopCode?: string | undefined;
  internalAdType?: string | undefined;
  status?: string | undefined;
  externalCampaignId?: string | undefined;
  keywordText?: string | undefined;
  selectedKeys?: string[] | undefined;
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

export function fetchAdGroupKeywordReportCapabilities(): Promise<AdGroupKeywordReportCapabilities> {
  return api<AdGroupKeywordReportCapabilities>('channel/ad-group-keyword-reports/capabilities');
}

export function fetchAdGroupKeywordReportOptions(): Promise<AdGroupKeywordReportOptions> {
  return api<AdGroupKeywordReportOptions>('channel/ad-group-keyword-reports/options');
}

export function fetchAdGroupKeywordReportRows(
  query: AdGroupKeywordReportQuery = {},
): Promise<AdGroupKeywordReportPage> {
  return api<AdGroupKeywordReportPage>(
    `channel/ad-group-keyword-reports/rows${queryString(query)}`,
  );
}

/**
 * 🔴 批量选中导出：走 **POST + body**。
 *
 * 选中的是**一串 id**，塞进 URL 会有长度上限，而且「选了哪几条」是一次**动作**
 * （一次导出），不是「读一个集合」。服务端把选中集合进 SQL 的 WHERE，
 * 不会只导到第一页为止。
 */
export function createAdGroupKeywordReportExport(
  request: AdGroupKeywordReportExportRequest = {},
): Promise<AdGroupKeywordReportExportRow> {
  return api<AdGroupKeywordReportExportRow>(
    'channel/ad-group-keyword-reports/exports',
    'POST',
    request,
  );
}

export function fetchAdGroupKeywordReportExports(
  page = 0,
  size = 20,
): Promise<AdGroupKeywordReportExportPage> {
  return api<AdGroupKeywordReportExportPage>(
    `channel/ad-group-keyword-reports/exports${queryString({ page, size })}`,
  );
}

/** 导出 CSV 的下载链接（按导出留痕 id 下载，与当时的筛选与选中完全一致）。 */
export function exportCsvUrl(exportId: string): string {
  return `/api/channel/ad-group-keyword-reports/exports/${exportId}/csv`;
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
  definitions: AdGroupReportMetricDefinition[],
  metric: string,
): AdGroupReportMetricDefinition | undefined {
  return definitions.find((d) => d.metric === metric);
}

/**
 * 表头文字：**指标名 + 口径定义**。
 *
 * 🔴 蓝图要求「口径要在表头写明定义」—— 定义就写在表头里，
 * 不用另找一份文档、不用问人。
 */
export function metricHeader(definition: AdGroupReportMetricDefinition | undefined): string {
  if (!definition) return '';
  return `${definition.label}（${definition.formula}）`;
}

/**
 * 未接入指标在表头 / 单元格里的文字。
 *
 * 🔴 「未接入（原因）」而不是 0、也不是空白：没有分母却给一个数，比不给更糟。
 */
export function unavailableMetricText(
  definition: AdGroupReportMetricDefinition | undefined,
): string {
  if (!definition) return '未接入';
  return `未接入（${definition.ownerModule}：${definition.unavailableReason}）`;
}

/** 维度的显示名。 */
export function reportDimensionLabel(dimension: string): string {
  if (dimension === 'GROUP') return '按广告组';
  if (dimension === 'SEARCH_TERM') return '按搜索词';
  if (dimension === 'NEGATIVE_EFFECT') return '按否定记录';
  return '按关键词';
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
    return note || '还没有任何一次成功的数据同步 —— 这不是「没有投放」，是还没同步到数据。';
  }
  if (reason === 'NO_METRICS_IN_RANGE') {
    return (
      note || '同步成功过，但所选时间区间里没有任何投放指标 —— 这次是真的「这段时间没有投放数据」。'
    );
  }
  if (reason === 'FILTERED_EMPTY') {
    return note || '这个时间区间里确实有投放数据，但被当前的筛选条件全部排除了。放宽条件再试。';
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
// 否定效果评估
// ==================================================================

/**
 * 🔴 否定效果评估结论的显示名。
 *
 * <p>「否定后仍有数据」与「否定后无数据」是**两件相反的事**，
 * 渲染成同一个「否」就是把最重要的一条结论说反了。
 */
export function evaluationOutcomeText(outcome: string | null): string {
  switch (outcome) {
    case 'STILL_REPORTING':
      return '否定后仍有数据';
    case 'NO_DATA_AFTER':
      return '否定后无数据';
    case 'ZERO_SPEND_AFTER':
      return '否定后有数据但无花费';
    case 'NOT_EVALUABLE':
      return '无法评估（没有生效日）';
    default:
      return '—';
  }
}

/** 🔴 评估结论的语气色（红色 = 需要人去看）。 */
export function evaluationOutcomeTone(outcome: string | null): string {
  if (outcome === 'STILL_REPORTING') return 'text-destructive';
  if (outcome === 'NOT_EVALUABLE') return 'text-amber-600';
  return 'text-foreground';
}

/**
 * 🔴「否定后仍有数据」这一格的文字。
 *
 * <p>{@code null} = 无法评估（没有生效日）⇒ 写「无法评估」，
 * **不是**「否」—— 「否」会被读成「否定生效了」，那是假事实。
 */
export function stillReportingText(value: boolean | null | undefined): string {
  if (value === null || value === undefined) return '无法评估';
  return value ? '是' : '否';
}

// ==================================================================
// 缓存
// ==================================================================

/**
 * 🔴 缓存状态的显示名。
 *
 * <p>「缓存了但不知道什么时候过期」是最不可接受的一种结果，
 * 所以页面上永远显示**状态 + 过期时刻**，不只显示「已缓存」。
 */
export function cacheStatusText(status: string | null | undefined): string {
  switch (status) {
    case 'HIT':
      return '命中缓存';
    case 'MISS':
      return '本次现算（首次）';
    case 'STALE':
      return '缓存已失效（数据变了），本次现算';
    case 'BYPASS':
      return '不缓存（没有失效依据）';
    default:
      return '—';
  }
}

export function cacheStatusTone(status: string | null | undefined): string {
  if (status === 'HIT') return 'text-muted-foreground';
  if (status === 'BYPASS') return 'text-amber-600';
  return 'text-foreground';
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

/**
 * 🔴 导出前的形状校验（与报表的筛选校验同一套）。
 *
 * <p>选中集合也要校验：**空数组是合法的**（= 导出当前筛选的全部），
 * 但选中超过服务端上限（500 条）会被拒 —— 与其让服务端 400，不如在页面上先说清楚。
 */
export const MAX_SELECTED_KEYS = 500;

export function validateExportSelection(keys: string[]): AdReportRangeValidation {
  const errors: string[] = [];
  if (keys.length > MAX_SELECTED_KEYS) {
    errors.push(`一次最多选中 ${MAX_SELECTED_KEYS} 条，当前 ${keys.length} 条`);
  }
  return { ok: errors.length === 0, errors };
}
