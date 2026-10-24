import { api } from '../../lib/api';

/**
 * 16.3.1.2 申报日历 前端模型。
 *
 * 蓝图：「管理各辖区申报日历：申报周期、截止日、所需数据、状态（未开始/数据就绪/已提交/已确认），
 * 到期提醒与责任人。输入：申报周期配置；辖区要求；12.6.1.2 数据就绪状态。
 * 输出：申报日历视图 + 提醒 + 状态跟踪。关系：上游 = 16.3.1.1、12.6.1.2；下游 = 1 工作台待办。
 * **漏报罚款远高于系统能提供的价值**，日历提醒是最低成本的合规保障。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「读不到」显示成「读不到」**：12.6.1.2 读不到时显示「读不到」并给出原因，
 *    **不显示成「数据没就绪」** —— 前者要去查上游，后者是「已经查过，确实没有」，
 *    处置动作完全相反。
 * 2. **逾期 / 临近截止排在最前**：漏报是本模块最贵的错误，前端**不自己重排**，
 *    但**按后端给的 overdue / daysToDue 如实标注**。
 * 3. **截止日显示成当地时刻**：德国截止日就是德国当地时间，不换算成浏览器时区
 *    —— 换算了就等于把「23:59 截止」显示成第二天早上，运营会以为还有时间。
 * 4. **「已提交 / 已确认」必须有凭据号**：前端在缺凭据号时**不给出推进入口**
 *    （系统不代造受理号）。
 * 5. **辖区从受控码表选，不自由输入**（值域钉死在 DB 层）。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有 channel 分支）。 */
export const BASE = 'channel/filing-calendar';

/** 状态词表（顺序与后端一致）。 */
export const STATUSES = [
  { value: '', label: '全部状态' },
  { value: 'NOT_STARTED', label: '未开始' },
  { value: 'DATA_READY', label: '数据就绪' },
  { value: 'SUBMITTED', label: '已提交' },
  { value: 'CONFIRMED', label: '已确认' },
] as const;

/** 申报线（税种）筛选项。 */
export const TAX_TYPES = [
  { value: '', label: '全部申报线' },
  { value: 'VAT', label: '增值税（VAT）' },
  { value: 'IOSS', label: 'IOSS（一站式商店）' },
  { value: 'SALES_TAX', label: '销售税' },
  { value: 'GST', label: '商品服务税（GST）' },
  { value: 'CONSUMPTION_TAX', label: '消费税' },
] as const;

/** 申报周期筛选项。 */
export const FILING_CYCLES = [
  { value: '', label: '全部周期' },
  { value: 'MONTHLY', label: '按月' },
  { value: 'QUARTERLY', label: '按季' },
  { value: 'SEMI_ANNUAL', label: '按半年' },
  { value: 'ANNUAL', label: '按年' },
] as const;

/** 提醒档位筛选项。 */
export const REMINDER_KINDS = [
  { value: '', label: '全部提醒' },
  { value: 'DUE_SOON', label: '即将到期' },
  { value: 'DUE', label: '截止日' },
] as const;

// ==================================================================
// 类型
// ==================================================================

/** 一个未交付的下游模块。 */
export interface FilingCalendarCapabilityGap {
  moduleId: string;
  moduleName: string;
  ownerModule: string;
  available: boolean;
  impact: string;
}

/** 一个上游的读取状态（`available=false` ⇒ 显示「读不到」，**不是**「没有」）。 */
export interface FilingCalendarUpstream {
  moduleId: string;
  moduleName: string;
  available: boolean;
  ownerModule: string;
  impact: string;
}

/** 能力声明。 */
export interface FilingCalendarCapabilities {
  gaps: FilingCalendarCapabilityGap[];
  upstreams: FilingCalendarUpstream[];
  taxTypes: string[];
  filingCycles: string[];
  statuses: string[];
  reminderKinds: string[];
  statusSemantics: string;
  jurisdictionSemantics: string;
  dataReadinessSemantics: string;
  ownerSemantics: string;
  upstreamNote: string;
  downstreamNote: string;
  scopeNote: string;
}

/** 一个销售辖区（受控值域里的一行）。 */
export interface FilingCalendarJurisdiction {
  code: string;
  name: string;
  region: string;
  taxRegime: string;
  defaultFilingCycle: string | null;
}

