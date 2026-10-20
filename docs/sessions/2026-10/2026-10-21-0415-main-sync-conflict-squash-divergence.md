# 2026-10-21 04:15 main → 集成头同步：等闸 27 分钟，merge 冲突 6 文件，未并入

> 本轮**未 push 业务代码、未跑预检、未改任何业务代码**。
> 结果是四选一里的**「冲突未并入」**。
> 这是 `origin/main` 自 `d28188d6`（2026-10-20 20:39 squash PR #11）之后的**连续第 3 轮 HAS_UPDATE 撞同一堵墙**。

## 一句话结论

04:15 触发时调度闸 `gate=held`（`stale=false`，`ageSeconds=509`），持闸者是 **04:02:33 触发的
流水线 tick（batch-43）**，正在同一个 worktree 里跑它的 L2（`mvnw verify` 的 channel → warehouse →
trade → crm 四个服务 surefire 套件，外加 3 条车道 worktree 的测试与 `next build`）。

按协议用**密集 `acquire` 竞态**等待，**1609 次尝试、约 27 分钟**后在 **04:43:49** 取得闸
（owner pid 60314）—— 本轮**没有**靠 TTL 接管，是实打实等到了持闸者正常释放。

取得闸后 `git fetch origin --prune` 无 ref 更新，`origin/main` 仍是 `d28188d6`，
且它**不是集成头的祖先** ⇒ `merge-base --is-ancestor` 判定为假 ⇒ **HAS_UPDATE**，要同步 1 个提交。

`git merge --no-ff origin/main` 在**同样的 6 个文件上冲突** ⇒ 按协议 `git merge --abort`，
**未自行解冲突、未 push 业务代码**，集成头仍停在 `39edfe12`（与远端零偏差）。

## 本轮参数（实时读取，非历史文档）

