import type {
  InternalAttributeOption,
  MappedInternalCategory,
  PlatformAttributeMappingInput,
  PlatformAttributeMappingRow,
  PlatformCategoryMappingDetail,
  PlatformCategoryMappingInput,
  PlatformCategoryMappingOverview,
  PlatformCategoryMappingPage,
  PlatformCategoryMappingRow,
  PlatformCategoryResolveResult,
  PlatformMappingSummary,
  PlatformRequiredAttribute,
  PlatformSiteCoverage,
  PlatformSiteOption,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 4.2.3.1 平台类目映射 · 模型层。
 *
 * <p>三块内容：契约类型、纯规则、接口调用。
 *
 * <p>纯规则与后端 {@code PlatformCategoryMappingRules} **逐条同口径**：
 * 前端拦下的输入服务端也会拦，反过来也一样，否则界面会说「不行」而接口说「行」，
 * 或者更糟——界面放行、刊登提交才被平台驳回。两侧的用例是一批的，
 * 改一边必须同时改 {@code platform-mapping-model.test.ts} 与
 * {@code PlatformCategoryMappingRulesTest.java}。
 */

// ------------------------------------------------------------ 本地类型

/**
 * 台账筛选条件。本地定义：`@RequestParam` 不进 OpenAPI components，
 * 字段与后端 {@code PlatformCategoryMappingController#page} 逐字对应。
 */
export interface PlatformMappingQuery {
  internalCategoryId: string | null;
  platformCode: string;
  siteCode: string;
  keyword: string;
  /** 三态：null=不限，true=只看启用，false=只看停用。 */
  enabled: boolean | null;
  unmappedOnly: boolean;
  sort: PlatformMappingSort;
  page: number;
  size: number;
}

/** 排序取值与后端 `PlatformCategoryMappingService#orderBy` 的白名单一一对应。 */
export const PLATFORM_MAPPING_SORTS = [
  'updatedAt,desc',
  'updatedAt,asc',
  'createdAt,desc',
  'createdAt,asc',
  'platform,asc',
  'internalCategory,asc',
] as const;

export type PlatformMappingSort = (typeof PLATFORM_MAPPING_SORTS)[number];

export const PLATFORM_MAPPING_SORT_LABELS: Record<PlatformMappingSort, string> = {
  'updatedAt,desc': '最近改动在前',
  'updatedAt,asc': '最早改动在前',
  'createdAt,desc': '最近创建在前',
  'createdAt,asc': '最早创建在前',
  'platform,asc': '按平台与站点',
  'internalCategory,asc': '按内部分类',
};

/** 映射表单草稿。`id` 为空即新建。 */
export interface MappingDraft {
  id: string | null;
  internalCategoryId: string;
  platformCode: string;
  siteCode: string;
  platformCategoryId: string;
  isDefault: boolean;
  enabled: boolean;
  version: number;
}

/** 平台属性规则表单草稿。`id` 为空即新建。 */
export interface AttributeRuleDraft {
  id: string | null;
  platformAttributeCode: string;
  platformAttributeName: string;
  internalAttributeId: string;
  requirement: string;
  platformDataType: string;
  multiple: boolean;
  /** 编辑框里是一行一个候选值，落库前拆成数组。 */
  allowedValuesText: string;
  transform: string;
  enabled: boolean;
  position: number;
  version: number;
}

// ------------------------------------------------------------ 词表

/** 平台码长度上限，与 `platform_category_mapping.platform_code varchar(32)` 一致。 */
export const MAX_PLATFORM_LENGTH = 32;

/** 站点码长度上限，与 `platform_category_mapping.site_code varchar(32)` 一致。 */
export const MAX_SITE_LENGTH = 32;

/** 平台属性码长度上限，与 `platform_attribute_code varchar(64)` 一致。 */
export const MAX_PLATFORM_ATTRIBUTE_CODE_LENGTH = 64;

/** 平台属性显示名长度上限，与 `platform_attribute_name varchar(200)` 一致。 */
export const MAX_PLATFORM_ATTRIBUTE_NAME_LENGTH = 200;

/** 单条属性规则的候选值上限：平台枚举实际到几十条，几百条基本是误粘贴。 */
export const MAX_ALLOWED_VALUES = 200;

/** 单个候选值长度上限，与后端 `MAX_ALLOWED_VALUE_LENGTH` 一致。 */
export const MAX_ALLOWED_VALUE_LENGTH = 100;

/** 要求强度词表。`REQUIRED` 即蓝图点名的「平台类目必填」。 */
export const REQUIREMENTS = ['REQUIRED', 'RECOMMENDED', 'OPTIONAL'] as const;

/** 平台侧数据类型词表。与内部属性的 data_type 常常不同，所以是独立一列。 */
export const PLATFORM_DATA_TYPES = ['TEXT', 'ENUM', 'NUMBER', 'BOOLEAN', 'DATE'] as const;

/**
 * 值变换算子。`DIRECT`=原样，其余由 4.2.1.1 执行。
 *
 * <p>本模块只存算子名、不存实现：一个算子的两份实现迟早会对不上。
 */
export const TRANSFORMS = [
  'DIRECT',
  'TRIM',
  'UPPER',
  'LOWER',
  'NUMBER_PARSE',
  'BOOLEAN_PARSE',
  'DATE_PARSE',
] as const;

export const REQUIREMENT_LABELS: Record<string, string> = {
  REQUIRED: '必填',
  RECOMMENDED: '推荐',
  OPTIONAL: '可选',
};

export const PLATFORM_DATA_TYPE_LABELS: Record<string, string> = {
  TEXT: '文本',
  ENUM: '枚举',
  NUMBER: '数值',
  BOOLEAN: '布尔',
  DATE: '日期',
};

export const TRANSFORM_LABELS: Record<string, string> = {
  DIRECT: '原样',
  TRIM: '去首尾空白',
  UPPER: '转大写',
  LOWER: '转小写',
  NUMBER_PARSE: '数值解析',
  BOOLEAN_PARSE: '布尔解析',
  DATE_PARSE: '日期解析',
};

// ------------------------------------------------------------ 纯规则（与后端同口径）

/**
 * 平台码规范化：去空白 + 大写。
 *
 * <p>大写是刻意的：平台码要与 channel 的 `platform.code` 以及
 * `product_category_tree.platform_code` 对齐，那两处都是大写。
 * `amazon` 与 `AMAZON` 各存一份的话，覆盖率会按平台算成两个平台，而它们其实是同一个。
 */
export function normalizePlatform(value: string): string {
  return normalizeCode(value);
}

/** 站点码规范化：去空白 + 大写。与平台码同一理由（Amazon 的 `us` / `US` 是同一个站点）。 */
export function normalizeSite(value: string): string {
  return normalizeCode(value);
}

function normalizeCode(value: string): string {
  return (value ?? '').trim().toUpperCase();
}

/** 平台码校验。字符集与 DB 的 CHECK 完全一致。 */
export function platformProblem(value: string): string | null {
  const code = normalizePlatform(value);
  if (code.length === 0) return '平台编码不能为空（如 AMAZON / SHOPIFY / TIKTOK）';
  if (code.length > MAX_PLATFORM_LENGTH) return `平台编码最多 ${MAX_PLATFORM_LENGTH} 个字符`;
  if (!/^[A-Z0-9][A-Z0-9_-]{0,31}$/.test(code))
    return '平台编码请使用英文字母、数字、下划线或短横线，且以字母或数字开头（如 AMAZON）';
  return null;
}

/** 站点码校验。与平台码分开给消息，否则运营不知道自己填的是哪一栏错了。 */
export function siteProblem(value: string): string | null {
  const code = normalizeSite(value);
  if (code.length === 0) return '站点编码不能为空（如 US / DE / UK）';
  if (code.length > MAX_SITE_LENGTH) return `站点编码最多 ${MAX_SITE_LENGTH} 个字符`;
  if (!/^[A-Z0-9][A-Z0-9_-]{0,31}$/.test(code))
    return '站点编码请使用英文字母、数字、下划线或短横线，且以字母或数字开头（如 US）';
  return null;
}

export function requirementProblem(value: string): string | null {
  const key = (value ?? '').trim();
  if (key.length === 0) return '要求强度不能为空';
  if (!(REQUIREMENTS as readonly string[]).includes(key))
    return '要求强度只能是 REQUIRED（平台必填）、RECOMMENDED（平台推荐）或 OPTIONAL（可选）';
  return null;
}

export function platformDataTypeProblem(value: string): string | null {
  const key = (value ?? '').trim();
  if (key.length === 0) return '平台数据类型不能为空';
  if (!(PLATFORM_DATA_TYPES as readonly string[]).includes(key))
    return '平台数据类型只能是 TEXT / ENUM / NUMBER / BOOLEAN / DATE';
  return null;
}

export function transformProblem(value: string): string | null {
  if (!value || value.trim().length === 0) return null; // 未给即 DIRECT
  if (!(TRANSFORMS as readonly string[]).includes(normalizeTransform(value)))
    return `值变换只能是 ${[...TRANSFORMS].sort().join(' / ')}`;
  return null;
}

/** 规范化后的值变换算子名。空值归一到 DIRECT，避免到处判空。 */
export function normalizeTransform(value: string | null | undefined): string {
  if (!value || value.trim().length === 0) return 'DIRECT';
  return value.trim().toUpperCase();
}

/**
 * 平台属性码规范化。
 *
 * <p>与平台码不同，这里**保留原始大小写**：平台属性码要原样进请求体
 * （Amazon 的 `bullet_point_1` 与 `BULLET_POINT_1` 是两个字段），顺手大写会让刊登被驳回。
 */
export function normalizePlatformAttributeCode(value: string): string {
  return (value ?? '').trim();
}

export function platformAttributeCodeProblem(value: string): string | null {
  const code = normalizePlatformAttributeCode(value);
  if (code.length === 0) return '平台属性编码不能为空（如 material / bullet_point_1）';
  if (code.length > MAX_PLATFORM_ATTRIBUTE_CODE_LENGTH)
    return `平台属性编码最多 ${MAX_PLATFORM_ATTRIBUTE_CODE_LENGTH} 个字符`;
  if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(code))
    return '平台属性编码请使用英文字母、数字、下划线、点号或短横线，且以字母或数字开头';
  return null;
}

