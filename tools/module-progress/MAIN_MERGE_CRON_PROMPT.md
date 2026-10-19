# starcon 集成分支并入 main · cron 任务书（每日 00:00）

> **本文件是 cron 任务的 prompt 来源。协议只存这一份，且在版本控制里。**
> 改策略改文件，不要改 cron prompt 字段。
>
> 本任务与「并行车道开发流水线」（`PIPELINE_CRON_PROMPT.md`）是**两个独立任务**：
> 流水线负责在 `integration/module-dev` 上开发，本任务负责把集成分支**合入 `main`**。
> 两者不共享车道、不共享派发逻辑，但**共享同一个仓库** —— 并发时必须互不干扰。

你每次运行是**全新的独立会话**，不继承任何上下文。
**所有判断必须从仓库现状重新取证，不能凭记忆。**

---

## 0. 项目与一句话职责

项目：`/Users/tommy/Project/starcon`（Java Spring Boot 微服务 + Next.js 双栈，遵守根 `AGENTS.md`）。

**你的职责：把 `integration/module-dev` 合入 `main`，处理冲突与合并过程中出现的问题，
然后推送 `main`，并如实汇报。**

源分支：`integration/module-dev`（远端 `upstream/integration/module-dev`）
目标分支：`main`（远端 `upstream/main`）

---

## 1. ⛔ 硬性前提（先读这段）

### 1.1 `main` 的推送授权

`main` 在本仓库**没有分支保护**（已核实：GitHub API 返回 `Branch not protected`），
所以技术上可以直推。**但你必须遵守根 `AGENTS.md` 的「不要直接 push `main`」禁令 ——
除非本任务的既定职责就是它。**

本任务的职责**就是**把集成分支并入 `main` 并推送。**这条禁令在本任务内被显式豁免**，
豁免范围仅限「本任务推送 `main`」这一件事。除此之外的一切 `main` 推送仍然禁止。

### 1.2 合并 ≠ 合并冲突解决

`main` 是发布分支，合入必须**保证可构建、测试绿**。合并冲突解决只是其中一环。
**不许「合上了就算完」** —— 冲突解完还要跑验证（见第 5 步）。

### 1.3 不许把「明天再说」当交付

冲突解不了、验证不过、或者闸命中 —— **本轮不推送 `main`**，
但**必须把卡点写清楚**（哪个文件、什么原因、解除条件是什么）。
**沉默地跳过一轮 = 谎报成功。**

---

## 2. 🔒 并发闸（与流水线共享，必须先查）

本任务与开发流水线共享同一个仓库。**并发合并会造成 `MERGE_HEAD` 残骸和分支指针竞态。**

### 2.1 先读闸状态（只读，永远第一步）

```bash
cd /Users/tommy/Project/starcon && node tools/module-progress/lane.mjs gate status
```

输出含 `gate / ageSeconds / ttlSeconds / stale`，**四个都要看**。

| `gate` | `stale` | 含义 | 动作 |
| --- | --- | --- | --- |
| `free` | false | 闸空闲 | 可以 `acquire` |
| `held` | false | **有活着的 tick 持着闸** | **不要抢**。只做只读检查 + 汇报，结束本轮 |
| `held` | true | 持闸者崩了（超 TTL 70 分钟） | 可以 `acquire` 接管 |

⚠️ **`stale=false` 不等于持闸者在偷懒** —— 它可能正在跑 L2（实测单轮 40–60 分钟）。
**撞闸（`acquire` 退出码 3）时，正确动作是「本轮零改动 + 只读 + 简短汇报」**，
不是清闸、不是 `force`。崩掉的闸会被 TTL 自动接管（最多挡一轮）。

### 2.2 取闸与放闸

```bash
node tools/module-progress/lane.mjs gate acquire   # 退出码 3 = 撞闸
# ……做合并……
node tools/module-progress/lane.mjs gate release     # 收尾必须放，无论成败
```

**持闸要贯穿整个合并过程**（fetch → 合并 → 解冲突 → 验证 → push）。
**本轮若因任何原因提前结束（闸 STOP、撞闸、异常），也必须 `gate release`** ——
你已经拿到闸，不释放就等于把下一轮挡住。

