# starcon 集成分支并入 main · cron 任务书（每半小时）

> **本文件是 cron 任务的 prompt 来源。协议只存这一份，且在版本控制里。**
> 改策略改文件，不要改 cron prompt 字段。
>
> 本任务与「并行车道开发流水线」（`PIPELINE_CRON_PROMPT.md`）是**两个独立任务**：
> 流水线负责在 `integration/module-dev` 上开发，本任务负责把集成分支**通过 Pull Request 合入 `main`**。
> 两者不共享车道、不共享派发逻辑，但**共享同一个仓库** —— 并发时必须互不干扰。

你每次运行是**全新的独立会话**，不继承任何上下文。
**所有判断必须从仓库现状重新取证，不能凭记忆。**

---

## 0. 项目与一句话职责

项目：`/Users/tommy/Project/starcon`（Java Spring Boot 微服务 + Next.js 双栈，遵守根 `AGENTS.md`）。

**你的职责：把 `integration/module-dev` 合入 `main` —— 具体做法是开一个 Pull Request，
逐个审查并解决冲突，跑完验证，等 CI 绿了再把 PR 合进 `main`，然后如实汇报。**

源分支：`integration/module-dev`（远端 `upstream/integration/module-dev`）
目标分支：`main`（远端 `upstream/main`）
PR 分支：`chore/sync-module-dev`（固定名，复用同一个分支，不每轮新建）

**节奏：每半小时触发一次。** 这意味着**大多数轮次什么都不会发生** ——
没有新交付就安静收工，这是正常且正确的状态，不是失败。

---

## 1. ⛔ 硬性前提（先读这段）

### 1.1 `main` 只经 Pull Request 更新

根 `AGENTS.md` 的禁令是「**不要直接 push `main`**」。本任务**始终遵守**它：

- ❌ 不许 `git push upstream HEAD:main`
- ❌ 不许 `gh pr merge` 之外的任何直推 main 的手段
- ✅ `main` 的每一次变化，都必须来自**一个被合上的 PR**

`main` 在本仓库没有分支保护，**这不构成绕过流程的理由** ——
没有保护恰恰意味着**流程要靠你自己守**。

本任务对「合并 PR」的授权范围：仅限**它自己开的那一个同步 PR**。
除此之外的 PR 一律不碰。

### 1.2 合并 ≠ 合并冲突解决

`main` 是发布分支，合入必须**保证可构建、测试绿**。冲突解决只是其中一环。
**不许「合上了就算完」** —— 冲突解完还要跑验证（见第 5 节），还要等 CI 绿（第 7 节）。

### 1.3 不许把「明天再说」当交付

冲突解不了、验证不过、或者出现了**比 `main` 基线更多的 CI 失败** —— **本轮不合 PR**，
但**必须把卡点写清楚**（哪个文件、什么原因、解除条件是什么）。
**沉默地跳过一轮 = 谎报成功。**

### 1.4 每轮都要「先问有没有东西可做」

半小时一次意味着**大部分轮次无事可做**。本轮的判断顺序必须是：

1. `main` 相对集成分支**有没有新内容**？没有 → 看看有没有开着的同步 PR（可能在等 CI）→ 收工。
2. 有新内容 → 才进合并流程。

**不许每轮都重跑一遍全量验证来「确认没事」** —— 那是发热高峰，且没有信息增量。

### 1.5 ⛔ 契约产物必须拆成两个 PR，PR#1 必须先于 PR#2

**背景（2026-10-22 实测）**：`merge.contract-union.driver` 注册在**仓库本地** `.git/config`，
且注册的是**相对路径** `node tools/module-progress/merge-union.mjs %O %A %B`。
git 执行合并驱动时的工作目录 = **工作树顶层** ⇒ 跑的是**当前检出分支**那份驱动。

第 7.1 节的流程是「从 `upstream/main` 建 worktree → `git checkout -B chore/sync-module-dev`
→ `git merge upstream/integration/module-dev`」。此刻检出的分支基于 `main`，
**跑的是 `main` 那份驱动**。若 `main` 上的驱动还是旧版（横幅写末行 + 类型名不排序），
这次合并会把集成分支里已经 canonical 化的 11 个契约产物**重新写回 JS 形态** ——
结果就是「合进去了，但 CI 的契约校验照样红」。

**所以带契约产物的合并必须分两步走**：

**PR#1 —— 只修工具链，一个契约产物都不许带**。内容白名单：

| 文件                                          | 作用                                |
| --------------------------------------------- | ----------------------------------- |
| `tools/module-progress/merge-union.mjs`       | 横幅归首行 + 类型名字典序           |
| `tools/module-progress/json-format.py`        | JSON 快照统一格式化（与生成器同参） |
| `tools/codegen/catalog-contracts.py`          | `sort_keys=True`                    |
| `tools/module-progress/merge-union.itest.mjs` | 驱动回归测试                        |

`.gitignore` 的 `__pycache__/` **可以随任意一个 PR 带入**；
但**集成分支若已经带了，就不要重复加** —— 两边各加同一行会撞 add/add 冲突。

**PR#2 —— 才带 11 个契约产物**（6 个 `*.openapi.json` + 5 个 `contracts/*.ts`）。

**放行判据（开 PR#2 之前必须为真）**：

```bash
git show upstream/main:tools/module-progress/merge-union.mjs | grep -q 'json-format.py'
```

为 **假** ⇒ `main` 上还是旧驱动 ⇒ **STOP，不许开 PR#2**，先把 PR#1 合了再回来。

**另一条更稳的路（可选）**：本轮只搬契约产物、不想触发任何本地合并时，
把第 7.1 节的 ②③ 换成「不 merge，直接把同步分支指到集成分支」：

```bash
git checkout -B chore/sync-module-dev upstream/integration/module-dev
```

这样驱动根本不跑，产物原样搬运。**但 CI 的 `contracts:generate` 要求 `main` 上的
生成器与产物一致** ⇒ 无论走哪条路，**PR#1 都必须先合**。

**合入后自检（PR#2 合完必跑）**：

