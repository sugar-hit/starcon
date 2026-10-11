import type {
  ContentTemplateVariableInput,
  DescriptionImageSlotInput,
  DescriptionRenderInput,
  DescriptionRenderPage,
  DescriptionRenderProduct,
  DescriptionRenderRow,
  DescriptionSectionInput,
  DescriptionSectionRow,
  DescriptionTemplateInput,
  DescriptionTemplateOptions,
  DescriptionTemplateOverview,
  DescriptionTemplateRow,
  DescriptionTemplateSaved,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 渲染结果筛选条件。本地定义：`@RequestParam` 不会进 OpenAPI components，
 * 字段与后端 {@code DescriptionRenderQuery} 逐字对应。
 */
/**
 * 契约里的可空收窄别名。
 *
 * <p>codegen 按 `@Schema(nullable = true)` 与 `List<...>` 的声明把数组元素也标成
 * `| null`（Spring 的 JSON 契约里它们确实可能是 null）。编辑区全程只操作
 * **已归一化**的草稿，所以这里先剥掉 null 再用 —— 比在 JSX 里散落十几处
 * `?? []` 更好读，也不会因为漏一处就在类型检查上炸。
 */
/** 显式收窄：NonNullable 只剥最外层的 null，数组**元素**的 `| null` 还在。 */
export type VariableDraft = { name: string; source: string; slotNo: number | null };
export type ImageSlotDraft = {
  bindSource: string;
  caption: string | null;
  assetId: string | null;
  required: boolean;
  slotNo: number;
};
export type SectionDraft = {
  sectionType: string;
  body: string;
  variables: VariableDraft[] | null;
  imageSlots: ImageSlotDraft[] | null;
  showHeading: boolean;
  contentTemplateId: string | null;
};

/** 编辑草稿形状：契约形状归一化之后的东西。 */
export type DescriptionTemplateDraft = {
  code: string;
  name: string;
  note: string;
  sourceLocale: string;
  sections: SectionDraft[];
  enabled: boolean;
  version: number;
};

/**
 * 把契约形状归一化成编辑草稿：剔除 null 元素、补齐缺省字段。
 *
 * <p>页面上任何一个数组都不会是「可能含 null 的数组」—— 那是传输形状，不是编辑形状。
 */
export function normalizeDraft(input: DescriptionTemplateInput): DescriptionTemplateDraft {
  return {
    code: input.code,
    name: input.name,
    note: input.note ?? '',
    sourceLocale: input.sourceLocale,
    sections: (input.sections ?? [])
      .filter((section): section is NonNullable<typeof section> => section !== null)
      .map((section) => ({
        sectionType: section.sectionType,
        body: section.body,
        variables: (section.variables ?? [])
          .filter((variable): variable is NonNullable<typeof variable> => variable !== null)
          .map((variable) => ({
            name: variable.name,
            source: variable.source,
            slotNo: variable.slotNo ?? null,
          })),
        imageSlots: (section.imageSlots ?? [])
          .filter((slot): slot is NonNullable<typeof slot> => slot !== null)
          .map((slot) => ({
            bindSource: slot.bindSource,
            caption: slot.caption ?? '',
            assetId: slot.assetId ?? null,
            required: slot.required,
            slotNo: slot.slotNo,
          })),
        showHeading: section.showHeading,
        contentTemplateId: section.contentTemplateId ?? null,
      })),
    enabled: input.enabled,
    version: input.version,
  };
}

export type DescriptionRenderQuery = {
  keyword: string | null;
  status: string | null;
  skuId: string | null;
  page: number;
  size: number;
};

/**
 * 4.2.2.2 描述模板前端模型。
 *
 * <p>这个页面最容易出错的三处：
 *
 * <ul>
 *   <li><b>「缺变量 / 缺图」是一种结果，不是一种错误。</b>蓝图的硬要求是
 *       「变量未绑定必须显式提示，不允许把 {{尺寸}} 原样发到平台」，所以前端
 *       <b>必须</b>逐条显示缺失并说清每种成因怎么修。显示成「渲染失败」的话，
 *       运营会以为要重来一次，而实际上补上 brand 就能用。
 *   <li><b>前端不重算渲染结果。</b>HTML 由后端的
 *       {@code DescriptionTemplateRenderer} 产出，这里是「照原样显示」。
 *       前端不允许自己把占位符替成什么：那会让页面上看到的和 4.2.1.1 引用到的
 *       不是同一份 HTML。
 *   <li><b>渲染是同步的「已完成」，不是异步「已受理」。</b>提交返回时 HTML 与
 *       缺失报告都已经在库里了，可以直接跳去看详情。绝不能显示「正在渲染」再轮询。
 * </ul>
 */

/** 五类段落词表：与 V96 的 CHECK 和 `DescriptionTemplateRules.SECTION_TYPES` 同一口径。 */
export const SECTION_TYPES = [
  { value: 'INTRO', label: '产品介绍', hint: '详情页开头：是什么、为什么值得买' },
  { value: 'SCENE', label: '使用场景', hint: '什么人在什么场合用' },
  { value: 'SPEC', label: '规格参数', hint: '尺寸、重量、型号这类硬参数' },
  { value: 'CAUTION', label: '注意事项', hint: '禁止事项与安全警示' },
  { value: 'SERVICE_PROMISE', label: '售后承诺', hint: '退换、质保、客服承诺' },
] as const;

export const SECTION_TYPE_VALUES: readonly string[] = SECTION_TYPES.map((item) => item.value);

export function sectionTypeLabel(value: string): string {
  return SECTION_TYPES.find((item) => item.value === value)?.label ?? value;
}

/** 图片插入位的取图来源词表。 */
export const IMAGE_BIND_SOURCES = [
  {
    value: 'RENDER_INPUT',
    label: '渲染时由编辑器给图',
    hint: '4.2.1.1 在渲染请求里按位号给已解析的 URL',
  },
  { value: 'SKU_PRIMARY', label: '取该 SKU 的主图', hint: '从素材库按用途取主图' },
  { value: 'SKU_GALLERY', label: '取该 SKU 的附图', hint: '从素材库按用途取附图，按位号取第 N 张' },
] as const;

export function bindSourceLabel(value: string): string {
  return IMAGE_BIND_SOURCES.find((item) => item.value === value)?.label ?? value;
}

export const RENDER_STATUSES = [
  { value: 'SUCCEEDED', label: '五段全填好' },
  { value: 'PARTIAL', label: '有段落缺变量或缺图' },
  { value: 'FAILED', label: '未渲染出可用段落' },
] as const;

export function renderStatusLabel(value: string): string {
  return RENDER_STATUSES.find((item) => item.value === value)?.label ?? value;
}

/** 「能不能直接发给平台」——只有 SUCCEEDED 才允许，这是页面上最要紧的一句。 */
export function renderPublishable(status: string): boolean {
  return status === 'SUCCEEDED';
}

export function renderStatusTone(status: string): string {
  if (status === 'SUCCEEDED') return 'text-emerald-600';
  if (status === 'PARTIAL') return 'text-amber-600';
  return 'text-destructive';
}

export const SECTION_STATUSES = [
  { value: 'COMPLETE', label: '已填好' },
  { value: 'INCOMPLETE', label: '缺变量或缺图' },
] as const;

export function sectionStatusTone(status: string): string {
  return status === 'COMPLETE' ? 'text-emerald-600' : 'text-amber-600';
}

export const ISSUE_KINDS = [
  { value: 'EMPTY_FACT', label: '商品字段为空' },
  { value: 'EMPTY_PHRASE_POOL', label: '短语池为空' },
  { value: 'TRANSLATION_MISSING', label: '该语言没有译文' },
  { value: 'UNBOUND_PLACEHOLDER', label: '占位符没有绑定源' },
  { value: 'IMAGE_UNBOUND', label: '必填插图位没有图' },
] as const;

export function issueKindLabel(value: string): string {
  return ISSUE_KINDS.find((item) => item.value === value)?.label ?? value;
}

/**
 * 变量绑定源词表：与 3.4.2.2 **完全同一份**。
 *
 * <p>这里刻意不自己写一份 —— 「{{尺寸}} 的绑定源」在全仓只能有一个答案，
 * 前端另抄一份迟早和后端不同步，而不同步的表现是「下拉里能选，保存时被拒」。
 */
export const VARIABLE_SOURCES = [
  { value: 'SPU_BRAND', label: 'SPU 品牌', group: '商品事实' },
  { value: 'SPU_CATEGORY', label: 'SPU 分类', group: '商品事实' },
  { value: 'SPU_NAME', label: 'SPU 名称', group: '商品事实' },
  { value: 'SKU_NAME', label: 'SKU 名称', group: '商品事实' },
  { value: 'SKU_CODE', label: 'SKU 编码', group: '商品事实' },
  { value: 'SKU_SPEC', label: 'SKU 规格', group: '商品事实' },
  { value: 'SKU_SIBLING_LIST', label: '同 SPU 型号清单', group: '商品事实' },
  { value: 'PHRASE_SELLING', label: '卖点短语池', group: '文案短语' },
  { value: 'PHRASE_FEATURE', label: '特性短语池', group: '文案短语' },
  { value: 'PHRASE_SCENE', label: '场景短语池', group: '文案短语' },
  { value: 'PHRASE_CAUTION', label: '注意事项短语池', group: '文案短语' },
] as const;

/** 一个模板最多 8 段（与 `DescriptionTemplateRules.MAX_SECTIONS` 同源）。 */
export const MAX_SECTIONS = 8;
/** 一个段落最多 8 个插图位（与 `MAX_IMAGE_SLOTS_PER_SECTION` 同源）。 */
export const MAX_IMAGE_SLOTS_PER_SECTION = 8;

/** 每类段落建议的短语绑定源，页面配变量时用来给初值。 */
export const DEFAULT_PHRASE_BY_SECTION: Record<string, string> = {
  INTRO: 'PHRASE_SELLING',
  SCENE: 'PHRASE_SCENE',
  SPEC: '',
  CAUTION: 'PHRASE_CAUTION',
  SERVICE_PROMISE: '',
};

// ------------------------------------------------------------ 校验（与后端 DescriptionTemplateRules 同一口径）

export function sectionTypeProblem(sectionType: string): string | null {
  return SECTION_TYPE_VALUES.includes(sectionType)
    ? null
    : `段落类型只能是 ${SECTION_TYPES.map((item) => item.label).join(' / ')}`;
}

export function sectionsSizeProblem(size: number): string | null {
  return size > MAX_SECTIONS ? `一个描述模板最多 ${MAX_SECTIONS} 个段落` : null;
}

export function imageSlotsSizeProblem(size: number): string | null {
  return size > MAX_IMAGE_SLOTS_PER_SECTION
    ? `一个段落最多 ${MAX_IMAGE_SLOTS_PER_SECTION} 个图片插入位`
    : null;
}

/**
 * 重复段落类型要拦。
 *
 * <p>同一模板里两段「产品介绍」会让渲染出两个同名 section，页面与平台上都分不清
 * 哪段是哪段（后端还有 UNIQUE 兜底，前端先说清楚更好修）。
 */
export function sectionTypesProblem(sectionTypes: readonly string[]): string | null {
  const seen: string[] = [];
  for (const raw of sectionTypes) {
    const value = raw.toUpperCase();
    if (seen.includes(value))
      return `段落类型「${sectionTypeLabel(value)}」重复了：同一模板里同一类只能有一段`;
    seen.push(value);
  }
  return null;
}

/**
 * 图片地址只允许 http/https。
 *
 * <p>渲染出的 HTML 是直接交给平台上传的，`javascript:` 落在 `&lt;img src&gt;` 上
 * 是一条真实注入路径。与后端 `imageUrlProblem` 同一份判定。
 */
export function imageUrlProblem(url: string): string | null {
  const value = url.trim();
  if (!value) return '图片地址不能为空';
  if (value.length > 1000) return '图片地址最多 1000 个字符';
  if (!/^https?:\/\/[^\s"'<>]+$/i.test(value))
    return '图片地址必须以 http:// 或 https:// 开头';
  return null;
}

/** 插图位编号必须从 0 连续：data-slot-no 是渲染出来给平台定位用的。 */
export function imageSlotNumberingProblem(
  slots: ({ slotNo: number } | null)[],
): string | null {
  for (let i = 0; i < slots.length; i++) {
    if (slots[i]?.slotNo !== i)
      return `图片插入位的编号必须从 0 连续递增（当前第 ${i + 1} 个是 ${slots[i]?.slotNo ?? '空'}）`;
  }
  return null;
}

/** 提取正文里的占位符名（不含花括号），按出现顺序去重。 */
export function placeholdersOf(body: string): string[] {
  const out: string[] = [];
  const value = body.replace(/\s+/g, ' ').trim();
  let i = 0;
  while (i < value.length) {
    const open = value.indexOf('{', i);
    if (open < 0) break;
    const close = value.indexOf('}', open + 1);
    if (close < 0) break;
    const name = value.slice(open + 1, close).trim();
    if (name && !out.includes(name)) out.push(name);
    i = close + 1;
  }
  return out;
}

/**
 * 正文里的占位符与声明的变量必须互相对得上。
 *
 * <p>与后端 `DescriptionRules.variableBindingProblem` 同一纪律：没声明的占位符
 * 填不出值，会被降级成「缺变量」并报告——而那正是蓝图禁止的静默占位符。
 */
export function variableBindingProblem(
  body: string,
  declared: ({ name: string } | null)[],
): string | null {
  const names = declared
    .filter((item): item is { name: string } => item !== null)
    .map((item) => item.name.trim());
  const placeholders = placeholdersOf(body);
  for (const placeholder of placeholders)
    if (!names.includes(placeholder))
      return `正文里的占位符 {${placeholder}} 没有声明绑定源：请在变量列表里加一条 ${placeholder}`;
  for (const name of names)
    if (!placeholders.includes(name)) return `变量 ${name} 在段落正文里没有出现（死配置）`;
  return null;
}

/** 未配齐五类的段落类型（列表页要显示，这是「模板还不完整」的唯一信号）。 */
export function missingSectionTypes(row: {
  sections: { sectionType: string }[];
}): string[] {
  const present = new Set((row.sections ?? []).map((item) => item.sectionType));
  return SECTION_TYPES.filter((item) => !present.has(item.value)).map((item) => item.label);
}

export function templateSummary(row: DescriptionTemplateRow): string {
  const parts = (row.sections ?? []).map(
    (item) => `${sectionTypeLabel(item.sectionType)}(${item.variableCount}变量/${item.imageSlotCount}图)`,
  );
  return parts.length ? parts.join(' · ') : '还没有段落';
}

// ------------------------------------------------------------ 错误文案

/** 后端 400 的原因直接可读；网络类错误才套一句兜底。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '请求失败，请稍后重试';
}

/**
 * 缺失报告的处置建议。
 *
 * <p>只说「缺了变量」没用——运营不知道该去哪儿补。把每种成因指到一个具体去处。
 */
export function issueFix(issue: { issueKind: string; detail: string }): string {
  switch (issue.issueKind) {
    case 'EMPTY_FACT':
      return '去商品中心把对应字段补上，或把这个变量从段落里去掉';
    case 'EMPTY_PHRASE_POOL':
      return '到「描述与卖点库（3.4.2.2）」建至少一条该类型的短语';
    case 'TRANSLATION_MISSING':
      return '为这一段补一条该语言的译文（占位符必须与源语言完全一致）';
    case 'UNBOUND_PLACEHOLDER':
      return '给这个占位符声明绑定源后重新渲染';
    case 'IMAGE_UNBOUND':
      return '渲染时给这个位号补图，或把这个插图位改成非必填';
    default:
      return issue.detail;
  }
}

export function missingSummary(count: number): string {
  if (count === 0) return '没有缺失，这份 HTML 可以直接上传';
  return `${count} 处没填上 —— 占位符已从 HTML 里剥掉，补齐后重新渲染`;
}

// ------------------------------------------------------------ 展示辅助

/**
 * 把渲染出的 HTML 拆成可读片段。
 *
 * <p>只做两件事：去掉包装标签、把 `<img>` 换成一行文字说明。
 * **刻意不解析、不重排、不美化**——页面上看到的必须与发给平台的字节一致，
 * 否则「页面上没问题」就不能作为「可以上传」的依据。
 */
export function htmlToReadable(html: string): string {
  return html
    .replace(/<h3>/g, '\n【')
    .replace(/<\/h3>/g, '】\n')
    .replace(/<\/p>/g, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

/** 从渲染结果里数一下真的落了多少个 `<img>`（而不是数插图位声明数）。 */
export function countImgTags(html: string): number {
  return (html.match(/<img\b/g) ?? []).length;
}

// ------------------------------------------------------------ 空态与夹具

export function emptyTemplateInput(): DescriptionTemplateInput {
  return {
    code: '',
    name: '',
    note: '',
    sourceLocale: 'zh-CN',
    sections: [emptySectionInput('INTRO')],
    enabled: true,
    version: 0,
  };
}

export function emptySectionInput(sectionType: string): SectionDraft {
  return {
    sectionType,
    body: '',
    variables: [],
    imageSlots: [],
    showHeading: true,
    contentTemplateId: null,
  };
}

export function emptyImageSlot(slotNo: number): ImageSlotDraft {
  return { bindSource: 'RENDER_INPUT', caption: '', assetId: null, required: false, slotNo };
}

export function emptyRenderInput(): {
  templateId: string;
  skuId: string;
  locale: string;
} {
  return { templateId: '', skuId: '', locale: 'zh-CN' };
}

export function templateEmptyHint(): string {
  return '还没有描述模板。描述模板是「产品介绍 / 使用场景 / 规格参数 / 注意事项 / 售后承诺」五段骨架，配好后一次渲染就能产出一整份可上传的描述 HTML。';
}

export function sectionEmptyHint(): string {
  return '这一段还没有正文。正文里可以用 {变量名} 占位，取不到值时会被剥掉并逐条报告，绝不会把 {变量名} 原样发到平台。';
}

export function renderEmptyHint(): string {
  return '还没有渲染记录。选一个模板、一个 SKU、一种语言，点「渲染」就能拿到可直接上传的描述 HTML。';
}

export function renderEmptyHintForStatus(): string {
  return '没有符合筛选条件的渲染记录。';
}

// ------------------------------------------------------------ 接口

export function fetchDescriptionTemplateOverview() {
  return api<DescriptionTemplateOverview>('catalog/description-templates/overview');
}

export function fetchDescriptionTemplateOptions() {
  return api<DescriptionTemplateOptions>('catalog/description-templates/options');
}

export function fetchDescriptionTemplates() {
  return api<DescriptionTemplateRow[]>('catalog/description-templates/templates');
}

export function fetchDescriptionTemplate(id: string) {
  return api<DescriptionTemplateRow>(`catalog/description-templates/templates/${id}`);
}

export function fetchDescriptionSections(templateId: string) {
  return api<DescriptionSectionRow[]>(`catalog/description-templates/templates/${templateId}/sections`);
}

export function saveDescriptionTemplate(id: string | null, input: DescriptionTemplateInput) {
  return id
    ? api<DescriptionTemplateSaved>(`catalog/description-templates/templates/${id}`, 'PUT', input)
    : api<DescriptionTemplateSaved>('catalog/description-templates/templates', 'POST', input);
}

export function setDescriptionTemplateEnabled(id: string, enabled: boolean) {
  return api<DescriptionTemplateSaved>(
    `catalog/description-templates/templates/${id}/enabled?enabled=${enabled}`,
    'PUT',
  );
}

export function deleteDescriptionTemplate(id: string, version: number) {
  return api<{ deleted: boolean }>(
    `catalog/description-templates/templates/${id}?version=${version}`,
    'DELETE',
  );
}

export function renderDescriptionTemplate(input: DescriptionRenderInput) {
  return api<DescriptionRenderProduct>('catalog/description-templates/renders', 'POST', input);
}

export function fetchDescriptionRenders(query: DescriptionRenderQuery) {
  const params = new URLSearchParams();
  if (query.keyword) params.set('keyword', query.keyword);
  if (query.status) params.set('status', query.status);
  if (query.skuId) params.set('skuId', query.skuId);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return api<DescriptionRenderPage>(`catalog/description-templates/renders?${params.toString()}`);
}

export function fetchDescriptionRender(id: string) {
  return api<DescriptionRenderProduct>(`catalog/description-templates/renders/${id}`);
}

/** 复用的类型再导出一次，工作区文件少写一串 import。 */
export type {
  DescriptionImageSlotInput,
  DescriptionRenderPage,
  DescriptionRenderProduct,
  DescriptionRenderRow,
  DescriptionSectionInput,
  DescriptionSectionRow,
  DescriptionTemplateInput,
  DescriptionTemplateOptions,
  DescriptionTemplateOverview,
  DescriptionTemplateRow,
  DescriptionTemplateSaved,
};
