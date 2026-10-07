import type {
  LegalEntityTransferApproveRequest,
  LegalEntityTransferCancelRequest,
  LegalEntityTransferCloseRequest,
  LegalEntityTransferCreateRequest,
  LegalEntityTransferDetail,
  LegalEntityTransferMovements,
  LegalEntityTransferPage,
  LegalEntityTransferSaved,
  LegalEntityTransferSettlementHandoff,
  LegalEntityTransferShipRequest,
  LegalEntityTransferReceiveRequest,
  LegalEntityTransferUpdateRequest,
} from '../../../../../packages/api-contracts/src/catalog';
import { api } from './api';

/**
 * 7.5.2.1 跨法人调拨前端模型。
 *
 * <p>界面上最容易误读的三处，这个模块都显式处理了：
 *
 * <ul>
 *   <li>**这不是普通调拨**（蓝图原话「不能当作普通同仓调拨」）。转出法人与转入法人是页面的第一视觉要素，
 *       定价与金额也是 —— 货权变了意味着利润归属变了，把它排成「调拨单的一个筛选项」就抹掉了本模块的全部意义。
 *   <li>**货权在实收才转移**。发出只扣转出方、货挂在途；结算凭据在发出时就开出来了。
 *       不把这两个时刻分开显示，运营会把「已开凭据」读成「货已经归对方了」。
 *   <li>**逐币种看金额**。整单单币种，但列表跨单，跨币种直接相加是错的，所以汇总按币种分行给。
 * </ul>
 */

