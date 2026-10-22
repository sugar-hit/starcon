import { api } from '../inventory/api';

/**
 * 14.5.1.2 建联与样品 前端模型。
 *
 * <p>蓝图：「达人建联流程：按规则筛选达人 → 模板化私信邀约（走官方接口）→
 * 寄样管理（样品单、物流、签收）→ 合作跟进 → 佣金设置。输入：达人筛选条件；邀约模板；
 * 样品寄送信息；佣金比例。输出：建联记录 + 样品单 + 合作状态跟踪。关系：上游 = 14.5.1.1；
 * 下游 = 8 仓库（样品出库）、10 物流（样品物流）、12 财务（佣金计提）。
 * <strong>建联必须走官方接口</strong>，批量私信是平台风控重点，第三方工具易导致账号风险。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「没录」不是 0**。`sampleValue` / `commissionRate` / `baseAmount` / `accruedAmount`
 *    为 `null` 表示**还没录**（不可比），界面显示「未录入」，**不是 0.00** ——
 *    0 会被读成「这个达人一件样品成本都没有」。
 * 2. **「本地已录」不是「下游已交付」**。样品的 `trackingAvailable` 恒为 false、
 *    计提的 `financePosted` 恒为 false，各自带 `ownerModule` —— 页面要写明
 *    「轨迹不可查（归 10 物流 管）」「未进费用归集（归 12 财务 管）」。
 * 3. **建联渠道只能是官方接口**。`contactChannel` 恒为 `OFFICIAL_API`，
 *    `idempotencyKey` 是必填的幂等键；页面**不提供任何「批量群发」按钮**。
 * 4. **样品签收不是人填的**。样品的 `statusDerived` 说明这个状态是**由包裹推导**的，
 *    页面上手工推进只能到「已寄出 / 已取消」。
 * 5. **批量建联只生成草稿**。`batchCreate` 的结果逐条带 `result`，
 *    页面必须显示「还需逐条登记平台回执才会真正发出」。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * 佣金比例、计提金额、样品成本都是服务端 `BigDecimal` 的事，
 * 前端算一遍就会引入二进制浮点误差，而且会让「界面上的钱」和「账上的钱」对不上。
 */

/** 金额与数量一律 number（JSON number），只原样展示。 */
export type InfluencerOutreachMoney = number | null;

/** 🔴 null = 还没录（不可比），与 `0`（录了，而且真的是零）含义相反。 */
export type InfluencerOutreachRatio = number | null;

export interface InfluencerOutreachCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface InfluencerOutreachTemplateVariable {
  name: string;
  label: string;
  required: boolean;
}

