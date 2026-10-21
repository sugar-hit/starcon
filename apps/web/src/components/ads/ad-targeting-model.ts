import { api } from '../inventory/api';

/**
 * 14.1.3.2 投放定位 前端模型。
 *
 * 蓝图：「管理投放定位（地域、人群、设备、时段、匹配类型），支持按定位维度看效果，
 * 指导出价调整。输入：定位设置；定位维度效果数据。输出：定位效果对比表 + 调整建议。
 * 关系：上游 = 14.1.2.1；下游 = 14.2.1.2 分时策略。
 * **定位效果差异大时应收窄投放，无效定位的花费是最容易省下来的钱。**
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「不可比」与「真的是零」是两件事**。销售额为 0 时 ACOS 是 `null`（不可比），
 *    页面必须显示「不可比」，渲染成 `0.00` 就是在说「这个定位的 ACOS 是 0」。
 * 2. **跨币种不相加**。对比表按币种分行，汇总的金额按币种分列；前端**不做任何金额算术**。
 * 3. **建议必须有依据**。每条建议都带 `evidence`（依据数值）与 `basis`（口径与阈值）；
 *    `actionable=false` 的建议**不能照做**，页面要显式标出来。
 * 4. **「本地已收窄」≠「平台侧已收窄」**。平台回写队列未交付 ⇒ 本地写只落本地，
 *    页面显式标出「待回写平台」。
 * 5. **平台原文 ≠ 归一定位**。上游 V281 的 `targetingSettings` 是**平台原文串**；
 *    归一后的定位维度是**解读结果**，两者在字段名与展示上分得开。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * 比例与比率是服务端 `BigDecimal` 的事，前端算一遍就会引入二进制浮点误差。
 */

/** 金额与数量一律 number（JSON number），只原样展示。 */
export type AdMoney = number | null;

export interface AdTargetingCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface AdTargetingGuardrails {
  minBidFactor: number;
  maxBidFactor: number;
  minSpendForSuggestion: number;
  minImpressionsForSuggestion: number;
  minClicksForSuggestion: number;
  targetAcos: number;
  hardAcos: number;
  dimensionSpreadFactor: number;
  maxDimensionsPerInterpret: number;
  note: string;
}

