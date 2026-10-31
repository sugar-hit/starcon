# 18.2.3.1 其他对接（卷 18 集成开放与智能自动化 → L3 18.2.3 其他对接 → 第一格）

> **本车道经历了三次会话。** 前两任 worker 的会话都因**网络错误**
> （`net::ERR_NETWORK_CHANGED` / `net::ERR_HTTP2_PING_FAILED`）在写代码途中被中断：
> 第一任整段工作丢失，第二任留下了 18 个未提交文件但测试没跑绿。
> **本篇（第三任）接手了前两任留在磁盘上的产出**，逐个文件复核后就地修掉遗留缺陷、
> 补齐前端与契约、跑绿全部验证并收口。代码主体是前两任写的，收口与取舍是本任做的。

## 决策摘要

交付蓝图 18.2.3.1「其他对接」的**三项**点名能力（统一连接配置管理 / 同步任务配置 /
连接状态 + 同步记录），外加把「每个对接都有独立连接与独立健康状态」这条加粗句
**在数据层做成物理事实**。

- **迁移**：`V418__external_integration.sql`（platform，三表）+ `V418__platform_external_permission.sql`
  （identity，四个权限码）。版本号由调度者预分配，不自行找最大号。
- **端点**：19 条挂 `/platform/external-integrations`，jOOQ 显式 SQL。
- **适配层**：`ExternalIntegrationPort` + `AbstractUnavailableExternalIntegrationAdapter`
  + 六个逐外部系统实现 + `ExternalIntegrationRegistry`。
- **页面**：`/integrations/connectors/external-integrations`，五个页签。
- **契约**：`packages/api-contracts` codegen 重生成（其余 8 个服务逐字节未变）。

🔴 **诚实模式**：本仓**没有** 1688 / 淘宝 / 海外仓 / 物流商 / 聚合认证 / 自建 BI 的任何
真实客户端，也没有任何真实凭据与网络通道 ⇒ 六个适配器**逐个如实答「未接入」**，
直连同步端点如实答 **409**、不写成功记录、不写外部系统侧的单号，
适配器**不猜**各外部系统的接口形状（路径 / 字段名 / 签名方式）——
没有真实对接文档时编一个出来比留空更危险。

## 关键 trade-off

### 1. 「本地台账成功」≠「外部侧已登记」—— 两个字段刻意分开

同步记录台账**照写**（这是本系统自己的账：谁在什么时候对哪个对接发起了一次同步、
外部侧结论是什么）。但 `provider_notified` 与 `status` 是**两个分开的字段**，
DB 层 CHECK 钉死「DIRECT + SUCCEEDED ⇒ `provider_notified=true`」；
「外部侧已完成」的**人工登记**行永远是 `source=EXTERNAL` + `provider_notified=false`
+ 写明「本系统未与外部系统核对过」。

⇒ **一条 `status=SUCCEEDED 且 provider_notified=false` 的行不可能被误读成
「外部侧已登记过」**。页面上人工登记的行永远带着「未与外部系统核对过」这一格。

### 2. 健康判定：**记录优先**，不是通道优先

前两任留下的 `computeHealth` 是「适配器未接入 ⇒ NOT_CONNECTED 优先于一切」，
但前两任**自己写的集成测试**断言的恰恰是相反的结果（人工登记成功后 ⇒ HEALTHY），
单元测试与集成测试**彼此矛盾**。

本任按两条证据取**记录优先**：

1. 模块**自身的能力声明**写着「外部系统那边跑完同步，在这里补一条记录，
   **这个对接**的健康状态就如实反映现状」—— 若改成通道优先，
   本模块唯一「今天就能用」的那条路径会永远显示成「未接入」，能力声明就成了空话；
2. 「有没有与外部系统核对过」由 `provider_notified` 这一**独立字段**承担，
   **不混进健康状态**：人工登记的成功是「外部侧确实成功了，但我们没去核对」。

