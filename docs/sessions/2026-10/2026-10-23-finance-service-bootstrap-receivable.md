# 2026-10-23 finance 服务引导 + 12.1.1.1 应收单管理

分支 `feat/12-1-1-1`，基线 `integration/module-dev`（7811eb54）。两个 atomic commit。

## 摘要

卷 12「财务核算」的 32 个 L4 模块**全部卡在同一个前置条件**上：本仓不存在
`backend/services/finance/`。蓝图卷首写死了 finance 是应收应付、收付款、对账、成本、
总账、发票税务的唯一所有者，没有这个服务，卷 12 一格都落不了地。

本车道先做**服务引导**（解锁 32 格），再在引导出来的服务里交付 **12.1.1.1 应收单管理**
（卷 12 第一格）。最终卷 12 进度 0/32 → 1/32。

## 为什么必须先引导服务

这不是「顺手搭个骨架」。卷 12 的每个模块都会撞同一堵墙：编排闸按
`docs/product/capability-blueprint/README.md` 的「后端归属」列检查服务是否存在。
引导服务的动作被拆成两个 commit 是有意的：

- **commit 1 `feat(finance): 服务引导`** —— 服务骨架 + 共享接线点 + `V338` 迁移 + 空契约。
  表结构先落，因为「服务连一个库都没有」时编排闸放行了也没地方落数据。
- **commit 2 `feat(12.1.1.1): 应收单管理`** —— 服务层 / 端点 / 页面 / 完整契约 / 台账 / 会话日志。

**为什么 commit 1 的租户断言只证明了一半**：断言 2「伪造 `X-Tenant-Id` 头无效」要有真实
HTTP 写入口才能证（头得真被送进去、真被忽略）。commit 1 阶段没有写入口，只能证明
「无可信租户上下文时写入被 RLS 拒」。commit 1 的正文里写明了这条边界，commit 2 补上
HTTP 版断言（伪造头双向各验一次，且行仍落在会话租户名下）。

## 服务引导取舍：照抄 crm 什么、刻意没抄什么

**逐字照抄 crm（不重写「简化版」）**：

| 照抄对象                  | 为什么照抄                                                                                               |
| ------------------------- | -------------------------------------------------------------------------------------------------------- |
| `AccessService.java`      | 权限码前缀换成 `finance.`，实现一字不改 —— 租户/权限解析是**安全边界**，重写一份「简化版」就是多一条分叉 |
| `ApiErrorHandler.java`    | 错误响应形状必须与既有服务一致，否则前端拿不到同样的 `message`                                           |
| `CrmTenancyConfiguration` | `TenantTransactions` 声明为 Bean，租户由 identity 按**已认证成员关系**解析，不信任客户端头               |
| `pom.xml` 依赖集          | jOOQ 显式 SQL + Flyway + springdoc + validation + Testcontainers + `tenancy`                             |
| `OpenApiContractTest`     | 契约一致性检查随服务一起长出来，而不是等第一条业务模块才发现 spec 漂了                                   |

**刻意没抄（六个）**：

1. **没抄 crm 的业务端点 / 知识库表**（`V99__crm_knowledge_base.sql` 那一族）。
   crm 的 `knowledge_base` 是 crm 自己的领域资产，finance 没有知识库。
2. **没抄 crm 的 60+ 张业务表**，只建本模块需要的 5 张。引导车道不替后续 31 格猜表结构。
3. **没抄 crm 的 `*Port.java` 一族**（`PlatformEmailPort` / `CustomerFactsPort` 等）。
   本车道只有一个真实跨服务依赖（读 trade 发货确认），所以只写一个 `TradeReceivablePort`，
   **不为不存在的依赖预留空 Port**。
4. **没抄 crm 的 `*RulesTest.java` / `*TestSupport.java` 抽法**（crm 有 `CrmTestSupport`）。
   finance 目前只有两个测试类，抽象层会在第三张表出现时才值回票价。
5. **没抄 crm 的 `Unavailable*Port` 空实现**（`UnavailablePlatformEmailPort` 等）。
   那是「上游没交付时也要有形状」的写法；本模块的上游（trade 发货确认）**已交付**，
   所以没有可抄的必要性。
6. **没抄 crm 的迁移版本起点**。crm 从 V99 起（跟着全局分配器走），finance 的迁移
   **从 V338 起** —— 版本号走全局唯一分配器，各服务迁移目录虽独立但版本号会撞，
   撞号会让对方的 Flyway 直接炸。

