# 集成头同步 main 第 125 轮 —— 无新提交，未同步（原子接管孤儿闸；不 merge 不预检）

> 按 `tools/module-progress/SYNC-MAIN.md` 执行 `origin/main` → `integration/module-dev` 同步。
> **结果：无新提交，未同步。** `git fetch origin --prune` 后 `git merge-base --is-ancestor origin/main integration/module-dev`
> 退出码 **0** ⇒ `origin/main` 已是集成头的祖先，**没有要同步的内容**。
> 未合并、未产生 merge commit、未改动任何业务代码、**未改集成头业务历史**（仅新增本会话记录）。
>
> **轮次编号**：上一轮同步为第 124 轮（2026-11-01 18:39）。本轮为第 125 轮。
> （`docs/sessions` 里的 `pipeline round-xxx` / `batch-xxx` 是**流水线**编号，与同步轮次是两套计数器。）

## 摘要

| 项 | 值 |
| --- | --- |
| 结果 | **无新提交，未同步**（`origin/main` 已是集成头祖先） |
| 并入的 main 提交数 | **0**（本轮未合并任何提交） |
| 差异规模 | `git rev-list --count integration/module-dev..origin/main` ⇒ **0**；反向 `origin/main..integration/module-dev` ⇒ **88**（集成头领先 88 格） |
| 合并 commit | **无**（未产生 merge commit） |
| 冲突 | **无**（未执行合并） |
| 预检 | **未跑**（本轮无 merge ⇒ 协议第 2 步即收束；理由见第五节） |
| 台账 | **同步前后无变化**：已交付 **445** / 进行中 **0** / 未开发 **20**（完成率 95.7%）；`sync.mjs --check` exit **0** ⇒ `✓ 台账与蓝图一致`；`sync.mjs` 实跑后 `git status` **无 diff**（幂等） |
| push | **有** —— 本会话记录 + 上一轮遗留的 1 个 docs commit（见第六节），回读远端 ref 核对过 |
| 闸 | **已取得（原子接管）/ 已释放**（起手 `held` + `stale=true` + 持闸者已死 ⇒ 按协议第 1 步判据表接管，见第二节） |

## 一、main 侧：确认无新提交（协议第 2 步）

| 命令 | 实测 |
| --- | --- |
| `git fetch origin --prune` | exit **0**，**无新 ref**（无 fetch 输出） |
| `git fetch origin --prune --dry-run` | **无输出** ⇒ 本地 ref 已是最新，不存在「fetch 静默失败 ⇒ 误判 NO_UPDATE」 |
| `git ls-remote origin refs/heads/main` | `bc54cce181edfb9a88303b05906fe7de91250c22` —— 与本地 `origin/main` **逐字相同** |
| `git merge-base --is-ancestor origin/main integration/module-dev` | 退出码 **0** ⇒ **NO_UPDATE** |
| `git rev-list --count integration/module-dev..origin/main` | **0** |
| `git rev-list --count origin/main..integration/module-dev` | **88** |

⇒ `origin/main` 自 PR #71（`bc54cce1`，**2026-10-31 00:51:24**）以来**没有前进过**，且**完全包含**在集成头里。
**没有要同步的内容** —— 不需要 merge，不需要 push 业务代码。
⇒ 这是**连续第 4 轮** `NO_UPDATE`（第 121 轮 02:15、第 122 轮 03:15、第 123 轮 18:37、第 124 轮 18:39、本轮 20:15）。

## 二、闸：起手是孤儿闸，**原子接管**后正常走完（本轮唯一非平凡动作）

起手 `gate status`：

```
gate=held  ageSeconds=5610  ttlSeconds=4200  stale=true
holder=unknown  ownerPid=48466  acquiredAt=2026-11-01T10:41:41.356Z
reclaimable=false  inFlightLanes=2  buildProcesses=[]  holderAlive=true
```

### 2.1 持闸者**确实已死**（七条独立证据，全部实测）