export function normalizePlatformAttributeName(value: string): string {
  return (value ?? '').trim();
}

export function platformAttributeNameProblem(value: string): string | null {
  const name = normalizePlatformAttributeName(value);
  if (name.length === 0) return '平台属性显示名不能为空（如「材质」）';
  if (name.length > MAX_PLATFORM_ATTRIBUTE_NAME_LENGTH)
    return `平台属性显示名最多 ${MAX_PLATFORM_ATTRIBUTE_NAME_LENGTH} 个字符`;
  return null;
}

/**
 * 候选值规范化：去首尾空白 + 去重（保持出现顺序）。
 *
 * <p>去重而不是报错：平台后台导出的枚举里重复值很常见，而重复候选会让下拉出现两项同名，
 * 运营选了其中一项、刊登时平台按值匹配到另一项——症状是「明明选对了却总被驳回」。
 * 空白项直接丢弃。
 */
export function normalizeAllowedValues(values: readonly string[] | null | undefined): string[] {
  if (!values || values.length === 0) return [];
  const seen: string[] = [];
  for (const value of values) {
    const normalized = (value ?? '').trim();
    if (normalized.length === 0 || seen.includes(normalized)) continue;
    seen.push(normalized);
  }
  return seen;
}

/** 把编辑框里的一行一个候选值拆成数组。 */
export function parseAllowedValues(text: string): string[] {
  return normalizeAllowedValues((text ?? '').split('\n'));
}

