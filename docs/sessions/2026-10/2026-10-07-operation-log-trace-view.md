# 17.5.3.2 操作日志

## 决策摘要

按蓝图交付「操作日志 + 链路关联视图」，核心是把**请求级链路**做成真的：一次 HTTP 请求产生的
登录、API 调用摘要、错误堆栈共享同一个 `request_id`，链路聚合在数据库里完成，前端只负责呈现。

与 17.5.3.1 审计日志的分工是硬性的，不是文案差异：

| 维度 | 审计日志（17.5.3.1）     | 操作日志（本模块）                            |
| ---- | ------------------------ | --------------------------------------------- |
| 用途 | 合规                     | 运维排障                                      |
| 内容 | 变更前后值 + 授权上下文  | 耗时、状态码、任务结果、错误堆栈              |
| 留存 | 长期，不可删             | 短期，`expires_at` 到期可清理                 |
| 篡改 | 触发器拒绝 UPDATE/DELETE | 授予 DELETE（这正是「可短期留存」的执行方式） |
| 检索 | 资源 / 操作人            | 用户 / 时间 / **请求 ID**                     |

两表字段几乎没有交集，这正是蓝图「不可互相替代」的落地证据。集成测试里有一条用例
直接对照二者：审计行超级用户也删不掉，操作日志行可删。

## 关键 trade-off

**1. API 调用摘要放在拦截器，不放业务代码。**
`OperationLogInterceptor` 在请求结束后采集方法、路径、状态码、耗时与异常。
放业务代码里必然漏，而且会把每个控制器搅乱。写日志失败统一 catch 吞掉——
排障日志反过来变成故障源，比缺一条日志糟糕得多。

**2. 登录失败的租户归属交给数据库反查。**
登录失败时没有可信租户上下文，而 `tenant_id` 是 NOT NULL 且受 RLS 保护。
`record_failed_login` 按用户名的成员关系反查候选租户后写入，调用方结构上无处传 tenant_id
（沿用 V40 的窄权限纪律）。账号不存在则不写：给查无此人的账号硬造归属，
等于凭空造出一条不存在的成员登录流水。

**3. request_id 与 trace_id 分开两列。**
前者是本次 HTTP 调用，后者是网关传来的跨服务链路。混用会丢掉跨服务串联能力。

**4. 不记请求体，只记摘要。**
路径只取 URI 不带查询串（查询串里常有令牌）。技术细节仍走 `AuditRedactor` 同一套脱敏口径。

**5. 堆栈截断双保险。**
应用侧截到 8000 字符并追加 `…[已截断]` 标记；库层 `CHECK length(stack_trace)<=8200` 兜住
直连 SQL 的写入。测试分别验证「截断后能写进库」与「超限被数据库拒绝」。

## 踩过的坑

- **`?::timestamptz` 不能落在 values 末位**。`expires_at` 起初写成裸 `?`，
  PG 报 `timestamp with time zone 但表达式是 character varying`。两个 timestamptz 都加
  cast 收尾后正常。
- **`trace.firstAt/lastAt` 的可空性**：链路为空时服务端返回 null，但契约最初给的是 `string`。
  用 `@Schema(nullable=true)` 修正，否则前端会把「没记录」当成「耗时 0」——正是排障里
  最容易得出错误结论的地方。

## 文件清单

**迁移**

- `backend/services/identity/src/main/resources/db/migration/V49__operation_log.sql`

**后端**

- `backend/services/identity/src/main/java/com/starcon/identity/OperationLog.java`（写入侧 + 截断）
- `backend/services/identity/src/main/java/com/starcon/identity/OperationLogController.java`（检索/详情/链路/导出/清理）
- `backend/services/identity/src/main/java/com/starcon/identity/OperationLogInterceptor.java`（API 摘要与错误堆栈采集）
- `backend/services/identity/src/main/java/com/starcon/identity/RequestTrace.java`（链路键）
- `backend/services/identity/src/main/java/com/starcon/identity/IdentityController.java`（登录/登出事件，**已交付文件的最小改动**）

**前端**

- `apps/web/src/app/org/operation-log/page.tsx`
- `apps/web/src/components/iam/operation-log-workspace.tsx`
- `apps/web/src/components/iam/operation-log-model.ts`
- `apps/web/src/components/iam/operation-log-model.test.ts`
- `packages/ui/src/lib/task-pages.ts`（导航，并入共享文件）
- `apps/web/src/lib/api-proxy.ts`（白名单，并入共享文件）

**测试**

- `backend/services/identity/src/test/java/com/starcon/identity/OperationLogIntegrationTest.java`（14 项）

**契约 / 台账**

- `packages/api-contracts/src/`（codegen 产物，只读）
- `docs/product/capability-blueprint/module-progress.md`（仅本模块那一行）

## 验证

- `backend/./mvnw verify` → BUILD SUCCESS（identity 1205 项 + catalog/channel/warehouse 全绿）
- `pnpm typecheck` → 3/3 通过
- `pnpm lint` → 通过（仅剩一条别人文件的既有 warning）
- `pnpm test` → 54 文件 908 通过（基线 53/897，净增 11，无回归）
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → 成功，`/org/operation-log` 已注册
- `node tools/module-progress/sync.mjs --check` → 一致
- 契约 diff 逐字核对：路径集合仅 +5、schema 集合仅 +5（全为 `OperationLog*` 前缀），零删除；
  `catalog.ts` 其余差异经排序比对为纯键序重排。

## 后续待办

- **跨服务链路未覆盖**：蓝图说「上游 = 系统各层」，但 AGENTS.md 禁止跨服务读表，
  所以 `trace_id` 目前只在 identity 内产生。跨服务的统一链路视图需要事件总线先落地。
- **无定时清理**：`expires_at` 与 `/purge` 端点都已具备，但真正的定时任务留给平台运维层
  （17.7.2.2）统一调度，避免每个服务各自起一个调度器。
- **写入未采样**：高频租户的 API 调用摘要会持续增长。留存期清理是目前唯一的容量控制手段，
  生产化前需要评估采样或按量分档。
