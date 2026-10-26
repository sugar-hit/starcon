/**
 * 13.2.1.1 销量趋势 前端模型（卷 13「利润与经营分析」L3 13.2 销量分析下的第一格）。
 *
 * <p>蓝图原文：「销量与销售额趋势：按日/周/月、小时维度查看，支持多维对比
 * （同比/环比/店铺间/产品间）、异常日期标注（大促、缺货、断货）。
 * 输入「时间范围；维度与对比方式；异常事件标注。」输出「趋势图 + 对比视图 + 异常标注。」
 * 关系：上游 = 9 订单数据；下游 = 13.2.2、6.2.1 补货（销量预测输入）。
 * **销量异常必须能关联事件**（大促、断货、价格调整），否则图表只是曲线没有洞察。」
 *
 * <p><b>🔴 这一页最容易做丢的五件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「等于 0」</b>。服务端把基期缺失 / 基期为 0 的取值置为
 *       {@code null}，这里渲染成「—」或「不可比」+ 原因，<b>绝不用 0 顶替</b> ——
 *       假 0 会被读成「这天卖了 0」。</li>
 *   <li><b>异常必须能关联事件</b>（蓝图关系句加粗要求）。异常点带事件清单；
 *       一个事件都没关联上时，服务端会明说「<b>查不到原因</b>」—— 页面照原样显示，
 *       不自己编一个理由。</li>
 *   <li><b>多币种绝不相加</b>。每个币种各自一条序列，页面上永远没有跨币种的「总计」。</li>
 *   <li><b>数据更新时间常驻</b>。卷 13 卷首加粗句「数据延迟要显示」—— 加载中 / 出错时也显示。</li>
 *   <li><b>前端不做业务算术</b>。金额、比率、同比环比全部来自服务端
 *       （{@code BigDecimal} 定标），这里只做<b>展示格式化</b>，不做任何加减乘除 ——
 *       前端算一遍就会引入二进制浮点误差，而且会让界面和服务端的数对不上。</li>
 * </ol>
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  SalesTrendAnomaly,
  SalesTrendCapabilities,
  SalesTrendComparisonMetric,
  SalesTrendComparisonResponse,
  SalesTrendComparisonRow,
  SalesTrendCurrencyRate,
  SalesTrendEvent,
  SalesTrendEventResponse,
  SalesTrendMetricDefinitionView,
  SalesTrendPoint,
  SalesTrendRefreshResult,
  SalesTrendResponse,
  SalesTrendRuleNote,
  SalesTrendSeries,
} from '../../../../../packages/api-contracts/src/index';

export type {
  SalesTrendAnomaly,
  SalesTrendCapabilities,
  SalesTrendComparisonMetric,
  SalesTrendComparisonResponse,
  SalesTrendComparisonRow,
  SalesTrendCurrencyRate,
  SalesTrendEvent,
  SalesTrendEventResponse,
  SalesTrendMetricDefinitionView,
  SalesTrendPoint,
  SalesTrendRefreshResult,
  SalesTrendResponse,
  SalesTrendRuleNote,
  SalesTrendSeries,
};

// ================================================================ 十进制格式化

/**
 * 十进制定标格式化（<b>不碰浮点</b>）。
 *
 * <p>⚠️ 为什么不用 {@code toLocaleString}：它按 IEEE 754 的双精度做四舍五入，
 * HALF_UP 下 {@code 1.005} 会变成 {@code 1.00}（因为 1.005 的双精度实际是
 * 1.00499999999999989）—— 服务端按十进制算出来的 1.01 被页面显示成 1.00，
 * 界面和服务端的数就对不上了。
 *
 * <p>⚠️ 这里用 {@code toFixed} 只做<b>表示</b>（把 double 落到十进制字面串），
 * 之后的进位用 {@link BigInt} 做整数运算 ⇒ <b>HALF_UP</b>，与后端一致。
 */
