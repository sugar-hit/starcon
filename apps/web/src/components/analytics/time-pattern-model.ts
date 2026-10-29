/**
 * 13.2.1.2 分时与分周模式 前端模型（卷 13「利润与经营分析」L3 13.2 销量分析下的第二格）。
 *
 * <p>蓝图原文：「分析出单时间分布（按小时、按星期），识别高峰低谷，支持按此调整仓内人力与促销排期。」
 * 输入「订单时间戳（按店铺时区换算）；时段配置。」输出「时段分布热力图 + 高峰时段排序。」
 * 关系：上游 = 9 订单；下游 = 8.5 作业排班、14 广告（分时投放）。
 * **跨境多时区店铺必须按各自时区统计**，汇总时区不同会造成误判。
 *
 * <p><b>🔴 这一页最容易做丢的五件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>🔴 时区是命门</b>。「按小时」= <b>店铺本地时区</b>的小时，「按星期」=
 *       <b>店铺本地时区</b>的星期几（本地周一 00:00 起）。时区用 <b>IANA 标识</b>，
 *       不用固定 UTC 偏移 —— 固定偏移会错过夏令时，本地小时整整差一小时。
 *       页面必须显示每个店铺按哪个时区统计。</li>
 *   <li><b>🔴 跨店铺汇总必须说清对齐的是哪一个槽位</b>。「各店本地 17:00」与「UTC 17:00」
 *       <b>不是同一批订单</b>，两个口径结果<b>不同</b>。页面上必须显示当前用的是哪一个、
 *       为什么 —— 不显示口径的汇总就是让人误判。</li>
 *   <li><b>🔴 高峰低谷的判定依据要写出来</b>：时段层级是<b>租户自己标的</b>，
 *       系统只负责用阈值<b>核对</b>它；核对结果与理由都回给页面。</li>
 *   <li><b>🔴 多币种绝不相加</b>。成交金额按币种分别列出；订单数与销量件数与币种无关，
 *       给一个跨币种合计。</li>
 *   <li><b>「算不出来」≠「等于 0」</b>。分母为 0 时占比是<b>不可比</b>，<b>不是 0%</b>；
 *       定不了时区的订单<b>单列</b>，不让用户以为「那个时段就是没单」。</li>
 * </ol>
 *
 * <p><b>🔴 前端不做业务算术</b>。时段归属、高峰排序、占比、P90 刻度全部来自服务端，
 * 这里只做<b>展示格式化</b> —— 前端自己算一遍就会引入二进制浮点误差，
 * 而且界面和服务端的数会对不上。
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  TimePatternCapabilities,
  TimePatternCell,
  TimePatternCoverage,
  TimePatternHourBucket,
  TimePatternMetricDefinitionView,
  TimePatternPeakRank,
  TimePatternRefreshResult,
  TimePatternResponse,
  TimePatternRuleNote,
  TimePatternShopView,
  TimePatternSlotConfigView,
  TimePatternSlotView,
  TimePatternUnattributed,
  TimePatternWeekdayBucket,
} from '../../../../../packages/api-contracts/src/index';

export type {
  TimePatternCapabilities,
  TimePatternCell,
  TimePatternCoverage,
  TimePatternHourBucket,
  TimePatternMetricDefinitionView,
  TimePatternPeakRank,
  TimePatternRefreshResult,
  TimePatternResponse,
  TimePatternRuleNote,
  TimePatternShopView,
  TimePatternSlotConfigView,
  TimePatternSlotView,
  TimePatternUnattributed,
  TimePatternWeekdayBucket,
};

// ================================================================ 展示格式化

/** 金额刻度（与服务端 numeric(24,4) 对齐，展示时保留 2 位即可）。 */
const MONEY_DECIMALS = 2;

/** 百分比刻度（服务端已经算成百分数，例如 25.000000 = 25%）。 */
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
 * 笔数。
 *
 * <p>🔴 {@code null} ⇒ 「—」，**绝不用 0 顶替**。
 */
