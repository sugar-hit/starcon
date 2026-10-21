import { api } from '../inventory/api';

/**
 * 14.2.2.1 预算分配 前端模型。
 *
 * <p>蓝图：「跨店铺/跨活动的预算分配与再平衡：按销售目标、利润、历史 ROAS 分配预算，识别预算受限的活动与闲置预算。
 * 输入：预算总额；分配规则；各活动历史表现。输出：分配方案 + 再平衡建议 + 执行任务。
 * 关系：上游 = 14.1.1.1、13.4 利润；下游 = 14.2.2.2。<strong>预算应向利润高的活动倾斜</strong>，按销售额平均分配是常见浪费。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **利润维度当前不可用，必须让人看见**。`capabilities.dimensions` 里 `profit_weight`
 *    是 `available=false`，界面上要常驻一条说明 —— 不能让人以为「按利润分配」已经生效。
 * 2. **闲置预算与预算受限是两种相反的事实**。`idleBudget` 与 `budgetConstrained` 分开展示，
 *    利用率 `null` 显示「不可比」而不是 0%。
 * 3. **没参与分配的活动要看得见**。`skipped` 逐条列出并写明原因，不静默丢掉。
 * 4. **「待执行」不是「已生效」**。任务状态与逐条结果分开显示，被护栏拒绝的条目照样列出来。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * 分配额、闲置预算、建议调整额都是服务端 `BigDecimal` 的事，前端算一遍就会引入二进制浮点误差，
 * 而且会让「界面上的分配额」和「账上的分配额」对不上。
 */

/** 金额与数量一律 number（JSON number），只原样展示。 */
export type AdMoney = number | null;

/** 🔴 null = 不可比（分母为 0 或没有数据），与 `0`（可比但真的是零）含义相反。 */
export type AdRatio = number | null;

export interface AdBudgetCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface AdBudgetAllocationGuardrails {
  maxSingleBudgetDelta: AdMoney;
  maxTotalBudgetDelta: AdMoney;
  maxCampaignBudget: AdMoney;
  maxCampaignsPerTask: number;
  note: string;
}

