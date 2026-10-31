'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  PageHeader,
  SectionCard,
  Segmented,
  StatCard,
} from '@starcon/ui';
import {
  CircleAlert,
  FileKey2,
  ListChecks,
  Loader2,
  RefreshCw,
  RotateCcw,
  ScrollText,
  Send,
  TriangleAlert,
} from 'lucide-react';
import {
  DEAD_LETTER_STATUS_OPTIONS,
  DELIVERY_STATUS_OPTIONS,
  attemptSourceLabel,
  capabilityTone,
  deadLetterStatusTone,
  deliveryCapability,
  deliveryStatusHint,
  deliveryStatusTone,
  eventIdLabel,
  fetchCapabilities,
  fetchDeadLetters,
  fetchDeliveries,
  fetchDeliveryDetail,
  fetchOverview,
  fetchReplayTasks,
  isDelivered,
  paginationLabel,
  publishEvent,
  replayDeadLetter,
  replayDelivery,
  replayNotice,
  replayTriggerLabel,
  statusCodeLabel,
  sweepRetryDue,
  unavailableHint,
  type Tone,
} from './delivery-record-model';
import type {
  DeliveryRecordCapabilities,
  DeliveryRecordDeadLetterRow,
  DeliveryRecordDetail,
  DeliveryRecordOverview,
  DeliveryRecordReplayTaskRow,
} from '../../../../../../packages/api-contracts/src/index';

/**
 * 18.1.2.2「投递记录」工作区（卷 18 前端入口 `/integrations/deliveries`）。
 *
 * <p>蓝图：「<strong>投递记录</strong>：记录事件投递明细：事件 ID、订阅方、投递状态、响应码、
 * 重试次数；提供手动重投与死信重放。输入：投递记录；重放请求。输出：投递日志 + 重放任务。
 * 关系：上游 = 18.1.2.1；下游 = 外部端点。
 * <strong>接收方应按 event_id 幂等去重</strong>——投递保证是"至少一次"，不是"恰好一次"。」
 *
 * <p>🔴 页面上的六条纪律：
 *
 * 1. **「投递过」不等于「送达了」**。响应码 0 显示成「没有响应」——
 *    0 的含义是「**根本没拿到响应**」（连接被拒 / 超时 / 没发出去）。
 * 2. **「待重试」与「已转死信」分开显示**。前者还在自动重试队列里（别去手工重放），
 *    后者重试耗尽了（该人工处置）。
 * 3. **事件 ID 完整显示**，不截断 —— 它是接收方幂等去重的依据，运营要拿它去对账。
 * 4. **重投说明写清「发的是同一份报文」**，否则运营会以为重投会造成下游重复业务。
 * 5. **加载 / 空 / 错误三态齐全**：空态区分「还没有投递过」与「筛选无结果」。
 * 6. **能力声明里 `available=false` 的项按原样显示并写明归谁**，不显示成「暂无」。
 */

type Tab = 'deliveries' | 'dead-letters' | 'replay-tasks' | 'capabilities';

/** 语气 → Badge 的 tone。 */
const BADGE_TONE: Record<Tone, 'neutral' | 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
};

interface PublishForm {
  eventType: string;
  schemaVersion: string;
  eventId: string;
  payload: string;
}

const EMPTY_PUBLISH: PublishForm = {
  eventType: '',
  schemaVersion: '1',
  eventId: '',
  payload: '{\n  "sku": "SKU-1"\n}',
};

