'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, PackageCheck, RefreshCw, Truck } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  PageHeader,
  SectionCard,
  Segmented,
  StatCard,
} from '@starcon/ui';
import type {
  PurchaseArrivalAlertBoard,
  PurchaseArrivalCapabilities,
  PurchaseArrivalDetail,
  PurchaseArrivalDispositionRow,
  PurchaseArrivalPage,
} from '../../../../../packages/api-contracts/src/index';
import {
  alertTypeLabel,
  arrivalStatusLabel,
  arrivalStatusTone,
  basisFactEntries,
  cancelArrival,
  confirmPayable,
  createArrival,
  dispositionActionLabel,
  dwellText,
  fetchAlertBoard,
  fetchArrival,
  fetchArrivals,
  fetchCapabilities,
  fmtDate,
  fmtDays,
  fmtEvaluatedAt,
  fmtMoney,
  fmtPercent,
  fmtQty,
  markShipped,
  overdueDaysText,
  payableStatusLabel,
  promiseCountdownText,
  registerArrival,
  rejectPayable,
  resolveDisposition,
  severityLabel,
  severityTone,
  supplierText,
  syncInTransit,
  type PurchaseArrivalParams,
} from './purchase-arrival-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 6.3.2.2 到货与逾期工作区。
 *
 * <p>蓝图：「跟踪采购单的到货状态：已发货（在途）、部分到货、全部到货、逾期未到、取消。
 * 逾期自动预警并给出处置建议（催货/改供应商/调库存/延期售卖）。」
 * 加粗句：「<b>部分到货必须立即更新已收数量并触发应付部分确认</b>，
 * 否则采购与财务三方数据对不上。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>可收 / 已收 / 未收三个数并排</b>，带单位与百分比 ——
 *       表格里不出现裸数字。</li>
 *   <li><b>逾期天数带判定时刻</b>：判定时刻（后端按数据库时钟算）显示在列表上方，
 *       否则清单上的天数没法解释。</li>
 *   <li><b>在途不是逾期</b>：在途单显示「不适用（在途不判逾期）」，
 *       不跟逾期单用同一套文案。</li>
 *   <li><b>建议要连依据一起显示</b>：动作名 + 人话依据 + 依据里的数字（basis_facts）。</li>
 *   <li><b>应付部分确认不假装已进财务</b>：待财务确认时生效金额显示 0 并写明下游未交付。</li>
 *   <li><b>未交付的能力直接说清</b>，不给一个点下去 404 的按钮。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */
export function PurchaseArrivalWorkspace() {
  const [params, setParams] = useState<PurchaseArrivalParams>({ page: 0, size: 20 });
  const [data, setData] = useState<PurchaseArrivalPage | null>(null);
  const [caps, setCaps] = useState<PurchaseArrivalCapabilities | null>(null);
  const [board, setBoard] = useState<PurchaseArrivalAlertBoard | null>(null);
  const [loading, setLoading] = useState(true);
  const [boardLoading, setBoardLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [view, setView] = useState<'list' | 'board'>('list');
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [overdueOnly, setOverdueOnly] = useState(false);
  const [inTransitOnly, setInTransitOnly] = useState(false);
  const [detail, setDetail] = useState<PurchaseArrivalDetail | null>(null);
  const [arrivalFor, setArrivalFor] = useState<PurchaseArrivalDetail | null>(null);
  const [shipFor, setShipFor] = useState<PurchaseArrivalDetail | null>(null);
  const [cancelFor, setCancelFor] = useState<PurchaseArrivalDetail | null>(null);
  const [busy, setBusy] = useState(false);
  const [creating, setCreating] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchArrivals(params));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [params]);

  const loadBoard = useCallback(async () => {
    setBoardLoading(true);
    setError(null);
    try {
      setBoard(await fetchAlertBoard({ page: 0, size: 50 }));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBoardLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void (async () => {
      try {
        setCaps(await fetchCapabilities());
      } catch {
        // capabilities 拿不到不该让整页空掉：列表仍然可用，只是边界说明退化。
        // 页面不假装自己知道规则。
        setCaps(null);
      }
    })();
  }, []);

  useEffect(() => {
    if (view === 'board') void loadBoard();
  }, [view, loadBoard]);

  const rows = data?.rows ?? [];

  const openDetail = useCallback(async (id: string) => {
    setError(null);
    try {
      setDetail(await fetchArrival(id));
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  const run = useCallback(
    async (work: () => Promise<PurchaseArrivalDetail | null>) => {
      setBusy(true);
      setError(null);
      try {
        const next = await work();
        setDetail(next);
        setArrivalFor(null);
        await load();
        await loadBoard();
      } catch (e) {
        setError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [load, loadBoard],
  );

  const applyFilters = useCallback(() => {
    setParams((prev) =>
      compact<PurchaseArrivalParams>({
        ...prev,
        page: 0,
        status: status || undefined,
        keyword: keyword.trim() || undefined,
        overdueOnly: overdueOnly || undefined,
        inTransitOnly: inTransitOnly || undefined,
      }),
    );
  }, [status, keyword, overdueOnly, inTransitOnly]);

  const summary = data?.summary;
  const overdueSection = useMemo(
    () => caps?.sections?.find((s) => s.key === 'overdueAlert'),
    [caps],
  );
  const payableSection = useMemo(
    () => caps?.sections?.find((s) => s.key === 'payableConfirmation'),
    [caps],
  );

  return (
    <div className="space-y-6">
      <PageHeader
        title="到货与逾期"
        description="跟踪采购单的到货状态：已发货（在途）/ 部分到货 / 全部到货 / 逾期未到 / 取消。逾期自动预警并给出处置建议。"
        actions={
          <div className="flex items-center gap-2">
            <Segmented
              value={view}
              onChange={setView}
              aria-label="视图"
              options={[
                {
                  value: 'list',
                  label: '跟踪列表',
                  icon: <PackageCheck className="h-3.5 w-3.5" />,
                },
                {
                  value: 'board',
                  label: '逾期预警看板',
                  icon: <AlertTriangle className="h-3.5 w-3.5" />,
                },
              ]}
            />
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
            <Button onClick={() => setCreating(true)}>
              <PackageCheck className="h-4 w-4" />
              新建跟踪单
            </Button>
          </div>
        }
      />

      {summary && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="跟踪单总数" value={summary.total} unit="单" />
          <StatCard
            label="逾期未到"
            value={summary.overdue}
            unit="单"
            polarity={summary.overdue > 0 ? 'negative' : 'neutral'}
          />
          <StatCard label="逾期缺口" value={fmtQty(summary.overdueOutstandingQty)} />
          <StatCard
            label="待财务确认应付"
            value={fmtMoney(summary.pendingPayableAmount)}
            polarity="neutral"
          />
        </div>
      )}

      {view === 'list' ? (
        <>
          <SectionCard
            title="筛选"
            description="状态、关键字、逾期与在途可以叠加。逾期筛选只认「逾期未到」—— 在途不判逾期。"
          >
            <div className="grid gap-3 md:grid-cols-4">
              <FormField id="arrival-keyword" label="关键字">
                <Input
                  id="arrival-keyword"
                  value={keyword}
                  placeholder="采购单号 / 跟踪单号 / 供应商"
                  onChange={(e) => setKeyword(e.target.value)}
                />
              </FormField>
              <FormField id="arrival-status" label="到货状态">
                <select
                  id="arrival-status"
                  className="border-border bg-card h-9 w-full rounded-md border px-3 text-sm"
                  value={status}
                  onChange={(e) => setStatus(e.target.value)}
                >
                  <option value="">全部状态</option>
                  <option value="PENDING_SHIPMENT">待发货</option>
                  <option value="IN_TRANSIT">已发货（在途）</option>
                  <option value="PARTIALLY_RECEIVED">部分到货</option>
                  <option value="FULLY_RECEIVED">全部到货</option>
                  <option value="OVERDUE">逾期未到</option>
                  <option value="CANCELLED">取消</option>
                </select>
              </FormField>
              <FormField id="arrival-overdue" label="只看逾期">
                <label className="flex h-9 items-center gap-2 text-sm">
                  <input
                    id="arrival-overdue"
                    type="checkbox"
                    checked={overdueOnly}
                    onChange={(e) => setOverdueOnly(e.target.checked)}
                  />
                  只看逾期未到
                </label>
              </FormField>
              <FormField id="arrival-transit" label="只看在途">
                <label className="flex h-9 items-center gap-2 text-sm">
                  <input
                    id="arrival-transit"
                    type="checkbox"
                    checked={inTransitOnly}
                    onChange={(e) => setInTransitOnly(e.target.checked)}
                  />
                  只看已发货（在途）
                </label>
              </FormField>
            </div>
            <div className="mt-3 flex gap-2">
              <Button onClick={applyFilters}>应用筛选</Button>
              <Button
                variant="ghost"
                onClick={() => {
                  setKeyword('');
                  setStatus('');
                  setOverdueOnly(false);
                  setInTransitOnly(false);
                  setParams({ page: 0, size: 20 });
                }}
              >
                重置
              </Button>
            </div>
          </SectionCard>

          {data && (
            <p className="text-muted-foreground text-copy-12">
              逾期天数按判定时刻 {fmtEvaluatedAt(data.evaluatedAt)} 计算（后端取数据库时钟）。
              {data.overdueRuleNote}
            </p>
          )}

          {error && (
            <div
              role="alert"
              className="border-destructive bg-destructive-soft text-destructive rounded-md border px-4 py-3 text-sm"
            >
              {error}
            </div>
          )}

          {loading && <p className="text-muted-foreground text-sm">正在加载到货跟踪…</p>}

          {!loading && rows.length === 0 && !error && (
            <EmptyState
              title="还没有到货跟踪单"
              description="新建一张跟踪单，把采购单的承诺到货日与可收数量登记进来，逾期才会自动预警。"
              action={
                <Button onClick={() => setCreating(true)}>
                  <PackageCheck className="h-4 w-4" />
                  新建跟踪单
                </Button>
              }
            />
          )}

          {!loading && rows.length > 0 && (
            <SectionCard title="到货跟踪列表" description="点一行看逐项展开的详情。">
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground border-border border-b text-left">
                      <th className="p-2 font-medium">采购单号</th>
                      <th className="p-2 font-medium">供应商</th>
                      <th className="p-2 font-medium">可收</th>
                      <th className="p-2 font-medium">已收</th>
                      <th className="p-2 font-medium">未收</th>
                      <th className="p-2 font-medium">状态</th>
                      <th className="p-2 font-medium">承诺到货日</th>
                      <th className="p-2 font-medium">逾期</th>
                      <th className="p-2 font-medium">到货次数</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr
                        key={row.id}
                        className="border-border hover:bg-muted/50 cursor-pointer border-b last:border-0"
                        onClick={() => void openDetail(row.id)}
                      >
                        <td className="p-2">
                          <div className="font-medium">{row.purchaseOrderNo}</div>
                          <div className="text-muted-foreground text-copy-12">{row.trackingNo}</div>
                        </td>
                        <td className="p-2">{supplierText(row)}</td>
                        <td className="p-2">{fmtQty(row.receivableQty)}</td>
                        <td className="p-2">
                          {fmtQty(row.receivedQty)}
                          <span className="text-muted-foreground text-copy-12">
                            （{fmtPercent(row.receivedRatio)}）
                          </span>
                        </td>
                        <td className="p-2">{fmtQty(row.outstandingQty)}</td>
                        <td className="p-2">
                          <Badge tone={arrivalStatusTone(row.status)}>
                            {row.statusLabel || arrivalStatusLabel(row.status)}
                          </Badge>
                        </td>
                        <td className="p-2">
                          <div>{fmtDate(row.promisedArrivalDate)}</div>
                          <div className="text-muted-foreground text-copy-12">
                            {promiseCountdownText(row)}
                          </div>
                        </td>
                        <td className="p-2">
                          <span
                            className={
                              row.overdueDays > 0 ? 'text-destructive font-medium' : undefined
                            }
                          >
                            {overdueDaysText(row)}
                          </span>
                        </td>
                        <td className="p-2">{row.arrivalCount} 次</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="text-muted-foreground text-copy-12 mt-3 flex items-center justify-between">
                <span>共 {data?.total ?? 0} 单（汇总不受分页影响）</span>
                <div className="flex gap-2">
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={(params.page ?? 0) <= 0 || loading}
                    onClick={() => setParams((p) => ({ ...p, page: (p.page ?? 0) - 1 }))}
                  >
                    上一页
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={
                      loading ||
                      (params.page ?? 0) + 1 >= Math.ceil((data?.total ?? 0) / (params.size ?? 20))
                    }
                    onClick={() => setParams((p) => ({ ...p, page: (p.page ?? 0) + 1 }))}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            </SectionCard>
          )}
        </>
      ) : (
        <>
          {error && (
            <div
              role="alert"
              className="border-destructive bg-destructive-soft text-destructive rounded-md border px-4 py-3 text-sm"
            >
              {error}
            </div>
          )}
          {boardLoading && <p className="text-muted-foreground text-sm">正在加载逾期预警…</p>}
          {!boardLoading && board && board.rows.length === 0 && (
            <EmptyState
              title="没有需要预警的跟踪单"
              description="逾期未到与在途滞留两类预警都为空。逾期按承诺到货日判定，在途不判逾期。"
            />
          )}
          {!boardLoading && board && board.rows.length > 0 && (
            <>
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                <StatCard label="预警总数" value={board.summary.total} unit="单" />
                <StatCard
                  label="逾期未到"
                  value={board.summary.overdueCount}
                  unit="单"
                  polarity={board.summary.overdueCount > 0 ? 'negative' : 'neutral'}
                />
                <StatCard
                  label="在途滞留"
                  value={board.summary.inTransitStalledCount}
                  unit="单"
                  polarity="neutral"
                />
                <StatCard label="最长逾期" value={fmtDays(board.summary.maxOverdueDays)} />
              </div>
              <p className="text-muted-foreground text-copy-12">
                判定时刻 {fmtEvaluatedAt(board.evaluatedAt)}（后端取数据库时钟）。
                {board.ruleNote}
              </p>
              <SectionCard title="预警清单" description="每条预警都带处置建议与推导依据。">
                <div className="space-y-3">
                  {board.rows.map((row) => (
                    <div key={row.id} className="border-border rounded-md border p-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone={row.alertType === 'OVERDUE' ? 'danger' : 'warning'}>
                          {row.alertTypeLabel || alertTypeLabel(row.alertType)}
                        </Badge>
                        <span className="font-medium">{row.purchaseOrderNo}</span>
                        <span className="text-muted-foreground text-copy-12">
                          {supplierText(row)}
                        </span>
                        <span className="text-muted-foreground text-copy-12">
                          承诺到货日 {fmtDate(row.promisedArrivalDate)}
                        </span>
                        <span className="text-muted-foreground text-copy-12">
                          逾期 {fmtDays(row.overdueDays)} · 滞留 {fmtDays(row.inTransitDwellDays)}
                        </span>
                        <span className="text-muted-foreground text-copy-12">
                          缺口 {fmtQty(row.outstandingQty)}（{fmtPercent(row.gapRatio)}）
                        </span>
                      </div>
                      {row.dispositions.length === 0 ? (
                        <p className="text-muted-foreground text-copy-12 mt-2">
                          这条预警暂时没有可给的处置建议。
                        </p>
                      ) : (
                        <ul className="mt-2 space-y-2">
                          {row.dispositions.map((d) => (
                            <DispositionLine key={d.id ?? d.actionType} disposition={d} />
                          ))}
                        </ul>
                      )}
                    </div>
                  ))}
                </div>
              </SectionCard>
            </>
          )}
        </>
      )}

      {detail && (
        <ArrivalDetailModal
          detail={detail}
          busy={busy}
          onClose={() => setDetail(null)}
          onRegister={() => setArrivalFor(detail)}
          onShip={() => setShipFor(detail)}
          onCancel={() => setCancelFor(detail)}
          onRun={run}
        />
      )}

      {shipFor && (
        <ShipModal
          detail={shipFor}
          busy={busy}
          onClose={() => setShipFor(null)}
          onSubmit={(shipmentCode, waybillNo, shippedAt, promised) =>
            run(async () => {
              const next = await markShipped(shipFor.head.id, {
                expectedVersion: shipFor.head.version,
                shipmentCode,
                waybillNo,
                shippedAt,
                promisedArrivalDate: promised || null,
                promiseSource: shipFor.head.promiseSource,
              });
              return next;
            })
          }
        />
      )}

      {cancelFor && (
        <CancelModal
          detail={cancelFor}
          busy={busy}
          onClose={() => setCancelFor(null)}
          onSubmit={(reason) =>
            run(async () => {
              const next = await cancelArrival(cancelFor.head.id, {
                expectedVersion: cancelFor.head.version,
                reason,
              });
              return next;
            })
          }
        />
      )}

      {arrivalFor && (
        <RegisterArrivalModal
          detail={arrivalFor}
          busy={busy}
          onClose={() => setArrivalFor(null)}
          onSubmit={(qty, arrivedAt, receiptCode, note) =>
            run(async () => {
              const next = await registerArrival(arrivalFor.head.id, {
                expectedVersion: arrivalFor.head.version,
                arrivedQty: qty,
                arrivedAt,
                warehouseReceiptCode: receiptCode || null,
                waybillNo: '',
                note,
              });
              return next;
            })
          }
        />
      )}

      {creating && (
        <CreateArrivalModal
          busy={busy}
          onClose={() => setCreating(false)}
          onSubmit={async (input) => {
            const next = await createArrival(input);
            setCreating(false);
            await load();
            setDetail(next);
            return next;
          }}
        />
      )}

      {overdueSection && (
        <SectionCard title="逾期口径" description="来自后端能力声明（界面不自造解释）">
          <p className="text-muted-foreground text-copy-12">{overdueSection.detail}</p>
        </SectionCard>
      )}

      {payableSection && (
        <SectionCard title="应付部分确认口径" description="来自后端能力声明">
          <p className="text-muted-foreground text-copy-12">{payableSection.detail}</p>
        </SectionCard>
      )}
    </div>
  );
}

/** 一条处置建议：动作名 + 人话依据 + 依据里的数字。 */
function DispositionLine({ disposition }: { disposition: PurchaseArrivalDispositionRow }) {
  const facts = basisFactEntries(disposition.basisFacts);
  return (
    <li className="border-border bg-muted/40 rounded-md border px-3 py-2">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={severityTone(disposition.severity)}>
          {disposition.actionTypeLabel || dispositionActionLabel(disposition.actionType)}
        </Badge>
        <span className="text-muted-foreground text-copy-12">
          {severityLabel(disposition.severity)}
        </span>
        {disposition.dispositionStatus !== 'OPEN' && (
          <Badge tone="neutral">
            {disposition.dispositionStatus === 'ADOPTED' ? '已采纳' : '已忽略'}
          </Badge>
        )}
      </div>
      <p className="text-copy-13 mt-1">{disposition.basisNote}</p>
      {facts.length > 0 && (
        <div className="text-muted-foreground text-copy-12 mt-1 flex flex-wrap gap-x-4 gap-y-1">
          {facts.map((f) => (
            <span key={f.key}>
              {f.label} {f.value}
            </span>
          ))}
        </div>
      )}
    </li>
  );
}

/** 详情弹窗：逐项展开可收 / 已收 / 未收 / 到货流水 / 承诺到货日 / 逾期天数 / 建议与依据。 */
function ArrivalDetailModal({
  detail,
  busy,
  onClose,
  onRegister,
  onShip,
  onCancel,
  onRun,
}: {
  detail: PurchaseArrivalDetail;
  busy: boolean;
  onClose: () => void;
  onRegister: () => void;
  onShip: () => void;
  onCancel: () => void;
  onRun: (work: () => Promise<PurchaseArrivalDetail | null>) => Promise<void>;
}) {
  const head = detail.head;
  const [note, setNote] = useState('');
  const [financeRef, setFinanceRef] = useState('');
  const [rejectReason, setRejectReason] = useState('');
  const [tab, setTab] = useState<'overview' | 'receipts' | 'payable' | 'dispositions' | 'events'>(
    'overview',
  );

  const act = (action: string) => detail.allowedActions.find((a) => a.action === action);

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-w-3xl">
        <ModalHeader>
          <ModalTitle>到货跟踪 {head.purchaseOrderNo}</ModalTitle>
          <ModalDescription>
            {head.trackingNo} · {supplierText(head)} ·{' '}
            <Badge tone={arrivalStatusTone(head.status)}>
              {head.statusLabel || arrivalStatusLabel(head.status)}
            </Badge>
          </ModalDescription>
        </ModalHeader>

        <Segmented
          value={tab}
          onChange={setTab}
          aria-label="详情分区"
          options={[
            { value: 'overview', label: '总览' },
            { value: 'receipts', label: `到货流水（${detail.receipts.length}）` },
            { value: 'payable', label: `应付确认（${detail.payableConfirmations.length}）` },
            { value: 'dispositions', label: `处置建议（${detail.dispositions.length}）` },
            { value: 'events', label: `留痕（${detail.events.length}）` },
          ]}
        />

        <div className="space-y-3">
          {tab === 'overview' && (
            <>
              <div className="grid gap-3 sm:grid-cols-3">
                <StatCard label="可收数量" value={fmtQty(head.receivableQty)} />
                <StatCard
                  label="已收数量"
                  value={fmtQty(head.receivedQty)}
                  delta={Math.round(head.receivedRatio * 100)}
                  deltaDirection="up"
                  comparisonLabel={fmtPercent(head.receivedRatio)}
                />
                <StatCard label="未收数量" value={fmtQty(head.outstandingQty)} />
              </div>
              <dl className="grid gap-2 text-sm sm:grid-cols-2">
                <Row label="承诺到货日" value={fmtDate(head.promisedArrivalDate)} />
                <Row label="距承诺日" value={promiseCountdownText(head)} />
                <Row label="逾期" value={overdueDaysText(head)} />
                <Row label="判定时刻" value={fmtEvaluatedAt(detail.evaluatedAt)} />
                <Row label="到货次数" value={`${head.arrivalCount} 次`} />
                <Row label="单价" value={fmtMoney(head.unitPrice, head.currency)} />
                <Row label="在途滞留" value={dwellText(head)} />
                <Row label="头程发运单" value={head.inTransitShipmentCode || '未登记'} />
                <Row label="承诺来源" value={head.promiseSource || '未登记'} />
                <Row label="来源单据" value={head.sourceDocNo || '未登记'} />
                <Row
                  label="待财务确认应付"
                  value={fmtMoney(head.pendingPayableAmount, head.currency)}
                />
                <Row
                  label="财务已确认应付"
                  value={fmtMoney(head.confirmedPayableAmount, head.currency)}
                />
              </dl>
              <p className="text-muted-foreground text-copy-12">{detail.upstreamNote}</p>
            </>
          )}

          {tab === 'receipts' && (
            <>
              {detail.receipts.length === 0 ? (
                <p className="text-muted-foreground text-sm">
                  这张跟踪单还没有到货记录。登记一次到货后，已收数量会立即更新，
                  并同时落一笔应付部分确认。
                </p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="text-muted-foreground border-border border-b text-left">
                        <th className="p-2 font-medium">序号</th>
                        <th className="p-2 font-medium">类型</th>
                        <th className="p-2 font-medium">到货数量</th>
                        <th className="p-2 font-medium">到货时刻</th>
                        <th className="p-2 font-medium">仓库收货单</th>
                        <th className="p-2 font-medium">备注</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detail.receipts.map((r) => (
                        <tr key={r.id} className="border-border border-b last:border-0">
                          <td className="p-2">第 {r.arrivalSeq} 笔</td>
                          <td className="p-2">
                            <Badge tone={r.arrivalType === 'FULL' ? 'success' : 'info'}>
                              {r.arrivalTypeLabel}
                            </Badge>
                          </td>
                          <td className="p-2">{fmtQty(r.arrivedQty)}</td>
                          <td className="p-2">{fmtEvaluatedAt(r.arrivedAt)}</td>
                          <td className="p-2">
                            {r.warehouseReceiptCode ? (
                              <span>
                                {r.warehouseReceiptCode}
                                <span className="text-muted-foreground text-copy-12">
                                  （仓库实收 {fmtQty(r.warehouseReceiptReceivedQty)}）
                                </span>
                              </span>
                            ) : (
                              <span className="text-muted-foreground">未关联仓库收货单</span>
                            )}
                          </td>
                          <td className="p-2">{r.note || '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          )}

          {tab === 'payable' && (
            <>
              {detail.payableConfirmations.length === 0 ? (
                <p className="text-muted-foreground text-sm">
                  还没有应付部分确认。登记到货时会自动落一笔 —— 金额由后端按单价快照 ×
                  本次到货数量算。
                </p>
              ) : (
                <div className="space-y-3">
                  {detail.payableConfirmations.map((c) => (
                    <div key={c.id} className="border-border rounded-md border p-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium">{c.confirmationNo}</span>
                        <Badge
                          tone={
                            c.confirmationStatus === 'CONFIRMED'
                              ? 'success'
                              : c.confirmationStatus === 'REJECTED'
                                ? 'danger'
                                : 'warning'
                          }
                        >
                          {c.confirmationStatusLabel || payableStatusLabel(c.confirmationStatus)}
                        </Badge>
                      </div>
                      <dl className="mt-2 grid gap-2 text-sm sm:grid-cols-2">
                        <Row label="确认金额" value={fmtMoney(c.confirmationAmount, c.currency)} />
                        <Row label="已生效金额" value={fmtMoney(c.effectiveAmount, c.currency)} />
                        <Row label="本次到货" value={fmtQty(c.arrivedQty)} />
                        <Row label="单价" value={fmtMoney(c.unitPrice, c.currency)} />
                        {c.financeReference && (
                          <Row label="财务单据号" value={c.financeReference} />
                        )}
                        {c.rejectReason && <Row label="驳回理由" value={c.rejectReason} />}
                      </dl>
                      <p className="text-muted-foreground text-copy-12 mt-1">{c.basisNote}</p>
                      {c.confirmationStatus === 'PENDING_FINANCE_CONFIRM' && (
                        <div className="mt-2 flex flex-wrap items-end gap-2">
                          <FormField id={`fin-${c.id}`} label="财务单据号">
                            <Input
                              id={`fin-${c.id}`}
                              value={financeRef}
                              onChange={(e) => setFinanceRef(e.target.value)}
                              placeholder="应付单号"
                            />
                          </FormField>
                          <Button
                            disabled={busy || !financeRef.trim()}
                            onClick={() =>
                              void onRun(() =>
                                confirmPayable(head.id, c.id, {
                                  financeReference: financeRef.trim(),
                                }),
                              )
                            }
                          >
                            财务确认
                          </Button>
                          <FormField id={`rej-${c.id}`} label="驳回理由">
                            <Input
                              id={`rej-${c.id}`}
                              value={rejectReason}
                              onChange={(e) => setRejectReason(e.target.value)}
                              placeholder="写明理由"
                            />
                          </FormField>
                          <Button
                            variant="ghost"
                            disabled={busy || !rejectReason.trim()}
                            onClick={() =>
                              void onRun(() =>
                                rejectPayable(head.id, c.id, { reason: rejectReason.trim() }),
                              )
                            }
                          >
                            财务驳回
                          </Button>
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </>
          )}

          {tab === 'dispositions' && (
            <>
              {detail.dispositions.length === 0 ? (
                <p className="text-muted-foreground text-sm">
                  当前没有处置建议。逾期或在途滞留之后会按事实给出带依据的建议。
                </p>
              ) : (
                <ul className="space-y-2">
                  {detail.dispositions.map((d) => (
                    <li key={d.id ?? d.actionType} className="border-border rounded-md border p-3">
                      <DispositionLine disposition={d} />
                      {d.dispositionStatus === 'OPEN' && d.id && (
                        <div className="mt-2 flex flex-wrap items-end gap-2">
                          <FormField id={`note-${d.id}`} label="处理说明">
                            <Input
                              id={`note-${d.id}`}
                              value={note}
                              onChange={(e) => setNote(e.target.value)}
                              placeholder="写明处理方式"
                            />
                          </FormField>
                          <Button
                            size="sm"
                            disabled={busy || !note.trim()}
                            onClick={() =>
                              void onRun(() =>
                                resolveDisposition(head.id, d.id as string, {
                                  dispositionStatus: 'ADOPTED',
                                  note: note.trim(),
                                }),
                              )
                            }
                          >
                            采纳
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            disabled={busy || !note.trim()}
                            onClick={() =>
                              void onRun(() =>
                                resolveDisposition(head.id, d.id as string, {
                                  dispositionStatus: 'DISMISSED',
                                  note: note.trim(),
                                }),
                              )
                            }
                          >
                            忽略
                          </Button>
                        </div>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}

          {tab === 'events' && (
            <div className="space-y-2">
              {detail.events.length === 0 ? (
                <p className="text-muted-foreground text-sm">还没有留痕。</p>
              ) : (
                detail.events.map((e) => (
                  <div key={e.id} className="border-border rounded-md border px-3 py-2 text-sm">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-medium">{e.eventTypeLabel}</span>
                      <span className="text-muted-foreground text-copy-12">
                        {fmtEvaluatedAt(e.occurredAt)}
                      </span>
                      {e.fromStatus && e.toStatus && (
                        <span className="text-muted-foreground text-copy-12">
                          {arrivalStatusLabel(e.fromStatus)} → {arrivalStatusLabel(e.toStatus)}
                        </span>
                      )}
                      {e.quantity && (
                        <span className="text-muted-foreground text-copy-12">
                          数量 {fmtQty(e.quantity)}
                        </span>
                      )}
                      {e.amount && (
                        <span className="text-muted-foreground text-copy-12">
                          金额 {fmtMoney(e.amount)}
                        </span>
                      )}
                    </div>
                    {e.reasonNote && (
                      <p className="text-muted-foreground text-copy-12 mt-1">{e.reasonNote}</p>
                    )}
                  </div>
                ))
              )}
            </div>
          )}
        </div>

        <ModalFooter>
          {act('REGISTER_ARRIVAL')?.enabled && (
            <Button onClick={onRegister} disabled={busy}>
              <PackageCheck className="h-4 w-4" />
              登记到货
            </Button>
          )}
          {act('MARK_SHIPPED')?.enabled && (
            <Button variant="ghost" onClick={onShip} disabled={busy}>
              <Truck className="h-4 w-4" />
              标记已发货（在途）
            </Button>
          )}
          {act('SYNC_IN_TRANSIT')?.enabled && (
            <Button
              variant="ghost"
              disabled={busy}
              onClick={() => void onRun(() => syncInTransit(head.id))}
            >
              同步在途状态
            </Button>
          )}
          {act('CANCEL')?.enabled && (
            <Button variant="ghost" onClick={onCancel} disabled={busy}>
              取消跟踪单
            </Button>
          )}
          <Button variant="ghost" onClick={onClose}>
            关闭
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

/** 登记到货弹窗：数量由人填，「部分还是全部」由后端按缺口判定。 */
function RegisterArrivalModal({
  detail,
  busy,
  onClose,
  onSubmit,
}: {
  detail: PurchaseArrivalDetail;
  busy: boolean;
  onClose: () => void;
  onSubmit: (qty: number, arrivedAt: string, receiptCode: string, note: string) => Promise<unknown>;
}) {
  const head = detail.head;
  const [qty, setQty] = useState('');
  const [arrivedAt, setArrivedAt] = useState('');
  const [receiptCode, setReceiptCode] = useState('');
  const [note, setNote] = useState('');
  const [err, setErr] = useState<string | null>(null);

  const parsed = Number(qty);
  const valid = Number.isFinite(parsed) && parsed > 0 && parsed <= Number(head.outstandingQty);

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>登记到货</ModalTitle>
          <ModalDescription>
            {head.purchaseOrderNo} · 未收 {fmtQty(head.outstandingQty)}
          </ModalDescription>
        </ModalHeader>

        <div className="space-y-3">
          <FormField
            id="arrived-qty"
            label="本次到货数量"
            required
            hint={`不得超过未收数量 ${fmtQty(head.outstandingQty)}。填多少就是多少 —— 本次是「部分到货」还是「全部到货」由后端按缺口判定，不用在这里选。`}
            error={err ?? undefined}
          >
            <Input
              id="arrived-qty"
              type="number"
              min="0"
              step="0.000001"
              value={qty}
              onChange={(e) => {
                setQty(e.target.value);
                setErr(null);
              }}
            />
          </FormField>
          <FormField
            id="arrived-at"
            label="到货时刻"
            hint="留空按当前时刻登记；补录旧到货时填实际到货时刻。"
          >
            <Input
              id="arrived-at"
              type="datetime-local"
              value={arrivedAt}
              onChange={(e) => setArrivedAt(e.target.value)}
            />
          </FormField>
          <FormField
            id="arrived-receipt"
            label="仓库收货单号"
            hint="可选。填了会向仓库（8.2.1.1）核对这张单确实存在并冻结实收量快照；填错会直接报错，不会替你编一张。"
          >
            <Input
              id="arrived-receipt"
              value={receiptCode}
              onChange={(e) => setReceiptCode(e.target.value)}
            />
          </FormField>
          <FormField id="arrived-note" label="备注">
            <Input
              id="arrived-note"
              value={note}
              onChange={(e) => setNote(e.target.value)}
              placeholder="例如：首批到货 / 外箱轻微压损"
            />
          </FormField>
          <p className="text-muted-foreground text-copy-12">
            登记后**立即**更新已收数量，并同时落一笔应付部分确认（状态「待财务确认」， 生效金额 0 ——
            下游 12 财务尚未交付，本模块不返回「已触发财务确认」这种假回执）。
          </p>
        </div>

        <ModalFooter>
          <Button
            disabled={busy || !valid}
            onClick={() => {
              if (!valid) {
                setErr(`到货数量必须大于 0 且不超过 ${fmtQty(head.outstandingQty)}`);
                return;
              }
              void onSubmit(
                parsed,
                arrivedAt ? new Date(arrivedAt).toISOString() : new Date().toISOString(),
                receiptCode.trim(),
                note.trim(),
              );
            }}
          >
            登记到货
          </Button>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

/** 新建跟踪单弹窗。 */
function CreateArrivalModal({
  busy,
  onClose,
  onSubmit,
}: {
  busy: boolean;
  onClose: () => void;
  onSubmit: (input: {
    purchaseOrderNo: string;
    sourceDocNo: string;
    sourceDocType: string;
    supplierRefId: string;
    supplierCode: string;
    supplierName: string;
    receivableQty: number;
    unitPrice: number;
    currency: string;
    promisedArrivalDate: string;
    promiseSource: string;
  }) => Promise<unknown>;
}) {
  const [form, setForm] = useState({
    purchaseOrderNo: '',
    sourceDocNo: '',
    sourceDocType: 'PURCHASE_ORDER',
    supplierRefId: '',
    supplierCode: '',
    supplierName: '',
    receivableQty: '',
    unitPrice: '',
    currency: 'USD',
    promisedArrivalDate: '',
    promiseSource: '',
  });
  const [err, setErr] = useState<string | null>(null);

  const set = (key: keyof typeof form) => (value: string) =>
    setForm((f) => ({ ...f, [key]: value }));

  const submit = () => {
    if (!form.purchaseOrderNo.trim()) {
      setErr('必须给出采购单号 —— 这是跟单时唯一能拿到的锚点');
      return;
    }
    if (!form.supplierRefId.trim()) {
      setErr('必须给出供应商标识（供应商主数据在 catalog 独立库，这里只存逻辑引用）');
      return;
    }
    if (!Number(form.receivableQty) || Number(form.receivableQty) <= 0) {
      setErr('必须给出可收数量');
      return;
    }
    if (!form.promisedArrivalDate) {
      setErr('必须给出承诺到货日 —— 逾期判定全靠它');
      return;
    }
    setErr(null);
    void onSubmit({
      purchaseOrderNo: form.purchaseOrderNo.trim(),
      sourceDocNo: form.sourceDocNo.trim(),
      sourceDocType: form.sourceDocType,
      supplierRefId: form.supplierRefId.trim(),
      supplierCode: form.supplierCode.trim(),
      supplierName: form.supplierName.trim(),
      receivableQty: Number(form.receivableQty),
      unitPrice: Number(form.unitPrice || 0),
      currency: form.currency.trim().toUpperCase(),
      promisedArrivalDate: form.promisedArrivalDate,
      promiseSource: form.promiseSource.trim(),
    });
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-w-2xl">
        <ModalHeader>
          <ModalTitle>新建到货跟踪单</ModalTitle>
          <ModalDescription>
            6.3.1.x 采购单与 6.3.2.1 跟单**尚未交付** ⇒ 这里用逻辑引用（采购单号 / 来源单据号 /
            供应商标识）定位被跟踪的采购单，不建指向不存在的表的 FK。
          </ModalDescription>
        </ModalHeader>

        <div className="grid gap-3 sm:grid-cols-2">
          <FormField id="create-po" label="采购单号" required>
            <Input
              id="create-po"
              value={form.purchaseOrderNo}
              onChange={(e) => set('purchaseOrderNo')(e.target.value)}
            />
          </FormField>
          <FormField id="create-source" label="来源单据号" hint="采购申请号 / 跟单号">
            <Input
              id="create-source"
              value={form.sourceDocNo}
              onChange={(e) => set('sourceDocNo')(e.target.value)}
            />
          </FormField>
          <FormField id="create-supplier-id" label="供应商标识（uuid）" required>
            <Input
              id="create-supplier-id"
              value={form.supplierRefId}
              onChange={(e) => set('supplierRefId')(e.target.value)}
            />
          </FormField>
          <FormField id="create-supplier-code" label="供应商编码">
            <Input
              id="create-supplier-code"
              value={form.supplierCode}
              onChange={(e) => set('supplierCode')(e.target.value)}
            />
          </FormField>
          <FormField id="create-supplier-name" label="供应商名称">
            <Input
              id="create-supplier-name"
              value={form.supplierName}
              onChange={(e) => set('supplierName')(e.target.value)}
            />
          </FormField>
          <FormField
            id="create-promise"
            label="承诺到货日"
            required
            hint="跟单 / 采购单带来的输入事实"
          >
            <Input
              id="create-promise"
              type="date"
              value={form.promisedArrivalDate}
              onChange={(e) => set('promisedArrivalDate')(e.target.value)}
            />
          </FormField>
          <FormField id="create-promise-source" label="承诺来源" hint="跟单号 / 采购单号">
            <Input
              id="create-promise-source"
              value={form.promiseSource}
              onChange={(e) => set('promiseSource')(e.target.value)}
            />
          </FormField>
          <FormField id="create-qty" label="可收数量" required>
            <Input
              id="create-qty"
              type="number"
              min="0"
              step="0.000001"
              value={form.receivableQty}
              onChange={(e) => set('receivableQty')(e.target.value)}
            />
          </FormField>
          <FormField id="create-price" label="单价" hint="应付部分确认的依据（NUMERIC(19,6)）">
            <Input
              id="create-price"
              type="number"
              min="0"
              step="0.000001"
              value={form.unitPrice}
              onChange={(e) => set('unitPrice')(e.target.value)}
            />
          </FormField>
          <FormField id="create-currency" label="币种" required>
            <Input
              id="create-currency"
              value={form.currency}
              onChange={(e) => set('currency')(e.target.value)}
            />
          </FormField>
        </div>

        {err && (
          <p role="alert" className="text-destructive text-copy-12">
            {err}
          </p>
        )}

        <ModalFooter>
          <Button disabled={busy} onClick={submit}>
            创建
          </Button>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

/** 标记已发货（在途）弹窗。 */
function ShipModal({
  detail,
  busy,
  onClose,
  onSubmit,
}: {
  detail: PurchaseArrivalDetail;
  busy: boolean;
  onClose: () => void;
  onSubmit: (
    shipmentCode: string,
    waybillNo: string,
    shippedAt: string,
    promised: string,
  ) => Promise<unknown>;
}) {
  const head = detail.head;
  const [shipmentCode, setShipmentCode] = useState('');
  const [waybillNo, setWaybillNo] = useState('');
  const [shippedAt, setShippedAt] = useState('');
  const [promised, setPromised] = useState(head.promisedArrivalDate ?? '');
  const [err, setErr] = useState<string | null>(null);

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>标记已发货（在途）</ModalTitle>
          <ModalDescription>
            {head.purchaseOrderNo} · 承诺到货日 {fmtDate(head.promisedArrivalDate)}
          </ModalDescription>
        </ModalHeader>

        <div className="space-y-3">
          <FormField
            id="ship-code"
            label="头程发运单号"
            hint="10.4.1.1 的发运单号 —— 在途跟踪（10.4.1.2）按它定位，填了才能同步滞留天数。"
          >
            <Input
              id="ship-code"
              value={shipmentCode}
              onChange={(e) => setShipmentCode(e.target.value)}
            />
          </FormField>
          <FormField id="ship-waybill" label="运单号 / 订舱号">
            <Input
              id="ship-waybill"
              value={waybillNo}
              onChange={(e) => setWaybillNo(e.target.value)}
            />
          </FormField>
          <FormField id="ship-at" label="发货时刻" hint="留空按当前时刻登记。">
            <Input
              id="ship-at"
              type="datetime-local"
              value={shippedAt}
              onChange={(e) => setShippedAt(e.target.value)}
            />
          </FormField>
          <FormField
            id="ship-promise"
            label="承诺到货日"
            hint="跟单改了交期就改这里（改完会以新的承诺日重算逾期）。"
          >
            <Input
              id="ship-promise"
              type="date"
              value={promised}
              onChange={(e) => setPromised(e.target.value)}
            />
          </FormField>
          <p className="text-muted-foreground text-copy-12">
            ⚠️ **已发货在途不判逾期** —— 还在路上，判逾期会误伤。承诺日过后的滞留由
            「在途滞留预警」单独提醒。
          </p>
        </div>

        {err && (
          <p role="alert" className="text-destructive text-copy-12">
            {err}
          </p>
        )}

        <ModalFooter>
          <Button
            disabled={busy}
            onClick={() => {
              setErr(null);
              void onSubmit(
                shipmentCode.trim(),
                waybillNo.trim(),
                shippedAt ? new Date(shippedAt).toISOString() : new Date().toISOString(),
                promised,
              );
            }}
          >
            标记已发货
          </Button>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

/** 取消跟踪单弹窗：必须写理由。 */
function CancelModal({
  detail,
  busy,
  onClose,
  onSubmit,
}: {
  detail: PurchaseArrivalDetail;
  busy: boolean;
  onClose: () => void;
  onSubmit: (reason: string) => Promise<unknown>;
}) {
  const [reason, setReason] = useState('');
  const [err, setErr] = useState<string | null>(null);

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>取消跟踪单</ModalTitle>
          <ModalDescription>
            {detail.head.purchaseOrderNo} · 取消是终态，**不可再流转**。
          </ModalDescription>
        </ModalHeader>

        <FormField
          id="cancel-reason"
          label="取消理由"
          required
          hint="没有理由的取消等于把「这单为什么黄了」抹掉 —— 到货流水与留痕会一直留着。"
          error={err ?? undefined}
        >
          <Input
            id="cancel-reason"
            value={reason}
            onChange={(e) => {
              setReason(e.target.value);
              setErr(null);
            }}
            placeholder="例如：供应商停业，改由另一家供货"
          />
        </FormField>

        <ModalFooter>
          <Button
            variant="ghost"
            disabled={busy || !reason.trim()}
            onClick={() => {
              if (!reason.trim()) {
                setErr('必须写取消理由');
                return;
              }
              void onSubmit(reason.trim());
            }}
          >
            取消跟踪单
          </Button>
          <Button variant="ghost" onClick={onClose}>
            返回
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

/** 一行「标签 / 值」。 */
function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline gap-2">
      <dt className="text-muted-foreground text-copy-12 w-32 shrink-0">{label}</dt>
      <dd className="min-w-0 break-words">{value}</dd>
    </div>
  );
}

/**
 * 去掉值为 undefined 的键。
 *
 * <p>本仓 TS 开了 {@code exactOptionalPropertyTypes}：可选属性**不能**被显式赋成
 * {@code undefined}（那与「不传这个参数」在语义上不同）。所以筛选条件清空时
 * 只能把键整个删掉，而不是置成 undefined。
 */
function compact<T extends object>(value: Record<string, unknown>): T {
  return Object.fromEntries(Object.entries(value).filter(([, v]) => v !== undefined)) as T;
}
