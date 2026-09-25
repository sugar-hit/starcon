# 视觉规范手册 — starcon 跨境电商 ERP

> **项目名称**:starcon
> **文档版本**:V1.4
> **最新修订**:2026-09-25 — Typography V1.4 重平衡:StatCard value → h-24 (不再靠屏);PageHeader h1 18→16px font-semibold;Nav item 13→14px 跟正文对齐;Segmented md 12→13px;Logo / Header 统一 h-16 border-b 在 y=64 对齐
> **最新修订**:2026-09-25
> **修订人**:Mavis(基于"能耗诊断管理系统"V1.0 视觉规范迁移 + 跨境电商 ERP 场景适配)
> **适用读者**:前端开发 / UI 调整 / Agent 生成新页面

本文档是 **starcon 的项目级** 视觉规范,所有规则都已经过在"能耗诊断管理系统"(PIONEERING · 湃宁节能管理)中验证落地,现迁移至跨境电商 ERP 场景。

迁移过程中做了以下适配:
- 去掉 `--energy` 能源绿(ERP 没有"节能"语义)
- StatCard 趋势映射支持**双向**(正常 + 反向,适配营收/退款等不同语境)
- 侧栏导航结构按 ERP 领域重组
- 图表配色支持**多指标**(订单/库存/营收/退款)场景
- 所有用户硬规则**完整保留**(被打脸过的纪律不能丢)

---

## 0. 一句话定位

> **企业级 SaaS 调性 = Vercel/Geist 静态骨架 + 黑白灰主色 + 蓝/绿强调,克制一致,极少装饰**

不抄"工业控制感"的密集仪表盘、不堆图表、不上渐变、不用紫色。所有新页面和新组件都必须沿用现有的设计令牌,而不是另起一套。

---

## 1. 设计令牌(Token)— 单一真相源

所有令牌都以 CSS 变量 + Tailwind 扩展形式集中定义。新页面**只**通过类名/变量引用,**禁止**写死色值或字号。

### 1.1 色彩 (Color Tokens)

| Token | HSL | 实际色 | 用途 | 反例 |
|---|---|---|---|---|
| `--background` | `0 0% 98%` | `#FAFAFA` | 页面主背景(gray-0) | 不要用纯白 #FFFFFF 当主背景,会"飘" |
| `--foreground` | `0 0% 9%` | `#171717` | 主文字(Vercel color 9) | 不要用纯黑 #000,硬 |
| `--card` | `0 0% 100%` | `#FFFFFF` | 卡片底(纯白) | 不要给卡片加渐变/纹理 |
| `--primary` | `0 0% 4%` | `#0A0A0A` | 主 CTA、品牌主识别(近黑) | 不要用品牌蓝做主按钮 |
| `--primary-foreground` | `0 0% 100%` | `#FFFFFF` | 主按钮上的文字 | — |
| `--primary-soft` | `0 0% 96%` | `#F5F5F5` | 弱强调背景(块、chip) | 不要拿来做主 CTA |
| `--muted` | `0 0% 96%` | `#F5F5F5` | 次级背景、hover 浅底 | — |
| `--muted-foreground` | `0 0% 44%` | `#707070` | 次级文字、label | 不要用来表达主信息 |
| `--border` | `0 0% 0% / 0.08` | `rgba(0,0,0,0.08)` | 默认边框(gray-alpha) | 不要用纯灰 #E5E7EB,会"硬" |
| `--border-strong` | `0 0% 0% / 0.15` | — | hover 边框、状态强调 | — |
| `--ring` | `0 0% 4%` | `#0A0A0A` | focus 焦点环 | — |
| `--destructive` | `0 72% 51%` | rose-600 | 危险/错误 / 退款 / 投诉 | — |
| `--success` | `152 56% 38%` | emerald-700 | 成功 / 完成 / 营收上涨 | — |
| `--warning` | `32 95% 44%` | amber-600 | 警告 / 待处理 / 库存预警 | — |
| `--brand` | `217 91% 50%` | `#1D4ED8` | 品牌强调(链接、激活状态) | 不要做主 CTA |
| `--brand-soft` | `217 91% 96%` | `#EFF6FF` | 强调背景软底(选中、激活) | — |
| ~~`--energy`~~ | — | — | **(已移除,不适配 ERP)** | — |

