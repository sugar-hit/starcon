# 2026-10-21 01:15 main → 集成头同步：等闸 45 分钟，merge 冲突 6 文件，未并入

> 本轮**未 push、未跑预检、未改任何业务代码**。
> 结果是四选一里的**「冲突未并入」**。
> 这是 `origin/main` 自 00:00 起连续 6 轮 NO_UPDATE 之后的**第一次 HAS_UPDATE**，
> 也是**第一次撞上 main 用 squash 合并集成头带来的结构性分叉**。

## 一句话结论

01:15 触发时调度闸 `gate=held`（`stale=false`），持闸者是 01:03:50 tick 的 batch-40 L2
（`mvnw verify` pid 63873，01:23:31 起跑，channel 服务 surefire 阶段被本轮实测到）。
按协议用**密集 `acquire` 竞态**等待，**2451 次尝试、45 分钟后取得闸**（02:00:17，owner pid 85740）。

取得闸后 `git fetch origin --prune` 无 ref 更新，`origin/main` 已从 `1bb270a0`（batch-28 tip）
前进到 **`d28188d6`（Integration/module dev (#11)，2026-10-20 20:39:44 +0800）**，
且它**不再是集成头的祖先** ⇒ `merge-base --is-ancestor` 判定为假 ⇒ **HAS_UPDATE**，要同步 1 个提交。

`git merge --no-ff origin/main` 在 **6 个文件上冲突** ⇒ 按协议 `git merge --abort`，
**未自行解冲突、未 push**，集成头仍停在 `eb47b71e`（与远端零偏差）。

## 本轮参数（实时读取，非历史文档）

| 项 | 值 | 来源 |
| --- | --- | --- |
| 集成头 worktree | `/Users/guan/Projects/starcon-worktrees/integration` | `.dev-agent/PIPELINE.local.md` → `PROJECT_ROOT` |
| 推送远端 | `origin` | 同上 → `PUSH_REMOTE` |
| 协议文件 | `tools/module-progress/SYNC-MAIN.md`（**集成头内那份**，主工作区那份是旧版） | 本轮实际读取路径 |
| 集成头 head（合并前） | `eb47b71e`（batch-40 tip：5.2.1.3 / 5.2.3.1 / 5.3.1.1 / 9.3.3.1 / 4.3.1.1 五车道并入 + L2 六阶段全绿） | `git rev-parse` |
| `origin/main` tip | `d28188d6`（Integration/module dev (#11)，**GitHub squash 合并**） | `git log -1 origin/main` |
| merge-base | `1bb270a0`（batch-28 tip） | `git merge-base` |
| 车道状态 | 45 条：**41 `merged`** / 2 `ready`（15.2.2.2 索评规则、5.2.2.2 汇率快照）/ 2 `running`（5.3.1.2 批量测算、5.3.2.1 定价模板） | `lanes.json` |
| 台账 | 465 个 L4 模块：已交付 250 · 进行中 0 · 未开发 215（完成率 53.8%） | `sync.mjs` 输出 |
| 本轮 acquire 时刻 | **02:00:17**（owner pid 85740，2451 次尝试） | `dispatch.gate/owner` |
| 本轮等闸时长 | **约 45 分钟**（01:15 → 02:00:17） | 竞态脚本计数 |

## 取证一：闸被活 tick 持住，等了 45 分钟

```
$ node tools/module-progress/lane.mjs gate status        # 01:15 首次
{ "gate": "held", "ageSeconds": 695, "ttlSeconds": 4200, "stale": false }
```

`held + stale=false` ⇒ **活着的流水线 tick 持闸** ⇒ 按协议**等它释放**，不抢、不清、不 force。

**活性取证（闸外）**：`ps` 显示 `mvnw verify`（pid 63873，8 分 56 秒时仍在跑）在**同一 worktree** 里执行，
01:27:26 时 surefire 正在跑 `services/channel` ⇒ 确认是活 tick 的 L2，不是陈旧闸。

⚠️ **`ageSeconds` 会骗人**：持闸 tick 用 `touch keepalive-<新文件名>` 续期（新建文件 ⇒ 闸目录 mtime 刷新），
所以 `ageSeconds` 会被重置回几十秒。**判据只能是 `stale` 字段**（本轮全程 `stale=false`）。

⚠️ **闸目录 mtime 的语义**：判龄用的是 `statSync(GATE_DIR).mtimeMs`，**不是 `owner` 文件的时间戳**。
本轮实测：`owner` 内容仍是 53832 @ 17:03:50Z（持闸 tick 的短命 acquire 进程），而闸目录 mtime
被 keepalive 推到 02:00:17。两者不一致是正常的。

⚠️ **owner PID 查无此进程 ≠ 闸是死的**：`gate acquire` 是短命进程，写完 `owner` 就退出。

**竞态等待（协议第 1 步「等闸」）**：

```bash
for i in $(seq 1 3600); do
  if node tools/module-progress/lane.mjs gate acquire 2>/dev/null | grep -q '"ok": true'; then
    echo "GATE ACQUIRED"; break
  fi
  sleep 1
done
```

⇒ **02:00:17 取得闸（第 2451 次尝试）**。期间闸曾被释放并被下一 tick 重新占住
（`ageSeconds` 一度从 695 掉到 34 —— 交接窗口窄到秒级），密集竞态确实蹲到了它。

⚠️ **等闸期间没跑任何构建**（L2 与预检共用 `backend/**/target/` 与 PG 容器 ⇒ 并发必假红），
只做只读取证（`gate status` / `cat owner` / `ps` / `git log` / `git merge-tree`），**连 `git fetch` 都等到拿到闸之后才跑**。

## 取证二：main 前进了一个提交，且是 squash 合并

```
$ git fetch origin --prune
（输出为空 ⇒ 无 ref 更新）

$ git merge-base --is-ancestor origin/main integration/module-dev ; echo $?
1        # ⇒ origin/main 不是集成头的祖先 ⇒ HAS_UPDATE

$ git log --oneline integration/module-dev..origin/main
d28188d6 Integration/module dev (#11)
```

`d28188d6` 的形态（`git show --no-patch --pretty=fuller`）：

```
commit d28188d63337a2eda641c1ee1e93db77039786b6
Author: Tommy <...>
Commit: GitHub <noreply@github.com>
    Integration/module dev (#11)
parents: 1bb270a0        # 只有一个父提交 ⇒ squash 合并
```

⇒ **main 的 tip 是「集成头分支被 GitHub squash 进 main」的产物**：内容上是集成头在
2026-10-20 20:39 的快照，**历史上被压成了一颗与集成头无共同祖先的新提交**。

`git merge-base integration/module-dev origin/main` = `1bb270a0`（batch-28 tip）
⇒ squash 把 batch-28 之后的共同祖先全抹掉了，merge-base 被拉回 85 个 commit 之前。

**要同步的规模**（`git diff --stat integration/module-dev...origin/main`）：

```
406 files changed, 262390 insertions(+), 34181 deletions(-)
```

**两侧改动面**（以 merge-base `1bb270a0` 为界）：

| 侧 | 改动文件数 |
| --- | --- |
| 集成头侧（`1bb270a0..integration/module-dev`） | 537 |
| main 侧（`1bb270a0..d28188d6`） | 406 |
| 两侧都改过（潜在冲突面） | 406 |

## 取证三：merge 冲突 6 个文件，已 abort

**动手前先用只读的 `git merge-tree --write-tree` 干跑**（不碰工作区、不碰索引），
预测到的冲突集合与真实 merge **完全一致**（6 个文件，无遗漏无多余）：

```
merge-tree --write-tree --name-only integration/module-dev origin/main
⇒ exit 1，冲突清单 6 条
```

真实 `git merge --no-ff origin/main` 的结果：

```
merge-union: 走了行级兜底并集，请人工确认这个文件改对了
merge-union: 台账按模块 ID 求并集，折叠了 465 行同 ID 记录
Automatic merge failed; fix conflicts and then commit the result.
```

✅ **契约与台账两类文件由 `merge-union` 驱动自动求并成功**（406 个文件里的绝大部分）：
`packages/api-contracts/src/contracts/{catalog,channel}.ts`、
`packages/api-contracts/src/{catalog,channel,trade,warehouse,crm}.openapi.json`、
`docs/product/capability-blueprint/module-progress.md`（按模块 ID 求并集，465 行同 ID 记录折叠）。

❌ **6 个文件冲突**（协议规定**不自行解**）：

| # | 文件 | 冲突类型 | 冲突块数 | main 独有行 / 集成头独有行 |
| --- | --- | --- | --- | --- |
| 1 | `apps/web/src/app/pricing/cost/cost-composition-page.test.ts` | add/add | 3 | +18 / −40 |
| 2 | `apps/web/src/components/pricing/cost-composition-model.test.ts` | add/add | 1 | +7 / −19 |
| 3 | `apps/web/src/components/pricing/cost-composition-model.ts` | add/add | 2 | +7 / −8 |
| 4 | `apps/web/src/components/pricing/cost-composition-workspace.tsx` | add/add | 3 | +0 / −13 |
| 5 | `apps/web/src/lib/api-proxy.ts` | content | 3 | +0 / −341 |
| 6 | `backend/services/channel/src/test/java/com/starcon/channel/ListingPublishJobIntegrationTest.java` | add/add | 8 | +2 / −134 |

（「+N」= 该侧独有的行数，「−N」= 对方独有的行数）

**abort 后的状态核验**：

```
$ git merge --abort
$ git status --porcelain        # 空
$ git rev-parse HEAD            # eb47b71e2387a5ac07d6a9d7f1bb1e5f1b6a5226
$ test -f .git/MERGE_HEAD       # NO   ← 没有 MERGE_HEAD 残留，不会堵死流水线的 merge-ready
$ git rev-list --left-right --count origin/integration/module-dev...integration/module-dev
0	0                            # 与远端零偏差
```

⇒ 集成头**完全回到合并前**，无残留、无半合并状态，流水线可以正常继续。

## 冲突的结构性成因（根因）

这 6 个冲突**不是偶然**：

1. `d28188d6` 是集成头分支的 **squash 合并**（单父提交），common ancestry 被压回 `1bb270a0`（batch-28）。
2. batch-28 之后集成头交付的模块（5.2.1.1 成本构成 / 4.3.1.1 一键刊登 / 9.3.3.1 履约请求 …），
   在 merge-base 上**根本不存在** —— 两边都是「各自新增」⇒ 触发 **add/add** 冲突。
3. 内容又确有差异（集成头在 PR 快照之后又修了 bug、batch-37 的 `truncate ... cascade` 修复等）⇒ 无法自动求并。

**冲突方向是单向的**：这 6 个文件上，**集成头普遍更新**（集成头独有 8–341 行，main 独有 0–18 行）。
main 那一份是集成头**自己工作的旧快照**（PR 快照时刻），不是 main 侧的新交付。
其中 `cost-composition-workspace.tsx` 与 `api-proxy.ts` 两份是**集成头严格超集**（main 独有 0 行）。

⚠️ **但这不等于「全选 ours」就是对的**：第 1/2/3/6 个文件里 main 确实各有 2–18 行集成头没有的内容，
是否含 main 侧独有的修复**必须逐文件人工看**，不能批量取一边。

### 给下一棒（人工 / 修复车道）的处理建议

- **首选：修发布侧，别修这 6 个文件。** 让发布侧把集成头的**真实 commit（不 squash）**推到 main，
  则 main 的 tip 重新成为集成头的祖先，本任务回到「无新提交」的零改动常态，冲突从根上消失。
  squash 是这次冲突的唯一来源。
- **次选：开修复车道逐文件解这 6 个冲突**，方向为「以集成头为准 + 逐行核对 main 独有的 2–18 行是否需要保留」，
  解完必须重跑 L2（这批内容**从未经过本流水线的 L1/L2**）。
- **解冲突时不要用 `git checkout --ours/--theirs` 批量覆盖**：第 1/2/3/6 个文件 main 侧确有独有内容。

## 台账变化

| 指标 | 合并前 | 合并后（abort + `sync.mjs`） | 变化 |
| --- | --- | --- | --- |
| 模块总数 | 465 | 465 | — |
| 已交付 | 250 | 250 | ±0 |
| 进行中 | 0 | 0 | ±0 |
| 未开发 | 215 | 215 | ±0 |
| 完成率 | 53.8% | 53.8% | ±0 |

`sync.mjs` 输出：`✓ 同步 18 卷 / 465 个 L4 模块　已交付 250 · 进行中 0 · 未开发 215`，
**跑完零 diff**（`git status --porcelain` 为空）⇒ 台账已由 batch-40 刷新到位。

参照：main 的台账快照（`d28188d6` 里的版本）是 **已交付 242 / 进行中 0 / 未开发 223**，
比集成头少 8 个已交付 —— **这是 squash 快照的时差，不是本任务的功劳，也不是本任务的欠账**。
(main 自 00:00 起连续 6 轮 NO_UPDATE，本轮是第 7 轮，也是第一次真的动了。)

## 涉及文件

- `docs/sessions/2026-10/2026-10-21-0115-main-sync-conflict-squash-divergence.md` —— 本文件（新增，单独 docs commit）
- `docs/product/capability-blueprint/module-progress.md` —— **无改动**（`sync.mjs` 跑完零 diff）
- 业务代码 —— **零改动**（合并已 abort）

## 关键 trade-off / 讨论

**要不要自己把这 6 个冲突解掉？** **不解。**
协议写死「❌ 自行解冲突」「**冲突不自行解决** —— `git merge --abort` 后把冲突文件名列进汇报」。
而且这 6 个冲突的解法（保留 main 那 2–18 行独有内容吗？）需要逐行判断，
批量取一边是「看起来快、实际埋雷」的典型。abort + 上报是正确的终态。

**要不要在 abort 后还 push 一个「已同步」的空提交？** **不。**
没有合并就没有「已同步」；把 abort 包装成成功会直接毁掉下一棒 L2 的排查依据。
本轮只推**本会话文档**（与 12:15 / 09:15 / 04:15 轮同构）。

**为什么先跑 `git merge-tree` 干跑？** 因为它是**只读**的（不碰工作区、不碰索引），
能在动手前就拿到确切的冲突集合与集合大小 —— 于是本轮对「会不会冲突」的判断是**取证来的，不是猜的**，
且真实 merge 的结果与干跑**逐条吻合**。这比「merge 完才发现冲突」多了一条可复核的证据链。

**为什么等闸等 45 分钟而不抢闸？** 持闸 tick 正在跑 L2（`mvnw verify`，同 worktree）。
抢闸 ⇒ 两个 tick 同时改集成头、同时跑 L2、抢 Testcontainers ⇒ 成片假红。
**宁可等一轮，也不能双派发**（PIPELINE.md 的原话）。

## 后续待办

1. **发布侧（本任务的长期阻塞点）**：main 的 tip 现在是集成头的 **squash**，导致 merge-base 被拉回
   `1bb270a0`、6 个文件 add/add 冲突。**建议发布侧改成推真实 commit（不 squash）**，
   本任务即可回到「无新提交」的常态；否则每一轮 main 前进都会撞同一堵墙。
2. **修复车道（若坚持要并入 main 这次的内容）**：单独开一条 fix 车道解这 6 个冲突，
   方向「以集成头为准 + 逐行核对 main 独有的 2–18 行」，**解完必须重跑 L2**
   （这批内容从未经过本流水线的 L1/L2）。
3. **下一轮流水线的 L2 排查线索**：本轮**没有产生 main merge commit** ⇒ 协议里
   「L2 红了先查 main merge commit」这条**本轮不适用**；L2 若红，仍应先查 batch-40 的五条车道
   （5.2.1.3 / 5.2.3.1 / 5.3.1.1 / 9.3.3.1 / 4.3.1.1）。
4. **闸的使用节奏（本轮数据点）**：`held + stale=false` ⇒ 密集竞态等待，实测 **45 分钟**拿到；
   `free` ⇒ 一次 acquire 即得。历史参考区间 20–30 分钟，本轮 45 分钟偏长（L2 跑了 6 个模块）。
5. **别用 `ageSeconds` 猜闸的存活**：持闸 tick 会 `touch keepalive-<新文件名>` 续期，
   `ageSeconds` 会被重置回几十秒；且判龄用的是**闸目录 mtime**，不是 `owner` 文件时间戳。
   **判据只能是 `stale` 字段。**

## 关联

- `tools/module-progress/SYNC-MAIN.md` —— 本轮执行依据的协议（集成头内那份）
- `tools/module-progress/PIPELINE.md` —— 完整流水线协议（与 SYNC-MAIN 冲突时以它为准）
- `docs/sessions/2026-10/2026-10-20-1215-main-sync-no-new-commits.md` —— 上一轮（闸一次即得，零改动，第 6 次 NO_UPDATE）
- `docs/sessions/2026-10/2026-10-20-0915-main-sync-gate-contention-no-update.md` —— 等闸 27 分钟的对照轮
- `docs/product/capability-blueprint/module-progress.md` —— 交付台账（465 个 L4 模块）

## 闸

已 `gate release`（本轮 **02:00:17 确实 `acquire` 成功之后**才释放；
等闸期间从未 release 过 ⇒ 没有误删别人活着的闸）。
