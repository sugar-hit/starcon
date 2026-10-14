'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { ArrowLeft, CircleSlash, RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  OrderChangeCancelCancellationDetail,
  OrderChangeCancelCapabilities,
} from '../../../../../packages/api-contracts/src/index';
import {
  blockerText,
  cancelableCell,
  cancelScopeLabels,
  changeKindLabels,
  fetchCancellation,
  fetchCapabilities,
  holdTaskStageLabels,
  holdTaskStatusLabels,
  money,
  quantityCell,
  shipBlocking,
  statusLabel,
  trim,
  warningText,
  writebackStatusLabels,
} from './order-change-cancel-model';

/**
 * 9.1.1.3 订单变更与取消（取消单工作区）。
 *
 * <p>蓝图：「处理订单变更与取消：地址修改、数量调整（需平台允许）、
 * <b>取消未发货数量（先确认仓库停止作业并释放占用；已发货数量转售后）</b>，
 * 并把变更同步回平台。」
 *
 * <p><b>本页面按蓝图的因果顺序排布，而不是按数据表的顺序。</b>
 * 运营的判断链是：这张单还能改吗 → 还能取消多少 → 卡在哪 → 谁来放行。所以区块顺序是
 * 「状态与口径 → 逐行可取消量 → 仓库停止作业与释放 → 平台回写 → 时间线」。
 *
 * <p><b>三条硬规则在界面上各自可见，不靠文案承诺：</b>
 *
 * <ul>
 *   <li><b>已发货数量不可取消</b>：逐行给出「可取消量 = 原量 − 已发量」；
 *       已发量未接入时该格显示「未接入」而不是 0（{@link quantityCell}）。
 *       整行被发货占满的行会被标出来并指向转售后。</li>
 *   <li><b>先释放再取消</b>：「仓库停止作业与释放占用」两段任务都完成之前，
 *       「还差什么才能确认」逐条列出未完成的阶段，确认按钮不出现。</li>
 *   <li><b>终态只在确认之后</b>：申请阶段的「已确认取消量」显示 0，
 *       与「申请取消量」分两列 —— 合成一列就会在仓库一个货都没放的时候
 *       告诉运营「已取消 3 件」。</li>
 * </ul>
 */
