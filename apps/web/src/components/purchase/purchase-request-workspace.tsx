'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { FileCheck2, RefreshCw, Send, TriangleAlert } from 'lucide-react';
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
  PurchaseRequestCapabilities,
  PurchaseRequestDetail,
  PurchaseRequestLineRow,
  PurchaseRequestOptions,
  PurchaseRequestRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  adjustPurchaseRequestLine,
  approvalDecisionLabel,
  approvalSourceLabel,
  convertPurchaseRequest,
  createPurchaseRequest,
  fetchPurchaseRequestCapabilities,
  fetchPurchaseRequest,
  fetchPurchaseRequestOptions,
  fetchPurchaseRequests,
  fmt,
  fmtAmount,
  fmtDate,
  hasApprovalSyncError,
  isConvertible,
  isEditable,
  isLineEditable,
  isRefreshable,
  isSubmittable,
  missingConvertInputs,
  priorityLabel,
  refreshPurchaseRequestApproval,
  requestStatusLabel,
  requestStatusTone,
  selectPurchaseRequestSupplier,
  sourceTypeLabel,
  submitPurchaseRequest,
  type PurchaseRequestParams,
} from './purchase-request-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 6.2.2.1 采购申请工作区。
 *
 * <p>蓝图：「把计划行/补货建议转成采购申请，走审批，确认供应商与数量后转采购单。」
 * 输出「采购申请单（草稿/待审/已批/已转单/已驳回）；审批记录」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>申请与采购单分开</b>：页面**不出现**「采购单已创建」这类字样 ——
 *       6.3.1.2 采购单未交付，本模块只产出**转单事实**。转单那一段显示的是
 *       「转单事实：供应商 / 数量 / 交期 / 目标仓」，并注明「采购单下游未交付」。</li>
 *   <li><b>审批同步失败必须显眼</b>：{@code approvalSyncError} 非空时用危险色把那句话
 *       原样显示出来，并写明这张单**仍停在待审**。</li>
 *   <li><b>金额算不出来就说算不出来</b>：预估金额为 null 时显示「无数据」，
 *       绝不显示 0。</li>
 *   <li><b>加载 / 空 / 错误三态齐全</b>，且三态文案各不相同。</li>
 * </ul>
 */
