import type {
  FinanceBadDebtAction,
  FinanceBadDebtCapabilities,
  FinanceBadDebtCurrencyTotal,
  FinanceBadDebtDecisionInput,
  FinanceBadDebtDetail,
  FinanceBadDebtEvidenceInput,
  FinanceBadDebtEvidenceTypeOption,
  FinanceBadDebtOptions,
  FinanceBadDebtPage,
  FinanceBadDebtReasonTypeOption,
  FinanceBadDebtRow,
  FinanceBadDebtStatusOption,
  FinanceBadDebtSummary,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.1.1.3 坏账与核销的前端模型。
 *
 * <p>蓝图 12-finance.md：「处理坏账：争议损失确认、买家拒付、平台冻结无法收回的核销，保留核销依据与审批。」输入：「核销申请；依据材料；审批。」输出：「坏账核销记录 + 会计处理 + 审计留痕。」
 *
 * <p><b>命名空间</b>：{@code finance/bad-debt}，与 12.1.1.1 的 {@code finance/receivables}、
 * 12.1.1.2 的 {@code finance/receivable-aging} **刻意不共用前缀** —— 那是别的模块的命名空间。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>金额只原样展示</b>：核销金额、未核销余额、汇总都由**服务端**算好，
 *       页面**不做任何业务算术**（USD 与 EUR 加起来不是任何一个金额）。</li>
 *   <li><b>币种分开</b>：汇总按币种分组渲染，**绝不**把不同币种摆进同一列相加。</li>
 *   <li><b>依据链与审批是终审动作</b>：提交审批前必须有依据材料；审批人 ≠ 提交人；
 *       审批意见必填。页面据此把「为什么不能做」说清楚，而不是把按钮灰掉让人猜。</li>
 *   <li><b>会计处理不可用要看得见</b>：下游 12.5.2.1 未交付 ⇒ 页面显示「归属模块 12.5.2.1 /
 *       凭证号 —」，**不显示**一个 0 冒充已入账。</li>
 *   <li><b>没有「一键批量核销」入口</b>：蓝图写死不允许批量一键核销无审批。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有坏账核销申请」。</li>
 * </ul>
 */

const base = 'finance/bad-debt';

/**
 * 申请单列表的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface BadDebtParams {
  status?: string | undefined;
  reasonType?: string | undefined;
  currency?: string | undefined;
  receivableId?: string | undefined;
  disputeId?: string | undefined;
  shopId?: string | undefined;
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

/** 能力声明：上游真接通 / 下游缺口归谁 / 「会计处理不可用」的归属模块。 */
export function fetchBadDebtCapabilities() {
  return api<FinanceBadDebtCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（状态 / 事由 / 币种 / 店铺 / 依据类型）。 */
export function fetchBadDebtOptions() {
  return api<FinanceBadDebtOptions>(`${base}/options`);
}

/** 坏账汇总：按状态 / 事由 / 币种。 */
export function fetchBadDebtSummary() {
  return api<FinanceBadDebtSummary>(`${base}/summary`);
}

/** 申请单列表：真分页 + 真筛选 + 真排序 + 汇总（汇总覆盖整个筛选结果集）。 */
export function fetchBadDebtCases(params: BadDebtParams = {}, page = 0, size = 50) {
  return api<FinanceBadDebtPage>(`${base}${query({ ...params, page, size })}`);
}

/** 申请单详情：单头 + 依据材料 + 审计留痕 + 当前可执行动作。 */
export function fetchBadDebtDetail(id: string) {
  return api<FinanceBadDebtDetail>(`${base}/${id}`);
}

/** 提交坏账核销申请（草稿）。 */
export function createBadDebtCase(input: {
  receivableId: string;
  writeOffAmount: number;
  reasonType: string;
  reasonDetail: string;
  disputeId?: string | undefined;
  note?: string | undefined;
}) {
  return api<FinanceBadDebtRow>(base, 'POST', input);
}

/** 加一条依据材料。 */
export function addBadDebtEvidence(id: string, input: FinanceBadDebtEvidenceInput) {
  return api<FinanceBadDebtDetail>(`${base}/${id}/evidence`, 'POST', input);
}

/**
 * 移除一条依据材料。
 *
 * <p>⚠️ 走 **POST**（软移除，服务端把 status 置 'REMOVED'）：finance 的 API 出口
 * **刻意不导出 DELETE**（应收单是凭据，错了只能作废不能删）—— 本模块不越界改那个约定。
 */
export function removeBadDebtEvidence(id: string, evidenceId: string) {
  return api<FinanceBadDebtDetail>(`${base}/${id}/evidence/${evidenceId}/remove`, 'POST', {});
}

/** 提交审批。**必须**已有至少一条依据材料。 */
export function submitBadDebtCase(id: string) {
  return api<FinanceBadDebtRow>(`${base}/${id}/submit`, 'POST', {});
}

/** 审批通过。**审批人 ≠ 提交人**，**审批意见必填**。 */
export function approveBadDebtCase(id: string, input: FinanceBadDebtDecisionInput) {
  return api<FinanceBadDebtRow>(`${base}/${id}/approve`, 'POST', input);
}

/** 审批驳回。**审批意见必填**。 */
export function rejectBadDebtCase(id: string, input: FinanceBadDebtDecisionInput) {
  return api<FinanceBadDebtRow>(`${base}/${id}/reject`, 'POST', input);
}

/**
 * 执行核销：**作废原应收单 + 为仍可收回的余额新建承接单**。
 *
 * <p>⚠️ **没有批量版本**：蓝图加粗句「不允许批量一键核销无审批」。
 */
export function writeOffBadDebtCase(id: string) {
  return api<FinanceBadDebtRow>(`${base}/${id}/write-off`, 'POST', {});
}

/** 作废申请。 */
export function voidBadDebtCase(id: string, reason: string) {
  return api<FinanceBadDebtRow>(`${base}/${id}/void`, 'POST', { reason });
}

// ================================================================ 可读形态

/** 申请状态的可读名（服务端下发的中文标签优先，取不到时回落到代码）。 */
export function badDebtStatusLabel(
  status: string,
  options: FinanceBadDebtStatusOption[] | undefined,
): string {
  return options?.find((o) => o.code === status)?.label ?? status;
}

/** 坏账事由的可读名。 */
export function badDebtReasonLabel(
  reasonType: string,
  options: FinanceBadDebtReasonTypeOption[] | undefined,
): string {
  return options?.find((o) => o.code === reasonType)?.label ?? reasonType;
}

/** 依据材料类型的可读名。 */
export function badDebtEvidenceTypeLabel(
  evidenceType: string,
  options: FinanceBadDebtEvidenceTypeOption[] | undefined,
): string {
  return options?.find((o) => o.code === evidenceType)?.label ?? evidenceType;
}

/**
 * 金额的可读形态。
 *
 * <p>⚠️ **不在前端做算术**：数值原样透传（只做显示格式化）。
 */
export function formatBadDebtAmount(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '—';
  return amount.toFixed(4);
}

/** 金额 + 币种的可读形态。 */
export function formatBadDebtMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '—';
  return `${amount.toFixed(4)} ${currency ?? ''}`.trim();
}

/**
 * 会计处理的可读形态。
 *
 * <p>⚠️ 下游 12.5.2.1「凭证管理」未交付 ⇒ 会计处理**不可用**。
 * 这里显示「归属模块 12.5.2.1 · 待凭证」，**不显示 0** ——
 * 「会计处理不可用」与「已入账 0 块」是两件事。
 */
export function badDebtAccountingLabel(row: FinanceBadDebtRow): string {
  // 12.5.2.1 落地前：凭证号恒为空串 ⇒ 显示「待凭证 + 归属模块」。
  // 落地后：凭证号有值 ⇒ 显示凭证号。两种情况都**不会**显示成「已入账 0 块」。
  if (row.accountingRef) return `凭证 ${row.accountingRef}`;
  if (row.accountingStatus === 'PENDING_FINANCE') {
    return `待凭证（归属模块 ${row.accountingOwnerModule}）`;
  }
  return '—';
}

/** 核销路径的可读名。 */
export function badDebtWriteOffMethodLabel(row: FinanceBadDebtRow): string {
  switch (row.writeOffMethod) {
    case 'VOID_AND_REPLACE':
      return '作废原单 + 新建承接单';
    default:
      return '—';
  }
}

/**
 * 一个动作能不能做 —— 以及**为什么不能做**。
 *
 * <p>⚠️ 服务端已经在 {@code actions[]} 里把「不可执行」和「不可执行的原因」都算好了，
 * 页面只做呈现，**不自己另算一套规则**。
 */
export function badDebtActionBlockedReason(action: FinanceBadDebtAction): string {
  if (action.allowed) return '';
  return action.blockedReason || '当前状态不允许这个动作';
}

/** 空态区分「筛选无结果」与「还没有申请」。 */
export function badDebtEmptyHint(total: number, filtered: boolean): string {
  if (filtered && total === 0) return '当前筛选条件下没有坏账核销申请。';
  return '还没有坏账核销申请。争议损失确认、买家拒付、平台冻结都可以在这里发起核销申请。';
}
