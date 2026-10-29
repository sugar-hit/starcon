/**
 * 13.3.2.1 流量转化 前端模型（卷 13「利润与经营分析」L3 13.3.2 流量与转化下的第一格）。
 *
 * <p>蓝图原文：「分析流量与转化：曝光、点击、Session、转化率、客单价，区分自然与广告流量，
 * 计算转化漏斗。」输入「平台流量数据同步；订单数据。」输出「漏斗视图 + 转化率趋势 + 异常诊断。」
 * 关系：上游 = 2 同步、14 广告；下游 = 13.3.1.1。
 * **平台流量数据与内部订单数据口径不同，展示时必须标注来源与统计范围（页面 vs 订单）。**
 *
 * <p><b>🔴 这一页最容易做丢的四件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>两套口径必须分列、不混算</b>。平台口径（页面口径：曝光 / 点击 / Session / 平台成交）
 *       与订单口径（内部订单的笔数与金额）<b>各有各的指标</b>；转化率有两个口径、
 *       客单价有三个口径，每个值都带自己的标签 —— 页面上把口径标签<b>贴在值旁边</b>，
 *       不藏在 tooltip 里。</li>
 *   <li><b>「算不出来」≠「等于 0」</b>。服务端把分母为 0 / 没有事实的取值置为 {@code null}，
 *       这里渲染成「—」/「不可比」+ 原因，<b>绝不用 0.00 或 0% 顶替</b> ——
 *       假 0 会被读成「这段时间没流量」。</li>
 *   <li><b>自然流量是推导值</b>（平台商品口径 − 广告口径）。页面必须写明它是推导值，
 *       相减为负时显示「口径冲突」而不是 0。</li>
 *   <li><b>数据更新时间常驻</b>。卷 13 卷首加粗句「数据延迟要显示」。</li>
 * </ol>
 *
 * <p><b>前端不做业务算术</b>。漏斗环节的取值、环节转化率、趋势值、诊断全部来自服务端
 * （{@code BigDecimal} 定标），这里只做<b>展示格式化</b>，不做任何加减乘除 ——
 * 前端算一遍就会引入二进制浮点误差，而且会让界面和服务端的数对不上。
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  TrafficConversionCapabilities,
  TrafficConversionResponse,
  TrafficDiagnosis,
  TrafficDrilldownResponse,
  TrafficDrilldownRow,
  TrafficFunnelGroup,
  TrafficFunnelStage,
  TrafficMetricDefinitionView,
  TrafficMetricValue,
  TrafficRefreshResult,
  TrafficRuleNote,
  TrafficTrendPoint,
} from '../../../../../packages/api-contracts/src/index';

export type {
  TrafficConversionCapabilities,
  TrafficConversionResponse,
  TrafficDiagnosis,
  TrafficDrilldownResponse,
  TrafficDrilldownRow,
  TrafficFunnelGroup,
  TrafficFunnelStage,
  TrafficMetricDefinitionView,
  TrafficMetricValue,
  TrafficRefreshResult,
  TrafficRuleNote,
  TrafficTrendPoint,
};

// ================================================================ 指标码（与服务端口径表一致）

/** 曝光（平台商品口径）。 */
export const METRIC_IMPRESSIONS = 'TRAFFIC_IMPRESSIONS';
/** 点击（平台商品口径）。 */
export const METRIC_CLICKS = 'TRAFFIC_CLICKS';
/** Session（平台商品口径）。 */
export const METRIC_SESSIONS = 'TRAFFIC_SESSIONS';
/** 成交件数（平台口径）。 */
export const METRIC_PLATFORM_UNITS = 'TRAFFIC_PLATFORM_UNITS';
/** 转化率（平台商品口径）。 */
export const METRIC_CONVERSION_RATE = 'TRAFFIC_CONVERSION_RATE';
/** 转化率（广告口径）。 */
export const METRIC_AD_CONVERSION_RATE = 'TRAFFIC_AD_CONVERSION_RATE';
/** 客单价（订单口径）。 */
export const METRIC_ORDER_AOV = 'TRAFFIC_ORDER_AOV';
/** 客单价（平台口径）。 */
export const METRIC_PLATFORM_AOV = 'TRAFFIC_PLATFORM_AOV';
/** 客单价（广告口径）。 */
export const METRIC_AD_AOV = 'TRAFFIC_AD_AOV';
/** 自然流量曝光（推导值）。 */
export const METRIC_ORGANIC_IMPRESSIONS = 'TRAFFIC_ORGANIC_IMPRESSIONS';
/** 自然流量点击（推导值）。 */
export const METRIC_ORGANIC_CLICKS = 'TRAFFIC_ORGANIC_CLICKS';
/** 成交订单数（订单口径）。 */
export const METRIC_ORDER_COUNT = 'TRAFFIC_ORDER_COUNT';
/** 成交金额（订单口径）。 */
export const METRIC_ORDER_AMOUNT = 'TRAFFIC_ORDER_AMOUNT';

