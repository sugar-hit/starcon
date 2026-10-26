# 2026-10-26: 集成头同步 main 第 82 轮 —— 冲突未并入（9 个，与第 79/80/81 轮逐个一致）

## 摘要

本轮**结果是冲突未并入**：取到闸 → `git fetch origin --prune` → `merge-base --is-ancestor` ⇒ `HAS_UPDATE`
⇒ `git merge --no-ff --no-commit origin/main` **报 9 个冲突** ⇒ 按协议 `git merge --abort`（退出码 0，无 `MERGE_HEAD` 残留）
⇒ **没有预检、没有 push、没有产生 merge commit**，并入的 main 提交数 = **0**。

冲突面与第 79 / 80 / 81 轮**完全同形（9 个）**：文件清单逐个一致，连「ours / theirs 行数」都**一模一样**
（207/184、293/286、12668/12470、932/925、166/166、425/425、241/192、737/610、365/345）⇒
同一 `823ebca5` + 同一集成头 ⇒ 同一结果，**本轮是第 4 次复现**。

与前三轮不同的是：**本轮闸没有争用**（起手 `gate status` = `free`，当场取到），
所以本轮把全部精力放在**根因取证**上，并用**可复核的独立证据**确认了一件事：
**main 侧内容是集成头的严格更旧子集，且合并方向在这 9 个文件上是反的**（见结论 2、3、4）。

## 决策 / 结论