「有没有记录」时才去看通道：适配器不通 ⇒ `NOT_CONNECTED`，适配器通 ⇒ `UNKNOWN`
（「没跑过」≠「坏了」）。

### 3. 落库的健康列与算出来的结论**必须用同一套规则**

前两任在 INSERT 里写死了 `health_status='UNKNOWN'`，而 `computeHealth` 算出来是
`NOT_CONNECTED` ⇒ 概览里「健康 / 亚健康 / 不健康 / 未接入」各数与端点看到的**不一致**。
本任改成新建连接时**用同一套规则算初始健康**、改配置后**只**重算**这一个连接**，
两处口径一致，概览的数不再说谎。

### 4. 独立健康状态是**物理事实**，不是约定

- `health_status` 是**连接自己那一行**的列（写 A 的健康在物理上改不到 B 的行）；
- 健康重算的 SQL **必带 `connection_id` 过滤**（别的连接的记录进不来）；
- `UNIQUE(tenant_id, connection_id, task_type)` 保证两个对接的任务互不覆盖；
- **刻意没有「全局集成健康」这一列** —— 一旦有它，「一个对接挂了」就会显示成
  「全部都不健康」。整体状态只能在读的时候**逐条**列出来（页面上是逐档的数，
  不是一格「整体健康」）。

### 5. 密钥只存引用

沿用 18.1.1.1 应用凭证与 18.2.2.1 的纪律：`credential_ref` 存密钥管理器的**引用 ID**、
`credential_ref_lookup` 存**引用**的 SHA-256 摘要（只用于按引用定位这一行）、
`credential_hint` 存末四位提示。**库里没有任何密钥明文**。
测试用 information_schema 断言三张表**没有**任何 secret / password / api_key / token 列，
并额外钉死带「credential」的列只允许那三件套。

### 6. 页面**不提供**必然 409 的「发起同步」按钮

与 18.2.1.1 / 18.2.2.1 同一纪律：一个点了必然失败的按钮会让运营以为「点一下就能同步」。
按钮**只在适配器 `connected === true` 时**出现（今天六个都是 false ⇒ 不出现）。
与外部系统有没有直连通道，**不影响**连接配置、同步任务配置、独立健康状态、
同步记录台账，以及「外部侧已完成」的**人工登记**（今天就能用）。

### 7. api-proxy.ts：**纯增量**，锚点在数组末尾

新增**一条独立** `services[]` 元素，首 token `external-integrations`
（与同服务既有 9 支全不相同 ⇒ 天然互斥，既有那几支一个字都不用动）。
插入锚点：**数组末尾**（收尾 `];` 之前）—— 同批另一条车道（18.1.3.2）被要求插在
「数组最开头」，两个 hunk 各自独立，并入时能干净求并。
只放行**后端真实实现**的 16 条路径；刻意不放行裸路径 / delete / schema / not-a-uuid /
secret-keys / not-a-shape。**零命中核对是实测的**：用 TypeScript 编译器解析
`services` 变量的真实数组字面量，按 `resolve()` 的同构口径逐条判定。

### 8. 台账与契约是**共用**的，只碰自己那一格 / 自己那一份

- 台账 `docs/product/capability-blueprint/module-progress.md`：**只改 18.2.3.1 这一行**
  的「状态」「证据」两格，其余行一字未动、不重排；
- 契约 `packages/api-contracts`：**codegen 产物，禁止手改** —— 跑
  `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`，
  确认**其余 8 个服务的契约逐字节未变**且**复跑逐字节一致**。

## 接手时修掉的五处遗留缺陷

前两任留下的 18 个文件（迁移 ×2 + 主代码 ×14 + 测试 ×4）质量不错、主体可用，
但有五处**跑绿之前必须修掉**的问题：

