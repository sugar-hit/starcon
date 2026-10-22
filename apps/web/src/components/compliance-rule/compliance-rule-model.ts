import { api } from '../../lib/api';

/**
 * 16.2.1.1 规则库 前端模型。
 *
 * 蓝图：「管理合规规则库：规则类型（违禁词、极限词、侵权词、认证要求、禁限售、标签要求、材质要求）、
 * 严重度（阻断/警告/提示）、适用平台与站点、生效时间与版本。输入：规则定义与词库上传；版本更新；适用范围配置。
 * 输出：规则集 + 生效版本。关系：上游 = 平台政策同步 + 人工维护；下游 = 16.2.1.2 执行、4.2.3.2 预检。
 * **规则版本化是关键**：政策变化时历史商品的判定结果要可解释，不能因为规则库更新就说过去的商品当时违规。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **改规则 = 新增一个版本** —— 界面上**没有**「改词」「改严重度」的按钮，只有「发新版本」。
 *    把一个生效中的版本的词改掉，历史判定就再也解释不清了。
 * 2. **严重度是版本的属性** —— 规则行上的严重度显示的是**当前生效那一版**的严重度，
 *    页面**不重算**、不缓存、不允许就地改。
 * 3. **生效时刻不许回填** —— 新版本的生效时刻只能选「现在或将来」；
 *    页面把「不许回填」的原文显示出来（它是本模块最要紧的一条纪律）。
 * 4. **未交付的下游灰掉**：16.2.1.2 拦截执行当前**未交付**，
 *    界面上如实标出，**不编**「立即校验 / 放行」这类入口。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有 channel 分支）。 */
export const BASE = 'channel/compliance-rules';

/** 🔴 规则类型值域（与后端 CHECK 逐字一致）：七种。 */
export const RULE_TYPES = [
  'PROHIBITED_WORD',
  'SUPERLATIVE_WORD',
  'INFRINGEMENT_WORD',
  'CERTIFICATION_REQUIRED',
  'RESTRICTED_SALE',
  'LABEL_REQUIREMENT',
  'MATERIAL_REQUIREMENT',
] as const;

/** 🔴 严重度值域（与后端 CHECK 逐字一致）：阻断 / 警告 / 提示。 */
export const SEVERITIES = ['BLOCKING', 'WARNING', 'INFO'] as const;

/** 规则生命周期值域。 */
export const STATUSES = ['ACTIVE', 'RETIRED'] as const;

/** 词条分类值域。 */
export const TERM_KINDS = ['GENERAL', 'BRAND', 'CATEGORY', 'SPELLING'] as const;

/** 🔴 带词条集合的规则类型：违禁词 / 极限词 / 侵权词。 */
export const WORD_RULE_TYPES = ['PROHIBITED_WORD', 'SUPERLATIVE_WORD', 'INFRINGEMENT_WORD'] as const;

/** 规则类型筛选的可选项。 */
export const RULE_TYPE_OPTIONS = [
  { value: '', label: '全部类型' },
  { value: 'PROHIBITED_WORD', label: '违禁词' },
  { value: 'SUPERLATIVE_WORD', label: '极限词' },
  { value: 'INFRINGEMENT_WORD', label: '侵权词' },
  { value: 'CERTIFICATION_REQUIRED', label: '认证要求' },
  { value: 'RESTRICTED_SALE', label: '禁限售' },
  { value: 'LABEL_REQUIREMENT', label: '标签要求' },
  { value: 'MATERIAL_REQUIREMENT', label: '材质要求' },
] as const;

/** 规则状态筛选的可选项。 */
export const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'ACTIVE', label: '在用' },
  { value: 'RETIRED', label: '已停用' },
] as const;

/** 词条分类的可选项。 */
export const TERM_KIND_OPTIONS = [
  { value: 'GENERAL', label: '通用词' },
  { value: 'BRAND', label: '品牌词' },
  { value: 'CATEGORY', label: '品类词' },
  { value: 'SPELLING', label: '拼写变体' },
] as const;

// ==================================================================
// 类型
// ==================================================================

/** 一个下游（或维度）的交付状态。 */
export interface ComplianceRuleCapabilityGap {
  moduleId: string;
  moduleName: string;
  ownerModule: string;
  available: boolean;
  impact: string;
}

