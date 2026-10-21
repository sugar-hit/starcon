import { api, ApiError } from '../../../lib/api';

/**
 * 10.7.1.3 弃置与销毁前端模型。
 *
 * <p>蓝图：「管理弃置与销毁：无销售价值/品牌方要求/法规不允许再销售的退货，处理销毁、
 * <b>留存销毁凭证/照片/重量记录</b>，满足平台与品牌方的销毁证明要求；<b>销毁数量可作为
 * 品牌方索赔依据</b>。输入：待销毁清单；销毁方式（压碎/焚烧/专业销毁）；凭证要求。
 * 输出：销毁单 + 凭证 + 数量/重量记录；索赔依据（→ 6.1.2.3 / 11.2.3.3）。」
 *
 * <p>🔴 这个界面上**最容易做成假活**的两处：
 *
 * <ol>
 *   <li><b>「凭证」不能只是一个备注栏</b>：{@link buildEvidencePayload} 一次只登记
 *       <b>一类</b>凭证（照片 / 重量 / 处置证明编号 / 执行方签核），每类有专属必填字段；
 *       {@link missingEvidence} 把服务端那份「还缺哪几样」原样摊开给运营看 ——
 *       <b>凭证不齐时页面就不让点「执行销毁」</b>。</li>
 *   <li><b>「销毁方式」不能是自由文本</b>：{@link buildDisposalOrderPayload} 只接受
 *       压碎 / 焚烧 / 专业销毁三个枚举值，而且方式<b>决定</b>凭证要求
 *       （{@link methodRequirements} 与服务端同一套口径）。</li>
 * </ol>
 *
 * <p>🔴 币种纪律：销毁费用与索赔金额都走销毁单那<b>一个</b>费用币种，
 * 页面<b>不做任何汇率换算</b>，两者<b>永远不相加</b>（{@link moneyText} 只做展示）。
 */

// ================================================================ 枚举词表

/** 销毁单状态。 */
export type DisposalStatus = 'DRAFT' | 'SCHEDULED' | 'DISPOSED' | 'CANCELLED';

/** 🔴 销毁方式（枚举事实，不是自由文本）。 */
export type DisposalMethod = 'SHRED' | 'INCINERATE' | 'PROFESSIONAL';

/** 凭证种类。 */
export type EvidenceKind = 'PHOTO' | 'WEIGHT' | 'CERTIFICATE' | 'EXECUTOR';

/** 销毁费用类型。 */
export type DisposalCostType =
  | 'DESTROY_FEE'
  | 'PICKUP_FEE'
  | 'TRANSPORT_FEE'
  | 'LABEL_FEE'
  | 'OTHER';

/** 索赔依据类型。 */
export type ClaimType = 'BRAND' | 'SUPPLIER';

/** 凭证要求主体类型。 */
export type PolicySubjectType = 'GENERAL' | 'BRAND' | 'PLATFORM';

// ================================================================ 形状

export interface DisposalCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string | null;
  impact: string;
}

