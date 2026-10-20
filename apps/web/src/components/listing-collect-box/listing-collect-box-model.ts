import { api } from '../inventory/api';
import type {
  ClaimBoxItemsRequest,
  CreateBoxTagRequest,
  CreateClaimRuleRequest,
  EnrollBoxItemsRequest,
  ListingCollectBoxCandidatePage,
  ListingCollectBoxCandidateRow,
  ListingCollectBoxCapabilities,
  ListingCollectBoxClaimOutcome,
  ListingCollectBoxClaimResult,
  ListingCollectBoxClaimRulePage,
  ListingCollectBoxClaimRuleRow,
  ListingCollectBoxEnrollResult,
  ListingCollectBoxGroupRow,
  ListingCollectBoxGroups,
  ListingCollectBoxItemRow,
  ListingCollectBoxKeyValue,
  ListingCollectBoxPage,
  ListingCollectBoxRemoveResult,
  ListingCollectBoxTagOutcome,
  ListingCollectBoxTagResult,
  ListingCollectBoxTagRow,
  RemoveBoxItemsRequest,
  TagBoxItemsRequest,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 4.4.1.3 采集箱 前端模型。
 *
 * 蓝图：「采集结果的暂存区，按平台/来源/标签分组，支持勾选后批量认领为内部商品（生成 SPU/SKU 草稿并预填内容）、批量删除、批量打标。输入：勾选操作；认领规则（目标分类、品牌映射、命名规则）。输出：认领后的商品草稿；采集箱状态更新。关系：上游 = 4.4.1.1/4.4.1.2；下游 = 3.1.1.1 商品建档、4.2.1.1 编辑器。<b>认领时必须做品牌与分类映射</b>，否则采集来的商品归属混乱，后续无法按品牌做合规与绩效分析。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **认领只产出草稿**。页面不出现「建档完成」「已入库商品」这类字样，认领结果面板写明
 *    「待 03 建档」——后端给的 `buildNote` 原文照登。
 * 2. **品牌与分类映射是硬要求**。认领规则表单里，品牌映射是**必填**；页面上明写
 *    「没有品牌映射就不能认领」，并把服务端逐条返回的失败原因原文显示出来。
 * 3. **删除的语义要说清**。移出采集箱 ≠ 删掉草稿：上游草稿被批量采集结果清单引用时会被保留，
 *    页面把服务端写的 `reason` 原文显示出来。
 * 4. **图片只透传地址**。图片区显示的是外部图片地址，并明写「未下载入媒体库」。
 */

/** 采集箱条目状态的取值域（与后端 `ListingCollectBoxModels.ITEM_STATES` 一致）。 */
export const BOX_ITEM_STATES = ['PENDING', 'CLAIMED'] as const;

export type BoxItemState = (typeof BOX_ITEM_STATES)[number];

/** 条目来源取值域（与后端 `ListingCollectBoxModels.ORIGINS` 一致）。 */
export const BOX_ORIGINS = ['LINK', 'BULK', 'PLUGIN'] as const;

export type BoxOrigin = (typeof BOX_ORIGINS)[number];

/** 命名规则支持的占位符（与后端 `ListingCollectBoxModels.NAMING_PLACEHOLDERS` 一致）。 */
export const BOX_NAMING_PLACEHOLDERS = [
  '{brand}',
  '{title}',
  '{model}',
  '{category}',
  '{source}',
] as const;

export type {
  ListingCollectBoxCandidatePage,
  ListingCollectBoxCandidateRow,
  ListingCollectBoxCapabilities,
  ListingCollectBoxClaimOutcome,
  ListingCollectBoxClaimResult,
  ListingCollectBoxClaimRulePage,
  ListingCollectBoxClaimRuleRow,
  ListingCollectBoxEnrollResult,
  ListingCollectBoxGroupRow,
  ListingCollectBoxGroups,
  ListingCollectBoxItemRow,
  ListingCollectBoxKeyValue,
  ListingCollectBoxPage,
  ListingCollectBoxRemoveResult,
  ListingCollectBoxTagOutcome,
  ListingCollectBoxTagResult,
  ListingCollectBoxTagRow,
};

// ================================================================ 取数

export async function fetchBoxCapabilities(): Promise<ListingCollectBoxCapabilities> {
  return api<ListingCollectBoxCapabilities>('channel/listing-collect-box/capabilities');
}

export async function fetchBoxItems(
  sourceCode?: string,
  tagId?: string,
  state?: string,
  q?: string,
  page = 0,
  size = 50,
): Promise<ListingCollectBoxPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (sourceCode) query.set('sourceCode', sourceCode);
  if (tagId) query.set('tagId', tagId);
  if (state) query.set('state', state);
  if (q) query.set('q', q);
  return api<ListingCollectBoxPage>(`channel/listing-collect-box/items?${query.toString()}`);
}

