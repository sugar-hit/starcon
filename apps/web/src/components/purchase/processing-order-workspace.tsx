'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Factory, PackageCheck, RefreshCw, TriangleAlert } from 'lucide-react';
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
  FactorySaved,
  ProcessingCapabilities,
  ProcessingOrderDetail,
  ProcessingOrderSaved,
  RequirementPreview,
} from '../../../../../packages/api-contracts/src/index';
import {
  advanceStatus,
  createOrder,
  dateOnly,
  fetchCapabilities,
  fetchFactories,
  fetchOrder,
  fetchOrders,
  factoryKindLabels,
  factoryStatusLabels,
  fmt,
  inboundStatusLabel,
  isOverdue,
  issueComponent,
  issueProgressText,
  moment,
  needsQuantity,
  outstandingCount,
  previewRequirement,
  processingStatusLabels,
  statusActions,
  statusLabel,
  type ProcessingOrderParams,
} from './processing-order-model';
import { errorText } from '../inventory/inventory-model';
import { BomBindingPanel } from './bom-binding-panel';

/**
 * 6.4.1.1 加工单工作区。
 *
 * <p>蓝图：「发起委外/自有工厂加工：选择成品 SKU 与 BOM 版本、指定组件领用量与加工厂、
 * 约定完工数量与交期，跟踪加工进度与入库。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>组件领用量不是手填的</b>：新建时只填 BOM 版本 + 约定完工数量，
 *       页面先调试算接口把「按这一版 BOM，每个组件要多少」显示出来，
 *       再让运营确认下单 —— 下完单才知道料不对就晚了。
 *       损耗率与需求量口径来自 catalog 3.5.2.1 的规则 R6/R7，页面只显示不重算。
 *   <li><b>进度不是一个状态字段</b>：组件的「已领 / 需求（还欠）」并排显示，
 *       还欠 0 才算领满。
 *   <li><b>报完工 ≠ 已入库</b>：两列分开，未入库的差额常驻提示。
 *   <li><b>未交付的下游直接禁用并写明归属模块</b>，不给点下去 404 的按钮。
 *   <li>加载 / 空 / 错误三态齐全。
 * </ul>
 */
