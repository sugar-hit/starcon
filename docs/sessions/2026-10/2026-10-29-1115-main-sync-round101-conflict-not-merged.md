# 2026-10-29 11:15 · 集成头同步 main 第 101 轮 —— 冲突未并入（27 个，与第 99/100 轮**逐字节相同**）＋ 等闸 4396 秒

> 按 `tools/module-progress/SYNC-MAIN.md` 执行 `origin/main` → `integration/module-dev` 同步。
> **结果：冲突未并入**。`origin/main` tip 仍是 `823ebca5`（与第 79–100 轮**同一颗 squash**），
> 冲突面 **27 个**（26 add/add + 1 content），与第 99 / 100 轮**逐字节相同**
> （程序化集合比对：NEW 空、GONE 空）。
>
> **本轮新增的三点**：
> ① **等闸 4396 秒（73 分 16 秒）** —— 本任务历史最长，比第 100 轮的 544 秒长 8 倍。
>    闸被**活的流水线 r130 tick** 持有，该 tick 在跑 **L2 全量 `mvnw verify -fae`**（实测 1 小时+）并**每 60 秒续期**闸。
> ② **实证了「同一时刻存在 ≥2 个 main 同步竞态循环」**（backlog 里那条 P0 待办）——
>    本轮（11:15 触发）与第 100 轮（12:15 触发）**两个同步实例同时在跑 `gate acquire` 竞态**，
>    第 100 轮**先抢到闸**，本轮在它 release 之后 12 秒才拿到 ⇒ **闸的交接窗口确实窄到需要密集竞态**。
> ③ **「main 侧零新内容」的充要证明，第 4 次独立复现**（本轮重跑取证，不引用上轮结论）：
>    `origin/main^{tree}` 逐字节等于集成头祖先 `1e3228b4^{tree}`；main 树里 **5478 个 blob，0 个**不在集成头历史中；
>    台账逐模块 join **main 领先 = 0**、集成头领先 **23**。

## 摘要

| 项                     | 值                                                                             |
| ---------------------- | ------------------------------------------------------------------------------ |
| 结果                   | **冲突未并入**（27 个冲突，26 add/add + 1 content）                             |
| **并入的 main 提交数** | **0**（冲突中止，无 merge commit）                                             |
| main 新提交            | **无**（`origin/main` tip 仍是 `823ebca5`，与第 79–100 轮同一颗 squash）        |
| 差异规模               | 1 个提交 / 1032 个文件（+510134 / −45765）—— **行数是 squash 的假象**           |
| 冲突                   | **27 个**（与第 99 / 100 轮**逐字节相同**）                                    |
| **main 侧新内容**       | **0**（本轮独立复现的三条证据，见 §三）                                        |
| 预检                   | **绿**（`./mvnw -q compile` rc=0；`pnpm typecheck` rc=0，3/3）                 |
| 台账                   | **429 已交付 / 0 进行中 / 36 未开发**（465 总数，92.3%），**零文件改动**        |
| push                   | **已 push**，只含本会话日志 + backlog 更新（**零业务代码**）                    |
| 闸                     | **已释放**                                                                      |
| **等闸时长**           | **4396 秒（73 分 16 秒）**（11:15:36 → 12:28:52）                              |

## 一、取闸：等 4396 秒（**未抢闸、未清闸、未 force**）

首读 `gate status`（11:15）⇒ `held` / `ageSeconds=15` / `ttl=4200` / **`stale=false`**
⇒ 按协议判定为「**有活着的流水线 tick 持闸**」⇒ **等它释放**，不抢、不清、不 force。

协议特别要求「不要用每 20 秒 `gate status` → 看到 free 再 acquire」的写法（交接窗口可能窄到零宽度），
故直接用**密集 `gate acquire` 竞态**（`mkdirSync` 原子互斥，失败无副作用）：

```
# 第一段竞态（11:15:36 起，1 秒间隔）⇒ 命中工具 3600 秒上限，被超时截断，**未拿到闸**
# 第二段竞态（12:15:56 起，3 秒间隔）
for i in $(seq 1 1200); do
  node tools/module-progress/lane.mjs gate acquire | grep -q '"ok": true' && break
  sleep 3
done
⇒ GATE ACQUIRED at 12:28:52   （owner pid 30562）
```

### 1.1 持闸者是谁（活性取证全在闸外）

| 取证 | 结果 |
| --- | --- |
| `.dev-agent/dispatch.gate/owner` | PID `99642` + 时间戳 `2026-10-29T03:01:19.983Z`（11:01 起） |
| 闸目录内 `keepalive-r130-*` | **每 60 秒一个新文件**（`r130-1` @11:15 → `r130-61` @12:15）⇒ **持闸 tick 活着** |
| 闸外构建进程 | `mvnw verify -fae`（PID 1688，**1 小时+**）+ 不断更换的 `surefirebooter`（identity → catalog → finance）⇒ **L2 正在跑** |

