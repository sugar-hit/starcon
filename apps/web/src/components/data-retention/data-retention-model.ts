import { api } from '../inventory/api';

/**
 * 16.6.2.1 数据出境与留存 前端模型。
 *
 * 蓝图：「管理数据合规：跨境数据传输登记与合法性基础、数据留存期配置、到期清理与删除请求（被遗忘权）、
 * 保留期与财务留存期的区分。输入：数据流登记；留存策略；删除请求。
 * 输出：合规登记 + 清理/删除执行记录。关系：上游 = 17 权限、全部数据域；下游 = 数据清理任务。
 * **财务数据有法定留存期，PII 有 GDPR 删除权，两者冲突时按财务留存优先**——实体目录 §5 已明确这条边界。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「财务留存优先」必须看得见裁决过程**：冲突对、双方期限、裁决结果与依据四件齐全，
 *    不能只显示一个「已处理」。
 * 2. **「推迟」与「驳回」是两种状态**：法定留存期没走完 ⇒ 推迟到某一天；
 *    被拒 ⇒ 驳回。折成一个「没批」就没法做合规台账了。
 * 3. **「没登记留存策略」（判不了）与「没冲突」是两种状态**：
 *    判不了不是默认放行，界面上必须显式区分。
 * 4. **未交付的下游如实标**：12 财务（finance 服务）与下游数据清理任务都还没落地，
 *    页面上写成 `available=false` + 归属模块，**不编造**它们的内容。
 * 5. **前端不重算裁决**：生效留存期、是否被挡、推到哪天，全部按后端给的字段渲染，
 *    前端不自己拿两个期限比大小。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**）。 */
export const BASE = 'channel/data-governance';

/** 留存期类别。 */
export const RETENTION_CLASSES = [
  { value: 'STANDARD', label: '普通留存期' },
  { value: 'FINANCIAL', label: '法定（财务）留存期' },
] as const;

/** 合法性基础（受控值域）。 */
export const LEGAL_BASES = [
  { value: 'CONSENT', label: '同意（GDPR 6(1)(a)）' },
  { value: 'CONTRACT', label: '履行合同所必需（GDPR 6(1)(b)）' },
  { value: 'LEGAL_OBLIGATION', label: '法定义务（GDPR 6(1)(c)）' },
  { value: 'LEGITIMATE_INTEREST', label: '正当利益（GDPR 6(1)(f)）' },
] as const;

/** 数据分类。 */
export const DATA_CLASSES = [
  { value: 'PII', label: '个人信息' },
  { value: 'FINANCIAL', label: '财务数据' },
  { value: 'OPERATIONAL', label: '运营数据' },
  { value: 'MARKETING', label: '营销数据' },
] as const;

/** 删除请求类型。 */
export const REQUEST_TYPES = [
  { value: 'FORGETTING_RIGHT', label: '被遗忘权（GDPR 第 17 条）' },
  { value: 'CLEANUP', label: '定期清理申请' },
] as const;

/** 删除请求状态。 */
export const DELETION_STATUSES = [
  { value: 'PENDING', label: '待审批' },
  { value: 'APPROVED', label: '已批准（未执行）' },
  { value: 'DEFERRED', label: '已推迟（财务留存优先）' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'EXECUTED', label: '已执行（已派发清理工单）' },
] as const;

/** 清理工单触发来源。 */
export const TRIGGER_KINDS = [
  { value: 'RETENTION_EXPIRY', label: '留存期到期' },
  { value: 'DELETION_REQUEST', label: '删除请求' },
  { value: 'MANUAL', label: '人工发起' },
] as const;

/** 清理执行结果。 */
export const CLEANUP_RESULTS = [
  { value: 'SUCCESS', label: '全部成功' },
  { value: 'PARTIAL', label: '部分成功' },
  { value: 'FAILED', label: '失败' },
] as const;

// ================================================================ 类型

/** 上游读面可用性。 */
export interface DataGovernanceUpstreamState {
  sourceCode: string;
  sourceLabel: string;
  available: boolean;
  /** 这个上游归哪个模块（available=false 时必填）。 */
  ownerModule: string;
  note: string;
}

/** 能力声明。 */
export interface DataGovernanceCapabilities {
  upstreams: DataGovernanceUpstreamState[];
  transferSemantics: string;
  retentionSemantics: string;
  conflictSemantics: string;
  deletionSemantics: string;
  cleanupSemantics: string;
  auditSemantics: string;
  boundaryNote: string;
  downstreamNote: string;
}