/** 列表查询条件。与后端 `LegalEntityTransferQuery` 一一对应。 */
export interface CrossEntityTransferParams {
  fromLegalEntityId?: string;
  toLegalEntityId?: string;
  fromWarehouseId?: string;
  toWarehouseId?: string;
  skuId?: string;
  status?: string[];
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

function query(params: CrossEntityTransferParams): string {
  const parts: string[] = [];
  if (params.fromLegalEntityId)
    parts.push(`fromLegalEntityId=${encodeURIComponent(params.fromLegalEntityId)}`);
  if (params.toLegalEntityId)
    parts.push(`toLegalEntityId=${encodeURIComponent(params.toLegalEntityId)}`);
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

export function fetchCrossEntityTransfers(params: CrossEntityTransferParams) {
  return api<LegalEntityTransferPage>(
    `warehouse/inventory/cross-entity-transfers${query(params)}`,
  );
}

export function fetchCrossEntityTransferDetail(transferId: string) {
  return api<LegalEntityTransferDetail>(
    `warehouse/inventory/cross-entity-transfers/${encodeURIComponent(transferId)}`,
  );
}

/** 本单分录：后端复用 7.1.2.1 台账，`attributionNote` 必须在界面上显示。 */
export function fetchCrossEntityTransferMovements(
  transferId: string,
  params: { page?: number; size?: number } = {},
) {
  const qs = new URLSearchParams();
  if (params.page !== undefined) qs.set('page', String(params.page));
  if (params.size !== undefined) qs.set('size', String(params.size));
  const suffix = qs.toString() ? `?${qs.toString()}` : '';
  return api<LegalEntityTransferMovements>(
    `warehouse/inventory/cross-entity-transfers/${encodeURIComponent(transferId)}/movements${suffix}`,
  );
}

/** 内部结算凭据的取数形状（12 财务的反查入口）。未发运时后端 404。 */
export function fetchCrossEntitySettlement(transferId: string) {
  return api<LegalEntityTransferSettlementHandoff>(
    `warehouse/inventory/cross-entity-transfers/${encodeURIComponent(transferId)}/settlement`,
  );
}

/** 建单（草稿）。草稿不动库存，也不影响货权。 */
export function createCrossEntityTransfer(input: LegalEntityTransferCreateRequest) {
  return api<LegalEntityTransferSaved>('warehouse/inventory/cross-entity-transfers', 'POST', input);
}

export function updateCrossEntityTransfer(
  transferId: string,
  input: LegalEntityTransferUpdateRequest,
) {
  return api<LegalEntityTransferSaved>(
    `warehouse/inventory/cross-entity-transfers/${encodeURIComponent(transferId)}`,
    'PUT',
    input,
  );
}

/**
 * 转出方确认。与转入方确认是**两个独立端点**：
 * 让调用方在请求体里声明自己代表哪一方，等于让它可以自称另一方。
 */
export function confirmSourceSide(transferId: string, expectedVersion: number, notes = '') {
  const input: LegalEntityTransferApproveRequest = { expectedVersion, notes };
  return api<LegalEntityTransferSaved>(
    `warehouse/inventory/cross-entity-transfers/${encodeURIComponent(transferId)}/source-confirm`,
    'POST',
    input,
  );
}

/** 转入方确认。必须与转出方确认不是同一个人（后端 DB 触发器钉死）。 */
export function confirmTargetSide(transferId: string, expectedVersion: number, notes = '') {
  const input: LegalEntityTransferApproveRequest = { expectedVersion, notes };
  return api<LegalEntityTransferSaved>(
    `warehouse/inventory/cross-entity-transfers/${encodeURIComponent(transferId)}/target-confirm`,
    'POST',
    input,
  );
}

/** 发出：扣转出方余额、挂在途、开具内部结算凭据。整单发运。 */
export function shipCrossEntityTransfer(
  transferId: string,
  input: LegalEntityTransferShipRequest,
) {
  return api<LegalEntityTransferSaved>(
    `warehouse/inventory/cross-entity-transfers/${encodeURIComponent(transferId)}/ship`,
    'POST',
    input,
  );
}

/** 实收：转入方入库（货权转移）、冲减在途、确认凭据。 */
export function receiveCrossEntityTransfer(
  transferId: string,
  input: LegalEntityTransferReceiveRequest,
) {
  return api<LegalEntityTransferSaved>(
    `warehouse/inventory/cross-entity-transfers/${encodeURIComponent(transferId)}/receive`,
    'POST',
    input,
  );
}

/** 结案：发出过但收不到的部分如实记为差异。凭据不因此作废（部分货权确实转移了）。 */
export function closeCrossEntityTransfer(transferId: string, input: LegalEntityTransferCloseRequest) {
  return api<LegalEntityTransferSaved>(
    `warehouse/inventory/cross-entity-transfers/${encodeURIComponent(transferId)}/close`,
    'POST',
    input,
  );
}

/** 作废：仅发运之前可用。 */
export function cancelCrossEntityTransfer(
  transferId: string,
  input: LegalEntityTransferCancelRequest,
) {
  return api<LegalEntityTransferSaved>(
    `warehouse/inventory/cross-entity-transfers/${encodeURIComponent(transferId)}/cancel`,
    'POST',
    input,
  );
}

/** 八态标签。未收录的码原样显示，不静默吞掉。 */
export const crossEntityStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_SOURCE_CONFIRM: '待转出方确认',
  PENDING_TARGET_CONFIRM: '待转入方确认',
  CONFIRMED: '双方已确认',
  IN_TRANSIT: '在途',
  COMPLETED: '已交割',
  DISCREPANCY: '差异',
  CANCELLED: '已作废',
};

/** 转移定价口径。记录「这个价怎么来的」比只记金额更重要。 */
export const crossEntityValuationLabels: Record<string, string> = {
  MANUAL: '人工定价',
  TRANSFER_COST: '转出方成本',
  LOCAL_COST: '转入方当地成本',
};

export const crossEntitySortOptions = [
  { value: 'CREATED_AT', label: '建单时间' },
  { value: 'UPDATED_AT', label: '最近更新' },
  { value: 'TRANSFER_CODE', label: '单号' },
  { value: 'TOTAL_AMOUNT', label: '金额' },
  { value: 'EXPECTED_ARRIVE', label: '预计到达（最近在前）' },
];

/** 结算凭据状态。没有「已入账」—— 那是 12 财务的账，本模块无权宣告。 */
export const crossEntitySettlementLabels: Record<string, string> = {
  ISSUED: '已开具（待对方确认）',
  ACKNOWLEDGED: '已被转入方确认',
};

/**
 * 当前状态下允许推进的动作，按状态给出。
 *
 * <p>界面上只显示当前状态**允许**的按钮，而不是显示全部再让后端报错 ——
 * 「已交割的单还显示发货按钮」会让运营以为能重复发货或重复转移货权。
 */
export function crossEntityActions(status: string): string[] {
  switch (status) {
    case 'DRAFT':
      return ['source-confirm'];
    case 'PENDING_TARGET_CONFIRM':
      return ['target-confirm'];
    case 'CONFIRMED':
      return ['ship', 'cancel'];
    case 'IN_TRANSIT':
      return ['receive', 'close'];
    case 'PENDING_SOURCE_CONFIRM':
      return ['cancel'];
    default:
      // COMPLETED / DISCREPANCY / CANCELLED 都是终态：货权已经转移或已经结束。
      return [];
  }
}

/**
 * 某一行的实收上限：在途余量。
 *
 * <p>用后端给的 `inTransitQuantity` 而不是前端拿三个数自己减 —— 前端自算就得在前端复制一遍守恒口径，
 * 而那个口径一旦和后端漂移，界面上就会出现「还能收 0」但点下去后端说没数量的怪事。
 */
export function receivable(line: { inTransitQuantity: number }): number {
  return Math.max(0, Number(line.inTransitQuantity) || 0);
}

/** 某一行还能实收多少 = 调拨量 − 已实收 − 已确认差异。与后端同一口径。 */
export function remainingToReceive(line: {
  quantity: number;
  receivedQuantity: number;
  discrepancyQuantity: number;
}): number {
  const rest =
    (Number(line.quantity) || 0) -
    (Number(line.receivedQuantity) || 0) -
    (Number(line.discrepancyQuantity) || 0);
  return Math.max(0, rest);
}

/**
 * 数量守恒的自检结果。
 *
 * <p>详情页把它显式算出来显示：调拨量 ≠ 已实收 + 差异 + 在途时给出警告。
 * 正常情况下永远为真（后端有 DB CHECK 兜底），界面上再算一次是为了让
 * 「这个数字是怎么来的」在页面上自解释。
 */
export function conservationText(parts: {
  totalQuantity: number;
  receivedQuantity: number;
  discrepancyQuantity: number;
  inTransitQuantity: number;
}): { ok: boolean; text: string } {
  const total = Number(parts.totalQuantity) || 0;
  const received = Number(parts.receivedQuantity) || 0;
  const discrepancy = Number(parts.discrepancyQuantity) || 0;
  const transit = Number(parts.inTransitQuantity) || 0;
  const sum = received + discrepancy + transit;
  const ok = Math.abs(total - sum) < 0.0001;
  return {
    ok,
    text: `调拨 ${fmt(total)} = 已实收 ${fmt(received)} + 差异 ${fmt(discrepancy)} + 仍在途 ${fmt(transit)}${ok ? '' : `（差 ${fmt(total - sum)}）`}`,
  };
}

/** 数量展示：空值给破折号。 */
export function fmt(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return Number(value).toLocaleString('zh-CN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 4,
  });
}

/**
 * 金额展示：**必须带币种**。
 *
 * <p>整单单币种但列表跨单，跨币种直接相加是错的，所以这一列从不省略币种。
 */
export function money(amount: number | null | undefined, currency: string | null | undefined) {
  if (amount === null || amount === undefined) return '—';
  const text = Number(amount).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 4,
  });
  return currency ? `${text} ${currency}` : text;
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

/** 转出法人 → 转入法人。这是本模块的第一视觉要素。 */
export function partiesText(row: {
  fromLegalEntityCode: string;
  fromLegalEntityName: string;
  toLegalEntityCode: string;
  toLegalEntityName: string;
}): string {
  return `${row.fromLegalEntityCode} ${row.fromLegalEntityName} → ${row.toLegalEntityCode} ${row.toLegalEntityName}`;
}

/** 转出仓 → 转入仓。 */
export function routeText(row: {
  fromWarehouseCode: string;
  fromWarehouseName: string;
  toWarehouseCode: string;
  toWarehouseName: string;
}): string {
  return `${row.fromWarehouseCode} ${row.fromWarehouseName} → ${row.toWarehouseCode} ${row.toWarehouseName}`;
}
