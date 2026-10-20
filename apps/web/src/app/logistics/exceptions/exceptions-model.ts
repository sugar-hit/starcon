import { api, ApiError } from '../../../lib/api';

/**
 * 10.3.1.2 异常件前端模型。
 *
 * <p>蓝图：「识别与处理异常件：轨迹停滞、妥投失败、地址问题、清关滞留、破损丢件、买家投诉；
 * 支持<b>联系承运商、发起理赔、改址、补发</b>。输入：轨迹异常规则；被动异常标记；客服反馈。
 * 输出：异常件清单 + 处置记录 + 理赔/补发单。关系：上游 = 10.3.1.1；
 * 下游 = 11 售后（补发/退款）、15 客服、7 库存（补发出库）。
 * <b>异常件必须有人负责到底</b>，否则变成买家差评与平台纠纷。」
 *
 * <p>🔴 这个界面上最容易做成假活的三处，本模型把它们写死：
 *
 * <ol>
 *   <li><b>异常类型必须显式选</b>：{@link EMPTY_EXCEPTION_TYPE} 是**空串**，
 *       不是 'TRACKING_STALLED'。承运商的异常码是自由文本，服务端**不猜**它对应
 *       哪一种类型（猜错就是「地址问题」被记成「妥投失败」，后面全跑偏），
 *       页面也不替人选一个。</li>
 *   <li><b>下游只显示「待通知」</b>：11 售后 / 15 客服 / 7 库存当前未交付，
 *       页面**不提供**「标记已通知 / 已受理」的入口 —— 那会伪造凭据，
 *       让客服以为买家已收到而不再跟进。</li>
 *   <li><b>金额按币种分行</b>：跨币种**绝不相加**，页面也不做汇率换算。</li>
 * </ol>
 */

// ================================================================ 枚举词表

/** 异常类型：与服务端白名单逐字一致。 */
export type ExceptionType =
  | 'TRACKING_STALLED'
  | 'DELIVERY_FAILED'
  | 'ADDRESS_ISSUE'
  | 'CUSTOMS_HOLD'
  | 'DAMAGED_OR_LOST'
  | 'BUYER_COMPLAINT';

/** 异常类型的初值 —— **空串**，代表「还没显式选」。 */
export const EMPTY_EXCEPTION_TYPE = '' as const;

export const EXCEPTION_TYPES: readonly ExceptionType[] = [
  'TRACKING_STALLED',
  'DELIVERY_FAILED',
  'ADDRESS_ISSUE',
  'CUSTOMS_HOLD',
  'DAMAGED_OR_LOST',
  'BUYER_COMPLAINT',
];

export type Severity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export const SEVERITIES: readonly Severity[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

export type CaseStatus = 'OPEN' | 'INVESTIGATING' | 'ACTIONED' | 'RESOLVED' | 'VOID';

export const CASE_STATUSES: readonly CaseStatus[] = [
  'OPEN',
  'INVESTIGATING',
  'ACTIONED',
  'RESOLVED',
  'VOID',
];

export type ActionType =
  | 'CONTACT_CARRIER'
  | 'CONTACT_BUYER'
  | 'CONTACT_CS'
  | 'FILE_CLAIM'
  | 'REQUEST_ADDRESS_CHANGE'
  | 'REQUEST_RESHIP'
  | 'ESCALATE'
  | 'ASSIGN'
  | 'RESOLVE'
  | 'VOID';

export const ACTION_TYPES: readonly ActionType[] = [
  'CONTACT_CARRIER',
  'CONTACT_BUYER',
  'CONTACT_CS',
  'FILE_CLAIM',
  'REQUEST_ADDRESS_CHANGE',
  'REQUEST_RESHIP',
  'ESCALATE',
  'ASSIGN',
  'RESOLVE',
  'VOID',
];

export type DispositionStatus =
  | 'PENDING'
  | 'IN_PROGRESS'
  | 'SUBMITTED'
  | 'CONFIRMED'
  | 'FAILED'
  | 'CANCELLED';

export const DISPOSITION_STATUSES: readonly DispositionStatus[] = [
  'PENDING',
  'IN_PROGRESS',
  'SUBMITTED',
  'CONFIRMED',
  'FAILED',
  'CANCELLED',
];

export type ClaimType = 'CARRIER_CLAIM' | 'RESHIP';

export const CLAIM_TYPES: readonly ClaimType[] = ['CARRIER_CLAIM', 'RESHIP'];

export type DownstreamModule = '7' | '11' | '15' | '10.2.1.3';

export const DOWNSTREAM_MODULES: readonly DownstreamModule[] = ['7', '11', '15', '10.2.1.3'];

/** 轨迹节点白名单（规则的匹配节点用）。 */
export const TRACKING_NODES: readonly string[] = [
  'NO_TRACE',
  'PICKED_UP',
  'IN_TRANSIT',
  'CUSTOMS',
  'TRANSFER',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
  'EXCEPTION',
  'RETURNED',
];

// ================================================================ 形状

export interface ExceptionCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string | null;
  impact: string;
}