export function formatDecimal(value: number, decimals: number): string {
  if (!Number.isFinite(value)) return '—';
  const negative = value < 0;
  const raw = Math.abs(value).toFixed(decimals + 4);
  const [intRaw = '0', fracRaw = ''] = raw.split('.');
  const intPart = intRaw.replace(/^0+(?=\d)/, '');
  const keep = (fracRaw.slice(0, decimals) + '0'.repeat(decimals)).slice(0, decimals);
  const rest = fracRaw.slice(decimals);
  const scaled = BigInt(intPart + keep);
  const rounded = rest.length > 0 && Number(rest[0]) >= 5 ? scaled + 1n : scaled;
  const digits = rounded.toString().padStart(decimals + 1, '0');
  const head = digits.slice(0, digits.length - decimals) || '0';
  const tail = decimals > 0 ? `.${digits.slice(digits.length - decimals)}` : '';
  const grouped = head.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${negative ? '-' : ''}${grouped}${tail}`;
}

/** 金额刻度（服务端 numeric(24,4)，展示时保留 2 位）。 */
const MONEY_DECIMALS = 2;
/** 百分比刻度（服务端已经算成百分数，例如 -15.0000 = -15%）。 */
const PERCENT_DECIMALS = 2;
/** 数量刻度（服务端 numeric(19,6)，展示时保留 0 位 —— 件数不显示小数点）。 */
const QUANTITY_DECIMALS = 0;
/** 笔数刻度。 */
const COUNT_DECIMALS = 0;

/**
 * 金额 + 币种。
 *
 * <p>🔴 {@code null} ⇒ 「—」，<b>绝不用 0 顶替</b>。
 */
export function amountText(value: number | null, currency: string): string {
  if (value === null) return '—';
  return `${formatDecimal(value, MONEY_DECIMALS)} ${currency}`;
}

/**
 * 百分比。
 *
 * <p>🔴 {@code null} ⇒ 「不可比」（<b>不是 0%</b>）：基期缺失或基期为 0 时，
 * 环比 / 同比根本不存在。
 */
export function percentText(value: number | null): string {
  if (value === null) return '不可比';
  const sign = value > 0 ? '+' : '';
  return `${sign}${formatDecimal(value, PERCENT_DECIMALS)}%`;
}

/** 数量。🔴 {@code null} ⇒ 「—」，不是 0。 */
export function quantityText(value: number | null): string {
  if (value === null) return '—';
  return formatDecimal(value, QUANTITY_DECIMALS);
}

/** 笔数。🔴 {@code null} ⇒ 「—」，不是 0。 */
export function countText(value: number | null): string {
  if (value === null) return '—';
  return formatDecimal(value, COUNT_DECIMALS);
}

/**
 * 按指标单位分派的取值文本。
 *
 * <p>这是趋势点与对比矩阵的**唯一渲染入口** —— 单位判断只在这里做一次，
 * 免得每个单元格各写一遍判断、然后在某个单位上漏掉「算不出来」的处理。
 *
 * <p>⚠️ 单位来自**口径定义**（{@code unit}），不来自取值本身 ——
 * 取值里没有单位字段，猜单位就会把「百分比」当成「金额」渲染。
 */
export function metricValueText(value: number | null, unit: string, currency: string): string {
  switch (unit) {
    case 'CURRENCY':
      return amountText(value, currency);
    case 'PERCENT':
      return percentText(value);
    case 'COUNT':
      return countText(value);
    case 'QUANTITY':
      return quantityText(value);
    default:
      return value === null ? '—' : formatDecimal(value, PERCENT_DECIMALS);
  }
}

// ================================================================ 口径

/** 单位的展示名。 */
export function metricUnitLabel(unit: string): string {
  switch (unit) {
    case 'CURRENCY':
      return '金额';
    case 'COUNT':
      return '笔数';
    case 'QUANTITY':
      return '数量';
    case 'PERCENT':
      return '百分比';
    default:
      return unit;
  }
}

/** 聚合方式的展示名。 */
export function aggregationLabel(aggregation: string): string {
  switch (aggregation) {
    case 'SUM':
      return '窗口内相加';
    case 'PERIOD_OVER_PERIOD':
      return '环比';
    case 'YEAR_OVER_YEAR':
      return '同比';
    default:
      return aggregation;
  }
}

/** 维度的展示名。 */
export function dimensionLabel(dimensionType: string): string {
  switch (dimensionType) {
    case 'TOTAL':
      return '公司（全部店铺）';
    case 'STORE':
      return '店铺';
    case 'PRODUCT':
      return '商品（SKU）';
    default:
      return dimensionType;
  }
}

/**
 * 口径的「怎么算出来的」说明。
 *
 * <p>🔴 **对比型必须说清依赖的是谁** —— 否则页面上的「增长率」是个没有出处的数。
 */
export function metricFormulaText(definition: SalesTrendMetricDefinitionView): string {
  if (definition.aggregation === 'PERIOD_OVER_PERIOD') {
    return definition.baseMetricCode
      ? `环比：比的是「${definition.baseMetricCode}」的上一个同粒度桶 / 上一等长窗口`
      : '环比（**没有声明被比较的指标** —— 这个口径不成立）';
  }
  if (definition.aggregation === 'YEAR_OVER_YEAR') {
    return definition.baseMetricCode
      ? `同比：比的是「${definition.baseMetricCode}」的去年同期`
      : '同比（**没有声明被比较的指标** —— 这个口径不成立）';
  }
  return aggregationLabel(definition.aggregation);
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
 * 异常方向的语义色。
 *
 * <p>⚠️ **SPIKE 与 DIP 都是 warning**：异常是经营现象（要去查原因），不是错误。
 * 错误（读不到）是 danger。
 */
export function anomalyTone(direction: string): Tone {
  switch (direction) {
    case 'SPIKE':
      return 'warning';
    case 'DIP':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 异常方向的中文标签。 */
export function anomalyDirectionLabel(direction: string): string {
  switch (direction) {
    case 'SPIKE':
      return '异常偏高';
    case 'DIP':
      return '异常偏低';
    default:
      return direction;
  }
}

/** 事件严重级别的语义色。 */
export function severityTone(severity: string): Tone {
  switch (severity) {
    case 'CRITICAL':
      return 'danger';
    case 'WARNING':
      return 'warning';
    case 'INFO':
      return 'info';
    default:
      return 'neutral';
  }
}

/** 事件类型的中文标签（与服务端一致）。 */
export function eventTypeLabel(eventType: string): string {
  switch (eventType) {
    case 'PROMOTION':
      return '大促';
    case 'FLASH_SALE':
      return '秒杀';
    case 'STOCKOUT':
      return '断货';
    case 'SHORTAGE':
      return '缺货';
    default:
      return eventType;
  }
}

/** 可用性（AVAILABLE / NO_DATA / NOT_COMPARABLE）的语义色。 */
export function availabilityTone(availability: string): Tone {
  return availability === 'AVAILABLE' ? 'success' : 'neutral';
}

/** 可用性的中文标签。 */
export function availabilityLabel(availability: string): string {
  switch (availability) {
    case 'AVAILABLE':
      return '有数据';
    case 'NOT_COMPARABLE':
      return '不可比';
    case 'NO_DATA':
      return '没有数据';
    default:
      return availability;
  }
}

// ================================================================ 趋势

/** 取某个指标 + 币种的序列（找不到 ⇒ null）。 */
export function seriesOf(
  response: SalesTrendResponse | null,
  metricCode: string,
  currency: string,
): SalesTrendSeries | null {
  if (!response) return null;
  return (
    response.series.find((s) => s.metricCode === metricCode && s.currency === currency) ?? null
  );
}

/** 序列里某个桶的点（找不到 ⇒ null = 那个桶没有事实）。 */
export function pointOf(
  series: SalesTrendSeries | null,
  bucketStart: string,
): SalesTrendPoint | null {
  if (!series) return null;
  return series.points.find((p) => p.bucketStart === bucketStart) ?? null;
}

/** 桶的展示标签（服务端已给，原样显示）。 */
export function bucketLabel(point: SalesTrendPoint): string {
  return point.bucketLabel;
}

/** 环比的可比性说明（基期缺失 ⇒ 说清为什么不可比）。 */
export function periodOverPeriodNote(point: SalesTrendPoint): string {
  if (point.periodOverPeriodRate !== null) return '';
  if (point.periodOverPeriodValue === null) {
    return `上一个同粒度桶（${point.periodOverPeriodBucketStart ?? '—'}）**没有事实** ⇒ 环比不可比。`;
  }
  return '上一个同粒度桶的值为 0 ⇒ 环比不可比（不是 0%）。';
}

/** 同比的可比性说明。 */
export function yearOverYearNote(point: SalesTrendPoint): string {
  if (point.yearOverYearRate !== null) return '';
  if (point.yearOverYearValue === null) {
    return `去年同期（${point.yearOverYearBucketStart ?? '—'}）**没有事实** ⇒ 同比不可比。`;
  }
  return '去年同期的值为 0 ⇒ 同比不可比（不是 0%）。';
}

/** 一个桶上挂着的事件（异常必须能关联事件）。 */
export function eventsOf(point: SalesTrendPoint): SalesTrendEvent[] {
  return point.events;
}

/** 异常的说明（服务端原样显示，含「查不到原因」）。 */
export function anomalyNote(anomaly: SalesTrendAnomaly): string {
  return anomaly.note;
}

/** 本次响应的全部异常点。 */
export function anomaliesOf(response: SalesTrendResponse | null): SalesTrendAnomaly[] {
  return response?.anomalies ?? [];
}

// ================================================================ 多维对比

/** 对比矩阵里的一个指标取值。 */
export function comparisonMetricOf(
  row: SalesTrendComparisonRow,
  metricCode: string,
): SalesTrendComparisonMetric | null {
  return row.metrics.find((m) => m.metricCode === metricCode) ?? null;
}

/** 对比矩阵单元格的取值文本（null ⇒ 「—」/「不可比」，不冒充 0）。 */
export function comparisonCellText(
  row: SalesTrendComparisonRow,
  metricCode: string,
  unit: string,
): string {
  const metric = comparisonMetricOf(row, metricCode);
  if (!metric) return '—';
  return metricValueText(metric.value, unit, row.currency);
}

/** 对比矩阵单元格的环比文本。 */
export function comparisonGrowthText(
  row: SalesTrendComparisonRow,
  metricCode: string,
): string {
  const metric = comparisonMetricOf(row, metricCode);
  if (!metric) return '—';
  return percentText(metric.periodOverPeriodRate);
}

/** 对比矩阵单元格的同比文本。 */
export function comparisonYearText(row: SalesTrendComparisonRow, metricCode: string): string {
  const metric = comparisonMetricOf(row, metricCode);
  if (!metric) return '—';
  return percentText(metric.yearOverYearRate);
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
export function pageState(response: SalesTrendResponse | null, error: string): PageState {
  if (error) return 'error';
  if (!response) return 'loading';
  if (response.series.length === 0) return 'empty';
  return 'ready';
}

/** 空态文案（服务端给的原因**原样显示**，前端不自己编一个）。 */
export function emptyText(response: SalesTrendResponse | null): string {
  if (!response) return '';
  if (response.series.length === 0) {
    return response.emptyNote || '这个时间窗口里没有任何销量事实。';
  }
  return '';
}

/** 空态的原因标签。 */
export function emptyReasonLabel(reason: string | null): string {
  switch (reason) {
    case 'NOT_PROJECTED':
      return '还没有投影事实';
    case 'NO_FACTS_IN_WINDOW':
      return '这个窗口没有数据';
    default:
      return '';
  }
}

/** 本次查询的整体状态文案。 */
export function statusLabel(status: string): string {
  switch (status) {
    case 'PROJECTED':
      return '已投影';
    case 'NOT_PROJECTED':
      return '未投影';
    case 'PARTIAL':
      return '部分投影';
    default:
      return status;
  }
}

// ================================================================ 数据更新时间

/**
 * 「数据截至 X」文案。
 *
 * <p>🔴 卷 13 卷首加粗句「<b>数据延迟要显示</b>」：用户必须一眼看出看到的是 T-1 还是实时。
 */
export function dataUpdatedText(response: SalesTrendResponse | null): string {
  if (!response) return '';
  if (!response.dataUpdatedAt) return '数据更新时间：尚未投影任何销量事实';
  return `数据截至 ${response.dataUpdatedAt}`;
}

/** 「数据截至」这句话的口径说明（这个时刻**具体指什么**）。 */
export function dataUpdatedNote(response: SalesTrendResponse | null): string {
  return response?.dataUpdatedNote ?? '';
}

// ================================================================ 时间范围与筛选

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

/** 实际生效的范围文本（对齐到粒度后的范围）。 */
export function effectiveRangeText(response: SalesTrendResponse | null): string {
  if (!response) return '';
  if (response.from === response.requestedFrom && response.to === response.requestedTo) {
    return rangeText(response.from, response.to);
  }
  return `${rangeText(response.from, response.to)}（已对齐到${granularityLabel(response.granularity)}）`;
}

/** 粒度的中文标签。 */
export function granularityLabel(granularity: string): string {
  switch (granularity) {
    case 'DAY':
      return '日';
    case 'WEEK':
      return '周';
    case 'MONTH':
      return '月';
    case 'HOUR':
      return '小时';
    default:
      return granularity;
  }
}

/** 粒度下拉项。 */
export const GRANULARITY_OPTIONS: readonly { value: string; label: string }[] = [
  { value: 'DAY', label: '按日' },
  { value: 'WEEK', label: '按周' },
  { value: 'MONTH', label: '按月' },
  { value: 'HOUR', label: '按小时' },
];

/** 维度下拉项。 */
export const DIMENSION_OPTIONS: readonly { value: string; label: string }[] = [
  { value: 'TOTAL', label: '公司（同比 / 环比）' },
  { value: 'STORE', label: '店铺间对比' },
  { value: 'PRODUCT', label: '产品间对比（SKU）' },
];

// ================================================================ 口径目录

/** 本次展示的指标口径（服务端给的目录）。 */
export function metricDefinitionsOf(
  response: SalesTrendResponse | null,
): SalesTrendMetricDefinitionView[] {
  return response?.metricDefinitions ?? [];
}

/** 口径的说明（服务端原文，原样显示）。 */
export function caliberNotesOf(response: SalesTrendResponse | null): string[] {
  return response?.caliberNotes ?? [];
}

/** 必须常驻显示的提醒（多币种 / 折算 / 维度口径差异）。 */
export function warningsOf(response: SalesTrendResponse | null): string[] {
  return response?.warnings ?? [];
}

// ================================================================ 能力声明

/**
 * 能力声明里的「确实拿不到」的部分。
 *
 * <p>⚠️ <b>这些不是 bug，是边界</b>：把边界藏起来，用户会以为「这一格没数据是出错了」；
 * 写出来，用户才知道去哪儿找。
 */
export function notDeliveredLines(capabilities: SalesTrendCapabilities | null): string[] {
  return capabilities?.notDelivered ?? [];
}

/** 本服务真的读过的上游模块。 */
export function upstreamModules(capabilities: SalesTrendCapabilities | null): string[] {
  return capabilities?.upstreamModules ?? [];
}

/** 判定规则（阈值写清楚，页面上照着显示）。 */
export function ruleNotes(capabilities: SalesTrendCapabilities | null): SalesTrendRuleNote[] {
  return capabilities?.rules ?? [];
}

/** 可用粒度（服务端给的）。 */
export function granularityOptions(capabilities: SalesTrendCapabilities | null): string[] {
  return capabilities?.granularities ?? [];
}

/** 可用维度（服务端给的）。 */
export function dimensionOptions(capabilities: SalesTrendCapabilities | null): string[] {
  return capabilities?.dimensions ?? [];
}

// ================================================================ 事件

/** 异常事件清单。 */
export function eventsOfResponse(response: SalesTrendEventResponse | null): SalesTrendEvent[] {
  return response?.events ?? [];
}

/** 事件的展示文本（标题 + 日期区间 + 范围）。 */
export function eventText(event: SalesTrendEvent): string {
  const scope =
    event.shopCode && event.skuCode
      ? `（店铺 ${event.shopCode} · 商品 ${event.skuCode}）`
      : event.shopCode
        ? `（店铺 ${event.shopCode}）`
        : event.skuCode
          ? `（商品 ${event.skuCode}）`
          : '（全局）';
  return `${event.title} ${event.eventDate} ~ ${event.endDate} ${scope}`;
}

// ================================================================ 刷新

/** 刷新结果的中文状态。 */
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

/** 刷新结果的语义色。 */
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

/** 刷新结果列表。 */
export function refreshResultsOf(results: SalesTrendRefreshResult[] | null): SalesTrendRefreshResult[] {
  return results ?? [];
}
