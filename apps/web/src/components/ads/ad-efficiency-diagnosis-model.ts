import { api } from '../inventory/api';

/**
 * 14.3.2.2 效率诊断 前端模型。
 *
 * 蓝图：「诊断投放效率问题：高花费零转化、ACOS 长期超标、预算受限、竞价过低曝光不足、创意衰退，
 * 给出可执行建议与一键调整。输入：14.3 报表指标；诊断规则。输出：诊断清单 + 建议动作。
 * 关系：上游 = 14.3.1；下游 = 14.2.1.1、14.1.3.1。**诊断要给动作而不是只报警**，
 * 否则用户看完还是要自己想怎么办。」
 *
 * 🔴 这个页面上最容易做丢的三件事，这里都挡住了：
 *
 * 1. **「不可比」绝不渲染成 0**。`null` = 不可比（分母为 0，比如销售额为 0 时 ACOS 算不出来），
 *    `0` = 可比但真的是零。两者含义相反：把「烧了 210 块、零订单」显示成「ACOS 0%」，
 *    看起来像效率极高。页面上「不可比」显示成「不可比」而不是 0.00%。
 * 2. **「不可用」不是「失败」**。竞价类动作 `actionMode=UNAVAILABLE` + `ownerModule`
 *    是**能力声明**（14.2.1.1 竞价管理未交付），页面上写「待 14.2.1.1 交付后开放」，
 *    不写成报错、也不给一个点了没用的按钮。
 * 3. **「没产出结论」与「没跑」分开说**。`evaluated=false` + `skipReason` 是「这条规则这次没跑」
 *    （创意衰退：创意数据源未就绪），与「跑了但没命中」是两件事。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * ACOS / 预算利用率 / 曝光相对倍数与「本来会变成多少」全部是服务端 `BigDecimal` 的事，
 * 前端算一遍就会引入二进制浮点误差，而且会让「界面上的 ACOS」和「报表里的 ACOS」对不上。
 */

/** 金额与数量一律 number（JSON number），只原样展示。 */
export type AdMoney = number | null;

/** 🔴 null = 不可比（分母为 0），与 `0`（可比但真的是零）含义相反。 */
export type AdRatio = number | null;

