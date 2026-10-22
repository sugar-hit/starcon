import { api } from '../inventory/api';

/**
 * 16.5.1.2 风险评分 前端模型。
 *
 * 蓝图：「计算买家风险分：历史退款率、纠纷率、欺诈特征、地址模式、下单行为、关联账号，
 * 输出评分与建议动作。输入：买家行为数据；风险特征规则；权重配置。输出：风险评分 + 特征解释。
 * 关系：上游 = 15 客户、11 售后、9 订单；下游 = 16.5.1.1、9.2.2.2。
 * **评分要能解释**（为什么这个买家 80 分），黑盒分数客服无法向平台申诉。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「量不到」显示成「未知」，不显示 0 分**。标准化值与贡献分后端给 `null` 就是 `null`，
 *    这里渲染成「未知」并把「量不到的原因 + 归属模块」一起显示出来。
 * 2. **前端不重算后端算出的分数与贡献分**。分数、贡献分、理由全部原样显示后端算好的值 ——
 *    后端说 52.0833 就是 52.0833，前端不自己乘一遍权重（乘一遍就会出现两套真相）。
 * 3. **贡献分之和 = 总分**这件事由后端在数据层钉死，前端只把两者并排显示，让运营能自己核对。
 * 4. **未交付的下游不编造、不跳转**：16.5.1.1 黑名单、16.5.2.2 关联风险当前都未交付，
 *    页面上只显示能力声明里的「量不到 + 归属模块」。
 * 5. **回写失败要说清「未回写」**，不能让界面显示成「已同步」。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**）。 */
export const BASE = 'channel/buyer-risk-scores';

/** 建议动作（受控枚举，后端算好，前端只做展示映射）。 */
export const ACTIONS = [
  { value: 'RELEASE', label: '建议放行', tone: 'success' as const },
  { value: 'MANUAL_REVIEW', label: '建议人工复核', tone: 'warning' as const },
  { value: 'HOLD', label: '建议暂缓', tone: 'danger' as const },
];

/** 缺信号口径（必须由租户显式选，系统不给默认）。 */
export const MISSING_SIGNAL_POLICIES = [
  {
    value: 'SCORE_KNOWN_ONLY',
    label: '按已有维度归一化后打分',
    hint: '缺失维度不贡献，权重按比例摊回（缺失维度不进分母）',
  },
  {
    value: 'BLOCK_EVALUATION',
    label: '缺信号就不出分',
    hint: '任一维度量不到就不出分，只给「建议人工复核」',
  },
];

/** 逐维能力声明。 */
export interface BuyerRiskDimensionCapability {
  dimensionCode: string;
  dimensionLabel: string;
  featureCode: string;
  /** 该维度现在**量得到**吗（false ⇒ 评分里它不参与加权）。 */
  available: boolean;
  source: string;
  /** 量不到时归谁。 */
  ownerModule: string;
  /** 量不到的影响说明。 */
  impact: string;
}

/** 逐个上游读面的可用性。 */
export interface BuyerRiskSourceState {
  sourceCode: string;
  sourceLabel: string;
  available: boolean;
  note: string;
}

/** 能力声明。 */
export interface BuyerRiskCapabilities {
  dimensions: BuyerRiskDimensionCapability[];
  sources: BuyerRiskSourceState[];
  subjectKeySemantics: string;
  identityChoiceNote: string;
  scoreSemantics: string;
  missingSignalSemantics: string;
  explainSemantics: string;
  writebackNote: string;
  downstreamNote: string;
  boundaryNote: string;
}

/** 评分规则。 */
export interface BuyerRiskRule {
  ruleId: string | null;
  ruleName: string;
  weightRefundHistory: number | null;
  weightDisputeHistory: number | null;
  weightFraudProfile: number | null;
  weightAddressPattern: number | null;
  weightOrderBehavior: number | null;
  missingSignalPolicy: string | null;
  manualReviewThreshold: number | null;
  holdThreshold: number | null;
  refundRateFullRisk: number | null;
  fraudBlacklistedScore: number | null;
  fraudRiskLevelScore: number | null;
  fraudCaseFullRisk: number | null;
  addressSingleRatioThreshold: number | null;
  orderBurstWindowHours: number | null;
  orderBurstThreshold: number | null;
  orderFrequencyFullRisk: number | null;
  orderLookbackDays: number | null;
  version: number;
  /** 后端没登记过规则时是 false（**后端不预填任何默认权重**）。 */
  configured: boolean;
  createdAt: string | null;
  updatedAt: string | null;
}

