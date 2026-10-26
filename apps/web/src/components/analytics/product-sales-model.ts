/**
 * 13.2.2.2 SKU/SPU/Listing 前端模型（卷 13「利润与经营分析」L3 13.2.2 维度拆解下的第二格）。
 *
 * <p>蓝图原文：「按商品维度拆解销量：SKU/SPU/Listing 三层，支持 SPU 汇总（多平台多 SKU 合并看）、
 * 变体销量分布。」输入「商品维度；时间范围；是否合并 SPU。」输出「商品销量报表 + 层级钻取。」
 * 关系：上游 = 9 订单、3 商品主数据；下游 = 6 补货、5 定价。
 * **SPU 口径是跨平台经营的基本单位** —— 只按平台 SKU 看会误判商品整体表现。
 *
 * <p><b>🔴 这一页最容易做丢的五件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「等于 0」</b>。服务端把分母为 0 / 没有事实的取值置为
 *       {@code null}，这里渲染成「—」或「不可比」+ 原因，<b>绝不显示成 0.00 或 0%</b> ——
 *       假 0 会被读成「这个商品卖不出去」。</li>
 *   <li><b>🔴 多币种绝不相加</b>。金额按币种分别列出；只有件数与单数（与货币无关）给一个
 *       跨币种合计，它的币种是<b>空串</b> —— 空串 = 与币种无关，<b>不是「币种未知」</b>。</li>
 *   <li><b>🔴 Listing 层的销量只归「可唯一归属」的那部分</b>。同一款内部 SKU 挂在多个
 *       Listing 上时，它的销量归不了任何 Listing —— 页面上必须把「无法唯一归属」单列出来，
 *       而不是让用户以为「这个 Listing 没卖出去」。</li>
 *   <li><b>数据更新时间常驻</b>。卷 13 卷首加粗句「数据延迟要显示」。</li>
 *   <li><b>前端不做业务算术</b>。占比、件均价、客单价、汇总全部来自服务端
 *       （{@code BigDecimal} 定标），这里只做<b>展示格式化</b>，不做任何加减乘除 ——
 *       前端算一遍就会引入二进制浮点误差，而且会让界面和服务端的数对不上。
 *       <b>SPU 的合并口径由服务端算</b>，前端绝不自己把 SKU 相加。</li>
 * </ol>
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  CurrencyAmount,
  ProductDrilldownResponse,
  ProductDrilldownRow,
  ProductMetricDefinitionView,
  ProductMetricValue,
  ProductRefreshResult,
  ProductRow,
  ProductRuleNote,
  ProductSalesCapabilities,
  ProductSalesResponse,
  SpuSummaryResponse,
  SpuSummaryRow,
  SpuVariantSummary,
  UnattributedSummary,
  VariantDistributionResponse,
  VariantRow,
} from '../../../../../packages/api-contracts/src/index';

export type {
  CurrencyAmount,
  ProductDrilldownResponse,
  ProductDrilldownRow,
  ProductMetricDefinitionView,
  ProductMetricValue,
  ProductRefreshResult,
  ProductRow,
  ProductRuleNote,
  ProductSalesCapabilities,
  ProductSalesResponse,
  SpuSummaryResponse,
  SpuSummaryRow,
  SpuVariantSummary,
  UnattributedSummary,
  VariantDistributionResponse,
  VariantRow,
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
 * <p>🔴 **空串 = 与币种无关**（件数 / 单数的跨币种合计），**不是「币种未知」** ——
 * 渲染成「未知」会让人以为这里有数据问题。
 */
export function currencyLabel(currency: string): string {
  if (!currency) return '与币种无关';
  return currency;
}

