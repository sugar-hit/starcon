import { api } from '../inventory/api';

/**
 * 4.1.1.1 Listing 列表 前端模型。
 *
 * 蓝图：「按店铺/站点/状态/负责人/类目/是否有映射/是否缺货筛选平台商品，展示内部 SKU、价格、库存、销量、评分、近 7
 * 日状态变化，支持批量改价改库存（跳 05/07）、批量下架、批量分配负责人。输入：筛选条件；用户数据范围（店铺维度）；
 * `listing` + `listing_variant` + 销量/库存投影。输出：分页列表（含内部 SKU 映射状态、缺货标记、审核状态）；批量操作任务；点击进入
 * 4.1.1.2。关系：上游 = 02 店铺、03 SKU、05 价格、07 库存。<b>「未映射」和「缺货」是两个独立状态</b>，列表要能同时显示且可分别筛选。」
 *
 * 🔴 这个页面上最容易做丢的三件事，这里都挡住了：
 *
 * 1. **价格不画假数据**。卷 05 定价整卷 0/19 未交付，服务端 `priceAvailable=false`，本模型
 *    **没有任何 price 字段**，也没有 `formatPrice()` 这种「先返回 0，以后再接」的桩。
 *    价格位渲染的是「价格能力未接入（归 05 定价）」，由 `priceCell()` 统一产出 ——
 *    回 0 会被读成「这个商品卖 0 块」，占位价会被当真价拿去做定价决策。
 *
 * 2. **未映射与缺货是两条独立的筛选，不合成一个**。`mappingStatus` 与 `outOfStock` 在
 *    `ListingFilters` 里是两个字段、在 `buildQuery()` 里是两个参数、在 `hasActiveFilters()`
 *    里是两条独立判定。一个已映射但平台缺货的商品必须能被同时筛出来 —— 这是最常见的漏单场景。
 *
 * 3. **筛选与排序都在服务端**。前端不做过滤、不做排序、不做分页切片：`buildQuery()` 只负责把
 *    筛选条件翻译成 query string，真正的 where / order by / limit / offset 走 jOOQ 显式 SQL。
 *    在浏览器里过滤一份已经分页过的数据，会得到「总数说 500、实际只筛得出当前页 50 条」的结果。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * 服务端 `BigDecimal` 的事在前端算一遍就会引入二进制浮点误差。
 */

/** 🔴 null = 服务端没给这个值（平台未评分 / 平台未回报可售量），与 `0` 含义相反。 */
export type ListingNumber = number | null;

export interface ListingCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface ListingCapabilities {
  dimensions: ListingCapabilityDimension[];
  priceAvailable: boolean;
  priceOwnerModule: string;
  priceNote: string;
  statuses: string[];
  platformStockStates: string[];
  reviewStatuses: string[];
  mappingStatuses: string[];
  variantMappingStatuses: string[];
  batchActions: string[];
  independentStateNote: string;
  upstreamNote: string;
}

export interface ListingStatusChange {
  fromStatus: string | null;
  toStatus: string;
  source: 'MANUAL' | 'PLATFORM';
  occurredAt: string;
}

/**
 * 列表的一行。
 *
 * 🔴 `mappingStatus`（未映射/部分映射/已映射）与 `platformStockState`（平台侧有无货）
 * 是**两个字段**，可以同时为真。本类型里**刻意没有 price / sales / internalStock 字段** ——
 * 加了就等于给「假数据」留一个入口。
 */
