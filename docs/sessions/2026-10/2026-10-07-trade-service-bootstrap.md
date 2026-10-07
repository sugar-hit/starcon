# 2026-10-07 trade 服务引导（09 订单履约的地基）

分支 `feat/bootstrap-trade-service`，基线 `integration/module-dev`（4ead1b7）。

## 为什么做这件事

卷 09「订单履约」的 27 个 L4 模块全部「未开发」，根因不是没人写，而是
**`services/trade` 根本不存在**：`backend/pom.xml` 只注册了
`libraries/tenancy` 与 `services/{identity,catalog,channel,warehouse}` 四个服务。
今天 22:28 派发的 9.3.2.2「FBA 与自发货选择」在依赖自检阶段就 BLOCK ——
蓝图点名的两个上游（7.1.1.1 可用量 / 2.1.1.2 能力矩阵）确实已交付，
但模块无处落脚，且卷 09 零交付，连兄弟模块 9.3.2.1「分仓规则」都没有先例可抄。

按 [ADR-0004](../../decisions/0004-java-spring-boot-microservices.md) 与蓝图 09 卷
「后端归属」，`trade` 是订单与售后单据的唯一所有者。

**本车道交付的是「解锁」，不是模块。做完卷 09 仍然是 0/27。**
`docs/product/capability-blueprint/module-progress.md` 台账一行未改 ——
本车道不对应任何 L4 模块。

## 参照先例

`git show 8c3fc96`（channel 服务引导）是本仓库唯一一次服务引导，做法是
**独立的一个大 commit**，而不是某个 L4 模块车道的副作用。本次照它的结构与命名风格来。

## 接通了哪些接线点

| 接线点 | 位置 | 说明 |
| --- | --- | --- |
| Maven 模块 | `backend/pom.xml` | 只加一行 `<module>services/trade</module>` |
| 服务 POM | `backend/services/trade/pom.xml` | 照抄 channel：jOOQ 显式 SQL + Flyway + springdoc + Testcontainers + `tenancy` |
| 启动类 | `com.starcon.trade.TradeApplication` | 刻意**不注册任何业务 Bean** |
| 租户上下文 | `com.starcon.trade.TradeTenancyConfiguration` | `TenantTransactions` 声明为 Bean |
| 数据源/端口 | `application.yml` | 端口 **8085**（接在 warehouse 8084 之后）；`${DB_*}` 全部走环境变量 |
| 迁移基线 | `db/migration/V1__baseline.sql` | 版本号从 **V1** 起（各服务独立迁移目录） |
| 数据库 | `deploy/docker/catalog-init/01-databases.sh` | `trade_app` role + `starcon_trade` 库 + `REVOKE/GRANT CONNECT`，与既有 4 个服务完全对称 |
| 编排 | `deploy/docker/docker-compose.catalog.yml` | `trade` 服务 + `migrate-trade`（tools profile） |
| 前端路由 | `apps/web/src/lib/api-proxy.ts` | `trade/` 分支 + `TRADE_URL` |

## 三个关键决策

### 1. `trade_tenancy_probe` 是**非业务表**，且只有引导车道知道这件事

服务接通了但一张表都没有时，「RLS 生效了」是一句没人验证过的话。
V1 因此建一张**最小表**让三条租户断言可以被真的跑起来。它不承载任何卷 09 语义
（没有订单号、没有 SKU 行、没有履约状态），迁移头部写明了这一点，
并显式要求**第一条 9.x 模块车道决定保留还是删除** ——
不要让它以「占位表」的身份长期留在生产 schema 里。

### 2. 刻意**不建** `set_tenant` / `clear_tenant` 之类的 SQL 函数

既有四个服务都没有建，绑定点只有 `TenantTransactions` 一处
（`set_config('app.current_tenant', ?, true)`，事务局部，提交即清）。
凭空加一个没人调用的函数，只会让人以为存在第二条绑定路径 ——
而那正是多租户最不能有的分叉。清理也不需要函数，事务结束 GUC 自动回到未设置。

### 3. `TradeTenancyConfiguration` 声明 `TenantTransactions` 为 Bean

其余服务都是在控制器构造函数里 `new TenantTransactions(...)`，因为它们每个都有端点。
trade 目前没有任何端点，照抄那种写法就没有人持有它 ——
租户隔离这一层会在第一条业务车道落地时被重新发明一遍。
这里用同一个类、同一个绑定语义，只是把持有位置从控制器换成配置类。

## api-proxy 白名单：**接通但不放行**

`trade/` 分支的 `pattern` 是 `/^$/`（只匹配空余串，即当前不放行任何子路径）。

理由是这个文件自己的纪律：「放行不该存在的路径比 404 更糟」。
卷 09 的 27 个模块一个都还没开发，先放行一堆还不存在的形状只会让界面**看起来已交付**。
第一条 9.x 模块车道落地时补对应子路径即可，不必再动 `prefix` / `env` / `fallback`。

