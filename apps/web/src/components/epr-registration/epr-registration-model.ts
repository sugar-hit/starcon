import { api } from '../../lib/api';

/**
 * 16.3.2.2 EPR/包装法 前端模型。
 *
 * 蓝图：「管理 EPR 与包装法合规（德国 LUCID、包装注册、电池法、WEEE 等）：注册号、适用国家与品类、
 * 申报周期与关联费用。输入：注册信息；适用规则；申报数据。输出：EPR 档案 + 合规状态 + 费用记录。
 * 关系：上游 = 16.3.1.1；下游 = 12 财务（费用）、4 刊登（是否允许在相关国家销售）。
 * **EPR 未注册的国家应直接限制刊登**，这是避免下架与罚款最有效的做法。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **闸门结论是后端算出来的**：前端**不重算**、不自己编依据；后端说阻断就是阻断。
 * 2. **「不适用」与「未注册」分开显示**：美国不要求 EPR 就是「不适用」，不是「未注册」。
 *    把两者混为一谈会把不该拦的全拦了。
 * 3. **「判不了」是第三态**：没记有效期 / 没有生效版本 / 登记已停用 ⇒ 仍然不放行，
 *    但界面要说清「这是判不了，不是已知违规」。
 * 4. **金额按币种分组显示**：**不同币种绝不直接相加**（EUR + USD 不是钱）。
 * 5. **适用品类是多对多**：一条登记适用多个国家 + 多个品类 ⇒ 「这个国家这个品类有没有覆盖」
 *    是本模块的输出，不是内部细节。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有 channel 分支）。 */
export const BASE = 'channel/epr-registrations';

/** 制度的筛选项（顺序与后端一致）。 */
export const REGIME_TYPES = [
  { value: '', label: '全部制度' },
  { value: 'LUCID', label: '德国 LUCID 包装登记' },
  { value: 'PACKAGING_REGISTRATION', label: '包装注册' },
  { value: 'BATTERY', label: '电池法' },
  { value: 'WEEE', label: '电子废弃物（WEEE）' },
] as const;

/** 申报周期的筛选项。 */
export const FILING_CYCLES = [
  { value: '', label: '全部申报周期' },
  { value: 'MONTHLY', label: '按月' },
  { value: 'QUARTERLY', label: '按季' },
  { value: 'ANNUAL', label: '按年' },
  { value: 'ONE_TIME', label: '一次性' },
] as const;

/** 有效期形态的筛选项。 */
export const VALIDITY_KINDS = [
  { value: '', label: '全部有效期形态' },
  { value: 'FIXED', label: '有效期到某一天' },
  { value: 'OPEN_ENDED', label: '长期有效（未设失效日）' },
  { value: 'UNKNOWN', label: '没记有效期（判不了）' },
] as const;

/** 判定原因的筛选项。 */
export const REASONS = [
  { value: '', label: '全部判定' },
  { value: 'OK', label: '已覆盖' },
  { value: 'NOT_APPLICABLE', label: '不适用' },
  { value: 'EXEMPTED', label: '不适用（已声明）' },
  { value: 'NOT_REGISTERED', label: '未注册' },
  { value: 'EXPIRED', label: '登记已过有效期' },
  { value: 'VALIDITY_UNKNOWN', label: '有效期判不了' },
  { value: 'NOT_EFFECTIVE_YET', label: '当前没有生效的登记版本' },
  { value: 'DISABLED', label: '登记已停用' },
  { value: 'CATEGORY_NOT_COVERED', label: '适用品类没覆盖' },
] as const;

/** 费用的筛选项。 */
export const FEE_TYPES = [
  { value: '', label: '全部费用类型' },
  { value: 'REGISTRATION_FEE', label: '注册费' },
  { value: 'FILING_FEE', label: '申报费' },
  { value: 'PENALTY', label: '罚款' },
  { value: 'OTHER', label: '其他' },
] as const;