> **品牌色替换提示**:`--brand` 当前用蓝 (`#1D4ED8`),源自 PIONEERING 项目。如 starcon 后续有独立品牌色(例如紫蓝 `#5E6AD2 → #A78BFA`),**只改 HSL 值,不改 token 名**,所有引用方无需改代码。

**Tailwind 灰阶**(模拟 Vercel 0-9 scale,只有这 10 档):
```
gray-0 #FAFAFA   gray-5 #707070
gray-1 #F5F5F5   gray-6 #484848
gray-2 #EBEBEB   gray-7 #222222
gray-3 #D1D1D1   gray-8 #0A0A0A
gray-4 #A3A3A3   gray-9 #000000
```
新组件需要的浅灰**优先用 `gray-N`**,不要随意挑别的色。

**反面清单**:
- ❌ 紫(`#7C3AED`、`purple-600`)— 项目调性里没有
- ❌ 暖红 / 金(`#F59E0B` 做主色)— 标识性太跳
- ❌ 渐变(除 AI 浮动按钮的 `from-primary to-primary/80` 唯一例外)
- ❌ 大面积品牌蓝 — 品牌色**只作强调**,不让它主导布局

### 1.2 字体 (Font Family)

```
Sans: GeistSans(占位) → Inter(实际,Google Fonts CDN) → PingFang SC / Microsoft YaHei
Mono: GeistMono → JetBrains Mono → SFMono-Regular
```

**特征**:`font-feature-settings: 'tnum' 1`(等宽数字),所有数值场景(KPI 数字、订单金额、库存数)都要带 `num` 类。

### 1.3 字号 (14 档严格阶梯)

Tailwind 通过 `fontSize` 扩展注入,类名直接用:

| 类名 | px | weight | 用途 |
|---|---:|---:|---|
| `text-h-72` | 72 | 500 | 巨型数字/超显眼的 hero 数字 |
| `text-h-56` | 56 | 500 | — |
| `text-h-48` | 48 | 500 | — |
| `text-h-40` | 40 | 500 | 关键 KPI 主数字 |
| `text-h-32` | 32 | 500 | 卡片主数值 / PageHeader 标题 |
| `text-h-24` | 24 | 500 | 区块标题、Modal 标题 |
| `text-h-20` | 20 | 500 | 重要副标题 |
| `text-h-16` | 16 | 500 | 卡片标题、Section 标题、tab trigger |
| `text-h-14` | 14 | 500 | — |
| `text-copy-24` | 24 | 400 | — |
| `text-copy-20` | 20 | 400 | — |
| `text-copy-18` | 18 | 400 | — |
| `text-copy-16` | 16 | 400 | 正文(已极少用) |
| `text-copy-14` | 14 | 400 | **绝大多数正文、helper** |
| `text-copy-13` | 13 | 400 | 卡片副标题、description |
| `text-label-14` | 14 | 500 | — |
| `text-label-13` | 13 | 500 | — |
| `text-label-12` | 12 | 500 | 表格头、Badge、KPI 标签(全大写) |
| `text-btn-14` | 14 | 500 | 按钮文字 |

**硬规则**:
- 不允许用 `text-sm` / `text-base` / `text-lg` 这些 Tailwind 默认档,**只准用**上述 `h-N` / `copy-N` / `label-N` / `btn-N` 类名
- 字距自动:`letter-spacing: -0.02em`(标题)/ `0`(正文)/ `tracking-wider`(全大写 label)

**应用映射 (V1.4)** —— 哪个 token 用在哪儿:

| UI 元素 | token | px | 备注 |
|---|---|---:|---|
| StatCard value (KPI hero) | `text-h-24` | 24 | 不再靠屏,跟 SectionCard 标题区分 |
| PageHeader h1 | `text-h-16` | 16 | font-semibold;页面顶部独立标题栏 |
| SectionCard title | `text-h-16` | 16 | font-semibold |
| CardTitle (banner hero) | `text-h-16` | 16 | 弹窗/横幅里的大标题 |
| CardTitle (typical) | `text-h-14` | 14 | font-medium;常规卡片标题 |
| Nav item | `text-copy-14` | 14 | 跟正文一致,避免密疏差 |
| Button text | `text-btn-14` | 14 | |
| Header 按钮 (Copilot / 通知 / Avatar) | `text-label-13` | 13 | 工具栏次要文本 |
| Body / Description / helper | `text-copy-14` | 14 | 绝大多数正文 |
| StatCard label / Tabs | `text-label-13` | 13 | 元信息密度档 |
| **Segmented md** | `text-label-14` | 14 | 主切换控件;跟 Nav / CardTitle 同档 |
| Eyebrow / Badge / Table header / **Segmented sm** | `text-label-12` | 12 | 全大写、tracking-wider |

