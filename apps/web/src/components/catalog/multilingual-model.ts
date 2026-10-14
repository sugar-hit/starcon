import type {
  TranslationBatchDetail,
  TranslationBatchInput,
  TranslationBatchPage,
  TranslationCoverage,
  TranslationEntry,
  TranslationEntryEdit,
  TranslationEntryInput,
  TranslationEntryPage,
  TranslationLocaleInput,
  TranslationLocalePage,
  TranslationLocaleRow,
  TranslationLocaleSaved,
  TranslationOverview,
  TranslationProductView,
  TranslationRequirementView,
  TranslationStatusInput,
  TranslationSuggestionInput,
  TranslationSuggestionPage,
  TranslationSuggestionReject,
  TranslationSuggestionRow,
  TranslationSuggestionSubmit,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/** 语言需求列表筛选条件。字段与后端 `TranslationLocaleQuery` 逐字对应。 */
export type TranslationLocaleFilter = {
  keyword: string | null;
  source: string | null;
  enabled: string | null;
  page: number;
  size: number;
};

/** 译文列表筛选条件。字段与后端 `TranslationEntryQuery` 逐字对应。 */
export type TranslationEntryFilter = {
  targetType: string | null;
  status: string | null;
  locale: string | null;
  spuId: string | null;
  skuId: string | null;
  keyword: string | null;
  page: number;
  size: number;
};

/** 建议列表筛选条件。字段与后端 `TranslationSuggestionQuery` 逐字对应。 */
export type TranslationSuggestionFilter = {
  targetType: string | null;
  status: string | null;
  locale: string | null;
  spuId: string | null;
  skuId: string | null;
  batchRef: string | null;
  keyword: string | null;
  page: number;
  size: number;
};

/**
 * 3.4.3.1 多语言内容前端模型。
 *
 * <p>这个页面最容易出错的三处：
 *
 * <ul>
 *   <li><b>「已翻」不等于「可刊登」。</b>「已翻」是机器译文或刚填的草稿，
 *       拿去发平台等于没做校对这一步。所以「是否可刊登」在后端算好（{@code publishable}），
 *       前端**只显示**不重算 —— 页面显示可刊登而 04 刊登取不到，是最坏的一类不一致。
 *   <li><b>「待翻」是一条真实的记录，不是一片空白。</b>待翻表示「已经登记了这种语言、
 *       但还没有文本」，所以它有语言标签、有商品、有状态，只是没有正文。
 *       显示成「暂无数据」会让运营以为这个商品根本不需要这种语言。
 *   <li><b>「站点推导未接入」不能显示成「不需要多语言」。</b>02 渠道未接入时后端返回
 *       {@code siteLocaleAvailable=false} 与一句归属说明，页面要原样显示这句话，
 *       而不是把空的站点语言列表画成「你没有店铺站点」。
 * </ul>
 */

/** 翻译状态三态：与 V22 的 status CHECK 和 `TranslationRules.STATUSES` 同一口径。 */
export const TRANSLATION_STATUSES = [
  { value: 'PENDING', label: '待翻', hint: '已经登记了这种语言，但还没有文本' },
  { value: 'TRANSLATED', label: '已翻', hint: '有文本了，但还没有人校对 —— 不可刊登' },
  { value: 'REVIEWED', label: '已校对', hint: '人校对过 —— 只有这一态可刊登' },
] as const;

/** 译文来源。 */
export const TRANSLATION_ORIGINS = [
  { value: 'MANUAL', label: '人工撰写' },
  { value: 'AI', label: 'AI 翻译' },
  { value: 'IMPORTED', label: '导入' },
] as const;

/** 建议处置状态。 */
export const SUGGESTION_STATUSES = [
  { value: 'PENDING', label: '待处置' },
  { value: 'ADOPTED', label: '已采纳' },
  { value: 'REJECTED', label: '已驳回' },
] as const;

/** 建议来源。 */
export const SUGGESTION_ORIGINS = [
  { value: 'AI', label: 'AI 翻译' },
  { value: 'MANUAL', label: '人工草拟' },
  { value: 'IMPORTED', label: '导入' },
] as const;

/** 语言需求来源。SHOP_SITE 由 02 渠道的启用站点推导进来。 */
export const LOCALE_SOURCES = [
  { value: 'MANUAL', label: '人工维护', hint: '现在就能写：登记租户实际需要的语言' },
  { value: 'SHOP_SITE', label: '店铺站点推导', hint: '由 02 渠道的启用站点推导（尚未接入）' },
] as const;

/** 批量动作三值。每条都带「它会做什么」，避免运营选错。 */
export const BATCH_ACTIONS = [
  {
    value: 'CREATE_PENDING',
    label: '补齐待翻占位',
    hint: '按语言需求为选中的商品铺开「待翻」记录；已有译文的会跳过，不会被抹掉',
  },
  { value: 'ADOPT_SUGGESTIONS', label: '批量采纳 AI 建议', hint: '按 batchRef 找出 18 交来的一批建议逐条采纳' },
  { value: 'MARK_REVIEWED', label: '批量标记已校对', hint: '把「已翻」的译文标成已校对；待翻的无正文，不予执行' },
] as const;

/** 逐条结果。 */
export const BATCH_OUTCOMES = [
  { value: 'SUCCEEDED', label: '成功' },
  { value: 'SKIPPED', label: '已跳过' },
  { value: 'REJECTED', label: '未执行' },
] as const;

/** 批次汇总状态。同步执行，所以没有「排队中」。 */
export const BATCH_STATUSES = [
  { value: 'SUCCEEDED', label: '全部成功' },
  { value: 'PARTIAL', label: '部分未成功' },
  { value: 'FAILED', label: '全部未成功' },
] as const;

export const TARGET_TYPES = [
  { value: 'SPU', label: 'SPU' },
  { value: 'SKU', label: 'SKU' },
] as const;

export const MAX_ITEMS_PER_BATCH = 200;
export const MAX_LOCALES_PER_BATCH = 10;
export const MAX_NAME_LENGTH = 200;
export const MAX_DESCRIPTION_LENGTH = 8000;
export const MAX_LOCALE_LENGTH = 16;

export function statusLabel(value: string | null | undefined): string {
  return TRANSLATION_STATUSES.find((s) => s.value === value)?.label ?? value ?? '未登记';
}

export function statusHint(value: string | null | undefined): string {
  return TRANSLATION_STATUSES.find((s) => s.value === value)?.hint ?? '';
}

export function originLabel(value: string | null | undefined): string {
  return TRANSLATION_ORIGINS.find((o) => o.value === value)?.label ?? value ?? '未知';
}

export function suggestionStatusLabel(value: string | null | undefined): string {
  return SUGGESTION_STATUSES.find((s) => s.value === value)?.label ?? value ?? '未知';
}

export function localeSourceLabel(value: string | null | undefined): string {
  return LOCALE_SOURCES.find((s) => s.value === value)?.label ?? value ?? '未知';
}

export function batchActionLabel(value: string | null | undefined): string {
  return BATCH_ACTIONS.find((a) => a.value === value)?.label ?? value ?? '未知';
}

export function batchActionHint(value: string | null | undefined): string {
  return BATCH_ACTIONS.find((a) => a.value === value)?.hint ?? '';
}

export function outcomeLabel(value: string | null | undefined): string {
  return BATCH_OUTCOMES.find((o) => o.value === value)?.label ?? value ?? '未知';
}

export function batchStatusLabel(value: string | null | undefined): string {
  return BATCH_STATUSES.find((s) => s.value === value)?.label ?? value ?? '未知';
}

/**
 * 状态 Badge 色调，直接用 `@starcon/ui` 的 tone 词表，不自造一套。
 *
 * <p>「未登记」是 neutral 而不是 danger：那是「还没开始」而不是「坏了」。
 * 「已校对」用 success 是因为它是唯一可刊登的一态 —— 页面上必须一眼能挑出来。
 */
export function statusTone(
  status: string | null | undefined,
): 'neutral' | 'success' | 'warning' | 'danger' {
  if (status === 'REVIEWED') return 'success';
  if (status === 'TRANSLATED') return 'warning';
  if (status === 'PENDING') return 'neutral';
  return 'neutral';
}

export function batchStatusTone(
  status: string | null | undefined,
): 'neutral' | 'success' | 'warning' | 'danger' {
  if (status === 'SUCCEEDED') return 'success';
  if (status === 'PARTIAL') return 'warning';
  if (status === 'FAILED') return 'danger';
  return 'neutral';
}

export function outcomeTone(
  outcome: string | null | undefined,
): 'neutral' | 'success' | 'warning' | 'danger' {
  if (outcome === 'SUCCEEDED') return 'success';
  if (outcome === 'SKIPPED') return 'warning';
  if (outcome === 'REJECTED') return 'danger';
  return 'neutral';
}

/**
 * 一行译文能不能刊登。
 *
 * <p>只认「已校对」。**不**在这里按 status 重新推导 publishable ——
 * 后端已经把「可刊登」算成独立的一列并用部分索引支撑 04 刊登的取用路径，
 * 前端再算一遍只会在两边规则漂移时骗人。
 */
export function publishableTone(publishable: boolean): 'neutral' | 'success' | 'warning' {
  return publishable ? 'success' : 'warning';
}

/**
 * 某商品的翻译完成度。
 *
 * <p>返回 null 表示这个商品一条译文都没有（页面上是空态而不是「0% 完成」，
 * 「0%」会被读成「试过了都没成功」）。已登记但没正文的待翻不算完成，也不算失败。
 */
export function completionOf(rows: {
  required: boolean;
  status: string | null;
}[]): { required: number; reviewed: number; pending: number; missing: number; percent: number } | null {
  const required = rows.filter((r) => r.required);
  if (required.length === 0) return null;
  const reviewed = required.filter((r) => r.status === 'REVIEWED').length;
  const pending = required.filter((r) => r.status === 'PENDING').length;
  const missing = required.length - reviewed - pending;
  return {
    required: required.length,
    reviewed,
    pending,
    missing,
    percent: Math.round((reviewed / required.length) * 100),
  };
}

/** 合并视图里「还差哪些语言」——运营在这个模块唯一要问的问题。 */
export function missingRequiredLocales(
  requirements: TranslationRequirementView['requirements'],
  coverage: TranslationCoverage | null,
): string[] {
  if (!coverage) return requirements.filter((r) => r.required).map((r) => r.locale);
  return coverage.rows.filter((r) => r.required && !r.publishable).map((r) => r.locale);
}

// ------------------------------------------------------------ 校验（与服务层同口径）

/**
 * 语言标签校验（BCP-47 简写）。
 *
 * <p>与后端 `TranslationRules.localeProblem` 同一口径，但**不替代**它：
 * 这里的校验只是让人在提交前少点一次，真实拦截在服务端与 DB 的唯一约束上。
 */
export function localeProblem(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed) return '语言不能为空（例如 zh-CN / en-US）';
  if (trimmed.length > MAX_LOCALE_LENGTH) return `语言标签最多 ${MAX_LOCALE_LENGTH} 个字符`;
  if (!/^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?$/.test(trimmed))
    return '语言标签请用 BCP-47 简写（zh-CN / en-US / ja-JP）：不要用中文名或下划线写法';
  return null;
}

