import { ShipmentWritebackWorkspace } from '@/components/orders/shipment-writeback-workspace';

/**
 * 9.4.2.1 平台回传 / 回传状态。
 *
 * <p>蓝图 09 卷：「把发货信息（运单号、承运商、发货时间）回传平台并跟踪结果：
 * 成功 / 失败 / <b>结果未知</b>；支持批量重试与人工修正。」
 * 输入是「发货记录；平台回调结果」，输出是「回传状态清单 + 明细」。
 *
 * <p>这是一个真实工作区而不是占位页：监控汇总、分组看板、回传任务清单与明细、
 * 三态登记、查证结论登记、单条 / 批量重试与人工修正，全部落到 PostgreSQL 16，
 * 加载态 / 空态 / 错误态三态齐全。
 *
 * <p>路由取 {@code /orders/fulfillment/shipment-writeback}，与 9.4.1.2 的
 * {@code /orders/fulfillment/shipment-records} <b>并列而不是嵌套</b>：
 * 发货记录是「已经发生了什么」的事实台账，本模块是「把它回传给平台、并跟踪回传结果」
 * 的执行与状态机。挂到它下面会让界面暗示「在发货记录详情页里就能管回传」，
 * 而两者是两个独立入口、不同生命周期。
 *
 * <p>这一页与后端共同钉住蓝图里那句加粗：「回传状态必须<b>可监控可补偿</b>，
 * <b>结果未知要先查证再重发</b>」。界面上「结果未知」单列一格、染琥珀色、
 * 且在明细里<b>只</b>保留「登记查证结论」一个出路 —— 重试按钮在未知态不出现。
 */
export default function OrdersFulfillmentShipmentWritebackPage() {
  return <ShipmentWritebackWorkspace />;
}