export function DeliveryRecordWorkspace() {
  const [tab, setTab] = useState<Tab>('deliveries');

  const [capabilities, setCapabilities] = useState<DeliveryRecordCapabilities | null>(null);
  const [overview, setOverview] = useState<DeliveryRecordOverview | null>(null);

  const [deliveries, setDeliveries] = useState<DeliveryRecordDetail['delivery'][]>([]);
  const [deliveryTotal, setDeliveryTotal] = useState(0);
  const [deliveryPage, setDeliveryPage] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');

  const [deadLetters, setDeadLetters] = useState<DeliveryRecordDeadLetterRow[]>([]);
  const [deadLetterTotal, setDeadLetterTotal] = useState(0);
  const [deadLetterPage, setDeadLetterPage] = useState(0);
  const [deadLetterStatus, setDeadLetterStatus] = useState('');

  const [replayTasks, setReplayTasks] = useState<DeliveryRecordReplayTaskRow[]>([]);
  const [replayTotal, setReplayTotal] = useState(0);
  const [replayPage, setReplayPage] = useState(0);

  const [detail, setDetail] = useState<DeliveryRecordDetail | null>(null);
  const [publishForm, setPublishForm] = useState<PublishForm>(EMPTY_PUBLISH);
  const [replayReason, setReplayReason] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const fail = (e: unknown) => {
    const message = e instanceof Error ? e.message : '请求失败，请稍后重试';
    setError(message);
  };

  const loadDeliveries = useCallback(async () => {
    const page = await fetchDeliveries({
      keyword,
      status,
      page: deliveryPage,
      size: 20,
    });
    setDeliveries(page.items);
    setDeliveryTotal(page.total);
  }, [keyword, status, deliveryPage]);

  const loadDeadLetters = useCallback(async () => {
    const page = await fetchDeadLetters({
      status: deadLetterStatus,
      page: deadLetterPage,
      size: 20,
    });
    setDeadLetters(page.items);
    setDeadLetterTotal(page.total);
  }, [deadLetterStatus, deadLetterPage]);

  const loadReplayTasks = useCallback(async () => {
    const page = await fetchReplayTasks({ page: replayPage, size: 20 });
    setReplayTasks(page.items);
    setReplayTotal(page.total);
  }, [replayPage]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, ov] = await Promise.all([fetchCapabilities(), fetchOverview()]);
      setCapabilities(caps);
      setOverview(ov);
      await Promise.all([loadDeliveries(), loadDeadLetters(), loadReplayTasks()]);
    } catch (e) {
      fail(e);
    } finally {
      setLoading(false);
    }
  }, [loadDeliveries, loadDeadLetters, loadReplayTasks]);

  useEffect(() => {
    void load();
  }, [load]);

  const run = async (work: () => Promise<unknown>, ok: string) => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await work();
      setNotice(ok);
      await load();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  // ------------------------------------------------------------ 发布

  const submitPublish = async () => {
    await run(async () => {
      await publishEvent({
        eventType: publishForm.eventType.trim(),
        schemaVersion: Number(publishForm.schemaVersion) || 1,
        eventId: publishForm.eventId.trim() ? publishForm.eventId.trim() : undefined,
        // 🔴 payload 是**一段 JSON 文本**（后端契约口径：JSON 字段一律 String）。
        payload: publishForm.payload,
      });
      setPublishForm(EMPTY_PUBLISH);
    }, '事件已发布，投递明细已落库。');
  };

  // ------------------------------------------------------------ 详情 / 重投

  const openDetail = async (id: string) => {
    setBusy(true);
    setError('');
    try {
      setDetail(await fetchDeliveryDetail(id));
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const doReplay = async (id: string) => {
    await run(async () => {
      await replayDelivery(id, { reason: replayReason.trim() });
      setReplayReason('');
      await openDetail(id);
    }, '已重投 —— 发出去的是同一份报文，事件 ID 不变。');
  };

  const doReplayDeadLetter = async (id: string) => {
    await run(async () => {
      await replayDeadLetter(id, { reason: replayReason.trim() });
      setReplayReason('');
    }, '死信已重放。');
  };

  const doSweep = async () => {
    await run(async () => {
      const sweep = await sweepRetryDue(50);
      setNotice(
        `扫了 ${sweep.scanned} 条到期投递，重投 ${sweep.retried} 次，送达 ${sweep.delivered} 条，转死信 ${sweep.deadLettered} 条。`,
      );
    }, '到期重试已执行。');
  };

  const hasFilter = Boolean(keyword || status);
  const delivery = deliveryCapability(capabilities);

  return (
    <div className="space-y-4">
      <PageHeader
        title="投递记录"
        description="记录事件投递明细：事件 ID、订阅方、投递状态、响应码、重试次数；提供手动重投与死信重放。"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            {loading ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <RefreshCw className="h-4 w-4" />
            )}
            刷新
          </Button>
        }
      />

      {overview && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard
            label="投递记录"
            value={overview.deliveryCount}
            unit="条"
            icon={<ListChecks className="h-4 w-4" />}
          />
          <StatCard label="已送达" value={overview.deliveredCount} unit="条" />
          <StatCard label="待重试" value={overview.pendingCount} unit="条" />
          <StatCard
            label="已转死信"
            value={overview.deadLetterCount}
            unit="条"
            icon={<TriangleAlert className="h-4 w-4" />}
          />
        </div>
      )}

      {error && (
        <div className="border-destructive rounded-md border p-3 text-sm">
          <div className="flex items-center gap-2 font-medium">
            <CircleAlert className="h-4 w-4" />
            出错了
          </div>
          <p className="text-muted mt-1 text-xs">{error}</p>
        </div>
      )}
      {notice && (
        <div className="border-success rounded-md border p-3 text-sm">
          <div className="flex items-center gap-2 font-medium">
            <ListChecks className="h-4 w-4" />
            {notice}
          </div>
        </div>
      )}

      <Segmented
        value={tab}
        onChange={(value) => setTab(value)}
        options={[
          { value: 'deliveries', label: '投递记录' },
          { value: 'dead-letters', label: '死信' },
          { value: 'replay-tasks', label: '重放任务' },
          { value: 'capabilities', label: '能力声明' },
        ]}
      />

      {tab === 'deliveries' && (
        <div className="space-y-4">
          <SectionCard
            title="投递记录"
            description="一行 = 一个「事件 × 订阅方」。响应码 0 表示根本没拿到响应（连接被拒 / 超时 / 没发出去）。"
          >
            <div className="space-y-3">
              <div className="flex flex-wrap items-end gap-2">
                <label className="space-y-1 text-sm">
                  <span className="text-muted">关键词</span>
                  <Input
                    value={keyword}
                    placeholder="事件 ID / 事件类型 / 订阅名 / 目标地址"
                    onChange={(e) => setKeyword(e.target.value)}
                  />
                </label>
                <label className="space-y-1 text-sm">
                  <span className="text-muted">状态</span>
                  <select
                    className="border-input h-9 rounded-md border px-2 text-sm"
                    value={status}
                    onChange={(e) => setStatus(e.target.value)}
                  >
                    <option value="">全部</option>
                    {DELIVERY_STATUS_OPTIONS.map((o) => (
                      <option key={o.value} value={o.value}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                </label>
                <Button variant="outline" onClick={() => void loadDeliveries()} disabled={loading}>
                  筛选
                </Button>
              </div>

              {loading ? (
                <div className="text-muted flex items-center gap-2 text-sm">
                  <Loader2 className="h-4 w-4 animate-spin" /> 读取中…
                </div>
              ) : deliveries.length === 0 ? (
                <EmptyState
                  icon={<Send className="h-6 w-6" />}
                  title={hasFilter ? '没有匹配的投递记录' : '还没有任何投递记录'}
                  description={
                    hasFilter
                      ? '换个关键词或把状态筛选清掉再试。'
                      : '发布一个事件，或等业务侧的事件流过来 —— 投递明细会在这里逐条落下来。'
                  }
                />
              ) : (
                <div className="space-y-2">
                  {deliveries.map((row) => (
                    <div
                      key={row.id}
                      className="flex flex-wrap items-start justify-between gap-2 rounded-md border p-3 text-sm"
                    >
                      <div className="space-y-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-medium">
                            {row.eventType} v{row.schemaVersion}
                          </span>
                          <Badge tone={BADGE_TONE[deliveryStatusTone(row.status)]}>
                            {row.statusLabel}
                          </Badge>
                          <Badge tone="neutral">HTTP {statusCodeLabel(row.statusCode)}</Badge>
                          {row.replayCount > 0 && (
                            <Badge tone="info">重投 {row.replayCount} 次</Badge>
                          )}
                        </div>
                        <div className="text-muted text-xs">
                          订阅方 {row.subscriptionName} · {row.targetUrl}
                        </div>
                        <div className="text-muted text-xs">
                          事件 ID {eventIdLabel(row.eventId)} · 尝试 {row.attemptCount} 次
                        </div>
                        <div className="text-muted text-xs">
                          {deliveryStatusHint({
                            status: row.status,
                            statusCode: row.statusCode,
                            nextRetryAt: row.nextRetryAt,
                            attemptCount: row.attemptCount,
                          })}
                        </div>
                        {row.lastError && (
                          <div className="text-destructive text-xs">
                            最近一次失败：{row.lastError}
                          </div>
                        )}
                      </div>
                      <div className="flex gap-1">
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy}
                          onClick={() => void openDetail(row.id)}
                        >
                          详情
                        </Button>
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy}
                          onClick={() => void doReplay(row.id)}
                        >
                          <RotateCcw className="h-4 w-4" />
                          重投
                        </Button>
                      </div>
                    </div>
                  ))}
                  <div className="text-muted flex items-center justify-between text-xs">
                    <span>
                      {paginationLabel({
                        page: deliveryPage,
                        size: 20,
                        total: deliveryTotal,
                      })}
                    </span>
                    <span className="flex gap-1">
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={deliveryPage === 0 || busy}
                        onClick={() => setDeliveryPage((p) => Math.max(p - 1, 0))}
                      >
                        上一页
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={(deliveryPage + 1) * 20 >= deliveryTotal || busy}
                        onClick={() => setDeliveryPage((p) => p + 1)}
                      >
                        下一页
                      </Button>
                    </span>
                  </div>
                </div>
              )}
            </div>
          </SectionCard>

          <SectionCard
            title="发布一个事件"
            description="真的投出去：按订阅匹配、签名、POST 到订阅的 target_url，并把投递明细落库。事件 ID 留空则由平台生成。"
          >
            <div className="grid gap-3 md:grid-cols-2">
              <label className="space-y-1 text-sm">
                <span className="text-muted">事件类型</span>
                <Input
                  value={publishForm.eventType}
                  placeholder="SkuProfileSaved"
                  onChange={(e) => setPublishForm({ ...publishForm, eventType: e.target.value })}
                />
              </label>
              <label className="space-y-1 text-sm">
                <span className="text-muted">schema 版本</span>
                <Input
                  value={publishForm.schemaVersion}
                  placeholder="1"
                  onChange={(e) =>
                    setPublishForm({ ...publishForm, schemaVersion: e.target.value })
                  }
                />
              </label>
              <label className="space-y-1 text-sm md:col-span-2">
                <span className="text-muted">事件 ID（可选）</span>
                <Input
                  value={publishForm.eventId}
                  placeholder="留空 = 平台生成；填了则同一个 ID 重复发布是幂等的（不会重复发包）"
                  onChange={(e) => setPublishForm({ ...publishForm, eventId: e.target.value })}
                />
              </label>
              <label className="space-y-1 text-sm md:col-span-2">
                <span className="text-muted">事件载荷（JSON 文本）</span>
                <textarea
                  className="border-input min-h-24 w-full rounded-md border p-2 font-mono text-xs"
                  value={publishForm.payload}
                  onChange={(e) => setPublishForm({ ...publishForm, payload: e.target.value })}
                />
              </label>
            </div>
            <div className="mt-3">
              <Button
                onClick={() => void submitPublish()}
                disabled={busy || !publishForm.eventType.trim()}
              >
                <Send className="h-4 w-4" />
                发布并投递
              </Button>
            </div>
          </SectionCard>

          {detail && (
            <SectionCard
              title="投递详情"
              description={`事件 ${detail.delivery.eventType} · ${detail.delivery.subscriptionName}`}
            >
              <div className="space-y-3 text-sm">
                <div className="text-muted text-xs">
                  事件 ID {eventIdLabel(detail.delivery.eventId)} · 目标 {detail.delivery.targetUrl}{' '}
                  · 尝试 {detail.delivery.attemptCount} 次 · 响应{' '}
                  {statusCodeLabel(detail.delivery.statusCode)}
                </div>

                <div>
                  <div className="mb-1 font-medium">投递日志（每一次尝试一行）</div>
                  {detail.attempts.length === 0 ? (
                    <div className="text-muted text-xs">还没有任何尝试记录。</div>
                  ) : (
                    <div className="space-y-1">
                      {detail.attempts.map((a) => (
                        <div
                          key={a.id}
                          className="flex flex-wrap items-center gap-2 rounded-md border p-2 text-xs"
                        >
                          <span className="font-medium">第 {a.attemptNo} 次</span>
                          <Badge tone={BADGE_TONE[a.success ? 'ok' : 'danger']}>
                            HTTP {statusCodeLabel(a.statusCode)}
                          </Badge>
                          <Badge tone="neutral">{attemptSourceLabel(a.attemptSource)}</Badge>
                          <span className="text-muted">{a.durationMs} ms</span>
                          <span className="text-muted">{a.occurredAt}</span>
                          {a.error && <span className="text-destructive">{a.error}</span>}
                          {a.responseSnippet && (
                            <span className="text-muted truncate">响应：{a.responseSnippet}</span>
                          )}
                        </div>
                      ))}
                    </div>
                  )}
                </div>

                {detail.deadLetter && (
                  <div>
                    <div className="mb-1 font-medium">死信</div>
                    <div className="text-muted text-xs">
                      原因 {detail.deadLetter.reason} · 第 {detail.deadLetter.failedAttemptNo}{' '}
                      次失败 · 保留至 {detail.deadLetter.expiresAt}
                    </div>
                  </div>
                )}

                {detail.replayTasks.length > 0 && (
                  <div>
                    <div className="mb-1 font-medium">重放任务</div>
                    <div className="space-y-1">
                      {detail.replayTasks.map((t) => (
                        <div
                          key={t.id}
                          className="flex flex-wrap items-center gap-2 rounded-md border p-2 text-xs"
                        >
                          <span className="font-medium">{replayTriggerLabel(t.triggerType)}</span>
                          <Badge tone="neutral">{t.statusLabel}</Badge>
                          <span className="text-muted">
                            HTTP {statusCodeLabel(t.resultStatusCode)}
                          </span>
                          <span className="text-muted">{t.createdAt}</span>
                          {t.reason && <span className="text-muted">{t.reason}</span>}
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                <div className="flex flex-wrap items-end gap-2">
                  <label className="space-y-1 text-sm">
                    <span className="text-muted">重投备注</span>
                    <Input
                      value={replayReason}
                      placeholder="为什么重投（会记在重放任务里）"
                      onChange={(e) => setReplayReason(e.target.value)}
                    />
                  </label>
                  <Button
                    variant="outline"
                    disabled={busy}
                    onClick={() => void doReplay(detail.delivery.id)}
                  >
                    <RotateCcw className="h-4 w-4" />
                    重投这一条
                  </Button>
                </div>
                <p className="text-muted text-xs">{replayNotice()}</p>
              </div>
            </SectionCard>
          )}
        </div>
      )}

      {tab === 'dead-letters' && (
        <SectionCard
          title="死信"
          description="重试次数用尽的投递。重放它发出去的仍然是同一份报文（事件 ID 不变）。"
        >
          <div className="space-y-3">
            <div className="flex flex-wrap items-end gap-2">
              <label className="space-y-1 text-sm">
                <span className="text-muted">状态</span>
                <select
                  className="border-input h-9 rounded-md border px-2 text-sm"
                  value={deadLetterStatus}
                  onChange={(e) => setDeadLetterStatus(e.target.value)}
                >
                  <option value="">全部</option>
                  {DEAD_LETTER_STATUS_OPTIONS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </label>
              <Button variant="outline" onClick={() => void loadDeadLetters()} disabled={loading}>
                筛选
              </Button>
              <Button variant="outline" onClick={() => void doSweep()} disabled={busy}>
                扫一轮到期重试
              </Button>
            </div>

            {loading ? (
              <div className="text-muted flex items-center gap-2 text-sm">
                <Loader2 className="h-4 w-4 animate-spin" /> 读取中…
              </div>
            ) : deadLetters.length === 0 ? (
              <EmptyState
                icon={<TriangleAlert className="h-6 w-6" />}
                title={deadLetterStatus ? '没有匹配的死信' : '死信箱是空的'}
                description={
                  deadLetterStatus
                    ? '换个状态筛选再试。'
                    : '所有投递都在重试预算之内 —— 只有重试次数用尽才会进死信箱。'
                }
              />
            ) : (
              <div className="space-y-2">
                {deadLetters.map((row) => (
                  <div
                    key={row.id}
                    className="flex flex-wrap items-start justify-between gap-2 rounded-md border p-3 text-sm"
                  >
                    <div className="space-y-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium">{row.eventType}</span>
                        <Badge tone={BADGE_TONE[deadLetterStatusTone(row.status)]}>
                          {row.statusLabel}
                        </Badge>
                      </div>
                      <div className="text-muted text-xs">
                        订阅方 {row.subscriptionName} · {row.targetUrl}
                      </div>
                      <div className="text-muted text-xs">
                        事件 ID {eventIdLabel(row.eventId)} · 第 {row.failedAttemptNo} 次失败 ·
                        保留至 {row.expiresAt}
                      </div>
                      <div className="text-muted text-xs">原因：{row.reason}</div>
                    </div>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={busy || row.status !== 'PENDING'}
                      onClick={() => void doReplayDeadLetter(row.id)}
                    >
                      <RotateCcw className="h-4 w-4" />
                      重放
                    </Button>
                  </div>
                ))}
                <div className="text-muted flex items-center justify-between text-xs">
                  <span>
                    {paginationLabel({
                      page: deadLetterPage,
                      size: 20,
                      total: deadLetterTotal,
                    })}
                  </span>
                  <span className="flex gap-1">
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={deadLetterPage === 0 || busy}
                      onClick={() => setDeadLetterPage((p) => Math.max(p - 1, 0))}
                    >
                      上一页
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={(deadLetterPage + 1) * 20 >= deadLetterTotal || busy}
                      onClick={() => setDeadLetterPage((p) => p + 1)}
                    >
                      下一页
                    </Button>
                  </span>
                </div>
              </div>
            )}
          </div>
        </SectionCard>
      )}

      {tab === 'replay-tasks' && (
        <SectionCard title="重放任务" description="每一次人工重放（手动重投 / 死信重放）都留一条。">
          {loading ? (
            <div className="text-muted flex items-center gap-2 text-sm">
              <Loader2 className="h-4 w-4 animate-spin" /> 读取中…
            </div>
          ) : replayTasks.length === 0 ? (
            <EmptyState
              icon={<ScrollText className="h-6 w-6" />}
              title="还没有重放任务"
              description="人工重投或死信重放之后，这里会逐条记下每次重放的来源与结果。"
            />
          ) : (
            <div className="space-y-2">
              {replayTasks.map((row) => (
                <div key={row.id} className="rounded-md border p-3 text-sm">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{row.eventType}</span>
                    <Badge tone="neutral">{replayTriggerLabel(row.triggerType)}</Badge>
                    <Badge tone={BADGE_TONE[row.status === 'SUCCEEDED' ? 'ok' : 'danger']}>
                      {row.statusLabel}
                    </Badge>
                    <Badge tone="neutral">HTTP {statusCodeLabel(row.resultStatusCode)}</Badge>
                  </div>
                  <div className="text-muted mt-1 text-xs">
                    订阅方 {row.subscriptionName} · 事件 ID {eventIdLabel(row.eventId)} ·{' '}
                    {row.createdAt}
                  </div>
                  {row.resultMessage && (
                    <div className="text-muted text-xs">结果：{row.resultMessage}</div>
                  )}
                  {row.reason && <div className="text-muted text-xs">备注：{row.reason}</div>}
                </div>
              ))}
              <div className="text-muted flex items-center justify-between text-xs">
                <span>
                  {paginationLabel({
                    page: replayPage,
                    size: 20,
                    total: replayTotal,
                  })}
                </span>
                <span className="flex gap-1">
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={replayPage === 0 || busy}
                    onClick={() => setReplayPage((p) => Math.max(p - 1, 0))}
                  >
                    上一页
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={(replayPage + 1) * 20 >= replayTotal || busy}
                    onClick={() => setReplayPage((p) => p + 1)}
                  >
                    下一页
                  </Button>
                </span>
              </div>
            </div>
          )}
        </SectionCard>
      )}

      {tab === 'capabilities' && (
        <div className="space-y-4">
          <SectionCard title="能力声明" description="哪些真接通、依据是什么、缺了归谁。">
            {loading ? (
              <div className="text-muted flex items-center gap-2 text-sm">
                <Loader2 className="h-4 w-4 animate-spin" /> 读取中…
              </div>
            ) : (capabilities?.capabilities ?? []).length === 0 ? (
              <div className="text-muted text-sm">后端没有声明能力清单。</div>
            ) : (
              <div className="space-y-2">
                {(capabilities?.capabilities ?? []).map((c) => (
                  <div
                    key={`${c.module}-${c.name}`}
                    className="flex flex-wrap items-start justify-between gap-2 rounded-md border p-3 text-sm"
                  >
                    <div className="space-y-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium">{c.name}</span>
                        <Badge tone={BADGE_TONE[capabilityTone(c.available)]}>
                          {c.available ? '已具备' : '未接入'}
                        </Badge>
                        {c.ownerModule && <Badge tone="info">归 {c.ownerModule}</Badge>}
                      </div>
                      <div className="text-muted text-xs">依据：{c.basis}</div>
                      {c.impact && <div className="text-muted text-xs">影响：{c.impact}</div>}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          <SectionCard title="下游缺口" description="谁没交付、归谁。">
            {(capabilities?.downstreamGaps ?? []).length === 0 ? (
              <div className="text-muted text-sm">没有登记下游缺口。</div>
            ) : (
              <div className="space-y-2">
                {(capabilities?.downstreamGaps ?? []).map((g) => (
                  <div key={`${g.module}-${g.name}`} className="rounded-md border p-3 text-sm">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-medium">{g.name}</span>
                      <Badge tone={BADGE_TONE[capabilityTone(g.available)]}>
                        {g.available ? '已具备' : '未交付'}
                      </Badge>
                      <Badge tone="info">{g.module}</Badge>
                    </div>
                    <div className="text-muted mt-1 text-xs">{g.detail}</div>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          {delivery && !delivery.available && (
            <div className="border-warning rounded-md border p-3 text-sm">
              <div className="flex items-center gap-2 font-medium">
                <FileKey2 className="h-4 w-4" />
                事件实际投递未接入
              </div>
              <p className="text-muted mt-1 text-xs">{unavailableHint(delivery)}</p>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
