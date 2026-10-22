import type {
  CrmProductReviewCapabilities,
  CrmProductReviewCreateInput,
  CrmProductReviewDetail,
  CrmProductReviewPage,
  CrmProductReviewRow,
  CrmProductReviewShopView,
  CrmProductReviewStats,
  CrmProductReviewSyncInput,
  CrmProductReviewSyncResult,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.2.1.1 评价列表前端模型。
 *
 * <p>蓝图：「汇总各店铺评价：星级、内容、变体、图片、平台时间、关联订单、是否已回复、
 * 店铺评分汇总，支持筛选（星级、含图/含视频、未回复、时间范围）与导出。
 * 输出：评价列表 + 评分统计。关系：上游 = 2 同步；下游 = 15.2.1.2 回复、15.2.2.1 索评。
 * **评分趋势比单条评价更重要**：连续下滑要先查是产品问题还是履约问题。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>统计与数量只原样展示</b>：均分、分布、趋势、下滑差值全部由后端算好，
 *       页面**不做任何前端算术**（前端一算，口径就会与后端漂移）。
 *       连续性（continuous）也是后端给的判定，不是前端比出来的。</li>
 *   <li><b>「未接入」要显式灰掉</b>：平台评价同步（409 且不写任何数据）、
 *       评价回复（15.2.1.2 未交付）、差评处理（15.2.1.3 未交付）三项
 *       页面**不提供**看起来能点的假入口。</li>
 *   <li><b>订单上下文不可用要写清原因</b>：详情里读不到订单上下文时，
 *       页面显示「订单上下文不可用」+ 后端给的原因，
 *       <b>绝不显示空白</b> —— 空白会被客服读成「这条评价没有关联订单」。</li>
 *   <li><b>空态要区分两种</b>：「筛选无结果」与「还没有评价」是两句不同的话。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/reviews}，与同服务的 {@code crm/knowledge}、
 * {@code crm/review-requests}、{@code crm/review-rules}、{@code crm/buyer-emails}
 * **刻意不共用**。
 */

const base = 'crm/reviews';

/**
 * 评价列表的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface ProductReviewParams {
  shopId?: string | undefined;
  platformCode?: string | undefined;
  rating?: number | undefined;
  hasImage?: boolean | undefined;
  hasVideo?: boolean | undefined;
  unreplied?: boolean | undefined;
  productRef?: string | undefined;
  variantRef?: string | undefined;
  salesOrderNo?: string | undefined;
  buyerRef?: string | undefined;
  keyword?: string | undefined;
  reviewedFrom?: string | undefined;
  reviewedTo?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 把筛选条件拼成查询串（布尔值只在该为 true 时出现）。 */
