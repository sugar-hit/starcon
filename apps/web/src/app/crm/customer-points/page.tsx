'use client';

import { PointsWorkspace } from '@/components/crm/customer-points-workspace';

/**
 * 15.6.1.2 积分与权益（蓝图 15 卷 L3 锚点 15.6.1「会员体系」下的积分与权益子域）。
 *
 * <p>路由是 `/crm/customer-points`，与 `/crm/customer-membership`（15.6.1.1 会员与等级）、
 * `/crm/customers`（15.3.1.1 客户列表）、
 * `/crm/customer-lifecycle`（15.6.2.1 生命周期）、
 * `/crm/customer-merge`（15.3.1.3 跨店合并）**并列**：
 * 会员与等级回答「这位客户是什么等级、能享什么权益」，
 * 本模块回答「这位客户有多少积分、积分什么时候过期、能换什么权益、还欠财务多少负债」。
 *
 * <p><b>页面上必须讲清的四条</b>：
 *
 * <ul>
 *   <li><b>积分比率是租户数据</b>：拿多少分、抵多少比例、单积分值多少、积分有效期多久，
 *       全部由租户自己的规则决定 —— 页面<strong>不</strong>假装有一套默认比率。</li>
 *   <li><b>余额可审阅</b>：每一行流水都显示「变动前余额 → 变动后余额」，
 *       页面<strong>不做</strong>任何前端积分算术。</li>
 *   <li><b>有效期按绝对时刻</b>：积分什么时候过期写在批次上，
 *       页面显示的是<strong>绝对时刻</strong>（统一 UTC），不是「还有几个月」。</li>
 *   <li><b>负债如实标注</b>：未兑付积分是负债，但本模块<strong>只输出汇总</strong> ——
 *       12 财务侧尚未交付积分负债科目，页面<strong>不</strong>显示「已入账」。</li>
 * </ul>
 */
export default function CrmCustomerPointsPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">积分与权益看板</h1>
        <p className="text-muted-foreground text-copy-13">
          积分获取（消费 / 评价 / 签到 / 人工）与消耗（抵扣 /
          兑换）、积分余额与有效期、权益核销记录， 以及<strong>积分负债汇总</strong>。 积分比率
          <strong>全是租户数据</strong>—— 没有写死的默认比率，每一笔都留「变动前余额 →
          变动后余额」。 未兑付积分是<strong>负债</strong>，但本模块<strong>只输出汇总</strong>， 12
          财务侧尚未交付积分负债科目，<strong>未</strong>入账。
        </p>
      </header>

      <PointsWorkspace />
    </div>
  );
}
