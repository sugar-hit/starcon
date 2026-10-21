import { api } from '../../lib/api';

/**
 * 16.4.2.2 授权凭证 前端模型。
 *
 * 蓝图：「集中存储与核验授权凭证文件：真伪验证记录、文件版本、签署方信息，支持一键核验。
 * 输入：凭证上传；核验操作。输出：凭证档案 + 核验记录。
 * 关系：上游 = 16.4.2.1；下游 = 16.4.1.2。**凭证需保留原始文件与元数据**，争议时是唯一证据。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「没核验过」显示成「没核验过」，不显示成「核验不通过」**。后端给 `UNVERIFIED` 就是
 *    `UNVERIFIED`，前端**不重算**。把「还没查」显示成「假的」是误伤，把「假的」显示成
 *    「还没查」是放过 —— 两者都是把证据说错。
 * 2. **核验结论原样显示**（`conclusion` / `severity` / `reasons`），前端不重算、不编原因。
 *    不通过一定有原因（后端 DB 层 CHECK 兜底），前端把每条原因原样摆出来。
 * 3. **「通过但有提醒」不是「不通过」**。`SIGNATORY_UNVERIFIED` 是 WARN 级提醒
 *    （签署人姓名职务都登记了、只是还没人工核过身份），页面显示成提醒而不是红字。
 * 4. **文件本体不在列表里**。列表显示的是**元数据 + sha256 摘要**；
 *    摘要才是真伪核验的锚点，页面必须把它显示出来（争议时这就是证据）。
 * 5. **上游读不到 ≠ 没有候选**。16.4.2.1 的授权候选读不到时，后端返回的 `note` 说明读不到，
 *    前端原样显示，**不显示成「暂无候选」**。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有 channel 分支）。 */
export const BASE = 'channel/brand-credentials';

/** 凭证类型。 */
export const CREDENTIAL_TYPES = [
  { value: 'BRAND_LICENSE', label: '品牌授权书' },
  { value: 'DISTRIBUTOR_AGREEMENT', label: '经销协议' },
  { value: 'TRADEMARK_CERTIFICATE', label: '商标注册证' },
  { value: 'CERTIFICATE_OF_ORIGIN', label: '原产地证' },
  { value: 'AUTHORIZATION_LETTER', label: '授权函' },
  { value: 'OTHER', label: '其它凭证' },
] as const;

/** 状态筛选。 */
export const STATUS_FILTERS = [
  { value: '', label: '全部状态' },
  { value: 'ACTIVE', label: '有效' },
  { value: 'REVOKED', label: '已撤销' },
] as const;

/** 到期状态筛选。 */
export const EXPIRY_FILTERS = [
  { value: '', label: '全部' },
  { value: 'ACTIVE', label: '生效中' },
  { value: 'EXPIRING', label: '即将到期' },
  { value: 'EXPIRED', label: '已过期' },
  { value: 'NOT_YET_EFFECTIVE', label: '尚未生效' },
  { value: 'REVOKED', label: '已撤销' },
] as const;

/** 🔴 最近核验状态筛选 —— 「没核验过」与「核验过但不通过」是两个状态，必须能分开筛。 */
export const VERIFICATION_FILTERS = [
  { value: '', label: '全部核验状态' },
  { value: 'UNVERIFIED', label: '从未核验' },
  { value: 'VERIFIED', label: '核验通过' },
  { value: 'FAILED', label: '核验未通过' },
] as const;

export type CredentialType = (typeof CREDENTIAL_TYPES)[number]['value'];

// ==================================================================
// 类型
// ==================================================================

/** 一个未交付 / 已交付的下游。 */
export interface BrandCredentialCapabilityGap {
  moduleId: string;
  moduleName: string;
  ownerModule: string;
  available: boolean;
  impact: string;
}

/** 能力声明。 */
export interface BrandCredentialCapabilities {
  gaps: BrandCredentialCapabilityGap[];
  credentialTypes: string[];
  conclusions: string[];
  severities: string[];
  verificationStates: string[];
  expiryStates: string[];
  verificationSemantics: string;
  digestSemantics: string;
  appendOnlySemantics: string;
  boundaryNote: string;
  upstreamNote: string;
  downstreamNote: string;
}

