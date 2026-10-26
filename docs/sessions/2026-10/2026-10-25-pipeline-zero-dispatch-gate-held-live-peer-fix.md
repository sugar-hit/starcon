# 2026-10-25 15:00 tick · 零派发只读轮 —— 调度闸被活 tick 持有（同行正在跑 clock-domain 断言修复 + 终验）

- **日期**：2026-10-25 15:00（Asia/Shanghai）
- **分支**：`integration/module-dev`（集成头 worktree `/Users/guan/Projects/starcon-worktrees/integration`）
- **模式**：**降档模式**（15:00 ≥ `TOKEN_HORIZON` = 2026-10-25T00:00:00+0800）⇒ `LANES=4→2`、`THROTTLE_MIN=50→100`、`SILENT_WINDOW=0`、`CRON=0 * * * *`
- **本轮产出**：**零派发、零并入、零交付**。产出是一份只读取证记录 + 静态预检结论。
- **本轮 commit**：单独一次 `docs(pipeline):` 提交，**只含本文件**（台账未动，见「为什么不一起提交」）
- **本轮 push**：**未做** —— L2 未绿（同行批次 L2 未转绿，见下）

## 摘要

本轮只有**调度闸**命中，其余六道闸全部通过。

| 闸 | 判定 | 依据 |
| --- | --- | --- |
| 调度闸 | **命中** | `lane.mjs gate acquire` → 退出码 3 `held-by-other`；闸目录 mtime = 15:03:44（采样时龄 **≈ 2 分钟** ≪ TTL 4200）⇒ **持闸者活着** |
| 批次节流闸 | 通过 | `last-batch-at` = 2026-10-25 12:00:22，本轮 15:00 已过 **180.6 分钟** ≥ 降档阈值 **100 分钟** |
| 温度闸 | 通过 | `pmset -g therm` = `No thermal warning level has been recorded` / `No performance warning level has been recorded` / `No CPU power status has been recorded` |
| 配额闸 | 通过 | `lanesRunning` = **0** < `RUNNING_QUOTA` = 6（台账里 206 条车道状态直方图 = `{"merged":206}`，无 running / ready） |
| PG 闸 | **未参与判定** | 本轮不派发 ⇒ 不起容器；采样时带 `org.testcontainers.sessionId` 标签的 `postgres:16-alpine` = **0 → 1**（同行测试起容器了，见「PG 计数口径」） |
| load 闸 | 通过 | load **3.98 / 3.30 / 3.22**（10 核，阈值 12），三点无爬升 |
| 静默窗口闸 | 不适用 | `SILENT_WINDOW=0`（全天运行），但温度 / 容量 / 节流闸照旧生效 |

⇒ **本轮不能碰集成头的任何写操作**：不派发、不并入、不跑 `plan`、不跑 L2、不抢闸、不 force。

## 持闸者取证：活着的同行 tick

调度闸不是「派发窗口（秒级）」，TTL 70 分钟，靠闸目录 mtime 判龄。本轮拿到**活体**证据链：

| 证据 | 读数 |
| --- | --- |
| 会话状态 | `mavis` 会话 `mvs_f130e6a1267242eea61597d55f7ab278`（*starcon 并行车道功能开发流水线*）状态 = **`started`**，创建于 12:26:40；其 worker 子会话 `mvs_05eeace7c70a454cbad08e8eaafb1e23`（*Fix clock-domain assertion hazards*）状态 = **`started`** |
| 闸续期 | `dispatch.gate/keepalive-*` 连续 10 个，最新 `keepalive-150344-10`（**15:03:44**）—— 由 `bash .tmp/keepalive-loop.sh`（PID 7702，12:43 起）每 10 分钟新建一个**新文件名**续期（符合协议「必须新文件名才会刷新目录 mtime」） |
| 活动进程 | 15:00–15:01 观测到 `mvnw -q -pl services/{catalog,channel,identity,trade} spotless:check` 在跑，工作目录 = `.worktrees/fix-clock-domain/backend` |
| Testcontainers | 二次采样时出现带标签容器 `hopeful_perlman`（`postgres:16-alpine`）⇒ 同行**已在跑集成测试**，不只是编译 |
| 在途修复车道 | `.worktrees/fix-clock-domain`（分支 `fix/clock-domain-assertions`）**dirty = 4 个未提交文件**（catalog `ImportWizardIntegrationTest`、identity `ApprovalFlowIntegrationTest` / `ApprovalRuleIntegrationTest` / `IdentityIntegrationTestBase`） |
| 闸持有者 PID | `owner` = `6436` / `2026-10-25T04:25:29.903Z`（= 12:25:29 +0800，与 12:25 的两次 merge commit 同时刻）⇒ **本轮 tick 是在 12:25 那次 merge 之后紧接着取闸的同行** |