/** 一个可指派的责任人（租户内成员）。 */
export interface FilingCalendarMember {
  membershipId: string;
  username: string;
  status: string;
}

/** 责任人候选清单（`available=false` ⇒ 显示「读不到」）。 */
export interface FilingCalendarMemberList {
  items: FilingCalendarMember[];
  available: boolean;
  note: string;
}

/** 筛选项。 */
export interface FilingCalendarOptions {
  jurisdictions: FilingCalendarJurisdiction[];
  members: FilingCalendarMemberList;
  taxTypes: string[];
  filingCycles: string[];
  statuses: string[];
  reminderKinds: string[];
  statusLabels: Record<string, string>;
  note: string;
}

/** 申报周期配置的入参。 */
export interface FilingCalendarCycleConfigInput {
  jurisdictionCode: string;
  taxType: string;
  filingCycle: string;
  periodAnchorDay?: number;
  dueOffsetDays?: number;
  dueHour?: number;
  dueTimezone?: string;
  reminderLeadDays?: number;
  effectiveFrom?: string;
  status?: string;
  note?: string;
}

/** 一份申报周期配置。 */
export interface FilingCalendarCycleConfigView {
  id: string;
  jurisdictionCode: string;
  jurisdictionName: string;
  taxType: string;
  filingCycle: string;
  periodAnchorDay: number;
  dueOffsetDays: number;
  dueHour: number;
  dueTimezone: string;
  reminderLeadDays: number;
  effectiveFrom: string;
  status: string;
  note: string;
  createdAt: string;
  updatedAt: string;
}

/** 申报周期配置清单。 */
export interface FilingCalendarCycleConfigPage {
  items: FilingCalendarCycleConfigView[];
  total: number;
  page: number;
  size: number;
}

/** 一次申报需要凑齐的数据项。 */
export interface FilingCalendarRequiredData {
  code: string;
  label: string;
  required: boolean;
  source: string;
}

/** 申报日历条目的入参。 */
export interface FilingCalendarEntryInput {
  jurisdictionCode: string;
  taxType: string;
  filingCycle: string;
  periodStart?: string;
  periodEnd?: string;
  dueAt?: string;
  requiredData?: FilingCalendarRequiredData[];
  responsibleMembershipId?: string;
  reminderLeadDays?: number;
  note?: string;
}

/** 一条申报日历条目。 */
export interface FilingCalendarEntryView {
  id: string;
  jurisdictionCode: string;
  jurisdictionName: string;
  taxType: string;
  filingCycle: string;
  periodStart: string;
  periodEnd: string;
  dueAt: string;
  dueTimezone: string;
  status: string;
  statusLabel: string;
  statusAdvancedAt: string;
  requiredData: FilingCalendarRequiredData[];
  dataReady: boolean;
  dataReadyDraftId: string;
  dataReadinessAvailable: boolean;
  dataReadinessNote: string;
  dataReadyCheckedAt: string;
  responsibleMembershipId: string;
  responsibleName: string;
  responsibleAvailable: boolean;
  responsibleNote: string;
  reminderLeadDays: number;
  daysToDue: number | null;
  overdue: boolean;
  submittedAt: string;
  submittedBy: string;
  submissionReference: string;
  confirmedAt: string;
  confirmedBy: string;
  confirmationReference: string;
  note: string;
  createdAt: string;
  updatedAt: string;
}

/** 日历清单。 */
export interface FilingCalendarEntryPage {
  items: FilingCalendarEntryView[];
  total: number;
  page: number;
  size: number;
  overdueCount: number;
  dueSoonCount: number;
  notSubmittedCount: number;
  notice: string;
}

/** 批量生成的结果。 */
export interface FilingCalendarGenerateResult {
  created: number;
  skipped: number;
  items: FilingCalendarEntryView[];
  note: string;
}

/** 推进状态的入参。 */
export interface FilingCalendarAdvanceInput {
  targetStatus: string;
  reference?: string;
  note?: string;
}

