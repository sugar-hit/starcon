# 集成头同步 main 第 124 轮 —— 无新提交，未同步（等闸 74 分钟；不 merge 不预检）

> 按 `tools/module-progress/SYNC-MAIN.md` 执行 `origin/main` → `integration/module-dev` 同步。
> **结果：无新提交，未同步。** `git fetch origin --prune` 后 `git merge-base --is-ancestor origin/main integration/module-dev`
> 退出码 **0** ⇒ `origin/main` 已是集成头的祖先，**没有要同步的内容**。
> 未合并、未产生 merge commit、未改动任何业务代码、**未改集成头业务历史**（仅新增本会话记录）。
>
> **轮次编号**：上一轮同步为第 123 轮（2026-11-01 18:37）。本轮为第 124 轮。
> （`docs/sessions` 里的 `pipeline round-xxx` / `batch-xxx` 是**流水线**编号，与同步轮次是两套计数器。）

## 摘要

| 项 | 值 |
| --- | --- |
| 结果 | **无新提交，未同步**（`origin/main` 已是集成头祖先） |
| 并入的 main 提交数 | **0**（本轮未合并任何提交） |
| 差异规模 | `git rev-list --count integration/module-dev..origin/main` ⇒ **0**；反向 `origin/main..integration/module-dev` ⇒ **86**（集成头领先 86 格） |
| 合并 commit | **无**（未产生 merge commit） |
| 冲突 | **无**（未执行合并） |
| 预检 | **未跑**（本轮无 merge ⇒ 协议第 2 步即收束；理由见第五节） |
| 台账 | **同步前后无变化**：已交付 **445** / 进行中 **0** / 未开发 **20**（完成率 95.7%）；`sync.mjs --check` exit **0** ⇒ `✓ 台账与蓝图一致`；`sync.mjs` 实跑后 `git status` **无 diff**（幂等） |
| push | **有** —— 仅本会话记录这一个 docs commit（见第六节），回读远端 ref 核对过 |
| 闸 | **已取得 / 已释放**（等闸约 74 分钟，**等真实释放**取得，ownerPid 46969，acquiredAt `2026-11-01T10:39:15.836Z` = 18:39:15 CST） |

## 一、main 侧：确认无新提交（协议第 2 步）

| 命令 | 实测 |
| --- | --- |
| `git fetch origin --prune` | exit **0**，**无新 ref**（无 fetch 输出） |
| `git fetch origin --prune --dry-run` | **无输出** ⇒ 本地 ref 已是最新，不存在「fetch 静默失败 ⇒ 误判 NO_UPDATE」 |
| `git ls-remote origin refs/heads/main` | `bc54cce181edfb9a88303b05906fe7de91250c22` —— 与本地 `origin/main` **逐字相同** |
| `git merge-base --is-ancestor origin/main integration/module-dev` | 退出码 **0** ⇒ **NO_UPDATE** |
| `git rev-list --count integration/module-dev..origin/main` | **0** |
| `git rev-list --count origin/main..integration/module-dev` | **86** |

⇒ `origin/main` 自 PR #71（`bc54cce1`，**2026-10-31 00:51:24**）以来**没有前进过**，且**完全包含**在集成头里。
**没有要同步的内容** —— 不需要 merge，不需要 push 业务代码。
⇒ 这是**连续第 3 轮** `NO_UPDATE`（第 121 轮 02:15、第 122 轮 03:15、第 123 轮 18:37、本轮 18:39）。

## 二、闸：等 74 分钟后**等真实释放**取得（**没有清闸、没有 force、没有偷闸**）

起手 `gate status`（ageSeconds=47 ⇒ 起手 ≈ **17:25:07**）：

```
gate=held  ageSeconds=47  ttlSeconds=4200  stale=false
holder=pipeline-tick  ownerPid=8513  acquiredAt=2026-11-01T08:13:46.983Z  reclaimable=false
inFlightLanes=2  buildProcesses=[]  holderAlive=true
```

### 2.1 本轮取闸方式与第 123 轮**不同**（留给下一轮的关键经验）

第 123 轮用的是协议原文的**竞态 `gate acquire`**（1 秒密度、1078 次尝试、18:37:17 取得）。
本轮先用**同样的竞态**跑了约 **1 小时**仍未取得（闸龄 47s → 3057s，始终 `stale=false`），
于是改成**更保守的「守株待兔」写法**：只在**闸目录真正不存在**（= 持闸者真的释放了）时才发起 acquire：