### 1.4 圆角、阴影、动效

**圆角**(`borderRadius` 扩展):

```
sm 4px   DEFAULT 6px   md 6px   lg 8px   xl 12px   2xl 16px   3xl 24px   full 9999
```

- 卡片、按钮、Modal:**8px 以下**,绝不上 16+
- Modal 内嵌的图表块、SectionCard:8px
- 头像 / 圆形触发器:`full`

**阴影**(`boxShadow` 扩展):

```
none / xs / sm / soft / elevated / ring / ring-strong / focus
```

- **默认是 1px border + 极弱阴影或无阴影**
- `elevated` 只用于 Modal / 下拉菜单 / 浮动 AI 按钮
- 不要用 Tailwind 默认的 `shadow-md/lg/xl`

**动效**(`transition*` 扩展):

| 时长 | 缓动 | 用途 |
|---|---|---|
| `fast 80ms` | — | 即时反馈(checkbox) |
| `base 150ms` | `out-expo` | hover、focus、状态切换(默认) |
| `slow 250ms` | `out-expo` | 模态进出、抽屉滑动 |
| keyframes: `fade-in / fade-up / scale-in / slide-in-right/left / accordion-down/up` |

---

## 2. 布局结构

> **v1.1 更新 (2026-09-25)** — 引入 3 系统架构。
> 顶部 header 不再显示 page title,改为**系统切换器**;
> 新增 **PageHeader** 作为页面上方独立的标题栏(用户圈红的位置)。

```
┌────────────────────────────────────────────────────────────────────┐
│  Sidebar(240px,≥md)  │  Header(64px,h-16,系统切换器)               │
│  ─────────────       │  ─────────────────                         │
│  Logo                │  [全部][商品供应链][交易履约][经营管理] 搜索 │
│  ─────────────       ├─────────────────────────────────────────────┤
│  当前系统            │  PageHeader(56px,h-14,sticky top-16)        │
│  ─────────────       │  ──────────────────────                     │
│  功能列表            │  [title]                       [actions]   │
│   - 订单中心         ├─────────────────────────────────────────────┤
│   - 库存中心         │                                             │
│   - 仓储中心         │           Main Content                       │
│   - 物流中心         │           (max-w-7xl 居中,p-4~p-8)          │
│   - 售后中心         │                                             │
│  © 2026 starcon      │                                             │
└─────────────────────┴─────────────────────────────────────────────┘
```

### 2.1 侧栏(≥md)

| 项目 | 规格 |
|---|---|
| 宽度 | 240px(≥md),移动端隐藏 |
| 背景 | `--sidebar` 白底 |
| 边框 | 右侧 1px `border-sidebar-border` |
| Logo 区 | 48px 高(`h-12`),底部 1px `border-sidebar-border`(跟菜单永久分隔) |
| 导航项 | 28px(`h-7`),圆角 6px,水平 padding 8px;字号 14px(`copy-14`);icon 14px(`h-3.5`) |
| 未选中态 | `text-sidebar-foreground/70`,hover `bg-sidebar-accent/50` |
| **选中态** | 浅蓝软底 `bg-sidebar-accent` + 主色图标(`sidebar-primary`)+ `font-medium` |
| 选中态图标 | `text-[hsl(var(--sidebar-primary))] opacity-100`,**绝不是黑/灰** — 这是区分"我在哪"的关键视觉锚 |
| 分组标题 | `text-label-12 uppercase tracking-wider text-sidebar-muted` |
| 底栏 | 边框 + `text-label-12 text-sidebar-muted`,`© 2026 starcon` |

**4 系统 × 15 Domain 架构**(用户 2026-09-25 最终确认 — 按团队职能分组):

```
开发 (5)
├─ 商品中心     / SKU 与变型管理
├─ 刊登中心     / 多平台商品刊登
├─ 定价中心     / 价格策略与汇率
├─ 采购中心     / 采购单与供应商
└─ 供应中心     / 供应商与档口管理

运营 (7)
├─ 订单中心     / 多平台订单聚合
├─ 售后中心     / 退款 / 退货 / 客诉
├─ CRM / 客服   / 客户档案与工单
├─ 数据中心     / 数据看板与报表
├─ 财务中心     / 对账与结算
├─ 利润中心     / 毛利与分润
└─ 组织 / 权限  / 员工与角色权限

物流 (1)
└─ 物流中心     / 物流轨迹与配送

仓储 (2)
├─ 仓储中心     / 仓位与库内作业
└─ 库存中心     / 全渠道库存协同
```

