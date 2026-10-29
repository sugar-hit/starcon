# 2026-10-29 19:15 · 集成头同步 main 第 103 轮 —— 冲突未并入（27 个，同一集合）＋ 🔴 L2 假红根因字节级定位：同步任务的 merge 冲突标记污染了并发编译

> 按 `tools/module-progress/SYNC-MAIN.md` 执行 `origin/main` → `integration/module-dev` 同步。
> **结果：冲突未并入**。`origin/main` tip 仍是 `823ebca5`（与第 79–102 轮**同一颗 squash**），
> 冲突面 **27 个**（26 add/add + 1 content），与第 99 / 100 / 101 / 102 轮**同一集合**。
>
> **本轮新增的两点**：
> ① **L2 红的根因被独立定位到「字节级」** —— r132 的 `mvnw verify -fae` 在 identity 模块编译失败，
> 报错行号 **95 / 96 / 99 / 103** 与**第 102 轮 merge 冲突标记的行号逐行重合**
> （`<<<<<<<` 在 95、`=======` 在 99、`>>>>>>>` 在 103）⇒ **编译器读到的不是 HEAD 的源码，
> 而是第 102 轮 merge 写到工作区里的冲突标记版**。这不是代码缺陷，是**同步任务与 L2 并发**造成的假红。
> ② **`stale` 在 L2 运行期间恒为 false** ⇒ TTL 接管路径**机制性不可达**（详见 §一.2），
> 本轮等闸 **96 分钟**（19:15 → 20:51）才通过 TTL 接管取到闸。

## 摘要

| 项                     | 值                                                                       |
| ---------------------- | ------------------------------------------------------------------------ |
| 结果                   | **冲突未并入**（27 个冲突，26 add/add + 1 content）                      |
| **并入的 main 提交数** | **0**（冲突中止，无 merge commit）                                       |
| main 新提交            | **无**（`origin/main` tip 仍是 `823ebca5`，与第 79–102 轮同一颗 squash） |
| 差异规模               | 1 个提交 / 1032 个文件（+510134 / −45765）—— **行数是 squash 的假象**    |
| 冲突                   | **27 个**（与第 99 / 100 / 101 / 102 轮同一集合）                        |
| **main 侧新内容**      | **0**（本轮独立复现的三条证据，见 §三）                                  |
| 预检                   | **绿**（隔离 scratch 树 `./mvnw -q compile` rc=0；`pnpm typecheck` 3/3） |
| 台账                   | **431 已交付 / 0 进行中 / 34 未开发**（465 总数，92.7%），**零文件改动** |
| push                   | **未 push**（本轮零业务改动，无可推内容）                                |
| 闸                     | **已释放**                                                               |
| **等闸时长**           | **96 分钟**（19:15:00 → 20:51:40，TTL 接管）                             |

## 一、取闸：等 96 分钟，TTL 接管（**未抢未清未 force**）

首读 `gate status`（19:15:00）⇒ `held` / `ageSeconds=13` / `ttl=4200` / **`stale=false`**
⇒ 按协议判定为「持闸者活着」⇒ **等它释放**，不抢、不清、不 force。

### 1.1 持闸者是谁 —— 活性取证全在闸外

| 取证                             | 结果                                                                                  |
| -------------------------------- | ------------------------------------------------------------------------------------- |
| `.dev-agent/dispatch.gate/owner` | PID `83025` + `2026-10-29T10:39:18.662Z`（18:39 起）                                  |
| `ps -p 83025`                    | **查无此进程** ⇒ owner PID 不是持闸者（与协议第 1 步提醒一致）                        |
| 闸目录内容                       | 65 个 `keepalive-r132-l2-*`（**全部由 L2 脚本的守护新建**）                           |
| 同 worktree 构建进程（`ps`）     | `mvnw verify -fae`（PID 79641）+ surefire ⇒ **L2 在跑**（18:24:55 起，19:29:02 结束） |
| 流水线会话                       | `mvs_a4a84e3be1184622a226ffc07cfa94a6`（并行车道流水线）状态 `started` ⇒ **活的**     |

⇒ 结论：**闸被活着的流水线 tick 合法持有着** ⇒ 等它是**唯一正确动作**。

### 1.2 ⚠️ `stale` 在 L2 运行期间**永远翻不过 true**（机制性，不是「等得不够久」）

`lane.mjs:568-588` 的接管判据是 `age = (Date.now() - statSync(GATE_DIR).mtimeMs) / 1000`，
`stale = age > 4200`（`lane.mjs:620`）。
而 r132 的 L2 守护（`.dev-agent/l2-r132.sh:42-50`）**每 60 秒 `touch` 一个新建文件**进闸目录：

