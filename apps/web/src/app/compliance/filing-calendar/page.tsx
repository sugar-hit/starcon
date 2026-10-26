'use client';

import { FilingCalendarWorkspace } from '@/components/compliance/filing-calendar-workspace';

/**
 * 申报日历：L3「16.3.1 税号与辖区」下的 16.3.1.2。
 *
 * <p>蓝图：「管理各辖区申报日历：申报周期、截止日、所需数据、状态（未开始/数据就绪/已提交/已确认），
 * 到期提醒与责任人。输入：申报周期配置；辖区要求；12.6.1.2 数据就绪状态。
 * 输出：申报日历视图 + 提醒 + 状态跟踪。关系：上游 = 16.3.1.1、12.6.1.2；下游 = 1 工作台待办。
 * <strong>漏报罚款远高于系统能提供的价值</strong>，日历提醒是最低成本的合规保障。」
 *
 * <p>🔴 本页是 <strong>16.3.1.2 申报日历</strong>（前端入口 <code>/compliance/filing-calendar</code>），
 * 与 <code>/compliance/tax</code>（16.3.1.1 税号与辖区）<strong>是两个不同的页面</strong> ——
 * 路径不同、互不影响；16.3.1.1 管的是「有没有在册登记」，本页管的是「什么时候该申报、
 * 到没到期、数据齐没齐、谁负责、报没报」。
 *
 * <p>🔴 <strong>页面上的每一项都是后端算出来的</strong>：申报期与截止日按「生效起始日 +
 * 期起始日 + 周期 + 当地时区」真算；数据就绪是 12.6.1.2 生效底稿的投影；逾期判定按服务端
 * 当前时刻。前端只负责<strong>原样显示</strong>，不重算、不自己编依据。
 *
 * <p>🔴 <strong>「读不到」显示成「读不到」</strong>：12.6.1.2 读不到时显示「读不到」并给出原因，
 * <strong>不显示成「数据没就绪」</strong> —— 前者要去查上游，后者是「查过了，确实没有」，
 * 处置动作完全相反。
 *
 * <p>🔴 <strong>未交付的下游不编造、不跳转</strong>：1 工作台待办（1.1.1.x）与 16.3.2.x
 * 申报执行当前<strong>都未交付 / 未接线</strong>，能力声明里逐条写清了缺什么、归谁。
 */
export default function FilingCalendarPage() {
  return (
    <div className="space-y-4">
      <FilingCalendarWorkspace />
    </div>
  );
}
