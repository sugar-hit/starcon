# 2026-10-29 05:15 · 集成头同步 main 第 98 轮 —— 冲突未并入（27 个，比上轮 +1）＋ 根因坐实

> 按 `tools/module-progress/SYNC-MAIN.md` 执行 `origin/main` → `integration/module-dev` 同步。
> **结果：冲突未并入**。`origin/main` tip 仍是 `823ebca5`（与第 79–97 轮**同一颗 squash**），
> 冲突面 **27 个**（26 add/add + 1 content），比第 97 轮的 26 个**多 1 个**。
>
> **本轮两个新结论**：
> ① 第 97 轮报的「连续撞同一堵墙」**延长到第 79–98 轮**，且**冲突面会随集成头前进而增长** ——
>    新增的那 1 个冲突**不是 main 侧的新交付**，而是集成头自己第 97 轮之后落的**修复车道**撞上了旧快照；
> ② **根因在本轮被逐文件坐实**：`theirs`（main 侧）在这 27 个文件上的独有内容共 **79 行**，
>    逐行看过之后确认**没有一行是 main 侧的新交付**，全部是「已被集成头后续交付推翻的旧写法」
>    （旧 import、旧常量、旧断言、旧墙钟基准）。

## 摘要

| 项                     | 值                                                                          |
| ---------------------- | --------------------------------------------------------------------------- |
| 结果                   | **冲突未并入**（27 个冲突，26 add/add + 1 content）                          |
| **并入的 main 提交数** | **0**（冲突中止，无 merge commit）                                          |
| main 新提交            | **无**（`origin/main` tip 仍是 `823ebca5`，与第 79–97 轮同一颗 squash）      |
| 差异规模               | 1 个提交 / 1032 个文件（+510134 / −45765）                                  |
| 冲突                   | **27 个**（第 97 轮 26 个 + 新增 1 个，见 §三）                             |
| 预检                   | **绿**（`./mvnw -q compile` rc=0 / 5.7s；`pnpm typecheck` 3/3 / 450ms 缓存命中）—— 但见 §五口径提醒 |
| 台账                   | **426 已交付 / 0 进行中 / 39 未开发**（465 总数，91.6%），`sync --check` **绿**、**零 diff** |
| 台账变化               | **无**                                                                      |
| push                   | **已 push**，但**只含本会话日志 + backlog 更新**（**零业务代码**），fast-forward |
| 闸                     | **已释放**                                                                  |
| 等闸时长               | **0**（首读 `gate status` = `free` / `stale=false` ⇒ `gate acquire` 一次命中） |

## 一、取闸：一次命中，零等待

首读 `gate status`（05:15）⇒ `free` / `ageSeconds=null` / `ttl=4200` / **`stale=false`**
⇒ 按协议 `gate acquire` **一次命中**，**未等闸、未抢闸、未清闸、未 force**。
（与第 93–97 轮「等闸 1–3 小时」形成对照 —— 本轮闸是空的。）

## 二、main 侧：仍然**没有**新提交

`git fetch origin --prune` 后：

```
git rev-list --count integration/module-dev..origin/main    ⇒ 1
git diff --stat integration/module-dev...origin/main         ⇒ 1032 files, +510134 / −45765
git merge-base integration/module-dev origin/main              ⇒ 6522c497（2026-10-23 09:18）
git merge-base --is-ancestor origin/main integration/module-dev ⇒ 失败 ⇒ HAS_UPDATE
git log -1 --format='%P' origin/main                            ⇒ 6522c497（单父 ⇒ squash）
```

⇒ 要同步 1 个提交，但它是**集成头在 2026-10-23 时刻的旧快照**（squash），不是 main 侧的新交付。
（`823ebca5` 的父提交 `6522c497` = 「chore: 同步集成分支最新进度」。）

⚠️ **5032 / +510134 这类行数不要当工作量看** —— 它是 squash 把整段历史重放成一颗提交的行数假象。

## 三、冲突面：27 个 = 第 97 轮的 26 个 **+ 1 个新增**

合并触发冲突后，用 `git diff --name-only --diff-filter=U` 取全量并按 stage 拆类型
（stage 1 缺失 ⇒ add/add；stage 1 存在 ⇒ content），再与第 97 轮日志里的名单做**程序化集合比对**
（不是目测）：

```
round97 = 26 个   round98 = 27 个
NEW  in round98 : backend/services/identity/src/test/java/com/starcon/identity/SaasUsageIntegrationTest.java
GONE in round98 : （无）
```

### 3.1 新增那 1 个冲突的来源：**集成头自己的修复车道**，不是 main 的新交付

```
git log --oneline -3 HEAD -- .../identity/SaasUsageIntegrationTest.java
  ⇒ c904ec63 test(identity): 用量统计租户隔离断言改按字段值核对，消除随机 UUID 引起的假红
git log --oneline -3 origin/main -- .../identity/SaasUsageIntegrationTest.java
  ⇒ 823ebca5 Integration/module dev (#66)
```