```bash
for i in $(seq 1 120); do
  NOW=$(date +%s)
  [ $(( NOW - START )) -ge 7200 ] && exit 0
  touch "$GATE/keepalive-r132-l2-$i-$NOW"     # ← 新建文件 ⇒ 刷新目录 mtime
  sleep 60
done
```

⇒ **新建文件会刷新目录 mtime**（脚本注释第 13 行自己写明了这一点）
⇒ `ageSeconds` 被反复拉回 ~60s ⇒ **`stale` 恒为 false** ⇒
**TTL 接管这条路在 L2 运行期间从机制上就走不通**。

⇒ 本轮实况：L2 **19:29:02** 退出后守护随之停止（最后一个 keepalive 19:41:39），
闸开始正常老化（19:38 `ageSeconds=589`）⇒ TTL 接管在 **~20:52** 变得可达。

### 1.3 等闸方式：单实例密集竞态（协议第 1 步「等闸」写法）

本轮用**单实例 + 原子锁**的竞态循环（`.tmp/sync-race-gate.sh`），避免第 102 轮记录的
「两个孤儿竞态循环同时在 TTL 边界接管 ⇒ 双方都拿到 `ok: true` ⇒ 互斥失效」隐患：

```bash
mkdir "$LOCKDIR" 2>/dev/null || exit 2      # 原子单实例保护
for i in $(seq 1 14400); do                 # 上界 4 小时
  if node tools/module-progress/lane.mjs gate acquire | grep -q '"ok": true'; then
    printf 'ACQUIRED ...' > "$OUT"; exit 0
  fi
  sleep 1
done
```

⇒ 全程**只有一个**竞态循环在跑（`ps` 复核：无第二个竞态进程）。
⇒ **20:51:40 第 2246 次尝试成功**（owner PID `8187`，`gate status` 立即变 `ageSeconds=0`）。

### 1.4 取闸前最后的活性复核（**接管前必做**）

```
ps aux | grep -E 'mvnw|surefire|lane.mjs'   ⇒ 空（无构建在跑）
闸目录 keepalive 最新 mtime                     ⇒ 19:41:39（已停 61 分钟）
流水线会话 mvs_03f52330… / mvs_a4a84e3b…      ⇒ error: net::ERR_CONNECTION_CLOSED（**持闸 tick 已崩**）
```

⇒ 持闸 tick **已崩**（`ERR_CONNECTION_CLOSED`），无构建在跑，keepalive 已停
⇒ **TTL 接管是安全的**（不是「抢一个活着的闸」）。

## 二、main 侧：仍然**没有**新提交

```
git fetch origin --prune                     ⇒ 无新引用
git log --oneline integration/module-dev..origin/main
⇒ 823ebca5 Integration/module dev (#66)      # 唯一 1 个
git diff --stat integration/module-dev...origin/main | tail -1
⇒ 1032 files changed, 510134 insertions(+), 45765 deletions(-)
merge-base = 6522c497
```

⚠️ **+510134 / −45765 是 squash 的假象**（见 §三）。
集成头已领先 main **1218 个提交**。

## 三、main 侧零新内容：第 6 次**独立**复现（不引用上轮结论）

| #   | 证据                                                                                                                       | 本轮结果                                                                       |
| --- | -------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| 1   | `git rev-parse origin/main^{tree}`                                                                                         | `83269e111f5f42e8b9481fd0e9549687aa8a7ed0`                                     |
| 2   | 遍历集成头**全部祖先**，找 `tree` 相同的那个                                                                               | 命中 `1e3228b4`（第 75 轮的会话日志提交）⇒ **与 main 的树逐字节相同**          |
| 3   | `git rev-list --objects origin/main`（27230 个）vs `git rev-list --objects integration/module-dev`（46667 个），**集合差** | **差集只有 1 个对象**，且是 `commit 823ebca5` 本身 ⇒ **零个新 blob / 新 tree** |
| 4   | 台账按 L4 模块 ID 比对（465 行，模块集合完全相同）                                                                         | main **409 已交付 / 2 进行中 / 60 未开发**；集成头 **431 / 0 / 34**            |
| 5   | `git merge-summary.mjs --base origin/main --head integration/module-dev`                                                   | 集成头**领先 25 项交付**（工作台 5 项 + 利润分析 17 项 + 广告 3 项）           |

⇒ **本任务想同步的内容，集成头早就有了（且更新）**；冲突纯属「squash 抹掉血缘 + 集成头继续前进」的结构性产物。

