# 18.3.1.1 规则列表（卷 18 集成开放与智能自动化 → L3 18.3.1 规则管理 → 第一格）

> **本车道经历了三次会话。** 前两任 worker 的会话都因**网络错误**
> （`net::ERR_CONNECTION_CLOSED` / `net::ERR_HTTP2_PING_FAILED`）在写代码途中被中断，
> 两任都只做了勘察、**0 commit** ⇒ 工作区干净、无成果丢失、也无半截改动需要接续。
> 调度者代写了 `HANDOFF-18.3.1.1.md`（已复核的依赖自检结论 + 照抄模板路径 + 白名单写法）。
> **本篇（第三任）按交接说明的「先写迁移、再写服务、再写测试」顺序推进**，
> 并把**分阶段 commit**当保命纪律执行（迁移 / 后端主体 / 测试 / 契约 / 前端 / 台账各一次提交）。

## 决策摘要

交付蓝图 18.3.1.1「规则列表」的**七项**点名能力（规则名称 / 触发事件 / 条件 / 动作 /
优先级 / 启用范围 / 试运行开关 / 执行统计）与两项输入（增删改、启用停用、排序），
并把加粗句 **「规则要能看出最后一次执行结果，否则坏规则静默失败无人察觉」**
**在数据层做成物理事实**。

- **迁移**：`V419__automation_rule.sql`（platform，五表）+ `V419__platform_automation_rule_permission.sql`
  （identity，四个权限码）。版本号由调度者预分配（V419；另一条车道 18.3.3.1 占 V420），
  **未自行查找最大版本号**。
- **端点**：15 条挂 `/platform/automation-rules`，jOOQ 显式 SQL。
- **页面**：`/automations/rules`，**真实工作区**（非占位页）。
- **契约**：`packages/api-contracts` codegen 重生成（其余 8 个服务的契约**逐字节不变**）。

🔴 **诚实模式**：18.3.2「执行」是**下游**、尚未交付 ⇒ 本模块**不执行规则**。
页面**不提供**「执行规则」按钮（一个点了必然失败的按钮会让运营以为「点一下就能跑」），
端点如实答「执行引擎未交付」；执行台账的 `source` 列如实区分 ENGINE / MANUAL
（现阶段只可能是 MANUAL，**不伪造**「引擎跑过了」）。

## 关键 trade-off

### 1. 「最后一次执行结果」是**三件套**，不是一个布尔位

`last_run_at` / `last_run_status` / `last_run_failure_reason` / `last_run_execution_id`
四列直接落在**规则档案自己那一行**上 —— 打开规则列表就能看到「上次什么时候跑的、成了还是败了、
为什么败」。

**取舍**：早先想过只存 `last_executed boolean` + 一张独立的执行表（更省事），
但那样「为什么败」就得跨表现场拼；蓝图要的是**在档案上**看得见，
且页面列表要能**一次查询**渲染出来（多表 join 在列表场景里既慢又容易漏）。
⇒ 统计**快照冗余**存在规则行上，执行台账（append-only）保留完整明细。

### 2. 坏规则：把「连续失败」做成**可被筛出来的反面判据**

`consecutive_failure_count` + `consecutive_failure_threshold` + `failing` 三件套，
DB 层 CHECK 把 `failing` 与「连续失败数 ≥ 阈值」钉成**永远一致**，
`failing_since` 亦然（不是坏规则就不许有那个时刻）。

**取舍**：也可以把「坏规则」做成一个**枚举状态列**（NORMAL / FAILING），
但那样它就会变成「另一个可以随手写的状态」—— 与统计口径漂移。
⇒ 标记由统计**推导**、由 CHECK **约束**，页面另有 `health`（推导值）负责展示。

🔴 **更关键的一条**：登记执行结果的 SQL **只更新统计列**，**不碰** `enabled` / `status`。
「悄悄地把规则状态改回正常」在本模块里**物理上不成立** ——
一条被停用的坏规则不会因为一次执行失败或成功而自己变回启用。
（这条已写成测试：停用 → 登记失败 → `status` 仍是 `DISABLED`、`enabled` 仍是 `false`。）

### 3. 试运行：独立状态 + 三重钉死

`dry_run` 是**自己的列**，与 `enabled` / `status` 互不影响（一条规则可以「启用 + 试运行」）。

