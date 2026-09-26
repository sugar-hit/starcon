'use client';

import {
  AppShell,
  Badge,
  DetailDrawer,
  DetailDrawerBody,
  DetailDrawerContent,
  DetailDrawerDescription,
  DetailDrawerFooter,
  DetailDrawerHeader,
  DetailDrawerTitle,
  DomainPlaceholder,
  ExternalLink,
  getDomainSpec,
  Notice,
  SectionCard,
} from '@starcon/ui';
import { useState } from 'react';
import { Package, Boxes, Users } from 'lucide-react';

/**
 * 订单中心 — 多平台订单聚合
 *
 * 当前页面状态:scaffold
 * - 展示订单中心应具备的功能清单与上下游数据流
 * - 不展示 mock 数据(用户硬规则:永远不要 mock)
 * - 顶部"交互预览"展示本轮新加的 DetailDrawer + ExternalLink 能力
 * - 实施某个 feature 时,修改 packages/ui/src/lib/domain-spec.ts 中对应 status
 */
export default function OrdersPage() {
  const spec = getDomainSpec('orders');
  const [drawerOpen, setDrawerOpen] = useState(false);
  return (
    <AppShell
      title="订单中心"
      description="集中查看跨平台订单,关注每一笔履约进度。"
    >
      <SectionCard
        title="交互预览"
        description="本轮新增的详情抽屉与跨模块跳转能力,接入真实数据后即可用于查看订单/SKU/客户档案。"
      >
        <div className="flex flex-wrap items-center gap-3">
          <button
            type="button"
            onClick={() => setDrawerOpen(true)}
            className="border-border bg-card text-copy-13 hover:bg-muted inline-flex h-9 items-center gap-2 rounded-md border px-3"
          >
            <Package className="h-4 w-4" />
            打开订单详情抽屉
          </button>
          <span className="text-copy-12 text-muted-foreground">
            点击触发右侧 Drawer · 不离开当前列表上下文
          </span>
        </div>
        <div className="mt-4">
          <Notice tone="info" title="演示说明">
            抽屉演示区仅展示字段结构,业务数据接入后将从订单源填充。
          </Notice>
        </div>
      </SectionCard>

      <SectionCard
        title="跨模块跳转"
        description="订单处理时常需跳转其他模块核对信息。下方链接按住 Cmd/Ctrl 点击可在新窗口打开,不打断当前流程。"
      >
        <ul className="space-y-2">
          <li className="text-copy-13 flex items-center gap-2">
            <Package className="text-muted-foreground h-4 w-4 shrink-0" />
            <span className="text-muted-foreground shrink-0">查看该订单的商品 →</span>
            <ExternalLink href="/products" hint="在新窗口打开商品中心">
              商品中心
            </ExternalLink>
          </li>
          <li className="text-copy-13 flex items-center gap-2">
            <Boxes className="text-muted-foreground h-4 w-4 shrink-0" />
            <span className="text-muted-foreground shrink-0">核对库存与发货仓位 →</span>
            <ExternalLink href="/inventory" hint="在新窗口打开库存中心">
              库存中心
            </ExternalLink>
          </li>
          <li className="text-copy-13 flex items-center gap-2">
            <Users className="text-muted-foreground h-4 w-4 shrink-0" />
            <span className="text-muted-foreground shrink-0">查看客户档案 / 历史订单 →</span>
            <ExternalLink href="/crm" hint="在新窗口打开 CRM / 客服">
              CRM / 客服
            </ExternalLink>
          </li>
        </ul>
      </SectionCard>

      {spec && <DomainPlaceholder spec={spec} />}

      <DetailDrawer open={drawerOpen} onOpenChange={setDrawerOpen}>
        <DetailDrawerContent width="md">
          <DetailDrawerHeader>
            <DetailDrawerTitle>订单详情</DetailDrawerTitle>
            <DetailDrawerDescription>
              字段结构示例 · 真实数据接入后,字段值将从订单源填充
            </DetailDrawerDescription>
          </DetailDrawerHeader>
          <DetailDrawerBody>
            <dl className="text-copy-14 grid grid-cols-2 gap-x-6 gap-y-4">
              <div>
                <dt className="text-copy-12 text-muted-foreground mb-1">订单号</dt>
                <dd className="break-words font-medium">— 平台单号 / 店铺单号</dd>
              </div>
              <div>
                <dt className="text-copy-12 text-muted-foreground mb-1">平台 / 店铺</dt>
                <dd className="break-words font-medium">— 接入数据后展示</dd>
              </div>
              <div>
                <dt className="text-copy-12 text-muted-foreground mb-1">客户</dt>
                <dd className="break-words font-medium">— CRM 关联</dd>
              </div>
              <div>
                <dt className="text-copy-12 text-muted-foreground mb-1">商品明细</dt>
                <dd className="break-words font-medium">— SPU + SKU 列表</dd>
              </div>
              <div>
                <dt className="text-copy-12 text-muted-foreground mb-1">交易金额</dt>
                <dd className="break-words font-medium">— 原币 + 换算后 CNY</dd>
              </div>
              <div>
                <dt className="text-copy-12 text-muted-foreground mb-1">履约仓库</dt>
                <dd className="break-words font-medium">— 多仓路由结果</dd>
              </div>
              <div>
                <dt className="text-copy-12 text-muted-foreground mb-1">物流商 / 单号</dt>
                <dd className="break-words font-medium">— 调用路由推荐</dd>
              </div>
              <div>
                <dt className="text-copy-12 text-muted-foreground mb-1">当前状态</dt>
                <dd>
                  <Badge tone="neutral">占位</Badge>
                </dd>
              </div>
              <div className="col-span-2">
                <dt className="text-copy-12 text-muted-foreground mb-1">操作日志</dt>
                <dd className="break-words font-medium">— 全留痕</dd>
              </div>
            </dl>
          </DetailDrawerBody>
          <DetailDrawerFooter>
            <span className="text-copy-12 text-muted-foreground mr-auto">
              数据接入后:打印面单 / 申请退款 / 修改物流
            </span>
          </DetailDrawerFooter>
        </DetailDrawerContent>
      </DetailDrawer>
    </AppShell>
  );
}