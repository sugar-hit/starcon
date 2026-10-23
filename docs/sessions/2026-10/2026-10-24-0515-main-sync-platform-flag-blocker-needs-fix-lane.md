# 2026-10-24: main 同步 —— 集成头零推进、闸门仍阻塞，且该缺陷不会被派活链路自动拾取

## 摘要

本轮**没有合入任何内容**，PR #65 保持打开。

- 集成分支相对同步分支头**零推进** ⇒ 没有新交付可刷新。
- 合并形态是**快进**：`upstream/main` 本身就是 `upstream/integration/module-dev` 的祖先，
  冲突判定工具报 **0 个冲突文件**。
- PR #65 的 CI 闸门结论：**阻塞**。新增失败用例 1 条 ——
  `com.starcon.identity.PlatformFlagIntegrationTest.公告生效期按值写回且窗外不展示`。
- **本轮新增结论（此前未记录）**：这条缺陷**不会被流水线自动修复** ——
  派活器只从台账里挑 `未开发` / `进行中` 的行，而 17.7.2.1「平台参数与开关」已是 `已交付`，
  因此不会为它生成车道；`docs/backlog/` 的条目也**不被派活器读取**。
  ⇒ 解除条件必须是**一条专门的 fix 车道**，而不是「再等等」。

## 决策 / 结论

1. **本轮不合 PR #65**：闸门「阻塞」，出现了 `main` 基线里没有的失败用例。
2. **本轮不刷新同步分支**：集成分支零推进，同步分支头已包含集成头的全部提交。
3. **不在同步分支上修那条用例**：它是集成侧交付内容的所有物；在同步分支改断言等于自己把闸门改绿，
   而且会让同步分支与集成分支就此分叉。
4. **不再往 `docs/backlog/` 里加条目**：backlog 不进派活链路，加条目不改变任何派活结果。

## 关键取证

### ① 集成头零推进 ⇒ 无可刷新对象

```
git log --oneline upstream/chore/sync-module-dev..upstream/integration/module-dev      → 空
git merge-base --is-ancestor upstream/main upstream/integration/module-dev             → true
git merge-tree --write-tree --name-only upstream/main upstream/integration/module-dev → 单个树哈希，无冲突条目
node tools/module-progress/merge-resolve.mjs --base upstream/main --head upstream/integration/module-dev
  → 冲突文件 0 个
```

### ② 同步分支头相对集成头的 19 个差异文件

其中 11 个是历轮同步的会话记录、2 个是闸门工具本身；
剩下 6 个（`api-proxy.ts`、`catalog-workspace.tsx`、`app-shell.tsx`、`command-palette.tsx`、
`warehouse-outbound-timeliness-workspace.tsx`、`GrantPermissions.java`）**全部是纯排版差异**：
集成侧按约 80 列折行，仓库 `.prettierrc` 是 `printWidth: 100` ⇒ **同步分支侧才是合规的一方**。
这 6 个文件在两侧的 Lint 清单里**都不出现** ⇒ 本次合入**没有新增排版债**。

### ③ 闸门终态（run 已 `completed`，head 与 PR 头逐位相同）

| 作业                             | 基线 main | PR 本轮 |
| -------------------------------- | --------- | ------- |
| Catalog Browser & Contract Tests | ✗         | ✗       |
| Java 21 & PostgreSQL Integration | ✗         | ✗       |
| Lint                             | ✗         | ✗       |
| Security Scan                    | ✗         | ✗       |
| Typecheck                        | ✓         | ✓       |
| Unit & Integration Tests         | ✓         | ✓       |
| Build                            | skipped   | skipped |

新增失败作业：无。**新增失败用例：1 条**。

### ④ 三个「明细不可比」作业 → 自主下钻到文件级/用例级

- **Catalog Browser & Contract Tests**：两侧**同一个失败**（`channel` 服务
  `LocalCredentialDocumentStorage` 构造抛异常 ⇒ Spring 上下文启动失败）⇒ 持平，无新增。
