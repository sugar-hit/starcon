/**
 * 13.4.1.1 多模式利润报表 前端模型（卷 13「利润与经营分析」L3 13.4.1 利润报表下的第一格）。
 *
 * <p>蓝图原文：「按经营模式（自发货/全托管/半托管/平台仓）分别出利润报表，支持按天/月、
 * 按 MSKU/SKU/SPU/店铺/订单多维度核算。」输入「成本数据（12.4）、费用数据（12.3/12.4）、
 * 订单收入；模式标记（2.3.2.1）；汇率快照。」输出「利润报表（收入 - 各成本费用 = 毛利）
 * + 多维下钻 + 口径说明。」
 *
 * <p><b>🔴 这个页面上最容易做丢的五件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>不同模式的利润含义完全不同</b> ⇒ 模式**分行展示、绝不合并**，
 *       也不提供「全部模式合计」—— 混在一起会得出错误结论。</li>
 *   <li><b>「算不出来」≠「等于 0」</b>。服务端把算不出来的成本项与毛利置为 {@code null}，
 *       这里渲染成「—」+ 原因，**绝不显示成 0.00** —— 假 0 会被读成「完全没花钱」。</li>
 *   <li><b>多币种绝不相加</b>。每一行都带自己的币种，跨币种合计不存在。</li>
 *   <li><b>数据更新时间常驻</b>。卷 13 卷首加粗句「数据延迟要显示」—— 页面上任何时候都看得到
 *       「数据截至 X」，而不是只在加载完成后一闪而过。</li>
 *   <li><b>前端不做业务算术</b>。毛利、毛利率、成本合计全部来自服务端
 *       （{@code BigDecimal} 定标），这里只做**展示格式化**，不做任何加减乘除。</li>
 * </ol>
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  ProfitComponentAmount,
  ProfitDrilldownPage,
  ProfitDrilldownRow,
  ProfitModeView,
  ProfitRefreshResult,
  ProfitReportCapabilities,
  ProfitReportResponse,
  ProfitReportRow,
} from '../../../../../packages/api-contracts/src/index';

export type {
  ProfitComponentAmount,
  ProfitDrilldownPage,
  ProfitDrilldownRow,
  ProfitModeView,
  ProfitRefreshResult,
  ProfitReportCapabilities,
  ProfitReportResponse,
  ProfitReportRow,
};

// ================================================================ 展示格式化

/** 金额刻度（与服务端 numeric(24,4) 对齐，展示时保留 2 位即可）。 */
const MONEY_DECIMALS = 2;

/** 百分比刻度（服务端已经算成百分数，例如 -15.0000 = -15%）。 */
const PERCENT_DECIMALS = 2;

/** 汇率刻度（服务端 numeric(19,8)）。 */
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
 * 毛利。
 *
 * <p>🔴 {@code null} ⇒ 「—（不可比）」：毛利算不出来时**整格没有值**，
 * 而不是「毛利是 0」。
 */
export function grossProfitText(value: number | null, currency: string): string {
  if (value === null) return '—（不可比）';
  return `${formatNumber(value, MONEY_DECIMALS)} ${currency}`;
}

/**
 * 毛利率。
 *
 * <p>🔴 {@code null} ⇒ 「不可比」（**不是 0%**）：分母（收入）缺失或为 0 时毛利率不存在。
 */
export function grossMarginText(value: number | null): string {
  if (value === null) return '不可比';
  return `${formatNumber(value, PERCENT_DECIMALS)}%`;
}

/** 成本项的取值文本（null ⇒ 「—」+ 原因，**不是 0**）。 */
export function componentText(component: ProfitComponentAmount, currency: string): string {
  if (!component.available || component.amount === null) return '—';
  return `${formatNumber(component.amount, MONEY_DECIMALS)} ${currency}`;
}

/** 成本项为什么没有值（服务端给的原文，**不改写**）。 */
export function componentReason(component: ProfitComponentAmount): string {
  return component.reason ?? '';
}

