# 2026-10-18 · 2.5.2.1 常用回写操作（阶段 D5，L3 锚点 2.5.2）

车道：`feat/2-5-2-1`，worktree `.worktrees/2.5.2.1`，迁移 `V169__common_writeback_operations.sql`。

## 接手时的实际状态（**以磁盘为准，不采信上一轮自述**）

上一轮 worker 在 18:45 被 Token Plan 配额打断（`errorCode 42212`），自述「预检通过……开始写 V169 迁移」。
逐个读盘核实后，**自述与磁盘不符，但成果比自述完整得多**：

| 文件 | 自述 | 实际（读盘核实） |
| --- | --- | --- |
| `WritebackBatchService.java` | 刚开始写 | **已完成 1034 行**，无半截方法体、无 TODO/占位异常 |
| `WritebackBatchModels.java` | — | **已完成 270 行** |
| `WritebackBatchController.java` | — | **已完成 125 行**，7 个端点齐 |
| `V169__common_writeback_operations.sql` | 刚开始写 | **已完成 313 行**，三表 + RLS + 索引 + GRANT 齐全 |
| `WritebackBatchIntegrationTest.java` | — | **已完成 850 行**，三租户断言都在 |
| 前端 | — | **零文件，完全未开工** |

⇒ 后端不是半成品，**真正缺的是前端整个工作区**（台账 `已交付` 的定义是「迁移 + API + **页面** + 测试齐全」）。
本轮的工作因此是：**① 修后端 1 条测试断言缺陷 ② 补完前端工作区 ③ 补完契约 ④ 补完台账与会话文档**。

### 依赖自检（重新独立验证，未沿用上一轮结论）

| 依赖 | 落点 | 结论 |
| --- | --- | --- |
| `platform_operation` | `V2__sync_runtime_and_platform_operations.sql` | ✅ 存在。`UNIQUE(tenant_id,idempotency_key)`、`operation` CHECK 含 `PUBLISH/UPDATE_PRICE/UPDATE_STOCK/SHIP_UPDATE` |
| `shop` | V1 | ✅ 有 `UNIQUE(tenant_id,id)`（复合 FK 前提）、`platform_id` |
| `platform` | V1 | ✅ **独立于 `marketplace` 的表**，服务层 join `platform p on p.id=s.platform_id` 成立 |
| SyncService 既有 API | `SyncService.java` | ✅ `operations/startOperation/succeedOperation/failOperation/markUnknown` 全部存在（测试复用 2.5.1.1 的状态机，不自造） |
| `AccessService.Access` | 7 个分量 | ✅ 与测试构造一致 |
| 迁移号 V169 | channel 迁移目录 | ✅ 目录最大为 V146，V169 未被占用（预分配号，未自找最大号） |

## 决策摘要

### 1. 一条测试断言写错了（不是产品缺陷）—— 而且断言错在**测了 PostgreSQL 的实现细节**

原断言 `payload().contains("42.500")` 失败，实际存回 `"stock":42.5`。
原因：payload 落在 **jsonb** 列，PG 归一化 numeric 的尾随零（`42.500→42.5`、`10.000→10.0`）。
业务关心的是「发给平台的是 42.5 这个数」，不是 JSON 写了几位小数。
→ 改为解析 payload 后按 `BigDecimal.isEqualByComparingTo` 比值断言，并**顺带钉住 `previousStock`**（旧值冻结，
事后追问「从多少改成多少」不靠猜）。断言比原来更强，而不是被放宽。

### 2. 契约暴露两处**真实缺陷**，按规矩改后端 DTO 再重新生成（未手改 codegen 产物）

| 缺陷 | 生成结果 | 后果 | 修法 |
| --- | --- | --- | --- |
| `BatchParameters.currency` 缺 `@Schema(nullable=true)` | `currency: string`（必填） | 前端为过类型检查只能发 `""`，而 `""` **过不了** `@Pattern("^[A-Z]{3}$")` → DELIST / SHIP_UPDATE 一律 400 | DTO 加注解 |
| `perTarget` 用 `Map<String, PerTargetParameters>` | `perTarget: { }`（空对象） | 前端拿不到运单号、也过不了类型检查 | 改为**具名 record 列表**（每条自带 `targetId`）—— 正是 9.6.1.3 踩过并修过的那颗雷 |

顺带把 `WritebackBatchInput.reason` / `WritebackTargetInput.listingRef,skuCode,sourceRef` 也补上 `nullable`：
它们在 DB 里是 `NOT NULL DEFAULT ''`，Java 侧本就允许 null，契约却在说「必填」。

### 3. trade 契约的 churn **不回滚就等于制造合并冲突** —— 只提交 channel 那一份

codegen 一次生成六个服务，`trade.ts` / `trade.openapi.json` 跟着变了。
核实方式：排序后 `diff` 为空、JSON 语义 `==` 为 True ⇒ **纯行序 churn**（集成头既有漂移，与本模块无关）。
→ `git checkout` 复原这两个文件，只保留 channel 的**纯新增**（15 个 type，零删除）。

