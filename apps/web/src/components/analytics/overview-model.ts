/**
 * 13.1.1.1 经营总览 前端模型（卷 13「利润与经营分析」L3 13.1.1 下的第一格）。
 *
 * <p>蓝图原文：「展示公司/法人/店铺维度的核心经营指标：销售额、订单量、销量、毛利额、毛利率、
 * 广告花费、净利、退货率、库存总额、应收余额，支持时间对比与环比同比，显示数据更新时间。」
 * 输入「报表投影数据；时间范围与维度选择；用户数据范围。」
 * 输出「指标卡 + 趋势图 + 异常提示 + 下钻入口 + 数据更新时间戳。」
 *
 * <p><b>🔴 这个页面上最容易做丢的五件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>「没有数据」≠「数据是 0」</b>。服务端 {@code availability='NO_DATA'} 的指标渲染成
 *       「—（该口径下没有事实数据）」，**不是** 0。销售额 0 与「这一格没算出来」在页面上
 *       长得一样，含义却相反 —— 假 0 会被读成「这个月没卖出东西」。</li>
 *   <li><b>不可比 ≠ 0%</b>。对比期的分母为 0 或缺数据 ⇒ {@code changeRate} 为 {@code null}，
 *       渲染成「不可比」并把原因说清楚，**绝不显示成 0%**。</li>
 *   <li><b>多币种绝不相加</b>。同一张卡上多个币种就分行显示；服务端给了
 *       {@code baseTotal} 才显示本位币合计，并**同时显示汇率与汇率时点**
 *       （「今天的汇率变了不能让上月的报表变」）。</li>
 *   <li><b>数据更新时间常驻</b>。卷 13 卷首加粗句「数据延迟要显示」—— 页面上任何时候都看得到
 *       「数据截至 X」，而不是只在加载完成后一闪而过。</li>
 *   <li><b>前端不做业务算术</b>。金额、比率、变化率全部来自服务端（{@code BigDecimal}），
 *       这里只做**展示格式化**（Intl 格式化 + 字符串拼接），不做任何加减乘除 ——
 *       前端算一遍就会引入二进制浮点误差，而且会让「界面上的毛利率」和「报表里的毛利率」对不上。</li>
 * </ol>
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  Comparison,
  CurrencyValue,
  DrilldownPage,
  DrilldownRow,
  MetricCard,
  MetricDefinitionView,
  OverviewResponse,
  TrendPoint,
} from '../../../../../packages/api-contracts/src/index';

export type {
  Comparison,
  CurrencyValue,
  DrilldownPage,
  DrilldownRow,
  MetricCard,
  MetricDefinitionView,
  OverviewResponse,
  TrendPoint,
};

// ================================================================ 展示格式化

/** 金额刻度（与服务端 numeric(24,4) 对齐，展示时保留 2 位即可）。 */
const MONEY_DECIMALS = 2;

/** 数量刻度（服务端 numeric(19,6)，展示时保留 3 位）。 */
const QUANTITY_DECIMALS = 3;

/** 百分比刻度（服务端已经算成百分数，例如 60.0000 = 60%）。 */
const PERCENT_DECIMALS = 2;

function formatNumber(value: number, decimals: number): string {
  return value.toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}

/**
 * 指标取值 → 展示文本。
 *
 * <p>🔴 {@code null} ⇒ 「—」并**由调用方补上原因**；绝不用 0 顶替。
 */
export function metricValueText(value: number | null, unit: string): string {
  if (value === null) return '—';
  if (unit === 'PERCENT') return `${formatNumber(value, PERCENT_DECIMALS)}%`;
  if (unit === 'QUANTITY') return formatNumber(value, QUANTITY_DECIMALS);
  if (unit === 'COUNT') return formatNumber(value, 0);
  return formatNumber(value, MONEY_DECIMALS);
}

/** 一个币种上的取值 + 币种（渲染成「1,234.00 USD」这种形态）。 */
export function currencyValueText(value: CurrencyValue, unit: string): string {
  if (value.value === null) return '—';
  return `${metricValueText(value.value, unit)} ${value.currency}`;
}

