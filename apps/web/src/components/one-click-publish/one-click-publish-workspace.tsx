'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard } from '@starcon/ui';
import { Loader2, RefreshCw, RotateCcw, Send, TriangleAlert } from 'lucide-react';
import {
  buildCreateRequest,
  canRetry,
  contentSourceCandidates,
  createJob,
  dispatchJob,
  dispatchSummary,
  fetchCapabilities,
  fetchContentSources,
  fetchJob,
  fetchJobs,
  fetchSteps,
  fetchTargetOptions,
  fetchTargets,
  outcomeLabel,
  priceStateNotice,
  progressSummary,
  retryHint,
  retryTarget,
  stockStateNotice,
  targetOptionKey,
  targetOptionLabel,
  targetStateLabel,
  targetStateTone,
  unavailableDimensions,
  validateCreateInput,
  type ListingPublishCapabilities,
  type ListingPublishContentSources,
  type ListingPublishJobRow,
  type ListingPublishStepRow,
  type ListingPublishTargetInput,
  type ListingPublishTargetOption,
  type ListingPublishTargetRow,
} from './one-click-publish-model';

/**
 * 4.3.1.1 一键刊登 工作区。
 *
 * 蓝图：「单个 SKU 一次性发布到多个目标店铺/站点：自动套模板、填充内容、建立映射、上传媒体、设置价格库存、提交平台审核。输入：源 SKU；目标店铺与站点集合；模板选择；价格库存来源策略；`idempotency_key`。输出：N 个发布任务（每个目标一个）+ 各任务的进度与结果（草稿/审核中/成功/驳回+原因）。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **进度与结论原样显示**。计数、百分比、总体结论全部来自后端（它是目标行的聚合），
 *    前端一个数都不自己算。
 * 2. **「审核中」不是「已发布」**。徽章文案与「已成功」分开。
 * 3. **取价不可用就显示不可用**，并给出归属模块 5.1.1.1 —— 不编一个价、不显示成 0。
 * 4. **重试只重试失败的那一个**；被驳回的目标没有重试按钮（内容没改，重发只会再被驳回）。
 * 5. **跨平台不是一个事务**：结果清单逐目标列，某个目标失败不影响其它目标的显示。
 */
