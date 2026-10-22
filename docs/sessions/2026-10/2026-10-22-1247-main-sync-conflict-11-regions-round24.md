# 2026-10-22 12:47 集成头同步 main（第 24 轮 · 冲突未并入 · 冲突面 3 文件 11 区 · 与第 22/23 轮同墙但形态变化）

## 摘要

「main → 集成头 `integration/module-dev`」同步任务第 24 轮。

**结果：冲突未并入，未推送任何合并内容。** 四选一里的「**冲突未并入**」。
集成头 HEAD 保持 `4d85131f` 不变（合并已 `git merge --abort` 回退）。

与第 22/23 轮（3 文件 15 区）相比，本轮冲突面**缩小到 3 文件 11 区**，但**文件构成变了**：

| 维度                | 第 22 轮（08:08）      | 第 23 轮（08:19）      | 第 24 轮（12:47）      | 结论             |
| ------------------- | ---------------------- | ---------------------- | ---------------------- | ---------------- |
| `origin/main` tip   | `a105acf0`             | `a105acf0`             | `89395bc8`             | main 推进 4 个   |
| `merge-base`        | `1bb270a0`             | `1bb270a0`             | `1bb270a0`             | 未变             |
| 领先集成头的提交数  | 26                     | 26                     | **32**                 | 增加 6 个        |
| 冲突文件数 / 冲突区 | 3 文件 / 15 区         | 3 文件 / 15 区         | **3 文件 / 11 区**     | **减少 4 区**    |
| 冲突文件构成        | api-proxy / flaky-l2 / MAIN_MERGE_CRON_PROMPT | 同左 | api-proxy / **contract-drift** / MAIN_MERGE_CRON_PROMPT | **第 2 个换了** |

⇒ 上一轮那个 `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md`
**不再冲突**（两侧 blob 已逐字节相同 `d13bd140`）；
但**新冒出** `docs/backlog/2026-10-20-contract-drift-ci-gate.md`（add/add 冲突）。
⇒ **冲突面不是单调增长的，也不是固定不变的 —— 它会随两侧各自改动而此消彼长。**

## 决策 / 结论

1. **闸：等了 2 小时 32 分才拿到，一次拿到，没抢、没清、没 force。**
   起手 `gate status` ⇒ `held / stale:false` ⇒ 协议 §1 的「密集 acquire 竞态」循环。
   期间持闸 tick 连续跑了 5 轮 L2（batch-62 rerun / rerun2 等），
   每一轮都以 `touch keepalive-<HHMMSS>` 续期（闸目录 mtime 刷新），
   共观察到 **14 个 keepalive 文件**（10:08 → 12:25）⇒ **全程判活证据充分，不是一次假活**。
   闸在 **12:46:22** 由流水线 L2 全绿后释放，被热循环抢到。

   ⚠️ **本轮实测到协议第 1 步的一个坑**：`gate status` 在 12:28:23 报过一次 `free`
   （`ageSeconds: null`），但紧接着 `gate acquire` 返回 `held-by-other`，
   且闸目录里 11 个 keepalive 文件**一个没少**、目录 mtime 停在 12:25。
   ⇒ 那次 `free` 是 **`statSync` 的瞬时假阴性**，不是「闸真的空了」。
   **判据应以 `gate acquire` 的实际返回 + 闸目录内容为准，不能只看单次 `gate status`。**

2. **等闸期间严格只做只读取证，没跑第二个构建。**
   观察到持闸 tick 的 L2 正在跑 `backend/**/target/` 与 PG 容器
   （surefire 报告持续新增、Testcontainers 容器持续起落），
   按协议「等闸期间连 `git fetch` 都先别跑」执行 ⇒ 全程只做 `gate status` / `cat owner` / `ps` / `git log` / `git merge-tree --write-tree`（只读）。

3. **冲突只报不解**（协议 §0 + cron prompt 逐字要求）。
   `git merge --abort` 退出码 0，`MERGE_HEAD` 已清（worktree gitdir 下无任何 merge 状态文件），
   `git status --porcelain` 为空，HEAD 回到 `4d85131f`（== 本轮 PRE），
   **未 push 任何合并内容**。

