import { api } from '../inventory/api';

/**
 * 6.1.2.1 档口与货源池前端模型。
 *
 * <p>蓝图加粗句：「**货源数据是外部快照，会变**，采购下单时必须以实际下单金额为准，
 * 货源快照仅作参考。」
 *
 * <p>这条约束在界面上的直接后果有三个，缺一个界面就会开始骗人：
 *
 * <ol>
 *   <li><b>价格永远是区间</b>：后端返回 priceMin / priceMax 两个数，界面上永远显示成
 *       「下限 ~ 上限」，**没有**任何地方把它压成一个「精确价」；
 *   <li><b>价格必须带着采集时刻看</b>：每一行都显示「采集于 …」，让人知道这个价是什么时候的；
 *   <li><b>「当前价」不是一个独立概念</b>：界面上显示的永远是「最近一次采集看到的价」，
 *       历史版本在货源详情里可查，旧版本永不改写。
 * </ol>
 *
 * <p>另外两条界面上必须守住的口径：
 *
 * <ul>
 *   <li><b>不跨币种比价</b>：比价按币种分组给出最低价（汇率属 12 财务，本模块没有汇率快照）；
 *   <li><b>抓不到就说抓不到</b>：同步失败时界面显示的是**失败原因**，不是空列表。
 * </ul>
 */

// ================================================================== 类型

/** 一个词表选项（值 / 中文名 / 提示）。 */
export interface SupplierSourceOption {
  value: string;
  label: string;
  hint: string;
}

/** 一个可排序键。 */
export interface SupplierSourceSortOption {
  key: string;
  label: string;
  defaultDesc: boolean;
}

/** 采集接入登记（**只含凭证引用，不含明文**）。 */
export interface CollectorEndpointRow {
  platform: string;
  platformLabel: string;
  baseUrl: string;
  credentialRef: string;
  wired: boolean;
  status: string;
  note: string;
  updatedAt: string | null;
}

/** 词表选项。 */
export interface SupplierSourceOptions {
  platforms: SupplierSourceOption[];
  collectMethods: SupplierSourceOption[];
  statuses: SupplierSourceOption[];
  sorts: SupplierSourceSortOption[];
  collectorEndpoints: CollectorEndpointRow[];
  note: string;
}

/** 档口（店铺）一行。 */
export interface StallRow {
  id: string;
  code: string;
  platform: string;
  platformLabel: string;
  platformStallId: string;
  name: string;
  url: string;
  collectMethod: string;
  collectMethodLabel: string;
  status: string;
  statusLabel: string;
  supplierPartyId: string | null;
  supplierName: string;
  lastCollectedAt: string | null;
  lastCollectionStatus: string | null;
  lastFailureReason: string;
  itemCount: number;
  favoriteCount: number;
  skuLinkCount: number;
  note: string;
  version: number;
}

/** 一次采集批次。 */
export interface CollectionRow {
  id: string;
  stallId: string;
  collectMethod: string;
  collectMethodLabel: string;
  status: string;
  statusLabel: string;
  triggerSource: string;
  triggerSourceLabel: string;
  collectedAt: string;
  startedAt: string;
  finishedAt: string | null;
  itemCount: number;
  skippedCount: number;
  skippedReason: string;
  failureReason: string;
}

/** 货源条目一行（价格与起订量来自**当前快照**）。 */
export interface SourceItemRow {
  id: string;
  stallId: string;
  stallName: string;
  platform: string;
  platformLabel: string;
  platformItemId: string;
  title: string;
  priceMin: number;
  priceMax: number;
  currency: string;
  moq: number;
  spec: string;
  imageUrl: string;
  itemUrl: string;
  onSale: boolean;
  collectedAt: string;
  snapshotSeq: number;
  favorited: boolean;
  supplierName: string;
  skuCodes: string[];
  currentSnapshotId: string | null;
}

/** 一版采集快照。 */
export interface SourceSnapshotRow {
  id: string;
  itemId: string;
  snapshotSeq: number;
  title: string;
  priceMin: number;
  priceMax: number;
  currency: string;
  moq: number;
  spec: string;
  imageUrl: string;
  itemUrl: string;
  onSale: boolean;
  collectedAt: string;
  collectionId: string;
  collectionStatus: string;
  current: boolean;
}

/** 货源条目详情（含全部快照版本 = 价格历史）。 */
export interface SourceItemDetail {
  item: SourceItemRow;
  snapshots: SourceSnapshotRow[];
  note: string;
}

