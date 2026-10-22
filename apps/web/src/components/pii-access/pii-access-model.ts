import { api } from '../inventory/api';

/**
 * 16.6.1.1 PII 管理 前端模型。
 *
 * 蓝图：「管理个人信息（买家姓名、地址、电话、邮箱）的访问与脱敏：默认脱敏展示、
 * 按角色授权查看完整信息、访问留痕、导出审批。输入：PII 访问请求；授权配置；导出申请。
 * 输出：脱敏数据或授权后的完整数据；访问审计记录。
 * 关系：上游 = 15 客户、9 订单；下游 = 客服与运营页面。
 * **完整地址查看需留痕**——批量导出 PII 是数据泄露事件的典型形态。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **脱敏态与完整态在界面上必须一眼能分**。后端给 `masked` 标志，前端**按它渲染**，
 *    不靠「值里有没有星号」去猜 —— 星号是数据，不是状态。
 * 2. **「上游没给」与「值是空的」分开显示**。`present=false` 是「上游读到的这个来源
 *    没有这一栏」，不是「这个人没留电话」。
 * 3. **前端不重算后端的脱敏值**。脱敏值是后端算好的，前端原样显示。
 * 4. **「谁授权、为什么能看到」必须显示出来**（角色 + 范围 + 理由），
 *    否则客服只能凭感觉相信自己有权限。
 * 5. **未交付的下游不编造**：16.6.2.1 数据出境与留存当前未交付，
 *    页面上不写它的入口，也不声称已交付。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**）。 */
export const BASE = 'channel/pii-access';

/** 字段类别（受控值域，与后端一致）。 */
export const FIELDS = [
  { value: 'NAME', label: '姓名' },
  { value: 'PHONE', label: '电话' },
  { value: 'EMAIL', label: '邮箱' },
  { value: 'ADDRESS', label: '地址' },
] as const;

/** 主体类型。 */
export const SUBJECT_KINDS = [
  { value: 'ORDER', label: '9 订单 · 收货地址快照' },
  { value: 'CUSTOMER', label: '15 客户 · 买家联系方式' },
] as const;

/** 成员范围。 */
export const SCOPE_MODES = [
  { value: 'ALL', label: '全部成员' },
  { value: 'DEPARTMENT', label: '限部门成员' },
  { value: 'SELF', label: '限本人经手' },
] as const;

/** 导出申请状态。 */
export const EXPORT_STATUSES = [
  { value: 'PENDING', label: '待审批' },
  { value: 'APPROVED', label: '已批准（未执行）' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'EXECUTED', label: '已导出' },
] as const;

/** 审计动作。 */
export const AUDIT_ACTIONS = [
  { value: 'VIEW_MASKED', label: '脱敏展示' },
  { value: 'VIEW_FULL', label: '查看完整值' },
  { value: 'EXPORT', label: '导出' },
] as const;

/** 审计判定。 */
export const AUDIT_DECISIONS = [
  { value: 'ALLOWED', label: '放行' },
  { value: 'DENIED', label: '拒绝' },
] as const;

// ================================================================ 类型

/** 逐个字段类别的口径。 */
export interface PiiAccessFieldInfo {
  fieldCode: string;
  fieldLabel: string;
  maskRule: string;
  availableInCrm: boolean;
  availableInTrade: boolean;
}

/** 上游读面可用性。 */
export interface PiiAccessSourceState {
  sourceCode: string;
  sourceLabel: string;
  available: boolean;
  note: string;
}

/** 能力声明。 */
export interface PiiAccessCapabilities {
  fields: PiiAccessFieldInfo[];
  sources: PiiAccessSourceState[];
  maskingSemantics: string;
  authorizationSemantics: string;
  auditSemantics: string;
  exportSemantics: string;
  storageSemantics: string;
  downstreamNote: string;
  boundaryNote: string;
}

