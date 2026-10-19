import { api } from '../inventory/api';

/**
 * 4.1.2.3 套装组件映射 前端模型。
 *
 * <p>蓝图：「当平台商品对应内部虚拟套装时，维护组件 SKU 与数量的映射，支持平台变体与不同套装版本对应，并校验组件 SKU 在目标仓库可售。输入：组件映射维护；套装版本选择。输出：<b>组件映射记录 + 可售性校验结果</b>。关系：上游 = 3.5.1 套装版本、4.1.2.1；下游 = 07 库存（按组件预占）。<b>组件数量必须大于 0</b>，缺任一组件时整个套装不可售。」
 *
 * 🔴 这个页面上最容易做丢的三件事，这里都挡住了：
 *
 * 1. **「缺任一组件 ⇒ 整套不可售」是整体判定**。逐行看每个组件都还有货，但只要有一个不够，
 *    这一整套就卖不出去。所以 `bundleSellable` 必须**置顶**，不能只让用户自己数
 *    「三行里两行是绿的」——`sellableBanner()` 把这个结论直接写成一句话。
 * 2. **「查不到」绝不显示成「可售」**。可售量来自 07 库存；读不到时后端返回 503，
 *    前端进入**错误态**而不是空态。空态会被读成「查过了，没有库存记录」。
 * 3. **组件数量的合法区间在输入框上就说清**。蓝图加粗句「组件数量必须大于 0」不是提示，
 *    是硬约束：`quantityStep()` / `quantityInvalidReason()` 让非法值**在提交前**就带原因。
 */

/** 🔴 null = 服务端没给这个值，与 `0` 含义相反。 */
export type Quantity = number | null;

export interface BundleCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface ListingBundleComponentCapabilities {
  dimensions: BundleCapabilityDimension[];
  sorts: string[];
  effectiveStates: string[];
  gapNote: string;
  dependencyNote: string;
}

export interface ListingBundleFilterOptions {
  effectiveStates: string[];
  sorts: string[];
  maxPageSize: number;
}

export interface ListingBundleBindingRow {
  id: string;
  mappingId: string;
  listingId: string;
  listingVariantId: string;
  shopCode: string;
  externalItemId: string;
  externalVariantId: string;
  /** 🔴 catalog 3.5.1 套装版本逻辑 id（跨服务，不建 FK） */
  bundleVersionId: string;
  /** 🔴 生效区间起点 */
  effectiveFrom: string;
  /** null = 当前生效 */
  effectiveTo: string | null;
  current: boolean;
  changeReason: string;
  actorId: string | null;
  componentCount: number;
  /** 🔴 每卖 1 件平台套装要扣的总件数；无组件为 0 */
  totalQuantity: Quantity;
  createdAt: string;
}

/** 套装版本绑定（输入句的「套装版本选择」）的写入载荷。 */
export interface SaveBundleBindingInput {
  id?: string | null;
  mappingId: string;
  bundleVersionId: string;
  /** 🔴 换版时填当前生效那一版的退役时刻 */
  retireCurrentAt?: string | null;
  changeReason: string;
  effectiveFrom?: string | null;
}

export interface ListingBundleBindingPage {
  items: ListingBundleBindingRow[];
  total: number;
  page: number;
  size: number;
  currentInPage: number;
}

