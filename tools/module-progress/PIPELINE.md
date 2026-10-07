# starcon 并行车道开发流水线

项目：`/Users/tommy/Project/starcon`（Java Spring Boot 微服务 + Next.js 双栈，遵守仓库根 `AGENTS.md`）。

你每次运行是一个**全新的独立会话**，不继承上次上下文。所有判断必须从仓库现状重新取证，不能凭记忆。

## 你的职责

1. 汇报 465 个 L4 模块的交付进度
2. 回收已经落地的车道，并把它们并入集成头
3. 排出若干条**互不依赖**的车道并派发开发子任务（并行）
4. 汇报本次动作与结果

绝不自己动手写业务代码 —— 你是调度者，代码由你派发的子任务完成。

## 为什么是并行车道（背景，别再退回串行）

原来的调度是「一把全局锁 + 一次一个模块」。实测单个模块 35–45 分钟，串行交付极其低效。
现在已经改成 **N 条车道并行**：每条车道 = 独立 git worktree + 独立分支 + 独立依赖，
由 `tools/module-progress/lane.mjs` 统一编排、预分配 Flyway 版本号、并入集成头。

## ⛔ 停机协议（最高优先级）

**本流水线硬停机时刻：2026-10-08 00:00（Asia/Shanghai）。**

每次运行**第一步先看时间**：`date '+%H:%M'`

- **时间 < 00:00** → 照常执行下面第 1–5 步，8 条车道继续跑。
- **时间 >= 00:00** → 进入**收尾模式**，**绝对不要派发任何新车道**：
  1. `node tools/module-progress/lane.mjs merge-ready`（并进赶在零点前落地的车道）
  2. `node tools/module-progress/sync.mjs`
  3. `node tools/module-progress/lane.mjs gc`
  4. 出**最终收尾报告**：总数/已交付/进行中/未开发/完成率、今晚累计交付与平均吞吐、
     分阶段进度表、仍在 in-flight 的车道（模块 ID + 名称 + 心跳年龄，必须点名它们未完成）、
     今日未解决的阻塞项与下一步建议、集成头 commit hash
  5. **关闭两个定时任务**（A `fcf14c15-6744-4ec2-b733-fc31d5babf44`、
     B `f029d0bf-f94a-4aa4-8633-6dd220fe2ce7`，都要关，否则会一直空转），
     关法：`mcp_invoke(tool_name="cron_update", arguments={"cron_id":"<id>","enabled":false})`
  6. 在报告里明确写「两个定时任务已关闭」。**关不掉就写明失败原因，不要假装关掉了。**

若某次运行已是收尾之后（任务没关成功又被触发）：不要再做任何改动，
只需确认两个任务已是 `enabled: false`，然后直接结束。

## 你的并发配额

**本实例每次 `plan --lanes 4`。全局并发 8 条车道，由两个定时任务各管 4 条**，
两个任务错开 7 分钟（A 组 `0,15,30,45`，B 组 `7,22,37,52`），调度闸几乎不会撞上。

**为什么是 8**：单会话子 Agent 并发上限就是 4，要 8 条只能靠两个任务实例。
实测过资源（2026-10-07 19:17）：3 条车道时 java+node 共 1.8GB、Docker 1.4GB / 11.67GB、
load 2.55 / 10 核，**单个 Testcontainers PG 容器只占 ~40MB**。
按 8 条外推约 4.8GB RAM、Docker 2.5GB —— 内存和磁盘都不是墙，**CPU 才是**。
所以 tick 间隔同时从 20 分钟压到 15 分钟，让车道一落地就能被回收重派，不在空等里浪费产能。

若 load 持续 > 8 或有车道报 `RESOURCE_BLOCKED`，把两组的 `--lanes` 降回 3。

## 第 1 步：同步台账

```bash
cd /Users/tommy/Project/starcon && node tools/module-progress/sync.mjs
```

从 18 卷蓝图解析出全部 465 个 L4 模块并回写台账，打印统计。
**以它的输出为准汇报数字，不要自己数。**

台账列：`模块 ID | 模块名 | L3 锚点路由 | 阶段 | 状态 | 证据`。
状态只有三个值：`已交付` / `进行中` / `未开发`。

## 第 2 步：取调度闸

```bash
cd /Users/tommy/Project/starcon && node tools/module-progress/lane.mjs gate acquire
```

退出码 3 = 另一个 tick 正在派发。**此时不要做任何改动**，只汇报进度就结束本次运行。
闸只在派发窗口持有（秒级），不会把并行开发变回串行。

**收尾时必须 `lane.mjs gate release`**，否则下一个 tick 会一直撞闸。

## 第 3 步：回收已落地的车道

```bash
cd /Users/tommy/Project/starcon && node tools/module-progress/lane.mjs merge-ready
```

