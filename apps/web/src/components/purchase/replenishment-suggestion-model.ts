import type {
  ReplenishmentBatchPage,
  ReplenishmentCapabilities,
  ReplenishmentComputeInput,
  ReplenishmentComputeResult,
  ReplenishmentCoveragePolicyInput,
  ReplenishmentCoveragePolicyPage,
  ReplenishmentDecisionInput,
  ReplenishmentOrderPolicyInput,
  ReplenishmentOrderPolicyPage,
  ReplenishmentSuggestionDetail,
  ReplenishmentSuggestionPage,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 6.2.1.2 补货建议前端模型。
 *
 * <p>蓝图：「计算建议补货量：目标库存覆盖天数 × 日均销量 −（可用 + 在途），并结合 MOQ、
 * 箱规、采购起订额做取整修正，给出建议供应商与到货时间。」输出「补货建议（建议量、取整说明、
 * 预计到货日、预计费用）；支持接受/调整/忽略」。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>「答不出来」与「不需要补」不折叠</b>：{@code NO_SALES_DATA}（缺日均销量）/
 *       {@code NO_QUOTE}（缺有效报价）/ {@code NO_BOX_SPEC}（缺箱规）是三个不同的状态，
 *       {@code HOLD}（不需要补）是第四个。页面显示各自的名字，**绝不显示成 0** ——
 *       「没登记数据」被显示成 0 会被读成「库存充足」。</li>
 *   <li><b>建议量在基础单位，起订量在采购单位</b>：两列分开显示，中间标明箱规，
 *       否则「22 箱 = 264 件」会被读成两个可以互相比较的数量。</li>
 *   <li><b>取整说明与判定依据随行返回</b>：直接显示后端给的原文，
 *       「这个订量为什么比净需求量大」不用点进去再猜。</li>
 *   <li><b>每条建议都能展开看到计算过程</b>（日均销量怎么来的、扣了哪些在途、取整怎么做的）——
 *       蓝图加粗句「用户不信任的预测不会被采纳」。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */

const base = 'trade/replenishment-suggestions';

// ================================================================ 查询参数

export interface ReplenishmentSuggestionParams {
  batchId?: string;
  advice?: string;
  status?: string;
  skuCode?: string;
  keyword?: string;
  onlyActionable?: boolean;
  page?: number;
  size?: number;
}

export function replenishmentSuggestionQuery(params: ReplenishmentSuggestionParams): string {
  const parts: string[] = [];
  if (params.batchId) parts.push(`batchId=${encodeURIComponent(params.batchId)}`);
  if (params.advice) parts.push(`advice=${encodeURIComponent(params.advice)}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.skuCode) parts.push(`skuCode=${encodeURIComponent(params.skuCode)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.onlyActionable) parts.push('onlyActionable=true');
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export interface ReplenishmentBatchParams {
  page?: number;
  size?: number;
}

export function replenishmentBatchQuery(params: ReplenishmentBatchParams): string {
  const parts: string[] = [];
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export interface ReplenishmentCoveragePolicyParams {
  scope?: string;
  skuId?: string;
  categoryCode?: string;
  effectiveOnly?: boolean;
  page?: number;
  size?: number;
}

export function replenishmentCoveragePolicyQuery(
  params: ReplenishmentCoveragePolicyParams,
): string {
  const parts: string[] = [];
  if (params.scope) parts.push(`scope=${encodeURIComponent(params.scope)}`);
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  if (params.categoryCode) parts.push(`categoryCode=${encodeURIComponent(params.categoryCode)}`);
  if (params.effectiveOnly) parts.push('effectiveOnly=true');
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 调用点

export function fetchReplenishmentCapabilities() {
  return api<ReplenishmentCapabilities>(`${base}/capabilities`);
}

export function fetchReplenishmentSuggestions(params: ReplenishmentSuggestionParams) {
  return api<ReplenishmentSuggestionPage>(
    `${base}/suggestions${replenishmentSuggestionQuery(params)}`,
  );
}

export function fetchReplenishmentSuggestion(id: string) {
  return api<ReplenishmentSuggestionDetail>(`${base}/suggestions/${encodeURIComponent(id)}`);
}

export function fetchReplenishmentBatches(params: ReplenishmentBatchParams) {
  return api<ReplenishmentBatchPage>(`${base}/batches${replenishmentBatchQuery(params)}`);
}

/** 发起一次补货建议计算（后端落成一份批次）。 */
export function computeReplenishmentBatch(input: ReplenishmentComputeInput) {
  return api<ReplenishmentComputeResult>(`${base}/batches`, 'POST', input);
}

/** 处置一条建议：接受 / 调整 / 忽略。 */
export function decideReplenishmentSuggestion(id: string, input: ReplenishmentDecisionInput) {
  return api<ReplenishmentSuggestionDetail['suggestion']>(
    `${base}/suggestions/${encodeURIComponent(id)}/decision`,
    'POST',
    input,
  );
}

export function fetchReplenishmentCoveragePolicies(params: ReplenishmentCoveragePolicyParams) {
  return api<ReplenishmentCoveragePolicyPage>(
    `${base}/coverage-policies${replenishmentCoveragePolicyQuery(params)}`,
  );
}

export function upsertReplenishmentCoveragePolicy(input: ReplenishmentCoveragePolicyInput) {
  return api<ReplenishmentCoveragePolicyPage['rows'][number]>(
    `${base}/coverage-policies`,
    'POST',
    input,
  );
}

export function fetchReplenishmentOrderPolicies(params: {
  supplierId?: string;
  effectiveOnly?: boolean;
  page?: number;
  size?: number;
}) {
  const parts: string[] = [];
  if (params.supplierId) parts.push(`supplierId=${encodeURIComponent(params.supplierId)}`);
  if (params.effectiveOnly) parts.push('effectiveOnly=true');
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return api<ReplenishmentOrderPolicyPage>(
    `${base}/order-policies${parts.length ? `?${parts.join('&')}` : ''}`,
  );
}

export function upsertReplenishmentOrderPolicy(input: ReplenishmentOrderPolicyInput) {
  return api<ReplenishmentOrderPolicyPage['rows'][number]>(`${base}/order-policies`, 'POST', input);
}

// ================================================================ 展示口径

/**
 * 建议类别的标签（与后端 ReplenishmentAdvice 一一对应）。
 *
 * <p>五种取值各有各的含义，**不折叠**：把「缺日均销量」与「缺有效报价」都显示成 0，
 * 会让运营以为「不用补」，而真相是「算不出来」—— 那是要有人去补数据的洞。
 */
export const replenishmentAdviceLabels: Record<string, string> = {
  REPLENISH: '建议补货',
  HOLD: '无需补货',
  NO_SALES_DATA: '缺日均销量',
  NO_QUOTE: '缺有效报价',
  NO_BOX_SPEC: '缺箱规',
};

export function adviceLabel(advice: string): string {
  return replenishmentAdviceLabels[advice] ?? advice;
}

/**
 * 建议类别的视觉分级。
 *
 * <p>「答不出来」的三档用中性灰而不是危险色 —— 它们不是「危险」，是「答不出来」，
 * 用红色会让人以为那是急单，把真正要下单的那几条淹掉。
 */
export function adviceTone(advice: string): 'danger' | 'warning' | 'info' | 'neutral' {
  switch (advice) {
    case 'REPLENISH':
      return 'warning';
    case 'HOLD':
      return 'info';
    default:
      return 'neutral';
  }
}

/** 处置状态的标签（与后端 ReplenishmentStatus 一一对应）。 */
export const replenishmentStatusLabels: Record<string, string> = {
  SUGGESTED: '待处置',
  ACCEPTED: '已接受',
  ADJUSTED: '已调整',
  IGNORED: '已忽略',
};

export function statusLabel(status: string): string {
  return replenishmentStatusLabels[status] ?? status;
}

/** 处置动作的标签（与后端 ReplenishmentDecisionAction 一一对应）。 */
export const replenishmentDecisionLabels: Record<string, string> = {
  ACCEPT: '接受',
  ADJUST: '调整',
  IGNORE: '忽略',
};

export function decisionLabel(action: string): string {
  return replenishmentDecisionLabels[action] ?? action;
}

/** 「答不出来」的统一文案。 */
export const NO_DATA_TEXT = '无数据';

/**
 * 日均销量的展示。
 *
 * <p>null（没有登记销售速度）显示「无数据」，**不显示 0**。
 */
export function dailySalesText(dailySalesQuantity: number | null | undefined): string {
  if (dailySalesQuantity === null || dailySalesQuantity === undefined) return NO_DATA_TEXT;
  return fmt(dailySalesQuantity);
}

/** 近期增长率的展示（null = 没有上一个版本可比）。 */
export function growthRateText(growthRate: number | null): string {
  if (growthRate === null || growthRate === undefined) return NO_DATA_TEXT;
  const percent = Number((growthRate * 100).toFixed(2));
  return `${percent > 0 ? '+' : ''}${percent}%`;
}

/** 建议量的展示。null = 给不出建议量（缺报价 / 缺箱规），**不是 0**。 */
export function suggestedQuantityText(suggestedQuantity: number | null): string {
  if (suggestedQuantity === null || suggestedQuantity === undefined) return NO_DATA_TEXT;
  return fmt(suggestedQuantity);
}

/** 净需求的展示。null = 算不出来（缺日均销量）。 */
export function rawRequiredText(rawRequiredQuantity: number | null): string {
  if (rawRequiredQuantity === null || rawRequiredQuantity === undefined) return NO_DATA_TEXT;
  return fmt(rawRequiredQuantity);
}

/**
 * 预计费用的展示。
 *
 * <p>null = 取不到适用阶梯价（低于报价最低档），**不是 0** —— 0 会被读成「不要钱」。
 */
export function estimatedCostText(estimatedCost: number | null, currency: string | null): string {
  if (estimatedCost === null || estimatedCost === undefined) return NO_DATA_TEXT;
  return `${fmt(estimatedCost)}${currency ? ` ${currency}` : ''}`;
}

/**
 * 预计到货日的展示。
 *
 * <p>null = 没有交期（缺报价），**不是「今天到」**。
 */
export function arrivalDateText(expectedArrivalDate: string | null): string {
  if (!expectedArrivalDate) return NO_DATA_TEXT;
  return expectedArrivalDate;
}

/**
 * 箱规的展示。
 *
 * <p>null = 量不到（catalog 没登记换算比例），**不是 1** —— 猜成 1 会把订量算少一箱。
 */
export function boxSpecText(
  boxSpecQuantity: number | null,
  purchaseUnitCode: string | null,
): string {
  if (boxSpecQuantity === null || boxSpecQuantity === undefined) return NO_DATA_TEXT;
  return `${fmt(boxSpecQuantity)} 件/${purchaseUnitCode ?? '采购单位'}`;
}

/** 数量的展示。null 一律给「无数据」。 */
export function fmt(value: number | null | undefined): string {
  if (value === null || value === undefined) return NO_DATA_TEXT;
  const rounded = Number(value.toFixed(6));
  return rounded.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/** 时间格式化（timestamptz 带偏移的 ISO 串）。 */
export function moment(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/**
 * 这条建议能不能被处置。
 *
 * <p>只有「建议补货」能接受 / 调整 / 忽略 —— 给「答不出来」的行点忽略，
 * 会把一个数据缺口伪装成一次业务决定。
 */
export function isActionable(advice: string): boolean {
  return advice === 'REPLENISH';
}
