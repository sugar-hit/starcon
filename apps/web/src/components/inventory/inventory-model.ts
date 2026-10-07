import type {
  BucketAction,
  BucketRow,
  DimensionCapability,
  InventoryBucketDetail,
  InventoryOverview,
  InventoryPolicy,
  InventoryPolicyInput,
  InventoryPolicySaved,
  LegalEntityRef,
  LocationRef,
  LotRef,
  OverviewGroup,
  OverviewRow,
  QualityStatusRef,
  SkuRef,
  WarehouseRef,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from './api';

/**
 * 7.1.1.1 库存总览 + 7.1.1.2 库存明细前端模型。
 *
 * <p>界面上最容易出错的几处，这个模块都显式处理了：
 *
 * <ul>
 *   <li>**「未设阈值」不能画成「未超储」**。超储是拿在手和阈值比出来的，没有阈值就没有
 *       这个判断。两者含义不同（照 catalog `unregisteredSkus` 同一口径），所以行上与
 *       合计上都分开显示。
 *   <li>**在手 ≠ 可用**。在手含在途，可用不含 —— 未到货的货不能拿去发货。数字看着接近，
 *       但差的就是在途，界面上必须把在途单列一行。
 *   <li>**合计不受分页影响**。合计是全量的，翻页时它不变；否则"翻到第二页总数变小"
 *       会被当成数据丢了。
 *   <li>**明细的"未分解"不能算成"不平"**。库位明细页只覆盖已拆到库位的库存，
 *       还没拆的 balance 行是合法状态，要单独报出来而不是混进差异里。
 * </ul>
 */

// ------------------------------------------------------------ 接口

export function fetchOverview(params: OverviewParams) {
  return api<InventoryOverview>(`warehouse/inventory/overview${query(params)}`);
}

/** 7.1.1.2 bucket 明细。筛选条件与总览同一套 query 拼装约定。 */
export function fetchBuckets(params: BucketParams) {
  return api<InventoryBucketDetail>(`warehouse/inventory/buckets${bucketQuery(params)}`);
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

export function fetchSkus() {
  return api<SkuRef[]>('warehouse/skus');
}

/** 库位下拉。按仓库过滤，让"先选仓、再选库位"的逐级下钻在界面上成立。 */
export function fetchLocations(warehouseId?: string) {
  return api<LocationRef[]>(
    `warehouse/locations${warehouseId ? `?warehouseId=${encodeURIComponent(warehouseId)}` : ''}`,
  );
}

/** 批次下拉。按 SKU 过滤。 */
export function fetchLots(skuId?: string) {
  return api<LotRef[]>(`warehouse/lots${skuId ? `?skuId=${encodeURIComponent(skuId)}` : ''}`);
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

// ------------------------------------------------------------ 7.1.1.2 明细

/** 7.1.1.2 的逐级下钻条件：法人 → 仓库 → 库位 → SKU → 批次。 */
export interface BucketParams {
  warehouseId?: string;
  legalEntityId?: string;
  locationId?: string;
  skuId?: string;
  lotId?: string;
  qualityStatusId?: string;
  keyword?: string;
  onlyPickable?: boolean;
  sort?: string;
  page?: number;
  size?: number;
}

/**
 * 总览行（7.1.1.1）下钻到明细（7.1.1.2）时携带的四个维度。
 *
 * <p>字段与 `OverviewRow.detailHref` 里的 query 参数一一对应 ——
 * 那个 href 是后端给的路由契约，前端按同样的参数名解析，两者不会各说各话。
 */
export interface BucketDrill {
  warehouseId: string;
  legalEntityId: string;
  skuId: string;
  qualityStatusId: string;
}

/**
 * 解析 7.1.1.1 行上的 `detailHref`，取出下钻维度。
 *
 * <p>刻意**解析后端给的 href** 而不是在前端另写一份参数拼法：href 由
 * `InventoryOverviewService.detailHref()` 生成，参数名只存在一处。
 * href 形状不认识时返回 null（调用方按"无下钻"处理），不抛异常 ——
 * 一个 href 格式变化不该让整个总览页白屏。
 */
export function parseDetailHref(href: string | null | undefined): BucketDrill | null {
  if (!href) return null;
  const [raw] = href.split('?');
  if (raw !== '/inventory') return null;
  const params = new URLSearchParams(href.split('?')[1] ?? '');
  const pick = (key: string) => params.get(key)?.trim() ?? '';
  const drill = {
    warehouseId: pick('warehouseId'),
    legalEntityId: pick('legalEntityId'),
    skuId: pick('skuId'),
    qualityStatusId: pick('qualityStatusId'),
  };
  // 四个维度缺一个就不是一次完整的行级下钻：宁可按"无下钻"处理，
  // 也不要只带一半条件让用户以为看的就是那一行。
  return Object.values(drill).every(Boolean) ? drill : null;
}

/**
 * 明细查询串。
 *
 * 与总览的 `query()` 分开而不是复用同一个函数：明细多了库位、批次、可拣三个条件，
 * 共用一个函数就得给两处都塞可选字段，读起来分不清哪条路径支持哪个条件。
 */
export function bucketQuery(params: BucketParams) {
  const parts: string[] = [];
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  if (params.legalEntityId) parts.push(`legalEntityId=${encodeURIComponent(params.legalEntityId)}`);
  if (params.locationId) parts.push(`locationId=${encodeURIComponent(params.locationId)}`);
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  if (params.lotId) parts.push(`lotId=${encodeURIComponent(params.lotId)}`);
  if (params.qualityStatusId)
    parts.push(`qualityStatusId=${encodeURIComponent(params.qualityStatusId)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  // 复选框只表达"只看"，不表达"不看"：后端把未传与 false 视为两种含义
  if (params.onlyPickable) parts.push('onlyPickable=true');
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export const locationTypeLabels: Record<string, string> = {
  STORAGE: '存储',
  RECEIVING: '收货',
  SHIPPING: '发货',
  QUARANTINE: '待检',
  DEFECTIVE: '残次',
  FROZEN: '冻结',
};

export const lotStatusLabels: Record<string, string> = {
  ACTIVE: '在用',
  DEPLETED: '已耗尽',
  EXPIRED: '已过期',
};

/**
 * 批次的展示标签。
 *
 * 无批次不是"没填"，是一个真实取值（非批次管理品），所以显示成「无批次」而不是空白 ——
 * 空白在表里会被读成"数据缺失"。
 */
export function lotLabel(lotCode: string | null): string {
  return lotCode ?? '无批次';
}

/**
 * 库位 · 批次 · 质量状态：明细行的定位串。
 *
 * 蓝图要求界面上必须能看到"在哪个货位、什么批次、什么质量状态"，这三样合成一列放在
 * SKU 之后，任何一行都能一眼回答"这批货在哪、是什么批次"。
 */
export function bucketPlacement(row: BucketRow): string {
  return [
    row.locationCode,
    lotLabel(row.lotCode),
    roleLabels[row.qualityRole] ?? row.qualityStatusName,
  ].join(' · ');
}

/** 库位不可拣的原因说明：可售量计算（7.1.1.3）要把不可拣库位的量排除在可发货之外。 */
export function pickableNote(row: BucketRow): string {
  if (row.pickable) return '可拣，可发货';
  const reason = locationTypeLabels[row.locationType] ?? row.locationType;
  return `${reason}库位，不可发货`;
}

/**
 * 行内操作入口的可用性说明。
 *
 * 目标模块未交付时后端返回 available=false 与 note：界面上禁用按钮并显示原因，
 * 而不是给一个点下去 404 的入口。
 */
export function actionNote(action: BucketAction): string {
  return action.available ? `${action.label}（目标模块 ${action.ownerModule}）` : action.note;
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