/** 凭证档案的一行。 */
export interface BrandCredentialRecord {
  credentialId: string;
  authorizationId: string | null;
  grantor: string;
  credentialNo: string;
  credentialType: string;
  credentialTypeLabel: string;
  subject: string;
  brandId: string | null;
  brandName: string;
  validFrom: string;
  validUntil: string;
  issuedOn: string | null;
  status: string;
  statusLabel: string;
  revokedAt: string | null;
  revokedReason: string;
  expiryState: string;
  expiryStateLabel: string;
  daysToExpiry: number;
  currentVersion: number;
  documentCount: number;
  signatoryCount: number;
  verificationState: string;
  verificationStateLabel: string;
  lastVerifiedAt: string | null;
  lastConclusion: string | null;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

/** 一个签署方。 */
export interface BrandCredentialSignatoryRow {
  signatoryId: string;
  credentialId: string;
  signatoryName: string;
  signatoryTitle: string;
  signatoryOrg: string;
  contact: string;
  identityVerified: boolean;
  note: string;
  createdAt: string;
}

/** 一个凭证文件版本（**只追加**；文件本体在受管存储里）。 */
export interface BrandCredentialDocumentRow {
  documentId: string;
  credentialId: string;
  versionNo: number;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  storageKey: string;
  sha256: string;
  uploadedBy: string | null;
  uploadedAt: string;
  note: string;
  createdAt: string;
}

/** 一条核验原因。 */
export interface BrandCredentialVerificationReason {
  code: string;
  label: string;
  message: string;
  severity: string;
}

/** 一条核验记录（追加式凭据）。 */
export interface BrandCredentialVerificationRow {
  verificationId: string;
  credentialId: string;
  credentialNo: string;
  grantor: string;
  documentId: string | null;
  documentVersionNo: number;
  evaluatedAt: string;
  ruleVersion: number;
  conclusion: string;
  conclusionLabel: string;
  severity: string;
  severityLabel: string;
  passed: boolean;
  reasons: BrandCredentialVerificationReason[];
  reasonCount: number;
  checkedBy: string | null;
  createdAt: string;
}

/** 凭证档案详情。 */
export interface BrandCredentialDetail {
  record: BrandCredentialRecord;
  signatories: BrandCredentialSignatoryRow[];
  documents: BrandCredentialDocumentRow[];
  verifications: BrandCredentialVerificationRow[];
}

/** 凭证档案列表（真分页）。 */
export interface BrandCredentialPage {
  items: BrandCredentialRecord[];
  total: number;
  page: number;
  size: number;
}

/** 一次核验的结论。 */
export interface BrandCredentialVerificationResult {
  verificationId: string;
  credential: BrandCredentialRecord;
  documentId: string | null;
  documentVersionNo: number;
  evaluatedAt: string;
  ruleVersion: number;
  conclusion: string;
  conclusionLabel: string;
  severity: string;
  severityLabel: string;
  passed: boolean;
  reasons: BrandCredentialVerificationReason[];
  snapshot: Record<string, unknown>;
  checkedBy: string | null;
}

/** 批量核验的结果。 */
export interface BrandCredentialVerificationBatchResult {
  results: BrandCredentialVerificationResult[];
  verifiedCount: number;
  failedCount: number;
}

/** 核验记录历史（真分页）。 */
export interface BrandCredentialVerificationPage {
  items: BrandCredentialVerificationRow[];
  total: number;
  page: number;
  size: number;
}

/** 凭证登记 / 修改的入参。 */
export interface BrandCredentialRegistrationInput {
  authorizationId: string | null;
  grantor: string;
  credentialNo: string;
  credentialType: string;
  subject: string;
  brandId: string | null;
  brandName: string;
  validFrom: string;
  validUntil: string;
  issuedOn: string | null;
  note: string;
}

/** 登记一个签署方。 */
export interface BrandCredentialSignatoryInput {
  signatoryName: string;
  signatoryTitle: string;
  signatoryOrg: string;
  contact: string;
  identityVerified: boolean;
  note: string;
}

/** 上传凭证文件（JSON 形状：文件本体走 base64）。 */
export interface BrandCredentialDocumentInput {
  fileName: string;
  contentType: string;
  contentBase64: string;
  note: string;
}

/** 批量核验的入参。 */
export interface BrandCredentialVerificationBatchInput {
  credentialIds: string[];
}

/** 撤销凭证的入参。 */
export interface BrandCredentialRevokeInput {
  reason: string;
}

/** 上游 16.4.2.1 的授权档案候选（同库只读）。 */
export interface BrandCredentialAuthorizationOption {
  authorizationId: string;
  brandName: string;
  grantor: string;
  licenseeName: string;
  effectiveFrom: string;
  effectiveTo: string;
}

/** 上游候选清单。 */
export interface BrandCredentialAuthorizationOptionPage {
  items: BrandCredentialAuthorizationOption[];
  total: number;
  note: string;
}

/** 概览。 */
export interface BrandCredentialOverview {
  credentialCount: number;
  activeCount: number;
  expiringCount: number;
  expiredCount: number;
  revokedCount: number;
  verifiedCount: number;
  failedCount: number;
  unverifiedCount: number;
  documentCount: number;
}

// ==================================================================
// 接口调用
// ==================================================================

export async function fetchCapabilities(): Promise<BrandCredentialCapabilities> {
  return api<BrandCredentialCapabilities>(`${BASE}/capabilities`);
}

export async function fetchOverview(): Promise<BrandCredentialOverview> {
  return api<BrandCredentialOverview>(`${BASE}/overview`);
}

export async function fetchCredentials(
  page = 0,
  size = 20,
  filters: {
    // 🔴 `| undefined` 是必需的：仓库开了 `exactOptionalPropertyTypes`，
    //    不写它的话「传 undefined」与「不传」在类型上不是一回事。
    q?: string | undefined;
    status?: string | undefined;
    expiryState?: string | undefined;
    verificationState?: string | undefined;
    credentialType?: string | undefined;
    authorizationId?: string | undefined;
  } = {},
): Promise<BrandCredentialPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.q) query.set('q', filters.q);
  if (filters.status) query.set('status', filters.status);
  if (filters.expiryState) query.set('expiryState', filters.expiryState);
  if (filters.verificationState) query.set('verificationState', filters.verificationState);
  if (filters.credentialType) query.set('credentialType', filters.credentialType);
  if (filters.authorizationId) query.set('authorizationId', filters.authorizationId);
  return api<BrandCredentialPage>(`${BASE}/credentials?${query.toString()}`);
}

