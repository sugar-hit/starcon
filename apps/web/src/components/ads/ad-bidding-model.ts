import { api } from '../inventory/api';

/**
 * 14.2.1.1 竞价管理 前端模型。
 *
 * <p>蓝图：「管理竞价：动态竞价策略选择（只增只降/降低/提高）、手动竞价调整、竞价范围上限、
 * 按商品/SKU 的目标 ACOS 反推竞价建议。输入：竞价设置；目标 ACOS/ROAS；历史表现。
 * 输出：竞价建议（目标 ACOS → 建议竞价）；批量调整任务。关系：上游 = 14.1.3.1、14.3.2.1；
 * 下游 = 平台广告 API。
 * <strong>目标 ACOS 反推竞价是广告与利润打通的接口</strong>——没有利润数据，投放只能看平台给的建议，
 * 无法判断是否真的赚钱。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **动态竞价策略只有三档**（只增只降 / 只降低 / 只提高）。界面上不许出现自由输入框 ——
 *    写进去的字符串平台侧不认，投放会静默按平台默认值跑。
 * 2. **「不可比」≠「零」**。比率类字段为 `null` 表示**不可比**（分母为 0），
 *    `0` 表示**可比但真的是零** —— 显示成「0%」会把「判不了」说成「真的是零」。
 * 3. **两道闸要分开显示**：竞价范围上限（允许区间）与单次调整额度（额度）不是一回事。
 *    被闸拦下的条目要**看得见**，并写清被哪一道闸拦下 —— 静默丢弃会让人以为「按预期工作」。
 * 4. **「待下发」≠「已生效」**。平台广告 API 未交付，本地竞价已改但平台侧还没改。
 * 5. **撤销 ≠ 回滚**。撤销只是停止下发，本地竞价**不会**被改回去。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * 建议竞价、倍率、盈亏平衡 ACOS 全部是服务端 `BigDecimal` 的事。
 */

/** 金额与数量一律 number（JSON number），只原样展示。 */
export type AdMoney = number | null;

/** 🔴 null = 不可比（分母为 0 或没有数据），与 `0`（可比但真的是零）含义相反。 */
export type AdRatio = number | null;

export interface AdBiddingCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface AdBiddingCapabilities {
  dimensions: AdBiddingCapabilityDimension[];
  statuses: string[];
  scopes: string[];
  dynamicStrategies: string[];
  runModes: string[];
  dispatchStatuses: string[];
  guardrailCodes: string[];
  strategyNote: string;
  guardrailNote: string;
  ratioNote: string;
  profitNote: string;
  upstreamNote: string;
  dispatchNote: string;
  boundaryNote: string;
}

export interface AdBiddingOption {
  value: string;
  label: string;
  note: string;
}

export interface AdBiddingUpstreamGuardrails {
  maxBidDeltaRatio: number;
  maxBidAbsolute: number;
  maxTotalBidIncrease: number;
}

export interface AdBiddingOptions {
  platforms: AdBiddingOption[];
  shops: AdBiddingOption[];
  dynamicStrategies: AdBiddingOption[];
  statuses: AdBiddingOption[];
  upstreamGuardrails: AdBiddingUpstreamGuardrails;
  caliberNote: string;
}

