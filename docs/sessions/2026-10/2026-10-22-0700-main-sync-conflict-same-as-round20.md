# 2026-10-22 07:00 集成头同步 main（第 21 轮 · 冲突未并入 · 冲突面与第 20 轮逐字节相同 · main 侧新增「解冲突判定器」）

## 摘要

「main → 集成头 `integration/module-dev`」同步任务第 21 轮。

**结果：冲突未并入，未推送任何合并内容，集成头 HEAD 保持 `a3596a9b` 不变。**
四选一里的「**冲突未并入**」。

本轮与第 18 / 19 / 20 轮同属一堵墙，但**冲突面与第 20 轮逐字节相同**（3 文件 13 区，
stage1/2/3 blob 哈希完全一致），且有一条**新增结论**：

1. **main 本轮未推进**：tip 依旧是 `a105acf0`（2026-10-22 02:42:58 +0800），
   自第 18 轮（04:04）以来连续 4 轮零推进，领先集成头 **26 个提交**。
2. **main 侧新增了正好用来解这堵墙的工具**：`tools/module-progress/merge-resolve.mjs`
   （冲突解法判定器）与 `merge-summary.mjs`（功能增量摘要），带 3 个 itest。
   本轮用它们做**只读**取证，**独立复现了本任务自己算出的结论**（见「取证五」）。

## 决策 / 结论

1. **闸：等了 40 分钟才拿到，没抢、没清、没 force。**
   本轮起跑（06:15 前后）闸被一个**活着的流水线 tick**持有
   （`gate status` ⇒ `held / stale:false`；闸外取证：同 worktree 里 `mvnw verify` 在跑，
   pid 25506，随后是 channel / trade 服务的 surefire 集成测试连续推进）。
   按协议**密集竞态 `gate acquire`**（1 秒一次），于 **06:56:20** 取得
   （第 2299 次 attempt）。等闸期间**只做只读取证**（`gate status` / `cat owner` / `ps` / `git log`），
   没跑第二个构建（避免与 L2 抢 `backend/**/target/` 与 PG 容器造成假红），
   **连 `git fetch` 都压到拿闸之后才跑**。

2. **冲突只报不解**（协议 §0 + 第 3 步）。`git merge --abort` 退出码 0，
   `MERGE_HEAD` 已清，工作区 `git status --porcelain` 为空，HEAD 回到 `a3596a9b`，
   **未 push 任何合并内容**。

3. **本轮冲突面 = 3 个文件、13 个冲突区**，与第 20 轮**逐字节相同**：

   | 文件                                                              | 冲突区                       | 性质       | 本轮取证结论                                                                                                                                   |
   | ----------------------------------------------------------------- | ---------------------------- | ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
   | `apps/web/src/lib/api-proxy.ts`                                   | **9**（行 571/719/2550/4478/5204/5617/5725/6018/6916） | 单边       | main 的 blob `7f844ce6` == 集成头线性祖先 `976d7d42` 的 blob ⇒ **取集成头侧可证零丢失**；路由 token 对称差：**只属于 main = 0**                          |
   | `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md` | **1**（行 394）              | 单边       | main 的 blob `26fb4553` == 集成头线性祖先 `98de97fd` 的 blob ⇒ **取集成头侧可证零丢失**                                                          |
   | `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`                 | **3**（行 152/184/220）      | **真双向** | main 的 blob `4c77f6f3` **不在**集成头历史里 ⇒ main 独有的「落地树哈希」判据段集成头侧没有 ⇒ **单边取任一侧都丢内容，必须人工求并** |

   ⇒ **本轮与第 20 轮的冲突面完全一致**（stage3 blob 哈希逐个相同）：
   `7f844ce6` / `26fb4553` / `4c77f6f3`。
   这是「main 零推进 + 集成头侧无新增共享文件改动」两个条件同时成立的直接结果。

