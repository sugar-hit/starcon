# 2026-10-22 06:53 集成头同步 main（第 20 轮 · 冲突未并入 · 冲突面扩到 3 文件 13 区 · 新增一个单边冲突）

## 摘要

「main → 集成头 `integration/module-dev`」同步任务第 20 轮。

**结果：冲突未并入，未推送任何合并内容，集成头 HEAD 保持 `f2c8f5bc` 不变。**
四选一里的「**冲突未并入**」。

本轮与第 18 / 19 轮同属一堵墙，但**冲突面变大了**，且结论有两条是新增的：

1. **冲突面从 2 文件 10 区扩到 3 文件 13 区**。新增的第三个文件
   `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md`（1 区）
   是**流水线 batch-60 本轮自己制造的新冲突** —— 集成头在本轮 L2 失败排查中改了这个文件，
   而 main 侧也带着同一文件的旧版本 ⇒ 两边都改过同一文件 ⇒ 必冲突。
   `api-proxy.ts` 的冲突区也从 7 区涨到 **9 区**（batch-60 又并入了 3 条广告车道，
   每条往同一个 `services[]` 插入锚点）。
2. **3 个冲突里，2 个是「单边可证零丢失」**（blob 级证明），
   只有 1 个是**真双向**、必须人工求并 —— 比第 19 轮（2 个冲突里 1 个真双向）
   **更好解**。新增的那个冲突恰好是单边的。

`origin/main` 自第 18 轮（04:04）以来**仍然零推进**：tip 依旧是 `a105acf0`（02:42:58）。

## 决策 / 结论

1. **闸：等了 96 分钟才拿到，没抢、没清、没 force。**
   本轮开跑时闸被一个**活着的流水线 tick（batch-60）**持有
   （`gate status` ⇒ `held / stale:false`；闸外取证：同 worktree 里 `mvnw verify` 在跑，pid 92647）。
   按协议**密集竞态 `gate acquire`**（1 秒一次），跑了 3 轮竞态循环
   （第 1 轮 900 次、第 2 轮 2400 次用尽预算未拿到、第 3 轮在**第 2084 次**拿到），
   于 **06:51:45** 取得闸。等闸期间**只做只读取证**，没跑第二个构建（避免与 L2 抢
   `backend/**/target/` 与 PG 容器造成假红），连 `git fetch` 都压到拿闸之后才跑。

2. **冲突只报不解**（协议 §0 + 第 3 步）。`git merge --abort` 退出码 0，
   `MERGE_HEAD` 已清，工作区 `git status --porcelain` 为空，HEAD 回到 `f2c8f5bc`，
   **未 push 任何合并内容**。

3. **本轮冲突面 = 3 个文件、13 个冲突区**，逐个定性如下：

   | 文件                                                              | 冲突区                       | 性质       | 本轮取证结论                                                                                                                                                                            |
   | ----------------------------------------------------------------- | ---------------------------- | ---------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
   | `apps/web/src/lib/api-proxy.ts`                                   | **9**（行 571/719/2550/4478/5204/5617/5725/6018/6916） | 单边       | main 的 blob `7f844ce6` == 集成头线性祖先 `976d7d42` 的 blob ⇒ **取集成头侧可证零丢失**；路由 token 对称差：**只属于 main = 0**，只属于集成头 = 2（`/bid-factor`、`/sync`） |
   | `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md` | **1**（行 394）              | 单边       | main 的 blob `26fb4553` == 集成头线性祖先 `98de97fd` 的 blob ⇒ **取集成头侧可证零丢失**（main 侧没有 §八「批次 60 并发车道删容器导致 L2 假红」那 65 行）    |
   | `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`                 | **3**（行 152/184/220）      | **真双向** | main 的 blob `4c77f6f3` **不在**集成头历史里 ⇒ main 独有的 44 行「落地树哈希」判据段集成头侧没有 ⇒ **单边取任一侧都丢内容，必须人工求并**                    |

   ⇒ **本轮比第 19 轮更好解**：3 个冲突里 2 个可证零丢失（取集成头侧），
   只有 `MAIN_MERGE_CRON_PROMPT.md` 需要人工求并。

