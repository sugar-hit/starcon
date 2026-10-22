# 2026-10-22 13:02 集成头同步 main（第 25 轮 · 冲突未并入 · 3 文件 11 区 · 与第 24 轮逐区同墙）

## 摘要

「main → 集成头 `integration/module-dev`」同步任务第 25 轮。

**结果：冲突未并入，未推送任何合并内容。** 四选一里的「**冲突未并入**」。
集成头 HEAD 保持 `86711ff7` 不变（合并已回退，`MERGE_HEAD` 已清，工作区干净）。

与第 24 轮相比，`origin/main` tip 未推进（仍是 `89395bc8`，2026-10-22 12:17:28），
`merge-base` 未变（仍是 `1bb270a0`），**冲突面逐区同墙**：

| 维度                | 第 24 轮（12:47）                                   | 第 25 轮（13:02）  | 结论         |
| ------------------- | --------------------------------------------------- | ------------------ | ------------ |
| `origin/main` tip   | `89395bc8`                                          | `89395bc8`         | 未推进       |
| `merge-base`        | `1bb270a0`                                          | `1bb270a0`         | 未变         |
| 领先集成头的提交数  | 32                                                  | 32                 | 未变         |
| 冲突文件数 / 冲突区 | 3 文件 / 11 区                                      | **3 文件 / 11 区** | **逐区同墙** |
| 冲突文件构成        | api-proxy / contract-drift / MAIN_MERGE_CRON_PROMPT | 同左               | 未变         |

⇒ **在 `origin/main` 不推进的前提下，冲突面是稳定的**（前几轮的「此消彼长」来自两侧各自改动，
本轮 main 没动 ⇒ 集成头侧的两笔新提交（`4d85131f`、`86711ff7`，都在 `docs/sessions/`）
与 main 侧无交集，冲突区一行未变）。

## 决策 / 结论

1. **闸：等了 39 分 32 秒才拿到，一次拿到，没抢、没清、没 force。**
   起手 `gate status` ⇒ `held / stale:false` ⇒ 协议 §1 的「密集 acquire 竞态」循环（1 秒密度）。
   期间持闸 tick 连续跑了 batch-62 的 L2（`l2-run-batch-62-rerun2.log`，
   `mvnw verify` 跑了 **43 分 35 秒**、7 个模块全 SUCCESS；`pnpm typecheck/lint/test` 全 exit 0、
   6810 个用例全过；Next production build exit 0；`sync --check` 通过 ⇒ **L2 GREEN**，12:43:17 结束）。
   闸在 **12:57:21** 被热循环抢到（`owner` = 22644）。
   ⇒ 等闸期间**只做只读取证**（`gate status` / `cat owner` / `ps` / `git log` / `ls keepalive-*`），
   **没跑第二个构建、连 `git fetch` 都等到拿到闸之后才跑**（协议 §1 硬要求）。

2. ⚠️ **本轮实测到协议 §1 已被第 24 轮记录、但本轮再次复现的一个坑**：
   持闸 tick 在 L2 全绿后于 12:46:22 释放，**但下一 tick 几乎同时重新 acquire**
   （`gate status` 的 `ageSeconds` 观测到 6 → 21 → 34 → 47 → … 连续重置），
   闸目录里的 `keepalive-*` 文件被清空到只剩 `owner`。
   ⇒ 「闸空了」与「闸被下一 tick 立刻接走」在 `gate status` 上**长得一样**，
   **只有 `gate acquire` 的实际返回能区分**。这与第 24 轮记录的
   「`gate status` 单次报 `free` 是 `statSync` 瞬时假阴性」是**同一个坑的两面**。

3. ⚠️ **本轮新发现（协议里的 `git merge --abort` 在本仓库不可靠）** —— 见文末「新增 backlog」。
   `git merge --abort` **退出码 128**：
   `error: Entry 'docs/product/capability-blueprint/module-progress.md' not uptodate. Cannot merge.`
   根因：`merge-union` 驱动器在合并过程中**既写工作区又写 index**，
   该文件因此是 `MM` 状态（staged + 工作区又被改），git 拒绝覆盖 ⇒ **abort 失败、`MERGE_HEAD` 残留**。
   这正是协议 §3 警告的「`MERGE_HEAD` 残留会堵死流水线的 merge-ready」。
   ⇒ 改用 `git reset --hard HEAD` 收尾，`MERGE_HEAD` 已清、`git status --porcelain` 为空、
   `git grep` 无冲突标记、HEAD 回到 `86711ff7`（== 本轮 PRE）、**未 push 任何合并内容**。

4. **本轮冲突面 = 3 个文件、11 个冲突区**，与第 24 轮**逐区同墙**（连行号都一样）：

   | 文件                                                | 冲突区                                   | 性质        | 本轮取证结论                                                                                                                                    |
   | --------------------------------------------------- | ---------------------------------------- | ----------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
   | `apps/web/src/lib/api-proxy.ts`                     | **6**（行 699/4178/5566/5752/5878/7526） | 单边可机械  | main 侧 blob `9325e4d3` **在集成头历史里逐字节可达**（9 个改过该路径的提交中，ancestorIndex **8**）⇒ 线性演进，可单边求并                       |
   | `docs/backlog/2026-10-20-contract-drift-ci-gate.md` | **1**（行 84）                           | **add/add** | 共同祖先里**没有**这个文件（两侧各自新增）；main 侧 blob `3235b724` **可达**（ancestorIndex **2**）⇒ 集成头侧是超集（**117 行 vs main 83 行**） |
   | `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`   | **4**（行 152/184/220/699）              | **真双向**  | main 侧 blob `881a55eb` **不可达**（5 个改过该路径的提交里一次都没出现）⇒ main 有集成头从未有过的内容，**单边取任一侧都丢东西，必须人工求并**   |

   ⚠️ `MAIN_MERGE_CRON_PROMPT.md` 两侧体量差很大：集成头侧 **621 行** vs main 侧 **808 行**，
   main 多出的 **187 行** 在集成头历史里找不到对应版本 ⇒ 这是「真双向」而不是「集成头是超集」的根因。