export interface AdBudgetCapabilities {
  dimensions: AdBudgetCapabilityDimension[];
  weightSources: string[];
  ruleStatuses: string[];
  planStatuses: string[];
  taskStatuses: string[];
  recommendations: string[];
  skipReasons: string[];
  guardrails: AdBudgetAllocationGuardrails;
  allocationNote: string;
  utilizationNote: string;
  profitNote: string;
  currencyNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface AdBudgetWeightRow {
  weightSource: string;
  weight: AdMoney;
  note: string;
  position: number;
}

export interface AdBudgetRuleTargetRow {
  id: string;
  campaignId: string;
  campaignName: string;
  platformCode: string;
  shopCode: string;
  salesTarget: AdMoney;
  minBudget: AdMoney;
  maxBudget: AdMoney;
  note: string;
  createdAt: string;
}

export interface AdBudgetRuleRow {
  id: string;
  ruleCode: string;
  ruleName: string;
  currency: string;
  scopePlatformId: string | null;
  scopePlatformCode: string;
  scopeShopCode: string | null;
  minCampaignBudget: AdMoney;
  maxCampaignBudget: AdMoney;
  constrainedUtilization: AdRatio;
  idleUtilization: AdRatio;
  status: string;
  weights: AdBudgetWeightRow[];
  targetCount: number;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AdBudgetRulePage {
  items: AdBudgetRuleRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdBudgetRuleDetail {
  rule: AdBudgetRuleRow;
  targets: AdBudgetRuleTargetRow[];
  note: string;
}

export interface AdBudgetPoolRow {
  id: string;
  poolCode: string;
  poolName: string;
  currency: string;
  totalAmount: AdMoney;
  periodStart: string;
  periodEnd: string;
  ruleId: string;
  ruleName: string;
  status: string;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AdBudgetPoolPage {
  items: AdBudgetPoolRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdBudgetPlanRow {
  id: string;
  poolId: string;
  poolName: string;
  ruleId: string;
  ruleName: string;
  status: string;
  currency: string;
  totalAmount: AdMoney;
  allocatedAmount: AdMoney;
  unallocatedAmount: AdMoney;
  releasedAmount: AdMoney;
  idleBudget: AdMoney;
  constrainedShortfall: AdMoney;
  campaignCount: number;
  skippedCount: number;
  windowStart: string;
  windowEnd: string;
  computedAt: string;
  note: string;
}

export interface AdBudgetPlanPage {
  items: AdBudgetPlanRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdBudgetPlanLineRow {
  id: string;
  campaignId: string;
  campaignName: string;
  platformCode: string;
  shopCode: string;
  campaignStatus: string;
  currency: string;
  currentBudget: AdMoney;
  historicalSpend: AdMoney;
  historicalSales: AdMoney;
  historicalOrders: AdMoney;
  /** 🔴 ROAS = 销售额 / 消耗；null = 不可比。 */
  roas: AdRatio;
  /** 🔴 ACOS = 消耗 / 销售额；null = 不可比。 */
  acos: AdRatio;
  dailyAvgSpend: AdMoney;
  /** 🔴 利用率 = 日均消耗 / 日预算；null = 不可比。 */
  utilization: AdRatio;
  salesTarget: AdMoney;
  score: AdRatio;
  weight: AdRatio;
  weightShare: AdRatio;
  allocatedBudget: AdMoney;
  budgetDelta: AdMoney;
  budgetConstrained: boolean;
  idleBudget: boolean;
  recommendation: string;
  reason: string;
  position: number;
}

export interface AdBudgetSkippedRow {
  id: string;
  campaignId: string;
  campaignName: string;
  platformCode: string;
  shopCode: string;
  campaignStatus: string;
  currency: string;
  currentBudget: AdMoney;
  skipReason: string;
  reason: string;
}

export interface AdBudgetRebalanceRow {
  id: string;
  planId: string;
  fromCampaignId: string;
  fromCampaignName: string;
  toCampaignId: string;
  toCampaignName: string;
  amount: AdMoney;
  reason: string;
  status: string;
  decidedAt: string | null;
  createdAt: string;
}

export interface AdBudgetRebalancePage {
  items: AdBudgetRebalanceRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdBudgetPlanDetail {
  plan: AdBudgetPlanRow;
  lines: AdBudgetPlanLineRow[];
  skipped: AdBudgetSkippedRow[];
  rebalances: AdBudgetRebalanceRow[];
  note: string;
}

export interface AdBudgetTaskRow {
  id: string;
  planId: string;
  poolName: string;
  taskType: string;
  status: string;
  currency: string;
  requestedBy: string | null;
  reason: string;
  appliedAt: string | null;
  note: string;
  lineCount: number;
  appliedCount: number;
  rejectedCount: number;
  skippedCount: number;
  createdAt: string;
}

export interface AdBudgetTaskPage {
  items: AdBudgetTaskRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdBudgetTaskLineRow {
  id: string;
  campaignId: string;
  campaignName: string;
  beforeBudget: AdMoney;
  targetBudget: AdMoney;
  appliedBudget: AdMoney;
  budgetDelta: AdMoney;
  result: string;
  message: string;
}

export interface AdBudgetTaskDetail {
  task: AdBudgetTaskRow;
  lines: AdBudgetTaskLineRow[];
  note: string;
}

export interface AdBudgetOption {
  value: string;
  label: string;
}

export interface AdBudgetOptions {
  weightSources: AdBudgetOption[];
  ruleStatuses: AdBudgetOption[];
  planStatuses: AdBudgetOption[];
  taskStatuses: AdBudgetOption[];
  recommendations: AdBudgetOption[];
  skipReasons: AdBudgetOption[];
  currencies: string[];
  metricFrom: string | null;
  metricTo: string | null;
  guardrails: AdBudgetAllocationGuardrails;
  allocationNote: string;
  utilizationNote: string;
}

// ==================================================================
// 请求
// ==================================================================

export interface SaveBudgetWeightInput {
  weightSource: string;
  weight: number;
  note: string;
}

export interface SaveBudgetTargetInput {
  campaignId: string;
  salesTarget: number;
  minBudget?: number | undefined;
  maxBudget?: number | undefined;
  note: string;
}

export interface SaveBudgetRuleInput {
  ruleCode: string;
  ruleName: string;
  currency: string;
  scopePlatformId?: string | undefined;
  scopeShopCode?: string | undefined;
  minCampaignBudget?: number | undefined;
  maxCampaignBudget?: number | undefined;
  constrainedUtilization?: number | undefined;
  idleUtilization?: number | undefined;
  weights: SaveBudgetWeightInput[];
  targets: SaveBudgetTargetInput[];
  note: string;
}

export interface SaveBudgetPoolInput {
  poolCode: string;
  poolName: string;
  currency: string;
  totalAmount: number;
  periodStart: string;
  periodEnd: string;
  ruleId: string;
  note: string;
}

export interface ComputeBudgetPlanInput {
  poolId: string;
  windowStart: string;
  windowEnd: string;
  note: string;
}

export interface CreateBudgetTaskInput {
  planId: string;
  reason: string;
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

export function fetchAdBudgetCapabilities(): Promise<AdBudgetCapabilities> {
  return api<AdBudgetCapabilities>('channel/ad-budgets/capabilities');
}

export function fetchAdBudgetOptions(): Promise<AdBudgetOptions> {
  return api<AdBudgetOptions>('channel/ad-budgets/options');
}

export function fetchAdBudgetRules(
  query: { status?: string; currency?: string; keyword?: string; page?: number; size?: number } = {},
): Promise<AdBudgetRulePage> {
  return api<AdBudgetRulePage>(`channel/ad-budgets/rules${queryString(query)}`);
}

export function fetchAdBudgetRule(id: string): Promise<AdBudgetRuleDetail> {
  return api<AdBudgetRuleDetail>(`channel/ad-budgets/rules/${id}`);
}

export function saveAdBudgetRule(input: SaveBudgetRuleInput): Promise<AdBudgetRuleDetail> {
  return api<AdBudgetRuleDetail>('channel/ad-budgets/rules', 'POST', input);
}

export function fetchAdBudgetPools(
  query: { currency?: string; keyword?: string; page?: number; size?: number } = {},
): Promise<AdBudgetPoolPage> {
  return api<AdBudgetPoolPage>(`channel/ad-budgets/pools${queryString(query)}`);
}

export function fetchAdBudgetPool(id: string): Promise<AdBudgetPoolRow> {
  return api<AdBudgetPoolRow>(`channel/ad-budgets/pools/${id}`);
}

export function saveAdBudgetPool(input: SaveBudgetPoolInput): Promise<AdBudgetPoolRow> {
  return api<AdBudgetPoolRow>('channel/ad-budgets/pools', 'POST', input);
}

export function fetchAdBudgetPlans(
  query: { status?: string; poolId?: string; ruleId?: string; page?: number; size?: number } = {},
): Promise<AdBudgetPlanPage> {
  return api<AdBudgetPlanPage>(`channel/ad-budgets/plans${queryString(query)}`);
}

export function fetchAdBudgetPlan(id: string): Promise<AdBudgetPlanDetail> {
  return api<AdBudgetPlanDetail>(`channel/ad-budgets/plans/${id}`);
}

export function computeAdBudgetPlan(input: ComputeBudgetPlanInput): Promise<AdBudgetPlanDetail> {
  return api<AdBudgetPlanDetail>('channel/ad-budgets/plans', 'POST', input);
}

export function fetchAdBudgetRebalances(
  query: { planId?: string; status?: string; page?: number; size?: number } = {},
): Promise<AdBudgetRebalancePage> {
  return api<AdBudgetRebalancePage>(`channel/ad-budgets/rebalances${queryString(query)}`);
}

export function fetchAdBudgetTasks(
  query: { planId?: string; status?: string; page?: number; size?: number } = {},
): Promise<AdBudgetTaskPage> {
  return api<AdBudgetTaskPage>(`channel/ad-budgets/tasks${queryString(query)}`);
}

export function fetchAdBudgetTask(id: string): Promise<AdBudgetTaskDetail> {
  return api<AdBudgetTaskDetail>(`channel/ad-budgets/tasks/${id}`);
}

export function createAdBudgetTask(input: CreateBudgetTaskInput): Promise<AdBudgetTaskDetail> {
  return api<AdBudgetTaskDetail>('channel/ad-budgets/tasks', 'POST', input);
}

export function applyAdBudgetTask(id: string): Promise<AdBudgetTaskDetail> {
  return api<AdBudgetTaskDetail>(`channel/ad-budgets/tasks/${id}/apply`, 'POST');
}

// ==================================================================
// 展示辅助
// ==================================================================

/** 金额原样展示（服务端算好的，前端不做算术）。 */
export function money(value: AdMoney | undefined, currency?: string): string {
  if (value === null || value === undefined) return '—';
  const fixed = value.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

/** 带正负号的金额（增减额必须看得出方向）。 */
export function deltaMoney(value: AdMoney | undefined, currency?: string): string {
  if (value === null || value === undefined) return '—';
  const fixed = value.toFixed(2);
  const signed = value > 0 ? `+${fixed}` : fixed;
  return currency ? `${signed} ${currency}` : signed;
}

/**
 * 派生比率展示：**不可比显示「不可比」，不是 0.00%**。
 *
 * 🔴 0 是「可比，但真的是零」；null 是「压根算不出来」（分母为 0 / 没有数据）。
 * 渲染成 0% 会让人以为「这条活动一次点击都没有」，而真相是「没有可比的数据」。
 */
export function ratioText(value: AdRatio | undefined, asPercent = true): string {
  if (value === null || value === undefined) return '不可比';
  return asPercent ? `${(value * 100).toFixed(2)}%` : value.toFixed(4);
}

export function ratioTone(value: AdRatio | undefined): string {
  return value === null || value === undefined ? 'text-muted-foreground' : 'text-foreground';
}

/** 权重来源标签。PROFIT 必须显式标出「当前不可用」。 */
export function weightSourceLabel(source: string): string {
  if (source === 'SALES_TARGET') return '销售目标';
  if (source === 'ROAS') return '历史 ROAS';
  if (source === 'PROFIT') return '利润（当前不可用）';
  return source;
}

/** 权重来源的语气：利润当前不可用，要看得见。 */
export function weightSourceTone(source: string): string {
  return source === 'PROFIT' ? 'text-muted-foreground' : 'text-foreground';
}

/** 调整建议标签。 */
export function recommendationLabel(recommendation: string): string {
  if (recommendation === 'HOLD') return '维持';
  if (recommendation === 'INCREASE') return '建议加预算';
  if (recommendation === 'DECREASE') return '建议减预算';
  if (recommendation === 'PAUSE_CANDIDATE') return '建议收回预算并保持暂停';
  if (recommendation === 'START_CANDIDATE') return '建议开预算';
  return recommendation;
}

/** 调整建议的语气。 */
export function recommendationTone(recommendation: string): string {
  if (recommendation === 'INCREASE' || recommendation === 'START_CANDIDATE') {
    return 'text-emerald-600';
  }
  if (recommendation === 'DECREASE' || recommendation === 'PAUSE_CANDIDATE') {
    return 'text-amber-600';
  }
  return 'text-muted-foreground';
}

/** 未纳入分配的原因标签。 */
export function skipReasonLabel(reason: string): string {
  if (reason === 'CURRENCY_MISMATCH') return '币种与预算池不一致';
  if (reason === 'NO_SCORE_BASIS') return '没有可用的分配依据';
  if (reason === 'STATUS_NOT_ELIGIBLE') return '活动状态不该参与分配';
  if (reason === 'OUT_OF_SCOPE') return '不在规则范围内';
  return reason;
}

/** 规则状态标签。 */
export function ruleStatusLabel(status: string): string {
  if (status === 'ACTIVE') return '启用';
  if (status === 'PAUSED') return '停用';
  if (status === 'ARCHIVED') return '已归档';
  return status;
}

/** 方案状态标签。 */
export function planStatusLabel(status: string): string {
  if (status === 'COMPUTED') return '已计算';
  if (status === 'APPROVED') return '已确认';
  if (status === 'APPLIED') return '已执行';
  if (status === 'SUPERSEDED') return '已被新方案取代';
  return status;
}

/** 任务状态标签 + 语气。 */
export function taskStatusLabel(status: string): string {
  if (status === 'PENDING') return '待执行';
  if (status === 'APPLIED') return '全部生效';
  if (status === 'PARTIALLY_APPLIED') return '部分生效';
  if (status === 'FAILED') return '未生效';
  if (status === 'CANCELLED') return '已撤销';
  return status;
}

export function taskStatusTone(status: string): string {
  if (status === 'APPLIED') return 'text-emerald-600';
  if (status === 'PARTIALLY_APPLIED') return 'text-amber-600';
  if (status === 'FAILED') return 'text-destructive';
  if (status === 'CANCELLED') return 'text-muted-foreground';
  return 'text-foreground';
}

/** 逐条结果标签 + 语气（被护栏拒绝的那几条必须显眼）。 */
export function taskResultLabel(result: string): string {
  if (result === 'APPLIED') return '已生效';
  if (result === 'REJECTED') return '被护栏拒绝';
  if (result === 'SKIPPED') return '跳过';
  if (result === 'PENDING') return '待执行';
  return result;
}

export function taskResultTone(result: string): string {
  if (result === 'REJECTED') return 'text-destructive';
  if (result === 'APPLIED') return 'text-emerald-600';
  return 'text-muted-foreground';
}

export function timestampText(value: string | null): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

export function dateText(value: string | null): string {
  if (!value) return '—';
  return value;
}

/** 护栏上限的一行说明（界面上要把天花板写出来，而不是等用户撞墙）。 */
export function guardrailText(guardrails: AdBudgetAllocationGuardrails | null): string {
  if (!guardrails) return '';
  return (
    `单次单活动调整上限 ${guardrails.maxSingleBudgetDelta}，一次执行任务总额上限 ${guardrails.maxTotalBudgetDelta}，` +
    `单活动预算上限 ${guardrails.maxCampaignBudget}，一次最多 ${guardrails.maxCampaignsPerTask} 条。`
  );
}

/** UUID 形状校验（前端只做形状校验，不做业务校验）。 */
export function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value.trim());
}

/** 金额输入的形状校验（正数、有限小数）。 */
export function isPositiveAmount(value: string): boolean {
  const trimmed = value.trim();
  if (trimmed === '') return false;
  const parsed = Number(trimmed);
  return !Number.isNaN(parsed) && parsed > 0;
}

/** 非负金额输入的形状校验。 */
export function isNonNegativeAmount(value: string): boolean {
  const trimmed = value.trim();
  if (trimmed === '') return false;
  const parsed = Number(trimmed);
  return !Number.isNaN(parsed) && parsed >= 0;
}

// ==================================================================
// 表单校验
// ==================================================================

export interface AdBudgetRuleInputState {
  ruleCode: string;
  ruleName: string;
  currency: string;
  scopeShopCode: string;
  minCampaignBudget: string;
  maxCampaignBudget: string;
  constrainedUtilization: string;
  idleUtilization: string;
  salesTargetWeight: string;
  roasWeight: string;
  profitWeight: string;
  targetCampaignId: string;
  targetSalesTarget: string;
}

export const emptyAdBudgetRuleInput: AdBudgetRuleInputState = {
  ruleCode: '',
  ruleName: '',
  currency: 'USD',
  scopeShopCode: '',
  minCampaignBudget: '',
  maxCampaignBudget: '',
  constrainedUtilization: '0.95',
  idleUtilization: '0.30',
  salesTargetWeight: '1',
  roasWeight: '1',
  profitWeight: '0',
  targetCampaignId: '',
  targetSalesTarget: '',
};

/**
 * 分配规则表单校验。
 *
 * 🔴 **利润权重当前必须为 0**：13.4 利润未交付，没有利润数据源。
 * 前端在这里挡住，服务端还会再挡一道（DB 层还有 CHECK），但**界面上必须说得出为什么**，
 * 而不是只报一个「参数非法」。
 */
export function validateAdBudgetRule(input: AdBudgetRuleInputState): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.ruleCode.trim()) errors.push('规则编码必填');
  if (!input.ruleName.trim()) errors.push('规则名称必填');
  if (input.currency.trim().length !== 3) errors.push('币种必须是 3 位字母代码');
  if (input.minCampaignBudget.trim() !== '' && !isNonNegativeAmount(input.minCampaignBudget)) {
    errors.push('单活动预算下限必须是不小于 0 的数字');
  }
  if (input.maxCampaignBudget.trim() !== '' && !isNonNegativeAmount(input.maxCampaignBudget)) {
    errors.push('单活动预算上限必须是不小于 0 的数字');
  }
  if (
    input.minCampaignBudget.trim() !== '' &&
    input.maxCampaignBudget.trim() !== '' &&
    Number(input.maxCampaignBudget) < Number(input.minCampaignBudget)
  ) {
    errors.push('单活动预算上限不能低于下限');
  }
  const constrained = Number(input.constrainedUtilization);
  const idle = Number(input.idleUtilization);
  if (input.constrainedUtilization.trim() === '' || Number.isNaN(constrained) || constrained <= 0) {
    errors.push('预算受限的利用率阈值必须大于 0');
  }
  if (input.idleUtilization.trim() === '' || Number.isNaN(idle) || idle < 0 || idle >= 1) {
    errors.push('闲置预算的利用率阈值必须在 [0, 1) 之间');
  }
  if (!Number.isNaN(constrained) && !Number.isNaN(idle) && constrained <= idle) {
    errors.push('预算受限阈值必须高于闲置阈值，否则同一条活动会同时「受限」又「闲置」');
  }
  for (const [label, value] of [
    ['销售目标权重', input.salesTargetWeight],
    ['历史 ROAS 权重', input.roasWeight],
    ['利润权重', input.profitWeight],
  ] as const) {
    const parsed = Number(value.trim() === '' ? '0' : value);
    if (Number.isNaN(parsed) || parsed < 0) errors.push(`${label}必须是不小于 0 的数字`);
  }
  const salesWeight = Number(input.salesTargetWeight.trim() === '' ? '0' : input.salesTargetWeight);
  const roasWeight = Number(input.roasWeight.trim() === '' ? '0' : input.roasWeight);
  const profitWeight = Number(input.profitWeight.trim() === '' ? '0' : input.profitWeight);
  if (salesWeight <= 0 && roasWeight <= 0 && profitWeight <= 0) {
    errors.push('至少一个权重大于 0 —— 全 0 的规则算出来的分配额全是 0');
  }
  if (profitWeight > 0) {
    errors.push(
      '🔴 利润权重当前不可用：上游 13.4 利润分析（13.4.1.1 多模式利润报表）尚未交付，没有利润数据源。' +
        '当前只能按销售目标与历史 ROAS 分配。',
    );
  }
  if (input.targetCampaignId.trim() !== '' || input.targetSalesTarget.trim() !== '') {
    if (!isUuid(input.targetCampaignId)) errors.push('销售目标指向的活动 id 必须是合法 UUID');
    if (!isPositiveAmount(input.targetSalesTarget)) errors.push('销售目标必须大于 0');
  }
  return { ok: errors.length === 0, errors };
}

export interface AdBudgetPoolInputState {
  poolCode: string;
  poolName: string;
  currency: string;
  totalAmount: string;
  periodStart: string;
  periodEnd: string;
  ruleId: string;
  note: string;
}

export const emptyAdBudgetPoolInput: AdBudgetPoolInputState = {
  poolCode: '',
  poolName: '',
  currency: 'USD',
  totalAmount: '',
  periodStart: '',
  periodEnd: '',
  ruleId: '',
  note: '',
};

/** 预算池表单校验（预算总额是钱，必须大于 0；周期必须显式给出）。 */
export function validateAdBudgetPool(input: AdBudgetPoolInputState): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.poolCode.trim()) errors.push('预算池编码必填');
  if (!input.poolName.trim()) errors.push('预算池名称必填');
  if (input.currency.trim().length !== 3) errors.push('币种必须是 3 位字母代码');
  if (!isPositiveAmount(input.totalAmount)) errors.push('预算总额必须大于 0');
  if (!input.periodStart.trim()) errors.push('周期开始必填');
  if (!input.periodEnd.trim()) errors.push('周期结束必填');
  if (
    input.periodStart.trim() !== '' &&
    input.periodEnd.trim() !== '' &&
    input.periodEnd < input.periodStart
  ) {
    errors.push('周期结束不能早于周期开始');
  }
  if (!isUuid(input.ruleId)) errors.push('必须选择一条分配规则');
  return { ok: errors.length === 0, errors };
}

export interface AdBudgetPlanInputState {
  poolId: string;
  windowStart: string;
  windowEnd: string;
  note: string;
}

export const emptyAdBudgetPlanInput: AdBudgetPlanInputState = {
  poolId: '',
  windowStart: '',
  windowEnd: '',
  note: '',
};

/**
 * 计算分配方案表单校验。
 *
 * 🔴 统计窗口**必须显式给出**：不从墙上时钟推导 —— 「上个月」这种键在月初与月末
 * 会圈出不同范围，既没法复算也没法断言。
 */
export function validateAdBudgetPlan(input: AdBudgetPlanInputState): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!isUuid(input.poolId)) errors.push('必须选择一个预算池');
  if (!input.windowStart.trim()) errors.push('统计窗口开始必填（不从墙上时钟推导）');
  if (!input.windowEnd.trim()) errors.push('统计窗口结束必填（不从墙上时钟推导）');
  if (
    input.windowStart.trim() !== '' &&
    input.windowEnd.trim() !== '' &&
    input.windowEnd < input.windowStart
  ) {
    errors.push('统计窗口结束不能早于开始');
  }
  return { ok: errors.length === 0, errors };
}
