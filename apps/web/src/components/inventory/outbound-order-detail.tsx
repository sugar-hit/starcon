'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { AlertTriangle, ArrowLeft, Clock, Package, RefreshCw } from 'lucide-react';
import { Badge, Button, EmptyState, Notice, PageHeader, SectionCard } from '@starcon/ui';
import type { OutboundOrderDetail } from '../../../../../packages/api-contracts/src/catalog';
import {
  actionButtonText,
  carrierWarning,
  detailErrorText,
  eventText,
  fetchOutboundDetail,
  pieceText,
  quantityCell,
  quantityHint,
  timeText,
  unavailableHint,
} from './outbound-order-detail-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 8.3.1.2 出库单详情工作区。
 *
 * <p>蓝图：「单出库单作业视图：明细行与已拣/已发量、关联销售订单与买家地址、预占引用、
 * 包裹列表、作业时间线。」输出：「详情 + 作业操作入口。」以及本模块被点名的那条关系：
 * 「**详情页串联整条出库链路**，作业人员不需要在多个页面跳。」
 *
 * <p>界面纪律（每一条都对应一个会被误显示的坑）：
 *
 * <ul>
 *   <li>**已拣量显示成灰块，不显示 0。** 8.3.2.1 未交付 → 没有 picking_task 可查。
 *       显示 0 会让作业员以为一件都没拣，然后重复拣一遍 —— 这是本页最贵的错。
 *   <li>**已发量不按拣货进度推算。** 只有后端给了真值（已发货的单）才显示数字，
 *       其余显示「待 8.3.4.1 交付」。
 *   <li>**四段拿不到的能力画成灰块**，写清归属模块。空的包裹列表会被读成「这单没有包裹」。
 *   <li>**操作按钮由后端给**，界面不自己写一套禁用规则 —— 那会与 DB 触发器分叉。
 * </ul>
 */
