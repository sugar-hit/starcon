'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { ClipboardCheck, PackageCheck, RefreshCw, TriangleAlert } from 'lucide-react';
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
  PendingInspectionCapabilities,
  PendingInspectionOptions,
  PendingInspectionStockPage,
  PendingInspectionTaskDetail,
  PendingInspectionTaskPage,
} from '../../../../../packages/api-contracts/src/index';
import {
  canCancel,
  canDispose,
  canRecordResult,
  canRequestInspection,
  cancelPendingInspectionTask,
  createPendingInspectionTask,
  dispositionLabel,
  dispositionStaysInWarehouse,
  dispositionTypes,
  disposePendingInspectionTask,
  fetchPendingInspectionCapabilities,
  fetchPendingInspectionOptions,
  fetchPendingInspectionStock,
  fetchPendingInspectionTask,
  fetchPendingInspectionTasks,
  formatQuantity,
  formatSignedQuantity,
  inspectionQuantities,
  locationTypeLabel,
  netOnHandDeltaText,
  pendingInspectionStatusLabel,
  pendingInspectionStatusTone,
  qualityRoleLabel,
  recordPendingInspectionResult,
  requestPendingInspection,
  validateCreateQuantity,
  validateDispositionTotals,
  validateSellableLimit,
  type PendingInspectionParams,
} from './pending-inspection-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 8.2.2.2 待检处理工作区（待检区库存 → 质检 → 质量状态分流）。
 *
 * <p>蓝图 08 卷：「管理待检区库存：发起质检任务（6 质检单）、质检通过后转入可售、
 * 质检不通过转残次/报废/退货。」「输出：质量状态变更分录；可售量更新。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>「待检品不可售」是硬规则</b>：页首常驻说明，待检清单与可售量是两块分开的数字 ——
 *       压在待检区的货<b>不在</b>可售基数里，质检通过转可售之后才进。</li>
 *   <li><b>待检清单与处理单列表分开</b>：前者回答「仓里还有多少货压在待检区」，
 *       后者回答「这批货走到哪一步了」。</li>
 *   <li><b>质检结论不由人敲</b>：页面只展示从 6 质检单回读到的三量，
 *       并写明「数字来自质检单，不在这里输入」—— 免得操作员以为录不进是系统坏了。</li>
 *   <li><b>处置必须把这批货全部处置掉</b>：各去向之和与任务批量不一致时提交按钮禁用，
 *       并说清差多少；转可售的量超过质检结论里的可售量时同样禁用。</li>
 *   <li><b>可售量前后对照常驻</b>：处置前 / 处置后并排，让「可售量更新」看得见。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */
export function PendingInspectionWorkspace() {
  const [params, setParams] = useState<PendingInspectionParams>({ page: 0, size: 20 });
  const [data, setData] = useState<PendingInspectionTaskPage | null>(null);
  const [stock, setStock] = useState<PendingInspectionStockPage | null>(null);
  const [options, setOptions] = useState<PendingInspectionOptions | null>(null);
  const [caps, setCaps] = useState<PendingInspectionCapabilities | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [detail, setDetail] = useState<PendingInspectionTaskDetail | null>(null);
  const [creating, setCreating] = useState(false);
  const [disposing, setDisposing] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [tasks, pendingStock] = await Promise.all([
        fetchPendingInspectionTasks(params),
        fetchPendingInspectionStock({ page: 0, size: 50 }),
      ]);
      setData(tasks);
      setStock(pendingStock);
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
        setCaps(await fetchPendingInspectionCapabilities());
      } catch {
        // capabilities 拿不到不该让整页空掉：列表仍然可用，只是边界说明退化。
        setCaps(null);
      }
      try {
        setOptions(await fetchPendingInspectionOptions());
      } catch {
        setOptions(null);
      }
    })();
  }, []);

  const openDetail = useCallback(async (id: string) => {
    try {
      setDetail(await fetchPendingInspectionTask(id));
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
          setDetail(await fetchPendingInspectionTask(refreshId));
        }
      } catch (e) {
        setError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const tasks = data?.rows ?? [];
  const stockRows = stock?.rows ?? [];

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="待检处理"
        description="管理待检区库存：发起质检任务（6 质检单）、质检通过后转入可售、质检不通过转残次/报废/退货。"
        actions={
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
            <Button onClick={() => setCreating(true)} disabled={busy}>
              <ClipboardCheck />
              新建待检处理单
            </Button>
          </div>
        }
      />

      {/* 硬规则常驻：待检品不可售 + 处置必须全部处置掉 */}
      <div className="flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
        <TriangleAlert className="mt-0.5 size-4 shrink-0" />
        <div className="space-y-1">
          <p className="font-medium">待检品不可售：质检通过前不能被销售预占</p>
          <p className="text-muted-foreground">
            压在待检区的货不在可售基数里（7.1.1.3 的可售口径只认可售质量状态 + 可拣启用的库位），
            预占也会被数据库直接拒绝。质检通过转可售之后，这批货才进入可售量。
          </p>
          <p className="text-muted-foreground">
            另一条硬规则：一批待检货必须被<b>全部</b>处置掉 ——
            处置一半意味着另一半的结论永远没人给。
          </p>
        </div>
      </div>

      {caps && (
        <SectionCard title="本模块的硬规则与上下游">
          <ul className="space-y-2 text-sm">
            {caps.hardRules.map((rule) => (
              <li key={rule.code} className="rounded-md border p-2">
                <p className="font-medium">{rule.rule}</p>
                <p className="text-muted-foreground text-xs">裁决方：{rule.enforcedBy}</p>
              </li>
            ))}
          </ul>
          <div className="mt-3 flex flex-wrap gap-2">
            {caps.upstreamModules.map((m) => (
              <span
                key={m.moduleId}
                title={m.usage}
                className={
                  m.available
                    ? 'rounded-md border px-2 py-1 text-xs'
                    : 'text-muted-foreground rounded-md border border-dashed px-2 py-1 text-xs'
                }
              >
                {m.moduleId} {m.moduleName}（{m.available ? '已接入' : '未交付'}）
              </span>
            ))}
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
                {m.moduleId} {m.moduleName}（{m.available ? '已接入' : '未交付'}）
              </span>
            ))}
          </div>
          <p className="text-muted-foreground mt-2 text-xs">{caps.note}</p>
        </SectionCard>
      )}

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="待检库存行" value={stock?.bucketCount ?? 0} />
        <StatCard label="待检在手量" value={formatQuantity(stock?.totalOnHand)} />
        <StatCard label="待检处理单" value={data?.total ?? 0} />
        <StatCard
          label="待发起质检"
          value={data?.pendingCount ?? 0}
          comparisonLabel={`已发起 ${data?.inspectionRequestedCount ?? 0} · 已处置 ${
            data?.disposedCount ?? 0
          }`}
        />
      </div>

      {/* 待检库存清单：仓里还有多少货压在待检区 */}
      <SectionCard title="待检库存清单">
        {stockRows.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            当前没有待检库存。可售品与残次品不进这份清单 —— 它们不需要质检。
          </p>
        ) : (
          <div className="space-y-2">
            {stockRows.map((row) => (
              <div key={row.bucketId} className="rounded-md border p-3 text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge>
                    {row.skuCode} · {row.skuName}
                  </Badge>
                  <Badge>
                    {row.warehouseCode} / {row.locationCode} · {locationTypeLabel(row.locationType)}
                  </Badge>
                  {row.lotCode && <Badge>批次 {row.lotCode}</Badge>}
                  {row.hasOpenTask && <Badge>已建单</Badge>}
                </div>
                <p className="text-muted-foreground mt-1 text-xs">
                  在手 {formatQuantity(row.onHand)} · 可用 {formatQuantity(row.available)} ·
                  质量状态 {row.qualityStatusName}（{qualityRoleLabel('PENDING_INSPECTION')}）
                  {row.locationType !== 'QUARANTINE' ? ' · ⚠️ 这批待检货不在待检库位上' : ''}
                </p>
              </div>
            ))}
          </div>
        )}
        {stock?.note && <p className="text-muted-foreground mt-2 text-xs">{stock.note}</p>}
      </SectionCard>

      <SectionCard title="筛选">
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关键字</span>
            <Input
              value={keyword}
              placeholder="处理单号 / 收货单号 / 商品编码"
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
              <option value="PENDING">待发起质检</option>
              <option value="INSPECTION_REQUESTED">已发起质检</option>
              <option value="RESULT_RECORDED">已录入结果</option>
              <option value="DISPOSED">已处置</option>
              <option value="CANCELLED">已取消</option>
            </select>
          </label>
          <Button onClick={() => setParams({ keyword, status, page: 0, size: 20 })}>查询</Button>
        </div>
      </SectionCard>

      {error && (
        <div className="flex items-start gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p>{error}</p>
        </div>
      )}

      {loading && <p className="text-muted-foreground text-sm">正在加载待检处理单…</p>}

      {!loading && tasks.length === 0 && !error && (
        <EmptyState
          title="还没有待检处理单"
          description="从上面的待检库存清单里挑一批货建单，建单后发起质检任务（6 质检单），质检结论回来后做处置决定。"
        />
      )}

      {tasks.length > 0 && (
        <SectionCard title="待检处理单">
          <div className="space-y-2">
            {tasks.map((row) => {
              const q = inspectionQuantities(row);
              return (
                <button
                  key={row.id}
                  type="button"
                  onClick={() => void openDetail(row.id)}
                  className="hover:bg-muted/40 w-full rounded-md border p-3 text-left text-sm"
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge>{row.taskCode}</Badge>
                    <Badge tone={pendingInspectionStatusTone(row.status)}>
                      {pendingInspectionStatusLabel(row.status)}
                    </Badge>
                    <span>
                      {row.skuCode} · {row.skuName}
                    </span>
                    <span className="text-muted-foreground">
                      {row.warehouseCode} · 来源收货单 {row.receiptCode}
                    </span>
                    {row.inspectionOrderCode && <Badge>质检单 {row.inspectionOrderCode}</Badge>}
                  </div>
                  <p className="text-muted-foreground mt-1 text-xs">
                    批量 {formatQuantity(row.quantity)} · 可售 {formatQuantity(q.sellable)} · 不可售{' '}
                    {formatQuantity(q.unqualified)}
                    {row.dispositionSummary
                      ? ` · 去向 ${dispositionLabel(row.dispositionSummary)}`
                      : ''}
                  </p>
                </button>
              );
            })}
          </div>
          {data?.note && <p className="text-muted-foreground mt-2 text-xs">{data.note}</p>}
        </SectionCard>
      )}

      {detail && (
        <SectionCard title={`待检处理单 ${detail.task.taskCode}`}>
          <div className="flex flex-wrap items-center gap-2">
            <Badge tone={pendingInspectionStatusTone(detail.task.status)}>
              {pendingInspectionStatusLabel(detail.task.status)}
            </Badge>
            <span>
              {detail.task.skuCode} · {detail.task.skuName}
            </span>
            <span className="text-muted-foreground">
              {detail.task.warehouseCode} / {detail.sourceLocationCode} ·{' '}
              {locationTypeLabel(detail.sourceLocationType)}
            </span>
            {detail.lotCode && <Badge>批次 {detail.lotCode}</Badge>}
          </div>
          <p className="text-muted-foreground mt-1 text-xs">
            批量 {formatQuantity(detail.task.quantity)} · 待检位在手{' '}
            {formatQuantity(detail.sourceOnHand)} · 来源收货单 {detail.task.receiptCode}
          </p>

          {/* 质检单：三量从 6 质检单回读，页面不让人再敲一遍 */}
          <div className="mt-3 rounded-md border p-3 text-sm">
            <p className="font-medium">质检结论（来自 6.4.2.1 质检单，数字不在这里输入）</p>
            {detail.inspection.inspectionOrderCode ? (
              <>
                <p className="text-muted-foreground mt-1 text-xs">
                  质检单 {detail.inspection.inspectionOrderCode}
                  {detail.inspection.inspectionTemplateCode
                    ? ` · 模板 ${detail.inspection.inspectionTemplateCode}`
                    : ''}
                </p>
                <div className="mt-2 grid gap-3 sm:grid-cols-4">
                  <Reading
                    label="合格量"
                    value={formatQuantity(detail.task.qualifiedQuantity)}
                    hint="合格 + 让步 = 可售量"
                  />
                  <Reading
                    label="不合格量"
                    value={formatQuantity(detail.task.unqualifiedQuantity)}
                    hint="只能转残次 / 报废 / 退货"
                  />
                  <Reading
                    label="让步接收量"
                    value={formatQuantity(detail.task.concessionQuantity)}
                    hint="不合格里被批准放行的部分，仍算可售"
                  />
                  <Reading
                    label="可售量"
                    value={formatQuantity(
                      (detail.task.qualifiedQuantity ?? 0) + (detail.task.concessionQuantity ?? 0),
                    )}
                    hint="转可售的上限"
                  />
                </div>
              </>
            ) : (
              <p className="text-muted-foreground mt-1 text-xs">
                还没发起质检任务。发起后质检员在 6 质检单上录结论，结论出来后再回读。
              </p>
            )}
          </div>

          {/* 可售量前后对照：把「输出：可售量更新」摆在明处 */}
          {detail.task.status === 'DISPOSED' && (
            <div className="mt-3 grid gap-3 sm:grid-cols-3">
              <Reading
                label="处置前可售量"
                value={formatQuantity(detail.sellableBefore)}
                hint="同一口径：可售质量状态 + 可拣启用的库位"
              />
              <Reading
                label="处置后可售量"
                value={formatQuantity(detail.sellableAfter)}
                hint="差值就是这次质检放行的货"
              />
              <Reading
                label="分录净额"
                value={netOnHandDeltaText(detail.netOnHandDelta)}
                hint="0 = 只是换了质量状态与库位，货还在本仓"
              />
            </div>
          )}

          {detail.dispositions.length > 0 && (
            <div className="mt-3">
              <p className="text-sm font-medium">处置去向</p>
              <div className="mt-1 space-y-1 text-sm">
                {detail.dispositions.map((d) => (
                  <p key={d.id} className="text-muted-foreground">
                    {dispositionLabel(d.dispositionType)} {formatQuantity(d.quantity)}
                    {d.targetLocationCode
                      ? ` → ${d.targetLocationCode}（${locationTypeLabel(d.targetLocationType ?? '')}）`
                      : ''}
                    {d.targetQualityStatusName ? ` · ${d.targetQualityStatusName}` : ''}
                    {d.note ? ` · ${d.note}` : ''}
                  </p>
                ))}
              </div>
            </div>
          )}

          {detail.entries.length > 0 && (
            <div className="mt-3">
              <p className="text-sm font-medium">质量状态变更分录</p>
              <div className="mt-1 space-y-1 text-sm">
                {detail.entries.map((e) => (
                  <p key={e.id} className="text-muted-foreground">
                    {e.qualityStatusName}（{qualityRoleLabel(e.qualityRole)}）{' '}
                    {formatSignedQuantity(e.onHandDelta)}
                  </p>
                ))}
              </div>
            </div>
          )}

          <div className="mt-3">
            <p className="text-sm font-medium">事件台账</p>
            <div className="mt-1 space-y-1 text-sm">
              {detail.events.map((e) => (
                <p key={e.id} className="text-muted-foreground">
                  {e.eventTypeLabel} · {e.occurredAt} {e.note}
                </p>
              ))}
            </div>
          </div>

          <div className="mt-4 flex flex-wrap gap-2">
            <Button
              variant="secondary"
              disabled={busy || !canRequestInspection(detail.task.status)}
              onClick={() =>
                void run(
                  () =>
                    requestPendingInspection(
                      detail.task.id,
                      options?.inspectionTemplates[0]?.id ?? '',
                    ),
                  detail.task.id,
                )
              }
            >
              <ClipboardCheck />
              发起质检任务
            </Button>
            <Button
              variant="secondary"
              disabled={busy || !canRecordResult(detail.task.status)}
              onClick={() =>
                void run(() => recordPendingInspectionResult(detail.task.id), detail.task.id)
              }
            >
              录入质检结果
            </Button>
            <Button
              disabled={busy || !canDispose(detail.task.status)}
              onClick={() => setDisposing(true)}
            >
              <PackageCheck />
              处置
            </Button>
            <Button
              variant="ghost"
              disabled={busy || !canCancel(detail.task.status)}
              onClick={() =>
                void run(
                  () => cancelPendingInspectionTask(detail.task.id, '现场取消'),
                  detail.task.id,
                )
              }
            >
              取消
            </Button>
          </div>
          {detail.task.status === 'PENDING' && (
            <p className="text-muted-foreground mt-2 text-xs">
              发起质检需要一个 6.4.2.1 质检模板
              {options?.inspectionTemplatesAvailable === false
                ? `（当前读不到：${options.inspectionTemplatesNote ?? '质检服务不可达'}）`
                : ''}
              。模板决定「检什么、抽多少」，仓库这边不替它选。
            </p>
          )}
        </SectionCard>
      )}

      {creating && options && (
        <CreateTaskModal
          options={options}
          stock={stock}
          onClose={() => setCreating(false)}
          onCreated={(id) => {
            setCreating(false);
            void run(() => Promise.resolve(), id);
          }}
        />
      )}

      {disposing && detail && options && (
        <DisposeModal
          detail={detail}
          options={options}
          onClose={() => setDisposing(false)}
          onDisposed={() => {
            setDisposing(false);
            void run(() => Promise.resolve(), detail.task.id);
          }}
        />
      )}
    </div>
  );
}

