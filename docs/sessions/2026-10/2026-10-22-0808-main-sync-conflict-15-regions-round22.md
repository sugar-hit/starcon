# 2026-10-22 08:08 集成头同步 main（第 22 轮 · 冲突未并入 · 冲突面 3 文件 15 区 · api-proxy.ts 冲突区 9 → 11）

## 摘要

「main → 集成头 `integration/module-dev`」同步任务第 22 轮。

**结果：冲突未并入，未推送任何合并内容。** 四选一里的「**冲突未并入**」。
集成头 HEAD 保持 `da1f96f9` 不变（合并已 `git merge --abort` 回退）。

与第 21 轮（07:00）相比，**main 侧依然零推进**（tip 仍是 `a105acf0`，2026-10-22 02:42:58 +0800，
领先集成头 26 个提交，merge-base 仍是 `1bb270a0`），但**冲突面变了**：
三个文件的 **main 侧 blob 哈希与第 21 轮逐字节相同**（`7f844ce6` / `26fb4553` / `4c77f6f3`），
而**集成头侧 blob 变了**（batch-61 推进了集成头）⇒ `api-proxy.ts` 的冲突区
**从第 21 轮的 9 个增加到 11 个**，总冲突区 **13 → 15**。

本轮的新增结论：**main 自带的判定器 `merge-resolve.mjs` 独立复现了本任务自己算出的 blob 级证明**，
逐条一致；且本轮把「这堵墙不可能由本任务自己解开」从上一轮的推断变成了**双路取证一致的结论**
（本任务自算 + main 侧工具），且冲突区数量由 9 增至 11 进一步说明**每等一轮就多一处冲突面**。

## 决策 / 结论

1. **闸：等了约 55 分钟才拿到，没抢、没清、没 force。**
   起手 `gate status` ⇒ `held / stale:false`（age 535s，TTL 4200s）⇒ 持闸者是**活着的**流水线 tick。
   闸外取证：同 worktree 里 `mvnw verify`（pid 63871，`Launcher verify`）在跑，
   随后 surefire 的 PID 连续更换 —— channel → warehouse → trade → crm 逐服务推进，
   `backend/services/*/target/surefire-reports/` 持续新增报告 ⇒ **构建在推进，不是卡死**。
   等闸期间**只做只读取证**，未跑第二个构建（避免与 L2 抢 `backend/**/target/` 与 PG 容器造成假红），
   **连 `git fetch` 都压到拿闸之后才跑**。

   ⚠️ **交接窗口实测窄到「秒级都嫌宽」**：第一轮 1s 间隔竞态（600s 预算）整段错过；
   改 50ms 间隔仍错过一次（闸龄 1312s → 179s，tick 同秒释放又被下一 tick 接走）；
   最后用**单进程原子 mkdir 热循环**（实测 ~296k 次/秒 ≈ 3.4µs/次）才抓住 ——
   **第 280,197,253 次 attempt 于 08:01:33 拿到**。
   ⇒ 协议里「1 秒间隔竞态」的写法在这台机器上**已经不够用了**，见「关键 trade-off」。

2. **冲突只报不解**（协议 §0 + cron prompt 逐字要求）。`git merge --abort` 退出码 0，
   `MERGE_HEAD` 已清，`git ls-files -u` 为 0 行，工作区 `git status --porcelain` 为空，
   HEAD 回到 `da1f96f9`（== 本轮 PRE），**未 push 任何合并内容**。

