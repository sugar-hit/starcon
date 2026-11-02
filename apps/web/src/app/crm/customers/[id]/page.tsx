'use client';

import { use } from 'react';
import { CustomerDetailWorkspace } from '@/components/crm/customer-detail-workspace';

/**
 * 15.3.1.2 客户详情 的路由 `/crm/customers/[id]`。
 *
 * <p>蓝图：「客户全档案：基本信息、跨店铺身份关系、订单历史、售后历史、消息历史、评价、
 * 标签、接触记录、风险与价值分层。输入：customer_id；聚合各域数据。输出：详情视图 +
 * 时间线（订单/消息/售后合并时间线）。关系：上游 = 15.3.1.1；下游 = 9 订单、11 售后、
 * 15.1 消息。**统一时间线是客服理解客户的唯一高效方式** —— 跨系统翻找会显著拖慢响应。」
 *
 * <p>与 15.3.1.1 客户列表（`/crm/customers`）是<strong>同一业务对象的两个面</strong>：
 * 那边管「把客户按维度列出来」，这一页管「这一位客户到底怎么了」。
 * 路由是 `/crm/customers/<uuid>`，与列表的 `/crm/customers` 不冲突
 * （`[id]` 只匹配 `/crm/customers/<某一段>`）。
 *
 * <p><b>页面上必须讲清的三条</b>：
 *
 * <ol>
 *   <li><strong>统一时间线是主角</strong>：订单 / 售后 / 消息 / 评价 / 接触记录合并成一条，
 *       客服不必在五个系统之间翻找。</li>
 *   <li><strong>「读不到」与「没有数据」分开</strong>：某个来源读不到时，
 *       页面写清缺什么、归谁，而不是画成「0 条」—— 画成 0 条等于替上游下结论。</li>
 *   <li><strong>未跨店合并</strong>：合并能力归 15.3.1.3；本页只给<strong>线索</strong>
 *       （同名 / 同联系方式指纹），线索<strong>不是</strong>合并结论。</li>
 * </ol>
 */
export default function CrmCustomerDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">客户详情</h1>
        <p className="text-muted-foreground text-copy-13">
          客户全档案：基本信息、跨店铺身份关系、订单 / 售后 / 消息 / 评价 / 接触记录、标签、
          风险与价值分层，以及<strong>合并成一条的统一时间线</strong>。
          <strong>客户是按来源店铺的</strong> —— 跨店合并由 15.3.1.3 负责，本页只给身份线索。
        </p>
      </header>

      <CustomerDetailWorkspace id={id} />
    </div>
  );
}
