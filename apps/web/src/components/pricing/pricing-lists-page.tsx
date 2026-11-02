'use client';

import { useState } from 'react';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@starcon/ui';

import { PriceListItemWorkspace } from './price-list-item-workspace';
import { PriceListWorkspace } from './price-list-workspace';

/**
 * `/pricing/lists` 的两个分段：**价目表**（5.1.1.1）与**价目项**（5.1.1.2）。
 *
 * <p>蓝图 05 卷：「5.1 价目表 → /pricing/lists」—— 5.1.1.1 与 5.1.1.2 是**同一条路由**
 * 的两个分段（价目表是表头，价目项是表里的明细），所以这里用页签把两个工作区并列，
 * 而不是另建一个路由（另建路由会让「价目表」和「它的价目项」变成两个页面，
 * 运营切过去就找不到自己在哪张表里）。
 *
 * <p>🔴 本文件**只**做分段切换：两个工作区各自持有自己的状态与请求，
 * 互不干扰 —— 5.1.1.1 的既有实现一个字都没改。
 */
export function PricingListsPage() {
  const [tab, setTab] = useState('lists');

  return (
    <Tabs value={tab} onValueChange={setTab}>
      <TabsList>
        <TabsTrigger value="lists">价目表</TabsTrigger>
        <TabsTrigger value="items">价目项</TabsTrigger>
      </TabsList>
      <TabsContent value="lists">
        <PriceListWorkspace />
      </TabsContent>
      <TabsContent value="items">
        <PriceListItemWorkspace />
      </TabsContent>
    </Tabs>
  );
}
