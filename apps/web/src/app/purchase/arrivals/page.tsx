import { PurchaseArrivalWorkspace } from '@/components/purchase/purchase-arrival-workspace';

/**
 * 6.3.2.2 到货与逾期。
 *
 * <p>蓝图 06 卷：「跟踪采购单的到货状态：已发货（在途）、部分到货、全部到货、逾期未到、取消。
 * 逾期自动预警并给出处置建议（催货/改供应商/调库存/延期售卖）。
 * 输入：8 仓储的收货事件；跟单记录；物流在途状态（10）。
 * 输出：到货状态 + 逾期预警 + 处置建议。」
 *
 * <p>页面必须让三条硬要求看得见：
 *
 * <ul>
 *   <li><b>可收 / 已收 / 未收三个数并排</b>，带单位与百分比 —— 只给一个「已收数量」的话，
 *       界面上的「还差多少」要靠减法自己算。</li>
 *   <li><b>逾期天数带判定时刻</b>（后端按数据库时钟算）：不说清楚「按哪个时刻算的」，
 *       清单上的天数就没有意义。</li>
 *   <li><b>在途不是逾期</b>：已发货（在途）的单子逾期天数恒为 0，页面用不同文案区分，
 *       不让在途单子被标成逾期。</li>
 * </ul>
 *
 * <p>路由在 {@code /purchase/arrivals}（卷 06 采购与供应）；后端命名空间按服务走，
 * 是 {@code /trade/purchase-arrivals}（与 6.4.1.1 加工单 {@code /trade/processing} 同一纪律）。
 */
export default function PurchaseArrivalsPage() {
  return <PurchaseArrivalWorkspace />;
}