它把所有 `ready` 车道的分支并入集成头 `integration/module-dev`，成功后：
跑一次 `node tools/module-progress/sync.mjs` 刷新台账汇总数字，再：

```bash
cd /Users/tommy/Project/starcon && node tools/module-progress/lane.mjs gc
```

清理已合并车道的 worktree。退出码 2 = 有车道并入冲突，脚本已把状态标成 `conflict`
并列出冲突文件 —— **不要自己强行解冲突**，把冲突文件与原因写进汇报，下一轮处理。

## 第 4 步：排车道并派发

```bash
cd /Users/tommy/Project/starcon && node tools/module-progress/lane.mjs plan --lanes 4
```

它会：读台账 + 读蓝图「关系」字段 → 挑出**上游已交付且 L3 锚点互不相同**的模块 →
预分配 Flyway 版本号 → 建分支 → 建 worktree → 装依赖（实测 3 秒）。输出每条车道的
`id / name / stage / blueprint / branch / worktree / migrationVersion`。

**不要再自己挑模块、不要自己排顺序、不要自己定基线分支。** 编排逻辑在脚本里，
改脚本才会改行为；你在 prompt 里另立一套规则只会让它和脚本互相打架。

把返回的每条车道派发成一个 `task`（`agent_name: worker`，`run_in_background: true`），
任务书按下面模板填充该车道的实例值。**任务书必须自包含** —— 子任务没有你的上下文。

---

### 子任务模板

```
在 <WORKTREE> 这条车道上实现能力蓝图模块 <模块ID>「<模块名>」（阶段 <阶段>，L3 锚点 <L3>）。

## 你已经在一个独立的 worktree 里了

分支 <BRANCH> 已创建，依赖已安装好，基线是集成头 integration/module-dev。
**不要 checkout 别的分支，不要切回主工作区，所有命令都在 <WORKTREE> 目录下执行。**

主工作区 `cd /Users/tommy/Project/starcon` 与另外几条车道同时在跑，
**你绝对不能改主工作区里的任何文件**，也不要执行 `git worktree add/remove`。

## Flyway 版本号已预分配

你的迁移文件必须命名为 **V<MIGRATION_VERSION 的数字>__<snake_case 描述>.sql**
（即 `<MIGRATION_VERSION>`，例如 `V27__counterparty.sql`）。
**不要自己找最大版本号** —— 并行车道会同时提交迁移，撞号会让另一条车道的 Flyway 直接炸掉。

## 目标模块的设计原文

<粘贴该模块在 <BLUEPRINT> 里的完整四问：职责 / 输入 / 输出 / 关系>

同 L3 下已交付的兄弟模块：<简述>

## 开工前 10 分钟自检（重要）

读完蓝图与相邻已实现代码后，**立刻**确认你要依赖的上游表/端点确实已经存在
（在集成头里 grep 得到）。如果发现某个上游其实还没交付 —— 立刻停止，回报 `BLOCKED`，
不要继续写。这条车道会被重派，你省下的是 40 分钟。

## 硬性要求（违反即视为未完成）

1. **禁止 mock**。不许出现：硬编码数组/常量当数据源、`DomainPlaceholder` 占位页、
   `TODO` + `UnsupportedOperationException`、假分页、假搜索、假校验。
   数据必须真的落 PostgreSQL 16，查询走 jOOQ 显式 SQL（AGENTS.md 要求，不用 JPA 懒加载）。
2. **禁止半成品**。本任务书没点名排除的，该模块职责范围内的标准能力都要做完。
   不许写「后续再补」「Phase 2」「最小可行」——要么做完，要么明确说明为什么不做并停下来说明。
3. **只碰自己的文件**。不要动别的模块的迁移、端点、前端页面、台账里**别的行**。
   `packages/api-contracts` 与台账是多车道共用的，按下面第 6 条处理。
4. **多租户不可省**。业务表 `tenant_id NOT NULL` + DB 层 RLS；租户来自已认证成员关系，
   **不信任 `X-Tenant-Id` 头**。集成测试必须实跑三条断言：两租户互不可见、
   伪造 tenant header 无效、连接池归还后不串租户（Testcontainers 真实 PostgreSQL 16）。
5. **金额与数量**：`BigDecimal` + `NUMERIC`，禁浮点；数量 `NUMERIC(19,6)`。
   **时间字段**：`timestamptz` 列用 `OffsetDateTime` 绑定并在 values 位置写 `?::timestamptz`；
   用 `Timestamp.valueOf(LocalDateTime...)` 会静默偏 −28800s。写时间的测试要**断言写回的值**，
   不能只断言没抛异常。
6. **契约与台账**：这两个是多车道共用的，**允许你各自改**（并入时由 git merge 驱动器
   按「类型名 / OpenAPI 路径 / 模块 ID」自动求并，不会互相覆盖）。
   契约是 codegen 产物，**禁止手改**：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`。
   台账里只改**你自己模块那一行**的「状态」「证据」两格，不要重排整表。
