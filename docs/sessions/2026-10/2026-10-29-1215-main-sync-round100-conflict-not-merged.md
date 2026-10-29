# 2026-10-29 12:15 · 集成头同步 main 第 100 轮 —— 冲突未并入（27 个，与第 99 轮**逐字节相同**）＋ 等闸 544 秒

> 按 `tools/module-progress/SYNC-MAIN.md` 执行 `origin/main` → `integration/module-dev` 同步。
> **结果：冲突未并入**。`origin/main` tip 仍是 `823ebca5`（与第 79–99 轮**同一颗 squash**），
> 冲突面 **27 个**（26 add/add + 1 content），与第 99 轮**逐字节相同**（程序化集合比对：NEW 空、GONE 空）。
>
> **本轮新增的两点**：
> ① **等闸 544 秒**（前 99 轮多为「一次命中，零等待」）—— 闸被**活的流水线 r130 tick** 长期持有，
>    期间它正在跑 **L2 全量 `mvnw verify -fae`**（实测 1 小时+）并**每 60 秒续期**闸。
> ② **main 侧零新内容的充要证明，在本轮被独立复现**（不是引用上轮结论，而是本轮重跑取证）：
>    `origin/main^{tree}` **逐字节等于**集成头祖先 `1e3228b4^{tree}`；main 树里 **5478 个 blob，0 个**不在集成头历史中。

## 摘要

| 项                     | 值                                                                             |
| ---------------------- | ------------------------------------------------------------------------------ |
| 结果                   | **冲突未并入**（27 个冲突，26 add/add + 1 content）                             |
| **并入的 main 提交数** | **0**（冲突中止，无 merge commit）                                             |
| main 新提交            | **无**（`origin/main` tip 仍是 `823ebca5`，与第 79–99 轮同一颗 squash）         |
| 差异规模               | 1 个提交 / 1032 个文件（+510134 / −45765）—— **行数是 squash 的假象**           |
| 冲突                   | **27 个**（与第 99 轮**逐字节相同**）                                          |
| **main 侧新内容**       | **0**（本轮独立复现的两条证据，见 §三）                                        |
| 预检                   | **绿**（`./mvnw -q compile` rc=0；`pnpm typecheck` 3/3）—— 口径提醒见 §六       |
| 台账                   | **429 已交付 / 0 进行中 / 36 未开发**（465 总数，92.3%），**零文件改动**        |
| push                   | **已 push**，只含本会话日志 + backlog 更新（**零业务代码**），fast-forward       |
| 闸                     | **已释放**                                                                      |
| **等闸时长**           | **544 秒**（首读 `held`/`stale=false` ⇒ 密集竞态 `gate acquire`，**未抢未清未 force**） |

## 一、取闸：等 544 秒（**未抢闸、未清闸、未 force**）

首读 `gate status`（12:15）⇒ `held` / `ageSeconds=8` / `ttl=4200` / **`stale=false`**
⇒ 按协议判定为「**有活着的流水线 tick 持闸**」⇒ **等它释放**，不抢、不清、不 force。

协议特别要求「不要用每 20 秒 `gate status` → 看到 free 再 acquire」的写法（交接窗口可能窄到零宽度），
故直接用**密集 `gate acquire` 竞态**（`mkdirSync` 原子互斥，失败无副作用）：

```
for i in $(seq 1 900); do
  node tools/module-progress/lane.mjs gate acquire | grep -q '"ok": true' && break
  sleep 1
done
⇒ GATE ACQUIRED after 544s
```

### 1.1 持闸者是谁（活性取证全在闸外）

| 取证 | 结果 |
| --- | --- |
| `.dev-agent/dispatch.gate/owner` | PID `99642` + 时间戳 `2026-10-29T03:01:19.983Z`（11:01 起） |
| `ps -p 99642` | **查无此进程** ⇒ owner PID **不是**持闸者（`gate acquire` 是短命进程，与协议第 1 步的提醒一致） |
| 闸目录内 `keepalive-r130-*` | **每 60 秒一个新文件**（`r130-1` @11:15 → `r130-68` @12:21）⇒ **持闸 tick 活着** |
| 闸外构建进程 | `mvnw verify -fae`（PID 1688，实测 **1 小时+**）+ 不断更换的 `surefirebooter`（finance → …）⇒ **L2 正在跑** |

> ⚠️ **`ageSeconds` 会被 keepalive 重置**：`touch` 闸目录**内**的文件会刷新**目录自身的 mtime**，
> 而 `ageSeconds` 正是 `Date.now() - statSync(GATE_DIR).mtimeMs`（`lane.mjs:612`）。
> ⇒ 实测 12:21 观察到 `ageSeconds` 从 41 突降到 **1**，**不是**「闸被释放又重取」，
> **是持闸者做了一次续期**。**判活仍只看 `stale`，续期只会让 `stale` 更不容易为真。**

## 二、main 侧：仍然**没有**新提交

`git fetch origin --prune` 后：

