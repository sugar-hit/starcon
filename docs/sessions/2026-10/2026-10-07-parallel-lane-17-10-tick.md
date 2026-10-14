# 17:10 tick —— 两起 api-proxy 冲突解法 + 台账重复行机制定位

本轮 `plan --lanes 3` 派发 17.1.2.2，回收并解冲突并入 3.9.2.2 与 3.9.2.1 两条车道。
交付数 67 → 69。

## 台账重复行：真正的机制（此前只知道症状）

现象：一次 merge 之后 `sync.mjs` 把三个已交付模块打回「未开发」，交付数 67→65。

**机制不是「并集驱动按行 last-writer-wins」，而是更靠后的一步**：

1. `merge-union.mjs` 对台账走行级兜底并集时，把**车道那份陈旧台账的占位行也一并带了进来**，
   于是同一个模块 ID 在文件里出现**两行**（一行是集成侧的「已交付 + 完整证据」，
   一行是车道侧的「未开发 | —」）。
2. `sync.mjs` 的 `readExisting()` 用 `map.set(id, ...)` 读现有行 —— **后写覆盖**。
   占位行排在后面，于是「未开发」赢。
3. 计数随之被重算，看起来像「同步把状态改了」，实际是去重时选错了行。

**判据（比「已交付数对不上」更直接）**：

```bash
grep -oE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' docs/product/capability-blueprint/module-progress.md \
  | sort | uniq -d
```

出现输出 = 有重复行。`uniq -d` 空 = 没有重复，此时再怀疑就只是计数口径问题。

**修法**：按「证据更完整者胜」去重（证据非 `—` 优先，再按 已交付 > 进行中 > 未开发），
删掉占位行，再 `sync.mjs`。本轮两次 merge 各命中一次（5 个模块、5 行）。

**注意**：重复行也会让 `sync.mjs` 输出里多出空行，看起来像格式噪音，别被 diff 的行数吓到。

## merge-ready 冲突后会把 merge 留在进行中 → 下一 tick 硬失败

`lane.mjs merge-ready` 冲突时只 `lane.state='conflict'` 并 `continue`，**不 abort**。
主工作区因此留着 `MERGE_HEAD`，而 `merge-ready` 开头有干净度检查：

```
✗ 主工作区不干净，拒绝切分支   → exit 1
```

也就是说**下一 tick 会在干净度检查处直接退出，连回收都做不了**，冲突永远不会自愈。
遇到冲突若不打算当场解，**必须先 `git merge --abort`** 再写记录。

## plan 会把「正在跑的车道」列进 toDispatch（假重派）

本轮 `plan --lanes 3` 的 toDispatch 里，3.9.1.2 与 17.1.1.3 的 `redispatched=1`，
但这两条**都有活跃 worker**：心跳 09:10:25Z（就在几秒前）、worktree 里有未跟踪的新文件。

照 crontab 的「把 toDispatch 全部派发」执行，会在同一条分支上开第二个 writer。

**派发前逐条核对**：

```bash
node -e "const d=JSON.parse(require('fs').readFileSync('.dev-agent/lanes.json','utf8'));
for(const id of ['3.9.1.2','17.1.1.3'])
  console.log(id, d.lanes[id].state, d.lanes[id].heartbeatAt);"
git -C .worktrees/<id> status --porcelain | head   # 有未提交文件 = 正在写
```

心跳在 10 分钟内 + worktree 非空 → 已有 worker，只派发真正 `newlyPlanned` 的。

## 两起 api-proxy 冲突的共同形态

3.9.2.2 与 3.9.2.1 都是「纯新增」：base 段在两侧都无缺失，
ours = base+N、theirs = base+1，无 contested 分支。所以求并 = 以 ours 为基底追加 theirs 的新增分支。

**验证必须跑行为 diff，不能只数括号**：

```bash
node -e '...' # 对 integration 版与并入后的版分别构造 ^(...)$ 锚定正则，探针对比
```

有效判据是「非新模块的探针 A/B 结果**完全一致** + 新模块探针全部从 DENY 变 ALLOW」。
本轮 3.9.2.1：55 个非 completeness 探针 0 分歧，14 个 completeness 探针全部新放行，5 个拒绝探针 0 泄漏。

**两个自伤记录**（都只报错不静默，值得记住）：

- 用 `eval(lit)` 验证白名单时，字面量以 `^(` 开头会被 JS 当成**按位异或**表达式，整段炸掉 ——
  应该用 `new RegExp("^(?:" + inner + ")$")`。
- 用 `raw.slice(1,-1)` / `slice(2,-3)` 切正则字面量时，`/^(...)$/` 的**收尾斜杠属于定界符**，
  少切一个会得到不平衡的 body，报「Unterminated group」—— 报错方向和真实病因相反，容易误判成
  白名单被改坏。正确是 `slice(3,-3)`（去掉 `/^(` 与 `)$/`）。

## route.test.ts 的冲突形态

两侧都是**文件尾追加的 `it(...)` 块**，git 把两边相同的收尾 `});` 匹配成公共上下文，
于是合并结果里那个 `});` 只剩一份，直接拼接会少一个闭合。

判据：`head` 与 `theirs` 的最后一行都**不是** `});`，而 `>>>>>>>` 之后恰好有一个 `});`。
修法：各自补 `});`，再丢掉公共那一份。

## 台账之外

`sync.mjs --check` 在并入后立刻报「不一致」，是因为并集留下的重复行改变了计数；
去重后即一致。`merge-union` 跑两次都打印「走了行级兜底并集，请人工确认」——
台账每次都要按上面两条复核。