/** 货源条目列表分页结果。 */
export interface SourceItemPage {
  items: SourceItemRow[];
  page: number;
  size: number;
  total: number;
  onSaleCount: number;
  offSaleCount: number;
  favoriteCount: number;
  linkedSkuCount: number;
  note: string;
}

/** 档口列表分页结果。 */
export interface StallPage {
  items: StallRow[];
  page: number;
  size: number;
  total: number;
  activeCount: number;
  pausedCount: number;
  failedCollectionCount: number;
  note: string;
}

/** 档口详情。 */
export interface StallDetail {
  stall: StallRow;
  items: SourceItemRow[];
  collections: CollectionRow[];
  collectorEndpoint: CollectorEndpointRow;
  note: string;
}

/** 比价候选货源。 */
export interface SkuCandidate {
  itemId: string;
  stallId: string;
  stallName: string;
  platform: string;
  platformLabel: string;
  platformItemId: string;
  title: string;
  supplierName: string;
  priceMin: number;
  priceMax: number;
  currency: string;
  moq: number;
  spec: string;
  collectedAt: string;
  onSale: boolean;
  favorited: boolean;
  lowestInCurrency: boolean;
}

/** 某币种下的最低价候选。 */
export interface LowestPrice {
  currency: string;
  itemId: string;
  title: string;
  priceMin: number;
  priceMax: number;
  moq: number;
}

/** SKU 货源关联视图 + 比价。 */
export interface SkuSourceView {
  skuId: string;
  skuCode: string;
  skuName: string;
  linkCount: number;
  candidates: SkuCandidate[];
  lowestByCurrency: LowestPrice[];
  note: string;
}

/** 概览统计。 */
export interface SourceOverview {
  stallCount: number;
  activeStallCount: number;
  pausedStallCount: number;
  itemCount: number;
  onSaleCount: number;
  offSaleCount: number;
  favoriteCount: number;
  skuLinkCount: number;
  snapshotCount: number;
  failedCollectionCount: number;
  lastCollectedAt: string | null;
  note: string;
}

/** 一个未交付的下游维度。 */
export interface SourceGap {
  moduleId: string;
  moduleName: string;
  ownerModule: string;
  available: boolean;
  impact: string;
}

/** 能力声明。 */
export interface SupplierSourceCapabilities {
  platforms: SupplierSourceOption[];
  collectMethods: SupplierSourceOption[];
  collectorEndpoints: CollectorEndpointRow[];
  gaps: SourceGap[];
  snapshotSemantics: string;
  note: string;
}

/** 同步结果（成功 / 失败都带原因）。 */
export interface SyncResult {
  stallId: string;
  collectionId: string;
  status: string;
  statusLabel: string;
  itemCount: number;
  skippedCount: number;
  skippedReason: string;
  failureReason: string;
  collectedAt: string;
}

/** 收藏结果。 */
export interface FavoriteSaved {
  itemId: string;
  favorited: boolean;
  favoritedAt: string | null;
}

/** 供应商候选（catalog 只读）。 */
export interface SupplierCandidateRow {
  roleId: string;
  partyCode: string;
  partyName: string;
  partyStatus: string;
}

// ================================================================== 表单 / 筛选

/** 档口登记入参。 */
export interface StallInput {
  code: string;
  platform: string;
  platformStallId: string;
  name: string;
  url: string;
  collectMethod: string;
  supplierPartyId: string | null;
  note: string;
  version: number;
}

/** 档口修改入参。 */
export interface StallUpdateInput {
  name: string;
  url: string;
  collectMethod: string;
  supplierPartyId: string | null;
  status: string;
  note: string;
  version: number;
}

/** 一条被采到的货源。 */
export interface CollectedItemInput {
  platformItemId: string;
  title: string;
  priceMin: string;
  priceMax: string;
  currency: string;
  moq: string;
  spec: string;
  imageUrl: string;
  itemUrl: string;
  onSale: boolean;
}

/** 采集接入登记入参。 */
export interface CollectorEndpointInput {
  baseUrl: string;
  credentialRef: string;
  wired: boolean;
  note: string;
}

/** 档口列表筛选。 */
export interface StallFilters {
  platform: string;
  collectMethod: string;
  status: string;
  keyword: string;
  favoritedOnly: boolean;
  page: number;
  size: number;
}

