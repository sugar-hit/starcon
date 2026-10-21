import type {
  MassProductionBomLineRow,
  MassProductionCapabilities,
  MassProductionCatalogAttributeRow,
  MassProductionCatalogSpuRow,
  MassProductionCostComparisonRow,
  MassProductionCreateInput,
  MassProductionHandoffRow,
  MassProductionOptions,
  MassProductionPlanDetail,
  MassProductionPlanPage,
  MassProductionPlanRow,
  MassProductionSkuFilingInput,
  MassProductionSkuFilingRow,
  MassProductionSummary,
  MassProductionTransitionInput,
  MassProductionTrialCandidateRow,
  MassProductionUpdateInput,
  ProductionTrialBomVersionCandidateRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';
import { formatMoney, formatQuantity } from './production-trial-model';

/**
 * 6.6.2.2 转量产与建档前端模型。
 *
 * <p>蓝图：「试产通过后转正式量产：确定量产 BOM、批量采购/生产计划、目标成本、
 * 正式 SKU 建档（含编码/条码/属性/报关属性），并把量产参数带入采购与价格模型。」
 * 输出「量产计划；正式 SKU（3.1.2.1）；量产 BOM 版本；采购需求（6.2.1.2）」。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>正式 SKU 是「交给商品中心建的」，不是本页建的</b>：页面显示的是
 *       <b>建档请求</b>的状态与商品中心返回的 SKU id / 编码，
 *       <b>不出现任何「本模块已建 SKU」的说法</b> —— 写入所有权在 catalog（3.1.2.1）。</li>
 *   <li><b>「结果未知」是一种独立状态</b>：商品中心的建档端点<b>不接受幂等键</b>，
 *       超时 / 断连时无法判断建没建出来 ⇒ 页面必须把它显示成
 *       <b>「结果未知 · 不要重试」</b>，而不是笼统的「失败」——
 *       笼统的「失败」会诱导人点重试，而那可能建出<b>第二个</b> SKU。</li>
 *   <li><b>目标成本取不到就是取不到</b>：不可比时显示「不可比」+ 原因，
 *       <b>不显示 0</b>（目标成本取不到 ≠ 目标成本是 0）。</li>
 *   <li><b>跨币种不比</b>：本服务<b>不做固定汇率折算</b>（5.2.2.1 未交付），
 *       不可比的原因写在页面上。</li>
 *   <li><b>下游交接如实显示</b>：6.2.1.2 采购需求只做<b>重算触发</b>，
 *       量产参数<b>没有</b>进到它的计算里 ⇒ 页面显示
 *       <b>「量产参数未进入 6.2.1.2」</b>，不假装交接过。</li>
 *   <li><b>做不到的事写在页面上</b>：价格模型（5.3.1.1 未开发）、
 *       生产计划下达（8.4.1 组装单，warehouse 独立库）—— 常驻横幅，不假装可用。</li>
 *   <li><b>状态只给后端说的合法去向</b>：下拉里的目标状态来自 {@code allowedTargets}，
 *       页面不自己维护一份状态机。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */

const base = 'trade/mass-productions';

// ================================================================ 枚举（本地字面量联合）
//
// 🔴 契约里 enum 被 codegen **内联**成字符串联合，不会导出成具名类型。
// 所以这几个联合在这里**显式声明**，而不是从 api-contracts 里 import 一个并不存在的名字。

/** 量产计划状态。 */
export type MassProductionPlanStatus =
  | 'DRAFT'
  | 'DECIDED'
  | 'BOM_FROZEN'
  | 'SKU_FILED'
  | 'RELEASED'
  | 'CLOSED'
  | 'CANCELLED';

/** 量产决策结论。 */
export type MassProductionDecision = 'GO' | 'HOLD';

/** 量产决策来源。 */
export type MassProductionDecisionSource = 'TRIAL_RECOMMENDATION' | 'MANUAL_OVERRIDE';

/** SKU 建档请求状态。 */
export type MassProductionFilingStatus =
  | 'PENDING'
  | 'SUBMITTED'
  | 'CONFIRMED'
  | 'PARTIAL'
  | 'FAILED'
  | 'UNKNOWN';

/** 建档属性行的覆写状态。 */
export type MassProductionAttributeState = 'SET' | 'CLEAR' | 'INHERIT';

// ================================================================ 查询参数

export interface MassProductionPlanParams {
  status?: string;
  trialOrderId?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

/** 量产计划清单的查询串（真筛选，不是前端过滤）。 */
export function massProductionPlanQuery(params: MassProductionPlanParams): string {
  const parts: string[] = [];
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.trialOrderId) parts.push(`trialOrderId=${encodeURIComponent(params.trialOrderId)}`);
  if (params.keyword?.trim())
    parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** BOM 版本候选的查询串。 */
export function massProductionBomQuery(keyword?: string): string {
  return keyword?.trim() ? `?keyword=${encodeURIComponent(keyword.trim())}` : '';
}

/** SPU 候选的查询串。 */
export function massProductionSpuQuery(keyword?: string): string {
  return keyword?.trim() ? `?keyword=${encodeURIComponent(keyword.trim())}` : '';
}

/** 商品属性候选的查询串（按目标 SPU 取该 SPU 的属性）。 */
export function massProductionAttributeQuery(spuId: string): string {
  return `?spuId=${encodeURIComponent(spuId)}`;
}

// ================================================================ 显示格式化

/**
 * 成本偏差率（比例）→ 百分数显示。
 *
 * <p>🔴 这**只是显示格式**，不是第二次计算。{@code null} = **不可比** ⇒ 显示「不可比」，
 * <b>不显示 0.00%</b>。
 */
export function formatTargetVarianceRate(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '不可比';
  return `${(rate * 100).toFixed(2)}%`;
}

/**
 * 目标成本对比这一行该显示什么。
 *
 * <p>🔴 {@code comparable=false} 时显示「不可比」+ 原因，<b>不显示 0</b> ——
 * 「目标成本取不到」与「目标成本是 0」是两件事。
 */
export function describeTargetCostComparison(row: MassProductionCostComparisonRow): {
  headline: string;
  detail: string;
} {
  if (!row.comparable) {
    return { headline: '不可比', detail: row.reason };
  }
  return {
    headline: `${formatMoney(row.varianceAmount, row.currency)}（${formatTargetVarianceRate(
      row.varianceRate,
    )}）`,
    detail: row.reason,
  };
}

/**
 * SKU 建档请求该显示什么。
 *
 * <p>🔴 「结果未知」<b>单独一档</b>：商品中心的建档端点不接受幂等键，
 * 超时 / 断连时无法判断建没建出来 ⇒ 页面必须明写「不要重试」，
 * 否则界面会诱导人点重试，而那可能建出<b>第二个</b> SKU。
 *
 * <p>🔴 入参取 {@code NonNullable<...>}：契约里 {@code MassProductionSkuFilingRow}
 * 整体带 {@code | null}（详情里的 {@code skuFiling} 可能还没有建档请求），
 * 而本函数只对**确实存在的建档请求**求描述。
 */
export function describeFilingOutcome(row: NonNullable<MassProductionSkuFilingRow>): {
  headline: string;
  detail: string;
  retryAllowed: boolean;
  tone: 'neutral' | 'info' | 'success' | 'warning' | 'danger';
} {
  switch (row.status) {
    case 'CONFIRMED':
      return {
        headline: '已建成',
        detail: row.targetSkuId
          ? `商品中心已返回 SKU：${row.targetSkuCode || row.targetSkuId}（id ${row.targetSkuId}）`
          : '商品中心已确认建档。',
        retryAllowed: false,
        tone: 'success',
      };
    case 'PARTIAL':
      return {
        headline: '部分完成',
        detail: `${row.errorMessage || '报关属性没有写进去。'}再提交一次**只补报关属性**，不会建第二个 SKU。`,
        retryAllowed: true,
        tone: 'warning',
      };
    case 'FAILED':
      return {
        headline: '失败',
        detail: row.errorMessage || '商品中心拒绝了这次建档。改了载荷可以再试。',
        retryAllowed: true,
        tone: 'danger',
      };
    case 'UNKNOWN':
      return {
        headline: '结果未知 · 不要重试',
        detail: `${row.errorMessage || '调用商品中心超时或断连，无法判断 SKU 是否已经建出来。'}商品中心的建档端点**不接受幂等键**，请先到商品中心核对该编码是否已存在。`,
        retryAllowed: false,
        tone: 'warning',
      };
    case 'SUBMITTED':
      return {
        headline: '已提交',
        detail: '已经交给商品中心，等它回结果。',
        retryAllowed: false,
        tone: 'info',
      };
    default:
      return {
        headline: '待提交',
        detail: '建档请求已登记，还没提交给商品中心。',
        retryAllowed: true,
        tone: 'neutral',
      };
  }
}

/**
 * 下游交接留痕该显示什么。
 *
 * <p>🔴 「量产参数是否真的进了那次计算」是**单独一栏**：
 * 6.2.1.2 的起订额与交期是人工维护的策略，本模块**不顶掉**它 ⇒
 * 量产参数**没有**进到它的计算里，页面必须如实说。
 */
export function describeHandoff(row: MassProductionHandoffRow): {
  headline: string;
  detail: string;
  applied: string;
  tone: 'neutral' | 'info' | 'success' | 'warning' | 'danger';
} {
  const applied = row.massProductionInputApplied
    ? '量产参数已作为参数进入该模块的计算'
    : '量产参数**未**进入该模块的计算';
  // 🔴 「影响说明」必须**跟结果一起显示**：它才是那句诚实的边界
  // （比如「本次交接是重算触发，量产参数未进入 6.2.1.2」），
  // 只显示 resultSummary 会让人以为参数真的进去了。
  const detail = (primary: string) =>
    [primary, row.impact]
      .map((text) => (text ?? '').trim())
      .filter((text) => text.length > 0)
      .join(' ｜ ');
  switch (row.status) {
    case 'SUCCEEDED':
      return {
        headline: `已交接：${row.resultRef || '完成'}`,
        detail: detail(row.resultSummary),
        applied,
        tone: 'success',
      };
    case 'FAILED':
      return {
        headline: '交接失败',
        detail: detail(row.errorMessage || '交接没有完成。'),
        applied,
        tone: 'danger',
      };
    default:
      return {
        headline: '已跳过',
        detail: detail(row.resultSummary),
        applied,
        tone: 'warning',
      };
  }
}

/** 量产 BOM 组件行的「需求量」显示（**原样搬运 catalog 的 R6/R7**，不二次计算）。 */
export function describeBomLine(row: MassProductionBomLineRow): string {
  return `${row.skuCode} × ${formatQuantity(row.requiredQuantity)}（单位用量 ${formatQuantity(
    row.unitQuantity,
  )}，损耗率 ${formatQuantity(row.scrapRate)}%）`;
}

// ================================================================ 调用点

export function fetchMassProductionCapabilities() {
  return api<MassProductionCapabilities>(`${base}/capabilities`);
}

export function fetchMassProductionOptions() {
  return api<MassProductionOptions>(`${base}/options`);
}

export function fetchMassProductionSummary() {
  return api<MassProductionSummary>(`${base}/summary`);
}

export function fetchMassProductionTrialCandidates() {
  return api<MassProductionTrialCandidateRow[]>(`${base}/trial-candidates`);
}

export function fetchMassProductionBomVersionCandidates(keyword?: string) {
  return api<ProductionTrialBomVersionCandidateRow[]>(
    `${base}/bom-version-candidates${massProductionBomQuery(keyword)}`,
  );
}

export function fetchMassProductionSpuCandidates(keyword?: string) {
  return api<MassProductionCatalogSpuRow[]>(`${base}/spu-candidates${massProductionSpuQuery(keyword)}`);
}

export function fetchMassProductionSpuAttributes(spuId: string) {
  return api<MassProductionCatalogAttributeRow[]>(
    `${base}/spu-attributes${massProductionAttributeQuery(spuId)}`,
  );
}

export function fetchMassProductionPlans(params: MassProductionPlanParams) {
  return api<MassProductionPlanPage>(`${base}${massProductionPlanQuery(params)}`);
}

export function createMassProductionPlan(input: MassProductionCreateInput) {
  return api<MassProductionPlanDetail>(`${base}`, 'POST', input);
}

export function fetchMassProductionPlan(planId: string) {
  return api<MassProductionPlanDetail>(`${base}/${encodeURIComponent(planId)}`);
}

export function updateMassProductionPlan(planId: string, input: MassProductionUpdateInput) {
  return api<MassProductionPlanDetail>(`${base}/${encodeURIComponent(planId)}`, 'PUT', input);
}

export function transitionMassProductionPlan(planId: string, input: MassProductionTransitionInput) {
  return api<MassProductionPlanDetail>(
    `${base}/${encodeURIComponent(planId)}/transitions`,
    'POST',
    input,
  );
}

export function fetchMassProductionBomLines(planId: string) {
  return api<MassProductionBomLineRow[]>(`${base}/${encodeURIComponent(planId)}/bom-lines`);
}

export function fetchMassProductionSkuFiling(planId: string) {
  return api<MassProductionSkuFilingRow | null>(
    `${base}/${encodeURIComponent(planId)}/sku-filing`,
  );
}

export function createMassProductionSkuFiling(planId: string, input: MassProductionSkuFilingInput) {
  return api<MassProductionSkuFilingRow>(
    `${base}/${encodeURIComponent(planId)}/sku-filing`,
    'POST',
    input,
  );
}

/**
 * 提交 SKU 建档（**真调商品中心**）。
 *
 * <p>🔴 后端按**落库的状态**映射 HTTP 状态码（CONFIRMED→200 / FAILED→400 /
 * UNKNOWN→503 / PARTIAL→400），失败也是**落库的事实**，所以这里的响应体
 * 在非 2xx 时也带着建档请求本身。
 */
export function submitMassProductionSkuFiling(planId: string, filingId: string) {
  return api<MassProductionSkuFilingRow>(
    `${base}/${encodeURIComponent(planId)}/sku-filing/${encodeURIComponent(filingId)}/submit`,
    'POST',
  );
}

export function fetchMassProductionHandoffs(planId: string) {
  return api<MassProductionHandoffRow[]>(`${base}/${encodeURIComponent(planId)}/handoffs`);
}

/** 重新触发 6.2.1.2 补货建议重算（**只重算，量产参数进不去**）。 */
export function triggerMassProductionReplenishment(planId: string) {
  return api<MassProductionHandoffRow[]>(
    `${base}/${encodeURIComponent(planId)}/handoffs/replenishment`,
    'POST',
  );
}

export function fetchMassProductionCostComparison(planId: string) {
  return api<MassProductionCostComparisonRow>(
    `${base}/${encodeURIComponent(planId)}/cost-comparison`,
  );
}