4. **`api-proxy.ts` 的冲突区从 7 涨到 9，是「累积型白名单 + 双向叠加」的必然结果**：
   batch-60 又并入 3 条广告车道（`14.1.3.2` / `14.4.1.2` / `14.5.1.1`），
   每条往同一个 `services[]` 数组新增一条独立元素；main 侧那个 blob 又是集成头早期的快照
   ⇒ 双方改的是**同一批插入锚点** ⇒ 必冲突。**retry 不会自愈**。

5. **main 相对集成头依然零业务新增**（本轮三路重新取证）：

   | 维度             | 方法                                          | 结论                                                                                                                    |
   | ---------------- | --------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
   | 白名单路由 token | `api-proxy.ts` 两侧全文抽 path token 求对称差 | **只属于 main = 0**，只属于集成头 = **2**（`/bid-factor`、`/sync`）                                                        |
   | 树差异「A」侧    | `git diff --name-status HEAD origin/main`     | **23** 个「main 有、集成头没有」= 14 个 `docs/sessions/**` + 8 个 `tools/progress-site/**` + 1 个 `docs/backlog/**`；**落在 `backend/` 或 `apps/` 下的 = 0** |
   | 台账模块数       | `sync.mjs` 重新生成                           | 集成头 **308** 已交付 vs main **297** 已交付 ⇒ main 领先 **0**，集成头领先 11 个 14.x 广告/增长车道                                |

   ⇒ **main 侧没有任何集成头不具备的业务内容。** 本轮即使解完冲突并入，净增的也只有
   **14 个会话文档 + 8 个看板工具文件 + 1 个 backlog 文档**，业务代码净增 **0**。

6. **台账数字（本轮零变化）**：已交付 **308** / 进行中 **0** / 未开发 **157**（465 个模块，完成率 66.2%）。
   与第 19 轮记的 305 / 0 / 160 相比 +3，是因为**流水线 batch-60 在本轮并入了 3 个广告车道**
   （`14.1.3.2` / `14.4.1.2` / `14.5.1.1`），**不是 main 带进来的**。
   `node tools/module-progress/sync.mjs --check` ⇒ **✓ 台账与蓝图一致**（merge 已 abort，台账未被改动）。

7. **本轮只提交了本会话文档**（AGENTS.md「每次有结论的会话都要落到 `docs/sessions/`」），
   **业务代码零改动**；文件名带轮次时间戳，main 侧不存在同名路径 ⇒ 不会给下一轮制造 add/add 冲突。

## 关键 trade-off

1. **等 96 分钟 vs 提前退出** ⇒ 等。
   协议第 1 步给的判据是 `gate status` 的 `stale` 字段，实测 `held + stale:false`
   ⇒ 持闸者是活着的 tick（闸外 `ps` 见 `mvnw verify` 在跑），**等它释放本轮就能正常同步**。
   提前退出等于把这一轮白跑，而闸本来也会在 tick 结束时自己释放。

2. **等闸期间先 `git fetch` 行不行** ⇒ 不行。
   协议明写「连 `git fetch` 都先别跑」。虽然 fetch 只动 ref 不碰工作区，
   但同 worktree 里正有 tick 在跑 `mvnw verify` + 提交，**在它持闸期间动 ref 属于越权**，
   而且会让「本轮 main 到了什么」这件事的取证时点与闸的边界对不齐。
   本轮全程把 fetch 压在拿闸之后的第一条命令。

3. **第 18 / 19 轮的证据要不要直接继承** ⇒ 不要，全部重新取证。
   协议第 8 行明写「所有判断必须从仓库现状重新取证，不能凭记忆」。
   本轮重新独立证了：fetch 后的 main tip、merge-base、三阶段的 blob 身份、
   冲突区行号与数量、路由 token 对称差、树差异分类、台账自检、闸状态。
   **新增的是「新增冲突文件的 blob 级证明」和「冲突区从 7 涨到 9」这两条**，
   它们是第 19 轮没有的。

4. **本轮不跑预检** ⇒ 合理。
   预检的作用是给「合并产物」兜底；merge 已 abort，工作区里没有合并产物可检，
   且**业务代码净增 0**。跑 `./mvnw -q compile` + `pnpm typecheck` 只能证明
   「集成头自己没坏」，与本轮「main 的东西能不能并进来」无关。
   （另外闸外那轮 L2 本来就红在 `trade` 的 Testcontainers 容器被并发车道误删，
   与本同步任务无关，详见下节。）

