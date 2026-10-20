import { StockForecastWorkspace } from '@/components/logistics/stock-forecast';

/**
 * 10.4.2.2 备货预报。
 *
 * <p>蓝图：「向海外仓/平台仓提交备货计划：SKU 与数量、目的仓、期望到货时间、平台货件编号，
 * 接收仓端接收与拒收反馈。」L3 锚点是 10.4.2「海外仓对接」。
 *
 * <p>🔴 加粗约束贯穿整个页面：**平台仓备货有严格规格**（箱型、贴标、托盘、混装限制），
 * 规格不符会被拒收造成额外成本与延误。所以页面上「备货规格」是一等公民，
 * 规格不过闸就不给提交入口 —— 见 StockForecastWorkspace。
 *
 * <p>与 10.4.2.1 海外仓连接同在 `/logistics`：目的仓（仓账户）来自那一页，
 * 备货预报是它的下游动作，两页放在同一个域下才说得清「这批货备到哪个仓」。
 */
export default function StockForecastPage() {
  return <StockForecastWorkspace />;
}
