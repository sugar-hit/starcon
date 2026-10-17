import type {
  BuyerProfile,
  BuyerProfileInput,
  BuyerProfilePage,
  EvaluationPage,
  HitCondition,
  OrderReviewRuleCapabilities,
  ReviewDecision,
  ReviewDetail,
  ReviewRuleCondition,
  ReviewRuleConditionInput,
  ReviewRuleDetail,
  ReviewRuleInput,
  ReviewRulePage,
  ReviewRuleRow,
  ReviewRuleSaved,
  ReviewRuleStatusInput,
  ReviewRunInput,
  RuleHit,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 9.2.1.2 审单规则前端模型。
 *
 * <p>蓝图：「配置自动审单规则：按店铺/金额/买家等级/商品/收货国家自动通过或转人工；
 * 命中黑名单、地址异常、大额、异常折扣的强制转人工。规则有优先级与生效期。」
 * 输出是「规则命中结果（每单命中的规则链）；规则生效记录」。
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>未命中的规则与条件也要显示。</b>蓝图加粗句说「客服被买家投诉时能说出『为什么这单
 *       被拦了』」—— 只列命中项的话，客服被追问「为什么那条规则没兜住」时答不上来，
 *       而那恰恰是第二个会被问到的问题。后端把两种都返回了，这里原样展示、不做过滤。</li>
 *   <li><b>「取不到值」必须显示成「未知」而不是一个默认值。</b>买家没有画像行时，
 *       {@code buyerLevel} 是 {@code null}。显示成「STANDARD」会让运营以为
 *       系统给这个买家定了级，而实际是「没登记」。</li>
 *   <li><b>折扣率不可计算要说不可计算。</b>订单小计为 0 时折扣比例是 {@code null}，
 *       显示成 0 会被读成「零元订单打了 0 折」—— 那是结论，不是缺口。</li>
 *   <li><b>试跑与生效判定要一眼分得开。</b>试跑结论不能被当成生产结论读。</li>
 * </ul>
 */

// ------------------------------------------------------------ 参数

export interface ReviewRuleParams {
  keyword?: string;
  status?: string;
  action?: string;
  forcedOnly?: boolean;
  dimension?: string;
  effectiveOnly?: boolean;
  page?: number;
  size?: number;
}

export interface EvaluationParams {
  keyword?: string;
  orderId?: string;
  decision?: string;
  mode?: string;
  forcedOnly?: boolean;
  page?: number;
  size?: number;
}

export interface BuyerProfileParams {
  keyword?: string;
  buyerLevel?: string;
  blacklistedOnly?: boolean;
  page?: number;
  size?: number;
}

function query<T extends object>(params: T): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params as Record<string, unknown>)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

// ------------------------------------------------------------ 接口

const base = 'trade/order-review';

export function fetchCapabilities(): Promise<OrderReviewRuleCapabilities> {
  return api<OrderReviewRuleCapabilities>(`${base}/capabilities`);
}

export function fetchRules(params: ReviewRuleParams): Promise<ReviewRulePage> {
  return api<ReviewRulePage>(`${base}/rules${query(params)}`);
}

export function fetchRule(ruleId: string): Promise<ReviewRuleDetail> {
  return api<ReviewRuleDetail>(`${base}/rules/${ruleId}`);
}

export function createRule(input: ReviewRuleInput): Promise<ReviewRuleSaved> {
  return api<ReviewRuleSaved>(`${base}/rules`, 'POST', input);
}

export function updateRule(ruleId: string, input: ReviewRuleInput): Promise<ReviewRuleSaved> {
  return api<ReviewRuleSaved>(`${base}/rules/${ruleId}`, 'PUT', input);
}

export function changeRuleStatus(
  ruleId: string,
  input: ReviewRuleStatusInput,
): Promise<ReviewRuleSaved> {
  return api<ReviewRuleSaved>(`${base}/rules/${ruleId}/status`, 'POST', input);
}

export function fetchBuyerProfiles(params: BuyerProfileParams): Promise<BuyerProfilePage> {
  return api<BuyerProfilePage>(`${base}/buyer-profiles${query(params)}`);
}

export function saveBuyerProfile(input: BuyerProfileInput): Promise<BuyerProfile> {
  return api<BuyerProfile>(`${base}/buyer-profiles`, 'PUT', input);
}