```
git rev-list --count integration/module-dev..origin/main  ⇒ 1
git log -1 --format='%h %ad %s%nparents: %P' origin/main
  ⇒ 823ebca5 Mon Oct 26 14:49:58 2026 +0800  Integration/module dev (#66)
    parents: 6522c4976ed8959f20ab3037419d7074d596297a     # ← 单父 ⇒ squash
git merge-base integration/module-dev origin/main ⇒ 6522c497（2026-10-23 09:18）
```

⇒ 要同步 1 个提交，但它**不是 main 侧的新交付**，是**集成头分支的旧快照**。

## 三、main 侧零新内容：**本轮独立复现**的两条证据

第 99 轮已把这件事证明到充要级别。本轮**不引用**上轮结论，而是**重跑取证**：

### 证据 ①：`origin/main^{tree}` == 集成头祖先 `1e3228b4^{tree}`（逐字节相同）

```
git rev-parse origin/main^{tree}   ⇒ 83269e111f5f42e8b9481fd0e9549687aa8a7ed0
git rev-parse 1e3228b4^{tree}      ⇒ 83269e111f5f42e8b9481fd0e9549687aa8a7ed0   ← 完全相同
git log -1 --format='%h %ad %s' 1e3228b4
  ⇒ 1e3228b4 2026-10-25 23:16:05 +0800 docs(sessions): 集成头同步 main 第 75 轮
```

### 证据 ②：main 树里的每个 blob 都已在集成头历史中

```
git ls-tree -r origin/main | awk '{print $3}' | sort -u   ⇒ 5478 个唯一 blob
comm -23 main_blobs our_blobs                             ⇒ 0
```

⇒ **main 侧 0 个 blob 是集成头没有的** ⇒ main 不含任何我们尚未拥有的内容。
⇒ 这 27 个冲突**不是「真分叉」，是 git 对「同一份内容的两种历史」的机械撞车**。

## 四、冲突面：27 个，与第 99 轮**逐字节相同**

### 4.1 程序化集合比对（按 basename，规避第 99 轮 backlog 的「目录前缀只写一次」记法差异）

```
round99(记录于 backlog) = 27   round100(本轮实测) = 27
NEW  : （空）
GONE : （空）
```

### 4.2 theirs 侧独有行数：与第 99 轮**完全一致**

口径：`git diff --numstat HEAD origin/main -- <file>`（`+theirs` = theirs 独有行数）。

| 口径                 | 第 99 轮 | **第 100 轮** |
| -------------------- | -------- | ------------ |
| 文件数               | 27       | **27**       |
| **theirs 独有 = 0 ⇒ 取 ours 无损** | **4** | **4**（同一批：`api-proxy.ts` / `LinkageRiskIdentityStub.java` / `ReportingFinancePort.java` / `ReportingUpstreamClient.java`） |
| **theirs 独有行合计** | **79**   | **79**       |
| ours 独有行合计      | 1643     | **1786**     |

> ours 独有行从 1643 涨到 **1786**（+143），是**集成头这 3 天推进**（batch 110/111/112）带来的。
> ⇒ 再次印证第 99 轮**修正后的判据**：**冲突面只在「集成头改动了已存在的业务/测试文件」时才增长**，
> **不是**「集成头前进了就增长」。本轮集成头新增的是**新模块文件**（batch 110–112），
> 落在已存在文件上的改动只有 `c904ec63`（`SaasUsageIntegrationTest.java`）那一处 ⇒ **冲突面 27 → 27 不变**。

theirs 侧 79 行的构成与第 99 轮**逐行分类一致**（最大项仍是
`AnomalyOrderIntegrationTest.java` **35 行** JVM 墙钟基准、`AnomalyOrderTenancyIntegrationTest.java` **11 行** 同族、
`SalesTaxIntegrationTest.java` **7 行**、`SaasUsageIntegrationTest.java` **3 行**旧 flaky 子串断言）。

### 4.3 未解任何冲突（协议硬边界）

`git merge --abort` 已执行：

```
git rev-parse -q --verify MERGE_HEAD  ⇒ 无（已清）
git status --porcelain                ⇒ 空
git diff --diff-filter=U              ⇒ 空
git rev-parse HEAD                    ⇒ 85f34620（== PRE，零位移）
```

⇒ **未自行解任何冲突**，MERGE_HEAD 无残骸（不会堵死流水线的 merge-ready）。

### 4.4 干净合上的部分

契约与台账两类由 `merge-union` 驱动自动求并成功
（`packages/api-contracts/src/contracts/{channel,identity,reporting}.ts`、
`packages/api-contracts/src/{channel,identity,reporting}.openapi.json`、
`docs/product/capability-blueprint/module-progress.md`），其余业务文件由 git 三方合并干净合上。

## 五、台账：429 / 0 / 36（相对第 99 轮 +3 已交付，来自流水线自己的批次）

```
node tools/module-progress/sync.mjs --check  ⇒ ✓ 台账与蓝图一致
node tools/module-progress/sync.mjs          ⇒ ✓ 同步 18 卷 / 465 个 L4 模块
                                                已交付 429 · 进行中 0 · 未开发 36
git status --porcelain                      ⇒ 空（**零文件改动**）
```

