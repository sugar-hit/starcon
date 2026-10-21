import { api } from '../inventory/api';

/**
 * 14.1.2.1 广告组管理 前端模型。
 *
 * 蓝图：「管理广告组：关联投放商品、匹配类型、定向设置、默认竞价，查看组级表现。
 * 输入：广告组设置；关联商品选择（从 4 listing 中选）。
 * 输出：广告组记录 + 组级报表入口。
 * 关系：上游 = 14.1.1.2；下游 = 14.1.3.1 关键词、14.2.1.1 竞价。
 * **广告组与 SKU 的映射是利润归因的基础**——投放的是 listing，归因要落到内部 SKU。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **组级表现「拿不到」与「没有表现」是两件事**。现有事实表里**没有组级指标表**
 *    （`ad_campaign_daily_metric` 是 campaign 维度，没有 group_id）⇒ 组级指标
 *    `available=false`。页面必须把「拿不到」写出来 —— 拿活动级指标冒充组级指标是假事实。
 * 2. **「组级报表入口」是入口，不是报表**。入口已交付（`available=true` + 路由标识 +
 *    责任模块），报表本体归 14.3.1.2。页面要点出责任模块，别让人以为报表已经能看。
 * 3. **「本地已改」与「平台侧已改」分得开**。平台回写队列未交付 ⇒ 本地写只落本地并标
 *    `pendingPlatformWriteback`。页面必须显式标出，否则界面上的「已暂停」会被当成
 *    平台侧已暂停 —— 那是在烧钱的假象。
 * 4. **「组级预算为空」= 继承活动预算**，不是 0；**「默认竞价为空」= 平台侧没给**，不是 0。
 *    渲染成 0.00 会让运营以为「这个组不许花钱」。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * 预算是服务端 `BigDecimal` 的事，前端算一遍就会引入二进制浮点误差。
 */

/** 金额与数量一律 number（JSON number），只原样展示。 */
export type AdMoney = number | null;

export interface AdGroupCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface AdGroupGuardrails {
  maxGroupDailyBudget: number;
  maxDefaultBid: number;
  maxProductsPerGroup: number;
  note: string;
}

export interface AdGroupReportEntry {
  /** 🔴 报表入口是否已交付（本模块提供入口与路由标识）。 */
  available: boolean;
  ownerModule: string;
  /** 🔴 报表本体的前端路由标识（蓝图的 14.3 广告分析入口）。 */
  reportRoute: string;
  /** 报表模块的稳定标识（模块号）。 */
  routeIdentifier: string;
  impact: string;
  note: string;
}

export interface AdGroupPerformanceAvailability {
  /** 🔴 组级指标是否可得（当前恒为 false：没有组级指标事实表）。 */
  available: boolean;
  ownerModule: string;
  impact: string;
  note: string;
}

