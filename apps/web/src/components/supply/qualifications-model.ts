import { api } from '../inventory/api';

/**
 * 6.1.2.3 供应商资质前端模型。
 *
 * <p>蓝图加粗句：「**资质过期必须阻断相关商品的刊登与发货**，不能只做提醒。」
 *
 * <p>这条约束在界面上的直接后果有三条，缺一条界面就会开始骗人：
 *
 * <ol>
 *   <li><b>「即将过期」与「已过期」是两个不同的状态</b>，界面上必须**分得开**：
 *       前者还在有效期内（该去续期），后者已经越界（该停下来）。
 *       把两者揉成一个「快过期」标签，就会出现「还在有效期内却已经被标红」的事故。
 *   <li><b>提醒 ≠ 阻断</b>：提醒是一条可确认的记录（谁被提醒了、有没有人处理），
 *       阻断是给刊登 / 发货链路执行的判定结论。界面必须分开呈现，不能用一个红点包打天下。
 *   <li><b>到期状态不重算</b>：后端按**数据库时钟**现算，界面上原样显示
 *       {@code expiryState} 与 {@code daysToExpiry}，**不**按浏览器时钟重算 ——
 *       浏览器时钟漂移会让「这张证过期没有」在两台机器上答案不同。
 * </ol>
 */

// ================================================================== 类型

/** 一个词表选项（值 / 中文名 / 提示）。 */
export interface QualificationOption {
  value: string;
  label: string;
  hint: string;
}

/** 一个可排序键。 */
export interface QualificationSortOption {
  key: string;
  label: string;
  defaultDesc: boolean;
}

/** 一份资质档案（列表行 / 详情头）。 */
export interface QualificationRow {
  id: string;
  code: string;
  supplierRoleId: string;
  supplierName: string;
  qualificationType: string;
  qualificationTypeLabel: string;
  title: string;
  certNo: string;
  issuer: string;
  brandId: string | null;
  brandName: string;
  reviewStatus: string;
  reviewStatusLabel: string;
  validFrom: string;
  validUntil: string;
  /** 到期状态（后端按数据库时钟现算，不落库）。 */
  expiryState: string;
  expiryStateLabel: string;
  /** 距到期天数（负 = 已过期 N 天）。 */
  daysToExpiry: number;
  submittedAt: string | null;
  reviewedAt: string | null;
  reviewNote: string;
  documentCount: number;
  openReminderCount: number;
  note: string;
  version: number;
}

/** 资质详情：档案 + 全部文件 + 全部审核流转。 */
export interface QualificationDetail {
  qualification: QualificationRow;
  documents: QualificationDocumentRow[];
  reviews: QualificationReviewEventRow[];
  note: string;
}

/** 一份资质文件。 */
export interface QualificationDocumentRow {
  id: string;
  qualificationId: string;
  documentKind: string;
  documentKindLabel: string;
  documentKey: string;
  documentName: string;
  mimeType: string;
  sizeBytes: number;
  uploadedAt: string;
  note: string;
}

/** 一次审核流转（append-only）。 */
export interface QualificationReviewEventRow {
  id: string;
  qualificationId: string;
  seq: number;
  action: string;
  actionLabel: string;
  fromStatus: string;
  fromStatusLabel: string;
  toStatus: string;
  toStatusLabel: string;
  reason: string;
  operatedAt: string;
}

/** 一条过期提醒。 */
export interface QualificationReminderRow {
  id: string;
  qualificationId: string;
  qualificationCode: string;
  qualificationTitle: string;
  supplierRoleId: string;
  supplierName: string;
  qualificationType: string;
  qualificationTypeLabel: string;
  reminderKind: string;
  reminderKindLabel: string;
  dueDate: string;
  warningDays: number;
  status: string;
  statusLabel: string;
  generatedAt: string;
  acknowledgedAt: string | null;
  note: string;
}

/** 一条阻断原因。 */
export interface BlockingReason {
  code: string;
  label: string;
  message: string;
  qualificationId: string | null;
  qualificationCode: string | null;
  qualificationType: string | null;
  qualificationTypeLabel: string | null;
  reviewStatus: string | null;
  reviewStatusLabel: string | null;
  expiryState: string | null;
  expiryStateLabel: string | null;
  validUntil: string | null;
  daysToExpiry: number | null;
  supplierRoleId: string | null;
  supplierName: string | null;
}

/** 阻断判定结论（04 刊登与发货链路直接消费的那一份）。 */
export interface BlockingDecision {
  blocked: boolean;
  blockCode: string | null;
  blockLabel: string | null;
  blockMessage: string;
  scope: string;
  evaluatedAt: string;
  reasons: BlockingReason[];
  note: string;
}

