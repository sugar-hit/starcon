'use client';

import { PlatformViolationsWorkspace } from '@/components/platform-violations/platform-violations-workspace';

/**
 * 违规与处罚：L3「16.1.2 风险事件」下的 16.1.2.1。
 *
 * <p>蓝图：「记录与管理平台违规与处罚：违规类型、涉及商品/订单、处罚措施（警告/下架/限制销售/扣款/停号）、
 * 处理状态与整改要求。输入：平台违规通知同步；整改记录。输出：违规记录 + 整改跟踪 + 扣款金额（进 12 财务）。
 * 关系：上游 = 2 同步；下游 = 12 财务（罚扣款）、4 刊登（问题商品下架）、16.1.2.2 申诉。
 * <strong>违规常涉及多个商品</strong>，需批量定位受影响 listing 与库存。」
 *
 * <p>🔴 本页是<strong>16.1.2.1 违规与处罚</strong>（前端入口 <code>/compliance/violations</code>），
 * 与 16.1.1.1 的 <code>/compliance/account</code>（账号健康总览）、
 * 16.1.1.2 的 <code>/compliance/metrics</code>（绩效指标监控）<strong>是三个页面</strong>：
 * 一个看「账号现在怎么样」、一个看「指标会不会跌破阈值」、一个看「平台罚了我们什么、怎么整改」。
 *
 * <p>🔴 <strong>扣款金额永远带币种</strong>，合计<strong>按币种分组</strong>，
 * 页面上<strong>没有</strong>跨币种的总数 —— 不同币种直接相加是本模块明令禁止的。
 *
 * <p>🔴 下游 <strong>12 财务（罚扣款）</strong>与<strong>16.1.2.2 申诉管理</strong>
 * 当前<strong>都未交付</strong> —— 页面上如实标出，<strong>不编</strong>放款记录、
 * <strong>不编</strong>申诉单。
 */
export default function PlatformViolationsPage({
  searchParams,
}: {
  searchParams: Record<string, string | string[] | undefined>;
}) {
  const one = (value: string | string[] | undefined) =>
    Array.isArray(value) ? (value[0] ?? '') : (value ?? '');
  // 从别处跳进来时带上 ?shopId=，直接定位到那家店。
  const focus = { shopId: one(searchParams?.shopId) };

  return (
    <div className="space-y-4">
      <PlatformViolationsWorkspace focus={focus} />
    </div>
  );
}
