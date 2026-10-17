# 2026-10-17 9.3.1.1 拆单（trade）

## 决策摘要

在 `feat/9-3-1-1`（worktree `.worktrees/9.3.1.1`，基线 `integration/module-dev` @ `20ccd017`）
交付能力蓝图 09 卷的 **9.3.1.1 拆单**：把一个订单按仓库 / 承运商 / 发货时效 / 平台限制
拆成多个履约单元，输出履约单元 + 库存预占指令 + 按拆分执行的发货回传。

命名空间 `order_split_*`，迁移 `V151__trade_order_split.sql`，端点 `/trade/fulfillment/split`，
页面 `/orders/fulfillment/split`。

## 关键 trade-off

1. **蓝图加粗句「拆单不产生新的销售订单」做成 DB 硬约束，不做成注释。**
   `order_split.creates_sales_order` 恒 false 且由
   `CHECK(order_split_never_creates_sales_order = false)` 写死。写成列是为了让它出现在
   `\d` 输出里；集成测试有反例（绕过服务层 UPDATE 被 DB 挡下）。

2. **守恒的是订单总额（买家视角），不是行货值。**
   `Σ(单元行分摊额) + 尾差 == order_total_amount`，行金额只作**分摊权重**
   （行金额占比 × 数量占比）。理由：运费与税挂在订单上而不挂在某一行上，按行切货值会让
   买家看到的总额变 —— 那正是这条加粗句要防的事。

3. **守恒由 DB 在提交时复核，不靠服务层的 if。**
   `recalc_order_split(uuid,uuid)` 是 `DEFERRABLE INITIALLY DEFERRED` 的普通函数，
   单元行侧与明细行侧两个触发器都薄薄地转调它（PostgreSQL 不允许直接调用 trigger 函数，
   所以实算主体必须先抽成普通函数，否则两边各算各的）。
   测试用超级用户连接绕过服务层插一条「分摊 7 元 ≠ 总额 100 元」的明细，`commit()` 必须抛错。

4. **尾差必须有归属。** 照 V116 的 `PARTIAL_SHORTFALL` 做法：HALF_UP 到 4 位产生的几分钱
   落进 `order_split_unit_line.residual_amount`，确定性归到一行（金额最大者，同额按
   `(unit_seq, order_line_ref)` 字典序），部分唯一索引 + 触发器复核「至多一行承载」。

5. **四个拆分维度是租户口径的落库存档配置，不是 Java if。**
   `order_split_rule` 的四个布尔 + `primary_strategy`，并用 CHECK 钉住「主维度必须真的开着」
   「四个不能全关」。分组后按四元组字典序发单元号 → **同样输入两次拆逐项一致**（测试用递归比较钉死）。

6. **候选依据落库不联算。** 库存会被补上、仓会被停用、规则会被改；查询时联算只能回答
   「现在重拆会怎么拆」，答不出「当时为什么这么拆」。落选候选与被选候选同等重要。

7. **排不下的货绝不静默塞给某个仓。** 显式落成落选候选 + `blockedReason` 说清「还剩多少
   没排上」。货权错记比晚发严重得多。

8. **预览与落库共用同一段 `plan()`。** 两边各算一次必然出现「预览说 3 个单元、落库变 2 个」
   这种最消耗信任的偏差。

## 两个上游前提的核实结论

任务书给的两条前提，**实查后全部成立，无反证**：

- **9.2 审单整条线未交付**：`grep '^CREATE TABLE' | grep -i 'review\|risk\|audit'` 在 trade
  服务 `db/migration` 下为空 —— 待审队列 / 审单规则 / 异常拦截都没有建表语句。
  ⇒ 本模块只存 `order_ref` 逻辑引用 + 收货/货权快照列，不建指向审单记录的引用、
  **不校验审单结论存在性**；`capabilities` 里 `ORDER_REVIEW` 维度 `available=false`
  且 `orderReviewConnected=false`。
- **7 预占跨库不可读**：`inventory_reservation` / `reservation_entry` / `reservation_allocation`
  / `stock_bucket` 全在 **warehouse 自己的数据库**里。⇒ 预占落成
  `order_split_reservation_command`（**指令登记**），并用
  `CHECK(order_split_reservation_never_reserves_upstream CHECK(upstream_reserved = false))`
  从数据层杜绝「两边都以为占了同一批货」（V116 里踩过的坑），`capabilities` 里
  `reservesUpstreamStock=false` 同报。

**第三条（9.3.3.1 是本批并行 V152）同样成立**，本模块**不建也不读**它的表，输出「多个履约请求」
落成 `order_split_unit` 履约单元行（带状态）由它后续消费。

## 过程中被集成测试当场逮住的四个真 bug（都已修）

1. `order_split_unit_line` 第一版按 `(tenant_id, split_id, order_line_ref)` 唯一 ——
   这等于从数据层禁掉了「部分现货先发」（同一行分两批走两个仓是本模块存在的理由）。