/** 把候选值数组拼回编辑框文本（按行）。 */
export function formatAllowedValues(values: readonly string[] | null | undefined): string {
  return normalizeAllowedValues(values).join('\n');
}

/**
 * 单条属性规则的完整校验。
 *
 * <p>逐条给可读原因而不是抛一个笼统的「规则不合法」：这条规则一次要填七项，
 * 一次只告诉一项的话运营得反复提交七轮。顺序与后端一致：
 * 编码 → 名称 → 强度 → 类型 → 变换 → 候选值 → 排序位。
 */
export function attributeProblem(draft: AttributeRuleDraft): string | null {
  let problem = platformAttributeCodeProblem(draft.platformAttributeCode);
  if (problem) return problem;
  problem = platformAttributeNameProblem(draft.platformAttributeName);
  if (problem) return problem;
  problem = requirementProblem(draft.requirement);
  if (problem) return problem;
  problem = platformDataTypeProblem(draft.platformDataType);
  if (problem) return problem;
  problem = transformProblem(draft.transform);
  if (problem) return problem;
  const values = parseAllowedValues(draft.allowedValuesText);
  if (values.length > MAX_ALLOWED_VALUES) return `候选值最多 ${MAX_ALLOWED_VALUES} 条`;
  const tooLong = values.find((v) => v.length > MAX_ALLOWED_VALUE_LENGTH);
  if (tooLong !== undefined)
    return `单个候选值最多 ${MAX_ALLOWED_VALUE_LENGTH} 个字符：「${tooLong}」超长`;
  // 与后端 CHECK 同口径：枚举没有候选值时，刊登侧既渲染不出下拉也校验不了值，
  // 只能原样发过去等平台驳回。
  if (draft.platformDataType.trim() === 'ENUM' && values.length === 0)
    return '平台数据类型是 ENUM 时必须提供候选值，否则刊登时无法渲染下拉、也无法校验取值';
  if (draft.position < 0) return '排序位不能为负数';
  return null;
}