/** 费用状态的筛选项。 */
export const FEE_STATUSES = [
  { value: '', label: '全部费用状态' },
  { value: 'RECORDED', label: '已记录（未付）' },
  { value: 'PAID', label: '已付' },
  { value: 'WAIVED', label: '已豁免' },
] as const;

/** 承担方的筛选项。 */
export const PAID_BY = [
  { value: '', label: '全部承担方' },
  { value: 'SELLER', label: '卖家承担' },
  { value: 'PLATFORM', label: '平台承担' },
  { value: 'SERVICE_PROVIDER', label: '服务商承担' },
] as const;

// ==================================================================
// 类型
// ==================================================================

/** 一个未交付的下游模块。 */
export interface EprCapabilityGap {
  moduleId: string;
  moduleName: string;
  ownerModule: string;
  available: boolean;
  impact: string;
}

/** 一个上游的读取状态（`available=false` ⇒ 显示「读不到」，**不是**「没有」）。 */
export interface EprUpstream {
  moduleId: string;
  moduleName: string;
  available: boolean;
  ownerModule: string;
  impact: string;
}

/** 能力声明。 */
export interface EprCapabilities {
  gaps: EprCapabilityGap[];
  upstreams: EprUpstream[];
  regimeTypes: string[];
  categories: string[];
  filingCycles: string[];
  validityKinds: string[];
  feeTypes: string[];
  feeStatuses: string[];
  paidBy: string[];
  reasons: string[];
  conclusions: string[];
  filingStatuses: string[];
  regimeSemantics: string;
  applicabilitySemantics: string;
  filingSemantics: string;
  feeSemantics: string;
  upstreamNote: string;
  downstreamNote: string;
  enforcementNote: string;
}

/** 一个国家 × 一条制度的适用事实。 */
export interface EprCountryRegimeView {
  countryCode: string;
  countryName: string;
  regimeCode: string;
  regimeName: string;
  authority: string;
  categoryCodes: string[];
}

/** 一个制度（受控值域里的一行）+ 它在哪些国家适用。 */
export interface EprRegimeView {
  code: string;
  name: string;
  authority: string;
  defaultFilingCycle: string;
  countries: EprCountryRegimeView[];
}

/** 制度码表清单。 */
export interface EprRegimeList {
  items: EprRegimeView[];
  total: number;
  note: string;
}

/** 一个适用品类。 */
export interface EprCategoryView {
  code: string;
  name: string;
}

/** 适用品类码表清单。 */
export interface EprCategoryList {
  items: EprCategoryView[];
  total: number;
  note: string;
}

/** 店铺目录里的一行。 */
export interface EprShopView {
  shopId: string;
  code: string;
  name: string;
  platformCode: string;
  platformName: string;
  marketplaceCodes: string[];
  eprCountryCodes: string[];
}

/** 店铺目录清单。 */
export interface EprShopList {
  items: EprShopView[];
  total: number;
  note: string;
}

/** 新建 / 修改一条 EPR 注册的入参。 */
export interface EprRegistrationInput {
  regimeCode: string;
  registrationNo: string;
  status: string;
  validityKind: string;
  validFrom: string;
  validUntil: string;
  filingCycle: string;
  filingStartMonth: number | null;
  filingEndMonth: number | null;
  effectiveFrom: string;
  countryCodes: string[];
  categoryCodes: string[];
  note: string;
}

/** 一条 EPR 制度级注册档案。 */
export interface EprRegistrationView {
  id: string;
  registrationKey: string;
  versionNo: number;
  regimeCode: string;
  regimeName: string;
  authority: string;
  registrationNo: string;
  status: string;
  validityKind: string;
  validFrom: string;
  validUntil: string;
  filingCycle: string;
  filingCycleLabel: string;
  filingStartMonth: number | null;
  filingEndMonth: number | null;
  filingStatus: string;
  filingStatusLabel: string;
  filingPeriodLabel: string;
  daysToNextFiling: number | null;
  effectiveFrom: string;
  effectiveTo: string;
  current: boolean;
  countryCodes: string[];
  categoryCodes: string[];
  daysToExpiry: number | null;
  note: string;
  createdAt: string;
  updatedAt: string;
}

