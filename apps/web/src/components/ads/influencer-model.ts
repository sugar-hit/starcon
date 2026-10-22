import { api } from '../inventory/api';

/**
 * 14.5.1.1 达人库 前端模型。
 *
 * <p>蓝图：「管理达人/联盟资源：达人档案（类目、粉丝层级、带货 GMV、互动率、合作历史）、
 * 标签分组、评级与黑名单。输入：达人数据同步（平台接口或手动录入）；标签与评级。
 * 输出：达人库 + 筛选检索。关系：上游 = 平台数据；下游 = 14.5.1.2 建联、15 客户与客服。
 * <strong>达人效果数据（GMV/退款率）必须入库</strong>，否则无法筛选优质达人。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「没录」不是 0**。`gmv` / `engagementRate` / `refundRate` 为 `null` 表示
 *    **还没录**（不可比），界面显示「未录入」，**不是 0.00** —— 0 会被读成
 *    「这个达人一单没带过货」。同理 `ratingScore` 为 `null` 是「评分口径未配置 /
 *    必需数据缺项」，不是「0 分」。
 * 2. **粉丝层级不是手填的**。服务端由 DB 触发器从粉丝数推导，界面只展示并按它筛选。
 * 3. **「本地已录」不是「平台侧已录」**。`syncSource` 与 `platformSyncStatus` 分开显示，
 *    页面要写明「平台侧还没有这个达人」。
 * 4. **两个 GMV 口径分开**。`gmv`（档案口径）与 `gmvFromCooperations`（合作历史合计）
 *    是两个字段，归因口径归 14.5.2.1，未交付。
 * 5. **黑名单是独立事实**。拉黑后从正常检索里排除，解除留痕 —— 页面必须有一个
 *    **独立可见的黑名单处理入口**，否则运营找不到「我为什么搜不到这个达人」。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * GMV / 佣金 / 互动率 / 退款率 / 评分都是服务端 `BigDecimal` 的事，
 * 前端算一遍就会引入二进制浮点误差，而且会让「界面上的 GMV」和「账上的 GMV」对不上。
 */

/** 金额与数量一律 number（JSON number），只原样展示。 */
export type InfluencerMoney = number | null;

/** 🔴 null = 还没录（不可比），与 `0`（录了，而且真的是零）含义相反。 */
export type InfluencerRatio = number | null;

