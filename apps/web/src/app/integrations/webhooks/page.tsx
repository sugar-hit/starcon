'use client';

import { WebhooksWorkspace } from '@/components/integrations/webhooks/webhooks-workspace';

/**
 * 18.1 开放与凭证 → 18.1.2 事件订阅 → 18.1.2.1 事件订阅（卷 18 前端入口 `/integrations/webhooks`）。
 *
 * <p>蓝图：「<strong>事件订阅</strong>：对外提供事件订阅（webhook）：可订阅事件列表、订阅端点配置、
 * 签名密钥、事件过滤、重试与死信策略。输入：订阅配置；事件定义（schema 版本）。输出：订阅记录；
 * 事件投递（带签名）。关系：上游 = 业务事件（outbox）；下游 = 外部端点。
 * <strong>事件 schema 必须版本化，破坏性变更要新增版本而非原地修改。</strong>」
 *
 * <p>🔴 本页与 <code>/integrations</code>（18.1.1.1 应用与凭证）<strong>不是一件事</strong>：
 * 那一页管的是「谁可以接入我们」，本页管的是「我们把事件送到哪里、怎么签名、怎么重试」。
 * 两者只是同属卷 18 而已 —— 命名空间不同、权限码不同、端点前缀不同。
 *
 * <p>🔴 本仓当前<strong>没有事件投递执行器</strong>（归 18.1.2.2「投递记录」），
 * 所以「事件实际投递」在页面上如实显示为「未接入」，页面**不提供**投递按钮 ——
 * 一个点了必然 409 的按钮会让运营以为「点一下就能推」。与有没有投递执行器无关，
 * 下面五件<strong>订阅配置侧</strong>的能力今天就是真实生效的：可订阅事件列表（带出处）、
 * 订阅端点配置、签名密钥（明文只出现一次）、事件过滤、重试与死信策略配置。
 */
export default function WebhooksPage() {
  return (
    <div className="space-y-4">
      <WebhooksWorkspace />
    </div>
  );
}
