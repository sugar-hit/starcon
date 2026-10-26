/**
 * 13.1.1.2 多店对比 前端模型（卷 13「利润与经营分析」L3 13.1.1 经营看板下的第二格）。
 *
 * <p>蓝图原文：「多店铺横向对比：规模、增长、利润率、广告效率、退货率、库存周转，
 * 找出掉队店铺与增长店铺，支持按平台分组。」
 * 输入「店铺维度聚合；对比指标选择。」
 * 输出「对比矩阵 + 排名 + 差异归因提示。」
 * 关系：上游 = 13.1.1.1；下游 = 各店铺的专题分析。
 *
 * <p><b>🔴 这一页最容易做丢的五件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「等于 0」</b>。服务端把分母为 0 / 没有事实的取值置为
 *       {@code null}，这里渲染成「—」或「不可比」+ 原因，**绝不显示成 0.00 或 0%** ——
 *       假 0 会被读成「这个店铺销售为零」。</li>
 *   <li><b>🔴 跨平台口径差异必须显式标注</b>（蓝图关系句加粗要求：「亚马逊的 GMV 与 TikTok 的
 *       口径不同，对比时标注口径说明」）。矩阵里同时给「平台侧成交金额」与「还原口径成交金额」，
 *       两者的差是「口径差异额」；<b>不同平台的数字不能直接排名</b>，
 *       所以每个平台分组都挂了自己的口径说明。</li>
 *   <li><b>多币种绝不相加</b>。排名在**币种内**做 —— 一个 USD 店铺和一个 EUR 店铺的大小
 *       本来就不可比。</li>
 *   <li><b>数据更新时间常驻</b>。卷 13 卷首加粗句「数据延迟要显示」。</li>
 *   <li><b>前端不做业务算术</b>。增长率、口径差异额、派生比全部来自服务端
 *       （{@code BigDecimal} 定标），这里只做**展示格式化**，不做任何加减乘除 ——
 *       前端算一遍就会引入二进制浮点误差，而且会让界面和服务端的数对不上。</li>
 * </ol>
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  ComparisonCapabilities,
  ComparisonResponse,
  CurrencyRate,
  MetricRanking,
  PlatformGroup,
  RuleNote,
  StoreComparisonRow,
  StoreListResponse,
  StoreMetricDefinitionView,
  StoreMetricValue,
  StorePlatformOption,
  StoreRefreshResult,
  StoreSummary,
} from '../../../../../packages/api-contracts/src/index';

export type {
  ComparisonCapabilities,
  ComparisonResponse,
  CurrencyRate,
  MetricRanking,
  PlatformGroup,
  RuleNote,
  StoreComparisonRow,
  StoreListResponse,
  StoreMetricDefinitionView,
  StoreMetricValue,
  StorePlatformOption,
  StoreRefreshResult,
  StoreSummary,
};

// ================================================================ 展示格式化

/** 金额刻度（与服务端 numeric(24,4) 对齐，展示时保留 2 位即可）。 */
const MONEY_DECIMALS = 2;

/** 百分比刻度（服务端已经算成百分数，例如 -15.0000 = -15%）。 */
const PERCENT_DECIMALS = 2;

/** 倍数刻度（广告产出比 3.2 = 每 1 元花费带来 3.2 元广告销售额）。 */
const RATIO_DECIMALS = 2;

/** 笔数刻度。 */
const COUNT_DECIMALS = 0;

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
 * 百分比。
 *
 * <p>🔴 {@code null} ⇒ 「不可比」（**不是 0%**）：上一等长窗口没有事实或金额为 0 时，
 * 环比增长率根本不存在。
 */
export function percentText(value: number | null): string {
  if (value === null) return '不可比';
  const sign = value > 0 ? '+' : '';
  return `${sign}${formatNumber(value, PERCENT_DECIMALS)}%`;
}

/**
 * 倍数。
 *
 * <p>🔴 {@code null} ⇒ 「—」（**不是 0 倍**）：分母为 0 时这个比值算不出来。
 */
