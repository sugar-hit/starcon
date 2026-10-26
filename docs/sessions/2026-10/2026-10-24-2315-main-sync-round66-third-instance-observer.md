# 2026-10-25: main → 集成头同步（第 66 轮 · 三实例并发 · 观察者实例）

> 本文件记录的是**同一轮同步任务的第三个并发实例**（下文称「本实例」）。
> 本轮已由另两个实例先后完成并推送（见「关联」第 1、2 条）。
> 本实例的独特价值不在搬运 commit（**零搬运**），而在：
> **① 独立复核最终状态；② 记录一个此前从未被观测到的闸竞态 —— TTL 接管点的非原子性。**

- 时间：2026-10-24 23:15 起手读参数 → 2026-10-25 01:28:56 竞态命中 TTL 接管点 → 01:29–01:43 复核 + 收尾
- 集成头 worktree：`/Users/guan/Projects/starcon-worktrees/integration`
  （主工作区 `/Users/guan/Projects/starcon` 全程未做任何 git 操作）
- 分支：`integration/module-dev`
  - 起手 HEAD = `7ecd64f5`「merge(12.3.2.3): 自动匹配规则」
  - 收尾时 HEAD = `76569eae`「docs(sessions): 集成头同步 main 第 66 轮（并发实例）—— 无新提交，未同步」
  - 收尾时 `origin/integration/module-dev` = `76569eae` = 本地 HEAD（ahead/behind = 0/0）
- `origin/main` tip：`6522c497`（2026-10-23 09:18:01 +0800「chore: 同步集成分支最新进度」，
  **自该时刻起约 40 小时未再前进**；本轮 `git fetch` 后与 `git ls-remote` 实时远端一致）
- `merge-base --is-ancestor origin/main integration/module-dev` ⇒ **成立**（exit 0）
- `git rev-list --count integration/module-dev..origin/main` ⇒ **0**
- 结果：**无新提交，未同步**（NO_UPDATE）—— 本实例**未 merge、未造空 merge commit、未推送任何代码**
- 闸：起手 `held` / `stale: false` / `ageSeconds: 201`（TTL 4200）
  ⇒ 等闸约 **2 小时 13 分钟**（其中 70 分钟是持闸 tick 跑完整 L2），
  01:28:56 由 TTL 过期点（01:28:55）后 1 秒原子接管取得；本轮已 `gate release`

---

## 摘要

本实例**没有产生任何合并，也没有推送任何代码**。`origin/main` 的 tip `6522c497` 早已是集成分支的祖先
⇒ 按协议第 2 步的 NO_UPDATE 分支，**不 merge、不造空 merge commit**。

本轮实际发生的事，比「零新提交」复杂得多，本实例记录了三件值得留痕的事：

1. **持闸 tick（batch-90）在 L2 绿灯之后、提交/推送之前崩了**，留下一枚**孤儿闸**
   （owner 进程已死、闸目录无 keepalive 续期、目录 mtime 冻结）。这是本系列首次观测到
   「**L2 已绿但收尾三件套（说明/commit/push）未完成**」的崩法 —— 与历次「崩在 L2 中途」形状不同。
2. **TTL 接管点不是原子的**：01:28:56 前后三个并发实例的 `gate acquire` 出现了
   **两次先后「成功」**（本实例 01:28:56 写入 owner `11835`；另一实例 01:30:55 写入 owner `12631`），
   且**中间出现闸目录凭空消失**。详见下节「🔴 闸竞态实测」。
3. **崩掉 tick 的在途产物归属明确**：工作区里那份未跟踪的 batch-90 session doc 带
   `<!-- THREE_PIECE_PLACEHOLDER -->` 占位符未填 ⇒ 属被中断 tick 的在途产物，**不属于本轮**。

---

## 🔴 闸竞态实测（本次最值得记的一条）

### 观测到的时间线

| 时刻 | 事件 | 取证 |
| --- | --- | --- |
| 23:11:59 | batch-90 tick 取闸（owner pid `59636`） | `.dev-agent/dispatch.gate/owner` |
| 23:16:59 | batch-90 启动全量 L2（`mvnw verify`） | `.dev-agent/l2-run-batch-90.log` |
| 00:18:19 | **L2 GREEN**（12 个退出码全 0） | 同上，末行 `===== L2 END 2026-10-25 00:18:19 =====` |
| 00:18:55 | 闸被 pid `88196` **重新持有** | `owner` 改为 `88196` / `2026-10-24T16:18:55.969Z` |
| 00:37:24 | batch-90 的 keepalive 自行停止 | `.dev-agent/keepalive-b90.log` |
| 00:18:55–01:28:55 | 闸目录 mtime **冻结**（无人续期）⇒ 闸已成孤儿 | `stat` 闸目录 mtime 恒为 00:18:55 |
| 01:28:55 | **TTL 过期点**（00:18:55 + 4200s） | 计算值 |
| 01:28:56 | **本实例**的 `gate acquire` 命中（owner pid `11835`） | `owner` = `11835` / `2026-10-24T17:28:56.850Z` |
| 01:29–01:30 | **闸目录短暂消失**（`ls` 报 No such file or directory） | 本实例 01:29:xx 的命令输出 |
| 01:30:55 | 另一实例的 `gate acquire` 也「成功」（owner pid `12631`） | `owner` = `12631` / `2026-10-24T17:30:55.574Z` |

