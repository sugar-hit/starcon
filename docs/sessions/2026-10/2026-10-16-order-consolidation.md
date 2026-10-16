# 2026-10-16 9.3.1.2 合单（阶段 D4）

## 决策摘要

按蓝图 09 卷 9.3.1.2 交付「合单」：把同一买家的多笔订单合并成一个包裹发货，
降低运费与包材成本；识别跨店铺同买家订单，并按平台**分别**回传物流号。

- 迁移 `V144__trade_order_consolidation.sql`，五张新表、**零 ALTER 既有表**。
- 后端 `OrderConsolidation{Controller,Models,Service}`，端点 8 个挂 `/trade/fulfillment/consolidation`。
- 前端真实工作区 `/orders/fulfillment/consolidation`。
- 测试：后端 26 例（Testcontainers PG16，池压到 1）+ 前端 21 例 + 白名单守卫 5 例。
- trade 全套 **639 例全绿**。

## 关键 trade-off

### 1. 「同一买家」用客户 ID，不用姓名电话

实查发现 V99 的 `guard_sales_order_submitted` 要求 SUBMITTED 订单**必须**有
`sales_order_party_snapshot`，所以可合订单在生产里一定带 `customer_party_id`。
选它而不是姓名+电话+邮箱，是因为**同一收货地址上完全可能有两位买家**（家人、同事代收、合租），
只用姓名电话判会把两箱货合成一箱 —— 那是错发，不是省钱。
地址身份保留为退回路径（`A:` 前缀），客户 ID 用 `P:` 前缀，两种口径不可能撞出同一个摘要。

### 2. 地址指纹用 U+001F 而不是 `|`

收件人字段由运营自由录入。`|` 会让「Smith|John」与「Smith」+「John」两组字段
拼出同一个字符串 —— 一次**跨买家的错误合单**，比不合单严重得多。

### 3. 候选检索的锚点用 `order_no` 打破平手

`submitted_at` 相同时**不能**用 `order_id`（uuid 随机）——否则「谁是锚点」每次查询都不一样，
同一批数据在两次刷新之间给出不同候选集，运营会认为系统在乱跳。

### 4. 「报价不得高于分开发合计」只能写在延迟触发器里

`separate_shipping_amount` 是成员行写完之后才由 `guard_order_consolidation_totals`
实算回填的。插头那一刻它还是 `DEFAULT 0`，写成 CHECK 会让「0 >= 报价」恒假，
**把所有带报价的合并在插头时就拒掉**。本车道实际踩到过这个坑（见下「踩过的坑」）。

### 5. 触发器必须用 `UPDATE OF 列清单`

`guard_order_consolidation_totals` 自己会 UPDATE 成员行的 line_count/total_qty。
若监听整行，更新自己写的列会**再次排队**本触发器 → 无限循环 → 提交时挂死连接。
只监听 `consolidation_id, order_id, member_status`。

### 6. 回传任务落在表结构上，不只写在注释里

蓝图加粗句「合单后每个平台订单仍要单独回传物流号」如果只写注释，
下一次有人把回传做成「一个包裹一个任务」时没有东西会拦住他。
所以 `order_consolidation_writeback_task` 是 **一行一个平台订单**，
`UNIQUE(tenant_id, member_id)` 把「不多不少」钉死；手工单不建任务（没有平台可回传，
建了是给「回传零失败」这个指标掺水）。

## 踩过的坑（都改成了带注释的不变式）

| 现象 | 根因 | 处理 |
| --- | --- | --- |
| 测试挂死 | 延迟触发器监听整行，自触发无限循环 | `UPDATE OF consolidation_id, order_id, member_status` |
| 带报价的合并全被拒 | CHECK 在 `separate` 还是 DEFAULT 0 时求值 | 挪进实算之后的延迟触发器 |
| 23514 `violates check constraint order_consolidation_check6` | 未命名 CHECK 的 `_checkN` 编号不可读 | 本表 CHECK **全部命名** |
| 回传登记撞 `updated_at>=created_at` | 拿入参的业务时刻填了行的记账时刻 | `updated_at=now()`，业务时刻进 `submitted_at`/`settled_at` |
| 触发器 `invalid reference to FROM-clause entry` | UPDATE 的 FROM 子查询引用了更新目标表 | 先把成员订单 id 收成集合再对 `sales_order_line` 分组 |
| 候选集恒为空 | 测试裸调 `service.*`，FORCE RLS 看不到任何行 | 一律包在可信事务里 |
| 候选集两次刷新结果不同 | 平手用随机 uuid 打破 | 改用 `order_no` |

## 文件清单

**新增**

- `backend/services/trade/src/main/resources/db/migration/V144__trade_order_consolidation.sql`
- `backend/services/trade/src/main/java/com/starcon/trade/OrderConsolidation{Controller,Models,Service}.java`
- `backend/services/trade/src/test/java/com/starcon/trade/OrderConsolidationIntegrationTest.java`
- `apps/web/src/app/orders/fulfillment/consolidation/page.tsx`
- `apps/web/src/components/orders/order-consolidation-{model,model.test,workspace}.{ts,tsx}`
- `apps/web/src/app/api/trade/order-consolidation-allowlist.test.ts`

**修改**

- `apps/web/src/lib/api-proxy.ts`（新增一条独立 `trade/` 元素，既有 alternation 一字未动）
- `packages/api-contracts/src/contracts/trade.ts`（纯新增）、`channel.ts`（仅字母序重排）
- `docs/product/capability-blueprint/module-progress.md`（只改 9.3.1.2 那一行）

## 后续待办

1. **9.3.1.1 拆单仍未开发**。任务书称它「已交付」并称 `fulfillment_routing_*` 是它的产出 ——
   实查台账与代码，这些表属 **9.3.2.2 FBA 与自发货选择**，拆单本身台账为「未开发」。
   本模块与它无表/函数耦合（只依赖 9.1.1.1 已交付的订单视图），故按可交付推进；
   拆单落地后需要复核的是：两者是否共用「履约切分」的入口约定。
2. **D5 平台单同步未交付** → `sales_order` 没有 `shop_code` 列，
   故跨平台可识别、**跨店铺暂不可区分**（`platformShopResolved=false` 已如实报出）。
   D5 落地后由同步器补 `shop_code`，本模块表结构与读法都不用改。
3. **下游未交付**：8.3.3.2 打包（warehouse）、9.3.3.1 履约请求（trade）、9.4.2.1 物流号回传。
   当前 DISPATCHED 只是「已下发」这一个动作的凭据，回传任务由 9.4.2.1 消费
   （同服务同库读 `order_consolidation_writeback_task`）。
4. **契约可空性缺口**：`maxWindowHours` / `consolidatedShippingAmount` / `ruleBlockedReason`
   在 OpenAPI codegen 里被生成成非空，但后端确实返回 null。前端已用显式 cast 绕开并注明；
   若 codegen 支持 `nullable`，应从源头修掉这三处 cast。
5. 9.2 风控未交付，故规则里的 `requireSamePaymentMethod` 给的是租户开关而不是系统判定。
