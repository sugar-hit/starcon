import type {
  FinanceTransactionBatchInput,
  FinanceTransactionCapabilities,
  FinanceTransactionImportInput,
  FinanceTransactionImportResult,
  FinanceTransactionInput,
  FinanceTransactionOptions,
  FinanceTransactionPage,
  FinanceTransactionReconciliation,
  FinanceTransactionRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.3.1.2 交易明细的前端模型。
 *
 * <p>蓝图 12-finance.md：「管理结算单下的逐笔明细：订单销售、退款、费用（佣金/仓储/配送/广告）、赔付、调整、代扣税，
 * 保留外部流水号与币种；<b>允许无订单行的费用类明细</b>。」输入：「平台交易报告解析结果；<code>idempotency_key</code>。」
 * 输出：「明细行 + <b>汇总校验（明细合计 = 结算单汇总）</b>。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>金额只原样展示</b>：明细的归一后贡献与结算单的合计都由**服务端**算好，
 *       页面**不做任何前端算术**（USD 与 EUR 加起来不是任何一个金额）。</li>
 *   <li><b>符号看得懂</b>：金额是「对结算金额的贡献」（正=增加、负=减少），
 *       页面必须把这个含义写在界面上，否则「退款 −100」会被读成「退款了 100 块」。</li>
 *   <li><b>订单行可以为空，而且这是正常状态</b>：佣金、仓储费、月度调整天然没有订单行
 *       （蓝图加粗句「不强制每行都有订单」）。页面**不**把「没有订单号」渲染成异常，
 *       只如实显示「—」。</li>
 *   <li><b>上游没交付的事就说没交付</b>：平台交易报告自动同步（上游「卷 02 同步」未交付）
 *       **不提供**入口；自动对账任务（12.3.2.1）与利润（13）**未交付**，
 *       页面只显示**汇总校验**，不提供任何「跑对账 / 看利润」的入口。</li>
 *   <li><b>导入的告警原样显示</b>：文件级错误整体失败，行级错误只跳过那一行 ——
 *       页面必须把 warnings 一条条显示出来，绝不能让它看起来像「跑过了但一条都没有」。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有交易明细」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code finance/transactions}，与同服务的 12.1.1.1（{@code finance/receivables}）、
 * 12.2.1.1（{@code finance/receipts}）、12.3.1.1（{@code finance/settlement}）**并列**而非嵌套。
 */

/** 后端命名空间（与 api-proxy 白名单里的首 token 一致）。 */
const base = 'finance/transactions';

/** 交易类型词表（与后端 `FinanceTransactionType` 一一对应）。 */
export const TRANSACTION_TYPES = [
  'ORDER_SALES',
  'REFUND',
  'COMMISSION',
  'FBA_FEE',
  'STORAGE_FEE',
  'SHIPPING_FEE',
  'ADVERTISING',
  'COMPENSATION',
  'ADJUSTMENT',
  'WITHHOLDING_TAX',
] as const;

export type TransactionType = (typeof TRANSACTION_TYPES)[number];

/**
 * 筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface TransactionParams {
  settlementId?: string | undefined;
  shopId?: string | undefined;
  transactionType?: string | undefined;
  category?: string | undefined;
  currency?: string | undefined;
  source?: string | undefined;
  platformCode?: string | undefined;
  occurredFrom?: string | undefined;
  occurredTo?: string | undefined;
  hasOrder?: boolean | undefined;
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
export function fetchTransactionCapabilities() {
  return api<FinanceTransactionCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（全部来自真实数据）。 */
export function fetchTransactionOptions() {
  return api<FinanceTransactionOptions>(`${base}/options`);
}

/** 交易明细列表：真分页 + 真筛选 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。 */
export function fetchTransactions(params: TransactionParams = {}) {
  return api<FinanceTransactionPage>(`${base}${query({ ...params })}`);
}

/** 一条明细的详情：明细本身 + 所属结算单的当前状态与汇总项。 */
export function fetchTransaction(id: string) {
  return api<FinanceTransactionRow & { settlementStatus: string }>(
    `${base}/${encodeURIComponent(id)}`,
  );
}

/**
 * 汇总校验：<b>明细合计 = 结算单汇总</b>。
 *
 * <p>⚠️ 这是**实时算出来**的核对结果，页面原样显示，不在前端重算。
 */
export function fetchTransactionReconciliation(settlementId: string) {
  return api<FinanceTransactionReconciliation>(
    `${base}/reconciliation${query({ settlementId })}`,
  );
}

/**
 * 登记一条交易明细。
 *
 * <p>⚠️ 「平台交易报告同步」当前**不可产生**（上游「卷 02 同步」未交付）：后端会拒绝并写明归属模块，
 * 页面也不提供这个入口。
 */
export function registerTransaction(input: FinanceTransactionInput) {
  return api<FinanceTransactionRow>(`${base}`, 'POST', input);
}

/** 批量登记交易明细（同一张结算单）。 */
export function registerTransactionBatch(input: FinanceTransactionBatchInput) {
  return api<FinanceTransactionRow[]>(`${base}/batch`, 'POST', input);
}

/**
 * 平台交易报告导入（CSV / JSON）。
 *
 * <p>⚠️ 文件级错误后端**整体失败**（400）；行级错误只跳过那一行并逐条写进 warnings ——
 * 页面必须把 warnings 原样显示出来。
 */