/** 货源条目列表筛选。 */
export interface ItemFilters {
  stallId: string;
  platform: string;
  keyword: string;
  onSale: 'ALL' | 'ON_SALE' | 'OFF_SALE';
  favoritedOnly: boolean;
  skuId: string;
  priceFrom: string;
  priceTo: string;
  sort: string;
  desc: boolean;
  page: number;
  size: number;
}

export const EMPTY_STALL_FILTERS: StallFilters = {
  platform: '',
  collectMethod: '',
  status: '',
  keyword: '',
  favoritedOnly: false,
  page: 0,
  size: 20,
};

export const EMPTY_ITEM_FILTERS: ItemFilters = {
  stallId: '',
  platform: '',
  keyword: '',
  onSale: 'ALL',
  favoritedOnly: false,
  skuId: '',
  priceFrom: '',
  priceTo: '',
  sort: 'collectedAt',
  desc: true,
  page: 0,
  size: 20,
};

/** 空表单初值（新建档口）。 */
export function emptyStallForm(): StallInput {
  return {
    code: '',
    platform: 'ALIBABA_1688',
    platformStallId: '',
    name: '',
    url: '',
    collectMethod: 'PLUGIN_PUSH',
    supplierPartyId: null,
    note: '',
    version: 0,
  };
}

/** 空表单初值（推送一条采集结果）。 */
export function emptyCollectedItem(): CollectedItemInput {
  return {
    platformItemId: '',
    title: '',
    priceMin: '',
    priceMax: '',
    currency: 'CNY',
    moq: '',
    spec: '',
    imageUrl: '',
    itemUrl: '',
    onSale: true,
  };
}

// ================================================================== 展示口径

/** 金额 → 展示串：保留最多 4 位小数，不补零。 */
export function priceText(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return value.toFixed(4).replace(/\.?0+$/, '');
}

/**
 * 价格区间 → 展示串。
 *
 * <p>**下限与上限都在**：外部货源普遍是阶梯价，把区间压成一个数就是在编一个精确价。
 * 上下限相同时显示成一个数（那确实是精确价）。
 */
export function priceRangeText(
  min: number | null | undefined,
  max: number | null | undefined,
  currency?: string | null,
): string {
  const prefix = currency ? `${currency} ` : '';
  if (min === null || min === undefined) return '—';
  if (max === null || max === undefined) return `${prefix}${priceText(min)}`;
  if (min === max) return `${prefix}${priceText(min)}`;
  return `${prefix}${priceText(min)} ~ ${priceText(max)}`;
}

/** 起订量 → 展示串。 */
export function moqText(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return `${value.toFixed(6).replace(/\.?0+$/, '')} 起`;
}

/** ISO 时刻 → 只到分钟的串。空值显示「从未采集」。 */
export function collectedAtText(value: string | null | undefined): string {
  if (!value) return '从未采集';
  return value.slice(0, 16).replace('T', ' ');
}

/** 采集结果 → 展示串（成功 / 失败 + 原因）。 */
export function collectionStatusText(
  status: string | null | undefined,
  reason: string | null | undefined,
): string {
  if (!status) return '从未采集';
  if (status === 'FAILED') return `失败：${reason ?? '未记录原因'}`;
  return '成功';
}

/** 采集结果 → 展示色。 */
export function collectionStatusTone(
  status: string | null | undefined,
): 'success' | 'warning' | 'neutral' {
  if (status === 'SUCCEEDED') return 'success';
  if (status === 'FAILED') return 'warning';
  return 'neutral';
}

/** 在售状态 → 展示色。 */
export function onSaleTone(onSale: boolean): 'success' | 'neutral' {
  return onSale ? 'success' : 'neutral';
}

/** 币种 → 展示名（没有词表时用代码本身）。 */
export function currencyLabel(currency: string | null | undefined): string {
  return currency ? currency : '—';
}

// ================================================================== 本地校验

/** 数字串 → 非负数校验（前端只做最基础的形态拦截，后端有规则层与 DB CHECK 兜底）。 */
function decimalProblem(label: string, raw: string, allowZero: boolean): string[] {
  const value = raw.trim();
  if (!value) return [`${label}必填`];
  if (!/^-?\d+(\.\d+)?$/.test(value)) return [`${label}请填数字：${raw}`];
  const parsed = Number(value);
  if (Number.isNaN(parsed)) return [`${label}请填数字：${raw}`];
  if (allowZero ? parsed < 0 : parsed <= 0)
    return [allowZero ? `${label}不能为负` : `${label}必须大于 0`];
  return [];
}

