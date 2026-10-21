import { api } from '../inventory/api';

/**
 * 6.1.2.2 同款货源推荐前端模型。
 *
 * <p>蓝图加粗句：**同款判定要给置信度**，图片相似不等于同款，误判会导致采购到错误商品。
 *
 * <p>这条约束在界面上的直接后果：
 *
 * <ul>
 *   <li><b>置信度与排序分并排显示，且标签写清楚它们不是一回事</b> ——
 *       置信度回答「是不是同款」，排序分回答「先看哪一条」。折叠成一个「推荐度」之后，
 *       运营会把「便宜」误读成「同一个货」。</li>
 *   <li><b>每条候选都显示判定依据</b>（凭什么算成候选）——
 *       没有依据的货源根本不该出现在清单里。</li>
 *   <li><b>需要人工确认的候选带醒目标记，且没确认就不给「生成采购申请」</b> ——
 *       疑似同款直接下单就是把采购单指向错误商品。</li>
 *   <li><b>价格永远是区间 + 采集时刻</b>，没有精确价；<b>历史交期没有就是「暂无」</b>，
 *       不是 0 天；<b>评级客观分与最终分并排</b>，人工调整过的单独标记。</li>
 * </ul>
 */

/** 推荐理由。 */
export type SourceAlternativeReason = 'STOCKOUT' | 'QUALITY';

/** 判定类型（证据强度从高到低）。 */
export type SourceAlternativeMatchType =
  | 'SKU_LINK'
  | 'PURCHASE_HISTORY'
  | 'TITLE_SPEC_MATCH';

/** 批次状态。 */
export type SourceAlternativeRunStatus = 'OPEN' | 'REQUESTED' | 'CLOSED';

/** 判定类型。 */
export type SourceAlternativeDecisionType = 'CONFIRMED' | 'REJECTED' | 'REQUESTED';

/** 能力声明里的一条。 */
export interface SourceAlternativeCapability {
  moduleId: string;
  name: string;
  ownerService: string;
  available: boolean;
  impact: string;
}

/** 能力与上下游可用性。 */
export interface SourceAlternativeCapabilities {
  capabilities: SourceAlternativeCapability[];
  matchTypes: string[];
  notes: string[];
}

/** 词表选项。 */
export interface SourceAlternativeOption {
  value: string;
  label: string;
  hint: string;
}

/** 词表。 */
export interface SourceAlternativeOptions {
  reasons: SourceAlternativeOption[];
  matchTypes: SourceAlternativeOption[];
  statuses: SourceAlternativeOption[];
  decisionTypes: SourceAlternativeOption[];
  notes: string[];
}

/** 目标仓下拉项。 */
export interface SourceAlternativeWarehouseOption {
  id: string;
  code: string;
  name: string;
  status: string;
}

/** 法人下拉项。 */
export interface SourceAlternativeLegalEntityOption {
  id: string;
  code: string;
  name: string;
  status: string;
}

/** 目标仓与法人。 */
export interface SourceAlternativeTargetOptions {
  warehouses: SourceAlternativeWarehouseOption[];
  legalEntities: SourceAlternativeLegalEntityOption[];
  available: boolean;
  unavailableNote: string;
}

/** 概览。 */
export interface SourceAlternativeOverview {
  runCount: number;
  openRunCount: number;
  candidateCount: number;
  confirmedCandidateCount: number;
  weakCandidateCount: number;
  requestCount: number;
  lastRunAt: string | null;
  note: string;
}

/** 租户级规则与排序权重。 */
export interface SourceAlternativePolicy {
  configured: boolean;
  weakMatchMinConfidence: number;
  confirmedMatchMinConfidence: number;
  requireManualConfirmation: boolean;
  maxCandidates: number;
  priceWeight: number;
  ratingWeight: number;
  historyWeight: number;
  leadTimeWeight: number;
  version: number;
  updatedAt: string | null;
}

/** 规则表单。 */
export interface SourceAlternativePolicyForm {
  weakMatchMinConfidence: string;
  confirmedMatchMinConfidence: string;
  requireManualConfirmation: boolean;
  maxCandidates: string;
  priceWeight: string;
  ratingWeight: string;
  historyWeight: string;
  leadTimeWeight: string;
}

