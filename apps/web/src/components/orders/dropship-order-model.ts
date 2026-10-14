import type {
  DropshipAlertAckInput,
  DropshipAlertPage,
  DropshipAlertRow,
  DropshipEndpointInput,
  DropshipEndpointPage,
  DropshipEndpointRow,
  DropshipEndpointSaved,
  DropshipFailureInput,
  DropshipOrderCapabilities,
  DropshipOrderDetail,
  DropshipOrderEventRow,
  DropshipOrderInput,
  DropshipOrderLineRow,
  DropshipOrderPage,
  DropshipOrderRow,
  DropshipOrderSaved,
  DropshipPaymentConfirmInput,
  DropshipShipmentInput,
  DropshipSupplierAckInput,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 9.6.1.2 代发单与供应商下单前端模型。
 *
 * <p>蓝图：「买家下单后，自动/手工向 1688 等供应商代发平台下单，传递地址与商品信息，
 * 回填供应商单号与代发成本，计算我方差价毛利。输出：代发单（供应商、单号、发货状态）；
 * 差价成本；差异告警（供应商无货/涨价/失败）。」
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>差价为负不是 bug，是要显眼的经营结论。</b>转负的单在列表里用警示色标出
 *       并置顶提示，而不是与正常单混排 —— 蓝图说「否则差价转负的订单会静默亏损」，
 *       而「静」首先就是「混在正常行里看不见」。</li>
 *   <li><b>「还没回执」与「回执是 0」不是一回事。</b>
 *       {@code actualPurchaseAmount} 为 {@code null} 显示「待回执」，
 *       而不是「0」。画成同一个 0 会让人以为供应商回的是零成本。</li>
 *   <li><b>四类告警分开显示</b>：涨价 / 无货 / 失败 / 差价转负是四个不同的事实，
 *       合并成一条「有异常」就没法处置（改价 / 换供应商 / 重下 / 止损各不相同）。</li>
 *   <li><b>收款确认必须显出主体。</b>9.1 没有支付状态列，本模块的「已收款」是
 *       <b>有成员署名的书面确认</b>，界面上必须显示「谁在什么时候确认的、依据是什么」，
 *       让人一眼看出这不是系统读到的支付状态。</li>
 *   <li><b>未接线的 vendor 说人话。</b>1688 未接线时显示「还差什么」，
 *       而不是画成一个可点的按钮让人反复重试一个不存在的集成。</li>
 *   <li><b>金额不在前端二次取整。</b>代发成本、差价、差价率都由后端生成列算好，
 *       前端只格式化不重算 —— 再 round 一次会让界面上比账面多出或少掉一分钱。</li>
 * </ul>
 */

// ------------------------------------------------------------ 参数

export interface DropshipOrderParams {
  orderNo?: string;
  salesOrderNo?: string;
  supplierCode?: string;
  vendorCode?: string;
  status?: string;
  negativeSpreadOnly?: boolean;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface DropshipAlertParams {
  orderId?: string;
  alertKind?: string;
  status?: string;
  severity?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface DropshipEndpointParams {
  vendorCode?: string;
  page?: number;
  size?: number;
}

function query<T extends object>(params: T): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params as Record<string, unknown>)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

// ------------------------------------------------------------ 接口

const base = 'trade/fulfillment/dropship-orders';

export function fetchDropshipOrderCapabilities(): Promise<DropshipOrderCapabilities> {
  return api<DropshipOrderCapabilities>(`${base}/capabilities`);
}

export function fetchDropshipEndpoints(
  params: DropshipEndpointParams,
): Promise<DropshipEndpointPage> {
  return api<DropshipEndpointPage>(`${base}/endpoints${query(params)}`);
}

export function saveDropshipEndpoint(input: DropshipEndpointInput): Promise<DropshipEndpointSaved> {
  return api<DropshipEndpointSaved>(`${base}/endpoints`, 'POST', input);
}

export function fetchDropshipOrders(params: DropshipOrderParams): Promise<DropshipOrderPage> {
  return api<DropshipOrderPage>(`${base}/orders${query(params)}`);
}

export function createDropshipOrder(input: DropshipOrderInput): Promise<DropshipOrderSaved> {
  return api<DropshipOrderSaved>(`${base}/orders`, 'POST', input);
}

export function fetchDropshipOrder(orderId: string): Promise<DropshipOrderDetail> {
  return api<DropshipOrderDetail>(`${base}/orders/${orderId}`);
}

export function confirmDropshipPayment(
  orderId: string,
  input: DropshipPaymentConfirmInput,
): Promise<DropshipOrderSaved> {
  return api<DropshipOrderSaved>(`${base}/orders/${orderId}/confirm-payment`, 'POST', input);
}

export function dispatchDropshipOrder(orderId: string): Promise<DropshipOrderSaved> {
  return api<DropshipOrderSaved>(`${base}/orders/${orderId}/dispatch`, 'POST');
}

export function recordDropshipSupplierAck(
  orderId: string,
  input: DropshipSupplierAckInput,
): Promise<DropshipOrderSaved> {
  return api<DropshipOrderSaved>(`${base}/orders/${orderId}/supplier-ack`, 'POST', input);
}

export function recordDropshipShipment(
  orderId: string,
  input: DropshipShipmentInput,
): Promise<DropshipOrderSaved> {
  return api<DropshipOrderSaved>(`${base}/orders/${orderId}/shipment`, 'POST', input);
}

export function markDropshipOrderFailed(
  orderId: string,
  input: DropshipFailureInput,
): Promise<DropshipOrderSaved> {
  return api<DropshipOrderSaved>(`${base}/orders/${orderId}/fail`, 'POST', input);
}

export function fetchDropshipAlerts(params: DropshipAlertParams): Promise<DropshipAlertPage> {
  return api<DropshipAlertPage>(`${base}/alerts${query(params)}`);
}

export function acknowledgeDropshipAlert(
  alertId: string,
  input: DropshipAlertAckInput,
): Promise<DropshipAlertRow> {
  return api<DropshipAlertRow>(`${base}/alerts/${alertId}/acknowledge`, 'POST', input);
}

// ------------------------------------------------------------ 词表

export const ORDER_STATUSES: { value: string; label: string }[] = [
  { value: 'DRAFT', label: '草稿（未确认收款）' },
  { value: 'READY', label: '已确认收款，可下单' },
  { value: 'DISPATCHED', label: '已提交供应商，待回执' },
  { value: 'ACCEPTED', label: '供应商已受理' },
  { value: 'SHIPPED', label: '供应商已发货' },
  { value: 'FAILED', label: '下单失败' },
  { value: 'CANCELLED', label: '已取消' },
];

export const DISPATCH_MODES: { value: string; label: string }[] = [
  { value: 'AUTO', label: '自动（走供应商接口）' },
  { value: 'MANUAL', label: '人工录入回执' },
];

export const VENDOR_CODES: { value: string; label: string }[] = [
  { value: 'MANUAL_ENTRY', label: '人工录入' },
  { value: 'ALIBABA_1688', label: '1688 开放平台' },
];

/**
 * 四类差异告警。刻意**不合并**成「有异常」：
 * 处置动作完全不同（改售价 / 换供应商 / 重下 / 止损）。
 */
export const ALERT_KINDS: { value: string; label: string }[] = [
  { value: 'PRICE_INCREASE', label: '供应商涨价' },
  { value: 'NO_STOCK', label: '供应商无货' },
  { value: 'ORDER_FAILED', label: '下单失败' },
  { value: 'NEGATIVE_SPREAD', label: '差价转负（亏损）' },
];

export const ALERT_STATUSES: { value: string; label: string }[] = [
  { value: 'OPEN', label: '未处置' },
  { value: 'ACKNOWLEDGED', label: '已确认' },
  { value: 'RESOLVED', label: '已处置' },
];

export const SEVERITIES: { value: string; label: string }[] = [
  { value: 'INFO', label: '提示' },
  { value: 'WARNING', label: '警告' },
  { value: 'CRITICAL', label: '严重' },
];

function labelOf(options: { value: string; label: string }[], value: string): string {
  return options.find((o) => o.value === value)?.label ?? value;
}

export function orderStatusLabel(v: string): string {
  return labelOf(ORDER_STATUSES, v);
}
export function dispatchModeLabel(v: string): string {
  return labelOf(DISPATCH_MODES, v);
}
export function vendorLabel(v: string): string {
  return labelOf(VENDOR_CODES, v);
}
export function alertKindLabel(v: string): string {
  return labelOf(ALERT_KINDS, v);
}
export function alertStatusLabel(v: string): string {
  return labelOf(ALERT_STATUSES, v);
}
export function severityLabel(v: string): string {
  return labelOf(SEVERITIES, v);
}

// ------------------------------------------------------------ 展示层判定

/** 差价是否已经转负 —— 这是本模块最需要被一眼看见的那一格。 */
export function negativeSpread(order: DropshipOrderRow): boolean {
  return Number(order.spreadAmount) < 0;
}

/**
 * 「还没回执」还是「回执了」？
 *
 * <p>代发成本（差价成本）只在有回执之后才是**真实**成本。冻结价算出来的那个数
 * 是「预计」，回执后的才是「实际」，界面上必须分开标。
 */
export function hasSupplierAck(order: DropshipOrderRow): boolean {
  return order.actualPurchaseAmount !== null && order.actualPurchaseAmount !== undefined;
}

/** 收款是否已确认（注意：是人工确认，不是系统读到的支付状态）。 */
export function paymentConfirmed(order: DropshipOrderRow): boolean {
  return order.paymentConfirmedAt !== null && order.paymentConfirmedAt !== undefined;
}

/** 是否还能向供应商提交（只有「已确认收款」可以）。 */
export function canDispatch(order: DropshipOrderRow): boolean {
  return order.status === 'READY';
}

/** 是否可以录入供应商回执（只有「已提交、待回执」可以）。 */
export function canRecordAck(order: DropshipOrderRow): boolean {
  return order.status === 'DISPATCHED';
}

/** 是否可以记发货（只有「供应商已受理」可以）。 */
export function canRecordShipment(order: DropshipOrderRow): boolean {
  return order.status === 'ACCEPTED';
}

/** 是否可以人工置失败（终态与已取消都不行）。 */
export function canMarkFailed(order: DropshipOrderRow): boolean {
  return ['READY', 'DISPATCHED', 'ACCEPTED'].includes(order.status);
}

/**
 * 供应商实际成本相对下单冻结价的偏离额（正数 = 比预计贵）。
 *
 * <p>刻意只读后端回填的两个数做减法，**不重算差价**：差价有 DB 生成列，
 * 前端再算一份必然与账面漂移。
 */
export function costDrift(order: DropshipOrderRow): number | null {
  if (!hasSupplierAck(order)) return null;
  return (
    Number(order.actualPurchaseAmount) +
    Number(order.actualSupplierShippingAmount ?? 0) -
    (Number(order.purchaseAmount) + Number(order.supplierShippingAmount ?? 0))
  );
}

/** 未接线的 vendor 在界面上该显示什么（已接线的返回 null）。 */
export function vendorUnavailableHint(
  vendors: { vendorCode: string; displayName: string; wired: boolean; wiringRequirement: string }[],
  vendorCode: string,
): string | null {
  const found = vendors.find((v) => v.vendorCode === vendorCode);
  if (!found || found.wired) return null;
  return found.wiringRequirement;
}

/** 未处置的 CRITICAL 告警数（列表页顶部的告警徽标）。 */
export function openCriticalAlertCount(alerts: DropshipAlertRow[]): number {
  return alerts.filter((a) => a.status === 'OPEN' && a.severity === 'CRITICAL').length;
}

/** 能力维度不可用时的说明（而不是画成 0 或「—」）。 */
export function dimensionNote(
  dimensions: {
    code: string;
    available: boolean;
    note: string | null;
    ownerModule: string | null;
  }[],
  code: string,
): string | null {
  const found = dimensions.find((d) => d.code === code);
  if (!found || found.available) return null;
  return found.note ?? (found.ownerModule ? `归属 ${found.ownerModule}` : null);
}

// ------------------------------------------------------------ 格式化

/** 金额文本：统一两位，不在前端再四舍五入成别的口径。 */
export function moneyText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return Number(value).toFixed(2);
}

/** 差价文本：转负时带负号前缀，便于扫视。 */
export function spreadText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  const n = Number(value);
  return n < 0 ? `-${Math.abs(n).toFixed(2)}` : n.toFixed(2);
}

