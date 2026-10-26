import type {
  TextBannedTermInput,
  TextBannedTermRow,
  TextCapabilities,
  TextCheckRow,
  TextConfidence,
  TextDraftInput,
  TextDraftRow,
  TextGenerationOutcome,
  TextGlossaryInput,
  TextGlossaryRow,
  TextLocaleView,
  TextOverview,
  TextRuleInput,
  TextRuleRow,
  TextSourceCandidateView,
  TextTaskDetail,
  TextTaskInput,
  TextTaskPage,
  TextTaskRow,
  TextTemplateBinding,
  TextTemplateInput,
  TextTemplateRow,
  TextViolation,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 18.4.2.1「文本与翻译」前端模型。
 *
 * 蓝图：「**AI 文本能力**：多语言翻译（标题/描述/客服话术，支持保留品牌词与专业术语）、
 * 标题优化与生成、卖点提炼、违禁词规避建议、客服回复草稿。输入：源文本；目标语言；
 * 风格要求；术语表。输出：翻译/生成结果 + 置信度提示 + 可人工编辑。关系：上游 = 3.4.3 多语言、
 * 4.2.1.1 编辑器、15.1.1.2 发送；下游 = 无（结果需人工确认）。
 * **翻译结果必须人工确认后发布**，直接发机器翻译是品牌风险。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「没接 AI」显示成「没接 AI」，不是「翻译好了」**。本仓目前没有任何真实的
 *    AI / LLM 供应商适配器，所以 `/generate` 会如实返回 409。前端**不**把它渲染成
 *    一个空白的译文框 —— 那会让运营以为「机器已经翻好了，只是没显示」。
 * 2. **置信度是四个维度的加权，不是一个神秘的总分**。页面逐维显示，
 *    「不适用」显示成「不适用」，**不**显示成 0% —— 那是两件不同的事。
 * 3. **「未确认的译文取不走」**：只有 PUBLISHED 的行出现在「已发布」里，
 *    页面不提供任何「绕过确认」的入口。
 * 4. **确定性检查的 FAIL / WARN / SKIPPED 分开显示**：SKIPPED 是「没查」，
 *    不是「通过」。把 SKIPPED 显示成绿色「通过」等于骗人。
 * 5. **上游读不到 ≠ 没有**：能力声明里 `available=false` 的项按原样显示并写明归谁，
 *    不显示成「暂无」。
 *
 * <p><b>类型全部从契约派生</b>（`packages/api-contracts` 的 codegen 产物），
 * 前端**不手抄**字符串联合 —— 枚举值由后端的字典列给出。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有分支）。 */
const base = 'platform/text';

/** 任务列表的筛选条件。 */
export interface TaskParams {
  keyword?: string | undefined;
  contentType?: string | undefined;
  status?: string | undefined;
  targetLocale?: string | undefined;
}

/**
 * 拼查询串：空值不拼（拼 `undefined=` 上游会当成「筛选了这个值」）。
 *
 * <p>布尔值要显式转成 {@code 'true' / 'false'} —— 直接塞进 URLSearchParams 会变成
 * 「true」/「false」是对的，但 {@code undefined} 会被拼成字面量 {@code "undefined"}，
 * 上游就会当成「筛选了这个值」。
 */
function query(params: Record<string, string | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, typeof value === 'boolean' ? String(value) : value);
  }
  return search.toString();
}

// ================================================================ 读取

/** 能力声明：哪些真接通、哪些还没交付、归谁。 */
export function fetchCapabilities(): Promise<TextCapabilities> {
  return api<TextCapabilities>(`${base}/capabilities`);
}

/** 概览：几个数。 */
export function fetchOverview(): Promise<TextOverview> {
  return api<TextOverview>(`${base}/overview`);
}

/** 术语表列表（真筛选）。 */
export function fetchGlossary(params: {
  termType?: string | undefined;
  locale?: string | undefined;
  enabled?: boolean | undefined;
}): Promise<TextGlossaryRow[]> {
  return api<TextGlossaryRow[]>(`${base}/glossary?${query(params)}`);
}

