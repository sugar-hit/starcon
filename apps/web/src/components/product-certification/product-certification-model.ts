import { api } from '../inventory/api';

/**
 * 16.2.2.2 认证与资质 前端模型。
 *
 * 蓝图：「管理产品认证与资质（CE、FCC、RoHS、PSE、FDA、CCC 等）：认证编号、有效期、适用 SKU、
 * 对应类目要求，支持上传扫描件与到期提醒。输入：认证录入；文件上传；有效期。输出：认证档案 +
 * 到期提醒 + 覆盖情况统计（哪些 SKU 缺认证）。关系：上游 = 6.1.2.3 供应商资质；下游 = 16.2.2.1 预检、
 * 4 刊登。**认证缺失是 listing 被抑制与下架的高频原因**，且属于"上架时不管、出事才补"的典型滞后项。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **到期状态不重算**。后端按**数据库时钟**算好的 `expiryState` 与 `daysRemaining`
 *    原样显示。前端用自己浏览器的时钟去算「还剩几天」会因时区与时钟漂移而说错 ——
 *    而「这张证还剩 3 天」直接决定运营今天要不要去补。
 * 2. **「读不到」不显示成「没有」**。catalog 不可达时后端返回 503，
 *    页面显示**「读不到上游数据」**而不是一个空列表（空列表会被读成「这个租户没有 SKU」）。
 * 3. **覆盖统计是后端算的**。前端不自己数「哪些 SKU 缺认证」——那要跨服务读 SKU 与类目，
 *    前端算出来的数必然对不上。
 * 4. **认证类型是词表**。下拉框来自后端返回的**生效词表**（内置 ∪ 租户），
 *    前端不写死 CE / FCC / RoHS 之类 —— 租户加了新类型，页面要立刻能用。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有 channel 分支）。 */
export const BASE = 'channel/product-certifications';

// ==================================================================
// 形状
// ==================================================================

