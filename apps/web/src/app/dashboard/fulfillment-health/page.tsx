'use client';

import { FulfillmentHealthWorkspace } from '@/components/dashboard/fulfillment-health-workspace';

/**
 * 1.1.2.3 履约健康。
 *
 * <p>蓝图（`docs/product/capability-blueprint/01-dashboard-and-portal.md` 第 110–115 行）：
 * 「展示履约链路的健康水位：待发货积压、平均出库时长、超时发货率、物流异常件占比、
 * 平台绩效指标（ODR、迟发率、有效追踪率）。……输出：<b>健康度卡片 + 红灯项明细跳转</b>。」
 *
 * <p><b>路由说明</b>：本模块在台账里的 L3 锚点路由是「—」（1.1.2 下的三格都还没定锚点路由），
 * 按仓内惯例做成**真实可访问的工作区**，页面入口挂在工作台区
 * （{@code /dashboard/fulfillment-health}）。它<b>不改</b> {@code apps/web/src/app/page.tsx} 首页 ——
 * 首页是人工与别的模块共用的。
 *
 * <p><b>这是一个真实页面而不是占位页</b>：六张健康度卡片全部来自后端只读投影
 * （trade 服务的 {@code /trade/fulfillment/health/*} + warehouse 服务的出库时效报表），
 * 红灯项明细可按类型筛选、可翻页、每条都能跳到归属系统的处理页，
 * 且页面**没有任何写动作**（卷 01 的红线：工作台只读 + 跳转）。
 */
export default function FulfillmentHealthPage() {
  return <FulfillmentHealthWorkspace />;
}
