import { api } from '../../lib/api';

/**
 * 16.3.1.1 税号与辖区 前端模型。
 *
 * 蓝图：「维护各店铺/法人在各销售辖区的税务登记：税号、VAT 状态、是否注册、EPR 注册号、
 * 注册有效期与申报周期。输入：税号录入；辖区选择；有效期。输出：税务登记档案 + 缺失项提示。
 * 关系：上游 = 17 法人、2 店铺；下游 = 16.3.2.1、12.6 税务。
 * **未注册辖区的销售额是合规炸弹**：欧洲没有 VAT 号还在欧盟销售，是稽查重点。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **缺失项是后端算出来的真实清单**，不是前端拼的一句提醒。页面把每一条缺失项的
 *    **类型、严重度、人话说明与整改建议**原样显示 —— 前端**不重算**、不自己编依据。
 * 2. **「读不到」显示成「读不到」**：法人档案读不到时显示「读不到」并给出原因，
 *    **不显示成空白**，也**不显示成「没绑定法人」**。
 * 3. **「推不出要求」不等于「都齐了」**：美国各州登记推不出来（没有销售事实），
 *    界面原样转述后端的口径，不把「没有缺口」写成「都齐了」。
 * 4. **辖区是受控值域**：辖区从后端的码表来，前端**不提供自由输入**。
 * 5. **「未注册」与「缺号」是两条不同的缺失项**：不合并成一条「不合规」。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有 channel 分支）。 */
export const BASE = 'channel/tax-registration';

/** 缺失项类型的筛选项（顺序与后端一致）。 */
export const GAP_TYPES = [
  { value: '', label: '全部缺失项' },
  { value: 'JURISDICTION_NOT_REGISTERED', label: '未注册该辖区' },
  { value: 'VAT_NUMBER_MISSING', label: '缺 VAT 号（登记号）' },
  { value: 'EPR_NUMBER_MISSING', label: '缺 EPR 注册号' },
  { value: 'REGISTRATION_EXPIRED', label: '登记已过有效期' },
  { value: 'REGISTRATION_EXPIRING_SOON', label: '登记即将到期' },
  { value: 'REGISTRATION_VALIDITY_UNKNOWN', label: '没记有效期（判不了）' },
] as const;

/** 严重度的筛选项。 */
export const SEVERITIES = [
  { value: '', label: '全部严重度' },
  { value: 'HIGH', label: '高（合规炸弹）' },
  { value: 'MEDIUM', label: '中（要补）' },
] as const;

/** 主体类型的筛选项。 */
export const SUBJECT_TYPES = [
  { value: '', label: '全部主体' },
  { value: 'SHOP', label: '店铺' },
  { value: 'LEGAL_ENTITY', label: '法人' },
] as const;

/** VAT 状态的筛选项。 */
export const VAT_STATUSES = [
  { value: '', label: '全部 VAT 状态' },
  { value: 'REGISTERED', label: '已注册' },
  { value: 'NOT_REGISTERED', label: '未注册' },
  { value: 'PENDING', label: '注册中' },
  { value: 'EXEMPT', label: '豁免（无本地 VAT 号）' },
  { value: 'UNKNOWN', label: '未知（还没录入）' },
] as const;

// ==================================================================
// 类型
// ==================================================================

/** 一个未交付的下游模块。 */
export interface TaxRegistrationCapabilityGap {
  moduleId: string;
  moduleName: string;
  ownerModule: string;
  available: boolean;
  impact: string;
}

/** 一个上游的读取状态（`available=false` ⇒ 显示「读不到」，**不是**「没有」）。 */
export interface TaxRegistrationUpstream {
  moduleId: string;
  moduleName: string;
  available: boolean;
  ownerModule: string;
  impact: string;
}

/** 能力声明。 */
export interface TaxRegistrationCapabilities {
  gaps: TaxRegistrationCapabilityGap[];
  upstreams: TaxRegistrationUpstream[];
  gapTypes: string[];
  gapSeverities: string[];
  vatStatuses: string[];
  filingCycles: string[];
  subjectTypes: string[];
  gapSemantics: string;
  subjectSemantics: string;
  jurisdictionSemantics: string;
  upstreamNote: string;
  downstreamNote: string;
  reportingScopeNote: string;
}

