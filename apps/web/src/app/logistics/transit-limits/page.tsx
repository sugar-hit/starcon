import { LogisticsTransitWorkspace } from '@/components/logistics/logistics-transit-workspace';

/**
 * 10.1.1.3 时效与限制。
 *
 * <p>蓝图：「维护渠道时效（分国家分重量段）与限制（尺寸上限、重量上限、禁限品类、
 * 电池/液体/危险品标记要求），并在选品与刊登阶段提示」，输出「时效估算；限制命中提示」。
 * L3 锚点是 10.1.1「承运商管理」。
 *
 * <p>🔴 页面顶部那句话不是提示语，是这一页的设计前提：**限制命中由服务端判定**。
 * 页面把 SKU 安全属性与包裹尺寸重量发给 /channel/logistics-transit-limits/precheck
 * 换回 PASS / REVIEW / BLOCK 与逐条理由，而**不在浏览器里比对上限与标记要求** ——
 * 蓝图加粗句是「渠道限制（禁限品类、电池液体、磁力）要硬拦截，否则会被承运商拒收
 * 产生额外费用」，一个能被绕过的判断不算硬拦截。
 *
 * <p>与 10.1.1.2 渠道配置同在 `/logistics`：渠道与它的时效限制是一条链上的两段
 * （时效与限制挂在渠道之下），分开会让运营在两个域之间来回跳。
 */
export default function LogisticsTransitLimitsPage() {
  return <LogisticsTransitWorkspace />;
}
