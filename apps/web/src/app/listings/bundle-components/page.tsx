'use client';

import { BundleComponentWorkspace } from '@/components/bundle-component/bundle-component-workspace';

/**
 * 套装组件映射：L3「4.1.2 变体映射」下的 4.1.2.3，路由 `/listings/bundle-components`。
 *
 * <p>这个 L3 下有三个模块：
 *
 * <ul>
 *   <li><b>4.1.2.1 映射管理</b> —— 建立并维护「平台变体 → 内部 SKU/套装」的映射。
 *       <strong>已交付</strong>，路由 `/listings/mappings`。</li>
 *   <li><b>4.1.2.2 映射版本</b> —— 版本列表、按版本回溯商品范围。
 *       <strong>已交付</strong>，路由 `/listings/mapping-versions`。</li>
 *   <li><b>4.1.2.3 套装组件映射</b> —— 组件 SKU 逐条明细与可售性校验。
 *       <strong>已交付</strong>，就是本页：平台变体与不同套装版本对应，逐条维护组件数量，
 *       并按目标仓库校验可售性。<b>组件数量必须大于 0，缺任一组件时整个套装不可售</b>。</li>
 * </ul>
 *
 * <p>路由挂在 `/listings/bundle-components` 而不是 `/listings/mappings` 下再开一个 tab：
 * 组件明细与可售性是**跨映射**的查询面（可以按套装版本 / 店铺 / 生效状态筛），
 * 与 `/listings/mappings` 的「逐变体维护当前映射」是两种视角。与
 * `/listings/mapping-versions` 各挂一个工作区是同一手法。
 *
 * <p>🔴 后端路由同理用独立前缀 `channel/bundle-components`，没有挂到 4.1.2.1 的
 * `listing-mappings/{id}/components` 下面 —— 那条分支是已交付车道的成果，
 * 两个车道改同一行正则必然合并冲突。
 */
export default function BundleComponentsPage() {
  return (
    <div className="space-y-4">
      <BundleComponentWorkspace />
    </div>
  );
}
