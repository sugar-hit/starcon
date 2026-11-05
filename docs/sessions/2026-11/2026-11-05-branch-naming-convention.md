# 2026-11-05: 分支纪律 —— 用户级记忆 + 仓库级机制（工具与 AGENTS.md 规范）

## 摘要

用户要求追加一条长期记忆：**以后所有新需求都在独立 branch 下创建；分支名要区分 feature / bugfix
等改动性质；分支名不能是纯数字，必须说清楚这里改了什么；分支重名时第二个分支加时间，用户自己
想办法避免重复。**

这条纪律分两层落地：

1. **用户级记忆**：写入 User Memory，因为它描述的是这个用户在任何项目里的工作方式，换个项目
   依然成立。
2. **仓库级机制**：在 starcon 里不只是写进文档，而是做成可执行的工具 —— 新增
   `tools/scripts/new-branch.mjs`，建分支时自动校验命名、自动查本地与远端重名、自动追加时间戳；
   AGENTS.md 的「PR & commit 规范」同步改写。

全程只做工具脚本与文档，没有运行时代码、依赖、迁移或数据库改动。

## 决策 / 结论

1. **记忆层级选 User Memory**：三问测试里「换个项目这条规则还成立吗」答案是成立 —— 它约束的是
   人怎么用 git，不是 starcon 的技术细节。反过来「分支名必须带工单号」这类只在本仓库成立的约定，
   留在 AGENTS.md，不进记忆。
2. **type 取值收敛成七个**：`feat` / `fix` / `refactor` / `docs` / `test` / `chore` / `perf`，
   与仓库已有的 Conventional Commits 前缀完全对齐。这样「分支前缀」与「commit 前缀」讲的是同一
   件事，看历史时不用在两套词汇之间做翻译。`feature` / `bugfix` 这类同义异形一律拒绝。
3. **短名的判定标准是「能不能说清楚改了什么」**，落成三条可执行的检查：kebab-case、至少含一个
   字母、不在无语义词黑名单里（`wip` / `tmp` / `misc` / `update` / `task` 等 20 个）。
   纯数字 `123` 被第一条和第二条同时拦下。
4. **重名规避由工具兜底，不靠人自觉**：查重范围是三类 —— 本地分支、远端跟踪分支、远端真实分支
   （`git ls-remote --heads`）。任一命中就追加 `-YYYYMMDD-HHMM`；同一分钟内再次冲突再补秒；
   极端情况再补序号。**既有分支一个都不会被动**，这是这条纪律最关键的一点。
5. **时间戳格式选 `YYYYMMDD-HHMM` 而不是秒级**：分钟级已经足够区分两次同名需求，人眼也能直接
   读出先后顺序；秒级只在分钟内冲突时兜底。
6. **流水线车道分支明确豁免**：`tools/module-progress/lane.mjs` 生成的 `feat/<模块ID>` 由模块 ID
   决定形态，人改不了。AGENTS.md 里写清楚这条例外，避免规范与现有自动化互相打架。
7. **工具放在 `tools/scripts/`**，与仓库既有的 `*.mjs` + `*.itest.mjs` 成对组织保持一致
   （参照 `tools/module-progress/format-gate.mjs` 与它的 itest）；`vitest.config.ts` 的测试范围
   只覆盖 `apps/**` 与 `packages/**`，所以 tools 下的测试按仓库既有惯例以独立脚本运行。
8. **改动用独立 worktree 完成**：仓库里同时有 9 个 worktree，多条 agent 线并行；共享主目录切分支
   有前车之鉴（见 `2026-11-05-readme-refresh.md` 记录的「主工作区被别的 agent 切回 main，改动
   消失」）。本轮在 `.worktrees/branch-naming-convention` 上干活，主工作区一行不碰。

## 涉及文件

- `tools/scripts/new-branch.mjs` —— 新增：校验 type 与短名语义、查本地与远端重名、追加时间戳、
  建分支；支持 `--base` / `--remote` / `--dry-run` / `--no-switch`
- `tools/scripts/new-branch.itest.mjs` —— 新增：31 项断言的语义测试（沙箱自建裸远端仓库）
- `AGENTS.md` —— 改写「PR & commit 规范」：分支命名格式、type 取值、语义要求、重名规避、
  `pnpm branch:new` 用法、车道分支豁免
