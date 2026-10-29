/**
 * 13.3.1.1 产品表现分析前端模型（卷 13「利润与经营分析」L3 13.3 产品分析下的第一格）。
 *
 * <p>蓝图原文：「单产品的 360 度分析：销量、销售额、毛利、流量、转化、退货、库存、库龄、
 * 广告表现、竞品对比，集成在同一页面滚动查看（避免页面来回跳）。」输入「产品标识
 * （SKU/SPU/Listing）；时间范围；对比基线。」输出「多指标分析页 + 趋势 + 排名分位。」
 * 关系：上游 = 全部业务域；下游 = 各业务域的处理页。**这是运营每天用得最多的页面**，
 * 性能与信息密度优先，指标要可对比（环比/同层级排名）。
 *
 * <p><b>🔴 这一页最容易做丢的五件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「等于 0」</b>。服务端把分母为 0 / 没有事实 / 上游未交付三种情况
 *       分别置为 {@code null} 与 {@code NO_DATA} / {@code NOT_COMPARABLE} /
 *       {@code NOT_DELIVERED}，这里渲染成「—」/「不可比」/「本轮不可用（归谁管）」+ 原因，
 *       <b>绝不用 0 顶替</b> —— 假 0 会被读成「这个商品卖不出去」。</li>
 *   <li><b>🔴 多币种绝不相加</b>。金额按币种分别列出；只有件数与次数（与货币无关）给一个
 *       跨币种合计，它的币种是<b>空串</b> —— 空串 = 与币种无关，<b>不是「币种未知」</b>。</li>
 *   <li><b>环比由服务端算</b>（{@code change} / {@code changeRate} 已经是算好的
 *       {@code BigDecimal}），前端<b>只做展示格式化，不做加减乘除</b> —— 前端自己算一遍
 *       会引入二进制浮点误差，界面和服务端的数就对不上了。</li>
 *   <li><b>基线为 0 时变化率是 {@code null}</b>：「上期是 0」与「本期没变」是两件事，
 *       渲染成 0% 会把前者说成后者。</li>
 *   <li><b>数据更新时间常驻</b>。卷 13 卷首加粗句「数据延迟要显示」—— 加载中与出错时也在
 *       （没投影过就写「尚未投影任何数据」）。</li>
 * </ol>
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  PerformanceAdCampaignRow,
  PerformanceCompetitorRow,
  PerformanceCurrencyValue,
  PerformanceMetric,
  PerformanceMetricDefinitionView,
  PerformanceRanking,
  PerformanceRuleNote,
  PerformanceSection,
  PerformanceTrendPoint,
  ProductPerformanceCapabilities,
  ProductPerformanceRefreshResult,
  ProductPerformanceResponse,
} from '../../../../../packages/api-contracts/src/index';

export type {
  PerformanceAdCampaignRow,
  PerformanceCompetitorRow,
  PerformanceCurrencyValue,
  PerformanceMetric,
  PerformanceMetricDefinitionView,
  PerformanceRanking,
  PerformanceRuleNote,
  PerformanceSection,
  PerformanceTrendPoint,
  ProductPerformanceCapabilities,
  ProductPerformanceRefreshResult,
  ProductPerformanceResponse,
};

// ================================================================ 展示格式化

/** 金额刻度（与服务端 numeric(24,4) 对齐，展示时保留 2 位即可）。 */
const MONEY_DECIMALS = 2;

/** 百分比刻度（服务端已经算成百分数，例如 -15.0000 = -15%）。 */
const PERCENT_DECIMALS = 2;

/** 件数刻度（服务端 numeric(19,6)，展示时保留 3 位，去掉无意义的尾零）。 */
const QUANTITY_DECIMALS = 3;

/** 笔数刻度。 */
const COUNT_DECIMALS = 0;

function formatNumber(value: number, decimals: number): string {
  return value.toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}

/**
 * 件数。
 *
 * <p>🔴 {@code null} ⇒ 「—」，**绝不用 0 顶替**。
 */
export function quantityText(value: number | null): string {
  if (value === null) return '—';
  return formatNumber(value, QUANTITY_DECIMALS);
}

