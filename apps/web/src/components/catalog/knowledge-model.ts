import type {
  DeletedResponse,
  EntryHitRateRow,
  EntryRuleBinding,
  EntryTranslation,
  EntryTranslationCoverage,
  EntryTranslationInput,
  KnowledgeBaseOptions,
  KnowledgeBaseOverview,
  KnowledgeEntryDetail,
  KnowledgeEntryInput,
  KnowledgeEntryPage,
  KnowledgeEntryRow,
  KnowledgeSaved,
  KbUsagePage,
  KbUsageRow,
  ReviewTaskPage,
  ReviewTaskRow,
  RuleVersionPage,
  RuleVersionRow,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 11.4.2.2 客服知识库与话术前端模型。
 *
 * <p>这个页面最容易把三件事做错：
 *
 * <ul>
 *   <li><b>命中率不能在前端重算。</b>口径写在后端 {@code KnowledgeBaseRules.hitRate}
 *       （规则 R2~R4）：分子是「有命中记录的启用话术<b>条数</b>」，不是引用次数。
 *       前端拿 usageCount 去除一遍的话，分母会变成「被引用过的条数」，
 *       于是「多少条话术根本没人用」这个最该回答的问题反而被算没了。
 *   <li><b>待复核任务不是待办 widget。</b>它是蓝图「平台政策变化时能定位哪些话术需要更新」
 *       的落地页，每行必须显示<b>是哪一版接替了它</b>（{@code supersedingRuleLabel}）
 *       与两版的变更摘要 —— 只显示「政策改版了」的话，客服没法判断要改什么。
 *   <li><b>「没被用过」和「没配过译文」都要说清为什么。</b>空态文案逐条解释，
 *       而不是画成 0 或留白。
 * </ul>
 */

/** 话术列表筛选条件。字段与后端 {@code listEntries} 的 query 逐字对应。 */
export type KnowledgeEntryQuery = {
  keyword: string | null;
  entryType: string | null;
  enabled: string | null;
  reviewState: string | null;
  page: number;
  size: number;
};

/** 规则版本列表筛选条件。 */
export type RuleVersionQuery = {
  keyword: string | null;
  platform: string | null;
  status: string | null;
  page: number;
  size: number;
};

/** 待复核任务列表筛选条件。 */
export type ReviewTaskQuery = {
  keyword: string | null;
  reviewStatus: string | null;
  platform: string | null;
  page: number;
  size: number;
};

/** 引用记录列表筛选条件。 */
export type UsageQuery = {
  keyword: string | null;
  outcome: string | null;
  entryId: string | null;
  page: number;
  size: number;
};

/** 话术类型词表：与 V100 的 CHECK 和后端 ENTRY_TYPES 同一口径。 */
export const ENTRY_TYPES = [
  { value: 'FAQ', label: '常见问题标准答法', hint: '「怎么退？」→ 客服直接复制的标准答法' },
  { value: 'POLICY', label: '平台政策说明', hint: '「亚马逊 30 天退货政策怎么规定的」' },
  { value: 'RETURN_EX', label: '退换货话术', hint: '换货 / 仅退款 / 部分退款的标准回复' },
  { value: 'OTHER', label: '其它话术', hint: '尺码对照、物流沟通模板等' },
] as const;

/** 平台词表：与 V14 类目树的命名空间同源。 */
export const PLATFORMS = [
  { value: 'AMAZON', label: '亚马逊' },
  { value: 'SHOPIFY', label: 'Shopify' },
  { value: 'EBAY', label: 'eBay' },
  { value: 'TIKTOK', label: 'TikTok Shop' },
  { value: 'ALI1688', label: '速卖通 / 1688' },
] as const;

/**
 * 引用结果五态。
 *
 * <p>每一种都标了**该不该计入命中**（`hit`），因为这是整个页面最需要说清的一件事：
 * 「被驳回」不是「没效果」，而是「**这套话术是错的**」，比没人用更值得优先处理。
 */
export const USAGE_OUTCOMES = [
  { value: 'RESOLVED', label: '一次解决', hint: '买家接受，问题结案', hit: true },
  { value: 'PARTIAL', label: '部分解决', hint: '给了话术但买家仍有疑问', hit: true },
  {
    value: 'UNRESOLVED',
    label: '未解决',
    hint: '引用了但仍要转人工或升级',
    hit: false,
  },
  {
    value: 'ABANDONED',
    label: '放弃引用',
    hint: '打开了话术但最终没发出去 —— 检索噪声的信号',
    hit: false,
  },
  {
    value: 'REJECTED',
    label: '话术被驳回',
    hint: '照话术答复被买家或平台驳回 —— 最该被发现的一种',
    hit: false,
  },
] as const;

export const REVIEW_STATUSES = [
  { value: 'PENDING', label: '待复核' },
  { value: 'REVIEWING', label: '复核中' },
  { value: 'DONE', label: '已复核' },
] as const;

export const RULE_STATUSES = [
  { value: 'DRAFT', label: '草稿', hint: '还没接替现行版本，不参与挂钩判定' },
  { value: 'ACTIVE', label: '当前生效', hint: '一个政策同时只能有一版生效' },
  {
    value: 'SUPERSEDED',
    label: '已被接替',
    hint: '标记后自动给挂在它上面的话术生成待复核任务',
  },
] as const;

export const TRANSLATION_ORIGINS = [
  { value: 'MANUAL', label: '人工撰写' },
  { value: 'AI', label: 'AI 翻译预填' },
  { value: 'IMPORTED', label: '导入' },
] as const;

export const TRANSLATION_STATUSES = [
  { value: 'DRAFT', label: '草稿', hint: 'AI 预填未复核，不算「能直接发给买家」' },
  { value: 'REVIEWED', label: '已复核' },
] as const;

export const MAX_BODY_LENGTH = 8000;
export const MAX_TITLE_LENGTH = 120;
export const MAX_KEYWORD_LENGTH = 40;
export const MAX_KEYWORDS = 12;
export const PAGE_SIZE = 20;

// ------------------------------------------------------------ 标签

export function entryTypeLabel(value: string | null | undefined): string {
  return ENTRY_TYPES.find((t) => t.value === value)?.label ?? value ?? '未知';
}

export function platformLabel(value: string | null | undefined): string {
  return PLATFORMS.find((p) => p.value === value)?.label ?? value ?? '未知';
}

export function outcomeLabel(value: string | null | undefined): string {
  return USAGE_OUTCOMES.find((o) => o.value === value)?.label ?? value ?? '未知';
}

export function reviewStatusLabel(value: string | null | undefined): string {
  return REVIEW_STATUSES.find((s) => s.value === value)?.label ?? value ?? '未知';
}

export function ruleStatusLabel(value: string | null | undefined): string {
  return RULE_STATUSES.find((s) => s.value === value)?.label ?? value ?? '未知';
}

export function originLabel(value: string | null | undefined): string {
  return TRANSLATION_ORIGINS.find((o) => o.value === value)?.label ?? value ?? '未知';
}

export function translationStatusLabel(value: string | null | undefined): string {
  return TRANSLATION_STATUSES.find((s) => s.value === value)?.label ?? value ?? '未知';
}

export function reviewStateLabel(value: string | null | undefined): string {
  if (value === 'PENDING') return '有待复核';
  if (value === 'NONE') return '无待复核';
  return '全部';
}

// ------------------------------------------------------------ 色调

export function reviewStatusTone(
  status: string | null | undefined,
): 'neutral' | 'success' | 'warning' {
  if (status === 'DONE') return 'success';
  if (status === 'REVIEWING') return 'warning';
  return 'neutral';
}

export function ruleStatusTone(
  status: string | null | undefined,
): 'neutral' | 'success' | 'warning' {
  if (status === 'ACTIVE') return 'success';
  if (status === 'SUPERSEDED') return 'warning';
  return 'neutral';
}

/** 引用结果色调：**被驳回用 danger**，其余按是否计入命中区分。 */
export function outcomeTone(
  outcome: string | null | undefined,
): 'neutral' | 'success' | 'warning' | 'danger' {
  if (outcome === 'REJECTED') return 'danger';
  if (outcome === 'RESOLVED') return 'success';
  if (outcome === 'PARTIAL') return 'warning';
  return 'neutral';
}

// ------------------------------------------------------------ 本地校验（与服务层同口径）

export function codeProblem(code: string): string | null {
  const value = code.trim().toUpperCase();
  if (!value) return '话术编码不能为空';
  if (value.length > 32) return `话术编码最长 32 个字符（现在 ${value.length} 个）`;
  if (!/^[A-Z0-9_]+$/.test(value))
    return '话术编码只能用大写字母、数字与下划线（便于客服与运营互相口头引用）';
  return null;
}

export function titleProblem(title: string): string | null {
  const value = title.trim();
  if (!value) return '标题不能为空';
  if (value.length > MAX_TITLE_LENGTH)
    return `标题最长 ${MAX_TITLE_LENGTH} 个字符（现在 ${value.length} 个）`;
  return null;
}

export function bodyProblem(body: string): string | null {
  const value = body.trim();
  if (!value) return '标准答法正文不能为空';
  if (value.length > MAX_BODY_LENGTH)
    return `正文最长 ${MAX_BODY_LENGTH} 个字符（现在 ${value.length} 个）—— 客服会话窗口里的话术是一屏，不是说明书`;
  return null;
}

export function keywordsProblem(keywords: string): string | null {
  if (!keywords.trim()) return null;
  const words = keywords
    .split(/[,，]/)
    .map((w) => w.trim())
    .filter(Boolean);
  if (words.length > MAX_KEYWORDS)
    return `触发词最多 ${MAX_KEYWORDS} 个（现在 ${words.length} 个）`;
  for (const word of words) {
    if (word.length > MAX_KEYWORD_LENGTH)
      return `触发词「${word.slice(0, 20)}…」最长 ${MAX_KEYWORD_LENGTH} 个字符：触发词是短词（客服检索时打的是「退」「换」），不是整句`;
    if (word.includes('{') || word.includes('}')) return '触发词不能含花括号（那是正文里的占位符写法）';
  }
  return null;
}

/** 正文里的占位符名（去重、排序）。与后端 {@code KnowledgeBaseRules.placeholdersOf} 同一口径。 */
export function placeholdersOf(body: string): string[] {
  const out = new Set<string>();
  let i = 0;
  while (i < body.length) {
    const open = body.indexOf('{', i);
    if (open < 0) break;
    const close = body.indexOf('}', open + 1);
    if (close < 0) break;
    const name = body.slice(open + 1, close).trim();
    if (name) out.add(name);
    i = close + 1;
  }
  return [...out].sort();
}

/**
 * 译文占位符与源语言是否一致（规则 R1）。
 *
 * <p>两个方向分别报：只报「不一致」的话，运营不知道自己漏的还是多的，
 * 而这两种的修法不同（漏 = 译文没抄全；多 = 译文里有源语言没有的占位符，永远填不上）。
 */
export function placeholderProblem(
  sourceBody: string,
  translationBody: string,
): string | null {
  const source = placeholdersOf(sourceBody);
  const translation = placeholdersOf(translationBody);
  const missing = source.filter((p) => !translation.includes(p));
  const extra = translation.filter((p) => !source.includes(p));
  if (!missing.length && !extra.length) return null;
  let reason = '占位符与源语言正文不一致';
  if (missing.length) reason += `：源语言多出 {${missing.join('} { ')}}（译文漏抄，补上即可）`;
  if (extra.length) reason += `；译文多出 {${extra.join('} { ')}}（源语言没有这个占位符，永远填不上，删掉）`;
  return reason;
}

// ------------------------------------------------------------ 展示派生

/** 百分比文本。**null 显示「—」而不是 0%**（规则 R4）。 */
export function percentText(rate: number | string | null | undefined): string {
  if (rate === null || rate === undefined || rate === '') return '—';
  const value = Number(rate);
  if (Number.isNaN(value)) return '—';
  return `${(value * 100).toFixed(1)}%`;
}

/** 命中率色调：低于 30% 提醒，高于等于 70% 正常，中间 warn。 */
export function hitRateTone(rate: number | string | null | undefined): 'neutral' | 'success' | 'warning' {
  if (rate === null || rate === undefined || rate === '') return 'neutral';
  const value = Number(rate);
  if (value >= 0.7) return 'success';
  if (value >= 0.3) return 'warning';
  return 'warning';
}

/**
 * 一条待复核任务**要告诉客服做什么**。
 *
 * <p>不是「政策改版了」四个字 —— 必须点名是哪一版接替了它，以及两版各自改了什么，
 * 否则客服只能一条条翻规则历史才知道该比对什么。
 */
export function reviewTaskSummary(task: {
  policyKey: string;
  versionLabel: string;
  supersedingRuleLabel: string | null;
  supersedingChangeSummary: string | null;
}): string {
  const policy = platformLabel(task.policyKey.startsWith('AMAZON') ? 'AMAZON' : 'AMAZON');
  if (!task.supersedingRuleLabel) {
    return `${policy} ${task.policyKey} 的 ${task.versionLabel} 已改版，但还没有登记接替版本 —— 请先补上新版再复核`;
  }
  const change = task.supersedingChangeSummary?.trim();
  return change
    ? `${policy} ${task.policyKey} 已从 ${task.versionLabel} 改版到 ${task.supersedingRuleLabel}：${change}`
    : `${policy} ${task.policyKey} 已从 ${task.versionLabel} 改版到 ${task.supersedingRuleLabel}`;
}

/** 引用结果一句话解释（页面上每行都给，让运营知道这个词什么意思）。 */
export function outcomeHint(outcome: string | null | undefined): string {
  return USAGE_OUTCOMES.find((o) => o.value === outcome)?.hint ?? '';
}

/** 空态文案：逐条说明「为什么空」而不是画成 0。 */
export function entryEmptyHint(enabled: string, reviewState: string, keyword: string): string {
  if (keyword.trim()) return `没有匹配「${keyword.trim()}」的话术 —— 关键词会同时搜编码、标题与正文。`;
  if (reviewState === 'PENDING') return '当前没有待复核的话术 —— 平台规则版本被标记为「已被接替」时才会自动生成。';
  if (enabled === 'false') return '当前只看停用的话术 —— 停用后不能再被新引用，历史引用记录与译文照常可读。';
  return '话术库还是空的。新建一条话术后，客服就能在会话里一键引用，并在这里看到它的使用效果。';
}

export function reviewEmptyHint(status: string, platform: string): string {
  if (platform) return `当前平台（${platformLabel(platform)}）没有${reviewStatusLabel(status)}的复核任务。`;
  if (status === 'PENDING')
    return '没有待复核任务 —— 在「平台规则版本」里把某一版标记为「已被接替」，挂在它上面的话术会自动各生成一条。';
  return '没有匹配的复核任务。';
}

export function usageEmptyHint(outcome: string): string {
  if (outcome === 'REJECTED')
    return '没有被驳回的话术 —— 这是好消息：照话术答复被买家或平台驳回，是最该被发现的一种。';
  if (outcome) return `当前筛选「${outcomeLabel(outcome)}」下还没有引用记录。`;
  return '还没有引用记录 —— 客服在会话里一键引用话术后会在这里逐条落库，命中率统计也从这些行现算。';
}

export function ruleEmptyHint(keyword: string, status: string): string {
  if (keyword.trim()) return `没有匹配「${keyword.trim()}」的平台规则版本。`;
  if (status === 'SUPERSEDED')
    return '还没有被接替的规则版本 —— 把现行版本标记为「已被接替」时，挂在它上面的话术会自动生成待复核任务。';
  return '还没有登记平台规则版本。话术要挂在某一版规则上，才能在政策改版时定位到需要更新的话术。';
}

// ------------------------------------------------------------ 归一化

export function normalizeLocale(locale: string): string {
  const value = locale.trim();
  const dash = value.indexOf('-');
  if (dash < 0) return value.toLowerCase();
  const lang = value.slice(0, dash).toLowerCase();
  const region = value.slice(dash + 1);
  return /^\d{3}$/.test(region) ? `${lang}-${region}` : `${lang}-${region.toUpperCase()}`;
}

export function emptyEntryInput(code?: string): KnowledgeEntryInput {
  return {
    code: code ?? '',
    title: '',
    entryType: 'FAQ',
    body: '',
    sourceLocale: 'zh-CN',
    triggerKeywords: '',
    enabled: true,
    version: 0,
  };
}

export function emptyTranslationInput(locale: string): EntryTranslationInput {
  return { locale, title: '', body: '', origin: 'MANUAL', status: 'DRAFT', version: 0 };
}

export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '请求失败，请稍后重试';
}