```bash
# 横幅在首行
git show upstream/main:packages/api-contracts/src/catalog.openapi.json | head -1
# JS 形态指纹：任何服务都必须为 0
for s in catalog channel crm trade warehouse identity; do
  printf '%s %s\n' "$s" \
    "$(git show upstream/main:packages/api-contracts/src/$s.openapi.json | grep -c '0\.000001')"
done
```

⚠️ **别把「必须有 `1e-06`」当判据**：`1e-06` 只在部分服务出现
（catalog 2 / channel 5 / crm 6；identity / warehouse / trade **一次都没有**），
写成「期望 > 0」会在这三个服务上误判 FAIL。**通用判据只有一条：不得出现 `0.000001`。**

**数量不变量**（变了就是内容丢失 = 回归，不是排版变更）：
导出声明 identity 158 / catalog 863 / channel 1348 / warehouse 1077 / trade 1717 / crm 159；
paths identity 112 / catalog 494 / channel 710 / warehouse 518 / trade 789 / crm 91。

---

## 2. 🔒 并发闸（与流水线共享，必须先查）

本任务与开发流水线共享同一个仓库。**并发合并会造成 `MERGE_HEAD` 残骸和分支指针竞态。**

### 2.1 先读闸状态（只读，永远第一步）

```bash
cd /Users/tommy/Project/starcon && node tools/module-progress/lane.mjs gate status
```

输出含 `gate / ageSeconds / ttlSeconds / stale`，**四个都要看**。

| `gate` | `stale` | 含义                         | 动作                                      |
| ------ | ------- | ---------------------------- | ----------------------------------------- |
| `free` | false   | 闸空闲                       | 可以 `acquire`                            |
| `held` | false   | **有活着的 tick 持着闸**     | **不要抢**。只做只读检查 + 汇报，结束本轮 |
| `held` | true    | 持闸者崩了（超 TTL 70 分钟） | 可以 `acquire` 接管                       |

⚠️ **`stale=false` 不等于持闸者在偷懒** —— 它可能正在跑 L2（实测单轮 40–60 分钟）。
**撞闸（`acquire` 退出码 3）时，正确动作是「本轮零改动 + 只读 + 简短汇报」**，
不是清闸、不是 `force`。崩掉的闸会被 TTL 自动接管（最多挡一轮）。

### 2.2 闸的持有范围

**持闸 = 保护「本地会改状态的动作」**：

```
取闸 → fetch → 合并 → 解冲突 → 验证 → 推分支 → 建/更新 PR → 放闸
```

**CI 在远端跑，不持闸。** 等 CI 的那段时间必须放闸，
否则一轮就把闸占住 30–60 分钟，流水线整轮空转。

**合并 PR 前要再取一次闸**（短持有）—— 避免两轮同时合同一个 PR。
流水线此刻正持闸 ⇒ 退出码 3 ⇒ **本轮不合，下一轮再合**，不许 `force`。

### 2.3 取闸与放闸

```bash
node tools/module-progress/lane.mjs gate acquire   # 退出码 3 = 撞闸
# ……做合并……
node tools/module-progress/lane.mjs gate release     # 收尾必须放，无论成败
```

**本轮若因任何原因提前结束（闸 STOP、撞闸、异常），也必须 `gate release`** ——
你已经拿到闸，不释放就等于把下一轮挡住。

### 2.4 静默窗口

开发流水线的静默窗口是 **20:30 → 次日 08:00**（见 `.dev-agent/PIPELINE.local.md`）。
本任务**全天 24 小时每半小时触发**，必然有一半落在静默窗口内。

**静默窗口管的是「派发新车道」和「跑 L2 发热高峰」，管不住「合入 main」** ——
后者是发布动作。但因此**你要格外节制资源**：

- 合并本身很轻（git 操作），**可以**在静默窗口内做。
- **本地全量 L2 是发热高峰**（后端全量 + 前端全量 + Next build，40–60 分钟）。
  本任务**默认不跑本地全量 L2**，改用第 5 节的「定向验证」。
  **CI 是真正的全量闸**（远端 runner，不占本机资源）—— 见第 7 节。

⚠️ **但 `main` 的 CI 现在就是红的**（2026-10-14 之后每次都红）：

| 失败作业                           | 原因                                                        | 性质                   |
| ---------------------------------- | ----------------------------------------------------------- | ---------------------- |
| `Lint`                             | `pnpm format:check` —— 仓库级 Prettier 格式债（数百个文件） | 存量债，与本次合入无关 |
| `Java 21 & PostgreSQL Integration` | identity 服务 1 例失败（`ApprovalRecordIntegrationTest`）   | 存量缺陷               |
| `Catalog Browser & Contract Tests` | 端到端用例失败                                              | 存量缺陷               |

**所以「CI 全绿」不是可用的闸** —— 拿它当闸，这个任务永远合不进去，等于没有闸。
本任务用**基线相对判据**：PR 的失败不得**比 `main` 基线更多**。见 7.3。

⚠️ **`Lint` 作业的覆盖面含 `.md`**：`ci.yml:26` 跑 `pnpm format:check`，
匹配 `**/*.{ts,tsx,js,jsx,json,md,yaml,yml}` ⇒ **Markdown 也在格式闸门内**。
`main` 上现有 **621 个文件**不合 Prettier（多为历史 `.tsx` / `.test.ts`）⇒ `Lint` 早已红，
这是**存量债**；但**本次合入带进来的新文件不许再增加这个数**。
⇒ 提交前对本 PR 触及的文件逐个跑一遍：

```bash
pnpm exec prettier --check <本 PR 改动的 .md / .ts / .json / .yaml>
```

⇒ 判据看**失败文件集合的差集**（见 7.3），不是看仓库总数。

---

## 3. 合并前的只读体检（先做，别急着合）

