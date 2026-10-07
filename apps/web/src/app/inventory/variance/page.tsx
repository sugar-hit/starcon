import { StocktakeVarianceWorkspace } from '@/components/inventory/stocktake-variance-workspace';

/**
 * 7.4.2.2 盘点差异处理。
 *
 * <p>蓝图：「处理盘点差异：按差异原因（损耗、错发、偷盗、未入账、时点差）分类，生成调整单走审批，
 * 盘盈盘亏入账并可追责。」输出「调整单 + 盘点完成报告（含盘盈率、差异率指标）」，以及红线
 * 「差异不直接改余额，必须经调整单与审批」。
 *
 * <p>这是一个真实工作区而不是占位页：差异归因（五类原因 × 调整/复盘/忽略）、调整单状态机
 * （草稿 → 待审批 → 已批 → 入账）、审批前不入账、append-only 审计与完成报告指标
 * 都落到 PostgreSQL 16，且「未审批不得入账」由数据库触发器强制。
 */
export default function StocktakeVariancePage() {
  return <StocktakeVarianceWorkspace />;
}