**也没抄 trade 的 `trade_tenancy_probe` 式最小探测表**：引导车道直接建了真实业务表
（`V338` 的 5 张），没有「探测表 + 后续模块决定删不删」这一步。

## 关键决策

### 1. 跨服务读发货确认 = Port + 可维护的来源列，两者都要

`TradeReceivablePort` 经 trade 的**授权 HTTP API** 读
`GET /trade/b2b-orders/receivables`（9.7.1.1 已交付）。**不跨库 SQL / 跨库 FK / 跨库视图**
—— 每个服务拥有自己的数据库。

落库时带三列溯源：`source='B2B_CREDIT_TERM'` / `source_system='trade'` /
`source_reference=上游单号`，幂等键 `UNIQUE(tenant_id, source, source_reference)`。

**为什么不二选一**：只留可维护列 ⇒ 金额靠人抄；只做实时读 ⇒ 上游不可用时历史应收消失。
两者都要，人工录入路径才能在 upstream 挂掉时继续用。

trade 侧两个形状细节（踩过才知道）：分页字段是 `items`（不是 `rows`）；
`b2b_receivable_line.line_amount` 是生成列 `round(quantity*unit_price,4)`，与单头金额一致
⇒ 导入可精确对账，对不上就**跳过并如实报出来**。

### 2. 应收确认政策是闸门，且不预置任何行

每个来源必须有**已启用**的 `finance_receivable_policy` 行，否则该来源一张应收都产生不了
（服务层 400，消息含「应收确认政策」）。

**政策表不预置任何行** —— 预置 `enabled=true` 等于替用户把会计政策定了。同理，
**成本算法与核销口径由用户选定**，本模块不替用户定会计政策。

### 3. 金额与状态全部派生，库里没有可手改的「已核销」标记

单头 `amount` 由触发器从明细求和；单头 `settled_amount` / `status` 由明细 `settled_amount`
派生；行 `settlement_status` 是生成列。测试直接写 `finance_receivable_line.settled_amount`
验证这条不变量成立 —— 因为**本模块不开放核销写端点**（核销属 12.2.1.2）。

### 4. 汇率快照留在单据上；无汇率时 `base_amount` 是 null 而不是 0

`exchange_rate` / `base_currency` 是普通列，`base_amount` 是生成列
`round(amount*exchange_rate,4)`。**按 0 折算会被读成「这笔应收折成 0」** ——
那是假的确定值，不是缺失。确认后快照列被触发器冻结。

### 5. 12.3.1 结算未交付 ⇒ 平台结算 / 平台代扣来源当前**不可产生**

DB 词表保留这两个来源枚举，但服务层**拒绝建单**并写明归属模块 `12.3.1.1`，
对应字段恒为 null，**绝不填 0、绝不造结算**；能力声明 `available=false`。
人工录入路径可用（自建单据是本模块自己的真入口，不是伪装成平台来源）。

对称地，B2B 账期来源**只允许从发货确认导入，不允许手工录入**（服务层拒）。

### 6. 争议中 = `dispute_flag` + 人工标注端点

11.4.1.1 纠纷列表**未与本模块自动联动**，这一点如实写进能力声明。
账龄分布**单列争议金额**，不混进正常桶。

### 7. 前端 api-proxy 新增**一条独立 services[] 元素**

prefix `finance/`、env `FINANCE_URL`、fallback `http://127.0.0.1:8087`，
插入锚点固定在 services[] **最末尾**（`default-context` 之后），**既有条目一字未改**
（该文件 diff = **+49/-0**）。并进既有 trade 那条巨型 alternation 会让多车道同时改同一行
⇒ git 必然冲突（结构性，retry 不会自愈）。

id 段用**完整 UUID 形状** `[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}`，
不是 `[0-9a-f-]+` —— 后者会把 `capabilities` 也当成 id 放行。
字面量子路径排在 `{uuid}` 段之前判别（alternation 首个命中即返回）。

刻意**不放行**：`{id}/settle`（核销 12.2.1.2）、`{id}/write-off`（坏账 12.1.1.3）、
`{id}/invoice`（发票 12.6.2.1）、`payments`、`settlements`、非 UUID 段 ——
放行后端不存在的映射比 404 更糟。

## 涉及文件

**新增（commit 1）**：