export interface LogisticsExceptionCapabilities {
  dimensions: ExceptionCapabilityDimension[];
  exceptionTypes: string[];
  severities: string[];
  caseStatuses: string[];
  actionTypes: string[];
  dispositionStatuses: string[];
  claimTypes: string[];
  claimStatuses: string[];
  downstreamModules: string[];
  downstreamStatuses: string[];
  responsibilityNote: string;
  durationNote: string;
  moneyNote: string;
  upstreamNote: string;
  downstreamNote: string;
  boundaryNote: string;
}

export interface TypeCount {
  exceptionType: string;
  exceptionTypeLabel: string;
  count: number;
}

export interface StatusCount {
  status: string;
  statusLabel: string;
  count: number;
}

export interface SeverityCount {
  severity: string;
  severityLabel: string;
  count: number;
}

export interface CurrencyTotal {
  currency: string;
  amount: number;
  claimCount: number;
}

export interface LogisticsExceptionOverview {
  caseCount: number;
  openCount: number;
  investigatingCount: number;
  actionedCount: number;
  resolvedCount: number;
  voidCount: number;
  overdueCount: number;
  pendingDownstreamCount: number;
  claimCount: number;
  reshipCount: number;
  carrierClaimCount: number;
  claimTotals: CurrencyTotal[];
  byType: TypeCount[];
  byStatus: StatusCount[];
  bySeverity: SeverityCount[];
  evaluatedAt: string;
  note: string;
  moneyNote: string;
}

export interface ExceptionCaseRow {
  id: string;
  caseNo: string;
  source: string;
  sourceLabel: string;
  exceptionType: string;
  exceptionTypeLabel: string;
  severity: string;
  severityLabel: string;
  status: string;
  statusLabel: string;
  subjectId: string | null;
  sourceEventId: string | null;
  waybillNo: string;
  carrierCode: string;
  salesOrderNo: string;
  stalledDurationMinutes: number;
  stalledDurationHours: number;
  ownerMembershipId: string;
  detectedAt: string;
  dueAt: string;
  overdue: boolean;
  customerFeedback: string;
  closedAt: string | null;
  closeReason: string;
  dispositionCount: number;
  claimCount: number;
  note: string;
  version: number;
}

export interface ExceptionCasePage {
  rows: ExceptionCaseRow[];
  page: number;
  size: number;
  total: number;
  evaluatedAt: string;
  note: string;
  responsibilityNote: string;
}

export interface ExceptionDispositionRow {
  id: string;
  caseId: string;
  caseNo: string;
  waybillNo: string;
  actionType: string;
  actionTypeLabel: string;
  status: string;
  statusLabel: string;
  targetOwnerMembershipId: string | null;
  amount: number;
  currency: string;
  detail: string;
  resultNote: string;
  operatorMembershipId: string;
  occurredAt: string;
  downstreamTaskCount: number;
}

export interface ExceptionDispositionPage {
  rows: ExceptionDispositionRow[];
  page: number;
  size: number;
  total: number;
  evaluatedAt: string;
  note: string;
}

