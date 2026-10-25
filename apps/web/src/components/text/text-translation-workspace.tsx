'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  PageHeader,
  SectionCard,
  Segmented,
  StatCard,
} from '@starcon/ui';
import { CircleAlert, Languages, Loader2, RefreshCw, ShieldCheck, TriangleAlert } from 'lucide-react';
import {
  confirmTranslation,
  createTask,
  deleteBannedTerm,
  deleteGlossary,
  deleteRule,
  deleteTemplate,
  draftOriginLabel,
  fetchBannedTerms,
  fetchCapabilities,
  fetchGlossary,
  fetchLocales,
  fetchOverview,
  fetchPublished,
  fetchRules,
  fetchSourceCandidates,
  fetchTaskDetail,
  fetchTasks,
  fetchTemplateBinding,
  fetchTemplates,
  generateTranslation,
  publishGate,
  publishTranslation,
  rejectTranslation,
  saveBannedTerm,
  saveDraft,
  saveGlossary,
  saveRule,
  saveTemplate,
  sortChecks,
  statusTone,
  verdictLabel,
  verdictTone,
  type TaskParams,
  type Tone,
} from './text-translation-model';
import type {
  TextBannedTermRow,
  TextCapabilities,
  TextCheckRow,
  TextGlossaryRow,
  TextLocaleView,
  TextOverview,
  TextRuleRow,
  TextSourceCandidateView,
  TextTaskDetail,
  TextTaskPage,
  TextTaskRow,
  TextTemplateRow,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 18.4.2.1「文本与翻译」工作区（卷 18 前端入口 `/text`）。
 *
 * 蓝图：「**AI 文本能力**：多语言翻译（标题/描述/客服话术，支持保留品牌词与专业术语）、
 * 标题优化与生成、卖点提炼、违禁词规避建议、客服回复草稿。输入：源文本；目标语言；
 * 风格要求；术语表。输出：翻译/生成结果 + 置信度提示 + 可人工编辑。
 * **翻译结果必须人工确认后发布**，直接发机器翻译是品牌风险。」
 *
 * 🔴 页面上的六条纪律：
 *
 * 1. **AI 未接入就明说**：能力声明里那一维 `available=false`，「AI 生成」按钮点了会拿到
 *    409 与一段说明，页面**如实显示**这段说明 —— 不渲染成一个空白的译文框，
 *    也不在本地假装翻译。
 * 2. **置信度逐维显示**：四个维度分开，「不适用」显示成「不适用」而不是 0%。
 * 3. **发布门槛写在按钮旁边**：没确认 / 有 FAIL 时，发布按钮是禁用的，
 *    并写明**为什么**禁用（不是只灰着）。
 * 4. **检查结论失败在前**：失败 → 警告 → 不适用 → 通过。
 * 5. **上游读不到 ≠ 没有**：能力声明与上游区块按原样显示 `available=false` 并写明归谁。
 * 6. **加载 / 空 / 错误三态齐全**：空态区分「筛选无结果」与「还没有建过任务」。
 */

type Tab = 'tasks' | 'glossary' | 'banned' | 'rules' | 'templates' | 'published';

const PAGE_SIZE = 10;

/** 语气 → Badge 的 tone。 */
const BADGE_TONE: Record<Tone, 'neutral' | 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
};

const CONTENT_TYPES = [
  { value: 'TITLE', label: '标题' },
  { value: 'DESCRIPTION', label: '描述' },
  { value: 'HIGHLIGHT', label: '卖点' },
  { value: 'CUSTOMER_REPLY', label: '客服话术' },
];

const TASK_STATUSES = [
  { value: 'PENDING', label: '待生成' },
  { value: 'DRAFTED', label: '待复核' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'PUBLISHED', label: '已发布' },
  { value: 'REJECTED', label: '已驳回' },
];

const TERM_TYPES = [
  { value: 'BRAND', label: '品牌词' },
  { value: 'PROFESSIONAL', label: '专业术语' },
  { value: 'PRODUCT', label: '商品专名' },
];

const BANNED_CATEGORIES = [
  { value: 'ABSOLUTE', label: '绝对化用语' },
  { value: 'CLAIM', label: '功效或效果宣称' },
  { value: 'GUARANTEE', label: '承诺与担保' },
  { value: 'COMPETITOR', label: '竞品指向' },
  { value: 'CONTACT', label: '导流联系方式' },
  { value: 'INCENTIVE', label: '诱导好评' },
];

const CHARSETS = [
  { value: 'ANY', label: '不限制' },
  { value: 'LATIN', label: '不得含中日韩字符' },
  { value: 'CJK', label: '需含中日韩字符' },
];

interface TaskForm {
  contentType: string;
  sourceText: string;
  sourceLocale: string;
  targetLocale: string;
  styleRequirement: string;
}