export function OneClickPublishWorkspace() {
  const [caps, setCaps] = useState<ListingPublishCapabilities | null>(null);
  const [options, setOptions] = useState<ListingPublishTargetOption[]>([]);
  const [sources, setSources] = useState<ListingPublishContentSources | null>(null);
  const [jobs, setJobs] = useState<ListingPublishJobRow[]>([]);
  const [activeJobId, setActiveJobId] = useState<string | null>(null);
  const [targets, setTargets] = useState<ListingPublishTargetRow[]>([]);
  const [steps, setSteps] = useState<ListingPublishStepRow[]>([]);
  const [dispatchNote, setDispatchNote] = useState<string[] | null>(null);

  // 表单
  const [sourceSkuId, setSourceSkuId] = useState('');
  const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
  const [locales, setLocales] = useState<Record<string, string>>({});
  const [contentSource, setContentSource] = useState('EDITOR_SUBMISSION');
  const [contentSourceIds, setContentSourceIds] = useState<string[]>([]);
  const [priceSource, setPriceSource] = useState('PRICING_SERVICE');
  const [priceAmount, setPriceAmount] = useState('');
  const [priceCurrency, setPriceCurrency] = useState('USD');
  const [stockSource, setStockSource] = useState('KEEP_UNSET');
  const [idempotencyKey, setIdempotencyKey] = useState('');
  const [note, setNote] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  const loadJobDetail = useCallback(async (jobId: string) => {
    const [job, targetList, stepList] = await Promise.all([
      fetchJob(jobId),
      fetchTargets(jobId),
      fetchSteps(jobId),
    ]);
    setJobs((prev) => prev.map((item) => (item.jobId === jobId ? job : item)));
    setTargets(targetList);
    setSteps(stepList);
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, optionList, sourceResult, jobList] = await Promise.all([
        fetchCapabilities(),
        fetchTargetOptions(),
        fetchContentSources(),
        fetchJobs(),
      ]);
      setCaps(capsResult);
      setOptions(optionList);
      setSources(sourceResult);
      setJobs(jobList);
      const nextActive = activeJobId ?? (jobList.length > 0 ? jobList[0]!.jobId : null);
      setActiveJobId(nextActive);
      if (nextActive) await loadJobDetail(nextActive);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [activeJobId, loadJobDetail]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 幂等键默认给一个值：同一个键重复提交只会产生一条作业（运营可以改成自己的键）
  useEffect(() => {
    if (!idempotencyKey) {
      setIdempotencyKey(`ocp-${Date.now().toString(36)}`);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const run = useCallback(async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    setFormError(null);
    try {
      await work();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败，请重试');
    } finally {
      setBusy(false);
    }
  }, []);

  const selectedOptions = useMemo(
    () => options.filter((option) => selectedKeys.includes(targetOptionKey(option))),
    [options, selectedKeys],
  );

  const targetInputs = useMemo<ListingPublishTargetInput[]>(
    () =>
      selectedOptions.map((option) => ({
        shopId: option.shopId,
        marketplaceId: option.marketplaceId,
        locale: locales[targetOptionKey(option)] ?? option.defaultLocale,
        categoryPath: '',
      })),
    [selectedOptions, locales],
  );

  const candidates = useMemo(
    () =>
      contentSourceCandidates(sources ?? { editorSubmissions: [], templates: [] }, contentSource),
    [sources, contentSource],
  );

  const activeJob = useMemo(
    () => jobs.find((job) => job.jobId === activeJobId) ?? null,
    [jobs, activeJobId],
  );

  function toggleTarget(option: ListingPublishTargetOption) {
    const key = targetOptionKey(option);
    setSelectedKeys((prev) =>
      prev.includes(key) ? prev.filter((item) => item !== key) : [...prev, key],
    );
  }

  function toggleContentSource(id: string) {
    setContentSourceIds((prev) =>
      prev.includes(id) ? prev.filter((item) => item !== id) : [...prev, id],
    );
  }

  /** 按已选目标自动匹配内容来源（编辑器草稿按 店铺+站点+语言 对上；模板按平台对上）。 */
  function autoMatchSources() {
    const picked = new Set(contentSourceIds);
    if (contentSource === 'EDITOR_SUBMISSION') {
      for (const target of targetInputs) {
        const hit = (sources?.editorSubmissions ?? []).find(
          (item) =>
            item.shopId === target.shopId &&
            item.marketplaceId === target.marketplaceId &&
            item.locale === target.locale,
        );
        if (hit) picked.add(hit.submissionId);
      }
    } else {
      for (const target of targetInputs) {
        const hit = (sources?.templates ?? []).find(
          (item) => item.platformId === options.find((o) => o.shopId === target.shopId)?.platformId,
        );
        if (hit) picked.add(hit.templateId);
      }
    }
    setContentSourceIds([...picked]);
  }

  async function submit(andDispatch: boolean) {
    const problem = validateCreateInput({
      sourceSkuId,
      targets: targetInputs,
      contentSourceIds,
      priceSource,
      priceAmount,
      priceCurrency,
      idempotencyKey,
    });
    if (problem) {
      setFormError(problem);
      return;
    }
    const body = buildCreateRequest({
      sourceSkuId: sourceSkuId.trim(),
      targets: targetInputs,
      contentSource,
      contentSourceIds,
      priceSource,
      priceAmount,
      priceCurrency,
      stockSource,
      idempotencyKey: idempotencyKey.trim(),
      note: note.trim(),
    });
    const job = await createJob(body);
    setJobs((prev) => [job, ...prev.filter((item) => item.jobId !== job.jobId)]);
    setActiveJobId(job.jobId);
    if (andDispatch) {
      const result = await dispatchJob(job.jobId);
      setDispatchNote(dispatchSummary(result));
      await loadJobDetail(job.jobId);
      const refreshed = await fetchJob(job.jobId);
      setJobs((prev) => prev.map((item) => (item.jobId === job.jobId ? refreshed : item)));
    } else {
      await loadJobDetail(job.jobId);
    }
  }

  if (loading) {
    return (
      <div className="text-muted-foreground flex items-center justify-center gap-2 py-16">
        <Loader2 className="size-4 animate-spin" />
        <span className="text-label-13">正在加载一键刊登…</span>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <SectionCard title="一键刊登">
        <EmptyState
          icon={<TriangleAlert className="text-destructive size-8" />}
          title="一键刊登加载失败"
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
        title="一键刊登"
        description="单个 SKU 一次性发布到多个目标店铺/站点：自动套模板、填充内容、建立映射、上传媒体、设置价格库存、提交平台审核。跨平台发布不是一个事务 —— 某个平台失败不影响其他平台，各自独立重试。"
      />

      {error && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-start gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p className="text-label-13">{error}</p>
        </div>
      )}

      {caps && unavailableDimensions(caps).length > 0 && (
        <div className="border-warning/40 bg-warning/5 rounded-lg border px-4 py-3">
          <p className="text-warning text-label-13 mb-1 font-medium">部分能力未接入</p>
          <ul className="text-muted-foreground text-label-12 space-y-1">
            {unavailableDimensions(caps).map((dimension) => (
              <li key={dimension.dimension}>
                <span className="text-foreground">{dimension.description}</span>
                ：归 {dimension.ownerModule} —— {dimension.impact}
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* ---- 🔴 建作业 ---- */}
      <SectionCard
        title="一键刊登"
        description={caps?.snapshotNote}
        extra={
          <Button variant="ghost" size="sm" onClick={() => void refresh()} disabled={busy}>
            <RefreshCw className="mr-2 size-4" />
            刷新
          </Button>
        }
      >
        <div className="space-y-5">
          {/* 源 SKU */}
          <div className="space-y-2">
            <label className="text-label-13 text-foreground block" htmlFor="ocp-sku">
              源 SKU
            </label>
            <Input
              id="ocp-sku"
              placeholder="03 商品的 SKU id（UUID）"
              value={sourceSkuId}
              onChange={(e) => setSourceSkuId(e.target.value)}
            />
            <p className="text-copy-12 text-muted-foreground">
              一个 SKU 一次发到多个目标店铺/站点；每个目标建一个发布任务。
            </p>
          </div>

          {/* 目标店铺 / 站点多选 */}
          <div className="space-y-2">
            <p className="text-label-13 text-foreground">
              目标店铺与站点
              {caps ? `（一次最多 ${caps.maxTargets} 个）` : ''}
            </p>
            {options.length === 0 ? (
              <EmptyState
                icon={<TriangleAlert className="text-warning size-8" />}
                title="没有可选的目标店铺"
                description="这里列的是**已启用站点**的店铺（真实数据）。先到店铺设置里启用站点，再回来发布。"
              />
            ) : (
              <div className="grid gap-2 sm:grid-cols-2">
                {options.map((option) => {
                  const key = targetOptionKey(option);
                  const checked = selectedKeys.includes(key);
                  return (
                    <label
                      key={key}
                      className="border-border text-label-13 flex items-center gap-2 rounded-md border px-3 py-2"
                    >
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={() => toggleTarget(option)}
                      />
                      <span className="text-foreground">{targetOptionLabel(option)}</span>
                    </label>
                  );
                })}
              </div>
            )}
            {selectedOptions.length > 0 && (
              <div className="space-y-2">
                <p className="text-copy-12 text-muted-foreground">
                  每个目标按「平台 + 站点 + 语言」分别保存内容，语言要跟内容来源对得上。
                </p>
                {selectedOptions.map((option) => {
                  const key = targetOptionKey(option);
                  return (
                    <div key={key} className="flex items-center gap-3">
                      <span className="text-copy-12 text-foreground min-w-64">
                        {targetOptionLabel(option)}
                      </span>
                      <Input
                        className="w-40"
                        placeholder="语言"
                        value={locales[key] ?? option.defaultLocale}
                        onChange={(e) => setLocales({ ...locales, [key]: e.target.value })}
                      />
                    </div>
                  );
                })}
              </div>
            )}
          </div>

          {/* 内容来源 */}
          <div className="space-y-2">
            <p className="text-label-13 text-foreground">内容来源</p>
            <div className="flex flex-wrap gap-4">
              <label className="text-label-13 flex items-center gap-2">
                <input
                  type="radio"
                  checked={contentSource === 'EDITOR_SUBMISSION'}
                  onChange={() => {
                    setContentSource('EDITOR_SUBMISSION');
                    setContentSourceIds([]);
                  }}
                />
                编辑器草稿（4.2.1.1 的入队记录）
              </label>
              <label className="text-label-13 flex items-center gap-2">
                <input
                  type="radio"
                  checked={contentSource === 'TEMPLATE'}
                  onChange={() => {
                    setContentSource('TEMPLATE');
                    setContentSourceIds([]);
                  }}
                />
                刊登模板套用（4.2.2.1）
              </label>
            </div>
            <div className="flex items-center gap-2">
              <Button variant="secondary" size="sm" onClick={autoMatchSources} disabled={busy}>
                按已选目标自动匹配
              </Button>
              <span className="text-copy-12 text-muted-foreground">
                已选 {contentSourceIds.length} 个来源
              </span>
            </div>
            {candidates.submissions.length === 0 && candidates.templates.length === 0 ? (
              <EmptyState
                icon={<TriangleAlert className="text-warning size-8" />}
                title="没有可选的内容来源"
                description="编辑器草稿要先在刊登编辑器里提交审核，刊登模板要先在模板页启用 —— 这里列的是真实候选，不是写死的清单。"
              />
            ) : (
              <div className="space-y-2">
                {candidates.submissions.map((item) => (
                  <label key={item.submissionId} className="text-label-13 flex items-center gap-2">
                    <input
                      type="checkbox"
                      checked={contentSourceIds.includes(item.submissionId)}
                      onChange={() => toggleContentSource(item.submissionId)}
                    />
                    <span className="text-foreground">{item.title || '(无标题)'}</span>
                    <span className="text-muted-foreground">
                      {item.platformCode} / {item.shopCode} / {item.marketplaceCode} / {item.locale}
                    </span>
                    <span className="text-muted-foreground">（{item.imageCount} 张图）</span>
                  </label>
                ))}
                {candidates.templates.map((item) => (
                  <label key={item.templateId} className="text-label-13 flex items-center gap-2">
                    <input
                      type="checkbox"
                      checked={contentSourceIds.includes(item.templateId)}
                      onChange={() => toggleContentSource(item.templateId)}
                    />
                    <span className="text-foreground">{item.name}</span>
                    <span className="text-muted-foreground">
                      {item.platformCode} / {item.scopeTarget} / {item.fieldCount} 条规则
                    </span>
                  </label>
                ))}
              </div>
            )}
          </div>

          {/* 价格与库存来源策略 */}
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-2">
              <p className="text-label-13 text-foreground">价格来源策略</p>
              <label className="text-label-13 flex items-center gap-2">
                <input
                  type="radio"
                  checked={priceSource === 'PRICING_SERVICE'}
                  onChange={() => setPriceSource('PRICING_SERVICE')}
                />
                05 定价取价（5.1.1.1）
              </label>
              <label className="text-label-13 flex items-center gap-2">
                <input
                  type="radio"
                  checked={priceSource === 'MANUAL'}
                  onChange={() => setPriceSource('MANUAL')}
                />
                运营手填
              </label>
              {priceSource === 'PRICING_SERVICE' ? (
                <p className="text-warning text-copy-12">
                  取价来源 05 定价（5.1.1.1）尚未交付：本次发布**不带平台售价**，
                  界面不会编一个价，发布后由 05 定价给出再补。
                </p>
              ) : (
                <div className="flex gap-2">
                  <Input
                    placeholder="金额"
                    value={priceAmount}
                    onChange={(e) => setPriceAmount(e.target.value)}
                  />
                  <Input
                    className="w-24"
                    placeholder="币种"
                    value={priceCurrency}
                    onChange={(e) => setPriceCurrency(e.target.value)}
                  />
                </div>
              )}
            </div>
            <div className="space-y-2">
              <p className="text-label-13 text-foreground">库存来源策略</p>
              <label className="text-label-13 flex items-center gap-2">
                <input
                  type="radio"
                  checked={stockSource === 'WAREHOUSE_AVAILABLE'}
                  onChange={() => setStockSource('WAREHOUSE_AVAILABLE')}
                />
                07 库存可售量
              </label>
              <label className="text-label-13 flex items-center gap-2">
                <input
                  type="radio"
                  checked={stockSource === 'KEEP_UNSET'}
                  onChange={() => setStockSource('KEEP_UNSET')}
                />
                本次不设置库存
              </label>
              <p className="text-copy-12 text-muted-foreground">
                可售量从 warehouse 读（跨服务只读）。**读不到就是读不到**，不会折成 0。
              </p>
            </div>
          </div>

          {/* 幂等键 */}
          <div className="space-y-2">
            <label className="text-label-13 text-foreground block" htmlFor="ocp-idem">
              幂等键
            </label>
            <Input
              id="ocp-idem"
              value={idempotencyKey}
              onChange={(e) => setIdempotencyKey(e.target.value)}
            />
            <p className="text-copy-12 text-muted-foreground">{caps?.idempotencyNote}</p>
          </div>

          {formError && (
            <div className="border-destructive/40 bg-destructive/5 text-destructive rounded-lg border px-4 py-3">
              <p className="text-label-13">{formError}</p>
            </div>
          )}

          <div className="flex flex-wrap gap-2">
            <Button
              onClick={() =>
                void run(async () => {
                  await submit(true);
                })
              }
              disabled={busy}
            >
              <Send className="mr-2 size-4" />
              一键刊登（创建并派发）
            </Button>
            <Button
              variant="secondary"
              onClick={() =>
                void run(async () => {
                  await submit(false);
                })
              }
              disabled={busy}
            >
              只创建作业
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* ---- 🔴 作业与发布任务 ---- */}
      <SectionCard
        title="发布任务"
        description="每个目标一个任务；跨平台发布不是一个事务，某个平台失败不影响其它平台。"
        extra={
          <Button variant="ghost" size="sm" onClick={() => void refresh()} disabled={busy}>
            <RefreshCw className="mr-2 size-4" />
            刷新
          </Button>
        }
      >
        {jobs.length === 0 ? (
          <EmptyState
            icon={<Send className="text-muted-foreground size-8" />}
            title="还没有一键刊登作业"
            description="填好源 SKU 与目标店铺，点「一键刊登（创建并派发）」开始。"
          />
        ) : (
          <div className="space-y-4">
            <div className="overflow-x-auto">
              <table className="text-label-13 w-full text-left">
                <thead className="text-muted-foreground border-border border-b">
                  <tr>
                    <th className="px-4 py-2 font-medium">创建时刻</th>
                    <th className="px-4 py-2 font-medium">源 SKU</th>
                    <th className="px-4 py-2 font-medium">内容来源</th>
                    <th className="px-4 py-2 font-medium">进度</th>
                    <th className="px-4 py-2 font-medium">总体结论</th>
                    <th className="px-4 py-2 font-medium">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {jobs.map((job) => (
                    <tr
                      key={job.jobId}
                      className={
                        job.jobId === activeJobId
                          ? 'border-border bg-muted/40 border-b last:border-0'
                          : 'border-border border-b last:border-0'
                      }
                    >
                      <td className="px-4 py-2">
                        <button
                          type="button"
                          className="text-foreground underline-offset-2 hover:underline"
                          onClick={() => {
                            setActiveJobId(job.jobId);
                            void run(async () => {
                              await loadJobDetail(job.jobId);
                            });
                          }}
                        >
                          {new Date(job.createdAt).toLocaleString('zh-CN')}
                        </button>
                      </td>
                      <td className="text-copy-12 px-4 py-2 font-mono">{job.sourceSkuId}</td>
                      <td className="px-4 py-2">
                        {job.contentSource === 'TEMPLATE' ? '刊登模板' : '编辑器草稿'}
                      </td>
                      <td className="px-4 py-2">{progressSummary(job.progress)}</td>
                      <td className="px-4 py-2">
                        <Badge
                          tone={
                            job.progress.outcome === 'COMPLETED'
                              ? 'success'
                              : job.progress.outcome === 'FAILED'
                                ? 'danger'
                                : 'info'
                          }
                        >
                          {outcomeLabel(job.progress.outcome)}
                        </Badge>
                      </td>
                      <td className="px-4 py-2">
                        {job.state === 'DRAFT' && (
                          <Button
                            variant="secondary"
                            size="sm"
                            disabled={busy}
                            onClick={() =>
                              void run(async () => {
                                const result = await dispatchJob(job.jobId);
                                setDispatchNote(dispatchSummary(result));
                                await loadJobDetail(job.jobId);
                              })
                            }
                          >
                            派发
                          </Button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {activeJob && (
              <div className="space-y-3">
                <div className="border-border bg-muted/30 text-copy-13 rounded-md border px-4 py-3">
                  <p className="text-foreground">价格：{priceStateNotice(activeJob)}</p>
                  <p className="text-foreground">库存：{stockStateNotice(activeJob)}</p>
                  <p className="text-muted-foreground">{caps?.nonTransactionalNote}</p>
                </div>

                {dispatchNote && (
                  <div className="border-border rounded-md border px-4 py-3">
                    <p className="text-foreground text-label-13 mb-1">本轮派发（逐目标）</p>
                    <ul className="text-muted-foreground text-label-12 space-y-1">
                      {dispatchNote.map((line) => (
                        <li key={line}>{line}</li>
                      ))}
                    </ul>
                  </div>
                )}

                {targets.length === 0 ? (
                  <EmptyState
                    icon={<Send className="text-muted-foreground size-8" />}
                    title="还没有发布任务"
                    description="先创建一键刊登作业，每个目标会在这里列出。"
                  />
                ) : (
                  <div className="overflow-x-auto">
                    <table className="text-label-13 w-full text-left">
                      <thead className="text-muted-foreground border-border border-b">
                        <tr>
                          <th className="px-4 py-2 font-medium">平台 / 店铺 / 站点</th>
                          <th className="px-4 py-2 font-medium">语言</th>
                          <th className="px-4 py-2 font-medium">状态</th>
                          <th className="px-4 py-2 font-medium">尝试</th>
                          <th className="px-4 py-2 font-medium">冻结的内容</th>
                          <th className="px-4 py-2 font-medium">原因 / 平台商品</th>
                          <th className="px-4 py-2 font-medium">操作</th>
                        </tr>
                      </thead>
                      <tbody>
                        {targets.map((target) => (
                          <tr
                            key={target.targetId}
                            className="border-border border-b last:border-0"
                          >
                            <td className="px-4 py-2">
                              {target.platformCode} / {target.shopCode} / {target.marketplaceCode}
                            </td>
                            <td className="px-4 py-2">{target.locale}</td>
                            <td className="px-4 py-2">
                              <Badge tone={targetStateTone(target.state)}>
                                {targetStateLabel(target.state)}
                              </Badge>
                            </td>
                            <td className="px-4 py-2">{target.attempts}</td>
                            <td className="px-4 py-2">
                              {target.contentSnapshot?.title ? (
                                <span className="text-foreground">
                                  {target.contentSnapshot.title}
                                  {target.contentSnapshot.images
                                    ? ` · ${target.contentSnapshot.images.length} 图`
                                    : ''}
                                </span>
                              ) : (
                                <span className="text-muted-foreground">（无标题）</span>
                              )}
                            </td>
                            <td className="px-4 py-2">
                              {target.failureReason ? (
                                <span className="text-destructive">{target.failureReason}</span>
                              ) : target.externalItemId ? (
                                <span className="text-foreground">{target.externalItemId}</span>
                              ) : (
                                <span className="text-muted-foreground">—</span>
                              )}
                            </td>
                            <td className="px-4 py-2">
                              {canRetry(target) ? (
                                <Button
                                  variant="secondary"
                                  size="sm"
                                  disabled={busy}
                                  title={retryHint(target)}
                                  onClick={() =>
                                    void run(async () => {
                                      await retryTarget(target.targetId);
                                      await loadJobDetail(target.jobId);
                                    })
                                  }
                                >
                                  <RotateCcw className="mr-2 size-4" />
                                  重试
                                </Button>
                              ) : (
                                <span className="text-muted-foreground text-copy-12">
                                  {retryHint(target)}
                                </span>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}

                {steps.length > 0 && (
                  <details className="border-border rounded-md border px-4 py-3">
                    <summary className="text-label-13 text-foreground cursor-pointer">
                      步骤时间线（{steps.length} 步）
                    </summary>
                    <ul className="text-muted-foreground text-label-12 mt-2 space-y-1">
                      {steps.map((step) => (
                        <li key={step.stepId}>
                          <span className="text-foreground">{step.stepKey}</span> · {step.state}
                          {step.detail ? ` —— ${step.detail}` : ''}
                        </li>
                      ))}
                    </ul>
                  </details>
                )}
              </div>
            )}
          </div>
        )}
      </SectionCard>
    </div>
  );
}