/** 能力声明。 */
export interface ProductCertificationCapabilities {
  statuses: string[];
  expiryStates: string[];
  documentKinds: string[];
  scopes: string[];
  defaultWarningDays: number;
  maxDocumentBytes: number;
  browserProxyBase64LimitBytes: number;
  expirySemantics: string;
  coverageSemantics: string;
  vocabularyNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

/** 生效词表里的一行（内置 ∪ 租户，租户行优先）。 */
export interface CertificationTypeRow {
  code: string;
  name: string;
  scope: string;
  description: string;
  enabled: boolean;
  /** 是否由本租户自定义（false = 内置）。 */
  tenantDefined: boolean;
}

/** 租户级策略。 */
export interface CertificationPolicyView {
  warningDays: number;
  note: string;
  updatedAt: string | null;
}

/** 认证档案（台账行）。 */
export interface CertificationSummary {
  id: string;
  code: string;
  certificationTypeCode: string;
  certificationTypeName: string;
  title: string;
  certNo: string;
  issuer: string;
  status: string;
  /** 到期状态（后端按数据库时钟算好，**前端不重算**）。 */
  expiryState: string;
  validFrom: string;
  validUntil: string;
  daysRemaining: number;
  skuCount: number;
  categoryCount: number;
  documentCount: number;
  createdAt: string;
  updatedAt: string;
}

/** 认证适用的一个 SKU。 */
export interface CertificationSkuBinding {
  id: string;
  skuId: string;
  skuCode: string;
  skuName: string;
  note: string;
  createdAt: string;
}

/** 认证对应的一个类目要求。 */
export interface CertificationCategoryBinding {
  id: string;
  categoryId: string;
  categoryCode: string;
  categoryName: string;
  requirementNote: string;
  createdAt: string;
}

/** 一份扫描件的元数据。 */
export interface CertificationDocumentRow {
  id: string;
  certificationId: string;
  documentKind: string;
  documentName: string;
  mimeType: string;
  sizeBytes: number;
  storageKey: string;
  uploadedBy: string | null;
  uploadedAt: string;
  note: string;
  createdAt: string;
}

/**
 * 认证档案详情。
 *
 * <p>🔴 **不** extends {@link CertificationSummary}：台账行带 skuCount / categoryCount /
 * documentCount 三个计数（列表页一行展示），详情接口返回的是**明细数组**而不是计数 ——
 * 两者形状不同，硬套一个 extends 会让页面以为详情里也有计数（实际是 undefined）。
 */
export interface CertificationDetail {
  id: string;
  code: string;
  certificationTypeCode: string;
  certificationTypeName: string;
  title: string;
  certNo: string;
  issuer: string;
  issuingCountry: string;
  status: string;
  expiryState: string;
  validFrom: string;
  validUntil: string;
  daysRemaining: number;
  revokedAt: string | null;
  revokedReason: string;
  note: string;
  version: number;
  skus: CertificationSkuBinding[];
  categories: CertificationCategoryBinding[];
  documents: CertificationDocumentRow[];
  createdAt: string;
  updatedAt: string;
}

/** 认证档案台账（真分页）。 */
export interface CertificationPage {
  items: CertificationSummary[];
  total: number;
  page: number;
  size: number;
}

/** 一条 SKU 的认证要求。 */
export interface RequirementRow {
  id: string;
  skuId: string;
  skuCode: string;
  skuName: string;
  certificationTypeCode: string;
  certificationTypeName: string;
  categoryId: string | null;
  categoryCode: string;
  categoryName: string;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

/** 要求侧登记清单（真分页）。 */
export interface RequirementPage {
  items: RequirementRow[];
  total: number;
  page: number;
  size: number;
}

/** 一条到期提醒。 */
export interface ExpiryReminderRow {
  certificationId: string;
  code: string;
  certificationTypeCode: string;
  certificationTypeName: string;
  title: string;
  certNo: string;
  expiryState: string;
  validUntil: string;
  daysRemaining: number;
  warningDays: number;
  skuCount: number;
  documentCount: number;
}

/** 到期提醒清单。 */
export interface ExpiryReminderPage {
  items: ExpiryReminderRow[];
  total: number;
  page: number;
  size: number;
  warningDays: number;
}

/** 按认证类型汇总的覆盖情况。 */
export interface CoverageByType {
  certificationTypeCode: string;
  certificationTypeName: string;
  requiredCount: number;
  coveredCount: number;
  missingCount: number;
  missingSkuCount: number;
}

/** 按类目汇总的覆盖情况。 */
export interface CoverageByCategory {
  categoryId: string | null;
  categoryCode: string;
  categoryName: string;
  requiredCount: number;
  coveredCount: number;
  missingCount: number;
  missingSkuCount: number;
  missingSkuIds: string[];
}

/** 一个缺认证的 SKU。 */
export interface CoverageMissingSku {
  skuId: string;
  skuCode: string;
  skuName: string;
  missingTypeCodes: string[];
}

/** 覆盖情况统计。 */
export interface CoverageReport {
  requiredRequirementCount: number;
  coveredRequirementCount: number;
  missingRequirementCount: number;
  requiredSkuCount: number;
  coveredSkuCount: number;
  missingSkuCount: number;
  byType: CoverageByType[];
  byCategory: CoverageByCategory[];
  missingSkus: CoverageMissingSku[];
}

/** 单个 SKU 的覆盖详情。 */
export interface CoverageSkuDetail {
  skuId: string;
  skuCode: string;
  skuName: string;
  requirements: CoverageRequirement[];
  coveredTypeCodes: string[];
  missingTypeCodes: string[];
  certificationIds: string[];
}

/** 一个 SKU 的一条要求及其覆盖情况。 */
export interface CoverageRequirement {
  certificationTypeCode: string;
  certificationTypeName: string;
  covered: boolean;
  certificationId: string | null;
  certificationCode: string | null;
  expiryState: string | null;
  categoryId: string | null;
  categoryName: string;
}

/** catalog 的 SKU 候选。 */
export interface SkuCandidate {
  id: string;
  code: string;
  name: string;
  category: string;
  spuId: string;
  source: string;
}

/** catalog 的类目候选。 */
export interface CategoryCandidate {
  id: string;
  code: string;
  name: string;
  path: string;
  treeId: string;
  treeKind: string;
  source: string;
}

/** 候选清单。 */
export interface CandidatePage<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
  source: string;
}

/** 概览。 */
export interface CertificationOverview {
  certificationCount: number;
  activeCount: number;
  expiringCount: number;
  expiredCount: number;
  revokedCount: number;
  coveredSkuCount: number;
  missingSkuCount: number;
  warningDays: number;
}

// ==================================================================
// 接口调用
// ==================================================================

export async function fetchCapabilities(): Promise<ProductCertificationCapabilities> {
  return api<ProductCertificationCapabilities>(`${BASE}/capabilities`);
}

export async function fetchOverview(): Promise<CertificationOverview> {
  return api<CertificationOverview>(`${BASE}/overview`);
}

export async function fetchTypes(): Promise<CertificationTypeRow[]> {
  return api<CertificationTypeRow[]>(`${BASE}/types`);
}

export async function saveType(
  code: string,
  input: {
    code: string;
    name: string;
    scope: string;
    description?: string;
    enabled?: boolean;
    sortOrder?: number;
  },
): Promise<CertificationTypeRow> {
  return api<CertificationTypeRow>(`${BASE}/types/${encodeURIComponent(code)}`, 'PUT', input);
}

export async function deleteType(code: string): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(`${BASE}/types/${encodeURIComponent(code)}`, 'DELETE');
}