/** 真实判定（RECORDED）：产出审单结论与命中链。 */
export function reviewOrder(orderId: string, input: ReviewRunInput): Promise<ReviewDetail> {
  return api<ReviewDetail>(`${base}/orders/${orderId}/review`, 'POST', input);
}

/** 试跑（DRY_RUN）：拿真实订单试规则，不冒充生产结论。 */
export function trialOrder(orderId: string, input: ReviewRunInput): Promise<ReviewDetail> {
  return api<ReviewDetail>(`${base}/orders/${orderId}/trial`, 'POST', input);
}

export function fetchEvaluations(params: EvaluationParams): Promise<EvaluationPage> {
  return api<EvaluationPage>(`${base}/evaluations${query(params)}`);
}

export function fetchEvaluation(evaluationId: string): Promise<ReviewDetail> {
  return api<ReviewDetail>(`${base}/evaluations/${evaluationId}`);
}

// ------------------------------------------------------------ 条件维度与运算符

/**
 * 某个维度能用哪些运算符。
 *
 * <p>与后端 {@code V149} 的
 * {@code order_review_rule_condition_operator_fits_dimension} 同一条口径：
 * 数值型只能配金额与折扣比例、标记型只能配黑名单、其余维度只能配集合型。
 *
 * <p>前端**也**按这条过滤下拉框，而不是让用户配出一个「收货国家 ≥ 50」再由后端 400 挡下 ——
 * 那条规则看上去配好了，却永远判不出结果，是最难查的一类故障。
 */
export function operatorsFor(dimension: string): string[] {
  switch (dimension) {
    case 'AMOUNT':
    case 'DISCOUNT_RATIO':
      return ['GTE', 'GT', 'LTE', 'LT', 'EQ'];
    case 'BUYER_BLACKLIST':
      return ['IS_TRUE', 'IS_FALSE'];
    default:
      return ['IN', 'NOT_IN'];
  }
}

/** 运算符中文（与后端枚举 label 同源，不在前端另立一份词表）。 */
export const OPERATOR_LABELS: Record<string, string> = {
  IN: '属于',
  NOT_IN: '不属于',
  GTE: '大于等于',
  GT: '大于',
  LTE: '小于等于',
  LT: '小于',
  EQ: '等于',
  IS_TRUE: '为真',
  IS_FALSE: '为假',
};

/** 维度中文。 */
export const DIMENSION_LABELS: Record<string, string> = {
  SHOP: '店铺（平台来源）',
  AMOUNT: '订单金额',
  BUYER_LEVEL: '买家等级',
  ITEM: '商品',
  SHIPPING_COUNTRY: '收货国家',
  BUYER_BLACKLIST: '买家黑名单',
  ADDRESS_ANOMALY: '地址异常',
  DISCOUNT_RATIO: '折扣比例',
};

/** 订单来源词表（sales_order.order_source，V99 的 CHECK 就是这七个值）。 */
export const ORDER_SOURCES = [
  'MANUAL',
  'PLATFORM',
  'AMAZON',
  'SHOPIFY',
  'EBAY',
  'TIKTOK_SHOP',
  'ALIBABA_1688',
];

/** 买家等级词表。 */
export const BUYER_LEVELS = ['STANDARD', 'KEY', 'STRATEGIC', 'VIP', 'RISK'];

/**
 * 地址异常码 → 中文。
 *
 * <p>五个码与后端 {@code OrderReviewRuleService.addressAnomalies} 一一对应。
 * 前端不自己判断有没有异常（那是后端实算的事实），只负责把码翻成人话。
 */
export const ADDRESS_ANOMALY_LABELS: Record<string, string> = {
  MISSING_POSTCODE: '缺邮编',
  MISSING_PHONE: '缺联系电话',
  PO_BOX_ONLY: '只有信箱类地址且无公司名',
  INCOMPLETE_ADDRESS: '地址信息不全（地址行过短或省市都空）',
  RESERVED_POSTCODE: '邮编是同一个数字重复的占位号',
};

/** 地址异常标签；空数组显示「无异常」而不是空白。 */
export function anomalyText(codes: readonly string[] | null | undefined): string {
  if (!codes || codes.length === 0) return '无异常';
  return codes.map((code) => `${ADDRESS_ANOMALY_LABELS[code] ?? code}（${code}）`).join('；');
}

