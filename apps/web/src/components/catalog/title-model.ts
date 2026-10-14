import type {
  TitleGeneration,
  TitleGenerationDetail,
  TitleGenerationInput,
  TitleGenerationPage,
  TitleGenerationRow,
  TitleKeywordInput,
  TitleKeywordPage,
  TitleKeywordRow,
  TitleOverview,
  TitleRecord,
  TitleRecordPage,
  TitleRecordSelected,
  TitleTemplateInput,
  TitleTemplatePlatformInput,
  TitleTemplateRow,
  TitleTemplateSaved,
  TitleTemplateVariableInput,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 词根列表筛选条件。
 *
 * <p>本地定义而不是从契约里取：它只由 query string 承载，`@RequestParam`
 * 不会出现在 OpenAPI 的 components 里（那里只有 body/response 形状）。
 * 字段与后端 {@code TitleKeywordQuery} 逐字对应。
 */
export type TitleKeywordQuery = {
  keyword: string | null;
  keywordType: string | null;
  page: number;
  size: number;
};

/** 候选列表筛选条件。字段与后端 {@code TitleRecordQuery} 逐字对应。 */
export type TitleRecordQuery = {
  skuId: string | null;
  platform: string | null;
  status: string | null;
  keyword: string | null;
  generationId: string | null;
  page: number;
  size: number;
};

/**
 * 3.4.2.1 标题库前端模型。
 *
 * <p>这个页面最容易出错的三处：
 *
 * <ul>
 *   <li><b>重复度是在后端算法里控制的，前端不重算。</b>蓝图写死了
 *       「批量生成要保证"相似但不雷同"，需在生成算法中显式控制重复度」——
 *       所以前端**不**做「自己排一版词序凑数」，只把后端返回的
 *       {@code similarityPermille} 显示出来。那是库里可查的事实（每条候选落库时写死的）。
 *   <li><b>「没产出满额」不是 bug，是约束在起作用。</b>词池太小时算法会拒绝并给出原因，
 *       界面上要把原因显示出来，而不是显示成「生成失败」让用户以为是系统坏了。
 *   <li><b>生成是同步的「已完成」，不是异步「已受理」。</b>提交返回后候选已经在库里了，
 *       可以直接跳转去看。绝不能显示「正在生成」再轮询——那会让用户以为要等。
 * </ul>
 */

/** 词根类型词表：与 V20 的 CHECK 和 `TitleRules.KEYWORD_TYPES` 同一口径。 */
export const KEYWORD_TYPES = [
  { value: 'CORE', label: '核心词', hint: '标题主体：品类或用途。批量生成的「词根」默认取这一类' },
  { value: 'SELLING', label: '卖点', hint: '标题里的卖点短语（短词，长文本归 3.4.2.2 描述库）' },
  {
    value: 'MODIFIER',
    label: '修饰词',
    hint: '差异化主力：换平台标题靠它。池子太小就凑不出不雷同的候选',
  },
] as const;

/** 变量绑定源词表：事实字段从 SPU/SKU 取，词根类从上面三类池子取。 */
export const VARIABLE_SOURCES = [
  { value: 'SPU_BRAND', label: 'SPU 品牌', group: '商品事实' },
  { value: 'SPU_CATEGORY', label: 'SPU 分类', group: '商品事实' },
  { value: 'SPU_NAME', label: 'SPU 名称', group: '商品事实' },
  { value: 'SKU_NAME', label: 'SKU 名称', group: '商品事实' },
  { value: 'SKU_CODE', label: 'SKU 编码', group: '商品事实' },
  { value: 'SKU_SPEC', label: 'SKU 规格', group: '商品事实' },
  { value: 'KEYWORD_CORE', label: '核心词池', group: '标题词根' },
  { value: 'KEYWORD_SELLING', label: '卖点词池', group: '标题词根' },
  { value: 'KEYWORD_MODIFIER', label: '修饰词池', group: '标题词根' },
] as const;

export const GENERATION_STATUSES = [
  { value: 'SUCCEEDED', label: '已生成' },
  { value: 'PARTIAL', label: '部分 SKU 未产出' },
  { value: 'FAILED', label: '未产出' },
] as const;

export const RECORD_STATUSES = [
  { value: 'CANDIDATE', label: '待选用' },
  { value: 'SELECTED', label: '已选用' },
  { value: 'REJECTED', label: '已淘汰' },
] as const;

export function keywordTypeLabel(value: string | null | undefined): string {
  return KEYWORD_TYPES.find((t) => t.value === value)?.label ?? value ?? '未知';
}

export function sourceLabel(value: string | null | undefined): string {
  return VARIABLE_SOURCES.find((s) => s.value === value)?.label ?? value ?? '未知';
}

export function generationStatusLabel(value: string | null | undefined): string {
  return GENERATION_STATUSES.find((s) => s.value === value)?.label ?? value ?? '未知';
}

export function recordStatusLabel(value: string | null | undefined): string {
  return RECORD_STATUSES.find((s) => s.value === value)?.label ?? value ?? '未知';
}

/** Badge 色调，直接用 `@starcon/ui` 的 tone 词表，不自造一套。 */
export function generationStatusTone(
  status: string | null | undefined,
): 'neutral' | 'success' | 'danger' | 'warning' {
  if (status === 'SUCCEEDED') return 'success';
  if (status === 'PARTIAL') return 'warning';
  if (status === 'FAILED') return 'danger';
  return 'neutral';
}

export function recordStatusTone(
  status: string | null | undefined,
): 'neutral' | 'success' | 'danger' {
  if (status === 'SELECTED') return 'success';
  if (status === 'REJECTED') return 'danger';
  return 'neutral';
}

/**
 * 相似度的展示文案。
 *
 * <p>千分比转成百分比带一位小数。0‰ 也要如实显示「0.0%」而不是「-」：
 * 「与其它标题毫无共同词」是一个真实且值得看到的结果。
 */
export function similarityText(permille: number | null | undefined): string {
  if (permille === null || permille === undefined) return '未测量';
  return `${(permille / 10).toFixed(1)}%`;
}

/**
 * 相似度的紧迫度：离重复铺货判定越近越红。
 *
 * <p>阈值用 0.9 而不是 1.0：完全相同是 100%，而 90% 以上已经足够像到
 * 「换平台可能被判重复」了。分三档是为了让列表一眼能扫出问题行。
 */
export function similarityTone(
  permille: number | null | undefined,
): 'neutral' | 'warning' | 'danger' {
  if (permille === null || permille === undefined) return 'neutral';
  if (permille >= 900) return 'danger';
  if (permille >= 700) return 'warning';
  return 'neutral';
}

// ------------------------------------------------------------ 接口

export function fetchTitleOverview() {
  return api<TitleOverview>('catalog/titles/overview');
}

export function fetchTemplates() {
  return api<TitleTemplateRow[]>('catalog/titles/templates');
}

export function fetchTemplate(id: string) {
  return api<TitleTemplateRow>(`catalog/titles/templates/${id}`);
}

export function saveTemplate(id: string | null, input: TitleTemplateInput) {
  return id
    ? api<TitleTemplateSaved>(`catalog/titles/templates/${id}`, 'PUT', input)
    : api<TitleTemplateSaved>('catalog/titles/templates', 'POST', input);
}

export function setTemplateEnabled(id: string, enabled: boolean) {
  return api<TitleTemplateSaved>(
    `catalog/titles/templates/${id}/enabled?enabled=${enabled}`,
    'PUT',
  );
}

export function deleteTemplate(id: string, version: number) {
  return api<{ deleted: boolean }>(`catalog/titles/templates/${id}?version=${version}`, 'DELETE');
}

export function fetchKeywords(query: TitleKeywordQuery) {
  const params = new URLSearchParams();
  if (query.keyword?.trim()) params.set('keyword', query.keyword.trim());
  if (query.keywordType) params.set('keywordType', query.keywordType);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return api<TitleKeywordPage>(`catalog/titles/keywords?${params.toString()}`);
}

export function saveKeyword(id: string | null, input: TitleKeywordInput) {
  return id
    ? api<TitleKeywordRow>(`catalog/titles/keywords/${id}`, 'PUT', input)
    : api<TitleKeywordRow>('catalog/titles/keywords', 'POST', input);
}

export function setKeywordEnabled(id: string, enabled: boolean) {
  return api<TitleKeywordRow>(`catalog/titles/keywords/${id}/enabled?enabled=${enabled}`, 'PUT');
}

export function deleteKeyword(id: string, version: number) {
  return api<{ deleted: boolean }>(`catalog/titles/keywords/${id}?version=${version}`, 'DELETE');
}

/** 批量生成。**返回即已完成**——候选已经落库，可以直接去看。 */
export function generateTitles(
  input: Pick<TitleGenerationInput, 'templateId' | 'skuIds' | 'platforms'> & {
    keywordCodes: string[];
  },
) {
  return api<TitleGeneration>('catalog/titles/generations', 'POST', input);
}

export function fetchGenerations(params: {
  keyword?: string;
  status?: string;
  page: number;
  size: number;
}) {
  const query = new URLSearchParams();
  if (params.keyword?.trim()) query.set('keyword', params.keyword.trim());
  if (params.status) query.set('status', params.status);
  query.set('page', String(params.page));
  query.set('size', String(params.size));
  return api<TitleGenerationPage>(`catalog/titles/generations?${query.toString()}`);
}

export function fetchGenerationDetail(id: string) {
  return api<TitleGenerationDetail>(`catalog/titles/generations/${id}`);
}

export function fetchRecords(query: TitleRecordQuery) {
  const params = new URLSearchParams();
  if (query.skuId) params.set('skuId', query.skuId);
  if (query.generationId) params.set('generationId', query.generationId);
  if (query.platform?.trim()) params.set('platform', query.platform.trim());
  if (query.status) params.set('status', query.status);
  if (query.keyword?.trim()) params.set('keyword', query.keyword.trim());
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return api<TitleRecordPage>(`catalog/titles/records?${params.toString()}`);
}

export function selectRecord(id: string) {
  return api<TitleRecordSelected>(`catalog/titles/records/${id}/select`, 'POST');
}

export function deselectRecord(id: string) {
  return api<TitleRecordSelected>(`catalog/titles/records/${id}/deselect`, 'POST');
}

export function rejectRecord(id: string) {
  return api<TitleRecordSelected>(`catalog/titles/records/${id}/reject`, 'POST');
}

/** 导出 CSV 的浏览器地址（用普通 <a download>，不走 api()：它无条件设 JSON content-type）。 */
export function csvExportUrl(
  query: Pick<TitleRecordQuery, 'skuId' | 'platform' | 'status'>,
): string {
  const params = new URLSearchParams();
  if (query.skuId) params.set('skuId', query.skuId);
  if (query.platform?.trim()) params.set('platform', query.platform.trim());
  if (query.status) params.set('status', query.status);
  return `/api/catalog/titles/records/export?${params.toString()}`;
}

// ------------------------------------------------------------ 校验（与服务层同口径）

export function templateCodeProblem(value: string): string | null {
  const code = value.trim().toUpperCase();
  if (!code) return '模板编码不能为空';
  if (code.length > 32) return '模板编码最多 32 个字符';
  if (!/^[A-Z0-9][A-Z0-9_-]{0,31}$/.test(code))
    return '模板编码请使用英文字母、数字、下划线或短横线，长度 1~32 位';
  return null;
}

export function templateNameProblem(value: string): string | null {
  const name = value.trim();
  if (!name) return '模板名称不能为空';
  if (name.length > 100) return '模板名称最多 100 个字符';
  return null;
}

export function keywordCodeProblem(value: string): string | null {
  const code = value.trim().toUpperCase();
  if (!code) return '词根编码不能为空';
  if (code.length > 32) return '词根编码最多 32 个字符';
  if (!/^[A-Z0-9][A-Z0-9_-]{0,31}$/.test(code))
    return '词根编码请使用英文字母、数字、下划线或短横线，长度 1~32 位';
  return null;
}

export function keywordTextProblem(value: string): string | null {
  const text = value.trim();
  if (!text) return '词根内容不能为空';
  if (text.length > 60) return '词根内容最多 60 个字符';
  if (text.includes('{') || text.includes('}'))
    return '词根内容不能包含花括号：它是直接填进标题里的文本，不是另一个模板';
  return null;
}

/**
 * 重复度上限校验。上界 999 与后端一致：
 * 1000‰ 就是允许与已有标题完全一样，等于没有重复度控制。
 */
export function maxSimilarityProblem(value: number | null): string | null {
  if (value === null) return '重复度上限不能为空';
  if (value < 0 || value > 999)
    return '重复度上限必须在 0~999‰ 之间（1000‰ 就是允许完全一样，等于没有重复度控制）';
  return null;
}

export function candidateCountProblem(value: number | null): string | null {
  if (value === null) return '每个 SKU 的候选条数不能为空';
  if (value < 1 || value > 8) return '每个 SKU 每个平台的候选条数必须在 1~8 之间';
  return null;
}

/** 规范化模板文本：折叠连续空白 + 去首尾空白，与后端 TitleRules.normalizePattern 同口径。 */
export function normalizePattern(value: string): string {
  return value.trim().replace(/\s+/g, ' ');
}

/** 提取模板里的占位符名（不含花括号），按出现顺序去重。与后端 placeholdersOf 同口径。 */
export function placeholdersOf(pattern: string): string[] {
  const out: string[] = [];
  const regex = /\{([^}]*)\}/g;
  let match = regex.exec(pattern);
  while (match) {
    // noUncheckedIndexedAccess：捕获组也可能是 undefined。
    const name = (match[1] ?? '').trim();
    if (name && !out.includes(name)) out.push(name);
    match = regex.exec(pattern);
  }
  return out;
}

