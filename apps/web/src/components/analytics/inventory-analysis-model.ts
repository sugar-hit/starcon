/**
 * 13.5.1.1 库存分析 前端模型（卷 13「利润与经营分析」L3 13.5.1 库存分析下的第一格）。
 *
 * <p>蓝图原文：「库存分析报表：库存金额结构、周转率、库龄分布、缺货与超储、ABC 分类、呆滞占比，
 * 按仓库/类目/店铺拆解。」输入「库存数据（7）；销售数据；盘点历史。」
 * 输出「库存分析报表 + 图表 + 下钻。」
 *
 * <p><b>🔴 这个页面上最容易做丢的五件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「数值是 0」</b>。服务端把「没有维护成本」「分母为 0」「没有期初快照」
 *       这几种情形的值置为 {@code null}，这里渲染成「—」+ 原因，**绝不用 0 顶替** ——
 *       「库存金额 0 元」会被读成「这些货不占资金」，那比报错危险得多。</li>
 *   <li><b>周转率按成本口径算</b>。页面顶部常驻服务端的 {@code caliberNote}，
 *       明确写着分子是**销售出库成本**、分母是**成本口径的库存金额**，**没有用售价**。</li>
 *   <li><b>多币种绝不相加</b>。每一行带自己的币种；跨币种的合计只在**恰好一个币种**时显示，
 *       否则显示「—（跨币种不可加）」。</li>
 *   <li><b>数据更新时间常驻</b>。卷 13 卷首加粗句「数据延迟要显示」—— 页面上任何时候都看得到
 *       「数据截至 X」，而不是只在加载完成后一闪而过。</li>
 *   <li><b>前端不做业务算术</b>。金额、占比、周转率、周转天数、ABC 分类全部来自服务端
 *       （{@code BigDecimal} 定标），这里只做**展示格式化**，不做任何加减乘除 ——
 *       前端算一遍就会引入二进制浮点误差，而且会让「界面上的占比」和「报表里的占比」对不上。</li>
 * </ol>
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  InventoryAbcResponse,
  InventoryAbcRow,
  InventoryAnalysisCapabilities,
  InventoryAgingResponse,
  InventoryDrilldownResponse,
  InventoryStockStatusResponse,
  InventoryStocktakeResponse,
  InventoryStructureResponse,
  InventoryTurnoverResponse,
} from '../../../../../packages/api-contracts/src/index';

export type {
  InventoryAbcResponse,
  InventoryAbcRow,
  InventoryAnalysisCapabilities,
  InventoryAgingResponse,
  InventoryDrilldownResponse,
  InventoryStockStatusResponse,
  InventoryStocktakeResponse,
  InventoryStructureResponse,
  InventoryTurnoverResponse,
};

// ================================================================ 展示格式化

/** 金额刻度（与服务端 numeric(24,4) 对齐，展示时保留 2 位即可）。 */
const MONEY_DECIMALS = 2;

/** 百分比刻度（服务端已经算成百分数，例如 74.0741 = 74.07%）。 */
const PERCENT_DECIMALS = 2;

/** 数量刻度（服务端 numeric(19,6)，展示时保留 3 位）。 */
const QUANTITY_DECIMALS = 3;

/** 比率刻度（周转率，服务端已定标到 4 位小数）。 */
const RATE_DECIMALS = 4;

function formatNumber(value: number, decimals: number): string {
  return value.toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}

/**
 * 金额 + 币种。
 *
 * <p>🔴 {@code null} ⇒ 「—」，**绝不用 0 顶替**：没有维护成本的 SKU 的库存金额是
 * 「算不出来」，写成 0 元会被读成「这些货不占资金」。
 */
export function amountText(value: number | null, currency: string | null): string {
  if (value === null) return '—';
  return `${formatNumber(value, MONEY_DECIMALS)} ${currency ?? ''}`.trim();
}

/**
 * 占比（百分数）。
 *
 * <p>🔴 {@code null} ⇒ 「—（不可比）」，**不是 0%**：分母缺失或为 0 时占比根本不存在。
 */
export function shareText(value: number | null): string {
  if (value === null) return '—';
  return `${formatNumber(value, PERCENT_DECIMALS)}%`;
}

/**
 * 周转率（次）。
 *
 * <p>🔴 {@code null} ⇒ 「—（不可比）」，**不是 0 次**：分母算不出来时周转率不存在。
 */
export function rateText(value: number | null): string {
  if (value === null) return '—';
  return `${formatNumber(value, RATE_DECIMALS)} 次`;
}