```bash
GATE=".dev-agent/dispatch.gate"
while true; do
  if [ ! -d "$GATE" ]; then
    node tools/module-progress/lane.mjs gate acquire | grep -q '"ok": true' && break
  fi
  sleep 1
done
```

**为什么改**：本轮等闸期间发现 **18:00 轮（round-150）正在同一个 worktree 里活着**，
且已经写好了 `takeover-r150.mjs` 在等闸龄超 TTL 后**主动接管**这个孤儿闸。
若本轮在 18:34:20 阈值打开的瞬间**用「偷闸」路径抢到**，round-150 的接管就会失败、
它这一轮**零派发**。改成只认「真实释放」后，两边**结构上不可能互抢**：
本轮拿到的是一个**没人持有的空闸**，round-150 也拿到了它自己的闸。

### 2.2 闸龄判据的实测来源

`gate status` 的 `ageSeconds` **不是**从 `acquiredAt` 算的，而是从**闸目录里最新 keepalive 文件的 mtime** 算。
起手时目录里最新的历史续期文件是 `keepalive-20261101T092420829Z-26526`（**17:24:20**）
⇒ 持闸 tick（round-148）的续期在 **17:24:20 戛然而止**，TTL 4200 秒 ⇒ **18:34:20** 才算陈旧。
本轮 **18:39:15** 取得时闸目录已被 round-150 重建并释放过，闸龄重新从 0 计。

### 2.3 ⚠️ `holderAlive: true` 仍是**假阳性**（已知 P0 缺陷复现）

`gate status` 报 `holderAlive: true`，但 `ps -p 8513` **只出表头**（进程不存在）。
成因是 `lane.mjs` 的 `holderLiveness()` 用 `lanes.json` 的 `state` 字段判活，
而 `18.3.1.2` / `18.4.1.1` 两条车道的 `state` 恒为 `running` ⇒ 恒判「持闸者还活着」。

**直接后果**：`lane.mjs gate acquire` 的偷闸路径被 `holderAlive` 挡死
（`if (live.holderAlive) return false;`）⇒ **协议自带的 `gate acquire` 在这种局面下永远拿不到闸**，
这正是 round-150 不得不自己写 `takeover-r150.mjs` 的原因。
本轮**严格按协议只用 `stale` 字段判活**，没有用自己的 age 推理去抢闸，
且**全程没有清闸、没有 force、没有 `rm -rf` 闸目录**。

### 2.4 等闸期间的取证纪律

等闸期间只做**只读**动作（`gate status` / `cat owner` / `ls` 闸目录 / `ps` / `git log` / `git reflog`），
**没有跑 `git fetch`、没有跑任何构建**（协议明确：等闸期间连 fetch 都先别跑），
避免与持闸 tick 的 L2 共用 `backend/**/target/` 与 PG 容器导致并发假红。

## 三、并发事实：round-150 在本轮等闸期间完成了一整轮

本轮把 18:34:20 之后的链路完整取证如下（`.dev-agent/takeover-r150-run.log`）：

| 时刻 | 事件 |
| --- | --- |
| 18:34:20 | 闸龄越过 TTL 4200s ⇒ 孤儿闸正式陈旧 |
| **18:34:26** | round-150 的 `takeover-r150.mjs` **取证后原子接管**成功（rename → mkdir，单进程内完成，无双向持闸窗口）；`gate status` 转为 `holder=pipeline-tick  ownerPid=44296  acquiredAt=2026-11-01T10:34:26.784Z` |
| 18:34:27 | `lane.mjs plan --lanes 2` 完成：**重派 2 条僵尸车道** —— `18.3.1.2`（`redispatched: 2`）、`18.4.1.1`（`redispatched: 1`），`newlyPlanned: []` |
| 18:36:32 | `25460f6d docs(batch-124): … L2 绿积压 78 commit 首次落 origin + 2 条僵尸车道重派` 提交并 push ⇒ **78 个 commit 的推送积压清零** |
| 18:39:02 | `c335cc54 docs(sessions): 集成头同步 main 第 123 轮 …` 提交（第 123 轮自己的会话记录） |
| **18:39:15** | **本轮取得闸**（闸目录已被真实释放，ownerPid 46969） |

### 3.1 两条僵尸车道**已经活了**（本轮实测心跳）