/** EPR 档案清单（真分页）。 */
export interface EprRegistrationPage {
  items: EprRegistrationView[];
  total: number;
  page: number;
  size: number;
}

/** 一个生效版本。 */
export interface EprVersionView {
  id: string;
  versionNo: number;
  registrationNo: string;
  status: string;
  validityKind: string;
  validFrom: string;
  validUntil: string;
  filingCycle: string;
  effectiveFrom: string;
  effectiveTo: string;
  countryCodes: string[];
  categoryCodes: string[];
  note: string;
  createdAt: string;
}

/** 版本历史。 */
export interface EprVersionList {
  registrationKey: string;
  items: EprVersionView[];
  note: string;
}

/** 一条覆盖结论：这个国家这个制度有没有被覆盖。 */
export interface EprCoverageRow {
  countryCode: string;
  countryName: string;
  regimeCode: string;
  regimeName: string;
  applicable: boolean;
  categoryCodes: string[];
  registrationId: string;
  registrationNo: string;
  covered: boolean;
  reasonCode: string;
  reasonLabel: string;
  message: string;
}

/** 覆盖情况清单（真分页）。 */
export interface EprCoveragePage {
  items: EprCoverageRow[];
  total: number;
  page: number;
  size: number;
  coveredCount: number;
  blockedCount: number;
  notApplicableCount: number;
  note: string;
}

/** 一条阻断 / 放行原因。 */
export interface EprGateReason {
  regimeCode: string;
  regimeName: string;
  countryCode: string;
  countryName: string;
  categoryCode: string;
  reasonCode: string;
  reasonLabel: string;
  message: string;
  blocking: boolean;
}

/** 一个判定口径下的结论。 */
export interface EprGateScope {
  allowed: boolean;
  conclusion: string;
  conclusionLabel: string;
  reasons: EprGateReason[];
}

/** 刊登闸门结论（服务端算出来的）。 */
export interface EprPublishGate {
  allowed: boolean;
  conclusion: string;
  conclusionLabel: string;
  countryCode: string;
  countryName: string;
  categoryCode: string;
  reasons: EprGateReason[];
  countryScope: EprGateScope;
  evaluatedAt: string;
  note: string;
}

/** 批量刊登闸门结论。 */
export interface EprPublishGateBatch {
  allowed: boolean;
  gates: EprPublishGate[];
  blockedCount: number;
  evaluatedAt: string;
  note: string;
}

/** 一个国家的合规状态。 */
export interface EprCountryStatus {
  countryCode: string;
  countryName: string;
  applicableRegimeCount: number;
  coveredRegimeCount: number;
  blockedRegimeCount: number;
  status: string;
  statusLabel: string;
  reasons: EprGateReason[];
}

/** 合规状态汇总。 */
export interface EprComplianceStatus {
  countries: EprCountryStatus[];
  total: number;
  compliantCount: number;
  nonCompliantCount: number;
  indeterminateCount: number;
  note: string;
}

/** 一条申报期状态。 */
export interface EprFilingRow {
  registrationId: string;
  registrationKey: string;
  regimeCode: string;
  regimeName: string;
  registrationNo: string;
  filingCycle: string;
  filingCycleLabel: string;
  filingStartMonth: number | null;
  filingEndMonth: number | null;
  filingStatus: string;
  filingStatusLabel: string;
  filingPeriodLabel: string;
  daysToNextFiling: number | null;
  countryCodes: string[];
  categoryCodes: string[];
}

/** 申报期状态清单（真分页）。 */
export interface EprFilingPage {
  items: EprFilingRow[];
  total: number;
  page: number;
  size: number;
  inPeriodCount: number;
  note: string;
}

