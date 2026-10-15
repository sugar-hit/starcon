import { api } from '../inventory/api';

/**
 * 10.1.1.3 时效与限制前端模型。
 *
 * <p>蓝图：「维护渠道时效（分国家分重量段）与限制（尺寸上限、重量上限、禁限品类、
 * 电池/液体/危险品标记要求），并在选品与刊登阶段提示」，输出「时效估算；限制命中提示」。
 *
 * <p>🔴 这个模块的界面上最容易做成假活的一件事，是把「限制」画成几列数字然后让页面
 * 自己判断能不能发。**限制命中由服务端算**（POST precheck），页面里
 * **没有**任何一处拿 sizeLimits / markings 数组自己比对出「可以发 / 不能发」——
 * 蓝图的加粗句是「渠道限制（禁限品类、电池液体、磁力）要硬拦截，否则会被承运商
 * 拒收产生额外费用」，一个能被绕过的判断不算硬拦截。
 *
 * <p>⚠️ 重量段是**半开区间 [下限, 上限)**（下限含、上限不含，留空上限 = 最后一段）。
 * 页面上的校验与提示都按这个口径写：把 30kg 填进 [0.5, 30) 会得到一句「没有承诺」，
 * 而它在承运商档期表上正是这么写的。
 */

export interface TransitCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface LogisticsTransitCapabilities {
  dimensions: TransitCapabilityDimension[];
  serviceLevels: string[];
  flagKinds: string[];
  requirementModes: string[];
  readinessCodes: string[];
  statusCodes: string[];
  boundaryNote: string;
  limitationNote: string;
}

