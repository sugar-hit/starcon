'use client';

import { CartRecoveryWorkspace } from '@/components/crm/cart-recovery-workspace';

/**
 * 15.6.2.2 购物车与弃单挽回（蓝图 15 卷 L3 锚点 15.6.2 下的弃单挽回子域）。
 *
 * <p>路由是 `/crm/cart-recovery`，与 `/crm/review-requests`（15.2.2.1 索评）、
 * `/crm/review-rules`（15.2.2.2 索评规则）、`/crm/knowledge`（15.4.2.1 客服知识库）、
 * `/crm/emails`（15.1.2.1 买家邮件）**并列**：本模块的上游是 2 渠道的独立站购物车事件、
 * 下游是 9 订单的挽回成交，与「索评 / 知识 / 邮件」不是一件事。
 *
 * <p><b>页面上必须讲清的三条</b>：
 *
 * <ul>
 *   <li><b>这是独立站(DTC)特有能力</b>：平台订单无购物车概念 —— 平台店铺的购物车 / 结账事件
 *       在登记入口就被拒，<b>不进弃单清单</b>，所以挽回率报表不会因为混进平台订单而失真。</li>
 *   <li><b>触达须遵守退订与频次上限</b>：已退订的联系人一律不触达（发出去之前就查），
 *       频次上限可配置且有天花板；越界会**明确失败并写明原因**，不是静默跳过。</li>
 *   <li><b>挽回话术需人工确认后启用</b>：草稿 → 已审核 → 已启用；
 *       未启用的话术选不出来（服务端还会再挡一道），改了内容自动回到草稿。</li>
 * </ul>
 */
export default function CrmCartRecoveryPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">购物车与弃单挽回</h1>
        <p className="text-muted-foreground text-copy-13">
          管理<strong>独立站</strong>购物车与弃单挽回：采集加购 / 结账未完成事件，
          按超时策略触发挽回（邮件 / 短信 / 站内信），跟踪挽回率。
          <strong>此项是独立站(DTC)特有能力，平台订单无购物车概念</strong>
          ，所以平台店铺的购物车事件一律不进弃单清单。
        </p>
      </header>

      <CartRecoveryWorkspace />
    </div>
  );
}
