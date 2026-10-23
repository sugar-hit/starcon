import type {
  FinancePayableCapabilities,
  FinancePayableCurrencySummary,
  FinancePayableDetail,
  FinancePayableImportResult,
  FinancePayableInput,
  FinancePayableOptions,
  FinancePayablePage,
  FinancePayablePolicyInput,
  FinancePayablePolicyView,
  FinancePayableRow,
  FinancePayableUnpaidList,
  FinancePrepaymentAllocationInput,
  FinancePrepaymentDetail,
  FinancePrepaymentInput,
  FinancePrepaymentPage,
  FinancePrepaymentRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.1.2.1 应付单管理的前端模型。
 *
 * <p>蓝图 12-finance.md：「管理应付账款：来源（采购收货、供应商对账、物流费用、平台费用、其他）、
 * 应付对象、金额与币种、到期日、已付金额与未付余额。」输出：「应付单 + 明细 + 未付清单。」
 * 加粗句：「<b>应付、预付款与采购承诺分开建模</b>；<b>确认依据由业务合同和已批准政策决定，
 * 不能仅凭下单或收货统一推导</b>。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>金额只原样展示</b>：合计、逾期额、未付余额都由**服务端**算好，
 *       页面**不做任何前端算术**（USD 与 EUR 加起来不是任何一个金额）。</li>
 *   <li><b>币种分开</b>：汇总、未付清单、预付款余额一律按币种分组渲染，
 *       **绝不**把不同币种摆进同一列相加。</li>
 *   <li><b>「未配置」与「已禁用」是两件事</b>：应付确认政策里，没配过（你还没决定）
 *       与配了但关掉（你决定了这个来源不产生应付）必须分开显示。</li>
 *   <li><b>上游没交付的事就说没交付</b>：供应商对账单（财务侧确认属 12.1.2.3）与
 *       平台费用（12.3.1.1 结算）当前**不可产生**，页面**不提供**用它们建单的入口，
 *       只在能力声明里如实报出归属模块。</li>
 *   <li><b>已付相关一律只读</b>：付款（12.2.2.1）**未交付**，页面只显示已付金额与未付余额，
 *       **不提供**任何「标记已付」的按钮 —— 那个入口一旦存在就是一条假的付款路径。</li>
 *   <li><b>预付款是另一个入口</b>：它有自己的前缀与页面区段，
 *       页面**不**把它画成应付单的一个 tab —— 蓝图要求分开建模，界面也分开。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有应付单」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code finance/payables} 与 {@code finance/prepayments}，
 * 与同服务的 {@code finance/receivables}（12.1.1.1）以及卷 12 的其余模块各自独立前缀。
 */

const base = 'finance/payables';
const prepaymentBase = 'finance/prepayments';

/**
 * 筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface PayableParams {
  status?: string | undefined;
  source?: string | undefined;
  currency?: string | undefined;
  counterpartyRef?: string | undefined;
  overdueOnly?: boolean | undefined;
  dueFrom?: string | undefined;
  dueTo?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 预付款筛选条件。 */