export interface ReturnDisposalCapabilities {
  dimensions: DisposalCapabilityDimension[];
  statuses: string[];
  methods: string[];
  evidenceKinds: string[];
  costTypes: string[];
  claimTypes: string[];
  weightUnits: string[];
  methodNote: string;
  evidenceNote: string;
  currencyNote: string;
  financeNote: string;
  claimNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface DisposalCurrencyTotal {
  currency: string;
  amount: number;
  itemCount: number;
}

export interface ReturnDisposalOverview {
  orderCount: number;
  draftCount: number;
  scheduledCount: number;
  disposedCount: number;
  cancelledCount: number;
  candidateCount: number;
  candidateQty: number;
  evidenceCount: number;
  disposedOrderCount: number;
  disposedQty: number;
  disposedWeightKg: number;
  claimBasisCount: number;
  costTotals: DisposalCurrencyTotal[];
  evaluatedAt: string;
  note: string;
  currencyNote: string;
}

export interface DisposalCandidateRow {
  dispositionOrderId: string;
  dispositionNo: string;
  decisionId: string;
  receiptNo: string;
  skuCode: string;
  skuName: string;
  batchNo: string;
  brandCode: string;
  platformCode: string;
  disposedQty: number;
  dispositionMethod: string;
  dispositionMethodLabel: string;
  issuedAt: string;
  note: string;
}

export interface DisposalCandidatePage {
  rows: DisposalCandidateRow[];
  page: number;
  size: number;
  total: number;
  totalQty: number;
  evaluatedAt: string;
  note: string;
}

export interface ReturnDisposalPolicyRow {
  id: string;
  subjectType: string;
  subjectTypeLabel: string;
  subjectRef: string;
  requiredPhoto: boolean;
  requiredWeight: boolean;
  requiredCertificate: boolean;
  requiredExecutor: boolean;
  effectiveFrom: string;
  effectiveTo: string | null;
  status: string;
  statusLabel: string;
  note: string;
  createdAt: string;
}

export interface ReturnDisposalPolicyPage {
  rows: ReturnDisposalPolicyRow[];
  page: number;
  size: number;
  total: number;
  evaluatedAt: string;
  note: string;
}

export interface ReturnDisposalRow {
  id: string;
  disposalNo: string;
  status: string;
  statusLabel: string;
  disposalMethod: string;
  disposalMethodLabel: string;
  sourceReference: string;
  dispositionOrderId: string;
  decisionId: string;
  receiptNo: string;
  skuCode: string;
  skuName: string;
  batchNo: string;
  brandCode: string;
  platformCode: string;
  disposalQty: number;
  weightKg: number;
  weightUnit: string;
  requirePhoto: boolean;
  requireWeight: boolean;
  requireCertificate: boolean;
  requireExecutor: boolean;
  costCurrency: string;
  totalCost: number;
  evidenceCount: number;
  plannedAt: string | null;
  disposedAt: string | null;
  executorName: string;
  createdAt: string;
  note: string;
  version: number;
}

export interface ReturnDisposalPage {
  rows: ReturnDisposalRow[];
  page: number;
  size: number;
  total: number;
  totalCost: number;
  evaluatedAt: string;
  note: string;
}

export interface ReturnDisposalEvidenceRow {
  id: string;
  disposalOrderId: string;
  evidenceKind: string;
  evidenceKindLabel: string;
  photoUrl: string;
  weightKg: number | null;
  certificateNo: string;
  executorName: string;
  occurredAt: string;
  recordedBy: string;
  note: string;
  createdAt: string;
}

export interface ReturnDisposalCostRow {
  id: string;
  disposalOrderId: string;
  costType: string;
  costTypeLabel: string;
  amount: number;
  currency: string;
  vendorRef: string;
  incurredAt: string;
  note: string;
}

export interface ReturnDisposalClaimBasisRow {
  id: string;
  disposalOrderId: string;
  claimType: string;
  claimTypeLabel: string;
  counterpartyCode: string;
  claimQty: number;
  claimWeightKg: number;
  claimedAmount: number;
  claimedCurrency: string;
  evidenceComplete: boolean;
  sourceReference: string;
  claimRef: string;
  status: string;
  statusLabel: string;
  submittedAt: string | null;
  rejectReason: string;
  note: string;
  createdAt: string;
}

export interface ReturnDisposalEventRow {
  id: string;
  eventType: string;
  eventTypeLabel: string;
  orderId: string | null;
  evidenceId: string | null;
  costId: string | null;
  claimBasisId: string | null;
  policyId: string | null;
  actorId: string;
  occurredAt: string;
  detail: string;
}

export interface ReturnDisposalEventPage {
  rows: ReturnDisposalEventRow[];
  page: number;
  size: number;
  total: number;
  evaluatedAt: string;
  note: string;
}

export interface ReturnDisposalDetail {
  order: ReturnDisposalRow;
  evidence: ReturnDisposalEvidenceRow[];
  costs: ReturnDisposalCostRow[];
  claimBases: ReturnDisposalClaimBasisRow[];
  events: ReturnDisposalEventRow[];
}

// ================================================================ 取数

const BASE = 'channel/return-disposals';

export function fetchCapabilities(): Promise<ReturnDisposalCapabilities> {
  return api<ReturnDisposalCapabilities>(`${BASE}/capabilities`);
}

export function fetchOverview(): Promise<ReturnDisposalOverview> {
  return api<ReturnDisposalOverview>(`${BASE}/overview`);
}

export interface CandidateQuery {
  keyword?: string;
  page?: number;
  size?: number;
}

export function fetchCandidates(query: CandidateQuery = {}): Promise<DisposalCandidatePage> {
  const params = new URLSearchParams();
  if (query.keyword) params.set('keyword', query.keyword);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 50));
  return api<DisposalCandidatePage>(`${BASE}/candidates?${params.toString()}`);
}

