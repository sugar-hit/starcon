'use client';

import { AccessSecurityWorkspace } from '@/components/access-security/access-security-workspace';

/**
 * 登录与访问安全：L3「16.5.2 账号与访问安全」下的 16.5.2.1。
 *
 * <p>蓝图：「管理平台账号与系统的访问安全：多因子认证、登录审计、异常登录告警、IP 白名单、
 * 会话管理；平台授权凭据的访问审计。输入：认证配置；登录事件；访问审计事件。
 * 输出：安全配置 + 审计记录 + 告警。关系：上游 = 17 identity；下游 = 2 凭据引用。
 * <strong>平台凭据是最值钱的资产</strong>，访问必须有审计且最小权限。」
 *
 * <p>🔴 本页是 <strong>16.5.2 账号与访问安全</strong>（前端入口 <code>/compliance/access-security</code>），
 * 与 <code>/compliance/buyer-risk</code>（16.5.1.2 买家风险评分）、
 * <code>/compliance/ip-risk</code>（16.4.1.1 侵权库）、
 * <code>/compliance/account</code>（16.1.1.1 账号健康）
 * <strong>是四个不同的页面</strong> —— 路径不同、互不影响。
 *
 * <p>🔴 <strong>「读不到」绝不显示成「没有异常」</strong>：后端给 <code>known=false</code>
 * 就是没记录，页面显示成「还没回填」并写清原因；<strong>量不到的维度</strong>
 * （按来源 IP 判异常、会话到期时刻、MFA 挑战真实下发）显示成「量不到 + 归属模块」，
 * <strong>绝不显示 0 或「正常」</strong>。
 */
export default function AccessSecurityPage() {
  return (
    <div className="space-y-4">
      <AccessSecurityWorkspace />
    </div>
  );
}
