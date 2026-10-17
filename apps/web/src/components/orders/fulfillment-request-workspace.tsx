'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, ArrowRight, Boxes, RefreshCw, Route, ShieldAlert } from 'lucide-react';
import {
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  FulfillmentRequestCapabilities,
  FulfillmentRequestDetail,
  FulfillmentRequestPage,
  FulfillmentRequestRow,
  FulfillmentRequestTimeoutPage,
} from '../../../../../packages/api-contracts/src/index';
import {
  STEP_LADDER,
  STEP_LABELS,
  type FulfillmentRequestBlockInput,
  type FulfillmentRequestListParams,
  advanceRequest,
  blockRequest,
  cancelRequest,
  completeRequest,
  errorText,
  eventSummary,
  fetchCapabilities,
  fetchRequest,
  fetchRequests,
  fetchResumable,
  fetchTimeouts,
  isVersionConflict,
  outboundSummary,
  reachedStep,
  requestOutboundOrder,
  requestReservation,
  reservationSummary,
  retryRequest,
  scanTimeouts,
  timeoutSummary,
} from './fulfillment-request-model';

const PAGE_SIZE = 20;

/**
 * 每一步的超时阈值（秒），与后端 FulfillmentStepKind.defaultTimeoutSeconds() 同源。
 *
 * <p>显式带上而不是让后端取缺省值：租户可以在建单时改阈值，而推进时带的是
 * **本次**沿用的口径，写在事件流里才答得出「这一单当初按几小时判超时」。
 */
const STEP_TIMEOUT_SECONDS: Record<FulfillmentStepLiteral, number> = {
  RESERVE: 3600,
  OUTBOUND: 7200,
  WORK: 14400,
  TRACK: 86400,
};

/**
 * 9.3.3.1 履约请求工作区。
 *
 * <p>蓝图：「编排一次履约：锁定预占 → 生成出库单 → 编排作业 → 跟踪完成；
 * 持久化流程状态，支持断点恢复与超时重试。」
 *
 * <p><b>区块顺序对应「先说清这模块不做什么，再说我做到哪」</b>：
 *
 * <ol>
 *   <li><b>能力与下游边界</b>：本模块**只在本服务内登记下游指令**，
 *       7.2 预占 / 8.3.1 出库单 / 10 物流三个都<b>没接通</b>。这条必须排在最前面 ——
 *       界面若让人以为「已锁定预占」等于货锁在 warehouse 那边了，那两边会各锁一批货
 *       （9.3.2.1 / V116 已经踩过并写进证据的坑）。</li>
 *   <li><b>履约请求看板</b>：四步进度 + 当前步 + 是否超时 + 版本号。
 *       版本号显示出来，是因为推进必须带它；冲突时界面要能「重新读一遍再推」，而不是一句红字。</li>
 *   <li><b>断点恢复</b>：挂起的流程单独一栏。重试必须写理由 ——
 *       蓝图说「超时不是失败，先查证再决定重试还是释放预占」。</li>
 *   <li><b>超时检出记录</b>：9.3.3.2 的数据来源，逐条列出卡在哪一步、卡了多久、为什么。</li>
 * </ol>
 *
 * <p>加载态 / 空态 / 错误态三态齐全；版本冲突（409）单独处理，不混进通用错误提示。
 */
