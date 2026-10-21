import { DevelopmentSampleWorkspace } from '@/components/purchase/development-sample-workspace';

/**
 * 6.6.1.2 打样与样品管理。
 *
 * <p>蓝图 06 卷：「管理打样与样品：样品申请、样品来源（供应商/自有产）、样品物流、
 * 确认结果（通过/修改/放弃）、样品成本；与达人寄样（14.5.1.2）区分打样与营销样品。」
 * 输出「打样单 + 样品台账 + 确认记录；样品成本」。
 *
 * <p>页面必须让四件事看得见：
 *
 * <ul>
 *   <li><b>打样与营销样品是分开的</b>：来源只有供应商 / 自有产 / 档口，用途只有「开发打样」。
 *       达人寄样（14.5.1.2）属 14 卷，不在本模块 —— 混进来会让营销费用污染开发成本。</li>
 *   <li><b>打样单与样品台账是两个东西</b>：打样单是**一次打样活动**，
 *       样品台账是**一件样品**（1 张单 : N 件样品）。两个页签分别看。</li>
 *   <li><b>做不到的事写在页面上</b>：样品成本未进费用归集（归 12 费用）、
 *       真实物流轨迹不可查（归 10 物流）、试产衔接未交付（6.6.2.1）—— 常驻横幅，不假装可用。</li>
 *   <li><b>成本不跨币种相加</b>：概览逐币种列，不给一个假的「总成本」。</li>
 * </ul>
 *
 * <p>前端路由是 {@code /purchase/development/samples}（6.6.1 立项与打样分区），
 * 后端命名空间是 {@code /trade/development-samples} —— 与 6.6.1.1 前端 /purchase/development/projects ↔
 * 后端 /trade/development-projects 同一纪律。
 */
export default function DevelopmentSamplesPage() {
  return <DevelopmentSampleWorkspace />;
}