export function ProcessingOrderWorkspace() {
  const [params, setParams] = useState<ProcessingOrderParams>({ page: 0, size: 20 });
  const [data, setData] = useState<Awaited<ReturnType<typeof fetchOrders>> | null>(null);
  const [caps, setCaps] = useState<ProcessingCapabilities | null>(null);
  const [factories, setFactories] = useState<FactorySaved[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [overdueOnly, setOverdueOnly] = useState(false);
  const [detail, setDetail] = useState<ProcessingOrderDetail | null>(null);
  const [creating, setCreating] = useState(false);
  const [busy, setBusy] = useState(false);
  const [actionFor, setActionFor] = useState<{
    detail: ProcessingOrderDetail;
    status: string;
    label: string;
  } | null>(null);
  const [issueFor, setIssueFor] = useState<ProcessingOrderDetail | null>(null);

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
        // capabilities 拿不到不该让整页空掉：列表仍然可用，只是边界说明与
        // 下游缺口提示退化。页面不假装自己知道规则。
        setCaps(null);
      }
    })();
    void (async () => {
      try {
        setFactories((await fetchFactories({ page: 0, size: 50 })).items);
      } catch {
        setFactories([]);
      }
    })();
  }, []);

  const orders = data?.items ?? [];

  const openDetail = useCallback(async (id: string) => {
    try {
      setDetail(await fetchOrder(id));
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  const run = useCallback(
    async (work: () => Promise<unknown>, refreshId?: string | null) => {
      setBusy(true);
      setError(null);
      try {
        await work();
        await load();
        if (refreshId) {
          setDetail(await fetchOrder(refreshId));
        }
        setActionFor(null);
        setIssueFor(null);
      } catch (e) {
        setError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="加工单"
        description="委外 / 自有工厂加工：选择 BOM 版本、指定加工厂与交期，跟踪组件领用进度与入库。"
        actions={
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />刷新
            </Button>
            <Button onClick={() => setCreating(true)} disabled={busy}>
              <Factory />新建加工单
            </Button>
          </div>
        }
      />

      {/* 模块边界常驻：库存动作归 warehouse，这是蓝图加粗要求，不是描述 */}
      <div className="flex items-start gap-2 rounded-md border border-sky-500/40 bg-sky-500/10 p-3 text-sm">
        <PackageCheck className="mt-0.5 size-4 shrink-0" />
        <div>
          <p className="font-medium">加工是「消耗组件产生成品」的库存动作</p>
          <p className="text-muted-foreground">
            {caps?.note ??
              '组件出库与成品入库在 warehouse 的一个本地事务里完成，本页只登记加工单据与领用进度，不直接改库存。'}
          </p>
        </div>
      </div>

      {caps && caps.downstreamGaps.length > 0 && (
        <SectionCard title="下游模块接入情况">
          <div className="flex flex-wrap gap-2">
            {caps.downstreamGaps.map((gap) => (
              <span
                key={gap.moduleId}
                title={gap.effect}
                className={
                  gap.available
                    ? 'rounded-md border px-2 py-1 text-xs'
                    : 'rounded-md border border-dashed px-2 py-1 text-xs text-muted-foreground'
                }
              >
                {gap.moduleId} {gap.moduleName}
                {gap.available ? '（已接入）' : ` · 未交付 → ${gap.ownerModule}`}
              </span>
            ))}
          </div>
          <p className="mt-2 text-xs text-muted-foreground">{caps.upstreamNote}</p>
        </SectionCard>
      )}

      {data && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="加工单" value={data.total} />
          <StatCard label="加工中" value={data.inProcessCount} />
          <StatCard label="待入库 / 已完工" value={data.completedCount} />
          <StatCard
            label="逾期"
            value={data.overdueCount}
            comparisonLabel={`已入库 ${data.receivedCount} · 已作废 ${data.cancelledCount}`}
          />
        </div>
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
              {Object.entries(processingStatusLabels).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={overdueOnly}
              onChange={(e) => setOverdueOnly(e.target.checked)}
            />
            只看逾期
          </label>
          <Button
            onClick={() => setParams(nextParams({ keyword, status, overdueOnly }))}
          >
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

      {loading && <p className="text-sm text-muted-foreground">正在加载加工单…</p>}

      {!loading && orders.length === 0 && !error && (
        <EmptyState
          title="还没有加工单"
          description="新建加工单时选择加工厂与已发布的 BOM 版本，组件领用量会按 BOM 规则自动算出来。"
        />
      )}

      {!loading && orders.length > 0 && (
        <SectionCard title={`加工单（${data?.total ?? 0}）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-muted-foreground">
                <tr>
                  <th className="px-2 py-1">单号</th>
                  <th className="px-2 py-1">加工厂</th>
                  <th className="px-2 py-1">成品 / BOM</th>
                  <th className="px-2 py-1">约定 / 完工</th>
                  <th className="px-2 py-1">组件领用</th>
                  <th className="px-2 py-1">交期</th>
                  <th className="px-2 py-1">状态</th>
                  <th className="px-2 py-1" />
                </tr>
              </thead>
              <tbody>
                {orders.map((order) => (
                  <tr key={order.id} className="border-t">
                    <td className="px-2 py-1 font-medium">{order.orderNo}</td>
                    <td className="px-2 py-1">{order.factoryName}</td>
                    <td className="px-2 py-1">
                      {order.outputSkuCode}
                      <span className="text-muted-foreground"> / {order.bomVersionNo}</span>
                    </td>
                    <td className="px-2 py-1">
                      {fmt(order.plannedOutputQuantity)} / {fmt(order.completedQuantity)}
                    </td>
                    <td className="px-2 py-1">
                      {order.componentCount === 0
                        ? '—'
                        : `${order.issuedComponentCount} / ${order.componentCount} 已领满`}
                    </td>
                    <td className="px-2 py-1">
                      {dateOnly(order.dueOn)}
                      {isOverdue(order) && (
                        <span className="ml-1 text-red-600">逾期</span>
                      )}
                    </td>
                    <td className="px-2 py-1">
                      <Badge tone={statusToneBadge(order.status)}>{statusLabel(order.status)}</Badge>
                    </td>
                    <td className="px-2 py-1 text-right">
                      <Button variant="ghost" onClick={() => void openDetail(order.id)}>
                        详情
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {data && data.total > params.size! && (
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
                disabled={((params.page ?? 0) + 1) * params.size! >= data.total}
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
          factories={factories}
          busy={busy}
          onClose={() => setCreating(false)}
          onCreated={async () => {
            setCreating(false);
            await load();
          }}
          onError={setError}
        />
      )}

      {detail && (
        <OrderDetailDialog
          detail={detail}
          busy={busy}
          onClose={() => setDetail(null)}
          onStatus={(status, label) => setActionFor({ detail, status, label })}
          onIssue={() => setIssueFor(detail)}
        />
      )}

      {actionFor && (
        <StatusActionDialog
          target={actionFor}
          busy={busy}
          onClose={() => setActionFor(null)}
          onSubmit={(quantity, reason, note) =>
            run(
              () =>
                advanceStatus(actionFor.detail.order.id, {
                  status: actionFor.status,
                  quantity: quantity ?? null,
                  reason: reason ?? null,
                  note: note ?? null,
                  version: actionFor.detail.order.version,
                }),
              actionFor.detail.order.id,
            )
          }
        />
      )}

      {issueFor && (
        <IssueDialog
          detail={issueFor}
          busy={busy}
          onClose={() => setIssueFor(null)}
          onSubmit={(componentId, quantity, note) =>
            run(
              () =>
                issueComponent(issueFor.order.id, {
                  componentId,
                  issuedQuantity: quantity,
                  note: note ?? null,
                  version: issueFor.order.version,
                }),
              issueFor.order.id,
            )
          }
        />
      )}
    </div>
  );
}

/** 把界面上的筛选控件折成查询参数；空值**不出现**在对象里（exactOptionalPropertyTypes）。 */
function nextParams(input: {
  keyword: string;
  status: string;
  overdueOnly: boolean;
}): ProcessingOrderParams {
  const params: ProcessingOrderParams = { page: 0, size: 20 };
  if (input.keyword.trim()) params.keyword = input.keyword.trim();
  if (input.status) params.status = input.status;
  if (input.overdueOnly) params.overdueOnly = true;
  return params;
}

/** 入库需求通知的色调：待入库是主色（还欠着），部分入库是警告，全入库是成功。 */
function inboundTone(status: string): 'neutral' | 'primary' | 'success' | 'danger' | 'warning' {
  switch (status) {
    case 'PENDING':
      return 'primary';
    case 'PARTIAL':
      return 'warning';
    case 'RECEIVED':
      return 'success';
    case 'CANCELLED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 状态徽章色调：草稿/作废中性、加工中/已下达主色、完工/已入库成功。 */
function statusToneBadge(status: string): 'neutral' | 'primary' | 'success' | 'danger' | 'warning' {
  switch (status) {
    case 'RELEASED':
    case 'IN_PROCESS':
      return 'primary';
    case 'COMPLETED':
      return 'warning';
    case 'RECEIVED':
      return 'success';
    case 'CANCELLED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 新建加工单：先试算组件需求量，再确认下单。 */
function CreateOrderDialog({
  factories,
  busy,
  onClose,
  onCreated,
  onError,
}: {
  factories: FactorySaved[];
  busy: boolean;
  onClose: () => void;
  onCreated: () => void | Promise<void>;
  onError: (message: string) => void;
}) {
  const [orderNo, setOrderNo] = useState('');
  const [factoryId, setFactoryId] = useState('');
  const [bomVersionId, setBomVersionId] = useState('');
  const [planned, setPlanned] = useState('10');
  const [dueOn, setDueOn] = useState(() => new Date(Date.now() + 30 * 864e5).toISOString().slice(0, 10));
  const [note, setNote] = useState('');
  const [preview, setPreview] = useState<RequirementPreview | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const plannedValue = useMemo(() => {
    const parsed = Number(planned);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
  }, [planned]);

  const runPreview = useCallback(async () => {
    if (!bomVersionId || plannedValue === null) {
      onError('请先填写 BOM 版本 ID 与大于 0 的约定完工数量');
      return;
    }
    setPreviewing(true);
    try {
      setPreview(
        await previewRequirement({ bomVersionId, outputQuantity: plannedValue }),
      );
    } catch (e) {
      setPreview(null);
      onError(errorText(e));
    } finally {
      setPreviewing(false);
    }
  }, [bomVersionId, plannedValue, onError]);

  return (
    <Modal open onOpenChange={() => onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>新建加工单</ModalTitle>
          <ModalDescription>
            组件领用量由已发布的 BOM 版本按规则 R7 自动算出（单位用量 × 产量 ×
            (1 + 损耗率/100)），页面只显示不重算。先试算确认料，再下单。
          </ModalDescription>
        </ModalHeader>
        <div className="flex flex-col gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">加工单号</span>
            <Input value={orderNo} onChange={(e) => setOrderNo(e.target.value)} placeholder="PO-2026-0001" />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">加工厂</span>
            <select
              className="h-9 rounded-md border px-2"
              value={factoryId}
              onChange={(e) => setFactoryId(e.target.value)}
            >
              <option value="">请选择</option>
              {factories.map((f) => (
                <option key={f.id} value={f.id}>
                  {f.code} {f.name}（{factoryKindLabels[f.factoryKind] ?? f.factoryKind} ·{' '}
                  {factoryStatusLabels[f.status] ?? f.status}）
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">BOM 版本 ID</span>
            <Input
              value={bomVersionId}
              onChange={(e) => setBomVersionId(e.target.value)}
              placeholder="catalog 的 bom_version.id"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">约定完工数量</span>
            <Input value={planned} onChange={(e) => setPlanned(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">交期</span>
            <Input type="date" value={dueOn} onChange={(e) => setDueOn(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">备注</span>
            <Input value={note} onChange={(e) => setNote(e.target.value)} />
          </label>

          <Button variant="ghost" onClick={() => void runPreview()} disabled={previewing}>
            {previewing ? '正在试算…' : '试算组件需求量'}
          </Button>

          {preview && (
            <div className="rounded-md border p-3 text-sm">
              <p className="mb-2 text-muted-foreground">
                按 BOM 版本 {preview.bomVersionNo}（{preview.outputSkuCode}）生产 {fmt(preview.outputQuantity)}{' '}
                件，需要：
              </p>
              <ul className="flex flex-col gap-1">
                {preview.components.map((line) => (
                  <li key={line.skuId} className="flex justify-between gap-2">
                    <span>{line.skuCode}</span>
                    <span className="text-muted-foreground">
                      {fmt(line.unitQuantity)} × {fmt(preview.outputQuantity)} × (1+{fmt(line.scrapRate)}%) ={' '}
                      <span className="font-medium text-foreground">{fmt(line.requiredQuantity)}</span>
                    </span>
                  </li>
                ))}
              </ul>
              <p className="mt-2 text-xs text-muted-foreground">{preview.note}</p>
            </div>
          )}
        </div>
        <ModalFooter>
          <Button
            disabled={busy || previewing || submitting}
            onClick={async () => {
              setSubmitting(true);
              try {
                await createOrder({
                  orderNo: orderNo.trim(),
                  factoryId,
                  bomVersionId,
                  plannedOutputQuantity: plannedValue ?? 0,
                  dueOn,
                  note: note.trim() || null,
                });
                await onCreated();
              } catch (e) {
                onError(errorText(e));
              } finally {
                setSubmitting(false);
              }
            }}
          >
            创建（草稿）
          </Button>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

/** 状态推进（下达 / 开工 / 报完工 / 登记入库 / 作废）。 */
function StatusActionDialog({
  target,
  busy,
  onClose,
  onSubmit,
}: {
  target: { detail: ProcessingOrderDetail; status: string; label: string };
  busy: boolean;
  onClose: () => void;
  onSubmit: (quantity: number | null, reason: string | null, note: string | null) => void;
}) {
  const [quantity, setQuantity] = useState('');
  const [reason, setReason] = useState('');
  const [note, setNote] = useState('');
  const wantsQuantity = needsQuantity(target.status);
  const needsReason = target.status === 'CANCELLED';
  const planned = Number(target.detail.order.plannedOutputQuantity);

  return (
    <Modal open onOpenChange={onClose}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>
            {target.label} · {target.detail.order.orderNo}
          </ModalTitle>
          <ModalDescription>
            {target.status === 'RELEASED' &&
              '下达会同时生成入库需求通知（入库需求通知 8），并冻结组件领用量。此后单位用量与需求量不可改。'}
            {target.status === 'IN_PROCESS' && '登记工厂开工，从这一刻开始按加工中跟踪。'}
            {target.status === 'COMPLETED' &&
              `报工厂报的完工数量（约定 ${fmt(target.detail.order.plannedOutputQuantity)}）。报完工不等于已入库 —— 入库要在仓库实收后另行登记。`}
            {target.status === 'RECEIVED' &&
              `登记仓库实收数量（待入库 ${fmt(target.detail.inboundRequest?.requestedQuantity)}）。收满才转已入库；未收满会停在已完工，余量可以继续登记。`}
            {target.status === 'CANCELLED' &&
              '作废必须有原因：没有原因的作废等于把一张已经发给厂里的单悄悄抹掉。入库需求通知会一并作废。'}
          </ModalDescription>
        </ModalHeader>
        <div className="flex flex-col gap-3">
          {wantsQuantity && (
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">
                {target.status === 'COMPLETED' ? '完工数量' : '实收数量'}
              </span>
              <Input value={quantity} onChange={(e) => setQuantity(e.target.value)} />
            </label>
          )}
          {needsReason && (
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">作废原因（必填）</span>
              <Input value={reason} onChange={(e) => setReason(e.target.value)} />
            </label>
          )}
          {!wantsQuantity && (
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">说明</span>
              <Input value={note} onChange={(e) => setNote(e.target.value)} />
            </label>
          )}
        </div>
        <ModalFooter>
          <Button
            disabled={busy}
            onClick={() =>
              onSubmit(
                wantsQuantity && quantity ? Number(quantity) : null,
                reason.trim() || null,
                note.trim() || null,
              )
            }
          >
            确认{target.label}
          </Button>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

/** 登记组件领用进度（记「已领多少」，不改库存）。 */
function IssueDialog({
  detail,
  busy,
  onClose,
  onSubmit,
}: {
  detail: ProcessingOrderDetail;
  busy: boolean;
  onClose: () => void;
  onSubmit: (componentId: string, quantity: number, note: string | null) => void;
}) {
  const [componentId, setComponentId] = useState(detail.components[0]?.id ?? '');
  const [quantity, setQuantity] = useState('');
  const [note, setNote] = useState('');
  const selected = detail.components.find((c) => c.id === componentId);
  const remaining = outstandingCount(detail.components);

  return (
    <Modal open onOpenChange={onClose}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>登记组件领用 · {detail.order.orderNo}</ModalTitle>
          <ModalDescription>
            这里登记的是**领用进度**（已领多少），组件出库与成品入库由 warehouse 在它自己的
            本地事务里完成。超领会被拒绝 —— 多领的料没有对应产出。
          </ModalDescription>
        </ModalHeader>
        <div className="flex flex-col gap-3 text-sm">
          <p className="text-muted-foreground">
            当前还有 <span className="font-medium text-foreground">{remaining}</span> 个组件未领满。
          </p>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">组件</span>
            <select
              className="h-9 rounded-md border px-2"
              value={componentId}
              onChange={(e) => setComponentId(e.target.value)}
            >
              {detail.components.map((line) => (
                <option key={line.id} value={line.id}>
                  {line.skuCode}（{issueProgressText(line)}）
                </option>
              ))}
            </select>
          </label>
          {selected && (
            <p className="rounded-md border p-2 text-xs text-muted-foreground">
              单位用量 {fmt(selected.unitQuantity)} · 损耗率 {fmt(selected.scrapRate)}%（百分数）·{' '}
              {issueProgressText(selected)}
            </p>
          )}
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">本次实发数量</span>
            <Input value={quantity} onChange={(e) => setQuantity(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">说明</span>
            <Input value={note} onChange={(e) => setNote(e.target.value)} />
          </label>
        </div>
        <ModalFooter>
          <Button
            disabled={busy || !componentId || !quantity}
            onClick={() => onSubmit(componentId, Number(quantity), note.trim() || null)}
          >
            登记领用
          </Button>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

/** 详情弹窗的宿主：加工单 + 组件领用记录 + 入库需求通知 + 事件流。 */
function OrderDetailDialog({
  detail,
  busy,
  onClose,
  onStatus,
  onIssue,
}: {
  detail: ProcessingOrderDetail;
  busy: boolean;
  onClose: () => void;
  onStatus: (status: string, label: string) => void;
  onIssue: () => void;
}) {
  const actions = statusActions(detail.order.status);
  const gap =
    detail.inboundRequest &&
    Number(detail.inboundRequest.outstandingQuantity) > 0 &&
    `还有 ${fmt(detail.inboundRequest.outstandingQuantity)} 件未入库（待入库 ${fmt(
      detail.inboundRequest.requestedQuantity,
    )}，实收 ${fmt(detail.inboundRequest.receivedQuantity)}）`;

  return (
    <Modal open onOpenChange={onClose}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>
            {detail.order.orderNo} · {detail.order.factoryName}
          </ModalTitle>
          <ModalDescription>
            成品 {detail.order.outputSkuCode} · BOM 版本 {detail.order.bomVersionNo} · 约定{' '}
            {fmt(detail.order.plannedOutputQuantity)} · 完工 {fmt(detail.order.completedQuantity)} ·
            交期 {dateOnly(detail.order.dueOn)}
          </ModalDescription>
        </ModalHeader>

        <div className="flex flex-col gap-4">
          {detail.boundaryNotes.length > 0 && (
            <ul className="rounded-md border p-3 text-xs text-muted-foreground">
              {detail.boundaryNotes.map((note) => (
                <li key={note}>· {note}</li>
              ))}
            </ul>
          )}

          <section>
            <h3 className="mb-2 text-sm font-medium">组件领用记录</h3>
            {detail.components.length === 0 ? (
              <p className="text-sm text-muted-foreground">这张加工单还没有组件领用记录。</p>
            ) : (
              <table className="w-full text-sm">
                <thead className="text-left text-muted-foreground">
                  <tr>
                    <th className="px-2 py-1">组件</th>
                    <th className="px-2 py-1">单位用量</th>
                    <th className="px-2 py-1">损耗率</th>
                    <th className="px-2 py-1">需求</th>
                    <th className="px-2 py-1">已领</th>
                    <th className="px-2 py-1">还欠</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.components.map((line) => (
                    <tr key={line.id} className="border-t">
                      <td className="px-2 py-1">{line.skuCode}</td>
                      <td className="px-2 py-1">{fmt(line.unitQuantity)}</td>
                      <td className="px-2 py-1">{fmt(line.scrapRate)}%</td>
                      <td className="px-2 py-1">{fmt(line.requiredQuantity)}</td>
                      <td className="px-2 py-1">{fmt(line.issuedQuantity)}</td>
                      <td className="px-2 py-1">
                        {Number(line.outstandingQuantity) > 0 ? (
                          <span className="text-amber-600">{fmt(line.outstandingQuantity)}</span>
                        ) : (
                          <span className="text-muted-foreground">已领满</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>

          {/* 6.4.1.2 BOM 关联：冻结展开结果（与上面的组件领用记录不是同一份东西）*/}
          <BomBindingPanel orderId={detail.order.id} orderStatus={detail.order.status} />

          <section>
            <h3 className="mb-2 text-sm font-medium">入库需求通知</h3>
            {!detail.inboundRequest ? (
              <p className="text-sm text-muted-foreground">
                尚未下达 —— 入库需求通知随「下达」一起生成。
              </p>
            ) : (
              <div className="rounded-md border p-3 text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={inboundTone(detail.inboundRequest.status)}>
                    {inboundStatusLabel(detail.inboundRequest.status)}
                  </Badge>
                  <span>{detail.inboundRequest.skuCode}</span>
                </div>
                <p className="mt-1 text-muted-foreground">
                  待入库 {fmt(detail.inboundRequest.requestedQuantity)} · 实收{' '}
                  {fmt(detail.inboundRequest.receivedQuantity)} · 登记于{' '}
                  {moment(detail.inboundRequest.requestedAt)}
                  {detail.inboundRequest.inventoryTransactionRef &&
                    ` · 入库单号 ${detail.inboundRequest.inventoryTransactionRef}`}
                </p>
                {gap && <p className="mt-1 text-amber-600">{gap}</p>}
              </div>
            )}
          </section>

          <section>
            <h3 className="mb-2 text-sm font-medium">进度与状态事件</h3>
            <ul className="flex flex-col gap-1 text-sm">
              {detail.events.map((event) => (
                <li key={event.id} className="flex gap-2">
                  <span className="w-36 shrink-0 text-muted-foreground">
                    {moment(event.occurredAt)}
                  </span>
                  <span>
                    {event.eventTypeLabel}
                    {event.fromStatus !== event.toStatus &&
                      `（${statusLabel(event.fromStatus)} → ${statusLabel(event.toStatus)}）`}
                    {Number(event.quantity) > 0 && ` · ${fmt(event.quantity)}`}
                    {event.note && ` · ${event.note}`}
                  </span>
                </li>
              ))}
            </ul>
          </section>
        </div>

        <ModalFooter>
          {actions.map((action) => (
            <Button
              key={action.status}
              variant={action.status === 'CANCELLED' ? 'ghost' : 'default'}
              disabled={busy}
              onClick={() => onStatus(action.status, action.label)}
            >
              {action.label}
            </Button>
          ))}
          {(detail.order.status === 'RELEASED' || detail.order.status === 'IN_PROCESS') && (
            <Button variant="ghost" disabled={busy} onClick={onIssue}>
              登记领用
            </Button>
          )}
          {actions.length === 0 && (
            <span className="text-xs text-muted-foreground">
              {statusLabel(detail.order.status)}是终态，没有可执行的动作。
            </span>
          )}
          <Button variant="ghost" onClick={onClose}>
            关闭
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

export type { ProcessingOrderSaved };
