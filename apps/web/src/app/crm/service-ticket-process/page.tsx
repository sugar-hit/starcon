'use client';

import { ServiceTicketProcessWorkspace } from '@/components/crm/service-ticket-process-workspace';

/**
 * 15.4.1.2 工单处理（蓝图 15 卷 L3 锚点 15.4.1 下的第二格）。
 *
 * <p>路由是 `/crm/service-ticket-process`，与 `/crm/service-tickets`
 * （15.4.1.1 工单列表，<b>只读</b>）**并列但刻意分开** ——
 * 那边回答「有哪些工单」，这边回答「<b>这一张怎么处理</b>」。
 *
 * <p><b>页面上必须讲清的一条</b>：
 *
 * <ul>
 *   <li><b>工单关闭不等于问题解决</b> —— 关闭只表示「不再跟了」，
 *       「实际结果到了没有」（退款到账 / 换货发出 / 买家接受方案）
 *       要由<b>另一个动作</b>（解决确认）回答。页面把「已关闭」与「已确认解决」
 *       做成**两个独立字段**并排显示，就是为了不让这两个结论互相顶替。</li>
 * </ul>
 *
 * <p>页面**不做任何算术**：SLA 剩余、汇总、逾期计数全部由服务端按营业时间轴算好，
 * 页面原样显示 —— 页面再乘一遍自然小时数，那正是要避免的误判。
 */
export default function CrmServiceTicketProcessPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">工单处理</h1>
        <p className="text-muted-foreground text-copy-13">
          处理客服工单：回复、备注、状态流转、关联订单与售后单、解决确认、满意度回收、
          SLA 暂停与恢复。<strong>工单关闭不等于问题解决</strong> ——
          关闭只是「不再跟了」，实际结果（退款到账、换货发出）要另做一次「解决确认」才计为解决，
          所以页面把这两个结论分开摆着。SLA 按营业时间计时，暂停区间不计入消耗。
        </p>
      </header>

      <ServiceTicketProcessWorkspace />
    </div>
  );
}
