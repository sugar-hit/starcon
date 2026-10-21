import type {
  InventoryAdjustmentAttachment,
  InventoryAdjustmentAttachmentInput,
  InventoryAdjustmentAuditEntry,
  InventoryAdjustmentCapabilities,
  InventoryAdjustmentCreateInput,
  InventoryAdjustmentDefectiveInstruction,
  InventoryAdjustmentDefectiveSourcePage,
  InventoryAdjustmentLineInput,
  InventoryAdjustmentNoteInput,
  InventoryAdjustmentOrder,
  InventoryAdjustmentOrderDetail,
  InventoryAdjustmentOrderLine,
  InventoryAdjustmentOrderPage,
  InventoryAdjustmentPostResult,
  InventoryAdjustmentReasonOption,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 7.4.1.1 调整单管理前端模型。
 *
 * <p>界面上最容易出错、本模块显式处理了几处：
 *
 * <ul>
 *   <li>**建单/提交/批准都不改余额**，只有「过账」那一步改。界面上必须把这一点说出来，
 *       否则处理人会以为「已经调完账了」。
 *   <li>**方向写反的调整单在审批时看不出来**。所以「盘盈只接受正数、盘亏只接受负数」
 *       在界面上先拦一道，而不是等 422。
 *   <li>**数量不会被静默四舍五入**。库存精度是 4 位小数，第 5 位开始一律拒绝并说明原因。
 *   <li>**平台仓差异不可用**要显示成不可选并说明去向，而不是把「不可用」的原因藏起来。
 *   <li>**附件是逻辑引用**：界面上挂的是 catalog 媒体库里的 id + 快照，本页不传文件。
 * </ul>
 */

export interface AdjustmentListParams {
  status?: string;
  reasonCode?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

function query<T extends object>(params: T): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params as Record<string, unknown>)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

// ------------------------------------------------------------ 接口

const base = 'warehouse/inventory/adjustments';

export function fetchAdjustmentCapabilities(): Promise<InventoryAdjustmentCapabilities> {
  return api<InventoryAdjustmentCapabilities>(`${base}/capabilities`);
}

export function fetchAdjustments(
  params: AdjustmentListParams,
): Promise<InventoryAdjustmentOrderPage> {
  return api<InventoryAdjustmentOrderPage>(`${base}${query(params)}`);
}

export function fetchAdjustment(id: string): Promise<InventoryAdjustmentOrderDetail> {
  return api<InventoryAdjustmentOrderDetail>(`${base}/${id}`);
}

export function createAdjustment(
  input: InventoryAdjustmentCreateInput,
): Promise<InventoryAdjustmentOrderDetail> {
  return api<InventoryAdjustmentOrderDetail>(`${base}`, 'POST', input);
}

export function addAdjustmentLine(
  id: string,
  input: InventoryAdjustmentLineInput,
): Promise<InventoryAdjustmentOrderDetail> {
  return api<InventoryAdjustmentOrderDetail>(`${base}/${id}/lines`, 'POST', input);
}

export function removeAdjustmentLine(
  id: string,
  lineId: string,
): Promise<InventoryAdjustmentOrderDetail> {
  return api<InventoryAdjustmentOrderDetail>(`${base}/${id}/lines/${lineId}/remove`, 'POST', {});
}

export function attachAdjustmentEvidence(
  id: string,
  input: InventoryAdjustmentAttachmentInput,
): Promise<InventoryAdjustmentOrderDetail> {
  return api<InventoryAdjustmentOrderDetail>(`${base}/${id}/attachments`, 'POST', input);
}

export function removeAdjustmentEvidence(
  id: string,
  attachmentId: string,
): Promise<InventoryAdjustmentOrderDetail> {
  return api<InventoryAdjustmentOrderDetail>(
    `${base}/${id}/attachments/${attachmentId}/remove`,
    'POST',
    {},
  );
}

/** 提交待审 / 批准 / 驳回 / 作废 / 过账：五个动作同一个形状，只是转移的状态不同。 */
export function submitAdjustment(id: string, note?: string): Promise<InventoryAdjustmentOrder> {
  return api<InventoryAdjustmentOrder>(`${base}/${id}/submit`, 'POST', { note: note ?? null });
}

export function approveAdjustment(id: string, note?: string): Promise<InventoryAdjustmentOrder> {
  return api<InventoryAdjustmentOrder>(`${base}/${id}/approve`, 'POST', { note: note ?? null });
}

export function rejectAdjustment(id: string, note: string): Promise<InventoryAdjustmentOrder> {
  return api<InventoryAdjustmentOrder>(`${base}/${id}/reject`, 'POST', { note });
}

export function cancelAdjustment(id: string, note?: string): Promise<InventoryAdjustmentOrder> {
  return api<InventoryAdjustmentOrder>(`${base}/${id}/cancel`, 'POST', { note: note ?? null });
}

/** 过账：唯一会改库存余额的动作，所以返回类型多带一个 transactionId 与改动过的余额行。 */
export function postAdjustment(id: string, note?: string): Promise<InventoryAdjustmentPostResult> {
  return api<InventoryAdjustmentPostResult>(`${base}/${id}/post`, 'POST', { note: note ?? null });
}

/** 6.4.2.2 不良品处置单候选（跨服务只读；上游不可达时后端返 503 并说明原因）。 */
export function fetchDefectiveSources(
  params: { keyword?: string; page?: number; size?: number },
): Promise<InventoryAdjustmentDefectiveSourcePage> {
  return api<InventoryAdjustmentDefectiveSourcePage>(
    `warehouse/inventory/adjustment-sources/defective-dispositions${query(params)}`,
  );
}

export function fetchDefectiveInstructions(
  orderId: string,
): Promise<InventoryAdjustmentDefectiveInstruction[]> {
  return api<InventoryAdjustmentDefectiveInstruction[]>(
    `warehouse/inventory/adjustment-sources/defective-dispositions/${orderId}/instructions`,
  );
}

// ------------------------------------------------------------ 标签

export const statusLabels: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_APPROVAL: '待审批',
  APPROVED: '已批待入账',
  REJECTED: '已驳回',
  POSTED: '已过账',
  CANCELLED: '已作废',
};

