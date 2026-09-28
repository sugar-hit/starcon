# 2026-09-25 — 字号，不是间距

## 用户反馈

> 是字体大小，不是间距。

用户纠正我之前的方向 — 我缩了高度（间距）但字号没缩够。

## 修复

只动字号，不动间距（高度）。

| 元素 | v5 (缩高度) | v6 (缩字号) |
|---|---|---|
| PageHeader h1 | h-14 (14px) font-semibold | **copy-13 (13px) font-semibold** |
| PageHeader description | copy-13 | **copy-12 (12px)** |
| PageHeader 高度 | h-10 (40px) | **h-12 (48px)** ← 回退 |
| Segmented (md) label | label-14 (14px) | **label-13 (13px)** |
| Segmented (sm) label | label-13 (13px) | **label-12 (12px)** |

现在三个位置字号统一：
- 顶部 Segmented: **13px** (label-13, font-medium)
- 左侧 Nav item: **13px** (copy-13, font-normal)
- PageHeader h1: **13px** (copy-13, font-semibold)

仅用 weight (400 vs 500 vs 600) 区分层级 — 视觉密度统一，不再"突兀"。

## 视觉对比

v5 vs v6:
- v5 PageHeader h1 14px / 高度 40px（看着还是大）
- v6 PageHeader h1 13px / 高度 48px（字号小 + 间距适度）

## 提交

```
8edabb7 fix(ui): Segmented — label size: label-14 → label-13 (md)
81d6b2e fix(ui): PageHeader — h1 font size: h-14 → copy-13 (13px), keep h-12
```

## 后续观察点

- PageHeader h1 (13px) 现在比 CardTitle (h-24, 24px) 还小很多 — 层级倒挂？
  - 但视觉上 PageHeader 跟 Nav 一致，感觉"自然"
  - 下一轮可以调回 15px 自定义，看用户反馈
- EmptyState title (copy-14, 14px) 也比 PageHeader 大 — 类似倒挂
  - 但 EmptyState 是"页面内容里的标题"，比 PageHeader 大合理

## 待办

- [ ] 第一个真实业务页（开发系统 / 商品中心）
- [ ] 考虑 PageHeader 字号是否需要重新分档（13px / 15px / 16px），目前跟 nav 字号撞了