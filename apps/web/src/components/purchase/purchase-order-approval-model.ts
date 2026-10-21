import type {
  PurchaseOrderApprovalApproverInput,
  PurchaseOrderApprovalApproverView,
  PurchaseOrderApprovalBatchRequest,
  PurchaseOrderApprovalBatchResult,
  PurchaseOrderApprovalBuyerInput,
  PurchaseOrderApprovalBuyerView,
  PurchaseOrderApprovalCapabilities,
  PurchaseOrderApprovalDecisionRequest,
  PurchaseOrderApprovalDetail,
  PurchaseOrderApprovalMembers,
  PurchaseOrderApprovalPayableConfirmRequest,
  PurchaseOrderApprovalPayablePage,
  PurchaseOrderApprovalPayableRejectRequest,
  PurchaseOrderApprovalPayableView,
  PurchaseOrderApprovalPage,
  PurchaseOrderApprovalRuleInput,
  PurchaseOrderApprovalRuleList,
  PurchaseOrderApprovalRuleView,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 6.3.1.3 采购单审批前端模型。
 *
 * <p>蓝图：「按金额阈值与采购员分级审批，支持批量审批、驳回并附意见，
 * 审批通过后锁定单据生成应付。」
 *
 * <p><b>界面纪律</b>（逐条对应后端模型的口径，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>「还没发生」用状态说，不用空对象</b>：应付登记与收货放行在后端永远是对象，
 *       {@code status='NONE'} 表示「还没登记 / 还没放行」，金额是 null（**不是 0**）。</li>
 *   <li><b>不能批要说清为什么</b>：{@code blockedReason} 原样显示 ——
 *       「层级不够」「金额超授权」「不能自审」对审批人的动作含义完全不同。</li>
 *   <li><b>不跨币种相加</b>：待审金额**按币种分列**，界面不出现一个跨币种的合计数。</li>
 *   <li><b>不重算后端的结论</b>：命中哪条规则、要几级几个人、能不能批，
 *       一律显示后端给的；页面不按金额自己猜档位。</li>
 *   <li><b>「没配规则」不是「不用审」</b>：{@code ruleMatch='DEFAULT'} 显示成
 *       「默认档（最低层级、1 人）」，让人知道这一单仍然要审。</li>
 *   <li>加载 / 空 / 错误三态齐全，且三态文案各不相同。</li>
 * </ul>
 */

const base = 'trade/purchase-order-approvals';

// ================================================================ 查询参数

export interface PurchaseOrderApprovalParams {
  // `| undefined` 是 `exactOptionalPropertyTypes` 下的必需写法：
  // 筛选器常常把「可能没有」的值直接透传，不显式声明 undefined 的话 TS 会拒绝显式传 undefined。
  caseStatus?: string | undefined;
  tier?: string | undefined;
  currency?: string | undefined;
  supplierCode?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 应付台账的查询参数。 */
export interface PurchaseOrderApprovalPayableParams {
  status?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/**
 * 台账的查询串。
 *
 * <p>🔴 刻意做成**独立函数**而不是在模板里内联三元表达式：白名单守卫
 * （{@code purchase-order-approval-allowlist.test.ts}）会从本文件源码里**反向抽取**真实调用点，
 * 模板里多一个内联插值就会抽出一条对不上的假路径。
 */
export function purchaseOrderApprovalQuery(params: PurchaseOrderApprovalParams): string {
  const parts: string[] = [];
  if (params.caseStatus) parts.push(`caseStatus=${encodeURIComponent(params.caseStatus)}`);
  if (params.tier) parts.push(`tier=${encodeURIComponent(params.tier)}`);
  if (params.currency) parts.push(`currency=${encodeURIComponent(params.currency)}`);
  if (params.supplierCode) parts.push(`supplierCode=${encodeURIComponent(params.supplierCode)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/**
 * 应付台账的查询串。
 *
 * <p>与 {@link purchaseOrderApprovalQuery} 同款：单独成函数，白名单守卫才能从源码里
 * 反向抽出真实调用点（模板里内联插值会抽出一条对不上的假路径）。
 */
export function purchaseOrderApprovalPayableQuery(
  params: PurchaseOrderApprovalPayableParams,
): string {
  const parts: string[] = [];
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 调用点

export function fetchPurchaseOrderApprovalCapabilities() {
  return api<PurchaseOrderApprovalCapabilities>(`${base}/capabilities`);
}

export function fetchPurchaseOrderApprovalRules() {
  return api<PurchaseOrderApprovalRuleList>(`${base}/rules`);
}

export function createPurchaseOrderApprovalRule(input: PurchaseOrderApprovalRuleInput) {
  return api<PurchaseOrderApprovalRuleView>(`${base}/rules`, 'POST', input);
}

export function updatePurchaseOrderApprovalRule(id: string, input: PurchaseOrderApprovalRuleInput) {
  return api<PurchaseOrderApprovalRuleView>(`${base}/rules/${id}`, 'PUT', input);
}

/** 启用 / 停用规则（带 version 乐观锁）。 */
export function setPurchaseOrderApprovalRuleStatus(id: string, status: string, version: number) {
  return api<PurchaseOrderApprovalRuleView>(
    `${base}/rules/${id}/status?status=${encodeURIComponent(status)}&version=${version}`,
    'POST',
  );
}

export function fetchPurchaseOrderApprovalMembers() {
  return api<PurchaseOrderApprovalMembers>(`${base}/members`);
}

export function upsertPurchaseOrderApprovalBuyer(input: PurchaseOrderApprovalBuyerInput) {
  return api<PurchaseOrderApprovalBuyerView>(`${base}/members/buyers`, 'POST', input);
}

export function upsertPurchaseOrderApprovalApprover(input: PurchaseOrderApprovalApproverInput) {
  return api<PurchaseOrderApprovalApproverView>(`${base}/members/approvers`, 'POST', input);
}

export function fetchPurchaseOrderApprovals(params: PurchaseOrderApprovalParams) {
  return api<PurchaseOrderApprovalPage>(`${base}/orders${purchaseOrderApprovalQuery(params)}`);
}

export function fetchPurchaseOrderApprovalDetail(id: string) {
  return api<PurchaseOrderApprovalDetail>(`${base}/orders/${id}`);
}

/** 审批通过（意见可选）。 */
export function approvePurchaseOrder(id: string, note: string) {
  const input: PurchaseOrderApprovalDecisionRequest = { note: note.trim() || null };
  return api<PurchaseOrderApprovalDetail>(`${base}/orders/${id}/approve`, 'POST', input);
}

/** 驳回（意见**必填** —— 后端会拒空意见）。 */
export function rejectPurchaseOrder(id: string, note: string) {
  const input: PurchaseOrderApprovalDecisionRequest = { note: note.trim() };
  return api<PurchaseOrderApprovalDetail>(`${base}/orders/${id}/reject`, 'POST', input);
}

/** 批量审批 / 批量驳回（逐条给结论）。 */
export function batchPurchaseOrderApprovals(
  orderIds: string[],
  action: 'APPROVE' | 'REJECT',
  note: string,
) {
  const input: PurchaseOrderApprovalBatchRequest = {
    orderIds,
    action,
    note: note.trim() || null,
  };
  return api<PurchaseOrderApprovalBatchResult>(`${base}/orders/batch`, 'POST', input);
}

export function fetchPurchaseOrderApprovalPayables(params: PurchaseOrderApprovalPayableParams) {
  return api<PurchaseOrderApprovalPayablePage>(
    `${base}/payables${purchaseOrderApprovalPayableQuery(params)}`,
  );
}

/** 财务确认（登记的应付变生效）。 */
export function confirmPurchaseOrderPayable(id: string, financeReference: string) {
  const input: PurchaseOrderApprovalPayableConfirmRequest = { financeReference: financeReference.trim() };
  return api<PurchaseOrderApprovalPayableView>(`${base}/payables/${id}/confirm`, 'POST', input);
}

/** 财务驳回（登记的应付作废）。 */
export function rejectPurchaseOrderPayable(id: string, reason: string) {
  const input: PurchaseOrderApprovalPayableRejectRequest = { reason: reason.trim() };
  return api<PurchaseOrderApprovalPayableView>(`${base}/payables/${id}/reject`, 'POST', input);
}

// ================================================================ 展示口径

/** 审批单状态的标签。 */
export const approvalCaseStatusLabels: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已驳回',
};

export function approvalCaseStatusLabel(status: string): string {
  return approvalCaseStatusLabels[status] ?? status;
}

/**
 * 审批单状态决定视觉分级。
 *
 * <p>待审批是警示色（有人卡在这里），已通过是成功色，已驳回是危险色。
 */
export function approvalCaseStatusTone(
  status: string,
): 'danger' | 'warning' | 'success' | 'info' | 'neutral' {
  switch (status) {
    case 'REJECTED':
      return 'danger';
    case 'PENDING':
      return 'warning';
    case 'APPROVED':
      return 'success';
    default:
      return 'neutral';
  }
}

/** 审批层级的标签。 */
export const approvalTierLabels: Record<string, string> = {
  L1: 'L1 采购主管',
  L2: 'L2 采购经理',
  L3: 'L3 采购总监',
};

export function approvalTierLabel(tier: string): string {
  return approvalTierLabels[tier] ?? tier;
}

/** 采购员分级的标签。 */
export const buyerLevelLabels: Record<string, string> = {
  JUNIOR: '初级采购员',
  SENIOR: '高级采购员',
  MANAGER: '采购经理',
  UNREGISTERED: '未登记分级',
};

export function buyerLevelLabel(level: string): string {
  return buyerLevelLabels[level] ?? level;
}

/**
 * 规则命中的标签。
 *
 * <p>🔴 DEFAULT（一条规则都没命中）**必须**显示成「默认档」而不是「无」——
 * 它表示「这一单仍然要审，走最低层级、1 人」，而不是「不用审」。
 */
export function ruleMatchLabel(ruleMatch: string): string {
  return ruleMatch === 'RULE' ? '命中规则' : '默认档（最低层级 · 1 人）';
}

/** 应付登记状态的标签。 */
export const payableStatusLabels: Record<string, string> = {
  NONE: '尚未登记',
  PENDING_FINANCE_CONFIRM: '待财务确认',
  CONFIRMED: '已生效',
  REJECTED: '已作废',
};

export function payableStatusLabel(status: string): string {
  return payableStatusLabels[status] ?? status;
}

/** 收货放行状态的标签。 */
export const releaseStatusLabels: Record<string, string> = {
  NONE: '尚未放行',
  RELEASED: '已放行',
  REVOKED: '已撤销',
};

export function releaseStatusLabel(status: string): string {
  return releaseStatusLabels[status] ?? status;
}

/** 「无数据」的统一文案。null 一律用它 —— 0 会被读成「不用审 / 不要钱」。 */
export const NO_DATA_TEXT = '无数据';

/** 金额的展示。null = 这个数**还没有**，**不是 0**。 */
export function fmtAmount(value: number | null | undefined): string {
  if (value === null || value === undefined) return NO_DATA_TEXT;
  const rounded = Number(value.toFixed(4));
  return rounded.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 });
}

/** 时刻的展示。 */
export function fmtDateTime(value: string | null | undefined): string {
  if (!value) return NO_DATA_TEXT;
  const at = new Date(value);
  if (Number.isNaN(at.getTime())) return value;
  return at.toLocaleString('zh-CN', { hour12: false });
}

/**
 * 审批进度的展示：已批人数 / 需要人数。
 *
 * <p>凑不齐时显示成「1 / 2 人」而不是「50%」—— 审批进度看的是**还差几个人**，
 * 百分比会让人以为批了一半就能过。
 */
export function approvalProgressText(approved: number, required: number): string {
  if (required <= 0) return NO_DATA_TEXT;
  return `${approved} / ${required} 人`;
}

/**
 * 这一行现在能不能操作。
 *
 * <p>🔴 「不能批」与「没有待办」是两件事：前者是**权限/额度**问题，
 * 后者是**流程**已经走完 —— 文案不同，动作也不同。
 */
export function actionHint(row: {
  caseStatus: string;
  canApprove: boolean;
  blockedReason: string;
}): string {
  if (row.caseStatus !== 'PENDING') {
    return row.caseStatus === 'APPROVED' ? '这一单已经审批通过' : '这一单已被驳回';
  }
  if (row.canApprove) return '可以审批';
  return row.blockedReason || '当前不可操作';
}

/** 批量结果的展示文案（逐条成败，失败原因不吞）。 */
export function batchApprovalResultText(result: PurchaseOrderApprovalBatchResult): string {
  if (result.failedCount === 0) {
    return `已处理 ${result.succeededCount} 张采购单`;
  }
  const detail = result.items
    .filter((item) => !item.succeeded)
    .map((item) => `${item.orderNo || item.orderId}：${item.message}`)
    .join('；');
  return `成功 ${result.succeededCount} 张，失败 ${result.failedCount} 张 —— ${detail}`;
}

/** 审批规则的展示摘要（区间是半开的 [下限, 上限)）。 */
export function ruleBandText(rule: {
  buyerLevel: string;
  currency: string;
  minAmount: number;
  maxAmount: number | null;
}): string {
  const level = rule.buyerLevel === 'ANY' ? '全部分级' : buyerLevelLabel(rule.buyerLevel);
  const currency = rule.currency === 'ANY' ? '全部币种' : rule.currency;
  const upper = rule.maxAmount === null ? '∞' : fmtAmount(rule.maxAmount);
  return `${level} · ${currency} · [${fmtAmount(rule.minAmount)}, ${upper})`;
}

/** 审批人授权区间的展示。 */
export function approverBandText(approver: {
  currency: string;
  minAmount: number;
  maxAmount: number | null;
}): string {
  const currency = approver.currency === 'ANY' ? '全部币种' : approver.currency;
  const upper = approver.maxAmount === null ? '∞' : fmtAmount(approver.maxAmount);
  return `${currency} · [${fmtAmount(approver.minAmount)}, ${upper})`;
}

/**
 * 错误文案。
 *
 * <p>🔴 403 要说清是**哪一个权限码** —— 审批与编辑是两种不同的权力，
 * 「没有审批权限」和「没有编辑权限」对使用者是完全不同的两件事。
 * 409 / 400 用后端给的原因原文（后端已经写清了「不能自审」「层级不够」这类口径）。
 */
export function approvalErrorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) {
      return '当前身份没有审批权限（审批需要独立权限码 trade.purchase.approve）';
    }
    if (error.status === 404) return '这张采购单不存在，或不在你的数据范围内';
    if (error.status === 409) return error.message || '这一单刚被别人处理过，请刷新后重试';
    if (error.status === 400) return error.message || '提交的内容不合法，请检查后重试';
    if (error.status === 0) return '正在连接采购服务，请稍后重试';
    return error.message;
  }
  return '加载失败，请稍后重试';
}