同理**没有**新建 `apps/web/src/app/api/trade/[...path]/route.ts`：
在 `/^$/` 之下它只可能 404，先不假装这条路存在。

## 「伪造 tenant header 无效」这条断言的诚实边界

集成测试实跑的三条断言里，前两条与第三条（连接池归还不串租户）是**逐行硬证据**。
第二条「伪造租户上下文无效」在引导阶段**没有 HTTP 端点可打**，因此证明的是：

1. 事务外 `app.current_tenant` 从未设置，任何行都看不见（不存在可沿用的默认租户）；
2. 事务内绑定的值精确等于可信参数（来自 `TenantTransactions.run` 的 UUID 形参），
   提交后立刻失效；
3. 带伪造 `X-Tenant-Id` 的请求打到 `/trade/**` 一律 404 —— 那个头**没有任何消费者**，
   也就没有任何一次请求能让某个租户被绑进本服务。

第 3 条是**弱于** warehouse `InventoryAvailabilityIntegrationTest` 的
「带合法会话 + 伪造头 → 仍只看到会话租户的行」。
**第一条 9.x 端点车道必须把这条升级成后者**，测试里已留了对应的注释。

## 卷 09 第一条模块车道应该从哪开始

**建议 `9.1.1.1 订单列表`**（而不是 9.3 履约编排）。理由：

- 9.3.2.1 / 9.3.2.2 看起来是现成的下一步，但它们依赖**分仓规则**与
  **履约请求**这两块尚未存在的东西，先做会再次 BLOCK；
- 9.1.1.1 的上游是 2.1 平台同步（已交付）与订单主表，
  下游不阻塞任何已交付模块，是唯一能让后续 9.x 站在**真实数据**上的一条；
- 它同时会补齐这条车道欠下的 HTTP 边界：`AccessService`、租户解析，
  以及上面那条需要升级的伪造头断言。

## 留给后续车道

- **卷 09 全部 27 个 L4 模块**：本车道一行业务逻辑都没写。
- `trade_tenancy_probe` 的去留：见「决策 1」。
- 伪造头断言的 HTTP 升级：见「诚实边界」。
- `AccessService` 与 identity 集成：`TradeTenancyConfiguration` 已备好依赖，
  但没有端点就没有地方解析租户。
- 跨服务引用登记（`sku_ref` / `shop_ref` 之类）：刻意未建 ——
  建哪些引用是 9.x 的设计决定，不该由引导车道替它们猜。

## 验证

- `cd backend && ./mvnw verify`：见交付报告。
- `pnpm typecheck` / `pnpm lint` / `pnpm test` / `@starcon/web build`：见交付报告。

## 顺带发现：基线上 warehouse 有一个**真实**失败（不是 flake，也不属于本车道）

全量 `./mvnw verify` 在 `warehouse` 阶段红：

```
SellablePolicyIntegrationTest.断言三_连接池归还后不串租户
java.lang.AssertionError: Expected size: 1 but was: 0 in: []
  at SellablePolicyIntegrationTest.java:794
```

该断言在 `for (int i = 0; i < 20; i++)` 的交替读取里，期望租户 A 能看到自己那条
`EXCLUSIVE` 可售策略，实际是 0 条。

**它不是并发 flake**：隔离重跑（`./mvnw -pl services/warehouse -am test
-Dtest=SellablePolicyIntegrationTest`）同样稳定失败，17 例红 1 例。
那次隔离重跑的 reactor 只有 `backend` 父 POM + `tenancy` + `warehouse`，
**根本没有构建 `services/trade`**。

**也不是本车道造成的**：`git diff --stat 4ead1b7 HEAD -- backend/services/warehouse
backend/services/channel backend/services/catalog backend/services/identity backend/libraries`
输出为空 —— 本车道对这五个目录零改动。全量 verify 的中断点在 warehouse，
按 Maven 反应堆顺序 `trade` 排在 warehouse 之后，因此全量跑时 `trade` 根本没轮到执行。

最后修改该测试的提交是 `54c5d6c feat(warehouse): 7.2.2.1 可售量口径（共享池 + 缓冲的防超卖体检）`。
建议单独派车道复查该模块的租户可见性，本车道不代劳（不在本车道所有权范围内）。

## 文件清单

新增：
- `backend/services/trade/pom.xml`
- `backend/services/trade/src/main/java/com/starcon/trade/TradeApplication.java`
- `backend/services/trade/src/main/java/com/starcon/trade/TradeTenancyConfiguration.java`
- `backend/services/trade/src/main/resources/application.yml`
- `backend/services/trade/src/main/resources/db/migration/V1__baseline.sql`
- `backend/services/trade/src/test/java/com/starcon/trade/TradeTenancyIntegrationTest.java`

修改（共享文件，全部只做增量）：
- `backend/pom.xml`（+1 行）
- `deploy/docker/catalog-init/01-databases.sh`（+5 / -1）
- `deploy/docker/docker-compose.catalog.yml`（+36）
- `apps/web/src/lib/api-proxy.ts`（+11）