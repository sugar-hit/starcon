import { api } from '../inventory/api';

/**
 * 5.4.2.1 平台价格同步前端模型。
 *
 * <p>蓝图加粗句是本模块唯一不可让步的一条：
 *
 * <blockquote>
 *
 * **平台可能拒绝或调整价格**（如低于最低价触发），差异必须显性反馈，不能假设回写即成功。
 *
 * </blockquote>
 *
 * <p>⇒ 这一条在页面上有**三个**落点，少一个都不算数：
 *
 * <ol>
 *   <li><b>每个价都标出来源</b>：期望价（我们要求平台收的）、平台承接价（规则判定出来的）、
 *       平台实际价（平台回执给的）—— 三者是三个不同的数，页面**不许**把它们混成一个「价格」列。
 *       见 {@link priceColumnText}。</li>
 *   <li><b>差额永远带符号、带偏离比例</b>：{@link diffHeadline} 给出
 *       「期望 120.0000 → 实际 118.0000（差 -2.0000，偏离 1.67%）」这样的整句，
 *       而不是让运营自己对着两列数字做减法。</li>
 *   <li><b>「还没结论」与「结论是 0」必须分得开</b>：平台还没回执时实际价是
 *       <b>null</b>，页面显示「待平台回执」—— <b>绝不渲染成 0</b>。
 *       渲染成 0 的后果是「平台把价压到 0」这种结论被凭空造出来，
 *       而它其实是「还不知道」。见 {@link priceText}。</li>
 * </ol>
 *
 * <p>🔴 页面把 <b>PENDING（已投递、等平台回执）</b> 与成功/失败分开显示：
 * PENDING 既不是成功也不是失败，把它算进成功就是那句加粗句的字面反面。
 */

/** 平台定价规则类型。与后端 {@code PlatformPriceRuleType} 逐字对应。 */
export type PlatformPriceRuleType = 'MIN_PRICE' | 'AUTO_COMPARE' | 'MULTI_SITE_LINK';

/** 规则状态。 */
export type PlatformPriceRuleStatus = 'ACTIVE' | 'DISABLED';

/** 回写的最终处置。与后端 {@code PlatformPriceSyncOutcome} 逐字对应。 */
export type PlatformPriceSyncOutcome = 'PENDING' | 'ACCEPTED' | 'ADJUSTED' | 'REJECTED' | 'FAILED';

/** 规则判定结果。与后端 {@code PlatformPriceRuleOutcome} 逐字对应。 */
export type PlatformPriceRuleOutcome =
  'NOT_EVALUATED' | 'NO_RULE' | 'ACCEPTED' | 'ADJUSTED' | 'REJECTED';

/** 差异结局。与后端 {@code PlatformPriceDiffOutcome} 逐字对应。 */
export type PlatformPriceDiffOutcome = 'MATCHED' | 'ADJUSTED' | 'REJECTED' | 'FAILED';

/** 差异来源。与后端 {@code PlatformPriceDiffSource} 逐字对应。 */
export type PlatformPriceDiffSource = 'RULE_EVALUATION' | 'PLATFORM_RECEIPT';

/** 一条平台定价规则。 */
export interface PlatformPriceRule {
  id: string;
  ruleType: PlatformPriceRuleType;
  platformCode: string;
  siteCode: string;
  skuId: string | null;
  status: PlatformPriceRuleStatus;
  priority: number;
  minPrice: number | null;
  currency: string | null;
  comparePlatformCode: string | null;
  compareSiteCode: string | null;
  maxGapRatio: number | null;
  referenceSiteCode: string | null;
  minRatio: number | null;
  maxRatio: number | null;
  note: string;
  createdAt: string;
  updatedAt: string;
}

