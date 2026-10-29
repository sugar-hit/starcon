import { api } from '../inventory/api';

/**
 * 14.2.1.2 分时策略 前端模型。
 *
 * <p>蓝图：「按小时设置不同竞价与预算（高峰提价提预算、低谷降价停投），
 * 支持按历史出单时段自动推荐分时曲线。输入：分时规则；13.2.1.2 时段分布数据。
 * 输出：分时策略配置 + 定时调整任务。关系：上游 = 13.2.1.2；下游 = 14.2.2.2 自动规则。
 * <strong>分时策略需按店铺时区配置</strong>，跨时区店铺用错时区会完全失效。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **时区归属必须显式**。`timezoneStatus` 有三态：`RESOLVED`（定得了）/
 *    `UNRESOLVED`（定不了）/ `CONFLICT`（与上游解析不一致）。
 *    定不了的店铺**单列**出来并写清原因 —— 把它混进某个默认时区的桶里，
 *    就是蓝图加粗句说的「用错时区会完全失效」。
 * 2. **「停投」在本模块里 = 预算系数置 0**，不是把活动状态改成 PAUSED。
 *    界面上**不要**出现「暂停活动」这种按钮 —— 那是 14.1.1.1 的能力。
 * 3. **不可比 ≠ 零**。占比类字段为 `null` 表示**不可比**（分母为 0），
 *    `0` 表示**可比但真的是零** —— 显示成「0%」会把「判不了」说成「真的是零」。
 * 4. **被护栏拒掉的条目要看得见**。任务明细里 `REJECTED` 的行照样列出来，
 *    并写清被哪道闸拦下 —— 静默丢弃会让人以为「按预期工作」。
 * 5. **试运行 ≠ 已生效**。`DRY_RUN` 的行显示「本来会变成多少」，不写成「已改成」。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * 目标预算、建议系数、上调合计都是服务端 `BigDecimal` 的事，前端算一遍就会引入
 * 二进制浮点误差，而且会让「界面上的数」和「账上的数」对不上。
 */

/** 金额与数量一律 number（JSON number），只原样展示。 */
export type AdMoney = number | null;

/** 🔴 null = 不可比（分母为 0 或没有数据），与 `0`（可比但真的是零）含义相反。 */
export type AdRatio = number | null;