/** 规范化后的语言标签：语言小写 + 地区大写。与后端同一份口径，否则筛选会静默失效。 */
export function normalizeLocale(value: string): string {
  const trimmed = value.trim();
  if (!trimmed) return '';
  const dash = trimmed.indexOf('-');
  if (dash < 0) return trimmed.toLowerCase();
  return `${trimmed.slice(0, dash).toLowerCase()}-${trimmed.slice(dash + 1).toUpperCase()}`;
}

/**
 * 译文正文校验。
 *
 * <p>花括号一律拒：占位符是 3.4.2.2 描述模板的机制，商品多语言名称与描述是**已填好的成品**。
 */
export function textProblem(name: string, description: string, status: string): string | null {
  const trimmedName = name.trim();
  const trimmedDescription = description.trim();
  if (trimmedName.length > MAX_NAME_LENGTH) return `商品名称最多 ${MAX_NAME_LENGTH} 个字符`;
  if (trimmedDescription.length > MAX_DESCRIPTION_LENGTH)
    return `商品描述最多 ${MAX_DESCRIPTION_LENGTH} 个字符`;
  if (trimmedName.includes('{') || trimmedDescription.includes('{'))
    return '名称或描述里出现了花括号：{...} 是 3.4.2.2 描述模板的占位符机制，这里必须是已填好的成品';
  if (status === 'PENDING' && (trimmedName || trimmedDescription))
    return '「待翻」要求名称与描述都为空：还没有文本就谈不上已翻译。要填译文请把状态改成「已翻」';
  if (status === 'REVIEWED' && !trimmedName) return '「已校对」要求商品名称非空：空名称进不了刊登';
  if (status !== 'PENDING' && !trimmedName && !trimmedDescription)
    return '「已翻」「已校对」要求至少有名称或描述其中之一有内容';
  return null;
}