⇒ 同行 12:25 tick 正在收尾 **L2 红的修复批次**：6 条修复车道分支（`fix/12-1-1-1-due-date-timezone-assertion`、`fix/12-1-2-1-nav-route-registration`、`fix/12-2-2-2-handoff`、`fix/16-3-2-3-lock-budget`、`fix/3-9-2-2-timestamptz-assertion`、`fix/clock-domain-assertions`）**已全部并入集成头**（`git merge-base --is-ancestor` 逐条核对 = MERGED），其中最后一条的 4 个文件还在改。

## 本轮只读结论

### 1. 台账进度（权威口径 = `sync.mjs` 本轮输出）

```
模块总数 465 | 已交付 404 | 进行中 0 | 未开发 61 | 完成率 86.9% | 距全部交付还差 61 个
```

交叉验证一致：`sync.mjs` = `✓ 同步 18 卷 / 465 个 L4 模块 · 已交付 404 · 进行中 0 · 未开发 61`；`lane.mjs status` = `delivered 404 / inProgress 0 / untouched 61`。

⚠️ **台账文件里汇总数字是过期的**（`| 已交付 | 402 |` / `| **未开发** | **63** |` / `完成率 86.5%`）—— 那是**上次提交**的状态（`docs(batch-95)` 于 12:04 提交）。12:25 的两次 merge（`merge(13.4.2.2)` / `merge(18.4.2.1)`）把台账行带进了工作区但**尚未提交**，所以当前 `git status` 有 1 个 ` M module-progress.md`。**汇报一律以 `sync.mjs` 本轮输出为准。**

### 2. 未开发模块的分布（61 个）

| 阶段 | 已交付 | 未开发 |
| --- | --- | --- |
| D1–D2 | 80 | 0 |
| D3 | 40 | 0 |
| D4 | 60 | 0 |
| D5 | 52 | 0 |
| D6 | 75 | **3** |
| D7 | 97 | **58** |

⇒ 剩余工作**高度集中在 D7（58 个，95%）**，D1–D5 六个阶段已全部清空。剩余 61 个几乎全是最后阶段的长尾。

### 3. 静态预检：同行在途批次的新迁移**零命中**（互破坏三形状）

对 12:25 并入的 3 条新迁移逐个扫「已知互相破坏形状」：

| 迁移 | `ADD COLUMN` 无 DEFAULT | `SET NOT NULL` | 新增 `CHECK` | RLS ENABLE / POLICY |
| --- | --- | --- | --- | --- |
| `reporting/V379__expense_variance.sql` | 无 | 无 | 无 | 1 / 1 |
| `platform/V380__text_translation.sql` | 无 | 无 | 无 | 7 / 7 |
| `identity/V380__platform_text_permission.sql` | 无 | 无 | 无 | 0 / 0 |

⚠️ **两处 `V380` 同号不冲突** —— Flyway 历史按**服务**隔离，各服务独立数据库（`backend/services/*`），`identity.V380` 与 `platform.V380` 互不相干。

`identity/V380` 无 RLS 是**正确的**：该文件只往 `permission` 目录表灌权限码并给 `role_permission` 灌初始行（`INSERT INTO role_permission SELECT r.tenant_id, r.id, p.code FROM role r CROSS JOIN permission p`），不新建租户业务表 ⇒ 不需要 RLS 策略；它写 `role_permission` 时显式带 `r.tenant_id` ⇒ 租户边界仍然成立。

⇒ **本批不会在 L2 之前触发跨车道违约。**

### 4. PG 容器计数口径校正

