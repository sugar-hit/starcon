import { api } from '../inventory/api';

/**
 * 14.4.1.1 促销列表 前端模型。
 *
 * <p>蓝图：「管理平台促销活动：促销类型、适用商品、折扣幅度、起止时间、预算上限、参与店铺，
 * 同步状态与效果。输入：促销定义；平台促销接口；适用商品选择。输出：促销记录 + 平台同步状态。
 * 关系：上游 = 5 定价（促销价计算）；下游 = 平台促销 API、12 财务（费用）。
 * <strong>促销的毛利必须实时测算</strong>——折扣叠加后亏损是常态，需在下单前拦截或预警。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **毛利维度当前不可用，必须让人看见**。`capabilities.dimensions` 里 `gross_margin` 是
 *    `available=false`，界面上要常驻一条说明 —— 不能让人以为「促销毛利已经算好了」。
 * 2. **「本地已建」不等于「平台侧已建」**。`platformSyncStatus` 与
 *    `pendingPlatformWriteback` 分开显示，页面要写明「平台侧还没有这个促销」。
 * 3. **折后价可算、毛利不可算**。`netPrice` 有值（本地算的），毛利**没有字段** ——
 *    界面不许拿一个 0 毛利糊弄过去。
 * 4. **不可比不是 0**。`netPrice === null` 显示「不可比」，不是 `0.00`。
 *
 * 🔴 金额与数量在 JS 侧只用 `number` 承载与展示，**不做任何前端算术** ——
 * 折后价、叠加折后价、最坏情况让利额都是服务端 `BigDecimal` 的事，
 * 前端算一遍就会引入二进制浮点误差，而且会让「界面上的折后价」和「账上的折后价」对不上。
 */

/** 金额与数量一律 number（JSON number），只原样展示。 */
export type AdMoney = number | null;

/** 🔴 null = 不可比（没有基准价 / 类型没有可折算系数），与 `0`（可比但真的是零）含义相反。 */
export type AdRatio = number | null;

export interface AdPromotionCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface AdPromotionCapabilities {
  dimensions: AdPromotionCapabilityDimension[];
  promotionTypes: string[];
  statuses: string[];
  syncStatuses: string[];
  ruleCodes: string[];
  severities: string[];
  netPriceNote: string;
  marginNote: string;
  effectNote: string;
  financeNote: string;
  syncNote: string;
  stackingNote: string;
  upstreamNote: string;
  boundaryNote: string;
}

export interface AdPromotionOption {
  code: string;
  label: string;
}

/** 平台下拉项（**带 id** —— 登记促销要的是平台 id，不只是编码）。 */
export interface AdPromotionPlatformOption {
  id: string;
  code: string;
  name: string;
}

export interface AdPromotionOptions {
  promotionTypes: AdPromotionOption[];
  statuses: AdPromotionOption[];
  syncStatuses: AdPromotionOption[];
  ruleCodes: AdPromotionOption[];
  severities: AdPromotionOption[];
  platforms: AdPromotionPlatformOption[];
  platformCodes: string[];
  shopCodes: string[];
  currencies: string[];
  netPriceNote: string;
  marginNote: string;
}

