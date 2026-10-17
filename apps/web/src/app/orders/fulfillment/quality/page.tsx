import { FulfillmentQualityWorkspace } from '@/components/orders/fulfillment-quality-workspace';

/**
 * 9.5.2.1 履约质量。
 *
 * <p>蓝图 09 卷：「统计履约质量：发货及时率、完整发货率、错发漏发率、取消率、
 * 纠纷率、退货率，按店铺与人员维度对比。」输入是「订单履约与售后数据；平台绩效指标同步」，
 * 输出是「质量指标卡 + 明细下钻」。
 *
 * <p>这是一个真实工作区而不是占位页：六个指标全部由 PostgreSQL 16 上的显式 SQL
 * 现算（不落表），每个指标卡同时给分子与分母；店铺（平台级）与人员两个维度都是真
 * GROUP BY；指标卡可下钻到构成它的明细单；纠纷/退货与错发/漏发登记落租户可维护表；
 * 预警阈值可配、预警 append-only 留痕；加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /orders/fulfillment/quality}，与 pending-shipment /
 * shipment-records / timeliness **并列而不是嵌套**：那几组回答「怎么发 / 发没发 /
 * 发得快不快」，本组回答「做得对不对」。刻意不挂在 timeliness 之下 ——
 * 那是 9.5.1.1 的「时效」命名空间，一个只读度量模块挂在被度量对象路径下，
 * 会让人以为改这里能改时效事实。
 *
 * <p>界面上如实写明四处缺口：错发的 SKU 级来源、售后真实数据（卷 11 未交付）、
 * shop_code（D5 未交付，当前只能到平台级）、与 16/17 的联动消费方。
 * 指标卡上「0%」在有缺口时表达的是「没人登记」，**不是**「没问题」——
 * 界面对此有独立的缺口区，不让它混在数字里。
 */
export default function OrdersFulfillmentQualityPage() {
  return <FulfillmentQualityWorkspace />;
}
