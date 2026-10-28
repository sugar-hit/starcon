/**
 * 13.4.2.1 成本结构 前端模型（卷 13「利润与经营分析」L3 13.4.2 成本拆解下的第一格）。
 *
 * <p>蓝图原文：「分析成本构成占比：采购/头程/平台费/物流/广告/退货/其他，
 * 按 SKU、店铺、类目、时间维度拆解，看成本结构变化趋势。」
 * 输入「成本分摊结果；历史基线。」输出「占比结构图 + 趋势 + 异常提示。」
 *
 * <p><b>🔴 这个页面上最容易做丢的五件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「占比是 0」</b>。服务端把分母缺失 / 历史不足 / 没有事实时的
 *       {@code share}、{@code previousShare}、{@code baselineShare}、{@code deltaVsPrevious}
 *       置为 {@code null}，这里渲染成「—」+ 原因，**绝不显示成 0.00% 或 0%** ——
 *       假 0 会被读成「这一类成本没有花钱」。</li>
 *   <li><b>占比的分母口径写死在页面上</b>。「占总成本」与「占收入」是两个口径，
 *       页面顶部常驻服务端给的 {@code denominatorNote}，两种口径不可混着读。</li>
 *   <li><b>多币种绝不相加</b>。每一行都带自己的币种，跨币种合计不存在。</li>
 *   <li><b>数据更新时间常驻</b>。卷 13 卷首加粗句「数据延迟要显示」—— 页面上任何时候都看得到
 *       「数据截至 X」，而不是只在加载完成后一闪而过。</li>
 *   <li><b>前端不做业务算术</b>。占比、环比差、同比差、基线占比全部来自服务端
 *       （{@code BigDecimal} 定标），这里只做**展示格式化**（Intl 格式化 + 字符串拼接），
 *       不做任何加减乘除 —— 前端算一遍就会引入二进制浮点误差，
 *       而且会让「界面上的占比」和「报表里的占比」对不上。</li>
 * </ol>
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  CostStructureAnomaly,
  CostStructureCapabilities,
  CostStructureResponse,
  CostStructureRow,
  CostStructureRule,
  CostStructureTrendPoint,
} from '../../../../../packages/api-contracts/src/index';

export type {
  CostStructureAnomaly,
  CostStructureCapabilities,
  CostStructureResponse,
  CostStructureRow,
  CostStructureRule,
  CostStructureTrendPoint,
};

// ================================================================ 展示格式化

/** 金额刻度（与服务端 numeric(24,4) 对齐，展示时保留 2 位即可）。 */
const MONEY_DECIMALS = 2;

/** 百分比刻度（服务端已经算成百分数，例如 -15.0000 = -15%）。 */
const PERCENT_DECIMALS = 2;

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
 * 占比。
 *
 * <p>🔴 {@code null} ⇒ 「—」（**不是 0%**）：占比的分母缺失或为 0 时占比根本不存在。
 */
export function shareText(value: number | null): string {
  if (value === null) return '—';
  return `${formatNumber(value, PERCENT_DECIMALS)}%`;
}

/**
 * 占比差（百分点，环比 / 同比）。
 *
 * <p>🔴 {@code null} ⇒ 「不可比」（**不是 0 个百分点**）：任一侧不可比时差值不存在。
 */
export function deltaText(value: number | null): string {
  if (value === null) return '不可比';
  const sign = value > 0 ? '+' : '';
  return `${sign}${formatNumber(value, PERCENT_DECIMALS)} 个百分点`;
}

