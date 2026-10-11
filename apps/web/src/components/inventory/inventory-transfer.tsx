'use client';

import { Fragment, useCallback, useEffect, useMemo, useState } from 'react';
import { ArrowRightLeft, PackageCheck, Plus, RefreshCw, TriangleAlert, Truck } from 'lucide-react';
import {
  Button,
  EmptyState,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  TransferOrderDetail,
  TransferOrderSaved,
} from '../../../../../packages/api-contracts/src/index';
import {
  closeTransfer,
  confirmTransfer,
  conservationText,
  createTransfer,
  fetchTransferDetail,
  fetchTransferMovements,
  fetchTransfers,
  fmt,
  moment,
  receivable,
  receiveTransfer,
  routeText,
  shippable,
  shipTransfer,
  transferActions,
  transferDiscrepancyLabels,
  transferSortOptions,
  transferStatusLabels,
  type TransferParams,
} from './inventory-transfer-model';
import { errorText, fetchSkus, fetchWarehouses, fetchQualityStatuses } from './inventory-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.5.1.1 调拨单工作区。
 *
 * <p>蓝图：「发起仓库间调拨：选择源仓与目标仓、调拨 SKU 与数量、预计发出与到达时间，
 * 跟踪发出、在途、目标实收」，输出「调拨单（草稿/待发/在途/部分收/已收/差异）」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li>**在途是独立的一列**，不是「已发出」的另一种说法。蓝图明确「调拨不是瞬时」：
 *       发出后货在去目的仓的路上，这段时间货既不在源仓、也不在目的仓 ——
 *       把这几列并成一列就是在界面上犯「异地收发同一瞬间」的错。
 *   <li>**未发出量不是差异**。请求 100 发出 60，那 40 是还没走；
 *       只有「发出过但收不到」才是差异，两者分开显示。
 *   <li>**按钮按状态给**（`transferActions`）：已收/差异是终态，不再显示发货收货，
 *       避免让人以为能重复收。
 *   <li>**乐观锁回显**：每次动作带当前 `version`，被他人改过会拿到 409 并提示刷新。
 * </ul>
 */
