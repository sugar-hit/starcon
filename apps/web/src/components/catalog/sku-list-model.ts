import type {
  SkuBatchInput,
  SkuBatchResult,
  SkuFieldInfo,
  SkuListCapabilities,
  SkuPage,
  SkuProjectionCell,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 3.1.2.1 SKU 列表前端模型。
 *
 * <p>筛选条件分成两类：catalog 自有的（SPU、分类、状态、关键字、条码、规格）在后端真执行；
 * 依赖上游服务的（库存区间、价格区间、是否缺货、是否在售）由 {@link SkuListCapabilities}
 * 在运行时告知是否已接入——**前端不猜**，接入与否是后端事实，编进前端就会漂移。
 */

/** SKU 状态沿用商品档案页的四值域（SPU 才是五态生命周期）。 */
export const skuStatuses: Record<string, string> = {
  DRAFT: '草稿',
  ACTIVE: '启用',
  INACTIVE: '停用',
  ARCHIVED: '已归档',
};

export const skuStatusOptions = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'ACTIVE', label: '启用' },
  { value: 'INACTIVE', label: '停用' },
  { value: 'ARCHIVED', label: '已归档' },
];

export const skuSortOptions = [
  { value: 'updated_at', label: '最近更新' },
  { value: 'created_at', label: '创建时间' },
  { value: 'code', label: 'SKU 编码' },
  { value: 'name', label: 'SKU 名称' },
  { value: 'barcode', label: '条码' },
  { value: 'status', label: '状态' },
];

/** 批量动作。分配与生成刊登依赖 04 刊登，后端会显式拒绝，这里照实标出不可用。 */
export const skuBatchActions = [
  { value: 'STOP_SELLING', label: '批量停售', listing: false },
  { value: 'SET_STATUS', label: '批量改状态', listing: false },
  { value: 'ASSIGN', label: '批量分配（04 刊登未接入）', listing: true },
  { value: 'GENERATE_LISTING', label: '批量生成刊登（04 刊登未接入）', listing: true },
];

export type SkuFilters = {
  category: string;
  statuses: string[];
  keyword: string;
  barcode: string;
  spec: string;
  /** 未接入投影的筛选值：填了就会被后端拒绝，所以这里只是原样带上、并在界面上禁用。 */
  onHandMin: string;
  onHandMax: string;
  priceMin: string;
  priceMax: string;
  outOfStock: string;
  onSale: string;
  sort: string;
  order: string;
};

export const emptySkuFilters: SkuFilters = {
  category: '',
  statuses: [],
  keyword: '',
  barcode: '',
  spec: '',
  onHandMin: '',
  onHandMax: '',
  priceMin: '',
  priceMax: '',
  outOfStock: '',
  onSale: '',
  sort: 'updated_at',
  order: 'desc',
};

/**
 * 把筛选条件变成 query string。
 *
 * <p>只带非空项，避免 `?category=` 这种"看着像筛选、其实后端当未传"的参数——
 * 那会让"查不到结果"和"没在筛"看起来一模一样。
 */
export function skuQuery(filters: SkuFilters, page: number, size: number) {
  const parts: string[] = [`page=${page}`, `size=${size}`];
  if (filters.category) parts.push(`category=${encodeURIComponent(filters.category)}`);
  for (const status of filters.statuses) parts.push(`status=${encodeURIComponent(status)}`);
  if (filters.keyword) parts.push(`q=${encodeURIComponent(filters.keyword)}`);
  if (filters.barcode) parts.push(`barcode=${encodeURIComponent(filters.barcode)}`);
  if (filters.spec) parts.push(`spec=${encodeURIComponent(filters.spec)}`);
  if (filters.onHandMin) parts.push(`onHandMin=${encodeURIComponent(filters.onHandMin)}`);
  if (filters.onHandMax) parts.push(`onHandMax=${encodeURIComponent(filters.onHandMax)}`);
  if (filters.priceMin) parts.push(`priceMin=${encodeURIComponent(filters.priceMin)}`);
  if (filters.priceMax) parts.push(`priceMax=${encodeURIComponent(filters.priceMax)}`);
  if (filters.outOfStock) parts.push(`outOfStock=${encodeURIComponent(filters.outOfStock)}`);
  if (filters.onSale) parts.push(`onSale=${encodeURIComponent(filters.onSale)}`);
  parts.push(`sort=${encodeURIComponent(filters.sort)}`);
  parts.push(`order=${encodeURIComponent(filters.order)}`);
  return parts.join('&');
}

/** 导出链接：与列表共用同一套条件，所以导出结果和当前看到的完全一致。 */
export function skuExportUrl(filters: SkuFilters) {
  return `/api/catalog/catalog/skus/export?${skuQuery(filters, 0, 0)}`;
}

export function fetchSkuPage(filters: SkuFilters, page: number, size = 50) {
  return api<SkuPage>(`catalog/skus?${skuQuery(filters, page, size)}`);
}

export function fetchSkuCapabilities() {
  return api<SkuListCapabilities>('catalog/skus/capabilities');
}

export function runSkuBatch(input: SkuBatchInput) {
  return api<SkuBatchResult>('catalog/skus/batch', 'POST', input);
}

/** 字段是否已接入：能力声明里逐字段标注，未接入的列必须显示"未接入"而不是 0。 */
export function isFieldAvailable(capabilities: SkuListCapabilities | null, field: string) {
  return capabilities?.fields.find((f: SkuFieldInfo) => f.code === field)?.available === true;
}

export function fieldNote(capabilities: SkuListCapabilities | null, field: string) {
  return capabilities?.fields.find((f: SkuFieldInfo) => f.code === field)?.note ?? '';
}

/**
 * 单格投影的展示文本。
 *
 * <p>available=false 时返回 '未接入'——**绝不返回 '0'**。缺失数据不是 0，
 * 把它画成 0 会让运营以为这个 SKU 真的没有库存或没有定价。
 */
export function projectionText(cell: SkuProjectionCell) {
  if (!cell.available) return '未接入';
  if (cell.value !== null && cell.value !== undefined) return String(cell.value);
  if (cell.text) return cell.text;
  return '—';
}

/** 规格展示：「颜色=黑色 / 容量=128G」；没有规格轴时回落到签名原文。 */
export function specText(specs: { axis: string; value: string }[], signature: string) {
  if (!specs.length) return signature || '—';
  return specs.map((s) => `${s.axis}=${s.value}`).join(' / ');
}

/** 筛选区是否有任何非默认条件——空态文案据此区分"没数据"和"没匹配"。 */
export function hasActiveFilters(filters: SkuFilters) {
  return (
    filters.category !== '' ||
    filters.statuses.length > 0 ||
    filters.keyword !== '' ||
    filters.barcode !== '' ||
    filters.spec !== '' ||
    filters.onHandMin !== '' ||
    filters.onHandMax !== '' ||
    filters.priceMin !== '' ||
    filters.priceMax !== '' ||
    filters.outOfStock !== '' ||
    filters.onSale !== ''
  );
}

/**
 * 后端返回 422 表示筛选条件依赖尚未接入的上游投影。
 * 这不是"查不到"，界面必须区别于空态提示，并说明归哪个服务。
 */
export function isProjectionRejection(error: unknown) {
  return error instanceof ApiError && error.status === 422;
}
