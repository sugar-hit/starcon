# 2026-09-26 — ERP 用户浏览器行为调研

## 用户输入

> 我了解到他们似乎会在一个浏览器页面下来回切换,对吗?你调研一下是不是真的。

## 结论

**是的,而且情况更复杂 — 多 tab + 同窗口来回切换 同时存在**。

## 证据

### 多 tab / 多窗口是常态

- **简道云 ERP / 用友 U8 / 金蝶 K3 Cloud / Microsoft Dynamics 365 / Awery ERP** 都明确支持多窗口/多 tab
- 财务员"对照采购单与发票审核" — 多 tab 并行
- 仓库员"同步查看库存和出入库记录" — 多 tab 并行
- 销售员"跨查客户与订单执行情况" — 多 tab 并行

### 跨平台多账号必须有浏览器多开

- **拉力猫 / 跨境卫士 / 飞跨浏览器**整个市场围绕"多 Profile 隔离"展开
- 客服同时管 Amazon / eBay / TikTok Shop,3 个店铺后台消息分散
- 多 Profile 才能避免账号关联风控

### 行业从 OPAT 转向多任务架构

- **ProBuilt** 整家公司专利就是反 OPAT(One-Page-At-A-Time),改成"多窗口同时开 + 持久化"
  - "Open invoices, sale orders, an inventory low-stock view, a customer aging report; all visible at once, all live against the same database"
- **Amazon 2026 新版 Seller Central** 把"避免 tab-switching / data re-entry / menu hunting"当卖点
  - 用 6 个 workspaces + universal search 替代传统菜单
- **Microsoft Dynamics 365 Business Central** 明确支持 multiple pages opened simultaneously
  - Alt+Shift+W 弹独立窗口;可在多显示器显示

### 客服来回切店铺是核心痛点

- eBay 卖家社区:"卖家 3 个店铺,后台消息分散,半小时就过去了"
- "切换多了容易漏消息,某条重要的退款申请没看到,处理超时了"

## 两种行为模式

| 模式 | 场景 | SaaS UX 难点 |
|---|---|---|
| **同窗口来回切** | 订单页 ↔ SKU 页 ↔ 库存页(上下文丢失) | 操作员负担高,易漏 |
| **多 tab 并行** | 财务/仓库/客服各开一个 tab(上下文隔离) | 跨 tab 复制信息,易出错 |

## 对 starcon 的影响

| 行业最佳实践 | 当前状态 | 缺口 |
|---|---|---|
| AppShell 全局一致,跨 tab 也保持(角色/菜单/tenant) | ✅ V1.3 已实现 | 无 |
| 详情用 Drawer/Modal,允许不离开列表看 SKU/订单/客户 | ❌ 全页跳转 | **要做** — 对应 ProBuilt Floating Forms |
| 关键链接支持"新 tab 打开",不打断当前流程 | ❌ 全是同 tab | **要做** — 对应简道云 Ctrl+点击 |
| 全局搜索,减少 menu hunting | ⚠️ 顶栏有占位 | **要强化** — 对应 Amazon universal search |
| 聚合视图(多店铺消息聚合到一个界面) | ❌ CRM/客服是单店视角 | 数据中心 + CRM 子页后续 |

## 核心判断

当前 4 系统 × 15 Domain 信息架构 + 左侧 Nav + 顶部 Segmented 切换器 **已符合"来回切换"的核心场景**。

但缺三个能力能覆盖 80% 的来回切换摩擦:

1. **Drawer 详情** — 订单/SKU/客户详情用右侧 Drawer 打开,不离开列表
2. **新 tab 打开** — 跨模块链接支持 `Cmd/Ctrl+点击` / `target="_blank"`
3. **全局搜索** — 顶栏搜索栏要支持跨模块索引(SKU/订单号/客户名/物流单号)

## 后续待办

- [ ] 决定 Drawer vs Modal 的使用边界(详情/编辑/创建)
- [ ] 给 `<Link>` 加一个 `external` prop 或全局行为(按修饰键打开新 tab)
- [ ] 全局搜索的索引范围与搜索语法(订单号 / SKU / 客户)
- [ ] CRM/客服 多店铺消息聚合 子页设计

## 参考

- ProBuilt Software — Patent-Pending Multitasking Architecture
- Amazon New Seller Central Experience (2026)
- Microsoft Dynamics 365 Business Central — Multi-tasking
- 简道云 ERP 多窗口操作技巧
- 拉力猫 / 跨境卫士 / 飞跨浏览器 行业评测