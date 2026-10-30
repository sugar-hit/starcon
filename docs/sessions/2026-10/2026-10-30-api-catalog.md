# 2026-10-30: 18.1.1.2 接口目录（platform 服务 · L3 18.1.1 API 管理第三格）

## 摘要

交付蓝图 18 卷 L3 18.1.1「API 管理」下的第三格 **18.1.1.2 接口目录**：对外开放 API 的目录与文档
（按业务域列出接口、参数、返回、限流说明、示例）+ 沙箱在线测试。

蓝图把本模块钉死在一句话上：**「接口契约由后端 spec 生成，文档不手写——手写文档必然与实现漂移」**。
所以本模块的核心不是「写一份接口文档」，而是**把 spec 变成目录**：

- **接口清单不入库** —— 它每次从各服务的**活** `/v3/api-docs` 真解析出来。库里只落「测试结果」。
- **业务域 = 对外路径的首段** —— 分组口径来自 spec 本身，不是本模块写死的词表。
- **沙箱真跑** —— 真发一次 HTTP，带调用方自己的会话，回真实状态码 + 响应摘要 + 真实耗时。

交付物：迁移 **V411**（一张表）、**8 条端点**、**1 个真实工作区页面**、
**34 例后端测试 + 23 例前端测试**。

蓝图的四条硬要求逐条落成**物理形态**：

| 蓝图原话 | 落成在哪 |
| --- | --- |
| **接口契约由后端 spec 生成，文档不手写** | `ApiSpecSource` 去九个服务的 `/v3/api-docs` 取活契约；`platform_api_sandbox_run` 里**没有**任何一张接口表 |
| **按业务域列出接口、参数、返回、限流说明、示例** | 域 = 路径首段；参数 / 返回取自 spec 的 `parameters` / `responses`；示例按 schema 生成并标注出处；限流说明真读 18.1.1.1 的配额与授权 |
| **支持在线测试（沙箱环境）** | `ApiSandboxExecutor` 真发一次请求，回真实响应；结论落 `platform_api_sandbox_run`（可复现、可追责） |
| **沙箱与生产数据隔离** | 只放行 GET / HEAD；写方法如实答 409 且**一次请求都不发出去** |

## 决策 / 结论

1. **🔴 spec 的来源选「活的 `/v3/api-docs`」，不选 `backend/target/openapi/*.openapi.json` 那份快照。**
   快照是各服务的契约测试在**构建阶段**导出的产物：运行时不一定存在，而且它比活着的 spec 旧。
   活着的 `/v3/api-docs` 是同一份契约的**运行时等价物**，且永远是当前代码的那一份 ——
   「文档与实现漂移」正是要防这件事。两者都由 springdoc 从同一批注解生成，**不是**两份真相源。

2. **🔴 接口清单不入库。**
   把接口清单存成一张表，等于把 spec 抄一份进数据库：spec 改了，库里那份不会跟着改，
   而运营看到的正是库里那份 —— 那是「手写文档必然与实现漂移」的另一种写法。
   只有**测试结果**是本模块自己产生的事实，所以只有它落库。

3. **🔴 沙箱只放行读方法，写请求 409 且一次都不发出去。**
   本仓**没有独立的沙箱部署**，写请求打出去就是打在**生产业务表**上 —— 一个「测试」按钮改了真单据，
   比不提供测试更糟。这不是半成品，是任务书明写的「沙箱必须与生产数据隔离」。
   写操作的正确用法是在 18.1.1.1 里用真实凭证对真实环境调用（那里有配额 / IP 白名单 / 审计兜着）。

4. **🔴 沙箱的目标路径与查询参数必须来自 spec。**
   路径要能匹配上 spec 里的**路径形状**（`{spuId}` 段是通配），查询参数的键必须是该操作声明过的
   query 参数，缺必填参数直接拒。这既是「接口目录」的意义（目录说的就是这些接口），
   也挡住了「拿测试接口当任意代理去探测内网」。