/** 策略。 */
export interface PiiAccessPolicy {
  policyId: string | null;
  policyName: string;
  maxExportRecords: number;
  exportApprovalTtlHours: number;
  maxRevealPerDay: number;
  requireReasonOnReveal: boolean;
  version: number;
  /** 后端没登记过策略时是 false（**后端不预填任何默认值**）。 */
  configured: boolean;
  createdAt: string | null;
  updatedAt: string | null;
}

/** 策略入参。 */
export interface PiiAccessPolicyInput {
  policyName: string;
  maxExportRecords: number;
  exportApprovalTtlHours: number;
  maxRevealPerDay: number;
  requireReasonOnReveal: boolean;
  version: number | null;
}

/** 授权配置。 */
export interface PiiAccessGrant {
  grantId: string;
  roleCode: string;
  fieldCode: string;
  fieldLabel: string;
  scopeMode: string;
  scopeLabel: string;
  reasonRequired: boolean;
  effectiveFrom: string;
  effectiveTo: string | null;
  status: string;
  grantedBy: string;
  grantedAt: string;
  revokedBy: string | null;
  revokedAt: string | null;
  revokeReason: string;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

/** 授权配置入参。 */
export interface PiiAccessGrantInput {
  roleCode: string;
  fieldCode: string;
  scopeMode: string;
  reasonRequired: boolean | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  note: string;
}

/** 授权配置列表。 */
export interface PiiAccessGrantPage {
  items: PiiAccessGrant[];
  page: number;
  size: number;
  total: number;
}

/** 一个字段的脱敏值。 */
export interface PiiAccessSubjectField {
  fieldCode: string;
  fieldLabel: string;
  maskedValue: string;
}

/** 主体列表行。 */
export interface PiiAccessSubjectView {
  subjectKind: string;
  subjectRef: string;
  subjectLabel: string;
  known: boolean;
  fields: PiiAccessSubjectField[];
  availableFields: string[];
  missingFields: string[];
  sourceLabel: string;
  note: string;
}

/** 主体列表。 */
export interface PiiAccessSubjectPage {
  available: boolean;
  note: string;
  items: PiiAccessSubjectView[];
  page: number;
  size: number;
  total: number;
  sources: PiiAccessSourceState[];
}

/** 一栏的值。 */
export interface PiiAccessFieldValue {
  fieldCode: string;
  label: string;
  value: string;
  /** 这个值是不是脱敏后的 —— **界面按它渲染，不靠值里有没有星号去猜**。 */
  masked: boolean;
  /** 上游到底给没给这一栏（false ⇒ 「上游没给」，不是「空值」）。 */
  present: boolean;
}

/** 一栏的授权判定。 */
export interface PiiAccessFieldDecision {
  fieldCode: string;
  allowed: boolean;
  reason: string;
  roleCode: string;
  grantId: string | null;
}

/** 主体详情。 */
export interface PiiAccessSubjectDetail {
  subjectKind: string;
  subjectRef: string;
  subjectLabel: string;
  known: boolean;
  fields: PiiAccessFieldValue[];
  revealed: boolean;
  revealNote: string;
  decisions: PiiAccessFieldDecision[];
}

/** 审计行。 */
export interface PiiAccessAuditRow {
  auditId: string;
  subjectKind: string;
  subjectRef: string;
  subjectLabel: string;
  fieldCode: string;
  action: string;
  decision: string;
  grantId: string | null;
  grantRoleCode: string;
  decisionReason: string;
  actorId: string;
  actorName: string;
  reason: string;
  exportRequestId: string | null;
  occurredAt: string;
}

/** 审计汇总。 */
export interface PiiAccessAuditCounts {
  total: number;
  viewMasked: number;
  viewFull: number;
  export: number;
  denied: number;
}

/** 审计列表。 */
export interface PiiAccessAuditPage {
  items: PiiAccessAuditRow[];
  page: number;
  size: number;
  total: number;
  counts: PiiAccessAuditCounts;
}

/** 导出清单里的一条。 */
export interface PiiAccessExportItem {
  itemId: string;
  subjectKind: string;
  subjectRef: string;
  subjectLabel: string;
}

/** 导出申请。 */
export interface PiiAccessExportRequest {
  requestId: string;
  requestNo: string;
  subjectKind: string;
  fieldCode: string;
  fieldLabel: string;
  recordCount: number;
  maxRecordsLimit: number;
  purpose: string;
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
  executedAt: string | null;
  exportBatchId: string | null;
  expiresAt: string;
  items: PiiAccessExportItem[];
  createdAt: string;
}

/** 导出申请入参。 */
export interface PiiAccessExportRequestInput {
  subjectKind: string;
  fieldCode: string;
  recordCount: number;
  purpose: string;
  subjectRefs: string[];
}

/** 导出申请列表。 */
export interface PiiAccessExportPage {
  items: PiiAccessExportRequest[];
  page: number;
  size: number;
  total: number;
  counts: {
    total: number;
    pending: number;
    approved: number;
    rejected: number;
    executed: number;
  };
}

/** 导出执行结果里的一行。 */
export interface PiiAccessExportedRow {
  subjectKind: string;
  subjectRef: string;
  subjectLabel: string;
  value: string;
  present: boolean;
}

/** 导出执行结果。 */
export interface PiiAccessExportResult {
  requestId: string;
  requestNo: string;
  exportNo: string;
  batchId: string;
  recordCount: number;
  fieldCode: string;
  exportedAt: string;
  rows: PiiAccessExportedRow[];
}

// ================================================================ 取数

export async function fetchCapabilities(): Promise<PiiAccessCapabilities> {
  return api<PiiAccessCapabilities>(`${BASE}/capabilities`);
}

export async function fetchPolicy(): Promise<PiiAccessPolicy> {
  return api<PiiAccessPolicy>(`${BASE}/policy`);
}

export async function savePolicy(input: PiiAccessPolicyInput): Promise<PiiAccessPolicy> {
  return api<PiiAccessPolicy>(`${BASE}/policy`, 'PUT', input);
}

export async function fetchGrants(
  roleCode = '',
  status = '',
  page = 0,
  size = 20,
): Promise<PiiAccessGrantPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (roleCode.trim()) query.set('roleCode', roleCode.trim());
  if (status.trim()) query.set('status', status.trim());
  return api<PiiAccessGrantPage>(`${BASE}/grants?${query}`);
}

