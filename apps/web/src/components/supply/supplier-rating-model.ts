import { api } from '../inventory/api';

/**
 * 6.1.1.3 供应商评级前端模型。
 *
 * <p>蓝图加粗句：**评级必须由客观数据算出，人工加权分需单独标记，否则会变成纯主观打分。**
 *
 * <p>这条约束在界面上的直接后果：
 *
 * <ul>
 *   <li><b>客观分与最终分并排显示</b>，且人工调整过的行带一个醒目的「人工调整」标记 ——
 *       折叠成一个「总分」之后，三个月后没人能回答「这个 A 是数据算出来的还是谁拍的」。</li>
 *   <li><b>维度的 null 绝不能渲染成 0 分</b>：后端在分母为 0 时返回 null
 *       （规则：不可用 ≠ 0 分），前端再补一次同样的判断 ——
 *       一个刚开���合作的供应商在「没交货过」与「交货全部延期」之间必须能一眼分开。</li>
 *   <li><b>权重和必须精确等于 1</b>，保存前在前端就拦一道（后端还有服务层 + DB CHECK 两道）。</li>
 * </ul>
 */

/** 评级周期类型。 */
export type SupplierRatingPeriodType = 'MONTHLY' | 'QUARTERLY';

/** 评级等级。 */
export type SupplierRatingGrade = 'A' | 'B' | 'C' | 'D';

/** 五个评级维度。 */
export type SupplierRatingDimensionKey =
  | 'DELIVERY'
  | 'QUALITY'
  | 'RESPONSE'
  | 'PRICE'
  | 'SERVICE';

/** 评级变动的来源。 */
export type SupplierRatingChangeType = 'PERIOD_ROLL' | 'RECOMPUTE' | 'MANUAL_ADJUSTMENT';

/** 词表选项。 */
export interface SupplierRatingOption {
  value: string;
  label: string;
  hint: string;
}

/** 能力声明里的一条。 */
export interface SupplierRatingCapability {
  name: string;
  label: string;
  available: boolean;
  ownerModule: string | null;
  impact: string | null;
}

/** 能力声明。 */
export interface SupplierRatingCapabilities {
  capabilities: SupplierRatingCapability[];
  dimensions: string[];
  notes: string[];
}

/** 词表。 */
export interface SupplierRatingOptions {
  periodTypes: SupplierRatingOption[];
  dimensions: SupplierRatingOption[];
  grades: SupplierRatingOption[];
  changeTypes: SupplierRatingOption[];
}

/** 权重配置。 */
export interface SupplierRatingPolicy {
  periodType: SupplierRatingPeriodType;
  deliveryWeight: number;
  qualityWeight: number;
  responseWeight: number;
  priceWeight: number;
  serviceWeight: number;
  responseTargetHours: number;
  gradeAMin: number;
  gradeBMin: number;
  gradeCMin: number;
  configured: boolean;
  defaultDeliveryWeight: number;
  defaultQualityWeight: number;
  defaultResponseWeight: number;
  defaultPriceWeight: number;
  defaultServiceWeight: number;
  version: number;
  note: string | null;
}

/** 权重配置表单。 */
export interface SupplierRatingPolicyForm {
  periodType: SupplierRatingPeriodType;
  deliveryWeight: string;
  qualityWeight: string;
  responseWeight: string;
  priceWeight: string;
  serviceWeight: string;
  responseTargetHours: string;
  note: string;
}

/** 评级结果的一行。 */
export interface SupplierRatingRow {
  id: string;
  periodType: SupplierRatingPeriodType;
  periodStart: string;
  periodEnd: string;
  supplierRefId: string;
  supplierCode: string;
  supplierName: string;
  objectiveScore: number;
  manualAdjustment: number;
  finalScore: number;
  grade: SupplierRatingGrade;
  rankInPeriod: number | null;
  availableWeight: number;
  unavailableDimensions: string;
  unavailableReason: string;
  deliveryScore: number | null;
  qualityScore: number | null;
  responseScore: number | null;
  priceScore: number | null;
  serviceScore: number | null;
  deliveryOnTimeRate: number | null;
  deliveryArrivedCount: number;
  qualityPassRate: number | null;
  qualityInspectedQuantity: number;
  qualityQualifiedQuantity: number;
  responseAvgHours: number | null;
  responseSampleCount: number;
  priceIndex: number | null;
  priceSampleCount: number;
  priceComparableQuantity: number;
  serviceCooperationRate: number | null;
  serviceClaimCount: number;
  manuallyAdjusted: boolean;
  manualAdjustedBy: string | null;
  manualAdjustedAt: string | null;
  manualAdjustmentReason: string;
  computedAt: string;
  version: number;
}

