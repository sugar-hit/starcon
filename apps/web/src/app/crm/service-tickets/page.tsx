'use client';

import { ServiceTicketListWorkspace } from '@/components/crm/service-ticket-list-workspace';

/**
 * 15.4.1.1 工单列表（蓝图 15 卷 L3 锚点 15.4.1 下的第一格）。
 *
 * <p>路由是 `/crm/service-tickets`，与 `/crm/messages`（15.1.1.1 消息收件箱）、
 * `/crm/knowledge`（15.4.2.1 客服知识库）、`/crm/care-reminders`（15.5.2.1 关怀与催办）**并列**。
 *
 * <p><b>页面上必须讲清的两条</b>：
 *
 * <ul>
 *   <li><b>SLA 计时按营业时间而非自然时间</b> —— 夜里积压的工单用自然时间算会立刻全部逾期，
 *       造成误判。页面显示的剩余量是**营业时间口径**，并且把当前生效的营业时间配置
 *       （工作日区间 + 时区 + 节假日例外）摆在同一页上 ——
 *       口径看不见，数字就没人敢用。</li>
 *   <li><b>工单事实不在本服务</b> —— 工单由工单管理服务（11.4.2.1）拥有，
 *       本页只经授权只读 API 读；工单处理（15.4.1.2）**未交付**，
 *       所以页面**不提供**任何处理按钮，也不放一个点不动的入口。</li>
 * </ul>
 */
export default function CrmServiceTicketsPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">工单列表</h1>
        <p className="text-muted-foreground text-copy-13">
          客服工单总览：类型、优先级、状态、SLA 剩余、关联买家与订单、分配客服、转派与升级。
          <strong>SLA 按营业时间计时</strong>
          —— 到期时刻在营业时间轴上累加，而不是「创建时间 + N 小时」，
          所以夜里积压的工单不会一上班就全变成逾期；当前生效的营业时间口径就摆在下方，
          可随时查看与修改。
        </p>
      </header>

      <ServiceTicketListWorkspace />
    </div>
  );
}
