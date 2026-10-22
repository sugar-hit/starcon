import { api } from '../inventory/api';

/**
 * 14.4.1.2 优惠券与秒杀 前端模型（券这一半）。
 *
 * <p>蓝图：「管理优惠券（折扣码、满减、买赠）与秒杀活动：生成码、设置库存与限领、
 * 投放渠道（站内/站外/社媒），跟踪领取与核销。输入：券规则；投放计划；核销数据同步。
 * 输出：券记录 + 核销统计。关系：上游 = 5 定价；下游 = 平台营销接口、15 客户（定向发放）。
 * <strong>券的核销要与订单绑定</strong>，否则无法评估活动真实效果与补贴成本。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **门槛与面额是快照，不是实时价**。上游 5 定价在 catalog（跨服务不可读）⇒
 *    界面上要写明「建券时登记的定价口径」，不能让运营以为是实时联动价。
 * 2. **「本地已建」不等于「平台侧已建」**。平台营销接口未交付 ⇒ 页面常驻
 *    「平台侧还没有这张券」。
 * 3. **核销必须带订单标识**。表单里订单标识是必填，且为空时前端就拦下。
 * 4. **不可测算不是 0**。`subsidyCost === null` 显示「不可测算」，
 *    **不是 `0.00`** —— 0 会被当成「补贴成本是 0」的假事实。
 * 5. **限领看的是累计**。页面提示「每人限领」时要说清是「这个人在这张券上累计」。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * 让利额、补贴成本、核销率都是服务端 `BigDecimal` 的事，
 * 前端算一遍就会引入二进制浮点误差，而且会让「界面上的让利额」和「账上的让利额」对不上。
 */

/** 金额与数量一律 number（JSON number），只原样展示。 */
export type AdMoney = number | null;

/** 🔴 null = 不可比 / 不可测算，与 `0`（可比但真的是零）含义相反。 */
export type AdRatio = number | null;

