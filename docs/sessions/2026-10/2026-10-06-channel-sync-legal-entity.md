# 2026-10-06 02.4/02.5 同步与回写 + 17.1 法人档案，收口 D1–D2 期

> 承接 [2026-09-29 D1–D2 期启动](../2026-09/2026-09-29-channel-service-and-d1-d2-start.md) 里列的三项待办，
> 本轮全部做完，D1–D2 期闭环交付完毕。

## 决策摘要

**UNKNOWN ≠ FAILED：平台超时不代表没执行，必须核验后才允许重发。**

这是本轮最重要的一条业务判断。渠道回写（改库存、改价、改发货状态）失败时，
区分不了"平台没收到"和"平台收到了但没回执"。若一律当失败重发，
就会出现重复发货、库存错扣这类真实资损。所以同步失败进 `markUnknown` 产出 `UNKNOWN`，
只有人工/对账确认了平台侧真实结果（`Verification`）才允许再次投递。

其余关键决策：

- **不可篡改靠触发器而非撤权**：最初给 `sync_run` 做 `REVOKE UPDATE`，结果 `completeRun`
  自己都写不进去。改为 `GRANT UPDATE` + `run_settled` 触发器，只放行 RUNNING → 终态这一次跃迁。
- **游标只在 SUCCEEDED 推进**：FAILED / PARTIAL 原地保留，保证重放从失败点续传而非从头。
  失败记录按 `external_key` 合并去重，归属**最初**那一次运行，不是重放的那次 ——
  否则重放会把历史问题洗成"新的问题"。
- **`owner_id` 不允许静默变更**：店铺转让走显式端点（`app.transfer` 意图位），
  绩效归属变化必须可审计。
- **法人档案写入与变更仅 OWNER 可执行**（沿用组织变更纪律），读取只需
  `identity.legal_entity.read`，财务角色可看不可改。
- **UNIQUE 含 NULL 列必须 `NULLS NOT DISTINCT`**：连接级默认游标的 `marketplace_id` 为 NULL，
  普通 UNIQUE 会插出多行，续传点分裂。

## 本轮完成

### 02.4 同步运行（channel）

- `V2__sync_runtime_and_platform_operations.sql`：
  `sync_cursor`（粒度 = 连接 + 站点 + 资源类型 + 分区）/ `sync_run` / `sync_failure` /
  `platform_operation`；`mark_broken_cursor()` 触发器（连续失败 5 次 → BROKEN）。
- `SyncService.java` + `SyncController.java`：游标生命周期、运行日志、失败队列与重放。
- 踩到一个**真实资损 bug**：`CONFIRMED_NOT_APPLIED` 曾与枚举而非 `.name()` 比较，
  导致所有核验静默落进 `STILL_UNKNOWN` 分支 —— 核验形同虚设。修掉后核验才真正生效。

### 02.5 平台回写幂等（channel）

- `platform_operation` 以 `UNIQUE(tenant_id, idempotency_key)` 保证同一意图只落一次账。
- 回写动作 start / succeed / fail / unknown / verify 全链路可审计。

### 17.1 法人档案（identity）

- `V5__legal_entity.sql`：`legal_entity`（含 `base_currency`、`amount_scale`、`quantity_scale`、
  `rounding`）+ `legal_entity_reference`（显式登记引用，使删除保护有真实依据）+
  `guard_legal_entity_delete()` 触发器。
- 本位币和舍入规则决定 12 财务总账的记账基准，放在法人上而不是全局设置上，
  是因为多法人主体天然有本位币差异。
- 删除策略：仍被业务对象引用时拒绝并提示先停用 —— 货权与记账主体不能凭空消失，
  而停用可逆且不破坏历史单据。

### 前端

- `channel-workspace.tsx` 扩为五段（新增「同步运行」「平台回写」），含失败原因分布、
  游标暂停/恢复/触发、运行日志、UNKNOWN 三选一核验。
- 新增 `legal-entity-workspace.tsx` + `/org/legal-entities` 页面。

## 关键 trade-off

**身份权限码按动作粒度拆开**：`channel.sync.trigger`（触发同步）与
`channel.sync.complete`（写入并推进游标）拆成两个权限。前者是低风险的只读触发，
后者会真实推进续传点、把数据标记为已同步 —— 两者混在一个权限里，
就等于把"能看"和"能让系统认为已经看过"绑在一起。