## 四、合并：27 个冲突 ⇒ 按协议 `abort`，**未自行解冲突**

真实合并（在拿到闸后执行）：

```
git merge --no-ff origin/main -F <正文含并入提交清单>
⇒ merge exit=1
⇒ merge-union 自动求并：packages/api-contracts/** + docs/…/module-progress.md（台账 465 行折叠）
⇒ git diff --name-only --diff-filter=U  ⇒ 27 个冲突文件
git merge --abort
⇒ MERGE_HEAD 已清，HEAD 回到 a6ce2bf5，工作区干净
```

### 4.1 冲突面（26 add/add + 1 content）—— 与第 99 / 100 / 101 / 102 轮**同一集合**

| 口径                               | 第 99 轮 | 第 100 轮 | 第 101 轮 | 第 102 轮 | **第 103 轮**            |
| ---------------------------------- | -------- | --------- | --------- | --------- | ------------------------ |
| 文件数                             | 27       | 27        | 27        | 27        | **27**                   |
| **theirs 独有 = 0 ⇒ 取 ours 无损** | 4        | 4         | 4         | 4         | **4**（同一批 4 个文件） |

theirs 独有 = 0 的 4 个文件（取 ours 无损）：

- `apps/web/src/lib/api-proxy.ts`（**content** 冲突）
- `backend/services/channel/src/test/java/com/starcon/channel/LinkageRiskIdentityStub.java`
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingFinancePort.java`
- `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingUpstreamClient.java`

### 4.2 🔴 本轮新增的**字节级**判据：27 个冲突文件的 theirs blob **全部 = 集成头自己的旧快照**

```
for f in <27 个冲突文件>; do
  git rev-parse "origin/main:$f"  vs  git rev-parse "1e3228b4:$f"
