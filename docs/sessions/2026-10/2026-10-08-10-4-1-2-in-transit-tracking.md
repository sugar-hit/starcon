# 10.4.1.2 在途跟踪 —— 崩溃车道接手与收尾

日期：2026-10-08　车道：`feat/10-4-1-2`　worktree：`.worktrees/10.4.1.2`　迁移：**V84**

## 接手背景

上一个 worker 在这条车道上因 `net::ERR_NETWORK_CHANGED` **崩溃**（不是被打断），
留下 2 个 commit、5 个文件、18 例集成测试红 9 例，未做全量验证、未 land。
本轮从**零信任**出发重新取证：先跑一遍拿真实红绿，再逐例判定「真 bug 还是断言错」。

## 决策摘要

### 1. 9 例红里有 **7 个是真 bug**，只有 2 个是断言与实现不一致

上一轮的接手备注把 9 例红归为「断言与实现冲突」+「NPE 与 SQL 错误」。实跑后发现，
**大部分是被掩盖的实现 bug**，断言反而是对的：

| #   | 位置                                                               | 性质               | 后果                                                                                                                                                                       |
| --- | ------------------------------------------------------------------ | ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | `Service.days()`                                                   | **真 bug**         | SQL 已把 `remaining_days_raw` 换算成「天」，Java 又除了一次 86400 → 「还剩 40 天」变成 0.0046 天 → **每一条在途记录都会被判成已进预警区**，提前量口径整个失去意义          |
| 2   | `Service.recordEvent` 事件 insert                                  | **真 bug**         | 15 个 `?` 对 14 列，且 `eta_before` 漏 `::timestamptz` → 改期类事件一律 BadSqlGrammar                                                                                      |
| 3   | `Service.confirmReceipt` update                                    | **真 bug**         | `receipt_confirmed_at` / `closed_at` 同样漏 cast                                                                                                                           |
| 4   | `Service.reloadAlert`                                              | **真 bug**         | `select *` 不含 `shipment_code`，而 `alertOf` 要读它 → jOOQ 抛 `IllegalArgumentException` → 被翻成 **400「字段格式不正确」**。这个最阴：现象完全指不到「回读时少查了一列」 |
| 5   | `Service.confirmReceipt` 顺序                                      | **真 bug**         | 先 UPDATE 关记录再 INSERT 事件，被自己的 DB 触发器 `guard_first_mile_tracking_event_source`（已闭环不接受 RECEIPT 类更新）当场拒掉、整笔回滚。**事件必须先写**             |
| 6   | V84 `guard_first_mile_tracking_state`                              | **真 bug**         | 条件写反：`status <> 'CANCELLED'` 应为 `= 'CANCELLED'`。结果**正常的收货确认被自己的约束拒绝**                                                                             |
| 7   | `Service.page` 的 `delayedOnly`                                    | **真 bug（设计）** | 把 WATCH 行过滤掉了。而 WATCH（默认到港前 7 天进区）恰恰是蓝图要求的「提前若干天」里提前量最大的那一档 —— 这个筛选正好把本模块最该被看见的那批货藏起来                     |
| 8   | 测试 `idOfShipment(...)` 传错参                                    | 断言错             | 把 tracking id 当 shipment id 传，查询必然空 → NPE                                                                                                                         |
| 9   | 测试 `收货确认_不写库存` 用 `source_reference like '%shipmentId%'` | 断言错             | 10.4.1.1 写的锚点是「发运单**号**-行号」不是 UUID，拿 UUID 去 like 永远匹配不到，断言在「查不到行」上空转                                                                  |

**关键 trade-off**：第 7 条我改了**实现**而不是改测试断言。测试说「`delayedOnly` 该返回 WATCH 行」，
从代码看测试像是在迁就实现，但对照蓝图原文「预警必须提前到预计到港前若干天」——
提前量最大的那一档恰恰是 WATCH，一个叫「延误筛选」的过滤器把它藏起来，正好抵消了本模块的核心要求。
**当断言与蓝图冲突时，实现错。**

### 2. 阈值测试改了数据而不只是断言

原用例传 `watch=7, warning=9` 想让「剩余 5 天」进 WARNING 区，但这违反三档递减约束被正确拒绝。
改为 `watch=9, warning=9` —— 并在注释里写明「这一改是两档一起动，不是单行孤立改 warning」。

### 3. 租户隔离断言二改了判据

