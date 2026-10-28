/**
 * 13.3.1.2 生命周期与分层 前端模型（卷 13「利润与经营分析」L3 13.3.1 产品分析下的第二格）。
 *
 * <p>蓝图原文：「按生命周期与表现分层商品：新品、爆款、旺款、平款、滞销款、衰退款，
 * 按 ABC 分类（销量/金额），输出分层结果与建议动作。」输入「销量与毛利历史；分层规则配置。」
 * 输出「分层结果 + 各层建议（备货/清仓/下架/推广）。」
 * 关系：上游 = 13.3.1.1；下游 = 6.2.1.2 备货、5.4 调价、7.6.1 呆滞。
 *
 * <p><b>🔴 这一页最容易做丢的四件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>「算不出来」≠「等于 0」</b>。档位算不出来（历史不足 / 窗口内没有事实 / 口径未投影）
 *       时服务端把 {@code lifecycleSegment} 置为 {@code null}，这里渲染成「不可比 / 没有数据」
 *       + 原因，<b>绝不用 0 或某个默认档位顶替</b> —— 假档位会被读成「这个商品是平款」。</li>
 *   <li><b>不跨币种相加</b>。金额<b>按币种分行</b>；ABC 按金额是<b>在同一个币种内部</b>排的，
 *       页面上把币种贴在每一行旁边 —— 前端绝不自己把两个币种的金额加起来。</li>
 *   <li><b>毛利额可能算不出来</b>（出库成本没投影）。那一格显示「—」+ 原因，
 *       <b>不是 0 元</b>（0 元会被读成「这个商品不赚钱」）。</li>
 *   <li><b>数据更新时间常驻</b>。卷 13 卷首加粗句「数据延迟要显示」。</li>
 * </ol>
 *
 * <p><b>前端不做业务算术</b>。档位、ABC 分档、销量份额、下滑比例、毛利额、建议动作
 * 全部来自服务端（{@code BigDecimal} 定标），这里只做<b>展示格式化</b> ——
 * 前端算一遍就会引入二进制浮点误差，而且会让界面和服务端的数对不上。
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  LifecycleMetricReference,
  LifecycleSegmentationAmountRow,
  LifecycleSegmentationCapabilities,
  LifecycleSegmentationResponse,
  LifecycleSegmentationRuleNote,
  LifecycleSegmentationRuleView,
  LifecycleSegmentationRow,
  LifecycleSegmentSummary,
} from '../../../../../packages/api-contracts/src/index';

export type {
  LifecycleMetricReference,
  LifecycleSegmentationAmountRow,
  LifecycleSegmentationCapabilities,
  LifecycleSegmentationResponse,
  LifecycleSegmentationRuleNote,
  LifecycleSegmentationRuleView,
  LifecycleSegmentationRow,
  LifecycleSegmentSummary,
};

// ================================================================ 档位

/** 生命周期档位（与服务端一致）。 */
export const SEGMENTS = ['NEW', 'HOT', 'STRONG', 'STEADY', 'SLOW', 'DECLINING'] as const;

/** 档位码。 */
export type SegmentCode = (typeof SEGMENTS)[number];

/** 档位的中文档位名。 */
export function segmentLabel(segment: string | null): string {
  switch (segment) {
    case 'NEW':
      return '新品';
    case 'HOT':
      return '爆款';
    case 'STRONG':
      return '旺款';
    case 'STEADY':
      return '平款';
    case 'SLOW':
      return '滞销款';
    case 'DECLINING':
      return '衰退款';
    default:
      return '未定档';
  }
}

/**
 * 档位的语义色。
 *
 * <p>🔴 **「不可算」不是中性灰** —— 它是「这里有话要说」，用 warning 提醒。
 */
