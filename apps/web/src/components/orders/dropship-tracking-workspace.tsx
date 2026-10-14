'use client';

import type { ReactNode } from 'react';
import { useCallback, useEffect, useState } from 'react';
import { PackageSearch, RefreshCw, TriangleAlert } from 'lucide-react';
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
import type {
  DropshipTrackingCapabilities,
  DropshipTrackingDetail,
  DropshipTrackingEventRow,
  DropshipTrackingExceptionRow,
  DropshipTrackingPage,
  DropshipTrackingPolicyRow,
  DropshipTrackingRow,
  DropshipTaskPage,
  DropshipTaskRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  EXCEPTION_KINDS,
  EXCEPTION_STATUSES,
  OWN_LEG_STATUSES,
  SUPPLIER_LEG_STATUSES,
  TASK_STATUSES,
  activeLegLabel,
  callbackText,
  canDismiss,
  canDispose,
  changeDropshipDispositionStatus,
  claimDropshipTask,
  completeDropshipTask,
  createDropshipTracking,
  dismissDropshipException,
  disposeFormError,
  disposeDropshipException,
  errorText,
  evaluateDropshipTrackingExceptions,
  exceptionKindLabel,
  exceptionStatusLabel,
  fetchDropshipTrackingExceptions,
  fetchDropshipTasks,
  fetchDropshipTracking,
  fetchDropshipTrackingCapabilities,
  fetchDropshipTrackingEvents,
  fetchDropshipTrackingPolicy,
  fetchDropshipTrackings,
  legLines,
  moneyText,
  ownLegIdle,
  overdueNotShipped,
  promisedText,
  recordDropshipOwnLeg,
  recordDropshipSupplierLeg,
  saveDropshipTrackingPolicy,
  severityLabel,
  supplierLegAbandoned,
  supplierLegLabel,
  taskKindLabel,
  taskStatusLabel,
  timeText,
} from './dropship-tracking-model';
const PAGE_SIZE = 20;

/**
 * 表单字段外壳。
 *
 * <p>与 9.6.1.1 / 9.6.1.2 工作区同一理由刻意不用 `FormField`：本页大量字段是
 * `<select>`，而 `FormField` 要求 children 是 `Input`。视觉一致，但下拉与输入可共用外壳。
 */
function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="space-y-2">
      <span className="text-label-13 text-foreground block">{label}</span>
      {children}
    </div>
  );
}

/**
 * 9.6.1.3 代发履约跟踪与异常工作区。
 *
 * <p>蓝图：「跟踪代发订单的供应商发货与妥投状态，同步供应商运单号回传平台，
 * 处理代发异常（延迟、丢件、供应商跑单），并把『我方』与『供应商』两段物流在订单上
 * 分开呈现。输出：代发履约状态；异常清单 + 处置（催供应商/改自营发货/退款）。」
 *
 * <p><b>四个区块按「会不会掉绩效」的顺序排</b>，而不是按操作顺序：
 * 异常清单 → 跟踪工作区（两段物流） → 待回传/待处置任务 → 检出策略。
 * 异常排最前是因为蓝图那句「代发模式下『及时发货』是供应商责任但平台考核算我方，
 * 因此异常必须可发现可切换，否则直接掉绩效」。
 *
 * <p><b>本工作区最要紧的一件事</b>：两段物流画成**两块**而不是一行。
 * 切段之后供应商段是「保留的凭据」而不是当前承运，合并显示会让人以为供应商那票
 * 货凭空消失了 —— 而跑单纠纷问的恰好是「供应商到底发没发过」。
 */
