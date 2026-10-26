import type {
  FinanceCostCollectionCapabilities,
  FinanceCostCollectionEntry,
  FinanceCostCollectionEntryPage,
  FinanceCostCollectionManualInput,
  FinanceCostCollectionOptions,
  FinanceCostCollectionPool,
  FinanceCostCollectionPoolDetail,
  FinanceCostCollectionPoolPage,
  FinanceCostCollectionResult,
  FinanceCostCollectionRule,
  FinanceCostCollectionRuleInput,
  FinanceCostCollectionRuleTemplate,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.4.2.1 费用归集的前端模型。
 *
 * <p>蓝图 12-finance.md：「归集各类费用到指定维度：按月分摊的平台费/订阅费、按单分摊的物流费、按 SKU 分摊的头程费、按期间分摊的人员与办公费用，<b>保留归集依据</b>。」输入：「费用来源（平台、物流、订阅、人工录入）；分摊目标维度。」输出：「归集明细（费用项 → 分摊对象 → 金额）。」关系：「上游 = 12.3.1.2、10 物流、14 广告；下游 = 12.4.2.2。<b>归集与分摊要分开</b>：先按规则归集费用池，再按维度分摊，便于核对与调整。」
 *
 * <p><b>命名空间</b>：{@code finance/cost-collections}，与 12.3.1.2 的 {@code finance/transactions}、
 * 12.7.2.1 的 {@code finance/fund-analysis} <b>刻意不共用前缀</b> —— 那是别的模块的命名空间。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>归集 ≠ 分摊</b>：列表里的「分摊目标维度」是<b>规则声明的目标</b>，
 *       <b>不是</b>分摊结果。页面不显示任何「已分摊 / 已摊到 SKU」的结论 ——
 *       12.4.2.2 未交付，分摊结果在本系统里根本不存在。</li>
 *   <li><b>金额只原样展示</b>：合计、占比都由服务端算好，页面<b>不做任何业务算术</b>
 *       （USD 与 EUR 加起来不是任何一个金额）。</li>
 *   <li><b>币种分开</b>：每一处汇总都按币种渲染，绝不把不同币种摆进同一列相加。</li>
 *   <li><b>依据要看得见</b>：每条条目都带它的归集依据（凭据号 / 原始金额 / 上游分项），
 *       页面把它显示出来 —— 「保留归集依据」是蓝图点名的职责。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有归集条目」。</li>
 * </ul>
 */

const base = 'finance/cost-collections';

/**
 * 费用归集的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface CostCollectionParams {
  poolId?: string | undefined;
  ruleId?: string | undefined;
  feeSource?: string | undefined;
  feeCategory?: string | undefined;
  targetDimension?: string | undefined;
  currency?: string | undefined;
  poolKey?: string | undefined;
  baseDateFrom?: string | undefined;
  baseDateTo?: string | undefined;
  periodStart?: string | undefined;
  periodEnd?: string | undefined;
  keyword?: string | undefined;
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
export function fetchCostCollectionCapabilities() {
  return api<FinanceCostCollectionCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（全部来自真实数据）。 */
export function fetchCostCollectionOptions() {
  return api<FinanceCostCollectionOptions>(`${base}/options`);
}

/** 归集规则列表（租户自己维护，系统不预置任何一行）。 */
export function fetchCostCollectionRules() {
  return api<FinanceCostCollectionRule[]>(`${base}/rules`);
}

/** 蓝图标准绑定模板（只读，不落库）。 */
export function fetchCostCollectionRuleTemplates() {
  return api<FinanceCostCollectionRuleTemplate[]>(`${base}/rule-templates`);
}

/** 新建归集规则。 */
export function createCostCollectionRule(input: FinanceCostCollectionRuleInput) {
  return api<FinanceCostCollectionRule>(`${base}/rules`, 'POST', input);
}

/** 修改归集规则。 */
export function updateCostCollectionRule(id: string, input: FinanceCostCollectionRuleInput) {
  return api<FinanceCostCollectionRule>(`${base}/rules/${id}`, 'PUT', input);
}

/** 费用池列表（真分页 + 真筛选 + 按币种分开的汇总）。 */
export function fetchCostCollectionPools(params: CostCollectionParams = {}) {
  return api<FinanceCostCollectionPoolPage>(
    `${base}/pools${query({
      poolKey: params.poolKey,
      dimension: params.targetDimension,
      currency: params.currency,
    })}`,
  );
}

/** 一个费用池的详情（池 + 它的归集条目）。 */
export function fetchCostCollectionPool(id: string) {
  return api<FinanceCostCollectionPoolDetail>(`${base}/pools/${id}`);
}

/** 归集明细列表（真分页 + 真筛选 + 汇总覆盖整个筛选结果集）。 */
export function fetchCostCollectionEntries(
  params: CostCollectionParams = {},
  page = 0,
  size = 50,
) {
  return api<FinanceCostCollectionEntryPage>(
    `${base}/entries${query({
      poolId: params.poolId,
      ruleId: params.ruleId,
      feeSource: params.feeSource,
      feeCategory: params.feeCategory,
      targetDimension: params.targetDimension,
      currency: params.currency,
      poolKey: params.poolKey,
      baseDateFrom: params.baseDateFrom,
      baseDateTo: params.baseDateTo,
      periodStart: params.periodStart,
      periodEnd: params.periodEnd,
      keyword: params.keyword,
      page,
      size,
    })}`,
  );
}

/** 一条归集条目的详情（条目 + 归集依据）。 */
export function fetchCostCollectionEntry(id: string) {
  return api<FinanceCostCollectionEntry>(`${base}/entries/${id}`);
}

/**
 * 撤掉一条归集条目（调整用）。
 *
 * <p>⚠️ **用 POST 而不是 DELETE**：finance 的代理出口刻意不导出 DELETE
 * （那条出口对全部 finance 路径生效，导出 DELETE 会把「能不能删」扩散到别的模块）。
 * 路径是本模块自己的 `entries/{id}/remove`。
 */
export function removeCostCollectionEntry(id: string) {
  return api<void>(`${base}/entries/${id}/remove`, 'POST');
}

/** 从 12.3.1.2 结算明细行归集平台费（按月）。 */
export function collectPlatformSettlementCost(ruleId: string, from?: string, to?: string) {
  return api<FinanceCostCollectionResult>(
    `${base}/collect/platform-settlement${query({ ruleId, from, to })}`,
    'POST',
  );
}

/** 从 14.3.1.1 活动报表归集广告费（按月）。基准日必须显式给出。 */
export function collectAdSpendCost(ruleId: string, baseDate: string) {
  return api<FinanceCostCollectionResult>(
    `${base}/collect/ad-spend${query({ ruleId, baseDate })}`,
    'POST',
  );
}

/** 从 10.2.1.1 运单运费登记归集物流费（按单）。 */
export function collectLogisticsCost(ruleId: string) {
  return api<FinanceCostCollectionResult>(`${base}/collect/logistics${query({ ruleId })}`, 'POST');
}

/** 从 10.4.1.1 头程发运单归集头程费（按 SKU）。 */
export function collectHeadMileCost(ruleId: string) {
  return api<FinanceCostCollectionResult>(`${base}/collect/head-mile${query({ ruleId })}`, 'POST');
}

/** 人工录入归集（订阅费 / 人员费用 / 办公费用）。 */
export function collectManualCost(input: FinanceCostCollectionManualInput) {
  return api<FinanceCostCollectionResult>(`${base}/collect/manual`, 'POST', input);
}

// ================================================================ 可读形态

/** 费用来源的可读名。 */
export function costFeeSourceLabel(source: string): string {
  switch (source) {
    case 'PLATFORM_SETTLEMENT':
      return '平台结算单';
    case 'AD_SPEND':
      return '广告花费';
    case 'SUBSCRIPTION':
      return '订阅';
    case 'LOGISTICS_WAYBILL':
      return '运单运费';
    case 'HEAD_MILE_SHIPMENT':
      return '头程发运单';
    case 'PERSONNEL':
      return '人员费用';
    case 'OFFICE':
      return '办公费用';
    default:
      return source;
  }
}

/** 费用项的可读名。 */
export function costFeeCategoryLabel(category: string): string {
  switch (category) {
    case 'PLATFORM_FEE':
      return '平台费';
    case 'AD_FEE':
      return '广告费';
    case 'SUBSCRIPTION_FEE':
      return '订阅费';
    case 'LOGISTICS_FEE':
      return '物流费';
    case 'HEAD_MILE_FEE':
      return '头程费';
    case 'PERSONNEL_COST':
      return '人员费用';
    case 'OFFICE_COST':
      return '办公费用';
    default:
      return category;
  }
}

/**
 * 分摊目标维度的可读名。
 *
 * <p>⚠️ 措辞刻意是「目标」而不是「已分摊」—— 本模块只声明下游要按什么维度分摊。
 */
export function costDimensionLabel(dimension: string): string {
  switch (dimension) {
    case 'MONTH':
      return '按月分摊';
    case 'ORDER':
      return '按单分摊';
    case 'SKU':
      return '按 SKU 分摊';
    case 'PERIOD':
      return '按期间分摊';
    default:
      return dimension;
  }
}

/** 归属期间口径的可读名。 */
export function costPeriodBasisLabel(basis: string): string {
  switch (basis) {
    case 'CALENDAR_MONTH':
      return '自然月';
    case 'ACCOUNTING_PERIOD':
      return '会计期间';
    default:
      return basis;
  }
}

/** 归集依据类型的可读名。 */
export function costBasisTypeLabel(type: string): string {
  switch (type) {
    case 'SETTLEMENT_LINE':
      return '结算明细行';
    case 'AD_REPORT_ROW':
      return '广告报表行';
    case 'WAYBILL_FREIGHT':
      return '运单运费登记';
    case 'FIRST_MILE_SHIPMENT':
      return '头程发运单';
    case 'MANUAL_DOCUMENT':
      return '人工单据';
    default:
      return type;
  }
}

/**
 * 金额的可读形态。
 *
 * <p>⚠️ **不在前端做算术**：数值原样透传（只做显示格式化）。
 */
export function formatCostAmount(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '—';
  return amount.toFixed(4);
}

/** 金额 + 币种的可读形态。 */
export function formatCostMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '—';
  return `${amount.toFixed(4)} ${currency ?? ''}`.trim();
}

/**
 * 归属期间的可读形态。
 *
 * <p>⚠️ 只做**拼接**不做日期差计算 —— 期间边界是服务端给的日历区间。
 */
export function formatCostPeriod(start: string | null | undefined, end: string | null | undefined): string {
  if (!start || !end) return '—';
  return `${start} ~ ${end}`;
}

/**
 * 分摊状态的可读形态。
 *
 * <p>本模块恒为「未分摊」（分摊属 12.4.2.2，未交付）。
 */
export function costAllocationStatusLabel(status: string): string {
  switch (status) {
    case 'UNALLOCATED':
      return '未分摊';
    default:
      return status;
  }
}