export interface ExceptionClaimRow {
  id: string;
  claimNo: string;
  caseId: string;
  caseNo: string;
  dispositionId: string | null;
  waybillNo: string;
  claimType: string;
  claimTypeLabel: string;
  status: string;
  statusLabel: string;
  amount: number;
  currency: string;
  qty: number;
  submittedAt: string | null;
  decidedAt: string | null;
  decisionNote: string;
  note: string;
  createdBy: string;
  createdAt: string;
  version: number;
}

export interface ExceptionClaimPage {
  rows: ExceptionClaimRow[];
  page: number;
  size: number;
  total: number;
  evaluatedAt: string;
  note: string;
  moneyNote: string;
}

export interface DownstreamTaskRow {
  id: string;
  caseId: string;
  caseNo: string;
  dispositionId: string | null;
  claimId: string | null;
  downstreamModule: string;
  downstreamModuleLabel: string;
  downstreamStatus: string;
  downstreamStatusLabel: string;
  referenceNo: string;
  note: string;
  createdAt: string;
  notifiedAt: string | null;
  acknowledgedAt: string | null;
}

export interface ExceptionUpstreamContext {
  subjectId: string;
  masterWaybillNo: string;
  carrierCode: string;
  latestNode: string;
  latestOccurredAt: string | null;
  eventCount: number;
  exceptionFlag: boolean;
  lastExceptionCode: string;
  lastExceptionAt: string | null;
  subjectStatus: string;
}

export interface ExceptionCaseDetail {
  head: ExceptionCaseRow;
  dispositions: ExceptionDispositionRow[];
  claims: ExceptionClaimRow[];
  downstreamTasks: DownstreamTaskRow[];
  upstream: ExceptionUpstreamContext | null;
}

export interface ExceptionRuleRow {
  id: string;
  ruleCode: string;
  ruleType: string;
  ruleTypeLabel: string;
  matchNode: string;
  matchExceptionCode: string;
  thresholdMinutes: number | null;
  severity: string;
  severityLabel: string;
  resolveSlaMinutes: number;
  enabled: boolean;
  note: string;
  createdBy: string;
  createdAt: string;
}

export interface ExceptionRulePage {
  rows: ExceptionRuleRow[];
  page: number;
  size: number;
  total: number;
  evaluatedAt: string;
  note: string;
}

export interface ExceptionScanSkip {
  ruleCode: string;
  waybillNo: string;
  reason: string;
  detail: string | null;
}

export interface ExceptionScanResult {
  ruleCount: number;
  candidateCount: number;
  createdCount: number;
  skippedCount: number;
  skips: ExceptionScanSkip[];
  createdCaseNos: string[];
  scannedAt: string;
  note: string;
}

// ================================================================ 请求体

const BASE = 'channel/logistics-tracking';

// ================================================================ 取数

export function fetchCapabilities(): Promise<LogisticsExceptionCapabilities> {
  return api<LogisticsExceptionCapabilities>(`${BASE}/exceptions/capabilities`);
}

export function fetchOverview(): Promise<LogisticsExceptionOverview> {
  return api<LogisticsExceptionOverview>(`${BASE}/exceptions/overview`);
}

export interface CaseQuery {
  exceptionType?: string | undefined;
  severity?: string | undefined;
  status?: string | undefined;
  carrierCode?: string | undefined;
  keyword?: string | undefined;
  ownerMembershipId?: string | undefined;
  overdueOnly?: boolean | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export function fetchCases(query: CaseQuery = {}): Promise<ExceptionCasePage> {
  const params = new URLSearchParams();
  if (query.exceptionType) params.set('exceptionType', query.exceptionType);
  if (query.severity) params.set('severity', query.severity);
  if (query.status) params.set('status', query.status);
  if (query.carrierCode) params.set('carrierCode', query.carrierCode);
  if (query.keyword) params.set('keyword', query.keyword);
  if (query.ownerMembershipId) params.set('ownerMembershipId', query.ownerMembershipId);
  if (query.overdueOnly) params.set('overdueOnly', 'true');
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 50));
  return api<ExceptionCasePage>(`${BASE}/exceptions?${params.toString()}`);
}

export function fetchCase(id: string): Promise<ExceptionCaseDetail> {
  return api<ExceptionCaseDetail>(`${BASE}/exceptions/${id}`);
}

