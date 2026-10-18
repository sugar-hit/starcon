# 11.3.2.1 换货授权（阶段 D4）

> 车道 `feat/11-3-2-1`，worktree `.worktrees/11.3.2.1`，Flyway **V162**（版本号由调度器预分配）。

## 决策摘要

按蓝图 11-aftersales-and-returns.md 11.3.2.1 交付「批准换货」。职责句里加粗的
「**要真实预占替换货**」是本模块唯一的技术难点，其余（生成新出库安排、保留原单追溯）都是常规落库。

**上游依赖实查结论（全部已交付，无 BLOCKED）**：

| 依赖 | 位置 | 结论 |
| --- | --- | --- |
| 售后单 + 商品行 | trade V156 / V157 | ✅ `aftersales_case`（`case_type='EXCHANGE'`）、`aftersales_case_line` |
| 库存与预占 | warehouse V102 / V107 / V112 | ✅ 预占事实的写入所有者是 warehouse，有授权**写**端点 |
| 原订单行 | trade `sales_order_line` | ✅ |
| 替换商品事实 | catalog `GET /catalog/skus/{id}` | ✅ |

## 关键 trade-off

### 1. 预占落在 warehouse，本服务只落「记录」——为什么这样才对

预占事实的写入所有者是 warehouse（`inventory_reservation`，V102）。AGENTS.md 禁止跨服务
SQL / 外键 / 共享视图，所以 trade 侧只能走**授权写端点**：`POST /warehouse/inventory/reservations`
登记、`POST .../{id}/release` 释放（7.2.1.3，V112 已交付）。

代价是本服务要处理一个**分布式事务**：多行授权时第 3 行预占失败，第 1、2 行已经在 warehouse 落库了，
而本地事务一回滚就查不到那两行。取舍是**真补偿**——逐笔调 release，拿不到释放应答就整笔回滚并如实报错。

**明确拒绝的替代方案**：在本地写一个 `is_reserved` 标志位 + 读一次可售量。
那不是预占，是把「查到什么」记成「占住了什么」，并发两单会把同一份库存预占两次。

### 2. 加粗句靠 DB 层强制，不靠服务层自觉

「换货必须同时预占替换货并等待旧货回寄」。服务层自觉会被绕过服务层的直连 SQL 击穿，
所以做成**两个 `DEFERRABLE INITIALLY DEFERRED` 约束触发器**，在 COMMIT 时复核：

- `trg_exchange_line_reserved`：恰好一笔预占 + 恰好一条回寄要求 + `预占量 == 授权量 == reserved`
- `trg_exchange_authorization_complete`：各行数量之和 == 头上数量，替换 SKU 数一致

选 DEFERRABLE 而不是 BEFORE 触发器，是因为授权是「建头 → 逐行建预占 → 建需求与回寄要求」的多步写，
BEFORE 触发器跑在建行那一刻、预占记录还不存在。

**测试直接打这一层**：在独立事务里绕过服务层直连插「有授权行无预占」「有预占无回寄」，
断言在**提交那一刻**被 PG 用中文报错打回。

### 3. 并发防线落在 trade 侧，顺序是 load-bearing 的

实查发现：warehouse 的 7.2.1.1 登记端点在 `(source_system, source_ref_id)` 上**没有**唯一约束
（V102 只 `UNIQUE(tenant_id, reservation_no)`，而单号是 warehouse 自己发的）。
所以同一张换货售后单被并发提交两次时，**两次请求都会真的打到 warehouse 并各拿到一个 reservation_no**——
预占两次从此成立。

唯一的防线是 trade 侧：`SELECT ... FOR UPDATE` 锁 `aftersales_case` → 查重 →
`UNIQUE(tenant_id, case_id)` 判第二次。**顺序反了这个防线就废了**（第二次会在远程调用之后才被拒）。
测试实跑断言：并发两次授权，一成一 409，且 warehouse 登记端点**只被调用一次**。

### 4. 「拒绝」刻意不落成一行

库存不足而授权不了，就是**没有授权可立**。落一条 `REJECTED` 记录只会让人在列表里反复看到一张
永远不会被处理的单。所以头表只有 `ACTIVE` / `CANCELLED` 两态，凑不齐时服务层返回 409 并写明差多少；
允许部分授权时结论记 `PARTIALLY_APPROVED`，部分批准与整量批准在数据上分开，
否则「批了 2 件、申请 3 件」会被读成全额批准。

### 5. 不建下游回填列

蓝图输出只到「新发货需求」。9.3.3.1 新出库与 11.2.2.2 旧货回寄**均未交付**，
所以刻意**不建** `outbound_order_ref` / `dispatched_at` 这类永远为空的列——
建了只会在页面上显示成「已下发」而实际没有任何出库单，正是「空时间线被读成没发生过迁移」同款假交付。
`capabilities` 如实报 `outboundHandoffDelivered=false` / `oldGoodsReturnDelivered=false` 并给出归属模块。

## 过程中被测试逼出来的真实缺陷