```bash
cd /Users/tommy/Project/starcon
git fetch upstream --prune
echo "=== 两分支关系（左=main 独有，右=集成分支独有）==="
git rev-list --left-right --count upstream/main...upstream/integration/module-dev
echo "=== 真实增量（看树差异，不看提交数）==="
git diff --stat upstream/main upstream/integration/module-dev | tail -3
git diff --quiet upstream/main upstream/integration/module-dev \
  && echo "树差异为空" || echo "树差异非空（仍要过本节末尾的落地树判定）"
echo "=== 合并预演（无副作用，不碰工作区）==="
git merge-tree --write-tree --name-only upstream/main upstream/integration/module-dev
echo "=== 落地树 vs main 树（两者相同 = 零变更）==="
MT_OUT=$(git merge-tree --write-tree --name-only upstream/main upstream/integration/module-dev 2>&1)
MT_TREE=$(printf '%s\n' "$MT_OUT" | head -1)
MAIN_TREE=$(git rev-parse upstream/main^{tree})
echo "落地树: $MT_TREE"
echo "main 树: $MAIN_TREE"
[ "$MT_TREE" = "$MAIN_TREE" ] \
  && echo "零变更：本轮不开 PR" || echo "有新增内容：走第 4–7 节"
```

`merge-tree` 会**提前告诉你哪些文件会真冲突**。它是只读的，**不要跳过**。

⚠️ **「提交数」不是增量，`main` 是 squash 合入的**：

`main` 的每次更新都是 **squash 合并**，所以 `main` 的提交**不是**集成分支的祖先
（`merge-base --is-ancestor upstream/main upstream/integration/module-dev` 恒为假），
`git rev-list --count upstream/main...upstream/integration/module-dev` 会报出**上百个提交**，
而**实际内容差异可能只有几十个文件**（实测：提交数口径报 142，内容口径是 83 个文件）。

⇒ **判断有没有新内容，只能看树差异**（`git diff --quiet upstream/main upstream/integration/module-dev`）。
用提交数判断的后果是：**每一轮都以为有大增量**，于是每轮都开一个内容其实没变的 PR。

同理，第 3.2 节的「可快进」判据在 squash 合入后**基本不成立** ——
正常形态是「真合并」，且冲突解法见第 4 节。

⚠️ **树差异非空 ≠ 有新内容要合入 —— 最终判据是「落地树哈希」**：

树差异回答的是「两个分支的内容是否不同」，但**你要回答的是「合入后 `main` 会不会变」**。
这两件事不等价：`main` 上可能已经有集成分支**没有**的文件（上一轮同步留下的会话记录、
其他 PR 带进 `main` 的产物），这些差异会让树差异恒为非空，
而**把集成分支合进来，`main` 一个字节都不会变**。

⚠️ 2026-10-22 实测：树差异 31 个文件、12 万行增删，但**落地树哈希与 `main` 完全相同**
（`git merge-tree --write-tree` 的输出 == `git rev-parse upstream/main^{tree}`），
真合并后 `git diff upstream/main HEAD` 为空 ⇒ **零变更，不该开 PR**。

⇒ **判断该不该开 PR，最终看落地树哈希**：

| 落地树 vs `main` 树 | 含义                         | 本轮动作                        |
| ------------------- | ---------------------------- | ------------------------------- |
| **相同**            | 合入后 `main` 不变（零变更） | **本轮不开 PR**，简短汇报后结束 |
| 不同                | 确有新内容要合入             | 走第 4–7 节                     |

**为什么不能只看树差异**：树差异是**双向**的（既含「集成分支有、`main` 没有」，
也含「`main` 有、集成分支没有」）。后一部分**不该**被合入动作改变 ——
真合并时 git 会自动保留 `main` 侧，所以最终树与 `main` 相同。
⇒ **树差异只用来提示「值得看一眼」，落地树哈希才是「该不该开 PR」的判据。**

⚠️ **只跑 `merge-tree` 不够**：它给出的是**预演**结果。
真合并后必须再核一次 `git write-tree` == `git rev-parse upstream/main^{tree}`，
确认落地树确实等于 `main`，才敢断言「零变更」。

### 3.1 同步 PR 的现状（决定本轮走哪条路）

```bash
gh pr list --base main --head chore/sync-module-dev --state open
```

| 现状                                               | 本轮动作                                                                              |
| -------------------------------------------------- | ------------------------------------------------------------------------------------- |
| 没有开着的 PR **且** 落地树 == `main` 树（零变更） | **本轮无动作**，简短汇报后结束                                                        |
| 没有开着的 PR **且** 落地树 != `main` 树           | 走第 4–7 节：建分支 → 合并 → 解冲突 → 验证 → 推送 → 建 PR                             |
| 有开着的 PR                                        | 看它的 CI 状态（第 7 节）：绿了就合；红了就报告；跑着就等；**有更新的内容就刷新分支** |

⚠️ **「有没有新内容」在第 3 节末尾已经换了判据** ——
不要再用「树差异是否为空」直接决定开不开 PR，用**落地树哈希**。

⚠️ **并发会话可能在同一轮内把 PR 合掉 —— 远端跟踪 ref「消失」是成功信号，不是故障。**

共享仓库的另一个执行者（并发会话、人工操作）可能在你核验期间完成合并。
合并后远端分支被删除，本地 `upstream/<pr-branch>` 立刻解析不出来：

```
fatal: ambiguous argument 'upstream/chore/sync-module-dev': unknown revision
```

这个报错看起来像 ref 损坏，**实际是合并已经成功**。判据顺序：

1. `gh pr view <PR号> --json state,mergedAt,mergeCommit` —— 显示 `MERGED` 就是合上了。
2. `upstream/main` 的指针是否已前进到合并提交。
3. 确认后再判断「这一轮还剩什么没做」。

⚠️ **不要因为 ref 解析失败就重跑合并流程** —— 那会造出第二个 PR。
也不要据此断定「合并没发生」—— 以 PR 状态与 `main` 指针为准。

### 3.2 三种合并形态，处理方式不同

| 形态                  | 判据                      | 处理                                                       |
| --------------------- | ------------------------- | ---------------------------------------------------------- |
| **A. 可快进**         | 树差异为空且祖先关系成立  | 极少出现（`main` 是 squash 合入的，祖先关系通常不成立）    |
| **B. 真合并但无冲突** | `merge-tree` 不报冲突文件 | 正常 `git merge --no-ff`，可能有 `contract-union` 自动求并 |
| **C. 真合并且有冲突** | `merge-tree` 报出冲突文件 | 走第 4 节逐个解冲突                                        |

