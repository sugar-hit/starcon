# trade 补货建议：上游 404 ⇒ 503，且**只在整模块连跑时**出现

发现：2026-10-22 11:04（batch-62 的 L2 重跑）。发现人：批次调度者。
影响范围：**模块 6.2.1.2 补货建议**（trade 服务）的
`ReplenishmentSuggestionTenancyIntegrationTest` 三个租户断言，
以及**每一个批次 L2**（后端段在 trade 中止 ⇒ 前端四段全部不执行）。

## 现象

```
[ERROR] ReplenishmentSuggestionTenancyIntegrationTest.断言1_两租户互不可见:410->compute:339 Status expected:<200> but was:<503>
[ERROR] ReplenishmentSuggestionTenancyIntegrationTest.断言2_伪造租户上下文无效:460->compute:339 Status expected:<200> but was:<503>
[ERROR] ReplenishmentSuggestionTenancyIntegrationTest.断言3_连接池归还后不串租户:522->compute:339 Status expected:<200> but was:<503>
[ERROR] Tests run: 2380, Failures: 3, Errors: 0, Skipped: 0      （trade 模块）
[ERROR] MVN_EXIT=1
```

服务端 503 消息（从 surefire 报告逐字读出）：

> 商品主数据服务返回 **404**：MOQ / 箱规 / 交期 / 价格的事实来源是商品主数据服务的授权只读接口
> （3.6.2.1 报价与 3.1.2.4 单位换算，跨库视图与跨服务 SQL 均被 AGENTS.md 禁止），
> 本服务不会凭空生成一份报价或箱规出来算建议量

## 与本批的关系：**无关**

`git log da1f96f9..HEAD -- backend/services/trade/` **为空**
⇒ trade 目录与上一个绿批次 `da1f96f9`（batch-61）**逐字节相同**。
本批 4 条车道全在 **crm** 服务，crm 迁移是纯 `CREATE TABLE`（零 ALTER 既有表、零跨库 FK），
**不碰 trade 的任何代码或迁移**。

⇒ 这是**既有缺陷被暴露**。上一轮 L2 在 warehouse 中止（trade 被 SKIPPED），
trade 至今**没在批次 L2 里跑过一次**；这一轮第一次跑到，就红了。
⇒ **不要去二分本批的车道。**

## 失败时的 reactor 位置

```
backend ✅ → tenancy ✅ → identity ✅ → catalog ✅ → channel ✅
→ warehouse ✅（本轮新绿：10.4.1.2 的墙钟缺陷已修）→ trade 🔴 → crm ⏸ SKIPPED（Maven fail-fast）
```

⇒ **warehouse 的墙钟缺陷已确认修好**（同一段本轮全绿）；
⇒ 但 **crm 段连续第三轮被 fail-fast 跳过** ⇒ **本批 4 条 crm 模块的后端测试至今没在任何一次批次 L2 里跑过**，
前端四段也一次都没执行过 ⇒ **全量仍未走完 ⇒ 不 push**。

## 已实测的事实（可直接采信）

