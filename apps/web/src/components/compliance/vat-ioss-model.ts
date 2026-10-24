import { api } from '../../lib/api';

/**
 * 16.3.2.1 VAT/IOSS 前端模型。
 *
 * 蓝图：「管理 VAT 与 IOSS 制度：适用店铺与商品、申报周期、税率适用、IOSS 订单识别与代收代缴统计、
 * 与财务申报数据的对接。输入：IOSS 配置；订单税务数据；12.6.1.2 申报底稿。
 * 输出：IOSS 订单清单与金额统计；申报状态。关系：上游 = 12.6.1.1 税率、9 订单；下游 = 12.6.1.2。
 * **IOSS 订单与一般 VAT 订单必须区分统计**，混在一起申报是最常见的错误。」
 *
 * 🔴 这个页面上最容易做丢的六件事，这里都挡住了：
 *
 * 1. **分档是数据**：每一行订单都有 `filingScope`（IOSS / 一般 VAT / 不在范围 / 判不了）。
 *    页面**不靠颜色**区分 —— 颜色只是显示，**取数本身就是分开的**。
 * 2. **「判不了」是第四档**：没有收货国家 / 买家类型没采到 / 汇率读不到折不出门槛。
 *    界面**不**把它渲染成「IOSS 0 笔」，那是最贵的假结论。
 * 3. **税率读不到 ⇒ 显示「读不到」，绝不显示 0%**：0% 看起来像免税。
 * 4. **金额按币种分组**：**不同币种绝不直接相加**（EUR + USD 不是钱）。
 * 5. **申报状态只能前进**：DRAFT 待提交 → DRAFTED 已出底稿 → CONFIRMED 已确认。
 *    页面**不提供**回退入口，也**不提供**「代用户向税务机构提交」的入口。
 * 6. **三态空态各不相同**：「还没识别过订单」「筛选无结果」「这一期还没有底稿」分开说。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**）。 */
export const BASE = 'channel/vat-ioss';

/** 🔴 统计分档（受控值域，后端钉死）。**顺序即页面上的展示顺序。 */
export const FILING_SCOPES = ['IOSS', 'GENERAL_VAT', 'OUT_OF_SCOPE', 'UNRESOLVED'] as const;

export type FilingScope = (typeof FILING_SCOPES)[number];

/** 分档下拉（筛选用）。 */
export const FILING_SCOPE_FILTERS = [
  { value: '', label: '全部分档' },
  { value: 'IOSS', label: 'IOSS 订单' },
  { value: 'GENERAL_VAT', label: '一般 VAT 订单' },
  { value: 'OUT_OF_SCOPE', label: '不在申报范围' },
  { value: 'UNRESOLVED', label: '判不了' },
] as const;

/** 判定原因（受控值域）。 */
export const REASON_CODES = [
  'IOSS_ELIGIBLE',
  'ABOVE_THRESHOLD',
  'EXCISE_GOODS',
  'B2B_ORDER',
  'NON_EU_DESTINATION',
  'NO_DESTINATION_COUNTRY',
  'NO_CUSTOMER_TYPE',
  'SHOP_OUT_OF_SCOPE',
  'NO_ACTIVE_REGISTRATION',
  'THRESHOLD_UNRESOLVED',
  'ORDER_CANCELLED',
] as const;

/** 申报周期（受控值域）。 */
export const FILING_CYCLES = [
  { value: '', label: '全部申报周期' },
  { value: 'MONTHLY', label: '按月申报' },
  { value: 'QUARTERLY', label: '按季申报' },
  { value: 'SEMI_ANNUAL', label: '按半年申报' },
  { value: 'ANNUAL', label: '按年申报' },
] as const;

/** 申报状态（受控值域）。 */
export const FILING_STATUS_FILTERS = [
  { value: '', label: '全部申报状态' },
  { value: 'DRAFT', label: '待提交' },
  { value: 'DRAFTED', label: '已出底稿' },
  { value: 'CONFIRMED', label: '已确认' },
] as const;

// ==================================================================
// 类型
// ==================================================================

/** 一个未交付的下游模块。 */
export interface VatIossCapabilityGap {
  moduleId: string;
  moduleName: string;
  ownerModule: string;
  available: boolean;
  impact: string;
}