/** 租户级策略。 */
export interface QualificationPolicyRow {
  warningDays: number;
  requiredTypes: string[];
  note: string;
  updatedAt: string | null;
}

/** 概览统计。 */
export interface QualificationOverview {
  totalCount: number;
  draftCount: number;
  pendingCount: number;
  approvedCount: number;
  rejectedCount: number;
  revokedCount: number;
  expiringCount: number;
  expiredCount: number;
  blockingCount: number;
  openReminderCount: number;
  supplierCount: number;
  brandAuthorizationCount: number;
  nearestExpiry: string | null;
  note: string;
}

/** 一个未交付的下游 / 维度。 */
export interface QualificationGap {
  moduleId: string;
  moduleName: string;
  ownerModule: string;
  available: boolean;
  impact: string;
}

/** 能力声明。 */
export interface SupplierQualificationCapabilities {
  qualificationTypes: QualificationOption[];
  reviewStatuses: QualificationOption[];
  expiryStates: QualificationOption[];
  gaps: QualificationGap[];
  blockingSemantics: string;
  note: string;
}

/** 词表选项。 */
export interface SupplierQualificationOptions {
  qualificationTypes: QualificationOption[];
  reviewStatuses: QualificationOption[];
  expiryStates: QualificationOption[];
  reminderKinds: QualificationOption[];
  reminderStatuses: QualificationOption[];
  sorts: QualificationSortOption[];
  warningDays: number;
  requiredTypes: string[];
  note: string;
}

/** 资质列表分页结果。 */
export interface QualificationPage {
  items: QualificationRow[];
  page: number;
  size: number;
  total: number;
  expiringCount: number;
  expiredCount: number;
  approvedCount: number;
  openReminderCount: number;
  note: string;
}

/** 提醒清单分页结果。 */
export interface QualificationReminderPage {
  items: QualificationReminderRow[];
  page: number;
  size: number;
  total: number;
  openCount: number;
  expiringCount: number;
  expiredCount: number;
  note: string;
}

/** 提醒扫描结果。 */
export interface QualificationReminderScanResult {
  createdCount: number;
  expiringCount: number;
  expiredCount: number;
  scannedAt: string;
  note: string;
}

/** 供应商候选（catalog 只读）。 */
export interface QualificationSupplierCandidateRow {
  roleId: string;
  partyCode: string;
  partyName: string;
  partyStatus: string;
}

/** 品牌候选（03 品牌，只读）。 */
export interface QualificationBrandCandidateRow {
  id: string;
  code: string;
  name: string;
  authorizationStatus: string;
  enabled: boolean;
}

// ================================================================== 表单 / 筛选

/** 登记资质入参。 */
export interface QualificationInput {
  code: string;
  supplierRoleId: string;
  qualificationType: string;
  title: string;
  certNo: string;
  issuer: string;
  brandId: string | null;
  validFrom: string;
  validUntil: string;
  note: string;
  version: number;
}

/** 修改资质入参。 */
export interface QualificationUpdateInput {
  title: string;
  certNo: string;
  issuer: string;
  brandId: string | null;
  validFrom: string;
  validUntil: string;
  note: string;
  version: number;
}

/** 上传资质文件入参。 */
export interface QualificationDocumentInput {
  documentKind: string;
  documentKey: string;
  documentName: string;
  mimeType: string;
  sizeBytes: string;
  note: string;
}

/** 审核流转入参。 */
export interface QualificationReviewInput {
  action: string;
  reason: string;
}

/** 提醒处理入参。 */
export interface QualificationReminderHandleInput {
  status: string;
  note: string;
}

/** 资质列表筛选。 */
export interface QualificationFilters {
  supplierRoleId: string;
  qualificationType: string;
  reviewStatus: string;
  expiryState: string;
  brandId: string;
  keyword: string;
  sort: string;
  desc: boolean;
  page: number;
  size: number;
}

/** 提醒清单筛选。 */
export interface ReminderFilters {
  supplierRoleId: string;
  reminderKind: string;
  status: string;
  page: number;
  size: number;
}

export const EMPTY_QUALIFICATION_FILTERS: QualificationFilters = {
  supplierRoleId: '',
  qualificationType: '',
  reviewStatus: '',
  expiryState: '',
  brandId: '',
  keyword: '',
  sort: 'validUntil',
  desc: true,
  page: 0,
  size: 20,
};

export const EMPTY_REMINDER_FILTERS: ReminderFilters = {
  supplierRoleId: '',
  reminderKind: '',
  status: '',
  page: 0,
  size: 20,
};

