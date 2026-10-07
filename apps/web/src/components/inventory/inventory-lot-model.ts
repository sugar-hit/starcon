import type {
  LotArchiveCreateRequest,
  LotArchivePage,
  LotArchiveRow,
  LotArchiveSaved,
  LotArchiveUpdateRequest,
  LotDistribution,
  LotMovements,
  SkuRef,
  WarehouseRef,
} from '../../../../../packages/api-contracts/src/catalog';
import { api } from './api';

/**
 * 7.3.1.1 批次管理前端模型。
 *
 * <p>界面上最容易误读的三处，这个模块都显式处理了：
 *
 * <ul>
 *   <li>**过期是日历事实，状态是人工判断**。行上同时给 `expired` 与 `status`：
 *       一个 `status=ACTIVE` 但 `expired=true` 的批次是真实存在的状态（货还在，但日期过了、
 *       运营还没标），把它算成「已过期」会掩盖待办，算成「在库」会误发。
 *   <li>**在手 / 预占 / 可用分三列**。预占不减在手，只减可用；合成一列会让人以为
 *       「这批有 30 件」就等于「能发 30 件」。
 *   <li>**出入库历史标着归属口径**。后端返回的 `attribution=BALANCE_GRANULAR` 必须显示出来：
 *       流水按余额行记账，余额行不含批次维度，所以那是「批次相关流水」而非批次精确流水。
 *       不说清的话，用户会把同一个余额行上别的批次的量算到这一批头上。
 * </ul>
 */

/** 批次列表查询条件。与后端 `LotArchiveQuery` 一一对应。 */
export interface LotParams {
  skuId?: string;
  warehouseId?: string;
  status?: string[];
  origin?: string[];
  sourceSystem?: string[];
  supplierRefId?: string;
  keyword?: string;
  expiringWithinDays?: number;
  inStock?: boolean;
  sort?: string;
  page?: number;
  size?: number;
}

function lotQuery(params: LotParams): string {
  const parts: string[] = [];
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  for (const s of params.status ?? []) parts.push(`status=${encodeURIComponent(s)}`);
  for (const o of params.origin ?? []) parts.push(`origin=${encodeURIComponent(o)}`);
  for (const s of params.sourceSystem ?? []) parts.push(`sourceSystem=${encodeURIComponent(s)}`);
  if (params.supplierRefId) parts.push(`supplierRefId=${encodeURIComponent(params.supplierRefId)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.expiringWithinDays !== undefined)
    parts.push(`expiringWithinDays=${params.expiringWithinDays}`);
  if (params.inStock !== undefined) parts.push(`inStock=${params.inStock}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchLotArchive(params: LotParams) {
  return api<LotArchivePage>(`warehouse/inventory/lots${lotQuery(params)}`);
}

export function fetchLotArchiveDetail(lotId: string) {
  return api<LotArchiveRow>(`warehouse/inventory/lots/${encodeURIComponent(lotId)}`);
}

/** 批次库存分布：仓库级 + 库位级切片。 */
export function fetchLotDistribution(lotId: string) {
  return api<LotDistribution>(`warehouse/inventory/lots/${encodeURIComponent(lotId)}/distribution`);
}

/**
 * 批次出入库历史。后端复用 7.1.2.1 台账，分录下钻仍走
 * `warehouse/inventory/ledger/{transactionId}`，所以这里不重复实现下钻。
 */
export function fetchLotMovements(
  lotId: string,
  params: { from?: string; to?: string; warehouseId?: string; page?: number; size?: number } = {},
) {
  return api<LotMovements>(
    `warehouse/inventory/lots/${encodeURIComponent(lotId)}/movements${lotQuery(params)}`,
  );
}

/**
 * 建立批次。收货生成与手工建立共用这一条：`origin` 显式声明，
 * 而不是从 `sourceSystem` 反推（手工补登也会带来源单据）。
 */
export function createLot(input: LotArchiveCreateRequest) {
  return api<LotArchiveSaved>('warehouse/inventory/lots', 'POST', input);
}

/** 修改批次属性。批号 / SKU / origin 是身份，不可改；未填的字段保持不变。 */
export function updateLot(lotId: string, input: LotArchiveUpdateRequest) {
  return api<LotArchiveSaved>(
    `warehouse/inventory/lots/${encodeURIComponent(lotId)}`,
    'PUT',
    input,
  );
}

/** 批次状态标签。未知码原样显示而不是静默吞掉。 */
export const lotStatusLabels: Record<string, string> = {
  ACTIVE: '在库',
  DEPLETED: '已用尽',
  EXPIRED: '已过期',
};

export const lotOriginLabels: Record<string, string> = {
  RECEIPT: '收货生成',
  MANUAL: '手工建立',
};

export const lotSourceSystemLabels: Record<string, string> = {
  PURCHASE: '采购单',
  RECEIPT: '收货单',
  MANUAL: '手工补录',
};

/** 排序键。后端有白名单，非法值返回 400，所以下拉只给这几个。 */
export const lotSortOptions = [
  { value: 'LOT_CODE', label: '批号' },
  { value: 'EXPIRY', label: '失效日期（最近到期在前）' },
  { value: 'ON_HAND', label: '在手数量' },
  { value: 'RECEIVED_ON', label: '收货日期' },
  { value: 'PRODUCED_ON', label: '生产日期' },
  { value: 'UPDATED_AT', label: '最近更新' },
];

/**
 * 剩余有效期的展示文案。
 *
 * <p>三种情况分开说：没有失效日期（不适用）、已过期（负数）、还有 N 天。
 * 把「已过期 2471 天」说成「剩余 -2471 天」读起来别扭，所以这里单独给文案。
 */
export function expiryText(expiresOn: string | null, daysToExpiry: number | null): string {
  if (!expiresOn) return '非效期品';
  if (daysToExpiry === null) return expiresOn;
  if (daysToExpiry < 0) return `${expiresOn}（已过期 ${Math.abs(daysToExpiry)} 天）`;
  if (daysToExpiry === 0) return `${expiresOn}（今天到期）`;
  return `${expiresOn}（还有 ${daysToExpiry} 天）`;
}

/** 来源单据的可读文案：跨服务引用，本服务只有 id 与固化的单号，不联查采购/收货。 */
export function sourceText(row: {
  sourceSystem: string | null;
  sourceRefCode: string;
  supplierName: string;
}): string {
  const parts: string[] = [];
  if (row.sourceSystem) {
    const label = lotSourceSystemLabels[row.sourceSystem] ?? row.sourceSystem;
    // 单号必须 trim 后再判空：只填了空格时直接拼上去会留下悬空的「收货单   」
    const code = row.sourceRefCode.trim();
    parts.push(code ? `${label} ${code}` : label);
  }
  if (row.supplierName.trim()) parts.push(`供应商 ${row.supplierName.trim()}`);
  return parts.length ? parts.join(' · ') : '手工补录（无来源单据）';
}

export type { SkuRef, WarehouseRef };
