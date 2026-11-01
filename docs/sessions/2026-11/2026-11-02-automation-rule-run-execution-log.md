# 2026-11-02: 18.3.2.1 执行日志（逐步明细 + 幂等 + 手动重跑）

## 摘要

蓝图 18「集成开放与智能自动化」→ 18.3「业务规则引擎」→ 18.3.2「执行」→ 18.3.2.1「执行日志」落地。

车道分支 `feat/18-3-2-1`（基于集成头 `6b8ae806`），迁移版本号由调度器预分配为 **V422**
（`backend/services/platform/.../V422__automation_rule_run.sql` +
`backend/services/identity/.../V422__platform_automation_rule_run_permission.sql`，
与 V419 / V420 / V421 同一「platform + identity 同号」的登记口径）。

蓝图原文：「**记录每次规则执行**：触发事件、条件命中情况、执行的动作、每步结果、耗时、错误；
支持查看上下文与手动重跑。…… **规则必须幂等**：同一事件重复触发不能重复执行副作用（如重复改价、重复建单）。」

交付物：两张新表（run + run_step）、6 个端点（`/platform/automation/runs`）、
一个真实工作区页面（`/automations/runs`）、28 个后端集成测试 + 24 个前端测试。

## 决策 / 结论

### 1. 🔴 只建自己的新表，**不碰** 18.3.1.1 的五张表

18.3.1.1（V419）已经有 `platform_automation_rule_execution` —— 但它是**执行统计台账**
（命中条件数 / 计划动作数 / 已执行动作数 / 起止时间这类**聚合计数**），
**不含逐步明细** ⇒「这次到底卡在第几步、命中的是哪条 condition」在那边查不到。

因此另建 `platform_automation_rule_run`（run）+ `platform_automation_rule_run_step`（step），
表名与 18.3.1.1 **不重名**，且：

- **只读** 18.3.1.1 的规则档案（真 FK `FOREIGN KEY (tenant_id, rule_id) REFERENCES platform_automation_rule(tenant_id, id)` + 规则名快照）；
- **一个字都不写**进 18.3.1.1 的任何一张表 —— 执行统计台账的写入所有者始终是 18.3.1.1
  （AGENTS.md「每张事实表只有一个写入所有者」）。

> ⚠️ 顺带影响：本模块**不复用** `platform.automation_rule.record` 那个权限码。
> 18.3.1.1 的 record 写的是 `platform_automation_rule_execution`（聚合统计），
> 本模块的 record 写的是 `platform_automation_rule_run`（逐步明细）——
> 两张表两个所有者，权限码跟着「写哪张表」分开，避免「改统计」与「改明细」互相越权。
> 新登记三个码：`platform.automation_rule_run.read / .record / .rerun`。

### 2. 🔴 幂等：蓝图加粗句落成**三条物理事实**

| 事实 | 落点 | 挡住什么 |
| --- | --- | --- |
| (a) **事件维度部分唯一索引** `UNIQUE (tenant_id, rule_id, event_id) WHERE rerun_of_run_id IS NULL` | V422 迁移 | 同一规则 + 同一事件**至多**一条执行记录 ⇒ 重复投递撞索引 |
| (b) **幂等键唯一索引** `UNIQUE (tenant_id, rule_id, idempotency_key)` | V422 迁移 | 事件触发用 `evt:<eventId>`；重跑用 `rerun:<原runId>:<序号>` 或调用方自带 ⇒ 重跑请求重复提交也拿不到第二条 |
| (c) **重跑是新的一次执行** | `rerun_of_run_id` + `rerun_seq` + `rerun_requested_by` | 原 run **一个字都不改**（只追加台账的触发器物理上不允许改） |

**为什么 (a) 必须是部分唯一索引**（`WHERE rerun_of_run_id IS NULL`）：
手动重跑复用原事件 id —— 若不排除重跑行，重跑会被这条约束挡住（那就成了「重跑不了」）。

**为什么服务层与 DB 层都要有**：应用层校验不是唯一防线。测试里专门用**不同的幂等键**
（`'evt:evt-dup2-alt'`）、**同一个 eventId** 直连 SQL 插第二条 ⇒ 只有事件维度那条索引
能挡住它 —— 这样断言才真的在验「同一事件 ⇒ 至多一条执行记录」这条约束本身，
而不是被幂等键那条约束「顺带」挡住。

### 3. 🔴 手动重跑只做记录与编排，**不擅自执行副作用**

本仓**不存在**规则执行引擎（backend / services 下没有求值器与动作执行器）⇒
重跑请求落成一条**待执行**记录：

- `outcome = 'NOT_ATTEMPTED'`（**未执行**，不是「已重跑成功」）
- `failure_class = 'EXECUTOR_UNAVAILABLE'`、`error_code = 'EXECUTOR_NOT_CONNECTED'`
- `not_attempted_reason` 写清「缺什么、归谁、现在怎么办」
- `side_effects_applied = false`、`executed_actions = 0`

