import { api } from '../inventory/api';

/**
 * 5.2.1.3 成本分摊前端模型。
 *
 * <p>蓝图加粗句是本模块唯一不可让步的一条：
 *
 * <blockquote>
 *
 * **分摊规则变更会导致历史利润变化**，因此调整必须留历史版本，不能就地覆盖。
 *
 * </blockquote>
 *
 * <p>⇒ 这一条在页面上有**两个**落点，少一个都不算数：
 *
 * <ol>
 *   <li><b>规则在每一条分摊明细上都看得见</b>（{@link PricingCostAllocationRow.basisRuleLabel} /
 *       {@link PricingCostAllocationRevisionRow.basisRuleLabel}）—— 运营问「这个 12.00 是怎么摊出来的」
 *       时，答案不用去别的页面找。
 *   <li><b>换规则只能「追加一版」</b>：页面上有「试算重算」（不落库，先看差异）与「应用重算」
 *       （追加新版本）两个动作，**没有**「就地改这一行」的入口。
 * </ol>
 *
 * <h2>🔴 null 绝不能渲染成 0</h2>
 *
 * <p>「基数取不到」与「确实分到 0」是两件事：一行没申报重量时，它**不参与分母**，
 * 分摊额是 0，但那个 0 的含义是「没基数可分」，不是「它不用花钱」。
 * 后端用 {@code basisAvailable=false} + {@code basisValue=null} 表达前者，
 * 前端这里用 {@link moneyText} 把它渲染成「基数不可得」而不是一个安静的 0.00。
 *
 * <h2>🔴 差额必须显示，不能藏</h2>
 *
 * <p>人工微调<b>故意</b>会让「逐行最终分摊额之和」≠「货件成本总额」。
 * 那个差额是人工决定的结果，不是错误 —— 但把它藏起来就是错账。
 * 所以 {@link varianceText} 把差额显式说出来。
 */

/** 分摊规则。与后端 {@code BasisRule} 逐字对应。 */
export type BasisRule = 'WEIGHT' | 'VOLUME' | 'AMOUNT' | 'QUANTITY';

/** 分摊来源类型。与后端 {@code SourceType} 逐字对应。 */
export type AllocationSourceType = 'FIRST_MILE_SHIPMENT' | 'MANUAL';

/** 差异类型。 */
export type DiffChange = 'ADDED' | 'REMOVED' | 'CHANGED' | 'SAME';

/** 一条分摊规则的说明。 */
export interface PricingCostAllocationRuleOption {
  value: string;
  label: string;
  basis: string;
}

/** 一种来源的说明。 */
export interface PricingCostAllocationSourceOption {
  value: string;
  label: string;
  basis: string;
}

/** 能力声明。页面顶部固定显示它。 */
export interface PricingCostAllocationCapabilities {
  rules: PricingCostAllocationRuleOption[];
  sources: PricingCostAllocationSourceOption[];
  currencies: string[];
  upstreamNote: string;
  basisNote: string;
  historyNote: string;
  downstreamNote: string;
  actionsNote: string;
}

/** 一个可分摊的货件候选（头程发运单）。 */
export interface PricingCostAllocationSourceCandidate {
  sourceRef: string;
  sourceLabel: string;
  currency: string | null;
  totalCost: string | null;
  lineCount: number;
  createdAt: string | null;
  alreadyAllocated: boolean;
}

/** 货件候选清单。 */
export interface PricingCostAllocationSourceCandidatePage {
  items: PricingCostAllocationSourceCandidate[];
  count: number;
  note: string;
  truncated: boolean;
}

/** 台账一行 = 一张分摊单（当前版本口径）。 */
export interface PricingCostAllocationRow {
  allocationId: string;
  sourceType: string;
  sourceTypeLabel: string;
  sourceRef: string;
  sourceLabel: string;
  currency: string | null;
  totalCost: string | null;
  basisRule: string | null;
  basisRuleLabel: string | null;
  revisionNo: number | null;
  revisionCount: number;
  lineCount: number;
  allocatedTotal: string | null;
  adjustmentTotal: string | null;
  finalTotal: string | null;
  variance: string | null;
  effectiveFrom: string | null;
  updatedAt: string | null;
}

