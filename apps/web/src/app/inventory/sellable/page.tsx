import { InventorySellableWorkspace } from '@/components/inventory/inventory-sellable-workspace';

/**
 * 7.1.1.3 可用量计算。
 *
 * <p>蓝图：「统一计算并展示可售量（available），明确其口径：bucket 可用 = 在手 − 预占；
 * 可售 = 合格且允许销售的 bucket 可用量之和，再按策略扣一次安全缓冲。待检、残次、未上架等被排除的
 * bucket 不重复扣减；支持查看构成拆解。」以及关系段的「<b>可售量是全系统的单一口径来源</b>，
 * 不允许各系统各自算 —— 这是超卖的第一根源」。
 *
 * <p>页面按 L3 锚点挂在 /inventory 下，与总览（7.1.1.1）、明细（7.1.1.2）同属库存账本。
 * 本页不存任何派生数据：算式在 warehouse 服务里，每次数都是现算的
 * （可售量不落库、不留历史快照 —— 回看一个时刻的可售量没有定义）。
 */
export default function InventorySellablePage() {
  return <InventorySellableWorkspace />;
}
