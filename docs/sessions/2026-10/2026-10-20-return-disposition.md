# 10.7.1.1 退货接收与处置决策（channel 服务）

> 会话：2026-10-20 · 车道 `feat/10-7-1-1`（worktree `.worktrees/10.7.1.1`）· 集成头基线 `969a9832`
> ⚠️ 本车道**上一 worker 中途崩溃**（`net::ERR_CONNECTION_CLOSED`），本会话是**续写**：
> 5 个后端文件由上一 worker 留下且未提交，本会话逐个读完判断完成度后**保留 + 修 bug + 补完**，
> 集成测试 / 前端 / api-proxy / 契约 / 台账 / 会话日志全部由本会话从零完成。

## 决策摘要

按蓝图 10 卷 10.7.1.1 交付「退货接收与处置决策」，归属 **channel** 服务，迁移
`V214__return_receiving_disposition.sql`（预分配版本号，**零 ALTER 既有表**）。

蓝图职责句三样输入，真值位置与蓝图写的**不一样**：

| 蓝图输入 / 上游 | 真值实际在哪 | 本模块怎么接 |
| --- | --- | --- |
| 退货实收记录（11.2.2.1） | **trade** `return_receipt` / `return_receipt_line`（V172） | `ReturnReceiptQueryPort` 读 trade 授权 HTTP API |
| 质检结论（11.2.3.1） | **trade** `return_quality_inspection`（V179） | 同上（列表 + 留痕两跳） |
| 商品再销售限制（品牌方 / 平台政策） | **全库没有这类表** | 本模块**自持** `return_resale_restriction` |
| 仓储作业（8） | warehouse | 本模块只登记「哪个仓」，不碰仓储作业表 |
| 库存状态变更指令（→ 7） | 7.4.1.1 调整单管理**未交付** | 只产出指令事实，**只出不消费** |
| 处置成本（→ 12 财务） | **finance 服务不存在** | 本服务自有事实落地，capabilities 显式声明 |

⇒ 全程**零跨服务 SQL、零跨库 FK、零共享视图**（AGENTS.md 模块边界纪律）。

### 加粗句「默认按『可再售』处理会高估回收价值，转卖/弃置决策必须显式」的五道结构闸

这条是本模块唯一不可让步的约束，落地时**刻意不写成一句提示语**，而是五道彼此独立的闸：

1. **列上没有 DEFAULT。**
   `disposition_method varchar(24) NOT NULL CHECK(disposition_method IN ('RESALE','RETURN_TO_COUNTRY','DISCARD'))`
   —— 决策表与处置单表两张都是。少给一列 ⇒ DB 直接 **23502 not-null violation**。
   写一个 `DEFAULT 'RESALE'` 就正好是蓝图点名禁止的那种默认。
2. **决策与处置单两段分离。**
   去向先落在**决策**上（谁 / 什么时候 / 凭什么），处置单只能**引用**一条决策；
   触发器 `guard_return_disposition_order_decision` 钉死「去向逐字相同 + 数量相同 + 决策只能被消费一次」。
   于是「这张处置单为什么是转销售」永远有一条可审的决策记录可追，
   而不是处置单自己带一个 `disposition_method` 就完事。
3. **决策终态冻结。**
   `guard_return_disposition_decision_final`：被消费（CONSUMED）**与**被取代（SUPERSEDED）都算终态 ——
   不许改去向 / 数量 / 依据 / 判定时刻。改它等于改历史。
4. **转销售另有两道服务端否决**，判定结论**冻结在决策行上**
   （`quality_verdict` / `quality_inspection_id` / `resale_restricted` / `resale_restriction_note`）：
   - **质量否决**：上游 11.2.3.1 质检结论必须 `RESALABLE`。
     判成 DEFECTIVE / SCRAPPED ⇒ 拒；**上游还没有质检结论 ⇒ 同样拒** ——
     「没有证据证明它能再卖」不等于「大概率能卖」。
   - **政策否决**：本租户不得命中任何品牌方 / 平台政策的再销售限制。
   DB 侧再钉一道 CHECK：判了转销售就必须带 `RESALABLE` 结论与 inspection id，且 `resale_restricted = false`。