export interface AdTargetingCapabilities {
  dimensions: AdTargetingCapabilityDimension[];
  dimensionValues: string[];
  statuses: string[];
  guardrails: AdTargetingGuardrails;
  interpretationNote: string;
  platformWritebackNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface AdTargetingOption {
  value: string;
  label: string;
}

export interface AdTargetingOptions {
  dimensions: AdTargetingOption[];
  statuses: AdTargetingOption[];
  sorts: AdTargetingOption[];
  guardrails: AdTargetingGuardrails;
  note: string;
}

export interface AdTargetingSettingRow {
  id: string;
  campaignId: string;
  campaignName: string;
  shopCode: string;
  currency: string;
  /** 🔴 所属广告组 id（null = 活动级定位）。 */
  groupId: string | null;
  groupName: string | null;
  scopeKind: string;
  dimension: string;
  dimensionValue: string;
  platformRawKey: string;
  platformRawValue: string;
  status: string;
  bidFactor: AdMoney;
  daypartStartHour: number | null;
  daypartEndHour: number | null;
  daypartHours: AdMoney;
  daypartCrossesMidnight: boolean | null;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  pendingPlatformWriteback: boolean;
  localChangedAt: string | null;
  source: string;
  note: string;
  createdAt: string;
  updatedAt: string;
}

export interface AdTargetingSettingPage {
  items: AdTargetingSettingRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdTargetingSettingSummary {
  total: number;
  campaignCount: number;
  dimensionCounts: Record<string, number>;
  statusCounts: Record<string, number>;
  pendingWritebackCount: number;
  note: string;
}

export interface AdTargetingEffectRow {
  campaignId: string;
  campaignName: string;
  shopCode: string;
  groupId: string | null;
  groupName: string | null;
  scopeKind: string;
  dimension: string;
  dimensionValue: string;
  currency: string;
  impressions: number;
  clicks: number;
  conversions: number;
  spend: AdMoney;
  sales: AdMoney;
  ctr: AdMoney;
  cpc: AdMoney;
  cvr: AdMoney;
  acos: AdMoney;
  roas: AdMoney;
  wastedSpend: AdMoney;
  wastedRatio: AdMoney;
  spendShare: AdMoney;
  settingCount: number;
  dataAsOf: string | null;
}

export interface AdTargetingEffectSummary {
  rowCount: number;
  campaignCount: number;
  dimensionCounts: Record<string, number>;
  spendByCurrency: Record<string, number>;
  salesByCurrency: Record<string, number>;
  impressions: number;
  clicks: number;
  conversions: number;
  note: string;
}

export interface AdTargetingEffectPage {
  items: AdTargetingEffectRow[];
  total: number;
  page: number;
  size: number;
  summary: AdTargetingEffectSummary;
}

export interface AdTargetingSuggestion {
  ruleId: string;
  ruleTitle: string;
  action: string;
  severity: string;
  campaignId: string;
  campaignName: string;
  groupId: string | null;
  groupName: string | null;
  dimension: string;
  dimensionValue: string;
  currency: string;
  spend: AdMoney;
  sales: AdMoney;
  impressions: number;
  clicks: number;
  conversions: number;
  acos: AdMoney;
  targetAcos: AdMoney;
  wastedSpend: AdMoney;
  spendShare: AdMoney;
  bidFactor: AdMoney;
  /** 🔴 依据数值（每条都带真实数字）。 */
  evidence: string[];
  /** 🔴 依据口径（规则 + 用了哪些阈值）。 */
  basis: string;
  /** 🔴 是否可执行（false = 样本不足，别照做）。 */
  actionable: boolean;
  note: string;
}

export interface AdTargetingSuggestionResult {
  items: AdTargetingSuggestion[];
  count: number;
  actionableCount: number;
  note: string;
}

export interface AdTargetingDaypartRow {
  campaignId: string;
  campaignName: string;
  groupId: string | null;
  groupName: string | null;
  dimensionValue: string;
  startHour: number | null;
  endHour: number | null;
  crossesMidnight: boolean;
  hours: AdMoney;
  impressions: number;
  clicks: number;
  conversions: number;
  spend: AdMoney;
  sales: AdMoney;
  currency: string;
  acos: AdMoney;
  roas: AdMoney;
}

export interface AdTargetingDaypartProfile {
  items: AdTargetingDaypartRow[];
  ownerModule: string;
  note: string;
}

export interface AdTargetingOperationRow {
  id: string;
  campaignId: string;
  groupId: string | null;
  settingId: string | null;
  operation: string;
  beforeValue: string;
  afterValue: string;
  result: string;
  message: string;
  requestedBy: string;
  reason: string;
  createdAt: string;
}

export interface AdTargetingOperationPage {
  items: AdTargetingOperationRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdTargetingEntry {
  entry: string;
  label: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
  note: string;
}

export interface AdTargetingDetail {
  setting: AdTargetingSettingRow;
  effects: AdTargetingEffectRow[];
  recentOperations: AdTargetingOperationRow[];
  entries: AdTargetingEntry[];
  note: string;
}

export interface AdTargetingCreateRequest {
  campaignId: string;
  groupId?: string | undefined;
  dimension: string;
  dimensionValue: string;
  platformRawKey?: string | undefined;
  platformRawValue?: string | undefined;
  bidFactor?: number | undefined;
  effectiveFrom?: string | undefined;
  effectiveTo?: string | undefined;
  reason?: string | undefined;
}

export interface AdTargetingUpdateRequest {
  dimensionValue?: string | undefined;
  status?: string | undefined;
  bidFactor?: number | undefined;
  effectiveFrom?: string | undefined;
  effectiveTo?: string | undefined;
  clearEffectiveTo?: boolean | undefined;
  note?: string | undefined;
  reason?: string | undefined;
}

export interface AdTargetingInterpretRequest {
  campaignId: string;
  groupId?: string | undefined;
  targetingSettings?: string | undefined;
  matchType?: string | undefined;
  apply?: boolean | undefined;
  reason?: string | undefined;
}

export interface AdTargetingInterpretedRow {
  dimension: string;
  dimensionValue: string;
  platformRawKey: string;
  platformRawValue: string;
  recognized: boolean;
  daypartStartHour: number | null;
  daypartEndHour: number | null;
  daypartHours: AdMoney;
  daypartCrossesMidnight: boolean | null;
  note: string;
}

export interface AdTargetingInterpretResult {
  items: AdTargetingInterpretedRow[];
  recognizedCount: number;
  unrecognizedCount: number;
  applied: boolean;
  appliedSettings: AdTargetingSettingRow[];
  skippedCount: number;
  note: string;
}

export interface AdTargetingMetricEntry {
  settingId: string;
  bucketDate: string;
  impressions: number;
  clicks: number;
  conversions: number;
  spend: number | undefined;
  sales: number | undefined;
  currency: string;
  syncedAt?: string | undefined;
  note?: string | undefined;
}

export interface AdTargetingMetricBatchRequest {
  entries: AdTargetingMetricEntry[];
  batchKey?: string | undefined;
  source?: string | undefined;
  sourceRef?: string | undefined;
  reason?: string | undefined;
}

export interface AdTargetingMetricBatchResult {
  accepted: number;
  replaced: number;
  rejected: number;
  rejectedMessages: string[];
  batchKey: string;
  syncedAt: string;
  note: string;
}

export interface AdTargetingNarrowRequest {
  reason?: string | undefined;
  bidFactor?: number | undefined;
  override?: boolean | undefined;
}

export interface AdTargetingNarrowResult {
  settingId: string;
  status: string;
  pendingPlatformWriteback: boolean;
  bidFactor: AdMoney;
  evidence: string[];
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

const BASE = 'channel/ad-targetings';

export interface AdTargetingListQuery {
  campaignId?: string | undefined;
  groupId?: string | undefined;
  dimension?: string | undefined;
  dimensionValue?: string | undefined;
  status?: string | undefined;
  keyword?: string | undefined;
  sort?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface AdTargetingEffectQuery {
  campaignId?: string | undefined;
  groupId?: string | undefined;
  dimension?: string | undefined;
  dimensionValue?: string | undefined;
  currency?: string | undefined;
  from?: string | undefined;
  to?: string | undefined;
  sort?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export function fetchAdTargetingCapabilities(): Promise<AdTargetingCapabilities> {
  return api<AdTargetingCapabilities>(`${BASE}/capabilities`);
}

export function fetchAdTargetingOptions(): Promise<AdTargetingOptions> {
  return api<AdTargetingOptions>(`${BASE}/options`);
}

export function fetchAdTargetings(query: AdTargetingListQuery = {}): Promise<AdTargetingSettingPage> {
  return api<AdTargetingSettingPage>(`${BASE}${queryString(query)}`);
}

export function fetchAdTargetingSummary(
  query: Omit<AdTargetingListQuery, 'page' | 'size' | 'sort'> = {},
): Promise<AdTargetingSettingSummary> {
  return api<AdTargetingSettingSummary>(`${BASE}/summary${queryString(query)}`);
}

export function fetchAdTargetingDetail(settingId: string): Promise<AdTargetingDetail> {
  return api<AdTargetingDetail>(`${BASE}/${settingId}`);
}

export function fetchAdTargetingOperations(
  settingId: string,
  query: { page?: number; size?: number } = {},
): Promise<AdTargetingOperationPage> {
  return api<AdTargetingOperationPage>(`${BASE}/${settingId}/operations${queryString(query)}`);
}

export function fetchAdTargetingEffects(
  query: AdTargetingEffectQuery = {},
): Promise<AdTargetingEffectPage> {
  return api<AdTargetingEffectPage>(`${BASE}/effects${queryString(query)}`);
}

export function fetchAdTargetingSuggestions(
  query: {
    campaignId?: string | undefined;
    groupId?: string | undefined;
    dimension?: string | undefined;
    from?: string | undefined;
    to?: string | undefined;
  } = {},
): Promise<AdTargetingSuggestionResult> {
  return api<AdTargetingSuggestionResult>(`${BASE}/suggestions${queryString(query)}`);
}

export function fetchAdTargetingDaypartProfile(
  query: {
    campaignId?: string | undefined;
    groupId?: string | undefined;
    from?: string | undefined;
    to?: string | undefined;
  } = {},
): Promise<AdTargetingDaypartProfile> {
  return api<AdTargetingDaypartProfile>(`${BASE}/daypart-profile${queryString(query)}`);
}

export function createAdTargeting(
  request: AdTargetingCreateRequest,
): Promise<AdTargetingSettingRow> {
  return api<AdTargetingSettingRow>(BASE, 'POST', request);
}

export function updateAdTargeting(
  settingId: string,
  request: AdTargetingUpdateRequest,
): Promise<AdTargetingSettingRow> {
  return api<AdTargetingSettingRow>(`${BASE}/${settingId}`, 'PATCH', request);
}

export function archiveAdTargeting(
  settingId: string,
  reason?: string,
): Promise<AdTargetingSettingRow> {
  return api<AdTargetingSettingRow>(`${BASE}/${settingId}`, 'DELETE', { reason: reason ?? '' });
}

export function interpretAdTargeting(
  request: AdTargetingInterpretRequest,
): Promise<AdTargetingInterpretResult> {
  return api<AdTargetingInterpretResult>(`${BASE}/interpret`, 'POST', request);
}

export function ingestAdTargetingMetrics(
  request: AdTargetingMetricBatchRequest,
): Promise<AdTargetingMetricBatchResult> {
  return api<AdTargetingMetricBatchResult>(`${BASE}/metrics`, 'POST', request);
}

export function narrowAdTargeting(
  settingId: string,
  request: AdTargetingNarrowRequest,
): Promise<AdTargetingNarrowResult> {
  return api<AdTargetingNarrowResult>(`${BASE}/${settingId}/narrow`, 'POST', request);
}

// ==================================================================
// 展示辅助
// ==================================================================

/** 定位维度展示名。 */
export function dimensionLabel(dimension: string): string {
  if (dimension === 'GEO') return '地域';
  if (dimension === 'AUDIENCE') return '人群';
  if (dimension === 'DEVICE') return '设备';
  if (dimension === 'DAYPART') return '时段';
  if (dimension === 'MATCH_TYPE') return '匹配类型';
  if (dimension === 'UNMAPPED') return '未识别';
  return dimension;
}

/** 定位状态展示名。 */
export function targetingStatusLabel(status: string): string {
  if (status === 'ACTIVE') return '在投';
  if (status === 'PAUSED') return '已收窄（停投这条定位）';
  if (status === 'ARCHIVED') return '已归档';
  return status;
}

/** 归属层级展示名。 */
export function scopeLabel(scopeKind: string): string {
  if (scopeKind === 'CAMPAIGN') return '活动级';
  if (scopeKind === 'GROUP') return '组级';
  return scopeKind;
}

/** 建议动作展示名。 */
export function suggestionActionLabel(action: string): string {
  if (action === 'NARROW') return '收窄投放';
  if (action === 'REDUCE_BID') return '降价';
  if (action === 'INCREASE_BID') return '加价放量';
  return action;
}

/** 建议规则展示名。 */
export function suggestionRuleLabel(ruleId: string): string {
  if (ruleId === 'WASTED_TARGETING') return '无效定位';
  if (ruleId === 'ACOS_OVER_TARGET') return 'ACOS 超标';
  if (ruleId === 'UNDERPERFORMING') return '严重超标';
  if (ruleId === 'SCALE_UP') return '表现好，可放量';
  if (ruleId === 'DIMENSION_SPREAD') return '维度内差异过大';
  return ruleId;
}

/** 严重度展示名 + 色调。 */
export function suggestionSeverityLabel(severity: string): string {
  if (severity === 'HIGH') return '严重';
  if (severity === 'MEDIUM') return '中等';
  if (severity === 'LOW') return '轻微';
  return severity;
}

export function suggestionSeverityTone(severity: string): string {
  if (severity === 'HIGH') return 'text-destructive';
  if (severity === 'MEDIUM') return 'text-amber-600';
  return 'text-muted-foreground';
}

/** 写动作展示名。 */
export function targetingOperationLabel(operation: string): string {
  if (operation === 'CREATE_SETTING') return '新建定位';
  if (operation === 'UPDATE_SETTING') return '改定位';
  if (operation === 'ARCHIVE_SETTING') return '归档定位';
  if (operation === 'INTERPRET_TARGETING') return '解读平台原文';
  if (operation === 'INGEST_METRIC') return '同步效果数据';
  if (operation === 'NARROW_TARGETING') return '收窄投放';
  return operation;
}

/**
 * 🔴 比率展示：**null = 不可比**，不是 0。
 *
 * 渲染成「0.00%」就是在说「这个定位的 ACOS 是 0」—— 那是假的
 * （销售额为 0 时 ACOS 是**算不出来**，不是零）。
 */
export function ratioText(value: AdMoney | undefined, digits = 2, suffix = ''): string {
  if (value === null || value === undefined) return '不可比';
  return `${value.toFixed(digits)}${suffix}`;
}

/** 百分比展示（null = 不可比）。 */
export function percentText(value: AdMoney | undefined): string {
  if (value === null || value === undefined) return '不可比';
  return `${(value * 100).toFixed(1)}%`;
}

/** 金额展示：null 显示「—」（**不是 0**）。 */
export function money(value: AdMoney | undefined, currency?: string): string {
  if (value === null || value === undefined) return '—';
  const fixed = value.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

/** 数量展示（计数）。 */
export function countText(value: number): string {
  return value.toLocaleString('zh-CN');
}

/** 出价系数展示（比例：1 = 不调整）。 */
export function bidFactorText(value: AdMoney | undefined): string {
  if (value === null || value === undefined) return '—';
  return `${value.toFixed(6)}（1 = 不调整）`;
}

/** 时间戳展示。 */
export function timestampText(value: string | null): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/** 日期展示（UTC 日）。 */
export function dateText(value: string | null): string {
  if (!value) return '—';
  return value.slice(0, 10);
}

/** UUID 形状校验（前端只做形状校验，不做业务校验）。 */
export function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value.trim());
}

/**
 * 🔴 时段窗口展示：**解析不出来的窗口是空**，不能编一个出来。
 */
export function daypartWindowText(
  startHour: number | null,
  endHour: number | null,
  hours: AdMoney,
  crossesMidnight: boolean | null,
): string {
  if (startHour === null || endHour === null) return '时段窗口未解析';
  const span = `${startHour}:00 ~ ${endHour}:00${crossesMidnight ? '（跨零点）' : ''}`;
  return hours === null || hours === undefined ? span : `${span} · ${hours.toFixed(6)} 小时`;
}

/**
 * 🔴 解读结果的一行文案：认不出来要显式说「未识别」，
 * 不能渲染成「地域：FOO」让人以为那是个正经的地域定位。
 */
export function interpretedRowText(row: AdTargetingInterpretedRow): string {
  const label = row.recognized ? dimensionLabel(row.dimension) : '未识别';
  const value = row.dimensionValue || '（空值）';
  const raw = row.platformRawKey ? `（平台原文键：${row.platformRawKey}）` : '';
  return `${label}：${value}${raw}`;
}

/** 🔴 本地写回写状态：本地已改 ≠ 平台侧已改。 */
export function pendingWritebackText(pending: boolean): string {
  return pending ? '本地已改、尚未回写平台' : '未标记待回写';
}

// ==================================================================
// 表单校验
// ==================================================================

export interface AdTargetingCreateInput {
  campaignId: string;
  groupId: string;
  dimension: string;
  dimensionValue: string;
  platformRawKey: string;
  platformRawValue: string;
  bidFactor: string;
  reason: string;
}

export const emptyAdTargetingCreateInput: AdTargetingCreateInput = {
  campaignId: '',
  groupId: '',
  dimension: 'GEO',
  dimensionValue: '',
  platformRawKey: '',
  platformRawValue: '',
  bidFactor: '',
  reason: '',
};

function parseNumber(raw: string): number | null {
  const trimmed = raw.trim();
  if (trimmed === '') return null;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : Number.NaN;
}

/**
 * 新建定位设置的表单校验。
 *
 * 🔴 数值上限由服务端最终裁决（护栏逐条拒绝），这里只挡住明显的错。
 */
export function validateTargetingCreate(input: AdTargetingCreateInput): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.campaignId.trim()) errors.push('所属活动必填');
  if (!input.dimension.trim()) errors.push('定位维度必填');
  if (!input.dimensionValue.trim()) errors.push('定位取值必填');

