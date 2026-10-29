/**
 * 13.5.2.1 采购分析 前端模型（卷 13「利润与经营分析」L3 13.5.2 采购分析下的第一格）。
 *
 * <p>蓝图原文：「采购分析：采购金额趋势与占比、供应商集中度、价格波动、到货准时率、
 * 采购周期、采购成本占销售额比。」输入「采购数据（6）；供应商评级。」
 * 输出「采购报表 + 趋势 + 供应商对比。」
 *
 * <p><b>🔴 这个页面上最容易做丢的五件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「数值是 0」</b>。服务端把「没有金额」「分母为 0」「没有到货记录」
 *       这几种情形的值置为 {@code null}，这里渲染成「—（暂无数据）」+ 中性配色，
 *       **绝不用 0 顶替** —— 「采购金额 0 元」会被读成「这段时间没花钱」，
 *       而真相是「这一格没算出来」。</li>
 *   <li><b>供应商集中度必须带风险信号</b>。蓝图加粗句：「供应商集中度是供应链风险指标，
 *       单一供应商占比过高会在断供时直接击穿业务。」⇒ 页面给的是**风险等级 + 风险说明**
 *       （哪一家、占比多少、断供时哪一档采购额会中断），不是一个中性的百分比。</li>
 *   <li><b>多币种绝不相加</b>。每一行带自己的币种；跨币种的「总计」只在**恰好一个币种**时显示，
 *       否则显示「—（跨币种不可加）」。</li>
 *   <li><b>数据更新时间常驻</b>。卷 13 卷首加粗句「数据延迟要显示」—— 页面上任何时候都看得到
 *       「数据截至 X」，而不是只在加载完成后一闪而过。</li>
 *   <li><b>前端不做业务算术</b>。金额、占比、比率、周期全部来自服务端（{@code BigDecimal} 定标），
 *       这里只做**展示格式化**，不做任何加减乘除 —— 前端算一遍就会引入二进制浮点误差，
 *       而且会让「界面上的占比」和「报表里的占比」对不上。</li>
 * </ol>
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  ProcurementAnalysisCapabilities,
  ProcurementAnalysisRule,
  ProcurementConcentrationResponse,
  ProcurementCostToSalesResponse,
  ProcurementCycleResponse,
  ProcurementDrilldownResponse,
  ProcurementOnTimeResponse,
  ProcurementPriceVarianceResponse,
  ProcurementSupplierComparisonResponse,
  ProcurementTrendResponse,
} from '../../../../../packages/api-contracts/src/index';

export type {
  ProcurementAnalysisCapabilities,
  ProcurementAnalysisRule,
  ProcurementConcentrationResponse,
  ProcurementCostToSalesResponse,
  ProcurementCycleResponse,
  ProcurementDrilldownResponse,
  ProcurementOnTimeResponse,
  ProcurementPriceVarianceResponse,
  ProcurementSupplierComparisonResponse,
  ProcurementTrendResponse,
};

// ================================================================ 展示格式化

/** 金额刻度（与服务端 numeric(24,4) 对齐，展示时保留 2 位即可）。 */
const MONEY_DECIMALS = 2;

/** 百分比刻度（服务端已经算成百分数，例如 74.4186 = 74.42%）。 */
const PERCENT_DECIMALS = 2;

/** 数量刻度（服务端 numeric(19,6)，展示时保留 3 位）。 */
const QUANTITY_DECIMALS = 3;

/** 天数刻度（服务端已定标到 4 位小数，展示时保留 1 位）。 */
const DAYS_DECIMALS = 1;

function formatNumber(value: number, decimals: number): string {
  return value.toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}

/**
 * 金额 + 币种。
 *
 * <p>🔴 {@code null} ⇒ 「—」，**绝不用 0 顶替**：没有金额的到货流水是
 * 「算不出来」，写成 0 元会被读成「这批货不要钱」。
 */
