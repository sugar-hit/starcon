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
  History,
  Loader2,
  RefreshCw,
  RotateCcw,
  Send,
  Square,
  TriangleAlert,
} from 'lucide-react';
import {
  afterSourceLabel,
  actionSummary,
  beforeDisplay,
  buildCreateRequest,
  cancelWriteback,
  CHANGE_TYPE_OPTIONS,
  createWriteback,
  dispatchSummary,
  dispatchWriteback,
  emptyWritebacksMessage,
  fetchCapabilities,
  fetchChanges,
  fetchTasks,
  fetchWritebacks,
  fieldLabel,
  foreignChannels,
  outcomeLabel,
  progressSummary,
  retryFailed,
  taskStateLabel,
  taskStateTone,
  triggerStateLabel,
  unavailableDimensions,
  type ContentWritebackChangeRow,
  type ContentWritebackCapabilities,
  type ContentWritebackRow,
  type ContentWritebackTaskRow,
} from './content-writeback-model';

/**
 * 4.3.2.1 内容回写 工作区。
 *
 * 蓝图：「把内部已发布的商品内容变更推送到平台（改标题、改图、改描述、改 A+），支持按变更类型
 * 触发或批量触发，并记录回写前后快照。输出：回写任务（经 02 队列）+ 变更历史。
 * **内容回写不触碰价格与库存**（那是 05/07 的事），三条回写通道独立失败独立重试。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **页面上没有改价 / 改库存入口**。可选项只有五个内容字段；价格与库存只在「通道归属」里被
 *    点名（改价归 05 定价 / 改库存归 07 库存）。放一个必然被 422 的按钮比不放更糟。
 * 2. **「跳过」有自己的徽章与计数**。改后与线上逐字相同的那条标成「已跳过（无变更）」，
 *    既不算成功（谎报已推送），也不算失败（会让运营以为出了问题）。
 * 3. **改前值缺失就说缺失**。点开某条任务时，`beforeSource='MISSING'` 的字段显示
 *    「平台侧无基线（改前值不可知）」，**不显示**改后值顶替。
 * 4. **在途 ≠ 已回写**。`DISPATCHED` 的徽章是「在途（等平台回报）」，与「已回写」分开。
 * 5. **重试只重试失败项**。按钮只对 FAILED 生效，没有「全部重试」入口。
 */
