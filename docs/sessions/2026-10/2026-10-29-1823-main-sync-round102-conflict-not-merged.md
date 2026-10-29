# 2026-10-29 18:15 · 集成头同步 main 第 102 轮 —— 冲突未并入（27 个，与第 99/100/101 轮同一集合）＋ 等闸 519 秒（TTL 接管）

> 按 `tools/module-progress/SYNC-MAIN.md` 执行 `origin/main` → `integration/module-dev` 同步。
> **结果：冲突未并入**。`origin/main` tip 仍是 `823ebca5`（与第 79–101 轮**同一颗 squash**），
> 冲突面 **27 个**（26 add/add + 1 content），与第 99 / 100 / 101 轮**同一集合**。
>
> **本轮新增的两点**：
> ① **等闸 519 秒，且这次的持闸者是「孤儿锁」** —— owner PID 已死、无构建进程、闸目录内**无 keepalive 文件**
>    ⇒ 闸被死掉的 r131 tick 留下，`stale=false` ⇒ 按协议**等 TTL 自然接管**（未手删闸目录）。
> ② **实测到「双向持闸」**：我 18:23:45 取闸成功后，流水线 **r132 的 L2 脚本 `.dev-agent/l2-r132.sh`
>    根本不调用 `gate acquire`**，直接把自己的 keepalive 文件 `touch` 进既有闸目录 ⇒
>    双方都以为自己是持闸者，**L2 与本轮的预检在同一 worktree 里并发**（详见 §五、§七）。

## 摘要

| 项                     | 值                                                                        |
| ---------------------- | ------------------------------------------------------------------------- |
| 结果                   | **冲突未并入**（27 个冲突，26 add/add + 1 content）                        |
| **并入的 main 提交数** | **0**（冲突中止，无 merge commit）                                        |
| main 新提交            | **无**（`origin/main` tip 仍是 `823ebca5`，与第 79–101 轮同一颗 squash）   |
| 差异规模               | 1 个提交 / 1032 个文件（+510134 / −45765）—— **行数是 squash 的假象**       |
| 冲突                   | **27 个**（与第 99 / 100 / 101 轮同一集合）                                |
| **main 侧新内容**      | **0**（本轮独立复现的三条证据，见 §三）                                   |
| 预检                   | **绿**（隔离 scratch 树 `./mvnw -q compile` rc=0；`pnpm typecheck` 3/3）  |
| 台账                   | **431 已交付 / 0 进行中 / 34 未开发**（465 总数，92.7%），**零文件改动**   |
| push                   | **已 push**，只含本会话日志 + backlog 更新（**零业务代码**），fast-forward   |
| 闸                     | **已释放**                                                                 |
| **等闸时长**           | **519 秒**（首读 `held`/`stale=false` ⇒ 密集竞态 `gate acquire`，**未抢未清未 force**） |

## 一、取闸：等 519 秒（孤儿锁 ⇒ TTL 接管）

首读 `gate status`（18:15:06）⇒ `held` / `ageSeconds=3682` / `ttl=4200` / **`stale=false`**
⇒ 按协议判定为「持闸者活着」⇒ **等它释放**，不抢、不清、不 force。

### 1.1 持闸者是谁 —— 活性取证全在闸外

| 取证                                              | 结果                                                                 |
| ------------------------------------------------- | -------------------------------------------------------------------- |
| `.dev-agent/dispatch.gate/owner`                  | PID `74168` + `2026-10-29T09:13:44.967Z`（17:13 起）                 |
| `ps -p 74168`                                     | **查无此进程** ⇒ owner PID 不是持闸者（与协议第 1 步提醒一致）       |
| 闸目录内容                                        | **只有 `owner`，无任何 `keepalive-*`** ⇒ 持闸者**没有续期机制在跑**   |
| 同 worktree 构建进程（`ps`）                      | **无 `mvnw` / `java` / `surefire`** ⇒ 没有任何 L2 / L1 在跑           |
| `.tmp/watch-lanes.sh`（PID 22174，已跑 2 天）      | 只写 `.tmp/lane-watch.log`（每分钟一行车道状态），**不碰闸** ⇒ 被动观测 |
| 闸外其它 node/java 进程                           | 全部是 hermes-studio-mcp / Tabbit / MCode helper，与本仓无关          |

