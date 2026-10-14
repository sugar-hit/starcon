'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { Button, EmptyState, Input, Notice, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  PackingCapabilities,
  PackingDetail,
  PackingPage,
} from '../../../../../packages/api-contracts/src/index';
import type { DeclarationSource } from './packing-model';
import {
  addParcel,
  addParcelItem,
  cancelPackingOrder,
  completionVerdict,
  completePackingOrder,
  declarationCell,
  declarationSourceLabel,
  failPackingOrder,
  materialPage,
  measureParcel,
  measuredCell,
  packingCapabilities,
  packingDetail,
  packingPage,
  packingStatusLabel,
  shippedHandoffNote,
} from './packing-model';

/**
 * 8.3.3.2 打包与称重工作区。
 *
 * <p>三条硬要求在界面上是**看得见**的（与后端模型一一对应，页面不自己解释）：
 *
 * <ul>
 *   <li><b>没量 ≠ 0。</b>未称重/未量尺寸显示「未量」，不显示 0 g / 0 cbm。</li>
 *   <li><b>明细永远跟着包裹。</b>每个包裹下面直接列出「哪些 SKU、多少件」，
 *       不折叠成「共 3 件」。</li>
 *   <li><b>完成 ≠ 已发货。</b>完成后提示写「交到待发货（8.3.4.1）」。</li>
 * </ul>
 */