/** 数据域。 */
export interface DataGovernanceDomain {
  domainId: string;
  domainCode: string;
  domainName: string;
  ownerModule: string;
  dataClass: string;
  dataClassLabel: string;
  containsPii: boolean;
  statutoryFinancial: boolean;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

/** 数据域入参。 */
export interface DataGovernanceDomainInput {
  domainCode: string;
  domainName: string;
  ownerModule: string;
  dataClass: string;
  containsPii: boolean | null;
  statutoryFinancial: boolean | null;
  note: string;
}

/** 数据域列表。 */
export interface DataGovernanceDomainPage {
  items: DataGovernanceDomain[];
  page: number;
  size: number;
  total: number;
}

/** 跨境传输登记。 */
export interface DataGovernanceTransfer {
  transferId: string;
  transferNo: string;
  sourceDomainCode: string;
  sourceDomainName: string;
  targetRegion: string;
  recipient: string;
  legalBasis: string;
  legalBasisLabel: string;
  safeguards: string;
  tiaRef: string;
  status: string;
  statusLabel: string;
  registeredBy: string;
  registeredByName: string;
  registeredAt: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  suspendReason: string;
  endReason: string;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

/** 跨境传输入参。 */
export interface DataGovernanceTransferInput {
  sourceDomainCode: string;
  targetRegion: string;
  recipient: string;
  legalBasis: string;
  safeguards: string;
  tiaRef: string;
  effectiveFrom: string;
  note: string;
}

/** 跨境传输列表。 */
export interface DataGovernanceTransferPage {
  items: DataGovernanceTransfer[];
  page: number;
  size: number;
  total: number;
  counts: { total: number; active: number; suspended: number; ended: number };
}

/** 留存策略。 */
export interface RetentionPolicy {
  policyId: string;
  domainCode: string;
  domainName: string;
  policyName: string;
  retentionClass: string;
  retentionClassLabel: string;
  /** 天数是数量（NUMERIC(19,6)），前端按 number 收。 */
  retentionDays: number;
  retentionTrigger: string;
  retentionTriggerLabel: string;
  legalBasis: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  status: string;
  statusLabel: string;
  supersedesId: string | null;
  versionNo: number;
  createdBy: string;
  createdAt: string;
}

/** 留存策略入参。 */
export interface RetentionPolicyInput {
  domainCode: string;
  policyName: string;
  retentionClass: string;
  retentionDays: number;
  retentionTrigger: string;
  legalBasis: string;
  effectiveFrom: string;
}

/** 留存策略列表。 */
export interface RetentionPolicyPage {
  items: RetentionPolicy[];
  page: number;
  size: number;
  total: number;
}

/**
 * 留存期裁决（冲突对 + 双方期限 + 裁决结果与依据）。
 *
 * 🔴 界面上**必须**把冲突对与裁决依据一起显示出来 ——
 * 只显示「已裁决」等于没告诉运营依据是什么。
 */
export interface RetentionConflict {
  domainCode: string;
  domainName: string;
  standardPolicyId: string | null;
  standardRetentionDays: number | null;
  standardLegalBasis: string;
  financialPolicyId: string | null;
  financialRetentionDays: number | null;
  financialLegalBasis: string;
  conflictKind: string;
  conflictKindLabel: string;
  resolution: string;
  resolutionLabel: string;
  effectiveRetentionDays: number | null;
  deletionBlockedUntil: string | null;
  deletionBlocked: boolean;
  rationale: string;
  lastAdjudicatedAt: string | null;
}

/** 裁决清单。 */
export interface RetentionConflictPage {
  items: RetentionConflict[];
  page: number;
  size: number;
  total: number;
  counts: { total: number; conflicting: number; deletionBlocked: number };
}

/** 删除请求。 */
export interface DataGovernanceDeletionRequest {
  requestId: string;
  requestNo: string;
  requestType: string;
  requestTypeLabel: string;
  subjectKind: string;
  subjectRef: string;
  domainCode: string;
  domainName: string;
  reason: string;
  status: string;
  statusLabel: string;
  requestedBy: string;
  requestedByName: string;
  requestedAt: string;
  approvedBy: string | null;
  approvedByName: string | null;
  approvedAt: string | null;
  decisionNote: string;
  executedBy: string | null;
  executedByName: string | null;
  executedAt: string | null;
  deferUntil: string | null;
  deferReason: string;
  cleanupWorkOrderId: string | null;
  cleanupOrderNo: string | null;
  createdAt: string;
}

/** 删除请求入参。 */
export interface DataGovernanceDeletionRequestInput {
  requestType: string;
  subjectKind: string;
  subjectRef: string;
  domainCode: string;
  reason: string;
}

/** 删除请求列表。 */
export interface DataGovernanceDeletionPage {
  items: DataGovernanceDeletionRequest[];
  page: number;
  size: number;
  total: number;
  counts: {
    total: number;
    pending: number;
    approved: number;
    deferred: number;
    rejected: number;
    executed: number;
  };
}

/** 清理工单。 */
export interface RetentionCleanupOrder {
  orderId: string;
  orderNo: string;
  domainCode: string;
  domainName: string;
  subjectRef: string;
  triggerKind: string;
  triggerKindLabel: string;
  retentionDays: number;
  retentionClass: string;
  retentionClassLabel: string;
  affectedRecordCount: number;
  status: string;
  statusLabel: string;
  eligibleAt: string;
  dueAt: string;
  requestedBy: string;
  requestedByName: string;
  createdAt: string;
  updatedAt: string;
}

/** 清理工单入参。 */
export interface RetentionCleanupOrderInput {
  domainCode: string;
  subjectRef: string;
  triggerKind: string;
  dueAt: string | null;
  affectedRecordCount: number | null;
}

/** 清理工单列表。 */
export interface RetentionCleanupPage {
  items: RetentionCleanupOrder[];
  page: number;
  size: number;
  total: number;
  counts: {
    total: number;
    pending: number;
    inProgress: number;
    completed: number;
    partial: number;
    failed: number;
  };
}

/** 清理执行记录。 */
export interface RetentionCleanupRecord {
  recordId: string;
  orderId: string;
  orderNo: string;
  domainCode: string;
  domainName: string;
  action: string;
  actionLabel: string;
  result: string;
  resultLabel: string;
  recordCount: number;
  message: string;
  executedBy: string;
  executedByName: string;
  executedAt: string;
}

/** 清理执行记录列表。 */
export interface RetentionCleanupRecordPage {
  items: RetentionCleanupRecord[];
  page: number;
  size: number;
  total: number;
}

// ================================================================ 取数

export async function fetchCapabilities(): Promise<DataGovernanceCapabilities> {
  return api<DataGovernanceCapabilities>(`${BASE}/capabilities`);
}

export async function fetchDomains(
  domainCode = '',
  page = 0,
  size = 20,
): Promise<DataGovernanceDomainPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (domainCode.trim()) query.set('domainCode', domainCode.trim());
  return api<DataGovernanceDomainPage>(`${BASE}/domains?${query}`);
}

export async function saveDomain(input: DataGovernanceDomainInput): Promise<DataGovernanceDomain> {
  return api<DataGovernanceDomain>(`${BASE}/domains`, 'PUT', input);
}

export async function fetchTransfers(
  status = '',
  domainCode = '',
  page = 0,
  size = 20,
): Promise<DataGovernanceTransferPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (status.trim()) query.set('status', status.trim());
  if (domainCode.trim()) query.set('domainCode', domainCode.trim());
  return api<DataGovernanceTransferPage>(`${BASE}/transfers?${query}`);
}