export interface PrepaymentParams {
  status?: string | undefined;
  currency?: string | undefined;
  counterpartyRef?: string | undefined;
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
export function fetchPayableCapabilities() {
  return api<FinancePayableCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（全部来自真实数据）。 */
export function fetchPayableOptions() {
  return api<FinancePayableOptions>(`${base}/options`);
}

/** 应付单列表：真分页 + 真筛选 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。 */
export function fetchPayables(params: PayableParams = {}) {
  return api<FinancePayablePage>(`${base}${query({ ...params })}`);
}

/** 未付清单（12.1.2.1 的输出之一）：按币种分开的小计 + 明细行。 */
export function fetchPayableUnpaidList() {
  return api<FinancePayableUnpaidList>(`${base}/unpaid`);
}

/** 应付单详情：单头 + 明细行 + 事件流。 */
export function fetchPayable(id: string) {
  return api<FinancePayableDetail>(`${base}/${encodeURIComponent(id)}`);
}

/**
 * 人工录入一张应付单。
 *
 * <p>⚠️ 只有「人工录入」与「其他」能手工建单：采购收货 / 物流费用 / 广告费三个来源
 * **只能**由上游事实产生（手工录入等于自己编一条上游事实）。
 */
export function createPayable(input: FinancePayableInput) {
  return api<FinancePayableRow>(`${base}`, 'POST', input);
}

/**
 * 从上游（6.3.2.2 到货跟踪的应付部分确认）导入应付，并把应付单号写回上游。
 *
 * <p>⚠️ 上游读不到时后端**整体失败**（503），不会返回「导入 0 条」。
 * 页面必须把失败显式显示出来，绝不能让它看起来像「跑过了但一条都没有」。
 */
export function importPayablesFromPurchaseArrivals() {
  return api<FinancePayableImportResult>(`${base}/import-purchase-arrivals`, 'POST');
}

/** 从上游（10.4.1.1 头程发运单）导入物流费用应付。 */
export function importPayablesFromFirstMile() {
  return api<FinancePayableImportResult>(`${base}/import-first-mile`, 'POST');
}

/** 从上游（14.3.1.1 活动报表）导入广告费应付。 */
export function importPayablesFromAdSpend() {
  return api<FinancePayableImportResult>(`${base}/import-ad-spend`, 'POST');
}

/** 作废（红冲的等价物）：终态，整单冻结。已付的不可作废。 */
export function voidPayable(id: string, reason: string) {
  return api<FinancePayableRow>(`${base}/${encodeURIComponent(id)}/void`, 'POST', { reason });
}

/** 应付确认政策：每个（来源 × 义务类型）全列（含未配置的）。 */
export function fetchPayablePolicies() {
  return api<FinancePayablePolicyView[]>(`${base}/policies`);
}

/** 配置一个（来源 × 义务类型）的确认政策（幂等 upsert）。 */
export function updatePayablePolicy(
  source: string,
  obligationKind: string,
  input: FinancePayablePolicyInput,
) {
  return api<FinancePayablePolicyView>(
    `${base}/policies/${encodeURIComponent(source)}?obligationKind=${encodeURIComponent(obligationKind)}`,
    'PUT',
    { ...input },
  );
}

// ================================================================ 预付款（独立聚合）

/** 预付款列表：真分页 + 真筛选 + 按币种分组的可抵扣余额。 */
export function fetchPrepayments(params: PrepaymentParams = {}) {
  return api<FinancePrepaymentPage>(`${prepaymentBase}${query({ ...params })}`);
}

/** 预付款详情：单头 + 抵扣明细。 */
export function fetchPrepayment(id: string) {
  return api<FinancePrepaymentDetail>(`${prepaymentBase}/${encodeURIComponent(id)}`);
}

/**
 * 登记一笔预付款。
 *
 * <p>⚠️ 它**不产生任何「已付款」回执**：登记的是「这笔钱预付给谁、约定什么时候付」，
 * 实际付款走 12.2.2.1（未交付）。
 */
export function createPrepayment(input: FinancePrepaymentInput) {
  return api<FinancePrepaymentRow>(`${prepaymentBase}`, 'POST', input);
}

/** 登记一笔预付款抵扣（**不改**应付单的已付金额 —— 那是 12.2.2.1 的写入口）。 */
export function allocatePrepayment(
  prepaymentId: string,
  input: FinancePrepaymentAllocationInput,
) {
  return api<FinancePrepaymentDetail>(
    `${prepaymentBase}/${encodeURIComponent(prepaymentId)}/allocations`,
    'POST',
    input,
  );
}

/** 作废预付款。已有抵扣的不可作废（撤销抵扣属 12.2.2.1 域，未交付）。 */
export function voidPrepayment(id: string, reason: string) {
  return api<FinancePrepaymentRow>(`${prepaymentBase}/${encodeURIComponent(id)}/void`, 'POST', {
    reason,
  });
}

// ================================================================ 可读形态

/** 应付来源的可读名。 */
export function payableSourceLabel(source: string): string {
  switch (source) {
    case 'PURCHASE_RECEIPT':
      return '采购收货';
    case 'LOGISTICS_FEE':
      return '物流费用';
    case 'ADVERTISING_FEE':
      return '广告费';
    case 'SUPPLIER_STATEMENT':
      return '供应商对账单';
    case 'PLATFORM_FEE':
      return '平台费用';
    case 'MANUAL':
      return '人工录入';
    case 'OTHER':
      return '其他';
    default:
      return source;
  }
}

/**
 * 应付状态的可读名。
 *
 * <p>⚠️ 「未付」与「部分已付」必须分开：后者说明**已经付出去一部分**，
 * 合成一个「未付清」会让财务以为一分没付。
 */
export function payableStatusLabel(status: string): string {
  switch (status) {
    case 'OPEN':
      return '未付';
    case 'PARTIALLY_PAID':
      return '部分已付';
    case 'PAID':
      return '已付清';
    case 'VOID':
      return '已作废';
    case 'DRAFT':
      return '草稿';
    default:
      return status;
  }
}

/** 应付状态的可读色调。 */
export function payableStatusTone(status: string): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'OPEN':
      return 'warning';
    case 'PARTIALLY_PAID':
      return 'warning';
    case 'PAID':
      return 'success';
    case 'VOID':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 义务类型的可读名（应付 / 预付款 / 采购承诺 —— 三者在词表上分开）。 */
export function obligationKindLabel(kind: string): string {
  switch (kind) {
    case 'PAYABLE':
      return '应付';
    case 'PREPAYMENT':
      return '预付款';
    case 'COMMITMENT':
      return '采购承诺';
    default:
      return kind;
  }
}

/** 应付对象类型的可读名。 */
export function counterpartyTypeLabel(type: string): string {
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

/** 预付款状态的可读名。 */
export function prepaymentStatusLabel(status: string): string {
  switch (status) {
    case 'OPEN':
      return '未抵扣';
    case 'PARTIALLY_ALLOCATED':
      return '部分抵扣';
    case 'ALLOCATED':
      return '已抵扣完';
    case 'VOID':
      return '已作废';
    default:
      return status;
  }
}

/** 预付款状态的可读色调。 */
export function prepaymentStatusTone(status: string): 'success' | 'warning' | 'danger' | 'neutral' {
  switch (status) {
    case 'OPEN':
      return 'warning';
    case 'PARTIALLY_ALLOCATED':
      return 'warning';
    case 'ALLOCATED':
      return 'success';
    case 'VOID':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 事件类型的可读名。 */
export function payableEventLabel(eventType: string): string {
  switch (eventType) {
    case 'CREATED':
      return '建单';
    case 'CONFIRMED':
      return '确认';
    case 'IMPORTED':
      return '从上游导入';
    case 'CONFIRMED_UPSTREAM':
      return '已回写上游确认';
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
 * 「未登记」而不是 0 —— 两者含义不同。
 */
export function formatPayableAmount(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '未登记';
  return amount.toFixed(4);
}

/** 金额 + 币种的可读形态。 */
export function formatPayableMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '未登记';
  return `${amount.toFixed(4)} ${currency ?? ''}`.trim();
}

/**
 * 应付确认政策的状态可读形态。
 *
 * <p>🔴 **「未配置」与「已禁用」是两件不同的事**，这里刻意用不同的文案：
 * 把它们合成一个「否」会让「你还没决定」看起来像「你决定了这个来源不产生应付」。
 */
export function payablePolicyStateLabel(policy: FinancePayablePolicyView): string {
  if (policy.obligationKind === 'COMMITMENT') return '本服务不建模';
  if (!policy.configured) return '未配置';
  if (!policy.enabled) return '已禁用';
  return policy.dueDays > 0 ? `已启用 · 账期 ${policy.dueDays} 天` : '已启用 · 确认即到期';
}

/** 政策状态的可读色调。 */
export function payablePolicyStateTone(
  policy: FinancePayablePolicyView,
): 'success' | 'warning' | 'danger' | 'neutral' {
  if (policy.obligationKind === 'COMMITMENT') return 'neutral';
  if (!policy.configured) return 'neutral';
  if (!policy.enabled) return 'danger';
  if (!policy.producible) return 'warning';
  return 'success';
}

/**
 * 上游来源的可读形态（谁产出了这张应付）。
 *
 * <p>⚠️ 「人工录入」与「从上游导入」必须能分开看 ——
 * 财务要知道这张应付是谁给的。
 */
export function payableSourceSystemLabel(sourceSystem: string): string {
  switch (sourceSystem) {
    case 'manual':
      return '人工录入';
    case 'trade':
      return '从到货应付确认导入';
    case 'warehouse':
      return '从头程发运单导入';
    case 'channel':
      return '从广告花费导入';
    default:
      return sourceSystem;
  }
}

/**
 * 逾期提示（**不**在前端算日期差 —— 后端已经分好桶并给了基准日）。
 */
export function payableOverdueHint(row: FinancePayableRow, asOfDate: string): string {
  if (row.status === 'PAID' || row.status === 'VOID') return '—';
  if (row.dueDate >= asOfDate) return '未到期';
  return `逾期中（到期日 ${row.dueDate}）`;
}

/**
 * 这个来源**当前**能不能产生应付（与后端同口径）。
 *
 * <p>⚠️ 页面用它决定「要不要给出建单入口」——
 * 给一个上游未交付的来源放建单入口，就是让界面以为功能已交付。
 */
export function payableSourceProducible(source: string): boolean {
  return (
    source === 'PURCHASE_RECEIPT' ||
    source === 'LOGISTICS_FEE' ||
    source === 'ADVERTISING_FEE' ||
    source === 'MANUAL' ||
    source === 'OTHER'
  );
}

/**
 * 汇总行的可读形态（**按币种一行**，绝不合并）。
 */
export function payableSummaryLabel(summary: FinancePayableCurrencySummary): string {
  return `${summary.currency}：未付 ${formatPayableAmount(summary.unpaidAmount)}`;
}