/** 空表单初值（登记资质）。 */
export function emptyQualificationForm(): QualificationInput {
  return {
    code: '',
    supplierRoleId: '',
    qualificationType: 'BUSINESS_LICENSE',
    title: '',
    certNo: '',
    issuer: '',
    brandId: null,
    validFrom: '',
    validUntil: '',
    note: '',
    version: 0,
  };
}

/** 空表单初值（上传资质文件）。 */
export function emptyDocumentForm(): QualificationDocumentInput {
  return {
    documentKind: 'ORIGINAL',
    documentKey: '',
    documentName: '',
    mimeType: '',
    sizeBytes: '',
    note: '',
  };
}

// ================================================================== 展示口径

/** 到期状态 → 展示色。**「即将过期」与「已过期」必须是两种色**，不能都归成 warning。 */
export function expiryTone(
  state: string | null | undefined,
): 'success' | 'warning' | 'danger' | 'neutral' {
  if (state === 'EXPIRED') return 'danger';
  if (state === 'EXPIRING') return 'warning';
  if (state === 'VALID') return 'success';
  return 'neutral';
}

/**
 * 到期状态 → 展示串。
 *
 * <p>「即将过期」写清**还剩几天**，「已过期」写清**过了几天** —— 一个是「快去续期」，
 * 一个是「已经出事了」，措辞不能一样。
 */
export function expiryText(
  state: string | null | undefined,
  daysToExpiry: number | null | undefined,
): string {
  if (state === 'EXPIRED') {
    const days = daysToExpiry === null || daysToExpiry === undefined ? 0 : Math.abs(daysToExpiry);
    return days > 0 ? `已过期 ${days} 天` : '已过期';
  }
  if (state === 'EXPIRING') {
    const days = daysToExpiry === null || daysToExpiry === undefined ? 0 : daysToExpiry;
    return days > 0 ? `${days} 天后过期` : '即将过期';
  }
  if (state === 'NOT_EFFECTIVE') return '尚未生效';
  if (state === 'VALID') return '有效';
  return '—';
}

/** 审核状态 → 展示色。 */
export function reviewTone(
  status: string | null | undefined,
): 'success' | 'warning' | 'danger' | 'neutral' {
  if (status === 'APPROVED') return 'success';
  if (status === 'PENDING') return 'warning';
  if (status === 'REJECTED' || status === 'REVOKED') return 'danger';
  return 'neutral';
}

/** 提醒类别 → 展示色（即将过期 / 已过期分开）。 */
export function reminderTone(kind: string | null | undefined): 'warning' | 'danger' | 'neutral' {
  if (kind === 'EXPIRED') return 'danger';
  if (kind === 'EXPIRING') return 'warning';
  return 'neutral';
}

/** 提醒处理状态 → 展示色。 */
export function reminderStatusTone(
  status: string | null | undefined,
): 'danger' | 'warning' | 'success' | 'neutral' {
  if (status === 'OPEN') return 'danger';
  if (status === 'ACKNOWLEDGED') return 'warning';
  if (status === 'RESOLVED') return 'success';
  return 'neutral';
}

/** ISO 时刻 → 只到分钟的串。 */
export function instantText(value: string | null | undefined): string {
  if (!value) return '—';
  return value.slice(0, 16).replace('T', ' ');
}

/** 日期（yyyy-MM-dd）→ 展示串。 */
export function dateText(value: string | null | undefined): string {
  if (!value) return '—';
  return value.slice(0, 10);
}

/** 有效期窗口 → 展示串。 */
export function validityText(
  from: string | null | undefined,
  until: string | null | undefined,
): string {
  if (!from && !until) return '—';
  return `${dateText(from)} ~ ${dateText(until)}`;
}

