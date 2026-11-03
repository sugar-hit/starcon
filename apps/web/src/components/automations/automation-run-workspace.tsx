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
import { History, Loader2, RefreshCw, RotateCcw, ScrollText } from 'lucide-react';
import {
  actionSteps,
  buildRerunInput,
  conditionSteps,
  describeDuration,
  describeOutcome,
  fetchCapabilities,
  fetchOverview,
  fetchRunDetail,
  fetchRuns,
  firstFailedStep,
  newIdempotencyKey,
  outcomeLabel,
  outcomeTone,
  requestRerun,
  stepResultTone,
  summarizePayload,
  summarizeSteps,
  type Tone,
} from './automation-run-model';
import type {
  AutomationRuleRunCapabilities,
  AutomationRuleRunDetail,
  AutomationRuleRunOverview,
  AutomationRuleRunPage,
  AutomationRuleRunRow,
  AutomationRuleRunStepRow,
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
 * 18.3.2.1「执行日志」工作区。
 *
 * 🔴 页面上三件事**不许**做错：
 *
 * 1. **「重跑」不是「已经重跑」**。本仓没有规则执行引擎 ⇒ 后端返回的是
 *    `outcome = NOT_ATTEMPTED` 的**待执行**记录。页面把它明确写成
 *    「未执行 · 执行器未接通」，并展示未接通原因 —— **绝不**显示成「已重跑成功」。
 * 2. **失败要能定位到步**。详情区把逐步明细逐条摊开，并把**第一处失败**单独拎出来
 *    （哪一步、命中的是哪条 condition、错误码与错误信息）。
 * 3. **幂等是真的**。同一事件重复触发会被后端 409 拒掉；页面把这件事说清楚，
 *    而不是让运营以为「点两次就跑了两次」。
 */
export function AutomationRuleRunWorkspace() {
  const [capabilities, setCapabilities] = useState<AutomationRuleRunCapabilities | null>(null);
  const [overview, setOverview] = useState<AutomationRuleRunOverview | null>(null);
  const [page, setPage] = useState<AutomationRuleRunPage | null>(null);

  const [outcome, setOutcome] = useState('');
  const [source, setSource] = useState('');
  const [eventType, setEventType] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  const [detail, setDetail] = useState<AutomationRuleRunDetail | null>(null);
  const [rerunReason, setRerunReason] = useState('');
  const [rerunKey, setRerunKey] = useState(() => newIdempotencyKey());

  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [rerunning, setRerunning] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    const [caps, stats, runs] = await Promise.allSettled([
      fetchCapabilities(),
      fetchOverview(),
      fetchRuns({
        outcome: outcome || undefined,
        source: source || undefined,
        eventType: eventType || undefined,
        keyword: keyword || undefined,
        page: pageIndex,
        size: PAGE_SIZE,
      }),
    ]);
    if (caps.status === 'fulfilled') setCapabilities(caps.value);
    if (stats.status === 'fulfilled') setOverview(stats.value);
    if (runs.status === 'fulfilled') setPage(runs.value);
    const failed = [
      caps.status === 'rejected' ? '能力声明' : null,
      stats.status === 'rejected' ? '概览' : null,
      runs.status === 'rejected' ? '执行日志列表' : null,
    ].filter((x): x is string => x !== null);
    if (failed.length > 0) {
      setLoadError(`以下内容读不到：${failed.join('、')}`);
    }
    setLoading(false);
  }, [outcome, source, eventType, keyword, pageIndex]);

  useEffect(() => {
    void load();
  }, [load]);

  const openRun = useCallback(async (id: string) => {
    setDetailLoading(true);
    setActionError('');
    setNotice('');
    try {
      setDetail(await fetchRunDetail(id));
      setRerunReason('');
      // 每次打开重跑表单生成**新的幂等键**：同一个键重复提交只会产生一条重跑记录。
      setRerunKey(newIdempotencyKey());
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '读取执行详情失败');
      setDetail(null);
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const submitRerun = useCallback(async () => {
    if (!detail) return;
    setRerunning(true);
    setActionError('');
    setNotice('');
    try {
      const result = await requestRerun(detail.run.id, buildRerunInput(rerunReason, rerunKey));
      await load();
      await openRun(detail.run.id);
      if (result.duplicate) {
        setNotice('同一个幂等键已经提交过一次重跑，返回的是那一条记录（没有重复执行）。');
      } else {
        setNotice(
          `已登记第 ${result.detail.run.rerunSeq ?? '?'} 次重跑请求 —— ` +
            '⚠️ 执行器未接通，这次重跑**没有真的执行**（未产生任何副作用）。',
        );
      }
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '重跑请求失败');
    } finally {
      setRerunning(false);
    }
  }, [detail, rerunReason, rerunKey, load, openRun]);

  const failedStep = useMemo(() => (detail ? firstFailedStep(detail.steps) : null), [detail]);

  const executorNote = useMemo(() => {
    const capability = capabilities?.capabilities.find((c) => c.name.includes('规则执行引擎'));
    if (!capability) return '';
    if (capability.available) return '';
    return capability.note || '规则执行引擎尚未接通。';
  }, [capabilities]);

  const totalPages = page ? Math.max(1, Math.ceil(page.total / Math.max(1, page.size))) : 1;

  return (
    <div className="space-y-4">
      <PageHeader
        title="执行日志"
        description="记录每次规则执行：触发事件、条件命中情况、执行的动作、每步结果、耗时、错误；支持查看上下文与手动重跑。"
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

      {executorNote && (
        <Notice tone="warning" title="规则执行引擎（18.3.2）尚未交付">
          {executorNote}
          <p className="mt-2">
            🔴 因此本页的「重跑」**只登记一条待执行记录**（结论 = 未执行），
            **不会**真的改价、建单或发通知 —— 页面不会把它显示成「已重跑成功」。
          </p>
        </Notice>
      )}

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="执行总数"
          value={overview?.runs ?? 0}
          icon={<History className="h-4 w-4" />}
        />
        <StatCard
          label="失败"
          value={overview?.failedRuns ?? 0}
          polarity="negative"
          icon={<ScrollText className="h-4 w-4" />}
        />
        <StatCard
          label="未执行（执行器未接通）"
          value={overview?.notAttemptedRuns ?? 0}
          unit="条重跑请求落在这里"
          icon={<RotateCcw className="h-4 w-4" />}
        />
        <StatCard
          label="失败步骤"
          value={overview?.failedSteps ?? 0}
          unit="步（可从 run 下钻到 step）"
          icon={<ScrollText className="h-4 w-4" />}
        />
      </div>

      <SectionCard
        title="运行列表"
        description="筛选与关键词都是真筛选（后端 SQL 过滤），分页是真分页（后端 LIMIT/OFFSET + 总数）。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-4">
            <label className="block space-y-1">
              <span className="text-label-13 text-foreground">关键词</span>
              <Input
                aria-label="关键词"
                value={keyword}
                placeholder="规则名 / 事件 id / 触发来源"
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setPageIndex(0);
                }}
              />
            </label>
            <label className="block space-y-1">
              <span className="text-label-13 text-foreground">结论</span>
              <select
                aria-label="结论"
                className={selectClass}
                value={outcome}
                onChange={(e) => {
                  setOutcome(e.target.value);
                  setPageIndex(0);
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
            <label className="block space-y-1">
              <span className="text-label-13 text-foreground">来源</span>
              <select
                aria-label="来源"
                className={selectClass}
                value={source}
                onChange={(e) => {
                  setSource(e.target.value);
                  setPageIndex(0);
                }}
              >
                <option value="">全部</option>
                <option value="ENGINE">执行引擎写入</option>
                <option value="MANUAL">人工登记</option>
                <option value="RERUN">手动重跑</option>
              </select>
            </label>
            <label className="block space-y-1">
              <span className="text-label-13 text-foreground">触发事件类型</span>
              <Input
                aria-label="触发事件类型"
                value={eventType}
                placeholder="order.created"
                onChange={(e) => {
                  setEventType(e.target.value);
                  setPageIndex(0);
                }}
              />
            </label>
          </div>

          {loading && (
            <div className="text-muted-foreground flex items-center gap-2 py-8 text-sm">
              <Loader2 className="h-4 w-4 animate-spin" />
              正在读取执行日志…
            </div>
          )}

          {!loading && page && page.items.length === 0 && (
            <EmptyState
              icon={<History className="h-6 w-6" />}
              title="本租户还没有执行记录"
              description={
                '执行日志是在规则真的跑过一次之后才会出现的。' +
                '18.3.2「执行」尚未交付，所以现在这一段通常是空的 —— ' +
                '空在这里是**预期状态**，不是故障。'
              }
            />
          )}

          {!loading && page && page.items.length > 0 && (
            <div className="space-y-2">
              {page.items.map((run) => (
                <RunRow
                  key={run.id}
                  run={run}
                  selected={detail?.run.id === run.id}
                  onOpen={() => void openRun(run.id)}
                />
              ))}
            </div>
          )}

          {page && page.total > 0 && (
            <div className="text-muted-foreground flex items-center justify-between text-sm">
              <span>
                共 {page.total} 条 · 第 {page.page + 1} / {totalPages} 页
              </span>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page.page <= 0 || loading}
                  onClick={() => setPageIndex(Math.max(0, pageIndex - 1))}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page.page + 1 >= totalPages || loading}
                  onClick={() => setPageIndex(pageIndex + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      <SectionCard
        title="运行详情"
        description="逐步明细与失败定位：失败原因可以从 run 直接下钻到 step。"
      >
        {detailLoading && (
          <div className="text-muted-foreground flex items-center gap-2 py-8 text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在读取执行详情…
          </div>
        )}

        {!detailLoading && !detail && (
          <EmptyState
            icon={<ScrollText className="h-6 w-6" />}
            title="尚未选择一次执行"
            description="从上面的运行列表点一行，这里会显示这次执行的上下文、逐步明细与重跑入口。"
          />
        )}

        {!detailLoading && detail && (
          <div className="space-y-4">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={BADGE_TONE[outcomeTone(detail.run.outcome)]}>
                {outcomeLabel(detail.run.outcome, detail.run.outcomeLabel)}
              </Badge>
              <Badge tone={BADGE_TONE[outcomeTone(detail.run.dryRun ? 'SKIPPED' : 'SUCCEEDED')]}>
                {detail.run.dryRun ? '试运行 · 不产生副作用' : '真实执行'}
              </Badge>
              <span className="text-muted-foreground text-sm">{describeOutcome(detail.run)}</span>
            </div>

            <dl className="grid gap-3 text-sm md:grid-cols-2">
              <Field label="规则">{detail.run.ruleName || '（未命名规则）'}</Field>
              <Field label="触发事件 id">{detail.run.eventId}</Field>
              <Field label="触发事件类型">{detail.run.triggerEventType || '—'}</Field>
              <Field label="触发来源">{detail.run.triggeredBy || '—'}</Field>
              <Field label="总耗时">{describeDuration(detail.run.durationMs)}</Field>
              <Field label="幂等键">
                <code className="text-copy-12">{detail.run.idempotencyKey}</code>
              </Field>
              <Field label="触发上下文" wide>
                <code className="text-copy-12 break-all">
                  {summarizePayload(detail.run.eventPayload)}
                </code>
              </Field>
              {detail.run.rerunOfRunId && (
                <Field label="这是一次重跑" wide>
                  第 {detail.run.rerunSeq ?? '?'} 次重跑 · 原执行记录{' '}
                  <code className="text-copy-12">{detail.run.rerunOfRunId}</code>
                  {detail.run.rerunReason ? ` · 理由：${detail.run.rerunReason}` : ''}
                </Field>
              )}
            </dl>

            {detail.run.outcome === 'NOT_ATTEMPTED' && detail.run.notAttemptedReason && (
              <Notice tone="warning" title="这一次没有真的执行">
                {detail.run.notAttemptedReason}
              </Notice>
            )}

            {detail.run.failureReason && (
              <Notice tone="danger" title={`执行失败：${detail.run.failureReason}`}>
                {detail.run.errorCode ? `错误码 ${detail.run.errorCode}` : ''}
                {detail.run.errorMessage ? ` · ${detail.run.errorMessage}` : ''}
              </Notice>
            )}

            {failedStep && (
              <Notice tone="danger" title={`卡在第 ${failedStep.stepNo} 步`}>
                {failedStep.stepKind === 'CONDITION'
                  ? `条件判定（${failedStep.fieldPath || '未记录字段'} ${failedStep.operator || ''}）`
                  : `动作执行（${failedStep.actionType || '未记录动作类型'}）`}
                {failedStep.errorCode ? ` · 错误码 ${failedStep.errorCode}` : ''}
                {failedStep.errorMessage ? ` · ${failedStep.errorMessage}` : ''}
                {failedStep.conditionSeq !== null && failedStep.conditionSeq !== undefined
                  ? ` · 命中的是第 ${failedStep.conditionSeq} 条条件`
                  : ''}
              </Notice>
            )}

            <StepTable steps={detail.steps} />

            <div className="border-border space-y-3 rounded-md border p-4">
              <div className="flex flex-wrap items-center gap-2">
                <h3 className="text-label-13 text-foreground font-semibold">手动重跑</h3>
                <Badge tone={BADGE_TONE.warn}>只登记请求 · 不会真的执行</Badge>
              </div>
              <p className="text-muted-foreground text-copy-13">
                本仓当前**没有**规则执行引擎 ⇒ 这次重跑会落成一条
                <strong>未执行</strong>的记录（结论 = 未执行，未产生任何副作用），
                并与这一次执行显式关联。原执行记录**不会被改写**。
              </p>
              <label className="block space-y-1">
                <span className="text-label-13 text-foreground">重跑理由</span>
                <Input
                  aria-label="重跑理由"
                  value={rerunReason}
                  placeholder="为什么再跑一次"
                  onChange={(e) => setRerunReason(e.target.value)}
                />
              </label>
              <Button variant="outline" disabled={rerunning} onClick={() => void submitRerun()}>
                {rerunning ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <RotateCcw className="h-4 w-4" />
                )}
                提交重跑请求
              </Button>
            </div>
          </div>
        )}
      </SectionCard>
    </div>
  );
}

/** 列表里的一行。 */
function RunRow({
  run,
  selected,
  onOpen,
}: {
  run: AutomationRuleRunRow;
  selected: boolean;
  onOpen: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onOpen}
      aria-current={selected}
      className={
        'border-border bg-background hover:bg-muted/60 block w-full rounded-md border px-3 py-2 text-left transition-colors ' +
        (selected ? 'border-primary ring-primary/30 ring-1' : '')
      }
    >
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={BADGE_TONE[outcomeTone(run.outcome)]}>
          {outcomeLabel(run.outcome, run.outcomeLabel)}
        </Badge>
        <span className="text-label-13 text-foreground font-medium">
          {run.ruleName || '（未命名规则）'}
        </span>
        <span className="text-muted-foreground text-copy-12">
          {run.triggerEventType || '未知事件'} · {run.eventId}
        </span>
        <span className="text-muted-foreground text-copy-12 ml-auto">
          {describeDuration(run.durationMs)}
        </span>
      </div>
      <div className="text-muted-foreground text-copy-12 mt-1">
        {describeOutcome(run)}
        {run.rerunOfRunId ? ` · 第 ${run.rerunSeq ?? '?'} 次重跑` : ''}
      </div>
    </button>
  );
}