export async function createTransfer(
  input: DataGovernanceTransferInput,
): Promise<DataGovernanceTransfer> {
  return api<DataGovernanceTransfer>(`${BASE}/transfers`, 'POST', input);
}

export async function suspendTransfer(
  transferId: string,
  reason: string,
): Promise<DataGovernanceTransfer> {
  return api<DataGovernanceTransfer>(`${BASE}/transfers/${transferId}/suspend`, 'POST', { reason });
}

export async function endTransfer(
  transferId: string,
  reason: string,
): Promise<DataGovernanceTransfer> {
  return api<DataGovernanceTransfer>(`${BASE}/transfers/${transferId}/end`, 'POST', { reason });
}

export async function fetchPolicies(
  domainCode = '',
  page = 0,
  size = 20,
): Promise<RetentionPolicyPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (domainCode.trim()) query.set('domainCode', domainCode.trim());
  return api<RetentionPolicyPage>(`${BASE}/retention-policies?${query}`);
}

export async function createPolicy(input: RetentionPolicyInput): Promise<RetentionPolicy> {
  return api<RetentionPolicy>(`${BASE}/retention-policies`, 'POST', input);
}

export async function fetchConflicts(
  domainCode = '',
  page = 0,
  size = 20,
): Promise<RetentionConflictPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (domainCode.trim()) query.set('domainCode', domainCode.trim());
  return api<RetentionConflictPage>(`${BASE}/retention-conflicts?${query}`);
}

