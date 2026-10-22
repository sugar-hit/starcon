# main 合并通道的 PR 次序硬约束：契约产物必须拆两个 PR

**日期**：2026-10-22
**性质**：跨会话协调 + 协议订正（本轮**没有**新的车道功能交付，台账无变化）
**分支**：`fix/main-merge-pr-ordering` → 并入 `integration/module-dev`
**远端预备分支**：`origin/chore/fix-contract-union-driver` = `8627e85a`

---

## 一句话

契约并合驱动被注册成**相对路径**，跑的是**当前检出分支**那份；
而「集成分支并入 main」的任务书流程检出的恰恰是基于 `main` 的分支
⇒ **会把已经 canonical 化的 11 个契约产物重新写回 JS 形态**。
必须拆成 **PR#1（只修工具链、零产物）先合进 main**，**PR#2（才带产物）**。

---

## 1. 背景：main 合并通道的现状（实测）

| 事实                                       | 取证方式                                                                               | 结果                                                                                                                |
| ------------------------------------------ | -------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| 本机是否有「集成分支并入 main」的注册 cron | `cron_read` 列举全部 7 个任务                                                          | **没有**。流水线 `96f8ea76`、main→module-dev 同步 `01450d5d`、进度看板 `8f4761bb`、保活核验 + 3 个一次性 follow-up  |
| 任务书里记的 cron id `fb70d589`            | `cron_read` detail                                                                     | **not found**（已不存在于本机注册表）                                                                               |
| 任务书写的是谁的环境                       | 读 `MAIN_MERGE_CRON_PROMPT.md`                                                         | 项目路径 `/Users/tommy/Project/starcon`、远端 `upstream` —— **与本机不同**（本机 `/Users/guan/...`、远端 `origin`） |
| 当前开着的 PR                              | `gh pr list`                                                                           | **PR #39 OPEN**，`chore/sync-module-dev` → `main`，2026-10-22 14:08 创建                                            |
| PR #39 的内容                              | `gh pr diff 39 --name-only`                                                            | **只有 2 篇 docs**；`git diff origin/main origin/chore/sync-module-dev -- packages/api-contracts/` = **0 个文件**   |
| 驱动修复与 11 个产物是否已上 main          | `git show origin/main:tools/module-progress/merge-union.mjs \| grep -c json-format.py` | **0** ⇒ **没有**                                                                                                    |

⇒ 结论：PR 通道**存在且在跑**（PR #31–#39 都开过），但**执行方不在本机注册表里**，
而任务书本身指向另一台机器。**因此任务书文件是通往 PR 开启方的唯一可读通道。**

---

## 2. 实证：污染在 main 上是活的

main 上 11 个契约产物的当前形态（`git show origin/main:packages/api-contracts/src/...`）：

| 载体                  | main 上的现状                                                                           | 生成器 / 正确形态                                   |
| --------------------- | --------------------------------------------------------------------------------------- | --------------------------------------------------- |
| `contracts/*.ts`      | catalog / channel / crm / trade / warehouse **横幅在末行**；identity 横幅在首行（正常） | 横幅**恒在首行**（`render_types` 先写 `GENERATED`） |
| `*.openapi.json` 浮点 | catalog 2 / channel 5 / crm 6 处 `0.000001`（JS 形态）；`1e-06` **0 处**                | `json.dumps` ⇒ `1e-06`（Python 形态）               |
| `*.openapi.json` 末尾 | **无换行**（blob 最后一字节是 `}`）                                                     | 生成器写 `+'\n'` ⇒ **有换行**                       |
| JSON 键序             | 插入序                                                                                  | `sort_keys=True` ⇒ 字典序                           |

⇒ main 上这些产物是**旧驱动写出的形态**，与 main 自己的生成器输出**逐字节形态不同**
⇒ CI 的 `pnpm contracts:generate` + `git diff --exit-code -- packages/api-contracts/src`
（`ci.yml:140-141`）**一旦跑到就会红**。

⚠️ 但 main 当前的 `catalog-e2e` **更早就挂了**：`gh run view 39731780336` 显示失败发生在
`Wait for all services` 步骤（exit 1），**第 140–141 行根本没轮到执行**。
⇒ 修好服务等待之后，契约闸会**再红一次**。PR#1 与 PR#2 都落地才能真正转绿。

## 3. 根因：驱动跑的是「当前检出分支」那份

