import type {
  FinancePaymentApprovalRecordRow,
  FinancePaymentExchangeRateView,
  FinancePaymentOrderCapabilities,
  FinancePaymentOrderDetail,
  FinancePaymentOrderEventRow,
  FinancePaymentOrderInput,
  FinancePaymentOrderInstallmentInput,
  FinancePaymentOrderInstallmentRow,
  FinancePaymentOrderLineInput,
  FinancePaymentOrderLineRow,
  FinancePaymentOrderOptions,
  FinancePaymentOrderPage,
  FinancePaymentOrderRow,
  FinancePaymentOrderSubmitResult,
  FinancePaymentOrderSyncResult,
  FinancePaymentPayableLineOption,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.2.2.1 付款单与审批的前端模型。
 *
 * <p>蓝图 12-finance.md：「<b>发起付款申请：选择应付明细、付款账户、币种与汇率、付款方式、
 * 分批安排，走分级审批。</b>输入：付款申请；审批规则（金额阈值）；汇率快照。
 * 输出：<b>付款单（草稿/待审/已批/执行中/已付/失败）+ 审批记录</b>。
 * 关系：上游 = 12.1.2.1、12.1.2.2；下游 = 12.2.2.2、17 审批。
 * <b>付款审批是资金安全的第一道控制</b>，大额付款必须双人复核或多级审批。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>金额只原样展示</b>：付款金额、汇总金额都由**服务端**算好，
 *       页面**不做任何前端算术**（USD 与 EUR 加起来不是任何一个金额）。</li>
 *   <li><b>币种分开</b>：汇总金额一律按币种分组渲染，
 *       **绝不**把不同币种摆进同一列相加。</li>
 *   <li><b>「已批」不等于「已付」</b>：付款属 12.2.2.2（未交付），
 *       页面**不提供**任何「执行付款」「标记已付」的按钮 —— 那会是一条假的付款路径。</li>
 *   <li><b>审批在 17 审批里做</b>：本模块**不提供**本地的「审批通过 / 驳回」按钮；
 *       页面提供的是「提交审批」与「同步审批结果」两个动作，
 *       审批动作本身要去 identity 的待我审批里做。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有付款单」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code finance/payment-orders}，与 12.1.2.1 的 {@code finance/payables}
 * 、12.1.2.2 的 {@code finance/payment-plans} 各自独立前缀。
 */

const base = 'finance/payment-orders';