/** 一个销售辖区（受控值域里的一行）。 */
export interface TaxJurisdictionView {
  code: string;
  name: string;
  region: string;
  countryCode: string;
  subdivisionCode: string;
  taxRegime: string;
  vatRequired: boolean;
  requiresEpr: boolean;
  defaultFilingCycle: string | null;
  derivationScope: string;
}

/** 辖区码表清单。 */
export interface TaxJurisdictionList {
  items: TaxJurisdictionView[];
  total: number;
  note: string;
}

/** 店铺目录里的一行。 */
export interface TaxShopView {
  shopId: string;
  code: string;
  name: string;
  platformCode: string;
  platformName: string;
  legalEntityId: string;
  marketplaceCodes: string[];
  requiredJurisdictionCodes: string[];
}

/** 店铺目录清单。 */
export interface TaxShopList {
  items: TaxShopView[];
  total: number;
  note: string;
}

/** 一条税务登记档案。 */
export interface TaxRegistrationView {
  id: string;
  subjectType: string;
  subjectId: string;
  subjectCode: string;
  subjectName: string;
  subjectAvailable: boolean;
  subjectNote: string;
  jurisdictionCode: string;
  jurisdictionName: string;
  taxRegime: string;
  taxNumber: string;
  vatStatus: string;
  vatStatusLabel: string;
  isRegistered: boolean;
  vatNumber: string;
  eprRegistrationNo: string;
  filingCycle: string;
  registrationValidFrom: string;
  registrationValidUntil: string;
  status: string;
  effectiveStatus: string;
  daysToExpiry: number | null;
  note: string;
  createdAt: string;
  updatedAt: string;
}

/** 登记档案清单（真分页）。 */
export interface TaxRegistrationPage {
  items: TaxRegistrationView[];
  total: number;
  page: number;
  size: number;
}

/** 一条缺失项。 */
export interface TaxGapItem {
  gapType: string;
  gapTypeLabel: string;
  severity: string;
  subjectType: string;
  subjectId: string;
  subjectCode: string;
  subjectName: string;
  shopId: string;
  shopCode: string;
  shopName: string;
  jurisdictionCode: string;
  jurisdictionName: string;
  message: string;
  remediation: string;
}

/** 缺失项清单（真分页 + 汇总计数）。 */
export interface TaxGapPage {
  items: TaxGapItem[];
  total: number;
  page: number;
  size: number;
  highCount: number;
  mediumCount: number;
  affectedShopCount: number;
  note: string;
}

/** 按缺失项类型分的计数。 */
export interface TaxGapTypeCount {
  gapType: string;
  gapTypeLabel: string;
  severity: string;
  count: number;
}

/** 缺失项汇总。 */
export interface TaxGapSummary {
  total: number;
  highCount: number;
  mediumCount: number;
  affectedShopCount: number;
  affectedSubjectCount: number;
  byGapType: Record<string, number>;
  gapTypeCounts: TaxGapTypeCount[];
  note: string;
}

/** 一个辖区在某家店上的覆盖情况。 */
export interface TaxCoverageItem {
  jurisdictionCode: string;
  jurisdictionName: string;
  registrationId: string;
  registrationState: string;
  covered: boolean;
}

/** 一家店的辖区覆盖情况。 */
export interface TaxCoverageRow {
  shopId: string;
  shopCode: string;
  shopName: string;
  platformCode: string;
  requiredJurisdictionCount: number;
  coveredJurisdictionCount: number;
  gapCount: number;
  coverageStatus: string;
  coverageStatusLabel: string;
  jurisdictions: TaxCoverageItem[];
}

/** 覆盖情况清单。 */
export interface TaxCoverageList {
  items: TaxCoverageRow[];
  total: number;
  note: string;
}

/** 登记的入参。 */
export interface TaxRegistrationInput {
  subjectType: string;
  subjectId: string;
  jurisdictionCode: string;
  taxNumber: string;
  vatStatus: string;
  isRegistered: boolean;
  vatNumber: string;
  eprRegistrationNo: string;
  filingCycle: string;
  registrationValidFrom: string;
  registrationValidUntil: string;
  status: string;
  note: string;
}

