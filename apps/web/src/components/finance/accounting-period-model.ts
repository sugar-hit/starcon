import type {
  FinanceAccountingCalendarDetail,
  FinanceAccountingCalendarInput,
  FinanceAccountingCalendarRow,
  FinanceAccountingCalendarUpdateInput,
  FinanceAccountingPeriodCapabilities,
  FinanceAccountingPeriodCloseInput,
  FinanceAccountingPeriodDetail,
  FinanceAccountingPeriodGenerateInput,
  FinanceAccountingPeriodGenerateResult,
  FinanceAccountingPeriodInput,
  FinanceAccountingPeriodOptions,
  FinanceAccountingPeriodPage,
  FinanceAccountingPeriodReopenInput,
  FinanceAccountingPeriodReopenResult,
  FinanceAccountingPeriodRow,
  FinanceAccountingPeriodUpdateInput,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.5.1.2 会计期间的前端模型。
 *
 * <p>蓝图 12-finance.md：「管理会计期间（自然月或 4-4-5 fiscal 周期）：期间状态（开放/关闭）、
 * 关账与重开、期间内凭证限制。」输入：「会计日历配置；关账/重开操作；审批。」输出：「期间状态；关账记录。」
 * 关系：「上游 = 会计日历；下游 = 12.5.2.1 凭证（已关闭期间禁止过账）。**重开已关期间是高风险操作**，
 * 必须审批留痕。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>期间身份只读</b>：期间编码 / 起止日 / 本位币 / 会计日历 / fiscal 周期**一经入账不可改**
 *       （凭证按期间引用它，改了等于改账期）⇒ 页面**不提供**改这些字段的输入框，只显示它们。</li>
 *   <li><b>重开不是一次点击的事</b>：重开走 17 审批（identity）⇒ 页面把**四种结局**都如实显示
 *       （已提交申请 / 审批中 / 已驳回 / 已重开），**绝不**显示成「已重开」了事。</li>
 *   <li><b>关账必须写原因</b>：没有原因就不让提交（服务端也会拒）。</li>
 *   <li><b>关账检查清单不是本页的事</b>：属 12.5.1.3（未交付）⇒ 页面按能力声明如实说明，
 *       <b>不假装</b>跑过清单。</li>
 *   <li>不可执行的动作把**服务端给的阻断原因**显示出来，而不是把按钮灰掉让人猜。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有期间」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code finance/accounting-periods}，与同服务的其它端点首 token 全不相同
 * （{@code receivables} / {@code settlement} / {@code transactions} / {@code vouchers} …）。
 */

/** 后端命名空间（与 api-proxy 白名单里的首 token 一致）。 */
const base = 'finance/accounting-periods';

/**
 * 期间列表的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface AccountingPeriodParams {
  status?: string | undefined;
  periodType?: string | undefined;
  calendarId?: string | undefined;
  fiscalYear?: number | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 期间类型的可选值（与契约里的枚举一致）。 */
export type AccountingPeriodTypeValue = 'CALENDAR_MONTH' | 'FISCAL_445' | 'CUSTOM';

/** 会计日历类型的可选值（与契约里的枚举一致）。 */
export type AccountingCalendarTypeValue = 'CALENDAR_MONTH' | 'FISCAL_445' | 'CUSTOM';

