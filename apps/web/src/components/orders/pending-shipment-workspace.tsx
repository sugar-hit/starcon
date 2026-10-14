'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, Boxes, Filter, PackageCheck, RefreshCw, Truck } from 'lucide-react';
import {
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';

/**
 * 一个朴素的下拉框。
 *
 * <p>刻意不套 {@code FormField}：那个组件的 children 被约束成
 * {@code React.InputHTMLAttributes} 的 input，而分组维度 / 批量操作类型都是
 * <b>枚举</b> —— 用 input 画一个下拉会得到一个只能手打、还能打错枚举值的控件。
 * 所以这里自己写 label + select，并显式给出同样的可访问性 id。
 */
function SelectField(props: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
}) {
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={props.id} className="text-sm font-medium">
        {props.label}
      </label>
      <select
        id={props.id}
        className="border-input h-9 rounded-md border bg-transparent px-2 text-sm"
        value={props.value}
        onChange={(e) => props.onChange(e.target.value)}
      >
        {props.options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </div>
  );
}
import type {
  PendingShipmentBoard,
  PendingShipmentCapabilities,
  PendingShipmentUnitDetail,
  PendingShipmentUnitPage,
  PendingShipmentUnitRow,
  ShipmentBatchTaskDetail,
  ShipmentBatchTaskInput,
  ShipmentBatchTaskPage,
  ShipmentBatchTaskRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  batchStatusSeverity,
  batchTaskFinished,
  cancelPendingShipmentUnit,
  cancelShipmentBatchTask,
  carrierChannelLabel,
  carrierMissing,
  createShipmentBatchTask,
  errorText,
  executeShipmentBatchTask,
  failedItems,
  fetchPendingShipmentBoard,
  fetchPendingShipmentCapabilities,
  fetchPendingShipmentUnit,
  fetchPendingShipmentUnits,
  fetchShipmentBatchTask,
  fetchShipmentBatchTasks,
  groupViews,
  needsAttention,
  nextActionText,
  pendingShipmentStatusLabel,
  registerShipmentSla,
  shipmentBatchStatusLabel,
  shipmentBatchTaskTypeLabel,
  shipmentSlaBucketLabel,
  shipmentSlaSourceLabel,
  shipmentTaskItemStatusLabel,
  slaBucketSeverity,
  summaryText,
  syncPendingShipment,
  taskEligibility,
  timeText,
  waybillText,
  weightText,
} from './pending-shipment-model';

const PAGE_SIZE = 20;

/** 蓝图点名的四个分组维度，顺序按「现场最先看的那个」排：承运商在大促期最要紧。 */
const GROUP_BY_OPTIONS = [
  { value: 'CARRIER', label: '按承运商分组' },
  { value: 'WAREHOUSE', label: '按仓库分组' },
  { value: 'CARRIER_CHANNEL', label: '按承运渠道分组' },
  { value: 'SLA_DEADLINE', label: '按发货时限分组' },
];

const TASK_TYPES: ShipmentBatchTaskInput['taskType'][] = [
  'WAYBILL_REQUEST',
  'LABEL_PRINT',
  'HANDOVER',
];

/**
 * 9.4.1.1 待发货工作区。
 *
 * <p>蓝图：「待发货订单看板：按仓库、承运渠道、承运商、发货时限分组展示，
 * 支持批量申请运单号、批量打单、批量交接。」
 *
 * <p>五个区块按现场的作业顺序排：**分组看板 → 待发货清单 → 批量操作任务**，
 * 外加能力声明与同步入口。
 *
 * <p>刻意不在界面上出现「已发货」字样：本模块最远到**已交接**，
 * 真正确认发货的是 8.3.4.1（见 9.4.1.2 发货记录）。在这里写「已发货」
 * 会让仓库以为货已经出门，而承运商还没上门。
 *
 * <p>加载态 / 空态 / 错误态三态齐全：空看板要区分「一条待发货都没有」与
 * 「还没从 8.3.3.2 同步过」—— 后者给的是同步按钮，不是空白页。
 */