4. **main 相对集成头依然零业务新增**（本轮三路重新取证）：

   | 维度             | 方法                                          | 结论                                                                                                      |
   | ---------------- | --------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
   | 白名单路由 token | `api-proxy.ts` 两侧全文抽 path token 求对称差 | **只属于 main = 0**，只属于集成头 = 1（`/influencers`，14.5.1.1 达人库，batch-60 带来）                     |
   | 树差异「A」侧    | `git diff --name-status HEAD origin/main`     | **23** 个「main 有、集成头没有」= 14 个 `docs/sessions/**` + 8 个 `tools/progress-site/**` + 1 个 `docs/backlog/**`；**落在 `backend/` 或 `apps/` 下的 = 0** |
   | 台账模块数       | main 侧 `merge-summary.mjs` 反向跑             | 集成头比 main **多 11 个已交付模块**（全是 14.x 广告与增长：14.1.1.2 / 14.1.1.3 / 14.1.2.1 / 14.1.3.2 / 14.2.2.1 / 14.3.1.1 / 14.3.1.2 / 14.3.2.2 / 14.4.1.1 / 14.4.1.2 / 14.5.1.1） |

   ⇒ **main 侧没有任何集成头不具备的业务内容。** 本轮即使解完冲突并入，
   净增的也只有 14 个会话文档 + 8 个看板工具文件 + 1 个 backlog 文档，**业务代码净增 0**。

5. **【本轮新增结论】main 自己带了「解冲突判定器」，它独立复现了本任务的结论。**
   `tools/module-progress/merge-resolve.mjs`（main 侧新增，276 行 + itest）是**只读**判定器，
   用 `git merge-tree --write-tree` 在内存里算冲突，不碰工作区。本轮把 main 侧 blob 取出
   直接跑（不改仓库），结论与本任务自己算的 blob 级证明**逐条一致**：

   ```
   [取集成分支侧] apps/web/src/lib/api-proxy.ts
       依据：main 侧是集成分支历史上的祖先快照，且累积型三件套全部成立
       累积型三件套：集合包含 ✓ · 有序子序列 ✓ · 零删除 ✓（条目 125 → 136）
   [取集成分支侧] docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md
       依据：main 侧是集成分支历史上的祖先快照 ⇒ 两侧是线性演进，取新侧不丢内容
   [并入（人工）] tools/module-progress/MAIN_MERGE_CRON_PROMPT.md
       依据：main 侧的文件版本不在集成分支的历史里 —— main 有集成分支从未有过的内容，直接取一边会丢东西
   结论：1 个文件必须人工并入，其余按上面标注机械执行。   （退出码 1）
   ```

   ⇒ **即使解冲突的工作全部做完，本轮仍有 1 个文件（`MAIN_MERGE_CRON_PROMPT.md`）必须人工求并。**
   这不是「本轮做不做得完」的问题，是「本轮在协议下**根本不该做完**」的问题。

6. **台账数字（本轮零变化）**：已交付 **308** / 进行中 **0** / 未开发 **157**
   （465 个模块，完成率 66.2%）。与第 20 轮完全相同。
   `node tools/module-progress/sync.mjs --check` ⇒ **✓ 台账与蓝图一致**（merge 已 abort，台账未被改动）。

7. **本轮跑了预检**（与第 18/19/20 轮「abort 后不跑预检」不同）：
   `./mvnw -q compile` ⇒ **exit 0**；`pnpm typecheck` ⇒ **3/3 全绿**。
   理由：本轮虽然没有合并产物要推，但**要推一个会话文档提交**，
   协议第 4 步「预检绿才 push 集成头」应当对这唯一一次 push 生效。
   顺带给下一轮流水线一个「集成头当前可编译、类型检查绿」的基线。
   （**未跑** `mvnw verify` —— 那是流水线的 L2，不属本任务。）

8. **本轮只提交了本会话文档**（AGENTS.md「每次有结论的会话都要落到 `docs/sessions/`」），
   **业务代码零改动**；文件名带轮次时间戳，main 侧不存在同名路径 ⇒ 不会给下一轮制造 add/add 冲突。

## 关键 trade-off