---

## 4. 冲突处理（形态 C）

### 4.1 冲突面分类 —— 三类文件，三种解法

| 文件                                                                                                                                          | 合并驱动            | 解法                                                           |
| --------------------------------------------------------------------------------------------------------------------------------------------- | ------------------- | -------------------------------------------------------------- |
| `packages/api-contracts/src/**/*.ts`<br>`packages/api-contracts/src/*.openapi.json`<br>`docs/product/capability-blueprint/module-progress.md` | ✅ `contract-union` | **自动按键求并**。git 会自己跑 `merge-union.mjs`，通常不留冲突 |
| `apps/web/src/lib/api-proxy.ts`                                                                                                               | ❌ **无驱动覆盖**   | ⚠️ **必然真冲突**，按 4.3 的判据解                             |
| 其余源码                                                                                                                                      | ❌ 无驱动覆盖       | 按 4.2 的判据解                                                |

### 4.2 ⛔ 第一步永远是「判定 main 侧是不是集成分支的祖先快照」

**这不是可选的诊断步骤，是解冲突的入口。**

因为 `main` 是 **squash 合入**的，`main` 的文件版本来自集成分支的**某个较早提交**。
所以大多数冲突**不是双向分叉，而是线性演进**：

- main 侧 = 集成分支历史上的某个旧版本 ⇒ **取集成分支侧**（新侧是演进的终点，取一边不丢内容）。
- main 侧 = 集成分支**从未有过**的内容（真分叉）⇒ **必须并入**，取一边会丢东西。

⚠️ **在 squash 合入的仓库里，「无脑求并」是错的** —— 求并会把 main 侧的旧内容重新塞回去，
制造出「本该被替换掉的旧代码又活了」的状态。

**用工具判，不要靠眼睛：**

```bash
cd /Users/tommy/Project/starcon
node tools/module-progress/merge-resolve.mjs --base upstream/main --head upstream/integration/module-dev
```

输出逐个文件给出：

| 输出                                  | 含义                             | 动作                                        |
| ------------------------------------- | -------------------------------- | ------------------------------------------- |
| `[取集成分支侧]` + 祖先快照成立       | 线性演进                         | `git checkout --theirs <file>` 后 `git add` |
| `[取集成分支侧]` + 累积型三件套全成立 | 取新侧**在结果上等同于完整求并** | 同上                                        |
| `[并入（人工）]`                      | main 有集成分支从未有过的内容    | **人工求并**，不许取一边                    |

工具的退出码：0 = 全部可机械执行；1 = 存在必须人工并入的文件。

### 4.3 `api-proxy.ts`：白名单累积型文件

`apps/web/src/lib/api-proxy.ts` 是**累积型白名单**（按路径登记的 `pattern` 列表），
**没有合并驱动**。

**症状极隐蔽**：白名单只在浏览器端生效，**typecheck / lint / vitest / Playwright 全绿**，
但页面 404。所以解法必须有证据，不能靠眼看。

**两层 alternation 的求并要求依然成立**（内层是某服务组，外层是仓库级段）——
两侧各往同一处加分支时，只并内层会**静默丢掉外层新增分支**
（已在 8.2.1.2 / 10.4.1.1 上各咬一次）。

**但「求并」有两种形态，要分清：**

| 形态                                    | 特征                         | 解法                                               |
| --------------------------------------- | ---------------------------- | -------------------------------------------------- |
| **真分叉**（main 有集成分支没有的分支） | 两侧各加了**不同**的分支     | **必须人工求并**，两个分支都要在                   |
| **线性演进**（main 是旧快照）           | 集成分支只是在旧版本上继续加 | **取集成分支侧** —— 但要先证明它确实是严格保序超集 |

**「取集成分支侧」的三个证明**（工具已自动跑完并打印结论）：

1. **集合包含**：旧侧的每一条 pattern 都在新侧。
2. **有序子序列**：旧侧的顺序在新侧保持 —— `resolve()` 是 first-match-wins，顺序错了会让旧分支吃掉新分支的路径。
3. **零删除**：新旧之间没有删除行。

三条同时成立 ⇒ 取新侧在结果上等同于完整求并，没有任何东西被丢掉。

**验证方式不能是眼看，也不能是 grep + 自写正则**（该方法已连续骗过 6 版）。
必须**跑真 proxy 断言 HTTP 状态码**（白名单拦截 = 404，放行 = 转发），
并**同时断言反向用例**（未知路径仍 404）—— 否则「全部拒绝」的正则也能让放行用例全绿。
对应测试文件：`apps/web/src/app/api/[...path]/route.test.ts`。

### 4.4 `contract-union` 自动求并的正确用法

驱动已注册在 `.git/config`（`merge.contract-union.driver`，**仓库本地配置，worktree 共享**）。
它按「键」做**三方**求并（类型名 / OpenAPI 路径 / 模块 ID），base 才是裁判。

⚠️ **驱动跑的是「当前检出分支」那份**（注册的是相对路径，git 的工作目录 = 工作树顶层）。
第 7.1 节建 worktree 时检出的是基于 `main` 的同步分支 ⇒ **跑 `main` 那份驱动**。
契约产物的正确形态是：**横幅在首行 + 类型名按字典序 + JSON 键按字典序 + 浮点用 Python 形态
（`1e-06` 而非 `0.000001`）**，且与 `tools/codegen/catalog-contracts.py` 的输出**逐字节相同**。
产出不是这个形态 ⇒ 不要接受，先查 1.5。

- 输出 `merge-union: 走了行级兜底并集，请人工确认这个文件改对了` ⇒ **它没认出这个格式**，
  走了行级兜底。**必须人工核对**这个文件的每一行。
- 输出 `CONFLICT_KEYS` ⇒ 同一个键两侧都改了，**必须人工裁决**。
- 台账求并后**必须**核对行数：`grep -cE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ '` 须恒为 **465**，
  且 `grep -oE '...' | sort | uniq -d` 须**为空**。

### 4.5 解冲突的通用纪律