export async function fetchPolicy(): Promise<CertificationPolicyView> {
  return api<CertificationPolicyView>(`${BASE}/policy`);
}

export async function savePolicy(input: {
  warningDays: number;
  note?: string;
}): Promise<CertificationPolicyView> {
  return api<CertificationPolicyView>(`${BASE}/policy`, 'PUT', input);
}

export async function fetchCertifications(params: {
  certificationTypeCode?: string | undefined;
  status?: string | undefined;
  expiryState?: string | undefined;
  q?: string | undefined;
  skuId?: string | undefined;
  categoryId?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<CertificationPage> {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    query.set(key, String(value));
  }
  return api<CertificationPage>(`${BASE}/certifications?${query.toString()}`);
}

export async function fetchCertification(id: string): Promise<CertificationDetail> {
  return api<CertificationDetail>(`${BASE}/certifications/${id}`);
}

export async function createCertification(input: {
  code: string;
  certificationTypeCode: string;
  title: string;
  certNo?: string;
  issuer?: string;
  issuingCountry?: string;
  validFrom: string;
  validUntil: string;
  note?: string;
}): Promise<CertificationDetail> {
  return api<CertificationDetail>(`${BASE}/certifications`, 'POST', input);
}

export async function updateCertification(
  id: string,
  input: {
    code: string;
    certificationTypeCode: string;
    title: string;
    certNo?: string;
    issuer?: string;
    issuingCountry?: string;
    validFrom: string;
    validUntil: string;
    note?: string;
    version: number;
  },
): Promise<CertificationDetail> {
  return api<CertificationDetail>(`${BASE}/certifications/${id}`, 'PUT', input);
}

export async function revokeCertification(
  id: string,
  reason: string,
): Promise<CertificationDetail> {
  return api<CertificationDetail>(`${BASE}/certifications/${id}/revoke`, 'POST', { reason });
}

export async function attachSku(
  certificationId: string,
  input: { skuId: string; skuCode?: string; skuName?: string; note?: string },
): Promise<CertificationSkuBinding> {
  return api<CertificationSkuBinding>(
    `${BASE}/certifications/${certificationId}/skus`,
    'POST',
    input,
  );
}

export async function detachSku(
  certificationId: string,
  skuId: string,
): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(
    `${BASE}/certifications/${certificationId}/skus/${skuId}`,
    'DELETE',
  );
}

export async function attachCategory(
  certificationId: string,
  input: {
    categoryId: string;
    categoryCode?: string;
    categoryName?: string;
    requirementNote?: string;
  },
): Promise<CertificationCategoryBinding> {
  return api<CertificationCategoryBinding>(
    `${BASE}/certifications/${certificationId}/categories`,
    'POST',
    input,
  );
}

export async function detachCategory(
  certificationId: string,
  categoryId: string,
): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(
    `${BASE}/certifications/${certificationId}/categories/${categoryId}`,
    'DELETE',
  );
}

/**
 * 上传扫描件。
 *
 * <p>🔴 走 **base64 JSON** 而不是 FormData：浏览器同源代理只转发 JSON
 * （`request.text()` + 固定 `Content-Type: application/json`），multipart 过不去。
 * 代价是体积涨 33%、且受代理请求体上限约束 —— 代理原文件上限由后端能力声明给出。
 */
export async function uploadDocument(
  certificationId: string,
  file: File,
  documentKind: string,
  note?: string,
): Promise<CertificationDocumentRow> {
  const buffer = await file.arrayBuffer();
  let binary = '';
  const bytes = new Uint8Array(buffer);
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return api<CertificationDocumentRow>(
    `${BASE}/certifications/${certificationId}/documents`,
    'POST',
    {
      fileName: file.name,
      contentType: file.type || 'application/octet-stream',
      contentBase64: btoa(binary),
      documentKind,
      note: note ?? '',
    },
  );
}

export async function fetchRequirements(params: {
  skuId?: string | undefined;
  certificationTypeCode?: string | undefined;
  categoryId?: string | undefined;
  q?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<RequirementPage> {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    query.set(key, String(value));
  }
  return api<RequirementPage>(`${BASE}/requirements?${query.toString()}`);
}

export async function createRequirement(input: {
  skuId: string;
  skuCode?: string | undefined;
  skuName?: string | undefined;
  certificationTypeCode: string;
  categoryId?: string | undefined;
  categoryCode?: string | undefined;
  categoryName?: string | undefined;
  note?: string | undefined;
}): Promise<RequirementRow> {
  return api<RequirementRow>(`${BASE}/requirements`, 'POST', input);
}

export async function deleteRequirement(id: string): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(`${BASE}/requirements/${id}`, 'DELETE');
}

