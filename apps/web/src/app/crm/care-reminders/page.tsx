'use client';

import { CareReminderWorkspace } from '@/components/crm/care-reminder-workspace';

/**
 * 15.5.2.1 关怀与催办（蓝图 15 卷 L3 锚点 15.5.2 下的触达子域）。
 *
 * <p>路由是 `/crm/care-reminders`，与 `/crm/contacts`（15.3.2.2 接触记录）、
 * `/crm/tags`（15.3.2.1 客户标签）、`/crm/messages`（15.1.1.1 消息收件箱）**并列**。
 *
 * <p><b>页面上必须讲清的四条</b>：
 *
 * <ul>
 *   <li><b>遵守频次上限与退订要求</b>：退订名单的唯一事实源是 15.3.2.2 的退订记录，
 *       本模块**只读不写**；被退订闸 / 频次闸挡下的触达**逐条显示明确原因**，
 *       不是静默跳过。</li>
 *   <li><b>催付有合规边界</b>：催付规则**必须按平台配置**，且平台必须在白名单里；
 *       白名单来自服务端能力声明，页面不自己维护一份。</li>
 *   <li><b>催评只对已签收订单成立</b>，未签收不生成催评任务。</li>
 *   <li><b>不重造发送</b>：平台侧发送接口未接入 ⇒ 页面只提供「人工交接登记」，
 *       不提供任何看起来能点的「一键群发」入口。</li>
 * </ul>
 */
export default function CrmCareRemindersPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">关怀与催办</h1>
        <p className="text-muted-foreground text-copy-13">
          主动触达买家：发货通知、物流提醒、催付、催评、售后进度通知、关怀邮件， 按规则定时发送。
          <strong>遵守频次上限与退订要求</strong>
          —— 退订名单的唯一事实源是接触记录模块的退订记录，本模块只读不写；
          被挡下的触达会逐条写明原因，不会静默跳过。
          <strong>催付有合规边界</strong>
          —— 催付规则必须按平台配置，不同平台配不同的频次上限与话术。
        </p>
      </header>

      <CareReminderWorkspace />
    </div>
  );
}