5. **不等 main 推进就重复撞同一堵墙** ⇒ 已在「后续待办」里明确建议：
   **真正的缺口在反方向**（main 落后集成头 11 个广告车道），
   需要重切一次「集成头 → main」的 PR，而不是继续把 main 往集成头同步。

## 涉及文件

- 冲突文件（本轮未改动，已 abort）：
  - `apps/web/src/lib/api-proxy.ts`（9 个冲突区）
  - `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md`（1 个冲突区，**本轮新增**）
  - `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`（3 个冲突区）
- 本轮新增（唯一提交）：`docs/sessions/2026-10/2026-10-22-0653-main-sync-conflict-3-files-round20.md`
- 合并中已干净求并但**随 abort 丢弃**的产物（未进入最终树）：
  `docs/product/capability-blueprint/module-progress.md`、
  `packages/api-contracts/src/{catalog,channel,trade,warehouse}.openapi.json`、
  `packages/api-contracts/src/contracts/{catalog,channel,trade,warehouse}.ts`

## 验证结果

- **闸**：`gate status` 起手 ⇒ `held / stale:false`；密集竞态 3 轮，
  第 3 轮第 2084 次 attempt（**06:51:45**）取得；**收尾已 release**。
- **fetch**：`git fetch origin --prune` ⇒ `origin/main` tip = `a105acf0`（2026-10-22 02:42:58 +0800），
  领先集成头 **26 个提交**，`merge-base` = `1bb270a0`（与第 18 / 19 轮相同）。
- **merge**：`git merge --no-ff -F <msg> origin/main` ⇒ **3 文件冲突（13 区）**；
  `git merge --abort` ⇒ 退出码 0，`MERGE_HEAD` 已清，工作区 `git status --porcelain` 为空，
  HEAD 回到 `f2c8f5bc`。
- **远端**：`origin/integration/module-dev` == `f2c8f5bc` == 本地 HEAD（合入前）⇒ **未推送任何合并内容**。
- **台账**：`sync.mjs --check` ⇒ ✓ 与蓝图一致（465 个模块行，编号零重复）。
- **业务代码**：本轮零改动；未跑 `mvnw verify`（属流水线 L2）；未跑预检（merge 已 abort）。

## 取证

### 一、闸：等 96 分钟，竞态拿到

```
$ node tools/module-progress/lane.mjs gate status          # 05:15
{ "gate": "held", "ageSeconds": 249, "ttlSeconds": 4200, "stale": false }
```

`stale: false` ⇒ 持闸者活着。**闸外取证**（协议说活性要看闸外）：

```
$ ps -eo pid,etime,command | grep -Ei 'mvnw|maven|java'
92647  04:17  /usr/bin/java ... org.codehaus.plexus.classworlds.launcher.Launcher verify
```

⇒ 同 worktree 里正在跑 `mvnw verify`（L2），**这就是活着的 tick**。
按协议**密集竞态**（不是低频 `status` 观测）：

| 竞态轮次 | 预算 | 结果                                                        |
| -------- | ---- | ----------------------------------------------------------- |
| 第 1 轮   | 900 次 | 用尽预算未拿到（05:31 结束）                               |
| 第 2 轮   | 2400 次 | 用尽预算未拿到（06:15 结束）                               |
| 第 3 轮   | 3600 次 | **第 2084 次 attempt 拿到，06:51:45**                        |

⚠️ 闸目录里的 `owner` 文件内容是**旧值**（pid 89597 / 05:01:05），
**不能**拿它当存活证据（协议第 85–88 行已明写）；`keepalive-*` 文件才是心跳证据
（05:01:08 / 05:10:56 / 05:22:53 / 05:43:55 / 06:01:16 —— 每 12 分钟一次）。

### 二、main 自第 19 轮以来仍然零推进

```
$ git fetch origin --prune
$ git log -1 --format='%H%n%ci%n%s' origin/main
a105acf013bda16ca36d6fdc7c120a7318ea0a60
2026-10-22 02:42:58 +0800
docs: 修正集成分支并入主分支的新内容判据
```

第 19 轮跑于同日 04:20，当时 main tip 已是 `a105acf0` ⇒ **main 在这之后一个提交都没多**。
⇒ 冲突面复现是必然的；本轮新增的那条冲突来自**集成头侧的新增**，不是 main 动了。