export interface InfluencerOutreachCapabilities {
  dimensions: InfluencerOutreachCapabilityDimension[];
  outreachStatuses: string[];
  sampleOrderStatuses: string[];
  shipmentStatuses: string[];
  followupTypes: string[];
  commissionTypes: string[];
  templateVariables: InfluencerOutreachTemplateVariable[];
  officialApiNote: string;
  rateLimitNote: string;
  downstreamNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface InfluencerOutreachOption {
  code: string;
  label: string;
}

export interface InfluencerOutreachPlatformOption {
  id: string;
  code: string;
  name: string;
}

export interface InfluencerOutreachOptions {
  outreachStatuses: InfluencerOutreachOption[];
  sampleOrderStatuses: InfluencerOutreachOption[];
  shipmentStatuses: InfluencerOutreachOption[];
  followupTypes: InfluencerOutreachOption[];
  commissionTypes: InfluencerOutreachOption[];
  commissionScopes: InfluencerOutreachOption[];
  accrualStatuses: InfluencerOutreachOption[];
  platforms: InfluencerOutreachPlatformOption[];
  platformCodes: string[];
  shopCodes: string[];
  templateVariables: InfluencerOutreachTemplateVariable[];
  officialApiNote: string;
  rateLimitNote: string;
  downstreamNote: string;
}

export interface InfluencerOutreachSegmentRow {
  id: string;
  segmentCode: string;
  segmentName: string;
  description: string;
  category: string;
  followerTier: string;
  minFollowerCount: number | null;
  minEngagementRate: InfluencerOutreachRatio;
  minGmv: InfluencerOutreachMoney;
  ratingTierCode: string;
  tagCodes: string;
  platformId: string | null;
  platformCode: string | null;
  shopCode: string;
  excludeBlacklisted: boolean;
  status: string;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface InfluencerOutreachSegmentPage {
  items: InfluencerOutreachSegmentRow[];
  total: number;
  page: number;
  size: number;
}

export interface InfluencerOutreachCandidate {
  id: string;
  displayName: string;
  handle: string;
  category: string;
  followerCount: number;
  followerTier: string;
  engagementRate: InfluencerOutreachRatio;
  gmv: InfluencerOutreachMoney;
  gmvCurrency: string;
  platformCode: string;
  shopCode: string;
  blacklisted: boolean;
  hasOpenOutreach: boolean;
}

export interface InfluencerOutreachCandidatePage {
  items: InfluencerOutreachCandidate[];
  total: number;
  page: number;
  size: number;
}

export interface InfluencerOutreachTemplateRow {
  id: string;
  templateCode: string;
  templateName: string;
  platformId: string;
  platformCode: string;
  platformName: string;
  subject: string;
  body: string;
  status: string;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface InfluencerOutreachTemplatePage {
  items: InfluencerOutreachTemplateRow[];
  total: number;
  page: number;
  size: number;
}

export interface InfluencerOutreachTemplatePreview {
  templateId: string;
  subject: string;
  messageBody: string;
  values: Record<string, string>;
  note: string;
}

export interface InfluencerOutreachPolicyRow {
  id: string;
  platformId: string;
  platformCode: string;
  platformName: string;
  dailySendLimit: number;
  minIntervalSeconds: number;
  status: string;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface InfluencerOutreachPolicyPage {
  items: InfluencerOutreachPolicyRow[];
  total: number;
  page: number;
  size: number;
}

export interface InfluencerOutreachRow {
  id: string;
  influencerId: string;
  influencerName: string;
  influencerCategory: string;
  segmentId: string;
  segmentCode: string;
  segmentName: string;
  templateId: string;
  templateCode: string;
  templateName: string;
  platformId: string;
  platformCode: string;
  shopCode: string;
  contactChannel: string;
  idempotencyKey: string;
  subject: string;
  messageBody: string;
  status: string;
  platformMessageId: string;
  platformReceipt: string;
  sentAt: string | null;
  repliedAt: string | null;
  closedAt: string | null;
  followupCount: number;
  sampleOrderCount: number;
  accrualCount: number;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface InfluencerOutreachPage {
  items: InfluencerOutreachRow[];
  total: number;
  page: number;
  size: number;
}

export interface InfluencerOutreachBatchItem {
  influencerId: string;
  influencerName: string;
  outreachId: string | null;
  idempotencyKey: string;
  created: boolean;
  result: string;
  message: string;
}

export interface InfluencerOutreachBatchResult {
  matchedCount: number;
  createdCount: number;
  skippedCount: number;
  items: InfluencerOutreachBatchItem[];
  note: string;
}

export interface InfluencerOutreachPlatformAvailability {
  platformCode: string;
  platformName: string;
  available: boolean;
  status: string;
  reason: string;
  sentToday: number;
  dailySendLimit: number;
}

export interface InfluencerOutreachAvailability {
  platforms: InfluencerOutreachPlatformAvailability[];
  officialApiNote: string;
  rateLimitNote: string;
}

export interface InfluencerOutreachSendResult {
  sent: boolean;
  message: string;
  platformMessageId: string;
}

export interface InfluencerOutreachFollowupRow {
  id: string;
  outreachId: string;
  followupType: string;
  content: string;
  occurredAt: string;
  nextFollowupAt: string | null;
  ownerUserId: string | null;
  note: string;
  createdAt: string;
  updatedAt: string;
}

export interface InfluencerOutreachFollowupPage {
  items: InfluencerOutreachFollowupRow[];
  total: number;
  page: number;
  size: number;
}

export interface InfluencerSampleOrderRow {
  id: string;
  outreachId: string;
  influencerId: string;
  influencerName: string;
  shopCode: string;
  skuCode: string;
  skuName: string;
  quantity: InfluencerOutreachMoney;
  sampleValue: InfluencerOutreachMoney;
  sampleCurrency: string;
  recipientName: string;
  recipientPhone: string;
  recipientAddress: string;
  status: string;
  statusDerived: boolean;
  outboundStatus: string;
  outboundAt: string | null;
  warehouseOutboundRef: string;
  warehouseOwnerModule: string;
  trackingAvailable: boolean;
  trackingOwnerModule: string;
  signedAt: string | null;
  signedBy: string;
  shipmentCount: number;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface InfluencerSampleOrderPage {
  items: InfluencerSampleOrderRow[];
  total: number;
  page: number;
  size: number;
}

export interface InfluencerSampleShipmentRow {
  id: string;
  sampleOrderId: string;
  carrierCode: string;
  trackingNo: string;
  packageCount: InfluencerOutreachMoney;
  status: string;
  shippedAt: string;
  deliveredAt: string | null;
  signedAt: string | null;
  signedBy: string;
  trackingAvailable: boolean;
  trackingOwnerModule: string;
  note: string;
  createdAt: string;
  updatedAt: string;
}

export interface InfluencerSampleShipmentPage {
  items: InfluencerSampleShipmentRow[];
  total: number;
  page: number;
  size: number;
}

export interface InfluencerCommissionRuleRow {
  id: string;
  ruleCode: string;
  ruleName: string;
  scopeType: string;
  segmentId: string | null;
  segmentName: string | null;
  influencerId: string | null;
  influencerName: string | null;
  commissionType: string;
  commissionRate: InfluencerOutreachRatio;
  fixedAmount: InfluencerOutreachMoney;
  currency: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  status: string;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface InfluencerCommissionRulePage {
  items: InfluencerCommissionRuleRow[];
  total: number;
  page: number;
  size: number;
}

export interface InfluencerCommissionAccrualRow {
  id: string;
  outreachId: string;
  influencerId: string;
  influencerName: string;
  ruleId: string | null;
  ruleCode: string | null;
  commissionType: string;
  commissionRate: InfluencerOutreachRatio;
  fixedAmount: InfluencerOutreachMoney;
  baseAmount: InfluencerOutreachMoney;
  baseCurrency: string;
  accruedAmount: InfluencerOutreachMoney;
  accruedCurrency: string;
  status: string;
  financePosted: boolean;
  financeOwnerModule: string;
  accruedAt: string;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface InfluencerCommissionAccrualPage {
  items: InfluencerCommissionAccrualRow[];
  total: number;
  page: number;
  size: number;
}

export interface InfluencerOutreachDetail {
  outreach: InfluencerOutreachRow;
  followups: InfluencerOutreachFollowupRow[];
  sampleOrders: InfluencerSampleOrderRow[];
  accruals: InfluencerCommissionAccrualRow[];
  officialApiNote: string;
  rateLimitNote: string;
  downstreamNote: string;
  note: string;
}

export interface InfluencerOutreachSummary {
  totalOutreachs: number;
  draftCount: number;
  sentCount: number;
  repliedCount: number;
  cooperatingCount: number;
  completedCount: number;
  closedCount: number;
  sampleOrderCount: number;
  signedSampleCount: number;
  pendingSignCount: number;
  accrualCount: number;
  accruedAmountTotal: InfluencerOutreachMoney;
  accruedCurrency: string;
  downstreamNote: string;
  asOf: string;
}

// ==================================================================
// 表单状态
// ==================================================================

export interface InfluencerOutreachSegmentInput {
  segmentCode: string;
  segmentName: string;
  description: string;
  category: string;
  followerTier: string;
  minFollowerCount: string;
  minEngagementRate: string;
  minGmv: string;
  ratingTierCode: string;
  tagCodes: string;
  platformId: string;
  shopCode: string;
  excludeBlacklisted: boolean;
  status: string;
  note: string;
}

export interface InfluencerOutreachTemplateInput {
  templateCode: string;
  templateName: string;
  platformId: string;
  subject: string;
  body: string;
  status: string;
  note: string;
}

export interface InfluencerOutreachPolicyInput {
  platformId: string;
  dailySendLimit: string;
  minIntervalSeconds: string;
  status: string;
  note: string;
}

export interface InfluencerOutreachInput {
  influencerId: string;
  segmentId: string;
  templateId: string;
  idempotencyKey: string;
  note: string;
}

export interface InfluencerSampleOrderInput {
  outreachId: string;
  skuCode: string;
  skuName: string;
  quantity: string;
  sampleValue: string;
  sampleCurrency: string;
  recipientName: string;
  recipientPhone: string;
  recipientAddress: string;
  note: string;
}

export interface InfluencerCommissionRuleInput {
  ruleCode: string;
  ruleName: string;
  scopeType: string;
  segmentId: string;
  influencerId: string;
  commissionType: string;
  commissionRate: string;
  fixedAmount: string;
  currency: string;
  effectiveFrom: string;
  effectiveTo: string;
  status: string;
  note: string;
}

export interface InfluencerCommissionAccrualInput {
  outreachId: string;
  ruleId: string;
  baseAmount: string;
  baseCurrency: string;
  note: string;
}

export interface InfluencerOutreachQuery {
  influencerId?: string;
  segmentId?: string;
  templateId?: string;
  platformCode?: string;
  shopCode?: string;
  status?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export interface InfluencerSampleOrderQuery {
  outreachId?: string;
  influencerId?: string;
  status?: string;
  shopCode?: string;
  skuCode?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export interface InfluencerCommissionAccrualQuery {
  outreachId?: string;
  influencerId?: string;
  status?: string;
  sort?: string;
  page?: number;
  size?: number;
}

// ==================================================================
// 接口
// ==================================================================

const base = 'channel/outreach';

function queryString(query: object): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : '';
}

export function fetchInfluencerOutreachCapabilities(): Promise<InfluencerOutreachCapabilities> {
  return api<InfluencerOutreachCapabilities>(`${base}/capabilities`);
}

export function fetchInfluencerOutreachOptions(): Promise<InfluencerOutreachOptions> {
  return api<InfluencerOutreachOptions>(`${base}/options`);
}

export function fetchInfluencerOutreachSummary(): Promise<InfluencerOutreachSummary> {
  return api<InfluencerOutreachSummary>(`${base}/summary`);
}

export function fetchInfluencerOutreachAvailability(): Promise<InfluencerOutreachAvailability> {
  return api<InfluencerOutreachAvailability>(`${base}/outreach-availability`);
}

// ---- 筛选条件 ----

export function fetchInfluencerOutreachSegments(
  query: { status?: string; keyword?: string; page?: number; size?: number } = {},
): Promise<InfluencerOutreachSegmentPage> {
  return api<InfluencerOutreachSegmentPage>(`${base}/segments${queryString(query)}`);
}

export function saveInfluencerOutreachSegment(input: InfluencerOutreachSegmentInput) {
  return api<InfluencerOutreachSegmentRow>(`${base}/segments`, 'POST', segmentPayload(input));
}

export function updateInfluencerOutreachSegment(
  id: string,
  input: InfluencerOutreachSegmentInput,
) {
  return api<InfluencerOutreachSegmentRow>(`${base}/segments/${id}`, 'POST', segmentPayload(input));
}

export function deleteInfluencerOutreachSegment(id: string): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(`${base}/segments/${id}`, 'DELETE');
}

export function fetchInfluencerOutreachCandidates(
  id: string,
  query: { keyword?: string; page?: number; size?: number } = {},
): Promise<InfluencerOutreachCandidatePage> {
  return api<InfluencerOutreachCandidatePage>(
    `${base}/segments/${id}/candidates${queryString(query)}`,
  );
}

// ---- 邀约模板 ----

export function fetchInfluencerOutreachTemplates(
  query: { platformCode?: string; status?: string; keyword?: string; page?: number; size?: number } = {},
): Promise<InfluencerOutreachTemplatePage> {
  return api<InfluencerOutreachTemplatePage>(`${base}/templates${queryString(query)}`);
}

export function saveInfluencerOutreachTemplate(input: InfluencerOutreachTemplateInput) {
  return api<InfluencerOutreachTemplateRow>(`${base}/templates`, 'POST', templatePayload(input));
}

export function updateInfluencerOutreachTemplate(
  id: string,
  input: InfluencerOutreachTemplateInput,
) {
  return api<InfluencerOutreachTemplateRow>(`${base}/templates/${id}`, 'POST', templatePayload(input));
}

export function deleteInfluencerOutreachTemplate(id: string): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(`${base}/templates/${id}`, 'DELETE');
}

export function fetchInfluencerOutreachTemplatePreview(
  id: string,
  influencerId: string,
): Promise<InfluencerOutreachTemplatePreview> {
  return api<InfluencerOutreachTemplatePreview>(
    `${base}/templates/${id}/preview${queryString({ influencerId })}`,
  );
}

// ---- 风控策略 ----

export function fetchInfluencerOutreachPolicies(
  query: { platformCode?: string; page?: number; size?: number } = {},
): Promise<InfluencerOutreachPolicyPage> {
  return api<InfluencerOutreachPolicyPage>(`${base}/policies${queryString(query)}`);
}

export function saveInfluencerOutreachPolicy(input: InfluencerOutreachPolicyInput) {
  return api<InfluencerOutreachPolicyRow>(`${base}/policies`, 'POST', {
    platformId: input.platformId,
    dailySendLimit: input.dailySendLimit === '' ? undefined : Number(input.dailySendLimit),
    minIntervalSeconds:
      input.minIntervalSeconds === '' ? undefined : Number(input.minIntervalSeconds),
    status: input.status,
    note: input.note,
  });
}

// ---- 建联记录 ----

export function fetchInfluencerOutreachs(
  query: InfluencerOutreachQuery = {},
): Promise<InfluencerOutreachPage> {
  return api<InfluencerOutreachPage>(`${base}/outreachs${queryString(query)}`);
}

export function fetchInfluencerOutreach(id: string): Promise<InfluencerOutreachDetail> {
  return api<InfluencerOutreachDetail>(`${base}/outreachs/${id}`);
}

export function saveInfluencerOutreach(input: InfluencerOutreachInput) {
  return api<InfluencerOutreachRow>(`${base}/outreachs`, 'POST', {
    influencerId: input.influencerId,
    segmentId: input.segmentId,
    templateId: input.templateId,
    idempotencyKey: input.idempotencyKey,
    note: input.note,
  });
}

export function updateInfluencerOutreach(id: string, input: InfluencerOutreachInput) {
  return api<InfluencerOutreachRow>(`${base}/outreachs/${id}`, 'POST', {
    influencerId: input.influencerId,
    segmentId: input.segmentId,
    templateId: input.templateId,
    idempotencyKey: input.idempotencyKey,
    note: input.note,
  });
}

export function deleteInfluencerOutreach(id: string): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(`${base}/outreachs/${id}`, 'DELETE');
}

/** 🔴 手动登记「已发送 + 平台回执」（官方私信接口未交付时唯一的发送登记入口）。 */
export function registerInfluencerOutreachReceipt(
  id: string,
  input: { platformMessageId: string; platformReceipt: string; sentAt?: string; note?: string },
) {
  return api<InfluencerOutreachRow>(`${base}/outreachs/${id}/receipt`, 'POST', {
    platformMessageId: input.platformMessageId,
    platformReceipt: input.platformReceipt ?? '',
    sentAt: input.sentAt || undefined,
    note: input.note ?? '',
  });
}

/** 🔴 走官方接口发送（适配器未接入 ⇒ 后端明确失败且不写数据）。 */
export function sendInfluencerOutreach(id: string): Promise<InfluencerOutreachSendResult> {
  return api<InfluencerOutreachSendResult>(`${base}/outreachs/${id}/send`, 'POST');
}

export function transitionInfluencerOutreach(
  id: string,
  input: { status: string; occurredAt?: string; note?: string },
) {
  return api<InfluencerOutreachRow>(`${base}/outreachs/${id}/status`, 'POST', {
    status: input.status,
    occurredAt: input.occurredAt || undefined,
    note: input.note ?? '',
  });
}

/** 🔴 批量建联：**只生成草稿**，不是批量群发。 */
export function batchCreateInfluencerOutreach(input: {
  segmentId: string;
  templateId: string;
  // 🔴 写成 `string | undefined`（不是裸 `string`）：exactOptionalPropertyTypes 下，
  //   调用方习惯 `batchKey: form.batchKey || undefined`，裸 `?:` 会把它判成不兼容。
  batchKey?: string | undefined;
  limit?: number;
  note?: string;
}): Promise<InfluencerOutreachBatchResult> {
  return api<InfluencerOutreachBatchResult>(`${base}/outreachs/batch`, 'POST', {
    segmentId: input.segmentId,
    templateId: input.templateId,
    batchKey: input.batchKey || undefined,
    limit: input.limit,
    note: input.note ?? '',
  });
}

// ---- 合作跟进 ----

export function fetchInfluencerOutreachFollowups(
  query: { outreachId?: string; page?: number; size?: number } = {},
): Promise<InfluencerOutreachFollowupPage> {
  return api<InfluencerOutreachFollowupPage>(`${base}/followups${queryString(query)}`);
}

export function createInfluencerOutreachFollowup(
  outreachId: string,
  input: {
    followupType: string;
    content: string;
    occurredAt?: string;
    nextFollowupAt?: string | undefined;
    ownerUserId?: string;
    note?: string;
  },
) {
  return api<InfluencerOutreachFollowupRow>(`${base}/outreachs/${outreachId}/followups`, 'POST', {
    followupType: input.followupType,
    content: input.content,
    occurredAt: input.occurredAt || undefined,
    nextFollowupAt: input.nextFollowupAt || undefined,
    ownerUserId: input.ownerUserId || undefined,
    note: input.note ?? '',
  });
}

// ---- 样品单与包裹 ----

export function fetchInfluencerSampleOrders(
  query: InfluencerSampleOrderQuery = {},
): Promise<InfluencerSampleOrderPage> {
  return api<InfluencerSampleOrderPage>(`${base}/samples${queryString(query)}`);
}

export function createInfluencerSampleOrder(input: InfluencerSampleOrderInput) {
  return api<InfluencerSampleOrderRow>(`${base}/samples`, 'POST', samplePayload(input));
}

export function updateInfluencerSampleOrder(id: string, input: InfluencerSampleOrderInput) {
  return api<InfluencerSampleOrderRow>(`${base}/samples/${id}`, 'POST', samplePayload(input));
}

export function transitionInfluencerSampleOrder(
  id: string,
  input: { status: string; outboundAt?: string; note?: string },
) {
  return api<InfluencerSampleOrderRow>(`${base}/samples/${id}/status`, 'POST', {
    status: input.status,
    outboundAt: input.outboundAt || undefined,
    note: input.note ?? '',
  });
}

export function fetchInfluencerSampleShipments(
  query: { sampleOrderId?: string; page?: number; size?: number } = {},
): Promise<InfluencerSampleShipmentPage> {
  return api<InfluencerSampleShipmentPage>(`${base}/shipments${queryString(query)}`);
}

export function createInfluencerSampleShipment(
  sampleOrderId: string,
  input: { carrierCode: string; trackingNo: string; packageCount: string; shippedAt?: string; note?: string },
) {
  return api<InfluencerSampleShipmentRow>(`${base}/samples/${sampleOrderId}/shipments`, 'POST', {
    carrierCode: input.carrierCode,
    trackingNo: input.trackingNo,
    packageCount: input.packageCount === '' ? undefined : Number(input.packageCount),
    shippedAt: input.shippedAt || undefined,
    note: input.note ?? '',
  });
}

export function transitionInfluencerSampleShipment(
  id: string,
  input: { status: string; occurredAt?: string; signedBy?: string; note?: string },
) {
  return api<InfluencerSampleShipmentRow>(`${base}/shipments/${id}/status`, 'POST', {
    status: input.status,
    occurredAt: input.occurredAt || undefined,
    signedBy: input.signedBy ?? '',
    note: input.note ?? '',
  });
}

// ---- 佣金 ----

export function fetchInfluencerCommissionRules(
  query: { scopeType?: string; status?: string; keyword?: string; page?: number; size?: number } = {},
): Promise<InfluencerCommissionRulePage> {
  return api<InfluencerCommissionRulePage>(`${base}/commission-rules${queryString(query)}`);
}

export function saveInfluencerCommissionRule(input: InfluencerCommissionRuleInput) {
  return api<InfluencerCommissionRuleRow>(`${base}/commission-rules`, 'POST', rulePayload(input));
}

export function updateInfluencerCommissionRule(id: string, input: InfluencerCommissionRuleInput) {
  return api<InfluencerCommissionRuleRow>(`${base}/commission-rules/${id}`, 'POST', rulePayload(input));
}

export function deleteInfluencerCommissionRule(id: string): Promise<{ deleted: boolean }> {
  return api<{ deleted: boolean }>(`${base}/commission-rules/${id}`, 'DELETE');
}

export function fetchInfluencerCommissionAccruals(
  query: InfluencerCommissionAccrualQuery = {},
): Promise<InfluencerCommissionAccrualPage> {
  return api<InfluencerCommissionAccrualPage>(`${base}/commission-accruals${queryString(query)}`);
}

export function createInfluencerCommissionAccrual(input: InfluencerCommissionAccrualInput) {
  return api<InfluencerCommissionAccrualRow>(`${base}/commission-accruals`, 'POST', {
    outreachId: input.outreachId,
    ruleId: input.ruleId || undefined,
    baseAmount: input.baseAmount === '' ? undefined : Number(input.baseAmount),
    baseCurrency: input.baseCurrency || undefined,
    note: input.note ?? '',
  });
}

// ==================================================================
// 请求载荷（把表单字符串转成后端要的形状）
// ==================================================================

function segmentPayload(input: InfluencerOutreachSegmentInput) {
  return {
    segmentCode: input.segmentCode.trim(),
    segmentName: input.segmentName.trim(),
    description: input.description.trim(),
    category: input.category.trim(),
    followerTier: input.followerTier.trim(),
    minFollowerCount: input.minFollowerCount === '' ? undefined : Number(input.minFollowerCount),
    minEngagementRate:
      input.minEngagementRate === '' ? undefined : Number(input.minEngagementRate),
    minGmv: input.minGmv === '' ? undefined : Number(input.minGmv),
    ratingTierCode: input.ratingTierCode.trim(),
    tagCodes: input.tagCodes.trim(),
    platformId: input.platformId || undefined,
    shopCode: input.shopCode.trim(),
    excludeBlacklisted: input.excludeBlacklisted,
    status: input.status,
    note: input.note.trim(),
  };
}

function templatePayload(input: InfluencerOutreachTemplateInput) {
  return {
    templateCode: input.templateCode.trim(),
    templateName: input.templateName.trim(),
    platformId: input.platformId,
    subject: input.subject.trim(),
    body: input.body.trim(),
    status: input.status,
    note: input.note.trim(),
  };
}

function samplePayload(input: InfluencerSampleOrderInput) {
  return {
    outreachId: input.outreachId,
    skuCode: input.skuCode.trim(),
    skuName: input.skuName.trim(),
    quantity: input.quantity === '' ? undefined : Number(input.quantity),
    sampleValue: input.sampleValue === '' ? undefined : Number(input.sampleValue),
    sampleCurrency: input.sampleCurrency.trim(),
    recipientName: input.recipientName.trim(),
    recipientPhone: input.recipientPhone.trim(),
    recipientAddress: input.recipientAddress.trim(),
    note: input.note.trim(),
  };
}

function rulePayload(input: InfluencerCommissionRuleInput) {
  return {
    ruleCode: input.ruleCode.trim(),
    ruleName: input.ruleName.trim(),
    scopeType: input.scopeType,
    segmentId: input.segmentId || undefined,
    influencerId: input.influencerId || undefined,
    commissionType: input.commissionType,
    commissionRate: input.commissionRate === '' ? undefined : Number(input.commissionRate),
    fixedAmount: input.fixedAmount === '' ? undefined : Number(input.fixedAmount),
    currency: input.currency.trim(),
    effectiveFrom: input.effectiveFrom || undefined,
    effectiveTo: input.effectiveTo || undefined,
    status: input.status,
    note: input.note.trim(),
  };
}

// ==================================================================
// 展示辅助
// ==================================================================

/** 🔴 金额的展示：null 显示「未录入」，不是 0.00。 */
export function outreachMoney(value: InfluencerOutreachMoney | undefined, currency?: string): string {
  if (value === null || value === undefined) return '未录入';
  const fixed = value.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

/** 🔴 比率的展示：null 显示「未录入」，不是 0.00%。 */
export function outreachRatio(value: InfluencerOutreachRatio | undefined): string {
  if (value === null || value === undefined) return '未录入';
  return `${(value * 100).toFixed(2)}%`;
}

/** 🔴 数量的展示：null 显示「未录入」，不是 0。 */
export function outreachCount(value: InfluencerOutreachMoney | undefined): string {
  if (value === null || value === undefined) return '未录入';
  return Number(value).toLocaleString('zh-CN');
}

export function outreachStatusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'DRAFT':
      return '草稿（还没发出去）';
    case 'SENT':
      return '已发送';
    case 'REPLIED':
      return '已回复';
    case 'ACCEPTED':
      return '已接受合作';
    case 'REJECTED':
      return '已拒绝';
    case 'NO_REPLY':
      return '无回复';
    case 'COOPERATING':
      return '合作中';
    case 'COMPLETED':
      return '已完成';
    case 'CANCELLED':
      return '已取消';
    default:
      return status ?? '—';
  }
}

export function sampleOrderStatusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'REQUESTED':
      return '已申请（待出库）';
    case 'OUTBOUNDED':
      return '已寄出';
    case 'DELIVERED':
      return '已送达';
    case 'SIGNED':
      return '已签收';
    case 'CANCELLED':
      return '已取消';
    default:
      return status ?? '—';
  }
}

export function shipmentStatusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'SHIPPED':
      return '已发出';
    case 'IN_TRANSIT':
      return '运输中';
    case 'DELIVERED':
      return '已送达';
    case 'SIGNED':
      return '已签收';
    case 'LOST':
      return '丢件';
    case 'RETURNED':
      return '已退回';
    default:
      return status ?? '—';
  }
}

export function followupTypeLabel(type: string | null | undefined): string {
  switch (type) {
    case 'CALL':
      return '电话';
    case 'IM':
      return '站内私信';
    case 'EMAIL':
      return '邮件';
    case 'SAMPLE_FEEDBACK':
      return '样品反馈';
    case 'NEGOTIATION':
      return '谈判';
    case 'OTHER':
      return '其它';
    default:
      return type ?? '—';
  }
}

export function commissionTypeLabel(type: string | null | undefined): string {
  switch (type) {
    case 'RATE':
      return '按比例';
    case 'FIXED':
      return '固定额';
    default:
      return type ?? '—';
  }
}

export function commissionScopeLabel(scope: string | null | undefined): string {
  switch (scope) {
    case 'GLOBAL':
      return '全局';
    case 'SEGMENT':
      return '按分群';
    case 'INFLUENCER':
      return '按达人';
    default:
      return scope ?? '—';
  }
}

export function accrualStatusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'PENDING':
      return '待确认';
    case 'ACCRUED':
      return '已计提（本地）';
    case 'POSTED':
      return '已入财务';
    case 'CANCELLED':
      return '已取消';
    default:
      return status ?? '—';
  }
}

