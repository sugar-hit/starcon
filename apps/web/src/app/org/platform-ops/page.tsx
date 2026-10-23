import { PlatformOpsWorkspace } from '@/components/iam/platform-ops-workspace';

/**
 * 17.7.2.2 平台运维视图。
 *
 * <p>页面路径叫 `platform-ops` 而不是 `monitoring` / `health`：它是**平台管理员视角**的
 * 跨租户运维视图，与租户自己的「业务参数」「平台参数与开关」都不是一回事。
 * 名字混在一起会让人以为租户能看别的租户的在线状态 —— 而那正是蓝图禁止的。
 */
export default function PlatformOpsPage() {
  return <PlatformOpsWorkspace />;
}
