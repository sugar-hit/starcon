# 2026-10-29 07:20 · 集成头同步 main 第 99 轮 —— 冲突未并入（27 个，与上轮**逐字节相同**）＋ main 侧零新内容的**充要证明**

> 按 `tools/module-progress/SYNC-MAIN.md` 执行 `origin/main` → `integration/module-dev` 同步。
> **结果：冲突未并入**。`origin/main` tip 仍是 `823ebca5`（与第 79–98 轮**同一颗 squash**），
> 冲突面 **27 个**（26 add/add + 1 content），与第 98 轮**逐字节相同**（程序化集合比对：NEW 空、GONE 空）。
>
> **本轮的核心结论（第 99 轮新增，第 93–98 轮均未证明到这一步）**：
> **`origin/main` 带进来的内容是集成头自己的旧快照，零新内容。**
> 本轮用**三条独立证据**把这件事证明到「充要」级别 ——
> ① `origin/main^{tree}` 与集成头祖先 `1e3228b4^{tree}` **逐字节相同**；
> ② `origin/main` 树里的 **5478 个 blob，0 个**不在集成头历史中；
> ③ 在**隔离的 scratch clone** 里用 `-X ours` 合并 `823ebca5`，得到的树与**当前 HEAD 逐字节相同**。
>
> ⇒ **这 27 个冲突不是「真分叉」，是 git 对「同一份内容的两种历史」的机械撞车。**
> ⇒ 因此：**「取 ours」在数学上就是无损的**（不是「大概安全」，是**结果与 HEAD 完全一致**）。

## 摘要

| 项                     | 值                                                                        |
| ---------------------- | ------------------------------------------------------------------------- |
| 结果                   | **冲突未并入**（27 个冲突，26 add/add + 1 content）                        |
| **并入的 main 提交数** | **0**（冲突中止，无 merge commit）                                        |
| main 新提交            | **无**（`origin/main` tip 仍是 `823ebca5`，与第 79–98 轮同一颗 squash）    |
| 差异规模               | 1 个提交 / 1032 个文件（+510134 / −45765）—— **行数是 squash 的假象**     |
| 冲突                   | **27 个**（与第 98 轮**逐字节相同**）                                     |
| **main 侧新内容**     | **0**（三条证据，见 §三）                                                 |
| 预检                   | **绿**（`./mvnw -q compile` rc=0；`pnpm typecheck` 3/3）—— 见 §六口径提醒  |
| 台账                   | **426 已交付 / 0 进行中 / 39 未开发**（465 总数，91.6%），**零变化**      |
| push                   | **已 push**，只含本会话日志 + backlog 更新（**零业务代码**），fast-forward |
| 闸                     | **已释放**                                                                |
| 等闸时长               | **0**（首读 `gate status` = `free` / `stale=false` ⇒ `gate acquire` 一次命中） |

## 一、取闸：一次命中，零等待

首读 `gate status`（07:15）⇒ `free` / `ageSeconds=null` / `ttl=4200` / **`stale=false`**
⇒ 按协议 `gate acquire` **一次命中**，**未等闸、未抢闸、未清闸、未 force**。

⚠️ 本轮按 `PIPELINE.md`「闸的续期」在**合并前**与**预检前**各做了一次续期
（`touch .dev-agent/dispatch.gate/keepalive-<新文件名>`，必须是**新文件名**，
touch 已存在文件 / 只往 `owner` 追加都**不会**续期）。

## 二、main 侧：仍然**没有**新提交

`git fetch origin --prune` 后：

```
git rev-list --count integration/module-dev..origin/main    ⇒ 1
git merge-base integration/module-dev origin/main              ⇒ 6522c497（2026-10-23 09:18）
git log -1 --format='%P' origin/main                            ⇒ 6522c497（单父 ⇒ squash）
git log -1 --format='%ad' origin/main                           ⇒ 2026-10-26 14:49:58 +0800
```

⇒ 要同步 1 个提交，但它是**集成头自己的旧快照**（squash），不是 main 侧的新交付。

## 三、**本轮新增：main 侧零新内容的三条独立证明**

