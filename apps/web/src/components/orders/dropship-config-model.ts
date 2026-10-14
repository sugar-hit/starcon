import type {
  DropshipCapabilities,
  DropshipConfigDetail,
  DropshipConfigInput,
  DropshipConfigLineInput,
  DropshipConfigLineRow,
  DropshipConfigLineSaved,
  DropshipConfigPage,
  DropshipConfigRow,
  DropshipConfigSaved,
  DropshipConfigStatusInput,
  DropshipEvaluationPage,
  DropshipEvaluationRequest,
  DropshipEvaluationRow,
  DropshipEvaluationSaved,
  DropshipEventPage,
  DropshipEventRow,
  DropshipShopRefInput,
  DropshipShopRefPage,
  DropshipShopRefRow,
  DropshipShopRefSaved,
  DropshipSupplierRefInput,
  DropshipSupplierRefPage,
  DropshipSupplierRefRow,
  DropshipSupplierRefSaved,
  DropshipSupplierStockInput,
  DropshipSupplierStockPage,
  DropshipSupplierStockRow,
  DropshipSupplierStockSaved,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 9.6.1.1 代发模式配置前端模型。
 *
 * <p>蓝图：「配置店铺/SKU 是否走代发（一件代发），以及代发供应商、结算方式
 * （下单/确认收货后结算、差价抽成）、可售校验规则；区分『自营发货』与『供应商直发』
 * 两种履约路径。纯代理代发不占用自有仓库存；其他直发模式的货权与结算规则须单独确认。」
 * 输出是「代发模式生效配置」与「代发可售标记」。
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>物流 ≠ 货权，要分两列说人话。</b>配置行同时显示「谁发货」与「货权在谁」，
 *       并且只在<b>两者同时</b>是「供应商直发 + 纯代理」时才显示「不记我方库存」。
 *       把这两件事合成一个「是否代发」的勾选框，就正是蓝图加粗句要防的错误。</li>
 *   <li><b>供应商直发 + 我方货权必须显眼。</b>那是 VMI 型：不经我方仓但货权在我方，
 *       照样占用我方库存口径，界面上必须用警告色标出而不是混在普通行里。</li>
 *   <li><b>「没有观测」不是 0。</b>可售标记里供应商可供货量为 {@code null} 时
 *       显示「未观测」而不是「0」—— 0 是「供应商确实没货」，null 是「没人报过」，
 *       画成同一个 0 会让人以为这单确定发不出。</li>
 *   <li><b>可售 ≠ 可刊登。</b>两列分开显示：ALWAYS_LIST 时不可售但仍可刊登是合法状态。</li>
 *   <li><b>金额不在前端二次取整。</b>差价、毛利、应付供应商都由后端生成列算好，
 *       前端只格式化不重算 —— 再 round 一次会让界面上比账面多出或少掉一分钱。</li>
 * </ul>
 */

// ------------------------------------------------------------ 参数

export interface DropshipConfigParams {
  configCode?: string;
  scopeKind?: string;
  shopCode?: string;
  skuRef?: string;
  fulfillmentPath?: string;
  ownershipMode?: string;
  settlementBasis?: string;
  status?: string;
  effectiveOnly?: boolean;
  countsAgainstOwnInventory?: boolean;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface DropshipShopRefParams {
  shopCode?: string;
  platformCode?: string;
  activeOnly?: boolean;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface DropshipSupplierRefParams {
  supplierCode?: string;
  quoteSource?: string;
  activeOnly?: boolean;
  quoteValidOnly?: boolean;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface DropshipSupplierStockParams {
  supplierCode?: string;
  skuRef?: string;
  stockSource?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface DropshipEvaluationParams {
  configId?: string;
  skuRef?: string;
  shopCode?: string;
  supplierCode?: string;
  sellable?: boolean;
  listable?: boolean;
  reasonCode?: string;
  page?: number;
  size?: number;
}

function query<T extends object>(params: T): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params as Record<string, unknown>)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

// ------------------------------------------------------------ 接口

const base = 'trade/fulfillment/dropship';

export function fetchDropshipCapabilities(): Promise<DropshipCapabilities> {
  return api<DropshipCapabilities>(`${base}/capabilities`);
}

export function fetchShopRefs(params: DropshipShopRefParams): Promise<DropshipShopRefPage> {
  return api<DropshipShopRefPage>(`${base}/shop-refs${query(params)}`);
}

export function saveShopRef(input: DropshipShopRefInput): Promise<DropshipShopRefSaved> {
  return api<DropshipShopRefSaved>(`${base}/shop-refs`, 'POST', input);
}

export function fetchSupplierRefs(
  params: DropshipSupplierRefParams,
): Promise<DropshipSupplierRefPage> {
  return api<DropshipSupplierRefPage>(`${base}/supplier-refs${query(params)}`);
}

export function saveSupplierRef(
  input: DropshipSupplierRefInput,
): Promise<DropshipSupplierRefSaved> {
  return api<DropshipSupplierRefSaved>(`${base}/supplier-refs`, 'POST', input);
}

export function fetchSupplierStocks(
  params: DropshipSupplierStockParams,
): Promise<DropshipSupplierStockPage> {
  return api<DropshipSupplierStockPage>(`${base}/supplier-stocks${query(params)}`);
}

export function saveSupplierStock(
  input: DropshipSupplierStockInput,
): Promise<DropshipSupplierStockSaved> {
  return api<DropshipSupplierStockSaved>(`${base}/supplier-stocks`, 'POST', input);
}

export function fetchDropshipConfigs(params: DropshipConfigParams): Promise<DropshipConfigPage> {
  return api<DropshipConfigPage>(`${base}/configs${query(params)}`);
}

export function saveDropshipConfig(input: DropshipConfigInput): Promise<DropshipConfigSaved> {
  return api<DropshipConfigSaved>(`${base}/configs`, 'POST', input);
}

export function fetchDropshipConfig(configId: string): Promise<DropshipConfigDetail> {
  return api<DropshipConfigDetail>(`${base}/configs/${configId}`);
}

export function advanceDropshipConfigStatus(
  configId: string,
  input: DropshipConfigStatusInput,
): Promise<DropshipConfigSaved> {
  return api<DropshipConfigSaved>(`${base}/configs/${configId}/status`, 'POST', input);
}

export function saveConfigLine(
  configId: string,
  input: DropshipConfigLineInput,
): Promise<DropshipConfigLineSaved> {
  return api<DropshipConfigLineSaved>(`${base}/configs/${configId}/lines`, 'POST', input);
}

export function fetchEvaluations(
  params: DropshipEvaluationParams,
): Promise<DropshipEvaluationPage> {
  return api<DropshipEvaluationPage>(`${base}/evaluations${query(params)}`);
}

export function evaluateDropship(
  input: DropshipEvaluationRequest,
): Promise<DropshipEvaluationSaved> {
  return api<DropshipEvaluationSaved>(`${base}/evaluations`, 'POST', input);
}

export function fetchDropshipEvents(
  configId: string | undefined,
  page: number,
  size: number,
): Promise<DropshipEventPage> {
  return api<DropshipEventPage>(`${base}/events${query({ configId, page, size })}`);
}

// ------------------------------------------------------------ 词表

export const SCOPE_KINDS: { value: string; label: string }[] = [
  { value: 'SHOP', label: '按店铺' },
  { value: 'SKU', label: '按 SKU' },
];

export const FULFILLMENT_PATHS: { value: string; label: string }[] = [
  { value: 'SELF_FULFILLED', label: '自营发货' },
  { value: 'SUPPLIER_DIRECT', label: '供应商直发' },
];

export const OWNERSHIP_MODES: { value: string; label: string }[] = [
  { value: 'PURE_AGENCY', label: '纯代理（货权归供应商，不记我方库存）' },
  { value: 'OUR_ACCOUNT', label: '我方货权（须单独确认）' },
];

export const SETTLEMENT_TIMINGS: { value: string; label: string }[] = [
  { value: 'ON_ORDER', label: '下单结算' },
  { value: 'ON_RECEIPT', label: '确认收货后结算' },
];

export const SETTLEMENT_BASES: { value: string; label: string }[] = [
  { value: 'FLAT_COST', label: '固定供货价' },
  { value: 'MARGIN_SHARE', label: '差价抽成' },
];

export const AVAILABILITY_CHECK_MODES: { value: string; label: string }[] = [
  { value: 'SUPPLIER_STOCK', label: '按供应商库存校验' },
  { value: 'CONFIRM_ON_ORDER', label: '下单时向供应商确认' },
  { value: 'SKIP', label: '不校验' },
];

export const LISTING_POLICIES: { value: string; label: string }[] = [
  { value: 'ALWAYS_LIST', label: '始终刊登' },
  { value: 'PAUSE_WHEN_UNSELLABLE', label: '不可售即下架' },
  { value: 'PAUSE_WHEN_NEGATIVE_MARGIN', label: '差价为负即下架' },
  { value: 'MANUAL', label: '人工决定' },
];

export const CONFIG_STATUSES: { value: string; label: string }[] = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'ACTIVE', label: '生效中' },
  { value: 'SUSPENDED', label: '已暂停' },
  { value: 'CLOSED', label: '已关闭' },
];

export const SELL_REASON_CODES: { value: string; label: string }[] = [
  { value: 'SELLABLE', label: '可售可刊登' },
  { value: 'CONFIG_NOT_ACTIVE', label: '配置未生效' },
  { value: 'CONFIG_NOT_EFFECTIVE', label: '不在有效期内' },
  { value: 'NO_SUPPLIER_STOCK_RECORD', label: '无供应商库存记录' },
  { value: 'SUPPLIER_STOCK_INSUFFICIENT', label: '供应商库存不足' },
  { value: 'NEGATIVE_MARGIN', label: '差价为负' },
  { value: 'LISTING_PAUSED', label: '刊登策略已暂停' },
  { value: 'QUOTE_EXPIRED', label: '供应商报价已过期' },
  { value: 'SHOP_INACTIVE', label: '店铺已停用' },
  { value: 'LINE_INACTIVE', label: '明细行已停用' },
];

export const SHOP_SOURCES: { value: string; label: string }[] = [
  { value: 'MODULE_2_3_1', label: '2.3.1 店铺（channel）' },
  { value: 'TENANT_MAINTAINED', label: '租户手工登记' },
];

export const SUPPLIER_SOURCES: { value: string; label: string }[] = [
  { value: 'MODULE_3_6_2', label: '3.6.2 供应商报价（catalog）' },
  { value: 'TENANT_MAINTAINED', label: '租户手工登记' },
];

export const STOCK_SOURCES: { value: string; label: string }[] = [
  { value: 'MODULE_7_1_1_3', label: '7.1.1.3 可用量（warehouse）' },
  { value: 'MODULE_3_6_2', label: '3.6.2 供应商报价（catalog）' },
  { value: 'TENANT_MAINTAINED', label: '租户手工登记' },
];

function labelOf(options: { value: string; label: string }[], value: string): string {
  return options.find((o) => o.value === value)?.label ?? value;
}

export function scopeKindLabel(v: string): string {
  return labelOf(SCOPE_KINDS, v);
}
export function fulfillmentPathLabel(v: string): string {
  return labelOf(FULFILLMENT_PATHS, v);
}
export function ownershipModeLabel(v: string): string {
  return labelOf(OWNERSHIP_MODES, v);
}
export function settlementTimingLabel(v: string): string {
  return labelOf(SETTLEMENT_TIMINGS, v);
}
export function settlementBasisLabel(v: string): string {
  return labelOf(SETTLEMENT_BASES, v);
}
export function availabilityCheckModeLabel(v: string): string {
  return labelOf(AVAILABILITY_CHECK_MODES, v);
}
export function listingPolicyLabel(v: string): string {
  return labelOf(LISTING_POLICIES, v);
}
export function configStatusLabel(v: string): string {
  return labelOf(CONFIG_STATUSES, v);
}
export function sellReasonLabel(v: string): string {
  return labelOf(SELL_REASON_CODES, v);
}
export function stockSourceLabel(v: string): string {
  return labelOf(STOCK_SOURCES, v);
}

// ------------------------------------------------------------ 展示层判定

/**
 * 这一行是否「不记我方库存」。
 *
 * <p>刻意只读 DB 的 {@code pureAgencyExempt} 生成列，**不在前端另算一遍**
 * 「路径 = 直发 && 货权 = 纯代理」—— 两份算法迟早会漂移，那时界面上显示的
 * 「不占我方库存」就会和 9.6.1.2 实际预占与否对不上，而那正是要防的事。
 */
export function exemptFromOwnInventory(config: DropshipConfigRow): boolean {
  return config.pureAgencyExempt === true;
}

/**
 * 「不经我方仓但货权在我方」—— 需要显式警告的那一格（VMI 型）。
 *
 * <p>这是蓝图加粗句「不经自有仓不代表不承担货权」在界面上的落点：
 * 它必须显眼，不能混在普通行里让人一眼扫过去。
 */
export function isSupplierDirectButOurOwnership(config: DropshipConfigRow): boolean {
  return config.fulfillmentPath === 'SUPPLIER_DIRECT' && config.ownershipMode === 'OUR_ACCOUNT';
}

/** 非纯代理必须「单独确认」过货权：没确认就不该生效。 */
export function ownershipConfirmationMissing(config: DropshipConfigRow): boolean {
  return config.ownershipMode !== 'PURE_AGENCY' && !config.ownershipConfirmedAt;
}

/** 差价是否已经转负（转负 = 供应商涨价吃掉了全部差价）。 */
export function negativeMargin(config: DropshipConfigRow): boolean {
  return Number(config.expectedMarginAmount) < 0;
}

/**
 * 供应商可供货量的展示文本。
 *
 * <p>{@code null} → 「未观测」；{@code 0} → 「0」。两者在界面上必须是不同的字样：
 * 前者是缺口（该去补观测或换校验方式），后者是确定结论（供应商确实没货）。
 */
export function supplierStockText(row: DropshipEvaluationRow): string {
  if (row.supplierAvailableQty === null || row.supplierAvailableQty === undefined) {
    return '未观测';
  }
  return qtyText(row.supplierAvailableQty);
}

/** 毛利展示：转负时带负号前缀，便于扫视。 */
export function marginText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  const fixed = Number(value).toFixed(2);
  return Number(value) < 0 ? `-${Math.abs(Number(fixed)).toFixed(2)}` : fixed;
}

/** 差价抽成比例展示（前端不重算，只把后端给的比例格式化）。 */
export function marginShareText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return `${(Number(value) * 100).toFixed(4)}%`;
}