export function variableNameProblem(value: string): string | null {
  const name = value.trim();
  if (!name) return '变量名不能为空';
  if (name.length > 32) return '变量名最多 32 个字符';
  if (!/^[A-Za-z][A-Za-z0-9_]{0,31}$/.test(name))
    return '变量名要以英文字母开头，只含字母、数字或下划线，长度 1~32 位（例如 brand / core）';
  return null;
}

export function patternProblem(value: string): string | null {
  const pattern = normalizePattern(value);
  if (!pattern) return '标题模板不能为空';
  if (pattern.length > 2000) return '标题模板最多 2000 个字符';
  if (placeholdersOf(pattern).length === 0)
    return '标题模板里至少要有一个变量占位（例如 {brand} {category} {core}）';
  for (const name of placeholdersOf(pattern)) {
    const problem = variableNameProblem(name);
    if (problem) return problem;
  }
  return null;
}

/** 模板整体校验。与后端 TitleService.variablesProblem + platformsProblem 同口径。 */
export function templateProblem(input: TitleTemplateInput): string | null {
  const pattern = patternProblem(input.pattern);
  if (pattern) return pattern;

  const variables = (input.variables ?? []).filter((v): v is NonNullable<typeof v> => v !== null);
  if (variables.length === 0) return '模板至少要声明一个变量占位';
  if (variables.length > 12) return '一个模板最多 12 个变量占位';

  const declared: string[] = [];
  const sources: string[] = [];
  const seenNames = new Set<string>();
  const seenSlots = new Set<number>();
  for (let i = 0; i < variables.length; i++) {
    // noUncheckedIndexedAccess：下标取值可能是 undefined，先滤掉再判。
    const variable = variables[i];
    if (variable === undefined) continue;
    const nameProblem = variableNameProblem(variable.name);
    if (nameProblem) return nameProblem;
    if (!VARIABLE_SOURCES.some((s) => s.value === variable.source))
      return `变量绑定源只能是 ${VARIABLE_SOURCES.map((s) => s.value).join(' / ')}`;
    const name = variable.name.trim();
    if (seenNames.has(name)) return `变量名重复：${name}（一个占位符只能有一个绑定源）`;
    seenNames.add(name);
    const slot = variable.slotNo ?? i;
    if (slot < 0) return '变量渲染顺序不能为负数';
    if (seenSlots.has(slot)) return `变量渲染顺序重复：第 ${slot} 位有两个变量`;
    seenSlots.add(slot);
    declared.push(name);
    sources.push(variable.source);
  }

  const placeholders = placeholdersOf(normalizePattern(input.pattern));
  for (const placeholder of placeholders)
    if (!declared.includes(placeholder))
      return `模板里的占位符 {${placeholder}} 没有声明绑定源：请在变量列表里加一条 ${placeholder}`;
  for (const name of declared)
    if (!placeholders.includes(name))
      return `变量 ${name} 在模板文本里没有出现（应为 {${name}}）：死配置会让模板含义与实际渲染对不上`;
  if (!sources.some((s) => s.startsWith('KEYWORD_')))
    return '模板至少要有一个词根变量（核心词 / 卖点 / 修饰词）：只绑 SPU/SKU 字段的话每个 SKU 只有一种文本，候选会彼此雷同';

  const platforms = (input.platforms ?? []).filter((p): p is NonNullable<typeof p> => p !== null);
  if (platforms.length === 0) return '模板至少要配置一个平台的长度上限';
  if (platforms.length > 12) return '一个模板最多配 12 个平台长度上限';
  const seenPlatforms = new Set<string>();
  for (const platform of platforms) {
    if (platform === null) continue;
    if (!platform.platform.trim()) return '平台不能为空';
    if (platform.platform.length > 64) return '平台名称最多 64 个字符';
    if (platform.maxLength < 1 || platform.maxLength > 255)
      return '平台标题长度上限必须在 1~255 个字符之间';
    const key = platform.platform.trim().toUpperCase();
    if (seenPlatforms.has(key)) return `平台重复：${key}（同一模板内每个平台只能配一个长度上限）`;
    seenPlatforms.add(key);
  }

  return (
    templateCodeProblem(input.code) ??
    templateNameProblem(input.name) ??
    maxSimilarityProblem(input.maxSimilarityPermille ?? null) ??
    candidateCountProblem(input.candidateCount ?? null)
  );
}

