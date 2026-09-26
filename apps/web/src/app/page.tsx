import Link from 'next/link';
import { ArrowRight, Box, CheckCircle2, Clock3 } from 'lucide-react';
import { AppShell, Button, DomainPlaceholder, getDomainSpec, SectionCard } from '@starcon/ui';

/**
 * 工作台 — 首页
 *
 * 设计原则:
 *   - 不展示 mock 数据
 *   - 顶部放快速入口,引导用户进入具体 domain
 *   - 主区域展示工作台 domain 的功能清单与上下游
 */
export default function HomePage() {
  const spec = getDomainSpec('overview');
  return (
    <AppShell
      title="工作台"
      description="今日关键指标与待办,业务一日开屏。"
      actions={
        <Button variant="outline" asChild>
          <Link href="/orders">
            订单中心
            <ArrowRight className="h-4 w-4" />
          </Link>
        </Button>
      }
    >
      <div className="text-copy-13 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <span className="font-medium">2026 年 9 月 26 日</span>
          <span className="text-muted-foreground">Asia/Shanghai</span>
        </div>
        <span className="text-copy-13 text-muted-foreground">
          指标与图表将在数据接入后展示
        </span>
      </div>

      <SectionCard
        title="快捷入口"
        description="按任务类型快速进入业务"
        bodyClassName="p-0"
      >
        <div className="divide-border divide-y">
          {[
            {
              label: '处理新订单',
              description: '检查订单信息与履约条件',
              href: '/orders',
              icon: Clock3,
            },
            {
              label: '关注库存',
              description: '检查可用量与补货需求',
              href: '/inventory',
              icon: Box,
            },
            {
              label: '核对结算',
              description: '按币种与店铺核对账目',
              href: '/finance',
              icon: CheckCircle2,
            },
          ].map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className="hover:bg-muted/50 flex items-center gap-3 p-5"
            >
              <item.icon className="text-muted-foreground h-4 w-4 shrink-0" />
              <div className="flex-1">
                <p className="text-label-13">{item.label}</p>
                <p className="text-copy-12 text-muted-foreground mt-1">{item.description}</p>
              </div>
              <ArrowRight className="text-muted-foreground h-4 w-4" />
            </Link>
          ))}
        </div>
      </SectionCard>

      {spec && <DomainPlaceholder spec={spec} />}
    </AppShell>
  );
}