/** 结构条（占比条）的宽度百分比（**只做展示钳位**，不做算术）。 */
export function shareBarWidth(value: number | null): number {
  if (value === null) return 0;
  if (value < 0) return 0;
  if (value > 100) return 100;
  return value;
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
 * 一行的语义色。
 *
 * <p>⚠️ **tone 跟语义挂钩，不跟颜色挂钩**：
 *
 * <ul>
 *   <li>异常 ⇒ danger（这是要盯的信号）；</li>
 *   <li>占比不可比 ⇒ neutral（**不是 danger**：算不出来不是错误，是「这一格没数据」）；</li>
 *   <li>正常 ⇒ success。</li>
 * </ul>
 */
export function rowTone(row: CostStructureRow): Tone {
  if (!row.comparable) return 'neutral';
  return row.anomaly ? 'danger' : 'success';
}

/** 行的短标签（表格里那一列）。 */
export function rowStatusLabel(row: CostStructureRow): string {
  if (!row.comparable) return '不可比';
  return row.anomaly ? '异常' : '正常';
}

/** 异常方向的中文说明。 */
export function anomalyDirectionLabel(direction: string): string {
  switch (direction) {
    case 'UP':
      return '占比突然上升';
    case 'DOWN':
      return '占比突然下降';
    case 'BOTH':
      return '占比跳变';
    default:
      return direction;
  }
}

/** 阈值口径的中文说明。 */
export function thresholdModeLabel(mode: string): string {
  switch (mode) {
    case 'FIXED':
      return '固定百分点';
    case 'STDDEV':
      return '基线标准差的若干倍';
    default:
      return mode;
  }
}

/** 生效阈值的文案（null ⇒ 判不了，不是 0）。 */
export function effectiveThresholdText(anomaly: CostStructureAnomaly): string {
  if (anomaly.effectiveThreshold === null) return '判不了（没有生效阈值）';
  return `${formatNumber(anomaly.effectiveThreshold, PERCENT_DECIMALS)} 个百分点`;
}

// ================================================================ 趋势

/** 趋势上的一根线（一个成本类别 × 一个币种）。 */
export interface StructureTrendSeries {
  key: string;
  costCategory: string;
  costCategoryLabel: string;
  currency: string;
  points: { periodStart: string; periodEnd: string; share: number }[];
}

/**
 * 把趋势点按「成本类别 × 币种」拆成线。
 *
 * <p>🔴 **不可比的期间没有点**（不是 0%）：曲线上「跳过去」和「掉到 0」是两件事。
 */
export function structureTrendSeries(
  trend: CostStructureTrendPoint[],
): StructureTrendSeries[] {
  const series = new Map<string, StructureTrendSeries>();
  for (const point of trend) {
    if (point.share === null) continue;
    const key = `${point.costCategory}|${point.currency}`;
    const existing = series.get(key);
    if (existing) {
      existing.points.push({
        periodStart: point.periodStart,
        periodEnd: point.periodEnd,
        share: point.share,
      });
    } else {
      series.set(key, {
        key,
        costCategory: point.costCategory,
        costCategoryLabel: point.costCategoryLabel,
        currency: point.currency,
        points: [
          {
            periodStart: point.periodStart,
            periodEnd: point.periodEnd,
            share: point.share,
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
 * <p>⚠️ **范围里始终包含 0**：占比有高有低，零线是「结构没变」的基准，画图时必须看得见。
 */
export function seriesBounds(points: { share: number }[]): SeriesBounds | null {
  if (points.length === 0) return null;
  let min = 0;
  let max = 0;
  for (const point of points) {
    if (point.share < min) min = point.share;
    if (point.share > max) max = point.share;
  }
  return { min, max };
}

// ================================================================ 数据更新时间

/**
 * 「数据截至 X」文案。
 *
 * <p>🔴 卷 13 卷首加粗句「**数据延迟要显示**」：用户必须一眼看出看到的是 T-1 还是实时。
 */
export function dataUpdatedText(response: CostStructureResponse | null): string {
  if (!response) return '';
  if (!response.dataUpdatedAt) return '数据更新时间：尚未投影任何成本事实';
  return `数据截至 ${response.dataUpdatedAt}`;
}

/** 「数据截至」这句话的口径说明（这个时刻**具体指什么**）。 */
export function dataUpdatedNote(response: CostStructureResponse | null): string {
  return response?.dataUpdatedNote ?? '';
}

// ================================================================ 维度与选项

/** 归属维度下拉（**空串 = 全部维度**）。 */
export const DIMENSION_OPTIONS: { value: string; label: string }[] = [
  { value: 'ALL', label: '全部（不拆维度）' },
  { value: 'SKU', label: '按 SKU' },
  { value: 'SHOP', label: '按店铺' },
  { value: 'CATEGORY', label: '按类目' },
  { value: 'PLATFORM', label: '按平台' },
];

/** 分母口径下拉（**口径必须写在页面上**）。 */
export const DENOMINATOR_OPTIONS: { value: string; label: string }[] = [
  { value: 'TOTAL_COST', label: '总成本口径（占成本合计）' },
  { value: 'REVENUE', label: '收入口径（占销售额）' },
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
 * 读不到（服务不可达 / 无权限）⇒ 错误态；读到了但这个窗口一格成本事实都没有 ⇒ 空态。
 * 两者都渲染成同一种空白，运维会以为「这个租户没生意」。
 */
export function pageState(response: CostStructureResponse | null, error: string): PageState {
  if (error) return 'error';
  if (!response) return 'loading';
  if (response.rows.length === 0) return 'empty';
  return 'ready';
}

/** 空态文案。 */
export function emptyText(response: CostStructureResponse | null): string {
  if (!response) return '';
  if (response.rows.length === 0) {
    return '这个时间窗口里没有任何成本事实。上游可能还没有同步，或刷新窗口不对。';
  }
  return '';
}