/** 单次批量上限。与后端 `TitleRules.MAX_SKUS_PER_GENERATION` 同一口径。 */
export const MAX_SKUS_PER_GENERATION = 200;
export const MAX_PLATFORMS_PER_GENERATION = 5;
export const MAX_CANDIDATES_PER_SKU = 8;

export function skusSizeProblem(count: number): string | null {
  if (count === 0) return '请至少选择一个要生成标题的 SKU';
  if (count > MAX_SKUS_PER_GENERATION)
    return `单次最多为 ${MAX_SKUS_PER_GENERATION} 个 SKU 生成标题`;
  return null;
}

export function platformsSizeProblem(count: number): string | null {
  if (count === 0) return '请至少选择一个目标平台';
  if (count > MAX_PLATFORMS_PER_GENERATION)
    return `单次最多为 ${MAX_PLATFORMS_PER_GENERATION} 个平台生成标题`;
  return null;
}

// ------------------------------------------------------------ 展示格式化

/** 模板摘要：让列表页不用展开就知道这条标题怎么拼、重复度管到多严。 */
export function templateSummary(template: {
  pattern: string;
  maxSimilarityPermille: number;
  candidateCount: number;
  platforms: { platform: string; maxLength: number }[] | null;
}): string {
  const platforms = (template.platforms ?? [])
    .map((p) => `${p.platform} ≤${p.maxLength}`)
    .join(' · ');
  return [
    template.pattern,
    `候选 ${template.candidateCount} 条/平台`,
    `重复度上限 ${similarityText(template.maxSimilarityPermille)}`,
    platforms,
  ]
    .filter(Boolean)
    .join(' · ');
}

