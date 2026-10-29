/**
 * 13.5.3.1 物流分析 前端模型（卷 13「利润与经营分析」L3 13.5.3 物流分析下的第一格）。
 *
 * <p>蓝图原文：「物流分析：单均运费、头程分摊后成本、渠道对比、时效达成率、各国成本对比、
 * 异常件占比。」输入「物流数据（10）；成本数据。」输出「物流报表 + 渠道对比。」
 *
 * <p><b>🔴 这个页面上最容易做丢的六件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「数值是 0」</b>。服务端把「没有订单」「没有运单」「没有销售额」
 *       这几种情形的值置为 {@code null}，这里渲染成「—（不可比）」+ 中性配色，
 *       <b>绝不用 0 顶替</b> —— 「单均运费 0 元」会被读成「这批货没花运费」。</li>
 *   <li><b>单均运费的分母是订单数</b>。页面上「每票运费」（上游口径，分母是票）
 *       与「单均运费」（本模块口径，分母是订单）<b>并排显示</b>，
 *       一单多票时两者不同 —— 混成一个数就是错的。</li>
 *   <li><b>多币种绝不相加</b>。每一行带自己的币种；跨币种的「总计」不存在。</li>
 *   <li><b>类目维度只覆盖头程分摊部分</b>。尾程运费落在运单上，<b>没有 SKU 维度</b>
 *       ⇒ 那一段进不了类目。页面必须写明，否则下游会当成全口径。</li>
 *   <li><b>数据更新时间常驻</b>。卷 13 卷首加粗句「数据延迟要显示」。</li>
 *   <li><b>前端不做业务算术</b>。金额、占比、比率全部来自服务端（{@code BigDecimal} 定标），
 *       这里只做<b>展示格式化</b>。</li>
 * </ol>
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  LogisticsAnalysisCapabilities,
  LogisticsAnalysisRule,
  LogisticsCategoryCostResponse,
  LogisticsChannelComparisonResponse,
  LogisticsCostOverviewResponse,
  LogisticsCountryCostResponse,
  LogisticsDrilldownResponse,
  LogisticsExceptionShareResponse,
  LogisticsFreightPerOrderResponse,
  LogisticsHeadMileResponse,
  LogisticsTimelinessResponse,
} from '../../../../../packages/api-contracts/src/index';

export type {
  LogisticsAnalysisCapabilities,
  LogisticsAnalysisRule,
  LogisticsCategoryCostResponse,
  LogisticsChannelComparisonResponse,
  LogisticsCostOverviewResponse,
  LogisticsCountryCostResponse,
  LogisticsDrilldownResponse,
  LogisticsExceptionShareResponse,
  LogisticsFreightPerOrderResponse,
  LogisticsHeadMileResponse,
  LogisticsTimelinessResponse,
};

// ================================================================ 展示格式化

/** 金额刻度（与服务端 numeric(24,4) 对齐，展示时保留 2 位即可）。 */
const MONEY_DECIMALS = 2;

/** 百分比刻度（服务端已经算成百分数，例如 33.3333 = 33.33%）。 */
const PERCENT_DECIMALS = 2;

/** 数量刻度（服务端 numeric(19,6)，展示时保留 3 位）。 */
const QUANTITY_DECIMALS = 3;

/** 小时刻度（服务端 numeric(19,4)，展示时保留 1 位）。 */
const HOURS_DECIMALS = 1;

function formatNumber(value: number, decimals: number): string {
  return value.toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}

/**
 * 金额 + 币种。
 *
 * <p>🔴 {@code null} ⇒ 「—」，**绝不用 0 顶替**：算不出来的金额是「算不出来」，
 * 写成 0 元会被读成「这笔成本不要钱」。
 */
export function amountText(value: number | null, currency: string | null): string {
  if (value === null) return '—';
  return `${formatNumber(value, MONEY_DECIMALS)} ${currency ?? ''}`.trim();
}

/**
 * 占比（百分数）。
 *
 * <p>🔴 {@code null} ⇒ 「—（不可比）」，**不是 0%**：分母缺失或为 0 时占比根本不存在。
 * 界面上它必须与真的 0% **长得不一样**。
 */
export function percentText(value: number | null): string {
  if (value === null) return '—（不可比）';
  return `${formatNumber(value, PERCENT_DECIMALS)}%`;
}

/** 数量（件 / 票）。 */
export function quantityText(value: number | null): string {
  if (value === null) return '—';
  return formatNumber(value, QUANTITY_DECIMALS);
}

/** 时长（小时）。 */
export function hoursText(value: number | null): string {
  if (value === null) return '—（不可比）';
  return `${formatNumber(value, HOURS_DECIMALS)} 小时`;
}

/** 占比条宽度（**只做展示钳位**，不做算术）。 */
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
 * 达成率 → tone。
 *
 * <p>🔴 「算不出来」用 **neutral**（中性）而不是 warning ——
 * 「这一格没数据」是**中性事实**，不是异常；用警告色会把它读成「出问题了」。
 */