`c904ec63` 经 `3e83aa41` 并入集成头（第 97 轮之后）⇒ **新冲突点由集成头前进产生**。

### 3.2 ⚠️ 这 1 个新增冲突是**「取 theirs 会引入回归」**的高危型

`theirs`（main 侧）在这个文件里的独有内容是 **3 行**：

```java
assertThat(forA.toString()).contains("111").doesNotContain("222");
assertThat(forB.toString()).contains("222").doesNotContain("111");
assertThat(report.toString()).contains(tenantA.toString()).contains(tenantB.toString());
```

⇒ 这正是 `c904ec63` **修掉的随机 UUID 子串匹配写法**（原写法因 UUID 随机而 flaky）。
集成头侧已改为**按字段值核对**（`get("tenantCount").asInt()` / `get("status").asText()` 等，无 UUID 子串）。
⇒ **取 theirs = 把刚修掉的 flaky 断言装回去** ⇒ 这一处**必须取 ours**。

### 3.3 全 27 个冲突的 `ours` / `theirs` 行数分布（逐文件实测）

口径：`git diff --numstat HEAD origin/main -- <file>`
⇒ `+theirs` = theirs 独有行数（集成头没有的），`-ours` = ours 独有行数（theirs 没有的）。

| 口径 | 结果 |
| --- | --- |
| 文件数 | 27 |
| **theirs 是 ours 的严格超集（theirs 独有 = 0 ⇒ 取 ours 无损）** | **4**（`api-proxy.ts` / `LinkageRiskIdentityStub.java` / `ReportingFinancePort.java` / `ReportingUpstreamClient.java`） |
| theirs 独有行合计 | **79 行**（其中 23 个文件各 1–3 行） |
| ours 独有行合计 | **1643 行** |

theirs 独有行的**主要来源**（逐行看过）：

| 文件 | theirs 独有 | 性质 |
| --- | --- | --- |
| `AnomalyOrderIntegrationTest.java` | **35 行** | **JVM 墙钟基准**：`static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)` —— 集成头侧已改为 DB 时钟同源。**取 theirs = 把跨时钟域修复回退掉** |
| `AnomalyOrderTenancyIntegrationTest.java` | 11 行 | 同族（墙钟基准） |
| `SalesTaxIntegrationTest.java` | 7 行 | `parsed_at` 必须是真时刻的断言 |
| `NotificationInboxController.java` / `NotificationPreferenceService.java` | 3 / 2 行 | 「1.3.2.1 预警规则未交付」「1.3.2.2 外部渠道推送未交付」两条提示串 |
| `SaasUsageIntegrationTest.java` | 3 行 | **旧 flaky 子串断言**（§3.2） |
| `FundAnalysisIntegrationTest.java` | 2 行 | — |
| 其余 21 个文件 | 各 **1 行** | **几乎全是 import 行 / 常量声明行**的写法差异（例：`static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");`、`import { Boxes, ClipboardList, HeartPulse, LayoutGrid, Search } from 'lucide-react';`） |

⇒ **79 行里没有一行是 main 侧的新模块交付**；全部落在「已被集成头后续交付推翻或改写的旧写法」上。
⇒ 结论与第 93–97 轮一致且**本轮证据更硬**：冲突面虽扩大到 27 个，但**方向未变** —— **以集成头为准**。

### 3.4 未解任何冲突（协议硬边界）

`git merge --abort` 已执行：
HEAD 回到 `f05f7c13`、**`MERGE_HEAD` 已清**、工作区 `git status --porcelain` 为空、
`git diff --diff-filter=U` 为空 ⇒ **未自行解任何冲突**。

### 3.5 1032 个文件里另外 1005 个干净合上

合并日志显示契约与台账两类由 `merge-union` 驱动自动求并成功
（`packages/api-contracts/src/contracts/{channel,identity,reporting}.ts`、
`packages/api-contracts/src/{channel,identity,reporting}.openapi.json`、
`docs/product/capability-blueprint/module-progress.md`），其余业务文件由 git 三方合并干净合上
⇒ **问题源始终是那几十个「add/add 的业务/测试文件」**，不是契约、不是台账。

## 四、台账：零变化

`node tools/module-progress/sync.mjs --check` ⇒ `✓ 台账与蓝图一致`。
本轮**没有合并发生**，故台账无任何变化：**426 已交付 / 0 进行中 / 39 未开发**（465 总数，91.6%）。

> 与第 97 轮的 424/0/41 相比 +2/+0/−2，是**流水线第 127 轮**（batch-110：13.4.1.2 SKU 利润 +
> 13.4.3.1 利润预警）刷出来的，**不是本任务刷的**。

## 五、预检：绿，但**检的不是合并结果**

本轮按「冲突即中止」推进（协议第 3 步），**没有 merge commit 可检**；
但为了给流水线留基线信号，仍在**未合并**的 HEAD（`f05f7c13`）上跑了快速预检：

| 检查 | 结果 |
| --- | --- |
| `cd backend && ./mvnw -q compile` | **rc=0**（5.7s，增量命中；仅 JDK/Maven 无关的 WARNING） |
| `pnpm typecheck` | **3/3 successful**（450ms，3 个缓存命中） |