1. **等 40 分钟 vs 提前退出** ⇒ 等。
   协议第 1 步的判据是 `gate status` 的 `stale` 字段，实测 `held + stale:false`
   ⇒ 持闸者是活着的 tick（闸外 `ps` 见 `mvnw verify` + surefire 集成测试连续推进），
   **等它释放本轮就能正常同步**。提前退出等于白跑这一轮。
   实测交接窗口确实极窄：上一轮 tick 于 06:51:45 释放并被下一 tick **同秒**接走，
   本轮竞态在 2299 次 attempt 才撞上。

2. **main 新增的 `merge-resolve.mjs` 明明能机械解掉 2/3 冲突，本轮用它吗** ⇒ **只取证，不解冲突**。
   理由有三：
   (a) 协议 §0 与 cron prompt 都明写「冲突不自行解」「不要在集成头上解冲突」；
   (b) 协议允许的自动求并只覆盖**契约/台账**两类文件（`merge-union` 驱动），
       `api-proxy.ts` / backlog 文档 / cron prompt 都不在其列；
   (c) 剩下那 1 个真双向冲突（`MAIN_MERGE_CRON_PROMPT.md`）**本轮无论如何必须人工求并**，
       所以「机械解 2 个 + 留 1 个人工」并不构成一个可完成的本轮。
   ⇒ 把 `merge-resolve.mjs` 当**只读证据来源**用，是它在本轮的最大价值。

3. **第 18/19/20 轮的证据要不要直接继承** ⇒ 不要，全部重新取证。
   协议第 8 行明写「所有判断必须从仓库现状重新取证，不能凭记忆」。
   本轮重新独立证了：fetch 后的 main tip、merge-base、三阶段 blob 身份、
   冲突区行号与数量、路由 token 对称差、树差异分类、台账自检、闸状态。
   **新增的是「用 main 侧自带判定器独立复现冲突定性」这条**。

4. **跑不跑预检** ⇒ 跑（与前 3 轮不同）。
   前 3 轮没有要 push 的东西，所以不跑有理由。本轮要推一个提交，
   「预检绿才 push」就应当覆盖它；且集成头当前编译/类型全绿，
   对下一轮流水线是有效基线。

5. **第 21 轮了还在撞同一堵墙，继续撞有没有意义** ⇒ 意义在**取证链**，不在并入。
   本轮的新增价值是：证明了「第 20 轮的冲突定性可被 main 自带工具独立复现」，
   并且把「**这堵墙不可能由本任务自己解开**」这件事从推断变成了**有工具背书的结论**。
   ⇒ 下一步不在「再等一轮 main 推进」，而在**人工解掉那 1 个真双向冲突**，
   或**重切「集成头 → main」的 PR**（见「后续待办」）。

## 涉及文件

- 冲突文件（本轮未改动，已 abort）：
  - `apps/web/src/lib/api-proxy.ts`（9 个冲突区）
  - `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md`（1 个冲突区）
  - `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`（3 个冲突区）
- 本轮新增（唯一提交）：`docs/sessions/2026-10/2026-10-22-0700-main-sync-conflict-same-as-round20.md`
- 合并中已由 `merge-union` 干净求并但**随 abort 丢弃**的产物（未进入最终树）：
  `docs/product/capability-blueprint/module-progress.md`、
  `packages/api-contracts/src/{catalog,channel,trade,warehouse}.openapi.json`、
  `packages/api-contracts/src/contracts/{catalog,channel,trade,warehouse}.ts`

## 验证结果

- **闸**：`gate status` 起手 ⇒ `held / stale:false`（age 832s，闸外 `ps` 见 `mvnw verify` pid 25506）；
  密集竞态（1 秒一次），**第 2299 次 attempt 于 06:56:20 取得**；**收尾已 release**。
- **fetch**：`git fetch origin --prune` ⇒ `origin/main` tip = `a105acf0`（2026-10-22 02:42:58 +0800），
  领先集成头 **26 个提交**，`merge-base` = `1bb270a0`（与第 18/19/20 轮相同）。