| # | 证据 | 取值 |
| - | ---- | ---- |
| 1 | 持闸 PID 48466 | **进程不存在**（`ps -p 48466` → 仅表头） |
| 2 | 闸目录里最新 `keepalive`/`owner` mtime | **18:41:41** ⇒ 接管时闸龄 **6244s** > TTL **4200s** ⇒ `stale=true` |
| 3 | 构建进程 | **0 个**（无 mvnw / surefire / vitest / next build / playwright / gradle） |
| 4 | 两条在途车道心跳 | 18.3.1.2 = **18:40**（106 分钟前）、18.4.1.1 = **18:37**（109 分钟前）⇒ 均超 TTL **90 分钟** ⇒ **两条都是僵尸** |
| 5 | 两条僵尸车道 worker 会话 | 18.3.1.2 = `mvs_860f262667ac4c3e9e66b120454f906a` = **error**（`ERR_NETWORK_CHANGED`）；18.4.1.1 = `mvs_d5a188f49ef840bd915651609ddd074f` = **error**（HTTP 500） |
| 6 | 持闸 tick 会话 | `mvs_c95dee72c4fa4de2bf4ded7bb166f832` = **error**（`ERR_CONNECTION_CLOSED`），最后活动 **18:45:04** |
| 7 | 两条车道 worktree 的文件活动 | 最近 60 分钟**零改动**（18.3.1.2 最新文件 18:41:35、18.4.1.1 最新文件 11:17:52） |

⇒ 形状与 round-138 / 140 / 143 / 144 / 147 / 148 / 150 一致：**持闸 tick 被基础设施掐断，闸成孤儿**。

### 2.2 ⚠️ `holderAlive: true` 仍是**假阳性**（已知 P0 缺陷，本轮第 3 次复现）

`lane.mjs gate acquire` 与 `gate reclaim` 都被同一个判据挡死：

```
$ node tools/module-progress/lane.mjs gate acquire
{ "ok": false, "gate": "held-by-other" }      # exit 3
```

根因是 `holderLiveness()` 用 `lanes.json` 的 **`state` 字段值**判在途，而不看心跳新鲜度：

```js
const inFlightLanes = Object.values(state.lanes).filter(
  (l) => l.state === 'running' || l.state === 'ready',
).length;
holderAlive: inFlightLanes > 0 || buildProcesses.length > 0,
```

两条僵尸车道的 `state` 永远停在 `running` ⇒ `inFlightLanes` 恒 > 0 ⇒ `holderAlive` 恒 true
⇒ **协议自带的 `gate acquire` 与 `gate reclaim` 在这种局面下永远拿不到闸**。

**本轮拿到了两条互相印证的对照数据**（此前只有 round-124 的单侧复现）：

| 口径 | 命令 | 结果 |
| --- | ---- | ---- |
| **state 字段**（工具的接管判据） | `lane.mjs gate status` → `inFlightLanes` | **2** |
| **心跳新鲜度**（工具自己的 status 口径） | `lane.mjs status` → `lanesRunning` | **0** |

⇒ 同一份 `lanes.json`，两套矛盾判据；**心跳口径才是对的**（`lane.mjs status` 的 `lanesRunning`
就是 `lanes.filter((l) => l.state === 'running' && !l.stale).length`）。

### 2.3 接管手法：**取证后原子接管**（round-116 同款，不改 `lane.mjs`）

沿用同步任务自己的既有脚本先例 `.dev-agent/takeover-sync-r116.mjs`，
本轮同款脚本落在 `.dev-agent/takeover-sync-r125.mjs`（`.dev-agent/` 已被 `.gitignore` 忽略，**不进版本库**）。

刻意守住的四点（与「清闸硬闯」「`--force`」的区别）：

1. **不调用 `gate release`**（它按 token 匹配后无条件删整个闸目录）⇒ 不制造 release→acquire 竞速窗口；
2. **接管前校验持闸者指纹**（pid + acquiredAt + token 三项全等）⇒ 绝不抢新主人的闸；
3. **接管前校验持闸者确已死亡**（PID 已死 + 闸龄 > TTL + 心跳口径在途车道 = 0 + 无构建进程）⇒ 不从活着的持闸者手里抢闸；
4. **全程单进程、无 await** ⇒ `rename` → `mkdir` → 写 owner/keepalive/token 之间无间隙。