export interface PolicyQuery {
  subjectType?: string;
  status?: string;
  page?: number;
  size?: number;
}

export function fetchPolicies(query: PolicyQuery = {}): Promise<ReturnDisposalPolicyPage> {
  const params = new URLSearchParams();
  if (query.subjectType) params.set('subjectType', query.subjectType);
  if (query.status) params.set('status', query.status);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 50));
  return api<ReturnDisposalPolicyPage>(`${BASE}/policies?${params.toString()}`);
}

export interface OrderQuery {
  keyword?: string;
  status?: string;
  method?: string;
  page?: number;
  size?: number;
}

export function fetchOrders(query: OrderQuery = {}): Promise<ReturnDisposalPage> {
  const params = new URLSearchParams();
  if (query.keyword) params.set('keyword', query.keyword);
  if (query.status) params.set('status', query.status);
  if (query.method) params.set('method', query.method);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 50));
  return api<ReturnDisposalPage>(`${BASE}/orders?${params.toString()}`);
}

export function fetchOrder(id: string): Promise<ReturnDisposalDetail> {
  return api<ReturnDisposalDetail>(`${BASE}/orders/${id}`);
}

export function fetchEvents(
  orderId?: string,
  page = 0,
  size = 50,
): Promise<ReturnDisposalEventPage> {
  const params = new URLSearchParams();
  if (orderId) params.set('orderId', orderId);
  params.set('page', String(page));
  params.set('size', String(size));
  return api<ReturnDisposalEventPage>(`${BASE}/events?${params.toString()}`);
}

// ================================================================ 写动作

export interface DisposalOrderPayload {
  dispositionOrderId: string;
  disposalMethod: string;
  disposalQty: number;
  weightUnit?: string;
  costCurrency: string;
  note?: string;
}

export function createOrder(payload: DisposalOrderPayload): Promise<ReturnDisposalDetail> {
  return api<ReturnDisposalDetail>(`${BASE}/orders`, 'POST', payload);
}

export function scheduleOrder(id: string, plannedAt?: string): Promise<ReturnDisposalDetail> {
  return api<ReturnDisposalDetail>(`${BASE}/orders/${id}/schedule`, 'POST', { plannedAt });
}

export interface EvidencePayload {
  evidenceKind: string;
  photoUrl?: string;
  weightKg?: number;
  certificateNo?: string;
  executorName?: string;
  occurredAt?: string;
  note?: string;
}

export function recordEvidence(
  id: string,
  payload: EvidencePayload,
): Promise<ReturnDisposalDetail> {
  return api<ReturnDisposalDetail>(`${BASE}/orders/${id}/evidence`, 'POST', payload);
}

export interface DisposalCostPayload {
  costType: string;
  amount: number;
  currency?: string;
  vendorRef?: string;
  incurredAt?: string;
  note?: string;
}

export function recordCost(id: string, payload: DisposalCostPayload): Promise<ReturnDisposalDetail> {
  return api<ReturnDisposalDetail>(`${BASE}/orders/${id}/costs`, 'POST', payload);
}

export interface ClaimBasisPayload {
  claimType: string;
  counterpartyCode: string;
  claimedAmount?: number;
  claimedCurrency?: string;
  note?: string;
}

export function createClaimBasis(
  id: string,
  payload: ClaimBasisPayload,
): Promise<ReturnDisposalDetail> {
  return api<ReturnDisposalDetail>(`${BASE}/orders/${id}/claim-basis`, 'POST', payload);
}

export function submitClaimBasis(id: string, claimRef: string): Promise<ReturnDisposalDetail> {
  return api<ReturnDisposalDetail>(`${BASE}/claim-bases/${id}/submit`, 'POST', { claimRef });
}

