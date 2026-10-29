/**
 * 13.3.2.2 关联营销效果 前端模型（卷 13「利润与经营分析」L3 13.3.2 流量与转化下的第二格）。
 *
 * <p>蓝图原文：「分析站外/关联营销（关联视频、A+、Store 导流、社交媒体）的引流效果：
 * 曝光、点击、加购、成交归因。输入：营销位配置与曝光点击数据；订单归因数据。
 * 输出：营销位效果报表 + ROI。关系：上游 = 4 刊登（营销模块）、14 广告；下游 = 13.3.1.1。
 * **归因需要明确规则**（末次点击 vs 首次点击），否则各人看到的数不一样。」
 *
 * <p><b>🔴 这一页最容易做丢的四件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>归因规则必须显式</b>。每一行都带自己的规则快照（归因模型 / 分摊基准 / 归因窗口 /
 *       退款口径 / 佣金口径），报表按 <b>(营销位, 归因规则)</b> 分列 —— 页面必须把规则
 *       <b>贴在行头</b>，让人一眼看出这条数是用哪条规则算出来的。<b>ROI 只在同一条规则内部相除</b>。</li>
 *   <li><b>「算不出来」≠「等于 0」</b>。服务端把不可算 / 没数据的取值置为 {@code null}，
 *       这里渲染成「—」/「不可算」+ 原因，<b>绝不用 0.00 或 0% 顶替</b> ——
 *       假 0 会被读成「这段时间没投放」。</li>
 *   <li><b>「本轮不可用」≠「等于 0」，也不等于「不出现」</b>。加购 / 关联视频 / Store 导流 /
 *       达人站外曝光这一类，页面上要显示<b>「本轮不可用 + 归谁管 + 当前替代口径」</b>。</li>
 *   <li><b>两套口径不混算</b>。达人归因口径（成交归因 / 投入 / ROI）与落地商品口径
 *       （曝光 / 点击 / 点击率）是两个统计范围，<b>每个值旁边都带口径标签</b>。</li>
 * </ol>
 *
 * <p><b>前端不做业务算术</b>。ROI / 点击率 / 归因客单价全部来自服务端（{@code BigDecimal} 定标），
 * 这里只做<b>展示格式化</b>，不做任何加减乘除 —— 前端算一遍就会引入二进制浮点误差，
 * 而且会让界面和服务端的数对不上。
 *
 * <p><b>本文件是纯函数层</b>：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  AffiliateMarketingAttributionPage,
  AffiliateMarketingCapabilityDimension,
  AffiliateMarketingAttributionRow,
  AffiliateMarketingCapabilities,
  AffiliateMarketingMetricDefinitionView,
  AffiliateMarketingMetricValue,
  AffiliateMarketingRefreshResult,
  AffiliateMarketingResponse,
  AffiliateMarketingRuleUsage,
  AffiliateMarketingSlotRow,
  AffiliateMarketingSlotTypeView,
} from '../../../../../packages/api-contracts/src/index';

export type {
  AffiliateMarketingAttributionPage,
  AffiliateMarketingCapabilityDimension,
  AffiliateMarketingAttributionRow,
  AffiliateMarketingCapabilities,
  AffiliateMarketingMetricDefinitionView,
  AffiliateMarketingMetricValue,
  AffiliateMarketingRefreshResult,
  AffiliateMarketingResponse,
  AffiliateMarketingRuleUsage,
  AffiliateMarketingSlotRow,
  AffiliateMarketingSlotTypeView,
};

// ================================================================ 指标码（与服务端口径表一致）

/** 曝光。 */
export const METRIC_IMPRESSIONS = 'AFFILIATE_IMPRESSIONS';
/** 点击。 */
export const METRIC_CLICKS = 'AFFILIATE_CLICKS';
/** 加购（本轮不可用）。 */
export const METRIC_ADD_TO_CART = 'AFFILIATE_ADD_TO_CART';
/** 归因成交金额。 */
export const METRIC_ATTRIBUTED_GMV = 'AFFILIATE_ATTRIBUTED_GMV';
/** 归因订单数。 */
export const METRIC_ATTRIBUTED_ORDERS = 'AFFILIATE_ATTRIBUTED_ORDERS';
/** 营销投入（佣金成本）。 */
export const METRIC_SPEND = 'AFFILIATE_SPEND';
/** 点击率（派生）。 */
export const METRIC_CTR = 'AFFILIATE_CTR';
/** ROI（派生）。 */
export const METRIC_ROI = 'AFFILIATE_ROI';
/** 归因客单价（派生）。 */
export const METRIC_ATTRIBUTED_AOV = 'AFFILIATE_ATTRIBUTED_AOV';

