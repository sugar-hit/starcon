'use client';

import { ReviewRequestWorkspace } from '@/components/crm/review-request-workspace';

/**
 * 15.2.2.1 Request a Review（索评）（蓝图 15 卷 L3 锚点 15.2.2 下的索评子域）。
 *
 * <p>路由是 `/crm/review-requests`，与 `/crm/knowledge`（15.4.2.1 客服知识库）**并列**：
 * 本模块的上游是 10.3.2.2 签收、下游是 15.2.1.1 评价，与「客服知识」无关。
 *
 * <p><b>页面上必须讲清的四条</b>：
 *
 * <ul>
 *   <li><b>候选集来自真实签收事实</b>：已签收订单集合读的是 upstream channel 的
 *       确认单清单（10.3.2.2，端口 8083，经授权 HTTP API），本服务不造签收事实。</li>
 *   <li><b>索评频次必须受限</b>：频次上限、冷却窗口、每买家上限是<b>数据库层</b>的硬闸，
 *       超限的索评请求被<b>拒绝并留下原因</b>（列表里能筛出来），不是界面上一行提示。</li>
 *   <li><b>平台侧索评接口未接入</b>：发送通道只允许「人工交接」。
 *       一个点了就返回成功的假按钮会让运营以为买家已经被索评过 ——
 *       而过度索评的代价正是买家投诉与平台处罚。</li>
 *   <li><b>评价只落回收桶</b>：下游 15.2.1.1 评价列表未交付，页面不提供回复 / 申诉 / 标签，
 *       只登记「收到的评价原文 + 关联到索评请求」。</li>
 * </ul>
 */
export default function CrmReviewRequestsPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">索评（Request a Review）</h1>
        <p className="text-muted-foreground text-copy-13">
          按规则向<strong>已签收订单的买家</strong>发起索评，支持按
          <strong>店铺 / 时间 / 物流状态</strong>批量执行。{' '}
          <strong>索评频次必须受限</strong>：每买家上限、每订单上限与冷却窗口是数据库层的硬闸，
          超限的索评请求会被<strong>拒绝并留下原因</strong>，而不是悄悄发出去 ——
          过度索评会被买家投诉甚至平台处罚。
        </p>
      </header>

      <ReviewRequestWorkspace />
    </div>
  );
}
