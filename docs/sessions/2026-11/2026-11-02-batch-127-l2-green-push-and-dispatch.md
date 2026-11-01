# batch-127（2026-11-02 00:00 tick）—— L2 r153 绿 ⇒ batch-126 首次 push 到 origin + 本轮派发 4 条 platform 车道

> 轮次：**batch-127**（tick = 2026-11-02 00:00:23 CST 触发）
> 集成头：`integration/module-dev` @ `6b8ae806`（L2 r153 的被验 HEAD）
> 推送目标：`origin/integration/module-dev`（**不推 main**）
> 本轮性质：**回收型 + 派发型**。本轮不并入新车道（L2 已经跑完，见 §四），但把上一轮遗留的 batch-126 交付**首次推上远端**，并派发 4 条新车道。

---

## 一、本轮结论速览

| 项 | 结果 |
| --- | --- |
| 进度 | 465 个 L4 模块 · **已交付 446** · 进行中 0 · **未开发 19**（本轮未变：4 条车道尚未并入） |
| 本轮 push | **10 个提交**推到 `origin/integration/module-dev`（含 batch-126 的 18.3.1.2 规则编辑器全链 + 协议修订 + 本说明 + 台账熔断注记） |
| 本轮交付到远端 | **18.3.1.2 规则编辑器**（上轮已合入集成头但**从未 push** ⇒ 本轮 L2 覆盖后才首次上远端） |
| 本轮派发 | **4 条**：`18.3.2.1` / `18.4.2.2` / `18.4.3.1` / `18.5.1.1`（platform 服务，L3 锚点互不相同），**全部已 L1 绿 + 已 `land`（state=ready）**，下一轮并入 + 批次 L2 |
| 熔断 | **18.4.1.1 对话助手**（连续失败 ≥2 次且分支零提交）⇒ 写 `.dev-agent/blocked-modules.md` + 台账「阻塞：」前缀，不再自动重派 |
| 静态预检 | ✅ 绿（本批新迁移只有 `CREATE TABLE` / `CREATE INDEX` / `ALTER TABLE … RLS` / `GRANT`，**无** `ADD COLUMN` 无 DEFAULT、**无** `SET NOT NULL`、**无** `ADD CONSTRAINT … CHECK`） |
| L2 批次验证 | ✅ **GREEN**（详见 §三）：后端 2460 例 **0 失败 0 错误**、前端 **9979 例**全绿、Next build 绿、格式闸修后绿 |
| 后端 verify 是否复用 | ❌ **未复用**（后端树变了）：r152 记的 `HEAD:backend` = `6d90606f`，本轮 `git rev-parse HEAD:backend` = `b15b8a98` ⇒ 后端代码有变更 ⇒ 重跑全量 verify（71 分钟） |
| 收尾三件套 | 说明 ✅（本文件）/ commit ✅ / push ✅ |

---

## 二、调度时序与闸（为什么这一轮是「接管闸」而不是「等闸」）

1. **闸是上一轮遗留的孤儿闸**：开工时 `gate status` = `held`，闸龄 2384s（TTL 4200s，未到接管窗口），`holderAlive=false`（在途车道 0、构建进程 0、owner PID 80370 已不存在、keepalive 守护停在 2026-11-01 23:20）。
2. **没有抢闸**：本机 `.dev-agent/dispatch.gate.token` 与闸 `owner` 里的令牌一致 ⇒ 本机仍是**合法持闸者**，直接 `lane.mjs gate touch` 续期（闸龄归零），**未使用 `--force`、未删闸目录、未 `reclaim`** ⇒ 零竞速窗口。续期守护 `keepalive-r153.sh`（PID 25530，每 300s 一次、有界 5400s、三重退出条件）保证 TTL 覆盖到 push 结束。
3. **节流闸**：上次落盘 2026-11-01 22:02:44，距今 116 分钟 > `THROTTLE_MIN=50` ⇒ 放行。
4. **温度闸**：`pmset -g therm` = 「No thermal warning level has been recorded」⇒ 正常（不判 STOP）。
5. **容量闸**：load 1min 4.25（阈值 12）、带 testcontainers 标签的 PG 容器 0（阈值 16）、在途车道 0（配额 6）⇒ 放行。
6. **孤儿容器清理**：`eager_aryabhata` / `skew-probe3` 两个**无 testcontainers 标签**的 PG 容器（无 established 连接、不被任何在途车道引用）⇒ `docker rm -f` 清理，为 71 分钟全量 verify 腾内存。