/** 规则列表分页。 */
export interface PlatformPriceRulePage {
  items: PlatformPriceRule[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** 建 / 改规则的入参。 */
export interface PlatformPriceRuleInput {
  ruleType: PlatformPriceRuleType;
  platformCode: string;
  siteCode: string;
  skuId: string | null;
  status: PlatformPriceRuleStatus | null;
  priority: number | null;
  minPrice: number | null;
  currency: string | null;
  comparePlatformCode: string | null;
  compareSiteCode: string | null;
  maxGapRatio: number | null;
  referenceSiteCode: string | null;
  minRatio: number | null;
  maxRatio: number | null;
  note: string;
}

/** 一条回写结果。 */
export interface PlatformPriceSyncRecord {
  id: string;
  writebackId: string;
  orderId: string;
  orderItemId: string;
  skuId: string;
  skuCode: string;
  platformCode: string;
  siteCode: string;
  currency: string;
  expectedPrice: number;
  predictedPrice: number | null;
  actualPrice: number | null;
  priceDelta: number | null;
  deviationRatio: number | null;
  outcome: PlatformPriceSyncOutcome;
  ruleOutcome: PlatformPriceRuleOutcome;
  ruleId: string | null;
  ruleReason: string;
  platformOperationId: string | null;
  platformRequestId: string;
  idempotencyKey: string;
  lastError: string;
  dispatchedAt: string | null;
  completedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/** 回写结果详情 = 记录 + 它产生的全部差异记录。 */
export interface PlatformPriceSyncRecordDetail {
  record: PlatformPriceSyncRecord;
  diffs: PlatformPriceDiff[];
}

/** 回写结果分页。 */
export interface PlatformPriceSyncRecordPage {
  items: PlatformPriceSyncRecord[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** 一条差异记录。 */
export interface PlatformPriceDiff {
  id: string;
  syncId: string;
  writebackId: string;
  skuId: string;
  platformCode: string;
  siteCode: string;
  currency: string;
  expectedPrice: number;
  actualPrice: number | null;
  priceDelta: number | null;
  deviationRatio: number | null;
  outcome: PlatformPriceDiffOutcome;
  source: PlatformPriceDiffSource;
  reasonCode: string;
  reasonMessage: string;
  createdAt: string;
}

/** 差异台账分页。 */
export interface PlatformPriceDiffPage {
  items: PlatformPriceDiff[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** 能力声明。 */
export interface PlatformPriceSyncCapabilities {
  module: string;
  ruleTypes: string[];
  ruleTypeSemantics: string[];
  outcomes: string[];
  diffSources: string[];
  upstreamOwner: string;
  upstreamResource: string;
  upstreamWriteOwner: string;
  queueService: string;
  queueResource: string;
  queueOperation: string;
  comparisonPriceSource: string;
  notes: string[];
}

/** 一次同步的请求。 */
export interface PlatformPriceSyncRequest {
  platformCode: string | null;
  siteCode: string | null;
  skuId: string | null;
  limit: number | null;
}

/** 一次同步的结果汇总。 */
export interface PlatformPriceSyncRun {
  scanned: number;
  created: number;
  dispatched: number;
  rejected: number;
  skipped: number;
  failed: number;
  items: PlatformPriceSyncRecord[];
}

/** 平台回执。 */
export interface PlatformPriceReceiptInput {
  outcome: PlatformPriceSyncOutcome;
  actualPrice: number | null;
  platformRequestId: string | null;
  reason: string | null;
}

/** 回写结果的筛选条件。 */
export interface PlatformPriceRecordFilters {
  outcome: string;
  platformCode: string;
  siteCode: string;
  skuId: string;
}

/** 差异台账的筛选条件。 */
export interface PlatformPriceDiffFilters {
  outcome: string;
  platformCode: string;
  siteCode: string;
  skuId: string;
}

/** 空筛选条件。 */
export const EMPTY_RECORD_FILTERS: PlatformPriceRecordFilters = {
  outcome: '',
  platformCode: '',
  siteCode: '',
  skuId: '',
};

/** 空差异筛选条件。 */
export const EMPTY_DIFF_FILTERS: PlatformPriceDiffFilters = {
  outcome: '',
  platformCode: '',
  siteCode: '',
  skuId: '',
};

// ================================================================ 取数

/** 能力声明。 */
export async function fetchCapabilities(): Promise<PlatformPriceSyncCapabilities> {
  return api<PlatformPriceSyncCapabilities>('catalog/price-sync/capabilities');
}

/** 规则列表（真分页 + 真筛选）。 */
export async function fetchRules(
  page: number,
  size: number,
  filters: { ruleType: string; platformCode: string; siteCode: string; status: string },
): Promise<PlatformPriceRulePage> {
  const query = new URLSearchParams();
  query.set('page', String(page));
  query.set('size', String(size));
  if (filters.ruleType) query.set('ruleType', filters.ruleType);
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.siteCode) query.set('siteCode', filters.siteCode);
  if (filters.status) query.set('status', filters.status);
  return api<PlatformPriceRulePage>(`catalog/price-sync/rules?${query.toString()}`);
}

/** 规则详情。 */
export async function fetchRule(id: string): Promise<PlatformPriceRule> {
  return api<PlatformPriceRule>(`catalog/price-sync/rules/${id}`);
}

/** 建规则。 */
export async function createRule(input: PlatformPriceRuleInput): Promise<PlatformPriceRule> {
  return api<PlatformPriceRule>('catalog/price-sync/rules', 'POST', input);
}

/** 改规则（含停用）。 */
export async function updateRule(
  id: string,
  input: PlatformPriceRuleInput,
): Promise<PlatformPriceRule> {
  return api<PlatformPriceRule>(`catalog/price-sync/rules/${id}`, 'PATCH', input);
}

/** 回写结果列表。 */
export async function fetchRecords(
  page: number,
  size: number,
  filters: PlatformPriceRecordFilters,
): Promise<PlatformPriceSyncRecordPage> {
  const query = new URLSearchParams();
  query.set('page', String(page));
  query.set('size', String(size));
  if (filters.outcome) query.set('outcome', filters.outcome);
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.siteCode) query.set('siteCode', filters.siteCode);
  if (filters.skuId) query.set('skuId', filters.skuId);
  return api<PlatformPriceSyncRecordPage>(`catalog/price-sync/records?${query.toString()}`);
}

/** 回写结果详情（含差异记录）。 */
export async function fetchRecord(id: string): Promise<PlatformPriceSyncRecordDetail> {
  return api<PlatformPriceSyncRecordDetail>(`catalog/price-sync/records/${id}`);
}

/** 差异台账。 */
export async function fetchDiffs(
  page: number,
  size: number,
  filters: PlatformPriceDiffFilters,
): Promise<PlatformPriceDiffPage> {
  const query = new URLSearchParams();
  query.set('page', String(page));
  query.set('size', String(size));
  if (filters.outcome) query.set('outcome', filters.outcome);
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.siteCode) query.set('siteCode', filters.siteCode);
  if (filters.skuId) query.set('skuId', filters.skuId);
  return api<PlatformPriceDiffPage>(`catalog/price-sync/diffs?${query.toString()}`);
}

/** 执行一次同步：消费上游待回写事实并投递到平台。 */
export async function runSync(request: PlatformPriceSyncRequest): Promise<PlatformPriceSyncRun> {
  return api<PlatformPriceSyncRun>('catalog/price-sync/sync', 'POST', request);
}

/** 提交平台回执。 */
export async function submitReceipt(
  id: string,
  input: PlatformPriceReceiptInput,
): Promise<PlatformPriceSyncRecordDetail> {
  return api<PlatformPriceSyncRecordDetail>(
    `catalog/price-sync/records/${id}/receipt`,
    'POST',
    input,
  );
}

// ================================================================ 展示口径

/**
 * 🔴 一个价在页面上的写法。
 *
 * <p>{@code null} 表示「还没有这个价」（平台还没回执 / 还没判定），
 * **不是 0**。所以这里渲染成「待平台回执」，而不是 0.00 ——
 * 0.00 会让页面显示成「平台把价压到了 0」，那是一个凭空造出来的结论。
 */
export function priceText(
  value: number | null | undefined,
  unavailable = '待平台回执',
  digits = 4,
): string {
  if (value === null || value === undefined) return unavailable;
  return value.toFixed(digits);
}

/** 带币种的价。 */
export function priceWithCurrency(
  value: number | null | undefined,
  currency: string | null | undefined,
  unavailable = '待平台回执',
): string {
  const text = priceText(value, unavailable);
  if (text === unavailable) return text;
  return `${text} ${currency ?? ''}`.trim();
}

/** 差额（带符号）。null ⇒ 「—」。 */
export function deltaText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  if (value > 0) return `+${value.toFixed(4)}`;
  return value.toFixed(4);
}

/** 偏离比例（百分比）。null ⇒ 「—」。 */
export function deviationText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return `${(value * 100).toFixed(2)}%`;
}

/** 回写结局的中文标签。 */
export function outcomeLabel(outcome: string): string {
  switch (outcome) {
    case 'PENDING':
      return '已投递 · 待平台回执';
    case 'ACCEPTED':
      return '平台按期望价收下';
    case 'ADJUSTED':
      return '平台改了价';
    case 'REJECTED':
      return '平台拒收';
    case 'FAILED':
      return '本次没发出去';
    default:
      return outcome;
  }
}

/** 回写结局的徽标色调。 */
export function outcomeTone(
  outcome: string,
): 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'brand' {
  switch (outcome) {
    case 'ACCEPTED':
      return 'success';
    case 'ADJUSTED':
      return 'warning';
    case 'REJECTED':
      return 'danger';
    case 'FAILED':
      return 'danger';
    case 'PENDING':
      return 'info';
    default:
      return 'neutral';
  }
}

/** 规则判定结论的中文标签。 */
export function ruleOutcomeLabel(outcome: string): string {
  switch (outcome) {
    case 'NOT_EVALUATED':
      return '尚未判定';
    case 'NO_RULE':
      return '没有适用规则';
    case 'ACCEPTED':
      return '规则判定通过';
    case 'ADJUSTED':
      return '规则判定平台会改价';
    case 'REJECTED':
      return '规则判定平台会拒收';
    default:
      return outcome;
  }
}

/** 规则类型的中文标签。 */
export function ruleTypeLabel(ruleType: string): string {
  switch (ruleType) {
    case 'MIN_PRICE':
      return '最低价';
    case 'AUTO_COMPARE':
      return '自动比价';
    case 'MULTI_SITE_LINK':
      return '多站点联动';
    default:
      return ruleType;
  }
}

/** 规则类型的一句话说明（页面上的 tooltip / 说明文字）。 */
export function ruleTypeHint(ruleType: string): string {
  switch (ruleType) {
    case 'MIN_PRICE':
      return '期望价低于平台最低价 ⇒ 平台拒收（本次不回写，直接显性回报原因）';
    case 'AUTO_COMPARE':
      return '期望价高于对比平台价允许的价差上限 ⇒ 平台压到对比价（差异显性记录）';
    case 'MULTI_SITE_LINK':
      return '期望价越出参照站点价格的联动比例带 ⇒ 平台拉到带边界（差异显性记录）';
    default:
      return '';
  }
}

/** 规则作用域的中文描述。 */
export function ruleScopeText(rule: {
  platformCode: string;
  siteCode: string;
  skuId: string | null;
}): string {
  const site = rule.siteCode ? rule.siteCode : '全部站点';
  const sku = rule.skuId ? '指定 SKU' : '全部 SKU';
  return `${rule.platformCode} / ${site} / ${sku}`;
}

/** 规则参数的一句话摘要（按类型取不同的列）。 */
export function ruleSummaryText(rule: PlatformPriceRule): string {
  switch (rule.ruleType) {
    case 'MIN_PRICE':
      return `最低价 ${priceText(rule.minPrice)} ${rule.currency ?? ''}`.trim();
    case 'AUTO_COMPARE':
      return (
        `对比 ${rule.comparePlatformCode ?? '—'} / ${rule.compareSiteCode || '全部站点'}` +
        `，允许价差上限 ${deviationText(rule.maxGapRatio)}`
      );
    case 'MULTI_SITE_LINK':
      return (
        `参照站点 ${rule.referenceSiteCode ?? '—'}` +
        `，价格带 ${deviationText(rule.minRatio)} ~ ${deviationText(rule.maxRatio)}`
      );
    default:
      return '';
  }
}

/**
 * 差异的一句话摘要。
 *
 * <p>🔴 这是本页最要紧的一行字：期望价、实际价、差额、偏离比例一次说完，
 * 运营不用对着两列数字做减法。
 */
export function diffHeadline(diff: PlatformPriceDiff): string {
  const expected = priceText(diff.expectedPrice);
  if (diff.actualPrice === null || diff.actualPrice === undefined) {
    return `期望 ${expected} ${diff.currency} → 平台没有给出实际价（${diff.reasonCode || '无原因码'}）`;
  }
  const actual = priceText(diff.actualPrice);
  const delta = deltaText(diff.priceDelta);
  const deviation = deviationText(diff.deviationRatio);
  return `期望 ${expected} ${diff.currency} → 实际 ${actual}（差 ${delta}，偏离 ${deviation}）`;
}

/** 回写记录的一句话摘要（期望价 → 实际价）。 */
export function recordHeadline(record: PlatformPriceSyncRecord): string {
  const expected = priceText(record.expectedPrice);
  if (record.actualPrice === null || record.actualPrice === undefined) {
    if (record.outcome === 'PENDING') return `期望 ${expected} ${record.currency} → 待平台回执`;
    if (record.outcome === 'REJECTED') return `期望 ${expected} ${record.currency} → 平台拒收`;
    if (record.outcome === 'FAILED') return `期望 ${expected} ${record.currency} → 本次没发出去`;
    return `期望 ${expected} ${record.currency}`;
  }
  return (
    `期望 ${expected} ${record.currency} → 实际 ${priceText(record.actualPrice)}` +
    `（差 ${deltaText(record.priceDelta)}，偏离 ${deviationText(record.deviationRatio)}）`
  );
}

/**
 * 一列「价」在页面上的写法。
 *
 * <p>🔴 三个价分三列，**不许合成一列**：期望价 / 平台承接价 / 平台实际价是三个不同的数，
 * 合成一列就看不出「平台到底改了多少」。
 */
export function priceColumnText(
  expected: number | null | undefined,
  predicted: number | null | undefined,
  actual: number | null | undefined,
): { expected: string; predicted: string; actual: string } {
  return {
    expected: priceText(expected, '—'),
    predicted: priceText(predicted, '本次判定未给出承接价'),
    actual: priceText(actual, '待平台回执'),
  };
}

/** 一次同步的结果摘要（一句话）。 */
export function runSummaryText(run: PlatformPriceSyncRun): string {
  return (
    `扫描 ${run.scanned} 条待回写事实：已投递 ${run.dispatched} 条、` +
    `被平台规则拒收 ${run.rejected} 条、没发出去 ${run.failed} 条、` +
    `已处理过跳过 ${run.skipped} 条。`
  );
}

/**
 * 客户端参数校验（与后端同一套口径）。
 *
 * <p>🔴 缺参数**直接提示**，不给「先存下来以后再补」的口子 ——
 * 一条没配最低价的最低价规则在库里静默存在、判定时又拿不出结果，
 * 那是最难排查的一种数据质量问题。
 */
export function validateRuleInput(input: PlatformPriceRuleInput): string[] {
  const problems: string[] = [];
  if (!input.platformCode.trim()) problems.push('必须填平台码');
  if (!input.ruleType) problems.push('必须选规则类型');
  if (input.ruleType === 'MIN_PRICE') {
    if (input.minPrice === null || input.minPrice <= 0) problems.push('最低价必须大于 0');
    if (!input.currency || !/^[A-Z]{3}$/.test(input.currency.trim()))
      problems.push('最低价规则必须填三位大写币种代码');
  }
  if (input.ruleType === 'AUTO_COMPARE') {
    if (!input.comparePlatformCode?.trim()) problems.push('自动比价必须填对比平台码');
    if (input.maxGapRatio === null || input.maxGapRatio < 0)
      problems.push('自动比价必须填一个不小于 0 的最大价差比例');
  }
  if (input.ruleType === 'MULTI_SITE_LINK') {
    if (!input.referenceSiteCode?.trim()) problems.push('多站点联动必须填参照站点码');
    if (input.minRatio === null || input.minRatio <= 0)
      problems.push('多站点联动必须填一个大于 0 的联动下限比例');
    if (input.maxRatio === null || input.maxRatio < 0)
      problems.push('多站点联动必须填一个不小于 0 的联动上限比例');
    if (input.minRatio !== null && input.maxRatio !== null && input.maxRatio < input.minRatio)
      problems.push('联动上限比例不能小于下限比例');
  }
  return problems;
}

/** 空规则表单。 */
export function emptyRuleInput(): PlatformPriceRuleInput {
  return {
    ruleType: 'MIN_PRICE',
    platformCode: '',
    siteCode: '',
    skuId: null,
    status: 'ACTIVE',
    priority: 100,
    minPrice: null,
    currency: 'USD',
    comparePlatformCode: null,
    compareSiteCode: null,
    maxGapRatio: null,
    referenceSiteCode: null,
    minRatio: null,
    maxRatio: null,
    note: '',
  };
}

/** 规则表单的数字字段解析（空串 ⇒ null，而不是 0）。 */
export function numberOrNull(text: string): number | null {
  const trimmed = text.trim();
  if (!trimmed) return null;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : null;
}