/** 成本项的名称（服务端给的原文）。 */
export function componentLabel(component: ProfitComponentAmount): string {
  return component.label;
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
 * 毛利是否可比的语义色。
 *
 * <p>⚠️ **tone 跟语义挂钩，不跟颜色挂钩**：
 *
 * <ul>
 *   <li>盈利 ⇒ success；亏损 ⇒ danger；</li>
 *   <li>不可比 ⇒ neutral（**不是 danger**：算不出来不是错误，是「这一格没数据」）。</li>
 * </ul>
 */
export function grossProfitTone(row: ProfitReportRow): Tone {
  if (!row.grossProfitComparable || row.grossProfit === null) return 'neutral';
  if (row.grossProfit > 0) return 'success';
  if (row.grossProfit < 0) return 'danger';
  return 'neutral';
}

/** 毛利状态的短标签（表格里那一列）。 */
export function grossProfitLabel(row: ProfitReportRow): string {
  if (!row.grossProfitComparable || row.grossProfit === null) return '不可比';
  if (row.grossProfit > 0) return '盈利';
  if (row.grossProfit < 0) return '亏损';
  return '持平';
}

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

/** 经营模式是不是「未归属」（**未归属不是一种经营模式**，页面上要标出来）。 */
export function isUnattributed(mode: string): boolean {
  return mode === 'UNKNOWN';
}

/** 经营模式清单里的说明。 */
export function modeNote(mode: ProfitModeView): string {
  return mode.note;
}

// ================================================================ 汇率快照

/**
 * 本位币合计的文本（**没有汇率快照就没有合计**）。
 *
 * <p>🔴 {@code null} ⇒ 「—」：**不是 0**（拿不到汇率就折算不出本位币金额）。
 */
export function baseAmountText(value: number | null, currency: string | null): string {
  if (value === null || !currency) return '—';
  return `${formatNumber(value, MONEY_DECIMALS)} ${currency}`;
}

/** 汇率文本（**汇率与时点必须一起显示**）。 */
export function baseRateText(rate: number | null, asOf: string | null): string {
  if (rate === null) return '—';
  const stamp = asOf ? `（${asOf}）` : '';
  return `${formatNumber(rate, RATE_DECIMALS)}${stamp}`;
}

// ================================================================ 数据更新时间

/**
 * 「数据截至 X」文案。
 *
 * <p>🔴 卷 13 卷首加粗句「**数据延迟要显示**」：用户必须一眼看出看到的是 T-1 还是实时。
 */
export function dataUpdatedText(response: ProfitReportResponse | null): string {
  if (!response) return '';
  if (!response.dataUpdatedAt) return '数据更新时间：尚未投影任何利润事实';
  return `数据截至 ${response.dataUpdatedAt}`;
}

/** 「数据截至」这句话的口径说明（这个时刻**具体指什么**）。 */
export function dataUpdatedNote(response: ProfitReportResponse | null): string {
  return response?.dataUpdatedNote ?? '';
}

// ================================================================ 维度与选项

/** 核算维度下拉（**空串 = 全部**，不是「不筛」以外的第三种含义）。 */
export const DIMENSION_OPTIONS: { value: string; label: string }[] = [
  { value: 'MODE', label: '经营模式' },
  { value: 'SHOP', label: '店铺' },
  { value: 'ORDER', label: '订单' },
  { value: 'SKU', label: 'SKU' },
  { value: 'SPU', label: 'SPU' },
  { value: 'MSKU', label: 'MSKU' },
];

/** 期间粒度下拉（按天 / 按月）。 */
export const GRANULARITY_OPTIONS: { value: string; label: string }[] = [
  { value: 'DAY', label: '按日' },
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

/** 一行的期间标签（行上给的是桶的起止）。 */
export function rowPeriodText(row: ProfitReportRow): string {
  return periodText(row.periodStart, row.periodEnd);
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
export function pageState(response: ProfitReportResponse | null, error: string): PageState {
  if (error) return 'error';
  if (!response) return 'loading';
  if (response.rows.length === 0) return 'empty';
  return 'ready';
}

/** 空态文案。 */
export function emptyText(response: ProfitReportResponse | null): string {
  if (!response) return '';
  if (response.rows.length === 0) {
    return '这个时间窗口里没有投影任何利润事实（不是「利润为 0」）。上游可能还没有同步，或刷新窗口不对。';
  }
  return '';
}

/** 下钻的分页说明（真分页 + 真计数）。 */
export function drilldownPageNote(page: ProfitDrilldownPage | null): string {
  if (!page) return '';
  return `共 ${page.total} 条，当前第 ${page.page + 1} 页（每页 ${page.size} 条）。`;
}

/** 下钻一行的维度摘要（**取服务端给的原值，不编名字**）。 */
export function drilldownScopeText(row: ProfitDrilldownRow): string {
  const parts: string[] = [];
  if (row.shopCode) parts.push(`店铺 ${row.shopCode}`);
  if (row.skuCode) parts.push(`SKU ${row.skuCode}`);
  if (row.spuCode) parts.push(`SPU ${row.spuCode}`);
  if (row.mskuCode) parts.push(`MSKU ${row.mskuCode}`);
  if (row.orderRef) parts.push(`订单 ${row.orderRef}`);
  if (parts.length === 0) return '（本维度没有更细的归属）';
  return parts.join(' · ');
}
