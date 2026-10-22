'use client';

import { ComplianceAppealsWorkspace } from '@/components/compliance-appeals/compliance-appeals-workspace';

/**
 * 申诉管理：L3「16.1.2 风险事件」下的 16.1.2.2。
 *
 * <p>蓝图：「管理平台申诉：申诉类型、举证材料收集、申诉文本、提交跟踪、结果记录与申诉次数统计。
 * 输入：违规记录；申诉材料；提交动作。输出：申诉单 + 提交记录 + 结果（通过/驳回）。
 * 关系：上游 = 16.1.2.1；下游 = 平台申诉接口。<strong>申诉配额有限</strong>，需记录已用次数并优先申诉高影响违规。」
 *
 * <p>🔴 本页是<strong>16.1.2.2 申诉管理</strong>（前端入口 <code>/compliance/appeals</code>），
 * 与 16.1.2.1 的 <code>/compliance/violations</code>（违规与处罚）、
 * 16.1.1.1 的 <code>/compliance/account</code>（账号健康总览）、
 * 16.1.1.2 的 <code>/compliance/metrics</code>（绩效指标监控）<strong>是四个页面</strong>：
 * 一个看「平台罚了我们什么」、一个看「怎么把处罚撤销掉」、一个看「账号现在怎么样」、
 * 一个看「指标会不会跌破阈值」。混为一谈会让运营以为「违规列表里能直接申诉」——
 * 而违规那侧**刻意没有**申诉入口。
 *
 * <p>🔴 <strong>申诉配额永远摆在明面上</strong>：上限 / 已用 / 剩几次三样都显示；
 * 「没配配额」显示成「未配置」，<strong>不显示成 0</strong>（没配 ≠ 无限）。
 *
 * <p>🔴 <strong>优先申诉高影响违规</strong>：队列按影响档位从高到低排，
 * 运营照着队列提申诉，而不是从下往上翻违规列表。
 *
 * <p>🔴 下游<strong>平台申诉接口</strong>本仓<strong>不存在</strong> —— 页面上如实标出，
 * <strong>不编</strong>「已提交到平台」。
 */
export default function ComplianceAppealsPage({
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
      <ComplianceAppealsWorkspace focus={focus} />
    </div>
  );
}