- **merge**：`git merge --no-ff -F <msg> origin/main` ⇒ **3 文件冲突（13 区）**；
  `git merge --abort` ⇒ 退出码 0，`MERGE_HEAD` 已清，工作区 `git status --porcelain` 为空，
  HEAD 回到 `a3596a9b`。
- **远端**：`origin/integration/module-dev` == `a3596a9b` == 本地 HEAD（合入前）⇒ **未推送任何合并内容**。
- **台账**：`sync.mjs --check` ⇒ ✓ 与蓝图一致（465 个模块行，编号零重复）；308 / 0 / 157（66.2%）。
- **预检**：`backend/./mvnw -q compile` ⇒ **exit 0**；`pnpm typecheck` ⇒ **3 successful / 3 total**。
- **业务代码**：本轮零改动；**未跑** `mvnw verify`（属流水线 L2）。

## 取证

### 一、闸：等 40 分钟，竞态拿到

```
$ node tools/module-progress/lane.mjs gate status          # 起手
{  "gate": "held",  "ageSeconds": 832,  "ttlSeconds": 4200,  "stale": false }
```

`stale: false` ⇒ 持闸者活着。**闸外取证**（协议说活性要看闸外）：

```
$ ps -eo pid,etime,command | grep -Ei 'classworlds|surefirebooter'
25506 13:59  ... org.codehaus.plexus.classworlds.launcher.Launcher verify
28169 07:30  ... services/channel/target/surefire/surefirebooter-...
```

⇒ 同 worktree 里正在跑 `mvnw verify`（L2），**这就是活着的 tick**。
整个等闸期间 surefire PID 从 `28169/28170`（channel）→ `30774/30779` → `37104/37105`（trade）
连续更换，且 `backend/services/trade/target/surefire-reports/` 持续新增报告文件
⇒ **构建在推进，不是卡死**。

竞态：`/tmp/starcon-gate-race.sh` 每秒一次 `gate acquire`，
**第 2299 次 attempt 于 06:56:20 拿到**（`{"ok": true, "gate": "acquired"}`）。
拿闸后立刻停掉第二个竞态进程，避免与后面的构建抢 CPU。

⚠️ `owner` 文件内容是**旧值**（pid 89597 / 05:01:05），**不能**当存活证据（协议第 85–88 行已明写）。

### 二、main 自第 18 轮以来仍然零推进

```
$ git fetch origin --prune
$ git log -1 --format='%H%n%ci' origin/main
a105acf013bda16ca36d6fdc7c120a7318ea0a60
2026-10-22 02:42:58 +0800
```

第 18（04:04）/ 19（04:20）/ 20（06:53）三轮跑时 main tip 已是 `a105acf0` ⇒ **连续 4 轮零推进**。
⇒ 冲突面逐字节复现是必然的。

### 三、冲突面：3 文件 13 区（与第 20 轮逐字节相同）

```
$ git diff --name-only --diff-filter=U
apps/web/src/lib/api-proxy.ts
docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md
tools/module-progress/MAIN_MERGE_CRON_PROMPT.md

$ for f in $(git diff --name-only --diff-filter=U); do grep -c '^<<<<<<<' "$f"; done
9 / 1 / 3
```

冲突区起始行号 —— `api-proxy.ts`：`571 / 719 / 2550 / 4478 / 5204 / 5617 / 5725 / 6018 / 6916`；
`flaky-integration-tests-l2-false-red.md`：`394`；
`MAIN_MERGE_CRON_PROMPT.md`：`152 / 184 / 220`。
⇒ 与第 20 轮**逐行相同**。

**blob 级证明（本轮重新独立证）**：

```
$ git ls-files -u
100644 2bcc22a8… 1  apps/web/src/lib/api-proxy.ts
100644 87253024… 2  apps/web/src/lib/api-proxy.ts
100644 7f844ce6… 3  apps/web/src/lib/api-proxy.ts
100644 2d59bd2b… 1  docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md
100644 d13bd140… 2  docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md
100644 26fb4553… 3  docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md
100644 2a52b3f5… 1  tools/module-progress/MAIN_MERGE_CRON_PROMPT.md
100644 f644fdf9… 2  tools/module-progress/MAIN_MERGE_CRON_PROMPT.md
100644 4c77f6f3… 3  tools/module-progress/MAIN_MERGE_CRON_PROMPT.md
```

