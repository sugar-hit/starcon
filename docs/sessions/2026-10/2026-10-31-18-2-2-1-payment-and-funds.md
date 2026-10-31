# 18.2.2.1 支付与资金（卷 18 集成开放与智能自动化 → L3 18.2.2 支付与资金 → 第一格）

- **日期**：2026-10-31
- **车道**：18.2.2.1（分支 `feat/18-2-2-1`）
- **阶段**：D7
- **状态**：已交付
- **后端主责服务**：platform（8089）

## 决策摘要

蓝图原文（卷 18 → 18.2 → 18.2.2 → 18.2.2.1）：
「职责：对接支付与资金服务（PayPal、Stripe、PingPong、连连、万里汇）：余额查询、收款登记、付款执行、
汇率查询、手续费明细同步。输入：资金操作请求；接口配置（**密钥引用**）。输出：操作结果 + 资金流水同步。
关系：上游 = 12.2 收付款、12.7 资金账户；下游 = 支付服务商。**所有第三方资金操作必须有幂等键与查询兜底**，
超时先查证再重试（实体目录 §6）。」

本车道交付的是 **18.2 卷里「外部资金通道 + 本系统资金台账」这一层**：
服务商适配层（端口 + 五个逐服务商实现 + 注册表）、平台自己拥有的那一层
（支付配置、幂等键与操作台账、查证兜底、资金流水台账、余额 / 手续费 / 汇率观测），
以及与上游 12.2 / 12.7 的**只读**授权连接。

**本仓没有任何真实的服务商凭据与网络通道** ⇒ 五个服务商的直连能力**一律如实答 `available=false`**，
端点如实答 409 且**不写成功流水**。这与兄弟模块 18.2.1.1「财务软件」已确立的做法同构。

## 关键 trade-off

### 1. 「密钥引用」怎么落 —— 沿用 18.1.1.1 应用凭证的纪律，但**摘要的是引用而不是密钥**

`platform_payment_provider` 三个凭据面：`credential_ref`（密钥管理器里的**引用 ID**）、
`credential_ref_lookup`（**引用**的 SHA-256 十六进制摘要，只用于按引用定位这一行）、
`credential_hint`（末四位，人眼分辨用）。**库里没有任何密钥明文**。

**与 V377（18.1.1.1）的差别要说清**：V377 摘要的是 **API Key 本体**（256 bit CSPRNG 随机串，高熵、不可枚举，
所以摘要可当定位索引）；这里摘要的是**引用串** —— 引用串本身**不是机密**（它是「去哪里取密钥」的指针），
摘要它既不泄露密钥、又能按引用定位。**密钥本体从不进这张表**，所以 V377 里「Secret 侧刻意没有可定位摘要」
那条风险面在本模块**不存在**。测试用 information_schema 断言七张表**没有**任何
`secret / password / api_key / token` 列。

### 2. 「幂等键 + 查询兜底」怎么实现 —— 把「超时 ≠ 失败」做成**两个不同的状态值**

这是本模块最核心的正确性要求，做法是**把纪律钉在数据层**，而不是写在注释里：

| 纪律 | 物理形态 |
| --- | --- |
| 同键重复提交只产生一笔 | `UNIQUE(tenant_id, idempotency_key)` ⇒ 同键**只可能命中同一行**，服务层原样返回（`replayed=true`）、**绝不第二次调用服务商** |
| 同键必须是同一个请求 | `request_digest`（SHA-256）⇒ 同键不同内容 ⇒ **409**（那不是重放，是另一个请求借用了旧键） |
| 超时不等于失败 | `status` 里 **UNKNOWN** 与 **FAILED** 是**两个值**。把超时记成失败，重试逻辑就会跳过查证直接二次付款 ⇒ 资损 |
| 先查证再重试 | `platform_payment_attempt.attempt_kind` 有 `SEND` 与 `QUERY` 两种，**`attempt_no` 由两者共用一条递增序列** ⇒ 「QUERY 的序号 < 重发的 SEND 序号」在数据上自证 |
| 查证结论决定能不能重发 | 查证说**成功** ⇒ **不重发**；说「服务商侧确实没有」（NOT_FOUND）⇒ 才允许重发；**查不出来** ⇒ **不允许重发**（查不到 ≠ 没付出去） |

**测试替身**：外部边界用 `StubPaymentProviderPort`（一个 `PaymentProviderPort` bean，
按脚本回放「超时 → 查证 → 重发」的每一步，并单独计数 `sendCalls()` / `queryCalls()`）。
被测的服务层、幂等判定、查证状态机、jOOQ 显式 SQL、RLS **全是真代码** ——
替身的只是「服务商那一次网络往返」，而没有它就**测不到**超时分支。

### 3. 「未接入」要写进台账，还是一次数据都不写？—— **刻意与 18.2.1.1 不同**

