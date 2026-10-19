'use client';

import { MappingVersionWorkspace } from '@/components/mapping-version/mapping-version-workspace';

/**
 * 映射版本：L3「4.1.2 变体映射」下的 4.1.2.2，路由 `/listings/mapping-versions`。
 *
 * <p>这个 L3 下有三个模块：
 *
 * <ul>
 *   <li><b>4.1.2.1 映射管理</b> —— 建立并维护「平台变体 → 内部 SKU/套装」的映射
 *       （一对一、一对多（套装）、多对一，以及批量候选）。
 *       <strong>已交付</strong>，路由 `/listings/mappings`。</li>
 *   <li><b>4.1.2.2 映射版本</b> —— 版本列表、按版本回溯商品范围。
 *       <strong>已交付</strong>，就是本页：改映射生成新版本而不是原地改，
 *       旧版本只读可回溯，每版留存生效时间点与变更原因。
 *       「受影响订单范围」需要 09 订单的真实来源（trade 侧冻结
 *       {@code order_line_component}），本页的能力声明里显式标了 available=false。</li>
 *   <li><b>4.1.2.3 套装组件映射</b> —— 组件 SKU 逐条明细与可售性校验。<strong>尚未开发</strong>。</li>
 * </ul>
 *
 * <p>路由挂在 `/listings/mapping-versions` 而不是 `/listings/mappings` 下再开一个 tab：
 * 版本是<b>跨映射</b>的查询面（可以按 SKU / 店铺 / 生效区间筛，不是挂在某一条映射下面），
 * 与 `/listings/mappings` 的「逐变体维护当前映射」是两种视角。与 `/listings/templates`、
 * `/listings/category-mappings` 各挂一个工作区是同一手法。
 *
 * <p>🔴 后端路由同理用独立前缀 `channel/mapping-versions`，没有挂到 4.1.2.1 的
 * `listing-mappings/{id}/versions` 下面 —— 那条分支是已交付车道的成果，
 * 两个车道改同一行正则必然合并冲突。
 */
export default function MappingVersionsPage() {
  return (
    <div className="space-y-4">
      <MappingVersionWorkspace />
    </div>
  );
}
