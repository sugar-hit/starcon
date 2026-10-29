# 1.5.2.1 服务与任务状态（阶段 D7，L3 锚点 1.5.2）

- **日期**：2026-10-26
- **车道**：`feat/1-5-2-1`（worktree `.worktrees/1.5.2.1`）
- **批次**：round-105 / batch-104

## 决策摘要

1. **落在 channel 服务、零迁移、V386 主动不占用。**
   蓝图输入是「02 同步任务、18 规则任务、数据迁移任务」，这三类里**真实存在**的任务事实
   全部在 channel 自己的库里（`sync_run` / `sync_failure` / `backfill_task` /
   `listing_migration_batch` / `publish_task` / `listing_collect_task`）。
   卷 01 的红线是「不产生新的业务事实，只聚合各系统的投影」「工作台只读 + 跳转」
   ⇒ 落一张派生表只会比它描述的事实更早过期，所以**不建表**（沿 1.5.1.1 不占 V338、
   1.5.1.2 不占 V375 的先例）。

2. **端点挂在 `/channel/service-task-status`，权限码复用 `channel.sync.read`。**
   identity 零改动。同一权限码也决定数据范围（SELF / DEPARTMENT 时部分任务表按店铺收敛），
   这一点写进了能力声明 —— 「看不到」是数据范围口径，**不是**「没有任务」。

3. **「停滞」做成具名、可测、有口径的判定。**
   判据 = **在执行中 且 已执行时长 > 阈值**；已执行时长由 **SQL 里的 `now()`** 算出
   （`stall_minutes`），**不用应用层墙钟**。阈值 30 分钟（黄）/ 120 分钟（红）写进代码常量
   并由能力声明公布，可用 Spring 属性覆盖。
   🔴 **「判不了」是一等状态**：在跑但来源表没记开始时刻 ⇒ `STALL_UNDETERMINED`，
   **不**折成「没卡住」。

4. **五个来源逐表描述，绝不假设它们长得一样。**
   `ServiceTaskStatusSources` 是**同一份**注册表：SQL 的 CASE 表达式由它生成，
   能力声明的「状态列 / 取值域 / 规范化 / 执行中取值 / 能不能判停滞 / 耗时从哪几列算」
   也由它生成 ⇒ **SQL 与文档不可能对不上**。
   `listing_migration_batch` 与 `listing_collect_task` 的状态取值域里**没有「执行中」**
   ⇒ 它们**判不了停滞**（不是「没停滞」）；`listing_migration_batch` **没有耗时列**
   ⇒ 耗时一律 null（拿 `created_at` 冒充执行时长是错的）。

5. **「系统故障 vs 业务数据问题」落在数据字段上，不是一句文案。**
   `sync_failure.reason` 六值词表 ⇒ `failureClass` 六档
   （BUSINESS / SYSTEM / MIXED / UNCLASSIFIED / UNDETERMINED / NONE），总览里两类分开计数。
   🔴 只有 `sync_run` 能分类（失败落在受控词表上）；其余来源**没有词表** ⇒ 一律
   `UNCLASSIFIED`（判不了），**本模块不替它们编一套分类**。

6. **跨服务边界一条都不越。**
   finance / crm / warehouse / trade / platform 各有自己的任务表，那些**不是本模块的数据**。
   没有一条跨库 SQL，也没有为了「把全平台任务收在一页」建跨服务聚合表。
   能力声明里逐条列出「未接通 / 未开发」：18.3 规则任务（整卷未开发，**不造表顶替**）、
   18.1.3.1 导入导出任务、18.1.3.2 数据迁移，以及五个其它服务的任务表。

## 关键 trade-off

| 取舍 | 选择 | 代价 |
| --- | --- | --- |
| 任务范围 | **还在执行的 ∪ 最近 30 天发生的** | 更早的历史任务不在本页范围内（能力声明里写明）。好处：汇总与提示不会随历史线性变慢 |
| 严重度筛选 | 留在**前端**（与 1.5.1.1 一致），后端只筛来源/状态/触发源/停滞/失败/关键字 | 前端筛选不参与后端分页；换来的是「规则只有一份」（服务端） |
| 五个来源的 SQL | 每个来源**一条显式 SELECT**，union 成统一投影 | SQL 较长；但每张表的 join / 时间列 / 状态映射都必须自己写，套模板反而会写错 |
| 任务 id | `GET /tasks/{id}` 不带来源段 | 跨来源 id 撞车的概率可忽略；撞车时按注册表顺序取第一条（确定性） |
| api-proxy 格式 | 既有条目**一字未改**（+51 / −0），即使 `prettier --write` 会顺手改 6 条既有条目的排版 | 文件在本仓基线上仍是 prettier-dirty（HEAD 本来就 dirty，948 个文件） |

## 踩坑记录（留给下一个人）

