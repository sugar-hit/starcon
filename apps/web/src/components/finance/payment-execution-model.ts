import type {
  FinancePaymentChannelInput,
  FinancePaymentChannelRow,
  FinancePaymentExecutionCapabilities,
  FinancePaymentExecutionDetail,
  FinancePaymentExecutionInput,
  FinancePaymentExecutionOptions,
  FinancePaymentExecutionPage,
  FinancePaymentExecutionPendingOrder,
  FinancePaymentExecutionResult,
  FinancePaymentExchangeRateView,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.2.2.2 付款执行的前端模型。
 *
 * <p>蓝图 12-finance.md：「<b>执行付款：对接支付渠道/银企直连，发送付款指令，跟踪结果，
 * 失败重试与异常处理；处理汇率锁汇。</b>输入：已批付款单；支付渠道配置（密钥引用）；汇率锁定。
 * 输出：<b>付款结果 + 渠道流水号 + 失败原因</b>。关系：上游 = 12.2.2.1；
 * 下游 = 12.7.1 资金账户、12.5.2 凭证。
 * <b>外部支付超时不等于失败</b>，必须先查证再重发——重复付款是严重资损事故。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>「结果待查证」不是失败</b>：UNKNOWN 是一种**独立**的语气（warning），
 *       <b>不复用</b> FAILED 的 danger。把「超时」渲染成红色，等于在催人点重试 ——
 *       而重复付款是严重资损事故。页面必须在 UNKNOWN 上给出「先查证」的动作。</li>
 *   <li><b>金额只原样展示</b>：执行金额、汇总金额都由**服务端**算好，
 *       页面**不做任何前端算术**（USD 与 EUR 加起来不是任何一个金额）。</li>
 *   <li><b>币种分开</b>：汇总金额一律按币种分组渲染，**绝不**把不同币种摆进同一列相加。</li>
 *   <li><b>重发按钮只在「该重发」时出现</b>：只有 UNKNOWN / FAILED 才给重发入口，
 *       且重发端点内部**先查证再重发**（页面不替后端做这个判断）。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有执行单」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code finance/payment-executions}，与 12.2.2.1 的
 * {@code finance/payment-orders}、12.1.2.2 的 {@code finance/payment-plans} 各自独立前缀。
 */

const base = 'finance/payment-executions';

/**
 * 付款执行列表的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface PaymentExecutionParams {
  status?: string | undefined;
  failureClass?: string | undefined;
  currency?: string | undefined;
  channelCode?: string | undefined;
  orderId?: string | undefined;
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
export function fetchPaymentExecutionCapabilities() {
  return api<FinancePaymentExecutionCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（全部来自真实数据）。 */
export function fetchPaymentExecutionOptions() {
  return api<FinancePaymentExecutionOptions>(`${base}/options`);
}

/** 执行单列表：真分页 + 真筛选 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。 */
export function fetchPaymentExecutions(params: PaymentExecutionParams = {}) {
  return api<FinancePaymentExecutionPage>(`${base}${query({ ...params })}`);
}

/** 执行单详情：执行单 + 尝试记录 + 事件流 + 分摊 + 锁汇。 */
export function fetchPaymentExecutionDetail(id: string) {
  return api<FinancePaymentExecutionDetail>(`${base}/${encodeURIComponent(id)}`);
}

/** 可发起付款执行的付款单（已批 / 执行中）。 */
export function fetchPendingPaymentOrders() {
  return api<FinancePaymentExecutionPendingOrder[]>(`${base}/pending-orders`);
}

/** 支付渠道列表（**只含密钥引用**，没有明文）。 */
export function fetchPaymentChannels() {
  return api<FinancePaymentChannelRow[]>(`${base}/channels`);
}

/** 登记一个支付渠道（凭据只存**引用**）。 */
export function registerPaymentChannel(input: FinancePaymentChannelInput) {
  return api<FinancePaymentChannelRow>(`${base}/channels`, 'POST', input);
}

/** 改一个支付渠道的配置。 */
export function updatePaymentChannel(id: string, input: FinancePaymentChannelInput) {
  return api<FinancePaymentChannelRow>(
    `${base}/channels/${encodeURIComponent(id)}`,
    'PUT',
    input,
  );
}