export function FulfillmentRequestWorkspace() {
  const [capabilities, setCapabilities] = useState<FulfillmentRequestCapabilities | null>(null);
  const [page, setPage] = useState<FulfillmentRequestPage | null>(null);
  const [resumable, setResumable] = useState<FulfillmentRequestRow[]>([]);
  const [timeouts, setTimeouts] = useState<FulfillmentRequestTimeoutPage | null>(null);
  const [detail, setDetail] = useState<FulfillmentRequestDetail | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const [filters, setFilters] = useState<FulfillmentRequestListParams>({ page: 0, size: PAGE_SIZE });
  const [loading, setLoading] = useState(true);
  const [listError, setListError] = useState('');
  const [detailError, setDetailError] = useState('');
  const [notice, setNotice] = useState('');
  const [conflict, setConflict] = useState('');
  const [busy, setBusy] = useState(false);
  const [retryReason, setRetryReason] = useState('');
  const [scanStep, setScanStep] = useState<FulfillmentStepLiteral>('RESERVE');

  const reload = useCallback(async () => {
    setLoading(true);
    setListError('');
    try {
      const [caps, list, resumablePage, timeoutPage] = await Promise.all([
        fetchCapabilities(),
        fetchRequests({ ...filters, size: PAGE_SIZE }),
        fetchResumable({ page: 0, size: PAGE_SIZE }),
        fetchTimeouts({ page: 0, size: PAGE_SIZE }),
      ]);
      setCapabilities(caps);
      setPage(list);
      setResumable(resumablePage.items);
      setTimeouts(timeoutPage);
    } catch (e) {
      setListError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [filters]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const openDetail = useCallback(async (requestId: string) => {
    setSelectedId(requestId);
    setDetailError('');
    setConflict('');
    setNotice('');
    try {
      setDetail(await fetchRequest(requestId));
    } catch (e) {
      setDetail(null);
      setDetailError(errorText(e));
    }
  }, []);

  /** 所有写操作共用：统一处理 409 版本冲突（重新读，而不是一句红字）。 */
  const act = useCallback(
    async (label: string, run: () => Promise<string>) => {
      setBusy(true);
      setNotice('');
      setConflict('');
      try {
        setNotice(await run());
        if (selectedId) await openDetail(selectedId);
        await reload();
      } catch (e) {
        if (isVersionConflict(e)) {
          setConflict(
            `${label}失败：这票流程已被别人推进过（版本冲突）。下面已重新读取最新状态，请确认后再操作。`,
          );
          if (selectedId) await openDetail(selectedId);
        } else {
          setNotice('');
          setDetailError(errorText(e));
        }
      } finally {
        setBusy(false);
      }
    },
    [openDetail, reload, selectedId],
  );

  const stats = useMemo(() => {
    const items = page?.items ?? [];
    return {
      total: page?.total ?? 0,
      blocked: items.filter((r) => r.status === 'BLOCKED').length,
      overdue: items.filter((r) => r.overdue).length,
      resumable: resumable.length,
    };
  }, [page, resumable]);

  return (
    <div className="space-y-6">
      <PageHeader
        title="履约请求"
        description="编排一次履约：锁定预占 → 生成出库单 → 编排作业 → 跟踪完成；流程状态持久化，支持断点恢复与超时重试。"
      />

      {notice && (
        <Notice tone="success" title="操作完成">
          {notice}
        </Notice>
      )}
      {conflict && (
        <Notice tone="warning" title="版本冲突（可恢复）">
          {conflict}
        </Notice>
      )}
      {listError && (
        <Notice tone="danger" title="加载失败">
          {listError}
          <Button variant="ghost" onClick={() => void reload()} className="ml-3">
            重试
          </Button>
        </Notice>
      )}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="履约请求" value={stats.total} unit="条" />
        <StatCard label="已挂起（��页）" value={stats.blocked} unit="条" polarity="negative" />
        <StatCard label="已超时（本页）" value={stats.overdue} unit="条" polarity="negative" />
        <StatCard label="可恢复" value={stats.resumable} unit="条" polarity="positive" />
      </div>

      {/* 区块 1：下游边界 —— 必须排在最前面 */}
      <SectionCard
        title="下游边界（先说清这模块没做什么）"
        extra={<ShieldAlert className="h-4 w-4 text-amber-600" />}
      >
        {capabilities ? (
          <div className="space-y-3 text-sm">
            <Notice tone="warning" title="本模块不预占上游库存">
              本模块**只在本服务内登记下游指令**，不写 warehouse 库存、不派单、不下运单。
              预占与出库单的缺省状态都是「待回执」，没有下游回执就不算锁定、不算出库。
            </Notice>
            <div className="grid gap-2 sm:grid-cols-2">
              <BoundaryRow
                label="是否真的锁了上游库存"
                value={capabilities.reservesUpstreamStock}
                noText="否（只登记指令，等 warehouse 回执）"
              />
              <BoundaryRow
                label="是否真的派了出库单"
                value={capabilities.outboundOrderDispatched}
                noText="否（只登记指令，等 8.3.1 回执）"
              />
              <BoundaryRow
                label="是否真的下了运单"
                value={capabilities.logisticsOrderPlaced}
                noText="否（运单主权在 9.4.1.1 / 8.3.4.2）"
              />
              <BoundaryRow
                label="流程状态是否持久化"
                value={capabilities.processStatePersisted}
                noText="否"
                yesText="是（落在 PostgreSQL，重启后可继续）"
              />
            </div>
            <details className="text-xs text-muted-foreground">
              <summary className="cursor-pointer">上下游逐项可用性</summary>
              <ul className="mt-2 space-y-1">
                {capabilities.dimensions.map((d) => (
                  <li key={d.key}>
                    <span className={d.available ? 'text-emerald-600' : 'text-amber-600'}>
                      {d.available ? '已接通' : '未接通'}
                    </span>{' '}
                    <strong>{d.label}</strong> · {d.ownerModule}
                    {d.reason ? ` —— ${d.reason}` : ''}
                  </li>
                ))}
              </ul>
            </details>
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">{loading ? '加载中…' : '暂无数据'}</p>
        )}
      </SectionCard>

      {/* 区块 2：履约请求看板 */}
      <SectionCard
        title="履约请求"
        extra={
          <Button variant="ghost" onClick={() => void reload()} disabled={loading}>
            <RefreshCw className="mr-1 h-4 w-4" />
            刷新
          </Button>
        }
      >
        <div className="mb-3 grid gap-3 sm:grid-cols-4">
          <FormField id="fr-keyword" label="请求号 / 订单号 / SKU">
            <Input
              value={filters.keyword ?? ''}
              onChange={(e) => setFilters({ ...filters, keyword: e.target.value, page: 0 })}
              placeholder="模糊匹配"
            />
          </FormField>
          <FormField id="fr-warehouse" label="发货仓">
            <Input
              value={filters.warehouseRef ?? ''}
              onChange={(e) => setFilters({ ...filters, warehouseRef: e.target.value, page: 0 })}
              placeholder="如 WH-CN-SH"
            />
          </FormField>
          <FormField id="fr-status" label="状态">
            <Input
              value={filters.status ?? ''}
              onChange={(e) => setFilters({ ...filters, status: e.target.value, page: 0 })}
              placeholder="如 BLOCKED"
            />
          </FormField>
          <FormField id="fr-overdue" label="只看">
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={Boolean(filters.overdueOnly)}
                onChange={(e) =>
                  setFilters({ ...filters, overdueOnly: e.target.checked, page: 0 })
                }
              />
              已超时
            </label>
          </FormField>
        </div>

        {loading ? (
          <p className="text-sm text-muted-foreground">加载中…</p>
        ) : !page || page.items.length === 0 ? (
          <EmptyState
            title="还没有履约请求"
            description="履约请求来自上游的拆合单与分仓结果（9.3.1 / 9.3.2）。上游产出履约单元后，这里会出现待编排的请求。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="border-b text-xs text-muted-foreground">
                <tr>
                  <th className="py-2 pr-3">请求号</th>
                  <th className="py-2 pr-3">进度</th>
                  <th className="py-2 pr-3">当前步 / 状态</th>
                  <th className="py-2 pr-3">发货仓</th>
                  <th className="py-2 pr-3">数量</th>
                  <th className="py-2 pr-3">版本</th>
                  <th className="py-2 pr-3">超时</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((row) => (
                  <tr
                    key={row.id}
                    className="cursor-pointer border-b last:border-0 hover:bg-muted/50"
                    onClick={() => void openDetail(row.id)}
                  >
                    <td className="py-2 pr-3 font-mono text-xs">{row.requestCode}</td>
                    <td className="py-2 pr-3">
                      <StepMeter reached={reachedStep(row, detail?.steps ?? [])} />
                    </td>
                    <td className="py-2 pr-3">
                      {row.currentStepLabel} · {row.statusLabel}
                      {row.status === 'BLOCKED' && row.blockedReason && (
                        <div className="text-xs text-amber-700">{row.blockedReason}</div>
                      )}
                    </td>
                    <td className="py-2 pr-3">{row.warehouseRef}</td>
                    <td className="py-2 pr-3">{row.requestedQty}</td>
                    <td className="py-2 pr-3 font-mono text-xs">v{row.processVersion}</td>
                    <td className="py-2 pr-3">
                      {row.overdue ? (
                        <span className="text-amber-700">已超时</span>
                      ) : (
                        <span className="text-muted-foreground">—</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* 区块 3：断点恢复 */}
      <SectionCard title="断点恢复" extra={<Route className="h-4 w-4" />}>
        {resumable.length === 0 ? (
          <EmptyState title="没有卡住的流程" description="所有履约请求都在正常推进中。" />
        ) : (
          <>
            <Notice tone="warning" title="先查证再决定">
              重试会从**卡住的那一步**继续（不是从头再来），且必须写明理由 ——
              蓝图说「超时不是失败，先查证再决定重试还是释放预占」。
            </Notice>
            <div className="space-y-3">
              {resumable.map((row) => (
                <div key={row.id} className="rounded border p-3 text-sm">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-mono text-xs">{row.requestCode}</span>
                    <span>{row.currentStepLabel} 卡住</span>
                    <span className="text-xs text-muted-foreground">
                      第 {row.attempt} 次尝试 · v{row.processVersion}
                    </span>
                    <Button variant="ghost" onClick={() => void openDetail(row.id)}>
                      看详情
                    </Button>
                  </div>
                  <p className="mt-1 text-xs text-muted-foreground">
                    {row.blockedReasonCodeLabel}：{row.blockedReason || '未写明'}
                  </p>
                  <div className="mt-2 flex gap-2">
                    <Input
                      placeholder="重试理由（必填）：如 已电话确认下游在处理"
                      value={retryReason}
                      onChange={(e) => setRetryReason(e.target.value)}
                    />
                    <Button
                      disabled={
                        busy ||
                        retryReason.trim().length === 0 ||
                        row.id !== selectedId
                      }
                      onClick={() =>
                        void act('重试', async () => {
                          const result = await retryRequest(row.id, {
                            expectedVersion: row.processVersion,
                            resumeReason: retryReason.trim(),
                          });
                          setRetryReason('');
                          return `已从断点「${row.currentStepLabel}」重试（第 ${result.attempt} 次尝试，版本 ${result.processVersion}）。`;
                        })
                      }
                    >
                      从断点重试
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          </>
        )}
      </SectionCard>

      {/* 区块 4：超时检出记录（9.3.3.2 的数据来源） */}
      <SectionCard
        title="超时与失败检出记录"
        extra={
          <div className="flex items-center gap-2">
            <select
              aria-label="扫描哪一步"
              className="rounded-md border bg-background px-2 py-1 text-sm"
              value={scanStep}
              onChange={(e) => setScanStep(e.target.value as FulfillmentStepLiteral)}
            >
              {STEP_LADDER.map((step) => (
                <option key={step} value={step}>
                  {STEP_LABELS[step]}
                </option>
              ))}
            </select>
            <Button
              variant="secondary"
              disabled={busy}
              onClick={() =>
                void act('扫描超时', async () => {
                  const result = await scanTimeouts({ stepKind: scanStep, limit: 100 });
                  return `扫描「${STEP_LABELS[scanStep]}」步 ${result.scanned} 条，`
                    + `挂起 ${result.blocked.length} 条，跳过 ${result.skipped.length} 条。`;
                })
              }
            >
              扫描超时
            </Button>
          </div>
        }
      >
        {!timeouts || timeouts.items.length === 0 ? (
          <EmptyState title="没有超时记录" description="扫描已超时的流程会在这里留痕。" />
        ) : (
          <ul className="space-y-2 text-sm">
            {timeouts.items.map((row) => (
              <li key={row.id} className="rounded border p-2">
                <div className="flex items-center gap-2">
                  <span className="font-mono text-xs">{row.requestId?.slice(0, 8)}</span>
                  <span className="text-xs text-muted-foreground">
                    第 {row.attempt} 次尝试 · {row.detectionKindLabel}
                  </span>
                </div>
                <p className="mt-1 text-xs">{timeoutSummary(row)}</p>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* 详情抽屉 */}
      {selectedId && (
        <SectionCard
          title={`履约请求详情 ${detail?.request.requestCode ?? selectedId}`}
          extra={<ArrowRight className="h-4 w-4" />}
        >
          {detailError && (
            <Notice tone="danger" title="操作失败">
              {detailError}
            </Notice>
          )}
          {!detail ? (
            <p className="text-sm text-muted-foreground">加载中…</p>
          ) : (
            <div className="space-y-4 text-sm">
              <div className="grid gap-2 sm:grid-cols-4">
                <Field label="状态" value={`${detail.request.currentStepLabel} · ${detail.request.statusLabel}`} />
                <Field label="版本（推进时必须带上）" value={`v${detail.request.processVersion}`} />
                <Field label="第几次尝试" value={String(detail.request.attempt)} />
                <Field label="当前步截止" value={iso(detail.request.deadlineAt)} />
              </div>

              <StepActions detail={detail} busy={busy} act={act} />

              <div>
                <h4 className="mb-1 text-xs font-semibold text-muted-foreground">流程状态记录</h4>
                <ul className="space-y-1">
                  {detail.steps.map((s) => (
                    <li key={s.id} className="text-xs">
                      {STEP_LABELS[s.stepKind] ?? s.stepKind} · 第 {s.attempt} 次 · {s.statusLabel}
                      {s.finishedAt ? ` · ${iso(s.finishedAt)}` : ''}
                    </li>
                  ))}
                  {detail.steps.length === 0 && <li className="text-xs text-muted-foreground">尚无</li>}
                </ul>
              </div>

              <div>
                <h4 className="mb-1 text-xs font-semibold text-muted-foreground">
                  锁定预占指令（下游 7.2 · 本模块未锁任何库存）
                </h4>
                <ul className="space-y-1">
                  {detail.reservations.map((r) => (
                    <li key={r.id} className="text-xs">
                      第 {r.attempt} 次：{reservationSummary(r)}
                    </li>
                  ))}
                  {detail.reservations.length === 0 && (
                    <li className="text-xs text-muted-foreground">尚未发出预占指令</li>
                  )}
                </ul>
              </div>

              <div>
                <h4 className="mb-1 text-xs font-semibold text-muted-foreground">
                  出库单指令（下游 8.3.1 · 本模块未派单）
                </h4>
                <ul className="space-y-1">
                  {detail.outboundOrders.map((r) => (
                    <li key={r.id} className="text-xs">
                      第 {r.attempt} 次：{outboundSummary(r)}
                    </li>
                  ))}
                  {detail.outboundOrders.length === 0 && (
                    <li className="text-xs text-muted-foreground">尚未发出库单指令</li>
                  )}
                </ul>
              </div>

              <div>
                <h4 className="mb-1 text-xs font-semibold text-muted-foreground">编排作业</h4>
                <ul className="space-y-1">
                  {detail.workOrders.map((w) => (
                    <li key={w.id} className="text-xs">
                      {w.workOrderCode} · {w.taskTypeLabel} · {w.statusLabel} · {w.qty}
                      {w.failureReason ? ` · ${w.failureReason}` : ''}
                    </li>
                  ))}
                  {detail.workOrders.length === 0 && (
                    <li className="text-xs text-muted-foreground">尚未编排作业</li>
                  )}
                </ul>
              </div>

              <div>
                <h4 className="mb-1 text-xs font-semibold text-muted-foreground">事件流（append-only）</h4>
                <ul className="space-y-1">
                  {detail.events.map((e) => (
                    <li key={e.id} className="text-xs">
                      v{e.processVersion} · {eventSummary(e)}
                    </li>
                  ))}
                </ul>
              </div>
            </div>
          )}
        </SectionCard>
      )}
    </div>
  );
}

function StepActions({
  detail,
  busy,
  act,
}: {
  detail: FulfillmentRequestDetail;
  busy: boolean;
  act: (label: string, run: () => Promise<string>) => Promise<void>;
}) {
  const row = detail.request;
  const version = row.processVersion;
  const next = nextAction(row);

  return (
    <div className="flex flex-wrap gap-2">
      {next && (
        <Button
          disabled={busy}
          onClick={() =>
            void act('推进', async () => {
              const result = await advanceRequest(row.id, {
                status: next.to,
                currentStep: next.step,
                expectedVersion: version,
                stepTimeoutSeconds: STEP_TIMEOUT_SECONDS[next.step],
              });
              return `已推进到「${result.status}」（版本 ${result.processVersion}）。`;
            })
          }
        >
          推进到「{next.label}」
        </Button>
      )}
      {row.status === 'RESERVING' && (
        <Button
          disabled={busy}
          onClick={() =>
            void act('发出预占指令', async () => {
              await requestReservation(row.id, { idempotencyKey: '' });
              return '已向 7.2 登记预占指令（待回执，尚未锁定任何库存）。';
            })
          }
        >
          发出预占指令
        </Button>
      )}
      {row.status === 'OUTBOUND_PENDING' && (
        <Button
          disabled={busy}
          onClick={() =>
            void act('发出库单指令', async () => {
              await requestOutboundOrder(row.id, { idempotencyKey: '', packageRef: '' });
              return '已向 8.3.1 登记出库单指令（待回执，尚未派单）。';
            })
          }
        >
          发出库单指令
        </Button>
      )}
      <BlockButton detail={detail} busy={busy} act={act} />
      {row.status === 'WORKING' && (
        <Button
          disabled={busy}
          onClick={() =>
            void act('跟踪完成', async () => {
              const result = await completeRequest(row.id, {
                expectedVersion: version,
                resumeReason: '全部收尾',
              });
              return `已跟踪完成（版本 ${result.processVersion}）。`;
            })
          }
        >
          登记跟踪完成
        </Button>
      )}
      {row.status !== 'COMPLETED' && row.status !== 'CANCELLED' && (
        <CancelButton detail={detail} busy={busy} act={act} />
      )}
    </div>
  );
}

function BlockButton({
  detail,
  busy,
  act,
}: {
  detail: FulfillmentRequestDetail;
  busy: boolean;
  act: (label: string, run: () => Promise<string>) => Promise<void>;
}) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  if (detail.request.status === 'COMPLETED' || detail.request.status === 'CANCELLED') return null;
  if (!open) {
    return (
      <Button variant="secondary" disabled={busy} onClick={() => setOpen(true)}>
        挂起（超时 / 失败）
      </Button>
    );
  }
  return (
    <div className="w-full space-y-2 rounded border p-3">
      <textarea
        aria-label="挂起原因"
        className="w-full rounded-md border bg-background px-3 py-2 text-sm min-h-20"
        placeholder="挂起原因（必填）：如 warehouse 回执缺货"
        value={reason}
        onChange={(e) => setReason(e.target.value)}
      />
      <div className="flex gap-2">
        <Button
          disabled={busy || reason.trim().length === 0}
          onClick={() =>
            void act('挂起', async () => {
              const input: FulfillmentRequestBlockInput = {
                stepKind: detail.request.currentStep,
                detectionKind: 'STEP_FAILED',
                reasonCode: reasonCodeFor(detail.request.currentStep),
                reason: reason.trim(),
                expectedVersion: detail.request.processVersion,
              };
              const result = await blockRequest(detail.request.id, input);
              setOpen(false);
              setReason('');
              return `已挂起在「${detail.request.currentStepLabel}」步（版本 ${result.processVersion}）。`;
            })
          }
        >
          确认挂起
        </Button>
        <Button variant="ghost" onClick={() => setOpen(false)}>
          取消
        </Button>
      </div>
    </div>
  );
}

function CancelButton({
  detail,
  busy,
  act,
}: {
  detail: FulfillmentRequestDetail;
  busy: boolean;
  act: (label: string, run: () => Promise<string>) => Promise<void>;
}) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  if (!open) {
    return (
      <Button variant="ghost" disabled={busy} onClick={() => setOpen(true)}>
        取消履约请求
      </Button>
    );
  }
  return (
    <div className="w-full space-y-2 rounded border border-destructive/40 p-3">
      <Input
        placeholder="取消原因（必填）：取消等于把货停在半路"
        value={reason}
        onChange={(e) => setReason(e.target.value)}
      />
      <div className="flex gap-2">
        <Button
          variant="destructive"
          disabled={busy || reason.trim().length === 0}
          onClick={() =>
            void act('取消', async () => {
              const result = await cancelRequest(detail.request.id, {
                expectedVersion: detail.request.processVersion,
                reason: reason.trim(),
              });
              setOpen(false);
              setReason('');
              return `已取消（版本 ${result.processVersion}）。`;
            })
          }
        >
          确认取消
        </Button>
        <Button variant="ghost" onClick={() => setOpen(false)}>
          返回
        </Button>
      </div>
    </div>
  );
}

type FulfillmentStepLiteral = 'RESERVE' | 'OUTBOUND' | 'WORK' | 'TRACK';
type FulfillmentStatusLiteral =
  | 'DRAFT'
  | 'RESERVING'
  | 'RESERVED'
  | 'OUTBOUND_PENDING'
  | 'OUTBOUND_CONFIRMED'
  | 'WORKING'
  | 'COMPLETED'
  | 'BLOCKED'
  | 'CANCELLED';

function nextAction(row: FulfillmentRequestRow):
  | { to: FulfillmentStatusLiteral; step: FulfillmentStepLiteral; label: string }
  | null {
  switch (row.status) {
    case 'DRAFT':
      return { to: 'RESERVING', step: 'RESERVE', label: '预占中' };
    case 'RESERVING':
      return { to: 'RESERVED', step: 'OUTBOUND', label: '已锁定预占' };
    case 'RESERVED':
      return { to: 'OUTBOUND_PENDING', step: 'OUTBOUND', label: '出库单待确认' };
    case 'OUTBOUND_PENDING':
      return { to: 'OUTBOUND_CONFIRMED', step: 'OUTBOUND', label: '出库单已确认' };
    case 'OUTBOUND_CONFIRMED':
      return { to: 'WORKING', step: 'WORK', label: '作业编排中' };
    default:
      return null;
  }
}

function reasonCodeFor(step: string): string {
  switch (step) {
    case 'RESERVE':
      return 'RESERVE_REJECTED';
    case 'OUTBOUND':
      return 'OUTBOUND_REJECTED';
    default:
      return 'STALE_ACK';
  }
}

function StepMeter({ reached }: { reached: number }) {
  return (
    <span className="inline-flex items-center gap-1">
      {STEP_LADDER.map((step, index) => (
        <span
          key={step}
          title={STEP_LABELS[step]}
          className={`inline-block h-2 w-6 rounded ${
            index < reached ? 'bg-emerald-500' : 'bg-muted'
          }`}
        />
      ))}
    </span>
  );
}

function BoundaryRow({
  label,
  value,
  noText,
  yesText,
}: {
  label: string;
  value: boolean;
  noText: string;
  yesText?: string;
}) {
  return (
    <div className="rounded border px-2 py-1">
      <span className="text-muted-foreground">{label}：</span>
      <span className={value ? 'text-emerald-600' : 'text-amber-700'}>
        {value ? (yesText ?? '是') : noText}
      </span>
    </div>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-xs text-muted-foreground">{label}</div>
      <div>{value}</div>
    </div>
  );
}

function iso(value: string | null | undefined): string {
  if (!value) return '—';
  return new Date(value).toLocaleString('zh-CN');
}