`docker ps --filter "label=org.testcontainers.sessionId"` 在两次采样间由 **0 → 1**（`hopeful_perlman`）—— 同行正在起 Testcontainers 容器。**无标签的孤儿 `postgres:16-alpine` = 1**：`starskewtest`，创建于 **2026-10-25T06:48:56Z（14:48:56 +0800）**，_labels 为 `{}`_。

⚠️ 该孤儿**不是本轮创建**（本轮未起任何容器），且正处于同行 tick 的作业时段，**本轮不清**（清理权归持有闸的 tick，避免打断它的测试）。**需人工/下一轮点名清理。**

另有 `langfuse-postgres-1`（`postgres:15-alpine`，Up 5 days）是**常驻业务依赖**，不是孤儿，不该清。

⇒ 教训同 10-22 那轮：**PG 闸的瞬时读数会因同行正在跑测试而剧烈波动**，判据须结合「是否有在途 L2」一起看，不要据单点采样下结论。

### 5. 吞吐预期（实测口径，不引用历史文档里的写死数字）

**降档模式算术**（`CRON=0 * * * *` = 每小时栅格，节流阈值 100 分钟 > 60 分钟栅格 ⇒ 实际每 **2 小时**放行一批）：

```
12 批/天 × LANES 2 = 名义 24 个/天 × 折损系数 0.70 ≈ 16 个/天
```

**实测落地速率**（`lanes.json` 按 `mergedAt` 统计，206 条车道全为 `merged`）：

| 窗口 | 落地模块数 | 折算/天 |
| --- | --- | --- |
| 近 12 小时 | 4 | 8 |
| 近 24 小时 | 16 | **16** |
| 近 36 小时 | 33 | 22 |
| 近 48 小时 | 51 | 25.5 |

近 24 小时的 16 个/天与降档模式理论值**完全吻合**；近 12 小时掉到 4 个是因为这半段卡在 **L2 红的修复周期**（batch-93 / 94 / 95 三轮都是「零回收零交付」的崩溃恢复重派，见 `docs(batch-95)`）。

剩余 61 个 ⇒ 按实测 16–25 个/天，**约 2.5–4 天**；计入 L2 返工、闸等待、长尾车道占位（历史 P90 约 15 小时），**诚实区间 4–6 天**。

## 关键决策

### 决策 1：不派发、不并入、不跑 L2 —— 撞闸即止

调度闸存在的唯一理由就是防「两个 tick 同时改集成头、同时跑 L2 抢 Testcontainers ⇒ 成片假红」。本轮全部证据表明持闸者是**活着的同行 tick 且正在跑终验修复** ⇒ 此刻任何写操作都可能：

- 与同行的 `git merge` / `git commit` 抢 index；
- 与同行的 maven 抢同一批 Testcontainers 容器，让它的 L2 假红；
- 抢闸 / force 掉一个**活着**的闸 —— 协议明令禁止。

⇒ **零派发 + 只读检查 + 汇报结束**。

### 决策 2：不 push（本地有 6 个待推 commit）

本地 `integration/module-dev` = `f16de390`，远端 `origin/integration/module-dev` = `e0a7d966`（`git ls-remote` 核对，非本地跟踪分支）⇒ **6 个 commit 待推**。这 6 个全部是同行的成果（`merge(13.4.2.2)`、`merge(18.4.2.1)`、2 个 clock-domain 修复 + 2 个 lane commit）。

但**同行批次的 L2 从未在本轮转绿**：`.dev-agent/l2-run-batch-90.log` 最后一次 L2 绿在 **2026-10-25 00:18:19**，此后 batch-93 / 94 / 95 三轮均为「L2 延后 / 零交付」，当前正在跑的 clock-domain 修复正是为了把那批红转绿。

协议硬规：**「L2 没绿绝不 push」**、**「push 前必须确认工作区干净、L2 是绿的。不要 push 红的。」**
⇒ 本轮**不 push**，也不动那 6 个 commit。修复后的成果由同行在 L2 转绿后统一推。

### 决策 3：说明照写、commit 照做，但**只 commit 本文件**

协议对「L2 红」的处置是「**说明照写、commit 照做**（成果与诊断留在集成头本地，修复后随下一批一起 push）」。本轮同属「零派发只读轮」⇒ 写说明 + 提交本地 commit 符合协议；push 因 L2 未绿而不做。

