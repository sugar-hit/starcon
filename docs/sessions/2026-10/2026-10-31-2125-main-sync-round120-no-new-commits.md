# 集成头同步 main 第 120 轮 —— 无新提交，未同步（闸被死掉的 round-143 tick 占用，取不到闸，未改集成头）

> 按 `tools/module-progress/SYNC-MAIN.md` 执行 `origin/main` → `integration/module-dev` 同步。
> **结果：无新提交，未同步。** `git fetch origin --prune` 后 `git merge-base --is-ancestor origin/main integration/module-dev`
> 退出码 **0** ⇒ `origin/main` 已是集成头的祖先，**没有要同步的内容**。
> 未合并、未产生 merge commit、未 push、未改动任何业务代码、**未改集成头历史**。
>
> **轮次编号**：上一轮同步为第 119 轮（2026-10-31 14:21）。本轮为第 120 轮。
> （`docs/sessions` 里的 `pipeline round-xxx` / `batch-xxx` 是**流水线**编号，与同步轮次是两套计数器。）
>
> ⚠️ **本轮与 116–119 轮的关键差别：闸没取到。** 前四轮都能竞态取得闸；本轮闸被
> round-143 tick（PID 87495）持住后**该 tick 崩溃死亡**，闸成孤儿，协议自带的接管路径
> 全部拒绝 ⇒ 本轮**没有拿到闸**，因此**没有做任何会改动集成头的事**（未合并 / 未跑
> `sync.mjs` 写盘 / 未 commit / 未 release）。

## 摘要

| 项 | 值 |
| --- | --- |
| 结果 | **无新提交，未同步**（`origin/main` 已是集成头祖先） |
| 并入的 main 提交数 | **0**（本轮未合并任何提交） |
| 差异规模 | `git rev-list --count integration/module-dev..origin/main` ⇒ **0**；`git diff --stat integration/module-dev...origin/main` ⇒ **空** |
| 合并 commit | **无**（未产生 merge commit） |
| 冲突 | **无**（未执行合并） |
| 预检 | **未跑** —— round-143 的全量 L2 正在**同一 worktree** 跑（共用 `backend/**/target/` 与 PG 容器 ⇒ 并发必假红，协议明令） |
| 台账 | **同步前后无变化**（同步自身零变化）；`sync.mjs --check` exit **0** ⇒ `✓ 台账与蓝图一致` |
| push | **无**（无 merge commit ⇒ 无本轮成果可推；且本地领先远端 49 个 commit 属流水线积压，须由流水线在自己 L2 绿后统一推） |
| 闸 | **未取得 / 未释放**（详见第三节） |
| 等闸时长 | **≈ 17 分钟**（21:15 → 21:24，密集竞态 **510+ 次**全败） |

## 一、main 侧：确认无新提交（协议第 2 步）

| 命令 | 实测 |
| --- | --- |
| `git fetch origin --prune` | exit **0**，**无新 ref**（无 fetch 输出） |
| `git ls-remote origin refs/heads/main` | `bc54cce181edfb9a88303b05906fe7de91250c22` —— 与本地 `origin/main` **逐字相同** |
| `git merge-base --is-ancestor origin/main integration/module-dev` | 退出码 **0** ⇒ **NO_UPDATE** |
| `git rev-list --count integration/module-dev..origin/main` | **0** |
| `git merge-base origin/main integration/module-dev` | `bc54cce181edfb9a88303b05906fe7de91250c22` —— **与 `origin/main` 逐字相同** |
| `git diff --stat integration/module-dev...origin/main` | **空** |

⇒ `origin/main` 自 PR #71（`bc54cce1`）以来**没有前进**，且它**完全包含**在集成头里。
**没有要同步的内容** —— 不需要 merge，不需要预检，不需要 push。

## 二、闸：持闸者已死，但协议路径接管不了

起手 `gate status`：`gate=held, ageSeconds=3674, ttlSeconds=4200, stale=false, holder=pipeline-tick, ownerPid=87495`。
按协议 `stale=false` ⇒ **等它释放**，不抢、不清、不 force。

### 2.1 八项取证（全部实测）

