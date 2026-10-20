import { api } from '../inventory/api';

/**
 * 5.5.2.1 异常告警前端模型。
 *
 * <p>蓝图加粗句是本模块唯一不可让步的一条：
 *
 * <blockquote>
 *
 * **站点价差倒挂是跨境特有的风险**：德国站定价低于美国站会引发跨区窜货与合规问题。
 *
 * </blockquote>
 *
 * <p>⇒ 页面上**站点两两比对的���果**必须一眼看得出「谁比谁便宜」：
 * 每一行都带**低的那一边**与**贵的那一边**（{@link EventRow.siteCode} /
 * {@link EventRow.relatedSiteCode}），而不是只给一个「全平台平均价差」。
 *
 * <h2>🔴 null 绝不能渲染成 0</h2>
 *
 * <p>「取不到」与「确实是 0」是两件事：成本线读不到 ≠ 成本是 0；观测没有内部价 ≠ 内部价是 0。
 * 后端对这两种情况分别给了 {@code null} 与 {@code available=false}，
 * 前端这里用 {@link moneyText} 把它们渲染成「未登记」/「取不到」而不是 0.00。
 * 渲染成 0.00 的后果是「售价低于成本线」看起来比实际更严重，而页面上看不出任何异常。
 *
 * <h2>🔴 「长期」与「发生过」在界面上要分得开</h2>
 *
 * <p>①②④ 是**当前状态**（不再命中就自动消解）；③ 平台自动降价是**发生过的事**
 * （不会因窗口移走而消失）。所以 {@link statusTone} 对 RESOLVED 与 OPEN 给不同的语气，
 * 而持续天数只在状态型异常上才有意义。
 */

/** 四类价格异常。与后端 {@code AnomalyType} 逐字对应。 */
export type AnomalyType =
  | 'PLATFORM_INTERNAL_DIVERGENCE'
  | 'BELOW_COST'
  | 'PLATFORM_AUTO_DISCOUNT'
  | 'CROSS_SITE_INVERSION';

/** 异常事件的状态。与后端 {@code EventStatus} 逐字对应。 */
export type EventStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED';

/** 严重度。与后端 {@code Severity} 逐字对应。 */
export type Severity = 'HIGH' | 'MEDIUM' | 'LOW';

/** 这次价格是**谁动的**。与后端 {@code PriceChangeSource} 逐字对应。 */
export type PriceChangeSource =
  | 'AUTOMATIC_PLATFORM'
  | 'MANUAL_INTERNAL'
  | 'PLATFORM_FEEDBACK'
  | 'UNKNOWN';

/** 观测从哪个通道进来。与后端 {@code ObservationSource} 逐字对应。 */
export type ObservationSource = 'PLATFORM_FEEDBACK' | 'PRICE_LIST' | 'MANUAL_ATTESTATION';

/** 被判异常的那个价是哪一个。与后端 {@code SubjectKind} 逐字对应。 */
export type SubjectKind = 'PLATFORM_PRICE' | 'INTERNAL_PRICE';

// ================================================================== 能力声明

/** 一个输入维度的能力声明。 */
export interface InputCapability {
  code: string;
  label: string;
  source: string;
  available: boolean;
  ownerModule: string;
  impact: string;
  basis: string;
  note: string;
}

/** 一类异常的判定口径声明。 */
export interface AnomalyCapability {
  code: string;
  label: string;
  detectedBy: string;
  available: boolean;
  thresholdNote: string;
  basis: string;
  note: string;
}

/** 能力声明。页面顶部固定显示它。 */
export interface PriceAnomalyCapabilities {
  inputs: InputCapability[];
  anomalyTypes: AnomalyCapability[];
  rules: AnomalyRuleRow[];
  detectionNote: string;
  upstreamNote: string;
  downstreamNote: string;
  sourceNote: string;
  actionsNote: string;
}

// ================================================================== 规则