export function PackingWorkspace() {
  const [caps, setCaps] = useState<PackingCapabilities | null>(null);
  const [page, setPage] = useState<PackingPage | null>(null);
  const [detail, setDetail] = useState<PackingDetail | null>(null);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [form, setForm] = useState({ materialId: '', gross: '', l: '', w: '', h: '', reason: '' });

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const params = {
        ...(status ? { status } : {}),
        ...(keyword ? { keyword } : {}),
        size: 50,
      };
      setPage(await packingPage(params));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载打包单列表失败');
    } finally {
      setLoading(false);
    }
  }, [keyword, status]);

  const loadDetail = useCallback(async (id: string) => {
    setDetailLoading(true);
    setActionError(null);
    try {
      setDetail(await packingDetail(id));
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '加载打包单详情失败');
    } finally {
      setDetailLoading(false);
    }
  }, []);

  useEffect(() => {
    void (async () => {
      try {
        setCaps(await packingCapabilities());
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
    else setDetail(null);
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

  const verdict = completionVerdict(detail?.completion);

  return (
    <div className="space-y-6">
      <PageHeader
        title="打包与称重"
        description="选择包材与填充物、装明细项（哪些 SKU、多少件）、称重与量尺寸 —— 实测重量体积交给 8.3.4.1 发货与 10 物流算费。"
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
          <StatCard label="打包单" value={page.summary.totalOrders} />
          <StatCard label="打包中" value={page.summary.openOrders} />
          <StatCard label="未称重包裹" value={page.summary.unmeasuredParcels} />
          <StatCard label="实测毛重合计（g）" value={page.summary.totalGrossWeightG} />
        </div>
      ) : null}

      <SectionCard title="打包单列表">
        <div className="flex flex-wrap items-end gap-3">
          <label className="text-xs">
            关键字
            <Input
              className="mt-1 w-64"
              value={keyword}
              placeholder="打包单号 / 出库单号 / 复核单号"
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
            <option value="OPEN">打包中</option>
            <option value="PACKED">已打包</option>
            <option value="FAILED">打包失败</option>
            <option value="CANCELLED">已取消</option>
          </select>
        </div>

        {loading ? (
          <p className="mt-4 text-sm text-muted-foreground">加载中…</p>
        ) : page && page.items.length === 0 ? (
          <EmptyState
            className="mt-4"
            title="还没有打包单"
            description="打包单由「复核通过」（8.3.3.1）的复核单开出来；还没有通过的复核单时这里就是空的。"
          />
        ) : (
          <table className="mt-4 w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="p-2">打包单</th>
                <th className="p-2">出库单</th>
                <th className="p-2">状态</th>
                <th className="p-2">基线 / 已装（件）</th>
                <th className="p-2">包裹</th>
                <th className="p-2">实测毛重（g）</th>
                <th className="p-2">实测体积（cbm）</th>
              </tr>
            </thead>
            <tbody>
              {(page?.items ?? []).map((row) => (
                <tr
                  key={row.id}
                  className={`cursor-pointer border-b ${selected === row.id ? 'bg-muted' : ''}`}
                  onClick={() => setSelected(row.id)}
                >
                  <td className="p-2 font-mono text-xs">{row.packingCode}</td>
                  <td className="p-2 font-mono text-xs">{row.outboundOrderCode}</td>
                  <td className="p-2">{packingStatusLabel(row.status)}</td>
                  <td className="p-2">
                    {row.baselineQuantity} / {row.packedQuantity}
                  </td>
                  <td className="p-2">
                    {row.parcelCount}（明细 {row.parcelItemCount}）
                  </td>
                  <td className="p-2">{measuredCell(row.totalGrossWeightG, '未称重')}</td>
                  <td className="p-2">{measuredCell(row.totalVolumeCbm, '未量尺寸')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </SectionCard>

      {selected ? (
        <SectionCard title="打包详情">
          {detailLoading ? (
            <p className="text-sm text-muted-foreground">加载中…</p>
          ) : !detail ? (
            <EmptyState title="详情不可用" description="该打包单在当前租户下读不到。" />
          ) : (
            <div className="space-y-4">
              {actionError ? <Notice tone="danger" title="操作未完成">{actionError}</Notice> : null}

              <p className="text-sm text-muted-foreground">
                {detail.order.packingBasis}
                {detail.order.outboundOrderStatusLabel ? (
                  <> · 出库单当前：{detail.order.outboundOrderStatusLabel}</>
                ) : null}
              </p>

              <Notice tone="info" title="实测口径">
                净重与体积由后端从「实测毛重 − 包材自重」与「长×宽×高」派生；**体积重不在本页算**
                ——除数（6000/5000/…）是承运商渠道口径，归 10 物流。
              </Notice>

              <div>
                <h3 className="text-sm font-semibold">复核基线对齐</h3>
                <table className="mt-2 w-full text-sm">
                  <thead>
                    <tr className="border-b text-left">
                      <th className="p-2">SKU</th>
                      <th className="p-2">应发</th>
                      <th className="p-2">已装</th>
                      <th className="p-2">还差</th>
                      <th className="p-2">逐件核对</th>
                      <th className="p-2">装了几个箱</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.baseline.map((line) => (
                      <tr key={line.id} className="border-b">
                        <td className="p-2">{line.skuCode}</td>
                        <td className="p-2">{line.expectedQuantity}</td>
                        <td className="p-2">{line.packedQuantity}</td>
                        <td className="p-2">{line.fullyPacked ? '0' : line.remainingQuantity}</td>
                        <td className="p-2">{line.requiresSerial ? '是（一件一号）' : '否'}</td>
                        <td className="p-2">{line.parcelCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <div>
                <h3 className="text-sm font-semibold">包裹与明细项</h3>
                {detail.parcels.length === 0 ? (
                  <EmptyState
                    className="mt-2"
                    title="还没有包裹"
                    description="先建一个包裹（选包材与填充物），再往里装明细项。"
                  />
                ) : (
                  detail.parcels.map((parcel) => {
                    const items = detail.items.filter((i) => i.parcelId === parcel.id);
                    return (
                      <div key={parcel.id} className="mt-3 rounded-md border p-3">
                        <div className="flex flex-wrap items-center gap-3 text-sm">
                          <span className="font-mono text-xs">{parcel.parcelCode}</span>
                          <span>包材 {parcel.materialCode}</span>
                          {parcel.fillerMaterialCode ? (
                            <span>填充物 {parcel.fillerMaterialCode}</span>
                          ) : null}
                          <span>
                            外形 {measuredCell(parcel.outerLengthCm)} ×{' '}
                            {measuredCell(parcel.outerWidthCm)} ×{' '}
                            {measuredCell(parcel.outerHeightCm)} cm
                          </span>
                          <span>毛重 {measuredCell(parcel.measuredGrossWeightG, '未称重')} g</span>
                          <span>净重 {measuredCell(parcel.netContentWeightG, '未称重')} g</span>
                          <span>体积 {measuredCell(parcel.volumeCbm, '未量尺寸')} cbm</span>
                          {parcel.labelPrintCount > 0 ? (
                            <span>面单已打印 {parcel.labelPrintCount} 次</span>
                          ) : null}
                        </div>
                        <p className="mt-2 text-xs text-muted-foreground">
                          {items.length === 0
                            ? '未装任何明细 —— 蓝图要求「包裹必须含明细项」，空箱不能结算。'
                            : items
                                .map(
                                  (i) =>
                                    `${i.skuCode} × ${i.quantity}${i.serialNumber ? `（SN ${i.serialNumber}）` : ''}`,
                                )
                                .join('、')}
                        </p>
                        <p className="mt-1 text-xs text-muted-foreground">
                          申报：{declarationCell(
                            items[0]?.declarationSource as DeclarationSource | undefined,
                            items[0]?.hsCode,
                          ).text}
                        </p>
                      </div>
                    );
                  })
                )}
              </div>

              {detail.order.status === 'OPEN' ? (
                <div className="space-y-2">
                  <Notice
                    tone={verdict.packable ? 'success' : 'warning'}
                    title={verdict.packable ? '可以完成打包' : '还不能完成打包'}
                  >
                    {verdict.packable
                      ? '已装齐、已称重、每箱都有明细 —— 可以完成打包。'
                      : `还不能完成：${verdict.reasons.join('；')}`}
                    {verdict.warnings.length ? `（提醒：${verdict.warnings.join('；')}）` : ''}
                  </Notice>
                  <PackActions
                    busy={busy}
                    detail={detail}
                    form={form}
                    setForm={setForm}
                    onAct={act}
                  />
                </div>
              ) : (
                <Notice tone="info" title="打包单已结束">
                  打包单已结束（{packingStatusLabel(detail.order.status)}），包裹与明细项不再允许改动。
                  {detail.order.status === 'PACKED' ? shippedHandoffNote() : ''}
                </Notice>
              )}

              {detail.unavailable.length ? (
                <div>
                  <h3 className="text-sm font-semibold">不由本模块负责的相邻能力</h3>
                  <ul className="mt-1 space-y-1 text-xs text-muted-foreground">
                    {detail.unavailable.map((u) => (
                      <li key={u.capability}>
                        {u.capability} —— 归属 {u.ownerModule}（{u.href}）。{u.reason}
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}

              <div>
                <h3 className="text-sm font-semibold">作业流水</h3>
                <ul className="mt-1 space-y-1 text-xs text-muted-foreground">
                  {detail.timeline.map((e) => (
                    <li key={e.id}>
                      {e.occurredAt} · {e.eventTypeLabel}：{e.detail}
                    </li>
                  ))}
                </ul>
              </div>
            </div>
          )}
        </SectionCard>
      ) : null}

      {caps ? (
        <SectionCard title="口径与边界">
          <ul className="space-y-1 text-xs text-muted-foreground">
            <li>重量：{caps.weightBasis}</li>
            <li>尺寸与体积：{caps.dimensionBasis}</li>
            <li>体积重：{caps.volumetricWeightBasis}</li>
            <li>申报信息：{caps.declaredInformationBasis}</li>
            <li>包材守卫：{caps.measureGuardBasis}</li>
          </ul>
        </SectionCard>
      ) : null}
    </div>
  );
}

/** 作业动作区：建包裹 / 装明细 / 称重 / 完成 / 失败 / 取消。 */
function PackActions({
  busy,
  detail,
  form,
  setForm,
  onAct,
}: {
  busy: string | null;
  detail: PackingDetail;
  form: { materialId: string; gross: string; l: string; w: string; h: string; reason: string };
  setForm: (f: { materialId: string; gross: string; l: string; w: string; h: string; reason: string }) => void;
  onAct: (name: string, run: () => Promise<unknown>) => Promise<void>;
}) {
  const [materials, setMaterials] = useState<{ id: string; materialCode: string; materialRole: string }[]>(
    [],
  );

  useEffect(() => {
    void (async () => {
      try {
        const res = await materialPage({ size: 100 });
        setMaterials(
          res.items.map((m: { id: string; materialCode: string; materialRole: string }) => ({
            id: m.id,
            materialCode: m.materialCode,
            materialRole: m.materialRole,
          })),
        );
      } catch {
        setMaterials([]);
      }
    })();
  }, []);

  const firstParcel = detail.parcels[0];
  const openLine = detail.baseline.find((l) => !l.fullyPacked) ?? detail.baseline[0];

  return (
    <div className="space-y-3 rounded-md border p-3">
      <div className="flex flex-wrap items-end gap-2">
        <label className="text-xs">
          包材
          <select
            aria-label="包材"
            className="mt-1 block rounded-md border px-2 py-1.5 text-sm"
            value={form.materialId}
            onChange={(e) => setForm({ ...form, materialId: e.target.value })}
          >
            <option value="">选包材…</option>
            {materials
              .filter((m) => m.materialRole === 'PACKAGING')
              .map((m) => (
                <option key={m.id} value={m.id}>
                  {m.materialCode}
                </option>
              ))}
          </select>
        </label>
        <Button
          disabled={!form.materialId || busy !== null}
          onClick={() =>
            void onAct('addParcel', () =>
              addParcel(detail.order.id, {
                materialId: form.materialId,
                fillerMaterialId: null,
                parcelNo: null,
                note: null,
              }),
            )
          }
        >
          建包裹
        </Button>
      </div>

      {firstParcel && openLine ? (
        <>
          <div className="flex flex-wrap items-end gap-2">
            <span className="text-xs text-muted-foreground">
              往 {firstParcel.parcelCode} 装：{openLine.skuCode}，还差 {openLine.remainingQuantity} 件
            </span>
            <Button
              disabled={busy !== null}
              onClick={() =>
                void onAct('addItem', () =>
                  addParcelItem(detail.order.id, firstParcel.id, {
                    recountLineId: openLine.id,
                    quantity: '1',
                    serialId: null,
                    note: null,
                  }),
                )
              }
            >
              装一件
            </Button>
          </div>

          <div className="flex flex-wrap items-end gap-2">
            {(['gross', 'l', 'w', 'h'] as const).map((key) => (
              <label key={key} className="text-xs">
                {key === 'gross' ? '毛重(g)' : key === 'l' ? '长(cm)' : key === 'w' ? '宽(cm)' : '高(cm)'}
                <Input
                  className="mt-1 w-24"
                  value={form[key]}
                  onChange={(e) => setForm({ ...form, [key]: e.target.value })}
                />
              </label>
            ))}
            <Button
              disabled={!form.gross || !form.l || !form.w || !form.h || busy !== null}
              onClick={() =>
                void onAct('measure', () =>
                  measureParcel(detail.order.id, firstParcel.id, {
                    measuredGrossWeightG: form.gross,
                    outerLengthCm: form.l,
                    outerWidthCm: form.w,
                    outerHeightCm: form.h,
                    measuredAt: null,
                    note: null,
                  }),
                )
              }
            >
              称重并量尺寸
            </Button>
          </div>
        </>
      ) : null}

      <div className="flex flex-wrap items-end gap-2">
        <Button
          disabled={busy !== null || detail.completion.packable !== true}
          onClick={() => void onAct('complete', () => completePackingOrder(detail.order.id))}
        >
          完成打包（交到待发货）
        </Button>
        <label className="text-xs">
          原因（失败 / 取消必填）
          <Input
            className="mt-1 w-64"
            value={form.reason}
            onChange={(e) => setForm({ ...form, reason: e.target.value })}
          />
        </label>
        <Button
          disabled={!form.reason || busy !== null}
          onClick={() => void onAct('fail', () => failPackingOrder(detail.order.id, form.reason))}
        >
          判失败（退回复核）
        </Button>
        <Button
          disabled={!form.reason || busy !== null}
          onClick={() => void onAct('cancel', () => cancelPackingOrder(detail.order.id, form.reason))}
        >
          取消
        </Button>
      </div>
      <p className="text-xs text-muted-foreground">
        {declarationSourceLabel(null)} —— 界面不会给一个没人能填的 HS 编码框。
      </p>
    </div>
  );
}