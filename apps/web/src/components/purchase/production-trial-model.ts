import type {
  ProductionTrialBomVersionCandidateRow,
  ProductionTrialCapabilities,
  ProductionTrialComponentRequirementRow,
  ProductionTrialCostComparisonRow,
  ProductionTrialCostLineInput,
  ProductionTrialCostView,
  ProductionTrialCreateInput,
  ProductionTrialInspectionItemInput,
  ProductionTrialInspectionItemRow,
  ProductionTrialInspectionJudgmentInput,
  ProductionTrialMassProductionRecommendationInput,
  ProductionTrialMassProductionRecommendationRow,
  ProductionTrialOptions,
  ProductionTrialOrderDetail,
  ProductionTrialOrderPage,
  ProductionTrialOrderRow,
  ProductionTrialRequirementReleaseInput,
  ProductionTrialResultInput,
  ProductionTrialResultRow,
  ProductionTrialSummary,
  ProductionTrialTransitionInput,
  ProductionTrialUpdateInput,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 6.6.2.1 试产单前端模型。
 *
 * <p>蓝图：「管理试产：小批量试产（数量、BOM 版本、组件需求、产线/加工厂、质检标准、试产成本与良率），
 * 试产结果记录并对比量产目标，输出转量产建议。」输出「试产单 + 组件领用需求（→ 7 库存）；良率与成本结果；转量产建议」。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>组件领用需求是「需求」，不是「已领用」</b>：页面**不显示任何「已扣库存 / 已领用」**的说法 ——
 *       本模块**不扣库存**，真实领用由 warehouse 侧 8.4.1 组装单承接。</li>
 *   <li><b>良率是比例</b>：后端返回的是 {@code 0.95}（不是 95）。页面把它显示成
 *       {@code 95.00%} 时那只是**同一个值的显示格式**，不是第二次计算；
 *       原始比例在 tooltip 里给出，页面另有一行口径说明。</li>
 *   <li><b>成本不跨币种加成一个总数</b>：概览逐币种列；「不可比」时显示「不可比」，
 *       <b>不显示 0</b>（量产目标成本取不到 ≠ 目标成本是 0）。</li>
 *   <li><b>做不到的事写在页面上</b>：库存扣减（归 8.4.1 组装单）、转量产建档（归 6.6.2.2）、
 *       汇率（归 5.2.2.1）—— 常驻横幅，不假装可用。</li>
 *   <li><b>状态只显示后端给的合法去向</b>：下拉里的目标状态来自 {@code allowedTargets}，
 *       页面不自己维护一份状态机。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */

const base = 'trade/trial-productions';

// ================================================================ 枚举（本地字面量联合）
//
// 🔴 契约里 enum 被 codegen **内联**成字符串联合（`"GO" | "NO_GO"`），
// 不会导出成具名类型。所以这几个联合在这里**显式声明**，
// 而不是从 api-contracts 里 import 一个并不存在的名字。

/** 试产单状态。 */
export type ProductionTrialStatus =
  | 'REQUESTED'
  | 'SCHEDULED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CLOSED'
  | 'CANCELLED';

/** 组件领用需求状态（🔴 需求，不是库存）。 */
export type ProductionTrialRequirementStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'RELEASED'
  | 'CANCELLED';

/** 质检判定结果。 */
export type ProductionTrialJudgment = 'PENDING' | 'PASS' | 'FAIL';

/** 试产成本类型（封闭五值，无「其它」）。 */
export type ProductionTrialCostType =
  | 'MATERIAL'
  | 'PROCESSING'
  | 'MOLD'
  | 'INSPECTION'
  | 'OVERHEAD';

/** 转量产建议结论。 */
export type ProductionTrialMassProductionDecision = 'GO' | 'NO_GO';

// ================================================================ 查询参数

export interface ProductionTrialOrderParams {
  status?: string;
  bomVersionId?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

/** 试产单清单的查询串（真筛选，不是前端过滤）。 */
export function productionTrialOrderQuery(params: ProductionTrialOrderParams): string {
  const parts: string[] = [];
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.bomVersionId) parts.push(`bomVersionId=${encodeURIComponent(params.bomVersionId)}`);
  if (params.keyword?.trim())
    parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** BOM 版本候选的查询串。 */
export function productionTrialBomQuery(keyword?: string): string {
  return keyword?.trim() ? `?keyword=${encodeURIComponent(keyword.trim())}` : '';
}

// ================================================================ 显示格式化

/**
 * 良率（比例）→ 百分数显示。
 *
 * <p>🔴 这**只是显示格式**，不是第二次计算：后端给的 {@code 0.95} 与这里显示的
 * {@code 95.00%} 是同一个值（比例 0.95 = 95%）。
 * 「取不到」传 {@code null} ⇒ 显示「—」，**不显示 0.00%** ——
 * 「良率取不到」与「良率确实是 0」是两件事。
 */
export function formatYieldRate(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '—';
  return `${(rate * 100).toFixed(2)}%`;
}

/**
 * 成本偏差率（比例）→ 百分数显示。
 *
 * <p>同样只是显示格式。{@code null} = **不可比**（量产目标成本不可得）⇒ 显示「不可比」。
 */
export function formatVarianceRate(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '不可比';
  return `${(rate * 100).toFixed(2)}%`;
}

/**
 * 金额按币种显示（**不跨币种相加**）。
 *
 * <p>🔴 去掉尾随零时**只动小数部分** —— 直接 {@code replace(/0+$/,'')} 会把
 * {@code "100.0000"} 砍成 {@code "1"}（连整数位的零一起吃掉）。
 */
export function formatMoney(amount: number | null | undefined, currency?: string): string {
  if (amount === null || amount === undefined) return '—';
  const fixed = amount.toFixed(4);
  const text = fixed.includes('.')
    ? fixed.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '')
    : fixed;
  return currency ? `${text} ${currency}` : text;
}

/** 数量显示（保留 6 位，去掉尾随零）。 */
export function formatQuantity(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '—';
  return String(amount);
}

/**
 * 成本对比这一行该显示什么。
 *
 * <p>🔴 {@code comparable=false} 时显示「不可比」+ 原因，**不显示 0** ——
 * 「量产目标成本取不到」与「量产目标成本是 0」是两件事。
 */
export function describeComparison(row: ProductionTrialCostComparisonRow): {
  headline: string;
  detail: string;
} {
  if (!row.comparable) {
    return {
      headline: '不可比',
      detail: row.note,
    };
  }
  return {
    headline: `${formatMoney(row.varianceAmount, row.currency)}（${formatVarianceRate(row.varianceRate)}）`,
    detail: row.note,
  };
}

// ================================================================ 调用点

export function fetchProductionTrialCapabilities() {
  return api<ProductionTrialCapabilities>(`${base}/capabilities`);
}

export function fetchProductionTrialOptions() {
  return api<ProductionTrialOptions>(`${base}/options`);
}

export function fetchProductionTrialSummary() {
  return api<ProductionTrialSummary>(`${base}/summary`);
}

export function fetchProductionTrialBomVersionCandidates(keyword?: string) {
  return api<ProductionTrialBomVersionCandidateRow[]>(
    `${base}/bom-version-candidates${productionTrialBomQuery(keyword)}`,
  );
}

export function fetchProductionTrialOrders(params: ProductionTrialOrderParams) {
  return api<ProductionTrialOrderPage>(`${base}${productionTrialOrderQuery(params)}`);
}

export function createProductionTrialOrder(input: ProductionTrialCreateInput) {
  return api<ProductionTrialOrderDetail>(`${base}`, 'POST', input);
}

export function fetchProductionTrialOrder(orderId: string) {
  return api<ProductionTrialOrderDetail>(`${base}/${encodeURIComponent(orderId)}`);
}

export function updateProductionTrialOrder(
  orderId: string,
  input: ProductionTrialUpdateInput,
) {
  return api<ProductionTrialOrderDetail>(
    `${base}/${encodeURIComponent(orderId)}`,
    'PUT',
    input,
  );
}

export function transitionProductionTrialOrder(
  orderId: string,
  input: ProductionTrialTransitionInput,
) {
  return api<ProductionTrialOrderDetail>(
    `${base}/${encodeURIComponent(orderId)}/transitions`,
    'POST',
    input,
  );
}

export function fetchProductionTrialComponentRequirements(orderId: string) {
  return api<ProductionTrialComponentRequirementRow[]>(
    `${base}/${encodeURIComponent(orderId)}/component-requirements`,
  );
}

/**
 * 提交组件领用需求（草稿 → 已提交领用需求）。
 *
 * <p>🔴 **这一步不扣库存** —— 它只是把「要领什么、领多少」交出去。
 */
export function submitProductionTrialRequirement(orderId: string, requirementId: string) {
  return api<ProductionTrialComponentRequirementRow[]>(
    `${base}/${encodeURIComponent(orderId)}/component-requirements/${encodeURIComponent(requirementId)}/submit`,
    'POST',
  );
}

/** 记录「已由加工单承接」（8.4.1 组装单，逻辑引用 + 快照）。 */
export function releaseProductionTrialRequirement(
  orderId: string,
  requirementId: string,
  input: ProductionTrialRequirementReleaseInput,
) {
  return api<ProductionTrialComponentRequirementRow[]>(
    `${base}/${encodeURIComponent(orderId)}/component-requirements/${encodeURIComponent(requirementId)}/release`,
    'POST',
    input,
  );
}

export function fetchProductionTrialInspectionItems(orderId: string) {
  return api<ProductionTrialInspectionItemRow[]>(
    `${base}/${encodeURIComponent(orderId)}/inspection-items`,
  );
}

export function createProductionTrialInspectionItem(
  orderId: string,
  input: ProductionTrialInspectionItemInput,
) {
  return api<ProductionTrialInspectionItemRow[]>(
    `${base}/${encodeURIComponent(orderId)}/inspection-items`,
    'POST',
    input,
  );
}

/** 记录一条质检判定（**不可撤回成「待判定」**）。 */
export function judgeProductionTrialInspectionItem(
  orderId: string,
  itemId: string,
  input: ProductionTrialInspectionJudgmentInput,
) {
  return api<ProductionTrialInspectionItemRow[]>(
    `${base}/${encodeURIComponent(orderId)}/inspection-items/${encodeURIComponent(itemId)}`,
    'PUT',
    input,
  );
}

export function fetchProductionTrialResult(orderId: string) {
  return api<ProductionTrialResultRow | null>(`${base}/${encodeURIComponent(orderId)}/result`);
}

/** 记录试产结果（**良率不收**：由后端按 合格 ÷ 产出 推导）。 */
export function recordProductionTrialResult(orderId: string, input: ProductionTrialResultInput) {
  return api<ProductionTrialResultRow>(`${base}/${encodeURIComponent(orderId)}/result`, 'POST', input);
}

export function fetchProductionTrialCost(orderId: string) {
  return api<ProductionTrialCostView | null>(`${base}/${encodeURIComponent(orderId)}/cost`);
}

export function addProductionTrialCostLine(
  orderId: string,
  input: ProductionTrialCostLineInput,
) {
  return api<ProductionTrialCostView>(
    `${base}/${encodeURIComponent(orderId)}/cost/lines`,
    'POST',
    input,
  );
}

export function deleteProductionTrialCostLine(orderId: string, lineId: string) {
  return api<ProductionTrialCostView>(
    `${base}/${encodeURIComponent(orderId)}/cost/lines/${encodeURIComponent(lineId)}`,
    'DELETE',
  );
}

/** 「试产成本 vs 量产目标成本」的对比（**同一张单、同一个币种**）。 */
export function fetchProductionTrialCostComparison(orderId: string) {
  return api<ProductionTrialCostComparisonRow>(
    `${base}/${encodeURIComponent(orderId)}/cost-comparison`,
  );
}

export function fetchProductionTrialMassProductionRecommendation(orderId: string) {
  return api<ProductionTrialMassProductionRecommendationRow | null>(
    `${base}/${encodeURIComponent(orderId)}/mass-production-recommendation`,
  );
}

/** 记录一条转量产建议（**建议事实**，不是 SKU 建档）。 */
export function recordProductionTrialMassProductionRecommendation(
  orderId: string,
  input: ProductionTrialMassProductionRecommendationInput,
) {
  return api<ProductionTrialMassProductionRecommendationRow>(
    `${base}/${encodeURIComponent(orderId)}/mass-production-recommendation`,
    'POST',
    input,
  );
}

