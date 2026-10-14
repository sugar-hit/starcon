# 2026-09-25 — Logo/Header 一体对齐 + 字号再平衡

## 用户反馈

> 左侧顶侧菜单的字体，还是太小。标题太大，然后还靠中间。很突兀。Logo下面的横线和右侧顶部导航栏没对齐。应该是一体的，必须要对齐。

3 个问题：

1. 左侧 Nav 字体太小（之前 12px 还是不够）
2. PageHeader 标题太大（20px）且靠中间（mx-auto 偏移）
3. Logo 下分隔线跟 header 下边框没对齐（应该是一体）

## 修复

### 1. PageHeader — 删 mx-auto + 字号降到 18px

```diff
- <div className="mx-auto flex h-12 w-full max-w-7xl items-center gap-4 px-5">
+ <div className="flex h-12 w-full items-center gap-4 px-5">
    ...
-   <h1 className="truncate text-h-20 font-semibold text-primary tracking-tight">
+   <h1 className="truncate text-[18px] font-semibold text-primary tracking-tight">
```

**关键修复** — 删 `mx-auto` 后:

- 内容永远从主区左边沿 x=240 + px-5(20) = x=260 开始
- 不再被 max-w-7xl (1280px) 居中偏移
- 任何视窗宽度下都左对齐

字号: 20px → **18px** (text-[18px]) — 介于 16px (太小) 和 20px (太大) 之间

### 2. Logo / Header 高度统一 h-16

```diff
- <div className="flex h-12 shrink-0 items-center border-b border-sidebar-border px-5">
+ <div className="flex h-16 shrink-0 items-center border-b border-sidebar-border px-5">
```

**对齐** — 两个顶部容器都 h-16 (64px):

- Logo div bottom edge: y=64
- Header bottom edge: y=64
- 两条 border-b 在同一条线上 → "一体" ✓

### 3. Nav 字号 12 → 13

```diff
- 'group flex h-7 items-center gap-2 rounded-md px-2 text-copy-12 ...'
+ 'group flex h-7 items-center gap-2 rounded-md px-2 text-copy-13 ...'
```

字号 12px → **13px** — 比之前略大，可读性更好，但仍小于 PageHeader 18px 保持层级

## 验证（Playwright）

```
Logo div bottom edge:        64
Header bottom edge:          64  ← 完美对齐
h1:    fontSize=18px fontWeight=600 x=260
Nav:   fontSize=13px
```

## 提交

```
659a5df fix(ui): AppShell — Logo 区 h-12 → h-16 跟 header 对齐, Nav 12→13px
74a8d55 fix(ui): PageHeader — drop mx-auto, h1 20→18px font-semibold
```

## 当前 typography 完整图

| 元素                         | 字号     | token                         |
| ---------------------------- | -------- | ----------------------------- |
| StatCard value (KPI hero)    | 24px     | h-24                          |
| **PageHeader h1 (页面标题)** | **18px** | **text-[18px] font-semibold** |
| SectionCard title            | 16px     | h-16 font-semibold            |
| CardTitle (banner)           | 16px     | h-16 font-semibold            |
| CardTitle (typical)          | 14px     | h-14 font-medium              |
| CardDescription              | 13px     | copy-13                       |
| Body text                    | 14px     | copy-14                       |
| Segmented label              | 12px     | label-12                      |
| **Nav item**                 | **13px** | **copy-13**                   |
| Eyebrow / footer             | 12px     | label-12                      |

5 档清晰层级：24 / 18 / 16 / 14 / 12-13

## 待办

- [ ] 第一个真实业务页（开发系统 / 商品中心）
- [ ] visual-spec V1.4 同步新 typography