/** 数量文本：去掉多余的尾零，但至少保留整数部分。 */
export function qtyText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  const n = Number(value);
  return Number.isInteger(n) ? String(n) : String(Number(n.toFixed(6)));
}

/** 金额文本：统一两位，不在前端再四舍五入成别的口径。 */
export function moneyText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return Number(value).toFixed(2);
}

/** 配置是否还能推进状态（已关闭是终态）。 */
export function canAdvance(status: string): boolean {
  return status !== 'CLOSED';
}

/** 下一步可以推进到的状态。 */
export function nextStatuses(status: string): string[] {
  switch (status) {
    case 'DRAFT':
      return ['ACTIVE'];
    case 'ACTIVE':
      return ['SUSPENDED', 'CLOSED'];
    case 'SUSPENDED':
      return ['ACTIVE', 'CLOSED'];
    default:
      return [];
  }
}

/** 能力维度：不可用时把原因一并显示，而不是画成 0 或「—」。 */
export function dimensionUnavailableReason(
  dimensions: { code: string; available: boolean; unavailableReason: string | null }[],
  code: string,
): string | null {
  return dimensions.find((d) => d.code === code && !d.available)?.unavailableReason ?? null;
}

/** 录入表单的自校验：前端也拦一道，省得白跑一趟后端。 */
export function configFormError(form: {
  fulfillmentPath: string;
  supplierCode: string;
  ownershipMode: string;
  ownershipConfirmedAt: string;
  ownershipConfirmedNote: string;
  ownershipReviewedWith: string;
  settlementBasis: string;
  marginShareRate: string;
  availabilityCheckMode: string;
  minSupplierStockBuffer: string;
}): string | null {
  if (form.fulfillmentPath === 'SUPPLIER_DIRECT' && !form.supplierCode.trim()) {
    return '履约路径是「供应商直发」，必须填代发供应商';
  }
  if (form.fulfillmentPath === 'SELF_FULFILLED' && form.supplierCode.trim()) {
    return '自营发货不该挂代发供应商：货是我方仓发的';
  }
  if (form.fulfillmentPath === 'SELF_FULFILLED' && form.ownershipMode === 'PURE_AGENCY') {
    return '自营发货不可能是「纯代理」：货权在我方是前提';
  }
  if (form.ownershipMode === 'OUR_ACCOUNT') {
    if (!form.ownershipConfirmedAt.trim()) {
      return '货权在我方时必须给出确认时间：不经我方仓不代表不承担货权';
    }
    if (!form.ownershipConfirmedNote.trim()) {
      return '货权在我方时必须写明确认依据（与 7.7 货权规则联合评审的结论）';
    }
    if (!form.ownershipReviewedWith.trim()) {
      return '货权在我方时必须填写联合评审对象';
    }
  }
  if (form.ownershipMode === 'PURE_AGENCY') {
    if (form.ownershipConfirmedAt.trim() || form.ownershipConfirmedNote.trim()) {
      return '纯代理不需要「单独确认」货权，带上会让人误以为它也被复核过';
    }
  }
  if (form.settlementBasis === 'MARGIN_SHARE' && !form.marginShareRate.trim()) {
    return '差价抽成必须给出我方留存的差价比例';
  }
  if (form.settlementBasis === 'FLAT_COST' && form.marginShareRate.trim()) {
    return '固定供货价不该带抽成比例：留着会让人以为会抽佣';
  }
  if (form.availabilityCheckMode === 'SUPPLIER_STOCK' && !form.minSupplierStockBuffer.trim()) {
    return '按供应商库存校验时必须给出安全余量，否则「够不够卖」没有判定线';
  }
  if (form.availabilityCheckMode !== 'SUPPLIER_STOCK' && form.minSupplierStockBuffer.trim()) {
    return '不按供应商库存校验时，安全余量必须留空';
  }
  return null;
}

export type ConfigRow = DropshipConfigRow;
export type LineRow = DropshipConfigLineRow;
export type EvaluationRow = DropshipEvaluationRow;
export type ShopRefRow = DropshipShopRefRow;
export type SupplierRefRow = DropshipSupplierRefRow;
export type SupplierStockRow = DropshipSupplierStockRow;
export type EventRow = DropshipEventRow;

export { ApiError };
