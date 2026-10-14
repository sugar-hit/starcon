# 2026-09-25 — Nav 略大 / H1 略小

## 用户反馈

> 菜单字体，略小。稍微调大一点。H1字体略大，调小一个号码

## 改动

| 元素          | 之前                             | 现在                               |
| ------------- | -------------------------------- | ---------------------------------- |
| Nav item      | text-copy-13 (13px)              | **text-copy-14 (14px)**            |
| PageHeader h1 | text-[18px] (18px) font-semibold | **text-h-16 (16px) font-semibold** |

## 当前 typography 完整图

| 元素                      | 字号                            |
| ------------------------- | ------------------------------- |
| StatCard value (KPI hero) | 24px                            |
| **PageHeader h1**         | **16px** ← 比 h-14 CardTitle 大 |
| SectionCard title         | 16px                            |
| CardTitle (banner hero)   | 16px                            |
| CardTitle (typical)       | 14px                            |
| **Nav item**              | **14px** ← 跟正文一致           |
| Body / Description        | 13-14px                         |
| Eyebrow / Segmented       | 12px                            |

## 提交

```
dec664b fix(ui): AppShell — Nav item 13→14px
1b6125e fix(ui): PageHeader — h1 18→16px font-semibold
```