/** 文件大小 → 展示串。 */
export function fileSizeText(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/** 阻断判定 → 展示色。 */
export function blockingTone(blocked: boolean): 'danger' | 'success' {
  return blocked ? 'danger' : 'success';
}

/** 阻断判定的范围 → 中文名。 */
export function scopeLabel(scope: string | null | undefined): string {
  if (scope === 'SUPPLIER') return '供应商维度';
  if (scope === 'BRAND') return '品牌维度';
  if (scope === 'SKU') return '商品维度';
  return '—';
}

// ================================================================== 本地校验

/**
 * 资质登记 / 修改的本地校验。
 *
 * <p>「授权书必须关联品牌」是蓝图原文「品牌授权需关联 03 品牌」的前置拦截：
 * 不关联就答不出「这个供应商能不能卖这个牌子」。
 */
export function validateQualification(input: QualificationInput): string[] {
  const problems: string[] = [];
  if (!input.code.trim()) problems.push('资质编码必填：它是档案上的稳定引用');
  if (!input.supplierRoleId.trim()) problems.push('供应商必填：资质是挂在某个供应商名下的');
  if (!input.qualificationType.trim()) problems.push('资质类型必填');
  if (!input.title.trim()) problems.push('证件名称必填');
  if (input.qualificationType === 'AUTHORIZATION_LETTER' && !input.brandId) {
    problems.push('授权书是品牌授权，必须关联 03 品牌');
  }
  const from = input.validFrom.trim();
  const until = input.validUntil.trim();
  if (!from) problems.push('有效期起点必填');
  if (!until) problems.push('有效期终点必填');
  if (from && until) {
    // validityProblem 返回 null = 合法；非 null 时**原样**带上它的原因（不要另编一句）。
    const problem = validityProblem(from, until);
    if (problem) problems.push(problem);
  }
  if (input.note.length > 2000) problems.push('备注最长 2000 字');
  return problems;
}

/** 审核流转的本地校验：驳回与撤销必须写明理由。 */
export function validateReview(action: string, reason: string): string[] {
  const problems: string[] = [];
  if (!action.trim()) problems.push('审核动作必填');
  if ((action === 'REJECT' || action === 'REVOKE') && !reason.trim()) {
    problems.push('驳回与撤销必须写明理由：没有理由，供应商永远不知道自己该改什么');
  }
  return problems;
}

/** 上传文件的本地校验。 */
export function validateDocument(input: QualificationDocumentInput): string[] {
  const problems: string[] = [];
  if (!input.documentKind.trim()) problems.push('文件类别必填');
  if (!input.documentKey.trim()) problems.push('文件 key 必填：文件本体不入库，只存对象存储的 key');
  if (input.sizeBytes.trim() && !/^\d+(\.\d+)?$/.test(input.sizeBytes.trim())) {
    problems.push(`文件大小请填数字：${input.sizeBytes}`);
  }
  return problems;
}

/**
 * 有效期起止的先后校验。
 *
 * <p>只接受 `YYYY-MM-DD` 或带时区的 ISO-8601；日期按 UTC 当日零点算，
 * 终点日期按**当日最后一微秒**算（「有效期到 12-31」= 12-31 一整天都有效）。
 */
export function validityProblem(from: string, until: string): string | null {
  const fromMs = parseInstantMs(from, false);
  const untilMs = parseInstantMs(until, true);
  if (fromMs === null || untilMs === null) return '有效期不是合法的时间';
  return untilMs > fromMs ? null : '有效期终点必须晚于起点';
}

function parseInstantMs(value: string, endOfDay: boolean): number | null {
  const raw = value.trim();
  if (!raw) return null;
  if (/^\d{4}-\d{2}-\d{2}$/.test(raw)) {
    const ms = Date.parse(`${raw}T${endOfDay ? '23:59:59.999' : '00:00:00.000'}Z`);
    return Number.isNaN(ms) ? null : ms;
  }
  const ms = Date.parse(raw);
  return Number.isNaN(ms) ? null : ms;
}

// ================================================================== 端点

export function fetchQualificationOptions(): Promise<SupplierQualificationOptions> {
  return api<SupplierQualificationOptions>('trade/supplier-qualifications/options');
}

export function fetchQualificationOverview(): Promise<QualificationOverview> {
  return api<QualificationOverview>('trade/supplier-qualifications/overview');
}

export function fetchQualificationCapabilities(): Promise<SupplierQualificationCapabilities> {
  return api<SupplierQualificationCapabilities>('trade/supplier-qualifications/capabilities');
}

export function fetchQualificationPolicy(): Promise<QualificationPolicyRow> {
  return api<QualificationPolicyRow>('trade/supplier-qualifications/policy');
}

export function saveQualificationPolicy(
  warningDays: number,
  requiredTypes: string[],
  note = '',
): Promise<QualificationPolicyRow> {
  return api<QualificationPolicyRow>('trade/supplier-qualifications/policy', 'PUT', {
    warningDays,
    requiredTypes,
    note,
  });
}

export function fetchQualifications(filters: QualificationFilters): Promise<QualificationPage> {
  const query = new URLSearchParams();
  if (filters.supplierRoleId) query.set('supplierRoleId', filters.supplierRoleId);
  if (filters.qualificationType) query.set('qualificationType', filters.qualificationType);
  if (filters.reviewStatus) query.set('reviewStatus', filters.reviewStatus);
  if (filters.expiryState) query.set('expiryState', filters.expiryState);
  if (filters.brandId) query.set('brandId', filters.brandId);
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.sort) query.set('sort', filters.sort);
  query.set('desc', String(filters.desc));
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<QualificationPage>(`trade/supplier-qualifications/qualifications?${query.toString()}`);
}

