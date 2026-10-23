import type {
  FinanceFundAnalysisCapabilities,
  FinanceFundAnalysisOptions,
  FinanceFundCurrencyDistribution,
  FinanceFundExposureMeasureResult,
  FinanceFundExposureReport,
  FinanceFundExposureSnapshotPage,
  FinanceFundFlowTrend,
  FinanceFundLiquidityAlertReport,
  FinanceFundLiquidityThresholdConfig,
  FinanceFundLiquidityThresholdConfigInput,
  FinanceFundLiquidityThresholdInput,
  FinanceFundPaymentConcentrationReport,
  FinanceFundTurnoverReport,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.7.2.1 资金分析的前端模型。
 *
 * <p>蓝图 12-finance.md：「资金分析：现金流入流出趋势、各币种资金分布与汇率敞口、资金周转天数、付款集中度与流动性预警。」输入：「资金流水；应收应付账期；汇率数据。」输出：「资金报表 + 汇率敞口测算 + 流动性预警。」
 *
 * <p><b>命名空间</b>：{@code finance/fund-analysis}，与 12.1.1.1 的 {@code finance/receivables}
 * （应收单管理）、12.1.1.2 的 {@code finance/receivable-aging}（应收账龄）
 * **刻意不共用前缀** —— 那是别的模块的命名空间，不要互相调用。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>金额只原样展示</b>：占比、敏感度、集中度都由**服务端**算好，
 *       页面**不做任何业务算术**（USD 与 EUR 加起来不是任何一个金额）。</li>
 *   <li><b>币种分开</b>：每一处汇总都按币种渲染，**绝不**把不同币种摆进同一列相加。</li>
 *   <li><b>实际 ≠ 计划</b>：页面上「实际流入 / 预期流入 / 计划流出」三列**各有各的口径**，
 *       标题就写清楚，不许让人把「计划流出」读成「已付」。</li>
 *   <li><b>缺口如实显示</b>：12.7.1.1 账户管理 / 12.7.1.2 银行对账未交付 ⇒
 *       账户级余额与银行流水**不可用**，页面顶部就把这条显示出来，
 *       免得有人以为「账户余额是 0」。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有资金流水」。</li>
 * </ul>
 */

const base = 'finance/fund-analysis';

/**
 * 资金分析的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface FundAnalysisParams {
  currency?: string | undefined;
  baseCurrency?: string | undefined;
  from?: string | undefined;
  to?: string | undefined;
  granularity?: string | undefined;
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
export function fetchFundAnalysisCapabilities() {
  return api<FinanceFundAnalysisCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（币种 + 本位币候选，全部来自真实数据）。 */
export function fetchFundAnalysisOptions() {
  return api<FinanceFundAnalysisOptions>(`${base}/options`);
}

/** 现金流入流出趋势：实际流入 / 预期流入 / 计划流出三个口径并列。 */
export function fetchFundFlowTrend(params: FundAnalysisParams = {}) {
  return api<FinanceFundFlowTrend>(
    `${base}/cash-flow-trend${query({
      currency: params.currency,
      from: params.from,
      to: params.to,
      granularity: params.granularity,
    })}`,
  );
}

/** 各币种资金分布（四路真实聚合，按币种分开）。 */
export function fetchFundCurrencyDistribution(currency?: string) {
  return api<FinanceFundCurrencyDistribution>(
    `${base}/currency-distribution${query({ currency })}`,
  );
}

/** 汇率敞口测算（实时，不落库）。 */
export function fetchFundExposure(params: FundAnalysisParams = {}) {
  return api<FinanceFundExposureReport>(
    `${base}/exposure${query({ baseCurrency: params.baseCurrency, currency: params.currency })}`,
  );
}

/** 汇率敞口测算并落成快照（把这次用的汇率与生效日一起钉住）。 */
export function measureFundExposure(params: FundAnalysisParams = {}) {
  return api<FinanceFundExposureMeasureResult>(
    `${base}/exposure/measure${query({ baseCurrency: params.baseCurrency, currency: params.currency })}`,
    'POST',
  );
}

/** 敞口快照历史（真分页）。 */
export function fetchFundExposureSnapshots(page = 0, size = 20) {
  return api<FinanceFundExposureSnapshotPage>(`${base}/exposure/snapshots${query({ page, size })}`);
}

/** 资金周转天数（按币种分开）。 */
export function fetchFundTurnoverDays(params: FundAnalysisParams = {}) {
  return api<FinanceFundTurnoverReport>(
    `${base}/turnover-days${query({ currency: params.currency, from: params.from, to: params.to })}`,
  );
}

/** 付款集中度（口径 = 计划付款）。 */
export function fetchFundPaymentConcentration(params: FundAnalysisParams = {}) {
  return api<FinanceFundPaymentConcentrationReport>(
    `${base}/payment-concentration${query({
      currency: params.currency,
      from: params.from,
      to: params.to,
      granularity: params.granularity,
    })}`,
  );
}

/** 流动性预警（四条规则跑在真实聚合上）。 */
export function fetchFundLiquidityAlerts(params: FundAnalysisParams = {}) {
  return api<FinanceFundLiquidityAlertReport>(
    `${base}/liquidity-alerts${query({ from: params.from, to: params.to })}`,
  );
}

/** 生效中的流动性预警阈值（租户配的，或蓝图默认档）。 */
export function fetchFundLiquidityThresholds() {
  return api<FinanceFundLiquidityThresholdConfig>(`${base}/thresholds`);
}

/**
 * 配置流动性预警阈值（**整体替换**）。
 *
 * <p>⚠️ 服务端会整体校验：四条规则必须**全部**给出（不想启用某条就把 enabled 置为 false），
 * 少一条会被拒绝并说清原因。传空列表 = 回到蓝图默认档。
 */
export function updateFundLiquidityThresholds(thresholds: FinanceFundLiquidityThresholdInput[]) {
  return api<FinanceFundLiquidityThresholdConfig>(`${base}/thresholds`, 'PUT', {
    thresholds,
  } as FinanceFundLiquidityThresholdConfigInput);
}

// ================================================================ 可读形态

/** 时间粒度的可读名。 */
export function fundGranularityLabel(granularity: string): string {
  switch (granularity) {
    case 'DAY':
      return '按日';
    case 'WEEK':
      return '按周';
    case 'MONTH':
      return '按月';
    default:
      return granularity;
  }
}

/** 敞口方向的可读名（服务端已给 label，这里只兜底）。 */
export function fundExposureDirectionLabel(direction: string, fallback?: string): string {
  switch (direction) {
    case 'LONG_ASSET':
      return '多外币资产';
    case 'SHORT_ASSET':
      return '多外币负债';
    case 'FLAT':
      return '无净敞口';
    default:
      return fallback ?? direction;
  }
}

/** 预警严重度的可读名。 */
export function fundSeverityLabel(severity: string): string {
  switch (severity) {
    case 'CRITICAL':
      return '严重';
    case 'WARN':
      return '警告';
    case 'INFO':
      return '提示';
    default:
      return severity;
  }
}

/** 阈值来源的可读名。 */
export function fundThresholdSourceLabel(source: string): string {
  switch (source) {
    case 'TENANT_CONFIG':
      return '租户自配';
    case 'BLUEPRINT_DEFAULT':
      return '蓝图默认';
    default:
      return source;
  }
}

/**
 * 金额的可读形态。
 *
 * <p>⚠️ **不在前端做算术**：数值原样透传（只做显示格式化）。
 */
export function formatFundAmount(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '—';
  return amount.toFixed(4);
}

/** 金额 + 币种的可读形态。 */
export function formatFundMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '—';
  return `${amount.toFixed(4)} ${currency ?? ''}`.trim();
}

/**
 * 占比的可读形态。
 *
 * <p>⚠️ 这里做的是**单位换算**（比例 → 百分数），不是业务算术：
 * 分子与分母都在服务端算好了，页面只把 0.6667 显示成 66.67%。
 * 分母为 0 时服务端给 `null`（「没有逾期」≠「占比 0」）—— 这里显示「—」。
 */
export function formatFundShare(ratio: number | null | undefined): string {
  if (ratio === null || ratio === undefined) return '—';
  return `${(ratio * 100).toFixed(2)}%`;
}

/**
 * 天数的可读形态。
 *
 * <p>⚠️ 天数由**服务端**算好（UTC 日历日口径），页面不自己算日期差 ——
 * 换一条时区就会算出一套不同的天数。
 */
export function formatFundDays(days: number | null | undefined): string {
  if (days === null || days === undefined) return '—';
  return `${days.toFixed(4)} 天`;
}

/** 敞口集中度的可读形态（服务端算好的占比）。 */
export function formatFundExposureShare(ratio: number | null | undefined): string {
  if (ratio === null || ratio === undefined) return '—';
  return `${(ratio * 100).toFixed(2)}%`;
}
