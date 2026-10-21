# 2026-10-21: main 同步第 8 轮 —— 仍撞 3 文件冲突（main 侧无任何新交付）

## 摘要

「main → module-dev 集成头同步」任务第 8 轮。本轮与 11:27 轮（`mvs_2f63f7da`）**撞同一堵墙**：
`origin/main` 的 tip 仍是 `ec225641` + `d28188d6`，merge-base 仍被拉回 `1bb270a0`，
`git merge --no-ff origin/main` 仍以冲突告终。

本轮的**独立增量**（不采信 11:27 轮的历史会话日志，全部重新取证）：

1. **冲突面从 2 文件变 3 文件** —— 新增的第 3 个是 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`
   （add/add）。原因：11:27 轮把这个 backlog 文档**提交进了集成头**（`f0940993` + `053ee1f0`），
   而 main 侧也有一份 6 轮版本（`d28188d6` 带来的旧稿）⇒ 两侧各自新增 ⇒ add/add。
   ⇒ **11:27 轮自己的提交，反过来给下一轮多加了一个冲突文件。**
2. **闸情为本仓史上最恶劣的一次**：闸自 08:03:59 起被 08:00 轮流水线 tick 持有，
   经一个**脱离父进程的 `keepalive.sh`** 每 10 分钟新建 `keepalive-<HHMMSS>` 文件续期，
   **`stale` 全程为 false ⇒ TTL 接管路径被彻底堵死**，闸只能被显式 release 释放。
   本轮等闸 **约 2 小时 25 分钟**（10:17 → 11:39），期间有 **至少 4 个并发竞态循环**在抢同一个闸。
3. **本轮在 11:30:24 拿到过一次闸，又在 11:33:52 被并发轮次的 `gate release` 抹掉** ——
   闸目录被 `rmSync` 后，另一个竞态循环同秒抢回 ⇒ 正好命中 backlog 记录的 P0「release 后被秒抢」向量。
   本轮处理：**重新确认无活跃竞态循环后再取闸**，并在**确认无竞态循环存活**的前提下才 release。

**结果：冲突未并入，未 push 集成头。**

## 决策 / 结论

1. **冲突只报不解**（协议 `SYNC-MAIN.md` §0 硬规定）。本轮 `git merge --abort`，
   `MERGE_HEAD` 已清，工作区干净，HEAD 停在 `053ee1f0`，**未 push**。
2. **本轮 3 个冲突文件的结构（base / ours / theirs 行数 + main 侧独有内容）**：

   | 冲突文件 | 类型 | base / ours / theirs | main 侧独有内容 | 判定 |
   | --- | --- | --- | --- | --- |
   | `apps/web/src/lib/api-proxy.ts` | content | 2634 / 5359 / 4967 | **0 行** | ours 是**严格超集**（numstat `0 392`，零插入零独有）；7 个 hunk 全是「main 侧缺整块白名单条目」 |
   | `backend/services/catalog/src/test/java/com/starcon/catalog/PriceHistoryIntegrationTest.java` | add/add | — / 812 / 769 | 28 行 | main 侧 28 行是**旧写法**：`OffsetDateTime.now().minusDays(10)` 墙钟基准；ours 是 5.5.1.1 修复后的 `a1At/a2At` **DB 时钟锚定**写法（`dayStart` / `atOffset(ZoneOffset.UTC)`）⇒ main 侧是**已被推翻的旧断言** |
   | `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` | add/add | — / 267 / 178 | 15 行 | ours 是 7 轮版本，main 是 6 轮版本 ⇒ **纯文档版本差**，两边都不是对方的新交付 |

   ⇒ **三个文件没有一处是 main 侧的新代码交付。** `api-proxy.ts` 严格超集、
   `PriceHistoryIntegrationTest.java` 是旧断言、backlog 文档是旧版本稿。
   ⇒ 与 11:27 轮的判断同向，但**文件名单从 2 个变成 3 个**（第 3 个是 11:27 轮自己的提交带来的）。

3. **`merge-union` 驱动按预期工作**：台账 `module-progress.md` 按模块 ID 求并集折叠 465 行同 ID 记录（**无冲突**）；
   `packages/api-contracts/**` 8 个文件全部干净合并；1 个文件走了「行级兜底并集」并打印人工确认提示。
4. **台账数字（merge abort 后，集成头 `053ee1f0`）**：已交付 **273** / 进行中 **0** / 未开发 **192**（465 个模块）。
   `node tools/module-progress/sync.mjs --check` ⇒ **✓ 台账与蓝图一致**（merge 已 abort，台账未被改动）。
5. **`origin/main` 相对集成头的模块差距 = 7**（逐模块 ID + 状态 join，两边各 465 行，独立重算）：
   领先的都是**集成头**（5.4.2.1 平台价格同步 / 6.2.2.1 采购申请 / 6.4.2.1 质检单 / 6.5.1.1 采购对账 /
   6.5.2.1 采购分析 / 7.7.2.2 平台托管与全托管备货 / 16.2.2.2 认证与资质），
   **main 领先的模块数 = 0** ⇒ main 依然没有任何集成头没有的东西。
6. **`origin/main` 的树不等于集成头任何祖先树**（`f4d478c8`，在集成头全部历史提交里无同哈希命中）
   ⇒ main 侧确实带了新内容（`ec225641` 带了 24 项能力）。
   **但这 24 项能力集成头全部已交付**（逐模块 ID 核对：24 个模块 ours 与 theirs **都是「已交付」**）
   ⇒ 内容上仍是「集成头领先」，冲突纯属 squash 抹血 + 并行新增的副作用。
7. ⚠️ **更正 11:27 轮的一处措辞（独立核验）**：`ec225641` 的父提交是
   `git log -1 --format='parents=[%P]' ec225641` ⇒ **`parents=[d28188d6]`，只有一个父提交**
   ⇒ **它同样是 squash，不是「真实 merge」**。
   ⇒ 11:27 轮会话日志里「main 上多了一颗**真实提交**（非 squash）」「2 个父提交（真实 merge）」的说法**不成立**；
   `053ee1f0` 那次更正只改了同一处措辞的一半。**本轮据实更正。**
   这也解释了为什么 merge-base 依旧停在 `1bb270a0` —— main 上**两颗**提交（`d28188d6` / `ec225641`）都是 squash。

## 涉及文件

- 冲突文件（未解决，已 abort）：
  - `apps/web/src/lib/api-proxy.ts`
  - `backend/services/catalog/src/test/java/com/starcon/catalog/PriceHistoryIntegrationTest.java`
  - `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`
- 本轮临时文件（未入库）：`.tmp/sync-race-final.sh`（单线程密集竞态）、`.tmp/sync-merge-msg.txt`（merge commit 正文）
- 闸机制相关：`.dev-agent/dispatch.gate/`（`keepalive-<HHMMSS>` 续期文件 + `owner`）

## 关键 trade-off / 讨论

- **并发竞态循环 vs 只跑一个**：等闸期间发现 **至少 4 个并发竞态循环**（其他同步轮次留下的，
  父进程都是 MCode helper）。backlog 明确记录并发 acquire 循环会造成「release 后被秒抢 ⇒ 闸泄漏 70 分钟」。
  本轮取舍：**自己只保留一个竞态循环，且在拿到闸后先确认无其他竞态循环存活，才执行 release** ——
  实测本轮在 11:30:24 拿到闸后，正是被另一个轮次的 release 抹掉（11:33:52），
  若本轮在 11:30 就 release，就会与那个轮次互抢。
- **`stale` 判据 vs `ageSeconds`**：全程遵守协议 —— 只看 `gate status` 的 `stale` 字段。
  本轮 `stale` 恒为 false（keepalive 每 10 分钟刷新闸目录 mtime），**TTL 接管路径完全不可用**，
  只能等显式 release。这也是等闸长达 2h25m 的根因。

## 后续待办

1. **（发布侧，仍是根治项）把集成头的真实提交继续推到 main**：`ec225641` 已经证明这条路可行 ——
   发布侧改用「真提交」而不是 squash，merge-base 就能自然前移，冲突随之消失。
   剩余 7 个落后模块（5.4.2.1 / 6.2.2.1 / 6.4.2.1 / 6.5.1.1 / 6.5.2.1 / 7.7.2.2 / 16.2.2.2）应一并推上去。
2. **（新增，本轮取证）同步任务自身提交文档会制造下一个冲突文件**：
   本轮第 3 个冲突文件是 11:27 轮提交 backlog 文档造成的 —— main 侧也有一份旧稿，两侧各自新增 ⇒ add/add。
   ⇒ 建议：同步任务**不要把 backlog / 会话文档提交进集成头**（或提交前先确认 main 侧同名文件不存在），
   否则每轮同步都会给自己多加一个冲突面。
3. **（闸机制，仍然是 P0）`keepalive.sh` 会把死掉的持闸者的闸无限续命**：
   - 现象：持闸 tick 的父会话被截断后，`keepalive.sh` 作为 nohup 脱离进程继续每 10 分钟续期，
     `stale` 永远为 false ⇒ **TTL 接管路径形同虚设**，闸最多泄漏 20 小时（脚本 `seq 1 120`）。
   - 本轮实测：该闸被持有 **3h23m**（08:03:59 → 11:27:37），远超单轮实测 45–60 分钟。
   - 建议：`keepalive.sh` 应跟着持闸者生命周期走（或加「本轮 tick 活性令牌」校验）。
4. **（调度侧）同一时间存在多个同步轮的竞态循环** —— 本轮已用「单线程竞态 + release 前确认无竞态存活」规避，
   但根治应在调度器层保证「同一时刻只有一个 main 同步竞态循环」。

## 关联

- 协议：`tools/module-progress/SYNC-MAIN.md`
- backlog：`docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`（本轮取证已并入该文档）
- 上一轮同题：`docs/sessions/2026-10/2026-10-21-1127-main-sync-conflict-main-advanced.md`
- 历史同题：`docs/sessions/2026-10/2026-10-21-0115-main-sync-conflict-squash-divergence.md` 等 6 轮
