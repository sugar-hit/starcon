import { api } from '../../lib/api';

/**
 * 16.3.2.3 销售税与 Marketplace Facilitator 前端模型。
 *
 * 蓝图：「处理美国各州销售税与 Marketplace Facilitator 制度：应缴/代缴状态、州级阈值、免征额、
 * 按平台的代扣代缴说明与对账。输入：店铺所在州数据；平台代扣代缴报表；自营应税销售额。
 * 输出：各州税务状态 + 代扣代缴对账结果。关系：上游 = 2 店铺、12.3.1.2；下游 = 12.6 申报。
 * **平台代扣代缴不等于免税**，卖家的申报义务依然存在，只是由平台代收代缴，需要正确对账。」
 *
 * 🔴 这个页面上最容易做丢的六件事，这里都挡住了：
 *
 * 1. **应缴与已代缴是两条线**：前端**不压成净额**、不自己算差额 —— 后端给了就照显示。
 *    压成净额就看不出「平台少收了要补缴」与「平台多收了要退款」。
 * 2. **「判不了」是第三态**：没有生效规则 / 没登记店铺所在州 ⇒ 仍然显示「判不了」，
 *    **不许**在前端把它渲染成「应缴 0」或绿色。
 * 3. **税率是数据不是常量**：页面**不内置任何州税率表**，税率、阈值、免征额全部来自后端规则。
 * 4. **金额按币种分组**：**不同币种绝不直接相加**。
 * 5. **规则是版本化的**：改规则 = 发新版本，页面显示的是「当前生效的那一版」+ 版本号。
 * 6. **三态空态各不相同**：「没有州」「筛选无结果」「还没有对账记录」分开说。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有 channel 分支）。 */
export const BASE = 'channel/sales-tax';

/** 销售税联系类型（受控值域，后端钉死）。 */
export const NEXUS_TYPES = [
  { value: '', label: '全部联系类型' },
  { value: 'PHYSICAL_PRESENCE', label: '物理存在' },
  { value: 'INVENTORY_STORED', label: '库存存放' },
  { value: 'ECONOMIC_NEXUS', label: '经济联系' },
  { value: 'MARKETPLACE_FACILITATOR', label: '仅平台代收' },
] as const;

/** 申报周期（受控值域）。 */
export const FILING_CYCLES = [
  { value: '', label: '全部申报周期' },
  { value: 'MONTHLY', label: '按月' },
  { value: 'QUARTERLY', label: '按季' },
  { value: 'SEMI_ANNUAL', label: '半年' },
  { value: 'ANNUAL', label: '按年' },
] as const;

/** 登记状态。 */
export const REGISTRATION_STATUSES = [
  { value: '', label: '全部登记状态' },
  { value: 'REGISTERED', label: '已登记' },
  { value: 'PARTIAL', label: '部分已登记' },
  { value: 'NOT_REGISTERED', label: '未登记' },
] as const;

/** 对账状态。 */
export const RECONCILIATION_STATUSES = [
  { value: '', label: '全部对账状态' },
  { value: 'BALANCED', label: '已对平' },
  { value: 'VARIANCE', label: '有差异' },
  { value: 'BELOW_THRESHOLD', label: '未达阈值' },
  { value: 'NO_ACTIVITY', label: '无活动' },
  { value: 'RULE_MISSING', label: '没有生效规则（判不了）' },
  { value: 'NEXUS_MISSING', label: '没登记所在州（判不了）' },
] as const;

// ==================================================================
// 类型
// ==================================================================

/** 一个未交付的下游模块。 */
export interface SalesTaxCapabilityGap {
  moduleId: string;
  moduleName: string;
  ownerModule: string;
  available: boolean;
  impact: string;
}

/** 一个上游的读取状态（`available=false` ⇒ 显示「读不到」，**不是**「没有」）。 */
export interface SalesTaxUpstream {
  moduleId: string;
  moduleName: string;
  available: boolean;
  ownerModule: string;
  impact: string;
}

/** 能力声明。 */
export interface SalesTaxCapabilities {
  gaps: SalesTaxCapabilityGap[];
  upstreams: SalesTaxUpstream[];
  nexusTypes: string[];
  filingCycles: string[];
  reconciliationStatuses: string[];
  filingObligations: string[];
  rateSemantics: string;
  reconciliationSemantics: string;
  upstreamNote: string;
  downstreamNote: string;
}