### 三、冲突面：3 文件 13 区

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
（`api-proxy.ts` 的 9 区比第 19 轮的 7 区多 2 区，`MAIN_MERGE_CRON_PROMPT.md` 的 3 区与第 19 轮逐行相同。）

**blob 级证明（三阶段 blob 全取到）**：

```
$ git ls-files -u
stage1 2bcc22a8ff19147489d9d00de74622d2e1770c0f  apps/web/src/lib/api-proxy.ts
stage2 872530249bb93ec322c6173759bc9752ece320d7  apps/web/src/lib/api-proxy.ts
stage3 7f844ce6483b008186aa99c060fd582d98ba2d6e  apps/web/src/lib/api-proxy.ts
stage1 2d59bd2b2ab0fc3562f318b8d7e443ad9da0af73  docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md
stage2 d13bd140b6c727def978ab3edfb36f717aa07729  docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md
stage3 26fb4553c2af0e90aa9defdb95b74e80a9762312  docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md
stage1 2a52b3f58fe2481ae537dd31c751bc0379be6c38  tools/module-progress/MAIN_MERGE_CRON_PROMPT.md
stage2 f644fdf90c347600de309820674583f511dc68ec  tools/module-progress/MAIN_MERGE_CRON_PROMPT.md
stage3 4c77f6f35f01d67044d528b515e5a4c13fbf5ec6  tools/module-progress/MAIN_MERGE_CRON_PROMPT.md
```

对每个 stage3（main 侧）blob，回查集成头历史里是否有**同一 blob**、且该提交是集成头的线性祖先：

```
api-proxy.ts          main blob 7f844ce6 → 命中集成头提交 976d7d42 → 是线性祖先 ⇒ 单边，取 ours 零丢失
flaky-integration...  main blob 26fb4553 → 命中集成头提交 98de97fd → 是线性祖先 ⇒ 单边，取 ours 零丢失
MAIN_MERGE_CRON...    main blob 4c77f6f3 → 未命中 ⇒ 真双向，必须人工求并
```

新增冲突文件的内容差（`git diff HEAD origin/main -- <file>`）：
**65 行纯删除** —— main 侧缺集成头新增的「§八、批次 60：并发车道手动删容器导致 L2 假红」整节。

### 四、main 零业务新增：三路独立取证

**① 白名单路由 token 对称差**：

```
ours (集成头)   抽出 path token 56 个
theirs (main)   抽出 path token 54 个
只属于 main 的 token = 0        ← main 没有任何一条集成头没有的路由
只属于集成头的 token = 2        ← /bid-factor、/sync
```

**② 树差异分类**（tip 对 tip，180 个文件 = 144 D / 23 A / 13 M）：

| 方向                | 数量 | 内容                                                                                                              |
| ------------------- | ---- | ----------------------------------------------------------------------------------------------------------------- |
| 集成头有、main 没有 | 144  | 广告车道代码（`backend/services/channel/**`、`apps/web/src/**`）+ 会话文档                                     |
| main 有、集成头没有 | 23   | **14** 个 `docs/sessions/**` + **8** 个 `tools/progress-site/**` + **1** 个 `docs/backlog/**` ⇒ 落在 `backend/` 或 `apps/` 下的 = **0** |
| 两侧都有、内容不同  | 13   | `.gitignore`、`api-proxy.ts`、台账、7 份契约、`MAIN_MERGE_CRON_PROMPT.md`、flaky-integration-tests backlog       |

**③ 台账模块级比对**：main **297** 已交付 vs 集成头 **308** 已交付 ⇒ main 领先 **0**，
集成头领先 11 个 14.x 广告/增长车道。

⇒ **main 侧没有任何集成头不具备的业务内容。**

### 五、闸外观察：batch-60 的 L2 红了（本同步任务无关，但影响下一轮判断）

本轮等闸期间，持有闸的 batch-60 tick 的 L2 在 `trade` 模块 FAILURE（05:55:39）：
`PSQL Connection to localhost:58039 refused` —— **连接失败，不是断言失败**。
按该 tick 自己的排查结论：并发车道 `14.5.2.1` 的 worker 在收尾时**手写 `docker rm` 删掉了
自己的 PG 容器**，而那一刻 L2 正在跑 trade 的集成测试 ⇒ 容器被误删 ⇒ L2 假红。
该 tick 随后隔离复跑 3 轮全绿，并提交了 `f2c8f5bc`（含 §八 的根因记录）后 push 释放闸。