/** 一个读数（标签 + 数值 + 口径说明）。 */
function Reading({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <div className="rounded-md border p-2">
      <p className="text-muted-foreground text-xs">{label}</p>
      <p className="text-lg font-semibold">{value}</p>
      <p className="text-muted-foreground text-xs">{hint}</p>
    </div>
  );
}

/** 建单：挑一行待检库存 + 填批量（质量状态不在这里选 —— 它从库存行读出来）。 */
function CreateTaskModal({
  options,
  stock,
  onClose,
  onCreated,
}: {
  options: PendingInspectionOptions;
  stock: PendingInspectionStockPage | null;
  onClose: () => void;
  onCreated: (taskId: string) => void;
}) {
  const rows = (stock?.rows ?? []).filter((row) => !row.hasOpenTask);
  const [bucketId, setBucketId] = useState(rows[0]?.bucketId ?? '');
  const [receiptLineId, setReceiptLineId] = useState('');
  const [quantity, setQuantity] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const selected = rows.find((row) => row.bucketId === bucketId);
  const check = useMemo(
    () => validateCreateQuantity(Number(quantity), Number(selected?.onHand ?? 0)),
    [quantity, selected],
  );

  return (
    <Modal open onOpenChange={(open) => (open ? undefined : onClose())}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>新建待检处理单</ModalTitle>
          <ModalDescription>
            质量状态不在这里选 —— 它从库存行读出来（只有 role=PENDING_INSPECTION
            的库存才能进待检处理）。 收货明细行必填：质检单的到货量来自那张收货单。
          </ModalDescription>
        </ModalHeader>
        <div className="space-y-3 text-sm">
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">待检库存行</span>
            <select
              className="h-9 rounded-md border px-2"
              value={bucketId}
              onChange={(e) => setBucketId(e.target.value)}
            >
              {rows.length === 0 && <option value="">没有可建单的待检库存</option>}
              {rows.map((row) => (
                <option key={row.bucketId} value={row.bucketId}>
                  {row.skuCode} · {row.warehouseCode}/{row.locationCode} · 在手{' '}
                  {formatQuantity(row.onHand)}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">收货明细行 ID（goods_receipt_line.id）</span>
            <Input
              value={receiptLineId}
              placeholder="这批货是哪次实收的哪一行"
              onChange={(e) => setReceiptLineId(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-muted-foreground">本次待检批量</span>
            <Input
              value={quantity}
              inputMode="decimal"
              onChange={(e) => setQuantity(e.target.value)}
            />
            {quantity !== '' && !check.ok && (
              <span className="text-xs text-red-600">{check.message}</span>
            )}
          </label>
          {error && <p className="text-xs text-red-600">{error}</p>}
        </div>
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={busy || !bucketId || !receiptLineId.trim() || !check.ok}
            onClick={() => {
              setBusy(true);
              setError(null);
              void (async () => {
                try {
                  const created = await createPendingInspectionTask({
                    sourceBucketId: bucketId,
                    receiptLineId: receiptLineId.trim(),
                    quantity: Number(quantity),
                    notes: null,
                  });
                  onCreated(created.id);
                } catch (e) {
                  setError(errorText(e));
                } finally {
                  setBusy(false);
                }
              })();
            }}
          >
            建单
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

/** 处置：四个去向 + 目标库位；两条本地校验（全部处置掉 / 不合格品不能转可售）。 */
function DisposeModal({
  detail,
  options,
  onClose,
  onDisposed,
}: {
  detail: PendingInspectionTaskDetail;
  options: PendingInspectionOptions;
  onClose: () => void;
  onDisposed: () => void;
}) {
  const sellableLimit =
    (detail.task.qualifiedQuantity ?? 0) + (detail.task.concessionQuantity ?? 0);
  const [lines, setLines] = useState<
    {
      dispositionType: string;
      quantity: string;
      targetQualityStatusId: string;
      targetLocationId: string;
    }[]
  >([
    { dispositionType: 'SELLABLE', quantity: '', targetQualityStatusId: '', targetLocationId: '' },
  ]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const numeric = lines.map((line) => ({
    dispositionType: line.dispositionType,
    quantity: Number(line.quantity || 0),
  }));
  const totals = validateDispositionTotals(numeric, Number(detail.task.quantity));
  const sellable = validateSellableLimit(numeric, sellableLimit);
  const sellableStatuses = options.qualityStatuses.filter((q) => q.role === 'SELLABLE');
  const defectiveStatuses = options.qualityStatuses.filter((q) => q.role === 'DEFECTIVE');

  const update = (index: number, patch: Partial<(typeof lines)[number]>) => {
    setLines((prev) => prev.map((line, i) => (i === index ? { ...line, ...patch } : line)));
  };

  return (
    <Modal open onOpenChange={(open) => (open ? undefined : onClose())}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>处置决定</ModalTitle>
          <ModalDescription>
            各去向数量之和必须等于任务批量 {formatQuantity(detail.task.quantity)}
            ；转可售的量不得超过质检结论里的可售量 {formatQuantity(sellableLimit)}。
            报废与退货是离仓的货，不需要目标库位。
          </ModalDescription>
        </ModalHeader>
        <div className="space-y-3 text-sm">
          {lines.map((line, index) => (
            <div key={index} className="rounded-md border p-2">
              <div className="flex flex-wrap items-end gap-2">
                <label className="flex flex-col gap-1">
                  <span className="text-muted-foreground">去向</span>
                  <select
                    className="h-9 rounded-md border px-2"
                    value={line.dispositionType}
                    onChange={(e) => update(index, { dispositionType: e.target.value })}
                  >
                    {dispositionTypes.map((type) => (
                      <option key={type} value={type}>
                        {dispositionLabel(type)}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="flex flex-col gap-1">
                  <span className="text-muted-foreground">数量</span>
                  <Input
                    value={line.quantity}
                    inputMode="decimal"
                    onChange={(e) => update(index, { quantity: e.target.value })}
                    className="w-28"
                  />
                </label>
                {dispositionStaysInWarehouse(line.dispositionType) && (
                  <>
                    <label className="flex flex-col gap-1">
                      <span className="text-muted-foreground">目标质量状态</span>
                      <select
                        className="h-9 rounded-md border px-2"
                        value={line.targetQualityStatusId}
                        onChange={(e) => update(index, { targetQualityStatusId: e.target.value })}
                      >
                        <option value="">请选择</option>
                        {(line.dispositionType === 'SELLABLE'
                          ? sellableStatuses
                          : defectiveStatuses
                        ).map((q) => (
                          <option key={q.id} value={q.id}>
                            {q.name}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label className="flex flex-col gap-1">
                      <span className="text-muted-foreground">目标库位</span>
                      <select
                        className="h-9 rounded-md border px-2"
                        value={line.targetLocationId}
                        onChange={(e) => update(index, { targetLocationId: e.target.value })}
                      >
                        <option value="">请选择</option>
                        {options.locations
                          .filter(
                            (loc) =>
                              loc.type ===
                              (line.dispositionType === 'SELLABLE' ? 'STORAGE' : 'DEFECTIVE'),
                          )
                          .map((loc) => (
                            <option key={loc.id} value={loc.id}>
                              {loc.warehouseCode}/{loc.code} · {locationTypeLabel(loc.type)}
                            </option>
                          ))}
                      </select>
                    </label>
                  </>
                )}
                {lines.length > 1 && (
                  <Button
                    variant="ghost"
                    onClick={() => setLines((prev) => prev.filter((_, i) => i !== index))}
                  >
                    去掉这一行
                  </Button>
                )}
              </div>
            </div>
          ))}
          <Button
            variant="secondary"
            onClick={() =>
              setLines((prev) => [
                ...prev,
                {
                  dispositionType: 'DEFECTIVE',
                  quantity: '',
                  targetQualityStatusId: '',
                  targetLocationId: '',
                },
              ])
            }
          >
            再加一个去向
          </Button>
          <p className={totals.ok ? 'text-xs' : 'text-xs text-red-600'}>{totals.message}</p>
          <p className={sellable.ok ? 'text-xs' : 'text-xs text-red-600'}>{sellable.message}</p>
          {error && <p className="text-xs text-red-600">{error}</p>}
        </div>
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={
              busy ||
              !totals.ok ||
              !sellable.ok ||
              lines.some(
                (line) =>
                  Number(line.quantity || 0) <= 0 ||
                  (dispositionStaysInWarehouse(line.dispositionType) &&
                    (!line.targetQualityStatusId || !line.targetLocationId)),
              )
            }
            onClick={() => {
              setBusy(true);
              setError(null);
              void (async () => {
                try {
                  await disposePendingInspectionTask(detail.task.id, {
                    expectedVersion: detail.task.version,
                    lines: lines.map((line) => ({
                      dispositionType: line.dispositionType,
                      quantity: Number(line.quantity),
                      targetQualityStatusId: line.targetQualityStatusId || null,
                      targetLocationId: line.targetLocationId || null,
                      note: null,
                    })),
                    notes: null,
                  });
                  onDisposed();
                } catch (e) {
                  setError(errorText(e));
                } finally {
                  setBusy(false);
                }
              })();
            }}
          >
            确认处置
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