- **先跑 4.2 的判定工具**，不要凭印象决定取哪一边。
- **取一边是允许的，但必须带证据**：祖先快照成立（线性演进），
  且累积型文件的三件套全成立。
  **没有证据的「取一边」= 静默删掉另一边已交付的成果。**
- **真分叉（main 有集成分支没有的内容）必须并入**，这类文件工具会标成「并入（人工）」。
- **先看 base**：用 `git diff <base> -- <file>` 判断「这一行是谁改的」。
  集成头侧新增 ≠ 车道删除 —— 逐行比对时也要问这个问题。
- **git 没报冲突 ≠ 不用审查**。已知累积型文件（白名单 / 契约 / 台账）
  即使自动合并成功，也要核对一遍是不是真求并了（`contract-union` 的输出见 4.4）。
- 解完必须 `git add` 冲突文件，确认 `git diff --name-only --diff-filter=U` **为空**。
- 冲突解到一半**不许提交**。半解状态推上去会毁掉 `main`。

### 4.6 判定工具的 `[并入（人工）]` 是保守警报，不是判决

工具标出「并入（人工）」只证明**不能机械取一边**，不证明**必须两侧都留**。
还要自己取证；取证后若证明「并集 == 一侧」，就**只留那一侧** ——
无脑求并会把已被替换掉的旧措辞重新塞回文档（本文件第 3 节的判据章节就是这么被塞回去过的）。

**取证配方（行级差集）**：

⚠️ **必须在 `git add` 解冲突文件之前跑这套取证** ——
`git add` 一执行，stage 2 / stage 3 就被清掉，之后再取就只剩 stage 0：

```
fatal: path '<file>' is in the index, but not at stage 2
```

⇒ 顺序是：**先取证 → 再 `git checkout --ours/--theirs` → 再 `git add`**。
若已经 `add` 过了，改用显式 ref 取同一份数据（结果等价）：

```bash
git show upstream/main:<file> | sort -u > /tmp/side_main.txt        # main 侧
git show upstream/integration/module-dev:<file> | sort -u > /tmp/side_head.txt   # 集成分支侧
comm -23 /tmp/side_head.txt /tmp/side_main.txt      # 集成分支侧独有行
comm -13 /tmp/side_head.txt /tmp/side_main.txt      # main 侧独有行
```

⚠️ **两侧必须用同一条命令归一化**，否则表格列宽差异会被当成「集成侧独有内容」：

```bash
norm() { awk '{gsub(/[ \t]+/," "); sub(/^ /,""); sub(/ $/,""); print}'; }
git show :2:<file> | norm | sort -u > /tmp/side_main.txt
git show :3:<file> | norm | sort -u > /tmp/side_head.txt
```

（不归一化时，Markdown 表格的分隔行与列宽会被算成独有行，差集结论会失真。
2026-10-22 12:00 那轮实测：未归一化时集成侧独有 6 行，归一化后只有 4 行。）

判据：**集成分支侧独有行是否逐条都能在 main 侧找到对应的新版形态**。
若每一行都是「旧措辞被新版替换」（而不是独有内容），则 **main 侧是更新的快照，取 main 侧零丢失**。

⚠️ 本任务书（`MAIN_MERGE_CRON_PROMPT.md`）自己就是一个高频冲突文件 ——
集成分支侧的副本总是落后于 `main` 侧（本文件在 `main` 上被修正过，集成侧没有这些修正）。
⇒ 它的冲突**默认取 main 侧**，但仍要用上面的行级差集确认一遍。

---

## 5. 验证（决定能不能推 PR 分支的闸）

### 5.1 判定表：跑哪一级

| 合并形态       | 验证级别              | 内容                                         |
| -------------- | --------------------- | -------------------------------------------- |
| A 可快进       | **定向**              | 静态预检 + `sync.mjs --check` + 关键服务测试 |
| B 真合并无冲突 | **定向**              | 同上 + 合并驱动输出核对                      |
| C 有冲突       | **定向 + 冲突面专项** | 同上 + 冲突文件的针对性测试                  |

**默认不跑本地全量 L2**（发热高峰，见 2.4）。理由：
`main` 的内容来自 `integration/module-dev`，而**流水线在推 `module-dev` 之前已经跑过全量 L2**；
本任务的增量只有「合并动作本身」与「冲突解法」—— **验证要打在增量上，不是重跑全量**。
**真正的全量闸是 CI**（第 7 节），它在远端 runner 上跑，不占本机资源。

**例外 —— 这几种情况必须本地跑全量 L2**：

- 冲突解法动到了**后端业务代码 / 迁移脚本**（不只是前端白名单和契约）。
- `sync.mjs --check` 红了**且是合并引入的**（按 5.4 判别；源分支本来就红的不算）。
- 定向测试红了（先按 5.3 归因，不是直接归因回归）。

### 5.2 定向验证清单

```bash
cd /Users/tommy/Project/starcon

# ① 静态预检：已知互相破坏形状
grep -rn 'ADD COLUMN' backend/services/*/src/main/resources/db/migration/ | grep -v 'DEFAULT'
grep -rn 'SET NOT NULL' backend/services/*/src/main/resources/db/migration/
grep -rn 'ADD CONSTRAINT.*CHECK' backend/services/*/src/main/resources/db/migration/

# ② 台账完整性（恒为 465，且无重复 ID）
grep -cE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' docs/product/capability-blueprint/module-progress.md
grep -oE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' docs/product/capability-blueprint/module-progress.md | sort | uniq -d

# ③ 台账同步自检
node tools/module-progress/sync.mjs --check

# ④ 冲突面专项（解过 api-proxy 冲突时必跑）
cd apps/web && pnpm vitest run src/app/api/\[...path\]/route.test.ts
```

**① 静态预检要读成「相对 `main` 基线的增量」，不是绝对命中数。**
仓库里这些形状的绝对命中数是几百条存量债（实测 `ADD COLUMN` 160+、`SET NOT NULL` 43、
`ADD CONSTRAINT CHECK` 21），**绝对数字对本次合入没有信息量**。
判据是「合入后比 `main` 基线多了几条」：