export function configStatusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'ACTIVE':
      return '启用';
    case 'PAUSED':
      return '停用';
    default:
      return status ?? '—';
  }
}

export function outreachTimestamp(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/**
 * 🔴 物流轨迹的展示：**恒为「不可查」**，并说清归谁管。
 *
 * 10 物流未交付 ⇒ 样品只有本地登记的物流单号，真实轨迹查不到。
 */
export function trackingText(row: {
  trackingAvailable: boolean;
  trackingOwnerModule: string;
}): string {
  if (row.trackingAvailable) return '轨迹可查';
  return `轨迹不可查（归 ${row.trackingOwnerModule} 管）`;
}

/**
 * 🔴 财务的展示：**恒为「未进费用归集」**，并说清归谁管。
 *
 * 12 财务未交付 ⇒ 佣金计提只是本地事实。
 */
export function financeText(row: { financePosted: boolean; financeOwnerModule: string }): string {
  if (row.financePosted) return '已入财务';
  return `未进费用归集（归 ${row.financeOwnerModule} 管）`;
}

/**
 * 🔴 8 仓库出库的展示：本地出库事实 + 责任模块。
 *
 * 仓库那边的真实单号未交付 ⇒ 恒为空串，页面显示「待 8 仓库交付后回填」。
 */
export function outboundText(row: {
  outboundStatus: string;
  outboundAt: string | null;
  warehouseOutboundRef: string;
  warehouseOwnerModule: string;
}): string {
  const local = row.outboundStatus === 'LOCAL_OUTBOUNDED' ? '本地已记出库' : '本地待出库';
  const ref = row.warehouseOutboundRef
    ? `仓库单号 ${row.warehouseOutboundRef}`
    : `仓库单号待 ${row.warehouseOwnerModule} 交付后回填`;
  return row.outboundAt ? `${local}（${outreachTimestamp(row.outboundAt)}）· ${ref}` : `${local} · ${ref}`;
}

/** 批量建联结果码 → 人话。 */
export function batchResultLabel(result: string | null | undefined): string {
  switch (result) {
    case 'CREATED':
      return '已生成草稿';
    case 'SKIPPED_OPEN':
      return '跳过（已有未走完的邀约）';
    case 'SKIPPED_DUPLICATE':
      return '跳过（同批次已建过）';
    case 'SKIPPED_TEMPLATE':
      return '跳过（模板渲染失败）';
    default:
      return result ?? '—';
  }
}

// ==================================================================
// 校验
// ==================================================================

export function validateInfluencerOutreachSegment(input: InfluencerOutreachSegmentInput): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.segmentCode.trim()) errors.push('筛选条件编码必填');
  if (!input.segmentName.trim()) errors.push('筛选条件名称必填');
  if (input.minEngagementRate !== '') {
    const value = Number(input.minEngagementRate);
    if (Number.isNaN(value) || value < 0 || value > 1) errors.push('互动率下限必须在 0 ~ 1 之间');
  }
  if (input.minGmv !== '') {
    const value = Number(input.minGmv);
    if (Number.isNaN(value) || value < 0) errors.push('GMV 下限不能是负数');
  }
  if (input.minFollowerCount !== '') {
    const value = Number(input.minFollowerCount);
    if (Number.isNaN(value) || value < 0) errors.push('粉丝数下限不能是负数');
  }
  return { ok: errors.length === 0, errors };
}