// ================================================================ 展示格式化

/** 金额刻度（与服务端 numeric(24,4) 对齐，展示时保留 2 位即可）。 */
const MONEY_DECIMALS = 2;

/** 百分比刻度（服务端已经算成百分数，例如 -15.0000 = -15%）。 */
const PERCENT_DECIMALS = 2;

/** 件数刻度（服务端 numeric(19,6)，展示时保留 3 位）。 */
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
 * <p>🔴 **空串 = 与币种无关**（件数 / 单数的跨币种合计），**不是「币种未知」** ——
 * 渲染成「未知」会让人以为这里有数据问题。
 */
export function currencyLabel(currency: string): string {
  if (!currency) return '与币种无关';
  return currency;
}

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

/**
 * 🔴 **口径标签**（页面上必须贴在值旁边）。
 *
 * <p>蓝图加粗句：「平台流量数据与内部订单数据口径不同，展示时必须标注来源与统计范围
 * （页面 vs 订单）。」所以每个指标、每一节漏斗都带这个标签。
 */
export function caliberLabel(caliber: string): string {
  switch (caliber) {
    case 'PLATFORM':
      return '平台口径（页面口径）';
    case 'ORDER':
      return '订单口径';
    default:
      return caliber;
  }
}

/** 🔴 **来源标签**（流量来自哪一套报表）。 */
export function trafficSourceLabel(source: string): string {
  switch (source) {
    case 'LISTING':
      return '平台商品口径（2 同步的平台表现）';
    case 'AD':
      return '广告口径（14 广告的活动报表）';
    case 'ORDER':
      return '订单口径（9 订单）';
    case 'ORGANIC':
      return '自然流量（推导值）';
    default:
      return source;
  }
}

/** 来源的短标签（表格里空间小的时候用）。 */
export function trafficSourceShortLabel(source: string): string {
  switch (source) {
    case 'LISTING':
      return '平台商品';
    case 'AD':
      return '广告';
    case 'ORDER':
      return '订单';
    case 'ORGANIC':
      return '自然（推导）';
    default:
      return source;
  }
}

