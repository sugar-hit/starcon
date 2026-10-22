# 闸工具把「仍在跑的 run」当基线，导致假阻塞

- 创建: 2026-10-23
- Owner: TBD（工具链 / 流水线侧）
- 优先级: **P1**
- 关联: `tools/module-progress/ci-gate.mjs` ·
  `docs/sessions/2026-10/2026-10-23-0700-main-sync-round47.md` ·
  `docs/backlog/2026-10-23-merge-resolve-revparse-stdout-echo-mislabels-add-add.md`

## 背景

2026-10-23 第 47 轮同步任务里，闸工具对同步 PR 给出了「结论：阻塞 —— 出现了 main 基线里没有的失败，
不合并」，并列出 4 个「新增失败作业」：`Catalog Browser & Contract Tests`、
`Java 21 & PostgreSQL Integration`、`Lint`、`Security Scan`。

**这个结论是假的。** 逐条核对后，PR 的失败作业集合与 main 基线的失败作业集合**完全相同**，
零新增失败。

根因是基线 run 的选取没有检查作业是否收完：

- 闸工具取 main 分支**最近一次** run 作为基线；
- 但 GitHub 会先把 run 标记成 `completed`，**再**让里面最后一个作业收尾，
  于是存在「run 级已 completed、内部某个作业仍在 in_progress」的中间态；
- 本轮被选中的基线 run `#39868943263` 就是这个状态：run 级 `status=in_progress`，
  而它下面的 `Typecheck` / `Lint` / `Security Scan` / `Catalog Browser & Contract Tests` /
  `Unit & Integration Tests` / `Java 21 & PostgreSQL Integration` **6 个作业全在跑**；
- 工具把「基线里没有失败作业」读了出来，于是 PR 的 4 个失败作业全被判成「新增」。

### 为什么这条会造成假阻塞而不是假放行

假放行是安全的（多合一次，下一轮还能纠），**假阻塞是不安全的**：
它让任务在明明可以合并的时候不动，于是集成分支与主分支持续分叉，
下一轮又要面对同一个冲突面 —— 本仓库已经因此反复出现「同一个 39 文件冲突面连打好几轮」。

## 判据（正确用法）

**基线 run 的「跑完」必须按作业判定，不能只看 run 级的 status/conclusion。**

1. 取候选 run 时，逐个作业检查 `status === 'completed'`；
2. 只要有任何一个作业没收完，**跳过这个 run**，往回找最近一个所有作业都收完的 main run；
3. 比对对象优先用 **main 当前 tip 对应的 run**，而不是「最近一个全收完的 run」——
   两者内容可能不同，拿老基线比会把 main 自己新加的失败算成本次 PR 的新增失败。

本轮的正确基线是 main 当前 tip 对应的 run：失败作业集合 =
`{Lint, Security Scan, Catalog Browser & Contract Tests, Java 21 & PostgreSQL Integration}`，
与 PR 侧完全一致 ⇒ 零新增失败。

## 后果

- 任务在可以合并的时候空转一轮，主分支与集成分支的差距被拉大；
- 报告里若直接抄工具的「阻塞」结论，会把一次**零增量**的同步误报成「新增 4 项失败」。

## 建议修法

- `tools/module-progress/ci-gate.mjs` 的 `baselineRun()` 增加作业级收完判定，
  跳过内部作业未收完的 run；
- 取基线时优先匹配 main 当前 tip 的 commit；
- 工具输出里把「基线用了哪个 run、它是否全部收完」打印出来，
  让人能一眼看出基线是否可用。

## 关联

- `tools/module-progress/ci-gate.mjs`
- `docs/sessions/2026-10/2026-10-23-0700-main-sync-round47.md`
