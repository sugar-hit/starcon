import { api } from '../inventory/api';

/**
 * 10.1.2.2 渠道比价前端模型。
 *
 * 蓝图：「在满足时效与限制的渠道中按成本/时效/成功率多目标排序，支持按店铺预设优先级自动选择。
 * 输入：比价条件；渠道评分（历史成功率、时效达成率、投诉率）。输出：排序后的推荐渠道 + 评分明细。」
 *
 * 🔴 这个页面上最容易做成假活的两件事，这里都挡住了：
 *
 * 1. **得分不在浏览器里算**。页面把比价条件发给 `/channel/rate-comparison/compare`，
 *    拿回服务端算好的三维得分、综合分与推荐。没有任何一处在前端做归一化或加权 ——
 *    前端算一遍就意味着「界面上的名次」和「落库的名次」会有两个算法。
 * 2. **不可比不是 0 分**。`costScore === null` 表示「压根没资格比」，与 `0` 分
 *    （比过了、确实最差）含义完全相反；界面上两者必须分开显示。同一栏位混在一起
 *    会让人以为「没登记时效的那条被打了 0 分」。
 *
 * 🔴 三个闸（投诉率红线 / 最小样本量 / 评分新鲜度）**只挡自动选择，不挡评分**：
 * 被闸拦下的渠道照样出现在对照表里并给出完整得分 —— 从表里删掉它会让人以为
 * 「它不存在」，而它明明最便宜，运营就再也查不出「为什么这条没被选」。
 *
 * 🔴 金额与得分在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * 运算是服务端 BigDecimal 的事，前端算一遍就会引入二进制浮点误差。
 */

/** 金额一律 number（JSON number），只原样展示。 */
export type CompareMoney = number | null;

/** 三维得分（0~100）。null = 不可比，与 0 分含义相反。 */
export type CompareScore = number | null;

export interface RateCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface RateComparisonCapabilities {
  dimensions: RateCapabilityDimension[];
  selectionBases: string[];
  rankBases: string[];
  scoreSources: string[];
  eligibilityCodes: string[];
  weightingNote: string;
  gatingNote: string;
  estimationNote: string;
  dataNote: string;
}