// ------------------------------------------------------------ 展示

/** 金额：只做展示格式化，不改值（后端已按 numeric(19,4) 落库）。 */
export function money(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toFixed(4).replace(/\.?0+$/, '') || '0';
}

/**
 * 折扣率百分数。
 *
 * <p>{@code null} = 小计为 0、比例**不可计算**；显示成 0 会被读成「打了 0 折」。
 */
export function discountRateText(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '不可计算（订单小计为 0）';
  return `${rate.toFixed(4).replace(/\.?0+$/, '') || '0'}%`;
}

/** 买家等级：null = 未登记，是「未知」不是「STANDARD」。 */
export function buyerLevelText(level: string | null | undefined): string {
  if (!level) return '未登记（等级未知）';
  return level;
}

/** 黑名单：null = 未登记，是「未知」不是「不在黑名单」。 */
export function blacklistText(blacklisted: boolean | null | undefined): string {
  if (blacklisted === null || blacklisted === undefined) return '未登记（是否在黑名单未知）';
  return blacklisted ? '在黑名单里' : '不在黑名单';
}

/**
 * 规则此刻的生效状态。
 *
 * <p>把「状态」与「生效期」分开说：一条 ACTIVE 规则在生效期之前同样不参与判定，
 * 只显示「生效中」会让人以为它已经在管事了。
 */
export function effectiveText(rule: {
  status: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  effectiveNow: boolean;
}): string {
  if (rule.status === 'DRAFT') return '草稿（不参与判定）';
  if (rule.status === 'DISABLED') return '已停用（不参与判定）';
  if (rule.effectiveNow) return rule.effectiveTo ? `生效中，至 ${rule.effectiveTo}` : '生效中，长期有效';
  return `已生效但不在生效期内（${rule.effectiveFrom} 起${rule.effectiveTo ? ` 至 ${rule.effectiveTo}` : ''}）`;
}

/** 规则状态徽标色调。 */
export function statusTone(status: string): 'default' | 'info' | 'success' | 'warning' | 'danger' {
  switch (status) {
    case 'ACTIVE':
      return 'success';
    case 'DRAFT':
      return 'info';
    case 'DISABLED':
      return 'default';
    default:
      return 'default';
  }
}

/** 结论徽标色调。转人工是本页最需要被一眼看到的状态。 */
export function decisionTone(decision: string): 'success' | 'warning' {
  return decision === 'AUTO_APPROVE' ? 'success' : 'warning';
}

/** 条件的人类可读摘要：拿什么比了什么。 */
export function conditionSummary(condition: {
  dimension: string;
  operator: string;
  expected: string;
}): string {
  const op = OPERATOR_LABELS[condition.operator] ?? condition.operator;
  const dim = DIMENSION_LABELS[condition.dimension] ?? condition.dimension;
  return `${dim} ${op} ${condition.expected}`;
}

/**
 * 规则**定义**侧的条件摘要（还没有订单可比，所以只说「跟什么比什么」）。
 *
 * <p>刻意与 {@link conditionSummary} 分开：定义侧没有 actual_field / actual_value，
 * 把两者合成一个函数只能靠一堆可选字段糊过去，读的人会分不清
 * 「这是条件写的是什么」还是「订单的值是多少」。
 */
export function ruleConditionSummary(condition: {
  dimension: string;
  operator: string;
  valueSet?: readonly (string | null)[] | null;
  valueNumeric?: number | null;
}): string {
  const op = OPERATOR_LABELS[condition.operator] ?? condition.operator;
  const dim = DIMENSION_LABELS[condition.dimension] ?? condition.dimension;
  const value = condition.operator === 'IN' || condition.operator === 'NOT_IN'
    ? (condition.valueSet ?? []).filter((v): v is string => !!v).join(' / ')
    : condition.valueNumeric === null || condition.valueNumeric === undefined
      ? '（未填阈值）'
      : String(condition.valueNumeric);
  return `${dim} ${op} ${value}`;
}