export interface DispositionQuery {
  actionType?: string | undefined;
  status?: string | undefined;
  caseId?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export function fetchDispositions(
  query: DispositionQuery = {},
): Promise<ExceptionDispositionPage> {
  const params = new URLSearchParams();
  if (query.actionType) params.set('actionType', query.actionType);
  if (query.status) params.set('status', query.status);
  if (query.caseId) params.set('caseId', query.caseId);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 50));
  return api<ExceptionDispositionPage>(`${BASE}/dispositions?${params.toString()}`);
}

export interface ClaimQuery {
  caseId?: string | undefined;
  claimType?: string | undefined;
  status?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export function fetchClaims(query: ClaimQuery = {}): Promise<ExceptionClaimPage> {
  const params = new URLSearchParams();
  if (query.caseId) params.set('caseId', query.caseId);
  if (query.claimType) params.set('claimType', query.claimType);
  if (query.status) params.set('status', query.status);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 50));
  return api<ExceptionClaimPage>(`${BASE}/dispositions/claims?${params.toString()}`);
}

export function fetchRules(ruleType?: string, page = 0, size = 50): Promise<ExceptionRulePage> {
  const params = new URLSearchParams();
  if (ruleType) params.set('ruleType', ruleType);
  params.set('page', String(page));
  params.set('size', String(size));
  return api<ExceptionRulePage>(`${BASE}/exceptions/rules?${params.toString()}`);
}

export function scanExceptions(ruleId?: string): Promise<ExceptionScanResult> {
  const params = new URLSearchParams();
  if (ruleId) params.set('ruleId', ruleId);
  const suffix = params.toString() ? `?${params.toString()}` : '';
  return api<ExceptionScanResult>(`${BASE}/exceptions/scan${suffix}`, 'POST');
}

// ================================================================ 写入

export interface CreateCasePayload {
  subjectId?: string | undefined;
  sourceEventId?: string | undefined;
  ruleId?: string | undefined;
  exceptionType: string;
  severity?: string | undefined;
  customerFeedback?: string | undefined;
  waybillNo?: string | undefined;
  note?: string | undefined;
}

export function createExceptionCase(payload: CreateCasePayload): Promise<ExceptionCaseRow> {
  return api<ExceptionCaseRow>(`${BASE}/exceptions`, 'POST', payload);
}

export interface AddDispositionPayload {
  actionType: string;
  status?: string | undefined;
  targetOwnerMembershipId?: string | undefined;
  amount?: number | undefined;
  currency?: string | undefined;
  detail?: string | undefined;
  resultNote?: string | undefined;
  occurredAt?: string | undefined;
}

export function addDisposition(
  caseId: string,
  payload: AddDispositionPayload,
): Promise<ExceptionDispositionRow> {
  return api<ExceptionDispositionRow>(`${BASE}/exceptions/${caseId}/dispositions`, 'POST', payload);
}

export interface CreateClaimPayload {
  claimType: string;
  dispositionId?: string | undefined;
  amount?: number | undefined;
  currency?: string | undefined;
  qty?: number | undefined;
  evidenceNote?: string | undefined;
  note?: string | undefined;
}

export function createExceptionClaim(
  caseId: string,
  payload: CreateClaimPayload,
): Promise<ExceptionClaimRow> {
  return api<ExceptionClaimRow>(`${BASE}/exceptions/${caseId}/claims`, 'POST', payload);
}

export interface SaveRulePayload {
  ruleCode: string;
  ruleType: string;
  matchNode?: string | undefined;
  matchExceptionCode?: string | undefined;
  thresholdMinutes?: number | undefined;
  severity: string;
  resolveSlaMinutes: number;
  enabled: boolean;
  note?: string | undefined;
}

export function saveExceptionRule(payload: SaveRulePayload): Promise<ExceptionRuleRow> {
  return api<ExceptionRuleRow>(`${BASE}/exceptions/rules`, 'POST', payload);
}

// ================================================================ 纯函数（可测）