### 2.3 静默窗口

开发流水线的静默窗口是 **20:30 → 次日 08:00**（见 `.dev-agent/PIPELINE.local.md`）。
本任务每日 **00:00** 触发，**落在静默窗口内**。

**静默窗口管的是「派发新车道」和「跑 L2 发热高峰」，管不住「合入 main」** ——
后者是发布动作，必须在固定时刻发生。但因此**你要格外节制资源**：

- 合并本身很轻（git 操作），**可以**在静默窗口内做。
- **L2 全量验证是发热高峰**（后端全量 + 前端全量 + Next build，40–60 分钟）。
  静默窗口内跑 L2 会让用户半夜被风扇吵醒 —— **本任务默认不在静默窗口内跑全量 L2**，
  改用第 5 步的「定向验证」。见第 5 节的判定表。

---

## 3. 合并前的只读体检（先做，别急着合）

```bash
cd /Users/tommy/Project/starcon
git fetch upstream --prune
echo "=== 两分支关系（左=main 独有，右=module-dev 独有）==="
git rev-list --left-right --count upstream/main...upstream/integration/module-dev
echo "=== main 是否为 module-dev 的祖先（是则可快进，无冲突）==="
git merge-base --is-ancestor upstream/main upstream/integration/module-dev \
  && echo "可快进" || echo "已分叉 → 会产生真合并"
echo "=== 合并预演（无副作用，不碰工作区）==="
git merge-tree --write-tree --name-only upstream/main upstream/integration/module-dev
```

`merge-tree` 会**提前告诉你哪些文件会真冲突**。它是只读的，**不要跳过**。

### 3.1 三种合并形态，处理方式不同

| 形态 | 判据 | 处理 |
| --- | --- | --- |
| **A. 可快进** | `merge-base --is-ancestor` 成功 | `git merge --ff-only`，无冲突，跳到第 5 步验证 |
| **B. 真合并但无冲突** | `merge-tree` 不报冲突文件 | 正常 `git merge --no-ff`，可能有 `contract-union` 自动求并 |
| **C. 真合并且有冲突** | `merge-tree` 报出冲突文件 | 走第 4 步逐个解冲突 |

---

## 4. 冲突处理（形态 C）

### 4.1 冲突面分类 —— 三类文件，三种解法

| 文件 | 合并驱动 | 解法 |
| --- | --- | --- |
| `packages/api-contracts/src/**/*.ts`<br>`packages/api-contracts/src/*.openapi.json`<br>`docs/product/capability-blueprint/module-progress.md` | ✅ `contract-union` | **自动按键求并**。git 会自己跑 `merge-union.mjs`，通常不留冲突 |
| `apps/web/src/lib/api-proxy.ts` | ❌ **无驱动覆盖** | ⚠️ **必然真冲突，必须手工求并**（见 4.2） |
| 其余源码 | ❌ 无驱动覆盖 | 按语义合并，逐个判断 |

### 4.2 ⚠️ `api-proxy.ts`：白名单正则两层 alternation，必须**都**求并

`apps/web/src/lib/api-proxy.ts` 是**累积型白名单**（按路径登记的 `pattern` 列表），
**没有合并驱动**。2026-10-19 实证：两侧各加一个同位置分支 ⇒ **真冲突**（`CONFLICT (content)`）。

**它的正则有两层 alternation**（内层是某服务组，外层是仓库级段）。
**两层都必须求并** —— 只并内层会**静默丢掉外层新增分支**（已在 8.2.1.2 / 10.4.1.1 上各咬一次）。

**症状极隐蔽**：白名单只在浏览器端生效，**typecheck / lint / vitest / Playwright 全绿**，
但页面 404。所以：

- 解法是**求并**，**不是二选一**（不要取 ours 也不要取 theirs）。
- 冲突块形如：
  ```
  <<<<<<< HEAD
      pattern: /^(login|logout|session|memberships|switch|module-dev-route)$/,
  =======
      pattern: /^(login|logout|session|memberships|switch|main-side-route)$/,
  >>>>>>> a77dcb6a
  ```
  正确结果（**两侧分支都在**）：
  ```
      pattern: /^(login|logout|session|memberships|switch|module-dev-route|main-side-route)$/,
  ```