export interface ChannelRatingRow {
  id: string;
  channelId: string;
  channelCode: string;
  channelName: string;
  carrierName: string;
  countryCode: string;
  dispatched: number;
  delivered: number;
  onTime: number;
  complaint: number;
  deliverySuccessRate: number | null;
  onTimeRate: number | null;
  complaintRate: number | null;
  windowFrom: string;
  windowTo: string;
  observedAt: string;
  scoreSource: string;
  sourceRef: string;
  maxComplaintRatePct: number;
  minSampleThreshold: number;
  status: string;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface RatingOverview {
  ratings: ChannelRatingRow[];
  activeCount: number;
  manualCount: number;
  /** 没有任何评分的渠道：比价时它们会以「没有评分」落选，这里提前告诉运营。 */
  unratedChannelCodes: string[];
  note: string;
}

export interface ShopPriorityRow {
  id: string;
  shopCode: string;
  channelId: string;
  channelCode: string;
  channelName: string;
  carrierName: string;
  priorityNo: number;
  autoSelectAllowed: boolean;
  minCompositeScore: number;
  status: string;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface ShopPriorityOverview {
  shopCode: string;
  priorities: ShopPriorityRow[];
  activeCount: number;
  note: string;
}

export interface ComparisonLine {
  channelId: string;
  channelCode: string;
  channelName: string;
  carrierName: string;
  eligibility: string;
  totalCharge: CompareMoney;
  currency: string;
  transitMinDays: number | null;
  transitMaxDays: number | null;
  transitBasisDays: number | null;
  /** false = 上游 10.1.2.1 算不出钱，**不是 0 元**。 */
  priced: boolean;
  costScore: CompareScore;
  transitScore: CompareScore;
  reliabilityScore: CompareScore;
  compositeScore: CompareScore;
  rankNo: number | null;
  rankBasis: string;
  exclusionReason: string;
  ratingId: string | null;
  ratingWindowFrom: string | null;
  ratingWindowTo: string | null;
  ratingObservedAt: string | null;
  ratingDispatched: number | null;
  ratingDelivered: number | null;
  ratingOnTime: number | null;
  ratingComplaint: number | null;
  ratingSuccessRate: number | null;
  ratingOnTimeRate: number | null;
  ratingComplaintRate: number | null;
  ratingSource: string | null;
  // ---- 三个闸：只挡自动选择，不挡评分 ----
  riskFlagged: boolean;
  riskNote: string;
  sampleSufficient: boolean;
  sampleNote: string;
  ratingStale: boolean;
  staleNote: string;
  priorityNo: number | null;
  autoSelectAllowed: boolean;
  minCompositeScore: number | null;
  selected: boolean;
  selectionNote: string;
  scoreBreakdown: string[];
  notes: string[];
}

export interface CompareResult {
  /** 恒为 RULE_ESTIMATE：比价成本来自 10.1.2.1 的试算，不是承运商账单。 */
  costBasis: string;
  sourceQuoteId: string | null;
  comparedAt: string;
  destCountry: string;
  shopCode: string;
  weightKg: number;
  pieces: number;
  currency: string;
  mixedCurrency: boolean;
  cheapestTotal: CompareMoney;
  dearestTotal: CompareMoney;
  candidateCount: number;
  rankedCount: number;
  riskFlaggedCount: number;
  insufficientSampleCount: number;
  staleRatingCount: number;
  costWeight: number;
  transitWeight: number;
  reliabilityWeight: number;
  successRateWeight: number;
  onTimeRateWeight: number;
  complaintRateWeight: number;
  maxTransitDays: number | null;
  maxRatingAgeDays: number;
  requirePassOnly: boolean;
  applyShopPriority: boolean;
  minCompositeScore: number | null;
  selectedChannelId: string | null;
  selectedChannelCode: string;
  selectedChannelName: string;
  selectionBasis: string;
  selectionNote: string;
  savedComparisonId: string | null;
  lines: ComparisonLine[];
  note: string;
}

export interface ComparisonRow {
  id: string;
  costBasis: string;
  sourceQuoteId: string | null;
  destCountry: string;
  shopCode: string;
  weightKg: number;
  pieces: number;
  comparedAt: string;
  costWeight: number;
  transitWeight: number;
  reliabilityWeight: number;
  maxTransitDays: number | null;
  maxRatingAgeDays: number;
  requirePassOnly: boolean;
  applyShopPriority: boolean;
  candidateCount: number;
  rankedCount: number;
  riskFlaggedCount: number;
  insufficientSampleCount: number;
  staleRatingCount: number;
  currency: string;
  mixedCurrency: boolean;
  cheapestTotal: CompareMoney;
  dearestTotal: CompareMoney;
  selectedChannelId: string | null;
  selectedChannelCode: string;
  selectionBasis: string;
  selectionNote: string;
  note: string;
  createdAt: string;
}

export interface ComparisonDetail {
  comparison: ComparisonRow;
  lines: ComparisonLine[];
}

export interface ComparisonSummary {
  total: number;
  withSelection: number;
  noSelection: number;
  shopPreset: number;
  riskFlagged: number;
}

export interface ComparisonPage {
  items: ComparisonRow[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
  summary: ComparisonSummary;
}

/** 比价条件表单（全是字符串，提交时才转数字）。 */
export interface CompareInput {
  destCountry: string;
  shopCode: string;
  weightKg: string;
  declaredValue: string;
  declaredCurrency: string;
  maxTransitDays: string;
  costWeight: string;
  transitWeight: string;
  reliabilityWeight: string;
  maxRatingAgeDays: string;
  applyShopPriority: boolean;
  requirePassOnly: boolean;
  minCompositeScore: string;
  comparedAt: string;
  note: string;
}

export const emptyCompareInput: CompareInput = {
  destCountry: 'DE',
  shopCode: '',
  weightKg: '1.5',
  declaredValue: '',
  declaredCurrency: '',
  maxTransitDays: '',
  costWeight: '0.4',
  transitWeight: '0.3',
  reliabilityWeight: '0.3',
  maxRatingAgeDays: '90',
  applyShopPriority: false,
  requirePassOnly: false,
  minCompositeScore: '',
  comparedAt: '',
  note: '',
};

/** 登记渠道评分的表单。 */
export interface RatingInput {
  channelId: string;
  channelCode: string;
  countryCode: string;
  dispatched: string;
  delivered: string;
  onTime: string;
  complaint: string;
  windowFrom: string;
  windowTo: string;
  observedAt: string;
  scoreSource: string;
  sourceRef: string;
  maxComplaintRatePct: string;
  minSampleThreshold: string;
  note: string;
}

export const emptyRatingInput: RatingInput = {
  channelId: '',
  channelCode: '',
  countryCode: 'DE',
  dispatched: '',
  delivered: '',
  onTime: '',
  complaint: '0',
  windowFrom: '',
  windowTo: '',
  observedAt: '',
  scoreSource: 'MANUAL_ENTERED',
  sourceRef: '',
  maxComplaintRatePct: '5',
  minSampleThreshold: '30',
  note: '',
};

// ==================================================================
// 接口调用
// ==================================================================

export function fetchRateCapabilities(): Promise<RateComparisonCapabilities> {
  return api<RateComparisonCapabilities>('channel/rate-comparison/capabilities');
}

/** 比价（无副作用）。得分与推荐全部来自服务端，页面不做运算。 */
export function compareChannels(input: CompareInput): Promise<CompareResult> {
  return api<CompareResult>('channel/rate-comparison/compare', 'POST', toCompareRequest(input));
}

/** 比价并落库（下游 9.3.2.1 分仓、18 自动化规则引用的是它）。 */
export function saveComparison(input: CompareInput): Promise<CompareResult> {
  return api<CompareResult>('channel/rate-comparison/comparisons', 'POST', {
    request: toCompareRequest(input),
  });
}

export function fetchComparisons(params: {
  destCountry?: string;
  shopCode?: string;
  selectionBasis?: string;
  sort?: string;
  page?: number;
  size?: number;
}): Promise<ComparisonPage> {
  const query = new URLSearchParams();
  if (params.destCountry) query.set('destCountry', params.destCountry);
  if (params.shopCode) query.set('shopCode', params.shopCode);
  if (params.selectionBasis) query.set('selectionBasis', params.selectionBasis);
  query.set('sort', params.sort ?? 'TIME_DESC');
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<ComparisonPage>(`channel/rate-comparison/comparisons?${query.toString()}`);
}

export function fetchComparison(id: string): Promise<ComparisonDetail> {
  return api<ComparisonDetail>(`channel/rate-comparison/comparisons/${id}`);
}

export function fetchRatings(params: {
  channelId?: string;
  countryCode?: string;
  status?: string;
  sort?: string;
}): Promise<RatingOverview> {
  const query = new URLSearchParams();
  if (params.channelId) query.set('channelId', params.channelId);
  if (params.countryCode) query.set('countryCode', params.countryCode);
  if (params.status) query.set('status', params.status);
  query.set('sort', params.sort ?? 'CHANNEL');
  return api<RatingOverview>(`channel/rate-comparison/ratings?${query.toString()}`);
}

export function saveRating(input: RatingInput): Promise<ChannelRatingRow> {
  return api<ChannelRatingRow>('channel/rate-comparison/ratings', 'POST', {
    channelId: input.channelId,
    countryCode: input.countryCode.trim().toUpperCase(),
    dispatched: Number(input.dispatched),
    delivered: Number(input.delivered),
    onTime: Number(input.onTime),
    complaint: Number(input.complaint),
    windowFrom: toInstant(input.windowFrom),
    windowTo: toInstant(input.windowTo),
    observedAt: toInstant(input.observedAt),
    scoreSource: input.scoreSource,
    sourceRef: input.sourceRef.trim(),
    maxComplaintRatePct: Number(input.maxComplaintRatePct),
    minSampleThreshold: Number(input.minSampleThreshold),
    status: 'ACTIVE',
    note: input.note.trim(),
    version: 0,
  });
}

export function fetchShopPriorities(params: { shopCode?: string; status?: string }): Promise<ShopPriorityOverview> {
  const query = new URLSearchParams();
  if (params.shopCode) query.set('shopCode', params.shopCode);
  if (params.status) query.set('status', params.status);
  return api<ShopPriorityOverview>(
    `channel/rate-comparison/shop-priorities${query.size ? `?${query.toString()}` : ''}`,
  );
}

export function saveShopPriority(input: {
  shopCode: string;
  channelId: string;
  channelCode: string;
  priorityNo: string;
  autoSelectAllowed: boolean;
  minCompositeScore: string;
  note: string;
}): Promise<ShopPriorityRow> {
  return api<ShopPriorityRow>('channel/rate-comparison/shop-priorities', 'POST', {
    shopCode: input.shopCode.trim().toUpperCase(),
    channelId: input.channelId,
    priorityNo: Number(input.priorityNo),
    autoSelectAllowed: input.autoSelectAllowed,
    minCompositeScore: Number(input.minCompositeScore),
    status: 'ACTIVE',
    note: input.note.trim(),
    version: 0,
  });
}

/**
 * `datetime-local` 的本地时间串 → 带时区的 ISO-8601。
 *
 * 🔴 不做这一步等于把一个**无时区**的字符串发给后端：同一份评分在不同机器上会被
 * 解释成不同时刻，「新鲜度」这个闸会得出不同结论。
 */
export function toInstant(local: string): string | null {
  const trimmed = local.trim();
  if (!trimmed) return null;
  const parsed = new Date(trimmed);
  if (Number.isNaN(parsed.getTime())) return null;
  return parsed.toISOString();
}

function toCompareRequest(input: CompareInput) {
  return {
    parcel: {
      destCountry: input.destCountry.trim().toUpperCase(),
      shopCode: input.shopCode.trim().toUpperCase(),
      weightKg: Number(input.weightKg),
      pieces: 1,
      categoryCodes: [],
      containsBattery: false,
      containsLiquid: false,
      containsMagnet: false,
      quotedAt: toInstant(input.comparedAt),
      declaredValue: input.declaredValue.trim() ? Number(input.declaredValue) : null,
      declaredCurrency: input.declaredCurrency.trim().toUpperCase() || null,
      note: '',
    },
    maxTransitDays: input.maxTransitDays.trim() ? Number(input.maxTransitDays) : null,
    costWeight: input.costWeight.trim() ? Number(input.costWeight) : null,
    transitWeight: input.transitWeight.trim() ? Number(input.transitWeight) : null,
    reliabilityWeight: input.reliabilityWeight.trim() ? Number(input.reliabilityWeight) : null,
    maxRatingAgeDays: input.maxRatingAgeDays.trim() ? Number(input.maxRatingAgeDays) : null,
    applyShopPriority: input.applyShopPriority,
    requirePassOnly: input.requirePassOnly,
    minCompositeScore: input.minCompositeScore.trim() ? Number(input.minCompositeScore) : null,
    comparedAt: toInstant(input.comparedAt),
    note: input.note.trim(),
  };
}

// ==================================================================
// 校验
// ==================================================================

export interface CompareValidation {
  ok: boolean;
  errors: string[];
}

/**
 * 提交前校验。
 *
 * 这里的每一条都对应服务端会拒的场景；前端提前说清楚，是为了让人在表单旁边就知道
 * 哪里不对，而不是提交后收到一个 400。
 */
export function validateCompare(input: CompareInput): CompareValidation {
  const errors: string[] = [];
  if (!/^[A-Za-z]{2}$/.test(input.destCountry.trim())) {
    errors.push('收货国家是两位国家代码（如 DE / US）');
  }
  const weight = Number(input.weightKg);
  if (!input.weightKg.trim() || Number.isNaN(weight) || weight < 0) {
    errors.push('计费重量必须是不小于 0 的数字（单位 kg）');
  }
  if (input.declaredValue.trim() && Number(input.declaredValue) < 0) {
    errors.push('申报价值不能是负数');
  }
  if (input.declaredCurrency.trim() && !/^[A-Za-z]{3}$/.test(input.declaredCurrency.trim())) {
    errors.push('申报币种是三位币种代码（如 USD / EUR）');
  }
  if (input.maxTransitDays.trim() && Number(input.maxTransitDays) < 0) {
    errors.push('时效上限不能是负数');
  }
  const weights = ['costWeight', 'transitWeight', 'reliabilityWeight'] as const;
  let sum = 0;
  let filled = 0;
  weights.forEach((key) => {
    const raw = input[key].trim();
    if (!raw) return;
    const value = Number(raw);
    if (Number.isNaN(value) || value < 0) {
      errors.push(`权重必须是不小于 0 的数字（${labelOfWeight(key)}）`);
      return;
    }
    sum += value;
    filled += 1;
  });
  // 🔴 三个都留空 = 用服务端默认值（合法）；填了却加起来为 0 = 没选目标（拒）。
  //   这两种在界面上长得几乎一样，所以判据是「有没有填」，不是「和是不是 0」。
  if (filled > 0 && sum <= 0) {
    errors.push('成本 / 时效 / 可靠性三个权重不能同时为 0 —— 那不叫「都不重要」，那叫没选目标');
  }
  if (input.maxRatingAgeDays.trim() && Number(input.maxRatingAgeDays) < 0) {
    errors.push('评分新鲜度上限不能是负数');
  }
  if (input.minCompositeScore.trim() && Number(input.minCompositeScore) < 0) {
    errors.push('最低可接受综合分不能是负数');
  }
  return { ok: errors.length === 0, errors };
}

/** 登记评分的表单校验（与服务层的拒收条件一一对应）。 */
export function validateRating(input: RatingInput): CompareValidation {
  const errors: string[] = [];
  if (!input.channelId.trim()) {
    errors.push('请选择要登记评分的渠道');
  }
  if (!/^[A-Za-z]{2}$/.test(input.countryCode.trim())) {
    errors.push('国家是两位国家代码（如 DE / US）');
  }
  const dispatched = Number(input.dispatched);
  const delivered = Number(input.delivered);
  const onTime = Number(input.onTime);
  const complaint = Number(input.complaint);
  if (!input.dispatched.trim() || Number.isNaN(dispatched) || dispatched < 1) {
    errors.push('已发运样本量必须 ≥ 1 —— 0 单算不出任何比率');
  }
  if (Number.isNaN(delivered) || delivered < 0 || delivered > dispatched) {
    errors.push('已妥投数必须在 0 与已发运数之间');
  }
  if (Number.isNaN(onTime) || onTime < 0 || onTime > delivered) {
    errors.push('按时妥投数不能大于已妥投数 —— 时效达成率的分母是已妥投单');
  }
  if (Number.isNaN(complaint) || complaint < 0 || complaint > dispatched) {
    errors.push('投诉数不能大于已发运数');
  }
  if (!toInstant(input.windowFrom) || !toInstant(input.windowTo)) {
    errors.push('统计窗口的开始与结束时刻都要填');
  } else if (new Date(toInstant(input.windowTo) as string) <= new Date(toInstant(input.windowFrom) as string)) {
    errors.push('统计窗口的结束时刻必须晚于开始时刻');
  }
  if (!toInstant(input.observedAt)) {
    errors.push('观测时刻必填 —— 不存它就答不出「这组比率是哪一段的」');
  }
  const redLine = Number(input.maxComplaintRatePct);
  if (Number.isNaN(redLine) || redLine < 0 || redLine > 100) {
    errors.push('投诉率红线是 0~100 之间的百分比');
  }
  const minSample = Number(input.minSampleThreshold);
  if (Number.isNaN(minSample) || minSample < 1) {
    errors.push('最小样本量必须 ≥ 1');
  }
  return { ok: errors.length === 0, errors };
}

function labelOfWeight(key: 'costWeight' | 'transitWeight' | 'reliabilityWeight'): string {
  if (key === 'costWeight') return '成本';
  if (key === 'transitWeight') return '时效';
  return '可靠性';
}

// ==================================================================
// 展示辅助
// ==================================================================

export function moneyText(value: CompareMoney, currency?: string): string {
  if (value === null || value === undefined) return '—';
  const fixed = value.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

/**
 * 得分展示：**不可比显示「不可比」，不是 0.00**。
 *
 * 🔴 这一栏最容易骗人：0 分是「比过了，确实最差」，null 是「压根没资格比」。
 * 渲染成 0.00 会让人以为「没登记时效的那条被打了 0 分」。
 */
export function scoreText(score: CompareScore): string {
  if (score === null || score === undefined) return '不可比';
  return score.toFixed(2);
}

export function scoreTone(score: CompareScore): string {
  if (score === null || score === undefined) return 'text-muted-foreground';
  if (score >= 80) return 'text-emerald-600';
  if (score >= 50) return 'text-foreground';
  return 'text-muted-foreground';
}

export function rateText(rate: number | null): string {
  if (rate === null || rate === undefined) return '—';
  return `${(rate * 100).toFixed(2)}%`;
}

export function eligibilityLabel(code: string): string {
  if (code === 'PASS') return '可承运';
  if (code === 'REVIEW') return '需人工确认';
  if (code === 'BLOCK') return '硬拦截';
  return code;
}

export function rankBasisLabel(basis: string): string {
  if (basis === 'RANKED') return '参与排名';
  if (basis === 'EXCLUDED_INELIGIBLE') return '不参与：可承运性未过';
  if (basis === 'EXCLUDED_TRANSIT') return '不参与：超出时效要求';
  if (basis === 'EXCLUDED_CURRENCY') return '不参与：币种不同';
  if (basis === 'EXCLUDED_NO_PRICE') return '不参与：算不出运费';
  if (basis === 'EXCLUDED_NO_TRANSIT') return '不参与：未登记时效';
  if (basis === 'EXCLUDED_NO_SCORE') return '不参与：没有渠道评分';
  return basis;
}

export function selectionBasisLabel(basis: string): string {
  if (basis === 'SCORE_RANKED') return '按综合分排名自动选择';
  if (basis === 'SHOP_PRESET_PRIORITY') return '按店铺预设优先级自动选择';
  if (basis === 'NO_ELIGIBLE_CHANNEL') return '无可选渠道';
  return basis;
}

export function scoreSourceLabel(source: string | null): string {
  if (source === 'MANUAL_ENTERED') return '人工登记';
  if (source === 'TRANSIT_ANALYSIS') return '10.6.2.1 时效分析回灌（未交付）';
  if (source === 'HANDOVER_RECEIPT') return '10.3.2.1 交运回执汇总';
  if (source === 'CARRIER_REPORT') return '承运商考核报告';
  return source ?? '—';
}

export function transitText(min: number | null, max: number | null): string {
  if (min === null && max === null) return '—';
  if (min !== null && max !== null && min === max) return `${min} 天`;
  if (max !== null) return `${min ?? '?'}–${max} 天`;
  return `${min} 天`;
}

/**
 * 一条渠道被挡在自动选择之外的全部理由（逐条，不是拼成一团）。
 *
 * 🔴 「可承运但没被选中」和「压根不参与排名」在界面上必须是两种不同的呈现：
 * 前者要解释「它为什么便宜却没被选」，后者要解释「它为什么没资格比」。
 */
export function blockingReasons(line: ComparisonLine): string[] {
  const reasons: string[] = [];
  if (line.riskFlagged && line.riskNote) reasons.push(`投诉率红线：${line.riskNote}`);
  if (!line.sampleSufficient && line.sampleNote) reasons.push(`样本量：${line.sampleNote}`);
  if (line.ratingStale && line.staleNote) reasons.push(`评分新鲜度：${line.staleNote}`);
  return reasons;
}

/** 结果为空时的说明：**区分「没有候选渠道」与「有渠道但一条都不合格」**。 */
export function emptyResultText(result: CompareResult | null): string {
  if (!result) return '';
  if (result.lines.length === 0) {
    return (
      '本租户没有匹配这些条件的渠道：渠道要么没启用、要么适用范围不含目的国 / 店铺。' +
      '空结果**不代表**可以随便选一条 —— 它代表「这些条件下没有候选渠道」。'
    );
  }
  if (result.selectionBasis === 'NO_ELIGIBLE_CHANNEL') {
    return (
      '没有任何渠道通过自动选择的闸，本次比价**不给推荐**而不是随便挑一条。' +
      '逐条原因见对照表每一行的「落选原因」与「未自动选中」。'
    );
  }
  return '';
}