/**
 * 付款单列表的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface PaymentOrderParams {
  status?: string | undefined;
  approvalStatus?: string | undefined;
  currency?: string | undefined;
  counterpartyType?: string | undefined;
  counterpartyRef?: string | undefined;
  paymentMethod?: string | undefined;
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

/** 能力声明：哪些真接通、哪些还没交付、归谁。 */
export function fetchPaymentOrderCapabilities() {
  return api<FinancePaymentOrderCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（全部来自真实数据）。 */
export function fetchPaymentOrderOptions() {
  return api<FinancePaymentOrderOptions>(`${base}/options`);
}

/** 付款单列表：真分页 + 真筛选 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。 */
export function fetchPaymentOrders(params: PaymentOrderParams = {}) {
  return api<FinancePaymentOrderPage>(`${base}${query({ ...params })}`);
}

/** 付款单详情：单头 + 明细 + 分批 + 事件流 + 审批记录。 */
export function fetchPaymentOrderDetail(id: string) {
  return api<FinancePaymentOrderDetail>(`${base}/${encodeURIComponent(id)}`);
}

/** 建一张付款单（草稿）。 */
export function createPaymentOrder(input: FinancePaymentOrderInput) {
  return api<FinancePaymentOrderRow>(`${base}`, 'POST', input);
}

/** 改一张**草稿**付款单（明细与分批整体替换）。 */
export function updatePaymentOrder(id: string, input: FinancePaymentOrderInput) {
  return api<FinancePaymentOrderRow>(`${base}/${encodeURIComponent(id)}`, 'PUT', input);
}

/**
 * 提交审批（草稿 → 待审）。
 *
 * <p>⚠️ 真正的审批判定在 identity 的 17 审批里：起始节点由它按金额阈值分支选出来。
 */
export function submitPaymentOrder(id: string) {
  return api<FinancePaymentOrderSubmitResult>(`${base}/${encodeURIComponent(id)}/submit`, 'POST');
}

/**
 * 同步审批结果（读回 identity 的审批链路）。
 *
 * <p>已批 ⇒ 转「已批」；已驳回 ⇒ **退回草稿**；还在审 ⇒ 只刷新当前节点。
 */
export function syncPaymentOrderApproval(id: string) {
  return api<FinancePaymentOrderSyncResult>(
    `${base}/${encodeURIComponent(id)}/sync-approval`,
    'POST',
  );
}

/** 某张应付单下**可被选中**的明细行（只读 12.1.2.1）。 */
export function fetchPayableLines(payableId: string) {
  return api<FinancePaymentPayableLineOption[]>(
    `${base}/payable-lines/${encodeURIComponent(payableId)}`,
  );
}

/**
 * 汇率快照（**读** catalog 5.2.2.1）。
 *
 * <p>口径：「1 baseCurrency = rate quoteCurrency」，与单头汇率快照一致。
 */
export function fetchPaymentExchangeRate(baseCurrency: string, quoteCurrency: string) {
  return api<FinancePaymentExchangeRateView>(
    `${base}/exchange-rate${query({ baseCurrency, quoteCurrency })}`,
  );
}

// ================================================================ 可读形态

/** 付款单状态的可读名（**蓝图原文的六个词**）。 */
export function paymentOrderStatusLabel(status: string): string {
  switch (status) {
    case 'DRAFT':
      return '草稿';
    case 'PENDING_APPROVAL':
      return '待审';
    case 'APPROVED':
      return '已批';
    case 'EXECUTING':
      return '执行中';
    case 'PAID':
      return '已付';
    case 'FAILED':
      return '失败';
    default:
      return status;
  }
}

/**
 * 付款单状态的可读色调。
 *
 * <p>⚠️ <b>「已批」绝不能渲染成成功态（绿色）</b> —— 已批只表示「审批通过了」，
 * 钱**还没有付出去**。把已批渲染成绿色会被读成「这笔钱已经付了」，
 * 那正是「审批通过 ≠ 钱已付出」这条纪律在界面上的破口。
 */
export function paymentOrderStatusTone(
  status: string,
): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'DRAFT':
      return 'neutral';
    case 'PENDING_APPROVAL':
      return 'warning';
    case 'APPROVED':
      return 'warning';
    case 'EXECUTING':
      return 'warning';
    case 'PAID':
      return 'success';
    case 'FAILED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 审批状态的可读名。 */
export function paymentApprovalStatusLabel(status: string): string {
  switch (status) {
    case 'NOT_SUBMITTED':
      return '未提交';
    case 'PENDING':
      return '待审';
    case 'APPROVED':
      return '已批';
    case 'REJECTED':
      return '已驳回';
    default:
      return status;
  }
}

/** 审批状态的可读色调。 */
export function paymentApprovalStatusTone(
  status: string,
): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'NOT_SUBMITTED':
      return 'neutral';
    case 'PENDING':
      return 'warning';
    case 'APPROVED':
      return 'success';
    case 'REJECTED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 付款方式的可读名。 */
export function paymentMethodLabel(method: string): string {
  switch (method) {
    case 'BANK_TRANSFER':
      return '银行电汇';
    case 'PAYPAL':
      return 'PayPal';
    case 'STRIPE':
      return 'Stripe';
    case 'PINGPONG':
      return 'PingPong';
    case 'OTHER':
      return '其他';
    default:
      return method;
  }
}

/** 付款对象类型的可读名。 */
export function paymentCounterpartyTypeLabel(type: string): string {
  switch (type) {
    case 'SUPPLIER':
      return '供应商';
    case 'CARRIER':
      return '承运商';
    case 'PLATFORM':
      return '平台';
    case 'OTHER':
      return '其他';
    default:
      return type;
  }
}

/** 分批状态的可读名。 */
export function installmentStatusLabel(status: string): string {
  switch (status) {
    case 'PLANNED':
      return '已排期';
    case 'EXECUTING':
      return '执行中';
    case 'PAID':
      return '已付';
    case 'FAILED':
      return '失败';
    default:
      return status;
  }
}

/** 事件类型的可读名。 */
export function paymentOrderEventLabel(eventType: string): string {
  switch (eventType) {
    case 'CREATED':
      return '建单';
    case 'UPDATED':
      return '改单';
    case 'SUBMITTED':
      return '提交审批';
    case 'APPROVAL_SYNCED':
      return '同步审批进度';
    case 'APPROVED':
      return '审批通过';
    case 'RETURNED_TO_DRAFT':
      return '退回草稿';
    case 'EXECUTION_STARTED':
      return '开始执行';
    case 'PAID':
      return '已付款';
    case 'FAILED':
      return '付款失败';
    default:
      return eventType;
  }
}

/** 审批动作的可读名。 */
export function approvalActionLabel(action: string): string {
  switch (action) {
    case 'SUBMIT':
      return '提交审批';
    case 'APPROVE':
      return '审批通过';
    case 'REJECT':
      return '审批驳回';
    case 'TRANSFER':
      return '转交';
    default:
      return action;
  }
}

/**
 * 金额的可读形态。
 *
 * <p>⚠️ **不在前端做算术**：数值原样透传（只做显示格式化）。
 */
export function formatPaymentOrderAmount(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '—';
  return amount.toFixed(4);
}

/** 金额 + 币种的可读形态。 */
export function formatPaymentOrderMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '—';
  return `${amount.toFixed(4)} ${currency ?? ''}`.trim();
}