export function InventoryTransferWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [page, setPage] = useState<Awaited<ReturnType<typeof fetchTransfers>> | null>(null);
  const [warehouses, setWarehouses] = useState<{ id: string; code: string; name: string }[]>([]);
  const [skus, setSkus] = useState<{ id: string; code: string; name: string }[]>([]);
  const [qualities, setQualities] = useState<{ id: string; code: string; name: string }[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [expanded, setExpanded] = useState<string | null>(null);
  const [detail, setDetail] = useState<TransferOrderDetail | null>(null);
  const [detailError, setDetailError] = useState('');
  const [detailLoading, setDetailLoading] = useState(false);
  const [actionError, setActionError] = useState('');
  const [busy, setBusy] = useState('');

  const [keyword, setKeyword] = useState('');
  const [fromWarehouseId, setFromWarehouseId] = useState('');
  const [toWarehouseId, setToWarehouseId] = useState('');
  const [skuId, setSkuId] = useState('');
  const [status, setStatus] = useState('');
  const [sort, setSort] = useState('CREATED_AT');
  const [pageNumber, setPageNumber] = useState(0);

  const [createOpen, setCreateOpen] = useState(false);
  const [draftFrom, setDraftFrom] = useState('');
  const [draftTo, setDraftTo] = useState('');
  const [draftSku, setDraftSku] = useState('');
  const [draftQuality, setDraftQuality] = useState('');
  const [draftQuantity, setDraftQuantity] = useState('');
  const [draftReason, setDraftReason] = useState('');
  const [shipOpen, setShipOpen] = useState(false);
  const [shipAmounts, setShipAmounts] = useState<Record<string, string>>({});
  const [receiveOpen, setReceiveOpen] = useState(false);
  const [receiveAmounts, setReceiveAmounts] = useState<Record<string, string>>({});
  const [receiveLocation, setReceiveLocation] = useState('');
  const [receiveNotes, setReceiveNotes] = useState('');
  const [closeOpen, setCloseOpen] = useState(false);
  const [closeReason, setCloseReason] = useState('LOST');
  const [closeNotes, setCloseNotes] = useState('');

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    const params: TransferParams = { sort, page: pageNumber, size: 50 };
    if (keyword.trim()) params.keyword = keyword.trim();
    if (fromWarehouseId) params.fromWarehouseId = fromWarehouseId;
    if (toWarehouseId) params.toWarehouseId = toWarehouseId;
    if (skuId) params.skuId = skuId;
    if (status) params.status = [status];
    try {
      setPage(await fetchTransfers(params));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [readable, sort, pageNumber, keyword, fromWarehouseId, toWarehouseId, skuId, status]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!readable) return;
    void Promise.all([fetchWarehouses(), fetchSkus(), fetchQualityStatuses()])
      .then(([w, s, q]) => {
        setWarehouses(w);
        setSkus(s);
        setQualities(q);
      })
      .catch(() => undefined);
  }, [readable]);

  const openDetail = useCallback(async (orderId: string) => {
    setExpanded(orderId);
    setDetail(null);
    setDetailError('');
    setDetailLoading(true);
    try {
      setDetail(await fetchTransferDetail(orderId));
    } catch (e) {
      setDetailError(errorText(e));
    } finally {
      setDetailLoading(false);
    }
  }, []);

  /** 跑一个写动作：统一处理乐观锁 409 的提示与刷新，避免每个按钮各写一遍。 */
  const run = useCallback(
    async (key: string, work: () => Promise<TransferOrderSaved>, after?: () => Promise<void>) => {
      setBusy(key);
      setActionError('');
      try {
        await work();
        await load();
        if (expanded) {
          try {
            setDetail(await fetchTransferDetail(expanded));
            if (after) await after();
          } catch {
            /* 详情刷新失败不覆盖动作结果，列表已刷新 */
          }
        }
      } catch (e) {
        setActionError(errorText(e));
      } finally {
        setBusy('');
      }
    },
    [load, expanded],
  );

  const conservation = useMemo(
    () =>
      conservationText({
        shippedQuantity: page?.summary.shippedQuantity ?? 0,
        receivedQuantity: page?.summary.receivedQuantity ?? 0,
        discrepancyQuantity: page?.summary.discrepancyQuantity ?? 0,
        inTransitQuantity: page?.summary.inTransitQuantity ?? 0,
      }),
    [page],
  );

  const detailConservation = useMemo(
    () =>
      detail
        ? conservationText({
            shippedQuantity: detail.order.shippedQuantity,
            receivedQuantity: detail.order.receivedQuantity,
            discrepancyQuantity: detail.order.discrepancyQuantity,
            inTransitQuantity: detail.order.inTransitQuantity,
          })
        : null,
    [detail],
  );

  if (!readable) {
    return (
      <SectionCard title="调拨单">
        <EmptyState
          title="没有查看权限"
          description="当前角色没有 warehouse.inventory.read，无法查看调拨单。"
        />
      </SectionCard>
    );
  }

  const summary = page?.summary;

  return (
    <div className="space-y-6">
      <PageHeader
        title="调拨单"
        description="仓库间调拨：发出进入在途，目的仓分次收货逐笔转入。调拨不是瞬时。"
        actions={
          <>
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} /> 刷新
            </Button>
            {writable && (
              <Button onClick={() => setCreateOpen(true)}>
                <Plus /> 新建调拨单
              </Button>
            )}
          </>
        }
      />

      {actionError && (
        <div className="flex items-start gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <span>{actionError}</span>
        </div>
      )}

      {summary && (
        <div className="grid gap-4 md:grid-cols-4">
          <StatCard
            label="调拨单"
            value={String(summary.orderCount)}
            comparisonLabel={`${summary.lineCount} 条明细（合计不受分页影响）`}
          />
          <StatCard
            label="已发出"
            value={fmt(summary.shippedQuantity)}
            comparisonLabel={`请求 ${fmt(summary.requestedQuantity)}`}
          />
          <StatCard
            label="仍在途"
            value={fmt(summary.inTransitQuantity)}
            comparisonLabel={`已实收 ${fmt(summary.receivedQuantity)}`}
          />
          <StatCard
            label="差异"
            value={fmt(summary.discrepancyQuantity)}
            comparisonLabel={`${summary.discrepancyOrderCount} 张单有差异 · 未发出量不算差异`}
          />
        </div>
      )}

      {summary && (
        <p className="text-muted-foreground text-xs">守恒式（不受分页影响）：{conservation.text}</p>
      )}

      <SectionCard
        title="调拨单列表"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <input
              className="rounded-md border px-2 py-1 text-sm"
              placeholder="单号 / 原因 / SKU"
              value={keyword}
              onChange={(e) => {
                setKeyword(e.target.value);
                setPageNumber(0);
              }}
            />
            <select
              className="rounded-md border px-2 py-1 text-sm"
              value={fromWarehouseId}
              onChange={(e) => {
                setFromWarehouseId(e.target.value);
                setPageNumber(0);
              }}
            >
              <option value="">全部源仓</option>
              {warehouses.map((w) => (
                <option key={w.id} value={w.id}>
                  {w.code} {w.name}
                </option>
              ))}
            </select>
            <select
              className="rounded-md border px-2 py-1 text-sm"
              value={toWarehouseId}
              onChange={(e) => {
                setToWarehouseId(e.target.value);
                setPageNumber(0);
              }}
            >
              <option value="">全部目标仓</option>
              {warehouses.map((w) => (
                <option key={w.id} value={w.id}>
                  {w.code} {w.name}
                </option>
              ))}
            </select>
            <select
              className="rounded-md border px-2 py-1 text-sm"
              value={skuId}
              onChange={(e) => {
                setSkuId(e.target.value);
                setPageNumber(0);
              }}
            >
              <option value="">全部 SKU</option>
              {skus.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.code} {s.name}
                </option>
              ))}
            </select>
            <select
              className="rounded-md border px-2 py-1 text-sm"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPageNumber(0);
              }}
            >
              <option value="">全部状态</option>
              {(page?.statusOptions ?? []).map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}（{o.orderCount}）
                </option>
              ))}
            </select>
            <select
              className="rounded-md border px-2 py-1 text-sm"
              value={sort}
              onChange={(e) => setSort(e.target.value)}
            >
              {transferSortOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>
        }
      >
        {loading && <p className="text-muted-foreground text-sm">加载中…</p>}
        {!loading && error && <p className="text-destructive text-sm">{error}</p>}
        {!loading && !error && page && page.rows.length === 0 && (
          <EmptyState
            title="还没有调拨单"
            description="点右上角「新建调拨单」发起一次仓库间调拨。"
          />
        )}
        {!loading && !error && page && page.rows.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground border-b text-left">
                  <th className="p-2">单号 / 路线</th>
                  <th className="p-2">状态</th>
                  <th className="p-2 text-right">请求</th>
                  <th className="p-2 text-right">已发出</th>
                  <th className="p-2 text-right">已实收</th>
                  <th className="p-2 text-right">仍在途</th>
                  <th className="p-2 text-right">差异</th>
                  <th className="p-2">预计到达</th>
                  <th className="p-2" />
                </tr>
              </thead>
              <tbody>
                {page.rows.map((row) => (
                  <Fragment key={row.id}>
                    <tr className="border-b">
                      <td className="p-2">
                        <div className="font-medium">{row.orderCode}</div>
                        <div className="text-muted-foreground text-xs">{routeText(row)}</div>
                        {row.reason && (
                          <div className="text-muted-foreground text-xs">{row.reason}</div>
                        )}
                      </td>
                      <td className="p-2">
                        <span className="rounded px-2 py-0.5 text-xs">
                          {transferStatusLabels[row.status] ?? row.status}
                        </span>
                      </td>
                      <td className="p-2 text-right">{fmt(row.requestedQuantity)}</td>
                      <td className="p-2 text-right">{fmt(row.shippedQuantity)}</td>
                      <td className="p-2 text-right">{fmt(row.receivedQuantity)}</td>
                      <td className="p-2 text-right font-medium">{fmt(row.inTransitQuantity)}</td>
                      <td className="p-2 text-right">
                        {Number(row.discrepancyQuantity) > 0 ? (
                          <span className="text-destructive">{fmt(row.discrepancyQuantity)}</span>
                        ) : (
                          fmt(row.discrepancyQuantity)
                        )}
                      </td>
                      <td className="p-2 text-xs">{moment(row.expectedArriveAt)}</td>
                      <td className="p-2 text-right">
                        <Button
                          variant="ghost"
                          onClick={() =>
                            expanded === row.id ? setExpanded(null) : void openDetail(row.id)
                          }
                        >
                          {expanded === row.id ? '收起' : '明细'}
                        </Button>
                      </td>
                    </tr>
                    {expanded === row.id && (
                      <tr>
                        <td colSpan={9} className="bg-muted/30 p-3">
                          {detailLoading && (
                            <p className="text-muted-foreground text-sm">加载明细…</p>
                          )}
                          {!detailLoading && detailError && (
                            <p className="text-destructive text-sm">{detailError}</p>
                          )}
                          {!detailLoading && !detailError && detail && (
                            <DetailPanel
                              detail={detail}
                              writable={writable}
                              busy={busy}
                              conservation={detailConservation?.text ?? ''}
                              conservationOk={detailConservation?.ok ?? true}
                              onConfirm={() =>
                                void run('confirm', () =>
                                  confirmTransfer(detail.order.id, detail.order.version),
                                )
                              }
                              onShip={() => {
                                setShipAmounts(
                                  Object.fromEntries(detail.lines.map((l) => [l.id, ''])),
                                );
                                setShipOpen(true);
                              }}
                              onReceive={() => {
                                setReceiveAmounts(
                                  Object.fromEntries(detail.lines.map((l) => [l.id, ''])),
                                );
                                setReceiveNotes('');
                                setReceiveOpen(true);
                              }}
                              onClose={() => {
                                setCloseReason('LOST');
                                setCloseNotes('');
                                setCloseOpen(true);
                              }}
                            />
                          )}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {page && page.total > page.size && (
          <div className="mt-3 flex items-center gap-2 text-sm">
            <Button
              variant="ghost"
              disabled={pageNumber === 0}
              onClick={() => setPageNumber((p) => p - 1)}
            >
              上一页
            </Button>
            <span>
              第 {page.page + 1} / {Math.max(1, Math.ceil(page.total / page.size))} 页 · 共{' '}
              {page.total} 张
            </span>
            <Button
              variant="ghost"
              disabled={(page.page + 1) * page.size >= page.total}
              onClick={() => setPageNumber((p) => p + 1)}
            >
              下一页
            </Button>
          </div>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 新建 */}
      <Modal open={createOpen} onOpenChange={(open) => !open && setCreateOpen(false)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>新建调拨单</ModalTitle>
            <ModalDescription>
              建单只产生草稿，**不动库存**。货权在「发出」那一刻才离开发货仓。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <label className="block text-sm">
              源仓
              <select
                className="mt-1 w-full rounded-md border px-2 py-1"
                value={draftFrom}
                onChange={(e) => setDraftFrom(e.target.value)}
              >
                <option value="">请选择</option>
                {warehouses.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.code} {w.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-sm">
              目标仓
              <select
                className="mt-1 w-full rounded-md border px-2 py-1"
                value={draftTo}
                onChange={(e) => setDraftTo(e.target.value)}
              >
                <option value="">请选择</option>
                {warehouses.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.code} {w.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-sm">
              SKU
              <select
                className="mt-1 w-full rounded-md border px-2 py-1"
                value={draftSku}
                onChange={(e) => setDraftSku(e.target.value)}
              >
                <option value="">请选择</option>
                {skus.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.code} {s.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-sm">
              质量状态
              <select
                className="mt-1 w-full rounded-md border px-2 py-1"
                value={draftQuality}
                onChange={(e) => setDraftQuality(e.target.value)}
              >
                <option value="">请选择</option>
                {qualities.map((q) => (
                  <option key={q.id} value={q.id}>
                    {q.code} {q.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-sm">
              调拨数量
              <input
                className="mt-1 w-full rounded-md border px-2 py-1"
                value={draftQuantity}
                onChange={(e) => setDraftQuantity(e.target.value)}
                placeholder="例如 100"
              />
            </label>
            <label className="block text-sm">
              调拨原因
              <input
                className="mt-1 w-full rounded-md border px-2 py-1"
                value={draftReason}
                onChange={(e) => setDraftReason(e.target.value)}
                placeholder="例如 补深圳仓可售量"
              />
            </label>
          </div>
          <ModalFooter>
            <Button variant="ghost" onClick={() => setCreateOpen(false)}>
              取消
            </Button>
            <Button
              disabled={busy === 'create'}
              onClick={() =>
                void run('create', async () => {
                  const created = await createTransfer({
                    fromWarehouseId: draftFrom,
                    toWarehouseId: draftTo,
                    legalEntityId: '',
                    expectedShipAt: '',
                    expectedArriveAt: '',
                    reason: draftReason,
                    notes: '',
                    lines: [
                      {
                        skuId: draftSku,
                        qualityStatusId: draftQuality,
                        quantity: Number(draftQuantity),
                      },
                    ],
                  });
                  setCreateOpen(false);
                  setPageNumber(0);
                  return created;
                })
              }
            >
              创建草稿
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------------------------------------------------------- 发出 */}
      <Modal open={shipOpen} onOpenChange={(open) => !open && setShipOpen(false)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>发出</ModalTitle>
            <ModalDescription>
              发出即减源仓在手并挂上在途，**目的仓不会增加**。可只发一部分行或每行的一部分量。
            </ModalDescription>
          </ModalHeader>
          {detail && (
            <div className="space-y-2">
              {detail.lines.map((l) => (
                <div key={l.id} className="flex items-center gap-2 text-sm">
                  <span className="flex-1">
                    {l.skuCode} · {l.qualityStatusName}
                  </span>
                  <span className="text-muted-foreground text-xs">未发 {fmt(shippable(l))}</span>
                  <input
                    className="w-24 rounded-md border px-2 py-1"
                    value={shipAmounts[l.id] ?? ''}
                    onChange={(e) => setShipAmounts({ ...shipAmounts, [l.id]: e.target.value })}
                    placeholder="0"
                  />
                </div>
              ))}
            </div>
          )}
          <ModalFooter>
            <Button variant="ghost" onClick={() => setShipOpen(false)}>
              取消
            </Button>
            <Button disabled={busy === 'ship'} onClick={() => detail && void handleShip(detail)}>
              确认发出
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------------------------------------------------------- 收货 */}
      <Modal open={receiveOpen} onOpenChange={(open) => !open && setReceiveOpen(false)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>实收</ModalTitle>
            <ModalDescription>
              转入目标仓并逐次冲减在途。收货必须指定落库库位（实收才能回答「货到了哪个库位」）。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <label className="block text-sm">
              落库库位 ID
              <input
                className="mt-1 w-full rounded-md border px-2 py-1"
                value={receiveLocation}
                onChange={(e) => setReceiveLocation(e.target.value)}
                placeholder="目的仓下的库位 id"
              />
            </label>
            {detail?.lines.map((l) => (
              <div key={l.id} className="flex items-center gap-2 text-sm">
                <span className="flex-1">
                  {l.skuCode} · {l.qualityStatusName}
                </span>
                <span className="text-muted-foreground text-xs">在途 {fmt(receivable(l))}</span>
                <input
                  className="w-24 rounded-md border px-2 py-1"
                  value={receiveAmounts[l.id] ?? ''}
                  onChange={(e) => setReceiveAmounts({ ...receiveAmounts, [l.id]: e.target.value })}
                  placeholder="0"
                />
              </div>
            ))}
            <label className="block text-sm">
              备注
              <input
                className="mt-1 w-full rounded-md border px-2 py-1"
                value={receiveNotes}
                onChange={(e) => setReceiveNotes(e.target.value)}
              />
            </label>
          </div>
          <ModalFooter>
            <Button variant="ghost" onClick={() => setReceiveOpen(false)}>
              取消
            </Button>
            <Button
              disabled={busy === 'receive'}
              onClick={() => detail && void handleReceive(detail)}
            >
              确认收货
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------------------------------------------------------- 结案 */}
      <Modal open={closeOpen} onOpenChange={(open) => !open && setCloseOpen(false)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>结案</ModalTitle>
            <ModalDescription>
              把「已发出但收不到」的部分如实记为差异，在途归零。差异**不会**被自动抹平，也不会挪回源仓。
            </ModalDescription>
          </ModalHeader>
          <label className="block text-sm">
            差异原因
            <select
              className="mt-1 w-full rounded-md border px-2 py-1"
              value={closeReason}
              onChange={(e) => setCloseReason(e.target.value)}
            >
              {Object.entries(transferDiscrepancyLabels).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label className="mt-3 block text-sm">
            备注
            <input
              className="mt-1 w-full rounded-md border px-2 py-1"
              value={closeNotes}
              onChange={(e) => setCloseNotes(e.target.value)}
            />
          </label>
          <ModalFooter>
            <Button variant="ghost" onClick={() => setCloseOpen(false)}>
              取消
            </Button>
            <Button
              variant="destructive"
              disabled={busy === 'close'}
              onClick={() => detail && void handleClose(detail)}
            >
              确认结案
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );

  /** 三个提交动作各自包一层，把状态刷新交给 run()，避免逻辑散在按钮 onClick 里。 */
  async function handleShip(target: TransferOrderDetail) {
    const lines = target.lines
      .map((l) => ({ lineId: l.id, quantity: Number(shipAmounts[l.id] ?? '0') }))
      .filter((x) => x.quantity > 0);
    await run('ship', () =>
      shipTransfer(target.order.id, {
        expectedVersion: target.order.version,
        shippedAt: '',
        notes: '',
        lines,
      }),
    );
    setShipOpen(false);
  }

  async function handleReceive(target: TransferOrderDetail) {
    const lines = target.lines
      .map((l) => ({ lineId: l.id, quantity: Number(receiveAmounts[l.id] ?? '0') }))
      .filter((x) => x.quantity > 0);
    await run('receive', () =>
      receiveTransfer(target.order.id, {
        expectedVersion: target.order.version,
        receivedAt: '',
        locationId: receiveLocation,
        lotId: '',
        notes: receiveNotes,
        lines,
      }),
    );
    setReceiveOpen(false);
  }

  async function handleClose(target: TransferOrderDetail) {
    await run('close', () =>
      closeTransfer(target.order.id, {
        expectedVersion: target.order.version,
        reason: closeReason,
        notes: closeNotes,
      }),
    );
    setCloseOpen(false);
  }
}

/** 展开区：明细行 + 发出/收货/差异流水 + 可用动作。 */
function DetailPanel({
  detail,
  writable,
  busy,
  conservation,
  conservationOk,
  onConfirm,
  onShip,
  onReceive,
  onClose,
}: {
  detail: TransferOrderDetail;
  writable: boolean;
  busy: string;
  conservation: string;
  conservationOk: boolean;
  onConfirm: () => void;
  onShip: () => void;
  onReceive: () => void;
  onClose: () => void;
}) {
  const actions = transferActions(detail.order.status);
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <span className="rounded px-2 py-0.5 text-xs">
          {transferStatusLabels[detail.order.status] ?? detail.order.status}
        </span>
        <span className="text-muted-foreground text-xs">{routeText(detail.order)}</span>
        <span
          className={`text-xs ${conservationOk ? 'text-muted-foreground' : 'text-destructive'}`}
        >
          {conservation}
        </span>
        {writable && (
          <span className="ml-auto flex gap-2">
            {actions.includes('confirm') && (
              <Button variant="ghost" disabled={busy === 'confirm'} onClick={onConfirm}>
                确认（待发）
              </Button>
            )}
            {actions.includes('ship') && (
              <Button variant="ghost" disabled={busy === 'ship'} onClick={onShip}>
                <Truck /> 发出
              </Button>
            )}
            {actions.includes('receive') && (
              <Button variant="ghost" disabled={busy === 'receive'} onClick={onReceive}>
                <PackageCheck /> 实收
              </Button>
            )}
            {actions.includes('close') && (
              <Button variant="ghost" disabled={busy === 'close'} onClick={onClose}>
                <ArrowRightLeft /> 结案
              </Button>
            )}
          </span>
        )}
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-muted-foreground border-b text-left">
              <th className="p-2">SKU / 质量状态</th>
              <th className="p-2 text-right">请求</th>
              <th className="p-2 text-right">已发出</th>
              <th className="p-2 text-right">已实收</th>
              <th className="p-2 text-right">仍在途</th>
              <th className="p-2 text-right">未发出</th>
              <th className="p-2 text-right">差异</th>
            </tr>
          </thead>
          <tbody>
            {detail.lines.map((l) => (
              <tr key={l.id} className="border-b">
                <td className="p-2">
                  {l.skuCode} · {l.skuName}
                  <div className="text-muted-foreground text-xs">{l.qualityStatusName}</div>
                </td>
                <td className="p-2 text-right">{fmt(l.requestedQuantity)}</td>
                <td className="p-2 text-right">{fmt(l.shippedQuantity)}</td>
                <td className="p-2 text-right">{fmt(l.receivedQuantity)}</td>
                <td className="p-2 text-right font-medium">{fmt(l.inTransitQuantity)}</td>
                <td className="p-2 text-right">{fmt(l.unshippedQuantity)}</td>
                <td className="p-2 text-right">{fmt(l.discrepancyQuantity)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <p className="text-muted-foreground text-xs">{detail.conservationNote}</p>

      <div className="grid gap-4 md:grid-cols-2">
        <div>
          <h4 className="mb-1 text-sm font-medium">发出记录</h4>
          {detail.shipments.length === 0 && (
            <p className="text-muted-foreground text-xs">尚未发出。</p>
          )}
          <ul className="space-y-1 text-xs">
            {detail.shipments.map((s) => (
              <li key={s.id} className="border-b py-1">
                {moment(s.shippedAt)} ·{' '}
                {s.lines.map((x) => `${x.skuCode} ${fmt(x.quantity)}`).join('、')}
                {s.notes && <span className="text-muted-foreground"> · {s.notes}</span>}
              </li>
            ))}
          </ul>
        </div>
        <div>
          <h4 className="mb-1 text-sm font-medium">收货 / 差异记录</h4>
          {detail.receipts.length === 0 && (
            <p className="text-muted-foreground text-xs">尚无收货或差异记录。</p>
          )}
          <ul className="space-y-1 text-xs">
            {detail.receipts.map((r) => (
              <li key={r.id} className="border-b py-1">
                {moment(r.receivedAt)} ·{' '}
                <span className={r.kind === 'SHORTAGE' ? 'text-destructive' : ''}>
                  {r.kind === 'SHORTAGE' ? '差异' : '实收'}
                </span>
                {r.locationCode && (
                  <span className="text-muted-foreground"> @ {r.locationCode}</span>
                )}
                {r.reason && (
                  <span className="text-muted-foreground">
                    {' '}
                    （{transferDiscrepancyLabels[r.reason] ?? r.reason}）
                  </span>
                )}{' '}
                · {r.lines.map((x) => `${x.skuCode} ${fmt(x.quantity)}`).join('、')}
              </li>
            ))}
          </ul>
        </div>
      </div>

      <Movements orderId={detail.order.id} />
    </div>
  );
}

/**
 * 本单分录 —— 复用 7.1.2.1 台账。
 *
 * <p>归属口径必须显示：在途那部分数量**不在分录里**（货权在发出时一次离开发货仓），
 * 不说清就会把「发出 100、实收 40、分录只有 -100+40」读成「60 件的账不见了」。
 */
function Movements({ orderId }: { orderId: string }) {
  const [data, setData] = useState<Awaited<ReturnType<typeof fetchTransferMovements>> | null>(null);
  const [failed, setFailed] = useState('');

  useEffect(() => {
    let cancelled = false;
    setData(null);
    setFailed('');
    void fetchTransferMovements(orderId)
      .then((result) => {
        if (!cancelled) setData(result);
      })
      .catch((e) => {
        if (!cancelled) setFailed(errorText(e));
      });
    return () => {
      cancelled = true;
    };
  }, [orderId]);

  return (
    <div>
      <h4 className="mb-1 text-sm font-medium">库存分录</h4>
      {!data && !failed && <p className="text-muted-foreground text-xs">加载分录…</p>}
      {failed && <p className="text-destructive text-xs">{failed}</p>}
      {data && (
        <>
          <p className="text-muted-foreground mb-1 text-xs">{data.attributionNote}</p>
          {data.movements.rows.length === 0 ? (
            <p className="text-muted-foreground text-xs">尚无分录。</p>
          ) : (
            <ul className="space-y-1 text-xs">
              {data.movements.rows.map((r) => (
                <li key={r.transactionId} className="border-b py-1">
                  {moment(r.occurredAt)} · {r.kind} · {r.notes || '—'} · {fmt(r.onHandDelta)}（
                  {r.entryCount} 条分录）
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </div>
  );
}
