import { UsageStatWorkspace } from '@/components/iam/usage-stat-workspace';

/**
 * 17.7.1.2 用量统计。
 *
 * <p>starcon **自身**的 SaaS 商业化域的统计层：把来源系统登记的「当前用量」物化成
 * 按周期分桶的统计事实，产出用量报表、趋势、计费依据与容量规划。
 *
 * <p>路由放在 `/org/usage` 而不是 `/org/plans/usage`：用量统计与套餐权益是**两个模块**
 * （17.7.1.2 与 17.7.1.1），统计页有自己的一套读出口（报表 / 趋势 / 计费依据 / 容量 /
 * 覆盖），挂在套餐页下面会让人以为两者是同一件事。
 *
 * <p>与 17.7.1.1 一样：仅平台管理员可见，租户不可访问；迁移到 `apps/admin`
 * （蓝图里的 `/platform` 前端入口）是后续待办——`apps/admin` 目前只有空脚手架。
 */
export default function UsageStatisticsPage() {
  return <UsageStatWorkspace />;
}