- `backend/services/finance/pom.xml`
- `backend/services/finance/src/main/java/com/starcon/finance/FinanceApplication.java`
- `backend/services/finance/src/main/java/com/starcon/finance/FinanceTenancyConfiguration.java`
- `backend/services/finance/src/main/java/com/starcon/finance/AccessService.java`
- `backend/services/finance/src/main/java/com/starcon/finance/ApiErrorHandler.java`
- `backend/services/finance/src/main/resources/application.yml`（端口 8087）
- `backend/services/finance/src/main/resources/db/migration/V338__finance_receivable.sql`
- `backend/services/finance/src/test/java/com/starcon/finance/OpenApiContractTest.java`
- `backend/services/finance/src/test/java/com/starcon/finance/FinanceTenancyIntegrationTest.java`
- `packages/api-contracts/src/contracts/finance.ts`（commit 1 时为空契约）

**修改（commit 1，共享文件只做增量）**：

- `backend/pom.xml`（+1 行 `<module>services/finance</module>`）
- `deploy/docker/catalog-init/01-databases.sh`（`finance_app` 角色 + `starcon_finance` 库 + REVOKE/GRANT CONNECT）
- `deploy/docker/docker-compose.catalog.yml`（`finance` 服务 + `migrate-finance`）
- `.github/workflows/ci.yml`（等待端口加 8087 + 失败诊断日志加 finance）
- `tools/codegen/catalog-contracts.py`（**`SERVICES` 加 `('finance', 8087)`** —— 必须与 finance 的 `OpenApiContractTest` 同一 commit，否则 `load_specs()` 无条件读 `backend/target/openapi/finance.openapi.json` 会直接炸）
- `packages/api-contracts/src/index.ts`（+1 行 `export * from './contracts/finance';`）

**新增（commit 2）**：

- `backend/services/finance/src/main/java/com/starcon/finance/ReceivableModels.java`
- `backend/services/finance/src/main/java/com/starcon/finance/ReceivableService.java`
- `backend/services/finance/src/main/java/com/starcon/finance/ReceivableController.java`
- `backend/services/finance/src/main/java/com/starcon/finance/TradeReceivablePort.java`
- `backend/services/finance/src/test/java/com/starcon/finance/ReceivableIntegrationTest.java`
- `apps/web/src/components/finance/receivable-model.ts`
- `apps/web/src/components/finance/receivable-model.test.ts`
- `apps/web/src/components/finance/receivable-workspace.tsx`
- `apps/web/src/app/api/finance/[...path]/route.ts`
- `apps/web/src/app/api/finance/receivables-allowlist.test.ts`

**修改（commit 2）**：

- `apps/web/src/app/finance/page.tsx`（替换 `DomainPlaceholder` 占位）
- `apps/web/src/lib/api-proxy.ts`（+49/-0，一条独立 services[] 元素）
- `apps/web/src/lib/api.ts`（+`{ prefix: 'finance/', route: 'finance' }`）
- `packages/ui/src/lib/domain-spec.ts`（finance `pageStatus: 'partial'` + 「应收单管理」SHIPPED 条目）
- `packages/api-contracts/src/contracts/finance.ts`（重新生成，26 个 `Finance*` 类型）
- `packages/api-contracts/src/finance.openapi.json`（重新生成）
- `backend/services/finance/src/test/java/com/starcon/finance/FinanceTenancyIntegrationTest.java`（补 HTTP 伪造头断言）
- `docs/product/capability-blueprint/module-progress.md`（**只改 12.1.1.1 一行的「状态」「证据」两格**）

## 关键 trade-off

| 选项                                    | 选哪个                                               | 代价                                                                 |
| --------------------------------------- | ---------------------------------------------------- | -------------------------------------------------------------------- |
| 迁移版本号 V1（各服务独立）vs V338      | **V338**（全局唯一分配器）                           | 服务间迁移目录虽独立，版本号仍会撞；撞号会让对方 Flyway 炸掉         |
| 跨服务读发货确认：实时读 vs 可维护列    | **两者都要**                                         | 落库要维护 `source` / `source_system` / `source_reference` 三列溯源  |
| 核销状态：服务层写端点 vs DB 派生       | **DB 派生**（本模块不开放核销写端点）                | 核销（12.2.1.2）交付时才能有真写入路径；测试只能直写行级列验证不变量 |
| 应收确认政策：预置 enabled 行 vs 不预置 | **不预置**                                           | 用户第一次用要先配政策才能建单 —— 这是有意的，不是缺陷               |
| 无汇率时 `base_amount`：0 vs null       | **null**                                             | 汇总里不能直接当 0 累加；按 0 折算会被读成「这笔应收折成 0」         |
| 平台结算来源：先留空 vs 直接不做        | **词表留、不可产生**（服务层拒 + 写明归属 12.3.1.1） | 词表里有两个当前用不了的枚举值，需要能力声明与错误消息共同说清楚     |
| api-proxy：并进 trade 长正则 vs 独立条  | **独立 services[] 元素**                             | 每个服务多一条正则，但多车道并行时 git 不冲突                        |

