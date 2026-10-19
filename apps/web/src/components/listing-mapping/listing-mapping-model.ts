import { api } from '../inventory/api';

/**
 * 4.1.2.1 映射管理 前端模型。
 *
 * 蓝图：「建立并维护"平台变体 → 内部 SKU/套装"的映射，支持一对一、一对多（套装）、多对一；
 * 支持批量映射（按规则：SKU 编码匹配、条码匹配、标题相似度推荐但需人工确认）。输入：映射建立操作；
 * 匹配规则；批量候选。输出：`listing_mapping_version` 记录（当前生效版本 + 历史版本）；
 * 未映射清单回流 02 映射缺失队列。关系：上游 = 4.1.1.1、02 同步到的 listing；下游 = 09 订单
 * （订单行按映射解析 SKU）、07 库存（回写可售量）。**映射未建立时订单进入待映射状态，
 * 绝不按名称猜 SKU**（实体目录 §6）。」
 *
 * 🔴 这个页面上最容易做丢的三件事，这里都挡住了：
 *
 * 1. **候选不是映射**。`MappingCandidate` 上有 `requiresConfirmation` 字段，界面上
 *    标题相似度候选恒带「待人工确认」徽章，且**确认之前界面上不显示任何映射** ——
 *    前端自己去重画一条映射出来，就等于把「按名称猜 SKU」这条禁令从服务端搬到了浏览器里。
 *
 * 2. **筛选与排序全在服务端**。前端只翻译 query string，不在浏览器里过滤已分页的数据
 *    （那样会得到「总数说 500、实际只筛得出当前页 50 条」）。
 *
 * 3. **未交付段如实显示**。`unavailableDimensions()` 把服务端标了 `available=false` 的维度
 *    逐条列出来（归属模块 + 不交付的影响），而不是画 0、画占位、或者干脆不提。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * 服务端 `BigDecimal` 的事在前端算一遍就会引入二进制浮点误差。
 */

/** 🔴 null = 服务端没给这个值，与 `0` 含义相反。 */
export type MappingNumber = number | null;

export interface MappingCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface MappingCapabilities {
  dimensions: MappingCapabilityDimension[];
  relationTypes: string[];
  mappingStatuses: string[];
  matchRules: string[];
  candidateDecisions: string[];
  resolveStatuses: string[];
  maxVariantsPerBatch: number;
  maxCandidateSkus: number;
  similarityThreshold: number;
  gapNote: string;
  dependencyNote: string;
}

export interface MappingFilterOptions {
  relationTypes: string[];
  mappingStatuses: string[];
  statuses: string[];
  matchRules: string[];
  candidateDecisions: string[];
  sorts: string[];
  maxVariantsPerBatch: number;
}

