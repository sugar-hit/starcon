import { api } from '../inventory/api';

/**
 * 14.3.1.3 商品维度报表 前端模型。
 *
 * 蓝图：「商品维度广告效果：SKU/SPU/Listing 维度的广告花费、广告销售额、总销售额、
 * ACOS、TACOS、广告占比利润。输入：商品维度；时间范围；利润数据（13）。
 * 输出：商品广告报表 + 广告销售占比 + 广告后净利。关系：上游 = 14.3.1.1、13.4.1.2；
 * 下游 = 13.4 利润。**广告销售占比（广告单/总单）是判断广告是否带来增量还是抢自然单的关键**，
 * 只看广告 ROAS 无法回答这个问题。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **归因规则写在页面上**。商品维度的广告数字是**从活动级事实分摊出来的**
 *    （活动 → Listing → SKU 两级等分）—— 服务端把规则原文随报表一起返回，
 *    前端**原样展示**，不自己写第二份，也不自己推。
 * 2. **「直接归属」与「等分摊销」分开显示**。`attributionMode` ∈
 *    DIRECT / ALLOCATED_EVENLY / MIXED，界面上带一句话说明 ——
 *    把分摊值当成平台原值看，会做出完全错误的投放决策。
 * 3. **不可比 ≠ 0**。比率列的 `null` 渲染成「不可比」，因为分母为 0 时压根算不出来；
 *    `0` 才是「可比但真的是零」。
 * 4. **「归不到商品的广告花费」看得见**。汇总里单列，不摊给任何商品，也不摊成 0。
 * 5. **不同币种不合并**。汇总按币种拆开（服务端没有汇率源），界面上**按币种分块展示**，
 *    不做跨币种相加 —— 前端一相加就造出一个服务端从没算过的假总数。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * ACOS / ROAS / TACOS / 广告销售占比 / 广告后净利与汇总都是服务端 `BigDecimal` 的事，
 * 前端算一遍就会引入二进制浮点误差，而且会让「界面上的 ACOS」和「报表里的 ACOS」对不上。
 */

/**
 * 金额与数量一律 number（JSON number），只原样展示。
 *
 * 🔴 后端是 `BigDecimal`：Jackson 默认把它序列化成 JSON **number**（运行时到达前端就是
 * number），而 OpenAPI 契约里标注成 `string`（避免契约消费方按 JS number 丢精度）。
 * 两种序列化前端都兜住 —— 展示辅助函数里统一转换，界面上看到的数字永远一致。
 */
export type AdProductMoney = number | string | null;

/** 🔴 null = 不可比（分母为 0），与 `0`（可比但真的是零）含义相反。 */
export type AdProductRatio = number | string | null;

export interface AdProductCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

/** 🔴 单个指标的口径定义 —— 表头就是从这里渲染的。 */
export interface AdProductMetricDefinition {
  metric: string;
  label: string;
  /** false = 当前不出数值（没有分母 / 上游读不到），不是「这一列是 0」。 */
  available: boolean;
  formula: string;
  source: string;
  ownerModule: string;
  unavailableReason: string;
}

