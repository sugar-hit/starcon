import { StocktakeWorkspace } from '@/components/inventory/stocktake-workspace';

/**
 * 7.4.2.1 盘点单。
 *
 * <p>蓝图：「发起盘点：选择范围（按仓库/类目/库位/ABC 分类）、冻结时点快照、生成盘点任务
 * （按库位或按 SKU）、录入实盘数」，输出「盘点单 + 账面/实盘/差异三列数据」，以及红线
 * 「盘点时点必须冻结账面数，否则边盘边卖会产生虚假差异」。
 *
 * <p>这是一个真实工作区而不是占位页：发起盘点（当场冻结账面数并生成任务）、盘点任务与派工、
 * 三列明细与实盘录入（含撤销）都落到 PostgreSQL 16，账面数由数据库触发器钉死不可改写。
 */
export default function StocktakePage() {
  return <StocktakeWorkspace />;
}
