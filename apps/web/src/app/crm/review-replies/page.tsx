'use client';

import { ReviewReplyWorkspace } from '@/components/crm/review-reply-workspace';

/**
 * 15.2.1.2 评价回复（蓝图 15 卷 L3 锚点 15.2.1 下的评价子域）。
 *
 * <p>路由是 `/crm/review-replies`，与 `/crm/reviews`（15.2.1.1 评价列表）、
 * `/crm/review-requests`（15.2.2.1 索评）、`/crm/review-rules`（15.2.2.2 索评规则）
 * **并列**：本模块管的是「**已经收到的评价怎么回**」，
 * 与「收到的评价长什么样」「要不要向买家发起索评」是两件事。
 *
 * <p><b>页面上必须讲清的四条</b>：
 *
 * <ul>
 *   <li><b>差评回复是转化机会也是合规风险</b>：页面第一块是<strong>能力边界</strong>与
 *       <strong>平台措辞规范</strong>，不是一只可以随手点的「回复」按钮 ——
 *       平台政策会变，所以规范与模板都带<strong>版本与生效时间</strong>，
 *       回复记录追到当时用的那一版。</li>
 *   <li><b>模板 / 多语言 / 批量 / 两种形态都真的做</b>：一个模板 = 一个版本；
 *       多语言条目逐条列出来；批量按「同类」真的筛、真的逐条落库；
 *       公开回复与卖家评论追加是两个不同的形态（不是布尔开关）。</li>
 *   <li><b>平台投递未接入</b>：投递端点明确答「未接入」（409）且<strong>不写假回执</strong>。
 *       一个点了就返回成功的假按钮会让运营以为「评价已经回复给买家了」。</li>
 *   <li><b>差评处理（15.2.1.3）未交付</b>：本模块<strong>能</strong>对差评回复
 *       （那是回复的职责），但<strong>不</strong>实现差评的专门处理流程
 *       （申诉 / 补偿 / 升级）—— 那些在能力声明里显式标成 unavailable。</li>
 * </ul>
 */
export default function CrmReviewRepliesPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">评价回复</h1>
        <p className="text-muted-foreground text-copy-13">
          回复评价：<strong>公开回复 + 部分平台的卖家评论追加</strong>，支持
          <strong>模板</strong>（一个模板 = 一个版本，带生效时间）、<strong>多语言</strong>、
          <strong>批量回复同类评价</strong>。
          <strong>差评回复是转化机会也是合规风险</strong> —— 所以模板必须符合
          <strong>平台措辞规范</strong>：不合规的正文发不出去，回复记录也追得到
          当时用的模板版本与规范版本。
        </p>
      </header>

      <ReviewReplyWorkspace />
    </div>
  );
}
