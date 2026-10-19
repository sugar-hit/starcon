'use client';

import { useState } from 'react';
import { DescriptionTemplateWorkspace } from '@/components/catalog/description-template-workspace';
import { ListingTemplateWorkspace } from '@/components/listing-template/listing-template-workspace';

/**
 * 刊登模板工作区：L3「4.2.2 模板」的锚点路由 `/listings/templates`。
 *
 * <p>这个 L3 下有两个模块：
 *
 * <ul>
 *   <li><b>4.2.2.1 刊登模板</b> —— 类目级 / 店铺级的字段填充规则（取自商品属性、
 *       引用文案库、套用图片组），一键套用到目标商品，产出**内容草稿**。
 *   <li><b>4.2.2.2 描述模板</b> —— 富文本描述模板：五段段落结构、变量占位、
 *       多语言版本、图片插入位，产出渲染后的描述 HTML。
 * </ul>
 *
 * <p>两者分属 L3 的两个子节点、解决不同的问题：4.2.2.1 管「一整条 listing 的字段怎么填」，
 * 4.2.2.2 管「详情页正文怎么组织」。所以现在这一页加成页签分开 ——
 * 与 `/products/content` 里标题库 / 描述库用页签分开是同一手法。
 */
export default function ListingTemplatesPage() {
  const [tab, setTab] = useState<'listing' | 'description'>('listing');
  const TABS = [
    { key: 'listing' as const, label: '刊登模板（4.2.2.1）' },
    { key: 'description' as const, label: '描述模板（4.2.2.2）' },
  ];

  return (
    <div className="space-y-4">
      <nav className="border-border flex gap-1 border-b" aria-label="模板工作区">
        {TABS.map((item) => (
          <button
            key={item.key}
            type="button"
            aria-current={tab === item.key ? 'page' : undefined}
            onClick={() => setTab(item.key)}
            className={
              tab === item.key
                ? 'border-primary text-foreground -mb-px border-b-2 px-3 py-2 text-sm font-medium'
                : 'text-muted-foreground hover:text-foreground -mb-px border-b-2 border-transparent px-3 py-2 text-sm'
            }
          >
            {item.label}
          </button>
        ))}
      </nav>
      {tab === 'listing' ? <ListingTemplateWorkspace /> : <DescriptionTemplateWorkspace />}
    </div>
  );
}
