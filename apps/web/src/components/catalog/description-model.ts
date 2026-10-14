import type {
  ContentOverview,
  ContentPhraseInput,
  ContentPhrasePage,
  ContentPhraseRow,
  ContentRender,
  ContentRenderDetail,
  ContentRenderInput,
  ContentRenderPage,
  ContentSection,
  ContentSectionPage,
  ContentTemplateInput,
  ContentTemplateRow,
  ContentTemplateSaved,
  ContentTemplateVariableInput,
  ContentTranslation,
  ContentTranslationCoverage,
  ContentTranslationInput,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 短语库筛选条件。
 *
 * <p>本地定义而不是从契约里取：它只由 query string 承载，`@RequestParam`
 * 不会出现在 OpenAPI 的 components 里（那里只有 body/response 形状）。
 * 字段与后端 {@code ContentPhraseQuery} 逐字对应。
 */
export type ContentPhraseQuery = {
  keyword: string | null;
  phraseType: string | null;
  enabled: string | null;
  page: number;
  size: number;
};

/** 段落列表筛选条件。字段与后端 {@code ContentSectionQuery} 逐字对应。 */
export type ContentSectionQuery = {
  skuId: string | null;
  locale: string | null;
  contentType: string | null;
  status: string | null;
  keyword: string | null;
  renderId: string | null;
  page: number;
  size: number;
};

/**
 * 3.4.2.2 描述与卖点库前端模型。
 *
 * <p>这个页面最容易出错的三处：
 *
 * <ul>
 *   <li><b>「缺变量」是一种结果，不是一种错误。</b>蓝图的硬要求是
 *       「变量缺失必须在填充时报告，不能静默留下占位符」——所以前端**必须**
 *       把缺失逐条显示出来，并说清每种成因怎么修。把它显示成「填充失败」的话，
 *       运营会以为要重来一次，而实际上补上 brand 就能用。
 *   <li><b>前端不重算填充结果。</b>正文由后端的 {@code DescriptionRenderer} 产出，
 *       这里是「照原样显示」。前端**不允许**自己把 {@code {brand}} 替成什么：
 *       那会让页面上看到的和 04 刊登引用到的不是同一段文字。
 *   <li><b>填充是同步的「已完成」，不是异步「已受理」。</b>提交返回后段落已经在库里了，
 *       可以直接跳去看详情。绝不能显示「正在填充」再轮询。
 * </ul>
 */

/** 内容类型词表：与 V21 的 CHECK 和 `DescriptionRules.CONTENT_TYPES` 同一口径。 */
export const CONTENT_TYPES = [
  { value: 'DESCRIPTION', label: '详情描述', hint: '详情页主体：讲清是什么、为什么好' },
  { value: 'INSTRUCTION', label: '使用说明', hint: '怎么装、怎么用、怎么保养' },
  { value: 'CAUTION', label: '注意事项', hint: '禁止事项与安全警示。可以整段固定文本，不带变量' },
  { value: 'SELLING_POINT', label: '卖点段落', hint: '一组可复用的卖点陈述' },
] as const;

/** 变量绑定源词表：事实字段从 SPU/SKU 取，短语池类从上面四类池子取。 */
export const VARIABLE_SOURCES = [
  { value: 'SPU_BRAND', label: 'SPU 品牌', group: '商品事实' },
  { value: 'SPU_CATEGORY', label: 'SPU 分类', group: '商品事实' },
  { value: 'SPU_NAME', label: 'SPU 名称', group: '商品事实' },
  { value: 'SKU_NAME', label: 'SKU 名称', group: '商品事实' },
  { value: 'SKU_CODE', label: 'SKU 编码', group: '商品事实' },
  { value: 'SKU_SPEC', label: 'SKU 规格', group: '商品事实' },
  { value: 'SKU_SIBLING_LIST', label: '同 SPU 型号清单', group: '商品事实' },
  { value: 'PHRASE_SELLING', label: '卖点短语池', group: '卖点短语库' },
  { value: 'PHRASE_FEATURE', label: '特性短语池', group: '卖点短语库' },
  { value: 'PHRASE_SCENE', label: '场景短语池', group: '卖点短语库' },
  { value: 'PHRASE_CAUTION', label: '注意事项短语池', group: '卖点短语库' },
] as const;

/** 短语类型词表。 */
export const PHRASE_TYPES = [
  { value: 'SELLING', label: '卖点陈述', hint: '双单元立体声，低音下潜到 20Hz' },
  { value: 'FEATURE', label: '特性描述', hint: 'IP67 防水，1.5 米深水 30 分钟' },
  { value: 'SCENE', label: '适用场景', hint: '露营 / 泳池 / 车载三场景' },
  { value: 'CAUTION', label: '注意事项短语', hint: '请勿在充电时拆解本机' },
] as const;

export const RENDER_STATUSES = [
  { value: 'SUCCEEDED', label: '已填充' },
  { value: 'PARTIAL', label: '部分段落缺变量' },
  { value: 'FAILED', label: '未填充出可用段落' },
] as const;

export const SECTION_STATUSES = [
  { value: 'COMPLETE', label: '已填好' },
  { value: 'INCOMPLETE', label: '缺变量' },
] as const;

export const TRANSLATION_ORIGINS = [
  { value: 'MANUAL', label: '人工撰写' },
  { value: 'AI', label: 'AI 预填' },
  { value: 'IMPORTED', label: '导入' },
] as const;

export const TRANSLATION_STATUSES = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'REVIEWED', label: '已复核' },
] as const;

