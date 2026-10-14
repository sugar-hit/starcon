import type {
  BrandAuthorization,
  BrandAuthorizationInput,
  BrandCategoryLink,
  BrandDetail,
  BrandEligibilityBatchInput,
  BrandInput,
  BrandOverview,
  BrandPage,
  BrandPublishEligibility,
  BrandRow,
  BrandSaved,
  CategoryNode,
  CategoryTree,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 3.3.2.1 品牌管理前端模型。
 *
 * <p>这个页面上最容易出错的三处：
 *
 * <ul>
 *   <li>**刊登判定不能在前端重算**。`publishable` / `blockCode` 一律来自后端
 *       （`BrandRules.publishBlock` 是唯一实现）。前端只做一件事：把 `blockCode` 翻成
 *       要显示的话。复制一份判定到浏览器里的症状是「页面说能刊登、接口说不能」，
 *       而这种事只有真要发的那天才会暴露。
 *   <li>**状态码与中文标签要一起看**。四条原因码（停用 / 缺凭证 / 未生效 / 已过期）
 *       对应四种处理动作，界面上不能都写成「不能刊登」——运营看完不知道该做什么。
 *   <li>**撤凭证不是删凭证**。凭证停用后行还在（16 侵权检测要回溯「曾经有过、后来撤销」），
 *       所以界面上给的是「撤销 / 恢复」，不给删除。
 * </ul>
 */

/** 授权状态词表：与 V16 的 CHECK 和 `BrandRules.AUTHORIZATION_STATUSES` 同一口径。 */
export const AUTHORIZATION_STATUSES = [
  { value: 'OWNED', label: '自有', hint: '本公司的品牌，无需凭证即可刊登' },
  { value: 'AGENCY', label: '代理', hint: '已有代理协议，按协议范围刊登' },
  { value: 'LICENSED', label: '需授权', hint: '必须有至少一条在有效期内的授权凭证才能刊登' },
] as const;

export type AuthorizationStatus = (typeof AUTHORIZATION_STATUSES)[number]['value'];

export function statusLabel(status: string | null | undefined): string {
  return AUTHORIZATION_STATUSES.find((item) => item.value === status)?.label ?? status ?? '未知';
}

export function statusHint(status: string | null | undefined): string {
  return AUTHORIZATION_STATUSES.find((item) => item.value === status)?.hint ?? '';
}

/** 阻止刊登的原因码 → 中文标签。与 `BrandRules.blockLabel` 逐字同口径。 */
export const BLOCK_LABELS: Record<string, string> = {
  BRAND_DISABLED: '品牌停用',
  LICENSE_MISSING: '缺少凭证',
  LICENSE_NOT_YET_EFFECTIVE: '凭证未生效',
  LICENSE_EXPIRED: '凭证已过期',
};

/** 阻止刊登的原因码 → 运营该做什么。四种原因的处理动作不同，所以文案也不同。 */
export const BLOCK_ACTIONS: Record<string, string> = {
  BRAND_DISABLED: '启用品牌后才能刊登',
  LICENSE_MISSING: '登记一条授权凭证',
  LICENSE_NOT_YET_EFFECTIVE: '等凭证到生效日，或换一条已生效的凭证',
  LICENSE_EXPIRED: '续约后再刊登，或改用自有 / 代理品牌',
};

export function blockLabel(code: string | null | undefined): string {
  if (!code) return '可刊登';
  return BLOCK_LABELS[code] ?? '不可刊登';
}

export function blockAction(code: string | null | undefined): string | null {
  if (!code) return null;
  return BLOCK_ACTIONS[code] ?? '联系品牌负责人确认';
}

/** 列表页筛选项：刊登可行性三态。 */
export const PUBLISHABLE_FILTERS = [
  { value: '', label: '刊登可行性：全部' },
  { value: 'true', label: '只看可刊登' },
  { value: 'false', label: '只看不可刊登' },
] as const;

export type PublishableFilter = '' | 'true' | 'false';

// ------------------------------------------------------------ 接口

export type BrandListQuery = {
  keyword?: string;
  authorizationStatus?: string;
  enabled: boolean | null;
  publishable: boolean | null;
  categoryId: string | null;
  page: number;
  size: number;
};

export function fetchOverview() {
  return api<BrandOverview>('catalog/brands/overview');
}

export function fetchBrands(query: BrandListQuery) {
  const params = new URLSearchParams();
  if (query.keyword?.trim()) params.set('keyword', query.keyword.trim());
  if (query.authorizationStatus) params.set('authorizationStatus', query.authorizationStatus);
  if (query.enabled !== null) params.set('enabled', String(query.enabled));
  if (query.publishable !== null) params.set('publishable', String(query.publishable));
  if (query.categoryId) params.set('categoryId', query.categoryId);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return api<BrandPage>(`catalog/brands?${params.toString()}`);
}

export function fetchDetail(brandId: string) {
  return api<BrandDetail>(`catalog/brands/${brandId}`);
}

export function createBrand(input: BrandInput) {
  return api<BrandSaved>('catalog/brands', 'POST', input);
}

export function saveBrand(brandId: string, input: BrandInput) {
  return api<BrandSaved>(`catalog/brands/${brandId}`, 'PUT', input);
}

export function setBrandEnabled(brandId: string, enabled: boolean) {
  return api<{ enabled: boolean }>(`catalog/brands/${brandId}/enabled?enabled=${enabled}`, 'PUT');
}

export function deleteBrand(brandId: string, version: number) {
  return api<{ deleted: boolean }>(`catalog/brands/${brandId}?version=${version}`, 'DELETE');
}

export function fetchAuthorizations(brandId: string) {
  return api<BrandAuthorization[]>(`catalog/brands/${brandId}/authorizations`);
}

export function createAuthorization(brandId: string, input: BrandAuthorizationInput) {
  return api<BrandSaved>(`catalog/brands/${brandId}/authorizations`, 'POST', input);
}

export function saveAuthorization(brandId: string, id: string, input: BrandAuthorizationInput) {
  return api<BrandSaved>(`catalog/brands/${brandId}/authorizations/${id}`, 'PUT', input);
}

/**
 * 撤销 / 恢复一条凭证。
 *
 * <p>刻意没有删除凭证的调用：撤销后行还在，16 侵权检测要回溯「曾经有过、后来被撤销」。
 */
export function revokeAuthorization(brandId: string, id: string, enabled: boolean) {
  return api<BrandSaved>(
    `catalog/brands/${brandId}/authorizations/${id}/enabled?enabled=${enabled}`,
    'PUT',
  );
}

/** 单个品牌的刊登判定（04 刊登与 16 侵权检测消费的那一个端点）。 */
export function fetchEligibility(brandId: string) {
  return api<BrandPublishEligibility>(`catalog/brands/${brandId}/listing-eligibility`);
}

/** 批量刊登判定：一次提交多个品牌，后端按品牌去重后一起判。 */
export function fetchEligibilityBatch(brandIds: string[]) {
  const input: BrandEligibilityBatchInput = { brandIds };
  return api<BrandPublishEligibility[]>('catalog/brands/listing-eligibility', 'POST', input);
}

/** 刊登 / 合规的品牌引用候选。includeBlocked=true 时连不可刊登的也返回（带原因码）。 */
export function fetchCandidates(
  categoryId: string | null,
  keyword: string,
  includeBlocked: boolean,
) {
  const params = new URLSearchParams();
  if (categoryId) params.set('categoryId', categoryId);
  if (keyword.trim()) params.set('keyword', keyword.trim());
  if (includeBlocked) params.set('includeBlocked', 'true');
  const query = params.toString();
  return api<BrandPublishEligibility[]>(`catalog/brands/candidates${query ? `?${query}` : ''}`);
}

/** 分类树（关联类目的下拉数据源，与 3.3.1.1 同一批端点）。 */
export function fetchTrees() {
  return api<CategoryTree[]>('catalog/categories/trees');
}

export function fetchNodes(treeId: string) {
  return api<CategoryNode[]>(`catalog/categories/trees/${treeId}/nodes`);
}

// ------------------------------------------------------------ 校验（与服务层同口径）

/** 品牌编码校验。与 `BrandRules.codeProblem` 同一套规则。 */
export function codeProblem(value: string): string | null {
  const code = value.trim().toUpperCase();
  if (!code) return '品牌编码不能为空';
  if (code.length > 64) return '品牌编码最多 64 个字符';
  if (!/^[A-Z0-9][A-Z0-9_-]{0,63}$/.test(code))
    return '品牌编码请使用英文字母、数字、下划线或短横线，长度 1~64 位';
  return null;
}

export function nameProblem(value: string): string | null {
  const name = value.trim();
  if (!name) return '品牌名称不能为空';
  if (name.length > 100) return '品牌名称最多 100 个字符';
  return null;
}

/** Logo 引用校验：填了就不能含空格（未编码的空格不是合法 URI）。 */
export function logoProblem(value: string | null | undefined): string | null {
  const logo = (value ?? '').trim();
  if (logo.length > 500) return 'Logo 引用最多 500 个字符';
  if (logo.includes(' ')) return 'Logo 引用不能包含空格，请填对象存储 key 或完整 URL';
  return null;
}

/** 多语言名校验：逐条 + 同语言重复（规范化之后比较）。 */
export function namesProblem(names: ({ locale: string; name: string } | null)[]): string | null {
  if (names.length === 0) return null;
  if (names.length > 20) return '单个品牌最多配置 20 个语言名';
  const seen = new Set<string>();
  for (const entry of names) {
    // 契约里数组元素带 `| null`（Jackson 允许显式 null），空项直接当「删掉这条」处理。
    if (entry === null) continue;
    const locale = entry.locale.trim().toLowerCase();
    const value = entry.name.trim();
    if (!locale) return '多语言名的语言标识不能为空（如 zh-CN / en-US）';
    if (locale.length > 16) return '语言标识最多 16 个字符';
    if (!/^[a-z]{2,3}(-[a-z0-9]{2,8})?$/.test(locale))
      return '语言标识请使用 BCP 47 形式，如 zh-CN / en-US / ja';
    if (!value) return '多语言名的内容不能为空';
    if (value.length > 200) return '多语言名的内容最多 200 个字符';
    if (seen.has(locale)) return `同一个语言只能有一个品牌名：${locale}`;
    seen.add(locale);
  }
  return null;
}

/** 凭证校验。与 `BrandRules` 的 certificateNo / grantor / validity / evidence 同一口径。 */
export function credentialProblem(input: BrandAuthorizationInput): string | null {
  if (!input.certificateNo.trim()) return '凭证编号不能为空';
  if (input.certificateNo.trim().length > 64) return '凭证编号最多 64 个字符';
  if (!input.grantor.trim()) return '授权方不能为空';
  if (input.grantor.trim().length > 150) return '授权方最多 150 个字符';
  if (!input.validFrom.trim()) return '凭证生效日期不能为空';
  const from = input.validFrom.trim();
  const to = input.validTo?.trim() ?? '';
  if (!/^\d{4}-\d{2}-\d{2}$/.test(from)) return '生效日期格式需为 yyyy-MM-dd';
  if (to && !/^\d{4}-\d{2}-\d{2}$/.test(to)) return '失效日期格式需为 yyyy-MM-dd';
  if (to && to < from) return '凭证失效日期不能早于生效日期';
  if ((input.evidenceUri ?? '').length > 500) return '凭证材料引用最多 500 个字符';
  return null;
}

/** 整个品牌表单的校验：返回第一条可执行的原因，没有则 null。 */
export function brandInputProblem(
  input: Pick<BrandInput, 'code' | 'name' | 'logoUri' | 'authorizationStatus' | 'names'>,
): string | null {
  return (
    codeProblem(input.code) ??
    nameProblem(input.name) ??
    logoProblem(input.logoUri) ??
    namesProblem(input.names ?? []) ??
    (input.authorizationStatus ? null : '授权状态不能为空')
  );
}

/** 凭证状态的界面对应：三态（有效 / 已撤销 / 不在有效期内），不给「未知」。 */
export function credentialState(credential: BrandAuthorization): {
  label: string;
  tone: 'ok' | 'warn' | 'muted';
} {
  if (!credential.enabled) return { label: '已撤销', tone: 'muted' };
  if (credential.validOn) {
    return credential.daysToExpiry > 0 && credential.daysToExpiry <= 30
      ? { label: `有效（${credential.daysToExpiry} 天后到期）`, tone: 'warn' }
      : { label: '有效', tone: 'ok' };
  }
  return { label: '不在有效期内', tone: 'muted' };
}

/** 空表单初值。 */
export function emptyBrandInput(): BrandInput {
  return {
    code: '',
    name: '',
    logoUri: null,
    authorizationStatus: 'OWNED',
    enabled: true,
    names: [],
    categories: [],
    version: 0,
  };
}

/** 空凭证初值：生效日给今天（当天登记的凭证从今天起可用）。 */
export function emptyCredentialInput(): BrandAuthorizationInput {
  return {
    certificateNo: '',
    grantor: '',
    validFrom: new Date().toISOString().slice(0, 10),
    validTo: null,
    evidenceUri: null,
    note: null,
    enabled: true,
    version: 0,
  };
}

/** 详情 → 表单初值。 */
export function toBrandInput(detail: BrandDetail): BrandInput {
  return {
    code: detail.code,
    name: detail.name,
    logoUri: detail.logoUri,
    authorizationStatus: detail.authorizationStatus,
    enabled: detail.enabled,
    names: Object.entries(detail.names ?? {}).map(([locale, value]) => ({
      locale,
      name: String(value),
    })),
    categories: (detail.categories ?? [])
      .filter((link) => link !== null)
      .map((link) => ({ categoryId: link.categoryId, position: link.position })),
    version: detail.version,
  };
}

export function toCredentialInput(credential: BrandAuthorization): BrandAuthorizationInput {
  return {
    certificateNo: credential.certificateNo,
    grantor: credential.grantor,
    validFrom: credential.validFrom,
    validTo: credential.validTo,
    evidenceUri: credential.evidenceUri,
    note: credential.note,
    enabled: credential.enabled,
    version: credential.version,
  };
}

/** 类目候选：只给启用中的内部类目，带路径串便于界面上区分同名类目。 */
export function linkableCategories(nodes: CategoryNode[]): CategoryNode[] {
  return nodes.filter((node) => node.enabled && node.treeKind === 'INTERNAL');
}

/** 品牌名在给定语言下的显示名：有多语言名就用它，否则回退到基础名。 */
export function displayName(
  brand: { name: string; names?: Record<string, string> | null },
  locale: string,
): string {
  const names = brand.names ?? {};
  const exact = names[locale.toLowerCase()];
  if (exact) return exact;
  const base = locale.toLowerCase().split('-')[0];
  const loose = Object.entries(names).find(([key]) => key.split('-')[0] === base)?.[1];
  return loose ?? brand.name;
}

/** 空态与筛选态的提示文案：两种空态文案不同，别混。 */
export function emptyHint(filtered: boolean) {
  return filtered
    ? '当前筛选条件下没有品牌。品牌字典是租户级的，先建一条品牌才能在刊登与合规里引用。'
    : '这个租户还没有品牌。品牌是刊登的品牌字段与 16 侵权检测的引用源，先建一条。';
}

/** 错误文案：后端给的是可执行的中文原因，这里只补上上下文。 */
export function errorText(error: unknown, fallback = '品牌数据加载失败') {
  if (error instanceof ApiError && error.message) return error.message;
  return fallback;
}

export type { BrandCategoryLink, BrandDetail, BrandRow };