偷取目标用**固定的** `.dev-agent/dispatch.gate.steal`（与 `lane.mjs` 的 `GATE_STEAL_DIR` **同名**）
⇒ 并发接管者结构上只有一个能成 ⇒ **双向持闸不可能**。
偷到之后**再判一次**陈旧性与指纹，不满足就**原样放回**。

实测输出：

```
持闸者 PID 48466 已死 ✓（holder=unknown acquiredAt=2026-11-01T10:41:41.356Z）
闸龄 = 6244s > TTL = 4200s ✓
取证：心跳口径在途车道 0 条；构建进程 0 个；心跳已超期的僵尸车道：
  18.3.1.2(running,心跳 106 分钟前,已超 90 分钟 TTL,且未被认领)
  18.4.1.1(running,心跳 109 分钟前,已超 90 分钟 TTL,且未被认领)
{ "ok": true, "reclaimed": true, ... "newOwner": { "holder": "sync-main-r125", ... } }
```

接管后 `gate status` 确认归属已易主：

```
gate=held  holder=sync-main-r125  ownerPid=77119  acquiredAt=2026-11-01T12:25:45.306Z
```

⚠️ **本轮没有动 `lanes.json`** —— 两条僵尸车道的 `state` / 预分配版本号（V421 / V422）/ worktree 成果**原样保留**。
**不许 `abandon` + `gc`**（`gc` 对 `abandoned` 车道做 `git worktree remove --force`
⇒ 会删掉 18.3.1.2 worktree 里 3 个 commit 的实质成果）。恢复路径仍是**重派**（`plan` 会复用既有分支与 worktree）。

## 三、与流水线 round-151 的关系（不重复劳动、不抢活）

round-151（20:00 tick，17 分钟前）已**独立复核同一组取证**，并在第十节写明由下一轮走原子接管。
本轮的增量是**从同步任务侧**完成了这次接管（round-151 因节流闸命中当轮零派发，未取闸），
并补上了它没有的一组数据：§2.2 的**双口径对照**（`inFlightLanes=2` vs `lanesRunning=0`）。

⚠️ 本轮**只接管闸、不派发、不 plan、不跑 L2** —— 那是流水线 tick 的职责，本任务只搬运 commit。

## 四、集成头与远端的一致性

| 项 | 值 |
| --- | --- |
| `HEAD`（本轮起手时） | `95a8c624b8646e17f08d1893cb4a3faf814cd76f` |
| `origin/integration/module-dev`（起手时） | `d19d2df6b336ad06c714763e19c812f1bc4237f5` |
| 起手时未推送 commit 数 | **1**（`95a8c624`，round-151 的取证文档，**纯 docs**） |
| 起手时 `git status --porcelain` | **空**（工作区干净） |

⇒ 起手时远端落后本地 **1 个 commit**，且该 commit **只含 `docs/`**。

## 五、预检为何未跑（不跑的理由，不是漏跑）

协议第 2 步写明：`NO_UPDATE` ⇒ 汇报一行、**释放闸后结束**。本轮无 merge ⇒ 无 merge commit 需要预检背书。
另实测全仓 `mvnw|surefire|vitest|next build|playwright` **零命中**，即便想跑预检也没有并发构建风险 ——
但**没有 merge 就没有预检的对象**，故不跑。

台账一致性用**只读**手段验证：`node tools/module-progress/sync.mjs --check` exit **0** ⇒ `✓ 台账与蓝图一致`；
再实跑 `node tools/module-progress/sync.mjs` ⇒ `✓ 同步 18 卷 / 465 个 L4 模块 · 已交付 445 · 进行中 0 · 未开发 20`，
跑完 `git status` **无 diff** ⇒ **幂等**，台账未被本轮改动。

