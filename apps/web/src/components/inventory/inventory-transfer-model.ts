import type {
  TransferCloseRequest,
  TransferMovements,
  TransferOrderActionRequest,
  TransferOrderCreateRequest,
  TransferOrderDetail,
  TransferOrderPage,
  TransferOrderSaved,
  TransferOrderUpdateRequest,
  TransferReceiveRequest,
  TransferShipRequest,
} from '../../../../../packages/api-contracts/src/index';
import { api } from './api';

/**
 * 7.5.1.1 调拨单前端模型。
 *
 * <p>界面上最容易误读的两处，这个模块都显式处理了：
 *
 * <ul>
 *   <li>**调拨不是瞬时**（蓝图原文）。发出只减源仓、货进在途，目的仓要等收货才增加。
 *       所以列表把「仍在途」单独占一列，而不是把「已发出」直接当成「到货了」——
 *       后者正是蓝图点名批评的「异地收发同一瞬间」。
 *   <li>**未发出量不是差异**。请求 100、发出 60，那 40 是还没走，不是路上丢了。
 *       把两者混在一列会让「还在发货计划中」被当成「货损待查」。
 * </ul>
 */

/** 列表查询条件。与后端 `TransferOrderQuery` 一一对应。 */
export interface TransferParams {
  fromWarehouseId?: string;
  toWarehouseId?: string;
  skuId?: string;
  status?: string[];
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

function transferQuery(params: TransferParams): string {
  const parts: string[] = [];
  if (params.fromWarehouseId)
    parts.push(`fromWarehouseId=${encodeURIComponent(params.fromWarehouseId)}`);
  if (params.toWarehouseId) parts.push(`toWarehouseId=${encodeURIComponent(params.toWarehouseId)}`);
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  for (const s of params.status ?? []) parts.push(`status=${encodeURIComponent(s)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchTransfers(params: TransferParams) {
  return api<TransferOrderPage>(`warehouse/inventory/transfers${transferQuery(params)}`);
}

export function fetchTransferDetail(orderId: string) {
  return api<TransferOrderDetail>(`warehouse/inventory/transfers/${encodeURIComponent(orderId)}`);
}

/** 本单分录：后端复用 7.1.2.1 台账，`attributionNote` 必须在界面上显示。 */
export function fetchTransferMovements(orderId: string) {
  return api<TransferMovements>(
    `warehouse/inventory/transfers/${encodeURIComponent(orderId)}/movements`,
  );
}

/** 建单（草稿）。草稿不动库存。 */
export function createTransfer(input: TransferOrderCreateRequest) {
  return api<TransferOrderSaved>('warehouse/inventory/transfers', 'POST', input);
}

export function updateTransfer(orderId: string, input: TransferOrderUpdateRequest) {
  return api<TransferOrderSaved>(
    `warehouse/inventory/transfers/${encodeURIComponent(orderId)}`,
    'PUT',
    input,
  );
}

/** 确认：草稿 → 待发。之后明细锁定。 */
export function confirmTransfer(orderId: string, expectedVersion: number) {
  // notes 必填在契约里（后端是 @NotBlank 之外的普通 String），这里显式给空串而不是省略 ——
  // `fail-on-unknown-properties` 关掉的只是未知字段，缺字段照样 400。
  const input: TransferOrderActionRequest = { expectedVersion, notes: '' };
  return api<TransferOrderSaved>(
    `warehouse/inventory/transfers/${encodeURIComponent(orderId)}/confirm`,
    'POST',
    input,
  );
}

/** 发出：减源仓、挂在途。可分批发。 */
export function shipTransfer(orderId: string, input: TransferShipRequest) {
  return api<TransferOrderSaved>(
    `warehouse/inventory/transfers/${encodeURIComponent(orderId)}/ship`,
    'POST',
    input,
  );
}

/** 实收：转入目的仓、冲减在途。可分次收。 */
export function receiveTransfer(orderId: string, input: TransferReceiveRequest) {
  return api<TransferOrderSaved>(
    `warehouse/inventory/transfers/${encodeURIComponent(orderId)}/receive`,
    'POST',
    input,
  );
}

/** 结案：把收不到的部分如实记为差异，在途归零。 */
export function closeTransfer(orderId: string, input: TransferCloseRequest) {
  return api<TransferOrderSaved>(
    `warehouse/inventory/transfers/${encodeURIComponent(orderId)}/close`,
    'POST',
    input,
  );
}

/** 六态标签。未收录的码原样显示，不静默吞掉。 */
export const transferStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_SHIPMENT: '待发',
  IN_TRANSIT: '在途',
  PARTIALLY_RECEIVED: '部分收',
  RECEIVED: '已收',
  DISCREPANCY: '差异',
};

/** 差异原因。后端有受控词表，非法值返回 400，所以下拉只给这几个。 */
export const transferDiscrepancyLabels: Record<string, string> = {
  LOST: '运输丢失',
  DAMAGED: '破损',
  REJECTED: '目的仓拒收',
  SHORT_SHIPPED: '发运即短少',
};

export const transferSortOptions = [
  { value: 'CREATED_AT', label: '建单时间' },
  { value: 'UPDATED_AT', label: '最近更新' },
  { value: 'ORDER_CODE', label: '单号' },
  { value: 'EXPECTED_ARRIVE', label: '预计到达（最近在前）' },
  { value: 'EXPECTED_SHIP', label: '预计发出（最近在前）' },
];

/**
 * 一次可以推进的动作，按状态给出。
 *
 * <p>界面上只显示当前状态**允许**的按钮，而不是显示全部再在后端报错 ——
 * 「已收的单还显示收货按钮」会让运营以为能重复收货。
 */
export function transferActions(status: string): string[] {
  switch (status) {
    case 'DRAFT':
      return ['confirm'];
    case 'PENDING_SHIPMENT':
    case 'IN_TRANSIT':
    case 'PARTIALLY_RECEIVED':
      return ['ship', 'receive', 'close'];
    default:
      // RECEIVED / DISCREPANCY 都是终态：货已经不在路上了，不能再发再收。
      return [];
  }
}

/**
 * 某一行还能发多少。
 *
 * <p>用后端给的 `unshippedQuantity`（请求 − 已发）而不是前端拿四个数自己减 ——
 * 前端自算就得在前端复制一遍守恒口径，而那个口径一旦和后端漂移，界面上就会出现
 * 「还能发 0」但点下去后端说没数量的怪事。
 */
export function shippable(line: { unshippedQuantity: number }): number {
  return Math.max(0, Number(line.unshippedQuantity) || 0);
}

/** 某一行还能收多少：在途余量。 */
export function receivable(line: { inTransitQuantity: number }): number {
  return Math.max(0, Number(line.inTransitQuantity) || 0);
}

/**
 * 守恒式的自检结果。
 *
 * <p>详情页把它显式算出来显示：已发出 ≠ 已实收 + 差异 + 在途时给出警告。
 * 正常情况下永远为真（后端有 DB 触发器兜底），界面上再算一次是为了让
 * 「这个数字是怎么来的」在页面上自解释，而不是让人背口诀。
 */
export function conservationText(parts: {
  shippedQuantity: number;
  receivedQuantity: number;
  discrepancyQuantity: number;
  inTransitQuantity: number;
}): { ok: boolean; text: string } {
  const shipped = Number(parts.shippedQuantity) || 0;
  const received = Number(parts.receivedQuantity) || 0;
  const discrepancy = Number(parts.discrepancyQuantity) || 0;
  const transit = Number(parts.inTransitQuantity) || 0;
  const sum = received + discrepancy + transit;
  const ok = Math.abs(shipped - sum) < 0.0001;
  return {
    ok,
    text: `已发出 ${fmt(shipped)} = 已实收 ${fmt(received)} + 差异 ${fmt(discrepancy)} + 仍在途 ${fmt(transit)}${ok ? '' : `（差 ${fmt(shipped - sum)}）`}`,
  };
}

/** 数量展示：空值给破折号，不显示 0 与「没有」的区别之外的噪音。 */
export function fmt(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return Number(value).toLocaleString('zh-CN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 4,
  });
}

/**
 * 时间展示。**带偏移量**显示，避免跨时区看单子时把「预计到达」读错一天。
 *
 * <p>后端给的是 ISO-8601（含偏移），这里只做本地化不改时刻 —— 改时刻就是改事实。
 */
export function moment(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/** 源仓 → 目标仓。 */
export function routeText(row: {
  fromWarehouseCode: string;
  fromWarehouseName: string;
  toWarehouseCode: string;
  toWarehouseName: string;
}): string {
  return `${row.fromWarehouseCode} ${row.fromWarehouseName} → ${row.toWarehouseCode} ${row.toWarehouseName}`;
}
