import { api } from '../../lib/api';

/**
 * 16.1.2.2 申诉管理 前端模型。
 *
 * 蓝图：「管理平台申诉：申诉类型、举证材料收集、申诉文本、提交跟踪、结果记录与申诉次数统计。
 * 输入：违规记录；申诉材料；提交动作。输出：申诉单 + 提交记录 + 结果（通过/驳回）。
 * 关系：上游 = 16.1.2.1；下游 = 平台申诉接口。**申诉配额有限**，需记录已用次数并优先申诉高影响违规。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **配额永远显示得出来** —— 剩几次、用了几次、配没配，三样都摆在明面上。
 *    「没配配额」不是「无限」，页面上必须显示成「没配」而不是「0」。
 * 2. **影响档位是派生的** —— 页面只显示后端给的档位，自己不重算严重度。
 * 3. **举证材料 / 提交记录 / 结果记录三样都是只追加** —— 界面上没有「编辑 / 删除」。
 * 4. **状态机在服务端** —— 页面只显示后端给的合法下一步，点了非法的一律 422。
 * 5. **未交付的下游灰掉** —— 平台申诉接口本仓不存在，页面上如实标出，
 *    **不编**「已提交到平台」。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有 channel 分支）。 */
export const BASE = 'channel/platform-appeals';

/** 🔴 申诉类型值域（与后端 CHECK 逐字一致）。 */
export const APPEAL_TYPES = [
  'POLICY_MISINTERPRETATION',
  'EVIDENCE_PROVIDED',
  'PROCESS_ERROR',
  'DUPLICATE_PENALTY',
  'RESTORATION_REQUEST',
  'OTHER',
] as const;

/** 申诉状态值域。 */
export const STATUSES = ['DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'WITHDRAWN'] as const;

/** 举证材料类型值域。 */
export const EVIDENCE_TYPES = [
  'DOCUMENT',
  'IMAGE',
  'VIDEO',
  'LINK',
  'NOTE',
  'INVOICE',
  'CERTIFICATE',
] as const;

/** 申诉结果值域。 */
export const OUTCOMES = ['APPROVED', 'REJECTED'] as const;

/** 申诉状态筛选的可选项。 */
export const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'DRAFT', label: '草稿（未提交）' },
  { value: 'SUBMITTED', label: '已提交（等平台裁定）' },
  { value: 'APPROVED', label: '申诉通过' },
  { value: 'REJECTED', label: '申诉驳回' },
  { value: 'WITHDRAWN', label: '已撤回' },
] as const;

/** 申诉类型筛选的可选项。 */
export const APPEAL_TYPE_OPTIONS = [
  { value: '', label: '全部类型' },
  { value: 'POLICY_MISINTERPRETATION', label: '政策误判' },
  { value: 'EVIDENCE_PROVIDED', label: '补充证据' },
  { value: 'PROCESS_ERROR', label: '处理流程有误' },
  { value: 'DUPLICATE_PENALTY', label: '重复处罚' },
  { value: 'RESTORATION_REQUEST', label: '恢复措施申请' },
  { value: 'OTHER', label: '其他' },
] as const;

/** 举证材料类型的可选项。 */
export const EVIDENCE_TYPE_OPTIONS = [
  { value: 'DOCUMENT', label: '文档' },
  { value: 'IMAGE', label: '图片' },
  { value: 'VIDEO', label: '视频' },
  { value: 'LINK', label: '链接' },
  { value: 'NOTE', label: '说明' },
  { value: 'INVOICE', label: '发票 / 采购凭证' },
  { value: 'CERTIFICATE', label: '资质证书' },
] as const;

// ==================================================================
// 类型
// ==================================================================

/** 一个下游（或维度）的交付状态。 */
export interface ComplianceAppealCapabilityGap {
  moduleId: string;
  moduleName: string;
  ownerModule: string;
  available: boolean;
  impact: string;
}

/** 能力声明。 */
export interface ComplianceAppealCapabilities {
  gaps: ComplianceAppealCapabilityGap[];
  appealTypes: string[];
  appealTypeLabels: Record<string, string>;
  statuses: string[];
  statusLabels: Record<string, string>;
  statusTransitions: Record<string, string[]>;
  evidenceTypes: string[];
  evidenceTypeLabels: Record<string, string>;
  outcomes: string[];
  outcomeLabels: Record<string, string>;
  impactLevels: string[];
  typeDomainNote: string;
  quotaNote: string;
  priorityNote: string;
  stateMachineSemantics: string;
  appendOnlySemantics: string;
  upstreamNote: string;
  downstreamNote: string;
}

/** 店铺在本模块里的覆盖情况。 */
export interface ComplianceAppealShop {
  shopId: string;
  shopCode: string;
  shopName: string;
  platformCode: string;
  platformName: string;
  appealCount: number;
  openAppealCount: number;
  /** 🔴 本周期配额上限（0 = 没配配额） */
  quotaLimit: number;
  quotaUsed: number;
  quotaRemaining: number;
}