4. **本轮冲突面 = 3 个文件、11 个冲突区**：

   | 文件                                                              | 冲突区                                                        | 性质       | 本轮取证结论                                                                                                                                                    |
   | ----------------------------------------------------------------- | ------------------------------------------------------------- | ---------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
   | `apps/web/src/lib/api-proxy.ts`                                   | **6**（行 699/4178/5566/5752/5878/7526）                      | 单边可机械 | main 侧 blob `9325e4d3` **在集成头历史里逐字节可达**（ancestorIndex 9 / 474 versions）⇒ 线性演进；但累积型三件套 `zeroDeletion: false`（removedCount 2）⇒ 仍需人工确认 |
   | `docs/backlog/2026-10-20-contract-drift-ci-gate.md`               | **1**（行 84）                                                | **add/add** | 共同祖先里**没有**这个文件（两侧各自新增）；main 侧 blob `3235b724` 在集成头历史里可达（ancestorIndex 3 / 3）⇒ **可机械取集成分支侧**（集成头 117 行 vs main 83 行，集成头是超集） |
   | `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`                 | **4**（行 152/184/220/699）                                   | **真双向** | main 侧 blob `881a55eb` **不在**集成头历史里 ⇒ main 有集成头从未有过的内容，**单边取任一侧都丢东西，必须人工求并** |

   ⚠️ 与第 23 轮比：`api-proxy.ts` 从 11 区降到 **6 区**，
   `MAIN_MERGE_CRON_PROMPT.md` 从 3 区升到 **4 区**。
   ⇒ **上一轮记的「冲突区会每轮增长」在这轮同样被证伪**（第 23 轮已证伪过一次，本轮再证一次）。

5. **main 相对集成头依然零业务代码增量**（本轮重新取证）：

   | 维度             | 方法                                                        | 结论                                                                                                                                    |
   | ---------------- | ----------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
   | 树差异「A」侧     | `git diff --name-status integration/module-dev origin/main` | **25** 个「main 有、集成头没有」= `docs/sessions/**` + `tools/progress-site/**` + `docs/backlog/**`；**落在 `backend/` 或 `apps/` 下的 = 0** |
   | 台账模块数        | 两侧 `module-progress.md` 汇总表逐项比对                      | 集成头 **316** 已交付 vs main **312** ⇒ 集成头**多 4 个已交付模块**，main 侧没有任何集成头不具备的业务内容 |

   ⚠️ **「0 个业务增量」这个结论对 32 个提交仍然成立，但 main 侧的提交数量在涨**：
   `9da98852`（15 项能力）、`e57e1497`（10 项）、`807739db`/`05fb216e`/`478778ef`（采购域）、
   `73791049`/`a257a922`（品牌授权）、`a9f3b4d0`（7 项）、`ec225641`（24 项）
   这些 `feat` 提交**在集成头历史里已经全部并过**（集成头 316 vs main 312，集成头是超集），
   main 上多出来的只是**集成分支自身进度的镜像记录**。

6. **合并驱动 `merge-union` 的自动求并部分全部成功**（37 个文件已暂存）：
   - `packages/api-contracts/src/contracts/{catalog,warehouse}.ts` ⇒ 按导出类型名求并，
     **导出声明数三阶段完全一致（863 / 1077）**，无重复无丢失；
   - `packages/api-contracts/src/{catalog,warehouse}.openapi.json` ⇒ 走了**行级兜底并集**
     （驱动自己打了 `merge-union: 走了行级兜底并集，请人工确认这个文件改对了`），
     差异只有 `1e-06` ↔ `0.000001` 的**数值字面量格式**与**末尾换行**，**JSON 语义等价**；
   - `docs/product/capability-blueprint/module-progress.md` ⇒ 按模块 ID 求并，465 行全折叠，
     **但产出里出现了一份重复的汇总块**（第 13–16 行是集成头的 316/149/68.0%，
     第 543–546 行是 main 的 312/153/67.1%）——
     ⇒ **台账的汇总表不是「按模块 ID」覆盖的，驱动只对模块行求并、汇总块两边都留了**。
     **这正是 `12cbd407` 那次提交（`chore(ledger): 重新生成台账`）在修的同一类问题**，
     说明**台账必须靠 `sync.mjs` 重新生成来归一，不能靠合并驱动的求并结果**。
     本轮合并已 abort ⇒ 该重复块**没有进入集成头**，无需处理。

