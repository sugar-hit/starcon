'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard } from '@starcon/ui';
import {
  AlertTriangle,
  CheckCheck,
  ChevronLeft,
  ChevronRight,
  Loader2,
  RefreshCw,
  Send,
  TriangleAlert,
} from 'lucide-react';
import {
  acknowledgeQueueItem,
  conflictSummary,
  emptyConflictsMessage,
  emptyEventsMessage,
  emptyQueueMessage,
  EVENT_TYPE_OPTIONS,
  eventTypeLabel,
  fetchCapabilities,
  fetchConflicts,
  fetchEvents,
  fetchQueue,
  ingestChannelLabel,
  operationStatusLabel,
  operationStatusTone,
  pageSummary,
  queueKindLabel,
  queueStateLabel,
  queueStateTone,
  republish,
  republishSummary,
  resolveQueueItem,
  syncDelayLabel,
  unavailableDimensions,
  type ListingStatusCapabilities,
  type ListingStatusEventPage,
  type ListingStatusEventRow,
  type ListingStatusQueuePage,
  type ListingStatusQueueRow,
} from './listing-status-sync-model';

/**
 * 4.3.2.2 平台状态回流 工作区。
 *
 * 蓝图：「接收并展示平台侧的商品状态变化：审核通过/驳回、下架、限流、被抑制（suppressed）、
 * 类目审核、价格异常通知，并支持一键处理（如按驳回原因批量修正重发）。输出：状态变更记录 +
 * 需处理队列。**回流是平台状态的权威来源**，内部状态与之冲突时以平台为准并告警。」
 *
 * 🔴 页面上的四段（顺序就是处理顺序）：
 *
 * 1. **冲突与告警区** —— 放在最上面。内部状态与平台状态冲突时，平台是权威，
 *    这条告警是本模块最要紧的信息，不能埋在列表里等人翻。
 * 2. **需处理队列** —— 按事件类型分组（驳回 / 下架 / 被抑制 ⇒ 可一键修正重发；
 *    限流 / 类目审核 / 价格异常 ⇒ 需人工处理）。**盲��重发解决不了限流**，
 *    所以「一键处理」只作用于可重发的那一类。
 * 3. **状态变更记录** —— 真分页 + 按店铺 / 平台 / 类型 / 时间筛选。
 * 4. **一键处理** —— 按驳回原因筛 → 批量发起修正重发 → 回执逐条展示。
 *
 * 🔴 三态齐全且文案不同：加载中 / 出错了（带重试）/ 空态。
 * 空态还分两种：「还没有记录」与「事件源尚未接入」—— 那是两回事。
 */