export interface AdCouponCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface AdCouponCapabilities {
  dimensions: AdCouponCapabilityDimension[];
  couponTypes: string[];
  statuses: string[];
  syncStatuses: string[];
  claimKinds: string[];
  channelTypes: string[];
  pricingSnapshotNote: string;
  syncNote: string;
  recipientNote: string;
  subsidyNote: string;
  orderBindingNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface AdCouponOption {
  code: string;
  label: string;
}

/** 平台下拉项（**带 id** —— 登记券要的是平台 id，不只是编码）。 */
export interface AdCouponPlatformOption {
  id: string;
  code: string;
  name: string;
}

export interface AdCouponOptions {
  couponTypes: AdCouponOption[];
  statuses: AdCouponOption[];
  syncStatuses: AdCouponOption[];
  claimKinds: AdCouponOption[];
  channelTypes: AdCouponOption[];
  platforms: AdCouponPlatformOption[];
  platformCodes: string[];
  shopCodes: string[];
  currencies: string[];
  pricingSnapshotNote: string;
}

export interface AdCouponRow {
  id: string;
  couponCode: string;
  couponName: string;
  couponType: string;
  platformId: string;
  platformCode: string;
  shopCode: string;
  promotionId: string | null;
  discountRate: AdRatio;
  discountValue: AdMoney;
  thresholdAmount: AdMoney;
  maxDiscountAmount: AdMoney;
  buyQuantity: AdRatio;
  giftQuantity: AdRatio;
  currency: string;
  startAt: string | null;
  endAt: string | null;
  withinScheduleWindow: boolean;
  status: string;
  totalStock: AdRatio;
  generatedCount: AdRatio;
  claimedCount: AdRatio;
  redeemedCount: AdRatio;
  claimableCount: AdRatio;
  perUserLimit: AdRatio;
  platformSyncStatus: string;
  pendingPlatformWriteback: boolean;
  localChangedAt: string | null;
  channelCount: number;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AdCouponPage {
  items: AdCouponRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdCouponCodeRow {
  id: string;
  code: string;
  status: string;
  claimantKey: string;
  claimedAt: string | null;
  usedAt: string | null;
  orderRef: string;
  createdAt: string;
}

export interface AdCouponCodePage {
  items: AdCouponCodeRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdCouponChannelRow {
  id: string;
  channelType: string;
  placement: string;
  planStartAt: string | null;
  planEndAt: string | null;
  plannedReach: AdRatio;
  actualClaimCount: number;
  actualRedemptionCount: number;
  status: string;
  note: string;
  createdAt: string;
}

export interface AdCouponClaimRow {
  id: string;
  claimKind: string;
  recipientKey: string;
  channelType: string;
  code: string;
  quantity: AdRatio;
  claimedAt: string;
}

export interface AdCouponRedemptionRow {
  id: string;
  orderRef: string;
  code: string;
  orderAmount: AdMoney;
  redeemedAmount: AdMoney;
  subsidyCost: AdMoney;
  currency: string;
  redeemedAt: string;
}

export interface AdCouponChannelStat {
  channelType: string;
  placement: string;
  claimCount: number;
  redemptionCount: number;
  subsidyCost: AdMoney;
}

export interface AdCouponDailyStat {
  date: string;
  redemptionCount: number;
  subsidyCost: AdMoney;
}

export interface AdCouponStatistics {
  generatedCount: number;
  claimedCount: number;
  redeemedCount: number;
  redemptionRate: AdRatio;
  totalSubsidyCost: AdMoney;
  totalRedeemedAmount: AdMoney;
  unmeasurableRedemptions: number;
  channelBreakdown: AdCouponChannelStat[];
  dailyTrend: AdCouponDailyStat[];
  subsidyNote: string;
}

export interface AdCouponDetail {
  coupon: AdCouponRow;
  channels: AdCouponChannelRow[];
  recentClaims: AdCouponClaimRow[];
  recentRedemptions: AdCouponRedemptionRow[];
  statistics: AdCouponStatistics;
  syncNote: string;
  pricingSnapshotNote: string;
  note: string;
}

// ==================================================================
// 请求
// ==================================================================

export interface SaveCouponInput {
  couponCode: string;
  couponName: string;
  couponType: string;
  platformId: string;
  shopCode: string;
  promotionId?: string | undefined;
  discountRate?: number | undefined;
  discountValue?: number | undefined;
  thresholdAmount?: number | undefined;
  maxDiscountAmount?: number | undefined;
  buyQuantity?: number | undefined;
  giftQuantity?: number | undefined;
  currency: string;
  startAt: string | null;
  endAt: string | null;
  totalStock: number;
  perUserLimit?: number | undefined;
  codePrefix: string;
  codeLength: number;
  codeCharset?: string | undefined;
  note: string;
}

export interface ChangeCouponStatusInput {
  status: string;
  reason: string;
}

export interface GenerateCouponCodesInput {
  count: number;
  codePrefix?: string | undefined;
  codeLength?: number | undefined;
  codeCharset?: string | undefined;
}

export interface ClaimCouponInput {
  claimKind: string;
  recipientKey: string;
  channelType?: string | undefined;
  channelId?: string | undefined;
  quantity?: number | undefined;
}

export interface RedeemCouponInput {
  orderRef: string;
  code?: string | undefined;
  orderAmount?: number | undefined;
}

export interface SaveCouponChannelInput {
  channelType: string;
  placement: string;
  planStartAt: string | null;
  planEndAt: string | null;
  plannedReach?: number | undefined;
  status: string;
  note: string;
}

/** 表单状态：金额与比率一律用**字符串**承载，避免输入过程中的浮点误差。 */
export interface AdCouponInputState {
  couponCode: string;
  couponName: string;
  couponType: string;
  platformId: string;
  shopCode: string;
  discountRate: string;
  discountValue: string;
  thresholdAmount: string;
  maxDiscountAmount: string;
  buyQuantity: string;
  giftQuantity: string;
  currency: string;
  startAt: string;
  endAt: string;
  totalStock: string;
  perUserLimit: string;
  codePrefix: string;
  codeLength: string;
  codeCharset: string;
  note: string;
}

export function emptyAdCouponInput(): AdCouponInputState {
  return {
    couponCode: '',
    couponName: '',
    couponType: 'DISCOUNT_CODE',
    platformId: '',
    shopCode: '',
    discountRate: '',
    discountValue: '',
    thresholdAmount: '',
    maxDiscountAmount: '',
    buyQuantity: '',
    giftQuantity: '',
    currency: 'USD',
    startAt: '',
    endAt: '',
    totalStock: '0',
    perUserLimit: '1',
    codePrefix: '',
    codeLength: '8',
    codeCharset: '',
    note: '',
  };
}

/** 领取 / 定向发放表单。 */
export interface AdClaimInputState {
  claimKind: string;
  recipientKey: string;
  channelType: string;
  channelId: string;
  quantity: string;
}

export function emptyAdClaimInput(): AdClaimInputState {
  return {
    claimKind: 'PULL',
    recipientKey: '',
    channelType: 'ON_SITE',
    channelId: '',
    quantity: '1',
  };
}

/** 核销表单。 */
export interface AdRedeemInputState {
  orderRef: string;
  code: string;
  orderAmount: string;
}

export function emptyAdRedeemInput(): AdRedeemInputState {
  return { orderRef: '', code: '', orderAmount: '' };
}

/** 投放渠道 / 投放计划表单。 */
export interface AdCouponChannelInputState {
  channelType: string;
  placement: string;
  planStartAt: string;
  planEndAt: string;
  plannedReach: string;
  status: string;
  note: string;
}

export function emptyAdCouponChannelInput(): AdCouponChannelInputState {
  return {
    channelType: 'ON_SITE',
    placement: '',
    planStartAt: '',
    planEndAt: '',
    plannedReach: '',
    status: 'PLANNED',
    note: '',
  };
}

// ==================================================================
// 接口
// ==================================================================

function queryString(query: object): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : '';
}

export function fetchAdCouponCapabilities(): Promise<AdCouponCapabilities> {
  return api<AdCouponCapabilities>('channel/ad-coupons/capabilities');
}

export function fetchAdCouponOptions(): Promise<AdCouponOptions> {
  return api<AdCouponOptions>('channel/ad-coupons/options');
}

export function fetchAdCoupons(
  query: {
    status?: string;
    couponType?: string;
    platformId?: string;
    shopCode?: string;
    channelType?: string;
    syncStatus?: string;
    keyword?: string;
    page?: number;
    size?: number;
  } = {},
): Promise<AdCouponPage> {
  return api<AdCouponPage>(`channel/ad-coupons/coupons${queryString(query)}`);
}

export function fetchAdCoupon(id: string): Promise<AdCouponDetail> {
  return api<AdCouponDetail>(`channel/ad-coupons/coupons/${id}`);
}

export function saveAdCoupon(input: SaveCouponInput): Promise<AdCouponDetail> {
  return api<AdCouponDetail>('channel/ad-coupons/coupons', 'POST', input);
}

export function updateAdCoupon(id: string, input: SaveCouponInput): Promise<AdCouponDetail> {
  return api<AdCouponDetail>(`channel/ad-coupons/coupons/${id}`, 'POST', input);
}

export function changeAdCouponStatus(
  id: string,
  input: ChangeCouponStatusInput,
): Promise<AdCouponDetail> {
  return api<AdCouponDetail>(`channel/ad-coupons/coupons/${id}/status`, 'POST', input);
}

export function generateAdCouponCodes(
  id: string,
  input: GenerateCouponCodesInput,
): Promise<AdCouponCodePage> {
  return api<AdCouponCodePage>(`channel/ad-coupons/coupons/${id}/codes`, 'POST', input);
}

export function fetchAdCouponCodes(
  id: string,
  query: { status?: string; page?: number; size?: number } = {},
): Promise<AdCouponCodePage> {
  return api<AdCouponCodePage>(`channel/ad-coupons/coupons/${id}/codes${queryString(query)}`);
}

export function claimAdCoupon(id: string, input: ClaimCouponInput): Promise<AdCouponDetail> {
  return api<AdCouponDetail>(`channel/ad-coupons/coupons/${id}/claims`, 'POST', input);
}

export function redeemAdCoupon(id: string, input: RedeemCouponInput): Promise<AdCouponDetail> {
  return api<AdCouponDetail>(`channel/ad-coupons/coupons/${id}/redemptions`, 'POST', input);
}

export function fetchAdCouponStatistics(id: string): Promise<AdCouponStatistics> {
  return api<AdCouponStatistics>(`channel/ad-coupons/coupons/${id}/statistics`);
}

export function fetchAdCouponChannels(id: string): Promise<AdCouponChannelRow[]> {
  return api<AdCouponChannelRow[]>(`channel/ad-coupons/coupons/${id}/channels`);
}

export function saveAdCouponChannel(
  id: string,
  input: SaveCouponChannelInput,
): Promise<AdCouponChannelRow> {
  return api<AdCouponChannelRow>(`channel/ad-coupons/coupons/${id}/channels`, 'POST', input);
}

// ==================================================================
// 展示辅助
// ==================================================================

/** 金额原样展示（服务端算好的，前端不做算术）。null = 不可比 / 不可测算。 */
export function money(value: AdMoney | undefined, currency?: string): string {
  if (value === null || value === undefined) return '不可比';
  const fixed = value.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

/**
 * 补贴成本展示：**不可测算显示「不可测算」，不是 0.00**。
 *
 * 🔴 0 是「可比，但真的是零」；null 是「压根算不出来」（买赠券缺成本价）。
 */
export function subsidyText(value: AdMoney | undefined): string {
  if (value === null || value === undefined) return '不可测算';
  return value.toFixed(2);
}

/** 折扣率展示：null = 不可比，不是 0.00%。 */
export function ratioText(value: AdRatio | undefined): string {
  if (value === null || value === undefined) return '不可比';
  return `${(value * 100).toFixed(2)}%`;
}

/** 数量展示：null = 不可比，不是 0。 */
export function countText(value: AdRatio | undefined): string {
  if (value === null || value === undefined) return '—';
  return String(value);
}

/** 🔴 平台同步状态：必须把「本地已建、平台侧还没有」说清楚。 */
export function syncStatusLabel(status: string): string {
  switch (status) {
    case 'LOCAL_ONLY':
      return '本地已建，平台侧还没有';
    case 'PENDING_WRITEBACK':
      return '回写已发起，待平台确认';
    case 'SYNCED':
      return '平台侧已一致';
    case 'FAILED':
      return '回写失败';
    default:
      return status;
  }
}

export function couponTypeLabel(type: string): string {
  switch (type) {
    case 'DISCOUNT_CODE':
      return '折扣码';
    case 'FULL_REDUCTION':
      return '满减';
    case 'BOGO':
      return '买赠';
    default:
      return type;
  }
}

export function couponStatusLabel(status: string): string {
  switch (status) {
    case 'DRAFT':
      return '草稿';
    case 'ACTIVE':
      return '生效中';
    case 'PAUSED':
      return '已暂停';
    case 'ENDED':
      return '已结束';
    case 'CANCELLED':
      return '已停用';
    default:
      return status;
  }
}

export function couponStatusTone(status: string): string {
  if (status === 'ACTIVE') return 'text-emerald-600';
  if (status === 'PAUSED' || status === 'CANCELLED') return 'text-amber-600';
  return 'text-muted-foreground';
}

export function claimKindLabel(kind: string): string {
  switch (kind) {
    case 'PULL':
      return '用户主动领取';
    case 'PUSH':
      return '定向发放';
    case 'CHANNEL':
      return '渠道投放领取';
    default:
      return kind;
  }
}

export function channelTypeLabel(type: string): string {
  switch (type) {
    case 'ON_SITE':
      return '站内';
    case 'OFF_SITE':
      return '站外';
    case 'SOCIAL':
      return '社媒';
    case 'DIRECT':
      return '定向发放';
    default:
      return type;
  }
}

export function couponCodeStatusLabel(status: string): string {
  switch (status) {
    case 'GENERATED':
      return '已生成未领';
    case 'CLAIMED':
      return '已领取';
    case 'USED':
      return '已核销';
    case 'EXPIRED':
      return '已过期';
    case 'VOID':
      return '已作废';
    default:
      return status;
  }
}

export function timestampText(value: string | null): string {
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
export function localDateTimeInput(value: string | null): string {
  if (!value) return '';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())}T${pad(
    parsed.getHours(),
  )}:${pad(parsed.getMinutes())}`;
}

// ==================================================================
// 表单校验（与服务端同口径，错了也别让用户白跑一趟）
// ==================================================================

function isNonNegativeAmount(raw: string): boolean {
  if (raw.trim() === '') return true;
  const value = Number(raw);
  return !Number.isNaN(value) && value >= 0;
}

function isPositiveAmount(raw: string): boolean {
  if (raw.trim() === '') return false;
  const value = Number(raw);
  return !Number.isNaN(value) && value > 0;
}

function isPositiveRatio(raw: string): boolean {
  if (raw.trim() === '') return false;
  const value = Number(raw);
  return !Number.isNaN(value) && value > 0 && value <= 1;
}

export function validateAdCoupon(input: AdCouponInputState): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.couponCode.trim()) errors.push('券编码必填');
  if (!input.couponName.trim()) errors.push('券名称必填');
  if (!input.shopCode.trim()) errors.push('参与店铺必填');
  if (!input.platformId.trim()) errors.push('平台必填');
  if (input.currency.trim() && input.currency.trim().length !== 3) {
    errors.push('币种必须是 3 位字母代码');
  }
  if (!isNonNegativeAmount(input.totalStock)) errors.push('库存必须是不小于 0 的数字');

  const hasRate = input.discountRate.trim() !== '';
  const hasValue = input.discountValue.trim() !== '';
  const hasThreshold = input.thresholdAmount.trim() !== '';
  const hasCap = input.maxDiscountAmount.trim() !== '';
  const hasBuy = input.buyQuantity.trim() !== '';
  const hasGift = input.giftQuantity.trim() !== '';

  if (input.couponType === 'DISCOUNT_CODE') {
    if (hasRate === hasValue) {
      errors.push('折扣码必须给折扣率**或**固定面额其中一个（两个都填让利额会有两个答案）');
    }
    if (hasRate && !isPositiveRatio(input.discountRate)) {
      errors.push('折扣率必须在 (0, 1] 之间');
    }
    if (hasValue && !isNonNegativeAmount(input.discountValue)) {
      errors.push('面额必须是不小于 0 的数字');
    }
    if (hasThreshold) errors.push('折扣码不能给满减门槛');
    if (hasBuy || hasGift) errors.push('折扣码不能给买赠数量');
  } else if (input.couponType === 'FULL_REDUCTION') {
    if (!hasValue) errors.push('满减券必须给减免额');
    else if (!isNonNegativeAmount(input.discountValue)) {
      errors.push('减免额必须是不小于 0 的数字');
    }
    if (!hasThreshold) errors.push('满减券必须给门槛（适用订单最低金额）');
    else if (!isNonNegativeAmount(input.thresholdAmount)) {
      errors.push('门槛必须是不小于 0 的数字');
    }
    if (hasRate) errors.push('满减券不能给折扣率');
    if (hasBuy || hasGift) errors.push('满减券不能给买赠数量');
  } else {
    if (!hasBuy || !hasGift) errors.push('买赠券必须给买 N 与送 M');
    if (hasBuy && !isPositiveAmount(input.buyQuantity)) errors.push('买 N 必须大于 0');
    if (hasGift && !isPositiveAmount(input.giftQuantity)) errors.push('送 M 必须大于 0');
    if (hasRate || hasValue || hasThreshold || hasCap) {
      errors.push('买赠券没有金额口径，不能给折扣率 / 面额 / 门槛 / 封顶');
    }
  }

  if (hasCap && !isNonNegativeAmount(input.maxDiscountAmount)) {
    errors.push('单笔封顶必须是不小于 0 的数字');
  }
  if (input.perUserLimit.trim() !== '' && !isPositiveAmount(input.perUserLimit)) {
    errors.push('每人限领张数必须大于 0（留空 = 不限领）');
  }
  const codeLength = Number(input.codeLength);
  if (!Number.isInteger(codeLength) || codeLength < 4 || codeLength > 32) {
    errors.push('券码随机段长度必须是 4-32 的整数');
  }
  if (input.startAt && input.endAt && new Date(input.endAt) <= new Date(input.startAt)) {
    errors.push('失效时间必须晚于生效时间');
  }
  return { ok: errors.length === 0, errors };
}

export function validateAdClaim(input: AdClaimInputState): { ok: boolean; errors: string[] } {
  const errors: string[] = [];
  if (!input.recipientKey.trim()) {
    errors.push('领取人 / 收件人标识必填（定向发放填收件人标识）');
  }
  if (!isPositiveAmount(input.quantity)) errors.push('领取张数必须大于 0');
  return { ok: errors.length === 0, errors };
}

export function validateAdRedeem(input: AdRedeemInputState): { ok: boolean; errors: string[] } {
  const errors: string[] = [];
  // 🔴 蓝图加粗句：核销必须与订单绑定 —— 没有订单标识的核销写不进来。
  if (!input.orderRef.trim()) errors.push('订单标识必填 —— 核销必须与订单绑定');
  if (input.orderAmount.trim() !== '' && !isNonNegativeAmount(input.orderAmount)) {
    errors.push('订单金额必须是不小于 0 的数字');
  }
  return { ok: errors.length === 0, errors };
}

export function validateAdCouponChannel(input: AdCouponChannelInputState): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.placement.trim()) errors.push('投放位必填');
  if (input.plannedReach.trim() !== '' && !isNonNegativeAmount(input.plannedReach)) {
    errors.push('计划触达量必须是不小于 0 的数字');
  }
  if (
    input.planStartAt &&
    input.planEndAt &&
    new Date(input.planEndAt) <= new Date(input.planStartAt)
  ) {
    errors.push('投放结束时间必须晚于开始时间');
  }
  return { ok: errors.length === 0, errors };
}
