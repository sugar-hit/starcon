import type {
  FinanceVoucherCapabilities,
  FinanceVoucherDetail,
  FinanceVoucherGenerateInput,
  FinanceVoucherInput,
  FinanceVoucherMappingInput,
  FinanceVoucherMappingRow,
  FinanceVoucherOptions,
  FinanceVoucherPage,
  FinanceVoucherPeriodCloseInput,
  FinanceVoucherPeriodInput,
  FinanceVoucherPeriodRow,
  FinanceVoucherPostInput,
  FinanceVoucherResult,
  FinanceVoucherReverseInput,
  FinanceVoucherRow,
  FinanceVoucherVoidInput,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.5.2.1 凭证管理的前端模型。
 *
 * <p>蓝图 12-finance.md：「<b>生成与管理会计凭证</b>：由业务事件自动生成或手工录入，支持过账、校验（至少两行、金额有效、期间开放、借贷平衡）与红冲；已过账凭证不得反过账后改写，导出标准凭证文件。」输出：「<b>凭证 + 分录行 + 过账状态；审计记录</b>。」关系：「<b>过账后不可改行，只能红冲</b>；过账事务要锁凭证头并在数据库层防护。」
 *
 * <p><b>命名空间</b>：{@code finance/vouchers}，与 12.2.2.2 的 {@code finance/payment-executions}、
 * 12.4.2.1 的 {@code finance/cost-collections} <b>刻意不共用前缀</b> —— 那是别的模块的命名空间。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>已过账 = 不可改，只能红冲</b>：页面**不提供**「编辑已过账凭证」的入口，
 *       只提供「红冲」（生成一张反向凭证）。红冲出来的凭证**原样列出**，
 *       让人看得见「冲掉了哪一张、冲了多少」。</li>
 *   <li><b>金额只原样展示</b>：合计、占比都由服务端算好，页面<b>不做任何业务算术</b>
 *       （USD 与 EUR 加起来不是任何一个金额）。</li>
 *   <li><b>币种分开</b>：每一处汇总都按币种渲染，绝不把不同币种摆进同一列相加。</li>
 *   <li><b>阻断原因要看得见</b>：服务端拒绝时（借贷不平 / 期间已关闭 / 版本冲突），
 *       页面把<b>服务端给的那句话</b>显示出来，而不是把按钮灰掉让人猜。</li>
 *   <li><b>科目是软引用</b>：12.5.1.1 会计科目未交付 ⇒ 页面**不假装**科目是下拉可选的，
 *       它就是两个文本框（编码 + 名称），并在页面上明确标出这一点。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有凭证」。</li>
 * </ul>
 */

const base = 'finance/vouchers';

/**
 * 凭证列表的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface VoucherParams {
  status?: string | undefined;
  sourceType?: string | undefined;
  currency?: string | undefined;
  periodId?: string | undefined;
  dateFrom?: string | undefined;
  dateTo?: string | undefined;
  keyword?: string | undefined;
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
export function fetchVoucherCapabilities() {
  return api<FinanceVoucherCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（全部来自真实数据）。 */
export function fetchVoucherOptions() {
  return api<FinanceVoucherOptions>(`${base}/options`);
}

/** 凭证列表（真分页 + 真筛选 + 按币种分开的汇总）。 */
export function fetchVouchers(params: VoucherParams = {}, page = 0, size = 50) {
  return api<FinanceVoucherPage>(
    `${base}${query({
      status: params.status,
      sourceType: params.sourceType,
      currency: params.currency,
      periodId: params.periodId,
      dateFrom: params.dateFrom,
      dateTo: params.dateTo,
      keyword: params.keyword,
      page,
      size,
    })}`,
  );
}

/** 一张凭证的详情（凭证 + 分录行 + 审计留痕）。 */
export function fetchVoucher(id: string) {
  return api<FinanceVoucherDetail>(`${base}/${id}`);
}

/** 手工录入一张凭证（草稿）。 */
export function createVoucher(input: FinanceVoucherInput) {
  return api<FinanceVoucherResult>(`${base}`, 'POST', input);
}

/** 修改一张**草稿**凭证（乐观并发：必须带 expectedVersion）。 */
export function updateVoucher(id: string, input: FinanceVoucherInput) {
  return api<FinanceVoucherResult>(`${base}/${id}`, 'PUT', input);
}

/**
 * 过账。
 *
 * <p>服务端在**锁住凭证头**的事务里校验四条（至少两行 / 金额有效 / 期间开放 / 借贷平衡）。
 */
export function postVoucher(id: string, input: FinanceVoucherPostInput) {
  return api<FinanceVoucherResult>(`${base}/${id}/post`, 'POST', input);
}

/** 红冲一张**已过账**的凭证（生成一张反向凭证）。 */
export function reverseVoucher(id: string, input: FinanceVoucherReverseInput) {
  return api<FinanceVoucherResult>(`${base}/${id}/reverse`, 'POST', input);
}

/** 作废一张**草稿**凭证。 */
export function voidVoucher(id: string, input: FinanceVoucherVoidInput) {
  return api<FinanceVoucherResult>(`${base}/${id}/void`, 'POST', input);
}

/**
 * 由业务事件**自动生成**一张凭证（草稿）。
 *
 * <p>科目来自租户自己登记的**业务事件映射**；没有登记 ⇒ 服务端如实报错，页面原样显示。
 */
export function generateVoucher(input: FinanceVoucherGenerateInput) {
  return api<FinanceVoucherResult>(`${base}/generate`, 'POST', input);
}

/** 导出标准凭证文件（CSV / JSON；只含已过账凭证）。 */
export function fetchVoucherExport(params: {
  format?: string | undefined;
  periodId?: string | undefined;
  dateFrom?: string | undefined;
  dateTo?: string | undefined;
}) {
  return api<string>(
    `${base}/export${query({
      format: params.format ?? 'CSV',
      periodId: params.periodId,
      dateFrom: params.dateFrom,
      dateTo: params.dateTo,
    })}`,
  );
}

// ================================================================ 会计期间

/** 会计期间列表（真实数据）。 */
export function fetchVoucherPeriods() {
  return api<FinanceVoucherPeriodRow[]>(`${base}/periods`);
}

/** 登记一个会计期间。 */
export function createVoucherPeriod(input: FinanceVoucherPeriodInput) {
  return api<FinanceVoucherPeriodRow>(`${base}/periods`, 'POST', input);
}

/**
 * 关闭一个会计期间。
 *
 * <p>⚠️ **只能关、不能重开**：重开已关期间是高风险操作且必须审批留痕（属 12.5.1.2，未交付）。
 */
export function closeVoucherPeriod(id: string, input: FinanceVoucherPeriodCloseInput) {
  return api<FinanceVoucherPeriodRow>(`${base}/periods/${id}/close`, 'POST', input);
}

// ================================================================ 业务事件映射

/** 业务事件 → 借贷科目映射列表。 */
export function fetchVoucherMappings() {
  return api<FinanceVoucherMappingRow[]>(`${base}/mappings`);
}

/** 登记一条映射（系统不预置任何一行）。 */
export function createVoucherMapping(input: FinanceVoucherMappingInput) {
  return api<FinanceVoucherMappingRow>(`${base}/mappings`, 'POST', input);
}

/** 修改一条映射。 */
export function updateVoucherMapping(id: string, input: FinanceVoucherMappingInput) {
  return api<FinanceVoucherMappingRow>(`${base}/mappings/${id}`, 'PUT', input);
}

// ================================================================ 可读形态

/** 凭证状态的可读名。 */
export function voucherStatusLabel(status: string): string {
  switch (status) {
    case 'DRAFT':
      return '草稿';
    case 'POSTED':
      return '已过账';
    case 'REVERSED':
      return '已红冲';
    case 'VOID':
      return '已作废';
    default:
      return status;
  }
}

/**
 * 凭证状态的可读**语气**。
 *
 * <p>🔴 「已红冲」与「已过账」**不是**同一种语气：红冲意味着这张凭证的金额已经被
 * 另一张反向凭证冲掉了，报表口径不再计入它 —— 用「成功」的绿色会让人以为它还在账上。
 */
export function voucherStatusTone(status: string): 'neutral' | 'info' | 'success' | 'warning' | 'danger' {
  switch (status) {
    case 'DRAFT':
      return 'neutral';
    case 'POSTED':
      return 'success';
    case 'REVERSED':
      return 'warning';
    case 'VOID':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 来源类型的可读名。 */
export function voucherSourceTypeLabel(sourceType: string): string {
  switch (sourceType) {
    case 'MANUAL':
      return '手工录入';
    case 'SHIPMENT':
      return '发货';
    case 'RECEIPT':
      return '收货';
    case 'PAYMENT':
      return '收付款';
    case 'EXPENSE_ACCRUAL':
      return '费用计提';
    case 'REVERSAL':
      return '红冲';
    default:
      return sourceType;
  }
}

/** 借贷方向的可读名。 */
export function voucherLineTypeLabel(lineType: string): string {
  switch (lineType) {
    case 'DEBIT':
      return '借';
    case 'CREDIT':
      return '贷';
    default:
      return lineType;
  }
}

/** 审计事件类型的可读名。 */
export function voucherEventTypeLabel(eventType: string): string {
  switch (eventType) {
    case 'CREATED':
      return '创建';
    case 'UPDATED':
      return '修改';
    case 'AUTO_GENERATED':
      return '自动生成';
    case 'POSTED':
      return '过账';
    case 'REVERSAL_CREATED':
      return '生成红冲凭证';
    case 'REVERSED':
      return '已红冲';
    case 'VOIDED':
      return '作废';
    case 'EXPORTED':
      return '导出';
    case 'VALIDATION_FAILED':
      return '校验未通过';
    default:
      return eventType;
  }
}

/** 会计期间状态的可读名。 */
export function voucherPeriodStatusLabel(status: string): string {
  switch (status) {
    case 'OPEN':
      return '开放';
    case 'CLOSED':
      return '已关闭';
    default:
      return status;
  }
}

/**
 * 金额的可读形态。
 *
 * <p>⚠️ **不在前端做算术**：数值原样透传（只做显示格式化）。
 */
export function formatVoucherAmount(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '—';
  return amount.toFixed(4);
}

/** 金额 + 币种的可读形态。 */
export function formatVoucherMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '—';
  return `${amount.toFixed(4)} ${currency ?? ''}`.trim();
}

/** 数量的可读形态（数量是 numeric(19,6)，最多 6 位小数）。 */
export function formatVoucherQuantity(quantity: number | null | undefined): string {
  if (quantity === null || quantity === undefined) return '—';
  return String(quantity);
}

/** 期间区间的可读形态（只做拼接，不做日期差计算）。 */
export function formatVoucherPeriod(
  start: string | null | undefined,
  end: string | null | undefined,
): string {
  if (!start || !end) return '—';
  return `${start} ~ ${end}`;
}

/** 一张凭证现在**能不能**改（已过账的不可改，只能红冲）。 */
export function voucherEditable(voucher: FinanceVoucherRow | null): boolean {
  if (!voucher) return false;
  return voucher.status === 'DRAFT';
}

/** 一张凭证现在**能不能**红冲（只有已过账的能）。 */
export function voucherReversible(voucher: FinanceVoucherRow | null): boolean {
  if (!voucher) return false;
  return voucher.status === 'POSTED';
}

/** 一张凭证现在**能不能**作废（只有草稿能）。 */
export function voucherVoidable(voucher: FinanceVoucherRow | null): boolean {
  if (!voucher) return false;
  return voucher.status === 'DRAFT';
}