/**
 * 指标卡的可得性文案。
 *
 * <p>三种状态分开说：「有数据」/「该口径下没有事实数据」/「指标未在口径表登记」。
 */
export function availabilityText(card: MetricCard): string {
  if (card.availability === 'AVAILABLE') return '';
  if (card.availability === 'NOT_REGISTERED') return '该指标未在口径表登记';
  return '该口径下没有事实数据（不是 0）';
}

/** 指标卡上真正要展示的取值列表（多币种分行，不相加）。 */
export function cardValueRows(card: MetricCard): string[] {
  if (card.availability !== 'AVAILABLE') return [];
  return card.values.map((value) => currencyValueText(value, card.unit));
}

/** 本位币合计那一行（没有合计 ⇒ 没有这一行）。 */
export function baseTotalText(card: MetricCard): string {
  if (card.baseTotal === null || !card.baseCurrency) return '';
  const text = `${metricValueText(card.baseTotal, card.unit)} ${card.baseCurrency}`;
  if (card.baseRate === null || !card.baseRateAsOf) return text;
  // 🔴 汇率与时点必须同时出现：「折算用的是哪一档、哪一天」是这条数字的一部分。
  return `${text}（汇率 ${formatNumber(card.baseRate, 8)}，时点 ${card.baseRateAsOf}）`;
}

// ================================================================ 对比（环比 / 同比）

/** 对比的方向（供 StatCard 上色）。 */
export type ComparisonDirection = 'up' | 'down' | 'flat' | 'unknown';

/**
 * 对比方向。
 *
 * <p>🔴 {@code changeRate === null} ⇒ {@code 'unknown'} —— **不是** {@code 'flat'}。
 * 「算不出来」与「真的是持平」是两件事，混成同一个颜色就是在骗人。
 */
export function comparisonDirection(comparison: Comparison): ComparisonDirection {
  if (!comparison.comparable || comparison.changeRate === null) return 'unknown';
  if (comparison.changeRate > 0) return 'up';
  if (comparison.changeRate < 0) return 'down';
  return 'flat';
}

/** 对比变化的展示文本（null ⇒ 不可比 + 原因）。 */
export function comparisonText(comparison: Comparison): string {
  if (!comparison.comparable || comparison.changeRate === null) {
    return comparison.note || '不可比';
  }
  const sign = comparison.changeRate > 0 ? '+' : '';
  return `${sign}${formatNumber(comparison.changeRate, PERCENT_DECIMALS)}%`;
}

/** 对比窗口的文字（用于卡片脚注）。 */
export function comparisonWindowText(comparison: Comparison): string {
  return `${comparison.label} ${comparison.from} ~ ${comparison.to}`;
}

// ================================================================ 趋势

/** 趋势图上的一根线（一个指标 × 一个币种）。 */
export interface TrendSeries {
  metricCode: string;
  currency: string;
  points: { date: string; value: number }[];
}

/**
 * 把趋势点按「指标 × 币种」拆成线。
 *
 * <p>🔴 **没有事实的那一天没有点**（不是 0）：曲线上「跳过去」和「掉到 0」是两件事。
 */
export function trendSeries(points: TrendPoint[], metricCodes: string[]): TrendSeries[] {
  const series = new Map<string, TrendSeries>();
  for (const point of points) {
    if (!metricCodes.includes(point.metricCode)) continue;
    if (point.value === null) continue;
    const key = `${point.metricCode}|${point.currency}`;
    const existing = series.get(key);
    if (existing) {
      existing.points.push({ date: point.date, value: point.value });
    } else {
      series.set(key, {
        metricCode: point.metricCode,
        currency: point.currency,
        points: [{ date: point.date, value: point.value }],
      });
    }
  }
  return [...series.values()];
}

/** 折线的最大值（用来给 SVG 缩放）。**没有点时返回 null**，不返回 0。 */
export function seriesMax(series: TrendSeries): number | null {
  const first = series.points[0];
  if (!first) return null;
  return series.points.reduce((max, point) => (point.value > max ? point.value : max), first.value);
}

// ================================================================ 数据更新时间