/** 店铺范围清单。 */
export interface ComplianceAppealShopPage {
  items: ComplianceAppealShop[];
  total: number;
  page: number;
  size: number;
}

/** 一条申诉单。 */
export interface ComplianceAppeal {
  appealId: string;
  shopId: string;
  shopCode: string;
  shopName: string;
  platformCode: string;
  violationId: string;
  violationTitle: string;
  violationType: string;
  violationTypeLabel: string;
  /** 🔴 派生的影响档位 */
  impactLevel: string;
  impactLabel: string;
  appealType: string;
  appealTypeLabel: string;
  status: string;
  statusLabel: string;
  /** 服务端算出的合法下一步（页面不自己拼） */
  nextStatuses: string[];
  appealText: string;
  submittedAt: string | null;
  withdrawnAt: string | null;
  /** 🔴 提交次数 = 申诉次数 */
  submissionCount: number;
  evidenceCount: number;
  decisionCount: number;
  /** 🔴 null = 还没裁定过（不是「结果是空」） */
  lastOutcome: string | null;
  lastOutcomeLabel: string;
  lastOutcomeReason: string;
  lastDecidedAt: string | null;
  createdAt: string;
}

/** 申诉单清单。 */
export interface ComplianceAppealPage {
  items: ComplianceAppeal[];
  total: number;
  page: number;
  size: number;
  draftCount: number;
  submittedCount: number;
  approvedCount: number;
  rejectedCount: number;
  withdrawnCount: number;
  /** 🔴 提交总次数 = 申诉次数 */
  submissionCount: number;
  note: string;
}

/** 一条申诉举证材料（**append-only**）。 */
export interface ComplianceAppealEvidence {
  evidenceId: string;
  appealId: string;
  evidenceType: string;
  evidenceRef: string;
  summary: string;
  submittedAt: string;
}

/** 一条申诉提交记录（**append-only**）。 */
export interface ComplianceAppealSubmission {
  submissionId: string;
  appealId: string;
  attemptNo: number;
  submittedAt: string;
  platformReference: string;
  note: string;
}

/** 一条申诉结果记录（**append-only**）。 */
export interface ComplianceAppealDecision {
  decisionId: string;
  appealId: string;
  outcome: string;
  outcomeLabel: string;
  reason: string;
  decidedAt: string;
  platformReference: string;
}

/** 一个店铺在某个周期的申诉配额。 */
export interface ComplianceAppealQuota {
  shopId: string;
  shopCode: string;
  shopName: string;
  periodStart: string | null;
  periodEnd: string | null;
  quotaLimit: number;
  usedCount: number;
  remaining: number;
  /** 🔴 false = 没配配额（**不是「无限」**） */
  configured: boolean;
  note: string;
}

