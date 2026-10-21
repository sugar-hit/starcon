import { DemandMergeWorkspace } from '@/components/purchase/demand-merge-workspace';

/**
 * 6.2.2.2 需求合并与批量。
 *
 * <p>蓝图 06 卷：「把多个 SKU 的需求合并成一张采购单（同一供应商），支持批量生成、
 * 批量修改数量与供应商、批量打印。」输入「多行需求；供应商选择（相同供应商才能合并）」。
 * 输出「合并后的采购单草稿；未合并行说明原因」。
 *
 * <p>页面必须让四件事看得见：
 *
 * <ul>
 *   <li><b>分组是按供应商的</b>：一张草稿只属于一个供应商。跨供应商的需求行进
 *       「未合并行」并写明原因 —— 既不静默丢弃，也不偷偷归到第一个供应商
 *       （后者会付错钱）。</li>
 *   <li><b>草稿不是采购单</b>：6.3.1.2 采购单未交付 ⇒ 页面上出现的是**采购单草稿**，
 *       **不是**「采购单已创建」。</li>
 *   <li><b>批量动作整体生效或整体拒绝</b>：改数量 / 改供应商要么全成要么全拒，
 *       页面不提供「改一半」的中间操作。</li>
 *   <li><b>金额算不出来就说算不出来</b>：预估金额显示「无数据」而不是 0。</li>
 * </ul>
 *
 * <p>前端路由是 {@code /purchase/demand/merge}（与 6.2.2.1 的 /purchase/demand/requests
 * 同域不同页），后端命名空间是 {@code /trade/demand-merges} —— 与 6.2.2.1 前端
 * /purchase/demand/requests ↔ 后端 /trade/purchase-requests、6.2.1.3 前端
 * /purchase/demand/plans ↔ 后端 /trade/purchase-plans 同一纪律。
 */
export default function PurchaseDemandMergePage() {
  return <DemandMergeWorkspace />;
}