/**
 * 单条命中明细的三行式：订单字段 / 实际值 / 人话解释。
 *
 * <p>蓝图加粗句要求的「哪个条件命中了订单的哪个字段」就落在这三行上：
 * {@code actualValue} 为空时必须显示成「（无值）」，不能渲染成空白 ——
 * 空白会被读成「没查」，而实际是「查了，取不到」。
 */
export function hitConditionLines(condition: HitCondition): { field: string; value: string }[] {
  return [
    { field: condition.actualField || '（未取到字段）', value: condition.actualValue || '（无值）' },
  ];
}

/** 一条规则命中行的摘要：一句话说清命中 / 没命中。 */
export function hitSummary(hit: RuleHit): string {
  const order = hit.forcedManual ? '强制转人工' : hit.actionLabel;
  const state = hit.matched ? '命中' : '未命中';
  return `第 ${hit.evalRank} 位 · ${order} · ${state}：${hit.reason}`;
}

/** 判定记录的模式标签：试跑必须一眼与生产结论分开。 */
export function modeText(decision: ReviewDecision): string {
  return decision.mode === 'DRY_RUN' ? '试跑（不是生产结论）' : '生效判定';
}

/**
 * 结论一句话。
 *
 * <p>后端已经把它写成「哪条规则、为什么」，这里**原样显示**，
 * 刻意不自己再拼一句概括 —— 那会与后端的判定口径漂移，
 * 而这一句话正是客服要照着念给买家的。
 */
export function decisionHeadline(decision: ReviewDecision): string {
  return decision.decisionReason || decision.decisionLabel;
}

/** 判定汇总行：几条参与、几条命中、几条强制。 */
export function decisionCounts(decision: ReviewDecision): string {
  const parts = [`参与判定 ${decision.consideredRuleCount} 条`, `命中 ${decision.matchedRuleCount} 条`];
  if (decision.forcedRuleCount > 0) parts.push(`强制转人工 ${decision.forcedRuleCount} 条`);
  if (decision.winningRuleCode) parts.push(`决定规则 ${decision.winningRuleCode}`);
  return parts.join(' · ');
}

/**
 * 能否现在启用这条规则。
 *
 * <p>零条件的规则不能启用（后端延迟约束触发器也会挡）：零条件的 ANY 规则会放行
 * 每一张单，ALL 规则则永不命中 —— 两者都不是可用的规则。
 */
export function canActivate(rule: ReviewRuleRow | { status: string; conditionCount: number }): boolean {
  return rule.status !== 'ACTIVE' && rule.conditionCount > 0;
}

/** 停用必须写备注：停用会改变之后所有订单的审单结论。 */
export function canDisable(rule: { status: string }): boolean {
  return rule.status === 'ACTIVE';
}

/** 条件草稿 → 提交体（空字符串按 undefined 处理，避免把空集合发给后端）。 */
export function toConditionInput(condition: ReviewRuleConditionInput): ReviewRuleConditionInput {
  return {
    seqNo: condition.seqNo,
    dimension: condition.dimension,
    operator: condition.operator,
    valueSet: (condition.valueSet ?? []).filter((v): v is string => !!v && v.trim() !== ''),
    valueNumeric: condition.valueNumeric ?? null,
    description: condition.description ?? '',
  };
}

/** 详情里的条件 → 表单初值。 */
export function conditionToInput(condition: ReviewRuleCondition): ReviewRuleConditionInput {
  return {
    seqNo: condition.seqNo,
    dimension: condition.dimension,
    operator: condition.operator,
    valueSet: [...(condition.valueSet ?? [])],
    valueNumeric: condition.valueNumeric ?? null,
    description: condition.description,
  };
}

// ------------------------------------------------------------ 错误

export function errorText(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return '审单规则服务暂时不可用，请稍后重试';
}

// ------------------------------------------------------------ 选项

export const RULE_STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'ACTIVE', label: '生效中' },
  { value: 'DISABLED', label: '已停用' },
];

export const DECISION_OPTIONS = [
  { value: '', label: '全部结论' },
  { value: 'AUTO_APPROVE', label: '自动通过' },
  { value: 'MANUAL_REVIEW', label: '转人工' },
];

export const MODE_OPTIONS = [
  { value: '', label: '全部模式' },
  { value: 'RECORDED', label: '生效判定' },
  { value: 'DRY_RUN', label: '试跑' },
];
