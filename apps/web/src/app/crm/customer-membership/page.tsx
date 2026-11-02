'use client';

import { MembershipWorkspace } from '@/components/crm/customer-membership-workspace';

/**
 * 15.6.1.1 会员与等级（蓝图 15 卷 L3 锚点 15.6.1「会员体系」下的会员与等级子域）。
 *
 * <p>路由是 `/crm/customer-membership`，与 `/crm/customers`（15.3.1.1 客户列表）、
 * `/crm/customer-lifecycle`（15.6.2.1 生命周期）、
 * `/crm/customer-merge`（15.3.1.3 跨店合并）**并列**：
 * 客户列表回答「租户有哪些客户」，生命周期回答「这位客户该回购却没回购」，
 * 跨店合并回答「哪些来源店铺客户行其实是同一个人」，
 * 本模块回答「这位客户是什么等级、能享什么权益」。
 *
 * <p><b>页面上必须讲清的四条</b>：
 *
 * <ul>
 *   <li><b>等级从哪来</b>：等级<strong>只能</strong>由租户真实消费数据算出，
 *       门槛是<strong>租户数据</strong>（可配、可留空、带生效区间）——
 *       页面把这件事写在每一行上，而不是让人以为「等级可以手工指定」。</li>
 *   <li><b>没有手工挂等级的入口</b>：页面<strong>不提供</strong>任何设置等级的控件 ——
 *       那是后端刻意没有的写路径（蓝图加粗句「手工挂等级会导致权益成本失控」）。</li>
 *   <li><b>「未达任何等级」是一等状态</b>：它意味着这位客户<strong>没有</strong>达到
 *       任何一档门槛，不是「普通会员」。</li>
 *   <li><b>权益只输出不代劳</b>：权益生效配置交给 <strong>5 定价 / 14 促销</strong>，
 *       本页面<strong>不</strong>改价目表或促销表。</li>
 * </ul>
 */
export default function CrmCustomerMembershipPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">会员等级看板</h1>
        <p className="text-muted-foreground text-copy-13">
          会员档案、等级规则（按<strong>累计消费 / 订单数 / 复购率</strong>自动升级降级）、
          等级权益（折扣 / 免邮 / 优先发货）与等级变更记录。 等级
          <strong>只能由真实消费数据算出</strong>， 门槛是<strong>租户数据</strong>——
          没有手工挂等级的入口， 每一行都写着「凭什么落这一级」。
        </p>
      </header>

      <MembershipWorkspace />
    </div>
  );
}
