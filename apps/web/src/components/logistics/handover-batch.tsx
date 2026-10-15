'use client';

import { useCallback, useEffect, useState } from 'react';
import { PackageCheck, RefreshCw, Truck } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  PageHeader,
  SectionCard,
  StatCard,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@starcon/ui';
import type {
  HandoverBatchDetail,
  HandoverBatchRow,
  HandoverCapabilities,
  HandoverCandidatePage,
  HandoverGroupBucket,
  HandoverManifestItemRow,
  ManifestItemInput,
  SyncCandidatesResult,
} from './handover-batch-model';
import {
  batchStatusLabel,
  batchStatusTone,
  canCancel,
  canDispatch,
  canRecordReceipt,
  carrierChannelLabel,
  createBatch,
  dimensionText,
  dispatchBatch,
  fetchBatches,
  fetchBatchDetail,
  fetchCapabilities,
  fetchCandidates,
  fetchGroupPreview,
  fetchManifest,
  manifestInputFromCandidate,
  receiptNeedsDetail,
  receiptStatusLabel,
  cancelBatch,
  recordReceipt,
  syncCandidates,
  unavailableDimensions,
  validateBatch,
  waybillText,
  weightText,
} from './handover-batch-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 10.3.2.1 交运批次。
 *
 * <p>蓝图：「把已发货包裹按承运商与交运时间组批，交接给承运商并登记交接清单，
 * 减少交接差错」。L3 锚点是 10.3.2「交运管理」。
 *
 * <p>🔴 贯穿整页的约束：**批次交接是责任分界点——批次内包裹的丢失责任在承运商，
 * 需要交接清单留证**。所以这个界面上：
 *
 * <ul>
 *   <li>交接清单**只读**，没有编辑框也没有删除按钮 —— 凭据一旦可改写，
 *       「责任在承运商」就没有依据了</li>
 *   <li>能写的只有「组批」「登记交接」「带回回执」三个**登记事实**的动作</li>
 *   <li>运单号没有时显示缺口与原因，不显示空白，也不拿别的号顶上</li>
 *   <li>未交付能力（承运商对接 10.1.1.1、运单号 10.2.1.2、揽收 10.3.2.2…）
 *       在页面上逐段标成灰块，不假装有数据</li>
 * </ul>
 */
