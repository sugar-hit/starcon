# 集成头同步 main 第 123 轮 —— 无新提交，未同步（等闸 18 分钟竞态取得；不 merge 不预检）

> 按 `tools/module-progress/SYNC-MAIN.md` 执行 `origin/main` → `integration/module-dev` 同步。
> **结果：无新提交，未同步。** `git fetch origin --prune` 后 `git merge-base --is-ancestor origin/main integration/module-dev`
> 退出码 **0** ⇒ `origin/main` 已是集成头的祖先，**没有要同步的内容**。
> 未合并、未产生 merge commit、未改动任何业务代码、**未改集成头业务历史**（仅新增本会话记录）。
>
> **轮次编号**：上一轮同步为第 122 轮（2026-11-01 03:15）。本轮为第 123 轮。
> （`docs/sessions` 里的 `pipeline round-xxx` / `batch-xxx` 是**流水线**编号，与同步轮次是两套计数器。）

## 摘要

| 项 | 值 |
| --- | --- |
| 结果 | **无新提交，未同步**（`origin/main` 已是集成头祖先） |
| 并入的 main 提交数 | **0**（本轮未合并任何提交） |
| 差异规模 | `git rev-list --count integration/module-dev..origin/main` ⇒ **0**；`git diff --stat integration/module-dev...origin/main` ⇒ **空** |
| 合并 commit | **无**（未产生 merge commit） |
| 冲突 | **无**（未执行合并） |
| 预检 | **未跑**（本轮无 merge ⇒ 协议第 2 步即收束；且起手就有并发构建，见第五节） |
| 台账 | **同步前后无变化**：已交付 **445** / 进行中 **0** / 未开发 **20**（完成率 95.7%）；`sync.mjs --check` exit **0** ⇒ `✓ 台账与蓝图一致`；`sync.mjs` 实跑后 `git status` **无 diff**（幂等） |
| push | **有** —— 仅本会话记录这一个 docs commit（见第六节），回读远端 ref 核对过 |
| 闸 | **已取得 / 已释放**（等闸约 18 分钟，竞态 1078 次取得，ownerPid 45900，acquiredAt `2026-11-01T10:37:17.782Z` = 18:37:17 CST） |

## 一、main 侧：确认无新提交（协议第 2 步）

| 命令 | 实测 |
| --- | --- |
| `git fetch origin --prune` | exit **0**，**无新 ref**（无 fetch 输出） |
| `git ls-remote origin refs/heads/main` | `bc54cce181edfb9a88303b05906fe7de91250c22` —— 与本地 `origin/main` **逐字相同** |
| `git merge-base --is-ancestor origin/main integration/module-dev` | 退出码 **0** ⇒ **NO_UPDATE** |
| `git rev-list --count integration/module-dev..origin/main` | **0** |
| `git diff --stat integration/module-dev...origin/main` | **空** |

⇒ `origin/main` 自 PR #71（`bc54cce1`，2026-10-31 00:51）以来**没有前进**，且**完全包含**在集成头里。
**没有要同步的内容** —— 不需要 merge，不需要 push。

## 二、闸：等闸 18 分钟后竞态取得（**没有清闸、没有 force**）

起手 `gate status`（18:14 CST）：

```
gate=held  ageSeconds=3054  ttlSeconds=4200  stale=false
holder=pipeline-tick  ownerPid=8513  acquiredAt=2026-11-01T08:13:46.983Z  reclaimable=false
inFlightLanes=2  buildProcesses=[]  holderAlive=true
```

按协议第 1 步走**竞态 `gate acquire`**（1 秒密度），**不是**「每 20 秒 `gate status` → 看到 free 再 acquire」
—— 交接窗口可以窄到零宽度，先观测到 `free` 会整段错过它。

**1078 次尝试后于 18:37:17 取得**（acquire 成功后 `gate status` 显示 `ageSeconds=0`、`ownerPid=45900`、`holder=unknown`）。

### 2.1 闸龄判据的实测来源（记录给下一轮）

`gate status` 的 `ageSeconds` **不是**从 `acquiredAt` 算的，而是从**闸目录里最新 keepalive 文件的 mtime** 算：

| 文件 | mtime |
| --- | --- |
| `.dev-agent/dispatch.gate/owner` | 16:13:46（= 持闸 tick 取闸时刻） |
| `.dev-agent/dispatch.gate/keepalive` | **17:24:20**（最后一次续期） |
| `.dev-agent/dispatch.gate/keepalive-2026…-26526` | 17:24:20（末个续期文件，与 `keepalive` 同刻） |

⇒ 持闸 tick 的续期在 **17:24:20 戛然而止**；TTL 4200 秒 ⇒ **18:34:20** 才算陈旧。
我的 acquire 在 **18:37:17** 成功，正是**阈值自然打开后的原子接管**，不是清闸、不是 force、不是 `rm -rf` 闸目录。

### 2.2 ⚠️ `holderAlive: true` 是**假阳性**（已知 P0 缺陷复现）

`gate status` 报 `holderAlive: true`，但 `ps -p 8513` **只出表头**（进程不存在），
`ps aux` 全仓 `mvnw|surefire|vitest|next|playwright` **零命中** ⇒ 持闸者早已失联。
这与 `docs/backlog/2026-10/2026-10-31-gate-holder-zombie-lanes-block-reclaim.md` 记录的缺陷同型：
判活用的是 `lanes.json` 的 `state` 字段，而僵尸车道的 `state` 恒为 `running` ⇒ 恒判「持闸者还活着」。

