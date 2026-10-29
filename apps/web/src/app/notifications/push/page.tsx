import { ExternalChannelPushWorkspace } from '@/components/notifications/external-channel-push-workspace';

/**
 * 1.3.2.2 外部渠道推送（卷 01 · L3 1.3.2 预警订阅 → {@code /notifications/push}）。
 *
 * <p>蓝图原文：「把选定的通知推送到企业 IM（企业微信/钉钉/飞书）或邮件，
 * 支持卡片化消息与点击跳转。」「<b>凭据不落库</b>，只存 secret manager 引用。」
 *
 * <p>🔴 **本页面只做 1.3.2.2 这一格** —— 不把兄弟模块 1.3.2.1「预警规则」
 * （{@code /notifications/rules}）与 1.3.1.1 / 1.3.1.2 一起包进来：
 * 那一格有它自己的职责与安全边界，本车道不碰。
 *
 * <p>🔴 **投递结果如实**：外部 IM / 邮件通道在本仓未接通 ⇒ 每一次投递尝试都**真的失败**，
 * 并落成带失败码、重试排期与告警的投递记录。页面显示「未接通 + 归属系统」，
 * 不显示假成功。
 *
 * <p>🔴 **租户来自已认证成员关系**：前端不持有、不下发任何租户 id，
 * 也不把「谁能改」当成客户端说了算 —— 服务端会独立再判一次。
 */
export default function ExternalChannelPushPage() {
  return <ExternalChannelPushWorkspace />;
}