```bash
for pat in 'ADD COLUMN' 'SET NOT NULL' 'ADD CONSTRAINT.*CHECK'; do
  a=$(git grep -E "$pat" upstream/main -- backend/services | wc -l)
  b=$(git grep -E "$pat" HEAD -- backend/services | wc -l)
  echo "$pat main=$a HEAD=$b 增量=$((b-a))"
done
```

⚠️ **命中「无 DEFAULT 的 `ADD COLUMN`」不等于危险形状 —— 要看列的可空性与后续约束。**
危险的是**加在既有表上、且不可空**的列（既有行会违约）。
可空列加在既有表上，既有行取到 `NULL`，不破坏任何写入路径 ⇒ 放行。
逐条核对时看三件事：列是否可空、有没有后续 `SET NOT NULL`、有没有回填。

**④ `api-proxy.ts` 白名单的真实测试面比这一行写的更大，且路径写法会失效。**

- `apps/web/src/app/api/[...path]/route.test.ts` **不存在**（没有这个顶层目录）。
  真实的是按服务分的 4 个路由测试：
  `apps/web/src/app/api/{catalog,channel,iam,warehouse}/[...path]/route.test.ts`；
- 另有 `apps/web/src/app/api/<service>/<module>-allowlist.test.ts` 一大批
  （实测 141 个），以及 `apps/web/src/lib/api-proxy.test.ts`、
  `apps/web/src/lib/api-proxy-union.test.ts` 两个专职守卫。

⚠️ **vitest v4 选不中带方括号的目录** ——
`vitest run "apps/web/src/app/api/**"`、`--dir`、`\[...path\]` 转义写法**全部**返回
`No test files found`（filter 被规范化时把 `]` 当字符类收尾吃掉）。
唯一可靠做法是临时 scoped config 显式写 `include`：

```ts
// vitest.api-scratch.config.ts（放在 worktree 根，用完随 worktree 一起删）
import { defineConfig } from 'vitest/config';
export default defineConfig({
  test: {
    include: [
      'apps/web/src/app/api/**/*.test.ts',
      'apps/web/src/lib/api-proxy.test.ts',
      'apps/web/src/lib/api-proxy-union.test.ts',
    ],
    environment: 'node',
  },
});
```

```bash
npx vitest run --config vitest.api-scratch.config.ts   # 实测 170 文件 / 1235 例全绿
```

⚠️ vitest v4 **已删掉 `--reporter=basic`**（会被当成自定义 reporter 模块去加载而报错），
用默认 reporter 即可。
⚠️ 在 worktree 里删临时文件要用**绝对路径** + 可恢复删除：
相对路径会被权限闸按**主工作区**解析，报「dangerous removal target」。

### 5.3 红了怎么归因（别把环境问题当回归）

**成片的同形失败（`503` / 连接拒绝 / 固定挂在 `seed()` 或 `beforeAll`）几乎总是环境争抢**，
不是代码回归。判据：**先隔离复跑最大那一堆**：

```bash
cd backend && ./mvnw -pl services/<被改的服务> -am test -Dtest='<那个测试类>'
```

隔离复跑还红 ⇒ 真缺陷；复跑就绿 ⇒ 环境问题，**不要改代码去迎合一个 flaky 环境**。

**反过来**：**孤立的断言失败**（`expected: 4 but was: 3`）是货真价实的缺陷，别混进「都是 flake」。

### 5.4 验证不过怎么办

**不推 PR 分支。** 把卡点写进汇报：哪个测试、哪条断言、为什么、怎么复现。
**不许**为了让 PR 能推而去改测试、放宽断言、或跳过用例。

**`sync.mjs --check` 红了要分清两种情况**：

| 情况                 | 判据                                                                  | 处理                                                                                                                                                                |
| -------------------- | --------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **合并引入的**       | 在**源分支**（`upstream/integration/module-dev`）上跑同一个检查是绿的 | 真缺陷 → 阻塞，报告                                                                                                                                                 |
| **源分支本来就红的** | 源分支上也是红的                                                      | **不是本次合入的责任**。台账是 `sync.mjs` 的生成物，跑一次 `node tools/module-progress/sync.mjs` 重新生成即可（实测它只改汇总表与行内空白，不动交付状态），然后继续 |

源分支基线的查法（只读，不碰工作区）：

```bash
git worktree add --detach .worktrees/sync-baseline upstream/integration/module-dev
cd .worktrees/sync-baseline && node tools/module-progress/sync.mjs --check; cd ../..
git worktree remove --force .worktrees/sync-baseline
```

---

## 6. 落提交（内容必须易读、专业）

> 这一节是本任务的**硬性交付标准**，不是风格建议。

### 6.1 摘要工具：功能增量从台账出，不要从提交信息出

```bash
cd /Users/tommy/Project/starcon
node tools/module-progress/merge-summary.mjs --base upstream/main --head upstream/integration/module-dev
```

它输出**本次新交付 / 新推进的功能**（按业务系统分组，带功能名与模块 ID），
以及一条候选标题。**这是 PR 标题与正文的事实来源。**

⚠️ 它会**读不到就抛错**（台账已超过 1MB，Node 默认缓冲会踩 ENOBUFS；
ref 不存在也会抛错）。**读不到就报错是对的**，不许把读失败当成「没有新交付」。

### 6.2 标题

格式：`<type>: <一句话功能概述>`

- `type` 用 Conventional Commits 的（`feat` / `fix` / `chore` / `docs` / `refactor`）。
- 概述**只讲功能**，长度控制在 30 字以内。
- 候选标题由摘要工具给出，可微调，但**不许偏离「只讲功能」这条**。

例子：

- `feat: 订单变更取消与退货授权单交付`
- `feat: 一键刊登、合规预检等 6 项能力交付`
- `fix: 缺货预警与到岸成本口径修正`

### 6.3 正文

格式：

```
## 本次合入的功能
- 订单变更取消（9.2.1.1）
- 退货授权单（11.2.1.1）

## 说明
本次同步集成分支的最新交付，模块范围与验收记录见功能进度台账。
```

**正文里禁止出现的东西**（这正是「暴露工程细节」的表现）：

