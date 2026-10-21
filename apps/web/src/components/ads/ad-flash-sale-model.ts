import { api } from '../inventory/api';

/**
 * 14.4.1.2 优惠券与秒杀 前端模型（秒杀这一半）。
 *
 * <p>蓝图：「管理优惠券（折扣码、满减、买赠）与**秒杀活动**：生成码、设置库存与限领、
 * 投放渠道（站内/站外/社媒），跟踪领取与核销。……<b>券的核销要与订单绑定</b>，
 * 否则无法评估活动真实效果与补贴成本。」
 *
 * 🔴 这个页面上最容易做丢的三件事：
 *
 * 1. **秒杀价与原价都是快照**。上游 5 定价在 catalog（跨服务不可读）⇒ 界面上要写明
 *    「建活动时登记的定价口径」。
 * 2. **「本地已建」不等于「平台侧已建」**。平台营销接口未交付 ⇒ 页面常驻
 *    「平台侧还没有这个秒杀活动」。
 * 3. **核销必须带订单标识**，且**不可比不是 0**：原价没登记时让利额显示「不可比」，
 *    **不是 `0.00`** —— 0 会被当成「这次秒杀没让利」的假事实。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术**。
 */

/** 金额与数量一律 number（JSON number），只原样展示。 */
export type AdFlashMoney = number | null;

/** 🔴 null = 不可比 / 不可测算，与 `0`（可比但真的是零）含义相反。 */
export type AdFlashRatio = number | null;

