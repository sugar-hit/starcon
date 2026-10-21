import { DevelopmentProjectWorkspace } from '@/components/purchase/development-project-workspace';

/**
 * 6.6.1.1 开发立项。
 *
 * <p>蓝图 06 卷：「管理新品开发立项：立项编号、品类、目标市场与平台、目标售价与毛利、开发负责人、
 * 打样与量产时间计划、状态（评估/立项/开发中/打样/试产/量产/终止）。立项通过后可派生打样单与试产单，
 * 并作为 SKU 建档的前置。」输出「开发单（立项记录 + 里程碑计划）；可转 SKU 建档的候选参数」。
 *
 * <p>页面必须让三件事看得见：
 *
 * <ul>
 *   <li><b>目标毛利取不到就说取不到</b>：成本不可得时显示「取不到（成本不可得）」，
 *       <b>不</b>显示 0.00% —— 成本 0 的毛利率是 100%，是彻头彻尾的假数据。</li>
 *   <li><b>状态是受控的</b>：目标状态下拉只给后端说的合法去向；跳过打样 / 试产必须显式填豁免；
 *       终止是终态，页面上不提供「恢复」。</li>
 *   <li><b>下游未交付的模块不假装可用</b>：打样 / 试产只产交接单，
 *       页面显示「已交接，待 6.6.1.2 消费」；市场数据明说「系统侧未交付（归 14 广告管）」。</li>
 * </ul>
 *
 * <p>前端路由是 {@code /purchase/development/projects}（6.6.1 立项与打样分区），
 * 后端命名空间是 {@code /trade/development-projects} —— 与 6.2.1.3 前端 /purchase/demand/plans ↔
 * 后端 /trade/purchase-plans、6.2.1.2 前端 /purchase/demand/replenishment ↔
 * 后端 /trade/replenishment-suggestions 同一纪律。
 */
export default function DevelopmentProjectsPage() {
  return <DevelopmentProjectWorkspace />;
}
