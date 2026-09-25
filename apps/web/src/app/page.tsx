import Link from 'next/link';
import {
  ArrowRight,
  Boxes,
  CreditCard,
  Package,
  PackageOpen,
  Truck,
  LayoutDashboard,
  Sparkles,
} from 'lucide-react';
import { AppShell, Button, SectionCard, StatCard } from '@starcon/ui';
import { OrderTrendChart } from '@/components/order-trend-chart';

const stats = [
  { label: '今日订单', value: 12847, delta: 12.5, deltaDirection: 'up' as const, polarity: 'positive' as const, comparisonLabel: '较昨日', icon: <Package className="h-4 w-4" /> },
  { label: '今日营收', value: 2483950, unit: 'CNY', delta: 8.2, deltaDirection: 'up' as const, polarity: 'positive' as const, comparisonLabel: '较昨日', icon: <CreditCard className="h-4 w-4" /> },
  { label: '待处理订单', value: 384, delta: 4.2, deltaDirection: 'up' as const, polarity: 'negative' as const, comparisonLabel: '较昨日', icon: <Boxes className="h-4 w-4" /> },
  { label: '库存预警 SKU', value: 23, delta: 12.0, deltaDirection: 'down' as const, polarity: 'negative' as const, comparisonLabel: '较昨日', icon: <PackageOpen className="h-4 w-4" /> },
];

export default function HomePage() {
  return (
    <AppShell title="工作台">
      {/* 欢迎 / 跳转 banner */}
      <div className="flex flex-col gap-2 rounded-lg border border-border bg-card p-5 md:flex-row md:items-center md:justify-between">
        <div className="space-y-1">
          <div className="flex items-center gap-2 text-label-12 uppercase tracking-wider text-brand">
            <Sparkles className="h-3.5 w-3.5" />
            设计系统已就绪
          </div>
          <div className="text-h-20 text-foreground tracking-tight">
            视觉规范 v1.0 已落地
          </div>
          <div className="text-copy-13 text-muted-foreground">
            前往 Styleguide 查看所有设计令牌、组件与图表。
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="outline" asChild>
            <Link href="https://github.com/sugar-hit/starcon" target="_blank">
              查看仓库
            </Link>
          </Button>
          <Button variant="brand" asChild>
            <Link href="/styleguide">
              进入 Styleguide
              <ArrowRight className="h-4 w-4" />
            </Link>
          </Button>
        </div>
      </div>

      {/* KPI 4 卡（双向趋势演示） */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {stats.map((s) => (
          <StatCard key={s.label} {...s} />
        ))}
      </div>

      {/* 订单趋势图 + 平台分布 */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <SectionCard
          title="订单趋势"
          description="近 7 天订单量走势（主色 vs 同期对比）"
          className="lg:col-span-2"
          bodyClassName="px-2 py-3"
        >
          <OrderTrendChart />
        </SectionCard>

        <SectionCard title="平台分布" description="本周订单来源">
          <div className="space-y-3">
            {[
              { name: 'Amazon', share: 42, color: 'hsl(32 95% 44%)' },
              { name: 'Shopify', share: 26, color: 'hsl(152 56% 38%)' },
              { name: 'eBay', share: 14, color: 'hsl(220 70% 45%)' },
              { name: 'TikTok Shop', share: 11, color: 'hsl(330 75% 55%)' },
              { name: '其他', share: 7, color: 'hsl(0 0% 60%)' },
            ].map((p) => (
              <div key={p.name} className="space-y-1.5">
                <div className="flex items-center justify-between text-copy-13">
                  <span className="text-foreground">{p.name}</span>
                  <span className="num text-muted-foreground">{p.share}%</span>
                </div>
                <div className="h-1.5 overflow-hidden rounded-full bg-muted">
                  <div
                    className="h-full rounded-full"
                    style={{ width: `${p.share}%`, background: p.color }}
                  />
                </div>
              </div>
            ))}
          </div>
        </SectionCard>
      </div>
    </AppShell>
  );
}