export function countText(value: number | null): string {
  if (value === null) return '—';
  return formatNumber(value, COUNT_DECIMALS);
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
 * <p>🔴 {@code null} ⇒ 「不可比」（**不是 0%**）：分母为 0 时这个比值根本不存在。
 */
export function percentText(value: number | null): string {
  if (value === null) return '不可比';
  return `${formatNumber(value, PERCENT_DECIMALS)}%`;
}

/**
 * 币种标签。
 *
 * <p>🔴 **空串 = 与币种无关**（订单数 / 销量件数的跨币种合计），**不是「币种未知」**。
 */
export function currencyLabel(currency: string): string {
  if (!currency) return '与币种无关';
  return currency;
}

/** 一格（或一个桶）的成交金额文本：**按币种分别列出，绝不相加**。 */
export function amountsText(amounts: { currency: string; value: number }[]): string {
  if (!amounts || amounts.length === 0) return '—';
  return amounts.map((amount) => amountText(amount.value, amount.currency)).join(' / ');
}

// ================================================================ 汇总口径

/** 可选的跨店铺汇总口径（与服务端口径表一一对应）。 */
export const ALIGNMENT_OPTIONS: readonly { value: string; label: string }[] = [
  { value: 'LOCAL_TIME', label: '店铺本地时间对齐' },
  { value: 'UTC_SLOT', label: 'UTC 槽位对齐' },
];

/**
 * 汇总口径的展示名（**服务端给了就以服务端的为准**）。
 */
export function alignmentLabel(alignment: string): string {
  switch (alignment) {
    case 'LOCAL_TIME':
      return '店铺本地时间对齐';
    case 'UTC_SLOT':
      return 'UTC 槽位对齐';
    default:
      return alignment;
  }
}

/**
 * 汇总口径的说明 —— **服务端原样返回**，这里不自己编。
 *
 * <p>🔴 这一段必须让用户看到：「小时 17」有两种含义，结果不同。
 */
export function alignmentNote(response: TimePatternResponse | null): string {
  return response?.alignmentNote ?? '';
}

/**
 * 🔴 **两个口径为什么不同** —— 页面必须能回答这句话。
 */
export function alignmentDifferenceNote(): string {
  return (
    '🔴 「小时 17」有两种含义：**店铺本地时间对齐** = 每个店铺各自的本地 17:00–17:59；' +
    '**UTC 槽位对齐** = 所有店铺共同的 UTC 17:00–17:59。' +
    '**两者结果不同** —— 前者回答「买家什么时候下单」，后者回答「仓内同一绝对时刻的压力」。'
  );
}

// ================================================================ 时段层级

/**
 * Badge 的 tone 取值（与 `@starcon/ui` 的 `Badge` 一致）。
 */
export type Tone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'brand';

/**
 * 时段层级的语义色。
 *
 * <p>🔴 高峰 = warning（值得注意、要排人排促销），平峰 = neutral，低谷 = info。
 */
export function levelTone(level: string): Tone {
  switch (level) {
    case 'PEAK':
      return 'warning';
    case 'SHOULDER':
      return 'neutral';
    case 'TROUGH':
      return 'info';
    default:
      return 'neutral';
  }
}

/** 时段层级的中文标签。 */
export function levelLabel(level: string): string {
  switch (level) {
    case 'PEAK':
      return '高峰';
    case 'SHOULDER':
      return '平峰';
    case 'TROUGH':
      return '低谷';
    default:
      return level;
  }
}

/**
 * 时段**达标**与否的语义色。
 *
 * <p>🔴 **不达标不是错误** —— 它是「租户标的层级与实际数据对不上」的如实说明，
 * 用 neutral（不是 danger），免得把「核对没通过」渲染成「出错了」。
 */
export function qualifiedTone(qualified: boolean): Tone {
  return qualified ? 'success' : 'neutral';
}

/** 达标与否的中文标签。 */
export function qualifiedLabel(qualified: boolean): string {
  return qualified ? '达标' : '未达标';
}

/** 时段区间的展示文本（左闭右开）。 */
export function slotRangeText(startHour: number, endHour: number): string {
  return `${String(startHour).padStart(2, '0')}:00–${String(endHour).padStart(2, '0')}:00`;
}

// ================================================================ 热力图

/**
 * 热力图格子的热度档位。
 *
 * <p>🔴 **刻度用的是服务端给的 P90 阈值**，不是前端自己算的最大值 ——
 * 前端不重新定义「什么叫高峰」：服务端说「小时订单量的 90 分位是多少」，
 * 页面就照那个刻度分档。
 *
 * <ul>
 *   <li>{@code peak} —— 达到服务端给的 P90 高峰刻度；</li>
 *   <li>{@code active} —— 有单，但没到 P90 刻度；</li>
 *   <li>{@code empty} —— 这一格没有事实（**真的 0**）。</li>
 * </ul>
 */
export type HeatTier = 'peak' | 'active' | 'empty';

export function heatTier(cell: TimePatternCell, p90OrderCount: number | null): HeatTier {
  if (!cell.orderCount || cell.orderCount <= 0) return 'empty';
  if (p90OrderCount !== null && p90OrderCount > 0 && cell.orderCount >= p90OrderCount)
    return 'peak';
  return 'active';
}

/** 热度档位对应的背景色类名（设计令牌，见 docs/design/visual-spec.md）。 */
export function heatCellClass(tier: HeatTier): string {
  switch (tier) {
    case 'peak':
      return 'bg-warning-soft text-warning';
    case 'active':
      return 'bg-info-soft text-foreground';
    default:
      return 'bg-muted/40 text-muted-foreground';
  }
}

/** 热力图里的一格（按星期几 × 小时定位；找不到 ⇒ null）。 */
export function cellOf(
  heatmap: TimePatternCell[],
  weekday: number,
  hour: number,
): TimePatternCell | null {
  for (const cell of heatmap) {
    if (cell.weekday === weekday && cell.hour === hour) return cell;
  }
  return null;
}

/** 一行热力图（某个星期几的 24 个小时）。 */
export function heatRow(heatmap: TimePatternCell[], weekday: number): TimePatternCell[] {
  const out: TimePatternCell[] = [];
  for (let hour = 0; hour < 24; hour++) {
    const cell = cellOf(heatmap, weekday, hour);
    if (cell) out.push(cell);
  }
  return out;
}

/** 星期几的展示顺序（ISO 1 = 周一 … 7 = 周日）。 */
export const WEEKDAY_ORDER: readonly number[] = [1, 2, 3, 4, 5, 6, 7];

/** 星期几的展示名（服务端给了就以服务端的为准）。 */
export function weekdayLabel(weekday: number, response: TimePatternResponse | null): string {
  const found = response?.weekdayDistribution.find((bucket) => bucket.weekday === weekday);
  return found?.weekdayLabel ?? '';
}

/** P90 高峰刻度的说明文本。 */
export function p90Text(response: TimePatternResponse | null): string {
  if (!response) return '';
  if (response.p90OrderCount === null) return 'P90 高峰刻度：算不出来（这个窗口没有事实）';
  return (
    `P90 高峰刻度：小时订单量 ${countText(response.p90OrderCount)} 笔` +
    '（**24 个小时桶全部参与**，没有事实的小时算 0）'
  );
}

// ================================================================ 分布

/** 小时分布里的一桶（找不到 ⇒ null）。 */
export function hourBucketOf(
  distribution: TimePatternHourBucket[],
  hour: number,
): TimePatternHourBucket | null {
  for (const bucket of distribution) {
    if (bucket.hour === hour) return bucket;
  }
  return null;
}

/** 星期分布里的一桶（找不到 ⇒ null）。 */
export function weekdayBucketOf(
  distribution: TimePatternWeekdayBucket[],
  weekday: number,
): TimePatternWeekdayBucket | null {
  for (const bucket of distribution) {
    if (bucket.weekday === weekday) return bucket;
  }
  return null;
}

// ================================================================ 高峰时段排序

/** 高峰排序的排序依据指标（可选项）。 */
export const RANKING_METRIC_OPTIONS: readonly { value: string; label: string }[] = [
  { value: 'ORDER_COUNT', label: '成交订单数' },
  { value: 'UNIT_SOLD', label: '销量（件）' },
  { value: 'SALES_AMOUNT', label: '成交金额' },
];

/**
 * 排序行里那个指标的取值文本。
 *
 * <p>🔴 **金额类指标带币种**（服务端按币种各自排名，跨币种比大小没有意义）；
 * 订单数 / 销量与币种无关。
 */
export function peakRankValueText(rank: TimePatternPeakRank): string {
  if (rank.currency) return amountText(rank.metricValue, rank.currency);
  return countText(rank.metricValue);
}

/**
 * 🔴 **排序口径必须写出来** —— 否则「谁排第一」没有出处。
 */
export function rankingMetricNote(response: TimePatternResponse | null): string {
  if (!response) return '';
  const label = response.rankingMetricLabel || response.rankingMetricCode;
  if (response.rankingMetricCode === 'SALES_AMOUNT') {
    return (
      `排序依据：**${label}**。🔴 金额涉币 ⇒ **按币种各自排名**（跨币种比大小本来就没有意义），` +
      '所以同一批时段会出现多组排名，每组一个币种。'
    );
  }
  return `排序依据：**${label}**（与币种无关，给一个跨店铺合计）。`;
}

// ================================================================ 店铺时区

/**
 * 店铺时区的语义色。
 *
 * <p>🔴 **定不了时区用 warning**（不是 neutral）：它是一个**待处理**的状态 ——
 * 这家店的订单进不了任何本地桶，运营必须知道。
 */
export function timezoneTone(status: string): Tone {
  return status === 'RESOLVED' ? 'success' : 'warning';
}

/** 店铺时区状态的中文标签。 */
export function timezoneStatusLabel(status: string): string {
  switch (status) {
    case 'RESOLVED':
      return '时区已定';
    case 'UNRESOLVED':
      return '定不了时区';
    default:
      return status;
  }
}

/**
 * 店铺按哪个时区统计的展示文本。
 *
 * <p>🔴 **定不了时区时显示「定不了」而不是留空** —— 留空会被读成「这家店没有时区信息」，
 * 而事实是「它定不了」。
 */
export function shopTimezoneText(shop: TimePatternShopView): string {
  if (shop.timezoneStatus !== 'RESOLVED' || !shop.shopTimezone) return '定不了时区';
  return shop.shopTimezone;
}

// ================================================================ 未定时的归属

/**
 * 「未能按店铺本地时分桶」的说明文案。
 *
 * <p>🔴 这一段**必须常驻显示**：它是「热力图为什么不完整」的答案。
 * 藏起来的话，用户会以为「那个时段就是没单」。
 */
export function unattributedText(unattributed: TimePatternUnattributed | null): string {
  if (!unattributed || unattributed.shopCodes.length === 0) return '';
  const shops = unattributed.shopCodes.join('、');
  return (
    `有 ${unattributed.shopCodes.length} 个店铺（${shops}）的订单**未能按店铺本地时分桶**` +
    `（${countText(unattributed.orderCount)} 笔，${quantityText(unattributed.unitSold)} 件，` +
    `金额 ${amountsText(unattributed.salesAmounts)}）` +
    ' —— 摊给某个时区是造假，因此单列，**不计入热力图的分母**。'
  );
}

// ================================================================ 覆盖度

/** 覆盖度的说明文案。 */
export function coverageText(coverage: TimePatternCoverage | null): string {
  if (!coverage) return '';
  return coverage.note;
}

/**
 * 🔴 **投影不满**的语义色。
 *
 * <p>投影不满时格子里的 0 里有多少是真没单、有多少是没投影，说不清 ——
 * 这不是「正常」，得让人看见。
 */
export function coverageTone(coverage: TimePatternCoverage | null): Tone {
  if (!coverage) return 'neutral';
  return coverage.projectedDays < coverage.expectedDays ? 'warning' : 'success';
}

// ================================================================ 口径目录

/** 口径的「怎么算出来的」说明。 */
export function metricFormulaText(definition: TimePatternMetricDefinitionView): string {
  if (definition.baseMetricCode) return `${definition.baseMetricCode}（被比较指标）`;
  return definition.aggregation;
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
    case 'GROUP_SHARE':
      return '组内占比（÷ 同一窗口同层级合计）';
    case 'PERCENTILE':
      return '分位阈值';
    default:
      return aggregation;
  }
}

