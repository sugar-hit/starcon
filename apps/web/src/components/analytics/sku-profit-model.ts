/**
 * 13.4.1.2 SKU 利润 前端模型（卷 13「利润与经营分析」L3 13.4.1 利润报表下的第二格）。
 *
 * <p>蓝图原文：「SKU 级利润明细：单品收入、采购成本、头程、平台费、物流费、广告费、退款损失、
 * 净利，支持查看构成瀑布与订单级明细钻取。」输入「SKU 维度；时间范围；成本分摊结果。」
 * 输出「SKU 利润表 + 瀑布图 + 订单明细钻取。」关系：上游 = 13.4.1.1、12.4.2.2；下游 = 5 定价、6 采购。
 *
 * <p><b>🔴 这个页面上最容易做丢的五件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「等于 0」</b>。服务端把拿不到的成本项与净利置为 {@code null}，
 *       这里渲染成「—」+ 原因，**绝不显示成 0.00** —— 假 0 会被读成「完全没花钱」。</li>
 *   <li><b>「不可归属到 SKU」与「缺这一项」分开说</b>。前者是「上游只分摊到店铺 / 订单，
 *       SKU 维度拿不到」（本模块**不重算分摊**），后者是「上游在 SKU 维度上有、这个 SKU 没有」。
 *       两者都让净利不可比，但**原因不同**，混成一句话会让人以为改口径就能解决。</li>
 *   <li><b>退款是逐笔枚举的事实</b>：这个 SKU 不在退款清单里 ⇒ 退款损失**真的是 0**，
 *       与成本项的「算不出来」是两种语义。</li>
 *   <li><b>多币种绝不相加</b>。每一行都带自己的币种，跨币种合计不存在。</li>
 *   <li><b>前端不做业务算术</b>。净利、净利率、成本合计全部来自服务端
 *       （{@code BigDecimal} 定标），这里只做**展示格式化**，不做任何加减乘除。</li>
 * </ol>
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  SkuProfitCapabilities,
  SkuProfitComponentAmount,
  SkuProfitOrderPage,
  SkuProfitOrderRow,
  SkuProfitResponse,
  SkuProfitRow,
  SkuProfitWaterfall,
  SkuProfitWaterfallStep,
} from '../../../../../packages/api-contracts/src/index';

export type {
  SkuProfitCapabilities,
  SkuProfitComponentAmount,
  SkuProfitOrderPage,
  SkuProfitOrderRow,
  SkuProfitResponse,
  SkuProfitRow,
  SkuProfitWaterfall,
  SkuProfitWaterfallStep,
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
 * 净利。
 *
 * <p>🔴 {@code null} ⇒ 「—（不可比）」：净利算不出来时**整格没有值**，
 * 而不是「净利是 0」。
 */
export function netProfitText(value: number | null, currency: string): string {
  if (value === null) return '—（不可比）';
  return `${formatNumber(value, MONEY_DECIMALS)} ${currency}`;
}

/**
 * 净利率。
 *
 * <p>🔴 {@code null} ⇒ 「不可比」（**不是 0%**）：分母（收入）缺失或为 0 时净利率不存在。
 */
export function netMarginText(value: number | null): string {
  if (value === null) return '不可比';
  return `${formatNumber(value, PERCENT_DECIMALS)}%`;
}

/** 成本项的取值文本（null ⇒ 「—」+ 原因，**不是 0**）。 */
export function componentText(component: SkuProfitComponentAmount, currency: string): string {
  if (!component.available || component.amount === null) return '—';
  return `${formatNumber(component.amount, MONEY_DECIMALS)} ${currency}`;
}

/** 成本项为什么没有值（服务端给的原文，**不改写**）。 */
export function componentReason(component: SkuProfitComponentAmount): string {
  return component.reason ?? '';
}

/** 成本项的名称（服务端给的原文）。 */
export function componentLabel(component: SkuProfitComponentAmount): string {
  return component.label;
}

/** 数量文本（null ⇒ 「—」）。 */
export function quantityText(value: number | null): string {
  if (value === null) return '—';
  return formatNumber(value, 3);
}

