# 2026-10-21 09:00 集成分支同步 PR #12：闸判阻塞，根因是过期的 CI 基线（新发布的 vite 高危公告）

> 本轮**未合并 PR、未推送任何业务代码、未改任何业务代码**。
> 结果是四选一里的**「验证未绿，本轮不合」**。
> PR #12 保持开着，分支 `fae19794` 一个字节未动。

## 一句话结论

同步 PR #12 的**内容是好的**（24 项功能、冲突已按判定工具解完、定向验证全绿），
但 `ci-gate.mjs` 判**阻塞**：PR 侧多出一个 `main` 基线里没有的失败作业 **Security Scan**。

根因不是这次合入：**`main` 自己的树今天跑同一条 `pnpm audit` 也一样失败**
（`vite@7.3.6` 命中新发布的 GHSA-587h-7wmj-h5pm），而 `main` 的基线是 2026-10-20 12:44 UTC
记录的那次 run —— 那次只看到 3 个漏洞、1 个 high，是**公告发布之前**的快照。
⇒ **基线过期，不是回归。** 但闸只看作业名，看不到这层因果，所以它必然判阻塞。

**解除动作在开发流水线侧**（vite 升到 `>= 7.3.7` 落到 `integration/module-dev`），
已按 backlog 规范落档：`docs/backlog/2026-10/2026-10-21-ci-baseline-vite-advisory-blocker.md`。

## 本轮参数（实时读取，非历史文档）

