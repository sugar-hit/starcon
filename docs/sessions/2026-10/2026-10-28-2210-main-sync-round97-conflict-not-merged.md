# 2026-10-28: 集成头同步 main 第 97 轮 —— 冲突未并入（26 个，同墙第 18 轮）＋ 等闸 1 小时 47 分

> 20:15 起跑、**22:00:10 取到闸**（TTL 到期接管）、22:1x 收尾。**结果仍是「冲突未并入」**，
> 冲突面 **26 个**（25 add/add + 1 content），与第 93/94/95/96 轮**逐个同名同类型**。
> main tip 仍是 `823ebca5`（同一颗 squash），**已连续 18 轮（第 79–97 轮）撞同一堵墙**。
>
> **本轮两个新结论**：
> ① 第 96 轮报的「远端集成头双向分叉」**已被流水线解决**（远端 tip 现已是本地祖先 ⇒ push 不再被 non-fast-forward 拒）；
> ② 流水线第 123 轮 L2 仍**红**（`identity` 2 例失败），但失败面已从 38 例收窄到 2 例。

## 摘要

| 项                     | 值                                                                          |
| ---------------------- | --------------------------------------------------------------------------- |
| 结果                   | **冲突未并入**（26 个冲突，25 add/add + 1 content）                          |
| **并入的 main 提交数** | **0**（冲突中止，无 merge commit）                                          |
| main 新提交            | **无**（`origin/main` tip 仍是 `823ebca5`，与第 85–96 轮同一颗 squash）     |
| 差异规模               | 1 个提交 / 1032 个文件（+510134 / −45765）                                  |
| 台账                   | **424 已交付 / 0 进行中 / 41 未开发**（465 行，本轮 `sync.mjs` 重跑**零 diff**） |
| 台账变化               | **无**                                                                      |
| 冲突                   | 26 个（见「三、冲突面」）                                                   |
| 预检                   | **绿**（`./mvnw -q compile` rc=0；`pnpm typecheck` 3/3 成功，35.9s）—— 但见 §4 口径提醒 |
| push                   | **未 push**（两条独立理由，任一条都足够，见 §5）                             |
| 本轮提交              | 本会话日志（本文件）· `35f90bac`（回收崩溃 tick 的孤儿说明，§六）        |
| 闸                     | **已释放**                                                                  |
| 等闸时长               | **约 1 小时 45 分**（20:15 → 22:00:10，TTL 边界接管）                        |

## 一、取闸：等闸 1 小时 45 分（本轮最长的等闸，且闸中间换过手）

首读 `gate status`（20:16）⇒ `held` / `ageSeconds=281` / `ttl=4200` / **`stale=false`**
⇒ 按协议**不抢、不清、不 force**，走**密集 `gate acquire` 竞态**。

### 1.1 持闸者确实是活的：keepalive 在续期

前 20 分钟里 `ageSeconds` **每 ~30 秒归零一次**（281 → 21 → 1 → 28 …），
闸目录里不断出现新文件 `keepalive-204303` / `-204329` / `-204359` / …
⇒ 这是**持闸 tick 的 TTL 续期**（`PIPELINE.md` 第 451 行的 `touch keepalive-<新名字>`，
新建文件 ⇒ 闸目录 mtime 刷新 ⇒ `ageSeconds` 重算）。
⚠️ **`ageSeconds` 会骗人**：判活只能看 `stale` 字段；活性取证要看闸外（`ps`）。

闸外取证（`ps`）在 20:33 与 21:31 两次实测到**活着的流水线 tick**：
`bash .tmp/chain-l2-batch123.sh`（等 identity 修复车道落地 → merge-ready → sync → 全量 L2），
以及该 tick 派出的 `mvnw -pl services/identity -am test`（surefire 正在跑 identity 集成测试）。
⇒ 闸确实被活着的 tick 持有，**等它释放是对的**。

### 1.2 等闸期间严格只读取证，没跑任何构建

全程只做只读动作（`gate status` / `cat owner` / `ps` / `git log` / `git merge-tree`），
**连 `git fetch` 都等到拿到闸之后才跑**（协议要求）。
理由：持闸 tick 的 L2 与本任务的预检共用 `backend/**/target/` 与 Testcontainers PG
⇒ 并发必假红。

### 1.3 持闸 tick 崩了，闸被留到 TTL 到期

- 21:31 起 `keepalive` **停止刷新**（最后一个 `keepalive-205009` @ 20:50:09），
  `ageSeconds` 开始单调爬升（2478 → 2644 → … → 4200+）；