| 车道 | 重派前心跳 | 本轮实测心跳 | 状态 |
| --- | --- | --- | --- |
| `18.3.1.2` | `2026-11-01T03:26:19.094Z`（≈ 7.2 小时前） | **`2026-11-01T10:40:00.763Z`**（18:40 CST） | `running`，**活** |
| `18.4.1.1` | `2026-11-01T03:26:02.026Z`（≈ 7.2 小时前） | **`2026-11-01T10:37:08.126Z`**（18:37 CST） | `running`，**活** |

⇒ round-150 的重派**真的把两条车道救活了**（心跳从 7 小时前刷新到当下），
不再是 `docs/backlog/2026-10-2026-10-31-gate-holder-zombie-lanes-block-reclaim.md` 里描述的僵尸态。

## 四、集成头与远端的一致性

| 项 | 值 |
| --- | --- |
| `HEAD`（本轮起手时） | `c335cc5455ccd9b352656b9532df67c25f4f863a` |
| `origin/integration/module-dev` | `c335cc5455ccd9b352656b9532df67c25f4f863a`（`git ls-remote` 回读，逐字相同） |
| 未推送 commit 数 | **0**（`git rev-list --count origin/integration/module-dev..HEAD`） |
| 取闸后 `git status --porcelain` | **空** |

⇒ 第 122 轮遗留的 78 个 commit 积压已由 round-150 于 18:36 首次落上 origin，
本轮接手时**积压已清零**，不存在「假 push 声明」的遗留风险面。

## 五、预检为何未跑（不跑的理由，不是漏跑）

协议第 2 步写明：`NO_UPDATE` ⇒ 汇报一行、**释放闸后结束**。本轮无 merge ⇒ 无 merge commit 需要预检背书。
另实测 18:41 前后全仓 `mvnw|surefire|vitest|next build|playwright` **零命中**，
即便想跑预检也没有并发构建风险 —— 但**没有 merge 就没有预检的对象**，故不跑。

台账一致性用**只读**手段验证：`node tools/module-progress/sync.mjs --check` exit **0** ⇒ `✓ 台账与蓝图一致`；
再实跑 `node tools/module-progress/sync.mjs` ⇒ `✓ 同步 18 卷 / 465 个 L4 模块 · 已交付 445 · 进行中 0 · 未开发 20`，
跑完 `git status` **无 diff** ⇒ **幂等**，台账未被本轮改动。

## 六、push：只有本会话记录这一个 docs commit

- 本轮**没有** merge commit 可推；
- 远端在起手时与本地 `HEAD` **逐字相同**（`c335cc54`），若不推任何东西则不产生落后；
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

1. **`origin/main` 自 2026-10-31 00:51 起就没动过** —— 已连续 3 轮 `NO_UPDATE`（121 / 122 / 123 / 124）。
   下轮同步大概率仍是 `NO_UPDATE`，**不必为它加频率**。
2. 本轮无 merge commit ⇒ **本轮不需要在 L2 重点排查任何 merge**；
   下一轮 L2 的排查对象仍是 round-150 落的 `25460f6d` 及其祖先那 78 个 commit
   （它们**未经本同步任务的预检**，只经 round-148 的 L2）。
3. **`holderAlive` 假阳性**本轮再次复现（`ps` 证实 PID 8513 已死，工具却报 `holderAlive: true`）。
   这次的**新证据**是：它不仅让 `gate reclaim` 失效，还让**协议自带的 `gate acquire` 偷闸路径也被挡死**
   （`lane.mjs` 第 713–714 行 `if (live.holderAlive) return false;`）⇒ 同步任务在僵尸车道局面下
   **只能等真实释放**，不能靠竞态偷闸。建议在该 backlog 项里补这条「同步任务侧」的复现记录。
4. **本轮验证了「守株待兔式取闸」可行且更安全**（第二节 2.1）：只在闸目录不存在时才 acquire，
   可与流水线 tick 的主动接管**结构上互不冲突**，建议写进 `SYNC-MAIN.md` 的「等闸」小节作为备选写法。
5. **流水线 tick 与同步任务共用同一 worktree**（第三节）⇒ 同步任务的「工作区必须干净」判据依赖对方是否刚好在写；
   本轮取闸后 `git status` 为空，但这不是协议能保证的，值得在 `SYNC-MAIN.md` 里补一句「取闸后先 `git status` 复核」的显式动作。