/** 一条不适用声明。 */
export interface EprExemptionView {
  id: string;
  countryCode: string;
  countryName: string;
  regimeCode: string;
  regimeName: string;
  status: string;
  effectiveFrom: string;
  effectiveTo: string;
  reason: string;
  note: string;
  createdAt: string;
}

/** 不适用声明清单（真分页）。 */
export interface EprExemptionPage {
  items: EprExemptionView[];
  total: number;
  page: number;
  size: number;
  activeCount: number;
  note: string;
}

/** 新建 / 撤销一条不适用声明的入参。 */
export interface EprExemptionInput {
  countryCode: string;
  regimeCode: string;
  reason: string;
  effectiveFrom: string;
  note: string;
}

/** 一笔费用（金额是**十进制字符串**，不经浮点）。 */
export interface EprFeeView {
  id: string;
  registrationKey: string;
  regimeCode: string;
  regimeName: string;
  registrationNo: string;
  feeType: string;
  feeTypeLabel: string;
  amount: string;
  currency: string;
  billingCycle: string;
  billingCycleLabel: string;
  periodStart: string;
  periodEnd: string;
  status: string;
  statusLabel: string;
  paidBy: string;
  paidByLabel: string;
  note: string;
  createdAt: string;
}

/** 费用清单（真分页）。 */
export interface EprFeePage {
  items: EprFeeView[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 新建 / 修改一笔费用的入参。 */
export interface EprFeeInput {
  registrationKey: string;
  feeType: string;
  amount: string;
  currency: string;
  billingCycle: string;
  periodStart: string;
  periodEnd: string;
  status: string;
  paidBy: string;
  note: string;
}

/** 按币种分组的合计（**不同币种绝不直接相加**）。 */
export interface EprFeeTotal {
  currency: string;
  total: string;
  count: number;
}

/** 费用汇总。 */
export interface EprFeeSummary {
  totals: EprFeeTotal[];
  totalRecords: number;
  currencies: string[];
  note: string;
}

/** 刊登闸门的入参。 */
export interface EprPublishGateRequest {
  countryCode: string;
  categoryCode: string;
  shopId: string;
}

// ==================================================================
// API
// ==================================================================

export async function fetchCapabilities(): Promise<EprCapabilities> {
  return api<EprCapabilities>(`${BASE}/capabilities`);
}

export async function fetchRegimes(): Promise<EprRegimeList> {
  return api<EprRegimeList>(`${BASE}/regimes`);
}

export async function fetchCategories(): Promise<EprCategoryList> {
  return api<EprCategoryList>(`${BASE}/categories`);
}

export async function fetchShops(): Promise<EprShopList> {
  return api<EprShopList>(`${BASE}/shops`);
}

export interface RegistrationFilters {
  // 🔴 `| undefined` 是**显式**允许的：仓库开了 exactOptionalPropertyTypes，
  //    筛选器由 `x || undefined` 组装，不显式写出来就编不过。
  regimeCode?: string | undefined;
  status?: string | undefined;
  validityKind?: string | undefined;
  countryCode?: string | undefined;
}

export async function fetchRegistrations(
  page = 0,
  size = 10,
  filters: RegistrationFilters = {},
): Promise<EprRegistrationPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.regimeCode) query.set('regimeCode', filters.regimeCode);
  if (filters.status) query.set('status', filters.status);
  if (filters.validityKind) query.set('validityKind', filters.validityKind);
  if (filters.countryCode) query.set('countryCode', filters.countryCode);
  return api<EprRegistrationPage>(`${BASE}/registrations?${query.toString()}`);
}

export async function fetchRegistration(id: string): Promise<EprRegistrationView> {
  return api<EprRegistrationView>(`${BASE}/registrations/${id}`);
}

export async function fetchVersions(id: string): Promise<EprVersionList> {
  return api<EprVersionList>(`${BASE}/registrations/${id}/versions`);
}

export async function createRegistration(input: EprRegistrationInput): Promise<EprRegistrationView> {
  return api<EprRegistrationView>(`${BASE}/registrations`, 'POST', input);
}