原用例断言 `thresholds()` 对 B 返回 `length()==0`。但 `thresholds()` 有惰性 bootstrap
（`ensureDefaultThreshold` + `on conflict do nothing`），B 会拿到**自己那份**默认行。
断言 `length()==0` 等于把 bootstrap 判成 bug。改为断言**行 id 不同**：B 只看到自己那份，拿不到 A 的。

### 4. 契约 nullable 修正

请求 DTO 的可选字段补 `@Schema(nullable = true)`。不补的话 codegen 出的是非空 `string`，
前端只能被迫给「没填」的字段塞空串 —— 契约说了谎。
（顺带踩到 `@Schema` **不是可重复注解**，两条相邻的 `@Schema` 编译不过，必须合并成一条。）

## 文件清单

**新增**

- `backend/services/warehouse/src/main/resources/db/migration/V84__warehouse_in_transit_tracking.sql`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InTransitTrackingModels.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InTransitTrackingService.java`
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InTransitTrackingController.java`
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/InTransitTrackingIntegrationTest.java`
- `apps/web/src/app/logistics/transit-tracking/page.tsx`
- `apps/web/src/components/logistics/transit-tracking.tsx`
- `apps/web/src/components/logistics/transit-tracking-model.ts`
- `apps/web/src/components/logistics/transit-tracking-model.test.ts`

**修改**

- `apps/web/src/lib/api-proxy.ts`（白名单**求和**追加 transit-trackings 分支）
- `apps/web/src/app/api/warehouse/[...path]/route.test.ts`（放行 13 条 + 拒绝 8 条后端不存在的路径）
- `packages/api-contracts/src/catalog.ts`、`packages/api-contracts/src/warehouse.openapi.json`（codegen 产物）
- `docs/product/capability-blueprint/module-progress.md`（**只改第 349 行自己那行**）

## 跨车道约束（踩过的坑，值得记住）

1. **新表名绝不能含 `transit` 子串**。`InventoryInTransitIntegrationTest:1415` 扫
   `information_schema` 里 `table_name like '%transit%'` 并断言只允许 `inventory_transfer`
   与 `in_transit_event` 两张。用的 `first_mile_tracking*` / `first_mile_delay_*` 是合规的。
2. **必须在既有 `inventory_transfer` 之上构建，不建第二张在途账**。
   四张表里**没有任何数量余额列**，「发运 100 / 已了结 60 / 未达 40」全部 join 现算。
3. **对 `inventory_transfer` 不建外键**：7.5.1.2 的 truncate 不带本表，建了 FK 会让那条 truncate 直接失败。

## 验证（实跑）

| 命令                                                         | 结果                                                                                                                                                                         |
| ------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `./mvnw verify`                                              | **BUILD SUCCESS**，7 模块全绿；warehouse **820 例零失败**（含本模块 18 例 + 7.5.1.2 的 24 + 10.4.1.1 的 29 回归）；`Successfully applied 30 migrations … now at version v84` |
| `pnpm typecheck`                                             | 3/3 **零错误**                                                                                                                                                               |
| `pnpm lint`                                                  | 3/3 **零错误零新增 warning**                                                                                                                                                 |
| `pnpm test`                                                  | **86 文件 / 1587 例全过**（本车道新增 49 例：模型 27 + 白名单 22）                                                                                                           |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | 成功，`/logistics/transit-tracking` **11 kB**                                                                                                                                |
| `node tools/module-progress/sync.mjs --check`                | 一致                                                                                                                                                                         |

**并发 flake 记录（不要重复排查）**：首次 verify 时 catalog 的
`ImportValidationReportIntegrationTest` 挂 2 例 503、warehouse 的
`FirstMileShipmentIntegrationTest` 挂 6 例 503。两者**隔离单跑全绿**
（11/11、29/29），是 10 核机器上多车道并发跑 Testcontainers 的 flake，与本车道无关。

## 后续待办

1. **导航登记**：`/logistics/transit-tracking` 未进侧栏/搜索（`packages/ui` 的共享任务页表）。
   与 10.4.1.1 / 7.5.1.1 / 8.2.1.1 现状一致，本车道刻意不改以免重排他人条目。
2. **清关与阶段推进的 UI 表单**：后端端点完整（五合一 `POST /{id}/events`），详情弹窗只开了
   位置/改期/备注三个登记入口。
3. **错误文案**：`ApiErrorHandler` 把 `IllegalArgumentException` 统一翻成泛化 400，
   不指出具体字段。该 handler 为 warehouse 全服务共用，改它会动到别的车道的错误体。
4. **10.4.2.1 连接器交付后**：连接器按同一形状往 `first_mile_tracking_event` 写即可，表结构不用再改。