5. **api-proxy.ts 的结构性根因（沿用 P0 backlog，未变）**：
   `.gitattributes` 只给 `packages/api-contracts/src/**/*.ts`、
   `packages/api-contracts/src/*.openapi.json`、`docs/product/capability-blueprint/module-progress.md`
   配了 `merge=contract-union`，
   **`apps/web/src/lib/api-proxy.ts` 与 `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` 都不在名单里**
   ⇒ 走普通 git 三方合并 ⇒ 必然撞。
   `api-proxy.ts` 的 `services[]` 元素数：**集成头 191 条 vs main 185 条**（共享白名单累积文件）。
   ⇒ 见 `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md`。

6. **合并驱动 `merge-union` 的自动求并部分全部成功**（本轮再次验证幂等）：
   - `packages/api-contracts/src/contracts/*.ts` ⇒ 按导出类型名求并，
     导出声明数：catalog **863**、warehouse **1077**、channel **1348**、crm **159**、trade **1717**；
     catalog 侧**重复导出名 = 0**（无重复、无丢失）；
   - `catalog.openapi.json` / `warehouse.openapi.json` ⇒ 走**行级兜底并集**
     （驱动自报 `merge-union: 走了行级兜底并集，请人工确认这个文件改对了`）；
   - `docs/product/capability-blueprint/module-progress.md` ⇒ 按模块 ID 求并，折叠 465 行同 ID 记录
     （**同样出现汇总块重复**：集成头的 316/149 汇总与 main 的 312/153 汇总各留一份
     ⇒ 印证 `12cbd407` 那笔「台账必须靠 `sync.mjs` 重新生成来归一」的结论）。
     ⇒ 与第 24 轮的 863 / 1077 **完全一致** ⇒ 求并驱动**幂等**，无回归。
     ⚠️ 合并已 abort ⇒ 这些自动求并结果**没有进入集成头**。

7. ⚠️ **第 24 轮「main 相对集成头零业务代码增量」的结论，本轮需要修正**：
   第 24 轮实测「落在 `backend/` 或 `apps/` 下的差异 = **0**」，
   **本轮实测 = 2 个文件**（`apps/web/src/lib/api-proxy.ts` 本就在冲突清单里），
   多出来的是：

   ```
   M  backend/services/warehouse/src/test/java/com/starcon/warehouse/InTransitTrackingIntegrationTest.java
   ```

   ⇒ 但**方向是集成头领先、main 落后**，不是 main 有新东西：
   集成头侧多出 `import java.time.temporal.ChronoUnit`、
   `@AfterEach restoreClockSource()`（回归用例后还原钉死的时钟）
   以及 `WALL_CLOCK` 时钟源夹具 —— 这正是 `77bc832b`
   「warehouse 墙钟缺陷已闭环」那笔修复，**main 上还没有**。
   ⇒ 结论应表述为「**main 相对集成头依然零业务增量（方向：集成头领先）**」，
   而不是「两侧 backend/ 完全一致」。

8. **台账未变化**：合并未落地 ⇒ **未跑 `sync.mjs`** ⇒ 台账仍为
   **已交付 316 / 进行中 0 / 未开发 149**（合计 465）。
   （第 24 轮记录的「集成头 316 vs main 312」在本轮同样成立：集成头比 main 多 4 个已交付模块。）

9. **本轮未跑预检**：合并已 abort，**不存在「已合并态」可预检**。
   协议 §4 的 `mvnw -q compile` + `pnpm typecheck` 只在**合并落地后**才有意义；
   在未合并的集成头上跑只能证明集成头自身健康、不能证明合并可编译（第 24 轮已同此结论）。

## 新增 backlog

- `docs/backlog/2026-10-22-merge-abort-fails-on-merge-union-ledger-file.md`
  —— **`git merge --abort` 在本仓库会因 `merge-union` 改写台账文件而失败（exit 128），
  `MERGE_HEAD` 残留会堵死流水线的 `merge-ready`**。协议 §3 的字面命令在本仓库**不够用**，
  需改成「`git reset --hard HEAD` 兜底 + 显式校验 `MERGE_HEAD` 已清」。

## 后续待办

1. `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` 是**唯一的真双向冲突**，必须人工求并
   （main 多出 187 行，集成头历史里没有对应版本）⇒ 建议下一轮派一条**只做人工求并**的修复车道，
   或把该文件纳入 `merge-union` 的按「章节标题」求并。
2. `api-proxy.ts` 与 `contract-drift-ci-gate.md` 两处**单边可机械求并**，
   与 P0 backlog `2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md` 的方案 ① 一致
   （先派修复车道把 `api-proxy.ts` 解成逐字相同的基准版本）。
3. `origin/main` 下一个 tip 出现后，冲突面**大概率再次变化**（前几轮已两次证伪「单调增长」）。
   ⇒ 下一轮仍须**重新取证**，不能沿用本轮的 3 文件 / 11 区结论。
