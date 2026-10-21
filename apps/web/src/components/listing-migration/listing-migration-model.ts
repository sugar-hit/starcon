import { api } from '../inventory/api';
import type {
  ListingMigrationBatchRow,
  ListingMigrationCapabilities,
  ListingMigrationContentDiffRow,
  ListingMigrationDuplicateRow,
  ListingMigrationItemRow,
  ListingMigrationMigratedRow,
  ListingMigrationPreview,
  MigrationCategoryOption,
  MigrationDuplicatePolicy,
  MigrationExecuteRequest,
  MigrationFilterExplanation,
  MigrationFilterOptions,
  MigrationFilterRequest,
  MigrationPreviewRequest,
  MigrationTarget,
  ShopView,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 4.4.2.2 批量搬迁 前端模型。
 *
 * 蓝图：「批量把一批 listing 从一个店铺搬到多个店铺，支持按规则筛选（类目、销量区间、价格区间），并对重复铺货风险给出提示。输入：
 * 筛选规则；目标店铺集合；重复度控制参数。输出：批量搬迁任务 + 结果明细；重复度风险提示。关系：上游 = 4.4.2.1；下游 = 4.3.1.2。
 * <b>搬迁后必须做标题差异化</b>，否则平台可能判定重复铺货并抑制 listing。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「不知道」是一个状态，不是一个 0**。销量维度尚未接入（归 4.5.1.1 平台商品监控），
 *    价格取的是平台侧在售价快照（不是 05 定价的权威售价）—— 页面把这两件事写在筛选区旁边，
 *    不让"筛不出东西"看起来像"没有商品"。
 * 2. **重复度风险与已搬迁分开显示**。风险高 ≠ 已经搬过；两者是两种事实、两种处置，
 *    页面分成两块，不合并成一个结论。
 * 3. **标题差异化是流程的一部分**。页面展示的是**差异化之后**的标题，并列出每一步用了哪条规则；
 *    源标题只作为对照出现。页面不提供"原样照搬"的开关 —— 后端也没有这个入口。
 * 4. **草稿态一眼可见**。目标草稿是"草稿 · 尚未过平台审核"，不是"已发布"。
 * 5. **幂等**。重复点"确认搬迁"只会得到同一个批次（幂等键由后端按请求内容派生）。
 */

/** 未知值策略：数值维度取不到值时怎么办（与后端 `ListingMigrationModels.UNKNOWN_POLICIES` 一致）。 */
export const UNKNOWN_POLICY_OPTIONS = [
  { value: 'EXCLUDE', label: '排除未知（只保留确认落在区间内的）' },
  { value: 'INCLUDE', label: '保留未知（取不到值的也留下）' },
  { value: 'ONLY', label: '只要未知（把取不到值的挑出来）' },
] as const;

/** 排序维度（与后端 `ListingMigrationModels.SORT_FIELDS` 一致）。 */
export const SORT_OPTIONS = [
  { value: 'UPDATED_AT', label: '最近更新' },
  { value: 'CREATED_AT', label: '登记时间' },
  { value: 'TITLE', label: '标题' },
  { value: 'PRICE', label: '价格（未知排最后）' },
  { value: 'SALES', label: '销量（来源未接入）' },
] as const;

/** 重复度等级（UNKNOWN = 无法判定，**不是 0 分**）。 */
export const RISK_LEVEL_OPTIONS = [
  { value: 'LOW', label: '低' },
  { value: 'MEDIUM', label: '中' },
  { value: 'HIGH', label: '高' },
  { value: 'CRITICAL', label: '极高' },
  { value: 'UNKNOWN', label: '无法判定' },
] as const;

/** 重复度控制参数里「风险达到这一级就跳过搬迁」的取值。 */
export const MAX_RISK_OPTIONS = [
  { value: 'NONE', label: '不按风险跳过（只提示）' },
  { value: 'LOW', label: '低及以上就跳过' },
  { value: 'MEDIUM', label: '中及以上就跳过' },
  { value: 'HIGH', label: '高及以上就跳过' },
  { value: 'CRITICAL', label: '极高就跳过' },
] as const;

/** 风险 UNKNOWN（目标店铺没有可比商品）时的处置。 */
export const UNKNOWN_RISK_OPTIONS = [
  { value: 'PROCEED', label: '照常搬迁' },
  { value: 'SKIP', label: '跳过不搬' },
] as const;

export type UnknownPolicy = (typeof UNKNOWN_POLICY_OPTIONS)[number]['value'];
export type MaxRiskLevel = (typeof MAX_RISK_OPTIONS)[number]['value'];
export type UnknownRiskAction = (typeof UNKNOWN_RISK_OPTIONS)[number]['value'];

/** 页面上的筛选与参数草稿。 */
export interface MigrationDraft {
  sourceShopId: string;
  categoryPaths: string[];
  priceMin: string;
  priceMax: string;
  salesMin: string;
  salesMax: string;
  unknownPolicy: UnknownPolicy;
  sortBy: string;
  sortOrder: 'ASC' | 'DESC';
  page: number;
  size: number;
  targetShopIds: string[];
  maxRiskLevel: MaxRiskLevel;
  onUnknown: UnknownRiskAction;
  titleTemplate: string;
  note: string;
}

export type {
  ListingMigrationBatchRow,
  ListingMigrationCapabilities,
  ListingMigrationContentDiffRow,
  ListingMigrationDuplicateRow,
  ListingMigrationItemRow,
  ListingMigrationMigratedRow,
  ListingMigrationPreview,
  MigrationCategoryOption,
  MigrationDuplicatePolicy,
  MigrationExecuteRequest,
  MigrationFilterExplanation,
  MigrationFilterOptions,
  MigrationFilterRequest,
  MigrationPreviewRequest,
  MigrationTarget,
  ShopView,
};

export const EMPTY_DRAFT: MigrationDraft = {
  sourceShopId: '',
  categoryPaths: [],
  priceMin: '',
  priceMax: '',
  salesMin: '',
  salesMax: '',
  unknownPolicy: 'EXCLUDE',
  sortBy: 'UPDATED_AT',
  sortOrder: 'DESC',
  page: 0,
  size: 50,
  targetShopIds: [],
  maxRiskLevel: 'HIGH',
  onUnknown: 'PROCEED',
  titleTemplate: '',
  note: '',
};

// ================================================================ 纯函数

/**
 * 🔴 价格 / 销量输入 → 数字。
 *
 * 只接受**十进制**字面量（不接受指数写法、不接受负数、不接受超过 4 位小数）：
 * 区间筛选的权威类型在服务端是 NUMERIC，这里先把明显不合法的东西挡在提交之前。
 *
 * @returns 合法则返回数字；留空返回 null（= 该维度不参与过滤）；不合法返回 undefined（= 输入非法）
 */
export function parseDecimal(text: string): number | null | undefined {
  const trimmed = text.trim();
  if (trimmed === '') return null;
  if (!/^\d+(\.\d{1,4})?$/.test(trimmed)) return undefined;
  const value = Number(trimmed);
  return Number.isFinite(value) ? value : undefined;
}

/** 区间下界 / 上界是否成对且不倒置。 */
export function rangeIsValid(min: string, max: string): boolean {
  const low = parseDecimal(min);
  const high = parseDecimal(max);
  if (low === undefined || high === undefined) return false;
  if (low === null && high === null) return true;
  if (low === null || high === null) return true;
  return low <= high;
}

/**
 * 草稿 → 筛选规则。
 *
 * @returns 合法则返回请求体；源店铺没选 / 区间非法时返回 null（页面据此禁用提交）
 */
export function buildFilterRequest(draft: MigrationDraft): MigrationFilterRequest | null {
  if (!draft.sourceShopId) return null;
  if (!rangeIsValid(draft.priceMin, draft.priceMax)) return null;
  if (!rangeIsValid(draft.salesMin, draft.salesMax)) return null;
  const priceMin = parseDecimal(draft.priceMin);
  const priceMax = parseDecimal(draft.priceMax);
  const salesMin = parseDecimal(draft.salesMin);
  const salesMax = parseDecimal(draft.salesMax);
  if (priceMin === undefined || priceMax === undefined) return null;
  if (salesMin === undefined || salesMax === undefined) return null;
  return {
    sourceShopId: draft.sourceShopId,
    categoryPaths: draft.categoryPaths,
    platformCategoryIds: [],
    priceMin: priceMin ?? null,
    priceMax: priceMax ?? null,
    salesMin: salesMin ?? null,
    salesMax: salesMax ?? null,
    unknownPolicy: draft.unknownPolicy,
    sortBy: draft.sortBy,
    sortOrder: draft.sortOrder,
    page: draft.page,
    size: draft.size,
  };
}

/** 草稿 → 预览请求（预览不写数据）。 */
export function buildPreviewRequest(draft: MigrationDraft): MigrationPreviewRequest | null {
  const filter = buildFilterRequest(draft);
  if (!filter) return null;
  if (draft.targetShopIds.length === 0) return null;
  return { filter, targets: draft.targetShopIds.map((id) => ({ shopId: id, marketplaceId: null })) };
}

/** 草稿 → 搬迁请求。 */
export function buildExecuteRequest(draft: MigrationDraft): MigrationExecuteRequest | null {
  const filter = buildFilterRequest(draft);
  if (!filter) return null;
  if (draft.targetShopIds.length === 0) return null;
  const policy: MigrationDuplicatePolicy = {
    maxRiskLevel: draft.maxRiskLevel,
    onUnknown: draft.onUnknown,
    maxComparables: 200,
    titleTemplate: draft.titleTemplate.trim() === '' ? null : draft.titleTemplate.trim(),
  };
  return {
    filter,
    targets: draft.targetShopIds.map((id) => ({ shopId: id, marketplaceId: null })),
    duplicatePolicy: policy,
    idempotencyKey: null,
    note: draft.note,
  };
}

/** 目标店铺候选：**排除源店铺**（搬回原店不构成搬迁）。 */
export function targetShopOptions(shops: ShopView[], sourceShopId: string): ShopView[] {
  return shops.filter((s) => s.id !== sourceShopId && s.status === 'ACTIVE');
}

/** 风险等级文案。UNKNOWN 是"无法判定"，不是"低"。 */
export function riskLevelLabel(level: string): string {
  switch (level) {
    case 'LOW':
      return '低';
    case 'MEDIUM':
      return '中';
    case 'HIGH':
      return '高';
    case 'CRITICAL':
      return '极高';
    case 'UNKNOWN':
      return '无法判定（目标店铺没有可比商品）';
    default:
      return level;
  }
}

export function riskLevelTone(level: string): 'neutral' | 'info' | 'warning' | 'danger' | 'success' {
  switch (level) {
    case 'LOW':
      return 'success';
    case 'MEDIUM':
      return 'warning';
    case 'HIGH':
      return 'danger';
    case 'CRITICAL':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 重复度分值文案：UNKNOWN 时分值是 null，页面写"无法判定"，不写 0。 */
export function riskScoreLabel(score: number | null | undefined, level: string): string {
  if (score === null || score === undefined) return '无法判定（不是 0 分）';
  return `${(score * 100).toFixed(1)}% · ${riskLevelLabel(level)}`;
}

/** 搬迁结果状态文案。 */
export function itemStateLabel(state: string): string {
  switch (state) {
    case 'MIGRATED':
      return '已搬迁';
    case 'SKIPPED':
      return '已跳过';
    case 'FAILED':
      return '失败';
    default:
      return state;
  }
}

export function itemStateTone(state: string): 'neutral' | 'success' | 'warning' | 'danger' {
  switch (state) {
    case 'MIGRATED':
      return 'success';
    case 'SKIPPED':
      return 'warning';
    case 'FAILED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 批次状态文案。 */
export function batchStateLabel(state: string): string {
  switch (state) {
    case 'EXECUTED':
      return '全部搬迁';
    case 'PARTIAL':
      return '部分搬迁（有跳过或失败）';
    case 'FAILED':
      return '全部失败';
    default:
      return state;
  }
}

/** 取筛选说明里某一个维度的报告。 */
export function dimensionReport(
  explanation: MigrationFilterExplanation | null,
  dimension: string,
) {
  if (!explanation) return null;
  return explanation.dimensions.find((d) => d.dimension === dimension) ?? null;
}

/** 类目选项的显示名（类目路径优先，没有路径时退回平台类目 ID）。 */
export function categoryOptionLabel(option: MigrationCategoryOption): string {
  if (option.categoryPath && option.categoryPath.trim() !== '') return option.categoryPath;
  if (option.platformCategoryId && option.platformCategoryId.trim() !== '') return option.platformCategoryId;
  return '（未登记类目）';
}

/** 重复度分值的三个分量（不可知的那一个显示"未知"，不是 0）。 */
export function similarityLabel(value: number | null | undefined): string {
  if (value === null || value === undefined) return '未知';
  return `${(value * 100).toFixed(1)}%`;
}

/** 目标草稿徽章 —— 「尚未过平台审核」必须一眼可见。 */
export function targetDraftBadge(item: ListingMigrationItemRow): string {
  return item.targetListingId ? '草稿 · 尚未过平台审核' : '未产生目标草稿';
}

/** 差异化留痕的规则文案。 */
export function ruleLabel(ruleId: string): string {
  switch (ruleId) {
    case 'TEMPLATE':
      return '标题模板';
    case 'STORE_MARKER':
      return '店铺标识';
    case 'SITE_MARKER':
      return '站点标识';
    case 'TRUNCATE':
      return '超长截断';
    case 'COLLISION_RESOLVE':
      return '同名消歧';
    case 'BULLET_STORE_MARKER':
      return '卖点差异化';
    case 'DESCRIPTION_STORE_MARKER':
      return '描述差异化';
    default:
      return ruleId;
  }
}

// ================================================================ 取数

export async function fetchMigrationCapabilities(): Promise<ListingMigrationCapabilities> {
  return api<ListingMigrationCapabilities>('channel/listing-migrations/capabilities');
}

export async function fetchFilterOptions(sourceShopId?: string): Promise<MigrationFilterOptions> {
  const query = sourceShopId ? `?sourceShopId=${encodeURIComponent(sourceShopId)}` : '';
  return api<MigrationFilterOptions>(`channel/listing-migrations/filter-options${query}`);
}

export async function fetchShops(): Promise<ShopView[]> {
  return api<ShopView[]>('channel/shops');
}

export async function previewMigration(body: MigrationPreviewRequest): Promise<ListingMigrationPreview> {
  return api<ListingMigrationPreview>('channel/listing-migrations/preview', 'POST', body);
}

export async function executeMigration(body: MigrationExecuteRequest): Promise<ListingMigrationBatchRow> {
  return api<ListingMigrationBatchRow>('channel/listing-migrations', 'POST', body);
}

export async function fetchMigrationBatches(): Promise<ListingMigrationBatchRow[]> {
  return api<ListingMigrationBatchRow[]>('channel/listing-migrations');
}

export async function fetchMigrationItems(
  batchId: string,
): Promise<ListingMigrationItemRow[]> {
  return api<ListingMigrationItemRow[]>(`channel/listing-migrations/${batchId}/items`);
}

/** 「重复铺货风险」查询（与「已搬迁」分开：两种事实、两种处置）。 */
export async function fetchDuplicateRisks(): Promise<ListingMigrationDuplicateRow[]> {
  return api<ListingMigrationDuplicateRow[]>('channel/listing-migrations/duplicate-risks');
}

/** 「已搬迁」查询。 */
export async function fetchMigrated(): Promise<ListingMigrationMigratedRow[]> {
  return api<ListingMigrationMigratedRow[]>('channel/listing-migrations/migrated');
}

// ================================================================ 空态文案

export function emptyPreviewMessage(): string {
  return '还没有预览。选好源店铺、筛选条件与目标店铺，点「预览」看看会搬什么、风险有多高。';
}

export function emptyItemsMessage(): string {
  return '这个批次还没有结果明细。';
}

export function emptyRisksMessage(): string {
  return '还没有重复铺货风险记录。执行一次搬迁后，这里会列出每一条的风险分值与判定依据。';
}

export function emptyMigratedMessage(): string {
  return '还没有已搬迁记录。执行一次搬迁后，这里会列出哪些商品已经搬进了哪些店铺。';
}