> ⚠️ **`ageSeconds` 会被 keepalive 重置**（`touch` 闸目录**内**的文件会刷新**目录自身 mtime**，
> 而 `ageSeconds` 正是 `Date.now() - statSync(GATE_DIR).mtimeMs`，`lane.mjs:612`）。
> ⇒ 实测多次观察到 `ageSeconds` 突降到 1~4，**不是**「闸被释放又重取」，**是持闸者做了一次续期**。
> **判活仍只看 `stale`**（续期只会让 `stale` 更不容易为真）；**不要**把 `ageSeconds` 回落当作闸已释放。

### 1.2 ⚠️ 本轮新证据：**两个同步实例同时在竞闸**

| 实例 | 触发 | 结果 |
| --- | --- | --- |
| **第 100 轮** | 12:15 | 等闸 544 秒 ⇒ **先拿到闸** ⇒ 12:28:37 提交 `95151eb1` ⇒ release |
| **本轮（第 101 轮）** | 11:15 | 等闸 4396 秒 ⇒ 第 100 轮 release 后 **12 秒**才拿到（12:28:52） |

⇒ **同一时刻确实存在 ≥2 个 `gate acquire` 竞态循环**，且**后触发的第 100 轮反而先拿到闸**
（它的竞态间隔比本轮第一段更密）⇒ 直接实证 backlog 里那条 P0：
**「调度侧保证同一时刻只有一个 main 同步竞态循环」**。
⚠️ 本轮**没有**造成闸泄漏：本轮只 acquire 一次、release 一次，**配平**；
且第 100 轮 release 之后本轮 12 秒内即接手，**没有**出现「release 瞬间被另一个循环秒抢并持到 TTL」的窗口。

## 二、main 侧：仍然**没有**新提交

`git fetch origin --prune` 后：

```
git rev-list --count integration/module-dev..origin/main  ⇒ 1
git log -1 --format='%h %ad %s%nparents: %P' origin/main
  ⇒ 823ebca5 Mon Oct 26 14:49:58 2026 +0800  Integration/module dev (#66)
    parents: 6522c4976ed8959f20ab3037419d7074d596297a     # ← 单父 ⇒ squash
git merge-base integration/module-dev origin/main ⇒ 6522c497（2026-10-23 09:18）
```

⚠️ **merge-base 恰好等于这颗 squash 的父提交** ⇒ 合并路径上**没有**任何真实血缘，
冲突全部由「squash 抹掉血缘 + 集成头继续前进」合成。

## 三、main 侧零新内容：第 4 次**独立**复现

> 本轮**不引用**第 98–100 轮的结论，而是重跑取证 ⇒ 判据可复现、可依赖。

### ① `origin/main^{tree}` 与集成头祖先**逐字节相同**

```
git rev-parse origin/main^{tree}  ⇒ 83269e111f5f42e8b9481fd0e9549687aa8a7ed0
git rev-parse 1e3228b4^{tree}     ⇒ 83269e111f5f42e8b9481fd0e9549687aa8a7ed0   ← 相同
```

并在**集成头全部历史提交**里逐一比对树哈希 ⇒ **唯一命中者**是 `1e3228b4`
（2026-10-25，「集成头同步 main 第 75 轮 —— 无新提交，未同步」）
⇒ **main 的 tip = 集成头分支在 2026-10-25 23:16 时刻的旧快照。**

### ② main 树里的每个 blob 都已在集成头历史中

```
git ls-tree -r origin/main | awk '{print $3}' | sort -u            ⇒ 5478 个唯一 blob
git rev-list --objects integration/module-dev | … | sort -u        ⇒ 41807 个 blob
comm -23 main_blobs our_blobs                                    ⇒ 0
```

⇒ **main 没有带来任何一个新 blob。**

### ③ 台账逐模块 join：**main 领先 = 0**

对两侧台账 `docs/product/capability-blueprint/module-progress.md` 做**逐模块 ID + 状态 join**
（**不是**目测总览表）：

| 口径 | main 快照 | 集成头 `95151eb1` |
| --- | --- | --- |
| 模块行数 | 465 | 465 |
| 已交付 | **406** | **429** |
| 进行中 | 0 | 0 |
| 未开发 | **59** | **36** |
| 完成率 | 87.3% | **92.3%** |
| **模块 ID 集合** | 与集成头**完全相同** | — |

状态不同的模块 **23 个**，方向**全部**是「集成头已交付 / main 未开发」：

```
1.1.2.1  1.1.2.2  1.3.2.1  1.3.2.2  1.5.2.1
13.1.2.1  13.1.2.2  13.2.1.1  13.2.1.2  13.2.2.1  13.2.2.2
13.3.1.1  13.3.1.2  13.3.2.1  13.3.2.2  13.4.1.1  13.4.1.2
13.4.2.1  13.4.3.1  13.5.1.1  13.5.2.1  14.2.1.2  14.3.1.3
```

⇒ **main 领先（main 已交付、集成头未交付）= 0 个**
⇒ **main 没有任何一个模块是集成头没有的**；这 23 个正是集成头 batch 110/111/112 自己的交付。

## 四、合并：27 个冲突 ⇒ 按协议 `abort`，**未自行解冲突**