1. **`origin/main` 自第 79 轮（2026-10-26 17:41）起没有前进，仍停在 `823ebca5`（squash 形态）。**

   | 项 | 值 |
   | --- | --- |
   | `origin/main` tip | `823ebca5`「Integration/module dev (#66)」 |
   | 父 | **仅 1 个**：`6522c497`（= 与集成头的 merge-base）⇒ **squash，不是 merge commit** |
   | 待并入提交数 | **1** |
   | 集成头 tip（合并时） | `e0a9c3c3`「docs(sync): 第 81 轮 main→集成头同步 …」 |
   | 集成头相对 merge-base 的提交数 | **1052**（第 81 轮时为 1049，+3） |
   | 与集成头的树差异（2-dot） | **90 文件 / +6702 / −33832** |
   | 3-dot diff 规模 | 1032 文件 / +510134 / −45765 |
   | 并入的 main 提交数 | **0**（冲突中止） |

2. **本轮独立复核「main ⊆ 集成头」：结论成立，且比第 81 轮多一层证据。**

   方向 `git diff integration/module-dev origin/main`：

   | 类别 | 数量 | 含义 |
   | --- | --- | --- |
   | `A`（只存在于 main） | **0** | **main 没有任何一个文件是集成头没有的** |
   | `D`（只存在于集成头） | **73** | main 的快照更旧，缺后来新增的模块文件 |
   | `M`（内容不同） | **17** | 见结论 3 |

   ⇒ **9 个冲突全部是「squash 压合 vs 线性历史」的历史形状产物**，不是「main 带进来新代码、和集成头真冲突」。

3. **17 个 `M` 文件逐个比对「main 侧独有行」：全部是旧快照或纯格式差异，没有一条是 main 的新功能。**

   | 文件 | main 独有行数 | 性质 |
   | --- | --- | --- |
   | `apps/web/src/lib/api-proxy.ts` | **0** | 集成头是**严格超集**（+198 行） |
   | `backend/services/reporting/.../ReportingFinancePort.java` | **0** | 集成头是**严格超集**（+49 行，收款单 12.2.1.1） |
   | `packages/api-contracts/src/contracts/{identity,reporting}.ts` | **0** | 集成头严格超集 |
   | `packages/api-contracts/src/{identity,reporting}.openapi.json` | **0** | 集成头严格超集 |
   | `apps/web/src/components/dashboard/workbench-card-registry.ts` | 1 | 旧快照 |
   | `apps/web/src/components/dashboard/workbench-layout-model.test.ts` | 1 | 旧快照 |
   | `backend/services/finance/.../FundAnalysisIntegrationTest.java` | 2 | 旧快照 |
   | `backend/services/identity/.../NotificationInboxController.java` | 3 | **交付声明「未交付」（旧）** |
   | `backend/services/identity/.../NotificationPreferenceService.java` | 2 | **交付声明「未交付」（旧）** |
   | `backend/services/trade/.../AnomalyOrderIntegrationTest.java` | 34 | 旧快照 |
   | `backend/services/trade/.../AnomalyOrderTenancyIntegrationTest.java` | 10 | 旧快照 |
   | `backend/services/channel/.../AccessSecurityIntegrationTest.java` | 1 | **纯格式**：同一段 SQL 的换行切法不同 |
   | `backend/services/channel/.../AccessSecurityTenancyIntegrationTest.java` | 5 | **纯格式**：同上 + import 写法不同 |
   | `backend/services/channel/.../LogisticsExceptionIntegrationTest.java` | 1 | **旧快照，且是已被修掉的缺陷**（见结论 4） |
   | `docs/product/capability-blueprint/module-progress.md` | 9 | 台账旧快照（已被 `merge-union` 按模块 ID 求并集折叠） |

4. **🔴 本轮新证据：main 侧带着一个集成头**已经修掉**的日期脆弱性缺陷 ⇒ 合并方向在这条上是反的。**

   `backend/services/channel/src/test/java/com/starcon/channel/LogisticsExceptionIntegrationTest.java`：

   ```
   main（823ebca5）      : static final OffsetDateTime RECENT_AT = OffsetDateTime.parse("2026-10-19T08:00:00Z");
   集成头（e0a9c3c3）    : static final OffsetDateTime RECENT_AT = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
   ```

   对应集成头提交 **`ac583a14`**「fix(test): 轨迹异常「未命中票」夹具改成相对当前时刻 —— 消除「攒够 7 天自己红」的日期脆弱性」
   （2026-10-26 20:58，**在本轮之前**）。

   该夹具语义是「**未命中票**：最后一次轨迹是昨天」，被测规则是「停滞超过 **7** 天」。
   写死成绝对日期后，这张票在 **2026-10-26T08:00:00Z（= 北京时间 16:00）** 就攒够了 7 天 ⇒ 变成命中票 ⇒ 测试转红。
   **该时刻已在本轮之前过去**（本轮为 2026-10-26 21:18 CST = 13:18 UTC）。

   ⇒ **若把 main 的内容合进集成头，等于把这个已修掉的缺陷重新引入。**
   ⇒ 该文件本轮**未进冲突列表**（main 相对 base 没改这一行 ⇒ 3-way 自动取 ours，安全），
   但它证明了「main 的内容不比集成头新」这条判断**有具体反例支撑**，而不是靠行数猜。

5. **9 个冲突的构成：8 个 add/add + 1 个 content（与第 79 / 80 / 81 轮逐个一致）。**

   | 冲突文件 | 类型 | ours 行 | theirs 行 | theirs 独有行 | ours 独有行 | 性质 |
   | --- | --- | --- | --- | --- | --- | --- |
   | `apps/web/src/components/dashboard/workbench-card-registry.ts` | add/add | 207 | 184 | 1 | 24 | 语义（集成头更新） |
   | `apps/web/src/components/dashboard/workbench-layout-model.test.ts` | add/add | 293 | 286 | 1 | 8 | 语义（集成头更新） |
   | `apps/web/src/lib/api-proxy.ts` | **content** | 12668 | 12470 | **0** | 198 | 语义（集成头**严格超集**） |
   | `backend/services/finance/.../FundAnalysisIntegrationTest.java` | add/add | 932 | 925 | 2 | 9 | 语义（集成头更新） |
   | `backend/services/identity/.../NotificationInboxController.java` | add/add | 166 | 166 | 3 | 3 | **双向各改（1.3.x 交付声明）** |
   | `backend/services/identity/.../NotificationPreferenceService.java` | add/add | 425 | 425 | 2 | 2 | **双向各改（1.3.2.x 交付声明）** |
   | `backend/services/reporting/.../ReportingFinancePort.java` | add/add | 241 | 192 | **0** | 49 | 语义（集成头**严格超集**：收款单 12.2.1.1） |
   | `backend/services/trade/.../AnomalyOrderIntegrationTest.java` | add/add | 737 | 610 | 34 | 162 | 语义（集成头更新） |
   | `backend/services/trade/.../AnomalyOrderTenancyIntegrationTest.java` | add/add | 365 | 345 | 10 | 31 | 语义（集成头更新） |

   双向冲突的差异方向本轮**再次逐行确认**（集成头侧是当前真相，main 侧是压合那一刻的旧快照）：

   ```
   NotificationInboxController.java
   < (theirs/main)  "1.3.2.1 预警规则未交付"
   > (ours/集成头)   "1.3.2.1 预警规则已交付（命中产出归它，本接口只消费已入箱的站内通知）"
   ```

   ⇒ 这不是「两份都对、随便挑一份」的对等冲突，而是**新旧快照差**：
   main 说未交付，是因为压合那一刻它们确实还没交付；集成头说已交付，是因为它们**现在**确实已交付。

6. **`merge-union` 驱动器仍只兜底契约与台账两类文件**，行为与第 79 / 80 / 81 轮一致：
   - `docs/product/capability-blueprint/module-progress.md` 按模块 ID 求并集，折叠了 465 行同 ID 记录；
   - `packages/api-contracts/src/contracts/{identity,reporting}.ts` 与 `{identity,reporting}.openapi.json` 自动并集；
   - ⇒ 剩下 9 个全是**代码 / 测试**文件，需要人工语义裁决，**协议不允许本任务自行解**。

7. **`git merge --abort` 干净退出，P0「abort 失败 / `MERGE_HEAD` 残留」未复发。**

   事后核查：`git rev-parse -q --verify MERGE_HEAD` ⇒ **无残留**；
   `git status --porcelain` ⇒ **空**；`git diff --diff-filter=U` ⇒ **0**；
   `HEAD` 回到 `e0a9c3c3`（= 合并前的 `PRE`，逐字节相同）。

8. **本轮不跑预检、不 push。**
   冲突 ⇒ 没有 merge commit ⇒ 没有预检目标 ⇒ 按协议不跑 `./mvnw compile` / `pnpm typecheck`，
   更不 push 集成头（推一个没合成的集成头没有意义）。
   本篇文档**本地 commit、不推**（与第 81 轮同一处置），留给下一轮流水线连同它自己的批次一起推。

9. **台账无变化（未同步 ⇒ 不刷新）。**
   `node tools/module-progress/sync.mjs`（合并前与 abort 后各跑一次）⇒ 两次都是
   「✓ 同步 18 卷 / 465 个 L4 模块 · 已交付 410 · 进行中 0 · 未开发 55」，**零文件改动**（`git status --porcelain` 两次均为空）。
   与第 81 轮的 410 / 0 / 55 相同 ⇒ 本轮同步**没有改变任何进度基线**。

10. **闸：本轮走的是「直接取闸」路径 —— 起手即 `free`，当场取到，未清闸、未 force、未等闸。**

    | 证据 | 实测 |
    | --- | --- |
    | 起手判据 | `gate status` = `free`（`ageSeconds: null` / `stale: false`） |
    | 取闸 | `gate acquire` ⇒ `{"ok": true, "gate": "acquired"}`，owner pid `57916`，时间戳 `2026-10-26T13:15:15.020Z`（= 21:15:15 CST） |
    | 等闸时长 | **0**（与第 81 轮等闸约 66 分钟形成对比） |
    | 闸龄 | 全程 `stale = false`，**未走 TTL 接管**、**未清闸**、**未 force** |
    | 并发取证 | `ps` 全程无 `mvnw` / `surefire` / `lane.mjs` / `sync.mjs` 进程 ⇒ 无并发构建、无并发同步 agent |
    | 收尾 | `gate release`（见「文件清单」下方说明） |

    ⇒ 本轮**未触发**第 81 轮记录的「系统睡眠冻结 keepalive 计时器」边界（该边界只在持闸 tick 跑长任务时出现）。

## 后续待办

1. **main 侧需要一次「用集成头快照刷新 main」的正常合并（**保留祖先链**），而不是让同步任务硬解 9 个冲突。**
   根因是 PR #66 用了 **squash merge**：压合掉了集成分支的祖先链，
   于是每一次「main 落后于集成头」的同步都会在「同源快照」上重新长出冲突。
   ⇒ 若后续 PR 改用 **merge commit**（保留祖先链），本类冲突应当自然消失。
   ⇒ 本轮证据已把「main 更旧」钉死到具体行（含一条已被修掉的日期脆弱性缺陷），
   ⇒ **合并方向应当是「集成头 → main」，不是「main → 集成头」**。
2. **第 79 / 80 / 81 / 82 轮遗留问题依然成立**：`823ebca5` 自 2026-10-26 17:41 起就不再前进，
   而集成头已前进到 `e0a9c3c3`。⇒ 同步通道已连续**四轮**无法把 main 的任何内容并入集成头
   （虽然经取证，main 的内容本来就是集成头的**更旧子集**，**实质上没有新东西需要并入**）。
3. **合并冲突的正确裁决已可机械推导（本轮新增）**：既然 `A(只存在于 main) = 0`，
   且 9 个冲突的 theirs 侧独有行全部是旧快照/纯格式 ⇒ **9 个冲突全部取 ours 即等于「内容 no-op」**。
   ⇒ 这条裁决**应当由人工或由 main 侧的刷新流程去做**，**不由本同步任务执行**（协议禁止自行解冲突）。
   ⇒ 若下轮 main 仍停在 `823ebca5`，可以预期「取 ours」的合并结果与当前集成头内容一致 —— 即**并入 0 个新事实**。
4. **`gate status` 的 `stale` 判据应补一条活性旁证**（第 81 轮实测踩到，本轮未复发）：
   系统睡眠会**冻结**持闸者的 `sleep` 计时器 ⇒ 闸目录 mtime 停止刷新 ⇒ `ageSeconds` 虚高 ⇒
   `stale` 可能为 **true 而持闸 tick 仍然活着**。
   ⇒ 建议 `lane.mjs gate status` 在 `stale=true` 时额外输出「最近 keepalive 文件年龄」，
   并把「TTL 接管」的条件收紧为「超 TTL **且** 无活构建进程」。
5. **同步任务之间需要互斥**（第 81 轮实测，本轮未复发）：
   本轮与第 80 轮同步 agent 曾在同一 worktree 并发，出现「A 的 merge 被 B 的 abort 掉」。
   ⇒ 同步任务取闸后应写入**自己的 owner 标识**，并在动 worktree 前校验「当前闸 owner 是自己」。
6. `lane.mjs` 的 `gate release` 仍是无条件 `rmSync(GATE_DIR)`，并发下会互清；
   `gate status` 的年龄仍取自「闸目录 mtime」而非「最新 keepalive 文件 mtime」。
   （第 79 轮已登记，本轮再次确认。）
7. 本任务**不动冲突**，下一轮再试；若 main 届时仍停在 `823ebca5`，
   预期仍是同样的 9 个冲突（集成头再前进只会让新增声明类冲突变多，不会变少）。

## 文件清单

| 文件 | 动作 |
| --- | --- |
| `docs/sessions/2026-10/2026-10-26-2116-main-sync-round82-conflict-not-merged.md` | 新增（本文件） |

> 本轮**未改任何业务代码**：只搬运 commit（结果为 0 个），未产生 merge commit、未 push 集成头、未 push main。
> 本轮**未跑预检**（冲突中止 ⇒ 无预检目标），因此也**未跑**全量 `mvnw verify`。