/** 异常类型的展示名。服务端已给 label，这里兜底未知值。 */
export function exceptionTypeLabel(type: string | null | undefined): string {
  switch (type) {
    case 'TRACKING_STALLED':
      return '轨迹停滞';
    case 'DELIVERY_FAILED':
      return '妥投失败';
    case 'ADDRESS_ISSUE':
      return '地址问题';
    case 'CUSTOMS_HOLD':
      return '清关滞留';
    case 'DAMAGED_OR_LOST':
      return '破损丢件';
    case 'BUYER_COMPLAINT':
      return '买家投诉';
    default:
      return '未分类';
  }
}

export function severityLabel(severity: string | null | undefined): string {
  switch (severity) {
    case 'LOW':
      return '低';
    case 'MEDIUM':
      return '中';
    case 'HIGH':
      return '高';
    case 'CRITICAL':
      return '紧急';
    default:
      return '未知';
  }
}

export function caseStatusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'OPEN':
      return '待处理';
    case 'INVESTIGATING':
      return '处置中';
    case 'ACTIONED':
      return '已出处置动作';
    case 'RESOLVED':
      return '已结案';
    case 'VOID':
      return '已作废';
    default:
      return '未知';
  }
}

export function actionTypeLabel(action: string | null | undefined): string {
  switch (action) {
    case 'CONTACT_CARRIER':
      return '联系承运商';
    case 'CONTACT_BUYER':
      return '联系买家';
    case 'CONTACT_CS':
      return '联系客服';
    case 'FILE_CLAIM':
      return '发起理赔';
    case 'REQUEST_ADDRESS_CHANGE':
      return '请求改址';
    case 'REQUEST_RESHIP':
      return '请求补发';
    case 'ESCALATE':
      return '升级';
    case 'ASSIGN':
      return '转派责任人';
    case 'RESOLVE':
      return '结案';
    case 'VOID':
      return '作废';
    default:
      return '未知动作';
  }
}

export function downstreamModuleLabel(code: string | null | undefined): string {
  switch (code) {
    case '7':
      return '7 库存（补发出库）';
    case '11':
      return '11 售后（补发 / 退款）';
    case '15':
      return '15 客服';
    case '10.2.1.3':
      return '10.2.1.3 改址与取消';
    default:
      return '未知下游';
  }
}

export function downstreamStatusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'PENDING_NOTIFY':
      return '待通知';
    case 'NOTIFIED':
      return '已通知';
    case 'ACKNOWLEDGED':
      return '已受理';
    case 'CANCELLED':
      return '已取消';
    default:
      return '未知';
  }
}

export function sourceLabel(source: string | null | undefined): string {
  switch (source) {
    case 'TRACKING_FLAG':
      return '被动异常标记';
    case 'RULE_SCAN':
      return '轨迹异常规则';
    case 'CUSTOMER_FEEDBACK':
      return '客服反馈';
    case 'MANUAL':
      return '人工登记';
    default:
      return '未知来源';
  }
}

/**
 * 🔴 时长展示：**整数分钟**在前，小时只是括注。
 *
 * <p>禁浮点小时当主口径 —— 「停滞 0.5 小时」与「停滞 30 分钟」是两个数，
 * 界面上混着写会让人以为「小于 1 小时」不算异常。
 */
export function durationText(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined || Number.isNaN(minutes)) return '—';
  if (minutes <= 0) return '0 分钟';
  const hours = minutes / 60;
  const hourText = hours >= 1 ? `（约 ${hours.toFixed(1)} 小时）` : '';
  return `${minutes.toLocaleString('zh-CN')} 分钟${hourText}`;
}