export async function fetchDeletionRequests(
  status = '',
  domainCode = '',
  page = 0,
  size = 20,
): Promise<DataGovernanceDeletionPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (status.trim()) query.set('status', status.trim());
  if (domainCode.trim()) query.set('domainCode', domainCode.trim());
  return api<DataGovernanceDeletionPage>(`${BASE}/deletion-requests?${query}`);
}

export async function createDeletionRequest(
  input: DataGovernanceDeletionRequestInput,
): Promise<DataGovernanceDeletionRequest> {
  return api<DataGovernanceDeletionRequest>(`${BASE}/deletion-requests`, 'POST', input);
}

export async function approveDeletion(
  requestId: string,
  decisionNote = '',
): Promise<DataGovernanceDeletionRequest> {
  return api<DataGovernanceDeletionRequest>(
    `${BASE}/deletion-requests/${requestId}/approve`,
    'POST',
    {
      decisionNote,
    },
  );
}

export async function rejectDeletion(
  requestId: string,
  decisionNote = '',
): Promise<DataGovernanceDeletionRequest> {
  return api<DataGovernanceDeletionRequest>(
    `${BASE}/deletion-requests/${requestId}/reject`,
    'POST',
    {
      decisionNote,
    },
  );
}

export async function executeDeletion(requestId: string): Promise<DataGovernanceDeletionRequest> {
  return api<DataGovernanceDeletionRequest>(
    `${BASE}/deletion-requests/${requestId}/execute`,
    'POST',
  );
}

export async function fetchCleanupOrders(
  status = '',
  domainCode = '',
  page = 0,
  size = 20,
): Promise<RetentionCleanupPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (status.trim()) query.set('status', status.trim());
  if (domainCode.trim()) query.set('domainCode', domainCode.trim());
  return api<RetentionCleanupPage>(`${BASE}/cleanup-orders?${query}`);
}

export async function createCleanupOrder(
  input: RetentionCleanupOrderInput,
): Promise<RetentionCleanupOrder> {
  return api<RetentionCleanupOrder>(`${BASE}/cleanup-orders`, 'POST', input);
}

export async function executeCleanupOrder(
  orderId: string,
  result: string,
  recordCount: number,
  message = '',
): Promise<RetentionCleanupOrder> {
  return api<RetentionCleanupOrder>(`${BASE}/cleanup-orders/${orderId}/execute`, 'POST', {
    result,
    recordCount,
    message,
  });
}

export async function fetchCleanupRecords(
  orderId = '',
  domainCode = '',
  page = 0,
  size = 20,
): Promise<RetentionCleanupRecordPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (orderId.trim()) query.set('orderId', orderId.trim());
  if (domainCode.trim()) query.set('domainCode', domainCode.trim());
  return api<RetentionCleanupRecordPage>(`${BASE}/cleanup-records?${query}`);
}

// ================================================================ 展示映射（纯函数）

/** 留存期类别标签。 */
export function retentionClassLabel(retentionClass: string): string {
  if (retentionClass === 'FINANCIAL') return '法定（财务）留存期';
  if (retentionClass === 'STANDARD') return '普通留存期';
  if (retentionClass === 'NONE') return '不适用留存期';
  return retentionClass || '未登记';
}

/** 冲突形态标签。 */
export function conflictKindLabel(kind: string): string {
  switch (kind) {
    case 'NO_POLICY':
      return '没登记留存策略（判不了）';
    case 'FINANCIAL_LONGER':
      return '财务（法定）留存期更长';
    case 'STANDARD_LONGER':
      return '普通留存期更长';
    case 'STANDARD_ONLY':
      return '只有普通留存期';
    case 'FINANCIAL_ONLY':
      return '只有财务（法定）留存期';
    case 'NO_CONFLICT':
      return '没有冲突';
    default:
      return kind || '未知';
  }
}

/** 裁决结果标签。 */
export function resolutionLabel(resolution: string): string {
  switch (resolution) {
    case 'FINANCIAL_PREVAILS':
      return '财务留存优先';
    case 'STANDARD_APPLIES':
      return '按普通留存期';
    case 'NO_CONFLICT':
      return '无冲突';
    default:
      return resolution || '未裁决';
  }
}