/** 币种格式：ISO 4217 三位大写。刻意不校验它是不是真实币种（完整词表是 12 财务的资产）。 */
export function validateCurrency(currency: string): string[] {
  const value = currency.trim().toUpperCase();
  if (!value) return ['币种必填'];
  if (!/^[A-Z]{3}$/.test(value)) return [`币种请用 ISO 4217 三位大写字母码：${currency}`];
  return [];
}

/** 档口登记 / 修改的本地校验。 */
export function validateStall(input: StallInput): string[] {
  const problems: string[] = [];
  if (!input.code.trim()) problems.push('档口编码必填：它是档案上的稳定引用');
  if (!input.platformStallId.trim()) problems.push('平台档口 ID 必填：它是外部平台上的那个店铺');
  if (!input.name.trim()) problems.push('档口名称必填');
  if (!input.platform.trim()) problems.push('平台必填');
  if (!input.collectMethod.trim()) problems.push('采集方式必填');
  if (input.note.length > 2000) problems.push('备注最长 2000 字');
  return problems;
}

/**
 * 一条采集结果的本地校验。
 *
 * <p>价格区间必须**同时有下限与上限**，且上限不得低于下限 —— 这正是后端那条
 * 「价格区间写反 → 跳过并写明原因」的同一道闸。
 */
export function validateCollectedItem(input: CollectedItemInput): string[] {
  const problems: string[] = [];
  if (!input.platformItemId.trim()) problems.push('平台货源 ID 必填：它是外部平台上的那个货');
  if (!input.title.trim()) problems.push('货源标题必填');
  problems.push(...decimalProblem('价格下限', input.priceMin, false));
  problems.push(...decimalProblem('价格上限', input.priceMax, false));
  if (input.priceMin.trim() && input.priceMax.trim()) {
    const min = Number(input.priceMin.trim());
    const max = Number(input.priceMax.trim());
    if (!Number.isNaN(min) && !Number.isNaN(max) && max < min) {
      problems.push('价格上限不能低于下限：外部货源是阶梯价，区间写反了就不是这个货的价');
    }
  }
  problems.push(...decimalProblem('起订量', input.moq, false));
  problems.push(...validateCurrency(input.currency));
  return problems;
}

/**
 * 采集接入登记的本地校验。
 *
 * <p>凭证只允许**引用**（secret:// / kv:// / vault:// / external-secrets://）——
 * 前端这道校验是为了早点告诉人「贴进来的是明文」，后端与 DB CHECK 各拦一道。
 */
export function validateCollectorEndpoint(input: CollectorEndpointInput): string[] {
  const problems: string[] = [];
  if (input.wired) {
    if (!input.baseUrl.trim()) problems.push('声称已接线就必须填网关地址');
    if (!input.credentialRef.trim()) problems.push('声称已接线就必须填凭证引用');
  }
  const ref = input.credentialRef.trim();
  if (ref && !/^(secret:\/\/|kv:\/\/|vault:\/\/|external-secrets:\/\/)/.test(ref)) {
    problems.push(
      '凭证只能是引用（secret:// / kv:// / vault:// / external-secrets:// 开头），本系统不保存明文密钥',
    );
  }
  if (input.note.length > 2000) problems.push('备注最长 2000 字');
  return problems;
}

// ================================================================== 端点

export function fetchSourceOptions(): Promise<SupplierSourceOptions> {
  return api<SupplierSourceOptions>('trade/sources/options');
}

export function fetchSourceOverview(): Promise<SourceOverview> {
  return api<SourceOverview>('trade/sources/overview');
}

export function fetchSourceCapabilities(): Promise<SupplierSourceCapabilities> {
  return api<SupplierSourceCapabilities>('trade/sources/capabilities');
}

export function fetchStalls(filters: StallFilters): Promise<StallPage> {
  const query = new URLSearchParams();
  if (filters.platform) query.set('platform', filters.platform);
  if (filters.collectMethod) query.set('collectMethod', filters.collectMethod);
  if (filters.status) query.set('status', filters.status);
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.favoritedOnly) query.set('favoritedOnly', 'true');
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<StallPage>(`trade/sources/stalls?${query.toString()}`);
}