export function ListingStatusSyncWorkspace() {
  const [caps, setCaps] = useState<ListingStatusCapabilities | null>(null);
  const [events, setEvents] = useState<ListingStatusEventPage | null>(null);
  const [queue, setQueue] = useState<ListingStatusQueuePage | null>(null);
  const [conflicts, setConflicts] = useState<ListingStatusQueuePage | null>(null);
  const [receiptLines, setReceiptLines] = useState<string[] | null>(null);
  const [lastAction, setLastAction] = useState<string | null>(null);

  // ---- 筛选
  const [eventType, setEventType] = useState('');
  const [shopCode, setShopCode] = useState('');
  const [page, setPage] = useState(0);

  // ---- 一键处理
  const [rejectionReason, setRejectionReason] = useState('');
  const [republishEventTypes, setRepublishEventTypes] = useState<string[]>(['REJECTED']);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, eventPage, queuePage, conflictPage] = await Promise.all([
        fetchCapabilities(),
        // 🔴 exactOptionalPropertyTypes：可选属性**不能显式传 undefined**，
        //    所以筛选项是「有值才带上」，不是「带上 undefined」。
        fetchEvents({
          ...(eventType ? { eventType } : {}),
          ...(shopCode ? { shopCode } : {}),
          page,
          size: 20,
        }),
        fetchQueue({ size: 50 }),
        fetchConflicts(0, 20),
      ]);
      setCaps(capsResult);
      setEvents(eventPage);
      setQueue(queuePage);
      setConflicts(conflictPage);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [eventType, shopCode, page]);

  useEffect(() => {
    void load();
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

  const republishable = useMemo(
    () => (queue?.items ?? []).filter((item) => item.kind === 'REPUBLISH'),
    [queue],
  );
  const reviewable = useMemo(
    () => (queue?.items ?? []).filter((item) => item.kind === 'REVIEW'),
    [queue],
  );
  const openRepublishable = useMemo(
    () =>
      republishable.filter(
        (item) => item.state === 'OPEN' || item.state === 'ACKNOWLEDGED',
      ),
    [republishable],
  );

  /** 队列里出现过的驳回原因（去重），供「按驳回原因筛选」的下拉提示。 */
  const knownReasons = useMemo(() => {
    const set = new Set<string>();
    for (const item of republishable) {
      if (item.rejectionReason) set.add(item.rejectionReason);
    }
    return [...set].sort();
  }, [republishable]);

  /** 真实平台推送通道是否已接入（能力表里 available）。 */
  const webhookIngested = useMemo(
    () => caps?.dimensions.some((d) => d.dimension === 'real_platform_webhook' && d.available) ?? false,
    [caps],
  );

  function toggleRepublishType(value: string) {
    setRepublishEventTypes((prev) =>
      prev.includes(value) ? prev.filter((t) => t !== value) : [...prev, value],
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="平台状态回流"
        description={
          '接收并展示平台侧的商品状态变化：审核通过 / 驳回、下架、限流、被抑制、类目审核、价格异常通知；' +
          '回流是平台状态的权威来源 —— 内部状态与之冲突时以平台为准并告警。'
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

      {loading && !events ? (
        <div className="text-muted-foreground flex items-center gap-2 px-1 py-8 text-label-13">
          <Loader2 className="size-4 animate-spin" />
          正在加载平台状态回流数据…
        </div>
      ) : (
        <>
          {/* ---- 🔴 冲突与告警区（放最上面：这是本模块最要紧的一条信息） ---- */}
          <SectionCard
            title="冲突与告警"
            description="内部状态与平台状态对「当前是否在售」的主张相反时，平台为准并在这里告警。判定由服务端算好，页面原样显示。"
          >
            {!conflicts || conflicts.items.length === 0 ? (
              <EmptyState
                icon={<CheckCheck className="size-8 text-muted-foreground" />}
                title="当前没有冲突"
                description={emptyConflictsMessage()}
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-label-13">
                  <thead className="text-muted-foreground border-border border-b">
                    <tr>
                      <th className="px-4 py-2 font-medium">商品</th>
                      <th className="px-4 py-2 font-medium">事件</th>
                      <th className="px-4 py-2 font-medium">冲突说明</th>
                      <th className="px-4 py-2 font-medium">处置</th>
                      <th className="px-4 py-2 font-medium">时刻</th>
                    </tr>
                  </thead>
                  <tbody>
                    {conflicts.items.map((item) => (
                      <tr key={item.queueId} className="border-border border-b last:border-0">
                        <td className="text-foreground px-4 py-2">
                          {item.title || item.externalItemId}
                          <div className="text-muted-foreground text-label-12">
                            {item.shopCode} · {item.platformCode} · {item.externalItemId}
                          </div>
                        </td>
                        <td className="px-4 py-2">
                          <Badge tone="warning">{eventTypeLabel(item.eventType)}</Badge>
                        </td>
                        <td className="text-foreground px-4 py-2">
                          {item.note || '内部状态与平台状态冲突'}
                          <div className="text-muted-foreground text-label-12">
                            以平台为准 —— 平台状态是权威来源
                          </div>
                        </td>
                        <td className="px-4 py-2">
                          <Badge tone={queueStateTone(item.state)}>{queueStateLabel(item.state)}</Badge>
                        </td>
                        <td className="text-muted-foreground px-4 py-2 text-label-12">
                          {new Date(item.createdAt).toLocaleString('zh-CN')}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          {/* ---- 需处理队列 ---- */}
          <SectionCard
            title="需处理队列"
            description="可一键修正重发（驳回 / 下架 / 被抑制）与需人工处理（限流 / 类目审核 / 价格异常）是两类不同的待办 —— 盲目重发解决不了限流。"
            extra={
              <span className="text-muted-foreground text-label-12">
                可重发 {openRepublishable.length} 条 · 需人工 {reviewable.length} 条
              </span>
            }
          >
            {!queue || queue.items.length === 0 ? (
              <EmptyState
                icon={<TriangleAlert className="size-8 text-muted-foreground" />}
                title="没有待处理事项"
                description={emptyQueueMessage()}
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-label-13">
                  <thead className="text-muted-foreground border-border border-b">
                    <tr>
                      <th className="px-4 py-2 font-medium">商品</th>
                      <th className="px-4 py-2 font-medium">类型</th>
                      <th className="px-4 py-2 font-medium">驳回原因</th>
                      <th className="px-4 py-2 font-medium">状态</th>
                      <th className="px-4 py-2 font-medium">02 队列</th>
                      <th className="px-4 py-2 font-medium">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {queue.items.map((item) => (
                      <tr key={item.queueId} className="border-border border-b last:border-0">
                        <td className="text-foreground px-4 py-2">
                          {item.title || item.externalItemId}
                          <div className="text-muted-foreground text-label-12">
                            {item.shopCode} · {item.platformCode} · {item.externalItemId}
                          </div>
                        </td>
                        <td className="px-4 py-2">
                          <Badge tone={item.kind === 'CONFLICT_ALERT' ? 'warning' : 'neutral'}>
                            {queueKindLabel(item.kind)} · {eventTypeLabel(item.eventType)}
                          </Badge>
                        </td>
                        <td className="text-foreground px-4 py-2">
                          {item.rejectionReason || '—'}
                        </td>
                        <td className="px-4 py-2">
                          <Badge tone={queueStateTone(item.state)}>{queueStateLabel(item.state)}</Badge>
                        </td>
                        <td className="px-4 py-2">
                          {item.operationId ? (
                            <div>
                              <Badge tone={operationStatusTone(item.operationStatus)}>
                                {operationStatusLabel(item.operationStatus, item.operationVerification)}
                              </Badge>
                              <div className="text-muted-foreground text-label-12">{item.operationId}</div>
                            </div>
                          ) : (
                            <span className="text-muted-foreground text-label-12">尚未发起</span>
                          )}
                        </td>
                        <td className="px-4 py-2">
                          <div className="flex flex-wrap gap-2">
                            {item.state === 'OPEN' && (
                              <Button
                                size="sm"
                                variant="secondary"
                                disabled={busy}
                                onClick={() =>
                                  void run(async () => {
                                    await acknowledgeQueueItem(item.queueId, '已查看');
                                    setLastAction(`已确认 ${item.externalItemId || item.listingId}`);
                                    await load();
                                  })
                                }
                              >
                                确认
                              </Button>
                            )}
                            {item.state !== 'RESOLVED' && item.state !== 'CANCELLED' && (
                              <Button
                                size="sm"
                                variant="secondary"
                                disabled={busy}
                                onClick={() =>
                                  void run(async () => {
                                    await resolveQueueItem(item.queueId, '人工处理完成');
                                    setLastAction(`已标记处理完成 ${item.externalItemId || item.listingId}`);
                                    await load();
                                  })
                                }
                              >
                                处理完成
                              </Button>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          {/* ---- 🔴 一键处理（按驳回原因批量修正重发） ---- */}
          <SectionCard
            title="一键处理 · 按驳回原因批量修正重发"
            description="按驳回原因筛出待办并批量发起修正重发；动作走 2.5.1.1 的回写任务队列（幂等键唯一），重复点不会产生第二个平台任务。"
          >
            <div className="space-y-3">
              <div className="grid gap-3 sm:grid-cols-2">
                <Input
                  placeholder="按驳回原因筛选（平台原文，逐字相等；留空 = 不按它筛）"
                  value={rejectionReason}
                  onChange={(e) => setRejectionReason(e.target.value)}
                />
                <Input
                  placeholder="按店铺编码筛选（可选）"
                  value={shopCode}
                  onChange={(e) => setShopCode(e.target.value)}
                />
              </div>

              <div>
                <p className="text-foreground mb-2 text-label-13 font-medium">发起的事件类型</p>
                <div className="flex flex-wrap gap-2">
                  {(['REJECTED', 'DELISTED', 'SUPPRESSED'] as const).map((value) => (
                    <Button
                      key={value}
                      size="sm"
                      variant={republishEventTypes.includes(value) ? 'brand' : 'outline'}
                      onClick={() => toggleRepublishType(value)}
                    >
                      {eventTypeLabel(value)}
                    </Button>
                  ))}
                </div>
                <p className="text-muted-foreground mt-2 text-label-12">
                  限流 / 类目审核 / 价格异常<strong>不在</strong>可重发范围内 —— 它们进的是「需人工处理」，
                  盲目重发解决不了限流。
                </p>
              </div>

              {knownReasons.length > 0 && (
                <div className="text-muted-foreground text-label-12">
                  队列里出现过的驳回原因：
                  {knownReasons.map((r) => `「${r}」`).join('、')}
                </div>
              )}

              <div className="flex flex-wrap items-center gap-3">
                <Button
                  disabled={busy || openRepublishable.length === 0}
                  onClick={() =>
                    void run(async () => {
                      const receipt = await republish({
                        ...(rejectionReason ? { rejectionReason } : {}),
                        eventTypes: republishEventTypes,
                        ...(shopCode ? { shopCode } : {}),
                        limit: 50,
                        note: '按驳回原因批量修正重发',
                      });
                      setReceiptLines(republishSummary(receipt));
                      await load();
                    })
                  }
                >
                  <Send className="mr-2 size-4" />
                  发起修正重发（命中 {openRepublishable.length} 条可重发）
                </Button>
                {openRepublishable.length === 0 && (
                  <span className="text-muted-foreground text-label-12">
                    当前没有可发起修正重发的待办
                  </span>
                )}
              </div>

              {receiptLines && receiptLines.length > 0 && (
                <div className="border-border bg-muted/30 rounded-lg border px-3 py-3">
                  <p className="text-foreground mb-2 text-label-13 font-medium">回执</p>
                  <ul className="text-muted-foreground space-y-1 text-label-12">
                    {receiptLines.map((line, i) => (
                      <li key={`${line}-${i}`}>{line}</li>
                    ))}
                  </ul>
                </div>
              )}

              {lastAction && (
                <p className="text-muted-foreground text-label-12">{lastAction}</p>
              )}
            </div>
          </SectionCard>

          {/* ---- 状态变更记录（真分页 + 筛选） ---- */}
          <SectionCard
            title="状态变更记录"
            description="平台侧的商品状态变化逐条落库；内部人工改状态的那几条是另一件事（4.1.1.1 记录在案），本模块只列平台来源。"
            extra={
              <div className="flex items-center gap-2">
                <Input
                  className="w-40"
                  placeholder="事件类型筛选"
                  value={eventType}
                  onChange={(e) => {
                    setEventType(e.target.value);
                    setPage(0);
                  }}
                />
                <Button size="sm" variant="secondary" onClick={() => void load()}>
                  <RefreshCw className="mr-2 size-4" />
                  刷新
                </Button>
              </div>
            }
          >
            {!events || events.items.length === 0 ? (
              <EmptyState
                icon={<AlertTriangle className="size-8 text-muted-foreground" />}
                title="还没有状态变更记录"
                description={emptyEventsMessage(webhookIngested)}
              />
            ) : (
              <>
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-label-13">
                    <thead className="text-muted-foreground border-border border-b">
                      <tr>
                        <th className="px-4 py-2 font-medium">商品</th>
                        <th className="px-4 py-2 font-medium">事件</th>
                        <th className="px-4 py-2 font-medium">平台状态</th>
                        <th className="px-4 py-2 font-medium">通道</th>
                        <th className="px-4 py-2 font-medium">发生时刻</th>
                        <th className="px-4 py-2 font-medium">同步延迟</th>
                      </tr>
                    </thead>
                    <tbody>
                      {events.items.map((event: ListingStatusEventRow) => (
                        <tr key={event.eventId} className="border-border border-b last:border-0">
                          <td className="text-foreground px-4 py-2">
                            {event.title || event.externalItemId}
                            <div className="text-muted-foreground text-label-12">
                              {event.shopCode} · {event.platformCode} · {event.externalItemId}
                            </div>
                          </td>
                          <td className="px-4 py-2">
                            <Badge tone={event.conflictDetected ? 'warning' : 'neutral'}>
                              {eventTypeLabel(event.eventType)}
                            </Badge>
                            {event.conflictDetected && (
                              <div className="text-warning mt-1 text-label-12">
                                {conflictSummary(event)}
                              </div>
                            )}
                          </td>
                          <td className="text-foreground px-4 py-2">
                            {event.platformStatus || event.toStatus || '—'}
                            {event.rejectionReason && (
                              <div className="text-muted-foreground text-label-12">
                                {event.rejectionReason}
                              </div>
                            )}
                          </td>
                          <td className="text-muted-foreground px-4 py-2 text-label-12">
                            {ingestChannelLabel(event.ingestChannel)}
                          </td>
                          <td className="text-muted-foreground px-4 py-2 text-label-12">
                            {new Date(event.occurredAt).toLocaleString('zh-CN')}
                          </td>
                          <td className="text-muted-foreground px-4 py-2 text-label-12">
                            {syncDelayLabel(event.occurredAt, event.receivedAt)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <div className="text-muted-foreground mt-3 flex items-center justify-between text-label-12">
                  <span>{pageSummary(events)}</span>
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={page === 0}
                      onClick={() => setPage((p) => Math.max(0, p - 1))}
                    >
                      <ChevronLeft className="mr-1 size-4" />
                      上一页
                    </Button>
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={(page + 1) * events.size >= events.total}
                      onClick={() => setPage((p) => p + 1)}
                    >
                      下一页
                      <ChevronRight className="ml-1 size-4" />
                    </Button>
                  </div>
                </div>
              </>
            )}
          </SectionCard>

          {/* ---- 未交付维度的明账 ---- */}
          {caps && unavailableDimensions(caps).length > 0 && (
            <SectionCard
              title="未交付能力的归属"
              description="下面这些不属于本模块，页面据此显示「这归谁管」而不是显示一个必然失败的按钮。"
            >
              <ul className="space-y-2 text-label-13">
                {unavailableDimensions(caps).map((d) => (
                  <li key={d.dimension} className="border-border border-b pb-2 last:border-0 last:pb-0">
                    <span className="text-foreground font-medium">{d.dimension}</span>
                    <span className="text-muted-foreground"> · 归 {d.ownerModule}</span>
                    <div className="text-muted-foreground text-label-12">{d.impact}</div>
                  </li>
                ))}
              </ul>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}