export function amountText(value: number | null, currency: string | null): string {
  if (value === null) return '—';
  return `${formatNumber(value, MONEY_DECIMALS)} ${currency ?? ''}`.trim();
}

/**
 * 占比（百分数）。
 *
 * <p>🔴 {@code null} ⇒ 「—（暂无数据）」，**不是 0%**：分母缺失或为 0 时占比根本不存在。
 * 界面上它必须与真的 0% **长得不一样** —— 否则「没有采购额」会被读成「占比 0%」。
 */
export function percentText(value: number | null): string {
  if (value === null) return '—（暂无数据）';
  return `${formatNumber(value, PERCENT_DECIMALS)}%`;
}

/**
 * 比率（百分数，可正可负 —— 价格波动会跌）。
 *
 * <p>🔴 {@code null} ⇒ 「—（暂无数据）」，**不是 0%**。
 */
export function changeText(value: number | null): string {
  if (value === null) return '—（暂无数据）';
  const sign = value > 0 ? '+' : '';
  return `${sign}${formatNumber(value, PERCENT_DECIMALS)}%`;
}

/** 数量（件）。 */
export function quantityText(value: number | null): string {
  if (value === null) return '—';
  return formatNumber(value, QUANTITY_DECIMALS);
}

/**
 * 天数（采购周期）。
 *
 * <p>🔴 {@code null} ⇒ 「—（暂无数据）」，**不是 0 天** —— 「没有到货记录」与
 * 「当天到货」是两件事，混成一个 0 会让采购员以为供应商随叫随到。
 */
export function daysText(value: number | null): string {
  if (value === null) return '—（暂无数据）';
  return `${formatNumber(value, DAYS_DECIMALS)} 天`;
}

