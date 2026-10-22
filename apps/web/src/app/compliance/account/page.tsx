'use client';

import { AccountHealthWorkspace } from '@/components/account-health/account-health-workspace';

/**
 * 账号健康总览：L3「16.1.1 绩效指标」下的 16.1.1.1。
 *
 * <p>蓝图：「按店铺展示账号健康状态：绩效指标（ODR、迟发率、取消率、有效追踪率）、政策违规计数、
 * 申诉状态、风险等级，支持跨店铺对比与风险排序。输入：平台绩效数据同步；风险等级计算规则；
 * 用户数据范围（店铺维度）。输出：店铺健康卡片 + 风险排序 + 需处理项跳转。
 * 关系：上游 = 2 同步；下游 = 16.1.1.2 指标明细、16.1.2.1 违规。
 * <strong>风险排序比明细更重要</strong>：先处理最可能掉号的店铺。」
 *
 * <p>🔴 本页是<strong>16.1 账号健康</strong>（蓝图前端入口 <code>/compliance/account</code>），
 * 与 <code>/account</code>（identity 服务的登录账号页，<code>IdentityWorkspace</code>）
 * <strong>不是同一个页面</strong> —— 两者路径不同、互不影响。
 *
 * <p>🔴 <strong>页面上没有「政策违规计数」与「申诉状态」两块</strong>：那两维归
 * 16.1.2.1 违规与处罚、16.1.2.2 申诉管理，<strong>当前都未交付</strong>。
 * 界面宁可少两块，也不拿编造的数据去填 —— 能力声明里逐条写清了缺什么、归谁。
 *
 * <p>🔴 平台账号绩效同步<strong>未接入</strong>（归卷 02 同步运行）：绩效指标目前只能
 * <strong>手工录入</strong>，录入的条目一律标「人工录入」，不会冒充平台同步来的。
 */
export default function AccountHealthPage({
  searchParams,
}: {
  searchParams: Record<string, string | string[] | undefined>;
}) {
  const one = (value: string | string[] | undefined) =>
    Array.isArray(value) ? (value[0] ?? '') : (value ?? '');
  // 从「需处理项」跳进来时带上 ?shopId=，直接定位到那家店的卡片。
  const focus = { shopId: one(searchParams?.shopId) };

  return (
    <div className="space-y-4">
      <AccountHealthWorkspace focus={focus} />
    </div>
  );
}