与本仓既有做法同源：`UnavailableAiTextGenerationPort` / `UnavailableEventSubscriptionDeliveryPort`
那种「无上游 ⇒ 如实返回 Unavailable」。**伪造一次成功比不做重跑更糟。**

前端同步守住：结论徽章分 `成功 / 失败 / 跳过 / 超时 / 未执行` 五档，「未执行」单独一档，
并配一条黄色提示条说明「执行器未接通 · 这次重跑**没有真的执行**」。

### 4. 每一步都要可定位

逐步明细逐条记：`step_no`（同一 run 内唯一 ⇒ 次序确定，不靠 DB 返回顺序）/ `step_kind`
（CONDITION / ACTION）/ `matched`（**条件命中与否**）/ `condition_id` + `condition_seq` +
`field_path` + `operator` + `expected_value` + `actual_value`（**命中的是哪条 condition**）/
`action_type` + `target_ref` + `params`（**执行的动作**）/ `result` / `duration_ms`
（**整数毫秒**，禁浮点）/ `error_code` + `error_message`。

DB 层 CHECK 钉死「失败的那一步必须写清错误码与错误信息」⇒ 「卡在第几步但不知道为什么」
在数据层就不成立。页面把**第一处失败**单独拎出来。

> ⚠️ `condition_id` **刻意不建 FK**：18.3.1.1 保存规则时是「条件整体替换」，
> 条件行会被删掉 ⇒ 建了 FK 就会把**历史执行日志**一起锁死（改一次规则，历史就没法看了）。
> 所以只存**引用 + 快照**（seq / field_path 一起存下）。

### 5. 🔴 试运行标记由**规则档案**推导，不接受客户端自称

服务端读规则档案拿 `dry_run`，请求体**不接受** `dryRun` 字段（Jackson
`fail-on-unknown-properties` 直接 400 拒掉，不是静默忽略）。同理请求体**不接受**
tenantId / userId / membershipId。

### 6. 端点与页面

- 端点命名空间 `/platform/automation/runs` —— 与兄弟模块
  `/platform/automation-rules`（18.3.1.1）、`/platform/automation-rule-editor`（18.3.1.2）
  **首 token 就不同**（斜杠 vs 连字符）⇒ proxy 白名单分得开。
- 6 条端点：`GET /capabilities`、`GET /overview`、`GET /`（真分页 + 真筛选，全序 `created_at DESC, id DESC`）、
  `GET /{uuid}`（含逐步明细 + 重跑次数）、`POST /`（登记一次执行，幂等）、`POST /{uuid}/rerun`。
- 页面 `/automations/runs` —— 与 `/automations/rules`、`/automations/rules/editor` **互不为前缀**。
  真实工作区：运行列表 / 运行详情（逐步明细分「条件判定」「动作执行」两段）/ 重跑入口，
  **加载 · 空 · 错误三态齐全**；空态明说「18.3.2 执行尚未交付，这里空是**预期状态**不是故障」。

### 7. api-proxy 白名单的插入锚点

`apps/web/src/lib/api-proxy.ts` 是**共享白名单累积文件**，本车道**新增一条独立的
`services[]` 元素**（既有条目一字未改、不重排），插入锚点 = **18.3.1.1 规则列表那条之后** ——
与同批其它车道（18.4.2.2 / 18.4.3.1 / 18.5.1.1，`platform/ai/**` 系列）锚点不同 ⇒ 并入时 git 能干净求并。

**实测取证**（`automation-run-allowlist.test.ts`）：模型源码里**反向抽取真实调用点**逐条断言放行
（5 个形状）；7 个刻意不放行的形状仍 404（含 `{uuid}/execute` —— 执行引擎未交付 ⇒ 后端没有这个端点）；
22 条兄弟模块的形状仍放行（**未遮蔽任何既有已交付路由**）；代理**不转发**客户端伪造的租户头。

## 关键 trade-off

| 选择 | 放弃了什么 | 为什么 |
| --- | --- | --- |
| **另建两张表**而不是复用 18.3.1.1 的 `_execution` | 多两张表、多一套 RLS / 触发器 / GRANT | `_execution` 是聚合统计，不含逐步明细；复用就得 ALTER 别人的表（跨车道 DDL = L2 爆炸面） |
| **部分唯一索引**而不是普通唯一约束 | 重跑行不受 (rule, event) 约束保护 | 重跑复用原事件 id，普通唯一约束会让「重跑」直接不可用 |
| **重跑落成 NOT_ATTEMPTED 而不是 409 拒绝** | 「点重跑」不会立刻看到成功 | 仓库没有执行器；如实记录比伪造成功强。**拒绝**会让运营以为功能坏了，**记录**才是真相 |
| **试运行标记服务端推导** | 客户端不能逐次声明「这次是试运行」 | 试运行是**规则的状态**，不是**一次执行的状态**；与 V419 的执行台账触发器同一纪律 |
| **不给 UPDATE / DELETE 授权**（只给 SELECT, INSERT） | 后台任务没法改执行记录 | 执行历史本来就**不该**被改；少授比「授了再靠触发器兜」更省事 |