export interface ListingBundleComponentRow {
  id: string;
  bindingId: string;
  /** 🔴 catalog 03 的组件 SKU 逻辑 id */
  componentSkuId: string;
  /** 🔴 每卖 1 件平台套装需要这个组件多少；恒 > 0（NUMERIC(19,6)） */
  quantity: number;
  role: string;
  note: string;
  /** 已退役绑定的组件是历史，只读 */
  bindingCurrent: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ListingBundleComponentPage {
  items: ListingBundleComponentRow[];
  total: number;
  page: number;
  size: number;
}

export interface ListingBundleBindingDetail {
  binding: ListingBundleBindingRow;
  /** 🔴 组件映射记录（蓝图输出句） */
  components: ListingBundleComponentRow[];
  note: string;
}

export interface ListingBundleComponentDetail {
  removedId: string;
  bindingId: string;
  componentsBeforeRemoval: ListingBundleComponentRow[];
  binding: ListingBundleBindingRow;
  note: string;
}

/** 组件映射维护的写入载荷。 */
export interface SaveBundleComponentInput {
  id?: string | null;
  bindingId: string;
  componentSkuId: string;
  /** 🔴 必须 > 0 */
  quantity: number;
  role: string;
  note: string;
}

/** 单个组件的可售性（输出句的「可售性校验结果」）。 */
export interface ListingBundleComponentSellable {
  componentId: string;
  componentSkuId: string;
  quantity: number;
  sellableQuantity: number;
  /** 🔴 按当前组件数量还能成套卖几件（向下取整） */
  setsAvailable: number;
  sellable: boolean;
  reason: string | null;
}

/**
 * 可售性校验结果。
 *
 * 🔴 `available` 与 `bundleSellable` 是**两个独立布尔**：前者是「上游读到了没有」，
 * 后者是「确实能不能卖」。把两者合成一个，界面就再也无法区分「查不到」与「不可售」。
 */
export interface ListingBundleSellableCheck {
  bindingId: string;
  mappingId: string;
  bundleVersionId: string;
  warehouseId: string;
  available: boolean;
  /** 🔴 缺任一组件 ⇒ 整套不可售 */
  bundleSellable: boolean;
  componentCount: number;
  unsellableCount: number;
  reason: string | null;
  components: ListingBundleComponentSellable[];
  note: string;
}

// ================================================================ 筛选

export interface BundleFilters {
  mappingId: string;
  listingId: string;
  listingVariantId: string;
  bundleVersionId: string;
  shopCode: string;
  effectiveState: string;
  search: string;
  sort: string;
  page: number;
  size: number;
}

export function emptyFilters(): BundleFilters {
  return {
    mappingId: '',
    listingId: '',
    listingVariantId: '',
    bundleVersionId: '',
    shopCode: '',
    effectiveState: '',
    search: '',
    sort: 'RECENT',
    page: 0,
    size: 50,
  };
}

const FILTER_KEYS = [
  'mappingId',
  'listingId',
  'listingVariantId',
  'bundleVersionId',
  'shopCode',
  'effectiveState',
  'search',
] as const satisfies readonly (keyof BundleFilters)[];

/** 翻译成 query string。筛选与分页全在服务端，前端不在浏览器里过滤已分页的数据。 */
export function buildQuery(filters: BundleFilters): string {
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

export function activeFilterCount(filters: BundleFilters): number {
  return FILTER_KEYS.filter((key) => (filters[key] ?? '').trim().length > 0).length;
}

/** 改任一筛选条件都回到第 0 页 —— 停在第 3 页再看筛选结果永远是空的。 */
export function applyFilterChange(
  filters: BundleFilters,
  patch: Partial<BundleFilters>,
): BundleFilters {
  return { ...filters, ...patch, page: 0 };
}

export function resetFilters(filters: BundleFilters): BundleFilters {
  const size = filters.size;
  return { ...emptyFilters(), size };
}

// ================================================================ 组件数量：蓝图加粗句的前端一侧

/**
 * 🔴 合法数量区间是 `> 0`，不是「非负」。
 *
 * <p>返回 null 表示合法。**不返回下界 0**：0 本身是违法的，写成 `[0, ∞)` 会让
 * 「配了一个组件但不消耗」这种行在表单上看起来是合法的。
 */
export function quantityRange(): { min: number; exclusiveMin: boolean } {
  return { min: 0, exclusiveMin: true };
}

/** 输入框的 step：NUMERIC(19,6) 支持小数（0.5 米挂绳 + 2 个挂钩是真实场景）。 */
export function quantityStep(): string {
  return '0.000001';
}

/** 非法时给出**与后端同一句**的原因；合法时返回 null。 */
export function quantityInvalidReason(raw: string): string | null {
  const value = raw.trim();
  if (value === '') return '组件数量必填：没有数量的组件答不出「这个组件要几件」';
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return '组件数量必须是数字';
  if (parsed <= 0) {
    return '组件数量必须大于 0：数量为 0 的组件会让 07 库存扣出一条 0 扣减，两边都答不出「这个组件到底要不要」';
  }
  return null;
}

// ================================================================ 展示

export function effectiveStateLabel(state: string): string {
  switch (state) {
    case 'CURRENT':
      return '当前生效';
    case 'RETIRED':
      return '已退役';
    default:
      return state;
  }
}

export function sortLabel(sort: string): string {
  switch (sort) {
    case 'EFFECTIVE':
      return '按生效时间';
    case 'OLDEST':
      return '按生效时间（早→晚）';
    case 'MAPPING':
      return '按映射分组';
    default:
      return '按最近登记';
  }
}

/** 生效区间的人类可读形态：[from, to)，终点空 = 当前生效。 */
export function effectiveWindow(row: ListingBundleBindingRow): string {
  return row.effectiveTo
    ? `${formatMoment(row.effectiveFrom)} → ${formatMoment(row.effectiveTo)}（已退役）`
    : `${formatMoment(row.effectiveFrom)} → 至今（当前生效）`;
}

/**
 * 时刻展示：只做格式化，不做时区换算。
 *
 * 🔴 刻意**不**用 `toLocaleString()`：那会把服务端给的偏移量换算成浏览器本地时区，
 * 「生效时间点」在跨时区团队里就会各说各的。
 */
export function formatMoment(value: string | null | undefined): string {
  if (!value) return '—';
  return value.replace('T', ' ').replace(/\.\d+/, '').replace(/([+-]\d{2}:\d{2}|Z)$/, ' $1');
}

/**
 * 数量展示：去掉无意义的尾随零，但**不做单位换算**。
 *
 * <p>`0.500000` 显示成 `0.5`，`2.000000` 显示成 `2` —— 后端存的是 NUMERIC(19,6)，
 * 直接 `toString()` 会得到一串零，界面上看着像精度错误。
 */
export function formatQuantity(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return String(Number(value));
}

export function pageSummary(page: ListingBundleBindingPage | null): string {
  if (!page || page.total === 0) return '共 0 条';
  const from = page.page * page.size + 1;
  const to = Math.min(from + page.items.length - 1, page.total);
  return `共 ${page.total} 条，当前显示第 ${from}-${to} 条`;
}

export function hasNextPage(page: ListingBundleBindingPage | null): boolean {
  if (!page) return false;
  return (page.page + 1) * page.size < page.total;
}

export function hasPrevPage(page: ListingBundleBindingPage | null): boolean {
  if (!page) return false;
  return page.page > 0;
}

/** 服务端标了 available=false 的维度（未交付段明账）。 */
export function unavailableDimensions(
  caps: ListingBundleComponentCapabilities | null,
): BundleCapabilityDimension[] {
  if (!caps) return [];
  return caps.dimensions.filter((d) => !d.available);
}

/**
 * 🔴 整套可售性的**一句话结论**，置顶显示。
 *
 * <p>三个分支刻意互斥且都成话：
 * <ul>
 *   <li>还没校验（check 为 null）→ 不给结论，界面显示「尚未校验」而不是「可售」；</li>
 *   <li>组件数为 0 → 「没有组件，不可售」（缺组件，不是缺货）；</li>
 *   <li>有组件 → 「可售 N 件」或「整套不可售：哪几个组件不够」。</li>
 * </ul>
 * 逐行状态**不足以**推出结论，所以这里不让用户自己数。
 */
export function sellableBanner(check: ListingBundleSellableCheck | null): {
  tone: 'unknown' | 'ok' | 'bad';
  text: string;
} {
  if (!check) return { tone: 'unknown', text: '尚未校验' };
  if (check.componentCount === 0) {
    return { tone: 'bad', text: '这一版没有登记任何组件：按规则没有组件的套装不可售' };
  }
  if (check.bundleSellable) {
    const min = Math.min(...check.components.map((c) => c.setsAvailable));
    return { tone: 'ok', text: `整套可售，当前最多可成套卖出 ${formatQuantity(min)} 件` };
  }
  const short = check.components.filter((c) => !c.sellable).length;
  return {
    tone: 'bad',
    text: `整套不可售：${check.componentCount} 个组件里有 ${short} 个可售量不足（缺任一组件 ⇒ 整套不可售）`,
  };
}

/** 可售量展示：读不到库存行时如实说「无库存行」，而不是显示一个 0 被读成「真的是 0」。 */
export function sellableText(component: ListingBundleComponentSellable): string {
  return `${formatQuantity(component.sellableQuantity)}（每件需 ${formatQuantity(component.quantity)}）`;
}

// ================================================================ API

export function fetchCapabilities(): Promise<ListingBundleComponentCapabilities> {
  return api<ListingBundleComponentCapabilities>('channel/bundle-components/capabilities');
}

export function fetchFilterOptions(): Promise<ListingBundleFilterOptions> {
  return api<ListingBundleFilterOptions>('channel/bundle-components/filter-options');
}

export function fetchBindings(filters: BundleFilters): Promise<ListingBundleBindingPage> {
  return api<ListingBundleBindingPage>(`channel/bundle-components/bindings?${buildQuery(filters)}`);
}

export function fetchBinding(id: string): Promise<ListingBundleBindingDetail> {
  return api<ListingBundleBindingDetail>(`channel/bundle-components/bindings/${id}`);
}

export function fetchSellableCheck(
  id: string,
  warehouseId: string,
): Promise<ListingBundleSellableCheck> {
  return api<ListingBundleSellableCheck>(
    `channel/bundle-components/bindings/${id}/sellable-check?warehouseId=${encodeURIComponent(warehouseId)}`,
  );
}

export function saveBinding(
  input: SaveBundleBindingInput,
): Promise<ListingBundleBindingDetail> {
  return api<ListingBundleBindingDetail>(
    'channel/bundle-components/bindings',
    'POST',
    input,
  );
}

export function addComponent(input: SaveBundleComponentInput): Promise<ListingBundleComponentRow> {
  return api<ListingBundleComponentRow>(
    'channel/bundle-components/components',
    'POST',
    input,
  );
}

export function updateComponent(
  id: string,
  input: SaveBundleComponentInput,
): Promise<ListingBundleComponentRow> {
  return api<ListingBundleComponentRow>(
    `channel/bundle-components/components/${id}`,
    'PUT',
    input,
  );
}

export function removeComponent(id: string): Promise<ListingBundleComponentDetail> {
  return api<ListingBundleComponentDetail>(
    `channel/bundle-components/components/${id}`,
    'DELETE',
  );
}
