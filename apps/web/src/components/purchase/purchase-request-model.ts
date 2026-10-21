import type {
  PurchaseRequestCapabilities,
  PurchaseRequestConvertInput,
  PurchaseRequestCreateInput,
  PurchaseRequestDetail,
  PurchaseRequestLineAdjustInput,
  PurchaseRequestLineInput,
  PurchaseRequestLinePage,
  PurchaseRequestLineRow,
  PurchaseRequestOptions,
  PurchaseRequestPage,
  PurchaseRequestRow,
  PurchaseRequestSupplierInput,
  PurchaseRequestUpdateInput,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 6.2.2.1 采购申请前端模型。
 *
 * <p>蓝图：「把计划行/补货建议转成采购申请，走审批，确认供应商与数量后转采购单。」
 * 输出「采购申请单（草稿/待审/已批/已转单/已驳回）；审批记录」。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>申请与采购单分开</b>：页面**不显示**任何「采购单已创建」的字样 ——
 *       6.3.1.2 采购单未交付，本模块只产出**转单事实**。转单之后页面显示的是
 *       「已转单（转单事实）」加上供应商 / 数量 / 交期 / 目标仓，不是「已下单」。</li>
 *   <li><b>审批同步失败必须显眼</b>：{@code approvalSyncError} 非空时页面要把那句话
 *       **原样**显示出来，并说明这张单**仍停在待审**。不显示 = 用户以为它还在正常流转。</li>
 *   <li><b>「答不出来」不是 0</b>：预估金额取不到时显示「无数据」，
 *       绝不显示 0 —— 0 会被读成「这笔采购不要钱」。</li>
 *   <li><b>不重算后端的结论</b>：状态标签、审批结论、金额一律原样显示后端给的。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */

const base = 'trade/purchase-requests';

// ================================================================ 查询参数

export interface PurchaseRequestParams {
  // `| undefined` 是 `exactOptionalPropertyTypes` 下的必需写法：
  // 筛选器常常把「可能没有」的值直接透传（`string | undefined`），
  // 不显式声明 undefined 的话 TS 会拒绝「显式传 undefined」。
  status?: string | undefined;
  sourceType?: string | undefined;
  sourcePlanId?: string | undefined;
  targetWarehouseId?: string | undefined;
  supplierId?: string | undefined;
  keyword?: string | undefined;
  sort?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export function purchaseRequestQuery(params: PurchaseRequestParams): string {
  const parts: string[] = [];
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.sourceType) parts.push(`sourceType=${encodeURIComponent(params.sourceType)}`);
  if (params.sourcePlanId) parts.push(`sourcePlanId=${encodeURIComponent(params.sourcePlanId)}`);
  if (params.targetWarehouseId)
    parts.push(`targetWarehouseId=${encodeURIComponent(params.targetWarehouseId)}`);
  if (params.supplierId) parts.push(`supplierId=${encodeURIComponent(params.supplierId)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export interface PurchaseRequestLineParams {
  skuCode?: string | undefined;
  keyword?: string | undefined;
  supplierId?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export function purchaseRequestLineQuery(params: PurchaseRequestLineParams): string {
  const parts: string[] = [];
  if (params.skuCode) parts.push(`skuCode=${encodeURIComponent(params.skuCode)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.supplierId) parts.push(`supplierId=${encodeURIComponent(params.supplierId)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/**
 * 详情页的查询串。
 *
 * <p>🔴 刻意做成**独立函数**而不是在模板里内联三元表达式：白名单守卫
 * （{@code purchase-request-allowlist.test.ts}）会从本文件源码里**反向抽取**真实调用点，
 * 模板里多一个内联插值就会抽出一条对不上的假路径。
 */
export function purchaseRequestDetailQuery(refreshApproval?: boolean): string {
  return refreshApproval ? '?refreshApproval=true' : '';
}

// ================================================================ 调用点

export function fetchPurchaseRequestCapabilities() {
  return api<PurchaseRequestCapabilities>(`${base}/capabilities`);
}

export function fetchPurchaseRequestOptions() {
  return api<PurchaseRequestOptions>(`${base}/options`);
}

export function fetchPurchaseRequests(params: PurchaseRequestParams) {
  return api<PurchaseRequestPage>(`${base}${purchaseRequestQuery(params)}`);
}

export function fetchPurchaseRequest(id: string, refreshApproval = false) {
  return api<PurchaseRequestDetail>(
    `${base}/${encodeURIComponent(id)}${purchaseRequestDetailQuery(refreshApproval)}`,
  );
}

export function createPurchaseRequest(input: PurchaseRequestCreateInput) {
  return api<PurchaseRequestRow>(`${base}`, 'POST', input);
}

export function updatePurchaseRequest(id: string, input: PurchaseRequestUpdateInput) {
  return api<PurchaseRequestRow>(`${base}/${encodeURIComponent(id)}`, 'PUT', input);
}

export function fetchPurchaseRequestLines(requestId: string, params: PurchaseRequestLineParams) {
  return api<PurchaseRequestLinePage>(
    `${base}/${encodeURIComponent(requestId)}/lines${purchaseRequestLineQuery(params)}`,
  );
}

export function addPurchaseRequestLine(requestId: string, input: PurchaseRequestLineInput) {
  return api<PurchaseRequestLineRow>(
    `${base}/${encodeURIComponent(requestId)}/lines`,
    'POST',
    input,
  );
}

export function adjustPurchaseRequestLine(
  requestId: string,
  lineId: string,
  input: PurchaseRequestLineAdjustInput,
) {
  return api<PurchaseRequestLineRow>(
    `${base}/${encodeURIComponent(requestId)}/lines/${encodeURIComponent(lineId)}/adjust`,
    'POST',
    input,
  );
}

export function selectPurchaseRequestSupplier(
  requestId: string,
  lineId: string,
  input: PurchaseRequestSupplierInput,
) {
  return api<PurchaseRequestLineRow>(
    `${base}/${encodeURIComponent(requestId)}/lines/${encodeURIComponent(lineId)}/supplier`,
    'POST',
    input,
  );
}

export function submitPurchaseRequest(id: string) {
  return api<PurchaseRequestRow>(`${base}/${encodeURIComponent(id)}/submit`, 'POST', {});
}

export function refreshPurchaseRequestApproval(id: string) {
  return api<PurchaseRequestRow>(`${base}/${encodeURIComponent(id)}/approval/refresh`, 'POST', {});
}

export function convertPurchaseRequest(id: string, input: PurchaseRequestConvertInput) {
  return api<PurchaseRequestDetail>(`${base}/${encodeURIComponent(id)}/convert`, 'POST', input);
}

// ================================================================ 展示口径

/** 申请单状态的标签（与后端 PurchaseRequestStatus 一一对应）。 */
export const purchaseRequestStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_APPROVAL: '待审',
  APPROVED: '已批',
  CONVERTED: '已转单',
  REJECTED: '已驳回',
};

export function requestStatusLabel(status: string): string {
  return purchaseRequestStatusLabels[status] ?? status;
}

/** 来源类型的标签（与后端 PurchaseRequestSourceType 一一对应）。 */
export const purchaseRequestSourceTypeLabels: Record<string, string> = {
  PLAN: '采购计划行',
  SUGGESTION: '补货建议',
  MANUAL: '手工建单',
};

export function sourceTypeLabel(sourceType: string): string {
  return purchaseRequestSourceTypeLabels[sourceType] ?? sourceType;
}

/** 优先级的标签（与后端 PurchaseRequestPriority 一一对应）。 */
export const purchaseRequestPriorityLabels: Record<string, string> = {
  CRITICAL: '紧急',
  HIGH: '高',
  NORMAL: '普通',
  LOW: '低',
};

export function priorityLabel(priority: string): string {
  return purchaseRequestPriorityLabels[priority] ?? priority;
}

/** 审批判定结论的标签（identity 的 decision）。 */
export const approvalDecisionLabels: Record<string, string> = {
  SUBMIT_FOR_APPROVAL: '需审批',
  EXEMPT: '免审',
};

export function approvalDecisionLabel(decision: string): string {
  if (!decision) return '未判定';
  return approvalDecisionLabels[decision] ?? decision;
}

/** 审批判定来源的标签（identity 的 source）。 */
export const approvalSourceLabels: Record<string, string> = {
  RULE: '命中规则',
  DEFAULT: '规则集默认策略',
  UNCONTROLLED: '本租户未配规则',
};

export function approvalSourceLabel(source: string): string {
  if (!source) return '未判定';
  return approvalSourceLabels[source] ?? source;
}

/** 「无数据」的统一文案。null 一律用它 —— 0 会被读成「不用备 / 不要钱」。 */
export const NO_DATA_TEXT = '无数据';

/** 数量的展示。null 一律给「无数据」。 */
export function fmt(value: number | null | undefined): string {
  if (value === null || value === undefined) return NO_DATA_TEXT;
  const rounded = Number(value.toFixed(6));
  return rounded.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/** 金额的展示。null = 金额**算不出来**（有行没填预估单价），**不是 0**。 */
export function fmtAmount(value: number | null | undefined): string {
  if (value === null || value === undefined) return NO_DATA_TEXT;
  const rounded = Number(value.toFixed(4));
  return rounded.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 });
}

/** 交期的展示。null = 交期还没定，**不是**「不需要交期」。 */
export function fmtDate(value: string | null | undefined): string {
  if (!value) return '未定';
  return value;
}

/** 状态决定视觉分级：驳回与同步失败是危险色，已批 / 已转单是成功色。 */
export function requestStatusTone(
  status: string,
): 'danger' | 'warning' | 'success' | 'info' | 'neutral' {
  switch (status) {
    case 'REJECTED':
      return 'danger';
    case 'PENDING_APPROVAL':
      return 'warning';
    case 'APPROVED':
    case 'CONVERTED':
      return 'success';
    case 'DRAFT':
      return 'info';
    default:
      return 'neutral';
  }
}

/** 这一张申请单还能不能改内容（只有草稿可改）。 */
export function isEditable(status: string): boolean {
  return status === 'DRAFT';
}

/** 这一张申请单还能不能提交审批（只有草稿能提交）。 */
export function isSubmittable(status: string): boolean {
  return status === 'DRAFT';
}

/** 这一张申请单还能不能刷审批结论（只有待审需要刷）。 */
export function isRefreshable(status: string): boolean {
  return status === 'PENDING_APPROVAL';
}

/** 这一张申请单还能不能转单（只有已批能转）。 */
export function isConvertible(status: string): boolean {
  return status === 'APPROVED';
}

/**
 * 审批同步失败了吗。
 *
 * <p>🔴 这是页面**必须**显眼呈现的一种状态：审批服务不可达时申请单停在待审，
 * 不显示这句话 = 用户以为它还在正常流转。
 */
export function hasApprovalSyncError(request: {
  approvalSyncError: string | null | undefined;
}): boolean {
  return Boolean(request.approvalSyncError && request.approvalSyncError.trim());
}

/**
 * 这一行能不能选供应商 / 调整。
 *
 * <p>已提交的申请单**不许**再改内容 —— 改了与「已审批的那一版」对不上，
 * 审批留痕也就失去意义（服务端与 DB 层都会拒）。
 */
export function isLineEditable(status: string): boolean {
  return status === 'DRAFT';
}

/**
 * 这一行还缺什么才能转单。
 *
 * <p>返回空串 = 齐了。转单前每一行都必须有供应商与交期 ——
 * 缺一个就不许转（那不是确认，是猜）。
 */
export function missingConvertInputs(
  lines: Pick<PurchaseRequestLineRow, 'skuCode' | 'supplierId' | 'requestedArrivalDate'>[],
): string[] {
  const missing: string[] = [];
  for (const line of lines) {
    if (!line.supplierId) missing.push(`${line.skuCode}（还没选供应商）`);
    if (!line.requestedArrivalDate) missing.push(`${line.skuCode}（还没有交期）`);
  }
  return missing;
}
