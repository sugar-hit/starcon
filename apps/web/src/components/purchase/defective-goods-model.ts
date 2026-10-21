import type {
  DefectiveClaimAgreeInput,
  DefectiveClaimReasonInput,
  DefectiveClaimRow,
  DefectiveGoodsCapabilities,
  DefectiveInspectionOption,
  DefectiveLineInput,
  DefectiveLineRow,
  DefectiveOrderDetail,
  DefectiveOrderInput,
  DefectiveOrderPage,
  DefectiveOrderRow,
  DefectiveOptions,
  DefectiveSummary,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 6.4.2.2 不良品处理前端模型。
 *
 * <p>蓝图：「不合格品的处置：退换货补货、返修、让步接收、报废、转为促销品，
 * 处置结论联动库存状态与供应商索赔。」输出「处置单 + 库存状态变更指令 + 供应商索赔记录」。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>处置量不接受手填</b>：数量从 6.4.2.1 质检单回读，页面只显示
 *       「不合格量 / 可售量上限 / 还能处置多少」三个数，让人看得见上限在哪。</li>
 *   <li><b>让步接收要原因与权限码</b>：表单里两者都是必填，页面明写「缺一会被后端拒」，
 *       并写清这一档权限比质检结论录入高。</li>
 *   <li><b>放行量由处置类型决定</b>：报废 / 退换货 / 返修的货离仓或不可售，
 *       页面不让填放行量 —— 填了也会被后端拒。</li>
 *   <li><b>库存状态照实显示</b>：warehouse 没有可用的授权写端点 ⇒ 页面写「上游无写端点」
 *       并写清影响，绝不显示「已登记」而其实没动库存。</li>
 *   <li><b>索赔金额不假装已入账</b>：供应商认可了赔付也不等于冲减了应付，
 *       页面把「已生效冲减金额」单列并写明 12 财务未交付。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */

// 本模块用到的契约类型在这里**再导出一次**：页面与测试只从模型取，
// 不用越过模型直接去契约包里翻类型名（那样每个文件都要写一长串 import，
// 契约改名时漏一处就是一个编译错误）。
export type {
  DefectiveClaimAgreeInput,
  DefectiveClaimReasonInput,
  DefectiveClaimRow,
  DefectiveGoodsCapabilities,
  DefectiveInspectionOption,
  DefectiveLineInput,
  DefectiveLineRow,
  DefectiveOrderDetail,
  DefectiveOrderInput,
  DefectiveOrderPage,
  DefectiveOrderRow,
  DefectiveOptions,
  DefectiveSummary,
};

const base = 'trade/defective-dispositions';

export interface DefectiveParams {
  keyword?: string;
  status?: string;
  dispositionType?: string;
  inspectionNo?: string;
  skuCode?: string;
  sort?: string;
  page?: number;
  size?: number;
}

/** 列表查询串。空值一律不拼进去 —— 拼一个空串等于「按空值筛」。 */
export function defectiveQuery(params: DefectiveParams): string {
  const parts: string[] = [];
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.dispositionType)
    parts.push(`dispositionType=${encodeURIComponent(params.dispositionType)}`);
  if (params.inspectionNo) parts.push(`inspectionNo=${encodeURIComponent(params.inspectionNo)}`);
  if (params.skuCode) parts.push(`skuCode=${encodeURIComponent(params.skuCode)}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 读

export function fetchCapabilities() {
  return api<DefectiveGoodsCapabilities>(`${base}/capabilities`);
}

export function fetchOptions() {
  return api<DefectiveOptions>(`${base}/options`);
}

export function fetchOrders(params: DefectiveParams) {
  return api<DefectiveOrderPage>(`${base}${defectiveQuery(params)}`);
}

export function fetchOrder(orderId: string) {
  return api<DefectiveOrderDetail>(`${base}/${encodeURIComponent(orderId)}`);
}

export function fetchSummary(currencyCode?: string) {
  const query = currencyCode ? `?currencyCode=${encodeURIComponent(currencyCode)}` : '';
  return api<DefectiveSummary>(`${base}/summary${query}`);
}

// ================================================================ 写

export function createOrder(input: DefectiveOrderInput) {
  return api<DefectiveOrderRow>(`${base}`, 'POST', input);
}

export function updateOrder(orderId: string, input: DefectiveOrderInput) {
  return api<DefectiveOrderRow>(`${base}/${encodeURIComponent(orderId)}`, 'PUT', input);
}

export function decideOrder(orderId: string) {
  return api<DefectiveOrderRow>(`${base}/${encodeURIComponent(orderId)}/decide`, 'POST');
}

export function dispatchInventory(orderId: string) {
  return api<DefectiveOrderRow>(
    `${base}/${encodeURIComponent(orderId)}/dispatch-inventory`,
    'POST',
  );
}

export function completeOrder(orderId: string) {
  return api<DefectiveOrderRow>(`${base}/${encodeURIComponent(orderId)}/complete`, 'POST');
}

export function cancelOrder(orderId: string, input: { cancelReason: string }) {
  return api<DefectiveOrderRow>(`${base}/${encodeURIComponent(orderId)}/cancel`, 'POST', input);
}

export function submitClaim(orderId: string, claimId: string) {
  return api<DefectiveClaimRow>(
    `${base}/${encodeURIComponent(orderId)}/claims/${encodeURIComponent(claimId)}/submit`,
    'POST',
  );
}

export function agreeClaim(orderId: string, claimId: string, input: DefectiveClaimAgreeInput) {
  return api<DefectiveClaimRow>(
    `${base}/${encodeURIComponent(orderId)}/claims/${encodeURIComponent(claimId)}/agree`,
    'POST',
    input,
  );
}

export function rejectClaim(orderId: string, claimId: string, input: DefectiveClaimReasonInput) {
  return api<DefectiveClaimRow>(
    `${base}/${encodeURIComponent(orderId)}/claims/${encodeURIComponent(claimId)}/reject`,
    'POST',
    input,
  );
}

export function withdrawClaim(orderId: string, claimId: string, input: DefectiveClaimReasonInput) {
  return api<DefectiveClaimRow>(
    `${base}/${encodeURIComponent(orderId)}/claims/${encodeURIComponent(claimId)}/withdraw`,
    'POST',
    input,
  );
}

/**
 * 导出 CSV 的地址（浏览器直接下载）。
 *
 * <p>注意 {@code base} 已经含 `trade/` 前缀 —— 再拼一次就变成 `/api/trade/trade/...`，
 * 那种地址在浏览器里静默 404（typecheck / lint / vitest 全绿，只有页面点不出东西）。
 */
export function exportUrl(orderId: string) {
  return `/api/${base}/${encodeURIComponent(orderId)}/export`;
}

// ================================================================ 界面口径（纯函数，可单测）

/** 数量格式化：6 位小数去掉尾随零，但整数保留整数形态。 */
export function fmtQuantity(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  const rounded = Math.round(value * 1e6) / 1e6;
  return String(rounded);
}

/** 金额格式化：4 位小数，跟 DB 的 numeric(19,4) 对齐。 */
export function fmtAmount(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return String(Math.round(value * 1e4) / 1e4);
}

/** 处置类型词表（蓝图 6.4.2.2 职责句点名的五个去向）。 */
export const DISPOSITION_TYPE_OPTIONS = [
  { value: 'RETURN_EXCHANGE', label: '退换货补货' },
  { value: 'REPAIR', label: '返修' },
  { value: 'CONCESSION', label: '让步接收' },
  { value: 'SCRAP', label: '报废' },
  { value: 'PROMOTION', label: '转为促销品' },
];

/** 处置类型代码 → 中文（优先用后端给的 label）。 */
export function dispositionTypeLabel(code: string, serverLabel?: string | null): string {
  if (serverLabel && serverLabel.trim()) return serverLabel;
  return DISPOSITION_TYPE_OPTIONS.find((o) => o.value === code)?.label ?? code;
}

/**
 * 这个去向会不会让货回到可售池。
 *
 * <p>让步接收（放行）与转为促销品会；报废 / 退换货 / 返修的货离仓或不可售。
 */
export function isReleasing(type: string): boolean {
  return type === 'CONCESSION' || type === 'PROMOTION';
}

/**
 * 放行量由处置类型决定（与后端同一条规则）。
 *
 * <p>让步接收与转为促销品**整行**回可售池 ⇒ 放行量 = 该行数量；其余类型恒为 0。
 */
export function releaseQuantityFor(type: string, quantity: number): number {
  return isReleasing(type) ? Math.round(quantity * 1e6) / 1e6 : 0;
}

/** 让步接收是否需要额外的「原因 + 权限码」。 */
export function concessionRequired(type: string): boolean {
  return type === 'CONCESSION';
}

/** 处置明细的数量合计。 */
export function totalQuantity(lines: { quantity: number }[]): number {
  return Math.round(lines.reduce((sum, l) => sum + (l.quantity ?? 0), 0) * 1e6) / 1e6;
}

/** 处置明细的放行量合计。 */
export function totalReleased(lines: { releasedQuantity: number }[]): number {
  return (
    Math.round(lines.reduce((sum, l) => sum + (l.releasedQuantity ?? 0), 0) * 1e6) / 1e6
  );
}

/**
 * 处置量是否超了「还能处置多少」。
 *
 * <p>上限来自质检单的不合格量（已处置的部分被扣掉）—— 页面在提交前先挡一道，
 * 但**真正的闸在服务端与数据库层**，前端这道只是让人少点一次。
 */
export function withinDisposalLimit(planned: number, remaining: number): boolean {
  return Math.round((planned - remaining) * 1e6) / 1e6 <= 0;
}

/** 处置量与上限的文字（页面上直接把算式写出来）。 */
export function disposalLimitText(planned: number, remaining: number): string {
  return `本次处置 ${fmtQuantity(planned)}，还能处置 ${fmtQuantity(remaining)}`;
}

/** 终态（已完成 / 已作废）不再给动作。 */
export function isTerminal(status: string): boolean {
  return status === 'COMPLETED' || status === 'CANCELLED';
}

/** 库存指令状态：绝不把「没派发」说成「已登记」。 */
export function inventoryStatusText(row: {
  inventoryStatus: string;
  inventoryStatusLabel: string;
}): string {
  if (row.inventoryStatus === 'DISPATCHED') return '库存指令已登记到 warehouse';
  if (row.inventoryStatus === 'PARTIAL') return '库存指令部分已登记';
  if (row.inventoryStatus === 'UNAVAILABLE') return '库存指令未送出（上游无写端点）';
  if (row.inventoryStatus === 'FAILED') return '库存指令派发失败';
  return '库存指令待派发';
}

/** 索赔状态的中文说明。 */
export function claimStatusText(status: string, statusLabel?: string | null): string {
  if (status === 'NONE') return '未登记索赔';
  if (status === 'PENDING') return '索赔待提交';
  if (status === 'SUBMITTED') return '索赔已提交，待供应商答复';
  if (status === 'SETTLED') return '索赔已达成赔付共识';
  if (status === 'REJECTED') return '供应商拒赔';
  if (status === 'WITHDRAWN') return '索赔已撤回';
  return statusLabel ?? status;
}

/** 可处置的质检单下拉文案（含「还能处置多少」）。 */
export function inspectionOptionText(option: DefectiveInspectionOption): string {
  return `${option.inspectionNo} · ${option.skuCode} · 不合格 ${fmtQuantity(
    option.defectiveQuantity,
  )} · 还能处置 ${fmtQuantity(option.remainingQuantity)}`;
}

/** 让步接收的必填提示（表单与提示共用一句）。 */
export const CONCESSION_HINT =
  '让步接收必须给**原因**与**权限码**，且记录人必须真的持有 trade.defective-disposition.concession —— 这一档比「质检结论录入」高，缺一会被后端拒。';

/** 处置量口径的提示。 */
export const QUANTITY_HINT =
  '处置量不接受手填：所有数量从 6.4.2.1 质检单回读，处置总量由明细行重算；可售量上限（合格量 + 让步接收量）不被处置侵占。';

/** 库存指令口径的提示。 */
export const INVENTORY_HINT =
  '库存状态变更只发指令、不建账：库存分录的写入所有权在 warehouse。上游没有写端点时指令状态如实标「上游无写端点」。';

/** 索赔口径的提示。 */
export const CLAIM_HINT =
  '索赔记录落在 trade 本库；「登记的索赔金额」与「已生效的冲减金额」是两列 —— 12 财务未交付，已生效金额恒为 0。';

/** 状态词表（用于筛选下拉）。 */
export const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'DECIDED', label: '已决策' },
  { value: 'EXECUTING', label: '执行中' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'CANCELLED', label: '已作废' },
];

/** 处置类型筛选词表。 */
export const DISPOSITION_FILTER_OPTIONS = [
  { value: '', label: '全部去向' },
  ...DISPOSITION_TYPE_OPTIONS,
];

/** 排序白名单。 */
export const SORT_OPTIONS = [
  { value: 'created', label: '按创建时间' },
  { value: 'quantity', label: '按处置量' },
  { value: 'decided', label: '按定稿时间' },
  { value: 'status', label: '按状态' },
];

/** 建一张空的处置明细（表单用）。 */
export function emptyLine(): DefectiveLineInput {
  return {
    dispositionType: 'SCRAP',
    quantity: 0,
    releasedQuantity: null,
    concessionReason: null,
    permissionCode: null,
    expectedReturnDate: null,
    repairVendor: null,
    promotionPlan: null,
    note: null,
  };
}

/** 明细行 → 提交给后端的入参（放行量由处置类型决定，不让人自报）。 */
export function toLineInput(line: DefectiveLineInput): DefectiveLineInput {
  return {
    ...line,
    releasedQuantity: releaseQuantityFor(line.dispositionType, line.quantity ?? 0),
    concessionReason: concessionRequired(line.dispositionType)
      ? (line.concessionReason ?? '').trim()
      : null,
    permissionCode: concessionRequired(line.dispositionType)
      ? (line.permissionCode ?? '').trim()
      : null,
  };
}

/** 处置单详情里的「能不能派发库存指令」。 */
export function canDispatchInventory(detail: DefectiveOrderDetail): boolean {
  return detail.actions.includes('DISPATCH_INVENTORY');
}

/** 处置单详情里的「能不能定稿」。 */
export function canDecide(detail: DefectiveOrderDetail): boolean {
  return detail.actions.includes('DECIDE');
}

/** 索赔可执行的动作（页面上据此显示按钮，不给点了 404 的按钮）。 */
export function claimActions(claim: DefectiveClaimRow): string[] {
  if (claim.status === 'DRAFT') return ['SUBMIT', 'WITHDRAW'];
  if (claim.status === 'SUBMITTED') return ['AGREE', 'REJECT', 'WITHDRAW'];
  return [];
}

/** 处置明细 → 展示用的一行（把放行量算出来，不读库里那个可能被手改的值）。 */
export function lineDisplay(line: DefectiveLineRow): {
  type: string;
  quantity: number;
  released: number;
  needsConcession: boolean;
} {
  return {
    type: dispositionTypeLabel(line.dispositionType, line.dispositionTypeLabel),
    quantity: line.quantity,
    released: line.releasedQuantity,
    needsConcession: concessionRequired(line.dispositionType),
  };
}
