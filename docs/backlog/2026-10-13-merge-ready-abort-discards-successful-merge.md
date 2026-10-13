# lane.mjs merge-ready：后续车道的 merge 冲突会连带回滚前面已成功的合并

**发现时间**：2026-10-13 20:00 tick（L2 批次闸期间）
**严重度**：高 · **状态**：本轮已用 `git reset --soft` 抢救，**代码缺陷未修**

## 现象

2026-10-13 20:00 跑 `lane.mjs merge-ready`，4 条 ready 车道：

- `8.1.2.2 库位自动分配` —— **合并成功**，生成 merge commit `d6c882c`（5485 行新增）
- `7.2.1.2 预占分配明细` —— 冲突（`apps/web/src/lib/api-proxy.ts`）
- `8.4.2.1 拆解单` —— 冲突（`apps/web/src/lib/api-proxy.ts`）
- `8.5.2.1 库位利用率` —— 冲突（`api-proxy.ts` + `warehouse/[...path]/route.test.ts`）

脚本报告 `merged: [8.1.2.2]`、`failed: [其余 3 条]`，退出码 2。

**但实际上 8.1.2.2 的成果根本没进集成分支。**

## 根因

`tools/module-progress/lane.mjs:893-926` 的合并循环，对每条车道：

```js
const r = git(['merge', '--no-ff', '-m', `merge(${lane.id}): ${lane.name}`, lane.branch], ROOT, true);
if (r === null) {
  // ... 冲突
  git(['merge', '--abort'], ROOT, true);   // ← 这里
  lane.state = 'conflict';
  continue;
}
```

**`git merge --abort` 的语义不是「撤销这次冲突的合并」，而是「把分支指针退回 `ORIG_HEAD`」。**
而 `ORIG_HEAD` 是**最近一次成功的 `git merge`（或 `git reset`）留下的**，
也就是**上一条车道刚刚成功合并出来的那个 commit** —— 正是我们不想动的东西。

于是循环里的实际效果是：

1. `8.1.2.2` 合并成功 → `d6c882c` 成为 HEAD，同时 `ORIG_HEAD := b41a14f`
2. `7.2.1.2` 冲突 → `git merge --abort` → **HEAD 退回 `b41a14f`，`d6c882c` 被丢弃**

reflog 完整记录了这一串：

```
b41a14f HEAD@{0}: reset: moving to HEAD~1        ← abort 把 d6c882c 冲掉了
d6c882c HEAD@{1}: reset: moving to HEAD
d6c882c HEAD@{2}: reset: moving to HEAD
d6c882c HEAD@{3}: reset: moving to HEAD
d6c882c HEAD@{4}: merge feat/8-1-2-2: Merge made by the 'ort' strategy.
b41a14f HEAD@{5}: commit: docs(pipeline): cron 改半点触发...
```

**为什么难发现**：`d6c882c` 这个 commit 对象还在对象库里，工作区/index 也仍然保留着它的内容
（`git diff --cached d6c882c` 为空 ⇒ index 与该 commit 的树完全一致），
所以「文件都还在」看起来一切正常。只有 `git branch --contains d6c882c` 为空、
`git log` 里看不到它，才暴露出这个 commit 已经**不在集成分支上**（仅靠 reflog 存活）。

**次生伤害**：`lane.state` 已被写成 `merged`、`mergedAt` 已记录，
随后 `gc` 又把 8.1.2.2 的 worktree 删掉了 —— 台账说它交付了，分支上却没有它的代码。
如果本轮没有人工介入，这个状态会一直持续到下一个 tick 的 `merge-ready`
拿脏工作区（`git status --porcelain` 非空）直接 `process.exit(1)` 退出，
**并入通道彻底堵死**，且 8.1.2.2 的成果再也不会回来。

## 本轮抢救动作

```bash
# d6c882c^ == b41a14f == 当时的 HEAD ⇒ reset --soft 是纯快进，零内容变更
git reset --soft d6c882c
```

事后核对：`git status --porcelain` 为空、`git branch --contains d6c882c` 命中集成分支、
`git show --stat HEAD` 显示 16 个文件 5485 行新增全部在位。

## 修复建议（调度器/下一轮处理，不要在批次中途改）

循环里每条车道开始前记录 `before = HEAD`，冲突时**不要**用 `merge --abort`：

```js
if (r === null) {
  const conflicts = git(['diff', '--name-only', '--diff-filter=U']);
  failed.push({ lane: lane.id, conflicts: conflicts ? conflicts.split('\n').filter(Boolean) : [] });
  // 精确退回本条车道开始前的 HEAD，绝不动前面车道已经合进去的东西
  git(['reset', '--hard', before], ROOT, true);
  lane.state = 'conflict';
  ...
}
```

`before` 已经在循环开头就取好了（`lane.mjs:894`），改造成本极低。
`--hard` 在这里是安全的：冲突态下工作区里的改动全部来自这次失败的 merge，
且脚本在进循环前已经做过 `git status --porcelain` 脏检查（`lane.mjs:882`）。

**判据**：修完必须构造一个「第一条车道合并成功 + 第二条车道冲突」的场景验证
**第一条的 commit 仍在 `git log` 上**，否则等于没修。

⚠️ 另一个更稳的替代方案：每条车道合并成功后立刻把该 commit 记下来，
冲突时用 `git reset --hard <本批第一个车道开始前的 HEAD>` 统一回退整批，
再把已成功的车道重新 `merge --no-ff` 一遍。可靠但多一轮 IO。

## 顺带记录：台账与代码可能不一致

`lane.mjs` 的 conflict 车道补记逻辑（`lane.mjs:822-836`，按 `merge-base --is-ancestor` 判定）
**依赖 lane 的 headSha 是集成分支祖先**。本例中 8.1.2.2 被标成 `merged` 而非 `conflict`，
所以它压根没进这段补记逻辑 —— 这也意味着**下轮没有任何自动机制会发现它丢了**，
只能靠 `git log` 人工核对。这类「脚本说合了、git 说没合」的偏差应定期抽查。
