import { PurchasePlanWorkspace } from '@/components/purchase/purchase-plan-workspace';

/**
 * 6.2.1.3 采购计划。
 *
 * <p>蓝图 06 卷：「按周期（月/季）、按店铺大促节奏、按仓库制定采购计划，把补货建议汇总成计划行，
 * 支持调整、锁定、导出给采购员。」输出「采购计划（计划行：SKU、数量、目标仓、期望到货日、
 * 优先级）」。
 *
 * <p>页面必须让两件事看得见：
 *
 * <ul>
 *   <li><b>大促备货与日常补货分开</b>：顶部两段汇总（日常备货量 / 大促备货量）**并排**显示，
 *       <b>不给</b>一个两者相加的总数字 —— 那个数字会让日常补货失焦，正是蓝图加粗句
 *       「大促备货与日常补货要分开」要防的事。</li>
 *   <li><b>计划量带算式</b>：每行都显示后端给的算式原文（建议量 × 销量系数 × 备货系数），
 *       「大促计划量为什么是建议量的三倍」不用点进去再猜。</li>
 * </ul>
 *
 * <p>前端路由是 {@code /purchase/demand/plans}（6.2.1 需求生成下的采购计划分区），
 * 后端命名空间是 {@code /trade/purchase-plans} —— 与 6.2.1.2 前端 /purchase/demand/replenishment ↔
 * 后端 /trade/replenishment-suggestions、6.2.1.1 前端 /purchase/demand ↔
 * 后端 /trade/stockout-risk 同一纪律。
 */
export default function PurchaseDemandPlansPage() {
  return <PurchasePlanWorkspace />;
}
