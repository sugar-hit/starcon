'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, Segmented } from '@starcon/ui';
import { ListChecks, Loader2, RefreshCw, RotateCcw, TriangleAlert } from 'lucide-react';
import {
  attemptOutcomeLabel,
  canRetryTask,
  capabilityUnavailableMessage,
  durationLabel,
  emptyStateMessage,
  errorLine,
  errorScopeLabel,
  fetchCapabilities,
  fetchTask,
  fetchTasks,
  groupTimelineByPhase,
  retryOutcomeLine,
  retryStep,
  retryTask,
  stageLabel,
  stageTone,
  stepScopeLabel,
  stepStateLabel,
  stepStateTone,
  taskKindBadge,
  taskKindLabel,
  taskLabel,
  taskRetryHint,
  timeLabel,
  unavailableDimensions,
  type ListingPublishTaskCapabilities,
  type ListingPublishTaskDetail,
  type ListingPublishTaskPage,
  type ListingPublishTaskStepRow,
} from './listing-publish-task-model';

/**
 * 4.3.1.3 发布任务 工作区。
 *
 * 蓝图：「单次发布任务的进度视图：各步骤状态（内容校验 → 平台创建 → 变体创建 → 媒体上传 → 价格库存设置 → 提交审核）、失败步骤与原因、可重试的步骤。输入：任务 ID；平台返回结果。输出：步骤时间线 + 错误明细 + 重试操作。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「审核中」不是「已发布」**：阶段徽章写「审核中（等平台回执）」，并把后端给的
 *    异步说明原样展示。
 * 2. **重试成功 ≠ 平台审核通过**：重试后只说「已重新提交平台」。
 * 3. **两条上游的状态不揉**：来源标签 + 原始状态原样显示。
 * 4. **不可重试的步骤也给理由**：不给点了没反应的按钮。
 * 5. **批量发布的队列任务没有步骤行**：显示「本任务没有步骤时间线」+ 原因。
 */
