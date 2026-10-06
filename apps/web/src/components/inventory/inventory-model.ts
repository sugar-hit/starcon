import type {
  DimensionCapability,
  InventoryOverview,
  InventoryPolicy,
  InventoryPolicyInput,
  InventoryPolicySaved,
  LegalEntityRef,
  OverviewGroup,
  OverviewRow,
  QualityStatusRef,
  WarehouseRef,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from './api';

/**
 * 7.1.1.1 库存总览前端模型。
 *
 * <p>界面上最容易出错的三处，这个模块都显式处理了：
 *
 * <ul>
 *   <li>**「未设阈值」不能画成「未超储」**。超储是拿在手和阈值比出来的，没有阈值就没有
 *       这个判断。两者含义不同（照 catalog `unregisteredSkus` 同一口径），所以行上与
 *       合计上都分开显示。
 *   <li>**在手 ≠ 可用**。在手含在途，可用不含 —— 未到货的货不能拿去发货。数字看着接近，
 *       但差的就是在途，界面上必须把在途单列一行。
 *   <li>**合计不受分页影响**。合计是全量的，翻页时它不变；否则"翻到第二页总数变小"
 *       会被当成数据丢了。
 * </ul>
 */

// ------------------------------------------------------------ 接口

export function fetchOverview(params: OverviewParams) {
  return api<InventoryOverview>(`warehouse/inventory/overview${query(params)}`);
}

export function fetchCapabilities() {
  return api<DimensionCapability[]>('warehouse/inventory/capabilities');
}

export function fetchWarehouses() {
  return api<WarehouseRef[]>('warehouse/warehouses');
}

export function fetchLegalEntities() {
  return api<LegalEntityRef[]>('warehouse/legal-entities');
}

export function fetchQualityStatuses() {
  return api<QualityStatusRef[]>('warehouse/quality-statuses');
}

export function fetchPolicies(scopeType: string) {
  return api<InventoryPolicy[]>(
    `warehouse/inventory/policies${scopeType ? `?scopeType=${encodeURIComponent(scopeType)}` : ''}`,
  );
}

export function savePolicy(id: string | null, input: InventoryPolicyInput) {
  return api<InventoryPolicySaved>(
    id ? `warehouse/inventory/policies/${id}` : 'warehouse/inventory/policies',
    id ? 'PUT' : 'POST',
    input,
  );
}

export function deletePolicy(id: string) {
  return api<{ deleted: boolean }>(`warehouse/inventory/policies/${id}`, 'DELETE');
}

// ------------------------------------------------------------ 查询参数

export interface OverviewParams {
  warehouseId?: string;
  legalEntityId?: string;
  keyword?: string;
  zeroStock?: boolean;
  overstock?: boolean;
  sort?: string;
  page?: number;
  size?: number;
}

/** 拼查询串。空值不拼进去：后端把"未传"和"传空串"当成两种含义处理。 */
export function query(params: OverviewParams) {
  const parts: string[] = [];
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  if (params.legalEntityId) parts.push(`legalEntityId=${encodeURIComponent(params.legalEntityId)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.zeroStock !== undefined) parts.push(`zeroStock=${params.zeroStock}`);
  if (params.overstock !== undefined) parts.push(`overstock=${params.overstock}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ------------------------------------------------------------ 展示口径

/**
 * 数量格式化。
 *
 * <p>固定两位小数：库存数量会出现 0.001 这种小数量（散装称重），
 * 但把 4 位精度全铺在表里会让人读不出量级。
 */
export function qty(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

export const roleLabels: Record<string, string> = {
  SELLABLE: '可售',
  PENDING_INSPECTION: '待检',
  DEFECTIVE: '残次',
};

export const roleBadge: Record<string, string> = {
  SELLABLE: 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-300',
  PENDING_INSPECTION: 'bg-amber-500/10 text-amber-700 dark:text-amber-300',
  DEFECTIVE: 'bg-rose-500/10 text-rose-700 dark:text-rose-300',
};

export const scopeLabels: Record<string, string> = {
  SKU: 'SKU 级',
  CATEGORY: '类目级',
  WAREHOUSE: '仓库级',
  DEFAULT: '默认级',
};

/**
 * 行内"在手"的构成说明。
 *
 * <p>把在途单列而不是并进可用：运营判断能不能发货看的是可用，
 * 判断"货还在不在我手上"看的是在手。合成一个数就看不出调拨在途占了多少。
 */
export function rowComposition(row: OverviewRow): string {
  return `在仓 ${qty(row.onHand)} · 在途 ${qty(row.inTransit)}`;
}

export function groupByDimension(groups: OverviewGroup[], dimension: string): OverviewGroup[] {
  return groups.filter((g) => g.dimension === dimension);
}

/** 分组统计图的横轴标签。质量角色维度用角色中文名而不是行名（同一角色可能多行）。 */
export function groupLabel(group: OverviewGroup): string {
  if (group.dimension === 'QUALITY_ROLE') return roleLabels[group.label] ?? group.label;
  return group.label;
}

/**
 * 错误文案。
 *
 * <p>422（依赖未接入的筛选）要单独说清楚是"这个筛选现在查不了"，
 * 而不是笼统的"请求失败" —— 否则用户会以为自己填错了值，反复改一个改不动的条件。
 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 422) return error.message;
    if (error.status === 403) return '当前角色没有库存总览的查看权限';
    if (error.status === 0) return '正在连接仓库服务，请稍后重试';
    return error.message;
  }
  return '加载失败，请稍后重试';
}