| 项                       | 值                                                                                     | 来源                          |
| ------------------------ | -------------------------------------------------------------------------------------- | ----------------------------- |
| 调度闸（开始 / 结束）    | `gate=free` / `ageSeconds=null` / `ttlSeconds=4200` / `stale=false`                    | `lane.mjs gate status`        |
| `upstream/main` tip      | `d28188d6`（Integration/module dev (#11)，2026-10-20 20:39:44 +0800）                  | `git log -1 upstream/main`    |
| 集成分支 tip             | `7be7e4db`（2026-10-21 08:37:35 +0800）                                                 | `git rev-parse`               |
| 同步 PR 分支 tip         | `fae19794`（两个父提交：`d28188d6` + `7be7e4db` ⇒ 真合并）                             | `git log -1`                  |
| PR                       | #12 `feat: 到岸成本、成本分摊等 24 项能力交付`，OPEN，MERGEABLE                          | `gh pr list`                  |
| 本轮取闸 / 放闸          | **未取闸**（全程只做只读检查与临时 worktree 定向验证，没有合并/解冲突/推送）             | —                             |
| 台账（PR 分支）          | 465 行 L4 模块，无重复 ID，`sync.mjs --check` 一致                                      | `sync.mjs --check`            |

## 取证一：新内容有，但 PR 分支已经是最新，不需要刷新

```
$ git rev-list --left-right --count upstream/main...upstream/integration/module-dev
1	236                                  ← 提交数口径（squash 合入 ⇒ 虚高）

$ git diff --stat upstream/main upstream/integration/module-dev | tail -3
405 files changed, 282177 insertions(+), 75569 deletions(-)

$ git diff --quiet upstream/main upstream/integration/module-dev && echo 无新内容 || echo 有新增内容
有新增内容
```

⇒ **树差异有 405 个文件**，但同步 PR 已经在上一轮建好：

```
$ git merge-base --is-ancestor upstream/integration/module-dev upstream/chore/sync-module-dev
（是 —— 集成分支 tip 已是 PR 分支的祖先）

$ git diff --stat upstream/chore/sync-module-dev upstream/integration/module-dev
（空 —— 两棵树逐字节相同）
```

⇒ **PR 分支已经带齐集成分支的全部内容，本轮零推送**（推了只会白触发一次 CI）。

## 取证二：冲突解法复核 —— 6 个文件，全部可机械执行

```
$ node tools/module-progress/merge-resolve.mjs --base upstream/main --head upstream/integration/module-dev
冲突文件 6 个（upstream/main → upstream/integration/module-dev）

[取集成分支侧] apps/web/src/app/pricing/cost/cost-composition-page.test.ts
[取集成分支侧] apps/web/src/components/pricing/cost-composition-model.test.ts
[取集成分支侧] apps/web/src/components/pricing/cost-composition-model.ts
[取集成分支侧] apps/web/src/components/pricing/cost-composition-workspace.tsx
[取集成分支侧] apps/web/src/lib/api-proxy.ts
    依据：main 侧是集成分支历史上的祖先快照，且累积型三件套全部成立 ⇒ 取新侧在结果上等同于完整求并
    累积型三件套：集合包含 ✓ · 有序子序列 ✓ · 零删除 ✓（条目 73 → 97）
[取集成分支侧] backend/services/channel/src/test/java/com/starcon/channel/ListingPublishJobIntegrationTest.java

结论：所有冲突都有可机械执行的解法
```

复核合并结果本身：

```
$ git diff --stat upstream/integration/module-dev upstream/chore/sync-module-dev -- \
    apps/web/src/lib/api-proxy.ts docs/product/capability-blueprint/module-progress.md packages/api-contracts/
（空 —— 合并结果与集成分支侧逐字节一致）
```

⇒ 6 个冲突文件**没有一处内容丢失**，也没有一处把 `main` 的旧快照塞回去。

台账那条 `merge-union: 走了行级兜底并集，请人工确认这个文件改对了`
与 `merge-union: 台账按模块 ID 求并集，折叠了 465 行同 ID 记录`
**是同一段代码打印的**（`merge-union.mjs` 的台账分支），
不是「格式没认出来」的兜底 —— 人工核对项即台账行数与重复 ID，已核（见取证三）。

## 取证三：定向验证的真实输出（全部在 PR 分支内容上跑）

```
$ grep -cE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' docs/product/capability-blueprint/module-progress.md
465
$ grep -oE '^\| [0-9]+\.[0-9]+\.[0-9]+\.[0-9]+ ' docs/product/capability-blueprint/module-progress.md | sort | uniq -d
（空 —— 无重复 ID）

$ node tools/module-progress/sync.mjs --check
✓ 台账与蓝图一致
```

本次新增 25 个迁移文件的静态预检（只扫新增文件，不扫仓库存量）：

```
ADD COLUMN 无 DEFAULT   （空 —— 命中的 3 处全在注释里）
SET NOT NULL            （空 —— 同上）
ADD CONSTRAINT ... CHECK（空）
ALTER TABLE             （只有新建表上的 ENABLE / FORCE ROW LEVEL SECURITY）
```

冲突面专项（白名单是累积型文件，必须真跑代理断言）：

```
$ pnpm vitest run apps/web/src/app/api
Test Files  125 passed (125)
Tests       724 passed (724)

$ pnpm vitest run apps/web/src/lib/api-proxy.test.ts apps/web/src/lib/api-proxy-union.test.ts
Test Files  2 passed (2)
Tests       262 passed (262)
```

**本轮没有跑本地全量 L2**：本次增量只有「合并动作本身」，
`main` 的内容来自集成分支、流水线推它之前已跑过全量 L2，
本地再跑一遍是发热高峰且无信息增量（协议第 5 节默认）。

## 取证四：闸工具判定 + Security Scan 的真实根因

```
$ node tools/module-progress/ci-gate.mjs --pr 12
基线（main 最近一次 run #39423999973）：
  ✗ Catalog Browser & Contract Tests
  ✗ Java 21 & PostgreSQL Integration
  ✗ Lint
PR #12（run #39523293908）：
  ✗ Catalog Browser & Contract Tests
  ✗ Java 21 & PostgreSQL Integration
  ✗ Lint
  ✗ Security Scan

新增失败作业：Security Scan
新增失败用例：无
明细不可比的作业（只比对了作业粒度）：Catalog Browser & Contract Tests、Lint

结论：阻塞 —— 出现了 main 基线里没有的失败，不合并。
```

Security Scan 的作业内容是 `pnpm audit --audit-level=high`，失败明细：

```
high  launch-editor: `fs.existsSync()` existence check can be bypassed on Windows
Package  vite
Vulnerable versions  >=7.0.0 <=7.3.6
Patched versions     >=7.3.7
Paths  . > vitest@4.1.11 > vite@7.3.6
       . > vitest@4.1.11 > @vitest/mocker@4.1.11 > vite@7.3.6
More info  https://github.com/advisories/GHSA-587h-7wmj-h5pm
6 vulnerabilities found
Severity: 4 moderate | 2 high
```

**三项交叉验证，指向同一个结论「基线过期」：**

① PR 没有改任何依赖清单：

```
$ git diff --stat upstream/main upstream/chore/sync-module-dev -- pnpm-lock.yaml package.json 'apps/*/package.json' 'packages/*/package.json'
（空）
```

② `main` 自己的树今天跑同一条命令，输出**逐字相同**、退出码也是 1：

```
$ cd <upstream/main 的 detached worktree> && pnpm audit --audit-level=high
6 vulnerabilities found
Severity: 4 moderate | 2 high
$ echo $?
1
```

③ 基线那次 run 当时看到的更少（2026-10-20T12:44:13Z）：

```
3 vulnerabilities found
Severity: 2 moderate | 1 high      ← 通过
```

⇒ 多出来的 2 个 high 就是 vite 那条公告，**它在基线记录之后才生效**。

## 取证五：CodeQL 的「1 个高危」同样不是本次引入

PR 上 CodeQL 判失败：「7 new alerts including 1 high severity security vulnerability」。
逐条核对仓库现存告警与本次合入的关系：

| 告警                                        | 引入提交         | 本次 PR 是否改动该文件           |
| ------------------------------------------- | ---------------- | -------------------------------- |
| `js/redos`（error → high）                 | `d28188d6`（main） | **未改动**（逐字节相同）        |
| `js/unnecessary-use-of-cat`（error）        | `d28188d6`（main） | **未改动**                      |
| `js/identity-replacement`（warning）        | `d28188d6`（main） | 改的是 305–325 行，告警在 454 行 |
| `js/incomplete-multi-character-sanitization`×2 | `d28188d6`（main） | **未改动**                    |
| `js/xss-through-dom`（warning）            | `d28188d6`（main） | **未改动**                      |

⇒ 6 条告警全部指向 **`main` 当前 tip**，本次合入**没有引入任何新的安全缺陷**；
但那 2 条 error 级告警确实**已经在 `main` 上**，且 GitHub 对 PR 引入的 high 级告警判失败。

另外，`main` 的提交上**一次 CodeQL 检查记录都没有**
（`gh api .../code-scanning/default-setup` → `state: not-configured`），
⇒ PR 侧做「新增告警」比对时**没有 main 侧分析可参照**，仓库里每一条存量告警都会被算成「本 PR 新增」。

## 决策：为什么不合并

协议的硬性规则（`MAIN_MERGE_CRON_PROMPT.md` 第 9 节）写死：
**「不许在出现比 main 基线更多的失败时合并（用 7.3 的闸工具判，不许凭感觉）」**，
第 1.3 节同时写死：**出现比基线更多的失败 ⇒ 本轮不合 PR，但必须把卡点写清楚**。

⇒ 本轮**不合**。闸工具的结论是 `阻塞`，我没有绕过它。

同时我也**没有**把「main 自己也失败」当作放行理由：
那属于凭感觉压过闸工具。解除动作应该由**开发流水线**在 `integration/module-dev` 上做
（vite 升级必须落在集成分支，只修 `main` 的话同步 PR 仍带旧锁文件，闸照样判新增失败），
同步任务对该分支是**只读**的。

## 后续待办

1. **【P0，开发流水线】** 在 `integration/module-dev` 上把 vite 升到 `>= 7.3.7`
   （根 `package.json` 的 `pnpm.overrides` 加约束 + 刷新锁文件；仓库已有三条同类 override 先例）。
   修好后同步 PR 继承新锁文件 ⇒ Security Scan 变绿 ⇒ 闸放行。
   已落档 `docs/backlog/2026-10/2026-10-21-ci-baseline-vite-advisory-blocker.md`。
2. **【P1，开发流水线】** 修掉 `main` 上已有的 2 条 error 级 CodeQL 告警
   （`js/redos` 在 `hierarchy-authorization-workspace.tsx:95`、`js/unnecessary-use-of-cat` 在 `lane-merge-abort.itest.mjs:85`）。
3. **【P2，发布侧】** `main` 的 CI 基线**不会自己刷新**：
   CI 只在 `push` 到 `main` 与 `pull_request` 指向 `main` 时触发，`.github/workflows/` 下**没有任何 `schedule`**。
   ⇒ 在第 1 条落地前，后续每一轮同步都会撞同一道墙。
4. **【P2，发布侧】** 让默认代码扫描也覆盖 `main` 的 push 事件，给 PR 侧一个可参照的 CodeQL 基线。
5. **【协议修正，流水线侧】** 任务书 §4.3 与 §5.2 指向的
   `apps/web/src/app/api/[...path]/route.test.ts` **在仓库里不存在**（代理路由按服务分目录）。
   真正覆盖白名单的是 `apps/web/src/lib/api-proxy.test.ts` 与 `apps/web/src/lib/api-proxy-union.test.ts`，
   各服务代理行为在 `apps/web/src/app/api/<服务>/[...path]/route.test.ts`；
   可用命令要在**仓库根**跑（vitest 的 include 是 `apps/**/*.test.ts`，从 `apps/web` 跑会「找不到用例」）：
   `pnpm vitest run apps/web/src/lib/api-proxy.test.ts apps/web/src/lib/api-proxy-union.test.ts`。
   该文件由流水线高频改写（两侧版本相差 389 行），同步任务**没有代改**，以免制造合并冲突。

## 涉及文件

- `docs/sessions/2026-10/2026-10-21-0900-main-sync-vite-baseline-block.md` —— 本文件（新增）
- `docs/backlog/2026-10/2026-10-21-ci-baseline-vite-advisory-blocker.md` —— **新增**（把阻塞点交给开发流水线）
- 业务代码 —— **零改动**
- PR #12 的分支 `chore/sync-module-dev` —— **本轮零推送**

## 关联

- `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` —— 本轮执行依据的协议
- `docs/sessions/2026-10/2026-10-21-half-hourly-module-dev-pr-merge.md` —— 这个每半小时 PR 任务的设计与决策
- `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md` —— 另一个方向（流水线侧 main 同步）的同源阻塞
- `docs/sessions/2026-10/2026-10-21-0740-main-sync-conflict-squash-divergence.md` —— 流水线侧上一轮（同样 6 文件冲突）