/** 一个上游的读取状态（`available=false` ⇒ 显示「读不到」，**不是**「没有」）。 */
export interface VatIossUpstream {
  moduleId: string;
  moduleName: string;
  available: boolean;
  ownerModule: string;
  impact: string;
}

/** 能力声明。 */
export interface VatIossCapabilities {
  gaps: VatIossCapabilityGap[];
  upstreams: VatIossUpstream[];
  filingScopes: string[];
  reasonCodes: string[];
  filingStatuses: string[];
  filingCycles: string[];
  taxTypes: string[];
  classificationSemantics: string;
  scopeSemantics: string;
  filingSemantics: string;
  currencySemantics: string;
  upstreamNote: string;
  downstreamNote: string;
  note: string;
}

/** 一个辖区选项（来自 16.3.1.1 码表，只读）。 */
export interface VatIossJurisdictionOption {
  code: string;
  name: string;
  countryCode: string;
  region: string;
  taxRegime: string;
  vatRequired: boolean;
}

/** 筛选项。 */
export interface VatIossOptions {
  filingScopes: string[];
  reasonCodes: string[];
  filingStatuses: string[];
  filingCycles: string[];
  taxTypes: string[];
  jurisdictions: VatIossJurisdictionOption[];
  currencies: string[];
}

/** 登记的适用店铺。 */
export interface VatIossShopScope {
  platformCode: string;
  shopCode: string;
}

/** 登记的适用商品。 */
export interface VatIossGoodsScope {
  skuCode: string;
  goodsName: string;
  exciseGoods: boolean;
}

/** 一条 IOSS 登记。 */
export interface VatIossRegistrationView {
  id: string;
  registrationNo: string;
  status: string;
  effectiveFrom: string;
  effectiveTo: string;
  current: boolean;
  intermediaryName: string;
  thresholdAmount: string;
  thresholdCurrency: string;
  shopScopes: VatIossShopScope[];
  goodsScopes: VatIossGoodsScope[];
  exciseSkuCodes: string[];
  note: string;
  createdAt: string;
  updatedAt: string;
}

/** 登记清单。 */
export interface VatIossRegistrationPage {
  items: VatIossRegistrationView[];
  total: number;
  page: number;
  size: number;
}

/** 登记的入参。 */
export interface VatIossRegistrationInput {
  registrationNo: string;
  status: string;
  effectiveFrom: string;
  effectiveTo: string;
  intermediaryName: string;
  thresholdAmount: string;
  thresholdCurrency: string;
  shopScopes: VatIossShopScope[];
  goodsScopes: VatIossGoodsScope[];
  note: string;
}

/** 一条申报周期配置。 */
export interface VatIossFilingCycleView {
  id: string;
  jurisdictionCode: string;
  jurisdictionName: string;
  countryCode: string;
  filingCycle: string;
  filingCycleLabel: string;
  filingStartMonth: number | null;
  deadlineDay: number | null;
  status: string;
  note: string;
  createdAt: string;
  updatedAt: string;
}

/** 申报周期配置清单。 */
export interface VatIossFilingCyclePage {
  items: VatIossFilingCycleView[];
  total: number;
  page: number;
  size: number;
}

/** 申报周期的入参。 */
export interface VatIossFilingCycleInput {
  jurisdictionCode: string;
  filingCycle: string;
  filingStartMonth: number | null;
  deadlineDay: number | null;
  status: string;
  note: string;
}

/** 一期申报。 */
export interface VatIossFilingPeriodView {
  id: string;
  jurisdictionCode: string;
  jurisdictionName: string;
  countryCode: string;
  periodStart: string;
  periodEnd: string;
  status: string;
  statusLabel: string;
  draftedAt: string;
  confirmedAt: string;
  confirmedBy: string;
  note: string;
  createdAt: string;
  updatedAt: string;
}

/** 申报期清单。 */
export interface VatIossFilingPeriodPage {
  items: VatIossFilingPeriodView[];
  total: number;
  page: number;
  size: number;
  draftCount: number;
  draftedCount: number;
  confirmedCount: number;
  note: string;
}

/** 申报期的入参。 */
export interface VatIossFilingPeriodInput {
  jurisdictionCode: string;
  periodStart: string;
  periodEnd: string;
  note: string;
}

