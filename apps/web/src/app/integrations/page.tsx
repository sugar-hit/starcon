'use client';

import { AppCredentialWorkspace } from '@/components/integrations/app-credential-workspace';

/**
 * 18.1.1 应用与凭证 → 18.1.1.1 应用与凭证（卷 18 前端入口 `/integrations`）。
 *
 * <p>蓝图：「管理第三方接入的应用：应用注册、API Key/Secret 生成与轮换、授权范围（可访问的资源与操作）、
 * IP 白名单、调用配额。输入：应用注册；凭证轮换；授权范围配置。输出：应用档案 + 凭证
 * （<strong>明文仅在生成时展示一次</strong>，之后只存摘要）。关系：上游 = 17 权限；下游 = 18.1.1.2 接口目录。
 * <strong>对外凭证与平台凭据同等对待</strong>：不落库明文、访问有审计、泄露可一键吊销。」
 *
 * <p>🔴 本页只管「谁可以接入我们」。与之对照的是 channel 的 <code>/channels</code>
 * （「我们接入谁」—— 电商平台与店铺）—— 两者是两件事，命名空间也不一样。
 *
 * <p>🔴 卷 18 卷首红线「不提供任何绕过平台官方 API 的模拟登录能力」：本页登记的是
 * <strong>对接方拿我们官方 API 的 Key</strong>，不是我们拿对方的账号密码。
 */
export default function IntegrationsPage() {
  return (
    <div className="space-y-4">
      <AppCredentialWorkspace />
    </div>
  );
}
