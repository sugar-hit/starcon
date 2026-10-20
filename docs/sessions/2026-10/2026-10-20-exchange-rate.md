# 2026-10-20: 5.2.2.1 汇率查询

## 摘要

交付能力蓝图 **5.2.2.1 汇率查询**（catalog 服务，阶段 D6，L3 锚点 5.2.2）。

蓝图职责是「查询各币种对各本位币的汇率（来源：中行/央行/平台结算汇率，可配置），
展示汇率日期与来源，支持手动录入特定日期的汇率」，输出「汇率记录（汇率、方向、生效日期、来源）」，
并加粗**「汇率方向必须显式（1 USD = X CNY 与 1 CNY = X USD 混淆是常见错源）」**。

上游是**外部汇率源**（不是内部模块）⇒ 本车道无未交付的内部上游依赖，可独立开工。
下游 5.2.2.2 汇率快照**未交付** ⇒ 本模块只产出**汇率事实**，不假装快照已存在。

## 决策 / 结论

1. **方向落成结构，不是注释**。`exchange_rate` 同时存 `base_currency` + `quote_currency`，
   `rate` 表示「1 基准 = rate 报价」。少任一列就写不进一条事实，结构上不可能混淆。
   接口与页面的每一处显示都是整句「1 USD = 7.1234 CNY」——**表格里没有裸的汇率数字列**。

2. **反向汇率由正向显式推导并标注，不落库**。`1/rate`（8 位小数 HALF_UP），
   显示为「1 CNY = 0.1403824 USD（由 1 USD = 7.1234 CNY 推导）」。
   手工再录一条方向相反的会被 **DB 触发器** `guard_exchange_rate_direction` 拒绝
   （SQLSTATE 23514），应用层预检把它翻译成 409 并指出已有的那一条。

3. **同一 币种对 + 同一生效日 只能有一条**（`UNIQUE(tenant_id,base,quote,effective_date)`）。
   否则「今天的汇率」有多个答案，5.2.2.2 快照与 5.3 测算不知道该冻结哪一条。重复录入 409。

4. **引用校验用触发器，不用外键**（`guard_exchange_rate_source` 区分「来源未登记 / 已停用 /
   录入方式与来源类型不符」；`guard_exchange_rate_source_delete` 拒绝删除被引用的来源）。
   理由是同批 batch-37 有车道在迁移里给既有表加外键，打破了同服务既有测试的 `TRUNCATE`
   ⇒ 39 例 Errors。本迁移**纯新建、零 ALTER 既有表**，且不建任何外键。

5. **来源是租户级配置，不是代码常量**。`exchange_rate_source` 存 code / name / kind /
   enabled / priority / endpoint；`POST /sources/initialize` 幂等写入蓝图点名的
   中行 / 央行 / 平台结算 / 手工录入四条。**停用只挡住「再用它录入」，已登记的汇率照常可读**。
   外部来源允许**先登记后填地址**（缺地址时拉取端点明确拒绝，不发一个必然失败的请求）——
   这也是 DB CHECK 的最终形态：`MANUAL ⇒ 无地址`，`EXTERNAL ⇒ 地址可空但非空串`。

6. **外部拉取真发 HTTP**，且**不携带用户的 Authorization 头**。第三方汇率源不是本仓服务，
   把会话凭据送出去等于泄露凭据 —— 这一条与 `CostBasisPort`（读 warehouse，必须透传）**刻意相反**，
   已在端口类注释里写明理由。

7. **🔴 失败路径不抛异常**。外部源失败时返回 503 / 400 的**响应体**而不是抛异常：
   抛异常会让外层事务回滚，把刚写的**失败日志**一起回滚掉 —— 而失败日志是这次拉取唯一
   留下的痕迹。`FetchResult` 因此多带一个 `failureStatus` 字段供端点层映射状态码。
   连不上 / 超时 / 非 2xx / 响应不认 / 没有汇率，五种情况都有明确原因，**不静默返回空**。

8. **本位币经 identity 授权只读端点读**（`GET /identity/legal-entities`，透传调用方 bearer，
   租户由 identity 从成员关系解析）。本位币归**法人**（17.1.2.2），本服务**不**另建本位币表，
   也不从任何请求头猜租户默认币种。读不到 → 503「读不到本位币」，不用空集合冒充「没有本位币」。
   该端点放在**独立路径**上：本位币读不到时，汇率台账仍然可用。

9. **汇率表归 catalog 自持**。V215（5.2.1.1）迁移注释里写着「本库没有汇率表（12 财务的资产）」，
   即本模块是这张表的归属方。12 财务（finance 服务）当前不存在 ⇒ 不建 finance 侧表、
   不读写 finance、**不 ALTER 5.2.1.1 的表结构**。trade 的 `fulfillment_dropship_fx_rate`
   是另一服务另一领域 ⇒ 不跨服务读、不建跨库 FK。

10. **更正 ≠ 新增事实**。同一天同一条汇率记错了改那一条（PUT），不新增第二条；
    更新路径同样过方向守卫与唯一性预检。

## 关键 trade-off

- **外部拉取要不要做**：蓝图点名了中行/央行/平台结算三个外部源，但「以后接外部 API」的占位
  不算交付。取舍是：**手工录入这条真实通路必须落地**（已落地），外部拉取也**真做**
  （真发请求、真解析、真处理失败、真落日志），但**响应格式契约化**（`{"rate":…}` 或
  `{"rates":[…]}`）而不是去适配每家机构的真实报文 —— 真实适配器是接入时逐个补的，
  不该在契约未定型时固化进本仓。