/** 规则登记入参。 */
export interface BuyerRiskRuleInput {
  ruleName: string;
  weightRefundHistory: number;
  weightDisputeHistory: number;
  weightFraudProfile: number;
  weightAddressPattern: number;
  weightOrderBehavior: number;
  missingSignalPolicy: string;
  manualReviewThreshold: number;
  holdThreshold: number;
  refundRateFullRisk: number;
  fraudBlacklistedScore: number;
  fraudRiskLevelScore: number;
  fraudCaseFullRisk: number;
  addressSingleRatioThreshold: number;
  orderBurstWindowHours: number;
  orderBurstThreshold: number;
  orderFrequencyFullRisk: number;
  orderLookbackDays: number;
  version: number | null;
}

/** 一条特征的贡献明细。 */
export interface BuyerRiskFeature {
  featureId: string | null;
  dimensionCode: string;
  dimensionLabel: string;
  featureCode: string;
  featureLabel: string;
  /** 量到了没有（false ⇒ 标准化值与贡献分都是「未知」）。 */
  available: boolean;
  rawValue: string;
  /** 标准化值 0..1；量不到是 null。 */
  normalizedValue: number | null;
  /** 权重快照。 */
  weight: number;
  /** 对总分的贡献分；量不到 / 整次没出分是 null。 */
  contribution: number | null;
  /** 人话理由（后端算好的）。 */
  reason: string;
  /** 量不到时归谁。 */
  ownerModule: string;
  note: string;
}

/** 回写 9.2.2.2 风控信号的结果。 */
export interface BuyerRiskWriteback {
  featureCode: string;
  signalCode: string;
  subjectKey: string;
  measuredValue: number | null;
  writebackStatus: string;
  writebackNote: string;
  attemptedAt: string | null;
}

/** 一次评分。 */
export interface BuyerRiskScore {
  scoreId: string;
  buyerKey: string;
  buyerName: string;
  buyerPhone: string;
  buyerEmail: string;
  buyerLevel: string;
  blacklisted: boolean;
  blacklistReason: string;
  /** 总分 0..100；**信号不足时是 null**（不是 0）。 */
  riskScore: number | null;
  evaluationStatus: string;
  recommendedAction: string;
  scoredDimensionCount: number;
  missingDimensionCount: number;
  ruleId: string;
  ruleVersion: number;
  ruleName: string;
  missingSignalPolicy: string;
  evaluatedReferenceAt: string;
  ordersScanned: number;
  orderCount: number;
  refundedOrderCount: number;
  lookbackDays: number;
  conclusion: string;
  note: string;
  createdAt: string;
  features: BuyerRiskFeature[];
  writebacks: BuyerRiskWriteback[];
}

/** 评分列表行。 */
export interface BuyerRiskScoreSummary {
  scoreId: string;
  buyerKey: string;
  buyerName: string;
  buyerEmail: string;
  buyerLevel: string;
  riskScore: number | null;
  evaluationStatus: string;
  recommendedAction: string;
  scoredDimensionCount: number;
  missingDimensionCount: number;
  orderCount: number;
  refundedOrderCount: number;
  evaluatedReferenceAt: string;
  createdAt: string;
}

/** 评分列表。 */
export interface BuyerRiskScorePage {
  items: BuyerRiskScoreSummary[];
  page: number;
  size: number;
  total: number;
  counts: {
    total: number;
    scored: number;
    insufficientSignals: number;
    release: number;
    manualReview: number;
    hold: number;
  };
}

/** 买家名册行。 */
export interface BuyerRiskBuyer {
  buyerKey: string;
  buyerName: string;
  buyerPhone: string;
  buyerEmail: string;
  buyerLevel: string;
  blacklisted: boolean;
  blacklistReason: string;
  latestScoreId: string | null;
  latestRiskScore: number | null;
  latestEvaluationStatus: string | null;
  latestRecommendedAction: string | null;
}

/** 买家名册。 */
export interface BuyerRiskBuyerPage {
  available: boolean;
  note: string;
  items: BuyerRiskBuyer[];
  page: number;
  size: number;
  total: number;
}

/** 一次评分运行的结果。 */
export interface BuyerRiskEvaluateResult {
  evaluatedAt: string;
  ruleName: string;
  ruleVersion: number;
  missingSignalPolicy: string;
  sources: BuyerRiskSourceState[];
  ordersScanned: number;
  ordersUnresolved: number;
  buyerCount: number;
  scoredCount: number;
  insufficientCount: number;
  scores: BuyerRiskScore[];
}

// ================================================================ 取数

export async function fetchCapabilities(): Promise<BuyerRiskCapabilities> {
  return api<BuyerRiskCapabilities>(`${BASE}/capabilities`);
}

export async function fetchRule(): Promise<BuyerRiskRule> {
  return api<BuyerRiskRule>(`${BASE}/rule`);
}

export async function saveRule(input: BuyerRiskRuleInput): Promise<BuyerRiskRule> {
  return api<BuyerRiskRule>(`${BASE}/rule`, 'PUT', input);
}