export function productReviewQuery(params: ProductReviewParams = {}): string {
  const parts: string[] = [];
  if (params.shopId) parts.push(`shopId=${encodeURIComponent(params.shopId)}`);
  if (params.platformCode) parts.push(`platformCode=${encodeURIComponent(params.platformCode)}`);
  if (params.rating !== undefined) parts.push(`rating=${params.rating}`);
  if (params.hasImage) parts.push(`hasImage=true`);
  if (params.hasVideo) parts.push(`hasVideo=true`);
  if (params.unreplied) parts.push(`unreplied=true`);
  if (params.productRef) parts.push(`productRef=${encodeURIComponent(params.productRef)}`);
  if (params.variantRef) parts.push(`variantRef=${encodeURIComponent(params.variantRef)}`);
  if (params.salesOrderNo) parts.push(`salesOrderNo=${encodeURIComponent(params.salesOrderNo)}`);
  if (params.buyerRef) parts.push(`buyerRef=${encodeURIComponent(params.buyerRef)}`);
  if (params.keyword) parts.push(`keyword=${encodeURIComponent(params.keyword)}`);
  if (params.reviewedFrom) parts.push(`reviewedFrom=${encodeURIComponent(params.reviewedFrom)}`);
  if (params.reviewedTo) parts.push(`reviewedTo=${encodeURIComponent(params.reviewedTo)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。 */
export function fetchProductReviewCapabilities() {
  return api<CrmProductReviewCapabilities>(`${base}/capabilities`);
}

/** 店铺目录（来自上游 channel 的真实清单）+ 每个平台的评价接入状态。 */
export function fetchProductReviewShops() {
  return api<CrmProductReviewShopView[]>(`${base}/shops`);
}

/** 评价列表（真分页 + 真筛选）。 */
export function fetchProductReviews(params: ProductReviewParams = {}) {
  return api<CrmProductReviewPage>(`${base}${productReviewQuery(params)}`);
}

/**
 * 评分统计的查询串（比列表多一个 {@code days}，少分页参数）。
 *
 * <p>刻意做成一个**独立的函数**而不是在模板里拼字符串 ——
 * 空白筛选时不能产出 `?` 后面直接跟 `&` 的畸形查询串。
 */
export function productReviewStatsQuery(params: ProductReviewParams = {}, days = 30): string {
  const query = productReviewQuery({ ...params, page: undefined, size: undefined });
  return query ? `${query}&days=${days}` : `?days=${days}`;
}

/**
 * 评分统计（趋势 + 分布 + 下滑维度定位）。
 *
 * <p>⚠️ 作用域由后端按**平台评价时刻**算（`days` 天），页面只把 `days` 传过去。
 */
export function fetchProductReviewStats(params: ProductReviewParams = {}, days = 30) {
  return api<CrmProductReviewStats>(`${base}/stats${productReviewStatsQuery(params, days)}`);
}

/** 单条评价详情 = 评价本体 + 关联订单上下文。 */
export function fetchProductReview(reviewId: string) {
  return api<CrmProductReviewDetail>(`${base}/${encodeURIComponent(reviewId)}`);
}

/** 登记一条评价（人工登记）。 */
export function createProductReview(input: CrmProductReviewCreateInput) {
  return api<CrmProductReviewRow>(`${base}`, 'POST', input);
}

/**
 * 平台评价同步。
 *
 * <p>⚠️ 当前恒为 409（平台评价适配器未交付），后端**一行都不写**。
 */
export function syncProductReviews(input: CrmProductReviewSyncInput) {
  return api<CrmProductReviewSyncResult>(`${base}/sync`, 'POST', input);
}

/**
 * 导出当前筛选结果 CSV：走浏览器下载（同源代理原样透传 text/csv）。
 *
 * <p>与列表**同一套筛选条件** —— 导出的不是一份全量。
 */
export async function downloadProductReviewCsv(params: ProductReviewParams = {}): Promise<void> {
  const response = await fetch(`/api/crm/${base}/export${productReviewQuery(params)}`, {
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
  anchor.download = 'product-reviews.csv';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

// ================================================================ 可读形态

/** 星级的可读名。 */
export function ratingLabel(rating: number): string {
  return `${rating} 星`;
}

/** 「是否已回复」的可读名。 */
export function replyStateLabel(replied: boolean): string {
  return replied ? '已回复' : '未回复';
}

/** 评价来源的可读名。 */
export function reviewSourceLabel(source: string): string {
  switch (source) {
    case 'MANUAL_ENTRY':
      return '人工登记';
    case 'PLATFORM_SYNC':
      return '平台同步';
    default:
      return source;
  }
}

/** 下滑定位维度的可读名。 */
export function dimensionLabel(dimension: string): string {
  switch (dimension) {
    case 'SHOP':
      return '店铺';
    case 'PRODUCT':
      return '商品';
    case 'VARIANT':
      return '变体';
    default:
      return dimension;
  }
}

/**
 * 一条下滑记录的标题（维度 + 键 + 名称）。
 *
 * <p>名字缺失时用键兜底 —— 键一定要显示出来，否则客服看完「变体 SKU-DOWN」
 * 都不知道该去查哪个商品。
 */
export function declineLabel(decline: { dimension: string; key: string; label: string }): string {
  const name = decline.label.trim();
  return `${dimensionLabel(decline.dimension)}：${name || '（未命名）'}（${decline.key}）`;
}

/**
 * 「连续下滑」的可读名。
 *
 * <p>⚠️ 只有**三窗单调**才算连续：只有近窗与中窗可比时说「连续」是**过度解读**。
 */
export function continuousLabel(continuous: boolean): string {
  return continuous ? '连续下滑（三窗单调）' : '下滑（尚未确认连续）';
}