18.2.1.1 的规则是「外部适配器未接入就一个字都不写」，它拒绝写的是**「凭证已推给金蝶了」这种成功痕迹** ——
那种记录才危险（财务会拿着一张**不存在的**法定凭证去做账）。

本模块的操作台账是**请求账本**，它记的是「谁在什么时候发起了一次付款请求、服务商侧结论是什么」：
`status=NOT_CONNECTED` + `provider_notified=false` + `failure_class=NOT_CONFIGURED` +
`provider_notice`（写明缺什么、归谁）—— **不可能**被误读成成功。
所以本车道**写这一行**，并把 HTTP 状态码翻成 **409**（不是 200：200 会被前端当成「这笔钱处理完了」）。

**连带的一个刻意的不对称**：
- **付款执行**必须动钱 ⇒ 未接入 ⇒ 409 且**不写资金流水**；
- **收款登记**的主体效果是**本系统台账**（蓝图输出写的是「操作结果 + 资金流水同步」）⇒ 未接入时**照样登记本地流水**，
  但 `provider_notified=false` + `provider_notice` 写明「服务商未接入，未向服务商登记」。

### 4. 与 finance 的连接：**只读**，且**不回写**

上游 12.2 收付款 / 12.7 资金账户住在 finance 服务里，AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图
⇒ `FinanceFundPort`（8087）只经授权 HTTP 端点读，**只转发调用方自己的 bearer**、不传租户号、不抄余额历史，
取不到一律 **503**（是「读不到」不是「没有数据」）。

**「资金流水回写上游 12.2 / 12.7」这一维如实答 `available=false`**：finance **没有**提供接受外部流水写入的授权端点，
而「每张事实表只有一个写入所有者」不允许本模块去写 finance 的账。平台自己的 `platform_fund_flow` 是
**本系统那一层**，两边不混。

### 5. 汇率查询的来源：**参考汇率**（catalog，真实可读）与**服务商报价**（未接入）分开

蓝图把「汇率查询」写进本模块的职责，而汇率有两个口径：
catalog 5.2.2.1 汇率事实（**参考汇率**，本模块**真读**）与服务商锁汇报价（**含点差**，归服务商通道，未接入）。
端点两个都试：**优先用服务商报价**；服务商未接入时用参考汇率，并把 `source` 如实标成 `CATALOG`。
反方向推导出来的汇率打 `inverted=true` —— 页面必须让人看见它是推导值。

### 6. api-proxy.ts：**纯增量**，锚点在数组末尾

白名单是多车道共用的**累积型**文件，取错一侧会**静默删掉别人已交付的路由**，而 typecheck / lint / vitest / Playwright **全绿**。
本车道只**新增一条**独立的 `services[]` 元素（`prefix: 'platform/'`，首 token `payment-funds`），
既有条目**一字未改、也不重排**；插入锚点选在**数组末尾**，与同批另一条车道（18.1.3.1）拉开距离。

发车前用一次性脚本（`.tmp/api-proxy-zero-hit.mjs`，**不入库**）**用 TypeScript 编译器解析本文件里
`services` 变量的真实数组字面量**（不是文本计数），按 `resolve()` 的同构口径实测：
本模块 22 条路径对既有 **272** 条元素**零命中**；新元素对 17 条别的车道形状**零命中**（不遮蔽）；
13 条负向形状全部被拒。

## 文件清单

**迁移**
- `backend/services/platform/src/main/resources/db/migration/V416__payment_and_funds.sql`（七表 + RLS + GRANT + 只追加触发器）
- `backend/services/identity/src/main/resources/db/migration/V416__platform_payment_permission.sql`（5 个权限码 + OWNER 补权）

**后端（platform）**
- `PaymentFundsModels.java` — 传输模型（枚举 + record）
- `PaymentProviderPort.java` — 服务商适配端口（五件职责 + **独立的 `queryPayment` 查证**）
- `AbstractUnavailablePaymentProviderAdapter.java` — 「未接入」基类
- `PayPalProviderAdapter.java` / `StripeProviderAdapter.java` / `PingPongProviderAdapter.java` /
  `LianLianProviderAdapter.java` / `WorldFirstProviderAdapter.java` — 五个逐服务商实现
- `PaymentProviderRegistry.java` — 服务商代码 → 适配器（外部 bean 可覆盖内置实现）
- `FinanceFundPort.java` — 上游 12.7 / 12.2 **只读**端口（finance 8087）
- `CatalogRatePort.java` — 上游 5.2.2.1 汇率**只读**端口（catalog 8083）
- `PaymentFundsService.java` — 服务层（幂等键判定、查证兜底状态机、jOOQ 显式 SQL、append-only 台账）
- `PaymentFundsController.java` — 22 条端点（`/platform/payment-funds`）