/** 能力声明。 */
export interface ComplianceRuleCapabilities {
  gaps: ComplianceRuleCapabilityGap[];
  ruleTypes: string[];
  ruleTypeLabels: Record<string, string>;
  severities: string[];
  severityLabels: Record<string, string>;
  statuses: string[];
  statusLabels: Record<string, string>;
  termKinds: string[];
  termKindLabels: Record<string, string>;
  wordRuleTypes: string[];
  ruleTypeNote: string;
  severityNote: string;
  versioningNote: string;
  wordLibraryNote: string;
  scopeNote: string;
  upstreamNote: string;
  downstreamNote: string;
}

/** 店铺在本模块里的覆盖情况。 */
export interface ComplianceRuleShop {
  shopId: string;
  shopCode: string;
  shopName: string;
  /** 🔴 平台 id —— 配「适用范围」时要用，所以必须给出来 */
  platformId: string;
  platformCode: string;
  platformName: string;
  ruleCount: number;
  effectiveRuleCount: number;
}

/** 店铺范围清单。 */
export interface ComplianceRuleShopPage {
  items: ComplianceRuleShop[];
  total: number;
  page: number;
  size: number;
}

/** 一条规则。 */
export interface ComplianceRule {
  ruleId: string;
  ruleCode: string;
  ruleType: string;
  ruleTypeLabel: string;
  status: string;
  statusLabel: string;
  title: string;
  description: string;
  /** 🔴 当前生效版本的严重度（没有生效版本时为 null） */
  severity: string | null;
  severityLabel: string;
  versionCount: number;
  termCount: number;
  scopeCount: number;
  currentVersionId: string | null;
  currentVersionNo: number | null;
  currentEffectiveFrom: string | null;
  currentEffectiveTo: string | null;
  inForce: boolean;
  createdAt: string;
}

/** 规则清单。 */
export interface ComplianceRulePage {
  items: ComplianceRule[];
  total: number;
  page: number;
  size: number;
  activeCount: number;
  retiredCount: number;
  typeCounts: Record<string, number>;
  severityCounts: Record<string, number>;
  note: string;
}

/** 规则的一个版本。 */
export interface ComplianceRuleVersion {
  versionId: string;
  ruleId: string;
  ruleCode: string;
  versionNo: number;
  /** 🔴 严重度是**版本**的属性 */
  severity: string;
  severityLabel: string;
  title: string;
  description: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  inForce: boolean;
  changeNote: string;
  termCount: number;
  scopeCount: number;
  createdAt: string;
}