/**
 * 按指标单位分派的取值文本。
 *
 * <p>这是表格单元格的唯一渲染入口 —— 单位判断只在这里做一次，
 * 免得每个单元格各写一遍判断、然后在某个单位上漏掉「算不出来」的处理。
 *
 * <p>⚠️ 单位来自<b>口径定义</b>（{@code ProductMetricDefinitionView.unit}），不来自取值本身 ——
 * 取值里没有单位字段，猜单位就会把「百分比」当成「金额」渲染。
 */
export function metricValueText(metric: ProductMetricValue, unit: string): string {
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
    case 'DERIVED_DIFF':
      return '分子 − 分母';
    case 'DERIVED_RATIO':
      return '分子 ÷ 分母';
    default:
      return aggregation;
  }
}

/**
 * 口径的「怎么算出来的」说明。
 *
 * <p>🔴 **跨层引用必须说清分子分母各取自哪一层** —— 变体销量占比的分子在 SKU 层、
 * 分母在 SPU 层；不说清楚，页面上的「40%」就是个没有出处的数。
 */
export function metricFormulaText(definition: ProductMetricDefinitionView): string {
  if (definition.numeratorMetricCode && definition.denominatorMetricCode) {
    const numerator =
      definition.numeratorDimensionType && definition.numeratorDimensionType !== 'ANY'
        ? `${definition.numeratorMetricCode}（${definition.numeratorDimensionType} 层）`
        : definition.numeratorMetricCode;
    const denominator =
      definition.denominatorDimensionType && definition.denominatorDimensionType !== 'ANY'
        ? `${definition.denominatorMetricCode}（${definition.denominatorDimensionType} 层）`
        : definition.denominatorMetricCode;
    const scope =
      definition.currencyScope === 'ANY'
        ? '，允许跨币种合计（只给与货币无关的量）'
        : '，必须同币种';
    return `${numerator} ÷ ${denominator}${scope}`;
  }
  return aggregationLabel(definition.aggregation);
}

/** 币种口径的展示名。 */
export function currencyScopeLabel(scope: string): string {
  switch (scope) {
    case 'ANY':
      return '跨币种合计（只给与货币无关的量）';
    case 'SAME':
      return '同币种';
    default:
      return scope;
  }
}

// ================================================================ 维度层

/** 维度层的展示名。 */
export function levelLabel(level: string): string {
  switch (level) {
    case 'SKU':
      return 'SKU（内部单品）';
    case 'SPU':
      return 'SPU（标准产品单元）';
    case 'LISTING':
      return 'Listing（平台商品）';
    default:
      return level;
  }
}