- `owner` 里的 pid `37474` **查无此进程**（但按协议，owner PID 本就不是闸存活证据）；
- `ps` 全仓**零** `mvnw` / `surefire` 存活；
- 该 tick 的链式脚本在 20:49 打出 `STEP A: merge-ready` ⇒
  `✗ 主工作区不干净，拒绝切分支` ⇒ **merge-ready 被硬拒**，
  链式脚本却**继续往下跑**（`STEP B: sync` → `STEP C: L2 全量`）
  ⇒ 跑出的 L2 **不含 identity 修复**（必然复现 403 红点，浪费 30 分钟发热高峰）。
  这条教训已被崩掉的 tick 自己写进了 `docs/sessions/2026-10/2026-10-28-2000-pipeline-round123-worker-quota-death-redispatch.md`。
- 22:00:10 闸龄跨过 TTL（`keepalive-205009` @ 20:50:09 + 4200s = 22:00:09），
  竞态循环**第 1554 次尝试命中**（`ok:true`）⇒ **TTL 边界接管**，与闸龄算式**精确对齐**。

> ⚠️ 与第 96 轮一致：`owner` PID 不是闸存活证据；判活只能用 `stale` 字段。

## 二、main 侧：仍然**没有**新提交

`git fetch origin --prune`（拿到闸之后才跑）后 `origin/main` tip 仍是
**`823ebca5 Integration/module dev (#66)`** —— 与第 85–96 轮**同一颗** squash（**单父**，父为 `6522c497`）。

```
git rev-list --count integration/module-dev..origin/main   ⇒ 1
git diff --stat integration/module-dev...origin/main        ⇒ 1032 files, +510134 / −45765
git merge-base integration/module-dev origin/main           ⇒ 6522c497
git merge-base --is-ancestor origin/main integration/module-dev ⇒ 失败 ⇒ HAS_UPDATE
```

⇒ **要同步 1 个提交**，但它是**集成头自己的旧快照**（squash），不是 main 侧的新交付。

### 2.1 独立复核：main 就是集成头的一份旧快照

台账逐模块 ID + 状态 join（两边各 465 行，模块集合完全相同）：

| 项     | main 快照 | 集成头 `2f84b44b` |
| ------ | --------- | ------------------ |
| 已交付 | **406**   | **424**            |
| 进行中 | 0         | 0                  |
| 未开发 | 59        | **41**             |

- 状态不同的模块 **18 个**，**全部是集成头领先**；
- **main 领先（main 已交付、集成头未交付）= 0**
  ⇒ **main 在 465 个模块里没有任何一个是集成头没有的**，差距只增不减。

## 三、冲突面（26 个 = 第 93/94/95/96 轮逐个同名同类型）

动手前先用**只读的 `git merge-tree --write-tree --name-only`** 干跑（不碰工作区、不碰索引），
预测冲突集合与真实 merge **完全一致（26 个，集合逐个相同，无遗漏无多余）**。

✅ **契约与台账两类文件由 `merge-union` 驱动自动求并成功**（1032 个文件里的绝大部分）：
`packages/api-contracts/src/contracts/{channel,identity,reporting}.ts`、
`packages/api-contracts/src/{channel,identity,reporting}.openapi.json`、
`docs/product/capability-blueprint/module-progress.md`（按模块 ID 求并集，465 行同 ID 记录折叠）。

❌ **26 个文件冲突**（协议规定**不自行解**）：

