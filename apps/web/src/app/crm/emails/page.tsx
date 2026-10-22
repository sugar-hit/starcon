'use client';

import { BuyerEmailWorkspace } from '@/components/crm/buyer-email-workspace';

/**
 * 15.1.2.1 邮件收发（蓝图 15 卷 L3 锚点 15.1.2 下的买家邮件子域）。
 *
 * <p>路由是 `/crm/emails`，与 `/crm/review-requests`（15.2.2.1 索评）、
 * `/crm/review-rules`（15.2.2.2 索评规则）、`/crm/knowledge`（15.4.2.1 客服知识库）**并列**：
 * 本模块的上游是 2 同步与店铺目录、下游是 15.1.1.2 发送，与「索评 / 知识」不是一件事。
 *
 * <p><b>页面上必须讲清的四条</b>：
 *
 * <ul>
 *   <li><b>邮件与站内信要在同一会话里看</b>：同一买家 + 同一订单 + 同一店铺的邮件共用一个
 *       <b>会话键</b>；收到的与发出的在同一个会话视图里，客服一眼能看出「回过没有」——
 *       分开展示会导致重复回复买家。</li>
 *   <li><b>发送失败保留原邮件状态</b>：失败的那一封邮件<b>仍然是「已收到」</b>，
 *       失败原因与平台回执留在发送记录里 —— 不能让客服以为已经回过。</li>
 *   <li><b>平台侧未接入的通道一律不给假入口</b>：平台买家邮件同步与平台邮件发送接口都未接入，
 *       同步端点明确失败且不写数据，发送只允许「人工交接」。</li>
 *   <li><b>站内信那一半暂时看不到</b>：15.1.1.1 未交付，页面如实说明「同源已按会话键标记」，
 *       不假装能看到站内信原文。</li>
 * </ul>
 */
export default function CrmEmailsPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">邮件收发</h1>
        <p className="text-muted-foreground text-copy-13">
          管理买家邮件（Amazon Buyer-Seller Messaging、eBay 邮件等）的
          <strong>收发、附件与模板</strong>。<strong>部分平台要求邮件与站内信在同一会话处理</strong>
          ，所以同一买家、同一订单、同一店铺的邮件 共用一个<strong>会话键</strong>
          ，收到的与发出的在同一个会话视图里 —— 分开展示会导致重复回复买家。
        </p>
      </header>

      <BuyerEmailWorkspace />
    </div>
  );
}