export async function fetchCredential(id: string): Promise<BrandCredentialDetail> {
  return api<BrandCredentialDetail>(`${BASE}/credentials/${id}`);
}

export async function createCredential(
  input: BrandCredentialRegistrationInput,
): Promise<BrandCredentialDetail> {
  return api<BrandCredentialDetail>(`${BASE}/credentials`, 'POST', input);
}

export async function updateCredential(
  id: string,
  input: BrandCredentialRegistrationInput,
): Promise<BrandCredentialDetail> {
  return api<BrandCredentialDetail>(`${BASE}/credentials/${id}`, 'PUT', input);
}

/** 撤销凭证（**必须写明理由**；撤销是终态，没有「恢复」）。 */
export async function revokeCredential(id: string, reason: string): Promise<BrandCredentialDetail> {
  return api<BrandCredentialDetail>(`${BASE}/credentials/${id}/revoke`, 'POST', { reason });
}

export async function addSignatory(
  id: string,
  input: BrandCredentialSignatoryInput,
): Promise<BrandCredentialDetail> {
  return api<BrandCredentialDetail>(`${BASE}/credentials/${id}/signatories`, 'POST', input);
}

export async function removeSignatory(
  id: string,
  signatoryId: string,
): Promise<BrandCredentialDetail> {
  return api<BrandCredentialDetail>(
    `${BASE}/credentials/${id}/signatories/${signatoryId}`,
    'DELETE',
  );
}

/** 上传凭证文件（**新版本**，文件行只追加）。 */
export async function uploadCredentialDocument(
  id: string,
  input: BrandCredentialDocumentInput,
): Promise<BrandCredentialDocumentRow> {
  return api<BrandCredentialDocumentRow>(`${BASE}/credentials/${id}/documents`, 'POST', input);
}

/** 🔴 一键核验：服务端把文件读回来重算 sha256 并落一条不可改写的核验记录。 */
export async function verifyCredential(id: string): Promise<BrandCredentialVerificationResult> {
  return api<BrandCredentialVerificationResult>(`${BASE}/credentials/${id}/verify`, 'POST');
}

