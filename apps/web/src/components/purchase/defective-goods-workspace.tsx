'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { PackageCheck, RefreshCw, TriangleAlert } from 'lucide-react';
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
  DefectiveClaimRow,
  DefectiveGoodsCapabilities,
  DefectiveInspectionOption,
  DefectiveLineInput,
  DefectiveOrderDetail,
  DefectiveOrderRow,
  DefectiveOptions,
  DefectiveSummary,
} from '../../../../../packages/api-contracts/src/index';
import {
  CLAIM_HINT,
  CONCESSION_HINT,
  DISPOSITION_FILTER_OPTIONS,
  DISPOSITION_TYPE_OPTIONS,
  INVENTORY_HINT,
  QUANTITY_HINT,
  SORT_OPTIONS,
  STATUS_OPTIONS,
  agreeClaim,
  cancelOrder,
  claimActions,
  claimStatusText,
  completeOrder,
  createOrder,
  concessionRequired,
  decideOrder,
  dispatchInventory,
  emptyLine,
  exportUrl,
  fetchCapabilities,
  fetchOptions,
  fetchOrder,
  fetchOrders,
  fetchSummary,
  fmtAmount,
  fmtQuantity,
  inventoryStatusText,
  releaseQuantityFor,
  rejectClaim,
  submitClaim,
  toLineInput,
  totalQuantity,
  withdrawClaim,
  withinDisposalLimit,
  type DefectiveParams,
} from './defective-goods-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 6.4.2.2 不良品处理工作区。
 *
 * <p>蓝图：「不合格品的处置：退换货补货、返修、让步接收、报废、转为促销品，
 * 处置结论联动库存状态与供应商索赔。」输出「处置单 + 库存状态变更指令 + 供应商索赔记录」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>处置量不接受手填</b>：页面显示「不合格量 / 可售量上限 / 还能处置多少」，
 *       让人看得见上限在哪 —— 但真正的闸在服务端与数据库层。</li>
 *   <li><b>让步接收要原因与权限码</b>：表单两者必填，缺一会被后端拒。</li>
 *   <li><b>放行量由处置类型决定</b>：报废 / 退换货 / 返修的货离仓或不可售，
 *       表单里不让人填放行量。</li>
 *   <li><b>库存状态照实显示</b>：warehouse 没有可用的授权写端点 ⇒ 页面写「上游无写端点」
 *       并写清影响，绝不显示「已登记」而其实没动库存。</li>
 *   <li><b>索赔金额不假装已入账</b>：登记金额与已生效冲减金额分列显示。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */
export function DefectiveGoodsWorkspace() {
  const [params, setParams] = useState<DefectiveParams>({ page: 0, size: 20 });
  const [data, setData] = useState<Awaited<ReturnType<typeof fetchOrders>> | null>(null);
  const [caps, setCaps] = useState<DefectiveGoodsCapabilities | null>(null);
  const [options, setOptions] = useState<DefectiveOptions | null>(null);
  const [summary, setSummary] = useState<DefectiveSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [dispositionType, setDispositionType] = useState('');
  const [sort, setSort] = useState('created');

  const [detail, setDetail] = useState<DefectiveOrderDetail | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [creating, setCreating] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchOrders(params));
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
        setCaps(await fetchCapabilities());
      } catch {
        // capabilities 拿不到不该让整页空掉：列表仍然可用，只是边界说明退化。
        setCaps(null);
      }
      try {
        setOptions(await fetchOptions());
      } catch {
        setOptions(null);
      }
      try {
        setSummary(await fetchSummary());
      } catch {
        setSummary(null);
      }
    })();
  }, []);

  const orders = data?.rows ?? [];

  const openDetail = useCallback(async (orderId: string) => {
    setDetailError(null);
    try {
      setDetail(await fetchOrder(orderId));
    } catch (e) {
      setDetail(null);
      setDetailError(errorText(e));
    }
  }, []);

  const refreshDetail = useCallback(async (orderId: string) => {
    try {
      setDetail(await fetchOrder(orderId));
    } catch {
      /* 详情刷新失败不打断当前操作，列表刷新时会重新拉。 */
    }
  }, []);

  const inventoryGap = useMemo(
    () => caps?.downstream.find((d) => d.code === 'INVENTORY_ADJUSTMENT'),
    [caps],
  );
  const financeGap = useMemo(
    () => caps?.downstream.find((d) => d.code === 'FINANCE_CLAIM'),
    [caps],
  );

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="不良品处理"
        description="不合格品的处置：退换货补货 / 返修 / 让步接收 / 报废 / 转为促销品，处置结论联动库存状态变更指令与供应商索赔记录。"
        actions={
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
            <Button onClick={() => setCreating(true)} disabled={busy}>
              <PackageCheck />
              新建处置单
            </Button>
          </div>
        }
      />

      {/* 🔴 让步接收是加粗硬规则：常驻提醒，不藏在帮助里 */}
      <div className="flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
        <TriangleAlert className="mt-0.5 size-4 shrink-0" />
        <div>
          <p className="font-medium">让步接收需要权限与原因，不能由质检员随手放行</p>
          <p className="text-muted-foreground">
            {caps?.concessionNote ?? CONCESSION_HINT}
          </p>
        </div>
      </div>

      {inventoryGap && !inventoryGap.available && (
        <div className="flex items-start gap-2 rounded-md border border-sky-500/40 bg-sky-500/10 p-3 text-sm">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <div>
            <p className="font-medium">库存状态变更未接线 → {inventoryGap.ownerModule}</p>
            <p className="text-muted-foreground">{inventoryGap.impact}</p>
          </div>
        </div>
      )}

      {financeGap && !financeGap.available && (
        <div className="flex items-start gap-2 rounded-md border border-sky-500/40 bg-sky-500/10 p-3 text-sm">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <div>
            <p className="font-medium">财务入账未接线 → {financeGap.ownerModule}</p>
            <p className="text-muted-foreground">{financeGap.impact}</p>
          </div>
        </div>
      )}

      {caps && caps.downstream.length > 0 && (
        <SectionCard title="上下游接入情况">
          <div className="flex flex-wrap gap-2">
            {caps.upstream.map((item) => (
              <span
                key={item.code}
                title={item.reason ?? ''}
                className={
                  item.available
                    ? 'rounded-md border px-2 py-1 text-xs'
                    : 'text-muted-foreground rounded-md border border-dashed px-2 py-1 text-xs'
                }
              >
                {item.label}
                {item.available ? '（已接入）' : ` · 未交付 → ${item.ownerModule}`}
              </span>
            ))}
            {caps.downstream.map((item) => (
              <span
                key={item.code}
                title={item.impact ?? ''}
                className={
                  item.available
                    ? 'rounded-md border px-2 py-1 text-xs'
                    : 'text-muted-foreground rounded-md border border-dashed px-2 py-1 text-xs'
                }
              >
                {item.label}
                {item.available ? '（已接入）' : ` · 未交付 → ${item.ownerModule}`}
              </span>
            ))}
          </div>
          <p className="text-muted-foreground mt-2 text-xs">{caps.note}</p>
        </SectionCard>
      )}

      {summary && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="处置单" value={summary.orderCount} />
          <StatCard
            label="处置量"
            value={fmtQuantity(summary.disposedQuantity)}
            comparisonLabel={`放行 ${fmtQuantity(summary.releasedQuantity)}`}
          />
          <StatCard
            label="待结索赔"
            value={fmtAmount(summary.pendingClaimAmount)}
            comparisonLabel={`已达成 ${fmtAmount(summary.settledClaimAmount)}`}
          />
          <StatCard
            label="库存指令"
            value={summary.instructionDispatchedCount}
            comparisonLabel={`未送出 ${summary.instructionUnavailableCount} · 失败 ${summary.instructionFailedCount}`}
            polarity="neutral"
          />
        </div>
      )}

      <SectionCard title="筛选">
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关键字</span>
            <Input
              value={keyword}
              placeholder="处置单号 / 质检单号"
              onChange={(e) => setKeyword(e.target.value)}
              className="w-64"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">状态</span>
            <select
              className="h-9 rounded-md border px-2"
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            >
              {STATUS_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">去向</span>
            <select
              className="h-9 rounded-md border px-2"
              value={dispositionType}
              onChange={(e) => setDispositionType(e.target.value)}
            >
              {DISPOSITION_FILTER_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">排序</span>
            <select
              className="h-9 rounded-md border px-2"
              value={sort}
              onChange={(e) => setSort(e.target.value)}
            >
              {SORT_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          <Button
            onClick={() =>
              setParams({ ...params, keyword, status, dispositionType, sort, page: 0 })
            }
          >
            查询
          </Button>
        </div>
        <p className="text-muted-foreground mt-2 text-xs">{QUANTITY_HINT}</p>
      </SectionCard>

      {error && (
        <div className="flex items-start gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p>{error}</p>
        </div>
      )}

      {loading && <p className="text-muted-foreground text-sm">正在加载处置单…</p>}

      {!loading && orders.length === 0 && !error && (
        <EmptyState
          title="还没有处置单"
          description="新建处置单时选一张已完结的质检单 —— 不合格量、可售量上限与「还能处置多少」都由后端算出来，处置量不让人手填。"
        />
      )}

      {!loading && orders.length > 0 && (
        <SectionCard title={`处置单（${data?.total ?? 0}）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">处置单号</th>
                  <th className="px-2 py-1">质检单</th>
                  <th className="px-2 py-1">SKU</th>
                  <th className="px-2 py-1">去向</th>
                  <th className="px-2 py-1">处置量</th>
                  <th className="px-2 py-1">放行量</th>
                  <th className="px-2 py-1">状态</th>
                  <th className="px-2 py-1">库存指令</th>
                  <th className="px-2 py-1">索赔</th>
                  <th className="px-2 py-1" />
                </tr>
              </thead>
              <tbody>
                {orders.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="px-2 py-1 font-mono text-xs">{row.dispositionNo}</td>
                    <td className="px-2 py-1 text-xs">{row.inspectionNo}</td>
                    <td className="px-2 py-1 text-xs">{row.skuCode}</td>
                    <td className="px-2 py-1 text-xs">{row.dispositionSummaryLabel || '—'}</td>
                    <td className="px-2 py-1 text-xs">{fmtQuantity(row.disposedQuantity)}</td>
                    <td className="px-2 py-1 text-xs">{fmtQuantity(row.releasedQuantity)}</td>
                    <td className="px-2 py-1">
                      <Badge tone={statusTone(row.status)}>{row.statusLabel}</Badge>
                    </td>
                    <td className="px-2 py-1 text-xs">{inventoryStatusText(row)}</td>
                    <td className="px-2 py-1 text-xs">{claimStatusText(row.claimStatus, row.claimStatusLabel)}</td>
                    <td className="px-2 py-1 text-right">
                      <Button variant="ghost" onClick={() => void openDetail(row.id)}>
                        详情
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="mt-3 flex items-center gap-2">
            <Button
              variant="outline"
              disabled={(params.page ?? 0) <= 0}
              onClick={() => setParams({ ...params, page: (params.page ?? 0) - 1 })}
            >
              上一页
            </Button>
            <span className="text-muted-foreground text-xs">
              第 {(params.page ?? 0) + 1} / {Math.max(data?.totalPages ?? 1, 1)} 页 · 共{' '}
              {data?.total ?? 0} 张
            </span>
            <Button
              variant="outline"
              disabled={(params.page ?? 0) + 1 >= (data?.totalPages ?? 1)}
              onClick={() => setParams({ ...params, page: (params.page ?? 0) + 1 })}
            >
              下一页
            </Button>
          </div>
        </SectionCard>
      )}

      {detailError && (
        <div className="flex items-start gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p>{detailError}</p>
        </div>
      )}

      {detail && (
        <DetailModal
          detail={detail}
          busy={busy}
          onClose={() => setDetail(null)}
          onChanged={(orderId) => {
            void refreshDetail(orderId);
            void load();
          }}
          onBusy={setBusy}
        />
      )}

      {creating && (
        <CreateModal
          options={options}
          busy={busy}
          onClose={() => setCreating(false)}
          onCreated={(orderId) => {
            setCreating(false);
            void load();
            void openDetail(orderId);
          }}
          onBusy={setBusy}
        />
      )}
    </div>
  );
}

function statusTone(status: string): 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'brand' {
  switch (status) {
    case 'COMPLETED':
      return 'success';
    case 'CANCELLED':
      return 'neutral';
    case 'DECIDED':
      return 'info';
    case 'EXECUTING':
      return 'brand';
    default:
      return 'warning';
  }
}

// ================================================================ 详情

function DetailModal({
  detail,
  busy,
  onClose,
  onChanged,
  onBusy,
}: {
  detail: DefectiveOrderDetail;
  busy: boolean;
  onClose: () => void;
  onChanged: (orderId: string) => void;
  onBusy: (busy: boolean) => void;
}) {
  const order = detail.order;
  const [actionError, setActionError] = useState<string | null>(null);

  const run = useCallback(
    async (work: () => Promise<unknown>) => {
      setActionError(null);
      onBusy(true);
      try {
        await work();
        onChanged(order.id);
      } catch (e) {
        setActionError(errorText(e));
      } finally {
        onBusy(false);
      }
    },
    [onBusy, onChanged, order.id],
  );

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-w-4xl">
        <ModalHeader>
          <ModalTitle>
            {order.dispositionNo} · {order.skuCode}
          </ModalTitle>
          <ModalDescription>
            质检单 {order.inspectionNo} · 状态 {order.statusLabel} ·{' '}
            {order.dispositionSummaryLabel || '尚未填去向'}
          </ModalDescription>
        </ModalHeader>

        {/* 数量口径：不合格量 / 可售量上限 / 还能处置多少 */}
        <SectionCard title="数量口径（全部来自 6.4.2.1 质检单）">
          <div className="grid gap-3 sm:grid-cols-4">
            <StatCard
              label="不合格量"
              value={fmtQuantity(order.inspectionDefectiveQuantity)}
              comparisonLabel="处置的唯一物资来源"
              polarity="negative"
            />
            <StatCard
              label="可售量上限"
              value={fmtQuantity(order.sellableCeiling)}
              comparisonLabel="合格量 + 让步接收量"
              polarity="neutral"
            />
            <StatCard
              label="本次处置量"
              value={fmtQuantity(order.disposedQuantity)}
              comparisonLabel={`放行 ${fmtQuantity(order.releasedQuantity)}`}
            />
            <StatCard
              label="还能处置"
              value={fmtQuantity(order.remainingQuantity)}
              polarity="neutral"
            />
          </div>
          <p className="text-muted-foreground mt-2 text-xs">{detail.quantityNote}</p>
        </SectionCard>

        {/* 处置明细 */}
        <SectionCard title="处置明细">
          {detail.lines.length === 0 ? (
            <p className="text-muted-foreground text-sm">还没有处置明细 —— 草稿状态下可以补。</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left">
                  <tr>
                    <th className="px-2 py-1">去向</th>
                    <th className="px-2 py-1">数量</th>
                    <th className="px-2 py-1">放行量</th>
                    <th className="px-2 py-1">让步原因</th>
                    <th className="px-2 py-1">权限码</th>
                    <th className="px-2 py-1">备注</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.lines.map((line) => (
                    <tr key={line.id} className="border-t">
                      <td className="px-2 py-1 text-xs">{line.dispositionTypeLabel}</td>
                      <td className="px-2 py-1 text-xs">{fmtQuantity(line.quantity)}</td>
                      <td className="px-2 py-1 text-xs">{fmtQuantity(line.releasedQuantity)}</td>
                      <td className="px-2 py-1 text-xs">{line.concessionReason || '—'}</td>
                      <td className="px-2 py-1 font-mono text-xs">{line.permissionCode || '—'}</td>
                      <td className="px-2 py-1 text-xs">{line.note || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </SectionCard>

        {/* 库存状态变更指令 */}
        <SectionCard title="库存状态变更指令">
          {detail.instructions.length === 0 ? (
            <p className="text-muted-foreground text-sm">
              还没有指令 —— 决策定稿后会按明细逐条生成。
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left">
                  <tr>
                    <th className="px-2 py-1">动作</th>
                    <th className="px-2 py-1">数量</th>
                    <th className="px-2 py-1">写入所有权</th>
                    <th className="px-2 py-1">状态</th>
                    <th className="px-2 py-1">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.instructions.map((instruction) => (
                    <tr key={instruction.id} className="border-t">
                      <td className="px-2 py-1 text-xs">{instruction.actionLabel}</td>
                      <td className="px-2 py-1 text-xs">{fmtQuantity(instruction.quantity)}</td>
                      <td className="px-2 py-1 text-xs">{instruction.ownerModule}</td>
                      <td className="px-2 py-1">
                        <Badge
                          tone={
                            instruction.status === 'DISPATCHED'
                              ? 'success'
                              : instruction.status === 'FAILED'
                                ? 'danger'
                                : instruction.status === 'UNAVAILABLE'
                                  ? 'warning'
                                  : 'neutral'
                          }
                        >
                          {instruction.statusLabel}
                        </Badge>
                      </td>
                      <td className="px-2 py-1 text-xs">{instruction.reason}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="text-muted-foreground mt-2 text-xs">{detail.inventoryNote ?? INVENTORY_HINT}</p>
        </SectionCard>

        {/* 供应商索赔 */}
        <SectionCard title="供应商索赔">
          {detail.claims.length === 0 ? (
            <p className="text-muted-foreground text-sm">
              没有索赔记录 —— 定稿时未登记金额或质检单没有单价快照时不会凭空生成一笔。
            </p>
          ) : (
            <div className="space-y-3">
              {detail.claims.map((claim) => (
                <ClaimCard
                  key={claim.id}
                  orderId={order.id}
                  claim={claim}
                  busy={busy}
                  onRun={run}
                />
              ))}
            </div>
          )}
          <p className="text-muted-foreground mt-2 text-xs">{detail.claimNote ?? CLAIM_HINT}</p>
        </SectionCard>

        {/* 事件台账 */}
        <SectionCard title="事件台账">
          <ul className="space-y-1 text-xs">
            {detail.events.map((event) => (
              <li key={event.id} className="flex gap-2">
                <span className="text-muted-foreground w-40 shrink-0">
                  {new Date(event.occurredAt).toLocaleString('zh-CN')}
                </span>
                <span className="w-24 shrink-0">{event.eventTypeLabel}</span>
                <span>{event.note}</span>
              </li>
            ))}
          </ul>
        </SectionCard>

        {actionError && (
          <div className="flex items-start gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600">
            <TriangleAlert className="mt-0.5 size-4 shrink-0" />
            <p>{actionError}</p>
          </div>
        )}

        <ModalFooter>
          <Button variant="outline" onClick={onClose}>
            关闭
          </Button>
          {detail.actions.includes('DECIDE') && (
            <Button
              disabled={busy}
              onClick={() => void run(() => decideOrder(order.id))}
            >
              决策定稿
            </Button>
          )}
          {detail.actions.includes('DISPATCH_INVENTORY') && (
            <Button
              variant="outline"
              disabled={busy}
              onClick={() => void run(() => dispatchInventory(order.id))}
            >
              派发库存指令
            </Button>
          )}
          {detail.actions.includes('COMPLETE') && (
            <Button disabled={busy} onClick={() => void run(() => completeOrder(order.id))}>
              完成
            </Button>
          )}
          {detail.actions.includes('CANCEL') && (
            <Button
              variant="ghost"
              disabled={busy}
              onClick={() => {
                const reason = window.prompt('作废理由（必填）');
                if (reason && reason.trim()) {
                  void run(() => cancelOrder(order.id, { cancelReason: reason.trim() }));
                }
              }}
            >
              作废
            </Button>
          )}
          <a
            href={exportUrl(order.id)}
            className="text-muted-foreground ml-auto text-xs underline"
            target="_blank"
            rel="noreferrer"
          >
            导出 CSV
          </a>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

// ================================================================ 索赔卡片

function ClaimCard({
  orderId,
  claim,
  busy,
  onRun,
}: {
  orderId: string;
  claim: DefectiveClaimRow;
  busy: boolean;
  onRun: (work: () => Promise<unknown>) => Promise<void>;
}) {
  const [amount, setAmount] = useState(String(claim.claimedAmount ?? 0));
  const [reason, setReason] = useState('');
  const actions = claimActions(claim);

  return (
    <div className="rounded-md border p-3 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-mono text-xs">{claim.claimNo}</span>
        <Badge tone="info">{claim.claimTypeLabel}</Badge>
        <span className="text-xs">
          登记 {claim.currencyCode} {fmtAmount(claim.claimedAmount)}
          {claim.agreedAmount !== null && claim.agreedAmount !== undefined
            ? ` · 供应商认可 ${fmtAmount(claim.agreedAmount)}`
            : ''}
        </span>
        {/* 🔴 已生效冲减金额单列：供应商认可 ≠ 已经冲减了应付 */}
        <span className="text-muted-foreground text-xs">
          已生效冲减 {fmtAmount(claim.effectiveAmount)}（{claim.financeStatusLabel}）
        </span>
      </div>
      {claim.decidedReason && (
        <p className="text-muted-foreground mt-1 text-xs">结论：{claim.decidedReason}</p>
      )}

      {actions.length > 0 && (
        <div className="mt-3 flex flex-wrap items-end gap-2">
          {actions.includes('AGREE') && (
            <label className="flex flex-col gap-1 text-xs">
              <span className="text-muted-foreground">供应商认可金额</span>
              <Input
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                className="w-36"
              />
            </label>
          )}
          {(actions.includes('REJECT') || actions.includes('WITHDRAW')) && (
            <label className="flex flex-col gap-1 text-xs">
              <span className="text-muted-foreground">理由（必填）</span>
              <Input value={reason} onChange={(e) => setReason(e.target.value)} className="w-64" />
            </label>
          )}
          {actions.includes('SUBMIT') && (
            <Button disabled={busy} onClick={() => void onRun(() => submitClaim(orderId, claim.id))}>
              提交索赔
            </Button>
          )}
          {actions.includes('AGREE') && (
            <Button
              disabled={busy}
              onClick={() =>
                void onRun(() =>
                  agreeClaim(orderId, claim.id, {
                    agreedAmount: Number(amount),
                    note: reason || null,
                  }),
                )
              }
            >
              确认赔付
            </Button>
          )}
          {actions.includes('REJECT') && (
            <Button
              variant="outline"
              disabled={busy || !reason.trim()}
              onClick={() => void onRun(() => rejectClaim(orderId, claim.id, { reason: reason.trim() }))}
            >
              拒赔
            </Button>
          )}
          {actions.includes('WITHDRAW') && (
            <Button
              variant="ghost"
              disabled={busy || !reason.trim()}
              onClick={() => void onRun(() => withdrawClaim(orderId, claim.id, { reason: reason.trim() }))}
            >
              撤回
            </Button>
          )}
        </div>
      )}
    </div>
  );
}

// ================================================================ 新建

function CreateModal({
  options,
  busy,
  onClose,
  onCreated,
  onBusy,
}: {
  options: DefectiveOptions | null;
  busy: boolean;
  onClose: () => void;
  onCreated: (orderId: string) => void;
  onBusy: (busy: boolean) => void;
}) {
  const inspections = options?.inspections ?? [];
  const [inspectionId, setInspectionId] = useState('');
  const [reason, setReason] = useState('');
  const [currencyCode, setCurrencyCode] = useState('');
  const [claimAmount, setClaimAmount] = useState('');
  const [lines, setLines] = useState<DefectiveLineInput[]>([emptyLine()]);
  const [formError, setFormError] = useState<string | null>(null);

  const selected: DefectiveInspectionOption | undefined = inspections.find(
    (i) => i.id === inspectionId,
  );
  const planned = totalQuantity(
    lines.map((l) => ({ quantity: Number(l.quantity ?? 0) })),
  );
  const remaining = selected?.remainingQuantity ?? 0;
  const overLimit = selected ? !withinDisposalLimit(planned, remaining) : false;

  const patchLine = (index: number, patch: Partial<DefectiveLineInput>) => {
    setLines((current) =>
      current.map((line, i) => (i === index ? { ...line, ...patch } : line)),
    );
  };

  const submit = async () => {
    setFormError(null);
    if (!inspectionId) {
      setFormError('必须选一张已完结的质检单 —— 不合格量只能从它来。');
      return;
    }
    if (!reason.trim()) {
      setFormError('必须写处置理由 —— 决策要留理由。');
      return;
    }
    const valid = lines.filter((l) => Number(l.quantity ?? 0) > 0);
    if (valid.length === 0) {
      setFormError('至少要有一条数量大于 0 的处置明细。');
      return;
    }
    const concession = valid.find((l) => concessionRequired(l.dispositionType));
    if (concession && !concession.concessionReason?.trim()) {
      setFormError('让步接收必须写原因 —— 缺一会被后端拒。');
      return;
    }
    if (concession && !concession.permissionCode?.trim()) {
      setFormError('让步接收必须给权限码 —— 缺一会被后端拒。');
      return;
    }
    onBusy(true);
    try {
      const created = await createOrder({
        inspectionOrderId: inspectionId,
        reason: reason.trim(),
        currencyCode: currencyCode.trim() || null,
        claimAmount: claimAmount.trim() ? Number(claimAmount) : null,
        note: null,
        lines: valid.map(toLineInput),
      });
      onCreated(created.id);
    } catch (e) {
      setFormError(errorText(e));
    } finally {
      onBusy(false);
    }
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-w-3xl">
        <ModalHeader>
          <ModalTitle>新建处置单</ModalTitle>
          <ModalDescription>
            数量从 6.4.2.1 质检单回读 —— 处置量不让人手填，只填「处置到哪里去」。
          </ModalDescription>
        </ModalHeader>

        <SectionCard title="来源质检单">
          {inspections.length === 0 ? (
            <p className="text-muted-foreground text-sm">
              没有可处置的质检单 —— 需要一张**已完结**且仍有不合格量的质检单。
            </p>
          ) : (
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">质检单</span>
              <select
                className="h-9 rounded-md border px-2"
                value={inspectionId}
                onChange={(e) => setInspectionId(e.target.value)}
              >
                <option value="">请选择</option>
                {inspections.map((option) => (
                  <option key={option.id} value={option.id}>
                    {option.inspectionNo} · {option.skuCode} · 不合格{' '}
                    {option.defectiveQuantity} · 还能处置 {option.remainingQuantity}
                  </option>
                ))}
              </select>
            </label>
          )}
          {selected && (
            <p className="text-muted-foreground mt-2 text-xs">
              不合格量 {fmtQuantity(selected.defectiveQuantity)} · 可售量上限{' '}
              {fmtQuantity(selected.sellableCeiling)}（合格量 + 让步接收量）· 还能处置{' '}
              {fmtQuantity(selected.remainingQuantity)}
            </p>
          )}
        </SectionCard>

        <SectionCard title="处置明细">
          <div className="space-y-3">
            {lines.map((line, index) => (
              <div key={index} className="rounded-md border p-3 text-sm">
                <div className="flex flex-wrap items-end gap-2">
                  <label className="flex flex-col gap-1 text-xs">
                    <span className="text-muted-foreground">去向</span>
                    <select
                      className="h-9 rounded-md border px-2"
                      value={line.dispositionType}
                      onChange={(e) =>
                        patchLine(index, {
                          dispositionType: e.target
                            .value as DefectiveLineInput['dispositionType'],
                        })
                      }
                    >
                      {DISPOSITION_TYPE_OPTIONS.map((o) => (
                        <option key={o.value} value={o.value}>
                          {o.label}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    <span className="text-muted-foreground">数量</span>
                    <Input
                      value={String(line.quantity ?? 0)}
                      onChange={(e) =>
                        patchLine(index, { quantity: Number(e.target.value) })
                      }
                      className="w-32"
                    />
                  </label>
                  <label className="flex flex-col gap-1 text-xs">
                    <span className="text-muted-foreground">备注</span>
                    <Input
                      value={line.note ?? ''}
                      onChange={(e) => patchLine(index, { note: e.target.value })}
                      className="w-56"
                    />
                  </label>
                  {lines.length > 1 && (
                    <Button
                      variant="ghost"
                      onClick={() => setLines((c) => c.filter((_, i) => i !== index))}
                    >
                      移除
                    </Button>
                  )}
                </div>
                {concessionRequired(line.dispositionType) && (
                  <div className="mt-2 flex flex-wrap items-end gap-2">
                    <label className="flex flex-col gap-1 text-xs">
                      <span className="text-muted-foreground">让步原因（必填）</span>
                      <Input
                        value={line.concessionReason ?? ''}
                        onChange={(e) =>
                          patchLine(index, { concessionReason: e.target.value })
                        }
                        className="w-72"
                      />
                    </label>
                    <label className="flex flex-col gap-1 text-xs">
                      <span className="text-muted-foreground">权限码（必填）</span>
                      <Input
                        value={line.permissionCode ?? ''}
                        onChange={(e) => patchLine(index, { permissionCode: e.target.value })}
                        className="w-72"
                        placeholder="trade.defective-disposition.concession"
                      />
                    </label>
                  </div>
                )}
                <p className="text-muted-foreground mt-2 text-xs">
                  放行量 {fmtQuantity(releaseQuantityFor(line.dispositionType, Number(line.quantity ?? 0)))}
                  {concessionRequired(line.dispositionType)
                    ? '（让步接收整行回可售池）'
                    : '（该去向的货离仓或不可售，放行量恒为 0）'}
                </p>
              </div>
            ))}
            <Button variant="outline" onClick={() => setLines((c) => [...c, emptyLine()])}>
              增加一条去向
            </Button>
          </div>
          {selected && (
            <p className={`mt-2 text-xs ${overLimit ? 'text-red-600' : 'text-muted-foreground'}`}>
              本次处置 {fmtQuantity(planned)}，还能处置 {fmtQuantity(remaining)}
              {overLimit ? ' —— 超了，后端会拒。' : ''}
            </p>
          )}
        </SectionCard>

        <SectionCard title="理由与索赔">
          <div className="flex flex-wrap items-end gap-2">
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">处置理由（必填）</span>
              <Input
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                className="w-96"
              />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">币种</span>
              <Input
                value={currencyCode}
                onChange={(e) => setCurrencyCode(e.target.value)}
                className="w-24"
                placeholder="USD"
              />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">索赔金额</span>
              <Input
                value={claimAmount}
                onChange={(e) => setClaimAmount(e.target.value)}
                className="w-32"
                placeholder="留空则按单价快照推导"
              />
            </label>
          </div>
          <p className="text-muted-foreground mt-2 text-xs">{CLAIM_HINT}</p>
        </SectionCard>

        {formError && (
          <div className="flex items-start gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600">
            <TriangleAlert className="mt-0.5 size-4 shrink-0" />
            <p>{formError}</p>
          </div>
        )}

        <ModalFooter>
          <Button variant="outline" onClick={onClose}>
            取消
          </Button>
          <Button disabled={busy || overLimit} onClick={() => void submit()}>
            建单
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
