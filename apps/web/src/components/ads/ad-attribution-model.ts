import { api } from '../inventory/api';

/**
 * 14.3.2.1 归因分析 前端模型。
 *
 * 蓝图：「广告归因：把广告花费归因到 SKU/订单（支持平台归因窗口口径），计算广告后净利、
 * TACOS、增量效果估计。输入：平台广告归因数据；订单数据；利润数据；归因规则配置（末次点击/平台归因窗口）。
 * 输出：归因明细 + 广告后利润报表。关系：上游 = 14.3.1.3、13.4.1.2；下游 = 13.4 利润、5 定价。
 * **归因规则必须固定并公示**，规则变了历史报表不可比——这是广告与财务对账最容易吵架的点。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **归因规则写在页面上**。服务端把**规则原文**（`attributionNote`）与
 *    **增量效果估计的口径原文**（`incrementalityNote`）随响应一起返回，页面**原样展示** ——
 *    前端不自己写第二份规则，也不自己推。
 * 2. **规则快照随每一行走**。明细与报表的每一行都带 `ruleCode` / `ruleVersion` /
 *    `attributionModel` / `attributionWindowDays` ⇒ 事后能解释「这一行是按哪套口径归出来的」。
 * 3. **归不上的钱看得见**。汇总里单列 `unattributedSpend` / `unattributedSales`，
 *    **不摊给任何 SKU，也不摊成 0**（摊成 0 就是说「这些广告没花钱」）。
 * 4. **不可比 ≠ 0**。比率列的 `null` 渲染成「不可比」，`0` 才是「可比但真的是零」。
 * 5. **增量效果是推导值**。页面上标为「估算」，并把口径与边界（**不是因果增量**）一起显示 ——
 *    把它当平台原值看会做出错误的投放决策。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * TACOS / ACOS / 广告销售占比 / 广告后净利 / 增量效果估计与汇总都是服务端 `BigDecimal` 的事，
 * 前端算一遍就会引入二进制浮点误差，而且会让「界面上的 TACOS」和「报表里的 TACOS」对不上。
 */

/**
 * 金额与数量一律 number（JSON number），只原样展示。
 *
 * 🔴 后端是 `BigDecimal`：Jackson 默认把它序列化成 JSON **number**（运行时到达前端就是
 * number），而 OpenAPI 契约里标注成 `string`（避免契约消费方按 JS number 丢精度）。
 * 两种序列化前端都兜住 —— 展示辅助函数里统一转换，界面上看到的数字永远一致。
 */
export type AdAttributionMoney = number | string | null;

/** 🔴 null = 不可比（分母为 0），与 `0`（可比但真的是零）含义相反。 */
export type AdAttributionRatio = number | string | null;

export interface AdAttributionCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

/** 🔴 单个指标的口径定义 —— 表头就是从这里渲染的。 */
export interface AdAttributionMetricDefinition {
  metric: string;
  label: string;
  /** false = 当前不出数值（没有分母 / 上游读不到），不是「这一列是 0」。 */
  available: boolean;
  formula: string;
  source: string;
  ownerModule: string;
  unavailableReason: string;
}

