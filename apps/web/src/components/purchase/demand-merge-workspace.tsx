'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Layers, Printer, RefreshCw, TriangleAlert } from 'lucide-react';
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
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  PurchaseDemandBatchQuantityInput,
  PurchaseDemandBatchSupplierInput,
  PurchaseDemandMergeBatchPage,
  PurchaseDemandMergeBatchRow,
  PurchaseDemandMergeCapabilities,
  PurchaseDemandMergeDetail,
  PurchaseDemandMergeLineRow,
  PurchaseDemandMergeOptions,
  PurchaseDemandMergeUnmergedRow,
  PurchaseDemandPrintResult,
} from '../../../../../packages/api-contracts/src/index';
import {
  adjustDemandMergeQuantity,
  adjustDemandMergeSupplier,
  adjustmentSummary,
  batchStatusLabel,
  batchStatusTone,
  canFinalize,
  countByReason,
  createDemandMerge,
  draftStatusLabel,
  fetchDemandMergeBatch,
  fetchDemandMergeBatches,
  fetchDemandMergeCapabilities,
  fetchDemandMergeOptions,
  finalizeDemandMergeBatch,
  fmt,
  fmtAmount,
  fmtDate,
  groupLinesByDraft,
  isBatchEditable,
  printDemandMerges,
  reasonCodeLabel,
  reasonTone,
  type DemandMergeParams,
} from './demand-merge-model';
import { fetchPurchaseRequests } from './purchase-request-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 6.2.2.2 需求合并与批量工作区。
 *
 * <p>蓝图：「把多个 SKU 的需求合并成一张采购单（同一供应商），支持批量生成、批量修改数量与
 * 供应商、批量打印。」输出「合并后的采购单草稿；未合并行说明原因」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>一张草稿只属于一个供应商</b>：草稿区按供应商分块显示，每块都写明供应商 ——
 *       「合并只在同一供应商内进行」是硬规则，页面上必须一眼看得出分组。</li>
 *   <li><b>未合并行必须写明原因</b>：每条未合并行都带原因码与原因原文，
 *       原文**原样**显示，不概括、不省略。静默丢弃会让用户以为合掉了。</li>
 *   <li><b>草稿不是采购单</b>：页面**不出现**「采购单已创建」这类字样 ——
 *       6.3.1.2 采购单未交付，本模块只产出**采购单草稿**。</li>
 *   <li><b>金额算不出来就说算不出来</b>：预估金额为 null 时显示「无数据」，绝不显示 0。</li>
 *   <li><b>批量动作整体生效或整体拒绝</b>：改数量 / 改供应商的弹窗都写明这一点，
 *       失败后页面刷新到的仍是**没改过**的状态（服务端整批回滚）。</li>
 *   <li><b>加载 / 空 / 错误三态齐全</b>，且三态文案各不相同。</li>
 * </ul>
 */
