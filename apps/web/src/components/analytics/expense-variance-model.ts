/**
 * 13.4.2.2 费用差异 前端模型（卷 13「利润与经营分析」L3 13.4.2 成本拆解下的第二格）。
 *
 * <p>蓝图原文：「对比**预计费用与实际费用**的差异：平台费、物流费、广告费、头程分摊，
 * 按 SKU 与期间展示差异率，定位高估/低估的系统性偏差。」
 * 输入「测算费用（5.2.3）；实际费用（12.3.1.2）；时间范围。」
 * 输出「差异表 + 偏差趋势 + 规则调优建议。」
 *
 * <p><b>🔴 这个页面上最容易做丢的五件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「差异是 0」</b>。服务端把「只有一侧有数据」的行的
 *       {@code varianceAmount} / {@code varianceRate} 置为 {@code null}，这里渲染成
 *       「—」+ 原因，**绝不显示成 0.00 或 0%** —— 假 0 会被读成「预计和实际完全一致」。</li>
 *   <li><b>符号口径固定</b>：差异 = 实际 − 预计。差异为**负** ⇒ 实际比预计少 ⇒
 *       <b>预计高估</b>（规则配高了）；差异为**正** ⇒ 实际比预计多 ⇒ <b>预计低估</b>。
 *       蓝图那句「持续为负的差异说明费用规则配错了」就是在这个口径下成立的。</li>
 *   <li><b>多币种绝不相加</b>。每一行都带自己的币种，跨币种合计不存在。</li>
 *   <li><b>数据更新时间常驻</b>。卷 13 卷首加粗句「数据延迟要显示」—— 页面上任何时候都看得到
 *       「数据截至 X」，而不是只在加载完成后一闪而过。</li>
 *   <li><b>前端不做业务算术</b>。差异额、差异率、建议费率全部来自服务端
 *       （{@code BigDecimal} 定标），这里只做**展示格式化**（Intl 格式化 + 字符串拼接），
 *       不做任何加减乘除 —— 前端算一遍就会引入二进制浮点误差，
 *       而且会让「界面上的差异率」和「报表里的差异率」对不上。</li>
 * </ol>
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  ExpenseSkuVariancePage,
  ExpenseSkuVarianceRow,
  ExpenseVarianceCapabilities,
  ExpenseVarianceRecommendation,
  ExpenseVarianceResponse,
  ExpenseVarianceRow,
  ExpenseVarianceTrendPoint,
  UnmatchedActualRow,
} from '../../../../../packages/api-contracts/src/index';

export type {
  ExpenseSkuVariancePage,
  ExpenseSkuVarianceRow,
  ExpenseVarianceCapabilities,
  ExpenseVarianceRecommendation,
  ExpenseVarianceResponse,
  ExpenseVarianceRow,
  ExpenseVarianceTrendPoint,
  UnmatchedActualRow,
};

// ================================================================ 展示格式化

/** 金额刻度（与服务端 numeric(24,4) 对齐，展示时保留 2 位即可）。 */
const MONEY_DECIMALS = 2;

/** 百分比刻度（服务端已经算成百分数，例如 -15.0000 = -15%）。 */
const PERCENT_DECIMALS = 2;

/** 费率刻度（服务端 setScale(6)，展示时保留 6 位）。 */
const RATE_DECIMALS = 6;

function formatNumber(value: number, decimals: number): string {
  return value.toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}

/**
 * 金额 + 币种。
 *
 * <p>🔴 {@code null} ⇒ 「—」，**绝不用 0 顶替**。
 */
export function amountText(value: number | null, currency: string): string {
  if (value === null) return '—';
  return `${formatNumber(value, MONEY_DECIMALS)} ${currency}`;
}

/**
 * 差异率。
 *
 * <p>🔴 {@code null} ⇒ 「不可比」（**不是 0%**）：差异率的分母是预计费用，
 * 分母缺失（算不出预计值）或为 0 时差异率根本不存在。
 */