const EMPTY_TASK: TaskForm = {
  contentType: 'DESCRIPTION',
  sourceText: '',
  sourceLocale: 'zh-CN',
  targetLocale: '',
  styleRequirement: '',
};

export function TextTranslationWorkspace() {
  const [tab, setTab] = useState<Tab>('tasks');

  const [capabilities, setCapabilities] = useState<TextCapabilities | null>(null);
  const [overview, setOverview] = useState<TextOverview | null>(null);
  const [locales, setLocales] = useState<TextLocaleView | null>(null);

  const [tasks, setTasks] = useState<TextTaskPage | null>(null);
  const [detail, setDetail] = useState<TextTaskDetail | null>(null);
  const [published, setPublished] = useState<TextTaskRow[]>([]);

  const [glossary, setGlossary] = useState<TextGlossaryRow[]>([]);
  const [bannedTerms, setBannedTerms] = useState<TextBannedTermRow[]>([]);
  const [rules, setRules] = useState<TextRuleRow[]>([]);
  const [templates, setTemplates] = useState<TextTemplateRow[]>([]);

  const [keyword, setKeyword] = useState('');
  const [contentType, setContentType] = useState('');
  const [status, setStatus] = useState('');
  const [targetLocale, setTargetLocale] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [draftText, setDraftText] = useState('');
  const [rejectReason, setRejectReason] = useState('');
  const [showTaskForm, setShowTaskForm] = useState(false);
  const [taskForm, setTaskForm] = useState<TaskForm>(EMPTY_TASK);
  const [sourceCandidates, setSourceCandidates] = useState<TextSourceCandidateView | null>(null);
  const [sourceError, setSourceError] = useState('');

  const fail = (e: unknown) => {
    const message = e instanceof Error ? e.message : '请求失败，请稍后重试';
    setError(message);
  };

  const loadTasks = useCallback(async () => {
    const params: TaskParams = { keyword, contentType, status, targetLocale };
    const result = await fetchTasks(pageIndex, PAGE_SIZE, params);
    setTasks(result);
  }, [keyword, contentType, status, targetLocale, pageIndex]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, ov, page] = await Promise.all([
        fetchCapabilities(),
        fetchOverview(),
        fetchTasks(pageIndex, PAGE_SIZE, { keyword, contentType, status, targetLocale }),
      ]);
      setCapabilities(caps);
      setOverview(ov);
      setTasks(page);
    } catch (e) {
      fail(e);
    } finally {
      setLoading(false);
    }
  }, [keyword, contentType, status, targetLocale, pageIndex]);

  useEffect(() => {
    void load();
  }, [load]);

  // 词表 / 规则 / 模板 / 已发布 / 目标语言：按需加载，切换标签页时再取。
  useEffect(() => {
    if (tab === 'glossary' && glossary.length === 0) {
      fetchGlossary({})
        .then(setGlossary)
        .catch(fail);
    }
    if (tab === 'banned' && bannedTerms.length === 0) {
      fetchBannedTerms({})
        .then(setBannedTerms)
        .catch(fail);
    }
    if (tab === 'rules' && rules.length === 0) {
      fetchRules({})
        .then(setRules)
        .catch(fail);
    }
    if (tab === 'templates' && templates.length === 0) {
      fetchTemplates({})
        .then(setTemplates)
        .catch(fail);
    }
    if (tab === 'published' && published.length === 0) {
      fetchPublished(undefined, 0, PAGE_SIZE)
        .then(setPublished)
        .catch(fail);
    }
  }, [tab, glossary.length, bannedTerms.length, rules.length, templates.length, published.length]);

  // 目标语言来自上游（3.4.3 多语言）；读不到就如实显示「读不到」，不假装没有语言。
  useEffect(() => {
    fetchLocales()
      .then(setLocales)
      .catch((e) => setError(e instanceof Error ? e.message : '目标语言读不到'));
  }, []);

  const openDetail = useCallback(async (taskId: string) => {
    setBusy(true);
    setError('');
    try {
      const result = await fetchTaskDetail(taskId);
      setDetail(result);
      setDraftText(result.draft?.targetText ?? '');
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }, []);

  const run = async (work: () => Promise<unknown>, ok: string) => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await work();
      setNotice(ok);
      await load();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const onGenerate = async (taskId: string) => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await generateTranslation(taskId);
      setNotice('AI 已生成译文。');
    } catch (e) {
      // 🔴 AI 未接入时后端如实返回 409 —— 这里**原样显示**它的说明，
      //    不做任何本地兜底生成（那等于伪造一条译文）。
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const loadCandidates = async (refType: string, refId: string) => {
    setSourceError('');
    setSourceCandidates(null);
    try {
      setSourceCandidates(await fetchSourceCandidates(refType, refId));
    } catch (e) {
      setSourceError(e instanceof Error ? e.message : '上游源文本读不到');
    }
  };

  const gate = useMemo(() => publishGate(detail), [detail]);
  const sortedChecks = useMemo(() => sortChecks(detail?.checks ?? []), [detail]);

  const hasFilter = Boolean(keyword || contentType || status || targetLocale);

  return (
    <div className="space-y-4">
      <PageHeader
        title="文本与翻译"
        description="多语言翻译、术语与品牌词保护、违禁词规避建议、客服回复草稿。翻译结果必须人工确认后才能发布。"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
            刷新
          </Button>
        }
      />

      {error && (
        <div className="text-destructive flex items-start gap-2 rounded-md border p-3 text-sm">
          <CircleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}
      {notice && (
        <div className="text-success flex items-start gap-2 rounded-md border p-3 text-sm">
          <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{notice}</span>
        </div>
      )}

      {overview && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="任务总数" value={overview.total} icon={<Languages className="h-4 w-4" />} />
          <StatCard label="待复核" value={overview.drafted} />
          <StatCard label="已确认" value={overview.confirmed} />
          <StatCard label="已发布" value={overview.published} />
        </div>
      )}

      <Segmented<Tab>
        value={tab}
        onChange={setTab}
        aria-label="文本与翻译分区"
        options={[
          { value: 'tasks', label: '翻译工作区' },
          { value: 'glossary', label: '术语表' },
          { value: 'banned', label: '违禁词' },
          { value: 'rules', label: '长度与字符集' },
          { value: 'templates', label: '客服话术模板' },
          { value: 'published', label: '已发布' },
        ]}
      />

      {tab === 'tasks' && (
        <>
          <SectionCard
            title="翻译任务"
            description="真分页 + 真筛选。状态机：待生成 → 待复核 → 已确认 → 已发布；已驳回是终态。"
            extra={
              <Button onClick={() => setShowTaskForm((v) => !v)}>
                <Languages className="h-4 w-4" />
                新建任务
              </Button>
            }
          >
            {showTaskForm && (
              <div className="border-border mb-4 space-y-3 rounded-md border p-4">
                <div className="grid gap-3 sm:grid-cols-2">
                  <label className="space-y-1 text-sm">
                    <span className="text-muted-foreground">内容类型</span>
                    <select
                      className="border-border bg-background h-9 w-full rounded-md border px-2 text-sm"
                      value={taskForm.contentType}
                      onChange={(e) => setTaskForm({ ...taskForm, contentType: e.target.value })}
                    >
                      {CONTENT_TYPES.map((t) => (
                        <option key={t.value} value={t.value}>
                          {t.label}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="space-y-1 text-sm">
                    <span className="text-muted-foreground">源语言</span>
                    <Input
                      value={taskForm.sourceLocale}
                      onChange={(e) => setTaskForm({ ...taskForm, sourceLocale: e.target.value })}
                    />
                  </label>
                  <label className="space-y-1 text-sm">
                    <span className="text-muted-foreground">目标语言</span>
                    <Input
                      placeholder="如 de / en-US"
                      value={taskForm.targetLocale}
                      onChange={(e) => setTaskForm({ ...taskForm, targetLocale: e.target.value })}
                    />
                  </label>
                  <label className="space-y-1 text-sm">
                    <span className="text-muted-foreground">风格要求</span>
                    <Input
                      placeholder="如 简洁口语"
                      value={taskForm.styleRequirement}
                      onChange={(e) =>
                        setTaskForm({ ...taskForm, styleRequirement: e.target.value })
                      }
                    />
                  </label>
                </div>
                <label className="block space-y-1 text-sm">
                  <span className="text-muted-foreground">源文本</span>
                  <textarea
                    className="border-border bg-background min-h-24 w-full rounded-md border p-2 text-sm"
                    value={taskForm.sourceText}
                    onChange={(e) => setTaskForm({ ...taskForm, sourceText: e.target.value })}
                  />
                </label>
                <div className="flex flex-wrap items-center gap-2">
                  <Button
                    disabled={busy}
                    onClick={() =>
                      void run(async () => {
                        await createTask({
                          contentType: taskForm.contentType,
                          sourceText: taskForm.sourceText,
                          sourceLocale: taskForm.sourceLocale,
                          targetLocale: taskForm.targetLocale,
                          styleRequirement: taskForm.styleRequirement,
                          upstreamRefType: '',
                          upstreamRefId: '',
                          templateId: '',
                        });
                        setShowTaskForm(false);
                        setTaskForm(EMPTY_TASK);
                      }, '任务已创建（待生成）。')
                    }
                  >
                    创建任务
                  </Button>
                  <Button variant="secondary" onClick={() => setShowTaskForm(false)}>
                    取消
                  </Button>
                </div>
              </div>
            )}

            <div className="mb-4 flex flex-wrap gap-2">
              <Input
                className="max-w-xs"
                placeholder="搜索单号或源文本"
                value={keyword}
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setPageIndex(0);
                }}
              />
              <select
                className="border-border bg-background h-9 rounded-md border px-2 text-sm"
                value={contentType}
                onChange={(e) => {
                  setContentType(e.target.value);
                  setPageIndex(0);
                }}
              >
                <option value="">全部类型</option>
                {CONTENT_TYPES.map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-2 text-sm"
                value={status}
                onChange={(e) => {
                  setStatus(e.target.value);
                  setPageIndex(0);
                }}
              >
                <option value="">全部状态</option>
                {TASK_STATUSES.map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-2 text-sm"
                value={targetLocale}
                onChange={(e) => {
                  setTargetLocale(e.target.value);
                  setPageIndex(0);
                }}
              >
                <option value="">全部目标语言</option>
                {(locales?.items ?? []).map((l) => (
                  <option key={l.locale} value={l.locale}>
                    {l.localeName || l.locale}
                  </option>
                ))}
              </select>
            </div>

            {loading && !tasks ? (
              <div className="text-muted-foreground flex items-center gap-2 py-10 text-sm">
                <Loader2 className="h-4 w-4 animate-spin" />
                正在加载翻译任务…
              </div>
            ) : tasks && tasks.items.length === 0 ? (
              <EmptyState
                icon={<Languages className="h-6 w-6" />}
                title={hasFilter ? '没有符合条件的任务' : '还没有建过翻译任务'}
                description={
                  hasFilter
                    ? '换个筛选条件再试一次。'
                    : '新建一条任务后，可以人工录入译文、走确定性检查，再人工确认发布。'
                }
              />
            ) : (
              <div className="space-y-2">
                {tasks?.items.map((task) => (
                  <button
                    key={task.id}
                    type="button"
                    onClick={() => void openDetail(task.id)}
                    className="border-border hover:bg-muted/40 flex w-full flex-wrap items-center gap-3 rounded-md border p-3 text-left"
                  >
                    <span className="font-mono text-sm">{task.taskNo}</span>
                    <Badge tone={BADGE_TONE[statusTone(task.status)]}>{task.statusLabel}</Badge>
                    <span className="text-copy-13">{task.contentTypeLabel}</span>
                    <span className="text-muted-foreground text-copy-13">
                      {task.sourceLocale} → {task.targetLocale}
                    </span>
                    <span className="text-muted-foreground min-w-0 flex-1 truncate text-copy-13">
                      {task.sourceText}
                    </span>
                    {task.checkFailed > 0 && (
                      <Badge tone="danger">{task.checkFailed} 条检查失败</Badge>
                    )}
                  </button>
                ))}
                {tasks && tasks.totalPages > 1 && (
                  <div className="flex items-center justify-between pt-2 text-sm">
                    <span className="text-muted-foreground">
                      第 {tasks.page + 1} / {tasks.totalPages} 页，共 {tasks.total} 条
                    </span>
                    <div className="flex gap-2">
                      <Button
                        variant="secondary"
                        disabled={tasks.page === 0}
                        onClick={() => setPageIndex((p) => Math.max(0, p - 1))}
                      >
                        上一页
                      </Button>
                      <Button
                        variant="secondary"
                        disabled={tasks.page + 1 >= tasks.totalPages}
                        onClick={() => setPageIndex((p) => p + 1)}
                      >
                        下一页
                      </Button>
                    </div>
                  </div>
                )}
              </div>
            )}
          </SectionCard>

          {detail && (
            <SectionCard
              title={`译文工作区 · ${detail.task.taskNo}`}
              description="源文本、译文草稿、确定性检查结论、规避建议与置信度。"
            >
              <div className="space-y-4">
                <div className="border-border space-y-2 rounded-md border p-3">
                  <div className="text-muted-foreground text-label-12">源文本</div>
                  <div className="text-copy-13 whitespace-pre-wrap">{detail.task.sourceText}</div>
                  <div className="text-muted-foreground text-label-12">
                    {detail.task.sourceLocale} → {detail.task.targetLocale}
                    {detail.task.styleRequirement ? ` · 风格：${detail.task.styleRequirement}` : ''}
                  </div>
                </div>

                <div className="flex flex-wrap gap-2">
                  <Button variant="secondary" disabled={busy} onClick={() => void onGenerate(detail.task.id)}>
                    AI 生成译文
                  </Button>
                  <Button
                    disabled={busy}
                    onClick={() =>
                      void run(async () => {
                        const result = await saveDraft(detail.task.id, { targetText: draftText });
                        setDetail(result);
                      }, '译文已保存，并重跑了确定性检查。')
                    }
                  >
                    保存译文（追加新版本）
                  </Button>
                  <Button
                    variant="secondary"
                    disabled={busy}
                    onClick={() =>
                      void run(async () => {
                        const result = await confirmTranslation(detail.task.id);
                        setDetail(result);
                      }, '已人工确认 —— 现在可以发布了。')
                    }
                  >
                    人工确认
                  </Button>
                  <Button
                    disabled={busy || !gate.allowed}
                    onClick={() =>
                      void run(async () => {
                        const result = await publishTranslation(detail.task.id);
                        setDetail(result);
                      }, '已发布。')
                    }
                  >
                    发布
                  </Button>
                  <Button
                    variant="secondary"
                    disabled={busy}
                    onClick={() =>
                      void run(async () => {
                        const result = await rejectTranslation(detail.task.id, rejectReason);
                        setDetail(result);
                      }, '已驳回。')
                    }
                  >
                    驳回
                  </Button>
                  <Input
                    className="max-w-56"
                    placeholder="驳回原因（驳回时必填）"
                    value={rejectReason}
                    onChange={(e) => setRejectReason(e.target.value)}
                  />
                </div>

                <div className="text-muted-foreground text-copy-13 flex items-center gap-2">
                  <span>发布闸：</span>
                  <Badge tone={BADGE_TONE[gate.allowed ? 'ok' : 'warn']}>{gate.reason}</Badge>
                </div>

                <label className="block space-y-1 text-sm">
                  <span className="text-muted-foreground">译文（可人工编辑，保存时追加新版本）</span>
                  <textarea
                    className="border-border bg-background min-h-32 w-full rounded-md border p-2 text-sm"
                    value={draftText}
                    onChange={(e) => setDraftText(e.target.value)}
                  />
                </label>

                {detail.confidence && (
                  <div className="space-y-2">
                    <div className="text-copy-13">
                      置信度总分：
                      <span className="font-medium">
                        {detail.confidence.total === null
                          ? '暂无'
                          : `${Math.round(detail.confidence.total * 1000) / 10}%`}
                      </span>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      {[
                        { label: '术语覆盖率', value: detail.confidence.termCoverage },
                        { label: '占位符完整率', value: detail.confidence.placeholderIntegrity },
                        { label: '校验通过率', value: detail.confidence.checkPassRate },
                        { label: '长度合规率', value: detail.confidence.lengthCompliance },
                      ].map((d) => (
                        <Badge
                          key={d.label}
                          tone={
                            BADGE_TONE[
                              d.value === null
                                ? 'muted'
                                : Number(d.value) >= 0.8
                                  ? 'ok'
                                  : Number(d.value) >= 0.5
                                    ? 'warn'
                                    : 'danger'
                            ]
                          }
                        >
                          {d.label}{' '}
                          {d.value === null
                            ? '不适用'
                            : `${Math.round(Number(d.value) * 1000) / 10}%`}
                        </Badge>
                      ))}
                    </div>
                    {detail.confidence.explanation.length > 0 && (
                      <ul className="text-muted-foreground list-disc space-y-1 pl-5 text-copy-13">
                        {detail.confidence.explanation.map((line) => (
                          <li key={line}>{line}</li>
                        ))}
                      </ul>
                    )}
                  </div>
                )}

                <div className="space-y-2">
                  <div className="text-copy-13 font-medium">确定性检查结论</div>
                  {sortedChecks.length === 0 ? (
                    <EmptyState
                      icon={<TriangleAlert className="h-6 w-6" />}
                      title="还没有检查结论"
                      description="保存一版译文后，会自动重跑术语 / 占位符 / 违禁词 / 长度 / 字符集五项检查。"
                    />
                  ) : (
                    <div className="space-y-1">
                      {sortedChecks.map((c) => (
                        <CheckLine key={c.id} check={c} />
                      ))}
                    </div>
                  )}
                </div>

                {detail.violations.length > 0 && (
                  <div className="space-y-2">
                    <div className="text-copy-13 font-medium">规避建议</div>
                    {detail.violations.map((v) => (
                      <div
                        key={`${v.ruleCode}-${v.matchedItem}`}
                        className="border-border rounded-md border p-3 text-copy-13"
                      >
                        <Badge tone={v.severity === 'BLOCK' ? 'danger' : 'warning'}>
                          {v.severity === 'BLOCK' ? '拦住发布' : '警告'}
                        </Badge>
                        <div className="mt-1">{v.suggestion}</div>
                        <div className="text-muted-foreground mt-1">{v.detail}</div>
                      </div>
                    ))}
                  </div>
                )}

                {detail.history.length > 0 && (
                  <div className="space-y-2">
                    <div className="text-copy-13 font-medium">译文版本</div>
                    {detail.history.map((d) => (
                      <div key={d.id} className="border-border rounded-md border p-3">
                        <div className="text-muted-foreground text-label-12">
                          第 {d.version} 版 · {draftOriginLabel(d.origin)} · {d.createdAt}
                        </div>
                        <div className="text-copy-13 mt-1 whitespace-pre-wrap">{d.targetText}</div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </SectionCard>
          )}
        </>
      )}

      {tab === 'glossary' && (
        <SectionCard
          title="术语表"
          description="品牌词 / 专业术语 / 商品专名。品牌词在译文里缺失会直接拦住发布。"
          extra={
            <Button
              disabled={busy}
              onClick={() =>
                void run(async () => {
                  await saveGlossary({
                    sourceTerm: '',
                    targetTerm: '',
                    termType: 'BRAND',
                    targetLocale: '',
                    caseSensitive: false,
                    enabled: true,
                    note: '',
                  });
                }, '已保存。')
              }
            >
              新增术语
            </Button>
          }
        >
          {glossary.length === 0 ? (
            <EmptyState
              icon={<Languages className="h-6 w-6" />}
              title="还没有登记术语"
              description="术语表是翻译质量的第一道保障：品牌词必须在译文里原样保住。"
            />
          ) : (
            <div className="space-y-2">
              {glossary.map((g) => (
                <div
                  key={g.id}
                  className="border-border flex flex-wrap items-center gap-3 rounded-md border p-3"
                >
                  <span className="font-medium">{g.sourceTerm}</span>
                  <span className="text-muted-foreground">→</span>
                  <span>{g.targetTerm || '（保形态，不指定译法）'}</span>
                  <Badge tone="info">{g.termTypeLabel}</Badge>
                  {g.targetLocale && <Badge tone="neutral">{g.targetLocale}</Badge>}
                  <Button
                    variant="secondary"
                    disabled={busy}
                    onClick={() =>
                      void run(async () => {
                        await deleteGlossary(g.id);
                        setGlossary(await fetchGlossary({}));
                      }, '已删除。')
                    }
                  >
                    删除
                  </Button>
                </div>
              ))}
            </div>
          )}
        </SectionCard>
      )}

      {tab === 'banned' && (
        <SectionCard
          title="违禁词"
          description="租户自维护的违禁词表。BLOCK 级命中即拦住发布；规避建议基于真实命中检测。"
          extra={
            <Button
              disabled={busy}
              onClick={() =>
                void run(async () => {
                  await saveBannedTerm({
                    term: '',
                    category: 'ABSOLUTE',
                    severity: 'BLOCK',
                    targetLocale: '',
                    enabled: true,
                    note: '',
                  });
                }, '已保存。')
              }
            >
              新增违禁词
            </Button>
          }
        >
          {bannedTerms.length === 0 ? (
            <EmptyState
              icon={<TriangleAlert className="h-6 w-6" />}
              title="还没有登记违禁词"
              description="系统内置合规红线始终生效；这里放的是你自己店铺的词表。"
            />
          ) : (
            <div className="space-y-2">
              {bannedTerms.map((b) => (
                <div
                  key={b.id}
                  className="border-border flex flex-wrap items-center gap-3 rounded-md border p-3"
                >
                  <span className="font-medium">{b.term}</span>
                  <Badge tone="info">{b.categoryLabel}</Badge>
                  <Badge tone={b.severity === 'BLOCK' ? 'danger' : 'warning'}>
                    {b.severity === 'BLOCK' ? '拦住发布' : '只警告'}
                  </Badge>
                  {b.targetLocale && <Badge tone="neutral">{b.targetLocale}</Badge>}
                  <Button
                    variant="secondary"
                    disabled={busy}
                    onClick={() =>
                      void run(async () => {
                        await deleteBannedTerm(b.id);
                        setBannedTerms(await fetchBannedTerms({}));
                      }, '已删除。')
                    }
                  >
                    删除
                  </Button>
                </div>
              ))}
            </div>
          )}
        </SectionCard>
      )}

      {tab === 'rules' && (
        <SectionCard
          title="长度与字符集规则"
          description="按内容类型 × 目标语言配置。没配规则 ⇒ 该维度显式记「不适用」，不参与置信度。"
          extra={
            <Button
              disabled={busy}
              onClick={() =>
                void run(async () => {
                  await saveRule({
                    contentType: 'TITLE',
                    targetLocale: '',
                    minLength: 0,
                    maxLength: 0,
                    requireCharset: 'ANY',
                    terminologyStrict: true,
                    enabled: true,
                    note: '',
                  });
                }, '已保存。')
              }
            >
              新增规则
            </Button>
          }
        >
          {rules.length === 0 ? (
            <EmptyState
              icon={<Languages className="h-6 w-6" />}
              title="还没有配置长度规则"
              description="不同平台、不同语言的标题长度上限完全不同，请按自己的实际情况配置。"
            />
          ) : (
            <div className="space-y-2">
              {rules.map((r) => (
                <div
                  key={r.id}
                  className="border-border flex flex-wrap items-center gap-3 rounded-md border p-3"
                >
                  <Badge tone="info">{r.contentType}</Badge>
                  <span>{r.targetLocale || '全部语言'}</span>
                  <span className="text-muted-foreground">
                    {r.minLength ?? '不限'} ~ {r.maxLength ?? '不限'} 字符
                  </span>
                  <Badge tone="neutral">{r.requireCharsetLabel}</Badge>
                  <Button
                    variant="secondary"
                    disabled={busy}
                    onClick={() =>
                      void run(async () => {
                        await deleteRule(r.id);
                        setRules(await fetchRules({}));
                      }, '已删除。')
                    }
                  >
                    删除
                  </Button>
                </div>
              ))}
            </div>
          )}
        </SectionCard>
      )}

      {tab === 'templates' && (
        <SectionCard
          title="客服回复草稿模板"
          description="模板变量是封闭词表：服务端确定填不上的变量存不进来。"
          extra={
            <Button
              disabled={busy}
              onClick={() =>
                void run(async () => {
                  await saveTemplate({
                    templateCode: '',
                    templateName: '',
                    body: '',
                    contentType: 'CUSTOMER_REPLY',
                    targetLocale: '',
                    enabled: true,
                    note: '',
                  });
                }, '已保存。')
              }
            >
              新增模板
            </Button>
          }
        >
          {templates.length === 0 ? (
            <EmptyState
              icon={<Languages className="h-6 w-6" />}
              title="还没有客服话术模板"
              description="模板 + 变量绑定 + 确定性检查，是 AI 未接入时也能真做完的那条路径。"
            />
          ) : (
            <div className="space-y-3">
              {templates.map((t) => (
                <div key={t.id} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center gap-3">
                    <span className="font-medium">{t.templateName}</span>
                    <span className="text-muted-foreground font-mono text-label-12">
                      {t.templateCode}
                    </span>
                    {t.targetLocale && <Badge tone="neutral">{t.targetLocale}</Badge>}
                    <Button
                      variant="secondary"
                      disabled={busy}
                      onClick={() =>
                        void run(async () => {
                          const binding = await fetchTemplateBinding(t.id, {});
                          if (binding.missing.length > 0) {
                            setNotice(`还缺这些变量：${binding.missing.join(' / ')}`);
                          }
                          setDraftText(binding.body);
                        }, '已渲染。')
                      }
                    >
                      渲染模板
                    </Button>
                    <Button
                      variant="secondary"
                      disabled={busy}
                      onClick={() =>
                        void run(async () => {
                          await deleteTemplate(t.id);
                          setTemplates(await fetchTemplates({}));
                        }, '已删除。')
                      }
                    >
                      删除
                    </Button>
                  </div>
                  <div className="text-copy-13 mt-2 whitespace-pre-wrap">{t.body}</div>
                  <div className="text-muted-foreground mt-1 text-label-12">
                    变量：{t.variables.join(' / ') || '（无）'}
                  </div>
                </div>
              ))}
            </div>
          )}
        </SectionCard>
      )}

      {tab === 'published' && (
        <SectionCard
          title="已发布的文案"
          description="下游可取的那一份 —— 只有经过人工确认并已发布的行才会出现在这里。"
        >
          {published.length === 0 ? (
            <EmptyState
              icon={<Languages className="h-6 w-6" />}
              title="还没有已发布的译文"
              description="译文必须先经过人工确认，才能发布到这里。"
            />
          ) : (
            <div className="space-y-2">
              {published.map((t) => (
                <div
                  key={t.id}
                  className="border-border flex flex-wrap items-center gap-3 rounded-md border p-3"
                >
                  <span className="font-mono text-sm">{t.taskNo}</span>
                  <Badge tone="success">已发布</Badge>
                  <span className="text-copy-13">{t.contentTypeLabel}</span>
                  <span className="text-muted-foreground text-copy-13">
                    {t.sourceLocale} → {t.targetLocale}
                  </span>
                  <span className="min-w-0 flex-1 truncate text-copy-13">
                    {t.currentDraftText}
                  </span>
                </div>
              ))}
            </div>
          )}
        </SectionCard>
      )}

      {capabilities && (
        <SectionCard title="能力声明" description="哪些真接通、哪些还没交付、归谁。">
          <div className="space-y-2">
            {capabilities.capabilities.map((c) => (
              <div
                key={`${c.moduleId}-${c.name}`}
                className="border-border flex flex-wrap items-start gap-3 rounded-md border p-3"
              >
                <Badge tone={c.available ? 'success' : 'danger'}>
                  {c.available ? '已接通' : '未接入'}
                </Badge>
                <div className="min-w-0 flex-1">
                  <div className="text-copy-13 font-medium">{c.name}</div>
                  <div className="text-muted-foreground text-copy-13">{c.evidence}</div>
                  {c.note && (
                    <div className="text-muted-foreground mt-1 text-label-12">{c.note}</div>
                  )}
                </div>
                {!c.available && c.ownerModule && (
                  <Badge tone="neutral">归 {c.ownerModule}</Badge>
                )}
              </div>
            ))}
          </div>
          {capabilities.gaps.length > 0 && (
            <div className="mt-4 space-y-2">
              <div className="text-copy-13 font-medium">未交付的兄弟 / 下游</div>
              {capabilities.gaps.map((g) => (
                <div
                  key={`${g.moduleId}-${g.name}`}
                  className="border-border flex flex-wrap items-center gap-3 rounded-md border p-3"
                >
                  <Badge tone="neutral">{g.moduleId}</Badge>
                  <span className="text-copy-13">{g.name}</span>
                  <span className="text-muted-foreground text-label-12">{g.relation}</span>
                  <span className="text-muted-foreground flex-1 text-label-12">{g.note}</span>
                </div>
              ))}
            </div>
          )}
        </SectionCard>
      )}

      <SectionCard
        title="上游源文本"
        description="可拿来翻译的文案来自 3.4.3 多语言内容与 4.2.1.1 刊登编辑器（跨服务只读）。"
      >
        <div className="flex flex-wrap items-end gap-2">
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">上游类型</span>
            <select
              className="border-border bg-background h-9 rounded-md border px-2 text-sm"
              defaultValue="CATALOG_SPU"
              id="source-ref-type"
            >
              <option value="CATALOG_SPU">商品 SPU（3.4.3 多语言）</option>
              <option value="CATALOG_SKU">商品 SKU（3.4.3 多语言）</option>
              <option value="CHANNEL_LISTING">刊登草稿（4.2.1.1 编辑器）</option>
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">上游 id</span>
            <Input id="source-ref-id" placeholder="填上游的 UUID" />
          </label>
          <Button
            onClick={() => {
              const type = (
                document.getElementById('source-ref-type') as HTMLSelectElement | null
              )?.value;
              const id = (document.getElementById('source-ref-id') as HTMLInputElement | null)?.value;
              if (!type || !id) {
                setSourceError('上游类型与上游 id 都要填。');
                return;
              }
              void loadCandidates(type, id);
            }}
          >
            读取源文本
          </Button>
        </div>
        {sourceError && (
          <div className="text-destructive mt-3 flex items-start gap-2 rounded-md border p-3 text-sm">
            <CircleAlert className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{sourceError}</span>
          </div>
        )}
        {sourceCandidates && (
          <div className="mt-3 space-y-2">
            <div className="text-muted-foreground text-label-12">{sourceCandidates.source}</div>
            {sourceCandidates.items.length === 0 ? (
              <EmptyState
                icon={<Languages className="h-6 w-6" />}
                title="这个上游没有可翻译的文案"
                description="上游确实返回了空集合（不是读不到）。"
              />
            ) : (
              sourceCandidates.items.map((c) => (
                <div key={`${c.refType}-${c.refId}-${c.locale}`} className="border-border rounded-md border p-3">
                  <div className="text-copy-13 font-medium">{c.name || '（无标题）'}</div>
                  <div className="text-muted-foreground text-label-12">{c.locale}</div>
                  <div className="text-copy-13 mt-1 whitespace-pre-wrap">{c.description}</div>
                </div>
              ))
            )}
          </div>
        )}
      </SectionCard>
    </div>
  );
}

/** 一条检查结论（失败 / 警告 / 不适用 / 通过分开显示）。 */
function CheckLine({ check }: { check: TextCheckRow }) {
  return (
    <div className="border-border flex flex-wrap items-start gap-2 rounded-md border p-2">
      <Badge tone={BADGE_TONE[verdictTone(check.verdict)]}>{verdictLabel(check.verdict)}</Badge>
      <span className="text-copy-13 font-medium">{check.ruleLabel}</span>
      {check.matchedItem && (
        <span className="text-copy-13 font-mono">{check.matchedItem}</span>
      )}
      <span className="text-muted-foreground flex-1 text-copy-13">{check.detail}</span>
    </div>
  );
}
