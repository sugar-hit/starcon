import { api } from '../../lib/api';

/**
 * 16.4.2.1 品牌授权 前端模型。
 *
 * 蓝图：「管理品牌授权：授权方、授权范围（店铺/国家/类目/期限）、授权文件、被授权主体，
 * 校验刊登时的品牌使用是否在授权范围内。输入：授权录入；文件上传；有效期。
 * 输出：授权档案 + 校验结果。关系：上游 = 6.1.2.3 供应商资质；下游 = 3.3.2 品牌状态、4.2.3.2 预检。
 * **授权过期是「平时没事、上新时出事」的典型**，需到期提醒与刊登阻断。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **「判不了」显示成「判不了」，不显示成「在授权范围内」**。后端给 `UNKNOWN` 就是 `UNKNOWN`，
 *    前端**不重算**。发布闸门只在 `IN_SCOPE` 时放行 —— 界面上的绿色勾不是放行依据，
 *    服务端的 `publish-gate` 才是。
 * 2. **每条不覆盖都带原因**。`reasons` 是后端逐条给的（原因码 + 人话 + 关联授权），
 *    前端原样显示，**不自己编一句「授权无效」**。
 * 3. **到期提醒与授权档案分开显示**。临期 / 已过期是「平时没事、上新时出事」的那一半，
 *    必须一眼能看到，而不是混在档案列表里靠人眼扫。
 * 4. **候选读不到 ≠ 没有候选**。上游（03 品牌 / 6.1.2.3 供应商资质）挂掉时，
 *    后端返回的 `note` 说明读不到，前端原样显示，**不显示成「暂无候选」**。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有 channel 分支）。 */
export const BASE = 'channel/brand-authorizations';

/** 授权范围的三个维度。 */
export const SCOPE_KINDS = [
  { value: 'SHOP', label: '店铺', hint: '这条授权只覆盖这些店铺' },
  { value: 'COUNTRY', label: '国家', hint: '这条授权只覆盖这些国家（两位国家码）' },
  { value: 'CATEGORY', label: '类目', hint: '这条授权只覆盖这些类目' },
] as const;

/** 被授权主体类型。 */
export const LICENSEE_TYPES = [
  { value: 'SUPPLIER', label: '供应商' },
  { value: 'DISTRIBUTOR', label: '经销商' },
  { value: 'AGENT', label: '代理商' },
  { value: 'SELF', label: '品牌自有主体' },
] as const;

/** 到期状态筛选的可选项。 */
export const EXPIRY_STATES = [
  { value: '', label: '全部' },
  { value: 'ACTIVE', label: '生效中' },
  { value: 'EXPIRING', label: '即将到期' },
  { value: 'EXPIRED', label: '已过期' },
  { value: 'NOT_YET_EFFECTIVE', label: '尚未生效' },
  { value: 'DISABLED', label: '已停用' },
] as const;

export type ScopeKind = (typeof SCOPE_KINDS)[number]['value'];

// ==================================================================
// 类型
// ==================================================================

/** 一个未交付 / 已交付的下游。 */
export interface BrandAuthorizationCapabilityGap {
  moduleId: string;
  moduleName: string;
  ownerModule: string;
  available: boolean;
  impact: string;
}

/** 能力声明。 */
export interface BrandAuthorizationCapabilities {
  gaps: BrandAuthorizationCapabilityGap[];
  scopeKinds: string[];
  conclusions: string[];
  severities: string[];
  expiryStates: string[];
  verdictSemantics: string;
  unknownSemantics: string;
  blockingSemantics: string;
  expirySemantics: string;
  boundaryNote: string;
  upstreamNote: string;
  downstreamNote: string;
}

/** 授权档案的一行。 */
export interface BrandAuthorizationRecord {
  authorizationId: string;
  brandId: string;
  brandName: string;
  grantor: string;
  licenseeName: string;
  licenseeType: string;
  qualificationId: string | null;
  effectiveFrom: string;
  effectiveTo: string;
  enabled: boolean;
  ruleVersion: number;
  expiryState: string;
  expiryStateLabel: string;
  daysToExpiry: number;
  scopeCount: number;
  documentCount: number;
  note: string;
  createdAt: string;
  updatedAt: string;
}

/** 一行授权范围。 */
export interface BrandAuthorizationScopeLine {
  scopeKind: string;
  scopeValue: string;
  scopeLabel: string;
}

/** 一份授权文件（**只有引用与元数据**，文件本体不在这里）。 */
export interface BrandAuthorizationDocumentRow {
  documentId: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  storageKey: string;
  uploadedAt: string;
  note: string;
}

/** 授权档案详情。 */
export interface BrandAuthorizationDetail {
  record: BrandAuthorizationRecord;
  scopes: BrandAuthorizationScopeLine[];
  documents: BrandAuthorizationDocumentRow[];
}

