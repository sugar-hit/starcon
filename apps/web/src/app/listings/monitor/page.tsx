'use client';

import { ListingPerformanceWorkspace } from '@/components/listing-performance/listing-performance-workspace';

/**
 * Listing 表现：L3「4.5.1 表现监控」下的 4.5.1.1。
 *
 * <p>这个 L3 下有两个模块：
 *
 * <ul>
 *   <li><b>4.5.1.1 Listing 表现</b> —— 单个 listing 的表现明细：曝光、点击、转化率、销量、
 *       销售额、Session、退货率、评分变化，支持时间范围对比。<strong>就是本页</strong>。
 *   <li><b>4.5.1.2 关键词排名</b> —— 监控商品在核心关键词下的自然排名与广告排名，
 *       记录历史变化，低于阈值时预警。<strong>尚未交付</strong>。
 * </ul>
 *
 * <p>🔴 页面路由取 <code>/listings/monitor</code>（L3「4.5.1 表现监控」）。
 *
 * <p>🔴 本模块的数据**来自平台而非内部订单**：曝光 / 点击 / Session / 销量 / 销售额 /
 * 退货 / 评分全部是平台回报的口径，与本系统内部订单口径天然不同（平台按平台自然日归集、
 * 含取消与部分退款）。页面顶部固定显示来源标注，每个指标卡也带「平台口径」角标 ——
 * 蓝图加粗句「展示时需标注来源」是硬要求，不是可选项。
 *
 * <p>🔴 下游的 13 产品表现与 14 广告（关联投放）**尚未交付**：工作区把这两段显式列为
 * 「未接入」并写明归属模块，<b>不显示假数据</b>，也不给一个必然 404 的按钮。
 */
export default function ListingPerformancePage() {
  return (
    <div className="space-y-4">
      <ListingPerformanceWorkspace />
    </div>
  );
}
