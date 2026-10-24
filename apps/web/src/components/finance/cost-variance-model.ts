import type {
  FinanceCostVarianceAlertPage,
  FinanceCostVarianceBaselineComparison,
  FinanceCostVarianceBaselinePage,
  FinanceCostVarianceBaselineRequest,
  FinanceCostVarianceBaselineRow,
  FinanceCostVarianceCapabilities,
  FinanceCostVarianceDriverPage,
  FinanceCostVarianceDriverRow,
  FinanceCostVarianceItemPage,
  FinanceCostVarianceItemRow,
  FinanceCostVarianceOptions,
  FinanceCostVarianceReportDetail,
  FinanceCostVarianceReportPage,
  FinanceCostVarianceReportRequest,
  FinanceCostVarianceReportRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.4.1.3 成本差异分析的前端模型。
 *
 * <p>蓝图 12-finance.md：「分析成本差异：实际成本 vs 预算/标准成本、采购价波动、头程成本波动、损耗差异、汇率影响，定位利润波动主因。」输入：「成本计算结果；预算或历史基线；价格与汇率历史。」输出：「差异分析报表 + 主因归因 + 异常提示。」关系：「上游 = 12.4.1.2；下游 = 13 分析、6 采购（价格谈判依据）。<b>利润下滑时先看成本差异</b>，比看销售额更能定位问题。」
 *
 * <p><b>命名空间</b>：{@code finance/cost-variance}，与 12.4.1.2 的 {@code finance/cost-calculation}、
 * 12.4.1.1 的 {@code finance/cost-valuation} <b>刻意不共用前缀</b> —— 那是别的模块的命名空间。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>算不出来 ≠ 0</b>：服务端给 {@code null} 的地方前端一律显示「—」，
 *       并把「为什么算不出来」（{@code comparableReason}）原样显示出来。
 *       🔴 <b>无基线的 SKU 的差异不是 0</b>，是<b>不可计算</b>。</li>
 *   <li><b>多币种绝不相加</b>：报表按币种分组渲染，组内金额只在同币种内相加；
 *       页面<b>不提供</b>「全部差异合计」这一格。</li>
 *   <li><b>五类差异不可相加</b>：汇率影响是总差异的<b>构成部分</b>，其余三类是
 *       <b>独立观察到的驱动因素</b>；页面分别呈现并给<b>主因</b>，
 *       不把五类加起来。</li>
 *   <li><b>金额只原样展示</b>：差额、单位成本、贡献占比都由服务端算好，
 *       页面<b>不做任何业务算术</b>。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有分析过」。</li>
 * </ul>
 */

const base = 'finance/cost-variance';

/** 基线列表的筛选条件。 */
export interface CostVarianceBaselineParams {
  baselineType?: string | undefined;
  dimension?: string | undefined;
  dimensionRefId?: string | undefined;
  currency?: string | undefined;
  status?: string | undefined;
}

/** 报表列表的筛选条件。 */
export interface CostVarianceReportParams {
  scopeType?: string | undefined;
  scopeRefId?: string | undefined;
  status?: string | undefined;
}

/** 明细列表的筛选条件。 */
export interface CostVarianceItemParams {
  reportId?: string | undefined;
  skuId?: string | undefined;
  driver?: string | undefined;
  currency?: string | undefined;
  comparability?: string | undefined;
}

/** 驱动因素列表的筛选条件。 */
export interface CostVarianceDriverParams {
  reportId?: string | undefined;
  currency?: string | undefined;
  driver?: string | undefined;
}

/** 异常提示列表的筛选条件。 */
export interface CostVarianceAlertParams {
  reportId?: string | undefined;
  code?: string | undefined;
  severity?: string | undefined;
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
export function fetchCostVarianceCapabilities() {
  return api<FinanceCostVarianceCapabilities>(`${base}/capabilities`);
}

/** 筛选项（基线类型 / 维度 / 币种 / 驱动因素 / 可比性 / 严重度）。 */
export function fetchCostVarianceOptions() {
  return api<FinanceCostVarianceOptions>(`${base}/options`);
}

/** 基线列表（真分页 + 真筛选）。 */
export function fetchCostVarianceBaselines(params: CostVarianceBaselineParams = {}) {
  return api<FinanceCostVarianceBaselinePage>(
    `${base}/baselines${query({
      baselineType: params.baselineType,
      dimension: params.dimension,
      dimensionRefId: params.dimensionRefId,
      currency: params.currency,
      status: params.status,
    })}`,
  );
}

/**
 * 登记 / 切换一版基线（= **追加新版本**，不是就地改）。
 *
 * <p>旧版本**不被改写**：它被收口生效区间并置为「已被接替」，
 * 历史差异分析仍按当时那版基线算。
 */
export function upsertCostVarianceBaseline(input: FinanceCostVarianceBaselineRequest) {
  return api<FinanceCostVarianceBaselineRow>(`${base}/baselines`, 'POST', input);
}

/** 版本历史（新到旧）。 */
export function fetchCostVarianceBaselineVersions(
  baselineType: string,
  dimension: string,
  dimensionKey: string,
  currency?: string | undefined,
) {
  return api<FinanceCostVarianceBaselineRow[]>(
    `${base}/baselines/versions${query({
      baselineType,
      dimension,
      dimensionKey,
      currency,
    })}`,
  );
}

/** 版本对比（切换前 / 切换后，逐字段给出差异）。 */
export function fetchCostVarianceBaselineComparison(
  baselineType: string,
  dimension: string,
  dimensionKey: string,
  currency: string,
  fromVersion: number,
  toVersion: number,
) {
  return api<FinanceCostVarianceBaselineComparison>(
    `${base}/baselines/compare${query({
      baselineType,
      dimension,
      dimensionKey,
      currency,
      fromVersion,
      toVersion,
    })}`,
  );
}

/**
 * 执行一次差异分析：12.4.1.2 的真实结转金额 → 五类差异 → 报表 + 归因 + 异常提示。
 *
 * <p>⚠️ 上游读不到**不会**让整张报表失败：只有受影响的那一类差异被标成
 * 「上游不可得」，其余照常出数。
 */
export function runCostVarianceAnalysis(input: FinanceCostVarianceReportRequest) {
  return api<FinanceCostVarianceReportDetail>(`${base}/reports`, 'POST', input);
}

/** 报表列表（真分页 + 真筛选）。 */
export function fetchCostVarianceReports(params: CostVarianceReportParams = {}) {
  return api<FinanceCostVarianceReportPage>(
    `${base}/reports${query({
      scopeType: params.scopeType,
      scopeRefId: params.scopeRefId,
      status: params.status,
    })}`,
  );
}

/** 报表明细（表头 + 按币种分组 + 每组的驱动因素与告警）。 */
export function fetchCostVarianceReport(id: string) {
  return api<FinanceCostVarianceReportDetail>(`${base}/reports/${id}`);
}

/** 明细列表（真分页 + 真筛选）：逐 SKU × 币种 × 驱动因素。 */
export function fetchCostVarianceItems(params: CostVarianceItemParams = {}) {
  return api<FinanceCostVarianceItemPage>(
    `${base}/items${query({
      reportId: params.reportId,
      skuId: params.skuId,
      driver: params.driver,
      currency: params.currency,
      comparability: params.comparability,
    })}`,
  );
}

/** 驱动因素列表（真分页 + 真筛选）：按币种分组 + **主因归因**。 */
export function fetchCostVarianceDrivers(params: CostVarianceDriverParams = {}) {
  return api<FinanceCostVarianceDriverPage>(
    `${base}/drivers${query({
      reportId: params.reportId,
      currency: params.currency,
      driver: params.driver,
    })}`,
  );
}

/** 异常提示列表（真分页 + 真筛选）。 */
export function fetchCostVarianceAlerts(params: CostVarianceAlertParams = {}) {
  return api<FinanceCostVarianceAlertPage>(
    `${base}/alerts${query({
      reportId: params.reportId,
      code: params.code,
      severity: params.severity,
    })}`,
  );
}

// ================================================================ 可读名

/** 驱动因素的可读名（五类差异）。 */
export function costVarianceDriverLabel(value: string | null | undefined): string {
  switch (value) {
    case 'STANDARD_COST':
      return '实际 vs 预算/标准成本';
    case 'PURCHASE_PRICE':
      return '采购价波动';
    case 'FIRST_MILE':
      return '头程成本波动';
    case 'SHRINKAGE':
      return '损耗差异';
    case 'FX':
      return '汇率影响';
    default:
      return value ?? '';
  }
}

/** 可比性的可读名。 */
export function costVarianceComparabilityLabel(value: string | null | undefined): string {
  switch (value) {
    case 'COMPARABLE':
      return '可比';
    case 'NO_BASELINE':
      return '无基线，不可比';
    case 'CURRENCY_MISMATCH':
      return '币种不一致，不可比';
    case 'UPSTREAM_UNAVAILABLE':
      return '上游不可得，不可计算';
    case 'NOT_APPLICABLE':
      return '不适用';
    default:
      return value ?? '';
  }
}

/** 告警严重度的可读名。 */
export function costVarianceSeverityLabel(value: string | null | undefined): string {
  switch (value) {
    case 'INFO':
      return '提示';
    case 'WARNING':
      return '警告';
    case 'CRITICAL':
      return '严重';
    default:
      return value ?? '';
  }
}

/** 基线类型的可读名。 */
export function costVarianceBaselineTypeLabel(value: string | null | undefined): string {
  switch (value) {
    case 'STANDARD_COST':
      return '标准成本';
    case 'BUDGET':
      return '预算';
    default:
      return value ?? '';
  }
}

/** 基线维度的可读名。 */
export function costVarianceDimensionLabel(value: string | null | undefined): string {
  switch (value) {
    case 'SKU':
      return '按 SKU';
    case 'CATEGORY':
      return '按类目';
    default:
      return value ?? '';
  }
}

/** 基线版本状态的可读名。 */
export function costVarianceBaselineStatusLabel(value: string | null | undefined): string {
  switch (value) {
    case 'ACTIVE':
      return '生效中';
    case 'SUPERSEDED':
      return '已被接替';
    case 'ARCHIVED':
      return '已撤下';
    default:
      return value ?? '';
  }
}

/** 报表状态的可读名。 */
export function costVarianceReportStatusLabel(value: string | null | undefined): string {
  switch (value) {
    case 'COMPLETED':
      return '全部可算';
    case 'PARTIAL':
      return '部分不可算';
    default:
      return value ?? '';
  }
}

/**
 * 金额展示：服务端给 {@code null} 的显示「—」。
 *
 * <p>🔴 **算不出来不是 0** —— 页面显示 0.0000 会被读成「差异是 0」。
 */
export function formatCostVarianceMoney(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toFixed(4);
}

/** 数量展示（六位小数，与后端 NUMERIC(19,6) 一致）。 */
export function formatCostVarianceQuantity(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toFixed(6);
}

/** 占比展示（百分比，两位小数）。 */
export function formatCostVarianceShare(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return `${(value * 100).toFixed(2)}%`;
}

/**
 * 差异的措辞。
 *
 * <p>正数 = <b>成本更高</b>；负数 = 成本更低。页面直接展示这句话，
 * 免得看数字的人自己判断「贵了还是便宜了」。
 */
export function costVarianceDirectionLabel(value: number | null | undefined): string {
  if (value === null || value === undefined) return '不可计算';
  if (value > 0) return `成本更高 ${formatCostVarianceMoney(Math.abs(value))}`;
  if (value < 0) return `成本更低 ${formatCostVarianceMoney(Math.abs(value))}`;
  return '与基线持平';
}

/**
 * 期间区间的展示。
 *
 * <p>⚠️ 期间是**左闭右开**的（起含、止不含），页面上要讲清，
 * 否则「1 月 1 日到 2 月 1 日」到底含不含 2 月 1 日那天的出库就说不清了。
 */
export function costVariancePeriodLabel(
  from: string | null | undefined,
  to: string | null | undefined,
): string {
  const start = from ? from.slice(0, 10) : '—';
  const end = to ? to.slice(0, 10) : '—';
  return `${start} 起 ~ ${end} 前`;
}

/**
 * 驱动因素的排序：**可比的按贡献排名在前，不可计算的排最后**。
 *
 * <p>🔴 不可计算的类别**不参与排名**（服务端不给 rank）—— 页面也不能自己
 * 给它编一个名次。
 */
export function sortCostVarianceDrivers(drivers: FinanceCostVarianceDriverRow[]) {
  return [...drivers].sort((a, b) => {
    const aRank = a.rank ?? Number.MAX_SAFE_INTEGER;
    const bRank = b.rank ?? Number.MAX_SAFE_INTEGER;
    if (aRank !== bRank) return aRank - bRank;
    return a.driver.localeCompare(b.driver);
  });
}

/**
 * 明细的排序：**不可比的排最后**。
 *
 * <p>可比较的先看（差异真的算得出来），不可比的排后面并带上原因 ——
 * 页面一眼能看出「哪些 SKU 的差异根本不可计算」。
 */
export function sortCostVarianceItems<T extends { comparability: string; driver: string; skuCode: string }>(
  items: T[],
): T[] {
  return [...items].sort((a, b) => {
    const aBad = a.comparability !== 'COMPARABLE';
    const bBad = b.comparability !== 'COMPARABLE';
    if (aBad !== bBad) return aBad ? 1 : -1;
    if (a.skuCode !== b.skuCode) return a.skuCode.localeCompare(b.skuCode);
    return a.driver.localeCompare(b.driver);
  });
}

/**
 * 一个币种分组里的**主因**（没有主因时返回空串）。
 *
 * <p>🔴 主因 = 该币种内按 |金额| 排名第一**且可计算**的那一类；
 * 全部为 0 或全都不可算时**没有主因** —— 页面不硬凑一个。
 */
export function costVariancePrimaryDriverLabel(
  drivers: FinanceCostVarianceDriverRow[],
): string {
  const primary = drivers.find((d) => d.isPrimary);
  if (!primary) return '';
  return `${primary.driverLabel ?? costVarianceDriverLabel(primary.driver)}（${formatCostVarianceMoney(
    primary.amount,
  )}）`;
}