export interface AdBiddingPolicyView {
  id: string;
  policyCode: string;
  policyName: string;
  platformId: string;
  platformCode: string;
  platformName: string;
  shopCode: string;
  shopName: string;
  shopId: string;
  dynamicBiddingStrategy: string;
  currency: string;
  scope: string;
  targetCampaignId: string;
  targetCampaignName: string;
  status: string;
  minBid: number | null;
  maxBid: number | null;
  singleAdjustCapRatio: number | null;
  singleAdjustCapAmount: number | null;
  targetAcos: number | null;
  targetRoas: number | null;
  dryRun: boolean;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AdBiddingPolicySummary {
  id: string;
  policyCode: string;
  policyName: string;
  platformCode: string;
  shopCode: string;
  shopName: string;
  dynamicBiddingStrategy: string;
  currency: string;
  scope: string;
  status: string;
  minBid: number | null;
  maxBid: number | null;
  targetAcos: number | null;
  targetRoas: number | null;
  dryRun: boolean;
  updatedAt: string;
}

export interface AdBiddingPolicyPage {
  items: AdBiddingPolicySummary[];
  total: number;
  page: number;
  size: number;
}

export interface AdBiddingKeywordRow {
  keywordId: string;
  keyword: string;
  shopCode: string;
  platformCode: string;
  status: string;
  bid: number | null;
  bidCurrency: string;
  metricCurrency: string;
  windowSpend: number | null;
  windowSales: number | null;
  windowDays: number;
  externalCampaignId: string;
  externalKeywordId: string;
  skuId: string;
  skuCode: string;
  spuCode: string;
  skuMapped: boolean;
  skuNote: string;
  lastSyncedAt: string | null;
}

export interface AdBiddingKeywordPage {
  items: AdBiddingKeywordRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdBiddingSuggestionSummary {
  id: string;
  policyId: string;
  policyName: string;
  shopCode: string;
  shopName: string;
  windowFrom: string;
  windowTo: string;
  targetAcos: number | null;
  targetRoas: number | null;
  attributionRuleCode: string;
  attributionRuleVersion: number | null;
  attributionModel: string;
  attributionWindowDays: number | null;
  profitAvailable: boolean;
  profitUnavailableReason: string;
  dataAsOf: string;
  status: string;
  skuCount: number;
  suggestedCount: number;
  rejectedCount: number;
  note: string;
  createdAt: string;
}

export interface AdBiddingSuggestionSkuView {
  id: string;
  skuId: string;
  skuCode: string;
  spend: number | null;
  attributedSales: number | null;
  currency: string;
  currentAcos: number | null;
  currentRoas: number | null;
  breakEvenAcos: number | null;
  profitStatus: string;
  bidRatio: number | null;
  status: string;
  guardrailCode: string;
  reason: string;
  items: AdBiddingSuggestionItemView[];
}

export interface AdBiddingSuggestionItemView {
  id: string;
  keywordId: string;
  keywordText: string;
  currentBid: number | null;
  bidCurrency: string;
  suggestedBid: number | null;
  bidDelta: number | null;
  status: string;
  guardrailCode: string;
  reason: string;
}

export interface AdBiddingSuggestionView extends AdBiddingSuggestionSummary {
  basis: Record<string, unknown>;
  skus: AdBiddingSuggestionSkuView[];
}

export interface AdBiddingSuggestionPage {
  items: AdBiddingSuggestionSummary[];
  total: number;
  page: number;
  size: number;
}

export interface AdBiddingAdjustTarget {
  keywordId: string;
  bidValue: number | null;
}

export interface AdBiddingAdjustmentItemView {
  id: string;
  keywordId: string;
  targetRef: string;
  beforeBid: number | null;
  afterBid: number | null;
  requestedBid: number | null;
  status: string;
  guardrailCode: string;
  reason: string;
}

export interface AdBiddingAdjustmentSummary {
  id: string;
  policyId: string;
  policyName: string;
  shopId: string;
  shopCode: string;
  actionType: string;
  runMode: string;
  status: string;
  currency: string;
  bidMode: string;
  requestedCount: number;
  appliedCount: number;
  failedCount: number;
  bulkActionId: string;
  guardrail: Record<string, unknown>;
  idempotencyKey: string;
  reason: string;
  note: string;
  createdAt: string;
}

export interface AdBiddingAdjustmentView extends AdBiddingAdjustmentSummary {
  items: AdBiddingAdjustmentItemView[];
}

export interface AdBiddingAdjustmentPage {
  items: AdBiddingAdjustmentSummary[];
  total: number;
  page: number;
  size: number;
}

export interface AdBiddingDispatchView {
  id: string;
  policyId: string;
  adjustmentId: string;
  adjustmentItemId: string;
  platformId: string;
  platformCode: string;
  shopCode: string;
  externalCampaignId: string;
  externalKeywordId: string;
  keywordText: string;
  beforeBid: number | null;
  afterBid: number | null;
  actionType: string;
  actionPayload: Record<string, unknown>;
  dispatchStatus: string;
  dispatchedAt: string | null;
  dispatchRef: string;
  cancelledAt: string | null;
  cancelReason: string;
  note: string;
  createdAt: string;
}

export interface AdBiddingDispatchPage {
  items: AdBiddingDispatchView[];
  total: number;
  page: number;
  size: number;
}

// ================================================================ 取数

function queryString(query: object): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : '';
}

export function fetchAdBiddingCapabilities(): Promise<AdBiddingCapabilities> {
  return api<AdBiddingCapabilities>('channel/ad-bidding/capabilities');
}

export function fetchAdBiddingOptions(): Promise<AdBiddingOptions> {
  return api<AdBiddingOptions>('channel/ad-bidding/options');
}

export function fetchAdBiddingPolicies(
  query: {
    shopCode?: string | undefined;
    status?: string | undefined;
    keyword?: string | undefined;
    page?: number | undefined;
    size?: number | undefined;
  } = {},
): Promise<AdBiddingPolicyPage> {
  return api<AdBiddingPolicyPage>(`channel/ad-bidding/policies${queryString(query)}`);
}

export function fetchAdBiddingPolicy(id: string): Promise<AdBiddingPolicyView> {
  return api<AdBiddingPolicyView>(`channel/ad-bidding/policies/${id}`);
}

export function createAdBiddingPolicy(input: {
  policyCode: string;
  policyName: string;
  platformId: string;
  shopCode: string;
  dynamicBiddingStrategy: string;
  currency: string;
  scope: string;
  targetCampaignId: string;
  minBid: number | null;
  maxBid: number | null;
  singleAdjustCapRatio: number | null;
  singleAdjustCapAmount: number | null;
  targetAcos: number | null;
  targetRoas: number | null;
  dryRun: boolean;
  note: string;
}): Promise<AdBiddingPolicyView> {
  return api<AdBiddingPolicyView>('channel/ad-bidding/policies', 'POST', input);
}

export function updateAdBiddingPolicy(
  id: string,
  input: {
    policyCode: string;
    policyName: string;
    platformId: string;
    shopCode: string;
    dynamicBiddingStrategy: string;
    currency: string;
    scope: string;
    targetCampaignId: string;
    minBid: number | null;
    maxBid: number | null;
    singleAdjustCapRatio: number | null;
    singleAdjustCapAmount: number | null;
    targetAcos: number | null;
    targetRoas: number | null;
    dryRun: boolean;
    note: string;
  },
): Promise<AdBiddingPolicyView> {
  return api<AdBiddingPolicyView>(`channel/ad-bidding/policies/${id}`, 'PUT', input);
}

export function changeAdBiddingStatus(
  id: string,
  status: string,
  note = '',
): Promise<AdBiddingPolicyView> {
  return api<AdBiddingPolicyView>(`channel/ad-bidding/policies/${id}/status`, 'POST', {
    status,
    note,
  });
}

export function fetchAdBiddingKeywords(
  query: {
    shopCode?: string | undefined;
    keyword?: string | undefined;
    status?: string | undefined;
    from?: string | undefined;
    to?: string | undefined;
    page?: number | undefined;
    size?: number | undefined;
  } = {},
): Promise<AdBiddingKeywordPage> {
  return api<AdBiddingKeywordPage>(`channel/ad-bidding/keywords${queryString(query)}`);
}

export function generateAdBiddingSuggestion(input: {
  policyId: string;
  from: string;
  to: string;
  idempotencyKey: string;
  note: string;
}): Promise<AdBiddingSuggestionView> {
  return api<AdBiddingSuggestionView>('channel/ad-bidding/suggestions', 'POST', input);
}

export function fetchAdBiddingSuggestions(
  query: {
    policyId?: string | undefined;
    page?: number | undefined;
    size?: number | undefined;
  } = {},
): Promise<AdBiddingSuggestionPage> {
  return api<AdBiddingSuggestionPage>(`channel/ad-bidding/suggestions${queryString(query)}`);
}

export function fetchAdBiddingSuggestion(id: string): Promise<AdBiddingSuggestionView> {
  return api<AdBiddingSuggestionView>(`channel/ad-bidding/suggestions/${id}`);
}

export function createAdBiddingAdjustment(input: {
  policyId: string;
  suggestionId: string;
  currency: string;
  dryRun: boolean;
  targets: AdBiddingAdjustTarget[];
  idempotencyKey: string;
  note: string;
}): Promise<AdBiddingAdjustmentView> {
  return api<AdBiddingAdjustmentView>('channel/ad-bidding/adjustments', 'POST', input);
}

export function fetchAdBiddingAdjustments(
  query: {
    policyId?: string | undefined;
    status?: string | undefined;
    page?: number | undefined;
    size?: number | undefined;
  } = {},
): Promise<AdBiddingAdjustmentPage> {
  return api<AdBiddingAdjustmentPage>(`channel/ad-bidding/adjustments${queryString(query)}`);
}

export function fetchAdBiddingAdjustment(id: string): Promise<AdBiddingAdjustmentView> {
  return api<AdBiddingAdjustmentView>(`channel/ad-bidding/adjustments/${id}`);
}

export function fetchAdBiddingDispatches(
  query: {
    status?: string | undefined;
    shopCode?: string | undefined;
    page?: number | undefined;
    size?: number | undefined;
  } = {},
): Promise<AdBiddingDispatchPage> {
  return api<AdBiddingDispatchPage>(`channel/ad-bidding/dispatch${queryString(query)}`);
}

export function cancelAdBiddingDispatch(
  id: string,
  reason: string,
): Promise<AdBiddingDispatchView> {
  return api<AdBiddingDispatchView>(`channel/ad-bidding/dispatch/${id}/cancel`, 'POST', { reason });
}

// ================================================================ 展示

/** 🔴 动态竞价策略只有三档 —— 这里给中文名，**不允许**自由文本。 */
export function dynamicStrategyLabel(strategy: string): string {
  switch (strategy) {
    case 'UP_AND_DOWN':
      return '只增只降';
    case 'DOWN_ONLY':
      return '只降低';
    case 'UP_ONLY':
      return '只提高';
    default:
      return strategy;
  }
}

export function dynamicStrategyTone(strategy: string): 'good' | 'warn' | 'bad' | 'muted' {
  switch (strategy) {
    case 'UP_AND_DOWN':
      return 'warn';
    case 'DOWN_ONLY':
      return 'good';
    case 'UP_ONLY':
      return 'muted';
    default:
      return 'muted';
  }
}

export function policyStatusLabel(status: string): string {
  switch (status) {
    case 'DRAFT':
      return '草稿';
    case 'ACTIVE':
      return '生效中';
    case 'PAUSED':
      return '暂停';
    case 'ARCHIVED':
      return '已归档';
    default:
      return status;
  }
}

export function policyStatusTone(status: string): 'good' | 'warn' | 'bad' | 'muted' {
  switch (status) {
    case 'ACTIVE':
      return 'good';
    case 'DRAFT':
      return 'warn';
    case 'PAUSED':
      return 'muted';
    case 'ARCHIVED':
      return 'bad';
    default:
      return 'muted';
  }
}

export function scopeLabel(scope: string): string {
  return scope === 'CAMPAIGN' ? '活动级' : '店铺级';
}

/** 🔴 闸门码：被哪一道闸拦下的。 */
export function guardrailLabel(code: string): string {
  switch (code) {
    case 'RANGE_MIN':
      return '闸①竞价范围下限';
    case 'RANGE_MAX':
      return '闸①竞价范围上限';
    case 'ADJUST_RATIO':
      return '闸②单次调整比例额度';
    case 'ADJUST_AMOUNT':
      return '闸②单次调整金额额度';
    case 'UPSTREAM_RATIO':
      return '闸②上游单次比例上限';
    case 'UPSTREAM_ABSOLUTE':
      return '闸②上游单条绝对上限';
    case 'UPSTREAM_TOTAL':
      return '闸②上游单次总上调上限';
    case 'UPSTREAM_REJECTED':
      return '上游写路径拒绝';
    case 'CURRENCY':
      return '币种不一致';
    case 'NO_BASIS':
      return '算不出来';
    case 'ABOVE_BREAK_EVEN':
      return '高于盈亏平衡';
    default:
      return code || '—';
  }
}

export function adjustmentStatusLabel(status: string): string {
  switch (status) {
    case 'APPLIED':
      return '本地已改';
    case 'REJECTED':
      return '被拒';
    case 'SKIPPED':
      return '已跳过';
    default:
      return status;
  }
}

export function adjustmentStatusTone(status: string): 'good' | 'warn' | 'bad' | 'muted' {
  switch (status) {
    case 'APPLIED':
      return 'good';
    case 'REJECTED':
      return 'bad';
    case 'SKIPPED':
      return 'muted';
    default:
      return 'muted';
  }
}

export function runModeLabel(mode: string): string {
  return mode === 'DRY_RUN' ? '试运行（只算不调）' : '真调（走已交付写路径）';
}

export function dispatchStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '待下发';
    case 'DISPATCHED':
      return '已下发';
    case 'FAILED':
      return '下发失败';
    case 'CANCELLED':
      return '已撤销';
    default:
      return status;
  }
}

