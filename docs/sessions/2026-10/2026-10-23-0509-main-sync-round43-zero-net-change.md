# 2026-10-23 05:09 集成分支并入 main（第 43 轮 · 零功能增量 · 38 个纯排版冲突取 main 侧）

## 摘要

第 43 轮同步：`upstream/integration/module-dev` → `upstream/main`。

**结果：零功能增量。** 集成分支相对主分支的树差异报 **46 个文件**（+731 / −1458），
但逐文件核对到字节级后确认：**38 个冲突文件两侧内容完全相同（仅排版差异）**，
另有 7 个文件只存在于主分支侧、1 个文件只存在于集成分支侧。
⇒ 合并结果相对主分支的**净增只有 1 个文件**（集成分支带进来的流水线条目）。

台账侧同步印证：功能增量摘要输出「**本次没有新的功能交付或推进**」。

## 取证

| 项                   | 值                                             |
| -------------------- | ---------------------------------------------- |
| 源                   | `upstream/integration/module-dev` = `e1b808e3` |
| 目标                 | `upstream/main` = `4afc4423`                   |
| 共同祖先             | `4ab1d8d1`                                     |
| 树差异               | 46 个文件（+731 / −1458）                      |
| 冲突                 | **38 个，全部 `add/add`**                      |
| 功能增量             | **无**                                         |
| 合并后相对主分支净增 | **1 个文件**（172 行）                         |

⚠️ **提交数口径会虚报**：`git rev-list --left-right --count` 报 **615 / 11**，
而实际树差异只有 46 个文件。判定「有没有新内容」只能看树差异。

⚠️ **本地 `integration/module-dev` 分支指针陈旧**：它停在 `fea0cb25`，
而远端已领先 **283 个提交**。取证一律以远端 ref 为准 —— 拿本地分支比会看到
「784 个文件、30 万行」的假差异。

## 决策

1. **38 个冲突全部取主分支侧。** 依据是两条独立证据：
   - **仓库自身的 prettier 归一化**：两侧内容经归一化后**逐字节相同**（21 个 TS/TSX + 17 篇 md）⇒ 无内容差异。
   - **AST 指纹**：21 个 TS/TSX 的语法树节点序列**逐位相同**（括号节点按「下降进内部」处理）。
2. **主分支侧是 prettier 规范态，集成分支侧是原始态** ⇒ 取主分支侧**零新增格式债**。
   取集成分支侧会让 `format:check` 的失败文件数**比基线多** ⇒ 会被闸拦下。
3. **不跑本地全量 L2**：合并结果的后端、前端、契约产物、台账**与主分支逐字节相同**，
   增量只有 1 个 md 文件 ⇒ 定向验证足够，全量闸交给远端 CI。
4. **主分支侧独有的 7 份纪要保留**：它们不在共同祖先里 ⇒ 单边新增，合并时自动保留，不构成冲突。

## 验证

| 项                                        | 结果                                                  |
| ----------------------------------------- | ----------------------------------------------------- |
| 台账模块行数                              | **465**（恒定）                                       |
| 台账重复 ID                               | **0**                                                 |
| 台账同步自检                              | ✓ 台账与蓝图一致                                      |
| 新增文件 prettier 合规                    | ✓ All matched files use Prettier code style           |
| 冲突面专项（3 个冲突测试文件）            | **42 例全绿**                                         |
| proxy 白名单专项（4 个服务）              | **106 个测试文件 / 629 例全绿**                       |
| 契约产物 vs 源分支                        | `api-contracts` / `sdk` / 台账 **两侧 blob 哈希相同** |
| 合并结果 vs 主分支（后端·前端·契约·台账） | **空差异**                                            |

⚠️ 静态预检（`ADD COLUMN` 无 `DEFAULT` 88 处、`SET NOT NULL` 42 处、`ADD CONSTRAINT…CHECK` 21 处）
**全部是存量**：合并结果的后端目录与主分支**逐字节相同**，本轮未引入任何迁移脚本变更。

## 关键 trade-off

- **为什么不信判定器的类型标签？** 判定器把 38 个 `add/add` 全标成 `content`。
  根因是 `git rev-parse` 在路径不存在时**把参数原样回显到 stdout**，
  导致「路径不存在」被读成「存在」。详见
  `docs/backlog/2026-10-23-merge-resolve-revparse-stdout-echo-mislabels-add-add.md`。
  ⇒ **类型标签只作参考，裁决必须靠归一化取证 + AST 指纹。**
- **为什么 AST 指纹要把括号「下降进内部」而不是连子树一起跳？**
  `ParenthesizedExpression` 的括号是噪声，但**括号里包的是真代码**。
  连子树一起跳会把内部表达式连同操作数一起丢掉 ⇒ 产生假的节点数差异
  （首版实现就在 `care-reminder-model.ts` 上误报 904 vs 916）。
  修正后 21 个文件全部逐位相同。
- **`git diff -w` 不能用来判排版差异**（沿用第 42 轮结论）：折行改的是行边界，不是行内空白。
- **为什么保留主分支侧的 7 份纪要？** 只存在于主分支、共同祖先里也没有
  ⇒ 单边新增，合并时自动保留，不构成冲突，也不该被合入动作改变。

## 涉及文件

- `apps/web/src/app/api/crm/review-replies-allowlist.test.ts`
- `apps/web/src/app/crm/{care-reminders,review-replies}/page.tsx`
- `apps/web/src/components/account-health/{account-health-model.ts,account-health-workspace.tsx}`
- `apps/web/src/components/buyer-risk/{buyer-risk-model.ts,buyer-risk-workspace.tsx}`
- `apps/web/src/components/crm/{care-reminder,customer-contact,message-send,negative-review,review-reply}-*`
- `apps/web/src/components/epr-registration/{epr-registration-model.ts,epr-registration-workspace.tsx}`
- `docs/sessions/2026-10/2026-10-22-*.md`、`2026-10-23-*.md`（17 篇会话记录）
- `docs/product/capability-blueprint/module-progress.md` —— 核验对象，本轮两侧未变

## 后续待办

1. **修判定器的 `blobOf`**（已落 backlog）：改用 `git ls-tree` 或 `rev-parse --verify`，
   并断言返回值是 40 位 hash。**不修则每轮都要重新做一遍类型标签的人工纠偏。**
2. **主分支与集成分支的排版不对称依然存在**（主分支侧是规范态、集成分支侧是原始态）。
   根治要靠「把规范化后的字节回流到集成分支」，超出本任务授权范围 ⇒ **每轮都要重新取证**。
3. **集成分支持续缺主分支侧的纪要**（本轮为 round32 / round37 / round38 / round39 / round40 / round41 / round42 共 7 份），
   它们不在合并基线里，合并时主分支侧自动胜出，**不构成冲突**，但持续造成树差异。
4. **本轮没有跑本地全量 L2**：合并结果与主分支在代码侧逐字节相同，只增 1 个 md 文件。
   若后续轮次出现**代码侧**的真实增量，则按任务书 §5.1 的例外条件补跑全量。

## 关联

- 第 42 轮记录：`docs/sessions/2026-10/2026-10-23-0429-main-sync-round42-compliance-risk-capabilities.md`
- 第 41 轮记录（零净变更复核）：`docs/sessions/2026-10/2026-10-23-0414-main-sync-round41-zero-net-change.md`
- 流水线第 42 轮记录（本轮随合入带入）：`docs/sessions/2026-10/2026-10-23-0430-main-sync-round42.md`
- 判定器缺陷 backlog：`docs/backlog/2026-10-23-merge-resolve-revparse-stdout-echo-mislabels-add-add.md`
- 任务书：`tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`
