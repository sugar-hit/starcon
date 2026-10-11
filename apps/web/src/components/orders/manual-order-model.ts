import type {
  SalesOrderAddressRow,
  SalesOrderCapabilities,
  SalesOrderChargeInput,
  SalesOrderChargeRow,
  SalesOrderCreateInput,
  SalesOrderCustomerInput,
  SalesOrderDetail,
  SalesOrderEventRow,
  SalesOrderLineInput,
  SalesOrderPage,
  SalesOrderRow,
  SalesOrderSaved,
  SalesOrderUpdateInput,
} from '../../../../../packages/api-contracts/src/catalog';
import { api } from '../../lib/api';

/**
 * 枚举类型**从行类型派生**，不单独 import。
 *
 * <p>codegen 把 Java 枚举展开成内联的字面量联合（`"MANUAL" | "PLATFORM" | ...`），
 * 不会生成独立的 `SalesOrderSource` 导出 —— 直接 import 会报「没有导出成员」。
 * 从行类型的字段派生，两者永远跟着 spec 走，spec 改值域这里自动跟着变。
 */
export type SalesOrderSource = SalesOrderRow['orderSource'];
export type SalesOrderStatus = SalesOrderRow['status'];
export type SalesOrderManualKind = SalesOrderRow['manualKind'];
export type SalesOrderPaymentMethod = SalesOrderRow['paymentMethod'];
export type SalesOrderChargeType = SalesOrderChargeRow['chargeType'];
export type SalesOrderChargeLevel = SalesOrderChargeRow['chargeLevel'];
export type SalesOrderAddressRole = SalesOrderAddressRow['addressRole'];
export type SalesOrderCustomerType = NonNullable<SalesOrderDetail['customer']>['customerType'];
export type SalesOrderEventType = SalesOrderEventRow['eventType'];

/**
 * 9.1.2.2 手工订单前端模型。
 *
 * <p>蓝图：「支持非平台来源订单（独立站线下、B2B、代发、样机单）的录入，含客户选择、
 * 商品行、收货地址、费用与支付方式」，输出「手工销售订单（来源 = MANUAL）+ 后续同样进入
 * 审单履约流程」。
 *
 * <p><b>界面纪律</b>（与后端逐条对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>来源是字段不是选择项</b>：新建表单里<b>没有</b>「来源」下拉。手工通道只产
 *       MANUAL，让用户能选来源等于开了一条「手工录入写平台单」的后门。页面把来源标成
 *       固定徽章，是为了让「为什么这里不能选」在界面上自解释。
 *   <li><b>价格由人填，但规则由界面先说清</b>：05 价目（D6）未交付，所以单价是表单必填项；
 *       界面**不内置任何默认价**，也不提示「建议价」。行小计与订单总额实时算给人看
 *       （{@link lineAmount} / {@link previewTotals}），但提交后由 DB 与明细逐项复算 ——
 *       界面算一遍、后端算一遍、DB 再算一遍，三处公式同源。
 *   <li><b>折扣的负号由界面代劳</b>：{@link normalizeChargeAmount} 让人只填「幅度」，
 *       界面按类型补上符号。负负得正会让「减钱」变成「加钱」，而列表上看不出来。
 *   <li><b>已提交的单只读</b>：提交后行、地址、费用、客户快照全部冻结，界面不给编辑入口
 *       （{@link isEditable}），而不是让人改完被后端 409 打回。
 *   <li><b>未交付的下游写明归属模块</b>，而不是给一个点下去 404 的按钮。
 *   <li>加载 / 空 / 错误三态齐全。
 * </ul>
 */

const base = 'trade/sales-orders';