- ❌ 提交数 / 文件数 / 增删行数（`279 提交 / 827 文件` 这类）
- ❌ 冲突文件路径、冲突解决机制（求并策略、正则 alternation、合并驱动名）
- ❌ 内部工具与脚本名（`sync.mjs`、`merge-union`、`lane.mjs`、worktree 路径）
- ❌ 迁移版本号、数据库表名、端点路径（除非它本身就是这项功能的一部分）
- ❌ **工程决策与取舍理由**（「因为…所以…」「为了让…我们采用…」）
- ❌ 分支名、commit hash、任务内部代号

**正文里该出现的东西**：功能名、模块 ID、面向使用者的能力描述、验证状态。

### 6.4 提交信息

PR 分支上的那个提交，**用与 PR 标题一致的可读标题**：

```bash
git commit -m "feat: 订单变更取消与退货授权单交付"
```

**不许**用 `merge(module-dev): 并入集成分支 <日期>` 这类工程流水账标题。

---

## 7. PR 与合并执行

**在专用 worktree 里做，不要动主工作区** —— 主工作区是流水线的地盘，
在它上面 checkout `main` 会打断流水线。

### 7.1 建分支、合并、解冲突、验证、推送

```bash
cd /Users/tommy/Project/starcon
git fetch upstream --prune

# ① 建专用 worktree（从 main 出发）
#    ⚠️ 路径必须**带唯一后缀**。固定路径（如 `.worktrees/main-merge`）是共享地，
#    任何别的执行者（人工演练、另一台机器、另一个任务）用同一个路径建/删 worktree，
#    都会把你**进行中**的合并状态一起删掉 —— 2026-10-21 08:30 那轮就是这么被打断的。
WT=".worktrees/main-merge-$(date +%H%M%S)"
git worktree add --detach "$WT" upstream/main
cd "$WT"

# ② 切出同步分支（已存在就重置到 main）
git checkout -B chore/sync-module-dev

# ③ 合并集成分支
#    ⚠️ 本轮若要带 packages/api-contracts/ 的产物，先过 1.5 的放行判据！
#       否则会用 main 那份旧驱动把产物写回 JS 形态（合了也白合，CI 照样红）。
git merge --no-ff --no-commit upstream/integration/module-dev

# ④ 看冲突
git diff --name-only --diff-filter=U

# ⑤ 解冲突（按第 4 节）
#    先跑判定工具，它逐个文件给出「取集成分支侧」还是「并入（人工）」：
cd /Users/tommy/Project/starcon
node tools/module-progress/merge-resolve.mjs --base upstream/main --head upstream/integration/module-dev
cd .worktrees/main-merge
#    判「取集成分支侧」的文件：
#      git checkout --theirs <file> && git add <file>
#    判「并入（人工）」的文件：自己求并，**不许取一边**
#    解完确认：git diff --name-only --diff-filter=U 为空

# ⑥ 验证（按第 5 节）

# ⑦ 提交（可读标题，按第 6 节）
git add -A && git commit -m "feat: <功能概述>"

# ⑧ 推 PR 分支
git push --force-with-lease upstream chore/sync-module-dev
```

### 7.2 建 PR

```bash
cd /Users/tommy/Project/starcon
gh pr create \
  --base main \
  --head chore/sync-module-dev \
  --title "feat: <功能概述>" \
  --body "<按 6.3 的格式>"
```

**已经开着的 PR 不要重开** —— 直接更新分支即可（PR 内容自动跟随）。

### 7.3 等 CI，并用基线相对判据决定能不能合

CI 定义在 `.github/workflows/ci.yml`（`pull_request` 触发，会跑 lint / typecheck / test / build /
security / 后端 verify / 端到端）。

```bash
cd /Users/tommy/Project/starcon
gh pr checks <PR号> --watch --interval 30   # 有界等待，超时上限约 15 分钟
```

**等完之后，用闸工具判一次**（它会把 PR 的失败与 `main` 最近一次 run 的失败逐条比对）：

```bash
node tools/module-progress/ci-gate.mjs --pr <PR号>
```

判据（保守）：

| 闸工具输出             | 含义                                                       | 动作                               |
| ---------------------- | ---------------------------------------------------------- | ---------------------------------- |
| `结论：可以合并`       | PR 的失败是 `main` 基线的子集，且没有新的失败用例          | 进 7.4 合并                        |
| `结论：阻塞`           | 出现了基线里没有的失败作业，或同名作业里出现了新的失败用例 | **不合**，报告新增失败             |
| 退出码 2（取不到状态） | run 还没开始 / 日志取不到                                  | **不合**，报告状态取不到，下轮再试 |
| 明细不可比的作业       | 该作业的失败明细取不出来，只比对了作业粒度                 | 按持平放行，但**必须在汇报里点名** |

⚠️ **不许把「CI 全绿」当成合并前提** —— `main` 基线就是红的（见 2.4）。
也**不许因为「反正 main 也是红的」就无条件合并** —— 判据是**基线相对**，
不是「红的就可以过」。

- **CI 仍在跑** → **本轮不合**，报告「CI 仍在运行」，下一轮继续跟进。
  （CI 是远端 runner，不要为了等它在本机空转。）

⚠️ **闸工具挑基线时不会检查基线 run 是否已跑完 —— 在跑的基线会被读成「无失败」。**

闸工具取「`main` 最近一次 run」当基线。**若那个 run 还在 `in_progress`**，
尚未出结论的作业会被读成「基线没有失败」⇒ PR 侧同样的失败就被算成**新增失败**
⇒ 结论变成**假阻塞**。

判据顺序：

1. 闸工具报「阻塞」时，**先看基线 run 的状态**：
   `gh run view <基线run> --json status,conclusion`。
2. 基线还在跑 ⇒ **这是工具的假阻塞，不是真回归**；等基线跑完**重跑闸工具**再判。
   ⚠️ **不要**因为闸工具说「阻塞」就放弃，**也不要**绕过它硬合 —— 两种都错。
3. 基线已 completed 还报新增失败 ⇒ 那才是真回归。

⚠️ **「明细不可比的作业」按持平放行时，要补一层更强的证据**（不能只靠作业粒度）：

