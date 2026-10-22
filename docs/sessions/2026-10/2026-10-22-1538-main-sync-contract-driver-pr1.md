# 2026-10-22 15:38 集成头 → main 同步（第 29 轮 · PR#1 契约驱动修复已放行 · PR#2 产物待放）

## 摘要

本轮方向与前几轮相反：**集成头 `integration/module-dev` → main**。

**结果：PR#1 已成 PR 并推送，等 CI；PR#2 按仓库自有清单的机器断言暂不放行。**

集成头从 `e328e9c2` 推进到 `2d39fa4b`，`main` 仍是 `b49fe44c`
⇒ `merge-base(main, 2d39fa4b) == b49fe44c` ⇒ **可快进、零冲突**。

本轮落地内容全部落在 `tools/`（契约并合驱动修复 + 生成器 `sort_keys` + 新增 `json-format.py`）与 `docs/`。

## ⚠️ 关键裁决：本轮只能放 PR#1，11 个契约产物**一律不放行**

依据仓库自有清单 `docs/backlog/2026-10-22-contract-union-byte-stability-next-sync-checklist.md`（v5）§0：

```bash
git show origin/main:tools/module-progress/merge-union.mjs | grep -q 'json-format.py'
```

⇒ 实测输出 **STOP**（`main` 上的驱动还是旧版）⇒ 清单明文要求 module-dev→main **必须拆两笔 PR**：

| PR   | 内容                                               | 放行条件                    |
| ---- | -------------------------------------------------- | --------------------------- |
| PR#1 | 驱动修复 + 生成器 `sort_keys` + `__pycache__` 忽略 | 本轮 ✅                     |
| PR#2 | 11 个重生成契约产物                                | `main` 驱动已修好之后（⛔） |

### 为什么这条断言是硬的：旧驱动会把 11 个产物**全部**写回 JS 形态

本轮已实测复现该陷阱：合并落地时 git 执行 merge driver 的 cwd = 工作树顶层，
跑的是**目标分支工作区**那份驱动（此处即 `main` 的旧驱动），不是被合进来那份。

⇒ 实测落地后 6 份 `.openapi.json` 被改写成 JS 形态（`0.000001` 而非 canonical `1e-06`、
键序 `openapi` 在前而非 `components` 在前、无末尾换行），
落地 blob **既不等于 main 也不等于集成头**。

⇒ 处置：`git checkout upstream/main -- <11 个产物路径>`，
复验 **11/11 与 main 逐字节相同**，`packages/api-contracts/` 对 main **零差异**。

## 判据 F：本轮 merge 触及的契约文件面

| 面             | 结果                                                                           |
| -------------- | ------------------------------------------------------------------------------ |
| 被旧驱动改写   | 6 份 `.openapi.json`（catalog / channel / crm / identity / trade / warehouse） |
| 合并后自然一致 | 5 份 `.ts`（catalog / channel / crm / trade / warehouse 的 `contracts/*.ts`）  |
| 落地净差异     | **0**（契约目录整体与 main 逐字节相同）                                        |

## 排版规范化：2 份新清单文档

两份 backlog 文档随合并进入，但 Prettier 判定不合规 ⇒ 会把 CI Lint 失败数从 619 抬到 621。

处置：Prettier 规范化后**验证「仅空白差异、无正文丢失」**：

- 表格行数不变（30 vs 30、14 vs 14），差异全部落在 `| --- |` 分隔行（Prettier 按列宽重排）；
- 剔掉表格行后，正文行逐行精确比对：**仅空行增删**（1 份 +1/−2/+1，1 份 +1）；
- 另有一处**缩进修正**：canonical-form 文档中「本地闸的输入是 MockMvc 导出的快照；」后续两行
  本是同一列表项的续行，原文未缩进会被渲染成独立段落，Prettier 补 2 空格使其归位。

⇒ 结论：**排版修正，语义不变**。

## `__pycache__` 忽略条目（清单 §2 要求随 PR#1 进）

实测缺口：合并前 `git check-ignore` 对 `tools/module-progress/__pycache__` **无命中**，
且 `main` 与集成头的 `.gitignore` 完全相同、都无 python 条目。

处置：在 `.gitignore` 新增 `__pycache__/` 与 `*.pyc`。
复验：建真实目录后 `git check-ignore -v` 命中 `.gitignore:104:__pycache__/`，
文件不再出现在未跟踪列表。

## 验证（真实输出）

| 项                                                                      | 结果                                                         |
| ----------------------------------------------------------------------- | ------------------------------------------------------------ |
| `npx vitest run`                                                        | **462 文件 / 6810 用例全部通过**（10.69s）                   |
| `apps/web` `tsc --noEmit`                                               | exit 0                                                       |
| `node tools/module-progress/sync.mjs --check`                           | `✓ 台账与蓝图一致`（exit 0）                                 |
| 后端迁移文件本轮差异                                                    | **0 个**                                                     |
| 格式闸 `prettier --check`                                               | 失败 **619** 个文件，与 CI 基线 **619** 个**双向差集均为空** |
| 判据 G-1 `node tools/module-progress/merge-union.itest.mjs`             | exit 0（33 断言全绿）                                        |
| 判据 G-2 `python3 -m unittest discover -s tools/codegen -p 'test_*.py'` | 17 例 OK，exit 0                                             |

⚠️ 判据 G 的两组回归 **CI 不跑、L2 不跑、任何 package.json script 都不跑**
（`vitest.config` 的 include 只含 `apps/**` 与 `packages/**`）⇒ **只能显式执行**，
本轮已显式执行并记录。

⚠️ 另记一条环境坑：worktree 里只软链根级 `node_modules` 会让 vitest 大面积
`Cannot find package 'next/server'`（pnpm 为各 workspace 包单独装依赖）。
补齐 `apps/web` / `packages/ui` / `packages/config-tailwind` 三个包的 `node_modules` 软链后全绿。

## 落地内容面（vs main）

| 类别                  | 文件数 | 说明                                                         |
| --------------------- | ------ | ------------------------------------------------------------ |
| `apps/` + `packages/` | **0**  | 与 main **逐字节相同** ⇒ 应用侧无需重测（基线 CI 已覆盖）    |
| `tools/`              | 4      | 驱动修复、生成器 `sort_keys`、`json-format.py`、驱动集成测试 |
| `docs/`               | 6      | 2 份 backlog 清单 + 4 份会话记录                             |
| `.gitignore`          | 1      | `__pycache__` / `*.pyc`                                      |

## 后续待办

1. **PR#2（11 个契约产物）** 等 `main` 上驱动修好后单独走 ⇒ 放行前必须重跑 §0 断言，
   并按清单 §3 判定变化属类别①（回归）还是类别②（预期的一次性排版变更）。
2. **判据 E** 只能在 PR#2 阶段做（需要 `mvnw verify` 产出的 6 份 live 快照），
   且结论必须与「`backend/services/` 自快照生成后未变」一起报。
3. `*.itest.mjs` 无自动执行点这一缺口尚未修（清单 §2「已知缺口二」），
   建议后续给 `package.json` 加一个能进 CI 的执行点。