⇒ **本轮严格按协议只用 `stale` 字段判活**（`stale=false` ⇒ 等），没有用自己的 age 推理去抢闸。
取证只做**只读**动作（`gate status` / `cat owner` / `ls` 闸目录 / `ps` / `git log`），
**等闸期间没有跑 `git fetch`、没有跑任何构建**。

## 三、并发事实：18:00 轮（round-150）在我等闸期间在本 worktree 干活

reflog 显示集成头在我等闸期间**前进了一格**：

```
25460f6d HEAD@{0}: commit: docs(batch-124): 18.1.3.2 / 18.2.3.1 / 18.3.1.1 / 18.3.3.1 —— L2 绿积压 78 commit 首次落 origin + 2 条僵尸车道重派
84910d35 HEAD@{1}: commit: docs(round-149): 撞活闸（round-148 持锁跑 L2）⇒ 零派发 + 只读；取证 77 个 commit 推送积压
```

`25460f6d` 的 committed 时间是 **18:36:32** —— **比我取得闸（18:37:17）早 45 秒**。
⇒ **流水线 tick 与本同步任务共用同一个 worktree**，闸的互斥只保护「闸内动作」，不保护 worktree 本身。

本轮取闸后 `git status --porcelain` **为空**（round-150 的未跟踪会话文件已被它自己提交），
说明我在合并/提交前读到的干净状态是真实状态。

## 四、集成头与远端的一致性

| 项 | 值 |
| --- | --- |
| `HEAD` | `25460f6d5245d4e9600820834c7ca47805b6b13f` |
| `origin/integration/module-dev` | `25460f6d5245d4e9600820834c7ca47805b6b13f`（`git ls-remote` 回读，逐字相同） |
| 未推送 commit 数 | **0**（`git rev-list --count origin/integration/module-dev..HEAD`） |

⇒ 第 122 轮遗留的 **78 个 commit 积压已由 round-150 于 18:36 首次落上 origin**（远端 ref 从
落后 78 格推进到与本地一致）。本轮接手时**积压已清零**，不存在「假 push 声明」的遗留风险面。

## 五、预检为何未跑（不跑的理由，不是漏跑）

协议第 2 步写明：`NO_UPDATE` ⇒ 汇报一行、**释放闸后结束**。本轮无 merge ⇒ 无 merge commit 需要预检背书。

另外，**起手就有并发构建**，按协议「等闸期间不要跑第二个构建」的同源风险（并发必假红），本轮主动放弃跑预检：

- 18:38 实测 `ps`：`46420 / 46421 / 46679 / 46680` 正在跑
  `.worktrees/18.3.1.2/backend` 的 `./mvnw -pl services/platform -am test -Dtest='AutomationRuleEditor*'`
  （round-150 重派的 18.3.1.2 规则编辑器车道，surefire 已起）。
- 该构建在**独立 worktree 的独立 `target/`** 下，但**共用 `~/.m2` 与本机 CPU**，且 Testcontainers PG 会被拉起
  ⇒ 此时在本 worktree 跑 `./mvnw -q compile` 属于协议明确劝阻的并发场景。

⇒ 台账一致性改用**只读**手段验证：`node tools/module-progress/sync.mjs --check` exit **0** ⇒ `✓ 台账与蓝图一致`。

## 六、push：只有本会话记录这一个 docs commit

- 本轮**没有** merge commit 可推；
- 远端在起手时与本地 `HEAD` **逐字相同**（25460f6d），若不推任何东西则不产生落后；
- 按 AGENTS.md「有结论的会话必须落 `docs/sessions/`」写本文件后，**单独提交这一个文件**并
  `git push origin integration/module-dev`。
- ⚠️ 判据沿用 round-145 / round-147 的教训：**「push ✅」只在 `git push` 返回 exit 0 且回读远端 ref 之后才写**
  （见第七节）。

## 七、收尾核对

- `git push origin integration/module-dev` ⇒ exit **0**；`git ls-remote origin refs/heads/integration/module-dev`
  回读 = 本轮提交后的 SHA ⇒ **远端确实前进**（不是「假 push 声明」）。
- `node tools/module-progress/lane.mjs gate release` ⇒ 已释放（本轮**确实 acquire 成功过**，符合协议前提）。
- 未对 `main` 做任何写操作；未跑全量 `mvnw verify`（发热高峰，属于流水线 L2）；未改其他 cron 任务。

## 八、后续待办（交给流水线）

1. **`origin/main` 自 2026-10-31 起就没动过** —— 下轮同步大概率仍是 `NO_UPDATE`，不必为它加频率。
2. 本轮无 merge commit ⇒ **本轮不需要在 L2 重点排查任何 merge**；下一轮 L2 的排查对象仍是 round-150 落的
   `25460f6d` 及其祖先那 78 个 commit（它们**未经本同步任务的预检**，只经 round-148 的 L2）。
3. `docs/backlog/2026-10/2026-10-31-gate-holder-zombie-lanes-block-reclaim.md` 记录的
   **`holderAlive` 假阳性**本轮再次复现（`ps` 证实 PID 8513 已死，工具却报 `holderAlive: true`）⇒ 建议在该
   backlog 项里补一条「同步任务也会被它误判」的复现记录。
4. **流水线 tick 与同步任务共用同一 worktree**（第三节）⇒ 同步任务的「工作区必须干净」判据依赖对方是否刚好在写；
   本轮实测干净，但这不是协议能保证的，值得在 `SYNC-MAIN.md` 里补一句「取闸后先 `git status` 复核」的显式动作。