```
git merge --no-ff origin/main -F <正文>
⇒ merge exit=1，27 个冲突
⇒ git merge --abort
⇒ HEAD 回到 95151eb1，MERGE_HEAD 已清，工作区干净
```

**冲突面与第 99 / 100 轮逐字节相同**（程序化集合比对，按 basename）：

```
NEW  : （空）
GONE : （空）
```

| 口径 | 第 99 轮 | 第 100 轮 | **第 101 轮** |
| --- | --- | --- | --- |
| 文件数 | 27 | 27 | **27**（26 add/add + 1 content） |
| **theirs 独有 = 0 ⇒ 取 ours 无损** | 4 | 4 | **4**（同一批 4 个文件） |
| **theirs 独有行合计** | 79 | 79 | **79** |
| ours 独有行合计 | 1643 | 1786 | **1786** |

theirs 独有 = 0 的 4 个文件（取 ours 无损）：

- `apps/web/src/lib/api-proxy.ts`（**content** 冲突，ours 严格超集）
- `backend/services/channel/src/test/java/com/starcon/channel/LinkageRiskIdentityStub.java`
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingFinancePort.java`
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingUpstreamClient.java`

⚠️ **高危项仍然点名**（与第 98 轮一致）：
`SaasUsageIntegrationTest.java` 取 theirs 会把 `c904ec63` 修掉的 **flaky 随机子串断言**装回去；
`AnomalyOrder*` 取 theirs 会**回退跨时钟域修复**（JVM 墙钟基准）。
⇒ **本轮协议禁止自行解冲突，照办 abort**；机械解法仍待**协议层授权**（backlog 待办第 3 条）。

### 4.1 合并时由 `merge-union` 自动求并 / 干净合上的文件（不是问题源）

- `packages/api-contracts/**`（contract-union 驱动，行级兜底并集）
- `docs/product/capability-blueprint/module-progress.md`（台账按模块 ID 求并，折叠 465 行同 ID 记录）
- `backend/services/channel/src/test/java/com/starcon/channel/OAuthAuthorizationIntegrationTest.java`（三方干净合上）

## 五、快速预检：**绿**（口径提醒见下）

在**冲突已 abort、工作区回到 `95151eb1`** 的状态下跑预检（不是 L2）：

```
cd backend && ./mvnw -q compile   ⇒ rc=0
cd <root> && pnpm typecheck        ⇒ rc=0（3 successful / 3 total，380ms FULL TURBO）
```

> ⚠️ **口径提醒**：本次预检跑的是**未合并**的集成头 ⇒ 它证明的是
> **「abort 之后集成头本身仍然是绿的」**，**不是**「main 的提交与集成头合得起来」。
> 合并内容从未经过编译 —— 这正是 backlog 待办第 3 条要求协议层授权的原因。

## 六、台账：**零文件改动**

```
node tools/module-progress/sync.mjs
⇒ ✓ 同步 18 卷 / 465 个 L4 模块
  已交付 429 · 进行中 0 · 未开发 36
git status --porcelain ⇒ （空）
```

⇒ 台账已是 canonical 形态，**同步前后完全一致**（429 / 0 / 36），
**main 侧零新交付 ⇒ 进度基线不变**（第 101 轮并入 0 个模块）。

## 七、push 与闸

- **只 push 集成头**，未 push `main`；推送内容**只有本会话日志 + backlog 更新**，**零业务代码**、fast-forward。
- **`gate release` 已执行**（本轮 acquire 成功 1 次、release 1 次，**配平**）⇒ 闸未泄漏。

## 八、后续待办（与 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` 同源）

1. **（发布侧，唯一根治）** 把集成头的**真实 commit（不 squash）**推到 main，
   使 `origin/main` 的 tip 重新成为集成头的祖先 ⇒ 本任务回到「无新提交」的零改动常态。
   当前 main tip `823ebca5` 是 `6522c497` 的单父 squash，主干侧零新内容。
2. **（发布侧，次选）** 把 main 重置到集成头，或接受 squash 作为新基线（需配套「main 是集成头快照」的读取约定）。
3. **（协议层授权）** 在 `SYNC-MAIN.md` 增一条判据：
   **「main 侧 blob 已被集成头历史逐字节覆盖 ⇒ 允许 `-X ours` 机械合并」**。
   依据已具备：第 99 轮已用隔离 scratch clone 证明「27 个冲突全取 ours」的结果树 == 当前 HEAD 的树（**无损**），
   本轮第 ④ 节再次复现同一组数字（theirs 独有 79 行 / ours 独有 1786 行 / 4 个零独有文件）。
   ⇒ 一旦授权，本任务可**一轮并入**，无需再等发布侧。
4. **（调度侧，P0）** 保证同一时刻**只有一个** main 同步竞态循环 —— 本轮实测两个实例同时在竞（第 100 轮 + 本轮）。
5. **（协议层）** 补一条「等闸上限 + 超时如实汇报退出」判据 —— 本轮等闸 4396 秒（73 分 16 秒），
   已远超单轮惯例；若闸长期被活着的 L2 持有，应显式放弃而不是无限等。