/**
 * 🔴 **引用不重定义** —— 公司维度的三个基础口径（订单总额 / 销量 / 订单数）
 * 唯一��定义处是 13.1.1.1 的口径表，本模块只是**换了一种切法**。
 */
export function referencedFromText(definition: TimePatternMetricDefinitionView): string {
  return definition.referencedFrom;
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
export function pageState(response: TimePatternResponse | null, error: string): PageState {
  if (error) return 'error';
  if (!response) return 'loading';
  if (response.status === 'NOT_PROJECTED') return 'empty';
  if (response.status === 'NO_FACTS_IN_WINDOW') return 'empty';
  return 'ready';
}

/** 空态文案（服务端给的原因**原样显示**，前端不自己编一个）。 */
export function emptyText(response: TimePatternResponse | null): string {
  if (!response) return '';
  return response.emptyNote || '这个时间窗口里没有任何分时事实。';
}

/** 空态的原因标签。 */
export function emptyReasonLabel(reason: string | null): string {
  switch (reason) {
    case 'NOT_PROJECTED':
      return '这个租户还没有投影过分时事实';
    case 'NO_FACTS_IN_WINDOW':
      return '这个窗口没有分时事实';
    default:
      return '';
  }
}

/** 本次报表的整体状态文案。 */
export function statusLabel(status: string): string {
  switch (status) {
    case 'PROJECTED':
      return '已投影';
    case 'NOT_PROJECTED':
      return '未投影';
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
export function dataUpdatedText(response: TimePatternResponse | null): string {
  if (!response) return '';
  if (!response.dataUpdatedAt) return '数据更新时间：尚未投影任何分时事实';
  return `数据截至 ${response.dataUpdatedAt}`;
}

/** 「数据截至」这句话的口径说明（这个时刻**具体指什么**）。 */
export function dataUpdatedNote(response: TimePatternResponse | null): string {
  return response?.dataUpdatedNote ?? '';
}

// ================================================================ 能力声明

/**
 * 能力声明里的「确实拿不到」的部分。
 *
 * <p>⚠️ **这些不是 bug，是边界**：店铺归属、时区解析上限在事实本身上就有。
 * 把它们藏起来，用户会以为「这一格没数据是出错了」。
 */
export function notDeliveredLines(capabilities: TimePatternCapabilities | null): string[] {
  return capabilities?.notDelivered ?? [];
}

/** 本服务真的读过的上游模块。 */
export function upstreamModules(capabilities: TimePatternCapabilities | null): string[] {
  return capabilities?.upstreamModules ?? [];
}

/** 判定规则与口径说明（阈值写清楚，页面上照着显示）。 */
export function ruleNotes(capabilities: TimePatternCapabilities | null): TimePatternRuleNote[] {
  return capabilities?.rules ?? [];
}

/** 报表自带的提示（服务端给的**警示清单**，前端不自己编）。 */
export function warnings(response: TimePatternResponse | null): string[] {
  return response?.warnings ?? [];
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

/** 店铺筛选的展示文本（选了哪些店铺）。 */
export function shopFilterText(shopCodes: string[], shops: TimePatternShopView[]): string {
  if (shopCodes.length === 0) return '全部店铺';
  const names = shopCodes.map((code) => {
    const shop = shops.find((one) => one.shopCode === code);
    return shop?.shopName ? `${shop.shopName}（${code}）` : code;
  });
  return names.join('、');
}

// ================================================================ 刷新结果

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

// ================================================================ 时段配置

/** 时段配置的中文层级标签。 */
export function slotConfigLevelLabel(slot: TimePatternSlotConfigView): string {
  return slot.levelLabel || levelLabel(slot.level);
}

/**
 * 🔴 **区间边界的口径**（左闭右开）—— 页面照着显示，别让用户以为 endHour 那一小时也在内。
 */
export function boundaryNote(slot: TimePatternSlotConfigView): string {
  return slot.boundaryNote;
}