对每个 stage3（main 侧）blob，回查集成头该路径的历史里是否有**同一 blob**、且该提交是集成头的线性祖先：

```
api-proxy.ts          main blob 7f844ce6 → 命中集成头提交 976d7d42 → 是线性祖先 ⇒ 单边，取 ours 零丢失
flaky-integration...  main blob 26fb4553 → 命中集成头提交 98de97fd → 是线性祖先 ⇒ 单边，取 ours 零丢失
MAIN_MERGE_CRON...    main blob 4c77f6f3 → 未命中 ⇒ 真双向，必须人工求并
```

⇒ 与第 20 轮的三个哈希**逐个相同** ⇒ 冲突面逐字节相同。

### 四、main 零业务新增：三路独立取证

**① 白名单路由 token 对称差**（从 stage2 / stage3 两个 blob 抽 `pattern:` 正则的顶层字面量 token）：

```
ours (集成头)   token 数 71
theirs (main)   token 数 70
只属于 main 的 token = 0        ← main 没有任何一条集成头没有的路由
只属于集成头的 token = 1        ← /influencers（14.5.1.1 达人库）
```

**② 树差异分类**（tip 对 tip，181 个文件 = 145 D / 23 A / 13 M）：

| 方向                | 数量 | 内容                                                                                                            |
| ------------------- | ---- | --------------------------------------------------------------------------------------------------------------- |
| 集成头有、main 没有 | 145  | 广告车道代码（`backend/services/channel/**`、`apps/web/src/**`）+ 会话文档                                        |
| main 有、集成头没有 | 23   | **14** 个 `docs/sessions/**` + **8** 个 `tools/progress-site/**` + **1** 个 `docs/backlog/**` ⇒ 落在 `backend/` 或 `apps/` 下的 = **0** |
| 两侧都有、内容不同  | 13   | `.gitignore`、`api-proxy.ts`、台账、7 份契约、`MAIN_MERGE_CRON_PROMPT.md`、flaky-integration-tests backlog           |

**③ 台账模块级比对**（用 main 侧自带 `merge-summary.mjs` 反向跑 `origin/main → integration/module-dev`）：

```
新增交付 11 项：
  [14 广告与增长系统]
    活动详情 14.1.1.2 / 批量操作 14.1.1.3 / 广告组管理 14.1.2.1 / 投放定位 14.1.3.2 /
    预算分配 14.2.2.1 / 活动报表 14.3.1.1 / 广告组与词报表 14.3.1.2 / 效率诊断 14.3.2.2 /
    促销列表 14.4.1.1 / 优惠券与秒杀 14.4.1.2 / 达人库 14.5.1.1
```

⇒ **集成头比 main 多 11 个已交付模块，main 领先 0。**

⇒ **main 侧没有任何集成头不具备的业务内容。**

### 五、【新增】main 自带的解冲突判定器独立复现了本任务结论

`tools/module-progress/merge-resolve.mjs`（main 侧新增，276 行 + `merge-resolve.itest.mjs` 142 行）
与 `tools/module-progress/merge-summary.mjs`（233 行 + `merge-summary.itest.mjs` 206 行）
是 main 本轮带来的新工具，**只读**（用 `git merge-tree --write-tree` 在内存里算冲突）。

本轮把 main 侧 blob 取出到 `/tmp` 直接跑（**不落进仓库、不改工作区**）：

```
$ node merge-resolve.mjs --base origin/main --head integration/module-dev
[取集成分支侧] apps/web/src/lib/api-proxy.ts
    累积型三件套：集合包含 ✓ · 有序子序列 ✓ · 零删除 ✓（条目 125 → 136）
[取集成分支侧] docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md
[并入（人工）] tools/module-progress/MAIN_MERGE_CRON_PROMPT.md
结论：1 个文件必须人工并入，其余按上面标注机械执行。   （退出码 1）
```