1. **详情查询漏了 count 子查询**（`connectionRow`）⇒ jOOQ `Record.get("task_count")`
   抛 `IllegalArgumentException` ⇒ 落进 `ApiErrorHandler.invalid` ⇒ **400**，
   表现为「POST /connections 必然 400」。列表与详情口径也因此不一致。
2. **租户夹具的三条 insert 占位符错位**：同步记录那条 insert 有 12 个绑定对 11 个占位符
   （`external_ref` 的字面量里带了一个 `?` 却按占位符传参）⇒ 参数整体错位一格 ⇒
   `created_by`（uuid 列）收到一个**时间戳** ⇒ PG 报
   `invalid input syntax for type uuid`。另两处的 `created_by` 绑 null 被当 varchar ⇒
   `column created_by is of type uuid but expression is of type character varying`。
3. **「库里没有任何密钥明文列」的禁止名单把 `credential_ref` 也禁掉了** ——
   `credential_ref` 存的是**引用**不是密钥本体。改成按口径禁
   secret / password / api_key / token，另**正面**钉死带「credential」的列只允许三件套。
4. **「停用连接」的用例停用的其实是任务**（却断言连接被停用）⇒ 改成停用**连接**，
   并补一条「停用任务不动连接」。
5. **时间断言把会话时区的偏移当成了被测值**（`getOffset()` 期望 UTC，
   实际是 +08:00）—— `timestamptz` 存的是**时刻**、偏移量由会话时区决定 ⇒
   改成「UTC 归一化后逐字段比」，仍然能抓住 `Timestamp.valueOf(LocalDateTime…)`
   那种静默偏 −28800s 的 bug。

另外**错误处理器未被改动**：接手时 `git diff integration/module-dev` 为**空**，
上一任虽在会话里说过要「恢复错误处理器」，但磁盘上的 `ApiErrorHandler.java`
与基线**逐字相同**，无需恢复（本任核实过，不是凭会话描述推断）。

## 文件清单

### 迁移

- `backend/services/platform/src/main/resources/db/migration/V418__external_integration.sql`
- `backend/services/identity/src/main/resources/db/migration/V418__platform_external_permission.sql`

### 后端主代码（`backend/services/platform/src/main/java/com/starcon/platform/`）

- `ExternalIntegrationModels.java` — 传输模型（`External*` 前缀，避免与 channel / trade / warehouse 撞名）
- `ExternalIntegrationPort.java` — 外部系统适配器的**唯一边界**
- `AbstractUnavailableExternalIntegrationAdapter.java` — 当前实现基类（逐个如实答「未接入」）
- `Alibaba1688IntegrationAdapter.java` / `TaobaoIntegrationAdapter.java` /
  `OverseasWarehouseIntegrationAdapter.java` / `LogisticsCarrierIntegrationAdapter.java` /
  `CertificationAggregatorIntegrationAdapter.java` / `SelfHostedBiIntegrationAdapter.java`
- `ExternalIntegrationRegistry.java` — 适配器代码 → 实现（接入一个外部系统只需注册一个 bean）
- `ChannelPlatformPort.java` — 上游 2 渠道机制**只读**端口
- `ExternalIntegrationService.java` — 服务层（jOOQ 显式 SQL）
- `ExternalIntegrationController.java` — 19 条端点

### 后端测试（`backend/services/platform/src/test/java/com/starcon/platform/`）

- `ExternalIntegrationIntegrationTest.java`（21 例）
- `ExternalIntegrationTenancyIntegrationTest.java`（7 例）
- `ExternalIntegrationAdapterTest.java`（4 例）
- `ExternalIntegrationHealthRulesTest.java`（7 例）

### 前端

- `apps/web/src/components/integrations/external-integration-model.ts` — 模型（类型全部从契约派生）
- `apps/web/src/components/integrations/external-integration-model.test.ts`（20 例）
- `apps/web/src/components/integrations/external-integration-workspace.tsx` — 工作区
- `apps/web/src/app/integrations/connectors/external-integrations/page.tsx` — 页面入口
- `apps/web/src/app/api/platform/external-integration-allowlist.test.ts`（5 例）— api-proxy 双向守卫
- `apps/web/src/lib/api-proxy.ts` — **纯增量**一条 `services[]` 元素（共享文件）