export interface AdPromotionRow {
  id: string;
  promotionCode: string;
  promotionName: string;
  promotionType: string;
  platformId: string;
  platformCode: string;
  shopCode: string;
  discountRate: AdRatio;
  discountAmount: AdMoney;
  budgetCap: AdMoney;
  currency: string;
  startAt: string | null;
  endAt: string | null;
  withinScheduleWindow: boolean;
  status: string;
  platformSyncStatus: string;
  pendingPlatformWriteback: boolean;
  localChangedAt: string | null;
  lastCheckedAt: string | null;
  platformPromotionId: string;
  productCount: number;
  blockedCount: number;
  warningCount: number;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AdPromotionPage {
  items: AdPromotionRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdPromotionProductRow {
  id: string;
  listingId: string;
  externalItemId: string;
  title: string;
  listingStatus: string;
  basePrice: AdMoney;
  currency: string;
  netPrice: AdMoney;
  discountDepth: AdRatio;
  stackedPromotionCount: number;
  stackedNetPrice: AdMoney;
  note: string;
  createdAt: string;
}

export interface AdPromotionCheckRow {
  id: string;
  listingId: string | null;
  ruleCode: string;
  severity: string;
  message: string;
  observedValue: AdRatio;
  thresholdValue: AdRatio;
  checkedAt: string;
}

export interface AdPromotionGuardrailRow {
  id: string;
  guardrailCode: string;
  guardrailName: string;
  maxDiscountRate: AdRatio;
  minNetPrice: AdMoney;
  blockOnStacking: boolean;
  status: string;
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AdPromotionGuardrailPage {
  items: AdPromotionGuardrailRow[];
  total: number;
  page: number;
  size: number;
}

export interface AdPromotionDetail {
  promotion: AdPromotionRow;
  products: AdPromotionProductRow[];
  checks: AdPromotionCheckRow[];
  guardrail: AdPromotionGuardrailRow | null;
  blockedCount: number;
  warningCount: number;
  marginNote: string;
  syncNote: string;
  note: string;
}

// ==================================================================
// 请求
// ==================================================================

export interface SavePromotionProductInput {
  listingId: string;
  basePrice?: number | undefined;
  currency?: string | undefined;
  note: string;
}

export interface SavePromotionInput {
  promotionCode: string;
  promotionName: string;
  promotionType: string;
  platformId: string;
  shopCode: string;
  discountRate?: number | undefined;
  discountAmount?: number | undefined;
  budgetCap?: number | undefined;
  currency: string;
  startAt: string | null;
  endAt: string | null;
  products: SavePromotionProductInput[];
  note: string;
}

export interface ChangePromotionStatusInput {
  status: string;
  reason: string;
}

export interface SaveGuardrailInput {
  guardrailCode: string;
  guardrailName: string;
  maxDiscountRate?: number | undefined;
  minNetPrice?: number | undefined;
  blockOnStacking: boolean;
  status: string;
  note: string;
}

/** 表单状态：金额与比率一律用**字符串**承载，避免输入过程中的浮点误差。 */
export interface AdPromotionInputState {
  promotionCode: string;
  promotionName: string;
  promotionType: string;
  platformId: string;
  shopCode: string;
  discountRate: string;
  discountAmount: string;
  budgetCap: string;
  currency: string;
  startAt: string;
  endAt: string;
  /** 适用商品：listing id + 基准价（可空 = 不可比）。 */
  products: { listingId: string; basePrice: string; note: string }[];
  note: string;
}

export function emptyAdPromotionInput(): AdPromotionInputState {
  return {
    promotionCode: '',
    promotionName: '',
    promotionType: 'PERCENT_OFF',
    platformId: '',
    shopCode: '',
    discountRate: '',
    discountAmount: '',
    budgetCap: '',
    currency: 'USD',
    startAt: '',
    endAt: '',
    products: [],
    note: '',
  };
}

function queryString(query: object): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : '';
}

export function fetchAdPromotionCapabilities(): Promise<AdPromotionCapabilities> {
  return api<AdPromotionCapabilities>('channel/ad-promotions/capabilities');
}

export function fetchAdPromotionOptions(): Promise<AdPromotionOptions> {
  return api<AdPromotionOptions>('channel/ad-promotions/options');
}

export function fetchAdPromotions(
  query: {
    status?: string;
    promotionType?: string;
    platformId?: string;
    shopCode?: string;
    syncStatus?: string;
    keyword?: string;
    page?: number;
    size?: number;
  } = {},
): Promise<AdPromotionPage> {
  return api<AdPromotionPage>(`channel/ad-promotions/promotions${queryString(query)}`);
}

export function fetchAdPromotion(id: string): Promise<AdPromotionDetail> {
  return api<AdPromotionDetail>(`channel/ad-promotions/promotions/${id}`);
}

export function saveAdPromotion(input: SavePromotionInput): Promise<AdPromotionDetail> {
  return api<AdPromotionDetail>('channel/ad-promotions/promotions', 'POST', input);
}

export function updateAdPromotion(id: string, input: SavePromotionInput): Promise<AdPromotionDetail> {
  return api<AdPromotionDetail>(`channel/ad-promotions/promotions/${id}`, 'POST', input);
}

export function changeAdPromotionStatus(
  id: string,
  input: ChangePromotionStatusInput,
): Promise<AdPromotionDetail> {
  return api<AdPromotionDetail>(`channel/ad-promotions/promotions/${id}/status`, 'POST', input);
}

export function checkAdPromotion(id: string): Promise<AdPromotionDetail> {
  return api<AdPromotionDetail>(`channel/ad-promotions/promotions/${id}/check`, 'POST');
}

export function fetchAdPromotionGuardrails(
  query: { status?: string; page?: number; size?: number } = {},
): Promise<AdPromotionGuardrailPage> {
  return api<AdPromotionGuardrailPage>(`channel/ad-promotions/guardrails${queryString(query)}`);
}

export function saveAdPromotionGuardrail(input: SaveGuardrailInput): Promise<AdPromotionGuardrailRow> {
  return api<AdPromotionGuardrailRow>('channel/ad-promotions/guardrails', 'POST', input);
}

// ==================================================================
// 展示辅助
// ==================================================================

/** 金额原样展示（服务端算好的，前端不做算术）。 */
export function money(value: AdMoney | undefined, currency?: string): string {
  if (value === null || value === undefined) return '不可比';
  const fixed = value.toFixed(2);
  return currency ? `${fixed} ${currency}` : fixed;
}

/**
 * 折扣幅度展示：**不可比显示「不可比」，不是 0.00%**。
 *
 * 🔴 0 是「可比，但真的是零」；null 是「压根算不出来」（没登记基准价）。
 */
export function discountText(value: AdRatio | undefined): string {
  if (value === null || value === undefined) return '不可比';
  return `${(value * 100).toFixed(2)}%`;
}

export function discountTone(value: AdRatio | undefined): string {
  if (value === null || value === undefined) return 'text-muted-foreground';
  return value > 0.5 ? 'text-destructive' : 'text-foreground';
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

export function promotionTypeLabel(type: string): string {
  switch (type) {
    case 'PERCENT_OFF':
      return '百分比折扣';
    case 'FIXED_AMOUNT_OFF':
      return '固定额减免';
    case 'BOGO':
      return '买 N 送 M';
    case 'BUNDLE':
      return '组合优惠';
    case 'FREE_SHIPPING':
      return '免运费';
    default:
      return type;
  }
}

export function statusLabel(status: string): string {
  switch (status) {
    case 'DRAFT':
      return '草稿';
    case 'SCHEDULED':
      return '已排期';
    case 'ACTIVE':
      return '进行中';
    case 'PAUSED':
      return '已暂停';
    case 'ENDED':
      return '已结束';
    case 'CANCELLED':
      return '已取消';
    default:
      return status;
  }
}

export function statusTone(status: string): string {
  if (status === 'ACTIVE') return 'text-emerald-600';
  if (status === 'PAUSED' || status === 'CANCELLED') return 'text-amber-600';
  return 'text-muted-foreground';
}

export function ruleLabel(ruleCode: string): string {
  switch (ruleCode) {
    case 'DISCOUNT_DEPTH':
      return '折扣幅度';
    case 'PRICE_FLOOR':
      return '折后价下限';
    case 'PROMOTION_STACKING':
      return '促销叠加';
    case 'BUDGET_CAP':
      return '预算上限';
    default:
      return ruleCode;
  }
}

export function severityLabel(severity: string): string {
  return severity === 'BLOCKED' ? '拦截' : '预警';
}

export function severityTone(severity: string): string {
  return severity === 'BLOCKED' ? 'text-destructive' : 'text-amber-600';
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

function isPositiveRatio(raw: string): boolean {
  if (raw.trim() === '') return false;
  const value = Number(raw);
  return !Number.isNaN(value) && value > 0 && value <= 1;
}

export function validateAdPromotion(input: AdPromotionInputState): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.promotionCode.trim()) errors.push('促销编码必填');
  if (!input.promotionName.trim()) errors.push('促销名称必填');
  if (!input.shopCode.trim()) errors.push('参与店铺必填');
  if (input.currency.trim() && input.currency.trim().length !== 3) {
    errors.push('币种必须是 3 位字母代码');
  }

  if (input.promotionType === 'PERCENT_OFF') {
    if (!isPositiveRatio(input.discountRate)) errors.push('百分比促销的折扣率必须在 (0, 1] 之间');
    if (input.discountAmount.trim() !== '') {
      errors.push('百分比促销不能再给固定减免额 —— 折后价只能有一个答案');
    }
  } else if (input.promotionType === 'FIXED_AMOUNT_OFF') {
    if (input.discountAmount.trim() === '' || !isNonNegativeAmount(input.discountAmount)) {
      errors.push('固定额促销必须给一个不小于 0 的减免额');
    }
    if (input.discountRate.trim() !== '') {
      errors.push('固定额促销不能再给折扣率 —— 折后价只能有一个答案');
    }
  } else if (input.discountRate.trim() !== '' || input.discountAmount.trim() !== '') {
    errors.push('买 N 送 M / 组合优惠 / 免运费没有可折算的折扣率或减免额（折后价不可比，不是 0）');
  }

  if (!isNonNegativeAmount(input.budgetCap)) errors.push('预算上限必须是不小于 0 的数字');
  for (const product of input.products) {
    if (!product.listingId.trim()) errors.push('适用商品必须点名 listing');
    if (!isNonNegativeAmount(product.basePrice)) errors.push('基准价必须是不小于 0 的数字');
  }
  if (input.startAt && input.endAt && new Date(input.endAt) <= new Date(input.startAt)) {
    errors.push('结束时间必须晚于开始时间');
  }
  return { ok: errors.length === 0, errors };
}

export interface AdGuardrailInputState {
  guardrailCode: string;
  guardrailName: string;
  maxDiscountRate: string;
  minNetPrice: string;
  blockOnStacking: boolean;
  note: string;
}

export function emptyAdGuardrailInput(): AdGuardrailInputState {
  return {
    guardrailCode: '',
    guardrailName: '',
    maxDiscountRate: '',
    minNetPrice: '',
    blockOnStacking: true,
    note: '',
  };
}

export function validateAdGuardrail(input: AdGuardrailInputState): {
  ok: boolean;
  errors: string[];
} {
  const errors: string[] = [];
  if (!input.guardrailCode.trim()) errors.push('规则编码必填');
  if (!input.guardrailName.trim()) errors.push('规则名称必填');
  const hasRate = input.maxDiscountRate.trim() !== '';
  const hasFloor = input.minNetPrice.trim() !== '';
  if (!hasRate && !hasFloor) {
    errors.push('折扣幅度上限与折后价下限至少要有一个 —— 两个都不设的护栏一条规则都判不了');
  }
  if (hasRate && !isPositiveRatio(input.maxDiscountRate)) {
    errors.push('折扣幅度上限必须在 (0, 1] 之间（0.8 = 让利不超过八成）');
  }
  if (hasFloor && !isNonNegativeAmount(input.minNetPrice)) {
    errors.push('折后价下限必须是不小于 0 的数字');
  }
  return { ok: errors.length === 0, errors };
}
