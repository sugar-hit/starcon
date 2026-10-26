import type {
  FinanceCostAllocationBatch,
  FinanceCostAllocationBatchDetail,
  FinanceCostAllocationBatchPage,
  FinanceCostAllocationCapabilities,
  FinanceCostAllocationOptions,
  FinanceCostAllocationPool,
  FinanceCostAllocationPoolPage,
  FinanceCostAllocationResult,
  FinanceCostAllocationResultPage,
  FinanceCostAllocationRule,
  FinanceCostAllocationRuleInput,
  FinanceCostAllocationTarget,
  FinanceCostAllocationTargetCollectResult,
  FinanceCostAllocationTargetPage,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.4.2.2 分摊规则的前端模型。
 *
 * <p>蓝图 12-finance.md：「配置费用分摊规则：分摊维度（SKU/SPU/店铺/订单/类目）、分摊基数（数量/金额/重量/体积/等额）、生效区间、分摊顺序与舍入差处理。」输入：「规则配置；待分摊费用池。」输出：「分摊结果（保留舍入差额到指定对象）+ 分摊明细。」关系：「上游 = 12.4.2.1；下游 = 13 利润（SKU 利润）。<b>分摊结果之和必须等于被分摊总额</b>（含舍入差处理），否则利润报表总额对不上 —— 这是财务最不能接受的错误。」
 *
 * <p><b>命名空间</b>：{@code finance/cost-allocations}，与 12.4.2.1 的 {@code finance/cost-collections}
 * <b>刻意不共用前缀</b> —— 那是别的模块的命名空间，而本模块与它的边界正是「归集 ≠ 分摊」。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>🔴 Σ结果 = 被分摊总额</b>：页面把「被分摊总额 / 分摊总额 / 舍入差」三列并排显示，
 *       让「结果之和必须等于被分摊总额」这条等式<b>肉眼可查</b>；
 *       页面<b>不做任何业务算术</b>（服务端算好了）。</li>
 *   <li><b>舍入差必须看得见</b>：零头落在哪个对象上由服务端标记（{@code isRoundingTarget}），
 *       页面把它显示出来 —— 「保留舍入差额到指定对象」是蓝图点名的职责。</li>
 *   <li><b>基数不可得 ≠ 摊到 0</b>：页面把「没分到」显示成「基数不可得，未参与分摊」，
 *       而不是显示一个安静的 0.0000。</li>
 *   <li><b>币种分开</b>：汇总按币种渲染，绝不把不同币种摆进同一列相加。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有分摊对象 / 还没有分摊批次」。</li>
 * </ul>
 */

const base = 'finance/cost-allocations';

/**
 * 分摊的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface CostAllocationParams {
  poolId?: string | undefined;
  batchId?: string | undefined;
  dimension?: string | undefined;
  basisAvailable?: boolean | undefined;
  status?: string | undefined;
  currency?: string | undefined;
  poolKey?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

function query(params: Record<string, string | number | boolean | undefined>): string {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    if (typeof value === 'string' && value === '') continue;
    parts.push(`${key}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 端点

/** 能力声明：哪些上游真接通、哪些还没交付、归谁。 */
export function fetchCostAllocationCapabilities() {
  return api<FinanceCostAllocationCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（全部来自真实数据）。 */
export function fetchCostAllocationOptions() {
  return api<FinanceCostAllocationOptions>(`${base}/options`);
}

/** 分摊规则列表（租户自己维护，系统不预置任何一行）。 */
export function fetchCostAllocationRules() {
  return api<FinanceCostAllocationRule[]>(`${base}/rules`);
}

/** 新建分摊规则。 */
export function createCostAllocationRule(input: FinanceCostAllocationRuleInput) {
  return api<FinanceCostAllocationRule>(`${base}/rules`, 'POST', input);
}

/** 修改分摊规则（已经产生过分摊批次的规则不允许改口径）。 */
export function updateCostAllocationRule(id: string, input: FinanceCostAllocationRuleInput) {
  return api<FinanceCostAllocationRule>(`${base}/rules/${id}`, 'PUT', input);
}

/** 待分摊的费用池列表（真分页 + 真筛选）。 */
export function fetchCostAllocationPools(params: CostAllocationParams = {}) {
  return api<FinanceCostAllocationPoolPage>(
    `${base}/pools${query({
      poolKey: params.poolKey,
      currency: params.currency,
      page: params.page,
      size: params.size,
    })}`,
  );
}

/** 分摊对象列表（真分页 + 真筛选）。 */
export function fetchCostAllocationTargets(params: CostAllocationParams = {}) {
  return api<FinanceCostAllocationTargetPage>(
    `${base}/targets${query({
      poolId: params.poolId,
      dimension: params.dimension,
      keyword: params.keyword,
      page: params.page,
      size: params.size,
    })}`,
  );
}

/** 采集分摊对象（从真实上游拉）。 */
export function collectCostAllocationTargets(poolId: string, dimension: string) {
  return api<FinanceCostAllocationTargetCollectResult>(
    `${base}/targets/collect${query({ poolId, dimension })}`,
    'POST',
  );
}

/** 执行分摊：把一个费用池按规则摊到它的分摊对象上。 */
export function allocateCost(poolId: string, ruleId?: string | undefined) {
  return api<FinanceCostAllocationBatchDetail>(`${base}/allocate`, 'POST', {
    poolId,
    ...(ruleId ? { ruleId } : {}),
  });
}

/** 分摊批次列表（真分页 + 真筛选 + 按币种分开的汇总）。 */
export function fetchCostAllocationBatches(params: CostAllocationParams = {}) {
  return api<FinanceCostAllocationBatchPage>(
    `${base}/batches${query({
      poolId: params.poolId,
      status: params.status,
      currency: params.currency,
      page: params.page,
      size: params.size,
    })}`,
  );
}

/** 一个批次的详情（批次 + 分摊明细）。 */
export function fetchCostAllocationBatch(id: string) {
  return api<FinanceCostAllocationBatchDetail>(`${base}/batches/${id}`);
}

/** 撤销一次分摊（池回到未分摊状态，可以按新规则重摊）。 */
export function revertCostAllocationBatch(id: string) {
  return api<FinanceCostAllocationBatchDetail>(`${base}/batches/${id}/revert`, 'POST');
}

/** 分摊结果列表（真分页 + 真筛选 + 汇总覆盖整个筛选结果集）。 */
export function fetchCostAllocationResults(params: CostAllocationParams = {}) {
  return api<FinanceCostAllocationResultPage>(
    `${base}/results${query({
      poolId: params.poolId,
      batchId: params.batchId,
      dimension: params.dimension,
      basisAvailable: params.basisAvailable,
      page: params.page,
      size: params.size,
    })}`,
  );
}

// ================================================================ 可读名

/** 分摊维度的可读名（摊给谁）。 */
export function allocationDimensionLabel(dimension: string): string {
  switch (dimension) {
    case 'SKU':
      return '按 SKU';
    case 'SPU':
      return '按 SPU';
    case 'SHOP':
      return '按店铺';
    case 'ORDER':
      return '按订单';
    case 'CATEGORY':
      return '按类目';
    default:
      return dimension;
  }
}

/** 分摊基数的可读名（按什么摊）。 */
export function allocationBasisLabel(basis: string): string {
  switch (basis) {
    case 'QUANTITY':
      return '按数量';
    case 'AMOUNT':
      return '按金额';
    case 'WEIGHT':
      return '按重量';
    case 'VOLUME':
      return '按体积';
    case 'EQUAL':
      return '等额';
    default:
      return basis;
  }
}

/** 舍入差处理的可读名。 */
export function roundingPolicyLabel(policy: string): string {
  switch (policy) {
    case 'LARGEST_REMAINDER':
      return '最大余数优先';
    case 'SMALLEST_REMAINDER':
      return '最小余数优先';
    case 'FIRST_TARGET':
      return '第一个对象';
    case 'LAST_TARGET':
      return '最后一个对象';
    case 'SPECIFIED':
      return '指定对象';
    default:
      return policy;
  }
}

/** 分摊批次状态的可读名。 */
export function allocationBatchStatusLabel(status: string): string {
  switch (status) {
    case 'ALLOCATED':
      return '已分摊';
    case 'REVERTED':
      return '已撤销';
    default:
      return status;
  }
}

/** 分摊对象来源的可读名（这份基数是从哪个上游事实来的）。 */
export function allocationBasisSourceLabel(source: string): string {
  switch (source) {
    case 'TRADE_PLATFORM_ORDER':
      return '平台订单';
    case 'TRADE_SALES_ORDER_LINE':
      return '销售订单行';
    case 'CATALOG_SKU':
      return '商品主数据';
    case 'CHANNEL_SHOP':
      return '店铺主数据';
    default:
      return source;
  }
}

/**
 * 金额的可读形态。
 *
 * <p>⚠️ **不在前端做算术**：数值原样透传（只做显示格式化）。
 */
export function formatAllocationAmount(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '—';
  return amount.toFixed(4);
}

/** 金额 + 币种的可读形态。 */
export function formatAllocationMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '—';
  return `${amount.toFixed(4)} ${currency ?? ''}`.trim();
}

/** 数量的可读形态（数量是 numeric(19,6)）。 */
export function formatAllocationQuantity(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toFixed(6);
}

/** 占比的可读形态（比例是 numeric(19,10)）。 */
export function formatAllocationRatio(ratio: number | null | undefined): string {
  if (ratio === null || ratio === undefined) return '—';
  return `${(ratio * 100).toFixed(4)}%`;
}

/**
 * 归属期间的可读形态。
 *
 * <p>⚠️ 只做**拼接**不做日期差计算 —— 期间边界是服务端给的日历区间。
 */
export function formatAllocationPeriod(
  start: string | null | undefined,
  end: string | null | undefined,
): string {
  if (!start || !end) return '—';
  return `${start} ~ ${end}`;
}

/**
 * 「没分到」的可读形态。
 *
 * <p>⚠️ 基数不可得的对象分摊额是 0，但 0 的含义是「**没分到**」而不是「它不用花钱」——
 * 页面必须把这件事说出来，不能显示一个安静的 0.0000。
 */
export function allocationUnavailableText(result: FinanceCostAllocationResult): string {
  if (result.basisAvailable) return '';
  return result.basisNote?.trim()
    ? `基数不可得，未参与分摊：${result.basisNote.trim()}`
    : '基数不可得，未参与分摊';
}

/** 池是否已完成分摊（上游 12.4.2.1 的三列之一，语义在本模块交付后由「恒 false」改为「已分摊」）。 */
export function isPoolAllocated(pool: FinanceCostAllocationPool): boolean {
  return pool.allocationAvailable;
}