// ================================================================ 展示格式化

/** 金额刻度（与服务端 numeric(24,4) 对齐，展示时保留 2 位即可）。 */
const MONEY_DECIMALS = 2;

/** 百分比刻度（服务端已经算成百分数，例如 0.1067 = 10.67%）。 */
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
 * <p>🔴 **服务端给的是比值（0 ~ 1）**，这里只做**单位换算**（× 100）再展示 ——
 * 这是**展示格式化**，**不是业务算术**：比值本身是服务端算好的，这里原样搬过来。
 *
 * <p>🔴 {@code null} ⇒ 「不可比」（**不是 0%**）：分母为 0 或没有事实时，这个比值根本不存在。
 */
export function percentText(value: number | null): string {
  if (value === null) return '不可比';
  return `${formatNumber(value * 100, PERCENT_DECIMALS)}%`;
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
 * <p>两个口径的统计范围不同，混在一张表里会让人把两个数当成同一个数。
 */
export function caliberLabel(caliber: string): string {
  switch (caliber) {
    case 'INFLUENCER_ATTRIBUTION':
      return '达人归因口径';
    case 'LANDING_LISTING':
      return '落地商品口径';
    default:
      return caliber;
  }
}

/** 营销位类型的展示名。 */
export function slotTypeLabel(slotType: string): string {
  switch (slotType) {
    case 'RELATED_VIDEO':
      return '关联视频';
    case 'A_PLUS':
      return 'A+';
    case 'STORE_TRAFFIC':
      return 'Store 导流';
    case 'SOCIAL_MEDIA':
      return '社交媒体';
    default:
      return slotType;
  }
}

/** 归因模型的展示名（**逐字取自上游词表**，不擅自翻译成「末次点击」）。 */
export function attributionModelLabel(model: string): string {
  switch (model) {
    case 'COOPERATION_FULL':
      return '合作全额归因';
    case 'CAMPAIGN_POOL_SPLIT':
      return '活动池分摊';
    default:
      return model || '—';
  }
}

/** 分摊权重基准的展示名。 */
export function poolWeightBasisLabel(basis: string): string {
  switch (basis) {
    case 'FOLLOWER_SHARE':
      return '粉丝份额';
    case 'COOPERATION_GMV_SHARE':
      return '合作 GMV 份额';
    default:
      return basis || '—';
  }
}

/** 退款口径的展示名。 */
export function refundTreatmentLabel(treatment: string): string {
  switch (treatment) {
    case 'GROSS':
      return '毛额（不扣退款）';
    case 'NET':
      return '净额（扣退款）';
    default:
      return treatment || '—';
  }
}

/** 佣金计提口径的展示名。 */
export function commissionBasisLabel(basis: string): string {
  switch (basis) {
    case 'COOPERATION_COMMISSION':
      return '按合作佣金';
    case 'RATE_ON_ATTRIBUTED_GMV':
      return '按归因成交额 × 比例';
    default:
      return basis || '—';
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
    case 'NOT_AVAILABLE':
      return 'warning';
    case 'NOT_COMPARABLE':
      return 'warning';
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
    case 'NOT_AVAILABLE':
      return '本轮不可用';
    case 'NOT_COMPARABLE':
      return '不可算';
    default:
      return availability;
  }
}

/** 营销位类型的语义色（**不可用的类型必须被看见**）。 */
export function slotTypeTone(available: boolean): Tone {
  return available ? 'success' : 'warning';
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

// ================================================================ 指标取值

/** 按指标单位分派的取值文本（**只做格式化**，不算任何比值）。 */
export function metricValueText(metric: AffiliateMarketingMetricValue): string {
  switch (metric.unit) {
    case 'CURRENCY':
      return amountText(metric.value, metric.currency);
    case 'PERCENT':
      return percentText(metric.quantity);
    case 'QUANTITY':
      return quantityText(metric.quantity);
    case 'COUNT':
      return countText(metric.quantity);
    default:
      return metric.value === null ? '—' : formatNumber(metric.value, PERCENT_DECIMALS);
  }
}

/**
 * 在一行里找某个指标。
 *
 * <p>🔴 **币种是查找的一部分**：涉币指标按币种分列 ⇒ 找错币种会拿到别人的数。
 */
export function metricOf(
  row: AffiliateMarketingSlotRow,
  metricCode: string,
  currency?: string,
): AffiliateMarketingMetricValue | null {
  const matches = row.metrics.filter((metric) => metric.metricCode === metricCode);
  if (currency === undefined) return matches[0] ?? null;
  return matches.find((metric) => metric.currency === currency) ?? null;
}

/** 一行里的全部金额型指标（按币种分列）。 */
export function currencyBreakdown(
  row: AffiliateMarketingSlotRow,
  metricCode: string,
): { currency: string; metric: AffiliateMarketingMetricValue }[] {
  return row.metrics
    .filter((metric) => metric.metricCode === metricCode)
    .map((metric) => ({ currency: metric.currency, metric }));
}

/** 行头的归因规则摘要（**页面上必须贴在行头**）。 */
export function ruleSummary(row: AffiliateMarketingSlotRow): string {
  if (!row.attributionRuleCode) return '（本行无归因规则）';
  const parts = [attributionModelLabel(row.attributionModel)];
  if (row.attributionWindowDays !== null && row.attributionWindowDays !== undefined) {
    parts.push(`归因窗口 ${row.attributionWindowDays} 天`);
  }
  parts.push(refundTreatmentLabel(row.refundTreatment));
  parts.push(commissionBasisLabel(row.commissionBasis));
  return `${row.attributionRuleCode} · ${parts.join(' · ')}`;
}

// ================================================================ 页面状态

/** 页面状态。 */
export type PageState = 'loading' | 'error' | 'empty' | 'ready';

/**
 * 页面状态机。
 *
 * <p>🔴 **「读不到」与「没有数据」是两种状态** —— 上游不可达是 error，
 * 上游可达但这个窗口没有事实是 empty。混成一个空态会让用户以为「这段时间没投放」。
 */
export function pageState(
  response: AffiliateMarketingResponse | null,
  error: string,
): PageState {
  if (error) return 'error';
  if (!response) return 'loading';
  if (response.status === 'EMPTY') return 'empty';
  return 'ready';
}

/** 空态文案。 */
export function emptyText(response: AffiliateMarketingResponse | null): string {
  if (!response) return '';
  return response.emptyNote || '这个窗口内没有可分析的关联营销事实。';
}

/** 空态原因的文案。 */
export function emptyReasonLabel(reason: string | null): string {
  switch (reason) {
    case 'NO_FACTS_IN_WINDOW':
      return '这个窗口内没有关联营销事实';
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
export function dataUpdatedText(response: AffiliateMarketingResponse | null): string {
  if (!response) return '尚未读取数据';
  if (!response.dataUpdatedAt) return '尚未投影任何数据';
  return `数据截至 ${response.dataUpdatedAt}`;
}

/** 数据更新时间的说明。 */
export function dataUpdatedNote(response: AffiliateMarketingResponse | null): string {
  if (!response) return '报表是投影，不是实时的。';
  return response.dataUpdatedNote || '报表是投影，不是实时的。';
}

// ================================================================ 能力声明

/** 能力声明里的「确实拿不到」条目。 */
export function notDeliveredLines(
  capabilities: AffiliateMarketingCapabilities | null,
): AffiliateMarketingCapabilityDimension[] {
  return capabilities?.notDelivered ?? [];
}

/** 能力声明里的上游清单。 */
export function upstreamModules(capabilities: AffiliateMarketingCapabilities | null): string[] {
  return capabilities?.upstreamModules ?? [];
}

/** 能力声明里的规则清单。 */
export function ruleNotes(capabilities: AffiliateMarketingCapabilities | null) {
  return capabilities?.rules ?? [];
}

/** 指标口径的「怎么算出来的」说明。 */
export function metricFormulaText(definition: AffiliateMarketingMetricDefinitionView): string {
  if (definition.numeratorMetricCode && definition.denominatorMetricCode) {
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