| # | 文件 | 冲突类型 |
| --- | --- | --- |
| 1 | `apps/web/src/components/dashboard/workbench-card-registry.ts` | add/add |
| 2 | `apps/web/src/components/dashboard/workbench-layout-model.test.ts` | add/add |
| 3 | `apps/web/src/lib/api-proxy.ts` | **content** |
| 4 | `backend/services/channel/src/test/java/com/starcon/channel/DataGovernanceIntegrationTest.java` | add/add |
| 5 | `backend/services/channel/src/test/java/com/starcon/channel/DataGovernanceTenancyIntegrationTest.java` | add/add |
| 6 | `backend/services/channel/src/test/java/com/starcon/channel/FilingCalendarIntegrationTest.java` | add/add |
| 7 | `backend/services/channel/src/test/java/com/starcon/channel/FilingCalendarTenancyIntegrationTest.java` | add/add |
| 8 | `backend/services/channel/src/test/java/com/starcon/channel/GlobalSearchGroupIntegrationTest.java` | add/add |
| 9 | `backend/services/channel/src/test/java/com/starcon/channel/GlobalSearchIntegrationTest.java` | add/add |
| 10 | `backend/services/channel/src/test/java/com/starcon/channel/LinkageRiskIdentityStub.java` | add/add |
| 11 | `backend/services/channel/src/test/java/com/starcon/channel/LinkageRiskIntegrationTest.java` | add/add |
| 12 | `backend/services/channel/src/test/java/com/starcon/channel/LinkageRiskTenancyIntegrationTest.java` | add/add |
| 13 | `backend/services/channel/src/test/java/com/starcon/channel/SalesTaxIntegrationTest.java` | add/add |
| 14 | `backend/services/channel/src/test/java/com/starcon/channel/SalesTaxTenancyIntegrationTest.java` | add/add |
| 15 | `backend/services/channel/src/test/java/com/starcon/channel/SyncHealthIntegrationTest.java` | add/add |
| 16 | `backend/services/channel/src/test/java/com/starcon/channel/SyncHealthTenancyIntegrationTest.java` | add/add |
| 17 | `backend/services/channel/src/test/java/com/starcon/channel/VatIossIntegrationTest.java` | add/add |
| 18 | `backend/services/channel/src/test/java/com/starcon/channel/VatIossTenancyIntegrationTest.java` | add/add |
| 19 | `backend/services/finance/src/test/java/com/starcon/finance/FundAnalysisIntegrationTest.java` | add/add |
| 20 | `backend/services/identity/src/main/java/com/starcon/identity/NotificationInboxController.java` | add/add |
| 21 | `backend/services/identity/src/main/java/com/starcon/identity/NotificationPreferenceService.java` | add/add |
| 22 | `backend/services/identity/src/test/java/com/starcon/identity/ApprovalTodoIntegrationTest.java` | add/add |
| 23 | `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingFinancePort.java` | add/add |
| 24 | `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingUpstreamClient.java` | add/add |
| 25 | `backend/services/trade/src/test/java/com/starcon/trade/AnomalyOrderIntegrationTest.java` | add/add |
| 26 | `backend/services/trade/src/test/java/com/starcon/trade/AnomalyOrderTenancyIntegrationTest.java` | add/add |

（**25 个 add/add + 1 个 content**，与 `git diff --diff-filter=U` 计数、
`git merge-tree --write-tree` 干跑预测的集合**逐个相同**）

> ✅ **与第 96 轮逐个同名同类型**（已程序化核对，非目测）：
> 把第 96 轮日志里那张压缩表（`{a,b,c}` 花括号写法）展开成文件集合后，
> 与本轮 26 个冲突文件**集合完全相同**（双向零差异）⇒ 这堵墙的冲突面**连续 5 轮稳定不变**。

### 3.1 冲突**全部**是线性演进（集成头明显更新）

逐个用 **base / ours / theirs 三段 blob 身份**独立取证（不靠 `git checkout --ours/--theirs` 批量覆盖）：

| 口径 | 结果 |
| --- | --- |
| 集成头独有行（ours 相对 theirs） | **1500 行** |
| main 独有行（theirs 相对 ours） | **76 行** |

- `apps/web/src/lib/api-proxy.ts`（唯一的 content 冲突）：集成头独有 **892 行**、main 独有 **0 行**
  ⇒ **ours 严格超集**；
- `ReportingFinancePort.java` / `ReportingUpstreamClient.java`：main 独有 **0 行**；
- `AnomalyOrderIntegrationTest.java`（main 独有最多的一个，35 行）：
  main 侧是 **JVM 墙钟基准**（`static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC)`），
  集成头侧是 **DB 时钟同源 + 每用例重取 + `dwellFloor` 夹逼 + 「时钟错位开关」**（`DB_CLOCK_SKEW_SECONDS`），
  并把「2026-10-26 L2 批次闸 实际 260106 秒 vs 期望 [259080, 259320]」写成了注释
  ⇒ **取 main 那侧 = 把跨时钟域修复回退掉**（与第 94/95 轮同一判断，**不是语义等价**）。

⚠️ 但**不能因此就批量取 ours**：main 侧确有 76 行独有内容（`SalesTaxIntegrationTest` 的
`parsed_at` 必须是真时刻的断言、`NotificationInboxController` / `NotificationPreferenceService` 各 2-3 行等）
⇒ **仍须逐文件人工核对**。

### 3.2 未解任何冲突（协议硬边界）

`git merge --abort` 已执行：HEAD 回到 `2f84b44b`，**`MERGE_HEAD` 已清**，
`git diff --diff-filter=U` 为空，**未自行解任何冲突**。