export function rejectClaimBasis(id: string, reason: string): Promise<ReturnDisposalDetail> {
  return api<ReturnDisposalDetail>(`${BASE}/claim-bases/${id}/reject`, 'POST', { reason });
}

export interface DisposePayload {
  disposedAt?: string;
  executedBy?: string;
  executorName: string;
  weightKg?: number;
  note?: string;
}

export function disposeOrder(id: string, payload: DisposePayload): Promise<ReturnDisposalDetail> {
  return api<ReturnDisposalDetail>(`${BASE}/orders/${id}/dispose`, 'POST', payload);
}

export function cancelOrder(id: string, reason: string): Promise<ReturnDisposalDetail> {
  return api<ReturnDisposalDetail>(`${BASE}/orders/${id}/cancel`, 'POST', { reason });
}

export interface PolicyPayload {
  subjectType: string;
  subjectRef: string;
  requiredPhoto: boolean;
  requiredWeight: boolean;
  requiredCertificate: boolean;
  requiredExecutor: boolean;
  effectiveFrom?: string;
  effectiveTo?: string;
  note?: string;
}

export function createPolicy(payload: PolicyPayload): Promise<ReturnDisposalPolicyRow> {
  return api<ReturnDisposalPolicyRow>(`${BASE}/policies`, 'POST', payload);
}

export function revokePolicy(id: string, reason: string): Promise<ReturnDisposalPolicyRow> {
  return api<ReturnDisposalPolicyRow>(`${BASE}/policies/${id}/revoke`, 'POST', { reason });
}

// ================================================================ 展示口径

export function statusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'DRAFT':
      return '草稿（待排期）';
    case 'SCHEDULED':
      return '已排期（凭证要求已冻结）';
    case 'DISPOSED':
      return '已销毁（完成态）';
    case 'CANCELLED':
      return '已取消';
    default:
      return '未知状态';
  }
}

/** 🔴 销毁方式标签（枚举事实，不是自由文本）。 */
export function methodLabel(method: string | null | undefined): string {
  switch (method) {
    case 'SHRED':
      return '压碎';
    case 'INCINERATE':
      return '焚烧';
    case 'PROFESSIONAL':
      return '专业销毁';
    default:
      return '未知方式';
  }
}

export function evidenceKindLabel(kind: string | null | undefined): string {
  switch (kind) {
    case 'PHOTO':
      return '销毁照片';
    case 'WEIGHT':
      return '重量记录';
    case 'CERTIFICATE':
      return '处置证明编号';
    case 'EXECUTOR':
      return '执行方签核';
    default:
      return '未知凭证';
  }
}

export function costTypeLabel(type: string | null | undefined): string {
  switch (type) {
    case 'DESTROY_FEE':
      return '销毁费';
    case 'PICKUP_FEE':
      return '上门取件费';
    case 'TRANSPORT_FEE':
      return '运输费';
    case 'LABEL_FEE':
      return '标签 / 处理费';
    case 'OTHER':
      return '其他费用';
    default:
      return '其他费用';
  }
}

export function claimTypeLabel(type: string | null | undefined): string {
  switch (type) {
    case 'BRAND':
      return '品牌方索赔';
    case 'SUPPLIER':
      return '供应商索赔';
    default:
      return '未知索赔类型';
  }
}

export function subjectTypeLabel(type: string | null | undefined): string {
  switch (type) {
    case 'GENERAL':
      return '租户默认';
    case 'BRAND':
      return '品牌方要求';
    case 'PLATFORM':
      return '平台政策';
    default:
      return '未知主体';
  }
}

