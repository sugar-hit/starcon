import { ReplenishmentSuggestionWorkspace } from '@/components/purchase/replenishment-suggestion-workspace';

/**
 * 6.2.1.2 补货建议。
 *
 * <p>蓝图 06 卷：「计算建议补货量：目标库存覆盖天数 × 日均销量 −（可用 + 在途），并结合 MOQ、
 * 箱规、采购起订额做取整修正，给出建议供应商与到货时间。」输出「补货建议（建议量、取整说明、
 * 预计到货日、预计费用）；支持接受/调整/忽略」。
 *
 * <p>页面必须让两件事看得见：
 *
 * <ul>
 *   <li><b>建议带计算过程</b>：每条建议都能展开看到日均销量怎么来的、扣了哪些在途、
 *       取整怎么做的 —— 蓝图加粗句「建议必须展示计算过程，用户不信任的预测不会被采纳」。</li>
 *   <li><b>「答不出来」不是 0</b>：缺日均销量 / 缺有效报价 / 缺箱规 与「无需补货」
 *       是四个不同的状态，页面显示各自的名字 —— 把「没登记数据」显示成 0 会被读成「库存充足」。</li>
 * </ul>
 *
 * <p>前端路由是 {@code /purchase/demand/replenishment}（6.2.1 需求生成下的补货建议分区），
 * 后端命名空间是 {@code /trade/replenishment-suggestions} —— 与 6.2.1.1 前端 /purchase/demand ↔
 * 后端 /trade/stockout-risk、9.3.2.2 前端 /orders ↔ 后端 /trade/fulfillment/routing 同一纪律。
 */
export default function PurchaseDemandReplenishmentPage() {
  return <ReplenishmentSuggestionWorkspace />;
}