export async function updateRegistration(
  id: string,
  input: EprRegistrationInput,
): Promise<EprRegistrationView> {
  return api<EprRegistrationView>(`${BASE}/registrations/${id}`, 'PUT', input);
}

export interface CoverageFilters {
  countryCode?: string | undefined;
  regimeCode?: string | undefined;
  categoryCode?: string | undefined;
}

export async function fetchCoverage(
  page = 0,
  size = 10,
  filters: CoverageFilters = {},
): Promise<EprCoveragePage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.countryCode) query.set('countryCode', filters.countryCode);
  if (filters.regimeCode) query.set('regimeCode', filters.regimeCode);
  if (filters.categoryCode) query.set('categoryCode', filters.categoryCode);
  return api<EprCoveragePage>(`${BASE}/coverage?${query.toString()}`);
}

export async function fetchComplianceStatus(countryCode?: string): Promise<EprComplianceStatus> {
  const query = new URLSearchParams();
  if (countryCode) query.set('countryCode', countryCode);
  const suffix = query.toString();
  return api<EprComplianceStatus>(suffix ? `${BASE}/compliance-status?${suffix}` : `${BASE}/compliance-status`);
}

export async function publishGate(
  request: EprPublishGateRequest,
): Promise<EprPublishGate> {
  return api<EprPublishGate>(`${BASE}/publish-gate`, 'POST', request);
}

/**
 * 🔴 批量刊登闸门：一次问多个目标。
 *
 * <p>只要有**一个**目标被阻断，整批就不放行 —— 结论由服务端算，前端不重算。
 */
export async function publishGateBatch(
  requests: EprPublishGateRequest[],
): Promise<EprPublishGateBatch> {
  return api<EprPublishGateBatch>(`${BASE}/publish-gate/batch`, 'POST', { requests });
}

export async function fetchFilings(
  page = 0,
  size = 10,
  filters: { regimeCode?: string | undefined; inPeriod?: string | undefined } = {},
): Promise<EprFilingPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.regimeCode) query.set('regimeCode', filters.regimeCode);
  if (filters.inPeriod) query.set('inPeriod', filters.inPeriod);
  return api<EprFilingPage>(`${BASE}/filings?${query.toString()}`);
}

export interface FeeFilters {
  registrationKey?: string | undefined;
  currency?: string | undefined;
  feeType?: string | undefined;
  billingCycle?: string | undefined;
  status?: string | undefined;
}

export async function fetchFees(
  page = 0,
  size = 10,
  filters: FeeFilters = {},
): Promise<EprFeePage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.registrationKey) query.set('registrationKey', filters.registrationKey);
  if (filters.currency) query.set('currency', filters.currency);
  if (filters.feeType) query.set('feeType', filters.feeType);
  if (filters.billingCycle) query.set('billingCycle', filters.billingCycle);
  if (filters.status) query.set('status', filters.status);
  return api<EprFeePage>(`${BASE}/fees?${query.toString()}`);
}

export async function fetchFeeSummary(filters: FeeFilters = {}): Promise<EprFeeSummary> {
  const query = new URLSearchParams();
  if (filters.registrationKey) query.set('registrationKey', filters.registrationKey);
  if (filters.currency) query.set('currency', filters.currency);
  if (filters.feeType) query.set('feeType', filters.feeType);
  const suffix = query.toString();
  return api<EprFeeSummary>(suffix ? `${BASE}/fee-summary?${suffix}` : `${BASE}/fee-summary`);
}

export async function createFee(input: EprFeeInput): Promise<EprFeeView> {
  return api<EprFeeView>(`${BASE}/fees`, 'POST', input);
}

export async function updateFee(id: string, input: EprFeeInput): Promise<EprFeeView> {
  return api<EprFeeView>(`${BASE}/fees/${id}`, 'PUT', input);
}

