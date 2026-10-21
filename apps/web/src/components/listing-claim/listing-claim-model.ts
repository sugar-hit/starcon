import { api } from '../inventory/api';
import type {
  ClaimListingRequest,
  ListingClaimCapabilities,
  ListingClaimFieldDecision,
  ListingClaimFieldRow,
  ListingClaimRow,
  ListingPage,
  ListingRow,
  ShopMarketplaceView,
  ShopView,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 4.4.2.1 商品认领 前端模型。
 *
 * 蓝图：「把已在 A 店铺的 listing 认领到 B 店铺（跨店搬家），复用原商品内容与映射，生成 B 店铺的新 listing 草稿。输入：源
 * listing；目标店铺与站点；保留/重写的内容字段选择。输出：目标店铺的 listing 草稿 + 待发布任务。关系：上游 = 4.1.1.2；下游 =
 * 4.3.1.1。<b>新店铺 listing 是全新平台资源</b>，需重新过平台审核；<b>源 listing 不受影响</b>。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **源 listing 不受影响**。页面把「认领时记录的源状态」与「现在的源状态」并排显示
 *    （{@link sourceUnchanged}），两者不一致时页面直接说"源商品在认领之后被改过"——
 *    认领是一次**创建**动作，不是"搬走"动作。
 * 2. **目标是草稿态，尚未过平台审核**。目标草稿的徽章是「草稿 · 未过平台审核」
 *    （{@link draftBadge}），不是"已发布"。
 * 3. **页面不给"改价 / 改库存"入口**。{@link FIELD_OPTIONS} 里没有价格与库存字段：
 *    售价归 05 定价、内部可售量归 07 库存，源商品的评分与币种在目标店铺也不成立。
 * 4. **平台类目 ID 与站点绑定**。跨站点保留它会被服务端 422 拒绝；页面在选了跨站点目标时
 *    提前把它标成"必须重写或跳过"（{@link siteBoundFields}），不让用户点一次才知道。
 */

/** 可参与保留 / 重写的字段（与后端 `ListingClaimModels.FIELDS` 逐字一致）。 */
export const FIELD_OPTIONS: readonly {
  value: string;
  label: string;
  hint: string;
  json?: 'array' | 'object';
  siteBound?: boolean;
}[] = [
  { value: 'TITLE', label: '标题', hint: '平台商品标题' },
  { value: 'BULLETS', label: '卖点', hint: 'bullet points（JSON 数组）', json: 'array' },
  { value: 'IMAGES', label: '图片', hint: '主图与附图（JSON 数组）', json: 'array' },
  { value: 'DESCRIPTION', label: '描述', hint: '详情长文本' },
  { value: 'A_PLUS', label: 'A+', hint: 'A+ 模块（JSON 数组）', json: 'array' },
  { value: 'VIDEO_URL', label: '视频', hint: '商品视频地址' },
  { value: 'CATEGORY_PATH', label: '类目路径', hint: '平台侧类目路径原文' },
  {
    value: 'PLATFORM_CATEGORY_ID',
    label: '平台类目 ID',
    hint: '与**站点**绑定：跨站点不能沿用源值',
    siteBound: true,
  },
  {
    value: 'CATEGORY_ATTRIBUTES',
    label: '类目属性',
    hint: '与**站点**绑定：跨站点不能沿用源值',
    json: 'object',
    siteBound: true,
  },
  {
    value: 'COMPLIANCE_STATEMENTS',
    label: '合规声明',
    hint: '与**站点**绑定：跨站点不能沿用源值',
    json: 'object',
    siteBound: true,
  },
];

/** 字段决策的三个取值：保留 / 重写 / 跳过。 */
export const DECISION_OPTIONS = [
  { value: 'KEEP', label: '保留' },
  { value: 'REWRITE', label: '重写' },
  { value: 'SKIP', label: '跳过' },
] as const;

export type DecisionMode = (typeof DECISION_OPTIONS)[number]['value'];

export interface ClaimFieldDraft {
  mode: DecisionMode;
  value: string;
}

export type ClaimFieldDrafts = Record<string, ClaimFieldDraft>;

export interface ClaimDraft {
  sourceListingId: string;
  targetShopId: string;
  targetMarketplaceId: string;
  language: string;
  note: string;
  fields: ClaimFieldDrafts;
}

export type {
  ClaimListingRequest,
  ListingClaimCapabilities,
  ListingClaimFieldRow,
  ListingClaimRow,
  ListingRow,
  ShopView,
};

// ================================================================ 取数

export async function fetchClaimCapabilities(): Promise<ListingClaimCapabilities> {
  return api<ListingClaimCapabilities>('channel/listing-claims/capabilities');
}

export async function fetchClaims(
  state?: string,
  targetShopCode?: string,
): Promise<ListingClaimRow[]> {
  const query = new URLSearchParams();
  if (state) query.set('state', state);
  if (targetShopCode) query.set('targetShopCode', targetShopCode);
  const suffix = query.toString();
  return api<ListingClaimRow[]>(`channel/listing-claims${suffix ? `?${suffix}` : ''}`);
}

export async function fetchClaim(claimId: string): Promise<ListingClaimRow> {
  return api<ListingClaimRow>(`channel/listing-claims/${claimId}`);
}

export async function createClaim(body: ClaimListingRequest): Promise<ListingClaimRow> {
  return api<ListingClaimRow>('channel/listing-claims', 'POST', body);
}

/** 源 listing 候选（真分页 + 搜索，走 4.1.1.1 的既有列表端点）。 */
export async function fetchSourceCandidates(search: string): Promise<ListingRow[]> {
  const query = new URLSearchParams({ page: '0', size: '20' });
  if (search.trim()) query.set('search', search.trim());
  const page = await api<ListingPage>(`channel/listings?${query.toString()}`);
  return page.items;
}

/** 目标店铺候选（2.3.1.1 店铺列表）。 */
export async function fetchShops(): Promise<ShopView[]> {
  return api<ShopView[]>('channel/shops');
}

// ================================================================ 纯函数（可单测）

/**
 * 🔴 请求体 → 服务端载荷。
 *
 * 三件事在这里被挡住：
 *  * 标题必须有决策 —— 目标草稿不能没有标题；
 *  * 「重写」必须给出非空值 —— 否则服务端会 422，这里提前禁用提交；
 *  * 没有决策的字段**不下发**（服务端按"未给决策 = 不复用"处理）。
 */
export function buildClaimRequest(draft: ClaimDraft): ClaimListingRequest | null {
  if (!draft.sourceListingId) return null;
  if (!draft.targetShopId) return null;
  if (!draft.targetMarketplaceId) return null;

  const fieldDecisions: ListingClaimFieldDecision[] = [];
  for (const option of FIELD_OPTIONS) {
    const field = draft.fields[option.value];
    if (!field) continue;
    if (field.mode === 'REWRITE' && field.value.trim() === '') return null;
    fieldDecisions.push({ field: option.value, mode: field.mode, value: field.value });
  }
  if (!fieldDecisions.some((d) => d.field === 'TITLE')) return null;

  return {
    sourceListingId: draft.sourceListingId,
    targetShopId: draft.targetShopId,
    targetMarketplaceId: draft.targetMarketplaceId,
    language: draft.language,
    fieldDecisions,
    note: draft.note,
  };
}

/** 目标店铺候选：**排除源商品所在的店铺**（认领到同一个店铺不构成跨店搬家）。 */
export function targetShopOptions(shops: ShopView[], sourceShopId: string): ShopView[] {
  return shops.filter((s) => s.id !== sourceShopId && s.status === 'ACTIVE');
}

/** 目标店铺已启用的站点。 */
export function targetSiteOptions(shop: ShopView | null): ShopMarketplaceView[] {
  if (!shop) return [];
  return shop.marketplaces.filter((m) => m.status === 'ACTIVE');
}

/**
 * 🔴 与站点绑定的字段（跨站点时不能"保留"源值）。
 *
 * 页面上提前把它们标出来，而不是让用户点一次提交才吃一个 422。
 */
export function siteBoundFields(sourceMarketplaceId: string | null, targetMarketplaceId: string): string[] {
  if (!targetMarketplaceId) return [];
  if (sourceMarketplaceId && sourceMarketplaceId === targetMarketplaceId) return [];
  return FIELD_OPTIONS.filter((o) => o.siteBound).map((o) => o.value);
}

/** 认领状态文案。PUBLISHED 只表示"已离开草稿态"，**不代表平台已批准**。 */
export function claimStateLabel(state: string): string {
  switch (state) {
    case 'DRAFT':
      return '待发布（草稿）';
    case 'PUBLISHED':
      return '已离开草稿态（不代表平台审核通过）';
    default:
      return state;
  }
}

export function claimStateTone(state: string): 'neutral' | 'primary' | 'success' | 'warning' {
  return state === 'PUBLISHED' ? 'primary' : 'warning';
}

/** 字段决策文案。 */
export function decisionLabel(mode: string): string {
  switch (mode) {
    case 'KEEP':
      return '保留源值';
    case 'REWRITE':
      return '重写';
    case 'SKIP':
      return '跳过（目标留空）';
    default:
      return mode;
  }
}

/** 源值来源文案。 */
export function sourceOriginLabel(origin: string): string {
  switch (origin) {
    case 'PLATFORM_SNAPSHOT':
      return '平台内容快照';
    case 'LISTING':
      return 'listing 行';
    case 'NOT_PRESENT':
      return '源侧没有这个字段';
    default:
      return origin;
  }
}

/**
 * 🔴 源 listing 是否在认领之后被改过。
 *
 * 认领记录里存了「认领时」与「现在」两组快照；不一致就说明源商品在认领之后动过 ——
 * 这**不是**认领造成的（认领只读源），但运营需要知道，否则"源没变"这句话就是假的。
 */
export function sourceUnchanged(claim: ListingClaimRow): boolean {
  return (
    claim.sourceStatus === claim.sourceCurrentStatus &&
    claim.sourceReviewStatus === claim.sourceCurrentReviewStatus &&
    claim.sourceVersion === claim.sourceCurrentVersion
  );
}

/** 目标草稿的徽章文案 —— 「尚未过平台审核」必须一眼可见。 */
export function draftBadge(claim: ListingClaimRow): string {
  if (claim.targetStatus !== 'DRAFT') return `目标 ${claim.targetStatus}`;
  if (claim.targetReviewStatus === 'NOT_SUBMITTED') return '草稿 · 尚未过平台审核';
  return `草稿 · 审核状态 ${claim.targetReviewStatus}`;
}

export function draftBadgeTone(claim: ListingClaimRow): 'neutral' | 'warning' | 'info' {
  if (claim.targetStatus !== 'DRAFT') return 'info';
  return 'warning';
}

/** 未接入的维度（售价 / 内部可售量 / 平台审核 / 内部内容草稿 / 跨平台认领）。 */
export function unavailableDimensions(caps: ListingClaimCapabilities) {
  return caps.dimensions.filter((d) => !d.available);
}

/** 空态文案：还没有认领记录时显示什么。 */
export function emptyClaimsMessage(): string {
  return '还没有认领记录。选一个源商品、选目标店铺与站点、勾好字段决策，就可以把商品跨店搬过去。';
}

/** 目标 listing 的「平台商品 ID 尚未产生」提示。 */
export function pendingPlatformItemIdNote(claim: ListingClaimRow): string {
  if (!claim.targetExternalItemId.startsWith('CLAIM-PENDING:')) return '';
  return '平台商品 ID 尚未产生（草稿未发布，平台不分配 ASIN / item_id）。这里显示的是保留位，不是平台原文。';
}
