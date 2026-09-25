'use client';

import {
  AlertTriangle,
  ArrowRight,
  Check,
  Download,
  Info,
  Plus,
  Search,
  Settings as SettingsIcon,
  Trash2,
} from 'lucide-react';
import {
  AppShell,
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
  EmptyState,
  Input,
  Modal,
  ModalCloseButton,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  ModalTrigger,
  SectionCard,
  Segmented,
  StatCard,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  cn,
  numClass,
} from '@starcon/ui';
import { OrderTrendChart } from '@/components/order-trend-chart';
import { BarRevenueChart } from '@/components/bar-revenue-chart';

const sampleOrders = [
  { id: 'ORD-20260925-001', platform: 'Amazon', customer: 'Lena Park', amount: 1248.5, currency: 'USD', status: 'completed' as const },
  { id: 'ORD-20260925-002', platform: 'Shopify', customer: 'Marco Liu', amount: 89.0, currency: 'USD', status: 'shipped' as const },
  { id: 'ORD-20260925-003', platform: 'eBay', customer: 'Yuki Tanaka', amount: 412.3, currency: 'USD', status: 'pending' as const },
  { id: 'ORD-20260925-004', platform: 'TikTok Shop', customer: 'Ahmed Khan', amount: 56.8, currency: 'USD', status: 'refund' as const },
  { id: 'ORD-20260925-005', platform: 'Amazon', customer: 'Sofia García', amount: 789.0, currency: 'USD', status: 'completed' as const },
  { id: 'ORD-20260925-006', platform: 'Shopify', customer: 'Jonas Müller', amount: 234.5, currency: 'USD', status: 'pending' as const },
];

const statusBadge: Record<string, { tone: Parameters<typeof Badge>[0]['tone']; label: string }> = {
  completed: { tone: 'success', label: '已完成' },
  shipped: { tone: 'info', label: '已发货' },
  pending: { tone: 'warning', label: '待处理' },
  refund: { tone: 'danger', label: '退款中' },
};

const colorSwatches = [
  { name: 'background', var: '--background', hex: '#FAFAFA', note: '页面主背景' },
  { name: 'foreground', var: '--foreground', hex: '#171717', note: '主文字' },
  { name: 'primary', var: '--primary', hex: '#0A0A0A', note: '主 CTA（近黑）' },
  { name: 'muted', var: '--muted', hex: '#F5F5F5', note: '次级背景' },
  { name: 'muted-foreground', var: '--muted-foreground', hex: '#707070', note: '次级文字' },
  { name: 'border', var: '--border', hex: 'rgba(0,0,0,0.08)', note: '默认边框' },
  { name: 'destructive', var: '--destructive', hex: 'rose-600', note: '危险 / 退款' },
  { name: 'success', var: '--success', hex: 'emerald-700', note: '成功 / 已完成' },
  { name: 'warning', var: '--warning', hex: 'amber-600', note: '警告 / 待处理' },
  { name: 'brand', var: '--brand', hex: '#1D4ED8', note: '品牌强调（链接/选中）' },
  { name: 'brand-soft', var: '--brand-soft', hex: '#EFF6FF', note: '强调背景软底' },
];