「试运行的规则不产生真实副作用」由**三道**钉死：
① 服务层：登记「产生了真实副作用」或「已执行动作数 > 0」⇒ **409**；
② 表级 CHECK：`dry_run ⇒ executed_actions = 0 且 side_effects_applied = false`；
③ 触发器：执行行的 `dry_run` **必须**与规则档案的 `dry_run` 一致
（否则「试运行」就成了可以随手标上的标签）。

⇒ **「试运行的规则跑过 12 次」与「试运行的规则一个动作都没真跑」这两件事不可能被同时误读。**

### 4. 启用范围是**真约束**，不是展示用字段

范围表（租户 / 店铺 / 仓库）**参与过滤**：
`GET /rules/applicable` 的 SQL 真的 JOIN 范围表（租户范围 / 指定店铺 / 指定仓库三种命中），
`POST /rules/{id}/preview` 也按范围判生效。
范围行还有 CHECK：店铺 / 仓库范围**必须**带引用，租户范围**不得**带引用 ——
「范围是店铺」却没写是哪家店 = 没有范围，在数据层禁掉。

**取舍**：范围里的店铺 / 仓库是**只读引用**（上游 channel / warehouse 服务），
不建跨库外键、不抄上游档案（AGENTS.md「模块边界纪律」）。
代价是上游那个对象被删了，这条范围会变成「指向一个不存在的对象」——
本仓已有的一致口径（18.2.3.1 的 `channel_shop_id`）也是这么处理的。

### 5. 排序是**全序**，不是「优先级 + DB 返回顺序」

列表排序固定 `ORDER BY priority, created_at, id`。`id` 是全局唯一主键 ⇒
相同优先级的规则次序**确定**（不靠 DB 返回顺序 —— 那是未定义行为，两次查询可能不同）。

**取舍**：也可以只按 `priority` 排（看起来更「纯」），但那样同优先级的次序就飘了，
运营点「上移 / 下移」时看到的顺序会莫名其妙变。⇒ 加两级兜底排序键。

### 6. 预演（本模块新增的一个端点）：**求值但不执行**

`POST /rules/{id}/preview` 按样本载荷真的求值条件（逐条给出命中与否与原因）、
真的按范围判生效、给出**有序的动作计划**，`sideEffectsApplied` 恒为 false。

**取舍**：严格说，「样本测试」是 18.3.1.2 编辑器的职责。
但 18.3.1.1 有一条硬需求「启用范围要能被**过滤条件实际用上**」——
没有求值器就没法证明范围与条件真的在起作用。
⇒ 取**只读、零副作用**的那一半（求值 + 计划），把「可视化编辑 + 保存样本」留给 18.3.1.2，
并在该端点的能力声明里写明「本模块只求值，不执行」。

### 7. 一个被测试逼出来的服务层缺陷

`GET /rules/{uuid}/executions` 最初对「不是你的规则」返回 **200 + 空列表** ——
RLS 把行挡住了，所以没有数据泄漏，但「这条规则不是你的」与「这条规则还没执行过」
被混成了同一件事（200 + 空数组）。已改为 **404**（`executionRows` 先确认规则在当前租户名下存在）。

## 文件清单

**后端（platform）**

- `backend/services/platform/src/main/resources/db/migration/V419__automation_rule.sql`（新，五表 + RLS + 触发器 + GRANT）
- `backend/services/platform/src/main/java/com/starcon/platform/AutomationRuleModels.java`（新，传输模型）
- `backend/services/platform/src/main/java/com/starcon/platform/AutomationRuleService.java`（新，服务层，jOOQ 显式 SQL）
- `backend/services/platform/src/main/java/com/starcon/platform/AutomationRuleController.java`（新，15 条端点）
- `backend/services/platform/src/main/java/com/starcon/platform/WarehouseScopePort.java`（新，warehouse 只读端口）
- `backend/services/platform/src/test/java/com/starcon/platform/AutomationRuleIntegrationTest.java`（新，15 例）
- `backend/services/platform/src/test/java/com/starcon/platform/AutomationRuleTenancyIntegrationTest.java`（新，7 例）

**后端（identity）**

- `backend/services/identity/src/main/resources/db/migration/V419__platform_automation_rule_permission.sql`（新，四个权限码 + 存量 OWNER 补权）

**契约**