> ⚠️ **口径提醒**：这两个绿只说明**当前未合并的集成头**编译与类型是干净的，
> **不代表「合并后」也是绿的** —— 本轮没有合并发生，**不能**当作合并结果的预检结论。

本轮**没有**跑全量 `mvnw verify`（发热高峰，属流水线的 L2）。

## 六、push：只推了本会话日志 + backlog 更新（**零业务代码**）

- 推前复核：`origin/integration/module-dev` = `f05f7c13` = 本地 HEAD ⇒ **纯 fast-forward，不覆盖远端**；
- L2 状态：**流水线第 127 轮（今日 05:00）已验真 L2 绿且覆盖的正是 `f05f7c13`**
  （11 个 Maven 模块全 SUCCESS / 后端 11,152 例 0 失败 0 错误 / 前端 673 文件 9,498 例全过 /
  Next build 388/388 / `sync --check` 绿），本轮**未改动任何代码文件** ⇒ 不影响该绿判；
- 本次 push 的 delta = **1 个纯 docs 提交**（本文件 + backlog 更新）；
- ✅ **只 push 集成头，绝不 push `main`**；**未 push 任何合并结果**（本轮没有合并结果可推）。

> 📌 **为什么本轮还 commit + push 了日志**：集成头 worktree 若留下未提交文件，
> 下一轮流水线的 `lane.mjs merge-ready` 会因 `git status --porcelain` 非空而**硬拒**
> ⇒ 未提交 = 堵住下一轮并入通道（第 97 轮 §六 同款教训）。且本仓库惯例是
> `git add docs/sessions/$(date +%Y-%m)/`（`PIPELINE.md`），流水线迟早会扫进某个批次 commit。
> 本轮只是提前做掉，不改变最终落点。

## 七、后续待办

1. **P0（发布侧，未变）**：`origin/main` 的 tip 必须是一颗**正常 merge**（双父、基于集成头最新 tip），
   而不是集成头历史的 squash 快照。否则每一轮同步都会撞同一堵墙
   （第 79–98 轮），**且冲突面随集成头前进而单调增长**（本轮 26 → 27 就是证据）。
   见 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`（本轮已更新）。
2. **P0（同步侧，本轮新增）**：**冲突面增长的新机制已坐实** ——
   **不是 main 侧前进，而是集成头自己的修复车道撞上旧快照**。
   ⇒ 集成头每落一条「改动已存在文件」的修复车道（例如 `c904ec63` 改 `SaasUsageIntegrationTest.java`），
   就会**新增 1 个 add/add 冲突点** ⇒ 撞墙成本会随流水线推进而**加速上升**，不是恒定 26。
   ⇒ 建议在发布侧修复到位前，**同步任务改为按「模块 ID + 状态」做增量同步**
   （只同步 main 侧真正领先且集成头未交付的模块），而不是整树 merge。
3. **P0（同步侧，本轮新增）**：`theirs` 侧独有内容**已逐行分类**（§3.3）
   ⇒ 结论：79 行里 **0 行是 main 侧的新交付**。
   ⇒ 若发布侧短期修不了，**「逐文件取 ours + 人工核对 79 行」的机械解法是安全的**，
   但**本任务协议禁止自行解冲突** ⇒ 需协议层面授权后再做，或由发布侧直接改 main 的 tip。
4. **P0（流水线侧，未变）**：`SaasUsageIntegrationTest.java` 的 flaky 修复（`c904ec63`）已在集成头，
   **不要**为「让同步好过」而回退它 —— 取 theirs 会把 flaky 断言装回去。
5. **待办（第 94–97 轮，未变）**：`lane.mjs gate release` 只删自己持有的闸；
   `merge-ready` 失败必须让调用方立即中止（不要继续跑 L2）。

## 八、涉及文件

- 新增：`docs/sessions/2026-10/2026-10-29-0515-main-sync-round98-conflict-not-merged.md`（本文件）
- 更新：`docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`（补第 98 轮冲突面增长机制与 theirs 侧逐行分类）
- 未新增/修改任何**业务代码**（本任务只搬运 commit）
- 未解冲突、未 push 合并结果、未跑全量 `mvnw verify`
- 闸：05:15 取到 → 收尾时释放

## 九、关联

- `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` —— P0 根源（本轮已更新）
- `docs/sessions/2026-10/2026-10-28-2210-main-sync-round97-conflict-not-merged.md` —— 上一轮（同墙，26 个）
- `docs/sessions/2026-10/2026-10-29-0500-pipeline-round127-stale-takeover-push-backlog.md` —— 流水线第 127 轮（建议由同步侧排查这 26 个冲突的根因 ⇒ 本轮已回应）
- `docs/backlog/2026-10/2026-10-20-cross-clock-domain-test-assertion.md` —— 跨时钟域断言族（本轮 `AnomalyOrder*` 冲突同族）
- `tools/module-progress/SYNC-MAIN.md` · `tools/module-progress/PIPELINE.md`