/** 发起一次付款执行（对一张**已批**付款单）。 */
export function executePayment(input: FinancePaymentExecutionInput) {
  return api<FinancePaymentExecutionResult>(`${base}`, 'POST', input);
}

/**
 * 状态查证（**不重发**）。
 *
 * <p>⚠️ 查证结论由后端如实落库：渠道侧成功 ⇒ 收敛成「已付」；查不出来 ⇒ 结果仍未知。
 */
export function verifyPaymentExecution(id: string) {
  return api<FinancePaymentExecutionResult>(
    `${base}/${encodeURIComponent(id)}/verify`,
    'POST',
  );
}

/**
 * 重发。
 *
 * <p>🔴 **第一步永远是查证**：查证发现渠道侧已成功 ⇒ 后端**不重发**。
 * 页面不替后端做这个判断 —— 重发是有代价的动作，判据必须在服务端。
 */
export function retryPaymentExecution(id: string) {
  return api<FinancePaymentExecutionResult>(
    `${base}/${encodeURIComponent(id)}/retry`,
    'POST',
  );
}

/** 当前市场汇率（**只读** catalog 5.2.2.1），用来对照单据**锁定**的汇率。 */
export function fetchPaymentMarketRate(baseCurrency: string, quoteCurrency: string) {
  return api<FinancePaymentExchangeRateView>(
    `${base}/market-rate${query({ baseCurrency, quoteCurrency })}`,
  );
}

// ================================================================ 可读形态

/** 执行状态的可读名。 */
export function paymentExecutionStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '待发送';
    case 'SENDING':
      return '发送中';
    case 'SUCCEEDED':
      return '已付款';
    case 'FAILED':
      return '失败';
    case 'UNKNOWN':
      return '结果待查证';
    default:
      return status;
  }
}

/**
 * 执行状态的可读色调。
 *
 * <p>🔴 <b>UNKNOWN（结果待查证）刻意不是 danger</b>：外部支付超时不等于失败。
 * 把它渲染成红色等于在催人点「重发」—— 而重复付款是严重资损事故。
 * 它用 warning（与 FAILED 分开），页面同时给出「先查证」的入口。
 */
export function paymentExecutionStatusTone(
  status: string,
): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'PENDING':
      return 'neutral';
    case 'SENDING':
      return 'warning';
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
      return 'danger';
    case 'UNKNOWN':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 失败分类的可读名。 */
export function paymentFailureClassLabel(failureClass: string): string {
  switch (failureClass) {
    case 'NONE':
      return '无';
    case 'CHANNEL_REJECTED':
      return '渠道拒绝';
    case 'CHANNEL_UNAVAILABLE':
      return '渠道不可用';
    case 'NOT_CONFIGURED':
      return '渠道未配置';
    case 'TIMEOUT':
      return '渠道超时';
    case 'AMOUNT_MISMATCH':
      return '金额不符';
    case 'INTERNAL':
      return '内部错误';
    default:
      return failureClass;
  }
}

/** 尝试类型的可读名。 */
export function paymentAttemptKindLabel(kind: string): string {
  switch (kind) {
    case 'SEND':
      return '发送';
    case 'QUERY':
      return '查证';
    default:
      return kind;
  }
}

/** 尝试结论的可读名。 */
export function paymentAttemptOutcomeLabel(outcome: string): string {
  switch (outcome) {
    case 'SUCCESS':
      return '成功';
    case 'REJECTED':
      return '渠道拒绝';
    case 'NOT_FOUND':
      return '渠道无此笔';
    case 'TIMEOUT':
      return '超时';
    case 'CHANNEL_UNAVAILABLE':
      return '渠道不可用';
    case 'NOT_CONFIGURED':
      return '渠道未配置';
    case 'AMOUNT_MISMATCH':
      return '金额不符';
    default:
      return outcome;
  }
}

