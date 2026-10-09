'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import { Link2, Loader2, Plus, RefreshCw, Save, Trash2 } from 'lucide-react';
import type {
  EntryHitRateRow,
  EntryTranslationInput,
  KnowledgeBaseOverview,
  KnowledgeEntryDetail,
  KnowledgeEntryInput,
  RuleVersionRow,
} from '../../../../../packages/api-contracts/src/catalog';
import { Editor, Field, control } from '../common/fields';
import { useIdentity } from '../iam/identity-shell';
import {
  ENTRY_TYPES,
  HIT_RATE_SORTS,
  MAX_BODY_LENGTH,
  PAGE_SIZE,
  PLATFORMS,
  REVIEW_STATUSES,
  RULE_STATUSES,
  TRANSLATION_ORIGINS,
  TRANSLATION_STATUSES,
  USAGE_OUTCOMES,
  bindEntry,
  bodyProblem,
  codeProblem,
  createRuleVersion,
  deleteEntry,
  deleteRuleVersion,
  emptyEntryInput,
  emptyTranslationInput,
  entryEmptyHint,
  entryTypeLabel,
  errorText,
  fetchEntry,
  fetchEntries,
  fetchHitRates,
  fetchKnowledgeOverview,
  fetchReviewTasks,
  fetchRuleVersions,
  fetchTranslations,
  fetchUsages,
  keywordsProblem,
  normalizeLocale,
  outcomeHint,
  outcomeLabel,
  outcomeTone,
  percentText,
  platformLabel,
  placeholderProblem,
  placeholdersOf,
  recordUsage,
  reviewEmptyHint,
  reviewStateLabel,
  reviewStatusLabel,
  reviewStatusTone,
  reviewTaskSummary,
  ruleEmptyHint,
  ruleStatusLabel,
  ruleStatusTone,
  saveEntry,
  saveTranslation,
  setEntryEnabled,
  titleProblem,
  unbindEntry,
  updateReviewTask,
  updateRuleVersion,
  usageEmptyHint,
} from './knowledge-model';

type Tab = 'entries' | 'rules' | 'reviews' | 'usage';

/**
 * 11.4.2.2 客服知识库与话术工作区。
 *
 * <p>页面按「先有政策版本 → 配话术并挂上去 → 政策改版后按待办复核 → 看使用效果」排，
 * 这个顺序就是蓝图那句话的执行顺序：
 *
 * <ul>
 *   <li><b>话术库</b> —— 常见问题标准答法 / 平台政策说明 / 退换货话术，带触发词与多语言版本。
 *   <li><b>平台规则版本</b> —— 政策条目的版本台账。<strong>把某一版标记为「已被接替」，
 *       挂在它上面的话术会自动各生成一条待复核任务</strong>（DB 触发器，不是页面行为）。
 *   <li><b>待复核任务</b> —— 每行点名<b>是哪一版接替了它</b>与两版的变更摘要，
 *       没有这两样的话「政策改版了」不是可执行的指令。
 *   <li><b>使用效果</b> —— 逐条引用记录 + 命中率排行。
 * </ul>
 *
 * <p>界面上反复强调三件事：
 *
 * <ol>
 *   <li><b>命中率的分母是启用话术的总条数</b>，不是被引用次数 ——
 *       所以「多少条话术根本没人用」永远看得见。</li>
 *   <li><b>「被驳回」是坏话术的信号</b>，不是「没效果」，页面上单独标红并可一键筛。</li>
 *   <li><b>译文占位符必须与源语言一致</b>，保存前就拦，并说清是漏抄还是多出。</li>
 * </ol>
 */
