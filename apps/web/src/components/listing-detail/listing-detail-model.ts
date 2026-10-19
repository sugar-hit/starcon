import { api } from '../inventory/api';

/**
 * 4.1.1.2 Listing 详情 前端模型。
 *
 * 蓝图：「单个平台商品的全景：平台原始信息（ASIN/item_id、标题、状态、评分评论数）、内部映射、内容对照（平台当前内容 vs 内部草稿内容）、价格库存差异、发布历史、平台操作记录。输入：`listing_id`；聚合平台快照与内部数据；用户权限。输出：左右对照视图 + 编辑入口 + 差异高亮 + 回写按钮。关系：上游 = 4.1.1.1；下游 = 4.2.1.1 编辑、4.3.2.1 回写。**差异对照是本模块的核心价值**：让运营一眼看出「平台上卖的价格/库存和我系统里设的不一样」。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **「缺一侧」绝不显示成「一致」**。`diffTone()` / `diffLabel()` 把 `UNAVAILABLE` 与 `SAME` 分成两种
 *    呈现。有一侧没接入时，「一致」和「不一致」都答不出来 —— 显示成 SAME 会让运营以为
 *    「平台上卖 0 块、系统里也是 0，一致」。
 * 2. **不显示假价格**。本模型里**没有 price / salePrice 字段**，也没有「先返回 0，以后再接」的桩。
 *    `priceRow()` 只做一件事：把服务端给的 `available=false` + 归属 + 影响原样透出。
 * 3. **未接入的行照样出现在表里**。`visibleDiffRows()` 不过滤掉 `UNAVAILABLE` 行 —— 隐藏未接入的行等于隐藏缺口。
 * 4. **差异判定不在前端做**。前端只负责把服务端算好的 `status` 翻译成文案与配色；比较规则在 jOOQ 显式 SQL 里，
 *    在浏览器里再比一次会出现「服务端说 SAME、界面说 DIFFERENT」。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** —— 服务端 `BigDecimal` 的事在前端算一遍
 * 就会引入二进制浮点误差。价格 / 可售量的比对结果一律读服务端给的字符串。
 */

// ================================================================ 类型

/** 🔴 null = 服务端没给这个值（平台未评分 / 未登记回写目标），与 `0` 含义相反。 */
export type ListingNumber = number | null;

export interface ListingDetailCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface ListingDetailCapabilities {
  dimensions: ListingDetailCapabilityDimension[];
  priceAvailable: boolean;
  priceOwnerModule: string;
  priceNote: string;
  internalStockAvailable: boolean;
  internalStockOwnerModule: string;
  internalStockNote: string;
  contentDraftAvailable: boolean;
  contentDraftOwnerModule: string;
  contentDraftNote: string;
  salesAvailable: boolean;
  salesOwnerModule: string;
  salesNote: string;
  contentFields: string[];
  diffGroups: string[];
  editSupported: boolean;
  editOwnerModule: string;
  editReason: string;
  writebackSupported: boolean;
  writebackOwnerModule: string;
  writebackReason: string;
  platformOperationNote: string;
  upstreamNote: string;
}

/** 职责句里的「平台原始信息」。🔴 本类型**刻意没有** price / salePrice / internalStock / sales 字段。 */
export interface ListingPlatformProfile {
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
  launchedAt: string | null;
  lastSyncedAt: string | null;
  updatedAt: string;
}

export interface ListingVariantMapping {
  id: string;
  externalVariantId: string;
  mappingStatus: 'UNMAPPED' | 'MAPPED' | 'BUNDLE';
  internalSkuId: string | null;
  componentQuantity: ListingNumber;
  attributes: string;
  updatedAt: string;
}

export interface ListingContentField {
  field: string;
  label: string;
  platformLabel: string;
  /** 平台侧取值原文；平台没给该字段时为 null（≠ 空字符串） */
  platformValue: string | null;
  platformKnown: boolean;
  internalLabel: string;
  /** 🔴 内部草稿取值；4.2.1.1 未交付 ⇒ 恒为 null */
  internalValue: string | null;
  /** 🔴 内部侧是否有值；4.2.1.1 未交付 ⇒ 恒为 false */
  internalKnown: boolean;
  available: boolean;
  ownerModule: string;
  impact: string;
}

export interface ListingContentSnapshotView {
  id: string;
  language: string;
  source: 'SYNC' | 'COLLECTION' | 'MANUAL';
  current: boolean;
  capturedAt: string;
  historyCount: number;
}

