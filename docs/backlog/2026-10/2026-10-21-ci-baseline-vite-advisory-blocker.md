# CI 基线过期导致同步 PR 判阻塞：vite 高危公告（GHSA-587h-7wmj-h5pm）+ main 无 CodeQL 基线

- 创建: 2026-10-21
- Owner: 开发流水线（`integration/module-dev` 侧）—— 同步任务只读该分支，改不了
- 优先级: **P0** —— 不修的话，`main` 自身一直红在 Security Scan 上
- 关联: `tools/module-progress/MAIN_MERGE_CRON_PROMPT.md` ·
  `docs/sessions/2026-10/2026-10-21-0900-main-sync-vite-baseline-block.md` ·
  `docs/sessions/2026-10/2026-10-21-0930-main-sync-baseline-refresh-merge.md` ·
  `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`

---

## 进展更新（2026-10-21 09:30）：合并卡点已解除，本条不再是合并的前置条件

上一轮把解除条件记成「流水线必须把 vite 升到 `>= 7.3.7`」，据此判断
「每一轮都会卡在同一道墙上」。**这个判断不完整**：卡点来自**基线过期**，
而过期的基线**可以按需手动刷新**，不必等流水线先改依赖。

做法（同步任务自己做，只读 `main` 的提交，不改任何代码）：

```bash
# 只重跑基线里过期的那一个作业，拿到它今天的真实结论
gh run rerun 39423999973 --job 118631807667        # Security Scan
# 等它跑完后照常让闸工具自己判
node tools/module-progress/ci-gate.mjs --pr 12
```

重跑后 `main` 自己的 `Security Scan` 同样失败，输出与 PR 侧逐字相同
（`6 vulnerabilities found` / `Severity: 4 moderate | 2 high`），
闸随即判「新增失败作业：无 / 新增失败用例：无」⇒ PR #12 已合入 `main`。

⇒ **第 1 条（升 vite）仍然是欠着的安全债**（`main` 今天真的红），
但它不再阻塞「集成分支经 PR 合入 main」。两条要分开看：
**合并卡点已解，安全债未清。**

⚠️ 顺带修正本文件末尾那句结论：「`main` 的基线不会自己刷新」—— 成立，
但**可以手动刷新**（上面那条 `gh run rerun`）。没有 `schedule` 触发器只说明基线不会**自动**更新，
不说明它无法被更新。

---

## 背景

「集成分支合入 main」任务用 `ci-gate.mjs` 判能不能合，判据是**基线相对**：
**PR 的失败作业必须是 `main` 基线失败作业的子集**，出现基线里没有的新失败作业就阻塞。

`main` 的基线取的是**最近一次 CI run**。那个 run 记录于 2026-10-20 12:44 UTC。
从那之后，**仓库自身的依赖状态**和**公告数据库**都变了，于是基线不再代表 `main` 今天的真实状态。

## 三个可核验的事实

### ① PR 没有改任何依赖清单

```
$ git diff --stat upstream/main upstream/chore/sync-module-dev -- pnpm-lock.yaml package.json 'apps/*/package.json' 'packages/*/package.json'
（空）
```

⇒ PR 侧与 `main` 的依赖清单**逐字节相同**，任何依赖类失败都不可能由这次合入引入。

### ② `main` 自己的树今天跑同一条审计命令，同样失败

在 `upstream/main` 的 detached worktree 里跑：

```
$ pnpm audit --audit-level=high
high  launch-editor: `fs.existsSync()` existence check can be bypassed on Windows
Package  vite
Vulnerable versions  >=7.0.0 <=7.3.6
Patched versions     >=7.3.7
More info            https://github.com/advisories/GHSA-587h-7wmj-h5pm
6 vulnerabilities found
Severity: 4 moderate | 2 high
$ echo $?
1
```

⇒ **`main` 今天跑 Security Scan 也是红的**，与 PR 侧输出完全一致。

### ③ 基线那次 run 当时看到的漏洞更少

```
基线 run #39423999973（2026-10-20T12:44:13Z）：
3 vulnerabilities found
Severity: 2 moderate | 1 high          ← 通过
```