/**
 * 缺失变量的四种成因。
 *
 * <p>每一种都带**修法**——这是这个模块对运营最有用的一点：
 * 「brand 缺了 200 次」他不知道该改什么，
 * 「brand 缺了 200 次，因为这个 SPU 的品牌字段是空的，去商品页补上」他立刻知道。
 */
export const ISSUE_KINDS = [
  { value: 'EMPTY_FACT', label: '商品字段为空', fix: '去商品页补上这个字段，或把变量从模板里去掉' },
  { value: 'EMPTY_PHRASE_POOL', label: '短语池为空', fix: '去卖点短语库里建至少一条该类型的短语' },
  {
    value: 'TRANSLATION_MISSING',
    label: '该语言没有译文',
    fix: '补一条该语言的译文，或改用已配好的语言',
  },
  {
    value: 'UNBOUND_PLACEHOLDER',
    label: '占位符没有绑定源',
    fix: '在模板变量列表里给它加一条绑定',
  },
] as const;

export const MAX_SKUS_PER_RENDER = 200;
export const MAX_VARIABLES_PER_TEMPLATE = 16;
export const MAX_BODY_LENGTH = 20000;
export const MAX_PHRASE_LENGTH = 2000;

export function contentTypeLabel(value: string | null | undefined): string {
  return CONTENT_TYPES.find((t) => t.value === value)?.label ?? value ?? '未知';
}

export function sourceLabel(value: string | null | undefined): string {
  return VARIABLE_SOURCES.find((s) => s.value === value)?.label ?? value ?? '未知';
}

export function phraseTypeLabel(value: string | null | undefined): string {
  return PHRASE_TYPES.find((t) => t.value === value)?.label ?? value ?? '未知';
}

export function renderStatusLabel(value: string | null | undefined): string {
  return RENDER_STATUSES.find((s) => s.value === value)?.label ?? value ?? '未知';
}

export function sectionStatusLabel(value: string | null | undefined): string {
  return SECTION_STATUSES.find((s) => s.value === value)?.label ?? value ?? '未知';
}

export function originLabel(value: string | null | undefined): string {
  return TRANSLATION_ORIGINS.find((o) => o.value === value)?.label ?? value ?? '未知';
}

export function translationStatusLabel(value: string | null | undefined): string {
  return TRANSLATION_STATUSES.find((s) => s.value === value)?.label ?? value ?? '未知';
}