  const factor = parseNumber(input.bidFactor);
  if (Number.isNaN(factor)) errors.push('出价系数必须是数字');
  else if (factor !== null && factor <= 0) errors.push('出价系数必须大于 0');

  return { ok: errors.length === 0, errors };
}

export interface AdTargetingInterpretInput {
  campaignId: string;
  groupId: string;
  targetingSettings: string;
  matchType: string;
  apply: boolean;
  reason: string;
}

export const emptyAdTargetingInterpretInput: AdTargetingInterpretInput = {
  campaignId: '',
  groupId: '',
  targetingSettings: '',
  matchType: '',
  apply: false,
  reason: '',
};

/**
 * 解读平台原文的表单校验。
 *
 * 🔴 原文与广告组**至少给一个**：都不给就没什么可解读的。
 */
export function validateAdTargetingInterpret(input: AdTargetingInterpretInput): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.campaignId.trim()) errors.push('所属活动必填');
  if (!input.targetingSettings.trim() && !input.groupId.trim()) {
    errors.push('平台原文与广告组至少给一个（都不给就没什么可解读的）');
  }
  return { ok: errors.length === 0, errors };
}

export interface AdTargetingMetricInput {
  settingId: string;
  bucketDate: string;
  impressions: string;
  clicks: string;
  conversions: string;
  spend: string;
  sales: string;
  currency: string;
  batchKey: string;
  reason: string;
}

export const emptyAdTargetingMetricInput: AdTargetingMetricInput = {
  settingId: '',
  bucketDate: '',
  impressions: '',
  clicks: '',
  conversions: '',
  spend: '',
  sales: '',
  currency: '',
  batchKey: '',
  reason: '',
};

/**
 * 同步定位效果数据的表单校验。
 *
 * 🔴 点击数不能大于展示数 —— 服务端也会拒，但界面先挡住更省事。
 */
export function validateAdTargetingMetric(input: AdTargetingMetricInput): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.settingId.trim()) errors.push('定位设置必填');
  if (!input.bucketDate.trim()) errors.push('指标日必填（UTC 日，yyyy-MM-dd）');
  if (!input.currency.trim()) errors.push('指标币种必填');

  const impressions = parseNumber(input.impressions);
  const clicks = parseNumber(input.clicks);
  const conversions = parseNumber(input.conversions);
  for (const [label, value] of [
    ['展示数', impressions],
    ['点击数', clicks],
    ['转化数', conversions],
  ] as const) {
    if (Number.isNaN(value)) errors.push(`${label}必须是数字`);
    else if (value !== null && value < 0) errors.push(`${label}不能为负`);
  }
  if (
    impressions !== null &&
    clicks !== null &&
    !Number.isNaN(impressions) &&
    !Number.isNaN(clicks) &&
    clicks > impressions
  ) {
    errors.push('点击数不能大于展示数');
  }

  for (const [label, value] of [
    ['花费', parseNumber(input.spend)],
    ['销售额', parseNumber(input.sales)],
  ] as const) {
    if (Number.isNaN(value)) errors.push(`${label}必须是数字`);
    else if (value !== null && value < 0) errors.push(`${label}不能为负`);
  }

  return { ok: errors.length === 0, errors };
}