export function fetchQualification(qualificationId: string): Promise<QualificationDetail> {
  return api<QualificationDetail>(
    `trade/supplier-qualifications/qualifications/${qualificationId}`,
  );
}

export function createQualification(input: QualificationInput): Promise<QualificationRow> {
  return api<QualificationRow>('trade/supplier-qualifications/qualifications', 'POST', input);
}

export function updateQualification(
  qualificationId: string,
  input: QualificationUpdateInput,
): Promise<QualificationRow> {
  return api<QualificationRow>(
    `trade/supplier-qualifications/qualifications/${qualificationId}`,
    'PUT',
    input,
  );
}

/** 审核流转（状态机；合法转换只有六条，服务层与 DB 触发器各钉一次）。 */
export function reviewQualification(
  qualificationId: string,
  action: string,
  reason = '',
): Promise<unknown> {
  return api(`trade/supplier-qualifications/qualifications/${qualificationId}/review`, 'POST', {
    action,
    reason,
  });
}

/** 上传资质文件（文件本体不入库，只传对象存储的 key）。 */
export function uploadQualificationDocument(
  qualificationId: string,
  input: QualificationDocumentInput,
): Promise<unknown> {
  return api(`trade/supplier-qualifications/qualifications/${qualificationId}/documents`, 'POST', {
    documentKind: input.documentKind,
    documentKey: input.documentKey.trim(),
    documentName: input.documentName.trim(),
    mimeType: input.mimeType.trim(),
    sizeBytes: input.sizeBytes.trim() === '' ? 0 : Number(input.sizeBytes.trim()),
    note: input.note.trim(),
  });
}

export function fetchReminders(filters: ReminderFilters): Promise<QualificationReminderPage> {
  const query = new URLSearchParams();
  if (filters.supplierRoleId) query.set('supplierRoleId', filters.supplierRoleId);
  if (filters.reminderKind) query.set('reminderKind', filters.reminderKind);
  if (filters.status) query.set('status', filters.status);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<QualificationReminderPage>(
    `trade/supplier-qualifications/reminders?${query.toString()}`,
  );
}

/** 生成 / 刷新过期提醒（幂等）。 */
export function scanReminders(): Promise<QualificationReminderScanResult> {
  return api<QualificationReminderScanResult>(
    'trade/supplier-qualifications/reminders/scan',
    'POST',
  );
}

/** 处理一条提醒（确认 / 关闭）。 */
export function handleReminder(reminderId: string, status: string, note = ''): Promise<unknown> {
  return api(`trade/supplier-qualifications/reminders/${reminderId}`, 'PUT', { status, note });
}

/** 供应商候选（catalog 只读；取不到会 503，不会返回空清单）。 */
export function fetchQualificationSuppliers(
  keyword = '',
): Promise<QualificationSupplierCandidateRow[]> {
  const query = new URLSearchParams();
  if (keyword) query.set('keyword', keyword);
  return api<QualificationSupplierCandidateRow[]>(
    `trade/supplier-qualifications/suppliers?${query.toString()}`,
  );
}

/** 品牌候选（03 品牌，只读）。 */
export function fetchQualificationBrands(keyword = ''): Promise<QualificationBrandCandidateRow[]> {
  const query = new URLSearchParams();
  if (keyword) query.set('keyword', keyword);
  return api<QualificationBrandCandidateRow[]>(
    `trade/supplier-qualifications/brands?${query.toString()}`,
  );
}

/** 供应商维度的阻断判定。 */
export function fetchSupplierBlockingDecision(supplierRoleId: string): Promise<BlockingDecision> {
  return api<BlockingDecision>(
    `trade/supplier-qualifications/suppliers/${supplierRoleId}/blocking-decision`,
  );
}

/** 供应商 × 品牌的阻断判定（判「这个供应商现在还能不能卖这个牌子」）。 */
export function fetchSupplierBrandBlockingDecision(
  supplierRoleId: string,
  brandId: string,
): Promise<BlockingDecision> {
  return api<BlockingDecision>(
    `trade/supplier-qualifications/suppliers/${supplierRoleId}/brands/${brandId}/blocking-decision`,
  );
}

/** 商品维度的阻断判定。 */
export function fetchSkuBlockingDecision(skuId: string): Promise<BlockingDecision> {
  return api<BlockingDecision>(`trade/supplier-qualifications/skus/${skuId}/blocking-decision`);
}