/**
 * 「数据截至 X」文案。
 *
 * <p>🔴 卷 13 卷首加粗句「**数据延迟要显示**」：用户必须一眼看出看到的是 T-1 还是实时。
 */
export function dataUpdatedText(response: OverviewResponse | null): string {
  if (!response) return '';
  if (!response.dataUpdatedAt) return '数据更新时间：尚未投影任何数据';
  return `数据截至 ${response.dataUpdatedAt}`;
}

/** 「数据截至」这句话的口径说明（这个时刻**具体指什么**）。 */
export function dataUpdatedNote(response: OverviewResponse | null): string {
  return response?.dataUpdatedNote ?? '';
}

// ================================================================ 维度

/** 维度标签。 */
export function scopeLabel(scopeType: string): string {
  switch (scopeType) {
    case 'LEGAL_ENTITY':
      return '法人维度';
    case 'SHOP':
      return '店铺维度';
    default:
      return '公司维度';
  }
}

/** 可选的维度（前端下拉用；服务端会因为「维度与 id 不匹配」直接 400）。 */
export const SCOPE_OPTIONS: { value: string; label: string }[] = [
  { value: 'COMPANY', label: '公司维度' },
  { value: 'LEGAL_ENTITY', label: '法人维度' },
  { value: 'SHOP', label: '店铺维度' },
];

// ================================================================ 时间范围

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

// ================================================================ 口径目录

/**
 * 派生型指标的口径说明（分子 ÷ 分母）。
 *
 * <p>🔴 **口径写在卡片上**，不是写在代码注释里 —— 跨部门沟通时「毛利率是哪个除哪个」
 * 必须是同一份定义。
 */
export function definitionFormula(definition: MetricDefinitionView): string {
  if (definition.aggregation === 'DERIVED_DIFF') {
    return `${definition.numeratorMetricCode} − ${definition.denominatorMetricCode}`;
  }
  if (definition.aggregation === 'DERIVED_RATIO') {
    return `${definition.numeratorMetricCode} ÷ ${definition.denominatorMetricCode}`;
  }
  if (definition.aggregation === 'LAST') return '窗口内最近一天的快照';
  return '窗口内求和';
}

/** 聚合方式的中文标签。 */
export function aggregationLabel(aggregation: string): string {
  switch (aggregation) {
    case 'SUM':
      return '窗口内求和';
    case 'LAST':
      return '取最近一天的快照';
    case 'DERIVED_DIFF':
      return '派生差（分子 − 分母）';
    case 'DERIVED_RATIO':
      return '派生比（分子 ÷ 分母）';
    default:
      return aggregation;
  }
}

// ================================================================ 下钻

/** 下钻行的展示文本。 */
export function drilldownRowText(row: DrilldownRow): string {
  if (row.value === null) return '—';
  return `${formatNumber(row.value, MONEY_DECIMALS)} ${row.currency}`;
}

/** 下钻的来源类型标签（哪个订单 / 哪个 SKU / 哪个活动 / 哪张应收 / 哪条库存）。 */
export function drilldownSourceLabel(sourceKind: string): string {
  switch (sourceKind) {
    case 'ORDER':
      return '订单';
    case 'SKU':
      return 'SKU';
    case 'CAMPAIGN':
      return '广告活动';
    case 'RECEIVABLE':
      return '应收单';
    case 'STOCK':
      return '库存行';
    default:
      return sourceKind;
  }
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
export function pageState(response: OverviewResponse | null, error: string): PageState {
  if (error) return 'error';
  if (!response) return 'loading';
  if (response.cards.length === 0) return 'empty';
  const anyValue = response.cards.some((card) => card.availability === 'AVAILABLE');
  return anyValue ? 'ready' : 'empty';
}

/** 空态文案（区分「这个租户还没有投影过任何事实」）。 */
export function emptyText(response: OverviewResponse | null): string {
  if (!response) return '';
  if (response.cards.length === 0) {
    return '这个租户还没有登记指标口径，也没有投影过任何事实。';
  }
  return '这个时间窗口里没有任何指标有数据。上游可能还没有同步，或刷新窗口不对。';
}
