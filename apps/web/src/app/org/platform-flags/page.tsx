import { PlatformFlagWorkspace } from '@/components/iam/platform-flag-workspace';

/**
 * 17.7.2.1 平台参数与开关。
 *
 * <p>页面路径刻意叫 `platform-flags` 而不是 `flags` / `parameters`：
 * 它与租户级的 `/org/business-parameters`（17.5.1.1 业务参数）是**两件事** ——
 * 前者是平台侧运营的全局开关与参数，后者是租户自己的业务参数。
 * 名字混在一起会让人以为「租户参数能盖掉平台开关」，而那正是蓝图禁止的。
 */
export default function PlatformFlagsPage() {
  return <PlatformFlagWorkspace />;
}
