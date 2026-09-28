# 2026-09-25 — 4 系统命名 + 密度收紧

## 用户反馈

> 菜单，还有上面的banner，我都不满意。还是有点大。然后左侧的菜单，Logo 下面永远要有线隔开。最后上面系统的名字调整为：开发 运营 物流 仓储

4 件事：① 缩小 PageHeader ② 缩小左侧 Nav ③ Logo 下面永远要有线 ④ 系统名改 开发/运营/物流/仓储

## 修复

### 1. PageHeader 进一步缩小

之前 V1.2: h-12 (48px) + h-16 font-semibold (16px/600)
现在 V1.3: **h-10 (40px) + h-14 font-semibold (14px/600)**

跟周围 13px nav 文字层级连续，不突兀。

### 2. NavList item 缩小

| 元素 | V1.2 | V1.3 |
|---|---|---|
| item 高度 | h-8 (32px) | **h-7 (28px)** |
| 字号 | copy-14 (14px) | **copy-13 (13px)** |
| icon 大小 | h-4 (16px) | **h-3.5 (14px)** |
| padding | px-2.5 | **px-2** |
| gap | gap-2.5 | **gap-2** |
| 容器 padding | px-3 py-2 gap-4 | **px-2 py-2 gap-3** |

### 3. Logo 下面永远分隔线

Logo 容器加 `border-b border-sidebar-border`，永远跟菜单分隔。
容器高度 h-14 → h-12 配合整体收紧。

### 4. 4 系统命名 — 按团队职能分组

之前 V1.2: 商品供应链 / 交易履约 / 经营管理 (3 系统，按业务域)
现在 V1.3: **开发 / 运营 / 物流 / 仓储** (4 系统，按团队职能)

Domain 重分组（总数仍 15）:
```
开发 (5): 商品 / 刊登 / 定价 / 采购 / 供应
运营 (7): 订单 / 售后 / CRM 客服 / 数据 / 财务 / 利润 / 组织权限
物流 (1): 物流中心
仓储 (2): 仓储中心 / 库存中心
```

SystemId enum: `'product'|'trade'|'manage'` → `'dev'|'ops'|'logistics'|'storage'`

## 验证（Playwright 5 路由）

| 路由 | 顶部 tab | 左侧 Nav | h1 |
|---|---|---|---|
| `/` | 开发* | 工作台, AI Copilot, 商品中心, 刊登中心, 定价中心, 采购中心, 供应中心 | 工作台 |
| `/products` | 开发* | (开发分组) 商品中心高亮 | 商品中心 |
| `/orders` | 运营* | 订单中心高亮 + 7 个 domain | 订单中心 |
| `/logistics` | 物流* | 物流中心 (单 item) | 物流中心 |
| `/warehouse` | 仓储* | 仓储中心 + 库存中心 | 仓储中心 |

视觉：Logo 下分隔线 / PageHeader 紧凑 / Nav 紧凑 / 系统名新

## 提交

```
9e1c438 docs(design): V1.3 — 4 systems renamed, PageHeader h-14, NavList h-7
73545cf fix(ui): PageHeader — height h-12 → h-10, h1 h-16 → h-14 font-semibold
e80509e refactor(ui): AppShell — Logo border-b divider, NavList item h-7 copy-13
946b8e4 refactor(ui): nav-config — 4 systems renamed to 开发/运营/物流/仓储, regroup 15 domains
```

## 待办

- [ ] Logo v3 大图（之前没做）
- [ ] 第一个真实业务页：开发 系统 / 商品中心（StatCard + 商品表）
- [ ] 考虑: 字号能不能再缩一档？比如 PageHeader h-14 → h-13（自定义）？用户没明确说，先观察