/** 一笔订单的 IOSS 判定结果。 */
export interface VatIossOrderView {
  id: string;
  sourceOrderId: string;
  sourceSalesOrderId: string;
  sourceOrderNo: string;
  platformCode: string;
  shopCode: string;
  orderSource: string;
  orderStatus: string;
  customerType: string;
  destinationCountry: string;
  jurisdictionCode: string;
  orderDate: string;
  currency: string;
  goodsAmount: string;
  shippingAmount: string;
  taxBase: string;
  taxAmount: string;
  /** 🔴 空 = **读不到**，绝不是 0%。 */
  taxRate: string;
  taxRateSource: string;
  filingScope: string;
  filingScopeLabel: string;
  reasonCode: string;
  reasonLabel: string;
  reasonDetail: string;
  syncedAt: string;
}

/** 订单行明细。 */
export interface VatIossOrderLineView {
  lineNo: number;
  skuCode: string;
  skuName: string;
  quantity: string;
  unitPrice: string;
  lineAmount: string;
  taxAmount: string;
  taxRate: string;
  exciseGoods: boolean;
}

/** 订单详情。 */
export interface VatIossOrderDetail {
  order: VatIossOrderView;
  lines: VatIossOrderLineView[];
}

/** 订单清单。 */
export interface VatIossOrderPage {
  items: VatIossOrderView[];
  total: number;
  page: number;
  size: number;
  iossCount: number;
  generalVatCount: number;
  outOfScopeCount: number;
  unresolvedCount: number;
  note: string;
}

/** 一次识别的结果。 */
export interface VatIossOrderSyncResult {
  scanned: number;
  classified: number;
  iossCount: number;
  generalVatCount: number;
  outOfScopeCount: number;
  unresolvedCount: number;
  truncated: boolean;
  pagesFetched: number;
  note: string;
}

/** 🔴 一组统计：**一个分档 × 一个币种**。 */
export interface VatIossStatisticsRow {
  filingScope: string;
  filingScopeLabel: string;
  currency: string;
  orderCount: number;
  goodsAmount: string;
  shippingAmount: string;
  taxBase: string;
  taxAmount: string;
}

/** 统计结果。 */
export interface VatIossStatistics {
  rows: VatIossStatisticsRow[];
  totalOrders: number;
  /** 🔴 12.6.1.2 未交付 ⇒ false ⇒ 界面显示「**读不到申报底稿**」。 */
  filingDraftAvailable: boolean;
  filingDraftOwnerModule: string;
  filingDraftImpact: string;
  note: string;
}

/** 税率解析结果。 */
export interface VatIossTaxRateView {
  available: boolean;
  taxType: string;
  jurisdictionCode: string;
  jurisdictionName: string;
  rate: string;
  resolvedBy: string;
  asOf: string;
  impact: string;
}

/** 店铺目录里的一行。 */
export interface VatIossShopView {
  shopId: string;
  code: string;
  name: string;
  platformCode: string;
  platformName: string;
  marketplaceCodes: string[];
  countryCodes: string[];
  iossRegistrationId: string;
  iossRegistrationNo: string;
  inIossScope: boolean;
}

/** 店铺目录清单。 */
export interface VatIossShopList {
  items: VatIossShopView[];
  total: number;
  note: string;
}

// ==================================================================
// 纯函数：分档 / 口径（页面直接用，单测也直接测这些）
// ==================================================================

/** 分档的人话。 */
export function scopeLabel(scope: string): string {
  switch (scope) {
    case 'IOSS':
      return 'IOSS 订单';
    case 'GENERAL_VAT':
      return '一般 VAT 订单';
    case 'OUT_OF_SCOPE':
      return '不在申报范围';
    case 'UNRESOLVED':
      return '判不了';
    default:
      return '未知分档';
  }
}

/** 分档的人话（带解释，页面副标题用）。 */
export function scopeDescription(scope: string): string {
  switch (scope) {
    case 'IOSS':
      return '适用 IOSS：欧盟 B2C、货值未超免征门槛、非应税消费品、店铺在 IOSS 登记覆盖范围内。';
    case 'GENERAL_VAT':
      return '不适用 IOSS：走一般 VAT 申报口径。**不要**和 IOSS 混在一起报。';
    case 'OUT_OF_SCOPE':
      return '不在本模块的申报范围（已取消 / 收货地不在欧盟）。';
    case 'UNRESOLVED':
      return '判不了：信息不足以归档。**不计入 IOSS 申报**，但必须留在这里 —— 少算了看起来像「没有」。';
    default:
      return '';
  }
}

