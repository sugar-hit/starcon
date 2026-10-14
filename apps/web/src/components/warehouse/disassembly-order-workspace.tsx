'use client';

import { useCallback, useEffect, useState } from 'react';
import { PackageMinus, RefreshCw, TriangleAlert } from 'lucide-react';
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
  DisassemblyCapabilities,
  DisassemblyComponentInput,
  DisassemblyOrderDetail,
  DisassemblyOrderPage,
} from '../../../../../packages/api-contracts/src/index';
import {
  destinationRoleLabel,
  destinationRoleOptions,
  destinationRoleTone,
  disassemblySortKeys,
  disassemblySortLabels,
  disassemblySourceLabel,
  disassemblyStatusLabel,
  disassemblyStatusLabels,
  disassemblyStatusTone,
  cancelDisassemblyOrder,
  createDisassemblyOrder,
  entryDirectionLabel,
  executeDisassemblyOrder,
  fetchDisassemblyOrder,
  fetchDisassemblyOrders,
  fetchCapabilities,
  formatQuantity,
  invariantsHold,
  isExactAtStockScale,
  isValidRecoveryRate,
  type DisassemblyOrderParams,
} from './disassembly-order-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 8.4.2.1 拆解单工作区（成品拆解回组件）。
 *
 * <p>蓝图 08 卷：「成品拆解回组件：消耗成品库存、产生组件库存（用于退换货拆解、样品回收）。」
 * 输入「成品 SKU 与数量；拆解去向（可用/待检/残次）」，输出「拆解单 + 反向库存分录」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>去向逐行必填、没有默认值</b>：蓝图点名「拆解结果的组件质量状态由接收方决定，
 *       不能默认全部回可售」。建单表单的去向是必选下拉，没有「默认」项 ——
 *       不选就不让建单，而不是替操作员挑一个可售。</li>
 *   <li><b>成品出库量 / 组件应回 / 组件实回三列并排</b>，不藏起来 ——
 *       它们就是 D1 / D2 两条不变量的读数。</li>
 *   <li><b>去向分布常驻在汇总区</b>：多少组件回了可用、多少回了待检/残次，一眼可见。</li>
 *   <li><b>执行是不可逆的</b>：非草稿态的执行与取消按钮直接禁用并写明原因，
 *       而不是给一个点下去必然 409 的按钮。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */
export function DisassemblyOrderWorkspace() {
  const [params, setParams] = useState<DisassemblyOrderParams>({ page: 0, size: 20 });
  const [data, setData] = useState<DisassemblyOrderPage | null>(null);
  const [caps, setCaps] = useState<DisassemblyCapabilities | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [sort, setSort] = useState('CREATED_AT');
  const [detail, setDetail] = useState<DisassemblyOrderDetail | null>(null);
  const [creating, setCreating] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchDisassemblyOrders(params));
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
      setDetail(await fetchDisassemblyOrder(id));
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
          setDetail(await fetchDisassemblyOrder(refreshId));
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
        title="拆解单"
        description="成品拆解回组件：消耗成品库存、产生组件库存，用于退换货拆解与样品回收。"
        actions={
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
            <Button onClick={() => setCreating(true)} disabled={busy}>
              <PackageMinus />
              新建拆解单
            </Button>
          </div>
        }
      />

      {/* 模块边界常驻：去向由接收方决定 —— 这不是描述，是不能绕过的约束 */}
      <div className="flex items-start gap-2 rounded-md border border-violet-500/40 bg-violet-500/10 p-3 text-sm">
        <PackageMinus className="mt-0.5 size-4 shrink-0" />
        <div className="space-y-1">
          <p className="font-medium">组件去向逐行必填，没有默认值</p>
          <p className="text-muted-foreground">
            {caps?.qualityDestinationRule ??
              '拆解结果的组件质量状态由接收方决定，不能默认全部回可售：每一行都要显式写明可用 / 待检 / 残次。'}
          </p>
          {caps && <p className="text-muted-foreground">{caps.upstreamBoundary}</p>}
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
          <StatCard label="拆解单" value={data.total} />
          <StatCard label="草稿" value={data.draftCount} />
          <StatCard label="已完成" value={data.completedCount} />
          <StatCard
            label="已取消"
            value={data.cancelledCount}
            comparisonLabel={`拆解数量合计 ${formatQuantity(data.totalDisassemblyQuantity)}`}
          />
        </div>
      )}

      {data && data.total > 0 && (
        <SectionCard title="不变量读数">
          <dl className="grid gap-3 sm:grid-cols-3">
            <Reading
              label="组件应回合计"
              value={formatQuantity(data.totalExpectedComponentQuantity)}
              hint="按 BOM 反推 + 回收率冻结"
            />
            <Reading
              label="组件实回合计"
              value={formatQuantity(data.totalRecoveredComponentQuantity)}
              hint="执行后必须等于应回合计（D2）"
            />
            <Reading
              label="拆解数量合计"
              value={formatQuantity(data.totalDisassemblyQuantity)}
              hint="等于成品出库量（D1）"
            />
          </dl>
        </SectionCard>
      )}

      {/* 去向分布常驻：看不到它，「不能默认全部回可售」就只是一句文档里的话 */}
      {data && data.destinations.length > 0 && (
        <SectionCard title="组件去向分布">
          <div className="flex flex-wrap gap-2">
            {data.destinations.map((d) => (
              <div
                key={d.qualityStatusId}
                className="rounded-md border p-3"
                title={`${d.qualityRole} · ${d.lineCount} 行明细`}
              >
                <Badge className={destinationRoleTone(d.qualityRole)}>
                  {d.qualityCode} · {destinationRoleLabel(d.qualityRole)}
                </Badge>
                <p className="mt-2 text-lg font-semibold">{formatQuantity(d.expectedQuantity)}</p>
                <p className="text-muted-foreground text-xs">
                  应回 {formatQuantity(d.expectedQuantity)} · 实回{' '}
                  {formatQuantity(d.recoveredQuantity)} · {d.lineCount} 行
                </p>
              </div>
            ))}
          </div>
          <p className="text-muted-foreground mt-2 text-xs">
            去向由接收方逐行判定：只有明确判为「可用」的组件才进可售货架，待检与残次各自成账。
          </p>
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
              {Object.entries(disassemblyStatusLabels).map(([value, label]) => (
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
              {disassemblySortKeys.map((key) => (
                <option key={key} value={key}>
                  {disassemblySortLabels[key]}
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

      {loading && <p className="text-muted-foreground text-sm">正在加载拆解单…</p>}

      {!loading && orders.length === 0 && !error && (
        <EmptyState
          title="还没有拆解单"
          description="新建拆解单时选择成品 SKU 与 BOM 版本，逐个组件填应回量与去向；执行拆解会扣掉成品并把组件按去向入库。"
        />
      )}

      {!loading && orders.length > 0 && (
        <SectionCard title={`拆解单（${data?.total ?? 0}）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">单号</th>
                  <th className="px-2 py-1">成品 / BOM</th>
                  <th className="px-2 py-1">来源</th>
                  <th className="px-2 py-1">拆解数量</th>
                  <th className="px-2 py-1">组件 应回 / 实回</th>
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
                      <td className="px-2 py-1">{disassemblySourceLabel(order.sourceSystem)}</td>
                      <td className="px-2 py-1">{formatQuantity(order.disassemblyQuantity)}</td>
                      <td className="px-2 py-1">
                        {order.componentCount} 项 ·{' '}
                        {formatQuantity(order.expectedComponentQuantity)} /{' '}
                        {formatQuantity(order.recoveredComponentQuantity)}
                        {order.status === 'COMPLETED' && !inv.component && (
                          <span className="ml-1 text-red-600">（D2 不成立）</span>
                        )}
                      </td>
                      <td className="px-2 py-1">
                        <Badge className={disassemblyStatusTone(order.status)}>
                          {disassemblyStatusLabel(order.status)}
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
            await run(() => createDisassemblyOrder(input));
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
          onExecute={() =>
            run(() => executeDisassemblyOrder(detail.order.orderId), detail.order.orderId)
          }
          onCancel={(reason) =>
            run(
              () => cancelDisassemblyOrder(detail.order.orderId, { reason }),
              detail.order.orderId,
            )
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

/** 能力声明里的三条不变量常驻显示：它们是验收口径，不是运行时才知道的日志。 */
function InvariantCard({ caps }: { caps: DisassemblyCapabilities }) {
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
  expectedQuantity: string;
  unitQuantity: string;
  recoveryRate: string;
  /** 去向：空字符串 = 还没判（**不允许提交**，所以它不是「默认」而是「未选」）。 */
  destinationRole: string;
  destinationQualityStatusId: string;
  destinationLocationId: string;
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
  onSubmit: (input: Parameters<typeof createDisassemblyOrder>[0]) => Promise<void>;
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
  const [disassemblyQuantity, setDisassemblyQuantity] = useState('');
  const [outboundLocationId, setOutboundLocationId] = useState('');
  const [notes, setNotes] = useState('');
  const [components, setComponents] = useState<DraftComponent[]>([
    {
      skuId: '',
      skuCode: '',
      expectedQuantity: '',
      unitQuantity: '',
      recoveryRate: '100',
      destinationRole: '',
      destinationQualityStatusId: '',
      destinationLocationId: '',
      role: '',
    },
  ]);

  const qtyOk = isExactAtStockScale(disassemblyQuantity);
  const linesOk =
    components.length > 0 &&
    components.every(
      (c) =>
        c.skuId.trim() &&
        c.destinationQualityStatusId.trim() &&
        c.destinationLocationId.trim() &&
        isExactAtStockScale(c.expectedQuantity) &&
        isValidRecoveryRate(c.recoveryRate),
    );
  const missingDestination = components.some((c) => c.skuId.trim() && !c.destinationRole.trim());
  const ready =
    productSkuId.trim() &&
    bomId.trim() &&
    bomVersionId.trim() &&
    warehouseId.trim() &&
    legalEntityId.trim() &&
    outboundLocationId.trim() &&
    qtyOk &&
    linesOk;

  const patch = (index: number, next: Partial<DraftComponent>) =>
    setComponents((prev) => prev.map((c, i) => (i === index ? { ...c, ...next } : c)));

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-h-[85vh] max-w-4xl overflow-y-auto">
        <ModalHeader>
          <ModalTitle>新建拆解单</ModalTitle>
          <ModalDescription>
            组件应回量按「单位用量 × 拆解数量 × 回收率/100」在建单时冻结，之后 BOM
            改版不影响这张单。 每一行都必须显式选择入库去向（可用 / 待检 / 残次）——
            拆解结果的组件质量状态由接收方决定， 不能默认全部回可售。
          </ModalDescription>
        </ModalHeader>

        <div className="grid gap-3 sm:grid-cols-2">
          <TextField
            label="成品 SKU ID"
            value={productSkuId}
            onChange={setProductSkuId}
            hint="要被拆的那件成品"
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
              <option value="ASSEMBLY_ORDER">来自组装单（8.4.1.1）</option>
              <option value="AFTERSALES_RETURN">来自售后退件（11 售后）</option>
            </select>
          </label>
          <TextField
            label="上游单据引用 ID"
            value={referenceId}
            onChange={setReferenceId}
            hint={
              sourceSystem === 'DIRECT'
                ? 'DIRECT 不带引用键'
                : sourceSystem === 'ASSEMBLY_ORDER'
                  ? '8.4.1.1 组装单（必须已完成，跨单据用逻辑引用键、不建外键）'
                  : '11 售后退件单（跨服务无外键，仅作追溯）'
            }
          />
          <TextField label="BOM ID（catalog）" value={bomId} onChange={setBomId} />
          <TextField label="BOM 编码" value={bomCode} onChange={setBomCode} />
          <TextField label="BOM 版本 ID" value={bomVersionId} onChange={setBomVersionId} />
          <TextField label="BOM 版本号" value={bomVersionNo} onChange={setBomVersionNo} />
          <TextField
            label="成品出库库位 ID"
            value={outboundLocationId}
            onChange={setOutboundLocationId}
          />
          <TextField
            label="拆解数量"
            value={disassemblyQuantity}
            onChange={setDisassemblyQuantity}
            hint={
              disassemblyQuantity && !qtyOk
                ? '不能被库存刻度（4 位小数）精确表示'
                : '同时是成品出库量（D1）'
            }
            invalid={Boolean(disassemblyQuantity) && !qtyOk}
          />
        </div>

        <div className="mt-4 space-y-2">
          <div className="flex items-center justify-between">
            <p className="text-sm font-medium">组件明细（BOM 反向冻结快照 + 逐行去向）</p>
            <Button
              variant="ghost"
              onClick={() =>
                setComponents((prev) => [
                  ...prev,
                  {
                    skuId: '',
                    skuCode: '',
                    expectedQuantity: '',
                    unitQuantity: '',
                    recoveryRate: '100',
                    destinationRole: '',
                    destinationQualityStatusId: '',
                    destinationLocationId: '',
                    role: '',
                  },
                ])
              }
            >
              加一行
            </Button>
          </div>
          {missingDestination && (
            <div className="flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 p-2 text-sm text-amber-600">
              <TriangleAlert className="mt-0.5 size-4 shrink-0" />
              <p>
                有组件还没选去向。拆解结果的组件质量状态由接收方决定，本模块不替你默认回可售 ——
                退回的货凭什么能直接卖？
              </p>
            </div>
          )}
          {components.map((c, index) => {
            const lineOk =
              c.skuId.trim() &&
              c.destinationQualityStatusId.trim() &&
              c.destinationLocationId.trim() &&
              isExactAtStockScale(c.expectedQuantity) &&
              isValidRecoveryRate(c.recoveryRate);
            return (
              <div
                key={index}
                className="grid gap-2 rounded-md border p-2 sm:grid-cols-2 lg:grid-cols-4"
              >
                <TextField
                  label="组件 SKU ID"
                  value={c.skuId}
                  onChange={(v) => patch(index, { skuId: v })}
                />
                <TextField
                  label="应回量"
                  value={c.expectedQuantity}
                  onChange={(v) => patch(index, { expectedQuantity: v })}
                  invalid={Boolean(c.expectedQuantity) && !isExactAtStockScale(c.expectedQuantity)}
                />
                <TextField
                  label="单位用量"
                  value={c.unitQuantity}
                  onChange={(v) => patch(index, { unitQuantity: v })}
                  hint="追溯用，可空"
                />
                <TextField
                  label="回收率 %"
                  value={c.recoveryRate}
                  onChange={(v) => patch(index, { recoveryRate: v })}
                  hint="退件缺件/损坏的损耗，默认 100"
                  invalid={!isValidRecoveryRate(c.recoveryRate)}
                />
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">
                    去向（<span className="text-red-600">必填</span>）
                  </span>
                  <select
                    className={
                      'h-9 rounded-md border px-2' +
                      (c.skuId.trim() && !c.destinationRole ? ' border-red-500' : '')
                    }
                    value={c.destinationRole}
                    onChange={(e) => patch(index, { destinationRole: e.target.value })}
                  >
                    <option value="">请选择去向…</option>
                    {destinationRoleOptions.map((role) => (
                      <option key={role} value={role}>
                        {destinationRoleLabel(role)}
                      </option>
                    ))}
                  </select>
                  <span className="text-muted-foreground text-xs">没有「默认」这一项</span>
                </label>
                <TextField
                  label="去向质量状态 ID"
                  value={c.destinationQualityStatusId}
                  onChange={(v) => patch(index, { destinationQualityStatusId: v })}
                  hint="inventory_quality_status.id"
                />
                <TextField
                  label="去向库位 ID"
                  value={c.destinationLocationId}
                  onChange={(v) => patch(index, { destinationLocationId: v })}
                  hint="良品区 / 待检区 / 残次区可分开"
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
                {c.expectedQuantity && !lineOk && (
                  <p className="text-xs text-red-600 sm:col-span-4">
                    {c.skuId.trim() && !c.destinationQualityStatusId.trim()
                      ? '必须指定入库去向（可用 / 待检 / 残次），本模块不提供默认值。'
                      : c.expectedQuantity && !isExactAtStockScale(c.expectedQuantity)
                        ? '应回量不能被库存刻度（4 位小数）精确表示：向下取整等于吞组件、向上取整等于多产。'
                        : !isValidRecoveryRate(c.recoveryRate)
                          ? '回收率是百分数，取值 [0, 100]。'
                          : '去向库位必填。'}
                  </p>
                )}
              </div>
            );
          })}
        </div>

        <div className="mt-3">
          <TextField label="备注" value={notes} onChange={setNotes} hint="可空" />
        </div>

        {notes && !ready && (
          <p className="mt-2 text-xs text-amber-600">
            还不能建单：成品 SKU、仓库、法人、BOM、出库库位与拆解数量必填， 且每个组件都要有
            SKU、应回量、入库去向与去向库位。
          </p>
        )}

        <ModalFooter>
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            取消
          </Button>
          <Button
            disabled={busy || !ready}
            onClick={async () => {
              const payload: DisassemblyComponentInput[] = components.map((c) => ({
                skuId: c.skuId.trim(),
                bomComponentId: null,
                expectedQuantity: Number(c.expectedQuantity),
                unitQuantity: c.unitQuantity ? Number(c.unitQuantity) : null,
                recoveryRate: c.recoveryRate ? Number(c.recoveryRate) : null,
                destinationQualityStatusId: c.destinationQualityStatusId.trim(),
                destinationLocationId: c.destinationLocationId.trim(),
                destinationLotId: null,
                role: c.role.trim() || null,
                note: null,
              }));
              try {
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
                  disassemblyQuantity: Number(disassemblyQuantity),
                  outboundLocationId: outboundLocationId.trim(),
                  outboundQualityStatusId: null,
                  outboundLotId: null,
                  notes: notes.trim() || null,
                  components: payload,
                });
              } catch (e) {
                onError(errorText(e));
              }
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
  onExecute,
  onCancel,
}: {
  detail: DisassemblyOrderDetail;
  busy: boolean;
  onClose: () => void;
  onExecute: () => void;
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
            {disassemblySourceLabel(order.sourceSystem)} · BOM {order.bomCode} {order.bomVersionNo}
            {order.referenceNo ? ` · 上游 ${order.referenceNo}` : ''}
          </ModalDescription>
        </ModalHeader>

        <dl className="grid gap-2 sm:grid-cols-3">
          <Fact label="拆解数量" value={formatQuantity(order.disassemblyQuantity)} />
          <Fact label="组件应回合计" value={formatQuantity(order.expectedComponentQuantity)} />
          <Fact label="组件实回合计" value={formatQuantity(order.recoveredComponentQuantity)} />
          <Fact label="成品出库量" value={formatQuantity(order.outboundProductQuantity)} />
          <Fact label="库存分录" value={order.inventoryTransactionId || '—'} />
          <Fact label="状态" value={disassemblyStatusLabel(order.status)} />
        </dl>

        {order.status === 'COMPLETED' && (!inv.component || !inv.product) && (
          <div className="flex items-start gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600">
            <TriangleAlert className="mt-0.5 size-4 shrink-0" />
            <p>
              这张单的不变量读数对不上（组件 {!inv.component ? 'D2' : ''}
              {!inv.component && !inv.product ? ' / ' : ''}
              {!inv.product ? 'D1' : ''}
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
                  <th className="px-2 py-1">单位用量 / 回收率</th>
                  <th className="px-2 py-1">应回</th>
                  <th className="px-2 py-1">实回</th>
                  <th className="px-2 py-1">去向</th>
                  <th className="px-2 py-1">角色</th>
                </tr>
              </thead>
              <tbody>
                {detail.components.map((c) => (
                  <tr key={c.id} className="border-t">
                    <td className="px-2 py-1">{c.skuCode}</td>
                    <td className="text-muted-foreground px-2 py-1">
                      {formatQuantity(c.unitQuantity)} / {formatQuantity(c.recoveryRate)}%
                    </td>
                    <td className="px-2 py-1">{formatQuantity(c.expectedQuantity)}</td>
                    <td className="px-2 py-1">{formatQuantity(c.recoveredQuantity)}</td>
                    <td className="px-2 py-1">
                      <Badge className={destinationRoleTone(c.destinationQualityRole)}>
                        {destinationRoleLabel(c.destinationQualityRole)}
                      </Badge>
                    </td>
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
              {disassemblyStatusLabel(order.status)}
              是终态：成品已出库、组件已入库，不能再改数或重复执行。
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
                取消拆解单
              </Button>
              <Button disabled={busy} onClick={onExecute}>
                执行拆解
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