/** 登记或修改一条术语。 */
export function saveGlossary(input: TextGlossaryInput): Promise<TextGlossaryRow> {
  return api<TextGlossaryRow>(`${base}/glossary`, 'POST', input);
}

/** 删除一条术语。 */
export function deleteGlossary(id: string): Promise<void> {
  return api<void>(`${base}/glossary/${id}`, 'DELETE');
}

/** 违禁词列表（真筛选）。 */
export function fetchBannedTerms(params: {
  category?: string | undefined;
  severity?: string | undefined;
  locale?: string | undefined;
  enabled?: boolean | undefined;
}): Promise<TextBannedTermRow[]> {
  return api<TextBannedTermRow[]>(`${base}/banned-terms?${query(params)}`);
}

/** 登记或修改一条违禁词。 */
export function saveBannedTerm(input: TextBannedTermInput): Promise<TextBannedTermRow> {
  return api<TextBannedTermRow>(`${base}/banned-terms`, 'POST', input);
}

/** 删除一条违禁词。 */
export function deleteBannedTerm(id: string): Promise<void> {
  return api<void>(`${base}/banned-terms/${id}`, 'DELETE');
}

/** 长度与字符集规则列表。 */
export function fetchRules(params: {
  contentType?: string | undefined;
  locale?: string | undefined;
}): Promise<TextRuleRow[]> {
  return api<TextRuleRow[]>(`${base}/rules?${query(params)}`);
}

/** 登记或修改一条规则。 */
export function saveRule(input: TextRuleInput): Promise<TextRuleRow> {
  return api<TextRuleRow>(`${base}/rules`, 'POST', input);
}

/** 删除一条规则。 */
export function deleteRule(id: string): Promise<void> {
  return api<void>(`${base}/rules/${id}`, 'DELETE');
}

/** 客服回复模板列表。 */
export function fetchTemplates(params: {
  contentType?: string | undefined;
  locale?: string | undefined;
  enabled?: boolean | undefined;
}): Promise<TextTemplateRow[]> {
  return api<TextTemplateRow[]>(`${base}/templates?${query(params)}`);
}

/** 登记或修改一个模板。 */
export function saveTemplate(input: TextTemplateInput): Promise<TextTemplateRow> {
  return api<TextTemplateRow>(`${base}/templates`, 'POST', input);
}

/** 删除一个模板。 */
export function deleteTemplate(id: string): Promise<void> {
  return api<void>(`${base}/templates/${id}`, 'DELETE');
}

/** 变量绑定：把模板渲染成一封**可发出去**的回复。 */
export function fetchTemplateBinding(
  templateId: string,
  values: Record<string, string>,
): Promise<TextTemplateBinding> {
  return api<TextTemplateBinding>(`${base}/templates/${templateId}/binding?${query(values)}`);
}

/** 翻译任务列表（真分页 + 真筛选）。 */
export function fetchTasks(
  page: number,
  size: number,
  params: TaskParams,
): Promise<TextTaskPage> {
  return api<TextTaskPage>(
    `${base}/tasks?${query({ ...params, page: String(page), size: String(size) })}`,
  );
}

/** 任务详情（任务 + 当前草稿 + 历史版本 + 检查结论 + 规避建议 + 置信度）。 */
export function fetchTaskDetail(taskId: string): Promise<TextTaskDetail> {
  return api<TextTaskDetail>(`${base}/tasks/${taskId}`);
}

/** 已发布的文案（**下游可取的那一份**）。 */
export function fetchPublished(
  contentType: string | undefined,
  page: number,
  size: number,
): Promise<TextTaskRow[]> {
  return api<TextTaskRow[]>(
    `${base}/published?${query({ contentType, page: String(page), size: String(size) })}`,
  );
}