2. `recalc_order_split` 里 `UPDATE ... SET total_qty = total_qty` 与同名 PL/pgSQL 变量撞名，
   报 `column reference "total_qty" is ambiguous` —— **只在提交时炸**，每次落拆单都炸。
3. 守恒等式按 `unit_status <> 'CANCELLED'` 过滤 —— 一次**合法作废**就会让
   「分摊合计 0 ≠ 订单总额」成立，于是每次作废都被守恒触发器当成违规拒掉。
   ⇒ 金额守恒算的是「方案本身」不看状态；活着的单元数才看状态。
4. `reject_reason` 的 CHECK 词表里没写空串，而**被选中**的候选恰好就是空串 ——
   每一条被选中的候选都被 CHECK 拒掉。
   另有 `event_type varchar(24)` 装不下 28 字符的 `RESERVATION_COMMAND_ISSUED`。

## 契约踩的两个坑（已修，值得记）

1. codegen 的撞名闸先拦下**跨服务**撞名：`WarehouseRow`（warehouse 服务已有）与
   `ReservationPage`。
2. 更隐蔽的是**同服务内**撞名，撞名闸管不到：我的
   `WritebackTask` / `WritebackInput` / `WritebackPage` / `StockSnapshotRow` / `StockSnapshotInput`
   **静默覆盖了 9.3.1.2 合单与既有模块的同名契约**，别的车道消费端类型会被改写。
   已全部加 `Split` 前缀；复核结果：**既有 schema 改写 0 / 删除 0，新增 28 类型 + 12 path**。
   `trade.openapi.json` 的大 diff 经排序比对证实为**纯键序重排**，既有内容逐字节一致。
3. 附带一条：我的 controller 方法名（`rule` / `preview` / `splits`）与兄弟 controller 撞名，
   曾导致 springdoc 给 269 个**既有**端点重新编号 `operationId`。
   codegen 的 `normalize_operation_ids` 会按路径重算，所以最终产物不受影响，
   但这是「新增 controller 方法名要避开既有 controller」的一个提醒。

## 文件清单

新增：

- `backend/services/trade/src/main/resources/db/migration/V151__trade_order_split.sql`
- `backend/services/trade/src/main/java/com/starcon/trade/OrderSplitModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/OrderSplitService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/OrderSplitController.java`
- `backend/services/trade/src/test/java/com/starcon/trade/OrderSplitIntegrationTest.java`
- `apps/web/src/components/orders/order-split-model.ts`
- `apps/web/src/components/orders/order-split-model.test.ts`
- `apps/web/src/components/orders/order-split-workspace.tsx`
- `apps/web/src/app/orders/fulfillment/split/page.tsx`
- `apps/web/src/app/api/trade/order-split-allowlist.test.ts`

修改（**只碰自己的那一条**）：

- `packages/api-contracts/src/contracts/trade.ts`（+28 类型，0 删除）
- `packages/api-contracts/src/trade.openapi.json`（codegen 产物）
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 `services[]` 元素**，锚点夹在既有
  `prefix: 'trade/'` 之前；既有 trade 长单行 alternation 一字未动）
- `packages/ui/src/lib/task-pages.ts`（只新增 `/orders/fulfillment/split` 一条记录）
- `docs/product/capability-blueprint/module-progress.md`（只改 9.3.1.1 那一行）

## 验证

- L0：`backend ./mvnw -q compile` 绿；`pnpm exec tsc --noEmit` **web + ui 直出全绿**
  （**未采信 turbo 缓存命中** —— 它 replay 了另一条车道 `10.1.1.1` 的日志）。
- L1：`./mvnw -pl services/trade -am test` **701 例零失败**（本模块 36 例）。
- 前端：`pnpm vitest run` 本车道三文件 **23 例绿**（model 18 + allowlist 5），
  外加 `nav-routes.test.ts` / `task-pages.test.ts` 23 例绿。
- 迁移本身另在独立 PostgreSQL 16 容器上实跑过一遍（10 表零错误），随后清理。

## 后续待办

1. **9.2 审单接通后**：在本表补一条审单结论引用，`order_ref` 的判读口径收紧；
   表结构与既有读法不需要改。
2. **7 预占接通后**：本模块调 warehouse 的授权 API 建立真实预占，回执写回
   `external_reservation_ref`；`upstream_reserved` 恒 false 这条 CHECK 需随之下线
   （在此之前不要绕过它 —— 那会让两边都以为占了同一批货）。
3. **9.3.3.1（V152）接通后**：按 `order_split_unit` 这一形状消费履约单元行。
4. **9.4.2.1 回传接通后**：由它真正向平台发请求，本模块的
   `order_split_writeback_task` 只保留逐单元登记与结果留痕。
5. 本模块的 `allowed_platforms` 目前是仓级逗号分隔清单；若要做「平台 × 仓」关系管理，
   那是 2.5.1.1 能力矩阵的事，不该由本模块占表。
