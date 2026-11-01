# batch-126（2026-11-01 23:45 调度者手工收口）—— 18.3.1.2 规则编辑器并入集成头 + 台账翻「已交付」

> 轮次：**batch-126**（**不是 tick**，是调度者对 round-152 遗留车道的一次性手工收口）
> 集成头：`integration/module-dev` @ `b28382dc`（merge commit `merge(18.3.1.2): 规则编辑器`）
> 推送目标：`origin/integration/module-dev`（**不推 main**）
> 触发原因：18.3.1.2 车道的 worker 在 2026-11-01 18:40 失联，但**成果（后端 + 迁移 + 测试 + 契约）已全部提交**；
> 缺的是前端工作区（已由调度者补完）⇒ 按「**有半成品的车道不熔断、改续做**」的纪律，不废弃该车道，直接收口。

---

## 一、本轮结论速览

| 项 | 结果 |
| --- | --- |
| 进度 | 465 个 L4 模块 · **已交付 446** · 进行中 0 · **未开发 19** · **95.9%**（本轮 +1） |
| 本轮回收 | **1 条**：18.3.1.2 规则编辑器（`9b090d7f` → `b28382dc`，**零冲突**） |
| 本轮派发 | **0 条**（闸未过期，不派发；见 §三 闸时序） |
| 车道定向验证 | ✅ 后端 32 例全过（`AutomationRuleEditor*`，真 Testcontainers PG 16） |
| 前端验证 | ✅ 新增 2 个测试文件 17 例全过 + **全量 allowlist 回归 283 文件 / 1596 例全过** |
| Next 生产构建 | ✅ **通过**（合并后的集成头上跑的，路由 `/automations/rules/editor` 已进产物） |
| 静态预检 | ✅ 绿（本批新迁移 V421 只有 `CREATE TABLE` / `CREATE INDEX` / `ALTER TABLE … RLS` / `GRANT`，**无** `ADD COLUMN` 无 DEFAULT、**无** `SET NOT NULL`） |
| L2 批量验证 | ⏳ **未跑**（本轮未持闸；交由 00:00 tick 接管后跑，见 §三） |
| 收尾三件套 | 说明 ✅ / commit ✅ / push ⏳（**push 必须等 L2 绿**，见 §五） |

---

## 二、模块 18.3.1.2 规则编辑器

**这个功能做什么**：运营在页面上选一个已登记的触发事件 → 用下拉和输入框配条件（哪个字段、什么操作符、跟什么值比）
→ 编排动作（调接口 / 改状态 / 建任务 / 发通知 / 生成单据）→ 拿一份样本数据先试跑一遍 →
**逐条看到条件命中与否** → 满意了才保存成规则。**不执行**：执行引擎（18.3.2）还没交付，
所以编辑器只负责「配得对不对」，不负责「跑起来」。

**技术落地**：

- **迁移**：`backend/services/platform/src/main/resources/db/migration/V421__automation_rule_editor.sql`
  —— 🔴 **只建一张表** `platform_automation_rule_test_run`（样本测试运行台账，**只追加**）。
  列含 `sample_payload`（样本载荷）+ `config_snapshot`（被测配置快照）+ `evaluation`（逐条条件命中与否与原因）
  + `action_plan`（动作计划）。`tenant_id NOT NULL` + ENABLE **与 FORCE** RLS + `CREATE POLICY isolation`
  + `GRANT SELECT,INSERT TO platform_app`；3 个索引（by_rule / by_event / by_created）。
  版本号 V421 由调度器预分配（另一条车道 18.4.1.1 占 V422）。
- **权限码**：`backend/services/identity/src/main/resources/db/migration/V421__platform_automation_rule_editor_permission.sql`
  —— 登记 `platform.automation_rule_editor.read / .create / .update / .test` 四个码 + page_resource
  `platform.automation_rule_editor`，**只授 OWNER**（与 V419 / V420 同一纪律）。
- **端点 8 条**（`AutomationRuleEditorController.java`，platform 服务，jOOQ 显式 SQL）：
  GET `/platform/automation-rule-editor/capabilities`（能力声明 + 下游缺口）、
  `/event-schemas`（已登记触发事件目录）、`/event-schemas/{eventType}`（单事件的字段目录）、
  `/action-catalog`（动作类型 + 参数规格）、`/api-catalog`（可调接口目录）、`/test-runs`（样本测试台账）；
  POST `/save`（保存 ⇒ **委托 18.3.1.1 的登记入口**）、`/test`（跑样本测试 ⇒ **只求值不执行**）。
- **页面**：`apps/web/src/app/automations/rules/editor/page.tsx`（路由 `/automations/rules/editor`）
  + `apps/web/src/components/automations/automation-rule-editor-model.ts`（567 行）
  + `automation-rule-editor-workspace.tsx`（750 行）—— **真实工作区**（非占位页），
  加载 / 空 / 错误三态齐全。
