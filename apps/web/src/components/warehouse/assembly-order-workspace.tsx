'use client';

import { useCallback, useEffect, useState } from 'react';
import { Boxes, PackageCheck, RefreshCw, TriangleAlert } from 'lucide-react';
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
  AssemblyCapabilities,
  AssemblyComponentInput,
  AssemblyOrderDetail,
  AssemblyOrderPage,
} from '../../../../../packages/api-contracts/src/index';
import {
  assemblySortKeys,
  assemblySortLabels,
  assemblySourceLabel,
  assemblyStatusLabel,
  assemblyStatusLabels,
  assemblyStatusTone,
  cancelAssemblyOrder,
  createAssemblyOrder,
  entryDirectionLabel,
  fetchAssemblyOrder,
  fetchAssemblyOrders,
  fetchCapabilities,
  formatQuantity,
  invariantsHold,
  isExactAtStockScale,
  issueAssemblyOrder,
  type AssemblyOrderParams,
} from './assembly-order-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 8.4.1.1 组装单工作区（实物套装组装）。
 *
 * <p>蓝图 08 卷：「按 BOM 领用组件、消耗组件库存、产生成品 SKU 库存，同一事务完成。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>虚拟套装不是本页的输入</b>：虚拟套装不持有库存，出库时由 09 订单展开扣组件。
 *       所以本页只接受真实 catalog SKU，这条边界从后端 capabilities 读出来原样显示，
 *       不在页面里另编一套说法。</li>
 *   <li><b>应领量 / 实发量 / 成品入库量三列并排</b>，不藏起来 —— 它们就是两条不变量的读数。</li>
 *   <li><b>执行是不可逆的</b>：非草稿态的执行与取消按钮直接禁用并写明原因，
 *       而不是给一个点下去必然 409 的按钮。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */
export function AssemblyOrderWorkspace() {
  const [params, setParams] = useState<AssemblyOrderParams>({ page: 0, size: 20 });
  const [data, setData] = useState<AssemblyOrderPage | null>(null);
  const [caps, setCaps] = useState<AssemblyCapabilities | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [sort, setSort] = useState('CREATED_AT');
  const [detail, setDetail] = useState<AssemblyOrderDetail | null>(null);
  const [creating, setCreating] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchAssemblyOrders(params));
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
        // 页面不假装自己知道规则。
        setCaps(null);
      }
    })();
  }, []);

  const openDetail = useCallback(async (id: string) => {
    try {
      setDetail(await fetchAssemblyOrder(id));
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  const run = useCallback(
    async (work: () => Promise<unknown>, refreshId?: string) => {
      setBusy(true);
      setError(null);
      try {
        await work();
        await load();
        if (refreshId) {
          setDetail(await fetchAssemblyOrder(refreshId));
        }
      } catch (e) {
        setError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const orders = data?.items ?? [];

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="组装单"
        description="实物套装组装：按 BOM 领用组件、消耗组件库存、产生成品 SKU 库存，同一事务完成。"
        actions={
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
            <Button onClick={() => setCreating(true)} disabled={busy}>
              <Boxes />
              新建组装单
            </Button>
          </div>
        }
      />

      {/* 模块边界常驻：虚拟套装走不到这里、BOM 不在本库 —— 这两条不是描述，是约束 */}
      <div className="flex items-start gap-2 rounded-md border border-violet-500/40 bg-violet-500/10 p-3 text-sm">
        <PackageCheck className="mt-0.5 size-4 shrink-0" />
        <div className="space-y-1">
          <p className="font-medium">组件出库与成品入库在同一个本地事务内完成</p>
          <p className="text-muted-foreground">
            {caps?.note ??
              '组件出库与成品入库在 warehouse 的一个本地事务里完成，任一环节失败整体回滚。'}
          </p>
          {caps && <p className="text-muted-foreground">{caps.virtualKitBoundary}</p>}
        </div>
      </div>

      {caps && <InvariantCard caps={caps} />}

      {caps && caps.downstreamModules.some((m) => !m.available) && (
        <SectionCard title="下游模块接入情况">
          <div className="flex flex-wrap gap-2">
            {caps.downstreamModules.map((m) => (
              <span
                key={m.moduleId}
                title={m.effect}
                className={
                  m.available
                    ? 'rounded-md border px-2 py-1 text-xs'
                    : 'text-muted-foreground rounded-md border border-dashed px-2 py-1 text-xs'
                }
              >
                {m.moduleId} {m.moduleName}
                {m.available ? '（已接入）' : '（未交付）'}
              </span>
            ))}
          </div>
          <p className="text-muted-foreground mt-2 text-xs">{caps.bomBoundary}</p>
        </SectionCard>
      )}

      {data && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="组装单" value={data.total} />
          <StatCard label="草稿" value={data.draftCount} />
          <StatCard label="已完成" value={data.completedCount} />
          <StatCard
            label="已取消"
            value={data.cancelledCount}
            comparisonLabel={`组装数量合计 ${formatQuantity(data.totalAssemblyQuantity)}`}
          />
        </div>
      )}

      {data && data.total > 0 && (
        <SectionCard title="不变量读数">
          <dl className="grid gap-3 sm:grid-cols-3">
            <Reading
              label="组件应领合计"
              value={formatQuantity(data.totalRequiredComponentQuantity)}
              hint="按 BOM 规则 R7 冻结"
            />
            <Reading
              label="组件实发合计"
              value={formatQuantity(data.totalIssuedComponentQuantity)}
              hint="执行后必须等于应领合计（I1）"
            />
            <Reading
              label="组装数量合计"
              value={formatQuantity(data.totalAssemblyQuantity)}
              hint="等于成品入库量（I2）"
            />
          </dl>
        </SectionCard>
      )}

      <SectionCard title="筛选">
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关键字</span>
            <Input
              value={keyword}
              placeholder="单号 / 成品编码 / BOM 版本"
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
              <option value="">全部</option>
              {Object.entries(assemblyStatusLabels).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
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
              {assemblySortKeys.map((key) => (
                <option key={key} value={key}>
                  {assemblySortLabels[key]}
                </option>
              ))}
            </select>
          </label>
          <Button onClick={() => setParams({ keyword, status, sort, page: 0, size: 20 })}>
            查询
          </Button>
        </div>
      </SectionCard>

      {error && (
        <div className="flex items-start gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p>{error}</p>
        </div>
      )}

      {loading && <p className="text-muted-foreground text-sm">正在加载组装单…</p>}

      {!loading && orders.length === 0 && !error && (
        <EmptyState
          title="还没有组装单"
          description="新建组装单时选择成品 SKU 与 BOM 版本，逐个组件填应领量；执行组装会按 BOM 领料并把成品入库。"
        />
      )}

      {!loading && orders.length > 0 && (
        <SectionCard title={`组装单（${data?.total ?? 0}）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">单号</th>
                  <th className="px-2 py-1">成品 / BOM</th>
                  <th className="px-2 py-1">来源</th>
                  <th className="px-2 py-1">组装数量</th>
                  <th className="px-2 py-1">组件 应领 / 实发</th>
                  <th className="px-2 py-1">状态</th>
                  <th className="px-2 py-1" />
                </tr>
              </thead>
              <tbody>
                {orders.map((order) => {
                  const inv = invariantsHold(order);
                  return (
                    <tr key={order.orderId} className="border-t">
                      <td className="px-2 py-1 font-medium">{order.orderCode}</td>
                      <td className="px-2 py-1">
                        {order.productSkuCode}
                        <span className="text-muted-foreground">
                          {' '}
                          / {order.bomCode} {order.bomVersionNo}
                        </span>
                      </td>
                      <td className="px-2 py-1">{assemblySourceLabel(order.sourceSystem)}</td>
                      <td className="px-2 py-1">{formatQuantity(order.assemblyQuantity)}</td>
                      <td className="px-2 py-1">
                        {order.componentCount} 项 ·{' '}
                        {formatQuantity(order.requiredComponentQuantity)} /{' '}
                        {formatQuantity(order.issuedComponentQuantity)}
                        {order.status === 'COMPLETED' && !inv.component && (
                          <span className="ml-1 text-red-600">（I1 不成立）</span>
                        )}
                      </td>
                      <td className="px-2 py-1">
                        <Badge className={assemblyStatusTone(order.status)}>
                          {assemblyStatusLabel(order.status)}
                        </Badge>
                      </td>
                      <td className="px-2 py-1 text-right">
                        <Button variant="ghost" onClick={() => void openDetail(order.orderId)}>
                          详情
                        </Button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {data && (params.size ?? 20) * ((params.page ?? 0) + 1) < data.total && (
            <div className="mt-3 flex justify-end gap-2">
              <Button
                variant="ghost"
                disabled={(params.page ?? 0) === 0}
                onClick={() => setParams({ ...params, page: (params.page ?? 0) - 1 })}
              >
                上一页
              </Button>
              <Button
                variant="ghost"
                disabled={((params.page ?? 0) + 1) * (params.size ?? 20) >= data.total}
                onClick={() => setParams({ ...params, page: (params.page ?? 0) + 1 })}
              >
                下一页
              </Button>
            </div>
          )}
        </SectionCard>
      )}

      {creating && (
        <CreateOrderDialog
          busy={busy}
          onClose={() => setCreating(false)}
          onSubmit={async (input) => {
            await run(() => createAssemblyOrder(input));
            setCreating(false);
          }}
          onError={setError}
        />
      )}

      {detail && (
        <DetailDialog
          detail={detail}
          busy={busy}
          onClose={() => setDetail(null)}
          onIssue={() => run(() => issueAssemblyOrder(detail.order.orderId), detail.order.orderId)}
          onCancel={(reason) =>
            run(() => cancelAssemblyOrder(detail.order.orderId, { reason }), detail.order.orderId)
          }
        />
      )}
    </div>
  );
}

function Reading({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <div className="rounded-md border p-3">
      <dt className="text-muted-foreground text-xs">{label}</dt>
      <dd className="text-lg font-semibold">{value}</dd>
      <p className="text-muted-foreground text-xs">{hint}</p>
    </div>
  );
}

/** 能力声明里的两条不变量常驻显示：它们是验收口径，不是运行时才知道的日志。 */
function InvariantCard({ caps }: { caps: AssemblyCapabilities }) {
  return (
    <SectionCard title="业务不变量">
      <p className="text-sm">{caps.invariant}</p>
      <ul className="text-muted-foreground mt-2 space-y-1 text-xs">
        {caps.upstreamModules.map((m) => (
          <li key={m.moduleId}>
            上游 {m.moduleId} {m.moduleName}（{m.service}）· {m.usage}
          </li>
        ))}
      </ul>
    </SectionCard>
  );
}

interface DraftComponent {
  skuId: string;
  skuCode: string;
  requiredQuantity: string;
  unitQuantity: string;
  scrapRate: string;
  role: string;
}

function CreateOrderDialog({
  busy,
  onClose,
  onSubmit,
  onError,
}: {
  busy: boolean;
  onClose: () => void;
  onSubmit: (input: Parameters<typeof createAssemblyOrder>[0]) => Promise<void>;
  onError: (message: string) => void;
}) {
  const [productSkuId, setProductSkuId] = useState('');
  const [bomId, setBomId] = useState('');
  const [bomCode, setBomCode] = useState('');
  const [bomVersionId, setBomVersionId] = useState('');
  const [bomVersionNo, setBomVersionNo] = useState('');
  const [sourceSystem, setSourceSystem] = useState('DIRECT');
  const [referenceId, setReferenceId] = useState('');
  const [warehouseId, setWarehouseId] = useState('');
  const [legalEntityId, setLegalEntityId] = useState('');
  const [assemblyQuantity, setAssemblyQuantity] = useState('');
  const [outboundLocationId, setOutboundLocationId] = useState('');
  const [inboundLocationId, setInboundLocationId] = useState('');
  const [notes, setNotes] = useState('');
  const [components, setComponents] = useState<DraftComponent[]>([
    { skuId: '', skuCode: '', requiredQuantity: '', unitQuantity: '', scrapRate: '0', role: '' },
  ]);

  const qtyOk = isExactAtStockScale(assemblyQuantity);
  const linesOk =
    components.length > 0 &&
    components.every((c) => c.skuId.trim() && isExactAtStockScale(c.requiredQuantity));
  const ready =
    productSkuId.trim() &&
    bomId.trim() &&
    bomVersionId.trim() &&
    warehouseId.trim() &&
    legalEntityId.trim() &&
    outboundLocationId.trim() &&
    inboundLocationId.trim() &&
    qtyOk &&
    linesOk;

  const patch = (index: number, next: Partial<DraftComponent>) =>
    setComponents((prev) => prev.map((c, i) => (i === index ? { ...c, ...next } : c)));

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-h-[85vh] max-w-3xl overflow-y-auto">
        <ModalHeader>
          <ModalTitle>新建组装单</ModalTitle>
          <ModalDescription>
            组件应领量按 BOM 规则 R7 冻结在建单上，之后 BOM 改版不影响这张单。成品 SKU 必须是真实
            catalog SKU —— 虚拟套装不占 SKU 行、不持有库存，走不到这里。
          </ModalDescription>
        </ModalHeader>

        <div className="grid gap-3 sm:grid-cols-2">
          <TextField
            label="成品 SKU ID"
            value={productSkuId}
            onChange={setProductSkuId}
            hint="catalog 真实 SKU"
          />
          <TextField label="仓库 ID" value={warehouseId} onChange={setWarehouseId} />
          <TextField label="货权法人 ID" value={legalEntityId} onChange={setLegalEntityId} />
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">上游来源</span>
            <select
              className="h-9 rounded-md border px-2"
              value={sourceSystem}
              onChange={(e) => setSourceSystem(e.target.value)}
            >
              <option value="DIRECT">仓库直接发起</option>
              <option value="TRADE_PROCESSING_ORDER">来自加工单</option>
            </select>
          </label>
          <TextField
            label="加工单引用 ID"
            value={referenceId}
            onChange={setReferenceId}
            hint={
              sourceSystem === 'DIRECT'
                ? 'DIRECT 不带引用键'
                : '6.4.1.1 加工单的逻辑 ID（跨服务无外键，仅作追溯）'
            }
          />
          <TextField label="BOM ID（catalog）" value={bomId} onChange={setBomId} />
          <TextField label="BOM 编码" value={bomCode} onChange={setBomCode} />
          <TextField label="BOM 版本 ID" value={bomVersionId} onChange={setBomVersionId} />
          <TextField label="BOM 版本号" value={bomVersionNo} onChange={setBomVersionNo} />
          <TextField
            label="组件出库库位 ID"
            value={outboundLocationId}
            onChange={setOutboundLocationId}
          />
          <TextField
            label="成品入库库位 ID"
            value={inboundLocationId}
            onChange={setInboundLocationId}
          />
          <TextField
            label="组装数量"
            value={assemblyQuantity}
            onChange={setAssemblyQuantity}
            hint={
              assemblyQuantity && !qtyOk
                ? '不能被库存刻度（4 位小数）精确表示'
                : '同时是成品入库量（I2）'
            }
            invalid={Boolean(assemblyQuantity) && !qtyOk}
          />
        </div>

        <div className="mt-4 space-y-2">
          <div className="flex items-center justify-between">
            <p className="text-sm font-medium">组件明细（BOM 冻结快照）</p>
            <Button
              variant="ghost"
              onClick={() =>
                setComponents((prev) => [
                  ...prev,
                  {
                    skuId: '',
                    skuCode: '',
                    requiredQuantity: '',
                    unitQuantity: '',
                    scrapRate: '0',
                    role: '',
                  },
                ])
              }
            >
              加一行
            </Button>
          </div>
          {components.map((c, index) => {
            const lineOk = c.skuId.trim() && isExactAtStockScale(c.requiredQuantity);
            return (
              <div
                key={index}
                className="grid gap-2 rounded-md border p-2 sm:grid-cols-2 lg:grid-cols-5"
              >
                <TextField
                  label="组件 SKU ID"
                  value={c.skuId}
                  onChange={(v) => patch(index, { skuId: v })}
                />
                <TextField
                  label="应领量"
                  value={c.requiredQuantity}
                  onChange={(v) => patch(index, { requiredQuantity: v })}
                  invalid={Boolean(c.requiredQuantity) && !isExactAtStockScale(c.requiredQuantity)}
                />
                <TextField
                  label="单位用量"
                  value={c.unitQuantity}
                  onChange={(v) => patch(index, { unitQuantity: v })}
                  hint="追溯用，可空"
                />
                <TextField
                  label="损耗率 %"
                  value={c.scrapRate}
                  onChange={(v) => patch(index, { scrapRate: v })}
                  hint="追溯用，可空"
                />
                <div className="flex items-end gap-2">
                  <TextField
                    label="角色"
                    value={c.role}
                    onChange={(v) => patch(index, { role: v })}
                    hint="追溯用"
                  />
                  <Button
                    variant="ghost"
                    disabled={components.length === 1}
                    onClick={() => setComponents((prev) => prev.filter((_, i) => i !== index))}
                  >
                    删
                  </Button>
                </div>
                {c.requiredQuantity && !lineOk && (
                  <p className="text-xs text-red-600 sm:col-span-5">
                    应领量不能被库存刻度（4 位小数）精确表示：向下取整等于吞材料、向上取整等于多领。
                  </p>
                )}
              </div>
            );
          })}
        </div>

        <div className="mt-3">
          <TextField label="备注" value={notes} onChange={setNotes} hint="可空" />
        </div>

        <ModalFooter>
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            取消
          </Button>
          <Button
            disabled={busy || !ready}
            onClick={async () => {
              const payload: AssemblyComponentInput[] = components.map((c) => ({
                skuId: c.skuId.trim(),
                bomComponentId: null,
                requiredQuantity: Number(c.requiredQuantity),
                unitQuantity: c.unitQuantity ? Number(c.unitQuantity) : null,
                scrapRate: c.scrapRate ? Number(c.scrapRate) : null,
                role: c.role.trim() || null,
                note: null,
              }));
              await onSubmit({
                productSkuId: productSkuId.trim(),
                bomId: bomId.trim(),
                bomCode: bomCode.trim(),
                bomVersionId: bomVersionId.trim(),
                bomVersionNo: bomVersionNo.trim(),
                sourceSystem,
                referenceId: sourceSystem === 'DIRECT' ? null : referenceId.trim(),
                referenceNo: null,
                warehouseId: warehouseId.trim(),
                legalEntityId: legalEntityId.trim(),
                assemblyQuantity: Number(assemblyQuantity),
                outboundLocationId: outboundLocationId.trim(),
                outboundQualityStatusId: null,
                inboundLocationId: inboundLocationId.trim(),
                inboundQualityStatusId: null,
                outboundLotId: null,
                inboundLotId: null,
                notes: notes.trim() || null,
                components: payload,
              });
            }}
          >
            建单
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

function DetailDialog({
  detail,
  busy,
  onClose,
  onIssue,
  onCancel,
}: {
  detail: AssemblyOrderDetail;
  busy: boolean;
  onClose: () => void;
  onIssue: () => void;
  onCancel: (reason: string) => void;
}) {
  const order = detail.order;
  const draft = order.status === 'DRAFT';
  const inv = invariantsHold(order);
  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-h-[85vh] max-w-3xl overflow-y-auto">
        <ModalHeader>
          <ModalTitle>
            {order.orderCode} · {order.productSkuCode}
          </ModalTitle>
          <ModalDescription>
            {assemblySourceLabel(order.sourceSystem)} · BOM {order.bomCode} {order.bomVersionNo}
            {order.referenceNo ? ` · 上游 ${order.referenceNo}` : ''}
          </ModalDescription>
        </ModalHeader>

        <dl className="grid gap-2 sm:grid-cols-3">
          <Fact label="组装数量" value={formatQuantity(order.assemblyQuantity)} />
          <Fact label="组件应领合计" value={formatQuantity(order.requiredComponentQuantity)} />
          <Fact label="组件实发合计" value={formatQuantity(order.issuedComponentQuantity)} />
          <Fact label="成品入库量" value={formatQuantity(order.inboundProductQuantity)} />
          <Fact label="库存分录" value={order.inventoryTransactionId || '—'} />
          <Fact label="状态" value={assemblyStatusLabel(order.status)} />
        </dl>

        {order.status === 'COMPLETED' && (!inv.component || !inv.product) && (
          <div className="flex items-start gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600">
            <TriangleAlert className="mt-0.5 size-4 shrink-0" />
            <p>
              这张单的两条不变量读数对不上（组件 {!inv.component ? 'I1' : ''}
              {!inv.component && !inv.product ? ' / ' : ''}
              {!inv.product ? 'I2' : ''}
              ）。后端在数据库层已强制，账不对就说明有人绕过服务层改过数据。
            </p>
          </div>
        )}

        <SectionCard title={`组件明细（${detail.components.length}）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">组件</th>
                  <th className="px-2 py-1">单位用量 / 损耗率</th>
                  <th className="px-2 py-1">应领</th>
                  <th className="px-2 py-1">实发</th>
                  <th className="px-2 py-1">角色</th>
                </tr>
              </thead>
              <tbody>
                {detail.components.map((c) => (
                  <tr key={c.id} className="border-t">
                    <td className="px-2 py-1">{c.skuCode}</td>
                    <td className="text-muted-foreground px-2 py-1">
                      {formatQuantity(c.unitQuantity)} / {formatQuantity(c.scrapRate)}%
                    </td>
                    <td className="px-2 py-1">{formatQuantity(c.requiredQuantity)}</td>
                    <td className="px-2 py-1">{formatQuantity(c.issuedQuantity)}</td>
                    <td className="text-muted-foreground px-2 py-1">{c.role || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </SectionCard>

        {detail.entries.length > 0 && (
          <SectionCard title={`账本分录（${detail.entries.length}）`}>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left">
                  <tr>
                    <th className="px-2 py-1">方向</th>
                    <th className="px-2 py-1">数量变动</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.entries.map((e) => (
                    <tr key={e.entryId} className="border-t">
                      <td className="px-2 py-1">{entryDirectionLabel(e.direction)}</td>
                      <td
                        className={
                          e.onHandDelta < 0
                            ? 'px-2 py-1 text-red-600'
                            : 'px-2 py-1 text-emerald-600'
                        }
                      >
                        {formatQuantity(e.onHandDelta)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>
        )}

        {order.status === 'CANCELLED' && order.cancelReason && (
          <p className="text-muted-foreground text-sm">取消理由：{order.cancelReason}</p>
        )}

        <ModalFooter>
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            关闭
          </Button>
          {!draft && (
            <span className="text-muted-foreground self-center text-xs">
              {assemblyStatusLabel(order.status)}
              是终态：组件已出库、成品已入库，不能再改数或重复执行。
            </span>
          )}
          {draft && (
            <>
              <Button
                variant="ghost"
                disabled={busy}
                onClick={() => {
                  const reason = window.prompt('取消理由（必填）');
                  if (reason && reason.trim()) onCancel(reason.trim());
                }}
              >
                取消组装单
              </Button>
              <Button disabled={busy} onClick={onIssue}>
                执行组装
              </Button>
            </>
          )}
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border p-2">
      <dt className="text-muted-foreground text-xs">{label}</dt>
      <dd className="truncate text-sm font-medium">{value}</dd>
    </div>
  );
}

function TextField({
  label,
  value,
  onChange,
  hint,
  invalid,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  hint?: string;
  invalid?: boolean;
}) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span className="text-muted-foreground">{label}</span>
      <Input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className={invalid ? 'border-red-500' : undefined}
      />
      {(hint || invalid) && (
        <span className={invalid ? 'text-xs text-red-600' : 'text-muted-foreground text-xs'}>
          {hint}
        </span>
      )}
    </label>
  );
}