/** 判定原因的人话。 */
export function reasonLabel(code: string): string {
  switch (code) {
    case 'IOSS_ELIGIBLE':
      return '符合 IOSS';
    case 'ABOVE_THRESHOLD':
      return '货值超过免征门槛';
    case 'EXCISE_GOODS':
      return '含应税消费品（酒 / 烟 / 能源）';
    case 'B2B_ORDER':
      return 'B2B 订单';
    case 'NON_EU_DESTINATION':
      return '收货地不在欧盟';
    case 'NO_DESTINATION_COUNTRY':
      return '订单没有收货国家';
    case 'NO_CUSTOMER_TYPE':
      return '买家类型没采到（B2C / B2B 判不了）';
    case 'SHOP_OUT_OF_SCOPE':
      return '店铺不在 IOSS 登记覆盖范围内';
    case 'NO_ACTIVE_REGISTRATION':
      return '没有生效的 IOSS 登记';
    case 'THRESHOLD_UNRESOLVED':
      return '汇率读不到，无法折算免征门槛';
    case 'ORDER_CANCELLED':
      return '订单已取消';
    default:
      return '未知原因';
  }
}

/** 申报状态的人话。 */
export function filingStatusLabel(status: string): string {
  switch (status) {
    case 'DRAFT':
      return '待提交（还没出底稿）';
    case 'DRAFTED':
      return '已出底稿（等待人工核对）';
    case 'CONFIRMED':
      return '已确认（数据完整可核）';
    default:
      return '未知状态';
  }
}

/** 🔴 申报状态**只能前进**：这一档之后允许的动作。 */
export function nextFilingActions(status: string): Array<'draft' | 'confirm'> {
  if (status === 'DRAFT') {
    return ['draft'];
  }
  if (status === 'DRAFTED') {
    return ['confirm'];
  }
  return [];
}

/**
 * 🔴 税率显示：空 ⇒ **读不到**，**绝不显示 0%**。
 *
 * @param rate 税率（后端给的是十进制字符串）
 * @param source 税率来源（FINANCE / UNAVAILABLE）
 */
export function taxRateDisplay(rate: string, source: string): string {
  if (!rate) {
    return source === 'UNAVAILABLE' ? '读不到（不是 0%，也不是免税）' : '读不到';
  }
  return `${(Number(rate) * 100).toFixed(2)}%`;
}

/** 🔴 金额显示：**永远带币种**，绝不裸一个数字。 */
export function formatAmount(value: string, currency: string): string {
  const amount = Number(value ?? '0');
  if (!Number.isFinite(amount)) {
    return `${currency} —`;
  }
  return `${currency} ${amount.toFixed(2)}`;
}

/** 一个币种的合计。 */
export interface CurrencyTotal {
  currency: string;
  orderCount: number;
  taxBase: string;
  taxAmount: string;
}

/**
 * 🔴 **按币种分组合计** —— **不同币种绝不直接相加**。
 *
 * <p>这是页面上最要紧的一条：EUR + USD 不是钱，加起来的那一栏是错的。
 */
export function groupTotalsByCurrency(rows: VatIossStatisticsRow[]): CurrencyTotal[] {
  const buckets = new Map<string, CurrencyTotal>();
  for (const row of rows ?? []) {
    const existing = buckets.get(row.currency);
    if (existing) {
      existing.orderCount += row.orderCount;
      existing.taxBase = (Number(existing.taxBase) + Number(row.taxBase)).toFixed(6);
      existing.taxAmount = (Number(existing.taxAmount) + Number(row.taxAmount)).toFixed(6);
    } else {
      buckets.set(row.currency, {
        currency: row.currency,
        orderCount: row.orderCount,
        taxBase: Number(row.taxBase ?? '0').toFixed(6),
        taxAmount: Number(row.taxAmount ?? '0').toFixed(6),
      });
    }
  }
  return [...buckets.values()].sort((a, b) => a.currency.localeCompare(b.currency));
}

/**
 * 🔴 **按分档拆开** —— 取数的第一道分档。
 *
 * <p>四档各自独立；`unresolved` **不并进**任何一档。
 */