// ------------------------------------------------------------ 展示辅助

export function requirementLabel(requirement: string): string {
  return REQUIREMENT_LABELS[requirement] ?? requirement;
}

export function platformDataTypeLabel(dataType: string): string {
  return PLATFORM_DATA_TYPE_LABELS[dataType] ?? dataType;
}

export function transformLabel(transform: string): string {
  return TRANSFORM_LABELS[transform] ?? transform;
}

export function mappingEmptyHint(hasFilters: boolean): string {
  return hasFilters
    ? '没有符合筛选条件的映射。'
    : '还没有任何平台类目映射。选一个内部分类、一个平台与站点，再选对应的平台类目，就能开始刊登。';
}

export function attributeEmptyHint(hasMapping: boolean): string {
  return hasMapping
    ? '这条映射还没有平台属性规则。平台的「材质」对应内部哪个属性，就在这里一条一条配。'
    : '先在上方选一条映射，才能维护它的平台属性规则。';
}

/** 覆盖率百分比。分母为 0 时返回 0，避免出现 NaN%。 */
export function coveragePercent(mapped: number, total: number): number {
  if (total <= 0) return 0;
  return Math.round((mapped / total) * 100);
}

export function errorText(error: unknown, fallback = '操作失败'): string {
  if (error instanceof ApiError && error.message) return error.message;
  return error instanceof Error && error.message ? error.message : fallback;
}

/** 映射行 → 编辑草稿。 */
export function toMappingDraft(row: PlatformCategoryMappingRow): MappingDraft {
  return {
    id: row.id,
    internalCategoryId: row.internalCategoryId,
    platformCode: row.platformCode,
    siteCode: row.siteCode,
    platformCategoryId: row.platformCategoryId,
    isDefault: row.isDefault,
    enabled: row.enabled,
    version: row.version,
  };
}

export function emptyMappingDraft(platformCode = '', siteCode = ''): MappingDraft {
  return {
    id: null,
    internalCategoryId: '',
    platformCode,
    siteCode,
    platformCategoryId: '',
    isDefault: true,
    enabled: true,
    version: 0,
  };
}

/** 属性规则行 → 编辑草稿。 */
export function toAttributeDraft(row: PlatformAttributeMappingRow): AttributeRuleDraft {
  return {
    id: row.id,
    platformAttributeCode: row.platformAttributeCode,
    platformAttributeName: row.platformAttributeName,
    internalAttributeId: row.internalAttributeId ?? '',
    requirement: row.requirement,
    platformDataType: row.platformDataType,
    multiple: row.multiple,
    allowedValuesText: formatAllowedValues(row.allowedValues),
    transform: row.transform,
    enabled: row.enabled,
    position: row.position,
    version: row.version,
  };
}