/** 一个州的销售税状态。 */
export interface SalesTaxStateRow {
  stateCode: string;
  stateName: string;
  shopCount: number;
  registeredShopCount: number;
  registrationStatus: string;
  ruleStatus: string;
  ruleVersion: number | null;
  baseRate: number | null;
  thresholdAmount: number | null;
  exemptionAmount: number | null;
  filingCycle: string;
  marketplaceFacilitator: boolean;
  facilitatorScopeCount: number;
  latestReconciliationId: string | null;
  latestPeriodStart: string | null;
  latestPeriodEnd: string | null;
  currency: string | null;
  selfTaxableSales: number | null;
  expectedTax: number | null;
  facilitatorTax: number | null;
  variance: number | null;
  reconciliationStatus: string | null;
  filingObligation: string;
  status: string;
  statusLabel: string;
  notes: string[];
}

/** 州状态清单。 */
export interface SalesTaxStatePage {
  items: SalesTaxStateRow[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 一个店铺 + 它在哪些州有销售税联系。 */
export interface SalesTaxShopView {
  id: string;
  code: string;
  name: string;
  platformCode: string;
  platformName: string;
  status: string;
  operatingMode: string;
  stateCodes: string[];
  registeredStateCount: number;
}

/** 店铺目录。 */
export interface SalesTaxShopPage {
  items: SalesTaxShopView[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 一版销售税率规则。 */
export interface SalesTaxRateRuleView {
  id: string;
  stateCode: string;
  stateName: string;
  ruleVersion: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  baseRate: number;
  thresholdAmount: number;
  exemptionAmount: number;
  marketplaceFacilitator: boolean;
  filingCycle: string;
  status: string;
  note: string;
  createdAt: string;
  updatedAt: string;
}

/** 税率规则清单。 */
export interface SalesTaxRateRulePage {
  items: SalesTaxRateRuleView[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 录入 / 改一版税率规则。 */
export interface SalesTaxRateRuleInput {
  stateCode: string;
  effectiveFrom: string;
  effectiveTo?: string | null | undefined;
  baseRate: string;
  thresholdAmount: string;
  exemptionAmount: string;
  marketplaceFacilitator: boolean;
  filingCycle: string;
  note?: string | undefined;
}

/** 某一刻生效的那一版规则。 */
export interface SalesTaxEffectiveRule {
  stateCode: string;
  stateName: string;
  asOf: string;
  found: boolean;
  rule: SalesTaxRateRuleView | null;
  note: string;
}

/** 一家店在一个州的销售税联系。 */
export interface SalesTaxShopStateView {
  id: string;
  shopId: string;
  shopCode: string;
  shopName: string;
  stateCode: string;
  stateName: string;
  nexusType: string;
  registered: boolean;
  registrationNo: string;
  registeredFrom: string | null;
  note: string;
  createdAt: string;
  updatedAt: string;
}

/** 登记 / 改一家店在一个州的销售税联系。 */
export interface SalesTaxShopStateInput {
  shopId: string;
  stateCode: string;
  nexusType: string;
  registered: boolean;
  registrationNo?: string | undefined;
  registeredFrom?: string | null | undefined;
  note?: string | undefined;
}

/** 店铺州联系清单。 */
export interface SalesTaxShopStatePage {
  items: SalesTaxShopStateView[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 一个平台在一个州的代扣代缴说明。 */
export interface SalesTaxFacilitatorScopeView {
  id: string;
  stateCode: string;
  stateName: string;
  platformCode: string;
  facilitatorEnabled: boolean;
  remitTo: string;
  reportRequired: boolean;
  note: string;
  createdAt: string;
  updatedAt: string;
}

/** 登记 / 改一条代扣代缴说明。 */
export interface SalesTaxFacilitatorScopeInput {
  stateCode: string;
  platformCode: string;
  facilitatorEnabled: boolean;
  remitTo?: string | undefined;
  reportRequired: boolean;
  note?: string | undefined;
}

/** 代扣代缴说明清单。 */
export interface SalesTaxFacilitatorScopePage {
  items: SalesTaxFacilitatorScopeView[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 一份平台代扣代缴报表。 */
export interface SalesTaxFacilitatorReportView {
  id: string;
  platformCode: string;
  fileKey: string;
  fileName: string;
  contentType: string;
  contentHash: string;
  byteSize: number;
  parseVersion: number;
  parseStatus: string;
  parseWarnings: string[];
  periodStart: string;
  periodEnd: string;
  uploadedBy: string | null;
  parsedAt: string;
  createdAt: string;
  lineCount: number;
}

/** 一行代扣代缴明细。 */
export interface SalesTaxFacilitatorLineView {
  id: string;
  stateCode: string;
  stateName: string;
  platformCode: string;
  shopCode: string;
  taxableAmount: number;
  taxCollected: number;
  orderCount: number;
  currency: string;
  sourceLineNo: number;
  remark: string;
}

/** 一份报表的详情 = 报表本体 + 逐行解析结果。 */
export interface SalesTaxFacilitatorReportDetail {
  report: SalesTaxFacilitatorReportView;
  lines: SalesTaxFacilitatorLineView[];
}

/** 报表清单。 */
export interface SalesTaxFacilitatorReportPage {
  items: SalesTaxFacilitatorReportView[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 上传一份代扣代缴报表。 */
export interface SalesTaxFacilitatorImportInput {
  platformCode: string;
  fileKey: string;
  fileName: string;
  contentType: 'CSV' | 'JSON';
  content: string;
  periodStart: string;
  periodEnd: string;
}

/** 导入结果。 */
export interface SalesTaxFacilitatorImportResult {
  report: SalesTaxFacilitatorReportView;
  scanned: number;
  imported: number;
  skipped: number;
  warnings: string[];
}

/** 一条对账结果。 */
export interface SalesTaxReconciliationView {
  id: string;
  stateCode: string;
  stateName: string;
  periodStart: string;
  periodEnd: string;
  currency: string;
  selfTaxableSales: number;
  exemptSales: number;
  taxableBase: number;
  expectedTax: number;
  facilitatorTax: number;
  settlementTax: number;
  variance: number;
  ruleVersion: number;
  baseRate: number;
  thresholdAmount: number;
  exemptionAmount: number;
  rateChangedInPeriod: boolean;
  status: string;
  statusLabel: string;
  shopCount: number;
  computedAt: string;
}

/** 算一次对账的请求。 */
export interface SalesTaxReconciliationRequest {
  stateCode?: string | undefined;
  periodStart: string;
  periodEnd: string;
  currency?: string | undefined;
}

/** 对账结果清单。 */
export interface SalesTaxReconciliationPage {
  items: SalesTaxReconciliationView[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 一条申报义务（下游 12.6 接手用）。 */
export interface SalesTaxObligationRow {
  reconciliationId: string;
  stateCode: string;
  stateName: string;
  periodStart: string;
  periodEnd: string;
  currency: string;
  expectedTax: number;
  facilitatorTax: number;
  variance: number;
  filingCycle: string;
  registrationStatus: string;
  reconciliationStatus: string;
  filingObligation: string;
  note: string;
}

/** 申报义务清单。 */
export interface SalesTaxObligationPage {
  items: SalesTaxObligationRow[];
  total: number;
  page: number;
  size: number;
  note: string;
}

// ==================================================================
// 端点
// ==================================================================

export async function fetchCapabilities(): Promise<SalesTaxCapabilities> {
  return api<SalesTaxCapabilities>(`${BASE}/capabilities`);
}

export interface StateFilters {
  stateCode?: string | undefined;
  registered?: boolean | undefined;
  ruleStatus?: string | undefined;
  asOf?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export async function fetchStates(filters: StateFilters = {}): Promise<SalesTaxStatePage> {
  const query = new URLSearchParams();
  if (filters.stateCode) query.set('stateCode', filters.stateCode);
  if (filters.registered !== undefined) query.set('registered', String(filters.registered));
  if (filters.ruleStatus) query.set('ruleStatus', filters.ruleStatus);
  if (filters.asOf) query.set('asOf', filters.asOf);
  query.set('page', String(filters.page ?? 0));
  query.set('size', String(filters.size ?? 20));
  return api<SalesTaxStatePage>(`${BASE}/states?${query.toString()}`);
}

export async function fetchShops(keyword?: string): Promise<SalesTaxShopPage> {
  const query = new URLSearchParams();
  if (keyword) query.set('keyword', keyword);
  query.set('page', '0');
  query.set('size', '50');
  return api<SalesTaxShopPage>(`${BASE}/shops?${query.toString()}`);
}

export interface RateRuleFilters {
  stateCode?: string | undefined;
  status?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export async function fetchRateRules(
  filters: RateRuleFilters = {},
): Promise<SalesTaxRateRulePage> {
  const query = new URLSearchParams();
  if (filters.stateCode) query.set('stateCode', filters.stateCode);
  if (filters.status) query.set('status', filters.status);
  query.set('page', String(filters.page ?? 0));
  query.set('size', String(filters.size ?? 20));
  return api<SalesTaxRateRulePage>(`${BASE}/rate-rules?${query.toString()}`);
}

export async function fetchEffectiveRule(
  stateCode: string,
  asOf?: string,
): Promise<SalesTaxEffectiveRule> {
  const query = new URLSearchParams();
  query.set('stateCode', stateCode);
  if (asOf) query.set('asOf', asOf);
  return api<SalesTaxEffectiveRule>(`${BASE}/rate-rules/effective?${query.toString()}`);
}

export async function createRateRule(
  input: SalesTaxRateRuleInput,
): Promise<SalesTaxRateRuleView> {
  return api<SalesTaxRateRuleView>(`${BASE}/rate-rules`, 'POST', input);
}

export async function updateRateRule(
  id: string,
  input: SalesTaxRateRuleInput,
): Promise<SalesTaxRateRuleView> {
  return api<SalesTaxRateRuleView>(`${BASE}/rate-rules/${id}`, 'PUT', input);
}

export async function archiveRateRule(id: string): Promise<SalesTaxRateRuleView> {
  return api<SalesTaxRateRuleView>(`${BASE}/rate-rules/${id}/archive`, 'PUT', {});
}

export interface ShopStateFilters {
  shopId?: string | undefined;
  stateCode?: string | undefined;
  nexusType?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export async function fetchShopStates(
  filters: ShopStateFilters = {},
): Promise<SalesTaxShopStatePage> {
  const query = new URLSearchParams();
  if (filters.shopId) query.set('shopId', filters.shopId);
  if (filters.stateCode) query.set('stateCode', filters.stateCode);
  if (filters.nexusType) query.set('nexusType', filters.nexusType);
  query.set('page', String(filters.page ?? 0));
  query.set('size', String(filters.size ?? 20));
  return api<SalesTaxShopStatePage>(`${BASE}/shop-states?${query.toString()}`);
}

export async function createShopState(
  input: SalesTaxShopStateInput,
): Promise<SalesTaxShopStateView> {
  return api<SalesTaxShopStateView>(`${BASE}/shop-states`, 'POST', input);
}

export async function updateShopState(
  id: string,
  input: SalesTaxShopStateInput,
): Promise<SalesTaxShopStateView> {
  return api<SalesTaxShopStateView>(`${BASE}/shop-states/${id}`, 'PUT', input);
}

export interface FacilitatorScopeFilters {
  stateCode?: string | undefined;
  platformCode?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export async function fetchFacilitatorScopes(
  filters: FacilitatorScopeFilters = {},
): Promise<SalesTaxFacilitatorScopePage> {
  const query = new URLSearchParams();
  if (filters.stateCode) query.set('stateCode', filters.stateCode);
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  query.set('page', String(filters.page ?? 0));
  query.set('size', String(filters.size ?? 20));
  return api<SalesTaxFacilitatorScopePage>(`${BASE}/facilitator-scopes?${query.toString()}`);
}

export async function createFacilitatorScope(
  input: SalesTaxFacilitatorScopeInput,
): Promise<SalesTaxFacilitatorScopeView> {
  return api<SalesTaxFacilitatorScopeView>(`${BASE}/facilitator-scopes`, 'POST', input);
}

export async function updateFacilitatorScope(
  id: string,
  input: SalesTaxFacilitatorScopeInput,
): Promise<SalesTaxFacilitatorScopeView> {
  return api<SalesTaxFacilitatorScopeView>(`${BASE}/facilitator-scopes/${id}`, 'PUT', input);
}

export interface ReportFilters {
  platformCode?: string | undefined;
  periodStart?: string | undefined;
  periodEnd?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export async function fetchFacilitatorReports(
  filters: ReportFilters = {},
): Promise<SalesTaxFacilitatorReportPage> {
  const query = new URLSearchParams();
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.periodStart) query.set('periodStart', filters.periodStart);
  if (filters.periodEnd) query.set('periodEnd', filters.periodEnd);
  query.set('page', String(filters.page ?? 0));
  query.set('size', String(filters.size ?? 20));
  return api<SalesTaxFacilitatorReportPage>(`${BASE}/facilitator-reports?${query.toString()}`);
}

export async function fetchFacilitatorReport(
  id: string,
): Promise<SalesTaxFacilitatorReportDetail> {
  return api<SalesTaxFacilitatorReportDetail>(`${BASE}/facilitator-reports/${id}`);
}

export async function importFacilitatorReport(
  input: SalesTaxFacilitatorImportInput,
): Promise<SalesTaxFacilitatorImportResult> {
  return api<SalesTaxFacilitatorImportResult>(
    `${BASE}/facilitator-reports/import`,
    'POST',
    input,
  );
}

export interface ReconciliationFilters {
  stateCode?: string | undefined;
  periodStart?: string | undefined;
  periodEnd?: string | undefined;
  currency?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export async function fetchReconciliations(
  filters: ReconciliationFilters = {},
): Promise<SalesTaxReconciliationPage> {
  const query = new URLSearchParams();
  if (filters.stateCode) query.set('stateCode', filters.stateCode);
  if (filters.periodStart) query.set('periodStart', filters.periodStart);
  if (filters.periodEnd) query.set('periodEnd', filters.periodEnd);
  if (filters.currency) query.set('currency', filters.currency);
  query.set('page', String(filters.page ?? 0));
  query.set('size', String(filters.size ?? 20));
  return api<SalesTaxReconciliationPage>(`${BASE}/reconciliations?${query.toString()}`);
}

export async function computeReconciliations(
  request: SalesTaxReconciliationRequest,
): Promise<SalesTaxReconciliationView[]> {
  return api<SalesTaxReconciliationView[]>(`${BASE}/reconciliations`, 'POST', request);
}

export async function fetchObligations(
  filters: ReconciliationFilters = {},
): Promise<SalesTaxObligationPage> {
  const query = new URLSearchParams();
  if (filters.stateCode) query.set('stateCode', filters.stateCode);
  if (filters.periodStart) query.set('periodStart', filters.periodStart);
  if (filters.periodEnd) query.set('periodEnd', filters.periodEnd);
  if (filters.currency) query.set('currency', filters.currency);
  query.set('page', String(filters.page ?? 0));
  query.set('size', String(filters.size ?? 20));
  return api<SalesTaxObligationPage>(`${BASE}/obligations?${query.toString()}`);
}

// ==================================================================
// 展示口径
// ==================================================================

/** 对账状态的人话与色调（前端只做**兜底**翻译，后端已给 statusLabel）。 */
export function reconciliationStatusLabel(status: string): string {
  switch (status) {
    case 'BALANCED':
      return '已对平';
    case 'VARIANCE':
      return '有差异';
    case 'BELOW_THRESHOLD':
      return '未达州级阈值';
    case 'NO_ACTIVITY':
      return '期间无销售也无代缴';
    case 'RULE_MISSING':
      return '没有生效的税率规则（判不了）';
    case 'NEXUS_MISSING':
      return '没登记店铺所在州（判不了）';
    default:
      return '未知状态';
  }
}

export function reconciliationStatusTone(
  status: string,
): 'success' | 'danger' | 'warning' | 'neutral' {
  switch (status) {
    case 'BALANCED':
      return 'success';
    case 'VARIANCE':
      return 'danger';
    case 'BELOW_THRESHOLD':
      return 'neutral';
    case 'RULE_MISSING':
    case 'NEXUS_MISSING':
      return 'warning';
    default:
      return 'neutral';
  }
}

/**
 * 🔴 差异的显示口径：**正数 = 平台少收了（卖家要补缴）**，负数 = 平台多收了。
 *
 * <p>这个符号约定必须在界面上说清 —— 只显示一个带正负号的数字，核对员根本不知道
 * 「正数」是要补钱还是要退钱。
 */
export function varianceMeaning(variance: number): string {
  if (variance > 0) return '平台少收了，卖家要补缴';
  if (variance < 0) return '平台多收了，可能要退';
  return '已对平';
}

export function varianceTone(variance: number): 'success' | 'danger' | 'warning' | 'neutral' {
  if (variance > 0) return 'danger';
  if (variance < 0) return 'warning';
  return 'success';
}

/** 申报义务的人话。 */
export function filingObligationLabel(obligation: string): string {
  switch (obligation) {
    case 'FILING_REQUIRED':
      return '需要申报（平台代缴不抵消申报义务）';
    case 'BELOW_THRESHOLD':
      return '期间不需要申报（未达州级阈值）';
    case 'NO_ACTIVITY':
      return '期间不需要申报（无销售无代缴）';
    case 'RULE_MISSING':
      return '判不了（没有生效的税率规则）';
    case 'NOT_REGISTERED':
      return '州里还没有店铺登记销售税账号';
    default:
      return '未知';
  }
}

/** 登记状态的人话。 */
export function registrationStatusLabel(status: string): string {
  switch (status) {
    case 'REGISTERED':
      return '已登记';
    case 'PARTIAL':
      return '部分已登记';
    case 'NOT_REGISTERED':
      return '未登记';
    default:
      return '未知';
  }
}

/**
 * 🔴 税率的显示口径：后端给的是**小数率**（0.0725），界面要显示成**百分数**（7.25%）。
 *
 * <p>但「显示成百分数」和「它是一个 7.25% 的数字」是两件事 —— 页面上必须标出单位，
 * 否则「0.0725」会被读成「0.07%」，差了一百倍。
 */
export function formatRate(rate: number | null): string {
  if (rate === null || rate === undefined) return '—';
  return `${(rate * 100).toFixed(4).replace(/\.?0+$/, '')}%`;
}

/** 金额的显示口径（后端给的是字符串化的十进制或数字，统一按两位小数展示）。 */
export function formatAmount(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 6 });
}

/**
 * 🔴 **按币种分组**：不同币种绝不直接相加。
 *
 * <p>本模块的金额全部带币种；这个函数把一组金额按币种收拢，返回的就是**可以直接相加**的分组 ——
 * 同币种才在组内相加。
 */
export function groupTotalsByCurrency(
  rows: { currency: string; expectedTax: number; facilitatorTax: number }[],
): { currency: string; expectedTax: number; facilitatorTax: number }[] {
  const grouped = new Map<string, { currency: string; expectedTax: number; facilitatorTax: number }>();
  for (const row of rows) {
    const current = grouped.get(row.currency) ?? {
      currency: row.currency,
      expectedTax: 0,
      facilitatorTax: 0,
    };
    grouped.set(row.currency, {
      currency: row.currency,
      expectedTax: current.expectedTax + row.expectedTax,
      facilitatorTax: current.facilitatorTax + row.facilitatorTax,
    });
  }
  return [...grouped.values()].sort((a, b) => a.currency.localeCompare(b.currency));
}

/** 三态空态各不相同：「没有州」「筛选无结果」「还没有对账记录」分开说。 */
export function emptyState(
  kind: 'NO_STATE' | 'NO_FILTER_MATCH' | 'NO_RECONCILIATION',
): { title: string; description: string } {
  switch (kind) {
    case 'NO_STATE':
      return {
        title: '还没有销售税州',
        description:
          '先登记「店铺所在州」，再录该州的税率规则 —— 没有规则的话应缴是判不了的。',
      };
    case 'NO_FILTER_MATCH':
      return {
        title: '没有符合条件的州',
        description: '换个筛选条件再试 —— 州清单是本租户真实有销售税足迹的州。',
      };
    case 'NO_RECONCILIATION':
      return {
        title: '还没有对账记录',
        description:
          '选好对账期间点「算一次对账」：自营应税销售额来自 12.3.1.2 交易明细，平台代扣代缴来自已上传的报表。',
      };
    default:
      return { title: '暂无数据', description: '' };
  }
}

/** 判断当前筛选是否为空（用来区分「没有州」与「筛选无结果」）。 */
export function emptyStateKind(
  filters: {
    stateCode?: string | undefined;
    registered?: boolean | undefined;
    ruleStatus?: string | undefined;
  },
  total: number,
): 'NO_STATE' | 'NO_FILTER_MATCH' | 'NO_RECONCILIATION' {
  const hasFilter =
    Boolean(filters.stateCode) ||
    filters.registered !== undefined ||
    Boolean(filters.ruleStatus);
  if (hasFilter) return 'NO_FILTER_MATCH';
  return total === 0 ? 'NO_STATE' : 'NO_RECONCILIATION';
}