- 注册在**仓库本地** `.git/config`：`merge.contract-union.driver = node tools/module-progress/merge-union.mjs %O %A %B`
- 注册值是**相对路径**；git 执行合并驱动时的工作目录 = **工作树顶层**
  ⇒ 跑的是**当前检出分支**工作树里的那份 `merge-union.mjs`
- `MAIN_MERGE_CRON_PROMPT.md` §7.1 的流程是：
  `worktree add --detach $WT upstream/main` → `git checkout -B chore/sync-module-dev` → `git merge upstream/integration/module-dev`
  ⇒ 此刻检出的分支基于 **main** ⇒ **跑 main 那份旧驱动**

旧驱动的两个缺陷（`merge-union.mjs` TS 分支把横幅拼在导出之后；JSON 分支 `JSON.stringify` 不排序）
⇒ 这次合并会把 11 个产物**重排成 JS 形态**。结果 =「合进去了，但 CI 照样红」。

第 27 轮用最小复现实证过「取当前分支那份」这一语义；main-sync 会话又做了镜像复现，两次结论一致。

---

## 4. 本轮交付

### 4.1 §0 次序硬约束写进任务书（提交 `ba98aca8`）

文件：`tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`（**PR 开启方唯一读得到的通道**）

- **新增 §1.5「契约产物必须拆成两个 PR，PR#1 必须先于 PR#2」**：
  - PR#1 白名单 = `merge-union.mjs` + `json-format.py` + `catalog-contracts.py` + `merge-union.itest.mjs`
    （**一个契约产物都不许带**）
  - 放行判据：`git show upstream/main:tools/module-progress/merge-union.mjs | grep -q 'json-format.py'`
    为假 ⇒ **STOP，不许开 PR#2**
  - 备选路径：不 merge、直接把同步分支指到集成分支（驱动根本不跑）——
    **但 CI 仍要求 main 的生成器与产物一致 ⇒ PR#1 无论如何要先合**
  - 合入后自检：横幅在首行、`0.000001` 计数 = 0、`1e-06` 计数 > 0、**数量不变量**（变了就是内容丢失 = 回归）
- **§4.4 补充**：驱动跑的是当前检出分支那份；契约产物的正确形态定义（横幅首行 / 类型名字典序 /
  JSON 键字典序 / 浮点 Python 形态 / 与 codegen 逐字节相同）
- **§7.1 步骤③ 补警告**：本轮若要带产物，先过 §1.5 的放行判据

`.gitignore` 的 `__pycache__/` **刻意没进 PR#1 分支** —— 14:00 tick 在 module-dev 侧已在途补同一行，
两边各加会撞 add/add。

### 4.2 PR#1 预备分支（远端 `origin/chore/fix-contract-union-driver` = `8627e85a`）

构造：`origin/main`(`b49fe44c`) + `cherry-pick e5cb2f11`（驱动修复，4 个工具文件）

- `cherry-pick ba98aca8`（§0 任务书）。两个 cherry-pick 都**干净落地**。

验证：

- `git diff --name-only origin/main 8627e85a` = **5 个文件**
- 其中 `packages/api-contracts/` 差 **0** 个 ⇒ **结构上不可能带产物**
- 该分支上 `node tools/module-progress/merge-union.itest.mjs` **33 断言全绿（exit 0）**
  （含「与 `json-format.py` 逐字节相同」「浮点写成 Python repr（1e-06）」「不出现 JS 的 `0.000001`」
  「paths / components.schemas 键序字典序」）

**没有替它建 PR**：PR → `main` 是外部可见动作，留给执行方/人工拍板。
命令：`gh pr create --base main --head chore/fix-contract-union-driver --title "fix: 契约合并驱动器与生成器输出逐字节一致"`

### 4.3 跨会话消息 2 条

- **14:00 流水线 tick**（`mvs_629b82e6…`）：PR#1 分支就绪 + 零产物校验 + 放行判据 +
  `.gitignore` 不要重复加 + itest 未进 CI/L2 的缺口
- **第 27 轮同步**（`mvs_377bfdd46…`）：**一次性 followup cron `self-021d031b…` 15:23:47 触发但没有「下一次」**
  ⇒ 闸若仍 held 则 `/tmp/starcon_session_round27_followup.md` 会过期丢失；
  附 §0 结论 + PR#1 分支位置 + 判据 E 前置口径（契约产物是**测量目标**，不是前置）