/** 驳回理由必填 —— 不给理由的驳回让下一轮 AI 翻译无从改进。 */
export function rejectReasonProblem(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed) return '驳回必须写理由：没有理由的驳回让下一轮翻译无从改进';
  if (trimmed.length > 500) return '驳回理由最多 500 个字符';
  return null;
}

export function itemsSizeProblem(size: number): string | null {
  if (size === 0) return '请至少选择一个商品';
  if (size > MAX_ITEMS_PER_BATCH) return `单次批量最多 ${MAX_ITEMS_PER_BATCH} 个商品`;
  return null;
}

export function batchLocalesSizeProblem(size: number): string | null {
  if (size > MAX_LOCALES_PER_BATCH)
    return `单次批量最多指定 ${MAX_LOCALES_PER_BATCH} 种语言（商品 × 语言会相乘）`;
  return null;
}

/** 把 400 的人话直接显示出来，别只显示「操作失败」。 */
export function errorText(error: unknown, fallback = '多语言内容数据加载失败'): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return fallback;
}

// ------------------------------------------------------------ 空表单（与后端 record 逐字对应）

export function emptyLocaleInput(locale = '', localeName = ''): TranslationLocaleInput {
  return { locale, localeName, enabled: true, note: '', version: 0 };
}

export function emptyEntryInput(locale = ''): TranslationEntryInput {
  return { locale, name: '', description: '', origin: 'MANUAL', status: 'TRANSLATED' };
}