export interface AdProductReportCapabilities {
  dimensions: AdProductCapabilityDimension[];
  metricDefinitions: AdProductMetricDefinition[];
  dimensions_: string[];
  sortOptions: string[];
  emptyReasons: string[];
  /** 🔴 归因口径原文（两级等分规则）。 */
  attributionNote: string;
  metricDefinitionNote: string;
  dataAsOfNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface AdProductReportOption {
  value: string;
  label: string;
}

export interface AdProductReportOptions {
  dimensions: AdProductReportOption[];
  platforms: AdProductReportOption[];
  shops: AdProductReportOption[];
  internalAdTypes: AdProductReportOption[];
  statuses: AdProductReportOption[];
  sorts: AdProductReportOption[];
  metricDefinitions: AdProductMetricDefinition[];
  metricDefinitionNote: string;
  /** 🔴 归因口径原文。 */
  attributionNote: string;
  rangeLimitNote: string;
}

export interface AdProductReportRow {
  dimension: string;
  dimensionKey: string;
  dimensionLabel: string;
  currency: string;
  platformName: string;
  shopName: string;
  externalItemId: string;
  listingId: string;
  listingTitle: string;
  skuId: string;
  skuCode: string;
  spuCode: string;
  impressions: AdProductMoney;
  clicks: AdProductMoney;
  orders: AdProductMoney;
  spend: AdProductMoney;
  sales: AdProductMoney;
  ctr: AdProductRatio;
  cpc: AdProductRatio;
  cvr: AdProductRatio;
  acos: AdProductRatio;
  roas: AdProductRatio;
  totalSales: AdProductMoney;
  tacos: AdProductRatio;
  adSalesShare: AdProductRatio;
  netProfit: AdProductMoney;
  adAfterNetProfit: AdProductMoney;
  adProfitShare: AdProductRatio;
  attributionMode: string;
  attributionLabel: string;
  sourceListingCount: number;
  /** 🔴 这一行的利润列为空时的原因（取到了就是空串）。 */
  profitNote: string;
  metricFrom: string | null;
  metricTo: string | null;
}

export interface AdProductReportSummary {
  currency: string;
  groupCount: number;
  totalImpressions: AdProductMoney;
  totalClicks: AdProductMoney;
  totalOrders: AdProductMoney;
  totalSpend: AdProductMoney;
  totalSales: AdProductMoney;
  ctr: AdProductRatio;
  cpc: AdProductRatio;
  cvr: AdProductRatio;
  acos: AdProductRatio;
  roas: AdProductRatio;
  totalRevenue: AdProductMoney;
  tacos: AdProductRatio;
  adSalesShare: AdProductRatio;
  totalNetProfit: AdProductMoney;
  adAfterNetProfit: AdProductMoney;
  adProfitShare: AdProductRatio;
  scopedSpend: AdProductMoney;
  scopedSales: AdProductMoney;
  scopedGroupCount: number;
  scopedScopeNote: string;
  campaignTotalSpend: AdProductMoney;
  campaignTotalSales: AdProductMoney;
  unattributedSpend: AdProductMoney;
  unattributedSales: AdProductMoney;
  metricFrom: string | null;
  metricTo: string | null;
  metricDefinitionNote: string;
  attributionNote: string;
}

export interface AdProductReportPage {
  items: AdProductReportRow[];
  total: number;
  page: number;
  size: number;
  dimension: string;
  /** 🔴 按币种拆开：没有汇率源，不同币种不合并。 */
  summaries: AdProductReportSummary[];
  dataAsOf: string | null;
  dataAsOfNote: string;
  metricDefinitions: AdProductMetricDefinition[];
  emptyReason: string | null;
  emptyNote: string;
  metricDefinitionNote: string;
  attributionNote: string;
  profitAvailable: boolean;
  profitUnavailableReason: string;
  catalogAvailable: boolean;
  catalogUnavailableReason: string;
  candidateTruncated: boolean;
  candidateTruncationNote: string;
}

export interface AdProductReportExportRow {
  id: string;
  requestedBy: string;
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
  attributionNote: string;
  createdAt: string;
}

export interface AdProductReportExportPage {
  items: AdProductReportExportRow[];
  total: number;
  page: number;
  size: number;
}

/**
 * 导出链接的查询形状。
 *
 * 🔴 与 {@link AdProductReportQuery} 分开是因为 `exactOptionalPropertyTypes`：
 * 「这个筛选项没填」是 `undefined`，而可选属性不接受显式的 `undefined`。
 */
export interface AdProductReportExportQuery {
  from?: string | undefined;
  to?: string | undefined;
  dimension?: string | undefined;
  sort?: string | undefined;
  platformId?: string | undefined;
  shopCode?: string | undefined;
  internalAdType?: string | undefined;
  status?: string | undefined;
}

export interface AdProductReportQuery {
  from?: string;
  to?: string;
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

export function fetchAdProductReportCapabilities(): Promise<AdProductReportCapabilities> {
  return api<AdProductReportCapabilities>('channel/ad-product-reports/capabilities');
}

export function fetchAdProductReportOptions(): Promise<AdProductReportOptions> {
  return api<AdProductReportOptions>('channel/ad-product-reports/options');
}

export function fetchAdProductReportRows(
  query: AdProductReportQuery = {},
): Promise<AdProductReportPage> {
  return api<AdProductReportPage>(`channel/ad-product-reports/rows${queryString(query)}`);
}

export function fetchAdProductReportExports(
  page = 0,
  size = 20,
): Promise<AdProductReportExportPage> {
  return api<AdProductReportExportPage>(
    `channel/ad-product-reports/exports${queryString({ page, size })}`,
  );
}

/**
 * 导出链接。
 *
 * 🔴 导出走 GET + query（导出结果与当前筛选完全一致），浏览器同源代理会把
 * `text/csv` 原样透传（不走 JSON.parse，否则 CSV 会被重新序列化成一串带引号的转义文本）。
 */
export function reportExportUrl(query: AdProductReportExportQuery = {}): string {
  return `/api/channel/ad-product-reports/export${queryString(query)}`;
}

// ==================================================================
// 展示辅助
// ==================================================================

/**
 * 金额 / 比率 → number。
 *
 * 🔴 `null` 保持 `null`（不可比）；非数字的串一律当 `null`（**不是 0**）——
 * 把「读不出来」渲染成 0，就是把「算不出来」说成「真的是零」。
 */
export function toNumber(value: AdProductMoney | AdProductRatio | undefined): number | null {
  if (value === null || value === undefined || value === '') return null;
  const num = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(num) ? num : null;
}

export function reportMoney(value: AdProductMoney | undefined, currency?: string): string {
  const num = toNumber(value);
  if (num === null) return '—';
  const fixed = num.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

export function reportQuantity(value: AdProductMoney | undefined): string {
  const num = toNumber(value);
  if (num === null) return '—';
  return num.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

/**
 * 派生比率展示：**不可比显示「不可比」，不是 0.00%**。
 *
 * 🔴 0 是「可比，但真的是零」；null 是「压根算不出来」（分母为 0）。
 */
export function reportRatio(value: AdProductRatio | undefined, asPercent = true): string {
  const num = toNumber(value);
  if (num === null) return '不可比';
  return asPercent ? `${(num * 100).toFixed(2)}%` : num.toFixed(4);
}

export function reportRatioTone(value: AdProductRatio | undefined): string {
  return toNumber(value) === null ? 'text-muted-foreground' : 'text-foreground';
}

/** 指标的口径定义（找不到就返回 undefined）。 */
export function metricDefinitionOf(
  definitions: AdProductMetricDefinition[],
  metric: string,
): AdProductMetricDefinition | undefined {
  return definitions.find((d) => d.metric === metric);
}

/**
 * 表头文字：**指标名 + 口径定义**。
 *
 * 🔴 蓝图原话「口径要在表头写明定义」—— 定义就写在表头里，不用另找一份文档、不用问人。
 */
export function metricHeader(definition: AdProductMetricDefinition | undefined): string {
  if (!definition) return '';
  return `${definition.label}（${definition.formula}）`;
}

/**
 * 未接入指标在表头 / 单元格里的文字。
 *
 * 🔴 「未接入（原因）」而不是 0、也不是空白：没有分母却给一个数，比不给更糟。
 */
export function unavailableMetricText(definition: AdProductMetricDefinition | undefined): string {
  if (!definition) return '未接入';
  return `未接入（${definition.ownerModule}：${definition.unavailableReason}）`;
}

/** 商品维度的显示名。 */
export function reportDimensionLabel(dimension: string): string {
  if (dimension === 'SKU') return '按内部 SKU';
  if (dimension === 'SPU') return '按标准产品单元（SPU）';
  return '按平台商品（Listing）';
}

/**
 * 归因方式的显示名与语气。
 *
 * 🔴 DIRECT = 平台原值；ALLOCATED_EVENLY = 分摊值；MIXED = 混着。
 * 把三者渲染成同一个数字，运营会把分摊值当成平台原值看。
 */
export function attributionModeText(mode: string): string {
  if (mode === 'DIRECT') return '直接归属';
  if (mode === 'MIXED') return '部分直接 / 部分等分';
  return '等分摊销';
}

export function attributionModeTone(mode: string): string {
  if (mode === 'DIRECT') return 'text-emerald-600';
  if (mode === 'MIXED') return 'text-amber-600';
  return 'text-muted-foreground';
}

/**
 * 空态文案：**几种「空」必须分开说**。
 *
 * 🔴 「还没同步到数据」与「这个区间没有投放」与「被筛掉了」与「没登记投放商品」
 * 是四件不同的事，渲染成同一种空态就是把「没数据」说成「没投放」。
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
  if (reason === 'NO_PRODUCT_BINDING') {
    return note || '这个时间区间里确实有广告数据，但没有登记投放商品 —— 广告花费归不到任何商品上。';
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

export interface AdProductRangeValidation {
  ok: boolean;
  errors: string[];
}

/**
 * 时间范围校验。
 *
 * 🔴 时间范围是**必填**的：没有区间就是全表扫，而蓝图加粗句点名「不能实时全表扫」。
 * 前端只做形状校验（缺不填、起止颠倒），区间跨度的上限由服务端判。
 */
export function validateReportRange(from: string, to: string): AdProductRangeValidation {
  const errors: string[] = [];
  if (!from.trim()) errors.push('请选择统计起始日');
  if (!to.trim()) errors.push('请选择统计截止日');
  if (from.trim() && to.trim() && from > to) errors.push('统计起始日不能晚于统计截止日');
  return { ok: errors.length === 0, errors };
}