/** 一条到期提醒。 */
export interface FilingCalendarReminderView {
  id: string;
  entryId: string;
  jurisdictionCode: string;
  jurisdictionName: string;
  taxType: string;
  filingCycle: string;
  periodStart: string;
  periodEnd: string;
  dueAt: string;
  dueTimezone: string;
  kind: string;
  kindLabel: string;
  remindAt: string;
  acknowledged: boolean;
  acknowledgedAt: string;
  acknowledgedBy: string;
  status: string;
  statusLabel: string;
  responsibleMembershipId: string;
  responsibleName: string;
  responsibleAvailable: boolean;
  responsibleNote: string;
}

/** 到期提醒清单。 */
export interface FilingCalendarReminderPage {
  items: FilingCalendarReminderView[];
  total: number;
  page: number;
  size: number;
  unacknowledgedCount: number;
  notice: string;
}

// ==================================================================
// 接口
// ==================================================================

export async function fetchCapabilities(): Promise<FilingCalendarCapabilities> {
  return api<FilingCalendarCapabilities>(`${BASE}/capabilities`);
}

export async function fetchOptions(): Promise<FilingCalendarOptions> {
  return api<FilingCalendarOptions>(`${BASE}/options`);
}

export interface EntryFilters {
  jurisdictionCode?: string;
  taxType?: string;
  filingCycle?: string;
  status?: string;
  keyword?: string;
  responsibleMembershipId?: string;
  dueWithinDays?: number;
}

export async function fetchEntries(
  page = 0,
  size = 10,
  filters: EntryFilters = {},
): Promise<FilingCalendarEntryPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.jurisdictionCode) query.set('jurisdictionCode', filters.jurisdictionCode);
  if (filters.taxType) query.set('taxType', filters.taxType);
  if (filters.filingCycle) query.set('filingCycle', filters.filingCycle);
  if (filters.status) query.set('status', filters.status);
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.responsibleMembershipId)
    query.set('responsibleMembershipId', filters.responsibleMembershipId);
  if (filters.dueWithinDays !== undefined)
    query.set('dueWithinDays', String(filters.dueWithinDays));
  return api<FilingCalendarEntryPage>(`${BASE}/entries?${query.toString()}`);
}

export async function fetchEntry(id: string): Promise<FilingCalendarEntryView> {
  return api<FilingCalendarEntryView>(`${BASE}/entries/${id}`);
}

export async function createEntry(
  input: FilingCalendarEntryInput,
): Promise<FilingCalendarEntryView> {
  return api<FilingCalendarEntryView>(`${BASE}/entries`, 'POST', input);
}

export async function updateEntry(
  id: string,
  input: FilingCalendarEntryInput,
): Promise<FilingCalendarEntryView> {
  return api<FilingCalendarEntryView>(`${BASE}/entries/${id}`, 'PUT', input);
}

export async function generateEntries(referenceDate?: string): Promise<FilingCalendarGenerateResult> {
  const query = referenceDate ? `?referenceDate=${encodeURIComponent(referenceDate)}` : '';
  return api<FilingCalendarGenerateResult>(`${BASE}/entries/generate${query}`, 'POST');
}

export async function advanceEntry(
  id: string,
  input: FilingCalendarAdvanceInput,
): Promise<FilingCalendarEntryView> {
  return api<FilingCalendarEntryView>(`${BASE}/entries/${id}/advance`, 'POST', input);
}

export async function refreshReadiness(): Promise<{ refreshed: number; note: string }> {
  return api<{ refreshed: number; note: string }>(`${BASE}/entries/readiness`, 'POST');
}

export async function fetchCycleConfigs(
  page = 0,
  size = 10,
  filters: { jurisdictionCode?: string; taxType?: string; filingCycle?: string; status?: string } = {},
): Promise<FilingCalendarCycleConfigPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.jurisdictionCode) query.set('jurisdictionCode', filters.jurisdictionCode);
  if (filters.taxType) query.set('taxType', filters.taxType);
  if (filters.filingCycle) query.set('filingCycle', filters.filingCycle);
  if (filters.status) query.set('status', filters.status);
  return api<FilingCalendarCycleConfigPage>(`${BASE}/cycle-configs?${query.toString()}`);
}

export async function createCycleConfig(
  input: FilingCalendarCycleConfigInput,
): Promise<FilingCalendarCycleConfigView> {
  return api<FilingCalendarCycleConfigView>(`${BASE}/cycle-configs`, 'POST', input);
}