export default function StyleguidePage() {
  return (
    <AppShell title="设计系统总览">
      {/* === 0. 顶部说明 === */}
      <div className="rounded-lg border border-border bg-card p-6">
        <div className="flex items-start gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-brand-soft text-brand">
            <Info className="h-5 w-5" />
          </div>
          <div className="space-y-1">
            <h2 className="text-h-24 text-foreground tracking-tight">
              starcon 视觉规范 v1.0
            </h2>
            <p className="text-copy-14 text-muted-foreground">
              所有 token 来自 <code className="rounded bg-muted px-1.5 py-0.5 text-label-12">docs/design/visual-spec.md</code>。
              修改任何视觉令牌必须先改 spec，再改 <code className="rounded bg-muted px-1.5 py-0.5 text-label-12">packages/config-tailwind/src/preset.cjs</code>。
            </p>
          </div>
        </div>
      </div>

      {/* === 1. 颜色 Token === */}
      <SectionCard
        title="颜色令牌"
        description="11 个 HSL 语义 token；品牌色 --brand 可换 HSL 不改 token 名"
      >
        <div className="grid grid-cols-2 gap-3 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
          {colorSwatches.map((c) => (
            <div
              key={c.name}
              className="overflow-hidden rounded-md border border-border bg-card"
            >
              <div
                className="h-16 w-full border-b border-border"
                style={{ background: c.hex }}
              />
              <div className="space-y-0.5 p-3">
                <div className="font-mono text-label-12 text-foreground">
                  --{c.name}
                </div>
                <div className="font-mono text-[11px] text-muted-foreground">
                  {c.hex}
                </div>
                <div className="text-copy-13 text-muted-foreground">{c.note}</div>
              </div>
            </div>
          ))}
        </div>
      </SectionCard>

      {/* === 2. Tailwind 灰阶 0-9 === */}
      <SectionCard
        title="Gray 0-9 灰阶"
        description="模拟 Vercel 0-9 scale；只这 10 档，禁止用其他灰"
      >
        <div className="space-y-2">
          {[
            { n: 0, hex: '#FAFAFA' },
            { n: 1, hex: '#F5F5F5' },
            { n: 2, hex: '#EBEBEB' },
            { n: 3, hex: '#D1D1D1' },
            { n: 4, hex: '#A3A3A3' },
            { n: 5, hex: '#707070' },
            { n: 6, hex: '#484848' },
            { n: 7, hex: '#222222' },
            { n: 8, hex: '#0A0A0A' },
            { n: 9, hex: '#000000' },
          ].map((g) => (
            <div key={g.n} className="flex items-center gap-3">
              <div className="w-20 font-mono text-label-12 text-muted-foreground">
                gray-{g.n}
              </div>
              <div
                className={cn(
                  'h-9 w-20 rounded-md border',
                  g.n < 5 ? 'border-border' : 'border-transparent'
                )}
                style={{ background: g.hex }}
              />
              <div className="font-mono text-label-12 text-muted-foreground">
                {g.hex}
              </div>
            </div>
          ))}
        </div>
      </SectionCard>

      {/* === 3. 14 档字号 === */}
      <SectionCard
        title="14 档字号"
        description="h-N（标题） / copy-N（正文） / label-N（小标签） / btn-N（按钮）"
      >
        <div className="space-y-4">
          {[
            { cls: 'text-h-48', label: 'h-48 / 48 / 500', sample: '巨型数字' },
            { cls: 'text-h-32', label: 'h-32 / 32 / 500', sample: 'KPI 主数字' },
            { cls: 'text-h-24', label: 'h-24 / 24 / 500', sample: '区块标题' },
            { cls: 'text-h-20', label: 'h-20 / 20 / 500', sample: '重要副标题' },
            { cls: 'text-h-16', label: 'h-16 / 16 / 500', sample: '卡片标题 / Section 标题' },
            { cls: 'text-copy-18', label: 'copy-18 / 18 / 400', sample: '次级正文' },
            { cls: 'text-copy-14', label: 'copy-14 / 14 / 400', sample: '绝大多数正文、helper' },
            { cls: 'text-copy-13', label: 'copy-13 / 13 / 400', sample: '卡片副标题、description' },
            { cls: 'text-label-14', label: 'label-14 / 14 / 500', sample: '表单 label' },
            { cls: 'text-label-12', label: 'label-12 / 12 / 500 UPPER', sample: 'TABLE HEADER / BADGE' },
            { cls: 'text-btn-14', label: 'btn-14 / 14 / 500', sample: '按钮文字' },
          ].map((f) => (
            <div key={f.cls} className="flex items-baseline gap-4">
              <div className="w-40 shrink-0 font-mono text-label-12 text-muted-foreground">
                {f.label}
              </div>
              <div className={cn(f.cls, 'text-foreground')}>{f.sample}</div>
            </div>
          ))}
        </div>
      </SectionCard>

      {/* === 4. Button 变体 === */}
      <SectionCard
        title="Button"
        description="主 CTA 永远是黑色 (bg-primary)；品牌蓝只做强调"
      >
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="default">主操作</Button>
          <Button variant="outline">次级</Button>
          <Button variant="secondary">低优先</Button>
          <Button variant="ghost">返回</Button>
          <Button variant="brand">采纳建议</Button>
          <Button variant="destructive">删除</Button>
          <Button variant="success">确认</Button>
          <Button variant="link">链接</Button>
        </div>
        <div className="mt-4 flex flex-wrap items-center gap-2">
          <Button size="sm">小尺寸</Button>
          <Button size="default">默认</Button>
          <Button size="lg">大尺寸</Button>
          <Button size="icon" aria-label="设置">
            <SettingsIcon className="h-4 w-4" />
          </Button>
          <Button disabled>禁用</Button>
        </div>
      </SectionCard>

      {/* === 5. Badge 7 tone === */}
      <SectionCard
        title="Badge"
        description="7 种 tone，跟语义挂钩（订单状态 / 任务状态）"
      >
        <div className="flex flex-wrap gap-2">
          <Badge tone="neutral">通用</Badge>
          <Badge tone="primary">黑色实心</Badge>
          <Badge tone="success">已完成</Badge>
          <Badge tone="warning">待处理</Badge>
          <Badge tone="danger">退款中</Badge>
          <Badge tone="info">已发货</Badge>
          <Badge tone="brand">品牌色</Badge>
        </div>
      </SectionCard>

      {/* === 6. StatCard 双向趋势 === */}
      <SectionCard
        title="StatCard（双向趋势）"
        description="polarity 控制涨是好事还是坏事：营收涨=绿，退款涨=红"
      >
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard
            label="今日营收"
            value={2483950}
            unit="CNY"
            delta={12.5}
            deltaDirection="up"
            polarity="positive"
            comparisonLabel="较昨日"
            icon={<ArrowRight className="h-4 w-4" />}
          />
          <StatCard
            label="今日退款"
            value={143}
            delta={4.2}
            deltaDirection="up"
            polarity="negative"
            comparisonLabel="较昨日"
            icon={<Trash2 className="h-4 w-4" />}
          />
          <StatCard
            label="待处理订单"
            value={384}
            delta={2.1}
            deltaDirection="down"
            polarity="negative"
            comparisonLabel="较昨日"
            icon={<AlertTriangle className="h-4 w-4" />}
          />
          <StatCard
            label="库存预警 SKU"
            value={23}
            delta={12.0}
            deltaDirection="down"
            polarity="positive"
            comparisonLabel="较昨日"
            icon={<Check className="h-4 w-4" />}
          />
        </div>
        <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-3">
          <StatCard label="中性指标：库存数" value={18342} delta={0} deltaDirection="flat" />
          <StatCard label="等宽数字" value={1234567.89} delta={5.2} deltaDirection="up" polarity="positive" comparisonLabel="demo" />
          <StatCard label="极小数字" value={3} delta={0.5} deltaDirection="up" polarity="positive" comparisonLabel="demo" />
        </div>
      </SectionCard>

      {/* === 7. Segmented / Tabs === */}
      <SectionCard
        title="Segmented & Tabs"
        description="Segmented 用于时间范围切换；Tabs 用于内容视图切换"
      >
        <div className="space-y-6">
          <div className="flex items-center gap-4">
            <span className="text-copy-13 text-muted-foreground">时间范围：</span>
            <Segmented
              value="week"
              onChange={() => {}}
              options={[
                { label: '今日', value: 'day' },
                { label: '近 7 天', value: 'week' },
                { label: '本月', value: 'month' },
                { label: '本年', value: 'year' },
              ]}
            />
          </div>
          <Tabs defaultValue="overview">
            <TabsList>
              <TabsTrigger value="overview">概览</TabsTrigger>
              <TabsTrigger value="trend">趋势</TabsTrigger>
              <TabsTrigger value="detail">明细</TabsTrigger>
            </TabsList>
            <TabsContent value="overview" className="rounded-lg border border-border bg-card p-5 text-copy-14">
              概览面板内容 — 默认进入时 fade-up 240ms 动画。
            </TabsContent>
            <TabsContent value="trend" className="rounded-lg border border-border bg-card p-5 text-copy-14">
              趋势分析面板。
            </TabsContent>
            <TabsContent value="detail" className="rounded-lg border border-border bg-card p-5 text-copy-14">
              明细数据表。
            </TabsContent>
          </Tabs>
        </div>
      </SectionCard>

      {/* === 8. Card & SectionCard === */}
      <SectionCard
        title="Card & SectionCard"
        description="Card = 基础卡（1px 边 + 8px 圆角）；SectionCard = 页面级分区卡（带 header）"
      >
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <Card>
            <CardHeader>
              <CardTitle>基础 Card 标题</CardTitle>
              <CardDescription>副标题说明，可不填</CardDescription>
            </CardHeader>
            <CardContent>
              <p className="text-copy-14 text-foreground">
                默认 1px 边框 + 8px 圆角 + 无阴影。Hover 只换边框深度，不叠 4 件套。
              </p>
            </CardContent>
            <CardFooter className="justify-end gap-2">
              <Button variant="ghost" size="sm">
                取消
              </Button>
              <Button size="sm">确认</Button>
            </CardFooter>
          </Card>

          <SectionCard
            title="订单趋势"
            description="近 7 天订单量"
            extra={
              <Segmented
                value="week"
                onChange={() => {}}
                options={[
                  { label: '今日', value: 'day' },
                  { label: '近 7 天', value: 'week' },
                ]}
              />
            }
          >
            <p className="text-copy-14 text-muted-foreground">
              SectionCard 的头部用 border-b 分隔，extra 槽永远在右上角。
            </p>
          </SectionCard>
        </div>
      </SectionCard>

      {/* === 9. Input / Modal === */}
      <SectionCard title="Input & Modal" description="表单输入 + 浅遮罩 Modal">
        <div className="flex flex-wrap items-start gap-4">
          <div className="space-y-2">
            <label className="text-label-13 text-muted-foreground">搜索</label>
            <div className="relative">
              <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input placeholder="搜索订单号..." className="pl-9 w-72" />
            </div>
          </div>

          <div className="space-y-2">
            <label className="text-label-13 text-muted-foreground">禁用态</label>
            <Input placeholder="不可编辑" disabled className="w-72" />
          </div>

          <Modal>
            <ModalTrigger asChild>
              <Button variant="outline">打开 Modal</Button>
            </ModalTrigger>
            <ModalContent>
              <ModalHeader>
                <ModalTitle>确认创建订单</ModalTitle>
                <ModalDescription>
                  此操作将创建一笔新订单，创建后状态为"待审核"。
                </ModalDescription>
              </ModalHeader>
              <ModalCloseButton />
              <div className="text-copy-14 text-foreground">
                浅遮罩（bg-black/40 backdrop-blur-sm），进入动画 fade-in（遮罩）+ fade-up（本体）。
              </div>
              <ModalFooter>
                <Button variant="ghost">取消</Button>
                <Button>确认创建</Button>
              </ModalFooter>
            </ModalContent>
          </Modal>
        </div>
      </SectionCard>

      {/* === 10. 数据表 === */}
      <SectionCard
        title="数据表"
        description="订单列表 — 平台 / 客户 / 金额 / 状态；hover 只换底色，不叠 4 件套"
      >
        <div className="overflow-hidden rounded-md border border-border">
          <table className="w-full text-left text-copy-14">
            <thead className="border-b border-border bg-muted/40 text-label-12 uppercase tracking-wider text-muted-foreground">
              <tr>
                <th className="px-4 py-3 font-medium">订单号</th>
                <th className="px-4 py-3 font-medium">平台</th>
                <th className="px-4 py-3 font-medium">客户</th>
                <th className="px-4 py-3 font-medium text-right">金额</th>
                <th className="px-4 py-3 font-medium">状态</th>
                <th className="px-4 py-3 font-medium text-right">操作</th>
              </tr>
            </thead>
            <tbody>
              {sampleOrders.map((o, i) => (
                <tr
                  key={o.id}
                  className={cn(
                    'border-b border-border last:border-b-0 transition-colors duration-base ease-out-expo hover:bg-black/[0.02]',
                    i % 2 === 1 && 'bg-muted/10'
                  )}
                >
                  <td className="px-4 py-3 font-mono text-label-13">{o.id}</td>
                  <td className="px-4 py-3 text-foreground">{o.platform}</td>
                  <td className="px-4 py-3 text-foreground">{o.customer}</td>
                  <td className="px-4 py-3 text-right">
                    <span className={numClass}>
                      {o.currency === 'USD' ? '$' : '¥'}
                      {o.amount.toFixed(2)}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <Badge tone={statusBadge[o.status].tone}>
                      {statusBadge[o.status].label}
                    </Badge>
                  </td>
                  <td className="px-4 py-3 text-right">
                    <Button variant="ghost" size="sm">
                      详情
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </SectionCard>

      {/* === 11. 图表 === */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <SectionCard
          title="订单趋势"
          description="Recharts AreaChart — 主色蓝 + 中性灰对比"
          bodyClassName="px-2 py-3"
        >
          <OrderTrendChart />
        </SectionCard>
        <SectionCard
          title="营收对比"
          description="Recharts BarChart — 顶 6px 圆角"
          bodyClassName="px-2 py-3"
        >
          <BarRevenueChart />
        </SectionCard>
      </div>

      {/* === 12. EmptyState === */}
      <SectionCard title="EmptyState" description="极简空态，不要画插画、不要加渐变">
        <EmptyState
          icon={<Info className="h-6 w-6" />}
          title="暂无订单"
          description="当有新订单进来时，它们会出现在这里。试试切换上方时间范围。"
          action={<Button variant="outline">查看文档</Button>}
        />
      </SectionCard>

      {/* === 13. 用户硬规则速查 === */}
      <SectionCard
        title="用户硬规则速查"
        description="任何新页面 / 新组件必须逐条对照"
      >
        <ol className="space-y-2 text-copy-14">
          <li className="flex gap-3">
            <span className="num flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-brand-soft text-label-12 text-brand">
              1
            </span>
            <span>
              顶栏只显示当前页面 <code className="text-label-12 text-muted-foreground">title</code>，<b>不要</b>{" "}
              group 前缀（如 <code className="text-label-12 text-rose-600">数据 | 订单概览</code>）。
            </span>
          </li>
          <li className="flex gap-3">
            <span className="num flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-brand-soft text-label-12 text-brand">
              2
            </span>
            <span>
              桌面端导航只在左栏，<b>右上角不许有 hamburger</b>。
            </span>
          </li>
          <li className="flex gap-3">
            <span className="num flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-brand-soft text-label-12 text-brand">
              3
            </span>
            <span>
              移动端 hamburger 在右上，点击从右滑出抽屉，头部 <b>X 在左 / Logo 在右</b>。
            </span>
          </li>
          <li className="flex gap-3">
            <span className="num flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-brand-soft text-label-12 text-brand">
              4
            </span>
            <span>
              Hover 反馈跟周围组件风格保持一致，<b>亮色模式不叠 4 件套</b>。
            </span>
          </li>
          <li className="flex gap-3">
            <span className="num flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-brand-soft text-label-12 text-brand">
              5
            </span>
            <span>
              页面 UI 文案只展示对当前访客有用的内容，<b>不写"我们接下来要做 X"</b>。开发 TODO 走{' '}
              <code className="text-label-12 text-muted-foreground">docs/backlog/</code>。
            </span>
          </li>
        </ol>
      </SectionCard>
    </AppShell>
  );
}