// ------------------------------------------------------------ 接口

export function fetchKnowledgeOverview() {
  return api<KnowledgeBaseOverview>('catalog/kb/overview');
}

export function fetchKnowledgeOptions() {
  return api<KnowledgeBaseOptions>('catalog/kb/options');
}

export function fetchEntries(query: KnowledgeEntryQuery) {
  const params = new URLSearchParams();
  if (query.keyword?.trim()) params.set('keyword', query.keyword.trim());
  if (query.entryType) params.set('entryType', query.entryType);
  if (query.enabled) params.set('enabled', query.enabled);
  if (query.reviewState) params.set('reviewState', query.reviewState);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return api<KnowledgeEntryPage>(`catalog/kb/entries?${params.toString()}`);
}

export function fetchEntry(id: string) {
  return api<KnowledgeEntryDetail>(`catalog/kb/entries/${id}`);
}

export function saveEntry(id: string | null, input: KnowledgeEntryInput) {
  return id
    ? api<KnowledgeSaved>(`catalog/kb/entries/${id}`, 'PUT', input)
    : api<KnowledgeSaved>('catalog/kb/entries', 'POST', input);
}

export function setEntryEnabled(id: string, enabled: boolean) {
  return api<KnowledgeSaved>(`catalog/kb/entries/${id}/enabled?enabled=${enabled}`, 'PUT');
}

