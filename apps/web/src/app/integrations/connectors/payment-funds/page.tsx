'use client';

import { PaymentFundsWorkspace } from '@/components/integrations/payment-funds-workspace';

/**
 * 18.2 外部系统对接 → 18.2.2 支付与资金 → 18.2.2.1 支付与资金
 * （卷 18 前端入口 `/integrations/connectors/payment-funds`）。
 *
 * <p>蓝图：「<strong>支付与资金</strong>：对接支付与资金服务（PayPal、Stripe、PingPong、连连、万里汇）：
 * 余额查询、收款登记、付款执行、汇率查询、手续费明细同步。输入：资金操作请求；
 * 接口配置（<strong>密钥引用</strong>）。输出：操作结果 + 资金流水同步。关系：上游 = 12.2 收付款、
 * 12.7 资金账户；下游 = 支付服务商。<strong>所有第三方资金操作必须有幂等键与查询兜底</strong>，
 * 超时先查证再重试（实体目录 §6）。」
 *
 * <p>🔴 本页与 <code>/integrations/connectors/finance-software</code>
 * （18.2.1.1 财务软件）<strong>不是一件事</strong>：那一页管的是「凭证怎么交给外部财务软件」，
 * 本页管的是「钱怎么通过支付服务商出去」。两者只是同属 18.2 卷而已 ——
 * 命名空间不同、权限码不同、端点前缀不同。
 *
 * <p>🔴 本仓当前<strong>没有任何真实的支付服务商适配器</strong>，所以「直连 PayPal / Stripe /
 * PingPong / 连连 / 万里汇」这几格在页面上如实显示为「未接入」并写明缺在谁身上：
 * 页面**不提供**这些必然 409 的按钮，也不做本地兜底生成 ——
 * 一个点了必然失败的按钮会让运营以为「点一下就能付到 PayPal」。
 * 与服务商有没有直连通道，不影响下面这些<strong>本系统内部能力</strong>的真实生效：
 * 服务商配置（密钥只存引用）、幂等键与操作台账、查证兜底、资金流水台账、
 * 余额 / 手续费账单导入、汇率观测（真读上游 5.2.2.1 汇率事实）。
 */
export default function PaymentFundsPage() {
  return (
    <div className="space-y-4">
      <PaymentFundsWorkspace />
    </div>
  );
}