export async function fetchBoxItem(itemId: string): Promise<ListingCollectBoxItemRow> {
  return api<ListingCollectBoxItemRow>(`channel/listing-collect-box/items/${itemId}`);
}

export async function fetchBoxGroups(): Promise<ListingCollectBoxGroups> {
  return api<ListingCollectBoxGroups>('channel/listing-collect-box/groups');
}

export async function fetchBoxCandidates(
  sourceCode?: string,
  q?: string,
  page = 0,
  size = 50,
): Promise<ListingCollectBoxCandidatePage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (sourceCode) query.set('sourceCode', sourceCode);
  if (q) query.set('q', q);
  return api<ListingCollectBoxCandidatePage>(
    `channel/listing-collect-box/candidates?${query.toString()}`,
  );
}

export async function fetchBoxTags(): Promise<ListingCollectBoxTagRow[]> {
  return api<ListingCollectBoxTagRow[]>('channel/listing-collect-box/tags');
}

export async function fetchBoxRules(page = 0, size = 50): Promise<ListingCollectBoxClaimRulePage> {
  return api<ListingCollectBoxClaimRulePage>(
    `channel/listing-collect-box/rules?page=${page}&size=${size}`,
  );
}

export async function enrollBoxItems(draftIds: string[]): Promise<ListingCollectBoxEnrollResult> {
  const body: EnrollBoxItemsRequest = { draftIds };
  return api<ListingCollectBoxEnrollResult>('channel/listing-collect-box/items', 'POST', body);
}

export async function createBoxTag(name: string, color = ''): Promise<ListingCollectBoxTagRow> {
  const body: CreateBoxTagRequest = { name, color };
  return api<ListingCollectBoxTagRow>('channel/listing-collect-box/tags', 'POST', body);
}

export async function deleteBoxTag(tagId: string): Promise<void> {
  await api<void>(`channel/listing-collect-box/tags/${tagId}`, 'DELETE');
}

export async function tagBoxItems(
  itemIds: string[],
  tagId: string,
  action: 'ADD' | 'REMOVE',
): Promise<ListingCollectBoxTagResult> {
  const body: TagBoxItemsRequest = { itemIds, tagId, action };
  return api<ListingCollectBoxTagResult>('channel/listing-collect-box/items/tags', 'POST', body);
}

export async function removeBoxItems(itemIds: string[]): Promise<ListingCollectBoxRemoveResult> {
  const body: RemoveBoxItemsRequest = { itemIds };
  return api<ListingCollectBoxRemoveResult>(
    'channel/listing-collect-box/items/remove',
    'POST',
    body,
  );
}

export async function createBoxRule(
  request: Omit<CreateClaimRuleRequest, 'categoryMapping' | 'brandMapping'> & {
    categoryMapping: ListingCollectBoxKeyValue[];
    brandMapping: ListingCollectBoxKeyValue[];
  },
): Promise<ListingCollectBoxClaimRuleRow> {
  return api<ListingCollectBoxClaimRuleRow>('channel/listing-collect-box/rules', 'POST', request);
}

