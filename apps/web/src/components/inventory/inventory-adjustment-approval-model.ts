import type {
  InventoryAdjustmentApprovalApprover,
  InventoryAdjustmentApprovalApproverInput,
  InventoryAdjustmentApprovalBatchInput,
  InventoryAdjustmentApprovalBatchResult,
  InventoryAdjustmentApprovalCapabilities,
  InventoryAdjustmentApprovalCase,
  InventoryAdjustmentApprovalCaseDetail,
  InventoryAdjustmentApprovalEvaluation,
  InventoryAdjustmentApprovalEvaluateInput,
  InventoryAdjustmentApprovalPendingPage,
  InventoryAdjustmentApprovalRule,
  InventoryAdjustmentApprovalRuleInput,
  InventoryAdjustmentApprovalStatusInput,
  InventoryAdjustmentApprovalSubmitInput,
  InventoryAdjustmentApprovalValuationPage,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 7.4.1.2 调整审批前端模型。
 *
 * <p>界面上最容易出错、本模块显式处理了几处：
 *
 * <ul>
 *   <li>**批准不改余额**，只有「过账」那一步改。界面上必须把这一点说出来，
 *       否则审批人会以为「已经调完账了」。
 *   <li>**金额是申报值**（warehouse 没有成本口径）。所以界面上要把「申报金额」
 *       与「审批人看得到的金额」讲清楚，而不是显示成一个看起来像系统算出来的数。
 *   <li>**审批人数没凑齐就批不动**。界面必须显示「已批 N/M」，否则审批人不知道自己批了没有用。
 *   <li>**不该由我批的也要显示出来**，标成不可批并写清原因 —— 直接不显示会让人以为「这单没人管」。
 *   <li>**批量逐条给结论**：一张失败不影响其它张，失败的那几条必须逐条列出原因。
 * </ul>
 */

// ------------------------------------------------------------ 接口

const base = 'warehouse/inventory/adjustment-approvals';

export function fetchApprovalCapabilities(): Promise<InventoryAdjustmentApprovalCapabilities> {
  return api<InventoryAdjustmentApprovalCapabilities>(`${base}/capabilities`);
}

export function fetchApprovalRules(): Promise<InventoryAdjustmentApprovalRule[]> {
  return api<InventoryAdjustmentApprovalRule[]>(`${base}/rules`);
}

export function createApprovalRule(
  input: InventoryAdjustmentApprovalRuleInput,
): Promise<InventoryAdjustmentApprovalRule> {
  return api<InventoryAdjustmentApprovalRule>(`${base}/rules`, 'POST', input);
}

export function updateApprovalRule(
  ruleId: string,
  input: InventoryAdjustmentApprovalRuleInput,
): Promise<InventoryAdjustmentApprovalRule> {
  return api<InventoryAdjustmentApprovalRule>(`${base}/rules/${ruleId}/update`, 'POST', input);
}

export function setApprovalRuleStatus(
  ruleId: string,
  status: string,
): Promise<InventoryAdjustmentApprovalRule> {
  return api<InventoryAdjustmentApprovalRule>(`${base}/rules/${ruleId}/status`, 'POST', {
    status,
  } satisfies InventoryAdjustmentApprovalStatusInput);
}

export function fetchApprovalApprovers(): Promise<InventoryAdjustmentApprovalApprover[]> {
  return api<InventoryAdjustmentApprovalApprover[]>(`${base}/approvers`);
}

export function registerApprovalApprover(
  input: InventoryAdjustmentApprovalApproverInput,
): Promise<InventoryAdjustmentApprovalApprover> {
  return api<InventoryAdjustmentApprovalApprover>(`${base}/approvers`, 'POST', input);
}

export function setApprovalApproverStatus(
  approverId: string,
  status: string,
): Promise<InventoryAdjustmentApprovalApprover> {
  return api<InventoryAdjustmentApprovalApprover>(`${base}/approvers/${approverId}/status`, 'POST', {
    status,
  } satisfies InventoryAdjustmentApprovalStatusInput);
}

export function fetchPendingApprovals(
  page = 0,
  size = 20,
): Promise<InventoryAdjustmentApprovalPendingPage> {
  return api<InventoryAdjustmentApprovalPendingPage>(`${base}/pending?page=${page}&size=${size}`);
}

export function evaluateApproval(
  input: InventoryAdjustmentApprovalEvaluateInput,
): Promise<InventoryAdjustmentApprovalEvaluation> {
  return api<InventoryAdjustmentApprovalEvaluation>(`${base}/evaluate`, 'POST', input);
}

export function submitForApproval(
  orderId: string,
  input: InventoryAdjustmentApprovalSubmitInput,
): Promise<InventoryAdjustmentApprovalCaseDetail> {
  return api<InventoryAdjustmentApprovalCaseDetail>(`${base}/${orderId}/submit`, 'POST', input);
}

export function fetchApprovalCase(orderId: string): Promise<InventoryAdjustmentApprovalCaseDetail> {
  return api<InventoryAdjustmentApprovalCaseDetail>(`${base}/${orderId}`);
}

export function approveAdjustmentOrder(
  orderId: string,
  note: string,
): Promise<InventoryAdjustmentApprovalCaseDetail> {
  return api<InventoryAdjustmentApprovalCaseDetail>(`${base}/${orderId}/approve`, 'POST', { note });
}

export function rejectAdjustmentOrder(
  orderId: string,
  note: string,
): Promise<InventoryAdjustmentApprovalCaseDetail> {
  return api<InventoryAdjustmentApprovalCaseDetail>(`${base}/${orderId}/reject`, 'POST', { note });
}

export function batchApproveAdjustments(
  input: InventoryAdjustmentApprovalBatchInput,
): Promise<InventoryAdjustmentApprovalBatchResult> {
  return api<InventoryAdjustmentApprovalBatchResult>(`${base}/batch/approve`, 'POST', input);
}

export function batchRejectAdjustments(
  input: InventoryAdjustmentApprovalBatchInput,
): Promise<InventoryAdjustmentApprovalBatchResult> {
  return api<InventoryAdjustmentApprovalBatchResult>(`${base}/batch/reject`, 'POST', input);
}

export function fetchApprovalValuations(
  status?: string,
  page = 0,
  size = 20,
): Promise<InventoryAdjustmentApprovalValuationPage> {
  const query = status ? `&status=${encodeURIComponent(status)}` : '';
  return api<InventoryAdjustmentApprovalValuationPage>(
    `${base}/valuations?page=${page}&size=${size}${query}`,
  );
}

// ------------------------------------------------------------ 展示口径

/** 审批层级的中文名。 */
export function tierLabel(tier: string | null | undefined): string {
  switch (tier) {
    case 'L1':
      return 'L1 一级';
    case 'L2':
      return 'L2 二级';
    case 'L3':
      return 'L3 三级';
    default:
      return tier ?? '—';
  }
}

/** 命中口径：命中了哪条规则，还是走了默认档。 */
export function ruleMatchLabel(approvalCase: {
  ruleMatch: string;
  ruleName: string | null | undefined;
}): string {
  if (approvalCase.ruleMatch === 'DEFAULT') {
    return '默认档（没有命中任何规则：最低层级、1 人审批）';
  }
  return approvalCase.ruleName ? `规则「${approvalCase.ruleName}」` : '命中规则';
}

/** 审批单状态。 */
export function approvalCaseStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '待审批';
    case 'APPROVED':
      return '已批';
    case 'REJECTED':
      return '已驳回';
    default:
      return status;
  }
}