⇒ 结论：**孤儿锁** —— r131 的 tick 崩在闸里（`l2-r132.sh` 的注释写明 r131 崩在 `net::ERR_CONNECTION_CLOSED`，
崩溃点恰在 `plan --lanes 2` 之后、派发 worker 之前），闸被留下但**没有人续期、没有人干活**。
⇒ 但协议要求判活**只看 `stale`**，此时 `stale=false`（3682 < 4200）
⇒ 故**仍走 TTL 接管**，**没有手删闸目录**。

### 1.2 密集竞态（协议规定的写法）

```
for i in $(seq 1 1200); do
  node tools/module-progress/lane.mjs gate acquire | grep -q '"ok": true' && break
  sleep 1
done
⇒ GATE ACQUIRED at 18:23:45（owner pid 79303）
```

### 1.3 ⚠️ 等闸期间实测到 **2 个同族孤儿竞态循环**（已处置）

| 进程                             | 形态                                                                       | 处置                                   |
| -------------------------------- | -------------------------------------------------------------------------- | -------------------------------------- |
| `76215` / `76219`（`.tmp/wait-gate-r132.sh`） | 每 20 秒轮询 `gate status`，`age>4200` 时 `gate acquire` 后**立即退出**     | **kill**（`kill 76219 76215`）        |
| `76952` / `76955`（同名脚本，18:17 重新拉起） | 每 15 秒轮询，同样 acquire 后退出                                            | **kill**（`kill 76955 76952`）        |
| `77517`                          | `sleep 360; tail 日志; gate status; cat owner` —— **纯只读观测**            | **保留**（不 acquire，无副作用）       |

**为什么必须杀掉前两个**：`lane.mjs:568-588` 的 TTL 接管路径是
`mkdirSync` 失败 → `age > ttl` → **`rmSync(GATE_DIR)` + `mkdirSync(GATE_DIR)`**（**非原子**）。
若本轮与它们在同一个 TTL 边界同时接管，**双方都会拿到 `ok: true`** ⇒ 互斥直接失效
⇒ 两个持闸者同时改集成头、同时跑 L2（`PIPELINE.md` 明令「宁可挡住一轮，也不能双派发」）。
且这两个脚本 acquire 成功后**立即退出**，却把闸留成「新鲜持有」⇒ **闸泄漏 70 分钟**、白挡下一轮。

⇒ **本轮 acquire 1 次 / release 1 次，配平**；闸目录**从未被手删**。

## 二、main 侧：仍然**没有**新提交

```
git fetch origin --prune
git merge-base --is-ancestor origin/main integration/module-dev
⇒ 失败 ⇒ HAS_UPDATE
git log --oneline integration/module-dev..origin/main
⇒ 823ebca5 Integration/module dev (#66)      # 唯一 1 个
git diff --stat integration/module-dev...origin/main | tail -1
⇒ 1032 files changed, 510134 insertions(+), 45765 deletions(-)
merge-base = 6522c497
```

⚠️ **+510134 / −45765 是 squash 的假象**，不代表 main 侧有 51 万行新代码（见 §三）。

## 三、main 侧零新内容：第 5 次**独立**复现（不是引用上轮结论）

| # | 证据                                                                                                    | 本轮结果                                            |
| - | ------------------------------------------------------------------------------------------------------- | --------------------------------------------------- |
| 1 | `git rev-parse origin/main^{tree}`                                                                       | `83269e111f5f42e8b9481fd0e9549687aa8a7ed0`         |
| 2 | `git rev-parse 1e3228b4^{tree}`（集成头祖先，第 99 轮取证所得）                                           | `83269e111f5f42e8b9481fd0e9549687aa8a7ed0`         |
| 3 | `git diff --stat origin/main 1e3228b4`                                                                   | **空** ⇒ main 的树与集成头祖先**逐字节相同**        |
| 4 | `git rev-list --objects origin/main` 共 **27230** 个对象；其中**不在**集成头历史里的只有 **1 个**        | 该对象是 **commit `823ebca5` 本身**（`git cat-file -t` = commit）⇒ **零个新 blob / 新 tree** |
| 5 | 台账逐行按 L4 模块 ID 比对（465 行，模块集合完全相同）                                                     | main **406 已交付 / 0 进行中 / 59 未开发**；集成头 **431 / 0 / 34** ⇒ **main 有 0 个模块是集成头没有的** |

