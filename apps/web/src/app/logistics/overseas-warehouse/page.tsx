import { OverseasWarehouseWorkspace } from '@/components/logistics/overseas-warehouse';

/**
 * 10.4.2.1 海外仓连接。
 *
 * <p>蓝图：「对接海外仓/第三方仓服务商：连接授权、库存同步、订单预报、收发状态回传、
 * 账单对接；管理多仓服务商下的仓账户」。L3 锚点是 10.4.2「海外仓对接」。
 *
 * <p>🔴 加粗约束贯穿整个页面：外部仓库存是**观测值不是自有账本**，与自营库存分开存储与对账、
 * 不能覆盖。所以库存区只有只读出口，没有任何编辑控件——见 OverseasWarehouseWorkspace 顶部说明。
 *
 * <p>与 10.4.1.2 在途跟踪、10.5.1.1 报关同在 `/logistics`：海外仓是头程链条的落点，
 * 观测到的库存要与在途、发运对账才看得出缺口，所以放在同一个域下而不是新开一段导航。
 */
export default function OverseasWarehousePage() {
  return <OverseasWarehouseWorkspace />;
}