export function splitByScope(rows: VatIossStatisticsRow[]): Record<FilingScope, VatIossStatisticsRow[]> {
  const out: Record<FilingScope, VatIossStatisticsRow[]> = {
    IOSS: [],
    GENERAL_VAT: [],
    OUT_OF_SCOPE: [],
    UNRESOLVED: [],
  };
  for (const row of rows ?? []) {
    const scope = row.filingScope as FilingScope;
    if (out[scope]) {
      out[scope].push(row);
    }
  }
  return out;
}

/** 一个分档下的笔数（四档各自独立，**判不了不并进任何一档**）。 */
export function scopeCounts(rows: VatIossStatisticsRow[]): Record<FilingScope, number> {
  const counts: Record<FilingScope, number> = {
    IOSS: 0,
    GENERAL_VAT: 0,
    OUT_OF_SCOPE: 0,
    UNRESOLVED: 0,
  };
  for (const row of rows ?? []) {
    const scope = row.filingScope as FilingScope;
    if (counts[scope] !== undefined) {
      counts[scope] += row.orderCount;
    }
  }
  return counts;
}

/** 三态空态：不同原因说不同的话。 */
export type EmptyKind = 'never-synced' | 'filtered' | 'no-draft';

export function emptyStateKind(input: {
  everSynced: boolean;
  filtered: boolean;
  hasScopeFilter: boolean;
}): EmptyKind {
  if (!input.everSynced) {
    return 'never-synced';
  }
  if (input.filtered && input.hasScopeFilter) {
    return 'filtered';
  }
  return 'no-draft';
}

export function emptyStateText(kind: EmptyKind): string {
  switch (kind) {
    case 'never-synced':
      return '还没有识别过订单 —— 点「识别 IOSS 订单」从 9 订单拉真实订单来判定。';
    case 'filtered':
      return '当前筛选条件下没有订单 —— 换个分档或清掉筛选再看。';
    case 'no-draft':
      return '这一期还没有底稿数据 —— 先识别订单，再按辖区与区间取数。';
    default:
      return '';
  }
}

// ==================================================================
// 端点
// ==================================================================

export async function fetchCapabilities(): Promise<VatIossCapabilities> {
  return api<VatIossCapabilities>(`${BASE}/capabilities`);
}

export async function fetchOptions(): Promise<VatIossOptions> {
  return api<VatIossOptions>(`${BASE}/options`);
}

export async function fetchShops(keyword?: string): Promise<VatIossShopList> {
  const query = new URLSearchParams();
  if (keyword) query.set('keyword', keyword);
  return api<VatIossShopList>(`${BASE}/shops?${query.toString()}`);
}

export async function fetchRegistrations(page = 0, size = 20): Promise<VatIossRegistrationPage> {
  return api<VatIossRegistrationPage>(`${BASE}/registrations?page=${page}&size=${size}`);
}

export async function fetchRegistration(id: string): Promise<VatIossRegistrationView> {
  return api<VatIossRegistrationView>(`${BASE}/registrations/${id}`);
}

export async function createRegistration(
  input: VatIossRegistrationInput,
): Promise<VatIossRegistrationView> {
  return api<VatIossRegistrationView>(`${BASE}/registrations`, 'POST', input);
}

export async function updateRegistration(
  id: string,
  input: VatIossRegistrationInput,
): Promise<VatIossRegistrationView> {
  return api<VatIossRegistrationView>(`${BASE}/registrations/${id}`, 'PUT', input);
}

export async function fetchFilingCycles(
  filters: { jurisdictionCode?: string; page?: number; size?: number } = {},
): Promise<VatIossFilingCyclePage> {
  const query = new URLSearchParams();
  if (filters.jurisdictionCode) query.set('jurisdictionCode', filters.jurisdictionCode);
  query.set('page', String(filters.page ?? 0));
  query.set('size', String(filters.size ?? 20));
  return api<VatIossFilingCyclePage>(`${BASE}/filing-cycles?${query.toString()}`);
}

export async function createFilingCycle(
  input: VatIossFilingCycleInput,
): Promise<VatIossFilingCycleView> {
  return api<VatIossFilingCycleView>(`${BASE}/filing-cycles`, 'POST', input);
}

export async function updateFilingCycle(
  id: string,
  input: VatIossFilingCycleInput,
): Promise<VatIossFilingCycleView> {
  return api<VatIossFilingCycleView>(`${BASE}/filing-cycles/${id}`, 'PUT', input);
}

