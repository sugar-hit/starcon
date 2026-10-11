import type {
  SalesOrderListCapabilities,
  SalesOrderListCurrencyAmount,
  SalesOrderListFilterOptions,
  SalesOrderListPage,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 9.1.1.1 订单列表（全渠道统一订单列表）前端模型。
 *
 * <p>蓝图：「全渠道订单统一列表：订单号、平台店铺、下单时间、买家、SKU 行数、总金额/币种、
 * 支付状态、履约状态、发货仓、平台状态，支持多维筛选、导出与批量操作
 * （审单、备注、取消、发催付）。」
 *
 * <p><b>界面纪律：本模型只负责「如实显示拿不到」，不负责「编一个像样的值」。</b>
 *
 * <ul>
 *   <li><b>六个不可得维度在表格里显示为「—」并可点开看原因</b>
 *       （{@link unavailableCell}），而不是留空白、也不是显示 0 或「未支付」。
 *       空白会被读成「这单没有支付信息」，而真相是「本服务还没有支付状态这个概念」——
 *       两者对运营的决策含义完全不同。</li>
 *   <li><b>单据状态与履约状态是两个字段</b>：{@link documentStatusLabels} 只映射
 *       DRAFT / SUBMITTED。界面<b>不</b>把它放在「履约状态」那一列下面 ——
 *       放进去等于宣称「已提交待审」就是履约状态。</li>
 *   <li><b>批量操作按钮一律禁用并说明归属</b>（{@link bulkOperationDisabled}）：
 *       本模块是读投影，不提供写端点，给一个能点的按钮只会让人点了得到 404。</li>
 *   <li><b>金额按币种分列展示</b>（{@link formatAmount}）：USD 与 EUR 加起来不是任何金额。</li>
 * </ul>
 *
 * <p>路径前缀是 {@code trade/sales-orders/list} —— 与 9.1.2.2 手工单的
 * {@code trade/sales-orders} <b>并列</b>（那个前缀的集合读与 capabilities 已被 9.1.2.2 占用），
 * 不是覆盖。
 */

const base = 'trade/sales-orders/list';

export interface SalesOrderListParams {
  orderSource?: string;
  documentStatus?: string;
  currency?: string;
  buyerKeyword?: string;
  skuId?: string;
  orderedFrom?: string;
  orderedTo?: string;
  minAmount?: string;
  maxAmount?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function salesOrderListQuery(params: SalesOrderListParams): string {
  const parts: string[] = [];
  if (params.orderSource) parts.push(`orderSource=${encodeURIComponent(params.orderSource)}`);
  if (params.documentStatus) {
    parts.push(`documentStatus=${encodeURIComponent(params.documentStatus)}`);
  }
  if (params.currency) parts.push(`currency=${encodeURIComponent(params.currency)}`);
  if (params.buyerKeyword?.trim()) {
    parts.push(`buyerKeyword=${encodeURIComponent(params.buyerKeyword.trim())}`);
  }
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  // 时间按 ISO-8601 带时区串传：后端绑 ?::timestamptz，这里丢时区会偏。
  if (params.orderedFrom) parts.push(`orderedFrom=${encodeURIComponent(params.orderedFrom)}`);
  if (params.orderedTo) parts.push(`orderedTo=${encodeURIComponent(params.orderedTo)}`);
  if (params.minAmount) parts.push(`minAmount=${encodeURIComponent(params.minAmount)}`);
  if (params.maxAmount) parts.push(`maxAmount=${encodeURIComponent(params.maxAmount)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchOrderListCapabilities() {
  return api<SalesOrderListCapabilities>(`${base}/capabilities`);
}

export function fetchOrderListFilters() {
  return api<SalesOrderListFilterOptions>(`${base}/filters`);
}

export function fetchOrderList(params: SalesOrderListParams) {
  return api<SalesOrderListPage>(`${base}${salesOrderListQuery(params)}`);
}

// ================================================================ 展示口径

export const documentStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '已提交待审',
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

export const sortLabels: Record<string, string> = {
  ORDERED_AT_DESC: '下单时间，最新在前',
  ORDERED_AT_ASC: '下单时间，最早在前',
  AMOUNT_DESC: '订单金额，由高到低',
  AMOUNT_ASC: '订单金额，由低到高',
};

/**
 * 不可得维度的单元格：显示「—」并带上可解释的原因。
 *
 * <p>返回 null 时显示破折号而不是空字符串 —— 空单元格在表格里会被读成
 * 「这个字段没填」，而真实含义是「这段能力还没接入」。tooltip 里带上 ownerModule，
 * 用户点一下就知道该找哪个模块，而不是以为系统坏了。
 */
export function unavailableCell(
  value: string | null | undefined,
  dimension?: { label: string; ownerModule: string; reason: string },
): { text: string; title: string } {
  if (value !== null && value !== undefined && value !== '') {
    return { text: value, title: '' };
  }
  return {
    text: '—',
    title: dimension
      ? `${dimension.label}尚不可得：归属 ${dimension.ownerModule}。${dimension.reason}`
      : '该维度尚不可得（见「能力」面板里的归属模块与原因）',
  };
}

/**
 * 批量操作一律禁用：本模块是读投影，不提供写端点。
 *
 * <p>返回归属模块与前置条件，让界面能讲清「什么时候它会变成可用」，
 * 而不是给一个点下去 404 的按钮。
 */
export function bulkOperationDisabled(op: {
  code: string;
  label: string;
  available: boolean;
  ownerModule: string;
  precondition: string;
  reason: string;
}): { disabled: true; title: string } {
  return {
    disabled: true,
    title: op.available
      ? `${op.label}可用`
      : `${op.label}尚未交付：归属 ${op.ownerModule}。前置条件：${op.precondition}。${op.reason}`,
  };
}

/** 金额格式化：后端返回的是字符串（number 被 codegen 展开），这里只做展示格式化。 */
export function formatAmount(value: string | number | null | undefined, currency?: string): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(n)) return String(value);
  return currency ? `${n.toFixed(2)} ${currency}` : n.toFixed(2);
}

/**
 * 汇总条文案：订单数 + 分币种金额。
 *
 * <p><b>刻意不把不同币种加成一个总额</b>：USD 100 + EUR 100 不是 200。
 */
export function summaryLine(
  total: number,
  amounts: SalesOrderListCurrencyAmount[] | undefined,
): string {
  const count = `${total} 张订单`;
  if (!amounts || amounts.length === 0) return count;
  return `${count} · ${amounts
    .map((a) => `${formatAmount(a.amount, a.currency)}（${a.orderCount} 张）`)
    .join(' / ')}`;
}

/** 时间展示：带时区的 ISO 串转本地可读格式，解析失败时原样返回而不是显示 Invalid Date。 */
export function formatTime(value: string | null | undefined): string {
  if (!value) return '—';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}`;
}

/** 「待处理数」文案：口径未定义时如实说明，不拿 DRAFT 冒充。 */
export function pendingCountText(
  pendingCount: number | null | undefined,
  owner: string,
): string {
  if (pendingCount === null || pendingCount === undefined) {
    return `待处理数暂无口径（归属 ${owner}）`;
  }
  return `待处理 ${pendingCount} 张`;
}

/** 表格列定义。六个不可得维度也在列里 —— 缺席比「显示成别的含义」更容易被误读。 */
export const listColumns: { key: string; label: string; unavailable?: boolean }[] = [
  { key: 'orderNo', label: '订单号' },
  { key: 'orderSource', label: '来源' },
  { key: 'buyerName', label: '买家' },
  { key: 'lineCount', label: 'SKU 行数' },
  { key: 'grandTotalAmount', label: '总金额/币种' },
  { key: 'documentStatus', label: '单据状态' },
  { key: 'orderedAt', label: '下单时间' },
  { key: 'platformStatus', label: '平台状态', unavailable: true },
  { key: 'paymentStatus', label: '支付状态', unavailable: true },
  { key: 'fulfillmentStatus', label: '履约状态', unavailable: true },
  { key: 'shippingWarehouse', label: '发货仓', unavailable: true },
  { key: 'aftersalesStatus', label: '售后状态', unavailable: true },
];
