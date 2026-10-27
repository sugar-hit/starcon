# lane.mjs plan：复用「上一轮遗留的车道分支」时不做基线校正 ⇒ 重派车道在陈旧基线上开发

**发现时间**：2026-10-28 03:03 tick（batch-107 派发前）
**严重度**：中 · **状态**：🔴 未修 —— 本轮用**人工 `reset --hard` 止血**，修法留给下一轮专门处理

## 现象

batch-107 的 `plan --lanes 2` 排出来两条车道，其中 **14.2.1.2 分时策略** 的分支基线是陈旧的：

```
integration/module-dev = f8b9383a
feat/14-2-1-2          = 01e2ef06   ← 落后 3 个 commit
```

`01e2ef06` 是 round 114 的 docs commit，**比集成头落后**：

```
f8b9383a  merge(fix-catalog-timestamp-assertion-clock): …   ← 集成头 HEAD
67e1381e  test(catalog): 平台类目映射的 timestamptz 守卫改为同钟域比较 …
0c9daaa0  docs(sync): 第 90 轮 main→集成头同步 …
7666d8b2  docs(sync): 第 89 轮 main→集成头同步 …
e90c962b  docs(round-114): L2 终判红 + 修掉泄漏的 keepalive 守护 …
01e2ef06  docs(round-114): 撞闸+PG闸命中+L2在飞 ⇒ 零派发 …      ← 车道分支停在这
```

同一批的 `13.3.1.1` 是新分支，基线正确（`f8b9383a`）。

## 根因

`tools/module-progress/lane.mjs` 的 `plan` 建车道时：

```js
// 车道分支已存在（上一次跑崩了留的）就直接复用，不重新切
const branchExists = git(['rev-parse', '--verify', branch], ROOT, true);
if (!branchExists) git(['branch', branch, base]);
```

**分支存在就无条件复用，不比它与当前 `base` 的关系。**

而 `gc` 只删 worktree（`git worktree remove`），**不删分支** —— 所以：

1. 某轮派发了 14.2.1.2 → 建了 `feat/14-2-1-2`，基线 `01e2ef06`；
2. 那轮车道的成果**没有并入**（模块仍是 `未开发`），worktree 被 `gc` 回收，**分支留在 `01e2ef06`**；
3. 之后集成头前进到 `f8b9383a`；
4. 本轮 `plan` 再排 14.2.1.2 ⇒ 分支已存在 ⇒ 直接复用 ⇒ **车道在 3 个 commit 前的树上开发**。

`lane.baseSha` 记的却是**当前** base（`f8b9383`），与分支实际指向**不一致** ⇒
`land` 的「有没有新提交」闸门（`sha.slice(0,7) === lane.baseSha`）也失去了参照意义。

## 影响

1. **worker 在陈旧树上开发**：拿不到最近合入的修复与契约变化（本例中就少了 catalog 的时钟域修复与两条 sync commit）。
   若本车道改的文件恰好在这 3 个 commit 里也改过，并入时必然冲突。
2. **不会静默丢代码**：`land` 仍会拦「没有新提交」，并入仍走三方合并 ⇒ 最坏是冲突，不是覆盖。
   ⇒ 严重度是「中」而不是「高」。
3. **命中面大**：**任何被重派的模块都会踩到**（`gc` 不删分支 ⇒ 分支必然残留）。
   14.2.1.2 是本轮实测命中的第一个。

## 本轮怎么止血的（**不是**修法）

确认 `feat/14-2-1-2` **没有集成分支之外的独有提交**（`git log f8b9383a..feat/14-2-1-2` 为空）后：

```bash
git -C .worktrees/14.2.1.2 reset --hard f8b9383a
```

worker 尚未开工（工作区干净、0 commit），reset 不丢任何东西。
修正后两条车道的基线都是 `f8b9383a`，与集成头一致。

## 建议的修法

在 `plan` 里把「分支已存在」的分支拆成两种情况：

```js
const branchExists = git(['rev-parse', '--verify', branch], ROOT, true);
if (!branchExists) {
  git(['branch', branch, base]);
} else {
  // 复用残留分支前，先看它相对当前 base 落后多少
  const isAncestor = git(['merge-base', '--is-ancestor', branch, base], ROOT, true) === '';
  const behind = Number(git(['rev-list', '--count', `${branch}..${base}`], ROOT, true) || 0);
  if (isAncestor && behind > 0) {
    // 分支没有任何独有提交，且只是落后 ⇒ 校正基线到当前集成头
    git(['reset', '--hard', base], laneWorktree(id) /* 已存在时 */, true);
    git(['branch', '-f', branch, base], ROOT, true);
  }
}
```

要点：

- **只在「分支没有独有提交」时校正**（`branch` 是 `base` 的祖先）⇒ 绝不会丢 worker 的成果。
- 分支**有**独有提交（上一轮真做完了但没并入）时**不动**，让并入时走正常合并/冲突流程。
- worktree 已存在时要先 `reset --hard` 再 `branch -f`（分支被 worktree 占用时 `branch -f` 会失败）。

⚠️ 修完必须补一条回归测试（照 `tools/module-progress/lane-plan-state.itest.mjs` 的风格）：
「建一个落后于基线的分支 → 跑 `plan` → 断言分支与集成头一致，且分支上的独有提交没被冲掉」。

## 顺带记录：本轮 `plan` 打印了一行 `fatal: Needed a single revision`

`plan` 用 `git rev-parse --verify <branch>` 判断分支是否存在；分支不存在时 git 会往 stderr 打这行 fatal。
`git(...)` 的调用带了静默参数但**没吞掉 stderr**，于是它混在 `plan` 的正常输出里，看起来像报错。

**不是**故障（分支随后被正常创建、worktree 正常建立），但会误导读日志的人。建议顺手改成
`git(['rev-parse', '--verify', '--quiet', branch], ROOT, true)`。
