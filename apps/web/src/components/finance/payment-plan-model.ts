import type {
  FinancePaymentBatchPage,
  FinancePaymentForecast,
  FinancePaymentMilestoneRuleInput,
  FinancePaymentMilestoneRuleRow,
  FinancePaymentPlanCancelInput,
  FinancePaymentPlanCapabilities,
  FinancePaymentPlanDetail,
  FinancePaymentPlanGenerateInput,
  FinancePaymentPlanOptions,
  FinancePaymentPlanPage,
  FinancePaymentPlanRow,
  FinancePaymentPlanScheduleInput,
  FinancePaymentPlanScheduleResult,
  FinancePaymentReminderAcknowledgeInput,
  FinancePaymentReminderDismissInput,
  FinancePaymentReminderPage,
  FinancePaymentReminderRow,
  FinancePaymentReminderScanResult,
  FinancePaymentTermRuleInput,
  FinancePaymentTermRuleRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.1.2.2 付款计划的前端模型。
 *
 * <p>蓝图 12-finance.md：「按付款条件（账期、里程碑、到期日）生成付款计划与到期提醒，
 * 支持按供应商与到期窗口批量安排付款。」输出：「付款计划表 + 到期提醒 + 资金需求预测。」
 * 加粗句：「<b>付款计划与资金预测联动</b>：跨境资金常有回款延迟与付款刚性，错配会导致现金流断裂。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>金额只原样展示</b>：计划金额、汇总、预测的流入 / 流出 / 净额都由**服务端**算好，
 *       页面**不做任何前端算术**（USD 与 EUR 加起来不是任何一个金额）。</li>
 *   <li><b>币种分开</b>：汇总、预测、提醒金额一律按币种分组渲染，
 *       **绝不**把不同币种摆进同一列相加。</li>
 *   <li><b>三种付款条件要能分开看</b>：账期 / 里程碑 / 到期日是**三种算法**，
 *       页面必须显示「这条计划是按什么条件算出来的」以及条件日与计划日是否一致。</li>
 *   <li><b>「已安排」不等于「已付款」</b>：付款属 12.2.2.1（未交付），
 *       页面**不提供**任何「标记已付」「执行付款」的按钮 —— 那会是一条假的付款路径。</li>
 *   <li><b>提醒没推送就说没推送</b>：推送通道属 1.3（未交付），
 *       页面**不显示**「已通知」这类不存在的状态。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有付款计划」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code finance/payment-plans}，与 12.1.2.1 的 {@code finance/payables}
 * 以及同服务的其余模块各自独立前缀。
 */

const base = 'finance/payment-plans';

/**
 * 付款计划列表的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface PaymentPlanParams {
  status?: string | undefined;
  paymentCondition?: string | undefined;
  currency?: string | undefined;
  counterpartyRef?: string | undefined;
  batchId?: string | undefined;
  overdueOnly?: boolean | undefined;
  paidFrom?: string | undefined;
  paidTo?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 到期提醒的筛选条件。 */