5. **拿不准落到人工决策。**
   没有决策的接收登记是 `PENDING_DECISION`，页面文案「待人工决策」——
   **不是**「转销售」。前端下拉框第一项是**空值占位**，页面从不预选、也不高亮任何一项。

### 「可再售」是判定出来的，不是默认出来的

`GET /channel/return-dispositions/eligibility`（只读、无副作用）返回三个去向**各自**
`allowed` 与 `blockingReasons`。决策与这个预览走**同一条**服务端 `evaluate()` 路径 ——
各算一次必然漂移，而漂移的方向一定是「预览说能转销售、真判的时候又不能」。

页面**不在浏览器里自己比对**质检与限制：那条规则一旦散落到前端就一定会漏一处。
服务端还没给出结论时，三个去向**一律不许选**。

## 关键 trade-off

- **决策 / 处置单 / 限制分三张表，不合并。**
  「限制」是**配置**（与某次处置无关，可登记可撤销），「决策」是**判定**（某一次凭什么这么判），
  「处置单」是**执行**（这次花了多少钱、回收了多少）。混在一张表上就会出现
  「一行既是被处置的货、又是结论、又是政策」。
- **再销售限制本服务自持，且可见性不按负责人收窄。**
  查过 trade / catalog / warehouse 全库，没有「再销售限制」这类表；
  蓝图把它列为本模块的**输入**，所以归本模块自持（不假装上游有）。
  它**只挂 isolation 不挂 data_scope**（照抄 V136 `logistics_channel_restriction`）——
  理由是**正确性**不是省事：限制判定是服务端做的「全租户否决」，
  可见性按负责人收窄会让「否决」变成「可绕过的建议」。
- **成本合计不落表，查询时现算 SUM。**
  落表就得在每次加成本、每次改数量时同步它，漏同步一次就会出现一个比明细更笃定的错数。
- **成本与残值是两个币种，本模块不做任何汇率换算。**
  成本明细行币种**必须**与处置单成本币种逐字相同（触发器钉死）——
  混币种的「合计」是没有意义的数字；残值币种**可以**不同
  （真实场景：退运成本记 USD，海外仓清仓残值在 EUR 结算）。
  「成本 − 残值」的净额与折算归 12 财务。
- **库存指令「只出事实，不动对方的表」。**
  消费方 7.4.1.1 未交付 ⇒ `instruction_status` 恒 PENDING、`consumer_module` 恒 7.4.1.1，
  且 `REVOKE UPDATE,DELETE ON return_inventory_change_instruction` ——
  「在本模块里把它标成已消费」既没有代码路径也没有 SQL 权限。
  （照抄 trade V179 `return_quality_disposition_instruction` 的同一纪律。）
- **撤销处置单不撤回已产出的指令。**
  指令是「当时下达过什么」的事实；此刻没有任何库存被改动（消费方未交付）。
  改判的正确做法是「撤销本单 → 重新记录一条决策 → 重新下发」。
- **上游「读不到」与「没有」是两件事。**
  上游不可达 / 5xx ⇒ **503**，理由写「读不到」；上游正常返回但没有该行 ⇒
  判「尚未判定」⇒ **禁止转销售**。把「没质检」当成 503 会让系统坏了看起来像没质检；
  把它当成「可再销售」会直接把残次品当新品卖。**绝不返回空集合冒充「上游没有退货」。**
- **端口转发调用方自己的 bearer，绝不自己传租户号、绝不发明 `X-Tenant-Id` 头。**
  租户隔离因此是**两道**（channel 侧 RLS + trade 侧 RLS），不是本服务替对方决定租户。

