'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard } from '@starcon/ui';
import {
  CheckCircle2,
  ExternalLink,
  Loader2,
  RefreshCw,
  ShieldCheck,
  TriangleAlert,
} from 'lucide-react';
import {
  bulkModeLabel,
  bulkStateLabel,
  bulkStateTone,
  canResume,
  capturedAtLabel,
  emptyAllowlistMessage,
  emptyResultsMessage,
  emptyTasksMessage,
  fetchBulkCapabilities,
  fetchBulkResults,
  fetchBulkTask,
  fetchBulkTasks,
  originLabel,
  policySummary,
  precheckCategoryUrl,
  precheckKeyword,
  precheckPageRange,
  priceLabel,
  rateLimitNote,
  resultStateLabel,
  resultStateTone,
  runBulkCollect,
  sourceLabel,
  startBulkCollect,
  taskReason,
  taskSummaryLabel,
  unavailableDimensions,
  type BulkCollectResultRow,
  type BulkCollectTaskRow,
  type ListingBulkCollectCapabilities,
} from './listing-bulk-collect-model';

/**
 * 4.4.1.2 批量/插件采集 工作区。
 *
 * 蓝图：「支持按分类页、关键词搜索结果批量采集，以及浏览器插件在用户浏览时抓取当前页商品，解决大批量找货场景。输入：分类页 URL /
 * 关键词 + 页数范围；插件上报的页面数据。输出：批量采集任务 + 结果清单。关系：上游 = 4.4.1.1；下游 = 4.4.1.3。**抓取必须有频率限制与去重**
 * （同 URL 不重复抓、已存在的商品跳过），避免重复劳动与外部站点压力。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **限频要看得见**。服务端超限返回 429 + 可读原因（还要等多少秒），页面把**原文**显示出来 ——
 *    笼统的「操作失败」会让运营以为是系统坏了。
 * 2. **去重要看得见**。清单里「已存在，跳过」的那条要明说「没有再次请求来源站点」。
 * 3. **只产生草稿**。页面没有「发布」「上架」「建档」按钮，也没有价格 / 库存输入框。
 * 4. **三态俱全**。加载态 / 空态 / 错误态都有。
 */