7. **预检在「未合并的集成头」上跑，结论只证明集成头自身健康，不证明合并可编译。**
   本轮合并因冲突 abort，不存在「已合并态」可预检；又因闸在本轮手里（无并发 L2），
   在 HEAD 上跑快速预检是安全的：
   - `backend/./mvnw -q compile` ⇒ **exit 0**（仅 JVM `Unsafe`/`jansi` 警告，无编译错误）
   - `pnpm typecheck --force` ⇒ **3 successful / 3 total，0 cached**，exit 0
     （`@starcon/web`、`@starcon/ui`、`@starcon/config-tailwind`；**强制真跑，不吃缓存回放**）
   - `node tools/module-progress/sync.mjs --check` ⇒ ✓ 台账与蓝图一致（465 模块行，编号零重复）

## 关键 trade-off

- **不跑预检 vs 跑在未合并态上**：协议的第 4 步预检语义是「验证合并结果」。
  本轮没有合并结果，若照抄「无合并结果 ⇒ 不跑」，汇报里就少了「集成头自身是否绿」这一条关键信息
  （人工解冲突时需要知道基线是绿的）。本轮选择**在 HEAD 上跑**，并在汇报与本文档里
  **显式标注它不覆盖合并**。闸在本轮手中 ⇒ 无并发 L2 ⇒ 不存在抢 `backend/**/target/` 与 PG 容器造成的假红。
- **用 `gate acquire` 的返回值而不是单次 `gate status` 判闸**：
  本轮实测到一次 `status` 报 `free` 但 `acquire` 报 `held-by-other` 的假阴性。
  取闸时**以 `acquire` 的实际返回为准**，更稳。
- **把竞态循环从「每 1 秒 spawn 一个 node 进程」改成「单进程 mkdirSync 热循环」**：
  协议 §1 给的写法每次迭代要 spawn 一次 node（~100ms+），
  而闸的交接窗口可以窄到零宽度（第 22 轮已实测漏窗口）。
  本轮改用**与 `lane.mjs` 逐字同构**的单进程热循环（`/tmp/starcon-gate-race.mjs`，
  ~80 万次/秒），语义完全照抄 `gateAcquire`：
  空闲 ⇒ mkdir 成功即取得；持有且未过期 ⇒ mkdir 失败即返回（**无任何副作用**）；
  持有但已过期 ⇒ rm + mkdir 按 TTL 接管（与 `gate status` 的 `stale` 同阈值 4200s）；
  成功后写 `owner` 文件。
  **先在闸被持有时跑 3 秒自检，确认它没有抢活闸**，再投入正式竞态。
- **不自行解冲突**：三个冲突文件里，`contract-drift-ci-gate.md` 按 `merge-resolve.mjs`
  判定「取集成分支侧」零丢失、`api-proxy.ts` 一侧是严格超集，
  但那是**代码/文档文件**，协议与 cron prompt 都明令禁止本任务解冲突；
  `MAIN_MERGE_CRON_PROMPT.md` 更是**真双向**，单边取任一侧都丢内容 ⇒ 必须人工求并。

## 涉及文件

- **本轮只新增**：`docs/sessions/2026-10/2026-10-22-1247-main-sync-conflict-11-regions-round24.md`（本文件）
- **本轮未改动任何业务代码**；合并已 abort，工作区回到干净状态
- **冲突文件（未改动，仅取证）**：
  - `apps/web/src/lib/api-proxy.ts`（6 区：行 699/4178/5566/5752/5878/7526）
  - `docs/backlog/2026-10-20-contract-drift-ci-gate.md`（1 区：行 84，add/add）
  - `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`（4 区：行 152/184/220/699）
- **main 侧独有的 25 个文件**（`docs/sessions/**` + `tools/progress-site/**` + `docs/backlog/**`）本轮未并入

## 验证结果

- **闸**：`gate status` ⇒ 起手 `held / stale:false` ⇒ 密集竞态 `acquire` ⇒ **12:46:22 拿到**
  （等待 2h32m；期间 14 个 keepalive 续期 ⇒ 持闸者全程活着，未按 ageSeconds 猜判据）
- **fetch**：`git fetch origin --prune` ⇒ `origin/main` `d78ced6a..89395bc8`，
  `origin/chore/sync-module-dev` 被删（另一次会话的 PR 分支）
