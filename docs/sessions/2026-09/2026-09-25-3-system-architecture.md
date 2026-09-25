# 2026-09-25 — 3 系统 × 15 Domain 架构 + UI 重排版

## 用户输入

用户提供 AI ERP 总功能地图:
```
AI ERP
└─ AI Business OS (Agent / Copilot)
   ├─ 商品供应链
   │  ├─ 01 商品中心
   │  ├─ 02 刊登中心
   │  ├─ 03 定价中心
   │  ├─ 04 采购中心
   │  └─ 05 供应中心
   ├─ 交易履约
   │  ├─ 06 订单中心
   │  ├─ 07 库存中心
   │  ├─ 08 仓储中心
   │  ├─ 09 物流中心
   │  └─ 10 售后中心
   └─ 经营管理
      ├─ 11 财务中心
      ├─ 12 利润中心
      ├─ 13 数据中心
      ├─ 14 CRM / 客服
      └─ 15 组织 / 权限
   + 16 AI Intelligence Layer
```

用户在浏览器截图里**圈出红色区域**作为新标题栏位置,说明:
- 功能 / 子功能 → 左侧菜单
- 系统 → 顶部 header (替换原 page title)
- 红框区域 → 新 PageHeader,放当前页 title

## 实现

### 1. nav-config.ts — 单一真相源

`packages/ui/src/lib/nav-config.ts` 定义:
- `SystemDef` / `NavItem` 类型
- 4 个 system (`overview` / `product` / `trade` / `manage`)
- 15 个 domain + 2 个 overview item (工作台 / AI Copilot)
- `resolveSystemFromPath(pathname)` — 由 URL 推断当前 system
- `resolveFeatureFromPath(pathname, system)` — 推断当前 feature

每个 domain 带 `description` (tooltip / 占位文案复用)。

### 2. AppShell v2 — header 改为系统切换器

`packages/ui/src/components/layout/app-shell.tsx`:
- header 高度 `h-14` → `h-16` 给 Segmented 视觉舒展
- 移走 page title (放到 PageHeader)
- 左/中放 `<Segmented>` 4 个 system tab,选中态 `bg-background shadow-sm`
- 切换 → `router.push(target.defaultHref)`
- 左侧 aside 顶部加 "当前系统" 标识 (12px label + 13px 系统名)
- NavList 只渲染当前 system.items
- 移动端抽屉内也含 Segmented

### 3. PageHeader 组件 — 新标题栏

`packages/ui/src/components/common/page-header.tsx`:
- sticky `top-16` 紧贴顶栏下方
- h-14 + `bg-background/95 backdrop-blur`
- **只显示当前页 title,绝对不带系统前缀** (用户硬规则 #1)
- 右侧 actions 槽

反面例子:
- ❌ "交易履约 / 订单中心"
- ❌ "订单中心 / 订单列表"
- ✅ "订单列表"

### 4. catch-all 兜底路由

`apps/web/src/app/[...slug]/page.tsx`:
- 14 个还没实现具体页的 domain 统一渲染 EmptyState
- title = `feature.label`,description = `feature.description + "当前正在搭建,先用 styleguide 看设计令牌"`
- 不覆盖 `/` `/styleguide` `/api/*`

### 5. visual-spec V1.1

`docs/design/visual-spec.md` §2 整体重写:
- 顶层布局图更新为 3 段式 (Sidebar / Header-with-Segmented / PageHeader + Main)
- §2.1 侧栏加 "当前系统" 标识 + 完整 3 系统 × 15 Domain 树
- §2.2 顶栏改为系统切换器配置表
- §2.3 新 PageHeader 配置表 + 反面例子
- §2.4 移动端加 Segmented
- §2.5 z-index 层级 (Header z-30 / PageHeader z-20 / Sidebar z-10)
- 文档版本 V1.0 → V1.1

## 验证 (Playwright)

| 路由 | Top Segmented 高亮 | Left Nav | h1 |
|---|---|---|---|
| `/` | 全部 | 工作台, AI Copilot | 工作台 |
| `/products` | 商品供应链 | 商品中心, 刊登中心, 定价中心, 采购中心, 供应中心 | 商品中心 |
| `/orders` | 交易履约 | 订单中心, 库存中心, 仓储中心, 物流中心, 售后中心 | 订单中心 |
| `/finance` | 经营管理 | 财务中心, 利润中心, 数据中心, CRM / 客服, 组织 / 权限 | 财务中心 |
| `/styleguide` | 全部 | 工作台, AI Copilot | 设计系统总览 |

切换交互: 在 `/styleguide` 点 "商品供应链" tab → URL 跳到 `/products` ✓
所有 14 个 domain 路由 200 (catch-all 接管)

## 提交

```
d89e421 docs(design): V1.1 — 3-system × 15-domain architecture, header becomes system switcher
486bc26 feat(web): catch-all [...slug] page — 14 placeholder domains route to 'coming soon'
382554a refactor(ui): AppShell v2 — header becomes system switcher, nav filters by current system
8bec492 feat(ui): PageHeader — new title bar component (sticky top-16, no system prefix)
3e12aaf feat(ui): nav-config — single source of truth for 3 systems × 15 domains
```

## 待办

- [ ] 第一个真实业务页（建议从**交易履约 / 订单中心**开始，StatCard + 订单表，演示全套组件）
- [ ] Logo v3 大图截图给用户看（mark + wordmark 组合）— 上轮没做
- [ ] AI Copilot 子页面（overview 系统的第二个 item，暂空）