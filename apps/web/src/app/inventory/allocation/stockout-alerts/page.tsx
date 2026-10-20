import { StockoutAlertWorkspace } from '@/components/inventory/stockout-alert-workspace';

/**
 * 7.8.2.2 缺货与饥饿预警 —— L3 路由下的入口。
 *
 * <p>L3「7.8 多平台可售量分摊」的路由是 `/inventory/allocation`，它同时是
 * 7.8.1.1（分配策略配置）的页面路由。本页是同一个工作区在 L3 路由下的第二个入口，
 * 便于从 L3 路径直达「缺货与饥饿预警」，避免与 7.8.1.1 抢同一个路由。
 *
 * <p>两个入口渲染的是**同一个**工作区组件，因此不会出现两份口径 ——
 * 「没货卖」与「没分到货」这两类问题在任何一个入口下都是分开的两张清单。
 */
export default function StockoutAlertUnderL3Page() {
  return <StockoutAlertWorkspace />;
}