/** 维度层的下拉项（**空串 = 全部**）。 */
export function levelOptions(): { value: string; label: string }[] {
  return [
    { value: 'SKU', label: 'SKU 层（按平台单品看）' },
    { value: 'SPU', label: 'SPU 层（跨平台合并看）' },
    { value: 'LISTING', label: 'Listing 层（按平台商品看）' },
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

/** 一行的平台标签（多个平台逐个列出，跨平台合并看的证据）。 */
export function platformCodesLabel(platformCodes: string[]): string {
  if (!platformCodes || platformCodes.length === 0) return '未标注平台';
  return platformCodes.map(platformLabel).join('、');
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
 * Listing 销量归属状态的语义色。
 *
 * <p>⚠️ {@code SHARED_SKU}（同一 SKU 挂在多个 Listing 上）用 warning 而不是 danger：
 * 那是**归属规则的边界**，不是错误 —— 但它确实意味着「这个 Listing 的销量不完整」，
 * 值得提醒。
 */
export function attributionTone(status: string): Tone {
  switch (status) {
    case 'ATTRIBUTED':
      return 'success';
    case 'SHARED_SKU':
      return 'warning';
    case 'NO_LISTING':
      return 'warning';
    case 'NOT_APPLICABLE':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** Listing 销量归属状态的中文标签。 */
export function attributionLabel(status: string): string {
  switch (status) {
    case 'ATTRIBUTED':
      return '销量可唯一归属';
    case 'SHARED_SKU':
      return '销量无法唯一归属';
    case 'NO_LISTING':
      return '映射缺失';
    case 'NOT_APPLICABLE':
      return '不适用';
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

/**
 * 报表的一列 = 一个指标。
 *
 * <p>列的顺序是<b>有意的</b>：先看销量（这一页的主角），再看成交金额与订单数，
 * 最后是派生指标（件均价 / 客单价 / 变体占比）。
 */
export interface ProductColumn {
  metricCode: string;
  label: string;
  unit: string;
  /** 🔴 这一列是不是「按币种分行」的（金额类）。 */
  perCurrency: boolean;
}

export const PRODUCT_COLUMNS: readonly ProductColumn[] = [
  {
    metricCode: 'PRODUCT_SALES_QUANTITY',
    label: '销量（件）',
    unit: 'QUANTITY',
    perCurrency: false,
  },
  { metricCode: 'PRODUCT_SALES_AMOUNT', label: '成交金额', unit: 'CURRENCY', perCurrency: true },
  { metricCode: 'PRODUCT_ORDER_COUNT', label: '成交订单数', unit: 'COUNT', perCurrency: false },
  { metricCode: 'PRODUCT_AVG_UNIT_PRICE', label: '件均价', unit: 'CURRENCY', perCurrency: true },
  { metricCode: 'PRODUCT_ORDER_AVG_AMOUNT', label: '客单价', unit: 'CURRENCY', perCurrency: true },
  {
    metricCode: 'PRODUCT_VARIANT_SHARE',
    label: '变体销量占比',
    unit: 'PERCENT',
    perCurrency: false,
  },
];

/**
 * 只列出**该租户已登记**的列。
 *
 * <p>⚠️ 口径是租户自己的（服务端的口径表是唯一真相），所以列不能写死 ——
 * 租户把某个指标关掉，列就该消失，而不是留一个永远空的表头。
 *
 * <p>🔴 **变体销量占比只在 SKU 层出现**：它是跨层口径（分子 SKU 层 / 分母 SPU 层），
 * 在 SPU 层没有意义（SPU 销量 ÷ SPU 销量 = 100%）。
 */
export function productColumns(
  definitions: ProductMetricDefinitionView[],
  level: string,
): ProductColumn[] {
  const known = new Map(definitions.map((d) => [d.metricCode, d]));
  return PRODUCT_COLUMNS.filter((column) => {
    if (!known.has(column.metricCode)) return false;
    if (column.metricCode === 'PRODUCT_VARIANT_SHARE' && level !== 'SKU') return false;
    return true;
  }).map((column) => {
    const definition = known.get(column.metricCode);
    return {
      ...column,
      label: definition?.displayName ?? column.label,
      unit: definition?.unit ?? column.unit,
    };
  });
}

/** 矩阵里某个维度某个指标的取值（找不到 ⇒ null = 算不出来）。 */
export function cellOf(row: ProductRow, metricCode: string): ProductMetricValue | null {
  return row.metrics.find((m) => m.metricCode === metricCode) ?? null;
}

/**
 * 单元格的取值文本。
 *
 * <p>🔴 **按币种分行的列**（金额类）一行可能有**多条**取值（每个币种一条）——
 * 这里把它们的文字合起来（服务端已经算好，这里只拼字符串，**不做任何加减**）。
 */
export function cellText(row: ProductRow, column: ProductColumn): string {
  const matches = row.metrics.filter((m) => m.metricCode === column.metricCode);
  if (matches.length === 0) return '—';
  return matches.map((metric) => metricValueText(metric, column.unit)).join(' / ');
}

/** 单元格的可用性（AVAILABLE / NO_DATA / NOT_COMPARABLE）。 */
export function cellAvailability(row: ProductRow, column: ProductColumn): string {
  const matches = row.metrics.filter((m) => m.metricCode === column.metricCode);
  if (matches.length === 0) return 'NO_DATA';
  if (matches.some((m) => m.availability === 'AVAILABLE')) return 'AVAILABLE';
  return matches[0]!.availability;
}

/** 单元格的说明（为什么没有数据 / 为什么不可比）。 */
export function cellNote(row: ProductRow, column: ProductColumn): string {
  const matches = row.metrics.filter((m) => m.metricCode === column.metricCode);
  if (matches.length === 0) return '这一列在这个维度上没有指标定义。';
  return matches
    .map((m) => m.availabilityNote)
    .filter((note) => note && note.length > 0)
    .join(' ');
}

/** 一行涉及的币种（金额类指标按币种分别列）。 */
export function rowCurrencies(row: ProductRow): string[] {
  return row.currencies ?? [];
}

// ================================================================ SPU 汇总

/** SPU 汇总里某个 SPU 的指标取值（第一个匹配的）。 */
export function spuMetricValue(
  row: SpuSummaryRow,
  metricCode: string,
  currency = '',
): number | null {
  const metric = row.metrics.find(
    (m) => m.metricCode === metricCode && (currency === '' || m.currency === currency),
  );
  return metric ? metric.value : null;
}

/** SPU 名下某个变体的指标取值。 */
export function variantMetricValue(
  variant: SpuVariantSummary,
  metricCode: string,
  currency = '',
): number | null {
  const metric = variant.metrics.find(
    (m) => m.metricCode === metricCode && (currency === '' || m.currency === currency),
  );
  return metric ? metric.value : null;
}

/**
 * SPU 汇总的「跨平台合并」证据文案。
 *
 * <p>🔴 **这是蓝图那句「SPU 口径是跨平台经营的基本单位」的页面落点** ——
 * 一个 SPU 覆盖几个平台、几个 SKU，用户一眼就能看出「合并到什么程度」。
 */
export function spuMergeEvidence(row: SpuSummaryRow): string {
  const platforms = row.platformCodes.length;
  const skus = row.skuCount;
  if (platforms === 0) return '尚未归入任何平台商品。';
  return `合并了 ${skus} 个 SKU × ${platforms} 个平台 × ${row.listingCount} 个平台商品。`;
}

// ================================================================ 变体分布

/** 变体占比的展示文本（null ⇒ 「不可比」，**不是 0%**）。 */
export function shareText(share: number | null): string {
  return percentText(share);
}

/** 变体的平台标签。 */
export function variantPlatformLabel(variant: VariantRow): string {
  return platformCodesLabel(variant.platformCodes);
}

/**
 * 变体占比之和的说明。
 *
 * <p>⚠️ 正常是 100.0000；不等于 100 说明**有销量归不到任何变体**（例如 SKU 没有归入 SPU）——
 * 这时必须说出来，而不是让用户以为百分比算错了。
 */
export function shareTotalNote(response: VariantDistributionResponse | null): string {
  if (!response) return '';
  if (response.variants.length === 0) return '';
  const total = response.shareTotal;
  if (Math.abs(total - 100) < 0.0001) return '各变体占比之和 100%。';
  return `各变体占比之和 ${formatNumber(total, PERCENT_DECIMALS)}% —— 不足 100% 的部分是没有归入任何变体的销量。`;
}

// ================================================================ 无法唯一归属

/**
 * 「无法唯一归属到 Listing」的说明文案。
 *
 * <p>🔴 这一段**必须常驻显示**：它是「Listing 层的销量为什么不完整」的答案。
 * 藏起来的话，用户会以为「这个 Listing 就是没卖出去」。
 */
export function unattributedText(unattributed: UnattributedSummary | null): string {
  if (!unattributed || unattributed.skuCount === 0) return '';
  const amounts = unattributed.amounts
    .map((a: CurrencyAmount) => amountText(a.amount, a.currency))
    .join(' / ');
  return (
    `有 ${unattributed.skuCount} 个 SKU 的销量**无法唯一归属**到某个 Listing` +
    `（涉及 ${quantityText(unattributed.quantity)} 件、${countText(unattributed.orderCount)} 单` +
    (amounts ? `、${amounts}` : '') +
    '）—— 同一款内部 SKU 挂在多个 Listing 上时，订单行不带平台商品 id，摊给谁都是造假。'
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
 * 两者都渲染成同一种空白，运维会以为「这个租户没生意」。
 */
export function pageState(response: ProductSalesResponse | null, error: string): PageState {
  if (error) return 'error';
  if (!response) return 'loading';
  if (response.rows.length === 0) return 'empty';
  return 'ready';
}

/** 空态文案（服务端给的原因**原样显示**，前端不自己编一个）。 */
export function emptyText(response: ProductSalesResponse | null): string {
  if (!response) return '';
  if (response.rows.length === 0) {
    return response.emptyNote || '这个时间窗口里没有任何商品维度事实。';
  }
  return '';
}

/** 空态的原因标签。 */
export function emptyReasonLabel(reason: string | null): string {
  switch (reason) {
    case 'NO_FACTS':
      return '还没有投影商品维度事实';
    case 'NOT_PROJECTED':
      return '还没有投影商品维度事实';
    case 'NO_SPU':
      return '找不到这个 SPU';
    case 'NO_VARIANTS':
      return '这个 SPU 名下没有变体';
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
 * <p>🔴 卷 13 卷首加粗句「**数据延迟要显示**」：用户必须一眼看出看到的是 T-1 还是实时。
 * 空表时是「尚未投影」—— **不是一个长得像时间的假值**。
 */
export function dataUpdatedText(response: ProductSalesResponse | null): string {
  if (!response) return '';
  if (!response.dataUpdatedAt) return '数据更新时间：尚未投影任何商品维度事实';
  return `数据截至 ${response.dataUpdatedAt}`;
}

/** 「数据截至」这句话的口径说明（这个时刻**具体指什么**）。 */
export function dataUpdatedNote(response: ProductSalesResponse | null): string {
  return response?.dataUpdatedNote ?? '';
}

// ================================================================ 能力声明

/**
 * 能力声明里的「确实拿不到」的部分。
 *
 * <p>⚠️ **这些不是 bug，是边界**：Listing 层的销量归属、SPU 自身的平台维度、
 * 订单行的平台信息在上游根本没有。把它们藏起来，用户会以为「这一格没数据是出错了」。
 */
export function notDeliveredLines(capabilities: ProductSalesCapabilities | null): string[] {
  return capabilities?.notDelivered ?? [];
}

/** 本服务真的读过的上游模块。 */
export function upstreamModules(capabilities: ProductSalesCapabilities | null): string[] {
  return capabilities?.upstreamModules ?? [];
}

/** 判定规则与口径说明（阈值写清楚，页面上照着显示）。 */
export function ruleNotes(capabilities: ProductSalesCapabilities | null): ProductRuleNote[] {
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

/** 时区说明（跨时区店铺汇总必须显示所用时区）。 */
export function timezoneNote(timezones: string[] | undefined): string {
  if (!timezones || timezones.length === 0) return '';
  if (timezones.length === 1) return `本窗口的销量按 ${timezones[0]} 分桶。`;
  return `本窗口的销量跨越 ${timezones.join('、')} 多个分桶时区，跨时区汇总的时间口径以各行自己的时区为准。`;
}

// ================================================================ 构成明细

/** 构成明细里「哪张订单的哪一行」的展示文本。 */
export function drilldownReferenceText(row: ProductDrilldownRow): string {
  if (row.referenceLabel) return row.referenceLabel;
  return row.referenceId;
}

/** 构成明细的下单时刻文本（null ⇒ 「—」）。 */
export function occurredAtText(row: ProductDrilldownRow): string {
  if (!row.occurredAt) return '—';
  return row.occurredAt;
}
