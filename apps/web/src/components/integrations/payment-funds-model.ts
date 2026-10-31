import type {
  BalanceImportInput,
  BalanceQueryInput,
  FeeImportInput,
  FundFlowRow,
  PaymentAttemptRow,
  PaymentBalanceRow,
  PaymentExecutionInput,
  PaymentFeeRow,
  PaymentFundsCapabilities,
  PaymentFundsCapability,
  PaymentFundsOverview,
  PaymentOperationRow,
  PaymentProviderAvailability,
  PaymentProviderInput,
  PaymentProviderRow,
  PaymentRateRow,
  RateQueryInput,
  ReceiptRegistrationInput,
  UpstreamFundAccountRow,
  UpstreamPaymentOrderRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 18.2.2.1「支付与资金」前端模型。
 *
 * 蓝图：「**支付与资金**：对接支付与资金服务（PayPal、Stripe、PingPong、连连、万里汇）：
 * 余额查询、收款登记、付款执行、汇率查询、手续费明细同步。输入：资金操作请求；
 * 接口配置（**密钥引用**）。输出：操作结果 + 资金流水同步。关系：上游 = 12.2 收付款、12.7 资金账户；
 * 下游 = 支付服务商。**所有第三方资金操作必须有幂等键与查询兜底**，超时先查证再重试（实体目录 §6）。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「未接入」显示成「未接入」，不是「支付成功」**。本仓目前没有任何真实的支付服务商
 *    凭据与网络通道，所以「直连」那几格 `available=false`。页面**不提供**必然 409 的按钮，
 *    也**不做本地兜底生成** —— 一条看起来「钱付出去了」的假通道会让运营以为钱已经转出。
 * 2. **幂等键是页面上的一等公民**。付款执行必须带幂等键；页面把「同键重复提交不会重复扣款」
 *    写在按钮旁边，而不是让运营自己记住。
 * 3. **「结果未知」不是「失败」**。超时的操作进 `UNKNOWN`，页面必须把它显示成
 *    「结果未知 · 已自动查证」，并只给「允许重发」的操作显示重发按钮 ——
 *    「查不到」不等于「没付出去」，盲目重发可能付两次。
 * 4. **未接通服务商时收款登记照样在本地台账里**，但要显示「未向服务商登记」，
 *    不然运营会以为对方系统里也有一笔。
 * 5. **密钥只存引用**。服务商配置页面上只有「密钥引用」与「末位提示」两格，
 *    没有任何「粘贴 API Key」的地方。
 *
 * <p><b>类型全部从契约派生</b>（`packages/api-contracts` 的 codegen 产物），
 * 前端**不手抄**字符串联合 —— 枚举值由后端的字典列给出。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有分支）。 */
const base = 'platform/payment-funds';

/**
 * 拼查询串：空值不拼（拼 `undefined=` 上游会当成「筛选了这个值」）。
 */
function query(params: Record<string, string | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, value);
  }
  return search.toString();
}

// ================================================================ 读取

/** 能力声明：哪些真接通、哪些还没交付、归谁。 */
export function fetchCapabilities(): Promise<PaymentFundsCapabilities> {
  return api<PaymentFundsCapabilities>(`${base}/capabilities`);
}

/** 概览：几个数。 */
export function fetchOverview(): Promise<PaymentFundsOverview> {
  return api<PaymentFundsOverview>(`${base}/overview`);
}

/** 逐服务商的通道可用性（**逐家如实答**）。 */
export function fetchAvailability(): Promise<PaymentProviderAvailability[]> {
  return api<PaymentProviderAvailability[]>(`${base}/providers/availability`);
}

/** 服务商配置列表（真筛选）。 */
export function fetchProviders(params?: {
  providerCode?: string;
  status?: string;
}): Promise<PaymentProviderRow[]> {
  return api<PaymentProviderRow[]>(`${base}/providers?${query({ ...params })}`);
}

/** 资金操作记录（真筛选 + 真关键词）。 */
export function fetchOperations(params?: {
  providerCode?: string;
  operationType?: string;
  status?: string;
  keyword?: string;
}): Promise<PaymentOperationRow[]> {
  return api<PaymentOperationRow[]>(`${base}/operations?${query({ ...params })}`);
}