export interface AdDaypartCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface AdDaypartCapabilities {
  dimensions: AdDaypartCapabilityDimension[];
  statuses: string[];
  scopes: string[];
  actions: string[];
  runModes: string[];
  triggerSources: string[];
  upstreamStatuses: string[];
  timezoneNote: string;
  multiplierNote: string;
  guardrailNote: string;
  executionNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface AdDaypartShopTimezone {
  shopCode: string;
  shopName: string;
  shopId: string;
  shopTimezone: string;
  timezoneStatus: string;
  timezoneReason: string;
  activeSiteCount: number;
  siteTimezones: string[];
}

export interface AdDaypartHourConfig {
  localHour: number;
  action: string;
  bidMultiplier: number;
  budgetMultiplier: number;
  source: string;
  basisNote: string;
}

export interface AdDaypartStrategyView {
  id: string;
  strategyCode: string;
  strategyName: string;
  shopCode: string;
  shopName: string;
  shopTimezone: string;
  timezoneStatus: string;
  timezoneReason: string;
  scope: string;
  targetCampaignId: string;
  targetCampaignName: string;
  currency: string;
  status: string;
  singleAdjustCapAmount: number;
  singleAdjustCapPercent: number;
  totalCapAmount: number;
  totalCapCount: number;
  totalCapWindowHours: number;
  minBudget: number | null;
  maxBudget: number | null;
  minBid: number | null;
  maxBid: number | null;
  dryRun: boolean;
  scheduleEnabled: boolean;
  runIntervalMinutes: number;
  recommendPeakShareMultiple: number;
  recommendTroughShareMultiple: number;
  recommendPauseShareMultiple: number;
  recommendBoostBidMultiplier: number;
  recommendBoostBudgetMultiplier: number;
  recommendReduceBidMultiplier: number;
  recommendReduceBudgetMultiplier: number;
  recommendPauseBidMultiplier: number;
  recommendPauseBudgetMultiplier: number;
  lastRunAt: string | null;
  lastAppliedAt: string | null;
  hours: AdDaypartHourConfig[];
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AdDaypartStrategySummary {
  id: string;
  strategyCode: string;
  strategyName: string;
  shopCode: string;
  shopName: string;
  shopTimezone: string;
  timezoneStatus: string;
  timezoneReason: string;
  scope: string;
  currency: string;
  status: string;
  dryRun: boolean;
  scheduleEnabled: boolean;
  effectiveHourCount: number;
  lastRunAt: string | null;
  updatedAt: string;
}

export interface AdDaypartStrategyPage {
  items: AdDaypartStrategySummary[];
  total: number;
  page: number;
  size: number;
}

export interface AdDaypartStrategyInput {
  strategyCode: string;
  strategyName: string;
  shopCode: string;
  scope: string;
  targetCampaignId: string;
  currency: string;
  singleAdjustCapAmount: number;
  singleAdjustCapPercent: number;
  totalCapAmount: number;
  totalCapCount: number;
  totalCapWindowHours: number;
  minBudget: number | null;
  maxBudget: number | null;
  minBid: number | null;
  maxBid: number | null;
  dryRun: boolean;
  scheduleEnabled: boolean;
  runIntervalMinutes: number;
  recommendPeakShareMultiple: number;
  recommendTroughShareMultiple: number;
  recommendPauseShareMultiple: number;
  recommendBoostBidMultiplier: number;
  recommendBoostBudgetMultiplier: number;
  recommendReduceBidMultiplier: number;
  recommendReduceBudgetMultiplier: number;
  recommendPauseBidMultiplier: number;
  recommendPauseBudgetMultiplier: number;
  note: string;
}

export interface AdDaypartHourInput {
  localHour: number;
  action: string;
  bidMultiplier: number;
  budgetMultiplier: number;
  basisNote: string;
}

export interface AdDaypartRecommendationHourView {
  localHour: number;
  orderCount: number;
  orderShare: AdRatio;
  p90Peak: boolean;
  suggestedAction: string;
  suggestedBidMultiplier: number;
  suggestedBudgetMultiplier: number;
  basisNote: string;
}

export interface AdDaypartRecommendationView {
  id: string;
  strategyId: string;
  shopCode: string;
  shopName: string;
  shopTimezone: string;
  timezoneStatus: string;
  timezoneReason: string;
  windowFrom: string;
  windowTo: string;
  alignment: string;
  profile: Record<string, number | string | null>;
  upstreamStatus: string;
  upstreamDataUpdatedAt: string | null;
  upstreamMessage: string;
  projectedOrderCount: number;
  projectedDays: number;
  expectedDays: number;
  hours: AdDaypartRecommendationHourView[];
  generatedAt: string;
}

export interface AdDaypartTaskLineView {
  id: string;
  targetType: string;
  campaignId: string;
  campaignName: string;
  keywordId: string;
  targetRef: string;
  localHour: number;
  action: string;
  beforeValue: AdMoney;
  afterValue: AdMoney;
  delta: number;
  currency: string;
  result: string;
  guardrailCode: string;
  message: string;
}

export interface AdDaypartTaskView {
  id: string;
  strategyId: string;
  strategyCode: string;
  strategyName: string;
  shopCode: string;
  shopTimezone: string;
  timezoneStatus: string;
  localHour: number;
  runMode: string;
  triggerSource: string;
  status: string;
  currency: string;
  singleAdjustCapAmount: number;
  singleAdjustCapPercent: number;
  totalCapAmount: number;
  totalCapCount: number;
  totalCapWindowHours: number;
  windowFrom: string;
  windowTo: string;
  appliedCount: number;
  rejectedCount: number;
  skippedCount: number;
  totalIncrease: number;
  executedAt: string | null;
  message: string;
  note: string;
  lines: AdDaypartTaskLineView[];
  createdAt: string;
}

export interface AdDaypartTaskSummary {
  id: string;
  strategyId: string;
  strategyCode: string;
  shopCode: string;
  shopTimezone: string;
  localHour: number;
  runMode: string;
  triggerSource: string;
  status: string;
  currency: string;
  appliedCount: number;
  rejectedCount: number;
  skippedCount: number;
  totalIncrease: number;
  executedAt: string | null;
  createdAt: string;
}

export interface AdDaypartTaskPage {
  items: AdDaypartTaskSummary[];
  total: number;
  page: number;
  size: number;
}

export interface AdDaypartRunSkip {
  strategyId: string;
  strategyCode: string;
  shopCode: string;
  reason: string;
}

export interface AdDaypartRunResult {
  strategyCount: number;
  executedCount: number;
  skippedCount: number;
  tasks: AdDaypartTaskView[];
  skips: AdDaypartRunSkip[];
}

export interface AdDaypartCapView {
  strategyId: string;
  strategyCode: string;
  strategyName: string;
  status: string;
  singleAdjustCapAmount: number;
  singleAdjustCapPercent: number;
  totalCapAmount: number;
  totalCapCount: number;
  totalCapWindowHours: number;
  minBudget: number | null;
  maxBudget: number | null;
  minBid: number | null;
  maxBid: number | null;
  dryRun: boolean;
}

export interface AdDaypartShopCapsView {
  shopCode: string;
  shopName: string;
  shopTimezone: string;
  timezoneStatus: string;
  timezoneReason: string;
  strategies: AdDaypartCapView[];
  effectiveCaps: AdDaypartCapView | null;
  note: string;
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

export function fetchAdDaypartCapabilities(): Promise<AdDaypartCapabilities> {
  return api<AdDaypartCapabilities>('channel/ad-dayparts/capabilities');
}

export function fetchAdDaypartShops(): Promise<AdDaypartShopTimezone[]> {
  return api<AdDaypartShopTimezone[]>('channel/ad-dayparts/shops');
}

export function fetchAdDaypartShopTimezone(shopCode: string): Promise<AdDaypartShopTimezone> {
  return api<AdDaypartShopTimezone>(
    `channel/ad-dayparts/shops/${encodeURIComponent(shopCode)}/timezone`,
  );
}

export function fetchAdDaypartStrategies(
  query: {
    shopCode?: string | undefined;
    status?: string | undefined;
    keyword?: string | undefined;
    page?: number | undefined;
    size?: number | undefined;
  } = {},
): Promise<AdDaypartStrategyPage> {
  return api<AdDaypartStrategyPage>(`channel/ad-dayparts/strategies${queryString(query)}`);
}

export function fetchAdDaypartStrategy(id: string): Promise<AdDaypartStrategyView> {
  return api<AdDaypartStrategyView>(`channel/ad-dayparts/strategies/${id}`);
}

export function createAdDaypartStrategy(
  input: AdDaypartStrategyInput,
): Promise<AdDaypartStrategyView> {
  return api<AdDaypartStrategyView>('channel/ad-dayparts/strategies', 'POST', input);
}

export function updateAdDaypartStrategy(
  id: string,
  input: AdDaypartStrategyInput,
): Promise<AdDaypartStrategyView> {
  return api<AdDaypartStrategyView>(`channel/ad-dayparts/strategies/${id}`, 'PUT', input);
}

export function replaceAdDaypartCurve(
  id: string,
  hours: AdDaypartHourInput[],
): Promise<AdDaypartStrategyView> {
  return api<AdDaypartStrategyView>(`channel/ad-dayparts/strategies/${id}/curve`, 'PUT', { hours });
}

export function changeAdDaypartStatus(
  id: string,
  status: string,
  note = '',
): Promise<AdDaypartStrategyView> {
  return api<AdDaypartStrategyView>(`channel/ad-dayparts/strategies/${id}/status`, 'POST', {
    status,
    note,
  });
}

export function archiveAdDaypartStrategy(id: string): Promise<AdDaypartStrategyView> {
  return api<AdDaypartStrategyView>(`channel/ad-dayparts/strategies/${id}`, 'DELETE');
}

export function generateAdDaypartRecommendation(
  strategyId: string,
  from = '',
  to = '',
): Promise<AdDaypartRecommendationView> {
  return api<AdDaypartRecommendationView>('channel/ad-dayparts/recommendations', 'POST', {
    strategyId,
    from,
    to,
  });
}

export function fetchAdDaypartRecommendations(
  strategyId: string,
): Promise<AdDaypartRecommendationView[]> {
  return api<AdDaypartRecommendationView[]>(
    `channel/ad-dayparts/recommendations${queryString({ strategyId })}`,
  );
}

export function fetchAdDaypartRecommendation(id: string): Promise<AdDaypartRecommendationView> {
  return api<AdDaypartRecommendationView>(`channel/ad-dayparts/recommendations/${id}`);
}

export function applyAdDaypartRecommendation(
  id: string,
  hours: number[] = [],
): Promise<AdDaypartStrategyView> {
  return api<AdDaypartStrategyView>(`channel/ad-dayparts/recommendations/${id}/apply`, 'POST', {
    hours,
  });
}

export function runAdDaypartTasks(input: {
  strategyId?: string;
  runMode?: string;
  triggerSource?: string;
  note?: string;
}): Promise<AdDaypartRunResult> {
  return api<AdDaypartRunResult>('channel/ad-dayparts/tasks/run', 'POST', {
    strategyId: input.strategyId ?? '',
    runMode: input.runMode ?? 'DRY_RUN',
    triggerSource: input.triggerSource ?? 'MANUAL',
    note: input.note ?? '',
  });
}

export function fetchAdDaypartTasks(
  query: {
    strategyId?: string | undefined;
    status?: string | undefined;
    page?: number | undefined;
    size?: number | undefined;
  } = {},
): Promise<AdDaypartTaskPage> {
  return api<AdDaypartTaskPage>(`channel/ad-dayparts/tasks${queryString(query)}`);
}

export function fetchAdDaypartTask(id: string): Promise<AdDaypartTaskView> {
  return api<AdDaypartTaskView>(`channel/ad-dayparts/tasks/${id}`);
}

export function fetchAdDaypartShopCaps(shopCode: string): Promise<AdDaypartShopCapsView> {
  return api<AdDaypartShopCapsView>(`channel/ad-dayparts/caps${queryString({ shopCode })}`);
}

// ================================================================ 展示

/** 动作档的中文名。 */
export function daypartActionLabel(action: string): string {
  switch (action) {
    case 'BOOST':
      return '高峰提价提预算';
    case 'REDUCE':
      return '低谷降价';
    case 'PAUSE':
      return '低谷停投';
    default:
      return '不动';
  }
}

/** 动作档的色调（good / warn / bad / muted）。 */
export function daypartActionTone(action: string): 'good' | 'warn' | 'bad' | 'muted' {
  switch (action) {
    case 'BOOST':
      return 'good';
    case 'REDUCE':
      return 'warn';
    case 'PAUSE':
      return 'bad';
    default:
      return 'muted';
  }
}

export function daypartStatusLabel(status: string): string {
  switch (status) {
    case 'DRAFT':
      return '草稿';
    case 'ACTIVE':
      return '生效中';
    case 'PAUSED':
      return '已暂停';
    case 'ARCHIVED':
      return '已归档';
    default:
      return status;
  }
}

export function daypartStatusTone(status: string): 'good' | 'warn' | 'bad' | 'muted' {
  switch (status) {
    case 'ACTIVE':
      return 'good';
    case 'PAUSED':
      return 'warn';
    case 'ARCHIVED':
      return 'muted';
    default:
      return 'muted';
  }
}

/** 🔴 时区归属三态的中文名。 */
export function timezoneStatusLabel(status: string): string {
  switch (status) {
    case 'RESOLVED':
      return '时区已定';
    case 'CONFLICT':
      return '时区与上游不一致';
    default:
      return '时区定不了';
  }
}

export function timezoneStatusTone(status: string): 'good' | 'warn' | 'bad' | 'muted' {
  switch (status) {
    case 'RESOLVED':
      return 'good';
    case 'CONFLICT':
      return 'warn';
    default:
      return 'bad';
  }
}

/** 上游结论的中文名。 */
export function upstreamStatusLabel(status: string): string {
  switch (status) {
    case 'OK':
      return '已产出曲线';
    case 'SHOP_UNRESOLVED':
      return '店铺时区定不了';
    case 'CONFLICT':
      return '两侧时区不一致';
    case 'NO_FACT':
      return '上游窗口内没有事实';
    default:
      return status;
  }
}

export function upstreamStatusTone(status: string): 'good' | 'warn' | 'bad' | 'muted' {
  switch (status) {
    case 'OK':
      return 'good';
    case 'NO_FACT':
      return 'muted';
    default:
      return 'bad';
  }
}

/** 逐条结果的中文名。 */
export function taskResultLabel(result: string): string {
  switch (result) {
    case 'APPLIED':
      return '已生效';
    case 'REJECTED':
      return '被拒';
    case 'SKIPPED':
      return '已跳过';
    case 'DRY_RUN':
      return '试运行（只算不调）';
    default:
      return '待执行';
  }
}

export function taskResultTone(result: string): 'good' | 'warn' | 'bad' | 'muted' {
  switch (result) {
    case 'APPLIED':
      return 'good';
    case 'REJECTED':
      return 'bad';
    case 'DRY_RUN':
      return 'warn';
    default:
      return 'muted';
  }
}

export function taskStatusLabel(status: string): string {
  switch (status) {
    case 'APPLIED':
      return '已生效';
    case 'PARTIALLY_APPLIED':
      return '部分生效';
    case 'FAILED':
      return '全部被拒';
    case 'SKIPPED':
      return '已跳过';
    case 'CANCELLED':
      return '已取消';
    default:
      return '进行中';
  }
}

export function taskStatusTone(status: string): 'good' | 'warn' | 'bad' | 'muted' {
  switch (status) {
    case 'APPLIED':
      return 'good';
    case 'PARTIALLY_APPLIED':
      return 'warn';
    case 'FAILED':
      return 'bad';
    default:
      return 'muted';
  }
}

/** 🔴 被哪一道闸拦下的中文名（**空串 = 没过闸，是业务原因**）。 */
export function guardrailLabel(code: string): string {
  switch (code) {
    case 'SINGLE_AMOUNT':
      return '单次上限（金额）';
    case 'SINGLE_PERCENT':
      return '单次上限（比例）';
    case 'TOTAL_AMOUNT':
      return '总额上限';
    case 'TOTAL_COUNT':
      return '次数上限';
    case 'BOUND':
      return '上下限保护';
    case 'CURRENCY':
      return '币种不一致';
    default:
      return '';
  }
}

/** 金额展示（**原样**，不做任何前端算术）。 */
export function money(value: AdMoney): string {
  if (value === null || value === undefined) return '不可比';
  return value.toFixed(4);
}

/** 系数展示（比例是数量，不是金额）。 */
export function multiplierText(value: AdMoney): string {
  if (value === null || value === undefined) return '不可比';
  return value.toFixed(6);
}

/** 🔴 占比展示：null = **不可比**（不是 0%）。 */
export function ratioText(value: AdRatio): string {
  if (value === null || value === undefined) return '不可比';
  return `${(value * 100).toFixed(2)}%`;
}

/** 本地小时的中文标签（0..23）。 */
export function hourLabel(hour: number): string {
  return `${String(hour).padStart(2, '0')}:00`;
}

/** 时间戳展示（空 = 从没跑过）。 */
export function timestampText(value: string | null | undefined): string {
  if (!value) return '—';
  return value.replace('T', ' ').slice(0, 19);
}

// ================================================================ 校验

/**
 * 🔴 曲线校验（与服务层的规则**同款**）。
 *
 * <p>前端先拦一道是为了少一次往返；**但服务端仍然是权威**（它还查「24 小时齐全」
 * 与 DB 层的触发器）—— 前端校验通过**不代表**服务端一定收。
 */
export function validateDaypartCurve(hours: AdDaypartHourInput[]): string[] {
  const problems: string[] = [];
  if (hours.length === 0) {
    problems.push('曲线不能为空：缺一个小时，那个小时就落进「没有配置」的空洞');
    return problems;
  }
  const seen = new Set<number>();
  for (const hour of hours) {
    if (!Number.isInteger(hour.localHour) || hour.localHour < 0 || hour.localHour > 23) {
      problems.push('小时必须在 0..23 之间');
      continue;
    }
    if (seen.has(hour.localHour)) {
      problems.push(`小时 ${hour.localHour} 重复了`);
    }
    seen.add(hour.localHour);
    if (!['KEEP', 'BOOST', 'REDUCE', 'PAUSE'].includes(hour.action)) {
      problems.push(`小时 ${hour.localHour} 的动作不合法：${hour.action}`);
    }
    if (!(hour.bidMultiplier > 0)) {
      problems.push(`小时 ${hour.localHour} 的竞价系数必须大于 0`);
    }
    if (hour.budgetMultiplier < 0) {
      problems.push(`小时 ${hour.localHour} 的预算系数不能为负`);
    }
    if (hour.action === 'PAUSE' && hour.budgetMultiplier !== 0) {
      problems.push(`小时 ${hour.localHour} 标成「停投」，预算系数必须是 0`);
    }
  }
  for (let hour = 0; hour < 24; hour += 1) {
    if (!seen.has(hour)) {
      problems.push(`曲线必须覆盖 0..23 全部 24 个小时（缺 ${hour}）`);
    }
  }
  return problems;
}

/** 曲线里真正在动的小时数（24 = 全天都在调）。 */
export function effectiveHourCount(hours: AdDaypartHourInput[]): number {
  return hours.filter(
    (hour) =>
      !(hour.action === 'KEEP' && hour.bidMultiplier === 1 && hour.budgetMultiplier === 1),
  ).length;
}

/** 🔴 **能否激活**：时区定不了 或 曲线全是「不动」 ⇒ 激活不了。 */
export function activationBlockers(
  timezoneStatus: string,
  hours: AdDaypartHourInput[],
): string[] {
  const problems: string[] = [];
  if (timezoneStatus !== 'RESOLVED') {
    problems.push('店铺时区定不了 ⇒ 策略激活不了（跨时区店铺用错时区会完全失效）');
  }
  if (effectiveHourCount(hours) === 0) {
    problems.push('曲线 24 个小时全是「不动」，激活它等于什么都没配');
  }
  return problems;
}

/** 从已有曲线派生出一份**可编辑**的输入（24 行，缺的小时补 KEEP×1）。 */
export function editableCurve(hours: AdDaypartHourConfig[]): AdDaypartHourInput[] {
  const byHour = new Map(hours.map((hour) => [hour.localHour, hour]));
  const result: AdDaypartHourInput[] = [];
  for (let hour = 0; hour < 24; hour += 1) {
    const found = byHour.get(hour);
    result.push({
      localHour: hour,
      action: found?.action ?? 'KEEP',
      bidMultiplier: found?.bidMultiplier ?? 1,
      budgetMultiplier: found?.budgetMultiplier ?? 1,
      basisNote: found?.basisNote ?? '',
    });
  }
  return result;
}