/** 可选目标语言（来自 3.4.3 多语言）。 */
export function fetchLocales(): Promise<TextLocaleView> {
  return api<TextLocaleView>(`${base}/locales`);
}

/** 可拿来翻译的上游源文本（3.4.3 多语言 / 4.2.1.1 编辑器）。 */
export function fetchSourceCandidates(
  refType: string,
  refId: string,
): Promise<TextSourceCandidateView> {
  return api<TextSourceCandidateView>(
    `${base}/source-candidates?${query({ refType, refId })}`,
  );
}

// ================================================================ 写入

/** 建一个翻译任务。 */
export function createTask(input: TextTaskInput): Promise<TextTaskRow> {
  return api<TextTaskRow>(`${base}/tasks`, 'POST', input);
}

/**
 * 走 AI 通道生成 / 翻译。
 *
 * 🔴 本仓当前 AI 供应商**未接入** ⇒ 后端如实返回 **409**，**一个字都不写**。
 * 这里**不**做任何「本地兜底生成」—— 那等于伪造一条译文。
 */
export function generateTranslation(taskId: string): Promise<TextGenerationOutcome> {
  return api<TextGenerationOutcome>(`${base}/tasks/${taskId}/generate`, 'POST');
}

/** 人工录入 / 编辑译文（追加一个新版本）。 */
export function saveDraft(taskId: string, input: TextDraftInput): Promise<TextTaskDetail> {
  return api<TextTaskDetail>(`${base}/tasks/${taskId}/drafts`, 'POST', input);
}

/** 人工确认（**发布的硬门槛**）。 */
export function confirmTranslation(taskId: string): Promise<TextTaskDetail> {
  return api<TextTaskDetail>(`${base}/tasks/${taskId}/confirm`, 'POST');
}

/** 发布（必须已人工确认，且不允许带 FAIL 的检查结论）。 */
export function publishTranslation(taskId: string): Promise<TextTaskDetail> {
  return api<TextTaskDetail>(`${base}/tasks/${taskId}/publish`, 'POST');
}

/** 驳回（终态）。 */
export function rejectTranslation(taskId: string, reason: string): Promise<TextTaskDetail> {
  return api<TextTaskDetail>(`${base}/tasks/${taskId}/reject?${query({ reason })}`, 'POST');
}

// ================================================================ 展示口径

/** 语气档位。 */
export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

/**
 * 单条检查结论的语气。
 *
 * 🔴 **SKIPPED 是「没查」，不是「通过」** —— 显示成绿色「通过」等于骗人。
 * 它必须是灰色，而且带一句说明。
 */
export function verdictTone(verdict: string): Tone {
  if (verdict === 'PASS') return 'ok';
  if (verdict === 'WARN') return 'warn';
  if (verdict === 'FAIL') return 'danger';
  return 'muted';
}

/** 单条检查结论的中文说法。 */
export function verdictLabel(verdict: string): string {
  if (verdict === 'PASS') return '通过';
  if (verdict === 'WARN') return '警告';
  if (verdict === 'FAIL') return '失败';
  if (verdict === 'SKIPPED') return '不适用';
  return verdict;
}

/**
 * 一维置信度的语气。
 *
 * 🔴 **null（不适用）不是 0**：没有术语可保护的任务，术语覆盖率**没有信息量**，
 * 把「不适用」显示成「0%」等于在惩罚一件本来就不该扣分的事。
 */
export function dimensionTone(value: string | number | null | undefined): Tone {
  if (value === null || value === undefined) return 'muted';
  const numeric = Number(value);
  if (Number.isNaN(numeric)) return 'muted';
  if (numeric >= 0.8) return 'ok';
  if (numeric >= 0.5) return 'warn';
  return 'danger';
}

/** 一维置信度的中文说法：**不适用**就显示「不适用」，不显示 0%。 */
export function dimensionLabel(value: string | number | null | undefined): string {
  if (value === null || value === undefined) return '不适用';
  return `${Math.round(Number(value) * 1000) / 10}%`;
}

