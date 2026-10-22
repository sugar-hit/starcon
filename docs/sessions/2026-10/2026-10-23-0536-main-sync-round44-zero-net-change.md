# 2026-10-23 05:36 集成分支并入 main（第 44 轮 · 零功能增量 · 38 个纯排版冲突取 main 侧 · 合并结果零净变更）

## 摘要

第 44 轮同步：`upstream/integration/module-dev` → `upstream/main`。

**结果：零功能增量，且合并结果相对主分支零净变更。** 集成分支相对主分支的树差异报
**47 个文件**（+559 / −1661），逐文件核对到字节级后确认：**38 个冲突文件两侧内容完全相同
（仅排版差异）**，另有 **9 个文件只存在于主分支侧**、**0 个文件只存在于集成分支侧**。
⇒ 合并结果的树与主分支的树**逐字节相同**（`3aa3d09e`），合并动作本身**没有带来任何内容增量**。

台账侧同步印证：功能增量摘要输出「**本次没有新的功能交付或推进**」。

⚠️ **集成分支本轮没有前进**：源分支 tip 仍是 `e1b808e3`，与第 43 轮（05:09）取证时**完全相同**。
第 43 轮带进来的那 1 个流水线条目已在主分支就位，主分支因此**完全追平**了集成分支 ⇒ 本轮归零。

## 取证

| 项                   | 值                                             |
| -------------------- | ---------------------------------------------- |
| 源                   | `upstream/integration/module-dev` = `e1b808e3` |
| 目标                 | `upstream/main` = `fbdf691c`                   |
| 共同祖先             | `4ab1d8d1`                                     |
| 树差异               | 47 个文件（+559 / −1661）                      |
| 冲突                 | **38 个，全部 `add/add`**                      |
| 功能增量             | **无**                                         |
| 合并结果 vs 主分支   | **树逐字节相同**（`3aa3d09e` = `3aa3d09e`）    |
| 合并后相对主分支净增 | **0 个文件**                                   |
| 主分支独有           | 9 个文件（8 份历轮纪要 + 1 份 backlog）        |
| 集成分支独有         | **0 个文件**                                   |

⚠️ **提交数口径会虚报**：`git rev-list --left-right --count` 报 **12 / 615**，
而实际树差异只有 47 个文件。判定「有没有新内容」只能看树差异。

⚠️ **`git diff --name-status` 会把「只在一侧存在」的文件算进差异集**：
本轮 47 个差异文件里有 **9 个只存在于主分支侧**（状态 `D`）。
它们**不构成待合入内容**，合并动作也不会改变它们 ⇒ 不影响「零净变更」结论。

## 决策

1. **38 个冲突全部取主分支侧。** 依据是三条独立证据：
   - **仓库自身的 prettier 归一化**：两侧内容经归一化后**逐字节相同**（21 个 TS/TSX + 17 篇 md）。
   - **AST 指纹**：21 个 TS/TSX 的语法树节点序列**逐位相同**（括号节点按「下降进内部」处理）。
   - **规范态方向**：主分支侧 **38/38 已是 prettier 规范态**，集成分支侧 **38/38 不是**。
2. **取主分支侧零新增格式债。** 取集成分支侧会让 `format:check` 的失败文件数
   **比基线多 38 个** ⇒ 会被闸拦下。
3. **合并结果零净变更 ⇒ PR 只带审计记录。** 合并树与主分支树逐字节相同，
   合并动作不产生任何内容增量 ⇒ 本次 PR 与第 43 轮同形，**只承载本轮的会话记录与 backlog**。
4. **不跑本地全量 L2**：合并结果的后端、前端、契约产物、台账**与主分支逐字节相同**，
   本轮增量只有文档 ⇒ 定向验证足够，全量闸交给远端 CI。
5. **主分支侧独有的 9 份记录保留**：它们不在共同祖先里 ⇒ 单边新增，合并时自动保留，不构成冲突。

## 验证

| 项                                                                                  | 结果                                                       |
| ----------------------------------------------------------------------------------- | ---------------------------------------------------------- |
| 台账模块行数                                                                        | **465**（恒定）                                            |
| 台账重复 ID                                                                         | **0**                                                      |
| 台账同步自检                                                                        | ✓ 台账与蓝图一致                                           |
| 冲突面专项（3 个冲突测试文件）                                                      | **42 例全绿**                                              |
| proxy 白名单专项（4 个服务路由测试文件）                                            | **86 例全绿**                                              |
| 合并结果 vs 主分支（整棵树）                                                        | **树逐字节相同**                                           |
| 合并结果 vs 主分支（`backend/` 目录）                                               | **空差异**                                                 |
| 静态预检命中（`ADD COLUMN` 无 `DEFAULT` / `SET NOT NULL` / `ADD CONSTRAINT…CHECK`） | **88 / 42 / 21** —— 与第 43 轮数值逐项相同，**全部是存量** |