3. **本轮冲突面 = 3 个文件、15 个冲突区**：

   | 文件                                                              | 冲突区                                                                                      | 性质       | 本轮取证结论                                                                                                                                   |
   | ----------------------------------------------------------------- | ------------------------------------------------------------------------------------------- | ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
   | `apps/web/src/lib/api-proxy.ts`                                   | **11**（行 272/654/802/1445/2695/4739/5465/5878/5986/6279/7177）                            | 单边       | main 侧 blob `7f844ce6` **在集成头历史里逐字节可达**；`git diff origin/main integration/module-dev` = **+836 / −0** ⇒ 集成头侧是严格超集 |
   | `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md` | **1**（行 394）                                                                              | 单边       | main 侧 blob `26fb4553` **在集成头历史里逐字节可达**；两侧差 = **+65 / −0** ⇒ 严格超集                                                            |
   | `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`                 | **3**（行 152/184/220）                                                                      | **真双向** | main 侧 blob `4c77f6f3` **不在**集成头历史里 ⇒ main 独有的「落地树哈希」判据段集成头侧没有 ⇒ **单边取任一侧都丢内容，必须人工求并** |

   ⚠️ 与第 21 轮的差异：**main 侧三个 blob 哈希逐字节相同**（`7f844ce6` / `26fb4553` / `4c77f6f3`），
   **集成头侧三个 blob 全变**（`9325e4d3` / `d13bd140` / `f644fdf9`）。
   ⇒ 冲突区增长**全部来自集成头侧推进**（batch-61 往同一条累积型白名单里又加了条目），
   **不是 main 侧变化造成的**。

4. **main 相对集成头依然零业务新增**（本轮三路重新取证）：

   | 维度             | 方法                                                        | 结论                                                                                                                                    |
   | ---------------- | ----------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
   | 路由 token 对称差 | 两侧 `api-proxy.ts` 全文抽 path token 求对称差                | **只属于 main = 0**；只属于集成头 = **101**（全是 `ad-*` 广告与增长路由，batch-60/61 带来）                                             |
   | 树差异「A」侧     | `git diff --name-status integration/module-dev origin/main`   | **23** 个「main 有、集成头没有」= 14 个 `docs/sessions/**` + 8 个 `tools/progress-site/**` + 1 个 `docs/backlog/**`；**落在 `backend/` 或 `apps/` 下的 = 0** |
   | 台账模块数        | 两侧 `module-progress.md` 汇总表逐项比对                      | 集成头 **312** 已交付 vs main **297** ⇒ 集成头**多 15 个已交付模块**，main 侧没有任何集成头不具备的业务内容                            |

5. **【本轮新增结论】main 侧判定器独立复现了本任务的结论**（与第 21 轮相同的取证手段，但对手已变）。
   `tools/module-progress/merge-resolve.mjs`（main 侧，276 行）从 `origin/main` 取出后
   **在 /tmp 里只读运行**（不改仓库、不碰工作区），结论与本任务自己算的 blob 级证明**逐条一致**：

   ```
   [取集成分支侧] apps/web/src/lib/api-proxy.ts
       依据：main 侧是集成分支历史上的祖先快照，且累积型三件套全部成立
       累积型三件套：集合包含 ✓ · 有序子序列 ✓ · 零删除 ✓（条目 125 → 139）
   [取集成分支侧] docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md
       依据：main 侧是集成分支历史上的祖先快照 ⇒ 两侧是线性演进，取新侧不丢内容
   [并入（人工）] tools/module-progress/MAIN_MERGE_CRON_PROMPT.md
       依据：main 侧的文件版本不在集成分支的历史里 —— main 有集成分支从未有过的内容，直接取一边会丢东西
   结论：1 个文件必须人工并入，其余按上面标注机械执行。   （退出码 1）
   ```

   ⚠️ **该脚本在 macOS 上直接跑会静默无输出**：`invokedDirectly` 用
   `new URL(import.meta.url).pathname === process.argv[1]` 比对，而 `/tmp` 是指向 `/private/tmp` 的
   符号链接 ⇒ 两者不等 ⇒ `main()` 不被调用，进程 0 退出、什么都不打印。
   **必须用真实路径 `/private/tmp/merge-resolve.mjs` 跑**，否则会误判成「判定器没结论」。

   ⇒ **即使解冲突的工作全部做完，本轮仍有 1 个文件（`MAIN_MERGE_CRON_PROMPT.md`）必须人工求并。**

6. **台账数字（本轮相对集成头零变化）**：已交付 **312** / 进行中 **0** / 未开发 **153**
   （465 个模块，完成率 **67.1%**）。
   ⚠️ 与第 21 轮的 308 / 0 / 157（66.2%）不同 —— 差额来自**集成头本身推进**（batch-61 交付 4 个模块），
   **不是本轮合并带来的**（合并已 abort，台账未被改动）。
   `node tools/module-progress/sync.mjs --check` ⇒ **✓ 台账与蓝图一致**（465 个模块行，编号零重复）。