/** 台账分页结果。 */
export interface PricingCostAllocationListPage {
  items: PricingCostAllocationRow[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 分摊明细的一行（一个 SKU 一行）。 */
export interface PricingCostAllocationLineRow {
  skuId: string;
  skuCode: string;
  skuName: string;
  baseQuantity: string | null;
  weightGrams: string | null;
  volumeCm3: string | null;
  amountBasis: string | null;
  basisValue: string | null;
  basisAvailable: boolean;
  basisNote: string;
  allocatedAmount: string;
  adjustmentAmount: string;
  adjustmentReason: string;
  finalAmount: string;
}

/** 版本快照的表头。 */
export interface PricingCostAllocationRevisionRow {
  revisionId: string;
  revisionNo: number;
  basisRule: string;
  basisRuleLabel: string;
  totalCost: string;
  currency: string;
  lineCount: number;
  eligibleLineCount: number;
  basisTotal: string | null;
  allocatedTotal: string;
  adjustmentTotal: string;
  finalTotal: string;
  variance: string;
  effectiveFrom: string;
  changeReason: string;
  createdAt: string;
}

/** 版本明细。 */
export interface PricingCostAllocationRevisionDetail {
  revision: PricingCostAllocationRevisionRow;
  lines: PricingCostAllocationLineRow[];
  note: string;
}

/** 差异对比的一行。 */
export interface PricingCostAllocationDiffRow {
  skuId: string;
  skuCode: string;
  skuName: string;
  change: DiffChange;
  beforeAmount: string | null;
  afterAmount: string | null;
  delta: string | null;
  note: string;
}

/** 差异对比的总体结果。 */
export interface PricingCostAllocationDiff {
  ruleChanged: boolean;
  beforeRule: string;
  afterRule: string;
  beforeTotal: string;
  afterTotal: string;
  totalDelta: string;
  changedCount: number;
  rows: PricingCostAllocationDiffRow[];
  note: string;
}

/** 分摊单明细。 */
export interface PricingCostAllocationDetail {
  allocationId: string;
  sourceType: string;
  sourceTypeLabel: string;
  sourceRef: string;
  sourceLabel: string;
  currentRevision: PricingCostAllocationRevisionRow | null;
  lines: PricingCostAllocationLineRow[];
  lastDiff: PricingCostAllocationDiff | null;
  revisions: PricingCostAllocationRevisionRow[];
  note: string;
}

/** 保存结果。 */
export interface PricingCostAllocationSaved {
  allocationId: string;
  revisionId: string;
  revisionNo: number;
  basisRule: string | null;
  totalCost: string;
  currency: string;
  lineCount: number;
  allocatedTotal: string;
  finalTotal: string;
  variance: string;
  effectiveFrom: string;
  version: number;
}

/** 试算结果（不落库）。 */
export interface PricingCostAllocationPreview {
  basisRule: string;
  basisRuleLabel: string;
  totalCost: string;
  currency: string;
  lines: PricingCostAllocationLineRow[];
  allocatedTotal: string;
  adjustmentTotal: string;
  finalTotal: string;
  variance: string;
  diff: PricingCostAllocationDiff;
  note: string;
}

/** 人工微调一行。 */
export interface PricingCostAllocationAdjustmentInput {
  skuId: string;
  amount: string;
  reason: string;
}

/** 新建分摊单的表单。 */
export interface PricingCostAllocationCreateInput {
  sourceType: AllocationSourceType;
  sourceRef: string;
  sourceLabel: string;
  currency: string;
  totalCost: string;
  basisRule: BasisRule;
  effectiveFrom: string;
  changeReason: string;
}

/** 重算的表单。 */
export interface PricingCostAllocationRecalculateInput {
  basisRule: string;
  totalCost: string;
  effectiveFrom: string;
  changeReason: string;
  adjustments: PricingCostAllocationAdjustmentInput[];
}

// ================================================================== 文案

/** 分摊规则 → 中文名。 */
export function basisRuleLabel(value: string | null | undefined): string {
  switch (value) {
    case 'WEIGHT':
      return '按重量';
    case 'VOLUME':
      return '按体积';
    case 'AMOUNT':
      return '按金额';
    case 'QUANTITY':
      return '按数量';
    default:
      return value || '未维护';
  }
}

/** 分摊规则的口径串（页面上要能看到「按重量到底是怎么算的」）。 */
export function basisRuleBasis(value: string | null | undefined): string {
  switch (value) {
    case 'WEIGHT':
      return '按各行的毛重（克）占比分摊。适合重货（抛货按重量摊才公平）。';
    case 'VOLUME':
      return '按各行的体积（立方厘米）占比分摊。适合抛货（轻泡货按体积摊才公平）。';
    case 'AMOUNT':
      return '按各行的货值占比分摊。货值高的一批摊得多。';
    case 'QUANTITY':
      return '按各行的数量占比分摊。最简单，但对重货不公平。';
    default:
      return '';
  }
}

/** 来源类型 → 中文名。 */
export function sourceTypeLabel(value: string | null | undefined): string {
  switch (value) {
    case 'FIRST_MILE_SHIPMENT':
      return '头程发运单';
    case 'MANUAL':
      return '人工货件';
    default:
      return value || '未知来源';
  }
}

/**
 * 金额 → 展示串。
 *
 * <p>🔴 null 显示「取不到」而**不是 0.00**：
 * 后端把「没基数可分 / 没这个事实」表达成 {@code available=false} + {@code amount=null}，
 * 渲染成 0.00 会让人以为这一行不用花钱。
 */
export function moneyText(value: string | null | undefined, unavailable = '取不到'): string {
  if (value === null || value === undefined || value === '') return unavailable;
  return value;
}

/** 带币种的金额串。 */
export function moneyWithCurrency(
  value: string | null | undefined,
  currency: string | null | undefined,
  unavailable = '取不到',
): string {
  const text = moneyText(value, unavailable);
  if (text === unavailable) return text;
  return currency ? `${currency} ${text}` : text;
}

/**
 * 差额 → 展示串。
 *
 * <p>🔴 差额**必须显示**：它是人工微调造成的结果，不是错误。
 * 显示成「0.00」会把人工调账藏起来；显示成错误提示又会让人以为账坏了。
 */
export function varianceText(variance: string | null | undefined): {
  tone: 'success' | 'warning';
  text: string;
} {
  if (variance === null || variance === undefined || variance === '') {
    return { tone: 'success', text: '逐行分摊额之和与货件成本总额一致。' };
  }
  const value = Number(variance);
  if (Number.isNaN(value) || value === 0) {
    return { tone: 'success', text: '逐行分摊额之和与货件成本总额一致。' };
  }
  return {
    tone: 'warning',
    text: `差额 ${variance}：这是**人工微调**造成的结果（逐行最终分摊额之和 − 货件成本总额），不是错误 —— 它反映在页面上，不该被藏起来。`,
  };
}

/** 差异类型 → 中文名。 */
export function diffChangeLabel(change: DiffChange | string | null | undefined): string {
  switch (change) {
    case 'ADDED':
      return '新增';
    case 'REMOVED':
      return '移除';
    case 'CHANGED':
      return '变化';
    case 'SAME':
      return '未变';
    default:
      return change || '未知';
  }
}

/** 差异类型的色标。 */
export function diffTone(change: DiffChange | string | null | undefined): 'success' | 'warning' | 'info' | 'neutral' {
  switch (change) {
    case 'ADDED':
      return 'info';
    case 'REMOVED':
      return 'warning';
    case 'CHANGED':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** ISO 时刻 → 只到秒的串；空值显示兜底文案。 */
export function timeText(value: string | null | undefined, empty = '—'): string {
  if (!value) return empty;
  return value.replace('T', ' ').replace('Z', 'Z');
}

/** ISO 时刻 → 只到日的串。 */
export function dayText(value: string | null | undefined, empty = '—'): string {
  if (!value) return empty;
  return value.slice(0, 10);
}

/** 基数来源说明 → 展示串（不可得时写清「为什么没有」）。 */
export function basisNoteText(line: PricingCostAllocationLineRow): string {
  if (line.basisAvailable) return line.basisNote || '基数可用';
  return line.basisNote || '基数不可得，未参与分摊';
}

// ================================================================== 本地校验

/** 新建分摊单的本地校验。前端这道校验是为了**早点告诉人**，不是安全边界。 */
export function validateCreate(input: PricingCostAllocationCreateInput): string[] {
  const problems: string[] = [];
  if (!input.sourceType) problems.push('必须选一个来源');
  if (!input.sourceRef.trim()) problems.push('来源标识必填（发运单 id 或人工货件号）');
  if (!input.currency.trim()) problems.push('计价币种必填');
  else if (!/^[A-Za-z]{3}$/.test(input.currency.trim()))
    problems.push(`计价币种请用 ISO 4217 三位字母码：${input.currency}`);
  if (!input.totalCost.trim()) problems.push('货件成本总额必填：没有总额就没有可摊的钱');
  else if (!/^\d+(\.\d+)?$/.test(input.totalCost.trim()))
    problems.push(`货件成本总额请填数字：${input.totalCost}`);
  else if (Number(input.totalCost) === 0)
    problems.push('货件成本总额不能是 0：摊出一张全 0 的单会让下游以为这个货件不用花钱');
  if (!input.basisRule) problems.push('必须选一种分摊规则：口径要明示，不能留空');
  if (!input.effectiveFrom.trim()) problems.push('生效时间必填：这一版从哪一刻开始算数');
  if (!input.changeReason.trim())
    problems.push('变更原因必填：没有理由的历史等于没有历史，半年后没人知道当时为什么这么摊');
  return problems;
}

/** 重算的本地校验。 */
export function validateRecalculate(
  input: PricingCostAllocationRecalculateInput,
  options?: { requireReason?: boolean; requireEffectiveFrom?: boolean },
): string[] {
  const problems: string[] = [];
  if (input.totalCost.trim() && !/^\d+(\.\d+)?$/.test(input.totalCost.trim()))
    problems.push(`货件成本总额请填数字：${input.totalCost}`);
  if (options?.requireEffectiveFrom && !input.effectiveFrom.trim())
    problems.push('生效时间必填：这一版从哪一刻开始算数');
  if (options?.requireReason && !input.changeReason.trim())
    problems.push('变更原因必填：换规则 / 改总额 / 人工微调，每一次都要说清为什么');
  for (const adjustment of input.adjustments) {
    if (!adjustment.amount.trim()) problems.push('人工微调金额必填');
    else if (!/^-?\d+(\.\d+)?$/.test(adjustment.amount.trim()))
      problems.push(`人工微调金额请填数字：${adjustment.amount}`);
    else if (Number(adjustment.amount) === 0)
      problems.push('人工微调金额不能为 0（金额为 0 的微调行没有信息量）');
    if (!adjustment.reason.trim())
      problems.push('人工微调原因必填：没有理由的微调会让「成本为什么突然变了」永远答不上来');
  }
  return problems;
}

// ================================================================== 端点

const BASE = 'catalog/cost-allocations';

export function fetchAllocationCapabilities(): Promise<PricingCostAllocationCapabilities> {
  return api<PricingCostAllocationCapabilities>(`${BASE}/capabilities`);
}

export function fetchAllocationSources(): Promise<PricingCostAllocationSourceCandidatePage> {
  return api<PricingCostAllocationSourceCandidatePage>(`${BASE}/sources`);
}

export interface PricingCostAllocationFilters {
  keyword: string;
  sourceType: string;
  currency: string;
  basisRule: string;
  page: number;
  size: number;
}

export const EMPTY_ALLOCATION_FILTERS: PricingCostAllocationFilters = {
  keyword: '',
  sourceType: '',
  currency: '',
  basisRule: '',
  page: 0,
  size: 20,
};

export function fetchAllocationList(
  filters: PricingCostAllocationFilters,
): Promise<PricingCostAllocationListPage> {
  const query = new URLSearchParams();
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.sourceType) query.set('sourceType', filters.sourceType);
  if (filters.currency) query.set('currency', filters.currency);
  if (filters.basisRule) query.set('basisRule', filters.basisRule);
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<PricingCostAllocationListPage>(`${BASE}?${query.toString()}`);
}

export function fetchAllocationDetail(allocationId: string): Promise<PricingCostAllocationDetail> {
  return api<PricingCostAllocationDetail>(`${BASE}/${allocationId}`);
}

export function fetchAllocationRevisions(
  allocationId: string,
): Promise<PricingCostAllocationRevisionRow[]> {
  return api<PricingCostAllocationRevisionRow[]>(`${BASE}/${allocationId}/revisions`);
}

export function fetchAllocationRevision(
  allocationId: string,
  revisionNo: number,
): Promise<PricingCostAllocationRevisionDetail> {
  return api<PricingCostAllocationRevisionDetail>(`${BASE}/${allocationId}/revisions/${revisionNo}`);
}

export function fetchAllocationDiff(
  allocationId: string,
  from?: number | null,
  to?: number | null,
): Promise<PricingCostAllocationDiff> {
  const query = new URLSearchParams();
  if (from !== null && from !== undefined) query.set('from', String(from));
  if (to !== null && to !== undefined) query.set('to', String(to));
  const suffix = query.toString();
  return api<PricingCostAllocationDiff>(`${BASE}/${allocationId}/diffs${suffix ? `?${suffix}` : ''}`);
}

/** 新建分摊单（落第一个版本）。 */
export function createAllocation(
  input: PricingCostAllocationCreateInput,
): Promise<PricingCostAllocationSaved> {
  return api<PricingCostAllocationSaved>(BASE, 'POST', {
    sourceType: input.sourceType,
    sourceRef: input.sourceRef,
    sourceLabel: input.sourceLabel,
    currency: input.currency,
    totalCost: input.totalCost,
    basisRule: input.basisRule,
    effectiveFrom: input.effectiveFrom,
    changeReason: input.changeReason,
    lines: null,
    adjustments: null,
  });
}

/** 试算重算（**不落库**）：看清差异再决定要不要落库。 */
export function previewAllocationRecalculation(
  allocationId: string,
  input: PricingCostAllocationRecalculateInput,
): Promise<PricingCostAllocationPreview> {
  return api<PricingCostAllocationPreview>(`${BASE}/${allocationId}/recalculate`, 'POST', {
    basisRule: input.basisRule || null,
    totalCost: input.totalCost || null,
    effectiveFrom: input.effectiveFrom || null,
    changeReason: input.changeReason || null,
    adjustments: input.adjustments.map((a) => ({
      skuId: a.skuId,
      amount: a.amount,
      reason: a.reason,
    })),
    lines: null,
  });
}

/** 应用重算：追加一个新版本（旧版本原样留着）。 */
export function applyAllocationRecalculation(
  allocationId: string,
  input: PricingCostAllocationRecalculateInput,
): Promise<PricingCostAllocationSaved> {
  return api<PricingCostAllocationSaved>(`${BASE}/${allocationId}/revisions`, 'POST', {
    basisRule: input.basisRule || null,
    totalCost: input.totalCost || null,
    effectiveFrom: input.effectiveFrom,
    changeReason: input.changeReason,
    adjustments: input.adjustments.map((a) => ({
      skuId: a.skuId,
      amount: a.amount,
      reason: a.reason,
    })),
    lines: null,
  });
}
