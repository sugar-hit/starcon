import type {
  B2BCapabilities,
  B2BCustomerTermsPage,
  B2BCustomerTermsRow,
  B2BOrderCreateInput,
  B2BOrderDetail,
  B2BOrderPage,
  B2BOrderRow,
  B2BOrderUpdateInput,
  B2BPriceBookPage,
  B2BPriceBookRequest,
  B2BPriceBookRow,
  B2BPriceQuoteRequest,
  B2BPriceQuoteRow,
  B2BReceivablePage,
  B2BReceivableRow,
  B2BQuoteResponse,
  B2BSaved,
  B2BShipmentInput,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 枚举类型**从行类型派生**，不单独 import。
 *
 * <p>codegen 把 Java 枚举展开成内联的字面量联合，不会生成独立的 `B2BOrderType` 导出；
 * 从行类型的字段派生，两者永远跟着 spec 走，spec 改值域这里自动跟着变。
 */
export type B2BOrderType = B2BOrderRow['orderType'];
export type B2BStatus = B2BOrderRow['status'];
export type B2BPaymentMethod = B2BOrderRow['paymentMethod'];
export type B2BCostBasis = B2BOrderRow['costBasis'];
export type B2BCustomerLevel = B2BOrderRow['customerLevel'];
export type B2BReceivableStatus = B2BReceivableRow['status'];
export type B2BInvoiceState = B2BReceivableRow['invoiceState'];

/**
 * 9.7.1.1 批发订单与账期前端模型。
 *
 * <p>蓝图：「支持 B2B/批发订单：客户分级价、起订量、阶梯价、账期结算（不即时收款）、
 * 开票与对账；与零售订单在订单类型、收款方式、成本口径上区分」。
 *
 * <p><b>界面纪律</b>（与后端逐条对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>单价不可手填</b>：下单表单只收 SKU 与数量，落档由后端算出。
 *       界面改数量就重新 {@link fetchPriceQuote} 试算并显示「再买多少进下一档」，
 *       而不是给一个可以随便填的单价输入框。</li>
 *   <li><b>起订量在界面上先说清</b>：试算返回里带 {@code moqQuantity}，低于它时
 *       界面直接把起订量标红并禁用提交，而不是等点提交才吃一个 400。</li>
 *   <li><b>「未发货不产生应收」必须看得见</b>：列表与详情都显式展示
 *       「订单总额 / 已确认应收 / 未发货」，三态下不发货金额恒大于 0 ——
 *       让「钱在哪一环才会产生」在界面上是一眼能核对的事实。</li>
 *   <li><b>账期到期日按写入值展示</b>：应收行的到期日直接显示后端返回的
 *       {@code dueDate}，不在前端做时区换算（换算就是那些 −28800s 的来源）。</li>
 *   <li><b>下单结算不产生应收要说原因</b>：发货结果返回的 {@code message} 直接展示，
 *       而不是只显示一个空的应收区让用户困惑。</li>
 *   <li><b>未交付的下游写明归属模块</b>，而不是给一个点下去 404 的按钮。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */

const base = 'trade/b2b-orders';

export interface B2BOrderParams {
  status?: string;
  customerLevel?: string;
  customerPartyId?: string;
  paymentMethod?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface B2BReceivableParams {
  status?: string;
  invoiceState?: string;
  orderId?: string;
  page?: number;
  size?: number;
}

export function b2bOrderQuery(params: B2BOrderParams): string {
  const parts: string[] = [];
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.customerLevel) parts.push(`customerLevel=${encodeURIComponent(params.customerLevel)}`);
  if (params.customerPartyId) {
    parts.push(`customerPartyId=${encodeURIComponent(params.customerPartyId)}`);
  }
  if (params.paymentMethod) parts.push(`paymentMethod=${encodeURIComponent(params.paymentMethod)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function b2bReceivableQuery(params: B2BReceivableParams): string {
  const parts: string[] = [];
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.invoiceState) parts.push(`invoiceState=${encodeURIComponent(params.invoiceState)}`);
  if (params.orderId) parts.push(`orderId=${encodeURIComponent(params.orderId)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 上游投影

export function customerTermsQuery(params: {
  customerLevel?: string;
  keyword?: string;
  size?: number;
}): string {
  const parts: string[] = [];
  if (params.customerLevel) parts.push(`customerLevel=${encodeURIComponent(params.customerLevel)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function priceBooksQuery(params: { customerLevel?: string; skuId?: string; size?: number }): string {
  const parts: string[] = [];
  if (params.customerLevel) parts.push(`customerLevel=${encodeURIComponent(params.customerLevel)}`);
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// 查询串统一走**具名** query 函数，不在 URL 模板里就地拼条件表达式：
// 嵌套模板会让「从源码反向抽取真实调用点」的守卫读不懂这个调用点，
// 于是白名单漏登记时那条守卫恰好抽不到它 —— 守卫自己先失效了。
export function fetchCustomerTerms(params: {
  customerLevel?: string;
  keyword?: string;
  size?: number;
}) {
  return api<B2BCustomerTermsPage>(`${base}/customer-terms${customerTermsQuery(params)}`);
}

export function fetchPriceBooks(params: { customerLevel?: string; skuId?: string; size?: number }) {
  return api<B2BPriceBookPage>(`${base}/price-books${priceBooksQuery(params)}`);
}

export function savePriceBook(input: B2BPriceBookRequest) {
  return api<B2BPriceBookRow>(`${base}/price-books`, 'PUT', input);
}

/**
 * 阶梯价试算。只读、不落库，且走的是与建单**同一个**落档函数 ——
 * 界面上显示的价格与最终落库的价格不会出现两个答案。
 */
export function fetchPriceQuote(input: B2BPriceQuoteRequest) {
  return api<B2BQuoteResponse>(`${base}/price-quote`, 'POST', input);
}

// ================================================================ 订单

export function fetchCapabilities() {
  return api<B2BCapabilities>(`${base}/capabilities`);
}

export function fetchOrders(params: B2BOrderParams) {
  return api<B2BOrderPage>(`${base}${b2bOrderQuery(params)}`);
}

export function fetchOrder(orderId: string) {
  return api<B2BOrderDetail>(`${base}/${encodeURIComponent(orderId)}`);
}

export function createOrder(input: B2BOrderCreateInput) {
  return api<B2BSaved>(`${base}`, 'POST', input);
}

export function updateOrder(orderId: string, input: B2BOrderUpdateInput) {
  return api<B2BSaved>(`${base}/${encodeURIComponent(orderId)}`, 'PUT', input);
}

export function confirmOrder(orderId: string) {
  return api<B2BSaved>(`${base}/${encodeURIComponent(orderId)}/confirm`, 'POST');
}

/** 发货确认 —— 应收的**唯一**产生入口。 */
export function shipOrder(orderId: string, input: B2BShipmentInput) {
  return api<B2BSaved>(`${base}/${encodeURIComponent(orderId)}/shipments`, 'POST', input);
}

export function requestInvoice(orderId: string) {
  return api<B2BSaved>(`${base}/${encodeURIComponent(orderId)}/invoice-requests`, 'POST');
}

export function fetchReceivables(params: B2BReceivableParams) {
  return api<B2BReceivablePage>(`${base}/receivables${b2bReceivableQuery(params)}`);
}

// ================================================================ 展示口径

export const b2bStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  PARTIALLY_SHIPPED: '部分发货',
  SHIPPED: '已发货',
};

export const b2bOrderTypeLabels: Record<string, string> = {
  B2B_WHOLESALE: '批发',
  B2B_DISTRIBUTION: '分销',
  B2B_PROJECT: '项目采购',
};

/**
 * 收款方式标签**带口径说明**，不只是三个词。
 *
 * <p>蓝图把「账期结算不即时收款」列为硬要求，而界面只写「账期」三个字时，
 * 用户分不清它和「确认收货后结算」的现金流差别在哪。
 */
export const b2bPaymentMethodLabels: Record<string, string> = {
  ORDER_SETTLEMENT: '下单结算（即时收款，不产生应收）',
  ON_RECEIPT: '确认收货后结算（发货确认产生应收）',
  CREDIT_TERM: '账期结算（发货确认产生应收，到期日 = 发货日 + 账期天数）',
};

export const b2bCostBasisLabels: Record<string, string> = {
  STANDARD: '标准成本',
  MOVING_WEIGHTED: '移动加权',
  LOT: '批次成本',
};

export const b2bCustomerLevelLabels: Record<string, string> = {
  STRATEGIC: '战略客户',
  KEY: '重点客户',
  STANDARD: '普通客户',
  PROSPECT: '潜在客户',
  RISK: '风险客户',
};

export const b2bReceivableStatusLabels: Record<string, string> = {
  OPEN: '未结',
  SETTLED: '已结清',
  CANCELLED: '已作废',
};

export const b2bInvoiceStateLabels: Record<string, string> = {
  NOT_REQUESTED: '未提出开票',
  REQUESTED: '已提出开票',
  INVOICED: '已开票',
};

// ================================================================ 业务判定

/**
 * 是否可以发货。
 *
 * <p>草稿**不行** —— 草稿要先确认。界面上不给入口，而不是让人点了吃 409。
 */
export function canShip(status: string): boolean {
  return status === 'CONFIRMED' || status === 'PARTIALLY_SHIPPED';
}

/** 是否可以确认。已确认的单不能重复确认。 */
export function canConfirm(status: string): boolean {
  return status === 'DRAFT';
}

/**
 * 是否可以提出开票需求。
 *
 * <p>判据是「有没有未结且未提开票的应收」，不是「有没有发货」——
 * 未发货时后端会 409，界面提前按详情里的应收算。
 */
export function canRequestInvoice(detail: B2BOrderDetail | null): boolean {
  if (!detail) return false;
  return detail.receivables.some((r) => r.status === 'OPEN' && r.invoiceState === 'NOT_REQUESTED');
}

/**
 * 该行是否允许提交。
 *
 * <p>起订量是硬校验：低于它返回原因，界面直接禁用提交按钮并显示这句话。
 * 与后端 {@code resolveTier} 的判据逐字一致 —— 界面的「不能提交」和后端的 400
 * 说的是同一件事。
 */
export function lineBlockReason(quote: B2BPriceQuoteRow | null): string | null {
  if (!quote) return null;
  if (quote.moqQuantity > 0 && quote.quantity < quote.moqQuantity) {
    return `低于起订量：起订量 ${quote.moqQuantity} 件，本次 ${quote.quantity} 件`;
  }
  return null;
}

/** 下单表单在提交前的本地校验（与后端同判据，不替代后端校验）。 */
export function createBlockReason(input: {
  orderNo: string;
  customerPartyId: string;
  currency: string;
  paymentMethod: string;
  paymentTermsDays: number | null;
  lines: Array<{ quantity: string }>;
}): string | null {
  if (!input.orderNo.trim()) return '请填写订单号';
  if (!input.customerPartyId) return '请选择客户（账期与分级取自客户档案）';
  if (!/^[A-Z]{3}$/.test(input.currency.trim())) return '币种必须是 ISO 4217 三位码';
  if (input.paymentMethod === 'CREDIT_TERM' && !input.paymentTermsDays) {
    return '账期结算必须给出账期天数';
  }
  if (input.paymentMethod !== 'CREDIT_TERM' && input.paymentTermsDays) {
    return '下单结算与确认收货后结算是即时结算，不接受账期天数';
  }
  if (!input.lines.length) return '至少需要一个订单行';
  for (const [index, line] of input.lines.entries()) {
    const qty = Number(line.quantity);
    if (!Number.isFinite(qty) || qty <= 0) return `第 ${index + 1} 行数量必须大于 0`;
  }
  return null;
}

/** 「再买多少进下一档」的提示文案；已是最高档时返回 null。 */
export function nextTierHint(quote: B2BPriceQuoteRow | null): string | null {
  if (!quote || quote.lowestTier || quote.nextTierMinQuantity == null) return null;
  const gap = quote.nextTierMinQuantity - quote.quantity;
  return `再买 ${gap} 件进下一档（${quote.nextTierMinQuantity} 件起，单价 ${quote.nextTierUnitPrice}）`;
}

/** 账期到期展示：直接用后端返回的 ISO 值，不在前端做时区换算。 */
export function formatDueDate(iso: string | null): string {
  if (!iso) return '—';
  return iso.slice(0, 10);
}
