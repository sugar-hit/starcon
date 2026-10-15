import { OrderTimelinessWorkspace } from '@/components/orders/order-timeliness-workspace';

/**
 * 9.5.1.1 时效分析。
 *
 * <p>蓝图 09 卷：「统计订单时效分布：下单到出库、出库到交接、承诺到货 vs 实际到货，
 * 按店铺、仓库、商品、承运商维度拆解。」输入是「各环节时间戳；承诺时效配置；轨迹实际时间」，
 * 输出是「时效报表 + 分维度对比 + 超时明细」。
 *
 * <p>这是一个真实工作区而不是占位页：能力声明与缺口、时效报表（四段逐段统计 +
 * 发货超时三桶 + 承诺四桶）、四维拆解、超时明细、事实清单与明细、承诺/实际到货
 * 登记、事件流，全部落到 PostgreSQL 16，加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /orders/fulfillment/timeliness}，与 pending-shipment /
 * shipment-records 等**并列而不是嵌套**：那两组回答「这票货怎么发 / 发没发」，
 * 本组回答「发得够不够快」，在导航与权限上该分得开。刻意不挂在 shipment-records
 * 之下 —— 那是「已发货事实」的命名空间，一个只读度量模块挂在被度量对象路径下，
 * 会让人以为改这里能改已发货事实。
 *
 * <p>界面上如实写明三处缺口：轨迹实际时间（10.3 未交付）、承诺时效配置
 * （2.5.1.1 未交付）、下游 13 分析与 16 合规（均未交付）。前两处的处理是
 * **逐票登记**，界面上「未登记」**不等于**系统抓到了数据。
 */
export default function OrdersFulfillmentTimelinessPage() {
  return <OrderTimelinessWorkspace />;
}
