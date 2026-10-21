'use client';

import { ListingKeywordRankingWorkspace } from '@/components/listing-keyword-ranking/listing-keyword-ranking-workspace';

/**
 * 关键词排名：L3「4.5.1 表现监控」下的 4.5.1.2。
 *
 * <p>这个 L3 下有两个模块：
 *
 * <ul>
 *   <li><b>4.5.1.1 Listing 表现</b> —— 单个 listing 的表现明细：曝光、点击、转化率、销量、
 *       销售额、Session、退货率、评分变化，支持时间范围对比。<strong>是另一个页面</strong>
 *       （{@code /listings/monitor}）。</li>
 *   <li><b>4.5.1.2 关键词排名</b> —— 监控商品在核心关键词下的自然排名与广告排名，
 *       记录历史变化，低于阈值时预警。<strong>就是本页</strong>。</li>
 * </ul>
 *
 * <p>🔴 页面路由取 <code>/listings/keywords</code>（与 4.5.1.1 的
 * <code>/listings/monitor</code> 并列，不动那个页面）。
 *
 * <p>🔴 本模块的排名数据**依赖抓取频率与平台限制**：每个名次都带**抓取时间**与
 * **可能的不准确窗口**。页面顶部固定显示这条说明，明细区与列表行也都带这两个字段 ——
 * 蓝图加粗句「必须明确标注抓取时间与可能的不准确窗口」是**接口契约级**的硬要求，
 * 不是可选的 UI 文案。
 *
 * <p>🔴 下游的 1 工作台预警与 14 广告（关联投放）**尚未交付**：工作区把这两段显式列为
 * 「未接入」并写明归属模块，<b>不显示假数据</b>，也不给一个必然 404 的按钮。
 * 预警本身也只是**记录** —— 本模块不发通知。
 */
export default function ListingKeywordRankingPage() {
  return (
    <div className="space-y-4">
      <ListingKeywordRankingWorkspace />
    </div>
  );
}
