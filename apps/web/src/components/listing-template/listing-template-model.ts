import { api } from '../inventory/api';
import type {
  ApplyListingTemplateRequest,
  ListingTemplateApplyFieldRow,
  ListingTemplateApplyPage,
  ListingTemplateApplyResult,
  ListingTemplateApplyRow,
  ListingTemplateAssetGroupInput,
  ListingTemplateAssetGroupRow,
  ListingTemplateCapabilities,
  ListingTemplateDetail,
  ListingTemplateFieldInput,
  ListingTemplateFieldRow,
  ListingTemplateFilterOptions,
  ListingTemplateMatchPage,
  ListingTemplatePage,
  ListingTemplateRow,
  ListingRow,
  SaveListingTemplateRequest,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 4.2.2.1 刊登模板 前端模型。
 *
 * 蓝图：「沉淀可复用的刊登模板（类目级、店铺级），保存字段填充规则（取自商品属性、引用文案库、套用图片组），一键套用到目标商品。输入：模板
 * 定义（字段规则 + 图片组 + 必填校验）；套用目标。输出：模板记录；套用后的内容草稿。关系：上游 = 03 文案资产、03 媒体库；下游 = 4.2.1.1
 * 编辑器。<b>模板引用的是"取值规则"而非一次性拷贝</b>，商品改价或换图后套用结果跟着变。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **模板里没有值**。编辑面板只有「来源引用 + 取值表达式」，没有「当前值」输入框 ——
 *    页面形状本身就不许把值抄进模板（后端还有 DB 层的标识符约束兜底）。
 * 2. **套用只出草稿**。页面不给「直接上线」入口，套用结果恒显示为草稿。
 * 3. **「不知道」是一个状态**。求值明细里 SOURCE_MISSING / UNKNOWN_SOURCE 单独成态并点名归属模块，
 *    不会被渲染成「空值」或 0。
 * 4. **必填校验是算出来的**。违规清单指到具体字段与具体规则（形如 `PRODUCT_ATTRIBUTE:price`）。
 */

/** 可填字段（与后端 `ListingTemplateModels.FIELDS` 逐字一致）。 */
export const FIELD_OPTIONS: readonly {
  value: string;
  label: string;
  hint: string;
  list?: boolean;
  sources: readonly string[];
}[] = [
  {
    value: 'TITLE',
    label: '标题',
    hint: '平台商品标题（草稿表上限 500 字，另受平台上限约束）',
    sources: ['LISTING_FIELD', 'PRODUCT_ATTRIBUTE', 'CONTENT_TEMPLATE', 'LITERAL'],
  },
  {
    value: 'BULLETS',
    label: '卖点',
    hint: 'bullet points（多值，按行拆）',
    list: true,
    sources: ['LISTING_FIELD', 'PRODUCT_ATTRIBUTE', 'CONTENT_TEMPLATE', 'LITERAL'],
  },
  {
    value: 'IMAGES',
    label: '图片',
    hint: '主图与附图（多值，按行拆）',
    list: true,
    sources: ['LISTING_FIELD', 'IMAGE_GROUP', 'LITERAL'],
  },
  {
    value: 'CATEGORY_PATH',
    label: '类目路径',
    hint: '平台侧类目路径原文',
    sources: ['LISTING_FIELD', 'PRODUCT_ATTRIBUTE', 'LITERAL'],
  },
  {
    value: 'PLATFORM_CATEGORY_ID',
    label: '平台类目 ID',
    hint: '与站点绑定，跨站点不能沿用',
    sources: ['LISTING_FIELD', 'LITERAL'],
  },
];

/** 取值来源（与后端 `ListingTemplateModels.SOURCE_KINDS` 逐字一致）。 */
export const SOURCE_OPTIONS: readonly {
  value: string;
  label: string;
  hint: string;
  needsRef: boolean;
  refHint: string;
}[] = [
  {
    value: 'LISTING_FIELD',
    label: '取自 listing 自身',
    hint: '取目标 listing 当前的标题 / 卖点 / 图片 / 类目',
    needsRef: true,
    refHint: 'listing 字段名（TITLE / BULLETS / IMAGES / CATEGORY_PATH / PLATFORM_CATEGORY_ID）',
  },
  {
    value: 'PRODUCT_ATTRIBUTE',
    label: '取自商品属性',
    hint: '取内部 SKU 的生效属性（03 商品主数据，跨服务只读）',
    needsRef: true,
    refHint: '属性码（如 price、brand）',
  },
  {
    value: 'CONTENT_TEMPLATE',
    label: '引用文案库',
    hint: '引用 03 描述与卖点库里的模板并按商品渲染',
    needsRef: true,
    refHint: '文案模板编码',
  },
  {
    value: 'IMAGE_GROUP',
    label: '套用图片组',
    hint: '套用模板里定义的图片组（指向 03 媒体库）',
    needsRef: true,
    refHint: '图片组编码（本模板内已定义）',
  },
  {
    value: 'LITERAL',
    label: '固定常量',
    hint: '规则里的常量（前后缀 / 固定类目路径）',
    needsRef: false,
    refHint: '',
  },
];

/** 适用范围。 */
export const SCOPE_OPTIONS = [
  { value: 'CATEGORY', label: '类目级', hint: '适用于某个平台类目路径下的全部商品' },
  { value: 'SHOP', label: '店铺级', hint: '适用于某个店铺下的全部商品' },
] as const;

/** 模板状态。 */
export const STATUS_OPTIONS = [
  { value: 'DRAFT', label: '未启用' },
  { value: 'ACTIVE', label: '可套用' },
  { value: 'DISABLED', label: '已停用' },
] as const;

/** 取组方式。 */
export const GROUP_KIND_OPTIONS = [
  { value: 'ASSET_LIST', label: '按素材 id 列表' },
  { value: 'TAG', label: '按媒体库标签' },
] as const;

/** 求值状态。 */
export const EVALUATION_STATUS_OPTIONS = [
  { value: 'RESOLVED', label: '已取到' },
  { value: 'EMPTY', label: '来源为空' },
  { value: 'SOURCE_MISSING', label: '引用不存在' },
  { value: 'UNKNOWN_SOURCE', label: '上游读不到' },
  { value: 'LIMIT_EXCEEDED', label: '超出上限' },
] as const;

export type TemplateDraft = {
  id: string;
  code: string;
  name: string;
  description: string;
  scope: string;
  platformId: string;
  marketplaceId: string;
  categoryPath: string;
  shopId: string;
  language: string;
  status: string;
  fields: ListingTemplateFieldInput[];
  groups: ListingTemplateAssetGroupInput[];
};

export type ApplyDraft = {
  templateId: string;
  listingId: string;
  idempotencyKey: string;
  note: string;
};

export type {
  ApplyListingTemplateRequest,
  ListingRow,
  ListingTemplateApplyFieldRow,
  ListingTemplateApplyPage,
  ListingTemplateApplyResult,
  ListingTemplateApplyRow,
  ListingTemplateAssetGroupRow,
  ListingTemplateCapabilities,
  ListingTemplateDetail,
  ListingTemplateFieldRow,
  ListingTemplateFilterOptions,
  ListingTemplateMatchPage,
  ListingTemplatePage,
  ListingTemplateRow,
  SaveListingTemplateRequest,
};

// ================================================================ 取数

export async function fetchTemplateCapabilities(): Promise<ListingTemplateCapabilities> {
  return api<ListingTemplateCapabilities>('channel/listing-templates/capabilities');
}

export async function fetchTemplateFilterOptions(): Promise<ListingTemplateFilterOptions> {
  return api<ListingTemplateFilterOptions>('channel/listing-templates/filter-options');
}

export type TemplateListFilters = {
  scope?: string;
  platformCode?: string;
  shopCode?: string;
  categoryPath?: string;
  status?: string;
  keyword?: string;
  page?: number;
  size?: number;
};

export async function fetchTemplates(filters: TemplateListFilters = {}): Promise<ListingTemplatePage> {
  const query = new URLSearchParams();
  if (filters.scope) query.set('scope', filters.scope);
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.shopCode) query.set('shopCode', filters.shopCode);
  if (filters.categoryPath) query.set('categoryPath', filters.categoryPath);
  if (filters.status) query.set('status', filters.status);
  if (filters.keyword) query.set('keyword', filters.keyword);
  query.set('page', String(filters.page ?? 0));
  query.set('size', String(filters.size ?? 20));
  return api<ListingTemplatePage>(`channel/listing-templates?${query.toString()}`);
}