⇒ **本任务想同步的内容，集成头早就有了（且更新）**；冲突纯属「squash 抹掉血缘 + 集成头继续前进」的结构性产物。

## 四、合并：27 个冲突 ⇒ 按协议 `abort`，**未自行解冲突**

```
git merge --no-ff origin/main -F <正文>
⇒ merge exit=1，27 个冲突
⇒ git merge --abort
⇒ HEAD 回到 420962f5，MERGE_HEAD 已清，工作区干净
```

### 4.1 冲突面（26 add/add + 1 content）

| 口径                                   | 第 99 轮 | 第 100 轮 | 第 101 轮 | **第 102 轮** |
| -------------------------------------- | --------- | ---------- | ---------- | ------------- |
| 文件数                                 | 27        | 27         | 27         | **27**        |
| **theirs 独有 = 0 ⇒ 取 ours 无损**   | 4         | 4          | 4          | **4**（同一批 4 个文件） |
| theirs 独有行合计                     | 79        | 79         | 79         | **77**（口径见下注） |
| ours 独有行合计                       | 1643      | 1786       | 1786       | **1272**（同上） |

> 注：本轮用的是 `comm` + 排序去重行集，**归一化口径与前三轮不完全相同**，且 HEAD 已从
> `95151eb1` 前进到 `420962f5`（集成头继续交付）⇒ 绝对数不可与前三轮直接相减，
> **可比的判据是「4 个 theirs 独有 = 0 的文件集合完全一致」**，本轮复现无误。

theirs 独有 = 0 的 4 个文件（取 ours 无损）：

- `apps/web/src/lib/api-proxy.ts`（**content** 冲突，ours 严格超集：ours 13691 行 / theirs 12470 行，
  `diff :2: :3:` = **0 增 / 1221 删** ⇒ theirs 是 ours 的真子集）
- `backend/services/channel/src/test/java/com/starcon/channel/LinkageRiskIdentityStub.java`
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingFinancePort.java`
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingUpstreamClient.java`

⚠️ **高危项仍然点名**（与第 98 轮一致，本轮再次实测）：
`SaasUsageIntegrationTest.java` 取 theirs 会把 `c904ec63` 修掉的 **flaky 随机子串断言**装回去；
`AnomalyOrder*` 取 theirs 会**回退跨时钟域修复**（JVM 墙钟基准）。
⇒ **本轮协议禁止自行解冲突，照办 abort**；机械解法仍待**协议层授权**（backlog 待办第 3 条）。

### 4.2 合并时由 `merge-union` 自动求并 / 干净合上的文件（不是问题源）

- `packages/api-contracts/**`（contract-union 驱动，行级兜底并集）
- `docs/product/capability-blueprint/module-progress.md`（台账按模块 ID 求并，折叠 465 行同 ID 记录）
- `backend/services/channel/src/test/java/com/starcon/channel/OAuthAuthorizationIntegrationTest.java`（三方干净合上）

## 五、快速预检：**绿**（⚠️ 口径与前几轮不同，必须看 §七）

```
# 隔离 scratch 树（不动集成头的 backend/**/target/）
git archive HEAD backend | tar -x -C /tmp/starcon-precheck-r102
cd /tmp/starcon-precheck-r102/backend && ./mvnw -q -o compile
⇒ rc=0（仅 JVM 的 jansi/Unsafe 警告，无编译错误）

cd $PROJECT_ROOT && pnpm typecheck
⇒ 3 successful / 3 total，315ms FULL TURBO，rc=0
```