⚠️ 静态预检三项命中数与上一轮**完全相同**，且后端目录与主分支**逐字节相同**
⇒ 本轮**未引入任何迁移脚本变更**。

## 关键 trade-off

- **为什么不信判定器的类型标签？** 判定器再次把 38 个 `add/add` 全标成 `content`、
  全判「并入（人工）」。根因与第 43 轮相同（`git rev-parse` 把参数原样回显到 stdout），
  已落 backlog，详见
  `docs/backlog/2026-10-23-merge-resolve-revparse-stdout-echo-mislabels-add-add.md`。
  ⇒ **类型标签只作参考，裁决必须靠归一化取证 + AST 指纹。**
- **为什么本轮差点取错边？** 先用 `prettier --check` 判「哪一侧是规范态」，
  而 **Prettier 3 的 CLI 默认 `--ignore-path` 含 `.gitignore`**：
  对被 gitignore 的临时路径，`--check` **一个文件都不查**，却打印
  「All matched files use Prettier code style!」并以 0 退出 ⇒ **假绿**。
  本轮的取证脚本落在 `.tmp/`（已 gitignore）⇒ 差点据此判成「集成分支侧才是规范态」。
  改用 Node API（`prettier.format` + 真实 filepath）后结论反转为「主分支侧是规范态」。
  详见 `docs/backlog/2026-10-23-prettier-cli-check-skips-gitignored-paths-false-green.md`。
- **为什么 AST 指纹要把括号「下降进内部」而不是连子树一起跳？**
  `ParenthesizedExpression` 的括号是噪声，但**括号里包的是真代码**。
  连子树一起跳会把内部表达式连同操作数一起丢掉 ⇒ 产生假的节点数差异。
- **为什么不跑本地全量 L2？** 合并结果与主分支在代码侧**逐字节相同**，本轮只增文档。
  若后续轮次出现**代码侧**的真实增量，则按任务书 §5.1 的例外条件补跑全量。

## 涉及文件

- `apps/web/src/app/api/crm/review-replies-allowlist.test.ts`
- `apps/web/src/app/crm/{care-reminders,review-replies}/page.tsx`
- `apps/web/src/components/account-health/{account-health-model.ts,account-health-workspace.tsx}`
- `apps/web/src/components/buyer-risk/{buyer-risk-model.ts,buyer-risk-workspace.tsx}`
- `apps/web/src/components/crm/{care-reminder,customer-contact,message-send,negative-review,review-reply}-*`
- `apps/web/src/components/epr-registration/{epr-registration-model.ts,epr-registration-workspace.tsx}`
- `docs/sessions/2026-10/2026-10-22-*.md`、`2026-10-23-*.md`（17 篇会话记录）
- `docs/product/capability-blueprint/module-progress.md` —— 核验对象，本轮两侧未变
- 本轮新增：`docs/sessions/2026-10/2026-10-23-0536-main-sync-round44-zero-net-change.md`、
  `docs/backlog/2026-10-23-prettier-cli-check-skips-gitignored-paths-false-green.md`

## 后续待办

1. **修判定器的 `blobOf`**（已落 backlog L-01…L-04）：改用 `git ls-tree` 或 `rev-parse --verify`，
   并断言返回值是 40 位 hash。**不修则每轮都要重新做一遍类型标签的人工纠偏。**
2. **Prettier CLI 的假绿**（本轮新落 backlog）：不要用 `prettier --check` 判临时目录里的文件，
   改用 Node API 或显式 `--ignore-path .prettierignore`。
3. **主分支与集成分支的排版不对称依然存在**（主分支侧是规范态、集成分支侧是原始态）。
   根治要靠「把规范化后的字节回流到集成分支」，超出本任务授权范围 ⇒ **每轮都要重新取证**。
4. **集成分支持续缺主分支侧的纪要**（本轮为 round32 / round37 / round38 / round39 / round40 /
   round41 / round42 / round43 共 8 份，加 1 份 backlog），它们不在合并基线里，
   合并时主分支侧自动胜出，**不构成冲突**，但持续造成树差异。
5. **本轮没有跑本地全量 L2**：合并结果与主分支在代码侧逐字节相同，只增文档文件。
   若后续轮次出现**代码侧**的真实增量，则按任务书 §5.1 的例外条件补跑全量。

## 关联

- 第 43 轮记录：`docs/sessions/2026-10/2026-10-23-0509-main-sync-round43-zero-net-change.md`
- 第 42 轮记录：`docs/sessions/2026-10/2026-10-23-0429-main-sync-round42-compliance-risk-capabilities.md`
- 判定器缺陷 backlog：`docs/backlog/2026-10-23-merge-resolve-revparse-stdout-echo-mislabels-add-add.md`
- Prettier CLI 假绿 backlog：`docs/backlog/2026-10-23-prettier-cli-check-skips-gitignored-paths-false-green.md`
- 任务书：`tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`
