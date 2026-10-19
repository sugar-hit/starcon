import { StockoutAlertWorkspace } from '@/components/inventory/stockout-alert-workspace';

/**
 * 7.8.2.2 缺货与饥饿预警。
 *
 * <p>蓝图：「识别因分摊/优先级导致的店铺"饥饿"（长期分不到货、影响绩效），以及真正的全网缺货，区分两者给出不同处置建议。输入：分摊结果；店铺销售与绩效；总可用量。输出：饥饿店铺清单 + 缺货清单 + 建议（调优先级/调保留/补货）。」
 *
 * <p>这是一个真实工作区而不是占位页：饥饿店铺清单与缺货清单都落在 PostgreSQL 上
 * （V192 的 starvation_alert / stockout_alert 两张互不共表的表），
 * 而不是内存里的假数据。
 *
 * <p>路由说明：L3「7.8 多平台可售量分摊」的路由是 `/inventory/allocation`，
 * 而它已被 7.8.1.1（分配策略配置）占用。本页按 7.8.1.2 / 7.8.2.1 的先例取同族兄弟路由
 * `/inventory/stockout-alerts`；同时在 `/inventory/allocation/stockout-alerts` 保留一个
 * 指向同一工作区的入口，让 L3 路由下也能直达。
 */
export default function StockoutAlertPage() {
  return <StockoutAlertWorkspace />;
}
