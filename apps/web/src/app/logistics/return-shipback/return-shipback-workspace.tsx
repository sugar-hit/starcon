'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Boxes, ClipboardList, PackageOpen, RefreshCw, Ship, Split } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@starcon/ui';
import {
  addLine,
  allocationBalanced,
  allocationSum,
  buildConsignmentPayload,
  buildCostPayload,
  buildDeclarationPayload,
  buildEntryPayload,
  cancelConsignment,
  closeConsignment,
  confirmConsignment,
  costTypeLabel,
  createConsignment,
  dispatchConsignment,
  emptyConsignmentForm,
  emptyCostForm,
  emptyDeclarationForm,
  emptyEntryForm,
  emptySelectedCandidate,
  errorText,
  fetchCapabilities,
  fetchCandidates,
  fetchConsignment,
  fetchConsignments,
  fetchEvents,
  fetchOverview,
  hasResidual,
  moneyText,
  previewTariffAmount,
  quantityText,
  ratioText,
  recordArrival,
  recordCost,
  recordDeclaration,
  recordEntry,
  remainingEntryQty,
  residualSum,
  shipbackStatusLabel,
  transportModeLabel,
  type ConsignmentForm,
  type CostForm,
  type DeclarationForm,
  type ReturnShipbackAllocationRow,
  type ReturnShipbackCapabilities,
  type ReturnShipbackDetail,
  type ReturnShipbackEventPage,
  type ReturnShipbackOverview,
  type ReturnShipbackPage,
  type ReturnShipbackRow,
  type SelectedCandidate,
  type ShipbackCandidateRow,
} from './return-shipback-model';

/**
 * 10.7.1.2 退运回国。
 *
 * <p>蓝图：「管理退运回国：<b>批量退运单（凑单/合箱/报关）</b>、国内收货仓、关税与申报、
 * 回国后的重新入库或报废；<b>支持海运拼箱的成本分摊</b>。」
 *
 * <p>🔴 页面上不可让步的两条：
 *
 * <ul>
 *   <li><b>「凑单 / 合箱」是真的</b>：待退运清单可以**勾选多行**一次成单，
 *       退运单的明细行就是被勾中的那几批货。空单在服务端也会被拒（没法分摊、没法报关）。</li>
 *   <li><b>「成本分摊」是真的</b>：记完成本之后弹窗直接摊开逐行的权重 / 比例 / 金额 / 尾差，
 *       并用 {@link allocationBalanced} 在页面上做守恒自检 ——
 *       合计不等于成本金额就红字报出来。</li>
 * </ul>
 *
 * <p>🔴 页面**不做任何汇率换算**：成本、报关金额、残值是三个独立币种口径，
 * 分行显示、绝不相加。
 */