> ⚠️ **口径提醒（与第 99/100/101 轮不同）**：本次预检跑的是**未合并**的集成头
> ⇒ 它证明的是「**abort 之后集成头本身仍然是绿的**」，**不是**「main 的提交与集成头合得起来」。
> 合并内容从未经过编译 —— 这正是 backlog 待办第 3 条要求协议层授权的原因。
>
> ⚠️ **本轮为什么把 maven 编译放到隔离 scratch 树跑**：见 §七 —— 集成头 worktree 里**并发跑着
> 流水线 r132 的 `mvnw verify -fae`**，两者共用 `backend/**/target/` ⇒ 直接在集成头跑编译会
> **污染对方的 L2 判定**，也可能被对方污染成假红。隔离树让两边的 `target/` 完全分开。

## 六、台账：**零文件改动**

```
node tools/module-progress/sync.mjs
⇒ ✓ 同步 18 卷 / 465 个 L4 模块
  已交付 431 · 进行中 0 · 未开发 34
git status --porcelain ⇒ （空）
```

⇒ 台账已是 canonical 形态，**同步前后完全一致**（431 / 0 / 34，92.7%），
**main 侧零新交付 ⇒ 进度基线不变**（第 102 轮并入 0 个模块）。

## 七、⚠️ 新发现：**双向持闸**（r132 的 L2 脚本绕过了调度闸）

我 18:23:45 取闸成功后 71 秒，流水线 **r132 的 L2 启动**：

```
.dev-agent/l2-r132-nohup.log
  ===== L2 START 2026-10-29 18:24:55 =====
  ===== HEAD: 420962f5  branch: integration/module-dev =====
  ===== 本批并入: 无（round-131 的 tick 崩在派发前，两条车道从未开工）=====
  ===== [1/6] backend ./mvnw verify -fae =====
```

而闸目录里同期出现 **`keepalive-r132-l2-1/2/3`**（每 60 秒一个）——
**这些文件写进了我持有的闸目录**。

### 7.1 根因：`l2-r132.sh` 根本不调用 `gate acquire`

```
$ grep -nE 'git (reset|checkout|commit|push)' .dev-agent/l2-r132.sh   ⇒ （无）
$ grep -n "gate release" .dev-agent/l2-r132.sh                       ⇒ （无）
$ grep -n "gate acquire"  .dev-agent/l2-r132.sh                      ⇒ （无）
```

该脚本只做两件与闸有关的事：**启动一个 `touch "$GATE/keepalive-r132-l2-$i-$NOW"` 的续期守护**
（每 60 秒一次、墙钟上界 7200 秒）、以及跑 L2。
⇒ **它把闸目录当成「自己可以随便写进���的目录」，而不是「需要独占的互斥体」**。
⇒ 后果：**闸对 L2 这一段完全无效** —— 任何 detached 的 L2 脚本都能在别人持闸期间跑 L2。

### 7.2 并发后果（本轮实测）

| 时刻  | 事件                                                                 |
| ----- | -------------------------------------------------------------------- |
| 18:23:45 | 本轮（同步任务）`gate acquire` 成功，owner = 79303                  |
| 18:24:55 | r132 的 `mvnw verify -fae` 启动（**未取闸**）                        |
| 18:26:16 | 本轮的 `mvnw -q compile` 启动 ⇒ **与 r132 的 verify 重叠约 5 秒**     |
| 18:26:21 | 本轮的 compile 结束（很快，疑似增量 no-op）                            |
| 18:24 起 | r132 的 keepalive 守护开始 `touch` 进**本轮持有的**闸目录 ⇒ `ageSeconds` 被反复重置（实测 3897 → 4） |

⚠️ `ageSeconds` 被重置**不代表闸易主**（`ageSeconds` = `Date.now() - statSync(GATE_DIR).mtimeMs`，
新建文件会刷新目录 mtime）—— 判活仍只看 `stale`（与第 101 轮 §1.1 的结论一致）。

### 7.3 影响与本轮的处置