// ================================================================ 取值状态

/**
 * 取值状态的四种（**服务端给的原文**，这里只做展示映射）。
 *
 * <p>⚠️ **这四种不是同一种「没有」**：
 *
 * <ul>
 *   <li>{@code AVAILABLE} —— 有事实；
 *   <li>{@code MISSING} —— 上游在 SKU 维度上有这类成本，只是这个 SKU 没有 ⇒ 算不出来；
 *   <li>{@code NOT_ATTRIBUTABLE} —— 上游只分摊到店铺 / 订单 ⇒ SKU 维度不可归属；
 *   <li>{@code NOT_APPLICABLE} —— 本租户压根没有这类成本 ⇒ 不适用。
 * </ul>
 */
export function availabilityLabel(status: string): string {
  switch (status) {
    case 'AVAILABLE':
      return '有事实';
    case 'MISSING':
      return '缺这一项';
    case 'NOT_ATTRIBUTABLE':
      return '不可归属到 SKU';
    case 'NOT_APPLICABLE':
      return '不适用';
    default:
      return status;
  }
}

/** 取值状态的语义色（**tone 跟语义挂钩，不跟颜色挂钩**）。 */
export function availabilityTone(status: string): Tone {
  switch (status) {
    case 'AVAILABLE':
      return 'success';
    case 'MISSING':
      return 'warning';
    case 'NOT_ATTRIBUTABLE':
      return 'danger';
    case 'NOT_APPLICABLE':
      return 'neutral';
    default:
      return 'neutral';
  }
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
 * 净利是否可比的语义色。
 *
 * <ul>
 *   <li>盈利 ⇒ success；亏损 ⇒ danger；
 *   <li>不可比 ⇒ neutral（**不是 danger**：算不出来不是错误，是「这一格没数据」）。
 * </ul>
 */
export function netProfitTone(row: SkuProfitRow): Tone {
  if (!row.netProfitComparable || row.netProfit === null) return 'neutral';
  if (row.netProfit > 0) return 'success';
  if (row.netProfit < 0) return 'danger';
  return 'neutral';
}

/** 净利状态的短标签（表格里那一列）。 */
export function netProfitLabel(row: SkuProfitRow): string {
  if (!row.netProfitComparable || row.netProfit === null) return '不可比';
  if (row.netProfit > 0) return '盈利';
  if (row.netProfit < 0) return '亏损';
  return '持平';
}

// ================================================================ 经营模式

/**
 * 经营模式的中文名。
 *
 * <p>🔴 **未知模式原样显示服务端的码**：不猜一个中文名（猜错了就是在编造模式）。
 */
export function operatingModeLabel(mode: string, serverLabel: string): string {
  if (serverLabel && serverLabel !== mode) return serverLabel;
  switch (mode) {
    case 'SELF_FULFILLED':
      return '自发货';
    case 'FULL_CONSIGNMENT':
      return '全托管';
    case 'SEMI_CONSIGNMENT':
      return '半托管';
    case 'OVERSEAS_WAREHOUSE':
      return '平台仓';
    case 'LOCAL':
      return '本地';
    case 'UNKNOWN':
      return '未归属';
    default:
      return mode;
  }
}

/** 一个 SKU 的收入是否跨了多个经营模式（跨模式口径要标出来）。 */
export function isCrossMode(row: SkuProfitRow): boolean {
  return row.modeBreakdown.length > 1;
}

/** 跨模式口径的提示文案。 */
export function crossModeNote(row: SkuProfitRow): string {
  if (!isCrossMode(row)) return '';
  return `这个 SKU 在 ${row.modeBreakdown.length} 个经营模式下有销售，而 SKU 级成本不落到经营模式 ⇒ 这一行是跨模式口径。`;
}

// ================================================================ 数据更新时间

/**
 * 「数据截至 X」文案。
 *
 * <p>🔴 卷 13 卷首加粗句「**数据延迟要显示**」：用户必须一眼看出看到的是 T-1 还是实时。
 */
export function dataUpdatedText(response: SkuProfitResponse | null): string {
  if (!response) return '';
  if (!response.dataUpdatedAt) return '数据更新时间：尚未投影任何 SKU 级事实';
  return `数据截至 ${response.dataUpdatedAt}`;
}

/** 「数据截至」这句话的口径说明（这个时刻**具体指什么**）。 */
export function dataUpdatedNote(response: SkuProfitResponse | null): string {
  return response?.dataUpdatedNote ?? '';
}

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

// ================================================================ 页面状态

/** 页面的三种状态分开说。 */
export type PageState = 'loading' | 'error' | 'empty' | 'ready';

/**
 * 页面状态判定。
 *
 * <p>🔴 **「读不到」与「没有数据」是两件事**：
 * 读不到（服务不可达 / 无权限）⇒ 错误态；读到了但这个窗口一格数据都没有 ⇒ 空态。
 */
export function pageState(response: SkuProfitResponse | null, error: string): PageState {
  if (error) return 'error';
  if (!response) return 'loading';
  if (response.rows.length === 0) return 'empty';
  return 'ready';
}

/** 空态文案。 */
export function emptyText(response: SkuProfitResponse | null): string {
  if (!response) return '';
  if (response.rows.length === 0) {
    return '这个时间窗口里没有投影任何 SKU 级事实（不是「利润为 0」）。上游可能还没有同步，或刷新窗口不对。';
  }
  return '';
}

// ================================================================ 瀑布图

/** 瀑布一步的金额文本（**null ⇒ 「—」，不是 0**）。 */
export function waterfallStepText(step: SkuProfitWaterfallStep): string {
  if (!step.available || step.amount === null) return '—';
  return formatNumber(step.amount, MONEY_DECIMALS);
}

/** 瀑布一步的累计值文本（**null ⇒ 「—」**：前面有一步拿不到，后面就都算不出来）。 */
export function waterfallCumulativeText(step: SkuProfitWaterfallStep): string {
  if (step.cumulative === null) return '—';
  return formatNumber(step.cumulative, MONEY_DECIMALS);
}

/** 瀑布的净利文案（**null ⇒ 「—（不可比）」**）。 */
export function waterfallNetProfitText(waterfall: SkuProfitWaterfall | null): string {
  if (!waterfall) return '—（不可比）';
  if (!waterfall.netProfitComparable || waterfall.netProfit === null) return '—（不可比）';
  return formatNumber(waterfall.netProfit, MONEY_DECIMALS);
}

// ================================================================ 订单明细钻取

/** 下钻的分页说明（真分页 + 真计数）。 */
export function drilldownPageNote(page: SkuProfitOrderPage | null): string {
  if (!page) return '';
  return `共 ${page.total} 条，当前第 ${page.page + 1} 页（每页 ${page.size} 条）。`;
}

/** 下钻一行的归属摘要（**取服务端给的原值，不编名字**）。 */
export function orderScopeText(row: SkuProfitOrderRow): string {
  const parts: string[] = [];
  if (row.orderRef) parts.push(`订单 ${row.orderRef}`);
  if (!parts.length) parts.push('（这条事实没有订单归属）');
  return parts.join(' · ');
}

/** 下钻一行的经营模式（**未归属要标出来** —— 它不是一种经营模式）。 */
export function orderModeText(row: SkuProfitOrderRow): string {
  const label = operatingModeLabel(row.operatingMode, row.operatingModeLabel);
  return row.operatingMode === 'UNKNOWN' ? `${label}（未归属）` : label;
}

// ================================================================ 能力声明

/** 能力声明里「覆盖的成本项」的一段文本。 */
export function capabilitiesComponents(capabilities: SkuProfitCapabilities | null): string {
  return capabilities?.components.join('、') ?? '';
}

/** 能力声明里「确实算不出来」的一段文本。 */
export function capabilitiesNotDelivered(capabilities: SkuProfitCapabilities | null): string {
  return capabilities?.notDelivered.join('\n') ?? '';
}