export function fetchStall(stallId: string): Promise<StallDetail> {
  return api<StallDetail>(`trade/sources/stalls/${stallId}`);
}

export function createStall(input: StallInput): Promise<StallRow> {
  return api<StallRow>('trade/sources/stalls', 'POST', input);
}

export function updateStall(stallId: string, input: StallUpdateInput): Promise<StallRow> {
  return api<StallRow>(`trade/sources/stalls/${stallId}`, 'PUT', input);
}

/** 触发一次主动抓取。失败时后端返回 status=FAILED + 失败原因（不是空列表）。 */
export function syncStall(stallId: string): Promise<SyncResult> {
  return api<SyncResult>(`trade/sources/stalls/${stallId}/sync`, 'POST');
}

/** 插件推送一次采集结果。 */
export function pushCollection(stallId: string, items: CollectedItemInput[]): Promise<SyncResult> {
  const payload = items.map((item) => ({
    platformItemId: item.platformItemId.trim(),
    title: item.title.trim(),
    priceMin: item.priceMin.trim(),
    priceMax: item.priceMax.trim(),
    currency: item.currency.trim().toUpperCase(),
    moq: item.moq.trim(),
    spec: item.spec.trim(),
    imageUrl: item.imageUrl.trim(),
    itemUrl: item.itemUrl.trim(),
    onSale: item.onSale,
  }));
  return api<SyncResult>(`trade/sources/stalls/${stallId}/collections`, 'POST', { items: payload });
}

export function fetchItems(filters: ItemFilters): Promise<SourceItemPage> {
  const query = new URLSearchParams();
  if (filters.stallId) query.set('stallId', filters.stallId);
  if (filters.platform) query.set('platform', filters.platform);
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.onSale === 'ON_SALE') query.set('onSale', 'true');
  if (filters.onSale === 'OFF_SALE') query.set('onSale', 'false');
  if (filters.favoritedOnly) query.set('favoritedOnly', 'true');
  if (filters.skuId) query.set('skuId', filters.skuId);
  if (filters.priceFrom) query.set('priceFrom', filters.priceFrom);
  if (filters.priceTo) query.set('priceTo', filters.priceTo);
  if (filters.sort) query.set('sort', filters.sort);
  query.set('desc', String(filters.desc));
  query.set('page', String(filters.page));
  query.set('size', String(filters.size));
  return api<SourceItemPage>(`trade/sources/items?${query.toString()}`);
}

export function fetchItem(itemId: string): Promise<SourceItemDetail> {
  return api<SourceItemDetail>(`trade/sources/items/${itemId}`);
}

export function favoriteItem(itemId: string, note = ''): Promise<FavoriteSaved> {
  return api<FavoriteSaved>(`trade/sources/items/${itemId}/favorite`, 'POST', { note });
}

export function unfavoriteItem(itemId: string): Promise<FavoriteSaved> {
  return api<FavoriteSaved>(`trade/sources/items/${itemId}/favorite`, 'DELETE');
}

export function linkSku(itemId: string, skuId: string, note = ''): Promise<unknown> {
  return api(`trade/sources/items/${itemId}/sku-links`, 'POST', { skuId, note });
}

export function unlinkSku(itemId: string, linkId: string): Promise<unknown> {
  return api(`trade/sources/items/${itemId}/sku-links/${linkId}`, 'DELETE');
}

/** 某内部 SKU 的货源关联视图 + 比价。 */
export function fetchSkuSources(skuId: string): Promise<SkuSourceView> {
  return api<SkuSourceView>(`trade/sources/sku/${skuId}/sources`);
}

/** 供应商候选（catalog 只读；取不到会 503，不会返回空清单）。 */
export function fetchSupplierCandidates(keyword = ''): Promise<SupplierCandidateRow[]> {
  const query = new URLSearchParams();
  if (keyword) query.set('keyword', keyword);
  return api<SupplierCandidateRow[]>(`trade/sources/suppliers?${query.toString()}`);
}

export function fetchCollectorEndpoint(platform: string): Promise<CollectorEndpointRow> {
  return api<CollectorEndpointRow>(`trade/sources/collector-endpoint/${platform}`);
}

export function saveCollectorEndpoint(
  platform: string,
  input: CollectorEndpointInput,
): Promise<unknown> {
  return api(`trade/sources/collector-endpoint/${platform}`, 'PUT', input);
}