---

## 三、L2 r153 真实结论（7 步逐条退出码）

被验 HEAD = `6b8ae806`，`BACKEND_TREE=b15b8a98d44970a59b03a31c57ba38f98bd28191`（已写进 `.dev-agent/l2/l2-r153-summary.txt`，下一轮据此判断能否复用后端结论）。

| 步骤 | 退出码 | 耗时 | 实测结果 |
| --- | --- | --- | --- |
| `backend ./mvnw verify` | **0** | 4248s | `Tests run: 2460, Failures: 0, Errors: 0, Skipped: 0` · **BUILD SUCCESS** · Total time 01:10 h |
| `pnpm typecheck` | 0 | 54s | 3 packages successful |
| `pnpm lint` | 0 | 4s | 3/3 successful |
| `pnpm test` | 0 | 19s | **709 个测试文件 / 9979 例**全绿 |
| `pnpm --filter @starcon/web build` | 0 | 98s | Compiled successfully（408 个静态页） |
| `node tools/module-progress/sync.mjs --check` | 0 | 1s | 台账与蓝图一致 |
| `node tools/module-progress/format-gate.mjs` | 1 → **0** | 4s ×2 | 首跑红 1 条 → 修后绿（见下） |

**存量红 / 本批新增**：

- **存量红 0 条**。r152 的基线（`.dev-agent/l2-baseline.txt`，trade 18 errors + 6 failures = 24 条）在本轮**全部消失** —— 后端全量 2460 例 0 失败 0 错误。基线文件保留未删（删行 = 失败已被真正修好；本轮不确认那 24 条是否已被单独修掉，只确认「本轮 L2 无失败」）。
- **本批新增失败 0 条**（后端 / 前端 / 构建 / 同步检查全部绿）。
- **格式闸本批新增 1 条，已当场修掉**：`tools/module-progress/PIPELINE_CRON_PROMPT.md` 不合 prettier。取证：批次基线 `00658d46` 的同一文件**合规**、本轮工作区版本**不合规** ⇒ 是**本批引入**（本批的调度者协议提交 `f8f1fcc1` 重写 §0.0 嵌套列表 + `6b8ae806` 加 §3.3.1 表格时破坏了排版），不是存量债。修法按协议「在本车道自己的 worktree 里 `prettier --write` 后重新提交」执行 —— 该文件是**调度者自己拥有**的协议文件、4 条车道无一触碰它，故在集成头就地修（**只修这一个文件，未做全仓 `--write`**），改动量 `11 insertions / 11 deletions`（仅嵌套列表缩进 + 一张表的重排），修完**重跑格式闸 ⇒ `ok:true`，violations 为空**。
  - ⚠️ 该修复只动 `tools/` 下一个 Markdown 文件，**未改 `backend/` / `apps/` / `packages/` 的任何代码** ⇒ 后端 verify / typecheck / lint / test / nextbuild / sync-check 六步结论**全部继续成立**，只重跑了 format-gate 这一步（同一轮不重复跑其余六步）。

---

## 四、本轮并入决策：**不并入 4 条新车道**（按 cron 任务书「本轮不等它跑完」）

L2 r153 在 00:08 启动时，集成头里只有 batch-126 的 18.3.1.2；4 条新车道的代码**在各自 worktree 的分支上**，尚未并入。协议与任务书都要求「并入后的集成头状态必须被批次 L2 覆盖过才可 push」⇒ 本轮**不把未经验证的车道并进集成头**，它们以 `state=ready` 留在车道表里，下一轮 `merge-ready` 并入后由下一轮批次 L2 覆盖。

另附并入预演（只读，不改工作区）：用 `git merge-tree --write-tree` + 临时 commit 顺序预演四条车道，**合并本身无冲突**；但契约文件 `packages/api-contracts/src/platform.openapi.json` 走 union 驱动器时打印了「**走了行级兜底并集，请人工确认这个文件改对了**」⇒ 下一轮并入后**必须人工核对契约文件完整性**（四条车道都重新生成了 platform 契约）。这是「并入后必须跑批次 L2」的又一个具体理由。

---

## 五、本轮交付到远端的模块：18.3.1.2 规则编辑器（batch-126）