---

## 5. 仍开着 / 未做

| 项                               | 状态                                                                                |
| -------------------------------- | ----------------------------------------------------------------------------------- |
| PR#1 的 PR 未建                  | 有意：外部可见动作，等人工                                                          |
| PR#2（11 个产物）未开            | 必须等 PR#1 合进 main 后再放行                                                      |
| `main` 的契约污染未清            | 要等 PR#1 + PR#2 都落地才转绿                                                       |
| `*.itest.mjs` 未接进 CI / L2     | 待用户定夺的五条协议建议之一，未擅自改 `PIPELINE.md`                                |
| `__pycache__/` 未进 `.gitignore` | 由 14:00 tick 在 module-dev 侧补（避免 add/add）                                    |
| 本轮 L2                          | 由 14:00 tick 独占跑（batch-63 的 11 项契约闸），本会话**不在 L2 期间向集成头写入** |

## 6. 关键决策与 trade-off

1. **把约束写进任务书，而不是只发消息** —— PR 开启方不在本机注册表里，
   任务书是它唯一读得到的通道；消息只在同机会话间有效。
2. **PR#1 与 PR#2 的切分天然干净** —— `e5cb2f11` 恰好只含 4 个工具文件，
   `c862c529` 恰好只含 11 个产物 ⇒ 不需要人工挑拣，cherry-pick 即可得到零产物的 PR#1。
3. **不替人建 PR** —— PR 会进 `main` 的候选路径，且当前 PR#39 还开着；
   与其抢同一个分支，不如交出一个「结构上不可能带产物」的分支。
4. **不改 `PIPELINE.md` 的五条协议建议** —— 属于策略变更，等用户定夺；本轮只改
   `MAIN_MERGE_CRON_PROMPT.md` 里**已被实测证伪的流程缺陷**（不是新增策略）。

---

## 7. 补记（15:45）：第 27 轮同步会话的两条更正，实测落地

第 27 轮同步会话提出两条，我都实测过，结论与他们的方向一致，但**现状已经变了**：

### 7.1 `format:check` 覆盖 `.md` —— 确认，且比「格式债」更具体

- `ci.yml:26` 跑 `pnpm format:check`，匹配 `**/*.{ts,tsx,js,jsx,json,md,yaml,yml}`
  ⇒ **Markdown 在格式闸门内**。
- **全仓实测 621 个文件不合 Prettier**（多为历史 `.tsx` / `.test.ts`）⇒ `Lint` 早已红，
  是**存量债**，不是本次合入造成的。
- **本轮新提交的文件全部 prettier-clean**：对 `2d39fa4b..ef45304a` 之间改动的 6 个 `.md`
  逐个 `prettier --check` ⇒ **全部通过**（含第 27 轮的清单 v6、batch-63 说明、本文件）。
  ⇒ 他们「请 14:00 tick 顺手 `prettier --write`」的诉求，**已经由第 27 轮的自执行脚本满足**
  （脚本提交前跑了 `prettier --write`），不需要再补。
- ⇒ 给任务书补了这条事实（§3 表格下方）：**判据看失败文件集合的差集，不是看仓库总数**。

### 7.2 `1e-06` 不是每个服务都有 —— 确认，我的 §1.5 写窄了

集成头当前 canonical 产物的浮点形态实测：

| 服务                         | `0.000001`（JS 形态） | `1e-06`（Python 形态） |
| ---------------------------- | --------------------- | ---------------------- |
| catalog                      | 0                     | 2                      |
| channel                      | 0                     | 5                      |
| crm                          | 0                     | 6                      |
| trade / warehouse / identity | 0                     | **0**                  |

⇒ 我原先在 §1.5 写的自检命令用 catalog 举例并写「`1e-06` 期望 > 0」，**写法有误导性**：
照搬到 identity / warehouse / trade 会误判 FAIL。
⇒ **已改成通用判据：任何服务都不得出现 `0.000001`**；并明确写出 `1e-06` 的逐服务分布。

### 7.3 本次补记的提交

`tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` 两处订正（+21 −3）：

- §1.5 自检段：浮点判据改为「不得出现 `0.000001`」，附 `1e-06` 逐服务分布
- §3 CI 段：补 `format:check` 覆盖 `.md` 的事实 + 621 个存量失败 + 「看差集不看总数」