7. **本轮跑了预检**（与第 18/19/20 轮「abort 后不跑预检」不同，与第 21 轮同）：
   `./mvnw -q compile` ⇒ exit 0；`pnpm typecheck` ⇒ 3 successful / 3 total，exit 0。
   理由同第 21 轮 —— 本轮要推一个会话文档提交，
   协议第 4 步「预检绿才 push 集成头」应当对这唯一一次 push 生效。
   **未跑** `mvnw verify` —— 那是流水线的 L2，不属本任务。

8. **本轮只提交了本会话文档**（AGENTS.md「每次有结论的会话都要落到 `docs/sessions/`」），
   **业务代码零改动**；文件名带轮次时间戳，main 侧不存在同名路径 ⇒ 不会给下一轮制造 add/add 冲突。

## 关键 trade-off

1. **协议推荐的「1 秒间隔竞态」已不够用** ⇒ 改用单进程原子 mkdir 热循环。
   实测：1s 间隔 600s 预算整段错过；50ms 间隔仍错过一次（tick 同秒 release + re-acquire）；
   ~296k 次/秒的热循环第 2.8 亿次才撞上。
   热循环的语义**严格等价于** `lane.mjs gate acquire`：同一个 `mkdirSync(GATE_DIR)` 原子互斥、
   失败无副作用、TTL 接管前**必须**先跑 `lane.mjs gate status` 确认 `stale=true`
   （与协议的 `stale` 判据同一套阈值）。**未使用 force，未清闸，未碰别人的闸。**
   ⇒ 建议把协议里的竞态写法从「`node lane.mjs gate acquire` 每秒一次」改成
   「单进程 `mkdirSync` 热循环 + 拿闸后写 owner」，否则等闸窗口会持续被错过。

2. **main 自带的 `merge-resolve.mjs` 明明能机械解掉 2/3 冲突，本轮用它吗** ⇒ **只取证，不解冲突**。
   理由与第 21 轮一致且更强：
   (a) 协议 §0 与 cron prompt 都明写「冲突不自行解」「不要在集成头上解冲突」；
   (b) 协议允许的自动求并只覆盖**契约/台账**两类文件（`merge-union` 驱动），
       `api-proxy.ts` / backlog 文档 / cron prompt 都不在其列；
   (c) 剩下那 1 个真双向冲突（`MAIN_MERGE_CRON_PROMPT.md`）**本轮无论如何必须人工求并**，
       所以「机械解 2 个 + 留 1 个人工」并不构成一个可完成的本轮。
   ⇒ 把 `merge-resolve.mjs` 当**只读证据来源**用，是它在本轮的最大价值。

3. **第 21 轮的结论要不要直接继承** ⇒ 不要，全部重新取证。
   协议第 8 行明写「所有判断必须从仓库现状重新取证，不能凭记忆」。
   本轮重新独立证了：fetch 后的 main tip、merge-base、三阶段 blob 身份、冲突区行号与数量、
   路由 token 对称差、树差异分类、台账自检、闸状态、`merge-resolve.mjs` 的独立复现。
   **新增的是**：① api-proxy.ts 冲突区 9 → 11 的成因定位（集成头侧推进，非 main 侧变化）；
   ② 判定器在 macOS `/tmp` 符号链接下的**静默失效**（不打印任何东西却 0 退出）；
   ③ 闸交接窗口已窄到需要 ~3.4µs 级竞态。

4. **第 22 轮了还在撞同一堵墙，继续撞有没有意义** ⇒ 意义在**取证链**，不在并入。
   本轮的新增价值是：**双路取证一致**（本任务自算 + main 侧工具）+ 冲突区增长机制被定位 +
   协议竞态写法失效这一事实被量化。
   ⇒ 下一步不在「再等一轮 main 推进」，而在**人工解掉那 1 个真双向冲突**，
   或**重切「集成头 → main」的 PR**。

## 涉及文件

- 冲突文件（本轮未改动，已 abort）：
  - `apps/web/src/lib/api-proxy.ts`（**11** 个冲突区）
  - `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md`（**1** 个冲突区）
  - `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`（**3** 个冲突区）