/** 授权档案列表（真分页）。 */
export interface BrandAuthorizationPage {
  items: BrandAuthorizationRecord[];
  total: number;
  page: number;
  size: number;
}

/** 一行范围（录入用）。 */
export interface BrandAuthorizationScopeInput {
  scopeKind: string;
  scopeValue: string;
  scopeLabel: string;
}

/** 一份授权文件（录入用）。 */
export interface BrandAuthorizationDocumentInput {
  fileName: string;
  contentType: string;
  sizeBytes: number;
  storageKey: string;
  uploadedAt: string | null;
  note: string;
}

/** 授权录入 / 修改的入参。 */
export interface BrandAuthorizationRegistrationInput {
  brandId: string;
  brandName: string;
  grantor: string;
  licenseeName: string;
  licenseeType: string;
  qualificationId: string | null;
  effectiveFrom: string;
  effectiveTo: string;
  enabled: boolean;
  note: string;
  scopes: BrandAuthorizationScopeInput[];
  documents: BrandAuthorizationDocumentInput[];
}

/** 校验的入参：一次刊登上下文里的品牌使用。 */
export interface BrandAuthorizationCheckRequest {
  brandId: string;
  shopId: string | null;
  countryCode: string | null;
  categoryId: string | null;
}

/** 一条原因。 */
export interface BrandAuthorizationReason {
  code: string;
  label: string;
  message: string;
  severity: string;
  authorizationId: string | null;
  authorizationLabel: string;
}

/** 一次校验的结论。 */
export interface BrandAuthorizationCheckResult {
  checkId: string;
  brandId: string;
  brandName: string;
  shopId: string | null;
  countryCode: string | null;
  categoryId: string | null;
  evaluatedAt: string;
  ruleVersion: number;
  inScope: boolean;
  blocking: boolean;
  severity: string;
  conclusion: string;
  matchedAuthorizationId: string | null;
  reasons: BrandAuthorizationReason[];
  snapshot: Record<string, unknown>;
}

/** 刊登闸门结论（服务端算出来的）。 */
export interface BrandAuthorizationPublishGate {
  allowed: boolean;
  blocking: boolean;
  severity: string;
  conclusion: string;
  reasons: BrandAuthorizationReason[];
  evaluatedAt: string;
  ruleVersion: number;
  checkId: string;
  brandId: string;
}

/** 校验历史里的一行。 */
export interface BrandAuthorizationCheckSummary {
  checkId: string;
  brandId: string;
  brandName: string;
  shopId: string | null;
  countryCode: string | null;
  categoryId: string | null;
  evaluatedAt: string;
  ruleVersion: number;
  inScope: boolean;
  blocking: boolean;
  severity: string;
  severityLabel: string;
  conclusion: string;
  conclusionLabel: string;
  matchedAuthorizationId: string | null;
  reasons: BrandAuthorizationReason[];
  reasonCount: number;
}

/** 校验历史（真分页）。 */
export interface BrandAuthorizationCheckPage {
  items: BrandAuthorizationCheckSummary[];
  total: number;
  page: number;
  size: number;
}

/** 到期提醒清单里的一行。 */
export interface BrandAuthorizationExpiryRow {
  authorizationId: string;
  brandId: string;
  brandName: string;
  grantor: string;
  licenseeName: string;
  effectiveTo: string;
  expiryState: string;
  expiryStateLabel: string;
  daysToExpiry: number;
  enabled: boolean;
  scopeSummary: string;
  note: string;
}

/** 到期提醒清单（真分页）。 */
export interface BrandAuthorizationExpiryPage {
  items: BrandAuthorizationExpiryRow[];
  total: number;
  page: number;
  size: number;
  expiringCount: number;
  expiredCount: number;
  note: string;
}

/** 一个候选（品牌 / 被授权主体）。 */
export interface BrandAuthorizationCandidate {
  id: string;
  code: string;
  name: string;
  status: string;
  note: string;
}

/** 候选清单。 */
export interface BrandAuthorizationCandidatePage {
  items: BrandAuthorizationCandidate[];
  total: number;
  note: string;
}

/** 一个可选店铺（连同它覆盖的国家）。 */
export interface BrandAuthorizationShopOption {
  shopId: string;
  shopCode: string;
  shopName: string;
  platformCode: string;
  countryCodes: string[];
}

// ==================================================================
// 接口调用
// ==================================================================

export async function fetchCapabilities(): Promise<BrandAuthorizationCapabilities> {
  return api<BrandAuthorizationCapabilities>(`${BASE}/capabilities`);
}

export async function fetchAuthorizations(
  page = 0,
  size = 20,
  filters: { brandId?: string; keyword?: string; state?: string } = {},
): Promise<BrandAuthorizationPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.brandId) query.set('brandId', filters.brandId);
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.state) query.set('state', filters.state);
  return api<BrandAuthorizationPage>(`${BASE}/authorizations?${query.toString()}`);
}