export interface AdAttributionCapabilities {
  dimensions: AdAttributionCapabilityDimension[];
  metricDefinitions: AdAttributionMetricDefinition[];
  attributionModels: string[];
  attributionStatuses: string[];
  emptyReasons: string[];
  /** 🔴 归因规则原文。 */
  attributionNote: string;
  metricDefinitionNote: string;
  /** 🔴 增量效果估计的口径原文（推导值，不是平台原值）。 */
  incrementalityNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface AdAttributionOption {
  value: string;
  label: string;
}

export interface AdAttributionOptions {
  attributionModels: AdAttributionOption[];
  attributionStatuses: AdAttributionOption[];
  sorts: AdAttributionOption[];
  shops: AdAttributionOption[];
  platforms: AdAttributionOption[];
  metricDefinitions: AdAttributionMetricDefinition[];
  metricDefinitionNote: string;
  attributionNote: string;
  incrementalityNote: string;
  rangeLimitNote: string;
}

/** 一条归因规则。 */
export interface AdAttributionRule {
  id: string;
  ruleCode: string;
  /** LAST_CLICK / PLATFORM_WINDOW */
  attributionModel: string;
  attributionWindowDays: number;
  status: string;
  /** 🔴 规则版本（每次改动 +1）—— 历史口径可追。 */
  version: number;
  note: string;
  createdAt: string;
  updatedAt: string;
}

export interface AdAttributionRulePage {
  items: AdAttributionRule[];
  total: number;
  page: number;
  size: number;
}

/** 保存归因规则的请求体。 */
export interface AdAttributionRuleRequest {
  id?: string | undefined;
  ruleCode: string;
  attributionModel: string;
  attributionWindowDays: number;
  note: string;
  activate: boolean;
}

/** 一次点击。 */
export interface AdAttributionClickImport {
  clickId: string;
  clickAt: string;
  platformAttributed: boolean;
}

/** 一条订单级归因候选。 */
export interface AdAttributionOrderImport {
  campaignId?: string | undefined;
  externalCampaignId?: string | undefined;
  shopCode?: string | undefined;
  platformCode?: string | undefined;
  externalOrderNo: string;
  orderAt: string;
  internalSkuId?: string | undefined;
  currency?: string | undefined;
  attributedSpend?: number | undefined;
  attributedSales?: number | undefined;
  attributedOrders?: number | undefined;
  attributedUnits?: number | undefined;
  clicks?: AdAttributionClickImport[] | undefined;
}

export interface AdAttributionImportRequest {
  dataAsOf: string;
  orders: AdAttributionOrderImport[];
}

export interface AdAttributionImportResult {
  orderRows: number;
  writtenOrderRows: number;
  writtenClickRows: number;
  /** 🔴 被拒绝的行逐条列明原因，不静默丢弃。 */
  rejected: string[];
  dataAsOf: string;
  note: string;
}

export interface AdAttributionStatusCount {
  status: string;
  label: string;
  count: number;
}

export interface AdAttributionRunResult {
  ruleId: string;
  ruleCode: string;
  ruleVersion: number;
  attributionModel: string;
  attributionWindowDays: number;
  candidateOrders: number;
  writtenFacts: number;
  attributedFacts: number;
  unattributedFacts: number;
  statusCounts: AdAttributionStatusCount[];
  note: string;
}

/** 归因明细的一行。 */
export interface AdAttributionRecord {
  id: string;
  externalOrderNo: string;
  platformCode: string;
  shopCode: string;
  campaignId: string;
  campaignName: string;
  /** null = 归到了订单但归不到唯一内部 SKU。 */
  internalSkuId: string | null;
  currency: string;
  orderAt: string;
  attributionStatus: string;
  attributionStatusLabel: string;
  /** 🔴 这一行进不进 SKU 报表。 */
  attributed: boolean;
  chosenClickId: string;
  chosenClickAt: string | null;
  eligibleClickCount: number;
  attributedSpend: AdAttributionMoney;
  attributedSales: AdAttributionMoney;
  attributedOrders: AdAttributionMoney;
  attributedUnits: AdAttributionMoney;
  ruleCode: string;
  ruleVersion: number;
  attributionModel: string;
  attributionWindowDays: number;
  attributedAt: string;
}

export interface AdAttributionRecordSummary {
  currency: string;
  rowCount: number;
  attributedRows: number;
  unattributedRows: number;
  totalSpend: AdAttributionMoney;
  totalSales: AdAttributionMoney;
  totalOrders: AdAttributionMoney;
  totalUnits: AdAttributionMoney;
  attributedSpend: AdAttributionMoney;
  attributedSales: AdAttributionMoney;
  unattributedSpend: AdAttributionMoney;
  unattributedSales: AdAttributionMoney;
  unattributedOrders: AdAttributionMoney;
  statusCounts: AdAttributionStatusCount[];
  attributionNote: string;
}

export interface AdAttributionRecordPage {
  items: AdAttributionRecord[];
  total: number;
  page: number;
  size: number;
  ruleId: string | null;
  ruleCode: string;
  ruleVersion: number;
  attributionModel: string;
  attributionWindowDays: number;
  summaries: AdAttributionRecordSummary[];
  emptyReason: string | null;
  emptyNote: string;
  metricDefinitionNote: string;
  attributionNote: string;
  incrementalityNote: string;
}

/** 广告后利润报表的一行。 */
export interface AdAttributionReportRow {
  skuId: string;
  skuCode: string;
  spuCode: string;
  currency: string;
  adSpend: AdAttributionMoney;
  adSales: AdAttributionMoney;
  adOrders: AdAttributionMoney;
  adUnits: AdAttributionMoney;
  totalSales: AdAttributionMoney;
  totalUnits: AdAttributionMoney;
  netProfit: AdAttributionMoney;
  profitAdFee: AdAttributionMoney;
  acos: AdAttributionRatio;
  tacos: AdAttributionRatio;
  adSalesShare: AdAttributionRatio;
  adAfterNetProfit: AdAttributionMoney;
  adProfitShare: AdAttributionRatio;
  /** 🔴 推导值，不是平台原值。 */
  incrementalSalesEstimate: AdAttributionMoney;
  incrementalShare: AdAttributionRatio;
  profitNote: string;
  ruleCode: string;
  ruleVersion: number;
  attributionModel: string;
  attributionWindowDays: number;
}

export interface AdAttributionReportSummary {
  currency: string;
  skuCount: number;
  attributedSpend: AdAttributionMoney;
  attributedSales: AdAttributionMoney;
  attributedOrders: AdAttributionMoney;
  attributedUnits: AdAttributionMoney;
  /** 🔴 归不上的钱，单列。 */
  unattributedSpend: AdAttributionMoney;
  unattributedSales: AdAttributionMoney;
  unattributedOrders: AdAttributionMoney;
  campaignTotalSpend: AdAttributionMoney;
  campaignSpendGap: AdAttributionMoney;
  attributionCoverage: AdAttributionRatio;
  totalRevenue: AdAttributionMoney;
  totalUnits: AdAttributionMoney;
  totalNetProfit: AdAttributionMoney;
  acos: AdAttributionRatio;
  tacos: AdAttributionRatio;
  adSalesShare: AdAttributionRatio;
  adAfterNetProfit: AdAttributionMoney;
  adProfitShare: AdAttributionRatio;
  incrementalSalesEstimate: AdAttributionMoney;
  incrementalShare: AdAttributionRatio;
  metricDefinitionNote: string;
  attributionNote: string;
  incrementalityNote: string;
}

export interface AdAttributionReportPage {
  items: AdAttributionReportRow[];
  total: number;
  page: number;
  size: number;
  ruleId: string | null;
  ruleCode: string;
  ruleVersion: number;
  attributionModel: string;
  attributionWindowDays: number;
  summaries: AdAttributionReportSummary[];
  dataAsOf: string | null;
  emptyReason: string | null;
  emptyNote: string;
  metricDefinitions: AdAttributionMetricDefinition[];
  metricDefinitionNote: string;
  attributionNote: string;
  incrementalityNote: string;
  profitAvailable: boolean;
  profitUnavailableReason: string;
  catalogAvailable: boolean;
  catalogUnavailableReason: string;
}

export interface AdAttributionExport {
  id: string;
  requestedBy: string;
  ruleId: string;
  ruleCode: string;
  ruleVersion: number;
  attributionModel: string;
  attributionWindowDays: number;
  shopCode: string | null;
  campaignId: string | null;
  skuId: string | null;
  currency: string | null;
  dateFrom: string | null;
  dateTo: string | null;
  rowCount: number;
  dataAsOf: string | null;
  metricDefinitionNote: string;
  attributionNote: string;
  createdAt: string;
}

export interface AdAttributionExportPage {
  items: AdAttributionExport[];
  total: number;
  page: number;
  size: number;
}

/**
 * 查询形状。
 *
 * 🔴 与导出链接的形状分开是因为 `exactOptionalPropertyTypes`：
 * 「这个筛选项没填」是 `undefined`，而可选属性不接受显式的 `undefined`。
 */
export interface AdAttributionQuery {
  ruleId?: string | undefined;
  ruleVersion?: number | undefined;
  from?: string | undefined;
  to?: string | undefined;
  status?: string | undefined;
  shopCode?: string | undefined;
  campaignId?: string | undefined;
  skuId?: string | undefined;
  platformCode?: string | undefined;
  currency?: string | undefined;
  keyword?: string | undefined;
  sort?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 归因计算请求。 */
export interface AdAttributionRunRequest {
  ruleId?: string | undefined;
  from: string;
  to: string;
  recompute: boolean;
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

export function fetchAdAttributionCapabilities(): Promise<AdAttributionCapabilities> {
  return api<AdAttributionCapabilities>('channel/ad-attributions/capabilities');
}

export function fetchAdAttributionOptions(): Promise<AdAttributionOptions> {
  return api<AdAttributionOptions>('channel/ad-attributions/options');
}

export function fetchAdAttributionRules(
  status?: string,
  keyword?: string,
  page = 0,
  size = 20,
): Promise<AdAttributionRulePage> {
  return api<AdAttributionRulePage>(
    `channel/ad-attributions/rules${queryString({ status, keyword, page, size })}`,
  );
}

export function fetchAdAttributionCurrentRule(): Promise<AdAttributionRule | null> {
  return api<AdAttributionRule | null>('channel/ad-attributions/rules/current');
}

export function saveAdAttributionRule(
  request: AdAttributionRuleRequest,
): Promise<AdAttributionRule> {
  return api<AdAttributionRule>('channel/ad-attributions/rules', 'POST', request);
}

export function importAdAttribution(
  request: AdAttributionImportRequest,
): Promise<AdAttributionImportResult> {
  return api<AdAttributionImportResult>('channel/ad-attributions/import', 'POST', request);
}

export function runAdAttribution(
  request: AdAttributionRunRequest,
): Promise<AdAttributionRunResult> {
  return api<AdAttributionRunResult>('channel/ad-attributions/run', 'POST', request);
}

export function fetchAdAttributionRecords(
  query: AdAttributionQuery = {},
): Promise<AdAttributionRecordPage> {
  return api<AdAttributionRecordPage>(`channel/ad-attributions/records${queryString(query)}`);
}

export function fetchAdAttributionReport(
  query: AdAttributionQuery = {},
): Promise<AdAttributionReportPage> {
  return api<AdAttributionReportPage>(`channel/ad-attributions/report${queryString(query)}`);
}

export function fetchAdAttributionExports(page = 0, size = 20): Promise<AdAttributionExportPage> {
  return api<AdAttributionExportPage>(
    `channel/ad-attributions/exports${queryString({ page, size })}`,
  );
}

/**
 * 导出链接。
 *
 * 🔴 导出走 GET + query（导出结果与当前筛选完全一致），浏览器同源代理会把
 * `text/csv` 原样透传（不走 JSON.parse，否则 CSV 会被重新序列化成一串带引号的转义文本）。
 */
export function attributionExportUrl(query: AdAttributionQuery = {}): string {
  return `/api/channel/ad-attributions/export${queryString(query)}`;
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
export function toNumber(
  value: AdAttributionMoney | AdAttributionRatio | undefined,
): number | null {
  if (value === null || value === undefined || value === '') return null;
  const num = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(num) ? num : null;
}

export function attributionMoney(value: AdAttributionMoney | undefined, currency?: string): string {
  const num = toNumber(value);
  if (num === null) return '—';
  const fixed = num.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

export function attributionQuantity(value: AdAttributionMoney | undefined): string {
  const num = toNumber(value);
  if (num === null) return '—';
  return num.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

/**
 * 派生比率展示：**不可比显示「不可比」，不是 0.00%**。
 *
 * 🔴 0 是「可比，但真的是零」；null 是「压根算不出来」（分母为 0）。
 */
export function attributionRatio(value: AdAttributionRatio | undefined, asPercent = true): string {
  const num = toNumber(value);
  if (num === null) return '不可比';
  return asPercent ? `${(num * 100).toFixed(2)}%` : num.toFixed(4);
}

export function attributionRatioTone(value: AdAttributionRatio | undefined): string {
  return toNumber(value) === null ? 'text-muted-foreground' : 'text-foreground';
}

/** 指标的口径定义（找不到就返回 undefined）。 */
export function metricDefinitionOf(
  definitions: AdAttributionMetricDefinition[],
  metric: string,
): AdAttributionMetricDefinition | undefined {
  return definitions.find((d) => d.metric === metric);
}

/** 表头文字：**指标名 + 口径定义**。 */
export function attributionMetricHeader(
  definition: AdAttributionMetricDefinition | undefined,
): string {
  if (!definition) return '';
  return `${definition.label}（${definition.formula}）`;
}

/** 未接入指标在表头 / 单元格里的文字。 */
export function unavailableMetricText(
  definition: AdAttributionMetricDefinition | undefined,
): string {
  if (!definition) return '未接入';
  return `未接入（${definition.ownerModule}：${definition.unavailableReason}）`;
}

/** 归因模型的中文说明。 */
export function attributionModelText(model: string): string {
  if (model === 'LAST_CLICK') return '末次点击';
  if (model === 'PLATFORM_WINDOW') return '平台归因窗口';
  return model || '—';
}

/**
 * 归因状态的中文说明与语气。
 *
 * 🔴 「归因不成立」必须和「归因成立」在视觉上分开 ——
 * 把两者渲染成同一个数字，运营会把没归上的钱当成已经归到的钱。
 */
export function attributionStatusText(status: string): string {
  if (status === 'ATTRIBUTED') return '归因成立';
  if (status === 'NO_ELIGIBLE_CLICK') return '窗口内无合格点击';
  if (status === 'NO_PLATFORM_ATTRIBUTED_CLICK') return '平台未标记归因点击';
  return status || '—';
}

export function attributionStatusTone(status: string): string {
  if (status === 'ATTRIBUTED') return 'text-emerald-600';
  return 'text-amber-600';
}

/**
 * 空态文案：**几种「空」必须分开说**。
 *
 * 🔴 「还没导入数据」与「还没跑归因」与「没有生效规则」与「被筛掉了」
 * 是四件不同的事，渲染成同一种空态就是把「没数据」说成「没有广告」。
 */
export function attributionEmptyText(reason: string | null, note: string): string {
  if (!reason) return '';
  if (reason === 'NO_IMPORTED_DATA') {
    return note || '还没有导入任何平台广告归因数据 —— 这不是「没有广告归因」，是还没导入数据。';
  }
  if (reason === 'NO_FACTS_FOR_RULE') {
    return note || '这套规则下还没有归因结果 —— 先跑一次归因计算。';
  }
  if (reason === 'NO_ACTIVE_RULE') {
    return note || '本租户当前没有生效的归因规则 —— 先启用一套口径，再跑一次归因计算。';
  }
  if (reason === 'FILTERED_EMPTY') {
    return note || '这个时间区间里确实有归因结果，但被当前的筛选条件全部排除了。放宽条件再试。';
  }
  if (reason === 'NO_CAMPAIGN_BINDING') {
    return note || '导入的归因数据里没有对应的广告活动 —— 归因落不到任何活动上。';
  }
  return note;
}

export function attributionTimestampText(value: string | null): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

// ==================================================================
// 表单校验
// ==================================================================

export interface AdAttributionRangeValidation {
  ok: boolean;
  errors: string[];
}

/**
 * 时间范围校验。
 *
 * 🔴 时间范围是**必填**的：没有区间就是全表扫。前端只做形状校验（缺不填、起止颠倒），
 * 区间跨度的上限由服务端判。
 */
export function validateAttributionRange(from: string, to: string): AdAttributionRangeValidation {
  const errors: string[] = [];
  if (!from.trim()) errors.push('请选择统计起始日');
  if (!to.trim()) errors.push('请选择统计截止日');
  if (from.trim() && to.trim() && from > to) errors.push('统计起始日不能晚于统计截止日');
  return { ok: errors.length === 0, errors };
}

/** 归因规则表单校验（与服务端同一套取值域）。 */
export function validateAttributionRule(input: {
  ruleCode: string;
  attributionModel: string;
  attributionWindowDays: number;
}): AdAttributionRangeValidation {
  const errors: string[] = [];
  if (!input.ruleCode.trim()) errors.push('规则编码不能为空');
  if (!['LAST_CLICK', 'PLATFORM_WINDOW'].includes(input.attributionModel)) {
    errors.push('归因模型只能是末次点击或平台归因窗口');
  }
  if (!Number.isInteger(input.attributionWindowDays)) {
    errors.push('归因窗口必须是整数天');
  } else if (input.attributionWindowDays < 0 || input.attributionWindowDays > 365) {
    errors.push('归因窗口必须在 0 ~ 365 天之间');
  }
  return { ok: errors.length === 0, errors };
}

/**
 * 解析导入载荷（运营粘贴平台归因报表）。
 *
 * 🔴 解析失败要**说清楚哪一行坏了**，不能默默返回空数组 ——
 * 「导入了 0 行」看起来像「平台没数据」。
 */
export function parseImportPayload(raw: string): {
  ok: boolean;
  errors: string[];
  request: AdAttributionImportRequest | null;
} {
  const errors: string[] = [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch (e) {
    return {
      ok: false,
      errors: [`导入内容不是合法 JSON：${e instanceof Error ? e.message : String(e)}`],
      request: null,
    };
  }
  if (typeof parsed !== 'object' || parsed === null) {
    return { ok: false, errors: ['导入内容必须是一个 JSON 对象'], request: null };
  }
  const body = parsed as Partial<AdAttributionImportRequest>;
  if (!Array.isArray(body.orders) || body.orders.length === 0) {
    errors.push('导入内容里没有 orders 列表（或它是空的）');
  }
  if (typeof body.dataAsOf !== 'string' || !body.dataAsOf.trim()) {
    errors.push('导入内容缺少 dataAsOf（这批数据的数据截至时刻）');
  }
  if (errors.length > 0) {
    return { ok: false, errors, request: null };
  }
  return {
    ok: true,
    errors: [],
    request: { dataAsOf: body.dataAsOf as string, orders: body.orders ?? [] },
  };
}