export async function fetchTemplate(id: string): Promise<ListingTemplateDetail> {
  return api<ListingTemplateDetail>(`channel/listing-templates/${id}`);
}

export async function saveTemplate(body: SaveListingTemplateRequest): Promise<ListingTemplateDetail> {
  return api<ListingTemplateDetail>('channel/listing-templates', 'POST', body);
}

export async function setTemplateStatus(id: string, status: string): Promise<ListingTemplateDetail> {
  return api<ListingTemplateDetail>(
    `channel/listing-templates/${id}/status?status=${encodeURIComponent(status)}`,
    'PUT',
  );
}

export async function deleteTemplate(id: string): Promise<void> {
  await api<unknown>(`channel/listing-templates/${id}`, 'DELETE');
}

export async function fetchTemplateMatches(
  id: string,
  page = 0,
  size = 20,
): Promise<ListingTemplateMatchPage> {
  return api<ListingTemplateMatchPage>(
    `channel/listing-templates/${id}/matches?page=${page}&size=${size}`,
  );
}

export async function applyTemplate(
  body: ApplyListingTemplateRequest,
): Promise<ListingTemplateApplyResult> {
  return api<ListingTemplateApplyResult>('channel/listing-templates/apply', 'POST', body);
}

export async function fetchTemplateApplies(
  templateId?: string,
  listingId?: string,
  page = 0,
  size = 20,
): Promise<ListingTemplateApplyPage> {
  const query = new URLSearchParams();
  if (templateId) query.set('templateId', templateId);
  if (listingId) query.set('listingId', listingId);
  query.set('page', String(page));
  query.set('size', String(size));
  return api<ListingTemplateApplyPage>(`channel/listing-templates/applies?${query.toString()}`);
}

