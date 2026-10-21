import { InventoryAdjustmentWorkspace } from '@/components/inventory/inventory-adjustment-workspace';

/**
 * 7.4.1.1 调整单管理。
 *
 * <p>蓝图：「**发起库存调整**（盘盈盘亏、损坏、报废、错发纠正、平台仓差异），填写原因与凭证，
 * **支持批量调整**。」输出：「调整单（草稿/待审/已批/已执行）；库存分录（反向或正向）。」
 * 以及红线：「**调整必须走单据**，不允许在明细页直接改余额——没有单据就没有责任人与原因。」
 *
 * <p>这是一个真实工作区而不是占位页：批量建单、原因码受控词表、凭证附件（catalog 媒体库的
 * 逻辑引用）、乐观锁（expected_version）、状态机（草稿 → 待审 → 已批 → 已过账）、
 * append-only 审计时间线都落到 PostgreSQL 16，且「未审批不得入账」由数据库触发器强制。
 *
 * <p>路由带 bucket 定位参数（{@code skuId / warehouseId / locationId / lotId}）：那是 7.1.1.2
 * 库存明细「发起调整」行内入口的契约地址 —— 从某一行点进来时直接定位到那批货，
 * 而不是回到一个「从哪开始」的列表页。
 */
export default function InventoryAdjustmentPage({
  searchParams,
}: {
  searchParams: Record<string, string | string[] | undefined>;
}) {
  const one = (value: string | string[] | undefined) =>
    Array.isArray(value) ? (value[0] ?? '') : (value ?? '');
  return (
    <InventoryAdjustmentWorkspace
      focus={{
        skuId: one(searchParams?.skuId),
        warehouseId: one(searchParams?.warehouseId),
        locationId: one(searchParams?.locationId),
        lotId: one(searchParams?.lotId),
      }}
    />
  );
}