export function emptyAttributeDraft(position = 0): AttributeRuleDraft {
  return {
    id: null,
    platformAttributeCode: '',
    platformAttributeName: '',
    internalAttributeId: '',
    requirement: 'REQUIRED',
    platformDataType: 'TEXT',
    multiple: false,
    allowedValuesText: '',
    transform: 'DIRECT',
    enabled: true,
    position,
    version: 0,
  };
}

/** 草稿 → 契约输入形状。 */
export function toMappingInput(draft: MappingDraft): PlatformCategoryMappingInput {
  return {
    internalCategoryId: draft.internalCategoryId,
    platformCode: normalizePlatform(draft.platformCode),
    siteCode: normalizeSite(draft.siteCode),
    platformCategoryId: draft.platformCategoryId,
    isDefault: draft.isDefault,
    enabled: draft.enabled,
    version: draft.version,
  };
}

export function toAttributeInput(draft: AttributeRuleDraft): PlatformAttributeMappingInput {
  return {
    platformAttributeCode: normalizePlatformAttributeCode(draft.platformAttributeCode),
    platformAttributeName: normalizePlatformAttributeName(draft.platformAttributeName),
    internalAttributeId: draft.internalAttributeId.trim().length === 0 ? null : draft.internalAttributeId.trim(),
    requirement: draft.requirement.trim(),
    platformDataType: draft.platformDataType.trim(),
    multiple: draft.multiple,
    allowedValues: parseAllowedValues(draft.allowedValuesText),
    transform: normalizeTransform(draft.transform),
    enabled: draft.enabled,
    position: draft.position,
    version: draft.version,
  };
}

/** 映射表单的整体校验：先类目必填，再逐字校验平台与站点。 */
export function mappingProblem(draft: MappingDraft): string | null {
  if (!draft.internalCategoryId.trim()) return '请选择内部分类（左侧）';
  if (!draft.platformCategoryId.trim()) return '请选择平台类目（右侧）';
  if (draft.internalCategoryId.trim() === draft.platformCategoryId.trim())
    return '内部分类与平台类目不能是同一个';
  return platformProblem(draft.platformCode) ?? siteProblem(draft.siteCode);
}

// ------------------------------------------------------------ 接口

export function fetchMappingOverview() {
  return api<PlatformCategoryMappingOverview>('catalog/category-mappings/overview');
}

/**
 * 平台与站点候选。
 *
 * <p>平台来自租户**已建的 PLATFORM 分类树**、站点来自该平台上**已在用的**映射，都是真实数据。
 * catalog 不跨服务读 channel 的平台码表（AGENTS.md 禁止跨服务访问），
 * 所以这里允许录入新站点码并由前后端做同一套形态校验，而不是给一份硬编码常量清单。
 */
export function fetchPlatformSites() {
  return api<PlatformSiteOption[]>('catalog/category-mappings/platform-sites');
}

/** 内部属性字典候选：供「平台的材质对应内部哪个属性」下拉。只给租户级字典条目。 */
export function fetchAttributeOptions(q: string) {
  const params = new URLSearchParams();
  if (q.trim()) params.set('q', q.trim());
  const suffix = params.toString();
  return api<InternalAttributeOption[]>(
    `catalog/category-mappings/attribute-options${suffix ? `?${suffix}` : ''}`,
  );
}

/** 台账（真分页：筛选、排序、LIMIT/OFFSET 全在 SQL 上）。 */
export function fetchMappings(query: PlatformMappingQuery) {
  const params = new URLSearchParams();
  if (query.internalCategoryId) params.set('internalCategoryId', query.internalCategoryId);
  if (query.platformCode) params.set('platformCode', normalizePlatform(query.platformCode));
  if (query.siteCode) params.set('siteCode', normalizeSite(query.siteCode));
  if (query.keyword.trim()) params.set('q', query.keyword.trim());
  if (query.enabled !== null) params.set('enabled', String(query.enabled));
  if (query.unmappedOnly) params.set('unmappedOnly', 'true');
  params.set('sort', query.sort);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return api<PlatformCategoryMappingPage>(`catalog/category-mappings?${params.toString()}`);
}