5. **🔴 限流说明挂在应用上，不挂在接口上。**
   配额是 18.1.1.1 的能力（`platform_app_quota` / `platform_app_scope`，**同一个服务、同一套库**，
   不是跨服务读取）。本模块如实说明「哪个应用、哪个窗口、限多少」，**不**替网关编一套按接口的限流。
   授权资源码与业务域的对应关系用一条**写明的约定**（「资源码 = 业务域码」）：18.1.1.1 的资源码是
   运营自由登记的文本，去猜「哪个资源码对应哪个接口」会让「这个应用能不能调这个接口」变成一句假话。

6. **🔴 示例必须标出处。**
   spec 自带示例（`SPEC`）与按 schema 生成的示例（`GENERATED_FROM_SCHEMA`）是**两回事**，
   页面把后者显示成黄色的「按 schema 生成（非官方示例）」。把生成的冒充官方契约，
   是「漂移」的另一种形态 —— 只是漂到了文档内部。

7. **🔴 读不到的服务必须显式摆出。**
   某个服务的 spec 读不出来时，它在目录里那一格标成 `UNAVAILABLE` 并写明原因，
   而不是**悄悄消失** —— 消失等于告诉接入方「它没有对外接口」，那是假的。
   页面同理：读不到的服务单列一条提示，**不混进空态**。

8. **🔴 沙箱失败用真实状态码回，不用「一律 200」。**
   「没发出去」有四种结论（COMPLETED / REFUSED_WRITE / INVALID_TARGET / UPSTREAM_UNREACHABLE），
   分别对应 200 / 409 / 400 / 503。一律 200 会让「被拒了」看起来像「跑通了」。
   上游真的回了 404 / 500 时，沙箱自己回 200 并把上游的真实状态码放进结果里 ——
   那次测试**成功执行了**，上游的状态码正是要看的结论。

9. **命名与权限码跟端点前缀逐字对齐。**
   端点命名空间 `/platform/api-catalog`，权限码 `platform.api-catalog.read / .test`
   （与 `platform.text` ↔ `/platform/text` 同一纪律）。中间那段的连字符是有意的：
   免得「端点叫 api-catalog、权限码叫 apicatalog」这种对不上的事发生。

10. **沙箱测试是**独立**的一个权限码。**
    能看接口文档的人不该顺手对生产发请求；`AccessService` 的判据要求同一 grant 里同时有
    该动作与 `.read`，所以能测试的人至少看得懂接口是什么。

## 关键 trade-off

- **多一次 HTTP 往返，换「文档永不漂移」。**
  目录页面一次要读九个服务的 spec（catalog 780 KB / channel 2.0 MB / trade 1.5 MB …），
  冷启动确实慢。取舍是：进程内 TTL 缓存 60 秒 + 显式超时（连接 3 秒 / 读 10 秒），
  换掉「接口清单落库、半年后与实现对不上」。目录是**给人看契约**的页面，不是高频热路径；
  真嫌慢可以调 `platform.api-catalog.spec-ttl-seconds`。
- **沙箱只读，牺牲了「在线测试写接口」。**
  另一半的取舍：本仓没有沙箱环境，**开一个写接口的沙箱 = 一个能改生产数据的测试按钮**。
  宁可少一半能力，也不给这个口子；能力声明里如实写「未接入，归 18.1」，端点如实答 409。
- **自己也在目录里（`platform` → 8089）。**
  开放平台自己的接口（`/platform/**`）同样是「对外开放 API」的一部分。只列别人的接口不列自己的，
  那不叫接口目录，叫别人的目录。代价是平台服务要 HTTP 自取一次自己的 spec（自调用，不进死锁）。

## 文件清单

**后端（platform 服务，Java）**

- `backend/services/platform/src/main/resources/db/migration/V411__api_catalog.sql` —— 一张表 `platform_api_sandbox_run`
- `backend/services/platform/src/main/java/com/starcon/platform/ApiCatalogModels.java` —— 22 个 record
- `backend/services/platform/src/main/java/com/starcon/platform/ApiSpecSource.java` —— 活 spec 端口 + TTL 缓存
- `backend/services/platform/src/main/java/com/starcon/platform/ApiSchemaReader.java` —— schema → 字段 / 示例
- `backend/services/platform/src/main/java/com/starcon/platform/ApiSandboxExecutor.java` —— 真发请求 + 路径/参数白名单
- `backend/services/platform/src/main/java/com/starcon/platform/ApiCatalogService.java` —— 服务层
- `backend/services/platform/src/main/java/com/starcon/platform/ApiCatalogController.java` —— 8 条端点
- `backend/services/platform/src/main/resources/application.yml` —— `platform.api-catalog.services` 服务登记表 + TTL