export async function fetchFilingPeriods(
  filters: {
    jurisdictionCode?: string;
    status?: string;
    page?: number;
    size?: number;
  } = {},
): Promise<VatIossFilingPeriodPage> {
  const query = new URLSearchParams();
  if (filters.jurisdictionCode) query.set('jurisdictionCode', filters.jurisdictionCode);
  if (filters.status) query.set('status', filters.status);
  query.set('page', String(filters.page ?? 0));
  query.set('size', String(filters.size ?? 20));
  return api<VatIossFilingPeriodPage>(`${BASE}/filing-periods?${query.toString()}`);
}

export async function createFilingPeriod(
  input: VatIossFilingPeriodInput,
): Promise<VatIossFilingPeriodView> {
  return api<VatIossFilingPeriodView>(`${BASE}/filing-periods`, 'POST', input);
}

export async function draftFilingPeriod(id: string): Promise<VatIossFilingPeriodView> {
  return api<VatIossFilingPeriodView>(`${BASE}/filing-periods/${id}/draft`, 'POST', {});
}

export async function confirmFilingPeriod(id: string): Promise<VatIossFilingPeriodView> {
  return api<VatIossFilingPeriodView>(`${BASE}/filing-periods/${id}/confirm`, 'POST', {});
}

export async function fetchFilingPeriodStatistics(id: string): Promise<VatIossStatistics> {
  return api<VatIossStatistics>(`${BASE}/filing-periods/${id}/statistics`);
}

export async function fetchOrders(
  filters: {
    filingScope?: string;
    reasonCode?: string;
    currency?: string;
    jurisdictionCode?: string;
    shopCode?: string;
    keyword?: string;
    dateFrom?: string;
    dateTo?: string;
    page?: number;
    size?: number;
  } = {},
): Promise<VatIossOrderPage> {
  const query = new URLSearchParams();
  if (filters.filingScope) query.set('filingScope', filters.filingScope);
  if (filters.reasonCode) query.set('reasonCode', filters.reasonCode);
  if (filters.currency) query.set('currency', filters.currency);
  if (filters.jurisdictionCode) query.set('jurisdictionCode', filters.jurisdictionCode);
  if (filters.shopCode) query.set('shopCode', filters.shopCode);
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.dateFrom) query.set('dateFrom', filters.dateFrom);
  if (filters.dateTo) query.set('dateTo', filters.dateTo);
  query.set('page', String(filters.page ?? 0));
  query.set('size', String(filters.size ?? 20));
  return api<VatIossOrderPage>(`${BASE}/orders?${query.toString()}`);
}

export async function fetchOrder(id: string): Promise<VatIossOrderDetail> {
  return api<VatIossOrderDetail>(`${BASE}/orders/${id}`);
}

export async function syncOrders(shopCode?: string): Promise<VatIossOrderSyncResult> {
  const query = new URLSearchParams();
  if (shopCode) query.set('shopCode', shopCode);
  const suffix = query.toString();
  return api<VatIossOrderSyncResult>(`${BASE}/orders/sync${suffix ? `?${suffix}` : ''}`, 'POST', {});
}

export async function fetchStatistics(
  filters: {
    jurisdictionCode?: string;
    filingScope?: string;
    currency?: string;
    shopCode?: string;
    dateFrom?: string;
    dateTo?: string;
  } = {},
): Promise<VatIossStatistics> {
  const query = new URLSearchParams();
  if (filters.jurisdictionCode) query.set('jurisdictionCode', filters.jurisdictionCode);
  if (filters.filingScope) query.set('filingScope', filters.filingScope);
  if (filters.currency) query.set('currency', filters.currency);
  if (filters.shopCode) query.set('shopCode', filters.shopCode);
  if (filters.dateFrom) query.set('dateFrom', filters.dateFrom);
  if (filters.dateTo) query.set('dateTo', filters.dateTo);
  return api<VatIossStatistics>(`${BASE}/statistics?${query.toString()}`);
}

export async function fetchTaxRate(
  jurisdictionCode: string,
  shopCode?: string,
  asOf?: string,
): Promise<VatIossTaxRateView> {
  const query = new URLSearchParams();
  query.set('jurisdictionCode', jurisdictionCode);
  if (shopCode) query.set('shopCode', shopCode);
  if (asOf) query.set('asOf', asOf);
  return api<VatIossTaxRateView>(`${BASE}/tax-rates?${query.toString()}`);
}