export async function fetchAuthorization(id: string): Promise<BrandAuthorizationDetail> {
  return api<BrandAuthorizationDetail>(`${BASE}/authorizations/${id}`);
}

export async function createAuthorization(
  input: BrandAuthorizationRegistrationInput,
): Promise<BrandAuthorizationDetail> {
  return api<BrandAuthorizationDetail>(`${BASE}/authorizations`, 'POST', input);
}

export async function updateAuthorization(
  id: string,
  input: BrandAuthorizationRegistrationInput,
): Promise<BrandAuthorizationDetail> {
  return api<BrandAuthorizationDetail>(`${BASE}/authorizations/${id}`, 'PUT', input);
}

/** 停用 / 启用一条授权（撤销走这里，不做物理删除）。 */
export async function setAuthorizationEnabled(
  id: string,
  enabled: boolean,
): Promise<BrandAuthorizationDetail> {
  return api<BrandAuthorizationDetail>(
    `${BASE}/authorizations/${id}/enabled?enabled=${enabled ? 'true' : 'false'}`,
    'PUT',
  );
}

export async function fetchExpiryReminders(
  page = 0,
  size = 20,
  filters: { state?: string; withinDays?: number } = {},
): Promise<BrandAuthorizationExpiryPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.state) query.set('state', filters.state);
  if (filters.withinDays !== undefined) query.set('withinDays', String(filters.withinDays));
  return api<BrandAuthorizationExpiryPage>(`${BASE}/expiry-reminders?${query.toString()}`);
}

/** 跑一次范围校验（后端会落一条不可改写的判定凭据）。 */
export async function runCheck(
  request: BrandAuthorizationCheckRequest,
): Promise<BrandAuthorizationCheckResult> {
  return api<BrandAuthorizationCheckResult>(`${BASE}/checks`, 'POST', request);
}

/** 🔴 刊登闸门：服务端算出来的阻断结论。 */
export async function runPublishGate(
  request: BrandAuthorizationCheckRequest,
): Promise<BrandAuthorizationPublishGate> {
  return api<BrandAuthorizationPublishGate>(`${BASE}/publish-gate`, 'POST', request);
}

export async function fetchChecks(
  page = 0,
  size = 20,
  brandId?: string,
): Promise<BrandAuthorizationCheckPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (brandId) query.set('brandId', brandId);
  return api<BrandAuthorizationCheckPage>(`${BASE}/checks?${query.toString()}`);
}

export async function fetchBrandCandidates(keyword?: string): Promise<BrandAuthorizationCandidatePage> {
  const query = keyword ? `?keyword=${encodeURIComponent(keyword)}` : '';
  return api<BrandAuthorizationCandidatePage>(`${BASE}/candidates/brands${query}`);
}

export async function fetchSupplierCandidates(
  keyword?: string,
): Promise<BrandAuthorizationCandidatePage> {
  const query = keyword ? `?keyword=${encodeURIComponent(keyword)}` : '';
  return api<BrandAuthorizationCandidatePage>(`${BASE}/candidates/suppliers${query}`);
}

export async function fetchQualificationCandidates(
  brandId: string,
): Promise<BrandAuthorizationCandidatePage> {
  return api<BrandAuthorizationCandidatePage>(
    `${BASE}/candidates/qualifications?brandId=${encodeURIComponent(brandId)}`,
  );
}

export async function fetchShops(page = 0, size = 50): Promise<BrandAuthorizationShopOption[]> {
  return api<BrandAuthorizationShopOption[]>(`${BASE}/shops?page=${page}&size=${size}`);
}

// ==================================================================
// 展示口径（**不重算后端的结论**）
// ==================================================================

/** 结论的人话。 */
export function conclusionLabel(conclusion: string): string {
  switch (conclusion) {
    case 'IN_SCOPE':
      return '在授权范围内';
    case 'OUT_OF_SCOPE':
      return '不在授权范围内';
    case 'EXPIRED':
      return '授权已过期';
    case 'NO_AUTHORIZATION':
      return '没有授权档案';
    case 'UNKNOWN':
      return '判不了（上下文不全）';
    default:
      return conclusion;
  }
}