export const auditActionLabels: Record<string, string> = {
  CREATED: '建单',
  LINE_ADDED: '加行',
  LINE_REMOVED: '删行',
  ATTACHED: '挂凭证',
  ATTACHMENT_REMOVED: '摘凭证',
  SUBMITTED: '提交审批',
  APPROVED: '批准',
  REJECTED: '驳回',
  POSTED: '过账',
  CANCELLED: '作废',
};

/** 单据状态色：已过账是正向完成，待审批/已批待入账是最需要注意的中间态。 */
export function statusTone(status: string): 'ok' | 'warn' | 'bad' {
  if (status === 'REJECTED' || status === 'CANCELLED') return 'bad';
  if (status === 'PENDING_APPROVAL' || status === 'APPROVED') return 'warn';
  return 'ok';
}

/** 当前状态下**允许**的动作：界面上只给合法的按钮，不给点了必然 409 的按钮。 */
export function allowedActions(status: string): string[] {
  switch (status) {
    case 'DRAFT':
      return ['submit', 'cancel'];
    case 'PENDING_APPROVAL':
      return ['approve', 'reject', 'cancel'];
    case 'APPROVED':
      return ['post', 'cancel'];
    default:
      return [];
  }
}

/** 动作的中文名（按钮与提示共用一处）。 */
export const actionLabels: Record<string, string> = {
  submit: '提交待审',
  approve: '批准',
  reject: '驳回',
  cancel: '作废',
  post: '过账',
};

/**
 * 动作说明：**每个动作对余额的影响**。
 *
 * <p>界面上必须显式写出来 —— 「提交」「批准」都不改余额，只有「过账」改。
 * 处理人最怕的是以为「已经调完账了」。
 */
export const actionBalanceNotes: Record<string, string> = {
  submit: '不改余额：只是把单子送上审批队列。',
  approve: '不改余额：批准只是允许入账。',
  reject: '不改余额：驳回后可改完重提。',
  cancel: '不改余额：作废留痕，不删单。',
  post: '**改余额**：写库存分录并同步余额与库位明细。',
};

// ------------------------------------------------------------ 展示

/** 数量格式化：null 显示「—」而不是 0（「没有数」显示成 0 是最危险的误显示）。 */
export function qty(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 4 });
}

/** 带符号数量：`3` 显示成「+3」，`-2` 显示成「−2」。只给裸数字没人敢确认方向填对了没有。 */
export function signedQty(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  if (value > 0) return `+${qty(value)}`;
  if (value < 0) return `−${qty(Math.abs(value))}`;
  return '0';
}

/** 带符号数量的色调：正=增调，负=减调，0=中性。 */
export function signedTone(value: number | null | undefined): 'ok' | 'bad' | 'neutral' {
  if (value === null || value === undefined) return 'neutral';
  if (value > 0) return 'ok';
  if (value < 0) return 'bad';
  return 'neutral';
}