export function deleteEntry(id: string, version: number) {
  return api<DeletedResponse>(`catalog/kb/entries/${id}?version=${version}`, 'DELETE');
}

export function fetchTranslations(entryId: string) {
  return api<EntryTranslation[]>(`catalog/kb/entries/${entryId}/translations`);
}

export function fetchTranslationCoverage(entryId: string, locales: string[]) {
  const params = new URLSearchParams();
  for (const locale of locales) params.append('locale', locale);
  return api<EntryTranslationCoverage>(
    `catalog/kb/entries/${entryId}/translation-coverage?${params.toString()}`,
  );
}

export function saveTranslation(entryId: string, id: string | null, input: EntryTranslationInput) {
  return id
    ? api<EntryTranslation>(`catalog/kb/entries/${entryId}/translations/${id}`, 'PUT', input)
    : api<EntryTranslation>(`catalog/kb/entries/${entryId}/translations`, 'POST', input);
}

export function deleteTranslation(id: string, version: number) {
  return api<DeletedResponse>(`catalog/kb/translations/${id}?version=${version}`, 'DELETE');
}

export function fetchRuleVersions(query: RuleVersionQuery) {
  const params = new URLSearchParams();
  if (query.keyword?.trim()) params.set('keyword', query.keyword.trim());
  if (query.platform) params.set('platform', query.platform);
  if (query.status) params.set('status', query.status);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return api<RuleVersionPage>(`catalog/kb/rule-versions?${params.toString()}`);
}