- 🔴 **Spring Boot 的 jOOQ 异常翻译器会先把 `org.jooq.exception.DataAccessException`
  翻成 Spring 的 `org.springframework.dao.DataAccessException`**（`BadSqlGrammarException` 之类）。
  只 catch jOOQ 那个 ⇒ **真正的上游故障会漏掉**，「读不到」被折成空集合。
  本模块的 `catch (org.springframework.dao.DataAccessException | org.jooq.exception.DataAccessException e)`
  两条都接。
- 🔴 **占位符顺序 = `?` 在 SQL 文本里出现的顺序**。总览那个 `?`（停滞阈值）在 SELECT 列表里，
  文本上**早于** union 子查询 ⇒ 它的绑定必须**先**加，否则整条 SQL 报语法错。
- 🔴 **数据范围策略会藏行**：`listing_migration_batch` 的策略要数「在范围内的目标店铺条数」
  与 `target_shop_ids` 的长度比对 ⇒ 塞一个不存在的 UUID 当目标店铺，这一行会被直接藏起来
  （测试会误判成「这一类任务没接上」）。
- 🔴 **Spring 的 jOOQ 翻译器 + `bad SQL grammar` 的报错里会带整条 SQL**，日志很大，
  但它也是查参数顺序的最快线索。

## 文件清单

**后端（channel 服务）**

- `backend/services/channel/src/main/java/com/starcon/channel/ServiceTaskStatusModels.java`（新）
- `backend/services/channel/src/main/java/com/starcon/channel/ServiceTaskStatusRules.java`（新）
- `backend/services/channel/src/main/java/com/starcon/channel/ServiceTaskStatusSources.java`（新）
- `backend/services/channel/src/main/java/com/starcon/channel/ServiceTaskStatusService.java`（新）
- `backend/services/channel/src/main/java/com/starcon/channel/ServiceTaskStatusController.java`（新）
- `backend/services/channel/src/test/java/com/starcon/channel/ServiceTaskStatusRulesTest.java`（新）
- `backend/services/channel/src/test/java/com/starcon/channel/ServiceTaskStatusIntegrationTest.java`（新）
- `backend/services/channel/src/test/java/com/starcon/channel/ServiceTaskStatusTenancyIntegrationTest.java`（新）

**契约（codegen 产物，只由后端 spec 生成）**

- `packages/api-contracts/src/channel.openapi.json`（+869 / −0）
- `packages/api-contracts/src/contracts/channel.ts`（+16）

**前端**

- `apps/web/src/components/system-health/service-task-status-model.ts`（新）
- `apps/web/src/components/system-health/service-task-status-model.test.ts`（新）
- `apps/web/src/components/system-health/service-task-status-workspace.tsx`（新）
- `apps/web/src/app/system-health/tasks/page.tsx`（新）
- `apps/web/src/app/api/channel/service-task-status-allowlist.test.ts`（新）
- `apps/web/src/lib/api-proxy.ts`（+51 / −0）

**台账 / 会话**

- `docs/product/capability-blueprint/module-progress.md`（只改 1.5.2.1 一行的「状态」「证据」两格）
- `docs/sessions/2026-10/2026-10-26-1-5-2-1-service-task-status.md`（本文件）

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | 通过 |
| L0 类型 | `pnpm typecheck` | 通过（3/3 任务成功） |
| L1 后端 | `./mvnw -pl services/channel -am test -Dtest='ServiceTaskStatus*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **39 例全绿**（规则 16 / 集成 20 / 租户 3） |
| L1 前端（模块） | `pnpm vitest run apps/web/src/components/system-health/service-task-status-model.test.ts apps/web/src/app/api/channel/service-task-status-allowlist.test.ts` | **24 例全绿**（模型 19 / 白名单 5） |
| L1 前端（**全量白名单回归**，改了 api-proxy.ts 必跑） | `pnpm vitest run apps/web/src/app/api` | **257 文件 1450 例全绿** |
| Lint | `pnpm lint` | 通过（3/3；警告均在本车道之外的既有文件） |

## 后续待办

1. **卷 18 交付后接入规则任务**：18.3.1.1 / 18.3.2.1 落地后，
   在 `ServiceTaskStatusSources` 里**新增一条来源描述**即可 —— SQL 与能力声明都由注册表生成，
   不需要改查询层。
2. **18.1.3.2 数据迁移落地后同样接入**：那时「数据迁移任务」这条输入才有第二条真实来源。
3. **其它服务的任务要进来**：只能等对应服务提供**授权只读端点**，本模块再加一条来源描述；
   **不要**改成跨库 SQL。
4. **18.1.3.1 导入导出任务**：同上。
5. 若要扩大任务时间窗口，调 `channel.service-task-status.task-window-days`（默认 30）；
   停滞阈值调 `…stall-warn-minutes` / `…stall-critical-minutes`（默认 30 / 120）。