export function formatMoment(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/** 行状态：这一行过账了没有（过账后行不可再改）。 */
export function lineStateText(line: {
  postedAt?: string | null;
  currentBucketVersion?: number | null;
  expectedVersion?: number | null;
}): string {
  if (line.postedAt) return '已过账';
  if (
    line.currentBucketVersion !== null &&
    line.currentBucketVersion !== undefined &&
    line.expectedVersion !== null &&
    line.expectedVersion !== undefined &&
    line.currentBucketVersion !== line.expectedVersion
  ) {
    return '已过期（库位明细又动过）';
  }
  return '待过账';
}

/** 可选原因码：不可用的码保留在列表里但标成不可选，并说明去向。 */
export function reasonOptions(
  reasons: InventoryAdjustmentReasonOption[] | undefined,
): InventoryAdjustmentReasonOption[] {
  return reasons ?? [];
}

/** 可选原因码（只给可用的）：建单下拉里不出现不可用的码。 */
export function selectableReasons(
  reasons: InventoryAdjustmentReasonOption[] | undefined,
): InventoryAdjustmentReasonOption[] {
  return (reasons ?? []).filter((r) => r.available);
}

// ------------------------------------------------------------ 校验

/**
 * 一行调整请求的客户端校验。返回错误文案而不是静默纠正。
 *
 * <p>与后端同一条纪律：方向写反、数量超精度、缺 expected_version 都在这里先拦住，
 * 用户就不用等一个 422 才知道自己填错了。
 */
export function lineProblem(
  reasonCode: string,
  qtyValue: string,
  expectedVersion: number | null,
): string | null {
  if (!reasonCode) return '必须选择调整原因：原因码是受控词表，自由文本会让统计口径失效';
  const text = qtyValue.trim();
  if (text === '') return '必须填写调整数量';
  const parsed = Number(text);
  if (!Number.isFinite(parsed)) return '调整数量必须是数字';
  if (parsed === 0) return '调整数量不能是 0：这一行等于没调';
  if (reasonCode === 'OVERAGE' && parsed < 0)
    return '「盘盈」的调整数量必须是正数：方向写反的调整单在审批时是看不出来的';
  if (reasonCode === 'SHORTAGE' && parsed > 0)
    return '「盘亏」的调整数量必须是负数：方向写反的调整单在审批时是看不出来的';
  const decimals = text.includes('.') ? text.split('.')[1]?.length ?? 0 : 0;
  if (decimals > 4)
    return '调整数量最多 4 位小数（库存精度是 numeric(19,4)）：系统不会替你四舍五入';
  if (expectedVersion === null)
    return '这一行缺少库位明细版本（expected_version）：没有它就无法判断看到的是不是过期数量';
  return null;
}

/** 建单前的整体校验：至少要有一行，且每一行都要过。 */
export function createProblem(
  reasonCode: string,
  note: string,
  lines: { reasonCode: string; qty: string; expectedVersion: number | null }[],
): string | null {
  if (!reasonCode) return '必须选择单据级调整原因';
  if (lines.length === 0) return '至少要有一行：零行的调整单在台账上看起来像已经处理过了';
  for (const [index, line] of lines.entries()) {
    const problem = lineProblem(line.reasonCode || reasonCode, line.qty, line.expectedVersion);
    if (problem) return `第 ${index + 1} 行：${problem}`;
  }
  if (note.trim().length > 500) return '单据说明超过 500 字，请写结论与依据';
  return null;
}

/** 批准必须写理由（与后端同一条纪律：只留一个「已批」状态，事后没人说得清批的是哪条判断）。 */
export function approveProblem(note: string): string | null {
  if (note.trim() === '') return '批准必须写理由：只留一个「已批」状态，事后没人说得清批的是哪条判断';
  if (note.trim().length > 500) return '审批意见超过 500 字';
  return null;
}

/** 驳回必须写意见（只给一个 REJECTED 状态，提交的人无法知道该改什么）。 */
export function rejectProblem(note: string): string | null {
  if (note.trim() === '') return '驳回必须写意见：提交的人需要知道该改什么';
  if (note.trim().length > 500) return '驳回意见超过 500 字';
  return null;
}

/** 统一错误文案。409（状态冲突）、403（无权限）、422（条件不可用）要分开说。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return '当前角色没有此操作权限（需要 warehouse.inventory.update）';
    if (error.status === 404) return '找不到该对象（可能不属于当前租户）';
    if (error.status === 409) return error.message || '状态冲突：这一步现在还不能做（可能库存已经变动）';
    if (error.status === 422) return error.message || '这个条件当前不可用';
    // 503 一律说清「不可用」：上游不可达时后端会带原因（如「读取 6.4.2.2 不良品处置单失败：…」），
    // 直接把那句话原文抛给用户会**看不出这是不可用**，
    // 而用户最容易误判成「这单不能做」——实际上调整单仍可独立发起（不引用来源）。
    if (error.status === 503)
      return error.message
        ? `依赖的服务不可用：${error.message}。调整单仍可独立发起（不引用来源）。`
        : '依赖的服务不可用：调整单仍可独立发起（不引用来源）。';
    return error.message || `请求失败（${error.status}）`;
  }
  if (error instanceof Error) return error.message;
  return '未知错误';
}

/** 单据概要：给列表与详情页共用的一行摘要。 */
export function orderSummary(order: InventoryAdjustmentOrder): string {
  return `${order.code} · ${statusLabels[order.status] ?? order.status} · ${order.lineCount} 行 · 净调整 ${signedQty(order.netQty)}`;
}

/** 附件摘要（凭证清单里的一行）。 */
export function attachmentSummary(attachment: InventoryAdjustmentAttachment): string {
  const size = attachment.sizeBytes === null ? '' : ` · ${Math.round(attachment.sizeBytes / 1024)} KB`;
  return `${attachment.fileName}（${attachment.mediaType || '未知类型'}）${size}`;
}
