'use client';

import { useState } from 'react';
import { TitleWorkspace } from '@/components/catalog/title-workspace';
import { DescriptionWorkspace } from '@/components/catalog/description-workspace';

/**
 * 内容资产工作区：3.4.2.1 标题库 + 3.4.2.2 描述与卖点库。
 *
 * <p>路由是 `/products/content`，是 L3「3.4.2 文案资产」的锚点路由。
 * 蓝图把标题与描述放在同一个 L3 下（3.4.2「标题与内容库」），所以它们共用一个页面，
 * 但**不合并成一个工作区**——两者的排版约束根本不同：
 *
 * <ul>
 *   <li><b>3.4.2.1 标题库</b>：标题是<strong>有平台硬字符上限的短文本</strong>
 *       （eBay 80 / 亚马逊 200 / Shopify 255），核心矛盾是「相似但不雷同」
 *       的重复铺货判定。
 *   <li><b>3.4.2.2 描述与卖点库</b>：详情描述 / 使用说明 / 注意事项是
 *       <strong>无字符上限的长文本</strong>，核心矛盾是「变量缺失必须报告，
 *       不能静默留下占位符」与多语言版本一致性。
 * </ul>
 *
 * <p>把长文本卖点塞进标题变量只会让标题长度控制失去意义，所以两个工作区用页签分开：
 * 切到「描述与卖点库」时看不到标题的平台长度上限，切到「标题库」时看不到
 * 卖点短语池。运维上它们也是两条线——标题跟平台规则与关键词策略走，
 * 描述跟品类与说明书走。
 */
export default function ContentAssetsPage() {
  const [tab, setTab] = useState<'titles' | 'descriptions'>('titles');
  const TABS = [
    { key: 'titles' as const, label: '标题库（3.4.2.1）' },
    { key: 'descriptions' as const, label: '描述与卖点库（3.4.2.2）' },
  ];

  return (
    <div className="space-y-4">
      <nav className="border-border flex gap-1 border-b" aria-label="内容资产工作区">
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
      {tab === 'titles' ? <TitleWorkspace /> : <DescriptionWorkspace />}
    </div>
  );
}