export function emptyEntryEdit(entry: TranslationEntry): TranslationEntryEdit {
  return {
    name: entry.name,
    description: entry.description,
    origin: entry.origin,
    status: entry.status,
    version: entry.version,
  };
}

export function emptyBatchInput(action = 'CREATE_PENDING', targetType = 'SPU'): TranslationBatchInput {
  return { action, targetType, targetIds: [], locales: [], batchRef: '' };
}

// ------------------------------------------------------------ 接口

export function fetchTranslationOverview() {
  return api<TranslationOverview>('catalog/translations/overview');
}

export function fetchLocales(filter: TranslationLocaleFilter) {
  const params = new URLSearchParams();
  if (filter.keyword?.trim()) params.set('keyword', filter.keyword.trim());
  if (filter.source) params.set('source', filter.source);
  if (filter.enabled) params.set('enabled', filter.enabled);
  params.set('page', String(filter.page));
  params.set('size', String(filter.size));
  return api<TranslationLocalePage>(`catalog/translations/locales?${params.toString()}`);
}

export function fetchLocale(id: string) {
  return api<TranslationLocaleRow>(`catalog/translations/locales/${id}`);
}

export function saveLocale(id: string | null, input: TranslationLocaleInput) {
  return id
    ? api<TranslationLocaleSaved>(`catalog/translations/locales/${id}`, 'PUT', input)
    : api<TranslationLocaleSaved>('catalog/translations/locales', 'POST', input);
}

export function setLocaleEnabled(id: string, enabled: boolean) {
  return api<TranslationLocaleRow>(
    `catalog/translations/locales/${id}/enabled?enabled=${enabled}`,
    'PUT',
  );
}

export function deleteLocale(id: string, version: number) {
  return api<{ deleted: boolean }>(`catalog/translations/locales/${id}?version=${version}`, 'DELETE');
}

/** 合并视图：人工维护 ∪ 店铺启用站点推导（未接入时 siteLocaleAvailable=false）。 */
export function fetchRequirements() {
  return api<TranslationRequirementView>('catalog/translations/locales/requirements');
}

/** 从站点同步语言需求。未接入时后端返回 409 并写明归属。 */
export function syncFromSites() {
  return api<TranslationRequirementView>('catalog/translations/locales/sync', 'POST');
}

export function fetchProductWorkspace(targetType: 'SPU' | 'SKU', id: string) {
  const segment = targetType === 'SKU' ? 'skus' : 'spus';
  return api<TranslationProductView>(`catalog/translations/${segment}/${id}`);
}

export function fetchEntries(filter: TranslationEntryFilter) {
  const params = new URLSearchParams();
  if (filter.targetType) params.set('targetType', filter.targetType);
  if (filter.status) params.set('status', filter.status);
  if (filter.locale) params.set('locale', filter.locale);
  if (filter.spuId) params.set('spuId', filter.spuId);
  if (filter.skuId) params.set('skuId', filter.skuId);
  if (filter.keyword?.trim()) params.set('keyword', filter.keyword.trim());
  params.set('page', String(filter.page));
  params.set('size', String(filter.size));
  return api<TranslationEntryPage>(`catalog/translations/entries?${params.toString()}`);
}

export function fetchEntry(id: string) {
  return api<TranslationEntry>(`catalog/translations/entries/${id}`);
}

export function createEntry(
  targetType: 'SPU' | 'SKU',
  targetId: string,
  input: TranslationEntryInput,
) {
  const segment = targetType === 'SKU' ? 'skus' : 'spus';
  return api<TranslationEntry>(`catalog/translations/${segment}/${targetId}/entries`, 'POST', input);
}

