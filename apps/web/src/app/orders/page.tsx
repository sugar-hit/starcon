import { AppShell, DomainPlaceholder, getDomainSpec } from '@starcon/ui';

/**
 * 订单中心 — 多平台订单聚合
 *
 * 当前页面状态:scaffold
 * - 展示订单中心应具备的功能清单与上下游数据流
 * - 不展示 mock 数据(用户硬规则:永远不要 mock)
 * - 实施某个 feature 时,修改 packages/ui/src/lib/domain-spec.ts 中对应 status
 */
export default function OrdersPage() {
  const spec = getDomainSpec('orders');
  return (
    <AppShell
      title="订单中心"
      description="集中查看跨平台订单,关注每一笔履约进度。"
    >
      {spec && <DomainPlaceholder spec={spec} />}
    </AppShell>
  );
}