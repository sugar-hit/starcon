# 8.4.2.1 拆解单（阶段 D4，L3 锚点 8.4.2）

车道 `feat/8-4-2-1`，Flyway `V108__warehouse_disassembly_order.sql`，基线 `integration/module-dev` @ `6273501`。

## 决策摘要

- **蓝图模块**：08 卷 8.4.2.1「拆解单」—— 成品拆解回组件：消耗成品库存、产生组件库存
  （退换货拆解、样品回收）。上游 8.4.1.1 组装单，下游 7 库存 / 11 售后。
- **最要紧的一条是蓝图加粗句**：「拆解结果的组件质量状态由接收方决定，**不能默认全部回可售**」。
  它不是一句提示，落成三层强制：DB 层 `destination_quality_status_id NOT NULL`；
  服务层 `requireDestinationQuality` **不提供默认值**，不传即 400；
  **去向逐行**，单头没有「整单默认去向」。
- **命名空间与 8.4.1.1 完全隔离**：`disassembly_*` / `/warehouse/disassembly-orders` /
  `ref_type=DISASSEMBLY_ORDER`。刻意不挂到 `assembly-orders` 底下 —— 「组装」与「拆解」
  必须能分别统计，挂在同一支下会让「哪些单据动过库存」有两个答案。

## 关键 trade-off

| 取舍 | 选择 | 理由 |
|---|---|---|
| 8.4.1.1 组装单同属 warehouse，要不要建外键 | **不建**，用 `reference_id` + 服务层显式 SQL 校验 | 外键只能保证「那张单存在」，表达不了「它必须已 COMPLETED」。而拆一件没组装过的成品在账上说不通 |
| 组件去向放单头还是逐行 | **逐行必填** | 同一张单里「面料回可售、螺丝回残次」是常态；单头一个默认值会把常态压平，也等于变相允许「整单回可售」 |
| 应回量服务端算还是调用方传 | **调用方冻结传入**，服务端不重算 | 与 6.4.1.1 trade、8.4.1.1 warehouse 同一纪律：BOM 在别的服务，跨库重算等于跨服务读表 |
| 出库与入库的先后 | **先出成品、后入组件** | 反过来先入组件，出库失败时货已入账 —— 凭空多出一批来路不明的组件 |
| 是否加「回收率」这一维 | **加**（8.4.1.1 没有） | 组装发料会损耗（损耗率），拆解退件会缺件/损坏（回收率）。没有它无法解释「BOM 上 10 个螺丝，拆出来只有 8 个」 |

## 与 8.4.1.1 的关系（已实测，非推断）

`pairsWithAssemblyOrder` 用例实跑：先组装 2 件（消耗面料 4、拉链 40，产出成品 2），
再按 100% 回收率拆回去 —— 组件净变化 0、成品净变化 0，两单分录挂在不同 `ref_type` 上可分别追溯。
这就是蓝图说的「成对且可对账」。

## 文件清单

后端（warehouse 服务）：
- `backend/services/warehouse/src/main/resources/db/migration/V108__warehouse_disassembly_order.sql`（新建）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/DisassemblyOrderModels.java`（新建）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/DisassemblyOrderService.java`（新建）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/DisassemblyOrderController.java`（新建）
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/DisassemblyOrderIntegrationTest.java`（新建，27 例）

前端：
- `apps/web/src/app/warehouse/disassembly-orders/page.tsx`（新建）
- `apps/web/src/components/warehouse/disassembly-order-workspace.tsx`（新建）
- `apps/web/src/components/warehouse/disassembly-order-model.ts`（新建）
- `apps/web/src/components/warehouse/disassembly-order-model.test.ts`（新建，21 例）
- `apps/web/src/app/api/warehouse/disassembly-order-allowlist.test.ts`（新建，7 例）

共享/累积文件（**只追加，不收窄**）：
- `apps/web/src/lib/api-proxy.ts` — warehouse/ 分支 alternation 顶层追加 `disassembly-orders` 一支
- `packages/api-contracts/**` — codegen 产物
- `docs/product/capability-blueprint/module-progress.md` — 只改本模块那一行

## 本车道踩到的三个坑（都是自找的，记下来别再犯）

1. **SQL 里写了 Java 的字符串 `+` 拼接**。两处 `COMMENT ON ... IS '...' + '...'` 直接让
   Flyway 挂掉、Spring 上下文起不来，**全模块 27 个测试类一起红**。报错文案是
   `syntax error at or near "+"`，指向位置 109，与真正的行号对不上号，下次看到先怀疑拼接。
2. **D3 的断言点选错了**。我以为「成品出现在组件明细里」在**插明细**时就该被拒，
   实际明细守卫只管「非草稿不可改明细」，D3 是**推进到 COMPLETED 那一刻**回读明细与账本才判定。
   断言写错 → 误判实现有 bug → 差点去改正确的实现。
3. **抄兄弟模块的注释会连注释一起抄错**。8.4.1.1 的 `disassemblyStatusTone` 注释写
   「两个终态用同一种视觉」，但代码给 CANCELLED 的是中性色。我照抄注释后写了条断言，
   与实现对不上。查下来**实现是对的**（取消 vs 已完成对库存的意义本来就不同），
   该改的是我抄来的注释 —— 不是去改别人已交付的文件，也不是把测试改成迁就错误注释。

## record 命名（重名扫描结果）

全部带 `Disassembly` 前缀，扫描 `backend/services/*/src/main/java/` **零命中**：
`DisassemblyComponentInput` / `DisassemblyOrderCreateInput` / `DisassemblyOrderCreated` /
`DisassemblyOrderExecuteInput` / `DisassemblyOrderExecuted` / `DisassemblyComponentRecovered` /
`DisassemblyOrderCancelInput` / `DisassemblyDestinationStat` / `DisassemblyOrderPage` /
`DisassemblyOrderRow` / `DisassemblyOrderDetail` / `DisassemblyComponentRow` /
`DisassemblyOrderEntry` / `DisassemblyCapabilities`。

**嵌套 record 也会按简单名平铺进 schema**，所以 `UpstreamModule` / `DownstreamModule`
这两个 8.4.1.1 已占用的名字**不能复用**，本模块用 `DisassemblyUpstreamModule` /
`DisassemblyDownstreamModule`。

未与 8.4.1.1 的 `AssemblyOrder*` / `assembly_order*` / `ASM-*` 命名空间发生任何交集。

## 验证

- L0：`./mvnw -q compile` 绿；`pnpm typecheck` 绿
- L1：`./mvnw -pl services/warehouse -am test` 绿；本模块 `DisassemblyOrderIntegrationTest` 27 例全过
- 前端：模型 21 例 + 白名单 7 例全过；白名单守卫并跑 8.4.1.1 自己的 6 例，确认求并未误伤

## 后续待办

- **11 售后（退件拆解的发起方与接收方）未交付**：capabilities 显式 `available=false`，
  写明「判成可用还是残次是售后侧的判定，本模块只负责忠实执行、不替它决定」。
- **8.4.2.1 现场作业（拆解工位 / 拆解扫码）未建模**：本模块只到库存动作为止。
- 沿用 8.4.1.1 遗留观察：api-proxy 的 warehouse/ 分支上 `outbound-orders`、`printing`、
  `consignments`、`stocktake-variances` 在集成头上仍未登记（**非本车道引入**，HEAD 基线即为 404），
  应由各模块车道自己补。