export async function fetchExemptions(
  page = 0,
  size = 10,
  filters: { countryCode?: string | undefined; regimeCode?: string | undefined } = {},
): Promise<EprExemptionPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.countryCode) query.set('countryCode', filters.countryCode);
  if (filters.regimeCode) query.set('regimeCode', filters.regimeCode);
  return api<EprExemptionPage>(`${BASE}/exemptions?${query.toString()}`);
}

export async function createExemption(input: EprExemptionInput): Promise<EprExemptionView> {
  return api<EprExemptionView>(`${BASE}/exemptions`, 'POST', input);
}

export async function revokeExemption(id: string): Promise<EprExemptionView> {
  return api<EprExemptionView>(`${BASE}/exemptions/${id}`, 'PUT');
}

// ==================================================================
// 展示口径（**不重算后端的结论**）
// ==================================================================

/** 判定原因的人话（后端已给一份，这里给一份本地兜底）。 */
export function reasonLabel(reasonCode: string): string {
  switch (reasonCode) {
    case 'OK':
      return '已覆盖';
    case 'NOT_APPLICABLE':
      return '不适用';
    case 'EXEMPTED':
      return '不适用（已声明）';
    case 'NOT_REGISTERED':
      return '未注册';
    case 'EXPIRED':
      return '登记已过有效期';
    case 'VALIDITY_UNKNOWN':
      return '有效期判不了';
    case 'NOT_EFFECTIVE_YET':
      return '当前没有生效的登记版本';
    case 'DISABLED':
      return '登记已停用';
    case 'CATEGORY_NOT_COVERED':
      return '适用品类没覆盖';
    default:
      return reasonCode;
  }
}

/** 判定原因的徽章色调。 */
export function reasonTone(reasonCode: string): 'success' | 'danger' | 'warning' | 'neutral' {
  if (reasonCode === 'OK') return 'success';
  if (reasonCode === 'NOT_APPLICABLE' || reasonCode === 'EXEMPTED') return 'neutral';
  if (reasonCode === 'VALIDITY_UNKNOWN' || reasonCode === 'NOT_EFFECTIVE_YET') return 'warning';
  return 'danger';
}

/** 制度的人话。 */
export function regimeLabel(regimeCode: string): string {
  switch (regimeCode) {
    case 'LUCID':
      return '德国 LUCID 包装登记';
    case 'PACKAGING_REGISTRATION':
      return '包装注册';
    case 'BATTERY':
      return '电池法';
    case 'WEEE':
      return '电子废弃物（WEEE）';
    default:
      return regimeCode;
  }
}

/** 申报周期的人话。 */
export function filingCycleLabel(cycle: string): string {
  switch (cycle) {
    case 'MONTHLY':
      return '按月';
    case 'QUARTERLY':
      return '按季';
    case 'ANNUAL':
      return '按年';
    case 'ONE_TIME':
      return '一次性';
    default:
      return '未登记';
  }
}

/** 有效期形态的人话。 */
export function validityKindLabel(kind: string): string {
  switch (kind) {
    case 'FIXED':
      return '有效期到某一天';
    case 'OPEN_ENDED':
      return '长期有效（未设失效日）';
    case 'UNKNOWN':
      return '没记有效期（判不了）';
    default:
      return kind;
  }
}

/** 申报期状态的人话。 */
export function filingStatusLabel(status: string): string {
  switch (status) {
    case 'IN_PERIOD':
      return '当前在申报期内';
    case 'OUT_OF_PERIOD':
      return '当前不在申报期内';
    case 'NOT_RECURRING':
      return '一次性申报（无周期）';
    case 'UNKNOWN':
      return '没记申报月份（判不了）';
    default:
      return status;
  }
}

/** 申报期状态的徽章色调。 */
export function filingStatusTone(status: string): 'success' | 'warning' | 'neutral' {
  if (status === 'IN_PERIOD') return 'success';
  if (status === 'OUT_OF_PERIOD') return 'warning';
  return 'neutral';
}

