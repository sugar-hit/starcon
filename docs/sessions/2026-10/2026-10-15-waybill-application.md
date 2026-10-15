# 10.2.1.2 运单申请（trade）

> 车道 `feat/10-2-1-2` · 迁移版本 V128（调度器预分配）· 2026-10-15

## 决策摘要

蓝图 L3 锚点 10.2.1.1 里那句「本模块不调用承运商下单接口（属 10 物流，未交付）」
与「运单单号由承运商回执登记」——**本模块就是那个缺口**，且 10.2 的第一块地基
（10.2.1.1 运单列表、10.2.1.3 改址取消均未开发）。

三条不可让步的边界：

1. **幂等键是输入契约，不是优化。** 蓝图输入句第四项就是 `idempotency_key`，
   所以 DB 层 `UNIQUE(tenant_id, idempotency_key)` 是**完整唯一**（不是条件索引），
   服务层重复提交返回原来那一张并标 `idempotentReplay`。
   另有条件唯一索引挡住「同一包裹 + 同一承运商两张有效申请」。
2. **不造运单号。** `ISSUED` 只能由承运商适配器**真实返回**时进入；
   DB CHECK + 触发器各钉一道。10.1 未交付就如实失败并写明 ownerModule。
3. **限流排队是持久化队列，不是同步阻塞循环。** 入队一次、逐条放行、
   一次请求不许跨承运商。

## 关键 trade-off

**① 承运商：本地登记 + 适配器端口，而不是等 10.1。**
10.1 整卷未交付 ⇒ 没有连接配置。三个选项：
(a) 什么都不做，把整模块标 BLOCKED；(b) 凭空造一个「已接通的承运商」；
(c) 建模成**可登记可扩展的本地事实** + **明确的适配器端口**。
选 (c)：`logistics_carrier` 存代码/渠道/限流口径（**不含 endpoint 与凭据**，
归 secret manager），`CarrierWaybillPort` 是统一接口、`CarrierAdapterRegistry`
按 `adapterKey` 索引全部实现。10.1 接通后新增 vendor 包即可，
**本模块表与服务层一行不用改**。代价是现在就出不了号 —— 这是如实反映，不是缺陷。

**② 视图 `waybill_channel_rate_limit` 承载限流默认值，单独 GRANT。**
视图不继承 `GRANT ... ON ALL TABLES`，漏授权的症状是「表都读得到，
一查视图就 permission denied」——踩过一次，已写进迁移注释。
视图本身不携带 RLS（策略在基表上），所以查它时**必须显式带 tenant_id 谓词**。

**③ 逐包裹一张申请，不是逐出库单。**
面单逐件打印，逐出库单申请会让第二个包裹拿不到自己的面单。
9.4.1.1 的批量对象也是包裹（`shipment_pending_parcel`），二者对齐。

**④ 批量申报明细逐件给。**
`WaybillBatchCustomsInput.pendingParcelId` **必填**，刻意不提供「整批共用一份」：
共用会让件 B 的面单印着件 A 的件数与 HS 编码 —— 承运商照错的申报放行、
海关按错的口径查验，扣货与罚款是蓝图 10.5 明写的后果。

**⑤ 409 不是一种状态，是三件事。**
批量结果里把 409 一律算「跳过」会让现场以为「这批已经办好了」：
重复提交（幂等命中）算跳过，申报口径对不上算**失败**（改申报信息才有用）。

## 过程中发现并修掉的 4 个真实缺陷

都不是测试写错，是代码真的会出错的地方：

| 缺陷 | 症状 | 修法 |
|---|---|---|
| 负页码算出负 OFFSET | PostgreSQL 报 `OFFSET must not be negative`，异常处理器统一映射成 **409「请刷新后检查输入」** —— 一个页码笔误在界面上显示成「数据冲突」，现场刷新十次都找不到原因 | `offsetOf()` 钳位 + 补一条反例测试 |
| 注册表见到实现就 Connected | 「已注册但此刻不可用」（凭证过期、渠道停用、灰度开关）被显示成能下单 | `resolve` 把判定交回 `port.resolve()` |
| 队列失败时不报「未到期条数」 | 批量里「发了 1 条、还有 3 条在排」显示成「没有剩余」，运营无从判断要不要加窗口 | 始终统计 `countNotYetDue()` |
| 视图权限 | 见上文 trade-off ② | 迁移里单独 `GRANT SELECT` |

