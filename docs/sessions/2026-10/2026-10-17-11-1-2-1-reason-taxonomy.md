# 11.1.2.1 售后原因分类（阶段 D4 · trade 服务）

- **日期**：2026-10-17
- **车道**：`feat/11-1-2-1`（worktree `.worktrees/11.1.2.1`）
- **性质**：**接手被中断的车道**。上一任 worker 在 18:27 因 Token Plan 配额耗尽
  （errorCode 42212）被强制杀死，留下一份真实但**从未验证**的代码：分支上零提交，
  L0 / L1 一次都没跑过。本轮的任务不是重写，是把它变成「已验证、可落地」的车道。

## 决策摘要

1. **先抢救，再验证**。`git add -A` + 一条 `wip(...)` 提交把成果从「只在工作区」变成
   「在分支上」。这一层不带任何测试证据，只保证可恢复，不代表交付。
2. **契约重新生成的顺序不能反**。工作区里 `backend/target/openapi/trade.openapi.json`
   是上一任 worker 留下的旧快照（18:23）。集成头此后已前进 8 个提交，直接拿旧 spec
   生成契约会把批次 18 已交付的端点悄悄删掉。必须**先**跑 `OpenApiContractTest`
   从并入后的代码刷新 spec，**再** `pnpm contracts:generate --spec-dir backend/target/openapi`。
3. **不自己 merge 集成分支**。`land` 只校验工作区干净 + 有新提交，并入由调度器做。

## 关键 trade-off

### 1. `api-proxy.ts` 用新增元素，不并进既有 alternation

本仓 `api-proxy.ts` 里 trade 分支是一段**超长单行正则**，多车道同时往里并分支时
git 必然判冲突（结构性，retry 不会自愈）。本模块与并入的 11.1.1.2 都改为**新增一条独立
`services[]` 元素**，`resolve()` 是「按顺序逐条匹配、首个命中即返回」，靠**形状互斥**
而不是靠顺序正确来保证安全：

| 元素 | 管的路由 |
| --- | --- |
| 11.1.1.1 | `aftersales/(capabilities\|filters\|cases\|cases/bulk)` |
| 11.1.1.2 | `aftersales/case-detail/capabilities`、`aftersales/cases/{uuid}…` |
| **11.1.2.1（本模块）** | `aftersales/reasons/(capabilities\|tree\|categories\|categories/{uuid}\|attributions\|mappings\|mappings/{uuid}\|reports)` |

代价是文件里 trade 元素多了几条；收益是并入零冲突 + 有测试钉住互不吃。

### 2. 「删不掉」是口径完整性要求，不是缺陷

被归因引用过 / 被映射引用过 / 还有子分类的分类一律**不许删**，只能改状态停用。
停用 = 不再可被新归因选中，而历史归因仍指向它，两个口径都还在。
删掉会让 11.5.2 的历史归因指向一个不存在的分类，退货率分析的分母就变了。

### 3. 契约的可空性以**运行时语义**为准，不是以「建表时必填」为准

见下方缺陷 (3)。`AftersalesReasonMappingCreate.categoryId` 同时服务 POST 与 PATCH 两条路，
POST 必填而 PATCH「传了才改」。取**并集**就是可空；POST 侧的必填性由服务端校验强制。

## 过程中修掉的真实缺陷

上一任 worker 从未跑过任何测试，**L0 第一次真跑就暴露了两个**：

| # | 现象 | 根因 |
| --- | --- | --- |
| (1) | `TURBO_FORCE=true pnpm typecheck` 报 TS2552：`Cannot find name 'updateReasonMapping'` | workspace 第 313 行调用它，但 import 列表里漏了 —— 该函数在 model 第 122 行**确实导出**，漏的是 import。缺导入 ⇒ tsc 非零退出 ⇒ 这一页整体编译不出来，「启用/停用映射」按钮根本不存在 |
| (2) | TS2552：`Cannot find name 'setAttributionMessage'` | 实际声明的 setter 是 `setAttrMessage`（state 名 `attrMessage`），是文件改稿时留下的**过期名字** |
| (3) | 契约 `AftersalesReasonMappingCreate` 的 `categoryId` 生成成**必填** `string` | 后端 `updateMapping` 的语义是「传了才改」（`coalesce(?::uuid, category_id)` 与 `if (categoryId() != null)` 都按可空处理），而前端做「只切启用/停用」时不传内部分类，被必填逼得发 `""`，靠 Jackson 的空串→null 强制转换才蒙对 —— **那是实现细节不是契约**，全局 `CoercingConfig` 一改就会开始 400 而 CI 全绿。改为 `nullable` + 前端发显式 `null` |

另修 Controller Javadoc 里 **2 处 U+FFFD 乱码**（「若产出」「给一份」）——
上一任 worker 被杀时留下的字节截断，不是逻辑问题但会污染源码。

