import { AllocationResultWorkspace } from '@/components/inventory/allocation-result-workspace';

/**
 * 7.8.2.1 分摊结果查看 —— L3 路由下的入口。
 *
 * <p>L3「7.8 多平台可售量分摊」的路由是 `/inventory/allocation`，它同时是
 * 7.8.1.1（分配策略配置）的页面路由。本页是同一个工作区在 L3 路由下的第二个入口，
 * 便于从 L3 路径直达「分摊结果查看」，避免与 7.8.1.1 抢同一个路由。
 *
 * <p>两个入口渲染的是**同一个**工作区组件，因此不会出现两份口径。
 */
export default function AllocationResultUnderL3Page() {
  return <AllocationResultWorkspace />;
}
