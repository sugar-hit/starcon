'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard } from '@starcon/ui';
import {
  ArrowDownUp,
  CheckCheck,
  ChevronLeft,
  ChevronRight,
  Loader2,
  RefreshCw,
  ShieldAlert,
  TriangleAlert,
} from 'lucide-react';
import {
  actLifecycle,
  actionHint,
  actionLabel,
  actionSummary,
  applyPlatformStatus,
  divergenceLabel,
  divergenceTone,
  emptyDivergenceMessage,
  emptyListMessage,
  emptyTimelineMessage,
  eventHeadline,
  eventSubline,
  fetchCapabilities,
  fetchDivergences,
  fetchFilterOptions,
  fetchListingDetail,
  fetchLifecycles,
  fetchTimeline,
  formatTime,
  pageSummary,
  receiptSummary,
  sourceLabel,
  sourceTone,
  statusLabel,
  statusTone,
  syncPlatformStatus,
  syncSummary,
  unavailableDimensions,
  type ListingLifecycleCapabilities,
  type ListingLifecycleDetail,
  type ListingLifecycleDivergencePage,
  type ListingLifecycleEventPage,
  type ListingLifecycleFilterOptions,
  type ListingLifecyclePage,
} from './listing-lifecycle-model';

/**
 * 4.1.1.3 生命周期与下架 工作区。
 *
 * 蓝图：「管理 listing 状态：在售 / 草稿 / 平台审核中 / 平台驳回 / 主动下架 / 平台下架 /
 * 被封禁。支持平台侧状态变更被动同步，以及主动下架时选择是否回写平台。输出：状态变更记录
 * （来源：平台 or 人工）+ 可选的平台操作任务。」
 *
 * 🔴 页面上的四段（顺序就是处理顺序）：
 *
 * 1. **差异区** —— 放在最上面。平台把商品下架了而内部不知情，是**最常见的漏单原因**，
 *    这条信息不能埋在列表里等人翻。
 * 2. **生命周期列表** —— 七态筛选 + 来源筛选 + 店铺 / 平台 / 关键词；每行显式标出
 *    「最近一次变更来自平台还是人工」。
 * 3. **时间线** —— 选中商品后展示，按发生时刻倒序，**每条事件都标来源**。
 * 4. **人工动作** —— 主动下架（可选是否回写平台）/ 重新上架 / 提交平台审核 / 标记被封禁。
 *
 * 🔴 三态齐全且文案不同：加载中 / 出错了（带重试）/ 空态。
 * 空态还分两种：「还没有商品」与「筛选条件太窄」—— 那是两回事。
 */