/** 🔴 差异状态。`UNAVAILABLE` 与 `SAME` 是两件不同的事，见文件头第 1 条。 */
export type DiffStatus =
  | 'DIFFERENT'
  | 'SAME'
  | 'PLATFORM_ONLY'
  | 'INTERNAL_ONLY'
  | 'UNAVAILABLE'
  | 'UNKNOWN';

export type DiffGroup = 'CONTENT' | 'PRICE_STOCK' | 'PLATFORM_SIDE' | 'REGISTRY' | 'MAPPING';

export interface ListingDiffRow {
  group: DiffGroup;
  dimension: string;
  label: string;
  platformLabel: string;
  platformValue: string | null;
  platformKnown: boolean;
  internalLabel: string;
  internalValue: string | null;
  internalKnown: boolean;
  status: DiffStatus;
  available: boolean;
  ownerModule: string;
  impact: string;
  note: string;
}

export interface ListingDiffReport {
  listingId: string;
  rows: ListingDiffRow[];
  differentCount: number;
  sameCount: number;
  unavailableCount: number;
  note: string;
}

export interface ListingPublishRecordView {
  id: string;
  snapshotId: string | null;
  action: 'PUBLISH' | 'CONTENT_WRITEBACK';
  outcome: 'SUCCEEDED' | 'FAILED' | 'REJECTED';
  platformRequestId: string;
  reason: string;
  actorId: string | null;
  occurredAt: string;
}

export interface ListingPlatformOperationView {
  id: string;
  operation: string;
  status: 'PENDING' | 'SENDING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN';
  verification: string;
  attempts: number;
  platformRequestId: string;
  lastError: string;
  payload: string;
  occurredAt: string;
}

/**
 * 🔴 回写登记簿状态 —— **不是售价**。
 *
 * `currentPrice` 是 2.5.2.1 登记的「平台上当前在售的值」的快照。卷 05 定价未交付 ⇒ 它不是定价结论。
 * `registered=false` 时两列都是 `null`：**未登记不是 0**，把 0 显示成「平台上卖 0 块」是本模块最典型的假事实。
 */
export interface ListingWritebackRegistryView {
  registered: boolean;
  targetId: string | null;
  currentStock: ListingNumber;
  currentPrice: ListingNumber;
  currency: string | null;
  status: string;
  blockedReason: string;
  updatedAt: string | null;
  source: string;
  limitation: string;
}

export interface ListingStatusChange {
  fromStatus: string | null;
  toStatus: string;
  source: 'MANUAL' | 'PLATFORM';
  occurredAt: string;
}

export interface ListingDetail {
  profile: ListingPlatformProfile;
  mappingStatus: 'UNMAPPED' | 'PARTIAL' | 'MAPPED';
  variantCount: number;
  mappedVariantCount: number;
  variants: ListingVariantMapping[];
  contentComparison: ListingContentField[];
  contentSnapshot: ListingContentSnapshotView | null;
  diff: ListingDiffReport;
  publishHistory: ListingPublishRecordView[];
  platformOperations: ListingPlatformOperationView[];
  statusChanges: ListingStatusChange[];
  writebackRegistry: ListingWritebackRegistryView;
  differentCount: number;
  unavailableDimensions: string[];
}

export interface ListingContentComparison {
  listingId: string;
  snapshot: ListingContentSnapshotView | null;
  fields: ListingContentField[];
  internalDraftAvailable: boolean;
  internalDraftOwnerModule: string;
  note: string;
}

export interface SaveContentSnapshotInput {
  language: string;
  title?: string | null;
  bulletPoints?: string | null;
  description?: string | null;
  images?: string | null;
  videoUrl?: string | null;
  aPlusModules?: string | null;
  categoryAttributes?: string | null;
  complianceStatements?: string | null;
  source?: string | null;
  capturedAt?: string | null;
  current?: boolean;
}

// ================================================================ 状态文案

/** 平台商品状态 → 中文（与 4.1.1.1 列表同一套文案）。 */
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