1. **jOOQ 没有 `fetchOne(String, Class, Object...)` 这个重载** —— 该调用退化成 `fetchOne(String, Object...)`，
   于是 `Object.class` 被当成**绑参**塞进 SQL，运行时报
   「Type class java.lang.Class is not supported in dialect POSTGRES」。`fetch` 同样没有
   `(String, Class, Object...)`。这个坑很隐蔽：SQL 里的 `?::uuid` 全都对，单看语句看不出问题。
2. **查「没授权过」不能按必有行的标量去 `.get(0, ...)`** —— 第一次授权直接 NPE。
3. **`aftersales_case_line` 是 append-only（V157 REVOKE 了 UPDATE/DELETE）**，
   而 PG 的 `SELECT ... FOR UPDATE` 需要 UPDATE 权限 ⇒ 对它加行锁既拿不到也没意义；
   行锁只加在售后单头上，明细表本来也不会被并发 UPDATE。
4. **明细行 append-only ⇒「先插 0 再回填预占量」在本服务里根本不可执行**。
   改成「先预占、后建行，建行即预占足额」，正好是那条迟到约束触发器要复核的形态。
5. **契约静默缺口**：授权对话框拿不到商品行，首版把 `caseId` 当 `caseLineId` 传，
   后端会以「商品行不属于这张售后单」拒掉 —— 一个用户看不懂的错。
   ⇒ `filters` 随单下发 `ExchangeCaseLineOption`；`statusLabels` / `decisionLabels` 一并下发，
   界面按下标取标签，**不在前端另写一份词表**（前端硬编码就等于有两个真相）。
6. `cancel` 的 SELECT 误写了 `authorization_id` 列（头表没有这一列，是明细表才有）。
7. 前端 `Badge` 的真实属性是 `tone`（neutral/brand/success/warning/danger/info）不是 `variant`，
   `StatCard` 没有 `hint`，UI kit 里**没有** `Select` 组件（用原生 `<select>`，照抄同族工作区写法）。

## 文件清单

**迁移**
- `backend/services/trade/src/main/resources/db/migration/V162__trade_exchange_authorization.sql`

**后端**
- `backend/services/trade/src/main/java/com/starcon/trade/ExchangeAuthorizationModels.java`
- `.../ExchangeAuthorizationService.java`
- `.../ExchangeAuthorizationController.java`
- `.../ExchangeReservationPort.java`（warehouse 写端口：可售量 / 登记预占 / 释放）
- `.../ExchangeSkuQueryPort.java`（catalog 读端口：替换商品事实 + 在售校验）

**后端测试**
- `backend/services/trade/src/test/java/com/starcon/trade/ExchangeAuthorizationIntegrationTest.java`（25）
- `.../ExchangeAuthorizationTenancyIntegrationTest.java`（6）

**前端**
- `apps/web/src/app/aftersales/exchanges/page.tsx`
- `apps/web/src/components/aftersales/exchange-authorization-model.ts`
- `apps/web/src/components/aftersales/exchange-authorization-workspace.tsx`
- `apps/web/src/components/aftersales/exchange-authorization-model.test.ts`（25）
- `apps/web/src/app/api/trade/exchange-authorization-allowlist.test.ts`（4）
- `apps/web/src/lib/api-proxy.ts`（**只新增一条 services[] 元素**，未碰既有 trade 分支）

**生成物 / 台账**
- `packages/api-contracts/src/contracts/trade.ts`（+12 个 Exchange* 类型，**零类型丢失**，已比对前后类型名集合）
- `packages/api-contracts/src/trade.openapi.json`
- `docs/product/capability-blueprint/module-progress.md`（**只改 11.3.2.1 那一行的状态与证据两格**）

## 验证

- L0：`cd backend && ./mvnw -q compile`（无输出即通过）；`pnpm typecheck`（turbo 报 FULL TURBO，
  已另跑 `apps/web` 下 `tsc --noEmit` 取真实结果，exit 0）
- L1：后端 31 例全绿；前端 model 25 + allowlist 4 全绿；另跑全量 allowlist 回归 **178 例**全绿，
  确认新增条目未误吃他人已交付的路由
- lint：我的文件零 error（残留 warning 属其他车道既有文件）

## 后续待办

1. **11.3.2.2 换货新单**（下游）：建 `exchange_order_link`，跟踪换货闭环、防止重复换货。
   它交付后应回填本模块 `exchange_outbound_demand` 的出库接手状态。
2. **11.2 退回旧货实收**（V161 车道 + 11.2.2.2）：`exchange_return_requirement` 目前只有
   `WAITING` / `WAIVED`，**没有 `RETURNED`** —— 实收与质检归该模块，届时由它推进状态。
3. **9.3.3.1 新出库接手**：从 `exchange_outbound_demand`（`status='PENDING'`）取需求建出库单。
4. **预占的物理圈货**（7.2.1.2 warehouse 侧）：本模块只登记**预占需求**，
   圈到具体 bucket 由 warehouse 的分配策略执行，V107 §三已明确「本表不重复实现扣减」。