export function KnowledgeWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('catalog.assets.update');

  const [tab, setTab] = useState<Tab>('entries');
  const [overview, setOverview] = useState<KnowledgeBaseOverview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);

  // ------------------------------------------------------------ 话术库
  const [entries, setEntries] = useState<Awaited<ReturnType<typeof fetchEntries>> | null>(null);
  const [keyword, setKeyword] = useState('');
  const [entryType, setEntryType] = useState('');
  const [enabledFilter, setEnabledFilter] = useState('');
  const [reviewFilter, setReviewFilter] = useState('');
  const [entryPage, setEntryPage] = useState(0);
  const [detail, setDetail] = useState<KnowledgeEntryDetail | null>(null);
  const [editing, setEditing] = useState<KnowledgeEntryInput | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingTranslation, setEditingTranslation] = useState<EntryTranslationInput | null>(null);
  const [editingTranslationId, setEditingTranslationId] = useState<string | null>(null);

  // ------------------------------------------------------------ 平台规则版本
  const [rules, setRules] = useState<Awaited<ReturnType<typeof fetchRuleVersions>> | null>(null);
  const [ruleKeyword, setRuleKeyword] = useState('');
  const [rulePlatform, setRulePlatform] = useState('');
  const [ruleStatus, setRuleStatus] = useState('');
  const [rulePage, setRulePage] = useState(0);
  const [ruleEditor, setRuleEditor] = useState<{
    id: string | null;
    platform: string;
    policyKey: string;
    versionLabel: string;
    title: string;
    changeSummary: string;
    effectiveFrom: string;
  } | null>(null);
  const [bindingRuleId, setBindingRuleId] = useState('');

  // ------------------------------------------------------------ 待复核
  const [tasks, setTasks] = useState<Awaited<ReturnType<typeof fetchReviewTasks>> | null>(null);
  const [taskStatus, setTaskStatus] = useState('PENDING');
  const [taskPlatform, setTaskPlatform] = useState('');
  const [taskPage, setTaskPage] = useState(0);

  // ------------------------------------------------------------ 使用效果
  const [hitRates, setHitRates] = useState<EntryHitRateRow[]>([]);
  const [hitSort, setHitSort] = useState('');
  const [usages, setUsages] = useState<Awaited<ReturnType<typeof fetchUsages>> | null>(null);
  const [outcomeFilter, setOutcomeFilter] = useState('');
  const [usagePage, setUsagePage] = useState(0);

  const load = useCallback(async () => {
    try {
      const [o, e, r, t, h, u] = await Promise.all([
        fetchKnowledgeOverview(),
        fetchEntries({
          keyword: keyword || null,
          entryType: entryType || null,
          enabled: enabledFilter || null,
          reviewState: reviewFilter || null,
          page: entryPage,
          size: PAGE_SIZE,
        }),
        fetchRuleVersions({
          keyword: ruleKeyword || null,
          platform: rulePlatform || null,
          status: ruleStatus || null,
          page: rulePage,
          size: PAGE_SIZE,
        }),
        fetchReviewTasks({
          keyword: null,
          reviewStatus: taskStatus || null,
          platform: taskPlatform || null,
          page: taskPage,
          size: PAGE_SIZE,
        }),
        fetchHitRates(hitSort || undefined, 50),
        fetchUsages({
          keyword: null,
          outcome: outcomeFilter || null,
          entryId: null,
          page: usagePage,
          size: PAGE_SIZE,
        }),
      ]);
      setOverview(o);
      setEntries(e);
      setRules(r);
      setTasks(t);
      setHitRates(h);
      setUsages(u);
      setError(null);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setLoading(false);
    }
  }, [
    keyword,
    entryType,
    enabledFilter,
    reviewFilter,
    entryPage,
    ruleKeyword,
    rulePlatform,
    ruleStatus,
    rulePage,
    taskStatus,
    taskPlatform,
    taskPage,
    hitSort,
    outcomeFilter,
    usagePage,
  ]);

  useEffect(() => {
    void load();
  }, [load]);

  // ------------------------------------------------------------ 话术 CRUD

  function entryFormProblem(input: KnowledgeEntryInput): string | null {
    return (
      codeProblem(input.code) ??
      titleProblem(input.title) ??
      bodyProblem(input.body) ??
      keywordsProblem(input.triggerKeywords ?? '')
    );
  }

  async function submitEntry() {
    if (!editing) return;
    const problem = entryFormProblem(editing);
    if (problem) {
      setActionError(problem);
      return;
    }
    setBusy(true);
    try {
      const saved = await saveEntry(editingId, editing);
      setEditing(null);
      setEditingId(null);
      setNotice(`话术「${editing.code}」已保存。`);
      await load();
      await openDetail(saved.id);
    } catch (err) {
      setActionError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  async function openDetail(id: string) {
    setActionError(null);
    try {
      const [d, translations] = await Promise.all([fetchEntry(id), fetchTranslations(id)]);
      setDetail({ ...d, translations });
    } catch (err) {
      setActionError(errorText(err));
    }
  }

  async function toggleEnabled(entry: { id: string; code: string; enabled: boolean }) {
    setBusy(true);
    try {
      await setEntryEnabled(entry.id, !entry.enabled);
      await load();
    } catch (err) {
      setActionError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  async function removeEntry(entry: { id: string; code: string; version: number }) {
    setBusy(true);
    try {
      await deleteEntry(entry.id, entry.version);
      setDetail(null);
      setNotice(`话术「${entry.code}」已删除。`);
      await load();
    } catch (err) {
      setActionError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  // ------------------------------------------------------------ 译文

  function translationFormProblem(input: EntryTranslationInput): string | null {
    const base = titleProblem(input.title) ?? bodyProblem(input.body);
    if (base) return base;
    // 占位符一致性（规则 R1）：本地先拦一次，服务端与 DB 触发器还会各拦一次。
    if (!detail) return null;
    return placeholderProblem(detail.body, input.body);
  }

  async function submitTranslation() {
    if (!editingTranslation || !detail) return;
    const problem = translationFormProblem(editingTranslation);
    if (problem) {
      setActionError(problem);
      return;
    }
    setBusy(true);
    try {
      await saveTranslation(detail.id, editingTranslationId, editingTranslation);
      setEditingTranslation(null);
      setEditingTranslationId(null);
      setNotice(`语言「${editingTranslation.locale}」的译文已保存。`);
      await openDetail(detail.id);
      await load();
    } catch (err) {
      setActionError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  // ------------------------------------------------------------ 挂钩

  async function addBinding() {
    if (!detail || !bindingRuleId) {
      setActionError('请先选择要挂到哪一版平台规则上');
      return;
    }
    setBusy(true);
    try {
      await bindEntry(detail.id, bindingRuleId);
      setBindingRuleId('');
      setNotice('已挂到该平台规则版本上：政策改版时它会被自动标为待复核。');
      await openDetail(detail.id);
    } catch (err) {
      setActionError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  async function removeBinding(bindingId: string) {
    if (!detail) return;
    setBusy(true);
    try {
      await unbindEntry(detail.id, bindingId);
      await openDetail(detail.id);
    } catch (err) {
      setActionError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  // ------------------------------------------------------------ 规则版本

  async function submitRule() {
    if (!ruleEditor) return;
    if (!ruleEditor.policyKey.trim() || !ruleEditor.versionLabel.trim() || !ruleEditor.title.trim()) {
      setActionError('平台、政策编码、版本标签与标题都要填');
      return;
    }
    if (!ruleEditor.effectiveFrom) {
      setActionError('生效起点不能为空：没有生效起点的政策版本无法回答「这条话术当时依据的是哪版」');
      return;
    }
    setBusy(true);
    try {
      const payload = {
        platform: ruleEditor.platform,
        policyKey: ruleEditor.policyKey,
        versionLabel: ruleEditor.versionLabel,
        title: ruleEditor.title,
        changeSummary: ruleEditor.changeSummary,
        effectiveFrom: new Date(ruleEditor.effectiveFrom).toISOString(),
        effectiveTo: null,
      };
      if (ruleEditor.id) await updateRuleVersion(ruleEditor.id, undefined, payload);
      else await createRuleVersion(payload);
      setRuleEditor(null);
      setNotice(`规则版本「${ruleEditor.policyKey} / ${ruleEditor.versionLabel}」已保存为草稿。`);
      await load();
    } catch (err) {
      setActionError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  async function setRuleStatusVersion(rule: RuleVersionRow, status: string) {
    setBusy(true);
    try {
      await updateRuleVersion(rule.id, status, {
        platform: rule.platform,
        policyKey: rule.policyKey,
        versionLabel: rule.versionLabel,
        title: rule.title,
        changeSummary: rule.changeSummary,
        effectiveFrom: rule.effectiveFrom,
        effectiveTo: rule.effectiveTo,
      });
      setNotice(
        status === 'SUPERSEDED'
          ? `已标记「${rule.versionLabel}」被接替：挂在它上面的话术自动生成了待复核任务。`
          : `规则版本「${rule.versionLabel}」状态已改为${ruleStatusLabel(status)}。`,
      );
      await load();
    } catch (err) {
      setActionError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  async function removeRule(rule: RuleVersionRow) {
    setBusy(true);
    try {
      await deleteRuleVersion(rule.id);
      setNotice(`规则版本「${rule.versionLabel}」已删除。`);
      await load();
    } catch (err) {
      setActionError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  // ------------------------------------------------------------ 待复核

  async function claimTask(taskId: string, status: string) {
    setBusy(true);
    try {
      await updateReviewTask(taskId, status, status === 'DONE' ? '已按新规则确认或改写' : '已认领');
      await load();
    } catch (err) {
      setActionError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  // ------------------------------------------------------------ 引用

  async function logUsage(entryId: string, outcome: string) {
    setBusy(true);
    try {
      await recordUsage(entryId, outcome);
      setNotice(`已记录一次引用（${outcomeLabel(outcome)}）—— 命中率统计从这些明细行现算。`);
      await load();
    } catch (err) {
      setActionError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  const entryCount = entries?.items.length ?? 0;
  const ruleCount = rules?.items.length ?? 0;

  if (loading) {
    return (
      <SectionCard title="客服知识库与话术" description="加载中…">
        <p className="text-muted-foreground flex items-center gap-2 text-copy-13">
          <Loader2 className="size-4 animate-spin" /> 正在加载话术库、平台规则版本与待复核任务…
        </p>
      </SectionCard>
    );
  }

  return (
    <div className="space-y-4">
      {error && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive rounded-md px-3 py-2 text-copy-13">
          {error}
          <Button variant="ghost" size="sm" onClick={() => void load()} className="ml-2">
            <RefreshCw className="size-3" /> 重试
          </Button>
        </div>
      )}

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="话术存量"
          value={`${overview?.entryEnabled ?? 0} / ${overview?.entryTotal ?? 0}`}
          comparisonLabel="启用 / 总条数；停用的话术不计入命中率分母"
        />
        <StatCard
          label="命中率"
          value={percentText(overview?.hitRate)}
          comparisonLabel={
            overview?.hitRate === null || overview?.hitRate === undefined
              ? '还没有启用的话术 —— 与「话术没人用」是两件事'
              : `${overview?.hitEntryCount ?? 0} / ${overview?.entryEnabled ?? 0} 条启用话术被引用过`
          }
        />
        <StatCard
          label="待复核"
          value={String(overview?.reviewPending ?? 0)}
          comparisonLabel="平台政策改版后自动生成；完成一条少一条"
        />
        <StatCard
          label="话术被驳回"
          value={String(overview?.usageRejected ?? 0)}
          comparisonLabel="照话术答复被买家或平台驳回 —— 最该被发现的一种"
        />
      </div>

      <p className="text-muted-foreground text-copy-13">{overview?.note}</p>

      <div className="flex flex-wrap gap-2">
        {(
          [
            ['entries', `话术库（${entryCount}）`],
            ['rules', `平台规则版本（${ruleCount}）`],
            ['reviews', `待复核（${tasks?.total ?? 0}）`],
            ['usage', '使用效果'],
          ] as [Tab, string][]
        ).map(([key, label]) => (
          <Button key={key} variant={tab === key ? 'default' : 'outline'} size="sm" onClick={() => setTab(key)}>
            {label}
          </Button>
        ))}
      </div>

      {notice && (
        <div className="rounded-md border border-success/40 bg-success/5 px-3 py-2 text-copy-13">
          {notice}
        </div>
      )}
      {actionError && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive rounded-md px-3 py-2 text-copy-13">
          {actionError}
        </div>
      )}

      {/* ============================================================ 话术库 */}
      {tab === 'entries' && (
        <div className="space-y-3">
          <SectionCard title="话术库" description="常见问题标准答法 / 平台政策说明 / 退换货话术">
            <div className="flex flex-wrap items-end gap-2">
              <Field label="关键词" hint="同时搜编码、标题与正文">
                <input
                  className={control}
                  value={keyword}
                  onChange={(e) => {
                    setKeyword(e.target.value);
                    setEntryPage(0);
                  }}
                  placeholder="退货运费"
                />
              </Field>
              <Field label="类型">
                <select
                  className={control}
                  value={entryType}
                  onChange={(e) => {
                    setEntryType(e.target.value);
                    setEntryPage(0);
                  }}
                >
                  <option value="">全部类型</option>
                  {ENTRY_TYPES.map((t) => (
                    <option key={t.value} value={t.value}>
                      {t.label}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="启用状态">
                <select
                  className={control}
                  value={enabledFilter}
                  onChange={(e) => {
                    setEnabledFilter(e.target.value);
                    setEntryPage(0);
                  }}
                >
                  <option value="">全部</option>
                  <option value="true">启用</option>
                  <option value="false">停用</option>
                </select>
              </Field>
              <Field label="复核状态">
                <select
                  className={control}
                  value={reviewFilter}
                  onChange={(e) => {
                    setReviewFilter(e.target.value);
                    setEntryPage(0);
                  }}
                >
                  <option value="">全部</option>
                  <option value="PENDING">有待复核</option>
                  <option value="NONE">无待复核</option>
                </select>
              </Field>
              {writable && (
                <Button
                  size="sm"
                  onClick={() => {
                    setEditingId(null);
                    setEditing(emptyEntryInput());
                    setActionError(null);
                  }}
                >
                  <Plus className="size-3" /> 新建话术
                </Button>
              )}
            </div>

            {entries && entries.items.length === 0 ? (
              <EmptyState
                title="没有匹配的话术"
                description={entryEmptyHint(enabledFilter, reviewFilter, keyword)}
              />
            ) : (
              <table className="w-full text-left text-copy-13">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">编码</th>
                    <th>标题</th>
                    <th>类型</th>
                    <th>语言</th>
                    <th>触发词</th>
                    <th>被引用</th>
                    <th>待复核</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {entries?.items.map((row) => (
                    <tr key={row.id} className="border-t">
                      <td className="py-2 font-mono text-xs">{row.code}</td>
                      <td>
                        <button className="underline-offset-2 hover:underline" onClick={() => void openDetail(row.id)}>
                          {row.title}
                        </button>
                      </td>
                      <td>
                        <Badge tone="neutral">{row.entryTypeLabel}</Badge>
                      </td>
                      <td>
                        {row.sourceLocale}
                        {row.translationCount > 0 && (
                          <span className="text-muted-foreground"> +{row.translationCount} 译文</span>
                        )}
                      </td>
                      <td className="text-muted-foreground text-xs">
                        {row.triggerKeywords.join('、') || '—'}
                      </td>
                      <td>{row.usageCount}</td>
                      <td>
                        {row.reviewPendingCount > 0 ? (
                          <Badge tone="warning">{row.reviewPendingCount} 条</Badge>
                        ) : (
                          <span className="text-muted-foreground">—</span>
                        )}
                      </td>
                      <td className="space-x-1 text-right">
                        {writable && (
                          <>
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => {
                                setEditingId(row.id);
                                setEditing({
                                  code: row.code,
                                  title: row.title,
                                  entryType: row.entryType,
                                  body: row.body,
                                  sourceLocale: row.sourceLocale,
                                  triggerKeywords: row.triggerKeywords.join(','),
                                  enabled: row.enabled,
                                  version: row.version,
                                });
                                setActionError(null);
                              }}
                            >
                              编辑
                            </Button>
                            <Button
                              size="sm"
                              variant="ghost"
                              disabled={busy}
                              onClick={() => void toggleEnabled(row)}
                            >
                              {row.enabled ? '停用' : '启用'}
                            </Button>
                            <Button
                              size="sm"
                              variant="ghost"
                              disabled={busy}
                              onClick={() => void removeEntry(row)}
                            >
                              <Trash2 className="size-3" />
                            </Button>
                          </>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            {entries && entries.totalPages > 1 && (
              <Pager page={entries.page} totalPages={entries.totalPages} onPage={setEntryPage} />
            )}
          </SectionCard>

          {detail && (
            <SectionCard title={`${detail.code} · ${detail.title}`} description="正文、译文、挂钩的平台规则版本与使用效果">
              <p className="whitespace-pre-wrap text-copy-13">{detail.body}</p>
              <p className="text-muted-foreground mt-1 text-xs">
                占位符：
                {placeholdersOf(detail.body).length
                  ? placeholdersOf(detail.body).map((p) => `{${p}}`).join(' ')
                  : '（无）'}
              </p>

              <div className="mt-3 flex flex-wrap items-center gap-2">
                <Badge tone="neutral">{detail.entryTypeLabel}</Badge>
                <Badge tone={detail.enabled ? 'success' : 'neutral'}>
                  {detail.enabled ? '启用' : '停用'}
                </Badge>
                <Badge tone="neutral">源语言 {detail.sourceLocale}</Badge>
                <span className="text-muted-foreground text-xs">被引用 {detail.effect.usageCount} 次</span>
                {detail.effect.rejectedCount > 0 && (
                  <Badge tone="danger">被驳回 {detail.effect.rejectedCount} 次</Badge>
                )}
                <span className="text-muted-foreground text-xs">
                  本条命中率 {percentText(detail.effect.hitRate)}
                </span>
              </div>

              <div className="mt-4 space-y-2">
                <h3 className="text-copy-13 font-medium">多语言版本</h3>
                <p className="text-muted-foreground text-xs">
                  占位符集合必须与源语言正文完全一致 —— 漏一个会在答复里出现裸占位符，多一个则永远填不上。
                </p>
                <ul className="space-y-1">
                  {detail.translations.map((t) => (
                    <li key={t.id} className="flex flex-wrap items-center gap-2 text-copy-13">
                      <Badge tone="neutral">{t.locale}</Badge>
                      <span>{t.title}</span>
                      <Badge tone={t.status === 'REVIEWED' ? 'success' : 'warning'}>
                        {t.status === 'REVIEWED' ? '已复核' : '草稿'}
                      </Badge>
                      <span className="text-muted-foreground text-xs">{t.origin === 'AI' ? 'AI 预填' : '人工撰写'}</span>
                      {writable && (
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => {
                            setEditingTranslationId(t.id);
                            setEditingTranslation({
                              locale: t.locale,
                              title: t.title,
                              body: t.body,
                              origin: t.origin,
                              status: t.status,
                              version: t.version,
                            });
                            setActionError(null);
                          }}
                        >
                          编辑
                        </Button>
                      )}
                    </li>
                  ))}
                  {detail.translations.length === 0 && (
                    <li className="text-muted-foreground text-copy-13">
                      还没有译文。源语言（{detail.sourceLocale}）可直接使用，其余语言要先补一条译文才能发给对应语言的买家。
                    </li>
                  )}
                </ul>
                {writable && (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => {
                      setEditingTranslationId(null);
                      setEditingTranslation(emptyTranslationInput('en-US'));
                      setActionError(null);
                    }}
                  >
                    <Plus className="size-3" /> 新增译文
                  </Button>
                )}
              </div>

              <div className="mt-4 space-y-2">
                <h3 className="text-copy-13 font-medium">挂钩的平台规则版本</h3>
                <p className="text-muted-foreground text-xs">
                  蓝图要求「话术需与平台规则版本挂钩」：某版政策被标记为「已被接替」时，这里每条话术都会自动收到一条待复核任务。
                </p>
                <ul className="space-y-1">
                  {detail.ruleBindings.map((b) => (
                    <li key={b.id} className="flex flex-wrap items-center gap-2 text-copy-13">
                      <Badge tone="neutral">{platformLabel(b.platform)}</Badge>
                      <span className="font-mono text-xs">
                        {b.policyKey} / {b.versionLabel}
                      </span>
                      <Badge tone={ruleStatusTone(b.ruleStatus)}>{ruleStatusLabel(b.ruleStatus)}</Badge>
                      <span className="text-muted-foreground text-xs">{b.ruleTitle}</span>
                      {b.note && <span className="text-muted-foreground text-xs">备注：{b.note}</span>}
                      {writable && (
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={busy}
                          onClick={() => void removeBinding(b.id)}
                        >
                          解除
                        </Button>
                      )}
                    </li>
                  ))}
                  {detail.ruleBindings.length === 0 && (
                    <li className="text-muted-foreground text-copy-13">
                      还没有挂钩任何平台规则版本。没有挂钩的话，平台政策改版时这条话术不会被提醒更新。
                    </li>
                  )}
                </ul>
                {writable && (
                  <div className="flex flex-wrap items-end gap-2">
                    <Field label="挂到哪一版规则">
                      <select className={control} value={bindingRuleId} onChange={(e) => setBindingRuleId(e.target.value)}>
                        <option value="">选择规则版本…</option>
                        {rules?.items
                          .filter((r) => r.status !== 'SUPERSEDED')
                          .map((r) => (
                            <option key={r.id} value={r.id}>
                              {platformLabel(r.platform)} / {r.policyKey} / {r.versionLabel}
                            </option>
                          ))}
                      </select>
                    </Field>
                    <Button size="sm" disabled={busy} onClick={() => void addBinding()}>
                      <Link2 className="size-3" /> 挂钩
                    </Button>
                  </div>
                )}
              </div>

              <div className="mt-4 space-y-2">
                <h3 className="text-copy-13 font-medium">使用历史（最近引用）</h3>
                <ul className="space-y-1">
                  {detail.recentUsages.map((u) => (
                    <li key={u.id} className="flex flex-wrap items-center gap-2 text-copy-13">
                      <Badge tone={outcomeTone(u.outcome)}>{u.outcomeLabel}</Badge>
                      <span className="text-muted-foreground text-xs">{u.createdAt}</span>
                      {u.locale && <span className="text-muted-foreground text-xs">语言 {u.locale}</span>}
                      {u.conversationRef && (
                        <span className="text-muted-foreground text-xs">会话 {u.conversationRef}</span>
                      )}
                    </li>
                  ))}
                  {detail.recentUsages.length === 0 && (
                    <li className="text-muted-foreground text-copy-13">
                      还没有引用记录。客服在会话里一键引用后会在这里逐条落库 —— 命中率统计从这些明细行现算，不做累计列。
                    </li>
                  )}
                </ul>
                {writable && (
                  <div className="flex flex-wrap items-end gap-2">
                    {USAGE_OUTCOMES.map((o) => (
                      <Button
                        key={o.value}
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        title={o.hint}
                        onClick={() => void logUsage(detail.id, o.value)}
                      >
                        记一次「{o.label}」
                      </Button>
                    ))}
                  </div>
                )}
              </div>
            </SectionCard>
          )}
        </div>
      )}

      {/* ============================================================ 平台规则版本 */}
      {tab === 'rules' && (
        <SectionCard
          title="平台规则版本"
          description="政策条目的版本台账。把某一版标记为「已被接替」时，挂在它上面的话术会自动各生成一条待复核任务。"
        >
          <div className="flex flex-wrap items-end gap-2">
            <Field label="关键词" hint="搜政策编码、版本标签与标题">
              <input
                className={control}
                value={ruleKeyword}
                onChange={(e) => {
                  setRuleKeyword(e.target.value);
                  setRulePage(0);
                }}
                placeholder="RETURN_POLICY"
              />
            </Field>
            <Field label="平台">
              <select
                className={control}
                value={rulePlatform}
                onChange={(e) => {
                  setRulePlatform(e.target.value);
                  setRulePage(0);
                }}
              >
                <option value="">全部平台</option>
                {PLATFORMS.map((p) => (
                  <option key={p.value} value={p.value}>
                    {p.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="状态">
              <select
                className={control}
                value={ruleStatus}
                onChange={(e) => {
                  setRuleStatus(e.target.value);
                  setRulePage(0);
                }}
              >
                <option value="">全部状态</option>
                {RULE_STATUSES.map((s) => (
                  <option key={s.value} value={s.value}>
                    {s.label}
                  </option>
                ))}
              </select>
            </Field>
            {writable && (
              <Button
                size="sm"
                onClick={() => {
                  setRuleEditor({
                    id: null,
                    platform: 'AMAZON',
                    policyKey: '',
                    versionLabel: '',
                    title: '',
                    changeSummary: '',
                    effectiveFrom: '',
                  });
                  setActionError(null);
                }}
              >
                <Plus className="size-3" /> 新建规则版本
              </Button>
            )}
          </div>

          {rules && rules.items.length === 0 ? (
            <EmptyState
              title="没有匹配的平台规则版本"
              description={ruleEmptyHint(ruleKeyword, ruleStatus)}
            />
          ) : (
            <table className="w-full text-left text-copy-13">
              <thead className="text-muted-foreground">
                <tr>
                  <th className="py-2">平台</th>
                  <th>政策编码</th>
                  <th>版本</th>
                  <th>标题</th>
                  <th>状态</th>
                  <th>生效区间</th>
                  <th>挂钩话术</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rules?.items.map((r) => (
                  <tr key={r.id} className="border-t align-top">
                    <td className="py-2">{platformLabel(r.platform)}</td>
                    <td className="font-mono text-xs">{r.policyKey}</td>
                    <td>{r.versionLabel}</td>
                    <td>
                      {r.title}
                      {r.changeSummary && (
                        <p className="text-muted-foreground mt-0.5 text-xs">变更：{r.changeSummary}</p>
                      )}
                    </td>
                    <td>
                      <Badge tone={ruleStatusTone(r.status)}>{ruleStatusLabel(r.status)}</Badge>
                    </td>
                    <td className="text-muted-foreground text-xs">
                      {r.effectiveFrom?.slice(0, 10)} → {r.effectiveTo ? r.effectiveTo.slice(0, 10) : '（至今）'}
                    </td>
                    <td>{r.bindingCount}</td>
                    <td className="space-x-1 text-right">
                      {writable && (
                        <>
                          {r.status !== 'ACTIVE' && (
                            <Button
                              size="sm"
                              variant="ghost"
                              disabled={busy}
                              onClick={() => void setRuleStatusVersion(r, 'ACTIVE')}
                            >
                              发布生效
                            </Button>
                          )}
                          {r.status !== 'SUPERSEDED' && (
                            <Button
                              size="sm"
                              variant="ghost"
                              disabled={busy}
                              title="标记后自动给挂在它上面的话术生成待复核任务"
                              onClick={() => void setRuleStatusVersion(r, 'SUPERSEDED')}
                            >
                              已被接替
                            </Button>
                          )}
                          <Button
                            size="sm"
                            variant="ghost"
                            disabled={busy}
                            onClick={() => void removeRule(r)}
                          >
                            <Trash2 className="size-3" />
                          </Button>
                        </>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {rules && rules.totalPages > 1 && (
            <Pager page={rules.page} totalPages={rules.totalPages} onPage={setRulePage} />
          )}
        </SectionCard>
      )}

      {/* ============================================================ 待复核 */}
      {tab === 'reviews' && (
        <SectionCard
          title="待复核任务"
          description="平台政策变化时能定位哪些话术需要更新 —— 每行点名是哪一版接替了它与两版的变更摘要。"
        >
          <div className="flex flex-wrap items-end gap-2">
            <Field label="状态">
              <select
                className={control}
                value={taskStatus}
                onChange={(e) => {
                  setTaskStatus(e.target.value);
                  setTaskPage(0);
                }}
              >
                <option value="">全部状态</option>
                {REVIEW_STATUSES.map((s) => (
                  <option key={s.value} value={s.value}>
                    {s.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="平台">
              <select
                className={control}
                value={taskPlatform}
                onChange={(e) => {
                  setTaskPlatform(e.target.value);
                  setTaskPage(0);
                }}
              >
                <option value="">全部平台</option>
                {PLATFORMS.map((p) => (
                  <option key={p.value} value={p.value}>
                    {p.label}
                  </option>
                ))}
              </select>
            </Field>
          </div>

          {tasks && tasks.items.length === 0 ? (
            <EmptyState title="没有复核任务" description={reviewEmptyHint(taskStatus, taskPlatform)} />
          ) : (
            <ul className="space-y-2">
              {tasks?.items.map((t) => (
                <li key={t.id} className="rounded-md border px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={reviewStatusTone(t.reviewStatus)}>
                      {reviewStatusLabel(t.reviewStatus)}
                    </Badge>
                    <span className="font-medium">{t.entryCode}</span>
                    <span className="text-muted-foreground">{t.entryTitle}</span>
                    <Badge tone="neutral">{platformLabel(t.platform)}</Badge>
                    <span className="font-mono text-xs">
                      {t.policyKey} / {t.versionLabel}
                    </span>
                  </div>
                  <p className="mt-1 text-copy-13">{reviewTaskSummary(t)}</p>
                  {t.ruleChangeSummary && (
                    <p className="text-muted-foreground text-xs">旧版变更：{t.ruleChangeSummary}</p>
                  )}
                  {writable && (
                    <div className="mt-1 space-x-1">
                      {t.reviewStatus === 'PENDING' && (
                        <Button size="sm" variant="outline" disabled={busy} onClick={() => void claimTask(t.id, 'REVIEWING')}>
                          认领
                        </Button>
                      )}
                      {t.reviewStatus !== 'DONE' && (
                        <Button size="sm" variant="outline" disabled={busy} onClick={() => void claimTask(t.id, 'DONE')}>
                          标记已复核
                        </Button>
                      )}
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}

          {tasks && tasks.totalPages > 1 && (
            <Pager page={tasks.page} totalPages={tasks.totalPages} onPage={setTaskPage} />
          )}
        </SectionCard>
      )}

      {/* ============================================================ 使用效果 */}
      {tab === 'usage' && (
        <div className="space-y-3">
          <SectionCard
            title="命中率排行"
            description="默认按被引用次数倒序：先看哪些话术真的在用，再看命中率。"
          >
            <div className="flex flex-wrap items-end gap-2">
              <Field
                label="排序"
                hint={HIT_RATE_SORTS.find((s) => s.value === hitSort)?.hint ?? '按被引用次数倒序'}
              >
                <select
                  className={control}
                  value={hitSort}
                  onChange={(e) => setHitSort(e.target.value)}
                >
                  {HIT_RATE_SORTS.map((s) => (
                    <option key={s.value} value={s.value}>
                      {s.label}
                    </option>
                  ))}
                </select>
              </Field>
            </div>
            <table className="w-full text-left text-copy-13">
              <thead className="text-muted-foreground">
                <tr>
                  <th className="py-2">编码</th>
                  <th>标题</th>
                  <th>类型</th>
                  <th>被引用</th>
                  <th>命中</th>
                  <th>命中率</th>
                  <th>被驳回</th>
                  <th>最近使用</th>
                </tr>
              </thead>
              <tbody>
                {hitRates.map((r) => (
                  <tr key={r.entryId} className="border-t">
                    <td className="py-2 font-mono text-xs">{r.code}</td>
                    <td>{r.title}</td>
                    <td>
                      <Badge tone="neutral">{entryTypeLabel(r.entryType)}</Badge>
                    </td>
                    <td>{r.usageCount}</td>
                    <td>{r.hitUsageCount}</td>
                    <td>
                      {r.usageCount === 0 ? (
                        <span className="text-muted-foreground" title="从没被引用过 —— 不是 0%，是「没有样本」">
                          —
                        </span>
                      ) : (
                        percentText(r.hitRate)
                      )}
                    </td>
                    <td>
                      {r.rejectedCount > 0 ? <Badge tone="danger">{r.rejectedCount}</Badge> : '—'}
                    </td>
                    <td className="text-muted-foreground text-xs">{r.lastUsedAt?.slice(0, 10) ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {hitRates.length === 0 && (
              <EmptyState title="还没有可统计的话术" description="新建话术并被引用后，这里才会有数据。" />
            )}
          </SectionCard>

          <SectionCard title="引用记录" description="每次客服一键引用都真的落一行；命中率统计从这些明细行现算。">
            <div className="flex flex-wrap items-end gap-2">
              <Field label="结果类型">
                <select
                  className={control}
                  value={outcomeFilter}
                  onChange={(e) => {
                    setOutcomeFilter(e.target.value);
                    setUsagePage(0);
                  }}
                >
                  <option value="">全部结果</option>
                  {USAGE_OUTCOMES.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </Field>
            </div>
            {usages && usages.items.length === 0 ? (
              <EmptyState title="没有引用记录" description={usageEmptyHint(outcomeFilter)} />
            ) : (
              <table className="w-full text-left text-copy-13">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">话术</th>
                    <th>结果</th>
                    <th>计入命中</th>
                    <th>语言</th>
                    <th>会话</th>
                    <th>时间</th>
                  </tr>
                </thead>
                <tbody>
                  {usages?.items.map((u) => (
                    <tr key={u.id} className="border-t">
                      <td className="py-2">
                        <span className="font-mono text-xs">{u.entryCode}</span>
                        <span className="text-muted-foreground ml-1">{u.entryTitle}</span>
                      </td>
                      <td>
                        <Badge tone={outcomeTone(u.outcome)}>{u.outcomeLabel}</Badge>
                        <p className="text-muted-foreground mt-0.5 text-xs">{outcomeHint(u.outcome)}</p>
                      </td>
                      <td>{u.countsAsHit ? '是' : '否'}</td>
                      <td>{u.locale ?? '源语言'}</td>
                      <td className="text-muted-foreground text-xs">{u.conversationRef || '—'}</td>
                      <td className="text-muted-foreground text-xs">{u.createdAt}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {usages && usages.totalPages > 1 && (
              <Pager page={usages.page} totalPages={usages.totalPages} onPage={setUsagePage} />
            )}
          </SectionCard>
        </div>
      )}

      {/* ============================================================ 编辑器 */}
      {editing && (
        <Editor
          title={editingId ? `编辑话术 ${editing.code}` : '新建话术'}
          description="话术编码建后不可改：客服与运营会互相口头引用它，改了会让历史单据上的引用对不上。"
          close={() => setEditing(null)}
        >
          <div className="space-y-3">
            <Field label="话术编码" hint="大写字母、数字与下划线，例如 REFUND_POLICY">
              <input
                className={control}
                value={editing.code}
                disabled={editingId !== null}
                onChange={(e) => setEditing({ ...editing, code: e.target.value })}
              />
            </Field>
            <Field label="标题">
              <input
                className={control}
                value={editing.title}
                onChange={(e) => setEditing({ ...editing, title: e.target.value })}
              />
            </Field>
            <Field label="类型">
              <select
                className={control}
                value={editing.entryType}
                onChange={(e) => setEditing({ ...editing, entryType: e.target.value })}
              >
                {ENTRY_TYPES.map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label} — {t.hint}
                  </option>
                ))}
              </select>
            </Field>
            <Field
              label="标准答法正文"
              hint={`最长 ${MAX_BODY_LENGTH} 字符。可用 {buyer_name} / {order_no} 这类占位符，译文里必须原样保留。`}
            >
              <textarea
                className={`${control} min-h-28`}
                value={editing.body}
                onChange={(e) => setEditing({ ...editing, body: e.target.value })}
              />
            </Field>
            <Field label="源语言" hint="译文挂在它下面；只有源语言允许没有译文">
              <input
                className={control}
                value={editing.sourceLocale}
                onChange={(e) => setEditing({ ...editing, sourceLocale: normalizeLocale(e.target.value) })}
              />
            </Field>
            <Field label="触发词" hint="客服检索时打的短词，用英文逗号分隔，例如 退,退货,换货">
              <input
                className={control}
                value={editing.triggerKeywords ?? ''}
                onChange={(e) => setEditing({ ...editing, triggerKeywords: e.target.value })}
              />
            </Field>
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={() => setEditing(null)}>
                取消
              </Button>
              <Button disabled={busy} onClick={() => void submitEntry()}>
                <Save className="size-3" /> 保存
              </Button>
            </div>
          </div>
        </Editor>
      )}

      {editingTranslation && detail && (
        <Editor
          title={`新增 / 编辑 ${editingTranslation.locale} 译文`}
          description="占位符集合必须与源语言正文完全一致：漏一个会在答复里出现裸占位符，多一个则永远填不上。"
          close={() => setEditingTranslation(null)}
        >
          <div className="space-y-3">
            <Field label="语言" hint="BCP-47 简写，如 en-US / ja-JP">
              <input
                className={control}
                value={editingTranslation.locale}
                disabled={editingTranslationId !== null}
                onChange={(e) =>
                  setEditingTranslation({
                    ...editingTranslation,
                    locale: normalizeLocale(e.target.value),
                  })
                }
              />
            </Field>
            <Field label="标题">
              <input
                className={control}
                value={editingTranslation.title}
                onChange={(e) => setEditingTranslation({ ...editingTranslation, title: e.target.value })}
              />
            </Field>
            <Field
              label="译文正文"
              hint={`源语言占位符：${placeholdersOf(detail.body).map((p) => `{${p}}`).join(' ') || '（无）'}`}
            >
              <textarea
                className={`${control} min-h-28`}
                value={editingTranslation.body}
                onChange={(e) => setEditingTranslation({ ...editingTranslation, body: e.target.value })}
              />
            </Field>
            {placeholderProblem(detail.body, editingTranslation.body) && (
              <p className="text-destructive text-copy-13">
                {placeholderProblem(detail.body, editingTranslation.body)}
              </p>
            )}
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="来源">
                <select
                  className={control}
                  value={editingTranslation.origin ?? 'MANUAL'}
                  onChange={(e) => setEditingTranslation({ ...editingTranslation, origin: e.target.value })}
                >
                  {TRANSLATION_ORIGINS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="状态" hint="只有已复核的译文才算「能直接发给买家」">
                <select
                  className={control}
                  value={editingTranslation.status ?? 'DRAFT'}
                  onChange={(e) => setEditingTranslation({ ...editingTranslation, status: e.target.value })}
                >
                  {TRANSLATION_STATUSES.map((s) => (
                    <option key={s.value} value={s.value}>
                      {s.label}
                    </option>
                  ))}
                </select>
              </Field>
            </div>
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={() => setEditingTranslation(null)}>
                取消
              </Button>
              <Button disabled={busy} onClick={() => void submitTranslation()}>
                <Save className="size-3" /> 保存
              </Button>
            </div>
          </div>
        </Editor>
      )}

      {ruleEditor && (
        <Editor
          title={ruleEditor.id ? `编辑规则版本 ${ruleEditor.versionLabel}` : '新建平台规则版本'}
          description="新版本一律建成草稿：不生效的政策版本不该让话术被标成「依据了这一版」。"
          close={() => setRuleEditor(null)}
        >
          <div className="space-y-3">
            <Field label="平台">
              <select
                className={control}
                value={ruleEditor.platform}
                onChange={(e) => setRuleEditor({ ...ruleEditor, platform: e.target.value })}
              >
                {PLATFORMS.map((p) => (
                  <option key={p.value} value={p.value}>
                    {p.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="政策编码" hint="政策条目的稳定身份，改版不改它（如 AMAZON_RETURN_POLICY）">
              <input
                className={control}
                value={ruleEditor.policyKey}
                onChange={(e) => setRuleEditor({ ...ruleEditor, policyKey: e.target.value.toUpperCase() })}
              />
            </Field>
            <Field label="版本标签" hint="人读的不是 uuid，是它（如 2026-03）">
              <input
                className={control}
                value={ruleEditor.versionLabel}
                onChange={(e) => setRuleEditor({ ...ruleEditor, versionLabel: e.target.value })}
              />
            </Field>
            <Field label="标题">
              <input
                className={control}
                value={ruleEditor.title}
                onChange={(e) => setRuleEditor({ ...ruleEditor, title: e.target.value })}
              />
            </Field>
            <Field label="变更摘要" hint="标记为「已被接替」时必填：客服要靠它知道该复核什么">
              <textarea
                className={`${control} min-h-20`}
                value={ruleEditor.changeSummary}
                onChange={(e) => setRuleEditor({ ...ruleEditor, changeSummary: e.target.value })}
              />
            </Field>
            <Field label="生效起点" hint="必填：没有生效起点的政策版本无法回答「这条话术当时依据的是哪版」">
              <input
                type="date"
                className={control}
                value={ruleEditor.effectiveFrom}
                onChange={(e) => setRuleEditor({ ...ruleEditor, effectiveFrom: e.target.value })}
              />
            </Field>
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={() => setRuleEditor(null)}>
                取消
              </Button>
              <Button disabled={busy} onClick={() => void submitRule()}>
                <Save className="size-3" /> 保存
              </Button>
            </div>
          </div>
        </Editor>
      )}
    </div>
  );
}

function Pager({
  page,
  totalPages,
  onPage,
}: {
  page: number;
  totalPages: number;
  onPage: (page: number) => void;
}) {
  return (
    <div className="text-muted-foreground mt-2 flex items-center gap-2 text-copy-13">
      <Button size="sm" variant="outline" disabled={page <= 0} onClick={() => onPage(page - 1)}>
        上一页
      </Button>
      <span>
        第 {page + 1} / {Math.max(totalPages, 1)} 页
      </span>
      <Button
        size="sm"
        variant="outline"
        disabled={page + 1 >= totalPages}
        onClick={() => onPage(page + 1)}
      >
        下一页
      </Button>
    </div>
  );
}