## 四、预检：绿，但**检的不是合并结果**

本轮按「冲突即中止」推进（协议第 3 步），**没有 merge commit 可检**；
但为了给流水线留基线信号，仍在**未合并**的 HEAD（`2f84b44b`）上跑了快速预检：

| 检查                     | 结果                                              |
| ------------------------ | ------------------------------------------------- |
| `cd backend && ./mvnw -q compile` | **rc=0**（仅 JDK/Maven 无关的 WARNING）      |
| `pnpm typecheck`         | **3/3 successful**（35.9s，2 个缓存命中）          |

> ⚠️ **口径提醒**：这两个绿只说明**当前未合并的集成头**编译与类型是干净的，
> **不代表「合并后」也是绿的** —— 本轮没有合并发生，所以它们**不能**当作合并结果的预检结论。

## 五、为什么没 push（两条独立理由，任一条都足够）

1. **本轮没有产生任何可发布的合并** —— 冲突即中止，没有 merge commit。
2. **集成头的 L2 是红的** —— `.tmp/l2-batch123.status` ⇒ `backend_verify=1`
   （`identity` 服务 `Tests run: 644, Failures: 2, BUILD FAILURE`，
   失败例：`TenantListIntegrationTest.lastLoginIsARealTimestampWrittenAndReadBack`、
   `TenantListIntegrationTest.switchingTenantStampsOnlyTheNewlyEnteredTenant`）。
   `.tmp/l2-batch107b.status` / `.tmp/l2-batch108.status` 同样是 `backend_verify=1`。
   ⇒ 按 `PIPELINE.md` 硬规定「**L2 没绿绝对不 push**」不能推；
   而 `SYNC-MAIN.md` 开篇写明「**两者不一致时以 PIPELINE.md 为准**」。

> ✅ **本轮相对第 96 轮的好消息**：失败面已从 **38 例收窄到 2 例**，且第 96 轮报的
> 「远端集成头双向分叉 ⇒ push 被 non-fast-forward 拒」**已解决** ——
> `origin/integration/module-dev` = `94192b24`，**已是本地 HEAD 的祖先**
> （`git rev-list --left-right --count` ⇒ `0 123`）⇒ **push 通道重新畅通**，
> 现在唯一还卡着 push 的就是「L2 没绿」。

本轮**没有**跑全量 `mvnw verify`（发热高峰，属流水线 L2）。

## 六、遗留：崩掉的 tick 留下的未提交文件 —— 本轮已回收，**并入通道不再被堵**

集成头 worktree 里原本有 1 个**非本轮产生**的脏文件：

```
 M docs/sessions/2026-10/2026-10-28-2000-pipeline-round123-worker-quota-death-redispatch.md
```

- 是 **20:51 崩掉的调度 tick 自己写的未提交说明**（mtime 20:51:55，此后 80+ 分钟无人再碰）；
- 写它的会话 `mvs_1f9aad3805564f14ac24ec59b3b0a334` 状态 = **aborted（已崩）**，无活着的属主；
- 它的内容恰好记录了「`merge-ready` 会因工作区不干净而硬拒」这条教训；
- `lane.mjs merge-ready` 的干净度检查（`lane.mjs:1027-1029`，`git status --porcelain` 跑在
  **集成头 worktree** 里）会因它**直接 `exit 1`**
  ⇒ **下一轮流水线的并入通道会被它堵住**（崩掉的那 tick 20:49 就撞过这一堵）。

**本轮已回收它**（commit `35f90bac`）—— 理由：

1. **它是孤儿**：属主会话已 aborted，无人会再来提交它；
2. **不 commit 的代价是硬堵**：流水线下一轮的 `merge-ready` 会直接退出 ⇒ 并入通道彻底堵死，
   而这正是 batch-123 那条链式脚本踩过的坑；
3. **内容完整、无 secret**：文件有完整的「涉及文件」收尾段，
   secret 扫描（`ghp_` / `github_pat_` / `api_key` / `password` / `token` / `Bearer`）**零命中**；
4. **符合仓库惯例**：`PIPELINE.md` 第 807 行本身就是
   `git add docs/sessions/$(date +%Y-%m)/` ⇒ 流水线迟早会把它扫进某个批次 commit，
   本轮只是提前做掉，不改变最终落点；
5. **不新增冲突面**：`git cat-file -e origin/main:<path>` ⇒ **main 无同名文件**
   ⇒ 提交它不会给下一轮多加一个 add/add 冲突点（见 backlog「11:45 轮新增」那条教训）。