export function DropshipTrackingWorkspace() {
  const [caps, setCaps] = useState<DropshipTrackingCapabilities | null>(null);
  const [policy, setPolicy] = useState<DropshipTrackingPolicyRow | null>(null);
  const [trackings, setTrackings] = useState<DropshipTrackingPage | null>(null);
  const [exceptions, setExceptions] = useState<DropshipTrackingExceptionRow[]>([]);
  const [tasks, setTasks] = useState<DropshipTaskPage | null>(null);
  const [detail, setDetail] = useState<DropshipTrackingDetail | null>(null);
  const [events, setEvents] = useState<DropshipTrackingEventRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);

  const [legFilter, setLegFilter] = useState('');
  const [exceptionKindFilter, setExceptionKindFilter] = useState('');
  const [exceptionStatusFilter, setExceptionStatusFilter] = useState('');
  const [taskStatusFilter, setTaskStatusFilter] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsNext, policyNext, trackingsNext, exceptionsNext, tasksNext, eventsNext] =
        await Promise.all([
          fetchDropshipTrackingCapabilities(),
          fetchDropshipTrackingPolicy(),
          fetchDropshipTrackings({ activeLeg: legFilter, keyword, page, size: PAGE_SIZE }),
          fetchDropshipTrackingExceptions({
            exceptionKind: exceptionKindFilter,
            status: exceptionStatusFilter,
            keyword,
            page: 0,
            size: 50,
          }),
          fetchDropshipTasks({ taskStatus: taskStatusFilter, page: 0, size: 50 }),
          fetchDropshipTrackingEvents({ page: 0, size: 50 }),
        ]);
      setCaps(capsNext);
      setPolicy(policyNext);
      setTrackings(trackingsNext);
      setExceptions(exceptionsNext.items);
      setTasks(tasksNext);
      setEvents(eventsNext.items);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [legFilter, exceptionKindFilter, exceptionStatusFilter, taskStatusFilter, keyword, page]);

  useEffect(() => {
    void load();
  }, [load]);

  async function run(key: string, work: () => Promise<string>) {
    setBusy(key);
    setError(null);
    try {
      setNote(await work());
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(null);
    }
  }

  const openExceptions = exceptions.filter((e) => e.status === 'OPEN');
  const criticalCount = openExceptions.filter((e) => e.severity === 'CRITICAL').length;
  const switchedCount = trackings?.items.filter((t) => t.activeLeg === 'OWN').length ?? 0;
  const pendingTasks = tasks?.items.filter((t) => t.taskStatus === 'OPEN').length ?? 0;

  return (
    <div className="space-y-6">
      <PageHeader
        title="代发履约跟踪与异常"
        description="跟踪代发订单的供应商发货与妥投状态，同步供应商运单号回传平台，处理延迟 / 丢件 / 供应商跑单三类异常；「我方」与「供应商」两段物流分开呈现。代发模式下及时发货是供应商责任但平台考核算我方，异常必须可发现、可切换。"
        actions={
          <Button variant="outline" size="sm" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <div className="border-danger/40 bg-danger-soft text-danger flex items-center gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="h-4 w-4 shrink-0" />
          <span className="text-copy-13">{error}</span>
          <Button variant="outline" size="sm" onClick={() => void load()} className="ml-auto">
            重试
          </Button>
        </div>
      )}

      {note && (
        <Notice tone="success" title="操作完成">
          {note}
        </Notice>
      )}

      {caps && (
        <Notice tone="warning" title="平台回传与售后退款都还没交付，本模块只落待办任务">
          {caps.twoLegNote}
        </Notice>
      )}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="未处置异常"
          value={loading ? '—' : openExceptions.length}
          polarity={criticalCount > 0 ? 'negative' : 'neutral'}
          comparisonLabel={criticalCount > 0 ? `其中严重 ${criticalCount} 条` : '无严重异常'}
        />
        <StatCard
          label="已改自营发货"
          value={loading ? '—' : switchedCount}
          comparisonLabel="生效段切到我方段"
        />
        <StatCard
          label="待回传 / 待处置"
          value={loading ? '—' : pendingTasks}
          comparisonLabel="2.5.1.1 与 11 售后未交付"
        />
        <StatCard label="跟踪单数" value={loading ? '—' : (trackings?.total ?? 0)} />
      </div>

      {/* ------------------------------------------------ 异常清单（最显眼） */}
      <SectionCard
        title="履约异常"
        description="蓝图原文：「代发模式下『及时发货』是供应商责任但平台考核算我方，因此异常必须可发现可切换，否则直接掉绩效。」三类异常由规则检出而不是手工标记：超时未发货（按承诺时限）、丢件（按轨迹静默时长）、供应商跑单（报发货却无运单号）。每条都带判定阈值与算式，事后改策略不会让历史异常看起来是按新规则算的。"
      >
        <div className="mb-4 flex flex-wrap items-end gap-3">
          <Button
            size="sm"
            disabled={busy !== null}
            onClick={() =>
              void run('evaluate', async () => {
                const result = await evaluateDropshipTrackingExceptions();
                return result.detected === 0
                  ? `扫了 ${result.scanned} 张跟踪单，本轮没有检出新的履约异常。`
                  : `扫了 ${result.scanned} 张跟踪单，检出 ${result.detected} 条：${result.detectedKinds.join('、')}。`;
              })
            }
          >
            {busy === 'evaluate' ? '检出中…' : '运行异常检出'}
          </Button>
          <Field label="异常类型">
            <select
              className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
              value={exceptionKindFilter}
              onChange={(e) => {
                setPage(0);
                setExceptionKindFilter(e.target.value);
              }}
            >
              <option value="">全部</option>
              {EXCEPTION_KINDS.map((k) => (
                <option key={k} value={k}>
                  {exceptionKindLabel(k)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="状态">
            <select
              className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
              value={exceptionStatusFilter}
              onChange={(e) => {
                setPage(0);
                setExceptionStatusFilter(e.target.value);
              }}
            >
              <option value="">全部</option>
              {EXCEPTION_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {exceptionStatusLabel(s)}
                </option>
              ))}
            </select>
          </Field>
        </div>

        {loading ? (
          <p className="text-copy-13 text-muted">加载中…</p>
        ) : exceptions.length === 0 ? (
          <EmptyState
            icon={<PackageSearch className="h-6 w-6" />}
            title="没有履约异常"
            description="供应商在承诺时限内发了货、轨迹在正常更新、且给了可查的运单号。点「运行异常检出」可以按当前策略重新判定一次。"
          />
        ) : (
          <ul className="flex flex-col gap-2">
            {exceptions.map((e) => (
              <ExceptionItem
                key={e.id}
                row={e}
                busy={busy}
                onDispose={() =>
                  void run(`dispose:${e.id}`, async () => {
                    const action = window.prompt(
                      `处置 ${e.exceptionNo}：\n1=催供应商  2=改自营发货  3=发起退款\n请输入依据说明（必填）：`,
                      '催供应商尽快发货',
                    );
                    if (!action) return '已取消处置。';
                    const kind =
                      action.trim() === '2'
                        ? 'SWITCH_TO_SELF'
                        : action.trim() === '3'
                          ? 'REQUEST_REFUND'
                          : 'URGE_SUPPLIER';
                    let amount: string | undefined;
                    let currency: string | undefined;
                    if (kind === 'REQUEST_REFUND') {
                      const raw = window.prompt('退款金额（必填）：', '0.0000');
                      if (!raw) return '退款必须给金额，已取消。';
                      amount = raw;
                      currency = window.prompt('退款币种（如 USD）：', 'USD') ?? '';
                    }
                    const problem = disposeFormError(
                      kind,
                      amount ?? '',
                      currency ?? '',
                      action.trim(),
                    );
                    if (problem) return problem;
                    await disposeDropshipException(e.id, {
                      actionKind: kind,
                      note: action.trim(),
                      // 非退款动作的后端忽略这两个字段（服务层只对 REQUEST_REFUND 落金额），
                      // 这里仍按契约把字段传齐，避免出现「界面上少传一个字段」。
                      refundAmount: kind === 'REQUEST_REFUND' ? Number(amount) : 0,
                      refundCurrencyCode: kind === 'REQUEST_REFUND' ? (currency ?? '') : '',
                    });
                    return `已登记处置：${
                      kind === 'URGE_SUPPLIER'
                        ? '催供应商'
                        : kind === 'SWITCH_TO_SELF'
                          ? '改自营发货（生效段已切到我方段，供应商段保留为凭据）'
                          : '发起退款（待办已交给 11 售后）'
                    }。`;
                  })
                }
                onDismiss={() =>
                  void run(`dismiss:${e.id}`, async () => {
                    const reason = window.prompt('判为误报必须写明理由（必填）：', '');
                    if (!reason) return '判误报必须写明理由，已取消。';
                    await dismissDropshipException(e.id, { reason });
                    return '已判为误报；下次同类异常重新成立时会作为一条新事实重新检出。';
                  })
                }
                onOpen={() =>
                  void run(`detail:${e.id}`, async () => {
                    setDetail(await fetchDropshipTracking(e.trackingId));
                    return `已打开 ${e.orderNo} 的跟踪详情。`;
                  })
                }
              />
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ------------------------------------------------ 跟踪工作区（两段物流） */}
      <SectionCard
        title="履约跟踪"
        description="每一张跟踪把「供应商段」与「我方段」分两块呈现，各带各的承运商、运单号与状态。执行「改自营发货」后供应商段会变成「已废弃」但运单号仍然保留 —— 那是供应商确实发过这一票的凭据，不是可以抹掉的历史。"
      >
        <div className="mb-4 flex flex-wrap items-end gap-3">
          <Field label="生效段">
            <select
              className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
              value={legFilter}
              onChange={(e) => {
                setPage(0);
                setLegFilter(e.target.value);
              }}
            >
              <option value="">全部</option>
              <option value="SUPPLIER">供应商段</option>
              <option value="OWN">我方段（已改自营）</option>
            </select>
          </Field>
          <Field label="关键字">
            <Input
              value={keyword}
              placeholder="跟踪号 / 代发单号 / 销售单号 / 运单号"
              onChange={(e) => setKeyword(e.target.value)}
              className="w-72"
            />
          </Field>
          <Button
            size="sm"
            variant="outline"
            disabled={busy !== null}
            onClick={() =>
              void run('create', async () => {
                const orderId = window.prompt('代发单 ID（9.6.1.2）：', '');
                if (!orderId) return '已取消建跟踪。';
                const promised = window.prompt(
                  '供应商承诺发货时限（ISO 8601，如 2026-10-20T18:00:00+08:00）：延迟判定的唯一基准',
                  '',
                );
                if (!promised) return '承诺发货时限必填：没有承诺时限就无从「迟」。';
                await createDropshipTracking({
                  orderId: orderId.trim(),
                  supplierPromisedShipAt: promised.trim(),
                  note: '',
                });
                return '已建立跟踪。';
              })
            }
          >
            新建跟踪
          </Button>
        </div>

        {loading ? (
          <p className="text-copy-13 text-muted">加载中…</p>
        ) : (trackings?.items.length ?? 0) === 0 ? (
          <EmptyState
            icon={<PackageSearch className="h-6 w-6" />}
            title="还没有代发履约跟踪"
            description="代发单提交给供应商（DISPATCHED / ACCEPTED / SHIPPED）之后就可以建跟踪。承诺发货时限必填 —— 它是延迟判定的唯一基准。"
          />
        ) : (
          <ul className="flex flex-col gap-3">
            {trackings?.items.map((t) => (
              <li key={t.id} className="border-border rounded-lg border p-4">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-label-13 text-foreground font-medium">{t.orderNo}</span>
                  <span className="text-copy-13 text-muted">{t.salesOrderNo}</span>
                  <Badge tone={t.activeLeg === 'OWN' ? 'info' : 'neutral'}>
                    {activeLegLabel(t.activeLeg)}
                  </Badge>
                  <Badge tone="neutral">供应商 {t.supplierCode}</Badge>
                  {overdueNotShipped(t) && <Badge tone="danger">已逾期未发货</Badge>}
                  {supplierLegAbandoned(t) && <Badge tone="warning">供应商段已废弃</Badge>}
                  {t.urgeCount > 0 && <Badge tone="warning">已催 {t.urgeCount} 次</Badge>}
                  <span className="text-copy-13 text-muted ml-auto">
                    承诺 {timeText(t.supplierPromisedShipAt)}（
                    {promisedText(t.supplierPromisedShipAt)}）
                  </span>
                </div>

                {/* 两段物流：两块，不合并 */}
                <div className="mt-3 grid gap-2 md:grid-cols-2">
                  {legLines(t).map((leg) => (
                    <div
                      key={leg.leg}
                      className={
                        leg.active
                          ? 'border-primary/40 bg-primary-soft rounded-md border p-3'
                          : 'border-border bg-muted-soft rounded-md border p-3'
                      }
                    >
                      <div className="flex items-center gap-2">
                        <span className="text-label-13 text-foreground">{leg.legLabel}</span>
                        {leg.active ? (
                          <Badge tone="success">当前生效</Badge>
                        ) : (
                          <Badge tone="neutral">
                            {leg.leg === 'supplier' ? '已废弃（保留凭据）' : '未启用'}
                          </Badge>
                        )}
                      </div>
                      <div className="text-copy-13 text-muted mt-2 space-y-1">
                        <p>
                          状态：<span className="text-foreground">{leg.statusLabel}</span>
                        </p>
                        <p>
                          承运商：{leg.carrierCode || '—'}　运单号：
                          <span className="text-foreground">{leg.trackingNo || '—'}</span>
                        </p>
                        <p>
                          最后更新：{timeText(leg.lastEventAt)}　关键节点：
                          {timeText(leg.terminalAt)}
                        </p>
                      </div>
                    </div>
                  ))}
                </div>

                <div className="text-copy-13 text-muted mt-3 flex flex-wrap items-center gap-3">
                  <span>回传平台：{callbackText(t)}</span>
                  {t.switchedToOwnAt && (
                    <span>
                      切换到自营：{timeText(t.switchedToOwnAt)}（{t.switchReason}）
                    </span>
                  )}
                  <div className="ml-auto flex gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy !== null}
                      onClick={() =>
                        void run(`supplier:${t.id}`, async () => {
                          const status = window.prompt(
                            '供应商段状态：\nNOT_SHIPMENT 未发货 / SHIPPED 已发货 / IN_TRANSIT 在途 / DELIVERED 已妥投 / LOST 丢件\n\n请输入：',
                            'SHIPPED',
                          );
                          if (!status) return '已取消录入。';
                          const carrier =
                            window.prompt('承运商编码（给了运单号时必填）：', '') ?? '';
                          const trackingNo = window.prompt('供应商运单号：', '') ?? '';
                          const shippedAt =
                            window.prompt(
                              '供应商发货时刻（ISO 8601；已进入 SHIPPED/IN_TRANSIT/DELIVERED 必填）：',
                              new Date().toISOString(),
                            ) ?? '';
                          const lastEventAt =
                            window.prompt(
                              '轨迹最后更新时刻（ISO 8601）：**必须由观测方给**，填当前时刻会让停滞检测永远算不出丢件',
                              '',
                            ) ?? '';
                          await recordDropshipSupplierLeg(t.id, {
                            supplierLegStatus: status.trim().toUpperCase(),
                            supplierCarrierCode: carrier.trim(),
                            supplierTrackingNo: trackingNo.trim(),
                            supplierShippedAt: shippedAt.trim() || null,
                            supplierLastEventAt: lastEventAt.trim() || null,
                            supplierDeliveredAt: null,
                            supplierExceptionNote: '',
                          });
                          return trackingNo.trim()
                            ? '已更新供应商段；给了运单号就会排一条回传平台待办（2.5.1.1 未交付，状态停在「待回传」）。'
                            : '已更新供应商段。给了运单号之后才会排回传待办。';
                        })
                      }
                    >
                      录供应商段
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy !== null}
                      onClick={() =>
                        void run(`own:${t.id}`, async () => {
                          const status = window.prompt(
                            '我方段状态：\nNOT_HANDED_OVER 未交运 / HANDED_OVER 已交运 / IN_TRANSIT 在途 / DELIVERED 已妥投 / RETURNED 已退回\n\n请输入：',
                            'HANDED_OVER',
                          );
                          if (!status) return '已取消录入。';
                          const carrier =
                            window.prompt('承运商编码（给了运单号时必填）：', '') ?? '';
                          const trackingNo = window.prompt('我方运单号：', '') ?? '';
                          const handedOverAt =
                            window.prompt(
                              '我方交运时刻（ISO 8601；已进入 HANDED_OVER/IN_TRANSIT/DELIVERED 必填）：',
                              new Date().toISOString(),
                            ) ?? '';
                          await recordDropshipOwnLeg(t.id, {
                            ownLegStatus: status.trim().toUpperCase(),
                            ownCarrierCode: carrier.trim(),
                            ownTrackingNo: trackingNo.trim(),
                            ownHandedOverAt: handedOverAt.trim() || null,
                            ownLastEventAt: null,
                            ownDeliveredAt: null,
                            ownDeliveryNote: '',
                          });
                          return '已更新我方段（不会覆盖供应商段运单号）。';
                        })
                      }
                    >
                      录我方段
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy !== null}
                      onClick={() =>
                        void run(`detail:${t.id}`, async () => {
                          setDetail(await fetchDropshipTracking(t.id));
                          return `已打开 ${t.orderNo} 的详情。`;
                        })
                      }
                    >
                      查看详情
                    </Button>
                  </div>
                </div>
                {ownLegIdle(t) && (
                  <p className="text-copy-13 text-muted mt-2">
                    我方段还没启用：当前是纯代发，货由供应商直发买家，本来就没有我方这一段。
                  </p>
                )}
              </li>
            ))}
          </ul>
        )}

        {(trackings?.total ?? 0) > PAGE_SIZE && (
          <div className="mt-4 flex items-center gap-2">
            <Button
              size="sm"
              variant="outline"
              disabled={page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
            >
              上一页
            </Button>
            <span className="text-copy-13 text-muted">
              第 {page + 1} 页 / 共 {trackings?.total ?? 0} 条
            </span>
            <Button
              size="sm"
              variant="outline"
              disabled={(page + 1) * PAGE_SIZE >= (trackings?.total ?? 0)}
              onClick={() => setPage((p) => p + 1)}
            >
              下一页
            </Button>
          </div>
        )}
      </SectionCard>

      {/* ------------------------------------------------ 任务队列 */}
      <SectionCard
        title="待回传 / 待处置任务"
        description="供应商运单号回传平台（2.5.1.1，channel）与退款（11 售后）都尚未交付，因此本模块只在这里落**真任务**：有状态机、有载荷、有幂等键。任务在被下游接手之前不会被当成已办 —— 界面上「待回传」不会画成「已回传」。"
      >
        <div className="mb-4 flex flex-wrap items-end gap-3">
          <Field label="任务状态">
            <select
              className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
              value={taskStatusFilter}
              onChange={(e) => setTaskStatusFilter(e.target.value)}
            >
              <option value="">全部</option>
              {TASK_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {taskStatusLabel(s)}
                </option>
              ))}
            </select>
          </Field>
        </div>

        {loading ? (
          <p className="text-copy-13 text-muted">加载中…</p>
        ) : (tasks?.items.length ?? 0) === 0 ? (
          <EmptyState
            icon={<PackageSearch className="h-6 w-6" />}
            title="没有待办任务"
            description="供应商段给了运单号就会排一条回传平台待办；催供应商、改自营发货、发起退款也各自落一条。"
          />
        ) : (
          <ul className="flex flex-col gap-2">
            {tasks?.items.map((t) => (
              <TaskItem
                key={t.id}
                row={t}
                busy={busy}
                onClaim={() =>
                  void run(`claim:${t.id}`, async () => {
                    await claimDropshipTask(t.id);
                    return `已领取 ${t.taskNo}。`;
                  })
                }
                onComplete={() =>
                  void run(`done:${t.id}`, async () => {
                    await completeDropshipTask(t.id, { success: true, error: '' });
                    return `${t.taskNo} 已被下游受理，回传状态随之更新。`;
                  })
                }
              />
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ------------------------------------------------ 检出策略 */}
      <SectionCard
        title="异常检出策略"
        description="阈值是租户经营口径，不写死在代码里：不同店铺的平台考核强度不一样。改阈值只影响此后检出的异常 —— 已经落库的异常记着当时用的阈值与算式，不会被事后改动的规则「重新解释」。"
      >
        <PolicyEditor
          policy={policy}
          busy={busy !== null}
          onSave={(input) =>
            void run('policy', async () => {
              await saveDropshipTrackingPolicy(input);
              return '检出策略已更新。';
            })
          }
        />
      </SectionCard>

      {/* ------------------------------------------------ 跟踪详情 */}
      {detail && (
        <SectionCard
          title={`跟踪详情 · ${detail.tracking.orderNo}`}
          description="两段物流、异常、处置、任务与事件流。事件流是只读凭据（append-only），切换那一瞬两段状态同时变化，只记一段就看不出供应商段去哪了。"
        >
          <div className="space-y-4">
            <div className="grid gap-2 md:grid-cols-2">
              {legLines(detail.tracking).map((leg) => (
                <div key={leg.leg} className="border-border rounded-md border p-3">
                  <span className="text-label-13 text-foreground">{leg.legLabel}</span>
                  <div className="text-copy-13 text-muted mt-2 space-y-1">
                    <p>状态：{leg.statusLabel}</p>
                    <p>
                      承运商：{leg.carrierCode || '—'}　运单号：{leg.trackingNo || '—'}
                    </p>
                  </div>
                </div>
              ))}
            </div>

            <div>
              <h3 className="text-label-13 text-foreground">处置记录</h3>
              {detail.dispositions.length === 0 ? (
                <p className="text-copy-13 text-muted mt-2">还没有处置。</p>
              ) : (
                <ul className="mt-2 flex flex-col gap-2">
                  {detail.dispositions.map((d) => (
                    <li key={d.id} className="border-border rounded-md border p-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone="neutral">{d.dispositionNo}</Badge>
                        <Badge tone="info">{taskKindLabel(d.actionKind)}</Badge>
                        <Badge tone="neutral">{d.status}</Badge>
                        {d.refundAmount && (
                          <span className="text-copy-13">
                            退款 {moneyText(d.refundAmount, d.refundCurrencyCode ?? undefined)}
                          </span>
                        )}
                        <span className="text-copy-13 text-muted">{d.note}</span>
                      </div>
                      {d.status === 'PENDING' && (
                        <div className="mt-2 flex gap-2">
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={busy !== null}
                            onClick={() =>
                              void run(`dsent:${d.id}`, async () => {
                                await changeDropshipDispositionStatus(d.id, {
                                  toStatus: 'SENT',
                                  outcome: '',
                                });
                                return `${d.dispositionNo} 已发出。`;
                              })
                            }
                          >
                            标记已发出
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={busy !== null}
                            onClick={() =>
                              void run(`dcancel:${d.id}`, async () => {
                                await changeDropshipDispositionStatus(d.id, {
                                  toStatus: 'CANCELLED',
                                  outcome: '',
                                });
                                return `${d.dispositionNo} 已撤销（只能在还没发出时撤销）。`;
                              })
                            }
                          >
                            撤销
                          </Button>
                        </div>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div>
              <h3 className="text-label-13 text-foreground">事件流</h3>
              {detail.events.length === 0 ? (
                <p className="text-copy-13 text-muted mt-2">还没有事件。</p>
              ) : (
                <ul className="mt-2 flex flex-col gap-1">
                  {detail.events.map((e) => (
                    <li key={e.id} className="text-copy-13 text-muted">
                      <span className="text-foreground">{timeText(e.occurredAt)}</span> ·{' '}
                      {supplierLegLabel(e.toSupplierLegStatus || '—')} / {e.toOwnLegStatus || '—'} ·{' '}
                      {e.detail}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        </SectionCard>
      )}

      {/* ------------------------------------------------ 事件流（只读） */}
      <SectionCard
        title="履约事件流"
        description="全租户最近的事件（只读）。事件流 append-only：连运行角色都没有改删权限 —— 它是「什么时候发生了什么」的凭据。"
      >
        {loading ? (
          <p className="text-copy-13 text-muted">加载中…</p>
        ) : events.length === 0 ? (
          <EmptyState
            icon={<PackageSearch className="h-6 w-6" />}
            title="还没有事件"
            description="建跟踪、录轨迹、检出异常、处置与切换履约模式都会留下事件。"
          />
        ) : (
          <ul className="flex flex-col gap-1">
            {events.map((e) => (
              <li key={e.id} className="text-copy-13 text-muted">
                <span className="text-foreground">{timeText(e.occurredAt)}</span> · {e.eventKind} ·{' '}
                {e.detail}
              </li>
            ))}
          </ul>
        )}
      </SectionCard>
    </div>
  );
}

// ------------------------------------------------------------------ 子组件

/** 一条异常：判据、严重度、处置与判误报入口。 */
function ExceptionItem({
  row,
  busy,
  onDispose,
  onDismiss,
  onOpen,
}: {
  row: DropshipTrackingExceptionRow;
  busy: string | null;
  onDispose: () => void;
  onDismiss: () => void;
  onOpen: () => void;
}) {
  return (
    <li className="border-border rounded-lg border p-4">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={row.severity === 'CRITICAL' ? 'danger' : 'warning'}>
          {severityLabel(row.severity)}
        </Badge>
        <span className="text-label-13 text-foreground font-medium">{row.exceptionKindLabel}</span>
        <span className="text-copy-13 text-muted">
          {row.orderNo} / {row.salesOrderNo}
        </span>
        <Badge tone="neutral">{exceptionStatusLabel(row.status)}</Badge>
        <span className="text-copy-13 text-muted ml-auto">{row.exceptionNo}</span>
      </div>
      <p className="text-copy-13 text-muted mt-2">
        判据：{row.ruleSummary}（阈值 {row.ruleThresholdHours} 小时）—— {row.ruleEvidence}
      </p>
      {row.resolutionNote && (
        <p className="text-copy-13 text-muted mt-1">处置说明：{row.resolutionNote}</p>
      )}
      <div className="mt-3 flex flex-wrap gap-2">
        <Button size="sm" variant="outline" disabled={busy !== null} onClick={onOpen}>
          查看跟踪
        </Button>
        {canDispose(row) && (
          <Button size="sm" disabled={busy !== null} onClick={onDispose}>
            处置（催供应商 / 改自营 / 退款）
          </Button>
        )}
        {canDismiss(row) && (
          <Button size="sm" variant="outline" disabled={busy !== null} onClick={onDismiss}>
            判为误报
          </Button>
        )}
      </div>
    </li>
  );
}

/** 一条任务：目标、状态、载荷与下游回执入口。 */
function TaskItem({
  row,
  busy,
  onClaim,
  onComplete,
}: {
  row: DropshipTaskRow;
  busy: string | null;
  onClaim: () => void;
  onComplete: () => void;
}) {
  const payload = row.payload;
  return (
    <li className="border-border rounded-lg border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-label-13 text-foreground">{row.taskKindLabel}</span>
        <Badge
          tone={
            row.taskStatus === 'FAILED'
              ? 'danger'
              : row.taskStatus === 'ACKNOWLEDGED'
                ? 'success'
                : 'neutral'
          }
        >
          {taskStatusLabel(row.taskStatus)}
        </Badge>
        <Badge tone="neutral">{row.targetOwner}</Badge>
        <span className="text-copy-13 text-muted">
          {row.orderNo} / {row.salesOrderNo}
        </span>
        <span className="text-copy-13 text-muted ml-auto">{row.taskNo}</span>
      </div>
      <p className="text-copy-13 text-muted mt-1">
        {payload.supplierTrackingNo
          ? `运单号 ${payload.supplierTrackingNo}（承运商 ${payload.supplierCarrierCode || '—'}）`
          : payload.refundAmount
            ? `退款 ${moneyText(payload.refundAmount, payload.refundCurrencyCode ?? undefined)}`
            : payload.note || '—'}
      </p>
      {row.lastError && <p className="text-copy-13 text-danger mt-1">失败原因：{row.lastError}</p>}
      <div className="mt-2 flex gap-2">
        {row.taskStatus === 'OPEN' && (
          <Button size="sm" variant="outline" disabled={busy !== null} onClick={onClaim}>
            领取
          </Button>
        )}
        {(row.taskStatus === 'CLAIMED' || row.taskStatus === 'OPEN') && (
          <Button size="sm" variant="outline" disabled={busy !== null} onClick={onComplete}>
            标记下游已受理
          </Button>
        )}
      </div>
    </li>
  );
}

/** 检出策略编辑（阈值都是租户口径，不是写死的常量）。 */
function PolicyEditor({
  policy,
  busy,
  onSave,
}: {
  policy: DropshipTrackingPolicyRow | null;
  busy: boolean;
  onSave: (input: {
    delayToleranceHours: number;
    criticalDelayHours: number;
    stallThresholdHours: number;
    criticalStallHours: number;
    requireTrackingNoOnShipped: boolean;
    note: string;
  }) => void;
}) {
  const [delayToleranceHours, setDelayToleranceHours] = useState(0);
  const [criticalDelayHours, setCriticalDelayHours] = useState(24);
  const [stallThresholdHours, setStallThresholdHours] = useState(72);
  const [criticalStallHours, setCriticalStallHours] = useState(120);
  const [requireTrackingNoOnShipped, setRequireTrackingNoOnShipped] = useState(true);
  const [note, setNote] = useState('');

  useEffect(() => {
    if (!policy) return;
    setDelayToleranceHours(policy.delayToleranceHours);
    setCriticalDelayHours(policy.criticalDelayHours);
    setStallThresholdHours(policy.stallThresholdHours);
    setCriticalStallHours(policy.criticalStallHours);
    setRequireTrackingNoOnShipped(policy.requireTrackingNoOnShipped);
    setNote(policy.note);
  }, [policy]);

  return (
    <div className="space-y-4">
      {!policy?.policyRegistered && (
        <Notice tone="info" title="尚未登记策略，当前使用内置默认值">
          延迟宽限 0 小时、严重分界 24 小时；轨迹静默 72 小时判丢件、120 小时升级为严重；
          供应商报发货必须给运单号。登记后按这里的值检出。
        </Notice>
      )}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Field label="延迟宽限（小时）">
          <Input
            type="number"
            value={delayToleranceHours}
            onChange={(e) => setDelayToleranceHours(Number(e.target.value))}
          />
        </Field>
        <Field label="延迟严重分界（小时）">
          <Input
            type="number"
            value={criticalDelayHours}
            onChange={(e) => setCriticalDelayHours(Number(e.target.value))}
          />
        </Field>
        <Field label="轨迹静默阈值（小时）">
          <Input
            type="number"
            value={stallThresholdHours}
            onChange={(e) => setStallThresholdHours(Number(e.target.value))}
          />
        </Field>
        <Field label="停滞严重分界（小时）">
          <Input
            type="number"
            value={criticalStallHours}
            onChange={(e) => setCriticalStallHours(Number(e.target.value))}
          />
        </Field>
      </div>
      <div className="flex flex-wrap items-end gap-3">
        <Field label="供应商报发货必须给运单号">
          <select
            className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
            value={requireTrackingNoOnShipped ? '1' : '0'}
            onChange={(e) => setRequireTrackingNoOnShipped(e.target.value === '1')}
          >
            <option value="1">是（无单号即判跑单）</option>
            <option value="0">否（线下发货店铺，停用跑单规则）</option>
          </select>
        </Field>
        <Field label="说明">
          <Input value={note} onChange={(e) => setNote(e.target.value)} className="w-72" />
        </Field>
        <Button
          size="sm"
          disabled={busy}
          onClick={() =>
            onSave({
              delayToleranceHours,
              criticalDelayHours,
              stallThresholdHours,
              criticalStallHours,
              requireTrackingNoOnShipped,
              note,
            })
          }
        >
          保存策略
        </Button>
      </div>
      <div className="text-copy-13 text-muted">
        <p>· 超时未发货：仍「未发货」且承诺时限已过宽限 → DELAYED_SHIPMENT</p>
        <p>· 丢件：已发货/在途、有运单号、轨迹静默超阈值 → PARCEL_LOST</p>
        <p>· 跑单：已发货/在途/妥投却没有运单号 → SUPPLIER_RUNNING_AWAY（恒严重）</p>
      </div>
    </div>
  );
}
