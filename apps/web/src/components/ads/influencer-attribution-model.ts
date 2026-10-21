import { api } from '../inventory/api';

/**
 * 14.5.2.1 带货归因 前端模型。
 *
 * <p>蓝图：「达人带货归因：达人带来的订单量、GMV、退款率、佣金成本、有效粉丝转化，
 * 对比达人投入产出，沉淀优质达人库。输入：达人带货数据同步；订单归因；佣金数据。
 * 输出：达人效果报表 + ROI + 评级更新。关系：上游 = 14.5.1.1、平台数据；
 * 下游 = 14.5.1.1 评级、13.4 利润（佣金成本）。
 * <strong>达人佣金是真实成本，必须进利润表</strong>，否则会高估达人带货的净收益。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **三个 GMV 口径分列三栏**。`profileGmv`（14.5.1.1 的档案口径）、
 *    `cooperationGmvTotal`（本地按合作行求和的合作口径）与 `attributedGmvTotal`
 *    （本模块的归因口径）是**三个不同的数**，谁也不覆盖谁。
 * 2. **佣金成本还没进利润表**。`profitIntegration.available` 恒为 `false`
 *    （13.4 未交付）—— 页面**不许**写「已同步到利润分析」。
 * 3. **「没录」不是 0**。`null` 表示**还没录 / 不可比**，界面显示「未录入」，
 *    **不是 0.00** —— 0 会被读成「这个达人不用付佣金」。
 * 4. **平台带货数据同步未接入**。同步入口明确返回「未接入」并**不写任何数据**。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * GMV / 佣金 / ROI / 退款率 / 转化率都是服务端 `BigDecimal` 的事，
 * 前端算一遍就会引入二进制浮点误差，而且会让「界面上的 GMV」和「账上的 GMV」对不上。
 */

/** 金额与数量一律 number（JSON number），只原样展示。 */
export type AttributionMoney = number | null;

/** 🔴 null = 还没录（不可比），与 `0`（录了，而且真的是零）含义相反。 */
export type AttributionRatio = number | null;

export interface AttributionCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

/**
 * 🔴 利润侧对接状态：`available` 恒为 `false`（13.4 利润 / reporting 服务未交付）。
 *
 * <p>页面必须照着它说人话：**佣金成本只在本服务内留痕，没有进任何利润报表**。
 */
export interface AttributionProfitIntegration {
  available: boolean;
  ownerModule: string;
  note: string;
}