/** 🔴 缺货标记 → 中文。UNKNOWN 是「平台没回报」，不是「有货」。 */
export function stockLabel(state: string): string {
  const map: Record<string, string> = {
    IN_STOCK: '有货',
    OUT_OF_STOCK: '平台缺货',
    UNKNOWN: '平台未回报',
  };
  return map[state] ?? state;
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

export function reviewLabel(reviewStatus: string): string {
  const map: Record<string, string> = {
    NOT_SUBMITTED: '未提交审核',
    PENDING: '审核中',
    APPROVED: '已通过',
    REJECTED: '已驳回',
  };
  return map[reviewStatus] ?? reviewStatus;
}

export function variantMappingLabel(mappingStatus: string): string {
  const map: Record<string, string> = {
    UNMAPPED: '未映射',
    MAPPED: '已映射',
    BUNDLE: '套装',
  };
  return map[mappingStatus] ?? mappingStatus;
}

export function contentSourceLabel(source: string): string {
  const map: Record<string, string> = {
    SYNC: '02 同步',
    COLLECTION: '4.4.1 链接采集',
    MANUAL: '人工登记',
  };
  return map[source] ?? source;
}

export function publishActionLabel(action: string): string {
  const map: Record<string, string> = {
    PUBLISH: '4.3.1.1 发布',
    CONTENT_WRITEBACK: '4.3.2.1 内容回写',
  };
  return map[action] ?? action;
}

export function publishOutcomeLabel(outcome: string): string {
  const map: Record<string, string> = {
    SUCCEEDED: '成功',
    FAILED: '平台失败',
    REJECTED: '生成前被拒',
  };
  return map[outcome] ?? outcome;
}

export function operationLabel(operation: string): string {
  const map: Record<string, string> = {
    PUBLISH: '发布 / 下架',
    UPDATE_PRICE: '改价',
    UPDATE_STOCK: '改库存',
    SHIP_UPDATE: '回传物流',
    CANCEL_ORDER: '取消订单',
  };
  return map[operation] ?? operation;
}

export function operationStatusLabel(status: string): string {
  const map: Record<string, string> = {
    PENDING: '待派发',
    SENDING: '派发中',
    SUCCEEDED: '成功',
    FAILED: '失败',
    UNKNOWN: '结果未知',
  };
  return map[status] ?? status;
}

/** 🔴 UNKNOWN 的语义是「不知道平台上到底成没成」，不是「没成」。文案不许压成「失败」。 */
export function operationStatusHint(status: string): string {
  return status === 'UNKNOWN'
    ? '平台没给回执：先查证再决定重发（2.5.1.2）'
    : '';
}

// ================================================================ 差异呈现

/** 分组 → 中文 + 副标题。 */
export function groupLabel(group: DiffGroup): string {
  const map: Record<DiffGroup, string> = {
    CONTENT: '内容对照',
    PRICE_STOCK: '价格 / 库存',
    PLATFORM_SIDE: '平台侧自洽',
    REGISTRY: '系统登记 vs 平台回报',
    MAPPING: '内部映射',
  };
  return map[group] ?? group;
}

/**
 * 🔴 差异状态 → 中文。
 *
 * <p>`UNAVAILABLE` 的文案是「未接入」而**不是**「一致」：有一侧根本没接入时，一致与不一致都答不出来。
 */
export function diffLabel(status: DiffStatus): string {
  const map: Record<DiffStatus, string> = {
    DIFFERENT: '不同',
    SAME: '相同',
    PLATFORM_ONLY: '只有平台侧有值',
    INTERNAL_ONLY: '只有内部侧有值',
    UNAVAILABLE: '未接入',
    UNKNOWN: '两侧都没值',
  };
  return map[status] ?? status;
}

/** 🔴 徽章配色：`UNAVAILABLE` 用 warning（中性提示），`SAME` 用 success（真的是「一样」）。 */
export function diffTone(
  status: DiffStatus,
): 'danger' | 'success' | 'warning' | 'info' | 'neutral' {
  switch (status) {
    case 'DIFFERENT':
      return 'danger';
    case 'SAME':
      return 'success';
    case 'UNAVAILABLE':
      return 'warning';
    case 'PLATFORM_ONLY':
    case 'INTERNAL_ONLY':
      return 'info';
    default:
      return 'neutral';
  }
}

/**
 * 🔴 一格显示什么。
 *
 * <p>没有值时返回「未接入」而不是 `--` / `0` / 空串：`--` 会被读成「平台上有、只是没显示」，
 * `0` 会被读成「平台上卖 0 块」，两者都是另一种假事实。
 */
export function cellText(value: string | null, known: boolean): string {
  if (known && value !== null && value !== '') return value;
  return '未接入';
}

/** 一行的「可否比对」摘要，直接用服务端的 impact / note，不在前端重新组织口径。 */
export function rowExplain(row: ListingDiffRow): string {
  if (row.available) return row.note;
  return row.impact || row.note;
}

/**
 * 差异高亮摘要。
 *
 * <p>⚠️ 用服务端给的 `differentCount` / `unavailableCount`，**不用**在前端数一遍行 ——
 * 前端数会与过滤、渲染口径漂移，出现「角标说 2 条、表里高亮 3 行」。
 */
export function diffSummary(report: ListingDiffReport | null): string {
  if (!report) return '';
  const parts = [`可比对且不同 ${report.differentCount} 条`];
  parts.push(`相同 ${report.sameCount} 条`);
  if (report.unavailableCount > 0) {
    parts.push(`因一侧未接入而无法比对 ${report.unavailableCount} 条`);
  }
  return parts.join(' · ');
}

/** 未接入维度清单（顶部明账用）。 */
export function unavailableDimensions(
  capabilities: ListingDetailCapabilities | null,
): ListingDetailCapabilityDimension[] {
  if (!capabilities) return [];
  return capabilities.dimensions.filter((d) => !d.available);
}

// ================================================================ 入口可用性

export interface ActionAvailability {
  supported: boolean;
  ownerModule: string | null;
  reason: string | null;
}

/**
 * 🔴 编辑入口与回写按钮的可用性。
 *
 * <p>本批次两者都恒为 `supported=false`（4.2.1.1 / 4.3.2.1 尚未交付），按钮必须**禁用并说明归属**，
 * 而不是点了给一个「成功但什么都没做」的提示。
 */
export function editAvailability(
  capabilities: ListingDetailCapabilities | null,
): ActionAvailability {
  if (!capabilities) {
    return {
      supported: false,
      ownerModule: '4.2.1.1 刊登编辑器',
      reason: '能力声明未加载，编辑入口暂不可用。',
    };
  }
  return {
    supported: capabilities.editSupported,
    ownerModule: capabilities.editOwnerModule || null,
    reason: capabilities.editSupported ? null : capabilities.editReason,
  };
}

export function writebackAvailability(
  capabilities: ListingDetailCapabilities | null,
): ActionAvailability {
  if (!capabilities) {
    return {
      supported: false,
      ownerModule: '4.3.2.1 内容回写',
      reason: '能力声明未加载，内容回写暂不可用。',
    };
  }
  return {
    supported: capabilities.writebackSupported,
    ownerModule: capabilities.writebackOwnerModule || null,
    reason: capabilities.writebackSupported ? null : capabilities.writebackReason,
  };
}

// ================================================================ 展示

/** 平台的量 / 分数。🔴 null 显示「平台未回报」，**不回 0**。 */
export function quantityText(value: ListingNumber, emptyLabel = '平台未回报'): string {
  return value === null || value === undefined ? emptyLabel : String(value);
}

/** 评分：null = 平台没评分，与 0 分（真的零分）不同。 */
export function ratingText(rating: ListingNumber, count: ListingNumber): string {
  if (rating === null || rating === undefined) return '平台未评分';
  const suffix =
    count === null || count === undefined ? '' : `（${count} 条评论）`;
  return `${rating}${suffix}`;
}

/** 时间戳 → 本地可读文本；null → 「—」。 */
export function timeText(value: string | null | undefined): string {
  if (!value) return '—';
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
}

/** 发布记录的一行摘要（成功与失败都用它，两种结局都有明确措辞）。 */
export function publishSummary(record: ListingPublishRecordView): string {
  if (record.outcome === 'SUCCEEDED') {
    return `${publishActionLabel(record.action)}成功${
      record.platformRequestId ? `（平台请求 ${record.platformRequestId}）` : ''
    }`;
  }
  return `${publishActionLabel(record.action)}${publishOutcomeLabel(record.outcome)}：${
    record.reason || '未写明原因'
  }`;
}

// ================================================================ API

export function fetchListingDetailCapabilities(): Promise<ListingDetailCapabilities> {
  return api<ListingDetailCapabilities>('channel/listing-details/capabilities');
}

export function fetchListingDetail(id: string): Promise<ListingDetail> {
  return api<ListingDetail>(`channel/listing-details/${id}`);
}

export function fetchListingDetailDiff(id: string): Promise<ListingDiffReport> {
  return api<ListingDiffReport>(`channel/listing-details/${id}/diff`);
}

export function fetchListingDetailContent(id: string): Promise<ListingContentComparison> {
  return api<ListingContentComparison>(`channel/listing-details/${id}/content`);
}

export function fetchPublishHistory(id: string): Promise<ListingPublishRecordView[]> {
  return api<ListingPublishRecordView[]>(`channel/listing-details/${id}/publish-history`);
}

export function fetchPlatformOperations(id: string): Promise<ListingPlatformOperationView[]> {
  return api<ListingPlatformOperationView[]>(
    `channel/listing-details/${id}/platform-operations`,
  );
}

export function saveContentSnapshot(
  id: string,
  payload: SaveContentSnapshotInput,
): Promise<{ id: string; historyCount: number }> {
  return api(`channel/listing-details/${id}/content-snapshots`, 'POST', payload);
}