### 契约与台账

- `packages/api-contracts/src/contracts/platform.ts` + `packages/api-contracts/src/platform.openapi.json`
  （codegen 产物，**禁止手改**）
- `docs/product/capability-blueprint/module-progress.md` — **只改 18.2.3.1 那一行**

## 验证

```
# L0 编译闸
cd backend && ./mvnw -q compile                        ⇒ 通过
pnpm typecheck                                          ⇒ 3 个包全绿

# L1 模块闸（只跑本车道新增的 4 个测试类）
cd backend && ./mvnw -pl services/platform -am test \
  -Dtest='ExternalIntegration*' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
⇒ Tests run: 39, Failures: 0, Errors: 0, Skipped: 0
  （ExternalIntegrationIntegrationTest 21 + ExternalIntegrationTenancyIntegrationTest 7
    + ExternalIntegrationAdapterTest 4 + ExternalIntegrationHealthRulesTest 7）

# 契约
cd backend && ./mvnw test -Dtest='OpenApiContractTest' -Dsurefire.failIfNoSpecifiedTests=false
⇒ 9 个服务各 1 例全绿，导出 backend/target/openapi/*.openapi.json
python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi
⇒ Generated 7612 DTOs，退出码 0；其余 8 个服务的契约**逐字节未变**；复跑**逐字节一致**

# 前端
pnpm vitest run apps/web/src/components/integrations/external-integration-model.test.ts
⇒ 20 passed
pnpm vitest run apps/web/src/app/api/platform/external-integration-allowlist.test.ts
⇒ 5 passed
pnpm vitest run apps/web/src/app/api/platform ⇒ 10 files / 53 tests passed
pnpm vitest run apps/web/src/app/api/trade（改了共享的 api-proxy.ts ⇒ 全量 allowlist 回归）
⇒ 71 files / 394 tests passed

# 格式 / lint（只查本次改动过的文件）
pnpm exec prettier --check <5 个前端文件>   ⇒ 全绿
pnpm exec eslint <4 个新增前端文件>         ⇒ 无输出
```

多租户三条断言齐全（真 Testcontainers PostgreSQL 16，FORCE RLS，连接池压到 1 条）：

1. **两租户互不可见** —— 列表 + **按主键直查** + 事务外零行；
2. **伪造租户上下文无效** —— 伪造的 `X-Tenant-Id` 头改不动会话租户，行落在
   **会话租户**名下（**不只断言「看不见」**：一句「写入被全表拒掉」也能骗过那个断言）；
3. **连接池归还后不串租户** —— 固定 1 条连接交替读写 5 轮。

另断言：三张表 `relforcerowsecurity` 为真、`tenant_id` 均 NOT NULL、`platform_app` 真的能读；
三张表**没有**任何密钥明文列；同步记录 UPDATE / DELETE 被触发器拒绝且行未变；
**未接入不许写成功记录**（两个租户名下都只有「人工登记的 EXTERNAL」+「如实标注的 NOT_CONNECTED」）。

## 后续待办

1. **六个外部系统的直连适配器**归 **18.2.3 卷级**，不在本车道 ——
   适配器到位后 `POST /sync-tasks/{id}/run` 立刻变成真的，
   页面上的「发起同步」按钮也会按能力声明自动出现（今天不出现）。
2. **上游店铺绑定**目前是「只读引用」（平台代码 + 店铺 id 两个字段），
   若将来要在界面上做「按店铺筛对接」，需要 channel 侧提供更细的只读端点。
3. `platform.external.*` 四个权限码已登记并给存量 OWNER 补权；
   若将来要区分「只能登记配置」与「能发起同步」的角色，权限码已分开，不需要再改迁移。