## 踩坑记录

| 坑 | 结论 |
|---|---|
| `Instant` 绑 timestamptz | 只有 `LocalDateTime` 可用，需显式 `LocalDateTime.ofInstant(i, ZoneOffset.UTC)` 决定转换时区，不能依赖 JVM 默认时区 |
| jOOQ 不支持显式 SQL 里的类型断言 | `?::uuid is null`、`(?||' days')::interval` 都会 bad grammar → 在 Java 侧分支决定 SQL 片段 |
| jOOQ 不支持 Java enum 绑定 | `SQLDialectNotSupportedException` → 必须转 `.name()` |
| `Object[]` 混用 | UUID 会被误判成 `any[]` → 逐参传递，或显式 `new Object[]{...}` 并走正确重载 |
| `btrim(x) = 3` | PG16 里 `text = integer` 报 42883 → 改 `length(btrim(...)) = 3`；`char(N)` 改 `varchar(N)` |
| `unnest(ARRAY[...])` | 元素类型推断不稳定 → 统一加 `::text[]` |
| RLS 策略拼前缀 | `channel_operation()` 返回的已是完整权限码，再拼前缀会导致策略永不命中 |
| 断言全局列表长度 | 测试容器被本类所有测试共享，断言全局计数会失真 → 按自己的数据过滤 |
| 两个测试类各自起 PG 容器 | 角色创建会冲突 → 改条件式 `DO $$ ... IF NOT EXISTS` |
| Flyway 迁移报错难定位 | 报的是字节偏移（如"位置：1473"）无法反推行号 → 起临时 PG 逐条 `psql -f`，秒级定位 |

## 验证结果

- `cd backend && ./mvnw clean verify` → **62 tests green**（identity 13 / catalog 11 / channel 38），
  真实 PostgreSQL 16 + RLS 三条断言实跑
- `pnpm typecheck` → 通过
- `pnpm lint` → 通过
- `pnpm test` → **64 tests green**（11 个测试文件）
- `pnpm build` → Compiled successfully

## 文件清单

新增
- `backend/services/channel/src/main/resources/db/migration/V2__sync_runtime_and_platform_operations.sql`
- `backend/services/channel/src/main/java/com/starcon/channel/{SyncService,SyncController}.java`
- `backend/services/channel/src/test/java/com/starcon/channel/SyncRuntimeIntegrationTest.java`
- `backend/services/identity/src/main/resources/db/migration/V5__legal_entity.sql`
- `apps/web/src/components/iam/legal-entity-workspace.tsx`
- `apps/web/src/app/org/legal-entities/page.tsx`
- `apps/web/src/lib/api.test.ts`
- `tests/e2e/iam-recovery.spec.ts`

修改
- `backend/services/identity/src/main/java/com/starcon/identity/AdministrationController.java`（法人端点）
- `backend/services/identity/src/test/java/com/starcon/identity/IdentityIntegrationTest.java`
  （RLS 表清单纳入 legal_entity / legal_entity_reference，新增 5 个法人测试）
- `apps/web/src/components/channel/channel-workspace.tsx`（扩为五段）
- `apps/web/src/lib/{api.ts,api-proxy.ts}`（服务前缀分发 + allowlist）
- `packages/ui/src/lib/task-pages.ts`（/org/legal-entities 任务页）
- `packages/api-contracts/src/{catalog.ts,channel.openapi.json,identity.openapi.json}`（codegen 产物，61 DTO）
- `.gitignore`（忽略 `*.bak` 与 `.dev-cookie.txt`，防止凭据误入仓库）

## 后续待办

**D1–D2 期已收口，下一步进 D3 期（07 库存核算 + 08 仓储作业）。**

遗留项：
- `shop.legal_entity_id` 仍是悬空逻辑引用（跨库不建 FK，遵循 ADR-0004 纪律）。
  channel 侧尚未调用 `/identity/legal-entities/references` 登记引用，
  因此删除保护目前只对已登记的引用生效 —— 接入店铺侧写入时必须补上这一步。
- 02.6 平台消息接入（`webhook_receipt`）未实现。
- `spotless:apply` 在 Java 25 上崩溃（`NoSuchMethodError: Log$DeferredDiagnosticHandler`），
  仓库既有问题，未绑定到构建生命周期，不影响 `verify`。