export async function fetchCoverage(): Promise<CoverageReport> {
  return api<CoverageReport>(`${BASE}/coverage`);
}

export async function fetchCoverageForSku(skuId: string): Promise<CoverageSkuDetail> {
  return api<CoverageSkuDetail>(`${BASE}/coverage/skus/${skuId}`);
}

export async function fetchExpiryReminders(
  page = 0,
  size = 50,
): Promise<ExpiryReminderPage> {
  return api<ExpiryReminderPage>(`${BASE}/expiry-reminders?page=${page}&size=${size}`);
}

export async function fetchSkuCandidates(
  q?: string,
  page = 0,
  size = 20,
): Promise<CandidatePage<SkuCandidate>> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (q) query.set('q', q);
  return api<CandidatePage<SkuCandidate>>(`${BASE}/sku-candidates?${query.toString()}`);
}

export async function fetchCategoryCandidates(
  treeId?: string,
): Promise<CandidatePage<CategoryCandidate>> {
  const query = new URLSearchParams();
  if (treeId) query.set('treeId', treeId);
  return api<CandidatePage<CategoryCandidate>>(
    `${BASE}/category-candidates${query.toString() ? `?${query.toString()}` : ''}`,
  );
}

// ==================================================================
// 展示口径
// ==================================================================

/** 到期状态文案。 */
export function expiryStateLabel(state: string): string {
  switch (state) {
    case 'VALID':
      return '有效';
    case 'EXPIRING':
      return '即将过期';
    case 'EXPIRED':
      return '已过期';
    case 'FUTURE':
      return '尚未生效';
    case 'REVOKED':
      return '已撤销';
    default:
      return state;
  }
}

/** 到期状态的徽章色调。 */
export function expiryStateTone(state: string): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (state) {
    case 'VALID':
      return 'success';
    case 'EXPIRING':
      return 'warning';
    case 'EXPIRED':
    case 'REVOKED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/**
 * 🔴 剩余天数的展示串。
 *
 * <p>**用后端算好的 `daysRemaining`，前端不重算**：后端按数据库时钟与 floor 规则算，
 * 前端用浏览器时钟重算会因时区与时钟漂移说错。负数表示「已经过期 N 天」。
 */
export function daysRemainingText(days: number): string {
  if (days < 0) return `已过期 ${Math.abs(days)} 天`;
  if (days === 0) return '今天到期';
  return `还剩 ${days} 天`;
}

/** 扫描件类别文案。 */
export function documentKindLabel(kind: string): string {
  switch (kind) {
    case 'ORIGINAL':
      return '原件';
    case 'RENEWAL':
      return '续期件';
    case 'ATTACHMENT':
      return '补充材料';
    default:
      return kind;
  }
}

/** 认证状态文案。 */
export function statusLabel(status: string): string {
  switch (status) {
    case 'ACTIVE':
      return '有效';
    case 'REVOKED':
      return '已撤销';
    default:
      return status;
  }
}

/** 文件大小的展示串（字节 → 可读）。 */
export function fileSizeText(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/**
 * 🔴 空态文案：**三态不同**。
 *
 * @param kind 'NO_CERTIFICATION' 还没有认证档案 / 'NO_REMINDER' 没有到期提醒 / 'NO_GAP' 覆盖上没有缺口
 */
export function emptyState(kind: 'NO_CERTIFICATION' | 'NO_REMINDER' | 'NO_GAP'): {
  title: string;
  description: string;
} {
  switch (kind) {
    case 'NO_CERTIFICATION':
      return {
        title: '还没有登记任何认证',
        description:
          '认证档案是本模块的起点：登记证书编号与有效期，上传扫描件，系统才会算得出「哪些 SKU 缺认证」。',
      };
    case 'NO_REMINDER':
      return {
        title: '没有临近到期或已过期的认证',
        description:
          '当前窗口内没有需要处理的认证。注意「即将过期」的窗口是租户级策略，可以在策略卡里调整。',
      };
    case 'NO_GAP':
      return {
        title: '覆盖上没有缺口',
        description:
          '已登记的认证要求全部被当前有效的认证覆盖。注意：这说的是「已登记的要求」，没登记要求的 SKU 不会被算进来。',
      };
    default:
      return { title: '', description: '' };
  }
}

/**
 * 🔴 上游不可达时的提示文案。
 *
 * <p>刻意**不**与「没有数据」共用一套文案：catalog 读不到时显示空列表，
 * 会被读成「这个租户没有 SKU / 没有类目要求」—— 而真相是「我们没查到」。
 */
export function upstreamUnavailableText(message: string): string {
  return `读不到上游数据（catalog）：${message}。这不是「没有数据」，请稍后重试或检查 catalog 服务。`;
}