/** 按指标单位分派的取值文本。 */
export function metricValueText(metric: TrafficMetricValue): string {
  switch (metric.unit) {
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

/** 漏斗一节的取值文本（**只做格式化**，不算环节转化率）。 */
export function funnelStageValueText(stage: TrafficFunnelStage): string {
  switch (stage.unit) {
    case 'CURRENCY':
      return amountText(stage.value, stage.currency);
    case 'PERCENT':
      return percentText(stage.value);
    case 'QUANTITY':
      return quantityText(stage.value);
    case 'COUNT':
      return countText(stage.value);
    default:
      return stage.value === null ? '—' : formatNumber(stage.value, PERCENT_DECIMALS);
  }
}

// ================================================================ 语义色

/**
 * Badge 的 tone 取值（与 `@starcon/ui` 的 `Badge` 一致）。
 *
 * <p>本文件是纯函数层，不 import UI 包 —— 但 tone 的<b>取值集合</b>必须与 UI 一致，
 * 所以在这里显式列出来（多一个值就会在编译期报出来，而不是运行时悄悄变灰）。
 */
export type Tone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'brand';

/** 取值可用性的语义色。 */
export function availabilityTone(availability: string): Tone {
  switch (availability) {
    case 'AVAILABLE':
      return 'success';
    case 'NOT_COMPARABLE':
      return 'warning';
    case 'CALIBER_CONFLICT':
      return 'danger';
    case 'NO_DATA':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 取值可用性的文案。 */
export function availabilityLabel(availability: string): string {
  switch (availability) {
    case 'AVAILABLE':
      return '可用';
    case 'NO_DATA':
      return '没有数据';
    case 'NOT_COMPARABLE':
      return '不可比';
    case 'CALIBER_CONFLICT':
      return '口径冲突';
    default:
      return availability;
  }
}

/** 诊断严重度的语义色。 */
export function diagnosisTone(severity: string): Tone {
  switch (severity) {
    case 'HIGH':
      return 'danger';
    case 'MEDIUM':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 诊断严重度的文案。 */
export function diagnosisSeverityLabel(severity: string): string {
  switch (severity) {
    case 'HIGH':
      return '严重';
    case 'MEDIUM':
      return '关注';
    default:
      return severity;
  }
}

/** 诊断规则码的文案。 */
export function diagnosisRuleLabel(ruleCode: string): string {
  switch (ruleCode) {
    case 'IMPRESSION_COLLAPSE':
      return '曝光断崖';
    case 'CTR_DROP':
      return '点击率骤降';
    case 'CONVERSION_RATE_DROP':
      return '转化率骤降';
    case 'SESSION_CLICK_GAP':
      return '点击与 Session 口径冲突';
    case 'CALIBER_GAP':
      return '平台口径与订单口径背离';
    default:
      return ruleCode;
  }
}

/** 刷新结果的语义色。 */
export function refreshStatusTone(status: string): Tone {
  switch (status) {
    case 'SUCCEEDED':
      return 'success';
    case 'TRUNCATED':
      return 'warning';
    case 'FAILED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 刷新结果的文案。 */
export function refreshStatusLabel(status: string): string {
  switch (status) {
    case 'SUCCEEDED':
      return '成功';
    case 'TRUNCATED':
      return '已截断';
    case 'FAILED':
      return '失败';
    default:
      return status;
  }
}

// ================================================================ 粒度

/** 粒度的展示名。 */
export function granularityLabel(granularity: string): string {
  switch (granularity) {
    case 'DAY':
      return '日';
    case 'WEEK':
      return '周';
    case 'MONTH':
      return '月';
    default:
      return granularity;
  }
}

/** 粒度的下拉项（**只有三档**：本模块的事实按日落，给不了小时粒度）。 */
export function granularityOptions(): { value: string; label: string }[] {
  return [
    { value: 'DAY', label: '日' },
    { value: 'WEEK', label: '周' },
    { value: 'MONTH', label: '月' },
  ];
}

// ================================================================ 关键指标分组

/**
 * 关键指标按口径分组（**分列展示，不混算**）。
 *
 * <p>🔴 「平台口径」与「订单口径」是**两组**，不是一组的两种单位 ——
 * 混在一张表里会让人把两个口径的转化率/客单价当成同一个数。
 */
export function keyMetricGroups(
  response: TrafficConversionResponse | null,
): { caliber: string; label: string; metrics: TrafficMetricValue[] }[] {
  if (!response) return [];
  const groups: { caliber: string; label: string; metrics: TrafficMetricValue[] }[] = [];
  for (const caliber of ['PLATFORM', 'ORDER']) {
    const metrics = response.keyMetrics.filter((metric) => metric.caliber === caliber);
    if (metrics.length === 0) continue;
    groups.push({ caliber, label: caliberLabel(caliber), metrics });
  }
  return groups;
}

/** 找出关键指标里的某个指标（取不到返回 null）。 */
export function keyMetricOf(
  response: TrafficConversionResponse | null,
  metricCode: string,
): TrafficMetricValue | null {
  if (!response) return null;
  return response.keyMetrics.find((metric) => metric.metricCode === metricCode) ?? null;
}

/** 趋势桶里的某个指标（取不到返回 null）。 */
export function trendMetricOf(
  point: TrafficTrendPoint,
  metricCode: string,
): TrafficMetricValue | null {
  return point.metrics.find((metric) => metric.metricCode === metricCode) ?? null;
}

// ================================================================ 页面状态

/** 页面状态。 */
export type PageState = 'loading' | 'error' | 'empty' | 'ready';

/**
 * 页面状态机。
 *
 * <p>🔴 **「读不到」与「没有数据」是两种状态** —— 上游不可达是 error，
 * 上游可达但这个窗口没有事实是 empty。混成一个空态会让用户以为「这段时间没流量」。
 */
export function pageState(response: TrafficConversionResponse | null, error: string): PageState {
  if (error) return 'error';
  if (!response) return 'loading';
  if (response.status === 'EMPTY') return 'empty';
  return 'ready';
}

/** 空态文案。 */
export function emptyText(response: TrafficConversionResponse | null): string {
  if (!response) return '';
  return response.emptyNote || '这个窗口内没有可分析的流量事实。';
}

/** 空态原因的文案。 */
export function emptyReasonLabel(reason: string | null): string {
  switch (reason) {
    case 'NO_FACTS_IN_WINDOW':
      return '这个窗口内没有流量事实';
    default:
      return reason ?? '';
  }
}

/** 状态的文案。 */
export function statusLabel(status: string): string {
  switch (status) {
    case 'OK':
      return '就绪';
    case 'EMPTY':
      return '空';
    default:
      return status;
  }
}

/** 数据更新时间的文案（**空表时不给假时间**）。 */
export function dataUpdatedText(response: TrafficConversionResponse | null): string {
  if (!response) return '尚未读取数据';
  if (!response.dataUpdatedAt) return '尚未投影任何数据';
  return `数据截至 ${response.dataUpdatedAt}`;
}

/** 数据更新时间的说明。 */
export function dataUpdatedNote(response: TrafficConversionResponse | null): string {
  if (!response) return '报表是投影，不是实时的。';
  return response.dataUpdatedNote || '报表是投影，不是实时的。';
}

// ================================================================ 能力声明

/** 能力声明里的「确实拿不到」条目。 */
export function notDeliveredLines(capabilities: TrafficConversionCapabilities | null): string[] {
  return capabilities?.notDelivered ?? [];
}

/** 能力声明里的上游清单。 */
export function upstreamModules(capabilities: TrafficConversionCapabilities | null): string[] {
  return capabilities?.upstreamModules ?? [];
}

/** 能力声明里的规则清单。 */
export function ruleNotes(capabilities: TrafficConversionCapabilities | null): TrafficRuleNote[] {
  return capabilities?.rules ?? [];
}

/** 指标口径的「怎么算出来的」说明。 */
export function metricFormulaText(definition: TrafficMetricDefinitionView): string {
  if (definition.numeratorMetricCode && definition.denominatorMetricCode) {
    if (definition.aggregation === 'DERIVED_DIFF') {
      return `${definition.numeratorMetricCode} − ${definition.denominatorMetricCode}（推导值）`;
    }
    return `${definition.numeratorMetricCode} ÷ ${definition.denominatorMetricCode}`;
  }
  return '窗口内相加';
}

// ================================================================ 时间范围

/** 时间范围的文案。 */
export function rangeText(from: string, to: string): string {
  return `${from} ~ ${to}`;
}

/** 默认时间范围（最近 30 天，含今天）。 */
export function defaultRange(today: string): { from: string; to: string } {
  const end = new Date(`${today}T00:00:00Z`);
  const start = new Date(end.getTime());
  start.setUTCDate(start.getUTCDate() - 29);
  return { from: start.toISOString().slice(0, 10), to: today };
}

// ================================================================ 下钻

/** 明细的构成类型文案。 */
export function drilldownKindLabel(kind: string): string {
  switch (kind) {
    case 'LISTING':
      return '平台商品（平台口径）';
    case 'AD_CAMPAIGN':
      return '广告活动（广告口径）';
    case 'ORDER':
      return '内部订单（订单口径）';
    default:
      return kind;
  }
}

/** 明细的来源标签。 */
export function drilldownSourceLabel(source: string): string {
  return trafficSourceLabel(source);
}

/** 明细对象的可读标签。 */
export function drilldownReferenceText(row: TrafficDrilldownRow): string {
  if (row.referenceLabel && row.referenceLabel !== row.referenceId) {
    return `${row.referenceLabel}（${row.referenceId}）`;
  }
  return row.referenceId;
}

/** 发生时刻的文案（**订单口径的行才有**）。 */
export function occurredAtText(row: TrafficDrilldownRow): string {
  if (!row.occurredAt) return '—';
  return row.occurredAt;
}

/** 明细行的取值文本。 */
export function drilldownValueText(row: TrafficDrilldownRow): string {
  if (row.value && row.value !== 0) return amountText(row.value, row.currency);
  return quantityText(row.quantity);
}

/** 漏斗分组的来源说明（页面上常驻显示）。 */
export function funnelSourceNote(group: TrafficFunnelGroup): string {
  return group.sourceNote;
}

/** 诊断的取值文案（观测值 / 基线值）。 */
export function diagnosisValueText(diagnosis: TrafficDiagnosis): string {
  if (diagnosis.observed === null) return '—';
  return quantityText(diagnosis.observed);
}
