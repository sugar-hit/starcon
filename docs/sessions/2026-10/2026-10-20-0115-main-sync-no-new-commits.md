# 2026-10-20 01:15 main → 集成头同步：main 推进了，但内容已在集成头里

> 本轮**未 merge、未跑预检、未改任何业务代码**。
> 结果是四选一里的**「无新提交」**。
> 但 `origin/main` 在本轮 fetch 里**确实前进了**（`55bf6888..1bb270a0`）——
> 所以这轮值得记一笔：**main 前进了 ≠ 需要同步**。

## 一句话结论

`git fetch` 后 `origin/main` 从 `55bf6888` 推进到 `1bb270a0`（batch-28 那条线：11.2.3.3 供应商索赔 /
7.8.2.1 分摊结果查看 / 4.4.2.1 商品认领 / 2.4.2.3 映射缺失队列），
但 `merge-base --is-ancestor origin/main integration/module-dev` 判定为**真**——
`origin/main` 的 tip `1bb270a0` **本就是集成头的祖先**。
`git rev-list --count integration/module-dev..origin/main` = **0** ⇒ **要同步的内容为零**。
本轮零改动，闸已释放。

## 本轮参数（实时读取，非历史文档）

| 项 | 值 | 来源 |
| --- | --- | --- |
| 集成头 worktree | `/Users/guan/Projects/starcon-worktrees/integration` | `.dev-agent/PIPELINE.local.md` → `PROJECT_ROOT` |
| 推送远端 | `origin` | 同上 → `PUSH_REMOTE` |
| 协议文件 | `tools/module-progress/SYNC-MAIN.md`（**集成头内那份**，主工作区那份是旧版） | 本轮实际读取路径 |
| 集成头 head | `2a32b97b`（== `origin/integration/module-dev`，零偏差） | `git rev-parse` |
| `origin/main` tip | `1bb270a0` | `git rev-parse` |
| 车道状态 | 8 条全 `merged`，无可回收车道 | `.dev-agent/lanes.json` |

## 取证一：闸是 free，拿到了，没抢

```
$ node tools/module-progress/lane.mjs gate status
{ "gate": "free", "ageSeconds": null, "ttlSeconds": 4200, "stale": false }

$ node tools/module-progress/lane.mjs gate acquire
{ "ok": true, "gate": "acquired" }

$ cat .dev-agent/dispatch.gate/owner
77925
2026-10-19T17:15:07.760Z          # = 2026-10-20 01:15:07 +0800
```

`gate=free` + `stale=false` ⇒ 走 `acquire` 正常路径，**没有**遇到 01:00 那一轮的活 tick 持闸。
本轮取闸时间 01:15:07，收尾已 `gate release`。

## 取证二：main 前进了，但祖先关系为真

```
$ git fetch origin --prune
From https://github.com/sugar-hit/starcon
   55bf6888..1bb270a0  main       -> origin/main

$ git merge-base --is-ancestor origin/main integration/module-dev \
  && echo "NO_UPDATE" || echo "HAS_UPDATE"
NO_UPDATE

$ git merge-base origin/main integration/module-dev
1bb270a054f878c56075bfd7f7fbe6a40c8dc851     # == origin/main 的 tip

$ git rev-list --count integration/module-dev..origin/main
0
```

⇒ `origin/main` 的 tip **就是**两分支的 merge-base，main 的全部历史都在集成头里。

## 关键 trade-off：判据方向不能搞反

本轮最容易踩的坑是**拿反方向的命令当判据**：

| 命令 | 本轮结果 | 含义 |
| --- | --- | --- |
| `git rev-list --count integration/module-dev..origin/main` | **0** | ✅ 正确判据：main 没有集成头缺的东西 |
| `git rev-list --count origin/main..integration/module-dev` | **13** | ⚠️ 反向计数：集成头比 main 多 13 个 commit（batch-29 的 4 条车道 + 01:00 会话文档） |

反向那个 13 **不是**待同步量，而是**发布侧（module-dev → main）的欠账**——
由另一台机器的发布任务负责推。本轮若误用反向计数，就会凭空造出一个
「13 个 main 提交待并入」的假象，把不属于本任务的批次内容重复并一遍。

**判据固定为 `merge-base --is-ancestor origin/main integration/module-dev`**（协议第 2 步原文）。

## 为什么 main 推了 batch-28，集成头却已经含有它

`origin/main` 的 tip `1bb270a0` = `docs(batch-28): 11.2.3.3 / 7.8.2.1 / 4.4.2.1 / 2.4.2.3 —— 四条车道并入集成头，L2 全绿`。
这批内容**先**在集成头 merge 完成（batch-28 批绿后 push），**再**由发布侧推到 main。
所以 sync 方向的 main → 集成头在这批上是**闭合回路**：main 的新内容对集成头而言是**旧内容**。

⚠️ 顺带确认**闭环方向**：本轮集成头比 main 领先 13 个 commit（batch-29 尚未发布到 main）——
这属于**发布侧**的欠账，不是本同步任务的缺口，本轮不代劳、不越界。

## 台账现状（`sync.mjs` 实时值，跑完零 diff）

| 指标 | 值 |
| --- | --- |
| 模块总数 | 465 |
| 已交付 | **218**（46.9%） |
| 进行中 | **0** |
| 未开发 | **247** |
| `sync.mjs` 回写 | **零 diff**（`git status --porcelain` 为空 ⇒ 台账已是最新，无需提交） |

台账无变化是**预期**：本轮没有并入任何提交，交付基线不可能变。
（01:00 那一轮记的 218 与本轮一致，交叉验证了台账未被任何一方写坏。）

## 预检：未触发

协议第 4 步的快速预检（`./mvnw -q compile` + `pnpm typecheck`）**只在 HAS_UPDATE 分支跑**。
本轮 `NO_UPDATE` ⇒ 没有 merge 就没有需要验证的代码 ⇒ **未跑预检、未跑 L2、未产生任何构建产物**。
这既省了发热峰值（协议硬规定：不跑全量 `mvnw verify`），也没有把编译不过的集成头推上去的风险。

## 文件清单（本轮改动）

- `docs/sessions/2026-10/2026-10-20-0115-main-sync-no-new-commits.md` —— 本文件（新增，单独 docs commit）
- `docs/product/capability-blueprint/module-progress.md` —— **无改动**（`sync.mjs` 跑完零 diff）

## 后续待办

1. **发布侧**：集成头领先 main 13 个 commit（batch-29 的 7.8.2.2 / 4.4.2.2 / 4.2.1.1 / 4.2.2.1 + 01:00 文档），
   等发布侧任务把 batch-29 推上 main；那之后 main 的 tip 再次成为集成头祖先，sync 任务继续零改动。
2. **下一轮流水线 L2**：本轮**没有**产生 main merge commit ⇒
   协议里「L2 红了先查 main merge commit」这条排查线索本轮**不适用**，L2 若红仍应先查本批 4 条车道。
3. 若下一轮 main 真正带来集成头缺的内容（`HAS_UPDATE`），预检红 ⇒ `git reset --hard` 回退、**不 push**，
   并在汇报里点名具体编译错误文件。

## 闸

已 `gate release`（无论走到哪一步都释放）。
