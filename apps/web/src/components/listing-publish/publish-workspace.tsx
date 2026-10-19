'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  PageHeader,
  SectionCard,
} from '@starcon/ui';
import {
  ArrowDown,
  ArrowUp,
  Loader2,
  Pause,
  Play,
  RefreshCw,
  RotateCcw,
  Send,
  Square,
  TriangleAlert,
} from 'lucide-react';
import {
  actionSummary,
  batchStateLabel,
  buildCreateRequest,
  cancelBatch,
  createBatch,
  dispatchBatch,
  dispatchSummary,
  emptyBatchesMessage,
  emptyFilters,
  fetchBatches,
  fetchBatch,
  fetchCapabilities,
  fetchRateLimits,
  fetchTasks,
  activeFilterCount,
  failureHint,
  pauseBatch,
  progressSummary,
  reorderTasks,
  resumeBatch,
  retryFailed,
  taskStateLabel,
  taskStateTone,
  unavailableDimensions,
  type BatchActionResult,
  type DispatchResponse,
  type PublishBatchRow,
  type PublishCapabilities,
  type PublishFilters,
  type PublishRateLimitRow,
  type PublishTaskRow,
} from './publish-model';

/**
 * 4.3.1.2 批量发布 工作区。
 *
 * 蓝图：「批量刊登队列：按规则筛选待发布商品，生成任务批次，支持暂停、调整顺序、批量重试。
 * 输入：筛选规则与数量上限；发布策略；用户确认。输出：批次任务列表 + 总体进度 + 失败明细
 * （可跳转 4.3.1.3）。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **限流是数据、逐平台列账**。上半部分是各平台（或店铺）自己的 API 配额与当前占用，
 *    派发结果按限流行逐条列出。「未配置限流」显示成明确的提示，不并进一个看起来正常的总数。
 * 2. **在途 ≠ 成功**。`DISPATCHED` 的徽章是「在途（等平台回报）」，与「已成功」分开。
 * 3. **暂停只停派发**。暂停按钮的提示写明已在途的不受影响。
 * 4. **重试只重试失败项**。按钮只对 FAILED 生效，没有「全部重试」入口；被服务端拒绝的项原样回显。
 */