/** 一条判定规则。 */
export interface AnomalyRuleRow {
  id: string | null;
  anomalyType: string;
  anomalyTypeLabel: string;
  name: string;
  enabled: boolean;
  ratioThreshold: string | null;
  minDurationDays: number | null;
  severity: string;
  note: string;
  /** 该类型是否被租户显式覆盖过（false = 用服务层内置默认值）。 */
  overridden: boolean;
  updatedAt: string | null;
}

/** 保存规则的入参。 */
export interface AnomalyRuleInput {
  anomalyType: string;
  name: string;
  enabled: boolean;
  /** 阈值可空 = 用内置默认值；显式给 0 是合法的。 */
  ratioThreshold: string | null;
  minDurationDays: number | null;
  severity: string;
  note: string;
}

// ================================================================== 观测

/** 一行 = 一个 SKU 在一个站点上的一次价格观测。 */
export interface ObservationRow {
  id: string;
  skuId: string;
  skuCode: string;
  skuName: string;
  platformCode: string;
  siteCode: string;
  currency: string;
  internalPrice: string | null;
  platformPrice: string | null;
  priceChangeSource: string;
  priceChangeSourceLabel: string;
  source: string;
  sourceLabel: string;
  comparisonCurrency: string | null;
  fxRate: string | null;
  fxRateDate: string | null;
  fxRateSource: string | null;
  observedAt: string;
  note: string;
}