export function segmentTone(segment: string | null): Tone {
  switch (segment) {
    case 'HOT':
      return 'danger';
    case 'STRONG':
      return 'success';
    case 'NEW':
      return 'info';
    case 'STEADY':
      return 'neutral';
    case 'SLOW':
      return 'warning';
    case 'DECLINING':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 档位的下拉项（**只有六档**，顺序就是判定优先级之外的展示序）。 */
export function segmentOptions(): { value: string; label: string }[] {
  return [
    { value: '', label: '全部档位' },
    { value: 'NEW', label: '新品' },
    { value: 'HOT', label: '爆款' },
    { value: 'STRONG', label: '旺款' },
    { value: 'STEADY', label: '平款' },
    { value: 'SLOW', label: '滞销款' },
    { value: 'DECLINING', label: '衰退款' },
  ];
}

// ================================================================ 建议动作

/** 建议动作码。 */
export const ACTIONS = ['RESTOCK', 'CLEARANCE', 'DELIST', 'PROMOTE'] as const;

/** 建议动作码类型。 */
export type ActionCode = (typeof ACTIONS)[number];

/** 建议动作的中文档位名。 */
export function actionLabel(action: string | null): string {
  switch (action) {
    case 'RESTOCK':
      return '备货';
    case 'CLEARANCE':
      return '清仓';
    case 'DELIST':
      return '下架';
    case 'PROMOTE':
      return '推广';
    default:
      return '未定动作';
  }
}

/** 建议动作的语义色。 */
export function actionTone(action: string | null): Tone {
  switch (action) {
    case 'RESTOCK':
      return 'success';
    case 'CLEARANCE':
      return 'warning';
    case 'DELIST':
      return 'danger';
    case 'PROMOTE':
      return 'info';
    default:
      return 'neutral';
  }
}

// ================================================================ 展示格式化

/** 金额刻度（与服务端 numeric(24,4) 对齐，展示时保留 2 位即可）。 */
const MONEY_DECIMALS = 2;

/** 百分比刻度（服务端已经算成百分数，例如 -15.0000 = -15%）。 */
const PERCENT_DECIMALS = 2;

/** 件数刻度（服务端 numeric(19,6)，展示时保留 3 位）。 */
const QUANTITY_DECIMALS = 3;

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

/** ABC 档位的文案。 */
export function abcLabel(abc: string | null): string {
  if (!abc) return '不进榜';
  return `${abc} 档`;
}

/** ABC 排名的文案（**并列同档**，名次可能跳号）。 */
export function abcRankText(
  abc: string | null,
  rank: number | null,
  total: number | null,
): string {
  if (!abc || rank === null || total === null) return '不进榜';
  return `${abc} 档 · 第 ${rank} / ${total} 名`;
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
    case 'NOT_DELIVERED':
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
    case 'NOT_DELIVERED':
      return '本轮不可用';
    default:
      return availability;
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

// ================================================================ 页面状态

/** 页面状态。 */
export type PageState = 'loading' | 'error' | 'empty' | 'ready';

/**
 * 页面状态机。
 *
 * <p>🔴 **「读不到」与「没有数据」是两种状态** —— 上游不可达是 error，
 * 上游可达但这个窗口没有分层结果是 empty。混成一个空态会让用户以为「这个商品没有分层」。
 */
export function pageState(response: LifecycleSegmentationResponse | null, error: string): PageState {
  if (error) return 'error';
  if (!response) return 'loading';
  if (response.status === 'EMPTY') return 'empty';
  return 'ready';
}

/** 空态文案。 */
export function emptyText(response: LifecycleSegmentationResponse | null): string {
  if (!response) return '';
  return response.emptyNote || '这个窗口内还没有分层结果。';
}

/** 空态原因的文案。 */
export function emptyReasonLabel(reason: string | null): string {
  switch (reason) {
    case 'NO_SEGMENTATION_IN_WINDOW':
      return '这个窗口内还没有分层结果';
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
export function dataUpdatedText(response: LifecycleSegmentationResponse | null): string {
  if (!response) return '尚未读取数据';
  if (!response.dataUpdatedAt) return '尚未投影任何数据';
  return `数据截至 ${response.dataUpdatedAt}`;
}

/** 数据更新时间的说明。 */
export function dataUpdatedNote(response: LifecycleSegmentationResponse | null): string {
  if (!response) return '报表是投影，不是实时的。';
  return response.dataUpdatedNote || '报表是投影，不是实时的。';
}

// ================================================================ 分层结果

/** 找出某个商品维度的分层结果（取不到返回 null）。 */
export function rowOf(
  response: LifecycleSegmentationResponse | null,
  dimensionKey: string,
): LifecycleSegmentationRow | null {
  if (!response) return null;
  return response.rows.find((row) => row.dimensionKey === dimensionKey) ?? null;
}

/** 找出某个档位的汇总（取不到返回 null）。 */
export function summaryOf(
  response: LifecycleSegmentationResponse | null,
  segment: string,
): LifecycleSegmentSummary | null {
  if (!response) return null;
  return response.summary.find((item) => item.segment === segment) ?? null;
}

/** 有商品的档位（**只有这些档位才值得占版面**）。 */
export function populatedSegments(
  response: LifecycleSegmentationResponse | null,
): LifecycleSegmentSummary[] {
  if (!response) return [];
  return response.summary.filter((item) => item.productCount > 0);
}

/** 某个商品的某个币种的金额行（取不到返回 null）。 */
export function amountOf(
  row: LifecycleSegmentationRow | null,
  currency: string,
): LifecycleSegmentationAmountRow | null {
  if (!row) return null;
  return row.amounts.find((amount) => amount.currency === currency) ?? null;
}

/**
 * 一个商品的金额行文案（**只做格式化**，不做跨币种合计）。
 */
export function amountRowText(amount: LifecycleSegmentationAmountRow | null): string {
  if (!amount) return '—';
  return `${amountText(amount.salesAmount, amount.currency)}（毛利 ${amountText(
    amount.grossProfitAmount,
    amount.currency,
  )}）`;
}

/** 金额行的毛利可得性说明（**没有就返回空串**，不编一句）。 */
export function grossProfitNote(amount: LifecycleSegmentationAmountRow | null): string {
  if (!amount) return '';
  return amount.grossProfitNote;
}

// ================================================================ 规则

/** 规则的阈值文案（**只展示，不在页面上改算**）。 */
export function ruleThresholdText(rule: LifecycleSegmentationRuleView): string {
  return [
    `新品 ${rule.newMaxDaysSinceFirstSale} 天`,
    `首次销售回溯 ${rule.firstSaleLookbackDays} 天`,
    `对比窗口 ${rule.windowDays} 天`,
    `衰退下滑 ${percentText(rule.decliningMinDeclineRatio)}`,
    `爆款 ${percentText(rule.hotMinSalesShareRatio)}`,
    `旺款 ${percentText(rule.strongMinSalesShareRatio)}`,
    `平款 ${percentText(rule.steadyMinSalesShareRatio)}`,
    `滞销日均 ${quantityText(rule.slowMaxSalesPerDay)} 件`,
    `ABC ${percentText(rule.abcACumulativeRatio)} / ${percentText(rule.abcBCumulativeRatio)}`,
  ].join(' · ');
}

/** 被引用的口径的「怎么算出来的」说明。 */
export function metricFormulaText(reference: LifecycleMetricReference): string {
  if (reference.numeratorMetricCode && reference.denominatorMetricCode) {
    if (reference.aggregation === 'DERIVED_DIFF') {
      return `${reference.numeratorMetricCode} − ${reference.denominatorMetricCode}（推导值）`;
    }
    return `${reference.numeratorMetricCode} ÷ ${reference.denominatorMetricCode}`;
  }
  return '窗口内相加';
}

// ================================================================ 能力声明

/** 能力声明里的「确实拿不到」条目。 */
export function notDeliveredLines(capabilities: LifecycleSegmentationCapabilities | null): string[] {
  return capabilities?.notDelivered ?? [];
}

/** 能力声明里的上游清单。 */
export function upstreamModules(capabilities: LifecycleSegmentationCapabilities | null): string[] {
  return capabilities?.upstreamModules ?? [];
}

/** 能力声明里的规则清单。 */
export function ruleNotes(capabilities: LifecycleSegmentationCapabilities | null): LifecycleSegmentationRuleNote[] {
  return capabilities?.rules ?? [];
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

/** 维度层的下拉项（**只有 SKU / SPU 两档**）。 */
export function dimensionOptions(): { value: string; label: string }[] {
  return [
    { value: 'SKU', label: 'SKU（内部 SKU）' },
    { value: 'SPU', label: 'SPU（标准产品单元）' },
  ];
}