export function dispatchStatusTone(status: string): 'good' | 'warn' | 'bad' | 'muted' {
  switch (status) {
    case 'PENDING':
      return 'warn';
    case 'DISPATCHED':
      return 'good';
    case 'FAILED':
      return 'bad';
    case 'CANCELLED':
      return 'muted';
    default:
      return 'muted';
  }
}

export function dispatchActionLabel(action: string): string {
  return action === 'SET_BIDDING_STRATEGY' ? '改动态竞价策略' : '改竞价';
}

export function profitStatusLabel(status: string): string {
  switch (status) {
    case 'AVAILABLE':
      return '利润可算';
    case 'NOT_COMPARABLE':
      return '利润不可比（成本缺项）';
    case 'NO_DATA':
      return '利润侧没有这个 SKU 的事实';
    default:
      return status;
  }
}

export function suggestionStatusLabel(status: string): string {
  switch (status) {
    case 'READY':
      return '全部算出来了';
    case 'PARTIAL':
      return '部分算不出来';
    case 'EMPTY':
      return '窗口内一个 SKU 都没有';
    default:
      return status;
  }
}

/** 🔴 不可比显示成「—（不可比）」，**不显示 0**。 */
export function ratioText(value: AdRatio): string {
  if (value === null || value === undefined) return '—（不可比）';
  return `${(value * 100).toFixed(2)}%`;
}