export function OutboundOrderDetailWorkspace({ orderId }: { orderId: string }) {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');

  const [detail, setDetail] = useState<OutboundOrderDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      setDetail(await fetchOutboundDetail(orderId));
    } catch (e) {
      setError(detailErrorText(e));
    } finally {
      setLoading(false);
    }
  }, [readable, orderId]);

  useEffect(() => {
    void load();
  }, [load]);

  if (!readable) {
    return (
      <EmptyState
        title="没有查看出库单的权限"
        description="需要 warehouse.inventory.read 才能打开出库单详情。"
      />
    );
  }

  if (loading) {
    return (
      <div className="space-y-4">
        <PageHeader title="出库单详情" description="正在加载…" />
        <SectionCard title="加载中">
          <p className="text-sm text-muted-foreground">正在读取出库单详情，请稍候。</p>
        </SectionCard>
      </div>
    );
  }

  if (error || !detail) {
    return (
      <div className="space-y-4">
        <PageHeader title="出库单详情" description="读取失败" />
        <Notice tone="danger" title="读不到这张出库单">
          <p>{error || '出库单详情暂时不可用。'}</p>
          <p className="mt-2 text-xs">
            如果刚打开就报错，多半是这张单不属于当前租户，或它的 id 已经失效。
          </p>
          <div className="mt-3 flex gap-2">
            <Button variant="outline" onClick={() => void load()}>
              <RefreshCw className="mr-2 h-4 w-4" />
              重试
            </Button>
            <Link href="/warehouse/outbound">
              <Button variant="ghost">返回出库单列表</Button>
            </Link>
          </div>
        </Notice>
      </div>
    );
  }

  const carrier = carrierWarning(detail);
  const unavailable = [detail.linkedOrder, detail.shipToAddress, detail.reservationRefs];

  return (
    <div className="space-y-4">
      <PageHeader
        title={`出库单 ${detail.orderCode}`}
        description={`${detail.sourceTypeLabel} · ${detail.warehouseName} · ${detail.statusLabel}`}
        actions={
          <div className="flex gap-2">
            <Link href="/warehouse/outbound">
              <Button variant="outline">
                <ArrowLeft className="mr-2 h-4 w-4" />
                返回列表
              </Button>
            </Link>
            <Button variant="ghost" onClick={() => void load()}>
              <RefreshCw className="mr-2 h-4 w-4" />
              刷新
            </Button>
          </div>
        }
      />

      {/* ---- 单头 ---- */}
      <SectionCard title="单头">
        <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2 lg:grid-cols-3">
          <Field label="单号" value={detail.orderCode} />
          <Field label="状态" value={detail.statusLabel} />
          <Field label="来源" value={detail.sourceTypeLabel} />
          <Field
            label="关联单号"
            value={
              detail.sourceType === 'TRANSFER'
                ? (detail.sourceTransferCode ?? detail.sourceTransferOrderId ?? '—')
                : (detail.sourceExternalRef ?? '—')
            }
            hint={
              detail.sourceType === 'TRANSFER'
                ? '调拨单（真关联，可跳转）'
                : '外部单号字符串，不是订单详情'
            }
          />
          <Field label="发货仓" value={`${detail.warehouseCode} · ${detail.warehouseName}`} />
          <Field
            label="承运渠道"
            value={detail.carrierCode || '未指定'}
            hint={carrier ?? undefined}
          />
          <Field label="SKU 行数" value={`${detail.skuLineCount} 行`} />
          <Field label="计划件数" value={`${pieceText(detail.totalPieceCount)} 件`} />
          <Field label="创建时间" value={timeText(detail.createdAt)} />
          {detail.shippedAt ? <Field label="发货时间" value={timeText(detail.shippedAt)} /> : null}
          {detail.cancelledAt ? (
            <>
              <Field label="作废时间" value={timeText(detail.cancelledAt)} />
              <Field label="作废原因" value={detail.cancelReason ?? '—'} />
            </>
          ) : null}
        </dl>
      </SectionCard>

      {/* ---- 数量口径：已拣 / 已发，两段独立说明 ---- */}
      <SectionCard title="已拣量与已发量">
        <Notice tone="info" title="这两个数不是一个口径">
          <p>
            已拣量归 8.3.2.1 拣货任务，已发量归 8.3.4.1 发货交接，两者都尚未交付，
            所以多数单据上**拿不到**。拿不到时下面显示灰块与归属模块，**不是 0** ——
            0 会被读成「一件都没拣」。
          </p>
        </Notice>
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          {detail.quantities.map((q) => {
            const cell = quantityCell(q);
            return (
              <div key={q.quantity} className="rounded-lg border p-3">
                <div className="text-xs text-muted-foreground">{q.label}</div>
                <div className="mt-1 text-xl font-semibold">
                  {cell === null ? '待交付' : `${cell} 件`}
                </div>
                {cell === null ? (
                  <p className="mt-1 flex items-start gap-1 text-xs text-muted-foreground">
                    <AlertTriangle className="mt-0.5 h-3 w-3 shrink-0" />
                    {quantityHint(q)}
                  </p>
                ) : (
                  <p className="mt-1 text-xs text-muted-foreground">{q.reason}</p>
                )}
              </div>
            );
          })}
        </div>
      </SectionCard>

      {/* ---- 明细行 ---- */}
      <SectionCard title={`明细行（${detail.skuLineCount} 行 / ${pieceText(detail.totalPieceCount)} 件）`}>
        {detail.lines.length === 0 ? (
          <EmptyState title="这张单没有明细行" description="出库单是计划快照，没有明细就没有可作业的内容。" />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-xs text-muted-foreground">
                <tr>
                  <th className="py-2 pr-3">行号</th>
                  <th className="py-2 pr-3">SKU</th>
                  <th className="py-2 pr-3">计划数量</th>
                  <th className="py-2 pr-3">已拣量</th>
                  <th className="py-2">已发量</th>
                </tr>
              </thead>
              <tbody>
                {detail.lines.map((line) => (
                  <tr key={line.lineNo} className="border-t">
                    <td className="py-2 pr-3">{line.lineNo}</td>
                    <td className="py-2 pr-3">
                      <div>{line.skuCode}</div>
                      <div className="text-xs text-muted-foreground">{line.skuName}</div>
                    </td>
                    <td className="py-2 pr-3">{pieceText(line.plannedQuantity)}</td>
                    {/* 两列都必须显示归属模块，不能显示 0 —— 见类注释。 */}
                    <td className="py-2 pr-3 text-muted-foreground">
                      {line.pickedQuantity === null ? '待 8.3.2.1 交付' : pieceText(line.pickedQuantity)}
                    </td>
                    <td className="py-2 text-muted-foreground">
                      {line.shippedQuantity === null ? '待 8.3.4.1 交付' : pieceText(line.shippedQuantity)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* ---- 拣货交接 ---- */}
      <SectionCard title="拣货交接">
        {detail.pickRequest ? (
          <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
            <Field label="交接量" value={`${detail.pickRequest.lineCount} 行 / ${pieceText(detail.pickRequest.totalPieceCount)} 件`} />
            <Field label="交接时间" value={timeText(detail.pickRequest.requestedAt)} />
            <Field label="交接人" value={detail.pickRequest.requestedByUserId} />
            <Field label="交接说明" value={detail.pickRequest.note || '—'} />
          </dl>
        ) : (
          <p className="text-sm text-muted-foreground">
            尚未生成拣货任务（这张单仍是待拣）。交接量是**请求量快照**，不是实拣量。
          </p>
        )}
      </SectionCard>

      {/* ---- 作业时间线 ---- */}
      <SectionCard title={`作业时间线（${detail.timelineEventCount} 条）`}>
        {detail.timeline.length === 0 ? (
          <EmptyState title="还没有作业留痕" description="建单即会写入第一条事件；这里是空说明数据有问题。" />
        ) : (
          <ol className="space-y-3">
            {detail.timeline.map((e) => (
              <li key={e.id} className="flex gap-3">
                <Clock className="mt-1 h-4 w-4 shrink-0 text-muted-foreground" />
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2 text-sm">
                    <Badge tone="neutral">{e.eventTypeLabel}</Badge>
                    <span>{eventText(e)}</span>
                    <span className="text-xs text-muted-foreground">{timeText(e.occurredAt)}</span>
                  </div>
                  <p className="mt-1 text-xs text-muted-foreground">{e.detail}</p>
                </div>
              </li>
            ))}
          </ol>
        )}
      </SectionCard>

      {/* ---- 拿不到的四段：灰块 + 归属模块 ---- */}
      <SectionCard title="关联对象">
        <div className="grid gap-3 sm:grid-cols-2">
          {unavailable.map((link) => (
            <div key={link.label} className="rounded-lg border border-dashed p-3">
              <div className="flex items-center gap-2 text-sm">
                <Package className="h-4 w-4 text-muted-foreground" />
                <span>{link.label}</span>
                <Badge tone="neutral">{link.ownerModule ?? '本服务'}</Badge>
              </div>
              <p className="mt-1 text-xs text-muted-foreground">{unavailableHint(link)}</p>
            </div>
          ))}
          {detail.packages.map((link, i) => (
            <div key={`pkg-${i}-${link.label}`} className="rounded-lg border border-dashed p-3">
              <div className="flex items-center gap-2 text-sm">
                <Package className="h-4 w-4 text-muted-foreground" />
                <span>{link.label}</span>
                <Badge tone="neutral">{link.ownerModule ?? '本服务'}</Badge>
              </div>
              <p className="mt-1 text-xs text-muted-foreground">{unavailableHint(link)}</p>
            </div>
          ))}
        </div>
      </SectionCard>

      {/* ---- 作业操作入口：由后端按转换矩阵给出，界面不自己写禁用规则 ---- */}
      <SectionCard title="作业操作入口">
        {detail.actions.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            {detail.terminal
              ? `这张单已${detail.statusLabel}，终态不再接受状态推进。`
              : '当前状态没有合法的下一步。'}
          </p>
        ) : (
          <div className="space-y-2">
            {carrier ? (
              <p className="text-xs text-muted-foreground">{carrier}</p>
            ) : null}
            {detail.actions.map((a) => (
              <div key={a.toStatus} className="flex flex-wrap items-center gap-2 border-t pt-2 first:border-0 first:pt-0">
                <Button variant="outline" size="sm" disabled title="状态推进在出库单列表页执行">
                  {actionButtonText(a)}
                </Button>
                <span className="text-xs text-muted-foreground">{a.note}</span>
                <Badge tone="neutral">{a.ownerModule}</Badge>
                {a.requiresCarrier ? (
                  <span className="text-xs text-amber-600">需要承运渠道</span>
                ) : null}
              </div>
            ))}
            <p className="text-xs text-muted-foreground">
              状态推进在出库单列表页执行；这里只列出当前状态的合法去向与前置条件。
            </p>
          </div>
        )}
      </SectionCard>
    </div>
  );
}

function Field({
  label,
  value,
  hint,
}: {
  label: string;
  value: string;
  hint?: string | undefined;
}) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className={hint ? 'text-sm' : 'text-sm font-medium'}>{value}</dd>
      {hint ? <p className="text-xs text-muted-foreground">{hint}</p> : null}
    </div>
  );
}