export async function fetchTemplateApply(applyId: string): Promise<ListingTemplateApplyResult> {
  return api<ListingTemplateApplyResult>(`channel/listing-templates/applies/${applyId}`);
}

/** 套用目标候选（走 4.1.1.1 的既有列表端点：真分页 + 真搜索）。 */
export async function fetchListingCandidates(
  search: string,
): Promise<{ id: string; externalItemId: string; title: string; shopCode: string; platformCode: string }[]> {
  const query = new URLSearchParams({ page: '0', size: '20' });
  if (search.trim()) query.set('search', search.trim());
  const page = await api<{ items: ListingRow[] }>(`channel/listings?${query.toString()}`);
  return page.items;
}

// ================================================================ 纯函数（可单测）

/** 空模板草稿。 */
export function emptyTemplateDraft(): TemplateDraft {
  return {
    id: '',
    code: '',
    name: '',
    description: '',
    scope: 'CATEGORY',
    platformId: '',
    marketplaceId: '',
    categoryPath: '',
    shopId: '',
    language: 'en-US',
    status: 'DRAFT',
    fields: [
      { field: 'TITLE', sourceKind: 'LISTING_FIELD', sourceRef: 'TITLE', valueExpression: '', required: false, slotNo: 0 },
    ],
    groups: [],
  };
}

