'use client';

import { CustomerListWorkspace } from '@/components/crm/customer-list-workspace';

/**
 * 15.3.1.1 客户列表（蓝图 15 卷 L3 锚点 15.3.1「客户主档」下的客户列表子域）。
 *
 * <p>路由是 `/crm/customers`，与 `/crm/tags`（15.3.2.1 客户标签）、
 * `/crm/contacts`（15.3.2.2 接触记录）、`/crm/knowledge`（15.4.2.1 客服知识库）、
 * `/crm/messages`（15.1.1.1 消息收件箱）**并列**：
 * 本模块的上游是 9 订单，下游是 15.3.1.2 客户详情与 15.3.1.3 跨店合并，
 * 与「标签 / 接触记录」不是一件事。
 *
 * <p><b>页面上必须讲清的四条</b>：
 *
 * <ul>
 *   <li><b>客户是「按来源店铺」的</b>：同一个客户标识在两个来源下是<strong>两行</strong>。
 *       跨店合并（15.3.1.3）未交付，本模块<strong>不提前合并</strong> ——
 *       页面把「来源店铺」摆在最显眼的位置，免得运营以为看漏了人。</li>
 *   <li><b>联系方式默认脱敏</b>：默认显示 a***@***.com / 138****1234；
 *       明文要<strong>另外</strong>的权限码（crm.customers.pii.read），
 *       没有它时开关直接禁用 —— 而不是点了之后报 403。</li>
 *   <li><b>金额按币种分开</b>：累计消费与客单价按币种分行显示，
 *       页面<strong>不做任何前端算术</strong>（比率与客单价都由后端算好）。</li>
 *   <li><b>统计不受分页影响</b>：分层统计来自独立查询，翻页时分布不变。</li>
 * </ul>
 */
export default function CrmCustomersPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">客户列表</h1>
        <p className="text-muted-foreground text-copy-13">
          客户标识（按店铺）、来源店铺、首末购买时间、订单数、累计消费、退款率、客单价、标签、
          联系方式（<strong>默认脱敏</strong>）与风险标记，支持按店铺 / 标签 / 价值分层 / 风险筛选，
          并给出<strong>分层统计</strong>。<strong>客户是按来源店铺的</strong>
          —— 跨店合并（15.3.1.3）尚未交付，同一个人在两个店铺下是两行。
        </p>
      </header>

      <CustomerListWorkspace />
    </div>
  );
}