/** 裁决结果的徽章色调。 */
export function resolutionTone(resolution: string): 'success' | 'warning' | 'neutral' {
  if (resolution === 'FINANCIAL_PREVAILS') return 'warning';
  if (resolution === 'STANDARD_APPLIES') return 'success';
  return 'neutral';
}

/** 删除请求状态标签。 */
export function deletionStatusLabel(status: string): string {
  return DELETION_STATUSES.find((s) => s.value === status)?.label ?? status;
}

/** 删除请求状态徽章色调。 */
export function deletionStatusTone(
  status: string,
): 'neutral' | 'warning' | 'danger' | 'success' | 'info' {
  if (status === 'PENDING') return 'warning';
  if (status === 'APPROVED') return 'success';
  if (status === 'DEFERRED') return 'info';
  if (status === 'REJECTED') return 'danger';
  if (status === 'EXECUTED') return 'neutral';
  return 'neutral';
}

/** 传输状态标签。 */
export function transferStatusLabel(status: string): string {
  switch (status) {
    case 'ACTIVE':
      return '进行中';
    case 'SUSPENDED':
      return '已暂停';
    case 'ENDED':
      return '已终止';
    default:
      return status || '未知';
  }
}

/** 传输状态徽章色调。 */
export function transferStatusTone(status: string): 'neutral' | 'warning' | 'danger' | 'success' {
  if (status === 'ACTIVE') return 'success';
  if (status === 'SUSPENDED') return 'warning';
  if (status === 'ENDED') return 'danger';
  return 'neutral';
}

/** 清理工单状态标签。 */
export function cleanupStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '待处理';
    case 'IN_PROGRESS':
      return '处理中';
    case 'COMPLETED':
      return '已完成';
    case 'PARTIAL':
      return '部分完成';
    case 'FAILED':
      return '失败';
    case 'CANCELLED':
      return '已取消';
    default:
      return status || '未知';
  }
}

/** 清理工单状态徽章色调。 */
export function cleanupStatusTone(
  status: string,
): 'neutral' | 'warning' | 'danger' | 'success' | 'info' {
  if (status === 'COMPLETED') return 'success';
  if (status === 'PARTIAL') return 'warning';
  if (status === 'FAILED') return 'danger';
  if (status === 'IN_PROGRESS') return 'info';
  return 'neutral';
}

/** 天数的人话写法（去掉多余的 .0；空值写「未登记」而不是 0）。 */
export function daysText(days: number | null | undefined): string {
  if (days === null || days === undefined) return '未登记';
  if (!Number.isFinite(days)) return '未登记';
  return String(Number(days));
}

/** 只有日期的短写法（时区偏移由后端给，前端不自己换算时区）。 */
export function dateText(at: string | null | undefined): string {
  if (!at) return '—';
  return at.slice(0, 10);
}

/**
 * 裁决摘要 —— 「冲突对 / 双方期限 / 裁决结果 / 依据」四件齐全的一段话。
 *
 * 🔴 页面上**必须**显示它：只显示「已裁决」，运营根本不知道依据是什么。
 */
export function conflictSummary(conflict: RetentionConflict): string {
  const standard = daysText(conflict.standardRetentionDays);
  const financial = daysText(conflict.financialRetentionDays);
  const effective = daysText(conflict.effectiveRetentionDays);
  if (conflict.conflictKind === 'NO_POLICY') {
    return `「${conflict.domainName}」没有登记任何留存策略 —— 判不了能不能删。`;
  }
  return (
    `「${conflict.domainName}」：普通留存期 ${standard} 天` +
    (conflict.standardLegalBasis ? `（依据：${conflict.standardLegalBasis}）` : '') +
    ` vs 法定（财务）留存期 ${financial} 天` +
    (conflict.financialLegalBasis ? `（依据：${conflict.financialLegalBasis}）` : '') +
    ` ⇒ 裁决「${conflict.resolutionLabel}」，生效留存期 ${effective} 天。`
  );
}

/**
 * 推迟说明 —— 「被推迟到哪一天、为什么」。
 *
 * 🔴 「推迟」与「驳回」是两件事，界面上不能都写成「没批」。
 */
export function deferralText(request: DataGovernanceDeletionRequest): string {
  if (request.status !== 'DEFERRED') return '';
  const until = dateText(request.deferUntil);
  return `财务（法定）留存优先：推迟到 ${until} 才可执行。${request.deferReason}`;
}

