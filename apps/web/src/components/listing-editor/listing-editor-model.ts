import { api } from '../inventory/api';
import type {
  EditorCapabilities,
  EditorDraftRequest,
  EditorDraftRow,
  EditorFieldSchema,
  EditorFieldValue,
  EditorFormSchema,
  EditorMediaAsset,
  EditorSourceProduct,
  EditorSubmissionRow,
  EditorValidation,
  ListingPage,
  ListingRow,
  ShopView,
  SkuPage,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 4.2.1.1 刊登编辑器 前端模型。
 *
 * 蓝图：「单商品的平台内容编辑表单：按目标平台动态渲染字段（标题、要点、描述、变体维度、图片集、视频、A+
 * 模块、类目属性、合规声明），左侧编辑右侧实时预览，支持多语言 Tab、保存草稿、提交审核。输入：源商品（03
 * SKU/SPU）、目标店铺与站点、平台字段要求（02 能力矩阵）；用户编辑内容；已填内容的草稿。输出：内容草稿（按平台 +
 * 站点 + 语言分别保存）；提交发布或保存模板的动作；校验结果。关系：上游 = 03 商品主数据、03 媒体与文案、02 平台字段要求；下游 =
 * 4.3.1.1 发布。<b>表单字段由平台要求驱动生成</b>，不是固定一套表单套所有平台——平台能力矩阵变化时表单自动变化。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **表单字段不在前端写死**。字段集、必填性、长度 / 条数上限全部来自
 *    {@link EditorFormSchema}（后端从平台要求推导）。前端**不自己判断必填** ——
 *    `requirement` 说 REQUIRED 才是必填，`constraintState` 说 UNKNOWN 就显示"上限未知"，
 *    说 DELEGATED 就显示"归 4.2.3.1 管"，**不假装知道**。
 * 2. **草稿态一眼可见**。保存草稿 / 提交审核都不改线上，页面上的措辞是"已入队"不是"已发布"。
 * 3. **提交审核只是入队**。4.3.1.1 一键刊登未交付，页面上写"已入队，等 4.3.1.1 派发"。
 * 4. **多语言 Tab 真存多语言**。每个语言一份草稿（后端按 locale 存），改一个语言不动另一个。
 * 5. **校验结果指到具体字段**。违规项带字段键，点一下就跳到那一栏并标红。
 */

/** 编辑器的字段值与契约类型（与后端 EditorFieldValue 同构）。 */
export type {
  EditorCapabilities,
  EditorDraftRequest,
  EditorDraftRow,
  EditorFieldSchema,
  EditorFieldValue,
  EditorFormSchema,
  EditorMediaAsset,
  EditorSourceProduct,
  EditorSubmissionRow,
  EditorValidation,
  ListingRow,
  ShopView,
};

/** 表单里的一栏在本地编辑态的形状。 */
export interface FieldDraft {
  text: string;
  items: string[];
  modules: { type: string; heading: string; body: string; imageRef: string }[];
  pairs: { key: string; value: string }[];
}

export type FieldDrafts = Record<string, FieldDraft>;

export interface EditorTarget {
  shopId: string;
  marketplaceId: string;
  locale: string;
  listingId: string;
  sourceSkuId: string;
  categoryPath: string;
}

/** 空白的一栏。 */
export function emptyFieldDraft(): FieldDraft {
  return { text: '', items: [], modules: [], pairs: [] };
}

/** 后端的值 → 本地编辑态。 */
export function toFieldDrafts(values: EditorFieldValue[] | undefined): FieldDrafts {
  const result: FieldDrafts = {};
  for (const value of values ?? []) {
    result[value.field] = {
      text: value.text ?? '',
      items: (value.items ?? []).map((item) => item ?? ''),
      modules: (value.modules ?? []).map((m) => ({
        type: m?.type ?? '',
        heading: m?.heading ?? '',
        body: m?.body ?? '',
        imageRef: m?.imageRef ?? '',
      })),
      pairs: (value.pairs ?? []).map((p) => ({ key: p?.key ?? '', value: p?.value ?? '' })),
    };
  }
  return result;
}

/** 本地编辑态 → 后端的值（只发 schema 里登记了的栏）。 */
export function toFieldValues(
  drafts: FieldDrafts,
  schema: EditorFieldSchema[] | undefined,
): EditorFieldValue[] {
  const allowed = new Set((schema ?? []).map((f) => f.field));
  return Object.entries(drafts)
    .filter(([field]) => allowed.has(field))
    .map(([field, draft]) => ({
      field,
      text: draft.text,
      items: draft.items,
      modules: draft.modules,
      pairs: draft.pairs,
    }));
}

/** 一栏的字符数（列表取最长项）——用来对照平台长度上限。 */
export function fieldLength(draft: FieldDraft | undefined): number {
  if (!draft) return 0;
  if (draft.text) return [...draft.text].length;
  if (draft.items.length) return Math.max(...draft.items.map((item) => [...item].length));
  if (draft.pairs.length) return Math.max(...draft.pairs.map((p) => [...(p.value ?? '')].length));
  return 0;
}

/** 一栏的条数。 */
export function fieldItemCount(draft: FieldDraft | undefined): number {
  if (!draft) return 0;
  if (draft.items.length) return draft.items.length;
  if (draft.modules.length) return draft.modules.length;
  if (draft.pairs.length) return draft.pairs.length;
  return draft.text ? 1 : 0;
}

/** 必填徽章的语义色。 */
export function requirementTone(requirement: string): 'danger' | 'warning' | 'neutral' {
  if (requirement === 'REQUIRED') return 'danger';
  if (requirement === 'RECOMMENDED') return 'warning';
  return 'neutral';
}

/** 必填徽章的中文。 */
export function requirementLabel(requirement: string): string {
  if (requirement === 'REQUIRED') return '必填';
  if (requirement === 'RECOMMENDED') return '建议填';
  if (requirement === 'UNSUPPORTED') return '平台不支持';
  return '选填';
}

/**
 * 🔴 约束 / 能力状态的界面措辞。
 *
 * <p>「不知道」在这里是**一个状态**，不是"没有限制"：约束未知就写"上限未知"，
 * 细则在别的模块就写"归某某模块管"。
 */
export function constraintNote(schema: EditorFieldSchema): string {
  if (schema.constraintState === 'DELEGATED') {
    return `细则归 ${schema.ownerModule ?? '上游模块'} 管，本页不代它裁决`;
  }
  if (schema.constraintState === 'UNKNOWN') {
    return `长度 / 条数上限未知（归 ${schema.ownerModule ?? '上游模块'}）`;
  }
  if (schema.capabilityState === 'UNKNOWN') {
    return `平台是否支持这一栏未知（归 ${schema.ownerModule ?? '上游模块'}）`;
  }
  if (schema.maxLength != null) return `上限 ${schema.maxLength} 字`;
  if (schema.maxItems != null) return `上限 ${schema.maxItems} 条`;
  return '';
}

/** 平台要求整体状态的界面措辞。 */
export function requirementsNote(schema: EditorFormSchema): string {
  if (schema.requirementsState === 'UNKNOWN') {
    return `该平台的字段要求取不到（归 ${schema.requirementsOwnerModule ?? '上游模块'}），下面是空表单而不是"没有要填的字段"`;
  }
  if (schema.requirementsState === 'PARTIAL') {
    return '部分细则不在本页（见每栏标注），已知的部分照常生效';
  }
  return '字段集与上限都来自平台要求，改平台要求表单自动跟着变';
}

/** 草稿状态的徽章。 */
export function draftBadge(state: string): {
  label: string;
  tone: 'neutral' | 'info' | 'success' | 'warning';
} {
  if (state === 'SUBMITTED') return { label: '已入队 · 等 4.3.1.1 派发', tone: 'info' };
  if (state === 'DISCARDED') return { label: '已废弃', tone: 'warning' };
  return { label: '草稿 · 未上线', tone: 'neutral' };
}

/** 入队状态的徽章。 */
export function submissionBadge(state: string): {
  label: string;
  tone: 'neutral' | 'info' | 'success' | 'warning' | 'danger';
} {
  if (state === 'QUEUED') return { label: '已入队（未发布）', tone: 'info' };
  if (state === 'DISPATCHED') return { label: '已派发', tone: 'success' };
  if (state === 'FAILED') return { label: '派发失败', tone: 'danger' };
  if (state === 'CANCELLED') return { label: '已撤销（内容已改）', tone: 'warning' };
  return { label: state, tone: 'neutral' };
}

/** 校验违规的严重度语义色。 */
export function violationTone(severity: string): 'danger' | 'warning' {
  return severity === 'BLOCKING' ? 'danger' : 'warning';
}

/** 目标店铺的可选站点（只给启用的）。 */
export function siteOptions(shop: ShopView | null): ShopView['marketplaces'] {
  if (!shop) return [];
  return (shop.marketplaces ?? []).filter((m) => m.status === 'ACTIVE');
}

/** 一个语言页签是否已经有草稿。 */
export function localeHasDraft(locale: string, drafts: EditorDraftRow[]): boolean {
  return drafts.some((d) => d.locale === locale && d.state !== 'DISCARDED');
}

/** 幂等键：草稿 id + 内容版本。内容没变时重复提交不会产生第二条入队记录。 */
export function idempotencyKey(draftId: string, version: number): string {
  return `${draftId}:${version}`;
}

// ================================================================ 取数

export async function fetchEditorCapabilities(): Promise<EditorCapabilities> {
  return api<EditorCapabilities>('channel/listing-editor/capabilities');
}

export async function fetchFormSchema(
  shopId: string,
  marketplaceId: string,
  categoryPath: string,
  locale: string,
): Promise<EditorFormSchema> {
  const query = new URLSearchParams({ shopId, marketplaceId });
  if (categoryPath) query.set('categoryPath', categoryPath);
  if (locale) query.set('locale', locale);
  return api<EditorFormSchema>(`channel/listing-editor/form-schema?${query.toString()}`);
}

export async function fetchEditorDrafts(
  shopId?: string,
  locale?: string,
): Promise<EditorDraftRow[]> {
  const query = new URLSearchParams({ page: '0', size: '50' });
  if (shopId) query.set('shopId', shopId);
  if (locale) query.set('locale', locale);
  return api<EditorDraftRow[]>(`channel/listing-editor/drafts?${query.toString()}`);
}

export async function fetchEditorDraft(draftId: string): Promise<EditorDraftRow> {
  return api<EditorDraftRow>(`channel/listing-editor/drafts/${draftId}`);
}

export async function saveEditorDraft(body: EditorDraftRequest): Promise<EditorDraftRow> {
  return api<EditorDraftRow>('channel/listing-editor/drafts', 'POST', body);
}

export async function validateEditorContent(body: {
  shopId: string;
  marketplaceId: string;
  locale: string;
  categoryPath: string;
  fieldValues: EditorFieldValue[];
}): Promise<EditorValidation> {
  return api<EditorValidation>('channel/listing-editor/validate', 'POST', body);
}

export async function submitEditorDraft(
  draftId: string,
  idempotencyKey: string,
): Promise<EditorSubmissionRow> {
  return api<EditorSubmissionRow>(`channel/listing-editor/drafts/${draftId}/submit`, 'POST', {
    idempotencyKey,
  });
}

export async function fetchEditorSubmissions(): Promise<EditorSubmissionRow[]> {
  return api<EditorSubmissionRow[]>('channel/listing-editor/submissions?page=0&size=50');
}

export async function fetchSourceProduct(skuId: string): Promise<EditorSourceProduct> {
  return api<EditorSourceProduct>(
    `channel/listing-editor/source-product?skuId=${encodeURIComponent(skuId)}`,
  );
}

export async function fetchMediaAssets(keyword?: string): Promise<EditorMediaAsset[]> {
  const query = new URLSearchParams();
  if (keyword) query.set('keyword', keyword);
  const suffix = query.toString();
  return api<EditorMediaAsset[]>(
    `channel/listing-editor/media-assets${suffix ? `?${suffix}` : ''}`,
  );
}

/** 源商品候选（03 SKU 列表页，真分页 + 搜索）。 */
export async function fetchSkuCandidates(search: string): Promise<SkuPage> {
  const query = new URLSearchParams({ page: '0', size: '20' });
  if (search.trim()) query.set('q', search.trim());
  return api<SkuPage>(`catalog/skus?${query.toString()}`);
}

/** 线上商品候选（4.1.1.1 列表页，按店铺筛）。 */
export async function fetchListingCandidates(
  shopId: string,
  search: string,
): Promise<ListingRow[]> {
  const query = new URLSearchParams({ page: '0', size: '20' });
  if (shopId) query.set('shopId', shopId);
  if (search.trim()) query.set('search', search.trim());
  const page = await api<ListingPage>(`channel/listings?${query.toString()}`);
  return page.items;
}

/** 目标店铺候选（02 店铺列表）。 */
export async function fetchShops(): Promise<ShopView[]> {
  return api<ShopView[]>('channel/shops');
}