/** 单价（金额 / 数量口径）。 */
export function priceText(value: number | null, currency: string | null): string {
  if (value === null) return '—';
  return `${formatNumber(value, MONEY_DECIMALS)} ${currency ?? ''}`.trim();
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

/**
 * 集中度风险等级 → tone。
 *
 * <p>🔴 「算不出来」用 **neutral**（中性）而不是 warning ——
 * 「这一格没数据」是**中性事实**，不是异常；用警告色会把它读成「出问题了」。
 */
export function riskTone(riskLevel: string | null | undefined): Tone {
  switch (riskLevel) {
    case 'HIGH':
      return 'danger';
    case 'MEDIUM':
      return 'warning';
    case 'LOW':
      return 'success';
    default:
      return 'neutral';
  }
}

/** 集中度风险等级 → 中文说明。 */
export function riskLabel(riskLevel: string | null | undefined): string {
  switch (riskLevel) {
    case 'HIGH':
      return '高风险';
    case 'MEDIUM':
      return '中风险';
    case 'LOW':
      return '低风险';
    default:
      return '暂无数据';
  }
}

/** 准时率 → tone（**没有分母 ⇒ 中性**，不是「差」）。 */
export function onTimeTone(rate: number | null): Tone {
  if (rate === null) return 'neutral';
  if (rate >= 90) return 'success';
  if (rate >= 70) return 'warning';
  return 'danger';
}

/** 采购周期 → tone（**没有样本 ⇒ 中性**）。 */
export function cycleTone(days: number | null): Tone {
  if (days === null) return 'neutral';
  if (days <= 30) return 'success';
  if (days <= 60) return 'warning';
  return 'danger';
}

/** 成本占销售额比 → tone（**没有分母 ⇒ 中性**）。 */
export function costToSalesTone(ratio: number | null): Tone {
  if (ratio === null) return 'neutral';
  if (ratio > 80) return 'danger';
  if (ratio > 60) return 'warning';
  return 'success';
}

/** 供应商评级档位 → tone。 */
export function ratingTone(grade: string | null | undefined): Tone {
  switch (grade) {
    case 'A':
      return 'success';
    case 'B':
      return 'primary';
    case 'C':
      return 'warning';
    case 'D':
      return 'danger';
    default:
      return 'neutral';
  }
}

// ================================================================ 口径文案

/**
 * 价格波动行的状态 → 中文说明。
 *
 * <p>🔴 **「不可比」必须说清是哪一侧不可比** —— 「上一期间没有数量」与
 * 「当前期间没有数量」对采购员的动作完全不同（前者是「没有历史可比」，后者是「这批没买」）。
 */
export function priceStatusLabel(status: string): string {
  switch (status) {
    case 'COMPARABLE':
      return '可比';
    case 'NO_DATA':
      return '两个期间都没有可算的均价';
    case 'CURRENT_MISSING':
      return '当前期间没有数量';
    case 'PREVIOUS_MISSING':
      return '上一期间没有数量';
    case 'PREVIOUS_ZERO':
      return '上一期间均价为 0';
    default:
      return status;
  }
}

/** 准时状态 → 中文说明。 */
export function onTimeStatusLabel(status: string): string {
  switch (status) {
    case 'ON_TIME':
      return '准时';
    case 'LATE':
      return '逾期';
    case 'PENDING':
      return '未到货';
    default:
      return '不适用';
  }
}

// ================================================================ 维度与选项

/** 趋势的拆解维度下拉。 */
export const DIMENSION_OPTIONS: { value: string; label: string }[] = [
  { value: 'ALL', label: '全部（不拆维度）' },
  { value: 'SUPPLIER', label: '按供应商' },
  { value: 'CATEGORY', label: '按类目' },
  { value: 'SHOP', label: '按店铺' },
  { value: 'SKU', label: '按 SKU' },
];

/** 价格波动的拆解维度下拉。 */
export const PRICE_DIMENSION_OPTIONS: { value: string; label: string }[] = [
  { value: 'SKU', label: '按 SKU' },
  { value: 'SUPPLIER', label: '按供应商' },
];

/** 期间粒度下拉。 */
export const GRANULARITY_OPTIONS: { value: string; label: string }[] = [
  { value: 'DAY', label: '按日' },
  { value: 'WEEK', label: '按周' },
  { value: 'MONTH', label: '按月' },
];

/** 趋势点的期间标签（**按粒度**说清是日 / 周 / 月）。 */
export function periodLabel(period: string, granularity: string): string {
  if (granularity === 'MONTH') return period;
  if (granularity === 'WEEK') return `${period}（当周）`;
  return period;
}

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
    return '这个时间窗口里没有任何采购流水。上游可能还没有同步到货记录，或刷新窗口不对。';
  }
  return '';
}

/**
 * 「数据截至」这句话（**常驻显示**）。
 *
 * <p>🔴 没有投影过时显示「尚未投影任何采购事实」—— **不是**「数据截至 今天」：
 * 后者会让人以为「今天的数据已经在库里了」，而真相是「一格都还没有」。
 */
export function dataUpdatedText(response: { dataUpdatedAt: string | null } | null): string {
  if (!response) return '';
  if (!response.dataUpdatedAt) return '数据截至：尚未投影任何采购事实';
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

/**
 * 集中度的风险说明（**服务端算好的**，这里只做展示）。
 *
 * <p>🔴 服务端没给说明时给一句兜底 —— 页面上**不能**出现一个没有解释的风险等级。
 */
export function concentrationRiskText(response: ProcurementConcentrationResponse | null): string {
  if (!response) return '';
  if (!response.riskNote) return '集中度风险说明缺失（数据尚未投影）。';
  return response.riskNote;
}

/**
 * 能力声明里「接不通」的项 → 徽标文案。
 *
 * <p>🔴 **必须带上归属模块号**：让人知道「去哪维护」，而不是只看到一个灰掉的指标。
 */
export function unavailableLabel(ownerModule: string | null | undefined): string {
  if (!ownerModule) return '未接通';
  return `未接通 · ${ownerModule}`;
}