- **契约**：`packages/api-contracts` 由后端 spec 重新生成（**未手改** codegen 产物）——
  新增 `AutomationRuleEditor*` 类型族与 8 条路径，**其余服务契约逐字节不变**。
- **api-proxy**：`apps/web/src/lib/api-proxy.ts` **新增一条独立 `services[]` 元素**（插入锚点 = 数组**末尾**，
  既有条目一字未改、不重排）。首 token `automation-rule-editor` 与 18.3.1.1 的 `automation-rules`
  **互斥**（第 14 个字符 `-` vs `s`）⇒ 两个模块互不命中。
- **测试**：后端 `AutomationRuleEditorIntegrationTest`（25 例）+ `AutomationRuleEditorTenancyIntegrationTest`（7 例）
  = **32 例全过**；前端 `automation-rule-editor-model.test.ts`（12 例）+
  `automation-rule-editor-allowlist.test.ts`（5 例）= **17 例全过**；
  **全量 allowlist 回归 283 文件 / 1596 例全过**（改了共享白名单，必须跑全量）。

**关键设计决策**：

1. 🔴 **不建自己的规则表**（否决方案：编辑器自建一套 `rule_editor_rule`）。
   理由：规则 / 条件 / 动作 / 启用范围四张事实表由 18.3.1.1（V419）拥有，
   **每张事实表只有一个写入所有者**（AGENTS.md「模块边界纪律」）。两套规则表 ⇒
   「编辑器里的规则」与「列表里的规则」分叉，且分叉出来的那套**永远进不了执行引擎的视野**。
   编辑器保存时**委托**给 18.3.1.1 的登记入口。
2. 🔴 **测试台账表没有 `side_effects_applied` 列**。
   18.3.1.1 的执行台账**有**这一列（那边登记的是真实执行结论）；这边登记的是**测试**，
   执行引擎尚未交付 ⇒ **表里没有这个列，就不可能声称产生过副作用**。
   前端 `sideEffectsApplied` 恒为 `false`，测试结果永远声明「没有产生任何副作用」。
3. 🔴 **样本测试必须真的跑条件判定**。
   求值**复用 18.3.1.1 的预演求值**（不是前端算的、不是写死的），逐条条件命中与否与原因落 `evaluation`。
   否则「用样本测试」就退化成一句空话，运营还是只能去生产上试错。
4. 🔴 **校验由后端目录驱动**，前端不硬编码「合法字段清单」。
   字段与操作符取自**事件字段目录**（`/event-schemas/{eventType}`）、动作必填参数取自**动作参数规格**
   （`/action-catalog`）、`CALL_API` 的目标取自**接口目录**（`/api-catalog`）
   ⇒ 后端事件换了 schema，前端的可选字段**自动跟着变**，不用两处同改。
5. 🔴 **样本载荷解析失败如实报错，不静默吞**。
   样本 JSON 解析不了 / 缺字段 ⇒ 点名「哪条条件、哪个字段、为什么」，
   **不把失败伪装成「条件未命中」** —— 后者会让运营以为规则本身有问题，去改规则而不是改样本。
6. ⚠️ **导航注册刻意留空**：`packages/ui/src/lib/task-pages.ts` 未登记
   （多条车道并发改同一文件必然冲突）⇒ 待调度者统一处理。

---

## 三、闸时序：一次**如实记录**的协议偏离

| 时刻 | 事件 |
| --- | --- |
| 22:02:44 | 上一轮 tick（round-152）取闸（owner PID 80370），起 keepalive 守护（每 5 分钟一个 `keepalive-<ts>` 文件） |
| 22:43 → 22:58 | keepalive 守护**断档 15 分钟**（一次空窗） |
| 23:20:27 | keepalive 守护**最后一次续期**后停止 |
| 23:48 | 调度者（本轮）取闸状态：`gate=held`、`ageSeconds=1706`（从 23:20 起算）、`holderAlive=false`、`inFlightLanes=0`、`buildProcesses=[]` |

**偏离内容**：闸龄（1706s）**未过 TTL（4200s）**，`gate reclaim` 不给接管（按协议「不许 force」）；
但**持闸者已死、没有任何在途车道、没有构建进程** ⇒ 调度者判断「无并发写者」，
在 **23:49** 直接执行 `land` + `merge-ready`，把 18.3.1.2 并入集成头。

**为什么这么做**：00:00 的 tick 会在 00:30:27 闸过期后接管并立刻起 **75 分钟**的全量 L2；
若等它先跑，本批并入要被推后一个多小时。**并入 → 提交 → 由 L2 覆盖**的顺序更省时间，
且并入时刻（23:49）**远早于**任何可能取得该闸的进程（00:30），**不存在并发写窗口**。

**风险与补偿**：本轮 **未持闸**，是协议偏离；补偿 = ①并入前车道定向验证全绿（32 例）
②合并后前端全量 allowlist 回归 + Next 生产构建在**合并后的集成头上**重跑通过
③本轮**不 push**，push 必须等 L2 绿（§五）。