/** 一条候选货源。 */
export interface SourceAlternativeCandidate {
  id: string | null;
  runId: string | null;
  itemId: string;
  stallId: string;
  stallName: string;
  platform: string;
  platformItemId: string;
  supplierId: string | null;
  supplierCode: string;
  supplierName: string;
  title: string;
  spec: string;
  imageUrl: string;
  priceMin: number | string | null;
  priceMax: number | string | null;
  currency: string | null;
  moq: number | string | null;
  collectedAt: string | null;
  onSale: boolean;
  matchType: SourceAlternativeMatchType;
  matchTypeLabel: string;
  confidence: number | string;
  matchBasis: string;
  requiresManualConfirmation: boolean;
  historyOrderCount: number;
  historyQuantity: number | string;
  leadTimeDays: number | string | null;
  ratingObjectiveScore: number | string | null;
  ratingFinalScore: number | string | null;
  ratingGrade: string | null;
  ratingManuallyAdjusted: boolean | null;
  ratingPeriodStart: string | null;
  ratingPeriodEnd: string | null;
  priceRank: number | null;
  lowestPrice: boolean;
  score: number | string;
  sortRank: number;
  decision: string | null;
  decisionLabel: string | null;
  purchaseRequestNo: string | null;
}

/** 判定事件。 */
export interface SourceAlternativeDecision {
  id: string;
  candidateId: string;
  decisionType: SourceAlternativeDecisionType;
  decisionTypeLabel: string;
  reason: string;
  purchaseRequestId: string | null;
  purchaseRequestNo: string | null;
  decidedBy: string;
  decidedAt: string;
}

/** 一个币种下的价格对比。 */
export interface SourceAlternativePriceComparison {
  currency: string;
  lowestPrice: number | string | null;
  highestPrice: number | string | null;
  candidateCount: number;
}

/** 一次推荐批次。 */
export interface SourceAlternativeRun {
  id: string;
  runNo: string;
  skuId: string;
  skuCode: string;
  skuName: string;
  reason: SourceAlternativeReason;
  reasonLabel: string;
  requiredQuantity: number | string | null;
  referenceImageUrl: string;
  status: SourceAlternativeRunStatus;
  statusLabel: string;
  candidateCount: number;
  confirmedCount: number;
  targetWarehouseId: string | null;
  targetWarehouseCode: string;
  targetWarehouseName: string;
  legalEntityId: string | null;
  legalEntityCode: string;
  legalEntityName: string;
  note: string;
  createdBy: string;
  createdAt: string;
}

/** 批次列表。 */
export interface SourceAlternativeRunPage {
  items: SourceAlternativeRun[];
  total: number;
  page: number;
  size: number;
}

/** 批次详情。 */
export interface SourceAlternativeRunDetail {
  run: SourceAlternativeRun;
  candidates: SourceAlternativeCandidate[];
  decisions: SourceAlternativeDecision[];
  priceComparison: SourceAlternativePriceComparison[];
  note: string;
}

/** 列表筛选。 */
export interface SourceAlternativeFilters {
  skuId: string;
  reason: string;
  status: string;
  keyword: string;
  page: number;
  size: number;
}

export const EMPTY_SOURCE_ALTERNATIVE_FILTERS: SourceAlternativeFilters = {
  skuId: '',
  reason: '',
  status: '',
  keyword: '',
  page: 0,
  size: 20,
};

/** 建一次推荐的入参。 */
export interface SourceAlternativeCreateRunInput {
  skuId: string;
  reason: SourceAlternativeReason;
  requiredQuantity?: string;
  note?: string;
}

/** 人工判定入参。 */
export interface SourceAlternativeDecisionInput {
  decisionType: 'CONFIRMED' | 'REJECTED';
  reason: string;
}

/** 生成采购申请入参。 */
export interface SourceAlternativePurchaseRequestInput {
  quantity: string;
  targetWarehouseId: string;
  legalEntityId: string;
  note?: string;
}

/** 生成采购申请的结果。 */
export interface SourceAlternativePurchaseRequestResult {
  purchaseRequestId: string;
  purchaseRequestNo: string;
  purchaseRequestStatus: string;
  candidateId: string;
  runNo: string;
  note: string;
}

// ================================================================== 纯函数（可单测）