**后端（identity 服务，权限码）**

- `backend/services/identity/src/main/resources/db/migration/V411__platform_api_catalog_permission.sql`

**后端测试**

- `backend/services/platform/src/test/java/com/starcon/platform/ApiCatalogIntegrationTest.java`（22 例）
- `backend/services/platform/src/test/java/com/starcon/platform/ApiCatalogTenancyIntegrationTest.java`（7 例）
- `backend/services/platform/src/test/java/com/starcon/platform/ApiCatalogSchemaReaderTest.java`（5 例）

**前端**

- `apps/web/src/components/integrations/api-catalog-model.ts` —— 纯模型层
- `apps/web/src/components/integrations/api-catalog-model.test.ts`（18 例）
- `apps/web/src/components/integrations/api-catalog-workspace.tsx` —— 工作区
- `apps/web/src/app/integrations/api-catalog/page.tsx` —— 页面
- `apps/web/src/app/api/platform/api-catalog-allowlist.test.ts`（5 例）—— 白名单双向断言
- `apps/web/src/lib/api-proxy.ts` —— **追加**一条 `prefix: 'platform/'` 元素（数组末尾）

**契约 / 台账 / 会话**

- `packages/api-contracts/src/contracts/platform.ts` + `packages/api-contracts/src/platform.openapi.json` —— codegen 重生成
- `docs/product/capability-blueprint/module-progress.md` —— 18.1.1.2 那一行
- 本文件

## 验证

- 后端 L1：`./mvnw -pl services/platform -am test -Dtest='ApiCatalog*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → **34 例全绿**（22 + 7 + 5）
- 前端 L1：`pnpm vitest run apps/web/src/components/integrations/api-catalog-model.test.ts apps/web/src/app/api/platform/api-catalog-allowlist.test.ts`
  → **23 例全绿**
- 改了 `api-proxy.ts` ⇒ 全量 allowlist 回归：`pnpm vitest run apps/web/src/app/api`
  → **274 文件 / 1549 例全绿**（未误伤兄弟车道）
- `pnpm typecheck` → 全绿

**接手时就地修掉的两处真 bug**（不是测试写错，是主代码错了）

1. **`$ref` 解引用炸掉了整个接口详情**：`#/components/schemas/X` 是 **JSON Reference**，
   而 Jackson 的 `at()` 只吃 **JSON Pointer**（必须以 `/` 开头）—— 直接把引用丢给 `at()`
   会抛 `IllegalArgumentException`，表现是「接口详情整个打不开」（400），而不是「某个字段没显示」。
2. **`typeName` 先解引用再取组件名 ⇒ 组件名永远拿不到**：解引用之后 `$ref` 已经没了，
   类型名会退化成 `"object"`。现在**先看原始节点**有没有 `$ref`，有就显示组件名。

## 后续待办

- **🔴 18.1.1.3 调用监控**（同 L3 兄弟）：本模块只做「有哪些接口、怎么调」，
  不做「调了多少、有没有超限」。本车道**不依赖**它，也没有顺手实现它。
- **🔴 独立沙箱环境**：真正的沙箱（独立库 / 独立数据）落地后，`ApiSandboxExecutor` 的
  读方法白名单可以放开到写方法 —— 那时写请求打的是沙箱库，不是生产业务表。
  在那之前，写操作请走 18.1.1.1 的真实凭证 + 真实环境。
- **平台服务的 spec 自取**：目前 `platform` 那一格是 HTTP 自调用（自取 `/v3/api-docs`）。
  若将来平台服务的线程池调小，要改成进程内取（springdoc 的 `OpenAPIService`），
  否则自调用在高压下有占满线程池的风险。
