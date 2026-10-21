import { api } from '../inventory/api';

/**
 * 14.2.2.2 自动规则 前端模型。
 *
 * <p>蓝图：「自动化规则：按条件自动调整预算/竞价/启停（ACOS 超标降价、ROAS 达标加预算、消耗完毕暂停、花费无转化
 * 停投），支持规则优先级、单次调整上限与总上限、试运行模式。输入：规则定义；实时广告表现；上下限保护参数。输出：规则命中
 * 记录 + 调整执行记录 + 效果回溯。关系：上游 = 14.3 报表；下游 = 平台广告 API。<b>自动规则必须有上限与试运行</b>——
 * 没有上限的自动调价一次异常就能烧掉大量预算。」
 *
 * 🔴 这个页面上最容易做丢的三件事，这里都挡住了：
 *
 * 1. **上限是必填项，不是可选项**。表单里单次上限 / 总上限没有「留空 = 不限」这一档：
 *    留空直接被前端拦下（后端还会再拒一次、DB 还有 CHECK 兜底）。页面上**不提供**「无限额」选项 ——
 *    提供一个「不限」的勾选框，等于在界面上重新开了一次「一次异常烧掉全部预算」的口子。
 *
 * 2. **试运行与真实执行在界面上必须一眼分得开**。规则行上有「试运行中 / 真实执行」标记，
 *    执行记录上 `outcome` 直接显示成「试运行（未调整）/ 已执行 / 被闸门拒绝」，
 *    且**被拒绝的调整也列出来**并写清是被哪一道闸拦下来的 —— 静默截断会让运营以为规则按预期在工作。
 *
 * 3. **「待下发」不是「已生效」**。下游 = 平台广告 API，本模块**不实现**平台侧真实下发，
 *    所以待下发动作一律显示「待下发」，并显式标注「平台侧真实下发通道未交付」。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * ACOS / ROAS 与调整幅度全部是服务端 `BigDecimal` 的事，前端算一遍就会引入二进制浮点误差，
 * 而且会让「界面上的 ACOS」和「执行记录里的 ACOS」对不上。
 */

/** 金额与数量一律 number（JSON number），只原样展示。 */
export type AdMoney = number | null;

/** 🔴 null = 不可比（分母为 0 或销售额为空），与 `0`（可比但真的是零）含义相反。 */
export type AdRatio = number | null;

export interface AdRuleCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface AdRuleCapabilities {
  dimensions: AdRuleCapabilityDimension[];
  actionTypes: string[];
  adjustmentModes: string[];
  metrics: string[];
  operators: string[];
  targetTypes: string[];
  runModes: string[];
  executionOutcomes: string[];
  effectVerdicts: string[];
  guardNote: string;
  dryRunNote: string;
  priorityNote: string;
  dispatchNote: string;
  upstreamNote: string;
}