### 缺陷 (3) 为什么值得一条回归测试

它是**契约与运行时之间的缝**：前端单测只覆盖 model 层（不发真实 HTTP 请求），
后端单测只覆盖 service 层（直接传 Java 对象，碰不到 Jackson 反序列化那一层）。
**只有 HTTP 边界测试能钉住它**，所以补在 `AftersalesReasonIntegrationTest`：

`只切启用停用的映射更新在http边界上能成_内部分类可以显式传null表示不改` ——
断言只切状态的两个方向（停用 / 切回）都 200，且传 `null` 时内部分类**确实没被动过**。

⚠️ 一开始还想用「空串 ⇒ 400」当反向对照，实测拿到的是 **200**：
Jackson 会把空串强制转成 null。所以那条断言删掉了 ——
**把当前 Jackson 版本的强制转换行为写死成契约，反而会阻止将来正确的配置收紧**。

## 关键发现：契约缺口是双向的

不只是「旧 spec 会删掉别人的端点」。实测发现：**并入之后、重新生成之前**的
`trade.ts` 本身就与批次 18 的端点对不上；重新生成后才补齐 24 个类型。
最终核对结论：

- `trade.ts` 导出类型集合**并入前后一致**：724 个，`comm` 比对**零丢失零新增**
  （git diff 显示的 26 增 26 删全是**重排序**，外加补上 `DO NOT EDIT` 头 ——
  之前只有 trade.ts 缺这个头，另外 5 个服务都有）
- `trade.openapi.json`：批次 18 的 **8 条**路径（`case-detail/capabilities`、
  `cases/{id}` 及其 5 个子路径、`cases/bulk`）与本模块的 **8 条**路径**同时在**

## 文件清单

**新增（后端）**
- `backend/services/trade/src/main/java/com/starcon/trade/AftersalesReasonController.java`（280 行）
- `…/AftersalesReasonModels.java`（~535 行）
- `…/AftersalesReasonService.java`（1465 行）
- `backend/services/trade/src/main/resources/db/migration/V158__aftersales_reason_taxonomy.sql`（571 行）

**新增（测试）**
- `…/AftersalesReasonIntegrationTest.java`（36 例）
- `…/AftersalesReasonTenancyIntegrationTest.java`（5 例，含三断言）

**新增（前端）**
- `apps/web/src/app/aftersales/reasons/page.tsx`
- `apps/web/src/components/aftersales/reason-taxonomy-model.ts` + `.test.ts`（20 例）
- `apps/web/src/components/aftersales/reason-taxonomy-workspace.tsx`
- `apps/web/src/app/api/trade/aftersales-reason-allowlist.test.ts`（4 例双向断言）

**修改**
- `apps/web/src/lib/api-proxy.ts`（新增一条 services[] 元素）
- `packages/api-contracts/src/{contracts/trade.ts,trade.openapi.json}`（codegen 产物，禁手改）
- `docs/product/capability-blueprint/module-progress.md`（**仅 11.1.2.1 那一行**）

## 验证证据

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 后端 | `./mvnw -q compile` | exit 0 |
| L0 前端 | `TURBO_FORCE=true pnpm typecheck` | **3 successful，Cached: 0 cached, 3 total** |
| L1 后端 | `./mvnw -pl services/trade -am test -Dtest='AftersalesReason*'` | **41 例全绿**，Flyway 34 条迁移 → v158 |
| L1 前端 | `pnpm vitest run apps/web/src/components/aftersales apps/web/src/app/api/trade/aftersales-reason-allowlist.test.ts` | **59 例全绿 / 4 文件** |
| **全量 allowlist 回归** | `pnpm vitest run apps/web/src/app/api/trade` | **173 例全绿 / 31 文件** |

⚠️ `TURBO_FORCE=true` 是强制的：并行车道共享 turbo 缓存，FULL TURBO 的「绿」可能
命中的是**别的 worktree 的代码**。

⚠️ 全量 allowlist 回归是因为改了共享的 `api-proxy.ts`：取错一侧会**静默删掉别人
已交付的路由**，表现是页面 404，而 typecheck / lint / vitest / Playwright **全部全绿**。

## 后续待办

- **11.5.2.1 质量分析**、**6.4.2.2 供应商索赔**：直接取用 `/reports` 的既有口径，
  不要自己另定一套（本模块已把口径写进响应的 `semantics`）
- **11.1.1.2 详情页**可加一个「读本单归因」的入口（`GET /attributions?caseId=`）——
  本模块刻意只提供归因读写，不在售后单详情里造子页面
- 分类树**刻意没有按名称搜索**：没有对应索引的搜索框永远查不出东西，
  真要做要连同 GIN 索引一起加
- 平台原因码映射表的 **2.1.2.1 同步器**未落地；本表形状已按同一语义建好，
  同步器按同形状写入时结构与查询都不用改
