import type {
  FinanceReceiptAllocationInput,
  FinanceReceiptAllocationRow,
  FinanceReceiptBalance,
  FinanceReceiptCapabilities,
  FinanceReceiptCurrencySummary,
  FinanceReceiptDetail,
  FinanceReceiptImportInput,
  FinanceReceiptImportResult,
  FinanceReceiptInput,
  FinanceReceiptOptions,
  FinanceReceiptPage,
  FinanceReceiptRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.2.1.1 收款单的前端模型。
 *
 * <p>蓝图 12-finance.md：「登记与跟踪资金收入：平台回款、银行到账、PayPal/Stripe/PingPong 等第三方支付到账、
 * 其他收入；记录资金账户、金额币种、到账时间与来源。」输出：「收款单 + 未核销余额。」
 * 加粗句：「<b>一笔收款可核销多笔应收</b>（合并回款），也可能是预收。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>金额只原样展示</b>：汇总与余额都由**服务端**算好，页面**不做任何前端算术**
 *       （USD 与 EUR 加起来不是任何一个金额）。</li>
 *   <li><b>币种分开</b>：汇总与未核销余额一律按币种分组渲染。</li>
 *   <li><b>上游没交付的事就说没交付</b>：平台回款（上游 12.3.1.1 结算未交付）**不提供**建单入口；
 *       受管资金账户（上游 12.7.1.1 未交付）**不提供**选择器 —— 页面只能填自由文本标识，
 *       并明确说明它<b>不是</b>受管资金账户。</li>
 *   <li><b>预留 ≠ 核销</b>：核销（12.2.1.2）<b>未交付</b>，页面只显示预留关系与未核销余额，
 *       <b>不提供</b>任何「标记已核销」的按钮。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有收款单」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code finance/receipts}，与同服务的 12.1.1.1（{@code finance/receivables}）
 * 以及卷 12 的其余模块刻意不共用前缀。
 */

const base = 'finance/receipts';