// ==================================================================
// API
// ==================================================================

export async function fetchCapabilities(): Promise<TaxRegistrationCapabilities> {
  return api<TaxRegistrationCapabilities>(`${BASE}/capabilities`);
}

export async function fetchJurisdictions(): Promise<TaxJurisdictionList> {
  return api<TaxJurisdictionList>(`${BASE}/jurisdictions`);
}

export async function fetchShops(keyword?: string): Promise<TaxShopList> {
  const query = new URLSearchParams();
  if (keyword) query.set('keyword', keyword);
  const suffix = query.toString();
  return api<TaxShopList>(suffix ? `${BASE}/shops?${suffix}` : `${BASE}/shops`);
}

export interface RegistrationFilters {
  subjectType?: string;
  subjectId?: string;
  jurisdictionCode?: string;
  vatStatus?: string;
  registered?: string;
  status?: string;
}

export async function fetchRegistrations(
  page = 0,
  size = 10,
  filters: RegistrationFilters = {},
): Promise<TaxRegistrationPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.subjectType) query.set('subjectType', filters.subjectType);
  if (filters.subjectId) query.set('subjectId', filters.subjectId);
  if (filters.jurisdictionCode) query.set('jurisdictionCode', filters.jurisdictionCode);
  if (filters.vatStatus) query.set('vatStatus', filters.vatStatus);
  if (filters.registered) query.set('registered', filters.registered);
  if (filters.status) query.set('status', filters.status);
  return api<TaxRegistrationPage>(`${BASE}/registrations?${query.toString()}`);
}

export async function fetchRegistration(id: string): Promise<TaxRegistrationView> {
  return api<TaxRegistrationView>(`${BASE}/registrations/${id}`);
}

export async function createRegistration(input: TaxRegistrationInput): Promise<TaxRegistrationView> {
  return api<TaxRegistrationView>(`${BASE}/registrations`, 'POST', input);
}

export async function updateRegistration(
  id: string,
  input: TaxRegistrationInput,
): Promise<TaxRegistrationView> {
  return api<TaxRegistrationView>(`${BASE}/registrations/${id}`, 'PUT', input);
}

export interface GapFilters {
  gapType?: string;
  severity?: string;
  subjectType?: string;
  shopId?: string;
  jurisdictionCode?: string;
}

export async function fetchGaps(
  page = 0,
  size = 10,
  filters: GapFilters = {},
): Promise<TaxGapPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.gapType) query.set('gapType', filters.gapType);
  if (filters.severity) query.set('severity', filters.severity);
  if (filters.subjectType) query.set('subjectType', filters.subjectType);
  if (filters.shopId) query.set('shopId', filters.shopId);
  if (filters.jurisdictionCode) query.set('jurisdictionCode', filters.jurisdictionCode);
  return api<TaxGapPage>(`${BASE}/gaps?${query.toString()}`);
}

export async function fetchGapSummary(filters: GapFilters = {}): Promise<TaxGapSummary> {
  const query = new URLSearchParams();
  if (filters.gapType) query.set('gapType', filters.gapType);
  if (filters.severity) query.set('severity', filters.severity);
  if (filters.subjectType) query.set('subjectType', filters.subjectType);
  if (filters.shopId) query.set('shopId', filters.shopId);
  if (filters.jurisdictionCode) query.set('jurisdictionCode', filters.jurisdictionCode);
  const suffix = query.toString();
  return api<TaxGapSummary>(suffix ? `${BASE}/gap-summary?${suffix}` : `${BASE}/gap-summary`);
}

export async function fetchCoverage(): Promise<TaxCoverageList> {
  return api<TaxCoverageList>(`${BASE}/coverage`);
}

// ==================================================================
// 展示口径（**不重算后端的结论**）
// ==================================================================

