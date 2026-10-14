'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { Button, EmptyState, Input, Notice, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  PickTaskCapabilities,
  PickTaskDetail,
  PickTaskPage,
} from '../../../../../packages/api-contracts/src/index';
import { useIdentity } from '../iam/identity-shell';
import {
  assignPickTask,
  canCancel,
  canComplete,
  canRecordPick,
  canReportException,
  canReopen,
  cancelPickTask,
  completePickTask,
  exceptionKindLabel,
  fetchPickTask,
  fetchPickTaskCapabilities,
  fetchPickTasks,
  lineStatusLabel,
  lineWarnings,
  locationDrifted,
  needsShortageQuantity,
  pickCompletionText,
  pickTaskQuery,
  progressText,
  qtyText,
  recordPick,
  reopenPickTask,
  reportPickException,
  resolutionLabel,
  serialText,
  shortfallText,
  statusLabel,
  statusTone,
  type PickTaskExceptionKind,
} from './pick-task-model';

/**
 * 8.3.2.1 拣货任务工作区。
 *
 * <p>蓝图：「<b>生成与执行拣货任务：按库位顺序排序优化路径，记录实际拣货库位/批次/序列号/数量，
 * 异常（缺货、破损）上报。</b>」「<b>拣货完不等于已发货</b>：拣货只是把货从库位移到打包区，
 * 库存仍占用未释放。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>路径与计划段是这一页的主干</b>，不是附表：拣货单照 sequenceNo 打印、作业员照它走，
 *       所以序号、库位层级路径、不可拣标记、SKU 离散度都在表格里逐行给出。</li>
 *   <li><b>实拣与计划分开两块渲染</b>。实际取货库位与计划不一致的行要能一眼看出来 ——
 *       「计划错了」和「现场换了个地方」是两件要分别归因的事。</li>
 *   <li><b>「完成」按钮不叫「发货」。</b>完成后的提示里必须写明货在打包区、预占仍在、
 *       出库单只到「待复核」。</li>
 *   <li><b>缺货必须写缺几件</b>：只有缺货时表单才出现数量输入框，其余四类不出现
 *       （后端 CHECK 会拒带数量的非缺货异常）。</li>
 *   <li>加载 / 空 / 错误三态分开渲染。</li>
 * </ul>
 */

const exceptionKinds: PickTaskExceptionKind[] = [
  'SHORTAGE',
  'DAMAGED',
  'MISSING_SERIAL',
  'WRONG_LOCATION',
  'OTHER',
];