export interface SalesOrderParams {
  manualKind?: string;
  status?: string;
  customerPartyId?: string;
  paymentMethod?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export function salesOrderQuery(params: SalesOrderParams): string {
  const parts: string[] = [];
  if (params.manualKind) parts.push(`manualKind=${encodeURIComponent(params.manualKind)}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.customerPartyId) {
    parts.push(`customerPartyId=${encodeURIComponent(params.customerPartyId)}`);
  }
  if (params.paymentMethod) parts.push(`paymentMethod=${encodeURIComponent(params.paymentMethod)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchCapabilities() {
  return api<SalesOrderCapabilities>(`${base}/capabilities`);
}

export function fetchOrders(params: SalesOrderParams) {
  return api<SalesOrderPage>(`${base}${salesOrderQuery(params)}`);
}

export function fetchOrder(orderId: string) {
  return api<SalesOrderDetail>(`${base}/${encodeURIComponent(orderId)}`);
}

export function createOrder(input: SalesOrderCreateInput) {
  return api<SalesOrderSaved>(`${base}`, 'POST', input);
}

export function updateOrder(orderId: string, input: SalesOrderUpdateInput) {
  return api<SalesOrderSaved>(`${base}/${encodeURIComponent(orderId)}`, 'PUT', input);
}

export function submitOrder(orderId: string) {
  return api<SalesOrderSaved>(`${base}/${encodeURIComponent(orderId)}/submit`, 'POST');
}

// ================================================================ 展示口径

export const salesOrderStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '已提交待审',
};

export const manualKindLabels: Record<string, string> = {
  OFFLINE_STORE: '独立站线下',
  B2B: 'B2B 批发',
  DROPSHIP: '代发',
  SAMPLE: '样机单',
};

export const orderSourceLabels: Record<string, string> = {
  MANUAL: '手工订单',
  PLATFORM: '平台订单',
  AMAZON: 'Amazon',
  SHOPIFY: 'Shopify',
  EBAY: 'eBay',
  TIKTOK_SHOP: 'TikTok Shop',
  ALIBABA_1688: '1688',
};

export const paymentMethodLabels: Record<string, string> = {
  PREPAID: '预付',
  COD: '货到付款',
  BANK_TRANSFER: '银行转账',
  CREDIT_TERM: '账期',
  FREIGHT_FORWARDER: '货代代收',
  OTHER: '其他',
};

export const addressRoleLabels: Record<string, string> = {
  SHIPPING: '收货地址',
  BILLING: '开票地址',
};

export const chargeTypeLabels: Record<string, string> = {
  SHIPPING: '运费',
  DISCOUNT: '折扣',
  TAX: '税费',
  PACKAGING: '包装费',
  OTHER: '其他费用',
};

export const chargeLevelLabels: Record<string, string> = {
  ORDER: '整单费用',
  LINE: '行级费用',
};

export const customerTypeLabels: Record<string, string> = {
  ORGANIZATION: '企业',
  INDIVIDUAL: '个人',
};

export const eventTypeLabels: Record<string, string> = {
  CREATED: '创建',
  SUBMITTED: '提交待审',
};

export function statusLabel(status: string): string {
  return salesOrderStatusLabels[status] ?? status;
}

export function manualKindLabel(kind: string): string {
  return manualKindLabels[kind] ?? kind;
}

export function orderSourceLabel(source: string): string {
  return orderSourceLabels[source] ?? source;
}

export function paymentMethodLabel(method: string): string {
  return paymentMethodLabels[method] ?? method;
}

export function chargeTypeLabel(type: string): string {
  return chargeTypeLabels[type] ?? type;
}

export function addressRoleLabel(role: string): string {
  return addressRoleLabels[role] ?? role;
}

/**
 * 状态徽章的视觉分级。
 *
 * <p>草稿是「还没动」，已提交待审是「在途」。本模块只有两个状态，所以只有两档 ——
 * 不为了凑三档颜色去给不存在的状态编一个。
 */
export function statusTone(status: string): 'neutral' | 'primary' {
  return status === 'SUBMITTED' ? 'primary' : 'neutral';
}

/** 已提交的单不可再改：行、地址、费用、客户快照全部冻结（V99 触发器钉死）。 */
export function isEditable(status: string): boolean {
  return status === 'DRAFT';
}

/** 只有草稿能提交待审。 */
export function canSubmit(status: string): boolean {
  return status === 'DRAFT';
}

/**
 * 折扣类费用必须是负数、其余必须是正数 —— 与 DB CHECK 同一口径。
 *
 * <p>界面让操作员只填「幅度」（正数），符号由 {@link normalizeChargeAmount} 补上。
 * 人手写负号时最常出的错就是「填了个正折扣」，那在列表上完全看不出来，
 * 却让订单总额凭空多了一截。
 */
export function chargeSign(type: string): 1 | -1 {
  return type === 'DISCOUNT' ? -1 : 1;
}

export function normalizeChargeAmount(type: string, amount: number): number {
  return Number((Math.abs(amount) * chargeSign(type)).toFixed(4));
}

/** 这一笔费用按规则应该是什么符号 —— 用来在表单里就地提示正负。 */
export function chargeSignHint(type: string): string {
  return type === 'DISCOUNT' ? '折扣记负数：填幅度即可，界面会补负号' : '正数：直接填金额';
}

/** 账期与非账期必须自洽：非账期不许带天数（与服务层、后端 CHECK 同一口径）。 */
export function normalizeTermsDays(method: string, days: number | null): number {
  const value = days ?? 0;
  return method === 'CREDIT_TERM' ? value : 0;
}

export function needsTermsDays(method: string): boolean {
  return method === 'CREDIT_TERM';
}

// ================================================================ 金额

/**
 * 行小计 = 单价 × 数量（4 位小数 HALF_UP）。
 *
 * <p>与服务层和 DB 的 {@code sales_order_line_amount_matches} CHECK 同一条公式。
 * 界面算这一遍是为了让人下单**之前**看见数，不是为了替代后端与 DB 的复算。
 */
export function lineAmount(line: Pick<SalesOrderLineInput, 'unitPrice' | 'quantity'>): number {
  return Number((Number(line.unitPrice ?? 0) * Number(line.quantity ?? 0)).toFixed(4));
}

export interface OrderTotals {
  subtotal: number;
  discount: number;
  tax: number;
  shipping: number;
  other: number;
  grandTotal: number;
}

/**
 * 订单汇总试算，公式与服务层 {@code ManualOrderService.totals} 及 DB 的
 * {@code guard_sales_order_totals} 逐条一致：
 *
 * <pre>
 *   小计 = Σ 行小计
 *   折扣 = −Σ 行折扣（负）− Σ DISCOUNT 费用（负）→ 取正
 *   税额 = Σ 行税 + Σ TAX 费用
 *   运费 = Σ SHIPPING 费用
 *   其他 = Σ PACKAGING / OTHER 费用
 *   合计 = 小计 − 折扣 + 税额 + 运费 + 其他
 * </pre>
 *
 * <p>界面与服务层各算一次是刻意的：这里算错时用户当场看得见（总额不对），
 * 而真提交上去之后 DB 还会独立复算一次。
 */
export function previewTotals(
  lines: Array<Pick<SalesOrderLineInput, 'unitPrice' | 'quantity' | 'discountAmount' | 'taxAmount'>>,
  charges: Array<{ chargeType: string; amount: number }>,
): OrderTotals {
  let subtotal = 0;
  let lineDiscount = 0;
  let lineTax = 0;
  for (const line of lines) {
    subtotal += lineAmount(line);
    lineDiscount += Number(line.discountAmount ?? 0);
    lineTax += Number(line.taxAmount ?? 0);
  }
  let shipping = 0;
  let chargeDiscount = 0;
  let chargeTax = 0;
  let other = 0;
  for (const charge of charges) {
    const amount = Number(charge.amount ?? 0);
    if (charge.chargeType === 'SHIPPING') shipping += amount;
    else if (charge.chargeType === 'DISCOUNT') chargeDiscount += amount;
    else if (charge.chargeType === 'TAX') chargeTax += amount;
    else other += amount; // PACKAGING / OTHER
  }
  const discount = -lineDiscount - chargeDiscount;
  const tax = lineTax + chargeTax;
  return {
    subtotal: round(subtotal),
    discount: round(discount),
    tax: round(tax),
    shipping: round(shipping),
    other: round(other),
    grandTotal: round(subtotal - discount + tax + shipping + other),
  };
}

function round(value: number): number {
  return Number(value.toFixed(4));
}

/** 金额格式化：始终带两位，跨币种（JPY 无小数、KWD 三位）由币种决定，这里只做展示。 */
export function money(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return Number(value).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 4,
  });
}

/** 数量格式化：去掉无意义的尾零，并把 6 位小数的零头也显示出来。 */
export function fmt(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return Number(Number(value).toFixed(6)).toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/** 时间格式化（timestamptz 带偏移的 ISO 串）。 */
export function moment(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

// ================================================================ 表单校验

/**
 * 提交前的缺口提示。
 *
 * <p>这三条与 V99 第 8.1 节的延迟约束触发器逐条对应：后端提交时一定会再核一次，
 * 这里先说清是为了让人在建单时就补齐，而不是提交后才被 DB 打回。
 */
export function submitBlockers(detail: {
  lines: unknown[];
  addresses: Array<{ addressRole: string }>;
  customer: unknown | null;
}): string[] {
  const blockers: string[] = [];
  if (detail.lines.length === 0) blockers.push('至少要有一行商品：没有商品行的单在审单队列里无事可审');
  if (!detail.addresses.some((a) => a.addressRole === 'SHIPPING')) {
    blockers.push('必须有收货地址：收货地址是下单当时冻结的那一份，没有它无法发货');
  }
  if (!detail.customer) blockers.push('必须有客户快照：缺了它订单挂不到任何对手方名下');
  return blockers;
}

/** 费用行按类型分组的汇总文案（详情页展示用）。 */
export function chargeSummary(charges: SalesOrderChargeRow[]): string {
  if (charges.length === 0) return '无费用';
  return charges
    .map((c) => `${chargeTypeLabel(c.chargeType)} ${money(c.amount)}`)
    .join(' · ');
}

/** 未交付下游的说明文案（禁用按钮时写清归属模块，而不是让用户猜）。 */
export function downstreamGapText(moduleId: string, available: boolean, effect: string): string {
  if (available) return '';
  return `${moduleId} 尚未交付：${effect}`;
}
