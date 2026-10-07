# 2026-10-07 — D5 切片 1：02.4 同步游标（游标只在处理提交后推进）

夜间 D5–D7 冲刺的第一个切片，归属系统 02（渠道与店铺）2.4 同步运行。
模型设计以 `db/schemas/v2/README.md` §97 为准，行为约束以
`docs/product/capability-blueprint/02-channel-and-shop.md` §2.4 为准。

## 决策摘要

新建三张表承载「按连接、站点、资源、分区保存游标；只有处理提交后推进，失败可续传」：

| 表 | 承担的约束 |
|---|---|
| `sync_cursor` | 任务粒度唯一键（连接, 站点, 资源, 分区）；`cursor_value` + `external_marker` 双水位；连续失败计数 |
| `sync_run` | 单次运行的时间线与处理计数；`cursor_before` / `cursor_after` 快照；同游标单飞 |
| `sync_failure` | 失败队列；原因分类聚类；授权失效自动升级待办；重放幂等 |

**游标推进的三重保证**（缺一件就退化成「看起来能续传、实际会漏单」）：

1. `ChannelSyncService.commitRun` 在 `AccessService.run` 提供的同一个事务里同时写运行终态与推进游标。
   任一步抛错整体回滚——运行留在 RUNNING，水位原封不动。
2. 推进量不得早于本次 `cursor_before`，否则显式 400 而不是静默接受。调用方丢失运行上下文是真 bug，
   静默忽略会把 bug 变成生产漏单。
3. DB 层 `guard_cursor_direction` 触发器拦住任何倒退写；人工回退只能走 `rewindCursor` 端点，
   该端点用 `set_config('app.cursor_rollback', ...)` 声明意图后放行——与 `stamp_shop_owner`
   的负责人转移纪律同构：改事实来源必须走可审计的动作。

**重放幂等**落在 `failure_open_dedup` 部分唯一索引上：同租户同连接同资源的同一条外部记录只允许
一条未关闭失败。重复处理（webhook 重投撞上定时补拉）只累加 `attempt_count`，不堆第二张待办。

## 关键 trade-off

| 决策 | 取舍 |
|---|---|
| `marketplace_id` 在 `sync_cursor` 上 NOT NULL | 蓝图明确要求（连接, 站点, 资源类型）是任务粒度。保持 NOT NULL 也避开「NULL 在唯一索引里的重复语义」造出两个看起来一样的游标 |
| 重拉窗口（`from` 早于水位）不推进水位 | 否则「往回拉数据」会成为绕过游标方向守卫的第二条路径。回退只能走显式端点 |
| 同步数据用 `channel_scope(..., NULL, NULL)` | 任务粒度本身是连接级事实，不带店铺归属维度。沿用 `credential_scope` 先例；SELF/DEPARTMENT 数据范围只作用于 2.3.2.2 有归属语义的店铺数据 |
| `sync_failure` 不冗余 `marketplace_id` | 读取时 join `sync_cursor` 得到，避免第三份可能漂移的副本（实现时正是这个冗余字段先写错后被测试抓出） |
| 授权失效用 CHECK 约束强制 `escalated` | 2.4.2.1 说它「自动升级」，那就不能给「忘记点升级」留缝 |
| 前端页面不做 | 队列把前端列为 stretch goal。时间优先给迁移 + 测试 |

## 踩坑记录（下次直接照做）

- **jOOQ 纯文本 SQL 的绑定值数量必须与 `?` 个数相等**。多传会被内联进语句，产生
  `limit 500[Ljava.lang.Object;@...` 这种语法错误；少传同理。无过滤条件时要整体不传绑定值。
- **timestamptz 不能直接绑 `LocalDateTime`**。PG 按会话时区解释裸时间戳，JVM 在 Asia/Shanghai
  而容器在 UTC 时水位整整偏 8 小时。写入用带偏移量的字符串配 `?::timestamptz`，读取显式取
  `OffsetDateTime.toInstant()`。作业手册只记了「只有 LocalDateTime 可绑」，没提这层偏移陷阱。
- **游标起点用 `timestamptz 'epoch'` 而不是 NULL**：NULL 会把「从未同步过」和「清空游标」混为一谈；
  清零走显式回退动作而不是清列。

## 文件清单

新增
- `backend/services/channel/src/main/resources/db/migration/V2__channel_sync_cursor_run_failure.sql`
- `backend/services/channel/src/main/java/com/starcon/channel/ChannelSyncModels.java`
- `backend/services/channel/src/main/java/com/starcon/channel/ChannelSyncService.java`
- `backend/services/channel/src/main/java/com/starcon/channel/ChannelSyncController.java`
- `backend/services/channel/src/test/java/com/starcon/channel/ChannelSyncIntegrationTest.java`

改动（codegen 产物，勿手改）
- `packages/api-contracts/src/catalog.ts`
- `packages/api-contracts/src/channel.openapi.json`

## 验证

- `./mvnw verify`：identity 8 + catalog 11 + channel 38 = **57 tests 全绿**，BUILD SUCCESS（基线 39）。
- `pnpm lint` / `pnpm typecheck` 3/3；`pnpm test` 10 files / 61 tests（前端无改动）。
- RLS 三条断言在**新表**上实跑，不是从店铺表继承：两租户互不可见、伪造 `X-Tenant-Id` 无效、
  连接池归还后不串租户（池大小设为 1，使下一个借用者拿到同一条物理会话）。

## 后续待办

- 队列 #2 `d5-channel-webhook-receipt`：`webhook_receipt` / `external_snapshot`，与本切片的
  `sync_failure` 幂等键同源，重放去重口径要保持一致。
- 调度器本身（真正按 cron 拉起 `beginRun`）尚未实现；当前只有可被调度的端点与不变量。
- `sync_run` 的日志保留期（2.4.1.2「超期只保留聚合统计」）未做，需要定时归档作业。