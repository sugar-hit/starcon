'use client';

import { NegativeReviewWorkspace } from '@/components/crm/negative-review-workspace';

/**
 * 15.2.1.3 差评处理（蓝图 15 卷 L3 锚点 15.2.1 下的差评子域）。
 *
 * <p>路由是 `/crm/negative-reviews`，与 `/crm/reviews`（15.2.1.1 评价列表）、
 * `/crm/review-replies`（15.2.1.2 评价回复）、`/crm/review-requests`（15.2.2.1 索评）、
 * `/crm/review-rules`（15.2.2.2 索评规则）**并列**：本模块管的是
 * 「**收到差评之后怎么处理它**」，与「收到的评价长什么样」「怎么公开回复」
 * 「要不要向买家发起索评」是三件不同的事。
 *
 * <p><b>页面上必须讲清的四条</b>：
 *
 * <ul>
 *   <li><b>原因分类与「能不能申诉」真的可配</b>：差评阈值、平台是否支持申诉、
 *       申诉配额、以及逐（平台 × 原因）的规则，都是<strong>可配置的数据</strong>，
 *       不写死在代码里。没有平台策略就<strong>建不了处理单</strong>。</li>
 *   <li><b>解决差评的正确路径是先解决买家问题再请求修改</b>：服务端<strong>强制</strong>
 *       这个顺序 —— 没走完「联系买家」就请求修改 / 建申诉任务会被拒绝并说明原因，
 *       页面也把入口显式禁用。</li>
 *   <li><b>平台申诉接口未接入</b>：提交端点明确答「未接入」（409）且<strong>不写假回执</strong>。
 *       一个点了就返回成功的假按钮会让运营以为「差评已经处理掉了」。</li>
 *   <li><b>11 售后未交付</b>：转交只落<strong>转交意图</strong>（原因必填），
 *       页面<strong>不显示</strong>任何售后单据 id —— 下游没交付就没有 id。</li>
 * </ul>
 */
export default function CrmNegativeReviewsPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">差评处理</h1>
        <p className="text-muted-foreground text-copy-13">
          差评专项处理：<strong>标记原因</strong>（产品 / 物流 / 服务 / 误判）、
          <strong>判定是否申诉</strong>（部分平台支持，<strong>逐平台可配</strong>）、
          <strong>联系买家解决后请求修改</strong>、<strong>跟踪解决率</strong>。
          <strong>解决差评的正确路径是先解决买家问题再请求修改</strong> ——
          直接申诉容易被驳回且消耗申诉配额，所以这个顺序由服务端强制，页面也把入口禁用。
        </p>
      </header>

      <NegativeReviewWorkspace />
    </div>
  );
}