/**
 * 筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface ReceiptParams {
  status?: string | undefined;
  source?: string | undefined;
  sourceSystem?: string | undefined;
  currency?: string | undefined;
  payerType?: string | undefined;
  advanceOnly?: boolean | undefined;
  receivedFrom?: string | undefined;
  receivedTo?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

function query(params: Record<string, string | number | boolean | undefined>): string {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    if (typeof value === 'string' && value === '') continue;
    parts.push(`${key}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 端点

/** 能力声明：哪些上游真接通、哪些还没交付、归谁。 */
export function fetchReceiptCapabilities() {
  return api<FinanceReceiptCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（全部来自真实数据）。 */
export function fetchReceiptOptions() {
  return api<FinanceReceiptOptions>(`${base}/options`);
}

/** 收款单列表：真分页 + 真筛选 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。 */
export function fetchReceipts(params: ReceiptParams = {}) {
  return api<FinanceReceiptPage>(`${base}${query({ ...params })}`);
}

/** 收款单详情：单头 + 预留核销关系 + 事件流。 */
export function fetchReceipt(id: string) {
  return api<FinanceReceiptDetail>(`${base}/${encodeURIComponent(id)}`);
}

/** 未核销余额（按币种分开）。 */
export function fetchReceiptBalance() {
  return api<FinanceReceiptBalance>(`${base}/balance`);
}

/**
 * 手工登记一张收款单。
 *
 * <p>⚠️ 「平台回款」当前**不可产生**（上游 12.3.1.1 结算未交付）：后端会拒绝并写明归属模块，
 * 页面也不提供这个入口。
 */
export function createReceipt(input: FinanceReceiptInput) {
  return api<FinanceReceiptRow>(`${base}`, 'POST', input);
}

/**
 * 到账流水导入（CSV / JSON）。
 *
 * <p>⚠️ 文件级错误后端**整体失败**（400）；行级错误只跳过那一行并逐条写进 warnings ——
 * 页面必须把 warnings 原样显示出来，绝不能让它看起来像「跑过了但一条都没有」。
 */
export function importReceipts(input: FinanceReceiptImportInput) {
  return api<FinanceReceiptImportResult>(`${base}/import`, 'POST', input);
}

/**
 * 登记一笔预留核销：这笔钱**打算**核销到某一笔应收上。
 *
 * <p>⚠️ **预留不是核销**：它不减少未核销余额；实际核销由 12.2.1.2 承担。
 */
export function reserveReceiptAllocation(receiptId: string, input: FinanceReceiptAllocationInput) {
  return api<FinanceReceiptAllocationRow>(
    `${base}/${encodeURIComponent(receiptId)}/allocations`,
    'POST',
    input,
  );
}

/** 释放一笔预留核销（**不是**核销撤销 —— 核销撤销属 12.2.1.2）。 */
export function releaseReceiptAllocation(receiptId: string, allocationId: string) {
  return api<FinanceReceiptAllocationRow>(
    `${base}/${encodeURIComponent(receiptId)}/allocations/${encodeURIComponent(allocationId)}/release`,
    'POST',
  );
}

/** 作废（红冲的等价物）：终态，整单冻结。 */
export function voidReceipt(id: string, reason: string) {
  return api<FinanceReceiptRow>(`${base}/${encodeURIComponent(id)}/void`, 'POST', { reason });
}

// ================================================================ 可读形态

/** 收款来源的可读名。 */
export function receiptSourceLabel(source: string): string {
  switch (source) {
    case 'PLATFORM_SETTLEMENT':
      return '平台回款';
    case 'BANK_TRANSFER':
      return '银行到账';
    case 'PAYMENT_GATEWAY':
      return '第三方支付到账';
    case 'MANUAL':
      return '人工登记';
    case 'OTHER':
      return '其他收入';
    default:
      return source;
  }
}

/**
 * 来源系统的可读名。
 *
 * <p>⚠️ 「人工登记」与「从流水导入」必须能分开看 —— 运营要知道这笔钱是谁给的。
 */
export function receiptSourceSystemLabel(sourceSystem: string): string {
  switch (sourceSystem) {
    case 'manual':
      return '人工登记';
    case 'bank':
      return '银行流水导入';
    case 'paypal':
      return 'PayPal 流水导入';
    case 'stripe':
      return 'Stripe 流水导入';
    case 'pingpong':
      return 'PingPong 流水导入';
    default:
      return sourceSystem;
  }
}

/** 收款状态的可读名。 */
export function receiptStatusLabel(status: string): string {
  switch (status) {
    case 'RECORDED':
      return '已登记';
    case 'VOID':
      return '已作废';
    default:
      return status;
  }
}

/** 收款状态的可读色调。 */
export function receiptStatusTone(status: string): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'RECORDED':
      return 'success';
    case 'VOID':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 付款方类型的可读名。 */
export function payerTypeLabel(type: string): string {
  switch (type) {
    case 'PLATFORM':
      return '平台';
    case 'CUSTOMER':
      return '客户';
    case 'PARTNER':
      return '合作方';
    case 'OTHER':
      return '其他';
    default:
      return type;
  }
}

/** 预留核销状态的可读名。 */
export function allocationStatusLabel(status: string): string {
  switch (status) {
    case 'RESERVED':
      return '已预留';
    case 'RELEASED':
      return '已释放';
    default:
      return status;
  }
}

/** 事件类型的可读名。 */
export function receiptEventLabel(eventType: string): string {
  switch (eventType) {
    case 'CREATED':
      return '手工登记';
    case 'IMPORTED':
      return '到账流水导入';
    case 'RESERVED':
      return '预留核销';
    case 'RELEASED':
      return '释放预留';
    case 'VOIDED':
      return '作废';
    default:
      return eventType;
  }
}

/**
 * 金额的可读形态。
 *
 * <p>⚠️ **不在前端做算术**：数值原样透传（只做显示格式化）。
 * 后端给 {@code null} 时显示「未登记」而不是 0。
 */
export function formatReceiptAmount(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '未登记';
  return amount.toFixed(4);
}

/** 金额 + 币种的可读形态。 */
export function formatReceiptMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '未登记';
  return `${amount.toFixed(4)} ${currency ?? ''}`.trim();
}

/**
 * 未核销余额的可读形态。
 *
 * <p>⚠️ 核销（12.2.1.2）未交付 ⇒ 未核销余额**恒等于收款金额**。
 * 这里只把服务端算好的值显示出来，**不**在前端用「收款 − 预留」自己算一个。
 */
export function formatUnsettled(row: FinanceReceiptCurrencySummary): string {
  return formatReceiptMoney(row.unsettledAmount, row.currency);
}

/**
 * 到账时间的可读形态。
 *
 * <p>⚠️ 只做显示格式化，**不**在前端做时区换算 ——
 * 后端存的是 timestamptz，展示用浏览器本地时区即可（换算只是显示，不影响账）。
 */
export function formatReceivedAt(value: string | null | undefined): string {
  if (!value) return '未登记';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/**
 * 资金账户标识的可读形态。
 *
 * <p>⚠️ **它不是受管资金账户**：12.7.1.1 账户管理未交付，
 * 这里填的只是一段自由文本（备注式标识），没有与任何账户主数据关联。
 */
export function fundAccountLabelHint(row: FinanceReceiptRow): string {
  if (!row.fundAccountLabel) return '未登记（受管资金账户 12.7.1.1 未交付）';
  return `${row.fundAccountLabel}（自由文本标识，不是受管资金账户）`;
}
