'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { ArrowRightLeft, RefreshCw, ScanLine, TriangleAlert } from 'lucide-react';
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
  PutawayCapabilities,
  PutawayTaskCreateInput,
  PutawayTaskDetail,
  PutawayTaskPage,
  PutawayTaskScanInput,
} from '../../../../../packages/api-contracts/src/index';
import {
  allLinesFinished,
  assignPutawayTask,
  cancelPutawayTask,
  canCancel,
  canComplete,
  canScan,
  canStart,
  completePutawayTask,
  createPutawayTask,
  deviceTypeLabel,
  entryDirectionLabel,
  fetchPutawayCapabilities,
  fetchPutawayTask,
  fetchPutawayTasks,
  formatQuantity,
  locationTypeLabel,
  netIsBalanced,
  putawaySortKeys,
  putawaySortLabels,
  putawayStatusLabel,
  putawayStatusLabels,
  putawayStatusTone,
  qualityRoleLabel,
  qualityRoleTone,
  scanPutawayTask,
  startPutawayTask,
  validateScanQuantity,
  type PutawayTaskParams,
} from './putaway-task-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 8.2.2.1 上架任务工作区（收货暂存位 → 正式库位）。
 *
 * <p>蓝图 08 卷：「把已收货物从收货暂存位上架到正式库位，生成上架任务派发给作业人员
 * （支持 PDA/扫码）。」输出「上架任务（待执行/执行中/完成）；库存 bucket 位置变更。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>建单表单里没有「数量」输入框</b> —— 这是蓝图那句「上架只改位置不改数量，
 *       数量在收货时已确定」最直接的界面形态。给操作员一个数量输入框，
 *       等于邀请他改一个收货时就已经定死的事实。要上架哪一行、要搬到哪由人指。</li>
 *   <li><b>应上架量 / 实上架量并排显示</b>，差值直接标出来；「完成上架」在没搬完时禁用，
 *       并说清差几件 —— 省掉一次注定被后端 P2 拒掉的往返。</li>
 *   <li><b>账上净额常驻</b>，而且页面明说「净额为 0 不代表任务做完了」：
 *       一次扫码写的是一对反向分录，成对的两条加起来恒为 0，
 *       所以它抓的是「有人塞了不成对的分录」，不是「做完没做完」。</li>
 *   <li><b>扫码可重复</b>，每次填「本次搬多少」；超量、超精度在提交前本地挡下。</li>
 *   <li><b>取消按钮在已扫码时禁用并写明原因</b>，而不是给一个点下去必然 409 的按钮。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */
export function PutawayTaskWorkspace() {
  const [params, setParams] = useState<PutawayTaskParams>({ page: 0, size: 20 });
  const [data, setData] = useState<PutawayTaskPage | null>(null);
  const [caps, setCaps] = useState<PutawayCapabilities | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [sort, setSort] = useState('CREATED_AT');
  const [detail, setDetail] = useState<PutawayTaskDetail | null>(null);
  const [creating, setCreating] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchPutawayTasks(params));
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
        setCaps(await fetchPutawayCapabilities());
      } catch {
        // capabilities 拿不到不该让整页空掉：列表仍然可用，只是边界说明退化。
        // 页面不假装自己知道规则。
        setCaps(null);
      }
    })();
  }, []);

  const openDetail = useCallback(async (id: string) => {
    try {
      setDetail(await fetchPutawayTask(id));
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
          setDetail(await fetchPutawayTask(refreshId));
        }
      } catch (e) {
        setError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const tasks = data?.items ?? [];
  const lines = detail?.lines ?? [];
  const finished = useMemo(() => allLinesFinished(lines), [lines]);

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="上架任务"
        description="把已收货物从收货暂存位上架到正式库位，派发给作业人员，支持 PDA 扫码。"
        actions={
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
            <Button onClick={() => setCreating(true)} disabled={busy}>
              <ArrowRightLeft />
              新建上架任务
            </Button>
          </div>
        }
      />

      {/* 模块边界常驻：只改位置不改数量 —— 这不是描述，是不能绕过的约束 */}
      <div className="flex items-start gap-2 rounded-md border border-violet-500/40 bg-violet-500/10 p-3 text-sm">
        <ArrowRightLeft className="mt-0.5 size-4 shrink-0" />
        <div className="space-y-1">
          <p className="font-medium">上架只改位置不改数量，数量在收货时已确定</p>
          <p className="text-muted-foreground">
            {caps?.quantityBoundary ??
              '建单时不填数量：要上架哪一行、要搬到哪由人指，搬多少取自那条收货明细行的实收量并在建单时冻结。一次扫码在同一余额行上写一对反向分录（源位 -Q、目标位 +Q），所以账上净额恒为 0。'}
          </p>
          {caps && <p className="text-muted-foreground">{caps.allocationBoundary}</p>}
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
          <p className="text-muted-foreground mt-2 text-xs">{caps.note}</p>
        </SectionCard>
      )}

      {data && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="上架任务" value={data.total} />
          <StatCard label="待执行" value={data.summary.pendingCount} />
          <StatCard label="执行中" value={data.summary.inProgressCount} />
          <StatCard
            label="未派工"
            value={data.summary.unassignedCount}
            comparisonLabel={`已完成 ${data.summary.completedCount} · 已取消 ${data.summary.cancelledCount}`}
          />
        </div>
      )}

      {data && data.total > 0 && (
        <SectionCard title="不变量读数">
          <dl className="grid gap-3 sm:grid-cols-3">
            <Reading
              label="应上架合计"
              value={formatQuantity(data.summary.plannedQuantity)}
              hint="取自收货明细行的实收量，建单时冻结"
            />
            <Reading
              label="实上架合计"
              value={formatQuantity(data.summary.putawayQuantity)}
              hint="完成时必须等于应上架合计（P2）"
            />
            <Reading
              label="差额"
              value={formatQuantity(
                Number(data.summary.plannedQuantity) - Number(data.summary.putawayQuantity),
              )}
              hint="差额不为 0 说明还有货没搬完"
            />
          </dl>
        </SectionCard>
      )}

      {/* 目标库位分布常驻：看不到「正在往哪些库位搬」，库位利用率就无从预判 */}
      {data && data.targetStats.length > 0 && (
        <SectionCard title="目标库位分布">
          <div className="flex flex-wrap gap-2">
            {data.targetStats.map((t) => (
              <div key={t.locationId} className="rounded-md border p-3">
                <Badge>
                  {t.locationCode} · {locationTypeLabel(t.locationType)}
                </Badge>
                <p className="mt-2 text-lg font-semibold">{formatQuantity(t.plannedQuantity)}</p>
                <p className="text-muted-foreground text-xs">
                  应上架 {formatQuantity(t.plannedQuantity)} · 已搬{' '}
                  {formatQuantity(t.putawayQuantity)} · {t.lineCount} 行
                </p>
              </div>
            ))}
          </div>
          <p className="text-muted-foreground mt-2 text-xs">
            目标库位的类型由质量状态决定（可售 → 存储 / 待检 → 待检 / 残次 → 残次），
            残次品进非残次库位会被数据库直接拒绝，不允许混放。
          </p>
        </SectionCard>
      )}

      <SectionCard title="筛选">
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关键字</span>
            <Input
              value={keyword}
              placeholder="任务单号 / 收货单号"
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
              {Object.entries(putawayStatusLabels).map(([value, label]) => (
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
              {putawaySortKeys.map((key) => (
                <option key={key} value={key}>
                  {putawaySortLabels[key]}
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

      {loading && <p className="text-muted-foreground text-sm">正在加载上架任务…</p>}

      {!loading && tasks.length === 0 && !error && (
        <EmptyState
          title="还没有上架任务"
          description="新建上架任务时选择收货明细行与目标库位，数量不用填 —— 它取自收货时的实收量。派工后作业员用 PDA 扫码逐行搬货。"
        />
      )}

      {!loading && tasks.length > 0 && (
        <SectionCard title={`上架任务（${data?.total ?? 0}）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">任务单号</th>
                  <th className="px-2 py-1">状态</th>
                  <th className="px-2 py-1">收货单</th>
                  <th className="px-2 py-1">明细</th>
                  <th className="px-2 py-1">应上架</th>
                  <th className="px-2 py-1">实上架</th>
                  <th className="px-2 py-1">扫码</th>
                  <th className="px-2 py-1">派工</th>
                  <th className="px-2 py-1">操作</th>
                </tr>
              </thead>
              <tbody>
                {tasks.map((t) => (
                  <tr key={t.taskId} className="border-t">
                    <td className="px-2 py-1 font-mono">{t.taskCode}</td>
                    <td className="px-2 py-1">
                      <Badge className={putawayStatusTone(t.status)}>
                        {putawayStatusLabel(t.status)}
                      </Badge>
                    </td>
                    <td className="px-2 py-1">{t.receiptCode}</td>
                    <td className="px-2 py-1">{t.detailLineCount}</td>
                    <td className="px-2 py-1">{formatQuantity(t.plannedQuantity)}</td>
                    <td className="px-2 py-1">
                      {formatQuantity(t.putawayQuantity)}
                      {Number(t.putawayQuantity) !== Number(t.plannedQuantity) && (
                        <span className="text-muted-foreground ml-1 text-xs">
                          （差 {formatQuantity(Number(t.plannedQuantity) - Number(t.putawayQuantity))}）
                        </span>
                      )}
                    </td>
                    <td className="px-2 py-1">{t.scanCount}</td>
                    <td className="px-2 py-1 text-xs">{t.assigneeId ? '已派工' : '未派工'}</td>
                    <td className="px-2 py-1">
                      <Button variant="ghost" onClick={() => void openDetail(t.taskId)}>
                        详情
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </SectionCard>
      )}

      {creating && (
        <CreateModal
          busy={busy}
          onClose={() => setCreating(false)}
          onCreate={async (input) => {
            await run(async () => {
              const created = await createPutawayTask(input);
              await openDetail(created.taskId);
            });
            setCreating(false);
          }}
        />
      )}

      {detail && (
        <DetailModal
          detail={detail}
          busy={busy}
          finished={finished}
          onClose={() => setDetail(null)}
          onAssign={(assigneeId) =>
            void run(async () => {
              await assignPutawayTask(detail.task.taskId, { assigneeId, note: null });
            }, detail.task.taskId)
          }
          onStart={() =>
            void run(
              () => startPutawayTask(detail.task.taskId),
              detail.task.taskId,
            )
          }
          onScan={(input) =>
            void run(
              () => scanPutawayTask(detail.task.taskId, input),
              detail.task.taskId,
            )
          }
          onComplete={() =>
            void run(
              () => completePutawayTask(detail.task.taskId),
              detail.task.taskId,
            )
          }
          onCancel={(reason) =>
            void run(
              () => cancelPutawayTask(detail.task.taskId, { reason }),
              detail.task.taskId,
            )
          }
        />
      )}
    </div>
  );
}

function Reading({
  label,
  value,
  hint,
}: {
  label: string;
  value: string;
  hint?: string;
}) {
  return (
    <div className="rounded-md border p-3">
      <dt className="text-muted-foreground text-xs">{label}</dt>
      <dd className="mt-1 text-lg font-semibold">{value}</dd>
      {hint && <p className="text-muted-foreground mt-1 text-xs">{hint}</p>}
    </div>
  );
}

function InvariantCard({ caps }: { caps: PutawayCapabilities }) {
  return (
    <SectionCard title="本模块的三条硬约束">
      <p className="text-sm">{caps.invariant}</p>
      <p className="text-muted-foreground mt-2 text-xs">{caps.note}</p>
    </SectionCard>
  );
}

function CreateModal({
  busy,
  onClose,
  onCreate,
}: {
  busy: boolean;
  onClose: () => void;
  onCreate: (input: PutawayTaskCreateInput) => Promise<void>;
}) {
  const [receiptId, setReceiptId] = useState('');
  const [receiptLineId, setReceiptLineId] = useState('');
  const [targetLocationId, setTargetLocationId] = useState('');
  const [notes, setNotes] = useState('');
  const [touched, setTouched] = useState(false);

  const missing = !receiptId.trim() || !receiptLineId.trim() || !targetLocationId.trim();

  return (
    <Modal open onOpenChange={() => (busy ? undefined : onClose())}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>新建上架任务</ModalTitle>
          <ModalDescription>
            指明「哪一条收货明细行」搬到「哪个库位」。**数量不用填** —— 它取自收货明细行的实收量，
            并在建单时冻结；收货方事后改单不会让这张作业单变形。
          </ModalDescription>
        </ModalHeader>

        <div className="flex flex-col gap-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">收货单 ID</span>
            <Input
              value={receiptId}
              placeholder="8.2.1.1 收货单的 ID"
              onChange={(e) => setReceiptId(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">收货明细行 ID</span>
            <Input
              value={receiptLineId}
              placeholder="该收货单下要上架的那一行"
              onChange={(e) => setReceiptLineId(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">目标库位 ID</span>
            <Input
              value={targetLocationId}
              placeholder="正式库位；类型由质量状态决定"
              onChange={(e) => setTargetLocationId(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">备注</span>
            <Input value={notes} onChange={(e) => setNotes(e.target.value)} />
          </label>

          <div className="rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
            <p className="font-medium">这里刻意没有「数量」输入框</p>
            <p className="text-muted-foreground mt-1">
              蓝图 8.2.2.1 原话是「上架只改位置不改数量，数量在收货时已确定」。
              收货时定错数量的出口是<b>收货差异（8.2.1.2）</b>，不是在上架单上悄悄改数 ——
              给一个数字输入框就等于邀请操作员去改一个已经定死的事实。
            </p>
          </div>

          {touched && missing && (
            <p className="text-sm text-red-600">收货单、收货明细行、目标库位三项都必须填。</p>
          )}
        </div>

        <ModalFooter>
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            取消
          </Button>
          <Button
            disabled={busy}
            onClick={() => {
              setTouched(true);
              if (missing) return;
              void onCreate({
                receiptId: receiptId.trim(),
                allocationId: null,
                lines: [
                  {
                    receiptLineId: receiptLineId.trim(),
                    targetLocationId: targetLocationId.trim(),
                    lotId: null,
                    requiredCapacity: null,
                    requiredWeight: null,
                    note: null,
                  },
                ],
                notes: notes.trim() || null,
              });
            }}
          >
            建单（不动库存）
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

function DetailModal({
  detail,
  busy,
  finished,
  onClose,
  onAssign,
  onStart,
  onScan,
  onComplete,
  onCancel,
}: {
  detail: PutawayTaskDetail;
  busy: boolean;
  finished: { ok: boolean; unfinished: number; shortfall: number };
  onClose: () => void;
  onAssign: (assigneeId: string) => void;
  onStart: () => void;
  onScan: (input: PutawayTaskScanInput) => void;
  onComplete: () => void;
  onCancel: (reason: string) => void;
}) {
  const { task, lines, scans, entries } = detail;
  const [assigneeId, setAssigneeId] = useState('');
  const [scanLineId, setScanLineId] = useState('');
  const [scanQty, setScanQty] = useState('');
  const [scanRef, setScanRef] = useState('');
  const [cancelReason, setCancelReason] = useState('');
  const [touched, setTouched] = useState(false);

  const scanLine = lines.find((l) => l.lineId === scanLineId) ?? lines[0];
  const qtyCheck = scanLine
    ? validateScanQuantity(scanQty, Number(scanLine.putawayQuantity), Number(scanLine.plannedQuantity))
    : { ok: false, reason: '没有可扫码的明细行' };
  const balanced = netIsBalanced(detail.netOnHandDelta);

  return (
    <Modal open onOpenChange={() => (busy ? undefined : onClose())}>
      <ModalContent className="max-w-4xl">
        <ModalHeader>
          <ModalTitle>
            {task.taskCode} · {putawayStatusLabel(task.status)}
          </ModalTitle>
          <ModalDescription>
            收货单 {task.receiptCode} · {task.detailLineCount} 行明细 · 扫码 {task.scanCount} 次 ·
            建单于 {task.createdAt}
          </ModalDescription>
        </ModalHeader>

        <div className="flex flex-col gap-4">
          {/* 只改位置不改数量：两量并排 + 差额直接摆在最上面 */}
          <div className="grid gap-3 sm:grid-cols-3">
            <Reading label="应上架合计" value={formatQuantity(task.plannedQuantity)} hint="取自收货实收量，建单时冻结" />
            <Reading label="实上架合计" value={formatQuantity(task.putawayQuantity)} hint="逐行扫码累加" />
            <Reading
              label="差额"
              value={formatQuantity(Number(task.plannedQuantity) - Number(task.putawayQuantity))}
              hint="完成时必须为 0（P2）"
            />
          </div>

          <div
            className={
              balanced
                ? 'rounded-md border border-emerald-500/40 bg-emerald-500/10 p-3 text-sm'
                : 'rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600'
            }
          >
            <p className="font-medium">账上净额 {formatQuantity(detail.netOnHandDelta)}</p>
            <p className="text-muted-foreground mt-1">{detail.note}</p>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">SKU</th>
                  <th className="px-2 py-1">质量</th>
                  <th className="px-2 py-1">源位 → 目标位</th>
                  <th className="px-2 py-1">应上架</th>
                  <th className="px-2 py-1">实上架</th>
                  <th className="px-2 py-1">还差</th>
                </tr>
              </thead>
              <tbody>
                {lines.map((l) => (
                  <tr key={l.lineId} className="border-t">
                    <td className="px-2 py-1 font-mono">{l.skuCode}</td>
                    <td className="px-2 py-1">
                      <Badge className={qualityRoleTone(l.qualityRole)}>
                        {qualityRoleLabel(l.qualityRole)}
                      </Badge>
                    </td>
                    <td className="px-2 py-1">
                      {l.sourceLocationCode} → {l.targetLocationCode}
                      <span className="text-muted-foreground ml-1 text-xs">
                        {locationTypeLabel(l.targetLocationType)}
                      </span>
                    </td>
                    <td className="px-2 py-1">{formatQuantity(l.plannedQuantity)}</td>
                    <td className="px-2 py-1">{formatQuantity(l.putawayQuantity)}</td>
                    <td className="px-2 py-1">
                      {l.completed ? (
                        <span className="text-xs">已搬完</span>
                      ) : (
                        formatQuantity(Number(l.plannedQuantity) - Number(l.putawayQuantity))
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {scans.length > 0 && (
            <SectionCard title={`扫码台账（${scans.length}）`}>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left">
                    <tr>
                      <th className="px-2 py-1">时刻</th>
                      <th className="px-2 py-1">设备</th>
                      <th className="px-2 py-1">扫描码</th>
                      <th className="px-2 py-1">本次搬动</th>
                    </tr>
                  </thead>
                  <tbody>
                    {scans.map((s) => (
                      <tr key={s.scanId} className="border-t">
                        <td className="px-2 py-1">{s.scannedAt}</td>
                        <td className="px-2 py-1">{deviceTypeLabel(s.deviceType)}</td>
                        <td className="px-2 py-1 font-mono text-xs">
                          {s.scanReference || '—'}
                        </td>
                        <td className="px-2 py-1">{formatQuantity(s.scannedQuantity)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </SectionCard>
          )}

          {entries.length > 0 && (
            <SectionCard title={`账本分录（${entries.length}）`}>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left">
                    <tr>
                      <th className="px-2 py-1">方向</th>
                      <th className="px-2 py-1">库位</th>
                      <th className="px-2 py-1">在手变动</th>
                    </tr>
                  </thead>
                  <tbody>
                    {entries.map((e) => (
                      <tr key={e.entryId} className="border-t">
                        <td className="px-2 py-1">{entryDirectionLabel(e.direction)}</td>
                        <td className="px-2 py-1">{e.locationCode || '—'}</td>
                        <td className="px-2 py-1 font-mono">{formatQuantity(e.onHandDelta)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p className="text-muted-foreground mt-2 text-xs">
                分录成对出现（源位 -Q / 目标位 +Q），落在同一条余额行上 —— 所以净额恒为 0，
                变的只是库位。数量在收货时就已经定死了。
              </p>
            </SectionCard>
          )}

          {/* 派工 */}
          <div className="flex flex-wrap items-end gap-2">
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">派给（作业人员 ID）</span>
              <Input
                value={assigneeId}
                placeholder="作业人员 ID"
                className="w-64"
                onChange={(e) => setAssigneeId(e.target.value)}
              />
            </label>
            <Button
              variant="ghost"
              disabled={busy || !assigneeId.trim()}
              onClick={() => onAssign(assigneeId.trim())}
            >
              派工
            </Button>
          </div>

          {/* 扫码 */}
          {canScan(task) && scanLine && (
            <div className="rounded-md border p-3">
              <p className="flex items-center gap-2 text-sm font-medium">
                <ScanLine className="size-4" />
                PDA 扫码上架（本次唯一动库存的动作）
              </p>
              <div className="mt-3 flex flex-wrap items-end gap-2">
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">明细行</span>
                  <select
                    className="h-9 rounded-md border px-2"
                    value={scanLine.lineId}
                    onChange={(e) => setScanLineId(e.target.value)}
                  >
                    {lines.map((l) => (
                      <option key={l.lineId} value={l.lineId}>
                        {l.skuCode} → {l.targetLocationCode}（还差{' '}
                        {formatQuantity(Number(l.plannedQuantity) - Number(l.putawayQuantity))}）
                      </option>
                    ))}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">本次搬动量</span>
                  <Input
                    value={scanQty}
                    className="w-40"
                    placeholder="本次搬的量"
                    onChange={(e) => setScanQty(e.target.value)}
                  />
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">扫描码（可选，幂等用）</span>
                  <Input
                    value={scanRef}
                    className="w-48"
                    placeholder="库位条码 / SKU 条码"
                    onChange={(e) => setScanRef(e.target.value)}
                  />
                </label>
                <Button
                  disabled={busy || !qtyCheck.ok}
                  onClick={() => {
                    setTouched(true);
                    if (!qtyCheck.ok) return;
                    onScan({
                      lineId: scanLine.lineId,
                      quantity: Number(scanQty),
                      scanReference: scanRef.trim() || null,
                      deviceType: 'PDA',
                      scannedAt: null,
                      note: null,
                    });
                    setScanQty('');
                  }}
                >
                  确认上架
                </Button>
              </div>
              {touched && !qtyCheck.ok && (
                <p className="mt-2 text-sm text-red-600">{qtyCheck.reason}</p>
              )}
              <p className="text-muted-foreground mt-2 text-xs">
                一张单可以扫多次，每次填「本次搬的量」；累计不得超过应上架量。
                同一个扫描码重复提交会被幂等挡下 —— PDA 网络抖动重发是现场常态，
                没有这道，同一批货会被搬两遍<b>而「净额仍为 0」抓不到</b>。
              </p>
            </div>
          )}

          {/* 终态动作 */}
          <div className="flex flex-wrap items-end gap-2">
            <Button variant="ghost" disabled={busy || !canStart(task)} onClick={onStart}>
              开始作业
            </Button>
            <Button
              disabled={busy || !canComplete(task, lines)}
              onClick={onComplete}
              title={
                canComplete(task, lines)
                  ? undefined
                  : '只有执行中且每一行都搬完才能完成：完成时数据库会逐行核对 P2'
              }
            >
              完成上架
            </Button>
          </div>

          {!finished.ok && task.status === 'IN_PROGRESS' && (
            <p className="text-muted-foreground text-xs">
              还有 {finished.unfinished} 行没搬完（差 {formatQuantity(finished.shortfall)}）：
              「基本搬完了」不是完成，货去哪了说不清，所以「完成上架」暂时不可用。
            </p>
          )}

          {canCancel(task) ? (
            <div className="flex flex-wrap items-end gap-2">
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">取消原因（必填）</span>
                <Input
                  value={cancelReason}
                  className="w-64"
                  placeholder="货还没到齐 / 指派错了库位…"
                  onChange={(e) => setCancelReason(e.target.value)}
                />
              </label>
              <Button
                variant="ghost"
                disabled={busy || !cancelReason.trim()}
                onClick={() => onCancel(cancelReason.trim())}
              >
                取消任务
              </Button>
            </div>
          ) : (
            <p className="text-muted-foreground text-xs">
              {Number(task.scanCount) > 0
                ? `已经扫了 ${task.scanCount} 次，货已经在往目标库位搬了，不能取消：`
                : '任务已是终态，不能取消：'}
              {Number(task.scanCount) > 0
                ? '取消等于宣布「这批货还在收货暂存位」，而库里它已经不在那儿了。'
                : '已完成或已取消的单子再改状态，会让「这批货动过没有」有两个答案。'}
            </p>
          )}
        </div>

        <ModalFooter>
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            关闭
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}