- **外部源的响应解析失败宁可报错也不猜**：把一个格式相近的数字当汇率写进库，会污染
  5.3 测算与 13 利润，而页面上看不出任何异常。
- **失败日志要 durable，而 HTTP 错误码要显式**：两者在同一个事务里天然冲突
  （抛异常 ⇒ 回滚 ⇒ 日志也没了）。选择「返回带状态码的响应体」而不是「抛异常 + 独立事务」，
  因为独立事务在**单连接连接池**下会自锁（测试池大小为 1，正是为了证明连接池归还不串租户）。

## 文件清单

**迁移**

- `backend/services/catalog/src/main/resources/db/migration/V225__exchange_rate.sql`（新建）
  — `exchange_rate_source` / `exchange_rate` / `exchange_rate_fetch_log` + 4 个触发器
  （方向守卫、来源引用校验、来源删除保护）+ RLS + GRANT。

**后端（catalog）**

- `.../ExchangeRateModels.java`（新建）— DTO 与词表
- `.../ExchangeRateRules.java`（新建）— 纯计算：方向显示、反向推导、录入校验、能力声明文案
- `.../ExchangeRateService.java`（新建）— jOOQ 显式 SQL 的台账 / 录入 / 更正 / 来源配置 / 外部拉取 / 本位币面板
- `.../ExchangeRateController.java`（新建）— 12 个端点
- `.../ExternalRateSourcePort.java`（新建）— 外部汇率源 HTTP 端口（不带用户凭据）
- `.../FunctionalCurrencyPort.java`（新建）— identity 本位币只读端口（含 Unwired 变体）
- `.../ExchangeRateConfiguration.java`（新建）— 两个端口的装配

**后端测试**

- `.../ExchangeRateIntegrationTest.java`（新建，14 例）— Testcontainers PostgreSQL 16、
  租户隔离三断言、方向显式、唯一性、外部拉取真实请求与失败态、时间戳写回
- `.../ExchangeRateRulesTest.java`（新建，13 例）— 纯规则

**前端**

- `apps/web/src/components/pricing/exchange-rate-model.ts`（新建）— 类型、接口调用、纯函数
- `apps/web/src/components/pricing/exchange-rate-model.test.ts`（新建，20 例）
- `apps/web/src/components/pricing/exchange-rate-workspace.tsx`（新建）— 真实工作区
- `apps/web/src/app/pricing/rates/page.tsx`（新建）— 页面
- `apps/web/src/lib/api-proxy.ts`（改）— **新增一条独立 `services[]` 元素**（锚点在数组末尾）

**契约 / 台账 / 会话**

- `packages/api-contracts/src/catalog.openapi.json` + `packages/api-contracts/src/contracts/catalog.ts`
  （codegen 产物：新增 10 条路径 + 12 个 DTO，无其它改动）
- `docs/product/capability-blueprint/module-progress.md`（改）— 只改 5.2.2.1 一行的「状态」「证据」
  两格；另由 `tools/module-progress/sync.mjs` 刷新了**派生汇总数字**（此前 10.x 有一行已标
  「已交付」但未计入汇总，sync 顺带纠正）。
- `docs/sessions/2026-10/2026-10-20-exchange-rate.md`（新建，本文件）

## 验证

- L0 编译闸：`cd backend && ./mvnw -q compile` ✅
- L0 类型闸：`pnpm typecheck` ✅ 3/3
- L1 模块闸：`./mvnw -pl services/catalog -am test -Dtest='ExchangeRate*'
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` ✅ **27 例全绿**
  （集成 14 + 规则 13）
- 前端：`pnpm vitest run apps/web/src/components/pricing/exchange-rate-model.test.ts` ✅ 20 例
- api-proxy 全量 allowlist 回归（改了 api-proxy.ts 必须跑）：`pnpm vitest run apps/web/src/app/api` ✅
  106 文件 / 625 例
- 契约：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` ✅
  （3715 DTOs；`tools/codegen/test_catalog_contracts.py` 17 例绿；`merge-union.itest.mjs` 全部通过）
- 零命中核对脚本 `.tmp/verify-proxy.mjs`：11 个新形状在既有 71 条正则里**零命中**，
  未遮蔽 14 个其它模块形状，新增元素位于 `services[]` 末尾 ✅
- `pnpm lint` ✅ 3/3

## 后续待办

- **5.2.2.2 汇率快照**（未交付）：冻结单据/定价生效时所用的汇率，保留换算过程
  （原价、汇率、舍入方式、目标币金额）。上游是本模块 —— 本模块的 `effective_date` +
  `base/quote` + `rate` 已经是快照所需的全部输入。
- **5.3 测算 / 12 财务**（未交付）：本模块只提供**带方向的汇率事实**，
  折算与冻结由下游做；本模块**不**代它们冻结汇率。
- **外部源真实适配器**：响应契约已定型（`{"rate":…}` / `{"rates":[…]}`），
  接入中行 / 央行 / 平台结算的真实报文时逐个补适配器即可，服务层与页面无需改动。
- **12 财务落地后**：汇率表归属需要复核（蓝图卷 05 写「汇率来自 finance，本系统只读引用」，
  本仓没有 finance 服务，所以暂由 catalog 自持）。