/**
 * 汇率快照的可读形态。
 *
 * <p>⚠️ <b>推导出来的汇率必须让人看见</b>：上游只登记了反方向时，本模块取倒数得到
 * 目标方向 —— 页面必须标出「推导」，否则人会把一个推出来的数字当成登记的事实。
 */
export function paymentExchangeRateHint(rate: FinancePaymentExchangeRateView): string {
  const prefix = rate.inverted ? '推导自反方向 · ' : '';
  return `${prefix}1 ${rate.baseCurrency} = ${rate.rate} ${rate.quoteCurrency}`;
}

/**
 * 付款执行的可读形态。
 *
 * <p>⚠️ 付款属 12.2.2.2（未交付）⇒ 这个值**恒为**「不可用」。
 */
export function paymentExecutionLabel(executionAvailable: boolean, ownerModule: string): string {
  if (executionAvailable) return `可执行（归属 ${ownerModule}）`;
  return `付款执行不可用（归属模块 ${ownerModule}）`;
}

/**
 * 付款账户的可读形态。
 *
 * <p>⚠️ 资金账户属 12.7.1.1（未交付）⇒ 这里只有登记的引用与名称，没有账户余额。
 */
export function paymentAccountLabel(
  accountName: string | null | undefined,
  ownerModule: string,
): string {
  if (!accountName) return '未指定付款账户';
  return `${accountName}（账户登记归属 ${ownerModule}，本模块不持有余额）`;
}

/** 明细行是否可被选中（未被在册付款单占用，且还有未付余额）。 */
export function payableLineSelectable(line: FinancePaymentPayableLineOption): boolean {
  return line.lockedByOrderId === null && Number(line.unpaidAmount) > 0;
}

/** 明细行不可选时的原因（说清楚是「被占了」还是「没有余额」）。 */
export function payableLineBlockedReason(line: FinancePaymentPayableLineOption): string {
  if (line.lockedByOrderId) return `已被付款单 ${line.lockedByOrderNo ?? ''} 占用`;
  if (Number(line.unpaidAmount) <= 0) return '未付余额为 0';
  return '';
}

/** 构造一份付款单入参（页面用，省得到处写字段顺序）。 */
export function buildPaymentOrderInput(input: {
  counterpartyType: FinancePaymentOrderInput['counterpartyType'];
  counterpartyRef: string | null;
  counterpartyName: string | null;
  currency: string;
  amount: string;
  baseCurrency: string | null;
  exchangeRate: string | null;
  paymentMethod: FinancePaymentOrderInput['paymentMethod'];
  paymentAccountId: string | null;
  paymentAccountName: string | null;
  paymentAccountCurrency: string | null;
  paymentPlanId: string | null;
  note: string | null;
  lines: { payableLineId: string; description: string | null; currency: string; amount: string }[];
  installments: { plannedPaidDate: string; currency: string; amount: string }[];
}): FinancePaymentOrderInput {
  const lines: FinancePaymentOrderLineInput[] = input.lines.map((line) => ({
    payableLineId: line.payableLineId,
    description: line.description ?? null,
    currency: line.currency,
    amount: Number(line.amount),
  }));
  const installments: FinancePaymentOrderInstallmentInput[] = input.installments.map((item) => ({
    seq: null,
    plannedPaidDate: item.plannedPaidDate,
    currency: item.currency,
    amount: Number(item.amount),
  }));
  return {
    counterpartyType: input.counterpartyType,
    counterpartyRef: input.counterpartyRef,
    counterpartyName: input.counterpartyName,
    currency: input.currency,
    amount: Number(input.amount),
    baseCurrency: input.baseCurrency,
    exchangeRate: input.exchangeRate === null ? null : Number(input.exchangeRate),
    paymentMethod: input.paymentMethod,
    paymentAccountId: input.paymentAccountId,
    paymentAccountName: input.paymentAccountName,
    paymentAccountCurrency: input.paymentAccountCurrency,
    paymentPlanId: input.paymentPlanId,
    note: input.note,
    lines,
    installments: installments.length > 0 ? installments : null,
  };
}

/** 类型别名导出，页面与测试共用同一套名字。 */
export type {
  FinancePaymentApprovalRecordRow,
  FinancePaymentExchangeRateView,
  FinancePaymentOrderDetail,
  FinancePaymentOrderEventRow,
  FinancePaymentOrderInstallmentRow,
  FinancePaymentOrderLineRow,
  FinancePaymentOrderRow,
  FinancePaymentPayableLineOption,
};