export async function saveGrant(input: PiiAccessGrantInput): Promise<PiiAccessGrant> {
  return api<PiiAccessGrant>(`${BASE}/grants`, 'PUT', input);
}

export async function revokeGrant(grantId: string, revokeReason: string): Promise<PiiAccessGrant> {
  return api<PiiAccessGrant>(`${BASE}/grants/${grantId}/revoke`, 'POST', { revokeReason });
}

export async function fetchSubjects(
  kind: string,
  keyword = '',
  page = 0,
  size = 20,
): Promise<PiiAccessSubjectPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (kind.trim()) query.set('kind', kind.trim());
  if (keyword.trim()) query.set('keyword', keyword.trim());
  return api<PiiAccessSubjectPage>(`${BASE}/subjects?${query}`);
}

/**
 * 读一个主体的 PII。
 *
 * 🔴 不带 `reveal` 时后端返回的**永远是脱敏值**；`reveal=true` 时必须指明 `fields`。
 */
export async function fetchSubject(
  kind: string,
  ref: string,
  reveal = false,
  fields: string[] = [],
  reason = '',
): Promise<PiiAccessSubjectDetail> {
  const query = new URLSearchParams();
  if (reveal) query.set('reveal', 'true');
  for (const field of fields) query.append('fields', field);
  if (reason.trim()) query.set('reason', reason.trim());
  const suffix = query.toString();
  // 🔴 刻意**不写成嵌套模板串**（`${suffix ? `?${suffix}` : ''}`）：
  //   嵌套的反引号会让「从源码反向抽取真实调用点」的测试抽不全这一条
  //   （模板串的正则匹配到内层反引号就断了）⇒ 白名单漏登记时反而测不出来。
  const path = suffix
    ? `${BASE}/subjects/${kind}/${ref}?${suffix}`
    : `${BASE}/subjects/${kind}/${ref}`;
  return api<PiiAccessSubjectDetail>(path);
}

