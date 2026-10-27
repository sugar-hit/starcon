/**
 * 13.2.2.1 店铺与站点 前端模型（卷 13「利润与经营分析」L3 13.2.2 维度拆解下的第一格）。
 *
 * <p>蓝图原文：「按店铺、站点、平台维度拆解销量与销售额，含店铺增长贡献、新店/老店区分、渠道占比。」
 * 输入「维度选择；时间范围。」输出「分组报表 + 占比与贡献度。」
 * 关系：上游 = 13.2.1.1；下游 = 13.1.1.2。
 * **增长贡献度比绝对值有用** —— 告诉老板"哪个店/哪个品带来了增量"。
 *
 * <p><b>🔴 这一页最容易做丢的五件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「等于 0」</b>。服务端把分母为 0 / 没有事实的取值置为
 *       {@code null}，这里渲染成「—」或「不可比」+ 原因，<b>绝不显示成 0.00 或 0%</b> ——
 *       假 0 会被读成「这个店没卖出去」。</li>
 *   <li><b>🔴 多币种绝不相加</b>。成交金额按币种分别列出；只有件数与单数（与货币无关）给一个
 *       跨币种合计，它的币种是<b>空串</b> —— 空串 = 与币种无关，<b>不是「币种未知」</b>。</li>
 *   <li><b>🔴 站点维度的归属边界必须显式</b>。订单不带站点，启用多个站点的店铺其销量
 *       <b>归不了任何站点</b> —— 页面上必须把「无法唯一归属到站点」单列出来，
 *       <b>不让用户以为「这个站点就是没卖出去」</b>。</li>
 *   <li><b>🔴 增长贡献度与占比不是同一个数</b>。占比看<b>当前窗口</b>，贡献度看<b>增量</b> ——
 *       两者混为一谈就会把「跌得最狠的店」说成「贡献最大的店」。</li>
 *   <li><b>数据更新时间常驻</b>。卷 13 卷首加粗句「数据延迟要显示」。</li>
 * </ol>
 *
 * <p><b>前端不做业务算术</b>。占比、增长贡献度、汇总全部来自服务端
 * （{@code BigDecimal} 定标），这里只做<b>展示格式化</b>，不做任何加减乘除 ——
 * 前端算一遍就会引入二进制浮点误差，而且会让界面和服务端的数对不上。
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  StoreSiteCapabilities,
  StoreSiteChannelShare,
  StoreSiteCoverage,
  StoreSiteDrilldownResponse,
  StoreSiteDrilldownRow,
  StoreSiteMetricDefinitionView,
  StoreSiteMetricValue,
  StoreSiteNewVsExisting,
  StoreSiteRefreshResult,
  StoreSiteResponse,
  StoreSiteRow,
  StoreSiteRuleNote,
  StoreSiteUnattributed,
} from '../../../../../packages/api-contracts/src/index';

export type {
  StoreSiteCapabilities,
  StoreSiteChannelShare,
  StoreSiteCoverage,
  StoreSiteDrilldownResponse,
  StoreSiteDrilldownRow,
  StoreSiteMetricDefinitionView,
  StoreSiteMetricValue,
  StoreSiteNewVsExisting,
  StoreSiteRefreshResult,
  StoreSiteResponse,
  StoreSiteRow,
  StoreSiteRuleNote,
  StoreSiteUnattributed,
};

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

/** 聚合方式的展示名。 */
export function aggregationLabel(aggregation: string): string {
  switch (aggregation) {
    case 'SUM':
      return '窗口内相加';
    case 'GROUP_SHARE':
      return '组内占比（÷ 同层级合计）';
    case 'GROUP_CONTRIBUTION':
      return '增长贡献度（÷ 全部维度增量之和）';
    case 'WINDOW_GROWTH':
      return '窗口环比（÷ 上一等长窗口）';
    default:
      return aggregation;
  }
}

/**
 * 口径的「怎么算出来的」说明。
 *
 * <p>🔴 **被比较的指标必须说清** —— 组内占比与增长贡献度都比的是另一个口径，
 * 不说清楚，页面上的「80%」就是个没有出处的数。
 */
