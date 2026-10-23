'use client';

import { LinkageRiskWorkspace } from '@/components/linkage-risk/linkage-risk-workspace';

/**
 * 关联风险：L3「16.5.2 账号安全」下的 16.5.2.2。
 *
 * <p>蓝图：「识别账号关联风险：同一支付方式/收款账户/设备/IP/地址/法人关联的店铺，
 * 输出关联图谱与风险提示，提示避免触发平台关联处罚。输入：店铺与支付/设备/地址/法人关系数据；
 * 平台政策要求。输出：关联图谱 + 风险提示 + 整改建议。关系：上游 = 2 店铺、12.7.1 资金账户；
 * 下游 = 1 工作台预警。<strong>关联封号是跨境多店经营的头号风险</strong>：官方 API 授权是规避关联的关键，
 * 本系统只做官方 API，绝不提供任何模拟登录/指纹浏览器能力。」
 *
 * <p>🔴 本页是 <strong>16.5.2 账号安全</strong>（前端入口 <code>/compliance/linkage-risk</code>），
 * 与 <code>/compliance/access-security</code>（16.5.2.1 登录与访问安全）、
 * <code>/compliance/buyer-risk</code>（16.5.1.2 买家风险评分）、
 * <code>/compliance/pii-access</code>（16.6.1.1 PII 管理）
 * <strong>是四个不同的页面</strong> —— 路径不同、互不影响。
 *
 * <p>🔴 <strong>「读不到」绝不显示成「没有关联」</strong>：后端给 <code>known=false</code>
 * 就是没判定，页面显示成「还没登记策略」并写清原因；<strong>量不到的维度</strong>
 * （支付方式 / 收款账户归 12.7.1.1，未交付）显示成「读不到 + 归属模块」，
 * <strong>绝不显示 0 或「正常」</strong>。
 *
 * <p>🔴 <strong>本页不提供任何绕关联的入口</strong>：只有「登记已观测到的共享事实」，
 * 没有模拟登录 / 指纹浏览器 —— 蓝图明令禁止的能力，一个都不做。
 */
export default function LinkageRiskPage() {
  return (
    <div className="space-y-4">
      <LinkageRiskWorkspace />
    </div>
  );
}