| # | 证据 | 取值 |
| --- | --- | --- |
| 1 | 持闸 PID | **87495 已死**（`ps -p 87495` ⇒ 进程不存在） |
| 2 | 续期守护 | **`keepalive-r143.sh` 不在运行**；闸目录 `keepalive` / `keepalive-*` 最后 touch 均为 **20:13:53** ⇒ 闸龄自此开始计 |
| 3 | 闸龄 | 21:24:38 实测 **ageSeconds=4245 > TTL 4200** ⇒ **`stale=true`**（协议第 1 步的接管判据已满足） |
| 4 | `gate status` 终判 | `stale=**true**`、`reclaimable=**false**`、`holderAlive=**true**` |
| 5 | `gate reclaim --wait 0`（**未带 `--force`**） | exit **3**：`闸龄 4245s > TTL 4200s，但持闸者仍活着（在途车道 2 条 / 构建进程 2 个）⇒ 不接管，等持闸者自己释放` |
| 6 | 在途车道 | **2 条**：`18.3.1.1`（hb `13:13:44Z`）、`18.3.3.1`（hb `13:14:09Z`）⇒ 心跳仅 **5 分钟前**；两个 worktree 近 15 分钟内均有文件改动 ⇒ **两个工人真在干活** |
| 7 | 构建进程 | **2 个**（`98660` / `98661`）= round-143 的**全量 L2**（`mvnw verify` 在 warehouse 服务跑 surefire，20:14:41 起） |
| 8 | 闸令牌 | `.dev-agent/dispatch.gate.token` = `87495-1793448831905-wayeip8k`，与 `dispatch.gate/owner` 末行**一致** ⇒ **跑 `gate release` 会真的删掉这个闸**（= 清闸）⇒ **不能跑** |

### 2.2 密集竞态（协议指定写法）

`while true; do gate acquire | grep -q '"ok": true' && break; sleep 1; done` —— **510+ 次全部返回 `{"ok": false, "gate": "held-by-other"}`**，
**从未取得**。21:24 时主动停止竞态（理由见第五节第 3 条）。

### 2.3 为什么协议判 stale 却接管不了

`gateAcquire` 的接管分支（`lane.mjs:698-757`）要过两关：**① 闸龄 > TTL**（已过）**② 持闸者已死**（`holderLiveness` 判 false）。
第二关过不去，因为 `holderLiveness`（`lane.mjs:653-678`）的判据是：

```
holderAlive = inFlightLanes > 0 || buildProcesses.length > 0
```

**在途车道（2 条，活的）与构建进程（L2，活的）任一非零 ⇒ 恒为 true。**
这就是已入库的 P0 缺陷 `docs/backlog/2026-10/2026-10-31-gate-holder-zombie-lanes-block-reclaim.md`
（该文件**已在版本库里**，batch-120 说明里「查无此名」的说法有误）——它让「闸龄过 TTL ⇒ 自动接管」
这条协议自愈路径**机制性不可达**。

### 2.4 本轮**没有**做的事（以及为什么）

| 没做 | 原因 |
| --- | --- |
| `--force` / `override` 接管 | 协议明令「不要 force」；`lane.mjs:849` 自己写明「无视持闸者死活 = 从活着的持闸者手里抢闸 ⇒ 双派发 ⇒ 版本号撞号 + L2 成片假红」 |
| `gate release` | **本轮从未 acquire 成功** ⇒ 协议「没拿到过闸就绝对不要 release」；且令牌文件仍与死 tick 一致 ⇒ 跑 release = **删掉别人的闸** = 清闸 |
| 跑快速预检 | round-143 的**全量 L2 正在同一 worktree 跑**，共用 `backend/**/target/` 与 PG 容器 ⇒ 并发必假红（协议第 1 步明令「等闸期间不要跑第二个构建」） |
| 跑 `sync.mjs` 写盘刷新台账 | 台账刷新 = 改集成头工作区 ⇒ 需要闸 |
| commit / push | 同上；且本轮无 merge commit ⇒ 无本轮成果可推 |

## 三、台账：本轮同步零变化

| 项 | HEAD（集成头已提交态） | 工作区（round-143 未提交态） | 本轮同步带来的变化 |
| --- | --- | --- | --- |
| L4 总数 | 465 | 465 | **无** |
| 已交付 | **439** | **443** | **无** |
| 进行中 | 0 | 0 | **无** |
| 未开发 | **26** | **22** | **无** |
| 完成率 | 94.4% | 95.3% | — |

- `node tools/module-progress/sync.mjs --check` exit **0** ⇒ `✓ 台账与蓝图一致`（**只校验不写文件**，本轮用它代替写盘刷新做一致性核验）。
- 工作区里 **443 / 0 / 22** 是**流水线 round-143（batch-120：18.1.3.2 数据迁移 + 18.2.3.1 其他对接）**
  的成果，**尚未提交** —— 相对上一轮同步（439/0/26）多出的 4 个已交付**不是 main 带进来的**
  （main 本轮零提交），而是流水线并入的两条车道。

## 四、文件清单

| 文件 | 变更 |
| --- | --- |
| `docs/sessions/2026-10/2026-10-31-2125-main-sync-round120-no-new-commits.md` | 新增（本文件，**未提交** —— 未取得闸 ⇒ 不改集成头） |
| `docs/product/capability-blueprint/module-progress.md` | **无变更**（工作区里 443/0/22 的改动是 round-143 留下的，非本轮） |
| 集成头分支 `integration/module-dev` | **零变更**（未合并 / 未 commit / 未 push，HEAD 仍为 `ad57336c`） |
| `origin/main` / `origin/integration/module-dev` | **零变更**（只 fetch 读取，未写任何分支） |