/** 结论的徽章色调。 */
export function conclusionTone(conclusion: string): 'success' | 'danger' | 'warning' | 'neutral' {
  switch (conclusion) {
    case 'IN_SCOPE':
      return 'success';
    case 'OUT_OF_SCOPE':
    case 'EXPIRED':
    case 'NO_AUTHORIZATION':
      return 'danger';
    case 'UNKNOWN':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 分级的人话。 */
export function severityLabel(severity: string): string {
  switch (severity) {
    case 'BLOCKING':
      return '阻断刊登';
    case 'REVIEW':
      return '需人工复核';
    case 'NONE':
      return '通过';
    default:
      return severity;
  }
}

/** 分级的徽章色调。 */
export function severityTone(severity: string): 'danger' | 'warning' | 'success' | 'neutral' {
  switch (severity) {
    case 'BLOCKING':
      return 'danger';
    case 'REVIEW':
      return 'warning';
    case 'NONE':
      return 'success';
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
    case 'DISABLED':
      return '已停用';
    default:
      return state;
  }
}

/** 到期状态的徽章色调。 */
export function expiryStateTone(state: string): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (state) {
    case 'ACTIVE':
      return 'success';
    case 'EXPIRING':
      return 'warning';
    case 'EXPIRED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 授权范围维度的人话。 */
export function scopeKindLabel(kind: string): string {
  switch (kind) {
    case 'SHOP':
      return '店铺';
    case 'COUNTRY':
      return '国家';
    case 'CATEGORY':
      return '类目';
    default:
      return kind;
  }
}

/** 被授权主体类型的人话。 */
export function licenseeTypeLabel(type: string): string {
  switch (type) {
    case 'SUPPLIER':
      return '供应商';
    case 'DISTRIBUTOR':
      return '经销商';
    case 'AGENT':
      return '代理商';
    case 'SELF':
      return '品牌自有主体';
    default:
      return type;
  }
}

/** 剩余天数的展示串（后端算好的是天数，这里只做排版）。 */
export function daysToExpiryText(days: number): string {
  if (days < 0) return `已过期 ${Math.abs(days)} 天`;
  if (days === 0) return '今天到期';
  return `还剩 ${days} 天`;
}

/**
 * 🔴 闸门结论的一句话摘要。
 *
 * <p>「判不了」与「不合格」必须分开说：前者是**上下文不全**，后者是**明确越权**，
 * 但两者都**不放行** —— 只有 `IN_SCOPE` 才放行。
 */
export function gateSummary(gate: BrandAuthorizationPublishGate | null): string {
  if (!gate) return '';
  if (gate.allowed) return '在授权范围内，允许刊登。';
  if (gate.conclusion === 'UNKNOWN') {
    return '判不了：刊登上下文不全，无法证明在授权范围内，因此不放行。';
  }
  return '不允许刊登：' + conclusionLabel(gate.conclusion) + '。';
}

/**
 * 🔴 空态文案：**三态不同**。
 *
 * @param kind 'NO_AUTHORIZATION' 还没有授权档案 / 'NO_EXPIRING' 没有临期或过期 /
 *             'NO_CHECK' 还没有校验记录
 */
export function emptyState(kind: 'NO_AUTHORIZATION' | 'NO_EXPIRING' | 'NO_CHECK'): {
  title: string;
  description: string;
} {
  switch (kind) {
    case 'NO_AUTHORIZATION':
      return {
        title: '还没有品牌授权档案',
        description:
          '授权档案要回答「哪一段范围、这一段时间、谁可以用这个品牌」。先录入一条（授权方 / 授权范围 / 被授权主体 / 期限）。',
      };
    case 'NO_EXPIRING':
      return {
        title: '没有临期或已过期的授权',
        description:
          '当前筛选条件下没有需要处理的授权。注意：授权过期是「平时没事、上新时出事」—— 临期提醒只覆盖已登记的授权。',
      };
    case 'NO_CHECK':
      return {
        title: '还没有校验记录',
        description:
          '选一个品牌与刊登上下文，跑一次范围校验，看看这次刊登的品牌使用是否落在授权范围内。',
      };
    default:
      return { title: '', description: '' };
  }
}

/**
 * 🔴 候选清单的可读性判断：上游读不到时**不能**显示成「暂无候选」。
 *
 * <p>后端在读不到上游时返回的 `note` 会写清原因；这里只判断「是不是读不到」。
 */
export function candidatesUnavailable(page: BrandAuthorizationCandidatePage | null): boolean {
  if (!page) return true;
  if (page.items.length > 0) return false;
  return page.note.trim().length > 0 && !page.note.includes('只读');
}

/** 授权范围的一行展示串（「店铺：X、Y」）。 */
export function scopeSummary(scopes: BrandAuthorizationScopeLine[]): string {
  if (!scopes || scopes.length === 0) return '全部（未限定范围）';
  const grouped = new Map<string, string[]>();
  for (const scope of scopes) {
    const label = scope.scopeLabel || scope.scopeValue;
    grouped.set(scope.scopeKind, [...(grouped.get(scope.scopeKind) ?? []), label]);
  }
  return [...grouped.entries()]
    .map(([kind, values]) => `${scopeKindLabel(kind)}：${values.join('、')}`)
    .join(' / ');
}