export function DemandMergeWorkspace() {
  const [params, setParams] = useState<DemandMergeParams>({ page: 0, size: 20 });
  const [page, setPage] = useState<PurchaseDemandMergeBatchPage | null>(null);
  const [capabilities, setCapabilities] = useState<PurchaseDemandMergeCapabilities | null>(null);
  const [options, setOptions] = useState<PurchaseDemandMergeOptions | null>(null);
  const [detail, setDetail] = useState<PurchaseDemandMergeDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [creating, setCreating] = useState(false);
  const [adjusting, setAdjusting] = useState<'quantity' | 'supplier' | null>(null);
  const [printResult, setPrintResult] = useState<PurchaseDemandPrintResult | null>(null);
  const [selectedLineIds, setSelectedLineIds] = useState<string[]>([]);
  const [selectedDraftIds, setSelectedDraftIds] = useState<string[]>([]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [list, caps, opts] = await Promise.all([
        fetchDemandMergeBatches(params),
        fetchDemandMergeCapabilities(),
        fetchDemandMergeOptions(),
      ]);
      setPage(list);
      setCapabilities(caps);
      setOptions(opts);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => {
    void load();
  }, [load]);

  const run = useCallback(async (work: () => Promise<void>) => {
    setBusy(true);
    setError('');
    try {
      await work();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, []);

  const openDetail = useCallback(
    async (id: string) => {
      await run(async () => {
        setDetail(await fetchDemandMergeBatch(id));
        setSelectedLineIds([]);
        setSelectedDraftIds([]);
      });
    },
    [run],
  );

  const rows = useMemo(() => page?.items ?? [], [page]);
  const detailLines = useMemo(() => detail?.lines ?? [], [detail]);
  const linesByDraft = useMemo(() => groupLinesByDraft(detailLines), [detailLines]);
  const reasonCounts = useMemo(() => countByReason(detail?.unmerged ?? []), [detail]);
  const editable = detail ? isBatchEditable(detail.batch.status) : false;

  const toggleLine = (id: string) =>
    setSelectedLineIds((ids) => (ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id]));

  const toggleDraft = (id: string) =>
    setSelectedDraftIds((ids) => (ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id]));

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="需求合并与批量"
        description="把多个 SKU 的需求合并成采购单草稿（同一供应商），支持批量生成、批量修改数量与供应商、批量打印。"
        actions={
          <Button onClick={() => setCreating(true)} disabled={busy || !options}>
            <Layers />
            批量生成合并
          </Button>
        }
      />

      {capabilities && (
        <SectionCard title="本模块的边界">
          <div className="flex flex-col gap-2 text-sm">
            <p className="text-muted-foreground">
              需求来源（6.2.2.1 采购申请）{capabilities.requestAvailable ? '可用' : '不可用'} ·
              供应商参照 {capabilities.supplierReferenceAvailable ? '可用' : '未接线'} ·
              目标仓与法人 {capabilities.warehouseReferenceAvailable ? '可用' : '不可用'}
            </p>
            <p className="text-muted-foreground">{capabilities.requestNote}</p>
            <p className="text-muted-foreground">{capabilities.supplierReferenceNote}</p>
            <p className="text-muted-foreground">
              合并规则：{capabilities.mergeRule} · 状态机：{capabilities.stateMachine}
            </p>
            {!capabilities.purchaseOrderDelivered && (
              <div className="flex items-start gap-2 rounded-md border border-amber-300 bg-amber-50 p-3">
                <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0 text-amber-700" />
                <div className="flex flex-col gap-1">
                  <span className="text-sm font-medium text-amber-900">
                    采购单（6.3.1.2）尚未交付 —— 这里只产出**采购单草稿**
                  </span>
                  <span className="text-xs text-amber-800">{capabilities.purchaseOrderNote}</span>
                </div>
              </div>
            )}
          </div>
        </SectionCard>
      )}

      <div className="grid gap-3 sm:grid-cols-4">
        <StatCard label="合并批次（当前页）" value={String(page?.total ?? 0)} />
        <StatCard label="草稿张数" value={String(detail?.batch.draftCount ?? 0)} />
        <StatCard label="草稿行数" value={String(detail?.batch.lineCount ?? 0)} />
        <StatCard label="未合并行" value={String(detail?.batch.unmergedCount ?? 0)} />
      </div>

      {error && (
        <div className="flex items-start gap-2 rounded-md border border-red-300 bg-red-50 p-3">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0 text-red-700" />
          <span className="text-sm text-red-900">{error}</span>
        </div>
      )}

      <SectionCard
        title="合并批次"
        extra={
          <Button variant="ghost" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'animate-spin' : ''} />
            刷新
          </Button>
        }
      >
        <div className="flex flex-wrap items-end gap-2 pb-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">状态</span>
            <select
              className="h-9 rounded-md border px-2"
              value={params.status ?? ''}
              onChange={(e) =>
                setParams((p) => ({ ...p, status: e.target.value || undefined, page: 0 }))
              }
            >
              <option value="">全部</option>
              <option value="OPEN">可批量修改</option>
              <option value="FINALIZED">已定稿</option>
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">排序</span>
            <select
              className="h-9 rounded-md border px-2"
              value={params.sort ?? 'NEWEST'}
              onChange={(e) => setParams((p) => ({ ...p, sort: e.target.value, page: 0 }))}
            >
              <option value="NEWEST">最新创建</option>
              <option value="OLDEST">最早创建</option>
              <option value="BATCH_NO">批次号</option>
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关键字</span>
            <Input
              value={params.keyword ?? ''}
              placeholder="批次号 / 备注"
              onChange={(e) => setParams((p) => ({ ...p, keyword: e.target.value }))}
            />
          </label>
          <Button
            variant="secondary"
            onClick={() => setParams((p) => ({ ...p, page: 0 }))}
            disabled={loading}
          >
            查询
          </Button>
        </div>

        {loading && rows.length === 0 ? (
          <p className="text-muted-foreground text-sm">正在读合并批次……</p>
        ) : rows.length === 0 ? (
          <EmptyState
            title="还没有合并批次"
            description="选几张已批的采购申请单，点「批量生成合并」把它们按供应商合成采购单草稿。"
            action={
              <Button onClick={() => setCreating(true)} disabled={!options}>
                批量生成合并
              </Button>
            }
          />
        ) : (
          <div className="flex flex-col gap-2">
            {rows.map((row) => (
              <BatchRow
                key={row.id}
                row={row}
                active={detail?.batch.id === row.id}
                onOpen={() => void openDetail(row.id)}
              />
            ))}
          </div>
        )}

        {page && page.total > page.size && (
          <div className="flex items-center justify-end gap-2 pt-3 text-sm">
            <Button
              variant="ghost"
              size="sm"
              disabled={(params.page ?? 0) <= 0}
              onClick={() => setParams((p) => ({ ...p, page: Math.max(0, (p.page ?? 0) - 1) }))}
            >
              上一页
            </Button>
            <span className="text-muted-foreground">
              第 {(params.page ?? 0) + 1} / {Math.max(1, Math.ceil(page.total / page.size))} 页
            </span>
            <Button
              variant="ghost"
              size="sm"
              disabled={(params.page ?? 0) + 1 >= Math.ceil(page.total / page.size)}
              onClick={() => setParams((p) => ({ ...p, page: (p.page ?? 0) + 1 }))}
            >
              下一页
            </Button>
          </div>
        )}
      </SectionCard>

      {detail && (
        <>
          <SectionCard
            title="批次详情"
            description={`批次 ${detail.batch.batchNo} · ${detail.batch.currencyCode} · 目标仓 ${detail.batch.targetWarehouseName || detail.batch.targetWarehouseCode} · 法人 ${detail.batch.legalEntityName || detail.batch.legalEntityCode}`}
          >
            <div className="flex flex-wrap items-center gap-2 pb-3">
              <Badge tone={batchStatusTone(detail.batch.status)}>
                {batchStatusLabel(detail.batch.status)}
              </Badge>
              <Badge tone="neutral">{detail.batch.draftCount} 张草稿</Badge>
              <Badge tone="neutral">{detail.batch.lineCount} 行</Badge>
              <Badge tone={detail.batch.unmergedCount > 0 ? 'warning' : 'neutral'}>
                未合并 {detail.batch.unmergedCount} 行
              </Badge>
              <Badge tone="neutral">
                预估金额 {fmtAmount(detail.batch.estimatedAmount)} {detail.batch.currencyCode}
              </Badge>
              <Badge tone="neutral">总数量 {fmt(detail.batch.totalQuantity)}</Badge>
            </div>

            <div className="flex flex-wrap gap-2">
              <Button
                variant="secondary"
                size="sm"
                disabled={busy || !editable || selectedLineIds.length === 0}
                onClick={() => setAdjusting('quantity')}
              >
                批量改数量（已选 {selectedLineIds.length} 行）
              </Button>
              <Button
                variant="secondary"
                size="sm"
                disabled={busy || !editable || selectedLineIds.length === 0 || !options}
                onClick={() => setAdjusting('supplier')}
              >
                批量改供应商（已选 {selectedLineIds.length} 行）
              </Button>
              <Button
                variant="secondary"
                size="sm"
                disabled={busy || !canFinalize(detail.batch.status, detail.batch.unmergedCount)}
                onClick={() =>
                  void run(async () => {
                    await finalizeDemandMergeBatch(detail.batch.id);
                    await openDetail(detail.batch.id);
                  })
                }
              >
                定稿
              </Button>
              <Button
                variant="ghost"
                size="sm"
                disabled={busy || selectedDraftIds.length === 0}
                onClick={() =>
                  void run(async () => {
                    setPrintResult(await printDemandMerges({ draftIds: selectedDraftIds }));
                  })
                }
              >
                <Printer />
                批量打印（已选 {selectedDraftIds.length} 张草稿）
              </Button>
            </div>

            {detail.batch.unmergedCount > 0 && (
              <p className="pt-2 text-xs text-amber-800">
                有未合并行时**不能定稿** —— 把没合上的需求悄悄带过去比定不了稿更糟。
                先处理下面的未合并行。
              </p>
            )}
            {!editable && (
              <p className="text-muted-foreground pt-2 text-xs">
                批次已定稿，内容冻结（6.3.1.2 采购单要读一份不再变动的草稿），不能再批量修改。
              </p>
            )}
          </SectionCard>

          <SectionCard
            title="采购单草稿"
            description="一个供应商一张草稿 —— 合并只在同一供应商内进行，跨供应商的行在下面的「未合并行」里并写明原因。"
          >
            {detail.drafts.length === 0 ? (
              <EmptyState
                title="这一批没有产出任何草稿"
                description="所有需求行都进了未合并行（原因见下一节）。"
              />
            ) : (
              <div className="flex flex-col gap-3">
                {detail.drafts.map((draft) => {
                  const lines = linesByDraft.get(draft.id) ?? [];
                  return (
                    <div key={draft.id} className="rounded-md border p-3">
                      <div className="flex flex-wrap items-center justify-between gap-2 pb-2">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-medium">{draft.draftNo}</span>
                          <Badge tone="info">
                            供应商 {draft.supplierName || draft.supplierCode}
                          </Badge>
                          <Badge tone="neutral">{draftStatusLabel(draft.status)}</Badge>
                          <Badge tone="neutral">{draft.lineCount} 行</Badge>
                          <Badge tone="neutral">
                            数量 {fmt(draft.totalQuantity)} · 预估金额{' '}
                            {fmtAmount(draft.estimatedAmount)} {draft.currencyCode}
                          </Badge>
                        </div>
                        <label className="flex items-center gap-2 text-sm">
                          <input
                            type="checkbox"
                            checked={selectedDraftIds.includes(draft.id)}
                            onChange={() => toggleDraft(draft.id)}
                          />
                          <span className="text-muted-foreground">选中打印</span>
                        </label>
                      </div>
                      {lines.length === 0 ? (
                        <p className="text-muted-foreground text-sm">这张草稿还没有行。</p>
                      ) : (
                        <div className="flex flex-col gap-2">
                          {lines.map((line) => (
                            <LineRow
                              key={line.id}
                              line={line}
                              selectable={editable}
                              checked={selectedLineIds.includes(line.id)}
                              onToggle={() => toggleLine(line.id)}
                            />
                          ))}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </SectionCard>

          <SectionCard
            title="未合并行"
            description="没合上的需求行，每一行都写明原因 —— 静默丢弃与偷偷归到第一个供应商都是错的。"
          >
            {detail.unmerged.length === 0 ? (
              <EmptyState title="没有未合并行" description="这次合并的每一行都进了采购单草稿。" />
            ) : (
              <div className="flex flex-col gap-2">
                <div className="flex flex-wrap gap-2 pb-1">
                  {Object.entries(reasonCounts).map(([code, count]) => (
                    <Badge key={code} tone={reasonTone(code)}>
                      {reasonCodeLabel(code)} {count}
                    </Badge>
                  ))}
                </div>
                {detail.unmerged.map((row) => (
                  <UnmergedRow key={row.id} row={row} />
                ))}
              </div>
            )}
          </SectionCard>

          {detail.adjustments.length > 0 && (
            <SectionCard
              title="批量修改留痕"
              description="改了多少、为什么改、什么时候改 —— 事后可追溯的凭据。"
            >
              <div className="flex flex-col gap-2">
                {detail.adjustments.map((adjustment) => (
                  <div
                    key={adjustment.id}
                    className="flex flex-wrap items-center gap-2 rounded-md border p-2 text-sm"
                  >
                    <Badge tone={adjustment.scope === 'SUPPLIER' ? 'info' : 'neutral'}>
                      {adjustment.scope === 'SUPPLIER' ? '改供应商' : '改数量'}
                    </Badge>
                    <span>{adjustmentSummary(adjustment)}</span>
                    <span className="text-muted-foreground">理由：{adjustment.reason}</span>
                    <span className="text-muted-foreground">
                      本次批量共 {adjustment.affectedLineCount} 行
                    </span>
                  </div>
                ))}
              </div>
            </SectionCard>
          )}
        </>
      )}

      {creating && options && (
        <CreateMergeModal
          options={options}
          onClose={() => setCreating(false)}
          onCreated={async (batchId) => {
            setCreating(false);
            await openDetail(batchId);
          }}
        />
      )}

      {adjusting && detail && options && (
        <AdjustModal
          kind={adjusting}
          batchId={detail.batch.id}
          lineIds={selectedLineIds}
          options={options}
          onClose={() => setAdjusting(null)}
          onDone={async () => {
            setAdjusting(null);
            setSelectedLineIds([]);
            await openDetail(detail.batch.id);
          }}
        />
      )}

      {printResult && (
        <Modal open onOpenChange={(open) => !open && setPrintResult(null)}>
          <ModalContent className="max-w-3xl">
            <ModalHeader>
              <ModalTitle>批量打印（采购单草稿）</ModalTitle>
              <ModalDescription>
                {printResult.note} 共 {printResult.draftCount} 张草稿、{printResult.lineCount} 行，
                总数量 {fmt(printResult.totalQuantity)}，总金额 {fmtAmount(printResult.totalAmount)}
                。
              </ModalDescription>
            </ModalHeader>
            <div className="flex flex-col gap-3">
              {printResult.sheets.map((sheet) => (
                <pre
                  key={sheet.draftId}
                  className="bg-muted overflow-x-auto whitespace-pre rounded-md border p-3 text-xs"
                >
                  {sheet.textContent}
                </pre>
              ))}
            </div>
            <ModalFooter>
              <Button variant="secondary" onClick={() => setPrintResult(null)}>
                关闭
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>
      )}
    </div>
  );
}

// ================================================================ 子组件

function BatchRow({
  row,
  active,
  onOpen,
}: {
  row: PurchaseDemandMergeBatchRow;
  active: boolean;
  onOpen: () => void;
}) {
  return (
    <div
      className={`flex flex-wrap items-center justify-between gap-3 rounded-md border p-3 ${
        active ? 'border-foreground/30 bg-muted/40' : ''
      }`}
    >
      <div className="flex flex-col gap-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-medium">{row.batchNo}</span>
          <Badge tone={batchStatusTone(row.status)}>{batchStatusLabel(row.status)}</Badge>
          <Badge tone="neutral">{row.draftCount} 张草稿</Badge>
          <Badge tone="neutral">{row.lineCount} 行</Badge>
          {row.unmergedCount > 0 && <Badge tone="warning">未合并 {row.unmergedCount} 行</Badge>}
        </div>
        <p className="text-muted-foreground text-xs">
          目标仓 {row.targetWarehouseName || row.targetWarehouseCode} · 法人{' '}
          {row.legalEntityName || row.legalEntityCode} · 币种 {row.currencyCode} · 数量{' '}
          {fmt(row.totalQuantity)} · 预估金额 {fmtAmount(row.estimatedAmount)}
        </p>
      </div>
      <Button variant="ghost" size="sm" onClick={onOpen}>
        打开
      </Button>
    </div>
  );
}

function LineRow({
  line,
  selectable,
  checked,
  onToggle,
}: {
  line: PurchaseDemandMergeLineRow;
  selectable: boolean;
  checked: boolean;
  onToggle: () => void;
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-3 rounded-md border p-2">
      <div className="flex flex-col gap-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-medium">{line.skuCode}</span>
          <span className="text-sm">{line.skuName}</span>
          <Badge tone="info">供应商 {line.supplierName || line.supplierCode}</Badge>
          {line.sourceRequestCount > 1 && (
            <Badge tone="neutral">由 {line.sourceRequestCount} 条需求合成</Badge>
          )}
        </div>
        <p className="text-muted-foreground text-xs">
          下单量 {fmt(line.mergedQuantity)} · 源需求量 {fmt(line.sourceQuantity)} · 单价{' '}
          {line.unitPrice === null ? '未填' : fmtAmount(line.unitPrice)} · 行金额{' '}
          {line.lineAmount === null ? '无数据' : fmtAmount(line.lineAmount)} · 到货日{' '}
          {fmtDate(line.arrivalDate)}
        </p>
        {line.sourceQuantity !== line.mergedQuantity && (
          <p className="text-xs text-amber-800">
            下单量与源需求量已经分叉 —— 这就是「被批量改过」的证据。
          </p>
        )}
      </div>
      {selectable && (
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={checked} onChange={onToggle} />
          <span className="text-muted-foreground">选中</span>
        </label>
      )}
    </div>
  );
}

function UnmergedRow({ row }: { row: PurchaseDemandMergeUnmergedRow }) {
  return (
    <div className="flex flex-col gap-1 rounded-md border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium">{row.sourceSkuCode}</span>
        {row.sourceSkuName && <span className="text-sm">{row.sourceSkuName}</span>}
        <Badge tone={reasonTone(row.reasonCode)}>
          {row.reasonCodeLabel || reasonCodeLabel(row.reasonCode)}
        </Badge>
        <Badge tone="neutral">数量 {fmt(row.sourceQuantity)}</Badge>
        {row.sourceSupplierName && <Badge tone="neutral">源供应商 {row.sourceSupplierName}</Badge>}
      </div>
      {/* 原因原文原样显示：概括掉等于让用户猜为什么没合上。 */}
      <p className="text-muted-foreground text-sm">{row.reason}</p>
    </div>
  );
}

// ================================================================ 批量生成合并

function CreateMergeModal({
  options,
  onClose,
  onCreated,
}: {
  options: PurchaseDemandMergeOptions;
  onClose: () => void;
  onCreated: (batchId: string) => Promise<void>;
}) {
  const [requestIds, setRequestIds] = useState<string[]>([]);
  const [manualIds, setManualIds] = useState('');
  const [supplierId, setSupplierId] = useState('');
  const [warehouseId, setWarehouseId] = useState(options.warehouses[0]?.id ?? '');
  const [entityId, setEntityId] = useState(options.legalEntities[0]?.id ?? '');
  const [currency, setCurrency] = useState('USD');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [requests, setRequests] = useState<{ id: string; requestNo: string }[]>([]);
  const [upstreamNote, setUpstreamNote] = useState('');

  // 上游是 6.2.2.1 采购申请：已批的申请单才是能合并的需求来源。
  // 读不到时**不阻塞** —— 页面退回「手填申请单 ID」，并说清为什么读不到。
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const page = await fetchPurchaseRequests({ status: 'APPROVED', size: 100 });
        if (!cancelled) {
          setRequests(page.items.map((item) => ({ id: item.id, requestNo: item.requestNo })));
        }
      } catch (e) {
        if (!cancelled) setUpstreamNote(errorText(e));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const manual = manualIds
    .split(/[\s,，]+/)
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
  const effectiveIds = [...requestIds, ...manual];

  const submit = async () => {
    setBusy(true);
    setError('');
    try {
      const detail = await createDemandMerge({
        sourceRequestIds: effectiveIds,
        sourceLineIds: null,
        targetSupplierId: supplierId || null,
        targetWarehouseId: warehouseId,
        legalEntityId: entityId,
        currencyCode: currency.trim().toUpperCase(),
        note: note.trim() || null,
      });
      await onCreated(detail.batch.id);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-w-2xl">
        <ModalHeader>
          <ModalTitle>批量生成合并</ModalTitle>
          <ModalDescription>
            把多张已批的采购申请单的需求合并成采购单**草稿**。 合并只在**同一供应商内进行** ——
            跨供应商的行会进「未合并行」并写明原因。
          </ModalDescription>
        </ModalHeader>

        <div className="flex flex-col gap-3">
          <div className="flex flex-col gap-1">
            <span className="text-sm font-medium">来源申请单（6.2.2.1 已批）</span>
            {upstreamNote ? (
              <p className="text-xs text-amber-800">
                上游清单暂时读不到（{upstreamNote}）—— 可以直接在下面手填申请单 ID。
              </p>
            ) : requests.length === 0 ? (
              <p className="text-muted-foreground text-xs">
                当前租户没有已批的采购申请单 —— 可以在下面手填申请单 ID。
              </p>
            ) : (
              <div className="flex max-h-48 flex-col gap-1 overflow-y-auto rounded-md border p-2">
                {requests.map((request) => (
                  <label key={request.id} className="flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={requestIds.includes(request.id)}
                      onChange={() =>
                        setRequestIds((ids) =>
                          ids.includes(request.id)
                            ? ids.filter((x) => x !== request.id)
                            : [...ids, request.id],
                        )
                      }
                    />
                    <span>{request.requestNo}</span>
                  </label>
                ))}
              </div>
            )}
          </div>

          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">手填申请单 ID（逗号或空格分隔）</span>
            <Input
              value={manualIds}
              placeholder="6.2.2.1 采购申请单 ID"
              onChange={(e) => setManualIds(e.target.value)}
            />
          </label>

          <div className="grid gap-3 sm:grid-cols-2">
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">只合并到某个供应商（可选）</span>
              <select
                className="h-9 rounded-md border px-2"
                value={supplierId}
                onChange={(e) => setSupplierId(e.target.value)}
              >
                <option value="">按各自供应商分组（每个供应商一张草稿）</option>
                {options.suppliers.map((supplier) => (
                  <option key={supplier.id} value={supplier.id}>
                    {supplier.name || supplier.code}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">币种</span>
              <Input value={currency} maxLength={3} onChange={(e) => setCurrency(e.target.value)} />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">目标仓</span>
              <select
                className="h-9 rounded-md border px-2"
                value={warehouseId}
                onChange={(e) => setWarehouseId(e.target.value)}
              >
                {options.warehouses.map((warehouse) => (
                  <option key={warehouse.id} value={warehouse.id}>
                    {warehouse.name || warehouse.code}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">法人</span>
              <select
                className="h-9 rounded-md border px-2"
                value={entityId}
                onChange={(e) => setEntityId(e.target.value)}
              >
                {options.legalEntities.map((entity) => (
                  <option key={entity.id} value={entity.id}>
                    {entity.name || entity.code}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">备注</span>
            <Input value={note} onChange={(e) => setNote(e.target.value)} />
          </label>

          {error && <p className="text-sm text-red-700">{error}</p>}
        </div>

        <ModalFooter>
          <Button variant="secondary" onClick={onClose}>
            取消
          </Button>
          <Button
            onClick={() => void submit()}
            disabled={
              busy ||
              effectiveIds.length === 0 ||
              !warehouseId ||
              !entityId ||
              currency.trim().length !== 3
            }
          >
            生成合并草稿
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

// ================================================================ 批量修改

function AdjustModal({
  kind,
  batchId,
  lineIds,
  options,
  onClose,
  onDone,
}: {
  kind: 'quantity' | 'supplier';
  batchId: string;
  lineIds: string[];
  options: PurchaseDemandMergeOptions;
  onClose: () => void;
  onDone: () => Promise<void>;
}) {
  const [mode, setMode] = useState('SET');
  const [value, setValue] = useState('');
  const [supplierId, setSupplierId] = useState(options.suppliers[0]?.id ?? '');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const submit = async () => {
    setBusy(true);
    setError('');
    try {
      if (kind === 'quantity') {
        const input: PurchaseDemandBatchQuantityInput = {
          lineIds,
          mode,
          value: Number(value),
          reason: reason.trim(),
        };
        await adjustDemandMergeQuantity(batchId, input);
      } else {
        const input: PurchaseDemandBatchSupplierInput = {
          lineIds,
          supplierId,
          reason: reason.trim(),
        };
        await adjustDemandMergeSupplier(batchId, input);
      }
      await onDone();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const invalidQuantity =
    kind === 'quantity' &&
    (value.trim() === '' || !Number.isFinite(Number(value)) || Number(value) <= 0);

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>
            {kind === 'quantity' ? '批量改数量' : '批量改供应商'}（{lineIds.length} 行）
          </ModalTitle>
          <ModalDescription>
            {kind === 'quantity'
              ? '批量动作**整体生效或整体拒绝**：任一行不合法，整批回滚，库里不留改了一半的中间态。'
              : '改供应商会把行搬到新供应商的草稿。搬完会**重新校验分组**：一张草稿里出现两个供应商就整批拒绝，什么都不改。'}
          </ModalDescription>
        </ModalHeader>

        <div className="flex flex-col gap-3">
          {kind === 'quantity' ? (
            <>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">方式</span>
                <select
                  className="h-9 rounded-md border px-2"
                  value={mode}
                  onChange={(e) => setMode(e.target.value)}
                >
                  <option value="SET">设为指定数量</option>
                  <option value="MULTIPLY">按倍数放大</option>
                </select>
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">
                  {mode === 'SET' ? '目标数量' : '倍数'}
                </span>
                <Input value={value} onChange={(e) => setValue(e.target.value)} />
              </label>
            </>
          ) : (
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">新供应商</span>
              <select
                className="h-9 rounded-md border px-2"
                value={supplierId}
                onChange={(e) => setSupplierId(e.target.value)}
              >
                {options.suppliers.map((supplier) => (
                  <option key={supplier.id} value={supplier.id}>
                    {supplier.name || supplier.code}
                  </option>
                ))}
              </select>
            </label>
          )}

          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">理由（必填）</span>
            <Input value={reason} onChange={(e) => setReason(e.target.value)} />
          </label>

          {error && <p className="text-sm text-red-700">{error}</p>}
        </div>

        <ModalFooter>
          <Button variant="secondary" onClick={onClose}>
            取消
          </Button>
          <Button
            onClick={() => void submit()}
            disabled={
              busy || reason.trim() === '' || (kind === 'quantity' ? invalidQuantity : !supplierId)
            }
          >
            执行
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