- **Lint**：比**失败文件集合的差集**，不是比文件数。差集应为空。
  （仓库级格式债会让绝对数在两次 run 之间变，但**差集**才说明是不是本次引入的。）
  取失败文件清单：
  ```bash
  gh api "repos/<owner>/<repo>/actions/jobs/<jobId>/logs" \
    | sed -E 's/\x1b\[[0-9;]*m//g' | grep -E '\[warn\] ' \
    | sed -E 's/^[^ ]+ \[warn\] //' | sort -u
  ```
  ⚠️ 日志行带 ISO 时间戳前缀，按 `^\[warn\]` 锚定会抽出 **0 条**，
  会误判成「没有失败文件」—— 必须用 `grep -E '\[warn\] '`。
- **Security Scan**：比**审计条目数与严重度分布**。若两次数字不同，
  先确认 `pnpm-lock.yaml` 的 blob 在两侧**逐字节相同**且本次改动不碰依赖清单
  ⇒ 差异只能来自 registry 在两次 run 之间新增 advisory，不是本次引入。
- **Catalog Browser & Contract Tests**：比**失败的根因链**（异常类型与创建失败的 bean 名），
  相同即持平。

### 7.4 合并 PR

**前置条件**：7.3 的闸工具输出 `结论：可以合并`。不是这个结论就不许走到这一步。

**合并前重新取闸**（短持有），避免两轮同时合同一个 PR：

```bash
cd /Users/tommy/Project/starcon
node tools/module-progress/lane.mjs gate acquire   # 退出码 3 = 撞闸 ⇒ 本轮不合，下轮再合
gh pr merge <PR号> --squash --subject "feat: <功能概述>" --body "<按 6.3 的格式>" --delete-branch
node tools/module-progress/lane.mjs gate release
```

**用 squash**：合进 `main` 的是**一条**可读提交，读者在 `git log` 里一眼看到
「这次交付了哪些功能」，而不是几百条工程流水账。

⚠️ `gh pr merge` 若报 `Pull request is not mergeable` / `not mergeable: the merge commit cannot be cleanly created`，
**不要加 `--admin` 强合**。把真实原因写进汇报。

### 7.5 收尾

```bash
cd /Users/tommy/Project/starcon
git worktree remove --force "$WT"     # 只删**你自己**这一轮建的那个
node tools/module-progress/lane.mjs gate release
```

**两个都必须做。** 漏掉 worktree 清理 → 自己的 worktree 会一直留着占磁盘。
漏掉 `gate release` → 下轮撞闸，任务空转一轮。

⚠️ **只删自己建的那一个。** 不要用 `git worktree remove --force .worktrees/main-merge`
这种固定路径去删 —— 那可能删掉**别人正在用**的 worktree（实测踩过）。

### 7.6 推之前最后确认

```bash
git log --oneline -3          # 确认提交内容对
git diff upstream/main...HEAD --stat | tail -3   # 确认合并进来的东西符合预期
```

⚠️ **推之前确认 worktree 里没有未提交的改动**（`git status --porcelain` 为空）——
带着脏工作区推会漏文件。

---

## 8. 汇报（12 行以内）

- **本轮动作**：无动作（无新内容）/ 新建 PR / 更新 PR / 合并 PR
- **合并形态**：可快进 / 真合并无冲突 / 真合并有冲突
- **冲突处理**：解了几个文件、分别是什么类（白名单 / 契约 / 台账 / 业务代码）、
  `api-proxy.ts` 是否两层都求并
- **合入的功能**：功能名 + 模块 ID（业务视角，**不要写提交数/文件数**）
- **验证结果**：跑了哪一级、定向验证逐项的真实输出、是否跑了本地全量 L2（以及为什么）
- **CI 结果**：闸工具的结论（可以合并 / 阻塞）+ 与 `main` 基线相比新增了什么
- **合并结果**：PR 号 + 是否已合进 `main`；**没合要明确写「验证未绿 / 出现新增失败，本轮不合」**
- **闸**：acquire/release 是否成功
- **台账**：行数是否恒为 465
- **风险提示**（可选）

**不许谎报**：没合就说没合，验证没跑就说没跑，冲突没解完就说没解完。

---

## 9. 最高优先级禁令

- **不许直推 `main`**（只经 PR）
- **不许在没有证据的情况下「取一边」** —— 必须先跑 4.2 的判定工具
- **不许在 squash 合入的仓库里无脑求并** —— 那会把旧内容塞回去；判据是祖先快照 + 三件套
- **不许在出现「比 `main` 基线更多的失败」时合并**（用 7.3 的闸工具判，不许凭感觉）
- **不许把「CI 全绿」当成合并前提**（`main` 基线就是红的），也**不许因为「反正也是红的」就无条件合并**
- **不许无证据地「取一边」** —— 白名单/契约/台账取一边 = 静默删交付成果（要取就带证据，见 4.2）
- **不许在主工作区 checkout `main`** —— 会打断流水线
- **不许用固定路径删 worktree**（只删自己本轮建的那个，路径带唯一后缀）
- **不许 force push `main`**
- **不许用 `gh pr merge --admin` 强合**
- **不许在 `main` 上改业务代码**（本任务只做「合入」，不开发）
- **不许动 `integration/module-dev`**（那是流水线的集成分支，本任务只读）
- **不许为了让 PR 能推而改测试、放宽断言、跳过用例**
- **不许在 PR 标题/正文/提交信息里写工程流水账**（提交数、文件数、冲突机制、内部脚本名、工程决策理由）
- **不许在 commit / 汇报里贴 secret**
- **不许谎报「已合并」**
- **必须 `gate release`**（无论成败）
- **必须清理 `.worktrees/main-merge`**（无论成败）
- **不许在冲突解到一半时提交**
- **不许把「读不到台账」当成「没有新交付」**

---

## 10. 参考：本地运行参数

本任务**没有需要本地覆盖的运行参数**（固定每半小时、固定分支名）。
若将来要加（例如「合并前必须等某个外部信号」），**放本地 gitignored 文件，不要写进本文件** ——
写进版本化文档会让每次调参都产生一个噪音 commit。