/** 🔴 比例型数字（倍率）：null 才是「算不出来」。 */
export function multiplierText(value: AdRatio): string {
  if (value === null || value === undefined) return '—（算不出来）';
  return value.toFixed(4);
}

export function money(value: AdMoney): string {
  if (value === null || value === undefined) return '—';
  return value.toFixed(4);
}

export function timestampText(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

export function dateText(value: string | null | undefined): string {
  if (!value) return '—';
  return value;
}

/**
 * 🔴 新建策略的前置校验（**只做形状校验，不做业务算术**）。
 *
 * @returns 错误清单（空数组 = 通过）
 */
export function validateBiddingPolicy(input: {
  policyCode: string;
  policyName: string;
  shopCode: string;
  dynamicBiddingStrategy: string;
  currency: string;
  scope: string;
  targetCampaignId: string;
  minBid: string;
  maxBid: string;
  singleAdjustCapRatio: string;
  singleAdjustCapAmount: string;
  targetAcos: string;
  targetRoas: string;
}): string[] {
  const errors: string[] = [];
  if (!input.policyCode.trim()) errors.push('策略编码不能为空');
  if (!input.policyName.trim()) errors.push('策略名不能为空');
  if (!input.shopCode.trim()) errors.push('必须选一个店铺');
  if (!['UP_AND_DOWN', 'DOWN_ONLY', 'UP_ONLY'].includes(input.dynamicBiddingStrategy)) {
    errors.push('动态竞价策略只能是三档之一（只增只降 / 只降低 / 只提高）');
  }
  if (!/^[A-Z]{3}$/.test(input.currency.trim().toUpperCase())) {
    errors.push('币种必须是三位大写字母');
  }
  if (input.scope === 'CAMPAIGN' && !input.targetCampaignId.trim()) {
    errors.push('作用范围是活动级时必须给出目标活动 id');
  }
  const min = input.minBid.trim();
  const max = input.maxBid.trim();
  if (min && Number(min) < 0) errors.push('竞价范围下限不能是负数');
  if (max && Number(max) < 0) errors.push('竞价范围上限不能是负数');
  if (min && max && Number(max) < Number(min)) errors.push('竞价范围上限不能低于下限');
  const ratio = input.singleAdjustCapRatio.trim();
  if (ratio && (Number(ratio) < 0 || Number(ratio) > 1)) {
    errors.push('单次调整比例额度必须在 0 与 1 之间（0 = 不额外限制）');
  }
  const amount = input.singleAdjustCapAmount.trim();
  if (amount && Number(amount) < 0) errors.push('单次调整金额额度不能是负数');
  const acos = input.targetAcos.trim();
  const roas = input.targetRoas.trim();
  if (acos && roas) {
    errors.push('目标 ACOS 与目标 ROAS 只能给一个（它们互为倒数）');
  }
  if (!acos && !roas) {
    errors.push('必须给一个目标（目标 ACOS 或目标 ROAS）');
  }
  if (acos && Number(acos) <= 0) errors.push('目标 ACOS 必须大于 0（比例：0.25 = 25%）');
  if (roas && Number(roas) <= 0) errors.push('目标 ROAS 必须大于 0（比例：4 = 400%）');
  return errors;
}