PR run #39523293908（2026-10-21T00:48:46Z）：

```
6 vulnerabilities found
Severity: 4 moderate | 2 high          ← 失败
```

⇒ 多出来的 2 个 high 就是 vite 那条公告，**它是在基线记录之后才被发布/生效的**。

**结论：Security Scan 这一项是「基线过期」，不是「本次合入引入的回归」。**
但闸工具只看得到作业名，看不到这层因果，所以它**必然判阻塞** —— 这不是闸坏了，是闸忠实地报告了「基线已过期」。

## 待办内容

### 1. 把 vite 升到 `>= 7.3.7`（P0，必须落在 `integration/module-dev`）

- 做法：根 `package.json` 的 `pnpm.overrides` 加一条 vite 约束，刷新锁文件。
  仓库里已有三条同类 override 先例（`postcss` / `source-map-js` / `glob@10.3.10`）。
- **必须落在 `integration/module-dev`**，不能只修 `main`：
  只修 `main` 的话，同步 PR 仍然带着 `vite@7.3.6` 的旧锁文件，Security Scan 照红不误，
  闸照样判「新增失败作业」。
- 修好后同步 PR 继承到新锁文件 ⇒ Security Scan 变绿 ⇒ 基线里没有失败作业 ⇒ 闸放行。
- 附带收益：`main` 上那条真实的高危公告（`launch-editor` 在 Windows 上可绕过 `fs.existsSync` 检查）被消掉。

### 2. 修掉 `main` 上已有的 2 条 error 级 CodeQL 告警（P1）

| 规则 | 位置 | 引入提交 |
| ---- | ---- | -------- |
| `js/redos` | `apps/web/src/components/iam/hierarchy-authorization-workspace.tsx:95` | `d28188d6`（= `main` 当前 tip） |
| `js/unnecessary-use-of-cat` | `tools/module-progress/lane-merge-abort.itest.mjs:85` | `d28188d6`（= `main` 当前 tip） |

两条都在 `main` 上，本次同步 PR 一个字节都没改过（同步 PR 只改了
`apps/web/src/components/pricing/cost-composition-model.ts` 的交付状态与说明文字，
其告警在 454 行、且是上一轮 PR 就带进 `main` 的）。
GitHub 的默认代码扫描对 PR 引入的 high 级新告警判失败 ⇒ 这两条不修，
**每一个同步 PR 的 CodeQL 检查都会红**。

### 3. 给 `main` 建立 CodeQL 基线（P2，可选但建议）

`main` 的提交上**一次 CodeQL 检查记录都没有**，代码扫描的默认设置也是 `not-configured`：

```
$ gh api repos/sugar-hit/starcon/commits/d28188d6/check-runs --jq '[.check_runs[].name] | join(", ")'
Build, Typecheck, Catalog Browser & Contract Tests, Lint, Java 21 & PostgreSQL Integration, Security Scan, Unit & Integration Tests
```

⇒ PR 侧做「新增告警」比对时**没有 main 侧分析可参照** ⇒ 仓库里每一条存量告警
都会被算成「本 PR 新增」。建议让默认代码扫描也覆盖 `main` 的 push 事件，
否则这个信号对所有 PR 都是失真的。

## 关联的另一个结构性事实

`main` 的基线**不会自己刷新**：CI 只在 `push` 到 `main` 和 `pull_request` 指向 `main` 时触发，
`.github/workflows/` 下**没有任何 `schedule` 触发器**。

⇒ 基线不会**自动**更新，但**可以手动刷新**（见开头「进展更新」里的 `gh run rerun <run-id> --job <job-id>`），
所以「基线停在 2026-10-20 那次 run」并不等于「每轮必然撞墙」——
只要在**外部输入漂移**（公告发布、依赖变更、工具链升级）之后刷新一次那一格即可。

这是「周期性任务配了基线相对判据」时必须同时确认的第三件事：
**基线会不会自己更新**。第三件事的完整形态其实是两问：
① 会不会**自动**更新（没有 `schedule` 就不会）？
② 能不能**手动**更新（能 —— 否则这条判据在外部输入漂移后就永久失效了）。