/** 配额清单。 */
export interface ComplianceAppealQuotaPage {
  items: ComplianceAppealQuota[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 优先申诉队列里的一条候选违规。 */
export interface ComplianceAppealPriorityCandidate {
  violationId: string;
  shopId: string;
  shopCode: string;
  shopName: string;
  platformCode: string;
  title: string;
  violationType: string;
  violationTypeLabel: string;
  impactLevel: string;
  impactLabel: string;
  occurredAt: string;
  deadline: string | null;
  violationStatus: string;
  /** 现在能不能提 */
  appealable: boolean;
  /** 不能提的原因 / 能提的提示 */
  reason: string;
  openAppealId: string | null;
  quotaConfigured: boolean;
  /** 🔴 -1 = 没配配额（**不是 0**） */
  quotaRemaining: number;
}

/** 优先申诉队列。 */
export interface ComplianceAppealPriorityPage {
  items: ComplianceAppealPriorityCandidate[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 申诉单详情。 */
export interface ComplianceAppealDetail {
  appeal: ComplianceAppeal;
  evidence: ComplianceAppealEvidence[];
  submissions: ComplianceAppealSubmission[];
  decisions: ComplianceAppealDecision[];
  quota: ComplianceAppealQuota;
  note: string;
}

/** 申诉次数统计。 */
export interface ComplianceAppealStats {
  shopId: string | null;
  totalAppeals: number;
  byStatus: Record<string, number>;
  byType: Record<string, number>;
  /** 🔴 申诉次数 = 提交总次数 */
  totalSubmissions: number;
  approvedCount: number;
  rejectedCount: number;
  quota: ComplianceAppealQuota | null;
  note: string;
}

/** 提交申诉的结果。 */
export interface ComplianceAppealSubmissionResult {
  appeal: ComplianceAppeal;
  submission: ComplianceAppealSubmission;
  attemptNo: number;
  quota: ComplianceAppealQuota;
  note: string;
}

/** 记录裁定的结果。 */
export interface ComplianceAppealDecisionResult {
  appeal: ComplianceAppeal;
  decision: ComplianceAppealDecision;
  note: string;
}

/** 配置配额的结果。 */
export interface ComplianceAppealQuotaUpdate {
  quota: ComplianceAppealQuota;
  created: boolean;
  note: string;
}

// ==================================================================
// 人话与色调
// ==================================================================

/** 申诉状态的人话（服务端也会给一份，这里只做兜底）。 */
export function statusLabel(code: string): string {
  switch (code) {
    case 'DRAFT':
      return '草稿（未提交）';
    case 'SUBMITTED':
      return '已提交（等平台裁定）';
    case 'APPROVED':
      return '申诉通过';
    case 'REJECTED':
      return '申诉驳回';
    case 'WITHDRAWN':
      return '已撤回';
    default:
      return code;
  }
}

/** 申诉状态的色调。 */
export function statusTone(code: string): 'neutral' | 'success' | 'warning' | 'danger' | 'info' {
  switch (code) {
    case 'DRAFT':
      return 'neutral';
    case 'SUBMITTED':
      return 'info';
    case 'APPROVED':
      return 'success';
    case 'REJECTED':
      return 'danger';
    case 'WITHDRAWN':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 申诉类型的人话。 */
export function appealTypeLabel(code: string): string {
  switch (code) {
    case 'POLICY_MISINTERPRETATION':
      return '政策误判';
    case 'EVIDENCE_PROVIDED':
      return '补充证据';
    case 'PROCESS_ERROR':
      return '处理流程有误';
    case 'DUPLICATE_PENALTY':
      return '重复处罚';
    case 'RESTORATION_REQUEST':
      return '恢复措施申请';
    case 'OTHER':
      return '其他';
    default:
      return code;
  }
}

/**
 * 🔴 影响档位的人话 —— **与服务端同一套口径**（上游 16.1.2.1 派生）。
 *
 * 「尚无处罚措施」用中性色：它**不等于没问题**，一条没有任何处罚措施的违规仍然需要处理。
 */
export function impactLabel(code: string): string {
  switch (code) {
    case 'CRITICAL':
      return '致命（停号级）';
    case 'HIGH':
      return '严重（下架级）';
    case 'MEDIUM':
      return '中等（限制销售 / 扣款级）';
    case 'LOW':
      return '轻微（警告级）';
    case 'NONE':
      return '尚无处罚措施';
    default:
      return code;
  }
}

/** 影响档位的色调。 */
export function impactTone(code: string): 'neutral' | 'success' | 'warning' | 'danger' | 'info' {
  switch (code) {
    case 'CRITICAL':
      return 'danger';
    case 'HIGH':
      return 'warning';
    case 'MEDIUM':
      return 'info';
    case 'LOW':
      return 'success';
    default:
      return 'neutral';
  }
}

/** 举证材料类型的人话。 */
export function evidenceTypeLabel(code: string): string {
  switch (code) {
    case 'DOCUMENT':
      return '文档';
    case 'IMAGE':
      return '图片';
    case 'VIDEO':
      return '视频';
    case 'LINK':
      return '链接';
    case 'NOTE':
      return '说明';
    case 'INVOICE':
      return '发票 / 采购凭证';
    case 'CERTIFICATE':
      return '资质证书';
    default:
      return code;
  }
}

/** 申诉结果的人话。 */
export function outcomeLabel(code: string): string {
  return code === 'APPROVED' ? '通过' : code === 'REJECTED' ? '驳回' : code;
}

/**
 * 🔴 配额的展示串 —— **「没配」和「用完了」是两件事**，页面上不能都显示成 0。
 */
export function quotaText(quota: ComplianceAppealQuota | null): string {
  if (!quota) return '未配置申诉配额';
  if (!quota.configured) return '未配置申诉配额（提交会被拒）';
  return `本周期配额 ${quota.quotaLimit} 次 · 已用 ${quota.usedCount} 次 · 剩 ${quota.remaining} 次`;
}

/** 剩余次数的展示串（-1 = 没配配额）。 */
export function remainingText(configured: boolean, remaining: number): string {
  if (!configured) return '未配置配额';
  if (remaining <= 0) return '配额已用尽';
  return `还剩 ${remaining} 次`;
}

/** 空态的种类。 */
export function emptyStateKind(filtered: boolean, total: number): 'no-data' | 'no-match' {
  return total === 0 && !filtered ? 'no-data' : 'no-match';
}

/** 空态的人话。 */
export function emptyState(kind: 'no-data' | 'no-match'): string {
  return kind === 'no-data'
    ? '还没有申诉单。可从「优先申诉队列」里挑一条高影响的违规发起申诉。'
    : '当前筛选条件下没有申诉单。';
}

// ==================================================================
// API
// ==================================================================

export async function fetchCapabilities(): Promise<ComplianceAppealCapabilities> {
  return api<ComplianceAppealCapabilities>(`${BASE}/capabilities`);
}

export async function fetchShops(
  page = 0,
  size = 50,
  filters: { platformCode?: string; keyword?: string } = {},
): Promise<ComplianceAppealShopPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.keyword) query.set('keyword', filters.keyword);
  return api<ComplianceAppealShopPage>(`${BASE}/shops?${query.toString()}`);
}

export interface AppealFilters {
  shopId?: string;
  status?: string;
  appealType?: string;
  violationId?: string;
  impactLevel?: string;
  keyword?: string;
  sort?: string;
}

export async function fetchAppeals(
  page = 0,
  size = 20,
  filters: AppealFilters = {},
): Promise<ComplianceAppealPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.shopId) query.set('shopId', filters.shopId);
  if (filters.status) query.set('status', filters.status);
  if (filters.appealType) query.set('appealType', filters.appealType);
  if (filters.violationId) query.set('violationId', filters.violationId);
  if (filters.impactLevel) query.set('impactLevel', filters.impactLevel);
  if (filters.keyword) query.set('keyword', filters.keyword);
  query.set('sort', filters.sort ?? 'priority');
  return api<ComplianceAppealPage>(`${BASE}/appeals?${query.toString()}`);
}

export async function fetchAppeal(appealId: string): Promise<ComplianceAppealDetail> {
  return api<ComplianceAppealDetail>(`${BASE}/appeals/${appealId}`);
}

export async function createAppeal(input: {
  violationId: string;
  appealType: string;
  appealText: string;
}): Promise<ComplianceAppeal> {
  return api<ComplianceAppeal>(`${BASE}/appeals`, 'POST', input);
}

export async function updateAppealText(
  appealId: string,
  appealText: string,
): Promise<ComplianceAppeal> {
  return api<ComplianceAppeal>(`${BASE}/appeals/${appealId}/text`, 'PUT', { appealText });
}

export async function addEvidence(
  appealId: string,
  input: { evidenceType: string; evidenceRef: string; summary: string },
): Promise<ComplianceAppealEvidence> {
  return api<ComplianceAppealEvidence>(`${BASE}/appeals/${appealId}/evidence`, 'POST', input);
}

export async function submitAppeal(
  appealId: string,
  input: { platformReference: string; note: string },
): Promise<ComplianceAppealSubmissionResult> {
  return api<ComplianceAppealSubmissionResult>(`${BASE}/appeals/${appealId}/submit`, 'POST', input);
}

export async function decideAppeal(
  appealId: string,
  input: { outcome: string; reason: string; platformReference: string },
): Promise<ComplianceAppealDecisionResult> {
  return api<ComplianceAppealDecisionResult>(`${BASE}/appeals/${appealId}/decision`, 'POST', input);
}

export async function withdrawAppeal(appealId: string): Promise<ComplianceAppeal> {
  return api<ComplianceAppeal>(`${BASE}/appeals/${appealId}/withdraw`, 'POST', { note: '' });
}

export async function reopenAppeal(appealId: string): Promise<ComplianceAppeal> {
  return api<ComplianceAppeal>(`${BASE}/appeals/${appealId}/reopen`, 'POST');
}

export async function fetchQuotas(
  page = 0,
  size = 20,
  filters: { shopId?: string } = {},
): Promise<ComplianceAppealQuotaPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.shopId) query.set('shopId', filters.shopId);
  return api<ComplianceAppealQuotaPage>(`${BASE}/quotas?${query.toString()}`);
}

export async function setQuota(input: {
  shopId: string;
  quotaLimit: number;
  periodStart?: string | null;
  periodEnd?: string | null;
}): Promise<ComplianceAppealQuotaUpdate> {
  return api<ComplianceAppealQuotaUpdate>(`${BASE}/quotas`, 'PUT', input);
}

export async function fetchStats(
  filters: { shopId?: string; from?: string; to?: string } = {},
): Promise<ComplianceAppealStats> {
  const query = new URLSearchParams();
  if (filters.shopId) query.set('shopId', filters.shopId);
  if (filters.from) query.set('from', filters.from);
  if (filters.to) query.set('to', filters.to);
  return api<ComplianceAppealStats>(`${BASE}/stats?${query.toString()}`);
}

export async function fetchPriorityQueue(
  page = 0,
  size = 20,
  filters: { shopId?: string; appealableOnly?: boolean } = {},
): Promise<ComplianceAppealPriorityPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.shopId) query.set('shopId', filters.shopId);
  if (filters.appealableOnly) query.set('appealableOnly', 'true');
  return api<ComplianceAppealPriorityPage>(`${BASE}/priority-queue?${query.toString()}`);
}