export interface AttributionCapabilities {
  dimensions: AttributionCapabilityDimension[];
  profitIntegration: AttributionProfitIntegration;
  attributionModels: string[];
  poolWeightBases: string[];
  refundTreatments: string[];
  commissionBases: string[];
  accrualStatuses: string[];
  caliberNote: string;
  ruleNote: string;
  commissionNote: string;
  syncNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface AttributionOption {
  code: string;
  label: string;
}

export interface AttributionPlatformOption {
  id: string;
  code: string;
  name: string;
}

export interface AttributionOptions {
  attributionModels: AttributionOption[];
  poolWeightBases: AttributionOption[];
  refundTreatments: AttributionOption[];
  commissionBases: AttributionOption[];
  accrualStatuses: AttributionOption[];
  platforms: AttributionPlatformOption[];
  platformCodes: string[];
  shopCodes: string[];
  currencies: string[];
  caliberNote: string;
  commissionNote: string;
}

export interface AttributionPlatformSyncAvailability {
  platformCode: string;
  platformName: string;
  available: boolean;
  status: string;
  reason: string;
  registeredCount: number;
}

export interface AttributionSyncStatus {
  items: AttributionPlatformSyncAvailability[];
  note: string;
}

export interface AttributionSyncResult {
  success: boolean;
  message: string;
  synced: number;
  skipped: number;
}

export interface AttributionRuleRow {
  id: string;
  ruleCode: string;
  attributionModel: string;
  poolWeightBasis: string;
  attributionWindowDays: number;
  refundTreatment: string;
  commissionBasis: string;
  commissionRate: AttributionRatio;
  status: string;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AttributionRulePage {
  items: AttributionRuleRow[];
  page: number;
  size: number;
  total: number;
  ruleNote: string;
}

/** 一条归因记录（自带规则快照与基数快照）。 */
export interface AttributionRow {
  id: string;
  influencerId: string;
  influencerName: string;
  influencerHandle: string;
  influencerCategory: string;
  followerCount: number;
  followerTier: string;
  cooperationId: string;
  campaignId: string;
  campaignName: string;
  shopCode: string;
  ruleCode: string;
  attributionModel: string;
  poolWeightBasis: string;
  attributionWindowDays: number;
  refundTreatment: string;
  commissionBasis: string;
  commissionRate: AttributionRatio;
  attributionWeight: AttributionRatio;
  sourceGmv: AttributionMoney;
  sourceOrderCount: AttributionMoney;
  sourceCommission: AttributionMoney;
  sourceRefundRate: AttributionRatio;
  sourceCurrency: string;
  attributionBaseGmv: AttributionMoney;
  attributionBaseOrderCount: AttributionMoney;
  attributionBaseRefundRate: AttributionRatio;
  attributedGmv: AttributionMoney;
  attributedOrderCount: AttributionMoney;
  attributedRefundRate: AttributionRatio;
  attributedRefundAmount: AttributionMoney;
  effectiveOrderCount: AttributionMoney;
  followerConversionRate: AttributionRatio;
  followerCountSnapshot: number;
  currency: string;
  commissionAmount: AttributionMoney;
  commissionBasisAmount: AttributionMoney;
  commissionVariance: AttributionMoney;
  accrualStatus: string;
  accruedAt: string | null;
  accrualNote: string;
  attributedAt: string;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AttributionPage {
  items: AttributionRow[];
  page: number;
  size: number;
  total: number;
  caliberNote: string;
  commissionNote: string;
}

/** 🔴 达人三个 GMV 口径并排。 */
export interface AttributionInfluencerSummary {
  influencerId: string;
  displayName: string;
  handle: string;
  category: string;
  followerCount: number;
  followerTier: string;
  shopCode: string;
  profileGmv: AttributionMoney;
  profileGmvCurrency: string;
  cooperationGmvTotal: AttributionMoney;
  cooperationOrderTotal: AttributionMoney;
  cooperationCommissionTotal: AttributionMoney;
  attributedGmvTotal: AttributionMoney;
  attributedOrderTotal: AttributionMoney;
  commissionTotal: AttributionMoney;
  effectiveOrderTotal: AttributionMoney;
  roi: AttributionRatio;
  followerConversionRate: AttributionRatio;
  ratingScore: AttributionRatio;
  ratingTierCode: string | null;
  ratingTierName: string | null;
}

export interface AttributionCooperationSource {
  cooperationId: string;
  campaignId: string;
  campaignName: string;
  status: string;
  startedAt: string | null;
  endedAt: string | null;
  gmv: AttributionMoney;
  orderCount: AttributionMoney;
  commission: AttributionMoney;
  refundRate: AttributionRatio;
  currency: string;
}

export interface AttributionRuleSnapshot {
  id: string;
  ruleCode: string;
  attributionModel: string;
  poolWeightBasis: string;
  attributionWindowDays: number;
  refundTreatment: string;
  commissionBasis: string;
  commissionRate: AttributionRatio;
  status: string;
  note: string;
}

export interface AttributionDetail {
  row: AttributionRow;
  influencer: AttributionInfluencerSummary;
  source: AttributionCooperationSource;
  rule: AttributionRuleSnapshot;
  caliberNote: string;
  commissionNote: string;
  profitIntegration: AttributionProfitIntegration;
}

export interface AttributionRunResult {
  success: boolean;
  ruleCode: string;
  attributionModel: string;
  processed: number;
  created: number;
  refreshed: number;
  skippedOutOfWindow: number;
  skippedNoEffectData: number;
  skippedNoSplitBasis: number;
  ratingRecomputed: number;
  message: string;
}

/** 达人效果报表的一行（**三个 GMV 口径分列三栏**）。 */
export interface AttributionReportRow {
  influencerId: string;
  displayName: string;
  handle: string;
  category: string;
  followerCount: number;
  followerTier: string;
  shopCode: string;
  platformCode: string;
  profileGmv: AttributionMoney;
  profileGmvCurrency: string;
  cooperationGmvTotal: AttributionMoney;
  cooperationOrderTotal: AttributionMoney;
  cooperationCommissionTotal: AttributionMoney;
  attributedGmvTotal: AttributionMoney;
  attributedOrderTotal: AttributionMoney;
  attributedRefundRate: AttributionRatio;
  commissionTotal: AttributionMoney;
  commissionAccruedTotal: AttributionMoney;
  commissionPendingTotal: AttributionMoney;
  effectiveOrderTotal: AttributionMoney;
  followerConversionRate: AttributionRatio;
  roi: AttributionRatio;
  netGmvAfterCommission: AttributionMoney;
  attributionCount: number;
  ratingScore: AttributionRatio;
  ratingTierCode: string | null;
  ratingTierName: string | null;
}

export interface AttributionReportSummary {
  influencerCount: number;
  attributionCount: number;
  attributedGmvTotal: AttributionMoney;
  commissionTotal: AttributionMoney;
  commissionAccruedTotal: AttributionMoney;
  commissionPendingTotal: AttributionMoney;
  roi: AttributionRatio;
  effectiveOrderTotal: AttributionMoney;
  followerConversionRate: AttributionRatio;
  ruleCode: string | null;
  attributionModel: string | null;
  profitIntegration: AttributionProfitIntegration;
}

export interface AttributionReportPage {
  items: AttributionReportRow[];
  page: number;
  size: number;
  total: number;
  summary: AttributionReportSummary;
  caliberNote: string;
  ruleNote: string;
  commissionNote: string;
  profitIntegration: AttributionProfitIntegration;
}

export interface SaveAttributionRuleInput {
  ruleCode: string;
  attributionModel: string;
  poolWeightBasis: string;
  attributionWindowDays: number;
  refundTreatment: string;
  commissionBasis: string;
  commissionRate: number | null;
  status: string;
  note: string;
}

export interface AccrualInput {
  status: string;
  note: string;
}

export interface AttributionRecordQuery {
  influencerId?: string | undefined;
  campaignId?: string | undefined;
  shopCode?: string | undefined;
  ruleCode?: string | undefined;
  accrualStatus?: string | undefined;
  currency?: string | undefined;
  minAttributedGmv?: number | undefined;
  maxAttributedGmv?: number | undefined;
  keyword?: string | undefined;
  sort?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export interface AttributionReportQuery {
  influencerId?: string | undefined;
  campaignId?: string | undefined;
  shopCode?: string | undefined;
  platformCode?: string | undefined;
  category?: string | undefined;
  followerTier?: string | undefined;
  keyword?: string | undefined;
  minAttributedGmv?: number | undefined;
  maxAttributedGmv?: number | undefined;
  sort?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

const base = 'channel/attributions';

function queryString(query: object): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : '';
}

// ================================================================ 取数

export function fetchAttributionCapabilities(): Promise<AttributionCapabilities> {
  return api<AttributionCapabilities>(`${base}/capabilities`);
}

export function fetchAttributionOptions(): Promise<AttributionOptions> {
  return api<AttributionOptions>(`${base}/options`);
}

export function fetchAttributionSyncStatus(): Promise<AttributionSyncStatus> {
  return api<AttributionSyncStatus>(`${base}/sync-status`);
}

export function syncAttributions(input: {
  platformCode: string;
  shopCode: string;
}): Promise<AttributionSyncResult> {
  return api<AttributionSyncResult>(`${base}/sync`, 'POST', input);
}

export function fetchAttributionRules(
  status?: string,
  keyword?: string,
  page = 0,
  size = 50,
): Promise<AttributionRulePage> {
  return api<AttributionRulePage>(`${base}/rules${queryString({ status, keyword, page, size })}`);
}

export function fetchCurrentAttributionRule(): Promise<AttributionRuleRow | null> {
  return api<AttributionRuleRow | null>(`${base}/rules/current`);
}

export function saveAttributionRule(input: SaveAttributionRuleInput): Promise<AttributionRuleRow> {
  return api<AttributionRuleRow>(`${base}/rules`, 'POST', input);
}

export function runAttribution(input: {
  influencerId?: string | undefined;
  campaignId?: string | undefined;
  shopCode?: string | undefined;
}): Promise<AttributionRunResult> {
  return api<AttributionRunResult>(`${base}/run`, 'POST', input);
}

export function fetchAttributionRecords(
  query: AttributionRecordQuery = {},
): Promise<AttributionPage> {
  return api<AttributionPage>(`${base}/records${queryString(query)}`);
}

export function fetchAttributionRecord(id: string): Promise<AttributionDetail> {
  return api<AttributionDetail>(`${base}/records/${id}`);
}

export function accrueAttribution(id: string, input: AccrualInput): Promise<AttributionDetail> {
  return api<AttributionDetail>(`${base}/records/${id}/accrual`, 'POST', input);
}

export function fetchAttributionReport(
  query: AttributionReportQuery = {},
): Promise<AttributionReportPage> {
  return api<AttributionReportPage>(`${base}/report${queryString(query)}`);
}

// ================================================================ 展示

/**
 * 🔴 「没录」显示「未录入」，**不是 0.00**。
 *
 * <p>0 会被读成「这个达人一单没带过货 / 不用付佣金」，而真相是「你还没录」。
 */
export function attributionMoney(
  value: AttributionMoney | undefined,
  currency?: string,
): string {
  if (value === null || value === undefined) return '未录入';
  const fixed = value.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

/** 0 ~ 1 的比率显示成百分比（**只是展示格式，不是业务算术**）。 */
export function attributionPercent(value: AttributionRatio | undefined): string {
  if (value === null || value === undefined) return '未录入';
  return `${(value * 100).toFixed(2)}%`;
}

/**
 * ROI 是**倍数**（10.00 = 10 倍），不是百分比 —— 原样展示，不做换算。
 */
export function attributionRoiText(value: AttributionRatio | undefined): string {
  if (value === null || value === undefined) return '未录入';
  return value.toFixed(2);
}

/** 数量原样展示（不补零、不四舍五入成整数）。 */
export function attributionCountText(value: AttributionMoney | undefined): string {
  if (value === null || value === undefined) return '未录入';
  return String(value);
}

export function attributionTimestamp(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

export function attributionModelLabel(code: string | null | undefined): string {
  if (code === 'COOPERATION_FULL') return '合作全额归因';
  if (code === 'CAMPAIGN_POOL_SPLIT') return '同活动内分摊';
  return code ?? '—';
}

export function poolWeightBasisLabel(code: string | null | undefined): string {
  if (code === 'FOLLOWER_SHARE') return '按粉丝数占比';
  if (code === 'COOPERATION_GMV_SHARE') return '按合作 GMV 占比';
  return code ?? '—';
}

export function refundTreatmentLabel(code: string | null | undefined): string {
  if (code === 'GROSS') return '毛额（不扣退款）';
  if (code === 'NET') return '净额（扣退款）';
  return code ?? '—';
}

export function commissionBasisLabel(code: string | null | undefined): string {
  if (code === 'COOPERATION_COMMISSION') return '用合作记录里的佣金';
  if (code === 'RATE_ON_ATTRIBUTED_GMV') return '按归因 GMV × 比例';
  return code ?? '—';
}

export function accrualStatusLabel(code: string | null | undefined): string {
  if (code === 'PENDING') return '待计提';
  if (code === 'ACCRUED') return '已计提';
  if (code === 'REVERSED') return '已冲销';
  return code ?? '—';
}

/** 能力维度里 `available=false` 的那些（页面上要显式列出来）。 */
export function unavailableDimensions(
  capabilities: AttributionCapabilities | null,
): AttributionCapabilityDimension[] {
  if (!capabilities) return [];
  return capabilities.dimensions.filter((dimension) => !dimension.available);
}

/**
 * 空态分两种，**不许混成一句话**：
 *
 * - 「还没有归因数据」＝ 库里一条归因记录都没有；
 * - 「筛选无结果」＝ 库里有数据，只是当前筛选条件筛不到。
 */
export function emptyStateText(total: number, filtered: boolean): string {
  if (total === 0) return '还没有归因数据：先在「归因规则」里配一套生效口径，再跑一次归因批次。';
  if (filtered) return '没有符合当前筛选条件的归因记录。放宽筛选条件再试。';
  return '还没有归因数据：先跑一次归因批次。';
}