/** 一条操作记录。 */
export function fetchOperation(id: string): Promise<PaymentOperationRow> {
  return api<PaymentOperationRow>(`${base}/operations/${id}`);
}

/** 一次操作的尝试留痕（**查证兜底**的证据）。 */
export function fetchAttempts(id: string): Promise<PaymentAttemptRow[]> {
  return api<PaymentAttemptRow[]>(`${base}/operations/${id}/attempts`);
}

/** 余额观测（真筛选）。 */
export function fetchBalances(params?: {
  providerId?: string;
  currency?: string;
}): Promise<PaymentBalanceRow[]> {
  return api<PaymentBalanceRow[]>(`${base}/balances?${query({ ...params })}`);
}

/** 手续费明细（真筛选）。 */
export function fetchFees(params?: {
  providerId?: string;
  currency?: string;
}): Promise<PaymentFeeRow[]> {
  return api<PaymentFeeRow[]>(`${base}/fees?${query({ ...params })}`);
}

/** 汇率观测（真筛选）。 */
export function fetchRates(params?: {
  providerId?: string;
  baseCurrency?: string;
  quoteCurrency?: string;
}): Promise<PaymentRateRow[]> {
  return api<PaymentRateRow[]>(`${base}/rates?${query({ ...params })}`);
}

/** 资金流水台账（真筛选）。 */
export function fetchFlows(params?: {
  providerId?: string;
  flowType?: string;
  currency?: string;
}): Promise<FundFlowRow[]> {
  return api<FundFlowRow[]>(`${base}/flows?${query({ ...params })}`);
}

/** 上游 12.7 资金账户（**只读**；读不到 ⇒ 503）。 */
export function fetchUpstreamFundAccounts(params?: {
  currency?: string;
  keyword?: string;
}): Promise<UpstreamFundAccountRow[]> {
  return api<UpstreamFundAccountRow[]>(`${base}/upstream/fund-accounts?${query({ ...params })}`);
}

/** 上游 12.2 收付款（**只读**；读不到 ⇒ 503）。 */
export function fetchUpstreamPaymentOrders(params?: {
  status?: string;
  currency?: string;
  keyword?: string;
}): Promise<UpstreamPaymentOrderRow[]> {
  return api<UpstreamPaymentOrderRow[]>(`${base}/upstream/payment-orders?${query({ ...params })}`);
}

// ================================================================ 写入

/** 新增或修改一个服务商配置（**密钥只存引用**）。 */
export function saveProvider(input: PaymentProviderInput): Promise<PaymentProviderRow> {
  return api<PaymentProviderRow>(`${base}/providers`, 'POST', input);
}

/** 停用一个服务商配置（**不删**）。 */
export function disableProvider(id: string): Promise<PaymentProviderRow> {
  return api<PaymentProviderRow>(`${base}/providers/${id}/disable`, 'POST');
}

/** **付款执行**（幂等键必填；未接入 ⇒ 409）。 */
export function executePayment(input: PaymentExecutionInput): Promise<PaymentOperationRow> {
  return api<PaymentOperationRow>(`${base}/payments`, 'POST', input);
}

/** **重发**一次付款（只在查证确认「服务商侧没有这笔」之后才允许）。 */
export function retryPayment(id: string): Promise<PaymentOperationRow> {
  return api<PaymentOperationRow>(`${base}/operations/${id}/retry`, 'POST');
}

/** **收款登记**。 */
export function registerReceipt(input: ReceiptRegistrationInput): Promise<PaymentOperationRow> {
  return api<PaymentOperationRow>(`${base}/receipts`, 'POST', input);
}

/** **余额查询**（服务商直查；未接入 ⇒ 409）。 */
export function queryBalance(input: BalanceQueryInput): Promise<PaymentOperationRow> {
  return api<PaymentOperationRow>(`${base}/balances/query`, 'POST', input);
}

/** **余额导入**（从服务商账单 / 对账单）。 */
export function importBalance(input: BalanceImportInput): Promise<PaymentBalanceRow> {
  return api<PaymentBalanceRow>(`${base}/balances/import`, 'POST', input);
}