export function metricFormulaText(definition: StoreSiteMetricDefinitionView): string {
  if (definition.baseMetricCode) {
    return `${definition.baseMetricCode}（${aggregationLabel(definition.aggregation)}）`;
  }
  return aggregationLabel(definition.aggregation);
}

/** 按指标单位分派的取值文本。 */
export function metricValueText(metric: StoreSiteMetricValue, unit: string): string {
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

// ================================================================ 维度层

/** 维度层的展示名。 */
export function dimensionLabel(dimension: string): string {
  switch (dimension) {
    case 'SHOP':
      return '店铺';
    case 'SITE':
      return '站点';
    case 'PLATFORM':
      return '平台（渠道）';
    default:
      return dimension;
  }
}

/** 维度层的下拉项。 */
export function dimensionOptions(): { value: string; label: string }[] {
  return [
    { value: 'SHOP', label: '店铺' },
    { value: 'SITE', label: '站点' },
    { value: 'PLATFORM', label: '平台（渠道）' },
  ];
}

/**
 * 平台标签。
 *
 * <p>🔴 **空平台码 = 尚未确认归属** —— 显示成「未标注平台」，**绝不猜一个平台**。
 */
export function platformLabel(platformCode: string): string {
  if (!platformCode) return '未标注平台';
  return platformCode;
}

// ================================================================ 语义色

/**
 * Badge 的 tone 取值（与 `@starcon/ui` 的 `Badge` 一致）。
 *
 * <p>本文件是纯函数层，不 import UI 包 —— 但 tone 的<b>取值集合</b>必须与 UI 一致，
 * 所以在这里显式列出来（多一个值就会在编译期报出来，而不是运行时悄悄变灰）。
 */
export type Tone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'brand';

/**
 * 取值可用性的语义色。
 *
 * <p>🔴 {@code NO_DATA} 与 {@code NOT_COMPARABLE} 都是 neutral：
 * 「这一格没有事实」和「这一格不可比」都是<b>如实说明</b>，不是错误。
 */
export function availabilityTone(availability: string): Tone {
  switch (availability) {
    case 'AVAILABLE':
      return 'success';
    case 'NOT_COMPARABLE':
      return 'neutral';
    case 'NO_DATA':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 取值可用性的中文标签。 */
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

/**
 * 新店 / 老店标签的语义色。
 *
 * <p>🔴 新店用 <b>info</b>（不是 success）—— 新店既不是好事也不是坏事，
 * 它是一个**待观察的事实**；用 success 会让运营误以为「新店 = 好」。
 */
export function storeAgeTone(age: string): Tone {
  switch (age) {
    case 'NEW':
      return 'info';
    case 'EXISTING':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 新店 / 老店的中文标签。 */
export function storeAgeLabel(age: string): string {
  switch (age) {
    case 'NEW':
      return '新店';
    case 'EXISTING':
      return '老店';
    default:
      return '';
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

// ================================================================ 报表的列

/** 指标码常量（与服务端口径一一对应）。 */
export const METRIC_SALES_AMOUNT = 'STORE_SITE_SALES_AMOUNT';
export const METRIC_SALES_QUANTITY = 'STORE_SITE_SALES_QUANTITY';
export const METRIC_ORDER_COUNT = 'STORE_SITE_ORDER_COUNT';
export const METRIC_AMOUNT_SHARE = 'STORE_SITE_AMOUNT_SHARE';
export const METRIC_QUANTITY_SHARE = 'STORE_SITE_QUANTITY_SHARE';
export const METRIC_AMOUNT_GROWTH = 'STORE_SITE_AMOUNT_GROWTH';
export const METRIC_QUANTITY_GROWTH = 'STORE_SITE_QUANTITY_GROWTH';
export const METRIC_AMOUNT_CONTRIBUTION = 'STORE_SITE_AMOUNT_GROWTH_CONTRIBUTION';
export const METRIC_QUANTITY_CONTRIBUTION = 'STORE_SITE_QUANTITY_GROWTH_CONTRIBUTION';
export const METRIC_NEW_STORE_CONTRIBUTION = 'STORE_SITE_NEW_STORE_AMOUNT_CONTRIBUTION';

/** 报表的一列 = 一个指标。 */
export interface StoreSiteColumn {
  metricCode: string;
  label: string;
  unit: string;
  /** 🔴 这一列是不是「按币种分行」的（金额类）。 */
  perCurrency: boolean;
}

/**
 * 报表的列（**只列出该租户已登记的**）。
 *
 * <p>⚠️ 口径是租户自己的（服务端的口径表是唯一真相），所以列不能写死 ——
 * 租户把某个指标关掉，列就该消失，而不是留一个永远空的表头。
 *
 * <p>🔴 **「新店增长贡献度」不出现在列里** —— 它是「新店这一组」的指标，
 * 只在「新店 / 老店」汇总块里出现。
 */
export const STORE_SITE_COLUMNS: readonly StoreSiteColumn[] = [
  { metricCode: METRIC_SALES_QUANTITY, label: '销量（件）', unit: 'QUANTITY', perCurrency: false },
  { metricCode: METRIC_SALES_AMOUNT, label: '成交金额', unit: 'CURRENCY', perCurrency: true },
  { metricCode: METRIC_ORDER_COUNT, label: '成交订单数', unit: 'COUNT', perCurrency: false },
  { metricCode: METRIC_AMOUNT_SHARE, label: '成交金额占比', unit: 'PERCENT', perCurrency: true },
  { metricCode: METRIC_QUANTITY_SHARE, label: '销量占比', unit: 'PERCENT', perCurrency: false },
  { metricCode: METRIC_AMOUNT_GROWTH, label: '金额环比增长率', unit: 'PERCENT', perCurrency: true },
  { metricCode: METRIC_QUANTITY_GROWTH, label: '销量环比增长率', unit: 'PERCENT', perCurrency: false },
  {
    metricCode: METRIC_AMOUNT_CONTRIBUTION,
    label: '金额增长贡献度',
    unit: 'PERCENT',
    perCurrency: true,
  },
  {
    metricCode: METRIC_QUANTITY_CONTRIBUTION,
    label: '销量增长贡献度',
    unit: 'PERCENT',
    perCurrency: false,
  },
];

/** 只列出**该租户已登记**的列。 */
export function storeSiteColumns(definitions: StoreSiteMetricDefinitionView[]): StoreSiteColumn[] {
  const known = new Map(definitions.map((d) => [d.metricCode, d]));
  return STORE_SITE_COLUMNS.filter((column) => known.has(column.metricCode)).map((column) => {
    const definition = known.get(column.metricCode);
    return {
      ...column,
      label: definition?.displayName ?? column.label,
      unit: definition?.unit ?? column.unit,
    };
  });
}

/** 矩阵里某个维度某个指标的取值（找不到 ⇒ null = 算不出来）。 */
export function cellOf(row: StoreSiteRow, metricCode: string): StoreSiteMetricValue | null {
  return row.metrics.find((m) => m.metricCode === metricCode) ?? null;
}

/**
 * 单元格的取值文本。
 *
 * <p>🔴 **按币种分行的列**一行可能有**多条**取值（每个币种一条）——
 * 这里把它们的文字合起来（服务端已经算好，这里只拼字符串，**不做任何加减**）。
 */
export function cellText(row: StoreSiteRow, column: StoreSiteColumn): string {
  const matches = row.metrics.filter((m) => m.metricCode === column.metricCode);
  if (matches.length === 0) return '—';
  return matches.map((metric) => metricValueText(metric, column.unit)).join(' / ');
}

/** 单元格的可用性（AVAILABLE / NO_DATA / NOT_COMPARABLE）。 */
export function cellAvailability(row: StoreSiteRow, column: StoreSiteColumn): string {
  const matches = row.metrics.filter((m) => m.metricCode === column.metricCode);
  if (matches.length === 0) return 'NO_DATA';
  if (matches.some((m) => m.availability === 'AVAILABLE')) return 'AVAILABLE';
  return matches[0]!.availability;
}

/** 单元格的说明（为什么没有数据 / 为什么不可比）。 */
export function cellNote(row: StoreSiteRow, column: StoreSiteColumn): string {
  const matches = row.metrics.filter((m) => m.metricCode === column.metricCode);
  if (matches.length === 0) return '这一列在这个维度上没有指标定义。';
  return matches
    .map((m) => m.availabilityNote)
    .filter((note) => note && note.length > 0)
    .join(' ');
}

/** 一行涉及的币种（金额类指标按币种分别列）。 */
export function rowCurrencies(row: StoreSiteRow): string[] {
  const out = new Set<string>();
  for (const metric of row.metrics) {
    if (metric.currency) out.add(metric.currency);
  }
  return [...out];
}

/**
 * 🔴 **占比与贡献度不是同一个数**：占比看当前窗口，贡献度看增量。
 *
 * <p>这一句在页面上必须能被用户看到 —— 否则「跌得最狠的店」会被读成「贡献最大的店」。
 */
export function shareVsContributionNote(): string {
  return (
    '**占比看当前窗口**（这个店现在占多少），**贡献度看增量**（这次的增量里它占多少）—— ' +
    '一个在缩的店可能占比很高、贡献度却是负的。'
  );
}

// ================================================================ 渠道占比

/** 渠道（平台）占比里某个指标的取值文本。 */
export function channelShareText(share: StoreSiteChannelShare, metricCode: string, unit: string): string {
  const matches = share.metrics.filter((m) => m.metricCode === metricCode);
  if (matches.length === 0) return '—';
  return matches.map((metric) => metricValueText(metric, unit)).join(' / ');
}

/** 渠道占比的标签（空平台码 ⇒ 未标注平台）。 */
export function channelShareLabel(share: StoreSiteChannelShare): string {
  const name = share.platformName || platformLabel(share.platformCode);
  return share.platformCode ? `${name}（${share.platformCode}）` : name;
}

// ================================================================ 新店 / 老店

/**
 * 新店 / 老店汇总的说明。
 *
 * <p>🔴 **判据是成交事实**：当前窗口有成交、基期没有任何成交事实 ⇒ 新店。
 */
export function newVsExistingText(summary: StoreSiteNewVsExisting | null): string {
  if (!summary) return '';
  const parts = [`新店 ${summary.newShopCount} 个`, `老店 ${summary.existingShopCount} 个`];
  const contribution = summary.newStoreContribution
    .map((m) => `${m.currency || '跨币种'} ${percentText(m.value)}`)
    .join(' / ');
  if (contribution) parts.push(`新店对增量的贡献 ${contribution}`);
  return parts.join('，');
}

// ================================================================ 无法唯一归属

/**
 * 「无法唯一归属到站点」的说明文案。
 *
 * <p>🔴 这一段**必须常驻显示**：它是「站点维度的销量为什么不完整」的答案。
 * 藏起来的话，用户会以为「这个站点就是没卖出去」。
 */
export function unattributedText(unattributed: StoreSiteUnattributed | null): string {
  if (!unattributed || unattributed.shopCount === 0) return '';
  const amounts = unattributed.metrics
    .filter((m) => m.metricCode === METRIC_SALES_AMOUNT)
    .map((m) => amountText(m.value, m.currency))
    .join(' / ');
  const shops = unattributed.shopCodes.join('、');
  return (
    `有 ${unattributed.shopCount} 个店铺（${shops}）的销量**无法唯一归属到站点**` +
    (amounts ? `（涉及 ${amounts}）` : '') +
    ' —— 订单不带站点，店铺启用多个站点（或没启用站点）时摊给谁是造假，因此单列。'
  );
}

// ================================================================ 覆盖度

/** 销量覆盖度的说明文案。 */
export function coverageText(coverage: StoreSiteCoverage | null): string {
  if (!coverage) return '';
  if (!coverage.truncated) return coverage.note;
  return (
    `销量只覆盖了 ${coverage.orderCountWithDetail} / ${coverage.orderCount} 张订单` +
    (coverage.detailCoveragePercent === null
      ? ''
      : `（明细覆盖率 ${formatNumber(coverage.detailCoveragePercent, PERCENT_DECIMALS)}%）`) +
    ' —— 未覆盖部分的销量**没有算进来**，成交金额与订单数不受影响。'
  );
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
export function pageState(response: StoreSiteResponse | null, error: string): PageState {
  if (error) return 'error';
  if (!response) return 'loading';
  if (response.rows.length === 0) return 'empty';
  return 'ready';
}

/** 空态文案（服务端给的原因**原样显示**，前端不自己编一个）。 */
export function emptyText(response: StoreSiteResponse | null): string {
  if (!response) return '';
  if (response.rows.length === 0) {
    return response.emptyNote || '这个时间窗口里没有任何店铺维度事实。';
  }
  return '';
}

/** 空态的原因标签。 */
export function emptyReasonLabel(reason: string | null): string {
  switch (reason) {
    case 'NO_FACTS_IN_WINDOW':
      return '这个窗口没有可拆解的成交事实';
    case 'NO_DIMENSION':
      return '找不到这个维度';
    case 'NO_DRILLDOWN':
      return '这个维度没有构成明细';
    default:
      return '';
  }
}

/** 本次报表的整体状态文案。 */
export function statusLabel(status: string): string {
  switch (status) {
    case 'OK':
      return '正常';
    case 'EMPTY':
      return '空';
    default:
      return status;
  }
}

// ================================================================ 数据更新时间

/**
 * 「数据截至 X」文案。
 *
 * <p>🔴 卷 13 卷首加粗句「**数据延迟要显示**」：用户必须一眼看出看到的是 T-1 还是实时。
 * 空表时是「尚未投影」—— **不是一个长得像时间的假值**。
 */
export function dataUpdatedText(response: StoreSiteResponse | null): string {
  if (!response) return '';
  if (!response.dataUpdatedAt) return '数据更新时间：尚未投影任何店铺维度事实';
  return `数据截至 ${response.dataUpdatedAt}`;
}

/** 「数据截至」这句话的口径说明（这个时刻**具体指什么**）。 */
export function dataUpdatedNote(response: StoreSiteResponse | null): string {
  return response?.dataUpdatedNote ?? '';
}

// ================================================================ 能力声明

/**
 * 能力声明里的「确实拿不到」的部分。
 *
 * <p>⚠️ **这些不是 bug，是边界**：站点归属、销量明细上限在事实本身上就有。
 * 把它们藏起来，用户会以为「这一格没数据是出错了」。
 */
export function notDeliveredLines(capabilities: StoreSiteCapabilities | null): string[] {
  return capabilities?.notDelivered ?? [];
}

/** 本服务真的读过的上游模块。 */
export function upstreamModules(capabilities: StoreSiteCapabilities | null): string[] {
  return capabilities?.upstreamModules ?? [];
}

/** 判定规则与口径说明（阈值写清楚，页面上照着显示）。 */
export function ruleNotes(capabilities: StoreSiteCapabilities | null): StoreSiteRuleNote[] {
  return capabilities?.rules ?? [];
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

/** 基期（上一等长窗口）文本 —— 增长贡献度算不出来时要说清楚基期是哪一段。 */
export function baseRangeText(response: StoreSiteResponse | null): string {
  if (!response) return '';
  return `${response.baseFrom} ~ ${response.baseTo}`;
}

// ================================================================ 构成明细

/** 构成明细里「哪张订单的哪一行」的展示文本。 */
export function drilldownReferenceText(row: StoreSiteDrilldownRow): string {
  if (row.referenceLabel) return row.referenceLabel;
  return row.referenceId;
}

/** 构成明细的下单时刻文本（null ⇒ 「—」）。 */
export function occurredAtText(row: StoreSiteDrilldownRow): string {
  if (!row.occurredAt) return '—';
  return row.occurredAt;
}

/** 构成明细的类型标签。 */
export function drilldownKindLabel(kind: string): string {
  switch (kind) {
    case 'ORDER':
      return '订单级';
    case 'ORDER_LINE':
      return '订单行级';
    default:
      return kind;
  }
}