### 根因

`lane.mjs` 的 `gateAcquire()` 在 TTL 过期后的接管分支里是：

```js
if (age > ttlSeconds) {
  rmSync(GATE_DIR, { recursive: true, force: true });
  mkdirSync(GATE_DIR);          // ← 只有这一步是原子的
}
```

`rmSync` 与 `mkdirSync` **之间存在一个窗口**：实例 A 执行完 `rmSync` 还没 `mkdirSync` 时，
实例 B 的 `mkdirSync` 可以先成功；随后 A 的 `mkdirSync` 又会失败 ⇒ A 走 `catch` 返回 `false`；
但 **B 的 `owner` 写入与 A 的 `rmSync` 之间的先后关系不保证「最后写入者 == 目录当前持有者」**。
本实例 01:28:56 成功写入 owner 后，闸目录在 01:29–01:30 之间**整体消失**（`ls` 报不存在），
随后由另一实例以 pid `12631` 重建 ⇒ **同一枚闸在 2 分钟内先后被两个实例判定为「已持有」**。

### 影响与本实例的处置

- **没有造成数据损坏**：两个实例都只写了**纯 docs 提交**，代码路径零改动，
  最终远端集成头 = `76569eae`，工作区与远端一致（0/0）。
- **但互斥语义被削弱了一瞬**：在 01:28:56–01:30:55 窗口内，两方都自认持闸。
  本实例在发现闸目录消失后**立即停止一切写操作**，只做只读复核，
  并在 01:35 确认闸已被对方正常释放后，**于 01:42 重新取闸**才提交本文件。

### 建议（留人工裁决）

1. 给 `gateAcquire()` 的 TTL 接管分支加**原子化**：把 `rmSync` + `mkdirSync` 换成
   「先 `mkdir` 到一个临时目录 → 成功后再 `rename` 覆盖」，或用 `mkdir` 的失败重试 + `owner` 二次校验
   （写入 owner 后回读，若读到别人的 owner 就放弃）。
2. `owner` 文件建议写入**本轮实例标识**（会话 id / 实例随机 token），而不只是 pid：
   本轮反复证明 **pid 不是存活证据**（协议第 1 步已写明），而 pid 也**不是归属证据**。
3. 协议第 1 步补一句：**TTL 接管窗口内可能有另一个实例同时在竞** ⇒ 取到闸后应立即回读
   `owner` 与闸目录内容，确认归属一致再动手。

---

## 独立复核（最终状态逐项取证）

| 项 | 复核方法 | 结果 |
| --- | --- | --- |
| main 无新提交 | `git fetch origin --prune` 后 `git rev-parse origin/main` | `6522c497` |
| 同上 | `git ls-remote origin refs/heads/main`（实时远端） | `6522c497`（一致） |
| 同上 | `git merge-base --is-ancestor origin/main integration/module-dev` | **成立**（exit 0） |
| 同上 | `git rev-list --count integration/module-dev..origin/main` | **0** |
| 推送到位 | `git rev-parse HEAD` vs `git ls-remote origin refs/heads/integration/module-dev` | `76569eae` = `76569eae`（**0/0**） |
| 台账一致 | `node tools/module-progress/sync.mjs --check` | **✓ 台账与蓝图一致** |
| 台账数字 | `docs/product/capability-blueprint/module-progress.md` | 465 总数 / **400 已交付 / 0 进行中 / 65 未开发** |
| 卷汇总行无重复 | 统计 `^\| 12 \|` 行数 | **1**（batch-90 merge 曾引入 2 条陈旧行，已被 `sync.mjs` 修正） |
| 预检（日志留痕） | `.dev-agent/sync-main-r66-compile.log` | `BUILD FAILURE` / `[ERROR]` 命中 **0** |
| 预检（日志留痕） | `.dev-agent/sync-main-r66-typecheck.log` | `Tasks: 3 successful, 3 total` |
| **已推代码 = L2 绿代码** | `git diff --stat 7ecd64f5..76569eae -- backend/ apps/ packages/ db/ services/ workers/ tools/` | **空** ⇒ 代码路径逐字节相同 |
| 全量 L2 绿证据 | `.dev-agent/l2-run-batch-90.log` | 12 个退出码全 0，`===== L2 GREEN =====`，END 00:18:19 |