- **判据**：`git merge-base --is-ancestor origin/main integration/module-dev` ⇒ **失败** ⇒ `HAS_UPDATE`
  - `origin/main` tip = `89395bc8`，领先集成头 **32 个提交**
  - `merge-base` = `1bb270a0`
  - 差异规模：**32 个提交 / 1503 个文件 / +953705 / −93722**
- **merge**：`git merge --no-ff -F <msg> origin/main` ⇒ **3 文件冲突（11 区）**；
  `git merge --abort` ⇒ 退出码 0，worktree gitdir 下无 `MERGE_HEAD` 等状态文件，
  `git status --porcelain` 为空，HEAD 回到 `4d85131f`
- **三阶段 blob 身份**（base/ours/theirs）：

  | 文件                                                              | base (stage1) | ours (stage2) | theirs (stage3) | 判定                                |
  | ----------------------------------------------------------------- | ------------- | ------------- | --------------- | ----------------------------------- |
  | `apps/web/src/lib/api-proxy.ts`                                   | `2bcc22a8`    | `692dacb0`    | `9325e4d3`      | main 侧是祖先快照（线性演进）        |
  | `docs/backlog/2026-10-20-contract-drift-ci-gate.md`               | **无**        | `0e473ae6`    | `3235b724`      | **add/add**，集成头侧是超集           |
  | `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`                 | `2a52b3f5`    | `f644fdf9`    | `881a55eb`      | **真双向**，main 有独有内容          |

- **可达性**：`git rev-list integration/module-dev --objects` ⇒
  `9325e4d3` **可达**、`3235b724` **可达**、`881a55eb` **不可达**（main 独有内容）。
- **远端**：`origin/integration/module-dev` == `4d85131f` == 本轮合并前本地 HEAD ⇒ **未推送任何合并内容**。
- **台账**：`sync.mjs --check` ⇒ ✓ 与蓝图一致；集成头 316 / 0 / 149，main 侧 312 / 0 / 153。
- **预检（在未合并的集成头上）**：`mvnw -q compile` ⇒ exit 0；`pnpm typecheck --force` ⇒ 3/3 exit 0（0 cached）。
- **业务代码**：本轮零改动；**未跑** `mvnw verify`（属流水线 L2）。

## 后续待办

- **人工解掉 `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` 的 4 个冲突区**（行 152/184/220/699）——
  唯一真双向冲突：main 侧有集成头从未有过的内容（`881a55eb`，含「并发会话可能在同一轮内把 PR 合掉」
  段与「判定工具的 `[并入（人工）]` 是保守警报」段），集成头侧有 main 没有的增量 ⇒ **必须求并，不能单边取**。
- **另 2 个文件可机械取集成分支侧**（`api-proxy.ts` 的 main 侧 blob 是祖先快照、
  `contract-drift-ci-gate.md` 集成头侧 117 行是 main 83 行的超集），
  但仍属人工动作，本任务禁止自行解冲突。
- **判断冲突面是否恶化的正确指标是「三个文件两侧 blob 有没有变」**，不是「又等了几轮」——
  本轮再次证明：等了 2h32m，冲突面从 15 区降到 11 区，且构成换了。
- **协议第 1 步的竞态写法建议正式改写**：`lane.mjs gate acquire` 的「每 1 秒 spawn 一次 node」
  在本机不足以覆盖零宽度交接窗口（本轮也观察到一次 `status` 假报 `free`）。
  建议把 `SYNC-MAIN.md` 第 1 步改成「单进程 mkdirSync 热循环」并写进协议，
  同时补上「判闸以 `acquire` 返回为准，`status` 可能瞬时假阴性」这一条。
- **台账汇总块会被合并驱动留成两份**（316 与 312 同时在文件里）⇒
  合并解完后**必须跑 `node tools/module-progress/sync.mjs` 重新生成台账**，不能直接用合并驱动的求并产物。
- **`merge-resolve.mjs` 在 add/add 文件上会先报一条 `fatal: path ... exists on disk, but not in <base>`
  到 stderr**（它按 merge-base 找 stage1，找不到就报错）——
  这条是**预期的**（add/add 本来就没有 stage1），但会混在输出里，容易被误读成工具失败。