/** 缺失项类型的人话（后端已给一份，这里给一份本地兜底）。 */
export function gapTypeLabel(gapType: string): string {
  switch (gapType) {
    case 'JURISDICTION_NOT_REGISTERED':
      return '未注册该辖区';
    case 'VAT_NUMBER_MISSING':
      return '缺 VAT 号（登记号）';
    case 'EPR_NUMBER_MISSING':
      return '缺 EPR 注册号';
    case 'REGISTRATION_EXPIRED':
      return '登记已过有效期';
    case 'REGISTRATION_EXPIRING_SOON':
      return '登记即将到期';
    case 'REGISTRATION_VALIDITY_UNKNOWN':
      return '没记有效期（判不了）';
    default:
      return gapType;
  }
}

/** 严重度的人话。 */
export function severityLabel(severity: string): string {
  return severity === 'HIGH' ? '高（合规炸弹）' : '中（要补）';
}

/** 严重度的徽章色调。 */
export function severityTone(severity: string): 'danger' | 'warning' | 'neutral' {
  if (severity === 'HIGH') return 'danger';
  if (severity === 'MEDIUM') return 'warning';
  return 'neutral';
}

/** VAT 状态的人话。 */
export function vatStatusLabel(vatStatus: string): string {
  switch (vatStatus) {
    case 'REGISTERED':
      return '已注册';
    case 'NOT_REGISTERED':
      return '未注册';
    case 'PENDING':
      return '注册中';
    case 'EXEMPT':
      return '豁免（无本地 VAT 号）';
    case 'UNKNOWN':
      return '未知（还没录入）';
    default:
      return vatStatus;
  }
}

/** 申报周期的人话。 */
export function filingCycleLabel(cycle: string): string {
  switch (cycle) {
    case 'MONTHLY':
      return '按月';
    case 'QUARTERLY':
      return '按季';
    case 'SEMI_ANNUAL':
      return '按半年';
    case 'ANNUAL':
      return '按年';
    default:
      return '未登记';
  }
}

/** 登记生效状态的人话。 */
export function effectiveStatusLabel(status: string): string {
  switch (status) {
    case 'ACTIVE':
      return '生效中';
    case 'EXPIRED':
      return '已过期';
    case 'ARCHIVED':
      return '已撤下';
    default:
      return status;
  }
}

/** 生效状态的徽章色调。 */
export function effectiveStatusTone(status: string): 'success' | 'danger' | 'neutral' {
  if (status === 'ACTIVE') return 'success';
  if (status === 'EXPIRED') return 'danger';
  return 'neutral';
}

/** 覆盖状态的人话。 */
export function coverageStateLabel(state: string): string {
  switch (state) {
    case 'REGISTERED':
      return '已登记';
    case 'EXPIRED':
      return '已过期';
    case 'NOT_REGISTERED':
      return '未注册';
    default:
      return '没有登记';
  }
}

/**
 * 🔴 主体显示串：**读不到上游时显示「读不到」**，不显示空白，也不显示「未注册」。
 *
 * <p>把「读不到」渲染成空白，等于告诉运营「这个主体没有法人」——
 * 那是这类合规台账最致命的一种撒谎。
 */
export function subjectText(
  subjectType: string,
  subjectCode: string,
  subjectName: string,
  available: boolean,
  note: string,
): string {
  if (!available) {
    return note ? `读不到（${note}）` : '读不到';
  }
  const name = subjectName || subjectCode;
  if (!name) {
    return subjectType === 'LEGAL_ENTITY' ? '法人' : '店铺';
  }
  return subjectType === 'LEGAL_ENTITY' ? `法人 ${name}` : name;
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
          '税号与辖区是**按店铺 / 法人**登记的。先在「渠道与店铺」里建好店铺，再回到本页录入税务登记。',
      };
    case 'NO_FILTER_MATCH':
      return {
        title: '当前筛选条件下没有匹配的记录',
        description:
          '把筛选条件放宽再看看。**这不等于「没有登记」** —— 换个类型或清掉筛选就能看到别的记录。',
      };
    case 'NO_REGISTRATION':
      return {
        title: '还没有任何税务登记',
        description:
          '在这个租户下还没有录过一条税务登记。选一个店铺（或法人）和一个辖区，把税号、VAT 号、'
          + 'EPR 注册号与有效期录进来 —— 录进来的登记才能算出「缺什么」。',
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
