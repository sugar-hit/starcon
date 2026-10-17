# 2026-10-17 9.3.3.1 履约请求

## 决策摘要

落地蓝图 09 卷 L3 锚点 9.3.3 之下的 9.3.3.1「履约请求」：把「分仓 / 合单的结果」编排成一次
**可推进、可断点恢复、可超时重试**的履约流程，主责服务 trade。

- **流程状态落在 PostgreSQL**，不落内存。头行 `fulfillment_request` 的
  `status + current_step + process_version` 三件套就是蓝图加粗句
  「流程状态必须持久化，断电重启后要能继续」的落点。
- **断点恢复做成 DB 可验证的不变量**，不是口号：`current_step` 是阶梯
  （RESERVE→OUTBOUND→WORK→TRACK），延迟约束触发器强制「current_step 之前的每一步
  都必须有一条 COMPLETED 记录」，于是「恢复到这一步」在数据库层面就等于「前面真的做完了」。
- **乐观并发做在 DB 层**：七条 SECURITY DEFINER 窄函数全部比对 `expected_version`，
  不符报 409。蓝图输入里点名了这个字段，所以真的实现了并用测试证明冲突会被拒。
- **下游三个（7.2 预占 / 8.3.1 出库单 / 10 物流）全部跨库不可读**，一律表达为
  **本服务内登记的下游指令行**（带状态、来源、幂等键），capabilities 里
  `reservesUpstreamStock=false` 等三个布尔显式报出。

## 关键 trade-off

1. **上游只存逻辑引用 + 快照，不 JOIN 已交付的同服务表。**
   分仓（V116）、合单（V144）同服务且已交付，读它们看似更省事；但一单多仓多批次场景下
   本模块要为**每个履约单元**留一份「当时按哪个仓判的」凭据，JOIN 出来的是当前状态而不是
   决策时状态。快照才是举证凭据。
2. **拆单（并行车道 V151）只占一列，不建表不读表不校验。**
   集成头 20ccd017 上实查确认拆单未交付；`split_unit_ref` 只是逻辑引用，
   capabilities 里 `available=false` 如实报出「拆单未接通」。
3. **写路径纪律分两类，而不是「全 append-only」。**
   留痕三表（`_step` / `_timeout` / `_event`）REVOKE UPDATE,DELETE；
   当前状态四表（头行 / `_reservation` / `_outbound_order` / `_work_order`）也 REVOKE UPDATE,
   DELETE，但推进只经窄函数。理由：蓝图要的「重启后能继续」恰恰要求当前状态**就地**更新 ——
   把它们也做成 append-only 是照抄过头。`_step` 另留**唯一**一个合法改写口
   （同一次尝试内 RUNNING→COMPLETED，重试会开 attempt+1 的新行），用 transaction-local GUC 开窗。
4. **跟踪完成是独立端点而不是 `advance` 的一个分支。**
   「状态说完成了但没有任何完成记录」是这类流程最典型的自欺，所以完成凭据与状态必须同事务落。
5. **超时判定要求真的超时。**
   `block_fulfillment_request` 在 `detection_kind='TIMEOUT'` 时校验 `clock_timestamp() >= deadline_at`，
   阈值未到就报超时被 DB 挡下 —— 蓝图 9.3.3.2 说的「先查证再决定」要有依据。

## 实现期抓到的三个真问题（都先复现再修）

1. **`SECURITY DEFINER` 绕过 RLS。** 函数体以属主身份执行，只靠函数外层的 RLS 不够 ——
   本租户已认证的用户拿着别家租户的 `request_id` 就能推进别人的流程。
   七个窄函数全部自带 `head.tenant_id IS DISTINCT FROM nullif(current_setting('app.current_tenant',true),'')::uuid`。
   本仓已有的 `advance_warehouse_allocation_status` 缺这一层，本模块补上。
2. **`UPDATE` 只收 DELETE 等于没收。** 最初只 REVOKE 了 DELETE，直接 UPDATE 会先撞上状态机触发器
   而被挡 —— 但那是**副作用**不是保证：一条顺手把 `warehouse_ref` / `requested_qty` 改掉、
   version 也照样递增的 UPDATE 就能通过全部校验。四张当前状态表改为 REVOKE UPDATE,DELETE。
3. **白名单挡不住 DELETE。** 最初在 api-proxy 注释里写「任何 DELETE 刻意不放行」，
   真跑 proxy 才验到白名单是**按 path 判别、不看 HTTP 方法**。注释与测试已按事实改写，
   并新增一条读控制器源码断言「无 `@DeleteMapping` + 迁移含 `REVOKE UPDATE,DELETE`」。

## 文件清单

**后端（trade 服务）**

- `backend/services/trade/src/main/resources/db/migration/V152__trade_fulfillment_request.sql`（新增）
- `backend/services/trade/src/main/java/com/starcon/trade/FulfillmentRequestModels.java`（新增）
- `backend/services/trade/src/main/java/com/starcon/trade/FulfillmentRequestService.java`（新增）
- `backend/services/trade/src/main/java/com/starcon/trade/FulfillmentRequestController.java`（新增）
- `backend/services/trade/src/test/java/com/starcon/trade/FulfillmentRequestIntegrationTest.java`（新增，33 例）

**前端**

- `apps/web/src/app/orders/fulfillment/page.tsx`（新增）
- `apps/web/src/components/orders/fulfillment-request-model.ts`（新增）
- `apps/web/src/components/orders/fulfillment-request-workspace.tsx`（新增）
- `apps/web/src/app/api/trade/fulfillment-request-allowlist.test.ts`（新增，6 例）
- `apps/web/src/lib/api-proxy.ts`（新增一条独立 `trade/` 元素；既有 alternation 一行未动）
- `packages/ui/src/lib/task-pages.ts`（只改 `/orders/fulfillment` 一行的 `implemented`）
- `packages/api-contracts/**` + `backend/target/openapi/**`（codegen 产物，只读）

**台账**

- `docs/product/capability-blueprint/module-progress.md`（只改 9.3.3.1 一行的状态与证据）

## 验证

- `cd backend && ./mvnw -q compile` ✅
- `cd backend && ./mvnw -pl services/trade -am test` ✅ 33/33（Testcontainers PostgreSQL 16，连接池压到 1）
- `pnpm typecheck` ✅ 3/3 turbo tasks
- `pnpm vitest run apps/web/src/app/api/trade/fulfillment-request-allowlist.test.ts` ✅ 6/6
- 契约：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` ✅ 新增 31 个 `FulfillmentRequest*`，零删除

## 后续待办

- **9.3.3.2 履约流程监控**：跨请求异常清单、超时看板、人工处置入口与**释放预占**。
  本模块的 `fulfillment_request_timeout` 表是它的数据来源；
  capabilities 里 `monitoringDelivered=false` 已显式标出未交付。
- **9.3.1.1 拆单（V151）落地后**：由它往 `fulfillment_request` 补 `split_unit_ref` 引用即可，
  本模块表结构与判定逻辑不需要改动。
- **7.2 / 8.3.1 真接通时**：按同一形状往 `_reservation` / `_outbound_order` 回填下游回执，
  幂等键已逐条落库，重放不会让两边各锁一批货。
- **10 物流下单**：`capabilities` 已报 `logisticsOrderPlaced=false`；运单主权在 9.4.1.1 / 8.3.4.2，
  本模块不自建运单表。
