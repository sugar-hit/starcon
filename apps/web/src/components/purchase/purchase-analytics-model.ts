import type {
  PurchaseAnalyticsCapabilities,
  PurchaseAnalyticsConcentration,
  PurchaseAnalyticsCostToSales,
  PurchaseAnalyticsEfficiency,
  PurchaseAnalyticsFactDetail,
  PurchaseAnalyticsFactPage,
  PurchaseAnalyticsOnTimeRate,
  PurchaseAnalyticsOptions,
  PurchaseAnalyticsPriceVariance,
  PurchaseAnalyticsSummary,
  PurchaseAnalyticsSyncResult,
  PurchaseAnalyticsSyncRunPage,
  PurchaseAnalyticsTrend,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 6.5.2.1 采购分析前端模型。
 *
 * <p>蓝图 06 卷：「采购侧报表：采购金额趋势、供应商集中度与贡献、采购价格波动、到货准时率、
 * 采购成本占销售额比、按类目/店铺维度的采购效率。输入：采购单与收货数据；时间范围与维度筛选。
 * 输出：报表图表 + 可导出明细；下钻到具体采购单。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>口径随数据返回</b>：每个报表块都带 {@code basis}（口径原文），页面**原样显示** ——
 *       集中度是 top-N 还是 HHI、准时率的分母是承诺到货行还是收货行、价格波动是环比还是同比，
 *       读的人不用猜。</li>
 *   <li><b>跨币种不合并</b>：金额按币种分列展示，页面**不给**一个把多个币种加起来的总数 ——
 *       那个总数是 1:1 折算出来的假数。</li>
 *   <li><b>算不出来不是 0</b>：没有汇率快照、缺销售分母、上期没数据，一律显示「无数据」，
 *       并把原因（来自后端的 impact / note）显示出来。</li>
 *   <li><b>未接线的能力显式标出</b>：采购单原单 / 质检 / 13 利润与经营分析未交付 ⇒
 *       能力横幅里逐条写明「归谁管、缺了会怎样」。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */

const base = 'trade/purchase-analytics';

// ================================================================ 查询参数

export interface PurchaseAnalyticsParams {
  // `| undefined` 是 `exactOptionalPropertyTypes` 下的必需写法：
  // 调用方常常把「可能没有」的值直接透传（`string | undefined`），
  // 不显式声明 undefined 的话 TS 会拒绝「显式传 undefined」。
  from?: string | undefined;
  to?: string | undefined;
  currency?: string | undefined;
  supplierRefId?: string | undefined;
  categoryCode?: string | undefined;
  shopCode?: string | undefined;
  factKind?: string | undefined;
  sourceType?: string | undefined;
  onTimeStatus?: string | undefined;
  keyword?: string | undefined;
  sort?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export function purchaseAnalyticsQuery(params: PurchaseAnalyticsParams): string {
  const parts: string[] = [];
  if (params.from) parts.push(`from=${encodeURIComponent(params.from)}`);
  if (params.to) parts.push(`to=${encodeURIComponent(params.to)}`);
  if (params.currency) parts.push(`currency=${encodeURIComponent(params.currency)}`);
  if (params.supplierRefId) parts.push(`supplierRefId=${encodeURIComponent(params.supplierRefId)}`);
  if (params.categoryCode) parts.push(`categoryCode=${encodeURIComponent(params.categoryCode)}`);
  if (params.shopCode) parts.push(`shopCode=${encodeURIComponent(params.shopCode)}`);
  if (params.factKind) parts.push(`factKind=${encodeURIComponent(params.factKind)}`);
  if (params.sourceType) parts.push(`sourceType=${encodeURIComponent(params.sourceType)}`);
  if (params.onTimeStatus) parts.push(`onTimeStatus=${encodeURIComponent(params.onTimeStatus)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 调用点

export function fetchPurchaseAnalyticsCapabilities() {
  return api<PurchaseAnalyticsCapabilities>(`${base}/capabilities`);
}

export function fetchPurchaseAnalyticsOptions() {
  return api<PurchaseAnalyticsOptions>(`${base}/options`);
}

export function fetchPurchaseAnalyticsSummary(params: PurchaseAnalyticsParams) {
  return api<PurchaseAnalyticsSummary>(`${base}/summary${purchaseAnalyticsQuery(params)}`);
}

export function fetchPurchaseAnalyticsTrend(
  params: PurchaseAnalyticsParams & { granularity?: string },
) {
  const parts: string[] = [];
  if (params.granularity) parts.push(`granularity=${encodeURIComponent(params.granularity)}`);
  const base_ = purchaseAnalyticsQuery(params);
  const query = [base_ ? base_.slice(1) : '', ...parts].filter(Boolean).join('&');
  return api<PurchaseAnalyticsTrend>(`${base}/trend${query ? `?${query}` : ''}`);
}

export function fetchPurchaseAnalyticsConcentration(
  params: PurchaseAnalyticsParams & { topN?: number },
) {
  const parts: string[] = [];
  if (params.topN) parts.push(`topN=${params.topN}`);
  const base_ = purchaseAnalyticsQuery(params);
  const query = [base_ ? base_.slice(1) : '', ...parts].filter(Boolean).join('&');
  return api<PurchaseAnalyticsConcentration>(
    `${base}/supplier-concentration${query ? `?${query}` : ''}`,
  );
}

export function fetchPurchaseAnalyticsPriceVariance(
  params: PurchaseAnalyticsParams & { dimension?: string },
) {
  const parts: string[] = [];
  if (params.dimension) parts.push(`dimension=${encodeURIComponent(params.dimension)}`);
  const base_ = purchaseAnalyticsQuery(params);
  const query = [base_ ? base_.slice(1) : '', ...parts].filter(Boolean).join('&');
  return api<PurchaseAnalyticsPriceVariance>(`${base}/price-variance${query ? `?${query}` : ''}`);
}

export function fetchPurchaseAnalyticsOnTimeRate(params: PurchaseAnalyticsParams) {
  return api<PurchaseAnalyticsOnTimeRate>(`${base}/on-time-rate${purchaseAnalyticsQuery(params)}`);
}

export function fetchPurchaseAnalyticsCostToSales(params: PurchaseAnalyticsParams) {
  return api<PurchaseAnalyticsCostToSales>(
    `${base}/cost-to-sales-ratio${purchaseAnalyticsQuery(params)}`,
  );
}

export function fetchPurchaseAnalyticsEfficiency(
  params: PurchaseAnalyticsParams & { dimension?: string },
) {
  const parts: string[] = [];
  if (params.dimension) parts.push(`dimension=${encodeURIComponent(params.dimension)}`);
  const base_ = purchaseAnalyticsQuery(params);
  const query = [base_ ? base_.slice(1) : '', ...parts].filter(Boolean).join('&');
  return api<PurchaseAnalyticsEfficiency>(`${base}/efficiency${query ? `?${query}` : ''}`);
}

export function fetchPurchaseAnalyticsFacts(params: PurchaseAnalyticsParams) {
  return api<PurchaseAnalyticsFactPage>(`${base}/facts${purchaseAnalyticsQuery(params)}`);
}

export function fetchPurchaseAnalyticsFact(id: string) {
  return api<PurchaseAnalyticsFactDetail>(`${base}/facts/${encodeURIComponent(id)}`);
}

export function syncPurchaseAnalyticsFacts(input: {
  sourceType?: string | undefined;
  from?: string | undefined;
  to?: string | undefined;
}) {
  return api<PurchaseAnalyticsSyncResult>(`${base}/facts/sync`, 'POST', input);
}

export function fetchPurchaseAnalyticsSyncRuns(params: { page?: number; size?: number }) {
  const parts: string[] = [];
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return api<PurchaseAnalyticsSyncRunPage>(
    `${base}/sync-runs${parts.length ? `?${parts.join('&')}` : ''}`,
  );
}

/** 导出下钻明细 CSV：走浏览器下载（同源代理原样透传 text/csv）。 */
export async function downloadPurchaseAnalyticsCsv(params: PurchaseAnalyticsParams): Promise<void> {
  const response = await fetch(`/api/trade/${base}/export${purchaseAnalyticsQuery(params)}`, {
    headers: { Accept: 'text/csv' },
    credentials: 'same-origin',
  });
  if (!response.ok) {
    throw new Error(`导出失败（HTTP ${response.status}）`);
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `purchase-analytics-${params.from ?? 'all'}-${params.to ?? 'all'}.csv`;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

// ================================================================ 展示口径

/** 「无数据」的统一文案。null 一律用它 —— 0 会被读成「没有问题」。 */
export const NO_DATA_TEXT = '无数据';

/** 事实种类的标签。 */
export const purchaseAnalyticsFactKindLabels: Record<string, string> = {
  COST: '采购成本',
  RECEIPT: '实收量',
  PLAN: '计划量',
};

export function factKindLabel(kind: string): string {
  return purchaseAnalyticsFactKindLabels[kind] ?? kind;
}

/** 来源的标签（与后端 source_type 一一对应）。 */
export const purchaseAnalyticsSourceTypeLabels: Record<string, string> = {
  ARRIVAL_RECEIPT: '到货流水（6.3.2.2）',
  PURCHASE_PLAN_LINE: '采购计划行（6.2.1.3）',
  GOODS_RECEIPT: '收货单（8.2.1.1）',
  SUPPLIER_CLAIM: '供应商索赔（11.2.3.3）',
};

export function sourceTypeLabel(sourceType: string): string {
  return purchaseAnalyticsSourceTypeLabels[sourceType] ?? sourceType;
}

/** 汇率状态的标签。UNSET = 汇率未登记 ⇒ 没有折算。 */
export const purchaseAnalyticsFxStatusLabels: Record<string, string> = {
  SAME_CURRENCY: '原币即本位币',
  APPLIED: '按汇率快照折算',
  UNSET: '汇率未登记（未折算）',
  NOT_APPLICABLE: '无金额',
};

export function fxStatusLabel(status: string): string {
  return purchaseAnalyticsFxStatusLabels[status] ?? status;
}

/** 准时状态的标签。 */
export const purchaseAnalyticsOnTimeStatusLabels: Record<string, string> = {
  ON_TIME: '准时',
  LATE: '逾期',
  PENDING: '未到货',
  NOT_APPLICABLE: '不适用',
};

export function onTimeStatusLabel(status: string): string {
  return purchaseAnalyticsOnTimeStatusLabels[status] ?? status;
}

/** 效率行的状态标签。 */
export const purchaseAnalyticsEfficiencyStatusLabels: Record<string, string> = {
  COMPLETED: '已达成',
  PARTIAL: '部分达成',
  NO_PLAN: '无计划量',
  NO_RECEIPT: '无实收',
  PLAN_ONLY: '仅计划侧口径',
};

export function efficiencyStatusLabel(status: string): string {
  return purchaseAnalyticsEfficiencyStatusLabels[status] ?? status;
}

/** 价格波动行的状态标签。 */
export const purchaseAnalyticsVarianceStatusLabels: Record<string, string> = {
  UP: '上涨',
  DOWN: '下降',
  FLAT: '持平',
  NO_PREVIOUS_DATA: '上期无数据',
  NO_CURRENT_DATA: '本期无数据',
};

export function varianceStatusLabel(status: string): string {
  return purchaseAnalyticsVarianceStatusLabels[status] ?? status;
}

/** 数字的展示。null 一律给「无数据」。 */
export function fmt(value: number | null | undefined): string {
  if (value === null || value === undefined) return NO_DATA_TEXT;
  const rounded = Number(value.toFixed(6));
  return rounded.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/** 金额的展示（带币种）。null 一律给「无数据」。 */
export function moneyText(value: number | null | undefined, currency?: string | null): string {
  if (value === null || value === undefined) return NO_DATA_TEXT;
  const text = Number(value.toFixed(4)).toLocaleString('zh-CN', { maximumFractionDigits: 4 });
  return currency ? `${text} ${currency}` : text;
}

/** 比率的展示（小数口径 → 百分比）。null 一律给「无数据」—— 0% 会被读成「一点问题都没有」。 */
export function percentText(value: number | null | undefined): string {
  if (value === null || value === undefined) return NO_DATA_TEXT;
  return `${(value * 100).toFixed(2)}%`;
}

/** 份额的展示（0–1 → 百分比）。 */
export function shareText(value: number | null | undefined): string {
  return percentText(value);
}

/**
 * 能力声明里**不可用**的那些（页面顶部逐条列出「归谁管、缺了会怎样」）。
 */
export function blockedCapabilities(
  capabilities: PurchaseAnalyticsCapabilities | null,
): PurchaseAnalyticsCapabilities['sections'] {
  if (!capabilities) return [];
  return capabilities.sections.filter((section) => !section.available);
}

/**
 * 集中度风险的视觉分级。
 *
 * <p>HHI 越高越危险：≥ 0.25 属于高集中（供应商出问题时采购很被动）。
 */
export function concentrationTone(hhi: number | null | undefined): 'danger' | 'warning' | 'info' {
  if (hhi === null || hhi === undefined) return 'info';
  if (hhi >= 0.25) return 'danger';
  if (hhi >= 0.15) return 'warning';
  return 'info';
}