export function PublishWorkspace() {
  const [caps, setCaps] = useState<PublishCapabilities | null>(null);
  const [rateLimits, setRateLimits] = useState<PublishRateLimitRow[]>([]);
  const [batches, setBatches] = useState<PublishBatchRow[]>([]);
  const [activeBatchId, setActiveBatchId] = useState<string | null>(null);
  const [tasks, setTasks] = useState<PublishTaskRow[]>([]);
  const [failedTasks, setFailedTasks] = useState<PublishTaskRow[]>([]);
  const [filters, setFilters] = useState<PublishFilters>(emptyFilters());
  const [limit, setLimit] = useState(50);
  const [dispatch, setDispatch] = useState<DispatchResponse | null>(null);
  const [lastAction, setLastAction] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadBatchTasks = useCallback(async (batchId: string) => {
    const [all, failed] = await Promise.all([
      fetchTasks(batchId),
      fetchTasks(batchId, 'FAILED'),
    ]);
    setTasks(all);
    setFailedTasks(failed);
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, limits, batchList] = await Promise.all([
        fetchCapabilities(),
        fetchRateLimits(),
        fetchBatches(),
      ]);
      setCaps(capsResult);
      setRateLimits(limits);
      setBatches(batchList);
      const nextActive =
        activeBatchId ?? (batchList.length > 0 ? batchList[0]!.batchId : null);
      setActiveBatchId(nextActive);
      if (nextActive) await loadBatchTasks(nextActive);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [activeBatchId, loadBatchTasks]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const run = useCallback(
    async (work: () => Promise<void>) => {
      setBusy(true);
      setError(null);
      try {
        await work();
      } catch (e) {
        setError(e instanceof Error ? e.message : '操作失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [],
  );

  const activeBatch = useMemo(
    () => batches.find((b) => b.batchId === activeBatchId) ?? null,
    [batches, activeBatchId],
  );

  // 🔴 只有 PENDING 的任务可重排；已在途/已成功的冻结其位置
  const reorderable = useMemo(() => tasks.filter((t) => t.state === 'PENDING'), [tasks]);

  function moveTask(taskId: string, delta: number) {
    const ids = reorderable.map((t) => t.taskId);
    const index = ids.indexOf(taskId);
    const target = index + delta;
    if (index < 0 || target < 0 || target >= ids.length) return;
    const next = [...ids];
    const [moved] = next.splice(index, 1);
    next.splice(target, 0, moved!);
    void run(async () => {
      const result: BatchActionResult = await reorderTasks(activeBatchId!, next);
      setLastAction(actionSummary(result, '重排'));
      // 重排后重新拉取，让用户看见次序真的变了
      await loadBatchTasks(activeBatchId!);
      setBatches((prev) => prev.map((b) => (b.batchId === activeBatchId ? result.batch : b)));
    });
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-2 py-16 text-muted-foreground">
        <Loader2 className="size-4 animate-spin" />
        <span className="text-label-13">正在加载批量发布…</span>
      </div>
    );
  }

  if (error && batches.length === 0 && !caps) {
    return (
      <SectionCard title="批量发布">
        <EmptyState
          icon={<TriangleAlert className="size-8 text-destructive" />}
          title="批量发布加载失败"
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

  return (
    <div className="space-y-4">
      <PageHeader
        title="批量发布"
        description="按规则筛选待发布商品，生成任务批次；批量任务按各平台自己的 API 配额排队，支持暂停、调整顺序与批量重试。"
      />

      {error && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-start gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p className="text-label-13">{error}</p>
        </div>
      )}

      {caps && unavailableDimensions(caps).length > 0 && (
        <div className="border-warning/40 bg-warning/5 rounded-lg border px-4 py-3">
          <p className="text-warning mb-1 text-label-13 font-medium">部分能力未接入</p>
          <ul className="text-muted-foreground space-y-1 text-label-12">
            {unavailableDimensions(caps).map((d) => (
              <li key={d.dimension}>
                <span className="text-foreground">{d.description}</span>
                ：归 {d.ownerModule} —— {d.impact}
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* ---- 🔴 平台限流配置（数据，可查）---- */}
      <SectionCard
        title="平台限流配额"
        description={caps?.rateLimitNote}
        extra={
          <Button variant="ghost" size="sm" onClick={() => void refresh()} disabled={busy}>
            <RefreshCw className="mr-2 size-4" />
            刷新
          </Button>
        }
      >
        {rateLimits.length === 0 ? (
          <EmptyState
            icon={<TriangleAlert className="size-8 text-warning" />}
            title="还没有配置任何平台的限流配额"
            description="限流参数是数据而不是服务层写死的常量。没有配置的平台，本批次一条都不会派发（fail-closed），以免触发平台限流并影响店铺安全。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-label-13">
              <thead className="text-muted-foreground border-border border-b">
                <tr>
                  <th className="px-4 py-2 font-medium">平台 / 店铺</th>
                  <th className="px-4 py-2 font-medium">在途上限</th>
                  <th className="px-4 py-2 font-medium">窗口配额</th>
                  <th className="px-4 py-2 font-medium">当前占用</th>
                  <th className="px-4 py-2 font-medium">状态</th>
                </tr>
              </thead>
              <tbody>
                {rateLimits.map((limit) => (
                  <tr key={limit.id} className="border-border border-b last:border-0">
                    <td className="text-foreground px-4 py-2">
                      {limit.platformCode}
                      <span className="text-muted-foreground">
                        {limit.shopCode ? ` / ${limit.shopCode}` : '（平台级）'}
                      </span>
                    </td>
                    <td className="px-4 py-2">{limit.maxInFlight}</td>
                    <td className="px-4 py-2">
                      {limit.requestsPerWindow} / {limit.windowSeconds}s
                    </td>
                    <td className="px-4 py-2">
                      在途 {limit.currentInFlight} · 窗口 {limit.currentWindowCount}
                    </td>
                    <td className="px-4 py-2">
                      {limit.enabled ? (
                        <Badge tone="success">已启用</Badge>
                      ) : (
                        <Badge tone="warning">已停用（本轮不派）</Badge>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* ---- 筛选 + 建批次 ---- */}
      <SectionCard
        title="筛选待发布商品"
        description="筛选规则照抄 4.1.1.1 的形状；「只发有内容草稿的」来自 4.2.1.2。"
        extra={
          <Badge tone={activeFilterCount(filters) > 0 ? 'info' : 'neutral'}>
            {activeFilterCount(filters) > 0
              ? `${activeFilterCount(filters)} 个筛选条件`
              : '未筛选（全部在售商品）'}
          </Badge>
        }
      >
        <div className="grid gap-3 px-4 py-4 sm:grid-cols-2 lg:grid-cols-4">
          <Input
            placeholder="店铺编码"
            value={filters.shopCode}
            onChange={(e) => setFilters({ ...filters, shopCode: e.target.value })}
          />
          <Input
            placeholder="状态（如 ON_SALE）"
            value={filters.status}
            onChange={(e) => setFilters({ ...filters, status: e.target.value })}
          />
          <Input
            placeholder="类目路径"
            value={filters.category}
            onChange={(e) => setFilters({ ...filters, category: e.target.value })}
          />
          <Input
            placeholder="标题 / 平台商品 ID"
            value={filters.search}
            onChange={(e) => setFilters({ ...filters, search: e.target.value })}
          />
        </div>
        <div className="text-muted-foreground flex flex-wrap items-center gap-4 px-4 pb-4 text-label-13">
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={filters.hasPendingDraft}
              onChange={(e) =>
                setFilters({ ...filters, hasPendingDraft: e.target.checked })
              }
            />
            只发有内容草稿的（4.2.1.2）
          </label>
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={filters.excludeAlreadyPublished}
              onChange={(e) =>
                setFilters({ ...filters, excludeAlreadyPublished: e.target.checked })
              }
            />
            排除已发布过的
          </label>
          <label className="flex items-center gap-2">
            数量上限
            <input
              type="number"
              min={1}
              max={1000}
              value={limit}
              onChange={(e) => setLimit(Number(e.target.value))}
              className="border-border bg-background w-24 rounded-md border px-2 py-1"
            />
          </label>
          <Button
            variant="brand"
            disabled={busy}
            onClick={() =>
              void run(async () => {
                const created = await createBatch(buildCreateRequest(filters, limit));
                setBatches((prev) => [created, ...prev]);
                setActiveBatchId(created.batchId);
                await loadBatchTasks(created.batchId);
                setDispatch(null);
                setLastAction(`已生成批次，共 ${created.totalCount} 条任务`);
              })
            }
          >
            <Send className="mr-2 size-4" />
            生成批次
          </Button>
        </div>
      </SectionCard>

      {/* ---- 批次列表 ---- */}
      <SectionCard title="发布批次" description="选择一个批次查看任务列表与总体进度。">
        {batches.length === 0 ? (
          <EmptyState
            icon={<Send className="size-8" />}
            title="还没有批量发布批次"
            description={emptyBatchesMessage()}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-label-13">
              <thead className="text-muted-foreground border-border border-b">
                <tr>
                  <th className="px-4 py-2 font-medium">创建时间</th>
                  <th className="px-4 py-2 font-medium">状态</th>
                  <th className="px-4 py-2 font-medium">进度</th>
                  <th className="px-4 py-2 font-medium">操作</th>
                </tr>
              </thead>
              <tbody>
                {batches.map((batch) => (
                  <tr
                    key={batch.batchId}
                    className={`border-border border-b last:border-0 ${
                      batch.batchId === activeBatchId ? 'bg-muted/40' : ''
                    }`}
                  >
                    <td className="text-muted-foreground px-4 py-2">
                      {new Date(batch.createdAt).toLocaleString('zh-CN')}
                    </td>
                    <td className="px-4 py-2">
                      <Badge tone={batch.state === 'PAUSED' ? 'warning' : 'neutral'}>
                        {batchStateLabel(batch.state)}
                      </Badge>
                    </td>
                    <td className="text-foreground px-4 py-2">
                      {progressSummary(batch)}
                    </td>
                    <td className="px-4 py-2">
                      <Button
                        size="sm"
                        variant={batch.batchId === activeBatchId ? 'secondary' : 'ghost'}
                        onClick={() => {
                          setActiveBatchId(batch.batchId);
                          setDispatch(null);
                          void loadBatchTasks(batch.batchId);
                        }}
                      >
                        查看
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* ---- 当前批次：进度 + 队列控制 ---- */}
      {activeBatch && (
        <SectionCard
          title={`批次任务列表 · ${progressSummary(activeBatch)}`}
          description="暂停只停止派发新任务，已在途的任务不受影响；重试只重试失败项。"
          extra={
            <div className="flex flex-wrap items-center gap-2">
              <Button
                size="sm"
                variant="secondary"
                disabled={busy || activeBatch.state === 'COMPLETED'}
                onClick={() =>
                  void run(async () => {
                    const result = await dispatchBatch(activeBatchId!);
                    setDispatch(result);
                    setBatches((prev) =>
                      prev.map((b) => (b.batchId === activeBatchId ? result.batch : b)),
                    );
                    await loadBatchTasks(activeBatchId!);
                    setRateLimits(await fetchRateLimits());
                  })
                }
              >
                <Send className="mr-2 size-4" />
                派发一轮
              </Button>
              {activeBatch.state === 'PAUSED' ? (
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={busy}
                  onClick={() =>
                    void run(async () => {
                      await resumeBatch(activeBatchId!);
                      setBatches(await fetchBatches());
                    })
                  }
                >
                  <Play className="mr-2 size-4" />
                  恢复
                </Button>
              ) : (
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={busy || activeBatch.state !== 'RUNNING'}
                  onClick={() =>
                    void run(async () => {
                      await pauseBatch(activeBatchId!, '人工暂停');
                      setBatches(await fetchBatches());
                    })
                  }
                >
                  <Pause className="mr-2 size-4" />
                  暂停
                </Button>
              )}
              <Button
                size="sm"
                variant="ghost"
                disabled={busy || activeBatch.state === 'COMPLETED'}
                onClick={() =>
                  void run(async () => {
                    const result = await retryFailed(activeBatchId!, '批量重试失败项');
                    setLastAction(actionSummary(result, '重试'));
                    await loadBatchTasks(activeBatchId!);
                    setBatches((prev) =>
                      prev.map((b) => (b.batchId === activeBatchId ? result.batch : b)),
                    );
                  })
                }
              >
                <RotateCcw className="mr-2 size-4" />
                重试失败项（{failedTasks.length}）
              </Button>
              <Button
                size="sm"
                variant="ghost"
                disabled={busy || activeBatch.state === 'COMPLETED'}
                onClick={() =>
                  void run(async () => {
                    await cancelBatch(activeBatchId!, '人工中止');
                    setBatches(await fetchBatches());
                  })
                }
              >
                <Square className="mr-2 size-4" />
                中止
              </Button>
            </div>
          }
        >
          {dispatch && (
            <div className="border-border bg-muted/30 border-b px-4 py-3">
              <p className="text-foreground mb-1 text-label-13 font-medium">
                本轮派发 {dispatch.totalDispatched} 条 · 因限流跳过 {dispatch.totalSkipped} 条
              </p>
              <ul className="text-muted-foreground space-y-0.5 text-label-12">
                {dispatchSummary(dispatch).map((line) => (
                  <li key={line}>{line}</li>
                ))}
              </ul>
            </div>
          )}
          {lastAction && (
            <div className="border-border bg-info/5 text-info border-b px-4 py-2 text-label-13">
              {lastAction}
            </div>
          )}
          {tasks.length === 0 ? (
            <EmptyState
              icon={<Send className="size-8" />}
              title="该批次还没有任务"
              description="批次任务在生成时一并创建。如果任务数为 0，说明筛选没有命中任何商品 —— 换一组筛选条件试试。"
            />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-label-13">
                <thead className="text-muted-foreground border-border border-b">
                  <tr>
                    <th className="px-4 py-2 font-medium">顺序</th>
                    <th className="px-4 py-2 font-medium">商品</th>
                    <th className="px-4 py-2 font-medium">平台 / 店铺</th>
                    <th className="px-4 py-2 font-medium">状态</th>
                    <th className="px-4 py-2 font-medium">派发次数</th>
                    <th className="px-4 py-2 font-medium">失败原因</th>
                    <th className="px-4 py-2 font-medium">调整顺序</th>
                  </tr>
                </thead>
                <tbody>
                  {tasks.map((task) => (
                    <tr key={task.taskId} className="border-border border-b last:border-0">
                      <td className="text-muted-foreground px-4 py-2">{task.position + 1}</td>
                      <td className="text-foreground px-4 py-2">
                        <div>{task.title}</div>
                        <div className="text-muted-foreground text-label-12">
                          {task.externalItemId}
                        </div>
                      </td>
                      <td className="px-4 py-2">
                        {task.platformCode}
                        <span className="text-muted-foreground"> / {task.shopCode}</span>
                      </td>
                      <td className="px-4 py-2">
                        <Badge tone={taskStateTone(task.state)}>
                          {taskStateLabel(task.state)}
                        </Badge>
                      </td>
                      <td className="px-4 py-2">{task.attempts}</td>
                      <td className="text-destructive px-4 py-2">
                        {task.failureReason ?? '—'}
                      </td>
                      <td className="px-4 py-2">
                        {task.state === 'PENDING' ? (
                          <div className="flex items-center gap-1">
                            <Button
                              size="icon"
                              variant="ghost"
                              aria-label="上移"
                              disabled={busy || task.position === reorderable[0]?.position}
                              onClick={() => moveTask(task.taskId, -1)}
                            >
                              <ArrowUp className="size-4" />
                            </Button>
                            <Button
                              size="icon"
                              variant="ghost"
                              aria-label="下移"
                              disabled={
                                busy ||
                                task.position ===
                                  reorderable[reorderable.length - 1]?.position
                              }
                              onClick={() => moveTask(task.taskId, 1)}
                            >
                              <ArrowDown className="size-4" />
                            </Button>
                          </div>
                        ) : (
                          <span className="text-muted-foreground text-label-12">不可重排</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </SectionCard>
      )}

      {/* ---- 失败明细（可跳转 4.3.1.3）---- */}
      {activeBatch && failedTasks.length > 0 && (
        <SectionCard title={`失败明细（${failedTasks.length}）`} description={failureHint()}>
          <ul className="space-y-2 px-4 py-4">
            {failedTasks.map((task) => (
              <li key={task.taskId} className="border-border rounded-md border px-3 py-2">
                <div className="text-foreground text-label-13">
                  {task.title}
                  <span className="text-muted-foreground">
                    {' '}
                    · {task.platformCode} / {task.shopCode} · 已尝试 {task.attempts} 次
                  </span>
                </div>
                <div className="text-destructive text-label-12">{task.failureReason}</div>
                <div className="text-muted-foreground text-label-12">
                  任务 ID（4.3.1.3 跳转参数）：{task.taskId}
                </div>
              </li>
            ))}
          </ul>
        </SectionCard>
      )}
    </div>
  );
}
