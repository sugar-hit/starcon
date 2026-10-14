'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { PackagePlus, Plus, RefreshCw, TriangleAlert, Truck } from 'lucide-react';
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
  GoodsReceiptCapabilities,
  GoodsReceiptReceiveLineRequest,
  GoodsReceiptRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  closeGoodsReceipt,
  createGoodsReceipt,
  fetchGoodsReceiptCapabilities,
  fetchGoodsReceiptDetail,
  fetchGoodsReceiptMovements,
  fetchGoodsReceipts,
  fmt,
  receiveGoods,
  lineCaption,
  moment,
  receiptActions,
  receiptDiscrepancyLabels,
  receiptQuantityText,
  receiptSortOptions,
  receiptStatusLabels,
  receivable,
  sourceCaption,
  type GoodsReceiptParams,
} from './goods-receipt-model';
import {
  errorText,
  fetchLegalEntities,
  fetchQualityStatuses,
  fetchSkus,
  fetchWarehouses,
} from '../inventory/inventory-model';

interface Option {
  id: string;
  code: string;
  name: string;
}

/**
 * 8.2.1.1 收货单工作区。
 *
 * <p>蓝图：「执行到货收货：选择来源（采购/退货/调拨/平台仓回货），录入实收数量与差异，
 * 上传收货凭证，生成入库分录」；输出「收货单（草稿/已收/部分收/已关闭）+
 * 库存入库分录（转入待检或可售 bucket）」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li>**预期 / 实收 / 拒收 / 还差四个独立列**，不相加成「已处理」。
 *       蓝图原文「预期与实收分开记录」：货没到齐是常态，而「货没到齐」和
 *       「账记错了」必须一眼分开。
 *   <li>**还差量不是差异**，页面常驻说明。差异单独一列，
 *       因为差异意味着要有人去处理（归 8.2.1.2），还差只意味着货还在路上。
 *   <li>**未交付的来源直接禁用并写明归属模块**，而不是给一个点下去报错的选项。
 *   <li>**按钮按状态给**（`receiptActions`）：已关闭是终态，不再显示收货 ——
 *       重复收货等于账外入库。
 *   <li>加载 / 空 / 错误三态齐全：空态说清「还没有收货单」，不假装有数据。
 * </ul>
 */