顶层固定项 (不属于任何系统):
```
├─ 工作台       /  /  首页 dashboard、待办、关键指标
└─ AI Copilot   /  /copilot  AI 助手与自动化
```

**侧栏过滤规则**:`usePathname()` → `resolveSystemFromPath()` 推断 system → 只渲染该 system 的 items。切换系统 = 跳到该 system 的 `defaultHref`。

### 2.2 顶栏(系统切换器)

| 项目 | 规格 |
|---|---|
| 高度 | 64px(`h-16`) — 比 v1.0 的 56px 多 8px,给 Tab 视觉更舒展 |
| 背景 | `--background` + `bg-background/95 backdrop-blur`(滚动时透出内容) |
| 边框 | 底部 1px `border-border` |
| **左侧 (≥md)** | **不再显示 page title**;显示 **Segmented 系统切换器** |
| 中央 | 全局搜索框(占位 "搜索订单 / 商品 / 客户")+ 右侧 `⌘K` kbd 提示(≥xl 才显示) |
| 右侧 | 通知铃铛 + 头像下拉(用户菜单、租户切换、登出) |
| **左侧 (<md)** | Logo + 移动端 hamburger(右上角,见 §2.4) |

**系统切换器 Segmented 配置**:

```ts
[
  { id: 'dev',       label: '开发' },
  { id: 'ops',       label: '运营' },
  { id: 'logistics', label: '物流' },
  { id: 'storage',   label: '仓储' },
]
```

- 选中态: 浅蓝软底 `bg-background` + `shadow-sm` + `font-medium`
- 切换 → `router.push(target.defaultHref)`
- 由 `usePathname()` → `resolveSystemFromPath()` 自动同步当前高亮

### 2.3 PageHeader(新)