/** 版本清单。 */
export interface ComplianceRuleVersionPage {
  items: ComplianceRuleVersion[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 一个词条。 */
export interface ComplianceRuleTerm {
  termId: string;
  ruleVersionId: string;
  term: string;
  termKind: string;
  termKindLabel: string;
  note: string;
  createdAt: string;
}

/** 词条清单。 */
export interface ComplianceRuleTermPage {
  items: ComplianceRuleTerm[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 一条适用范围。 */
export interface ComplianceRuleScope {
  scopeId: string;
  ruleVersionId: string;
  platformId: string;
  platformCode: string;
  platformName: string;
  /** null = 该平台全部站点 */
  marketplaceId: string | null;
  marketplaceCode: string | null;
  marketplaceName: string | null;
  /** null = 该平台/站点下全部店铺 */
  shopId: string | null;
  shopCode: string | null;
  shopName: string | null;
  createdAt: string;
}

/** 适用范围清单。 */
export interface ComplianceRuleScopePage {
  items: ComplianceRuleScope[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 规则详情。 */
export interface ComplianceRuleDetail {
  rule: ComplianceRule;
  versions: ComplianceRuleVersion[];
  note: string;
}

/** 版本详情。 */
export interface ComplianceRuleVersionDetail {
  rule: ComplianceRule | null;
  version: ComplianceRuleVersion;
  terms: ComplianceRuleTerm[];
  scopes: ComplianceRuleScope[];
  note: string;
}

/** 「某一时刻生效的是哪一版」。 */
export interface ComplianceRuleEffectiveVersion {
  rule: ComplianceRule;
  version: ComplianceRuleVersion | null;
  terms: ComplianceRuleTerm[];
  scopes: ComplianceRuleScope[];
  resolvedAt: string;
  note: string;
}

/** 生效规则集里的一条。 */
export interface ComplianceRuleEffectiveEntry {
  rule: ComplianceRule;
  version: ComplianceRuleVersion;
  termCount: number;
  platformCodes: string[];
  marketplaceCodes: string[];
  shopCodes: string[];
  note: string;
}

/** 生效规则集。 */
export interface ComplianceRuleEffectiveSet {
  items: ComplianceRuleEffectiveEntry[];
  total: number;
  page: number;
  size: number;
  resolvedAt: string;
  note: string;
}

/** 一个词条入参。 */
export interface ComplianceRuleTermInput {
  term: string;
  termKind?: string;
  note?: string;
}

/** 一条适用范围入参。 */
export interface ComplianceRuleScopeInput {
  platformId: string;
  marketplaceId?: string | null;
  shopId?: string | null;
}

/** 词条批量落库的结果。 */
export interface ComplianceRuleTermBatchResult {
  ruleVersionId: string;
  requested: number;
  inserted: number;
  skipped: number;
  skippedTerms: string[];
  note: string;
}

/** 适用范围批量落库的结果。 */
export interface ComplianceRuleScopeBatchResult {
  ruleVersionId: string;
  requested: number;
  inserted: number;
  skipped: number;
  skippedScopes: string[];
  note: string;
}

/** 建规则的结果（幂等）。 */
export interface ComplianceRuleCreation {
  rule: ComplianceRule;
  created: boolean;
  note: string;
}

// ==================================================================
// 接口
// ==================================================================

export async function fetchCapabilities(): Promise<ComplianceRuleCapabilities> {
  return api<ComplianceRuleCapabilities>(`${BASE}/capabilities`);
}

export async function fetchShops(
  page = 0,
  size = 20,
  filters: { platformCode?: string; keyword?: string } = {},
): Promise<ComplianceRuleShopPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.keyword) query.set('keyword', filters.keyword);
  return api<ComplianceRuleShopPage>(`${BASE}/shops?${query.toString()}`);
}

export async function fetchRules(
  page = 0,
  size = 20,
  filters: { ruleType?: string; status?: string; platformCode?: string; keyword?: string } = {},
): Promise<ComplianceRulePage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.ruleType) query.set('ruleType', filters.ruleType);
  if (filters.status) query.set('status', filters.status);
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.keyword) query.set('keyword', filters.keyword);
  return api<ComplianceRulePage>(`${BASE}/rules?${query.toString()}`);
}

/** 建规则（只建身份；按规则编码幂等）。 */
export async function createRule(input: {
  ruleCode: string;
  ruleType: string;
  title: string;
  description?: string;
}): Promise<ComplianceRuleCreation> {
  return api<ComplianceRuleCreation>(`${BASE}/rules`, 'POST', input);
}

/** 规则详情。 */
export async function fetchRule(ruleId: string): Promise<ComplianceRuleDetail> {
  return api<ComplianceRuleDetail>(`${BASE}/rules/${ruleId}`);
}

/** 改规则的生命周期（停用 / 重新启用）。 */
export async function updateRuleStatus(ruleId: string, status: string): Promise<ComplianceRule> {
  return api<ComplianceRule>(`${BASE}/rules/${ruleId}`, 'PUT', { status });
}

/** 🔴 发一个新版本 —— **规则改动的唯一入口**。 */
export async function createVersion(
  ruleId: string,
  input: {
    severity: string;
    title: string;
    description?: string;
    effectiveFrom?: string | null;
    changeNote?: string;
    terms?: ComplianceRuleTermInput[];
    termsText?: string | null;
    scopes?: ComplianceRuleScopeInput[];
  },
): Promise<ComplianceRuleVersionDetail> {
  return api<ComplianceRuleVersionDetail>(`${BASE}/rules/${ruleId}/versions`, 'POST', input);
}

/** 版本清单。 */
export async function fetchVersions(
  ruleId: string,
  page = 0,
  size = 20,
): Promise<ComplianceRuleVersionPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  return api<ComplianceRuleVersionPage>(`${BASE}/rules/${ruleId}/versions?${query.toString()}`);
}

