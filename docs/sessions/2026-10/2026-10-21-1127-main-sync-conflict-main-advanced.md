# 2026-10-21: main 首次真实前进，同步仍撞冲突（冲突面从 6 文件缩到 2 文件）

## 摘要

「main → 集成头同步」任务第 7 轮。前 6 轮（01:15 / 03:15 / 04:15 / 05:15 / 06:43 / 07:40）都卡在同一堵墙：`origin/main` 的 tip 是 GitHub squash 产物，
merge-base 被拉回 `1bb270a0`，每次 merge 都撞 6 个文件的 add/add + content 冲突。

本轮**首次出现实质变化**：main 上多了一颗**真实提交** `ec225641 feat: 到岸成本、成本分摊等 24 项能力交付`（非 squash），
把此前「main 未开发、集成头已交付」的那 24 个模块以真实血缘推上了 main。
⇒ main 与集成头的模块差距从 24 缩到 **7**，冲突面从 **6 个文件缩到 2 个**。

但 **merge 仍然失败**（2 文件冲突）⇒ 本轮结果仍是「**冲突未并入**」，未 push。
根因未消除：老的 squash `d28188d6` 仍在 main 的历史里，merge-base 依旧被拉回 `1bb270a0`。

## 决策 / 结论

1. **冲突只报不解**（协议 SYNC-MAIN.md §0 硬规定）。本轮 `git merge --abort`，MERGE_HEAD 已清，工作区干净，
   HEAD 停在 `84801998`，未 push。冲突文件清单见下。
2. **本轮冲突面与前 6 轮完全不同 —— 不是同一堵墙**：

   | 冲突文件 | 类型 | base / ours / theirs 行数 | main 侧独有内容 |
   | --- | --- | --- | --- |
   | `apps/web/src/lib/api-proxy.ts` | content | 2634 / 5359 / 4967 | **0 行** ⇒ ours 是严格超集，main 侧无任何独有行 |
   | `backend/services/catalog/src/test/java/com/starcon/catalog/PriceHistoryIntegrationTest.java` | add/add | 0 / 812 / 769 | **14 行**，是同一份 fixture 的**旧写法**（`base.plusDays(N)` vs 集成头的 `a1At/a2At`），语义等价、写法已过时 |

   ⇒ 两个文件各 7 个冲突 hunk，但**没有一处是 main 侧的新交付**。
   这与前 6 轮的判断一致（main 侧独有文本全是「已被后续交付推翻的旧写法」），只是文件名单变了。
3. **merge-union 驱动按预期工作**：台账按模块 ID 求并集折叠 465 行同 ID 记录（无冲突）；
   契约 `packages/api-contracts/**` 全部干净合并；1 个文件走了「行级兜底并集」并打印人工确认提示。
4. **台账差距已从 24 降到 7**（逐模块 ID + 状态 join，两边各 465 行）：

   | 项 | main 快照 | 集成头 `84801998` |
   | --- | --- | --- |
   | 已交付 | 266 | **273** |
   | 进行中 | 0 | 0 |
   | 未开发 | 199 | **192** |
   | 完成率 | 57.2% | **58.7%** |

   状态不同的 7 个模块**全部是集成头领先**（5.4.2.1 平台价格同步 / 6.2.2.1 采购申请 / 6.4.2.1 质检单 /
   6.5.1.1 采购对账 / 6.5.2.1 采购分析 / 7.7.2.2 平台托管与全托管备货 / 16.2.2.2 认证与资质），
   **main 领先（main 已交付、集成头未交付）的模块数 = 0** ⇒ main 依然没有任何集成头没有的东西。
5. **`origin/main` 的树不再等于集成头的任何祖先树**（此前 5 轮都是逐字节相同 ⇒ merge 是内容空转）。
   本轮 `origin/main^{tree} = f4d478c8`，在集成头全部历史提交里**无同哈希命中** ⇒ main 侧确实带了新内容。
6. **本轮闸情（与前几轮不同，值得记一笔）**：
   - 闸自 08:03:59 起被 08:00 那轮流水线 tick 持有，**连续持有 3h23m**（远超实测 45–60 min 的单轮时长），
     期间靠一个**脱离父进程的 `keepalive.sh` 每 10 分钟新建 `keepalive-<HHMMSS>` 文件**续期
     ⇒ **`stale` 永远翻不到 true，TTL 接管路径被彻底堵死**，闸只能被显式 release 释放。
   - 活性取证（闸外）：`.tmp/l2-batch48b.runner.log` 显示 batch-48b 的 L2 在 **11:23:26 全绿**
     （backend/typecheck/lint/test/build/sync 全部 `=0`）⇒ 持闸 tick 在 11:23 后进入收尾
     （写文档 → commit → push → release），11:27:37 闸被释放，本轮才拿到闸。
   - 等闸期间另有**至少 3 个并发竞态循环**（其他同步轮次留下的 `.tmp/sync-gate-race.sh` 等，
     父进程都是 MCode helper）⇒ 正是 backlog 记录的 P0「release 后被另一个循环秒抢 ⇒ 闸泄漏 70 分钟」向量。
     本轮处理方式：**先等别人的竞态循环退出，再单线程竞态**，全程只允许一个 acquire 循环活着。

## 涉及文件

- 冲突文件（未解决，已 abort）：
  - `apps/web/src/lib/api-proxy.ts`
  - `backend/services/catalog/src/test/java/com/starcon/catalog/PriceHistoryIntegrationTest.java`
- 证据脚本（本轮临时，未入库）：`.tmp/ledger-join.mjs`（逐模块 ID + 状态 join）
- 台账：`docs/product/capability-blueprint/module-progress.md`（本轮**未改动** —— merge 已 abort，
  `sync.mjs --check` 仍为「✓ 台账与蓝图一致」，273 / 0 / 192）
- backlog：`docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`（已追加本轮证据）

## 后续待办

1. **（发布侧，仍是根治项）把集成头的真实提交继续推到 main**：`ec225641` 已经证明这条路可行 ——
   发布侧改用「真提交」而不是 squash，merge-base 就能自然前移，冲突随之消失。
   剩余 7 个落后模块（5.4.2.1 / 6.2.2.1 / 6.4.2.1 / 6.5.1.1 / 6.5.2.1 / 7.7.2.2 / 16.2.2.2）应一并推上去。
2. **（若坚持并入）开一条 fix 车道解这 2 个冲突**，方向已清楚：
   - `api-proxy.ts` —— ours 是严格超集，main 侧独有 0 行，**可以按 ours 收**，但仍需人工确认；
   - `PriceHistoryIntegrationTest.java` —— main 侧 14 行是同一 fixture 的旧写法，**逐行核对后按 ours 收**。
   解完**必须重跑 L2**（这批内容从未经过 L1/L2）。
3. **（闸机制，仍然是 P0）`keepalive.sh` 会把死掉的持闸者的闸无限续命**：
   - 现象：持闸 tick 的**父会话被截断**后，`keepalive.sh` 作为 nohup 脱离进程继续每 10 分钟续期，
     `stale` 永远为 false ⇒ **TTL 接管路径形同虚设**，闸最多泄漏 20 小时（脚本 `seq 1 120`）。
   - 建议：`keepalive.sh` 应当**跟着持闸者的生命周期走**（例如由持闸者 PID 存活与否决定续期），
     而不是独立 nohup 常驻；或给续期加一个「本轮 tick 的活性令牌」校验。
4. **（调度侧）同一时间存在多个同步轮的竞态循环** —— 本轮已用「先等别人退出再单跑」规避，
   但根治应在调度器层保证「同一时刻只有一个 main 同步竞态循环」。