export interface AdFlashSaleCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface AdFlashSaleCapabilities {
  dimensions: AdFlashSaleCapabilityDimension[];
  statuses: string[];
  syncStatuses: string[];
  channelTypes: string[];
  pricingSnapshotNote: string;
  syncNote: string;
  orderBindingNote: string;
  marginNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface AdFlashSaleOption {
  code: string;
  label: string;
}

export interface AdFlashSalePlatformOption {
  id: string;
  code: string;
  name: string;
}

export interface AdFlashSaleOptions {
  statuses: AdFlashSaleOption[];
  syncStatuses: AdFlashSaleOption[];
  channelTypes: AdFlashSaleOption[];
  platforms: AdFlashSalePlatformOption[];
  shopCodes: string[];
  currencies: string[];
  pricingSnapshotNote: string;
}

export interface AdFlashSaleRow {
  id: string;
  saleCode: string;
  saleName: string;
  platformId: string;
  platformCode: string;
  shopCode: string;
  listingId: string;
  listingTitle: string;
  flashPrice: AdFlashMoney;
  originalPrice: AdFlashMoney;
  currency: string;
  startAt: string | null;
  endAt: string | null;
  withinScheduleWindow: boolean;
  status: string;
  totalStock: AdFlashRatio;
  claimedQuantity: AdFlashRatio;
  redeemedQuantity: AdFlashRatio;
  remainingStock: AdFlashRatio;
  perUserLimit: AdFlashRatio;
  channelType: string;
  placement: string;
  platformSyncStatus: string;
  pendingPlatformWriteback: boolean;
  localChangedAt: string | null;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AdFlashSalePage {
  items: AdFlashSaleRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdFlashSaleClaimRow {
  id: string;
  recipientKey: string;
  quantity: AdFlashRatio;
  claimedAt: string;
  orderRef: string | null;
  redeemedAt: string | null;
  redeemedAmount: AdFlashMoney;
  subsidyCost: AdFlashMoney;
  status: string;
}

export interface AdFlashSaleDailyStat {
  date: string;
  redemptionCount: number;
  subsidyCost: AdFlashMoney;
}

export interface AdFlashSaleStatistics {
  totalStock: AdFlashRatio;
  claimedCount: number;
  redeemedCount: number;
  remainingStock: AdFlashRatio;
  redemptionRate: AdFlashRatio;
  totalRedeemedAmount: AdFlashMoney;
  totalSubsidyCost: AdFlashMoney;
  dailyTrend: AdFlashSaleDailyStat[];
  marginNote: string;
}

export interface AdFlashSaleDetail {
  sale: AdFlashSaleRow;
  recentClaims: AdFlashSaleClaimRow[];
  statistics: AdFlashSaleStatistics;
  syncNote: string;
  pricingSnapshotNote: string;
  note: string;
}

// ==================================================================
// 请求
// ==================================================================

export interface SaveFlashSaleInput {
  saleCode: string;
  saleName: string;
  platformId: string;
  shopCode: string;
  listingId: string;
  flashPrice: number;
  originalPrice?: number | undefined;
  currency: string;
  startAt: string | null;
  endAt: string | null;
  totalStock: number;
  perUserLimit?: number | undefined;
  channelType: string;
  placement: string;
  note: string;
}

export interface ChangeFlashSaleStatusInput {
  status: string;
  reason: string;
}

export interface ClaimFlashSaleInput {
  recipientKey: string;
  quantity?: number | undefined;
}

export interface RedeemFlashSaleInput {
  orderRef: string;
  claimId?: string | undefined;
}

/** 表单状态：金额与比率一律用**字符串**承载，避免输入过程中的浮点误差。 */
export interface AdFlashSaleInputState {
  saleCode: string;
  saleName: string;
  platformId: string;
  shopCode: string;
  listingId: string;
  flashPrice: string;
  originalPrice: string;
  currency: string;
  startAt: string;
  endAt: string;
  totalStock: string;
  perUserLimit: string;
  channelType: string;
  placement: string;
  note: string;
}

export function emptyAdFlashSaleInput(): AdFlashSaleInputState {
  return {
    saleCode: '',
    saleName: '',
    platformId: '',
    shopCode: '',
    listingId: '',
    flashPrice: '',
    originalPrice: '',
    currency: 'USD',
    startAt: '',
    endAt: '',
    totalStock: '0',
    perUserLimit: '1',
    channelType: 'ON_SITE',
    placement: '',
    note: '',
  };
}

/** 领取秒杀表单。 */
export interface AdFlashSaleClaimInputState {
  recipientKey: string;
  quantity: string;
}

export function emptyAdFlashSaleClaimInput(): AdFlashSaleClaimInputState {
  return { recipientKey: '', quantity: '1' };
}

/** 核销秒杀表单。 */
export interface AdFlashSaleRedeemInputState {
  orderRef: string;
  claimId: string;
}

export function emptyAdFlashSaleRedeemInput(): AdFlashSaleRedeemInputState {
  return { orderRef: '', claimId: '' };
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

export function fetchAdFlashSaleCapabilities(): Promise<AdFlashSaleCapabilities> {
  return api<AdFlashSaleCapabilities>('channel/ad-flash-sales/capabilities');
}

export function fetchAdFlashSaleOptions(): Promise<AdFlashSaleOptions> {
  return api<AdFlashSaleOptions>('channel/ad-flash-sales/options');
}

export function fetchAdFlashSales(
  query: {
    status?: string;
    platformId?: string;
    shopCode?: string;
    channelType?: string;
    syncStatus?: string;
    keyword?: string;
    page?: number;
    size?: number;
  } = {},
): Promise<AdFlashSalePage> {
  return api<AdFlashSalePage>(`channel/ad-flash-sales/flash-sales${queryString(query)}`);
}

export function fetchAdFlashSale(id: string): Promise<AdFlashSaleDetail> {
  return api<AdFlashSaleDetail>(`channel/ad-flash-sales/flash-sales/${id}`);
}

export function saveAdFlashSale(input: SaveFlashSaleInput): Promise<AdFlashSaleDetail> {
  return api<AdFlashSaleDetail>('channel/ad-flash-sales/flash-sales', 'POST', input);
}

export function updateAdFlashSale(id: string, input: SaveFlashSaleInput): Promise<AdFlashSaleDetail> {
  return api<AdFlashSaleDetail>(`channel/ad-flash-sales/flash-sales/${id}`, 'POST', input);
}

export function changeAdFlashSaleStatus(
  id: string,
  input: ChangeFlashSaleStatusInput,
): Promise<AdFlashSaleDetail> {
  return api<AdFlashSaleDetail>(`channel/ad-flash-sales/flash-sales/${id}/status`, 'POST', input);
}

export function claimAdFlashSale(id: string, input: ClaimFlashSaleInput): Promise<AdFlashSaleDetail> {
  return api<AdFlashSaleDetail>(`channel/ad-flash-sales/flash-sales/${id}/claims`, 'POST', input);
}

export function redeemAdFlashSale(
  id: string,
  input: RedeemFlashSaleInput,
): Promise<AdFlashSaleDetail> {
  return api<AdFlashSaleDetail>(`channel/ad-flash-sales/flash-sales/${id}/redemptions`, 'POST', input);
}

export function fetchAdFlashSaleStatistics(id: string): Promise<AdFlashSaleStatistics> {
  return api<AdFlashSaleStatistics>(`channel/ad-flash-sales/flash-sales/${id}/statistics`);
}

// ==================================================================
// 展示辅助
// ==================================================================

/** 金额原样展示（服务端算好的，前端不做算术）。null = 不可比 / 不可测算。 */
export function flashMoney(value: AdFlashMoney | undefined, currency?: string): string {
  if (value === null || value === undefined) return '不可比';
  const fixed = value.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

/** 🔴 不可测算显示「不可测算」，不是 0.00。 */
export function flashSubsidyText(value: AdFlashMoney | undefined): string {
  if (value === null || value === undefined) return '不可测算';
  return value.toFixed(2);
}

/** 数量展示：null = 不可比，不是 0。 */
export function flashCountText(value: AdFlashRatio | undefined): string {
  if (value === null || value === undefined) return '—';
  return String(value);
}

export function flashStatusLabel(status: string): string {
  switch (status) {
    case 'DRAFT':
      return '草稿';
    case 'ACTIVE':
      return '进行中';
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

export function flashStatusTone(status: string): string {
  if (status === 'ACTIVE') return 'text-emerald-600';
  if (status === 'PAUSED' || status === 'CANCELLED') return 'text-amber-600';
  return 'text-muted-foreground';
}

export function flashClaimStatusLabel(status: string): string {
  switch (status) {
    case 'CLAIMED':
      return '已领取未核销';
    case 'REDEEMED':
      return '已核销';
    case 'CANCELLED':
      return '已取消';
    default:
      return status;
  }
}

export function flashSyncStatusLabel(status: string): string {
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

export function flashTimestampText(value: string | null): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

export function flashLocalDateTimeInput(value: string | null): string {
  if (!value) return '';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())}T${pad(
    parsed.getHours(),
  )}:${pad(parsed.getMinutes())}`;
}

// ==================================================================
// 表单校验（与服务端同口径）
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

export function validateAdFlashSale(input: AdFlashSaleInputState): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.saleCode.trim()) errors.push('秒杀编码必填');
  if (!input.saleName.trim()) errors.push('秒杀名称必填');
  if (!input.shopCode.trim()) errors.push('参与店铺必填');
  if (!input.platformId.trim()) errors.push('平台必填');
  if (!input.listingId.trim()) errors.push('秒杀商品（listing id）必填');
  if (!isNonNegativeAmount(input.flashPrice) || input.flashPrice.trim() === '') {
    errors.push('秒杀价必填，且必须是不小于 0 的数字');
  }
  if (!isNonNegativeAmount(input.originalPrice)) errors.push('原价必须是不小于 0 的数字');
  if (!isNonNegativeAmount(input.totalStock)) errors.push('库存必须是不小于 0 的数字');
  if (input.perUserLimit.trim() !== '' && !isPositiveAmount(input.perUserLimit)) {
    errors.push('每人限领件数必须大于 0（留空 = 不限领）');
  }
  if (!input.startAt) errors.push('开始时间必填');
  if (!input.endAt) errors.push('结束时间必填');
  if (input.startAt && input.endAt && new Date(input.endAt) <= new Date(input.startAt)) {
    errors.push('结束时间必须晚于开始时间');
  }
  if (input.currency.trim() && input.currency.trim().length !== 3) {
    errors.push('币种必须是 3 位字母代码');
  }
  return { ok: errors.length === 0, errors };
}

export function validateAdFlashSaleClaim(input: AdFlashSaleClaimInputState): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.recipientKey.trim()) errors.push('领取人标识必填');
  if (!isPositiveAmount(input.quantity)) errors.push('领取件数必须大于 0');
  return { ok: errors.length === 0, errors };
}

export function validateAdFlashSaleRedeem(input: AdFlashSaleRedeemInputState): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  // 🔴 蓝图加粗句：核销必须与订单绑定 —— 没有订单标识的核销写不进来。
  if (!input.orderRef.trim()) errors.push('订单标识必填 —— 核销必须与订单绑定');
  return { ok: errors.length === 0, errors };
}