/** 差价率文本（前端不重算，只把后端生成的比例格式化）。 */
export function marginRateText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return `${(Number(value) * 100).toFixed(2)}%`;
}

/** 数量文本：去掉多余的尾零。 */
export function qtyText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  const n = Number(value);
  return Number.isInteger(n) ? String(n) : String(Number(n.toFixed(6)));
}

/** 实际采购价文本：null 显示「待回执」而不是 0（0 是「供应商回的是零」）。 */
export function actualCostText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '待回执';
  return Number(value).toFixed(2);
}

/** 偏离额文本：null（还没回执）显示「—」。 */
export function driftText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  const n = Number(value);
  if (n === 0) return '0.00';
  return n > 0 ? `+${n.toFixed(2)}` : n.toFixed(2);
}

// ------------------------------------------------------------ 表单自校验

/** 收款确认表单：依据不能空 —— 空依据等于没有凭据，事后无从追责。 */
export function paymentFormError(form: { note: string }): string | null {
  if (!form.note.trim()) {
    return '必须写明确认依据：这笔钱是怎么确认到账的（银行流水号 / 平台回执 / 现金签收）';
  }
  return null;
}

/** 供应商回执表单：单号与两个金额都必须真实给出。 */
export function supplierAckFormError(form: {
  supplierOrderNo: string;
  actualPurchaseAmount: string;
  actualSupplierShippingAmount: string;
}): string | null {
  if (!form.supplierOrderNo.trim()) {
    return '必须录入供应商单号：没有单号就对不上账，也追不到这批货';
  }
  if (!form.actualPurchaseAmount.trim() || Number.isNaN(Number(form.actualPurchaseAmount))) {
    return '必须录入供应商实际采购价：涨价判定比的是这个数';
  }
  if (Number(form.actualPurchaseAmount) < 0) return '实际采购价不能为负';
  if (
    !form.actualSupplierShippingAmount.trim() ||
    Number.isNaN(Number(form.actualSupplierShippingAmount))
  ) {
    return '必须录入供应商实际运费：代发成本 = 采购价 + 运费，缺运费差价会算错';
  }
  if (Number(form.actualSupplierShippingAmount) < 0) return '实际运费不能为负';
  return null;
}

/** 供应商接口登记表单：声称已接线就必须给出 baseUrl。 */
export function endpointFormError(form: {
  vendorName: string;
  baseUrl: string;
  credentialRef: string;
  wired: boolean;
}): string | null {
  if (!form.vendorName.trim()) return '请填写供应商名称';
  if (form.wired && !form.baseUrl.trim()) {
    return (
      '标记为「已接线」就必须给出网关地址：地址为空时任何调用都不可能成功，' +
      '而一个声称可用却调不通的集成比明确报未接线更糟'
    );
  }
  if (form.credentialRef.includes('=')) {
    return 'credentialRef 只能是 secret manager 里的键名，不接受 key=value 形态的明文凭证';
  }
  return null;
}

export type OrderRow = DropshipOrderRow;
export type LineRow = DropshipOrderLineRow;
export type AlertRow = DropshipAlertRow;
export type EventRow = DropshipOrderEventRow;
export type EndpointRow = DropshipEndpointRow;
export type OrderDetail = DropshipOrderDetail;

export { ApiError };
