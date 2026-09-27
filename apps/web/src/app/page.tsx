'use client';

import * as React from 'react';
import Link from 'next/link';
import { ArrowUpRight, Clock3, Globe2, Layers3, ListTodo, ArrowRight } from 'lucide-react';
import { SectionCard, useWorkspaceTabs, WorldClockStrip } from '@starcon/ui';

const priorities = [
  {
    title: '审核订单',
    caption: '处理地址、库存与风控异常',
    href: '/orders/review',
    group: '订单履约',
  },
  {
    title: '安排补货',
    caption: '联动在途、交期与安全库存',
    href: '/inventory/replenishment',
    group: '库存协同',
  },
  {
    title: '跟进货件',
    caption: '核对 FBA 发运与签收差异',
    href: '/logistics/fba',
    group: '跨境物流',
  },
  { title: '核对结算', caption: '复核平台账单与原币回款', href: '/finance', group: '财务结算' },
];

export default function HomePage() {
  const [now, setNow] = React.useState<Date | null>(null);
  const { tabs, activateTab } = useWorkspaceTabs();
  React.useEffect(() => {
    const tick = () => setNow(new Date());
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, []);
  return (
    <div className="space-y-6">
      <section aria-label="工作台概览" className="space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
            <h1 className="text-h-20 font-semibold tracking-tight">工作台</h1>
            <p className="text-copy-13 text-muted-foreground" data-testid="beijing-date">
              {now
                ? new Intl.DateTimeFormat('zh-CN', {
                    timeZone: 'Asia/Shanghai',
                    year: 'numeric',
                    month: 'long',
                    day: 'numeric',
                    weekday: 'long',
                  }).format(now)
                : '正在读取日期'}
            </p>
          </div>
          <span className="text-copy-12 text-muted-foreground flex items-center gap-1.5">
            <Globe2 className="h-3.5 w-3.5" />
            全球时间
          </span>
        </div>
        <WorldClockStrip />
      </section>

      <section aria-label="日常任务">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-h-16 font-semibold">从一件事开始</h2>
          <span className="text-copy-12 text-muted-foreground">贯穿采购、履约与回款</span>
        </div>
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {priorities.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className="border-border bg-card hover:border-control-border group rounded-lg border p-4 transition-colors"
            >
              <div className="text-copy-12 text-muted-foreground mb-4 flex items-center justify-between">
                <span>{item.group}</span>
                <ArrowUpRight className="group-hover:text-brand h-4 w-4" />
              </div>
              <p className="text-h-16 font-semibold">{item.title}</p>
              <p className="text-copy-13 text-muted-foreground mt-2">{item.caption}</p>
            </Link>
          ))}
        </div>
      </section>

      <div className="grid items-start gap-4 xl:grid-cols-3">
        <SectionCard
          title="待办与关注"
          description="把需要处理的事情放在同一个地方"
          className="xl:col-span-2"
        >
          <div className="grid gap-3 sm:grid-cols-3">
            {['待审核订单', '库存风险', '物流异常'].map((title) => (
              <div key={title} className="bg-muted/50 rounded-md p-3">
                <p className="text-copy-13">{title}</p>
                <p className="text-h-24 text-muted-foreground my-2">—</p>
                <p className="text-copy-12 text-muted-foreground">等待业务数据接入</p>
              </div>
            ))}
          </div>
          <div className="text-copy-13 text-muted-foreground mt-5 flex items-start gap-2">
            <ListTodo className="mt-0.5 h-4 w-4 shrink-0" />
            <p>连接店铺与仓库后，在这里汇总按负责人、处理期限排列的任务。</p>
          </div>
          <Link
            href="/org/stores"
            className="text-copy-13 text-brand mt-4 inline-flex items-center gap-1"
          >
            管理店铺连接 <ArrowRight className="h-3.5 w-3.5" />
          </Link>
        </SectionCard>
        <SectionCard title="继续处理" description="返回工作台不会关闭已打开的页面">
          {tabs.length ? (
            <ul className="max-h-64 space-y-1 overflow-y-auto">
              {tabs.map((tab) => (
                <li key={tab.id}>
                  <button
                    type="button"
                    onClick={() => activateTab(tab.id)}
                    className="hover:bg-muted text-copy-13 flex w-full items-center gap-2 rounded-md px-2 py-2 text-left"
                  >
                    <Layers3 className="text-muted-foreground h-4 w-4 shrink-0" />
                    <span className="min-w-0 flex-1 truncate">{tab.label}</span>
                    <ArrowUpRight className="h-3.5 w-3.5 shrink-0" />
                  </button>
                </li>
              ))}
            </ul>
          ) : (
            <div className="text-copy-13 text-muted-foreground py-6">
              <Clock3 className="mb-3 h-5 w-5" />
              从左侧展开业务分组，打开一个任务开始工作。
            </div>
          )}
        </SectionCard>
      </div>

      <SectionCard title="准备好你的业务空间" description="建议按以下顺序完成基础配置">
        <div className="grid gap-4 md:grid-cols-3">
          {[
            ['01', '连接销售渠道', '配置平台店铺与授权状态', '/org/stores'],
            ['02', '整理商品资料', '建立 SKU 与平台商品对应关系', '/products'],
            ['03', '配置仓库与库存', '明确可用库存、库位和履约范围', '/warehouse'],
          ].map(([step, title, description, href]) => (
            <Link
              key={step}
              href={href!}
              className="hover:bg-muted group flex min-w-0 gap-3 rounded-md p-2"
            >
              <span className="text-brand text-label-12 pt-1 font-semibold">{step}</span>
              <div>
                <p className="text-label-14">{title}</p>
                <p className="text-copy-13 text-muted-foreground mt-1">{description}</p>
              </div>
            </Link>
          ))}
        </div>
      </SectionCard>
    </div>
  );
}