export function PendingShipmentWorkspace() {
  const [capabilities, setCapabilities] = useState<PendingShipmentCapabilities | null>(null);
  const [board, setBoard] = useState<PendingShipmentBoard | null>(null);
  const [page, setPage] = useState<PendingShipmentUnitPage | null>(null);
  const [tasks, setTasks] = useState<ShipmentBatchTaskPage | null>(null);
  const [taskDetail, setTaskDetail] = useState<ShipmentBatchTaskDetail | null>(null);
  const [unitDetail, setUnitDetail] = useState<PendingShipmentUnitDetail | null>(null);

  const [groupBy, setGroupBy] = useState('CARRIER');
  const [carrierCode, setCarrierCode] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageIndex, setPageIndex] = useState(0);
  const [selected, setSelected] = useState<string[]>([]);
  const [taskType, setTaskType] = useState<ShipmentBatchTaskInput['taskType']>('WAYBILL_REQUEST');
  const [taskNote, setTaskNote] = useState('');
  const [syncCarrier, setSyncCarrier] = useState('');
  const [slaDeadline, setSlaDeadline] = useState('');
  const [slaNote, setSlaNote] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, boardData, unitData, taskData] = await Promise.all([
        fetchPendingShipmentCapabilities(),
        fetchPendingShipmentBoard({ groupBy, carrierCode, keyword }),
        fetchPendingShipmentUnits({
          carrierCode,
          keyword,
          page: pageIndex,
          size: PAGE_SIZE,
        }),
        fetchShipmentBatchTasks({ page: 0, size: PAGE_SIZE }),
      ]);
      setCapabilities(caps);
      setBoard(boardData);
      setPage(unitData);
      setTasks(taskData);
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setLoading(false);
    }
  }, [groupBy, carrierCode, keyword, pageIndex]);

  useEffect(() => {
    void load();
  }, [load]);

  const groups = useMemo(() => groupViews(board?.groups ?? []), [board]);
  const units = page?.items ?? [];
  const selectedUnits = useMemo(
    () => units.filter((u) => selected.includes(u.id)),
    [units, selected],
  );
  const eligibleSelected = useMemo(
    () => selectedUnits.filter((u) => taskEligibility(u).eligible),
    [selectedUnits],
  );
  const blockedSelected = useMemo(
    () => selectedUnits.filter((u) => !taskEligibility(u).eligible),
    [selectedUnits],
  );
  /** 选中集合里混了几个承运商 —— 建任务前先说清，免得点了才报 400。 */
  const selectedCarriers = useMemo(
    () => Array.from(new Set(eligibleSelected.map((u) => u.carrierCode))).sort(),
    [eligibleSelected],
  );

  async function run(action: () => Promise<string>) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      setNotice(await action());
      await load();
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setBusy(false);
    }
  }

  function toggle(id: string) {
    setSelected((current) =>
      current.includes(id) ? current.filter((x) => x !== id) : [...current, id],
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="待发货"
        description="9.4.1.1 · 按仓库 / 承运渠道 / 承运商 / 发货时限分组，支持批量申请运单号、批量打单、批量交接"
        actions={
          <Button onClick={() => void load()} disabled={loading || busy}>
            <RefreshCw className={loading ? 'animate-spin' : ''} /> 刷新
          </Button>
        }
      />

      {error ? (
        <Notice tone="danger" title="操作失败">
          {error}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      ) : null}

      {capabilities ? (
        <SectionCard title="能力与上游可用性">
          <ul className="space-y-2 text-sm">
            {capabilities.dimensions.map((dimension) => (
              <li key={dimension.dimension} className="flex items-start gap-2">
                <span
                  className={
                    dimension.available
                      ? 'mt-0.5 inline-flex shrink-0 rounded px-1.5 py-0.5 text-xs bg-emerald-100 text-emerald-800'
                      : 'mt-0.5 inline-flex shrink-0 rounded px-1.5 py-0.5 text-xs bg-amber-100 text-amber-800'
                  }
                >
                  {dimension.available ? '可用' : '未交付'}
                </span>
                <span>
                  <strong>{dimension.dimension}</strong> · {dimension.description}
                  <br />
                  <span className="text-muted-foreground">
                    来源：{dimension.source}
                    {dimension.ownerModule ? ` · 归属模块：${dimension.ownerModule}` : ''}
                  </span>
                  {dimension.impact ? <div className="text-muted-foreground">{dimension.impact}</div> : null}
                </span>
              </li>
            ))}
          </ul>
          <p className="mt-3 text-xs text-muted-foreground">{capabilities.downstreamNote}</p>
        </SectionCard>
      ) : null}

      <SectionCard
        title="从 8.3.3.2 同步已完成打包的出库单"
        extra={<Boxes className="h-4 w-4" />}
      >
        <p className="text-sm text-muted-foreground">
          待发货清单的唯一来源是 warehouse 服务 8.3.3.2 的授权只读接口
          （跨库视图与跨服务 SQL 都被禁止）。同步是幂等的：重复同步只更新，不重复插入。
        </p>
        <div className="mt-3 flex flex-wrap items-end gap-3">
          <FormField
            id="pending-shipment-sync-carrier"
            label="默认承运商代码（可留空，留空的单元会进「还没选承运商」状态）"
          >
            <Input
              value={syncCarrier}
              placeholder="例如 SF / DHL"
              onChange={(e) => setSyncCarrier(e.target.value)}
            />
          </FormField>
          <Button
            disabled={busy}
            onClick={() =>
              void run(async () => {
                const result = await syncPendingShipment({
                  warehouseId: '',
                  carrierChannel: 'THIRD_PARTY',
                  carrierCode: syncCarrier,
                  carrierAssignments: [],
                  pageSize: 100,
                });
                return `同步完成：读到 ${result.fetchedUnits} 票，新增 ${result.insertedUnits}，更新 ${result.updatedUnits}，跳过 ${result.skippedUnits}，包裹 ${result.parcelRowsUpserted} 行，上游已不在完成列表的历史单元 ${result.leftBehindUnits} 票（保留不删）。`;
              })
            }
          >
            同步
          </Button>
        </div>
      </SectionCard>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="待发货" value={page?.summary.pendingUnits ?? 0} />
        <StatCard label="已申请运单号" value={page?.summary.waybillRequestedUnits ?? 0} />
        <StatCard label="已打单" value={page?.summary.labelPrintedUnits ?? 0} />
        <StatCard label="已交接" value={page?.summary.handedOverUnits ?? 0} />
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="已逾期" value={page?.summary.overdueUnits ?? 0} />
        <StatCard label="今天到期" value={page?.summary.dueTodayUnits ?? 0} />
        <StatCard label="未登记时限" value={page?.summary.unsetSlaUnits ?? 0} />
        <StatCard label="未完结批量任务" value={page?.summary.openBatchTasks ?? 0} />
      </div>

      <SectionCard title="分组看板" extra={<Filter className="h-4 w-4" />}>
        <div className="flex flex-wrap items-end gap-3">
          <SelectField
            id="pending-shipment-group-by"
            label="分组维度"
            value={groupBy}
            onChange={setGroupBy}
            options={GROUP_BY_OPTIONS}
          />
          <FormField id="pending-shipment-carrier" label="承运商">
            <Input
              value={carrierCode}
              placeholder="按承运商筛选"
              onChange={(e) => setCarrierCode(e.target.value)}
            />
          </FormField>
          <FormField id="pending-shipment-keyword" label="关键字">
            <Input
              value={keyword}
              placeholder="出库单号 / 销售单号 / 店铺 / 买家"
              onChange={(e) => setKeyword(e.target.value)}
            />
          </FormField>
        </div>

        <p className="mt-2 text-xs text-muted-foreground">{summaryText(page?.summary)}</p>

        {groups.length === 0 ? (
          <EmptyState
            title="还没有待发货的票"
            description="先在上面从 8.3.3.2 同步一次已完成打包的出库单"
          />
        ) : (
          <div className="mt-3 overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-xs text-muted-foreground">
                <tr>
                  <th className="py-2">分组</th>
                  <th className="py-2">待发货单元</th>
                  <th className="py-2">包裹</th>
                  <th className="py-2">总重</th>
                  <th className="py-2">逾期</th>
                  <th className="py-2">今天到期</th>
                  <th className="py-2">未登记时限</th>
                </tr>
              </thead>
              <tbody>
                {groups.map((group) => (
                  <tr key={group.groupKey} className="border-t">
                    <td className="py-2 font-medium">
                      {group.groupLabel}
                      {group.attention ? (
                        <AlertTriangle className="ml-1 inline h-3.5 w-3.5 text-amber-600" />
                      ) : null}
                    </td>
                    <td className="py-2">{group.unitCount}</td>
                    <td className="py-2">{group.parcelCount}</td>
                    <td className="py-2">{weightText(group.totalWeightKg)}</td>
                    <td className="py-2">{group.overdueCount}</td>
                    <td className="py-2">{group.dueTodayCount}</td>
                    <td className="py-2">{group.unsetSlaCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="mt-2 text-xs text-muted-foreground">
          分组值来自后端 GROUP BY，与列表翻到第几页无关；未登记时限单独成桶，不会混进「今天到期」。
        </p>
      </SectionCard>

      <SectionCard title="批量操作" extra={<PackageCheck className="h-4 w-4" />}>
        <div className="flex flex-wrap items-end gap-3">
          <SelectField
            id="pending-shipment-task-type"
            label="批量操作"
            value={taskType}
            onChange={(value) => setTaskType(value as ShipmentBatchTaskInput['taskType'])}
            options={TASK_TYPES.map((type) => ({
              value: type,
              label: shipmentBatchTaskTypeLabel(type),
            }))}
          />
          <FormField id="pending-shipment-task-note" label="任务说明（必填）">
            <Input
              value={taskNote}
              placeholder="例如 大促交接第一批"
              onChange={(e) => setTaskNote(e.target.value)}
            />
          </FormField>
          <Button
            disabled={busy || eligibleSelected.length === 0 || taskNote.trim().length < 4}
            onClick={() =>
              void run(async () => {
                const detail = await createShipmentBatchTask({
                  taskType,
                  unitIds: eligibleSelected.map((u) => u.id),
                  warehouseId: '',
                  carrierChannel: 'THIRD_PARTY',
                  carrierCode: selectedCarriers[0] ?? '',
                  note: taskNote.trim(),
                });
                setSelected([]);
                setTaskDetail(detail);
                return `已建批量任务 ${detail.task.taskNo}，覆盖 ${detail.items.length} 条。`;
              })
            }
          >
            建批量任务（已选 {eligibleSelected.length}）
          </Button>
        </div>

        {selectedUnits.length > 0 ? (
          <div className="mt-3 space-y-2 text-sm">
            <p>
              已选 {selectedUnits.length} 个单元，其中可入任务 {eligibleSelected.length} 个。
              {selectedCarriers.length > 0
                ? ` 涉及承运商：${selectedCarriers.join('、')}。`
                : ' '}
            </p>
            {selectedCarriers.length > 1 ? (
              <Notice tone="danger" title="一次任务不许跨承运商">
                一次任务不许跨承运商（混在一起会导致交接混乱）。请按承运商拆成多张任务。
              </Notice>
            ) : null}
            {blockedSelected.length > 0 ? (
              <ul className="text-xs text-muted-foreground">
                {blockedSelected.map((unit) => (
                  <li key={unit.id}>
                    {unit.outboundOrderNo}：{taskEligibility(unit).reason}
                  </li>
                ))}
              </ul>
            ) : null}
          </div>
        ) : (
          <p className="mt-3 text-sm text-muted-foreground">
            在下面清单里勾选单元后再建任务。承运商为空的单元会禁用并写明原因。
          </p>
        )}
      </SectionCard>

      <SectionCard title="待发货清单">
        {loading ? (
          <p className="text-sm text-muted-foreground">加载中…</p>
        ) : units.length === 0 ? (
          <EmptyState
            title="没有符合条件的待发货单元"
            description="调整筛选，或先从 8.3.3.2 同步已完成打包的出库单"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-xs text-muted-foreground">
                <tr>
                  <th className="py-2">选</th>
                  <th className="py-2">出库单</th>
                  <th className="py-2">仓库</th>
                  <th className="py-2">承运渠道</th>
                  <th className="py-2">承运商</th>
                  <th className="py-2">时限</th>
                  <th className="py-2">包裹/重量</th>
                  <th className="py-2">运单号</th>
                  <th className="py-2">状态</th>
                  <th className="py-2">下一步</th>
                </tr>
              </thead>
              <tbody>
                {units.map((unit: PendingShipmentUnitRow) => {
                  const eligibility = taskEligibility(unit);
                  return (
                    <tr key={unit.id} className="border-t">
                      <td className="py-2">
                        <input
                          type="checkbox"
                          aria-label={`选择 ${unit.outboundOrderNo}`}
                          checked={selected.includes(unit.id)}
                          onChange={() => toggle(unit.id)}
                        />
                      </td>
                      <td className="py-2">
                        <button
                          type="button"
                          className="underline"
                          onClick={() =>
                            void run(async () => {
                              const detail = await fetchPendingShipmentUnit(unit.id);
                              setUnitDetail(detail);
                              return '';
                            })
                          }
                        >
                          {unit.outboundOrderNo}
                        </button>
                      </td>
                      <td className="py-2">{unit.warehouseRef}</td>
                      <td className="py-2">{carrierChannelLabel(unit.carrierChannel)}</td>
                      <td className="py-2">
                        {carrierMissing(unit) ? (
                          <span className="text-amber-700">还没选承运商</span>
                        ) : (
                          unit.carrierCode
                        )}
                      </td>
                      <td className="py-2">
                        <span
                          className={
                            slaBucketSeverity(unit.slaBucket) === 'danger'
                              ? 'font-medium text-red-700'
                              : slaBucketSeverity(unit.slaBucket) === 'warn'
                                ? 'text-amber-700'
                                : 'text-muted-foreground'
                          }
                        >
                          {shipmentSlaBucketLabel(unit.slaBucket)}
                        </span>
                        <div className="text-xs text-muted-foreground">
                          {timeText(unit.slaDeadline)} · {shipmentSlaSourceLabel(unit.slaSource)}
                        </div>
                      </td>
                      <td className="py-2">
                        {unit.parcelCount} 件 / {weightText(unit.totalWeightKg)}
                      </td>
                      <td className="py-2">{unit.waybillNo || '—'}</td>
                      <td className="py-2">{pendingShipmentStatusLabel(unit.status)}</td>
                      <td className="py-2 text-xs">
                        {nextActionText(unit)}
                        {!eligibility.eligible ? (
                          <div className="text-muted-foreground">{eligibility.reason}</div>
                        ) : null}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {page ? (
          <div className="mt-3 flex items-center gap-2 text-sm">
            <Button
              disabled={pageIndex === 0}
              onClick={() => setPageIndex((p) => Math.max(0, p - 1))}
            >
              上一页
            </Button>
            <span>
              第 {pageIndex + 1} / {Math.max(1, page.totalPages)} 页 · 共 {page.totalElements} 票
            </span>
            <Button
              disabled={pageIndex + 1 >= page.totalPages}
              onClick={() => setPageIndex((p) => p + 1)}
            >
              下一页
            </Button>
          </div>
        ) : null}
      </SectionCard>

      {unitDetail ? (
        <SectionCard title={`出库单 ${unitDetail.unit.outboundOrderNo}`}>
          <div className="flex flex-wrap items-end gap-3">
            <FormField
              id="pending-shipment-sla-deadline"
              label="登记发货时限（平台 SLA 暂无上游可读，这里是登记）"
            >
              <Input
                type="datetime-local"
                value={slaDeadline}
                onChange={(e) => setSlaDeadline(e.target.value)}
              />
            </FormField>
            <FormField id="pending-shipment-sla-note" label="登记依据（至少 4 个字）">
              <Input
                value={slaNote}
                placeholder="例如 平台后台截图 / 邮件"
                onChange={(e) => setSlaNote(e.target.value)}
              />
            </FormField>
            <Button
              disabled={busy || !slaDeadline || slaNote.trim().length < 4}
              onClick={() =>
                void run(async () => {
                  const saved = await registerShipmentSla(unitDetail.unit.id, {
                    deadlineAt: new Date(slaDeadline).toISOString(),
                    source: 'MANUAL_ATTESTATION',
                    note: slaNote.trim(),
                  });
                  setUnitDetail(null);
                  return `已登记 ${saved.outboundOrderNo} 的发货时限：${shipmentSlaBucketLabel(
                    saved.slaBucket,
                  )}。`;
                })
              }
            >
              登记时限
            </Button>
            <Button
              disabled={busy || unitDetail.unit.status === 'HANDED_OVER'}
              onClick={() =>
                void run(async () => {
                  await cancelPendingShipmentUnit(
                    unitDetail.unit.id,
                    '运营作废：退回仓库重新处理',
                  );
                  setUnitDetail(null);
                  return '已作废，该票退回仓库。';
                })
              }
            >
              作废这一票
            </Button>
          </div>

          <h4 className="mt-4 text-sm font-medium">包裹（批量打单的对象是包裹）</h4>
          <table className="mt-2 w-full text-sm">
            <thead className="text-left text-xs text-muted-foreground">
              <tr>
                <th className="py-1">#</th>
                <th className="py-1">重量</th>
                <th className="py-1">尺寸</th>
                <th className="py-1">面单</th>
                <th className="py-1">打印凭据</th>
              </tr>
            </thead>
            <tbody>
              {unitDetail.parcels.map((parcel) => (
                <tr key={parcel.id} className="border-t">
                  <td className="py-1">{parcel.parcelNo}</td>
                  <td className="py-1">{weightText(parcel.grossWeightKg)}</td>
                  <td className="py-1">
                    {parcel.lengthMm} × {parcel.widthMm} × {parcel.heightMm} mm
                  </td>
                  <td className="py-1">{parcel.labelPrinted ? '已打单' : '未打单'}</td>
                  <td className="py-1">{parcel.labelJobRef || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <h4 className="mt-4 text-sm font-medium">运单</h4>
          {unitDetail.waybills.length === 0 ? (
            <p className="mt-1 text-sm text-muted-foreground">还没申请过运单号</p>
          ) : (
            <ul className="mt-1 space-y-1 text-sm">
              {unitDetail.waybills.map((waybill) => (
                <li key={waybill.id}>
                  {waybillText(waybill)} · 承运商 {waybill.carrierCode} · 提交{' '}
                  {timeText(waybill.requestedAt)}
                  {waybill.errorMessage ? ` · ${waybill.errorMessage}` : ''}
                </li>
              ))}
            </ul>
          )}

          <h4 className="mt-4 text-sm font-medium">完成判据</h4>
          <ul className="mt-1 list-disc pl-5 text-sm text-muted-foreground">
            {unitDetail.completionCriteria.map((criterion) => (
              <li key={criterion}>{criterion}</li>
            ))}
          </ul>

          <h4 className="mt-4 text-sm font-medium">时间线</h4>
          <ul className="mt-1 space-y-1 text-sm">
            {unitDetail.timeline.map((event) => (
              <li key={event.id}>
                {timeText(event.occurredAt)} · {event.eventKind} · {event.detail}
              </li>
            ))}
          </ul>

          <ul className="mt-4 list-disc pl-5 text-xs text-muted-foreground">
            {unitDetail.boundaryNotes.map((note) => (
              <li key={note}>{note}</li>
            ))}
          </ul>
        </SectionCard>
      ) : null}

      <SectionCard title="批量操作任务" extra={<Truck className="h-4 w-4" />}>
        {loading ? (
          <p className="text-sm text-muted-foreground">加载中…</p>
        ) : (tasks?.items ?? []).length === 0 ? (
          <EmptyState title="还没有批量任务" description="在上面勾选单元后建一张任务" />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-xs text-muted-foreground">
                <tr>
                  <th className="py-2">任务号</th>
                  <th className="py-2">操作</th>
                  <th className="py-2">承运商</th>
                  <th className="py-2">进度</th>
                  <th className="py-2">状态</th>
                  <th className="py-2">创建时间</th>
                  <th className="py-2">操作</th>
                </tr>
              </thead>
              <tbody>
                {(tasks?.items ?? []).map((task: ShipmentBatchTaskRow) => (
                  <tr key={task.id} className="border-t">
                    <td className="py-2">{task.taskNo}</td>
                    <td className="py-2">{task.taskTypeLabel}</td>
                    <td className="py-2">{task.carrierCode}</td>
                    <td className="py-2">
                      成功 {task.succeededCount} / 失败 {task.failedCount} / 跳过{' '}
                      {task.skippedCount} · 共 {task.unitCount}
                    </td>
                    <td className="py-2">
                      <span
                        className={
                          batchStatusSeverity(task.status) === 'ok'
                            ? 'text-emerald-700'
                            : batchStatusSeverity(task.status) === 'warn'
                              ? 'text-amber-700'
                              : batchStatusSeverity(task.status) === 'danger'
                                ? 'font-medium text-red-700'
                                : ''
                        }
                      >
                        {shipmentBatchStatusLabel(task.status)}
                      </span>
                      {needsAttention(task) ? (
                        <AlertTriangle className="ml-1 inline h-3.5 w-3.5 text-amber-600" />
                      ) : null}
                      {task.failureReason ? (
                        <div className="text-xs text-muted-foreground">{task.failureReason}</div>
                      ) : null}
                    </td>
                    <td className="py-2">{timeText(task.createdAt)}</td>
                    <td className="py-2">
                      <div className="flex flex-wrap gap-2">
                        <Button
                          onClick={() =>
                            void run(async () => {
                              const detail = await fetchShipmentBatchTask(task.id);
                              setTaskDetail(detail);
                              return '';
                            })
                          }
                        >
                          逐条结果
                        </Button>
                        <Button
                          disabled={busy || batchTaskFinished(task)}
                          onClick={() =>
                            void run(async () => {
                              const detail = await executeShipmentBatchTask(task.id);
                              setTaskDetail(detail);
                              return `${detail.task.taskNo} 执行完毕：${detail.task.statusLabel}`
                                + `（成功 ${detail.task.succeededCount} / 失败 ${detail.task.failedCount} / 跳过 ${detail.task.skippedCount}）。`;
                            })
                          }
                        >
                          执行
                        </Button>
                        <Button
                          disabled={busy || batchTaskFinished(task)}
                          onClick={() =>
                            void run(async () => {
                              const detail = await cancelShipmentBatchTask(
                                task.id,
                                '运营取消：这一批先不发了',
                              );
                              setTaskDetail(detail);
                              return `${detail.task.taskNo} 已取消。`;
                            })
                          }
                        >
                          取消
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {taskDetail ? (
        <SectionCard title={`批量任务 ${taskDetail.task.taskNo} 的逐条结果`}>
          <p className="text-sm">
            {taskDetail.task.taskTypeLabel} · 承运商 {taskDetail.task.carrierCode} ·{' '}
            {shipmentBatchStatusLabel(taskDetail.task.status)}
          </p>
          <table className="mt-2 w-full text-sm">
            <thead className="text-left text-xs text-muted-foreground">
              <tr>
                <th className="py-1">出库单</th>
                <th className="py-1">包裹</th>
                <th className="py-1">结果</th>
                <th className="py-1">原因</th>
                <th className="py-1">处理时刻</th>
              </tr>
            </thead>
            <tbody>
              {taskDetail.items.map((item) => (
                <tr key={item.id} className="border-t">
                  <td className="py-1">{item.outboundOrderNo}</td>
                  <td className="py-1">{item.parcelNo > 0 ? `#${item.parcelNo}` : '整票'}</td>
                  <td className="py-1">{shipmentTaskItemStatusLabel(item.status)}</td>
                  <td className="py-1 text-xs">{item.detail || '—'}</td>
                  <td className="py-1">{timeText(item.processedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {failedItems(taskDetail.items).length > 0 ? (
            <p className="mt-2 text-xs text-amber-700">
              有 {failedItems(taskDetail.items).length} 条不是成功。跳过与失败都写明了原因 ——
              「跳过」和「漏了」必须能区分。
            </p>
          ) : null}
          <ul className="mt-3 list-disc pl-5 text-xs text-muted-foreground">
            {taskDetail.boundaryNotes.map((note) => (
              <li key={note}>{note}</li>
            ))}
          </ul>
        </SectionCard>
      ) : null}
    </div>
  );
}