**为什么不把台账与同行说明一起提交**（即 cron 任务书里 `git add docs/sessions/$(date +%Y-%m)/ … module-progress.md` 那条标准命令）：

1. 台账 `module-progress.md` 的当前改动是**同行的在途产物**（12:25 两次 merge 带进来的 402→404）。替同行提交它，等于把「说明 + 台账同一个 commit」这条规则用在**别人的批次**上，且抢在同行自己的 `docs(batch-N):` 提交之前 ⇒ 历史会变得难以审计。
2. 协议还明写「台账行 + 同行说明」必须**同一个 commit** ⇒ 现在单独提交台账会**拆散**同行的批次提交。
3. `docs/sessions/2026-10/` 目录下的 batch 说明文件可能带 `见文末「本轮 L2 结果」` 占位（L2 未跑完）⇒ 此刻提交等于把**半成品说明**钉进版本库。

⇒ 本轮**只 `git add` 本文件**并单独提交；台账与同行 batch 说明留给同行在其 L2 转绿后，用它自己的 `docs(batch-N):` 提交一次性带走。

### 决策 4：零派发轮的会话记录不带 batch 号

任务书要求 batch 说明「批次号接着已有往后排、不能重号」。本轮**没有交付任何模块**，不存在 batch-96；写 `batch-96` 会占掉一个号并暗示一个不存在的批次。仓内现有最大号是 **batch-95**（12:04 提交），同行正在收尾的仍是它自己那一批。
⇒ 本文件采用与 10-22 那轮一致的无 batch 号命名（`…-pipeline-zero-dispatch-…`），批次号留给真正产生交付的那一轮。

### 决策 5：升级信号 —— `TOKEN_HORIZON` 已过，本轮起进入降档模式

`TOKEN_HORIZON` = 2026-10-25T00:00:00+0800，本轮 15:00 **已过期限** 15 小时 ⇒ 自本轮起**降档模式**生效：`LANES=4→2`、节流闸 `50→100` 分钟。

⚠️ 这是**用户定的边界**，调度器**不自作主张改回高强度**，也不因此改变停机条件（停机只看「全部 465 个模块已交付」，当前差 61 个）。

## 文件清单

| 文件 | 性质 |
| --- | --- |
| `docs/sessions/2026-10/2026-10-25-pipeline-zero-dispatch-gate-held-live-peer-fix.md` | 本文件（本轮唯一新增，唯一提交） |

**未触碰**（属于同行的在途产物，本轮一个字节都没改）：

- `docs/product/capability-blueprint/module-progress.md`（同行 12:25 两次 merge 带进来的 402→404）
- `.dev-agent/lanes.json`（同行 lane 状态）
- `.dev-agent/dispatch.gate/`（同行持有的闸 —— 未清、未 force、未续期）
- `.worktrees/fix-clock-domain/`（同行的 4 个未提交测试文件）

## 后续待办

1. **同行（12:25 tick）**：clock-domain 修复提交 → `land` → `merge-ready` → 跑 L2 → 绿则填 batch 说明 → `sync.mjs` 写盘 → `git add docs/sessions/2026-10/ docs/product/capability-blueprint/module-progress.md` → `docs(batch-N):` 提交 → push `integration/module-dev`（连带那 6 个待推 commit）。
2. **⚠️ 并入前置条件**：集成头工作区当前带 1 个 ` M module-progress.md` ⇒ `lane.mjs merge-ready` 会在「主工作区不干净，拒绝切分支」处退出（`lane.mjs:1026`）。同行必须**先提交台账再并入**，否则并入通道会堵死。
3. **孤儿容器**：无标签的 `starskewtest`（`postgres:16-alpine`，14:48:56 创建）需点名清理；它不计入 `PG_CONTAINER_LIMIT`（只数带 `org.testcontainers.sessionId` 标签的）。
4. **下一轮**：本轮起处于降档模式，`plan --lanes 2`、节流闸 100 分钟 ⇒ 实际每 2 小时一批。**若届时闸已释放**，优先派发 **D7 剩余 58 个**里的上游已就绪模块（D6 仅剩 3 个）。
5. **技术债（不阻塞，来源见 batch 说明）**：`clock-domain-assertions` 这类「断言依赖墙钟窗口」的修复说明同类风险在别的模块仍可能出现；可开专门车道收敛。