export function importTransactionReport(input: FinanceTransactionImportInput) {
  return api<FinanceTransactionImportResult>(`${base}/import`, 'POST', input);
}

// ================================================================ 可读形态

/** 交易类型的可读名。 */
export function transactionTypeLabel(type: string): string {
  switch (type) {
    case 'ORDER_SALES':
      return '订单销售';
    case 'REFUND':
      return '退款';
    case 'COMMISSION':
      return '佣金';
    case 'FBA_FEE':
      return 'FBA 费用';
    case 'STORAGE_FEE':
      return '仓储费';
    case 'SHIPPING_FEE':
      return '配送费';
    case 'ADVERTISING':
      return '广告费';
    case 'COMPENSATION':
      return '赔付';
    case 'ADJUSTMENT':
      return '其他调整';
    case 'WITHHOLDING_TAX':
      return '代扣税';
    default:
      return type;
  }
}

/** 结算汇总类别的可读名（与 12.3.1.1 同一份词表）。 */
export function transactionCategoryLabel(category: string): string {
  switch (category) {
    case 'SALES':
      return '销售额';
    case 'REFUND':
      return '退款';
    case 'COMMISSION':
      return '佣金';
    case 'FBA_FEE':
      return 'FBA 费用';
    case 'ADVERTISING':
      return '广告费';
    case 'COMPENSATION':
      return '赔付';
    case 'ADJUSTMENT':
      return '其他调整';
    case 'TAX':
      return '代扣税';
    default:
      return category;
  }
}

/**
 * 明细来源的可读名。
 *
 * <p>⚠️ 「平台交易报告同步」在界面上必须带着「不可用」的提示 ——
 * 上游「卷 02 同步」没有这个端点，放一个能点的入口就是骗人。
 */
export function transactionSourceLabel(source: string): string {
  switch (source) {
    case 'MANUAL_UPLOAD':
      return '人工上传交易报告';
    case 'PLATFORM_SYNC':
      return '平台交易报告同步（当前不可用）';
    case 'MANUAL':
      return '人工登记';
    default:
      return source;
  }
}

/** 结算状态的可读名（用于展示所属结算单）。 */
export function transactionSettlementStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '待到账';
    case 'PARTIAL':
      return '部分到账';
    case 'SETTLED':
      return '已到账';
    case 'VOID':
      return '已作废';
    case 'SUPERSEDED':
      return '已被新版本取代';
    default:
      return status;
  }
}

/**
 * 金额（明细贡献）的可读形态。
 *
 * <p>⚠️ **不在前端做算术**：数值原样透传（只做显示格式化）。
 * 后端给 `null` 时显示「未登记」而不是 0。
 */
export function formatTransactionAmount(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '未登记';
  return amount.toFixed(4);
}

/** 金额 + 币种的可读形态。 */
export function formatTransactionMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '未登记';
  return `${amount.toFixed(4)} ${currency ?? ''}`.trim();
}

/**
 * 明细贡献的可读形态（带符号说明）。
 *
 * <p>⚠️ 明细金额是**对结算金额的贡献**（正=增加、负=减少）。
 * 页面必须把这个含义写出来，否则「退款 −100」会被读成「退款了 100 块」。
 */
export function formatTransactionContribution(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '未登记';
  const sign = amount > 0 ? '+' : '';
  return `${sign}${amount.toFixed(4)}`;
}

/**
 * 日期（账单行日期、结算周期）的可读形态。
 *
 * <p>⚠️ **日历日不做时区换算**：`'YYYY-MM-DD'` 交给 {@code new Date()} 会被当成 **UTC 午夜**，
 * 在 UTC 以西的时区渲染出来就是**前一天**。日历日就按原样显示。
 */
export function formatTransactionDate(value: string | null | undefined): string {
  if (!value) return '未登记';
  const calendarDay = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (calendarDay) return `${calendarDay[1]}-${calendarDay[2]}-${calendarDay[3]}`;
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleDateString('zh-CN');
}

/**
 * 订单引用的可读形态。
 *
 * <p>⚠️ 「没有订单号」是**正常状态**（蓝图加粗句「允许无订单行的费用类明细」）：
 * 页面显示「—」，**不**渲染成红色异常。
 */
export function formatOrderReference(value: string | null | undefined): string {
  if (!value) return '—';
  return value;
}

/**
 * 汇总校验结论的可读形态。
 *
 * <p>⚠️ 「明细合计 = 汇总合计 = 结算金额」是**金额层面**的结论；
 * 「分类口径 / 笔数 / 币种 / 符号 / 周期」是**口径层面**的结论 ——
 * 两件事分开说，页面不能把「金额平了」当成「账对了」。
 */
export function reconciliationHint(
  check: FinanceTransactionReconciliation,
): string {
  return [
    `明细合计 ${formatTransactionMoney(check.lineTotal, check.currency)}`,
    `汇总合计 ${formatTransactionMoney(check.summaryTotal, check.currency)}`,
    `结算金额 ${formatTransactionMoney(check.settlementAmount, check.currency)}`,
    check.balanced ? '三者相等' : '三者**不相等**（明细与结算单对不上）',
  ].join('；');
}