/** 词根溯源：把候选实际用到的三类词讲成一句人话。 */
export function keywordProvenance(record: {
  coreKeyword: string;
  sellingKeyword: string;
  modifierKeyword: string;
}): string {
  const parts: string[] = [];
  if (record.coreKeyword) parts.push(`核心词「${record.coreKeyword}」`);
  if (record.sellingKeyword) parts.push(`卖点「${record.sellingKeyword}」`);
  if (record.modifierKeyword) parts.push(`修饰词「${record.modifierKeyword}」`);
  return parts.length ? parts.join(' + ') : '没有可用的词根（该 SKU 的池子里没词）';
}

/** 字符数 / 上限：被截断时明确说出来，而不是只给一个数字让人自己比。 */
export function charLengthText(
  record: { charLength: number; truncated: boolean },
  maxLength: number | null | undefined,
): string {
  const base = maxLength ? `${record.charLength} / ${maxLength}` : `${record.charLength}`;
  return record.truncated ? `${base}（已按上限裁剪）` : base;
}

// ------------------------------------------------------------ 空表单与文案

export function emptyTemplateInput(): TitleTemplateInput {
  return {
    code: '',
    name: '',
    note: null,
    // pattern 必须与 defaultVariables() 的变量名逐一对上：多一个占位符会「没有声明绑定源」，
    // 少一个会变成死配置。defaultVariables() 里有 5 个变量，这里就写 5 个占位符。
    pattern: '{brand} {category} {core} {selling} {modifier}',
    maxSimilarityPermille: 850,
    candidateCount: 3,
    variables: defaultVariables(),
    platforms: defaultPlatforms(),
    enabled: true,
    version: 0,
  };
}