export async function updateCycleConfig(
  id: string,
  input: FilingCalendarCycleConfigInput,
): Promise<FilingCalendarCycleConfigView> {
  return api<FilingCalendarCycleConfigView>(`${BASE}/cycle-configs/${id}`, 'PUT', input);
}

export interface ReminderFilters {
  kind?: string;
  jurisdictionCode?: string;
  acknowledged?: boolean;
  withinDays?: number;
}

export async function fetchReminders(
  page = 0,
  size = 10,
  filters: ReminderFilters = {},
): Promise<FilingCalendarReminderPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.kind) query.set('kind', filters.kind);
  if (filters.jurisdictionCode) query.set('jurisdictionCode', filters.jurisdictionCode);
  if (filters.acknowledged !== undefined)
    query.set('acknowledged', String(filters.acknowledged));
  if (filters.withinDays !== undefined) query.set('withinDays', String(filters.withinDays));
  return api<FilingCalendarReminderPage>(`${BASE}/reminders?${query.toString()}`);
}

export async function acknowledgeReminder(id: string): Promise<FilingCalendarReminderView> {
  return api<FilingCalendarReminderView>(`${BASE}/reminders/${id}/acknowledge`, 'POST');
}

// ==================================================================
// 展示口径（**不重算后端的结论**）
// ==================================================================

/** 状态的序号（越大越靠后；与后端一致）。 */
export function statusRank(status: string): number {
  switch (status) {
    case 'NOT_STARTED':
      return 0;
    case 'DATA_READY':
      return 1;
    case 'SUBMITTED':
      return 2;
    case 'CONFIRMED':
      return 3;
    default:
      return 0;
  }
}

/**
 * 状态能不能推进（**只许前进**）。
 *
 * <p>前端这一条只是用来**灰掉不该有的按钮**；真正的闸门在后端的服务层与 DB 触发器。
 */
export function canAdvance(from: string, to: string): boolean {
  if (!['DATA_READY', 'SUBMITTED', 'CONFIRMED'].includes(to)) return false;
  return statusRank(to) >= statusRank(from);
}