export interface AdGroupManagementCapabilities {
  dimensions: AdGroupCapabilityDimension[];
  statuses: string[];
  guardrails: AdGroupGuardrails;
  reportEntry: AdGroupReportEntry;
  performance: AdGroupPerformanceAvailability;
  targetingNote: string;
  platformWritebackNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface AdGroupOption {
  value: string;
  label: string;
}

export interface AdGroupOptions {
  statuses: AdGroupOption[];
  sorts: AdGroupOption[];
  guardrails: AdGroupGuardrails;
  reportEntry: AdGroupReportEntry;
  targetingNote: string;
  platformWritebackNote: string;
}

export interface AdGroupRow {
  id: string;
  campaignId: string;
  campaignName: string;
  shopCode: string;
  currency: string;
  /** 平台侧广告组 id（本地新建的组是 LOCAL- 前缀的本地标记）。 */
  externalGroupId: string;
  groupName: string;
  status: string;
  /** 🔴 默认竞价（null = 平台侧没给，不是 0）。 */
  defaultBid: AdMoney;
  bidCurrency: string;
  /** 🔴 组级日预算（null = 继承活动预算，不是 0）。 */
  dailyBudget: AdMoney;
  /** 🔴 定向类型（平台原文，不解读）。 */
  targetingType: string;
  /** 🔴 匹配类型（平台原文，不解读）。 */
  matchType: string;
  /** 🔴 定向设置原文（平台原文串，不拆解不归一）。 */
  targetingSettings: string;
  productCount: number;
  /** 🔴 本地已改、尚未回写平台。 */
  pendingPlatformWriteback: boolean;
  /** 🔴 本地最后一次改动时刻（null = 本地没动过这个组）。 */
  localChangedAt: string | null;
  /** 🔴 这组结构的数据截至时刻。 */
  dataAsOf: string | null;
  updatedAt: string;
}

export interface AdGroupPage {
  items: AdGroupRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdGroupSummary {
  total: number;
  campaignCount: number;
  pendingWritebackCount: number;
  statusCounts: Record<string, number>;
  note: string;
}

export interface AdGroupProductRow {
  id: string;
  groupId: string;
  listingId: string;
  externalItemId: string;
  title: string;
  listingStatus: string | null;
  listingReviewStatus: string | null;
  /** 🔴 该 listing 已映射的内部 SKU 逻辑 id（只读；可能为空）。 */
  internalSkuIds: string[];
  /** 🔴 未映射到内部 SKU 的变体条数。 */
  unmappedVariantCount: number;
  /** 🔴 归因口径说明。 */
  attributionNote: string;
}

export interface AdGroupProductCandidateRow {
  listingId: string;
  externalItemId: string;
  title: string;
  listingStatus: string | null;
  listingReviewStatus: string | null;
  /** 🔴 是否已挂在这个广告组上。 */
  selected: boolean;
  /** 🔴 已映射的内部 SKU 逻辑 id（只读）。 */
  internalSkuIds: string[];
}

export interface AdGroupProductCandidatePage {
  items: AdGroupProductCandidateRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdGroupEntry {
  entry: string;
  label: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
  note: string;
}

export interface AdGroupOperationRow {
  id: string;
  campaignId: string;
  groupId: string;
  operation: string;
  beforeValue: string;
  afterValue: string;
  result: string;
  message: string;
  requestedBy: string;
  reason: string;
  createdAt: string;
}

export interface AdGroupOperationPage {
  items: AdGroupOperationRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdGroupDetail {
  group: AdGroupRow;
  products: AdGroupProductRow[];
  creativeCount: number;
  reportEntry: AdGroupReportEntry;
  performance: AdGroupPerformanceAvailability;
  entries: AdGroupEntry[];
  recentOperations: AdGroupOperationRow[];
  note: string;
}

export interface AdGroupCreateRequest {
  campaignId: string;
  groupName: string;
  defaultBid?: number | undefined;
  bidCurrency?: string | undefined;
  dailyBudget?: number | undefined;
  targetingType?: string | undefined;
  matchType?: string | undefined;
  targetingSettings?: string | undefined;
  reason?: string | undefined;
}

export interface AdGroupCreateResult {
  groupId: string;
  campaignId: string;
  groupName: string;
  status: string;
  externalGroupId: string;
  defaultBid: AdMoney;
  bidCurrency: string;
  dailyBudget: AdMoney;
  targetingType: string;
  matchType: string;
  targetingSettings: string;
  pendingPlatformWriteback: boolean;
  note: string;
}

export interface AdGroupSettingsUpdateRequest {
  groupName?: string | undefined;
  status?: string | undefined;
  dailyBudget?: number | undefined;
  clearDailyBudget?: boolean | undefined;
  targetingType?: string | undefined;
  matchType?: string | undefined;
  targetingSettings?: string | undefined;
  reason?: string | undefined;
}

export interface AdGroupSettingsUpdateResult {
  groupId: string;
  group: AdGroupRow;
  changedFields: string[];
  pendingPlatformWriteback: boolean;
  note: string;
}

export interface AdGroupProductUpdateRequest {
  listingIds: string[];
  reason?: string | undefined;
}

export interface AdGroupProductUpdateResult {
  groupId: string;
  addedListingIds: string[];
  removedListingIds: string[];
  productCount: number;
  pendingPlatformWriteback: boolean;
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

const BASE = 'channel/ad-groups';

export interface AdGroupListQuery {
  campaignId?: string | undefined;
  status?: string | undefined;
  keyword?: string | undefined;
  matchType?: string | undefined;
  targetingType?: string | undefined;
  shopCode?: string | undefined;
  sort?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export function fetchAdGroupCapabilities(): Promise<AdGroupManagementCapabilities> {
  return api<AdGroupManagementCapabilities>(`${BASE}/capabilities`);
}

export function fetchAdGroupOptions(): Promise<AdGroupOptions> {
  return api<AdGroupOptions>(`${BASE}/options`);
}

export function fetchAdGroups(query: AdGroupListQuery = {}): Promise<AdGroupPage> {
  return api<AdGroupPage>(`${BASE}${queryString(query)}`);
}

export function fetchAdGroupSummary(
  query: Omit<AdGroupListQuery, 'page' | 'size' | 'sort'> = {},
): Promise<AdGroupSummary> {
  return api<AdGroupSummary>(`${BASE}/summary${queryString(query)}`);
}

export function fetchAdGroupDetail(groupId: string): Promise<AdGroupDetail> {
  return api<AdGroupDetail>(`${BASE}/${groupId}`);
}

export function fetchAdGroupOperations(
  groupId: string,
  query: { page?: number; size?: number } = {},
): Promise<AdGroupOperationPage> {
  return api<AdGroupOperationPage>(`${BASE}/${groupId}/operations${queryString(query)}`);
}

export function fetchAdGroupProductCandidates(
  campaignId: string,
  query: { groupId?: string | undefined; keyword?: string | undefined; page?: number; size?: number } = {},
): Promise<AdGroupProductCandidatePage> {
  return api<AdGroupProductCandidatePage>(
    `${BASE}/product-candidates${queryString({ campaignId, ...query })}`,
  );
}

export function createAdGroup(request: AdGroupCreateRequest): Promise<AdGroupCreateResult> {
  return api<AdGroupCreateResult>(BASE, 'POST', request);
}

export function updateAdGroupSettings(
  groupId: string,
  request: AdGroupSettingsUpdateRequest,
): Promise<AdGroupSettingsUpdateResult> {
  return api<AdGroupSettingsUpdateResult>(`${BASE}/${groupId}`, 'PATCH', request);
}

export function setAdGroupProducts(
  groupId: string,
  request: AdGroupProductUpdateRequest,
): Promise<AdGroupProductUpdateResult> {
  return api<AdGroupProductUpdateResult>(`${BASE}/${groupId}/products`, 'PUT', request);
}

// ==================================================================
// 展示辅助
// ==================================================================

export function groupStatusLabel(status: string): string {
  if (status === 'ENABLED') return '投放中';
  if (status === 'PAUSED') return '已暂停';
  if (status === 'ENDED') return '已结束';
  if (status === 'ARCHIVED') return '已归档';
  if (status === 'PENDING_REVIEW') return '审核中';
  return '状态未知';
}

export function groupOperationLabel(operation: string): string {
  if (operation === 'CREATE_GROUP') return '新建广告组';
  if (operation === 'UPDATE_SETTINGS') return '改组设置';
  if (operation === 'SET_PRODUCTS') return '设投放商品';
  return operation;
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

/** 金额展示：null 显示「—」（**不是 0**）。 */
export function money(value: AdMoney, currency?: string): string {
  if (value === null || value === undefined) return '—';
  const fixed = value.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

/**
 * 🔴 组级预算的展示：**为空 = 继承活动预算**，不是 0。
 *
 * 渲染成「0.00」会让运营以为「这个组不许花钱」—— 那是相反的两件事。
 */
export function groupBudgetText(value: AdMoney | undefined, currency?: string): string {
  if (value === null || value === undefined) return '继承活动预算';
  return money(value, currency);
}

export function timestampText(value: string | null): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/** UUID 形状校验（前端只做形状校验，不做业务校验）。 */
export function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value.trim());
}

/**
 * 🔴 组级表现的可得性文案。
 *
 * 不可得时**必须**说清「拿不到」以及为什么 —— 把它渲染成「暂无表现」就是在说
 * 「这个组没有投放数据」，那是假的。
 */
export function groupPerformanceText(performance: AdGroupPerformanceAvailability): string {
  if (performance.available) return '组级表现指标已可得。';
  return (
    `当前不可得：${performance.impact}\n\n` +
    `责任模块：${performance.ownerModule}\n${performance.note}`
  );
}

/**
 * 🔴 组级报表入口的文案：**入口已交付 ≠ 报表已交付**。
 *
 * 责任模块必须一起显示，否则运营会以为报表数据已经能看。
 */
export function groupReportEntryText(entry: AdGroupReportEntry): string {
  if (!entry.available) {
    return `组级报表入口当前不可用：${entry.impact}（责任模块：${entry.ownerModule}）`;
  }
  return (
    `组级报表入口已交付：前端路由 ${entry.reportRoute}，稳定标识 ${entry.routeIdentifier}。` +
    `报表本体归 ${entry.ownerModule}。${entry.note}`
  );
}

/** 🔴 本地写回写状态：本地已改 ≠ 平台侧已改。 */
export function pendingWritebackText(pending: boolean): string {
  return pending ? '本地已改、尚未回写平台' : '与平台侧一致（未标记待回写）';
}

/**
 * 🔴 SKU 映射现状的展示：**映射现状 ≠ 归因结果**。
 */
export function skuMappingText(product: AdGroupProductRow): string {
  const mapped = product.internalSkuIds.length;
  if (mapped === 0 && product.unmappedVariantCount === 0) {
    return '该商品没有变体记录';
  }
  if (mapped === 0) {
    return `未映射到内部 SKU（${product.unmappedVariantCount} 个变体）`;
  }
  return `已映射 ${mapped} 个内部 SKU${
    product.unmappedVariantCount > 0 ? `，另有 ${product.unmappedVariantCount} 个变体未映射` : ''
  }`;
}

// ==================================================================
// 表单校验
// ==================================================================

export interface AdGroupCreateInput {
  campaignId: string;
  groupName: string;
  defaultBid: string;
  dailyBudget: string;
  targetingType: string;
  matchType: string;
  targetingSettings: string;
  reason: string;
}

export const emptyAdGroupCreateInput: AdGroupCreateInput = {
  campaignId: '',
  groupName: '',
  defaultBid: '',
  dailyBudget: '',
  targetingType: '',
  matchType: '',
  targetingSettings: '',
  reason: '',
};

function parseMoney(raw: string): number | null {
  const trimmed = raw.trim();
  if (trimmed === '') return null;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : Number.NaN;
}

/**
 * 新建广告组的表单校验。
 *
 * 🔴 数值上限由服务端最终裁决（护栏逐条拒绝），这里只挡住明显的错。
 */
export function validateGroupCreate(input: AdGroupCreateInput): { ok: boolean; errors: string[] } {
  const errors: string[] = [];
  if (!input.campaignId.trim()) errors.push('所属活动必填');
  if (!input.groupName.trim()) errors.push('广告组名必填');

  const bid = parseMoney(input.defaultBid);
  if (Number.isNaN(bid)) errors.push('默认竞价必须是数字');
  else if (bid !== null && bid < 0) errors.push('默认竞价不能为负');

  const budget = parseMoney(input.dailyBudget);
  if (Number.isNaN(budget)) errors.push('组级日预算必须是数字');
  else if (budget !== null && budget < 0) errors.push('组级日预算不能为负');

  return { ok: errors.length === 0, errors };
}

export interface AdGroupSettingsInput {
  groupName: string;
  status: string;
  dailyBudget: string;
  clearDailyBudget: boolean;
  targetingType: string;
  matchType: string;
  targetingSettings: string;
  reason: string;
}

export const emptyAdGroupSettingsInput: AdGroupSettingsInput = {
  groupName: '',
  status: '',
  dailyBudget: '',
  clearDailyBudget: false,
  targetingType: '',
  matchType: '',
  targetingSettings: '',
  reason: '',
};

/**
 * 改组设置的表单校验。
 *
 * 🔴 「恢复继承活动预算」与「设一个预算数值」**互斥** ——
 * 同时给会让服务端不知道听谁的（服务端会直接拒）。
 */
export function validateGroupSettings(input: AdGroupSettingsInput): { ok: boolean; errors: string[] } {
  const errors: string[] = [];
  if (input.clearDailyBudget && input.dailyBudget.trim() !== '') {
    errors.push('组级预算数值与「恢复继承活动预算」不能同时给');
  }
  const budget = parseMoney(input.dailyBudget);
  if (Number.isNaN(budget)) errors.push('组级日预算必须是数字');
  else if (budget !== null && budget < 0) errors.push('组级日预算不能为负');
  return { ok: errors.length === 0, errors };
}