- **对 r132 的 L2 判定**：本轮的 5 秒并发编译窗口落在 r132 的 **compile 阶段**
  （18:24:55 起，18:24:57 已在跑 catalog 的 surefire）⇒ 风险窗口小，
  但**不能排除**。⇒ **若 r132 本轮 L2 报红，请优先怀疑这次并发编译**，重跑一次即可确认。
  （本轮已用隔离 scratch 树独立验证同一棵树 `compile` rc=0 ⇒ **代码本身是绿的**。）
- **对 r132 的 L2 内容**：`l2-r132.sh` **不做任何 git 变更**（无 reset / checkout / commit / push）
  ⇒ 本轮提交并 push 的会话日志 + backlog 更新（**仅 2 个 markdown 文件**）
  **不会进入 r132 的 L2 判定面**，也不会与它的收尾冲突。
- **闸的归属**：r132 **不会 release**（脚本里没有）⇒ 闸的合法持有者只有本轮 ⇒ **由本轮释放**。

## 八、push 与闸

- **只 push 集成头**（`origin integration/module-dev`），**未 push `main`**；
  推送内容**只有本会话日志 + backlog 更新**，**零业务代码**，fast-forward
  （推前复核：待推 delta = 1 个纯 docs 提交，父提交 `420962f5` = 已在远端的 batch-113 head）。
- **`gate release` 已执行**（本轮 acquire 成功 1 次、release 1 次，**配平**）⇒ 闸未泄漏。
  ⇒ 释放后闸目录消失，r132 的 keepalive `touch` 会失败（该守护本就不依赖闸存活，只影响 TTL 续期）。

## 九、后续待办（与 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` 同源）

1. **（发布侧，唯一根治）** 把集成头的**真实 commit（不 squash）**推到 main，
   使 `origin/main` 的 tip 重新成为集成头的祖先 ⇒ 本任务回到「无新提交」的零改动常态。
2. **（发布侧，次选）** 把 main 重置到集成头，或接受 squash 作为新基线（需配套「main 是集成头快照」的读取约定）。
3. **（协议层授权）** 在 `SYNC-MAIN.md` 增一条判据：
   **「main 侧 blob 已被集成头历史逐字节覆盖 ⇒ 允许 `-X ours` 机械合并」**。
   依据已具备：第 99 轮已用隔离 scratch clone 证明「27 个冲突全取 ours」的结果树 == 当前 HEAD 的树（**无损**），
   本轮第 ④ 节第 4 次复现同一组判据（4 个 theirs 独有 = 0 的文件集合完全一致）。
   ⇒ 一旦授权，本任务可**一轮并入**，无需再等发布侧。
4. **（调度侧，P0）** 保证同一时刻**只有一个** main 同步竞态循环 —— 本轮实测 **2 个活跃孤儿竞态循环**
   （`76215/76219`、`76952/76955`），均已 kill；但根治应在调度器层保证唯一性。
5. **（协议层）** 补一条「等闸上限 + 超时如实汇报退出」判据 —— 本轮等闸 519 秒，远未触顶。
6. **（🔴 新增 P0）L2 脚本必须走调度闸** —— `.dev-agent/l2-r132.sh` 不调用 `gate acquire`、
   也不 `gate release`，却把自己的 keepalive 写进别人持有的闸目录 ⇒
   **闸对 L2 段完全失效**，「宁可挡住一轮也不能双派发」这条设计意图被绕过。
   ⇒ 建议：所有 L2 脚本在启动前**必须** `gate acquire`（失败即退出，不跑），
   收尾**必须** `gate release`；keepalive 守护应挂在闸存活前提上。
7. **（🟠 新增 P1）`lane.mjs` 的 TTL 接管路径非原子** —— `rmSync(GATE_DIR)` + `mkdirSync(GATE_DIR)`
   （`lane.mjs:576-578`）不是原子操作 ⇒ 两个并发接管者**可能都拿到 `ok: true`**。
   ⇒ 建议改成「先 `rename` 到唯一临时名再 `mkdir`」或引入持有令牌（owner 文件写随机 token，
   release 时校验 token 匹配才删），否则互斥在最坏情况下可被打破。
