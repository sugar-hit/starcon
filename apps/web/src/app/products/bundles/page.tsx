'use client';

import { useState } from 'react';
import { BundleWorkspace } from '@/components/catalog/bundle-workspace';
import { ComponentWorkspace } from '@/components/catalog/component-workspace';

/**
 * 套装与组件：3.5.1 套装版本（L3），页面上是 3.5.1.1 与 3.5.1.2 两个模块。
 *
 * <p>路由是 `/products/bundles`，与 `/products/content`（3.4.2.x 文案）、
 * `/products/translations`（3.4.3.1 商品多语言）**并列**。这里管的是
 * 「一个虚拟套装由哪些 SKU 按数量捆绑销售、有哪几个版本、此刻生效的是哪一版、
 * 组件之间会不会互相包含成环」。
 *
 * <p>两个模块的分工（V23 文件头第四节定下的边界）：
 *
 * <ul>
 *   <li><b>套装版本（3.5.1.1）</b>：套装档案、版本（草稿/已发布）、生效区间、发布后整版冻结。
 *   <li><b>组件维护（3.5.1.2）</b>：跨版本的组件清单、逐行增删改、<b>循环引用检测</b>、
 *       以及「哪些 SKU 代表哪个套装」这条指向。
 * </ul>
 *
 * <p>放在同一个页面的两个标签下而不是两个页面：运营的心智是「我在管一个套装」，
 * 切到另一个 URL 再切回来会以为丢了上下文；而两者写的是同一份数据，
 * 分成两页只会让人怀疑「这两边是不是各存一份」。
 *
 * <p>三条必须写在页面上的纪律：
 *
 * <ul>
 *   <li><b>虚拟套装不持有独立库存</b> —— 扣的是组件库存（实体目录 §3.4）。
 *       所以这一页管的是「卖什么」，不是「有多少」；库存预占在 07、展开在 09。
 *   <li><b>发布后不可变</b>，组件也随之冻结：唯一例外是把生效终点<em>向前</em>收紧
 *       （提前停用本版）。要改内容只能新建版本 —— 历史订单冻结的是当时那一版的组件清单。
 *   <li><b>不能出现循环引用</b>：套装 A 含 B 且 B 含 A 会让 09 订单的组件展开
 *       在环上无限递归，而订单要冻结的是有限的展开结果。系统拒绝并点名整条环，
 *       不替运营决定断哪一处。
 * </ul>
 */
type Tab = 'versions' | 'components';

export default function BundlePage() {
  const [tab, setTab] = useState<Tab>('versions');
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">套装与组件</h1>
        <p className="text-muted-foreground text-copy-13">
          管理组合套装（多个 SKU 按数量捆绑销售）的版本与组件：名称、版本号、生效区间、组件清单。
          <strong>发布后整版冻结</strong>，改内容要新建版本 —— 历史订单冻结的是当时那一版；
          唯一还能做的是「提前停用本版」（把生效终点向前收紧）。生效区间是左闭右开的 [起点,
          终点)，重叠一律拒绝，所以<strong>同一时刻至多一个生效版本</strong>。 组件之间
          <strong>不能互相包含成环</strong> —— 套装 A 含 B 且 B 含 A 会让订单展开在环上
          无限递归。虚拟套装不持有独立库存，扣的是组件库存。
        </p>
      </header>

      <div className="border-border flex gap-1 border-b" role="tablist">
        {(
          [
            { id: 'versions' as const, label: '套装版本' },
            { id: 'components' as const, label: '组件维护' },
          ] satisfies { id: Tab; label: string }[]
        ).map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            aria-selected={tab === t.id}
            onClick={() => setTab(t.id)}
            className={
              tab === t.id
                ? 'border-primary text-foreground text-copy-13 -mb-px border-b-2 px-3 py-2 font-medium'
                : 'text-muted-foreground hover:text-foreground text-copy-13 -mb-px border-b-2 border-transparent px-3 py-2'
            }
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'versions' ? <BundleWorkspace /> : <ComponentWorkspace />}
    </div>
  );
}
