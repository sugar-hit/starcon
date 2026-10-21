# 2026-10-19: 代码同步与集成头待合并状态核查

## 摘要

用户要求「拉取最新的代码」。执行 `git fetch --all --prune` + `git pull --ff-only` 后确认：
**`main` 与 `origin/main` 完全一致（`55bf6888`），远端 main 无新提交可拉**。
但发现集成头 `origin/integration/module-dev` 比 `origin/main` **多 20 个提交**（批次 21~27）尚未并入 main。
本轮**未执行合并、未执行 push**，仅完成引用同步与状态取证。

## 决策 / 结论

1. **不把集成头并入 main**（用户决策）。`origin/integration/module-dev`（`127db297`）保持原样，等人工在合适时机合并。
2. **不 push 本地 main 的 5 个未推送提交**（用户决策），`progress-site` 交付进度看板相关提交继续留在本地。
3. 合并预演已做过：`git merge-tree --write-tree main origin/integration/module-dev` → exit=0，**无冲突**。后续真正合并时不必再担心文本冲突，但仍需跑 L2 全量验证。
4. 3 个 worktree 车道分支（`dev` / `d7` / `mr`）均与各自 upstream 一致，无需更新。

## 涉及文件

- `docs/sessions/2026-10/2026-10-19-git-sync-integration-head-status.md`（本文件）
- 未修改任何源码；`git fetch` 阶段有远端引用更新（`origin/integration/module-dev`：`5531936d..127db297`）

## 关键 trade-off / 讨论

集成头与 main 的差异规模不小（20 个提交，244 文件，含 3 个业务模块的完整落地 + 4 条 pipeline 工具修复）：

| 集成头新增内容 | 说明 |
| --- | --- |
| 11.5.2.1 质量分析 | 六项专项分析 + 开箱率 + 质量成本估算 |
| 4.1.2.3 套装组件映射 | 组件明细 + 可售性校验 |
| 4.3.2.1 内容回写 | channel/listing 端点 + 迁移 V187 + 集成测试 20 例 |
| pipeline 工具修复 | 双机车道协议、远程执行脚本、PG 闸标签过滤、dispatch gate TTL |

**为什么本轮不合**：按 `tools/module-progress/PIPELINE.md` 的规约，流水线只推 `integration/module-dev`、**不推 main**，且明确要求「合并后的状态必须经 L2 全量验证才算数」（后端 `mvnw verify` + 前端 typecheck/lint/vitest + Next build）。
仓库里有真实事故记录：某车道把某列改成 NOT NULL 且无 DEFAULT 兜底，破坏了早先合入车道的 INSERT，**各自单跑都绿**。
因此「拉代码」与「把集成头合进 main」是两件不同的事，不应在一次拉取里顺带做掉。

## 后续待办

- [ ] 人工决定 `origin/integration/module-dev` → `main` 的合并时机，合并后跑 L2 全量验证
- [ ] 本地 main 的 5 个 `progress-site` 提交待推送（`df0a0775` / `002d3bfb` / `19f33485` / `dda34418` / `919fc338`）
- [ ] `docs/sessions/2026-10/2026-10-07-d5-d7-night-automation.md` 仍是未跟踪文件，待纳入版本库

## 关联

- `tools/module-progress/PIPELINE.md` — 并行车道流水线规约（含「不要 push 到 main」「L2 三层验证」）
- `tools/module-progress/PIPELINE-MULTI.md` — 双机车道协议与远程执行脚本（仅存于集成头）
- `docs/sessions/2026-10/2026-10-19-progress-dashboard-site.md` — 本地未推送的 5 个提交对应的会话日志
