'use client';

import { BuyerRiskWorkspace } from '@/components/buyer-risk/buyer-risk-workspace';

/**
 * 买家风险评分：L3「16.5.1 买家风控」下的 16.5.1.2。
 *
 * <p>蓝图：「计算买家风险分：历史退款率、纠纷率、欺诈特征、地址模式、下单行为、关联账号，
 * 输出评分与建议动作。输入：买家行为数据；风险特征规则；权重配置。输出：风险评分 + 特征解释。
 * 关系：上游 = 15 客户、11 售后、9 订单；下游 = 16.5.1.1、9.2.2.2。
 * <strong>评分要能解释</strong>（为什么这个买家 80 分），黑盒分数客服无法向平台申诉。」
 *
 * <p>🔴 本页是 <strong>16.5.1 买家风控</strong>（前端入口 <code>/compliance/buyer-risk</code>），
 * 与 <code>/compliance/account</code>（16.1.1.1 账号健康）、
 * <code>/compliance/violations</code>（16.1.2.1 违规与处罚）、
 * <code>/compliance/precheck</code>（16.2.2.1 合规预检）、
 * <code>/compliance/tax</code>（16.3.1.1 税号与辖区）
 * <strong>是五个不同的页面</strong> —— 路径不同、互不影响。
 *
 * <p>🔴 <strong>页面上的分数与解释是后端算出来的</strong>，前端原样显示：
 * 量不到的维度显示「未知」并写清量不到的原因与归属模块，<strong>绝不显示 0 分</strong>
 * —— 0 分会被读成「这个买家很干净」，而真相是「我们没查到」。
 *
 * <p>🔴 <strong>未交付的下游不编造、不跳转</strong>：16.5.1.1 黑名单、
 * 16.5.2.2 关联风险当前<strong>都未交付</strong>，页面上只显示能力声明里的
 * 「量不到 + 归属模块」，<strong>没有拉黑按钮、没有关联账号图谱</strong>。
 */
export default function BuyerRiskScorePage() {
  return (
    <div className="space-y-4">
      <BuyerRiskWorkspace />
    </div>
  );
}
