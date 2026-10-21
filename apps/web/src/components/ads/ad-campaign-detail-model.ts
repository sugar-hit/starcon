import { api } from '../inventory/api';

/**
 * 14.1.1.2 活动详情 前端模型。
 *
 * 蓝图：「单活动全景：基本设置、预算与消耗曲线、广告组列表、投放商品、关键词、创意（视频/图片）、
 * 每日表现明细、平台侧修改历史。输入：campaign_id；聚合子资源。
 * 输出：详情视图 + 操作入口（调预算、改竞价、暂停、复制结构）。
 * 关系：上游 = 14.1.1.1；下游 = 14.1.2.1 广告组、14.3.1.1 报表。
 * **"复制结构"是高价值操作**：把跑得好的活动结构复制到新店铺/新 ASIN，是广告提效的核心动作。」
 *
 * 🔴 这个页面上最容易做丢的三件事，这里都挡住了：
 *
 * 1. **「平台侧修改历史」不是「没有修改」**。上游没有平台广告 API 同步器 ⇒ 没有同步源 ⇒
 *    `changeHistory.available` 恒为 `false`。页面必须把「拿不到」写出来，
 *    并把当前展示的**本地操作审计**标清楚 —— 把本地审计说成平台侧改历史就是在骗人。
 * 2. **「复制结构」的副本是真的，平台侧还不是**。`copyResult.pendingPlatformWriteback` 为 true、
 *    目标活动状态是暂停、平台侧 id 是 `LOCAL-COPY-` 前缀的本地标记。
 *    页面必须把这三件事一起显示，只说「已复制」会让人以为平台侧也建好了。
 * 3. **比率的 `null` 与 `0` 含义相反**。`null` = 不可比（分母为 0），
 *    `0` = 可比但真的是零。渲染成 0.00% 会让人以为「一次点击都没有」。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * ACOS / ROAS 与预算是服务端 `BigDecimal` 的事，前端算一遍就会引入二进制浮点误差。
 */

/** 金额与数量一律 number（JSON number），只原样展示。 */
export type AdMoney = number | null;

/** 🔴 null = 不可比（分母为 0），与 `0`（可比但真的是零）含义相反。 */
export type AdRatio = number | null;

export interface AdCampaignDetailCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface AdBidGuardrails {
  maxBid: number;
  maxSingleBidDelta: number;
  maxGroupsPerRequest: number;
  note: string;
}