/** 登记状态的徽章色调。 */
export function statusTone(status: string): 'success' | 'danger' | 'neutral' {
  if (status === 'ACTIVE') return 'success';
  if (status === 'DISABLED') return 'danger';
  return 'neutral';
}

/** 费用状态的人话。 */
export function feeStatusLabel(status: string): string {
  switch (status) {
    case 'RECORDED':
      return '已记录（未付）';
    case 'PAID':
      return '已付';
    case 'WAIVED':
      return '已豁免';
    default:
      return status;
  }
}

/** 承担方的人话。 */
export function paidByLabel(paidBy: string): string {
  switch (paidBy) {
    case 'SELLER':
      return '卖家承担';
    case 'PLATFORM':
      return '平台承担';
    case 'SERVICE_PROVIDER':
      return '服务商承担';
    default:
      return paidBy;
  }
}

/** 合规状态的徽章色调。 */
export function countryStatusTone(status: string): 'success' | 'danger' | 'warning' | 'neutral' {
  if (status === 'COMPLIANT') return 'success';
  if (status === 'NON_COMPLIANT') return 'danger';
  if (status === 'INDETERMINATE') return 'warning';
  return 'neutral';
}

/** 合规状态的人话。 */
export function countryStatusLabel(status: string): string {
  switch (status) {
    case 'COMPLIANT':
      return '已覆盖';
    case 'NON_COMPLIANT':
      return '未覆盖（阻断刊登）';
    case 'INDETERMINATE':
      return '判不了（仍不放行）';
    case 'NOT_APPLICABLE':
      return '不适用（该国不要求 EPR）';
    default:
      return status;
  }
}

/**
 * 🔴 金额展示：**按币种分组**，**不同币种绝不直接相加**。
 *
 * <p>返回的是「一组一组的合计」，不是一个总数 —— EUR + USD 不是钱。
 */
export function groupTotalsByCurrency(totals: EprFeeTotal[]): { currency: string; total: string }[] {
  return totals.map((item) => ({ currency: item.currency, total: item.total }));
}

/**
 * 🔴 空态文案：**三态不同**。
 *
 * @param kind 'NO_SHOP' 租户下还没有店铺 /
 *             'NO_FILTER_MATCH' 当前筛选条件下没有匹配 /
 *             'NO_REGISTRATION' 有店铺但**一条登记都没有**
 */
export function emptyState(
  kind: 'NO_SHOP' | 'NO_FILTER_MATCH' | 'NO_REGISTRATION',
): { title: string; description: string } {
  switch (kind) {
    case 'NO_SHOP':
      return {
        title: '这个租户下还没有店铺',
        description:
          'EPR 合规是**按店铺在哪些国家卖**来判定的。先在「渠道与店铺」里建好店铺并启用站点，再回到本页录入 EPR 登记。',
      };
    case 'NO_FILTER_MATCH':
      return {
        title: '当前筛选条件下没有匹配的记录',
        description:
          '把筛选条件放宽再看看。**这不等于「没有 EPR 义务」** —— 换个制度或清掉筛选就能看到别的记录。',
      };
    case 'NO_REGISTRATION':
      return {
        title: '还没有任何 EPR 登记',
        description:
          '在这个租户下还没有录过一条 EPR 登记。选一个制度，把注册号、适用国家与适用品类录进来 —— '
          + '录进来的登记才能算出「哪些国家能不能刊登」。',
      };
    default:
      return { title: '', description: '' };
  }
}

/**
 * 🔴 空态的判定：**「筛选无结果」与「一条登记都没有」必须分开**。
 */
export function emptyStateKind(
  totalShops: number,
  filteredTotal: number,
  anyRegistration: boolean,
): 'NO_SHOP' | 'NO_FILTER_MATCH' | 'NO_REGISTRATION' {
  if (totalShops === 0) return 'NO_SHOP';
  if (!anyRegistration) return 'NO_REGISTRATION';
  if (filteredTotal === 0) return 'NO_FILTER_MATCH';
  return 'NO_FILTER_MATCH';
}
