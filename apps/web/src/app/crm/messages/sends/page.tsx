'use client';

import { MessageSendWorkspace } from '@/components/crm/message-send-workspace';

/**
 * 15.1.1.2 消息发送（蓝图 15 卷 L3 锚点 15.1.1 下的消息子域）。
 *
 * <p>路由是 `/crm/messages/sends`，与 15.1.1.1 的 `/crm/messages`（消息收件箱）
 * **并列**：那是「收到的消息」，这里是「发出去的那一条」。
 *
 * <p><b>页面上必须讲清的四条</b>：
 *
 * <ul>
 *   <li><b>🔴 发送失败保留原消息状态</b>：「已回复」是由「存在一条已发出的发送记录」
 *       <strong>推导</strong>出来的，不是写在源消息上的一列 —— 所以发送失败时
 *       那条消息<strong>仍然是未回复</strong>，客服不会看到「已回复」而实际没发出去。</li>
 *   <li><b>「没回过」与「回过但失败了」分开显示</b>：混成一句「操作失败」
 *       会让人再回一次，而重复回复买家的代价是平台违规与买家投诉。</li>
 *   <li><b>平台侧站内信发送接口未接入</b>：投递只允许<strong>人工交接</strong>
 *       （在平台侧实际发出后回登记结果与平台回执）；页面<strong>不提供</strong>
 *       「发到平台」按钮 —— 一个点了就成功的假按钮会让运营以为买家已经收到回复了，
 *       而现场一条都没发出去。</li>
 *   <li><b>下游未交付就不给假入口</b>：15.1.1.3 分类、15.5.1 自动回复、11 售后、
 *       15.4 工单都没有可调的下游 API，页面不提供它们的入口。</li>
 * </ul>
 */
export default function CrmMessageSendsPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">消息发送</h1>
        <p className="text-muted-foreground text-copy-13">
          回复买家消息：<strong>调用模板、手工编辑、附件支持、发送记录、失败重试</strong>，
          并支持对同类问题做<strong>批量相同回复</strong>。每一次发送都带幂等键 ——
          重放同一条不会给买家发两遍。
          <strong>发送失败要保留原消息状态</strong>：「已回复」是由「存在一条已发出的发送记录」
          推导出来的，失败时那条消息<strong>仍然是未回复</strong>，客服不会看到「已回复」
          而实际没发出去。 平台侧站内信发送接口<strong>未接入</strong>，投递走
          <strong>人工交接</strong>：在平台侧实际发出后回登记结果与平台回执。
        </p>
      </header>

      <MessageSendWorkspace />
    </div>
  );
}