/** Badge 色调，直接用 `@starcon/ui` 的 tone 词表，不自造一套。 */
export function renderStatusTone(
  status: string | null | undefined,
): 'neutral' | 'success' | 'danger' | 'warning' {
  if (status === 'SUCCEEDED') return 'success';
  if (status === 'PARTIAL') return 'warning';
  if (status === 'FAILED') return 'danger';
  return 'neutral';
}

export function sectionStatusTone(
  status: string | null | undefined,
): 'neutral' | 'success' | 'warning' {
  return status === 'COMPLETE' ? 'success' : 'warning';
}

/**
 * 一段正文里缺了几个变量。
 *
 * <p>缺失是 warn 而不是 danger：正文照样产出了（占位符被剥掉），
 * 它不能被下游当成可用内容，但也不是「系统坏了」。
 */
export function missingTone(count: number): 'neutral' | 'warning' | 'danger' {
  if (count === 0) return 'neutral';
  if (count <= 2) return 'warning';
  return 'danger';
}

/** 缺失变量的修法提示（按成因取 ISSUE_KINDS 里的 fix）。 */
export function issueFix(issueKind: string | null | undefined): string {
  return ISSUE_KINDS.find((k) => k.value === issueKind)?.fix ?? '请检查模板变量绑定与短语池';
}

/** 一段正文能不能被 04 刊登 / 15 客服引用。只有「已填好」才行。 */
export function sectionUsable(section: {
  complete: boolean;
  issues: unknown[];
}): 'usable' | 'blocked' {
  return section.complete && section.issues.length === 0 ? 'usable' : 'blocked';
}

// ------------------------------------------------------------ 接口

export function fetchContentOverview() {
  return api<ContentOverview>('catalog/descriptions/overview');
}

export function fetchContentTemplates() {
  return api<ContentTemplateRow[]>('catalog/descriptions/templates');
}

export function fetchContentTemplate(id: string) {
  return api<ContentTemplateRow>(`catalog/descriptions/templates/${id}`);
}

export function saveContentTemplate(id: string | null, input: ContentTemplateInput) {
  return id
    ? api<ContentTemplateSaved>(`catalog/descriptions/templates/${id}`, 'PUT', input)
    : api<ContentTemplateSaved>('catalog/descriptions/templates', 'POST', input);
}

export function setContentTemplateEnabled(id: string, enabled: boolean) {
  return api<ContentTemplateSaved>(
    `catalog/descriptions/templates/${id}/enabled?enabled=${enabled}`,
    'PUT',
  );
}

export function deleteContentTemplate(id: string, version: number) {
  return api<{ deleted: boolean }>(
    `catalog/descriptions/templates/${id}?version=${version}`,
    'DELETE',
  );
}

export function fetchTranslations(templateId: string) {
  return api<ContentTranslation[]>(`catalog/descriptions/templates/${templateId}/translations`);
}

export function fetchTranslationCoverage(templateId: string, locales: string[]) {
  const params = new URLSearchParams();
  for (const locale of locales) params.append('locale', locale);
  return api<ContentTranslationCoverage>(
    `catalog/descriptions/templates/${templateId}/translation-coverage?${params.toString()}`,
  );
}

export function saveTranslation(
  templateId: string,
  id: string | null,
  input: ContentTranslationInput,
) {
  return id
    ? api<ContentTranslation>(
        `catalog/descriptions/templates/${templateId}/translations/${id}`,
        'PUT',
        input,
      )
    : api<ContentTranslation>(
        `catalog/descriptions/templates/${templateId}/translations`,
        'POST',
        input,
      );
}

export function deleteTranslation(id: string, version: number) {
  return api<{ deleted: boolean }>(
    `catalog/descriptions/translations/${id}?version=${version}`,
    'DELETE',
  );
}