## 文件清单

**迁移**

- `backend/services/platform/src/main/resources/db/migration/V422__automation_rule_run.sql`
- `backend/services/identity/src/main/resources/db/migration/V422__platform_automation_rule_run_permission.sql`

**后端**

- `backend/services/platform/src/main/java/com/starcon/platform/AutomationRuleRunModels.java`
- `backend/services/platform/src/main/java/com/starcon/platform/AutomationRuleRunService.java`
- `backend/services/platform/src/main/java/com/starcon/platform/AutomationRuleRunController.java`

**后端测试**

- `backend/services/platform/src/test/java/com/starcon/platform/PlatformAutomationRuleRunIntegrationTest.java`（19 例）
- `backend/services/platform/src/test/java/com/starcon/platform/PlatformAutomationRuleRunTenancyIntegrationTest.java`（9 例）

**前端**

- `apps/web/src/app/automations/runs/page.tsx`
- `apps/web/src/components/automations/automation-run-model.ts`
- `apps/web/src/components/automations/automation-run-workspace.tsx`
- `apps/web/src/components/automations/automation-run-model.test.ts`（18 例）
- `apps/web/src/app/api/platform/automation-run-allowlist.test.ts`（6 例）
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 `services[]` 元素**，既有条目未动）

**契约（codegen 产物，未手改）**

- `packages/api-contracts/src/contracts/platform.ts`
- `packages/api-contracts/src/platform.openapi.json`

**台账**

- `docs/product/capability-blueprint/module-progress.md`（**只改 18.3.2.1 那一行**）

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | EXIT=0 |
| L1 模块闸 | `cd backend && ./mvnw -pl services/platform -am test -Dtest='PlatformAutomationRuleRun*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | `Tests run: 28, Failures: 0, Errors: 0` BUILD SUCCESS |
| 前端模块 | `pnpm vitest run apps/web/src/components/automations/automation-run-model.test.ts apps/web/src/app/api/platform/automation-run-allowlist.test.ts` | 2 文件 / **24 例全绿** |
| 全量 allowlist 回归 | `pnpm vitest run apps/web/src/app/api` | **284 文件 / 1602 例全绿** |
| 类型 | `pnpm typecheck` | 3/3 successful |
| Lint | `pnpm lint` | 3/3 successful |
| 格式 | `pnpm exec prettier --check <本车道 6 个前端文件>` | All matched files use Prettier code style |

多租户三条断言（`PlatformAutomationRuleRunTenancyIntegrationTest`，真 Testcontainers PostgreSQL 16、
**FORCE** RLS、连接池压到 1 条）：

1. **两租户互不可见** —— 列表 / 按主键直查 / 逐步明细直查 / 事务外零行，四路都验；
2. **伪造租户上下文无效** —— 事务外写入被**租户触发器**当场拒 + 在 A 的上下文里写 B 的租户号被
   **RLS** 当场拒 + **伪造的 `X-Tenant-Id` 头改不动会话租户**（行仍落在**会话租户**名下，B 看不见）；
3. **连接池归还后不串租户** —— 固定 1 条连接交替 5 轮，SQL 直查与端点两路都验。

另有独立断言钉住 **FORCE** RLS（不是只 ENABLE）、「只追加台账不可改不可删」、
「跨租户的规则挂不上执行日志（复合 FK 兜底）」。

## 后续待办

1. **18.3.2「执行」引擎未交付** —— 本模块只记录与编排，不执行。执行器接通后：
   `platform_automation_rule_run` 的 `source` 会出现 `ENGINE`，重跑请求才可能从
   `NOT_ATTEMPTED` 变成真实结论。**在此之前重跑永远返回「未执行」** —— 这是设计，不是缺陷。
2. **18.3.2.2 规则效果未交付** —— 本模块提供**执行明细**（事实），聚合报表 / 误触发反馈 / 优化建议归它。
3. **1 工作台告警未交付** —— 「连续失败要告警」归 1 系统；本模块只产出执行事实。
4. ⚠️ **导航注册缺口**：`packages/ui/src/lib/task-pages.ts` **刻意未登记** `/automations/runs`
   （多条车道并发改同一文件必然冲突）⇒ 待调度者统一处理。
5. ⚠️ **条件引用无 FK** 是有意为之（见「决策 4」）；若将来 18.3.1.1 改成条件软删除，
   可以再考虑补 FK —— 现在补会把历史日志锁死。