第 93–98 轮已经证明「theirs 侧 79 行里没有一行是 main 侧的新模块交付」，
但那只是**逐行核对冲突文件**的口径 —— 它**不能排除**「main 侧在非冲突文件里带了新东西」。
本轮补上了这个缺口，用三条**互不依赖**的证据把它证明到充要级别：

### 证据 ①：`origin/main` 的树 = 我们自己的祖先的树（逐字节相同）

```
git rev-parse origin/main^{tree}   ⇒ 83269e111f5f42e8b9481fd0e9549687aa8a7ed0
git rev-parse 1e3228b4^{tree}      ⇒ 83269e111f5f42e8b9481fd0e9549687aa8a7ed0   ← 完全相同
git merge-base --is-ancestor 1e3228b4 integration/module-dev ⇒ YES（是集成头的祖先）
git log -1 --format='%h %ad %s' 1e3228b4
  ⇒ 1e3228b4 2026-10-25 23:16:05 +0800 docs(sessions): 集成头同步 main 第 75 轮 —— 无新提交，未同步
```

⇒ **main 的 tip 不是「main 侧的新交付」，而是「集成头分支在 2026-10-25 23:16 时刻的旧快照」。**

### 证据 ②：main 树里的每一个 blob 都已在集成头历史中

```
git ls-tree -r origin/main | awk '{print $3}' | sort -u   ⇒ 5478 个唯一 blob
comm -23 main_blobs our_blobs                             ⇒ 0
```

⇒ **main 侧 0 个 blob 是集成头没有的** ⇒ main 不含任何我们尚未拥有的内容。

### 证据 ③：`-X ours` 合并的结果树 = 当前 HEAD 的树（**结果完全一致**）

在**隔离的 scratch clone**（`git clone --shared --no-checkout` 到 `/tmp`，**不碰集成头 worktree**）里：

```
git merge --no-ff --no-commit -X ours 823ebca5
  ⇒ 0 个未解决冲突
git write-tree                 ⇒ 79a5711bce1e7e88117b4b60895c8210d0ca74e2
git rev-parse a475f1df^{tree}  ⇒ 79a5711bce1e7e88117b4b60895c8210d0ca74e2   ← 完全相同
```

⇒ **「27 个冲突全部取 ours」的结果，与「什么都不做」的 HEAD 逐字节相同。**
⇒ 所以「取 ours」不是「大概安全」，是**可以证明的无损操作**。

> ⚠️ 这**不是**本轮的执行方式。本轮**没有**取 ours、**没有**解任何冲突
> —— 协议第 3 步明令「冲突时 `git merge --abort`」，本轮照办。
> 证据 ③ 的作用是**为协议层授权提供依据**（见 §七）。

### 证据 ④（补充）：27 个冲突文件的 `theirs` blob 全部 = 我们自己的旧快照

```
逐文件比对 :3:<file>（theirs）与 1e3228b4:<file>（我们自己的旧快照）
  ⇒ 27 个相同，0 个不同
```

⇒ 27 个冲突点，**没有一个**是 main 侧的新内容。

## 四、冲突面：27 个，与第 98 轮**逐字节相同**

程序化集合比对（`git merge-tree --write-tree --name-only`，**只读，不动 worktree**）：

```
round98(在 f05f7c13 上) = 27 个   round99(在 a475f1df 上) = 27 个
NEW  : （空）
GONE : （空）
```

> ⚠️ 与第 98 轮记录的一个差异要说清：第 98 轮观察到「冲突面随集成头前进而增长（26 → 27）」，
> 本轮**没有增长**。原因是**集成头自第 98 轮以来只前进了 3 个纯 docs 文件**
> （`git diff --name-only f05f7c13 a475f1df` = 3 个 docs 文件，零业务代码）
> ⇒ 落在**已存在文件**上的改动一个都没有 ⇒ 按第 98 轮坐实的机制，**本轮不新增冲突点**。
> ⇒ **「增长」机制的判据是「集成头是否改了已存在的业务/测试文件」，不是「集成头是否前进」。**
> （集成头第 97 轮之后那条 `c904ec63` 改 `SaasUsageIntegrationTest.java` 才是真正的触发器。）