/** 把后端可能给成 string 或 number 的数值读成 number。 */
export function numeric(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null;
  const parsed = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

/**
 * 置信度文案：**百分比**，一位小数。
 *
 * <p>刻意不做成「1.0000」这种裸小数 —— 运营看的是「像不像同一个货」，
 * 百分比比小数直观，而「同款概率 55%」这句话本身就是一句需要被质疑的话（它不是概率）。
 */
export function confidenceLabel(confidence: number | string | null | undefined): string {
  const value = numeric(confidence);
  if (value === null) return '置信度未知';
  return `${(value * 100).toFixed(1)}%`;
}

/** 置信度色调：确证 = 绿，强线索 = 黄，弱线索 = 红。 */
export function confidenceTone(
  confidence: number | string | null | undefined,
): 'success' | 'warning' | 'danger' | 'neutral' {
  const value = numeric(confidence);
  if (value === null) return 'neutral';
  if (value >= 0.9) return 'success';
  if (value >= 0.5) return 'warning';
  return 'danger';
}

/** 判定类型的本地兜底文案（后端给的中文优先）。 */
export function matchTypeLabel(
  matchType: string,
  fallback?: string | null,
): string {
  if (fallback) return fallback;
  switch (matchType) {
    case 'SKU_LINK':
      return '已确认同款（人工关联）';
    case 'PURCHASE_HISTORY':
      return '历史采购过该货的供应商';
    case 'TITLE_SPEC_MATCH':
      return '疑似同款（标题与规格一致）';
    default:
      return matchType;
  }
}

/**
 * 价格区间文案：**永远是区间 + 币种**，不给一个假精确价。
 *
 * <p>外部货源普遍是阶梯价，只显示一个价就是把区间假装成精确值。
 */
export function priceRangeLabel(
  priceMin: number | string | null | undefined,
  priceMax: number | string | null | undefined,
  currency: string | null | undefined,
): string {
  const min = numeric(priceMin);
  const max = numeric(priceMax);
  if (min === null) return '暂无价格';
  const unit = currency ? `${currency} ` : '';
  if (max === null || max === min) return `${unit}${min.toFixed(4)}`;
  return `${unit}${min.toFixed(4)}~${max.toFixed(4)}`;
}

/** 起订量文案。没有就是「未知」，**不是 0**（0 件起订是另一回事）。 */
export function moqLabel(moq: number | string | null | undefined): string {
  const value = numeric(moq);
  if (value === null) return '起订量未知';
  return `起订量 ${value.toFixed(0)}`;
}

/** 历史交期文案。没有历史就是「暂无历史交期」，**不是 0 天**。 */
export function leadTimeLabel(days: number | string | null | undefined): string {
  const value = numeric(days);
  if (value === null) return '暂无历史交期';
  return `历史平均交期 ${value.toFixed(1)} 天`;
}

/**
 * 评级文案：**客观分与最终分并排**，人工调整过的单独标记。
 *
 * <p>蓝图 6.1.1.3：评级必须由客观数据算出，人工加权分需单独标记。
 * 折叠成一个「总分」之后，三个月后没人能回答「这个 A 是数据算出来的还是谁拍的」。
 */
export function ratingLabel(
  objectiveScore: number | string | null | undefined,
  finalScore: number | string | null | undefined,
  grade: string | null | undefined,
  manuallyAdjusted: boolean | null | undefined,
): string {
  const objective = numeric(objectiveScore);
  const final = numeric(finalScore);
  if (objective === null && final === null) return '暂无评级';
  const objectiveText = objective === null ? '—' : objective.toFixed(2);
  const finalText = final === null ? '—' : final.toFixed(2);
  const gradeText = grade ? `（${grade}）` : '';
  const manual = manuallyAdjusted ? ' · 人工调整' : '';
  return `客观 ${objectiveText} / 最终 ${finalText}${gradeText}${manual}`;
}

/** 排序分文案（**与置信度不是一回事**，标签必须写清楚）。 */
export function scoreLabel(score: number | string | null | undefined): string {
  const value = numeric(score);
  if (value === null) return '排序分未知';
  return `排序分 ${(value * 100).toFixed(1)}`;
}

/** 采集时刻文案。 */
export function collectedAtText(value: string | null | undefined): string {
  if (!value) return '采集时刻未知';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return `采集于 ${date.toLocaleString('zh-CN', { hour12: false })}`;
}

/** 判定状态文案。 */
export function decisionLabel(decision: string | null | undefined): string {
  switch (decision) {
    case 'CONFIRMED':
      return '已人工确认是同款';
    case 'REJECTED':
      return '已否决';
    case 'REQUESTED':
      return '已生成采购申请';
    default:
      return '待处置';
  }
}

/** 候选能不能直接生成采购申请。 */
export function canCreateRequest(candidate: SourceAlternativeCandidate): boolean {
  if (!candidate.id) return false;
  if (!candidate.supplierId) return false;
  if (candidate.requiresManualConfirmation && candidate.decision !== 'CONFIRMED') return false;
  return true;
}

/** 不能生成时的**具体原因**（界面上要说得清为什么按钮是灰的）。 */
export function requestBlockedReason(candidate: SourceAlternativeCandidate): string {
  if (!candidate.supplierId) return '这个货源没有关联供应商，没有下单对象';
  if (candidate.requiresManualConfirmation && candidate.decision !== 'CONFIRMED') {
    return '疑似同款必须先人工确认是同款，才能生成采购申请';
  }
  return '';
}

/** 规则表单校验：权重和必须**精确等于 1**（用整数算，避免浮点误差）。 */
export function validatePolicyForm(form: SourceAlternativePolicyForm): string[] {
  const errors: string[] = [];
  const weights = [form.priceWeight, form.ratingWeight, form.historyWeight, form.leadTimeWeight];
  const parsed = weights.map((w) => numeric(w));
  if (parsed.some((w) => w === null || w < 0 || w > 1)) {
    errors.push('四个维度的权重都必须是不小于 0、不大于 1 的数字');
    return errors;
  }
  const total = parsed.reduce<number>(
    (sum, w) => sum + Math.round((w as number) * 1_000_000),
    0,
  );
  if (total !== 1_000_000) {
    errors.push(`四个维度的权重之和必须精确等于 1，当前是 ${(total / 1_000_000).toFixed(6)}`);
  }
  const weak = numeric(form.weakMatchMinConfidence);
  if (weak === null || weak < 0 || weak > 1) {
    errors.push('弱匹配最低置信度必须在 0 与 1 之间');
  }
  const confirmed = numeric(form.confirmedMatchMinConfidence);
  if (confirmed === null || confirmed < 0 || confirmed > 1) {
    errors.push('确证匹配最低置信度必须在 0 与 1 之间');
  }
  const max = numeric(form.maxCandidates);
  if (max === null || max < 1 || max > 500) {
    errors.push('单次候选上限必须在 1 与 500 之间');
  }
  return errors;
}

/** 由后端策略转成表单。 */
export function toPolicyForm(policy: SourceAlternativePolicy): SourceAlternativePolicyForm {
  return {
    weakMatchMinConfidence: String(policy.weakMatchMinConfidence),
    confirmedMatchMinConfidence: String(policy.confirmedMatchMinConfidence),
    requireManualConfirmation: policy.requireManualConfirmation,
    maxCandidates: String(policy.maxCandidates),
    priceWeight: String(policy.priceWeight),
    ratingWeight: String(policy.ratingWeight),
    historyWeight: String(policy.historyWeight),
    leadTimeWeight: String(policy.leadTimeWeight),
  };
}

/** 判定表单校验：否决必须写理由。 */
export function validateDecisionForm(
  decisionType: 'CONFIRMED' | 'REJECTED',
  reason: string,
): string[] {
  const errors: string[] = [];
  if (decisionType === 'REJECTED' && !reason.trim()) {
    errors.push('否决必须写理由：说不出理由的否决等于没否决');
  }
  return errors;
}

/** 生成采购申请的表单校验：数量必须大于 0 且不低于起订量。 */
export function validateRequestForm(
  quantity: string,
  targetWarehouseId: string,
  legalEntityId: string,
  moq: number | string | null | undefined,
): string[] {
  const errors: string[] = [];
  const value = numeric(quantity);
  if (value === null || value <= 0) {
    errors.push('申请数量必须大于 0');
  } else {
    const min = numeric(moq);
    if (min !== null && value < min) {
      errors.push(`申请数量不能低于起订量 ${min.toFixed(0)}（起订量是外部平台的硬约束）`);
    }
  }
  if (!targetWarehouseId) errors.push('必须选目标仓');
  if (!legalEntityId) errors.push('必须选法人');
  return errors;
}

// ================================================================== 端点

export function fetchSourceAlternativeCapabilities(): Promise<SourceAlternativeCapabilities> {
  return api<SourceAlternativeCapabilities>('trade/source-alternatives/capabilities');
}

export function fetchSourceAlternativeOptions(): Promise<SourceAlternativeOptions> {
  return api<SourceAlternativeOptions>('trade/source-alternatives/options');
}

export function fetchSourceAlternativeTargets(): Promise<SourceAlternativeTargetOptions> {
  return api<SourceAlternativeTargetOptions>('trade/source-alternatives/targets');
}

export function fetchSourceAlternativeOverview(): Promise<SourceAlternativeOverview> {
  return api<SourceAlternativeOverview>('trade/source-alternatives/overview');
}

export function fetchSourceAlternativePolicy(): Promise<SourceAlternativePolicy> {
  return api<SourceAlternativePolicy>('trade/source-alternatives/policy');
}

export function saveSourceAlternativePolicy(
  form: SourceAlternativePolicyForm,
): Promise<SourceAlternativePolicy> {
  return api<SourceAlternativePolicy>('trade/source-alternatives/policy', 'PUT', {
    weakMatchMinConfidence: Number(form.weakMatchMinConfidence),
    confirmedMatchMinConfidence: Number(form.confirmedMatchMinConfidence),
    requireManualConfirmation: form.requireManualConfirmation,
    maxCandidates: Number(form.maxCandidates),
    priceWeight: Number(form.priceWeight),
    ratingWeight: Number(form.ratingWeight),
    historyWeight: Number(form.historyWeight),
    leadTimeWeight: Number(form.leadTimeWeight),
  });
}

export function fetchSourceAlternativeRuns(
  filters: SourceAlternativeFilters,
): Promise<SourceAlternativeRunPage> {
  const query = new URLSearchParams();
  if (filters.skuId) query.set('skuId', filters.skuId);
  if (filters.reason) query.set('reason', filters.reason);
  if (filters.status) query.set('status', filters.status);
  if (filters.keyword) query.set('keyword', filters.keyword);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<SourceAlternativeRunPage>(`trade/source-alternatives/runs?${query.toString()}`);
}

export function fetchSourceAlternativeRun(runId: string): Promise<SourceAlternativeRunDetail> {
  return api<SourceAlternativeRunDetail>(`trade/source-alternatives/runs/${runId}`);
}

/** 发起一次推荐（写动作）。 */
export function createSourceAlternativeRun(
  input: SourceAlternativeCreateRunInput,
): Promise<SourceAlternativeRunDetail> {
  return api<SourceAlternativeRunDetail>('trade/source-alternatives/runs', 'POST', {
    skuId: input.skuId,
    reason: input.reason,
    requiredQuantity:
      input.requiredQuantity && input.requiredQuantity.trim()
        ? Number(input.requiredQuantity)
        : null,
    note: input.note ?? '',
  });
}

/** 实时候选预览（不落库）。 */
export function previewSourceAlternativeCandidates(
  skuId: string,
): Promise<SourceAlternativeCandidate[]> {
  return api<SourceAlternativeCandidate[]>(
    `trade/source-alternatives/skus/${skuId}/candidates`,
  );
}

/** 人工判定（写动作）。 */
export function decideSourceAlternativeCandidate(
  candidateId: string,
  input: SourceAlternativeDecisionInput,
): Promise<SourceAlternativeDecision> {
  return api<SourceAlternativeDecision>(
    `trade/source-alternatives/candidates/${candidateId}/decision`,
    'POST',
    { decisionType: input.decisionType, reason: input.reason },
  );
}

/** 一键生成采购申请（写动作）。 */
export function createSourceAlternativePurchaseRequest(
  candidateId: string,
  input: SourceAlternativePurchaseRequestInput,
): Promise<SourceAlternativePurchaseRequestResult> {
  return api<SourceAlternativePurchaseRequestResult>(
    `trade/source-alternatives/candidates/${candidateId}/purchase-request`,
    'POST',
    {
      quantity: Number(input.quantity),
      targetWarehouseId: input.targetWarehouseId,
      legalEntityId: input.legalEntityId,
      note: input.note ?? '',
    },
  );
}