export function fetchPhrases(query: ContentPhraseQuery) {
  const params = new URLSearchParams();
  if (query.keyword?.trim()) params.set('keyword', query.keyword.trim());
  if (query.phraseType) params.set('phraseType', query.phraseType);
  if (query.enabled) params.set('enabled', query.enabled);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return api<ContentPhrasePage>(`catalog/descriptions/phrases?${params.toString()}`);
}

export function savePhrase(id: string | null, input: ContentPhraseInput) {
  return id
    ? api<ContentPhraseRow>(`catalog/descriptions/phrases/${id}`, 'PUT', input)
    : api<ContentPhraseRow>('catalog/descriptions/phrases', 'POST', input);
}

export function setPhraseEnabled(id: string, enabled: boolean) {
  return api<ContentPhraseRow>(
    `catalog/descriptions/phrases/${id}/enabled?enabled=${enabled}`,
    'PUT',
  );
}

export function deletePhrase(id: string, version: number) {
  return api<{ deleted: boolean }>(
    `catalog/descriptions/phrases/${id}?version=${version}`,
    'DELETE',
  );
}

/** 批量填充。**返回即已完成**——段落与缺失报告已经落库，可以直接去看。 */
export function renderDescriptions(input: ContentRenderInput) {
  return api<ContentRender>('catalog/descriptions/renders', 'POST', input);
}

export function fetchRenders(params: {
  keyword?: string | null;
  status?: string | null;
  page: number;
  size: number;
}) {
  const query = new URLSearchParams();
  if (params.keyword?.trim()) query.set('keyword', params.keyword.trim());
  if (params.status) query.set('status', params.status);
  query.set('page', String(params.page));
  query.set('size', String(params.size));
  return api<ContentRenderPage>(`catalog/descriptions/renders?${query.toString()}`);
}

export function fetchRenderDetail(id: string) {
  return api<ContentRenderDetail>(`catalog/descriptions/renders/${id}`);
}

/**
 * 某批次的「缺变量」段落。
 *
 * <p>批量缺失的按变量汇总在 {@code fetchRenderDetail().missingVariables} 上
 * （「brand 缺 200 次」比 200 行罗列有用），这里取的是逐段落的明细，
 * 供只想看「哪几段没填好」时用。
 */
export function fetchMissingSections(renderId: string) {
  return api<ContentSectionPage>(
    `catalog/descriptions/sections?renderId=${renderId}&status=INCOMPLETE&page=0&size=200`,
  );
}

export function fetchSections(query: ContentSectionQuery) {
  const params = new URLSearchParams();
  if (query.skuId) params.set('skuId', query.skuId);
  if (query.locale) params.set('locale', query.locale);
  if (query.contentType) params.set('contentType', query.contentType);
  if (query.status) params.set('status', query.status);
  if (query.keyword?.trim()) params.set('keyword', query.keyword.trim());
  if (query.renderId) params.set('renderId', query.renderId);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return api<ContentSectionPage>(`catalog/descriptions/sections?${params.toString()}`);
}