**后端测试**
- `PaymentFundsIntegrationTest.java`（18 例）
- `PaymentFundsTenancyIntegrationTest.java`（7 例，多租户三断言）
- `PaymentProviderAdapterTest.java`（4 例，五家适配器如实答未接入）
- `StubPaymentProviderPort.java`（外部边界测试替身）

**契约（codegen 产物）**
- `packages/api-contracts/src/contracts/platform.ts`、`packages/api-contracts/src/platform.openapi.json`

**前端**
- `apps/web/src/app/integrations/connectors/payment-funds/page.tsx`
- `apps/web/src/components/integrations/payment-funds-model.ts` / `payment-funds-model.test.ts` / `payment-funds-workspace.tsx`
- `apps/web/src/app/api/platform/payment-funds-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（**纯新增**一条 `services[]` 元素）

**台账 / 会话**
- `docs/product/capability-blueprint/module-progress.md`（**只改自己那一行**的状态与证据两格）
- `docs/sessions/2026-10/2026-10-31-18-2-2-1-payment-and-funds.md`（本文件）

## 验证

**L0 编译闸**
```
cd backend && ./mvnw -q compile
```
⇒ exit 0（无输出）。

**L0 格式自检（只查自己改动过的前端文件）**
```
pnpm exec prettier --check apps/web/src/components/integrations/payment-funds-model.ts \
  apps/web/src/components/integrations/payment-funds-workspace.tsx \
  apps/web/src/components/integrations/payment-funds-model.test.ts \
  apps/web/src/app/api/platform/payment-funds-allowlist.test.ts \
  apps/web/src/app/integrations/connectors/payment-funds/page.tsx \
  apps/web/src/lib/api-proxy.ts
```
⇒ `All matched files use Prettier code style!`

**L1 模块闸（只跑本模块新增的测试类）**
```
cd backend && ./mvnw -pl services/platform -am test \
  -Dtest='PaymentFunds*,PaymentProviderAdapterTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
```
⇒
```
Tests run:  4, Failures: 0, Errors: 0 -- PaymentProviderAdapterTest
Tests run: 18, Failures: 0, Errors: 0 -- PaymentFundsIntegrationTest
Tests run:  7, Failures: 0, Errors: 0 -- PaymentFundsTenancyIntegrationTest
Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**前端测试**
```
pnpm vitest run apps/web/src/components/integrations/payment-funds-model.test.ts \
  apps/web/src/app/api/platform/payment-funds-allowlist.test.ts
```
⇒ `Test Files 2 passed (2) / Tests 27 passed (27)`

**全量 allowlist 回归（多车道共用的白名单必须全绿）**
```
pnpm vitest run apps/web/src/app/api
```
⇒ `Test Files 278 passed (278) / Tests 1571 passed (1571)`（未误伤兄弟车道）

**类型检查**
```
NEXT_TELEMETRY_DISABLED=1 pnpm typecheck
```
⇒ `Tasks: 3 successful, 3 total`

**契约**
```
python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi
```
⇒ `Generated 7583 DTOs in 9 per-service files, 9 OpenAPI snapshots and one barrel`，
git 状态里**只有** `packages/api-contracts/src/contracts/platform.ts` 与
`packages/api-contracts/src/platform.openapi.json` 两个文件变更 ⇒ 其余 8 个服务的契约**逐字节不变**。

**PG 容器**：本车道两个 Testcontainers 类各自起一个 `postgres:16-alpine`，
surefire **顺序执行** ⇒ 同一时刻最多存活 **1** 个；两个类的 `@AfterAll` 都 `pg.stop()` ⇒
verify 结束前已由 ryuk / `@AfterAll` **回收**。

## 后续待办

1. **五个服务商的直连适配器**归 **18.2.2 卷级**，不在本车道。适配器到位后
   `/payments`、`/balances/query`、`/fees/sync` 与服务商侧汇率报价立刻变成真的，
   页面上的按钮也会按能力声明自动出现（现在**刻意不提供**必然 409 的按钮）。
   接入方式已经定好：实现 `PaymentProviderPort` 并注册成 bean 即覆盖内置的「未接入」实现。
2. **资金流水回写上游 12.2 / 12.7** 需要 finance 先提供**接受外部流水写入**的授权端点；
   在那之前本模块与 finance 的关系**只能是只读**（「一张事实表只有一个写入所有者」）。
3. **服务商侧汇率报价**（锁汇价，含点差）随第 1 条一起接。
4. 接入真实适配器时，`HttpPaymentChannelAdapter`（finance 的实现）是可参考的**协议形状**
   （`Idempotency-Key` 头 + `GET /payments/{idempotencyKey}` 查证 + 主机白名单 fail-closed），
   但**不要直接照抄**：finance 那一版是通用支付渠道，本模块的五个服务商协议各不相同，
   适配器**不猜**形状 —— 没有真实对接文档时编一个出来比留空更危险。