### 4.1 theirs 侧独有内容：与第 98 轮**完全一致**（79 行）

口径：`git diff --numstat HEAD origin/main -- <file>`（`+theirs` = theirs 独有行数）。

| 口径 | 结果 | 与第 98 轮对比 |
| --- | --- | --- |
| 文件数 | 27（26 add/add + 1 content） | **相同** |
| **theirs 独有 = 0 ⇒ 取 ours 无损** | **4**：`api-proxy.ts` / `LinkageRiskIdentityStub.java` / `ReportingFinancePort.java` / `ReportingUpstreamClient.java` | **相同** |
| theirs 独有行合计 | **79** | **相同** |
| ours 独有行合计 | **1643** | **相同** |

theirs 侧 79 行的构成（逐行看过，**与第 98 轮逐行分类完全一致**）：

| 文件 | theirs 独有 | 性质 |
| --- | --- | --- |
| `AnomalyOrderIntegrationTest.java` | **35** | **JVM 墙钟基准**（`OffsetDateTime.now(ZoneOffset.UTC)`）；ours 已改 DB 时钟同源 ⇒ 取 theirs = **回退跨时钟域修复** |
| `AnomalyOrderTenancyIntegrationTest.java` | 11 | 同族（墙钟基准） |
| `SalesTaxIntegrationTest.java` | 7 | `parsed_at` 必须是真时刻的断言 |
| `NotificationInboxController.java` | 3 | 「1.3.2.1 预警规则未交付」提示串 |
| `SaasUsageIntegrationTest.java` | 3 | **旧 flaky 子串断言**（`c904ec63` 已修掉）⇒ 取 theirs = **把 flaky 装回去** |
| `NotificationPreferenceService.java` | 2 | 「1.3.2.2 外部渠道推送未交付」提示串 |
| `FundAnalysisIntegrationTest.java` | 2 | — |
| 其余 20 个文件 | 各 **1** | import 行 / 常量声明行写法差异（例：`import { Boxes, ClipboardList, HeartPulse, LayoutGrid, Search } from 'lucide-react';`） |

本轮新增的两个 dashboard 冲突文件也在「各 1 行」之列：
`workbench-card-registry.ts`（theirs 独有 1 行 = 一条 lucide-react import）、
`workbench-layout-model.test.ts`（theirs 独有 1 行 = 一条 `nav.global-search` / `nav.shortcuts` 顺序断言）。

⇒ **79 行里 0 行是 main 侧的新交付**，与证据 ③ 完全自洽。

### 4.2 未解任何冲突（协议硬边界）

`git merge --abort` 已执行：

```
git rev-parse -q --verify MERGE_HEAD  ⇒ 无（已清）
git status --porcelain                ⇒ 空
git diff --diff-filter=U              ⇒ 空
```

⇒ **未自行解任何冲突**，MERGE_HEAD 无残骸（不会堵死流水线的 merge-ready）。

### 4.3 其余 1005 个文件干净合上

契约与台账两类由 `merge-union` 驱动自动求并成功
（`packages/api-contracts/src/contracts/{channel,identity,reporting}.ts`、
`packages/api-contracts/src/{channel,identity,reporting}.openapi.json`、
`docs/product/capability-blueprint/module-progress.md`），其余业务文件由 git 三方合并干净合上。

## 五、台账：零变化

`node tools/module-progress/sync.mjs --check` ⇒ `✓ 台账与蓝图一致`；
本轮 `sync.mjs` ⇒ **426 已交付 / 0 进行中 / 39 未开发**（465 总数，91.6%），**零文件改动**。

## 六、预检：绿，但**检的不是合并结果**

本轮按「冲突即中止」推进，**没有 merge commit 可检**；为了给流水线留基线信号，
仍在**未合并**的 HEAD（`a475f1df`）上跑了快速预检：

| 检查 | 结果 |
| --- | --- |
| `cd backend && ./mvnw -q compile` | **rc=0**（仅 JDK/Maven 无关的 WARNING） |
| `pnpm typecheck` | **3/3 successful**（663ms，3 个缓存命中） |