/** 这条删除请求现在能不能执行（被推迟的不行）。 */
export function canExecuteDeletion(request: DataGovernanceDeletionRequest): boolean {
  return request.status === 'APPROVED';
}

/** 这条删除请求能不能审批（只有待审批的能）。 */
export function canApproveDeletion(request: DataGovernanceDeletionRequest): boolean {
  return request.status === 'PENDING';
}

/** 这张清理工单能不能执行（已完成 / 已取消的不行）。 */
export function canExecuteCleanup(order: RetentionCleanupOrder): boolean {
  return order.status !== 'COMPLETED' && order.status !== 'CANCELLED';
}

/** 成员标识的短码（拿不到显示名时也不编造）。 */
export function actorLabel(actorId: string | null | undefined): string {
  return actorId ? `成员 ${actorId.slice(0, 8)}` : '未知成员';
}

/**
 * 「读不到」态 —— 上游还没交付时的说法。
 *
 * 🔴 与「空态」是**两件不同的事**：空态是「确实没有数据」，
 * 「读不到」是「这个上游还没交付 / 读不到真值」。
 * 折成一个空列表，界面就会说「没有财务数据」，而真相是「12 财务还没落地」。
 */
export function unavailableState(upstream: DataGovernanceUpstreamState): {
  title: string;
  description: string;
} {
  return {
    title: `${upstream.sourceLabel} 尚未交付`,
    description:
      `该上游当前**读不到真值**（归 ${upstream.ownerModule || '未标注归属模块'} 负责）。` +
      `${upstream.note || ''} 这一块**不是「没有数据」**，而是「还没落地」—— ` +
      `界面不会用空列表冒充它。`,
  };
}

/** 找出读不到真值的上游。 */
export function unavailableUpstreams(
  capabilities: DataGovernanceCapabilities | null,
): DataGovernanceUpstreamState[] {
  if (!capabilities) return [];
  return capabilities.upstreams.filter((u) => !u.available);
}

/**
 * 页面各区块的空态文案（**每种都不一样**，不是同一个空态换个图标）。
 */
export function emptyState(
  kind:
    | 'NO_DOMAIN'
    | 'NO_TRANSFER'
    | 'NO_POLICY'
    | 'NO_CONFLICT'
    | 'NO_DELETION'
    | 'NO_CLEANUP'
    | 'NO_RECORD'
    | 'FILTER_EMPTY',
): { title: string; description: string } {
  switch (kind) {
    case 'NO_DOMAIN':
      return {
        title: '还没有登记数据域',
        description:
          '数据域登记表是这个模块的核心事实 —— 留存策略、跨境传输、删除请求都挂在它上面。先登记数据域（含不含 PII、是不是财务数据）。',
      };
    case 'NO_TRANSFER':
      return {
        title: '还没有跨境传输登记',
        description:
          '这里为空说明本租户**没有登记过**对外的数据传输 —— 不是「读不到」。有跨境传输时必须写清合法性基础与接收方法域。',
      };
    case 'NO_POLICY':
      return {
        title: '还没有留存策略',
        description:
          '没有留存策略的数据域**判不了能不能删** —— 删除请求在审批时会被拒绝（判不了 ≠ 默认放行）。先登记留存策略。',
      };
    case 'NO_CONFLICT':
      return {
        title: '没有留存期冲突',
        description:
          '这个数据域只有一类留存期，或两类的期限相同 ⇒ 没有需要裁决的冲突。注意「没冲突」和「没登记策略」是两回事。',
      };
    case 'NO_DELETION':
      return {
        title: '还没有删除请求',
        description:
          '删除请求走「申请 → 审批 → 执行」三段式，审批人不能是申请人本人。法定留存期没走完的请求会被**推迟**而不是驳回。',
      };
    case 'NO_CLEANUP':
      return {
        title: '还没有清理工单',
        description:
          '清理工单是**可供下游数据清理任务消费的状态**。本模块不跨服务删业务数据 —— 工单是产出，真正的删除由下游执行。',
      };
    case 'NO_RECORD':
      return {
        title: '还没有清理执行记录',
        description:
          '执行记录是 append-only 的凭据（不可改、不可删）。下游回报执行结果后，这里才会出现记录。',
      };
    case 'FILTER_EMPTY':
      return {
        title: '筛选条件下没有匹配项',
        description: '当前筛选条件过窄或过新，**放宽筛选**即可看到已有记录 —— 这不是「没有数据」。',
      };
    default:
      return { title: '暂无数据', description: '当前条件下没有可展示的记录。' };
  }
}