| 项目 | 规格 |
|---|---|
| 位置 | 主区上方,**不 sticky**(普通块元素,占文档流) |
| 高度 | 40px(`h-10`) |
| 背景 | `bg-background`(实心,不透明) |
| 边框 | 底部 1px `border-border` |
| 内边距 | `px-5`(跟侧栏 Logo / Nav padding 对齐,20px) |
| **左侧** | **当前页 title**,**只显示当前页 label,不带系统/分组前缀**(见 §6 用户硬规则 #1)。类名:`text-h-14 font-semibold tracking-tight truncate`(14px / 600) |
| 右侧 | 页面级 actions 槽(`ml-auto`) |
| 副标题 | 可选 `description`(13px 灰) |

**字号选择**:`text-h-14 font-semibold`(14px / 600),用 **weight 而不是 size** 来突出 — 跟周围 13px nav 文字层级连续, 不突兀。

**反面例子**:
- ❌ "交易履约 / 订单中心"(系统前缀)
- ❌ "订单中心 / 订单列表"(父级前缀)
- ✅ "订单列表"(只当前页 label)

### 2.3 主区

- 内边距:页面级 `space-y-6`(卡片间纵向 gap),内部 grid 用 `gap-4` 或 `gap-6`
- 最大宽度:`max-w-2xl` 起、整页响应式 grid
- 没有"中央居中卡片"的画面 — 内容**始终**贴左对齐,纵向滚动

### 2.4 移动端

- <md:**侧栏隐藏**,核心操作放浮动按钮(若需要 AI 助手入口)
- 顶栏:hamburger 在**右上角**(Menu 图标),点击**从右滑出**抽屉
- 抽屉头部:**关闭 X 在左**,Logo 在右(贴近入口图标)
- **抽屉内额外含 Segmented 系统切换器**(置于 Logo 下方),保持系统切换能力
- 这条规则见 §6 用户硬规则 #3

### 2.5 顶栏 / PageHeader / 侧栏 的层级关系

```
z-30  ← Header (sticky top-0)
z-20  ← PageHeader (sticky top-16)
z-10  ← Sidebar (sticky top-0) — 但 aside 自身只在 nav 溢出时滚动
```

主区滚动时,Header + PageHeader 都 sticky 不动;侧栏独立滚动(只在 nav 内容溢出时)。

---

## 3. 组件调性(shadcn + 自定义)

### 3.1 Button

```tsx
// 主 CTA  — 黑底白字
<Button variant="default" size="default">保存</Button>

// 次级  — 1px 边,透明底,hover 浅灰
<Button variant="outline" size="default">取消</Button>

// 低优先  — 浅灰底
<Button variant="secondary" size="default">更多</Button>

// 三级  — 透明 + hover 浅灰
<Button variant="ghost" size="default">返回</Button>

// 品牌强调  — 仅用于"采纳 / 同意"等强语义场景
<Button variant="brand" size="default">采纳建议</Button>

// 危险
<Button variant="destructive" size="default">删除</Button>
```

**反面清单**:
- ❌ 不要写 `className="bg-blue-500 text-white"` 自己拼按钮,**用 variant**
- ❌ 不要让 ghost 按钮带边框 — ghost 是透明,如果需要边就改用 outline
- ❌ 不要在主 CTA 上做 `bg-brand`(品牌蓝) — 主 CTA 永远是黑色

### 3.2 Card

```tsx
<Card className="p-5">                              // 默认 1px + 8px 圆角
  <CardHeader>
    <CardTitle>标题</CardTitle>
    <CardDescription>副标题</CardDescription>
  </CardHeader>
  <CardContent>...</CardContent>
</Card>
```

- 默认背景 `#FFFFFF`、边框 `gray-alpha 8%`、**不加阴影**
- hover(可选):`hover:border-black/15` 加深边框,**不要**叠 transform/shadow
- 圆角 8px,**禁用** `rounded-2xl` / `rounded-3xl` 这类大圆角
- 卡片内部 padding:`p-5`(20px)/ `p-6`(24px),不混用

### 3.3 SectionCard — 页面级分区卡

```tsx
<SectionCard
  title="订单趋势"
  description="近 7 天订单量走势"
  extra={<Segmented value={range} onChange={setRange} options={...} />}
  bodyClassName="px-2 py-3"  // 图表场景需要去掉左右 padding
>
  ...chart
</SectionCard>
```

- 默认头:标题 `text-h-16`、描述 `text-copy-13 text-muted-foreground`
- 头部 `border-b border-border` 分隔,**不要换成阴影**
- extra 槽永远放右上角(查询控件、按钮组)

### 3.4 StatCard — KPI 卡(双向趋势支持)

```
┌──────────────────────────────────┐
│  今日订单                  📦    │   ← icon 在右上,9×9 浅灰底
│  12,847                          │   ← text-h-32 num(等宽数字)
│  ↑ +12.5%  较昨日                │   ← trend chip + label
└──────────────────────────────────┘
```

**趋势方向映射**(starcon ERP 支持双向,不像能耗项目单一反向):

| 场景 | up 含义 | 颜色 |
|---|---|---|
| 营收、订单量、活跃用户(正向指标) | 好 | `text-emerald-600 bg-emerald-50` |
| 退款单、投诉、库存周转天数(负向指标) | 坏 | `text-rose-600 bg-rose-50` |
| 库存预警、待处理(中性指标) | 警示 | `text-amber-600 bg-amber-50` |

**API 设计**(组件层面):
```tsx
<StatCard
  label="今日订单"
  value={12847}
  delta={12.5}            // 数值
  deltaDirection="up"    // up | down | flat
  polarity="positive"    // positive | negative | neutral
  // ↑ polarity="positive" + direction="up" = 绿色
  // ↑ polarity="negative" + direction="up" = 红色
/>
```

### 3.5 Badge

7 种 tone,默认 pill:

| Tone | 背景 | 文字 | 场景 |
|---|---|---|---|
| `neutral` | `bg-gray-2` | `text-foreground/80` | 通用标识 |
| `primary` | `bg-foreground` | `text-background` | 黑色实心 — 最显眼 |
| `success` | `bg-emerald-50` | `text-emerald-700` | 已发货、已完成、已结算 |
| `warning` | `bg-amber-50` | `text-amber-700` | 待审核、库存预警 |
| `danger` | `bg-rose-50` | `text-rose-700` | 退款、投诉、缺货、错误 |
| `info` | `bg-sky-50` | `text-sky-700` | 信息提示 |
| `brand` | `bg-brand-soft` | `text-brand` | 品牌色软底,链接/选中 |

**订单状态映射参考**:
- 已下单 → `info` / 待审核 → `warning` / 已发货 → `success` / 退款中 → `danger` / 已完成 → `neutral`

### 3.6 Segmented — Tab 式切换

```tsx
<Segmented
  value={range}
  onChange={setRange}
  options={[
    { label: '今日', value: 'day' },
    { label: '近 7 天', value: 'week' },
    { label: '本月', value: 'month' },
  ]}
/>
```

- 容器:`bg-muted/60 border border-border p-0.5 rounded-md`
- 选中态:`bg-background font-medium shadow-sm`(白底浮起 + 1px 阴影,有"按下"感)
- 默认尺寸 `sm`(28px 高),重要页面才用 `md`

### 3.7 Tabs

- 整列底部一条 1px 灰线
- 选中 trigger:底部 **2px `border-primary`** + `text-primary font-semibold`
- 未选中:`text-muted-foreground`,hover 才转 `text-foreground`
- 内容区:进入时 `animate-fade-up` 240ms

### 3.8 Modal

- 遮罩 `bg-black/40 backdrop-blur-sm p-4`(浅遮罩,不是黑的)
- 进入动画 `animate-fade-in`(遮罩)+ `animate-fade-up`(本体)
- 标题区 `border-b`,关闭 X 在右上
- esc 关闭 + 锁滚动

### 3.9 EmptyState

```
       ┌──┐
       │📦│   ← 10×10 浅灰圆角底 + muted 图标
       └──┘
       主标题 — 居中,text-copy-14 font-medium
       描述 — text-copy-13 muted
       [action 按钮]
```

**极简**。不要在 EmptyState 上画插画、不要加渐变背景。

### 3.10 Logo

starcon 当前没有正式品牌,Logo 组件先用占位:

```
STARCON              ← font-black tracking-[0.04em] 大写粗体
跨境电商 ERP           ← font-medium tracking-[0.05em] 60% 透明
```

正式 Logo 设计出来后,只替换文案 + 可选 icon,**不**改组件结构。

- tone:
  - `light`(默认):深字,适配白底
  - `dark`:反白,适配深色
  - `brand`:浅蓝底

---

## 4. 图表规范(Recharts)

| 项目 | 值 |
|---|---|
| 主色 | `hsl(220, 70%, 45%)` (深蓝) |
| 第二色 | `hsl(152, 56%, 38%)` (emerald-700,正向指标) |
| 警示色 | `hsl(0, 72%, 51%)` (rose-600,负向指标) |
| 中性灰 | `#cbd5e1` (用于"对比组/历史值") |
| 网格线 | `#e2e8f0` 横虚线 `strokeDasharray="3 3"`,**纵向不画** |
| 坐标文字 | `#94a3b8` 11px(`tick={{ fill: '#94a3b8', fontSize: 11 }}`) |
| 坐标轴 | axisLine 横线淡,纵向去掉 |
| Tooltip | 8px 圆角 + 1px `#e2e8f0` 边 + `shadow-elevated` |
| Area 渐变 | 顶部 `stop-opacity="0.28"` → 底部 `0`,线性向下 |
| Bar 圆角 | `radius={[6, 6, 0, 0]}`(顶部圆角,底部直角) |

**多指标场景的色板分配**(ERP 报表常用):

```
指标 1 (营收、订单)         → 主色 (深蓝)
指标 2 (对比、目标、历史)    → 中性灰 #cbd5e1
指标 3 (退款、流失、投诉)    → 警示色 (rose-600)
指标 4 (增长、上架新品)      → 第二色 (emerald-700)
```

**反面**:
- ❌ 不要上五彩斑斓的 ECharts 默认配色
- ❌ 不要堆 3D、阴影、立体效果
- ❌ 不要每条线都加 legend,只保留真实对比需要的 1–2 条

---

## 5. 交互一致性

### 5.1 Hover 反馈(必须遵守的全局约束)

| 组件类 | hover 行为 |
|---|---|
| 导航菜单项 | 只换底色(`hover:bg-sidebar-accent/50`) |
| 按钮 | variant 默认的色阶微调(已写在 cva) |
| 卡片(可选) | `hover:border-black/15`(变深边框) |
| 表格行 | `hover:bg-black/[0.02]` |
| 卡片 / 整行可点击 | `hover:border-black/15` 或 `bg-black/[0.02]` 二选一 |

**铁律**:**hover 反馈要跟周围组件风格保持一致**。
- 菜单项只换底色 → 卡片也只换底色,**不要再叠 4 件套**(transform / shadow / 边框浮现 / 同色光晕)
- 不要在"普通菜单"hover 只换背景时,自己在另一个组件上叠多层效果
- 暗色模式(若后续引入)hover 可叠 4 件套做强调,但亮色模式默认**禁止**

### 5.2 Focus 环

- input/textarea:`focus:border-foreground/40`,**不加 ring**
- button:`focus-visible:ring-2 ring-black/10 ring-offset-2`,只在键盘聚焦时出现

### 5.3 动效节奏

- 所有切换类(hover / focus / state)用 `transition-colors duration-base ease-out-expo` 150ms
- 抽屉 / 模态:220–250ms `out-expo`
- 严禁 500ms 以上的"提示性动画"

---

## 6. 用户硬规则(任何新页面必须遵守)

> 这些是从 PIONEERING 项目反复被打脸沉淀的"项目宪法",违反任何一条都会被当场打回。
> **starcon 全盘继承**,不要再走一遍弯路。

1. **顶栏只显示当前页面的 title,不要 group 前缀**
   - ❌ `数据 | 订单概览`、`订单 / 异常单`
   - ❌ 自己加面包屑或自动注入分组信息
   - ✅ 只显示单 label:`订单概览` / `库存管理` / `财务对账`

2. **桌面端导航 = 左栏(且只在左栏),右上角不许有 hamburger**
   - ❌ "桌面端左栏 + 右上 hamburger" 是重复设计
   - ✅ 仅移动端(<md)有 hamburger,且位置是**右上角**

3. **移动端 hamburger 在右上,点击从右滑出抽屉,抽屉头部 X 在左 / Logo 在右**
   - ❌ "hamburger 在左上但抽屉从右滑出" — 反人类距离
   - ✅ 头部:X 关闭在左,Logo 在右(贴近入口方向)

4. **Hover 反馈保持一致,不要自作主张叠 4 件套**
   - 跟周围 hover 风格保持一致
   - 不要在某个组件上擅自加 `transform + box-shadow + 边框浮现 + 同色光晕`,菜单如果是只换底色,你也只换底色
   - 亮色模式 hover 默认**只换底色 / 变深边框**,禁止叠多层
   - 暗色模式(如未来引入)hover 可叠 4 件套做强调

5. **页面 UI 文案只展示对当前访客有用的内容,不写"我们接下来要做 X"**
   - ❌ "退款功能即将上线,目前请走线下流程"
   - ❌ "团队套餐创建后默认 Free,升级在系统设置"
   - ✅ 表单错误的描述 — "组织名已被占用,试试更独特的名字"
   - 开发 TODO 走 `docs/backlog/` 或源码 inline,不要写进用户能看到的地方

---

## 7. 目标页面视觉对齐度

starcon 前端尚未实现,以下为**规划中的页面**与本规范的对应,便于新页面做参照。

| 页面 | 路由 | 目标组件 | 备注 |
|---|---|---|---|
| 工作台(总览) | `/` | PageHeader + StatCard × 4 + SectionCard + Segmented | 今日订单、营收、待处理、库存预警 |
| 订单列表 | `/orders` | PageHeader + 顶部 stat + 筛选 + 数据表 + 详情面板 | 多平台来源筛选 |
| 异常订单 | `/orders/exceptions` | PageHeader + StatCard + Tabs(超时/缺货/退款) + 列表 | 强调操作时效 |
| 库存总览 | `/inventory` | PageHeader + 平台切换 + 多仓库表格 | SKU 健康度 + 预警 |
| 商品管理 | `/products` | PageHeader + 左右分栏 + SPU/SKU 嵌套表 | 多平台 Listing 映射 |
| 履约出库 | `/fulfillment` | PageHeader + SectionCard(待拣货/已打包/已发货) | 看板风格 |
| 物流追踪 | `/shipments` | PageHeader + 时间线 + 异常标记 | 轨迹地图可选 |
| 财务对账 | `/finance` | PageHeader + Tabs(应收/应付/结算) + 复杂表 | 汇率、平台手续费展示 |
| 数据报表 | `/analytics` | PageHeader + 时间 Segmented + 多 SectionCard | 多指标看板 |
| 平台对接 | `/settings/integrations` | PageHeader + 卡片网格(各平台) + 连接状态 | 强调"已连接/未连接"状态 |
| 登录 | `/login` | 左右分屏,左侧深色 + Logo,右侧浅色表单 | 唯一允许的"深色版"页面 |
| 通知中心 | `/notifications` | PageHeader + 时间分组 + 列表 | 必读标记 |

**对照要点**:
- 大部分页面 = 浅色白底 + 1px 边框 + 无阴影
- 登录的左侧 = 深色 — **深色版**唯一允许的场景
- 报表/BI 类页面允许 hero 区域稍深的渐变暗色做"沉浸感",**仅此一处**

---

## 8. 新页面 / 新组件的 checklist

在写新页面之前对照一遍,直到它能自然通过为止:

- [ ] 只引用 `text-h-N / text-copy-N / text-label-N / text-btn-N`,没有任何 `text-sm / text-base / text-lg`
- [ ] 颜色全部走 `bg-primary / text-foreground / border-border` 等 token,没有写死的 `#xxx` 或 `text-[#xxx]`
- [ ] 圆角只用 4 / 6 / 8 / 12,没有 `rounded-2xl` 或 `rounded-3xl`
- [ ] 阴影只在 Modal / 下拉菜单 / 浮动按钮使用,普通卡片**只有 1px 边**
- [ ] 数字(KPI、价格)带 `num` 类(等宽数字)
- [ ] 主 CTA 是黑色(`bg-primary`),品牌蓝只作强调
- [ ] 顶栏 title 由 Layout 自动从 navItems 解析,我**不**自己加面包屑/分组前缀
- [ ] 移动端 hamburger 在右上,抽屉头部 X 在左 / Logo 在右
- [ ] hover 反馈只换底色或变深边框,**不**叠 4 件套(亮色模式)
- [ ] 没有写"开发 TODO / 即将上线" 到用户可见文案
- [ ] 图表用 Recharts 的规格(主色 `hsl(220,70%,45%)`、网格 `#e2e8f0`、bar 顶部 6px 圆角),不用 ECharts 默认配色
- [ ] StatCard 用了正确的 `polarity` 属性(营收 vs 退款 不要混)

---

## 9. 单一真相源 — 文件索引(落地后)

| 内容 | 文件路径(规划) |
|---|---|
| CSS 变量 / 字体引入 / 全局 utility 类 | `apps/web/src/styles/tokens.css` |
| Tailwind 扩展(14 档字号、gray 阶、圆角、阴影、动画 keyframes) | `apps/web/tailwind.config.ts` |
| 顶栏 + 侧栏布局 | `packages/ui/src/layout/AppShell.tsx` |
| 全局字体(Inter from Google Fonts) | `apps/web/src/app/layout.tsx` |
| Logo 组件 | `packages/ui/src/logo/Logo.tsx` |
| 通用组件库(Button/Card/Tabs/Input/Modal) | `packages/ui/src/` |
| 业务组件(SectionCard/StatCard/Badge/Segmented/EmptyState) | `packages/ui/src/` |
| cn() 工具 | `packages/ui/src/lib/utils.ts` |

> **当前状态**:starcon 还没有任何前端代码。本规范是**约束前置**——后续 `apps/web` `apps/admin` 落地时,组件库必须按本规范实现,禁止另起一套。

---

## 10. 修订记录

| 版本 | 日期 | 修订说明 |
|---|---|---|
| V1.0 | 2026-09-25 | 初版,从"能耗诊断管理系统"PIONEERING V1.0 视觉规范迁移,适配跨境电商 ERP 场景;移除 `--energy`,StatCard 趋势支持双向,导航按 ERP 领域重组,图表支持多指标 |
| V1.1 | 2026-09-25 | 引入 3 系统 × 15 Domain 架构(AI ERP 总功能地图);顶部 header 改为系统切换器(全部 / 商品供应链 / 交易履约 / 经营管理);新增 PageHeader 独立标题栏;新增 nav-config.ts 单一真相源;catch-all `[...slug]` 兜底路由 |
| V1.2 | 2026-09-25 | 移除"全部"系统 / 移除侧栏"当前系统"标识;PageHeader h-20 → h-16 font-semibold,h-14 → h-12;主区 padding 跟侧栏对齐(px-5)|
| V1.3 | 2026-09-25 | 4 系统重命名为 开发/运营/物流/仓储 (按团队职能);PageHeader h-16→h-14 font-semibold, h-12→h-10;NavList item h-8→h-7, copy-14→copy-13;Logo 下加 border-b 分隔 |
| V1.4 | 2026-09-25 | Typography 重平衡:StatCard value → h-24;PageHeader h1 18→16px font-semibold;Nav item 13→14px;Segmented md 12→13→14px(两轮反馈);Logo / Header 统一 h-16 border-b 在 y=64 对齐 |