done
⇒ 27 same / 0 differs
```

⇒ **main 侧那 27 个文件的内容，就是集成头自己在第 75 轮时的旧快照**，
集成头后来把它们改了（所以 ours ≠ theirs，27/27 全部不同）。
⇒ 冲突**不是真分叉**，是 git 对「同一份内容的两种历史」的机械撞车。

### 4.3 🔴 `-X ours` 的**无损性本轮再次被证明到等价性级别**

在隔离 scratch clone（`git clone --shared --no-checkout`，**不碰集成头 worktree**）里：

```
git merge --no-ff --no-commit -X ours 823ebca5
⇒ Automatic merge went well; stopped before committing as requested
⇒ 未解决冲突：0
git write-tree                ⇒ 439a1388ec2e8336f95558d9c517ae0972221faa
git rev-parse HEAD^{tree}     ⇒ 439a1388ec2e8336f95558d9c517ae0972221faa   ← 相同
```

⇒ **「27 个冲突全部取 ours」的结果与「什么都不做」的 HEAD 逐字节相同** ⇒ 取 ours 是**可证明的无损操作**。
（与第 99 轮结论一致；第 99 轮记录的树 hash 不同，是因为那轮 HEAD 还没前进到 `a6ce2bf5`。）

⚠️ **但本轮仍按协议 `abort`，没有执行这个机械解法** ——
`SYNC-MAIN.md:23` 与 `:153` 仍明令「❌ 自行解冲突」「不要自己解」，
而 backlog 待办第 3 条要求的**协议层显式授权**至今**未落地**（`SYNC-MAIN.md` 正文无该判据）。
⇒ **授权到位前，本任务不得自行解冲突。**

## 五、🔴🔴 本轮最重要的发现：**L2 的红是同步任务造成的假红**（字节级归因）

r132 的 L2 在 **19:29:02** 以 `MVN_EXIT=1` 结束，失败点是 **identity 模块编译**：

```
[ERROR] .../identity/NotificationInboxController.java:[95,1] 非法的表达式开始
[ERROR] .../identity/NotificationInboxController.java:[96,21] 需要>或','
[ERROR] .../identity/NotificationInboxController.java:[99,1] 非法的表达式开始
[ERROR] .../identity/NotificationInboxController.java:[103,1] 非法的表达式开始
[ERROR] .../identity/NotificationPreferenceService.java:[230,1] 非法的表达式开始
```

### 5.1 报错行号 = 冲突标记行号（**逐行重合**）

在隔离 scratch clone 里真实复现第 102 轮的 merge，得到的 `NotificationInboxController.java`：

```
 94:                 List.of(
 95: <<<<<<< HEAD                          ← 编译报 [95,1] 非法的表达式开始
 96:                     "1.3.1.2 通知偏好…已交付…",   ← 编译报 [96,21] 需要>或','
 99: =======                               ← 编译报 [99,1] 非法的表达式开始
103: >>>>>>> 823ebca5                      ← 编译报 [103,1] 非法的表达式开始
```

⇒ **报错行号与冲突标记行号一一对应** ⇒ 编译器读到的**不是 HEAD 的源码**，
而是**第 102 轮的 merge 写到工作区里的冲突标记版**。

### 5.2 时间线闭合

| 时刻     | 事件                                                                                    |
| -------- | --------------------------------------------------------------------------------------- |
| 18:24:55 | r132 的 `mvnw verify -fae` 启动（**未取闸**，见第 102 轮 §七）                          |
| 18:25:20 | 第 102 轮同步任务 `git reset --hard`（reflog）                                          |
| 18:26:04 | 第 102 轮同步任务 `git reset --hard`（reflog）                                          |
| 18:26:22 | 第 102 轮同步任务 `git reset --hard`（reflog）⇒ **27 个冲突文件 mtime 全部 = 18:26:22** |
| ~18:26   | **L2 的编译器正在读 identity 源码** ⇒ 读到的是冲突标记版 ⇒ 编译失败                     |
| 19:29:02 | L2 结束，`MVN_EXIT=1`                                                                   |

`git reflog`（集成头 worktree）：

```
a6ce2bf5 HEAD@{2026-10-29 18:30:13}: commit: docs(sessions): 集成头同步 main 第 102 轮 …
420962f5 HEAD@{2026-10-29 18:26:22}: reset: moving to HEAD
420962f5 HEAD@{2026-10-29 18:26:04}: reset: moving to HEAD
420962f5 HEAD@{2026-10-29 18:25:20}: reset: moving to HEAD
```

⇒ **27 个冲突文件的 mtime 全部等于 18:26:22**（`27/27` 同一个时间戳），
而 L2 的 identity 编译恰好在这个窗口内 ⇒ **编译输入被污染**。

### 5.3 反证：HEAD 的代码本身是绿的

```
git archive HEAD backend | tar -x -C <隔离 scratch 树>
cd <隔离 scratch 树>/backend && ./mvnw -q -o -pl services/identity -am compile
⇒ COMPILE_RC=0（只有 JVM 的 jansi/Unsafe 警告，无编译错误）
```

⇒ **identity 模块在 HEAD 上编译通过** ⇒ L2 的红**不是代码缺陷**。

### 5.4 ⚠️ 给流水线 r132 的归因建议（**请优先看这条**）

r132 的 L2 红了，而本批**零并入**（最后 3 个提交全是 `.md`）。
⇒ **不要走 `git bisect`**（本批没有业务代码可二分，bisect 必然空手而归）。
⇒ **L2 红的第一嫌疑是第 102 轮同步任务在 18:25–18:26 与 L2 并发**：
同步任务在同一个 worktree 里做 merge → 冲突标记落到工作区 → L2 的编译器读到标记版。
⇒ **重跑一次 L2 即可确认**（本轮已在隔离 scratch 树独立验证同一棵树 identity 编译 rc=0）。

> ⚠️ 另注：r132 流水线会话在排查此红时**已崩**（`net::ERR_CONNECTION_CLOSED`），
> 且它当时的假设是「`git reset` 恢复了旧文件导致时钟/边界断言失败」——
> 本轮证据表明 **`git reset` 恢复的文件内容与 HEAD 完全一致**（`git status` 干净、
> 隔离树编译 rc=0），**真正的污染源是冲突标记**而不是恢复后的内容。

## 六、快速预检：**绿**（⚠️ 口径：跑的是**未合并**的集成头）

```
# 隔离 scratch 树（不动集成头的 backend/**/target/）
git archive HEAD backend | tar -x -C <隔离 scratch 树>
cd <隔离 scratch 树>/backend && ./mvnw -q -o compile
⇒ COMPILE_RC=0（仅 JVM 的 jansi/Unsafe 警告，无编译错误）

cd $PROJECT_ROOT && pnpm typecheck
⇒ 3 successful / 3 total，513ms FULL TURBO，rc=0
```

> ⚠️ **口径提醒**：本次预检跑的是**未合并**的集成头 ⇒ 它证明的是
> **「abort 之后集成头本身仍然是绿的」**，**不是**「main 的提交与集成头合得起来」。
> 合并内容从未经过编译 —— 这正是 backlog 待办第 3 条要求协议层授权的原因。

## 七、台账：**零文件改动**

```
node tools/module-progress/sync.mjs
⇒ ✓ 同步 18 卷 / 465 个 L4 模块
  已交付 431 · 进行中 0 · 未开发 34
git status --porcelain ⇒ （空）
```

⇒ 台账已是 canonical 形态，**同步前后完全一致**（431 / 0 / 34，92.7%），
**main 侧零新交付 ⇒ 进度基线不变**（本轮并入 0 个模块）。

> ⚠️ **口径注**：用 `grep -c "| 已交付 |"` 之类的朴素统计会数出 **434 / 2 / 35**，
> 那是把文件末尾汇总表里的行也算进去了。**canonical 口径是 `sync.mjs` 的 465 行模块表**
> ⇒ **431 / 0 / 34**（`sync.mjs` 自己的输出即此数）。

## 八、push 与闸

- **未 push**（本轮**零业务改动**：无 merge commit、台账无变化 ⇒ 无可推内容）。
  集成头 tip 仍是 `a6ce2bf5`（= `origin/integration/module-dev`）。
- **未 push `main`**：全程只读 `origin/main`。
- **`gate release` 已执行**（本轮 acquire 成功 1 次、release 1 次，**配平**）⇒ 闸未泄漏。

## 九、后续待办（与 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` 同源）

1. **（发布侧，唯一根治）** 把集成头的**真实 commit（不 squash）**推到 main，
   使 `origin/main` 的 tip 重新成为集成头的祖先 ⇒ 本任务回到「无新提交」的零改动常态。
2. **（发布侧，次选）** 把 main 重置到集成头，或接受 squash 作为新基线。
3. **（协议层授权，仍未落地）** 在 `SYNC-MAIN.md` 增一条判据：
   **「main 侧 blob 已被集成头历史逐字节覆盖 ⇒ 允许 `-X ours` 机械合并」**。
   依据本轮**第 6 次独立复现**：① main 侧唯一独有对象是 commit 本身（零新 blob）；
   ② 27 个冲突文件的 theirs blob **全部 = 集成头旧快照 `1e3228b4`**；
   ③ `-X ours` 的结果树 **== HEAD 的树**（等价性证明）。
   ⇒ 一旦授权，本任务可**一轮并入**，无需再等发布侧。
4. **（🔴 P0，本轮新增实证）同步任务与 L2 的并发必须真正互斥** ——
   本轮**实测到**同步任务的 merge 冲突标记会让**并发跑的 L2 编译出假红**（行号逐行重合），
   而这类假红会被误判为「代码回归」，进而浪费 `git bisect` 与修复任务。
   ⇒ 现状是「闸对 L2 段完全无效」（`l2-r132.sh` 不调用 `gate acquire`，第 102 轮已实证）。
   ⇒ 建议：所有 L2 脚本启动前**必须** `gate acquire`（失败即退出，不跑），收尾**必须** `gate release`；
   同时**同步任务在拿到闸之前，绝不允许在集成头 worktree 里做 merge**
   （本轮 §一.3 的「拿到闸再动手」顺序，正是为了避免重演第 102 轮的污染）。
5. **（🔴 P0，本轮新增实证）`lane.mjs` 的 TTL 接管路径在 L2 运行期间**机制性不可达** ——
   L2 keepalive 守护每 60 秒**新建文件**刷新闸目录 mtime ⇒ `ageSeconds` 恒 < 60 ⇒ `stale` 恒 false
   ⇒ **只要 L2 不走闸，就没人能接管孤儿闸**（本轮因此白等 96 分钟）。
   ⇒ 与待办 4 同源：**L2 必须走闸**，或 keepalive 必须改成不刷新目录 mtime 的形式。
6. **（协议层）** 补一条「等闸上限 + 超时如实汇报退出」判据 —— 本轮等闸 **96 分钟**仍未取到闸，
   直到 L2 崩溃 + TTL 到期才接管。
7. **（🟠 P1）`lane.mjs` 的 TTL 接管路径非原子** ——
   `rmSync(GATE_DIR)` + `mkdirSync(GATE_DIR)`（`lane.mjs:576-578`）不是原子操作
   ⇒ 两个并发接管者**可能都拿到 `ok: true`**。
   ⇒ 建议改成「先 `rename` 到唯一临时名再 `mkdir`」或引入持有令牌（owner 文件写随机 token，
   release 时校验 token 匹配才删），否则互斥在最坏情况下可被打破。
8. **（🟠 P1）`gate release` 应只删自己持有的闸** —— 现实现是无条件 `rmSync(GATE_DIR)`，
   任何没拿到闸的一方误调 `gate release` 都会**删掉别人活着的闸**（破坏互斥）。
   本轮因为**确实 acquire 成功过**，才安全地执行了 release。