/** 列表分页结果。 */
export interface SupplierRatingPage {
  items: SupplierRatingRow[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 列表筛选条件。 */
export interface SupplierRatingFilters {
  periodType: SupplierRatingPeriodType;
  periodStart: string;
  supplierRefId: string;
  grade: string;
  keyword: string;
  sort: string;
  desc: boolean;
  page: number;
  size: number;
}

/** 概览。 */
export interface SupplierRatingOverview {
  periodType: SupplierRatingPeriodType;
  periodStart: string;
  periodEnd: string;
  ratedSupplierCount: number;
  gradeACount: number;
  gradeBCount: number;
  gradeCCount: number;
  gradeDCount: number;
  averageScore: number | null;
  averageObjectiveScore: number | null;
  manuallyAdjustedCount: number;
  partialDimensionCount: number;
  note: string;
}

/** 一次计算的结果。 */
export interface SupplierRatingComputeResult {
  periodType: SupplierRatingPeriodType;
  periodStart: string;
  periodEnd: string;
  computedCount: number;
  createdCount: number;
  updatedCount: number;
  changeRecordCount: number;
  unavailableDimensions: string[];
  note: string;
}

/** 人工调整留痕。 */
export interface SupplierRatingAdjustmentRow {
  id: string;
  resultId: string;
  supplierRefId: string;
  periodType: SupplierRatingPeriodType;
  periodStart: string;
  periodEnd: string;
  objectiveScore: number;
  previousScore: number;
  adjustedScore: number;
  adjustment: number;
  reason: string;
  adjustedBy: string;
  adjustedAt: string;
}

/** 评级变动历史的一行。 */
export interface SupplierRatingChangeRow {
  id: string;
  supplierRefId: string;
  supplierCode: string;
  supplierName: string;
  periodType: SupplierRatingPeriodType;
  periodStart: string;
  periodEnd: string;
  previousGrade: SupplierRatingGrade | null;
  previousScore: number | null;
  currentGrade: SupplierRatingGrade;
  currentScore: number;
  scoreDelta: number | null;
  gradeChanged: boolean;
  changeType: SupplierRatingChangeType;
  reason: string;
  operatorId: string;
  occurredAt: string;
}

/** 变动历史分页。 */
export interface SupplierRatingChangePage {
  items: SupplierRatingChangeRow[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 优先级的一行。 */
export interface SupplierRatingPriorityRow {
  rank: number;
  supplierRefId: string;
  supplierCode: string;
  supplierName: string;
  finalScore: number;
  objectiveScore: number;
  grade: SupplierRatingGrade;
  manuallyAdjusted: boolean;
  periodStart: string;
  periodEnd: string;
  unavailableDimensions: string | null;
}

/** 优先级清单。 */
export interface SupplierRatingPriorityList {
  periodType: SupplierRatingPeriodType;
  periodStart: string;
  periodEnd: string;
  items: SupplierRatingPriorityRow[];
  note: string;
}

/** 单个评级详情。 */
export interface SupplierRatingDetail {
  result: SupplierRatingRow;
  adjustments: SupplierRatingAdjustmentRow[];
  changes: SupplierRatingChangeRow[];
  note: string;
}

/** 人工调整的入参。 */
export interface SupplierRatingAdjustmentInput {
  adjustment: string;
  reason: string;
}

// ================================================================== 词表与标签

const DIMENSION_LABELS: Record<SupplierRatingDimensionKey, string> = {
  DELIVERY: '交期达成率',
  QUALITY: '质量合格率',
  RESPONSE: '响应时长',
  PRICE: '价格竞争力',
  SERVICE: '售后配合度',
};

const GRADE_LABELS: Record<SupplierRatingGrade, string> = {
  A: 'A 级',
  B: 'B 级',
  C: 'C 级',
  D: 'D 级',
};

const CHANGE_TYPE_LABELS: Record<SupplierRatingChangeType, string> = {
  PERIOD_ROLL: '跨周期滚动',
  RECOMPUTE: '同周期重算',
  MANUAL_ADJUSTMENT: '人工调整',
};

const PERIOD_LABELS: Record<SupplierRatingPeriodType, string> = {
  MONTHLY: '月度',
  QUARTERLY: '季度',
};

/** 维度键 → 中文名。 */
export function dimensionLabel(key: string): string {
  return DIMENSION_LABELS[key as SupplierRatingDimensionKey] ?? key;
}

/** 等级 → 中文名。 */
export function gradeLabel(grade: string | null | undefined): string {
  if (!grade) return '未定级';
  return GRADE_LABELS[grade as SupplierRatingGrade] ?? grade;
}

/** 等级的展示色。 */
export function gradeTone(grade: string | null | undefined): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (grade) {
    case 'A':
      return 'success';
    case 'B':
      return 'success';
    case 'C':
      return 'warning';
    case 'D':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 变动来源 → 中文名。 */
export function changeTypeLabel(type: string): string {
  return CHANGE_TYPE_LABELS[type as SupplierRatingChangeType] ?? type;
}

/** 周期类型 → 中文名。 */
export function periodTypeLabel(type: string): string {
  return PERIOD_LABELS[type as SupplierRatingPeriodType] ?? type;
}

/**
 * 比率 → 展示串。
 *
 * <p>null（这个周期没有可算的依据）显示「暂无依据」而**不是 0%** ——
 * 「没交货过」与「交货全部延期」是相反的两件事，折叠成一个数字就会误伤新供应商。
 */
export function rateLabel(rate: string | number | null | undefined): string {
  if (rate === null || rate === undefined || rate === '') return '暂无依据';
  const value = typeof rate === 'number' ? rate : Number(rate);
  if (Number.isNaN(value)) return '暂无依据';
  return `${(value * 100).toFixed(2)}%`;
}

/** 维度的得分 → 展示串（null = 不可用，不是 0 分）。 */
export function scoreLabel(score: string | number | null | undefined): string {
  if (score === null || score === undefined || score === '') return '不可用';
  const value = typeof score === 'number' ? score : Number(score);
  if (Number.isNaN(value)) return '不可用';
  return value.toFixed(2);
}

/** 分数 → 展示串。 */
export function finalScoreLabel(score: string | number | null | undefined): string {
  if (score === null || score === undefined || score === '') return '—';
  const value = typeof score === 'number' ? score : Number(score);
  if (Number.isNaN(value)) return '—';
  return value.toFixed(2);
}

/** 人工调整量 → 展示串（带正负号）。 */
export function adjustmentLabel(adjustment: string | number | null | undefined): string {
  if (adjustment === null || adjustment === undefined || adjustment === '') return '无人工调整';
  const value = typeof adjustment === 'number' ? adjustment : Number(adjustment);
  if (Number.isNaN(value)) return '无人工调整';
  if (value === 0) return '无人工调整';
  return value > 0 ? `+${value.toFixed(2)}` : value.toFixed(2);
}

/** 覆盖权重 → 展示串（百分比）。 */
export function weightLabel(weight: string | number | null | undefined): string {
  if (weight === null || weight === undefined || weight === '') return '—';
  const value = typeof weight === 'number' ? weight : Number(weight);
  if (Number.isNaN(value)) return '—';
  return `${(value * 100).toFixed(0)}%`;
}

/** 周期区间 → 展示串。 */
export function periodText(start: string | null | undefined, end: string | null | undefined): string {
  if (!start || !end) return '本期';
  return `${start.slice(0, 10)} ~ ${end.slice(0, 10)}`;
}

/** 不可用维度清单 → 展示串。 */
export function unavailableText(dimensions: string | null | undefined): string {
  if (!dimensions) return '';
  const keys = dimensions
    .split(',')
    .map((k) => k.trim())
    .filter(Boolean);
  if (keys.length === 0) return '';
  return keys.map(dimensionLabel).join('、');
}

/** 响应时长 → 展示串。 */
export function hoursLabel(hours: string | number | null | undefined): string {
  if (hours === null || hours === undefined || hours === '') return '暂无依据';
  const value = typeof hours === 'number' ? hours : Number(hours);
  if (Number.isNaN(value)) return '暂无依据';
  return `${value.toFixed(1)} 小时`;
}

/** 数量 → 展示串（去掉无意义的小数尾巴）。 */
export function quantityLabel(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '0';
  const num = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(num)) return '0';
  return num.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/** ISO 时刻 → 只到分钟的串。 */
export function timeText(value: string | null | undefined): string {
  if (!value) return '—';
  return value.slice(0, 16).replace('T', ' ');
}

// ================================================================== 本地校验

/** 权重串 → 数字（空串按 0 处理）。 */
function weightNumber(raw: string): number | null {
  const value = raw.trim();
  if (!value) return 0;
  if (!/^-?\d+(\.\d+)?$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isNaN(parsed) ? null : parsed;
}

/**
 * 权重配置的本地校验。
 *
 * <p>前端这道校验是为了**早点告诉人**，不是安全边界 —— 后端有规则层与 DB CHECK 兜底。
 * 权重和必须**精确等于 1**：大于 1 综合分被放大、小于 1 被压缩，两种都会让名次与分数脱钩。
 */
export function validatePolicyForm(form: SupplierRatingPolicyForm): string[] {
  const problems: string[] = [];
  const labels: Record<string, string> = {
    deliveryWeight: '交期达成率',
    qualityWeight: '质量合格率',
    responseWeight: '响应时长',
    priceWeight: '价格竞争力',
    serviceWeight: '售后配合度',
  };
  let sum = 0;
  for (const [key, label] of Object.entries(labels)) {
    const value = weightNumber(form[key as keyof SupplierRatingPolicyForm] as string);
    if (value === null) {
      problems.push(`${label}的权重请填数字`);
      continue;
    }
    if (value < 0 || value > 1) {
      problems.push(`${label}的权重必须在 0 ~ 1 之间`);
      continue;
    }
    sum += value;
  }
  if (problems.length === 0 && Math.abs(sum - 1) > 1e-9) {
    problems.push(
      `五个维度的权重之和必须精确等于 1（当前是 ${sum.toFixed(6)}）：权重和不为 1 时综合分会失真，而这种失真不会报错`,
    );
  }
  const hours = Number(form.responseTargetHours.trim());
  if (!form.responseTargetHours.trim() || Number.isNaN(hours) || hours <= 0) {
    problems.push('响应目标小时数必须大于 0');
  }
  if (form.note.length > 2000) problems.push('备注最长 2000 字');
  return problems;
}

/**
 * 人工调整的本地校验。
 *
 * <p>理由必填：没有理由的人工调整等于没留痕，三个月后没人能解释这个名次。
 */
export function validateAdjustment(input: SupplierRatingAdjustmentInput): string[] {
  const problems: string[] = [];
  const raw = input.adjustment.trim();
  if (!raw) {
    problems.push('调整量必填');
  } else if (!/^-?\d+(\.\d+)?$/.test(raw)) {
    problems.push(`调整量请填数字：${input.adjustment}`);
  } else {
    const value = Number(raw);
    if (Number.isNaN(value)) problems.push('调整量请填数字');
    else if (value === 0) problems.push('调整量不能为 0：一次「调整了 0 分」是噪声');
    else if (value > 50 || value < -50) problems.push('调整量必须在 −50 ~ +50 之间');
  }
  if (!input.reason.trim()) {
    problems.push('理由必填：没有理由的人工调整等于没留痕，三个月后无法解释这个名次');
  }
  if (input.reason.length > 1000) problems.push('理由最长 1000 字');
  return problems;
}

/** 权重表单初值（从后端读回来的配置或默认口径铺出来）。 */
export function toPolicyForm(policy: SupplierRatingPolicy): SupplierRatingPolicyForm {
  return {
    periodType: policy.periodType,
    deliveryWeight: String(policy.deliveryWeight),
    qualityWeight: String(policy.qualityWeight),
    responseWeight: String(policy.responseWeight),
    priceWeight: String(policy.priceWeight),
    serviceWeight: String(policy.serviceWeight),
    responseTargetHours: String(policy.responseTargetHours),
    note: policy.note ?? '',
  };
}

/** 空筛选条件。 */
export const EMPTY_SUPPLIER_RATING_FILTERS: SupplierRatingFilters = {
  periodType: 'MONTHLY',
  periodStart: '',
  supplierRefId: '',
  grade: '',
  keyword: '',
  sort: 'finalScore',
  desc: true,
  page: 0,
  size: 20,
};

// ================================================================== 端点

export function fetchSupplierRatingCapabilities(): Promise<SupplierRatingCapabilities> {
  return api<SupplierRatingCapabilities>('trade/supplier-ratings/capabilities');
}

export function fetchSupplierRatingOptions(): Promise<SupplierRatingOptions> {
  return api<SupplierRatingOptions>('trade/supplier-ratings/options');
}

export function fetchSupplierRatingOverview(
  periodType: string,
  periodStart?: string,
): Promise<SupplierRatingOverview> {
  const query = new URLSearchParams();
  query.set('periodType', periodType);
  if (periodStart) query.set('periodStart', periodStart);
  return api<SupplierRatingOverview>(`trade/supplier-ratings/overview?${query.toString()}`);
}

export function fetchSupplierRatingPolicy(
  periodType: string,
): Promise<SupplierRatingPolicy> {
  return api<SupplierRatingPolicy>(`trade/supplier-ratings/policy?periodType=${periodType}`);
}

export function saveSupplierRatingPolicy(
  form: SupplierRatingPolicyForm,
): Promise<SupplierRatingPolicy> {
  return api<SupplierRatingPolicy>('trade/supplier-ratings/policy', 'PUT', {
    periodType: form.periodType,
    deliveryWeight: Number(form.deliveryWeight),
    qualityWeight: Number(form.qualityWeight),
    responseWeight: Number(form.responseWeight),
    priceWeight: Number(form.priceWeight),
    serviceWeight: Number(form.serviceWeight),
    responseTargetHours: Number(form.responseTargetHours),
    note: form.note,
  });
}

export function fetchSupplierRatings(
  filters: SupplierRatingFilters,
): Promise<SupplierRatingPage> {
  const query = new URLSearchParams();
  if (filters.periodType) query.set('periodType', filters.periodType);
  if (filters.periodStart) query.set('periodStart', filters.periodStart);
  if (filters.supplierRefId) query.set('supplierRefId', filters.supplierRefId);
  if (filters.grade) query.set('grade', filters.grade);
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.sort) query.set('sort', filters.sort);
  query.set('desc', String(filters.desc));
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<SupplierRatingPage>(`trade/supplier-ratings/ratings?${query.toString()}`);
}

export function fetchSupplierRatingDetail(resultId: string): Promise<SupplierRatingDetail> {
  return api<SupplierRatingDetail>(`trade/supplier-ratings/ratings/${resultId}`);
}

/** 计算一个周期的评级（写动作）。 */
export function computeSupplierRatings(
  periodType: string,
  periodStart?: string,
): Promise<SupplierRatingComputeResult> {
  const query = new URLSearchParams();
  query.set('periodType', periodType);
  if (periodStart) query.set('periodStart', periodStart);
  return api<SupplierRatingComputeResult>(
    `trade/supplier-ratings/compute?${query.toString()}`,
    'POST',
  );
}

/** 人工加权分（写动作，理由必填）。 */
export function adjustSupplierRating(
  resultId: string,
  input: SupplierRatingAdjustmentInput,
): Promise<SupplierRatingRow> {
  return api<SupplierRatingRow>(
    `trade/supplier-ratings/ratings/${resultId}/adjustment`,
    'POST',
    { adjustment: Number(input.adjustment), reason: input.reason.trim() },
  );
}

export function fetchSupplierRatingChanges(
  page: number,
  size: number,
  supplierRefId?: string,
  changeType?: string,
): Promise<SupplierRatingChangePage> {
  const query = new URLSearchParams();
  query.set('page', String(page));
  query.set('size', String(size));
  if (supplierRefId) query.set('supplierRefId', supplierRefId);
  if (changeType) query.set('changeType', changeType);
  return api<SupplierRatingChangePage>(`trade/supplier-ratings/changes?${query.toString()}`);
}

/** 默认供应商优先级（供 6.2.1.2 补货建议消费）。 */
export function fetchSupplierRatingPriorities(
  periodType: string,
  periodStart?: string,
): Promise<SupplierRatingPriorityList> {
  const query = new URLSearchParams();
  query.set('periodType', periodType);
  if (periodStart) query.set('periodStart', periodStart);
  return api<SupplierRatingPriorityList>(`trade/supplier-ratings/priorities?${query.toString()}`);
}
