'use client';

import { PerformanceMetricsWorkspace } from '@/components/performance-metrics/performance-metrics-workspace';

/**
 * 绩效指标监控：L3「16.1.1 绩效指标」下的 16.1.1.2。
 *
 * <p>蓝图：「监控各平台核心绩效指标的趋势与阈值：订单缺陷率、迟发率、取消率、有效追踪率、
 * 响应时间、账户评级，支持自定义阈值预警与趋势预测（是否会跌破阈值）。
 * 输入：指标时间序列；阈值配置；预测模型参数。
 * 输出：指标趋势图 + 阈值预警 + 跌破预测。
 * 关系：上游 = 16.1.1.1；下游 = 1 工作台预警、9 订单履约（迟发预警前置）。
 * <strong>预测跌破比已跌破更有价值</strong>——提前几天知道就能提前改。」
 *
 * <p>🔴 本页是<strong>16.1.1.2 绩效指标监控</strong>（前端入口 <code>/compliance/metrics</code>），
 * 与 16.1.1.1 的 <code>/compliance/account</code>（账号健康总览）<strong>是两个页面</strong>：
 * 一个看「现在怎么样、谁该先处理」，一个看「趋势怎么走、会不会跌破阈值」。
 *
 * <p>🔴 <strong>六项指标分属两个来源，页面上如实标注</strong>：订单缺陷率 / 迟发率 /
 * 取消率 / 有效追踪率的时间序列来自 <strong>16.1.1.1</strong>；响应时间 / 账户评级来自
 * <strong>本模块自有</strong>。每条序列都标了它的来源，<strong>不假装</strong>是同一个来源。
 *
 * <p>🔴 <strong>「量不到」是第三种状态</strong>：显示成「未知」，
 * <strong>绝不显示成 0.00%</strong>；它也不等于「合格」。
 *
 * <p>🔴 下游 <strong>1 工作台预警</strong>与<strong>9 订单履约（迟发预警前置）</strong>
 * 当前<strong>都未交付</strong> —— 页面上如实标出，<strong>不编</strong>推送、
 * <strong>不编</strong>履约联动。
 */
export default function PerformanceMetricsPage({
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
      <PerformanceMetricsWorkspace focus={focus} />
    </div>
  );
}