⇒ 提交后集成头 worktree **干净**（`git status --porcelain` 为空），
**下一轮流水线的 `merge-ready` 可以正常跑**。

## 七、后续待办

1. **P0（发布侧，未变）**：`origin/main` 的 tip 必须是一颗**正常 merge**（双父、基于集成头最新 tip），
   而不是集成头历史的 squash 快照。否则每一轮同步都会撞同一堵墙（已连续 **18 轮**：第 79–97 轮）。
   见 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`。
2. **P0（流水线侧，进展）**：batch 123 的 L2 失败面已从 38 例收窄到 **2 例**
   （`TenantListIntegrationTest` 两个用例），仍红 ⇒ 下一轮应从这两个用例入手。
   注意：**这两个失败也是「时钟域」族**（`last_login` 漂移 8 小时、`switchingTenant` 打戳），
   与 identity 修复车道刚修的 `role_assignment.effective_from` 同族 ⇒ 建议合并成一条钟域修复车道。
3. **P0（流水线侧，本轮已解一半）**：崩掉的 tick 留下的未提交说明文件**本轮已回收**（§六，commit `35f90bac`），
   `merge-ready` 的硬堵已解除；
   **仍未解的是另一半**：`merge-ready` 失败后**链式脚本仍继续往下跑 L2**（20:49 实测差点跑出不含修复的 L2）
   ⇒ 建议 `lane.mjs merge-ready` 的失败语义从「exit 1」升级为
   「调用方必须检查返回值并立即中止」（本轮 §七.8 已记）。
4. **待办（第 94 轮 §1.3，未变）**：持闸会话崩在闸里 ⇒ 闸泄漏到 TTL 到期才被接管。
   **本轮再次实证**（§1.3）⇒ 建议 `lane.mjs` 让 `gate release` 只删自己持有的闸。
5. **待办（第 94 轮 §1.4 / 第 96 轮 §七.5，未变）**：并发 `gate acquire` 竞态循环会让闸被抢回并持有到 TTL。
   本轮**只跑了一个**竞态循环（第一轮 900 次用尽后确认已退出才起第二轮），
   收尾 `gate release` 前已 `ps` 确认**存活竞态循环 = 0** ⇒ 未触发。
6. **协议待补（第 96 轮 §七.6，未变）**：`SYNC-MAIN.md` 冲突段可补一条判据 ——
   **「add/add 不等于真分叉」**：先查 main 侧 blob 是否在集成头历史中原样存在，
   命中即线性演进、可机械取集成分支侧。
7. **协议待补（第 96 轮 §七.7，本轮再次实证通过）**：`SYNC-MAIN.md` 第 1 步可补
   「**闸龄从最新 keepalive mtime 起算**」—— 本轮 `keepalive-205009` @ 20:50:09 + 4200s
   = 22:00:09，与我 22:00:10 的接管时刻**精确对齐**。
8. **【本轮新增】协议可补「merge-ready 失败必须中断后续步骤」**：
   本轮实测链式脚本在 `merge-ready` 被拒后**仍继续**跑 sync 与全量 L2（§1.3），
   等于烧掉一次发热高峰去跑一个**必然红**的 L2 ⇒ 建议 `lane.mjs merge-ready` 的失败
   语义从「exit 1」升级为「调用方必须检查返回值并立即中止」。

## 八、涉及文件

- 新增：本文件（本轮提交；本文件不含自身 commit hash，避免自引用过期）
- 提交（回收孤儿说明，commit `35f90bac`）：
  `docs/sessions/2026-10/2026-10-28-2000-pipeline-round123-worker-quota-death-redispatch.md`
  —— 崩掉的 batch-123 tick 的未提交说明，见 §六
- 未新增任何业务代码（本任务只搬运 commit）
- 未解冲突、未 push、未跑全量 verify
- 闸：22:00:10 取到 → 收尾时释放

## 九、关联

- `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` —— P0 根源
- `docs/backlog/2026-10/2026-10-28-remote-integration-head-divergence.md` —— 第 96 轮新增，**本轮已解决**
- `docs/sessions/2026-10/2026-10-28-1716-main-sync-round96-conflict-not-merged.md` —— 上一轮（同墙）
- `docs/sessions/2026-10/2026-10-28-1548-main-sync-round95-conflict-not-merged.md` —— 上上轮（同墙）
- `tools/module-progress/SYNC-MAIN.md` · `tools/module-progress/PIPELINE.md`