export interface ListingRow {
  id: string;
  shopId: string;
  shopCode: string;
  shopName: string;
  platformId: string;
  platformCode: string | null;
  marketplaceId: string | null;
  marketplaceCode: string | null;
  /** 🔴 平台商品 ID 原文（ASIN / item_id），不折叠大小写 */
  externalItemId: string;
  title: string;
  categoryPath: string;
  platformCategoryId: string;
  status: string;
  reviewStatus: string;
  reviewMessage: string;
  ownerId: string | null;
  rating: ListingNumber;
  ratingCount: ListingNumber;
  currency: string | null;
  /** 🔴 缺货标记（平台侧事实）：IN_STOCK / OUT_OF_STOCK / UNKNOWN */
  platformStockState: 'IN_STOCK' | 'OUT_OF_STOCK' | 'UNKNOWN';
  platformStockQty: ListingNumber;
  /** 🔴 内部 SKU 映射状态：UNMAPPED / PARTIAL / MAPPED */
  mappingStatus: 'UNMAPPED' | 'PARTIAL' | 'MAPPED';
  variantCount: number;
  mappedVariantCount: number;
  /** 跨服务逻辑 ID（03 catalog 拥有），这里只显示"已挂 N 个内部 SKU"，不猜编码 */
  internalSkuIds: string[];
  statusChangeCount7d: number;
  recentStatusChanges: ListingStatusChange[];
  launchedAt: string | null;
  lastSyncedAt: string | null;
  updatedAt: string;
}

export interface ListingPage {
  items: ListingRow[];
  total: number;
  page: number;
  size: number;
  rejectedFilters: string[];
}

export interface ListingBatchResult {
  taskId: string;
  action: string;
  outcome: 'SUCCEEDED' | 'REJECTED';
  requestedCount: number;
  affectedCount: number;
  skipCount: number;
  skippedIds: string[];
  reason: string;
  ownerModule: string;
  createdAt: string;
}

export interface ListingFilterOptions {
  statuses: string[];
  platformStockStates: string[];
  reviewStatuses: string[];
  mappingStatuses: string[];
  sortFields: string[];
  maxSize: number;
}

export interface SaveVariantInput {
  id?: string | null;
  externalVariantId: string;
  mappingStatus: string;
  internalSkuId: string | null;
  componentQuantity: number | null;
  attributes: string | null;
}

export interface SaveListingInput {
  id?: string | null;
  shopId: string;
  marketplaceId: string | null;
  externalItemId: string;
  title: string;
  categoryPath: string;
  platformCategoryId: string;
  status: string | null;
  reviewStatus: string | null;
  reviewMessage: string;
  platformStockState: string;
  platformStockQty: number | null;
  rating: number | null;
  ratingCount: number | null;
  currency: string | null;
  launchedAt: string | null;
  lastSyncedAt: string | null;
  variants: SaveVariantInput[];
}

// ================================================================ 筛选

/**
 * 筛选条件。
 *
 * 🔴 `mappingStatus` 与 `outOfStock` 是**两个独立字段**。把它们合成一个 `flag` 就再也
 * 表达不出「已映射但平台缺货」了 —— 那恰好是运营最需要看见的一批商品。
 */
export interface ListingFilters {
  shopCode: string;
  marketplaceCode: string;
  status: string;
  ownerId: string;
  category: string;
  /** 🔴 是否有内部 SKU 映射 */
  mappingStatus: string;
  /**
   * 🔴 是否缺货（平台侧）；`undefined` = 不过滤。
   *
   * <p>写成 `boolean | undefined`（而不是裸 `boolean`）是因为本仓开着
   * `exactOptionalPropertyTypes`：裸可选属性不接受「显式赋 undefined」，
   * 而三态（不筛 / 有货 / 缺货）必须能区分开，`undefined` 是其中一态。
   */
  outOfStock?: boolean | undefined;
  search: string;
  sort: string;
  page: number;
  size: number;
}

export const emptyFilters: ListingFilters = {
  shopCode: '',
  marketplaceCode: '',
  status: '',
  ownerId: '',
  category: '',
  mappingStatus: '',
  outOfStock: undefined,
  search: '',
  sort: 'RECENT',
  page: 0,
  size: 50,
};

/**
 * 翻译成 query string。
 *
 * <p>空值一律**不下发**（而不是下发空串）：后端把 `?status=` 读成"显式筛选空状态"，
 * 会得到一个空列表，而用户以为自己没在筛选。
 */