/**
 * 任务状态的语气。
 *
 * <p>已发布 = 绿；待复核 / 待生成 = 灰；已确认 = 蓝（还没发出去）；
 * 已驳回 = 红。
 */
export function statusTone(status: string): Tone {
  if (status === 'PUBLISHED') return 'ok';
  if (status === 'REJECTED') return 'danger';
  if (status === 'CONFIRMED') return 'warn';
  return 'muted';
}

/**
 * 这一版译文**能不能发**。
 *
 * 🔴 门槛是**两道**，缺一不可：
 *   1. 已经过人工确认（CONFIRMED）；
 *   2. 没有 FAIL 的检查结论（确定性检查必须真的拦得住，否则它只是一行提示）。
 */
export function publishGate(detail: TextTaskDetail | null): {
  allowed: boolean;
  reason: string;
} {
  if (!detail) return { allowed: false, reason: '还没有选中任何译文。' };
  const task = detail.task;
  if (task.status === 'PUBLISHED') {
    return { allowed: false, reason: '这条译文已经发布，不能重复发布。' };
  }
  if (task.status === 'REJECTED') {
    return { allowed: false, reason: '这条任务已被驳回，请新建一条任务。' };
  }
  if (task.status !== 'CONFIRMED') {
    return { allowed: false, reason: '还没有经过人工确认 —— 必须先确认才能发布。' };
  }
  const failed = (detail.checks ?? []).filter((c) => c.verdict === 'FAIL');
  if (failed.length > 0) {
    return {
      allowed: false,
      reason: `还有 ${failed.length} 条检查失败未解决，不能发布。`,
    };
  }
  return { allowed: true, reason: '可以发布。' };
}

/**
 * 一条规避建议的可执行说明。
 *
 * <p>🔴 「建议注意合规」运营没法执行 —— 必须说清**改哪个词**。
 */
export function violationHint(violation: TextViolation): string {
  if (!violation.suggestion) return violation.detail;
  return violation.suggestion;
}

/**
 * 置信度的**逐维**展示行（前端直接显示，不自己编）。
 *
 * <p>后端已经算好每一维的依据；这里只是把它排成一行行人能读的。
 */
export function confidenceRows(confidence: TextConfidence | null): {
  label: string;
  value: string;
  tone: Tone;
}[] {
  if (!confidence) return [];
  return [
    { label: '术语覆盖率', value: dimensionLabel(confidence.termCoverage), tone: dimensionTone(confidence.termCoverage) },
    {
      label: '占位符完整率',
      value: dimensionLabel(confidence.placeholderIntegrity),
      tone: dimensionTone(confidence.placeholderIntegrity),
    },
    {
      label: '校验通过率',
      value: dimensionLabel(confidence.checkPassRate),
      tone: dimensionTone(confidence.checkPassRate),
    },
    {
      label: '长度合规率',
      value: dimensionLabel(confidence.lengthCompliance),
      tone: dimensionTone(confidence.lengthCompliance),
    },
  ];
}

/**
 * 草稿版本的说明（人工可编辑的**版本**，不是流水）。
 */
export function draftOriginLabel(origin: string): string {
  if (origin === 'MANUAL') return '人工录入';
  if (origin === 'AI') return 'AI 生成';
  return origin;
}

/**
 * 检查结论的排序口径：**失败在前**，其次警告，最后通过。
 *
 * <p>🔴 运营看检查结果时最关心的是「哪条不过」，不是「哪条过了」——
 * 顺序错了，失败的那条会被埋在一堆绿色里。
 */
export function sortChecks(checks: TextCheckRow[]): TextCheckRow[] {
  const rank = (verdict: string) =>
    verdict === 'FAIL' ? 0 : verdict === 'WARN' ? 1 : verdict === 'SKIPPED' ? 2 : 3;
  return [...checks].sort((a, b) => rank(a.verdict) - rank(b.verdict));
}