export function PickTaskWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [page, setPage] = useState(0);
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [list, setList] = useState<PickTaskPage | null>(null);
  const [caps, setCaps] = useState<PickTaskCapabilities | null>(null);
  const [detail, setDetail] = useState<PickTaskDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');

  // 实拣表单
  const [pickLineId, setPickLineId] = useState('');
  const [pickLocationId, setPickLocationId] = useState('');
  const [pickQuantity, setPickQuantity] = useState('');
  const [pickNote, setPickNote] = useState('');

  // 异常表单
  const [exceptionKind, setExceptionKind] = useState<PickTaskExceptionKind>('SHORTAGE');
  const [exceptionLineId, setExceptionLineId] = useState('');
  const [exceptionQuantity, setExceptionQuantity] = useState('');
  const [exceptionDetail, setExceptionDetail] = useState('');

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const [data, capability] = await Promise.all([
        fetchPickTasks({ status, keyword, page, size: 50 }),
        fetchPickTaskCapabilities(),
      ]);
      setList(data);
      setCaps(capability);
    } catch (e) {
      setError(e instanceof Error ? e.message : '拣货任务读取失败');
    } finally {
      setLoading(false);
    }
  }, [status, keyword, page, readable]);

  useEffect(() => {
    void load();
  }, [load]);

  const openTask = async (taskId: string) => {
    setActionError('');
    try {
      setDetail(await fetchPickTask(taskId));
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '拣货任务详情读取失败');
    }
  };

  const run = async (work: () => Promise<unknown>, taskId?: string) => {
    setActionError('');
    try {
      await work();
      await load();
      if (taskId) await openTask(taskId);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '操作失败');
    }
  };

  const rows = useMemo(() => list?.items ?? [], [list]);
  const summary = list?.summary;
  const task = detail?.task;

  if (!readable) {
    return (
      <SectionCard>
        <EmptyState
          title="没有查看拣货任务的权限"
          description="本功能需要出入库作业读取权限（warehouse.inventory.read）。"
        />
      </SectionCard>
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="拣货任务"
        description="按库位顺序排出拣货路径，逐次记录实际库位、批次、序列号与数量，缺货与破损当场上报"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            <RefreshCw className="h-4 w-4" /> 刷新
          </Button>
        }
      />

      {error !== '' && (
        <Notice tone="danger" title="拣货任务读取失败">
          {error}
        </Notice>
      )}
      {actionError !== '' && (
        <Notice tone="danger" title="操作失败">
          {actionError}
        </Notice>
      )}

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <StatCard label="任务总数" value={String(summary?.totalCount ?? 0)} />
        <StatCard label="计划件数合计" value={qtyText(summary?.totalPlannedQuantity)} />
        <StatCard label="实拣件数合计" value={qtyText(summary?.totalPickedQuantity)} />
        <StatCard
          label="含不可拣库位取货的任务"
          value={String(summary?.nonPickableTaskCount ?? 0)}
        />
        <StatCard
          label="含 SKU 分散的任务"
          value={String(summary?.dispersionTaskCount ?? 0)}
        />
      </div>
      <p className="text-xs text-slate-400">
        「含不可拣库位取货」与「含 SKU 分散」两张卡对应 8.5.2.1 库位利用率里
        <b>ownerModule 指向 8.3.2.1</b> 的两类建议（高频货在不可拣库位 / SKU 散在多库位）：
        分析模块只指出问题并指认归属，落地是在这一页的拣货路径上。
      </p>

      <SectionCard title="筛选条件">
        <div className="grid gap-3 sm:grid-cols-3">
          <label className="text-sm text-slate-300">
            状态
            <select
              className="mt-1 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部</option>
              {(caps?.statuses ?? []).map((s) => (
                <option key={s} value={s}>
                  {statusLabel(s)}
                </option>
              ))}
            </select>
          </label>
          <label className="text-sm text-slate-300">
            关键字（拣货单号 / 出库单号）
            <Input
              value={keyword}
              placeholder="如 PT-20261014-1a2b3c4d"
              onChange={(e) => {
                setKeyword(e.target.value);
                setPage(0);
              }}
            />
          </label>
          <div className="flex items-end gap-2">
            <Button
              onClick={() => {
                setStatus('');
                setKeyword('');
                setPage(0);
              }}
            >
              重置筛选
            </Button>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="拣货任务">
        {loading && rows.length === 0 ? (
          <p className="text-sm text-slate-400">正在加载拣货任务…</p>
        ) : rows.length === 0 ? (
          <EmptyState
            title="还没有拣货任务"
            description="拣货任务由出库单的拣货交接请求生成：先在出库单列表点「生成拣货任务」，再回到这里生成真正的拣货任务。"
          />
        ) : (
          <div className="space-y-3">
            <table className="w-full text-sm">
              <thead className="text-left text-slate-400">
                <tr>
                  <th className="py-2">拣货单号</th>
                  <th className="py-2">出库单</th>
                  <th className="py-2">状态</th>
                  <th className="py-2">进度</th>
                  <th className="py-2">拣齐</th>
                  <th className="py-2">异常</th>
                  <th className="py-2">警示</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id} className="border-t border-slate-800">
                    <td className="py-2 font-mono text-xs">{row.taskCode}</td>
                    <td className="py-2">{row.outboundOrderCode}</td>
                    <td className="py-2">
                      <span
                        className={
                          statusTone(row.status) === 'success'
                            ? 'rounded bg-emerald-900/40 px-2 py-0.5 text-xs text-emerald-200'
                            : statusTone(row.status) === 'danger'
                              ? 'rounded bg-rose-900/40 px-2 py-0.5 text-xs text-rose-200'
                              : statusTone(row.status) === 'info'
                                ? 'rounded bg-indigo-900/40 px-2 py-0.5 text-xs text-indigo-200'
                                : 'rounded bg-slate-800 px-2 py-0.5 text-xs text-slate-300'
                        }
                      >
                        {statusLabel(row.status)}
                      </span>
                    </td>
                    <td className="py-2">
                      {progressText(row.progress)}（{qtyText(row.pickedQuantity)} /{' '}
                      {qtyText(row.plannedQuantity)}）
                    </td>
                    <td className="py-2">{pickCompletionText(row)}</td>
                    <td className="py-2">
                      {row.exceptionCount}
                      {row.hasUnresolvedException ? '（有未处理）' : ''}
                    </td>
                    <td className="py-2 text-xs text-amber-200">
                      {row.nonPickableLineCount > 0 ? `${row.nonPickableLineCount} 段不可拣库位` : ''}
                      {row.reopenCount > 0 ? ` · 已重开 ${row.reopenCount} 次` : ''}
                    </td>
                    <td className="py-2 text-right">
                      <Button onClick={() => void openTask(row.id)}>详情</Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="flex items-center justify-between text-sm text-slate-400">
              <span>
                共 {list?.total ?? 0} 张（汇总不受筛选影响）
              </span>
              <div className="flex gap-2">
                <Button disabled={page === 0} onClick={() => setPage(page - 1)}>
                  上一页
                </Button>
                <Button disabled={(list?.items.length ?? 0) < 50} onClick={() => setPage(page + 1)}>
                  下一页
                </Button>
              </div>
            </div>
          </div>
        )}
      </SectionCard>

      {detail && task && (
        <>
          <SectionCard
            title={`拣货任务 ${task.taskCode}`}
            description={`出库单 ${detail.outboundOrderCode}（${detail.outboundOrderStatusLabel ?? detail.outboundOrderStatus}）`}
          >
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <StatCard label="状态" value={statusLabel(task.status)} />
              <StatCard label="打包区" value={task.stagingLocationCode ?? '—'} />
              <StatCard
                label="进度"
                value={`${progressText(task.progress)}（${qtyText(task.pickedQuantity)} / ${qtyText(task.plannedQuantity)}）`}
              />
              <StatCard label="拣齐" value={pickCompletionText(task)} />
            </div>
            <p className="text-xs text-slate-400">
              {task.fullyPicked
                ? '已拣齐，可提交复核（8.3.3.1）。'
                : '还有段没拣齐或未按缺货登记：完成拣货要求每一段都拣齐，或每一段未拣齐的都已按缺货登记。'}
            </p>
            <Notice tone="info" title="拣货完 ≠ 已发货">
              {detail.note}
            </Notice>
            <Notice tone="info" title="拣货路径口径">
              {detail.pathReport.basis}
            </Notice>
            <div className="flex flex-wrap gap-2">
              <Button
                disabled={!writable || !canRecordPick(task.status)}
                onClick={() =>
                  void run(
                    () =>
                      recordPick(task.id, {
                        lineId: pickLineId,
                        locationId: pickLocationId,
                        lotId: null,
                        quantity: Number(pickQuantity),
                        serialIds: null,
                        pickedAt: new Date().toISOString(),
                        note: pickNote === '' ? null : pickNote,
                      }),
                    task.id,
                  )
                }
              >
                登记实拣
              </Button>
              <Button
                disabled={!writable || !canComplete(task.status)}
                onClick={() => void run(() => completePickTask(task.id), task.id)}
              >
                完成拣货（提交复核）
              </Button>
              <Button
                disabled={!writable || !canReopen(task.status)}
                onClick={() => void run(() => reopenWithReason(task.id), task.id)}
              >
                重开（复核返工）
              </Button>
              <Button
                disabled={!writable || !canCancel(task.status)}
                onClick={() => void run(() => cancelWithReason(task.id), task.id)}
              >
                取消任务
              </Button>
            </div>
            <p className="text-xs text-slate-400">
              实拣与异常只能在「拣货中」登记；重开需要出库单已被复核退回「拣货中」，且必须写原因；
              取消不动库存（货仍在原库位、预占不变）。
            </p>
          </SectionCard>

          <SectionCard title="拣货路径与计划段" description="拣货单照序号打印，作业员照它走">
            <table className="w-full text-sm">
              <thead className="text-left text-slate-400">
                <tr>
                  <th className="py-2">序号</th>
                  <th className="py-2">SKU</th>
                  <th className="py-2">取货库位</th>
                  <th className="py-2">层级路径</th>
                  <th className="py-2">批次</th>
                  <th className="py-2">计划 / 已拣</th>
                  <th className="py-2">行状态</th>
                  <th className="py-2">警示</th>
                </tr>
              </thead>
              <tbody>
                {detail.lines.map((line) => (
                  <tr key={line.id} className="border-t border-slate-800">
                    <td className="py-2">{line.sequenceNo}</td>
                    <td className="py-2">
                      {line.skuCode}
                      <span className="ml-1 text-xs text-slate-400">{line.skuName}</span>
                    </td>
                    <td className="py-2">
                      {line.sourceLocationCode}
                      {!line.sourcePickable ? (
                        <span className="ml-1 text-xs text-amber-200">不可拣</span>
                      ) : null}
                    </td>
                    <td className="py-2 font-mono text-xs text-slate-400">{line.locationPath}</td>
                    <td className="py-2">{line.sourceLotCode ?? '—'}</td>
                    <td className="py-2">
                      {qtyText(line.plannedQuantity)} / {qtyText(line.pickedQuantity)}
                      <span className="ml-1 text-xs text-slate-400">
                        （{shortfallText(line.shortfall)}）
                      </span>
                    </td>
                    <td className="py-2">{lineStatusLabel(line.lineStatus)}</td>
                    <td className="py-2 text-xs text-amber-200">
                      {lineWarnings(line).join('；') || '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="mt-3 grid gap-2 text-sm text-slate-400 sm:grid-cols-3">
              <span>共 {detail.pathReport.totalLineCount} 段</span>
              <span>要走 {detail.pathReport.distinctLocationCount} 个库位</span>
              <span>
                其中 {detail.pathReport.nonPickableLineCount} 段只能退到不可拣库位 ·{' '}
                {detail.pathReport.dispersionSkuCount} 段 SKU 散在多库位
              </span>
            </div>
          </SectionCard>

          <SectionCard title="登记实拣" description="实际库位可以与计划不同：分配是建议，现场是事实">
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <label className="text-sm text-slate-300">
                拣哪一段
                <select
                  className="mt-1 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
                  value={pickLineId}
                  onChange={(e) => {
                    setPickLineId(e.target.value);
                    const line = detail.lines.find((l) => l.id === e.target.value);
                    if (line) setPickLocationId(line.sourceLocationId);
                  }}
                >
                  <option value="">请选择</option>
                  {detail.lines.map((line) => (
                    <option key={line.id} value={line.id}>
                      #{line.sequenceNo} {line.skuCode} @ {line.sourceLocationCode}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-sm text-slate-300">
                实际取货库位 id
                <Input value={pickLocationId} onChange={(e) => setPickLocationId(e.target.value)} />
              </label>
              <label className="text-sm text-slate-300">
                本次实拣数量
                <Input value={pickQuantity} onChange={(e) => setPickQuantity(e.target.value)} />
              </label>
              <label className="text-sm text-slate-300">
                现场备注
                <Input value={pickNote} onChange={(e) => setPickNote(e.target.value)} />
              </label>
            </div>
          </SectionCard>

          <SectionCard title="实拣记录" description="实际库位 / 批次 / 序列号逐次留痕">
            {detail.picks.length === 0 ? (
              <EmptyState title="还没有实拣记录" description="接单之后才能登记实拣。" />
            ) : (
              <table className="w-full text-sm">
                <thead className="text-left text-slate-400">
                  <tr>
                    <th className="py-2">时间</th>
                    <th className="py-2">实际库位</th>
                    <th className="py-2">批次</th>
                    <th className="py-2">数量</th>
                    <th className="py-2">序列号</th>
                    <th className="py-2">备注</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.picks.map((pick) => {
                    const line = detail.lines.find((l) => l.id === pick.lineId);
                    return (
                      <tr key={pick.id} className="border-t border-slate-800">
                        <td className="py-2 text-xs text-slate-400">{pick.pickedAt}</td>
                        <td className="py-2">
                          {pick.locationCode}
                          {line && locationDrifted(line, pick) ? (
                            <span className="ml-1 text-xs text-amber-200">
                              计划是 {line.sourceLocationCode}
                            </span>
                          ) : null}
                        </td>
                        <td className="py-2">{pick.lotCode ?? '—'}</td>
                        <td className="py-2">{qtyText(pick.quantity)}</td>
                        <td className="py-2 text-xs">{serialText(pick) || '—'}</td>
                        <td className="py-2 text-xs text-slate-400">{pick.note || '—'}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
          </SectionCard>

          <SectionCard title="异常上报" description="缺货必须写缺几件；其余四类不填数量">
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <label className="text-sm text-slate-300">
                异常种类
                <select
                  className="mt-1 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
                  value={exceptionKind}
                  onChange={(e) => setExceptionKind(e.target.value as PickTaskExceptionKind)}
                >
                  {exceptionKinds.map((k) => (
                    <option key={k} value={k}>
                      {exceptionKindLabel(k)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-sm text-slate-300">
                哪一段（可空）
                <select
                  className="mt-1 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
                  value={exceptionLineId}
                  onChange={(e) => setExceptionLineId(e.target.value)}
                >
                  <option value="">不指定</option>
                  {detail.lines.map((line) => (
                    <option key={line.id} value={line.id}>
                      #{line.sequenceNo} {line.skuCode}
                    </option>
                  ))}
                </select>
              </label>
              {needsShortageQuantity(exceptionKind) && (
                <label className="text-sm text-slate-300">
                  缺多少件（必填）
                  <Input
                    value={exceptionQuantity}
                    onChange={(e) => setExceptionQuantity(e.target.value)}
                  />
                </label>
              )}
              <label className="text-sm text-slate-300">
                说明（必填）
                <Input
                  value={exceptionDetail}
                  onChange={(e) => setExceptionDetail(e.target.value)}
                />
              </label>
            </div>
            <Button
              className="mt-3"
              disabled={!writable || !canReportException(task.status)}
              onClick={() =>
                void run(
                  () =>
                    reportPickException(task.id, {
                      kind: exceptionKind,
                      lineId: exceptionLineId === '' ? null : exceptionLineId,
                      shortageQuantity: needsShortageQuantity(exceptionKind)
                        ? Number(exceptionQuantity)
                        : null,
                      locationId: null,
                      detail: exceptionDetail,
                      reportedAt: new Date().toISOString(),
                    }),
                  task.id,
                )
              }
            >
              上报异常
            </Button>
            {detail.exceptions.length === 0 ? (
              <EmptyState title="没有异常" description="缺货、破损等情况在这里逐条上报。" />
            ) : (
              <table className="mt-4 w-full text-sm">
                <thead className="text-left text-slate-400">
                  <tr>
                    <th className="py-2">种类</th>
                    <th className="py-2">缺量</th>
                    <th className="py-2">说明</th>
                    <th className="py-2">处置</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.exceptions.map((ex) => (
                    <tr key={ex.id} className="border-t border-slate-800">
                      <td className="py-2">{exceptionKindLabel(ex.kind)}</td>
                      <td className="py-2">{qtyText(ex.shortageQuantity)}</td>
                      <td className="py-2">{ex.detail}</td>
                      <td className="py-2 text-xs text-slate-400">
                        {ex.resolved
                          ? `${resolutionLabel(ex.resolution ?? '')} · ${ex.resolveNote ?? ''}`
                          : '未处理'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>

          <SectionCard title="作业时间线">
            <ul className="space-y-2 text-sm">
              {detail.events.map((event) => (
                <li key={event.id} className="border-l-2 border-slate-700 pl-3">
                  <span className="text-xs text-slate-400">{event.occurredAt}</span>{' '}
                  <span className="text-slate-200">
                    {event.eventTypeLabel ?? event.eventType}
                  </span>
                  <p className="text-slate-400">{event.detail}</p>
                </li>
              ))}
            </ul>
          </SectionCard>
        </>
      )}
    </div>
  );

  async function reopenWithReason(taskId: string) {
    const reason = window.prompt('重开原因（复核返工必须写原因）');
    if (!reason) return;
    // 实拣量在重开后是**累加**，第一次拣的记录不会被抹掉。
    await reopenPickTask(taskId, { reason, assigneeUserId: task?.assigneeUserId ?? null });
  }

  async function cancelWithReason(taskId: string) {
    const reason = window.prompt('取消原因（货仍在原库位、预占不变）');
    if (!reason) return;
    await cancelPickTask(taskId, { reason });
  }
}

/** 供测试与其它页面复用的查询串拼装（避免各处手拼导致漏掉参数）。 */
export { pickTaskQuery };