/** 模板详情 → 编辑草稿。 */
export function toTemplateDraft(detail: ListingTemplateDetail): TemplateDraft {
  const t = detail.template;
  return {
    id: t.id,
    code: t.code,
    name: t.name,
    description: t.description,
    scope: t.scope,
    platformId: t.platformId,
    marketplaceId: t.marketplaceId ?? '',
    categoryPath: t.scope === 'CATEGORY' ? t.scopeTarget : '',
    shopId: '',
    language: t.language,
    status: t.status,
    fields: detail.fields.map((f) => ({
      field: f.field,
      sourceKind: f.sourceKind,
      sourceRef: f.sourceRef,
      valueExpression: f.valueExpression,
      required: f.required,
      slotNo: f.slotNo,
    })),
    groups: detail.assetGroups.map((g) => ({
      groupCode: g.groupCode,
      groupKind: g.groupKind,
      tagCode: g.tagCode ?? '',
      assetIds: g.assetIds,
      maxCount: g.maxCount,
      slotNo: g.slotNo,
    })),
  };
}

/**
 * 🔴 草稿 → 服务端载荷。
 *
 * 三件事在这里被挡住：
 *  * 两种适用范围**互斥**（给了类目路径就不能再给店铺，反之亦然）；
 *  * 至少要有一条字段规则（没有规则的模板套不出任何东西）；
 *  * LITERAL 必须给常量，其余来源必须给引用；来源必须与字段匹配。
 */
export function buildTemplateRequest(draft: TemplateDraft): SaveListingTemplateRequest | null {
  if (!draft.code.trim()) return null;
  if (!draft.name.trim()) return null;
  if (!draft.platformId) return null;
  if (draft.scope === 'CATEGORY') {
    if (!draft.categoryPath.trim()) return null;
    if (draft.shopId) return null;
  } else {
    if (!draft.shopId) return null;
    if (draft.categoryPath.trim()) return null;
  }
  if (draft.fields.length === 0) return null;

  const fields: ListingTemplateFieldInput[] = [];
  for (const field of draft.fields) {
    if (!field.sourceKind) return null;
    if (field.sourceKind === 'LITERAL') {
      if (!field.valueExpression.trim()) return null;
    } else if (!field.sourceRef.trim()) {
      return null;
    }
    if (!sourcesAllowedFor(field.field, field.sourceKind)) return null;
    fields.push({
      field: field.field,
      sourceKind: field.sourceKind,
      sourceRef: field.sourceKind === 'LITERAL' ? '' : field.sourceRef.trim(),
      valueExpression: field.valueExpression,
      required: field.required,
      slotNo: field.slotNo,
    });
  }

  const groups: ListingTemplateAssetGroupInput[] = [];
  for (const group of draft.groups) {
    if (!group.groupCode.trim()) return null;
    if (group.groupKind === 'TAG') {
      if (!group.tagCode.trim()) return null;
    } else if (!group.assetIds || group.assetIds.length === 0) {
      return null;
    }
    groups.push({
      groupCode: group.groupCode.trim(),
      groupKind: group.groupKind,
      tagCode: group.groupKind === 'TAG' ? group.tagCode.trim() : '',
      assetIds: group.groupKind === 'TAG' ? [] : group.assetIds,
      maxCount: group.maxCount,
      slotNo: group.slotNo,
    });
  }

  return {
    id: draft.id,
    code: draft.code.trim(),
    name: draft.name.trim(),
    description: draft.description,
    scope: draft.scope,
    platformId: draft.platformId,
    marketplaceId: draft.marketplaceId,
    categoryPath: draft.scope === 'CATEGORY' ? draft.categoryPath.trim() : '',
    shopId: draft.scope === 'SHOP' ? draft.shopId : '',
    language: draft.language.trim() || 'en-US',
    status: draft.status,
    fields,
    assetGroups: groups,
  };
}

/** 字段与来源的合法组合（与后端逐字一致）。 */
export function sourcesAllowedFor(field: string, sourceKind: string): boolean {
  const option = FIELD_OPTIONS.find((o) => o.value === field);
  if (!option) return false;
  return option.sources.includes(sourceKind);
}