export interface AdDiagnosisCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface AdDiagnosisCapabilities {
  dimensions: AdDiagnosisCapabilityDimension[];
  ruleTypes: string[];
  severities: string[];
  actionTypes: string[];
  actionModes: string[];
  outcomes: string[];
  diagnosisStatuses: string[];
  emptyReasons: string[];
  metricDefinitionNote: string;
  ruleDefinitionNote: string;
  actionNote: string;
  guardrailNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface AdDiagnosisRuleRow {
  id: string;
  ruleCode: string;
  ruleName: string;
  /** 🔴 判定口径：五种问题之一。 */
  ruleType: string;
  severity: string;
  enabled: boolean;
  lookbackDays: number;
  /** 🔴 最少需要几天的数据才下结论。 */
  minMetricDays: number;
  thresholdAmount: AdMoney;
  thresholdRatio: AdRatio;
  minOverTargetDays: number;
  budgetUtilizationRatio: AdRatio;
  deliveryRatio: AdRatio;
  actionType: string;
  actionPercent: AdRatio;
  actionAmount: AdMoney;
  secondaryActionType: string | null;
  secondaryActionPercent: AdRatio;
  secondaryActionAmount: AdMoney;
  /** 🔴 数据源未就绪 ⇒ 该规则不产出任何结论。 */
  dataSourceReady: boolean;
  /** 🔴 规则级试运行开关：与本次请求的 dryRun 取更严的那个。 */
  dryRun: boolean;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AdDiagnosisRulePage {
  items: AdDiagnosisRuleRow[];
  total: number;
  page: number;
  size: number;
  enabledCount: number;
  notReadyCount: number;
}

export interface AdDiagnosisRuleDefaultsResult {
  created: number;
  existing: number;
  rules: AdDiagnosisRuleRow[];
  note: string;
}

export interface AdDiagnosisRunRow {
  id: string;
  lookbackDays: number;
  windowFrom: string;
  windowTo: string;
  ruleCount: number;
  enabledRuleCount: number;
  skippedRuleCount: number;
  scannedCampaignCount: number;
  diagnosisCount: number;
  /** 🔴 数据截至时刻。 */
  dataAsOf: string | null;
  status: string;
  message: string;
  startedAt: string;
  finishedAt: string | null;
  createdAt: string;
}

export interface AdDiagnosisRunPage {
  items: AdDiagnosisRunRow[];
  total: number;
  page: number;
  size: number;
}

/** 🔴 逐条规则的评估结论：被跳过的也在，写明差在哪。 */
export interface AdDiagnosisRunRuleRow {
  id: string;
  runId: string;
  ruleId: string;
  ruleCode: string;
  ruleType: string;
  severity: string;
  evaluated: boolean;
  skipReason: string;
  noMatchReason: string;
  matchedCampaignCount: number;
  createdAt: string;
}

export interface AdDiagnosisRow {
  id: string;
  runId: string;
  ruleId: string;
  ruleCode: string;
  ruleType: string;
  severity: string;
  campaignId: string;
  campaignName: string;
  platformCode: string;
  shopCode: string;
  status: string;
  windowFrom: string;
  windowTo: string;
  metricDays: number;
  impressions: AdMoney;
  clicks: AdMoney;
  orders: AdMoney;
  spend: AdMoney;
  sales: AdMoney;
  /** 🔴 ACOS = 消耗 / 销售额；null = 不可比。 */
  acos: AdRatio;
  roas: AdRatio;
  ctr: AdRatio;
  dailyBudget: AdMoney;
  /** 🔴 预算利用率 = 日均花费 / 日预算。 */
  budgetUtilization: AdRatio;
  /** 🔴 曝光相对倍数 = 日均曝光 / 同平台在投活动中位数。 */
  deliveryRatio: AdRatio;
  overTargetDays: number;
  title: string;
  /** 🔴 判定依据。 */
  evidence: string;
  /** 🔴 可执行建议。 */
  recommendation: string;
  createdAt: string;
  updatedAt: string;
}

export interface AdDiagnosisPage {
  items: AdDiagnosisRow[];
  total: number;
  page: number;
  size: number;
  openCount: number;
  criticalCount: number;
  emptyReason: string | null;
  emptyNote: string;
  metricDefinitionNote: string;
}

export interface AdDiagnosisActionRow {
  id: string;
  diagnosisId: string;
  ruleId: string;
  campaignId: string;
  campaignName: string;
  actionType: string;
  /** 🔴 WRITE 本模块可写 / NAVIGATE 去已交付模块 / UNAVAILABLE 依赖未交付模块。 */
  actionMode: string;
  available: boolean;
  ownerModule: string;
  impact: string;
  targetModule: string;
  targetPath: string;
  actionPercent: AdRatio;
  actionAmount: AdMoney;
  beforeValue: AdMoney;
  /** 🔴 「本来会变成多少」。 */
  afterValue: AdMoney;
  outcome: string;
  message: string;
  guardrailNote: string;
  dryRun: boolean;
  requestedBy: string | null;
  appliedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AdDiagnosisActionPage {
  items: AdDiagnosisActionRow[];
  total: number;
  page: number;
  size: number;
  availableCount: number;
  appliedCount: number;
  rejectedCount: number;
  dryRunCount: number;
  unavailableCount: number;
}

export interface AdDiagnosisRunDetail {
  run: AdDiagnosisRunRow;
  ruleResults: AdDiagnosisRunRuleRow[];
  diagnoses: AdDiagnosisRow[];
  actions: AdDiagnosisActionRow[];
  note: string;
}

export interface AdDiagnosisDetail {
  diagnosis: AdDiagnosisRow;
  actions: AdDiagnosisActionRow[];
  note: string;
}

export interface AdDiagnosisApplyResult {
  action: AdDiagnosisActionRow;
  outcome: string;
  message: string;
  guardrailNote: string;
  note: string;
}

export interface AdDiagnosisQuery {
  ruleType?: string | undefined;
  severity?: string | undefined;
  status?: string | undefined;
  campaignId?: string | undefined;
  runId?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface AdDiagnosisActionQuery {
  outcome?: string | undefined;
  actionMode?: string | undefined;
  available?: boolean | undefined;
  diagnosisId?: string | undefined;
  runId?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 登记 / 改一条诊断规则的表单。 */
export interface AdDiagnosisRuleInput {
  id: string;
  ruleCode: string;
  ruleName: string;
  ruleType: string;
  severity: string;
  enabled: boolean;
  lookbackDays: string;
  minMetricDays: string;
  thresholdAmount: string;
  thresholdRatio: string;
  minOverTargetDays: string;
  budgetUtilizationRatio: string;
  deliveryRatio: string;
  actionType: string;
  actionPercent: string;
  actionAmount: string;
  secondaryActionType: string;
  secondaryActionPercent: string;
  secondaryActionAmount: string;
  dataSourceReady: boolean;
  dryRun: boolean;
  note: string;
}

export const emptyAdDiagnosisRuleInput: AdDiagnosisRuleInput = {
  id: '',
  ruleCode: '',
  ruleName: '',
  ruleType: 'HIGH_SPEND_NO_CONVERSION',
  severity: 'HIGH',
  enabled: true,
  lookbackDays: '14',
  minMetricDays: '3',
  thresholdAmount: '',
  thresholdRatio: '',
  minOverTargetDays: '',
  budgetUtilizationRatio: '',
  deliveryRatio: '',
  actionType: 'PAUSE_CAMPAIGN',
  actionPercent: '',
  actionAmount: '',
  secondaryActionType: '',
  secondaryActionPercent: '',
  secondaryActionAmount: '',
  dataSourceReady: true,
  dryRun: true,
  note: '',
};

function queryString(query: object): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

// ==================================================================
// 取数
// ==================================================================

export function fetchAdDiagnosisCapabilities(): Promise<AdDiagnosisCapabilities> {
  return api<AdDiagnosisCapabilities>('channel/ad-diagnostics/capabilities');
}

export function fetchAdDiagnosisRules(
  ruleType?: string,
  enabled?: boolean,
  page = 0,
  size = 50,
): Promise<AdDiagnosisRulePage> {
  return api<AdDiagnosisRulePage>(
    `channel/ad-diagnostics/rules${queryString({ ruleType, enabled, page, size })}`,
  );
}

export function fetchAdDiagnosisRule(id: string): Promise<AdDiagnosisRuleRow> {
  return api<AdDiagnosisRuleRow>(`channel/ad-diagnostics/rules/${id}`);
}

export function saveAdDiagnosisRule(input: AdDiagnosisRuleInput): Promise<AdDiagnosisRuleRow> {
  const numberOrNull = (value: string): number | null =>
    value.trim() === '' ? null : Number(value.trim());
  return api<AdDiagnosisRuleRow>('channel/ad-diagnostics/rules', 'POST', {
    id: input.id.trim() === '' ? null : input.id.trim(),
    ruleCode: input.ruleCode.trim(),
    ruleName: input.ruleName.trim(),
    ruleType: input.ruleType,
    severity: input.severity,
    enabled: input.enabled,
    lookbackDays: Number(input.lookbackDays),
    minMetricDays: Number(input.minMetricDays),
    thresholdAmount: numberOrNull(input.thresholdAmount),
    thresholdRatio: numberOrNull(input.thresholdRatio),
    minOverTargetDays:
      input.minOverTargetDays.trim() === '' ? null : Number(input.minOverTargetDays.trim()),
    budgetUtilizationRatio: numberOrNull(input.budgetUtilizationRatio),
    deliveryRatio: numberOrNull(input.deliveryRatio),
    actionType: input.actionType,
    actionPercent: numberOrNull(input.actionPercent),
    actionAmount: numberOrNull(input.actionAmount),
    secondaryActionType: input.secondaryActionType.trim() === '' ? null : input.secondaryActionType,
    secondaryActionPercent: numberOrNull(input.secondaryActionPercent),
    secondaryActionAmount: numberOrNull(input.secondaryActionAmount),
    dataSourceReady: input.dataSourceReady,
    dryRun: input.dryRun,
    note: input.note.trim(),
  });
}

export function setAdDiagnosisRuleStatus(id: string, status: string): Promise<AdDiagnosisRuleRow> {
  return api<AdDiagnosisRuleRow>(`channel/ad-diagnostics/rules/${id}/status?status=${status}`, 'POST');
}

/** 为本租户初始化默认诊断规则（幂等：已存在的不覆盖）。 */
export function initAdDiagnosisRuleDefaults(): Promise<AdDiagnosisRuleDefaultsResult> {
  return api<AdDiagnosisRuleDefaultsResult>('channel/ad-diagnostics/rules/defaults', 'POST');
}

export function fetchAdDiagnosisRuns(page = 0, size = 20): Promise<AdDiagnosisRunPage> {
  return api<AdDiagnosisRunPage>(`channel/ad-diagnostics/runs${queryString({ page, size })}`);
}

export function fetchAdDiagnosisRun(id: string): Promise<AdDiagnosisRunDetail> {
  return api<AdDiagnosisRunDetail>(`channel/ad-diagnostics/runs/${id}`);
}

/** 跑一次诊断。 */
export function runAdDiagnosis(
  lookbackDays: number | null,
  dryRun = true,
): Promise<AdDiagnosisRunDetail> {
  return api<AdDiagnosisRunDetail>('channel/ad-diagnostics/runs', 'POST', {
    lookbackDays,
    dryRun,
    note: '',
  });
}

export function fetchAdDiagnoses(query: AdDiagnosisQuery = {}): Promise<AdDiagnosisPage> {
  return api<AdDiagnosisPage>(`channel/ad-diagnostics/diagnoses${queryString(query)}`);
}

export function fetchAdDiagnosis(id: string): Promise<AdDiagnosisDetail> {
  return api<AdDiagnosisDetail>(`channel/ad-diagnostics/diagnoses/${id}`);
}

export function fetchAdDiagnosisActions(
  query: AdDiagnosisActionQuery = {},
): Promise<AdDiagnosisActionPage> {
  return api<AdDiagnosisActionPage>(`channel/ad-diagnostics/actions${queryString(query)}`);
}

export function fetchAdDiagnosisAction(id: string): Promise<AdDiagnosisActionRow> {
  return api<AdDiagnosisActionRow>(`channel/ad-diagnostics/actions/${id}`);
}

/** 🔴 一键调整。`dryRun=true` 只算「本来会变成多少」，不真的调。 */
export function applyAdDiagnosisAction(
  id: string,
  dryRun: boolean,
  reason: string,
): Promise<AdDiagnosisApplyResult> {
  return api<AdDiagnosisApplyResult>(`channel/ad-diagnostics/actions/${id}/apply`, 'POST', {
    dryRun,
    reason,
  });
}

// ==================================================================
// 展示
// ==================================================================

/** 🔴 null 一律显示「不可比」，**绝不渲染成 0**。 */
export function ratioText(value: AdRatio | undefined, asPercent = true): string {
  if (value === null || value === undefined) return '不可比';
  return asPercent ? `${(value * 100).toFixed(2)}%` : value.toFixed(4);
}

export function money(value: AdMoney | undefined, currency?: string): string {
  if (value === null || value === undefined) return '—';
  const fixed = value.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

export function quantity(value: AdMoney | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
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

/** 判定口径（五种问题）的中文名。 */
export function ruleTypeLabel(ruleType: string): string {
  switch (ruleType) {
    case 'HIGH_SPEND_NO_CONVERSION':
      return '高花费零转化';
    case 'ACOS_OVER_TARGET':
      return 'ACOS 长期超标';
    case 'BUDGET_CAPPED':
      return '预算受限';
    case 'LOW_DELIVERY':
      return '竞价过低曝光不足';
    case 'CREATIVE_DECAY':
      return '创意衰退';
    default:
      return ruleType;
  }
}

export function severityLabel(severity: string): string {
  switch (severity) {
    case 'CRITICAL':
      return '严重';
    case 'HIGH':
      return '高';
    case 'MEDIUM':
      return '中';
    case 'LOW':
      return '低';
    default:
      return severity;
  }
}

export function severityTone(severity: string): string {
  switch (severity) {
    case 'CRITICAL':
      return 'text-destructive';
    case 'HIGH':
      return 'text-amber-600';
    case 'MEDIUM':
      return 'text-sky-600';
    default:
      return 'text-muted-foreground';
  }
}

/** 建议动作的中文名。 */
export function actionTypeLabel(actionType: string): string {
  switch (actionType) {
    case 'PAUSE_CAMPAIGN':
      return '暂停活动';
    case 'INCREASE_BUDGET':
      return '提高预算';
    case 'DECREASE_BUDGET':
      return '降低预算';
    case 'INCREASE_BID':
      return '提高竞价';
    case 'OPTIMIZE_KEYWORDS':
      return '关键词层优化';
    case 'NONE':
      return '不给动作';
    default:
      return actionType;
  }
}

/** 🔴 动作模式：可用 / 去别的模块处理 / 依赖未交付模块。 */
export function actionModeLabel(mode: string): string {
  switch (mode) {
    case 'WRITE':
      return '本模块可执行';
    case 'NAVIGATE':
      return '去对应模块处理';
    case 'UNAVAILABLE':
      return '暂不可用';
    default:
      return mode;
  }
}

export function outcomeLabel(outcome: string): string {
  switch (outcome) {
    case 'NOT_APPLIED':
      return '待执行';
    case 'DRY_RUN':
      return '试运行（只算不调）';
    case 'APPLIED':
      return '已生效（本地）';
    case 'REJECTED':
      return '被护栏拒绝';
    case 'SKIPPED':
      return '已跳过';
    case 'UNAVAILABLE':
      return '不可用';
    default:
      return outcome;
  }
}

export function outcomeTone(outcome: string): string {
  switch (outcome) {
    case 'APPLIED':
      return 'text-emerald-600';
    case 'REJECTED':
      return 'text-destructive';
    case 'DRY_RUN':
      return 'text-sky-600';
    case 'SKIPPED':
      return 'text-muted-foreground';
    case 'UNAVAILABLE':
      return 'text-amber-600';
    default:
      return 'text-muted-foreground';
  }
}

export function diagnosisStatusLabel(status: string): string {
  switch (status) {
    case 'OPEN':
      return '待处理';
    case 'ACTIONED':
      return '已按建议处理';
    case 'DISMISSED':
      return '已忽略';
    case 'STALE':
      return '数据已过期';
    default:
      return status;
  }
}

/** 🔴 空态：把「没规则 / 没数据 / 没发现 / 被筛掉」分开说。 */
export function emptyStateText(reason: string | null, note: string): string {
  switch (reason) {
    case 'NO_RULES':
      return '还没有诊断规则 —— 先初始化默认规则或逐条配置，规则是诊断的输入。';
    case 'NO_DATA':
      return '还没有广告每日指标（活动列表还没同步到数据）—— 没有事实就没有诊断结论。';
    case 'NO_FINDINGS':
      return '已经跑过诊断且没有发现效率问题 —— 这是一个结论，不是「没数据」。';
    case 'FILTERED_EMPTY':
      return '有诊断结论，但被当前的筛选条件全部排除了。';
    default:
      return note || '暂无数据。';
  }
}

/** 建议幅度的展示：比例优先，没有比例才看绝对额。 */
export function actionAmountText(
  percent: AdRatio,
  amount: AdMoney,
): string {
  if (percent !== null && percent !== undefined) return `${percent.toFixed(2)}%`;
  if (amount !== null && amount !== undefined) return amount.toFixed(2);
  return '—';
}

/** 🔴 一键调整按钮能不能点：不可用 / 要去别的模块 ⇒ 不给一个点了没用的按钮。 */
export function actionExecutable(action: AdDiagnosisActionRow): boolean {
  return action.available && action.actionMode === 'WRITE';
}

/** 🔴 不可用时界面上要显示的那句话（含责任模块）。 */
export function unavailableText(action: AdDiagnosisActionRow): string {
  if (action.actionMode === 'UNAVAILABLE') {
    return `待 ${action.ownerModule || '下游模块'} 交付后开放`;
  }
  if (action.actionMode === 'NAVIGATE') {
    return `请到 ${action.targetModule || '对应模块'} 处理`;
  }
  return '暂不可用';
}

// ==================================================================
// 校验
// ==================================================================

/**
 * 诊断规则表单校验。
 *
 * 🔴 阈值**没有「不填」这一档**：每种判定口径用到的阈值是必填的（服务端 + DB 双重把关），
 * 「一条没有阈值的规则」永远判不出东西，留着只会让人以为它在生效。
 */
export function validateAdDiagnosisRule(input: AdDiagnosisRuleInput): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.ruleCode.trim()) errors.push('规则编码必填（租户内唯一）');
  if (!input.ruleType) errors.push('判定口径必填');

  const lookback = Number(input.lookbackDays);
  if (!Number.isInteger(lookback) || lookback < 1 || lookback > 365) {
    errors.push('观察窗口必须是 1..365 的整数天');
  }
  const minDays = Number(input.minMetricDays);
  if (!Number.isInteger(minDays) || minDays < 1 || minDays > 365) {
    errors.push('最少需要的天数必须是 1..365 的整数天');
  } else if (Number.isInteger(lookback) && minDays > lookback) {
    errors.push('最少需要的天数不能大于观察窗口');
  }

  const numeric = (value: string, label: string, allowZero = true): number | null => {
    if (value.trim() === '') return null;
    const parsed = Number(value.trim());
    if (Number.isNaN(parsed)) {
      errors.push(`${label}必须是数字`);
      return null;
    }
    if (parsed < 0 || (!allowZero && parsed === 0)) {
      errors.push(`${label}不能为负`);
      return null;
    }
    return parsed;
  };

  // 🔴 阈值按判定口径分别必填
  switch (input.ruleType) {
    case 'HIGH_SPEND_NO_CONVERSION':
      if (numeric(input.thresholdAmount, '金额门槛') === null && input.thresholdAmount.trim() !== '') {
        // 数字非法已在上面报过
      } else if (input.thresholdAmount.trim() === '') {
        errors.push('高花费零转化规则必须给出金额门槛');
      }
      break;
    case 'ACOS_OVER_TARGET':
      if (input.thresholdRatio.trim() === '') errors.push('ACOS 超标规则必须给出目标 ACOS');
      else numeric(input.thresholdRatio, '目标 ACOS');
      if (input.minOverTargetDays.trim() === '') {
        errors.push('ACOS 超标规则必须给出「超标天数」下限（否则「长期」无从判断）');
      }
      break;
    case 'BUDGET_CAPPED':
      if (input.budgetUtilizationRatio.trim() === '') {
        errors.push('预算受限规则必须给出预算利用率下限');
      } else numeric(input.budgetUtilizationRatio, '预算利用率下限');
      break;
    case 'LOW_DELIVERY':
      if (input.deliveryRatio.trim() === '') {
        errors.push('曝光不足规则必须给出曝光相对下限');
      } else numeric(input.deliveryRatio, '曝光相对下限');
      break;
    case 'CREATIVE_DECAY':
      if (input.dataSourceReady) {
        errors.push('创意衰退的数据源尚未就绪，不许标成「已就绪」');
      }
      break;
    default:
      break;
  }

  // 🔴 改金额的动作必须带幅度；暂停 / 去别的模块的动作不得带幅度
  if (input.actionType === 'INCREASE_BUDGET' || input.actionType === 'DECREASE_BUDGET') {
    if (input.actionPercent.trim() === '' && input.actionAmount.trim() === '') {
      errors.push('调整预算的动作必须给出建议幅度（比例或绝对额）');
    }
  } else if (input.actionType !== 'NONE') {
    if (input.actionPercent.trim() !== '' || input.actionAmount.trim() !== '') {
      errors.push('暂停 / 关键词层优化的动作不携带建议幅度');
    }
  }

  return { ok: errors.length === 0, errors };
}