第 99 轮为 **426 / 0 / 39** ⇒ **+3 已交付**，来自流水线这期间自己的 batch 110–112 交付，
**不是**本次同步带来的（本次**没有**任何合并发生）。

## 六、预检：绿，但**检的不是合并结果**

本轮按「冲突即中止」推进，**没有 merge commit 可检**；为了给流水线留基线信号，
仍在**未合并**的 HEAD（`85f34620`）上跑了快速预检：

| 检查 | 结果 |
| --- | --- |
| `cd backend && ./mvnw -q compile` | **rc=0** |
| `pnpm typecheck` | **3/3 successful**（596ms，3 个缓存命中） |

> ⚠️ **口径提醒**（同第 98/99 轮）：这两个绿只说明**当前未合并的集成头**编译与类型是干净的，
> **不代表「合并后」也是绿的** —— 本轮没有合并发生，**不能**当作合并结果的预检结论。
>
> 本轮**没有**跑全量 `mvnw verify`（发热高峰，属流水线的 L2）。
> 本轮**等闸 544 秒的原因正是**看到闸外有活着的 `mvnw verify -fae` 在跑 —— **全程未与它并发过任何构建**。

## 七、push：只推了本会话日志 + backlog 更新（**零业务代码**）

- 推前复核：远端 `origin/integration/module-dev` 与本地 HEAD 关系为 **fast-forward**；
- 本次 push 的 delta = **纯 docs 提交**，**零业务代码、零契约、零迁移**；
- ✅ **只 push 集成头，绝不 push `main`**；**未 push 任何合并结果**（本轮没有合并结果可推）。

> 📌 **为什么本轮还 commit + push 了日志**：集成头 worktree 若留下未提交文件，
> 下一轮流水线的 `lane.mjs merge-ready` 会因 `git status --porcelain` 非空而**硬拒**
> ⇒ 未提交 = 堵住下一轮并入通道（第 97/98/99 轮同款教训）。
> （本轮开工时看到的脏工作区 —— `M module-progress.md` + `?? 2026-10-29-batch-112-*.md` ——
> 是流水线 r130 tick 自己的中间态，它已自行提交为 `85f34620`；合并前复核工作区已干净。）

## 八、后续待办

1. **P0（发布侧，未变）**：`origin/main` 的 tip 必须是一颗**正常 merge**（双父、基于集成头最新 tip），
   而不是集成头历史的 squash 快照。否则每一轮同步都会撞同一堵墙（第 79–100 轮，**已 22 轮**）。
2. **P0（同步侧，未变）**：「取 ours 无损」的等价性保证（第 99 轮证据 ③）仍然成立，
   但**协议禁止自行解冲突** ⇒ 仍需**协议层授权**（`SYNC-MAIN.md` 加一条
   「main 侧 blob 已被集成头历史覆盖 ⇒ 允许 `-X ours` 机械合并」的判据），或由发布侧直接改 main 的 tip（首选）。
3. **P0（流水线侧，未变）**：`SaasUsageIntegrationTest.java` 的 flaky 修复（`c904ec63`）已在集成头，
   **不要**为「让同步好过」而回退它 —— 取 theirs 会把 flaky 断言装回去。
4. **协议级待补（第 96 轮提出，第 99/100 轮再次实证成立）**：`SYNC-MAIN.md` 可补一条判据 ——
   **「add/add ≠ 真分叉」**：先查 main 侧 blob 是否在集成头历史中原样存在，命中即线性演进、可机械取集成头侧。
5. **⚠️ 协议级待补（本轮新增）**：`SYNC-MAIN.md` 的「等闸」段只说了**怎么等**，没写**等多久算等不到**。
   本轮实测量级：**活着的 L2 tick 可以持有闸 1 小时+**（含每 60 秒续期 ⇒ `stale` 永远不会为真）。
   ⇒ 建议补一条「等闸上限（如 30 分钟）+ 超时如实汇报退出」的显式判据，避免无限等待。

## 九、涉及文件

- 新增：`docs/sessions/2026-10/2026-10-29-1215-main-sync-round100-conflict-not-merged.md`（本文件）
- 更新：`docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`（补第 100 轮的冲突面比对、theirs 行数、闸续期观测）
- 未新增/修改任何**业务代码**（本任务只搬运 commit）
- 未解冲突、未 push 合并结果、未跑全量 `mvnw verify`

## 十、关联

- `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` —— P0 根源（本轮已更新）
- `docs/sessions/2026-10/2026-10-29-0720-main-sync-round99-conflict-not-merged.md` —— 上一轮（同墙，27 个）
- `docs/sessions/2026-10/2026-10-29-0300-pipeline-round126-gate-held-live-peer-l2.md` —— 流水线侧的「闸被活 peer 持有」同款记录
- `docs/backlog/2026-10/2026-10-20-cross-clock-domain-test-assertion.md` —— 跨时钟域断言族（`AnomalyOrder*` 冲突同族）
- `tools/module-progress/SYNC-MAIN.md` · `tools/module-progress/PIPELINE.md`