另外在测试夹具上修掉：V124/V128 没有 `current_tenant()` 这个 SQL 函数
（租户是显式绑定的）、jOOQ 对「单表多列」的 `fetchOne` 直接返回 `Record` 而非 `Result`、
`@Transactional` 会把业务异常包起来所以断言要用 `catchThrowable` 而不是 `assertThrows`、
以及 static 测试开关会被 JUnit 执行顺序污染。

## 文件清单

**迁移（1）**
- `backend/services/trade/src/main/resources/db/migration/V128__trade_waybill_application.sql`
  — 五张业务表 + 序列 + `waybill_channel_rate_limit` 视图 + 5 个触发器 + RLS + GRANT

**后端（7）**
- `WaybillModels.java` — 枚举与契约形状（24 个 record）
- `WaybillApplicationService.java` — jOOQ 显式 SQL + 状态机 + 队列 + 放行 + 重试 + 作废
- `WaybillApplicationController.java` — 10 个端点
- `CarrierWaybillPort.java` — 承运商适配器统一接口（含 sealed `Resolution`）
- `CarrierAdapterRegistry.java` — 按 adapterKey 索引 + 如实报 Unavailable
- `CustomsAttributePort.java` — 3.7.1.1 跨服务只读 + 申报口径核对
- `CarrierLabelTemplatePort.java` — 8.3.4.2 跨服务只读

**测试（1）**
- `WaybillApplicationIntegrationTest.java` — 32 例，实跑 Testcontainers PostgreSQL 16，池压到 1

**前端（5）**
- `apps/web/src/app/logistics/waybills/page.tsx`
- `apps/web/src/components/logistics/waybill-workspace.tsx`
- `apps/web/src/components/logistics/waybill-model.ts`
- `apps/web/src/components/logistics/waybill-model.test.ts`（24 例）
- `apps/web/src/app/api/trade/waybill-allowlist.test.ts`（5 例，双向断言）

**契约（codegen 产物，只可重新生成不可手改）**
- `packages/api-contracts/src/contracts/trade.ts`（新增 24 个 `Waybill*` DTO）
- `packages/api-contracts/src/trade.openapi.json`

**共用车文件的最小改动**
- `apps/web/src/lib/api-proxy.ts` — trade 段**外科式最小追加** `logistics/waybill-applications` 一组
- `docs/product/capability-blueprint/module-progress.md` — 只改 `| 10.2.1.2 |` 那一行的状态与证据

## 验证

| 闸 | 命令 | 结果 |
|---|---|---|
| L0 | `cd backend && ./mvnw -q compile` | 绿（EXIT=0） |
| L1 | `cd backend && ./mvnw -pl services/trade -am test` | **401 例全绿**，BUILD SUCCESS |
| 类型 | `pnpm typecheck` | 绿（3 包成功） |
| Lint | `pnpm lint` | 绿 |
| 前端 | `pnpm vitest run <本模块 2 文件>` | 29 例全绿 |
| 回归 | `pnpm vitest run apps/web/src/app/api/trade/ api-proxy*.test.ts` | 14 文件 **191 例全绿**（兄弟十组未被挤掉） |

测试期间并存的 PG 容器：Testcontainers 每次跑起 1 个 `postgres:16-alpine`；
结束后 ryuk 已全部回收（`docker ps` 无残留）。

## 后续待办

1. **10.1 接通后**：在 channel 侧新增 vendor 包实现 `CarrierWaybillPort`，
   本模块只需保证登记的 `adapter_key` 与之对应 —— 表与服务层不用改。
2. **限流配额**待 10.1.1.3 接入后由它喂进 `logistics_carrier` 的限流列。
3. **跨渠道混排未做**：一次请求固定一个承运商（各家配额互不相通，混排没法算窗口）。
4. **10.2.1.1 / 10.2.1.3 / 10.3 未交付**：已出号的运单在本模块只能读，
   取消与改址会 409 并指向 10.2.1.3；轨迹与异常件不在本模块。
5. **`OrderChangeCancelIntegrationTest` 的跨用例干扰**（9.1.1.3，与本模块无关）：
   用 `-Dtest=A,B` 指定顺序跑时会失败 3 例；换成 `PendingShipmentIntegrationTest`
   同样复现，且单独跑全绿 —— 是集成分支上既有的 Spring 上下文/测试顺序问题，
   **不是本车道引入**（本车道只新增文件，未修改任何共享测试）。