export function ReturnShipbackWorkspace() {
  const [capabilities, setCapabilities] = useState<ReturnShipbackCapabilities | null>(null);
  const [overview, setOverview] = useState<ReturnShipbackOverview | null>(null);
  const [candidates, setCandidates] = useState<ShipbackCandidateRow[]>([]);
  const [consignments, setConsignments] = useState<ReturnShipbackPage | null>(null);
  const [events, setEvents] = useState<ReturnShipbackEventPage | null>(null);

  const [keyword, setKeyword] = useState('');
  const [candidateKeyword, setCandidateKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [modeFilter, setModeFilter] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  // 合单（凑单）
  const [consignOpen, setConsignOpen] = useState(false);
  const [consignForm, setConsignForm] = useState<ConsignmentForm>(emptyConsignmentForm());
  const [selected, setSelected] = useState<SelectedCandidate[]>([]);
  const [consignError, setConsignError] = useState('');

  // 详情
  const [detail, setDetail] = useState<ReturnShipbackDetail | null>(null);
  const [detailError, setDetailError] = useState('');

  // 报关 / 成本 / 入库分录
  const [declarationOpen, setDeclarationOpen] = useState(false);
  const [declarationForm, setDeclarationForm] = useState<DeclarationForm>(emptyDeclarationForm());
  const [declarationError, setDeclarationError] = useState('');

  const [costOpen, setCostOpen] = useState(false);
  const [costForm, setCostForm] = useState<CostForm>(emptyCostForm('USD'));
  const [costError, setCostError] = useState('');

  const [entryOpen, setEntryOpen] = useState(false);
  const [entryForm, setEntryForm] = useState(emptyEntryForm());
  const [entryError, setEntryError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, ov, cands, list, evts] = await Promise.all([
        fetchCapabilities(),
        fetchOverview(),
        fetchCandidates({ keyword: candidateKeyword, size: 50 }),
        fetchConsignments({ keyword, status: statusFilter, transportMode: modeFilter, size: 50 }),
        fetchEvents({ size: 50 }),
      ]);
      setCapabilities(caps);
      setOverview(ov);
      setCandidates(cands.rows);
      setConsignments(list);
      setEvents(evts);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [keyword, statusFilter, modeFilter, candidateKeyword]);

  useEffect(() => {
    void load();
  }, [load]);

  async function openDetail(id: string) {
    setDetail(null);
    setDetailError('');
    setBusy(true);
    try {
      setDetail(await fetchConsignment(id));
    } catch (e) {
      setDetailError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  function toggleCandidate(candidate: ShipbackCandidateRow) {
    setSelected((prev) =>
      prev.some((row) => row.dispositionOrderId === candidate.dispositionOrderId)
        ? prev.filter((row) => row.dispositionOrderId !== candidate.dispositionOrderId)
        : [...prev, emptySelectedCandidate(candidate)],
    );
  }

  async function submitConsignment() {
    const payload = buildConsignmentPayload(consignForm, selected);
    if ('error' in payload) {
      setConsignError(payload.error);
      return;
    }
    setBusy(true);
    setConsignError('');
    try {
      const created = await createConsignment(payload);
      setConsignOpen(false);
      setConsignForm(emptyConsignmentForm());
      setSelected([]);
      await load();
      setDetail(created);
    } catch (e) {
      setConsignError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitDeclaration() {
    if (!detail) return;
    const payload = buildDeclarationPayload(declarationForm);
    if ('error' in payload) {
      setDeclarationError(payload.error);
      return;
    }
    setBusy(true);
    setDeclarationError('');
    try {
      await recordDeclaration(detail.head.id, payload);
      setDeclarationOpen(false);
      setDeclarationForm(emptyDeclarationForm());
      await load();
      await openDetail(detail.head.id);
    } catch (e) {
      setDeclarationError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitCost() {
    if (!detail) return;
    const payload = buildCostPayload(costForm);
    if ('error' in payload) {
      setCostError(payload.error);
      return;
    }
    setBusy(true);
    setCostError('');
    try {
      await recordCost(detail.head.id, payload);
      setCostOpen(false);
      setCostForm(emptyCostForm(detail.head.costCurrency));
      await load();
      await openDetail(detail.head.id);
    } catch (e) {
      setCostError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitEntry() {
    if (!detail) return;
    const line = detail.lines.find((l) => l.id === entryForm.lineId);
    const remaining = line ? remainingEntryQty(line, detail.entries) : 0;
    const payload = buildEntryPayload(entryForm, remaining);
    if ('error' in payload) {
      setEntryError(payload.error);
      return;
    }
    setBusy(true);
    setEntryError('');
    try {
      await recordEntry(detail.head.id, payload);
      setEntryOpen(false);
      setEntryForm(emptyEntryForm());
      await load();
      await openDetail(detail.head.id);
    } catch (e) {
      setEntryError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function advance(action: 'confirm' | 'dispatch' | 'close' | 'cancel', id: string) {
    setBusy(true);
    setError('');
    try {
      if (action === 'confirm') await confirmConsignment(id);
      else if (action === 'dispatch') {
        const tracking = window.prompt('运单号 / 提单号（可留空）', '');
        if (tracking === null) {
          setBusy(false);
          return;
        }
        await dispatchConsignment(id, tracking.trim() || undefined);
      } else if (action === 'close') await closeConsignment(id);
      else {
        const reason = window.prompt('取消理由（必填）', '');
        if (!reason || !reason.trim()) {
          setBusy(false);
          return;
        }
        await cancelConsignment(id, reason.trim());
      }
      await load();
      await openDetail(id);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  /** 明细行上的分摊合计（按成本 id 归集）。 */
  const allocationsByCost = useMemo(() => {
    const map = new Map<string, ReturnShipbackAllocationRow[]>();
    for (const row of detail?.allocations ?? []) {
      const list = map.get(row.costId) ?? [];
      list.push(row);
      map.set(row.costId, list);
    }
    return map;
  }, [detail]);

  const candidateQty = useMemo(
    () => candidates.reduce((sum, c) => sum + (c.disposedQty ?? 0), 0),
    [candidates],
  );

  return (
    <>
      <PageHeader
        title="退运回国"
        description="批量退运单（凑单 / 合箱 / 报关）、国内收货仓、关税与申报、回国后的重新入库或报废，支持海运拼箱成本分摊。"
      />

      {error ? (
        <Notice tone="danger" title="操作未完成">
          {error}
        </Notice>
      ) : null}

      {capabilities ? (
        <Notice tone="warning" title="退运是批量、长周期、高单票成本的操作">
          {capabilities.consolidationNote} {capabilities.allocationNote}
        </Notice>
      ) : null}

      <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="退运单"
          value={String(overview?.consignmentCount ?? 0)}
          comparisonLabel={`草稿 ${overview?.draftCount ?? 0} · 已发出 ${overview?.dispatchedCount ?? 0} · 已到货 ${overview?.arrivedCount ?? 0}`}
        />
        <StatCard
          label="待退运候选"
          value={String(overview?.candidateCount ?? 0)}
          comparisonLabel={`共 ${quantityText(overview?.candidateQty ?? 0)} 件（10.7.1.1 决策为退运回国）`}
        />
        <StatCard
          label="明细行"
          value={String(overview?.lineCount ?? 0)}
          comparisonLabel="退运单里的货行数"
        />
        <StatCard
          label="退运成本合计"
          value={
            overview && overview.costTotals.length === 1
              ? moneyText(
                  overview.costTotals[0]?.amount,
                  overview.costTotals[0]?.currency,
                )
              : `${overview?.costTotals.length ?? 0} 个币种`
          }
          comparisonLabel="按币种分行，跨币种绝不相加"
        />
      </div>

      <Tabs defaultValue="candidates" className="mt-6">
        <TabsList>
          <TabsTrigger value="candidates">待退运清单</TabsTrigger>
          <TabsTrigger value="consignments">退运单</TabsTrigger>
          <TabsTrigger value="events">留痕</TabsTrigger>
        </TabsList>

        {/* ---------------- 待退运清单（凑单入口） ---------------- */}
        <TabsContent value="candidates">
          <SectionCard
            title="待退运清单"
            description="只来自 10.7.1.1 决策为「退运回国」的处置单（转销售 / 弃置销毁不会出现在这里）。勾选多行即可合单。"
            extra={
              <Button
                size="sm"
                onClick={() => {
                  setConsignOpen(true);
                  setConsignError('');
                }}
                disabled={busy || candidates.length === 0}
              >
                <Split className="mr-1.5 h-4 w-4" />
                合单 / 凑单（已选 {selected.length}）
              </Button>
            }
          >
            <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
              <Input
                className="max-w-xs"
                placeholder="SKU / 收货单号 / 批次"
                value={candidateKeyword}
                onChange={(e) => setCandidateKeyword(e.target.value)}
              />
              <span className="text-muted-foreground">
                共 {candidates.length} 条候选，{quantityText(candidateQty)} 件
              </span>
            </div>

            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载待退运清单…</p>
            ) : candidates.length === 0 ? (
              <EmptyState
                icon={<PackageOpen className="h-6 w-6" />}
                title="没有待退运的货"
                description="待退运清单来自 10.7.1.1 已下发且去向为「退运回国」的处置单；没有这类处置单时这里就是空的。"
              />
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">合单</th>
                    <th className="py-2">处置单 / SKU</th>
                    <th className="py-2">收货单号</th>
                    <th className="py-2">处置数量</th>
                    <th className="py-2">残值</th>
                    <th className="py-2">下发时间</th>
                  </tr>
                </thead>
                <tbody>
                  {candidates.map((c) => (
                    <tr key={c.dispositionOrderId} className="border-b last:border-0">
                      <td className="py-2">
                        <input
                          type="checkbox"
                          aria-label={`选择 ${c.skuCode}`}
                          checked={selected.some(
                            (row) => row.dispositionOrderId === c.dispositionOrderId,
                          )}
                          onChange={() => toggleCandidate(c)}
                        />
                      </td>
                      <td className="py-2">
                        <div className="font-mono text-xs">{c.dispositionNo}</div>
                        <div>{c.skuCode}</div>
                        <div className="text-muted-foreground text-xs">{c.skuName}</div>
                      </td>
                      <td className="py-2 font-mono text-xs">{c.receiptNo}</td>
                      <td className="py-2">{quantityText(c.disposedQty)}</td>
                      <td className="py-2">{moneyText(c.salvageAmount, c.salvageCurrency)}</td>
                      <td className="py-2 text-xs">{c.issuedAt}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>
        </TabsContent>

        {/* ---------------- 退运单 ---------------- */}
        <TabsContent value="consignments">
          <SectionCard
            title="退运单"
            description="一次批量退运的批次：多批退货的货合在一张单里，单票成本由此摊薄。成本合计是查询时现算的 SUM（不落表）。"
            extra={
              <Button size="sm" variant="ghost" onClick={() => void load()} disabled={busy}>
                <RefreshCw className="mr-1.5 h-4 w-4" />
                刷新
              </Button>
            }
          >
            <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
              <Input
                className="max-w-xs"
                placeholder="退运单号 / 运单号 / 仓库"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
              />
              <select
                className="rounded border px-2 py-1"
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value)}
              >
                <option value="">全部状态</option>
                <option value="DRAFT">草稿</option>
                <option value="CONFIRMED">已确认</option>
                <option value="DISPATCHED">已发出</option>
                <option value="ARRIVED">已到货</option>
                <option value="CLOSED">已关闭</option>
                <option value="CANCELLED">已取消</option>
              </select>
              <select
                className="rounded border px-2 py-1"
                value={modeFilter}
                onChange={(e) => setModeFilter(e.target.value)}
              >
                <option value="">全部运输方式</option>
                <option value="SEA_CONSOLIDATION">海运拼箱（LCL）</option>
                <option value="SEA_FULL_CONTAINER">海运整柜（FCL）</option>
                <option value="AIR">空运</option>
                <option value="EXPRESS">国际快递</option>
                <option value="TRUCK">陆运</option>
              </select>
              <span className="text-muted-foreground">共 {consignments?.total ?? 0} 张</span>
            </div>

            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载退运单…</p>
            ) : (consignments?.rows.length ?? 0) === 0 ? (
              <EmptyState
                icon={<Ship className="h-6 w-6" />}
                title="还没有退运单"
                description="退运单由「待退运清单」里的处置单合出来：勾选多行 → 合单。"
              />
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">退运单号</th>
                    <th className="py-2">运输方式</th>
                    <th className="py-2">路线 / 仓库</th>
                    <th className="py-2">行数 / 数量</th>
                    <th className="py-2">成本合计</th>
                    <th className="py-2">报关</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {consignments?.rows.map((row) => (
                    <tr key={row.id} className="border-b last:border-0">
                      <td className="py-2">
                        <div className="font-mono text-xs">{row.shipbackNo}</div>
                        <div className="text-muted-foreground text-xs">
                          {row.consolidationNote || '—'}
                        </div>
                      </td>
                      <td className="py-2">{transportModeLabel(row.transportMode)}</td>
                      <td className="py-2 text-xs">
                        {row.originCountry} → {row.destinationCountry}
                        <div className="text-muted-foreground">
                          {row.originWarehouseRef} → {row.domesticWarehouseRef}
                        </div>
                      </td>
                      <td className="py-2">
                        {row.lineCount} 行
                        <div className="text-muted-foreground text-xs">
                          {quantityText(row.totalQty)} 件
                        </div>
                      </td>
                      <td className="py-2">{moneyText(row.totalCost, row.costCurrency)}</td>
                      <td className="py-2 text-xs">
                        {row.customsStatusLabel || shipbackStatusLabel(row.customsStatus)}
                        {row.customsDeclarationNo ? (
                          <div className="font-mono text-muted-foreground">
                            {row.customsDeclarationNo}
                          </div>
                        ) : null}
                      </td>
                      <td className="py-2">
                        <Badge tone="primary">{row.statusLabel}</Badge>
                      </td>
                      <td className="py-2">
                        <Button size="sm" variant="outline" onClick={() => void openDetail(row.id)}>
                          详情
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>
        </TabsContent>

        {/* ---------------- 留痕 ---------------- */}
        <TabsContent value="events">
          <SectionCard
            title="退运留痕"
            description="追加式记录：已写入的事件不许改、不许删。"
          >
            {loading ? (
              <p className="text-muted-foreground text-sm">正在加载留痕…</p>
            ) : (events?.rows.length ?? 0) === 0 ? (
              <EmptyState
                icon={<ClipboardList className="h-6 w-6" />}
                title="还没有留痕"
                description="建单、加行、记成本、发出、收货、入库都会在这里留一条。"
              />
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">事件</th>
                    <th className="py-2">退运单</th>
                    <th className="py-2">时间</th>
                    <th className="py-2">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {events?.rows.map((e) => (
                    <tr key={e.id} className="border-b last:border-0">
                      <td className="py-2">{e.eventTypeLabel}</td>
                      <td className="py-2 font-mono text-xs">{e.consignmentId}</td>
                      <td className="py-2 text-xs">{e.occurredAt}</td>
                      <td className="py-2 text-xs">{e.detail}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>
        </TabsContent>
      </Tabs>

      {/* ---------------- 合单（凑单）对话框 ---------------- */}
      <Modal open={consignOpen} onOpenChange={setConsignOpen}>
        <ModalContent className="max-w-3xl">
          <ModalHeader>
            <ModalTitle>合单 / 凑单：把这几批退货装进同一张退运单</ModalTitle>
            <ModalDescription>
              退运是批量、长周期、高单票成本的操作：合单就是为了摊薄单票成本。
            </ModalDescription>
          </ModalHeader>

          {consignError ? (
            <Notice tone="danger" title="不能建单">
              {consignError}
            </Notice>
          ) : null}

          <div className="grid gap-3 sm:grid-cols-2">
            <label className="text-sm">
              <span className="mb-1 block">运输方式</span>
              <select
                className="w-full rounded border px-2 py-1"
                value={consignForm.transportMode}
                onChange={(e) =>
                  setConsignForm({ ...consignForm, transportMode: e.target.value })
                }
              >
                <option value="SEA_CONSOLIDATION">海运拼箱（LCL）</option>
                <option value="SEA_FULL_CONTAINER">海运整柜（FCL）</option>
                <option value="AIR">空运</option>
                <option value="EXPRESS">国际快递</option>
                <option value="TRUCK">陆运</option>
                <option value="OTHER">其他</option>
              </select>
            </label>
            <label className="text-sm">
              <span className="mb-1 block">成本币种</span>
              <Input
                value={consignForm.costCurrency}
                onChange={(e) =>
                  setConsignForm({ ...consignForm, costCurrency: e.target.value })
                }
                placeholder="USD"
              />
            </label>
            <label className="text-sm">
              <span className="mb-1 block">起运国家（两位）</span>
              <Input
                value={consignForm.originCountry}
                onChange={(e) =>
                  setConsignForm({ ...consignForm, originCountry: e.target.value })
                }
                placeholder="DE"
              />
            </label>
            <label className="text-sm">
              <span className="mb-1 block">目的国家（两位）</span>
              <Input
                value={consignForm.destinationCountry}
                onChange={(e) =>
                  setConsignForm({ ...consignForm, destinationCountry: e.target.value })
                }
                placeholder="CN"
              />
            </label>
            <label className="text-sm">
              <span className="mb-1 block">起运仓</span>
              <Input
                value={consignForm.originWarehouseRef}
                onChange={(e) =>
                  setConsignForm({ ...consignForm, originWarehouseRef: e.target.value })
                }
                placeholder="WH-DE-1"
              />
            </label>
            <label className="text-sm">
              <span className="mb-1 block">国内收货仓</span>
              <Input
                value={consignForm.domesticWarehouseRef}
                onChange={(e) =>
                  setConsignForm({ ...consignForm, domesticWarehouseRef: e.target.value })
                }
                placeholder="WH-CN-1"
              />
            </label>
            <label className="text-sm sm:col-span-2">
              <span className="mb-1 block">为什么合这一单（凑单 / 合箱的理由）</span>
              <Input
                value={consignForm.consolidationNote}
                onChange={(e) =>
                  setConsignForm({ ...consignForm, consolidationNote: e.target.value })
                }
                placeholder="两票货合一个海运拼箱"
              />
            </label>
          </div>

          <div className="mt-4">
            <p className="mb-2 text-sm font-medium">这一单装哪些货（{selected.length} 行）</p>
            {selected.length === 0 ? (
              <p className="text-muted-foreground text-sm">
                还没有勾选任何货 —— 退运单至少要一行：空单既摊不了成本也报不了关。
              </p>
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">SKU</th>
                    <th className="py-2">退运数量</th>
                    <th className="py-2">重量（kg）</th>
                    <th className="py-2">申报货值</th>
                    <th className="py-2">HS / 申报品名</th>
                  </tr>
                </thead>
                <tbody>
                  {selected.map((row, index) => (
                    <tr key={row.dispositionOrderId} className="border-b last:border-0">
                      <td className="py-2">
                        {row.skuCode}
                        <div className="text-muted-foreground text-xs">{row.skuName}</div>
                      </td>
                      <td className="py-2">
                        <Input
                          className="w-24"
                          value={row.qty}
                          onChange={(e) => {
                            const next = [...selected];
                            const current = next[index];
                            if (current) next[index] = { ...current, qty: e.target.value };
                            setSelected(next);
                          }}
                        />
                      </td>
                      <td className="py-2">
                        <Input
                          className="w-24"
                          value={row.weightKg}
                          placeholder="按重量摊时填"
                          onChange={(e) => {
                            const next = [...selected];
                            const current = next[index];
                            if (current) next[index] = { ...current, weightKg: e.target.value };
                            setSelected(next);
                          }}
                        />
                      </td>
                      <td className="py-2">
                        <Input
                          className="w-28"
                          value={row.declaredValue}
                          placeholder="按货值摊时填"
                          onChange={(e) => {
                            const next = [...selected];
                            const current = next[index];
                            if (current) next[index] = { ...current, declaredValue: e.target.value };
                            setSelected(next);
                          }}
                        />
                      </td>
                      <td className="py-2">
                        <Input
                          className="w-28"
                          value={row.hsCode}
                          placeholder="HS 编码"
                          onChange={(e) => {
                            const next = [...selected];
                            const current = next[index];
                            if (current) next[index] = { ...current, hsCode: e.target.value };
                            setSelected(next);
                          }}
                        />
                        <Input
                          className="mt-1 w-56"
                          value={row.declaredItemName}
                          placeholder="申报品名（不照搬销售标题）"
                          onChange={(e) => {
                            const next = [...selected];
                            const current = next[index];
                            if (current) next[index] = { ...current, declaredItemName: e.target.value };
                            setSelected(next);
                          }}
                        />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>

          <ModalFooter>
            <Button variant="ghost" onClick={() => setConsignOpen(false)} disabled={busy}>
              取消
            </Button>
            <Button onClick={() => void submitConsignment()} disabled={busy}>
              建退运单
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------------- 报关对话框 ---------------- */}
      <Modal open={declarationOpen} onOpenChange={setDeclarationOpen}>
        <ModalContent className="max-w-2xl">
          <ModalHeader>
            <ModalTitle>登记报关资料</ModalTitle>
            <ModalDescription>
              申报品名独立必填（不照搬销售标题）；关税金额由「申报货值 × 税率」派生，不给两个互相矛盾的数。
            </ModalDescription>
          </ModalHeader>

          {declarationError ? (
            <Notice tone="danger" title="登记失败">
              {declarationError}
            </Notice>
          ) : null}

          <div className="grid gap-3 sm:grid-cols-2">
            <label className="text-sm">
              <span className="mb-1 block">报关单类型</span>
              <select
                className="w-full rounded border px-2 py-1"
                value={declarationForm.declarationType}
                onChange={(e) =>
                  setDeclarationForm({ ...declarationForm, declarationType: e.target.value })
                }
              >
                <option value="RETURN_IMPORT">退运进口</option>
                <option value="EXPORT">出口</option>
                <option value="TRANSIT">过境</option>
              </select>
            </label>
            <label className="text-sm">
              <span className="mb-1 block">HS 编码</span>
              <Input
                value={declarationForm.hsCode}
                onChange={(e) => setDeclarationForm({ ...declarationForm, hsCode: e.target.value })}
                placeholder="61091000"
              />
            </label>
            <label className="text-sm sm:col-span-2">
              <span className="mb-1 block">申报品名</span>
              <Input
                value={declarationForm.declaredItemName}
                onChange={(e) =>
                  setDeclarationForm({ ...declarationForm, declaredItemName: e.target.value })
                }
                placeholder="退货 T 恤（申报品名）"
              />
            </label>
            <label className="text-sm">
              <span className="mb-1 block">申报数量</span>
              <Input
                value={declarationForm.declaredQty}
                onChange={(e) =>
                  setDeclarationForm({ ...declarationForm, declaredQty: e.target.value })
                }
              />
            </label>
            <label className="text-sm">
              <span className="mb-1 block">申报货值</span>
              <Input
                value={declarationForm.declaredValue}
                onChange={(e) =>
                  setDeclarationForm({ ...declarationForm, declaredValue: e.target.value })
                }
              />
            </label>
            <label className="text-sm">
              <span className="mb-1 block">申报币种</span>
              <Input
                value={declarationForm.declaredCurrency}
                onChange={(e) =>
                  setDeclarationForm({ ...declarationForm, declaredCurrency: e.target.value })
                }
                placeholder="CNY"
              />
            </label>
            <label className="text-sm">
              <span className="mb-1 block">关税税率（小数，0.15 = 15%）</span>
              <Input
                value={declarationForm.tariffRate}
                onChange={(e) =>
                  setDeclarationForm({ ...declarationForm, tariffRate: e.target.value })
                }
                placeholder="0.15"
              />
            </label>
            <label className="text-sm">
              <span className="mb-1 block">税费（增值税等）</span>
              <Input
                value={declarationForm.taxAmount}
                onChange={(e) =>
                  setDeclarationForm({ ...declarationForm, taxAmount: e.target.value })
                }
              />
            </label>
            <label className="text-sm">
              <span className="mb-1 block">报关单号</span>
              <Input
                value={declarationForm.declarationNo}
                onChange={(e) =>
                  setDeclarationForm({ ...declarationForm, declarationNo: e.target.value })
                }
              />
            </label>
          </div>

          {declarationForm.declaredValue && declarationForm.tariffRate ? (
            <Notice tone="info" title="关税预览（服务端会再算一遍，以服务端为准）">
              关税 = {moneyText(Number(declarationForm.declaredValue), declarationForm.declaredCurrency)}{' '}
              × {declarationForm.tariffRate} ={' '}
              {moneyText(
                previewTariffAmount(Number(declarationForm.declaredValue), Number(declarationForm.tariffRate)),
                declarationForm.declaredCurrency,
              )}
            </Notice>
          ) : null}

          <ModalFooter>
            <Button variant="ghost" onClick={() => setDeclarationOpen(false)} disabled={busy}>
              取消
            </Button>
            <Button onClick={() => void submitDeclaration()} disabled={busy}>
              登记报关资料
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------------- 成本 + 分摊对话框 ---------------- */}
      <Modal open={costOpen} onOpenChange={setCostOpen}>
        <ModalContent className="max-w-2xl">
          <ModalHeader>
            <ModalTitle>登记退运成本并分摊</ModalTitle>
            <ModalDescription>
              记完立刻按口径分摊到每一行；合计恒等于这笔成本金额，尾差补到最后一笔。
            </ModalDescription>
          </ModalHeader>

          {costError ? (
            <Notice tone="danger" title="登记失败">
              {costError}
            </Notice>
          ) : null}

          <div className="grid gap-3 sm:grid-cols-2">
            <label className="text-sm">
              <span className="mb-1 block">成本类型</span>
              <select
                className="w-full rounded border px-2 py-1"
                value={costForm.costType}
                onChange={(e) => setCostForm({ ...costForm, costType: e.target.value })}
              >
                <option value="OCEAN_FREIGHT">海运费</option>
                <option value="AIR_FREIGHT">空运费</option>
                <option value="TARIFF">关税</option>
                <option value="CUSTOMS_FEE">报关费</option>
                <option value="INLAND_FREIGHT">内陆运费</option>
                <option value="HANDLING_FEE">操作 / 装卸费</option>
                <option value="REPACK_FEE">换包装费</option>
                <option value="OTHER">其他费用</option>
              </select>
            </label>
            <label className="text-sm">
              <span className="mb-1 block">分摊口径</span>
              <select
                className="w-full rounded border px-2 py-1"
                value={costForm.allocationBasis}
                onChange={(e) => setCostForm({ ...costForm, allocationBasis: e.target.value })}
              >
                <option value="WEIGHT">按重量</option>
                <option value="QUANTITY">按数量（件）</option>
                <option value="DECLARED_VALUE">按申报货值</option>
                <option value="EQUAL">平均分摊</option>
              </select>
            </label>
            <label className="text-sm">
              <span className="mb-1 block">金额</span>
              <Input
                value={costForm.amount}
                onChange={(e) => setCostForm({ ...costForm, amount: e.target.value })}
                placeholder="1000.00"
              />
            </label>
            <label className="text-sm">
              <span className="mb-1 block">币种（必须与退运单成本币种一致）</span>
              <Input
                value={costForm.currency}
                onChange={(e) => setCostForm({ ...costForm, currency: e.target.value })}
              />
            </label>
          </div>

          <ModalFooter>
            <Button variant="ghost" onClick={() => setCostOpen(false)} disabled={busy}>
              取消
            </Button>
            <Button onClick={() => void submitCost()} disabled={busy}>
              登记并分摊
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------------- 入库 / 报废分录对话框 ---------------- */}
      <Modal open={entryOpen} onOpenChange={setEntryOpen}>
        <ModalContent className="max-w-xl">
          <ModalHeader>
            <ModalTitle>登记重新入库 / 报废分录</ModalTitle>
            <ModalDescription>
              分录只出事实：消费方 7.4.1.1 与 10.7.1.3 均未交付，状态恒为 PENDING。
            </ModalDescription>
          </ModalHeader>

          {entryError ? (
            <Notice tone="danger" title="登记失败">
              {entryError}
            </Notice>
          ) : null}

          <div className="grid gap-3 sm:grid-cols-2">
            <label className="text-sm">
              <span className="mb-1 block">明细行</span>
              <select
                className="w-full rounded border px-2 py-1"
                value={entryForm.lineId}
                onChange={(e) => setEntryForm({ ...entryForm, lineId: e.target.value })}
              >
                <option value="">—— 请选择明细行 ——</option>
                {detail?.lines.map((line) => (
                  <option key={line.id} value={line.id}>
                    {line.skuCode}（尚余 {quantityText(remainingEntryQty(line, detail.entries))}）
                  </option>
                ))}
              </select>
            </label>
            <label className="text-sm">
              <span className="mb-1 block">去向</span>
              <select
                className="w-full rounded border px-2 py-1"
                value={entryForm.entryType}
                onChange={(e) => setEntryForm({ ...entryForm, entryType: e.target.value })}
              >
                <option value="RESTOCK">重新入库（→ 7 库存）</option>
                <option value="SCRAP">报废（→ 10.7.1.3）</option>
              </select>
            </label>
            <label className="text-sm">
              <span className="mb-1 block">数量</span>
              <Input
                value={entryForm.qty}
                onChange={(e) => setEntryForm({ ...entryForm, qty: e.target.value })}
              />
            </label>
          </div>

          <ModalFooter>
            <Button variant="ghost" onClick={() => setEntryOpen(false)} disabled={busy}>
              取消
            </Button>
            <Button onClick={() => void submitEntry()} disabled={busy}>
              登记分录
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------------- 退运单详情 ---------------- */}
      <Modal open={detail !== null} onOpenChange={(open) => (open ? null : setDetail(null))}>
        <ModalContent className="max-w-4xl">
          <ModalHeader>
            <ModalTitle>
              {detail ? `${detail.head.shipbackNo}（${detail.head.statusLabel}）` : '退运单详情'}
            </ModalTitle>
            <ModalDescription>
              {detail
                ? `${transportModeLabel(detail.head.transportMode)} · ${detail.head.originCountry} → ${detail.head.destinationCountry} · ${detail.head.originWarehouseRef} → ${detail.head.domesticWarehouseRef}`
                : ''}
            </ModalDescription>
          </ModalHeader>

          {detailError ? (
            <Notice tone="danger" title="读不到">
              {detailError}
            </Notice>
          ) : null}

          {detail ? (
            <div className="space-y-4">
              <div className="flex flex-wrap gap-2">
                {detail.head.status === 'DRAFT' ? (
                  <Button size="sm" onClick={() => void advance('confirm', detail.head.id)} disabled={busy}>
                    确认
                  </Button>
                ) : null}
                {detail.head.status === 'CONFIRMED' ? (
                  <Button size="sm" onClick={() => void advance('dispatch', detail.head.id)} disabled={busy}>
                    发出
                  </Button>
                ) : null}
                {detail.head.status === 'DISPATCHED' ? (
                  <Button
                    size="sm"
                    onClick={() => {
                      setDeclarationOpen(true);
                      setDeclarationError('');
                    }}
                    disabled={busy}
                  >
                    登记报关资料
                  </Button>
                ) : null}
                {detail.head.status === 'ARRIVED' ? (
                  <Button
                    size="sm"
                    onClick={() => {
                      setEntryOpen(true);
                      setEntryError('');
                      setEntryForm(emptyEntryForm(detail.lines[0]?.id ?? ''));
                    }}
                    disabled={busy || detail.lines.length === 0}
                  >
                    登记入库 / 报废分录
                  </Button>
                ) : null}
                {detail.head.status === 'ARRIVED' ? (
                  <Button size="sm" variant="outline" onClick={() => void advance('close', detail.head.id)} disabled={busy}>
                    关闭
                  </Button>
                ) : null}
                {detail.head.status === 'DRAFT' || detail.head.status === 'CONFIRMED' ? (
                  <Button size="sm" variant="outline" onClick={() => void advance('cancel', detail.head.id)} disabled={busy}>
                    取消
                  </Button>
                ) : null}
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => {
                    setCostOpen(true);
                    setCostError('');
                    setCostForm(emptyCostForm(detail.head.costCurrency));
                  }}
                  disabled={busy || detail.head.status === 'CLOSED' || detail.head.status === 'CANCELLED'}
                >
                  登记成本并分摊
                </Button>
              </div>

              <SectionCard title="明细行" description="每一行挂一条 10.7.1.1 的处置单；摊到本行的成本是分摊算出来的。">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="py-2">SKU</th>
                      <th className="py-2">数量 / 重量</th>
                      <th className="py-2">申报货值</th>
                      <th className="py-2">残值</th>
                      <th className="py-2">摊到的成本</th>
                      <th className="py-2">状态</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.lines.map((line) => (
                      <tr key={line.id} className="border-b last:border-0">
                        <td className="py-2">
                          <div>{line.skuCode}</div>
                          <div className="font-mono text-muted-foreground text-xs">
                            {line.dispositionNo}
                          </div>
                        </td>
                        <td className="py-2">
                          {quantityText(line.qty)}
                          <div className="text-muted-foreground text-xs">
                            {quantityText(line.weightKg)} kg
                          </div>
                        </td>
                        <td className="py-2">
                          {moneyText(line.declaredValue, line.declaredCurrency)}
                          <div className="text-muted-foreground text-xs">
                            {line.hsCode} {line.declaredItemName}
                          </div>
                        </td>
                        <td className="py-2">{moneyText(line.salvageAmount, line.salvageCurrency)}</td>
                        <td className="py-2">{moneyText(line.allocatedCost, detail.head.costCurrency)}</td>
                        <td className="py-2">{line.statusLabel}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </SectionCard>

              <SectionCard
                title="成本分摊明细"
                description="逐行权重 / 权重合计 / 比例 / 分摊金额 / 尾差调整额；合计恒等于该笔成本金额。"
              >
                {detail.costs.length === 0 ? (
                  <EmptyState
                    icon={<Boxes className="h-6 w-6" />}
                    title="还没有成本"
                    description="登记一笔运费 / 报关费，服务端会立刻按口径分摊到每一行。"
                  />
                ) : (
                  detail.costs.map((cost) => {
                    const rows = allocationsByCost.get(cost.id) ?? [];
                    const balanced = allocationBalanced(rows, cost.amount);
                    return (
                      <div key={cost.id} className="mb-4 last:mb-0">
                        <div className="flex flex-wrap items-center gap-2 text-sm">
                          <span className="font-medium">{costTypeLabel(cost.costType)}</span>
                          <span>{moneyText(cost.amount, cost.currency)}</span>
                          <Badge tone="neutral">{cost.allocationBasisLabel}</Badge>
                          {balanced ? (
                            <span className="text-muted-foreground text-xs">
                              分摊合计 {moneyText(allocationSum(rows), cost.currency)}（与成本一致）
                            </span>
                          ) : (
                            <span className="text-destructive text-xs">
                              分摊合计 {moneyText(allocationSum(rows), cost.currency)} ≠ 成本{' '}
                              {moneyText(cost.amount, cost.currency)}
                            </span>
                          )}
                          {hasResidual(rows) ? (
                            <span className="text-muted-foreground text-xs">
                              尾差 {moneyText(residualSum(rows), cost.currency)}（已补到最后一笔）
                            </span>
                          ) : null}
                        </div>
                        <table className="mt-2 w-full text-sm">
                          <thead>
                            <tr className="text-muted-foreground border-b text-left">
                              <th className="py-2">SKU</th>
                              <th className="py-2">权重 / 合计</th>
                              <th className="py-2">比例</th>
                              <th className="py-2">分摊金额</th>
                              <th className="py-2">尾差</th>
                            </tr>
                          </thead>
                          <tbody>
                            {rows.map((row) => (
                              <tr key={row.id} className="border-b last:border-0">
                                <td className="py-2">{row.skuCode}</td>
                                <td className="py-2 text-xs">
                                  {quantityText(row.basisValue)} / {quantityText(row.basisTotal)}
                                  {row.appliedBasis !== row.requestedBasis ? (
                                    <div className="text-muted-foreground">
                                      实际口径：{row.appliedBasisLabel}（权重合计为 0，显式回退）
                                    </div>
                                  ) : null}
                                </td>
                                <td className="py-2 font-mono text-xs">{ratioText(row.allocationRatio)}</td>
                                <td className="py-2">{moneyText(row.allocatedAmount, row.currency)}</td>
                                <td className="py-2 text-xs">
                                  {Math.abs(row.residualAmount ?? 0) > 1e-9
                                    ? moneyText(row.residualAmount, row.currency)
                                    : '—'}
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    );
                  })
                )}
              </SectionCard>

              <SectionCard title="报关资料" description="本模块自持的快照：10.5.1 / 3.7 全部跨服务，不跨库 join。">
                {detail.declarations.length === 0 ? (
                  <EmptyState
                    icon={<ClipboardList className="h-6 w-6" />}
                    title="还没有报关资料"
                    description="退运回国必须报关才能确认。"
                  />
                ) : (
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="text-muted-foreground border-b text-left">
                        <th className="py-2">报关单号</th>
                        <th className="py-2">HS / 申报品名</th>
                        <th className="py-2">货值</th>
                        <th className="py-2">关税 / 税费 / 合计</th>
                        <th className="py-2">状态</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detail.declarations.map((d) => (
                        <tr key={d.id} className="border-b last:border-0">
                          <td className="py-2 font-mono text-xs">{d.declarationNo || '—'}</td>
                          <td className="py-2 text-xs">
                            {d.hsCode} {d.declaredItemName}
                          </td>
                          <td className="py-2">
                            {moneyText(d.declaredValue, d.declaredCurrency)}
                            <div className="text-muted-foreground text-xs">
                              {quantityText(d.declaredQty)} 件
                            </div>
                          </td>
                          <td className="py-2 text-xs">
                            {moneyText(d.tariffAmount, d.declaredCurrency)} /{' '}
                            {moneyText(d.taxAmount, d.declaredCurrency)} /{' '}
                            {moneyText(d.totalAmount, d.declaredCurrency)}
                            <div className="text-muted-foreground">
                              税率 {ratioText(d.tariffRate)}
                            </div>
                          </td>
                          <td className="py-2">{d.statusLabel}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </SectionCard>

              {detail.arrivals.length > 0 ? (
                <SectionCard title="回国收货" description="国内收货仓签收：破损与短少单列，不会被抹平。">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="text-muted-foreground border-b text-left">
                        <th className="py-2">国内收货仓</th>
                        <th className="py-2">到货时间</th>
                        <th className="py-2">收货 / 破损 / 短少</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detail.arrivals.map((a) => (
                        <tr key={a.id} className="border-b last:border-0">
                          <td className="py-2">{a.domesticWarehouseRef}</td>
                          <td className="py-2 text-xs">{a.arrivedAt}</td>
                          <td className="py-2">
                            {quantityText(a.receivedQty)} / {quantityText(a.damagedQty)} /{' '}
                            {quantityText(a.shortageQty)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </SectionCard>
              ) : null}

              {detail.entries.length > 0 ? (
                <SectionCard
                  title="重新入库 / 报废分录"
                  description="只出事实不消费：消费方 7.4.1.1 与 10.7.1.3 均未交付，状态恒为 PENDING。"
                >
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="text-muted-foreground border-b text-left">
                        <th className="py-2">分录号</th>
                        <th className="py-2">SKU</th>
                        <th className="py-2">去向</th>
                        <th className="py-2">数量</th>
                        <th className="py-2">消费方</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detail.entries.map((e) => (
                        <tr key={e.id} className="border-b last:border-0">
                          <td className="py-2 font-mono text-xs">{e.entryNo}</td>
                          <td className="py-2">{e.skuCode}</td>
                          <td className="py-2">{e.entryTypeLabel}</td>
                          <td className="py-2">{quantityText(e.qty)}</td>
                          <td className="py-2 text-xs">
                            {e.consumerModule}
                            <div className="text-muted-foreground">{e.postingStatus}</div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </SectionCard>
              ) : null}
            </div>
          ) : null}

          <ModalFooter>
            <Button variant="ghost" onClick={() => setDetail(null)} disabled={busy}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </>
  );
}