## 五、遗留 / 待办（交下一轮，重要）

1. 🔴 **集成头工作区是脏的 ⇒ 22:00 tick 的 `merge-ready` 会直接被拒。**
   现状：`M docs/product/capability-blueprint/module-progress.md` +
   `?? docs/sessions/2026-10/2026-10-31-batch-120-data-migration-and-external-integrations.md`
   —— 都是 round-143 崩死前的**未提交成果**（台账刷新 + batch-120 说明）。
   `lane.mjs:1363` 的 `git status --porcelain` 一旦非空就 `✗ 主工作区不干净，拒绝切分支` ⇒
   **两条在途车道（18.3.1.1 / 18.3.3.1）的并入通道会被堵死**。
   ⇒ 下一轮**必须先处理这两个遗留文件**（提交或明确丢弃），再谈并入。本轮**没有**动它们（无闸，且丢弃 = 销毁死 tick 的交付物）。

2. 🔴 **闸仍是孤儿**（`stale=true` 但 `reclaimable=false`，持闸 PID 87495 已死，续期守护已死）。
   下一轮流水线要接管，需要 round-138/140/143 那套**取证式接管**脚本；
   但它的第 4 道门「在途车道心跳**全部过期**」**现在不成立**（两条车道心跳只有 5 分钟）⇒
   **下一轮 tick 很可能同样取不到闸**。这正是 P0 缺陷的连续复发，**必须单独修 `holderLiveness()`**。

3. ⚠️ **本轮 21:24 主动停止了密集竞态**：竞态已 510+ 次全败且闸龄已过 TTL（再等也不会自己变 free），
   继续挂着只会在会话结束后**留下「已取得但无人释放」的孤儿闸** —— 那正是本轮在诊断的问题，不能自己制造一个。

4. ⚠️ **本地集成头领先远端 49 个 commit**（`origin/integration/module-dev` = `7a994ec0`，本地 HEAD = `ad57336c`）。
   batch-120 说明里写的「③ push ✅」**实际没有发生**（`git log --all --grep='batch-120'` 查无此提交，
   远端 `integration/module-dev` 仍停在 `7a994ec0`）—— 死 tick 死在了 commit 之前。
   ⇒ 这 49 个 commit（含 18.1.3.2 / 18.2.3.1 等真实代码）**仍待流水线在自己 L2 绿后统一推送**。
   本轮**不推**（无 merge commit；且快速预检 ≠ L2，无权代流水线放行）。

5. ⚠️ **round-143 的全量 L2 仍在跑**（20:14:41 起，warehouse 服务 surefire，已跑 1 小时+）。
   它的终判（绿/红）是这 49 个 commit 能否推送的依据 —— **下一轮流水线接手时先看
   `.dev-agent/l2/l2-r143.log` 的终判**，别重跑一次全量。

6. ⚠️ **`holderLiveness()` P0 缺陷未修**（`docs/backlog/2026-10/2026-10-31-gate-holder-zombie-lanes-block-reclaim.md`，
   已入库）—— 须单独评估 + 改 `lane-gate-reclaim.itest.mjs` 既有断言，不在流水线 tick 里顺手改。

7. ⚠️ **本文件与上一轮的同步日志同样未提交** —— 无闸不能改集成头。下一轮取得闸后请一并提交
   （`docs/sessions/2026-10/2026-10-31-2125-main-sync-round120-*.md`）。

## 六、关键命令留痕

```bash
# 取数（只读远端 + 真实 fetch）
git ls-remote origin refs/heads/main refs/heads/integration/module-dev
git fetch origin --prune
git merge-base --is-ancestor origin/main integration/module-dev && echo NO_UPDATE || echo HAS_UPDATE
git rev-list --count integration/module-dev..origin/main      # → 0
git merge-base origin/main integration/module-dev              # → bc54cce1（与 origin/main 逐字相同）

# 闸：密集竞态 510+ 次全败；stale=true 仍接管不了
node tools/module-progress/lane.mjs gate status                # → held, stale=true, reclaimable=false, holderAlive=true
node tools/module-progress/lane.mjs gate reclaim --wait 0      # → exit 3「持闸者仍活着 ⇒ 不接管」
ps -p 87495                                                     # → 进程不存在（持闸 tick 已死）
ps aux | grep keepalive-r143                                    # → 空（续期守护已死）

# 台账（只校验，不写盘）
node tools/module-progress/sync.mjs --check                    # → ✓ 台账与蓝图一致

# ⚠️ 本轮**未**执行：gate release（没取得闸）/ merge / mvnw compile / pnpm typecheck / git push
```