## 后续待办

- **卷 12 剩余 31 格**（本车道只交付 1/32）：12.1.1.2 应收账龄（本模块已有账龄端点，
  但账龄作为独立模块的交付口径与筛选维度待定）、12.1.1.3 坏账与核销、12.1.2.x 应付单 /
  付款计划 / 供应商对账、12.2.1.x 收款单、12.3.1 结算、12.4.x 成本、12.5.x 总账、
  12.6.x 发票与税务。
- **核销写路径**（12.2.1.2）：本模块已把单头状态做成明细派生态，12.2.1.2 交付时直接写
  行级 `settled_amount` 即可，不需要改本模块的表结构。
- **坏账 / 核销的权限码**：`finance.receivable.*` 之外另立，不要复用。
- **11.4.1.1 纠纷与应收的自动联动**：本模块当前只有人工标注端点，
  自动联动归属哪个车道待定。
- **12.3.1 平台结算 / 平台代扣来源**：交付后需要在本模块服务层放开建单，
  并把 `available=false` 的能力声明改成真实可用。

## 验证

- L0：`./mvnw -q compile` EXIT=0。
- L1（只跑本车道相关）：`./mvnw -pl services/finance -am test
-Dtest='Finance*,Receivable*,OpenApiContractTest' -Dsurefire.failIfNoSpecifiedTests=false
-DfailIfNoSpecifiedTests=false` ⇒ **Tests run: 37, Failures: 0, Errors: 0** BUILD SUCCESS
  （FinanceTenancyIntegrationTest 5 + ReceivableIntegrationTest 31 + OpenApiContractTest 1）。
- 前端全量 allowlist 回归（改了共享白名单 `api-proxy.ts` 必须跑全量）：
  `pnpm vitest run apps/web/src/app/api apps/web/src/lib/api-proxy.test.ts
apps/web/src/lib/api-proxy-union.test.ts` ⇒ **211 文件 / 1454 例全绿**。
- 本车道前端单测：`pnpm vitest run apps/web/src/components/finance
apps/web/src/app/api/finance` ⇒ **2 文件 / 18 例全绿**。
- `pnpm typecheck` ⇒ 3/3 successful；`pnpm lint` ⇒ 3/3 successful（仅既有 warning）。
- 契约：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
  重生成（**codegen 产物未手改**）⇒ finance 契约 26 个 `Finance*` 类型，
  既有六服务契约**零变化**。

**本机环境注意**：JDK 25 与 google-java-format 1.22 不兼容 ⇒ Maven 必须
`export JAVA_HOME=/Users/guan/Library/Java/JavaVirtualMachines/openjdk-21/Contents/Home`。

**Testcontainers 容器观察**：L1 期间共享 Docker daemon 上同时存活 7 个 `postgres:16-alpine`，
跑完后 `docker ps -a --filter label=org.testcontainers=true` **无任何 Exited 残留**
⇒ ryuk 在正常回收；但**存活容器数在本车道测试结束后仍继续增长**（7 → 10，且新容器名
出现在我方 BUILD SUCCESS 之后），取证到并发 maven 进程 PID 14428 的工作目录是
`/Users/guan/Projects/starcon-worktrees/integration/backend`（**集成头，不是本 worktree**）
⇒ 那批容器属于**其它车道**的并发测试，不是本车道泄漏。

## 关联

- [ADR-0004 Java Spring Boot 微服务](../../decisions/0004-java-spring-boot-microservices.md)
- [ADR-0002 多租户策略](../../decisions/0002-multi-tenancy-strategy.md)
- [多租户架构说明](../../architecture/multi-tenancy.md)
- [台账：12.1.1.1 应收单管理](../../product/capability-blueprint/module-progress.md)
- [trade 服务引导先例](../2026-10/2026-10-07-trade-service-bootstrap.md)
- [商品中心本地开发（跑后端）](../../operations/catalog-local-development.md)