export interface InfluencerCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface InfluencerCapabilities {
  dimensions: InfluencerCapabilityDimension[];
  followerTiers: string[];
  blacklistReasonCodes: string[];
  cooperationStatuses: string[];
  syncSources: string[];
  effectNote: string;
  ratingNote: string;
  syncNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface InfluencerOption {
  code: string;
  label: string;
}

export interface InfluencerPlatformOption {
  id: string;
  code: string;
  name: string;
}

export interface InfluencerOptions {
  followerTiers: InfluencerOption[];
  blacklistReasons: InfluencerOption[];
  cooperationStatuses: InfluencerOption[];
  platforms: InfluencerPlatformOption[];
  platformCodes: string[];
  shopCodes: string[];
  currencies: string[];
  categories: string[];
  effectNote: string;
  ratingNote: string;
}

export interface InfluencerRow {
  id: string;
  platformId: string;
  platformCode: string;
  shopCode: string;
  externalInfluencerId: string;
  displayName: string;
  handle: string;
  category: string;
  followerCount: number;
  followerTier: string;
  engagementRate: InfluencerRatio;
  gmv: InfluencerMoney;
  gmvCurrency: string;
  refundRate: InfluencerRatio;
  ratingScore: InfluencerRatio;
  ratingTierId: string | null;
  ratingTierCode: string | null;
  ratingTierName: string | null;
  ratingSortOrder: number | null;
  cooperationCount: number;
  gmvFromCooperations: InfluencerMoney;
  syncSource: string;
  platformSyncStatus: string;
  status: string;
  blacklisted: boolean;
  blacklistId: string | null;
  blacklistReasonCode: string | null;
  blacklistEffectiveAt: string | null;
  tagCodes: string | null;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface InfluencerPage {
  items: InfluencerRow[];
  total: number;
  page: number;
  size: number;
}

export interface InfluencerTagGroupRow {
  id: string;
  groupCode: string;
  groupName: string;
  description: string;
  status: string;
  tagCount: number;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface InfluencerTagGroupPage {
  items: InfluencerTagGroupRow[];
  total: number;
  page: number;
  size: number;
}

export interface InfluencerTagRow {
  id: string;
  groupId: string;
  groupCode: string;
  groupName: string;
  tagCode: string;
  tagName: string;
  description: string;
  status: string;
  influencerCount: number;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface InfluencerTagPage {
  items: InfluencerTagRow[];
  total: number;
  page: number;
  size: number;
}

export interface InfluencerRatingTierRow {
  id: string;
  tierCode: string;
  tierName: string;
  minScore: InfluencerRatio;
  maxScore: InfluencerRatio;
  sortOrder: number;
  status: string;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface InfluencerRatingTierPage {
  items: InfluencerRatingTierRow[];
  total: number;
  page: number;
  size: number;
}

export interface InfluencerRatingRuleRow {
  id: string;
  ruleCode: string;
  weightGmv: InfluencerRatio;
  weightEngagement: InfluencerRatio;
  weightRefund: InfluencerRatio;
  gmvCap: InfluencerMoney;
  engagementCap: InfluencerRatio;
  refundCap: InfluencerRatio;
  status: string;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface InfluencerBlacklistRow {
  id: string;
  influencerId: string;
  influencerName: string;
  category: string;
  followerTier: string;
  reasonCode: string;
  reason: string;
  effectiveAt: string;
  releasedAt: string | null;
  releasedReason: string;
  status: string;
  note: string;
  createdAt: string;
  updatedAt: string;
}

export interface InfluencerBlacklistPage {
  items: InfluencerBlacklistRow[];
  total: number;
  page: number;
  size: number;
}

export interface InfluencerCooperationRow {
  id: string;
  influencerId: string;
  influencerName: string;
  campaignId: string;
  campaignName: string;
  externalCampaignId: string;
  shopCode: string;
  status: string;
  startedAt: string | null;
  endedAt: string | null;
  gmv: InfluencerMoney;
  orderCount: InfluencerRatio;
  commission: InfluencerMoney;
  refundRate: InfluencerRatio;
  currency: string;
  note: string;
  createdAt: string;
  updatedAt: string;
}

export interface InfluencerCooperationPage {
  items: InfluencerCooperationRow[];
  total: number;
  page: number;
  size: number;
}

export interface InfluencerDetail {
  influencer: InfluencerRow;
  tags: InfluencerTagRow[];
  cooperations: InfluencerCooperationRow[];
  blacklist: InfluencerBlacklistRow | null;
  effectNote: string;
  ratingNote: string;
  syncNote: string;
  note: string;
}

export interface InfluencerPlatformSyncAvailability {
  platformCode: string;
  platformName: string;
  available: boolean;
  status: string;
  reason: string;
  registeredCount: number;
}

export interface InfluencerSyncStatus {
  platforms: InfluencerPlatformSyncAvailability[];
  syncNote: string;
}

export interface InfluencerSyncResult {
  synced: boolean;
  message: string;
  syncedCount: number;
  failedCount: number;
}

// ==================================================================
// 请求
// ==================================================================

export interface SaveInfluencerInput {
  platformId: string;
  shopCode: string;
  externalInfluencerId: string;
  displayName: string;
  handle: string;
  category: string;
  followerCount: number | null;
  engagementRate: number | null;
  gmv: number | null;
  gmvCurrency: string;
  refundRate: number | null;
  status: string;
  note: string;
}

export interface TagInfluencersInput {
  tagIds: string[];
  tagCodes: string[];
  note: string;
}

export interface SaveInfluencerTagInput {
  groupId: string;
  tagCode: string;
  tagName: string;
  description: string;
  status: string;
  note: string;
}

export interface SaveInfluencerTagGroupInput {
  groupCode: string;
  groupName: string;
  description: string;
  status: string;
  note: string;
}

export interface SaveInfluencerRatingTierInput {
  tierCode: string;
  tierName: string;
  minScore: number | null;
  maxScore: number | null;
  sortOrder: number | null;
  status: string;
  note: string;
}

export interface SaveInfluencerRatingRuleInput {
  ruleCode: string;
  weightGmv: number | null;
  weightEngagement: number | null;
  weightRefund: number | null;
  gmvCap: number | null;
  engagementCap: number | null;
  refundCap: number | null;
  status: string;
  note: string;
}

export interface BlacklistInfluencerInput {
  influencerId: string;
  reasonCode: string;
  reason: string;
  effectiveAt: string | null;
  note: string;
}

export interface SaveCooperationInput {
  influencerId: string;
  campaignId: string;
  status: string;
  startedAt: string | null;
  endedAt: string | null;
  gmv: number | null;
  orderCount: number | null;
  commission: number | null;
  refundRate: number | null;
  currency: string;
  note: string;
}

export interface SyncInfluencersInput {
  platformCode: string;
  shopCode: string;
}

/**
 * 达人列表的筛选条件。
 *
 * 🔴 每一项都显式带 `| undefined`：`exactOptionalPropertyTypes` 下，
 * 「清掉某个筛选」必须能显式传 `undefined`（否则传不进去）。
 */
export interface InfluencerQuery {
  category?: string | undefined;
  followerTier?: string | undefined;
  tagId?: string | undefined;
  tagCode?: string | undefined;
  tagGroupCode?: string | undefined;
  ratingTierCode?: string | undefined;
  minGmv?: number | undefined;
  maxGmv?: number | undefined;
  minEngagementRate?: number | undefined;
  maxEngagementRate?: number | undefined;
  platformId?: string | undefined;
  shopCode?: string | undefined;
  keyword?: string | undefined;
  includeBlacklisted?: boolean | undefined;
  sort?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 表单状态：金额与比率一律用**字符串**承载，避免输入过程中的浮点误差。 */
export interface InfluencerInputState {
  platformId: string;
  shopCode: string;
  externalInfluencerId: string;
  displayName: string;
  handle: string;
  category: string;
  followerCount: string;
  engagementRate: string;
  gmv: string;
  gmvCurrency: string;
  refundRate: string;
  status: string;
  note: string;
}

export function emptyInfluencerInput(): InfluencerInputState {
  return {
    platformId: '',
    shopCode: '',
    externalInfluencerId: '',
    displayName: '',
    handle: '',
    category: '',
    followerCount: '0',
    engagementRate: '',
    gmv: '',
    gmvCurrency: 'USD',
    refundRate: '',
    status: 'ACTIVE',
    note: '',
  };
}

/** 标签表单状态。 */
export interface InfluencerTagInputState {
  groupId: string;
  tagCode: string;
  tagName: string;
  description: string;
  status: string;
  note: string;
}

export function emptyInfluencerTagInput(): InfluencerTagInputState {
  return { groupId: '', tagCode: '', tagName: '', description: '', status: 'ACTIVE', note: '' };
}

/** 标签分组表单状态。 */
export interface InfluencerTagGroupInputState {
  groupCode: string;
  groupName: string;
  description: string;
  status: string;
  note: string;
}

export function emptyInfluencerTagGroupInput(): InfluencerTagGroupInputState {
  return { groupCode: '', groupName: '', description: '', status: 'ACTIVE', note: '' };
}

/** 评级档位表单状态。 */
export interface InfluencerRatingTierInputState {
  tierCode: string;
  tierName: string;
  minScore: string;
  maxScore: string;
  sortOrder: string;
  status: string;
  note: string;
}

export function emptyInfluencerRatingTierInput(): InfluencerRatingTierInputState {
  return {
    tierCode: '',
    tierName: '',
    minScore: '',
    maxScore: '',
    sortOrder: '0',
    status: 'ACTIVE',
    note: '',
  };
}

/** 评分口径表单状态。 */
export interface InfluencerRatingRuleInputState {
  ruleCode: string;
  weightGmv: string;
  weightEngagement: string;
  weightRefund: string;
  gmvCap: string;
  engagementCap: string;
  refundCap: string;
  status: string;
  note: string;
}

export function emptyInfluencerRatingRuleInput(): InfluencerRatingRuleInputState {
  return {
    ruleCode: 'DEFAULT',
    weightGmv: '0.5',
    weightEngagement: '0.3',
    weightRefund: '0.2',
    gmvCap: '100000',
    engagementCap: '0.1',
    refundCap: '0.3',
    status: 'ACTIVE',
    note: '',
  };
}

// ==================================================================
// 接口
// ==================================================================

const base = 'channel/influencers';

function queryString(query: object): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : '';
}

export function fetchInfluencerCapabilities(): Promise<InfluencerCapabilities> {
  return api<InfluencerCapabilities>(`${base}/capabilities`);
}

export function fetchInfluencerOptions(): Promise<InfluencerOptions> {
  return api<InfluencerOptions>(`${base}/options`);
}

export function fetchInfluencerSyncStatus(): Promise<InfluencerSyncStatus> {
  return api<InfluencerSyncStatus>(`${base}/sync-status`);
}

export function syncInfluencers(input: SyncInfluencersInput): Promise<InfluencerSyncResult> {
  return api<InfluencerSyncResult>(`${base}/sync`, 'POST', input);
}

export function fetchInfluencers(query: InfluencerQuery = {}): Promise<InfluencerPage> {
  return api<InfluencerPage>(`${base}/profiles${queryString(query)}`);
}

export function fetchInfluencer(id: string): Promise<InfluencerDetail> {
  return api<InfluencerDetail>(`${base}/profiles/${id}`);
}

export function saveInfluencer(input: SaveInfluencerInput): Promise<InfluencerDetail> {
  return api<InfluencerDetail>(`${base}/profiles`, 'POST', input);
}

export function updateInfluencer(
  id: string,
  input: SaveInfluencerInput,
): Promise<InfluencerDetail> {
  return api<InfluencerDetail>(`${base}/profiles/${id}`, 'POST', input);
}

export function deleteInfluencer(id: string): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(`${base}/profiles/${id}`, 'DELETE');
}

export function tagInfluencer(id: string, input: TagInfluencersInput): Promise<InfluencerDetail> {
  return api<InfluencerDetail>(`${base}/profiles/${id}/tags`, 'POST', input);
}

export function untagInfluencer(id: string, tagId: string): Promise<InfluencerDetail> {
  return api<InfluencerDetail>(`${base}/profiles/${id}/tags/${tagId}`, 'DELETE');
}

export function recomputeInfluencerRating(id: string): Promise<InfluencerDetail> {
  return api<InfluencerDetail>(`${base}/profiles/${id}/rating`, 'POST');
}

export function fetchInfluencerTagGroups(
  query: { status?: string; keyword?: string; page?: number; size?: number } = {},
): Promise<InfluencerTagGroupPage> {
  return api<InfluencerTagGroupPage>(`${base}/tag-groups${queryString(query)}`);
}

export function saveInfluencerTagGroup(
  input: SaveInfluencerTagGroupInput,
): Promise<InfluencerTagGroupRow> {
  return api<InfluencerTagGroupRow>(`${base}/tag-groups`, 'POST', input);
}

export function updateInfluencerTagGroup(
  id: string,
  input: SaveInfluencerTagGroupInput,
): Promise<InfluencerTagGroupRow> {
  return api<InfluencerTagGroupRow>(`${base}/tag-groups/${id}`, 'POST', input);
}

export function deleteInfluencerTagGroup(id: string): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(`${base}/tag-groups/${id}`, 'DELETE');
}

export function fetchInfluencerTags(
  query: { groupCode?: string; status?: string; keyword?: string; page?: number; size?: number } = {},
): Promise<InfluencerTagPage> {
  return api<InfluencerTagPage>(`${base}/tags${queryString(query)}`);
}

export function saveInfluencerTag(input: SaveInfluencerTagInput): Promise<InfluencerTagRow> {
  return api<InfluencerTagRow>(`${base}/tags`, 'POST', input);
}

export function updateInfluencerTag(
  id: string,
  input: SaveInfluencerTagInput,
): Promise<InfluencerTagRow> {
  return api<InfluencerTagRow>(`${base}/tags/${id}`, 'POST', input);
}

export function deleteInfluencerTag(id: string): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(`${base}/tags/${id}`, 'DELETE');
}

export function fetchInfluencerRatingTiers(
  query: { status?: string; page?: number; size?: number } = {},
): Promise<InfluencerRatingTierPage> {
  return api<InfluencerRatingTierPage>(`${base}/rating-tiers${queryString(query)}`);
}

export function saveInfluencerRatingTier(
  input: SaveInfluencerRatingTierInput,
): Promise<InfluencerRatingTierRow> {
  return api<InfluencerRatingTierRow>(`${base}/rating-tiers`, 'POST', input);
}

export function updateInfluencerRatingTier(
  id: string,
  input: SaveInfluencerRatingTierInput,
): Promise<InfluencerRatingTierRow> {
  return api<InfluencerRatingTierRow>(`${base}/rating-tiers/${id}`, 'POST', input);
}

export function deleteInfluencerRatingTier(id: string): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(`${base}/rating-tiers/${id}`, 'DELETE');
}

/** 生效的评分口径（**没有配置返回 null** —— 「没配口径」不是「口径是 0」）。 */
export function fetchInfluencerRatingRule(): Promise<InfluencerRatingRuleRow | null> {
  return api<InfluencerRatingRuleRow | null>(`${base}/rating-rules`);
}

export function saveInfluencerRatingRule(
  input: SaveInfluencerRatingRuleInput,
): Promise<InfluencerRatingRuleRow> {
  return api<InfluencerRatingRuleRow>(`${base}/rating-rules`, 'POST', input);
}

export function fetchInfluencerBlacklist(
  query: {
    status?: string;
    reasonCode?: string;
    influencerId?: string;
    keyword?: string;
    page?: number;
    size?: number;
  } = {},
): Promise<InfluencerBlacklistPage> {
  return api<InfluencerBlacklistPage>(`${base}/blacklist${queryString(query)}`);
}

export function blacklistInfluencer(
  input: BlacklistInfluencerInput,
): Promise<InfluencerBlacklistRow> {
  return api<InfluencerBlacklistRow>(`${base}/blacklist`, 'POST', input);
}

export function releaseInfluencerBlacklist(
  id: string,
  reason: string,
): Promise<InfluencerBlacklistRow> {
  return api<InfluencerBlacklistRow>(`${base}/blacklist/${id}/release`, 'POST', { reason });
}

export function fetchInfluencerCooperations(
  query: {
    influencerId?: string;
    campaignId?: string;
    shopCode?: string;
    status?: string;
    page?: number;
    size?: number;
  } = {},
): Promise<InfluencerCooperationPage> {
  return api<InfluencerCooperationPage>(`${base}/cooperations${queryString(query)}`);
}

export function saveInfluencerCooperation(
  input: SaveCooperationInput,
): Promise<InfluencerCooperationRow> {
  return api<InfluencerCooperationRow>(`${base}/cooperations`, 'POST', input);
}

export function updateInfluencerCooperation(
  id: string,
  input: SaveCooperationInput,
): Promise<InfluencerCooperationRow> {
  return api<InfluencerCooperationRow>(`${base}/cooperations/${id}`, 'POST', input);
}

// ==================================================================
// 展示辅助
// ==================================================================

/**
 * 🔴 效果数据的展示：**null 显示「未录入」，不是 0.00**。
 *
 * 0 是「录了，而且真的是零」；null 是「压根没录」。两者渲染成同一个 0.00
 * 就把「没录」说成了「录了而且是零」。
 */
export function effectMoney(value: InfluencerMoney | undefined, currency?: string): string {
  if (value === null || value === undefined) return '未录入';
  const fixed = value.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

/** 🔴 比率的展示：null 显示「未录入」，不是 0.00%。 */
export function effectRatio(value: InfluencerRatio | undefined): string {
  if (value === null || value === undefined) return '未录入';
  return `${(value * 100).toFixed(2)}%`;
}

/** 🔴 数量的展示：null 显示「未录入」，不是 0。 */
export function effectCount(value: InfluencerRatio | undefined): string {
  if (value === null || value === undefined) return '未录入';
  return Number(value).toLocaleString('zh-CN');
}

/** 🔴 评分的展示：null 显示「暂无评分」（口径未配置 / 必需数据缺项），不是「0.00 分」。 */
export function ratingText(value: InfluencerRatio | undefined): string {
  if (value === null || value === undefined) return '暂无评分';
  return value.toFixed(4);
}

/** 粉丝数的展示。 */
export function followerText(value: number | undefined): string {
  if (value === null || value === undefined) return '未录入';
  return value.toLocaleString('zh-CN');
}

export function followerTierLabel(tier: string | null | undefined): string {
  switch (tier) {
    case 'NANO':
      return '素人（1 万以下）';
    case 'MICRO':
      return '微型（1 万 ~ 10 万）';
    case 'MID':
      return '中型（10 万 ~ 50 万）';
    case 'MACRO':
      return '头部（50 万 ~ 100 万）';
    case 'MEGA':
      return '超头（100 万 ~ 500 万）';
    case 'TOP':
      return '顶级（500 万以上）';
    default:
      return tier ?? '—';
  }
}

export function blacklistReasonLabel(code: string | null | undefined): string {
  switch (code) {
    case 'FRAUD':
      return '欺诈';
    case 'INFRINGEMENT':
      return '侵权';
    case 'POOR_QUALITY':
      return '效果差';
    case 'NON_COMPLIANCE':
      return '违规';
    case 'BRAND_SAFETY':
      return '品牌安全';
    case 'DATA_QUALITY':
      return '数据质量';
    default:
      return code ?? '—';
  }
}

export function cooperationStatusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'PLANNED':
      return '筹备中';
    case 'ACTIVE':
      return '合作中';
    case 'COMPLETED':
      return '已完成';
    case 'CANCELLED':
      return '已取消';
    default:
      return status ?? '—';
  }
}

/** 🔴 数据来源与平台同步状态：必须把「本地已录、平台侧还没有」说清楚。 */
export function syncSourceLabel(source: string | null | undefined): string {
  switch (source) {
    case 'MANUAL':
      return '手动录入';
    case 'PLATFORM_API':
      return '平台接口同步';
    default:
      return source ?? '—';
  }
}

export function platformSyncStatusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'LOCAL_ONLY':
      return '本地已录，平台侧还没有';
    case 'PENDING_SYNC':
      return '待同步到平台';
    case 'SYNCED':
      return '平台侧已一致';
    case 'FAILED':
      return '同步失败';
    default:
      return status ?? '—';
  }
}