export function HandoverBatchWorkspace() {
  const [capabilities, setCapabilities] = useState<HandoverCapabilities | null>(null);
  const [candidates, setCandidates] = useState<HandoverCandidatePage | null>(null);
  const [groups, setGroups] = useState<HandoverGroupBucket[]>([]);
  const [batches, setBatches] = useState<HandoverBatchRow[]>([]);
  const [summary, setSummary] = useState<
    Awaited<ReturnType<typeof fetchBatches>>['summary'] | null
  >(null);
  const [selected, setSelected] = useState('');
  const [detail, setDetail] = useState<HandoverBatchDetail | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [caps, cands, previews, page] = await Promise.all([
        fetchCapabilities(),
        fetchCandidates(),
        fetchGroupPreview(),
        fetchBatches(),
      ]);
      setCapabilities(caps);
      setCandidates(cands);
      setGroups(previews);
      setBatches(page.items);
      setSummary(page.summary);
      setSelected((prev) =>
        prev && page.items.some((b) => b.id === prev) ? prev : (page.items[0]?.id ?? ''),
      );
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, []);

  const loadDetail = useCallback(async (batchId: string) => {
    if (!batchId) {
      setDetail(null);
      return;
    }
    try {
      setDetail(await fetchBatchDetail(batchId));
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void loadDetail(selected);
  }, [selected, loadDetail]);

  async function run<T>(work: () => Promise<T>): Promise<T | undefined> {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      return await work();
    } catch (e) {
      setError(errorText(e));
      return undefined;
    } finally {
      setBusy(false);
    }
  }

  /** 同步候选池：候选**只**来自上游 9.4.1.1 已交出待发货队列的单元。 */
  async function sync() {
    const result = (await run(() => syncCandidates())) as SyncCandidatesResult | undefined;
    if (result) {
      setNotice(
        `同步完成：读到 ${result.fetched} 条，新建 ${result.created} 条，更新 ${result.updated} 条。${result.note}`,
      );
      await load();
    }
  }

  /**
   * 组批：把某个承运商分桶下的候选逐件写成交接清单。
   *
   * <p>交接时刻用**当前时间显式提交**，不是让服务端拿墙上时钟 —— 交运时间是
   * 一个要被留证的业务时刻，必须在界面上看得见、也可改。
   */
  async function buildBatch(group: HandoverGroupBucket) {
    const rows = (candidates?.items ?? []).filter(
      (c) => c.status === 'PENDING' && c.carrierCode === group.carrierCode,
    );
    if (rows.length === 0) {
      setError(`承运商 ${group.carrierCode} 没有待组批的候选包裹`);
      return;
    }
    const items: ManifestItemInput[] = [];
    rows.forEach((row) => {
      for (let p = 1; p <= Math.max(row.parcelCount, 1); p += 1) {
        items.push(
          manifestInputFromCandidate(
            row,
            p,
            Number((row.totalWeightKg / Math.max(row.parcelCount, 1)).toFixed(6)),
          ),
        );
      }
    });
    const handoverAt = new Date(`${group.handoverBucket}T09:30:00Z`).toISOString();
    const problems = validateBatch(group.carrierCode, handoverAt, items);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    const created = (await run(() => createBatch(group.carrierCode, handoverAt, items))) as
      HandoverBatchDetail | undefined;
    if (created) {
      setNotice(
        `已组批 ${created.batch.batchNo}：逐件清单 ${created.manifest.length} 件。交接清单一经写入不可修改。`,
      );
      setSelected(created.batch.id);
      await load();
    }
  }

  async function dispatch(row: HandoverBatchRow) {
    const note =
      window.prompt('登记交接备注（承运商系统对接尚未接通，批次将停在「待接通」）') ?? '';
    const done = (await run(() => dispatchBatch(row.id, new Date().toISOString(), note.trim()))) as
      HandoverBatchDetail | undefined;
    if (done) {
      setNotice(`${row.batchNo} 已登记交接。`);
      await load();
      await loadDetail(row.id);
    }
  }

  async function receipt(row: HandoverBatchRow) {
    const ref = window.prompt('承运商回执号（没有回执号的回执无法用于事后追责）');
    if (!ref) return;
    const status = window.prompt(
      '回执状态：ACCEPTED / ACCEPTED_WITH_DISCREPANCY / REJECTED',
      'ACCEPTED',
    );
    if (!status) return;
    let reason = '';
    if (receiptNeedsDetail(status.toUpperCase())) {
      reason = window.prompt('差异 / 拒收原因（必填，否则无法发起理赔）') ?? '';
      if (!reason.trim()) {
        setError('有差异或被拒的交接必须写明原因');
        return;
      }
    }
    const done = (await run(() =>
      recordReceipt(row.id, ref.trim(), status.toUpperCase(), reason.trim()),
    )) as HandoverBatchDetail | undefined;
    if (done) {
      setNotice(`${row.batchNo} 已带回回执：${receiptStatusLabel(done.batch.receiptStatus)}。`);
      await load();
      await loadDetail(row.id);
    }
  }

  async function cancel(row: HandoverBatchRow) {
    const note = window.prompt('作废原因') ?? '';
    const done = (await run(() => cancelBatch(row.id, note.trim()))) as
      HandoverBatchDetail | undefined;
    if (done) {
      setNotice(`${row.batchNo} 已作废。登记过交接这件事仍留在事件时间线上。`);
      await load();
      await loadDetail(row.id);
    }
  }

  /** 交接清单导出：追责时直接给对方的那一份（与详情同源，不是另算一套）。 */
  async function exportManifest() {
    if (!detail) return;
    const export_ = await run(() => fetchManifest(detail.batch.id));
    if (export_) {
      setNotice(
        `交接清单 ${export_.batchNo}：${export_.itemCount} 件，合计 ${weightText(export_.totalWeightKg)}。此清单不可修改或删除。`,
      );
    }
  }

  const current = batches.find((b) => b.id === selected) ?? null;
  const gaps = unavailableDimensions(capabilities);

  return (
    <>
      <PageHeader
        title="交运批次"
        description="把已发货包裹按承运商与交运时间组批，交接给承运商并登记交接清单。批次交接是责任分界点：交接后包裹丢失责任在承运商，交接清单是唯一凭据。"
      />

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
      {notice ? <p className="text-muted-foreground text-sm">{notice}</p> : null}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="待组批包裹"
          value={String(candidates?.summary.pendingCount ?? 0)}
          comparisonLabel={`${candidates?.summary.pendingParcels ?? 0} 件 · ${weightText(candidates?.summary.pendingWeightKg ?? 0)}`}
        />
        <StatCard
          label="待承运商接通"
          value={String(summary?.dispatchPendingCount ?? 0)}
          comparisonLabel="已登记交接，等待 10.1.1.1 通道"
        />
        <StatCard
          label="已交接"
          value={String(summary?.handedOverCount ?? 0)}
          comparisonLabel={`${summary?.handedOverParcels ?? 0} 件 · 责任在承运商`}
        />
        <StatCard
          label="回执有差异"
          value={String(summary?.receiptMismatchCount ?? 0)}
          comparisonLabel={
            (summary?.receiptMismatchCount ?? 0) > 0 ? '需人工跟进与理赔' : '回执都对得上'
          }
        />
      </div>

      <Tabs defaultValue="batches" className="mt-6">
        <TabsList>
          <TabsTrigger value="batches">交运批次</TabsTrigger>
          <TabsTrigger value="candidates">候选池</TabsTrigger>
          <TabsTrigger value="manifest">交接清单</TabsTrigger>
          <TabsTrigger value="capabilities">能力与边界</TabsTrigger>
        </TabsList>

        <TabsContent value="batches">
          <SectionCard
            title="交运批次"
            extra={
              <div className="flex gap-2">
                <Button size="sm" variant="ghost" onClick={() => void load()} disabled={busy}>
                  <RefreshCw className="mr-1.5 h-4 w-4" />
                  刷新
                </Button>
                <Button size="sm" onClick={() => void sync()} disabled={busy}>
                  同步候选池
                </Button>
              </div>
            }
          >
            {batches.length === 0 ? (
              <EmptyState
                icon={<Truck className="h-6 w-6" />}
                title="还没有交运批次"
                description="先同步候选池（上游 9.4.1.1 已交出待发货队列的包裹），再按承运商与交运时间组批。"
              />
            ) : (
              <ul className="divide-y">
                {batches.map((b) => (
                  <li key={b.id} className="flex items-start justify-between gap-4 py-3">
                    <button
                      type="button"
                      className="min-w-0 text-left"
                      onClick={() => setSelected(b.id)}
                    >
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium">{b.batchNo}</span>
                        <Badge tone="info">{b.carrierCode}</Badge>
                        <Badge tone={batchStatusTone(b.status)}>{batchStatusLabel(b.status)}</Badge>
                        {b.receiptStatus === 'REJECTED' ? <Badge tone="warning">拒收</Badge> : null}
                        {b.receiptStatus === 'ACCEPTED_WITH_DISCREPANCY' ? (
                          <Badge tone="warning">有差异</Badge>
                        ) : null}
                      </div>
                      <p className="text-muted-foreground mt-1 text-sm">
                        交运 {new Date(b.handoverAt).toLocaleString()} · {b.unitCount} 单元 /{' '}
                        {b.parcelCount} 件 · {weightText(b.totalWeightKg)} · 清单 {b.manifestCount}{' '}
                        行
                      </p>
                      {b.statusDetail ? (
                        <p className="text-muted-foreground mt-1 text-sm">{b.statusDetail}</p>
                      ) : null}
                      {b.receiptDetail ? (
                        <p className="text-destructive mt-1 text-sm">{b.receiptDetail}</p>
                      ) : null}
                    </button>
                    <div className="flex shrink-0 gap-2">
                      {canDispatch(b) ? (
                        <Button size="sm" onClick={() => void dispatch(b)} disabled={busy}>
                          登记交接
                        </Button>
                      ) : null}
                      {canRecordReceipt(b) ? (
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => void receipt(b)}
                          disabled={busy}
                        >
                          带回回执
                        </Button>
                      ) : null}
                      {canCancel(b) ? (
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => void cancel(b)}
                          disabled={busy}
                        >
                          作废
                        </Button>
                      ) : null}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>
        </TabsContent>

        <TabsContent value="candidates">
          <SectionCard
            title="候选包裹池"
            extra={
              <Button size="sm" variant="ghost" onClick={() => void load()} disabled={busy}>
                <RefreshCw className="mr-1.5 h-4 w-4" />
                刷新
              </Button>
            }
          >
            <p className="text-muted-foreground mb-3 text-sm">
              候选池只读上游 9.4.1.1 的 <code>HANDED_OVER</code> 单元 —— 已完成打包与面单、
              已交出待发货队列、正等着交给承运商的那些件。本服务不碰 trade 的库。
            </p>

            {groups.length === 0 ? (
              <EmptyState
                icon={<PackageCheck className="h-6 w-6" />}
                title="候选池是空的"
                description="点右上角「同步候选池」从上游拉取。读到 0 条时先确认上游是否真的没有已交出的包裹 —— 上游不可达会被明确报错，不会静默显示 0。"
              />
            ) : (
              <ul className="divide-y">
                {groups.map((g) => (
                  <li
                    key={`${g.carrierCode}-${g.handoverBucket}`}
                    className="flex items-start justify-between gap-4 py-3"
                  >
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium">{g.carrierCode}</span>
                        <Badge tone="info">{carrierChannelLabel(g.carrierChannel)}</Badge>
                        <Badge tone="neutral">{g.handoverBucket}</Badge>
                        {g.missingWaybillCount > 0 ? (
                          <Badge tone="warning">{g.missingWaybillCount} 票无运单号</Badge>
                        ) : null}
                      </div>
                      <p className="text-muted-foreground mt-1 text-sm">
                        {g.unitCount} 单元 / {g.parcelCount} 件 · {weightText(g.totalWeightKg)}
                      </p>
                    </div>
                    <Button size="sm" onClick={() => void buildBatch(g)} disabled={busy}>
                      组批
                    </Button>
                  </li>
                ))}
              </ul>
            )}

            {(candidates?.items.length ?? 0) > 0 ? (
              <div className="mt-6">
                <p className="mb-2 text-sm font-medium">逐票候选</p>
                <ul className="divide-y">
                  {(candidates?.items ?? []).map((c) => {
                    const wb = waybillText(c.waybillNo, c.waybillGapCode, c.waybillGapReason);
                    return (
                      <li key={c.id} className="flex items-start justify-between gap-4 py-2">
                        <div className="min-w-0">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="font-medium">{c.outboundOrderNo}</span>
                            <Badge tone="info">{c.carrierCode}</Badge>
                            <Badge tone={c.status === 'PENDING' ? 'warning' : 'success'}>
                              {c.status === 'PENDING' ? '待组批' : '已入批次'}
                            </Badge>
                            {wb.missing ? <Badge tone="warning">无运单号</Badge> : null}
                          </div>
                          <p className="text-muted-foreground mt-1 text-sm">
                            {c.warehouseRef} · {c.salesOrderNo} · {c.parcelCount} 件 ·{' '}
                            {weightText(c.totalWeightKg)}
                          </p>
                          <p className="text-muted-foreground mt-1 text-sm">运单号：{wb.text}</p>
                        </div>
                      </li>
                    );
                  })}
                </ul>
              </div>
            ) : null}
          </SectionCard>
        </TabsContent>

        <TabsContent value="manifest">
          <SectionCard
            title="交接清单（法律凭据 · 只读）"
            extra={
              <Button
                size="sm"
                variant="ghost"
                onClick={() => void exportManifest()}
                disabled={busy || !detail}
              >
                导出清单
              </Button>
            }
          >
            {!current ? (
              <EmptyState
                icon={<PackageCheck className="h-6 w-6" />}
                title="先选一个交运批次"
                description="交接清单逐件记录交接时刻、包裹标识、运单号、重量尺寸与经手人，是交接后追责的唯一依据。"
              />
            ) : (
              <>
                <p className="text-muted-foreground mb-3 text-sm">
                  {current.batchNo} · {current.carrierCode} · 交运{' '}
                  {new Date(current.handoverAt).toLocaleString()} · 共{' '}
                  {detail?.manifest.length ?? 0} 件。
                  <span className="text-foreground">
                    清单一经写入不可修改或删除 —— 批次交接后包裹丢失责任在承运商。
                  </span>
                </p>

                {current.status === 'HANDED_OVER' ? (
                  <p className="text-muted-foreground mb-3 text-sm">
                    该批次已交接，成员与交接内容不可再被静默改写。
                  </p>
                ) : null}

                <ul className="divide-y">
                  {(detail?.manifest ?? []).map((m: HandoverManifestItemRow) => {
                    const wb = waybillText(m.waybillNo, m.waybillGapCode, m.waybillGapReason);
                    return (
                      <li key={m.id} className="py-2">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-medium">第 {m.parcelNo} 件</span>
                          <span className="text-muted-foreground text-sm">{m.parcelRef}</span>
                          {wb.missing ? (
                            <Badge tone="warning">无运单号</Badge>
                          ) : (
                            <Badge tone="success">有运单号</Badge>
                          )}
                        </div>
                        <p className="text-muted-foreground mt-1 text-sm">
                          {m.warehouseRef} · 出库 {m.outboundOrderNo} ·{' '}
                          {weightText(m.grossWeightKg)} ·{' '}
                          {dimensionText(m.lengthMm, m.widthMm, m.heightMm)}
                        </p>
                        <p className="text-muted-foreground mt-1 text-sm">运单号：{wb.text}</p>
                        <p className="text-muted-foreground mt-1 text-sm">
                          交接于 {new Date(m.handoverAt).toLocaleString()} · 经手人{' '}
                          {m.handedByMembershipId}
                        </p>
                        {m.remark ? (
                          <p className="text-muted-foreground mt-1 text-sm">现场备注：{m.remark}</p>
                        ) : null}
                      </li>
                    );
                  })}
                </ul>

                {(detail?.boundaryNotes.length ?? 0) > 0 ? (
                  <div className="mt-6">
                    <p className="mb-2 text-sm font-medium">边界声明</p>
                    <ul className="text-muted-foreground list-disc space-y-1 pl-5 text-sm">
                      {(detail?.boundaryNotes ?? []).map((n) => (
                        <li key={n}>{n}</li>
                      ))}
                    </ul>
                  </div>
                ) : null}

                {(detail?.timeline.length ?? 0) > 0 ? (
                  <div className="mt-6">
                    <p className="mb-2 text-sm font-medium">事件时间线</p>
                    <ul className="space-y-1">
                      {(detail?.timeline ?? []).map((e) => (
                        <li key={e.id} className="text-muted-foreground text-sm">
                          {new Date(e.occurredAt).toLocaleString()} · {e.eventKind}
                          {e.toStatus ? ` → ${batchStatusLabel(e.toStatus)}` : ''} · {e.detail}
                        </li>
                      ))}
                    </ul>
                  </div>
                ) : null}
              </>
            )}
          </SectionCard>
        </TabsContent>

        <TabsContent value="capabilities">
          <SectionCard title="能力与边界">
            <p className="text-muted-foreground mb-3 text-sm">{capabilities?.boundaryNote ?? ''}</p>
            <p className="text-muted-foreground mb-3 text-sm">{capabilities?.upstreamNote ?? ''}</p>
            <p className="text-muted-foreground mb-4 text-sm">
              {capabilities?.carrierSourceNote ?? ''}
            </p>

            <ul className="divide-y">
              {(capabilities?.dimensions ?? []).map((d) => (
                <li key={d.dimension} className="py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{d.description || d.dimension}</span>
                    {d.available ? (
                      <Badge tone="success">已交付</Badge>
                    ) : (
                      <Badge tone="neutral">未接入 · 归属 {d.ownerModule}</Badge>
                    )}
                  </div>
                  {d.available ? null : (
                    <p className="text-muted-foreground mt-1 text-sm">
                      来源：{d.source} · 影响：{d.impact}
                    </p>
                  )}
                </li>
              ))}
            </ul>

            {gaps.length > 0 ? (
              <p className="text-muted-foreground mt-4 text-sm">
                共 {gaps.length} 项能力未接入。它们在本页显示为灰块而不是空数据 ——
                空列表在界面上与「确实没有记录」无法区分。
              </p>
            ) : null}
          </SectionCard>
        </TabsContent>
      </Tabs>
    </>
  );
}