⚠️ 跑法注意：两个工具都用 `import.meta.url` 推导仓库根目录，
放在 `/tmp` 跑会因 ROOT 解析到 `/tmp` 而读不到 git ref；
放进 `<repo>/tools/module-progress/` 跑才正确（跑完已删除，工作区仍干净）。

⇒ **两个独立方法（我自己的 blob 级证明 vs main 自带判定器）结论完全一致**，
且都指向同一个硬结论：**剩下 1 个文件必须人工求并，本轮不可能在协议内完成并入。**

### 六、台账

```
模块总数 465 / 已交付 308 / 进行中 0 / 未开发 157 / 完成率 66.2%
$ node tools/module-progress/sync.mjs --check
✓ 台账与蓝图一致
```

与第 20 轮**完全相同**（本轮 net 业务变更 0）。

### 七、预检

```
$ cd backend && ./mvnw -q compile
（仅 JVM native-access / sun.misc.Unsafe 警告）exit 0
$ pnpm typecheck
Tasks: 3 successful, 3 total
```

## 后续待办

1. **（需有授权的人/调度器）解 `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` 冲突** ——
   这是**唯一的真双向冲突**，main 独有的「落地树哈希」判据段要保留，集成头侧也要保留。
   **其余 2 个冲突不要手写 alternation 求并**，直接取集成头侧即可
   （blob 级证明 + `merge-resolve.mjs` 的累积型三件套双背书）。
2. **解完冲突后必须重跑 `node tools/module-progress/sync.mjs` 再提交台账** ——
   合并驱动打印过 `merge-union: 走了行级兜底并集` 与
   `merge-union: 台账按模块 ID 求并集，折叠了 465 行同 ID 记录`，
   解完不重跑会让 `sync.mjs --check` 退出码 1
   （详见 backlog `docs/backlog/2026-10-20-merge-union-ledger-markdown-structure-collapse.md`）。
3. **把 main 侧的 `merge-resolve.mjs` / `merge-summary.mjs` 优先并进集成头** ——
   这两个工具正是为解这堵墙而生，且已自带 itest。
   ⇒ 建议下一轮同步时**先单独并入这两个工具**（它们是 main 独有文件，无冲突），
   后续每一轮同步就能**自动**拿到「冲突解法判定器」，不必再手工做 blob 级取证。
4. **真正的缺口在反方向** —— `main` 落后集成头 **11 个 14.x 广告/增长模块**，
   需要重切一次「集成头 → main」的 PR（走 `MAIN_MERGE_CRON_PROMPT.md` 那套流程），
   而不是继续把 main 往集成头同步。
5. **本同步任务在 main 零推进时无收益** —— 第 18/19/20/21 轮连续 4 轮撞同一堵墙，
   main tip 四轮都是 `a105acf0`。若下一轮 main 仍未推进，建议直接跳过本任务，
   或先确认反方向 PR 的进展。
6. **「流水线自己制造新冲突」这个模式仍成立** ——
   流水线改**共享文件**（台账、契约、backlog、api-proxy）时，
   要意识到下一轮 main→集成头同步会多一个冲突面。
7. **闸的交接窗口实测比协议描述的还窄** —— 上一 tick 06:51:45 释放并被下一 tick 同秒接走；
   本轮 1 秒一次的竞态密度是对的，低频 `status` 观测大概率会错过。

## 关联

- 同步协议：`tools/module-progress/SYNC-MAIN.md`
- 反向合并协议：`tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`（本轮唯一真双向冲突文件）
- 功能进度台账：`docs/product/capability-blueprint/module-progress.md`
- 已知缺陷：`docs/backlog/2026-10-20-merge-union-ledger-markdown-structure-collapse.md`、
  `docs/backlog/2026-10/2026-10-21-api-proxy-shared-file-merge-conflict.md`、
  `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md`（§八）
- 上一轮：`docs/sessions/2026-10/2026-10-22-0653-main-sync-conflict-3-files-round20.md`
- 再上一轮：`docs/sessions/2026-10/2026-10-22-0420-main-sync-conflict-2-files-round19.md`