export function onTimeTone(rate: number | null): Tone {
  if (rate === null) return 'neutral';
  if (rate >= 90) return 'success';
  if (rate >= 70) return 'warning';
  return 'danger';
}

/** 异常件占比 → tone（**没有分母 ⇒ 中性**，不是「差」）。 */
export function exceptionShareTone(share: number | null): Tone {
  if (share === null) return 'neutral';
  if (share > 10) return 'danger';
  if (share > 5) return 'warning';
  return 'success';
}

/** 物流成本占销售额比 → tone（**没有分母 ⇒ 中性**）。 */
export function categoryRatioTone(ratio: number | null): Tone {
  if (ratio === null) return 'neutral';
  if (ratio > 50) return 'danger';
  if (ratio > 30) return 'warning';
  return 'success';
}

/** 运费登记状态 → tone。 */
export function freightGapTone(gapCode: string | null | undefined): Tone {
  if (gapCode === 'REGISTERED') return 'success';
  if (gapCode === 'NOT_REGISTERED') return 'warning';
  return 'neutral';
}

/** 运费登记状态 → 中文说明。 */
export function freightGapLabel(gapCode: string | null | undefined): string {
  switch (gapCode) {
    case 'REGISTERED':
      return '已登记';
    case 'NOT_REGISTERED':
      return '未登记';
    default:
      return '未知';
  }
}

/** 严重度 → tone。 */
export function severityTone(severity: string | null | undefined): Tone {
  switch (severity) {
    case 'HIGH':
    case 'CRITICAL':
      return 'danger';
    case 'MEDIUM':
      return 'warning';
    case 'LOW':
      return 'success';
    default:
      return 'neutral';
  }
}

// ================================================================ 口径文案

/**
 * 单均运费口径提示（**常驻页面**）。
 *
 * <p>🔴 「每票运费」与「单均运费」**不是一回事** —— 一单多票时前者偏低。
 * 这个区别必须写在页面上，否则运营会把上游那个数当单均运费去定价。
 */
export function freightScopeNote(response: LogisticsFreightPerOrderResponse | null): string {
  if (!response) return '';
  const parts = [
    `订单分母口径：${response.orderScope}。`,
    `窗口内运单 ${response.waybillCount} 票（已登记运费 ${response.registeredWaybillCount} 票、未登记 ${response.notRegisteredWaybillCount} 票）。`,
  ];
  if (response.orderCountAll > response.orderCount) {
    parts.push(
      `⚠️ 有 ${response.orderCountAll - response.orderCount} 个订单**只有未登记运费的票** —— 它们不进分母（进了会把单均运费压低）。`,
    );
  }
  return parts.join(' ');
}

/**
 * 类目维度覆盖范围提示（**下游 5 定价必须知道这件事**）。
 */
export function categoryScopeNote(response: LogisticsCategoryCostResponse | null): string {
  if (!response) return '';
  const parts = ['⚠️ 类目维度**只覆盖头程分摊部分**：尾程运费落在运单上，一票可能多 SKU，没有 SKU 维度 ⇒ 那一段进不了类目。'];
  if (response.unattributedLineCount > 0) {
    parts.push(
      `有 ${response.unattributedLineCount} 条分摊明细行**挂不上类目**（单列「未归属」，不摊进任何类目）。`,
    );
  }
  if (response.revenueMissingSkuCount && response.revenueMissingSkuCount > 0) {
    parts.push(
      `有 ${response.revenueMissingSkuCount} 条销售额事实**挂不上类目** ⇒ 它们**不进任何类目的分母**。`,
    );
  }
  return parts.join(' ');
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

// ================================================================ 维度与选项

/** 时间范围与筛选。 */
export function rangeText(from: string, to: string): string {
  return `${from} ~ ${to}`;
}

/** 默认窗口：最近 30 天（含今天）。 */
export function defaultRange(today: string): { from: string; to: string } {
  const end = new Date(`${today}T00:00:00Z`);
  const start = new Date(end.getTime() - 29 * 24 * 60 * 60 * 1000);
  return { from: start.toISOString().slice(0, 10), to: today };
}

// ================================================================ 时间与页面状态

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
    return '这个时间窗口里没有任何物流事实。上游可能还没有同步运单与成本数据，或刷新窗口不对 —— 先刷新投影。';
  }
  return '';
}

/**
 * 「数据截至」这句话（**常驻显示**）。
 *
 * <p>🔴 没有投影过时显示「尚未投影任何物流事实」—— **不是**「数据截至 今天」：
 * 后者会让人以为「今天的数据已经在库里了」，而真相是「一格都还没有」。
 */
export function dataUpdatedText(response: { dataUpdatedAt: string | null } | null): string {
  if (!response) return '';
  if (!response.dataUpdatedAt) return '数据截至：尚未投影任何物流事实';
  return `数据截至 ${response.dataUpdatedAt}`;
}

/** 「数据截至」这句话的口径说明（这个时刻**具体指什么**）。 */
export function dataUpdatedNote(response: { dataUpdatedNote: string } | null): string {
  return response?.dataUpdatedNote ?? '';
}
