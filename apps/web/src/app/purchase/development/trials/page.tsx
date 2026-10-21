import { ProductionTrialWorkspace } from '@/components/purchase/production-trial-workspace';

/**
 * 6.6.2.1 试产单。
 *
 * <p>蓝图 06 卷：「管理试产：小批量试产（数量、BOM 版本、组件需求、产线/加工厂、质检标准、
 * 试产成本与良率），试产结果记录并对比量产目标，输出转量产建议。」
 * 输出「试产单 + 组件领用需求（→ 7 库存）；良率与成本结果；转量产建议」。
 *
 * <p>页面必须让四件事看得见：
 *
 * <ul>
 *   <li><b>组件领用需求是「需求」，不是「已领用」</b>：蓝图加粗句「试产消耗真实组件库存，
 *       需走 8 加工单而非直接改库存」—— 本页<b>不扣库存</b>，真实领用由 warehouse 侧
 *       8.4.1 组装单承接。常驻横幅写明这条边界。</li>
 *   <li><b>良率按比例</b>：后端给的是 0.95（不是 95）；页面显示的百分数只是同一份值的
 *       显示格式，tooltip 里给出原始比例。</li>
 *   <li><b>成本只与同一币种的量产目标成本可比</b>：概览逐币种列，不给一个假的「总成本」；
 *       目标成本取不到时显示「不可比」，<b>不显示 0</b>。</li>
 *   <li><b>做不到的事写在页面上</b>：库存扣减（归 8.4.1 组装单）、转量产建档（归 6.6.2.2）、
 *       汇率（归 5.2.2.1）—— 常驻横幅，不假装可用。</li>
 * </ul>
 *
 * <p>前端路由是 {@code /purchase/development/trials}（6.6 立项与试产分区），
 * 后端命名空间是 {@code /trade/trial-productions} —— 与 6.6.1.1 前端 /purchase/development/projects ↔
 * 后端 /trade/development-projects 同一纪律。
 */
export default function ProductionTrialsPage() {
  return <ProductionTrialWorkspace />;
}
