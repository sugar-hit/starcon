'use client';

import { StoreOverviewWorkspace } from '@/components/dashboard/store-overview-workspace';

/**
 * 1.1.2.2 店铺概览（卷 01「工作台与门户」L3 1.1.2 关键指标下的第二格）。
 *
 * <p>蓝图原文：「按店铺横向对比当期表现（**销售额、订单量、利润率、库存天数、广告花费**），
 * 识别掉队店铺。……输出：店铺对比表 / 卡片；点击进入该店铺在 13 的分析页。」
 *
 * <p><b>路由说明</b>：本模块在台账里的 L3 锚点路由是「—」（1.1.2 下的三格都还没定锚点路由），
 * 按仓内惯例做成**真实可访问的工作区**，页面入口挂在工作台区
 * （{@code /dashboard/store-overview}）。它<b>不改</b> {@code apps/web/src/app/page.tsx} 首页 ——
 * 首页是人工与别的模块共用的。
 *
 * <p><b>这是一个真实页面而不是占位页</b>：对比表、掉队店铺清单与五个维度的能力声明
 * 全部来自 reporting 服务的只读投影端点（{@code GET /reporting/store-overview} 与
 * {@code GET /reporting/store-overview/capabilities}），筛选用的可见店铺集合来自
 * {@code GET /reporting/store-overview/shops}，且页面**没有任何写动作**
 * （卷 01 的红线：工作台只读 + 跳转）。
 *
 * <p>🔴 **「销售额」那一格是三个口径的并列**（平台侧 / 我方还原 / 口径差），
 * **不是单一口径** —— 只取其一等于抹掉口径差异。
 * 🔴 **利润率与库存天数两个维度尚未接通**：页面写明**归属模块号**，
 * 让人知道「去哪维护」，而不是给一个 0 或一个编出来的数。
 */
export default function StoreOverviewPage() {
  return <StoreOverviewWorkspace />;
}