/** 映射列表的一行。 */
export interface MappingRow {
  id: string;
  listingId: string;
  externalItemId: string;
  title: string;
  shopCode: string;
  listingVariantId: string;
  externalVariantId: string;
  /** 🔴 映射基数：ONE_TO_ONE / ONE_TO_MANY / MANY_TO_ONE */
  relationType: string;
  /** ACTIVE 生效中 / REMOVED 已解除 */
  status: string;
  /** MAPPED / BUNDLE */
  mappingStatus: string;
  /** 🔴 内部 SKU 逻辑 id（03 拥有，跨服务不建 FK） */
  internalSkuId: string | null;
  /** 套装组件数量；仅 ONE_TO_MANY 有值 */
  componentQuantity: MappingNumber;
  currentVersionId: string | null;
  currentVersionNo: MappingNumber;
  effectiveFrom: string | null;
  sharedWithVariants: number;
  versionCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface MappingVersionView {
  id: string;
  versionNo: number;
  relationType: string;
  internalSkuId: string | null;
  componentQuantity: MappingNumber;
  /** 🔴 生效时间点：区间为 [effectiveFrom, effectiveTo) */
  effectiveFrom: string;
  /** null = 当前生效版本 */
  effectiveTo: string | null;
  changeReason: string;
  changeSource: string;
  actorId: string | null;
  createdAt: string;
}

export interface MappingDetail {
  mapping: MappingRow;
  versions: MappingVersionView[];
  affectedOrdersNote: string;
}

export interface MappingPage {
  items: MappingRow[];
  total: number;
  page: number;
  size: number;
  removedInPage: number;
}

export interface MappingCandidate {
  id: string;
  batchTaskId: string;
  listingId: string;
  listingVariantId: string;
  externalVariantId: string;
  rule: string;
  internalSkuId: string;
  internalSkuCode: string;
  internalSkuName: string;
  matchedText: string;
  score: number;
  /** 🔴 标题相似度恒为 true：候选在人工确认前不是映射 */
  requiresConfirmation: boolean;
  decision: string;
  decidedBy: string | null;
  decidedAt: string | null;
  decisionReason: string;
  createdAt: string;
}

export interface BatchCandidateResult {
  batchTaskId: string;
  rule: string;
  requestedCount: number;
  scannedVariantCount: number;
  candidateCount: number;
  scannedSkus: number;
  /** 🔴 true = 只比对了前 N 条 SKU，不是全集 */
  truncated: boolean;
  requiresConfirmationCount: number;
  candidates: MappingCandidate[];
  reason: string;
}

export interface CandidatePage {
  items: MappingCandidate[];
  total: number;
  page: number;
  size: number;
}

export interface CandidateDecisionResult {
  candidateId: string;
  decision: string;
  mappingId: string | null;
  versionId: string | null;
  versionNo: MappingNumber;
  reason: string;
}

export interface UnmappedVariantRow {
  listingVariantId: string;
  listingId: string;
  externalItemId: string;
  title: string;
  shopCode: string;
  externalVariantId: string;
  attributes: string;
  ordinal: number;
  unmappedDays: number;
}

export interface UnmappedPage {
  items: UnmappedVariantRow[];
  total: number;
  page: number;
  size: number;
  /** 🔴 回流目标 02 映射缺失队列（2.4.2.3）的交付状态 */
  backflowDelivered: boolean;
  backflowOwnerModule: string;
  backflowNote: string;
}

export interface MappingResolveResult {
  listingVariantId: string;
  externalVariantId: string;
  /** 🔴 RESOLVED / PENDING_MAPPING */
  status: string;
  /** PENDING_MAPPING 时必为 null */
  internalSkuId: string | null;
  relationType: string | null;
  componentQuantity: MappingNumber;
  mappingId: string | null;
  versionNo: MappingNumber;
  effectiveFrom: string | null;
  pendingReason: string;
}

export interface MappedVariantRow {
  mappingId: string;
  listingId: string;
  listingVariantId: string;
  externalVariantId: string;
  externalItemId: string;
  shopCode: string;
  relationType: string;
  componentQuantity: MappingNumber;
  versionNo: MappingNumber;
}

export interface SkuUsage {
  internalSkuId: string;
  variantCount: number;
  variants: MappedVariantRow[];
  note: string;
}

// ================================================================ 筛选

export interface MappingFilters {
  relationType: string;
  mappingStatus: string;
  status: string;
  internalSkuId: string;
  shopCode: string;
  search: string;
  sort: string;
  page: number;
  size: number;
}

export const emptyFilters: MappingFilters = {
  relationType: '',
  mappingStatus: '',
  status: '',
  internalSkuId: '',
  shopCode: '',
  search: '',
  sort: 'RECENT',
  page: 0,
  size: 50,
};

/**
 * 翻译成 query string。
 *
 * <p>空值一律**不下发**（而不是下发空串）：后端把 `?status=` 读成「显式筛选空状态」，
 * 会得到一个空列表，而用户以为自己没在筛选。
 */
export function buildQuery(filters: MappingFilters): string {
  const params = new URLSearchParams();
  const put = (key: string, value: string | undefined | null) => {
    const trimmed = (value ?? '').trim();
    if (trimmed) params.set(key, trimmed);
  };
  put('relationType', filters.relationType);
  put('mappingStatus', filters.mappingStatus);
  put('status', filters.status);
  put('internalSkuId', filters.internalSkuId);
  put('shopCode', filters.shopCode);
  put('search', filters.search);
  put('sort', filters.sort || 'RECENT');
  params.set('page', String(Math.max(0, filters.page)));
  params.set('size', String(Math.max(1, filters.size)));
  return params.toString();
}

/** 已生效的筛选条数（用于「清空筛选」按钮的角标）。 */
export function activeFilterCount(filters: MappingFilters): number {
  let n = 0;
  if (filters.relationType.trim()) n += 1;
  if (filters.mappingStatus.trim()) n += 1;
  if (filters.status.trim()) n += 1;
  if (filters.internalSkuId.trim()) n += 1;
  if (filters.shopCode.trim()) n += 1;
  if (filters.search.trim()) n += 1;
  return n;
}

/** 清空筛选（保留排序与分页大小 —— 那不是「筛选条件」）。 */
export function resetFilters(filters: MappingFilters): MappingFilters {
  return { ...emptyFilters, sort: filters.sort, size: filters.size };
}

/** 🔴 换筛选条件必须回到第 0 页：停在第 3 页看新的筛选结果永远是空的。 */
export function applyFilterChange(
  filters: MappingFilters,
  patch: Partial<MappingFilters>,
): MappingFilters {
  return { ...filters, ...patch, page: 0 };
}

// ================================================================ 文案

/** 🔴 映射基数 → 中文。 */
export function relationLabel(relationType: string): string {
  const map: Record<string, string> = {
    ONE_TO_ONE: '一对一',
    ONE_TO_MANY: '一对多（套装）',
    MANY_TO_ONE: '多对一',
  };
  return map[relationType] ?? relationType;
}

/** 🔴 映射状态 → 中文。这是**单条**映射指向什么，不是 4.1.1.1 的列表级聚合。 */
export function mappingStatusLabel(mappingStatus: string): string {
  const map: Record<string, string> = {
    MAPPED: '已映射',
    BUNDLE: '套装映射',
    UNMAPPED: '未映射',
  };
  return map[mappingStatus] ?? mappingStatus;
}

/** 映射存续状态 → 中文。 */
export function mappingStateLabel(status: string): string {
  const map: Record<string, string> = {
    ACTIVE: '生效中',
    REMOVED: '已解除',
  };
  return map[status] ?? status;
}

/** 匹配规则 → 中文。 */
export function ruleLabel(rule: string): string {
  const map: Record<string, string> = {
    SKU_CODE: 'SKU 编码匹配',
    BARCODE: '条码匹配',
    TITLE_SIMILARITY: '标题相似度推荐',
  };
  return map[rule] ?? rule;
}

/** 候选裁决 → 中文。 */
export function decisionLabel(decision: string): string {
  const map: Record<string, string> = {
    PENDING: '待确认',
    CONFIRMED: '已确认',
    REJECTED: '已否决',
  };
  return map[decision] ?? decision;
}

/** 排序 → 中文。 */
export function sortLabel(sort: string): string {
  const map: Record<string, string> = {
    RECENT: '最近更新',
    VERSION: '版本号从新到旧',
    SKEW: '按基数与更新时间',
  };
  return map[sort] ?? sort;
}

/** 未接入的维度清单（用于页面顶部的「本模块未接入什么」明账）。 */
export function unavailableDimensions(
  capabilities: MappingCapabilities | null,
): MappingCapabilityDimension[] {
  if (!capabilities) return [];
  return capabilities.dimensions.filter((d) => !d.available);
}

/**
 * 🔴 候选是不是「还在等人签字」。
 *
 * <p>界面上只有待确认的候选才给「确认 / 否决」两个动作；已裁决的候选只读。
 * 这不是 UX 偏好 —— 重复裁决会被服务端拒（409），按钮给错了就是让运营撞错误。
 */
export function isPending(candidate: MappingCandidate): boolean {
  return candidate.decision === 'PENDING';
}

/**
 * 候选的分数怎么展示。
 *
 * <p>🔴 标题相似度的分数是 PostgreSQL `pg_trgm similarity()` 的真算值（0~1）；
 * SKU 编码 / 条码是精确匹配，恒为 1。这里**不把分数换算成百分比**再四舍五入 ——
 * 那会把「当时算出来的那个数」变成另一个数，复核时对不上账。
 */
export function scoreText(candidate: MappingCandidate): string {
  if (candidate.rule === 'SKU_CODE' || candidate.rule === 'BARCODE') return '精确命中';
  return candidate.score.toFixed(4);
}

// ================================================================ 汇总

/**
 * 分页摘要。
 *
 * <p>🔴 用 `total` 而不是 `items.length` 说「共 N 条」：后者会把「当前页 50 条」说成「共 50 条」。
 */
export function pageSummary(page: MappingPage | null): string {
  if (!page) return '';
  if (page.total === 0) return '共 0 条';
  const from = page.page * page.size + 1;
  const to = Math.min((page.page + 1) * page.size, page.total);
  return `共 ${page.total} 条，当前显示第 ${from}-${to} 条`;
}

export function hasNextPage(page: MappingPage | null): boolean {
  if (!page) return false;
  return (page.page + 1) * page.size < page.total;
}

export function hasPrevPage(page: MappingPage | null): boolean {
  if (!page) return false;
  return page.page > 0;
}

/** 未映射清单的摘要（口径同样用 total，不用本页条数）。 */
export function unmappedSummary(page: UnmappedPage | null): string {
  if (!page) return '';
  if (page.total === 0) return '没有未映射的平台变体';
  return `共 ${page.total} 个未映射的平台变体`;
}

// ================================================================ API

export function fetchCapabilities(): Promise<MappingCapabilities> {
  return api<MappingCapabilities>('channel/listing-mappings/capabilities');
}

export function fetchFilterOptions(): Promise<MappingFilterOptions> {
  return api<MappingFilterOptions>('channel/listing-mappings/filter-options');
}

export function fetchMappings(filters: MappingFilters): Promise<MappingPage> {
  return api<MappingPage>(`channel/listing-mappings?${buildQuery(filters)}`);
}

export function fetchMapping(id: string): Promise<MappingDetail> {
  return api<MappingDetail>(`channel/listing-mappings/${id}`);
}

export function fetchUnmapped(filters: {
  shopCode?: string;
  search?: string;
  page?: number;
  size?: number;
}): Promise<UnmappedPage> {
  const params = new URLSearchParams();
  if (filters.shopCode?.trim()) params.set('shopCode', filters.shopCode.trim());
  if (filters.search?.trim()) params.set('search', filters.search.trim());
  params.set('page', String(Math.max(0, filters.page ?? 0)));
  params.set('size', String(Math.max(1, filters.size ?? 50)));
  return api<UnmappedPage>(`channel/listing-mappings/unmapped?${params.toString()}`);
}

export function fetchCandidates(filters: {
  batchTaskId?: string;
  listingVariantId?: string;
  decision?: string;
  page?: number;
  size?: number;
}): Promise<CandidatePage> {
  const params = new URLSearchParams();
  if (filters.batchTaskId?.trim()) params.set('batchTaskId', filters.batchTaskId.trim());
  if (filters.listingVariantId?.trim())
    params.set('listingVariantId', filters.listingVariantId.trim());
  if (filters.decision?.trim()) params.set('decision', filters.decision.trim());
  params.set('page', String(Math.max(0, filters.page ?? 0)));
  params.set('size', String(Math.max(1, filters.size ?? 50)));
  return api<CandidatePage>(`channel/listing-mappings/candidates?${params.toString()}`);
}

export interface SaveMappingInput {
  id?: string | null;
  listingVariantId: string;
  relationType: string;
  internalSkuId: string;
  componentQuantity?: number | null;
  changeReason: string;
  effectiveFrom?: string | null;
  expectedVersionNo?: number | null;
}

export function saveMapping(input: SaveMappingInput): Promise<MappingDetail> {
  return api<MappingDetail>('channel/listing-mappings', 'POST', input);
}

export function removeMapping(id: string, changeReason: string): Promise<MappingDetail> {
  return api<MappingDetail>(`channel/listing-mappings/${id}`, 'DELETE', { changeReason });
}

export interface BatchCandidateInput {
  rule: string;
  listingIds: string[];
  minSimilarity?: number | null;
  topPerListing?: number | null;
}

export function runBatchCandidates(input: BatchCandidateInput): Promise<BatchCandidateResult> {
  return api<BatchCandidateResult>('channel/listing-mappings/batch-candidates', 'POST', input);
}

export function decideCandidate(
  id: string,
  decision: string,
  reason?: string,
): Promise<CandidateDecisionResult> {
  return api<CandidateDecisionResult>(
    `channel/listing-mappings/candidates/${id}/decide`,
    'POST',
    { decision, reason: reason ?? '' },
  );
}

export function resolveVariants(
  listingVariantIds: string[],
  at?: string | null,
): Promise<MappingResolveResult[]> {
  return api<MappingResolveResult[]>('channel/listing-mappings/resolve', 'POST', {
    listingVariantIds,
    at: at ?? null,
  });
}
