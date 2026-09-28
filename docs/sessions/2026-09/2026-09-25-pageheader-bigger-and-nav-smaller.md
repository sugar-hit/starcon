# 2026-09-25 — 标题栏放大 + 菜单缩小 + twMerge 坑

## 用户反馈

> 你到底能不能找对了？现在红色圈出的是标题栏！他的问题是字体太小，然后颜色不对，且左右间距有问题，莫名其妙的居中了，左侧有大量的空白不和任何元素对齐，这很恶心很难看；其次我绿色圈出来的叫系统切换和功能切换的，这些菜单，字体多大？间距多小？看起来多恶心啊。左侧菜单字体调小一点，间距流出来，让视觉更和谐。上面的字体调小一点就好了

## 我之前误判的方向

上一轮我把所有字号都缩到 13px (PageHeader / Segmented / Nav)。但用户要的是：
- 标题栏：**字号太小，要放大**（不是缩）
- Segmented：**字号调小**
- Nav：**字号调小，间距流出来**

三个位置的字号方向不同，不是"全部缩小"。

## 修复

### 1. PageHeader (红框 — 标题栏)

| 项 | 之前 | 现在 |
|---|---|---|
| h1 字号 | text-copy-13 (13px) font-semibold | **text-h-16 (16px) font-semibold** |
| h1 颜色 | text-foreground (#171717) | **text-primary (#0A0A0A)** ← 更黑更醒目 |
| 布局 | `mx-auto flex max-w-7xl px-5` | **`mx-auto flex w-full max-w-7xl px-5`** ← 加 w-full 避免任何视窗下偏移 |
| description | text-copy-13 | text-copy-12 |

测量验证（Playwright @ viewport 1440）：
- h1: fontSize=16px, fontWeight=600, color=rgb(10,10,10), x=260
- 跟主区内容卡片起始 x=260 完全对齐 ✓

### 2. 顶部 Segmented (绿框 — 系统切换)

- md label: label-13 (13px) → **label-12 (12px)**
- 高度 / padding 不变（用户说"字体调小一点就好了"）

### 3. 左侧 Nav (绿框 — 功能切换)

| 项 | 之前 | 现在 |
|---|---|---|
| NavItem 字号 | copy-13 (13px) | **copy-12 (12px)** |
| NavList padding | py-2 | **py-3** |
| NavList gap-3 → gap-4 | gap-3 (12px) | **gap-4 (16px)** |
| ul space-y | space-y-0.5 (2px) | **space-y-1 (4px)** ← 间距流出来 |
| 分组标题 padding | pt-0.5 pb-1 | **pt-1 pb-1.5** |
| 分隔线 | mx-1.5 | **mx-2** |

## 4. twMerge 坑 — 关键修复

新发现：当我把 NavItem 改成 `text-copy-12` 后，渲染出来还是 16px。
原因 — agent memory 里有记录但我又踩了：

`twMerge` 默认把所有 `text-*` 当作颜色类同组冲突。
NavItem className: `... text-copy-12 ... text-muted-foreground ...`
twMerge: text-copy-12 (font-size) vs text-muted-foreground (color) → 同组冲突，后者覆盖前者 → text-copy-12 被移除 → 默认 16px

修复 (`packages/ui/src/lib/utils.ts`)：
- `extendTailwindMerge` 把所有自定义 fontSize key 显式注册到 `font-size` group
- twMerge 识别它们是字号不是颜色，不再冲突覆盖
- 覆盖范围：14 个 h-* + 7 个 copy-* + 3 个 label-* + 1 个 btn-* = 25 个

顺带：preset.cjs 之前没有 `text-copy-12` token，新增：
```
'copy-12': ['12px', { lineHeight: '1.55' }],
```

## 提交

```
b7ec388 refactor(ui): AppShell — NavList 字号 copy-13→copy-12, 间距 0.5→1
e11d2b8 fix(ui): Segmented — md label label-13 → label-12 (12px)
9bbdff3 fix(ui): PageHeader — h1 16px text-primary, layout w-full
b0f72b5 fix(ui): cn — extend twMerge config to recognize custom fontSize keys
9788f22 feat(ui): preset — add text-copy-12 fontSize token (12px / 1.55)
```

## 视觉验证

`.tmp-screenshots/v7-01-products.png` — 商品中心：
- PageHeader "商品中心" 16px / 纯黑 / x=260 (跟主区卡片完全对齐)
- 顶部 Segmented "开发/运营/物流/仓储" 12px
- 左侧 Nav "工作台/AI Copilot/商品中心/..." 12px + 间距流出来

## 待办

- [ ] 第一个真实业务页（开发系统 / 商品中心）
- [ ] visual-spec 同步 V1.3 → V1.4