'use client';

import { BomWorkspace } from '@/components/catalog/bom-workspace';

/**
 * BOM 版本：3.5.2.1（L3 锚点路由 `/products/bom`）。
 *
 * <p>路由与 `/products/bundles`（3.5.1 套装与组件）**并列**，名字都叫「版本」但语义不同：
 * 那边是「几个 SKU 捆在一起卖」，这边是「这个成品要领哪些料」。合并会让「v2 指哪个版本」
 * 在 URL 上无法回答。
 *
 * <p>三条必须写在页面上的纪律：
 *
 * <ul>
 *   <li><b>BOM 产实物成品，套装卖虚拟组合。</b>所以 BOM 必须指向一个成品 SKU（套装不占 SKU 行），
 *       08 组装单按它领料、06 按它提加工采购。虚拟套装不持有独立库存，扣的是组件库存。
 *   <li><b>发布后不可变，用量与损耗率一并冻结。</b>唯一例外是把生效终点<em>向前</em>收紧
 *       （提前停用本版）。要改内容只能复制一个新版本 —— 08 已经按当时那一版领过料了。
 *   <li><b>损耗率是百分数，需求量不取整。</b>填 5 就是 5%，不是 500%（填错会让采购量翻 6 倍且
 *       <em>不报错</em>）。需求量是 6 位小数的精确值，取整与缺口判断归 3.5.2.2 与 06。
 * </ul>
 */
export default function BomPage() {
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">BOM 版本</h1>
        <p className="text-muted-foreground text-copy-13">
          维护物料清单（成品由哪些组件组成）的版本：成品 SKU、版本号、生效区间、每个组件的
          <strong>单位用量</strong>（每 1 个成品要多少）与<strong>损耗率</strong>（百分数，5 =
          5%）。
          <strong>发布后整版冻结</strong>，连用量与损耗率一起冻结 —— 08
          加工单已经按当时的数领过料了；
          唯一还能做的是「提前停用本版」（把生效终点向前收紧）。生效区间是左闭右开的 [起点, 终点)，
          重叠一律拒绝，所以<strong>同一时刻至多一个生效版本</strong>。 本页算的是
          <strong>单层</strong>精确需求量（不取整）；多级展开是 3.5.2.2。
          <strong>BOM 产实物成品，套装卖虚拟组合</strong> ——
          两者不合并：套装不持有独立库存，扣的是组件库存。
        </p>
      </header>

      <BomWorkspace />
    </div>
  );
}