export function buildQuery(filters: ListingFilters): string {
  const params = new URLSearchParams();
  const put = (key: string, value: string | undefined | null) => {
    const trimmed = (value ?? '').trim();
    if (trimmed) params.set(key, trimmed);
  };
  put('shopCode', filters.shopCode);
  put('marketplaceCode', filters.marketplaceCode);
  put('status', filters.status);
  put('ownerId', filters.ownerId);
  put('category', filters.category);
  // 🔴 两个独立维度各发各的参数
  put('mappingStatus', filters.mappingStatus);
  if (filters.outOfStock !== undefined) params.set('outOfStock', String(filters.outOfStock));
  put('search', filters.search);
  put('sort', filters.sort || 'RECENT');
  params.set('page', String(Math.max(0, filters.page)));
  params.set('size', String(Math.max(1, filters.size)));
  return params.toString();
}

/** 已生效的筛选条数（用于「清空筛选」按钮的角标）。 */
export function activeFilterCount(filters: ListingFilters): number {
  let n = 0;
  if (filters.shopCode.trim()) n += 1;
  if (filters.marketplaceCode.trim()) n += 1;
  if (filters.status.trim()) n += 1;
  if (filters.ownerId.trim()) n += 1;
  if (filters.category.trim()) n += 1;
  // 🔴 两个独立维度各算一条
  if (filters.mappingStatus.trim()) n += 1;
  if (filters.outOfStock !== undefined) n += 1;
  if (filters.search.trim()) n += 1;
  return n;
}

/** 清空筛选（保留排序与分页大小 —— 那不是"筛选条件"）。 */
export function resetFilters(filters: ListingFilters): ListingFilters {
  return {
    ...emptyFilters,
    sort: filters.sort,
    size: filters.size,
  };
}

/** 🔴 换筛选条件必须回到第 0 页：停在第 3 页看新的筛选结果永远是空的。 */
export function applyFilterChange(
  filters: ListingFilters,
  patch: Partial<ListingFilters>,
): ListingFilters {
  return { ...filters, ...patch, page: 0 };
}

// ================================================================ 单元格文案

/** 平台商品状态 → 中文。 */
export function statusLabel(status: string): string {
  const map: Record<string, string> = {
    DRAFT: '草稿',
    ON_SALE: '在售',
    UNDER_REVIEW: '平台审核中',
    REJECTED: '平台驳回',
    DELISTED_BY_US: '主动下架',
    DELISTED_BY_PLATFORM: '平台下架',
    BLOCKED: '被封禁',
  };
  return map[status] ?? status;
}

/** 🔴 映射状态 → 中文。与缺货是两回事，文案上不许互相靠近。 */
export function mappingLabel(mappingStatus: string): string {
  const map: Record<string, string> = {
    UNMAPPED: '未映射',
    PARTIAL: '部分映射',
    MAPPED: '已映射',
  };
  return map[mappingStatus] ?? mappingStatus;
}

/** 🔴 缺货标记 → 中文。UNKNOWN 是「平台没回报」，不是「有货」。 */
export function stockLabel(platformStockState: string): string {
  const map: Record<string, string> = {
    IN_STOCK: '有货',
    OUT_OF_STOCK: '平台缺货',
    UNKNOWN: '平台未回报',
  };
  return map[platformStockState] ?? platformStockState;
}

export interface PriceCell {
  /** 🔴 恒为 false：卷 05 整卷未交付，本批次不可能有 true。 */
  available: boolean;
  /** 服务端措辞原样透出；available 时为 null */
  note: string | null;
  ownerModule: string | null;
}

/**
 * 🔴 价格位。
 *
 * <p>本批次恒返回 `available=false` + 服务端给的措辞。**刻意不提供「返回 0」的分支** ——
 * 0 会被读成「这个商品卖 0 块」，是假事实而不是「暂无数据」。
 * 05 上线后服务端把 `priceAvailable` 翻成 true，这条才可能返回 available。
 */
export function priceCell(capabilities: ListingCapabilities | null): PriceCell {
  if (!capabilities) {
    return {
      available: false,
      note: '价格能力未接入（归 05 定价与价目系统）',
      ownerModule: '05 定价',
    };
  }
  if (capabilities.priceAvailable) {
    // 05 接入后由服务端下发真实价格字段；本分支只负责不再显示"未接入"提示。
    return { available: true, note: null, ownerModule: null };
  }
  return {
    available: false,
    note: capabilities.priceNote,
    ownerModule: capabilities.priceOwnerModule,
  };
}

