# 远端集成头 `origin/integration/module-dev` 与本地双向分叉 ⇒ push 被 non-fast-forward 拒

- 创建: 2026-10-28
- Owner: TBD（**发布侧**负责，见「待办内容」第 1 条）
- 优先级: **P0** —— 直接阻塞「集成头 push」，且与 `main` squash 墙**叠加**（两个 P0 同时卡住同步任务）
- 发现于: 2026-10-28 17:16（main → 集成头同步第 96 轮）
- 关联: `docs/sessions/2026-10/2026-10-28-1716-main-sync-round96-conflict-not-merged.md` ·
  `tools/module-progress/SYNC-MAIN.md` ·
  `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`（另一个 P0）

---

## 背景

「main → 集成头同步」任务的第 5 步是 `git push "$PUSH_REMOTE" integration/module-dev`。
第 96 轮（17:16）做 `git push --dry-run` 时得到：

```
To https://github.com/sugar-hit/starcon.git
 ! [rejected]          integration/module-dev -> integration/module-dev (non-fast-forward)
error: failed to push some refs to 'https://github.com/sugar-hit/starcon.git'
hint: Updates were rejected because the tip of your current branch is behind
hint: its remote counterpart.
```

---

## 现象：不是「远端领先」，是**双向分叉**

第 95 轮（15:54）的记录是「本地集成头领先 `origin/integration/module-dev` **101** 个提交、**落后 0** 个」。
第 96 轮（17:16）变成：

```
git rev-list --left-right --count origin/integration/module-dev...HEAD
1	102
```

⇒ **远端多 1 个提交，本地多 102 个提交** ⇒ 两边**互不包含** ⇒ push 必被拒。

### 四个取证

| #   | 证据                                                                | 取值                                                                                    |
| --- | ------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| 1   | `origin/integration/module-dev` 的 tip                              | `94192b24`（作者 Mavis，**2026-10-28 16:46:06**）                                       |
| 2   | 它的父提交                                                         | `e6f443ae`（**10-26 21:45** 的旧 tip）⇒ **远端是从旧基线上长出来的**                     |
| 3   | `e6f443ae` 是不是本地 HEAD 的祖先                                  | **是** ⇒ 分叉点是 `e6f443ae`，两边**双向**分叉                                          |
| 4   | 远端那 1 个提交的内容                                               | 纯文档：`docs/sessions/2026-10/2026-10-28-1600-pipeline-round107-state-recovery-and-dispatch.md`（198 行，**零业务代码**） |

### 远端那 1 个提交是谁推的

流水线第 107 轮（16:00–16:46）的会话语档自述：

> 「本地集成头落后上游 742 个提交 … `git merge --ff-only upstream/integration/module-dev`
> ⇒ 集成头前进到 `e6f443ae`」

⇒ 那一轮跑的**不是**本 worktree 的集成头（它在一个落后 742 个提交的工作副本上快进到了 `e6f443ae`），
然后把这个位置**推进成了远端 tip**。

⇒ **两个 P0 在这里交汇**：
main 的 tip 是集成头的 **squash 快照**（`823ebca5`），而远端集成头又被**另一个落后的工作副本**推了一笔。
于是同步任务面对的是：**base 被拉回、主侧零新交付、远端与本地互不包含**。

---

## 可解性（已实测，非推测）

```bash
git merge-tree --write-tree HEAD origin/integration/module-dev
# ⇒ 663830b3c3481c6923ef6f3e2cf059a57b4d7a72   rc=0，无冲突段
```

**rc=0 且无冲突** ⇒ 远端那 1 个提交与本地 102 个提交**内容上完全可调和**
（本地根本没有那个文件名，`git ls-tree -r --name-only HEAD | grep round107` ⇒ 0 命中）。

⇒ 调和本身是**一行机械操作**；卡住的不是「解不开」，而是**什么时候解**。

---

## 待办内容

1. **【发布侧】在 L2 绿的窗口把远端那 1 个提交合回本地集成头，再 push**
   （例如 `git merge --no-ff origin/integration/module-dev`，
   再 `git push origin integration/module-dev`）。
   ⚠️ **必须在 L2 绿之后做** —— 本地那 102 个提交里包含 `identity` L2 红点的相关改动
   （batch 107b / 108 的 `identity` 38 个失败例），推红头等于把红点带到远端。
   ⚠️ **禁止 force-push** —— 会丢掉远端那 1 个提交（尽管它是纯文档）。
2. **【流水线侧】查清第 107 轮为何在「落后 742 个提交的工作副本」上 push**
   —— `lane.mjs` 的 `plan`/车道基线选取必须与**当前集成头**一致，
   否则任何一轮「顺手 push」都可能把远端推到与本地不同的历史上。
   （同类问题另见 `docs/backlog/2026-10/2026-10-28-lane-plan-reuses-stale-branch-base.md`。）
3. **【调度侧】给同步任务加一道前置检查**：push 前先 `git fetch` 并确认
   `git merge-base --is-ancestor origin/integration/module-dev HEAD`，
   不成立就**跳过 push 并如实汇报**，而不是让 push 被拒后才被发现。
   （`SYNC-MAIN.md` 第 5 步目前没有这一步。）
4. **【与另一 P0 合并处理】** 修掉本问题**不会**自动拆掉 main 的 squash 墙
   （那是 `origin/main` 的 tip 形态问题，见 `2026-10-21-main-squash-merge-divergence.md`）。
   ⇒ **两个都要修**，否则同步任务仍然每轮撞 26 个冲突。

---

## 影响面

- **阻塞**：`main → 集成头同步` 的第 5 步 push（直到远端那 1 个提交被合回本地）。
- **不阻塞**：本地开发与车道分支（都在本地 worktree 内进行，不依赖 push）。
- **风险**：本地 102 个提交（13.3.1.1 / 13.3.1.2 / 13.3.2.1 / 13.3.2.2 等已交付模块的实现）
  **只存在于本机**。一旦本机 worktree 丢失，这批交付**不可恢复**。
  ⇒ 建议尽快在 L2 绿后 push 出去。
