import type {
  FinanceCostValuationCapabilities,
  FinanceCostValuationMethodInput,
  FinanceCostValuationMethodPage,
  FinanceCostValuationMethodRow,
  FinanceCostValuationOptions,
  FinanceCostValuationScopeOptions,
  FinanceCostValuationSpecifiedCostInput,
  FinanceCostValuationSpecifiedCostPage,
  FinanceCostValuationSpecifiedCostRow,
  FinanceCostValuationTrialPage,
  FinanceCostValuationTrialRequest,
  FinanceCostValuationTrialRow,
  FinanceCostValuationVersionComparison,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.4.1.1 计价方法配置的前端模型。
 *
 * <p>蓝图 12-finance.md：「配置成本计算方法（先进先出/移动加权平均/批次成本/指定成本法），<b>按法人或品类设置</b>，<b>确定生效时间</b>。」输入：「计价方法选择；生效时间；适用范围。」输出：「生效的成本方法配置。」关系：「上游 = 用户会计政策决定；下游 = 12.4.1.2、13 利润。<b>系统提供选项与试算，不替用户决定政策</b>；<b>切换方法需重算并留版本对比</b>。」
 *
 * <p><b>命名空间</b>：{@code finance/cost-valuation}，与 12.4.2.1 的 {@code finance/cost-collections}、
 * 12.3.1.3 的 {@code finance/deferred-settlement} <b>刻意不共用前缀</b> —— 那是别的模块的命名空间。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>系统提供选项，不替用户决定政策</b>：四个计价方法连同各自的<b>口径说明</b>摆出来，
 *       页面**不标任何「推荐方法」** —— 计价方法是租户自己的会计政策，
 *       系统替用户默认一个就是替用户记账。</li>
 *   <li><b>切换 = 追加版本</b>：页面上永远能看到「版本 N / 上一版是什么 / 差异在哪」，
 *       并且明确提示<b>需要 12.4.1.2 重算</b>（重算本身不在本模块）。</li>
 *   <li><b>算不出来 ≠ 0</b>：服务端给 {@code null} 的地方前端一律显示「—」，
 *       并把「为什么算不出来」（{@code gapNote}）原样显示出来。</li>
 *   <li><b>金额只原样展示</b>：差额、单位成本都由服务端算好，页面<b>不做任何业务算术</b>。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有配置过计价方法」。</li>
 * </ul>
 */

const base = 'finance/cost-valuation';

/**
 * 计价方法配置列表的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface CostValuationParams {
  scopeType?: string | undefined;
  scopeRefId?: string | undefined;
  valuationMethod?: string | undefined;
  status?: string | undefined;
  currency?: string | undefined;
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
export function fetchCostValuationCapabilities() {
  return api<FinanceCostValuationCapabilities>(`${base}/capabilities`);
}

/** 筛选项 + 四个计价方法选项（系统提供选项，不替用户决定政策）。 */
export function fetchCostValuationOptions() {
  return api<FinanceCostValuationOptions>(`${base}/options`);
}

/** 可用计价范围（法人 + 品类）：实时读 identity / catalog。 */
export function fetchCostValuationScopes() {
  return api<FinanceCostValuationScopeOptions>(`${base}/scopes`);
}

/** 计价方法配置列表（真分页 + 真筛选）。 */
export function fetchCostValuationMethods(params: CostValuationParams = {}) {
  return api<FinanceCostValuationMethodPage>(
    `${base}/methods${query({
      scopeType: params.scopeType,
      scopeRefId: params.scopeRefId,
      valuationMethod: params.valuationMethod,
      status: params.status,
      currency: params.currency,
      keyword: params.keyword,
    })}`,
  );
}

/** 登记 / 切换计价方法（同范围已有生效版本 ⇒ 追加新版本）。 */
export function switchCostValuationMethod(input: FinanceCostValuationMethodInput) {
  return api<FinanceCostValuationMethodRow>(`${base}/methods`, 'POST', input);
}

/** 版本历史（新到旧）。 */
export function fetchCostValuationVersions(scopeType: string, scopeRefId: string) {
  return api<FinanceCostValuationMethodRow[]>(
    `${base}/methods/versions${query({ scopeType, scopeRefId })}`,
  );
}

/** 版本对比（切换前 / 切换后）。 */
export function fetchCostValuationComparison(
  scopeType: string,
  scopeRefId: string,
  fromVersion: number,
  toVersion: number,
) {
  return api<FinanceCostValuationVersionComparison>(
    `${base}/methods/compare${query({ scopeType, scopeRefId, fromVersion, toVersion })}`,
  );
}

/** 指定单价列表（真分页 + 真筛选）。 */
export function fetchCostValuationSpecifiedCosts(params: { skuId?: string; currency?: string } = {}) {
  return api<FinanceCostValuationSpecifiedCostPage>(
    `${base}/specified-costs${query({ skuId: params.skuId, currency: params.currency })}`,
  );
}

/** 维护指定单价（同 SKU + 币种已有生效值 ⇒ 追加新版本）。 */
export function upsertCostValuationSpecifiedCost(input: FinanceCostValuationSpecifiedCostInput) {
  return api<FinanceCostValuationSpecifiedCostRow>(`${base}/specified-costs`, 'POST', input);
}

/** 发起一次试算：四种方法在同一批真实成本层上的结果并排给出。 */
export function runCostValuationTrial(input: FinanceCostValuationTrialRequest) {
  return api<FinanceCostValuationTrialRow>(`${base}/trials`, 'POST', input);
}

/** 试算列表（真分页 + 真筛选）。 */
export function fetchCostValuationTrials(params: { skuId?: string; scopeType?: string } = {}) {
  return api<FinanceCostValuationTrialPage>(
    `${base}/trials${query({ skuId: params.skuId, scopeType: params.scopeType })}`,
  );
}

/** 试算明细（表头 + 四个方法的结果）。 */
export function fetchCostValuationTrial(id: string) {
  return api<FinanceCostValuationTrialRow>(`${base}/trials/${id}`);
}

// ================================================================ 可读名

/** 计价方法的可读名（不认识的原样返回 —— 新增取值时页面不会显示 undefined）。 */
export function valuationMethodLabel(value: string | null | undefined): string {
  switch (value) {
    case 'FIFO':
      return '先进先出';
    case 'MOVING_AVERAGE':
      return '移动加权平均';
    case 'BATCH':
      return '批次成本';
    case 'SPECIFIED':
      return '指定成本法';
    default:
      return value ?? '';
  }
}

/** 适用范围的可读名。 */
export function valuationScopeTypeLabel(value: string | null | undefined): string {
  switch (value) {
    case 'LEGAL_ENTITY':
      return '按法人';
    case 'CATEGORY':
      return '按品类';
    default:
      return value ?? '';
  }
}

/** 配置状态的可读名。 */
export function valuationStatusLabel(value: string | null | undefined): string {
  switch (value) {
    case 'ACTIVE':
      return '生效中';
    case 'SUPERSEDED':
      return '已被取代';
    case 'CANCELLED':
      return '已作废';
    default:
      return value ?? '';
  }
}

/** 重算标记的可读名。 */
export function recalculationStatusLabel(value: string | null | undefined): string {
  switch (value) {
    case 'NOT_REQUIRED':
      return '无需重算';
    case 'PENDING':
      return '待重算（12.4.1.2）';
    default:
      return value ?? '';
  }
}

/**
 * 差额的措辞。
 *
 * <p>正数 = 这个方法**更贵**；负数 = 更便宜。页面直接展示这句话，
 * 免得看数字的人自己判断「贵了还是便宜了」。
 */
export function valuationDifferenceLabel(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  if (value > 0) return `更贵 ${formatValuationMoney(Math.abs(value))}`;
  if (value < 0) return `更便宜 ${formatValuationMoney(Math.abs(value))}`;
  return '与生效方法相同';
}

/**
 * 金额展示：服务端给 {@code null} 的显示「—」。
 *
 * <p>🔴 **算不出来不是 0** —— 页面显示 0.00 会被读成「成本是 0」。
 */
export function formatValuationMoney(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toFixed(4);
}

/** 数量展示（六位小数，与后端 NUMERIC(19,6) 一致）。 */
export function formatValuationQuantity(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toFixed(6);
}

/**
 * 生效区间的展示。
 *
 * <p>⚠️ 结束时间为空 = **一直生效**，不是「没填」。
 */
export function valuationEffectiveWindow(
  from: string | null | undefined,
  to: string | null | undefined,
): string {
  const start = from ? from.slice(0, 10) : '—';
  if (!to) return `${start} 起（一直生效）`;
  return `${start} → ${to.slice(0, 10)}`;
}

/**
 * 试算结果的排序：**不可比的排在最后**。
 *
 * <p>可比的结果之间按成本金额从低到高排（先看省钱的），
 * 不可比的（算不出结果）在最后并带上原因 —— 页面一眼能看出「哪个方法用不了」。
 */
export function sortTrialResults<
  T extends { comparable: boolean; costAmount: number | null; valuationMethod: string },
>(results: T[]): T[] {
  return [...results].sort((a, b) => {
    if (a.comparable !== b.comparable) return a.comparable ? -1 : 1;
    if (!a.comparable) return a.valuationMethod.localeCompare(b.valuationMethod);
    const left = a.costAmount ?? Number.POSITIVE_INFINITY;
    const right = b.costAmount ?? Number.POSITIVE_INFINITY;
    if (left !== right) return left - right;
    return a.valuationMethod.localeCompare(b.valuationMethod);
  });
}