export async function deleteBoxRule(ruleId: string): Promise<void> {
  await api<void>(`channel/listing-collect-box/rules/${ruleId}`, 'DELETE');
}

export async function claimBoxItems(
  itemIds: string[],
  ruleId: string,
): Promise<ListingCollectBoxClaimResult> {
  const body: ClaimBoxItemsRequest = { itemIds, ruleId };
  return api<ListingCollectBoxClaimResult>('channel/listing-collect-box/items/claim', 'POST', body);
}

// ================================================================ 纯函数（可单测）

/** 条目状态文案。 */
export function itemStateLabel(state: string): string {
  switch (state) {
    case 'PENDING':
      return '待认领';
    case 'CLAIMED':
      return '已认领';
    default:
      return state;
  }
}

export function itemStateTone(state: string): 'success' | 'warning' | 'neutral' {
  if (state === 'CLAIMED') return 'success';
  if (state === 'PENDING') return 'warning';
  return 'neutral';
}

/** 条目来源文案。 */
export function originLabel(origin: string): string {
  switch (origin) {
    case 'LINK':
      return '链接采集';
    case 'BULK':
      return '批量采集';
    case 'PLUGIN':
      return '插件上报';
    default:
      return origin;
  }
}

/**
 * 🔴 外部页面原文标价的界面措辞。
 *
 * <p>**不是我们的售价** —— 售价归 05 定价（整卷未交付）。页面必须写清这一点，
 * 否则运营会把来源站的报价当成自己的定价直接用。
 */
export function sourcePriceLabel(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '未抓到';
  const value = String(amount);
  return currency ? `${value} ${currency}` : value;
}

/** 抓取时刻的界面文案。 */
export function capturedAtLabel(capturedAt: string | null): string {
  if (!capturedAt) return '—';
  const date = new Date(capturedAt);
  if (Number.isNaN(date.getTime())) return capturedAt;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** 键值项列表 → 渲染用的行（过滤掉空键与空值）。 */
export function mappingRows(items: ListingCollectBoxKeyValue[] | null | undefined) {
  return (items ?? [])
    .filter((item) => item && item.name && item.value)
    .map((item) => ({ name: item.name, value: item.value }));
}

/**
 * 🔴 认领规则表单里的映射文本 → 键值项列表。
 *
 * <p>一行一个 {@code 源 = 目标}。空行与没有 {@code =} 的行被丢掉（不是静默吞掉 ——
 * {@link validateMappingText} 会把问题指出来）。
 */
export function parseMappingText(text: string): ListingCollectBoxKeyValue[] {
  const out: ListingCollectBoxKeyValue[] = [];
  for (const raw of (text ?? '').split('\n')) {
    const line = raw.trim();
    if (!line) continue;
    const at = line.indexOf('=');
    if (at <= 0) continue;
    const name = line.slice(0, at).trim();
    const value = line.slice(at + 1).trim();
    if (!name || !value) continue;
    out.push({ name, value });
  }
  return out;
}

/**
 * 🔴 映射文本的客户端预检。
 *
 * <p>只挡**明显不合法**的输入，不代替服务端的判定 —— 服务端才是权威（它会丢掉空键空值、
 * 并在映射为空时直接 400）。这个预检只是让运营在提交前就看见问题。
 */
export function validateMappingText(text: string, label: string): string | null {
  const lines = (text ?? '')
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l.length > 0);
  if (lines.length === 0) return `请填写${label}（一行一个「源 = 目标」）`;
  for (const line of lines) {
    const at = line.indexOf('=');
    if (at < 0) return `${label}里有一行没有「=」：${line}`;
    if (at === 0) return `${label}里有一行左边是空的：${line}`;
    if (!line.slice(at + 1).trim()) return `${label}里有一行右边是空的：${line}`;
  }
  return null;
}

/**
 * 🔴 命名规则的预览。
 *
 * <p>把占位符换成示例值，让运营在保存规则前就看到「认领出来的商品会叫什么名字」——
 * 命名规则配错了，商品会带着错的名字进商品库。
 */