⇒ 对本同步任务的影响：**零业务影响**（本轮净并入 0 行业务代码），
但**下一轮若撞同样的 3 文件冲突并解完，预检（`mvnw -q compile` + `pnpm typecheck`）不受此影响**
（编译与类型检查不跑 Testcontainers 集成测试）。

### 六、台账塌陷：第 19 轮的实测结论本轮仍然成立

合并驱动在台账上照样打印了告警横幅：

```
merge-union: 走了行级兜底并集，请人工确认这个文件改对了
merge-union: 台账按模块 ID 求并集，折叠了 465 行同 ID 记录
```

⇒ **解完冲突后必须重跑 `node tools/module-progress/sync.mjs` 再提交台账**，
否则提交进去的台账会让 `sync.mjs --check` 退出码 1（详见 backlog
`docs/backlog/2026-10-20-merge-union-ledger-markdown-structure-collapse.md`
与第 19 轮的实测）。

## 后续待办

1. **（需有授权的人/调度器）解 `apps/web/src/lib/api-proxy.ts` 冲突**：取集成头侧即可。
   已有 blob 级证明「零丢失」（main 的 blob `7f844ce6` 是集成头线性祖先 `976d7d42` 的逐字节副本），
   且路由 token 对称差证明**只属于 main 的 token = 0**。**不要**再逐条手写 alternation 求并。
2. **（需有授权的人/调度器）解 `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md` 冲突**：
   取集成头侧即可。同样有 blob 级证明（main 的 blob `26fb4553` 是集成头线性祖先 `98de97fd` 的逐字节副本），
   main 侧缺的正是 §八 那 65 行根因记录。
3. **（需有授权的人/调度器）解 `MAIN_MERGE_CRON_PROMPT.md` 冲突**：这是**真双向**，
   必须人工求并 —— main 独有的 44 行「落地树哈希」判据段要保留，集成头侧也要保留。
4. **解完冲突后必须重跑 `node tools/module-progress/sync.mjs` 再提交台账**（见「取证六」）。
5. **注意「流水线自己制造新冲突」这个模式**：batch-60 改 `flaky-integration-tests-l2-false-red.md`
   时，main 侧也带着同一文件的旧版本 ⇒ 立刻变成一个冲突。
   ⇒ 流水线改**共享文件**（台账、契约、backlog、api-proxy）时，
   要意识到下一轮 main→集成头同步会多一个冲突面。
6. **真正的缺口在反方向** —— `main` 落后集成头 **11 个 14.x 广告/增长车道模块**，
   需要重切一次「集成头 → main」的 PR（走 `MAIN_MERGE_CRON_PROMPT.md` 那套流程），
   而不是继续把 main 往集成头同步。
7. **本同步任务在 main 零推进时无收益**：第 18、19、20 三轮连续撞同一堵墙，
   main tip 三轮都是 `a105acf0`。若下一轮 main 仍未推进，建议直接跳过本任务，
   或先确认反方向 PR 的进展。
8. **闸的交接窗口实测比协议描述的还窄**：本轮第 3 轮竞态共 2084 次才拿到，
   而整轮 tick 从 05:10 到 06:51 持闸约 41 分钟 ⇒ 竞态密度（1 秒一次）是对的，
   低频 `status` 观测大概率会错过。

## 关联

- 同步协议：`tools/module-progress/SYNC-MAIN.md`
- 反向合并协议：`tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`
- 功能进度台账：`docs/product/capability-blueprint/module-progress.md`
- 已知缺陷：`docs/backlog/2026-10-20-merge-union-ledger-markdown-structure-collapse.md`、
  `docs/backlog/2026-10/2026-10-21-api-proxy-shared-file-merge-conflict.md`、
  `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md`（§八）
- 上一轮：`docs/sessions/2026-10/2026-10-22-0420-main-sync-conflict-2-files-round19.md`
- 再上一轮：`docs/sessions/2026-10/2026-10-22-0404-main-sync-conflict-2-files-round18.md`