export function ContentWritebackWorkspace() {
  const [caps, setCaps] = useState<ContentWritebackCapabilities | null>(null);
  const [writebacks, setWritebacks] = useState<ContentWritebackRow[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [tasks, setTasks] = useState<ContentWritebackTaskRow[]>([]);
  const [changes, setChanges] = useState<ContentWritebackChangeRow[] | null>(null);
  const [dispatchLines, setDispatchLines] = useState<string[] | null>(null);
  const [lastAction, setLastAction] = useState<string | null>(null);

  // ---- 建回写的表单
  const [changeTypes, setChangeTypes] = useState<string[]>(['TITLE']);
  const [listingIdsText, setListingIdsText] = useState('');
  const [shopCode, setShopCode] = useState('');
  const [status, setStatus] = useState('ON_SALE');
  const [description, setDescription] = useState('');
  const [aPlus, setAPlus] = useState('');
  const [limit, setLimit] = useState(50);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadTasks = useCallback(async (writebackId: string) => {
    setTasks(await fetchTasks(writebackId));
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, list] = await Promise.all([fetchCapabilities(), fetchWritebacks()]);
      setCaps(capsResult);
      setWritebacks(list);
      const next = activeId ?? (list.length > 0 ? list[0]!.writebackId : null);
      setActiveId(next);
      setChanges(null);
      setDispatchLines(null);
      if (next) await loadTasks(next);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [activeId, loadTasks]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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

  const active = useMemo(
    () => writebacks.find((w) => w.writebackId === activeId) ?? null,
    [writebacks, activeId],
  );

  function toggleType(value: string) {
    setChangeTypes((prev) =>
      prev.includes(value) ? prev.filter((t) => t !== value) : [...prev, value],
    );
  }

  const listingIds = useMemo(
    () =>
      listingIdsText
        .split(/[\s,，]+/)
        .map((s) => s.trim())
        .filter(Boolean),
    [listingIdsText],
  );

  // 🔴 描述 / A+ 需要显式值（内部草稿没有这两列，4.2.1.1 未交付）——
  //    选了但没给值时，提交按钮禁用并说明原因，而不是发一个注定"无来源"的任务。
  const needsOverride = useMemo(
    () => changeTypes.some((t) => t === 'DESCRIPTION' || t === 'A_PLUS'),
    [changeTypes],
  );
  const missingOverride = useMemo(() => {
    const missing: string[] = [];
    if (changeTypes.includes('DESCRIPTION') && description.trim() === '') missing.push('描述');
    if (changeTypes.includes('A_PLUS') && aPlus.trim() === '') missing.push('A+');
    return missing;
  }, [changeTypes, description, aPlus]);

  const payload = useMemo(
    () =>
      buildCreateRequest({
        changeTypes,
        listingIds,
        shopCode,
        status,
        overrides: { DESCRIPTION: description, A_PLUS: aPlus },
        limit,
        note: '',
      }),
    [changeTypes, listingIds, shopCode, status, description, aPlus, limit],
  );

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-2 py-16 text-muted-foreground">
        <Loader2 className="size-4 animate-spin" />
        <span className="text-label-13">正在加载内容回写…</span>
      </div>
    );
  }

  if (error && writebacks.length === 0 && !caps) {
    return (
      <SectionCard title="内容回写">
        <EmptyState
          icon={<TriangleAlert className="size-8 text-destructive" />}
          title="内容回写加载失败"
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
        title="内容回写"
        description="把内部已发布的商品内容变更推送到平台（改标题、改图、改描述、改 A+），支持按变更类型触发或批量触发，并记录回写前后快照。"
      />

      {error && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-start gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p className="text-label-13">{error}</p>
        </div>
      )}

      {/* ---- 🔴 通道归属：内容 / 价格 / 库存是三条独立通道 ---- */}
      {caps && foreignChannels(caps).length > 0 && (
        <div className="border-warning/40 bg-warning/5 rounded-lg border px-4 py-3">
          <p className="text-warning mb-1 text-label-13 font-medium">
            内容回写不触碰价格与库存
          </p>
          <ul className="text-muted-foreground space-y-1 text-label-12">
            {foreignChannels(caps).map((c) => (
              <li key={c.channel}>
                <span className="text-foreground">{c.label}</span>：归 {c.ownerModule} —— {c.note}
              </li>
            ))}
            <li>三条通道独立失败独立重试 —— 本页的「重试失败项」只动内容通道自己的任务。</li>
          </ul>
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

      {/* ---- 建一次回写（按变更类型 / 批量触发）---- */}
      <SectionCard
        title="创建内容回写"
        description={caps?.snapshotNote}
        extra={
          <Button variant="ghost" size="sm" onClick={() => void refresh()} disabled={busy}>
            <RefreshCw className="mr-2 size-4" />
            刷新
          </Button>
        }
      >
        <div className="space-y-4 px-4 py-4">
          <div>
            <p className="text-foreground mb-2 text-label-13 font-medium">
              变更类型
              <span className="text-muted-foreground font-normal">
                （只有内容通道的字段；价格与库存不在此列）
              </span>
            </p>
            <div className="flex flex-wrap gap-2">
              {CHANGE_TYPE_OPTIONS.map((opt) => (
                <button
                  key={opt.value}
                  type="button"
                  title={opt.hint}
                  onClick={() => toggleType(opt.value)}
                  disabled={busy}
                  className={
                    changeTypes.includes(opt.value)
                      ? 'border-brand bg-brand-soft text-brand rounded-md border px-3 py-1.5 text-label-13'
                      : 'border-border text-muted-foreground hover:border-foreground/40 hover:text-foreground rounded-md border px-3 py-1.5 text-label-13'
                  }
                >
                  {opt.label}
                </button>
              ))}
            </div>
          </div>

          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Input
              placeholder="目标 listing ID（逗号或空格分隔；留空按筛选挑）"
              value={listingIdsText}
              onChange={(e) => setListingIdsText(e.target.value)}
            />
            <Input
              placeholder="店铺编码筛选"
              value={shopCode}
              onChange={(e) => setShopCode(e.target.value)}
            />
            <Input
              placeholder="状态筛选（如 ON_SALE）"
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            />
            <Input
              type="number"
              placeholder="数量上限（1~500）"
              value={limit}
              onChange={(e) => setLimit(Number(e.target.value))}
            />
          </div>

          {needsOverride && (
            <div className="border-border bg-muted/30 rounded-lg border px-3 py-3">
              <p className="text-foreground mb-2 text-label-13 font-medium">
                描述 / A+ 的显式值
              </p>
              <p className="text-muted-foreground mb-2 text-label-12">
                内部内容草稿（4.2.1.2）只有标题 / 卖点 / 图片，<strong>没有描述与 A+</strong>
                （属 4.2.1.1 刊登编辑器，尚未交付）。所以这两段的改后值只能在这里给；
                不给值的那条会判「内部无可推送内容」并**不编一个假值**。
              </p>
              <div className="grid gap-3 sm:grid-cols-2">
                {changeTypes.includes('DESCRIPTION') && (
                  <Input
                    placeholder="新的详情描述"
                    value={description}
                    onChange={(e) => setDescription(e.target.value)}
                  />
                )}
                {changeTypes.includes('A_PLUS') && (
                  <Input
                    placeholder='新的 A+ 模块（JSON 数组文本）'
                    value={aPlus}
                    onChange={(e) => setAPlus(e.target.value)}
                  />
                )}
              </div>
              {missingOverride.length > 0 && (
                <p className="text-warning mt-2 text-label-12">
                  还差显式值：{missingOverride.join('、')}。不填也能提交 —— 那些字段会判「内部无可推送内容」。
                </p>
              )}
            </div>
          )}

          <div className="flex flex-wrap items-center gap-3">
            <Button
              onClick={() =>
                void run(async () => {
                  if (!payload) throw new Error('请至少选择一个变更类型');
                  const created = await createWriteback(payload);
                  setWritebacks((prev) => [created, ...prev]);
                  setActiveId(created.writebackId);
                  await loadTasks(created.writebackId);
                  setChanges(null);
                  setDispatchLines(null);
                  setLastAction(
                    `已创建回写 ${created.totalCount} 条（跳过 ${created.skippedCount} 条：与线上逐字相同或内部无可推送内容）`,
                  );
                })
              }
              disabled={busy || payload === null}
            >
              <Send className="mr-2 size-4" />
              创建内容回写
            </Button>
            {payload === null && (
              <span className="text-muted-foreground text-label-12">
                至少选一个变更类型才能提交
              </span>
            )}
          </div>
        </div>
      </SectionCard>

      {/* ---- 一次触发列表 ---- */}
      <SectionCard
        title="回写记录"
        description="每一次触发的总体进度；跳过与成功分开计数。"
      >
        {writebacks.length === 0 ? (
          <EmptyState
            icon={<TriangleAlert className="size-8 text-muted-foreground" />}
            title="还没有内容回写记录"
            description={emptyWritebacksMessage()}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-label-13">
              <thead className="text-muted-foreground border-border border-b">
                <tr>
                  <th className="px-4 py-2 font-medium">字段</th>
                  <th className="px-4 py-2 font-medium">进度</th>
                  <th className="px-4 py-2 font-medium">状态</th>
                  <th className="px-4 py-2 font-medium">触发时刻</th>
                </tr>
              </thead>
              <tbody>
                {writebacks.map((w) => (
                  <tr
                    key={w.writebackId}
                    onClick={() => {
                      setActiveId(w.writebackId);
                      setChanges(null);
                      setDispatchLines(null);
                      void run(async () => {
                        await loadTasks(w.writebackId);
                      });
                    }}
                    className={
                      w.writebackId === activeId
                        ? 'bg-brand-soft/40 cursor-pointer border-border border-b'
                        : 'hover:bg-muted/40 cursor-pointer border-border border-b last:border-0'
                    }
                  >
                    <td className="text-foreground px-4 py-2">{w.changeTypes.join(' / ')}</td>
                    <td className="text-muted-foreground px-4 py-2 text-label-12">
                      {progressSummary(w)}
                    </td>
                    <td className="px-4 py-2">
                      <Badge
                        tone={
                          w.state === 'COMPLETED'
                            ? 'success'
                            : w.state === 'CANCELLED'
                              ? 'danger'
                              : 'info'
                        }
                      >
                        {triggerStateLabel(w.state)}
                      </Badge>
                    </td>
                    <td className="text-muted-foreground px-4 py-2 text-label-12">
                      {new Date(w.createdAt).toLocaleString('zh-CN')}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* ---- 选中的一次触发：任务列表 + 动作 ---- */}
      {active && (
        <SectionCard
          title={`任务列表 · ${active.changeTypes.join(' / ')}`}
          description={progressSummary(active)}
          extra={
            <div className="flex flex-wrap gap-2">
              <Button
                size="sm"
                onClick={() =>
                  void run(async () => {
                    const d = await dispatchWriteback(active.writebackId);
                    setDispatchLines(dispatchSummary(d));
                    setWritebacks((prev) =>
                      prev.map((w) => (w.writebackId === active.writebackId ? d.writeback : w)),
                    );
                    await loadTasks(active.writebackId);
                  })
                }
                disabled={busy || active.state !== 'RUNNING'}
              >
                <Send className="mr-2 size-4" />
                派发一轮（按各平台配额）
              </Button>
              <Button
                size="sm"
                variant="secondary"
                title="只重试失败项；已成功的不会被重跑"
                onClick={() =>
                  void run(async () => {
                    const failed = tasks.filter((t) => t.state === 'FAILED').map((t) => t.taskId);
                    const result = await retryFailed(active.writebackId, failed, '批量重试失败项');
                    setLastAction(actionSummary(result, '重试'));
                    setWritebacks((prev) =>
                      prev.map((w) =>
                        w.writebackId === active.writebackId ? result.writeback : w,
                      ),
                    );
                    await loadTasks(active.writebackId);
                  })
                }
                disabled={busy || !tasks.some((t) => t.state === 'FAILED')}
              >
                <RotateCcw className="mr-2 size-4" />
                重试失败项
              </Button>
              <Button
                size="sm"
                variant="ghost"
                onClick={() =>
                  void run(async () => {
                    const after = await cancelWriteback(active.writebackId, '人工中止');
                    setWritebacks((prev) =>
                      prev.map((w) => (w.writebackId === active.writebackId ? after : w)),
                    );
                    await loadTasks(active.writebackId);
                  })
                }
                disabled={busy || active.state !== 'RUNNING'}
              >
                <Square className="mr-2 size-4" />
                中止
              </Button>
            </div>
          }
        >
          <div className="space-y-3 px-4 py-4">
            {(dispatchLines ?? []).length > 0 && (
              <ul className="text-muted-foreground space-y-1 text-label-12">
                {dispatchLines!.map((line, i) => (
                  <li key={i} className="text-foreground">
                    {line}
                  </li>
                ))}
              </ul>
            )}
            {lastAction && (
              <p className="text-muted-foreground text-label-12">{lastAction}</p>
            )}

            {tasks.length === 0 ? (
              <EmptyState
                icon={<TriangleAlert className="size-8 text-muted-foreground" />}
                title="这次回写还没有任务"
                description="任务在创建回写时按目标 listing 逐条生成；没有任何目标时服务端会直接拒绝，不会建一条空回写。"
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-label-13">
                  <thead className="text-muted-foreground border-border border-b">
                    <tr>
                      <th className="px-4 py-2 font-medium">商品</th>
                      <th className="px-4 py-2 font-medium">平台 / 店铺</th>
                      <th className="px-4 py-2 font-medium">状态</th>
                      <th className="px-4 py-2 font-medium">02 队列</th>
                      <th className="px-4 py-2 font-medium">说明</th>
                      <th className="px-4 py-2 font-medium">快照</th>
                    </tr>
                  </thead>
                  <tbody>
                    {tasks.map((t) => (
                      <tr key={t.taskId} className="border-border border-b last:border-0">
                        <td className="text-foreground px-4 py-2">
                          {t.title}
                          <span className="text-muted-foreground block text-label-12">
                            {t.externalItemId}
                          </span>
                        </td>
                        <td className="text-muted-foreground px-4 py-2">
                          {t.platformCode} / {t.shopCode}
                        </td>
                        <td className="px-4 py-2">
                          <Badge tone={taskStateTone(t.state)}>{taskStateLabel(t.state)}</Badge>
                          {t.attempts > 0 && (
                            <span className="text-muted-foreground ml-2 text-label-12">
                              第 {t.attempts} 次
                            </span>
                          )}
                        </td>
                        <td className="text-muted-foreground px-4 py-2 text-label-12">
                          {t.operationId ? (
                            <span title={t.operationId}>已入队列</span>
                          ) : (
                            '—'
                          )}
                        </td>
                        <td className="text-muted-foreground px-4 py-2 text-label-12">
                          {t.failureReason || '—'}
                        </td>
                        <td className="px-4 py-2">
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() =>
                              void run(async () => {
                                setChanges(await fetchChanges(t.taskId));
                              })
                            }
                          >
                            <History className="mr-2 size-4" />
                            改前 / 改后
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </SectionCard>
      )}

      {/* ---- 🔴 改前 / 改后快照 ---- */}
      {changes && (
        <SectionCard
          title="回写前后快照"
          description="改前来自平台侧当前内容快照，改后来自内部草稿或本次显式给值；两侧来源逐行记下。"
          extra={
            <Button size="sm" variant="ghost" onClick={() => setChanges(null)}>
              收起
            </Button>
          }
        >
          {changes.length === 0 ? (
            <EmptyState
              icon={<TriangleAlert className="size-8 text-muted-foreground" />}
              title="这条任务没有字段变更记录"
              description="这不应该发生：任务与它的字段变更是同一次写入落库的。若确实为空，说明数据被绕过了服务层写入。"
            />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-label-13">
                <thead className="text-muted-foreground border-border border-b">
                  <tr>
                    <th className="px-4 py-2 font-medium">字段</th>
                    <th className="px-4 py-2 font-medium">改前（平台侧）</th>
                    <th className="px-4 py-2 font-medium">改后（内部 / 覆盖值）</th>
                    <th className="px-4 py-2 font-medium">结局</th>
                  </tr>
                </thead>
                <tbody>
                  {changes.map((c) => {
                    const before = beforeDisplay(c);
                    return (
                      <tr key={c.changeId} className="border-border border-b last:border-0">
                        <td className="text-foreground px-4 py-2">{fieldLabel(c.field)}</td>
                        <td className="px-4 py-2">
                          <span
                            className={
                              before.known ? 'text-foreground break-all' : 'text-warning break-all'
                            }
                          >
                            {before.text}
                          </span>
                          <span className="text-muted-foreground block text-label-12">
                            来源：{c.beforeSource === 'MISSING' ? '无' : '平台侧快照'}
                          </span>
                        </td>
                        <td className="px-4 py-2">
                          <span className="text-foreground break-all">
                            {c.afterValue === '' ? '（空）' : c.afterValue}
                          </span>
                          <span className="text-muted-foreground block text-label-12">
                            来源：{afterSourceLabel(c.afterSource)}
                          </span>
                        </td>
                        <td className="px-4 py-2">
                          <Badge
                            tone={
                              c.outcome === 'APPLIED'
                                ? 'success'
                                : c.outcome === 'NO_CHANGE'
                                  ? 'warning'
                                  : 'neutral'
                            }
                          >
                            {outcomeLabel(c.outcome)}
                          </Badge>
                          {c.reason && (
                            <span className="text-muted-foreground mt-1 block text-label-12">
                              {c.reason}
                            </span>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </SectionCard>
      )}

      {caps && (
        <p className="text-muted-foreground text-label-12">
          {caps.relationNote}
        </p>
      )}
    </div>
  );
}
