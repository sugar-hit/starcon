'use client';

import { MessageInboxWorkspace } from '@/components/crm/message-inbox-workspace';

/**
 * 15.1.1.1 消息收件箱（蓝图 15 卷 L3 锚点 15.1.1 下的消息子域）。
 *
 * <p>路由是 `/crm/messages`，与 `/crm/knowledge`（15.4.2.1 客服知识库）、
 * `/crm/review-requests`（15.2.2.1 索评）、`/crm/review-rules`（15.2.2.2 索评规则）**并列**：
 * 本模块管**收到的消息**，那三个分别管知识内容与索评触达。
 *
 * <p><b>页面上必须讲清的四条</b>：
 *
 * <ul>
 *   <li><b>消息必须关联到买家与订单</b>：买家标识与订单标识在数据库层就是 NOT NULL + CHECK，
 *       一条没有买家、没有订单的消息**存不进去** —— 登记表单把这两个框标成必填，
 *       并写清「留空以后再补」在客服场景里等于「永远补不上」。</li>
 *   <li><b>订单上下文查不到时显示「不可用 + 原因」</b>，**不显示空白** ——
 *       空白会被客服读成「这一单没有信息」，而不是「我们没查到」。</li>
 *   <li><b>平台消息同步未接入</b>：平台侧买家站内信适配器还没有，
 *       同步入口会返回 <strong>409</strong> 且一条数据都不写；
 *       页面**不提供**「同步平台消息」按钮 —— 一个点了就成功的假按钮会让运营
 *       以为平台消息已经进来了，而现场一条真实消息都还没有。</li>
 *   <li><b>下游未交付就不给假入口</b>：15.1.1.2 回复、15.1.1.3 分类、11 售后（转售后）、
 *       15.4 工单都没有可调的下游 API，页面只提供「登记处置意图」，
 *       并写明它**不会**建任何售后单或工单。</li>
 * </ul>
 */
export default function CrmMessagesPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">消息收件箱</h1>
        <p className="text-muted-foreground text-copy-13">
          集中展示各店铺的<strong>平台站内信与买家消息</strong>：店铺、买家、主题、正文、附件、
          平台时间、收发方向、已读状态与关联订单，支持按
          <strong>店铺 / 类型 / 优先级 / 未读</strong>筛选与批量处理。{' '}
          <strong>消息必须关联到买家与订单</strong> —— 没有它们，客服看到消息还要去猜是哪一单，
          所以这两项在数据库层就是必填。 关联订单的上下文来自订单服务，
          <strong>查不到时会明确显示「不可用 + 原因」</strong>，而不是一片空白。
        </p>
      </header>

      <MessageInboxWorkspace />
    </div>
  );
}
