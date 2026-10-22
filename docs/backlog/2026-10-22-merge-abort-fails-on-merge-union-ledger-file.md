# `git merge --abort` 在 `merge-union` 改写台账后会失败，`MERGE_HEAD` 残留堵死流水线

**优先级：P0 —— 这不是理论风险，而是第 25 轮同步任务**实测撞上**的。**

**状态：待决（等用户拍板改协议或改驱动）。本轮只做了「用 `git reset --hard HEAD` 兜底」，没改驱动器、没改协议。**

---

## 现象（2026-10-22 13:02，第 25 轮同步任务实测）

同步任务在集成头 worktree 里执行 `git merge --no-ff origin/main`，合并因 3 个文件冲突失败，
按协议执行 `git merge --abort` —— **退出码 128**：

```
error: Entry 'docs/product/capability-blueprint/module-progress.md' not uptodate. Cannot merge.
fatal: Could not reset index file to revision 'HEAD'.
```

abort 之后 `MERGE_HEAD` **仍在**：

```
$ ls "$(git rev-parse --git-dir)/MERGE_HEAD"
/Users/guan/Projects/starcon/.git/worktrees/integration/MERGE_HEAD
```

## 根因

`.gitattributes` 给台账文件配了并合驱动器：

```
docs/product/capability-blueprint/module-progress.md  merge=contract-union
```

`merge-union` 驱动在合并过程中**同时改写 index 与工作区**：

- index 里按模块 ID 求并，折叠 465 行同 ID 记录；
- 工作区再落一份求并结果（并额外打印
  `merge-union: 走了行级兜底并集，请人工确认这个文件改对了`）。

⇒ 冲突解算完之后，该文件的 git 状态是 **`MM`**（staged 有改动 + 工作区又被改），
`git merge --abort` 要把 index 复位到 `HEAD`，发现**工作区内容与 index 不一致**，
按 git 的安全策略**拒绝覆盖** ⇒ abort 报错退出。

⇒ 与 `lane-merge-abort.itest.mjs` 里断言的两条不变式直接冲突：

> ① 合并失败后没有 MERGE_HEAD、没有冲突标记、工作区干净；
> ② 下一次 merge-ready 仍能正常推进（不被脏树挡住）

**第 ① 条现在会在「台账进了 merge-union」时破。**

## 后果

协议 `SYNC-MAIN.md` §3 明确写着：

> `git merge --abort`  # 必须 abort，否则 MERGE_HEAD 残留会堵死流水线的 merge-ready

而 `lane.mjs` 的 `merge-ready` 正是靠

```js
git rev-parse -q --verify MERGE_HEAD
```

判「上一轮是不是还卡在合并里」，一旦有残留就**直接 exit(1)，并入通道彻底堵死**。
⇒ 同步任务如果照协议字面执行 abort 而 abort 失败，**下一个 tick 的流水线会整个卡住**。

本轮实测的修复（在 abort 失败后补一步）：

```bash
git reset --hard HEAD     # 强制复位 index + 工作区，并清掉 MERGE_HEAD
git merge --abort 2>/dev/null || true   # 此时已是 "no merge to abort"，可忽略
# 显式校验（不要只看命令退出码）：
git rev-parse -q --verify MERGE_HEAD      # 必须为空
git status --porcelain                     # 必须为空
git grep -l -E '^(<<<<<<<|>>>>>>>) ' -- .  # 必须无命中
```

实测结果：`MERGE_HEAD` 已清、工作区干净、`git grep` 无冲突标记、HEAD 回到 PRE。

## 三个可选修法（建议选 ①）

| 选项 | 做法 | 代价 | 风险 |
| --- | --- | --- | --- |
| **①（建议）** | **改协议**：`SYNC-MAIN.md` §3 的 abort 步骤改成「`git merge --abort` **失败也必须继续**用 `git reset --hard HEAD` 兜底 + 显式校验 `MERGE_HEAD`/`status`/`冲突标记` 三项」 | 只改文档，不碰驱动器 | 无；本轮已实证可行 |
| ② | **改驱动**：`merge-union` 驱动改成只写 index、不写工作区（或反之），让 abort 不再撞「not uptodate」 | 要动 `tools/module-progress/lane.mjs` 里注册驱动的代码 | 中；驱动是求并逻辑的核心，改动面大，且要重跑契约求并回归 |
| ③ | **给台账退出驱动名单** | 去掉 `.gitattributes` 里台账那行 | **高**：台账会退回普通三方合并 → 每轮必冲突在台账上，且会丢汇总块 |

## 为什么不能靠「冲突了就不 abort」

`lane.mjs` 里 `merge-ready` 自己的注释已经点明：

> **但必须立刻 abort**：不 abort 的话 MERGE_HEAD 残留、主工作区保持脏，
> 于是 ① 下一个 tick 的 merge-ready 会在「主工作区不干净，拒绝切分支」处直接退出，
> 并入通道彻底堵死；② 本循环里下一条车道会在同一棵冲突树上再 merge，
> 报出与它自身毫无关系的级联冲突。

⇒ **必须 abort**，只是「怎么 abort」在台账进了 merge-union 之后需要加一步兜底。

## 复现方式

```bash
# 在集成头 worktree 里，构造一个「台账进 merge-union + 另有文件冲突」的三方合并
git merge --no-ff <某个与集成头有冲突的 ref> -m test
git merge --abort        # 预期 exit 128 + "not uptodate"
```

> 第 25 轮原始现场：`origin/main` tip `89395bc8`，
> 冲突文件 `apps/web/src/lib/api-proxy.ts`、
> `docs/backlog/2026-10-20-contract-drift-ci-gate.md`、
> `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md`（3 文件 11 区）。