/** 🔴 某一时刻生效的是哪一版。 */
export async function fetchEffectiveVersion(
  ruleId: string,
  at?: string | null,
): Promise<ComplianceRuleEffectiveVersion> {
  const query = new URLSearchParams();
  if (at) query.set('at', at);
  // 🔴 路径与查询串**分开拼**：模板串里再嵌一个模板串会让白名单回归脚本
  //   （从源码反向抽真实调用点）抽不出这条路径 —— 抽不到就等于没测。
  const path = `${BASE}/rules/${ruleId}/versions/effective`;
  const suffix = query.toString();
  return api<ComplianceRuleEffectiveVersion>(suffix ? `${path}?${suffix}` : path);
}

/** 版本详情。 */
export async function fetchVersion(
  ruleId: string,
  versionId: string,
): Promise<ComplianceRuleVersionDetail> {
  return api<ComplianceRuleVersionDetail>(`${BASE}/rules/${ruleId}/versions/${versionId}`);
}

/** 词条清单。 */
export async function fetchTerms(
  ruleId: string,
  versionId: string,
  page = 0,
  size = 50,
): Promise<ComplianceRuleTermPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  return api<ComplianceRuleTermPage>(
    `${BASE}/rules/${ruleId}/versions/${versionId}/terms?${query.toString()}`,
  );
}

/** 追加词条（批量）。 */
export async function addTerms(
  ruleId: string,
  versionId: string,
  terms: ComplianceRuleTermInput[],
): Promise<ComplianceRuleTermBatchResult> {
  return api<ComplianceRuleTermBatchResult>(
    `${BASE}/rules/${ruleId}/versions/${versionId}/terms`,
    'POST',
    { terms },
  );
}

/** 🔴 词库上传（整份词库一次落库，每行一个词）。 */
export async function uploadTerms(
  ruleId: string,
  versionId: string,
  content: string,
): Promise<ComplianceRuleTermBatchResult> {
  return api<ComplianceRuleTermBatchResult>(
    `${BASE}/rules/${ruleId}/versions/${versionId}/terms/upload`,
    'POST',
    { content },
  );
}

/** 适用范围清单。 */
export async function fetchScopes(
  ruleId: string,
  versionId: string,
  page = 0,
  size = 50,
): Promise<ComplianceRuleScopePage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  return api<ComplianceRuleScopePage>(
    `${BASE}/rules/${ruleId}/versions/${versionId}/scopes?${query.toString()}`,
  );
}

/** 追加适用范围（批量）。 */
export async function addScopes(
  ruleId: string,
  versionId: string,
  scopes: ComplianceRuleScopeInput[],
): Promise<ComplianceRuleScopeBatchResult> {
  return api<ComplianceRuleScopeBatchResult>(
    `${BASE}/rules/${ruleId}/versions/${versionId}/scopes`,
    'POST',
    { scopes },
  );
}

/** 🔴 生效规则集（规则集 + 生效版本）。 */
export async function fetchEffectiveSet(
  page = 0,
  size = 20,
  filters: { at?: string | null; platformCode?: string; shopId?: string } = {},
): Promise<ComplianceRuleEffectiveSet> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.at) query.set('at', filters.at);
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.shopId) query.set('shopId', filters.shopId);
  return api<ComplianceRuleEffectiveSet>(`${BASE}/effective?${query.toString()}`);
}

// ==================================================================
// 展示口径（**不重算后端的结论**）
// ==================================================================

/** 规则类型的人话。 */
export function ruleTypeLabel(code: string | null | undefined): string {
  switch (code) {
    case 'PROHIBITED_WORD':
      return '违禁词';
    case 'SUPERLATIVE_WORD':
      return '极限词';
    case 'INFRINGEMENT_WORD':
      return '侵权词';
    case 'CERTIFICATION_REQUIRED':
      return '认证要求';
    case 'RESTRICTED_SALE':
      return '禁限售';
    case 'LABEL_REQUIREMENT':
      return '标签要求';
    case 'MATERIAL_REQUIREMENT':
      return '材质要求';
    default:
      return code ?? '未声明';
  }
}

/** 严重度的人话。 */
export function severityLabel(code: string | null | undefined): string {
  switch (code) {
    case 'BLOCKING':
      return '阻断';
    case 'WARNING':
      return '警告';
    case 'INFO':
      return '提示';
    default:
      return code ?? '未声明';
  }
}

