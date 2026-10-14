'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { Button, EmptyState, Input, Notice, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  HandoverConfirmed,
  HandoverDetail,
  HandoverPage,
  ShipmentHandoverCapabilities,
} from '../../../../../packages/api-contracts/src/index';
import {
  cancelHandover,
  confirmHandover,
  effectiveWaybill,
  handoverCapabilities,
  handoverDetail,
  handoverPage,
  handoverStatusLabel,
  handoverVerdict,
  handedOverNote,
  ledgerDigest,
  openHandover,
  registerWaybills,
  registeredCell,
  waybillInputOf,
} from './handover-model';

/**
 * 8.3.4.1 发货交接工作区。
 *
 * <p>四条硬要求在界面上是**看得见**的（与后端模型一一对应，页面不自己解释）：
 *
 * <ul>
 *   <li><b>承运商与运单号分开登记、分开显示。</b>只有承运商没有号等于没人知道运的是哪一单。</li>
 *   <li><b>「确认交接」会真的扣库存。</b>按钮与二次确认都写明「从本仓出库并消费预占」，
 *       避免作业员把它当成普通的保存。</li>
 *   <li><b>两本预占账分开显示。</b>实物预占释放量（7.1.1.1）与承诺侧被消费的分配（7.2.1.2）
 *       各占一栏；没有可消费的预占时显示「本次未预占出库」而不是「预占已消耗」。</li>
 *   <li><b>明细永远跟着包裹。</b>每箱下面直接列出「哪些 SKU、多少件」，不折叠成总数。</li>
 * </ul>
 */
