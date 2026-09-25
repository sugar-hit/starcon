# 2026-09-25 — PageHeader 字号 + 对齐微调

## 用户反馈

> 字体太大，太突兀。全部调整一下。然后标题也不和谐。大小不好，左右没对齐到该对齐的地方。

红框指向：商品中心 PageHeader h1。

## 根因

1. **h1 太大**：PageHeader 用了 `text-h-20 font-medium` (20px / 500)，比周围 14px 文字大 6px，weight 500 也不够突出 → 视觉突兀，跟周围不连续
2. **主区 padding 跟侧栏不对齐**：之前 `p-4 md:p-6 lg:p-8` (16/24/32px)，侧栏 Logo `px-5` (20px) — 主区内容起始位置 (x=32) ≠ 侧栏起始位置 (x=20)

## 修复

### 1. PageHeader h1: h-20 font-medium → h-16 font-semibold
- 字号 20px → 16px
- weight 500 → 600 (semibold)
- **用 weight 而不是 size 来突出层级**，跟周围 14px 文字连续
- 高度 h-14 (56px) → h-12 (48px)，更紧凑

### 2. 主区 padding: p-4 md:p-6 lg:p-8 → px-5 py-6
- 统一 20px 水平内边距
- 主区内容起始位置 x=260 (= 240 侧栏宽度 + 20 padding)
- 跟侧栏 Logo 起始 x=20 视觉对齐（各自容器内 20px 起始）

### 3. PageHeader 内边距同步改 px-5

## 验证

| 元素 | 之前 | 现在 |
|---|---|---|
| PageHeader h1 | 20px / 500 | 16px / 600 |
| PageHeader 高度 | 56px | 48px |
| PageHeader padding | px-4/6/8 | px-5 |
| 主区 padding | p-4/6/8 | px-5 py-6 |
| 侧栏 Logo padding | px-5 | px-5 (不变) |

视觉对比（v3-01-home.png → v4-03-home.png）：
- PageHeader "工作台" 跟 CardTitle "视觉规范 v1.0 已落地" (h-24) 层级清晰
- 主区内容起始 x 跟侧栏 Logo 起始 x 视觉对齐

## 提交

```
d5f597b docs(design): V1.2 — PageHeader h-16 font-semibold, padding align sidebar
8a8d1bf fix(ui): AppShell — main content padding aligns with sidebar (px-5)
52d9c84 fix(ui): PageHeader — reduce h1 size h-20 → h-16 font-semibold, height h-14 → h-12
```

## 待办

- [ ] Logo v3 mark + wordmark 大图（之前没做）
- [ ] 第一个真实业务页：交易履约 / 订单中心
- [ ] 考虑: Segmented label 是否也缩小（label-13/500）？用户没明确说，留待观察