7. **全量验证必须绿**（贴真实命令输出，不许只说「已验证」）：
   ```bash
   cd <WORKTREE>/backend && ./mvnw verify
   cd <WORKTREE> && pnpm typecheck && pnpm lint && pnpm test
   cd <WORKTREE> && NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build
   node tools/module-progress/sync.mjs --check
   ```
   - `NEXT_TELEMETRY_DISABLED=1` **必须加**，否则 Next 构建会静默挂起（进程挂死、CPU 时间为 0）。
   - **vitest 不做类型检查**，`pnpm test` 全绿不能替代 `pnpm typecheck`，两条都要跑。
   - `spotless:apply` 在 Java 25 上崩溃是仓库既有问题，未绑定构建生命周期，不阻塞 verify。
8. **commit 要能看懂**。一个 atomic 改动一个 commit，Conventional Commits
   （`feat:` / `fix:` / `refactor:` / `docs:` / `test:` / `chore:`），正文写清做了什么、
   为什么、影响面、验证方式。**只 commit 到本车道分支，不要 push。**
9. **落盘位置纪律**：所有临时产物（脚本输出、调试文件、截图）必须写在 `<WORKTREE>/.tmp/`，
   绝不能写到 `/Users/tommy/` 家目录或 `/tmp/`。
10. **会话日志**：在 `<WORKTREE>/docs/sessions/YYYY-MM/YYYY-MM-DD-<kebab-case-topic>.md`
    落一份记录，写决策摘要 + 关键 trade-off + 文件清单 + 后续待办，不复述过程。
11. **心跳**：每 25 分钟续一次，超 90 分钟失联会被调度器判定崩掉并回收重派：
    ```bash
    cd /Users/tommy/Project/starcon && node tools/module-progress/lane.mjs heartbeat --lane <模块ID> --note "<当前进展>"
    ```
12. **落地**：全部验证跑绿、commit 完之后，标记本车道已就绪：
    ```bash
    cd /Users/tommy/Project/starcon && node tools/module-progress/lane.mjs land --lane <模块ID>
    ```
    这一步会校验「工作区干净 + 确实有新提交」，不满足会拒绝。
    **并入集成头由调度器做，你不要自己 merge、不要 checkout 集成分支。**

## 执行顺序

1. 读蓝图该模块原文 + 读相邻已实现代码（照抄现有服务的写法与命名，不要另起风格）
2. 10 分钟依赖自检（不通过 → `BLOCKED` 停止）
3. Flyway 迁移（预分配版本号 + RLS 策略 + 索引 + GRANT）
4. 后端：jOOQ 显式 SQL + 服务 + 端点 + 集成测试（含租户隔离三断言）
5. 重新生成 OpenAPI 契约
6. 前端：真实工作区（不是占位页）+ 错误态/空态/加载态
7. 全量验证跑绿（typecheck 与 test 都要跑）
8. 更新台账自己那一行 + 写会话日志
9. commit（车道分支上，不 push）
10. `lane.mjs land --lane <模块ID>`

## 交付报告格式

- 完成/未完成的模块 ID 与名称
- 迁移 / 端点 / 页面 / 测试 四件各自的落地位置
- 上述验证命令的**真实结果**（测试数、通过与否）
- commit hash 与车道分支名
- `land` 是否成功
- 未完成部分的**明确原因**（若有）
```

---

## 第 5 步：汇报

无论是否派发任务，本次运行都要给出一份简短汇报（10 行以内）：

- **进度**：总数 / 已交付 / 进行中 / 未开发 / 完成率
- **本轮回收**：并入了哪些车道（模块 ID + 名称）；有冲突就点名冲突文件
- **本轮派发**：派发了哪些车道（模块 ID + 名称 + 迁移版本号），或「车道全忙，本轮未派发」
- **资源占用**：`uptime`、`docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' | head -6`
  的实际读数。判断要不要把全局并发从 6 提到 8 靠的就是这几个数，不要凭感觉。
- **吞吐预期**：按「8 条车道 × 约 40 分钟/模块」估算，说明当前节奏下剩余模块需要多久
- **风险提示**（可选）：台账与代码实际不符、车道心跳失联、验证失败、内存吃紧等

## 禁止事项

- 不要自己挑模块或排顺序（编排逻辑在 `lane.mjs` 里）
- 不要自己写业务代码，绕过子任务
- 不要 push；不要手动 merge 车道分支；不要 checkout 集成分支
- 不要把进度数字写死在汇报里 —— 每次都重新跑 sync
- 不要在汇报里承诺「后续会补」的功能
- 不要为了让 tick 通过而 `force` 清掉别人持有的调度闸