export function rateText(value: number | null): string {
  if (value === null) return '不可比';
  const sign = value > 0 ? '+' : '';
  return `${sign}${formatNumber(value, PERCENT_DECIMALS)}%`;
}

/**
 * 差异额（带符号，便于一眼看出高估 / 低估）。
 *
 * <p>🔴 {@code null} ⇒ 「—（算不出来）」。
 */
export function varianceText(value: number | null, currency: string): string {
  if (value === null) return '—（算不出来）';
  const sign = value > 0 ? '+' : '';
  return `${sign}${formatNumber(value, MONEY_DECIMALS)} ${currency}`;
}

// ================================================================ 偏差方向

/**
 * 偏差方向。
 *
 * <p>🔴 {@code 'unknown'} 表示**不可比**（算不出来），它与 {@code 'balanced'}（真的是持平）
 * 是两件事 —— 混成同一个颜色就是在骗人。
 */
export type BiasDirection = 'over' | 'under' | 'balanced' | 'unknown';

/**
 * 差异率 ⇒ 偏差方向（差异 = 实际 − 预计）。
 *
 * <p>差异为负 ⇒ 实际比预计少 ⇒ <b>预计高估</b>；差异为正 ⇒ 实际比预计多 ⇒ <b>预计低估</b>。
 */
export function biasDirection(rate: number | null): BiasDirection {
  if (rate === null) return 'unknown';
  if (rate < 0) return 'over';
  if (rate > 0) return 'under';
  return 'balanced';
}

/** 偏差方向的中文说明（把「高估 / 低估」和「为什么」一起说清楚）。 */
export function biasLabel(direction: BiasDirection): string {
  switch (direction) {
    case 'over':
      return '预计高估（实际比预计少 ⇒ 费用规则可能配高了）';
    case 'under':
      return '预计低估（实际比预计多 ⇒ 费用规则可能配低了）';
    case 'balanced':
      return '与预计一致';
    default:
      return '不可比（差异算不出来，不是 0%）';
  }
}

/** 偏差方向的短标签（表格里那一列）。 */
export function biasShortLabel(direction: BiasDirection): string {
  switch (direction) {
    case 'over':
      return '预计高估';
    case 'under':
      return '预计低估';
    case 'balanced':
      return '一致';
    default:
      return '不可比';
  }
}

/** 一行差异的可比性说明（**为什么**算不出来要写出来，不能只丢一个「—」）。 */
export function comparableNote(row: ExpenseVarianceRow): string {
  return row.comparable ? '可比' : row.note;
}

// ================================================================ 语义色（Badge tone）

/**
 * Badge 的 tone 取值（与 `@starcon/ui` 的 `Badge` 一致）。
 *
 * <p>本文件是纯函数层，不 import UI 包 —— 但 tone 的**取值集合**必须与 UI 一致，
 * 所以在这里显式列出来（多一个值就会在编译期报出来，而不是运行时悄悄变灰）。
 */
export type Tone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'brand';

/**
 * 偏差方向的语义色。
 *
 * <p>⚠️ **tone 跟语义挂钩，不跟颜色挂钩**：
 *
 * <ul>
 *   <li>预计高估 / 预计低估 —— 都是「费用规则可能配错了、要去校准」的信号 ⇒ warning / info；</li>
 *   <li>与预计一致 ⇒ success；</li>
 *   <li>不可比 ⇒ neutral（**不是 danger**：算不出来不是错误，是「这一格没数据」）。</li>
 * </ul>
 */
export function biasTone(direction: BiasDirection): Tone {
  switch (direction) {
    case 'over':
      return 'warning';
    case 'under':
      return 'info';
    case 'balanced':
      return 'success';
    default:
      return 'neutral';
  }
}

/** 建议方向的语义色（服务端词表）。 */
export function recommendationTone(direction: string): Tone {
  switch (direction) {
    case 'OVER_ESTIMATED':
      return 'warning';
    case 'UNDER_ESTIMATED':
      return 'info';
    case 'BALANCED':
      return 'success';
    default:
      return 'neutral';
  }
}