export function createRuleVersion(input: {
  platform: string;
  policyKey: string;
  versionLabel: string;
  title: string;
  changeSummary?: string;
  effectiveFrom?: string;
  effectiveTo?: string | null;
}) {
  return api<KnowledgeSaved>('catalog/kb/rule-versions', 'POST', input);
}

export function updateRuleVersion(
  id: string,
  status: string | undefined,
  input: {
    platform: string;
    policyKey: string;
    versionLabel: string;
    title: string;
    changeSummary?: string;
    effectiveFrom?: string;
    effectiveTo?: string | null;
  },
) {
  const params = new URLSearchParams();
  if (status) params.set('status', status);
  const query = params.toString();
  return api<KnowledgeSaved>(
    `catalog/kb/rule-versions/${id}${query ? `?${query}` : ''}`,
    'PUT',
    input,
  );
}

export function deleteRuleVersion(id: string) {
  return api<DeletedResponse>(`catalog/kb/rule-versions/${id}`, 'DELETE');
}

export function fetchBindings(entryId: string) {
  return api<EntryRuleBinding[]>(`catalog/kb/entries/${entryId}/rule-bindings`);
}

export function bindEntry(entryId: string, ruleVersionId: string, note?: string) {
  return api<EntryRuleBinding>(`catalog/kb/entries/${entryId}/rule-bindings`, 'POST', {
    ruleVersionId,
    note: note ?? '',
  });
}