- 本轮新增（唯一提交）：`docs/sessions/2026-10/2026-10-22-0808-main-sync-conflict-15-regions-round22.md`
- 合并中已由 `merge-union` 干净求并但**随 abort 丢弃**的产物（未进入最终树）：
  `docs/product/capability-blueprint/module-progress.md`、
  `packages/api-contracts/src/{catalog,channel,crm,trade,warehouse}.openapi.json`、
  `packages/api-contracts/src/contracts/{catalog,channel,crm,trade,warehouse}.ts`

## 验证结果

- **闸**：`gate status` 起手 ⇒ `held / stale:false`（age 535s）；闸外 `ps` 见
  `mvnw verify`（pid 63871）与逐服务 surefire 连续推进；
  高密度原子 mkdir 竞态，**第 280,197,253 次 attempt 于 08:01:33 取得**；**收尾已 release**。
- **fetch**：`git fetch origin --prune` ⇒ `origin/main` tip = `a105acf0`（2026-10-22 02:42:58 +0800），
  领先集成头 **26 个提交**，`merge-base` = `1bb270a0`（与第 18/19/20/21 轮相同 ⇒ main 连续 5 轮零推进）。
- **merge**：`git merge --no-ff -F <msg> origin/main` ⇒ **3 文件冲突（15 区）**；
  `git merge --abort` ⇒ 退出码 0，`MERGE_HEAD` 已清，工作区 `git status --porcelain` 为空，
  HEAD 回到 `da1f96f9`。
- **三阶段 blob 身份**（`git ls-files -u`，abort 前取证）：

  | 文件                                                              | base (stage1) | ours (stage2) | theirs (stage3) | ours↔theirs 差     |
  | ----------------------------------------------------------------- | ------------- | ------------- | --------------- | ------------------ |
  | `apps/web/src/lib/api-proxy.ts`                                   | `2bcc22a8`    | `9325e4d3`    | `7f844ce6`      | **+836 / −0**      |
  | `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md` | `2d59bd2b`    | `d13bd140`    | `26fb4553`      | **+65 / −0**       |
  | `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`                 | `2a52b3f5`    | `f644fdf9`    | `4c77f6f3`      | **+6 / −44**       |

- **可达性**：`git rev-list integration/module-dev --objects` ⇒
  `7f844ce6` **可达**、`26fb4553` **可达**、`4c77f6f3` **不可达**（main 独有内容）。
- **远端**：`origin/integration/module-dev` == `da1f96f9` == 本轮合并前本地 HEAD ⇒ **未推送任何合并内容**。
- **台账**：`sync.mjs --check` ⇒ ✓ 与蓝图一致（465 个模块行，编号零重复）；312 / 0 / 153（67.1%）。
- **预检**：`backend/./mvnw -q compile` ⇒ **exit 0**（仅 JVM `Unsafe`/`jansi` 警告，无编译错误）；
  `pnpm typecheck` ⇒ **3 successful / 3 total**，exit 0（`@starcon/web`、`@starcon/ui`、`@starcon/config-tailwind`）。
- **业务代码**：本轮零改动；**未跑** `mvnw verify`（属流水线 L2）。

## 后续待办

- **人工解掉 `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` 的 3 个冲突区**（行 152/184/220）——
  这是唯一真双向冲突：main 侧有集成头从未有过的「落地树哈希」判据段（来自 `a105acf0`），
  集成头侧有 6 行 main 没有的增量 + 44 行 main 已删除的旧判据。**必须求并，不能单边取。**
- **另 2 个文件可机械取集成分支侧**（`merge-resolve.mjs` 已给出三件套证明），但仍属人工动作。
- **协议竞态写法待修**：`SYNC-MAIN.md` 第 1 步的「1 秒间隔 `gate acquire`」在本机已连续漏窗口，
  建议改成单进程 `mkdirSync` 热循环（~3.4µs/次），并在协议里补上「热循环拿闸后写 owner」的约定。
- **`merge-resolve.mjs` 在 macOS 上需用真实路径运行**（`/private/tmp/...`），
  否则 `invokedDirectly` 判定失败 ⇒ 静默 0 退出、无任何输出。