export function influencerStatusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'ACTIVE':
      return '在库可用';
    case 'INACTIVE':
      return '已停用';
    default:
      return status ?? '—';
  }
}

export function timestampText(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/**
 * 服务端时刻 → `<input type="datetime-local">` 的本地格式（`YYYY-MM-DDTHH:mm`）。
 *
 * 🔴 用**本地时区**取值：`toISOString()` 会转成 UTC，而 datetime-local 控件要的是
 * 本地墙上时间 —— 直接塞 ISO 串会让运营看到的起止时间整体偏掉几个小时。
 */
export function localDateTimeInput(value: string | null | undefined): string {
  if (!value) return '';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())}T${pad(
    parsed.getHours(),
  )}:${pad(parsed.getMinutes())}`;
}

/** 标签编码串（可能是 null）→ 展示用数组。 */
export function tagCodeList(tagCodes: string | null | undefined): string[] {
  if (!tagCodes) return [];
  return tagCodes
    .split(',')
    .map((code) => code.trim())
    .filter((code) => code.length > 0);
}

// ==================================================================
// 表单校验（与服务端同口径，错了也别让用户白跑一趟）
// ==================================================================

function parseOptional(raw: string): number | null {
  const trimmed = raw.trim();
  if (trimmed === '') return null;
  const value = Number(trimmed);
  return Number.isNaN(value) ? null : value;
}

function isRatio(raw: string): boolean {
  const trimmed = raw.trim();
  if (trimmed === '') return true;
  const value = Number(trimmed);
  return !Number.isNaN(value) && value >= 0 && value <= 1;
}

export function validateInfluencer(input: InfluencerInputState): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.platformId.trim()) errors.push('平台必填');
  if (!input.shopCode.trim()) errors.push('店铺必填');
  if (!input.displayName.trim()) errors.push('达人名称必填');
  if (!input.category.trim()) errors.push('类目必填');
  if (input.gmvCurrency.trim() && input.gmvCurrency.trim().length !== 3) {
    errors.push('币种必须是 3 位字母代码');
  }
  if (!isRatio(input.engagementRate)) errors.push('互动率必须在 0 ~ 1 之间');
  if (!isRatio(input.refundRate)) errors.push('退款率必须在 0 ~ 1 之间');
  const gmv = parseOptional(input.gmv);
  if (gmv !== null && gmv < 0) errors.push('带货 GMV 不能是负数');
  const followers = Number(input.followerCount.trim() === '' ? '0' : input.followerCount.trim());
  if (Number.isNaN(followers) || followers < 0 || !Number.isInteger(followers)) {
    errors.push('粉丝数必须是不小于 0 的整数');
  }
  return { ok: errors.length === 0, errors };
}

export function validateInfluencerTag(input: InfluencerTagInputState): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.groupId.trim()) errors.push('标签分组必填');
  if (!input.tagCode.trim()) errors.push('标签编码必填');
  if (!input.tagName.trim()) errors.push('标签名称必填');
  return { ok: errors.length === 0, errors };
}

export function validateInfluencerTagGroup(input: InfluencerTagGroupInputState): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.groupCode.trim()) errors.push('分组编码必填');
  if (!input.groupName.trim()) errors.push('分组名称必填');
  return { ok: errors.length === 0, errors };
}

export function validateInfluencerRatingTier(input: InfluencerRatingTierInputState): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.tierCode.trim()) errors.push('档位编码必填');
  if (!input.tierName.trim()) errors.push('档位名称必填');
  const min = parseOptional(input.minScore);
  const max = parseOptional(input.maxScore);
  if (min === null || max === null) {
    errors.push('档位的分数下限与上限必填');
  } else if (min < 0 || min > 1 || max < 0 || max > 1) {
    errors.push('分数区间必须落在 0 ~ 1 之间');
  } else if (max <= min) {
    errors.push('档位上限必须严格大于下限');
  }
  return { ok: errors.length === 0, errors };
}

export function validateInfluencerRatingRule(input: InfluencerRatingRuleInputState): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  const weights = [input.weightGmv, input.weightEngagement, input.weightRefund].map((raw) =>
    parseOptional(raw),
  );
  if (weights.some((w) => w === null)) {
    errors.push('三个权重必填');
  } else {
    const sum = (weights[0] ?? 0) + (weights[1] ?? 0) + (weights[2] ?? 0);
    // 🔴 权重之和必须为 1（服务端同口径，DB CHECK 也钉死了这一条）
    if (Math.abs(sum - 1) > 1e-9) errors.push(`三个权重之和必须等于 1（当前是 ${sum}）`);
    weights.forEach((w) => {
      if (w !== null && (w < 0 || w > 1)) errors.push('每个权重必须落在 0 ~ 1 之间');
    });
  }
  const caps = [input.gmvCap, input.engagementCap, input.refundCap].map((raw) =>
    parseOptional(raw),
  );
  if (caps.some((c) => c === null)) {
    errors.push('三个归一化上限必填');
  } else {
    caps.forEach((c) => {
      if (c !== null && c <= 0) errors.push('归一化上限必须大于 0');
    });
  }
  return { ok: errors.length === 0, errors };
}