export function ListingLifecycleWorkspace() {
  const [caps, setCaps] = useState<ListingLifecycleCapabilities | null>(null);
  const [options, setOptions] = useState<ListingLifecycleFilterOptions | null>(null);
  const [rows, setRows] = useState<ListingLifecyclePage | null>(null);
  const [divergences, setDivergences] = useState<ListingLifecycleDivergencePage | null>(null);
  const [detail, setDetail] = useState<ListingLifecycleDetail | null>(null);
  const [timeline, setTimeline] = useState<ListingLifecycleEventPage | null>(null);

  const [receiptLines, setReceiptLines] = useState<string[] | null>(null);
  const [syncLines, setSyncLines] = useState<string[] | null>(null);
  const [lastAction, setLastAction] = useState<string | null>(null);

  // ---- 筛选
  const [status, setStatus] = useState('');
  const [source, setSource] = useState('');
  const [shopCode, setShopCode] = useState('');
  const [platformCode, setPlatformCode] = useState('');
  const [keyword, setKeyword] = useState('');
  const [onlyDivergence, setOnlyDivergence] = useState(false);
  const [page, setPage] = useState(0);
  const [timelineSource, setTimelineSource] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);

  // ---- 人工动作表单
  const [pending, setPending] = useState<{ listingId: string; action: string } | null>(null);
  const [reason, setReason] = useState('');
  const [writeback, setWriteback] = useState(false);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, optionsResult, rowPage, divergencePage] = await Promise.all([
        fetchCapabilities(),
        fetchFilterOptions(),
        // 🔴 exactOptionalPropertyTypes：可选属性**不能显式传 undefined**，
        //    所以筛选项是「有值才带上」，不是「带上 undefined」。
        fetchLifecycles({
          ...(status ? { status } : {}),
          ...(source ? { source } : {}),
          ...(shopCode ? { shopCode } : {}),
          ...(platformCode ? { platformCode } : {}),
          ...(keyword ? { keyword } : {}),
          ...(onlyDivergence ? { onlyDivergence: true } : {}),
          page,
          size: 20,
        }),
        fetchDivergences(0, 20),
      ]);
      setCaps(capsResult);
      setOptions(optionsResult);
      setRows(rowPage);
      setDivergences(divergencePage);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [status, source, shopCode, platformCode, keyword, onlyDivergence, page]);

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadSelected = useCallback(async (listingId: string) => {
    setError(null);
    try {
      const [detailResult, timelineResult] = await Promise.all([
        fetchListingDetail(listingId),
        fetchTimeline(
          listingId,
          ...(timelineSource ? [timelineSource] : []),
          0,
          50,
        ),
      ]);
      setDetail(detailResult);
      setTimeline(timelineResult);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    }
  }, [timelineSource]);

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

  function openAction(listingId: string, action: string) {
    setPending({ listingId, action });
    setReason('');
    // 🔴 回写默认「否」：回写会把平台上正在卖的商品下架（**有资损风险**），
    //    漏发的后果只是内部与平台暂时不一致 —— 保守默认永远是对的那一侧。
    setWriteback(false);
    setReceiptLines(null);
  }

  async function confirmAction() {
    if (!pending) return;
    const { listingId, action } = pending;
    await run(async () => {
      const receipt = await actLifecycle(listingId, {
        action,
        ...(action === 'DELIST' ? { writebackToPlatform: writeback } : {}),
        reason,
      });
      setReceiptLines(receiptSummary(receipt));
      setLastAction(
        `${actionLabel(action)}：${receipt.fromStatus} → ${receipt.toStatus}` +
          (receipt.writebackRequested ? '（已回写平台）' : '（未回写平台）'),
      );
      setPending(null);
      if (selectedId === listingId) await loadSelected(listingId);
      await load();
    });
  }

  const filtered = useMemo(
    () => Boolean(status || source || shopCode || platformCode || keyword || onlyDivergence),
    [status, source, shopCode, platformCode, keyword, onlyDivergence],
  );

  const totalPages = rows ? Math.max(1, Math.ceil(rows.total / rows.size)) : 1;

  return (
    <div className="space-y-4">
      <PageHeader
        title="生命周期与下架"
        description={
          '管理商品状态：草稿 / 在售 / 平台审核中 / 平台驳回 / 主动下架 / 平台下架 / 被封禁；' +
          '支持平台侧状态变更被动同步，主动下架时可选择是否回写平台。' +
          '平台下架与内部下架是两个事实 —— 页面把两边的差异单列出来。'
        }
        actions={
          <>
            <Button
              size="sm"
              variant="secondary"
              disabled={busy}
              onClick={() =>
                void run(async () => {
                  const receipt = await syncPlatformStatus();
                  setSyncLines(syncSummary(receipt));
                  setLastAction(`平台状态同步：联动 ${receipt.appliedCount} 条`);
                  if (selectedId) await loadSelected(selectedId);
                  await load();
                })
              }
            >
              <RefreshCw className="size-4" />
              同步平台状态
            </Button>
            <Button size="sm" variant="secondary" disabled={busy} onClick={() => void load()}>
              <RefreshCw className="size-4" />
              刷新
            </Button>
          </>
        }
      />

      {error && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-center gap-2 rounded-lg border px-4 py-3 text-label-13">
          <TriangleAlert className="size-4 shrink-0" />
          <span className="flex-1">{error}</span>
          <Button size="sm" variant="secondary" onClick={() => void load()}>
            <RefreshCw className="mr-2 size-4" />
            重试
          </Button>
        </div>
      )}

      {lastAction && (
        <div className="border-brand/40 bg-brand/5 text-brand flex items-center gap-2 rounded-lg border px-4 py-3 text-label-13">
          <CheckCheck className="size-4 shrink-0" />
          <span className="flex-1">{lastAction}</span>
        </div>
      )}

      {loading && !rows ? (
        <div className="text-muted-foreground flex items-center gap-2 px-1 py-8 text-label-13">
          <Loader2 className="size-4 animate-spin" />
          正在加载生命周期数据…
        </div>
      ) : (
        <>
          {/* ---- 🔴 差异区（放最上面：这是本模块最要紧的一条信息） ---- */}
          <SectionCard
            title="平台侧 vs 内部侧的差异"
            description="平台把商品下架了而内部不知情，是最常见的漏单原因。判定由服务端算好，页面原样显示。"
            extra={
              <span className="text-muted-foreground text-label-12">
                {divergences ? `${divergences.total} 条` : '—'}
              </span>
            }
          >
            {!divergences || divergences.items.length === 0 ? (
              <EmptyState
                icon={<CheckCheck className="size-8 text-muted-foreground" />}
                title="当前没有差异"
                description={emptyDivergenceMessage()}
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-label-13">
                  <thead className="text-muted-foreground border-border border-b">
                    <tr>
                      <th className="px-4 py-2 font-medium">商品</th>
                      <th className="px-4 py-2 font-medium">差异</th>
                      <th className="px-4 py-2 font-medium">说明</th>
                      <th className="px-4 py-2 font-medium">严重度</th>
                      <th className="px-4 py-2 font-medium">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {divergences.items.map((row) => (
                      <tr key={row.listingId} className="border-border border-b last:border-0">
                        <td className="text-foreground px-4 py-2">
                          {row.title || row.externalItemId}
                          <div className="text-muted-foreground text-label-12">
                            {row.shopCode} · {row.platformCode} · {row.externalItemId}
                          </div>
                        </td>
                        <td className="px-4 py-2">
                          <Badge tone={divergenceTone(row.divergenceKind)}>
                            {divergenceLabel(row.divergenceKind)}
                          </Badge>
                        </td>
                        <td className="text-foreground px-4 py-2">
                          {row.divergenceNote}
                          <div className="text-muted-foreground text-label-12">
                            内部 {row.internalStatus} · 平台 {row.platformStatus || row.platformEventType || '（无平台事实）'}
                          </div>
                        </td>
                        <td className="px-4 py-2">
                          <Badge tone={row.severity === 'HIGH' ? 'danger' : 'warning'}>
                            {row.severity === 'HIGH' ? '漏单风险' : '需跟进'}
                          </Badge>
                        </td>
                        <td className="px-4 py-2">
                          <div className="flex flex-wrap gap-2">
                            <Button
                              size="sm"
                              variant="secondary"
                              disabled={busy || !row.platformEventType}
                              onClick={() =>
                                void run(async () => {
                                  const receipt = await applyPlatformStatus(row.listingId, {
                                    eventType: row.platformEventType,
                                    platformStatus: row.platformStatus || row.platformEventType,
                                    ingestChannel: 'API',
                                    note: '差异区联动：内部状态跟随平台',
                                  });
                                  setReceiptLines(receiptSummary(receipt));
                                  setLastAction(`已跟随平台状态：${row.title || row.externalItemId}`);
                                  await load();
                                })
                              }
                            >
                              跟随平台状态
                            </Button>
                            <Button
                              size="sm"
                              variant="ghost"
                              disabled={busy}
                              onClick={() => {
                                setSelectedId(row.listingId);
                                void loadSelected(row.listingId);
                              }}
                            >
                              查看
                            </Button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          {/* ---- 人工动作回执 ---- */}
          {receiptLines && (
            <SectionCard title="动作回执" description="逐条说明这次动作真的做了什么。">
              <ul className="text-foreground space-y-1 text-label-13">
                {receiptLines.map((line, index) => (
                  <li key={index} className="flex gap-2">
                    <span className="text-muted-foreground">·</span>
                    <span>{line}</span>
                  </li>
                ))}
              </ul>
            </SectionCard>
          )}

          {syncLines && (
            <SectionCard
              title="平台状态同步回执"
              description="真的联动了几条、哪些没联动以及为什么 —— 静默跳过会让运营以为全同步了。"
            >
              <ul className="text-foreground space-y-1 text-label-13">
                {syncLines.map((line, index) => (
                  <li key={index} className="flex gap-2">
                    <span className="text-muted-foreground">·</span>
                    <span>{line}</span>
                  </li>
                ))}
              </ul>
            </SectionCard>
          )}

          {/* ---- 🔴 人工动作确认面板 ---- */}
          {pending && (
            <SectionCard
              title={`确认：${actionLabel(pending.action)}`}
              description={actionHint(pending.action)}
            >
              <div className="space-y-3">
                <div>
                  <label className="text-muted-foreground mb-1 block text-label-12">
                    动因（为什么做这个动作，会进留痕）
                  </label>
                  <Input
                    value={reason}
                    maxLength={500}
                    placeholder="例如：库存告罄，先下架"
                    onChange={(e) => setReason(e.target.value)}
                  />
                </div>
                {pending.action === 'DELIST' && (
                  <label className="flex items-start gap-2 text-label-13">
                    <input
                      type="checkbox"
                      className="mt-1"
                      checked={writeback}
                      onChange={(e) => setWriteback(e.target.checked)}
                    />
                    <span>
                      <span className="text-foreground">同时回写平台（把平台上正在卖的商品也下架）</span>
                      <span className="text-muted-foreground mt-0.5 block text-label-12">
                        不勾选 = 只改内部状态，平台侧不知情（平台可能还在卖）。
                        回写会产生一个平台操作任务，由 02 回写队列派发 —— 有资损风险，默认不勾。
                      </span>
                    </span>
                  </label>
                )}
                <div className="flex gap-2">
                  <Button size="sm" disabled={busy} onClick={() => void confirmAction()}>
                    确认
                  </Button>
                  <Button
                    size="sm"
                    variant="secondary"
                    disabled={busy}
                    onClick={() => setPending(null)}
                  >
                    取消
                  </Button>
                </div>
              </div>
            </SectionCard>
          )}

          {/* ---- 生命周期列表 ---- */}
          <SectionCard
            title="生命周期列表"
            description="七态筛选 + 来源筛选（最近一次状态变更来自平台还是人工）。"
            extra={
              rows ? (
                <span className="text-muted-foreground text-label-12">{pageSummary(rows)}</span>
              ) : null
            }
          >
            <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
              <div>
                <label className="text-muted-foreground mb-1 block text-label-12">生命周期状态</label>
                <select
                  className="border-control-border bg-card text-foreground h-9 w-full rounded-md border px-3 text-label-13"
                  value={status}
                  onChange={(e) => {
                    setStatus(e.target.value);
                    setPage(0);
                  }}
                >
                  <option value="">全部状态</option>
                  {(options?.lifecycleStatuses ?? []).map((value) => (
                    <option key={value} value={value}>
                      {statusLabel(value)}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label className="text-muted-foreground mb-1 block text-label-12">
                  最近变更来源
                </label>
                <select
                  className="border-control-border bg-card text-foreground h-9 w-full rounded-md border px-3 text-label-13"
                  value={source}
                  onChange={(e) => {
                    setSource(e.target.value);
                    setPage(0);
                  }}
                >
                  <option value="">全部来源</option>
                  {(options?.actionSources ?? []).map((value) => (
                    <option key={value} value={value}>
                      {sourceLabel(value)}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label className="text-muted-foreground mb-1 block text-label-12">店铺编码</label>
                <Input
                  value={shopCode}
                  onChange={(e) => {
                    setShopCode(e.target.value);
                    setPage(0);
                  }}
                />
              </div>
              <div>
                <label className="text-muted-foreground mb-1 block text-label-12">平台编码</label>
                <Input
                  value={platformCode}
                  onChange={(e) => {
                    setPlatformCode(e.target.value);
                    setPage(0);
                  }}
                />
              </div>
              <div>
                <label className="text-muted-foreground mb-1 block text-label-12">关键词</label>
                <Input
                  value={keyword}
                  placeholder="标题或平台商品 ID"
                  onChange={(e) => {
                    setKeyword(e.target.value);
                    setPage(0);
                  }}
                />
              </div>
            </div>

            <div className="mb-4 flex flex-wrap items-center gap-4">
              <label className="flex items-center gap-2 text-label-13">
                <input
                  type="checkbox"
                  checked={onlyDivergence}
                  onChange={(e) => {
                    setOnlyDivergence(e.target.checked);
                    setPage(0);
                  }}
                />
                只看有差异的
              </label>
              <div className="ml-auto flex items-center gap-2">
                <Button
                  size="icon"
                  variant="secondary"
                  disabled={page <= 0}
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                >
                  <ChevronLeft className="size-4" />
                </Button>
                <span className="text-muted-foreground text-label-12">
                  第 {page + 1} / {totalPages} 页
                </span>
                <Button
                  size="icon"
                  variant="secondary"
                  disabled={page + 1 >= totalPages}
                  onClick={() => setPage((p) => p + 1)}
                >
                  <ChevronRight className="size-4" />
                </Button>
              </div>
            </div>

            {!rows || rows.items.length === 0 ? (
              <EmptyState
                icon={<ShieldAlert className="size-8 text-muted-foreground" />}
                title={filtered ? '没有符合条件的商品' : '还没有登记任何商品'}
                description={emptyListMessage(filtered)}
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-label-13">
                  <thead className="text-muted-foreground border-border border-b">
                    <tr>
                      <th className="px-4 py-2 font-medium">商品</th>
                      <th className="px-4 py-2 font-medium">当前状态</th>
                      <th className="px-4 py-2 font-medium">最近变更</th>
                      <th className="px-4 py-2 font-medium">平台侧</th>
                      <th className="px-4 py-2 font-medium">差异</th>
                      <th className="px-4 py-2 font-medium">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.items.map((row) => (
                      <tr key={row.listingId} className="border-border border-b last:border-0">
                        <td className="text-foreground px-4 py-2">
                          {row.title || row.externalItemId}
                          <div className="text-muted-foreground text-label-12">
                            {row.shopCode} · {row.platformCode} · {row.externalItemId}
                          </div>
                        </td>
                        <td className="px-4 py-2">
                          <Badge tone={statusTone(row.status)}>{statusLabel(row.status)}</Badge>
                        </td>
                        <td className="px-4 py-2">
                          {row.lastChangeSource ? (
                            <>
                              <Badge tone={sourceTone(row.lastChangeSource)}>
                                {sourceLabel(row.lastChangeSource)}
                              </Badge>
                              <div className="text-muted-foreground mt-0.5 text-label-12">
                                {row.lastChangeFrom || '—'} → {row.lastChangeTo || '—'}
                              </div>
                              <div className="text-muted-foreground text-label-12">
                                {formatTime(row.lastChangeAt)}
                              </div>
                            </>
                          ) : (
                            <span className="text-muted-foreground text-label-12">暂无变更记录</span>
                          )}
                        </td>
                        <td className="px-4 py-2">
                          {row.platformEventType ? (
                            <>
                              <div className="text-foreground">{row.platformStatus || row.platformEventType}</div>
                              <div className="text-muted-foreground text-label-12">
                                {formatTime(row.platformEventAt)}
                              </div>
                            </>
                          ) : (
                            <span className="text-muted-foreground text-label-12">无平台事实</span>
                          )}
                        </td>
                        <td className="px-4 py-2">
                          {row.divergence ? (
                            <Badge tone={divergenceTone(row.divergenceKind)}>
                              {divergenceLabel(row.divergenceKind)}
                            </Badge>
                          ) : (
                            <span className="text-muted-foreground text-label-12">一致</span>
                          )}
                        </td>
                        <td className="px-4 py-2">
                          <div className="flex flex-wrap gap-2">
                            <Button
                              size="sm"
                              variant="secondary"
                              disabled={busy}
                              onClick={() => {
                                setSelectedId(row.listingId);
                                void loadSelected(row.listingId);
                              }}
                            >
                              时间线
                            </Button>
                            {/* 🔴 合法动作由服务端算好（allowedActions），页面据此渲染按钮 ——
                                不自己猜该允许什么；非法动作即使被构造出来，服务端也会显式拒绝。 */}
                            {(row.allowedActions ?? []).map((action) => (
                              <Button
                                key={`row-${action}`}
                                size="sm"
                                variant="ghost"
                                disabled={busy}
                                onClick={() => openAction(row.listingId, action)}
                              >
                                {actionLabel(action)}
                              </Button>
                            ))}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          {/* ---- 🔴 时间线（每条事件显式标注来源） ---- */}
          {selectedId && (
            <SectionCard
              title="生命周期时间线"
              description="按发生时刻倒序。每条事件都标出来源 —— 平台与人工是两件事，处置方式相反。"
              extra={
                <div className="flex items-center gap-2">
                  <select
                    className="border-control-border bg-card text-foreground h-8 rounded-md border px-2 text-label-12"
                    value={timelineSource}
                    onChange={(e) => {
                      setTimelineSource(e.target.value);
                      void loadSelected(selectedId);
                    }}
                  >
                    <option value="">全部来源</option>
                    {(options?.actionSources ?? []).map((value) => (
                      <option key={value} value={value}>
                        {sourceLabel(value)}
                      </option>
                    ))}
                  </select>
                  {timeline ? (
                    <span className="text-muted-foreground text-label-12">
                      {pageSummary(timeline)}
                    </span>
                  ) : null}
                </div>
              }
            >
              {!timeline || timeline.items.length === 0 ? (
                <EmptyState
                  icon={<ArrowDownUp className="size-8 text-muted-foreground" />}
                  title="没有状态变更记录"
                  description={emptyTimelineMessage(timelineSource)}
                />
              ) : (
                <ol className="space-y-3">
                  {timeline.items.map((event) => (
                    <li
                      key={event.eventId}
                      className="border-border flex flex-wrap items-start gap-3 border-b pb-3 last:border-0 last:pb-0"
                    >
                      <Badge tone={sourceTone(event.source)}>{sourceLabel(event.source)}</Badge>
                      <div className="min-w-0 flex-1">
                        <div className="text-foreground">{eventHeadline(event)}</div>
                        <div className="text-muted-foreground text-label-12">
                          {eventSubline(event)}
                        </div>
                        {event.conflictDetected && (
                          <div className="text-destructive text-label-12">
                            冲突：{event.conflictNote}（以平台为准）
                          </div>
                        )}
                      </div>
                      <span className="text-muted-foreground text-label-12">
                        {formatTime(event.occurredAt)}
                      </span>
                    </li>
                  ))}
                </ol>
              )}
            </SectionCard>
          )}

          {/* ---- 动作留痕 ---- */}
          {selectedId && detail && (
            <SectionCard
              title="动作留痕"
              description="谁、为什么、要不要回写平台、关联到哪个平台任务。"
            >
              {detail.actions.length === 0 ? (
                <EmptyState
                  icon={<ShieldAlert className="size-8 text-muted-foreground" />}
                  title="还没有动作留痕"
                  description="在这个商品上做一次人工动作（主动下架 / 重新上架 / 提交平台审核 / 标记被封禁）后，动因与回写选择会出现在这里。"
                />
              ) : (
                <ul className="space-y-2">
                  {detail.actions.map((action) => (
                    <li key={action.actionId} className="border-border border-b pb-2 last:border-0 last:pb-0">
                      <div className="text-foreground text-label-13">{actionSummary(action)}</div>
                      <div className="text-muted-foreground text-label-12">
                        {formatTime(action.createdAt)}
                        {action.writebackBatchNo ? ` · 批次 ${action.writebackBatchNo}` : ''}
                        {action.operationId ? ` · 队列任务 ${action.operationId}` : ''}
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </SectionCard>
          )}

          {/* ---- 能力声明（未交付段） ---- */}
          {caps && (
            <SectionCard
              title="未交付的能力"
              description="这些段落归别的模块 —— 页面据此显示「这归谁管」，而不是画成已完成。"
            >
              {unavailableDimensions(caps).length === 0 ? (
                <EmptyState
                  icon={<CheckCheck className="size-8 text-muted-foreground" />}
                  title="本模块声明的能力全部已交付"
                  description={caps.stateMachineRule}
                />
              ) : (
                <ul className="space-y-3">
                  {unavailableDimensions(caps).map((dimension) => (
                    <li key={dimension.dimension}>
                      <div className="text-foreground text-label-13">
                        <Badge tone="warning">未交付</Badge>{' '}
                        {dimension.description}
                      </div>
                      <div className="text-muted-foreground text-label-12">
                        责任模块：{dimension.ownerModule} —— {dimension.impact}
                      </div>
                    </li>
                  ))}
                </ul>
              )}
              <div className="text-muted-foreground mt-4 space-y-1 text-label-12">
                <div>状态机规则：{caps.stateMachineRule}</div>
                <div>差异判定口径：{caps.divergenceRule}</div>
                <div>上下游：{caps.relationNote}</div>
              </div>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}