/** 套用草稿 → 服务端载荷。 */
export function buildApplyRequest(draft: ApplyDraft): ApplyListingTemplateRequest | null {
  if (!draft.templateId) return null;
  if (!draft.listingId) return null;
  return {
    templateId: draft.templateId,
    listingId: draft.listingId,
    idempotencyKey: draft.idempotencyKey.trim(),
    note: draft.note,
  };
}

/** 适用范围落点的文案。 */
export function scopeTargetLabel(template: ListingTemplateRow): string {
  if (template.scope === 'CATEGORY') return `类目路径 ${template.scopeTarget}`;
  return `店铺 ${template.scopeTarget}`;
}

/** 模板状态文案。 */
export function statusLabel(status: string): string {
  return STATUS_OPTIONS.find((s) => s.value === status)?.label ?? status;
}

export function statusTone(status: string): 'neutral' | 'success' | 'warning' {
  if (status === 'ACTIVE') return 'success';
  if (status === 'DISABLED') return 'warning';
  return 'neutral';
}

/** 求值状态文案。🔴 「不知道」两态各有各的说法，绝不合并成「空」。 */
export function evaluationStatusLabel(status: string): string {
  return EVALUATION_STATUS_OPTIONS.find((s) => s.value === status)?.label ?? status;
}

export function evaluationTone(status: string): 'neutral' | 'success' | 'warning' | 'danger' {
  switch (status) {
    case 'RESOLVED':
      return 'success';
    case 'EMPTY':
    case 'LIMIT_EXCEEDED':
      return 'warning';
    case 'SOURCE_MISSING':
    case 'UNKNOWN_SOURCE':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 来源文案。 */
export function sourceKindLabel(sourceKind: string): string {
  return SOURCE_OPTIONS.find((s) => s.value === sourceKind)?.label ?? sourceKind;
}

/** 求值结果的值：多值字段是 JSON 数组文本，拆成行展示。 */
export function valueLines(field: string, value: string | null): string[] {
  if (value === null || value === '') return [];
  if (field !== 'BULLETS' && field !== 'IMAGES') return [value];
  try {
    const parsed: unknown = JSON.parse(value);
    if (Array.isArray(parsed)) return parsed.map((v) => String(v));
  } catch {
    // 不是 JSON 数组就按原文展示（后端存的是 JSON 数组文本，这里只是兜底）
  }
  return value.split('\n');
}

/** 违规清单的汇总文案。 */
export function validationSummary(result: ListingTemplateApplyResult): string {
  if (result.validationStatus === 'PASS') {
    return `必填校验通过：${result.appliedCount} 个字段已取到值`;
  }
  const unknown = result.violations.filter((v) => v.status === 'UNKNOWN_SOURCE').length;
  const missing = result.violations.filter((v) => v.status === 'SOURCE_MISSING').length;
  const parts = [`${result.violationCount} 个必填字段没通过`];
  if (unknown > 0) parts.push(`其中 ${unknown} 个是上游读不到`);
  if (missing > 0) parts.push(`${missing} 个是引用不存在`);
  return parts.join('，');
}

/** 未接入的维度（编辑器入口 / 发布 / 描述正文字段）。 */
export function unavailableDimensions(caps: ListingTemplateCapabilities) {
  return caps.dimensions.filter((d) => !d.available);
}

/** 空态文案。 */
export function emptyTemplatesMessage(): string {
  return '还没有刊登模板。新建一个模板：选作用域（类目级 / 店铺级）、配字段填充规则与必填校验，就能一键套用到目标商品。';
}

/** 模板规则摘要（列表里显示「这个模板管哪几个字段」）。 */
export function ruleSummary(detail: ListingTemplateDetail): string {
  const required = detail.fields.filter((f) => f.required).length;
  return `${detail.fields.length} 条规则（${required} 条必填）· ${detail.assetGroups.length} 个图片组`;
}

/** 套用记录里「幂等重放」的提示。 */
export function replayNote(result: ListingTemplateApplyResult): string {
  return result.idempotentReplay
    ? '这次请求被幂等键挡下，返回的是第一次套用的结果（没有重新求值，也没有再写草稿）。'
    : '';
}