| 项                    | 值                                                                                            | 来源                                            |
| --------------------- | --------------------------------------------------------------------------------------------- | ----------------------------------------------- |
| 集成头 worktree       | `/Users/guan/Projects/starcon-worktrees/integration`                                          | `.dev-agent/PIPELINE.local.md` → `PROJECT_ROOT` |
| 推送远端              | `origin`                                                                                      | 同上 → `PUSH_REMOTE`                            |
| 协议文件              | `tools/module-progress/SYNC-MAIN.md`（**集成头内那份**）                                      | 本轮实际读取路径                                |
| 集成头 head（合并前） | `39edfe12`（batch-43：六车道并入集成头 + L2 六阶段全绿）                                        | `git rev-parse`                                 |
| `origin/main` tip     | `d28188d6`（Integration/module dev (#11)，**GitHub squash 合并**，2026-10-20 20:39:44 +0800） | `git log -1 origin/main`                        |
| merge-base            | `1bb270a0`（batch-28 tip）                                                                    | `git merge-base`                                |
| 本轮 acquire 时刻     | **04:43:49**（owner pid 60314，1609 次尝试）                                                   | `dispatch.gate/owner`                           |
| 本轮等闸时长          | **约 27 分钟**（04:16 → 04:43:49）                                                            | 竞态脚本计数                                    |
| 台账（合并前 / 后）   | 465 个 L4 模块：**258 已交付 · 0 进行中 · 207 未开发**（完成率 55.5%）                        | `sync.mjs --check` + `lane.mjs status`          |

## 取证一：闸被 batch-43 的流水线 tick 正常持有，等了 27 分钟

```
$ node tools/module-progress/lane.mjs gate status        # 04:16 首次
{ "gate": "held", "ageSeconds": 509, "ttlSeconds": 4200, "stale": false }
```

`held + stale=false` ⇒ 按协议**等它释放**，不抢、不清、不 force。

**活性取证（闸外）**：`ps` 查 `mvnw|surefire|java` ⇒ 命中**同 worktree 内正在跑的 L2**：

```
backend/services/channel/target/surefire/surefirebooter-20261021041300020_127.jar
backend/services/warehouse/target/surefire/surefirebooter-20261021041847969_219.jar
backend/services/trade/target/surefire/surefirebooter-20261021042948854_380.jar
backend/services/crm/target/surefire/surefirebooter-20261021043915084_533.jar
.worktrees/6.2.1.2/...  .worktrees/6.1.2.3/...  .worktrees/5.4.1.2/...   ← 车道 worktree 的测试
node .../apps/web/node_modules/.bin/next build                         ← 前端 L2
```

⇒ 持闸者是**活着的流水线 tick**（与 03:15 轮「闸泄漏、pid 已死」的情形**完全不同**），
本轮是正常等它跑完。

⚠️ **`ageSeconds` 会骗人**：`ageSeconds` 取的是 `dispatch.gate/` 目录的 mtime，
而持闸者每轮 `keepalive-XXXXXX` 会在该目录里建新文件 ⇒ **目录 mtime 被不断顶高、ageSeconds 反而变小**。
本轮实测 age 从 509 → 174 → 42 → 16 → 124 的锯齿形变化，而持闸者始终是同一个（owner 文件
始终是 `39793 / 2026-10-20T20:02:33.801Z`）。**判活只能用 `stale` 字段**，不要按 ageSeconds 猜。

**竞态等待（协议第 1 步「等闸」）**：

```bash
for i in $(seq 1 3600); do
  if node tools/module-progress/lane.mjs gate acquire | grep -q '"ok": true'; then
    echo "GATE ACQUIRED after ${i}s"; break
  fi
  sleep 1
done
```

⇒ **04:43:49 取得闸（第 1609 次尝试）**，owner 写成 pid 60314。

⚠️ **等闸期间没跑任何构建**（L2 与预检共用 `backend/**/target/` 与 PG 容器 ⇒ 并发必假红），
只做**只读取证**：`gate status` / `cat owner` / `ls .dev-agent/dispatch.gate/` / `ps` / `git log`，
**连 `git fetch` 都等到拿到闸之后才跑**。

## 取证二：main 有新提交，但它是集成头自己的 squash 快照（**本轮新增关键证据**）

```
$ git fetch origin --prune            # 无 ref 更新
$ git merge-base --is-ancestor origin/main integration/module-dev ; echo $?
1        # ⇒ origin/main 不是集成头的祖先 ⇒ HAS_UPDATE

$ git log --oneline integration/module-dev..origin/main
d28188d6 Integration/module dev (#11)
```

`d28188d6` 的形态与 01:15 / 03:15 轮取证一致：**单父提交**（父 = `1bb270a0`）⇒ GitHub squash 合并产物。

**本轮新做的决定性取证 —— 比「main 落后 16 个模块」更硬**：

```
$ git rev-parse origin/main^{tree}
06607694b31c6fdfe354ed4783c22d4a0e0b8c20

$ git log --format='%H %T %s' integration/module-dev | grep ' 06607694b31c6fdfe354ed4783c22d4a0e0b8c20 '
d22bc1e125264e5321c7d62363b5e399d28715a1 06607694b31c6fdfe354ed4783c22d4a0e0b8c20 \
  docs(batch-37): 10.3.1.2 / 10.3.2.2 / 10.6.2.1 / 10.7.1.2 —— 四车道并入 + L2 绿的修复与验证
```

⇒ **`origin/main` 的树与集成头自己的祖先提交 `d22bc1e1`（batch-37）逐字节相同。**

换句话说：**main 上「要同步的内容」集成头早就有了**（而且更新的版本也有）。
这次 merge 在**内容上是空转**的 —— 它不打算往集成头搬任何一行新代码，
只是因为 squash 抹掉了血缘（merge-base 被拉回 `1bb270a0`），
git 只能按「两边各自新增」处理，于是 6 个文件炸成 add/add。

**规模数字**（`git diff --stat integration/module-dev...origin/main`）：
`406 files changed, 262390 insertions(+), 34181 deletions(-)`。
⚠️ 这个数字是 **squash 把 85 个 commit 的行数重放**造成的，**不是真有 26 万行新代码**，不要按它估工作量。

## 取证三：台账逐行比对 ⇒ main 侧**零新交付**，且比集成头落后 16 个模块

按 L4 模块 ID 逐行比对 main 快照与集成头的台账：

* 模块集合**完全相同**：`only in main = []`、`only in head = []`（两边各 465 个）
* 状态不同的有 **16 个，方向全部一致**（main = 未开发，集成头 = 已交付）：

| 模块 ID  | 名称             | main 快照 | 集成头     |
| -------- | ---------------- | --------- | ---------- |
| 5.2.1.2  | 到岸成本         | 未开发    | **已交付** |
| 5.2.1.3  | 成本分摊         | 未开发    | **已交付** |
| 5.2.2.1  | 汇率查询         | 未开发    | **已交付** |
| 5.2.2.2  | 汇率快照         | 未开发    | **已交付** |
| 5.2.3.1  | 平台费用         | 未开发    | **已交付** |
| 5.2.3.2  | 物流与头程费用   | 未开发    | **已交付** |
| 5.3.1.1  | 利润测算器       | 未开发    | **已交付** |
| 5.3.1.2  | 批量测算         | 未开发    | **已交付** |
| 5.3.2.1  | 定价模板         | 未开发    | **已交付** |
| 5.5.2.1  | 异常告警         | 未开发    | **已交付** |
| 6.1.2.1  | 档口与货源池     | 未开发    | **已交付** |
| 6.2.1.1  | 缺货与断货风险   | 未开发    | **已交付** |
| 6.3.2.2  | 到货与逾期       | 未开发    | **已交付** |
| 10.7.1.3 | 弃置与销毁       | 未开发    | **已交付** |
| 15.2.2.1 | Request a Review | 未开发    | **已交付** |
| 15.2.2.2 | 索评规则         | 未开发    | **已交付** |

台账汇总口径同样吻合：

| 指标     | main 快照          | 集成头 `39edfe12` | 变化 |
| -------- | ------------------ | ----------------- | ---- |
| 模块总数 | 465                | 465               | —    |
| 已交付   | **242**（完成率 52.0%） | **258**（完成率 55.5%） | +16 |
| 进行中   | 0                  | 0                 | ±0   |
| 未开发   | 223                | 207               | −16 |

⇒ **main 没有任何一个模块是集成头没有的**（`main 领先` 的集合为空）；
main 是集成头**早 16 个模块的旧快照**。本任务想同步的内容，**集成头已经有了（且更新）**。

## 取证四：merge 冲突 6 个文件，与 01:15 / 03:15 轮完全一致

真实 `git merge --no-ff origin/main`：

```
merge-union: 走了行级兜底并集，请人工确认这个文件改对了
merge-union: 台账按模块 ID 求并集，折叠了 465 行同 ID 记录
Automatic merge failed; fix conflicts and then commit the result.
```

✅ **契约与台账两类文件由 `merge-union` 驱动自动求并成功**（406 个文件里的绝大部分）：
`packages/api-contracts/src/contracts/{catalog,channel,trade}.ts`、
`packages/api-contracts/src/{catalog,channel,trade,warehouse}.openapi.json`、
`docs/product/capability-blueprint/module-progress.md`（按模块 ID 求并集，465 行同 ID 记录折叠）。

❌ **6 个文件冲突**（协议规定**不自行解**）：

| #   | 文件                                                                                               | 冲突类型 | 集成头独有行 / main 独有行 |
| --- | -------------------------------------------------------------------------------------------------- | -------- | -------------------------- |
| 1   | `apps/web/src/app/pricing/cost/cost-composition-page.test.ts`                                      | add/add  | +40 / −18                  |
| 2   | `apps/web/src/components/pricing/cost-composition-model.test.ts`                                   | add/add  | +19 / −7                   |
| 3   | `apps/web/src/components/pricing/cost-composition-model.ts`                                        | add/add  | +8 / −7                    |
| 4   | `apps/web/src/components/pricing/cost-composition-workspace.tsx`                                   | add/add  | +13 / −0                   |
| 5   | `apps/web/src/lib/api-proxy.ts`                                                                    | content  | +675 / −0                  |
| 6   | `backend/services/channel/src/test/java/com/starcon/channel/ListingPublishJobIntegrationTest.java` | add/add  | +134 / −2                  |

（「+N」= 集成头独有行数，「−N」= main 独有行数；口径 = `git diff --numstat d28188d6 39edfe12 -- <file>`）

**abort 后的状态核验**：

```
$ git merge --abort
$ git status --porcelain        # 空
$ git rev-parse HEAD            # 39edfe12c4fb0862fdfbce7b8f55c782263fe914   （= 合并前 PRE）
$ test -f .git/MERGE_HEAD       # NO   ← 没有 MERGE_HEAD 残留，不会堵死流水线的 merge-ready
$ git rev-parse origin/integration/module-dev
39edfe12c4fb0862fdfbce7b8f55c782263fe914   ← 与远端零偏差
```

⇒ 集成头**完全回到合并前**，无残留、无半合并状态，流水线可以正常继续。

## 取证五：6 个冲突文件上，main 那一侧是**过期快照**，不是新交付

沿用 03:15 轮的定性（逐文件看 main 独有内容），本轮换到新 head `39edfe12` 后**结论不变**：

| 文件                                    | main 独有内容是什么                                                                                         | 集成头现状                                                                                       |
| --------------------------------------- | ----------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| `cost-composition-model.ts`             | `delivered: false` + 「🔴 5.2.1.2 **未交付**」/「🔴 5.2.1.3 **未交付**」的摘要文案                          | 5.2.1.2 / 5.2.1.3 **都已交付**，`delivered: true`                                                |
| `cost-composition-model.test.ts`        | 断言 5.2.1.2 / 5.2.1.3 `delivered === false` 且摘要含「未交付」                                             | 集成头改成**随交付状态翻转**的断言，且明确不断言未交付分区的具体关键词                         |
| `cost-composition-page.test.ts`         | 同上的「未交付」断言 + 三个 moduleId 的固定列表断言                                                         | 集成头已改为「已交付 ⇒ 摘要不得再写未交付；未交付 ⇒ 必须写明未交付」的通用断言                   |
| `ListingPublishJobIntegrationTest.java` | `OffsetDateTime before = now().minusSeconds(2); assertThat(dispatchedAt()).isAfter(before)`（JVM 墙钟基准） | 集成头已用 **DB 时钟同源**重写，并**专门写了一条 `assertThatThrownBy` 把这条旧写法钉死成必红**       |
| `cost-composition-workspace.tsx`        | **0 行**（严格超集）                                                                                        | —                                                                                                |
| `api-proxy.ts`                          | **0 行**（`services[]` 元素：集成头比 main 多 675 行，严格超集）                                            | 严格超集                                                                                          |

⇒ **这 6 个文件上，main 那一侧是集成头自己工作的旧快照**，不是 main 侧的新交付；
其中 2 个文件（4、5）集成头是**严格超集**，另 4 个文件里 main 的「独有内容」是
**已经被后续交付推翻的旧断言**（集成头甚至把其中一条旧写法钉成必红）。

⚠️ **但这仍不等于「全选 ours 就是对的」**：第 1/2/3/6 个文件里 main 确实各有 2–18 行
集成头没有的**文本**，是否要保留必须逐文件人工看（例如 `cost-composition-model.ts` 里
「未交付」文案换成「已交付」后，兄弟模块的口径说明措辞可能还需要保留）。
**批量取一边 = 埋雷**，所以本轮仍然 abort + 上报。

## 台账变化

| 指标     | 合并前 | 合并后（abort + `sync.mjs --check`） | 变化 |
| -------- | ------ | ----------------------------------- | ---- |
| 模块总数 | 465    | 465                                 | —    |
| 已交付   | 258    | 258                                 | ±0   |
| 进行中   | 0      | 0                                   | ±0   |
| 未开发   | 207    | 207                                 | ±0   |
| 完成率   | 55.5%  | 55.5%                               | ±0   |

`node tools/module-progress/sync.mjs --check` 输出：`✓ 台账与蓝图一致`，
**零 diff**（`git status --porcelain` 为空）⇒ 台账已由 batch-43 刷新到位，本轮无新增。
（用 `--check` 而非默认写入模式，是为了在「冲突未并入」轮里**不留下脏工作区**，
不挡住下一轮同步。）

参照：main 的台账快照是 **已交付 242 / 进行中 0 / 未开发 223**（完成率 52.0%），
比集成头少 16 个已交付 —— **这是 squash 快照的时差，不是本任务的功劳，也不是本任务的欠账**。

## 关键 trade-off / 讨论

**要不要自己把这 6 个冲突解掉？** **不解。**
协议写死「❌ 自行解冲突」「**冲突不自行解决** —— `git merge --abort` 后把冲突文件名列进汇报」。
本轮还多了一条证据让「解」更不划算：**main 的树与集成头祖先 `d22bc1e1` 逐字节相同**，
即这次 merge 本质是空转；但即便如此，全取 ours 在第 1/2/3/6 个文件上仍可能有 2–18 行
「已被推翻的旧断言」需要逐行核对，协议不允许，且「大概率对」不构成放行依据。

**要不要在 abort 后还 push 一个「已同步」的空提交？** **不。**
没有合并就没有「已同步」；把 abort 包装成成功会直接毁掉下一棒 L2 的排查依据。
本轮只推**本会话文档**（与 01:15 / 03:15 / 12:15 / 09:15 / 04:15 轮同构）。

**为什么这轮没先跑 `git merge-tree --write-tree` 干跑？** 03:15 轮用只读干跑先拿到了冲突集合；
本轮直接真合并后 abort，得到的冲突集合**与前两轮完全一致**（同样 6 个文件），
事后用 `git diff --numstat` 补齐了逐文件行数口径。结论没受影响，
但**下轮建议沿用 03:15 轮的干跑优先顺序** —— 先取证再动手。

**为什么等闸等 27 分钟而不抢闸？** `stale=false` ⇒ 协议要求等。
本轮 `ps` 明确查到同 worktree 里有多个 surefire 套件在跑 ⇒ 持闸者是**活着的流水线 tick**。
抢闸 ⇒ 两个 tick 同时改集成头、同时跑 L2、抢 Testcontainers ⇒ 成片假红。
**宁可等一轮，也不能双派发**。

## 后续待办

1. **发布侧（本任务的长期阻塞点，连续 3 轮撞同一堵墙）**：main 的 tip 是集成头的 **squash**，
   导致 merge-base 被拉回 `1bb270a0`、6 个文件 add/add 冲突，**且 main 侧零新交付**。
   **建议发布侧改成推真实 commit（不 squash）**，本任务即可回到「无新提交」的常态；
   否则每一轮 main 前进都会撞同一堵墙。
   已按 backlog 规范落档：`docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`。
2. **同步任务的收尾保障（连续 2 轮暴露，本轮未复现）**：02:15 那个回合被截断时**既没写会话日志也没
   `gate release`**，直接泄漏了一个 70 分钟的闸。本轮**没有**复现该问题
   —— 闸在 merge 阶段结束后**先 release 了一次**，写会话文档前**又用竞态重新取回**（1 秒拿到），
   push 完再 release。全程 `release` 次数与 `acquire` 成功次数配平，没有误删别人活着的闸。
3. **修复车道（若坚持要并入 main 这次的内容）**：单独开一条 fix 车道解这 6 个冲突，
   方向「以集成头为准 + 逐行核对 main 那 2–18 行独有内容是否需要保留」，
   解完**必须重跑 L2**（这批内容从未经过本流水线的 L1/L2）。
4. **下一轮流水线的 L2 排查线索**：本轮**没有产生 main merge commit** ⇒ 协议里
   「L2 红了先查 main merge commit」这条**本轮不适用**；L2 若红，仍应先查 batch-43 的六条车道
   （5.2.3.2 / 5.3.1.2 / 5.3.2.1 / 6.1.2.1 / 6.2.1.1 / 6.3.2.2）。
5. **闸的使用节奏（本轮数据点）**：`held + stale=false` ⇒ 密集竞态等待，实测 **27 分钟**拿到；
   历史参考区间 20–45 分钟。本轮是**正常释放**（非 TTL 接管），
   与 03:15 轮「TTL 超点接管」形成对照 —— **两种收尾路径都可用**。

## 涉及文件

- `docs/sessions/2026-10/2026-10-21-0415-main-sync-conflict-squash-divergence.md` —— 本文件（新增，单独 docs commit）
- `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` —— **更新**（轮次 2 → 3，补本轮取证）
- `docs/product/capability-blueprint/module-progress.md` —— **无改动**（`sync.mjs --check` 零 diff）
- 业务代码 —— **零改动**（合并已 abort）

## 关联

- `tools/module-progress/SYNC-MAIN.md` —— 本轮执行依据的协议（集成头内那份）
- `tools/module-progress/PIPELINE.md` —— 完整流水线协议（与 SYNC-MAIN 冲突时以它为准）
- `docs/sessions/2026-10/2026-10-21-0315-main-sync-conflict-squash-divergence.md` —— 上一轮（**同样 6 文件冲突**，等闸 40 分钟，闸为 TTL 接管）
- `docs/sessions/2026-10/2026-10-21-0115-main-sync-conflict-squash-divergence.md` —— 再上一轮（**同样 6 文件冲突**，等闸 45 分钟）
- `docs/sessions/2026-10/2026-10-21-batch-43-six-lane-merge.md` —— 本轮等闸期间持闸的流水线批次（batch-43）
- `docs/backlog/2026-10/2026-10-21-api-proxy-shared-file-merge-conflict.md` —— `api-proxy.ts` 共享白名单文件的结构性冲突根因
- `docs/product/capability-blueprint/module-progress.md` —— 交付台账（465 个 L4 模块）

## 闸

已 `gate release`。本轮 **04:43:49 确实 `acquire` 成功之后**才释放（merge 阶段结束后先 release 一次）；
写本会话文档前**又 `acquire` 成功一次**（1 秒），push 完再 release 一次。
等闸与两次释放期间从未 release 过别人持有的闸 ⇒ 没有误删活着的闸。
