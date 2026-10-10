import { ProcessingOrderWorkspace } from '@/components/purchase/processing-order-workspace';

/**
 * 6.4.1.1 加工单。
 *
 * <p>蓝图 06 卷：「发起委外/自有工厂加工：选择成品 SKU 与 BOM 版本、指定组件领用量与加工厂、
 * 约定完工数量与交期，跟踪加工进度与入库。」输出「加工单（组件领用记录 + 完工数量）；
 * 入库需求通知 8」。
 *
 * <p>页面必须让两条硬要求看得见：
 *
 * <ul>
 *   <li><b>组件领用量来自已发布的 BOM 版本</b>，按 catalog 3.5.2.1 的规则 R7 算出
 *       （单位用量 × 产量 × (1 + 损耗率/100)，损耗率是<b>百分数</b>），页面只显示不重算 ——
 *       所以新建时先试算再下单，而不是让人手填「每个组件要几件」。
 *   <li><b>加工是「消耗组件产生成品」的库存动作</b>：组件出库与成品入库在 warehouse 的
 *       一个本地事务里完成。本页只登记加工单据与领用进度，<b>不直接改库存</b>。
 * </ul>
 */
export default function ProcessingOrdersPage() {
  return <ProcessingOrderWorkspace />;
}