export function ListingPublishTaskWorkspace() {
  const [caps, setCaps] = useState<ListingPublishTaskCapabilities | null>(null);
  const [page, setPage] = useState<ListingPublishTaskPage | null>(null);
  const [detail, setDetail] = useState<ListingPublishTaskDetail | null>(null);
  const [activeTaskId, setActiveTaskId] = useState<string | null>(null);
  const [lastAction, setLastAction] = useState<string | null>(null);

  // ---- 筛选
  const [kind, setKind] = useState('');
  const [stage, setStage] = useState('');
  const [platformId, setPlatformId] = useState('');
  const [shopId, setShopId] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadDetail = useCallback(async (taskId: string) => {
    const result = await fetchTask(taskId);
    setDetail(result);
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, pageResult] = await Promise.all([
        fetchCapabilities(),
        fetchTasks({
          ...(kind ? { kind } : {}),
          ...(stage ? { stage } : {}),
          ...(platformId.trim() ? { platformId: platformId.trim() } : {}),
          ...(shopId.trim() ? { shopId: shopId.trim() } : {}),
          page: pageIndex,
          size: 20,
        }),
      ]);
      setCaps(capsResult);
      setPage(pageResult);
      const nextActive =
        activeTaskId && pageResult.rows.some((row) => row.taskId === activeTaskId)
          ? activeTaskId
          : (pageResult.rows[0]?.taskId ?? null);
      setActiveTaskId(nextActive);
      if (nextActive) {
        await loadDetail(nextActive);
      } else {
        setDetail(null);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [activeTaskId, kind, platformId, shopId, stage, pageIndex, loadDetail]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kind, stage, platformId, shopId, pageIndex]);

  const run = useCallback(async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败，请重试');
    } finally {
      setBusy(false);
    }
  }, []);

  const onRetryStep = useCallback(
    (step: ListingPublishTaskStepRow) =>
      run(async () => {
        const result = await retryStep(detail!.task.taskId, step.stepKey);
        setLastAction(retryOutcomeLine(result));
        await refresh();
      }),
    [detail, refresh, run],
  );

  const onRetryTask = useCallback(
    () =>
      run(async () => {
        const result = await retryTask(detail!.task.taskId);
        setLastAction(retryOutcomeLine(result));
        await refresh();
      }),
    [detail, refresh, run],
  );

  const groups = useMemo(
    () => (caps && detail ? groupTimelineByPhase(detail.timeline, caps.phases) : []),
    [caps, detail],
  );

  if (loading && !caps) {
    return (
      <div className="text-muted-foreground flex items-center justify-center gap-2 py-16">
        <Loader2 className="size-4 animate-spin" />
        <span className="text-label-13">正在加载发布任务…</span>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <SectionCard title="发布任务">
        <EmptyState
          icon={<TriangleAlert className="text-destructive size-8" />}
          title="发布任务加载失败"
          description={error}
          action={
            <Button variant="secondary" onClick={() => void refresh()}>
              <RefreshCw className="mr-2 size-4" />
              重试
            </Button>
          }
        />
      </SectionCard>
    );
  }

  const rows = page?.rows ?? [];
  const total = page?.total ?? 0;

  return (
    <div className="space-y-4">
      <PageHeader
        title="发布任务"
        description="单次发布任务的进度视图：各步骤状态、失败步骤与原因、可重试的步骤。平台提交审核是异步的 —— 任务走到「提交审核」就进入「审核中」，实际结果由 4.3.2.2 平台状态回流写回。"
      />

      {error && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-start gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p className="text-label-13">{error}</p>
        </div>
      )}

      {lastAction && (
        <div className="border-info/40 bg-info/5 rounded-lg border px-4 py-3">
          <p className="text-label-13">{lastAction}</p>
        </div>
      )}

      {caps && unavailableDimensions(caps).length > 0 && (
        <div className="border-warning/40 bg-warning/5 rounded-lg border px-4 py-3">
          <p className="text-warning text-label-13 mb-1 font-medium">部分能力未接入</p>
          <ul className="text-muted-foreground text-label-12 space-y-1">
            {unavailableDimensions(caps).map((dimension) => (
              <li key={dimension.dimension}>
                <span className="text-foreground">{dimension.description}</span>
                ：归 {dimension.ownerModule} —— {capabilityUnavailableMessage(dimension.dimension)}
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* ---- 筛选 + 任务列表 ---- */}
      <SectionCard
        title="发布任务"
        description={caps?.nonTransactionalNote}
        extra={
          <Button variant="ghost" size="sm" onClick={() => void refresh()} disabled={busy}>
            <RefreshCw className="mr-2 size-4" />
            刷新
          </Button>
        }
      >
        <div className="space-y-4">
          <div className="flex flex-wrap items-end gap-3">
            <div className="space-y-1.5">
              <p className="text-label-12 text-muted-foreground">任务来源</p>
              <Segmented
                aria-label="任务来源"
                value={kind}
                onChange={(value) => {
                  setKind(value);
                  setPageIndex(0);
                }}
                options={[
                  { label: '全部', value: '' },
                  { label: '一键刊登（4.3.1.1）', value: 'ONECLICK' },
                  { label: '批量发布（4.3.1.2）', value: 'BATCH' },
                ]}
              />
            </div>
            <div className="space-y-1.5">
              <p className="text-label-12 text-muted-foreground">阶段</p>
              <Segmented
                aria-label="任务阶段"
                value={stage}
                onChange={(value) => {
                  setStage(value);
                  setPageIndex(0);
                }}
                options={[
                  { label: '全部', value: '' },
                  { label: '待提交', value: 'PENDING' },
                  { label: '审核中', value: 'IN_REVIEW' },
                  { label: '已成功', value: 'SUCCEEDED' },
                  { label: '驳回', value: 'REJECTED' },
                  { label: '失败', value: 'FAILED' },
                ]}
              />
            </div>
            <div className="space-y-1.5">
              <label className="text-label-12 text-muted-foreground" htmlFor="lpt-platform">
                平台 id
              </label>
              <Input
                id="lpt-platform"
                className="w-56"
                placeholder="平台 id（UUID）"
                value={platformId}
                onChange={(e) => {
                  setPlatformId(e.target.value);
                  setPageIndex(0);
                }}
              />
            </div>
            <div className="space-y-1.5">
              <label className="text-label-12 text-muted-foreground" htmlFor="lpt-shop">
                店铺 id
              </label>
              <Input
                id="lpt-shop"
                className="w-56"
                placeholder="店铺 id（UUID）"
                value={shopId}
                onChange={(e) => {
                  setShopId(e.target.value);
                  setPageIndex(0);
                }}
              />
            </div>
          </div>

          {loading ? (
            <div className="text-muted-foreground flex items-center justify-center gap-2 py-10">
              <Loader2 className="size-4 animate-spin" />
              <span className="text-label-13">正在加载任务…</span>
            </div>
          ) : rows.length === 0 ? (
            <EmptyState
              icon={<ListChecks className="text-muted-foreground size-8" />}
              title="没有符合条件的发布任务"
              description={emptyStateMessage('tasks')}
            />
          ) : (
            <div className="space-y-2">
              {rows.map((row) => {
                const active = row.taskId === activeTaskId;
                return (
                  <button
                    key={row.taskId}
                    type="button"
                    onClick={() => {
                      setActiveTaskId(row.taskId);
                      void loadDetail(row.taskId).catch((e) =>
                        setError(e instanceof Error ? e.message : '加载详情失败'),
                      );
                    }}
                    className={
                      active
                        ? 'border-primary bg-muted/40 w-full rounded-md border px-3 py-2 text-left'
                        : 'border-border hover:bg-muted/30 w-full rounded-md border px-3 py-2 text-left'
                    }
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={stageTone(row.stage)}>{stageLabel(row.stage)}</Badge>
                      <Badge tone="neutral">{taskKindBadge(row.taskKind)}</Badge>
                      <span className="text-foreground text-label-13">
                        {taskLabel({
                          platformCode: row.platformCode,
                          shopCode: row.shopCode,
                          marketplaceCode: row.marketplaceCode,
                          locale: row.locale,
                        })}
                      </span>
                      {row.failedStepCount > 0 && (
                        <Badge tone="danger">{row.failedStepCount} 步失败</Badge>
                      )}
                      <span className="text-muted-foreground text-copy-12 ml-auto">
                        {timeLabel(row.createdAt)} · 尝试 {row.attempts} 次
                      </span>
                    </div>
                    {row.failureReason && (
                      <p className="text-destructive text-copy-12 mt-1">{row.failureReason}</p>
                    )}
                    {!row.stepTimelineAvailable && (
                      <p className="text-muted-foreground text-copy-12 mt-1">
                        本任务没有步骤时间线（4.3.1.2 的队列任务不落步骤行）
                      </p>
                    )}
                  </button>
                );
              })}
            </div>
          )}

          <div className="flex items-center justify-between">
            <span className="text-muted-foreground text-label-12">
              共 {total} 条 · 第 {(page?.page ?? 0) + 1} 页
            </span>
            <div className="flex gap-2">
              <Button
                variant="secondary"
                size="sm"
                disabled={pageIndex === 0}
                onClick={() => setPageIndex((v) => Math.max(0, v - 1))}
              >
                上一页
              </Button>
              <Button
                variant="secondary"
                size="sm"
                disabled={(pageIndex + 1) * (page?.size ?? 20) >= total}
                onClick={() => setPageIndex((v) => v + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        </div>
      </SectionCard>

      {/* ---- 任务详情：步骤时间线 + 错误明细 + 重试 ---- */}
      {detail && (
        <SectionCard
          title="任务详情"
          description={`${taskKindLabel(detail.task.taskKind)} · ${taskLabel({
            platformCode: detail.task.platformCode,
            shopCode: detail.task.shopCode,
            marketplaceCode: detail.task.marketplaceCode,
            locale: detail.task.locale,
          })}`}
          extra={
            <div className="flex items-center gap-2">
              <Badge tone={stageTone(detail.task.stage)}>{stageLabel(detail.task.stage)}</Badge>
              <Badge tone="neutral">原始状态 {detail.task.state}</Badge>
              {canRetryTask(detail.task) && (
                <Button size="sm" onClick={() => void onRetryTask()} disabled={busy}>
                  <RotateCcw className="mr-2 size-4" />
                  重试整条任务
                </Button>
              )}
            </div>
          }
        >
          <div className="space-y-4">
            <div className="text-muted-foreground text-label-12 space-y-1">
              <p>🔴 {detail.asyncReviewNote}</p>
              {detail.contentSnapshotTitle && (
                <p>
                  当时冻结的内容：<span className="text-foreground">{detail.contentSnapshotTitle}</span>
                </p>
              )}
              {detail.externalItemId && (
                <p>
                  平台商品 id：<span className="text-foreground">{detail.externalItemId}</span>
                </p>
              )}
              {detail.task.state === 'FAILED' && (
                <p className="text-destructive">任务级重试：{taskRetryHint(detail.task)}</p>
              )}
              {detail.task.state === 'REJECTED' && (
                <p className="text-warning">任务级重试：{taskRetryHint(detail.task)}</p>
              )}
            </div>

            {/* 步骤时间线 */}
            <div className="space-y-3">
              <p className="text-foreground text-label-13 font-medium">步骤时间线</p>
              {!detail.stepTimelineAvailable ? (
                <EmptyState
                  icon={<ListChecks className="text-muted-foreground size-8" />}
                  title="本任务没有步骤时间线"
                  description={detail.stepTimelineNote}
                />
              ) : detail.timeline.length === 0 ? (
                <EmptyState
                  icon={<ListChecks className="text-muted-foreground size-8" />}
                  title="还没有步骤"
                  description={emptyStateMessage('timeline')}
                />
              ) : (
                groups.map((group) => (
                  <div key={group.phase} className="space-y-2">
                    <div className="flex items-center gap-2">
                      <span className="text-foreground text-label-13 font-medium">{group.phase}</span>
                      {group.steps.length === 0 && (
                        <Badge tone="warning">没有独立步骤行</Badge>
                      )}
                    </div>
                    {group.note && group.steps.length === 0 && (
                      <p className="text-muted-foreground text-copy-12">{group.note}</p>
                    )}
                    {group.steps.map((step) => (
                      <div
                        key={step.stepId}
                        className="border-border bg-muted/20 rounded-md border px-3 py-2"
                      >
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge tone={stepStateTone(step.state)}>{stepStateLabel(step.state)}</Badge>
                          <span className="text-foreground text-label-13">{step.stepKey}</span>
                          <Badge tone="neutral">{stepScopeLabel(step.scope)}</Badge>
                          {step.attemptCount > 0 && (
                            <span className="text-muted-foreground text-copy-12">
                              重试 {step.attemptCount} 次
                            </span>
                          )}
                          <span className="text-muted-foreground text-copy-12 ml-auto">
                            {timeLabel(step.startedAt)}
                            {step.finishedAt ? ` → ${timeLabel(step.finishedAt)}` : ''}
                            {step.durationMillis !== null
                              ? ` · ${durationLabel(step.durationMillis)}`
                              : ''}
                          </span>
                          {step.retryable ? (
                            <Button
                              variant="secondary"
                              size="sm"
                              disabled={busy}
                              onClick={() => void onRetryStep(step)}
                            >
                              <RotateCcw className="mr-2 size-4" />
                              重试这一步
                            </Button>
                          ) : (
                            step.retryBlockedReason && (
                              <span className="text-muted-foreground text-copy-12 max-w-md truncate">
                                不可重试：{step.retryBlockedReason}
                              </span>
                            )
                          )}
                        </div>
                        {step.detail && (
                          <p className="text-muted-foreground text-copy-12 mt-1">{step.detail}</p>
                        )}
                        {step.attempts.length > 0 && (
                          <ul className="text-muted-foreground text-copy-12 mt-2 space-y-1">
                            {step.attempts.map((attempt) => (
                              <li key={attempt.attemptId}>
                                第 {attempt.attemptNo} 次 · {attemptOutcomeLabel(attempt.outcome)}
                                {attempt.reason ? ` —— ${attempt.reason}` : ''}
                                {attempt.requestedAt ? `（${timeLabel(attempt.requestedAt)}）` : ''}
                              </li>
                            ))}
                          </ul>
                        )}
                      </div>
                    ))}
                  </div>
                ))
              )}
            </div>

            {/* 错误明细 */}
            <div className="space-y-2">
              <p className="text-foreground text-label-13 font-medium">错误明细</p>
              {detail.errors.length === 0 ? (
                <p className="text-muted-foreground text-copy-12">{emptyStateMessage('errors')}</p>
              ) : (
                <ul className="space-y-1">
                  {detail.errors.map((error, index) => (
                    <li key={`${error.scope}-${error.stepKey ?? 'task'}-${index}`} className="flex gap-2">
                      <Badge tone={error.scope === 'TASK' ? 'danger' : 'warning'}>
                        {errorScopeLabel(error.scope)}
                      </Badge>
                      <span className="text-copy-12 text-foreground">{errorLine(error)}</span>
                      {error.occurredAt && (
                        <span className="text-muted-foreground text-copy-12">{timeLabel(error.occurredAt)}</span>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {/* 平台侧发布留痕 */}
            {detail.publishRecords.length > 0 && (
              <div className="space-y-2">
                <p className="text-foreground text-label-13 font-medium">平台侧发布留痕</p>
                <ul className="space-y-1">
                  {detail.publishRecords.map((record) => (
                    <li key={record.recordId} className="text-copy-12">
                      <Badge tone="neutral">{record.action}</Badge>
                      <span className="text-foreground ml-2">{record.outcome}</span>
                      {record.reason ? ` —— ${record.reason}` : ''}
                      {record.platformRequestId ? ` · 请求 ${record.platformRequestId}` : ''}
                      {record.occurredAt ? ` · ${timeLabel(record.occurredAt)}` : ''}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        </SectionCard>
      )}
    </div>
  );
}
