import { HandoverBatchWorkspace } from '@/components/logistics/handover-batch';

/**
 * 10.3.2.1 交运批次。
 *
 * <p>蓝图：「把已发货包裹按承运商与交运时间组批，交接给承运商并登记交接清单，
 * 减少交接差错」。L3 锚点是 10.3.2「交运管理」。
 *
 * <p>🔴 页面顶部那句话不是提示语，是这一页的设计前提：**批次交接是责任分界点**——
 * 交接后批次内包裹的丢失责任在承运商，交接清单是唯一凭据。所以清单区只读，
 * 页面上没有任何「改清单」「删一批」的入口。
 *
 * <p>与 10.3.1.1 轨迹查询、10.3.2.2 揽收确认同在 `/logistics`：交运、轨迹、
 * 揽收是一条链上的三段，分开导航会让现场在三个域之间来回跳。
 */
export default function HandoverBatchPage() {
  return <HandoverBatchWorkspace />;
}