export interface PaymentReminderParams {
  status?: string | undefined;
  reminderType?: string | undefined;
  currency?: string | undefined;
  counterpartyRef?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

function query(params: Record<string, string | number | boolean | undefined>): string {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    if (typeof value === 'string' && value === '') continue;
    parts.push(`${key}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 端点

/** 能力声明：哪些真接通、哪些还没交付、归谁。 */
export function fetchPaymentPlanCapabilities() {
  return api<FinancePaymentPlanCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（全部来自真实数据）。 */
export function fetchPaymentPlanOptions() {
  return api<FinancePaymentPlanOptions>(`${base}/options`);
}

/** 付款计划列表：真分页 + 真筛选 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。 */
export function fetchPaymentPlans(params: PaymentPlanParams = {}) {
  return api<FinancePaymentPlanPage>(`${base}${query({ ...params })}`);
}

/** 付款计划详情：计划 + 事件流 + 当前提醒。 */
export function fetchPaymentPlan(id: string) {
  return api<FinancePaymentPlanDetail>(`${base}/${encodeURIComponent(id)}`);
}

/** 付款批次列表（真分页 + 真筛选）。 */
export function fetchPaymentBatches(
  params: {
    status?: string | undefined;
    counterpartyRef?: string | undefined;
    currency?: string | undefined;
  } = {},
  page = 0,
  size = 50,
) {
  return api<FinancePaymentBatchPage>(`${base}/batches${query({ ...params, page, size })}`);
}

/**
 * 生成付款计划（**只生成，不排批次**）。
 *
 * <p>⚠️ 它**不产生任何付款动作**：付款属 12.2.2.1（未交付）。
 */
export function generatePaymentPlans(input: FinancePaymentPlanGenerateInput) {
  return api<FinancePaymentPlanScheduleResult>(`${base}/generate`, 'POST', input);
}

/**
 * 按供应商与到期窗口**批量安排付款**。
 *
 * <p>⚠️ 安排 = 把应付排进某个付款窗口 / 批次，**不是执行付款**。
 */
export function schedulePaymentPlans(input: FinancePaymentPlanScheduleInput) {
  return api<FinancePaymentPlanScheduleResult>(`${base}/schedule`, 'POST', input);
}

/** 取消一条计划。理由必填。取消不是删除：痕迹要留着。 */
export function cancelPaymentPlan(id: string, reason: string) {
  return api<FinancePaymentPlanRow>(`${base}/${encodeURIComponent(id)}/cancel`, 'POST', {
    reason,
  } as FinancePaymentPlanCancelInput);
}

/**
 * 资金需求预测（**实时聚合**：按币种 × 到期窗口，流入 − 流出）。
 *
 * <p>⚠️ 计划改了预测当场跟着变 —— 它不是一张静态表。
 */
export function fetchPaymentForecast(
  params: {
    from?: string | undefined;
    to?: string | undefined;
    windowDays?: number | undefined;
  } = {},
) {
  return api<FinancePaymentForecast>(`${base}/forecast${query({ ...params })}`);
}

/** 到期提醒清单（真分页 + 真筛选）。 */
export function fetchPaymentReminders(params: PaymentReminderParams = {}) {
  return api<FinancePaymentReminderPage>(`${base}/reminders${query({ ...params })}`);
}

/**
 * 扫描在册计划，生成 / 更新 / 失效到期提醒。
 *
 * <p>⚠️ **不推送任何东西**：推送通道属 1.3（未交付），提醒的 pushStatus 恒为 NOT_PUSHED。
 */
export function scanPaymentReminders(
  params: { asOf?: string | undefined; horizonDays?: number | undefined } = {},
) {
  return api<FinancePaymentReminderScanResult>(
    `${base}/reminders/scan${query({ ...params })}`,
    'POST',
  );
}

/** 确认一条到期提醒。 */
export function acknowledgePaymentReminder(id: string, reason?: string) {
  return api<FinancePaymentReminderRow>(
    `${base}/reminders/${encodeURIComponent(id)}/acknowledge`,
    'POST',
    { reason: reason ?? null } as FinancePaymentReminderAcknowledgeInput,
  );
}

/** 忽略一条到期提醒。理由必填。 */
export function dismissPaymentReminder(id: string, reason: string) {
  return api<FinancePaymentReminderRow>(
    `${base}/reminders/${encodeURIComponent(id)}/dismiss`,
    'POST',
    { reason } as FinancePaymentReminderDismissInput,
  );
}

/** 账期规则清单（**租户自己维护**，系统不预置任何一行）。 */
export function fetchPaymentTermRules() {
  return api<FinancePaymentTermRuleRow[]>(`${base}/term-rules`);
}

/** 新建一条账期规则。 */
export function createPaymentTermRule(input: FinancePaymentTermRuleInput) {
  return api<FinancePaymentTermRuleRow>(`${base}/term-rules`, 'POST', input);
}

/** 改一条账期规则。 */
export function updatePaymentTermRule(id: string, input: FinancePaymentTermRuleInput) {
  return api<FinancePaymentTermRuleRow>(
    `${base}/term-rules/${encodeURIComponent(id)}`,
    'PUT',
    input,
  );
}

/** 里程碑规则清单（含里程碑明细）。 */
export function fetchPaymentMilestoneRules() {
  return api<FinancePaymentMilestoneRuleRow[]>(`${base}/milestone-rules`);
}

/** 新建一套里程碑规则（整套一起落；比例之和必须为 1）。 */
export function createPaymentMilestoneRule(input: FinancePaymentMilestoneRuleInput) {
  return api<FinancePaymentMilestoneRuleRow>(`${base}/milestone-rules`, 'POST', input);
}

/** 改一套里程碑规则（里程碑整套替换）。 */
export function updatePaymentMilestoneRule(id: string, input: FinancePaymentMilestoneRuleInput) {
  return api<FinancePaymentMilestoneRuleRow>(
    `${base}/milestone-rules/${encodeURIComponent(id)}`,
    'PUT',
    input,
  );
}

// ================================================================ 可读形态

/** 付款条件的可读名。 */
export function paymentConditionLabel(condition: string): string {
  switch (condition) {
    case 'TERM':
      return '账期';
    case 'MILESTONE':
      return '里程碑';
    case 'DUE_DATE':
      return '到期日';
    default:
      return condition;
  }
}

/**
 * 付款条件的**口径说明**。
 *
 * <p>⚠️ 三种条件不是同一个东西：账期要规则、里程碑要拆条、到期日才是直接取上游事实。
 * 页面必须让人一眼看出「这条计划是按什么算出来的」。
 */
export function paymentConditionHint(condition: string): string {
  switch (condition) {
    case 'TERM':
      return '起算基准日 + 账期规则的天数';
    case 'MILESTONE':
      return '按里程碑比例拆成多条';
    case 'DUE_DATE':
      return '直接取应付单的到期日';
    default:
      return condition;
  }
}

/** 付款计划状态的可读名。 */
export function paymentPlanStatusLabel(status: string): string {
  switch (status) {
    case 'PLANNED':
      return '已生成';
    case 'SCHEDULED':
      return '已安排';
    case 'CANCELLED':
      return '已取消';
    default:
      return status;
  }
}

/**
 * 付款计划状态的可读色调。
 *
 * <p>⚠️ 「已安排」**不是**「已付款」—— 它只表示这笔钱被排进了某个付款窗口。
 * 页面绝不能把已安排渲染成成功态（绿色），否则会被读成「钱已经付出去了」。
 */
export function paymentPlanStatusTone(
  status: string,
): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'PLANNED':
      return 'warning';
    case 'SCHEDULED':
      return 'warning';
    case 'CANCELLED':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 提醒类型的可读名。 */
export function reminderTypeLabel(type: string): string {
  switch (type) {
    case 'UPCOMING':
      return '即将到期';
    case 'DUE':
      return '今日到期';
    case 'OVERDUE':
      return '已逾期';
    default:
      return type;
  }
}

/** 提醒类型的可读色调。 */
export function reminderTypeTone(type: string): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (type) {
    case 'UPCOMING':
      return 'warning';
    case 'DUE':
      return 'warning';
    case 'OVERDUE':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 提醒处理状态的可读名。 */
export function reminderStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '待处理';
    case 'ACKNOWLEDGED':
      return '已确认';
    case 'DISMISSED':
      return '已忽略';
    default:
      return status;
  }
}

/** 提醒处理状态的可读色调。 */
export function reminderStatusTone(status: string): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'PENDING':
      return 'warning';
    case 'ACKNOWLEDGED':
      return 'success';
    case 'DISMISSED':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 账期起算基准的可读名。 */
export function termBaseKindLabel(baseKind: string): string {
  switch (baseKind) {
    case 'CONFIRMED_DATE':
      return '确认日';
    case 'DUE_DATE':
      return '到期日';
    default:
      return baseKind;
  }
}

/** 应付对象类型的可读名。 */
export function paymentCounterpartyTypeLabel(type: string): string {
  switch (type) {
    case 'SUPPLIER':
      return '供应商';
    case 'CARRIER':
      return '承运商';
    case 'PLATFORM':
      return '平台';
    case 'OTHER':
      return '其他';
    default:
      return type;
  }
}

/** 事件类型的可读名。 */
export function paymentPlanEventLabel(eventType: string): string {
  switch (eventType) {
    case 'GENERATED':
      return '生成计划';
    case 'SCHEDULED':
      return '排入付款窗口';
    case 'RESCHEDULED':
      return '重新排期';
    case 'CANCELLED':
      return '取消计划';
    case 'REMINDER_ACKNOWLEDGED':
      return '确认提醒';
    case 'REMINDER_DISMISSED':
      return '忽略提醒';
    default:
      return eventType;
  }
}

/**
 * 金额的可读形态。
 *
 * <p>⚠️ **不在前端做算术**：数值原样透传（只做显示格式化）。
 */
export function formatPaymentAmount(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '—';
  return amount.toFixed(4);
}

/** 金额 + 币种的可读形态。 */
export function formatPaymentMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '—';
  return `${amount.toFixed(4)} ${currency ?? ''}`.trim();
}

/**
 * 计划日与条件日的关系（**不**在前端算日期差）。
 *
 * <p>⚠️ 服务端把两个日期都给了，页面据此显示「按 X 算，压到 Y」——
 * 条件日与计划日不一致时**必须让人看见**，否则「为什么这笔钱提前/延后付」就答不出来。
 */
export function paymentPlanDateHint(plan: FinancePaymentPlanRow): string {
  if (!plan.clamped) return `计划付款日 ${plan.plannedPaidDate}`;
  return `条件日 ${plan.conditionDate} → 压入付款窗口 ${plan.plannedPaidDate}`;
}

/**
 * 里程碑比例的可读形态。
 *
 * <p>这里做的是**单位换算**（比例 → 百分数），不是业务算术。
 */
export function formatMilestoneRatio(ratio: number | null | undefined): string {
  if (ratio === null || ratio === undefined) return '—';
  return `${(ratio * 100).toFixed(2)}%`;
}

/**
 * 预测窗口的净额可读形态。
 *
 * <p>⚠️ 净额是**服务端**算好的（流入 − 流出）；这里只决定怎么显示，
 * 绝不在前端重算一遍。
 */
export function forecastNetHint(netAmount: number, gapFlag: boolean): string {
  const sign = netAmount > 0 ? '+' : '';
  return `${sign}${netAmount.toFixed(4)}${gapFlag ? '（净流出 · 现金流断裂风险）' : ''}`;
}

/**
 * 推送状态的可读形态。
 *
 * <p>⚠️ 推送通道属 1.3（未交付）⇒ 这个值**恒为**「未推送」。
 * 页面照实显示「未推送」，**不显示**任何「已通知」。
 */
export function reminderPushLabel(pushStatus: string): string {
  switch (pushStatus) {
    case 'NOT_PUSHED':
      return '未推送（推送通道属 1.3，未交付）';
    default:
      return pushStatus;
  }
}

/**
 * 付款执行的可读形态。
 *
 * <p>⚠️ 付款属 12.2.2.1（未交付）⇒ 这个值**恒为**「不可用」。
 */
export function paymentExecutionLabel(executionAvailable: boolean, ownerModule: string): string {
  if (executionAvailable) return `可执行（归属 ${ownerModule}）`;
  return `付款执行不可用（归属模块 ${ownerModule}）`;
}