/** 审批链路上一个动作的中文名。 */
export function approvalRecordActionLabel(action: string): string {
  switch (action) {
    case 'SUBMITTED':
      return '提交待审';
    case 'APPROVED':
      return '批准';
    case 'REJECTED':
      return '驳回';
    case 'EVALUATED':
      return '阈值判定';
    default:
      return action;
  }
}

/** 审批单状态的颜色档（info / success / warning / danger）。 */
export function approvalCaseTone(status: string): 'info' | 'success' | 'warning' | 'danger' {
  switch (status) {
    case 'APPROVED':
      return 'success';
    case 'REJECTED':
      return 'danger';
    case 'PENDING':
      return 'warning';
    default:
      return 'info';
  }
}

/** 会计影响的状态（登记 ≠ 生效）。 */
export function valuationStatusLabel(status: string): string {
  switch (status) {
    case 'REGISTERED':
      return '已登记（未入账）';
    case 'EFFECTIVE':
      return '已生效';
    default:
      return status;
  }
}

/** 金额展示：保留 4 位小数（库存与金额口径都是 numeric(19,4)）。 */
export function amountText(value: number | string | null | undefined): string {
  if (value === null || value === undefined) return '—';
  const parsed = typeof value === 'string' ? Number(value) : value;
  if (!Number.isFinite(parsed)) return String(value);
  return parsed.toFixed(4);
}