export function ratioText(value: number | null): string {
  if (value === null) return '—';
  return `${formatNumber(value, RATIO_DECIMALS)}×`;
}

/** 笔数。🔴 {@code null} ⇒ 「—」，不是 0。 */
export function countText(value: number | null): string {
  if (value === null) return '—';
  return formatNumber(value, COUNT_DECIMALS);
}

/**
 * 按指标单位分派的取值文本。
 *
 * <p>这是矩阵单元格的唯一渲染入口 —— 单位判断只在这里做一次，
 * 免得每个单元格各写一遍判断、然后在某个单位上漏掉「算不出来」的处理。
 *
 * <p>⚠️ 单位来自**口径定义**（{@code StoreMetricDefinitionView.unit}），不来自取值本身 ——
 * 取值里没有单位字段，猜单位就会把「百分比」当成「金额」渲染。
 */
export function metricValueText(metric: StoreMetricValue, unit: string): string {
  switch (unit) {
    case 'CURRENCY':
      return amountText(metric.value, metric.currency);
    case 'PERCENT':
      return percentText(metric.value);
    case 'RATIO':
      return ratioText(metric.value);
    case 'COUNT':
    case 'QUANTITY':
      return countText(metric.value);
    default:
      return metric.value === null ? '—' : formatNumber(metric.value, PERCENT_DECIMALS);
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
    case 'RATIO':
      return '倍数';
    default:
      return unit;
  }
}

/** 聚合方式的展示名。 */
export function aggregationLabel(aggregation: string): string {
  switch (aggregation) {
    case 'SUM':
      return '窗口内相加';
    case 'LAST':
      return '取最近一天快照';
    case 'DERIVED_DIFF':
      return '分子 − 分母';
    case 'DERIVED_RATIO':
      return '分子 ÷ 分母';
    case 'WINDOW_GROWTH':
      return '窗口环比';
    default:
      return aggregation;
  }
}

/**
 * 口径的「怎么算出来的」说明。
 *
 * <p>🔴 **派生型与环比型必须说清依赖的是谁** —— 否则页面上的「增长率」是个没有出处的数。
 * 窗口环比型尤其要说出它比的是**哪个指标**（{@code baseMetricCode}）。
 */