/** 重开结局（与后端的 outcome 一一对应）。 */
export type AccountingReopenOutcome =
  | 'REOPEN_REQUESTED'
  | 'PENDING_APPROVAL'
  | 'REJECTED'
  | 'REOPENED';

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
export function fetchAccountingPeriodCapabilities() {
  return api<FinanceAccountingPeriodCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（全部来自真实数据）。 */
export function fetchAccountingPeriodOptions() {
  return api<FinanceAccountingPeriodOptions>(`${base}/options`);
}

/** 期间列表：真分页 + 真筛选 + 状态汇总。 */
export function fetchAccountingPeriods(params: AccountingPeriodParams = {}) {
  return api<FinanceAccountingPeriodPage>(`${base}${query({ ...params })}`);
}

/** 期间详情：期间 + 关账记录 + 审批链路。 */
export function fetchAccountingPeriod(id: string) {
  return api<FinanceAccountingPeriodDetail>(`${base}/${encodeURIComponent(id)}`);
}

/** 期间事件留痕（＝ 蓝图输出里的「关账记录」）。 */
export function fetchAccountingPeriodEvents(id: string) {
  return api<FinanceAccountingPeriodDetail['events']>(
    `${base}/${encodeURIComponent(id)}/events`,
  );
}

/** 期间的审批链路（identity 链路的本地镜像）。 */
export function fetchAccountingPeriodApprovals(id: string) {
  return api<FinanceAccountingPeriodDetail['approvals']>(
    `${base}/${encodeURIComponent(id)}/approvals`,
  );
}

/** 手工登记一个自定义期间（不绑定会计日历 ⇒ 没有 fiscal 周期）。 */
export function createAccountingPeriod(input: FinanceAccountingPeriodInput) {
  return api<FinanceAccountingPeriodRow>(`${base}`, 'POST', input);
}

/** 改期间备注（⚠️ 期间身份字段不可改）。 */
export function updateAccountingPeriod(id: string, input: FinanceAccountingPeriodUpdateInput) {
  return api<FinanceAccountingPeriodRow>(`${base}/${encodeURIComponent(id)}`, 'PUT', input);
}

/** 关闭一个会计期间（必须写明关闭原因）。 */
export function closeAccountingPeriod(id: string, input: FinanceAccountingPeriodCloseInput) {
  return api<FinanceAccountingPeriodRow>(`${base}/${encodeURIComponent(id)}/close`, 'POST', input);
}

/**
 * 重开一个已关闭的会计期间。
 *
 * <p>⚠️ **四种结局都要看**：返回的 `outcome` 决定期间到底开没开 ——
 * `REOPEN_REQUESTED` / `PENDING_APPROVAL` / `REJECTED` 三种期间**仍然关闭**。
 */
export function reopenAccountingPeriod(id: string, input: FinanceAccountingPeriodReopenInput) {
  return api<FinanceAccountingPeriodReopenResult>(
    `${base}/${encodeURIComponent(id)}/reopen`,
    'POST',
    input,
  );
}

/** 会计日历清单。 */
export function fetchAccountingCalendars() {
  return api<FinanceAccountingCalendarRow[]>(`${base}/calendars`);
}

/** 日历详情：日历 + 它生成的期间。 */
export function fetchAccountingCalendar(id: string) {
  return api<FinanceAccountingCalendarDetail>(`${base}/calendars/${encodeURIComponent(id)}`);
}

/** 建会计日历（自然月 / 4-4-5 / 自定义）。 */
export function createAccountingCalendar(input: FinanceAccountingCalendarInput) {
  return api<FinanceAccountingCalendarRow>(`${base}/calendars`, 'POST', input);
}

/** 改会计日历（⚠️ 账期形状不可改 —— 只有名称 / 状态 / 备注可改）。 */
export function updateAccountingCalendar(
  id: string,
  input: FinanceAccountingCalendarUpdateInput,
) {
  return api<FinanceAccountingCalendarRow>(`${base}/calendars/${encodeURIComponent(id)}`, 'PUT', input);
}

/** 从日历生成一个财年的期间。 */
export function generateAccountingPeriods(
  calendarId: string,
  input: FinanceAccountingPeriodGenerateInput,
) {
  return api<FinanceAccountingPeriodGenerateResult>(
    `${base}/calendars/${encodeURIComponent(calendarId)}/generate-periods`,
    'POST',
    input,
  );
}

// ================================================================ 可读形态

/** 期间状态的可读名。 */
export function accountingPeriodStatusLabel(status: string): string {
  switch (status) {
    case 'OPEN':
      return '开放';
    case 'CLOSED':
      return '关闭';
    default:
      return status;
  }
}

/**
 * 期间状态的语义色。
 *
 * <p>🔴 **「已关闭」用 warning 而不是 success/neutral** —— 关闭不是「完成」也不是「坏了」，
 * 它是一个需要人盯着的状态（期间不能过账了）。
 */
export function accountingPeriodStatusTone(status: string): 'success' | 'warning' {
  return status === 'OPEN' ? 'success' : 'warning';
}

/** 期间类型的可读名。 */
export function accountingPeriodTypeLabel(type: string): string {
  switch (type) {
    case 'CALENDAR_MONTH':
      return '自然月';
    case 'FISCAL_445':
      return '4-4-5 周期';
    case 'CUSTOM':
      return '自定义';
    default:
      return type;
  }
}

/** 会计日历类型的可读名。 */
export function accountingCalendarTypeLabel(type: string): string {
  return accountingPeriodTypeLabel(type);
}

/** 期间区间的可读形态。 */
export function accountingPeriodRangeText(period: {
  periodStart: string;
  periodEnd: string;
  periodLengthDays: number;
}): string {
  return `${period.periodStart} ~ ${period.periodEnd}（${period.periodLengthDays} 天）`;
}

/**
 * fiscal 周期位置的可读形态。
 *
 * <p>⚠️ 自定义期间**没有** fiscal 周期 ⇒ 这里如实说「自定义」，不编一个出来。
 */
export function accountingFiscalPositionText(period: {
  periodType: string;
  fiscalYear: number | null;
  fiscalPeriodNo: number | null;
}): string {
  if (period.periodType === 'CUSTOM') return '自定义（不绑定会计日历）';
  if (period.fiscalYear === null || period.fiscalPeriodNo === null) return '—';
  return `财年 ${period.fiscalYear} 第 ${period.fiscalPeriodNo} 期`;
}

/**
 * 关账状态的可读形态。
 *
 * <p>🔴 **没有关过**与**关过又被重开**是两回事，界面必须分得清 ——
 * 「本期已关账（又被重开过 1 次）」和「本期开放」不是同一句话。
 */
export function accountingCloseStateText(period: {
  status: string;
  closedAt: string | null;
  closedReason: string;
  reopenCount: number;
}): string {
  if (period.status === 'CLOSED') {
    return `已关账${period.closedReason ? `（${period.closedReason}）` : ''}`;
  }
  if (period.reopenCount > 0) {
    return `开放（曾关账，已重开 ${period.reopenCount} 次）`;
  }
  return '开放';
}

/**
 * 重开结局的可读形态。
 *
 * <p>⚠️ **四种结局必须分开说**：只有 `REOPENED` 才是真的开了，
 * 其余三种期间**仍然关闭** —— 说成「已重开」就是撒谎。
 */
export function accountingReopenOutcomeText(outcome: string): string {
  switch (outcome) {
    case 'REOPEN_REQUESTED':
      return '重开申请已提交审批';
    case 'PENDING_APPROVAL':
      return '审批中';
    case 'REJECTED':
      return '审批已驳回';
    case 'REOPENED':
      return '已重开';
    default:
      return outcome;
  }
}

/**
 * 重开结局的语义色。
 *
 * <p>🔴 `REJECTED` 是 **danger**（被驳回了，得有人处理）；
 * `REOPEN_REQUESTED` / `PENDING_APPROVAL` 是 **warning**（在审，但期间还关着）。
 */
export function accountingReopenOutcomeTone(
  outcome: string,
): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (outcome) {
    case 'REOPENED':
      return 'success';
    case 'REOPEN_REQUESTED':
    case 'PENDING_APPROVAL':
      return 'warning';
    case 'REJECTED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/**
 * 重开是否真的执行了（只有这一种结局期间才真的开放）。
 *
 * <p>⚠️ 页面据此决定「能不能继续过账」的提示 —— 用别的结局说「已重开」等于撒谎。
 */
export function accountingReopenActuallyApplied(outcome: string): boolean {
  return outcome === 'REOPENED';
}

/**
 * 生成期间结果的可读形态。
 *
 * <p>⚠️ **被跳过的期间编码必须逐条显示** —— 「生成了 12 个期间」如果不说清哪几个没进来，
 * 现场会以为整年都建好了。
 */
export function accountingPeriodGenerateSummary(result: {
  fiscalYear: number;
  created: number;
  skipped: number;
  skippedCodes: string[];
}): string {
  const skipped = result.skippedCodes.length
    ? `，跳过 ${result.skipped} 个（编码已存在：${result.skippedCodes.join('、')}）`
    : '';
  return `已按日历生成财年 ${result.fiscalYear} 的 ${result.created} 个期间${skipped}`;
}

/** 期间事件（留痕）一行的可读形态。 */
export function accountingPeriodEventText(event: {
  eventType: string;
  eventTypeLabel: string;
  fromStatus: string;
  toStatus: string;
  reason: string;
  detail: string;
}): string {
  const flow =
    event.fromStatus && event.toStatus
      ? `${accountingPeriodStatusLabel(event.fromStatus)} → ${accountingPeriodStatusLabel(
          event.toStatus,
        )}`
      : '';
  return [flow, event.reason, event.detail].filter(Boolean).join(' · ');
}

/** 审批动作的可读名。 */
export function accountingApprovalActionLabel(action: string): string {
  switch (action) {
    case 'SUBMIT':
      return '提交';
    case 'APPROVE':
      return '批准';
    case 'REJECT':
      return '驳回';
    case 'TRANSFER':
      return '转交';
    default:
      return action;
  }
}