/** 数量展示：去掉无意义的尾随 0，但最多 4 位小数。 */
export function qtyText(value: number | string | null | undefined): string {
  if (value === null || value === undefined) return '—';
  const parsed = typeof value === 'string' ? Number(value) : value;
  if (!Number.isFinite(parsed)) return String(value);
  return String(Number(parsed.toFixed(4)));
}

/** 审批单一行摘要（列表与详情共用）。 */
export function approvalCaseSummary(approvalCase: InventoryAdjustmentApprovalCase): string {
  return `${approvalCase.caseNo} · ${approvalCaseStatusLabel(approvalCase.status)} · ${tierLabel(
    approvalCase.requiredTier,
  )} · 已批 ${approvalCase.approvedCount}/${approvalCase.requiredApprovals} 人 · 申报 ${amountText(
    approvalCase.orderAmount,
  )} ${approvalCase.currency} · 调整量 ${qtyText(approvalCase.orderQty)}`;
}

// ------------------------------------------------------------ 校验

/**
 * 提交待审前的校验。
 *
 * <p>金额**必填**：没有金额就没有金额阈值，而且「不申报就能躲开金额档」是不能接受的。
 */
export function submitProblem(amount: string, currency: string): string | null {
  const text = amount.trim();
  if (text === '') {
    return '必须申报调整的估值金额：金额阈值与审批人的金额授权区间都以它为准，'
      + '「不申报就能躲开金额档」是不能接受的';
  }
  const parsed = Number(text);
  if (!Number.isFinite(parsed)) return '申报金额必须是数字';
  if (parsed <= 0) return '申报金额必须大于 0';
  const decimals = text.includes('.') ? text.split('.')[1]?.length ?? 0 : 0;
  if (decimals > 4) {
    return '申报金额最多 4 位小数（numeric(19,4)）：系统不会替你四舍五入 —— '
      + '被悄悄改掉的数字会让人以为记的是那个数';
  }
  const code = currency.trim().toUpperCase();
  if (code !== 'ANY' && !/^[A-Z]{3}$/.test(code)) {
    return '币种必须是三个大写字母（例：USD / CNY）';
  }
  return null;
}

/** 阈值规则入参的校验（与后端同一条纪律：不静默纠正，直接说清哪里不对）。 */
export function ruleProblem(input: {
  ruleNo: string;
  ruleName: string;
  currency: string;
  minAmount: string;
  maxAmount: string;
  minQty: string;
  maxQty: string;
  requiredTier: string;
  requiredApprovals: string;
}): string | null {
  if (!input.ruleNo.trim()) return '规则号不能为空：规则号在租户内必须唯一';
  if (!input.ruleName.trim()) return '规则名不能为空';
  const code = input.currency.trim().toUpperCase();
  if (code !== 'ANY' && !/^[A-Z]{3}$/.test(code)) {
    return '币种必须是三个大写字母（例：USD / CNY），或 ANY 表示不限币种';
  }
  if (!['L1', 'L2', 'L3'].includes(input.requiredTier)) return '审批层级只能是 L1 / L2 / L3';
  const approvals = Number(input.requiredApprovals);
  if (!Number.isInteger(approvals) || approvals < 1 || approvals > 5) {
    return '审批人数只能是 1~5 之间（一个人批两次不能凑数）';
  }
  const minAmount = Number(input.minAmount || 0);
  const maxAmount = input.maxAmount.trim() === '' ? null : Number(input.maxAmount);
  if (!Number.isFinite(minAmount) || minAmount < 0) return '金额下限必须是不小于 0 的数字';
  if (maxAmount !== null && (!Number.isFinite(maxAmount) || maxAmount <= minAmount)) {
    return '金额区间必须满足 下限 < 上限（上限留空 = 无上限）';
  }
  const minQty = Number(input.minQty || 0);
  const maxQty = input.maxQty.trim() === '' ? null : Number(input.maxQty);
  if (!Number.isFinite(minQty) || minQty < 0) return '数量下限必须是不小于 0 的数字';
  if (maxQty !== null && (!Number.isFinite(maxQty) || maxQty <= minQty)) {
    return '数量区间必须满足 下限 < 上限（上限留空 = 无上限）';
  }
  return null;
}