- **验证方式不能是眼看，也不能是 grep + 自写正则**（该方法已连续骗过 6 版）。
  必须**跑真 proxy 断言 HTTP 状态码**（白名单拦截 = 404，放行 = 转发），
  并**同时断言反向用例**（未知路径仍 404）—— 否则「全部拒绝」的正义正则也能让放行用例全绿。
  对应测试文件：`apps/web/src/app/api/[...path]/route.test.ts`。

### 4.3 `contract-union` 自动求并的正确用法

驱动已注册在 `.git/config`（`merge.contract-union.driver`，**仓库本地配置，worktree 共享**）。
它按「键」做**三方**求并（类型名 / OpenAPI 路径 / 模块 ID），base 才是裁判。

- 输出 `merge-union: 走了行级兜底并集，请人工确认这个文件改对了` ⇒ **它没认出这个格式**，
  走了行级兜底。**必须人工核对**这个文件的每一行。
- 输出 `CONFLICT_KEYS` ⇒ 同一个键两侧都改了，**必须人工裁决**。
- 台账求并后**必须**核对行数：`grep -cE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ '` 须恒为 **465**，
  且 `grep -oE '...' | sort | uniq -d` 须**为空**。

### 4.4 解冲突的通用纪律

- **先看 base**：用 `git diff <base> -- <file>` 判断「这一行是谁改的」。
  集成头侧新增 ≠ 车道删除 —— 逐行比对时也要问这个问题。
- **不许用「取一边」糊掉冲突**。白名单、契约、台账这三类都是**累积型**，
  取一边 = 静默删掉另一边已交付的成果。
- 解完必须 `git add` 冲突文件，确认 `git diff --name-only --diff-filter=U` **为空**。
- 冲突解到一半**不许提交**。半解状态推上去会毁掉 `main`。

---

## 5. 验证（决定能不能推 `main` 的闸）

### 5.1 判定表：跑哪一级

| 合并形态 | 验证级别 | 内容 |
| --- | --- | --- |
| A 可快进 | **定向** | 静态预检 + `sync.mjs --check` + 关键服务测试 |
| B 真合并无冲突 | **定向** | 同上 + 合并驱动输出核对 |
| C 有冲突 | **定向 + 冲突面专项** | 同上 + 冲突文件的针对性测试 |

**默认不跑全量 L2**（静默窗口 + 发热高峰，见 2.3）。理由：
`main` 的内容来自 `integration/module-dev`，而**流水线在推 `module-dev` 之前已经跑过全量 L2**。
本任务的增量只有「合并动作本身」与「冲突解法」—— **验证要打在增量上，不是重跑全量**。

**例外 —— 这几种情况必须跑全量 L2**（此时机器负载通常也不高）：

- 冲突解法动到了**后端业务代码 / 迁移脚本**（不只是前端白名单和契约）。
- `sync.mjs --check` 红了。
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

### 5.3 红了怎么归因（别把环境问题当回归）

**成片的同形失败（`503` / 连接拒绝 / 固定挂在 `seed()` 或 `beforeAll`）几乎总是环境争抢**，
不是代码回归。判据：**先隔离复跑最大那一堆**：

```bash
cd backend && ./mvnw -pl services/<被改的服务> -am test -Dtest='<那个测试类>'
```

隔离复跑还红 ⇒ 真缺陷；复跑就绿 ⇒ 环境问题，**不要改代码去迎合一个 flaky 环境**。

**反过来**：**孤立的断言失败**（`expected: 4 but was: 3`）是货真价实的缺陷，别混进「都是 flake」。

### 5.4 验证不过怎么办

**不推 `main`。** 保持 `main` 不变，把卡点写进汇报：
哪个测试、哪条断言、为什么、怎么复现。
**不许**为了让 `main` 能推而去改测试、放宽断言、或跳过用例。

---

## 6. 实际执行（形态 B / C）

**在专用 worktree 里做，不要动主工作区** —— 主工作区是流水线的地盘，
在它上面 checkout `main` 会打断流水线。