/** 导出 CSV 的浏览器地址（用普通 <a download>，不走 api()：它无条件设 JSON content-type）。 */
export function csvExportUrl(
  query: Pick<ContentSectionQuery, 'skuId' | 'locale' | 'contentType' | 'status'>,
): string {
  const params = new URLSearchParams();
  if (query.skuId) params.set('skuId', query.skuId);
  if (query.locale) params.set('locale', query.locale);
  if (query.contentType) params.set('contentType', query.contentType);
  if (query.status) params.set('status', query.status);
  return `/api/catalog/descriptions/sections/export?${params.toString()}`;
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

export function contentTypeProblem(value: string): string | null {
  if (!CONTENT_TYPES.some((t) => t.value === value)) return '请选择内容类型';
  return null;
}

/** 规范化正文：折叠连续空白 + 去首尾空白，与后端同一口径。 */
export function normalizeBody(value: string): string {
  const text = value.trim();
  return text ? text.replace(/\s+/g, ' ') : '';
}

/**
 * 第一个未配平花括号的位置，没有则返回 -1。
 *
 * <p>漏写右括号会让占位符解析把后面一整段都吞成变量名，
 * 而填充时看不出来却会漏内容 —— 所以在保存前就拦。
 */
export function unbalancedBraceIndex(value: string): number {
  let open = -1;
  for (let i = 0; i < value.length; i += 1) {
    const c = value[i];
    if (c === '{') {
      if (open < 0) open = i;
    } else if (c === '}') {
      if (open < 0) return i;
      open = -1;
    }
  }
  return open;
}

/**
 * 正文校验。
 *
 * <p>与 3.4.2.1 的标题模板相反，**不要求**至少一个占位符：
 * 「请勿进水」这种注意事项就是一个变量都没有的固定句子，它完全成立。
 * 但花括号必须配平。
 */
export function bodyProblem(value: string): string | null {
  const text = normalizeBody(value);
  if (!text) return '模板正文不能为空';
  if (text.length > MAX_BODY_LENGTH)
    return `模板正文最多 ${MAX_BODY_LENGTH} 个字符（长文本，但仍不是整本说明书）`;
  const at = unbalancedBraceIndex(text);
  if (at >= 0)
    return `正文里的花括号没有配平：位置 ${at + 1} 附近有一个孤立的 { 或 }。漏写右括号会让占位符解析把后面一整段都吞成变量名`;
  for (const name of placeholdersOf(text)) {
    const problem = variableNameProblem(name);
    if (problem) return problem;
  }
  return null;
}

/** 提取正文里的占位符名（不含花括号），按出现顺序去重。 */
export function placeholdersOf(body: string): string[] {
  const text = normalizeBody(body);
  const out: string[] = [];
  let i = 0;
  while (i < text.length) {
    const open = text.indexOf('{', i);
    if (open < 0) break;
    const close = text.indexOf('}', open + 1);
    if (close < 0) break;
    const name = text.slice(open + 1, close).trim();
    if (name && !out.includes(name)) out.push(name);
    i = close + 1;
  }
  return out;
}

export function variableNameProblem(value: string): string | null {
  const name = value.trim();
  if (!name) return '变量名不能为空';
  if (name.length > 32) return '变量名最多 32 个字符';
  if (!/^[A-Za-z][A-Za-z0-9_]{0,31}$/.test(name))
    return '变量名要以英文字母开头，只含字母、数字或下划线，长度 1~32 位（例如 brand / material）';
  return null;
}

export function sourceProblem(value: string): string | null {
  if (!VARIABLE_SOURCES.some((s) => s.value === value)) return '请选择变量绑定源';
  return null;
}

/**
 * 变量声明与正文占位符必须**双向**对得上。
 *
 * <p>少声明 → 填充时被报成「缺变量」（那正是蓝图禁止的静默占位符）；
 * 多声明 → 死配置，会让模板含义与实际填充对不上。两个方向都挡在保存前。
 *
 * <p>入参接受 `null` 项：契约里 `ContentTemplateVariableInput` 是 `X | null`
 * （整体替换语义下表单可能短暂持有空项），与服务层跳过 null 的行为一致。
 */
export function variableBindingProblem(
  body: string,
  declared: ({ name: string } | null | undefined)[] | null,
): string | null {
  const placeholders = placeholdersOf(body);
  const names = (declared ?? []).map((v) => (v?.name ?? '').trim()).filter(Boolean);
  for (const placeholder of placeholders)
    if (!names.includes(placeholder))
      return `正文里的占位符 {${placeholder}} 没有声明绑定源：请在变量列表里加一条 ${placeholder}。没声明的占位符填不出值，会被降级成「缺变量」并报告`;
  for (const name of names)
    if (!placeholders.includes(name))
      return `变量 ${name} 在模板正文里没有出现（应为 {${name}}）：死配置会让模板含义与实际填充对不上`;
  return null;
}

export function variablesSizeProblem(count: number): string | null {
  if (count > MAX_VARIABLES_PER_TEMPLATE)
    return `一个模板最多 ${MAX_VARIABLES_PER_TEMPLATE} 个变量占位`;
  return null;
}

/** 语言标签校验：BCP-47 简写。下划线写法会让同一个语言建出两条译文。 */
export function localeProblem(value: string): string | null {
  const text = value.trim();
  if (!text) return '语言不能为空（例如 zh-CN / en-US）';
  if (text.length > 16) return '语言标签最多 16 个字符';
  if (!/^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?$/.test(text))
    return '语言标签请用 BCP-47 简写（zh-CN / en-US / ja-JP）：不要用中文名或下划线写法';
  return null;
}

/** 规范化语言：语言小写 + 地区大写，与后端同一口径（否则筛选会静默失效）。 */
export function normalizeLocale(value: string): string {
  const text = value.trim();
  const at = text.indexOf('-');
  if (at < 0) return text.toLowerCase();
  return `${text.slice(0, at).toLowerCase()}-${text.slice(at + 1).toUpperCase()}`;
}

/**
 * 译文的占位符必须与源语言**完全一致**，三个方向都查。
 *
 * <p>这正是 AI 预填最典型的失败模式：漏一个（那句话对所有 SKU 都一样）、
 * 多一个或拼错（每批都降级成缺变量）。
 */
export function translationPlaceholdersProblem(
  sourceBody: string,
  translatedBody: string,
): string | null {
  const source = placeholdersOf(sourceBody);
  const translated = placeholdersOf(translatedBody);
  for (const name of translated)
    if (!source.includes(name))
      return `译文里的占位符 {${name}} 在源语言正文里没有：译文必须与源语言的占位符集合完全一致。多出来的占位符填不出值，会让每次填充都降级成「缺变量」`;
  for (const name of source)
    if (!translated.includes(name))
      return `译文漏掉了源语言的占位符 {${name}}：译文必须与源语言的占位符集合完全一致。漏掉的占位符会让那句话对所有 SKU 都一样`;
  return null;
}

export function phraseCodeProblem(value: string): string | null {
  const code = value.trim().toUpperCase();
  if (!code) return '短语编码不能为空';
  if (code.length > 32) return '短语编码最多 32 个字符';
  if (!/^[A-Z0-9][A-Z0-9_-]{0,31}$/.test(code))
    return '短语编码请使用英文字母、数字、下划线或短横线，长度 1~32 位';
  return null;
}

export function phraseTextProblem(value: string): string | null {
  const text = value.trim();
  if (!text) return '短语内容不能为空';
  if (text.length > MAX_PHRASE_LENGTH) return `短语内容最多 ${MAX_PHRASE_LENGTH} 个字符`;
  if (text.includes('{') || text.includes('}'))
    return '短语内容不能包含花括号：它是直接填进正文里的文本，不是另一个模板';
  return null;
}

export function phraseTypeProblem(value: string): string | null {
  if (!PHRASE_TYPES.some((t) => t.value === value)) return '请选择短语类型';
  return null;
}

export function skusSizeProblem(count: number): string | null {
  if (count === 0) return '请至少选择一个要填充描述的 SKU';
  if (count > MAX_SKUS_PER_RENDER) return `单次最多为 ${MAX_SKUS_PER_RENDER} 个 SKU 填充描述`;
  return null;
}

// ------------------------------------------------------------ 表单初值与展示

export function emptyTemplateInput(): ContentTemplateInput {
  return {
    code: '',
    name: '',
    note: null,
    contentType: 'DESCRIPTION',
    body: '',
    sourceLocale: 'zh-CN',
    variables: [],
    enabled: true,
    version: 0,
  };
}

export function defaultVariables(): ContentTemplateVariableInput[] {
  return [
    { name: 'brand', source: 'SPU_BRAND', slotNo: 0 },
    { name: 'selling', source: 'PHRASE_SELLING', slotNo: 1 },
  ];
}

export function emptyPhraseInput(): ContentPhraseInput {
  return { code: '', text: '', phraseType: 'SELLING', enabled: true, version: 0 };
}

export function emptyTranslationInput(sourceBody: string): ContentTranslationInput {
  return {
    locale: '',
    name: '',
    // 译文初值带上源语言的正文：运营改成目标语言时，占位符天然在位，
    // 也就不会因为「手打一遍忘了打 {brand}」而漏掉占位符。
    body: sourceBody,
    origin: 'MANUAL',
    status: 'DRAFT',
    version: 0,
  };
}

export function emptyRenderInput(templateId = '', locale = 'zh-CN'): ContentRenderInput {
  return { templateId, skuIds: [], locale };
}

/** 模板一行的一句话摘要：类型 + 变量数 + 已配语言数。 */
export function templateSummary(template: {
  contentType: string;
  variables: unknown[];
  locales: string[];
}): string {
  const vars = template.variables.length === 0 ? '固定文本' : `${template.variables.length} 个变量`;
  const locales =
    template.locales.length === 0 ? '仅源语言' : `已配 ${template.locales.length} 种语言`;
  return `${contentTypeLabel(template.contentType)} · ${vars} · ${locales}`;
}

/**
 * 一段正文的展示文案。
 *
 * <p>缺变量时**不**在这里做任何占位符替换 —— 正文里本来就没有占位符
 * （后端剥掉了），前端照原样显示，缺失单列在 issues 上。
 */
export function sectionText(section: { text: string; complete: boolean }): string {
  return section.complete ? section.text : `${section.text}（这段有变量没填上，不能直接发布）`;
}

/** 短语文本快照的展示：空数组要说清是「没用到」而不是留空。 */
export function usedPhrasesText(phrases: string[] | null | undefined): string {
  if (!phrases || phrases.length === 0) return '未使用短语';
  return phrases.join(' / ');
}

export function templateEmptyHint(): string {
  return '还没有内容模板。蓝图里的四类内容各有各的位置：详情描述（详情页主体）、使用说明（怎么用）、注意事项（安全警示）、卖点段落（可复用的卖点陈述）。先建一个详情描述模板。';
}

export function phraseEmptyHint(): string {
  return '卖点短语库还是空的。绑到 PHRASE_* 的变量从这里取值；某个池子一条短语都没有时，填充会把那个变量报成「短语池为空」，而不是渲染成空白。';
}

export function translationEmptyHint(sourceLocale: string): string {
  return `还没有译文。源语言 ${sourceLocale} 直接用模板正文渲染；其它语言必须配一条译文才能填充——缺译文时不会悄悄退回源语言正文，因为那会被当成译文发到平台上。`;
}

export function renderEmptyHint(filtered: boolean): string {
  return filtered
    ? '没有匹配的填充批次。换个关键词或状态筛选试试。'
    : '还没有填充过描述。选一个模板 + 一种语言 + 一批 SKU，几十毫秒就填好，段落与缺失报告立刻可查。';
}

export function sectionEmptyHint(filtered: boolean): string {
  return filtered
    ? '没有匹配的段落。换个筛选条件试试。'
    : '还没有段落。04 刊登与 15 客服会从这里取「已填好」的正文；缺变量的段落也会列出来，但它们不能发布。';
}

/** 缺变量的总览一句话：这个库当前最该做的一件事。 */
export function missingSummary(complete: number, incomplete: number, issues: number): string {
  if (incomplete === 0) return `${complete} 段全部已填好，可以直接被 04 刊登引用。`;
  return `${incomplete} 段缺变量（合计 ${issues} 次），正文里没有占位符残留，但这些段落不能发布：补完模板变量或短语池后重跑即可。`;
}

/**
 * 错误文案：把后端给的原因**原样透出**。
 *
 * <p>后端拒绝时给的就是可执行的中文原因（「模板没有语言 en-US 的译文，不能用这个语言填充」），
 * 前端换成自己的话等于把可行动的信息丢掉，所以这里只兜底处理非 Error 的抛出物。
 */
export function errorText(error: unknown, fallback = '请求失败，请稍后重试'): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return fallback;
}