/** **手续费明细同步**（服务商直拉；未接入 ⇒ 409）。 */
export function syncFees(params: {
  providerId: string;
  currency?: string;
  idempotencyKey?: string;
}): Promise<PaymentOperationRow> {
  return api<PaymentOperationRow>(
    `${base}/fees/sync?${query({ providerId: params.providerId, currency: params.currency })}`,
    'POST',
  );
}

/** **手续费明细导入**（从服务商账单 / 对账单）。 */
export function importFee(input: FeeImportInput): Promise<PaymentFeeRow> {
  return api<PaymentFeeRow>(`${base}/fees/import`, 'POST', input);
}

/** **汇率查询**（真读 catalog 汇率事实并落库）。 */
export function queryRate(input: RateQueryInput): Promise<PaymentOperationRow> {
  return api<PaymentOperationRow>(`${base}/rates/query`, 'POST', input);
}

// ================================================================ 纯函数（可单测）

/** 语气。 */
export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

/** 金额格式化：空值显示「—」，小数位固定 2 位（后端是 NUMERIC(20,6)）。 */
export function formatAmount(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return value.toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 6,
  });
}

/** 金额 + 币种。 */
export function formatMoney(
  value: number | null | undefined,
  currency?: string | undefined,
): string {
  const amount = formatAmount(value);
  if (amount === '—') return amount;
  return currency ? `${amount} ${currency}` : amount;
}

/** 汇率格式化：口径是「1 base = rate quote」。 */
export function formatRate(
  value: number | null | undefined,
  base?: string | undefined,
  quote?: string | undefined,
): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  const rate = value.toLocaleString('en-US', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 8,
  });
  if (!base || !quote) return rate;
  return `1 ${base} = ${rate} ${quote}`;
}

/**
 * 操作状态 → 语气。
 *
 * 🔴 **UNKNOWN 必须是 warning 而不是 danger**：
 * 「结果未知」既不是成功也不是失败，它的意思是「**还没查清楚**」。
 * 把它显示成红色失败会让人以为钱没出去而直接重发 —— 那可能付两次。
 */
export function operationTone(status: string): Tone {
  switch (status) {
    case 'SUCCEEDED':
      return 'ok';
    case 'FAILED':
      return 'danger';
    case 'UNKNOWN':
      return 'warn';
    case 'NOT_CONNECTED':
      return 'muted';
    default:
      return 'muted';
  }
}

/** 操作状态的中文说法。 */
export function operationStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '已受理';
    case 'SENDING':
      return '执行中';
    case 'SUCCEEDED':
      return '成功';
    case 'FAILED':
      return '失败';
    case 'UNKNOWN':
      return '结果未知';
    case 'NOT_CONNECTED':
      return '服务商未接入';
    default:
      return status;
  }
}

/** 操作类型的中文说法。 */
export function operationTypeLabel(type: string): string {
  switch (type) {
    case 'BALANCE_QUERY':
      return '余额查询';
    case 'RECEIPT_REGISTER':
      return '收款登记';
    case 'PAYMENT_EXECUTE':
      return '付款执行';
    case 'RATE_QUERY':
      return '汇率查询';
    case 'FEE_SYNC':
      return '手续费明细同步';
    default:
      return type;
  }
}

/** 资金方向的中文说法。 */
export function directionLabel(direction: string): string {
  switch (direction) {
    case 'INBOUND':
      return '进';
    case 'OUTBOUND':
      return '出';
    default:
      return '—';
  }
}

/** 流水类型的中文说法。 */
export function flowTypeLabel(type: string): string {
  switch (type) {
    case 'RECEIPT':
      return '收款';
    case 'PAYMENT':
      return '付款';
    case 'FEE':
      return '手续费';
    case 'ADJUSTMENT':
      return '调整';
    default:
      return type;
  }
}

/** 失败分类的中文说法。 */
export function failureClassLabel(failureClass: string): string {
  switch (failureClass) {
    case 'REJECTED':
      return '服务商明确拒绝';
    case 'TIMEOUT':
      return '超时';
    case 'UNAVAILABLE':
      return '服务商不可达';
    case 'NOT_CONFIGURED':
      return '未接入';
    default:
      return '';
  }
}

