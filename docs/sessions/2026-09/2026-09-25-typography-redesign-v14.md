# 2026-09-25 — Typography V1.4 重设计

## 用户反馈

> 这里太小了。可以调研一下目前的设计架构，重新设计一下所有元素的字体大小吗？感觉很不和谐。

用户反馈 PageHeader 标题（"商品中心"）太小，且整体字号体系不和谐。

## 现状审计

_*packages/ui/src 中 text-* 引用统计_*:

| 字号                 | 使用次数                                         |
| -------------------- | ------------------------------------------------ |
| text-label-12 (12px) | 8 (Card eyebrow, sidebar-muted, Segmented)       |
| text-copy-13 (13px)  | 7 (page description, EmptyState, StatCard label) |
| text-h-16 (16px)     | 5 (PageHeader h1, CardTitle)                     |
| text-copy-14 (14px)  | 4 (placeholder)                                  |
| text-label-13 (13px) | 3 (delta badge)                                  |
| text-btn-14 (14px)   | 3 (Button text)                                  |
| text-label-14 (14px) | 2 (Form label)                                   |
| text-h-20 (20px)     | 2 (SectionCard title)                            |
| text-copy-12 (12px)  | 2 (Nav item)                                     |
| text-h-32 (32px)     | 1 (StatCard value — 太大)                        |
| text-h-14 (14px)     | 1 (Segmented sm)                                 |

## 诊断

1. **PageHeader 跟 Segmented 字号差距**: 16px vs 12px → 仅差 4px
2. **PageHeader 跟 CardTitle 同档**: 都是 h-16 → 页面级 vs 卡片级 没视觉差
3. **SectionCard 在不同页面用不同字号**: h-24 / h-20 混用
4. **StatCard value (h-32) 跟 h1 (h-16) 差距太大**: 32 vs 16 = 100%，不和谐
5. **12/13/14/16 密度过窄**: 相邻档位只差 1-2px, 难区分

## 重设计 — Typography V1.4

**层级（每档差 25%+）**:

| 层级       | 字号            | 用途                    | 之前           |
| ---------- | --------------- | ----------------------- | -------------- |
| KPI hero   | **24px (h-24)** | StatCard value          | 32px (h-32)    |
| **页面级** | **20px (h-20)** | **PageHeader h1**       | 16px (h-16)    |
| **区块级** | **16px (h-16)** | **SectionCard title**   | h-24/h-20 (混) |
| **卡片级** | **14px (h-14)** | **CardTitle**           | 16px (h-16)    |
| 正文       | 13-14px         | body, description       | 不变           |
| caption    | 12px            | nav, segmented, eyebrow | 不变           |

5 个层级，每档清晰区分：

- 24 (KPI hero)
- 20 (PageHeader)
- 16 (SectionCard / Banner CardTitle)
- 14 (CardTitle)
- 12 (nav / seg / eyebrow)

## 改动

| 文件                                                    | 之前               | 现在                                         |
| ------------------------------------------------------- | ------------------ | -------------------------------------------- |
| `packages/ui/src/components/common/page-header.tsx`     | h-16 font-semibold | **h-20 font-semibold** + description copy-13 |
| `packages/ui/src/components/ui/card.tsx` CardTitle      | h-16               | **h-14 font-medium**                         |
| `packages/ui/src/components/common/stat-card.tsx` value | h-32 (32px)        | **h-24 (24px)**                              |
| `apps/web/src/app/page.tsx` banner                      | h-20               | **h-16 font-semibold** (跟 SectionCard 一致) |

## 视觉验证 (Playwright @ 1440x900)

```
PageHeader h1:    fontSize=20px fontWeight=600
Nav item:         fontSize=12px
Segmented:        fontSize=12px
StatCard value:   fontSize=24px
```

工作台截图 `.tmp-screenshots/v8-03-home.png`:

- PageHeader "工作台" 20px / 600 / 黑色 — 明显页面标题
- StatCard 数字 "12,847" 等 24px / 600 — KPI hero (但不再靠屏)
- SectionCard "订单趋势 / 平台分布" 16px / 600 — 区块级
- Card eyebrow "设计系统已就绪" 12px / uppercase / brand色 — 小标签
- Nav "工作台 / 商品中心" 12px — 菜单

## 提交

```
8c1afe6 fix(web): home demo — 视觉规范 v1.0 banner CardTitle 跟新字号体系一致
31e3a15 fix(ui): StatCard — value 32→24px (KPI 数字不再靠屏)
c5116f2 fix(ui): CardTitle — 16→14px font-medium (跟 SectionCard 16px 区分)
4c1d2b3 fix(ui): PageHeader — h1 16→20px font-semibold (页面标题该有的样子)
```

## 待办

- [ ] 更新 visual-spec V1.3 → V1.4 反映新 typography
- [ ] styleguide 页面 typography demo 需要重排（之前是 h-24 演示"区块标题"）
- [ ] 第一个真实业务页（开发系统 / 商品中心）— 用新字号体系