export interface AdCampaignDetailCapabilities {
  dimensions: AdCampaignDetailCapabilityDimension[];
  creativeTypes: string[];
  productModes: string[];
  bidGuardrails: AdBidGuardrails;
  metricDefinitionNote: string;
  platformChangeHistoryNote: string;
  copyStructureNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

// ---------------------------------------------------------------- 活动本体（复用上游 14.1.1.1）

export interface AdCampaignRow {
  id: string;
  platformId: string;
  platformCode: string;
  platformName: string;
  shopCode: string;
  shopName: string;
  externalCampaignId: string;
  campaignName: string;
  platformAdType: string;
  internalAdType: string;
  adTypeMappingSource: string;
  adTypeComparable: boolean;
  status: string;
  currency: string;
  dailyBudget: AdMoney;
  impressions: AdMoney;
  clicks: AdMoney;
  orders: AdMoney;
  spend: AdMoney;
  sales: AdMoney;
  ctr: AdRatio;
  acos: AdRatio;
  roas: AdRatio;
  startAt: string | null;
  endAt: string | null;
  pendingPlatformWriteback: boolean;
  localChangedAt: string | null;
  dataAsOf: string | null;
  lastSyncedAt: string | null;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AdCampaignDailyMetricRow {
  metricDate: string;
  impressions: AdMoney;
  clicks: AdMoney;
  orders: AdMoney;
  spend: AdMoney;
  sales: AdMoney;
  ctr: AdRatio;
  acos: AdRatio;
  roas: AdRatio;
  syncedAt: string;
}

export interface AdCampaignOperationLogRow {
  id: string;
  campaignId: string;
  operation: string;
  beforeStatus: string;
  afterStatus: string;
  beforeBudget: AdMoney;
  afterBudget: AdMoney;
  requestedBy: string;
  reason: string;
  result: string;
  message: string;
  createdAt: string;
}

// ---------------------------------------------------------------- 预算与消耗曲线

export interface AdCampaignBudgetCurvePoint {
  date: string;
  dailyBudget: AdMoney;
  spend: AdMoney;
  sales: AdMoney;
  acos: AdRatio;
  roas: AdRatio;
  budgetUsageRate: AdRatio;
}

export interface AdCampaignBudgetCurve {
  dailyBudget: AdMoney;
  currency: string;
  from: string | null;
  to: string | null;
  windowDays: number;
  windowBudget: AdMoney;
  totalSpend: AdMoney;
  totalSales: AdMoney;
  acos: AdRatio;
  roas: AdRatio;
  points: AdCampaignBudgetCurvePoint[];
  note: string;
}

// ---------------------------------------------------------------- 子资源

export interface AdCampaignStructureSummary {
  groupCount: number;
  productCount: number;
  creativeCount: number;
  keywordCount: number;
  structureAsOf: string | null;
  structureNote: string;
}

export interface AdCampaignOperationEntry {
  operation: string;
  label: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
  note: string;
}

export interface AdCampaignDetailOperations {
  entries: AdCampaignOperationEntry[];
}

export interface AdCampaignChangeHistory {
  /** 🔴 平台侧修改历史是否可得（当前恒为 false：没有同步源）。 */
  available: boolean;
  ownerModule: string;
  impact: string;
  note: string;
  localAuditNote: string;
  /** 🔴 本地操作审计（**不是**平台侧改历史）。 */
  localAudit: AdCampaignOperationLogRow[];
}

export interface AdCampaignDetailPanorama {
  campaign: AdCampaignRow;
  budgetCurve: AdCampaignBudgetCurve;
  dailyMetrics: AdCampaignDailyMetricRow[];
  structure: AdCampaignStructureSummary;
  operations: AdCampaignDetailOperations;
  changeHistory: AdCampaignChangeHistory;
  note: string;
}

export interface AdCampaignGroupRow {
  id: string;
  campaignId: string;
  externalGroupId: string;
  groupName: string;
  status: string;
  defaultBid: AdMoney;
  bidCurrency: string;
  dailyBudget: AdMoney;
  targetingType: string;
  productCount: number;
  creativeCount: number;
  dataAsOf: string | null;
  updatedAt: string;
}

export interface AdCampaignProductRow {
  id: string;
  groupId: string;
  groupName: string;
  listingId: string;
  externalItemId: string;
  title: string;
  listingStatus: string | null;
  listingReviewStatus: string | null;
  createdAt: string;
}

export interface AdCampaignCreativeRow {
  id: string;
  campaignId: string;
  groupId: string | null;
  groupName: string | null;
  externalCreativeId: string;
  creativeName: string;
  creativeType: string;
  assetUrl: string;
  durationSeconds: number | null;
  status: string;
  dataAsOf: string | null;
}

export interface AdCampaignKeywordRow {
  id: string;
  externalKeywordId: string;
  keyword: string;
  internalAdType: string;
  platformAdType: string;
  bid: AdMoney;
  bidCurrency: string;
  status: string;
  listingKeywordId: string | null;
  updatedAt: string;
}

export interface AdCampaignPage<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

// ---------------------------------------------------------------- 改竞价

export interface AdCampaignBidUpdateItem {
  groupId: string;
  bid: number;
  reason?: string;
}

export interface AdCampaignBidUpdateItemResult {
  groupId: string;
  groupName: string;
  beforeBid: AdMoney;
  afterBid: AdMoney;
  result: string;
  message: string;
}

export interface AdCampaignBidUpdateResult {
  total: number;
  applied: number;
  rejected: number;
  skipped: number;
  guardrails: AdBidGuardrails;
  items: AdCampaignBidUpdateItemResult[];
  note: string;
}

// ---------------------------------------------------------------- 复制结构

export interface AdCampaignCopyRequest {
  targetShopCode: string;
  targetListingId?: string | undefined;
  productMode: string;
  newCampaignName?: string | undefined;
  reason?: string | undefined;
}

export interface AdCampaignCopyResult {
  sourceCampaignId: string;
  targetCampaignId: string;
  targetCampaignName: string;
  targetStatus: string;
  targetShopCode: string;
  productMode: string;
  targetListingId: string | null;
  groupCount: number;
  productCount: number;
  creativeCount: number;
  keywordCount: number;
  pendingPlatformWriteback: boolean;
  note: string;
}

// ---------------------------------------------------------------- 结构登记

export interface AdCampaignProductImport {
  listingId: string;
  externalItemId?: string | undefined;
  title?: string | undefined;
}

export interface AdCampaignCreativeImport {
  externalCreativeId: string;
  creativeName?: string | undefined;
  creativeType: string;
  assetUrl?: string | undefined;
  durationSeconds?: number | undefined;
  status?: string | undefined;
}

export interface AdCampaignGroupImport {
  externalGroupId: string;
  groupName?: string | undefined;
  status?: string | undefined;
  defaultBid?: number | undefined;
  bidCurrency?: string | undefined;
  dailyBudget?: number | undefined;
  targetingType?: string | undefined;
  products?: AdCampaignProductImport[] | undefined;
  creatives?: AdCampaignCreativeImport[] | undefined;
}

export interface AdCampaignStructureImportRequest {
  dataAsOf: string;
  groups: AdCampaignGroupImport[];
  creatives?: AdCampaignCreativeImport[] | undefined;
}

export interface AdCampaignStructureImportResult {
  groupCount: number;
  productCount: number;
  creativeCount: number;
  removedGroupCount: number;
  dataAsOf: string | null;
  note: string;
}

// ==================================================================
// 请求
// ==================================================================

function queryString(query: object): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : '';
}

const BASE = 'channel/ad-campaign-details';

export function fetchAdCampaignDetailCapabilities(): Promise<AdCampaignDetailCapabilities> {
  return api<AdCampaignDetailCapabilities>(`${BASE}/capabilities`);
}

export function fetchAdCampaignPanorama(
  id: string,
  window: { from?: string; to?: string } = {},
): Promise<AdCampaignDetailPanorama> {
  return api<AdCampaignDetailPanorama>(`${BASE}/campaigns/${id}${queryString(window)}`);
}

export function fetchAdCampaignGroups(
  id: string,
  query: { keyword?: string; status?: string; page?: number; size?: number } = {},
): Promise<AdCampaignPage<AdCampaignGroupRow>> {
  return api<AdCampaignPage<AdCampaignGroupRow>>(`${BASE}/campaigns/${id}/groups${queryString(query)}`);
}

export function fetchAdCampaignProducts(
  id: string,
  query: { keyword?: string; page?: number; size?: number } = {},
): Promise<AdCampaignPage<AdCampaignProductRow>> {
  return api<AdCampaignPage<AdCampaignProductRow>>(
    `${BASE}/campaigns/${id}/products${queryString(query)}`,
  );
}

export function fetchAdCampaignCreatives(
  id: string,
  query: { creativeType?: string; page?: number; size?: number } = {},
): Promise<AdCampaignPage<AdCampaignCreativeRow>> {
  return api<AdCampaignPage<AdCampaignCreativeRow>>(
    `${BASE}/campaigns/${id}/creatives${queryString(query)}`,
  );
}

export function fetchAdCampaignKeywords(
  id: string,
  query: { keyword?: string; page?: number; size?: number } = {},
): Promise<AdCampaignPage<AdCampaignKeywordRow>> {
  return api<AdCampaignPage<AdCampaignKeywordRow>>(
    `${BASE}/campaigns/${id}/keywords${queryString(query)}`,
  );
}

export function fetchAdCampaignChangeHistory(id: string): Promise<AdCampaignChangeHistory> {
  return api<AdCampaignChangeHistory>(`${BASE}/campaigns/${id}/change-history`);
}

export function updateAdCampaignBids(
  id: string,
  items: AdCampaignBidUpdateItem[],
): Promise<AdCampaignBidUpdateResult> {
  return api<AdCampaignBidUpdateResult>(`${BASE}/campaigns/${id}/bids`, 'POST', { items });
}

export function copyAdCampaignStructure(
  id: string,
  request: AdCampaignCopyRequest,
): Promise<AdCampaignCopyResult> {
  return api<AdCampaignCopyResult>(`${BASE}/campaigns/${id}/copy`, 'POST', request);
}

export function importAdCampaignStructure(
  id: string,
  request: AdCampaignStructureImportRequest,
): Promise<AdCampaignStructureImportResult> {
  return api<AdCampaignStructureImportResult>(`${BASE}/campaigns/${id}/structure`, 'POST', request);
}

// ==================================================================
// 展示辅助
// ==================================================================

export function adStatusLabel(status: string): string {
  if (status === 'ENABLED') return '投放中';
  if (status === 'PAUSED') return '已暂停';
  if (status === 'ENDED') return '已结束';
  if (status === 'ARCHIVED') return '已归档';
  if (status === 'PENDING_REVIEW') return '审核中';
  return '状态未知';
}

export function creativeTypeLabel(type: string): string {
  if (type === 'IMAGE') return '图片';
  if (type === 'VIDEO') return '视频';
  if (type === 'CAROUSEL') return '图集';
  if (type === 'TEXT') return '文本';
  return type;
}

export function productModeLabel(mode: string): string {
  if (mode === 'KEEP_PRODUCTS') return '沿用源活动的投放商品';
  if (mode === 'TARGET_LISTING') return '改投指定的 ASIN';
  return mode;
}

export function operationResultLabel(result: string): string {
  if (result === 'APPLIED') return '已生效';
  if (result === 'REJECTED') return '被拒';
  if (result === 'SKIPPED') return '跳过';
  return result;
}

export function operationResultTone(result: string): string {
  if (result === 'REJECTED') return 'text-destructive';
  if (result === 'SKIPPED') return 'text-muted-foreground';
  return 'text-emerald-600';
}

export function operationLabel(operation: string): string {
  if (operation === 'PAUSE') return '暂停';
  if (operation === 'ENABLE') return '启用';
  if (operation === 'SET_BUDGET') return '设为预算';
  if (operation === 'ADJUST_BUDGET') return '调整预算';
  return operation;
}

/** 金额展示：null 显示「—」（**不是 0**）。 */
export function money(value: AdMoney, currency?: string): string {
  if (value === null || value === undefined) return '—';
  const fixed = value.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

export function quantity(value: AdMoney): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

/**
 * 派生比率展示：**不可比显示「不可比」，不是 0.00%**。
 *
 * 🔴 0 是「可比，但真的是零」；null 是「压根算不出来」（分母为 0）。
 */
export function ratioText(value: AdRatio | undefined, asPercent = true): string {
  if (value === null || value === undefined) return '不可比';
  return asPercent ? `${(value * 100).toFixed(2)}%` : value.toFixed(4);
}

export function ratioTone(value: AdRatio | undefined): string {
  return value === null || value === undefined ? 'text-muted-foreground' : 'text-foreground';
}

export function timestampText(value: string | null): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/**
 * 🔴 平台侧修改历史的可用性文案。
 *
 * 🔴 `available=false` 时**必须**说清「拿不到」以及「当前展示的是什么」——
 * 把它渲染成「暂无修改记录」就是在说「平台侧没改过」，那是假的。
 */
export function changeHistoryText(available: boolean, note: string, localAuditNote: string): string {
  if (available) return note;
  return `${note}\n\n当前展示：${localAuditNote}`;
}

/**
 * 🔴 复制结构结果的文案：**结构副本是真的，平台侧还不是**。
 *
 * 三件事缺一不可：结构真落库了、平台侧还没有、关键词没有复制。
 */
export function copyResultText(result: AdCampaignCopyResult): string {
  const parts = [
    `结构副本已落库：目标活动 ${result.targetCampaignName}（${result.targetShopCode}），` +
      `复制了 ${result.groupCount} 个广告组 / ${result.productCount} 个投放商品 / ${result.creativeCount} 个创意。`,
  ];
  if (result.pendingPlatformWriteback) {
    parts.push('平台侧还没有这份结构（回写队列未交付），目标活动已标「待回写平台」且状态为暂停。');
  }
  parts.push(`关键词没有复制（${result.keywordCount} 条）：平台侧还没建这个活动，关键词 id 无从分配。`);
  return parts.join(' ');
}

/** UUID 形状校验（前端只做形状校验，不做业务校验）。 */
export function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value.trim());
}