export function updateEntry(id: string, input: TranslationEntryEdit) {
  return api<TranslationEntry>(`catalog/translations/entries/${id}`, 'PUT', input);
}

/** 改译文。与 description-model 的 saveTemplate/savePhrase 同一形状：按 id 决定 PUT 还是 POST。 */
export function saveEntry(id: string, input: TranslationEntryEdit) {
  return updateEntry(id, input);
}

/** 状态流转（人工校对 / 退回）。后端会记下校对人与校对时刻。 */
export function setEntryStatus(id: string, input: TranslationStatusInput) {
  return api<TranslationEntry>(`catalog/translations/entries/${id}/status`, 'PUT', input);
}

export function deleteEntry(id: string, version: number) {
  return api<{ deleted: boolean }>(`catalog/translations/entries/${id}?version=${version}`, 'DELETE');
}

export function fetchCoverage(targetType: string, targetId: string, locales: string[] = []) {
  const params = new URLSearchParams();
  params.set('targetType', targetType);
  params.set('targetId', targetId);
  for (const locale of locales) params.append('locale', locale);
  return api<TranslationCoverage>(`catalog/translations/coverage?${params.toString()}`);
}

/** 导出 CSV 的浏览器地址（普通 <a download>，不走 api()：它无条件设 JSON content-type）。 */
export function csvExportUrl(filter: TranslationEntryFilter): string {
  const params = new URLSearchParams();
  if (filter.targetType) params.set('targetType', filter.targetType);
  if (filter.status) params.set('status', filter.status);
  if (filter.locale) params.set('locale', filter.locale);
  if (filter.spuId) params.set('spuId', filter.spuId);
  if (filter.skuId) params.set('skuId', filter.skuId);
  if (filter.keyword?.trim()) params.set('keyword', filter.keyword.trim());
  return `/api/catalog/translations/entries/export?${params.toString()}`;
}

export function fetchSuggestions(filter: TranslationSuggestionFilter) {
  const params = new URLSearchParams();
  if (filter.targetType) params.set('targetType', filter.targetType);
  if (filter.status) params.set('status', filter.status);
  if (filter.locale) params.set('locale', filter.locale);
  if (filter.spuId) params.set('spuId', filter.spuId);
  if (filter.skuId) params.set('skuId', filter.skuId);
  if (filter.batchRef?.trim()) params.set('batchRef', filter.batchRef.trim());
  if (filter.keyword?.trim()) params.set('keyword', filter.keyword.trim());
  params.set('page', String(filter.page));
  params.set('size', String(filter.size));
  return api<TranslationSuggestionPage>(`catalog/translations/suggestions?${params.toString()}`);
}

export function fetchSuggestion(id: string) {
  return api<TranslationSuggestionRow>(`catalog/translations/suggestions/${id}`);
}

/** 18 AI 翻译回写建议的入口（人工也可以登记一条候选）。 */
export function submitSuggestions(items: TranslationSuggestionInput[]) {
  const body: TranslationSuggestionSubmit = { items };
  return api<TranslationSuggestionPage>('catalog/translations/suggestions', 'POST', body);
}

/** 采纳一条建议。译文会落在「已翻」，不是「已校对」。 */
export function adoptSuggestion(id: string) {
  return api<TranslationEntry>(`catalog/translations/suggestions/${id}/adopt`, 'POST');
}

export function rejectSuggestion(id: string, input: TranslationSuggestionReject) {
  return api<TranslationSuggestionRow>(
    `catalog/translations/suggestions/${id}/reject`,
    'POST',
    input,
  );
}

export function deleteSuggestion(id: string, version: number) {
  return api<{ deleted: boolean }>(
    `catalog/translations/suggestions/${id}?version=${version}`,
    'DELETE',
  );
}

export function fetchBatches(filter: { action: string | null; targetType: string | null; status: string | null; page: number; size: number }) {
  const params = new URLSearchParams();
  if (filter.action) params.set('action', filter.action);
  if (filter.targetType) params.set('targetType', filter.targetType);
  if (filter.status) params.set('status', filter.status);
  params.set('page', String(filter.page));
  params.set('size', String(filter.size));
  return api<TranslationBatchPage>(`catalog/translations/batches?${params.toString()}`);
}

export function fetchBatchDetail(id: string) {
  return api<TranslationBatchDetail>(`catalog/translations/batches/${id}`);
}

/** 执行批量操作。**同步语义**：返回即已完成，不要显示「处理中」再轮询。 */
export function runBatch(input: TranslationBatchInput) {
  return api<TranslationBatchDetail>('catalog/translations/batches', 'POST', input);
}