export function OrderChangeCancelWorkspace({ cancellationId }: { cancellationId: string }) {
  const [detail, setDetail] = useState<OrderChangeCancelCancellationDetail | null>(null);
  const [caps, setCaps] = useState<OrderChangeCancelCapabilities | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setDetail(await fetchCancellation(cancellationId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '取消单加载失败');
    } finally {
      setLoading(false);
    }
  }, [cancellationId]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void (async () => {
      try {
        setCaps(await fetchCapabilities());
      } catch {
        // 能力声明拿不到不该挡住单据本身 —— 单据才是这个页面的主内容。
        setCaps(null);
      }
    })();
  }, []);

  if (loading) return <EmptyState title="正在加载取消单…" description="读取取消请求与仓库任务。" />;

  if (error) {
    return (
      <EmptyState
        title="取消单加载失败"
        description={error}
        action={
          <Button variant="outline" onClick={() => void load()}>
            <RefreshCw className="mr-2 h-4 w-4" />
            重试
          </Button>
        }
      />
    );
  }

  if (!detail) {
    return <EmptyState title="取消单不存在" description="该取消请求不存在或无权查看。" />;
  }

  const h = detail.header;
  const verified = h.shippedQuantityVerified;
  const warnings = detail.warnings;
  const blocked = detail.blockers;

  return (
    <div className="space-y-6">
      <PageHeader
        title={`取消单 ${h.orderNo}`}
        description="订单变更与取消：取消未发货数量，先释放仓库占用，再由责任方确认。"
        actions={
          <Link href="/orders">
            <Button variant="outline">
              <ArrowLeft className="mr-2 h-4 w-4" />
              返回订单列表
            </Button>
          </Link>
        }
      />

      {/* 一、状态与口径 */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="取消状态"
          value={`${statusLabel(h.status)} · ${cancelScopeLabels[h.cancelScope] ?? h.cancelScope}`}
        />
        <StatCard label="订单号" value={h.orderNo} />
        <StatCard
          label="已确认取消量"
          value={trim(
            detail.lines.reduce((sum, line) => sum + Number(line.cancelledQuantity ?? 0), 0),
          )}
        />
        <StatCard label="退款金额（预估）" value={money(h.refundAmount, h.currency)} />
      </div>

      {h.status === 'REJECTED' && h.rejectionReason ? (
        <SectionCard title="拒绝原因">
          <p className="text-muted-foreground text-sm">{h.rejectionReason}</p>
        </SectionCard>
      ) : null}

      {/* 二、逐行可取消量：蓝图硬规则第一条在这里可见 */}
      <SectionCard
        title="逐行数量与可取消量"
        description="可取消量 = 原量 − 已发货数量。已发货数量不可取消，只能转售后（11 售后未开发）。"
      >
        {detail.lines.length === 0 ? (
          <EmptyState title="这张取消单还没有明细行" description="明细行在申请时写入。" />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground border-b text-left text-xs">
                  <th className="p-2">SKU</th>
                  <th className="p-2">原量</th>
                  <th className="p-2">已发货</th>
                  <th className="p-2">申请取消</th>
                  <th className="p-2">已确认取消</th>
                  <th className="p-2">可取消量</th>
                </tr>
              </thead>
              <tbody>
                {detail.lines.map((line) => {
                  const cancelable = cancelableCell(line.cancelableQuantity, verified);
                  return (
                    <tr key={line.id} className="border-b last:border-0">
                      <td className="p-2 font-medium">{line.skuCode || line.orderLineId}</td>
                      <td className="p-2">{quantityCell(line.orderedQuantity, true)}</td>
                      <td className="p-2">
                        {quantityCell(line.shippedQuantityAtRequest, verified)}
                      </td>
                      <td className="p-2">{quantityCell(line.cancelQuantity, true)}</td>
                      <td className="p-2">{quantityCell(line.cancelledQuantity, true)}</td>
                      <td className="p-2">
                        <span
                          className={
                            cancelable.verified ? '' : 'text-amber-600 dark:text-amber-400'
                          }
                        >
                          {cancelable.value}
                        </span>
                        {line.shipBlocking ? (
                          <p className="mt-1 text-xs text-amber-600 dark:text-amber-400">
                            {shipBlocking(line)}
                          </p>
                        ) : null}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* 三、仓库停止作业与释放占用：硬规则第二条 */}
      <SectionCard
        title="仓库停止作业与释放占用"
        description={`两个阶段都完成后才允许确认取消。下游归属 7.2.1.3（warehouse 服务），本模块只登记任务与回执，不跨服务写它的表。`}
      >
        {detail.warehouseTasks.length === 0 ? (
          <EmptyState
            title="没有仓库任务"
            description="取消申请时会同步下达停止作业与释放占用两条任务。"
          />
        ) : (
          <ul className="space-y-2">
            {detail.warehouseTasks.map((task) => (
              <li
                key={task.id}
                className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-3 text-sm"
              >
                <div>
                  <span className="font-medium">
                    {task.warehouseRef} · {holdTaskStageLabels[task.taskStage] ?? task.taskStage}
                  </span>
                  <p className="text-muted-foreground text-xs">
                    目标 {quantityCell(task.targetQuantity, true)} · 已完成{' '}
                    {quantityCell(task.completedQuantity, true)} · {task.targetApi}
                  </p>
                  {task.resultNote ? <p className="mt-1 text-xs">{task.resultNote}</p> : null}
                  {task.failureReason ? (
                    <p className="mt-1 text-xs text-amber-600 dark:text-amber-400">
                      {task.failureReason}
                    </p>
                  ) : null}
                </div>
                <Badge tone={task.status === 'COMPLETED' ? 'success' : 'neutral'}>
                  {holdTaskStatusLabels[task.status] ?? task.status}
                </Badge>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* 四、还差什么才能确认 */}
      <SectionCard title="确认前置条件" description={blockerText(blocked)}>
        {blocked.length === 0 && h.status !== 'CONFIRMED' ? (
          <p className="text-sm text-emerald-600 dark:text-emerald-400">
            仓库与平台的回执都已确认，可以推进到「已确认」。
          </p>
        ) : null}
        {warnings && warnings.length > 0 ? (
          <p className="mt-3 flex items-start gap-2 text-xs text-amber-600 dark:text-amber-400">
            <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{warningText(warnings)}</span>
          </p>
        ) : null}
      </SectionCard>

      {/* 五、平台回写 */}
      <SectionCard
        title="平台回写任务"
        description="把取消同步回平台。下游归属 2.5.1.1（channel 服务）。手工单不经过平台，没有回写任务。"
      >
        {!detail.writebackTasks || detail.writebackTasks.length === 0 ? (
          <EmptyState
            title="没有平台回写任务"
            description="手工单不走平台；平台单在申请时会自动下达一条回写任务。"
          />
        ) : (
          <ul className="space-y-2">
            {detail.writebackTasks.map((task) => (
              <li
                key={task.id}
                className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-3 text-sm"
              >
                <div>
                  <span className="font-medium">
                    {task.platformCode || '—'} ·{' '}
                    {changeKindLabels[task.changeKind] ?? task.changeKind}
                  </span>
                  <p className="text-muted-foreground text-xs">
                    平台单号 {task.platformOrderRef || '—'} · 尝试 {task.attemptCount} 次 ·{' '}
                    {task.ownerModule}
                  </p>
                  {task.lastError ? (
                    <p className="mt-1 text-xs text-amber-600 dark:text-amber-400">
                      {task.lastError}
                    </p>
                  ) : null}
                </div>
                <Badge tone={task.status === 'CONFIRMED' ? 'success' : 'neutral'}>
                  {writebackStatusLabels[task.status] ?? task.status}
                </Badge>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* 六、时间线 */}
      <SectionCard
        title="处理时间线"
        description="事件流不可改写：它是「谁在什么时候把这张单推到哪一步」的举证凭据。"
      >
        {detail.timeline.length === 0 ? (
          <EmptyState title="还没有事件" description="申请提交后会记录第一条事件。" />
        ) : (
          <ol className="space-y-2 text-sm">
            {detail.timeline.map((event) => (
              <li key={event.id} className="border-l-2 pl-3">
                <p className="font-medium">
                  {event.eventType}
                  {event.toStatus ? ` → ${statusLabel(event.toStatus)}` : ''}
                </p>
                {event.detail ? (
                  <p className="text-muted-foreground text-xs">{event.detail}</p>
                ) : null}
                <p className="text-muted-foreground text-xs">{event.occurredAt}</p>
              </li>
            ))}
          </ol>
        )}
      </SectionCard>

      {/* 七、上游可用性：未接上的能力明说，不让界面上出现「0」冒充答案 */}
      {caps && caps.unavailableCapabilities.length > 0 ? (
        <SectionCard title="上游可用性" description="本模块哪些能力还没接上，归谁负责。">
          <ul className="space-y-2 text-sm">
            {caps.unavailableCapabilities.map((item) => (
              <li key={item.code} className="flex items-start gap-2">
                <CircleSlash className="text-muted-foreground mt-0.5 h-4 w-4 shrink-0" />
                <div>
                  <p className="font-medium">{item.code}</p>
                  <p className="text-muted-foreground text-xs">
                    归属 {item.ownerModule} —— {item.description}
                  </p>
                </div>
              </li>
            ))}
          </ul>
        </SectionCard>
      ) : null}
    </div>
  );
}
