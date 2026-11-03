'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import {
  Activity,
  AlertTriangle,
  Clock,
  Gauge,
  Lightbulb,
  Loader2,
  MessageSquareWarning,
  RefreshCw,
  Target,
  TrendingUp,
} from 'lucide-react';
import {
  adviceSourceLabel,
  describeAdviseReason,
  describeBasis,
  describeDuration,
  describeOutcome,
  falseTriggerTypeLabel,
  fetchAdvices,
  fetchBaselines,
  fetchCapabilities,
  fetchFeedbacks,
  fetchOverview,
  fetchRuleDetail,
  fetchRules,
  fetchRuns,
  feedbackStatusTone,
  formatRate,
  formatSavedMinutes,
  outcomeLabel,
  outcomeTone,
  refreshAdvices,
  saveBaseline,
  setAdviceStatus,
  setFeedbackStatus,
  severityLabel,
  severityTone,
  submitFeedback,
  type Tone,
} from './automation-rule-effect-model';
import type {
  AutomationRuleEffectAdvicePage,
  AutomationRuleEffectBaselinePage,
  AutomationRuleEffectCapabilities,
  AutomationRuleEffectFeedbackPage,
  AutomationRuleEffectOverview,
  AutomationRuleEffectRuleDetail,
  AutomationRuleEffectRulePage,
  AutomationRuleEffectRunPage,
} from '../../../../../packages/api-contracts/src/index';

const selectClass =
  'border-border bg-background text-foreground h-9 w-full rounded-md border px-2 text-sm';

/** 模型的语气 → Badge 的语气（徽章只有 7 种 tone，这里用其中 4 种）。 */
const BADGE_TONE: Record<Tone, 'neutral' | 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
};

/** 一页多少行。 */
const PAGE_SIZE = 20;

/**
 * 18.3.2.2「规则效果」工作区。
 *
 * 🔴 页面上五件**不许**做错的事：
 *
 * 1. **「触发次数」是「记录条数」，不是「规则自动触发了几次」**。18.3.2 执行引擎
 *    **尚未交付** ⇒ 这些行绝大多数是人工登记或「未执行」的重跑请求。页面顶部
 *    常驻提示条把这一点写清楚，**绝不**把人工登记说成自动触发。
 * 2. **「未执行」既不是成功也不是失败**。它单列一档，**不进**成功率的母 ——
 *    否则「执行器没接通所以一次没跑」会被算成失败，报表会说谎。
 * 3. **「节省工时预估」必须带口径**。只显示一个数字而不显示「怎么算出来的」，
 *    那个数字不能用于决策 ⇒ 口径说明与数字一起渲染。
 * 4. **「没有口径」是空，不是 0**。没有登记人工基线的规则显示「—（未登记口径）」。
 * 5. **「关闭」不是本模块的动作**。误触发率高的规则能**快速定位**（规则 id +
 *    规则名 + 指向规则档案的引用），但「关闭」归 18.3.1.1 —— 页面**不提供**
 *    一个点了也没用的「关闭」按钮，只提供定位入口并写明归属。
 */