/** 状态的徽章色调。 */
export function statusTone(status: string): 'danger' | 'warning' | 'success' | 'neutral' {
  switch (status) {
    case 'CONFIRMED':
      return 'success';
    case 'SUBMITTED':
      return 'success';
    case 'DATA_READY':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 状态的人话。 */
export function statusLabel(status: string): string {
  switch (status) {
    case 'NOT_STARTED':
      return '未开始';
    case 'DATA_READY':
      return '数据就绪';
    case 'SUBMITTED':
      return '已提交';
    case 'CONFIRMED':
      return '已确认';
    default:
      return status;
  }
}

/** 提醒档位的人话。 */
export function reminderKindLabel(kind: string): string {
  switch (kind) {
    case 'DUE_SOON':
      return '即将到期';
    case 'DUE':
      return '截止日';
    default:
      return kind;
  }
}

/** 申报周期的人话。 */
export function filingCycleLabel(cycle: string): string {
  switch (cycle) {
    case 'MONTHLY':
      return '按月';
    case 'QUARTERLY':
      return '按季';
    case 'SEMI_ANNUAL':
      return '按半年';
    case 'ANNUAL':
      return '按年';
    default:
      return cycle;
  }
}

/** 申报线的人话。 */
export function taxTypeLabel(taxType: string): string {
  switch (taxType) {
    case 'VAT':
      return '增值税（VAT）';
    case 'IOSS':
      return 'IOSS（一站式商店）';
    case 'SALES_TAX':
      return '销售税';
    case 'GST':
      return '商品服务税（GST）';
    case 'CONSUMPTION_TAX':
      return '消费税';
    default:
      return taxType;
  }
}

/**
 * 🔴 紧迫度（**只用后端给的 overdue / daysToDue**，前端不自己算时间）。
 *
 * <p>漏报是本模块最贵的错误 ⇒ 逾期最刺眼、临近截止次之、已报完的最不刺眼。
 */
export type FilingUrgency = 'OVERDUE' | 'DUE_SOON' | 'SCHEDULED' | 'DONE';

export function urgencyOf(entry: {
  status: string;
  overdue: boolean;
  daysToDue: number | null;
}): FilingUrgency {
  if (entry.status === 'SUBMITTED' || entry.status === 'CONFIRMED') return 'DONE';
  if (entry.overdue) return 'OVERDUE';
  if (entry.daysToDue !== null && entry.daysToDue <= 7) return 'DUE_SOON';
  return 'SCHEDULED';
}

/** 紧迫度的人话。 */
export function urgencyLabel(urgency: FilingUrgency): string {
  switch (urgency) {
    case 'OVERDUE':
      return '已逾期未提交';
    case 'DUE_SOON':
      return '临近截止';
    case 'SCHEDULED':
      return '在计划内';
    default:
      return '已申报';
  }
}

/** 紧迫度的徽章色调。 */
export function urgencyTone(urgency: FilingUrgency): 'danger' | 'warning' | 'success' | 'neutral' {
  switch (urgency) {
    case 'OVERDUE':
      return 'danger';
    case 'DUE_SOON':
      return 'warning';
    case 'DONE':
      return 'success';
    default:
      return 'neutral';
  }
}

/**
 * 🔴 数据就绪的**人话**（三态严格分开）。
 *
 * <p>`available=false` ⇒ 「读不到」；`available=true && !ready` ⇒ 「没就绪」；
 * `ready` ⇒ 「就绪」。**把「读不到」写成「没就绪」是这类台账最致命的一种撒谎** ——
 * 前者要去查上游，后者是「查过了，确实没有」。
 */
export function readinessText(entry: {
  dataReady: boolean;
  dataReadinessAvailable: boolean;
  dataReadinessNote: string;
}): string {
  if (!entry.dataReadinessAvailable) {
    return entry.dataReadinessNote || '上游读不到 —— 不是「数据没就绪」，是「没查成」。';
  }
  if (entry.dataReady) {
    return entry.dataReadinessNote || '12.6.1.2 有一份生效中的底稿覆盖这个申报期。';
  }
  return entry.dataReadinessNote || '这个辖区这个申报期当前没有生效中的底稿。';
}

/** 责任人的人话（读不到 / 没指定 / 有名字，三态分开）。 */
export function responsibleText(entry: {
  responsibleMembershipId: string;
  responsibleName: string;
  responsibleAvailable: boolean;
  responsibleNote: string;
}): string {
  if (!entry.responsibleMembershipId) return '未指定责任人';
  if (!entry.responsibleAvailable) {
    return entry.responsibleNote || '责任人**读不到** —— 不是「没有责任人」。';
  }
  return entry.responsibleName || entry.responsibleMembershipId;
}

/**
 * 🔴 截止日的显示（**当地时刻 + 时区名**）。
 *
 * <p>后端已经把截止日换算成条目自己的当地时区（`Europe/Berlin` 之类），
 * 前端**不再换算**成浏览器时区 —— 换算了就等于把「23:59 截止」显示成第二天早上，
 * 运营会以为还有时间。
 */
export function deadlineText(dueAt: string, dueTimezone: string): string {
  if (!dueAt) return '未设截止日';
  const local = dueAt.replace('T', ' ').slice(0, 16);
  return dueTimezone ? `${local}（${dueTimezone}）` : local;
}

/** 空态的三种（**分别说，不混成一句**）。 */
export type FilingEmptyKind = 'NO_CONFIG' | 'NO_ENTRY' | 'NO_MATCH';

export function emptyStateKind(input: {
  totalConfigs: number;
  totalEntries: number;
  filtered: boolean;
}): FilingEmptyKind {
  if (input.filtered) return 'NO_MATCH';
  if (input.totalConfigs === 0) return 'NO_CONFIG';
  if (input.totalEntries === 0) return 'NO_ENTRY';
  return 'NO_MATCH';
}

/** 空态的人话。 */
export function emptyStateText(kind: FilingEmptyKind): string {
  switch (kind) {
    case 'NO_CONFIG':
      return '还没有任何申报周期配置 —— 先登记一份（辖区 × 申报线 × 周期），日历才有的可生成。';
    case 'NO_ENTRY':
      return '配置有了，但日历上还没有申报期 —— 点「按配置生成当前申报期」。';
    default:
      return '当前筛选条件下没有申报义务 —— 清掉筛选再看。';
  }
}