- `packages/api-contracts/src/contracts/platform.ts`（codegen 重生成）
- `packages/api-contracts/src/platform.openapi.json`（codegen 重生成）

**前端**

- `apps/web/src/app/automations/rules/page.tsx`（新，路由）
- `apps/web/src/components/automations/automation-rule-model.ts`（新，模型层 + 表单校验）
- `apps/web/src/components/automations/automation-rule-workspace.tsx`（新，真实工作区）
- `apps/web/src/components/automations/automation-rule-model.test.ts`（新，14 例）
- `apps/web/src/app/api/platform/automation-rule-allowlist.test.ts`（新，5 例）
- `apps/web/src/lib/api-proxy.ts`（**只新增**一条 `prefix: 'platform/'` 元素）

**台账 / 会话**

- `docs/product/capability-blueprint/module-progress.md`（**只改** 18.3.1.1 那一行的「状态」「证据」两格）
- `docs/sessions/2026-10/2026-10-31-18-3-1-1-automation-rule.md`（本篇）

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | EXIT=0 |
| L1 模块闸 | `cd backend && ./mvnw -pl services/platform -am test -Dtest='AutomationRule*'` | **Tests run: 22, Failures: 0, Errors: 0**，BUILD SUCCESS |
| 类型检查 | `pnpm typecheck` | 3/3 successful |
| Lint | `pnpm lint` | 3/3 successful（本模块文件零 warning） |
| 格式 | `pnpm exec prettier --check`（只查本车道改动过的文件） | All matched files use Prettier code style |
| 前端单测 | `pnpm vitest run <本模块两个测试文件>` | 19 例全绿 |
| **全量 allowlist 回归** | `pnpm vitest run apps/web/src/app/api` | **282 文件 / 1591 例全绿**（改了共享白名单，必须跑全量） |

**白名单零命中取证**：发车前用一次性脚本 `.tmp/api-proxy-zero-hit.mjs`（不入库）
**用 TypeScript 编译器解析** `apps/web/src/lib/api-proxy.ts` 里 `services` 变量的**真实数组字面量**，
按 `resolve()` 的同构口径实测：
本模块 **14** 条路径对既有 **277** 条元素**零命中**；
新元素对 **19** 条别人已交付的形状**零命中**；
**5** 条负向形状全部被拒（裸路径 / `unknown` / `not-a-uuid` / 多一段 /
**`rules/{uuid}/execute`** —— 最后一条刻意不放行：18.3.2 执行引擎尚未交付，
放行它会让界面以为「点一下就能跑」）。

**多租户三条断言**（跑在**本模块自己的五张表**上，真 Testcontainers PostgreSQL 16、FORCE RLS、
连接池压到 1 条）：

1. 两租户互不可见 —— 列表 + 按主键直查 + **五张子表**逐张直查 + 事务外零行；
2. 伪造 `X-Tenant-Id` 无效 —— 行仍落在**会话租户**名下（不是只断言「看不见」）；
3. 连接池固定 1 条交替 5 轮不串租户。

## 后续待办

- **下游 18.3.1.2 规则编辑器**：可视化编辑（选触发事件 → 配条件 → 编排动作 → 测试与保存）。
  本模块已备好条件 / 动作的受控词表与**只读预演**端点，编辑器可直接复用。
- **下游 18.3.2.1 执行日志**：全局执行明细、上下文查看、手动重跑。
  本模块的 `platform_automation_rule_execution` 是**单条规则**的执行历史与登记入口；
  全局日志与重跑归 18.3.2.1（本模块**刻意没有**「重跑」端点 —— 重跑要产生真实副作用，
  而执行引擎未交付）。
- **下游 18.3.2.2 规则效果**：成功率 / 误触发率 / 优化建议。本模块的统计是**规则档案上的快照**
  （够看出坏规则），聚合报表归 18.3.2.2。
- **导航注册缺口**：`packages/ui/src/lib/task-pages.ts` **刻意未登记** `/automations/rules`
  （多条车道并发改同一文件必然冲突）⇒ 待调度者统一处理。
- **规则执行引擎**（18.3.2 卷级）：引擎到位后，`POST /rules/{id}/executions` 的 `source=ENGINE`
  才有来源；届时「登记执行结果」这条人工入口应保留（外部侧完成的情况仍需人工登记）。