/**
 * 🔴 严重度的色调。
 *
 * <p>「阻断」是红的，「警告」是黄的，「提示」是中性色 —— **提示不是「好」**，
 * 把它显示成绿色会让人以为这条规则可以无视。
 */
export function severityTone(
  code: string | null | undefined,
): 'danger' | 'warning' | 'success' | 'neutral' {
  switch (code) {
    case 'BLOCKING':
      return 'danger';
    case 'WARNING':
      return 'warning';
    case 'INFO':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 规则生命周期的人话。 */
export function statusLabel(code: string | null | undefined): string {
  switch (code) {
    case 'ACTIVE':
      return '在用';
    case 'RETIRED':
      return '已停用';
    default:
      return code ?? '未声明';
  }
}

/** 词条分类的人话。 */
export function termKindLabel(code: string | null | undefined): string {
  switch (code) {
    case 'GENERAL':
      return '通用词';
    case 'BRAND':
      return '品牌词';
    case 'CATEGORY':
      return '品类词';
    case 'SPELLING':
      return '拼写变体';
    default:
      return code ?? '未声明';
  }
}

/** 该规则类型是否带词条集合。 */
export function isWordRuleType(code: string | null | undefined): boolean {
  return !!code && (WORD_RULE_TYPES as readonly string[]).includes(code);
}

/**
 * 🔴 生效时间窗的展示串。
 *
 * <p>没有结束时刻 ⇒ 显示「一直有效（直到下一版接替）」，**不是**空白 ——
 * 空白会被读成「这个版本没定」。
 */
export function windowText(
  from: string | null | undefined,
  to: string | null | undefined,
): string {
  const start = from ? new Date(from).toLocaleString('zh-CN') : '未声明';
  if (!to) return `${start} 起 · 一直有效（直到下一版接替）`;
  return `${start} 起 · ${new Date(to).toLocaleString('zh-CN')} 止`;
}

/** 适用范围的展示串（平台 + 站点 + 店铺，NULL 写成人话）。 */
export function scopeText(scope: ComplianceRuleScope): string {
  const site = scope.marketplaceCode ? `站点 ${scope.marketplaceCode}` : '全部站点';
  const shop = scope.shopCode ? `店铺 ${scope.shopCode}` : '全部店铺';
  return `${scope.platformCode} · ${site} · ${shop}`;
}

// ==================================================================
// 空态（三态）
// ==================================================================

/** 空态的种类。 */
export type EmptyKind = 'NO_SHOP' | 'NO_DATA' | 'NO_MATCH' | 'NO_VERSION';

/**
 * 🔴 空态四态：**没有店铺** / **还没有规则** / **筛选无结果** / **规则还没有生效版本**。
 *
 * <p>把它们混成一句「暂无数据」是最省事也最没用的做法：运营看到「暂无数据」
 * 不知道该去建规则、该去发一版、还是该换个筛选条件。
 */
export function emptyStateKind(
  shopTotal: number,
  ruleTotal: number,
  filtered: boolean,
  versionTotal = 0,
): EmptyKind {
  if (shopTotal === 0) return 'NO_SHOP';
  if (ruleTotal === 0 && !filtered) return 'NO_DATA';
  if (versionTotal === 0) return 'NO_VERSION';
  return 'NO_MATCH';
}

/** 空态的标题与说明。 */
export function emptyState(kind: EmptyKind): { title: string; description: string } {
  switch (kind) {
    case 'NO_SHOP':
      return {
        title: '还没有店铺',
        description: '规则适用于具体的平台与站点：先在 02 渠道与店铺里登记店铺，再回来配适用范围。',
      };
    case 'NO_DATA':
      return {
        title: '还没有任何规则',
        description:
          '本模块的输入只有「人工维护」这一条真实路径：先建一条规则（编码 + 类型），再发第一个版本。',
      };
    case 'NO_VERSION':
      return {
        title: '这条规则还没有版本',
        description:
          '规则身份只是「哪一条规则」；严重度、词条、适用范围与生效时间都在**版本**上。发第一个版本。',
      };
    default:
      return {
        title: '筛选条件下没有规则',
        description: '当前筛选（类型 / 状态 / 平台 / 关键词）没有命中任何一条规则，换个条件再试。',
      };
  }
}