⇒ 最终状态**全部复核通过**，本实例**无需重做**任何一步。

---

## 决策 / 结论

1. **本轮结果 = 无新提交，未同步（NO_UPDATE）**。`origin/main` 自 2026-10-23 09:18 起静止约 40 小时，
   tip 仍是 `6522c497`；集成头已领先 main **932 个提交**。本实例**未 merge、未造空 merge commit、未推送任何代码**。
2. **闸的判据只用 `stale` 字段**：全程未按 `ageSeconds` 自行判断，也未用 owner PID 判活
   （协议明写：`gate acquire` 是短命进程，**owner PID 不是存活证据**）。
   起手 `stale: false` ⇒ 等；TTL 自然过期后由 `mkdirSync` 原子接管。**全程未清闸、未 force。**
3. **等闸期间只做只读取证**：全程未跑 `git fetch`（直到取闸成功后才跑）、未跑任何构建、
   未在等闸窗口里制造第二个 `mvnw` / `pnpm` 进程 —— 这是必须的：
   持闸 tick 的 L2 与任何预检共用 `backend/**/target/` 与 PG 容器，并发必假红。
   等闸窗口内唯一跑过的进程是**只读**的 `gate status` / `cat owner` / `ps` / `git log` / `git ls-remote`。
4. **闸消失时立刻停手**：01:29–01:30 发现闸目录被对方接管/删除后，本实例**停止一切写操作**，
   改为纯只读复核，直到 01:35 确认闸已被正常释放，才在 01:42 重新取闸提交本文件。
5. **崩掉 tick 的在途产物不归本轮**：工作区里未跟踪的
   `docs/sessions/2026-10/2026-10-24-batch-90-merge-12-3-2-3-whitelist-union.md`
   带 `<!-- THREE_PIECE_PLACEHOLDER -->` 占位符未填 ⇒ 是被中断 tick 的在途产物。
   **本实例未提交它，也未 `git checkout --` 抹掉它**（抹掉就是弄丢别人的在途工作），
   **留给流水线下一轮 tick 处理**。
6. **本实例不跑预检**：本实例**没有要 push 的代码**（唯一提交是本文件，纯 docs），
   代码侧可编译性由更强的两条证据覆盖：batch-90 的全量 L2 GREEN + 上表「已推代码 = L2 绿代码」逐字节比对为空。

---

## 涉及文件

- 本轮**没有**改动任何业务代码 / 契约 / 迁移。
- 本轮新增（唯一提交）：`docs/sessions/2026-10/2026-10-25-2315-main-sync-round66-third-instance-observer.md`（本文件）
- 只读取证涉及：
  - `tools/module-progress/SYNC-MAIN.md`（本任务协议）
  - `tools/module-progress/lane.mjs`（`gate status` / `gate acquire` / `gate release` 的实现语义）
  - `tools/module-progress/sync.mjs`（台账刷新器，含 `--check` 只校验模式）
  - `docs/product/capability-blueprint/module-progress.md`（台账，**内容未变**）
  - `.dev-agent/l2-run-batch-90.log`、`.dev-agent/l2-guard-b90.log`、`.dev-agent/keepalive-b90.log`（L2 与闸续期取证）
  - `.dev-agent/sync-main-r66-compile.log` / `.dev-agent/sync-main-r66-typecheck.log`（并发实例的预检留痕）
  - `.dev-agent/dispatch.gate/owner`（闸归属取证）
- 临时脚本（gitignored，未入库）：`.tmp/sync-main-gate-race.sh`、`.tmp/watch-b90-l2.sh`、`.tmp/keepalive-sync66.sh`

---

## 关键 trade-off / 讨论

- **🔴 三个实例并发跑同一轮同步任务**：本轮至少有三个并发实例在跑（另两个实例分别落地了
  `988d6632` 与 `76569eae`）。它们靠 `mkdirSync` 的原子性互斥，
  但**TTL 接管分支的 `rmSync`+`mkdirSync` 不是原子的**（见「🔴 闸竞态实测」），
  导致 01:28:56–01:30:55 窗口内两方都自认持闸。
  ⇒ **协议第 1 步的「密集竞态 `acquire`」在 TTL 过期点是安全的**（失败无副作用），
  但**接管成功的那一刻未必等于独占** ⇒ 取闸后必须立即回读 `owner` 复核归属。