/** 尝试结论的可读色调（UNKNOWN 类结论**不**用 danger）。 */
export function paymentAttemptOutcomeTone(
  outcome: string,
): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (outcome) {
    case 'SUCCESS':
      return 'success';
    case 'REJECTED':
      return 'danger';
    case 'NOT_FOUND':
      return 'warning';
    case 'TIMEOUT':
    case 'CHANNEL_UNAVAILABLE':
      return 'warning';
    case 'NOT_CONFIGURED':
      return 'danger';
    case 'AMOUNT_MISMATCH':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 锁汇状态的可读名。 */
export function paymentRateLockStatusLabel(status: string): string {
  switch (status) {
    case 'LOCKED':
      return '生效中';
    case 'RELEASED':
      return '已释放';
    case 'EXPIRED':
      return '已过期';
    default:
      return status;
  }
}

/** 事件类型的可读名。 */
export function paymentExecutionEventLabel(eventType: string): string {
  switch (eventType) {
    case 'CREATED':
      return '创建执行单';
    case 'SEND_STARTED':
      return '开始发送';
    case 'SEND_SUCCEEDED':
      return '发送成功';
    case 'SEND_REJECTED':
      return '发送被拒';
    case 'SEND_UNKNOWN':
      return '发送结果未知';
    case 'VERIFY_STARTED':
      return '开始查证';
    case 'VERIFY_SUCCEEDED':
      return '查证发现已成功';
    case 'VERIFY_NOT_FOUND':
      return '查证：渠道无此笔';
    case 'VERIFY_UNKNOWN':
      return '查证结果未知';
    case 'RETRY':
      return '重发';
    case 'RATE_LOCKED':
      return '锁定汇率';
    case 'RATE_RELEASED':
      return '释放汇率锁';
    case 'WRITEBACK_APPLIED':
      return '行级已付金额已回写';
    case 'WRITEBACK_CONFLICT':
      return '行级已付金额回写冲突';
    default:
      return eventType;
  }
}

/** 付款方式的可读名（与 12.2.2.1 同一词表）。 */
export function paymentExecutionMethodLabel(method: string): string {
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

// ================================================================ 动作

/**
 * 当前状态**允许**的动作。
 *
 * <p>⚠️ 这是**界面层**的提示，不是判据：真正的判据在服务端（重发前必须查证）。
 * 页面据此决定按钮的可用性，但**不**替后端判断「能不能重发」。
 */
export function paymentExecutionActions(status: string): {
  canVerify: boolean;
  canRetry: boolean;
} {
  return {
    // 查证：除了「已付款」以外都能查（已成功再查没有意义）。
    canVerify: status !== 'SUCCEEDED',
    // 重发：只有「结果未知」与「失败」才给入口（已成功再发就是重复付款）。
    canRetry: status === 'UNKNOWN' || status === 'FAILED',
  };
}

/** 金额 + 币种（**只原样展示**，页面不做任何前端算术）。 */
export function formatPaymentExecutionAmount(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '—';
  const fixed = amount.toFixed(4);
  return currency ? `${fixed} ${currency}` : fixed;
}

/** 汇率（1 A = rate B）。 */
export function formatPaymentExchangeRate(
  rate: number | null | undefined,
  baseCurrency: string | null | undefined,
  quoteCurrency: string | null | undefined,
): string {
  if (rate === null || rate === undefined) return '—';
  const fixed = rate.toFixed(8);
  return baseCurrency && quoteCurrency ? `1 ${baseCurrency} = ${fixed} ${quoteCurrency}` : fixed;
}

/**
 * 「可发起付款执行」的候选项的显示名。
 *
 * <p>有分批安排时**逐批**显示（每一批是独立的执行对象），
 * 否则显示整单 —— 页面必须让人看清「这一笔要付的是哪一批」。
 */
export function pendingPaymentOrderLabel(order: {
  orderNo: string;
  counterpartyName: string;
  currency: string;
  amount: number;
  installmentSeq: number | null;
  plannedPaidDate?: string | null;
}): string {
  // ⚠️ 局部变量**不要**也叫 `base`：模块级的 `base` 是命名空间常量，
  //    同名会让「从源码反向抽取真实调用点」的白名单守卫把这一行当成路径抽出来。
  const head = `${order.orderNo} · ${order.counterpartyName || '未命名'} · ${formatPaymentExecutionAmount(order.amount, order.currency)}`;
  if (order.installmentSeq === null || order.installmentSeq === undefined) return head;
  const date = order.plannedPaidDate ? ` · 计划 ${order.plannedPaidDate}` : '';
  return `${head} · 第 ${order.installmentSeq} 批${date}`;
}