/**
 * 🔴 模板校验：占位符必须在**闭合词表**里。
 *
 * 服务端也会拒，但前端先说清楚「哪个占位符没登记」，比让运营提交后才看到
 * 一句「未登记的占位符：xxx」省事。
 */
export function validateInfluencerOutreachTemplate(
  input: InfluencerOutreachTemplateInput,
  variables: InfluencerOutreachTemplateVariable[],
): { ok: boolean; errors: string[] } {
  const errors: string[] = [];
  if (!input.templateCode.trim()) errors.push('模板编码必填');
  if (!input.templateName.trim()) errors.push('模板名称必填');
  if (!input.platformId) errors.push('平台必填');
  if (!input.body.trim()) errors.push('模板正文必填');
  const known = new Set(variables.map((v) => v.name));
  for (const placeholders of placeholdersOf(input.subject).concat(placeholdersOf(input.body))) {
    if (!known.has(placeholders)) errors.push(`未登记的占位符：${placeholders}`);
  }
  return { ok: errors.length === 0, errors };
}

/** 取出文本里的 `{{变量}}` 名字。 */
export function placeholdersOf(text: string): string[] {
  const found: string[] = [];
  const pattern = /\{\{\s*([^}]+?)\s*\}\}/g;
  let match = pattern.exec(text);
  while (match) {
    // 🔴 捕获组在 noUncheckedIndexedAccess 下是 `string | undefined`，
    //   而正则保证有捕获组 —— 显式窄化，别用 `!` 硬压。
    const name = match[1];
    if (name) found.push(name);
    match = pattern.exec(text);
  }
  return found;
}