/** 能力声明的语气。 */
export function capabilityTone(available: boolean): Tone {
  return available ? 'ok' : 'muted';
}

/**
 * 挑出「直连服务商」那一维的能力声明。
 *
 * <p>🔴 页面**必须**把它们单独拎出来显示：它们是本模块唯一还没接通的一批，
 * 混在一堆「已交付」里会让运营以为「点一下就能付到 PayPal」。
 */
export function directProviderCapabilities(
  capabilities: PaymentFundsCapability[],
): PaymentFundsCapability[] {
  return capabilities.filter((c) => c.name.includes('直连'));
}

/**
 * 这一条操作**现在**能不能重发。
 *
 * 🔴 只有「结果未知 **且** 查证明确说服务商侧没有这笔」才允许重发。
 * 「查不出来」绝不允许重发 —— 查不到 ≠ 没付出去。
 */
export function canRetry(operation: PaymentOperationRow): boolean {
  return operation.status === 'UNKNOWN' && operation.retryAllowed === true;
}

/**
 * 收款登记这一条：服务商侧到底有没有被登记。
 *
 * <p>🔴 本地台账成功 ≠ 服务商侧已登记。返回「未登记」时页面要**明说**，
 * 不然运营会以为对方系统里也有一笔。
 */
export function receiptProviderNotice(operation: PaymentOperationRow): string | null {
  if (operation.operationType !== 'RECEIPT_REGISTER') return null;
  if (operation.providerNotified === true) return null;
  return operation.providerNotice || '服务商未接入，未向服务商登记（本地台账已记）';
}

/** 幂等键的提示文案（写在付款按钮旁边）。 */
export function idempotencyHint(operation: PaymentOperationRow): string {
  if (operation.replayed === true) {
    return `幂等重放：幂等键 ${operation.idempotencyKey} 已登记过，返回同一结果（未再次调用服务商）`;
  }
  return `幂等键 ${operation.idempotencyKey}：同键重复提交只会产生一笔`;
}

/**
 * 校验付款表单（前端先挡一道，省一次往返）。
 *
 * <p>🔴 **幂等键必填**：蓝图加粗句「所有第三方资金操作必须有幂等键」——
 * 页面不放行一个没有幂等键的付款请求。
 */
export function validatePayment(input: {
  idempotencyKey: string;
  reference: string;
  currency: string;
  amount: number | null;
  counterpartyName: string;
}): string[] {
  const errors: string[] = [];
  if (!input.idempotencyKey.trim()) errors.push('幂等键不能为空');
  if (!input.reference.trim()) errors.push('业务单号不能为空');
  if (!input.currency.trim()) errors.push('币种不能为空');
  if (input.amount === null || Number.isNaN(input.amount) || input.amount <= 0) {
    errors.push('付款金额必须大于 0');
  }
  if (!input.counterpartyName.trim()) errors.push('收款方名称不能为空');
  return errors;
}

/** 校验收款登记表单。 */
export function validateReceipt(input: {
  idempotencyKey: string;
  reference: string;
  currency: string;
  amount: number | null;
  counterpartyName: string;
}): string[] {
  return validatePayment(input);
}

/** 校验汇率查询表单。 */
export function validateRate(input: { baseCurrency: string; quoteCurrency: string }): string[] {
  const errors: string[] = [];
  if (!input.baseCurrency.trim()) errors.push('基准币种不能为空');
  if (!input.quoteCurrency.trim()) errors.push('报价币种不能为空');
  if (
    input.baseCurrency.trim() &&
    input.baseCurrency.trim().toUpperCase() === input.quoteCurrency.trim().toUpperCase()
  ) {
    errors.push('基准币种与报价币种不能相同');
  }
  return errors;
}

/**
 * 未接通的服务商该怎么显示。
 *
 * <p>🔴 显示成「未接入（归 18.2.2 支付与资金）」+ **一句说清现在怎么办的话**，
 * 而不是把那一格藏起来 —— 藏起来等于「这个功能不存在」，而真相是
 * 「外部通道还没接，但账单导入这条路今天就能用」。
 */
export function unavailableHint(availability: PaymentProviderAvailability): string {
  return `未接入（归 ${availability.ownerModule}）：${availability.reason}`;
}
