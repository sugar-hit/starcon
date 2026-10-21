import type {
  PendingInspectionCancelInput,
  PendingInspectionCapabilities,
  PendingInspectionDisposeInput,
  PendingInspectionDisposeLineInput,
  PendingInspectionOptions,
  PendingInspectionStockPage,
  PendingInspectionTaskCreateInput,
  PendingInspectionTaskDetail,
  PendingInspectionTaskPage,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 8.2.2.2 待检处理前端模型（待检区库存 → 质检 → 质量状态分流）。
 *
 * <p>蓝图 08 卷 8.2.2.2：「管理待检区库存：发起质检任务（6 质检单）、质检通过后转入可售、
 * 质检不通过转残次/报废/退货。」「输出：质量状态变更分录；可售量更新。」
 * 关系段的加粗句是整个模块的硬规则：「<b>待检品不可售</b>是硬规则，质检通过前不能被销售预占。」
 *
 * <p><b>界面纪律</b>（与后端一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>待检清单与处理单列表分开</b>：清单是「仓里还有多少货压在待检区」，
 *       处理单是「这批货走到哪一步了」。两者混在一起就没法回答「还有多少货没检」。</li>
 *   <li><b>建单表单没有「质量状态」选项</b>：质量状态从库存行读出来。
 *       给操作员一个「这是不是待检」的选择框，等于允许他把可售品重新标成待检。</li>
 *   <li><b>质检结果不由人敲</b>：结论来自 6 质检单（trade 服务），页面只展示回读到的三量。
 *       页面明说这一点 —— 免得操作员以为「录不进数字」是系统坏了。</li>
 *   <li><b>处置必须把这批货全部处置掉</b>：各去向数量之和与任务批量不一致时，
 *       提交按钮禁用并说清差多少（后端也会挡一道，但页面先说清省一次往返）。</li>
 *   <li><b>不合格品不能转可售</b>：转可售的量超过质检结论里的可售量时，页面直接拦下。</li>
 *   <li><b>可售量前后对照常驻</b>：处置前后的可售量快照并排显示，
 *       让「质检通过 → 可售量更新」这条输出看得见。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code warehouse/pending-inspections}，刻意不是 {@code goods-receipts} 的子路径 ——
 * 待检处理有质检、有结论、有处置，挂在收货单底下会让「还没检完」与「还没收完」在契约上分不清。
 */

const base = 'warehouse/pending-inspections';