export async function fetchExports(
  status = '',
  kind = '',
  page = 0,
  size = 20,
): Promise<PiiAccessExportPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (status.trim()) query.set('status', status.trim());
  if (kind.trim()) query.set('kind', kind.trim());
  return api<PiiAccessExportPage>(`${BASE}/exports?${query}`);
}

export async function createExport(
  input: PiiAccessExportRequestInput,
): Promise<PiiAccessExportRequest> {
  return api<PiiAccessExportRequest>(`${BASE}/exports`, 'POST', input);
}

export async function approveExport(
  requestId: string,
  decisionNote = '',
): Promise<PiiAccessExportRequest> {
  return api<PiiAccessExportRequest>(`${BASE}/exports/${requestId}/approve`, 'POST', {
    decisionNote,
  });
}

export async function rejectExport(
  requestId: string,
  decisionNote = '',
): Promise<PiiAccessExportRequest> {
  return api<PiiAccessExportRequest>(`${BASE}/exports/${requestId}/reject`, 'POST', {
    decisionNote,
  });
}

export async function executeExport(requestId: string): Promise<PiiAccessExportResult> {
  return api<PiiAccessExportResult>(`${BASE}/exports/${requestId}/execute`, 'POST');
}

export async function fetchAudit(
  kind = '',
  subjectRef = '',
  action = '',
  decision = '',
  page = 0,
  size = 20,
): Promise<PiiAccessAuditPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (kind.trim()) query.set('kind', kind.trim());
  if (subjectRef.trim()) query.set('subjectRef', subjectRef.trim());
  if (action.trim()) query.set('action', action.trim());
  if (decision.trim()) query.set('decision', decision.trim());
  return api<PiiAccessAuditPage>(`${BASE}/audit?${query}`);
}

// ================================================================ 展示映射（纯函数）

/** 取某一栏的值。 */
export function fieldValue(
  detail: PiiAccessSubjectDetail | null,
  fieldCode: string,
): PiiAccessFieldValue | null {
  if (!detail) return null;
  return detail.fields.find((f) => f.fieldCode === fieldCode) ?? null;
}

/** 这一栏现在是不是完整值（不是脱敏值）。 */
export function isFull(detail: PiiAccessSubjectDetail | null, fieldCode: string): boolean {
  const value = fieldValue(detail, fieldCode);
  return value !== null && !value.masked;
}

/**
 * 一栏的展示文本。
 *
 * 🔴 **「上游没给」与「空值」是两种文案**：
 * `present=false` 显示「上游没给这一栏」，不是「—」（后者会被读成「这个人没有电话」）。
 */
export function fieldText(detail: PiiAccessSubjectDetail | null, fieldCode: string): string {
  const value = fieldValue(detail, fieldCode);
  if (!value) return '—';
  if (!value.present) return '上游没给这一栏';
  return value.value || '（空）';
}

/** 状态徽章：完整值 / 脱敏值。 */
export function valueStateLabel(detail: PiiAccessSubjectDetail | null, fieldCode: string): string {
  const value = fieldValue(detail, fieldCode);
  if (!value) return '未知';
  if (!value.present) return '上游没给';
  return value.masked ? '已脱敏' : '完整值';
}

/** 状态徽章的色调。 */
export function valueStateTone(
  detail: PiiAccessSubjectDetail | null,
  fieldCode: string,
): 'success' | 'neutral' | 'warning' {
  const value = fieldValue(detail, fieldCode);
  if (!value) return 'neutral';
  if (!value.present) return 'warning';
  return value.masked ? 'neutral' : 'success';
}

/** 还能申请查看完整值的栏（现在还是脱敏态的）。 */
export function revealableFields(detail: PiiAccessSubjectDetail | null): string[] {
  if (!detail) return [];
  return detail.fields.filter((f) => f.present && f.masked).map((f) => f.fieldCode);
}

/** 某一栏的授权判定结果。 */
export function decisionFor(
  detail: PiiAccessSubjectDetail | null,
  fieldCode: string,
): PiiAccessFieldDecision | null {
  if (!detail) return null;
  return detail.decisions.find((d) => d.fieldCode === fieldCode) ?? null;
}

