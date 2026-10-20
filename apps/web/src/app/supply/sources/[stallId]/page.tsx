import { StallDetailWorkspace } from '@/components/supply/sources';

/**
 * 6.1.2.1 档口详情（`/supply/sources/{stallId}`）。
 *
 * <p>回答的是「这个档口现在是什么样」：档口头 + 它下面的**当前**货源条目（带价格区间与采集时刻）
 * + 采集批次历史（成功与失败都在，失败带原因）。
 *
 * <p>与 6.1.1.2 供应商详情是**两个不同的分区**：那边回答「供应商在我们这儿是什么状态、
 * 表现如何」，这边回答「这个外部店铺的货源池现在长什么样」。
 */
export default async function SupplySourceStallPage({
  params,
}: {
  params: Promise<{ stallId: string }>;
}) {
  const { stallId } = await params;
  return <StallDetailWorkspace stallId={stallId} />;
}