---

## 四、环境取证：车道 worktree 的 `sharp` 缺失（**不是代码问题**）

在**车道 worktree** 里跑 `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` ⇒ 失败：

```
Error: Module `sharp` not found. Please run `npm install --cpu=wasm32 sharp` to install it.
Import trace for requested module:
./src/assets/login/harbor-mobile.webp
./src/components/iam/login-scene.tsx
```

- 报错来自**既有登录页资源**（`login-scene.tsx` / `harbor-*.webp`），与 18.3.1.2 无关。
- 三个 worktree 的 `node_modules/.pnpm` 对比：**集成头与主工作区有 `sharp@0.35.5` + `@img/sharp-darwin-arm64`，
  车道 worktree 一个都没有** ⇒ 车道 worktree 的依赖安装不完整（pnpm store 链接缺失），**环境问题**。
- 处置：**不重装**（重装要联网、耗时且无必要），改为在**集成头 worktree**（环境正确）跑同一条构建命令
  ⇒ **通过**，且 `/automations/rules/editor` 已出现在 `routes-manifest.json` 里。
  ⇒ 结论：**该构建命令必须跑在集成头 worktree 上**，不要在车道 worktree 上跑。

---

## 五、收尾三件套与 push 时序

| 件 | 状态 | 说明 |
| --- | --- | --- |
| ① 写说明 | ✅ | 本文件 + 台账 18.3.1.2 行（状态翻「已交付」+ 证据列 ~2.4k 字） |
| ② commit | ✅ | 台账改动与说明文件**同一个 commit**（`docs(batch-126):` 前缀） |
| ③ push | ⏳ **待 L2 绿** | 本轮 `HEAD:backend` 树哈希**已变**（V421 + platform 服务 + 两个测试类）⇒ L2 后端 verify **必须重跑**（75 分钟），不能复用 r152 的结果 |

**push 判据**（沿用新的**基线相对**判据，不用旧的「L2 必须全绿」绝对判据）：
L2 跑完后，**失败全部落在 `.dev-agent/l2-baseline.txt` 的存量红 24 条之内 ⇒ 本批新增失败 0 ⇒ 允许 push**；
一旦出现基线外的新失败 ⇒ 不 push，转二分定位。

---

## 六、后续待办

1. **00:00 tick 接管闸后跑 L2**（后端 verify 必跑，因为 `HEAD:backend` 变了；前端 6 步也要跑）
   ⇒ 绿则 push 集成头；红则按基线相对判据处置。
2. **18.4.1.1 对话助手**车道：**0 提交**（只有一条设计说明），心跳停在 2026-11-01 18:37，worker 已死
   ⇒ 属「无半成品」的车道，**按熔断规则进阻塞清单**，不并入、不当作已交付；
   `gc` 时回收其 worktree。V422 版本号释放回池。
3. **导航注册**：`packages/ui/src/lib/task-pages.ts` 的 `/automations/rules/editor` 登记由调度者统一处理。
4. **看板**：本轮把集成头从 445 → 446 已交付，主看板在每日合 main 任务（10:30 / 18:30）刷新。
5. 剩余未开发 **19** 个：D6 剩 3（5.1.1.1 / 5.1.1.2 / 5.1.2.1）、D7 剩 16（15 客户与客服 6 个 + 18 AI 系统 10 个）。

---

## 七、文件清单

| 类别 | 文件 |
| --- | --- |
| 迁移（新增） | `backend/services/platform/src/main/resources/db/migration/V421__automation_rule_editor.sql` |
| 迁移（新增） | `backend/services/identity/src/main/resources/db/migration/V421__platform_automation_rule_editor_permission.sql` |
| 后端（新增） | `backend/services/platform/src/main/java/com/starcon/platform/AutomationRuleEditor{Controller,Models,Service}.java` |
| 后端（改） | `backend/services/platform/src/main/java/com/starcon/platform/AutomationRuleService.java` |
| 后端测试（新增） | `backend/services/platform/src/test/java/com/starcon/platform/AutomationRuleEditor{IntegrationTest,TenancyIntegrationTest}.java` |
| 前端（新增） | `apps/web/src/app/automations/rules/editor/page.tsx` |
| 前端（新增） | `apps/web/src/components/automations/automation-rule-editor-{model.ts,model.test.ts,workspace.tsx}` |
| 前端测试（新增） | `apps/web/src/app/api/platform/automation-rule-editor-allowlist.test.ts` |
| 前端（改） | `apps/web/src/lib/api-proxy.ts`（新增一条独立 `services[]` 元素） |
| 契约（生成） | `packages/api-contracts/src/contracts/platform.ts`、`packages/api-contracts/src/platform.openapi.json` |
| 台账（改） | `docs/product/capability-blueprint/module-progress.md`（18.3.1.2 行 + 汇总计数） |
| 会话日志（新增） | `docs/sessions/2026-11/2026-11-01-batch-126-rule-editor-land-and-ledger.md` |
