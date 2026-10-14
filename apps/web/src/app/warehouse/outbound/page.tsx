import { OutboundOrderWorkspace } from '@/components/inventory/outbound-order-workspace';

/**
 * 8.3.1.1 出库单列表。
 *
 * <p>蓝图：「出库作业主列表：来源（销售/采购退货/调拨/加工出库）、SKU 行数、件数、状态
 * （待拣/拣货中/待复核/待打包/待发货/已发货/取消）、发货仓、承运渠道。」输出「分页列表 +
 * 批量操作（生成拣货任务、打印、取消）」。
 *
 * <p>这是一个真实工作区而不是占位页：四个筛选维度全部落在后端 SQL 的 WHERE 上、分页是真的、
 * 七态状态机由 Java 枚举与 PostgreSQL 触发器双重约束，三项批量操作逐单独立处理并回报失败原因。
 *
 * <p>刻意**没有**单条详情页：明细行与已拣/已发量、关联销售订单与买家地址、预占引用、
 * 包裹列表、作业时间线属于 8.3.1.2「出库单详情」，那一屏会把整条出库链路串起来。
 */
export default function OutboundOrderPage() {
  return <OutboundOrderWorkspace />;
}
