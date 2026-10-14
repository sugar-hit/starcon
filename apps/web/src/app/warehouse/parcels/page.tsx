import { ParcelDetailWorkspace } from '@/components/warehouse/parcel-detail-workspace';

/**
 * 10.2.2.1 包裹详情。
 *
 * <p>蓝图 10 卷：「<b>单包裹视图：包裹内 SKU 与数量（parcel_item）、订单行对应关系、重量体积、
 * 面单、轨迹、交运信息。</b>输入：parcel_id；关联订单行与轨迹。」
 * 输出：「包裹详情 + 操作入口。」关系：「上游 = 8.3.3.2；下游 = 10.3.1.1、12 财务（运费成本归集）。
 * <b>parcel_item 与订单行的对应关系是成本归集到行的基础，缺了就只能算到订单级。</b>」
 *
 * <p>页面必须让四条硬要求看得见：
 *
 * <ul>
 *   <li><b>订单行对应关系是主角。</b>每条明细项下面列它关联到的销售订单行与分摊件数；
 *       没关联上时整块标「只能归到订单级」并逐条列名，而不是静默按订单摊。</li>
 *   <li><b>没量 ≠ 0。</b>未称重/未量尺寸显示「未量」，不显示 0 g / 0 cbm。</li>
 *   <li><b>轨迹与交运画成灰块。</b>本服务没有这两段数据，写明归谁未接入 ——
 *       空白的详情段会被读成「这一票没有轨迹」。</li>
 *   <li><b>体积重不试算。</b>除数是承运商渠道口径，归 10 物流，页面不替渠道定价。</li>
 * </ul>
 *
 * <p>路由挂在 {@code /warehouse/parcels} 而不是 {@code /warehouse/packing-orders} 之下：
 * 10 物流查轨迹、12 财务做运费归集时手里拿的是 parcel_id，不是作业单 id。
 */
export default function ParcelDetailPage() {
  return <ParcelDetailWorkspace />;
}
