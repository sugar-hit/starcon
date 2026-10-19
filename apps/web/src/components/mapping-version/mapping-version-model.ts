import { api } from '../inventory/api';

/**
 * 4.1.2.2 映射版本 前端模型。
 *
 * <p>蓝图：「映射变更时生成新版本而非原地修改，保留每个版本对应的商品/订单范围，支持查看某订单使用的是哪个版本、按版本回溯受影响订单。输入：映射变更；生效时间点；变更原因。输出：<b>版本列表 + 受影响订单范围查询</b>。关系：上游 = 4.1.2.1；下游 = 09 订单（`order_line_component` 冻结）。<b>改映射不影响已产生的订单</b>，只对新订单生效——这是财务与售后追溯的前提。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **未交付段如实显示，且不画空列表**。`affected_orders` 与 `order_to_version`
 *    两个维度 `available=false`。界面上逐条列出归属模块与影响
 *    （`unavailableDimensions()`），**绝不**画一个空的「受影响订单」表格 ——
 *    空表格会被读成「这一版没有影响任何订单」，而真相是「查不了」。
 *    `VersionAffectedOrders` 因此**没有** `items` 字段（服务端就不给）。
 * 2. **商品范围是快照，与平台当前值分开显示**。`titleSnapshot` 与 `currentTitle`
 *    并排给出，漂移时给 `driftedFromPlatform` 徽章。把两者混成一个字段，
 *    平台重新同步一次就会让历史悄悄改写。
 * 3. **筛选与分页全在服务端**。前端只翻译 query string，不在浏览器里过滤已分页的数据
 *    （那样会得到「总数说 500、实际只筛得出当前页 50 条」）。
 * 4. **时间只做展示，不做算术**。`effectiveFrom` / `effectiveTo` 原样透传显示；
 *    「这一版生效了多久」这类时长由人自己看两个时刻，前端不参与（时区与舍入会出错）。
 */

/** 🔴 null = 服务端没给这个值，与 `0` 含义相反。 */
export type VersionNumber = number | null;

export interface VersionCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface ListingMappingVersionCapabilities {
  dimensions: VersionCapabilityDimension[];
  sorts: string[];
  changeSources: string[];
  relationTypes: string[];
  gapNote: string;
  dependencyNote: string;
}

export interface VersionFilterOptions {
  relationTypes: string[];
  changeSources: string[];
  effectiveStates: string[];
  sorts: string[];
  maxPageSize: number;
}

/** 版本列表的一行。 */
export interface VersionRow {
  id: string;
  mappingId: string;
  versionNo: number;
  relationType: string;
  mappingStatus: string;
  internalSkuId: string | null;
  componentQuantity: VersionNumber;
  /** 🔴 生效时间点：区间为 [effectiveFrom, effectiveTo) */
  effectiveFrom: string;
  /** null = 当前生效版本 */
  effectiveTo: string | null;
  current: boolean;
  changeReason: string;
  changeSource: string;
  actorId: string | null;
  // ---- 商品范围（V182 冻结快照）：这一版当时覆盖哪个平台商品 / 变体 ----
  listingId: string;
  externalItemId: string;
  titleSnapshot: string;
  listingVariantId: string;
  externalVariantId: string;
  shopCode: string;
  capturedAt: string;
  createdAt: string;
}

export interface VersionPage {
  items: VersionRow[];
  total: number;
  page: number;
  size: number;
  currentInPage: number;
}

export interface VersionProductScope {
  versionId: string;
  mappingId: string;
  listingId: string;
  externalItemId: string;
  /** 🔴 版本落库那一刻的标题（快照） */
  titleSnapshot: string;
  listingVariantId: string;
  externalVariantId: string;
  shopCode: string;
  /** 平台侧**当前**值，用于与快照对照 */
  currentExternalItemId: string | null;
  currentTitle: string | null;
  driftedFromPlatform: boolean;
  capturedAt: string;
  note: string;
}

export interface VersionAffectedOrders {
  versionId: string;
  versionNo: number;
  available: boolean;
  ownerModule: string;
  note: string;
  prerequisite: string;
}

export interface MappingVersionDetail {
  version: VersionRow;
  productScope: VersionProductScope;
  previousVersionNo: VersionNumber;
  nextVersionNo: VersionNumber;
  changedFields: string[];
  affectedOrders: VersionAffectedOrders;
}

// ================================================================ 筛选

export interface VersionFilters {
  mappingId: string;
  listingId: string;
  internalSkuId: string;
  shopCode: string;
  relationType: string;
  changeSource: string;
  effectiveState: string;
  windowFrom: string;
  windowTo: string;
  search: string;
  sort: string;
  page: number;
  size: number;
}

export function emptyFilters(): VersionFilters {
  return {
    mappingId: '',
    listingId: '',
    internalSkuId: '',
    shopCode: '',
    relationType: '',
    changeSource: '',
    effectiveState: '',
    windowFrom: '',
    windowTo: '',
    search: '',
    sort: 'RECENT',
    page: 0,
    size: 50,
  };
}

const FILTER_KEYS = [
  'mappingId',
  'listingId',
  'internalSkuId',
  'shopCode',
  'relationType',
  'changeSource',
  'effectiveState',
  'windowFrom',
  'windowTo',
  'search',
] as const satisfies readonly (keyof VersionFilters)[];