## 上一 worker 遗留代码的处置

五个文件全部**保留**，逐个读完后判断完成度：

| 文件 | 处置 |
| --- | --- |
| `ReturnDispositionModels.java`（605 行） | **原样保留**。枚举 / 记录 / 能力声明形状完整，无 TODO、无 mock |
| `ReturnReceiptQueryPort.java`（207 行） | **原样保留**。写法与 `PendingShipmentQueryPort` / `SellableQuantityQueryPort` 逐字同构 |
| `V214__return_receiving_disposition.sql`（912 行） | **原样保留**。七张表 + RLS + GRANT + 触发器齐备，处置方式列上确实没有 DEFAULT |
| `ReturnDispositionService.java`（1870 行） | **保留 + 修 2 个真 bug**（见下） |
| `ReturnDispositionController.java`（286 行） | **原样保留**。14 个端点齐备 |

### 修掉的两个真 bug（都是「代码全在但一跑就炸」）

1. **`cancelOrder` 的 UPDATE 有 5 个占位符只绑了 4 个参数**
   （`cancel_reason` / `cancelled_at` / `updated_at` / `tenant_id` / `id`）——
   撤销处置单必炸（jOOQ「未设定参数值 5 的内容」）。补上第二个 `now()`。
2. **`registrations()` 的 count 查询漏了 lateral join**
   —— `dispositionState` 筛选引用的是 `d.id` / `o.id`（最近一条决策、最近一张处置单），
   计数查询没有 join ⇒ 带筛选时 SQL 语法错；就算改成能跑的写法，计数与列表口径也会漂移。
   改成与列表共用同一段 `REGISTRATION_FROM`。

两个 bug 都在**跑通集成测试**时才暴露 —— 编译期完全看不出来。

## 文件清单

**后端（channel 服务）**

- `backend/services/channel/src/main/resources/db/migration/V214__return_receiving_disposition.sql`
  （七张表 + 3 个单号序列 + 9 个触发器 + RLS isolation/data_scope + GRANT/REVOKE）
- `backend/services/channel/src/main/java/com/starcon/channel/ReturnDispositionModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/ReturnDispositionService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/ReturnDispositionController.java`（14 个端点）
- `backend/services/channel/src/main/java/com/starcon/channel/ReturnReceiptQueryPort.java`
- `backend/services/channel/src/test/java/com/starcon/channel/ReturnDispositionIntegrationTest.java`（26 例）
- `backend/services/channel/src/test/java/com/starcon/channel/ReturnDispositionTenancyIntegrationTest.java`（3 例）

**前端**

- `apps/web/src/app/logistics/returns/page.tsx`
- `apps/web/src/app/logistics/returns/returns-model.ts`
- `apps/web/src/app/logistics/returns/returns-model.test.ts`（15 例）
- `apps/web/src/app/logistics/returns/returns-workspace.tsx`
- `apps/web/src/app/api/channel/return-disposition-allowlist.test.ts`（5 例）
- `apps/web/src/lib/api-proxy.ts`（**新增独立一条 services[] 元素**，锚点固定在
  10.1.1.3 的 `logistics-transit-limits` 那条**之后**、10.1.2.1 的 `freight` 那条**之前**；
  既有条目一字未动、不重排、不格式化整文件）

**契约与台账（多车道共用，合并驱动器按类型名 / OpenAPI 路径 / 模块 ID 求并）**

- `packages/api-contracts/src/channel.openapi.json`（**+14 路径 / +29 schema / 0 改动 / 0 删除**）
- `packages/api-contracts/src/contracts/channel.ts`（+29 行）
- `docs/product/capability-blueprint/module-progress.md`（只改 10.7.1.1 一行的状态与证据两格 +
  `sync.mjs` 自动重算的 5 行总览统计）

## api-proxy 条目的形状与不撞车论证