/** 蓝图点名的四个变量（品牌 + 品类 + 核心词 + 卖点），外加差异化用的修饰词。 */
export function defaultVariables(): TitleTemplateVariableInput[] {
  return [
    { name: 'brand', source: 'SPU_BRAND', slotNo: 0 },
    { name: 'category', source: 'SPU_CATEGORY', slotNo: 1 },
    { name: 'core', source: 'KEYWORD_CORE', slotNo: 2 },
    { name: 'selling', source: 'KEYWORD_SELLING', slotNo: 3 },
    { name: 'modifier', source: 'KEYWORD_MODIFIER', slotNo: 4 },
  ];
}

/** 常见平台标题长度上限的起始值（eBay 80 / Amazon 200 / Shopify 255 的通行口径）。 */
export function defaultPlatforms(): TitleTemplatePlatformInput[] {
  return [
    { platform: 'AMAZON', maxLength: 200 },
    { platform: 'EBAY', maxLength: 80 },
    { platform: 'SHOPIFY', maxLength: 255 },
  ];
}

export function emptyKeywordInput(): TitleKeywordInput {
  return { code: '', text: '', keywordType: 'CORE', enabled: true, version: 0 };
}

/** 常见词根的起始值，让新租户不用对着空字典发呆。 */
export function starterKeywords(type: string): string[] {
  if (type === 'CORE') return ['收纳盒', '蓝牙音箱', '厨房砧板'];
  if (type === 'SELLING') return ['长续航', '防水便携', '易清洗'];
  return ['新品', '家用款', '户外款'];
}