// ================================================================ 有实际、无预计

/** 「有实际、无预计」一行的金额文本（null ⇒ 「—」，不是 0）。 */
export function unmatchedAmountText(row: UnmatchedActualRow): string {
  return amountText(row.actualAmount, row.currency);
}

/** 「有实际、无预计」的原因（服务端给的原文，**不改写**）。 */
export function unmatchedReasonText(row: UnmatchedActualRow): string {
  return row.reason;
}

// ================================================================ 规则调优建议

/** 建议的偏差方向（服务端词表）。 */
export function recommendationDirectionLabel(direction: string): string {
  switch (direction) {
    case 'OVER_ESTIMATED':
      return '预计高估';
    case 'UNDER_ESTIMATED':
      return '预计低估';
    case 'BALANCED':
      return '偏差在阈值内';
    default:
      return direction;
  }
}

/**
 * 当前有效费率（预计 ÷ 基数）。
 *
 * <p>🔴 {@code null} ⇒ 「算不出来（没有基数）」，**不是 0%**。
 */
export function currentRateText(recommendation: ExpenseVarianceRecommendation): string {
  if (recommendation.currentRate === null) return '算不出来（没有销售额 / 数量基数）';
  return formatNumber(recommendation.currentRate, RATE_DECIMALS);
}

/**
 * 建议费率（按实际发生额反推）。
 *
 * <p>🔴 {@code null} ⇒ 没有基数 ⇒ 反推不出费率 ⇒ 只能给**定性**建议。
 */
export function suggestedRateText(recommendation: ExpenseVarianceRecommendation): string {
  if (recommendation.suggestedRate === null) return '算不出来（没有基数 ⇒ 只能给定性建议）';
  return formatNumber(recommendation.suggestedRate, RATE_DECIMALS);
}

/**
 * 实际隐含费率（实际 ÷ 基数）。
 *
 * <p>🔴 {@code null} ⇒ 没有基数 ⇒ 反推不出来，**不是 0**。
 */
export function impliedRateText(recommendation: ExpenseVarianceRecommendation): string {
  if (recommendation.impliedRate === null) return '算不出来（没有销售额 / 数量基数）';
  return formatNumber(recommendation.impliedRate, RATE_DECIMALS);
}

// ================================================================ 偏差趋势

/** 趋势上的一根线（一个费用类型 × 一个币种）。 */
export interface VarianceTrendSeries {
  key: string;
  feeType: string;
  feeTypeLabel: string;
  currency: string;
  points: { periodStart: string; periodEnd: string; rate: number }[];
}

/**
 * 把趋势点按「费用类型 × 币种」拆成线。
 *
 * <p>🔴 **不可比的期间没有点**（不是 0%）：曲线上「跳过去」和「掉到 0」是两件事。
 */
export function varianceTrendSeries(trend: ExpenseVarianceTrendPoint[]): VarianceTrendSeries[] {
  const series = new Map<string, VarianceTrendSeries>();
  for (const point of trend) {
    if (point.varianceRate === null) continue;
    const key = `${point.feeType}|${point.currency}`;
    const existing = series.get(key);
    if (existing) {
      existing.points.push({
        periodStart: point.periodStart,
        periodEnd: point.periodEnd,
        rate: point.varianceRate,
      });
    } else {
      series.set(key, {
        key,
        feeType: point.feeType,
        feeTypeLabel: point.feeTypeLabel,
        currency: point.currency,
        points: [
          {
            periodStart: point.periodStart,
            periodEnd: point.periodEnd,
            rate: point.varianceRate,
          },
        ],
      });
    }
  }
  return [...series.values()];
}

/** 一根线的取值范围（**没有点时返回 null**，不返回 0）。 */
export interface SeriesBounds {
  min: number;
  max: number;
}

