'use client';

import { useState } from 'react';
import { InventoryAdjustmentWorkspace } from '@/components/inventory/inventory-adjustment-workspace';
import { InventoryAdjustmentApprovalWorkspace } from '@/components/inventory/inventory-adjustment-approval-workspace';

/**
 * 7.4.1.1 调整单管理 + 7.4.1.2 调整审批。
 *
 * <p>蓝图：「**发起库存调整**（盘盈盘亏、损坏、报废、错发纠正、平台仓差异），填写原因与凭证，
 * **支持批量调整**。」输出：「调整单（草稿/待审/已批/已执行）；库存分录（反向或正向）。」
 * 以及红线：「**调整必须走单据**，不允许在明细页直接改余额——没有单据就没有责任人与原因。」
 *
 * <p>7.4.1.2：「**按金额/数量阈值审批调整单，审批通过后才执行库存分录；支持批量审批与驳回。**」
 * 二者共用蓝图路由 {@code /inventory/adjustments}（台账里两行都是这个路由），
 * 所以本页用页签把「单据」与「审批」分开：单据页负责发起与提交，审批页负责阈值判定与会签。
 *
 * <p>这是一个真实工作区而不是占位页：批量建单、原因码受控词表、凭证附件（catalog 媒体库的
 * 逻辑引用）、乐观锁（expected_version）、状态机（草稿 → 待审 → 已批 → 已过账）、
 * append-only 审计时间线都落到 PostgreSQL 16，且「未审批不得入账」由数据库触发器强制。
 *
 * <p>路由带 bucket 定位参数（{@code skuId / warehouseId / locationId / lotId}）：那是 7.1.1.2
 * 库存明细「发起调整」行内入口的契约地址 —— 从某一行点进来时直接定位到那批货，
 * 而不是回到一个「从哪开始」的列表页。
 */
type Pane = 'orders' | 'approval';

export default function InventoryAdjustmentPage({
  searchParams,
}: {
  searchParams: Record<string, string | string[] | undefined>;
}) {
  const one = (value: string | string[] | undefined) =>
    Array.isArray(value) ? (value[0] ?? '') : (value ?? '');
  const focus = {
    skuId: one(searchParams?.skuId),
    warehouseId: one(searchParams?.warehouseId),
    locationId: one(searchParams?.locationId),
    lotId: one(searchParams?.lotId),
  };
  // ?pane=approval 直接落到审批页（从「待我审批」入口跳过来时用）。
  const initial: Pane = one(searchParams?.pane) === 'approval' ? 'approval' : 'orders';
  const [pane, setPane] = useState<Pane>(initial);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          className={
            pane === 'orders'
              ? 'bg-foreground text-background rounded px-3 py-1.5 text-sm'
              : 'border-control-border rounded border px-3 py-1.5 text-sm'
          }
          onClick={() => setPane('orders')}
        >
          调整单（7.4.1.1）
        </button>
        <button
          type="button"
          className={
            pane === 'approval'
              ? 'bg-foreground text-background rounded px-3 py-1.5 text-sm'
              : 'border-control-border rounded border px-3 py-1.5 text-sm'
          }
          onClick={() => setPane('approval')}
        >
          调整审批（7.4.1.2）
        </button>
      </div>
      {pane === 'orders' ? (
        <InventoryAdjustmentWorkspace focus={focus} />
      ) : (
        <InventoryAdjustmentApprovalWorkspace />
      )}
    </div>
  );
}