### 4. 「批量 ≠ 事务」在前端落成三处具体措辞，不是横幅口号

| 蓝图要求 | 前端落点 |
| --- | --- |
| 部分失败要如实回报 | `outcomeTone('PARTIAL')='warning'`，与 SUCCEEDED 不同色（最该看的批次不能混在历史里） |
| 没发出去的 ≠ 平台拒绝的 | `itemResultText` 对 `REJECTED` 说「**没发出去**」且用中性色 —— 说成「失败」会让人去平台侧查一条压根不存在的失败记录 |
| UNKNOWN 不是失败 | `itemTone('UNKNOWN')='warning'` 而非 danger，并带 `unknownHint`「先核验再决定重试」。并进 danger 就是**在诱导重试** = 重复扣减 |

页面**刻意没有**「一键重试整批」：对含 UNKNOWN 的批次整批重发是本模块最贵的资损动作，重试按 2.5.1.1 逐条判断（2.5.1.2 核验）。

### 5. 一处取舍：请求边界上做一次字符串→number 转换

契约把 BigDecimal 生成为 `number`，而 AGENTS.md 禁浮点记账。
决定：**展示层全程原样、绝不做前端算术**（那才是要防的），只在**发起请求这一个边界**转换 ——
库存 NUMERIC(11,3)、价格 NUMERIC(14,4) 的实际量级远在双精度精确范围内；后端收到即解析回 BigDecimal 并 `setScale` 归位，
量纲由 `@Digits` 校验（不合法 400 而非悄悄改数）。
**填错的数量给 `null` 而不是 0**：塞 0 是「合法地把库存改成 0」，那是一次真实事故。

### 6. `timestamptz` 两个方向都钉住

- 后端：`?::timestamptz` + `OffsetDateTime` 绑定（沿用上一轮正确实现），测试**断言写回值**且断言到具体瞬间 `2026-03-03T21:06:07Z`。
- 前端：`datetime-local` 的字符串按**浏览器本地时区**解析再转 UTC —— 直接当 UTC 发就是静默偏掉本机时差（同一类 bug）。

## 文件清单

**新增（本车道）**
- `backend/services/channel/src/main/resources/db/migration/V169__common_writeback_operations.sql`（接手时已完整，未改）
- `apps/web/src/app/channels/writeback/page.tsx`
- `apps/web/src/components/channel/writeback-batch-model.ts`
- `apps/web/src/components/channel/writeback-batch-workspace.tsx`
- `apps/web/src/components/channel/writeback-batch-model.test.ts`
- `apps/web/src/app/api/channel/writeback-batch-allowlist.test.ts`

**修改**
- `WritebackBatchModels.java`：`perTarget` Map→record 列表、`currency`/`reason`/`listingRef`/`skuCode`/`sourceRef` 补 nullable
- `WritebackBatchService.java`：`perTarget()` 改为按 `targetId` 遍历查找
- `WritebackBatchController.java`：删两处未用 import
- `WritebackBatchIntegrationTest.java`：payload 按值断言；`perTarget` 用 record 列表
- `apps/web/src/lib/api-proxy.ts`：**新增一条独立 `services[]` 元素**（既有分支一字未动、未重排）
- `packages/api-contracts/src/contracts/channel.ts` + `channel.openapi.json`（codegen，纯新增）

## 验证（真实输出）

- **L0** `cd backend && ./mvnw -q compile` → `COMPILE_EXIT=0`
- **L0** `pnpm typecheck` → `Tasks: 3 successful, 3 total`
- **L0** `pnpm --filter @starcon/web lint` → 我的文件 0 warning（输出里 4 条 warning 全在其他车道既有文件）
- **L1** `./mvnw -pl services/channel -am test -Dtest='WritebackBatch*'`
  → `Tests run: 20, Failures: 0, Errors: 0, Skipped: 0`（接手时是 `20, Failures: 1`）
- **L1 前端** `pnpm vitest run <model.test.ts> <allowlist.test.ts>` → `Test Files 2 passed (2) / Tests 27 passed (27)`
- **L1 全量 allowlist 回归**（动了共享白名单必须跑）`pnpm vitest run apps/web/src/app/api/channel`
  → `Test Files 7 passed (7) / Tests 33 passed (33)`
- 加跑 `pnpm vitest run apps/web/src/app/api` → `Test Files 59 passed (59) / Tests 401 passed (401)`

## 后续待办 / 下游缺口

- **2.5.1.1 未知结果核验（2.5.1.2）未交付**：本页 UNKNOWN 只如实呈现并提示先核验，**不提供**核验入口。
- **上游 04 刊登 / 07 库存 / 09 订单 / 10 物流未交付**：`writeback_target` 登记簿因此由本服务自持投影（迁移文件头第二节论证），
  页面提供手工登记入口；上游交付后改为事件驱动登记，本表结构不必变。
- **白名单刻意不放行**：`writeback-batches/{uuid}/retry`（整批重发，有资损风险）、`writeback-targets/{uuid}`（无此端点）、
  `writeback-targets/sync`（本模块不经跨服务同步）。均有 allowlist 反向断言钉住。