export function validateInfluencerOutreach(input: InfluencerOutreachInput): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.influencerId) errors.push('达人必填');
  if (!input.segmentId) errors.push('筛选条件必填');
  if (!input.templateId) errors.push('模板必填');
  if (!input.idempotencyKey.trim()) errors.push('幂等键必填（重放同一条不会发第二遍）');
  return { ok: errors.length === 0, errors };
}

export function validateInfluencerSampleOrder(input: InfluencerSampleOrderInput): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.outreachId) errors.push('建联记录必填');
  if (!input.skuCode.trim()) errors.push('样品 SKU 编码必填');
  if (input.quantity !== '') {
    const value = Number(input.quantity);
    if (Number.isNaN(value) || value <= 0) errors.push('样品数量必须大于 0');
  }
  if (input.sampleValue !== '') {
    const value = Number(input.sampleValue);
    if (Number.isNaN(value) || value < 0) errors.push('样品成本不能是负数');
  }
  if (input.sampleCurrency.trim() && input.sampleCurrency.trim().length !== 3) {
    errors.push('币种必须是 3 位字母代码');
  }
  return { ok: errors.length === 0, errors };
}

export function validateInfluencerCommissionRule(input: InfluencerCommissionRuleInput): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.ruleCode.trim()) errors.push('规则编码必填');
  if (!input.ruleName.trim()) errors.push('规则名称必填');
  if (input.scopeType === 'SEGMENT' && !input.segmentId) errors.push('按分群时必须选筛选条件');
  if (input.scopeType === 'INFLUENCER' && !input.influencerId) errors.push('按达人时必须选达人');
  if (input.commissionType === 'RATE') {
    if (input.commissionRate === '') errors.push('按比例时必须填佣金比例');
    else {
      const value = Number(input.commissionRate);
      if (Number.isNaN(value) || value < 0 || value > 1) errors.push('佣金比例必须在 0 ~ 1 之间');
    }
    if (input.fixedAmount !== '') errors.push('按比例时不能同时填固定金额（混合形态算不清）');
  } else {
    if (input.fixedAmount === '') errors.push('固定额时必须填固定金额');
    else {
      const value = Number(input.fixedAmount);
      if (Number.isNaN(value) || value < 0) errors.push('固定金额不能是负数');
    }
    if (input.commissionRate !== '') errors.push('固定额时不能同时填佣金比例（混合形态算不清）');
  }
  return { ok: errors.length === 0, errors };
}

