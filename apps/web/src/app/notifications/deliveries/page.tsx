'use client';

import { NotifyDeliveryWorkspace } from '@/components/platform/notify-delivery-workspace';

/**
 * 18.5.2.1 投递记录（卷 18 · L3 18.5.2 投递记录 → {@code /notifications/deliveries}）。
 *
 * <p>蓝图原文：「<strong>投递记录</strong>：记录通知投递结果：模板、收件范围、渠道、状态、
 * 失败原因、重试；统计触达率与退订情况。输入：投递记录；重试请求。输出：投递日志 + 统计。
 * 关系：上游 = 18.5.1；下游 = 1.3.2.2 外部推送、15.5.2 触达。
 * <strong>退订必须被尊重并生效</strong>，反复触达退订用户是投诉与合规风险的主要来源。」
 *
 * <p>🔴 **本页面只做 18.5.2.1 这一格** —— 不把兄弟模块 18.5.1.1「模板管理」
 * （{@code /platform/ai/templates}）与 1.3.2.2「外部渠道推送」
 * （{@code /notifications/push}）一起包进来：那两个是**内容**与**渠道执行**，
 * 本页管的是**投递结果与退订**。
 *
 * <p>🔴 **本页不执行投递**：真正把消息发到渠道的是下游 1.3.2.2 与 15.5.2。
 * 页面上的入口是「登记」—— 调用方拿着渠道执行器的真实结果来登记。
 *
 * <p>🔴 **租户来自已认证成员关系**：前端不持有、不下发任何租户 id，
 * 也不把「谁能改」当成客户端说了算 —— 服务端会独立再判一次。
 */
export default function NotifyDeliveryPage() {
  return (
    <div className="space-y-4">
      <NotifyDeliveryWorkspace />
    </div>
  );
}