/**
 * 金额 + 币种。
 *
 * <p>🔴 {@code null} ⇒ 「—」，**绝不用 0 顶替**。
 */
export function amountText(value: number | null, currency: string): string {
  if (value === null) return '—';
  if (!currency) return `${formatNumber(value, MONEY_DECIMALS)}（与币种无关）`;
  return `${formatNumber(value, MONEY_DECIMALS)} ${currency}`;
}

/**
 * 百分比。
 *
 * <p>🔴 {@code null} ⇒ 「不可比」（**不是 0%**）：分母为 0 或没有事实时，这个比值根本不存在。
 */
export function percentText(value: number | null): string {
  if (value === null) return '不可比';
  return `${formatNumber(value, PERCENT_DECIMALS)}%`;
}

/** 笔数。🔴 {@code null} ⇒ 「—」，不是 0。 */
export function countText(value: number | null): string {
  if (value === null) return '—';
  return formatNumber(value, COUNT_DECIMALS);
}

/**
 * 币种标签。
 *
 * <p>🔴 **空串 = 与币种无关**（件数 / 次数的跨币种合计），**不是「币种未知」** ——
 * 渲染成「未知」会让人以为这里有数据问题。
 */
export function currencyLabel(currency: string): string {
  if (!currency) return '与币种无关';
  return currency;
}

/**
 * 按指标单位分派的取值文本。
 *
 * <p>这是指标格的唯一渲染入口 —— 单位判断只在这里做一次，
 * 免得每处各写一遍判断、然后在某个单位上漏掉「算不出来」的处理。
 *
 * <p>⚠️ 单位来自<b>口径定义</b>（{@code PerformanceMetricDefinitionView.unit}），
 * 不来自取值本身 —— 取值里没有单位字段，猜单位就会把「百分比」当成「金额」渲染。
 */
export function metricValueText(metric: PerformanceMetric, unit: string): string {
  switch (unit) {
    case 'CURRENCY':
      return amountText(metric.value, metric.currency);
    case 'PERCENT':
      return percentText(metric.value);
    case 'QUANTITY':
      return quantityText(metric.value);
    case 'COUNT':
      return countText(metric.value);
    default:
      return metric.value === null ? '—' : formatNumber(metric.value, PERCENT_DECIMALS);
  }
}

/** 单位的展示名。 */
export function metricUnitLabel(unit: string): string {
  switch (unit) {
    case 'CURRENCY':
      return '金额';
    case 'COUNT':
      return '次数';
    case 'QUANTITY':
      return '数量';
    case 'PERCENT':
      return '百分比';
    default:
      return unit;
  }
}

// ================================================================ 可用性

export type Tone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'brand';

/**
 * 指标可用性的语义色。
 *
 * <p>🔴 {@code NO_DATA} 与 {@code NOT_COMPARABLE} 都是 neutral：
 * 「这一格没有事实」和「这一格不可比」都是<b>如实说明</b>，不是错误。
 *
 * <p>🔴 {@code NOT_DELIVERED} 用 warning：上游本轮没交付这一项，
 * 它既不是 0 也不是错误，而是**「这一格归谁管」** —— 值得提醒运营别在这格上找答案。
 */
