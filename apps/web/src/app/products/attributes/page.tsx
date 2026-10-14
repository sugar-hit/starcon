'use client';

import { useState } from 'react';
import { AttributeDictionaryWorkspace } from '@/components/catalog/attribute-dictionary-workspace';
import { AttributeOptionWorkspace } from '@/components/catalog/attribute-option-workspace';

/**
 * 属性中心：3.2.1.1 属性定义列表 + 3.2.1.2 属性选项管理。
 *
 * <p>属性字典是**租户级**的全局配置，被所有商品共用，所以路由挂在商品中心根下，
 * 而不是挂在某个商品的详情页里。与 3.1.3.1 的规格轴定义（/products/sku/spec-axis）同级思路：
 * 两者都是字典，只是被绑定的对象不同。
 *
 * <p>两个标签页是**同一份数据的两个视角**，不是两个功能：
 *
 * <ul>
 *   <li>「属性定义」按属性组织：建属性、配类目规则，3.2.1.1 交付。
 *   <li>「选项管理」按选项组织：跨属性看全部取值、停用与排序，3.2.1.2 交付。
 * </ul>
 *
 * <p>后者不做新增选项的入口——登记取值仍然在属性定义页，
 * 免得同一个动作有两个地方能做，界面上却看不出它们的关系。
 */
export default function AttributesPage() {
  const [tab, setTab] = useState<'definition' | 'option'>('definition');

  return (
    <div className="space-y-5">
      <div role="tablist" aria-label="属性中心分区" className="flex flex-wrap gap-1">
        {(
          [
            ['definition', '属性定义'],
            ['option', '选项管理'],
          ] as const
        ).map(([id, label]) => (
          <button
            key={id}
            role="tab"
            type="button"
            aria-selected={tab === id}
            onClick={() => setTab(id)}
            className={
              tab === id
                ? 'bg-muted text-copy-13 flex h-8 items-center gap-2 rounded-md px-3 font-medium'
                : 'text-muted-foreground hover:bg-muted text-copy-13 flex h-8 items-center gap-2 rounded-md px-3'
            }
          >
            {label}
          </button>
        ))}
      </div>

      {tab === 'definition' ? <AttributeDictionaryWorkspace /> : <AttributeOptionWorkspace />}
    </div>
  );
}