export async function fetchBuyers(
  keyword = '',
  page = 0,
  size = 20,
): Promise<BuyerRiskBuyerPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (keyword.trim()) query.set('keyword', keyword.trim());
  return api<BuyerRiskBuyerPage>(`${BASE}/buyers?${query}`);
}

export async function fetchScores(
  page = 0,
  size = 20,
  buyerKey = '',
): Promise<BuyerRiskScorePage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (buyerKey.trim()) query.set('buyerKey', buyerKey.trim());
  return api<BuyerRiskScorePage>(`${BASE}/scores?${query}`);
}

export async function fetchScore(scoreId: string): Promise<BuyerRiskScore> {
  return api<BuyerRiskScore>(`${BASE}/scores/${scoreId}`);
}

/** 跑一次评分。buyerKey 留空 ⇒ 评「画像买家 ∪ 订单里发现的买家」。 */
export async function evaluate(buyerKey = ''): Promise<BuyerRiskEvaluateResult> {
  return api<BuyerRiskEvaluateResult>(`${BASE}/evaluate`, 'POST', { buyerKey: buyerKey.trim() });
}

// ================================================================ 展示映射（纯函数）

/** 分数展示：**null 显示「未知」，绝不显示 0**（0 会被读成「这个买家很干净」）。 */
export function scoreText(score: number | null): string {
  if (score === null || score === undefined) return '未知';
  return score.toFixed(2);
}

/** 标准化值展示：null ⇒ 「未知」。 */
export function normalizedText(value: number | null): string {
  if (value === null || value === undefined) return '未知';
  return `${(value * 100).toFixed(2)}%`;
}

/** 贡献分展示：null ⇒ 「—」（没有参与计分）。 */
export function contributionText(value: number | null): string {
  if (value === null || value === undefined) return '—';
  return value.toFixed(4);
}

/** 权重展示。 */
export function weightText(weight: number | null): string {
  if (weight === null || weight === undefined) return '—';
  return weight.toFixed(4);
}

/** 建议动作的中文标签。 */
export function actionLabel(action: string): string {
  return ACTIONS.find((a) => a.value === action)?.label ?? action;
}

/** 建议动作的徽章色调。 */
export function actionTone(action: string): 'success' | 'warning' | 'danger' | 'neutral' {
  return ACTIONS.find((a) => a.value === action)?.tone ?? 'neutral';
}

/** 判定状态的标签。 */
export function statusLabel(status: string): string {
  if (status === 'SCORED') return '已出分';
  if (status === 'INSUFFICIENT_SIGNALS') return '信号不足';
  return status;
}

/** 量不到的维度（**它们也在清单里，不是悄悄消失**）。 */
export function unknownFeatures(score: BuyerRiskScore | null): BuyerRiskFeature[] {
  if (!score) return [];
  return score.features.filter((f) => !f.available);
}

/** 参与计分的维度。 */
export function scoredFeatures(score: BuyerRiskScore | null): BuyerRiskFeature[] {
  if (!score) return [];
  return score.features.filter((f) => f.available);
}

/** 权重合计（表单实时提示用；后端会再查一次）。 */
export function weightSum(weights: number[]): number {
  return weights.reduce((sum, w) => sum + (Number.isFinite(w) ? w : 0), 0);
}

/** 权重是否配平（合计恰好 1.0000）。 */
export function weightsBalanced(weights: number[]): boolean {
  return Math.abs(weightSum(weights) - 1) < 1e-9;
}

/** 回写状态的中文标签。 */
export function writebackLabel(status: string): string {
  if (status === 'SUCCEEDED') return '已回写';
  if (status === 'FAILED') return '未回写';
  if (status === 'SKIPPED') return '跳过（无信号值）';
  return status;
}

/** 三态文案各不相同（不是同一个空态换个图标）。 */
export function emptyState(
  kind: 'NO_RULE' | 'NO_BUYER' | 'NO_SCORE' | 'ALL_UNKNOWN',
): { title: string; description: string } {
  if (kind === 'NO_RULE') {
    return {
      title: '还没有登记评分规则',
      description:
        '权重与缺信号口径必须由租户显式选 —— 系统不给默认值。登记之后才能跑评分。',
    };
  }
  if (kind === 'NO_BUYER') {
    return {
      title: '上游读不到买家名册',
      description:
        '本页的买家来自 9.2.1.2 的租户自维护画像。读不到时这里显示「读不到」而不是「这个租户没有买家」。',
    };
  }
  if (kind === 'NO_SCORE') {
    return {
      title: '还没有跑过评分',
      description:
        '评分是真去上游读订单、读退款、读售后单算出来的 —— 不会凭空造一个分数。挑一个买家点「评分」试试。',
    };
  }
  return {
    title: '这次评分一个维度都没量到',
    description:
      '没有分数不是「零风险」。所有维度都量不到时，结论只能是「信号不足，无法评分」，请按各维度写明的原因补齐上游。',
  };
}