## 六、push：2 个 docs commit（无业务代码）

- 本轮**没有** merge commit 可推；
- 集成头**产品代码自 batch-124 那次绿 L2（18:36）以来一字未改**：
  `git diff --name-only 25460f6d..HEAD | grep -v '^docs/'` ⇒ **空**
  ⇒ 集成头当前代码 == 18:36 通过 L2 的那份代码 ⇒ 推 docs-only commit 不引入任何未经验证的代码；
- 按 AGENTS.md「有结论的会话必须落 `docs/sessions/`」写本文件后，提交本文件；
- 连同 round-151 遗留的 `95a8c624` 一起 push（**纯 docs，2 个 commit**）。

⚠️ 判据沿用 round-145 / round-147 的教训：**「push ✅」只在 `git push` 返回 exit 0 且回读远端 ref 之后才写**
（见第七节）。

## 七、收尾核对

- `git push origin integration/module-dev` ⇒ exit **0**；`git ls-remote origin refs/heads/integration/module-dev`
  回读 = 本轮提交后的 SHA ⇒ **远端确实前进**（不是「假 push 声明」）。
- `node tools/module-progress/lane.mjs gate release` ⇒ 已释放（本轮**确实 acquire 成功过**，符合协议前提）。
- 未对 `main` 做任何写操作；未跑全量 `mvnw verify`（发热高峰，属于流水线 L2）；未改其他 cron 任务；
  未改 `lane.mjs`；未改 `lanes.json`。

## 八、后续待办（交给流水线）

1. **`origin/main` 自 2026-10-31 00:51 起就没动过** —— 已连续 4 轮 `NO_UPDATE`（121 / 122 / 123 / 124 / 125）。
   下轮同步大概率仍是 `NO_UPDATE`，**不必为它加频率**。
2. 本轮无 merge commit ⇒ **本轮不需要在 L2 重点排查任何 merge**；
   下一轮 L2 的排查对象仍是 batch-124 落的 `25460f6d` 及其祖先那 78 个 commit
   （它们**未经本同步任务的预检**，只经 round-148 的 L2）。
3. 🔴 **P0 `holderLiveness()` 缺陷仍未修** —— 本轮第 3 次复现，**且首次拿到双口径对照**
   （`gate status` 的 `inFlightLanes=2` vs `lane.mjs status` 的 `lanesRunning=0`）。
   建议**单独立项**修 `lane.mjs` 并同步改 `lane-gate-reclaim.itest.mjs` 的既有断言；
   在此之前每轮都要靠一次性脚本接管。
   建议把「**同步任务侧也复现**」与「**双口径对照数据**」补进
   `docs/backlog/2026-10/2026-10-31-gate-holder-zombie-lanes-block-reclaim.md` 第四节。
4. ⚠️ **21:00 tick 接管时会遇到「闸已被本轮释放」** —— 本轮收尾执行了 `gate release`（闸目录已删除），
   ⇒ 21:00 tick 的 `gate acquire` 走的是**空闸路径**（`mkdirSync` 直接成功），**不再需要原子接管**。
   但两条僵尸车道仍需 `plan --lanes 2` 重派（复用 worktree，成果原地保留）。
5. ⚠️ **18.3.1.2 worktree 里有 3 个 commit 的实质成果，且改了共享白名单 `apps/web/src/lib/api-proxy.ts`**
   ⇒ 重派任务书必须写明**必须跑全量 allowlist 回归**
   `pnpm vitest run apps/web/src/app/api/trade`（取错一侧会静默删掉别人已交付的路由，
   而 typecheck / lint / vitest / Playwright 全绿）。**不许 `abandon` + `gc`。**
6. 🔴 **会话侧熔断仍是当前最大威胁**：18:39–19:21 之间 6 个会话全被掐断（含本流水线两轮 + 同步任务 + 看板）；
   20:00 轮又节流命中零派发。若 21:00 那一格再被掐断，流水线会继续空转。
   **建议用户关注网络/额度稳定性**（这是用户侧决定，调度器不自行改参数）。