export interface AdRuleRow {
  id: string;
  ruleCode: string;
  ruleName: string;
  platformId: string;
  platformCode: string;
  shopCode: string;
  targetType: string;
  /** 🔴 外部活动 id（外部引用，不假设 14.1.1.1 活动列表存在）。 */
  externalCampaignId: string;
  /** 🔴 外部关键词 id。 */
  externalKeywordId: string;
  actionType: string;
  adjustmentMode: string;
  adjustmentValue: AdMoney;
  /** 🔴 单次调整金额上限（必填，> 0）。 */
  singleAdjustCapAmount: AdMoney;
  /** 🔴 单次调整比例上限（0 = 不启用）。 */
  singleAdjustCapPercent: AdMoney;
  /** 🔴 窗口内累计金额上限（必填，> 0）。 */
  totalCapAmount: AdMoney;
  /** 🔴 窗口内累计次数上限（必填，> 0）。 */
  totalCapCount: number;
  /** 🔴 累计窗口长度（小时，必填，> 0）。 */
  totalCapWindowHours: number;
  minBudget: AdMoney;
  maxBudget: AdMoney;
  minBid: AdMoney;
  maxBid: AdMoney;
  /** 🔴 优先级：数字小 = 优先高。 */
  priority: number;
  /** 🔴 试运行开关。 */
  dryRun: boolean;
  evaluationWindowHours: number;
  status: string;
  conditionCount: number;
  /** 🔴 该目标上最新指标的数据截至时间；为空 = **没有数据**。 */
  latestMetricsAsOf: string | null;
  /** 🔴 指标是否已过期 —— 区分「没有数据」与「没同步到」。 */
  metricsStale: boolean;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AdRulePage {
  items: AdRuleRow[];
  total: number;
  page: number;
  size: number;
  dryRunCount: number;
  activeCount: number;
}

export interface AdRuleConditionRow {
  id: string;
  ruleId: string;
  metric: string;
  operator: string;
  /** 🔴 比率 / 计数阈值（ACOS / ROAS / CONVERSIONS / CLICKS / IMPRESSIONS）。 */
  thresholdValue: AdMoney;
  /** 🔴 金额阈值（SPEND / REVENUE）。 */
  thresholdAmount: AdMoney;
  note: string;
  position: number;
  createdAt: string;
}

export interface AdRuleLastRun {
  runId: string;
  runMode: string;
  status: string;
  hitCount: number;
  selectedCount: number;
  appliedCount: number;
  dryRunCount: number;
  rejectedCount: number;
  startedAt: string;
}

export interface AdRuleDetail {
  rule: AdRuleRow;
  conditions: AdRuleConditionRow[];
  lastRun: AdRuleLastRun | null;
  note: string;
}

export interface AdRuleMetricRow {
  id: string;
  platformId: string;
  platformCode: string;
  shopCode: string;
  metricScope: string;
  externalCampaignId: string;
  externalKeywordId: string;
  currency: string;
  deliveryStatus: string;
  currentBudget: AdMoney;
  currentBid: AdMoney;
  impressions: AdMoney;
  clicks: AdMoney;
  conversions: AdMoney;
  spend: AdMoney;
  attributedRevenue: AdMoney;
  /** 🔴 ACOS = 花费 / 归因销售额；null = 不可比。 */
  acos: AdRatio;
  /** 🔴 ROAS = 归因销售额 / 花费；null = 不可比。 */
  roas: AdRatio;
  /** 🔴 数据截至时间（必填）。 */
  metricsAsOf: string;
  windowStart: string;
  windowEnd: string;
  sourceRef: string;
  note: string;
  createdAt: string;
  updatedAt: string;
}

export interface AdRuleMetricPage {
  items: AdRuleMetricRow[];
  total: number;
  page: number;
  size: number;
  latestMetricsAsOf: string | null;
}

export interface AdRuleRunRow {
  id: string;
  runMode: string;
  triggerSource: string;
  status: string;
  evaluatedRules: number;
  hitCount: number;
  selectedCount: number;
  executionCount: number;
  appliedCount: number;
  dryRunCount: number;
  rejectedCount: number;
  startedAt: string;
  finishedAt: string | null;
  note: string;
}

export interface AdRuleRunPage {
  items: AdRuleRunRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdRuleHitRow {
  id: string;
  runId: string;
  ruleId: string;
  ruleName: string;
  metricId: string;
  platformCode: string;
  shopCode: string;
  externalCampaignId: string;
  externalKeywordId: string;
  matched: boolean;
  /** 🔴 是否被选中执行（同目标多条命中时按优先级取一条）。 */
  selected: boolean;
  selectionNote: string;
  hitReason: string;
  priority: number;
  spendSnapshot: AdMoney;
  revenueSnapshot: AdMoney;
  conversionsSnapshot: AdMoney;
  clicksSnapshot: AdMoney;
  impressionsSnapshot: AdMoney;
  acosSnapshot: AdRatio;
  roasSnapshot: AdRatio;
  metricsAsOf: string;
  evaluatedAt: string;
}

export interface AdRuleHitPage {
  items: AdRuleHitRow[];
  total: number;
  page: number;
  size: number;
  matchedCount: number;
  selectedCount: number;
}

export interface AdRuleExecutionRow {
  id: string;
  runId: string;
  ruleId: string;
  ruleName: string;
  hitId: string;
  metricId: string;
  actionType: string;
  adjustmentMode: string;
  requestedValue: AdMoney;
  /** 🔴 DRY_RUN 试运行（未调整）/ APPLIED 已执行 / REJECTED 被闸门拒绝。 */
  outcome: string;
  rejectReason: string;
  rejectDetail: string;
  beforeValue: AdMoney;
  /** 🔴 试运行里是「本来会变成多少」。 */
  afterValue: AdMoney;
  /** 🔴 实际调整幅度；被拒绝时为空（**不是**被截断后的值）。 */
  appliedDelta: AdMoney;
  beforeStatus: string | null;
  afterStatus: string | null;
  capCheck: string;
  boundCheck: string;
  singleCapAmount: AdMoney;
  singleCapPercent: AdMoney;
  totalCapAmount: AdMoney;
  totalCapCount: number;
  totalCapWindowHours: number;
  windowUsedAmount: AdMoney;
  windowUsedCount: number;
  prioritySelectionNote: string;
  executedAt: string;
}

export interface AdRuleExecutionPage {
  items: AdRuleExecutionRow[];
  total: number;
  page: number;
  size: number;
  appliedCount: number;
  dryRunCount: number;
  rejectedCount: number;
}

export interface AdRuleDispatchRow {
  id: string;
  executionId: string;
  ruleId: string;
  ruleName: string;
  platformId: string;
  platformCode: string;
  shopCode: string;
  externalCampaignId: string;
  externalKeywordId: string;
  actionType: string;
  actionPayload: Record<string, unknown>;
  /** 🔴 PENDING 待下发 / DISPATCHED 已下发 / FAILED 失败 / CANCELLED 已撤销。 */
  dispatchStatus: string;
  dispatchedAt: string | null;
  dispatchRef: string;
  note: string;
  createdAt: string;
  updatedAt: string;
}

export interface AdRuleDispatchPage {
  items: AdRuleDispatchRow[];
  total: number;
  page: number;
  size: number;
  pendingCount: number;
}

export interface AdRuleEffectRow {
  id: string;
  executionId: string;
  ruleId: string;
  ruleName: string;
  metricId: string;
  beforeAcos: AdRatio;
  afterAcos: AdRatio;
  acosDelta: AdRatio;
  beforeRoas: AdRatio;
  afterRoas: AdRatio;
  roasDelta: AdRatio;
  beforeSpend: AdMoney;
  afterSpend: AdMoney;
  beforeRevenue: AdMoney;
  afterRevenue: AdMoney;
  revenueDelta: AdMoney;
  /** 🔴 IMPROVED / WORSENED / UNCHANGED / INSUFFICIENT_DATA。 */
  verdict: string;
  verdictNote: string;
  observedAt: string;
}

export interface AdRuleEffectPage {
  items: AdRuleEffectRow[];
  total: number;
  page: number;
  size: number;
  improvedCount: number;
  worsenedCount: number;
  insufficientCount: number;
}

export interface AdRuleRunDetail {
  run: AdRuleRunRow;
  hits: AdRuleHitRow[];
  executions: AdRuleExecutionRow[];
  note: string;
}

// ==================================================================
// 表单
// ==================================================================

export interface RuleConditionInput {
  metric: string;
  operator: string;
  /** 比率 / 计数阈值（ACOS / ROAS / CONVERSIONS / CLICKS / IMPRESSIONS）。 */
  thresholdValue: string;
  /** 金额阈值（SPEND / REVENUE）。 */
  thresholdAmount: string;
  note: string;
}

export const emptyRuleConditionInput: RuleConditionInput = {
  metric: 'ACOS',
  operator: 'GT',
  thresholdValue: '',
  thresholdAmount: '',
  note: '',
};

export interface RuleInput {
  id: string;
  ruleCode: string;
  ruleName: string;
  platformId: string;
  shopCode: string;
  targetType: string;
  externalCampaignId: string;
  externalKeywordId: string;
  actionType: string;
  adjustmentMode: string;
  adjustmentValue: string;
  /** 🔴 单次调整金额上限（必填，> 0）。 */
  singleAdjustCapAmount: string;
  /** 🔴 单次调整比例上限（0 = 不启用）。 */
  singleAdjustCapPercent: string;
  /** 🔴 窗口内累计金额上限（必填，> 0）。 */
  totalCapAmount: string;
  /** 🔴 窗口内累计次数上限（必填，> 0）。 */
  totalCapCount: string;
  /** 🔴 累计窗口长度（小时，必填，> 0）。 */
  totalCapWindowHours: string;
  minBudget: string;
  maxBudget: string;
  minBid: string;
  maxBid: string;
  priority: string;
  /** 🔴 试运行开关。 */
  dryRun: boolean;
  evaluationWindowHours: string;
  status: string;
  note: string;
  conditions: RuleConditionInput[];
}

export const emptyRuleInput: RuleInput = {
  id: '',
  ruleCode: '',
  ruleName: '',
  platformId: '',
  shopCode: '',
  targetType: 'CAMPAIGN',
  externalCampaignId: '',
  externalKeywordId: '',
  actionType: 'DECREASE_BID',
  adjustmentMode: 'ABSOLUTE',
  adjustmentValue: '',
  singleAdjustCapAmount: '',
  singleAdjustCapPercent: '0',
  totalCapAmount: '',
  totalCapCount: '3',
  totalCapWindowHours: '24',
  minBudget: '',
  maxBudget: '',
  minBid: '',
  maxBid: '',
  priority: '100',
  dryRun: true,
  evaluationWindowHours: '24',
  status: 'ACTIVE',
  note: '',
  conditions: [{ ...emptyRuleConditionInput }],
};

export interface MetricInput {
  id: string;
  platformId: string;
  shopCode: string;
  metricScope: string;
  externalCampaignId: string;
  externalKeywordId: string;
  currency: string;
  deliveryStatus: string;
  currentBudget: string;
  currentBid: string;
  impressions: string;
  clicks: string;
  conversions: string;
  spend: string;
  attributedRevenue: string;
  /** 🔴 数据截至时间（必填）。 */
  metricsAsOf: string;
  windowStart: string;
  windowEnd: string;
  sourceRef: string;
  note: string;
}

export const emptyMetricInput: MetricInput = {
  id: '',
  platformId: '',
  shopCode: '',
  metricScope: 'CAMPAIGN',
  externalCampaignId: '',
  externalKeywordId: '',
  currency: 'USD',
  deliveryStatus: 'ENABLED',
  currentBudget: '',
  currentBid: '',
  impressions: '0',
  clicks: '0',
  conversions: '0',
  spend: '0',
  attributedRevenue: '',
  metricsAsOf: '',
  windowStart: '',
  windowEnd: '',
  sourceRef: '',
  note: '',
};

export interface AdValidation {
  ok: boolean;
  errors: string[];
}

// ==================================================================
// 接口调用
// ==================================================================

export function fetchAdRuleCapabilities(): Promise<AdRuleCapabilities> {
  return api<AdRuleCapabilities>('channel/ad-rules/capabilities');
}

export function fetchRules(params: {
  status?: string;
  platformId?: string;
  shopCode?: string;
  targetType?: string;
  dryRun?: boolean;
  page?: number;
  size?: number;
}): Promise<AdRulePage> {
  const query = new URLSearchParams();
  if (params.status) query.set('status', params.status);
  if (params.platformId) query.set('platformId', params.platformId);
  if (params.shopCode) query.set('shopCode', params.shopCode);
  if (params.targetType) query.set('targetType', params.targetType);
  if (params.dryRun !== undefined) query.set('dryRun', String(params.dryRun));
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<AdRulePage>(`channel/ad-rules/rules?${query.toString()}`);
}

export function fetchRule(id: string): Promise<AdRuleDetail> {
  return api<AdRuleDetail>(`channel/ad-rules/rules/${id}`);
}

export function fetchRuleConditions(id: string): Promise<AdRuleConditionRow[]> {
  return api<AdRuleConditionRow[]>(`channel/ad-rules/rules/${id}/conditions`);
}

export function setRuleStatus(id: string, status: string): Promise<AdRuleRow> {
  return api<AdRuleRow>(`channel/ad-rules/rules/${id}/status?status=${encodeURIComponent(status)}`, 'POST');
}

/**
 * 登记 / 改一条规则。
 *
 * 🔴 上限是**必填**：这里没有「留空 = 不限」这条路 —— 页面替用户填一个假上限，
 * 等于把「没有上限的自动调价」这个风险从后端挪到了前端。
 */
export function saveRule(input: RuleInput): Promise<AdRuleDetail> {
  const number = (value: string): number | undefined => {
    const trimmed = value.trim();
    return trimmed === '' ? undefined : Number(trimmed);
  };
  return api<AdRuleDetail>('channel/ad-rules/rules', 'POST', {
    id: input.id || undefined,
    ruleCode: input.ruleCode.trim(),
    ruleName: input.ruleName.trim(),
    platformId: input.platformId,
    shopCode: input.shopCode.trim(),
    targetType: input.targetType,
    externalCampaignId: input.externalCampaignId.trim(),
    externalKeywordId: input.externalKeywordId.trim(),
    actionType: input.actionType,
    adjustmentMode: input.adjustmentMode,
    adjustmentValue: number(input.adjustmentValue),
    singleAdjustCapAmount: number(input.singleAdjustCapAmount),
    singleAdjustCapPercent: number(input.singleAdjustCapPercent),
    totalCapAmount: number(input.totalCapAmount),
    totalCapCount: Number(input.totalCapCount || 0),
    totalCapWindowHours: Number(input.totalCapWindowHours || 0),
    minBudget: number(input.minBudget),
    maxBudget: number(input.maxBudget),
    minBid: number(input.minBid),
    maxBid: number(input.maxBid),
    priority: Number(input.priority || 0),
    dryRun: input.dryRun,
    evaluationWindowHours: Number(input.evaluationWindowHours || 24),
    status: input.status,
    note: input.note.trim(),
    conditions: input.conditions.map((condition, index) => ({
      metric: condition.metric,
      operator: condition.operator,
      thresholdValue: number(condition.thresholdValue),
      thresholdAmount: number(condition.thresholdAmount),
      note: condition.note.trim(),
      position: (index + 1) * 10,
    })),
  });
}

export function fetchMetrics(params: {
  platformId?: string;
  shopCode?: string;
  metricScope?: string;
  externalCampaignId?: string;
  page?: number;
  size?: number;
}): Promise<AdRuleMetricPage> {
  const query = new URLSearchParams();
  if (params.platformId) query.set('platformId', params.platformId);
  if (params.shopCode) query.set('shopCode', params.shopCode);
  if (params.metricScope) query.set('metricScope', params.metricScope);
  if (params.externalCampaignId) query.set('externalCampaignId', params.externalCampaignId);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<AdRuleMetricPage>(`channel/ad-rules/metrics?${query.toString()}`);
}

export function fetchMetric(id: string): Promise<AdRuleMetricRow> {
  return api<AdRuleMetricRow>(`channel/ad-rules/metrics/${id}`);
}

/** 登记一条广告表现指标（**本模块的显式写入口**）。 */
export function saveMetric(input: MetricInput): Promise<AdRuleMetricRow> {
  const number = (value: string): number | undefined => {
    const trimmed = value.trim();
    return trimmed === '' ? undefined : Number(trimmed);
  };
  return api<AdRuleMetricRow>('channel/ad-rules/metrics', 'POST', {
    id: input.id || undefined,
    platformId: input.platformId,
    shopCode: input.shopCode.trim(),
    metricScope: input.metricScope,
    externalCampaignId: input.externalCampaignId.trim(),
    externalKeywordId: input.externalKeywordId.trim(),
    currency: input.currency.trim().toUpperCase(),
    deliveryStatus: input.deliveryStatus,
    currentBudget: number(input.currentBudget),
    currentBid: number(input.currentBid),
    impressions: number(input.impressions),
    clicks: number(input.clicks),
    conversions: number(input.conversions),
    spend: number(input.spend),
    attributedRevenue: number(input.attributedRevenue),
    metricsAsOf: input.metricsAsOf ? new Date(input.metricsAsOf).toISOString() : undefined,
    windowStart: input.windowStart ? new Date(input.windowStart).toISOString() : undefined,
    windowEnd: input.windowEnd ? new Date(input.windowEnd).toISOString() : undefined,
    sourceRef: input.sourceRef.trim(),
    note: input.note.trim(),
  });
}

export function fetchRuns(params: {
  runMode?: string;
  page?: number;
  size?: number;
}): Promise<AdRuleRunPage> {
  const query = new URLSearchParams();
  if (params.runMode) query.set('runMode', params.runMode);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<AdRuleRunPage>(`channel/ad-rules/runs?${query.toString()}`);
}

export function fetchRun(id: string): Promise<AdRuleRunDetail> {
  return api<AdRuleRunDetail>(`channel/ad-rules/runs/${id}`);
}

/**
 * 发起一次评估。
 *
 * 🔴 `runMode` 是「本次运行的口径」，**不能**把一条开着试运行的规则推成真实执行 ——
 * 取更严的那个是服务端的规则，页面只是把选择送过去。
 */
export function runEvaluation(runMode: string, ruleId?: string, note?: string): Promise<AdRuleRunDetail> {
  return api<AdRuleRunDetail>('channel/ad-rules/runs', 'POST', {
    runMode,
    ruleId: ruleId || undefined,
    note: note ?? '',
  });
}

export function fetchHits(params: {
  ruleId?: string;
  runId?: string;
  matched?: boolean;
  selected?: boolean;
  page?: number;
  size?: number;
}): Promise<AdRuleHitPage> {
  const query = new URLSearchParams();
  if (params.ruleId) query.set('ruleId', params.ruleId);
  if (params.runId) query.set('runId', params.runId);
  if (params.matched !== undefined) query.set('matched', String(params.matched));
  if (params.selected !== undefined) query.set('selected', String(params.selected));
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<AdRuleHitPage>(`channel/ad-rules/hits?${query.toString()}`);
}

export function fetchExecutions(params: {
  ruleId?: string;
  runId?: string;
  outcome?: string;
  page?: number;
  size?: number;
}): Promise<AdRuleExecutionPage> {
  const query = new URLSearchParams();
  if (params.ruleId) query.set('ruleId', params.ruleId);
  if (params.runId) query.set('runId', params.runId);
  if (params.outcome) query.set('outcome', params.outcome);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<AdRuleExecutionPage>(`channel/ad-rules/executions?${query.toString()}`);
}

export function fetchExecution(id: string): Promise<AdRuleExecutionRow> {
  return api<AdRuleExecutionRow>(`channel/ad-rules/executions/${id}`);
}

export function fetchDispatches(params: {
  ruleId?: string;
  dispatchStatus?: string;
  page?: number;
  size?: number;
}): Promise<AdRuleDispatchPage> {
  const query = new URLSearchParams();
  if (params.ruleId) query.set('ruleId', params.ruleId);
  if (params.dispatchStatus) query.set('dispatchStatus', params.dispatchStatus);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<AdRuleDispatchPage>(`channel/ad-rules/dispatches?${query.toString()}`);
}

/** 撤销一条待下发动作（本模块**唯一**能改变待下发队列的动作）。 */
export function cancelDispatch(id: string, reason: string): Promise<AdRuleDispatchRow> {
  return api<AdRuleDispatchRow>(`channel/ad-rules/dispatches/${id}/cancel`, 'POST', { reason });
}

export function fetchEffects(params: {
  ruleId?: string;
  verdict?: string;
  page?: number;
  size?: number;
}): Promise<AdRuleEffectPage> {
  const query = new URLSearchParams();
  if (params.ruleId) query.set('ruleId', params.ruleId);
  if (params.verdict) query.set('verdict', params.verdict);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<AdRuleEffectPage>(`channel/ad-rules/effects?${query.toString()}`);
}

/** 回溯一次：把执行前后的指标变化算出来。 */
export function evaluateEffects(ruleId?: string): Promise<AdRuleEffectPage> {
  return api<AdRuleEffectPage>('channel/ad-rules/effects/evaluate', 'POST', {
    ruleId: ruleId || undefined,
    note: '',
  });
}

// ==================================================================
// 校验
// ==================================================================

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isUuid(value: string): boolean {
  return UUID_RE.test(value.trim());
}

/** 比率 / 计数口径的指标：阈值填 thresholdValue。 */
export const VALUE_METRICS = ['ACOS', 'ROAS', 'CONVERSIONS', 'CLICKS', 'IMPRESSIONS'];
/** 金额口径的指标：阈值填 thresholdAmount。 */
export const AMOUNT_METRICS = ['SPEND', 'REVENUE'];
/** 启停动作：没有调整幅度。 */
export const STATUS_ACTIONS = ['PAUSE', 'RESUME'];

function positive(text: string): boolean {
  const value = Number(text.trim());
  return text.trim() !== '' && Number.isFinite(value) && value > 0;
}

function nonNegative(text: string): boolean {
  const value = Number(text.trim());
  return text.trim() !== '' && Number.isFinite(value) && value >= 0;
}

/**
 * 规则的校验：与服务层的拒收条件一一对应。
 *
 * 🔴 三条**不可协商**的红线（与服务端、DB 层同口径）：
 * 上限必填且为正、启停动作没有幅度、条件的阈值列与指标口径必须对应。
 */
export function validateRule(input: RuleInput): AdValidation {
  const errors: string[] = [];

  if (!input.ruleCode.trim()) errors.push('规则编码必填（租户内唯一）');
  if (!input.ruleName.trim()) errors.push('规则名称必填');
  if (!isUuid(input.platformId)) errors.push('请选择平台');
  if (!input.shopCode.trim()) errors.push('店铺编码必填');

  if (!input.externalCampaignId.trim() && !input.externalKeywordId.trim()) {
    errors.push('🔴 请给出外部活动 id 或外部关键词 id —— 规则靠外部引用定位广告对象');
  }
  if (input.targetType === 'KEYWORD' && !input.externalKeywordId.trim()) {
    errors.push('🔴 关键词级规则必须给出外部关键词 id');
  }

  // 启停动作没有幅度；改价改预算必须有幅度
  if (STATUS_ACTIONS.includes(input.actionType)) {
    if (input.adjustmentValue.trim() !== '' && Number(input.adjustmentValue) !== 0) {
      errors.push('启停动作（PAUSE / RESUME）没有调整幅度，幅度必须为 0');
    }
  } else if (!positive(input.adjustmentValue)) {
    errors.push('调整幅度必须大于 0');
  }

  // 🔴 上限：必填且为正。页面**不提供**「不限」这一档。
  if (!positive(input.singleAdjustCapAmount)) {
    errors.push('🔴 单次调整金额上限必填且必须大于 0 —— 没有上限的自动调价一次异常就能烧掉大量预算');
  }
  if (input.singleAdjustCapPercent.trim() !== '' && !nonNegative(input.singleAdjustCapPercent)) {
    errors.push('单次调整比例上限不能为负（填 0 表示不启用比例上限）');
  }
  if (!positive(input.totalCapAmount)) {
    errors.push('🔴 总上限（窗口内累计金额）必填且必须大于 0');
  }
  if (!Number.isInteger(Number(input.totalCapCount)) || Number(input.totalCapCount) < 1) {
    errors.push('🔴 总上限（窗口内累计次数）必须至少为 1');
  }
  if (!Number.isInteger(Number(input.totalCapWindowHours)) || Number(input.totalCapWindowHours) < 1) {
    errors.push('🔴 累计窗口长度必须至少为 1 小时');
  }

  // 上下限必须自洽
  if (input.minBudget.trim() && input.maxBudget.trim()) {
    if (Number(input.maxBudget) < Number(input.minBudget)) {
      errors.push('预算上限不能低于预算下限');
    }
  }
  if (input.minBid.trim() && input.maxBid.trim()) {
    if (Number(input.maxBid) < Number(input.minBid)) {
      errors.push('出价上限不能低于出价下限');
    }
  }

  // 条件：至少一条，且阈值列与指标口径对应
  if (input.conditions.length === 0) {
    errors.push('🔴 规则至少要有一个条件 —— 没有条件的规则永远不会命中');
  }
  input.conditions.forEach((condition, index) => {
    const label = `条件 ${index + 1}`;
    if (!condition.metric) {
      errors.push(`${label}：请选择指标口径`);
      return;
    }
    if (VALUE_METRICS.includes(condition.metric)) {
      if (condition.thresholdAmount.trim() !== '') {
        errors.push(`${label}：${condition.metric} 是比率 / 计数口径，阈值不能填在「金额阈值」上`);
      }
      if (!condition.thresholdValue.trim()) {
        errors.push(`${label}：${condition.metric} 是比率 / 计数口径，阈值必须填在「比率 / 计数阈值」上`);
      }
    } else if (AMOUNT_METRICS.includes(condition.metric)) {
      if (condition.thresholdValue.trim() !== '') {
        errors.push(`${label}：${condition.metric} 是金额口径，阈值不能填在「比率 / 计数阈值」上`);
      }
      if (!condition.thresholdAmount.trim()) {
        errors.push(`${label}：${condition.metric} 是金额口径，阈值必须填在「金额阈值」上`);
      }
    } else {
      errors.push(`${label}：指标口径不合法`);
    }
  });

  return { ok: errors.length === 0, errors };
}

/** 指标的校验：数据截至时间必填，统计窗口必须自洽。 */
export function validateMetric(input: MetricInput): AdValidation {
  const errors: string[] = [];
  if (!isUuid(input.platformId)) errors.push('请选择平台');
  if (!input.shopCode.trim()) errors.push('店铺编码必填');
  if (!input.externalCampaignId.trim()) {
    errors.push('🔴 外部活动 id 必填 —— 指标靠外部引用定位对象');
  }
  if (input.metricScope === 'KEYWORD' && !input.externalKeywordId.trim()) {
    errors.push('🔴 关键词级指标必须给出外部关键词 id');
  }
  if (input.metricScope === 'CAMPAIGN' && input.externalKeywordId.trim()) {
    errors.push('🔴 活动级指标不得带外部关键词 id —— 层级串了会让规则作用到错的对象');
  }
  if (!input.metricsAsOf.trim()) {
    errors.push('🔴 数据截至时间必填 —— 没有它就分不清「没有数据」与「没同步到」');
  }
  if (!input.windowStart.trim() || !input.windowEnd.trim()) {
    errors.push('统计窗口的起止时间必填');
  } else if (new Date(input.windowEnd) <= new Date(input.windowStart)) {
    errors.push('统计窗口结束时间必须晚于开始时间');
  }
  if (input.spend.trim() && !nonNegative(input.spend)) errors.push('花费不能为负');
  if (input.attributedRevenue.trim() && !nonNegative(input.attributedRevenue)) {
    errors.push('归因销售额不能为负');
  }
  if (input.currentBudget.trim() && !nonNegative(input.currentBudget)) errors.push('当前预算不能为负');
  if (input.currentBid.trim() && !nonNegative(input.currentBid)) errors.push('当前出价不能为负');
  return { ok: errors.length === 0, errors };
}

// ==================================================================
// 展示辅助（只做展示，不做任何业务算术）
// ==================================================================

export function money(value: AdMoney): string {
  if (value === null || value === undefined) return '—';
  return value.toFixed(4);
}

/** 🔴 null = 不可比，与 0 含义相反 —— 界面上必须分开显示。 */
export function ratio(value: AdRatio): string {
  if (value === null || value === undefined) return '不可比';
  return value.toFixed(6);
}

export function count(value: AdMoney): string {
  if (value === null || value === undefined) return '—';
  return String(value);
}

export function actionLabel(action: string): string {
  switch (action) {
    case 'DECREASE_BID':
      return '降低出价';
    case 'INCREASE_BID':
      return '提高出价';
    case 'DECREASE_BUDGET':
      return '降低预算';
    case 'INCREASE_BUDGET':
      return '提高预算';
    case 'PAUSE':
      return '暂停投放';
    case 'RESUME':
      return '恢复投放';
    default:
      return action;
  }
}

export function outcomeLabel(outcome: string): string {
  switch (outcome) {
    case 'DRY_RUN':
      return '试运行（未调整）';
    case 'APPLIED':
      return '已执行';
    case 'REJECTED':
      return '被闸门拒绝';
    default:
      return outcome;
  }
}

export function rejectReasonLabel(reason: string): string {
  switch (reason) {
    case 'SINGLE_CAP_EXCEEDED':
      return '超过单次调整上限';
    case 'TOTAL_CAP_EXCEEDED':
      return '超过总上限（窗口内累计）';
    case 'BOUND_VIOLATION':
      return '越过上下限保护';
    case 'DUPLICATE_PENDING_DISPATCH':
      return '已有同类型待下发动作';
    case 'NOT_APPLICABLE':
      return '当前状态无需调整';
    default:
      return reason;
  }
}

export function verdictLabel(verdict: string): string {
  switch (verdict) {
    case 'IMPROVED':
      return '变好';
    case 'WORSENED':
      return '变差';
    case 'UNCHANGED':
      return '没变化';
    case 'INSUFFICIENT_DATA':
      return '数据不足（还不能下结论）';
    default:
      return verdict;
  }
}

export function metricLabel(metric: string): string {
  switch (metric) {
    case 'ACOS':
      return 'ACOS（花费 / 归因销售额）';
    case 'ROAS':
      return 'ROAS（归因销售额 / 花费）';
    case 'SPEND':
      return '花费';
    case 'REVENUE':
      return '归因销售额';
    case 'CONVERSIONS':
      return '转化数';
    case 'CLICKS':
      return '点击数';
    case 'IMPRESSIONS':
      return '展示数';
    default:
      return metric;
  }
}

export function operatorLabel(operator: string): string {
  switch (operator) {
    case 'GT':
      return '大于';
    case 'GTE':
      return '大于等于';
    case 'LT':
      return '小于';
    case 'LTE':
      return '小于等于';
    case 'EQ':
      return '等于';
    default:
      return operator;
  }
}

/**
 * 🔴 数据的「新鲜度」描述：**「没有数据」与「没同步到」是两件不同的事**。
 * 上游 14.3 报表未开发，指标靠显式登记进来；界面必须把这两者分开显示。
 */
export function freshnessText(row: {
  latestMetricsAsOf: string | null;
  metricsStale: boolean;
}): string {
  if (!row.latestMetricsAsOf) return '没有数据（指标还没登记进来）';
  if (row.metricsStale) return `没同步到（最新数据截至 ${row.latestMetricsAsOf}，已超出评估窗口）`;
  return `数据截至 ${row.latestMetricsAsOf}`;
}