export function ListingBulkCollectWorkspace() {
  const [caps, setCaps] = useState<ListingBulkCollectCapabilities | null>(null);
  const [tasks, setTasks] = useState<BulkCollectTaskRow[]>([]);
  const [total, setTotal] = useState(0);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [activeTask, setActiveTask] = useState<BulkCollectTaskRow | null>(null);
  const [results, setResults] = useState<BulkCollectResultRow[]>([]);
  const [resultTotal, setResultTotal] = useState(0);
  const [resultPage, setResultPage] = useState(0);
  const [resultState, setResultState] = useState('');

  // ---- 表单
  const [mode, setMode] = useState<'CATEGORY_PAGE' | 'KEYWORD'>('CATEGORY_PAGE');
  const [sourceCode, setSourceCode] = useState('');
  const [categoryUrl, setCategoryUrl] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageFrom, setPageFrom] = useState(1);
  const [pageTo, setPageTo] = useState(2);
  const [pageUrlTemplate, setPageUrlTemplate] = useState('');

  // ---- 筛选
  const [modeFilter, setModeFilter] = useState('');
  const [stateFilter, setStateFilter] = useState('');
  const [sourceFilter, setSourceFilter] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [rejection, setRejection] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, page] = await Promise.all([
        fetchBulkCapabilities(),
        fetchBulkTasks(modeFilter || undefined, stateFilter || undefined, sourceFilter || undefined),
      ]);
      setCaps(capsResult);
      setTasks(page.items);
      setTotal(page.total);
      setActiveId((prev) => prev ?? (page.items.length > 0 ? page.items[0]?.taskId ?? null : null));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [modeFilter, stateFilter, sourceFilter]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 选中某个任务时，拉它的详情
  useEffect(() => {
    if (!activeId) {
      setActiveTask(null);
      return;
    }
    let cancelled = false;
    void fetchBulkTask(activeId)
      .then((task) => {
        if (!cancelled) setActiveTask(task);
      })
      .catch(() => {
        if (!cancelled) setActiveTask(null);
      });
    return () => {
      cancelled = true;
    };
  }, [activeId]);

  // 拉结果清单（真分页 + 状态筛选）
  useEffect(() => {
    if (!activeId) {
      setResults([]);
      setResultTotal(0);
      return;
    }
    let cancelled = false;
    void fetchBulkResults(activeId, resultState || undefined, resultPage)
      .then((page) => {
        if (cancelled) return;
        setResults(page.items);
        setResultTotal(page.total);
      })
      .catch(() => {
        if (!cancelled) {
          setResults([]);
          setResultTotal(0);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [activeId, resultState, resultPage]);

  // 🔴 发起一次批量采集：建任务 → 立刻运行。
  //    限频的拒绝（429）发生在**建任务**那一步，页面把服务端原文显示出来。
  const start = useCallback(async () => {
    const precheck =
      mode === 'CATEGORY_PAGE' ? precheckCategoryUrl(categoryUrl) : precheckKeyword(keyword);
    if (precheck) {
      setRejection(precheck);
      setError(null);
      return;
    }
    const rangeError = precheckPageRange(pageFrom, pageTo);
    if (rangeError) {
      setRejection(rangeError);
      setError(null);
      return;
    }
    if (!sourceCode) {
      setRejection('请先选择来源平台');
      setError(null);
      return;
    }
    setBusy(true);
    setRejection(null);
    setNotice(null);
    setError(null);
    try {
      const task = await startBulkCollect({
        mode,
        sourceCode,
        categoryUrl: mode === 'CATEGORY_PAGE' ? categoryUrl.trim() : '',
        keyword: mode === 'KEYWORD' ? keyword.trim() : '',
        pageFrom,
        pageTo,
        pageUrlTemplate: pageUrlTemplate.trim(),
        note: '',
      });
      setActiveId(task.taskId);
      const done = await runBulkCollect(task.taskId);
      setActiveTask(done);
      setResultPage(0);
      const limited = rateLimitNote(done);
      if (limited) setNotice(limited);
      await refresh();
    } catch (e) {
      // 🔴 429（限频）/ 400（白名单外 / 输入不合法）= 请求根本没被允许：原文显示
      setRejection(e instanceof Error ? e.message : '发起批量采集失败');
      setError(null);
      await refresh();
    } finally {
      setBusy(false);
    }
  }, [mode, sourceCode, categoryUrl, keyword, pageFrom, pageTo, pageUrlTemplate, refresh]);

  // 🔴 继续采集：服务端保留了断点，再调一次 run 即可接着采
  const resume = useCallback(async () => {
    if (!activeId) return;
    setBusy(true);
    setRejection(null);
    setNotice(null);
    setError(null);
    try {
      const done = await runBulkCollect(activeId);
      setActiveTask(done);
      setResultPage(0);
      const limited = rateLimitNote(done);
      if (limited) setNotice(limited);
      await refresh();
    } catch (e) {
      setRejection(e instanceof Error ? e.message : '继续采集失败');
      await refresh();
    } finally {
      setBusy(false);
    }
  }, [activeId, refresh]);

  const sources = caps?.sources ?? [];
  const allowlistEmpty = emptyAllowlistMessage(caps);
  const unavailable = useMemo(() => unavailableDimensions(caps), [caps]);
  const activeReason = taskReason(activeTask);
  const activeLimitNote = rateLimitNote(activeTask);
  const totalPages = Math.max(1, Math.ceil(resultTotal / 50));

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-2 py-16 text-muted-foreground">
        <Loader2 className="size-4 animate-spin" />
        <span className="text-label-13">正在加载批量采集…</span>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <SectionCard title="批量/插件采集">
        <EmptyState
          icon={<TriangleAlert className="size-8 text-destructive" />}
          title="批量采集加载失败"
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
        title="批量/插件采集"
        description="按分类页 URL 或关键词 + 页数范围批量采集，把来源页上的商品抓成采集草稿；浏览器插件在用户浏览时上报当前页商品，走同一套草稿落点。"
      />

      {error && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-start gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p className="text-label-13">{error}</p>
        </div>
      )}

      {/* ---- 🔴 限频 / 白名单拒绝：把服务端给的原文显示出来，不笼统报错 ---- */}
      {rejection && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-start gap-2 rounded-lg border px-4 py-3">
          <ShieldCheck className="mt-0.5 size-4 shrink-0" />
          <div className="space-y-1">
            <p className="text-label-13 font-medium">这次批量采集被拒绝了</p>
            <p className="text-label-13 whitespace-pre-wrap">{rejection}</p>
          </div>
        </div>
      )}

      {/* ---- 🔴 限频打断提示 ---- */}
      {notice && (
        <div className="border-brand/40 bg-brand-soft/40 flex items-start gap-2 rounded-lg border px-4 py-3">
          <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-brand" />
          <p className="text-label-13 whitespace-pre-wrap">{notice}</p>
        </div>
      )}

      {/* ---- 🔴 三条硬边界（后端给原文，前端原样展示） ---- */}
      {caps && (
        <div className="border-brand/40 bg-brand-soft/40 rounded-lg border px-4 py-3">
          <p className="text-foreground mb-1 text-label-13 font-medium">批量采集的三条硬边界</p>
          <ul className="text-muted-foreground space-y-1 text-label-12">
            <li className="flex items-start gap-2">
              <ShieldCheck className="mt-0.5 size-3.5 shrink-0 text-brand" />
              <span>{caps.draftNote}</span>
            </li>
            <li className="flex items-start gap-2">
              <CheckCircle2 className="mt-0.5 size-3.5 shrink-0 text-brand" />
              <span>{caps.dedupNote}</span>
            </li>
            <li className="flex items-start gap-2">
              <TriangleAlert className="mt-0.5 size-3.5 shrink-0 text-brand" />
              <span>{caps.rateLimitNote}</span>
            </li>
          </ul>
          <p className="text-muted-foreground mt-2 text-label-12">
            🔴 当前限频策略：{policySummary(caps.policy)}
          </p>
        </div>
      )}

      {/* ---- 未接入维度明账 ---- */}
      {unavailable.length > 0 && (
        <div className="border-warning/40 bg-warning/5 rounded-lg border px-4 py-3">
          <p className="text-warning mb-1 text-label-13 font-medium">部分能力未接入</p>
          <ul className="text-muted-foreground space-y-1 text-label-12">
            {unavailable.map((d) => (
              <li key={d.dimension}>
                <span className="text-foreground">{d.description}</span>
                ：归 {d.ownerModule} —— {d.impact}
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* ---- 发起批量采集 ---- */}
      <SectionCard
        title="发起批量采集"
        description={
          mode === 'CATEGORY_PAGE'
            ? '填一个分类页 URL + 页数范围，挨页翻下去，把页面上的商品抓成采集草稿。'
            : '填一个关键词 + 页数范围，按平台的搜索模板逐页搜，把结果里的商品抓成采集草稿。'
        }
        extra={
          <Button variant="ghost" size="sm" onClick={() => void refresh()} disabled={busy}>
            <RefreshCw className="mr-2 size-4" />
            刷新
          </Button>
        }
      >
        <div className="space-y-3 px-4 py-4">
          {allowlistEmpty && (
            <div className="border-warning/40 bg-warning/5 text-warning flex items-start gap-2 rounded-lg border px-3 py-2">
              <TriangleAlert className="mt-0.5 size-4 shrink-0" />
              <p className="text-label-13">{allowlistEmpty}</p>
            </div>
          )}

          <div className="flex flex-wrap items-center gap-2">
            <div className="border-control-border bg-card h-9 flex items-center rounded-md border px-2 text-copy-13">
              <button
                type="button"
                className={`rounded px-2 py-1 ${mode === 'CATEGORY_PAGE' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground'}`}
                onClick={() => setMode('CATEGORY_PAGE')}
                disabled={busy}
              >
                分类页批量
              </button>
              <button
                type="button"
                className={`rounded px-2 py-1 ${mode === 'KEYWORD' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground'}`}
                onClick={() => setMode('KEYWORD')}
                disabled={busy}
              >
                关键词批量
              </button>
            </div>
            <select
              className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
              value={sourceCode}
              onChange={(e) => setSourceCode(e.target.value)}
              disabled={busy}
              aria-label="来源平台"
            >
              <option value="">选择来源平台</option>
              {sources.map((s) => (
                <option key={s.code} value={s.code}>
                  {sourceLabel(s)}
                </option>
              ))}
            </select>
          </div>

          {mode === 'CATEGORY_PAGE' ? (
            <Input
              className="max-w-2xl"
              placeholder="https://s.1688.com/category/xxx.html"
              value={categoryUrl}
              disabled={busy}
              onChange={(e) => setCategoryUrl(e.target.value)}
            />
          ) : (
            <Input
              className="max-w-2xl"
              placeholder="蓝牙耳机"
              value={keyword}
              disabled={busy}
              onChange={(e) => setKeyword(e.target.value)}
            />
          )}

          <div className="flex flex-wrap items-center gap-2">
            <span className="text-muted-foreground text-label-12">页码范围</span>
            <Input
              className="w-20"
              type="number"
              min={1}
              value={pageFrom}
              disabled={busy}
              onChange={(e) => setPageFrom(Number(e.target.value))}
              aria-label="起始页"
            />
            <span className="text-muted-foreground text-label-12">到</span>
            <Input
              className="w-20"
              type="number"
              min={1}
              value={pageTo}
              disabled={busy}
              onChange={(e) => setPageTo(Number(e.target.value))}
              aria-label="结束页"
            />
            <Input
              className="max-w-xl"
              placeholder="可选：覆盖平台默认的分页模板（含 {page} / {keyword} 占位符）"
              value={pageUrlTemplate}
              disabled={busy}
              onChange={(e) => setPageUrlTemplate(e.target.value)}
            />
            <Button onClick={() => void start()} disabled={busy}>
              {busy ? (
                <>
                  <Loader2 className="mr-2 size-4 animate-spin" />
                  采集中…
                </>
              ) : (
                '开始采集'
              )}
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* ---- 批量采集任务列表 ---- */}
      <SectionCard
        title="批量采集任务"
        description={`共 ${total} 个任务。状态说的是「这次批量采集跑到哪了」，不是商品上架没上架。`}
      >
        <div className="flex flex-wrap gap-2 px-4 pt-4">
          <select
            className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
            value={modeFilter}
            onChange={(e) => setModeFilter(e.target.value)}
            aria-label="模式筛选"
          >
            <option value="">全部模式</option>
            <option value="CATEGORY_PAGE">分类页批量</option>
            <option value="KEYWORD">关键词批量</option>
            <option value="PLUGIN">浏览器插件上报</option>
          </select>
          <select
            className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
            value={stateFilter}
            onChange={(e) => setStateFilter(e.target.value)}
            aria-label="状态筛选"
          >
            <option value="">全部状态</option>
            <option value="PENDING">待运行</option>
            <option value="RUNNING">采集中</option>
            <option value="SUCCEEDED">已采完</option>
            <option value="PARTIAL">部分采完</option>
            <option value="FAILED">失败</option>
            <option value="RATE_LIMITED">被限频打断</option>
          </select>
          <select
            className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
            value={sourceFilter}
            onChange={(e) => setSourceFilter(e.target.value)}
            aria-label="来源筛选"
          >
            <option value="">全部来源</option>
            {sources.map((s) => (
              <option key={s.code} value={s.code}>
                {s.code}
              </option>
            ))}
          </select>
        </div>

        {tasks.length === 0 ? (
          <div className="px-4 py-8">
            <EmptyState
              icon={<TriangleAlert className="size-8 text-muted-foreground" />}
              title="还没有批量采集任务"
              description={emptyTasksMessage()}
            />
          </div>
        ) : (
          <ul className="divide-control-border divide-y px-4 py-2">
            {tasks.map((task) => (
              <li key={task.taskId}>
                <button
                  type="button"
                  className={`flex w-full flex-wrap items-center gap-2 px-2 py-3 text-left ${
                    activeId === task.taskId ? 'bg-muted/50' : ''
                  }`}
                  onClick={() => {
                    setActiveId(task.taskId);
                    setResultPage(0);
                  }}
                >
                  <Badge tone={bulkStateTone(task.state)}>{bulkStateLabel(task.state)}</Badge>
                  <span className="text-foreground text-label-13">
                    {bulkModeLabel(task.mode)} · {task.sourceName}
                  </span>
                  {task.mode === 'KEYWORD' ? (
                    <span className="text-muted-foreground text-label-12">「{task.keyword}」</span>
                  ) : (
                    <span className="text-muted-foreground truncate text-label-12">
                      {task.categoryUrl}
                    </span>
                  )}
                  <span className="text-muted-foreground text-label-12">
                    {task.pageFrom}–{task.pageTo} 页
                  </span>
                  <span className="text-muted-foreground text-label-12">
                    {taskSummaryLabel(task)}
                  </span>
                  <span className="text-muted-foreground ml-auto text-label-12">
                    {capturedAtLabel(task.createdAt)}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ---- 选中任务的详情 ---- */}
      {activeTask && (
        <SectionCard
          title="任务详情"
          description="🔴 采集只产生草稿：这里没有 SPU / SKU、不是平台 listing、也没有我方售价与库存。"
          extra={
            canResume(activeTask) ? (
              <Button size="sm" onClick={() => void resume()} disabled={busy}>
                {busy ? <Loader2 className="mr-2 size-4 animate-spin" /> : null}
                继续采集
              </Button>
            ) : null
          }
        >
          <div className="space-y-3 px-4 py-4">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={bulkStateTone(activeTask.state)}>{bulkStateLabel(activeTask.state)}</Badge>
              <span className="text-muted-foreground text-label-12">
                {taskSummaryLabel(activeTask)}
              </span>
              <span className="text-muted-foreground text-label-12">
                断点：第 {activeTask.nextPage} 页
              </span>
              <span className="text-muted-foreground text-label-12">
                创建于 {capturedAtLabel(activeTask.createdAt)}
              </span>
            </div>
            {activeLimitNote && (
              <div className="border-warning/40 bg-warning/5 text-warning flex items-start gap-2 rounded-lg border px-3 py-2">
                <TriangleAlert className="mt-0.5 size-4 shrink-0" />
                <p className="text-label-13 whitespace-pre-wrap">{activeLimitNote}</p>
              </div>
            )}
            {activeReason && !activeLimitNote && (
              <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-start gap-2 rounded-lg border px-3 py-2">
                <TriangleAlert className="mt-0.5 size-4 shrink-0" />
                <p className="text-label-13 whitespace-pre-wrap">{activeReason}</p>
              </div>
            )}
          </div>
        </SectionCard>
      )}

      {/* ---- 结果清单 ---- */}
      <SectionCard
        title="结果清单"
        description="每一条都是「这次批量采集碰到的某个 URL 的结果」。落成草稿的那些，下游 4.4.1.3 采集箱认领的就是它们。"
        extra={
          <select
            className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
            value={resultState}
            onChange={(e) => {
              setResultState(e.target.value);
              setResultPage(0);
            }}
            disabled={!activeId}
            aria-label="结果状态筛选"
          >
            <option value="">全部结果</option>
            <option value="FETCHED">已落成草稿</option>
            <option value="SKIPPED_DUPLICATE">已存在，跳过</option>
            <option value="FAILED">抓取失败</option>
            <option value="SKIPPED_RATE_LIMITED">被限频跳过</option>
          </select>
        }
      >
        {!activeTask ? (
          <div className="px-4 py-8">
            <EmptyState
              icon={<TriangleAlert className="size-8 text-muted-foreground" />}
              title="还没选任务"
              description="先在上面的任务列表里选一个任务，这里会显示它抓到 / 跳过了哪些商品。"
            />
          </div>
        ) : results.length === 0 ? (
          <div className="px-4 py-8">
            <EmptyState
              icon={<TriangleAlert className="size-8 text-muted-foreground" />}
              title="没有结果"
              description={emptyResultsMessage(activeTask)}
            />
          </div>
        ) : (
          <>
            <ul className="divide-control-border divide-y px-4 py-2">
              {results.map((row) => (
                <li key={row.resultId} className="space-y-1 px-2 py-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={resultStateTone(row.state)}>{resultStateLabel(row.state)}</Badge>
                    <span className="text-muted-foreground text-label-12">
                      {originLabel(row.origin)} · 第 {row.pageNo} 页
                    </span>
                    <span className="text-foreground text-label-13">{row.title || '（无标题）'}</span>
                    {row.priceAmount !== null && row.priceAmount !== undefined && (
                      <span className="text-muted-foreground text-label-12">
                        {priceLabel(row.priceAmount, row.priceCurrency)}
                      </span>
                    )}
                    <a
                      className="text-brand text-label-12 ml-auto inline-flex items-center gap-1"
                      href={row.sourceUrl}
                      target="_blank"
                      rel="noreferrer noopener"
                    >
                      来源页
                      <ExternalLink className="size-3" />
                    </a>
                  </div>
                  {row.skipReason && (
                    <p className="text-warning text-label-12">{row.skipReason}</p>
                  )}
                  {row.errorMessage && (
                    <p className="text-destructive text-label-12">{row.errorMessage}</p>
                  )}
                  <p className="text-muted-foreground text-label-12">
                    {row.normalizedUrl}
                    {row.capturedAt ? ` · 抓取于 ${capturedAtLabel(row.capturedAt)}` : ''}
                  </p>
                </li>
              ))}
            </ul>
            <div className="flex items-center justify-between px-4 py-3">
              <span className="text-muted-foreground text-label-12">
                共 {resultTotal} 条 · 第 {resultPage + 1} / {totalPages} 页
              </span>
              <div className="flex gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  disabled={resultPage <= 0}
                  onClick={() => setResultPage((p) => Math.max(0, p - 1))}
                >
                  上一页
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={resultPage + 1 >= totalPages}
                  onClick={() => setResultPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          </>
        )}
      </SectionCard>
    </div>
  );
}