本车道**新增一条独立的 `services[]` 元素**（`prefix: 'channel/'`），
**不往既有 channel 长 alternation 里并分支** —— 那是同批多车道共享一个文件时的
结构性冲突源（retry 永远不会自愈）。

放行的形状只有本模块真实实现的 14 条：裸路径**不放行**（后端没有裸集合读），
`/decisions/{id}/consume`（决策只能被下发消费，没有独立消费端点）与
`/orders/{id}/instructions`（指令只出不消费，放行它会让人以为本页能确认 7 库存已执行）
**刻意 404**。id 段用**完整 UUID 形状**（`[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}`），
不是 `[0-9a-f-]+` —— 后者把单字符 `e` 当合法 id。

## 验证

- **L0 编译闸**：`cd backend && ./mvnw -q compile` → exit 0（只有 Maven/Jansi 的既有告警）。
  `pnpm typecheck` → **3 successful / 3 total**（`@starcon/web` cache miss 实跑 tsc）。
- **L1 模块闸**：`./mvnw -pl services/channel -am test -Dtest='ReturnDisposition*'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → **29 tests / 0 failures / 0 errors / 0 skipped**
  （ReturnDispositionIntegrationTest 26 + ReturnDispositionTenancyIntegrationTest 3）。
- **前端**：`pnpm vitest run apps/web/src/app/api/channel/return-disposition-allowlist.test.ts
  apps/web/src/app/logistics/returns` → **2 files / 20 tests 全绿**。
- **allowlist 全量回归**（改了共享的 `api-proxy.ts`）：`pnpm vitest run apps/web/src/app/api/trade`
  → **45 files / 238 tests 全绿**。
- **lint**：`pnpm --filter @starcon/web lint` → 通过（残留告警都在别的模块的既有文件里，
  本车道新增文件零告警）。
- **契约**：`./mvnw test -Dtest='OpenApiContractTest'`（6 个服务各出一份真实 SpringDoc 快照）
  → `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
  → 3514 DTOs / 6 文件。channel 侧 **+14 路径 +29 schema，0 删除、0 改动**。
  codegen 同时重排了 `trade.ts` / `trade.openapi.json`（**内容逐字相同、仅排序不同**，
  是基线本来就有的漂移），已 `git checkout` 还原 —— 那是别的车道的文件，本车道不越界改。

### 容器口径

L1 期间**本车道同时存活的 PG 容器数为 1**（两个测试类各一个 Testcontainers 容器，
surefire 顺序执行、每个类 `@AfterAll` 里 `pg.stop()`，所以 PG 本身不重叠；
两个 ryuk reaper 会在 JVM 退出前先后存在）。跑完后本车道的容器**全部被回收**，
`docker ps` 里残留的 PG 容器来自**并行车道 10.6.1.1**（跑 `LogisticsCostAnalysis*`），
不是本车道的。

## 后续待办（不属于本车道）

- **12 财务（finance 服务）交付后**：处置成本与残值需与 12 财务做最终对账。
  本模块已把「成本」与「残值」作为**两笔各自带币种的事实**落地，形状不用改。
- **7.4.1.1 调整单管理（warehouse）交付后**：消费 `return_inventory_change_instruction`
  （本模块已备好 `instruction_type` / `target_inventory_state` / `qty` / `sku_code`）。
  消费侧落地时需要把 `RETURN_PENDING` / `DISPOSAL_PENDING` 映射到 warehouse 的角色词表
  （`SELLABLE` 已与 `inventory_quality_status.role` 逐字对齐，另两个状态在 warehouse 侧尚无角色）。
- **10.7.1.2 退运回国 / 10.7.1.3 弃置与销毁**：本模块只出「这批货决定退运 / 销毁」这个**决策**，
  两者的批量合单成本分摊、销毁凭证与品牌方索赔依据都归那两个模块。
- **11.2.3.3 供应商 / 品牌索赔**：销毁数量与凭证由 10.7.1.3 产出后才谈得上索赔。
