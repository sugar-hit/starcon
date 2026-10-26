import type {
  FinanceCostCalculationCapabilities,
  FinanceCostCalculationEntryPage,
  FinanceCostCalculationLayerPage,
  FinanceCostCalculationOptions,
  FinanceCostCalculationRequest,
  FinanceCostCalculationRunDetail,
  FinanceCostCalculationRunPage,
  FinanceCostCalculationRunRow,
  FinanceCostCalculationVarianceComparison,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.4.1.2 成本计算执行的前端模型。
 *
 * <p>蓝图 12-finance.md：「执行成本计算：对销售出库、发货、平台仓转出计算应结转成本，生成成本层与成本分录；支持按期间批量重算与差异对比。」输入：「库存流水（7.1.2.1）；计价方法；成本层数据；计算期间。」输出：「成本计算结果（成本层 + 结转金额）+ 差异对比。」关系：「上游 = 7 库存流水、12.4.1.1；下游 = 13 利润、12.5.2 凭证。<b>数量账在 warehouse、价值账在 finance</b>——这是两个服务的职责划分，重算不能写库存。」
 *
 * <p><b>命名空间</b>：{@code finance/cost-calculation}，与 12.4.1.1 的 {@code finance/cost-valuation}、
 * 12.4.2.1 的 {@code finance/cost-collections} <b>刻意不共用前缀</b> —— 那是别的模块的命名空间。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>算不出来 ≠ 0</b>：服务端给 {@code null} 的地方前端一律显示「—」，
 *       并把「为什么算不出来」（{@code gapNote}）原样显示出来。
 *       覆盖不全的出库行**不给单价**（半截成本不能当成完整单价）。</li>
 *   <li><b>缺口必须看得见</b>：有缺口的分录行带警告标记，
 *       批次汇总上直接显示「覆盖量 / 出库量 / 缺口量」三个数。</li>
 *   <li><b>金额只原样展示</b>：差额、单位成本都由服务端算好，页面<b>不做任何业务算术</b>。</li>
 *   <li><b>重算 = 另起批次</b>：页面永远显示「这一批是哪一批重算出来的」，
 *       旧批次的数字**原样保留**，不会被重算改写。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有算过成本」。</li>
 * </ul>
 */

const base = 'finance/cost-calculation';

/**
 * 批次列表的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface CostCalculationParams {
  scopeType?: string | undefined;
  scopeRefId?: string | undefined;
  valuationMethod?: string | undefined;
  status?: string | undefined;
}

export interface CostCalculationEntryParams {
  runId?: string | undefined;
  skuId?: string | undefined;
  businessType?: string | undefined;
  status?: string | undefined;
}

export interface CostCalculationLayerParams {
  runId?: string | undefined;
  skuId?: string | undefined;
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
export function fetchCostCalculationCapabilities() {
  return api<FinanceCostCalculationCapabilities>(`${base}/capabilities`);
}

/** 筛选项 + 四个计价方法选项（复用 12.4.1.1 的方法选项与口径说明）。 */
export function fetchCostCalculationOptions() {
  return api<FinanceCostCalculationOptions>(`${base}/options`);
}

/** 批次列表（真分页 + 真筛选）。 */
export function fetchCostCalculationRuns(params: CostCalculationParams = {}) {
  return api<FinanceCostCalculationRunPage>(
    `${base}/runs${query({
      scopeType: params.scopeType,
      scopeRefId: params.scopeRefId,
      valuationMethod: params.valuationMethod,
      status: params.status,
    })}`,
  );
}

/** 执行一次成本计算（期间 + 范围 + 计价方法）。 */
export function runCostCalculation(input: FinanceCostCalculationRequest) {
  return api<FinanceCostCalculationRunRow>(`${base}/runs`, 'POST', input);
}

/** 批次明细（表头 + 全部成本层 + 全部成本分录）。 */
export function fetchCostCalculationRun(id: string) {
  return api<FinanceCostCalculationRunDetail>(`${base}/runs/${id}`);
}

/** 按期间重算（另起批次，旧批次不被改写）。 */
export function recalculateCostCalculation(id: string) {
  return api<FinanceCostCalculationRunRow>(`${base}/runs/${id}/recalculate`, 'POST');
}

/** 差异对比：两次批次之间逐 SKU 对比。 */
export function fetchCostCalculationComparison(fromRunId: string, toRunId: string) {
  return api<FinanceCostCalculationVarianceComparison>(
    `${base}/runs/compare${query({ fromRunId, toRunId })}`,
  );
}

/** 成本层列表（真分页 + 真筛选）。 */
export function fetchCostCalculationLayers(params: CostCalculationLayerParams = {}) {
  return api<FinanceCostCalculationLayerPage>(
    `${base}/layers${query({ runId: params.runId, skuId: params.skuId })}`,
  );
}

/** 成本分录列表（真分页 + 真筛选 + 汇总）。 */
export function fetchCostCalculationEntries(params: CostCalculationEntryParams = {}) {
  return api<FinanceCostCalculationEntryPage>(
    `${base}/entries${query({
      runId: params.runId,
      skuId: params.skuId,
      businessType: params.businessType,
      status: params.status,
    })}`,
  );
}

// ================================================================ 可读名

/** 业务类型的可读名（销售出库 / 发货 / 平台仓转出）。 */
export function costCalculationBusinessTypeLabel(value: string | null | undefined): string {
  switch (value) {
    case 'SALES_OUTBOUND':
      return '销售出库';
    case 'SHIPMENT':
      return '发货';
    case 'PLATFORM_TRANSFER_OUT':
      return '平台仓转出';
    default:
      return value ?? '';
  }
}

/** 批次状态的可读名。 */
export function costCalculationRunStatusLabel(value: string | null | undefined): string {
  switch (value) {
    case 'PENDING':
      return '计算中';
    case 'COMPLETED':
      return '已完成';
    case 'FAILED':
      return '已失败';
    default:
      return value ?? '';
  }
}

/** 分录状态的可读名。 */
export function costCalculationEntryStatusLabel(value: string | null | undefined): string {
  switch (value) {
    case 'CALCULATED':
      return '已结转';
    case 'INCOMPLETE':
      return '有缺口';
    default:
      return value ?? '';
  }
}

/** 批次类型的可读名。 */
export function costCalculationReasonLabel(value: string | null | undefined): string {
  switch (value) {
    case 'INITIAL':
      return '首次计算';
    case 'METHOD_SWITCH':
      return '方法切换重算';
    case 'MANUAL':
      return '人工重算';
    default:
      return value ?? '';
  }
}

/**
 * 金额展示：服务端给 {@code null} 的显示「—」。
 *
 * <p>🔴 **算不出来不是 0** —— 页面显示 0.0000 会被读成「成本是 0」。
 */
export function formatCostCalculationMoney(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toFixed(4);
}

/** 数量展示（六位小数，与后端 NUMERIC(19,6) 一致）。 */
export function formatCostCalculationQuantity(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toFixed(6);
}

/**
 * 差额的措辞。
 *
 * <p>正数 = 新批次**更贵**；负数 = 更便宜。页面直接展示这句话，
 * 免得看数字的人自己判断「贵了还是便宜了」。
 */
export function costCalculationDifferenceLabel(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  if (value > 0) return `更贵 ${formatCostCalculationMoney(Math.abs(value))}`;
  if (value < 0) return `更便宜 ${formatCostCalculationMoney(Math.abs(value))}`;
  return '与对比批次相同';
}

/**
 * 期间区间的展示。
 *
 * <p>⚠️ 期间是**左闭右开**的（起含、止不含），页面上要讲清，
 * 否则「1 月 1 日到 2 月 1 日」到底含不含 2 月 1 日那天的出库就说不清了。
 */
export function costCalculationPeriodLabel(
  from: string | null | undefined,
  to: string | null | undefined,
): string {
  const start = from ? from.slice(0, 10) : '—';
  const end = to ? to.slice(0, 10) : '—';
  return `${start} 起 ~ ${end} 前`;
}

/**
 * 覆盖量的展示：**覆盖 / 出库**。
 *
 * <p>⚠️ 覆盖量小于出库量 ⇒ 有缺口，必须看得见 —— 页面据此打警告标记。
 */
export function costCalculationCoverageLabel(
  covered: number | null | undefined,
  issue: number | null | undefined,
): string {
  if (covered === null || covered === undefined) return '—';
  if (issue === null || issue === undefined) return formatCostCalculationQuantity(covered);
  return `${formatCostCalculationQuantity(covered)} / ${formatCostCalculationQuantity(issue)}`;
}

/**
 * 分录的排序：**有缺口的排在最后**。
 *
 * <p>可比较的先看（成本已结转完整），缺口行排后面并带上原因 ——
 * 页面一眼能看出「哪些出库的成本没算全」。
 */
export function sortCostCalculationEntries<
  T extends { status: string; costAmount: number | null; occurredAt: string },
>(entries: T[]): T[] {
  return [...entries].sort((a, b) => {
    const aIncomplete = a.status === 'INCOMPLETE';
    const bIncomplete = b.status === 'INCOMPLETE';
    if (aIncomplete !== bIncomplete) return aIncomplete ? 1 : -1;
    const left = a.costAmount ?? Number.POSITIVE_INFINITY;
    const right = b.costAmount ?? Number.POSITIVE_INFINITY;
    if (left !== right) return left - right;
    return a.occurredAt.localeCompare(b.occurredAt);
  });
}