export function metricFormulaText(definition: StoreMetricDefinitionView): string {
  if (definition.aggregation === 'WINDOW_GROWTH') {
    return definition.baseMetricCode
      ? `窗口环比：比的是「${definition.baseMetricCode}」的当前窗口与上一等长窗口`
      : '窗口环比（**没有声明被比较的指标** —— 这个口径不成立）';
  }
  if (definition.numeratorMetricCode && definition.denominatorMetricCode) {
    return `${definition.numeratorMetricCode} ÷ ${definition.denominatorMetricCode}`;
  }
  if (definition.aggregation === 'DERIVED_DIFF') {
    return '分子 − 分母';
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
 * 店铺分类的语义色。
 *
 * <p>⚠️ **tone 跟语义挂钩，不跟颜色挂钩**：
 *
 * <ul>
 *   <li>增长店铺 ⇒ success（要去加码、值得复制）；</li>
 *   <li>掉队店铺 ⇒ warning（要去救，但**不是 danger**：掉队是经营状态，不是错误）；</li>
 *   <li>稳定 ⇒ neutral；</li>
 *   <li>不可比 ⇒ neutral（**不是 danger**：算不出来不是错误，是「这一格没数据」）。</li>
 * </ul>
 */
export function classificationTone(classification: string): Tone {
  switch (classification) {
    case 'GROWTH':
      return 'success';
    case 'LAGGARD':
      return 'warning';
    case 'STABLE':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 店铺分类的中文标签。 */
export function classificationLabel(classification: string): string {
  switch (classification) {
    case 'GROWTH':
      return '增长店铺';
    case 'LAGGARD':
      return '掉队店铺';
    case 'STABLE':
      return '稳定';
    case 'UNCLASSIFIED':
      return '不可比';
    default:
      return classification;
  }
}

/**
 * 取值可用性的语义色。
 *
 * <p>🔴 {@code NO_DATA} 与 {@code NOT_COMPARABLE} 都是 neutral：
 * 「这一格没有事实」和「这一格不可比」都是**如实说明**，不是错误。
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

/** 排名的展示文本。🔴 {@code null} ⇒ 「—」（没有可比数据就没有排名）。 */
export function rankText(rank: number | null): string {
  if (rank === null) return '—';
  return `第 ${rank} 名`;
}

// ================================================================ 平台

/**
 * 平台标签。
 *
 * <p>🔴 **空平台码 = 尚未确认归属**（上游只在广告活动里带店铺的平台，
 * 没有任何广告活动的店铺拿不到平台）—— 显示成「未标注平台」，
 * **绝不猜一个平台**，也不把它并进某个真实平台。
 */
export function platformLabel(platformCode: string, platformName: string): string {
  if (!platformCode) return '未标注平台';
  return platformName ? `${platformName}（${platformCode}）` : platformCode;
}

/** 平台下拉项（**空串 = 全部平台**，不是「不筛」以外的第三种含义）。 */
export function platformFilterOptions(platforms: StorePlatformOption[]): { value: string; label: string }[] {
  return [
    { value: '', label: '全部平台' },
    ...platforms.map((p) => ({ value: p.platformCode, label: platformLabel(p.platformCode, p.platformName) })),
  ];
}

// ================================================================ 对比矩阵的列

/**
 * 矩阵的一列 = 一个指标。
 *
 * <p>列的顺序是**有意的**：先看规模（成交金额），再看口径（还原口径 + 差异额），
 * 然后才是增长与效率。口径差异额紧跟在两份成交金额后面 —— 它是那两列的注脚，
 * 隔远了就会被当成另一个业务指标读。
 */
export interface MatrixColumn {
  metricCode: string;
  label: string;
  unit: string;
  /** 🔴 这一列是不是「金额」—— 金额列在多币种时要标出币种。 */
  currencyAware: boolean;
}

export const MATRIX_COLUMNS: readonly MatrixColumn[] = [
  { metricCode: 'STORE_GMV_PLATFORM', label: '平台侧成交金额', unit: 'CURRENCY', currencyAware: true },
  { metricCode: 'STORE_GMV_RESTORED', label: '还原口径成交金额', unit: 'CURRENCY', currencyAware: true },
  { metricCode: 'STORE_CALIBER_GAP', label: '口径差异额', unit: 'CURRENCY', currencyAware: true },
  { metricCode: 'STORE_ORDER_COUNT', label: '成交订单量', unit: 'COUNT', currencyAware: false },
  { metricCode: 'STORE_GMV_GROWTH', label: '成交金额环比增长率', unit: 'PERCENT', currencyAware: false },
  { metricCode: 'STORE_GMV_PER_ORDER', label: '客单价', unit: 'CURRENCY', currencyAware: true },
  { metricCode: 'STORE_RETURN_RATE', label: '退货率', unit: 'PERCENT', currencyAware: false },
  { metricCode: 'STORE_AD_ROAS', label: '广告产出比', unit: 'RATIO', currencyAware: false },
  { metricCode: 'STORE_AD_ACOS', label: '广告花费占比', unit: 'PERCENT', currencyAware: false },
];

/**
 * 只列出**该租户已登记**的列。
 *
 * <p>⚠️ 口径是租户自己的（服务端的口径表是唯一真相），所以列不能写死 ——
 * 租户把某个指标关掉，列就该消失，而不是留一个永远空的表头。
 */
export function matrixColumns(definitions: StoreMetricDefinitionView[]): MatrixColumn[] {
  const known = new Map(definitions.map((d) => [d.metricCode, d]));
  return MATRIX_COLUMNS.filter((column) => known.has(column.metricCode)).map((column) => {
    const definition = known.get(column.metricCode);
    return {
      ...column,
      label: definition?.displayName ?? column.label,
      unit: definition?.unit ?? column.unit,
      currencyAware: definition?.currencyAware ?? column.currencyAware,
    };
  });
}

/** 矩阵里某个店铺某个指标的取值（找不到 ⇒ null = 算不出来）。 */
export function cellOf(row: StoreComparisonRow, metricCode: string): StoreMetricValue | null {
  return row.metrics.find((m) => m.metricCode === metricCode) ?? null;
}

/** 矩阵单元格的取值文本（null ⇒ 「—」/「不可比」，不冒充 0）。 */
export function cellText(row: StoreComparisonRow, column: MatrixColumn): string {
  const metric = cellOf(row, column.metricCode);
  if (!metric) return '—';
  return metricValueText(metric, column.unit);
}

/** 矩阵单元格的可用性（AVAILABLE / NO_DATA / NOT_COMPARABLE）。 */
export function cellAvailability(row: StoreComparisonRow, column: MatrixColumn): string {
  return cellOf(row, column.metricCode)?.availability ?? 'NO_DATA';
}

/** 矩阵单元格的说明（为什么没有数据 / 为什么不可比）。 */
export function cellNote(row: StoreComparisonRow, column: MatrixColumn): string {
  return cellOf(row, column.metricCode)?.availabilityNote ?? '';
}

// ================================================================ 跨平台口径差异（蓝图的加粗要求）

/**
 * 本次对比**必须常驻显示**的口径提醒。
 *
 * <p>🔴 蓝图关系句：「跨平台对比要处理口径差异（亚马逊的 GMV 与 TikTok 的口径不同），
 * 对比时标注口径说明。」所以：
 *
 * <ul>
 *   <li>只要涉及**多个平台**，就必须提示「分组之间的大小差异不能直接当成经营能力差异」；</li>
 *   <li>只要涉及**多个币种**，就必须提示「排名在币种内做」；</li>
 *   <li>平台侧与还原口径的差异提示**始终**给出（哪怕只有一个平台）。</li>
 * </ul>
 */
export function caliberWarnings(response: ComparisonResponse | null): string[] {
  if (!response) return [];
  const notes: string[] = [];
  const platformCount = response.platformGroups.length;
  if (platformCount > 1) {
    notes.push(
      `本次对比涉及 ${platformCount} 个平台。不同平台的成交金额口径（是否含运费、是否含税、` +
        '是否含平台佣金）由平台各自决定，**分组之间的大小差异不能直接当成经营能力差异**。',
    );
  }
  const multiCurrency = response.rows.some((row) => row.currencies.length > 1);
  if (multiCurrency) {
    notes.push(
      '本次对比涉及**多个币种**：金额按币种分别列出，**排名也在币种内做** —— ' +
        '一个 USD 店铺和一个 EUR 店铺的大小本来就不可比。',
    );
  }
  notes.push(
    '矩阵里的「平台侧成交金额」是**平台自己报的口径**，「还原口径成交金额」是我方从平台订单' +
      '还原出来的口径，两者之差列在「口径差异额」里 —— 跨平台对比时这个差必须一起看，' +
      '否则会把口径差读成经营差。',
  );
  return notes;
}

/** 本次对比是否涉及多个平台。 */
export function hasMultiplePlatforms(response: ComparisonResponse | null): boolean {
  return (response?.platformGroups.length ?? 0) > 1;
}

/** 本次对比是否涉及多个币种。 */
export function hasMultipleCurrencies(response: ComparisonResponse | null): boolean {
  return (response?.rows ?? []).some((row) => row.currencies.length > 1);
}

/**
 * 平台分组的口径说明。
 *
 * <p>🔴 服务端给的原文**原样显示** —— 那是按平台逐个核对过的口径说明，
 * 前端改写它就等于自己造了一个口径。
 */
export function groupCaliberNote(group: PlatformGroup): string {
  return group.caliberNote;
}

// ================================================================ 排名榜

/**
 * 某个指标的排名条目（按币种分组的排名榜）。
 *
 * <p>⚠️ **排名在币种内做**：同一个指标下不同币种的条目**不能混在一张榜里**比较 ——
 * 它们的 rank 是各自币种内的位次。
 */
export function rankingEntries(ranking: MetricRanking): { entries: MetricRanking['entries']; currency: string }[] {
  const byCurrency = new Map<string, MetricRanking['entries']>();
  for (const entry of ranking.entries) {
    const bucket = byCurrency.get(entry.currency) ?? [];
    bucket.push(entry);
    byCurrency.set(entry.currency, bucket);
  }
  return [...byCurrency.entries()].map(([currency, entries]) => ({ currency, entries }));
}

/** 排名榜没有可比值时的说明。 */
export function rankingNote(ranking: MetricRanking): string {
  return ranking.note;
}

// ================================================================ 归因提示

/**
 * 差异归因提示（这一行相对中位数差在哪）。
 *
 * <p>⚠️ 服务端给的原文**原样显示**：提示里写的是「中位数」而不是某个具体店铺的数字，
 * 因为中位数会随店铺集合变化。
 */
export function attributionHints(row: StoreComparisonRow): string[] {
  return row.attributionHints;
}

// ================================================================ 数据更新时间

/**
 * 「数据截至 X」文案。
 *
 * <p>🔴 卷 13 卷首加粗句「**数据延迟要显示**」：用户必须一眼看出看到的是 T-1 还是实时。
 */
export function dataUpdatedText(response: ComparisonResponse | null): string {
  if (!response) return '';
  if (!response.dataUpdatedAt) return '数据更新时间：尚未投影任何店铺维度事实';
  return `数据截至 ${response.dataUpdatedAt}`;
}

/** 「数据截至」这句话的口径说明（这个时刻**具体指什么**）。 */
export function dataUpdatedNote(response: ComparisonResponse | null): string {
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

/** 对比窗口文本（含上一等长窗口 —— 环比的分母就在那里）。 */
export function comparisonWindowText(response: ComparisonResponse | null): string {
  if (!response) return '';
  return `${response.from} ~ ${response.to}（环比基准 ${response.previousFrom} ~ ${response.previousTo}）`;
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
export function pageState(response: ComparisonResponse | null, error: string): PageState {
  if (error) return 'error';
  if (!response) return 'loading';
  if (response.rows.length === 0) return 'empty';
  return 'ready';
}

/** 空态文案（服务端给的原因**原样显示**，前端不自己编一个）。 */
export function emptyText(response: ComparisonResponse | null): string {
  if (!response) return '';
  if (response.rows.length === 0) {
    return response.emptyNote || '这个时间窗口里没有任何店铺维度事实。';
  }
  return '';
}

/** 空态的原因标签。 */
export function emptyReasonLabel(reason: string | null): string {
  switch (reason) {
    case 'NO_STORES':
      return '还没有店铺档案';
    case 'NOT_PROJECTED':
      return '还没有投影事实';
    default:
      return '';
  }
}

/** 本次对比的整体状态文案。 */
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

// ================================================================ 能力声明

/**
 * 能力声明里的「确实拿不到」的部分。
 *
 * <p>⚠️ **这些不是 bug，是边界**：店铺维度的毛利率与库存周转在上游根本没有这个维度。
 * 把它们藏起来，用户会以为「这一格没数据是出错了」；写出来，用户才知道去哪儿找。
 */
export function notDeliveredLines(capabilities: ComparisonCapabilities | null): string[] {
  return capabilities?.notDelivered ?? [];
}

/** 本服务真的读过的上游模块。 */
export function upstreamModules(capabilities: ComparisonCapabilities | null): string[] {
  return capabilities?.upstreamModules ?? [];
}

/** 判定规则（阈值写清楚，页面上照着显示）。 */
export function ruleNotes(capabilities: ComparisonCapabilities | null): RuleNote[] {
  return capabilities?.rules ?? [];
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

// ================================================================ 店铺清单

/** 店铺清单里的平台分组数（用于筛选器）。 */
export function storePlatformCount(stores: StoreListResponse | null): number {
  return stores?.platforms.length ?? 0;
}

/** 店铺档案里的事实情况（有没有投影过）。 */
export function storeHasFacts(store: StoreSummary): boolean {
  return store.hasFacts;
}
