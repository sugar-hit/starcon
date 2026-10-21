import { api } from '../inventory/api';

/**
 * 14.1.1.1 广告活动列表 前端模型。
 *
 * 蓝图：「广告活动主列表：平台、店铺、活动名、类型、状态、预算、消耗、曝光、点击、订单、销售额、ACOS/ROAS，
 * 按维度筛选与排序，支持批量暂停/启用/调预算。输入：筛选条件（平台、店铺、状态、日期）；广告数据同步结果；用户数据范围。
 * 输出：分页列表 + 汇总；批量操作任务。关系：上游 = 平台广告 API 同步；下游 = 14.1.1.2 详情、14.2.2.1 预算。
 * **广告数据同步是本系统的地基**：同步失败时页面要明确显示「数据截至 X 时间」，不能用空数据假装没有投放。」
 *
 * 🔴 这个页面上最容易做丢的三件事，这里都挡住了：
 *
 * 1. **「数据截至 X 时间」不是文案，是数据**。`dataAsOf`（水位）、`syncStates`（逐平台/店铺的同步状态与
 *    失败原因）、`emptyReason`（三种「空」的区别）都由服务端算好返回 —— 浏览器里不自己推水位，
 *    推出来的水位和落库的水位会有两个答案。
 * 2. **归一类型与原始类型必须同时看得见**。`platformAdType`（平台原文）与 `internalAdType`（归一后）
 *    在每一行里成对出现；`UNMAPPED` 是**显式合法值**，表示「没有可比口径」，不是「没有这种广告」。
 * 3. **ACOS / ROAS 的口径写在表头**。`metricDefinitionNote` 随汇总一起返回，页面直接显示它 ——
 *    跨部门沟通时「ACOS 到底是哪个除哪个」必须是同一份定义。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * ACOS / ROAS 与汇总都是服务端 `BigDecimal` 的事，前端算一遍就会引入二进制浮点误差，
 * 而且会让「界面上的 ACOS」和「报表里的 ACOS」对不上。
 */

/** 金额与数量一律 number（JSON number），只原样展示。 */
export type AdMoney = number | null;

/** 🔴 null = 不可比（分母为 0），与 `0`（可比但真的是零）含义相反。 */
export type AdRatio = number | null;

export interface AdCampaignCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface AdBudgetGuardrails {
  maxSingleBudgetDelta: number;
  maxTotalBudgetDelta: number;
  maxCampaignBudget: number;
  maxCampaignsPerRequest: number;
  note: string;
}