/** 数量（件）。 */
export function quantityText(value: number | null): string {
  if (value === null) return '—';
  return formatNumber(value, QUANTITY_DECIMALS);
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
 * <p>本文件是纯函数层，不 import UI 包 —— 但 tone 的**取值集合**必须与 UI 一致。
 */
export type Tone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'brand';

/** ABC 类别 → tone。 */
export function abcTone(abcClass: string): Tone {
  switch (abcClass) {
    case 'A':
      return 'danger';
    case 'B':
      return 'warning';
    case 'C':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 一行周转率的 tone（**算不出来不是错误**，是「这一格没数据」）。 */
export function turnoverTone(comparable: boolean): Tone {
  return comparable ? 'success' : 'neutral';
}

/** 缺货 / 超储的 tone。 */
export function stockStatusTone(zeroStock: number, overstock: number): Tone {
  if (zeroStock > 0) return 'danger';
  if (overstock > 0) return 'warning';
  return 'success';
}

// ================================================================ 口径文案

/** 库存口径的中文说明（**成本口径 / 期末口径 / 都没有**）。 */
export function caliberLabel(caliber: string): string {
  switch (caliber) {
    case 'AVERAGE':
      return '平均库存（期初 + 期末）÷ 2';
    case 'CLOSING':
      return '期末库存（没有期初快照时的退而求其次口径）';
    case 'NONE':
      return '没有库存快照';
    default:
      return caliber;
  }
}

/** 呆滞档位的中文说明。 */
export function slowMovingStatusLabel(status: string): string {
  switch (status) {
    case 'OBSOLETE':
      return '呆滞';
    case 'SLOW_MOVING':
      return '滞销';
    case 'OBSOLETE_AND_UP':
      return '呆滞及以上';
    default:
      return status;
  }
}

/**
 * 覆盖率文案（**没有维护成本的 SKU 有多少**）。
 *
 * <p>🔴 这一句必须出现在页面上：否则用户会把「已知部分合计」当成全量金额。
 */
export function coverageText(skuCount: number, quantity: number | null): string {
  if (skuCount <= 0) return '';
  const qty = quantity === null ? '—' : formatNumber(quantity, QUANTITY_DECIMALS);
  return `另有 ${skuCount} 个 SKU 没有维护成本单价（在手 ${qty}），它们的金额是「—」，不在任何合计里。`;
}

// ================================================================ 维度与选项

/** 拆解维度下拉。 */
export const DIMENSION_OPTIONS: { value: string; label: string }[] = [
  { value: 'ALL', label: '全部（不拆维度）' },
  { value: 'WAREHOUSE', label: '按仓库' },
  { value: 'CATEGORY', label: '按类目' },
  { value: 'SHOP', label: '按店铺' },
  { value: 'SKU', label: '按 SKU' },
];

/** 期间粒度下拉。 */
export const GRANULARITY_OPTIONS: { value: string; label: string }[] = [
  { value: 'DAY', label: '按日' },
  { value: 'WEEK', label: '按周' },
  { value: 'MONTH', label: '按月' },
];

// ================================================================ 时间与页面状态

/** 窗口文本。 */
export function rangeText(from: string, to: string): string {
  return `${from} ~ ${to}`;
}

/** 默认窗口：最近 30 天（含今天）。 */
export function defaultRange(today: string): { from: string; to: string } {
  const end = new Date(`${today}T00:00:00Z`);
  const start = new Date(end.getTime() - 29 * 24 * 60 * 60 * 1000);
  return { from: start.toISOString().slice(0, 10), to: today };
}

/** 页面的三种状态分开说。 */
export type PageState = 'loading' | 'error' | 'empty' | 'ready';

/**
 * 页面状态判定。
 *
 * <p>🔴 **「读不到」与「没有数据」是两件事**：读不到 ⇒ 错误态；读到了但一格事实都没有 ⇒ 空态。
 */
export function pageState(response: { rows: unknown[] } | null, error: string): PageState {
  if (error) return 'error';
  if (!response) return 'loading';
  if (response.rows.length === 0) return 'empty';
  return 'ready';
}

/** 空态文案。 */
export function emptyText(response: { rows: unknown[] } | null): string {
  if (!response) return '';
  if (response.rows.length === 0) {
    return '这个时间窗口里没有任何库存事实。上游可能还没有同步，或刷新窗口不对。';
  }
  return '';
}

/**
 * 「数据截至」这句话（**常驻显示**）。
 *
 * <p>🔴 没有投影过时显示「尚未投影任何库存事实」—— **不是**「数据截至 今天」：
 * 后者会让人以为「今天的数据已经在库里了」，而真相是「一格都还没有」。
 */
export function dataUpdatedText(response: { dataUpdatedAt: string | null } | null): string {
  if (!response) return '';
  if (!response.dataUpdatedAt) return '数据截至：尚未投影任何库存事实';
  return `数据截至 ${response.dataUpdatedAt}`;
}

/** 「数据截至」这句话的口径说明（这个时刻**具体指什么**）。 */
export function dataUpdatedNote(response: { dataUpdatedNote: string } | null): string {
  return response?.dataUpdatedNote ?? '';
}

/**
 * 跨币种合计的提示（**没有跨币种合计**这件事必须说在页面上）。
 */
export function crossCurrencyNote(
  totals: { currency: string }[],
  totalCurrency: string | null,
): string {
  if (totals.length <= 1) return '';
  if (totalCurrency) return '';
  return `本次结果含 ${totals.length} 个币种，**跨币种的合计不存在**（绝不跨币种相加）。`;
}