/** 批量核验（一次最多 50 个）。 */
export async function verifyCredentialBatch(
  credentialIds: string[],
): Promise<BrandCredentialVerificationBatchResult> {
  return api<BrandCredentialVerificationBatchResult>(`${BASE}/credentials/verify-batch`, 'POST', {
    credentialIds,
  });
}

export async function fetchVerifications(
  page = 0,
  size = 20,
  filters: { credentialId?: string | undefined; conclusion?: string | undefined } = {},
): Promise<BrandCredentialVerificationPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.credentialId) query.set('credentialId', filters.credentialId);
  if (filters.conclusion) query.set('conclusion', filters.conclusion);
  return api<BrandCredentialVerificationPage>(`${BASE}/verifications?${query.toString()}`);
}

/** 上游 16.4.2.1 的授权档案候选（同库只读）。 */
export async function fetchAuthorizationOptions(
  keyword?: string,
): Promise<BrandCredentialAuthorizationOptionPage> {
  const query = keyword ? `?keyword=${encodeURIComponent(keyword)}` : '';
  return api<BrandCredentialAuthorizationOptionPage>(`${BASE}/authorizations${query}`);
}

// ==================================================================
// 展示口径（**不重算后端的结论**）
// ==================================================================

/** 核验结论的人话（与后端同一份口径）。 */
export function conclusionLabel(conclusion: string): string {
  switch (conclusion) {
    case 'VERIFIED':
      return '核验通过';
    case 'DIGEST_MISMATCH':
      return '文件摘要对不上';
    case 'FILE_MISSING':
      return '文件不在受管存储';
    case 'EXPIRED':
      return '凭证已过期';
    case 'NOT_YET_EFFECTIVE':
      return '凭证尚未生效';
    case 'SIGNATORY_INCOMPLETE':
      return '没有登记签署方';
    case 'NO_DOCUMENT':
      return '凭证还没有文件';
    case 'REVOKED':
      return '凭证已被撤销';
    default:
      return conclusion;
  }
}