/** 观测台账分页结果。 */
export interface ObservationListPage {
  items: ObservationRow[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 登记观测的入参。 */
export interface ObservationInput {
  skuId: string;
  platformCode: string;
  siteCode: string;
  currency: string;
  internalPrice: string | null;
  platformPrice: string | null;
  priceChangeSource: string;
  source: string;
  comparisonCurrency: string | null;
  fxRate: string | null;
  fxRateDate: string | null;
  fxRateSource: string | null;
  observedAt: string;
  note: string;
}

/** 登记结果。 */
export interface ObservationSaved {
  id: string;
  skuId: string;
  platformCode: string;
  siteCode: string;
  note: string;
}

// ================================================================== 事件

/** 异常事件一行。 */
export interface EventRow {
  id: string;
  anomalyType: string;
  anomalyTypeLabel: string;
  skuId: string;
  skuCode: string;
  skuName: string;
  platformCode: string;
  siteCode: string;
  relatedPlatformCode: string;
  relatedSiteCode: string;
  currency: string;
  subjectKind: string;
  subjectPrice: string | null;
  referencePrice: string | null;
  gapAmount: string | null;
  gapRatio: string | null;
  costLine: string | null;
  costCurrency: string;
  costComplete: boolean;
  costSnapshotAt: string | null;
  firstSeenAt: string;
  lastSeenAt: string;
  durationDays: number;
  severity: string;
  severityLabel: string;
  status: string;
  statusLabel: string;
  ruleId: string | null;
  fingerprint: string;
  /** 判定证据（后端给的是 JSON 文本）。 */
  evidence: string;
  updatedAt: string;
}

/** 异常清单分页结果。 */
export interface EventListPage {
  items: EventRow[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 异常事件详情。 */
export interface EventDetail {
  event: EventRow;
  notifications: NotificationRow[];
  observations: ObservationRow[];
  basis: string;
  note: string;
}

/** 事件状态变更结果。 */
export interface EventStatusSaved {
  id: string;
  status: string;
  statusLabel: string;
  updatedAt: string;
}

// ================================================================== 通知

/** 预警通知一行。 */
export interface NotificationRow {
  id: string;
  eventId: string;
  anomalyType: string;
  anomalyTypeLabel: string;
  severity: string;
  severityLabel: string;
  title: string;
  body: string;
  channel: string;
  recipientScope: string;
  /** 下游 1 工作台预警未交付 ⇒ 恒为 '1.3.1.1'。 */
  ownerModule: string;
  delivered: boolean;
  deliveredAt: string | null;
  createdAt: string;
}

/** 通知清单分页结果。 */
export interface NotificationListPage {
  items: NotificationRow[];
  total: number;
  page: number;
  size: number;
  note: string;
}

// ================================================================== 检测

/** 一个**不可比**的站点对（跨币种且没有汇率）。 */
export interface UncomparablePair {
  skuId: string;
  skuCode: string | null;
  platformCode: string;
  siteCode: string;
  currency: string;
  relatedPlatformCode: string;
  relatedSiteCode: string;
  relatedCurrency: string;
  reason: string;
}

/** 一次检测的逐类小结。 */
export interface DetectionTypeSummary {
  anomalyType: string;
  anomalyTypeLabel: string;
  evaluated: number;
  detected: number;
  note: string;
}

/** 一次检测的结果。 */
export interface DetectionRun {
  windowFrom: string;
  windowTo: string;
  observationsScanned: number;
  eventsOpened: number;
  eventsUpdated: number;
  eventsResolved: number;
  notificationsRaised: number;
  typeSummary: DetectionTypeSummary[];
  uncomparablePairs: UncomparablePair[];
  truncated: boolean;
  note: string;
}

// ================================================================== 筛选

export interface AnomalyFilters {
  keyword: string;
  anomalyType: string;
  status: string;
  severity: string;
  siteCode: string;
  windowFrom: string;
  windowTo: string;
  page: number;
  size: number;
}

export const EMPTY_ANOMALY_FILTERS: AnomalyFilters = {
  keyword: '',
  anomalyType: '',
  status: '',
  severity: '',
  siteCode: '',
  windowFrom: '',
  windowTo: '',
  page: 0,
  size: 20,
};

export interface ObservationFilters {
  keyword: string;
  platformCode: string;
  siteCode: string;
  currency: string;
  priceChangeSource: string;
  page: number;
  size: number;
}

export const EMPTY_OBSERVATION_FILTERS: ObservationFilters = {
  keyword: '',
  platformCode: '',
  siteCode: '',
  currency: '',
  priceChangeSource: '',
  page: 0,
  size: 20,
};

// ================================================================== 文案

/** 异常类型 → 中文名。后端已给 label，这里只做兜底。 */
export function anomalyTypeLabel(value: string | null | undefined): string {
  switch (value) {
    case 'PLATFORM_INTERNAL_DIVERGENCE':
      return '平台价与内部价长期不一致';
    case 'BELOW_COST':
      return '售价低于成本线';
    case 'PLATFORM_AUTO_DISCOUNT':
      return '平台自动降价';
    case 'CROSS_SITE_INVERSION':
      return '多站点价格倒挂';
    default:
      return value || '未知异常';
  }
}

/** 变动来源 → 人话标签。 */
export function changeSourceLabel(value: string | null | undefined): string {
  switch (value) {
    case 'AUTOMATIC_PLATFORM':
      return '平台自动降价';
    case 'MANUAL_INTERNAL':
      return '内部调价';
    case 'PLATFORM_FEEDBACK':
      return '平台回流';
    case 'UNKNOWN':
      return '来源未标注';
    default:
      return value || '来源未标注';
  }
}

/**
 * 变动来源的色。
 *
 * <p>🔴 「平台自动降价」是**要查的事**（红色），「内部调价」是**我们自己做的**（中性）——
 * 把两者都标成警告色，就看不出到底是谁在动价了。
 */
export function changeSourceTone(
  value: string | null | undefined,
): 'danger' | 'warning' | 'info' | 'neutral' {
  switch (value) {
    case 'AUTOMATIC_PLATFORM':
      return 'danger';
    case 'MANUAL_INTERNAL':
      return 'info';
    case 'PLATFORM_FEEDBACK':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 异常类型 → 语气色。 */
export function anomalyTone(
  value: string | null | undefined,
): 'danger' | 'warning' | 'info' | 'neutral' {
  switch (value) {
    case 'BELOW_COST':
    case 'CROSS_SITE_INVERSION':
      return 'danger';
    case 'PLATFORM_INTERNAL_DIVERGENCE':
      return 'warning';
    case 'PLATFORM_AUTO_DISCOUNT':
      return 'info';
    default:
      return 'neutral';
  }
}

/** 事件状态 → 语气色。已消解用中性色，别让历史事件一直占着红色的位置。 */
export function statusTone(value: string | null | undefined): 'danger' | 'warning' | 'neutral' {
  switch (value) {
    case 'OPEN':
      return 'danger';
    case 'ACKNOWLEDGED':
      return 'warning';
    case 'RESOLVED':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 严重度 → 语气色。 */
export function severityTone(value: string | null | undefined): 'danger' | 'warning' | 'info' {
  switch (value) {
    case 'HIGH':
      return 'danger';
    case 'MEDIUM':
      return 'warning';
    case 'LOW':
      return 'info';
    default:
      return 'info';
  }
}

/**
 * 金额 → 展示串。
 *
 * <p>🔴 null 显示「未登记」而**不是 0.00**：
 * 后端把「没有这个价 / 读不到」表达成 {@code null}，渲染成 0.00 会让
 * 「售价低于成本线」看起来比实际更严重。
 */
export function moneyText(value: string | null | undefined, unavailable = '未登记'): string {
  if (value === null || value === undefined || value === '') return unavailable;
  return value;
}

/** 带币种的金额串。 */
export function moneyWithCurrency(
  value: string | null | undefined,
  currency: string | null | undefined,
  unavailable = '未登记',
): string {
  const text = moneyText(value, unavailable);
  if (text === unavailable) return text;
  return currency ? `${currency} ${text}` : text;
}

/** ISO 时刻 → 只到秒的串。 */
export function timeText(value: string | null | undefined, empty = '—'): string {
  if (!value) return empty;
  return value.replace('T', ' ').replace('Z', 'Z');
}

/** ISO 时刻 → 只到日的串。 */
export function dayText(value: string | null | undefined, empty = '—'): string {
  if (!value) return empty;
  return value.slice(0, 10);
}

/** 比例 → 百分比串（后端给的是 0.05 这种小数）。 */
export function ratioText(value: string | null | undefined, empty = '—'): string {
  if (value === null || value === undefined || value === '') return empty;
  const parsed = Number(value);
  if (Number.isNaN(parsed)) return value;
  return `${(parsed * 100).toFixed(2)}%`;
}

/**
 * 站点对的展示串：**低的那一边 vs 贵的那一边**。
 *
 * <p>🔴 蓝图加粗句「德国站定价低于美国站会引发跨区窜货」在界面上必须读得出方向，
 * 所以固定写成「A 低于 B」，而不是「A / B」或只给一个价差。
 */
export function sitePairText(event: {
  siteCode: string;
  relatedSiteCode: string;
}): string {
  if (!event.relatedSiteCode) return event.siteCode;
  return `${event.siteCode} 低于 ${event.relatedSiteCode}`;
}

/**
 * 持续天数的展示。
 *
 * <p>🔴 「持续了多久」只对**状态型**异常有意义（①②④）；③ 平台自动降价是发生过的事，
 * 它的持续天数恒为 0 —— 那不是「持续了 0 天」，而是「这不是一个持续状态」。
 */
export function durationText(event: {
  anomalyType: string;
  durationDays: number;
}): string {
  if (event.anomalyType === 'PLATFORM_AUTO_DISCOUNT') return '发生过的事（不按持续计）';
  if (event.durationDays <= 0) return '不到 1 天';
  return `持续 ${event.durationDays} 天`;
}

/**
 * 成本线的展示。
 *
 * <p>🔴 成本快照不完整时，那个数只是「已知部分合计」—— 必须写明，
 * 否则用户会拿一个偏低的成本去断言「亏损」。
 */
export function costLineText(event: {
  costLine: string | null;
  costCurrency: string;
  costComplete: boolean;
}): string {
  if (event.costLine === null || event.costLine === '') return '未维护成本构成（不参与判定）';
  const base = `${event.costCurrency} ${event.costLine}`;
  return event.costComplete ? base : `${base}（🔴 已知部分合计，成本口径不完整）`;
}

/** 判定依据（后端给的 JSON 文本）→ 可读串。 */
export function evidenceText(evidence: string | null | undefined): string {
  if (!evidence) return '';
  try {
    const parsed = JSON.parse(evidence) as Record<string, unknown>;
    const parts: string[] = [];
    for (const [key, value] of Object.entries(parsed)) {
      if (value === null || value === undefined) continue;
      parts.push(`${key} = ${String(value)}`);
    }
    return parts.join('｜');
  } catch {
    // 后端给的不是 JSON 文本时原样返回，不要吞掉。
    return evidence;
  }
}

/** 站点 → 站点码（用于筛选下拉的占位）。 */
export const COMMON_SITES: readonly { code: string; label: string }[] = [
  { code: 'US', label: '美国站' },
  { code: 'DE', label: '德国站' },
  { code: 'FR', label: '法国站' },
  { code: 'UK', label: '英国站' },
  { code: 'JP', label: '日本站' },
  { code: 'CA', label: '加拿大站' },
];

// ================================================================== 本地校验

/** 登记观测的本地校验。前端这道校验是为了**早点告诉人**，不是安全边界。 */
export function validateObservation(input: ObservationInput): string[] {
  const problems: string[] = [];
  if (!input.skuId.trim()) problems.push('必须选一个 SKU：观测是挂在 SKU 上的');
  if (!input.platformCode.trim()) problems.push('平台码必填');
  if (!input.siteCode.trim()) problems.push('站点码必填');
  if (!input.currency.trim()) problems.push('币种必填');
  else if (!/^[A-Za-z]{3}$/.test(input.currency.trim()))
    problems.push(`币种请用 ISO 4217 三位字母码：${input.currency}`);
  const internal = input.internalPrice?.trim() ?? '';
  const platform = input.platformPrice?.trim() ?? '';
  if (!internal && !platform) {
    problems.push('内部价与平台价至少要给一个：两个都没有的观测没有任何信息量');
  }
  for (const [label, raw] of [
    ['内部价', internal],
    ['平台价', platform],
  ] as const) {
    if (!raw) continue;
    if (!/^-?\d+(\.\d+)?$/.test(raw)) problems.push(`${label}请填数字：${raw}`);
    else if (Number(raw) < 0) problems.push(`${label}不能为负`);
  }
  const fx = input.fxRate?.trim() ?? '';
  const cmpCurrency = input.comparisonCurrency?.trim() ?? '';
  if (fx && !cmpCurrency) {
    problems.push('给了汇率就必须同时给比价币种：只给一个，比价口径就是残缺的');
  }
  if (cmpCurrency && !fx) {
    problems.push('给了比价币种就必须同时给汇率');
  }
  if (fx && !/^-?\d+(\.\d+)?$/.test(fx)) problems.push(`汇率请填数字：${fx}`);
  else if (fx && Number(fx) <= 0) problems.push('汇率必须大于 0');
  if (fx && !input.fxRateDate?.trim()) {
    problems.push('给了汇率就必须说清是哪天的汇率 —— 否则事后无法复算');
  }
  if (fx && !input.fxRateSource?.trim()) {
    problems.push('给了汇率就必须说清是哪来的汇率');
  }
  if (!input.observedAt.trim()) problems.push('观测时刻必填：这一行说的是「这一刻」这个价');
  return problems;
}

/** 保存规则的本地校验。 */
export function validateRule(input: AnomalyRuleInput): string[] {
  const problems: string[] = [];
  if (!input.anomalyType) problems.push('必须选一种异常类型');
  if (!input.name.trim()) problems.push('规则名必填：半年后没人知道这条规则是干什么的');
  const ratio = input.ratioThreshold?.trim() ?? '';
  if (ratio) {
    if (!/^-?\d+(\.\d+)?$/.test(ratio)) problems.push(`价差比例阈值请填数字：${ratio}`);
    else if (Number(ratio) < 0 || Number(ratio) >= 1) {
      problems.push('价差比例阈值必须在 [0,1) 之间：1.0 意味着永远不命中');
    }
  }
  const days = input.minDurationDays === null ? '' : String(input.minDurationDays);
  if (days && !/^\d+$/.test(days)) problems.push(`持续天数请填整数：${days}`);
  else if (days && Number(days) < 0) problems.push('持续天数不能为负');
  if (!input.severity) problems.push('必须选一个严重度');
  return problems;
}

// ================================================================== 端点

export function fetchPriceAnomalyCapabilities(): Promise<PriceAnomalyCapabilities> {
  return api<PriceAnomalyCapabilities>('catalog/price-anomaly-alerts/capabilities');
}

export function fetchPriceAnomalyRules(): Promise<AnomalyRuleRow[]> {
  return api<AnomalyRuleRow[]>('catalog/price-anomaly-alerts/rules');
}

export function savePriceAnomalyRule(input: AnomalyRuleInput): Promise<AnomalyRuleRow> {
  return api<AnomalyRuleRow>('catalog/price-anomaly-alerts/rules', 'POST', input);
}

export function fetchPriceAnomalyEvents(filters: AnomalyFilters): Promise<EventListPage> {
  const query = new URLSearchParams();
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.anomalyType) query.set('anomalyType', filters.anomalyType);
  if (filters.status) query.set('status', filters.status);
  if (filters.severity) query.set('severity', filters.severity);
  if (filters.siteCode) query.set('siteCode', filters.siteCode);
  if (filters.windowFrom) query.set('windowFrom', filters.windowFrom);
  if (filters.windowTo) query.set('windowTo', filters.windowTo);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<EventListPage>(`catalog/price-anomaly-alerts/events?${query.toString()}`);
}

export function fetchPriceAnomalyEvent(eventId: string): Promise<EventDetail> {
  return api<EventDetail>(`catalog/price-anomaly-alerts/events/${eventId}`);
}

export function acknowledgePriceAnomaly(eventId: string): Promise<EventStatusSaved> {
  return api<EventStatusSaved>(`catalog/price-anomaly-alerts/events/${eventId}/acknowledge`, 'POST');
}

export function resolvePriceAnomaly(eventId: string): Promise<EventStatusSaved> {
  return api<EventStatusSaved>(`catalog/price-anomaly-alerts/events/${eventId}/resolve`, 'POST');
}

export function fetchPriceAnomalyObservations(
  filters: ObservationFilters,
): Promise<ObservationListPage> {
  const query = new URLSearchParams();
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.siteCode) query.set('siteCode', filters.siteCode);
  if (filters.currency) query.set('currency', filters.currency);
  if (filters.priceChangeSource) query.set('priceChangeSource', filters.priceChangeSource);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<ObservationListPage>(`catalog/price-anomaly-alerts/observations?${query.toString()}`);
}

export function registerPriceObservation(input: ObservationInput): Promise<ObservationSaved> {
  return api<ObservationSaved>('catalog/price-anomaly-alerts/observations', 'POST', input);
}

export function fetchPriceAnomalyNotifications(
  filters: AnomalyFilters,
): Promise<NotificationListPage> {
  const query = new URLSearchParams();
  if (filters.anomalyType) query.set('anomalyType', filters.anomalyType);
  if (filters.severity) query.set('severity', filters.severity);
  if (filters.status) query.set('status', filters.status);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<NotificationListPage>(`catalog/price-anomaly-alerts/notifications?${query.toString()}`);
}

export function runPriceAnomalyDetection(
  windowFrom: string | null,
  windowTo: string | null,
): Promise<DetectionRun> {
  const query = new URLSearchParams();
  if (windowFrom) query.set('windowFrom', windowFrom);
  if (windowTo) query.set('windowTo', windowTo);
  const suffix = query.toString();
  return api<DetectionRun>(
    `catalog/price-anomaly-alerts/detect${suffix ? `?${suffix}` : ''}`,
    'POST',
  );
}
