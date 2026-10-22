'use client';

import { ProductReviewWorkspace } from '@/components/crm/product-review-workspace';

/**
 * 15.2.1.1 评价列表（蓝图 15 卷 L3 锚点 15.2.1 下的评价子域）。
 *
 * <p>路由是 `/crm/reviews`，与 `/crm/review-requests`（15.2.2.1 索评）、
 * `/crm/review-rules`（15.2.2.2 索评规则）、`/crm/knowledge`（15.4.2.1 客服知识库）、
 * `/crm/emails`（15.1.2.1 买家邮件）**并列**：本模块管的是
 * 「**各店铺收到的评价长什么样、整体评分在往哪个方向走**」，
 * 与索评（要不要向买家发起索评）、索评规则（怎么发起）是两件事。
 *
 * <p><b>页面上必须讲清的四条</b>：
 *
 * <ul>
 *   <li><b>评分趋势比单条评价更重要</b>：页面第一块是**趋势 / 分布 / 下滑定位**，
 *       连续下滑（三窗单调）会被单独标出，并按<strong>店铺 / 商品 / 变体</strong>三个维度
 *       列出 —— 否则客服看到「评分在跌」却不知道该查产品还是履约。</li>
 *   <li><b>趋势按平台评价时刻分桶</b>：集中补录老评价不会造成假下滑。</li>
 *   <li><b>平台评价同步未接入</b>：同步端点明确答「未接入」（409）并且<b>一行都不写</b>。
 *       一个点了就返回成功的假按钮会让运营以为评价已经进来了。</li>
 *   <li><b>评价回复未交付</b>：15.2.1.2 未交付，页面<strong>不提供</strong>回复入口；
 *       「是否已回复」只反映平台侧是否已有公开回复。</li>
 * </ul>
 */
export default function CrmReviewsPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">评价列表</h1>
        <p className="text-muted-foreground text-copy-13">
          汇总各店铺评价：星级、内容、变体、图片、平台时间、关联订单、是否已回复，
          并给出<strong>店铺评分汇总</strong>（趋势 · 分布 · 下滑定位），
          支持按<strong>星级 / 含图 / 含视频 / 未回复 / 时间范围</strong>筛选与导出。
          <strong>评分趋势比单条评价更重要</strong>：连续下滑要先查是产品问题还是履约问题 ——
          所以下滑会按<strong>店铺 / 商品 / 变体</strong>三个维度分别列出来。
        </p>
      </header>

      <ProductReviewWorkspace />
    </div>
  );
}
