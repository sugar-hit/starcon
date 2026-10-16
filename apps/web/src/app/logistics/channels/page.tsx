import { LogisticsChannelWorkspace } from '@/components/logistics/logistics-channel-workspace';

/**
 * 10.1.1.2 渠道配置。
 *
 * <p>蓝图：「配置具体物流渠道：所属承运商、渠道代码、适用店铺/国家/品类、计费方式
 * （按重/按件/按体积）、附加费（燃油、偏远、旺季）、申报限制、保价规则」，
 * 输出「渠道配置；供运费试算与自动匹配使用」。L3 锚点是 10.1.1「承运商管理」。
 *
 * <p>🔴 页面顶部那句话不是提示语，是这一页的设计前提：**渠道限制由服务端判定**。
 * 页面把包裹属性发给 /channel/logistics-channels/eligibility 换回 PASS / REVIEW /
 * BLOCK 与逐条理由，而**不在浏览器里比对限制字段** —— 蓝图加粗句是
 * 「渠道限制（禁限品类、电池液体、磁力）要硬拦截，否则会被承运商拒收产生额外费用」，
 * 一个能被绕过的判断不算硬拦截。
 *
 * <p>与 10.1.1.1 承运商列表同在 `/logistics`：承运商与渠道是一条链上的两段
 * （承运商是往来单位的一种角色，渠道挂在某个承运商之下），分开会让运营在两个域
 * 之间来回跳。同 L3 的 10.1.1.3 时效与限制**尚未交付**，本页不预告它的入口 ——
 * 放一个点进去是空页的导航，比没有更糟。
 */
export default function LogisticsChannelsPage() {
  return <LogisticsChannelWorkspace />;
}