/** 未接入的维度清单（用于页面顶部的「本模块未接入什么」明账）。 */
export function unavailableDimensions(
  capabilities: ListingCapabilities | null,
): ListingCapabilityDimension[] {
  if (!capabilities) return [];
  return capabilities.dimensions.filter((d) => !d.available);
}

// ================================================================ 批量操作

export type BatchAction = 'ASSIGN_OWNER' | 'DELIST' | 'REPRICE' | 'RESTOCK';

/**
 * 🔴 批量动作在本模块能不能执行。
 *
 * <p>REPRICE / RESTOCK 恒为 `supported=false` —— 蓝图职责句本身就写明「（跳 05/07）」。
 * 按钮必须**禁用并说明归属**，而不是点了给一个「成功但什么都没做」的提示。
 */
export function batchSupport(action: string): {
  supported: boolean;
  ownerModule: string | null;
  reason: string | null;
} {
  if (action === 'REPRICE') {
    return {
      supported: false,
      ownerModule: '05 定价与价目系统',
      reason: '批量改价由 05 定价拥有，本模块不建影子价格表；请到 05 执行。',
    };
  }
  if (action === 'RESTOCK') {
    return {
      supported: false,
      ownerModule: '07 库存系统',
      reason: '批量改库存由 07 库存拥有；本模块的缺货标记是平台侧事实，与内部可售量不是一回事。',
    };
  }
  return { supported: true, ownerModule: null, reason: null };
}

/** 批量结果的一行摘要（成功与被拒都用它，保证两种结局都有明确措辞）。 */
export function batchSummary(result: ListingBatchResult): string {
  if (result.outcome === 'REJECTED') {
    return `${actionLabel(result.action)}未执行（归 ${result.ownerModule}）：${result.reason}`;
  }
  const skipped = result.skipCount > 0 ? `；${result.skipCount} 条不在数据范围内被跳过` : '';
  return `${actionLabel(result.action)}成功：影响 ${result.affectedCount} / ${result.requestedCount} 条${skipped}`;
}

export function actionLabel(action: string): string {
  const map: Record<string, string> = {
    ASSIGN_OWNER: '批量分配负责人',
    DELIST: '批量下架',
    REPRICE: '批量改价',
    RESTOCK: '批量改库存',
  };
  return map[action] ?? action;
}

// ================================================================ 汇总

/**
 * 分页摘要。
 *
 * <p>🔴 用 `total` 而不是 `items.length` 说"共 N 条"：后者会把"当前页 50 条"说成"共 50 条"。
 */
export function pageSummary(page: ListingPage | null): string {
  if (!page) return '';
  if (page.total === 0) return '共 0 条';
  const from = page.page * page.size + 1;
  const to = Math.min((page.page + 1) * page.size, page.total);
  return `共 ${page.total} 条，当前显示第 ${from}-${to} 条`;
}

export function hasNextPage(page: ListingPage | null): boolean {
  if (!page) return false;
  return (page.page + 1) * page.size < page.total;
}

export function hasPrevPage(page: ListingPage | null): boolean {
  if (!page) return false;
  return page.page > 0;
}

// ================================================================ API

export function fetchCapabilities(): Promise<ListingCapabilities> {
  return api<ListingCapabilities>('channel/listings/capabilities');
}

export function fetchFilterOptions(): Promise<ListingFilterOptions> {
  return api<ListingFilterOptions>('channel/listings/filter-options');
}

export function fetchListings(filters: ListingFilters): Promise<ListingPage> {
  return api<ListingPage>(`channel/listings?${buildQuery(filters)}`);
}

export function fetchListing(id: string): Promise<ListingRow> {
  return api<ListingRow>(`channel/listings/${id}`);
}

export function fetchBatchTasks(): Promise<ListingBatchResult[]> {
  return api<ListingBatchResult[]>('channel/listings/batch-tasks');
}

export function runBatch(payload: {
  action: string;
  listingIds: string[];
  ownerId?: string | null;
}): Promise<ListingBatchResult> {
  return api<ListingBatchResult>('channel/listings/batch', 'POST', payload);
}

export function saveListing(payload: SaveListingInput): Promise<ListingRow> {
  return api<ListingRow>('channel/listings', 'POST', payload);
}
