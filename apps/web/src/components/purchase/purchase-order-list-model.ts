import type {
  PurchaseOrderListBatchResult,
  PurchaseOrderListCapabilities,
  PurchaseOrderListCloseRequest,
  PurchaseOrderListConversionRequest,
  PurchaseOrderListConversionResult,
  PurchaseOrderListFilterOptions,
  PurchaseOrderListPage,
  PurchaseOrderListRow,
  PurchaseOrderListUrgeRequest,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 6.3.1.1 采购单列表前端模型。
 *
 * <p>蓝图：「采购单主列表：单号、供应商、SKU 行数、总金额、币种、状态、承诺到货日、
 * 已收数量/比例、逾期标记，支持多维筛选与导出。」输出「分页列表 + 批量操作（催单、关闭、导出）」。
 *
 * <p><b>界面纪律</b>（逐条对应后端模型的口径，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>「没进到货跟踪」不是「一件都没到」</b>：{@code receivedQuantity} 为 null 时
 *       显示「未跟踪」，<b>绝不显示 0</b> —— 0 会被读成「一件都没到，该催货了」，
 *       而 null 的含义是「这张单还没登记进 6.3.2.2 的到货跟踪，该去把单登记进去」。
 *       两件事对采购员的动作含义相反。</li>
 *   <li><b>总金额按币种分组</b>：界面**不出现**一个跨币种的合计数。
 *       USD 与 EUR 加成一个数不是任何一个金额。</li>
 *   <li><b>本位币合计可为「无」</b>：冻结不到汇率快照时后端给 null，
 *       页面显示「无数据」并把后端写的排除原因原样透出，
 *       不用固定汇率在本地凑一个数。</li>
 *   <li><b>数据范围常驻</b>：当前生效的范围（ALL / DEPARTMENT / SELF）由服务端推导，
 *       页面把它与范围说明显示出来 —— 采购员要能知道「我为什么只看到这几张单」。</li>
 *   <li><b>不重算后端的结论</b>：状态、逾期天数、已收比例一律原样显示后端给的。</li>
 *   <li>加载 / 空 / 错误三态齐全，且三态文案各不相同。</li>
 * </ul>
 */

const base = 'trade/purchase-orders';

// ================================================================ 查询参数

export interface PurchaseOrderListParams {
  // `| undefined` 是 `exactOptionalPropertyTypes` 下的必需写法：
  // 筛选器常常把「可能没有」的值直接透传，不显式声明 undefined 的话 TS 会拒绝显式传 undefined。
  supplierRefId?: string | undefined;
  status?: string | undefined;
  targetWarehouseId?: string | undefined;
  currency?: string | undefined;
  skuId?: string | undefined;
  createdFrom?: string | undefined;
  createdTo?: string | undefined;
  promisedFrom?: string | undefined;
  promisedTo?: string | undefined;
  overdueOnly?: boolean | undefined;
  keyword?: string | undefined;
  sort?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/**
 * 列表的查询串。
 *
 * <p>🔴 刻意做成**独立函数**而不是在模板里内联三元表达式：白名单守卫
 * （{@code purchase-order-list-allowlist.test.ts}）会从本文件源码里**反向抽取**真实调用点，
 * 模板里多一个内联插值就会抽出一条对不上的假路径。
 */
export function purchaseOrderListQuery(params: PurchaseOrderListParams): string {
  const parts: string[] = [];
  if (params.supplierRefId) parts.push(`supplierRefId=${encodeURIComponent(params.supplierRefId)}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.targetWarehouseId)
    parts.push(`targetWarehouseId=${encodeURIComponent(params.targetWarehouseId)}`);
  if (params.currency) parts.push(`currency=${encodeURIComponent(params.currency)}`);
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  if (params.createdFrom) parts.push(`createdFrom=${encodeURIComponent(params.createdFrom)}`);
  if (params.createdTo) parts.push(`createdTo=${encodeURIComponent(params.createdTo)}`);
  if (params.promisedFrom) parts.push(`promisedFrom=${encodeURIComponent(params.promisedFrom)}`);
  if (params.promisedTo) parts.push(`promisedTo=${encodeURIComponent(params.promisedTo)}`);
  if (params.overdueOnly) parts.push('overdueOnly=true');
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 调用点

export function fetchPurchaseOrderList(params: PurchaseOrderListParams) {
  return api<PurchaseOrderListPage>(`${base}${purchaseOrderListQuery(params)}`);
}

export function fetchPurchaseOrderListCapabilities() {
  return api<PurchaseOrderListCapabilities>(`${base}/capabilities`);
}

export function fetchPurchaseOrderListFilters() {
  return api<PurchaseOrderListFilterOptions>(`${base}/filters`);
}

/** 批量催单：只留痕（催单次数 + 最近催单时刻 + 留痕），**不改承诺到货日**。 */
export function urgePurchaseOrders(orderIds: string[], note: string) {
  const input: PurchaseOrderListUrgeRequest = { orderIds, note };
  return api<PurchaseOrderListBatchResult>(`${base}/batch/urge`, 'POST', input);
}

/** 批量关闭：理由必填（后端与 DB 层都拒空理由）。 */
export function closePurchaseOrders(orderIds: string[], reason: string) {
  const input: PurchaseOrderListCloseRequest = { orderIds, reason };
  return api<PurchaseOrderListBatchResult>(`${base}/batch/close`, 'POST', input);
}

/**
 * 由 6.2.2.1 的转单事实生成采购单。
 *
 * <p>6.3.1.2（创建与编辑）未交付 ⇒ 本仓此前**没有任何采购单产生路径**，
 * 列表会永远是空的。6.2.2.1 的转单事实正是「转成采购单时的参数」，本模块把这条路径先落地。
 */
export function createPurchaseOrderFromConversions(conversionIds: string[]) {
  const input: PurchaseOrderListConversionRequest = { conversionIds };
  return api<PurchaseOrderListConversionResult>(`${base}/from-conversions`, 'POST', input);
}

/**
 * 导出当前筛选结果 CSV：走浏览器下载（同源代理原样透传 text/csv）。
 *
 * <p>与列表**同一套筛选条件** —— 导出的不是一份全量。
 */
export async function downloadPurchaseOrderCsv(params: PurchaseOrderListParams): Promise<void> {
  const response = await fetch(`/api/trade/${base}/export${purchaseOrderListQuery(params)}`, {
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
  anchor.download = `purchase-orders-${params.status ?? 'all'}.csv`;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

// ================================================================ 展示口径

/** 采购单状态的标签（与后端 PurchaseOrderListStatus 一一对应）。 */
export const purchaseOrderStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_APPROVAL: '待审批',
  APPROVED: '已审批',
  REJECTED: '已驳回',
  IN_FULFILLMENT: '执行中',
  CLOSED: '已关闭',
};

export function orderStatusLabel(status: string): string {
  return purchaseOrderStatusLabels[status] ?? status;
}

/**
 * 状态决定视觉分级。
 *
 * <p>已驳回是危险色（这张单走不下去），待审批是警示色，执行中是信息色，
 * 已关闭是中性色（终态，不再需要动作）。
 */
export function orderStatusTone(
  status: string,
): 'danger' | 'warning' | 'success' | 'info' | 'neutral' {
  switch (status) {
    case 'REJECTED':
      return 'danger';
    case 'PENDING_APPROVAL':
      return 'warning';
    case 'APPROVED':
    case 'IN_FULFILLMENT':
      return 'info';
    case 'CLOSED':
      return 'neutral';
    case 'DRAFT':
      return 'success';
    default:
      return 'neutral';
  }
}

/** 6.3.2.2 的到货状态标签（本模块只**原样透传**供对照，不重新判定）。 */
export const purchaseArrivalStatusLabels: Record<string, string> = {
  PENDING_SHIPMENT: '待发货',
  IN_TRANSIT: '在途',
  PARTIALLY_RECEIVED: '部分到货',
  FULLY_RECEIVED: '全部到货',
  OVERDUE: '逾期未到',
  CANCELLED: '取消',
};

export function arrivalStatusLabel(status: string | null | undefined): string {
  if (!status) return '无数据';
  return purchaseArrivalStatusLabels[status] ?? status;
}

/** 「无数据」的统一文案。null 一律用它 —— 0 会被读成「不用备 / 不要钱 / 一件都没到」。 */
export const NO_DATA_TEXT = '无数据';

/**
 * 已收数量的来源标签。
 *
 * <p>🔴 界面上**必须**让人看出「这个数有没有到货跟踪撑着」：
 * NOT_TRACKED = 还没登记进 6.3.2.2 的到货跟踪（该去登记），
 * ARRIVAL_TRACKING = 有到货流水撑着（0 就是真的「一件都没到」）。
 */
export function receivedSourceLabel(source: string): string {
  return source === 'ARRIVAL_TRACKING' ? '到货跟踪' : '未跟踪';
}

/** 数量的展示。null 一律给「无数据」。 */
export function fmtQty(value: number | null | undefined): string {
  if (value === null || value === undefined) return NO_DATA_TEXT;
  const rounded = Number(value.toFixed(6));
  return rounded.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/** 金额的展示。null = 金额**算不出来**，**不是 0**。 */
export function fmtAmount(value: number | null | undefined): string {
  if (value === null || value === undefined) return NO_DATA_TEXT;
  const rounded = Number(value.toFixed(4));
  return rounded.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 });
}

/** 已收比例的展示。null = 没进到货跟踪，**不是 0%**。 */
export function fmtRatio(value: number | null | undefined): string {
  if (value === null || value === undefined) return NO_DATA_TEXT;
  return `${(value * 100).toFixed(1)}%`;
}

/** 交期的展示。 */
export function fmtDate(value: string | null | undefined): string {
  if (!value) return '未定';
  return value;
}

/**
 * 逾期标记的展示。
 *
 * <p>不逾期时给「否」而不是空 —— 空会让人以为是数据没取到。
 * 逾期时把天数一起给出（天数由后端按数据库时钟算）。
 */
export function overdueText(row: Pick<PurchaseOrderListRow, 'overdue' | 'overdueDays'>): string {
  if (!row.overdue) return '否';
  return row.overdueDays === null ? '是' : `是（逾期 ${row.overdueDays} 天）`;
}

/** 数据范围的标签。 */
export function dataScopeLabel(scope: string | null | undefined): string {
  switch (scope) {
    case 'DEPARTMENT':
      return '本部门';
    case 'SELF':
      return '本人负责';
    case 'ALL':
      return '全租户';
    default:
      return '未知范围';
  }
}

/**
 * 这一张采购单还能不能催单。
 *
 * <p>已关闭的单不再催单 —— 催一张关掉的单只是制造噪音，
 * 而且会让人以为它还在走流程。
 */
export function isUrgeable(status: string): boolean {
  return status !== 'CLOSED';
}

/**
 * 这一张采购单还能不能关闭。
 *
 * <p>已关闭的单关第二次没有意义（后端也会拒）。
 */
export function isCloseable(status: string): boolean {
  return status !== 'CLOSED';
}

/**
 * 批量结果的展示文案。
 *
 * <p>逐单成败：有的单能关、有的不能关是常事（已关闭的、不是你的、状态不允许）。
 * 页面要显示**失败原因**，而不是只说「失败了几条」。
 */
export function batchResultText(result: PurchaseOrderListBatchResult): string {
  if (result.failures.length === 0) {
    return `已处理 ${result.succeeded} 张采购单`;
  }
  const detail = result.failures
    .map((f) => `${f.orderNo || f.orderId}：${f.reason}`)
    .join('；');
  return `成功 ${result.succeeded} 张，失败 ${result.failures.length} 张 —— ${detail}`;
}