export function templateEmptyHint(): string {
  return '还没有标题模板。模板是一套可复用的标题排法：变量占位（品牌 + 品类 + 核心词 + 卖点 + 修饰词）、每个平台的长度上限，以及重复度上限。模板至少要有一个绑定到词池的变量——只绑 SPU/SKU 字段的话每个 SKU 只有一种文本，候选会彼此雷同。';
}

export function keywordEmptyHint(): string {
  return '还没有词根。词根是标题的差异化素材：核心词给主体、卖点给理由、修饰词拉开平台间差异。批量生成的组合空间全靠它们——池子太小会凑不出不雷同的候选。';
}

export function generationEmptyHint(filtered: boolean): string {
  return filtered
    ? '当前筛选条件下没有生成批次。先清掉筛选条件再看看。'
    : '还没有生成批次。先建一个模板、配好词根，然后勾选 SKU 与平台发起批量生成。';
}

export function recordEmptyHint(filtered: boolean): string {
  return filtered
    ? '当前筛选条件下没有标题候选。先清掉筛选条件再看看。'
    : '还没有标题候选。批量生成之后，候选集会出现在这里，逐条选用或导出给 04 刊登。';
}

/**
 * 提交后的提示。
 *
 * <p>必须说清「已生成多少条 / 有多少条被拒」，因为「被拒」是这个模块的**正常**结果
 * 而不是失败——不说清楚运营会以为系统坏了。
 */
export function generatedHint(generation: TitleGeneration): string {
  // FAILED 优先判断：一个候选都没产出时，「另有 N 条被拒」是次要信息，
  // 主因是「什么都没生成」，要指向词根库为空 / 品牌分类没填这类配置问题。
  if (generation.status === 'FAILED' || generation.candidateCount === 0)
    return '这一批一个候选都没产出。展开批次详情看逐 SKU 的原因：通常是词根库为空，或商品的品牌与分类都没填。';
  const base = `已为 ${generation.skuCount} 个 SKU 生成 ${generation.candidateCount} 条标题候选。`;
  if (generation.rejectedCount > 0)
    return `${base}另有 ${generation.rejectedCount} 条因重复度超限被拒——这通常意味着修饰词池太小，去加几个词就能多产出候选。`;
  return base;
}

/** 逐 SKU 未产出的原因：把「没产出」讲清是约束还是配置问题。 */
export function skuRejectionText(row: { rejectedCount: number; reason: string }): string | null {
  if (row.reason?.trim()) return row.reason;
  if (row.rejectedCount > 0) return `有 ${row.rejectedCount} 条候选因重复度超限被拒。`;
  return null;
}

export function errorText(error: unknown, fallback = '标题库数据加载失败') {
  if (error instanceof ApiError && error.message) return error.message;
  return fallback;
}

export type {
  TitleGeneration,
  TitleGenerationDetail,
  TitleGenerationRow,
  TitleKeywordRow,
  TitleRecord,
  TitleTemplateRow,
};