export interface AdCampaignCapabilities {
  dimensions: AdCampaignCapabilityDimension[];
  statuses: string[];
  sortOptions: string[];
  operations: string[];
  emptyReasons: string[];
  guardrails: AdBudgetGuardrails;
  metricDefinitionNote: string;
  dataAsOfNote: string;
  unmappedNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface AdCampaignOption {
  value: string;
  label: string;
}

export interface AdCampaignOptions {
  platforms: AdCampaignOption[];
  shops: AdCampaignOption[];
  statuses: AdCampaignOption[];
  internalAdTypes: AdCampaignOption[];
  sortOptions: AdCampaignOption[];
  guardrails: AdBudgetGuardrails;
  metricDefinitionNote: string;
}

export interface AdCampaignRow {
  id: string;
  platformId: string;
  platformCode: string;
  platformName: string;
  shopCode: string;
  shopName: string;
  externalCampaignId: string;
  campaignName: string;
  /** 🔴 原始平台类型标记：平台怎么叫就怎么存。 */
  platformAdType: string;
  /** 🔴 归一后的内部类型；UNMAPPED = 上游没给归一口径。 */
  internalAdType: string;
  /** 🔴 归一口径来源：PLATFORM_DEFAULT / TENANT_OVERRIDE / UNMAPPED。 */
  adTypeMappingSource: string;
  /** 🔴 是否参与跨平台类型比较（UNMAPPED 为 false，但活动照样存在）。 */
  adTypeComparable: boolean;
  status: string;
  currency: string;
  dailyBudget: AdMoney;
  impressions: AdMoney;
  clicks: AdMoney;
  orders: AdMoney;
  spend: AdMoney;
  sales: AdMoney;
  /** 🔴 派生比率：null = 不可比，与 0 含义相反。 */
  ctr: AdRatio;
  /** 🔴 ACOS = 消耗 / 销售额。 */
  acos: AdRatio;
  /** 🔴 ROAS = 销售额 / 消耗。 */
  roas: AdRatio;
  startAt: string | null;
  endAt: string | null;
  /** 🔴 本地已改、尚未回写平台。 */
  pendingPlatformWriteback: boolean;
  localChangedAt: string | null;
  /** 🔴 这条活动的数据截至时刻。 */
  dataAsOf: string | null;
  lastSyncedAt: string | null;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AdCampaignSummary {
  campaignCount: number;
  enabledCount: number;
  pausedCount: number;
  otherStatusCount: number;
  totalBudget: AdMoney;
  totalImpressions: AdMoney;
  totalClicks: AdMoney;
  totalOrders: AdMoney;
  totalSpend: AdMoney;
  totalSales: AdMoney;
  ctr: AdRatio;
  acos: AdRatio;
  roas: AdRatio;
  metricFrom: string | null;
  metricTo: string | null;
  metricDefinitionNote: string;
}

export interface AdSyncState {
  platformId: string;
  platformCode: string;
  platformName: string;
  shopCode: string;
  shopName: string;
  status: string;
  /** 🔴 数据截至时刻；失败的同步不推进水位，因此可能为 null。 */
  dataAsOf: string | null;
  lastRunAt: string | null;
  campaignCount: number;
  /** 🔴 失败原因（FAILED 时必有内容）。 */
  message: string;
}

export interface AdCampaignPage {
  items: AdCampaignRow[];
  total: number;
  page: number;
  size: number;
  summary: AdCampaignSummary;
  /** 🔴 数据截至时刻（成功同步的水位；从未同步成功过则为 null）。 */
  dataAsOf: string | null;
  dataAsOfNote: string;
  syncStates: AdSyncState[];
  /** 🔴 空态原因：NO_SYNCED_DATA / NO_CAMPAIGNS / FILTERED_EMPTY / null。 */
  emptyReason: string | null;
  emptyNote: string;
  metricDefinitionNote: string;
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

export interface AdCampaignDetail {
  campaign: AdCampaignRow;
  dailyMetrics: AdCampaignDailyMetricRow[];
  operationLogs: AdCampaignOperationLogRow[];
  note: string;
}

export interface AdCampaignExternalView {
  id: string;
  externalCampaignId: string;
  campaignName: string;
  platformCode: string;
  shopCode: string;
  platformAdType: string;
  internalAdType: string;
  status: string;
  currency: string;
  dailyBudget: AdMoney;
  spend: AdMoney;
  sales: AdMoney;
  orders: AdMoney;
  acos: AdRatio;
  roas: AdRatio;
  dataAsOf: string | null;
  pendingPlatformWriteback: boolean;
  note: string;
}

export interface AdSyncRunRow {
  id: string;
  platformId: string;
  platformCode: string;
  shopCode: string;
  shopName: string;
  status: string;
  dataAsOf: string | null;
  startedAt: string;
  finishedAt: string | null;
  campaignCount: number;
  metricRowCount: number;
  message: string;
  sourceRef: string;
  createdAt: string;
}

export interface AdSyncRunPage {
  items: AdSyncRunRow[];
  total: number;
  page: number;
  size: number;
}

export interface BulkOperationItem {
  campaignId: string;
  campaignName: string;
  result: string;
  beforeStatus: string;
  afterStatus: string;
  beforeBudget: AdMoney;
  afterBudget: AdMoney;
  message: string;
}

export interface BulkOperationResult {
  total: number;
  applied: number;
  rejected: number;
  skipped: number;
  guardrails: AdBudgetGuardrails;
  items: BulkOperationItem[];
  note: string;
}

export interface ImportDailyMetric {
  metricDate: string;
  impressions?: number | undefined;
  clicks?: number | undefined;
  orders?: number | undefined;
  spend?: number | undefined;
  sales?: number | undefined;
}

export interface ImportCampaign {
  externalCampaignId: string;
  campaignName: string;
  adTypeProfileId: string;
  status: string;
  currency: string;
  dailyBudget?: number | undefined;
  startAt?: string | undefined;
  endAt?: string | undefined;
  metrics: ImportDailyMetric[];
  note: string;
}

export interface ImportSyncRun {
  status: string;
  dataAsOf?: string | undefined;
  startedAt: string;
  finishedAt?: string | undefined;
  message: string;
  sourceRef: string;
}

export interface ImportRequest {
  platformId: string;
  shopCode: string;
  syncRun: ImportSyncRun;
  campaigns: ImportCampaign[];
}

export interface ImportResult {
  syncRunId: string;
  syncStatus: string;
  dataAsOf: string | null;
  campaignCount: number;
  metricRowCount: number;
  createdCount: number;
  updatedCount: number;
  campaigns: AdCampaignRow[];
  note: string;
}

// ==================================================================
// 请求
// ==================================================================

/** 列表筛选条件。空串与 undefined 同义（后端把它当成「不筛这一维」）。 */
export interface AdCampaignQuery {
  platformId?: string;
  shopCode?: string;
  status?: string;
  internalAdType?: string;
  platformAdType?: string;
  keyword?: string;
  from?: string;
  to?: string;
  sort?: string;
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

export function fetchAdCampaignCapabilities(): Promise<AdCampaignCapabilities> {
  return api<AdCampaignCapabilities>('channel/ad-campaigns/capabilities');
}

export function fetchAdCampaignOptions(): Promise<AdCampaignOptions> {
  return api<AdCampaignOptions>('channel/ad-campaigns/options');
}

export function fetchAdCampaigns(query: AdCampaignQuery = {}): Promise<AdCampaignPage> {
  return api<AdCampaignPage>(`channel/ad-campaigns/campaigns${queryString(query)}`);
}

export function fetchAdCampaign(id: string): Promise<AdCampaignDetail> {
  return api<AdCampaignDetail>(`channel/ad-campaigns/campaigns/${id}`);
}

export function fetchAdCampaignByExternal(
  platformId: string,
  shopCode: string,
  externalCampaignId: string,
): Promise<AdCampaignExternalView> {
  return api<AdCampaignExternalView>(
    `channel/ad-campaigns/by-external${queryString({ platformId, shopCode, externalCampaignId })}`,
  );
}

export function fetchAdSyncRuns(page = 0, size = 20): Promise<AdSyncRunPage> {
  return api<AdSyncRunPage>(`channel/ad-campaigns/sync-runs${queryString({ page, size })}`);
}

/** 登记一次广告数据同步的结果（活动 + 每日指标）。 */
export function importAdCampaigns(request: ImportRequest): Promise<ImportResult> {
  return api<ImportResult>('channel/ad-campaigns/imports', 'POST', request);
}

/** 批量暂停 / 启用 / 调预算（只接受显式选中的 id）。 */
export function bulkOperateAdCampaigns(
  campaignIds: string[],
  operation: string,
  extra: { budget?: number; delta?: number; reason?: string } = {},
): Promise<BulkOperationResult> {
  return api<BulkOperationResult>('channel/ad-campaigns/bulk-operations', 'POST', {
    campaignIds,
    operation,
    budget: extra.budget,
    delta: extra.delta,
    reason: extra.reason ?? '',
  });
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

/** 内部类型标签（蓝图点名的六类 + 显式的未映射）。 */
export function internalTypeLabel(internalAdType: string): string {
  if (internalAdType === 'AUTO_DISCOVERY') return '自动广告';
  if (internalAdType === 'SPONSORED_BRAND') return 'SB 品牌广告';
  if (internalAdType === 'SPONSORED_DISPLAY') return 'SD 展示型品牌广告';
  if (internalAdType === 'BRAND_AD') return '品牌广告';
  if (internalAdType === 'DSP_PROGRAMMATIC') return 'DSP 程序化';
  if (internalAdType === 'PROMOTION_EVENT') return '大促广告';
  if (internalAdType === 'UNMAPPED') return '未映射（无归一口径）';
  return internalAdType;
}

/** 归一口径来源：这条归一是走 2.1.2.1 的哪一层解析出来的。 */
export function mappingSourceLabel(source: string): string {
  if (source === 'TENANT_OVERRIDE') return '租户覆盖';
  if (source === 'PLATFORM_DEFAULT') return '平台默认';
  if (source === 'UNMAPPED') return '未映射';
  return source;
}

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
 * 渲染成 0% 会让人以为「这条活动一次点击都没有」，而真相是「没有展示量，比率无从计算」。
 */
export function ratioText(value: AdRatio, asPercent = true): string {
  if (value === null || value === undefined) return '不可比';
  return asPercent ? `${(value * 100).toFixed(2)}%` : value.toFixed(4);
}

export function ratioTone(value: AdRatio): string {
  return value === null || value === undefined ? 'text-muted-foreground' : 'text-foreground';
}

export function timestampText(value: string | null): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/**
 * 空态文案：**三种「空」必须分开说**。
 *
 * 🔴 「还没同步到数据」与「真的没有投放」是相反的两件事 ——
 * 把它们渲染成同一种空态，就是蓝图那句「不能用空数据假装没有投放」。
 */
export function emptyStateText(reason: string | null, note: string): string {
  if (!reason) return '';
  if (reason === 'NO_SYNCED_DATA') {
    return note || '还没有任何一次成功的广告数据同步 —— 这不是「没有投放」，是还没同步到数据。';
  }
  if (reason === 'NO_CAMPAIGNS') {
    return note || '同步成功过，这个范围里确实一条广告活动都没有。';
  }
  if (reason === 'FILTERED_EMPTY') {
    return note || '活动存在，但被当前的筛选条件全部排除了。';
  }
  return note;
}

/** 一行活动的类型提示：原始标记 + 归一类型（两者必须同时出现）。 */
export function adTypeLine(row: AdCampaignRow): string {
  return `原始标记 ${row.platformAdType} → 归一 ${internalTypeLabel(row.internalAdType)}`;
}

/** 同步状态标签 + 语气。 */
export function syncStatusLabel(status: string): string {
  if (status === 'SUCCESS') return '同步成功';
  if (status === 'PARTIAL') return '部分成功';
  if (status === 'FAILED') return '同步失败';
  return status;
}

export function syncStatusTone(status: string): string {
  if (status === 'FAILED') return 'text-destructive';
  if (status === 'PARTIAL') return 'text-amber-600';
  return 'text-emerald-600';
}

/** 批量操作结果标签 + 语气。 */
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

/** 护栏上限的一行说明（界面上要把天花板写出来，而不是等用户撞墙）。 */
export function guardrailText(guardrails: AdBudgetGuardrails | null): string {
  if (!guardrails) return '';
  return (
    `单次单活动调整上限 ${guardrails.maxSingleBudgetDelta}，一次批量总额上限 ${guardrails.maxTotalBudgetDelta}，` +
    `单活动预算上限 ${guardrails.maxCampaignBudget}，一次最多 ${guardrails.maxCampaignsPerRequest} 条。`
  );
}

/** UUID 形状校验（前端只做形状校验，不做业务校验）。 */
export function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value.trim());
}

// ==================================================================
// 表单校验
// ==================================================================

export interface AdImportInput {
  platformId: string;
  shopCode: string;
  syncStatus: string;
  dataAsOf: string;
  startedAt: string;
  finishedAt: string;
  message: string;
  sourceRef: string;
  externalCampaignId: string;
  campaignName: string;
  adTypeProfileId: string;
  status: string;
  currency: string;
  dailyBudget: string;
  metricDate: string;
  impressions: string;
  clicks: string;
  orders: string;
  spend: string;
  sales: string;
}

export const emptyAdImportInput: AdImportInput = {
  platformId: '',
  shopCode: '',
  syncStatus: 'SUCCESS',
  dataAsOf: '',
  startedAt: '',
  finishedAt: '',
  message: '',
  sourceRef: '',
  externalCampaignId: '',
  campaignName: '',
  adTypeProfileId: '',
  status: 'ENABLED',
  currency: 'USD',
  dailyBudget: '',
  metricDate: '',
  impressions: '0',
  clicks: '0',
  orders: '0',
  spend: '0',
  sales: '0',
};

/**
 * 登记同步结果的表单校验。
 *
 * 🔴 失败的同步**必须**有失败原因、**必须**没有数据截至时刻 ——
 * 失败就是没拿到数据，带了水位会让「数据截至 X 时间」虚高。
 */
export function validateAdImport(input: AdImportInput): { ok: boolean; errors: string[] } {
  const errors: string[] = [];
  if (!isUuid(input.platformId)) errors.push('平台 id 必须是合法 UUID');
  if (!input.shopCode.trim()) errors.push('店铺编码必填');
  if (!['SUCCESS', 'PARTIAL', 'FAILED'].includes(input.syncStatus)) {
    errors.push('同步状态只能是 SUCCESS / PARTIAL / FAILED');
  }
  if (input.syncStatus === 'FAILED') {
    if (!input.message.trim()) errors.push('🔴 失败的同步必须写明失败原因');
    if (input.dataAsOf.trim()) errors.push('🔴 失败的同步不能带数据截至时刻：失败不推进水位');
  } else if (!input.dataAsOf.trim()) {
    errors.push('成功的同步必须给出数据截至时刻（水位就是它）');
  } else if (Number.isNaN(new Date(input.dataAsOf).getTime())) {
    errors.push('数据截至时刻不是合法时间');
  }
  if (!input.startedAt.trim()) {
    errors.push('同步开始时刻必填');
  } else if (Number.isNaN(new Date(input.startedAt).getTime())) {
    errors.push('同步开始时刻不是合法时间');
  }
  if (!input.externalCampaignId.trim()) errors.push('平台侧活动 id 必填');
  if (!isUuid(input.adTypeProfileId)) {
    errors.push('🔴 广告类型档案 id 必须是合法 UUID（归一类型由 14.1.2.2 的档案带下来）');
  }
  if (!input.status.trim()) errors.push('活动状态必填');
  if (input.dailyBudget.trim() !== '') {
    const parsed = Number(input.dailyBudget);
    if (Number.isNaN(parsed) || parsed < 0) errors.push('日预算必须是不小于 0 的数字');
  }
  if (input.metricDate.trim() === '') {
    errors.push('每日指标的日期必填（没有指标也要写当天那一行 0 值）');
  } else if (Number.isNaN(new Date(input.metricDate).getTime())) {
    errors.push('每日指标的日期不是合法日期');
  }
  for (const [label, value] of [
    ['曝光量', input.impressions],
    ['点击量', input.clicks],
    ['订单量', input.orders],
  ] as const) {
    const parsed = Number(value.trim() === '' ? '0' : value);
    if (Number.isNaN(parsed) || parsed < 0) errors.push(`${label}必须是不小于 0 的数字`);
  }
  for (const [label, value] of [
    ['消耗', input.spend],
    ['销售额', input.sales],
  ] as const) {
    const parsed = Number(value.trim() === '' ? '0' : value);
    if (Number.isNaN(parsed) || parsed < 0) errors.push(`${label}必须是不小于 0 的数字`);
  }
  return { ok: errors.length === 0, errors };
}

/** 批量操作的表单校验（护栏的数值由服务端最终裁决，这里只挡住明显的错）。 */
export function validateBulkOperation(
  selected: string[],
  operation: string,
  budget: string,
  delta: string,
): { ok: boolean; errors: string[] } {
  const errors: string[] = [];
  if (selected.length === 0) errors.push('请先勾选要批量操作的活动');
  if (!['PAUSE', 'ENABLE', 'SET_BUDGET', 'ADJUST_BUDGET'].includes(operation)) {
    errors.push('操作类型不合法');
  }
  if (operation === 'SET_BUDGET') {
    const parsed = Number(budget);
    if (budget.trim() === '' || Number.isNaN(parsed) || parsed < 0) {
      errors.push('目标预算必须是不小于 0 的数字');
    }
  }
  if (operation === 'ADJUST_BUDGET') {
    const parsed = Number(delta);
    if (delta.trim() === '' || Number.isNaN(parsed)) {
      errors.push('调整额必须是数字（可以是负数）');
    }
  }
  return { ok: errors.length === 0, errors };
}