- **🔴 等闸 2 小时 13 分钟（本系列最长）**：其中前 70 分钟是持闸 tick 在跑完整 L2
  （`mvnw verify` 约 61 分钟），后 70 分钟是**孤儿闸等 TTL 自然过期**。
  后者是本系列首次出现的形状：持闸者已死，闸只能靠 TTL 过期自己解锁。
  ⇒ 协议第 1 步的「等闸时长可长达 1 小时以上」提示在本轮得到进一步验证：
  **孤儿闸会把等待时间再拉长一个完整 TTL**。
- **🔴 崩溃形状变了：从「崩在 L2 中途」到「崩在 L2 绿灯之后」**：
  batch-90 的 L2 是**绿的**（12 个退出码全 0），崩点在**收尾三件套**（说明/commit/push）之前。
  ⇒ 这类崩溃**最危险**：它留下一枚「看起来还有人在跑、实际已无人续期」的孤儿闸，
  且把一棵**已验证但未推送**的代码树扣在本地。
  ⇒ 下轮流水线 tick 取闸后应先看 `origin/integration/module-dev` 与本地 HEAD 是否脱节。
- **未把崩溃 tick 的在途产物据为己有**：那份带占位符的 session doc 归属明确是 batch-90 的，
  本实例既不提交也不删除，留给流水线处理。**这是「归属明确就等它提交」原则在「提交者已死」时的延伸**：
  提交者不会再来 ⇒ 把它留给**下一个真正有责的人**（流水线 tick），而不是同步任务顺手带走。

---

## 后续待办

1. **盯 `origin/main` 是否前进**：main 自 2026-10-23 09:18 起静止（已约 40 小时），
   若下一轮仍是同一 tip，本任务会持续输出「无新提交」。
2. **下一轮流水线的 L2 仍应重点看 `7ecd64f5`**：batch-90 的并入提交（12.3.2.3 自动匹配规则）
   已在远端，但它的**说明/commit/push 收尾**是本轮两个并发实例补的；
   下一轮 L2 仍应以该树为基线复核。
3. **协议修订（第八次提出，留人工裁决）**：
   - `lane.mjs` 的 **TTL 接管分支原子化**（`rmSync`+`mkdirSync` → 临时目录 + `rename`），
     或写入 owner 后回读校验；
   - `owner` 文件写入**实例 token**（而非仅 pid），并提供「只释放自己持有的闸」的校验
     （第 61 轮已提出过 release 的无条件 `rmSync` 问题，本轮的竞态说明它**更急迫**了）；
   - 协议第 1 步补「**取闸成功后立即回读 `owner` 复核归属**」；
   - 协议第 1 步补「**持闸者已崩 ⇒ 闸要再等一个完整 TTL**」的等待时长预期；
   - 协议第 4 步补「**本轮要 push 的树含业务代码时，即使没 merge 也要跑预检**」
     （并发实例 `988d6632` 的处理是对的：它推的是 batch-90 的代码树）。
4. **batch-90 的收尾三件套仍未完成**：那份带 `<!-- THREE_PIECE_PLACEHOLDER -->` 的 session doc
   仍是未跟踪文件 ⇒ **留给流水线下一轮 tick** 补齐并提交。
5. **本系列既有 P0 未动**（也没权动）：
   - `docs/backlog/2026-10-23-integration-branch-formatting-debt-manufactures-sync-conflicts.md`
   - `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md`
   - `docs/backlog/2026-10-24-nav-task-pages-registry-systemic-lag.md`

---

## 关联

- 协议：`tools/module-progress/SYNC-MAIN.md`（完整流水线协议见 `tools/module-progress/PIPELINE.md`，两者不一致时以 PIPELINE.md 为准）
- 第 66 轮（并发实例 A，已推送 `988d6632`）：
  `docs/sessions/2026-10/2026-10-25-0129-main-sync-round66-zero-net-change.md`
  —— 该实例跑完了预检（`mvnw -q compile` + `pnpm typecheck` 双绿）并把 batch-90 中断时未完成的 push 补上
- 第 66 轮（并发实例 B，已推送 `76569eae`）：
  `docs/sessions/2026-10/2026-10-25-0131-main-sync-concurrent-instance-gate-handover.md`
  —— 该实例接手并释放了孤儿闸，并复核了实例 A 的结论
- 第 65 轮（上一轮，同样零新提交）：`docs/sessions/2026-10/2026-10-24-2215-main-sync-round65-zero-net-change.md`
- 第 59 轮（本系列最近一次实际合并）：`docs/sessions/2026-10/2026-10-24-0335-main-sync-round59-conflict-resolved-pushed.md`
  → merge commit `ac0b1509`，并入 20 个 main 提交
- 闸语义的历史取证：`docs/sessions/2026-10/2026-10-23-0006-main-sync-conflict-31-files-gate-contention.md`
  （并行实例互撞、`ageSeconds` 不可用于判活）
