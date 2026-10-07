'use client';

import { BundleWorkspace } from '@/components/catalog/bundle-workspace';

/**
 * 套装版本管理：3.5.1.1。
 *
 * <p>路由是 `/products/bundles`，与 `/products/content`（3.4.2.x 文案）、
 * `/products/translations`（3.4.3.1 商品多语言）**并列**。这里管的是
 * 「一个虚拟套装由哪些 SKU 按数量捆绑销售、有哪几个版本、此刻生效的是哪一版」。
 *
 * <p>两条必须写在页面上的纪律：
 *
 * <ul>
 *   <li><b>虚拟套装不持有独立库存</b> —— 扣的是组件库存（实体目录 §3.4）。
 *       所以这一页管的是「卖什么」，不是「有多少」；库存预占在 07、展开在 09。
 *   <li><b>发布后不可变</b>，唯一例外是把生效终点<em>向前</em>收紧（提前停用本版）。
 *       要改内容只能新建版本 —— 历史订单冻结的是当时那一版的组件清单。
 * </ul>
 *
 * <p>与 3.5.1.2 组件维护的分工：本页交付的是「随草稿版本编辑组件清单 + 发布时校验齐备」；
 * 组件的循环引用检测与独立组件工作台归 3.5.1.2。
 */
export default function BundleVersionPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">套装版本</h1>
        <p className="text-muted-foreground text-copy-13">
          管理组合套装（多个 SKU 按数量捆绑销售）的版本：名称、组件清单、版本号、生效区间。
          <strong>发布后整版冻结</strong>，改内容要新建版本 —— 历史订单冻结的是当时那一版；
          唯一还能做的是「提前停用本版」（把生效终点向前收紧）。生效区间是左闭右开的
          [起点, 终点)，重叠一律拒绝，所以<strong>同一时刻至多一个生效版本</strong>。
          虚拟套装不持有独立库存，扣的是组件库存。
        </p>
      </header>
      <BundleWorkspace />
    </div>
  );
}