上轮 23:45 手工收口时已并入集成头（`b28382dc`）但**从未 push**；本轮 L2 覆盖后首次推上 `origin/integration/module-dev`。

- **迁移**：`backend/services/platform/src/main/resources/db/migration/V421__automation_rule_editor.sql`（只建一张表 `platform_automation_rule_test_run`，样本测试运行台账，只追加）+ `backend/services/identity/.../V421__platform_automation_rule_editor_permission.sql`
- **端点**：规则编辑器的控制器 / 服务 / 模型（`AutomationRuleEditor*.java`）
- **页面**：`/automations/rules/editor`（事件选择 / 条件编排 / 动作编排 / 样本测试 / 保存）
- **测试**：后端 32 例（`AutomationRuleEditor*`）、前端 17 例；全量 allowlist 回归 283 文件 / 1596 例全绿
- 详细设计见 `docs/sessions/2026-11/2026-11-01-batch-126-rule-editor-land-and-ledger.md`

---

## 六、本轮派发的 4 条车道（已 L1 绿 + 已 `land`，下一轮并入）

四条车道全部落在 **platform 服务**，L3 锚点互不相同（18.3.2 / 18.4.2 / 18.4.3 / 18.5.1），预分配的 Flyway 版本互不冲突。

### 6.1 `18.3.2.1` 执行日志（V422，head `370c850c`）

- **做什么**：记录每次规则执行 —— 触发事件、条件命中情况、执行的动作、每步结果、耗时、错误；支持查看上下文与手动重跑。
- **迁移**：`backend/services/platform/src/main/resources/db/migration/V422__automation_rule_run.sql`（两张新表 `platform_automation_rule_run` + `platform_automation_rule_run_step`）+ identity `V422` 权限码迁移（`platform.automation_rule_run.read/.record/.rerun`）。
- **端点**：`/platform/automation/runs` 6 条（capabilities / runs 列表真分页真筛选 / 详情含 steps / rerun 提交 / 幂等冲突显式错误）。
- **页面**：`/automations/runs`（运行列表 / 逐步明细 / 失败下钻 / 重跑入口，三态齐全）。
- **测试**：后端 28 例（19 + 9 租户三断言）、前端 24 例、全量 allowlist 回归 284 文件 / 1602 例全绿。
- **关键设计决策**：
  1. **幂等落成物理事实**：部分唯一索引 `UNIQUE(tenant_id, rule_id, event_id) WHERE rerun_of_run_id IS NULL` + 幂等键唯一索引；重跑经 `rerun_of_run_id` / `rerun_seq` 显式关联，**原 run 一个字都不改**（追加触发器物理上不允许）。
  2. **重跑不伪造成功**：本仓无通用副作用执行器 ⇒ `outcome=NOT_ATTEMPTED` + `EXECUTOR_UNAVAILABLE` + 未接通原因。
  3. **只建自己的表**：18.3.1.1 的 `platform_automation_rule_execution` 只是聚合统计，本模块只**读**规则档案（真 FK + 名称快照），不碰那五张表。
  4. `condition_id` **刻意不建 FK** —— 18.3.1.1 保存规则会整体替换条件行，建 FK 会锁死历史日志。

### 6.2 `18.4.2.2` 选品与需求预测（V424，head `cabba24c`）

- **做什么**：销量预测（含促销与大促影响）、补货量建议、价格弹性与调价建议、爆品识别与机会品发现。
- **迁移**：`V424__ai_forecast.sql`（六表：`platform_forecast_promotion` / `_demand_history` / `_run` / `_result` / `_factor` / `_suggestion`）+ identity `V424` 权限码。
- **端点**：`/platform/ai/forecast` 20 条；**页面** `/ai/forecast`（六页签真实工作区）。
- **测试**：后端 26 例（19 + 7 租户三断言）、前端 24 例、全量 allowlist 284 文件 / 1602 例全绿。
- **关键设计决策**：
  1. **预测必须带区间**：`point / lower / upper` 三列全 NOT NULL（NUMERIC(19,6)）；样本不足 4 天的 SKU **根本不进结果表**（进的是「跳过计数 + 原因」）。区间 = 点估计 ± z×σ×√预测天数。
  2. **影响因素带口径说明**：因素码 + 方向 + 取值 + **口径说明 + 事实来源**，五类 BASE_LEVEL / PROMOTION / SEASONALITY / PRICE_CHANGE / DATA_QUALITY；不适用时写「不适用 + 为什么」。
  3. **建议不自动执行**：状态机只有 SUGGESTED / ADOPTED / REJECTED，**没有 EXECUTED** ⇒ 结构上不可能声称已下单/已改价。
  4. **跨服务取数走 Port**：reporting 8088 销量趋势 / warehouse 在手 + 在途 / trade 采购事实，只转发调用方 bearer、不传租户号、不建跨库 FK；读不到一律 503；**库存读不到就不生成补货建议**（不用 0 冒充「库存为 0」）。促销日历是租户自有事实，`source` 标明录入方式，不假装上游同步。
  5. 算法**可解释不装 ML**：移动平均 / 指数平滑 + 促销因子，参数随运行落库，capabilities 逐条报出算法 / 参数 / 来源表。