export function availabilityTone(availability: string): Tone {
  switch (availability) {
    case 'AVAILABLE':
      return 'success';
    case 'NOT_DELIVERED':
      return 'warning';
    case 'NOT_COMPARABLE':
    case 'NO_DATA':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/**
 * 指标可用性的中文标签。
 *
 * <p>🔴 {@code NOT_DELIVERED} 渲染成「本轮不可用」而不是「没有数据」——
 * 前者是「上游还没交付」，后者是「交付了但这个窗口没有」，两者的处置动作完全不同。
 */
export function availabilityLabel(availability: string): string {
  switch (availability) {
    case 'AVAILABLE':
      return '有数据';
    case 'NOT_COMPARABLE':
      return '不可比';
    case 'NO_DATA':
      return '没有数据';
    case 'NOT_DELIVERED':
      return '本轮不可用';
    default:
      return availability;
  }
}

/**
 * 「本轮不可用」指标的归属说明。
 *
 * <p>🔴 指标口径登记了 {@code ownerModule}（归谁管）时，
 * 页面上要写明「这归谁管」—— 否则运营只会以为系统坏了。
 */
export function notDeliveredOwnerText(definition: PerformanceMetricDefinitionView): string {
  if (!definition.ownerModule) return '';
  return `归 ${definition.ownerModule} 管`;
}

// ================================================================ 维度层

/** 维度层的展示名。 */
export function dimensionLabel(dimensionType: string): string {
  switch (dimensionType) {
    case 'SKU':
      return 'SKU';
    case 'SPU':
      return 'SPU';
    case 'LISTING':
      return 'Listing';
    default:
      return dimensionType;
  }
}

/** 维度层下拉项。 */
export function dimensionOptions(): { value: string; label: string }[] {
  return [
    { value: 'SKU', label: 'SKU' },
    { value: 'SPU', label: 'SPU' },
    { value: 'LISTING', label: 'Listing' },
  ];
}

/** 跟卖者性质的展示名（竞品对比段用）。 */
export function sellerKindLabel(sellerKind: string): string {
  switch (sellerKind) {
    case 'OWN_SHOP':
      return '己方其他店铺';
    case 'EXTERNAL':
      return '外部跟卖者';
    default:
      return sellerKind || '未知';
  }
}

/** 跟卖者性质的语义色：己方店铺是「自己人」，外部跟卖者才是真对手。 */
export function sellerKindTone(sellerKind: string): Tone {
  return sellerKind === 'EXTERNAL' ? 'warning' : 'neutral';
}

// ================================================================ 环比（服务端算好的）

/**
 * 变化量文本。
 *
 * <p>🔴 {@code null} ⇒ 「—」：**任一侧为 null 就没有变化量**。
 * 不用 0 顶替 —— 「没有基线」与「跟基线一模一样」是两件事。
 */
export function changeText(metric: PerformanceMetric, unit: string): string {
  if (metric.change === null) return '—';
  if (unit === 'PERCENT') return percentText(metric.change);
  if (unit === 'CURRENCY') return amountText(metric.change, metric.currency);
  if (unit === 'QUANTITY') return quantityText(metric.change);
  if (unit === 'COUNT') return countText(metric.change);
  return formatNumber(metric.change, PERCENT_DECIMALS);
}

/**
 * 变化率文本。
 *
 * <p>🔴 {@code null} ⇒ 「不可比」（**不是 0%**）：基线为 0 时变化率算不出来。
 */
export function changeRateText(metric: PerformanceMetric): string {
  if (metric.changeRate === null) return '不可比';
  return `${formatNumber(metric.changeRate, PERCENT_DECIMALS)}%`;
}

/**
 * 变化方向的语义色。
 *
 * <p>⚠️ {@code null}（算不出来）返回 neutral —— 没有方向就没有好坏。
 *
 * <p>⚠️ **这里的「好坏」只表示方向**（涨 / 跌），不代表业务好坏：
 * 退货率涨是坏事，毛利额涨是好事 —— 所以调用方按指标语义选色，
 * 本函数只负责把「涨 / 跌 / 平」翻译成 tone。
 */
export function changeTone(metric: PerformanceMetric): Tone {
  if (metric.change === null) return 'neutral';
  if (metric.change > 0) return 'success';
  if (metric.change < 0) return 'danger';
  return 'neutral';
}

/** 变化方向的中文标签。 */
export function changeDirectionLabel(metric: PerformanceMetric): string {
  if (metric.change === null) return '不可比';
  if (metric.change > 0) return '上升';
  if (metric.change < 0) return '下降';
  return '持平';
}

// ================================================================ 排名分位

/**
 * 排名文本。
 *
 * <p>🔴 {@code total <= 0} ⇒ 「不可比」：没有同层级样本就没有分位。
 */
export function rankingText(ranking: PerformanceRanking | null): string {
  if (!ranking) return '不可比';
  if (ranking.total <= 0) return '不可比';
  return `第 ${ranking.rank} / ${ranking.total}`;
}

/**
 * 分位文本。
 *
 * <p>🔴 {@code null} ⇒ 「不可比」：分位算不出来就如实说算不出来。
 */
export function rankingPercentileText(ranking: PerformanceRanking | null): string {
  if (!ranking) return '不可比';
  if (ranking.percentile === null) return '不可比';
  return percentText(ranking.percentile);
}

/** 同层级中位数文本。 */
export function peerMedianText(ranking: PerformanceRanking | null): string {
  if (!ranking || ranking.peerMedian === null) return '—';
  return formatNumber(ranking.peerMedian, QUANTITY_DECIMALS);
}

/** 同层级平均数文本。 */
export function peerAverageText(ranking: PerformanceRanking | null): string {
  if (!ranking || ranking.peerAverage === null) return '—';
  return formatNumber(ranking.peerAverage, QUANTITY_DECIMALS);
}

// ================================================================ 区块

/**
 * 取某个区块。
 *
 * <p>🔴 找不到 ⇒ **返回 null 而不是造一个空区块** ——
 * 造一个空区块会让界面以为「这一段有数据但全是空的」。
 */
export function sectionByCode(
  response: ProductPerformanceResponse | null,
  sectionCode: string,
): PerformanceSection | null {
  if (!response) return null;
  return response.sections.find((section) => section.sectionCode === sectionCode) ?? null;
}

/** 区块里的指标列表。 */
export function sectionMetrics(section: PerformanceSection | null): PerformanceMetric[] {
  return section?.metrics ?? [];
}

/** 区块里的竞品行（只有「竞品对比」段有值）。 */
export function sectionCompetitors(section: PerformanceSection | null): PerformanceCompetitorRow[] {
  return section?.competitors ?? [];
}

/** 区块里的广告活动行（只有「广告表现」段有值）。 */
export function sectionAdCampaigns(section: PerformanceSection | null): PerformanceAdCampaignRow[] {
  return section?.adCampaigns ?? [];
}

/** 区块标题（找不到时回落到区块码本身，不留空白）。 */
export function sectionTitle(section: PerformanceSection | null, fallback: string): string {
  return section?.title || fallback;
}

// ================================================================ 趋势

/**
 * 趋势里按币种列出的金额。
 *
 * <p>🔴 **不跨币种相加**：每个币种一行，不给合计。
 */
export function trendAmountsText(values: PerformanceCurrencyValue[]): string {
  if (!values || values.length === 0) return '—';
  return values.map((value) => amountText(value.amount, value.currency)).join(' / ');
}

/** 趋势点里的销量文本。 */
export function trendSalesText(point: PerformanceTrendPoint): string {
  return quantityText(point.salesQuantity);
}

/** 趋势点里的退货件数文本。 */
export function trendReturnedText(point: PerformanceTrendPoint): string {
  return quantityText(point.returnedQuantity);
}

/** 趋势点里的曝光文本。 */
export function trendImpressionsText(point: PerformanceTrendPoint): string {
  return countText(point.impressions);
}

/** 趋势点里的点击文本。 */
export function trendClicksText(point: PerformanceTrendPoint): string {
  return countText(point.clicks);
}

/** 趋势点里的 Session 文本。 */
export function trendSessionsText(point: PerformanceTrendPoint): string {
  return countText(point.sessions);
}

/** 趋势里**当天有没有任何一格算不出来**（用于提示「这一段是空的」）。 */
export function trendHasValues(points: PerformanceTrendPoint[]): boolean {
  return points.some(
    (point) =>
      point.salesQuantity !== null ||
      point.impressions !== null ||
      point.clicks !== null ||
      point.sessions !== null ||
      (point.salesAmounts && point.salesAmounts.length > 0),
  );
}

// ================================================================ 页面状态

export type PageState = 'loading' | 'error' | 'empty' | 'ready';

/**
 * 页面状态。
 *
 * <p>🔴 **空 ≠ 错误**：上游读不到（error）与「读到了但这个窗口没有事实」（empty）
 * 是两件事 —— 前者要修，后者不用修。
 */
export function pageState(
  response: ProductPerformanceResponse | null,
  error: string,
): PageState {
  if (error) return 'error';
  if (!response) return 'loading';
  if (!response.sections || response.sections.length === 0) return 'empty';
  return 'ready';
}

/** 空态文案。 */
export function emptyText(response: ProductPerformanceResponse | null): string {
  if (!response) return '';
  return response.emptyNote || '这个窗口里没有可分析的事实。';
}

/** 空态原因标签。 */
export function emptyReasonLabel(reason: string | null): string {
  switch (reason) {
    case 'NO_PROJECTION':
      return '尚未投影';
    case 'NO_FACT':
      return '窗口内没有事实';
    case 'DIMENSION_NOT_FOUND':
      return '找不到该维度';
    case 'BASELINE_NOT_AVAILABLE':
      return '基线窗口不可用';
    default:
      return reason || '';
  }
}

/** 投影状态标签。 */
export function statusLabel(status: string): string {
  switch (status) {
    case 'PROJECTED':
      return '已投影';
    case 'NOT_PROJECTED':
      return '尚未投影';
    case 'PARTIAL':
      return '部分投影';
    default:
      return status;
  }
}

/**
 * 数据更新时间文本。
 *
 * <p>🔴 **空表时是 null** —— 渲染成「—」，不是一个长得像时间的假值。
 */
export function dataUpdatedText(response: ProductPerformanceResponse | null): string {
  if (!response || !response.dataUpdatedAt) return '尚未投影任何数据';
  return `数据截至 ${response.dataUpdatedAt}`;
}

/** 数据更新时间的口径说明。 */
export function dataUpdatedNote(response: ProductPerformanceResponse | null): string {
  if (!response) return '';
  return response.dataUpdatedNote || '';
}

// ================================================================ 能力声明

/** 确实拿不到的部分（每条都写清原因与归属模块）。 */
export function notDeliveredLines(capabilities: ProductPerformanceCapabilities | null): string[] {
  return capabilities?.notDelivered ?? [];
}

/** 本服务真的读过的上游模块。 */
export function upstreamModules(capabilities: ProductPerformanceCapabilities | null): string[] {
  return capabilities?.upstreamModules ?? [];
}

/** 判定规则与口径说明。 */
export function ruleNotes(capabilities: ProductPerformanceCapabilities | null): PerformanceRuleNote[] {
  return capabilities?.rules ?? [];
}

/** 刷新结果的状态标签。 */
export function refreshStatusLabel(status: string): string {
  switch (status) {
    case 'SUCCEEDED':
      return '成功';
    case 'FAILED':
      return '失败';
    case 'TRUNCATED':
      return '已截断';
    default:
      return status;
  }
}

/** 刷新结果的状态语义色。 */
export function refreshStatusTone(status: string): Tone {
  switch (status) {
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
      return 'danger';
    case 'TRUNCATED':
      return 'warning';
    default:
      return 'neutral';
  }
}

// ================================================================ 时间范围

/** 时间范围文本。 */
export function rangeText(from: string, to: string): string {
  return `${from} ~ ${to}`;
}

/**
 * 默认时间范围（最近 30 天，含今天）。
 *
 * <p>⚠️ 基线由**服务端**算（上一个等长周期）——
 * 前端不算基线日期，免得两端对「等长」的理解不一致。
 */
export function defaultRange(today: string): { from: string; to: string } {
  const end = new Date(`${today}T00:00:00Z`);
  const start = new Date(end.getTime() - 29 * 24 * 3600 * 1000);
  return { from: start.toISOString().slice(0, 10), to: today };
}

/** 基线窗口文本（环比比的是哪一段）。 */
export function baselineText(response: ProductPerformanceResponse | null): string {
  if (!response) return '';
  return `基线（上一个等长周期）${response.baselineFrom} ~ ${response.baselineTo}`;
}

/** 时区说明（跨时区店铺汇总必须显示所用时区）。 */
export function timezoneNote(timezones: string[] | undefined): string {
  if (!timezones || timezones.length === 0) return '';
  return `统计时区：${timezones.join('、')}`;
}

/** 维度覆盖范围文本（这一层覆盖了多少 SKU / Listing）。 */
export function dimensionScopeText(response: ProductPerformanceResponse | null): string {
  if (!response) return '';
  return `覆盖 ${response.skuCount} 个 SKU / ${response.listingCount} 个 Listing`;
}