export function PurchaseRequestWorkspace() {
  const [params, setParams] = useState<PurchaseRequestParams>({ page: 0, size: 20 });
  const [page, setPage] = useState<{ items: PurchaseRequestRow[]; total: number; page: number; size: number } | null>(
    null,
  );
  const [capabilities, setCapabilities] = useState<PurchaseRequestCapabilities | null>(null);
  const [options, setOptions] = useState<PurchaseRequestOptions | null>(null);
  const [detail, setDetail] = useState<PurchaseRequestDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [creating, setCreating] = useState(false);
  const [adjusting, setAdjusting] = useState<PurchaseRequestLineRow | null>(null);
  const [picking, setPicking] = useState<PurchaseRequestLineRow | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [list, caps, opts] = await Promise.all([
        fetchPurchaseRequests(params),
        fetchPurchaseRequestCapabilities(),
        fetchPurchaseRequestOptions(),
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

  const run = useCallback(
    async (work: () => Promise<void>) => {
      setBusy(true);
      setError('');
      try {
        await work();
      } catch (e) {
        setError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [],
  );

  const openDetail = useCallback(
    async (id: string, refresh = false) => {
      await run(async () => {
        const next = await fetchPurchaseRequest(id, refresh);
        setDetail(next);
      });
    },
    [run],
  );

  const rows = useMemo(() => page?.items ?? [], [page]);
  const statusCounts = useMemo(() => {
    const counts: Record<string, number> = {};
    for (const row of rows) counts[row.status] = (counts[row.status] ?? 0) + 1;
    return counts;
  }, [rows]);

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="采购申请"
        description="把 6.2.1.3 的采购计划行或 6.2.1.2 的补货建议转成采购申请，走审批，确认供应商与数量后转采购单。"
      />

      {capabilities && (
        <SectionCard title="本模块的边界">
          <div className="flex flex-col gap-2 text-sm">
            <p className="text-muted-foreground">
              计划行 {capabilities.planLineAvailable ? '可用' : '不可用'} · 补货建议{' '}
              {capabilities.suggestionAvailable ? '可用' : '不可用'} · 供应商参照{' '}
              {capabilities.supplierReferenceAvailable ? '可用' : '未接线'} · 目标仓与法人{' '}
              {capabilities.warehouseReferenceAvailable ? '可用' : '不可用'} · 审批服务{' '}
              {capabilities.approvalServiceAvailable ? '可用' : '不可用'}
            </p>
            <p className="text-muted-foreground">{capabilities.planLineNote}</p>
            <p className="text-muted-foreground">{capabilities.supplierReferenceNote}</p>
            <p className="text-muted-foreground">{capabilities.approvalNote}</p>
            {!capabilities.purchaseOrderDelivered && (
              <div className="flex items-start gap-2 rounded-md border border-amber-300 bg-amber-50 p-3">
                <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0 text-amber-700" />
                <div className="flex flex-col gap-1">
                  <span className="text-sm font-medium text-amber-900">
                    采购单（6.3.1.2）尚未交付
                  </span>
                  <span className="text-xs text-amber-800">{capabilities.purchaseOrderNote}</span>
                </div>
              </div>
            )}
          </div>
        </SectionCard>
      )}

      <div className="grid gap-3 sm:grid-cols-4">
        <StatCard label="当前页申请单" value={String(page?.total ?? 0)} />
        <StatCard label="草稿" value={String(statusCounts.DRAFT ?? 0)} />
        <StatCard label="待审" value={String(statusCounts.PENDING_APPROVAL ?? 0)} />
        <StatCard label="已转单" value={String(statusCounts.CONVERTED ?? 0)} />
      </div>

      <SectionCard
        title="筛选"
        extra={
          <Button variant="ghost" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'animate-spin' : ''} />
            刷新
          </Button>
        }
      >
        <div className="flex flex-wrap items-end gap-2">
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
              <option value="DRAFT">草稿</option>
              <option value="PENDING_APPROVAL">待审</option>
              <option value="APPROVED">已批</option>
              <option value="CONVERTED">已转单</option>
              <option value="REJECTED">已驳回</option>
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">来源</span>
            <select
              className="h-9 rounded-md border px-2"
              value={params.sourceType ?? ''}
              onChange={(e) =>
                setParams((p) => ({ ...p, sourceType: e.target.value || undefined, page: 0 }))
              }
            >
              <option value="">全部</option>
              <option value="PLAN">采购计划行</option>
              <option value="SUGGESTION">补货建议</option>
              <option value="MANUAL">手工建单</option>
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关键字</span>
            <Input
              value={params.keyword ?? ''}
              placeholder="申请单号 / SKU / 备注"
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
          <Button variant="secondary" onClick={() => setCreating(true)} disabled={busy}>
            新建采购申请
          </Button>
        </div>
      </SectionCard>

      {error && (
        <div className="rounded-md border border-red-300 bg-red-50 p-3 text-sm text-red-800">
          {error}
        </div>
      )}

      <SectionCard title="采购申请单">
        {loading && !page ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : rows.length === 0 ? (
          <EmptyState
            title="还没有采购申请单"
            description="新建一张申请单，或把 6.2.1.3 的采购计划行 / 6.2.1.2 的补货建议转成申请单。"
          />
        ) : (
          <div className="flex flex-col gap-2">
            {rows.map((row) => (
              <div
                key={row.id}
                className="flex flex-wrap items-center justify-between gap-3 rounded-md border p-3"
              >
                <div className="flex flex-col gap-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{row.requestNo}</span>
                    <Badge tone={requestStatusTone(row.status)}>
                      {requestStatusLabel(row.status)}
                    </Badge>
                    <Badge tone="neutral">{sourceTypeLabel(row.sourceType)}</Badge>
                    {hasApprovalSyncError(row) && <Badge tone="danger">审批同步失败</Badge>}
                  </div>
                  <p className="text-muted-foreground text-xs">
                    目标仓 {row.targetWarehouseName || row.targetWarehouseCode || '—'} · 法人{' '}
                    {row.legalEntityName || row.legalEntityCode || '—'} · {row.lineCount} 行 · 申请量{' '}
                    {fmt(row.totalQuantity)} · 预估金额{' '}
                    {row.currencyCode ? fmtAmount(row.estimatedAmount) : '无数据（未填币种与单价）'}
                    {row.supplierCount > 0 ? ` · ${row.supplierCount} 个供应商` : ' · 未选供应商'}
                  </p>
                  {hasApprovalSyncError(row) && (
                    <p className="text-xs text-red-700">
                      审批同步失败（这张单**仍停在待审**）：{row.approvalSyncError}
                    </p>
                  )}
                </div>
                <div className="flex gap-2">
                  <Button variant="ghost" onClick={() => void openDetail(row.id)}>
                    查看
                  </Button>
                  {isRefreshable(row.status) && (
                    <Button
                      variant="ghost"
                      disabled={busy}
                      onClick={() => void openDetail(row.id, true)}
                    >
                      <RefreshCw />
                      刷新审批结论
                    </Button>
                  )}
                </div>
              </div>
            ))}
            {page && (
              <p className="text-muted-foreground text-xs">
                共 {page.total} 张 · 第 {page.page + 1} 页
              </p>
            )}
          </div>
        )}
      </SectionCard>

      {detail && (
        <SectionCard
          title={`申请单详情 · ${detail.request.requestNo}`}
          description={`${sourceTypeLabel(detail.request.sourceType)} · 目标仓 ${
            detail.request.targetWarehouseName || detail.request.targetWarehouseCode || '—'
          }`}
        >
          <div className="flex flex-col gap-3">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={requestStatusTone(detail.request.status)}>
                {requestStatusLabel(detail.request.status)}
              </Badge>
              <Badge tone="neutral">{detail.request.lineCount} 行</Badge>
              <Badge tone="neutral">
                预估金额{' '}
                {detail.request.currencyCode
                  ? fmtAmount(detail.request.estimatedAmount)
                  : '无数据（未填币种与单价）'}
              </Badge>
              {detail.request.currencyCode && (
                <Badge tone="neutral">币种 {detail.request.currencyCode}</Badge>
              )}
            </div>

            {hasApprovalSyncError(detail.request) && (
              <div className="flex items-start gap-2 rounded-md border border-red-300 bg-red-50 p-3">
                <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0 text-red-700" />
                <div className="flex flex-col gap-1">
                  <span className="text-sm font-medium text-red-900">
                    审批同步失败 —— 这张申请单**仍停在待审**，不会被当成已批
                  </span>
                  <span className="text-xs text-red-800">{detail.request.approvalSyncError}</span>
                </div>
              </div>
            )}

            <div className="rounded-md border p-3 text-sm">
              <p className="font-medium">审批记录</p>
              <p className="text-muted-foreground text-xs">
                判定结论 {approvalDecisionLabel(detail.request.approvalDecision)} · 判定来源{' '}
                {approvalSourceLabel(detail.request.approvalSource)}
                {detail.request.approvalRuleName ? ` · 命中规则 ${detail.request.approvalRuleName}` : ''}
                {detail.request.approvalFlowName ? ` · 审批流 ${detail.request.approvalFlowName}` : ''}
              </p>
              {detail.request.approvalReason && (
                <p className="text-muted-foreground text-xs">{detail.request.approvalReason}</p>
              )}
              <p className="text-muted-foreground text-xs">
                审批实例 {detail.request.approvalInstanceNo || '无（免审直通或尚未提交）'}
                {detail.request.approvalSubmittedAt
                  ? ` · 提交于 ${detail.request.approvalSubmittedAt}`
                  : ''}
                {detail.request.approvalDecidedAt ? ` · 结论同步于 ${detail.request.approvalDecidedAt}` : ''}
              </p>
            </div>

            <div className="flex flex-wrap gap-2">
              {isSubmittable(detail.request.status) && (
                <Button
                  variant="secondary"
                  disabled={busy || detail.lines.length === 0}
                  onClick={() =>
                    void run(async () => {
                      await submitPurchaseRequest(detail.request.id);
                      await openDetail(detail.request.id);
                    })
                  }
                >
                  <Send />
                  提交审批
                </Button>
              )}
              {isRefreshable(detail.request.status) && (
                <Button
                  variant="ghost"
                  disabled={busy}
                  onClick={() =>
                    void run(async () => {
                      await refreshPurchaseRequestApproval(detail.request.id);
                      await openDetail(detail.request.id);
                    })
                  }
                >
                  <RefreshCw />
                  刷新审批结论
                </Button>
              )}
              {isConvertible(detail.request.status) && (
                <Button
                  variant="secondary"
                  disabled={busy}
                  onClick={() =>
                    void run(async () => {
                      await convertPurchaseRequest(detail.request.id, { lines: [], note: null });
                      await openDetail(detail.request.id);
                    })
                  }
                >
                  <FileCheck2 />
                  确认供应商与数量并转单
                </Button>
              )}
            </div>

            {isConvertible(detail.request.status) &&
              missingConvertInputs(detail.lines).length > 0 && (
                <p className="text-xs text-amber-800">
                  转单前每一行都必须有供应商与交期，还差：
                  {missingConvertInputs(detail.lines).join('、')}
                </p>
              )}

            <div className="flex flex-col gap-2">
              {detail.lines.length === 0 ? (
                <EmptyState
                  title="这张申请单还没有申请行"
                  description="新建时给了行，或把计划行 / 补货建议转进来。"
                />
              ) : (
                detail.lines.map((line) => (
                  <div
                    key={line.id}
                    className="flex flex-wrap items-start justify-between gap-3 rounded-md border p-3"
                  >
                    <div className="flex flex-col gap-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium">{line.skuCode}</span>
                        <span className="text-sm">{line.skuName}</span>
                        <Badge tone="neutral">{priorityLabel(line.priority)}</Badge>
                        {line.sourcePlanLineId && <Badge tone="info">来自采购计划行</Badge>}
                        {line.sourceSuggestionId && <Badge tone="info">来自补货建议</Badge>}
                      </div>
                      <p className="text-muted-foreground text-xs">
                        申请量 {fmt(line.requestedQuantity)} · 单价{' '}
                        {line.unitPrice === null ? '未填' : fmtAmount(line.unitPrice)} · 行金额{' '}
                        {line.lineAmount === null ? '无数据' : fmtAmount(line.lineAmount)} · 交期{' '}
                        {fmtDate(line.requestedArrivalDate)} · 供应商{' '}
                        {line.supplierName || line.supplierCode || '未选'}
                      </p>
                    </div>
                    {isLineEditable(detail.request.status) && (
                      <div className="flex gap-2">
                        <Button variant="ghost" onClick={() => setAdjusting(line)}>
                          调整数量 / 交期
                        </Button>
                        <Button variant="ghost" onClick={() => setPicking(line)}>
                          选供应商
                        </Button>
                      </div>
                    )}
                  </div>
                ))
              )}
            </div>

            {detail.adjustments.length > 0 && (
              <div className="rounded-md border p-3">
                <p className="font-medium text-sm">调整留痕</p>
                <div className="mt-2 flex flex-col gap-1">
                  {detail.adjustments.map((item) => (
                    <p key={item.id} className="text-muted-foreground text-xs">
                      {item.skuCode} · {item.field}：{item.beforeValue || '（空）'} →{' '}
                      {item.afterValue || '（空）'} · 理由：{item.reason} · {item.adjustedAt}
                    </p>
                  ))}
                </div>
              </div>
            )}

            {detail.conversions.length > 0 && (
              <div className="rounded-md border p-3">
                <p className="font-medium text-sm">转单事实</p>
                <p className="text-muted-foreground text-xs">
                  这是「转成采购单时的参数」。6.3.1.2 采购单尚未交付 ⇒ 本模块**不产出采购单**，
                  只把转单参数如实记下来，等采购单交付后由它去建真正的采购单。
                </p>
                <div className="mt-2 flex flex-col gap-1">
                  {detail.conversions.map((item) => (
                    <p key={item.id} className="text-muted-foreground text-xs">
                      {item.skuCode} · 供应商 {item.supplierName || item.supplierCode} · 数量{' '}
                      {fmt(item.convertedQuantity)} · 交期 {fmtDate(item.convertedArrivalDate)} · 目标仓{' '}
                      {item.targetWarehouseName || item.targetWarehouseCode}
                      {item.sourcePlanLineId ? ' · 可追溯到源计划行' : ''}
                      {item.sourceSuggestionId ? ' · 可追溯到源补货建议' : ''}
                    </p>
                  ))}
                </div>
              </div>
            )}
          </div>
        </SectionCard>
      )}

      {creating && options && (
        <CreateRequestModal
          options={options}
          onClose={() => setCreating(false)}
          onCreated={async (id) => {
            setCreating(false);
            await openDetail(id);
          }}
        />
      )}

      {adjusting && detail && (
        <AdjustLineModal
          line={adjusting}
          onClose={() => setAdjusting(null)}
          onDone={async () => {
            setAdjusting(null);
            await openDetail(detail.request.id);
          }}
        />
      )}

      {picking && detail && options && (
        <PickSupplierModal
          line={picking}
          options={options}
          onClose={() => setPicking(null)}
          onDone={async () => {
            setPicking(null);
            await openDetail(detail.request.id);
          }}
        />
      )}
    </div>
  );
}

// ================================================================ 新建申请单

function CreateRequestModal({
  options,
  onClose,
  onCreated,
}: {
  options: PurchaseRequestOptions;
  onClose: () => void;
  onCreated: (id: string) => Promise<void>;
}) {
  const [sourceType, setSourceType] = useState<'PLAN' | 'SUGGESTION' | 'MANUAL'>('MANUAL');
  const [warehouseId, setWarehouseId] = useState(options.warehouses[0]?.id ?? '');
  const [legalEntityId, setLegalEntityId] = useState(options.legalEntities[0]?.id ?? '');
  const [currencyCode, setCurrencyCode] = useState('');
  const [sourcePlanId, setSourcePlanId] = useState('');
  const [sourceLineIds, setSourceLineIds] = useState('');
  const [skuCode, setSkuCode] = useState('');
  const [quantity, setQuantity] = useState('');
  const [arrivalDate, setArrivalDate] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const submit = async () => {
    setBusy(true);
    setError('');
    try {
      const created = await createPurchaseRequest({
        sourceType,
        sourcePlanId: sourceType === 'PLAN' ? sourcePlanId : null,
        sourceBatchId: null,
        sourceLineIds:
          sourceType === 'MANUAL'
            ? null
            : sourceLineIds
                .split(/[,\s]+/)
                .map((v) => v.trim())
                .filter(Boolean),
        lines:
          sourceType === 'MANUAL'
            ? [
                {
                  skuId: crypto.randomUUID(),
                  skuCode,
                  skuName: '',
                  requestedQuantity: Number(quantity),
                  unitPrice: null,
                  requestedArrivalDate: arrivalDate || null,
                  priority: 'NORMAL',
                  note: null,
                },
              ]
            : null,
        targetWarehouseId: warehouseId,
        legalEntityId: legalEntityId,
        currencyCode: currencyCode || null,
        note: null,
      });
      await onCreated(created.id);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>新建采购申请</ModalTitle>
          <ModalDescription>
            来源可以是 6.2.1.3 的采购计划行（PLAN）、6.2.1.2 的补货建议（SUGGESTION），
            或手工建单（MANUAL）。PLAN 与 SUGGESTION 需要填来源行的 id（多个用逗号分隔）。
          </ModalDescription>
        </ModalHeader>
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">来源</span>
            <select
              className="h-9 rounded-md border px-2"
              value={sourceType}
              onChange={(e) => setSourceType(e.target.value as 'PLAN' | 'SUGGESTION' | 'MANUAL')}
            >
              <option value="MANUAL">手工建单</option>
              <option value="PLAN">采购计划行</option>
              <option value="SUGGESTION">补货建议</option>
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">目标仓</span>
            <select
              className="h-9 rounded-md border px-2"
              value={warehouseId}
              onChange={(e) => setWarehouseId(e.target.value)}
            >
              {options.warehouses.map((w) => (
                <option key={w.id} value={w.id}>
                  {w.name || w.code}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">法人</span>
            <select
              className="h-9 rounded-md border px-2"
              value={legalEntityId}
              onChange={(e) => setLegalEntityId(e.target.value)}
            >
              {options.legalEntities.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.name || l.code}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">币种（可留空）</span>
            <Input
              value={currencyCode}
              maxLength={3}
              placeholder="USD"
              onChange={(e) => setCurrencyCode(e.target.value.toUpperCase())}
            />
          </label>
          {sourceType === 'PLAN' && (
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">来源计划 id</span>
              <Input value={sourcePlanId} onChange={(e) => setSourcePlanId(e.target.value)} />
            </label>
          )}
          {sourceType !== 'MANUAL' && (
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">来源行 id（逗号分隔）</span>
              <Input value={sourceLineIds} onChange={(e) => setSourceLineIds(e.target.value)} />
            </label>
          )}
          {sourceType === 'MANUAL' && (
            <>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">SKU 编码</span>
                <Input value={skuCode} onChange={(e) => setSkuCode(e.target.value)} />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">申请数量</span>
                <Input
                  value={quantity}
                  onChange={(e) => setQuantity(e.target.value)}
                  placeholder="基础单位"
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">期望到货日（可留空）</span>
                <Input
                  value={arrivalDate}
                  placeholder="2026-12-05"
                  onChange={(e) => setArrivalDate(e.target.value)}
                />
              </label>
            </>
          )}
        </div>
        {options.supplierNote && (
          <p className="text-xs text-amber-800">供应商清单：{options.supplierNote}</p>
        )}
        {options.warehouseNote && (
          <p className="text-xs text-amber-800">仓库参照：{options.warehouseNote}</p>
        )}
        {error && <p className="text-sm text-red-700">{error}</p>}
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={
              busy ||
              !warehouseId ||
              !legalEntityId ||
              (sourceType === 'PLAN' && !sourcePlanId) ||
              (sourceType !== 'MANUAL' && !sourceLineIds) ||
              (sourceType === 'MANUAL' && (!skuCode || !quantity))
            }
            onClick={() => void submit()}
          >
            建单
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

// ================================================================ 调整申请行

function AdjustLineModal({
  line,
  onClose,
  onDone,
}: {
  line: PurchaseRequestLineRow;
  onClose: () => void;
  onDone: () => Promise<void>;
}) {
  const [quantity, setQuantity] = useState(String(line.requestedQuantity ?? ''));
  const [arrivalDate, setArrivalDate] = useState(line.requestedArrivalDate ?? '');
  const [unitPrice, setUnitPrice] = useState(
    line.unitPrice === null ? '' : String(line.unitPrice),
  );
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const submit = async () => {
    setBusy(true);
    setError('');
    try {
      await adjustPurchaseRequestLine(line.requestId, line.id, {
        requestedQuantity: quantity ? Number(quantity) : null,
        requestedArrivalDate: arrivalDate || null,
        unitPrice: unitPrice ? Number(unitPrice) : null,
        priority: null,
        note: null,
        reason,
      });
      await onDone();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>调整申请行 · {line.skuCode}</ModalTitle>
          <ModalDescription>
            数量与交期的每次改动都会落一条**调整留痕**（append-only），理由必填 ——
            一句「调整一下」不是留痕。
          </ModalDescription>
        </ModalHeader>
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">申请数量</span>
            <Input value={quantity} onChange={(e) => setQuantity(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">期望到货日（可留空 = 未定）</span>
            <Input value={arrivalDate} onChange={(e) => setArrivalDate(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">预估单价（可留空）</span>
            <Input value={unitPrice} onChange={(e) => setUnitPrice(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">调整理由</span>
            <Input value={reason} onChange={(e) => setReason(e.target.value)} />
          </label>
        </div>
        {error && <p className="text-sm text-red-700">{error}</p>}
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button disabled={busy || !reason.trim()} onClick={() => void submit()}>
            提交调整
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

// ================================================================ 选供应商

function PickSupplierModal({
  line,
  options,
  onClose,
  onDone,
}: {
  line: PurchaseRequestLineRow;
  options: PurchaseRequestOptions;
  onClose: () => void;
  onDone: () => Promise<void>;
}) {
  const [supplierId, setSupplierId] = useState(line.supplierId ?? '');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const submit = async () => {
    setBusy(true);
    setError('');
    try {
      await selectPurchaseRequestSupplier(line.requestId, line.id, { supplierId, reason });
      await onDone();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>选供应商 · {line.skuCode}</ModalTitle>
          <ModalDescription>
            供应商主数据来自 catalog（party）的授权只读接口 —— 服务端会确认它真的存在，
            不会把一个假的供应商写进单据。
          </ModalDescription>
        </ModalHeader>
        {options.supplierNote ? (
          <p className="text-xs text-amber-800">供应商清单暂时读不到：{options.supplierNote}</p>
        ) : (
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">供应商</span>
            <select
              className="h-9 rounded-md border px-2"
              value={supplierId}
              onChange={(e) => setSupplierId(e.target.value)}
            >
              <option value="">请选择</option>
              {options.suppliers.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name || s.code}
                </option>
              ))}
            </select>
          </label>
        )}
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-muted-foreground">理由</span>
          <Input value={reason} onChange={(e) => setReason(e.target.value)} />
        </label>
        {error && <p className="text-sm text-red-700">{error}</p>}
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button disabled={busy || !supplierId} onClick={() => void submit()}>
            选定
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
