'use client';

import { useState } from 'react';
import Link from 'next/link';
import { ArrowRight, Check, Layers3, LockKeyhole, Plus, Search, X } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Logo,
  Modal,
  ModalClose,
  ModalCloseButton,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalTitle,
  Notice,
  SectionCard,
  Segmented,
  StatCard,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@starcon/ui';

const sections = [
  ['principles', '设计原则'],
  ['brand', '品牌'],
  ['foundations', '基础样式'],
  ['components', '组件'],
  ['data', '数据界面'],
  ['forms', '表单与反馈'],
];

const palette = [
  ['background', '画布'],
  ['foreground', '主要文字'],
  ['primary', '主要操作'],
  ['brand', '链接 / 选中'],
  ['success', '成功'],
  ['warning', '需关注'],
  ['destructive', '错误 / 危险'],
  ['muted', '次级表面'],
];

export default function StyleguidePage() {
  const [state, setState] = useState('empty');
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');
  const [notice, setNotice] = useState('');
  return (
    <>
      <nav
        aria-label="设计系统目录"
        className="border-border flex flex-wrap gap-x-5 gap-y-3 border-b pb-4"
      >
        {sections.map(([id, label]) => (
          <a
            key={id}
            href={`#${id}`}
            className="text-label-13 text-muted-foreground hover:text-brand"
          >
            {label}
          </a>
        ))}
      </nav>

      <section id="principles" className="space-y-5">
        <div className="border-border bg-card flex flex-wrap items-center justify-between gap-6 rounded-lg border px-6 py-7">
          <div className="min-w-0 max-w-2xl flex-1 basis-80">
            <div className="text-label-12 text-brand mb-3 flex items-center gap-2">
              <Layers3 className="h-4 w-4" />
              STARCON DESIGN SYSTEM
            </div>
            <h2 className="text-h-24 font-semibold tracking-tight">让复杂业务,清晰有序。</h2>
            <p className="text-copy-14 text-muted-foreground mt-3 max-w-xl leading-relaxed">
              为跨平台交易、库存协同和财务核对设计。用清晰的层级减少查找,用一致的交互建立信任,把注意力留给重要的业务决策。
            </p>
            <div className="mt-5 flex flex-wrap gap-2">
              <Button asChild>
                <a href="#data">
                  查看数据界面
                  <ArrowRight className="h-4 w-4" />
                </a>
              </Button>
              <Button variant="outline" asChild>
                <Link href="/">打开工作台</Link>
              </Button>
            </div>
          </div>
          <div className="border-brand text-copy-13 text-muted-foreground border-l-2 pl-5">
            <p className="text-foreground font-semibold">一套语言,四个系统</p>
            <p className="mt-2">开发 · 运营 · 物流 · 仓储</p>
            <p className="mt-2">清晰 / 克制 / 可核对</p>
          </div>
        </div>
        <div className="grid gap-5 md:grid-cols-3">
          {[
            [
              '01',
              '数据先于装饰',
              '让金额、状态和下一步操作清晰可见。用留白组织信息,不让卡片与图标争夺注意力。',
            ],
            [
              '02',
              '操作有明确结果',
              '一个区域突出一个主要操作。提交、处理中、成功与失败分别表达,保留用户的上下文。',
            ],
            [
              '03',
              '密度服务于任务',
              '日常运营支持紧凑表格;触控场景保留操作空间。租户、币种与数据范围始终有据可查。',
            ],
          ].map(([n, title, desc]) => (
            <div key={n} className="px-1">
              <p className="text-label-12 text-muted-foreground mb-2">{n}</p>
              <h3 className="text-label-14 font-semibold">{title}</h3>
              <p className="text-copy-13 text-muted-foreground mt-2">{desc}</p>
            </div>
          ))}
        </div>
      </section>

      <section id="brand" className="space-y-4">
        <SectionHeading
          number="01"
          title="品牌与识别"
          description="四个节点连接成星。保留识别,减少干扰。"
        />
        <div className="grid gap-4 md:grid-cols-3">
          <div className="border-border bg-card flex min-h-40 items-center justify-center rounded-lg border p-6">
            <Logo size="lg" />
          </div>
          <div className="bg-primary flex min-h-40 items-center justify-center rounded-lg p-6">
            <Logo size="lg" tone="dark" />
          </div>
          <div className="border-border bg-brand-soft flex min-h-40 flex-col items-center justify-center gap-5 rounded-lg border p-6">
            <Logo size="md" tone="brand" />
            <p className="text-copy-12 text-muted-foreground">品牌蓝用于识别与数字化场景</p>
          </div>
        </div>
        <p className="text-copy-12 text-muted-foreground">
          默认使用单色组合标志。窄屏仅显示图形标志;图形与文字保持自然比例,不拉伸、不增加光晕。副字标为
          Commerce OS。
        </p>
      </section>

      <section id="foundations" className="space-y-4">
        <SectionHeading
          number="02"
          title="基础样式"
          description="中性骨架、明确语义。所有色块直接读取实际设计令牌。"
        />
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {palette.map(([token, label]) => (
            <div key={token} className="border-border bg-card overflow-hidden rounded-lg border">
              <div
                className="border-border h-16 border-b"
                style={{ backgroundColor: `hsl(var(--${token}))` }}
              />
              <div className="p-3">
                <p className="text-label-13">{label}</p>
                <code className="text-copy-12 text-muted-foreground mt-1 block break-all">
                  --{token}
                </code>
              </div>
            </div>
          ))}
        </div>
        <div className="grid gap-4 lg:grid-cols-2">
          <SectionCard title="排版层级" description="以角色定义字号,以字重建立秩序。">
            <div className="space-y-5">
              <TypeSample label="页面标题 · 20 / 600">
                <span className="text-h-20 font-semibold">订单中心</span>
              </TypeSample>
              <TypeSample label="区块标题 · 16 / 600">
                <span className="text-h-16 font-semibold">待处理订单</span>
              </TypeSample>
              <TypeSample label="正文 · 14 / 400">
                <span className="text-copy-14">在不同平台间,保持一致的工作方式。</span>
              </TypeSample>
              <TypeSample label="辅助 · 12 / 400">
                <span className="text-copy-12 text-muted-foreground">
                  原币金额 · 汇率以结算日为准
                </span>
              </TypeSample>
              <TypeSample label="指标 · 24 / 500">
                <span className="num text-h-24">—</span>
              </TypeSample>
            </div>
          </SectionCard>
          <SectionCard title="间距与形态" description="4px 基础网格,让界面保持稳定的节奏。">
            <div className="space-y-4">
              {[4, 8, 12, 16, 24, 32].map((n) => (
                <div key={n} className="flex items-center gap-4">
                  <span className="text-label-12 text-muted-foreground w-10">{n}px</span>
                  <div className="bg-brand/80 h-3 rounded-sm" style={{ width: n * 4 }} />
                </div>
              ))}
            </div>
            <div className="border-border text-copy-13 text-muted-foreground mt-5 border-t pt-4">
              控件圆角 6px · 容器圆角 8px
              <br />
              普通卡片用边框,浮层使用阴影。桌面控件 36px,紧凑行 44px。
            </div>
          </SectionCard>
        </div>
      </section>

      <section id="components" className="space-y-4">
        <SectionHeading
          number="03"
          title="组件与语义"
          description="动作、状态与趋势各司其职。颜色不能替代文字。"
        />
        <div className="grid gap-4 lg:grid-cols-2">
          <SectionCard title="操作层级" description="主要操作保持近黑色,次要操作使用描边。">
            <div className="flex flex-wrap gap-2">
              <Button
                onClick={() => {
                  setOpen(true);
                  setError('');
                }}
              >
                <Plus className="h-4 w-4" />
                新建视图
              </Button>
              <Button variant="outline" onClick={() => setNotice('次要操作适合导出、编辑和配置。')}>
                次要操作
              </Button>
              <Button variant="ghost" onClick={() => setNotice('轻量操作用于取消或关闭当前任务。')}>
                轻量操作
              </Button>
              <Button disabled>不可用</Button>
            </div>
            <div className="mt-4 flex flex-wrap gap-2">
              <Button
                variant="destructive"
                onClick={() => setNotice('危险操作必须说明对象与影响;此处仅演示样式。')}
              >
                危险操作示例
              </Button>
              <Button variant="link" asChild>
                <a href="#forms">
                  查看表单规范
                  <ArrowRight className="h-4 w-4" />
                </a>
              </Button>
            </div>
            {notice && (
              <p role="status" className="text-copy-13 text-muted-foreground mt-4">
                {notice}
              </p>
            )}
          </SectionCard>
          <SectionCard title="业务状态" description="进度与异常分开表达,退款处理中不等同于失败。">
            <div className="flex flex-wrap gap-2">
              <Badge tone="neutral">草稿</Badge>
              <Badge tone="warning">待审核</Badge>
              <Badge tone="info">已发货</Badge>
              <Badge tone="success">已完成</Badge>
              <Badge tone="danger">同步失败</Badge>
            </div>
            <p className="text-copy-13 text-muted-foreground mt-4">
              支付、履约、售后应分别展示状态。品牌色不承担额外的业务含义。
            </p>
          </SectionCard>
        </div>
        <SectionHeading
          number="03.1"
          title="指标卡 StatCard"
          description="展示业务指标与趋势。本页仅展示组件形态,具体数值在业务页接入。"
        />
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="指标名" value="—" unit="CNY" />
          <StatCard
            label="指标名"
            value="—"
            unit="笔"
            delta={12}
            deltaDirection="up"
            polarity="positive"
            comparisonLabel="较昨日"
          />
          <StatCard
            label="指标名"
            value="—"
            unit="笔"
            delta={4}
            deltaDirection="up"
            polarity="negative"
            comparisonLabel="较昨日"
          />
          <StatCard
            label="指标名"
            value="—"
            delta={12}
            deltaDirection="down"
            polarity="negative"
            comparisonLabel="较昨日"
          />
        </div>
      </section>

      <section id="data" className="space-y-4">
        <SectionHeading
          number="04"
          title="数据界面"
          description="筛选与密度可调,空数据有清晰解释。具体行/图在业务页接入后展示。"
        />
        <SectionCard title="表格容器形态" description="展示表头、筛选条、密度切换的视觉结构。">
          <div className="border-border flex flex-wrap items-end gap-3 border-b p-4">
            <div className="min-w-0 flex-1 basis-52">
              <div className="relative">
                <Search className="text-muted-foreground pointer-events-none absolute left-3 top-2.5 h-4 w-4" />
                <Input className="pl-9" placeholder="搜索订单号 / 客户 / 店铺" readOnly />
              </div>
            </div>
            <div className="min-w-0 flex-1 basis-32">
              <select className="field-control" disabled>
                <option>全部平台</option>
              </select>
            </div>
            <div className="min-w-0 flex-1 basis-32">
              <select className="field-control" disabled>
                <option>全部状态</option>
              </select>
            </div>
            <Segmented
              aria-label="表格密度"
              value="comfortable"
              onChange={() => undefined}
              options={[
                { label: '舒适', value: 'comfortable' },
                { label: '紧凑', value: 'compact' },
              ]}
            />
          </div>
          <EmptyState
            icon={<Search className="h-5 w-5" />}
            title="数据接入后展示"
            description="业务页接入订单源后,表格将在此渲染记录。"
          />
        </SectionCard>
      </section>

      <section id="forms" className="space-y-4">
        <SectionHeading
          number="05"
          title="表单与反馈"
          description="告诉用户当前发生了什么,以及下一步可以做什么。"
        />
        <div className="grid gap-4 lg:grid-cols-2">
          <SectionCard title="表单任务" description="标签明确、就地校验、取消可退出。">
            <div className="space-y-4">
              <FormField
                id="readonly-shop"
                label="所属店铺"
                hint="只读信息保留可读性,与不可用控件区分。"
              >
                <Input value="Amazon · US / Northstar" readOnly />
              </FormField>
              <FormField
                id="disabled-field"
                label="平台订单号"
                hint="由平台同步生成,无需手动填写。"
              >
                <Input placeholder="自动生成" disabled />
              </FormField>
              <Button
                onClick={() => {
                  setOpen(true);
                  setError('');
                }}
              >
                <Plus className="h-4 w-4" />
                新建订单视图
              </Button>
              {saved && (
                <Notice tone="success" title={`已创建示例视图「${saved}」`}>
                  仅保留在本次页面会话中。
                </Notice>
              )}
            </div>
          </SectionCard>
          <SectionCard title="持续反馈" description="重要状态留在页面,不依赖一闪而过的提示。">
            <div className="space-y-3">
              <Notice tone="warning" title="部分订单尚未同步">
                2 家店铺的数据有延迟,请以最近同步时间为准。
              </Notice>
              <Notice tone="info" title="任务已提交">
                提交不代表处理完成。批量操作应提供任务进度与结果。
              </Notice>
              <Notice tone="success" title="核对完成">
                已核对 24 笔记录,无差异。
              </Notice>
            </div>
          </SectionCard>
        </div>
        <SectionCard title="页面状态" description="空数据、错误和权限需要不同的解释与出口。">
          <Tabs value={state} onValueChange={setState}>
            <TabsList className="max-w-full gap-4">
              <TabsTrigger value="empty">无结果</TabsTrigger>
              <TabsTrigger value="error">错误</TabsTrigger>
              <TabsTrigger value="permission">权限</TabsTrigger>
              <TabsTrigger value="loading">加载</TabsTrigger>
            </TabsList>
            <TabsContent value="empty">
              <EmptyState
                icon={<Search className="h-5 w-5" />}
                title="没有符合条件的订单"
                description="调整搜索关键词或清空筛选,重新查看订单。"
                action={
                  <Button variant="outline" asChild>
                    <a href="#data">调整筛选</a>
                  </Button>
                }
              />
            </TabsContent>
            <TabsContent value="error">
              <EmptyState
                icon={<X className="text-destructive h-5 w-5" />}
                title="订单暂时无法加载"
                description="已保留筛选条件。重试不会重复提交订单。"
                action={
                  <Button variant="outline" onClick={() => setState('empty')}>
                    重试示例
                  </Button>
                }
              />
            </TabsContent>
            <TabsContent value="permission">
              <EmptyState
                icon={<LockKeyhole className="h-5 w-5" />}
                title="当前角色无法查看财务数据"
                description="请联系组织管理员开通对应店铺的财务查看权限。"
              />
            </TabsContent>
            <TabsContent value="loading">
              <div role="status" aria-label="正在加载订单" className="space-y-4 py-10">
                <p className="text-copy-13 text-muted-foreground">正在加载订单…</p>
                {[1, 2, 3].map((n) => (
                  <div key={n} className="bg-muted h-7 rounded" />
                ))}
              </div>
            </TabsContent>
          </Tabs>
        </SectionCard>
      </section>
      <footer className="border-border text-copy-12 text-muted-foreground flex flex-wrap justify-between gap-3 border-t pt-5">
        <span>Starcon Commerce UI · 2.0</span>
        <span>键盘可操作 · 语义化颜色 · 响应式布局</span>
      </footer>

      <Modal open={open} onOpenChange={setOpen}>
        <ModalContent>
          <ModalTitle>新建订单视图</ModalTitle>
          <ModalDescription>为常用筛选起一个易于识别的名称。此处为本地交互示例。</ModalDescription>
          <ModalCloseButton />
          <form
            noValidate
            onSubmit={(event) => {
              event.preventDefault();
              if (!name.trim()) {
                setError('请输入视图名称。');
                document.getElementById('view-name')?.focus();
                return;
              }
              setSaved(name.trim());
              setName('');
              setError('');
              setOpen(false);
            }}
          >
            <FormField
              id="view-name"
              label="视图名称"
              required
              error={error || undefined}
              hint="例如:Amazon 待发货。最多 40 个字符。"
            >
              <Input
                autoFocus
                maxLength={40}
                value={name}
                onChange={(event) => {
                  setName(event.target.value);
                  if (error) setError('');
                }}
                placeholder="输入视图名称"
              />
            </FormField>
            <ModalFooter className="mt-6">
              <ModalClose asChild>
                <Button variant="outline">取消</Button>
              </ModalClose>
              <Button type="submit">
                <Check className="h-4 w-4" />
                创建视图
              </Button>
            </ModalFooter>
          </form>
        </ModalContent>
      </Modal>
    </>
  );
}
function SectionHeading({
  number,
  title,
  description,
}: {
  number: string;
  title: string;
  description: string;
}) {
  return (
    <div className="space-y-1.5">
      <div className="text-label-12 text-muted-foreground">{number}</div>
      <h2 className="text-h-20 font-semibold tracking-tight">{title}</h2>
      <p className="text-copy-13 text-muted-foreground">{description}</p>
    </div>
  );
}

function TypeSample({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-baseline gap-4">
      <span className="text-label-12 text-muted-foreground w-32 shrink-0">{label}</span>
      <span>{children}</span>
    </div>
  );
}