export function previewNaming(
  template: string,
  values: { brand?: string; title?: string; model?: string; category?: string; source?: string },
): string {
  const out = (template ?? '').trim();
  if (!out) return '';
  return out
    .replace('{brand}', values.brand ?? 'BRAND')
    .replace('{title}', values.title ?? '商品标题')
    .replace('{model}', values.model ?? '型号')
    .replace('{category}', values.category ?? 'CAT')
    .replace('{source}', values.source ?? '来源平台')
    .replace(/\s+/g, ' ')
    .trim();
}

/** 命名规则里至少要有一个占位符（否则不管采来什么商品都是同一个名字）。 */
export function validateNamingTemplate(template: string): string | null {
  const value = (template ?? '').trim();
  if (!value) return '请填写命名规则模板';
  if (!BOX_NAMING_PLACEHOLDERS.some((p) => value.includes(p))) {
    return `命名规则里至少要有一个占位符（${BOX_NAMING_PLACEHOLDERS.join(' ')}）`;
  }
  return null;
}

/** 认领结果的一句话汇总。 */
export function claimSummary(result: ListingCollectBoxClaimResult | null): string {
  if (!result) return '';
  return `共 ${result.total} 条：成功 ${result.succeeded} 条，失败 ${result.failed} 条。`;
}

/** 认领失败的那几条（原因原文照登）。 */
export function failedClaimOutcomes(
  result: ListingCollectBoxClaimResult | null,
): ListingCollectBoxClaimOutcome[] {
  if (!result) return [];
  return (result.outcomes ?? []).filter((o) => !o.ok);
}

/** 删除结果里「草稿被保留」的那几条（原文照登）。 */
export function retainedDraftOutcomes(result: ListingCollectBoxRemoveResult | null) {
  if (!result) return [];
  return (result.outcomes ?? []).filter((o) => o.ok && !o.draftRemoved);
}

/** 未交付的维度（媒体库 / 商品草稿落库 / 售价 / 库存）。 */
export function unavailableDimensions(caps: ListingCollectBoxCapabilities | null) {
  if (!caps) return [];
  return caps.dimensions.filter((d) => !d.available);
}

/** 空态文案：箱子是空的。 */
export function emptyBoxMessage(): string {
  return '采集箱还是空的。在上面的「待收录的采集草稿」里勾选几条，点「收录进采集箱」就能开始处理。';
}

/** 空态文案：连待收录的草稿都没有。 */
export function emptyCandidatesMessage(): string {
  return '没有待收录的采集草稿。先去 4.4.1.1 链接采集或 4.4.1.2 批量采集抓几个商品回来。';
}

/** 空态文案：一条认领规则都没有。 */
export function emptyRulesMessage(): string {
  return '还没有认领规则。认领规则要同时给「目标分类（或分类映射）」「品牌映射」「命名规则」—— 没有品牌映射就不能认领。';
}

/** 🔴 图片区的说明：只记 URL，没下载。 */
export const IMAGE_URL_ONLY_NOTE =
  '🔴 图片**只记录外部地址**（未下载二进制、未入媒体库）—— 媒体库归 03 catalog，跨服务写表被禁止。';

/** 🔴 草稿边界的说明：认领只产出草稿。 */
export const DRAFT_ONLY_NOTE =
  '🔴 认领产出的是**商品草稿载荷**（已映射品牌与分类、已按命名规则命名、已预填内容），' +
  '不是商品库里的商品 —— 落库归 3.1.1.1 商品建档。';

/** 🔴 品牌与分类映射的界面措辞。 */
export const MAPPING_REQUIRED_NOTE =
  '🔴 **认领必须做品牌与分类映射**：草稿的品牌属性要在规则的「品牌映射」里查得到，' +
  '类目要落在「分类映射」或规则的「目标分类」上。查不到的那一条会认领失败并写明原因。';