- **Lint**：基线 646 个不合规文件，PR 侧 758 个；差额全部来自本次新增交付带来的新文件，
  本次合入动过的 6 个文件**两侧都不在清单里** ⇒ 无既有文件退化。
- **Security Scan**：基线 11 项 / PR 侧 10 项，critical 与 high 的包集合相同
  （`Next.js` RCE、`Next.js` DoS`、`launch-editor`）⇒ PR 侧**更少**，无新增。

### ⑤ 新增失败用例的归属：不是合并动作引入的

```
该用例文件在 upstream/main 上不存在                          ⇒ 源分支新交付
集成头与同步分支头该文件逐字节相同（sha 3d813f3ae8d15c58）  ⇒ 不是合并动作引入
```

断言原文：

```
expected: 2026-09-23T21:01:13.280542905Z (java.time.OffsetDateTime)
 but was: 2026-09-23T21:01:13.280543Z (java.time.OffsetDateTime)
	at ...PlatformFlagIntegrationTest.公告生效期按值写回且窗外不展示(PlatformFlagIntegrationTest.java:273)
```

期望值 9 位小数（纳秒）、回读值 6 位小数（微秒）⇒ 差 95 纳秒。
PostgreSQL 的 `timestamptz` 默认精度是微秒，而 `OffsetDateTime.now(ZoneOffset.UTC)` 带纳秒精度 ⇒
**逐值比较必然不等**（确定性失败，不是 flake，也与时区无关）。

### ⑥ 派活链路看不见这条缺陷

`tools/module-progress/lane.mjs` 的 `pickModules()` 只把台账里状态为 `未开发` 或 `进行中` 的行放进候选，
并显式排除 `已交付` 的行 ⇒ **17.7.2.1 永远不会被重新派活**。
`docs/backlog/` 里已有该缺陷的条目，但派活器并不读取 backlog ⇒ **光有条目不解决问题**。

### ⑦ `.worktrees/` 下的目录可能**不是真 worktree** ⇒ 新文件被静默忽略，且会误改主工作区

本轮实测踩到，值得写进流程：

- `.worktrees/main-merge-045656` **没有 `.git` 文件** ⇒ 它不是 git 登记的 linked worktree，
  而只是一个普通目录。git 在该目录里执行命令时**解析到主仓库**，于是：
  - `git checkout -B chore/sync-module-dev <sha>` **切的是主工作区的分支**（把流水线正在用的
    `integration/module-dev` 换掉了），不是那个目录的；
  - `git add` 得到的路径是 `.worktrees/main-merge-045656/docs/...`，
    而 `.gitignore` 里有 `.worktrees/` ⇒ **新文件被 ignore** ⇒ `git status --porcelain` 连
    untracked 都不显示、`git add -A` 静默无操作 ⇒ **写完会话记录却以为提交上了**。
- 在**真** worktree 里实测相反：路径相对 worktree 根，不匹配 `.worktrees/`，
  新文件正常显示为 `??`、正常可暂存。

⇒ **建完 worktree 必须断言一次**：

```bash
git -C "$WT" rev-parse --show-toplevel   # 必须等于 "$WT" 的绝对路径
```

不相等就**立即停止**，不要在里面跑任何写操作。

## 后续待办

- [ ] **派一条专门车道**修 `PlatformFlagIntegrationTest` 的精度假设：
      构造期望值时先截断到微秒（`ChronoUnit.MICROS`），或改成容差比较。
      建议车道名 `fix/17-7-2-1-platform-flag-precision-assertion`。
      **在它合入集成分支之前，每一次合并都会被这一条挡住。**
- [ ] 该车道合入集成分支后，本同步 PR 在下一轮即可通过闸门。
- [ ] 同步任务的 worktree 纪律补一条硬校验（见上 ⑦）：建完 worktree 先断言
      `rev-parse --show-toplevel` 等于 worktree 路径。