/** 金额展示（金额是 NUMERIC(19,6)，展示保留两位）。 */
export function moneyText(amount: number | null | undefined, currency?: string | null): string {
  if (amount === null || amount === undefined || Number.isNaN(amount)) return '—';
  const fixed = amount.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

/** 数量展示（补发数量是 NUMERIC(19,6)）。 */
export function quantityText(qty: number | null | undefined): string {
  if (qty === null || qty === undefined || Number.isNaN(qty)) return '—';
  return Number.isInteger(qty) ? String(qty) : qty.toFixed(6);
}

/**
 * 🔴 跨币种**分行**展示，绝不相加。
 *
 * <p>本模块没有汇率表，折算归 12 财务（未交付）。所以这里只把各币种的合计
 * 一行一行列出来，绝不给出「总额」。
 */
export function claimTotalsText(totals: CurrencyTotal[] | null | undefined): string {
  if (!totals || totals.length === 0) return '暂无登记金额';
  return totals
    .map((t) => `${moneyText(t.amount, t.currency)}（${t.claimCount} 张单）`)
    .join('；');
}

// ================================================================ 表单

export interface CaseForm {
  exceptionType: string;
  severity: string;
  waybillNo: string;
  customerFeedback: string;
  note: string;
}

/** 建件表单初值：🔴 异常类型是**空串**，不是某个默认值。 */
export function emptyCaseForm(): CaseForm {
  return {
    exceptionType: EMPTY_EXCEPTION_TYPE,
    severity: 'MEDIUM',
    waybillNo: '',
    customerFeedback: '',
    note: '',
  };
}

export function validateCaseForm(form: CaseForm): string | null {
  if (!form.exceptionType) {
    return '必须显式选择异常类型：承运商的异常码是自由文本，本页不替你猜它对应哪一种异常';
  }
  if (form.severity && !SEVERITIES.includes(form.severity as Severity)) {
    return '严重度不在白名单内';
  }
  return null;
}

export function buildCasePayload(
  form: CaseForm,
): { payload: CreateCasePayload } | { error: string } {
  const error = validateCaseForm(form);
  if (error) return { error };
  const payload: CreateCasePayload = { exceptionType: form.exceptionType };
  if (form.severity) payload.severity = form.severity;
  if (form.waybillNo.trim()) payload.waybillNo = form.waybillNo.trim();
  if (form.customerFeedback.trim()) payload.customerFeedback = form.customerFeedback.trim();
  if (form.note.trim()) payload.note = form.note.trim();
  return { payload };
}

export interface DispositionForm {
  actionType: string;
  status: string;
  targetOwnerMembershipId: string;
  amount: string;
  currency: string;
  detail: string;
}

export function emptyDispositionForm(): DispositionForm {
  return {
    actionType: '',
    status: 'PENDING',
    targetOwnerMembershipId: '',
    amount: '',
    currency: '',
    detail: '',
  };
}

export function validateDispositionForm(form: DispositionForm): string | null {
  if (!form.actionType) return '必须选择处置动作';
  if (form.actionType === 'ASSIGN' && !form.targetOwnerMembershipId.trim()) {
    return '转派责任人必须给出目标责任人（没有目标人的转派等于没转派）';
  }
  if ((form.actionType === 'RESOLVE' || form.actionType === 'VOID') && !form.detail.trim()) {
    return form.actionType === 'RESOLVE' ? '结案必须写明结案理由' : '作废必须写明作废理由';
  }
  const amount = form.amount.trim();
  if (amount) {
    const parsed = Number(amount);
    if (Number.isNaN(parsed) || parsed < 0) return '金额必须是不小于 0 的数字';
    if (parsed > 0 && !form.currency.trim()) return '金额大于 0 时必须给出币种';
  }
  if (form.currency.trim() && !/^[A-Za-z]{3}$/.test(form.currency.trim())) {
    return '币种必须是三个大写字母的代码（例如 USD / EUR）';
  }
  return null;
}

export function buildDispositionPayload(
  form: DispositionForm,
): { payload: AddDispositionPayload } | { error: string } {
  const error = validateDispositionForm(form);
  if (error) return { error };
  const payload: AddDispositionPayload = { actionType: form.actionType };
  if (form.status) payload.status = form.status;
  if (form.targetOwnerMembershipId.trim()) {
    payload.targetOwnerMembershipId = form.targetOwnerMembershipId.trim();
  }
  if (form.amount.trim()) payload.amount = Number(form.amount.trim());
  if (form.currency.trim()) payload.currency = form.currency.trim().toUpperCase();
  if (form.detail.trim()) payload.detail = form.detail.trim();
  return { payload };
}

export interface ClaimForm {
  claimType: string;
  amount: string;
  currency: string;
  qty: string;
  evidenceNote: string;
  note: string;
}

export function emptyClaimForm(): ClaimForm {
  return { claimType: '', amount: '', currency: '', qty: '', evidenceNote: '', note: '' };
}

export function validateClaimForm(form: ClaimForm): string | null {
  if (!form.claimType) return '必须选择单据类型（向承运商理赔 / 补发）';
  if (form.claimType === 'CARRIER_CLAIM') {
    const amount = Number(form.amount.trim());
    if (!form.amount.trim() || Number.isNaN(amount) || amount <= 0) {
      return '理赔金额必须大于 0（0 元理赔单是假单）';
    }
    if (!/^[A-Za-z]{3}$/.test(form.currency.trim())) {
      return '理赔币种必须是三个大写字母的代码（例如 USD / EUR）';
    }
  }
  if (form.claimType === 'RESHIP') {
    const qty = Number(form.qty.trim());
    if (!form.qty.trim() || Number.isNaN(qty) || qty <= 0) {
      return '补发数量必须大于 0';
    }
  }
  return null;
}

export function buildClaimPayload(form: ClaimForm): { payload: CreateClaimPayload } | { error: string } {
  const error = validateClaimForm(form);
  if (error) return { error };
  const payload: CreateClaimPayload = { claimType: form.claimType };
  if (form.claimType === 'CARRIER_CLAIM') {
    payload.amount = Number(form.amount.trim());
    payload.currency = form.currency.trim().toUpperCase();
  }
  if (form.claimType === 'RESHIP') payload.qty = Number(form.qty.trim());
  if (form.evidenceNote.trim()) payload.evidenceNote = form.evidenceNote.trim();
  if (form.note.trim()) payload.note = form.note.trim();
  return { payload };
}

export interface RuleForm {
  ruleCode: string;
  ruleType: string;
  matchNode: string;
  matchExceptionCode: string;
  thresholdMinutes: string;
  severity: string;
  resolveSlaMinutes: string;
  enabled: boolean;
  note: string;
}

export function emptyRuleForm(): RuleForm {
  return {
    ruleCode: '',
    ruleType: '',
    matchNode: '',
    matchExceptionCode: '',
    thresholdMinutes: '',
    severity: 'MEDIUM',
    resolveSlaMinutes: '1440',
    enabled: true,
    note: '',
  };
}

export function validateRuleForm(form: RuleForm): string | null {
  if (!form.ruleCode.trim()) return '规则编码不能为空（编码是租户内唯一的）';
  if (!form.ruleType) return '必须选择规则命中的异常类型';
  const threshold = form.thresholdMinutes.trim();
  if (threshold) {
    const parsed = Number(threshold);
    if (!Number.isInteger(parsed) || parsed <= 0) return '时长阈值必须是大于 0 的整数分钟';
  }
  const sla = Number(form.resolveSlaMinutes.trim());
  if (!Number.isInteger(sla) || sla <= 0) return '责任时限必须是大于 0 的整数分钟';
  return null;
}

export function buildRulePayload(form: RuleForm): { payload: SaveRulePayload } | { error: string } {
  const error = validateRuleForm(form);
  if (error) return { error };
  const payload: SaveRulePayload = {
    ruleCode: form.ruleCode.trim(),
    ruleType: form.ruleType,
    severity: form.severity,
    resolveSlaMinutes: Number(form.resolveSlaMinutes.trim()),
    enabled: form.enabled,
  };
  if (form.matchNode.trim()) payload.matchNode = form.matchNode.trim();
  if (form.matchExceptionCode.trim()) payload.matchExceptionCode = form.matchExceptionCode.trim();
  if (form.thresholdMinutes.trim()) payload.thresholdMinutes = Number(form.thresholdMinutes.trim());
  if (form.note.trim()) payload.note = form.note.trim();
  return { payload };
}

// ================================================================ 错误态

export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 422 || error.status === 409) return error.message;
    if (error.status === 403) return '当前角色没有异常件处置的操作权限';
    if (error.status === 0) return '正在连接渠道服务，请稍后重试';
    return error.message;
  }
  return '加载失败，请稍后重试';
}

/** 分页总页数（真实分页，不是假分页）。 */
export function totalPages(total: number, size: number): number {
  if (size <= 0) return 1;
  return Math.max(1, Math.ceil(total / size));
}