/** 逐步明细表（**条件判定**与**动作执行**分段，条件命中情况逐条可见）。 */
function StepTable({ steps }: { steps: AutomationRuleRunStepRow[] }) {
  const summary = summarizeSteps(steps);
  const conditions = conditionSteps(steps);
  const actions = actionSteps(steps);

  if (steps.length === 0) {
    return (
      <EmptyState
        icon={<ScrollText className="h-6 w-6" />}
        title="这次执行没有逐步明细"
        description="逐步明细是登记执行时一起写进来的。没有明细 ⇒ 这次执行没有记录到任何一步 —— 页面如实显示空，不编造步骤。"
      />
    );
  }

  return (
    <div className="space-y-3">
      <div className="text-muted-foreground text-copy-12">
        共 {summary.total} 步 · 成功 {summary.succeeded} · 失败 {summary.failed} · 未命中{' '}
        {summary.notMatched} · 未执行 {summary.notAttempted}
      </div>
      {conditions.length > 0 && <StepGroup title="条件判定" steps={conditions} showCondition />}
      {actions.length > 0 && <StepGroup title="动作执行" steps={actions} showCondition={false} />}
    </div>
  );
}

/** 一组步骤。 */
function StepGroup({
  title,
  steps,
  showCondition,
}: {
  title: string;
  steps: AutomationRuleRunStepRow[];
  showCondition: boolean;
}) {
  return (
    <div className="space-y-2">
      <h4 className="text-label-13 text-foreground font-semibold">{title}</h4>
      {steps.map((step) => (
        <div key={step.id} className="border-border rounded-md border px-3 py-2">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-muted-foreground text-copy-12">第 {step.stepNo} 步</span>
            <Badge tone={BADGE_TONE[stepResultTone(step.result)]}>
              {step.resultLabel || step.result}
            </Badge>
            {showCondition && (
              <Badge tone={BADGE_TONE[step.matched ? 'ok' : 'muted']}>
                {step.matched === null
                  ? '未记录命中情况'
                  : step.matched
                    ? '条件命中'
                    : '条件未命中'}
              </Badge>
            )}
            {step.actionType && (
              <span className="text-label-13 text-foreground">{step.actionType}</span>
            )}
            <span className="text-muted-foreground text-copy-12 ml-auto">
              {describeDuration(step.durationMs)}
            </span>
          </div>
          {showCondition && (
            <p className="text-muted-foreground text-copy-12 mt-1">
              {step.fieldPath || '未记录字段路径'} {step.operator || ''}{' '}
              {step.expectedValue ? `期望 ${step.expectedValue}` : ''}{' '}
              {step.actualValue ? `· 实际 ${step.actualValue}` : ''}
              {step.conditionSeq !== null && step.conditionSeq !== undefined
                ? ` · 第 ${step.conditionSeq} 条条件`
                : ''}
            </p>
          )}
          {step.targetRef && (
            <p className="text-muted-foreground text-copy-12 mt-1">目标 {step.targetRef}</p>
          )}
          {step.errorCode || step.errorMessage ? (
            <p className="text-destructive text-copy-12 mt-1">
              {step.errorCode ? `错误码 ${step.errorCode}` : ''}
              {step.errorMessage ? ` · ${step.errorMessage}` : ''}
            </p>
          ) : null}
        </div>
      ))}
    </div>
  );
}

/** 详情里的一个字段。 */
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
      <dt className="text-muted-foreground text-copy-12">{label}</dt>
      <dd className="text-foreground text-copy-13 mt-0.5 break-words">{children}</dd>
    </div>
  );
}