export function GoodsReceiptWorkspace() {
  const [params, setParams] = useState<GoodsReceiptParams>({ page: 0, size: 20 });
  const [data, setData] = useState<Awaited<ReturnType<typeof fetchGoodsReceipts>> | null>(null);
  const [caps, setCaps] = useState<GoodsReceiptCapabilities | null>(null);
  const [warehouses, setWarehouses] = useState<Option[]>([]);
  const [skus, setSkus] = useState<Option[]>([]);
  const [qualities, setQualities] = useState<Option[]>([]);
  const [legalEntities, setLegalEntities] = useState<Option[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [detailId, setDetailId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [receiving, setReceiving] = useState<GoodsReceiptRow | null>(null);
  const [closing, setClosing] = useState<GoodsReceiptRow | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchGoodsReceipts(params));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void (async () => {
      try {
        setCaps(await fetchGoodsReceiptCapabilities());
      } catch {
        // capabilities 拿不到不该让整页空掉：列表仍然可用，只是来源下拉退化为禁用。
        setCaps(null);
      }
    })();
    void (async () => {
      try {
        setWarehouses((await fetchWarehouses()) as unknown as Option[]);
      } catch {
        setWarehouses([]);
      }
    })();
    void (async () => {
      try {
        setSkus((await fetchSkus()) as unknown as Option[]);
      } catch {
        setSkus([]);
      }
    })();
    void (async () => {
      try {
        setQualities((await fetchQualityStatuses()) as unknown as Option[]);
      } catch {
        setQualities([]);
      }
    })();
    void (async () => {
      try {
        setLegalEntities((await fetchLegalEntities()) as unknown as Option[]);
      } catch {
        setLegalEntities([]);
      }
    })();
  }, []);

  const summary = data?.summary;
  const rows = data?.rows ?? [];

  const sourceOptions = caps?.sources ?? [];
  const availableSources = useMemo(() => sourceOptions.filter((s) => s.available), [sourceOptions]);
  const unavailableSources = useMemo(
    () => sourceOptions.filter((s) => !s.available),
    [sourceOptions],
  );

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="收货单"
        description="执行到货收货：选择来源、录入实收数量与差异、上传送货凭证、生成入库分录。"
        actions={
          <>
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
            <Button onClick={() => setCreating(true)} disabled={availableSources.length === 0}>
              <Plus />
              新建收货单
            </Button>
          </>
        }
      />

      {caps && !caps.discrepancyWorkflowAvailable && (
        <div className="flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <div>
            <p className="font-medium">
              差异的「处理流程」尚未交付（归属 {caps.discrepancyOwnerModule}）
            </p>
            <p className="text-muted-foreground">{caps.discrepancyNote}</p>
          </div>
        </div>
      )}

      {summary && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard
            label="收货单"
            value={String(summary.receiptCount)}
            comparisonLabel={`未关闭 ${summary.openReceiptCount} 张`}
          />
          <StatCard
            label="预期数量"
            value={fmt(summary.expectedQuantity)}
            comparisonLabel="来源单据承诺的量"
          />
          <StatCard
            label="实收 / 拒收"
            value={`${fmt(summary.receivedQuantity)} / ${fmt(summary.rejectedQuantity)}`}
            comparisonLabel="拒收部分不入库、不写分录"
          />
          <StatCard
            label="差异记录"
            value={String(summary.discrepancyCount)}
            comparisonLabel={`还差合计 ${fmt(summary.outstandingQuantity)}（不是差异）`}
          />
        </div>
      )}

      <SectionCard title="筛选">
        <div className="grid gap-3 md:grid-cols-4">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关键字</span>
            <input
              className="rounded-md border px-3 py-2"
              placeholder="收货单号 / 来源单号 / 运单号 / 备注"
              defaultValue={params.keyword ?? ''}
              onBlur={(e) => setParams((p) => ({ ...p, keyword: e.target.value, page: 0 }))}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">收货仓</span>
            <select
              className="rounded-md border px-3 py-2"
              value={params.warehouseId ?? ''}
              onChange={(e) =>
                setParams((p) => {
                  // exactOptionalPropertyTypes 下不能写 `warehouseId: undefined`，
                  // 只能删键 —— 否则「不筛选」和「筛一个空 id」在类型上分不开。
                  const next: GoodsReceiptParams = { ...p, page: 0 };
                  if (e.target.value) next.warehouseId = e.target.value;
                  else delete next.warehouseId;
                  return next;
                })
              }
            >
              <option value="">全部仓库</option>
              {warehouses.map((w) => (
                <option key={w.id} value={w.id}>
                  {w.code} {w.name}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">状态</span>
            <select
              className="rounded-md border px-3 py-2"
              value={params.status?.[0] ?? ''}
              onChange={(e) =>
                setParams((p) => {
                  const next: GoodsReceiptParams = { ...p, page: 0 };
                  if (e.target.value) next.status = [e.target.value];
                  else delete next.status;
                  return next;
                })
              }
            >
              <option value="">全部状态</option>
              {Object.entries(receiptStatusLabels).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">排序</span>
            <select
              className="rounded-md border px-3 py-2"
              value={params.sort ?? 'UPDATED_AT'}
              onChange={(e) => setParams((p) => ({ ...p, sort: e.target.value, page: 0 }))}
            >
              {receiptSortOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        </div>
      </SectionCard>

      {loading && !data ? (
        <SectionCard title="收货单列表">
          <p className="text-muted-foreground text-sm">正在加载收货单…</p>
        </SectionCard>
      ) : error ? (
        <SectionCard title="收货单列表">
          <div className="flex flex-col items-start gap-2">
            <TriangleAlert className="text-destructive size-5" />
            <p className="text-destructive text-sm">{error}</p>
            <Button variant="ghost" onClick={() => void load()}>
              重试
            </Button>
          </div>
        </SectionCard>
      ) : rows.length === 0 ? (
        <SectionCard title="收货单列表">
          <EmptyState
            title="还没有收货单"
            description="到货后先建一张收货单（草稿不动库存），再逐笔录入实收。"
          />
        </SectionCard>
      ) : (
        <SectionCard title={`收货单列表（共 ${data?.total ?? 0} 张）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground border-b text-left">
                  <th className="p-2">收货单号</th>
                  <th className="p-2">来源</th>
                  <th className="p-2">收货仓</th>
                  <th className="p-2">状态</th>
                  <th className="p-2 text-right">预期</th>
                  <th className="p-2 text-right">实收</th>
                  <th className="p-2 text-right">拒收</th>
                  <th className="p-2 text-right">还差</th>
                  <th className="p-2 text-right">差异</th>
                  <th className="p-2">操作</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => {
                  const q = receiptQuantityText(r);
                  const actions = receiptActions(r.status);
                  return (
                    <tr key={r.id} className="border-b last:border-0">
                      <td className="p-2">
                        <button
                          className="underline-offset-2 hover:underline"
                          onClick={() => setDetailId(r.id)}
                        >
                          {r.receiptCode}
                        </button>
                        <div className="text-muted-foreground text-xs">{moment(r.updatedAt)}</div>
                      </td>
                      <td className="p-2">
                        {r.sourceTypeLabel}
                        {r.sourceRefCode ? (
                          <div className="text-muted-foreground text-xs">{r.sourceRefCode}</div>
                        ) : null}
                      </td>
                      <td className="p-2">
                        {r.warehouseCode}
                        <div className="text-muted-foreground text-xs">{r.warehouseName}</div>
                      </td>
                      <td className="p-2">{r.statusLabel}</td>
                      <td className="p-2 text-right">{fmt(r.expectedQuantity)}</td>
                      <td className="p-2 text-right">{fmt(r.receivedQuantity)}</td>
                      <td className="p-2 text-right">{fmt(r.rejectedQuantity)}</td>
                      <td className="p-2 text-right" title={q.text}>
                        {fmt(r.outstandingQuantity)}
                      </td>
                      <td className="p-2 text-right">
                        {r.discrepancyCount > 0 ? (
                          <span className="inline-flex items-center gap-1 text-amber-600">
                            <TriangleAlert className="size-3" />
                            {r.discrepancyCount}
                          </span>
                        ) : (
                          '—'
                        )}
                      </td>
                      <td className="p-2">
                        <div className="flex gap-2">
                          <Button variant="ghost" onClick={() => setDetailId(r.id)}>
                            详情
                          </Button>
                          {actions.includes('receive') && (
                            <Button variant="ghost" onClick={() => setReceiving(r)}>
                              <PackagePlus />
                              实收
                            </Button>
                          )}
                          {actions.includes('close') && (
                            <Button variant="ghost" onClick={() => setClosing(r)}>
                              关单
                            </Button>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <p className="text-muted-foreground mt-3 text-xs">
            还差 = 预期 − 实收 − 拒收，货还在路上，**不是差异**；拒收量不入库、不写分录。
          </p>
        </SectionCard>
      )}

      {caps && (
        <SectionCard title="来源与能力">
          <div className="flex flex-col gap-2">
            {availableSources.map((s) => (
              <p key={s.value} className="text-sm">
                <span className="font-medium">{sourceCaption(s)}</span>
                <span className="text-muted-foreground ml-2">{s.note}</span>
              </p>
            ))}
            {unavailableSources.map((s) => (
              <p key={s.value} className="text-muted-foreground text-sm">
                <span>{sourceCaption(s)}</span>
                <span className="ml-2">{s.note}</span>
              </p>
            ))}
          </div>
        </SectionCard>
      )}

      {creating && caps && (
        <CreateDialog
          caps={caps}
          warehouses={warehouses}
          skus={skus}
          qualities={qualities}
          legalEntities={legalEntities}
          onClose={() => setCreating(false)}
          onSaved={() => {
            setCreating(false);
            void load();
          }}
        />
      )}

      {receiving && (
        <ReceiveDialog
          receipt={receiving}
          qualities={qualities}
          lots={[]}
          onClose={() => setReceiving(null)}
          onSaved={() => {
            setReceiving(null);
            void load();
          }}
        />
      )}

      {closing && (
        <CloseDialog
          receipt={closing}
          onClose={() => setClosing(null)}
          onSaved={() => {
            setClosing(null);
            void load();
          }}
        />
      )}

      {detailId && (
        <DetailDialog
          receiptId={detailId}
          onClose={() => setDetailId(null)}
          onChanged={() => void load()}
        />
      )}
    </div>
  );
}

function CreateDialog(props: {
  caps: GoodsReceiptCapabilities;
  warehouses: Option[];
  skus: Option[];
  qualities: Option[];
  legalEntities: Option[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const { caps, warehouses, skus, qualities, legalEntities, onClose, onSaved } = props;
  const available = caps.sources.filter((s) => s.available);
  const [sourceType, setSourceType] = useState(available[0]?.value ?? '');
  const [sourceRefId, setSourceRefId] = useState('');
  const [sourceRefCode, setSourceRefCode] = useState('');
  const [warehouseId, setWarehouseId] = useState(warehouses[0]?.id ?? '');
  const [legalEntityId, setLegalEntityId] = useState(legalEntities[0]?.id ?? '');
  const [skuId, setSkuId] = useState(skus[0]?.id ?? '');
  const [qualityStatusId, setQualityStatusId] = useState(qualities[0]?.id ?? '');
  const [expectedQuantity, setExpectedQuantity] = useState('1');
  const [carrier, setCarrier] = useState('');
  const [waybillNo, setWaybillNo] = useState('');
  const [evidenceNote, setEvidenceNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      await createGoodsReceipt({
        sourceType,
        sourceRefId: sourceType === 'TRANSFER' ? sourceRefId : null,
        sourceRefCode,
        warehouseId,
        legalEntityId,
        carrier,
        waybillNo,
        evidenceNote,
        lines: [
          {
            skuId,
            qualityStatusId,
            expectedQuantity: Number(expectedQuantity),
          },
        ],
      });
      onSaved();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>新建收货单（草稿）</ModalTitle>
          <ModalDescription>草稿只是意图，不动库存 —— 库存要等第一次实收才变化。</ModalDescription>
        </ModalHeader>
        <div className="grid gap-3 md:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">来源</span>
            <select
              className="rounded-md border px-3 py-2"
              value={sourceType}
              onChange={(e) => setSourceType(e.target.value)}
            >
              {available.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
            <span className="text-muted-foreground text-xs">
              {available.find((s) => s.value === sourceType)?.note}
            </span>
          </label>
          {sourceType === 'TRANSFER' && (
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">来源调拨单 id</span>
              <input
                className="rounded-md border px-3 py-2"
                value={sourceRefId}
                onChange={(e) => setSourceRefId(e.target.value)}
                placeholder="已发出（在途 / 部分收 / 已收）的调拨单"
              />
            </label>
          )}
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">来源单号（可读快照）</span>
            <input
              className="rounded-md border px-3 py-2"
              value={sourceRefCode}
              onChange={(e) => setSourceRefCode(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">收货仓</span>
            <select
              className="rounded-md border px-3 py-2"
              value={warehouseId}
              onChange={(e) => setWarehouseId(e.target.value)}
            >
              {warehouses.map((w) => (
                <option key={w.id} value={w.id}>
                  {w.code} {w.name}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">货权法人</span>
            <select
              className="rounded-md border px-3 py-2"
              value={legalEntityId}
              onChange={(e) => setLegalEntityId(e.target.value)}
            >
              {legalEntities.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.code} {l.name}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">SKU</span>
            <select
              className="rounded-md border px-3 py-2"
              value={skuId}
              onChange={(e) => setSkuId(e.target.value)}
            >
              {skus.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.code} {s.name}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">落库质量状态</span>
            <select
              className="rounded-md border px-3 py-2"
              value={qualityStatusId}
              onChange={(e) => setQualityStatusId(e.target.value)}
            >
              {qualities.map((q) => (
                <option key={q.id} value={q.id}>
                  {q.code} {q.name}
                </option>
              ))}
            </select>
            <span className="text-muted-foreground text-xs">
              破损与待检货挂到待检 / 残次，不会直接变成可售。
            </span>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">预期数量</span>
            <input
              className="rounded-md border px-3 py-2"
              value={expectedQuantity}
              inputMode="decimal"
              onChange={(e) => setExpectedQuantity(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">承运商</span>
            <input
              className="rounded-md border px-3 py-2"
              value={carrier}
              onChange={(e) => setCarrier(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">运单 / 箱单号</span>
            <input
              className="rounded-md border px-3 py-2"
              value={waybillNo}
              onChange={(e) => setWaybillNo(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm md:col-span-2">
            <span className="text-muted-foreground">收货凭证说明</span>
            <textarea
              className="rounded-md border px-3 py-2"
              value={evidenceNote}
              onChange={(e) => setEvidenceNote(e.target.value)}
            />
          </label>
        </div>
        {error && <p className="text-destructive mt-3 text-sm">{error}</p>}
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button onClick={() => void submit()} disabled={busy}>
            建单
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

function ReceiveDialog(props: {
  receipt: GoodsReceiptRow;
  qualities: Option[];
  lots: Option[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const { receipt, onClose, onSaved } = props;
  const [detail, setDetail] = useState<Awaited<ReturnType<typeof fetchGoodsReceiptDetail>> | null>(
    null,
  );
  const [error, setError] = useState<string | null>(null);
  const [locationId, setLocationId] = useState('');
  const [waybillNo, setWaybillNo] = useState('');
  const [notes, setNotes] = useState('');
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  const [rejects, setRejects] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void (async () => {
      try {
        setDetail(await fetchGoodsReceiptDetail(receipt.id));
      } catch (e) {
        setError(errorText(e));
      }
    })();
  }, [receipt.id]);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      const lines: GoodsReceiptReceiveLineRequest[] = (detail?.lines ?? [])
        .map((l) => ({
          lineId: l.id,
          receivedQuantity: Number(quantities[l.id] ?? 0),
          rejectedQuantity: Number(rejects[l.id] ?? 0),
          lotId: null,
          expiryDate: null,
          serialNumbers: [] as string[],
        }))
        .filter((l) => l.receivedQuantity > 0 || l.rejectedQuantity > 0);
      if (lines.length === 0) {
        setError('请至少录入一行实收或拒收数量');
        return;
      }
      await receiveGoods(receipt.id, {
        expectedVersion: receipt.version,
        // 留空表示「按当前时刻」：后端把 null 落成 now()，
        // 界面上写死一个时间戳只会让人误以为是那会儿收的货。
        receivedAt: '',
        locationId,
        waybillNo,
        notes,
        lines,
        discrepancies: [],
      });
      onSaved();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>录入实收 — {receipt.receiptCode}</ModalTitle>
          <ModalDescription>
            实收会转入库存并写入库分录；拒收部分**不入库、不写分录**，只留差异记录。
          </ModalDescription>
        </ModalHeader>
        {!detail ? (
          <p className="text-muted-foreground text-sm">正在加载明细…</p>
        ) : (
          <div className="flex flex-col gap-3">
            <div className="grid gap-3 md:grid-cols-3">
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">落库库位</span>
                <input
                  className="rounded-md border px-3 py-2"
                  value={locationId}
                  onChange={(e) => setLocationId(e.target.value)}
                  placeholder="收货仓下的库位 id"
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">本次运单号</span>
                <input
                  className="rounded-md border px-3 py-2"
                  value={waybillNo}
                  onChange={(e) => setWaybillNo(e.target.value)}
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">备注</span>
                <input
                  className="rounded-md border px-3 py-2"
                  value={notes}
                  onChange={(e) => setNotes(e.target.value)}
                />
              </label>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="p-2">明细</th>
                    <th className="p-2 text-right">还差</th>
                    <th className="p-2">本次实收</th>
                    <th className="p-2">本次拒收</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.lines.map((l) => (
                    <tr key={l.id} className="border-b last:border-0">
                      <td className="p-2 text-xs">{lineCaption(l)}</td>
                      <td className="p-2 text-right">{fmt(receivable(l))}</td>
                      <td className="p-2">
                        <input
                          className="w-24 rounded-md border px-2 py-1"
                          inputMode="decimal"
                          value={quantities[l.id] ?? ''}
                          onChange={(e) => setQuantities((q) => ({ ...q, [l.id]: e.target.value }))}
                        />
                      </td>
                      <td className="p-2">
                        <input
                          className="w-24 rounded-md border px-2 py-1"
                          inputMode="decimal"
                          value={rejects[l.id] ?? ''}
                          onChange={(e) => setRejects((q) => ({ ...q, [l.id]: e.target.value }))}
                        />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
        {error && <p className="text-destructive mt-3 text-sm">{error}</p>}
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button onClick={() => void submit()} disabled={busy || !detail}>
            提交实收
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

function CloseDialog(props: {
  receipt: GoodsReceiptRow;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { receipt, onClose, onSaved } = props;
  const [reason, setReason] = useState('');
  const [notes, setNotes] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      await closeGoodsReceipt(receipt.id, { expectedVersion: receipt.version, reason, notes });
      onSaved();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>关单 — {receipt.receiptCode}</ModalTitle>
          <ModalDescription>
            剩余未到齐的部分会如实记成「少收」，**不会**被悄悄抹平。已关闭是终态。
          </ModalDescription>
        </ModalHeader>
        <div className="flex flex-col gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关单原因</span>
            <input
              className="rounded-md border px-3 py-2"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="供应商少发 / 取消发货 / 已全部到齐"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">备注</span>
            <input
              className="rounded-md border px-3 py-2"
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
            />
          </label>
        </div>
        {error && <p className="text-destructive mt-3 text-sm">{error}</p>}
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button onClick={() => void submit()} disabled={busy}>
            关单
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

function DetailDialog(props: { receiptId: string; onClose: () => void; onChanged: () => void }) {
  const { receiptId, onClose, onChanged } = props;
  const [detail, setDetail] = useState<Awaited<ReturnType<typeof fetchGoodsReceiptDetail>> | null>(
    null,
  );
  const [movements, setMovements] = useState<Awaited<
    ReturnType<typeof fetchGoodsReceiptMovements>
  > | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setDetail(await fetchGoodsReceiptDetail(receiptId));
      setMovements(await fetchGoodsReceiptMovements(receiptId));
    } catch (e) {
      setError(errorText(e));
    }
  }, [receiptId]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>{detail?.receipt.receiptCode ?? '收货单详情'}</ModalTitle>
          <ModalDescription>
            {detail
              ? `${detail.receipt.sourceTypeLabel} · ${detail.receipt.warehouseCode} · ${detail.receipt.statusLabel}`
              : '加载中…'}
          </ModalDescription>
        </ModalHeader>
        {error && <p className="text-destructive text-sm">{error}</p>}
        {detail && (
          <div className="flex flex-col gap-4">
            <div className="rounded-md border p-3 text-sm">
              <p>{receiptQuantityText(detail.receipt).text}</p>
              <p className="text-muted-foreground mt-1 text-xs">{detail.conservationNote}</p>
            </div>

            <div>
              <h4 className="mb-2 text-sm font-medium">明细行</h4>
              <ul className="flex flex-col gap-1 text-sm">
                {detail.lines.map((l) => (
                  <li key={l.id} className="text-muted-foreground">
                    {lineCaption(l)}
                  </li>
                ))}
              </ul>
            </div>

            <div>
              <h4 className="mb-2 text-sm font-medium">实收事件（逐笔留痕）</h4>
              {detail.events.length === 0 ? (
                <p className="text-muted-foreground text-sm">还没有实收。</p>
              ) : (
                <ul className="flex flex-col gap-2 text-sm">
                  {detail.events.map((e) => (
                    <li key={e.id} className="rounded-md border p-2">
                      <p className="flex items-center gap-2">
                        <Truck className="size-3" />
                        {moment(e.receivedAt)} · {e.locationCode} {e.locationName}
                        {e.waybillNo ? ` · ${e.waybillNo}` : ''}
                      </p>
                      <ul className="text-muted-foreground mt-1 flex flex-col gap-0.5 text-xs">
                        {e.lines.map((l) => (
                          <li key={l.id}>
                            {l.skuCode} 实收 {fmt(l.receivedQuantity)} / 拒收{' '}
                            {fmt(l.rejectedQuantity)}
                            {l.lotCode ? ` · 批次 ${l.lotCode}` : ''}
                            {l.expiryDate ? ` · 效期 ${l.expiryDate}` : ''}
                            {l.serialNumbers.length > 0
                              ? ` · 序列号 ${l.serialNumbers.join('、')}`
                              : ''}
                          </li>
                        ))}
                      </ul>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div>
              <h4 className="mb-2 text-sm font-medium">差异记录（只记录事实）</h4>
              {detail.discrepancies.length === 0 ? (
                <p className="text-muted-foreground text-sm">没有差异。</p>
              ) : (
                <ul className="flex flex-col gap-1 text-sm">
                  {detail.discrepancies.map((d) => (
                    <li key={d.id}>
                      <span className="font-medium">
                        {receiptDiscrepancyLabels[d.discrepancyType]}
                      </span>{' '}
                      {fmt(d.quantity)}
                      {d.skuCode ? ` · ${d.skuCode}` : ''} · {moment(d.recordedAt)}
                      {d.reasonCode ? ` · ${d.reasonCode}` : ''}
                      {d.reasonNote ? ` · ${d.reasonNote}` : ''}
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {movements && (
              <div>
                <h4 className="mb-2 text-sm font-medium">库存分录（复用 7.1.2.1 台账）</h4>
                <p className="text-muted-foreground mb-2 text-xs">{movements.attributionNote}</p>
                <ul className="flex flex-col gap-1 text-sm">
                  {movements.movements.rows.map((m) => (
                    <li key={m.transactionId}>
                      {moment(m.occurredAt)} · {m.kind} · 分录 {m.entryCount} 条 · 净在手{' '}
                      {fmt(m.onHandDelta)}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            <div>
              <h4 className="mb-2 text-sm font-medium">可执行动作</h4>
              {detail.transitions.length === 0 ? (
                <p className="text-muted-foreground text-sm">已关闭是终态，不再接受任何动作。</p>
              ) : (
                <p className="text-sm">
                  可转为：{detail.transitions.map((t) => receiptStatusLabels[t.to]).join('、')}
                </p>
              )}
            </div>
          </div>
        )}
        <ModalFooter>
          <Button variant="ghost" onClick={() => void load()}>
            <RefreshCw />
            刷新
          </Button>
          <Button
            onClick={() => {
              onChanged();
              onClose();
            }}
          >
            关闭
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
