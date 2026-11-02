'use client';

import { CustomerLifecycleWorkspace } from '@/components/crm/customer-lifecycle-workspace';

/**
 * 15.6.2.1 生命周期与流失预警（蓝图 15 卷 L3 锚点 15.6.2「复购与留存」下的生命周期子域）。
 *
 * <p>路由是 `/crm/customer-lifecycle`，与 `/crm/customers`（15.3.1.1 客户列表）、
 * `/crm/cart-recovery`（15.6.2.2 购物车与弃单挽回）**并列**：
 * 客户列表回答「租户有哪些客户」，弃单挽回回答「这位买家加购没走完」，
 * 本模块回答「这位客户买过、**该回购却没回购**」。
 *
 * <p><b>页面上必须讲清的四条</b>：
 *
 * <ul>
 *   <li><b>阈值从哪来</b>：复购周期阈值是<strong>租户数据</strong>（可按类目配、带生效区间），
 *       命中不到任何策略时改用<strong>租户真实订单历史的中位复购间隔</strong>——
 *       页面把这件事写在每一行上，而不是让人以为「按类目生效」。</li>
 *   <li><b>「不可判定」是一等状态</b>：阈值不可判定、客户没有自己的复购间隔基线，
 *       都有<strong>自己的说法</strong>，不画成「正常」。</li>
 *   <li><b>风险分能拆开看</b>：四个因子各带权重、原始值、得分与说明，
 *       不是一个不透明的数字。</li>
 *   <li><b>召回只是建议</b>：归属写明「15.5.2 触达 / 15.6.2.2 弃单挽回」，
 *       页面<strong>没有</strong>「立即发送」—— 触达不是本模块的职责。</li>
 * </ul>
 */
export default function CrmCustomerLifecyclePage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">客户留存看板</h1>
        <p className="text-muted-foreground text-copy-13">
          客户生命周期分层（RFM / 新客-活跃-沉睡-流失-召回）与流失预警：按复购间隔识别
          <strong>本应复购未复购</strong>的客户，给出召回建议与流失风险分。 复购周期阈值
          <strong>可按类目配置</strong>（快消周级、家具月级各配各的）， 没有类目配置时用
          <strong>租户真实订单历史的中位复购间隔</strong>并在每一行标出来。 召回只是建议，
          <strong>触达归 15.5.2</strong>。
        </p>
      </header>

      <CustomerLifecycleWorkspace />
    </div>
  );
}