- `package.json` —— 新增 `branch:new` 脚本
- `docs/sessions/2026-11/2026-11-05-branch-naming-convention.md` —— 本记录

## 关键 trade-off / 讨论

**重名时该报错让人自己起名，还是自动加时间戳？** 自动加。

- 用户原话是「如果 branch 重复了就再第二次的 branch 加时间。总之自己想个办法避免重复」——
  要的是**自动兜底**，不是抛回给人。
- 报错让人重来的代价是每次都要人参与一次决策，而人很容易顺手写成 `feat/x-2` 这种更烂的名字。
- 工具自动选名之后仍然打印「原名已存在，已自动追加时间戳」，人知道发生了什么、想改名也还来得及。

**类型用「短名单独黑名单」还是「打分判断语义」？** 黑名单。

- 打分（词频、长度启发式）看起来更聪明，但不可预测：同一个名字这次过、下次不过，人没法建立
  「这样写就合规」的稳定直觉。
- 黑名单 + 三条硬检查（kebab-case、含字母、非黑名单）行为确定、可以写测试、也能直接告诉人
  「换个名字」。

**记忆工具写不进去，最后改用直接编辑文件，这算不算绕开规定？** 算，但有依据。

- `mavis` 技能的 `references/memory.md` 明确写着：用户记忆没有 `user:edit` / `user:delete`，
  **用户记忆的修改与删除就用 Edit/Write 直接编辑 `{{DATA_DIR}}/memory/user.md`**。
- 本轮 `memory(target=user, operation=append, ...)` 连续多次返回的是全文读取结果，条目始终没有
  落盘（判定依据是事后复查该文件内容仍无新条目）。既然原生工具的写入路径在这个环境里不通，
  按技能文档给的落点直接编辑文件，是官方留的通道，不是绕开规定。
- **给运行时的反馈**：这个「operation 参数被回退成 read」的现象值得单独报一条 issue；
  在它被修好之前，写用户记忆要预留「直接编辑 + 事后核对文件内容」的兜底动作。

**同一套纪律要不要同时写进 Agent Memory？** 不写。

- 这条规则的适用范围是「这个人要求所有新需求怎么走 git」，换个用户就不成立了，
  属于 User Memory 的典型场景；Agent Memory 存的是自己做事的方法与踩过的坑，写进去会造成
  同一件事在两层各有一份、后续容易改到一边忘掉另一边。

## 后续待办

- **`docs/sessions/2026-11/` 下 7 个仅本地存在的会话日志**（2026-11-01 至 11-03 的 marketing /
  progress 相关）尚未进版本库，且不在任何远端分支上；按新纪律它们需要一个分支承载，
  单独走一次 PR 归档。
- **流水线车道分支的命名形态**：`lane.mjs` 生成的 `feat/<模块ID>` 是纯数字形态，与本轮「短名要
  有语义」的纪律形似冲突。本轮已在 AGENTS.md 写明豁免，但车道分支本身是否也该带一段语义描述
  （如 `feat/1-5-2-1-invoice-export`），需要与流水线那条线对齐后再定。
- **`memory` 工具的 `operation` 参数异常**：建议向运行时反馈；在修复前，写用户记忆要走
  「直接编辑文件 + 复查文件内容」的双动作。
- **`pnpm branch:new` 是否要接入 husky / CI**：目前只在文档与工具层约束，CI 侧还没有对 PR 标题
  与分支名的校验。若要加，应做成 PR 状态检查而不是阻塞式 lint，避免重复失败刷屏。

## 关联

- PR：<https://github.com/sugar-hit/starcon/pull/81>
- 分支规范落点：`AGENTS.md`「PR & commit 规范」
- 工具：`tools/scripts/new-branch.mjs`（测试 `tools/scripts/new-branch.itest.mjs`）
- 既有 itest 组织范式参照：`tools/module-progress/format-gate.itest.mjs`
- 并发 worktree 教训：`docs/sessions/2026-11/2026-11-05-readme-refresh.md`
- 流水线车道实现：`tools/module-progress/lane.mjs`