/** 金额展示：**原样**显示币种与数额，不做任何换算。 */
export function moneyText(amount: number | null | undefined, currency?: string | null): string {
  if (amount === null || amount === undefined || Number.isNaN(amount)) return '—';
  const fixed = amount.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

/** 数量展示（数量是 NUMERIC(19,6)）。 */
export function quantityText(qty: number | null | undefined): string {
  if (qty === null || qty === undefined || Number.isNaN(qty)) return '—';
  return Number.isInteger(qty) ? String(qty) : qty.toFixed(6);
}

// ================================================================ 凭证闸（页面的那一半）

/**
 * 🔴 销毁方式的**基线凭证要求**（与服务端 `return_disposal_method_requirements` 同一套口径）。
 *
 * <p>品牌方 / 平台政策只能**加严**，所以页面用它算出一个**下限**；
 * 真正的判定在服务端（它还会把政策并进来），页面只是提前把话说在前面。
 */
export function methodRequirements(method: string | null | undefined): {
  photo: boolean;
  weight: boolean;
  certificate: boolean;
  executor: boolean;
} {
  return {
    photo: true,
    weight: method === 'PROFESSIONAL',
    certificate: method === 'INCINERATE' || method === 'PROFESSIONAL',
    executor: true,
  };
}

/**
 * 🔴 还缺哪几类凭证。
 *
 * <p>页面用它在「执行销毁」按钮旁把缺项**原样列出来** ——
 * 凭证缺失会触发品牌方追责，所以这件事必须在界面上就说清楚，
 * 而不是等提交后吃一个 422。
 */
export function missingEvidence(
  order: Pick<
    ReturnDisposalRow,
    'requirePhoto' | 'requireWeight' | 'requireCertificate' | 'requireExecutor'
  >,
  evidence: ReturnDisposalEvidenceRow[],
): string[] {
  const present = new Set(evidence.map((row) => row.evidenceKind));
  const missing: string[] = [];
  if (order.requirePhoto && !present.has('PHOTO')) missing.push('PHOTO');
  if (order.requireWeight && !present.has('WEIGHT')) missing.push('WEIGHT');
  if (order.requireCertificate && !present.has('CERTIFICATE')) missing.push('CERTIFICATE');
  if (order.requireExecutor && !present.has('EXECUTOR')) missing.push('EXECUTOR');
  return missing;
}

/** 🔴 凭证是否齐备（齐备才允许点「执行销毁」）。 */
export function evidenceReady(
  order: Pick<
    ReturnDisposalRow,
    'requirePhoto' | 'requireWeight' | 'requireCertificate' | 'requireExecutor'
  >,
  evidence: ReturnDisposalEvidenceRow[],
): boolean {
  return missingEvidence(order, evidence).length === 0;
}

// ================================================================ 表单与请求体

export interface OrderForm {
  dispositionOrderId: string;
  disposalMethod: string;
  disposalQty: string;
  weightUnit: string;
  costCurrency: string;
  note: string;
}

export function emptyOrderForm(): OrderForm {
  return {
    dispositionOrderId: '',
    // 🔴 销毁方式**不预选**：方式决定凭证要求，页面从不替运营做这个决定
    disposalMethod: '',
    disposalQty: '',
    weightUnit: 'KG',
    costCurrency: 'USD',
    note: '',
  };
}

/**
 * 🔴 建销毁单的请求体。
 *
 * <p>销毁方式是**必选**且必须是三个枚举值之一 —— 少给一个直接拒，
 * 不许页面悄悄替它挑一个。
 */
export function buildDisposalOrderPayload(
  form: OrderForm,
): DisposalOrderPayload | { error: string } {
  if (!form.dispositionOrderId) return { error: '必须先从待销毁清单里选一条处置单' };
  if (!form.disposalMethod) return { error: '必须选择销毁方式（压碎 / 焚烧 / 专业销毁）' };
  if (!['SHRED', 'INCINERATE', 'PROFESSIONAL'].includes(form.disposalMethod)) {
    return { error: '销毁方式只能是压碎 / 焚烧 / 专业销毁' };
  }
  const qty = Number(form.disposalQty);
  if (!Number.isFinite(qty) || qty <= 0) return { error: '销毁数量必须大于 0' };
  if (!/^[A-Z]{3}$/.test(form.costCurrency)) {
    return { error: '费用币种必须是三个大写字母（例如 USD）' };
  }
  return {
    dispositionOrderId: form.dispositionOrderId,
    disposalMethod: form.disposalMethod,
    disposalQty: qty,
    weightUnit: form.weightUnit || 'KG',
    costCurrency: form.costCurrency,
    ...(form.note.trim() ? { note: form.note.trim() } : {}),
  };
}

export interface EvidenceForm {
  evidenceKind: string;
  photoUrl: string;
  weightKg: string;
  certificateNo: string;
  executorName: string;
  occurredAt: string;
  note: string;
}

export function emptyEvidenceForm(): EvidenceForm {
  return {
    evidenceKind: '',
    photoUrl: '',
    weightKg: '',
    certificateNo: '',
    executorName: '',
    occurredAt: '',
    note: '',
  };
}

/** 🔴 登记一类凭证的请求体：每类有**专属必填字段**。 */
export function buildEvidencePayload(form: EvidenceForm): EvidencePayload | { error: string } {
  if (!form.evidenceKind) return { error: '必须选择凭证种类' };
  if (!['PHOTO', 'WEIGHT', 'CERTIFICATE', 'EXECUTOR'].includes(form.evidenceKind)) {
    return { error: '凭证种类只能是照片 / 重量 / 处置证明编号 / 执行方签核' };
  }
  if (form.evidenceKind === 'PHOTO' && !form.photoUrl.trim()) {
    return { error: '照片凭证必须填照片地址' };
  }
  if (form.evidenceKind === 'WEIGHT') {
    const weight = Number(form.weightKg);
    if (!form.weightKg.trim() || !Number.isFinite(weight) || weight < 0) {
      return { error: '重量凭证必须填一个不小于 0 的重量' };
    }
  }
  if (form.evidenceKind === 'CERTIFICATE' && !form.certificateNo.trim()) {
    return { error: '处置证明凭证必须填处置证明编号' };
  }
  if (form.evidenceKind === 'EXECUTOR' && !form.executorName.trim()) {
    return { error: '执行方签核凭证必须填执行方' };
  }
  return {
    evidenceKind: form.evidenceKind,
    ...(form.photoUrl.trim() ? { photoUrl: form.photoUrl.trim() } : {}),
    ...(form.weightKg.trim() ? { weightKg: Number(form.weightKg) } : {}),
    ...(form.certificateNo.trim() ? { certificateNo: form.certificateNo.trim() } : {}),
    ...(form.executorName.trim() ? { executorName: form.executorName.trim() } : {}),
    ...(form.occurredAt ? { occurredAt: new Date(form.occurredAt).toISOString() } : {}),
    ...(form.note.trim() ? { note: form.note.trim() } : {}),
  };
}

export interface DisposeForm {
  executorName: string;
  weightKg: string;
  disposedAt: string;
  note: string;
}

export function emptyDisposeForm(): DisposeForm {
  return { executorName: '', weightKg: '', disposedAt: '', note: '' };
}

/** 🔴 执行销毁的请求体：执行方必填（时刻与执行方是完成态的硬性要件）。 */
export function buildDisposePayload(form: DisposeForm): DisposePayload | { error: string } {
  if (!form.executorName.trim()) return { error: '必须写明执行方' };
  if (form.weightKg.trim()) {
    const weight = Number(form.weightKg);
    if (!Number.isFinite(weight) || weight < 0) return { error: '销毁重量不能小于 0' };
  }
  return {
    executorName: form.executorName.trim(),
    ...(form.weightKg.trim() ? { weightKg: Number(form.weightKg) } : {}),
    ...(form.disposedAt ? { disposedAt: new Date(form.disposedAt).toISOString() } : {}),
    ...(form.note.trim() ? { note: form.note.trim() } : {}),
  };
}

export interface CostForm {
  costType: string;
  amount: string;
  currency: string;
  vendorRef: string;
  incurredAt: string;
  note: string;
}

export function emptyCostForm(): CostForm {
  return {
    costType: 'DESTROY_FEE',
    amount: '',
    currency: 'USD',
    vendorRef: '',
    incurredAt: '',
    note: '',
  };
}

export function buildCostPayload(form: CostForm): DisposalCostPayload | { error: string } {
  if (!form.costType) return { error: '必须选择费用类型' };
  const amount = Number(form.amount);
  if (!form.amount.trim() || !Number.isFinite(amount) || amount < 0) {
    return { error: '费用金额必须是一个不小于 0 的数字' };
  }
  if (!/^[A-Z]{3}$/.test(form.currency)) {
    return { error: '费用币种必须是三个大写字母（例如 USD）' };
  }
  return {
    costType: form.costType,
    amount,
    currency: form.currency,
    ...(form.vendorRef.trim() ? { vendorRef: form.vendorRef.trim() } : {}),
    ...(form.incurredAt ? { incurredAt: new Date(form.incurredAt).toISOString() } : {}),
    ...(form.note.trim() ? { note: form.note.trim() } : {}),
  };
}

export interface ClaimBasisForm {
  claimType: string;
  counterpartyCode: string;
  claimedAmount: string;
  claimedCurrency: string;
  note: string;
}

export function emptyClaimBasisForm(): ClaimBasisForm {
  return {
    claimType: 'BRAND',
    counterpartyCode: '',
    claimedAmount: '',
    claimedCurrency: 'USD',
    note: '',
  };
}

export function buildClaimBasisPayload(
  form: ClaimBasisForm,
): ClaimBasisPayload | { error: string } {
  if (!form.claimType) return { error: '必须选择索赔类型' };
  if (!form.counterpartyCode.trim()) return { error: '必须写明索赔对方（品牌方 / 供应商编码）' };
  if (form.claimedAmount.trim()) {
    const amount = Number(form.claimedAmount);
    if (!Number.isFinite(amount) || amount < 0) return { error: '索赔金额不能小于 0' };
  }
  if (!/^[A-Z]{3}$/.test(form.claimedCurrency)) {
    return { error: '索赔币种必须是三个大写字母（例如 USD）' };
  }
  return {
    claimType: form.claimType,
    counterpartyCode: form.counterpartyCode.trim(),
    ...(form.claimedAmount.trim() ? { claimedAmount: Number(form.claimedAmount) } : {}),
    claimedCurrency: form.claimedCurrency,
    ...(form.note.trim() ? { note: form.note.trim() } : {}),
  };
}

export interface PolicyForm {
  subjectType: string;
  subjectRef: string;
  requiredPhoto: boolean;
  requiredWeight: boolean;
  requiredCertificate: boolean;
  requiredExecutor: boolean;
  note: string;
}

export function emptyPolicyForm(): PolicyForm {
  return {
    subjectType: 'BRAND',
    subjectRef: '',
    requiredPhoto: true,
    requiredWeight: false,
    requiredCertificate: false,
    requiredExecutor: true,
    note: '',
  };
}

/** 凭证要求：**只能加严**，所以四项至少要开一项。 */
export function buildPolicyPayload(form: PolicyForm): PolicyPayload | { error: string } {
  if (!form.subjectType) return { error: '必须选择要求主体类型' };
  if (form.subjectType === 'GENERAL') {
    // GENERAL 的 subject_ref 是保留位（服务端固定成 'ALL'）
    if (
      !form.requiredPhoto &&
      !form.requiredWeight &&
      !form.requiredCertificate &&
      !form.requiredExecutor
    ) {
      return { error: '凭证要求至少要开一项：要求只能加严，不能全关' };
    }
    return {
      subjectType: 'GENERAL',
      subjectRef: 'ALL',
      requiredPhoto: form.requiredPhoto,
      requiredWeight: form.requiredWeight,
      requiredCertificate: form.requiredCertificate,
      requiredExecutor: form.requiredExecutor,
      ...(form.note.trim() ? { note: form.note.trim() } : {}),
    };
  }
  if (
    !form.requiredPhoto &&
    !form.requiredWeight &&
    !form.requiredCertificate &&
    !form.requiredExecutor
  ) {
    return { error: '凭证要求至少要开一项：要求只能加严，不能全关' };
  }
  if (!form.subjectRef.trim()) {
    return { error: '品牌方 / 平台要求必须写明对应的编码' };
  }
  return {
    subjectType: form.subjectType,
    subjectRef: form.subjectRef.trim(),
    requiredPhoto: form.requiredPhoto,
    requiredWeight: form.requiredWeight,
    requiredCertificate: form.requiredCertificate,
    requiredExecutor: form.requiredExecutor,
    ...(form.note.trim() ? { note: form.note.trim() } : {}),
  };
}

// ================================================================ 错误态

export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 422 || error.status === 409) return error.message;
    if (error.status === 403) return '当前角色没有弃置与销毁的操作权限';
    if (error.status === 0) return '正在连接渠道服务，请稍后重试';
    return error.message;
  }
  return '加载失败，请稍后重试';
}
