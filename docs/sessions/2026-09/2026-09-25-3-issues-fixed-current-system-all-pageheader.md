# 2026-09-25 — 3 个 UI 问题修复（v3）

## 用户反馈

> 1. 左面的当前系统全部，不要；
> 2. 菜单里的全部，不要；
> 3. 标题页面明显异常了。

3 个红框指向：左侧"当前系统 全部"标识 / 顶部"全部" tab / PageHeader 显示异常。

## 修复

### 1. 左侧"当前系统"标识 — 删掉

之前 AppShell aside 顶部：
```
当前系统
全部        ← 跟顶部 Segmented 重复
─────────
工作台
AI Copilot
```

改为：
```
Logo
─────────
工作台（顶部固定）
AI Copilot（顶部固定）
─────────
商品供应链（分组标题）
- 商品中心
- 刊登中心
- ...
```

`packages/ui/src/components/layout/app-shell.tsx` NavList 重写：永远显示 `TOP_LEVEL_ITEMS` 2 项 + 当前系统的 items。

### 2. 顶部 Segmented"全部" — 删掉

`packages/ui/src/lib/nav-config.ts`：
- `SYSTEMS` 之前 `[overview, product, trade, manage]` 4 个
- 现在 `[product, trade, manage]` 3 个（用户硬规则）
- `overviewSystem` 拆出来变成 `TOP_LEVEL_ITEMS: NavItem[]`（不含"系统"语义）
- `resolveSystemFromPath` fallback 从 overview 改成 product

`packages/ui/src/components/layout/app-shell.tsx`：
- Segmented options 用 `SYSTEMS.map(...)`，自动不含 overview
- 右上角加 AI Copilot 入口（Sparkles icon + "AI Copilot" 文字）作为顶层入口快捷方式

### 3. PageHeader 显示异常 — 不 sticky + 实心 bg

**根因**：
- `<main className="flex-1 overflow-x-hidden">` 让 sticky 元素以 main 为 scroll context，而不是 window
- PageHeader 用 `sticky top-16 + bg-background/95 backdrop-blur`
- 半透明背景 → 内容卡片穿透 PageHeader 看起来"被盖"
- sticky 偏移异常 → PageHeader 在 y=128 而不是 y=64，下方内容卡片顶部被遮

**修复** (`packages/ui/src/components/common/page-header.tsx`)：
- 去掉 `sticky top-16` + `bg-background/95 backdrop-blur`
- 改用 `bg-background` 实心 + `border-b border-border`
- 普通块元素，**占文档流**，自然推开下方内容
- 主区 padding 由调用方控制（不再需要 sticky 占位）

副作用：滚动时 PageHeader 不再跟着滚 — 简单稳定，且不需要 z-index / scroll context 协调。

## 验证（Playwright 4 路由）

| 路由 | Segmented | Nav (顶部 + 当前系统) | h1 |
|---|---|---|---|
| `/` | 商品供应链*, 交易履约, 经营管理 | 工作台, AI Copilot, 商品中心, 刊登中心, 定价中心, 采购中心, 供应中心 | 工作台 |
| `/orders` | 商品供应链, 交易履约*, 经营管理 | 工作台, AI Copilot, 订单中心, 库存中心, 仓储中心, 物流中心, 售后中心 | 订单中心 |
| `/products` | 商品供应链*, 交易履约, 经营管理 | 工作台, AI Copilot, 商品中心, 刊登中心, 定价中心, 采购中心, 供应中心 | 商品中心 |
| `/finance` | 商品供应链, 交易履约, 经营管理* | 工作台, AI Copilot, 财务中心, 利润中心, 数据中心, CRM / 客服, 组织 / 权限 | 财务中心 |

视觉验证（`.tmp-screenshots/v3-01-home.png` 等）：
- ✅ 没有"当前系统"标识
- ✅ 没有"全部" tab
- ✅ PageHeader "工作台" 实心背景，下方"AI ERP 视觉规范 v1.0 已落地"卡片完整显示（之前被遮）

## 提交

```
fb8565a refactor(ui): AppShell v3 — drop '当前系统'标识, add fixed top-level nav items
ed6f459 fix(ui): PageHeader — drop sticky, use solid bg, no longer overlaps content
95cc9e9 refactor(ui): nav-config — drop overview pseudo-system, split TOP_LEVEL_ITEMS
```

## 待办

- [ ] Logo v3 mark + wordmark 大图（之前没做）
- [ ] 第一个真实业务页：交易履约 / 订单中心（StatCard + 平台订单表）
- [ ] 修复 PageHeader 后，主区 padding 顶部可能要重新微调