/**
 * 一根线的取值范围。
 *
 * <p>⚠️ **范围里始终包含 0**：差异率有正有负，零线是「预计与实际一致」的分界，
 * 画图时必须看得见。
 */
export function seriesBounds(points: { rate: number }[]): SeriesBounds | null {
  if (points.length === 0) return null;
  let min = 0;
  let max = 0;
  for (const point of points) {
    if (point.rate < min) min = point.rate;
    if (point.rate > max) max = point.rate;
  }
  return { min, max };
}

// ================================================================ 数据更新时间

/**
 * 「数据截至 X」文案。
 *
 * <p>🔴 卷 13 卷首加粗句「**数据延迟要显示**」：用户必须一眼看出看到的是 T-1 还是实时。
 */
export function dataUpdatedText(response: ExpenseVarianceResponse | null): string {
  if (!response) return '';
  if (!response.dataUpdatedAt) return '数据更新时间：尚未投影任何费用事实';
  return `数据截至 ${response.dataUpdatedAt}`;
}

/** 「数据截至」这句话的口径说明（这个时刻**具体指什么**）。 */
export function dataUpdatedNote(response: ExpenseVarianceResponse | null): string {
  return response?.dataUpdatedNote ?? '';
}

// ================================================================ 维度与选项

/** 费用类型下拉（**空串 = 全部**，不是「不筛」以外的第三种含义）。 */
export const FEE_TYPE_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: '全部费用类型' },
  { value: 'PLATFORM_FEE', label: '平台费' },
  { value: 'LOGISTICS_FEE', label: '物流费' },
  { value: 'AD_FEE', label: '广告费' },
  { value: 'HEAD_FREIGHT', label: '头程分摊' },
];

/** 期间粒度下拉。 */
export const GRANULARITY_OPTIONS: { value: string; label: string }[] = [
  { value: 'DAY', label: '按日' },
  { value: 'WEEK', label: '按周' },
  { value: 'MONTH', label: '按月' },
];

/** 时间范围文本。 */
export function rangeText(from: string, to: string): string {
  return `${from} ~ ${to}`;
}

/** 默认窗口：最近 30 天（含今天）。 */
export function defaultRange(today: string): { from: string; to: string } {
  const end = new Date(`${today}T00:00:00Z`);
  const start = new Date(end.getTime() - 29 * 24 * 60 * 60 * 1000);
  return { from: start.toISOString().slice(0, 10), to: today };
}

/** 期间文本（逐日事实两列相同；窗口型事实不同）。 */
export function periodText(periodStart: string, periodEnd: string): string {
  return periodStart === periodEnd ? periodStart : `${periodStart} ~ ${periodEnd}`;
}

// ================================================================ 页面状态

/** 页面的三种状态分开说。 */
export type PageState = 'loading' | 'error' | 'empty' | 'ready';

/**
 * 页面状态判定。
 *
 * <p>🔴 **「读不到」与「没有数据」是两件事**：
 * 读不到（服务不可达 / 无权限）⇒ 错误态；读到了但这个窗口一格数据都没有 ⇒ 空态。
 * 两者都渲染成同一种空白，运维会以为「这个租户没生意」。
 */
export function pageState(response: ExpenseVarianceResponse | null, error: string): PageState {
  if (error) return 'error';
  if (!response) return 'loading';
  if (response.rows.length === 0 && response.unmatchedActuals.length === 0) return 'empty';
  return 'ready';
}

/** 空态文案。 */
export function emptyText(response: ExpenseVarianceResponse | null): string {
  if (!response) return '';
  if (response.rows.length === 0 && response.unmatchedActuals.length === 0) {
    return '这个时间窗口里没有任何费用事实。上游可能还没有同步，或刷新窗口不对。';
  }
  return '';
}

/** SKU 级差异的分页说明（真分页 + 真计数）。 */
export function skuPageNote(page: ExpenseSkuVariancePage | null): string {
  if (!page) return '';
  return `共 ${page.total} 条，当前第 ${page.page + 1} 页（每页 ${page.size} 条）。`;
}