| # | 事实 | 怎么验的 |
| --- | --- | --- |
| 1 | 该类**单独跑 4/4 绿** | `./mvnw -pl services/trade -am test -Dtest='ReplenishmentSuggestionTenancyIntegrationTest'` ⇒ BUILD SUCCESS |
| 2 | 与同门类一起跑 **45 例绿** | `-Dtest='ReplenishmentSuggestion*'` ⇒ BUILD SUCCESS |
| 3 | 与紧邻前一个类一起跑 **46 例绿** | `-Dtest='DropshipMarginIntegrationTest,ReplenishmentSuggestionTenancyIntegrationTest'` ⇒ BUILD SUCCESS |
| 4 | 失败是**立刻**失败（单条 0.03–0.09 秒） | surefire 报告耗时 ⇒ **不是** 8 秒读超时 |
| 5 | 404 只能来自「请求路径没匹配上」 | 测试的 catalog 桩用 `com.sun.net.httpserver.HttpServer`，其 handler **从不返回 404**（只有 401/200）；`404 No context found for request` 是 HttpServer 对**无对应 context 的路径**的固定应答 |
| 6 | **双斜杠假设已证伪** | 独立 Java 程序实测：`UriComponentsBuilder.fromUriString("http://localhost:12345").path("/catalog/supplier-quotes/effective")` ⇒ **单斜杠** `http://localhost:12345/catalog/supplier-quotes/effective`；而 `//catalog/...`（双斜杠）确实会被裸 HttpServer 回 **404 No context found**（同一程序实测） |
| 7 | 失败那一刻紧邻的类是 `DropshipMarginIntegrationTest` | 按 surefire 报告落盘时间倒推：`PurchaseFollowupIntegrationTest`(10:50:52) → `OrderConsolidationIntegrationTest`(10:50:59) → `DealerChannelIntegrationTest`(10:51:03) → **`DropshipMarginIntegrationTest`(10:51:12)** → Tenancy 类 10:51:12 启动并失败 |

## 两个高危结构性事实

1. **19 处上游端口的默认值是「机器全局固定地址」**：
   trade 主代码里 `@Value("${CATALOG_URL:http://127.0.0.1:8082}")` 这类写法有 **19 处**，
   而 **8082 正是本机 catalog 服务的真实端口**
   （`deploy/docker/docker-compose.catalog.yml` 映射 `127.0.0.1:8082:8082`）。
   ⇒ 只要某个测试类**没显式给** `CATALOG_URL`，而它又成了「这个上下文的上游地址来源」，
   应用就会去敲**机器上那个固定端口**而不是自己的桩 ⇒ 行为取决于**当时机器上跑着什么**。
2. **116 个 trade 测试类的 `@DynamicPropertySource` 方法同名 `properties`**：
   Spring 7 的 `DynamicPropertiesContextCustomizer` 持有 `Set<Method>`（按**反射 Method 对象**比较，
   不同类 ⇒ 不同缓存键 ⇒ 上下文**应当**不跨类共享）—— 但**这一点必须在实跑里验证**，
   不能靠「读源码得出的结论」当作既成事实。

## 处置

已派**修复车道** `6.2.1.2`（分支 `fix/6-2-1-2-trade-catalog-upstream`，
worktree `.worktrees/6.2.1.2-fix`，任务书 `.dev-agent/taskbooks/6.2.1.2-fix.md`）：

- **先在整模块语境下复现**（单独跑绿不算数 —— 事实 #1 已经证明单独跑是绿的）；
- 把「**实际请求路径 + 实际目标端口**」钉死（可在桩侧临时打印 `e.getRequestURI()` 与端口）；
- **最小且忠实**地修：客户端拼装不对就修拼装；桩与真实服务行为不一致（真实 Tomcat 会归一化 `//`，
  裸 HttpServer 不会）就让桩忠实并写注释；默认值指向机器全局地址就让测试**永远显式**给上游地址，
  **不许靠「机器上没跑 catalog 服务」这种运气**；
- **禁止**放宽/删除断言、`@Disabled`、`assumeTrue`、跳过测试、减少测试例数、改领域规则、
  动别人车道（crm / warehouse / channel / catalog / identity）的文件；
- 修好后**整模块 trade 全量跑**且例数不减（当前 2380），并补一条能钉住这个缺陷的回归用例。

## ⚠️ 给下一个 tick 的提醒

- **别把这次红归因到最近并入的 4 条 crm 车道**：trade 与绿批次逐字节相同。
- **别用「单独跑一遍是绿的」来否定这个缺陷**：事实 #1 就是单独跑绿、整模块跑红。
- 修好后**必须重跑整个 reactor 的 L2**（后端 → typecheck/lint/test → Next build → sync --check），
  因为前几轮的前端四段一次都没执行过。