export interface TransitRuleRow {
  id: string;
  channelId: string;
  /** 渠道编码 / 名称是 10.1.1.2 的**只读快照** —— 本页不可编辑，改渠道回 /logistics/channels。 */
  channelCode: string;
  channelName: string;
  countryCode: string;
  serviceLevel: string;
  weightMinKg: number;
  /** 半开区间的上限；null = 无上限（最后一段）。 */
  weightMaxKg: number | null;
  transitDaysMin: number;
  transitDaysMax: number;
  /** 截单时间（当地墙钟，HH:mm）。 */
  cutoffTime: string;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  status: string;
  readiness: string;
  readinessMessage: string;
  /** 按**读取时刻**算出来的：生效区间是否含空或命中此刻。 */
  effectiveNow: boolean;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface TransitRuleSummary {
  total: number;
  ready: number;
  noTransit: number;
  noBand: number;
  disabled: number;
  effectiveNow: number;
  countries: number;
  channels: number;
}

export interface TransitRulePage {
  items: TransitRuleRow[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
  summary: TransitRuleSummary;
}

export interface SizeLimitRow {
  id: string;
  channelId: string;
  channelCode: string;
  channelName: string;
  /** null = 渠道级（所有国家）；非 null = 该国更严的一条。 */
  countryCode: string | null;
  maxLengthCm: number | null;
  maxWidthCm: number | null;
  maxHeightCm: number | null;
  maxGirthCm: number | null;
  maxWeightKg: number | null;
  scopeLabel: string;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface SizeLimitPage {
  items: SizeLimitRow[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
  channelLevel: number;
  countryLevel: number;
}

export interface MarkingRequirementRow {
  id: string;
  channelId: string;
  channelCode: string;
  channelName: string;
  flagKind: string;
  requirementMode: string;
  markingCode: string;
  carrierReason: string;
  /** 上游 10.1.1.2 对同一个标记的判定（null = 上游没登记限制）。 */
  upstreamRestrictionMode: string | null;
  consistentWithUpstream: boolean;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface MarkingRequirementPage {
  items: MarkingRequirementRow[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
  labelRequired: number;
  blocked: number;
  inconsistent: number;
}

export interface LogisticsTransitCount {
  code: string;
  label: string;
  count: number;
}

export interface LogisticsTransitOverview {
  transitSummary: TransitRuleSummary;
  byServiceLevel: LogisticsTransitCount[];
  byFlagKind: LogisticsTransitCount[];
  sizeLimitTotal: number;
  markingTotal: number;
  note: string;
}

// ================================================================ 写入口

export interface SaveTransitRuleInput {
  transitRuleId: string | null;
  channelId: string | null;
  countryCode: string;
  serviceLevel: string;
  weightMinKg: number;
  /** 留空 = 最后一段（大于下限）。 */
  weightMaxKg: number | null;
  transitDaysMin: number;
  transitDaysMax: number;
  cutoffTime: string;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  status: string;
  note: string;
  version: number;
}

export interface SaveSizeLimitInput {
  sizeLimitId: string | null;
  channelId: string | null;
  /** 留空 = 渠道级。 */
  countryCode: string;
  maxLengthCm: number | null;
  maxWidthCm: number | null;
  maxHeightCm: number | null;
  maxGirthCm: number | null;
  maxWeightKg: number | null;
  note: string;
  version: number;
}

export interface SaveMarkingRequirementInput {
  markingId: string | null;
  channelId: string | null;
  flagKind: string;
  requirementMode: string;
  markingCode: string;
  carrierReason: string;
  note: string;
  version: number;
}

// ================================================================ 判定

export interface LimitHit {
  hitKind: string;
  severity: 'BLOCK' | 'REVIEW' | string;
  subject: string;
  message: string;
  carrierReason: string;
}

export interface LimitPrecheckInput {
  channelIds: string[] | null;
  countryCode: string;
  categoryCodes: string[];
  containsBattery: boolean;
  containsLiquid: boolean;
  containsMagnet: boolean;
  containsDangerousGoods: boolean;
  weightKg: number | null;
  lengthCm: number | null;
  widthCm: number | null;
  heightCm: number | null;
  shipAt: string | null;
}

export interface LimitPrecheckVerdict {
  channelId: string;
  channelCode: string;
  channelName: string;
  countryCode: string;
  verdict: 'PASS' | 'REVIEW' | 'BLOCK' | string;
  hits: LimitHit[];
  transitDaysMin: number | null;
  transitDaysMax: number | null;
  note: string;
}

export interface LimitPrecheckResult {
  evaluatedAt: string;
  verdicts: LimitPrecheckVerdict[];
  pass: number;
  review: number;
  block: number;
  note: string;
}

export interface TransitEstimateInput {
  channelIds: string[] | null;
  countryCode: string;
  weightKg: number;
  serviceLevel: string;
  shipAt: string | null;
}

export interface TransitEstimateItem {
  channelId: string;
  channelCode: string;
  channelName: string;
  countryCode: string;
  serviceLevel: string;
  queriedWeightKg: number;
  matchedBand: string;
  transitDaysMin: number;
  transitDaysMax: number;
  cutoffTime: string;
  earliestArrival: string;
  latestArrival: string;
  effectiveWindowNote: string;
  matchedRuleNote: string;
}

export interface TransitEstimateResult {
  shipAt: string;
  items: TransitEstimateItem[];
  matched: number;
  unmatched: number;
  note: string;
}

// ================================================================ 表单初值

export const EMPTY_RULE_FORM: SaveTransitRuleInput = {
  transitRuleId: null,
  channelId: null,
  countryCode: 'DE',
  serviceLevel: 'STANDARD',
  weightMinKg: 0,
  weightMaxKg: null,
  transitDaysMin: 5,
  transitDaysMax: 8,
  cutoffTime: '18:00',
  effectiveFrom: null,
  effectiveTo: null,
  status: 'ACTIVE',
  note: '',
  version: 0,
};

export const EMPTY_SIZE_FORM: SaveSizeLimitInput = {
  sizeLimitId: null,
  channelId: null,
  countryCode: '',
  maxLengthCm: null,
  maxWidthCm: null,
  maxHeightCm: null,
  maxGirthCm: null,
  maxWeightKg: null,
  note: '',
  version: 0,
};

export const EMPTY_MARKING_FORM: SaveMarkingRequirementInput = {
  markingId: null,
  channelId: null,
  flagKind: 'BATTERY',
  requirementMode: 'LABEL_REQUIRED',
  markingCode: '',
  carrierReason: '',
  note: '',
  version: 0,
};

export interface TransitFilters {
  keyword: string;
  channelId: string;
  countryCode: string;
  serviceLevel: string;
  status: string;
  readiness: string;
  page: number;
  size: number;
}

export const EMPTY_TRANSIT_FILTERS: TransitFilters = {
  keyword: '',
  channelId: '',
  countryCode: '',
  serviceLevel: '',
  status: '',
  readiness: '',
  page: 0,
  size: 50,
};

export const EMPTY_PRECHECK: LimitPrecheckInput = {
  channelIds: null,
  countryCode: 'DE',
  categoryCodes: [],
  containsBattery: false,
  containsLiquid: false,
  containsMagnet: false,
  containsDangerousGoods: false,
  weightKg: 1,
  lengthCm: 20,
  widthCm: 15,
  heightCm: 5,
  shipAt: null,
};

export const EMPTY_ESTIMATE: TransitEstimateInput = {
  channelIds: null,
  countryCode: 'DE',
  weightKg: 1,
  serviceLevel: '',
  shipAt: null,
};

// ================================================================ 文案

export function serviceLevelLabel(code: string): string {
  switch (code) {
    case 'ECONOMY':
      return '经济';
    case 'STANDARD':
      return '标准';
    case 'EXPRESS':
      return '特快';
    default:
      return code;
  }
}

export function flagKindLabel(code: string): string {
  switch (code) {
    case 'BATTERY':
      return '电池';
    case 'LIQUID':
      return '液体';
    case 'DANGEROUS_GOODS':
      return '危险品';
    case 'MAGNET':
      return '磁力';
    default:
      return code;
  }
}

export function requirementModeLabel(code: string): string {
  switch (code) {
    case 'BLOCK':
      return '拒收';
    case 'MANUAL_REVIEW':
      return '需人工确认';
    case 'LABEL_REQUIRED':
      return '必须贴标识';
    case 'ALLOWED':
      return '明确放行';
    default:
      return code;
  }
}

export function transitStatusLabel(code: string): string {
  switch (code) {
    case 'ACTIVE':
      return '启用';
    case 'DISABLED':
      return '停用';
    default:
      return code;
  }
}

export function ruleReadinessMeta(readiness: string): {
  label: string;
  tone: 'success' | 'warning' | 'neutral';
} {
  switch (readiness) {
    case 'READY':
      return { label: '承诺齐备', tone: 'success' };
    case 'NO_TRANSIT':
      return { label: '缺时效天数', tone: 'neutral' };
    case 'NO_BAND':
      return { label: '重量段为空', tone: 'neutral' };
    case 'DISABLED':
      return { label: '已停用', tone: 'warning' };
    default:
      return { label: readiness, tone: 'neutral' };
  }
}

export function verdictMeta(verdict: string): {
  label: string;
  tone: 'success' | 'warning' | 'danger';
} {
  switch (verdict) {
    case 'PASS':
      return { label: '可承运', tone: 'success' };
    case 'REVIEW':
      return { label: '需人工确认', tone: 'warning' };
    case 'BLOCK':
      return { label: '硬拦截', tone: 'danger' };
    default:
      return { label: verdict, tone: 'neutral' as 'warning' };
  }
}

export function severityMeta(severity: string): { label: string; tone: 'warning' | 'danger' } {
  return severity === 'BLOCK'
    ? { label: '硬拦截', tone: 'danger' }
    : { label: '需人工确认', tone: 'warning' };
}

/** 命中项的展示文案：把 hitKind 翻成人话，前端只做**展示**不做判定。 */
export function hitKindLabel(hitKind: string): string {
  switch (hitKind) {
    case 'PROHIBITED_CATEGORY':
      return '禁限品类';
    case 'BATTERY':
      return '电池';
    case 'LIQUID':
      return '液体';
    case 'MAGNET':
      return '磁力';
    case 'DANGEROUS_GOODS':
      return '危险品';
    case 'LENGTH':
      return '长度超限';
    case 'WIDTH':
      return '宽度超限';
    case 'HEIGHT':
      return '高度超限';
    case 'DIMENSION':
      return '周长和超限';
    case 'WEIGHT':
      return '重量超限';
    case 'TRANSIT_MISSING':
      return '缺时效承诺';
    case 'CHANNEL_NOT_READY':
      return '渠道不可用';
    default:
      return hitKind;
  }
}

/** 重量段的展示文案：半开区间，留空上限显示为「≥X kg」。 */
export function bandText(min: number, max: number | null): string {
  const lo = trimNumber(min);
  return max === null || max === undefined
    ? `≥ ${lo} kg`
    : `${lo} ~ ${trimNumber(max)} kg（不含上限）`;
}

function trimNumber(value: number): string {
  return String(Number.parseFloat(value.toFixed(6)));
}

/** 时效天数展示：同一天数时不重复写两遍。 */
export function transitDaysText(min: number, max: number): string {
  return min === max ? `${min} 天` : `${min} ~ ${max} 天`;
}

// ================================================================ 工具

/** 空串转 null：后端对「未填」与「填了 0」的处理不同（重量上限 0 与未登记是两回事）。 */
export function optionalNumber(raw: string): number | null {
  const trimmed = raw.trim();
  if (trimmed === '') return null;
  const value = Number(trimmed);
  return Number.isFinite(value) ? value : null;
}

/** 把逗号 / 空格 / 分号分隔的输入拆成去重的大写列表（品类编码）。 */
export function parseList(raw: string): string[] {
  return Array.from(
    new Set(
      raw
        .split(/[,;\s]+/)
        .map((v) => v.trim().toUpperCase())
        .filter(Boolean),
    ),
  );
}

/** {@code 'HH:mm'} → {@code 'HH:mm:ss'}；无法解析时原样返回，交给后端报错。 */
export function toCutoffTime(hhmm: string): string {
  const trimmed = hhmm.trim();
  return /^\d{1,2}:\d{2}$/.test(trimmed) ? `${trimmed}:00` : trimmed;
}

// ================================================================ 校验

/**
 * 时效规则的本地校验，逐条返回理由。
 *
 * <p>前端这道校验是为了**早点告诉人**，不是安全边界 —— 后端有 DB 层 CHECK 与
 * 五个触发器兜底。但它能在提交前就标出「重量段上限 ≤ 下限」这类看着合理、
 * 实际永远命中不了的配置。
 */
export function validateRuleForm(input: SaveTransitRuleInput): string[] {
  const problems: string[] = [];
  if (!input.channelId) problems.push('必须选择所属渠道：时效承诺挂在渠道之下');
  if (!/^[A-Za-z]{2}$/.test(input.countryCode.trim())) {
    problems.push('国家必须是两位字母（如 DE / US）');
  }
  if (!(input.weightMinKg >= 0)) {
    problems.push('重量段下限不能为负');
  }
  if (input.weightMaxKg !== null && input.weightMaxKg <= input.weightMinKg) {
    problems.push(
      `重量段上限（${input.weightMaxKg}）必须大于下限（${input.weightMinKg}）：上限 ≤ 下限的段永远命中不了`,
    );
  }
  if (input.weightMaxKg !== null && input.weightMaxKg === input.weightMinKg) {
    problems.push('重量段是半开区间 [下限, 上限)：留空上限表示「大于下限」，不要填一个等于下限的值');
  }
  if (input.transitDaysMin < 1) {
    problems.push('时效天数至少 1 天：为 0 时承诺不出送达天数（可用性会停在「缺时效」）');
  }
  if (input.transitDaysMax < input.transitDaysMin) {
    problems.push('时效天数上限不得小于下限');
  }
  if (!/^\d{1,2}:\d{2}$/.test(input.cutoffTime.trim())) {
    problems.push('截单时间格式必须是 HH:mm');
  }
  const hasFrom = Boolean(input.effectiveFrom);
  const hasTo = Boolean(input.effectiveTo);
  if (hasFrom !== hasTo) {
    problems.push('生效区间要么都给要么都不给：只填一端是半配置');
  }
  if (
    hasFrom &&
    hasTo &&
    new Date(input.effectiveFrom!).getTime() >= new Date(input.effectiveTo!).getTime()
  ) {
    problems.push('生效区间的起点必须早于终点');
  }
  return problems;
}

/** 尺寸 / 重量上限的本地校验：至少一项，且三维要么都给要么都不给。 */
export function validateSizeForm(input: SaveSizeLimitInput): string[] {
  const problems: string[] = [];
  if (!input.channelId) problems.push('必须选择所属渠道：上限挂在渠道之下');
  if (input.countryCode.trim() && !/^[A-Za-z]{2}$/.test(input.countryCode.trim())) {
    problems.push('国家必须是两位字母（如 DE / US）；留空表示渠道级');
  }
  const dims = [input.maxLengthCm, input.maxWidthCm, input.maxHeightCm, input.maxGirthCm, input.maxWeightKg];
  if (dims.every((d) => d === null)) {
    problems.push('至少要给一项上限：否则这是一行「没写限制」');
  }
  const three = [input.maxLengthCm, input.maxWidthCm, input.maxHeightCm];
  const given = three.filter((d) => d !== null).length;
  if (given !== 0 && given !== 3) {
    problems.push(
      '长 / 宽 / 高要么都给要么都不给：只给两维会造出「20×30×∞」这种谁也判不了的半配置，而高恰恰是快递与航空最常卡的一维',
    );
  }
  for (const value of dims) {
    if (value !== null && value <= 0) problems.push('上限必须大于 0');
  }
  return problems;
}

/** 标记要求的本地校验：要求贴标识就必须点名贴什么。 */
export function validateMarkingForm(input: SaveMarkingRequirementInput): string[] {
  const problems: string[] = [];
  if (!input.channelId) problems.push('必须选择所属渠道：标记要求挂在渠道之下');
  if (input.requirementMode === 'LABEL_REQUIRED' && !input.markingCode.trim()) {
    problems.push(
      '要求贴标识就必须点名贴什么（如 UN38.3 / IATA 锂电池标）：不点名等于把「漏打标识在清关出问题」留给下一个人',
    );
  }
  return problems;
}

/** 从一行规则铺出表单初值（编辑时用）。 */
export function toRuleForm(row: TransitRuleRow): SaveTransitRuleInput {
  return {
    transitRuleId: row.id,
    channelId: row.channelId,
    countryCode: row.countryCode,
    serviceLevel: row.serviceLevel,
    weightMinKg: row.weightMinKg,
    weightMaxKg: row.weightMaxKg,
    transitDaysMin: row.transitDaysMin,
    transitDaysMax: row.transitDaysMax,
    cutoffTime: row.cutoffTime,
    effectiveFrom: row.effectiveFrom,
    effectiveTo: row.effectiveTo,
    status: row.status,
    note: row.note ?? '',
    version: row.version,
  };
}

/** 从一行上限铺出表单初值（编辑时用）。 */
export function toSizeForm(row: SizeLimitRow): SaveSizeLimitInput {
  return {
    sizeLimitId: row.id,
    channelId: row.channelId,
    countryCode: row.countryCode ?? '',
    maxLengthCm: row.maxLengthCm,
    maxWidthCm: row.maxWidthCm,
    maxHeightCm: row.maxHeightCm,
    maxGirthCm: row.maxGirthCm,
    maxWeightKg: row.maxWeightKg,
    note: row.note ?? '',
    version: row.version,
  };
}

/** 从一行标记要求铺出表单初值（编辑时用）。 */
export function toMarkingForm(row: MarkingRequirementRow): SaveMarkingRequirementInput {
  return {
    markingId: row.id,
    channelId: row.channelId,
    flagKind: row.flagKind,
    requirementMode: row.requirementMode,
    markingCode: row.markingCode ?? '',
    carrierReason: row.carrierReason ?? '',
    note: row.note ?? '',
    version: row.version,
  };
}

// ================================================================ 端点

export function fetchTransitCapabilities(): Promise<LogisticsTransitCapabilities> {
  return api<LogisticsTransitCapabilities>('channel/logistics-transit-limits/capabilities');
}

export function fetchTransitOverview(): Promise<LogisticsTransitOverview> {
  return api<LogisticsTransitOverview>('channel/logistics-transit-limits/overview');
}

export function fetchTransitRules(filters: TransitFilters): Promise<TransitRulePage> {
  const query = new URLSearchParams();
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.channelId) query.set('channelId', filters.channelId);
  if (filters.countryCode) query.set('countryCode', filters.countryCode);
  if (filters.serviceLevel) query.set('serviceLevel', filters.serviceLevel);
  if (filters.status) query.set('status', filters.status);
  if (filters.readiness) query.set('readiness', filters.readiness);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<TransitRulePage>(`channel/logistics-transit-limits/transit-rules?${query.toString()}`);
}

export function fetchSizeLimits(filters: TransitFilters): Promise<SizeLimitPage> {
  const query = new URLSearchParams();
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.channelId) query.set('channelId', filters.channelId);
  if (filters.countryCode) query.set('countryCode', filters.countryCode);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<SizeLimitPage>(`channel/logistics-transit-limits/size-limits?${query.toString()}`);
}

export function fetchMarkings(filters: TransitFilters): Promise<MarkingRequirementPage> {
  const query = new URLSearchParams();
  if (filters.channelId) query.set('channelId', filters.channelId);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<MarkingRequirementPage>(`channel/logistics-transit-limits/markings?${query.toString()}`);
}

export function saveTransitRule(input: SaveTransitRuleInput): Promise<TransitRuleRow> {
  return api<TransitRuleRow>('channel/logistics-transit-limits/transit-rules', 'POST', {
    ...input,
    cutoffTime: toCutoffTime(input.cutoffTime),
  });
}

export function removeTransitRule(id: string): Promise<null> {
  return api<null>(`channel/logistics-transit-limits/transit-rules/${id}`, 'DELETE');
}

export function saveSizeLimit(input: SaveSizeLimitInput): Promise<SizeLimitRow> {
  return api<SizeLimitRow>('channel/logistics-transit-limits/size-limits', 'POST', input);
}

export function removeSizeLimit(id: string): Promise<null> {
  return api<null>(`channel/logistics-transit-limits/size-limits/${id}`, 'DELETE');
}

export function saveMarkingRequirement(
  input: SaveMarkingRequirementInput,
): Promise<MarkingRequirementRow> {
  return api<MarkingRequirementRow>('channel/logistics-transit-limits/markings', 'POST', input);
}

export function removeMarking(id: string): Promise<null> {
  return api<null>(`channel/logistics-transit-limits/markings/${id}`, 'DELETE');
}

/**
 * 🔴 时效估算：分国家 + 重量 → 命中重量段 → 预计到达。
 *
 * <p>页面上**不自己算**预计到达：命中哪一段是服务端按半开区间与生效区间判的，
 * 页面再算一遍只会得到两个可能不一样的答案。
 */
export function estimateTransit(input: TransitEstimateInput): Promise<TransitEstimateResult> {
  return api<TransitEstimateResult>('channel/logistics-transit-limits/estimate', 'POST', input);
}

/**
 * 🔴 限制命中提示（选品与刊登阶段）。
 *
 * <p>页面把 SKU 安全属性（品类 / 带电 / 带液 / 带磁 / 危险品）与包裹尺寸重量发给
 * 服务端，拿回 PASS / REVIEW / BLOCK 与逐条理由。页面**不做**任何本地判定。
 */
export function checkLimits(input: LimitPrecheckInput): Promise<LimitPrecheckResult> {
  return api<LimitPrecheckResult>('channel/logistics-transit-limits/precheck', 'POST', input);
}