/** 结论的徽章色调。 */
export function conclusionTone(conclusion: string): 'success' | 'danger' | 'warning' | 'neutral' {
  switch (conclusion) {
    case 'VERIFIED':
      return 'success';
    case 'NOT_YET_EFFECTIVE':
      return 'warning';
    case 'DIGEST_MISMATCH':
    case 'FILE_MISSING':
    case 'EXPIRED':
    case 'SIGNATORY_INCOMPLETE':
    case 'NO_DOCUMENT':
    case 'REVOKED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 核验分级的人话。 */
export function severityLabel(severity: string): string {
  return severity === 'PASS' ? '通过' : '不通过';
}

/** 核验分级的徽章色调。 */
export function severityTone(severity: string): 'success' | 'danger' | 'neutral' {
  if (severity === 'PASS') return 'success';
  if (severity === 'FAIL') return 'danger';
  return 'neutral';
}

/**
 * 🔴 最近核验状态的人话。
 *
 * <p>**「从未核验」与「核验未通过」必须能区分开** ——
 * 把「还没查」显示成「假的」是误伤，把「假的」显示成「还没查」是放过。
 */
export function verificationStateLabel(state: string): string {
  switch (state) {
    case 'UNVERIFIED':
      return '从未核验';
    case 'VERIFIED':
      return '核验通过';
    case 'FAILED':
      return '核验未通过';
    default:
      return state;
  }
}

/** 最近核验状态的徽章色调。 */
export function verificationStateTone(state: string): 'success' | 'danger' | 'warning' | 'neutral' {
  switch (state) {
    case 'VERIFIED':
      return 'success';
    case 'FAILED':
      return 'danger';
    case 'UNVERIFIED':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 到期状态的人话。 */
export function expiryStateLabel(state: string): string {
  switch (state) {
    case 'ACTIVE':
      return '生效中';
    case 'EXPIRING':
      return '即将到期';
    case 'EXPIRED':
      return '已过期';
    case 'NOT_YET_EFFECTIVE':
      return '尚未生效';
    case 'REVOKED':
      return '已撤销';
    default:
      return state;
  }
}

/** 到期状态的徽章色调。 */
export function expiryStateTone(state: string): 'success' | 'danger' | 'warning' | 'neutral' {
  switch (state) {
    case 'ACTIVE':
      return 'success';
    case 'EXPIRING':
    case 'NOT_YET_EFFECTIVE':
      return 'warning';
    case 'EXPIRED':
    case 'REVOKED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 剩余天数的人话（负数 = 已过期几天）。 */
export function daysToExpiryText(days: number): string {
  if (days < 0) return `已过期 ${Math.abs(days)} 天`;
  if (days === 0) return '今天到期';
  return `还剩 ${days} 天`;
}

/** 原因的分级色调：FAIL 是红字，WARN 是提醒。 */
export function reasonTone(severity: string): 'danger' | 'warning' {
  return severity === 'WARN' ? 'warning' : 'danger';
}

/** 摘要预览（前后各 12 位，中间省略）—— 摘要太长时页面上只显示可辨认的一段。 */
export function digestPreview(sha256: string): string {
  if (!sha256) return '';
  if (sha256.length <= 28) return sha256;
  return `${sha256.slice(0, 12)}…${sha256.slice(-12)}`;
}

/** 文件大小的人话。 */
export function fileSizeText(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/** 凭证类型的人话（未知类型退回原值，不编一个）。 */
export function credentialTypeLabel(type: string): string {
  const found = CREDENTIAL_TYPES.find((item) => item.value === type);
  return found ? found.label : type;
}

/** 三种空态：标题与说明**各不相同**（同一个空态换个图标不算三态）。 */
export function emptyState(kind: 'NO_CREDENTIAL' | 'NO_VERIFICATION' | 'NO_DOCUMENT'): {
  title: string;
  description: string;
} {
  switch (kind) {
    case 'NO_CREDENTIAL':
      return {
        title: '还没有授权凭证',
        description:
          '授权凭证是「品牌能不能用」的证据链。登记第一份凭证，核验结论才能落成不可改写的凭据。',
      };
    case 'NO_VERIFICATION':
      return {
        title: '还没有核验记录',
        description:
          '核验会把凭证文件读回来重算 sha256 并与登记摘要比对。核验过才有「当时为什么这么判」的记录。',
      };
    case 'NO_DOCUMENT':
      return {
        title: '这份凭证还没有文件',
        description:
          '没有文件就没有摘要可核验。上传原件（PDF / 扫描件）之后，「一键核验」才有东西可验。',
      };
    default:
      return { title: '暂无数据', description: '' };
  }
}

/**
 * 上游候选读不到（≠ 没有候选）。
 *
 * <p>16.4.2.1 的授权档案读不到时，后端会把原因写在 `note` 里；
 * 界面必须显示「读不到」，不能显示成「暂无候选」。
 */
export function candidatesUnavailable(
  page: BrandCredentialAuthorizationOptionPage | null,
): boolean {
  if (!page) return true;
  if (page.items.length > 0) return false;
  const note = page.note ?? '';
  if (!note) return true;
  // 正常来源说明是「本模块从哪读」；读不到时 note 会带「不可达 / 失败 / 读不到」这类字样
  return /不可达|失败|读不到|超时|异常|错误/.test(note);
}

/**
 * 核验结论摘要（一句话）。
 *
 * <p>🔴 「还没核验过」返回空串 —— **不编一句**。
 */
export function verificationSummary(result: BrandCredentialVerificationResult | null): string {
  if (!result) return '';
  const warnCount = result.reasons.filter((reason) => reason.severity === 'WARN').length;
  if (result.passed) {
    return warnCount > 0
      ? `核验通过（另有 ${warnCount} 条提醒）`
      : '核验通过：文件摘要一致、凭证在有效期内、签署方已登记';
  }
  const reason = result.reasons.find((item) => item.severity !== 'WARN');
  return reason
    ? `核验未通过：${reason.label} —— ${reason.message}`
    : `核验未通过：${result.conclusionLabel}`;
}

/**
 * 批量核验摘要。
 *
 * <p>「几条通过 / 几条没通过」—— 没有通过的那几条也要点名，不能只报通过数。
 */
export function batchSummary(result: BrandCredentialVerificationBatchResult | null): string {
  if (!result) return '';
  const failed = result.results.filter((item) => !item.passed);
  const base = `${result.verifiedCount} 份核验通过，${result.failedCount} 份未通过`;
  if (failed.length === 0) return base;
  const names = failed.map((item) => item.credential.credentialNo || item.credential.credentialId);
  return `${base}：${names.join('、')}`;
}
