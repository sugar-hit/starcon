import type {
  PlatformOrderSyncBatchDetail,
  PlatformOrderSyncBatchPage,
  PlatformOrderSyncCapabilities,
  PlatformOrderSyncExceptionPage,
  PlatformOrderSyncMappingInput,
  PlatformOrderSyncMappingPage,
  PlatformOrderSyncMappingSyncInput,
  PlatformOrderSyncMappingSyncResult,
  PlatformOrderSyncOrderDetail,
  PlatformOrderSyncOrderPage,
  PlatformOrderSyncPollInput,
  PlatformOrderSyncResult,
  PlatformOrderSyncWebhookInput,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 9.1.2.1 平台订单同步前端模型。
 *
 * <p>蓝图：「接收并落库平台订单（webhook 实时 + 轮询补拉），做字段解析、状态归一、
 * 金额与税费还原、地址快照，形成统一订单模型」「**同一外部订单号在同一来源范围内唯一**，
 * 重复事件走 inbox 去重，绝不产生第二张订单」。
 *
 * <p><b>界面纪律</b>（与后端逐条对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>归一状态与单据状态是两个字段，不许混用</b>：页面上「平台状态」列显示的是
 *       归一后的生命周期（{@link normalizedStatusLabels}），「单据状态」列显示的是
 *       有没有进审单队列（{@link documentStatusLabels}）。两者口径不同，
 *       合成一列就会出现「这单到底在哪个阶段」答不出来。
 *   <li><b>重复不是失败</b>：重复事件（inbox 去重挡下的）在批次里单列一格，
 *       不混进「失败」—— 把重复算成失败会让运营以为平台侧出了问题。
 *   <li><b>拒收必须看得见原文</b>：异常明细列出错误码 + 人话解释，
 *       错误码是机器可判定的（{@link errorCodeLabels}），界面据此给处置建议。
 *   <li><b>金额不跨币种相加</b>：列表里的金额一律带币种显示（{@link money}），
 *       汇总条只报**笔数**不报合计 —— USD 与 EUR 加成一个数不是任何金额。
 *   <li><b>「没有映射」与「映射了但没同步」要分得开</b>：能力声明里的
 *       {@link PlatformOrderSyncCapabilities.enumMappingSyncAvailable} 如实报出
 *       2.1.2.1 的同步入口是否可用，不可用时页面写清归属模块，而不是给一个点下去 404 的按钮。
 *   <li>加载 / 空 / 错误三态齐全。
 * </ul>
 */

const base = 'trade/platform-orders';

// ================================================================ 接口调用

export type PlatformOrderSyncBatchParams = {
  platformCode?: string;
  shopCode?: string;
  status?: string;
  triggerType?: string;
  page?: number;
  size?: number;
};

export type PlatformOrderSyncOrderParams = {
  platformCode?: string;
  shopCode?: string;
  normalizedStatus?: string;
  keyword?: string;
  page?: number;
  size?: number;
};

export type PlatformOrderSyncExceptionParams = {
  platformCode?: string;
  errorCode?: string;
  externalOrderNo?: string;
  page?: number;
  size?: number;
};

export type PlatformOrderSyncMappingParams = {
  platformCode?: string;
  enumKind?: string;
  mappingSource?: string;
  page?: number;
  size?: number;
};

function query(params: Record<string, string | number | undefined>): string {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '') continue;
    parts.push(`${key}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchCapabilities() {
  return api<PlatformOrderSyncCapabilities>(`${base}/capabilities`);
}

export function fetchBatches(params: PlatformOrderSyncBatchParams) {
  return api<PlatformOrderSyncBatchPage>(`${base}/batches${query(params)}`);
}

export function fetchBatch(batchId: string) {
  return api<PlatformOrderSyncBatchDetail>(`${base}/batches/${encodeURIComponent(batchId)}`);
}

export function fetchOrders(params: PlatformOrderSyncOrderParams) {
  return api<PlatformOrderSyncOrderPage>(`${base}/orders${query(params)}`);
}

export function fetchOrder(orderId: string) {
  return api<PlatformOrderSyncOrderDetail>(`${base}/orders/${encodeURIComponent(orderId)}`);
}

export function fetchExceptions(params: PlatformOrderSyncExceptionParams) {
  return api<PlatformOrderSyncExceptionPage>(`${base}/exceptions${query(params)}`);
}

export function fetchMappings(params: PlatformOrderSyncMappingParams) {
  return api<PlatformOrderSyncMappingPage>(`${base}/mappings${query(params)}`);
}

export function registerMapping(input: PlatformOrderSyncMappingInput) {
  return api(`${base}/mappings`, 'POST', input);
}

export function syncMappings(input: PlatformOrderSyncMappingSyncInput) {
  return api<PlatformOrderSyncMappingSyncResult>(`${base}/mappings/sync`, 'POST', input);
}

export function submitWebhook(input: PlatformOrderSyncWebhookInput) {
  return api<PlatformOrderSyncResult>(`${base}/webhook`, 'POST', input);
}

export function submitPoll(input: PlatformOrderSyncPollInput) {
  return api<PlatformOrderSyncResult>(`${base}/poll`, 'POST', input);
}

// ================================================================ 展示口径

/** 归一后的平台生命周期状态（DB {@code platform_order.normalized_status}）。 */
export const normalizedStatusLabels: Record<string, string> = {
  PENDING_CONFIRMATION: '待确认',
  CONFIRMED: '已确认',
  IN_FULFILLMENT: '履约中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  ON_HOLD: '平台挂起',
};

/** 单据状态（有没有进 9.2 审单队列）—— 与上面的归一状态**不是**同一个轴。 */
export const documentStatusLabels: Record<string, string> = {
  DRAFT: '草稿（未进审单队列）',
  SUBMITTED: '已提交审单队列',
};

export const batchStatusLabels: Record<string, string> = {
  RUNNING: '进行中',
  SUCCEEDED: '全部落地',
  PARTIAL: '部分落地',
  FAILED: '一条未落地',
};

export const triggerTypeLabels: Record<string, string> = {
  WEBHOOK: 'webhook 实时接入',
  POLL: '轮询补拉',
};

export const eventTypeLabels: Record<string, string> = {
  ORDER_CREATED: '订单创建',
  ORDER_UPDATED: '订单更新',
  ORDER_CANCELLED: '订单取消',
};

export const eventOutcomeLabels: Record<string, string> = {
  RECEIVED: '已受理',
  APPLIED: '已落地',
  DUPLICATE: '重复（inbox 去重）',
  REJECTED: '已拒收',
};

export const errorCodeLabels: Record<string, string> = {
  UNMAPPED_PLATFORM_STATUS: '平台状态未映射',
  AMOUNT_MISMATCH: '金额还原对不上',
  INVALID_PAYLOAD: '事件字段非法',
  ORDER_SNAPSHOT_FROZEN: '订单已提交，内容改不动',
  ORDER_CONTENT_DIVERGED: '平台改了订单内容',
  EXTERNAL_ORDER_NO_COLLISION: '外部订单号在本平台已存在',
  MAPPING_SYNC_FAILED: '映射同步失败',
  PLATFORM_CALL_FAILED: '平台接口调用失败',
};

export const enumKindLabels: Record<string, string> = {
  ORDER_STATUS: '订单状态',
  PAYMENT_METHOD: '支付方式',
  COMPONENT_TYPE: '组件类型',
};

export const mappingSourceLabels: Record<string, string> = {
  TENANT_MAINTAINED: '租户自维护',
  MODULE_2_ENUM_MAPPING: '2.1.2.1 同步',
};

export const componentTypeLabels: Record<string, string> = {
  ITEM: '单品',
  BUNDLE: '套装',
  GIFT: '赠品',
  PACKAGING: '包装',
  OTHER: '其他',
};

export const componentSourceLabels: Record<string, string> = {
  LINE: '订单行本身',
  COMPONENT: '显式组件',
};

export const orderSourceLabels: Record<string, string> = {
  PLATFORM: '平台订单',
  AMAZON: 'Amazon',
  SHOPIFY: 'Shopify',
  EBAY: 'eBay',
  TIKTOK_SHOP: 'TikTok Shop',
  ALIBABA_1688: '1688',
};

export function normalizedStatusLabel(status: string): string {
  return normalizedStatusLabels[status] ?? status;
}

export function documentStatusLabel(status: string): string {
  return documentStatusLabels[status] ?? status;
}

export function batchStatusLabel(status: string): string {
  return batchStatusLabels[status] ?? status;
}

export function triggerTypeLabel(trigger: string): string {
  return triggerTypeLabels[trigger] ?? trigger;
}

export function eventOutcomeLabel(outcome: string): string {
  return eventOutcomeLabels[outcome] ?? outcome;
}

export function errorCodeLabel(code: string): string {
  return errorCodeLabels[code] ?? code;
}

export function componentTypeLabel(type: string): string {
  return componentTypeLabels[type] ?? type;
}

export function componentSourceLabel(source: string): string {
  return componentSourceLabels[source] ?? source;
}

export function orderSourceLabel(source: string): string {
  return orderSourceLabels[source] ?? source;
}

/**
 * 状态徽章的视觉分级。
 *
 * <p>只按「这单还在动 / 已经停下 / 出问题了」分三档 ——
 * 不为了凑颜色去给不存在的状态编一个。
 */
export function statusTone(status: string): 'neutral' | 'primary' | 'danger' {
  if (status === 'CANCELLED' || status === 'ON_HOLD') return 'danger';
  if (status === 'IN_FULFILLMENT' || status === 'COMPLETED') return 'primary';
  return 'neutral';
}

/** 批次的视觉分级：失败最重，部分落地次之。 */
export function batchTone(status: string): 'neutral' | 'primary' | 'danger' {
  if (status === 'FAILED') return 'danger';
  if (status === 'PARTIAL') return 'danger';
  if (status === 'SUCCEEDED') return 'primary';
  return 'neutral';
}

/**
 * 金额格式化：始终带币种。
 *
 * <p>刻意**不**提供「跨币种合计」—— USD 与 EUR 加成一个数不是任何金额。
 */
export function money(value: number | string | null | undefined, currency?: string): string {
  if (value === null || value === undefined || value === '') return '—';
  const amount = Number(value);
  if (Number.isNaN(amount)) return '—';
  const formatted = amount.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 4,
  });
  return currency ? `${formatted} ${currency}` : formatted;
}

/** 数量格式化：去掉无意义的尾零，6 位小数的零头也显示出来。 */
export function fmt(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const amount = Number(value);
  if (Number.isNaN(amount)) return '—';
  return Number(amount.toFixed(6)).toLocaleString('zh-CN', { maximumFractionDigits: 6 });
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

// ================================================================ 事件负载

/**
 * 平台事件的**样例负载**（供「手工重放平台事件」用）。
 *
 * <p>刻意做成一个**可编辑的 JSON 模板**而不是一堆表单字段：
 * 平台事件是平台自己给的东西，运营手里通常已经有原始报文；
 * 让���把报文粘进来重放，比让人照着界面重新拼一遍更接近真实场景，
 * 而且拼错字段名时后端会**明确拒收**（而不是半落地）。
 *
 * <p>模板里的字段名与后端 {@code PlatformOrderSyncEventInput} 逐字一致 ——
 * 改了一边另一边就会 400，不会「静默忽略」。
 */
export const EVENT_TEMPLATE = {
  eventType: 'ORDER_CREATED',
  externalOrderNo: 'AMZ-2026-0001',
  platformStatus: 'Shipped',
  platformEventVersion: 1,
  platformPlacedAt: '2026-03-01T08:00:00Z',
  platformUpdatedAt: '2026-03-01T08:00:00Z',
  currency: 'USD',
  paymentMethod: 'PREPAID',
  buyerId: 'BUYER-1',
  buyerName: 'Acme Trading Co.',
  buyerType: 'ORGANIZATION',
  discountAmount: '0.00',
  taxAmount: '0.00',
  shippingAmount: '0.00',
  otherAmount: '0.00',
  platformGrandTotalAmount: '21.00',
  shippingAddress: {
    recipientName: 'Jane Doe',
    recipientPhone: '+1-555-0100',
    recipientEmail: 'jane@acme.example',
    companyName: 'Acme Trading Co.',
    countryCode: 'US',
    province: 'CA',
    city: 'Los Angeles',
    district: '',
    postcode: '90001',
    addressLine1: '100 Main St',
    addressLine2: '',
    taxId: '',
    note: '',
  },
  lines: [
    {
      skuId: '11111111-1111-1111-1111-111111111111',
      skuCode: 'SKU-1',
      skuName: '商品 1',
      specSnapshot: '蓝色 / M',
      skuMappingVersion: 'map-v1',
      quantity: '1',
      unitPrice: '21.00',
      discountAmount: '0.00',
      taxAmount: '0.00',
      note: '',
      attributes: [],
      components: [],
    },
  ],
};

/** 解析手工重放面板里的 JSON 报文；解析失败时给出**能定位**的提示而不是抛异常。 */
export function parseEventPayload(
  raw: string,
): { ok: true; value: unknown } | { ok: false; message: string } {
  const text = raw.trim();
  if (!text) return { ok: false, message: '事件报文不能为空' };
  try {
    return { ok: true, value: JSON.parse(text) };
  } catch (e) {
    return { ok: false, message: `事件报文不是合法 JSON：${(e as Error).message}` };
  }
}

/**
 * 一条平台事件的**平台侧应付总额**与本侧还原口径的差额提示。
 *
 * <p>容差 0.01（一分钱）：超过就不是舍入而是口径不一致，后端会拒收。
 */
export const AMOUNT_TOLERANCE = 0.01;

export function amountMismatch(
  platformTotal: number | null | undefined,
  restored: number | null | undefined,
): boolean {
  if (platformTotal === null || platformTotal === undefined) return false;
  if (restored === null || restored === undefined) return false;
  return Math.abs(Number(platformTotal) - Number(restored)) > AMOUNT_TOLERANCE;
}