/**
 * 翻译成 query string。
 *
 * <p>🔴 生效区间是**重叠**语义（服务端按 [from,to) 与窗口求交），前端只负责把两个端点
 * 原样送过去，**不做任何本地求交** —— 本地算一遍就会和服务的口径漂移。
 */
export function buildQuery(filters: VersionFilters): string {
  const params = new URLSearchParams();
  for (const key of FILTER_KEYS) {
    const value = filters[key];
    if (value?.trim()) params.set(key, value.trim());
  }
  params.set('sort', filters.sort || 'RECENT');
  params.set('page', String(Math.max(0, filters.page)));
  params.set('size', String(Math.max(1, filters.size)));
  return params.toString();
}

export function activeFilterCount(filters: VersionFilters): number {
  return FILTER_KEYS.filter((key) => (filters[key] ?? '').trim().length > 0).length;
}

/** 改任一筛选条件都回到第 0 页 —— 停在第 3 页再看筛选结果永远是空的。 */
export function applyFilterChange(
  filters: VersionFilters,
  patch: Partial<VersionFilters>,
): VersionFilters {
  return { ...filters, ...patch, page: 0 };
}

export function resetFilters(filters: VersionFilters): VersionFilters {
  const size = filters.size;
  return { ...emptyFilters(), size };
}

// ================================================================ 展示

export function relationLabel(relationType: string): string {
  switch (relationType) {
    case 'ONE_TO_ONE':
      return '一对一';
    case 'ONE_TO_MANY':
      return '一对多（套装）';
    case 'MANY_TO_ONE':
      return '多对一';
    default:
      return relationType;
  }
}

export function changeSourceLabel(source: string): string {
  switch (source) {
    case 'MANUAL':
      return '人工建立';
    case 'BATCH_CONFIRMED':
      return '批量候选人工确认';
    default:
      return source;
  }
}

export function changedFieldLabel(field: string): string {
  switch (field) {
    case 'INTERNAL_SKU_ID':
      return '内部 SKU';
    case 'RELATION_TYPE':
      return '映射基数';
    case 'COMPONENT_QUANTITY':
      return '组件数量';
    case 'MAPPING_STATUS':
      return '映射状态';
    default:
      return field;
  }
}

/** 生效区间的人类可读形态：[from, to)，终点空 = 当前生效。 */
export function effectiveWindow(row: VersionRow): string {
  return row.effectiveTo
    ? `${formatMoment(row.effectiveFrom)} → ${formatMoment(row.effectiveTo)}（已关闭）`
    : `${formatMoment(row.effectiveFrom)} → 至今（当前生效）`;
}

/**
 * 时刻展示：只做格式化，不做时区换算。
 *
 * <p>🔴 刻意**不**用 `toLocaleString()`：那会把服务端给的偏移量换算成浏览器本地时区，
 * 「生效时间点」在跨时区团队里就会各说各的。原样带偏移显示，与库里存的值一致。
 */
export function formatMoment(value: string | null | undefined): string {
  if (!value) return '—';
  return value.replace('T', ' ').replace(/\.\d+/, '').replace(/([+-]\d{2}:\d{2}|Z)$/, ' $1');
}

export function pageSummary(page: VersionPage | null): string {
  if (!page || page.total === 0) return '共 0 条';
  const from = page.page * page.size + 1;
  const to = Math.min(from + page.items.length - 1, page.total);
  return `共 ${page.total} 条，当前显示第 ${from}-${to} 条`;
}

export function hasNextPage(page: VersionPage | null): boolean {
  if (!page) return false;
  return (page.page + 1) * page.size < page.total;
}

export function hasPrevPage(page: VersionPage | null): boolean {
  if (!page) return false;
  return page.page > 0;
}

/** 服务端标了 available=false 的维度（未交付段明账）。 */
export function unavailableDimensions(
  caps: ListingMappingVersionCapabilities | null,
): VersionCapabilityDimension[] {
  if (!caps) return [];
  return caps.dimensions.filter((d) => !d.available);
}

/**
 * 受影响订单范围的一行说明。
 *
 * <p>🔴 刻意**不返回任何条数**：返回一个 0 会被读成「这一版确实没有影响任何订单」。
 * 界面上这一段要么显示归属模块 + 原因，要么什么都不显示，绝不显示「0 条」。
 */
export function affectedOrdersText(
  affected: VersionAffectedOrders | null | undefined,
): string | null {
  if (!affected || affected.available) return null;
  return `未接入（归 ${affected.ownerModule}）：${affected.note}`;
}

// ================================================================ API

export function fetchCapabilities(): Promise<ListingMappingVersionCapabilities> {
  return api<ListingMappingVersionCapabilities>('channel/mapping-versions/capabilities');
}

export function fetchFilterOptions(): Promise<VersionFilterOptions> {
  return api<VersionFilterOptions>('channel/mapping-versions/filter-options');
}

export function fetchVersions(filters: VersionFilters): Promise<VersionPage> {
  return api<VersionPage>(`channel/mapping-versions?${buildQuery(filters)}`);
}

export function fetchVersion(id: string): Promise<MappingVersionDetail> {
  return api<MappingVersionDetail>(`channel/mapping-versions/${id}`);
}

export function fetchProductScope(id: string): Promise<VersionProductScope> {
  return api<VersionProductScope>(`channel/mapping-versions/${id}/product-scope`);
}

export function fetchAffectedOrders(id: string): Promise<VersionAffectedOrders> {
  return api<VersionAffectedOrders>(`channel/mapping-versions/${id}/affected-orders`);
}