// ==================================================================
// 表单初值
// ==================================================================

export function emptyInfluencerOutreachSegmentInput(): InfluencerOutreachSegmentInput {
  return {
    segmentCode: '',
    segmentName: '',
    description: '',
    category: '',
    followerTier: '',
    minFollowerCount: '',
    minEngagementRate: '',
    minGmv: '',
    ratingTierCode: '',
    tagCodes: '',
    platformId: '',
    shopCode: '',
    excludeBlacklisted: true,
    status: 'ACTIVE',
    note: '',
  };
}

export function emptyInfluencerOutreachTemplateInput(): InfluencerOutreachTemplateInput {
  return {
    templateCode: '',
    templateName: '',
    platformId: '',
    subject: '',
    body: '',
    status: 'ACTIVE',
    note: '',
  };
}

export function emptyInfluencerOutreachPolicyInput(): InfluencerOutreachPolicyInput {
  return {
    platformId: '',
    dailySendLimit: '20',
    minIntervalSeconds: '60',
    status: 'ACTIVE',
    note: '',
  };
}

export function emptyInfluencerOutreachInput(): InfluencerOutreachInput {
  return {
    influencerId: '',
    segmentId: '',
    templateId: '',
    idempotencyKey: '',
    note: '',
  };
}

export function emptyInfluencerSampleOrderInput(): InfluencerSampleOrderInput {
  return {
    outreachId: '',
    skuCode: '',
    skuName: '',
    quantity: '1',
    sampleValue: '',
    sampleCurrency: '',
    recipientName: '',
    recipientPhone: '',
    recipientAddress: '',
    note: '',
  };
}

export function emptyInfluencerCommissionRuleInput(): InfluencerCommissionRuleInput {
  return {
    ruleCode: '',
    ruleName: '',
    scopeType: 'GLOBAL',
    segmentId: '',
    influencerId: '',
    commissionType: 'RATE',
    commissionRate: '0.1',
    fixedAmount: '',
    currency: 'USD',
    effectiveFrom: '',
    effectiveTo: '',
    status: 'ACTIVE',
    note: '',
  };
}

export function emptyInfluencerCommissionAccrualInput(): InfluencerCommissionAccrualInput {
  return {
    outreachId: '',
    ruleId: '',
    baseAmount: '',
    baseCurrency: '',
    note: '',
  };
}