export function HandoverWorkspace() {
  const [caps, setCaps] = useState<ShipmentHandoverCapabilities | null>(null);
  const [page, setPage] = useState<HandoverPage | null>(null);
  const [detail, setDetail] = useState<HandoverDetail | null>(null);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [done, setDone] = useState<HandoverConfirmed | null>(null);
  const [openId, setOpenId] = useState('');
  const [form, setForm] = useState({
    carrierCode: '',
    carrierName: '',
    masterWaybillNo: '',
    receiverName: '',
    receiverContact: '',
    reason: '',
  });
  const [parcelWaybills, setParcelWaybills] = useState<Record<string, string>>({});

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const params = {
        ...(status ? { status } : {}),
        ...(keyword ? { keyword } : {}),
        size: 50,
      };
      setPage(await handoverPage(params));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载交接单列表失败');
    } finally {
      setLoading(false);
    }
  }, [keyword, status]);

  const loadDetail = useCallback(async (id: string) => {
    setDetailLoading(true);
    setActionError(null);
    setDone(null);
    try {
      const next = await handoverDetail(id);
      setDetail(next);
      setForm((f) => ({
        ...f,
        carrierCode: next.order.carrierCode || f.carrierCode,
        carrierName: next.order.carrierName || '',
        masterWaybillNo: next.order.masterWaybillNo || '',
        receiverName: next.order.receiverName || '',
        receiverContact: next.order.receiverContact || '',
      }));
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '加载交接单详情失败');
    } finally {
      setDetailLoading(false);
    }
  }, []);

  useEffect(() => {
    void (async () => {
      try {
        setCaps(await handoverCapabilities());
      } catch {
        // 能力声明拿不到不阻塞主流程：页面仍然按后端返回的字段渲染。
      }
    })();
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (selected) void loadDetail(selected);
    else {
      setDetail(null);
      setDone(null);
    }
  }, [selected, loadDetail]);

  async function act(name: string, run: () => Promise<unknown>) {
    setBusy(name);
    setActionError(null);
    try {
      await run();
      if (selected) await loadDetail(selected);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '操作失败');
    } finally {
      setBusy(null);
    }
  }

  const verdict = handoverVerdict(detail?.completion);

  return (
    <div className="space-y-6">
      <PageHeader
        title="发货交接"
        description="确认承运商与运单号、登记交接时间与交接人、扣减库存完成出库（预占转实扣同一事务）—— 物流轨迹与平台回传由 10 物流与 2 渠道消费本单流水。"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error ? <Notice tone="danger" title="操作未完成">{error}</Notice> : null}

      {page ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="交接单" value={page.summary.totalHandovers} />
          <StatCard label="交接中" value={page.summary.openHandovers} />
          <StatCard label="已交接" value={page.summary.handedOverHandovers} />
          <StatCard label="实扣合计（件）" value={page.summary.deductedQuantity} />
        </div>
      ) : null}

      <SectionCard title="开交接单">
        <p className="text-sm text-muted-foreground">
          交接单由一张<b>已打包</b>（8.3.3.2）的打包单开出，开单时会把包裹与明细冻结到本单。
          <b>开单不动库存</b> —— 实发扣减发生在「确认交接」那一个事务里。
        </p>
        <div className="mt-3 flex flex-wrap items-end gap-3">
          <label className="text-xs">
            打包单 ID
            <Input
              className="mt-1 w-72 font-mono"
              value={openId}
              placeholder="已打包的 packing_order id"
              onChange={(e) => setOpenId(e.target.value)}
            />
          </label>
          <Button
            disabled={busy === 'open' || openId.trim() === ''}
            onClick={() =>
              void act('open', async () => {
                const created = await openHandover({
                  packingOrderId: openId.trim(),
                  carrierCode: null,
                  carrierName: null,
                  masterWaybillNo: null,
                  note: null,
                });
                setSelected((created as { id?: string } | null)?.id ?? null);
                setOpenId('');
              })
            }
          >
            开交接单
          </Button>
        </div>
      </SectionCard>

      <SectionCard title="交接单列表">
        <div className="flex flex-wrap items-end gap-3">
          <label className="text-xs">
            关键字
            <Input
              className="mt-1 w-64"
              value={keyword}
              placeholder="交接单号 / 运单号 / 承运商"
              onChange={(e) => setKeyword(e.target.value)}
            />
          </label>
          <select
            aria-label="状态"
            className="rounded-md border px-2 py-1.5 text-sm"
            value={status}
            onChange={(e) => setStatus(e.target.value)}
          >
            <option value="">全部状态</option>
            <option value="OPEN">交接中</option>
            <option value="HANDED_OVER">已交接</option>
            <option value="CANCELLED">已作废</option>
          </select>
        </div>

        {loading ? (
          <p className="mt-4 text-sm text-muted-foreground">加载中…</p>
        ) : page && page.items.length === 0 ? (
          <EmptyState
            className="mt-4"
            title="还没有交接单"
            description="交接单要等打包单「已打包」（8.3.3.2）之后才开得出来；还没有已打包的单时这里就是空的。"
          />
        ) : (
          <table className="mt-4 w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="p-2">交接单</th>
                <th className="p-2">出库单</th>
                <th className="p-2">状态</th>
                <th className="p-2">承运 / 运单号</th>
                <th className="p-2">包裹</th>
                <th className="p-2">应发 / 实扣（件）</th>
                <th className="p-2">毛重合计（g）</th>
              </tr>
            </thead>
            <tbody>
              {(page?.items ?? []).map((row) => (
                <tr
                  key={row.id}
                  className={`cursor-pointer border-b ${selected === row.id ? 'bg-muted' : ''}`}
                  onClick={() => setSelected(row.id)}
                >
                  <td className="p-2 font-mono text-xs">{row.handoverCode}</td>
                  <td className="p-2 font-mono text-xs">{row.outboundOrderCode}</td>
                  <td className="p-2">{handoverStatusLabel(row.status)}</td>
                  <td className="p-2 text-xs">
                    {registeredCell(row.carrierCode, '未登记承运商')} ·{' '}
                    {registeredCell(row.masterWaybillNo, '未登记运单号')}
                  </td>
                  <td className="p-2">
                    {row.parcelCount}（明细 {row.parcelItemCount}）
                  </td>
                  <td className="p-2">
                    {row.totalQuantity} / {row.deductedQuantity}
                  </td>
                  <td className="p-2">{row.totalGrossWeightG}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </SectionCard>

      {selected ? (
        <SectionCard title="交接详情">
          {detailLoading ? (
            <p className="text-sm text-muted-foreground">加载中…</p>
          ) : !detail ? (
            <EmptyState title="详情不可用" description="该交接单在当前租户下读不到。" />
          ) : (
            <div className="space-y-4">
              {actionError ? <Notice tone="danger" title="操作未完成">{actionError}</Notice> : null}

              <div className="grid gap-2 text-sm sm:grid-cols-2 lg:grid-cols-4">
                <span>
                  交接单：<span className="font-mono text-xs">{detail.order.handoverCode}</span>
                </span>
                <span>
                  出库单：<span className="font-mono text-xs">{detail.order.outboundOrderCode}</span>
                  {detail.order.outboundOrderStatusLabel
                    ? `（${detail.order.outboundOrderStatusLabel}）`
                    : ''}
                </span>
                <span>
                  打包单：<span className="font-mono text-xs">{detail.order.packingCode}</span>
                </span>
                <span>
                  交接人 / 时刻：
                  {detail.order.handoverAt
                    ? `${detail.order.handoverUserId ?? '—'} · ${detail.order.handoverAt}`
                    : '确认时登记'}
                </span>
              </div>

              <Notice tone="info" title="承运商与运单号">
                承运渠道与运单号<b>缺一不可</b>：只有承运商没有号 = 车来了但没人知道运的是哪一单；
                只有号没有渠道 = 不知道谁运的。承运商主数据与轨迹归 10 物流，本单据只登记
                「这一次走了哪个渠道的哪个号」。
              </Notice>

              {detail.order.status === 'OPEN' ? (
                <div className="space-y-3 rounded-md border p-3">
                  <h3 className="text-sm font-semibold">登记承运商与运单号</h3>
                  <div className="flex flex-wrap items-end gap-3">
                    <label className="text-xs">
                      承运渠道
                      <Input
                        className="mt-1 w-40"
                        value={form.carrierCode}
                        placeholder="SF / YTO / JD"
                        onChange={(e) => setForm({ ...form, carrierCode: e.target.value })}
                      />
                    </label>
                    <label className="text-xs">
                      承运商名称
                      <Input
                        className="mt-1 w-40"
                        value={form.carrierName}
                        onChange={(e) => setForm({ ...form, carrierName: e.target.value })}
                      />
                    </label>
                    <label className="text-xs">
                      主运单号
                      <Input
                        className="mt-1 w-48 font-mono"
                        value={form.masterWaybillNo}
                        onChange={(e) => setForm({ ...form, masterWaybillNo: e.target.value })}
                      />
                    </label>
                    <label className="text-xs">
                      揽收人
                      <Input
                        className="mt-1 w-32"
                        value={form.receiverName}
                        onChange={(e) => setForm({ ...form, receiverName: e.target.value })}
                      />
                    </label>
                    <label className="text-xs">
                      揽收联系方式
                      <Input
                        className="mt-1 w-40"
                        value={form.receiverContact}
                        onChange={(e) => setForm({ ...form, receiverContact: e.target.value })}
                      />
                    </label>
                  </div>

                  {detail.parcels.length ? (
                    <div className="space-y-2">
                      <p className="text-xs text-muted-foreground">
                        每箱运单号（留空 = 跟随主运单号，整车发运的常态）：
                      </p>
                      {detail.parcels.map((p) => (
                        <label key={p.id} className="flex items-center gap-2 text-xs">
                          <span className="w-40 font-mono">{p.parcelCode}</span>
                          <Input
                            className="w-48 font-mono"
                            value={parcelWaybills[p.parcelId] ?? p.parcelWaybillNo ?? ''}
                            onChange={(e) =>
                              setParcelWaybills({ ...parcelWaybills, [p.parcelId]: e.target.value })
                            }
                          />
                        </label>
                      ))}
                    </div>
                  ) : null}

                  <Button
                    disabled={busy === 'waybill'}
                    onClick={() =>
                      void act('waybill', () => {
                        const check = waybillInputOf({
                          ...form,
                          parcelWaybills: Object.fromEntries(
                            detail.parcels.map((p) => [
                              p.parcelId,
                              parcelWaybills[p.parcelId] ?? p.parcelWaybillNo ?? '',
                            ]),
                          ),
                        });
                        if (!check.ok) {
                          setActionError(check.message);
                          return Promise.reject(new Error(check.message));
                        }
                        return registerWaybills(selected, check.body);
                      })
                    }
                  >
                    登记运单号
                  </Button>
                </div>
              ) : null}

              <div>
                <h3 className="text-sm font-semibold">包裹与明细</h3>
                {detail.parcels.length === 0 ? (
                  <EmptyState
                    className="mt-2"
                    title="这次交接没有包裹"
                    description="交接单由已打包的打包单开出；没有包裹说明这单还没冻上箱。"
                  />
                ) : (
                  detail.parcels.map((parcel) => {
                    const items = detail.items.filter((i) => i.parcelId === parcel.parcelId);
                    const wb = effectiveWaybill(parcel.parcelWaybillNo, detail.order.masterWaybillNo);
                    return (
                      <div key={parcel.id} className="mt-3 rounded-md border p-3">
                        <div className="flex flex-wrap items-center gap-3 text-sm">
                          <span className="font-mono text-xs">{parcel.parcelCode}</span>
                          <span className={wb.missing ? 'text-muted-foreground' : ''}>
                            运单号 {wb.text}
                          </span>
                          <span>毛重 {registeredCell(parcel.grossWeightG, '未称重')} g</span>
                          <span>体积 {registeredCell(parcel.volumeCbm, '未量尺寸')} cbm</span>
                          {parcel.deducted ? <span>已扣减</span> : <span>未扣减</span>}
                        </div>
                        <p className="mt-2 text-xs text-muted-foreground">
                          {items.length === 0
                            ? '这一箱一件货都没有 —— 蓝图要求「包裹必须含明细项」，空箱不能交出去。'
                            : items
                                .map(
                                  (i) =>
                                    `${i.skuCode} × ${i.quantity}${
                                      i.serialNumber ? `（SN ${i.serialNumber}）` : ''
                                    }`,
                                )
                                .join('、')}
                        </p>
                      </div>
                    );
                  })
                )}
              </div>

              <div>
                <h3 className="text-sm font-semibold">出库扣减落点</h3>
                <table className="mt-2 w-full text-sm">
                  <thead>
                    <tr className="border-b text-left">
                      <th className="p-2">SKU</th>
                      <th className="p-2">应交</th>
                      <th className="p-2">实扣</th>
                      <th className="p-2">其中释放实物预占</th>
                      <th className="p-2">扣减库位</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.items.length === 0 ? (
                      <tr>
                        <td className="p-2 text-muted-foreground" colSpan={5}>
                          还没有明细行。
                        </td>
                      </tr>
                    ) : (
                      detail.items.map((i) => (
                        <tr key={i.id} className="border-b">
                          <td className="p-2">{i.skuCode}</td>
                          <td className="p-2">{i.quantity}</td>
                          <td className="p-2">{i.deducted ? i.deductedQuantity : '未扣'}</td>
                          <td className="p-2">{i.reservedDeductedQuantity ?? '0'}</td>
                          <td className="p-2">
                            {i.locationCode ?? (i.deducted ? '已扣减' : '未扣减')}
                          </td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>

              <div>
                <h3 className="text-sm font-semibold">预占消费（承诺侧 7.2.1.2）</h3>
                {detail.reservations.length === 0 ? (
                  <p className="mt-2 text-xs text-muted-foreground">
                    本次交接<b>未预占出库</b> —— 没有可消费的预占分配，因此没有预占被消费。
                    这不是「预占已消耗」。
                  </p>
                ) : (
                  <table className="mt-2 w-full text-sm">
                    <thead>
                      <tr className="border-b text-left">
                        <th className="p-2">预占单号</th>
                        <th className="p-2">库位</th>
                        <th className="p-2">消耗量</th>
                        <th className="p-2">发生时刻</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detail.reservations.map((r) => (
                        <tr key={r.id} className="border-b">
                          <td className="p-2 font-mono text-xs">{r.reservationNo}</td>
                          <td className="p-2">{r.locationCode ?? '—'}</td>
                          <td className="p-2">{r.quantity}</td>
                          <td className="p-2 text-xs">{r.occurredAt}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
                {detail.reservationCandidates.length > 0 ? (
                  <p className="mt-2 text-xs text-muted-foreground">
                    本仓仍有 {detail.reservationCandidates.length} 条可消费的已承诺预占
                    （本次交接之外的其他订单）。
                  </p>
                ) : null}
              </div>

              <div>
                <h3 className="text-sm font-semibold">交接判据</h3>
                {verdict.handable ? (
                  <Notice tone="info" title="可以确认交接">
                    确认会<b>从本仓出库 {detail.order.totalQuantity} 件</b>并在同一事务里消费对应预占。
                  </Notice>
                ) : (
                  <Notice tone="warning" title="还不能确认交接">
                    <ul className="list-disc pl-4">
                      {verdict.reasons.map((r) => (
                        <li key={r}>{r}</li>
                      ))}
                    </ul>
                  </Notice>
                )}
                {verdict.warnings.length > 0 ? (
                  <ul className="mt-2 list-disc pl-6 text-xs text-muted-foreground">
                    {verdict.warnings.map((w) => (
                      <li key={w}>{w}</li>
                    ))}
                  </ul>
                ) : null}

                <div className="mt-3 flex flex-wrap gap-2">
                  <Button
                    disabled={!verdict.handable || busy === 'confirm'}
                    onClick={() =>
                      void act('confirm', async () => {
                        const result = await confirmHandover(selected, {
                          handoverUserId: null,
                          handoverAt: null,
                          receiverName: form.receiverName.trim() || null,
                          receiverContact: form.receiverContact.trim() || null,
                          note: null,
                        });
                        setDone(result);
                      })
                    }
                  >
                    确认交接（出库并消费预占）
                  </Button>

                  {detail.order.status === 'OPEN' ? (
                    <>
                      <label className="text-xs">
                        作废原因
                        <Input
                          className="mt-1 w-56"
                          value={form.reason}
                          onChange={(e) => setForm({ ...form, reason: e.target.value })}
                        />
                      </label>
                      <Button
                        variant="secondary"
                        disabled={busy === 'cancel' || form.reason.trim() === ''}
                        onClick={() =>
                          void act('cancel', () =>
                            cancelHandover(selected, { reason: form.reason.trim() }),
                          )
                        }
                      >
                        作废（库存不动）
                      </Button>
                    </>
                  ) : null}
                </div>
              </div>

              {done ? (
                <Notice tone="success" title="交接已完成">
                  <p>{handedOverNote(done.ledgerEntries.length)}</p>
                  <p className="mt-1 text-xs">
                    出库流水：<span className="font-mono">{done.inventoryTransactionId}</span>
                  </p>
                  <p className="text-xs">
                    分录：{ledgerDigest(done.ledgerEntries)}；释放实物预占 {done.reservedDeductedQuantity}
                    ；序列号出库 {done.serialCount} 个。
                  </p>
                </Notice>
              ) : null}

              <div>
                <h3 className="text-sm font-semibold">时间线</h3>
                {detail.timeline.length === 0 ? (
                  <p className="mt-2 text-xs text-muted-foreground">暂无流水。</p>
                ) : (
                  <ul className="mt-2 space-y-1 text-xs">
                    {detail.timeline.map((e) => (
                      <li key={e.id} className="border-b py-1">
                        <span className="font-mono text-muted-foreground">{e.occurredAt}</span>{' '}
                        <span className="font-semibold">{e.eventTypeLabel}</span> — {e.detail}
                      </li>
                    ))}
                  </ul>
                )}
              </div>

              <div>
                <h3 className="text-sm font-semibold">本模块边界</h3>
                <ul className="mt-2 space-y-1 text-xs text-muted-foreground">
                  {detail.unavailable.map((u) => (
                    <li key={u.capability}>
                      <span className="font-semibold">{u.capability}</span>（归 {u.ownerModule}）：{u.reason}
                    </li>
                  ))}
                </ul>
              </div>

              {caps ? (
                <p className="text-xs text-muted-foreground">
                  {caps.transactionBasis}
                </p>
              ) : null}
            </div>
          )}
        </SectionCard>
      ) : null}
    </div>
  );
}