// ==================================================================
// 表单校验
// ==================================================================

export interface AdCopyStructureInput {
  targetShopCode: string;
  productMode: string;
  targetListingId: string;
  newCampaignName: string;
  reason: string;
}

export const emptyAdCopyStructureInput: AdCopyStructureInput = {
  targetShopCode: '',
  productMode: 'KEEP_PRODUCTS',
  targetListingId: '',
  newCampaignName: '',
  reason: '',
};

/**
 * 复制结构的表单校验。
 *
 * 🔴 `TARGET_LISTING` 必须给出目标 ASIN（listing id）；`KEEP_PRODUCTS` 不接受目标 ASIN ——
 * 两者语义相反（沿用 vs 改投），混着填会让界面上说的和做的不一致。
 */
export function validateCopyStructure(input: AdCopyStructureInput): { ok: boolean; errors: string[] } {
  const errors: string[] = [];
  if (!input.targetShopCode.trim()) errors.push('目标店铺编码必填');
  if (!['KEEP_PRODUCTS', 'TARGET_LISTING'].includes(input.productMode)) {
    errors.push('投放商品处理方式不合法');
  }
  if (input.productMode === 'TARGET_LISTING') {
    if (!input.targetListingId.trim()) {
      errors.push('改投指定 ASIN 时必须给出目标 listing id');
    } else if (!isUuid(input.targetListingId)) {
      errors.push('目标 listing id 必须是合法 UUID');
    }
  } else if (input.targetListingId.trim()) {
    errors.push('沿用源活动的投放商品时不能同时指定目标 ASIN（两者语义相反）');
  }
  return { ok: errors.length === 0, errors };
}

export interface AdBidInput {
  groupId: string;
  bid: string;
  reason: string;
}

/**
 * 改竞价的表单校验。
 *
 * 🔴 数值上限由服务端最终裁决（护栏逐条拒绝），这里只挡住明显的错。
 */
export function validateBidUpdate(input: AdBidInput): { ok: boolean; errors: string[] } {
  const errors: string[] = [];
  if (!isUuid(input.groupId)) errors.push('广告组 id 必须是合法 UUID');
  if (input.bid.trim() === '') {
    errors.push('目标竞价必填');
  } else {
    const parsed = Number(input.bid);
    if (Number.isNaN(parsed)) errors.push('目标竞价必须是数字');
    else if (parsed < 0) errors.push('目标竞价不能为负');
  }
  return { ok: errors.length === 0, errors };
}