/** 审批人授权入参的校验。 */
export function approverProblem(input: {
  userId: string;
  approvalTier: string;
  currency: string;
  minAmount: string;
  maxAmount: string;
  maxQty: string;
}): string | null {
  if (!input.userId.trim()) return '必须指定审批人';
  if (!['L1', 'L2', 'L3'].includes(input.approvalTier)) return '审批层级只能是 L1 / L2 / L3';
  const code = input.currency.trim().toUpperCase();
  if (code !== 'ANY' && !/^[A-Z]{3}$/.test(code)) {
    return '币种必须是三个大写字母（例：USD / CNY），或 ANY 表示不限币种';
  }
  const minAmount = Number(input.minAmount || 0);
  const maxAmount = input.maxAmount.trim() === '' ? null : Number(input.maxAmount);
  if (!Number.isFinite(minAmount) || minAmount < 0) return '金额授权下限必须是不小于 0 的数字';
  if (maxAmount !== null && (!Number.isFinite(maxAmount) || maxAmount <= minAmount)) {
    return '金额授权区间必须满足 下限 < 上限（上限留空 = 不限）';
  }
  const maxQty = input.maxQty.trim() === '' ? null : Number(input.maxQty);
  if (maxQty !== null && (!Number.isFinite(maxQty) || maxQty < 0)) {
    return '数量授权上限不能为负';
  }
  return null;
}

/** 批准必须写理由（与后端同一条纪律）。 */
export function approveProblem(note: string): string | null {
  if (note.trim() === '') return '批准必须写理由：只留一个「已批」状态，事后没人说得清批的是哪一条判断';
  if (note.trim().length > 500) return '审批意见超过 500 字';
  return null;
}

/** 驳回必须写意见。 */
export function rejectProblem(note: string): string | null {
  if (note.trim() === '') return '驳回必须写意见：提交的人需要知道该改什么';
  if (note.trim().length > 500) return '驳回意见超过 500 字';
  return null;
}

/** 批量结果的一行文案（成功与失败都要说清楚）。 */
export function batchItemText(item: {
  orderId: string;
  orderCode: string;
  success: boolean;
  message: string;
}): string {
  const code = item.orderCode || item.orderId;
  return item.success ? `${code}：${item.message}` : `${code}：${item.message}`;
}

/** 批量结果的整体摘要。 */
export function batchSummary(result: {
  successCount: number;
  failureCount: number;
}): string {
  if (result.failureCount === 0) return `全部处理成功（${result.successCount} 张）`;
  return `成功 ${result.successCount} 张，失败 ${result.failureCount} 张（失败原因见下）`;
}

// ------------------------------------------------------------ 错误文案

/**
 * 统一错误文案。
 *
 * <p>403 / 404 / 409 的含义差别很大，要分开说：409 常常是「审批人数没凑齐」或
 * 「状态已经变了」，把它说成「未知错误」等于让用户自己猜。
 */
export function approvalErrorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return '当前角色没有此操作权限（需要 warehouse.inventory.update）';
    if (error.status === 404) return '找不到该对象（可能不属于当前租户）';
    if (error.status === 409) return error.message || '状态冲突：这一步现在还不能做';
    if (error.status === 400) return error.message || '请求被拒绝：参数或状态不满足条件';
    return error.message || `请求失败（${error.status}）`;
  }
  if (error instanceof Error) return error.message;
  return '未知错误';
}