export function fetchMappingDetail(id: string) {
  return api<PlatformCategoryMappingDetail>(`catalog/category-mappings/${id}`);
}

/** 映射详情里的三块内容单独取回，便于局部刷新（改完属性规则不必重取整页详情）。 */
export async function fetchMappingParts(id: string): Promise<{
  attributes: PlatformAttributeMappingRow[];
  alsoMappedFrom: MappedInternalCategory[];
  summary: PlatformMappingSummary;
}> {
  const detail = await api<PlatformCategoryMappingDetail>(`catalog/category-mappings/${id}`);
  return {
    attributes: detail.attributes,
    alsoMappedFrom: detail.alsoMappedFrom,
    summary: detail.summary,
  };
}

export function saveMapping(id: string | null, input: PlatformCategoryMappingInput) {
  return id
    ? api<PlatformCategoryMappingRow>(`catalog/category-mappings/${id}`, 'PUT', input)
    : api<PlatformCategoryMappingRow>('catalog/category-mappings', 'POST', input);
}

/** 设为该「内部分类 × 平台 × 站点」的默认目标；同一组合下只允许一个默认。 */
export function setMappingDefault(id: string, version: number) {
  return api<{ isDefault: boolean }>(
    `catalog/category-mappings/${id}/default?version=${version}`,
    'PUT',
  );
}

export function setMappingEnabled(id: string, enabled: boolean, version: number) {
  return api<{ enabled: boolean }>(
    `catalog/category-mappings/${id}/enabled?enabled=${enabled}&version=${version}`,
    'PUT',
  );
}

export function deleteMapping(id: string, version: number) {
  return api<{ deleted: boolean }>(`catalog/category-mappings/${id}?version=${version}`, 'DELETE');
}

export function fetchMappingAttributes(mappingId: string) {
  return api<PlatformAttributeMappingRow[]>(`catalog/category-mappings/${mappingId}/attributes`);
}

export function saveAttributeRule(
  mappingId: string,
  ruleId: string | null,
  input: PlatformAttributeMappingInput,
) {
  return ruleId
    ? api<PlatformAttributeMappingRow>(
        `catalog/category-mappings/${mappingId}/attributes/${ruleId}`,
        'PUT',
        input,
      )
    : api<PlatformAttributeMappingRow>(
        `catalog/category-mappings/${mappingId}/attributes`,
        'POST',
        input,
      );
}

export function setAttributeEnabled(
  mappingId: string,
  ruleId: string,
  enabled: boolean,
  version: number,
) {
  return api<{ enabled: boolean }>(
    `catalog/category-mappings/${mappingId}/attributes/${ruleId}/enabled?enabled=${enabled}&version=${version}`,
    'PUT',
  );
}

export function deleteAttributeRule(mappingId: string, ruleId: string) {
  return api<{ deleted: boolean }>(
    `catalog/category-mappings/${mappingId}/attributes/${ruleId}`,
    'DELETE',
  );
}

/**
 * 按「内部分类 × 平台 × 站点」解析刊登目标与必填属性清单（4.2.1.1 表单生成的输入）。
 *
 * <p>同组合有多条启用映射却没有默认时后端会明确报「需要指定」，
 * 不静默挑第一条——否则同一个商品两次刊登可能落到不同平台类目。
 */
export function resolveTarget(internalCategoryId: string, platformCode: string, siteCode: string) {
  const params = new URLSearchParams({
    internalCategoryId,
    platformCode: normalizePlatform(platformCode),
    siteCode: normalizeSite(siteCode),
  });
  return api<PlatformCategoryResolveResult>(`catalog/category-mappings/resolve?${params.toString()}`);
}

export type {
  InternalAttributeOption,
  MappedInternalCategory,
  PlatformAttributeMappingInput,
  PlatformAttributeMappingRow,
  PlatformCategoryMappingDetail,
  PlatformCategoryMappingInput,
  PlatformCategoryMappingOverview,
  PlatformCategoryMappingPage,
  PlatformCategoryMappingRow,
  PlatformCategoryResolveResult,
  PlatformMappingSummary,
  PlatformRequiredAttribute,
  PlatformSiteCoverage,
  PlatformSiteOption,
};
