import type {
  FinanceFundAccountBalanceInput,
  FinanceFundAccountBalancePage,
  FinanceFundAccountBalanceRow,
  FinanceFundAccountCapabilities,
  FinanceFundAccountCreateInput,
  FinanceFundAccountDetail,
  FinanceFundAccountOptions,
  FinanceFundAccountPage,
  FinanceFundAccountRow,
  FinanceFundAccountUpdateInput,
  FinanceFundCurrencyBalanceSummary,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.7.1.1 账户管理的前端模型。
 *
 * <p>蓝图 12-finance.md：「管理资金账户：银行账户、支付平台账户、平台备付金账户，记录币种、账号、余额、开户主体（法人）、账户类型。」输入：「账户定义；余额同步或手工更新；关联法人。」输出：「账户档案 + 余额历史。」关系：「上游 = 17 法人；下游 = 12.2 收付款、12.7.1.2 对账。<b>账户必须绑定法人</b>，跨法人混用资金账户会导致收入归属错误。」
 *
 * <p><b>命名空间</b>：{@code finance/fund-accounts}，与同 L3 的 12.7.2.1 资金分析
 * （{@code finance/fund-analysis}）、12.2.1.1 收款单（{@code finance/receipts}）
 * **刻意不共用前缀** —— 那是别的模块的命名空间，不要互相调用。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>金额只原样展示</b>：页面**不做任何业务算术**。USD 与 EUR 加起来不是任何一个金额，
 *       跨币种合计由服务端明确给「算不出」（{@code crossCurrencyTotal} 为 null）。</li>
 *   <li><b>币种分开</b>：汇总按币种分行渲染，**绝不**把不同币种摆进同一列相加。</li>
 *   <li><b>账号脱敏</b>：掩码规则（{@code 6222****5678}）**只有服务端一处实现**，
 *       页面**不**自己算掩码 —— 两处实现迟早会分叉，显示出来的掩码会跟库里存的不一样。</li>
 *   <li><b>余额历史是事实</b>：页面把「改前 → 改后」与理由、来源、操作人**都显示出来**，
 *       不让人以为余额是可以随便改的当前值。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有账户」。</li>
 * </ul>
 */

const base = 'finance/fund-accounts';

/** 账户列表的筛选条件。 */
export interface FundAccountParams {
  accountType?: string | undefined;
  currency?: string | undefined;
  legalEntityId?: string | undefined;
  status?: string | undefined;
  keyword?: string | undefined;
  sort?: string | undefined;
  direction?: string | undefined;
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

/** 能力声明：哪些真接通、哪些没交付、归谁。 */
export function fetchFundAccountCapabilities() {
  return api<FinanceFundAccountCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（账户类型词表 + 真实币种 + identity 的法人清单）。 */
export function fetchFundAccountOptions() {
  return api<FinanceFundAccountOptions>(`${base}/options`);
}

/** 账户列表（真分页 + 真筛选）。 */
export function fetchFundAccounts(params: FundAccountParams = {}, page = 0, size = 20) {
  return api<FinanceFundAccountPage>(
    `${base}${query({
      accountType: params.accountType,
      currency: params.currency,
      legalEntityId: params.legalEntityId,
      status: params.status,
      keyword: params.keyword,
      sort: params.sort,
      direction: params.direction,
      page,
      size,
    })}`,
  );
}

/** 账户详情（档案 + 最近若干条余额历史）。 */
export function fetchFundAccount(id: string) {
  return api<FinanceFundAccountDetail>(`${base}/${id}`);
}

/**
 * 建账户。
 *
 * <p>⚠️ 法人由**服务端实时校验**（未登记的法人会被拒），前端不预判。
 */
export function createFundAccount(input: FinanceFundAccountCreateInput) {
  return api<FinanceFundAccountRow>(`${base}`, 'POST', input);
}

/**
 * 改账户档案。
 *
 * <p>⚠️ 币种与账户编号**不可改**（单账户单币种是硬约束）⇒ 输入里没有这两项。
 */
export function updateFundAccount(id: string, input: FinanceFundAccountUpdateInput) {
  return api<FinanceFundAccountRow>(`${base}/${id}`, 'PUT', input);
}

/**
 * 登记一次余额（同步 / 手工更新）。
 *
 * <p>⚠️ 服务端会**追加**一条余额历史；手工更新没写理由会被拒。
 */
export function recordFundAccountBalance(id: string, input: FinanceFundAccountBalanceInput) {
  return api<FinanceFundAccountBalanceRow>(`${base}/${id}/balances`, 'POST', input);
}

/** 某个账户的余额历史（真分页）。 */
export function fetchFundAccountBalances(id: string, page = 0, size = 20) {
  return api<FinanceFundAccountBalancePage>(`${base}/${id}/balances${query({ page, size })}`);
}

/** 余额汇总（**按币种分组**；跨币种合计恒为 null）。 */
export function fetchFundAccountBalanceSummary() {
  return api<FinanceFundCurrencyBalanceSummary>(`${base}/balances/summary`);
}

/** 停用账户（不再接受新的余额登记；资金凭据不做物理删除）。 */
export function deactivateFundAccount(id: string) {
  return api<FinanceFundAccountRow>(`${base}/${id}/deactivate`, 'POST');
}

/** 启用账户。 */
export function activateFundAccount(id: string) {
  return api<FinanceFundAccountRow>(`${base}/${id}/activate`, 'POST');
}

// ================================================================ 可读形态

/** 账户类型的可读名（服务端已给 label，这里只兜底）。 */
export function fundAccountTypeLabel(accountType: string, fallback?: string): string {
  switch (accountType) {
    case 'BANK':
      return '银行账户';
    case 'PAYMENT_PLATFORM':
      return '支付平台账户';
    case 'PLATFORM_RESERVE':
      return '平台备付金账户';
    default:
      return fallback ?? accountType;
  }
}

/** 账户状态的可读名。 */
export function fundAccountStatusLabel(status: string, fallback?: string): string {
  switch (status) {
    case 'ACTIVE':
      return '启用';
    case 'INACTIVE':
      return '停用';
    default:
      return fallback ?? status;
  }
}

/** 余额来源的可读名。 */
export function fundBalanceSourceLabel(source: string, fallback?: string): string {
  switch (source) {
    case 'INITIAL':
      return '建档初始';
    case 'SYNC':
      return '余额同步';
    case 'MANUAL':
      return '手工更新';
    default:
      return fallback ?? source;
  }
}

/**
 * 金额的可读形态。
 *
 * <p>⚠️ **不在前端做算术**：数值原样透传（只做显示格式化）。
 */
export function formatFundAccountAmount(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '—';
  return amount.toFixed(4);
}

/** 金额 + 币种的可读形态。 */
export function formatFundAccountMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '—';
  return `${amount.toFixed(4)} ${currency ?? ''}`.trim();
}

/**
 * 账号的可读形态。
 *
 * <p>⚠️ 这里**不**重新实现掩码规则 —— 服务端返回的 {@code accountNumberMasked}
 * 已经是唯一的真相。页面直接显示它。
 */
export function formatFundAccountNumber(
  masked: string | null | undefined,
  tail: string | null | undefined,
): string {
  if (!masked) return '—';
  return tail ? `${masked}（后四位 ${tail}）` : masked;
}
