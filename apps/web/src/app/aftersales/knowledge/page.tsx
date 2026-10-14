'use client';

import { KnowledgeWorkspace } from '@/components/catalog/knowledge-workspace';

/**
 * 11.4.2.2 客服知识库与话术（L3 锚点 `/aftersales/disputes` 下的客诉工单子域）。
 *
 * <p>路由是 `/aftersales/knowledge`，与 `/aftersales`（售后工单，11.4.2.1，**未开发**）
 * **并列**而不是挂在它下面：本模块的上游是 3.4.2.2 文案资产、下游是 15 客服消息发送，
 * 两者都与「工单」这一实体无关 —— 把它挂在工单页下面会让「话术依赖工单」这个错误印象成立，
 * 而实际上工单那边挂了哪条话术是完全独立的。
 *
 * <p>与 `/aftersales/returns`（11.2 退货管理）、`/aftersales/refunds`（11.3 退款与换货）
 * 同属售后域但语义不同：那两个管的是**单据**，这里管的是**内容**。
 *
 * <p>页面上必须写清的四条纪律：
 *
 * <ul>
 *   <li><b>话术需与平台规则版本挂钩。</b>平台政策变化时能定位哪些话术需要更新 ——
 *       这是蓝图对本模块点名的硬要求，本页的「平台规则版本」与「待复核任务」两个页签就是它的落地。</li>
 *   <li><b>命中率的分子是「被引用过的启用话术条数」，不是引用次数。</b>
 *       所以「多少条话术根本没人用」永远看得见 —— 这恰恰是要回答的问题。</li>
 *   <li><b>「被驳回」不是「没效果」，而是「这套话术是错的」。</b>比没人用更该优先处理，
 *       页面上单独标红并可一键筛出。</li>
 *   <li><b>多语言版本的占位符必须与源语言一致。</b>漏一个会在答复里出现裸 <code>{buyer_name}</code>，
 *       多一个则永远填不上 —— 前端保存前就拦，服务端与 DB 触发器还会各拦一次。</li>
 * </ul>
 */
export default function AftersalesKnowledgePage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">客服知识库与话术</h1>
        <p className="text-muted-foreground text-copy-13">
          维护客服一键引用的话术：常见问题标准答法、平台政策说明、退换货话术，含多语言版本。
          <strong>话术与平台规则版本挂钩</strong> —— 把某一版政策标记为「已被接替」时，
          挂在它上面的话术会自动各生成一条待复核任务，并点名是哪一版接替了它。 每次引用都真实落库，
          <strong>命中率统计</strong>从明细行现算：
          分子是「被引用过的启用话术条数」，分母是「启用话术总条数」，
          所以「多少条话术根本没人用」永远看得见。被引用过但<strong>被驳回</strong>的话术会单独标红
          —— 那不是「没效果」，是「这套话术是错的」，比没人用更该优先处理。
        </p>
      </header>

      <KnowledgeWorkspace />
    </div>
  );
}
