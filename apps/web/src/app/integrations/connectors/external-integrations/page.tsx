'use client';

import { ExternalIntegrationWorkspace } from '@/components/integrations/external-integration-workspace';

/**
 * 18.2 外部系统对接 → 18.2.3 其他对接 → 18.2.3.1 其他对接
 * （卷 18 前端入口 `/integrations/connectors/external-integrations`）。
 *
 * <p>蓝图：「<strong>其他对接</strong>：对接其他外部系统：1688/淘宝货源平台、海外仓服务商、
 * 物流商、聚合认证服务、自建 BI。统一管理连接配置与同步健康。输入：连接配置；同步任务配置。
 * 输出：连接状态 + 同步记录。关系：上游 = 2 渠道机制；下游 = 外部服务。
 * <strong>每个对接都有独立连接与独立健康状态</strong>，一个外部服务故障不应影响其他集成。」
 *
 * <p>🔴 本页与 <code>/integrations/connectors/payment-funds</code>（18.2.2.1 支付与资金）、
 * <code>/integrations/connectors/finance-software</code>（18.2.1.1 财务软件）
 * <strong>不是一件事</strong>：那两页管的是「钱怎么出去」与「凭证怎么交给外部财务软件」，
 * 本页管的是「1688 / 海外仓 / 物流商这些**对接**本身的连接配置与同步健康」。
 * 三者只是同属 18.2 卷而已 —— 命名空间不同、权限码不同、端点前缀不同。
 *
 * <p>🔴 本仓当前<strong>没有任何真实的外部系统适配器</strong>（1688 / 淘宝 / 海外仓 / 物流商 /
 * 聚合认证 / 自建 BI 一个都没有），所以「直连」这几格如实显示为「未接入」并写明缺在谁身上：
 * 页面<strong>不提供</strong>必然 409 的「发起同步」按钮，也不做本地兜底生成 ——
 * 一个点了必然失败的按钮会让运营以为「点一下就能同步」。
 * 与外部系统有没有直连通道，<strong>不影响</strong>下面这些<strong>本系统内部能力</strong>的
 * 真实生效：对接连接配置（密钥只存引用）、同步任务配置、每个对接<strong>独立</strong>的健康状态、
 * 同步记录台账，以及「外部侧已完成」的同步<strong>人工登记</strong>（今天就能用）。
 *
 * <p>🔴 另一条不能丢的纪律：「<strong>本地台账成功</strong>」<strong>≠</strong>
 * 「<strong>外部侧已登记</strong>」—— 人工登记的行在页面上永远带着
 * 「未与外部系统核对过」这一格。
 */
export default function ExternalIntegrationsPage() {
  return (
    <div className="space-y-4">
      <ExternalIntegrationWorkspace />
    </div>
  );
}