> ⚠️ **口径提醒**（同第 98 轮）：这两个绿只说明**当前未合并的集成头**编译与类型是干净的，
> **不代表「合并后」也是绿的** —— 本轮没有合并发生，**不能**当作合并结果的预检结论。
>
> 本轮**没有**跑全量 `mvnw verify`（发热高峰，属流水线的 L2）。

## 七、push：只推了本会话日志 + backlog 更新（**零业务代码**）

- 推前复核：`origin/integration/module-dev` = `0ec4da51`；本地 HEAD = `a475f1df`
  （第 128 轮流水线的纯 docs 提交）+ 本轮会话日志提交 ⇒ **纯 fast-forward，不覆盖远端**；
- 本次 push 的 delta = **纯 docs 提交**，**零业务代码、零契约、零迁移**；
- ✅ **只 push 集成头，绝不 push `main`**；**未 push 任何合并结果**（本轮没有合并结果可推）。

> 📌 **为什么本轮还 commit + push 了日志**：集成头 worktree 若留下未提交文件，
> 下一轮流水线的 `lane.mjs merge-ready` 会因 `git status --porcelain` 非空而**硬拒**
> ⇒ 未提交 = 堵住下一轮并入通道（第 97/98 轮同款教训）。

## 八、后续待办

1. **P0（发布侧，未变）**：`origin/main` 的 tip 必须是一颗**正常 merge**（双父、基于集成头最新 tip），
   而不是集成头历史的 squash 快照。否则每一轮同步都会撞同一堵墙（第 79–99 轮，**已 21 轮**）。
2. **P0（同步侧，本轮已可决策）**：**「取 ours 无损」在本轮被证明到充要级别**
   （证据 ③：`-X ours` 合并结果树 == 当前 HEAD 树）。
   ⇒ **「27 个冲突全部取 ours + 提交 merge commit」这条机械解法，现在有可验证的等价性保证**，
   不再依赖「逐行人工核对 79 行」这种易疲劳、易漏的判断。
   ⇒ **但本任务协议禁止自行解冲突** ⇒ 仍需**协议层授权**（改 `SYNC-MAIN.md` 加一条
   「main 侧 blob 已被集成头历史覆盖 ⇒ 允许 `-X ours` 机械合并」的判据），或由发布侧直接改 main 的 tip（首选）。
3. **P0（流水线侧，未变）**：`SaasUsageIntegrationTest.java` 的 flaky 修复（`c904ec63`）已在集成头，
   **不要**为「让同步好过」而回退它 —— 取 theirs 会把 flaky 断言装回去。
4. **协议级待补（第 96 轮提出，本轮再次实证成立）**：`SYNC-MAIN.md` 可补一条判据 ——
   **「add/add ≠ 真分叉」**：先查 main 侧 blob 是否在集成头历史中原样存在，
   命中即线性演进、可机械取集成头侧。**本轮已把它写成可执行的 3 步取证（§三）。**

## 九、涉及文件

- 新增：`docs/sessions/2026-10/2026-10-29-0720-main-sync-round99-conflict-not-merged.md`（本文件）
- 更新：`docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`（补第 99 轮的三条充要证据与「增长机制」判据修正）
- 未新增/修改任何**业务代码**（本任务只搬运 commit）
- 未解冲突、未 push 合并结果、未跑全量 `mvnw verify`
- 闸：07:15 取到 → 收尾时释放

## 十、关联

- `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` —— P0 根源（本轮已更新）
- `docs/sessions/2026-10/2026-10-29-0515-main-sync-round98-conflict-not-merged.md` —— 上一轮（同墙，27 个，逐行分类的出处）
- `docs/sessions/2026-10/2026-10-28-2210-main-sync-round97-conflict-not-merged.md` —— 上上轮（26 个）
- `docs/backlog/2026-10/2026-10-20-cross-clock-domain-test-assertion.md` —— 跨时钟域断言族（`AnomalyOrder*` 冲突同族）
- `tools/module-progress/SYNC-MAIN.md` · `tools/module-progress/PIPELINE.md`