export interface PendingInspectionParams {
  status?: string;
  warehouseId?: string;
  skuId?: string;
  receiptId?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function pendingInspectionQuery(params: PendingInspectionParams): string {
  const parts: string[] = [];
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  if (params.receiptId) parts.push(`receiptId=${encodeURIComponent(params.receiptId)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchPendingInspectionCapabilities() {
  return api<PendingInspectionCapabilities>(`${base}/capabilities`);
}

export function pendingInspectionStockQuery(params: {
  warehouseId?: string;
  locationId?: string;
  skuId?: string;
  keyword?: string;
  page?: number;
  size?: number;
}): string {
  const parts: string[] = [];
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  if (params.locationId) parts.push(`locationId=${encodeURIComponent(params.locationId)}`);
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchPendingInspectionStock(params: {
  warehouseId?: string;
  locationId?: string;
  skuId?: string;
  keyword?: string;
  page?: number;
  size?: number;
}) {
  return api<PendingInspectionStockPage>(`${base}/stock${pendingInspectionStockQuery(params)}`);
}

export function pendingInspectionOptionsQuery(warehouseId?: string): string {
  return warehouseId ? `?warehouseId=${encodeURIComponent(warehouseId)}` : '';
}

export function fetchPendingInspectionOptions(warehouseId?: string) {
  return api<PendingInspectionOptions>(
    `${base}/options${pendingInspectionOptionsQuery(warehouseId)}`,
  );
}

export function fetchPendingInspectionTasks(params: PendingInspectionParams) {
  return api<PendingInspectionTaskPage>(`${base}${pendingInspectionQuery(params)}`);
}

export function fetchPendingInspectionTask(taskId: string) {
  return api<PendingInspectionTaskDetail>(`${base}/${encodeURIComponent(taskId)}`);
}

export function createPendingInspectionTask(input: PendingInspectionTaskCreateInput) {
  return api<PendingInspectionTaskPage['rows'][number]>(base, 'POST', input);
}

/** 发起质检任务（6.4.2.1 质检单，在 trade 服务）。 */
export function requestPendingInspection(taskId: string, templateId: string, notes?: string) {
  return api<PendingInspectionTaskPage['rows'][number]>(
    `${base}/${encodeURIComponent(taskId)}/request-inspection`,
    'POST',
    { templateId, notes: notes ?? null },
  );
}

/** 录入质检结果：数量由后端从质检单回读，这里只能给一句说明。 */
export function recordPendingInspectionResult(taskId: string, notes?: string) {
  return api<PendingInspectionTaskPage['rows'][number]>(
    `${base}/${encodeURIComponent(taskId)}/record-result`,
    'POST',
    { notes: notes ?? null },
  );
}

/** 处置决定：本模块唯一动库存的动作。 */
export function disposePendingInspectionTask(taskId: string, input: PendingInspectionDisposeInput) {
  return api<PendingInspectionTaskPage['rows'][number]>(
    `${base}/${encodeURIComponent(taskId)}/dispose`,
    'POST',
    input,
  );
}

export function cancelPendingInspectionTask(taskId: string, reason?: string) {
  return api<PendingInspectionTaskPage['rows'][number]>(
    `${base}/${encodeURIComponent(taskId)}/cancel`,
    'POST',
    { reason: reason ?? null },
  );
}

// ================================================================ 纯函数（页面据此渲染）

export const pendingInspectionStatuses = [
  'PENDING',
  'INSPECTION_REQUESTED',
  'RESULT_RECORDED',
  'DISPOSED',
  'CANCELLED',
] as const;

export function pendingInspectionStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '待发起质检';
    case 'INSPECTION_REQUESTED':
      return '已发起质检';
    case 'RESULT_RECORDED':
      return '已录入结果';
    case 'DISPOSED':
      return '已处置';
    case 'CANCELLED':
      return '已取消';
    default:
      return status;
  }
}

/** 状态徽章色调（Badge 的受控词表）：待办黄 / 进行中蓝 / 待处置品牌色 / 完成绿 / 取消中性。 */
export function pendingInspectionStatusTone(
  status: string,
): 'primary' | 'success' | 'warning' | 'info' | 'neutral' | 'danger' | 'brand' {
  switch (status) {
    case 'PENDING':
      return 'warning';
    case 'INSPECTION_REQUESTED':
      return 'info';
    case 'RESULT_RECORDED':
      return 'brand';
    case 'DISPOSED':
      return 'success';
    case 'CANCELLED':
      return 'neutral';
    default:
      return 'neutral';
  }
}

export const dispositionTypes = ['SELLABLE', 'DEFECTIVE', 'SCRAP', 'RETURN'] as const;

export type DispositionType = (typeof dispositionTypes)[number];

export function dispositionLabel(type: string): string {
  switch (type) {
    case 'SELLABLE':
      return '转可售';
    case 'DEFECTIVE':
      return '转残次';
    case 'SCRAP':
      return '报废（离仓）';
    case 'RETURN':
      return '退货（离仓）';
    case 'MIXED':
      return '混合去向';
    default:
      return type;
  }
}

/** 留仓的去向（转可售 / 转残次）需要目标库位，离仓的去向（报废 / 退货）不需要。 */
export function dispositionStaysInWarehouse(type: string): boolean {
  return type === 'SELLABLE' || type === 'DEFECTIVE';
}

export function qualityRoleLabel(role: string): string {
  switch (role) {
    case 'SELLABLE':
      return '可售';
    case 'PENDING_INSPECTION':
      return '待检';
    case 'DEFECTIVE':
      return '残次';
    default:
      return role;
  }
}

export function locationTypeLabel(type: string): string {
  switch (type) {
    case 'STORAGE':
      return '存储';
    case 'RECEIVING':
      return '收货暂存';
    case 'SHIPPING':
      return '发货暂存';
    case 'QUARANTINE':
      return '待检';
    case 'DEFECTIVE':
      return '残次';
    case 'FROZEN':
      return '冻结';
    default:
      return type;
  }
}

export function formatQuantity(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'string' ? Number(value) : value;
  if (!Number.isFinite(n)) return String(value);
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

export function formatSignedQuantity(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'string' ? Number(value) : value;
  if (!Number.isFinite(n)) return String(value);
  const text = n.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
  return n > 0 ? `+${text}` : text;
}

/** 质检结论的三个量：合格 / 不合格 / 让步接收。 */
export function inspectionQuantities(row: {
  qualifiedQuantity: number | null;
  unqualifiedQuantity: number | null;
  concessionQuantity: number | null;
}): { qualified: number; unqualified: number; concession: number; sellable: number } {
  const qualified = row.qualifiedQuantity ?? 0;
  const unqualified = row.unqualifiedQuantity ?? 0;
  const concession = row.concessionQuantity ?? 0;
  return { qualified, unqualified, concession, sellable: qualified + concession };
}

/** 处置前的一次自检：各去向数量之和必须等于任务批量。 */
export function validateDispositionTotals(
  lines: { quantity: number }[],
  taskQuantity: number,
): { ok: boolean; total: number; diff: number; message: string } {
  const total = lines.reduce((sum, line) => sum + (Number(line.quantity) || 0), 0);
  const diff = Number(taskQuantity || 0) - total;
  if (Math.abs(diff) < 1e-9) {
    return { ok: true, total, diff: 0, message: '各去向数量之和与任务批量一致' };
  }
  return {
    ok: false,
    total,
    diff,
    message:
      diff > 0
        ? `还差 ${formatQuantity(diff)} 没有去向：一批待检货必须被全部处置掉`
        : `多处置了 ${formatQuantity(-diff)}：没有这么多货可处置`,
  };
}

/** 处置前的一次自检：转可售的量不得超过质检结论里的可售量。 */
export function validateSellableLimit(
  lines: { dispositionType: string; quantity: number }[],
  sellableQuantity: number,
): { ok: boolean; sellableSum: number; limit: number; message: string } {
  const sellableSum = lines
    .filter((line) => line.dispositionType === 'SELLABLE')
    .reduce((sum, line) => sum + (Number(line.quantity) || 0), 0);
  const limit = Number(sellableQuantity || 0);
  if (sellableSum - limit < 1e-9) {
    return { ok: true, sellableSum, limit, message: '转可售的数量在质检结论的可售量之内' };
  }
  return {
    ok: false,
    sellableSum,
    limit,
    message: `转可售 ${formatQuantity(sellableSum)} 超过质检结论里的可售量 ${formatQuantity(limit)}：不合格品不能被转成可售`,
  };
}

/** 建单时的自检：批量必须在库存行在手量之内。 */
export function validateCreateQuantity(
  quantity: number,
  onHand: number,
): { ok: boolean; message: string } {
  if (!Number.isFinite(quantity) || quantity <= 0) {
    return { ok: false, message: '批量必须大于 0' };
  }
  if (quantity - onHand > 1e-9) {
    return { ok: false, message: `批量超过库位上的实际在手量 ${formatQuantity(onHand)}` };
  }
  return { ok: true, message: '' };
}

/** 动作可用性：哪些按钮在当前状态下点得动。 */
export function canRequestInspection(status: string): boolean {
  return status === 'PENDING';
}

export function canRecordResult(status: string): boolean {
  return status === 'INSPECTION_REQUESTED';
}

export function canDispose(status: string): boolean {
  return status === 'RESULT_RECORDED';
}

export function canCancel(status: string): boolean {
  return status === 'PENDING' || status === 'INSPECTION_REQUESTED';
}

/** 分录净额：0 = 只是换了质量状态与库位（货还在本仓）；负数 = 净离仓。 */
export function netOnHandDeltaText(net: number | string | null | undefined): string {
  const n = Number(net ?? 0);
  if (!Number.isFinite(n)) return '—';
  if (Math.abs(n) < 1e-9) return '0（货还在本仓，只是换了质量状态与库位）';
  return `${formatSignedQuantity(n)}（净离仓：报废与退货的货离开本仓）`;
}