export function unbindEntry(entryId: string, bindingId: string) {
  return api<DeletedResponse>(
    `catalog/kb/entries/${entryId}/rule-bindings/${bindingId}`,
    'DELETE',
  );
}

export function fetchReviewTasks(query: ReviewTaskQuery) {
  const params = new URLSearchParams();
  if (query.keyword?.trim()) params.set('keyword', query.keyword.trim());
  if (query.reviewStatus) params.set('reviewStatus', query.reviewStatus);
  if (query.platform) params.set('platform', query.platform);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return api<ReviewTaskPage>(`catalog/kb/review-tasks?${params.toString()}`);
}

export function updateReviewTask(taskId: string, reviewStatus: string, note?: string) {
  return api<ReviewTaskRow>(`catalog/kb/review-tasks/${taskId}`, 'PUT', { reviewStatus, note: note ?? '' });
}

export function recordUsage(entryId: string, outcome: string, conversationRef?: string, note?: string) {
  return api<KbUsageRow>(`catalog/kb/entries/${entryId}/usages`, 'POST', {
    outcome,
    conversationRef: conversationRef ?? '',
    note: note ?? '',
  });
}

export function fetchUsages(query: UsageQuery) {
  const params = new URLSearchParams();
  if (query.keyword?.trim()) params.set('keyword', query.keyword.trim());
  if (query.outcome) params.set('outcome', query.outcome);
  if (query.entryId) params.set('entryId', query.entryId);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return api<KbUsagePage>(`catalog/kb/usages?${params.toString()}`);
}

/** 单条话术的使用历史：返回数组（详情页只需要最近若干条，不分页）。 */
export function fetchEntryUsages(entryId: string, size = PAGE_SIZE) {
  return api<KbUsageRow[]>(`catalog/kb/entries/${entryId}/usages?size=${size}`);
}

export function fetchHitRates(sort?: string, limit = 50) {
  const params = new URLSearchParams();
  if (sort) params.set('sort', sort);
  params.set('limit', String(limit));
  return api<EntryHitRateRow[]>(`catalog/kb/hit-rates?${params.toString()}`);
}

/** 排行榜排序选项（页面下拉）。顺序即「运营该先看哪个」的顺序。 */
export const HIT_RATE_SORTS = [
  { value: '', label: '按被引用次数', hint: '先看哪些话术真的在用，再看命中率' },
  { value: 'HIT_RATE', label: '按命中率', hint: '注意：只被用过 1 次的话术也会排前面' },
  { value: 'LEAST_USED', label: '最少被使用', hint: '找出根本没人用的话术' },
  { value: 'REJECTED', label: '被驳回最多', hint: '找出照着答复会被驳回的话术' },
] as const;

export type KnowledgeEntryRowLike = KnowledgeEntryRow;
