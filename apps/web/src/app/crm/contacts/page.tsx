'use client';

import { CustomerContactWorkspace } from '@/components/crm/customer-contact-workspace';

/**
 * 15.3.2.2 接触记录（蓝图 15 卷 L3 锚点 15.3.2 下的接触记录子域）。
 *
 * <p>路由是 `/crm/contacts`，与 `/crm/tags`（15.3.2.1 客户标签）、
 * `/crm/messages`（15.1.1.1 消息收件箱）、`/crm/emails`（15.1.2.1 买家邮件）**并列**。
 *
 * <p>⚠️ 刻意**不**挂在 `/crm/customers` 下：15.3.1.1 客户列表**未交付**，
 * 本仓没有买家档案主数据 —— 客户标识是**逻辑引用**。
 * 为本模块造一个客户列表页会**越权**（那是 15.3.1.1 的职责），
 * 而且会让「客户主档已存在」这个假结论成立。
 *
 * <p><b>页面上必须讲清的四条</b>：
 *
 * <ul>
 *   <li><b>接触记录是自动化的豁免依据</b>：曾明确表示「不要再联系我」的客户，
 *       营销触达必须被排除。退订要带依据、撤销要带原因、两者都留痕，
 *       判定结果（「可以营销 / 不可营销」）直接摆在时间线最上方。</li>
 *   <li><b>退订不是一种接触类型</b>：它是一个合规状态，只能走显式动作改 ——
 *       写一条普通接触记录改不了它。</li>
 *   <li><b>各模块自动写入是真的</b>：15.1 消息与 15.1.2.1 邮件会自动落成接触记录；
 *       11 售后在 trade 服务（跨服务），其自动写入<b>尚未接线</b>，页面不假装已打通。</li>
 *   <li><b>本模块不发任何触达</b>：15.5.2.1 关怀与催办未交付，
 *       页面不提供任何看起来能点的「群发」入口。</li>
 * </ul>
 */
export default function CrmContactsPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">接触记录</h1>
        <p className="text-muted-foreground text-copy-13">
          记录我们与客户的<strong>每一次</strong>接触（客服通话、消息、邮件、营销触达、售后沟通），
          支持时间线检索。
          <strong>接触记录是自动化的豁免依据</strong>
          —— 曾明确表示「不要再联系我」的客户，营销触达必须被排除：
          退订要带依据、撤销要带原因、两者都留痕。
        </p>
      </header>

      <CustomerContactWorkspace />
    </div>
  );
}