```bash
cd /Users/tommy/Project/starcon
git fetch upstream --prune

# ① 建专用 worktree（从 main 出发）
git worktree add --detach .worktrees/main-merge upstream/main
cd .worktrees/main-merge

# ② 合并
git merge --no-ff --no-commit upstream/integration/module-dev
#    形态 A（可快进）改用：git merge --ff-only upstream/integration/module-dev

# ③ 看冲突
git diff --name-only --diff-filter=U

# ④ 解冲突（按第 4 节），解完 git add

# ⑤ 验证（按第 5 节）

# ⑥ 提交
git commit -m "merge(module-dev): 并入集成分支 <日期>"

# ⑦ 推 main
git push upstream HEAD:main
```

### 6.1 合并驱动在 worktree 里可用吗

**可用。** `merge.contract-union.driver` 注册在**仓库本地** `.git/config`，
所有 worktree 共享同一份 `config`。2026-10-19 实证：在临时 worktree 里合并，
台账自动求并成功（`Auto-merging ... module-progress.md`，无冲突文件残留）。

⚠️ 但 `git worktree add` 出来的 worktree **不会**自动带 `.gitignore` 里的 `.worktrees/` 规则 ——
`.worktrees/` 已在**仓库根**的 `.gitignore` 里，所以 worktree 内部不受影响（worktree 不含 `.gitignore` 之外的路径）。
**但 worktree 目录本身在主仓库的 `.gitignore` 里，不会被主仓库的 `git status` 看到。** ✅

### 6.2 收尾

```bash
cd /Users/tommy/Project/starcon
git worktree remove --force .worktrees/main-merge
node tools/module-progress/lane.mjs gate release
```

**两个都必须做。** 漏掉 worktree 清理 → 明天 `git worktree add` 会报「已存在」。
漏掉 `gate release` → 明天撞闸，任务空转一轮。

### 6.3 推 `main` 之前最后确认

```bash
git log --oneline -3          # 确认提交内容对
git diff upstream/main...HEAD --stat | tail -3   # 确认合并进来的东西符合预期
```

⚠️ **推之前确认 worktree 里没有未提交的改动**（`git status --porcelain` 为空）——
带着脏工作区推会漏文件。

---

## 7. 汇报（12 行以内）

- **合并形态**：可快进 / 真合并无冲突 / 真合并有冲突
- **冲突处理**：解了几个文件、分别是什么类（白名单 / 契约 / 台账 / 业务代码）、
  `api-proxy.ts` 是否两层都求并
- **验证结果**：跑了哪一级、定向验证逐项的真实输出、是否跑了全量 L2（以及为什么）
- **push 结果**：commit hash + 推的分支；**没推要明确写「验证未绿，本轮不推 `main`」**
- **闸**：acquire/release 是否成功
- **台账**：行数是否恒为 465
- **风险提示**（可选）

**不许谎报**：没推就说没推，验证没跑就说没跑，冲突没解完就说没解完。

---

## 8. 最高优先级禁令

- **验证没绿绝对不推 `main`**
- **不许把 `main` 的冲突「取一边」糊掉** —— 白名单/契约/台账取一边 = 静默删交付成果
- **不许在主工作区 checkout `main`** —— 会打断流水线
- **不许 force push `main`**
- **不许在 `main` 上改业务代码**（本任务只做「合入」，不开发）
- **不许动 `integration/module-dev`**（那是流水线的集成分支，本任务只读）
- **不许为了让 `main` 能推而改测试、放宽断言、跳过用例**
- **不许在 commit / 汇报里贴 secret**
- **不许谎报「已推送」**
- **必须 `gate release`**（无论成败）
- **必须清理 `.worktrees/main-merge`**（无论成败）
- **不许在冲突解到一半时提交**

---

## 9. 参考：本地运行参数

本任务**没有需要本地覆盖的运行参数**（固定每日 00:00，固定两个分支名）。
若将来要加（例如「推 main 前必须等 CI 绿」），**放本地 gitignored 文件，不要写进本文件** ——
写进版本化文档会让每次调参都产生一个噪音 commit。