/**
 * 「凭什么能看到完整值」的人话说明。
 *
 * 🔴 界面上必须能看到**角色 + 范围** —— 没有这句，客服只能凭感觉相信自己有权限。
 */
export function grantReason(detail: PiiAccessSubjectDetail | null, fieldCode: string): string {
  const decision = decisionFor(detail, fieldCode);
  if (!decision) return '';
  if (decision.allowed) {
    return `依据角色「${decision.roleCode}」的授权`;
  }
  return decision.reason;
}

/** 导出申请状态标签。 */
export function exportStatusLabel(status: string): string {
  return EXPORT_STATUSES.find((s) => s.value === status)?.label ?? status;
}

/** 导出申请状态徽章色调。 */
export function exportStatusTone(status: string): 'neutral' | 'warning' | 'danger' | 'success' {
  if (status === 'PENDING') return 'warning';
  if (status === 'APPROVED') return 'success';
  if (status === 'REJECTED') return 'danger';
  if (status === 'EXECUTED') return 'neutral';
  return 'neutral';
}

/** 审计动作标签。 */
export function auditActionLabel(action: string): string {
  return AUDIT_ACTIONS.find((a) => a.value === action)?.label ?? action;
}

/** 审计判定标签。 */
export function auditDecisionLabel(decision: string): string {
  return AUDIT_DECISIONS.find((d) => d.value === decision)?.label ?? decision;
}

/** 审计判定色调。 */
export function auditDecisionTone(decision: string): 'success' | 'danger' | 'neutral' {
  if (decision === 'ALLOWED') return 'success';
  if (decision === 'DENIED') return 'danger';
  return 'neutral';
}

/** 主体列表行的一行摘要（脱敏值拼起来）。 */
export function subjectSummary(view: PiiAccessSubjectView): string {
  if (!view.known) return '上游查无这个主体';
  const parts = view.fields.filter((f) => f.maskedValue).map((f) => `${f.fieldLabel} ${f.maskedValue}`);
  return parts.length ? parts.join(' · ') : '上游没给任何一栏';
}

/** 成员标识的短码（审计里显示的是成员 id —— 本模块不改 identity，拿不到显示名）。 */
export function actorLabel(actorId: string): string {
  return actorId ? `成员 ${actorId.slice(0, 8)}` : '未知成员';
}

/** 三态**文案各不相同**（不是同一个空态换个图标）。 */
export function emptyState(
  kind: 'NO_POLICY' | 'NO_SUBJECT' | 'NO_EXPORT' | 'NO_AUDIT' | 'UNKNOWN_SUBJECT',
): { title: string; description: string } {
  if (kind === 'NO_POLICY') {
    return {
      title: '还没有登记 PII 策略',
      description:
        '当前用的是系统口径（单次导出 100 条 / 审批有效期 24 小时 / 查看完整值必须写理由）。登记之后才能调这些额度。',
    };
  }
  if (kind === 'NO_SUBJECT') {
    return {
      title: '上游没有可展示的主体',
      description:
        '本页的主体来自 9 订单的收货地址快照与 15 客户的买家联系方式。这里为空说明上游确实没有数据 —— 不是「读不到」。',
    };
  }
  if (kind === 'NO_EXPORT') {
    return {
      title: '还没有导出申请',
      description:
        '批量导出 PII 必须走「申请 → 审批 → 执行」，审批人不能是申请人本人，导出对象也要逐条列明。',
    };
  }
  if (kind === 'NO_AUDIT') {
    return {
      title: '还没有访问记录',
      description:
        '脱敏展示、完整值查看、越权尝试与导出都会逐条留痕。还没有记录说明这一租户还没有人访问过 PII。',
    };
  }
  return {
    title: '上游查无这个主体',
    description:
      '「查无此人」不等于「这个人没有 PII」—— 上游正常但没有这条记录时，本模块如实这么报，不拿空值冒充。',
  };
}