### 6.3 `18.4.3.1` 使用与配额（V425，head `8b9102b2`）

- **做什么**：按租户与用户管理 AI 调用量与成本、配额限制、模型选择、敏感数据处理策略开关。
- **迁移**：`V425__ai_usage_quota.sql`（五表）+ identity `V425` 权限码。
- **端点**：`/platform/ai/usage` 13 条；**页面** `/ai/usage`（六页签）。
- **测试**：后端 24 例（16 + 8 租户三断言）、前端 24 例、全量 allowlist 284 文件 / 1600 例全绿。
- **关键设计决策**：
  1. **量纲由模型目录决定**（REQUEST / TOKEN / THOUSAND_TOKEN / CHARACTER），客户端不能自称；以「次」为量纲的调用量只能是 1。
  2. **单价快照进台账行** ⇒ 模型目录改价**不动历史成本**（测试读回历史行断言 `3.000000 / 0.002000` 不变）。
  3. **配额并发正确性**：递增用 DB 单语句 `INSERT … ON CONFLICT (quota_id, period_start) DO UPDATE SET used_count = … + EXCLUDED.used_count`，行锁串行、返回值即递增后累计值，**没有 Java 读后写**；8 条并发 ⇒ 计数恰好 8、成本 `4.000000`。
  4. **成本上限声明币种**：币种不符的调用照计次数、不计成本（原先会把 EUR 与 USD 加在一起）。
  5. **敏感数据策略边界如实写**：策略落库 + 可查询（`GET /platform/ai/usage/policies/effective`），但**本仓没有全局 AI 出站拦截点** ⇒ 能力声明把该维**如实答不可用**并写明「执行点是各个 AI 能力自己」，**未谎称已全局拦截**。下游计费只给可计费口径汇总，**不直连 finance 库 / 不建跨库 FK**。

### 6.4 `18.5.1.1` 模板管理（V427，head `cfae0a36`）

- **做什么**：管理通知与消息模板（内容、变量、适用场景、语言版本、启用状态）；改版后新通知用新版本，**历史通知保留当时内容**。
- **迁移**：`V427__notification_template.sql`（三表 + FORCE RLS + 租户触发器 + 不可篡改触发器 + 部分唯一索引）+ identity `V427` 权限码。
- **端点**：`/platform/ai/templates` 12 个形状；**页面** `/ai/templates`。
- **测试**：后端 40 例（Renderer 12 + 集成 22 + 租户 6）、前端 16 例、全量 allowlist 284 文件 / 1600 例全绿。
- **关键设计决策**：
  1. **模板必须版本化**：编辑 = **新增版本行**，版本表 `GRANT` 只给 SELECT/INSERT（不给 UPDATE/DELETE）+ `BEFORE UPDATE OR DELETE` 触发器 ⇒ 不可篡改由**权限层**兜底（测试直连 SQL 断言 `permission denied`）。
  2. **`resolve?…&at=` 按时刻解析当时版本**，返回**版本 id + 内容快照**（不是「当前版本」）⇒ 已发出的通知不会被后来改版「篡改」。
  3. **同时只允许一个启用版本**：部分唯一索引 `(… ) WHERE deactivated_at IS NULL`，数据库层写不进第二条。
  4. **渲染报错分型**：`MISSING_VARIABLE` / `UNKNOWN_VARIABLE` / `EXTRA_VARIABLE` / `SYNTAX_ERROR` / `INVALID_VALUE` / `INVALID_DECLARATION`，全部点名到具体变量；**渲染失败时 renderedBody = null**，绝不返回空串、绝不残留 `{{xxx}}`；登记模板时正文里的未声明变量当场 400 拒收。
  5. **上游 3.4.2 文案资产**（catalog 标题库 / 描述与卖点库）走 `CatalogContentAssetPort`，只转发调用方 bearer，取不到一律 503，**本服务不抄一份**。