export function AutomationRuleEffectWorkspace() {
  const [capabilities, setCapabilities] = useState<AutomationRuleEffectCapabilities | null>(null);
  const [overview, setOverview] = useState<AutomationRuleEffectOverview | null>(null);
  const [rulePage, setRulePage] = useState<AutomationRuleEffectRulePage | null>(null);
  const [feedbackPage, setFeedbackPage] = useState<AutomationRuleEffectFeedbackPage | null>(null);
  const [advicePage, setAdvicePage] = useState<AutomationRuleEffectAdvicePage | null>(null);
  const [baselinePage, setBaselinePage] = useState<AutomationRuleEffectBaselinePage | null>(null);
  const [runPage, setRunPage] = useState<AutomationRuleEffectRunPage | null>(null);
  const [detail, setDetail] = useState<AutomationRuleEffectRuleDetail | null>(null);

  const [keyword, setKeyword] = useState('');
  const [onlyFalseTrigger, setOnlyFalseTrigger] = useState(false);
  const [adviseDisableOnly, setAdviseDisableOnly] = useState(false);
  const [order, setOrder] = useState('totalruns');
  const [rulePageIndex, setRulePageIndex] = useState(0);
  const [runPageIndex, setRunPageIndex] = useState(0);
  const [outcome, setOutcome] = useState('');

  const [feedbackRuleId, setFeedbackRuleId] = useState('');
  const [feedbackRunId, setFeedbackRunId] = useState('');
  const [feedbackType, setFeedbackType] = useState('WRONG_CONDITION');
  const [feedbackNote, setFeedbackNote] = useState('');

  const [baselineRuleId, setBaselineRuleId] = useState('');
  const [baselineMinutes, setBaselineMinutes] = useState('');
  const [baselineSource, setBaselineSource] = useState('MANUAL_ESTIMATE');
  const [baselineNote, setBaselineNote] = useState('');

  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    const [caps, stats, rules, feedbacks, advices, baselines, runs] = await Promise.allSettled([
      fetchCapabilities(),
      fetchOverview(),
      fetchRules({
        keyword: keyword || undefined,
        onlyFalseTrigger: onlyFalseTrigger || undefined,
        adviseDisableOnly: adviseDisableOnly || undefined,
        order: order || undefined,
        page: rulePageIndex,
        size: PAGE_SIZE,
      }),
      fetchFeedbacks({ page: 0, size: PAGE_SIZE }),
      fetchAdvices({ onlyActive: true, page: 0, size: PAGE_SIZE }),
      fetchBaselines({ page: 0, size: PAGE_SIZE }),
      fetchRuns({
        outcome: outcome || undefined,
        page: runPageIndex,
        size: PAGE_SIZE,
      }),
    ]);
    if (caps.status === 'fulfilled') setCapabilities(caps.value);
    if (stats.status === 'fulfilled') setOverview(stats.value);
    if (rules.status === 'fulfilled') setRulePage(rules.value);
    if (feedbacks.status === 'fulfilled') setFeedbackPage(feedbacks.value);
    if (advices.status === 'fulfilled') setAdvicePage(advices.value);
    if (baselines.status === 'fulfilled') setBaselinePage(baselines.value);
    if (runs.status === 'fulfilled') setRunPage(runs.value);
    const failed = [
      caps.status === 'rejected' ? '能力声明' : null,
      stats.status === 'rejected' ? '概览' : null,
      rules.status === 'rejected' ? '逐规则效果明细' : null,
      feedbacks.status === 'rejected' ? '误触发反馈' : null,
      advices.status === 'rejected' ? '优化建议' : null,
      baselines.status === 'rejected' ? '节省工时口径' : null,
      runs.status === 'rejected' ? '执行记录下钻' : null,
    ].filter((x): x is string => x !== null);
    if (failed.length > 0) {
      setLoadError(`以下内容读不到：${failed.join('、')}`);
    }
    setLoading(false);
  }, [keyword, onlyFalseTrigger, adviseDisableOnly, order, rulePageIndex, outcome, runPageIndex]);

  useEffect(() => {
    void load();
  }, [load]);

  const openRule = useCallback(async (ruleId: string) => {
    setDetailLoading(true);
    setActionError('');
    setNotice('');
    try {
      setDetail(await fetchRuleDetail(ruleId));
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '读取规则效果详情失败');
      setDetail(null);
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const submitFeedbackForm = useCallback(async () => {
    if (!feedbackRuleId.trim()) {
      setActionError('必须指定这条反馈说的是哪条规则');
      return;
    }
    setSubmitting(true);
    setActionError('');
    setNotice('');
    try {
      await submitFeedback({
        ruleId: feedbackRuleId.trim(),
        runId: feedbackRunId.trim() || null,
        falseTriggerType: feedbackType,
        note: feedbackNote.trim(),
      });
      setFeedbackRunId('');
      setFeedbackNote('');
      await load();
      setNotice('误触发反馈已登记 —— 它会计入误触发率（指向有效执行的那些）。');
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '登记误触发反馈失败');
    } finally {
      setSubmitting(false);
    }
  }, [feedbackRuleId, feedbackRunId, feedbackType, feedbackNote, load]);

  const submitBaselineForm = useCallback(async () => {
    if (!baselineRuleId.trim()) {
      setActionError('必须指定这条口径属于哪条规则');
      return;
    }
    const minutes = baselineMinutes.trim();
    const parsed = Number(minutes);
    if (!minutes || Number.isNaN(parsed) || parsed < 0) {
      setActionError('人工基线分钟数必须是一个非负数字');
      return;
    }
    setSubmitting(true);
    setActionError('');
    setNotice('');
    try {
      await saveBaseline({
        ruleId: baselineRuleId.trim(),
        manualMinutesPerRun: parsed,
        baselineSource,
        baselineNote: baselineNote.trim(),
        effectiveFrom: null,
      });
      setBaselineMinutes('');
      setBaselineNote('');
      await load();
      setNotice('节省工时口径已保存 —— 报表里的「节省工时预估」现在有了口径。');
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '保存节省工时口径失败');
    } finally {
      setSubmitting(false);
    }
  }, [baselineRuleId, baselineMinutes, baselineSource, baselineNote, load]);

  const regenerateAdvices = useCallback(async () => {
    setSubmitting(true);
    setActionError('');
    setNotice('');
    try {
      const result = await refreshAdvices();
      await load();
      setNotice(
        `已按当前聚合重新生成系统建议：新增 ${result.createdAdvices} 条、更新 ${result.updatedAdvices} 条。` +
          '（幂等：同一条规则 + 同一个建议类型至多一条；已人工处理过的建议状态不被覆盖。）',
      );
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '重新生成建议失败');
    } finally {
      setSubmitting(false);
    }
  }, [load]);

  /** 🔴 处理一条反馈（已采纳 / 已忽略）。处理**不改变**误触发率。 */
  const handleFeedbackStatus = useCallback(
    async (id: string, status: 'ACCEPTED' | 'DISMISSED') => {
      setSubmitting(true);
      setActionError('');
      setNotice('');
      try {
        await setFeedbackStatus(id, status);
        await load();
        setNotice(
          status === 'ACCEPTED'
            ? '反馈已标记为「已采纳」。⚠️ 处理**不改变**误触发率 —— 它仍然是发生过的误触发。'
            : '反馈已标记为「已忽略」。⚠️ 处理**不改变**误触发率 —— 它仍然是发生过的误触发。',
        );
      } catch (e) {
        setActionError(e instanceof Error ? e.message : '处理误触发反馈失败');
      } finally {
        setSubmitting(false);
      }
    },
    [load],
  );

  /** 🔴 处理一条建议（已采纳 / 已忽略）。 */
  const handleAdviceStatus = useCallback(
    async (id: string, status: 'ADOPTED' | 'IGNORED') => {
      setSubmitting(true);
      setActionError('');
      setNotice('');
      try {
        await setAdviceStatus(id, status);
        await load();
        setNotice(
          status === 'ADOPTED'
            ? '建议已标记为「已采纳」。系统重新生成时**不会**覆盖这个决定。'
            : '建议已标记为「已忽略」。系统重新生成时**不会**覆盖这个决定。',
        );
      } catch (e) {
        setActionError(e instanceof Error ? e.message : '处理优化建议失败');
      } finally {
        setSubmitting(false);
      }
    },
    [load],
  );

  /** 🔴 引擎未交付的提示条（从能力声明里取，**如实**说清统计的是「记录」）。 */
  const engineNote = useMemo(() => {
    const capability = capabilities?.capabilities.find((c) => c.name.includes('规则执行引擎'));
    if (!capability) return '';
    if (capability.available) return '';
    return capability.note || '规则执行引擎尚未接通。';
  }, [capabilities]);

  /** 🔴 关闭动作的归属声明（来自能力声明，本模块**不做**关闭）。 */
  const disableOwnershipNote = useMemo(() => {
    const capability = capabilities?.capabilities.find((c) => c.name.includes('规则档案'));
    return capability?.note || '关闭一条规则归 18.3.1.1 规则档案，本模块只做定位。';
  }, [capabilities]);

  const totalRulePages = rulePage
    ? Math.max(1, Math.ceil(rulePage.total / Math.max(1, rulePage.size)))
    : 1;
  const totalRunPages = runPage
    ? Math.max(1, Math.ceil(runPage.total / Math.max(1, runPage.size)))
    : 1;

  return (
    <div className="space-y-4">
      <PageHeader
        title="规则效果"
        description="统计规则效果：触发次数、成功率、平均耗时、节省工时预估、误触发反馈与规则优化建议。"
        actions={
          <Button variant="outline" onClick={() => void load()} disabled={loading}>
            {loading ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <RefreshCw className="h-4 w-4" />
            )}
            刷新
          </Button>
        }
      />

      {loadError && (
        <Notice tone="danger" title="读取失败">
          {loadError}
        </Notice>
      )}
      {actionError && (
        <Notice tone="danger" title="操作失败">
          {actionError}
        </Notice>
      )}
      {notice && (
        <Notice tone="info" title="已处理">
          {notice}
        </Notice>
      )}

      {/* 🔴 区块 0：能力与边界声明 —— 先说清「统计的是记录，不是真实触发」。 */}
      <Notice tone="warning" title="规则执行引擎（18.3.2）尚未交付">
        {engineNote || '本模块统计的是执行台账里的**记录**，不是规则的**真实自动触发**。'}
        <p className="mt-2">
          🔴 现在的「触发次数」是<strong>登记在执行台账里的行数</strong>，
          绝大多数来自人工登记或「未执行」的重跑请求 —— 页面<strong>不会</strong>把它说成
          「规则自动触发了几次」。「未执行」单列一档，既不算成功也不算失败， 也<strong>不进</strong>
          成功率的母。
        </p>
      </Notice>

      {/* ============================================================ 区块 1：规则效果概览 */}
      <SectionCard
        title="规则效果概览"
        description="触发次数分五档列；成功率的母 = 成功 + 失败 + 超时（跳过与未执行都不进母）。"
      >
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard
            label="触发次数（记录条数）"
            value={overview?.totalRuns ?? 0}
            unit="条执行记录"
            icon={<Activity className="h-4 w-4" />}
          />
          <StatCard
            label="成功率"
            value={formatRate(overview?.successRate)}
            unit={`有效执行 ${overview?.ratedRuns ?? 0} 次`}
            icon={<TrendingUp className="h-4 w-4" />}
          />
          <StatCard
            label="平均耗时"
            value={describeDuration(overview?.avgDurationMs ?? 0)}
            unit="只在真正跑过的执行上平均"
            icon={<Clock className="h-4 w-4" />}
          />
          <StatCard
            label="节省工时预估"
            value={formatSavedMinutes(
              overview?.savedMinutes ?? null,
              (overview?.rulesWithBaseline ?? 0) > 0,
            )}
            unit={
              (overview?.rulesWithBaseline ?? 0) > 0
                ? `${overview?.rulesWithBaseline} 条规则有口径`
                : '暂无口径'
            }
            icon={<Gauge className="h-4 w-4" />}
          />
        </div>

        {overview && (
          <dl className="mt-4 grid gap-3 text-sm md:grid-cols-3">
            <Field label="成功">{overview.succeededRuns}</Field>
            <Field label="失败">{overview.failedRuns}</Field>
            <Field label="超时">{overview.timeoutRuns}</Field>
            <Field label="跳过（条件未命中）">{overview.skippedRuns}</Field>
            <Field label="未执行（执行器未接通）">{overview.notAttemptedRuns}</Field>
            <Field label="有执行记录的规则">{overview.rulesWithRuns}</Field>
            <Field label="误触发反馈">{overview.falseTriggerFeedbacks}</Field>
            <Field label="误触发率">
              {formatRate(overview.falseTriggerRate)}
              {overview.unlinkedFeedbacks > 0 && (
                <span className="text-muted-foreground">
                  （另有 {overview.unlinkedFeedbacks} 条未指向具体执行，不进分子）
                </span>
              )}
            </Field>
            <Field label="待处理建议">{overview.openAdvices}</Field>
          </dl>
        )}

        {overview?.savingBasis && (
          <Notice tone="info" title="节省工时预估的口径">
            {describeBasis(overview.savingBasis)}
          </Notice>
        )}
      </SectionCard>

      {/* ============================================================ 区块 2：逐规则效果明细 */}
      <SectionCard
        title="逐规则效果明细"
        description="筛选与关键词都是真筛选（后端 SQL 过滤），分页是真分页（后端 LIMIT/OFFSET + 总数）。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-4">
            <label className="block space-y-1">
              <span className="text-label-13 text-foreground">关键词</span>
              <Input
                aria-label="关键词"
                value={keyword}
                placeholder="规则名"
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setRulePageIndex(0);
                }}
              />
            </label>
            <label className="block space-y-1">
              <span className="text-label-13 text-foreground">排序</span>
              <select
                aria-label="排序"
                className={selectClass}
                value={order}
                onChange={(e) => {
                  setOrder(e.target.value);
                  setRulePageIndex(0);
                }}
              >
                <option value="totalruns">触发次数</option>
                <option value="successrate">成功率</option>
                <option value="avgdurationms">平均耗时</option>
                <option value="falsetriggerrate">误触发率</option>
                <option value="savedminutes">节省工时</option>
                <option value="lastrunat">最近执行</option>
              </select>
            </label>
            <label className="block space-y-1">
              <span className="text-label-13 text-foreground">只看有误触发的</span>
              <select
                aria-label="只看有误触发的"
                className={selectClass}
                value={onlyFalseTrigger ? '1' : ''}
                onChange={(e) => {
                  setOnlyFalseTrigger(e.target.value === '1');
                  setRulePageIndex(0);
                }}
              >
                <option value="">全部</option>
                <option value="1">只看有误触发的</option>
              </select>
            </label>
            <label className="block space-y-1">
              <span className="text-label-13 text-foreground">只看建议关闭的</span>
              <select
                aria-label="只看建议关闭的"
                className={selectClass}
                value={adviseDisableOnly ? '1' : ''}
                onChange={(e) => {
                  setAdviseDisableOnly(e.target.value === '1');
                  setRulePageIndex(0);
                }}
              >
                <option value="">全部</option>
                <option value="1">只看建议关闭的</option>
              </select>
            </label>
          </div>

          {loading && (
            <div className="text-muted-foreground flex items-center gap-2 py-8 text-sm">
              <Loader2 className="h-4 w-4 animate-spin" />
              正在读取规则效果…
            </div>
          )}

          {!loading && rulePage && rulePage.items.length === 0 && (
            <EmptyState
              icon={<Activity className="h-6 w-6" />}
              title="暂无规则效果数据"
              description={
                '规则效果是执行台账的聚合 —— 本租户还没有执行记录，所以这一段是空的。' +
                '18.3.2「执行」尚未交付，规则不会真的自己跑，' +
                '空在这里是**预期状态**，不是故障。'
              }
            />
          )}

          {!loading && rulePage && rulePage.items.length > 0 && (
            <div className="space-y-2">
              {rulePage.items.map((rule) => (
                <button
                  key={rule.ruleId}
                  type="button"
                  onClick={() => void openRule(rule.ruleId)}
                  aria-current={detail?.rule.ruleId === rule.ruleId}
                  className={
                    'border-border bg-background hover:bg-muted/60 block w-full rounded-md border px-3 py-2 text-left transition-colors ' +
                    (detail?.rule.ruleId === rule.ruleId
                      ? 'border-primary ring-primary/30 ring-1'
                      : '')
                  }
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-label-13 text-foreground font-medium">
                      {rule.ruleName || '（未命名规则）'}
                    </span>
                    {rule.adviseDisable && (
                      <Badge tone={BADGE_TONE.danger}>建议关闭 · 误触发率过高</Badge>
                    )}
                    <Badge tone={BADGE_TONE.muted}>
                      触发 {rule.totalRuns} 次 · 有效 {rule.ratedRuns} 次
                    </Badge>
                    <Badge tone={BADGE_TONE.muted}>成功率 {formatRate(rule.successRate)}</Badge>
                    <Badge tone={BADGE_TONE.muted}>
                      平均 {describeDuration(rule.avgDurationMs)}
                    </Badge>
                    <Badge tone={BADGE_TONE.muted}>
                      节省工时{' '}
                      {formatSavedMinutes(rule.savedMinutes, rule.manualMinutesPerRun !== null)}
                    </Badge>
                  </div>
                  {rule.adviseReason && (
                    <p className="text-muted-foreground text-copy-12 mt-1">
                      {describeAdviseReason(rule)}
                    </p>
                  )}
                </button>
              ))}
            </div>
          )}

          {rulePage && rulePage.total > 0 && (
            <div className="text-muted-foreground flex items-center justify-between text-sm">
              <span>
                共 {rulePage.total} 条 · 第 {rulePage.page + 1} / {totalRulePages} 页
              </span>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={rulePage.page <= 0 || loading}
                  onClick={() => setRulePageIndex(Math.max(0, rulePageIndex - 1))}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={rulePage.page + 1 >= totalRulePages || loading}
                  onClick={() => setRulePageIndex(rulePageIndex + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ============================================================ 区块 3：误触发反馈 */}
      <SectionCard
        title="误触发反馈"
        description="误触发率高的规则要能快速定位 —— 每条反馈都带规则（可再带具体哪一次执行）。"
        extra={
          <Button
            variant="outline"
            size="sm"
            disabled={submitting}
            onClick={() => void regenerateAdvices()}
          >
            {submitting ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Lightbulb className="h-4 w-4" />
            )}
            重新生成系统建议
          </Button>
        }
      >
        <div className="space-y-3">
          <div className="border-border space-y-3 rounded-md border p-4">
            <h3 className="text-label-13 text-foreground font-semibold">登记一条误触发反馈</h3>
            <div className="grid gap-3 md:grid-cols-4">
              <label className="block space-y-1">
                <span className="text-label-13 text-foreground">规则 id（必填）</span>
                <Input
                  aria-label="规则 id"
                  value={feedbackRuleId}
                  placeholder="规则 UUID"
                  onChange={(e) => setFeedbackRuleId(e.target.value)}
                />
              </label>
              <label className="block space-y-1">
                <span className="text-label-13 text-foreground">指向的执行（可空）</span>
                <Input
                  aria-label="指向的执行"
                  value={feedbackRunId}
                  placeholder="执行记录 UUID"
                  onChange={(e) => setFeedbackRunId(e.target.value)}
                />
              </label>
              <label className="block space-y-1">
                <span className="text-label-13 text-foreground">误触发分类</span>
                <select
                  aria-label="误触发分类"
                  className={selectClass}
                  value={feedbackType}
                  onChange={(e) => setFeedbackType(e.target.value)}
                >
                  <option value="WRONG_CONDITION">条件写错了</option>
                  <option value="WRONG_SCOPE">生效范围不对</option>
                  <option value="TOO_FREQUENT">触发太频繁</option>
                  <option value="UNEXPECTED_ACTION">执行了不该执行的动作</option>
                  <option value="OTHER">其它（必须写清说明）</option>
                </select>
              </label>
              <label className="block space-y-1">
                <span className="text-label-13 text-foreground">说明</span>
                <Input
                  aria-label="误触发说明"
                  value={feedbackNote}
                  placeholder="当时是什么情况"
                  onChange={(e) => setFeedbackNote(e.target.value)}
                />
              </label>
            </div>
            <Button
              variant="outline"
              disabled={submitting}
              onClick={() => void submitFeedbackForm()}
            >
              {submitting ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <MessageSquareWarning className="h-4 w-4" />
              )}
              登记反馈
            </Button>
          </div>

          {loading && (
            <div className="text-muted-foreground flex items-center gap-2 py-8 text-sm">
              <Loader2 className="h-4 w-4 animate-spin" />
              正在读取误触发反馈…
            </div>
          )}

          {!loading && feedbackPage && feedbackPage.items.length === 0 && (
            <EmptyState
              icon={<MessageSquareWarning className="h-6 w-6" />}
              title="暂无误触发反馈"
              description="没有反馈时误触发率是空的（不是 0%）—— 「没人报告过」与「报告了 0 次」是两件事。"
            />
          )}

          {!loading && feedbackPage && feedbackPage.items.length > 0 && (
            <div className="space-y-2">
              {feedbackPage.items.map((feedback) => (
                <div
                  key={feedback.id}
                  className="border-border bg-background rounded-md border px-3 py-2"
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-label-13 text-foreground font-medium">
                      {feedback.ruleName || '（未命名规则）'}
                    </span>
                    <Badge tone={BADGE_TONE.warn}>
                      {falseTriggerTypeLabel(
                        feedback.falseTriggerType,
                        feedback.falseTriggerTypeLabel,
                      )}
                    </Badge>
                    <Badge tone={BADGE_TONE[feedbackStatusTone(feedback.status)]}>
                      {feedback.statusLabel || feedback.status}
                    </Badge>
                    {feedback.runId ? (
                      <Badge tone={BADGE_TONE.muted}>已定位到具体执行</Badge>
                    ) : (
                      <Badge tone={BADGE_TONE.muted}>未指向具体执行 · 不进误触发率分子</Badge>
                    )}
                  </div>
                  {feedback.note && (
                    <p className="text-muted-foreground text-copy-12 mt-1">{feedback.note}</p>
                  )}
                  {feedback.status === 'OPEN' && (
                    <div className="mt-2 flex gap-2">
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={submitting}
                        onClick={() => void handleFeedbackStatus(feedback.id, 'ACCEPTED')}
                      >
                        标记已采纳
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={submitting}
                        onClick={() => void handleFeedbackStatus(feedback.id, 'DISMISSED')}
                      >
                        标记已忽略
                      </Button>
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      </SectionCard>

      {/* ============================================================ 区块 4：规则优化建议 */}
      <SectionCard
        title="规则优化建议"
        description="每条建议都带**依据**（算它用的数字）；动作归属恒为 18.3.1.1 规则档案。"
      >
        {loading && (
          <div className="text-muted-foreground flex items-center gap-2 py-8 text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在读取优化建议…
          </div>
        )}

        {!loading && advicePage && advicePage.items.length === 0 && (
          <EmptyState
            icon={<Lightbulb className="h-6 w-6" />}
            title="暂无优化建议"
            description="建议是按**当前聚合**算出来的 —— 没有执行记录时不会有建议。可以点「重新生成系统建议」按当前数据算一遍。"
          />
        )}

        {!loading && advicePage && advicePage.items.length > 0 && (
          <div className="space-y-2">
            {advicePage.items.map((advice) => (
              <div
                key={advice.id}
                className="border-border bg-background rounded-md border px-3 py-2"
              >
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={BADGE_TONE[severityTone(advice.severity)]}>
                    {severityLabel(advice.severity, advice.severityLabel)}
                  </Badge>
                  <span className="text-label-13 text-foreground font-medium">{advice.title}</span>
                  <span className="text-muted-foreground text-copy-12">
                    {advice.ruleName || '（未命名规则）'}
                  </span>
                  <Badge tone={BADGE_TONE.muted}>{adviceSourceLabel(advice.source)}</Badge>
                  {!advice.active && <Badge tone={BADGE_TONE.muted}>已不再成立</Badge>}
                </div>
                {advice.detail && (
                  <p className="text-muted-foreground text-copy-12 mt-1">{advice.detail}</p>
                )}
                {advice.evidence && (
                  <p className="text-muted-foreground text-copy-12 mt-1">
                    依据：<code className="text-copy-12">{advice.evidence}</code>
                  </p>
                )}
                {advice.status === 'OPEN' && (
                  <div className="mt-2 flex gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={submitting}
                      onClick={() => void handleAdviceStatus(advice.id, 'ADOPTED')}
                    >
                      标记已采纳
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={submitting}
                      onClick={() => void handleAdviceStatus(advice.id, 'IGNORED')}
                    >
                      标记已忽略
                    </Button>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ============================================================ 区块 5：单规则详情 + 下钻 */}
      <SectionCard
        title="规则效果详情"
        description="聚合 + 口径 + 反馈 + 建议 + 可下钻的执行记录。"
      >
        {detailLoading && (
          <div className="text-muted-foreground flex items-center gap-2 py-8 text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在读取规则效果详情…
          </div>
        )}

        {!detailLoading && !detail && (
          <EmptyState
            icon={<Target className="h-6 w-6" />}
            title="尚未选择一条规则"
            description="从上面的「逐规则效果明细」点一条规则，这里会显示它的口径、误触发反馈、优化建议与执行记录。"
          />
        )}

        {!detailLoading && detail && (
          <div className="space-y-4">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-label-13 text-foreground font-medium">
                {detail.rule.ruleName || '（未命名规则）'}
              </span>
              <Badge tone={BADGE_TONE.muted}>触发 {detail.rule.totalRuns} 次</Badge>
              <Badge tone={BADGE_TONE.muted}>有效执行 {detail.rule.ratedRuns} 次</Badge>
              <Badge tone={BADGE_TONE.muted}>成功率 {formatRate(detail.rule.successRate)}</Badge>
              <Badge tone={BADGE_TONE.muted}>
                平均耗时 {describeDuration(detail.rule.avgDurationMs)}
              </Badge>
              {detail.rule.adviseDisable && (
                <Badge tone={BADGE_TONE.danger}>建议关闭 · 误触发率过高</Badge>
              )}
            </div>

            {detail.rule.adviseDisable && (
              <Notice tone="danger" title="这条规则被建议关闭或收紧条件">
                {describeAdviseReason(detail.rule)}
                <p className="mt-2">
                  🔴 <strong>关闭动作归 18.3.1.1 规则档案</strong>，本模块<strong>不做</strong>
                  关闭 —— 只负责把人快速带到那条规则面前（规则 id{' '}
                  <code className="text-copy-12">{detail.rule.ruleId}</code>）。
                </p>
              </Notice>
            )}

            <dl className="grid gap-3 text-sm md:grid-cols-2">
              <Field label="规则 id">
                <code className="text-copy-12">{detail.rule.ruleId}</code>
              </Field>
              <Field label="触发事件类型">{detail.rule.triggerEventType || '—'}</Field>
              <Field label="人工基线（一次成功执行替代多少分钟人工）">
                {detail.rule.manualMinutesPerRun !== null
                  ? `${detail.rule.manualMinutesPerRun} 分钟（${detail.rule.baselineSource ?? '—'}）`
                  : '—（未登记口径 · 不计入节省工时合计）'}
              </Field>
              <Field label="节省工时预估">
                {formatSavedMinutes(
                  detail.rule.savedMinutes,
                  detail.rule.manualMinutesPerRun !== null,
                )}
              </Field>
              <Field label="误触发反馈">
                {detail.rule.falseTriggerFeedbacks} 条
                {detail.rule.unlinkedFeedbacks > 0
                  ? `（其中 ${detail.rule.unlinkedFeedbacks} 条未指向具体执行）`
                  : ''}
              </Field>
              <Field label="误触发率">{formatRate(detail.rule.falseTriggerRate)}</Field>
              <Field label="最近一次执行">
                {detail.rule.lastRunAt ?? '—'}
                {detail.rule.lastRunId && (
                  <>
                    {' '}
                    · <code className="text-copy-12">{detail.rule.lastRunId}</code>
                  </>
                )}
              </Field>
              <Field label="待处理建议">{detail.rule.openAdvices}</Field>
            </dl>

            {detail.baseline && (
              <Notice tone="info" title="这条规则的节省工时口径">
                {detail.baseline.manualMinutesPerRun} 分钟 / 次成功执行 · 来源{' '}
                {detail.baseline.baselineSource} · {detail.baseline.baselineNote || '（无说明）'}
              </Notice>
            )}

            {detail.advices.length > 0 && (
              <div className="space-y-2">
                <h3 className="text-label-13 text-foreground font-semibold">这条规则的建议</h3>
                {detail.advices.map((advice) => (
                  <div
                    key={advice.id}
                    className="border-border bg-background rounded-md border px-3 py-2"
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={BADGE_TONE[severityTone(advice.severity)]}>
                        {severityLabel(advice.severity, advice.severityLabel)}
                      </Badge>
                      <span className="text-label-13 text-foreground font-medium">
                        {advice.title}
                      </span>
                    </div>
                    {advice.detail && (
                      <p className="text-muted-foreground text-copy-12 mt-1">{advice.detail}</p>
                    )}
                  </div>
                ))}
              </div>
            )}

            <div className="space-y-2">
              <h3 className="text-label-13 text-foreground font-semibold">
                执行记录（共 {detail.runCount} 条，下表是最近 {detail.recentRuns.length} 条）
              </h3>
              {detail.recentRuns.length === 0 ? (
                <EmptyState
                  icon={<Activity className="h-6 w-6" />}
                  title="暂无执行记录"
                  description="没有执行记录就没有效果可统计 —— 这一段为空是预期状态。"
                />
              ) : (
                detail.recentRuns.map((run) => (
                  <div
                    key={run.id}
                    className="border-border bg-background rounded-md border px-3 py-2"
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={BADGE_TONE[outcomeTone(run.outcome)]}>
                        {outcomeLabel(run.outcome, run.outcomeLabel)}
                      </Badge>
                      <span className="text-muted-foreground text-copy-12">
                        {describeOutcome({ outcome: run.outcome, dryRun: run.dryRun })}
                      </span>
                      <span className="text-muted-foreground text-copy-12">
                        耗时 {describeDuration(run.durationMs)}
                      </span>
                      {run.failureReason && (
                        <span className="text-destructive text-copy-12">{run.failureReason}</span>
                      )}
                    </div>
                  </div>
                ))
              )}
            </div>
          </div>
        )}
      </SectionCard>

      {/* ============================================================ 区块 6：节省工时口径 */}
      <SectionCard
        title="节省工时口径"
        description="口径由租户自己登记（人工估算 / 实测均值 / 公司口径），并写清这个分钟数怎么算出来的。"
      >
        <div className="space-y-3">
          <div className="border-border space-y-3 rounded-md border p-4">
            <h3 className="text-label-13 text-foreground font-semibold">配置一条规则的口径</h3>
            <div className="grid gap-3 md:grid-cols-4">
              <label className="block space-y-1">
                <span className="text-label-13 text-foreground">规则 id（必填）</span>
                <Input
                  aria-label="口径规则 id"
                  value={baselineRuleId}
                  placeholder="规则 UUID"
                  onChange={(e) => setBaselineRuleId(e.target.value)}
                />
              </label>
              <label className="block space-y-1">
                <span className="text-label-13 text-foreground">一次成功执行替代（分钟）</span>
                <Input
                  aria-label="人工基线分钟数"
                  value={baselineMinutes}
                  placeholder="例：4"
                  onChange={(e) => setBaselineMinutes(e.target.value)}
                />
              </label>
              <label className="block space-y-1">
                <span className="text-label-13 text-foreground">口径来源</span>
                <select
                  aria-label="口径来源"
                  className={selectClass}
                  value={baselineSource}
                  onChange={(e) => setBaselineSource(e.target.value)}
                >
                  <option value="MANUAL_ESTIMATE">人工估算</option>
                  <option value="OBSERVED_AVERAGE">实测均值</option>
                  <option value="POLICY">公司口径</option>
                </select>
              </label>
              <label className="block space-y-1">
                <span className="text-label-13 text-foreground">怎么算出来的</span>
                <Input
                  aria-label="口径说明"
                  value={baselineNote}
                  placeholder="例：客服手工改一次价格约 4 分钟，按近 30 天 200 单平均得出"
                  onChange={(e) => setBaselineNote(e.target.value)}
                />
              </label>
            </div>
            <Button
              variant="outline"
              disabled={submitting}
              onClick={() => void submitBaselineForm()}
            >
              {submitting ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Gauge className="h-4 w-4" />
              )}
              保存口径
            </Button>
          </div>

          {loading && (
            <div className="text-muted-foreground flex items-center gap-2 py-8 text-sm">
              <Loader2 className="h-4 w-4 animate-spin" />
              正在读取节省工时口径…
            </div>
          )}

          {!loading && baselinePage && baselinePage.items.length === 0 && (
            <EmptyState
              icon={<Gauge className="h-6 w-6" />}
              title="暂无节省工时口径"
              description={
                '没有口径时「节省工时预估」是**空的**（不是 0 分钟）—— ' +
                '「按 0 分钟算」会把「从没评估过」说成「评估结果是省了 0 分钟」。'
              }
            />
          )}

          {!loading && baselinePage && baselinePage.items.length > 0 && (
            <div className="space-y-2">
              {baselinePage.items.map((baseline) => (
                <div
                  key={baseline.id}
                  className="border-border bg-background rounded-md border px-3 py-2"
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-label-13 text-foreground font-medium">
                      {baseline.ruleName || '（未命名规则）'}
                    </span>
                    <Badge tone={BADGE_TONE.muted}>
                      {baseline.manualMinutesPerRun} 分钟 / 次成功执行
                    </Badge>
                    <Badge tone={BADGE_TONE.muted}>{baseline.baselineSource}</Badge>
                  </div>
                  {baseline.baselineNote && (
                    <p className="text-muted-foreground text-copy-12 mt-1">
                      {baseline.baselineNote}
                    </p>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      </SectionCard>

      {/* ============================================================ 区块 7：执行记录下钻 */}
      <SectionCard
        title="执行记录下钻"
        description="报表里的每个数字都能追到这里（只读 18.3.2.1 的台账，本模块一个字都不写那两张表）。"
      >
        <div className="space-y-3">
          <label className="block max-w-xs space-y-1">
            <span className="text-label-13 text-foreground">执行结论</span>
            <select
              aria-label="执行结论"
              className={selectClass}
              value={outcome}
              onChange={(e) => {
                setOutcome(e.target.value);
                setRunPageIndex(0);
              }}
            >
              <option value="">全部</option>
              <option value="SUCCEEDED">成功</option>
              <option value="FAILED">失败</option>
              <option value="SKIPPED">跳过</option>
              <option value="TIMEOUT">超时</option>
              <option value="NOT_ATTEMPTED">未执行</option>
            </select>
          </label>

          {loading && (
            <div className="text-muted-foreground flex items-center gap-2 py-8 text-sm">
              <Loader2 className="h-4 w-4 animate-spin" />
              正在读取执行记录…
            </div>
          )}

          {!loading && runPage && runPage.items.length === 0 && (
            <EmptyState
              icon={<Activity className="h-6 w-6" />}
              title="暂无执行记录"
              description="本租户还没有执行记录 —— 这一段为空是预期状态，不是故障。"
            />
          )}

          {!loading && runPage && runPage.items.length > 0 && (
            <div className="space-y-2">
              {runPage.items.map((run) => (
                <div
                  key={run.id}
                  className="border-border bg-background rounded-md border px-3 py-2"
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={BADGE_TONE[outcomeTone(run.outcome)]}>
                      {outcomeLabel(run.outcome, run.outcomeLabel)}
                    </Badge>
                    <span className="text-muted-foreground text-copy-12">{run.eventId}</span>
                    <span className="text-muted-foreground text-copy-12">
                      耗时 {describeDuration(run.durationMs)}
                    </span>
                    {run.failureReason && (
                      <span className="text-destructive text-copy-12">{run.failureReason}</span>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}

          {runPage && runPage.total > 0 && (
            <div className="text-muted-foreground flex items-center justify-between text-sm">
              <span>
                共 {runPage.total} 条 · 第 {runPage.page + 1} / {totalRunPages} 页
              </span>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={runPage.page <= 0 || loading}
                  onClick={() => setRunPageIndex(Math.max(0, runPageIndex - 1))}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={runPage.page + 1 >= totalRunPages || loading}
                  onClick={() => setRunPageIndex(runPageIndex + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ============================================================ 区块 8：能力与边界声明 */}
      <SectionCard
        title="能力与边界声明"
        description="哪些真交付、哪些还没交付、归谁 —— 逐条报出来。"
      >
        {loading && (
          <div className="text-muted-foreground flex items-center gap-2 py-8 text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在读取能力声明…
          </div>
        )}

        {!loading && capabilities && (
          <div className="space-y-3">
            <Notice tone="info" title="关闭动作的归属">
              {disableOwnershipNote}
            </Notice>
            <div className="space-y-2">
              {capabilities.capabilities.map((capability) => (
                <div
                  key={`${capability.moduleId}-${capability.name}`}
                  className="border-border bg-background rounded-md border px-3 py-2"
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={BADGE_TONE[capability.available ? 'ok' : 'warn']}>
                      {capability.available ? '已交付' : '未交付'}
                    </Badge>
                    <span className="text-label-13 text-foreground font-medium">
                      {capability.name}
                    </span>
                    {capability.ownerModule && (
                      <Badge tone={BADGE_TONE.muted}>缺在 {capability.ownerModule}</Badge>
                    )}
                  </div>
                  <p className="text-muted-foreground text-copy-12 mt-1">
                    依据：{capability.evidence}
                  </p>
                  {capability.note && (
                    <p className="text-muted-foreground text-copy-12 mt-1">{capability.note}</p>
                  )}
                </div>
              ))}
            </div>

            {capabilities.gaps.length > 0 && (
              <div className="space-y-2">
                <h3 className="text-label-13 text-foreground font-semibold">上下游缺口</h3>
                {capabilities.gaps.map((gap) => (
                  <div
                    key={`${gap.moduleId}-${gap.name}`}
                    className="border-border bg-background rounded-md border px-3 py-2"
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={BADGE_TONE[gap.available ? 'ok' : 'warn']}>
                        {gap.available ? '已交付' : '未交付'}
                      </Badge>
                      <span className="text-label-13 text-foreground font-medium">
                        {gap.moduleId} {gap.name}
                      </span>
                      <Badge tone={BADGE_TONE.muted}>{gap.service}</Badge>
                    </div>
                    <p className="text-muted-foreground text-copy-12 mt-1">{gap.relation}</p>
                    <p className="text-muted-foreground text-copy-12 mt-1">{gap.note}</p>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </SectionCard>
    </div>
  );
}

/** 一个「标签 + 值」的小块。 */
function Field({
  label,
  children,
  wide,
}: {
  label: string;
  children: React.ReactNode;
  wide?: boolean;
}) {
  return (
    <div className={wide ? 'md:col-span-2' : undefined}>
      <dt className="text-label-13 text-muted-foreground">{label}</dt>
      <dd className="text-foreground mt-0.5">{children}</dd>
    </div>
  );
}
