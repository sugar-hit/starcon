'use client';

import { CustomerMergeWorkspace } from '@/components/crm/customer-merge-workspace';

/**
 * 15.3.1.3 跨店合并（蓝图 15 卷 L3 锚点 15.3.1「客户主档」下的跨店合并子域）。
 *
 * <p>路由是 `/crm/customer-merge`，与 `/crm/customers`（15.3.1.1 客户列表）、
 * `/crm/tags`（15.3.2.1 客户标签）、`/crm/contacts`（15.3.2.2 接触记录）**并列**：
 * 本模块回答的是「这两个身份是不是同一个人、要不要合并、能不能拆开」，
 * 与「这位客户是谁 / 属于哪一类」不是一件事。
 *
 * <p><b>页面上必须讲清的四条</b>：
 *
 * <ul>
 *   <li><b>只给建议，不自动合并</b>：生成候选**不会**合并任何客户；
 *       合并只能由人工确认触发，理由必填。</li>
 *   <li><b>置信度要能解释</b>：逐条列出命中了哪个信号、证据强度多少、
 *       比对的是什么（<strong>脱敏</strong>形态）。</li>
 *   <li><b>同名不同人不会进队列</b>：只有姓名相同、地址对不上的那些对子
 *       只在运行台账里计数 —— 那是「同名不同人」那一档。</li>
 *   <li><b>必须可拆分</b>：拆出去之后来源客户行<strong>原样还在</strong>
 *       （合并不曾改动过它）。</li>
 * </ul>
 */
export default function CrmCustomerMergePage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">跨店合并</h1>
        <p className="text-muted-foreground text-copy-13">
          把同一自然人在不同店铺的身份合并到一个逻辑客户。按邮箱 / 电话 / 姓名+地址 / 平台买家标识
          给出候选与置信度，<strong>需人工确认</strong>，合并后<strong>可拆分</strong>。
          <strong>系统只给建议、绝不自动合并</strong> —— 同名不同人、同人不同邮箱这两类错判，
          只能由人确认。
        </p>
      </header>

      <CustomerMergeWorkspace />
    </div>
  );
}
