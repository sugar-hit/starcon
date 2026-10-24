import { NotificationPreferenceWorkspace } from '@/components/notifications/notification-preference-workspace';

/**
 * 1.3.1.2 通知偏好（卷 01 · L3 1.3.1 消息与预警 → {@code /notifications/preferences}）。
 *
 * <p>蓝图原文：「用户按通知类型选择接收渠道（站内/邮件/企业 IM）与免打扰时段。
 * ……输出：偏好配置；供 1.3.2.2 推送时过滤。
 * ……**关键业务通知（超期发货、账务差异）不允许被完全关闭**，系统强制保留下不可关闭的底线项。」
 *
 * <p>🔴 **本页面只做 1.3.1.2 这一格**：兄弟模块 1.3.1.1「通知列表」在
 * {@code /notifications}（收件箱），1.3.2.x「预警规则 / 外部渠道推送」在
 * {@code /notifications/rules} —— 那几格各有自己的职责，本车道不碰别人的文件。
 *
 * <p>🔴 **偏好是纯个人配置**，落在 identity 服务的 {@code user_preference}
 * （页面 key = {@code notifications.delivery}），按**成员**存放 ——
 * 同事的偏好与你无关，租户与数据范围全部由服务端按已认证的成员关系判定。
 *
 * <p>🔴 **本页面不实现推送**：外部渠道投递属于 1.3.2.2（未交付）。
 * 本页只负责把偏好配置存好并可读。
 */
export default function NotificationPreferencesPage() {
  return <NotificationPreferenceWorkspace />;
}