---

## 七、熔断：18.4.1.1 对话助手

- **判据**：同一模块连续失败 ≥ `LANE_MAX_ATTEMPTS=2`（`redispatched=2`），且**分支 `feat/18-4-1-1` 零提交**、worktree 干净（**没有任何半成品代码可续做**）⇒ 按协议熔断，不再重派。
- **处置**：写 `.dev-agent/blocked-modules.md`（含熔断原因与恢复条件）；台账该行证据改写为以「**阻塞：**」开头的说明 —— 这同时让 `lane.mjs plan` 的 `^阻塞[：:]` 跳过判定生效（该机制只认这个前缀）。
- **恢复条件**：需人工把 18.4 AI 域的任务书拆细（该模块依赖面过宽，任务书颗粒度过粗），拆细后重新入队。
- **占用释放**：该车道 worktree 保留供人工检查；预分配版本号 V423 作废（Flyway 允许跳号，不会撞号）。

---

## 八、遗留与后续待办

1. **4 条车道（18.3.2.1 / 18.4.2.2 / 18.4.3.1 / 18.5.1.1）待下一轮并入 + 批次 L2**；并入后**必须人工核对 `packages/api-contracts/src/platform.openapi.json`**（union 驱动器走了行级兜底）。
2. **导航注册缺口**：`packages/ui/src/lib/task-pages.ts` 四条车道都**刻意未登记**（多车道并发改同一文件必然冲突）⇒ 统一由调度者在并入后处理 `/automations/runs`、`/ai/forecast`、`/ai/usage`、`/ai/templates`。
3. **18.4.3.1 遗留**：identity 缺「成员是否属于本租户」的只读端点（只有 `/internal/access`）⇒ 管理员代配用户级配额时无法校验成员归属，写进去的配额是**惰性行**（匹配不到用量，不会越权）；补齐需在 identity 侧新增只读端点。
4. **18.4.3.1 配额预留未实现**：预检是读快照、不占额度；权威判定在记用量那一次的原子递增 —— 能力声明里如实答不可用。
5. **格式闸的一个真实缺口**：增量格式闸只判断「本批触碰过的文件现在是否合规」，**不比对它在批次基线上的合规状态** ⇒ 触碰既有债文件会把「存量债」变成「本批红」。本轮是本批引入（已修）；若将来碰到「基线就不合规、本批只是改了内容」的情形，闸仍会红 —— 需要给闸补「基线合规性对比」才真正兑现协议里「存量债不得让闸打红」那句保证。**本轮未擅自改闸的判定口径**（改闸就是在同一轮里动裁判）。
6. **18.5.2.1 投递记录未交付**（卷 18 仅剩 19 个未开发模块中的一项）：本轮 18.5.1.1 只留出「版本 id + 内容快照」这根线，投递侧须引用版本 id 才谈得上「历史通知保留当时内容」。
7. **18.3.2.1 遗留**：18.3.2 执行引擎、18.3.2.2 规则效果、1 工作台告警均未交付 ⇒ 重跑在引擎交付前**永远**返回「未执行」（设计，非缺陷）。

---

## 九、文件清单（本轮新增 / 修改）

| 类别 | 路径 |
| --- | --- |
| 会话说明（本文件） | `docs/sessions/2026-11/2026-11-02-batch-127-l2-green-push-and-dispatch.md` |
| 台账（熔断注记） | `docs/product/capability-blueprint/module-progress.md`（18.4.1.1 一行） |
| 格式修复 | `tools/module-progress/PIPELINE_CRON_PROMPT.md`（prettier 归一，11+/11−） |
| L2 脚本 / 守护 | `.dev-agent/l2-r153.sh`、`.dev-agent/keepalive-r153.sh`、`.dev-agent/l2/l2-r153-*.log`、`.dev-agent/l2/l2-r153-summary.txt` |
| 熔断清单 | `.dev-agent/blocked-modules.md` |
| 车道 worktree | `.worktrees/18.3.2.1` / `.worktrees/18.4.2.2` / `.worktrees/18.4.3.1` / `.worktrees/18.5.1.1`（各含自己的会话日志，**未并入**） |
