# `origin/main` 的 tip 是集成头的 squash —— main → 集成头同步结构性冲突（第 79–100 轮连续未并入）

- 创建: 2026-10-21
- Owner: TBD（**发布侧**负责，见「待办内容」第 1 条）
- 优先级: **P0** —— 这是「main → 集成头同步」任务的**长期阻塞点**，每一轮 main 前进都会撞同一堵墙
- 关联: `tools/module-progress/SYNC-MAIN.md` · `docs/sessions/2026-10/2026-10-21-0115-main-sync-conflict-squash-divergence.md` · `docs/sessions/2026-10/2026-10-21-0315-main-sync-conflict-squash-divergence.md` · `docs/sessions/2026-10/2026-10-21-0415-main-sync-conflict-squash-divergence.md` · `docs/sessions/2026-10/2026-10-21-0515-main-sync-conflict-squash-divergence.md` · `docs/sessions/2026-10/2026-10-21-0643-main-sync-conflict-squash-divergence.md` · `docs/sessions/2026-10/2026-10-21-0740-main-sync-conflict-squash-divergence.md` · `docs/sessions/2026-10/2026-10-21-1127-main-sync-conflict-main-advanced.md` · `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md`

---

## 背景

「main → 集成头同步」任务每轮做的是：`fetch` → `merge-base --is-ancestor origin/main integration/module-dev`
→ 有新提交就 `git merge --no-ff origin/main` → 预检 → push 集成头。

**连续 8 轮（2026-10-21 01:15、03:15、04:15、05:15、06:43、07:40、11:27、11:45）都在同一个地方停住**：`origin/main` 的 tip `d28188d6`
（`Integration/module dev (#11)`）是 GitHub **squash 合并**的产物 —— **单父提交**，
父提交是 `1bb270a0`（batch-28 tip）。

```
commit d28188d63337a2eda641c1ee1e93db77039786b6
Author: Tommy <...>
Commit: GitHub <noreply@github.com>
    Integration/module dev (#11)
parents: 1bb270a0        # ← 只有一个父提交 ⇒ squash 合并
```

⇒ **main 的 tip 不是「main 侧的新交付」，而是「集成头分支在 2026-10-20 20:39 时刻的旧快照」**，
被 GitHub 压成了一颗与集成头无共同祖先的新提交。

### 三个可核验的事实

**① merge-base 被拉回 85 个 commit 之前**

```
$ git merge-base integration/module-dev origin/main
1bb270a0                                     # batch-28 tip
```

batch-28 之后集成头交付的模块，在 merge-base 上**根本不存在** ⇒ 两边都是「各自新增」
⇒ 触发 **add/add** 冲突。

**② main 侧零新交付，且落后集成头 20 个模块**

按 L4 模块 ID 逐行比对台账（465 行，模块集合完全相同）：

| 项     | main 快照 | 集成头 `97c257dc`（2026-10-21 06:43 轮） |
| ------ | --------- | ---------------------------------------- |
| 已交付 | **242**   | **262**                                  |
| 进行中 | 0         | 0                                        |
| 未开发 | 223       | 203                                      |
| 完成率 | 52.0%     | 56.3%                                    |

状态不同的模块（05:15 轮为 16 个：5.2.1.2 到岸成本 / 5.2.1.3 成本分摊 / 5.2.2.1 汇率查询 /
5.2.2.2 汇率快照 / 5.2.3.1 平台费用 / 5.2.3.2 物流与头程费用 / 5.3.1.1 利润测算器 /
5.3.1.2 批量测算 / 5.3.2.1 定价模板 / 5.5.2.1 异常告警 / 6.1.2.1 档口与货源池 /
6.2.1.1 缺货与断货风险 / 6.3.2.2 到货与逾期 / 10.7.1.3 弃置与销毁 /
15.2.2.1 Request a Review / 15.2.2.2 索评规则），
**方向全部一致：main 未开发、集成头已交付**；06:43 轮再增 batch-45 的 4 个
（5.3.2.2 价格规则 / 5.4.1.2 批量调价 / 6.2.1.2 补货建议 / 6.1.2.3 供应商资质）⇒ **共 20 个**。

⇒ **main 没有任何一个模块是集成头没有的。** 本任务想同步的内容，集成头已经有了（且更新）。

⚠️ **这个数字会随集成头继续前进而变大**（03:15 轮是 10 个，04:15 / 05:15 轮是 16 个，
06:43 轮是 20 个 —— 差值正是 batch-43 六车道 + batch-45 四车道；batch-44 是零派发只读轮，无新增）。
**main 侧不前进 ⇒ 差距只增不减。** 这本身就是「squash 快照已经过时」的最好证据。

**③ 冲突面上，main 那一侧是过期快照**

6 个冲突文件里 main 的「独有内容」逐条看过：

| 文件                                    | main 独有内容                                              | 集成头现状                                                                           |
| --------------------------------------- | ---------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| `cost-composition-model.ts`             | `delivered: false` + 「🔴 5.2.1.2/5.2.1.3 **未交付**」文案 | 两个模块**都已交付**，`delivered: true`                                              |
| `cost-composition-model.test.ts`        | 断言 5.2.1.2/5.2.1.3 `delivered === false`                 | 改为**随交付状态翻转**的断言                                                         |
| `cost-composition-page.test.ts`         | 同上的「未交付」断言                                       | 改为「已交付 ⇒ 摘要不得写未交付」的通用断言                                          |
| `ListingPublishJobIntegrationTest.java` | `isAfter(now().minusSeconds(2))`（JVM 墙钟基准）           | 改用 **DB 时钟同源**，并**专门写了一条 `assertThatThrownBy` 把这条旧写法钉死成必红** |
| `cost-composition-workspace.tsx`        | **0 行**（严格超集）                                       | —                                                                                    |
| `api-proxy.ts`                          | **0 行**（`services[]`：main 100 条 vs 集成头 110 条）     | 严格超集                                                                             |

⇒ main 侧的独有内容是**已被后续交付推翻的旧断言**，不是新代码。

### ④ 2026-10-21 11:27 轮：main 首次真实前进，差距 24 → 7，冲突面 6 → 2

**这是 7 轮以来第一次出现实质变化。** main 上多了一颗**真实提交**（非 squash）：

```
ec225641  feat: 到岸成本、成本分摊等 24 项能力交付
parents:   1 个父提交 = d28188d6（**常规提交，不是 merge**）
```

> ⚠️ 2026-10-21 11:33 更正：本条原先写成「2 个父提交（真实 merge）」，**实测是 1 个父提交**
> （`git log -1 --format='%P' origin/main` ⇒ 只有 `d28188d6`；`git cat-file -p` 里 `parent` 行数 = 1）。
> 这个区别是**承重的**：正因为它是**单父的常规提交**（而不是双父 merge），
> merge-base 才仍然被老 squash 钉在 `1bb270a0` ⇒ merge 仍然失败。
> 若它真是双父 merge，merge-base 早就前移了。

它把此前「main 未开发、集成头已交付」的那 24 个模块（5.2.1.2 / 5.2.1.3 / 5.2.2.1 / 5.2.2.2 / 5.2.3.1 / 5.2.3.2 /
5.3.1.1 / 5.3.1.2 / 5.3.2.1 / 5.3.2.2 / 5.4.1.1 / 5.4.1.2 / 5.5.1.1 / 5.5.2.1 / 6.1.2.1 / 6.1.2.3 / 6.2.1.1 /
6.2.1.2 / 6.2.1.3 / 6.3.2.2 / 10.4.2.2 / 10.7.1.3 / 15.2.2.1 / 15.2.2.2）以**真实血缘**推上了 main。
⇒ **「待办内容」第 1 条的根治路径被验证可行**，且已部分落地。

| 项     | main 快照 | 集成头 `84801998` |
| ------ | --------- | ----------------- |
| 已交付 | 266       | **273**           |
| 进行中 | 0         | 0                 |
| 未开发 | 199       | **192**           |
| 完成率 | 57.2%     | **58.7%**         |

- 逐模块 ID + 状态 join（两边各 465 行）：状态不同只剩 **7** 个，**全部是集成头领先**
  （5.4.2.1 平台价格同步 / 6.2.2.1 采购申请 / 6.4.2.1 质检单 / 6.5.1.1 采购对账 /
  6.5.2.1 采购分析 / 7.7.2.2 平台托管与全托管备货 / 16.2.2.2 认证与资质）；
  **main 领先（main 已交付、集成头未交付）= 0**。
- `origin/main^{tree}` = `f4d478c8`，在集成头**全部历史提交**里**无同哈希命中**
  ⇒ 与前 5 轮「main 的树逐字节等于集成头某个祖先 ⇒ merge 内容空转」**结论相反**：
  **main 侧确实带了新内容**。
- 但 merge-base 仍是 `1bb270a0`（老 squash `d28188d6` 还在 main 历史里，血缘仍被抹掉）⇒ **merge 仍失败**。
- **冲突面从 6 个文件缩到 2 个，且换了文件**（各 7 个 hunk）：

  | 冲突文件 | 类型 | base / ours / theirs 行数 | main 侧独有内容 |
  | --- | --- | --- | --- |
  | `apps/web/src/lib/api-proxy.ts` | content | 2634 / 5359 / 4967 | **0 行** ⇒ ours 严格超集 |
  | `backend/services/catalog/src/test/java/com/starcon/catalog/PriceHistoryIntegrationTest.java` | add/add | 0 / 812 / 769 | **27 行**，**修复前的 JVM 墙钟写法**（见下方 ⚠️） |

  ⇒ 仍然**没有一处是 main 侧的新交付**；结论与前 6 轮一致，只是文件名单变了。

> ⚠️ 2026-10-21 11:33 更正：本条原先把 `PriceHistoryIntegrationTest.java` 的 main 侧独有内容
> 写成「**14 行**，同一 fixture 的旧写法（`base.plusDays(N)` vs 集成头 `a1At/a2At`），**语义等价**」。
> **「语义等价」这个判断是错的**，实测（`git show <side>:…PriceHistoryIntegrationTest.java`）：
>
> | | main 侧 | 集成头侧 |
> | --- | --- | --- |
> | 窗口锚点 | `LocalDate.now()`（**JVM 默认时区**）+ `OffsetDateTime.now().minusDays(10)` | `LocalDate.now(ZoneOffset.UTC)`（**显式 UTC**） |
> | 种子与查询窗口的关系 | 种子 `base` 与查询用的 `LocalDate.now().minusDays(9)` **各自取墙上时钟** | 种子 `a1At/a2At` 与查询窗口**同源于 `windowDay`** ⇒ 不可能跨日界 |
> | 可执行不变量 | 无 | 有（`assertThat(a2At.toInstant()).isAfterOrEqualTo(windowFromBound).isBefore(windowToBound)`） |
>
> ⇒ 集成头侧是车道 `fix/5-5-1-1-price-history-window` **刻意做的跨时钟域修复**
> （把窗口锚到显式 UTC 日、并写了一条把旧写法**钉死成必红**的不变量），
> main 侧是**修复前**的墙钟写法。
> ⇒ **「以集成头为准」不只是「不丢东西」，而是「取 main 那侧会把这个跨时钟域修复回退掉」** ——
> 这正是跨时钟域第 1 例（见 `docs/sessions/2026-10/2026-10-21-price-history-clock-window-fix.md`）。
>
> 另更正行数：main 侧独有 **27 行**（`diff theirs ours` 统计），非 14 行。
> 结论方向不变（**以集成头为准**），但**理由比原先写的更强**。

### 影响

- 「main → 集成头同步」任务**连续 8 轮产出「冲突未并入」**。
  各轮等闸时长：45 / 40 / 27 / **0** / **26** / **25 分 49 秒** / **3 小时 23 分** ⇒ **闸不是瓶颈，唯一瓶颈是 main 的 tip 是 squash**。
  05:15 轮闸是空的、第 1 次尝试就拿到；06:43 轮闸被**上一轮流水线的后端 L2（`mvnw verify`）**持有，
  按协议用密集竞态 `gate acquire`（1 秒一次）等到第 1568 次命中（26 分 08 秒）；
  **07:40 轮同样是后端 L2 持闸**（`mvnw verify` 从 `warehouse` 推进到 `trade`），
  第 **1421** 次尝试命中（**25 分 49 秒**，owner pid 26606）。
  **11:27 轮等闸 3 小时 23 分**（08:03:59 → 11:27:37），是前 6 轮的 8 倍 —— 见下面「闸泄漏」一条。
- 2026-10-21 04:15 轮补一条**更硬的证据**：`origin/main` 的树（`06607694b31c6fdfe354ed4783c22d4a0e0b8c20`）
  与集成头自己的祖先提交 `d22bc1e1`（batch-37）**逐字节相同** ⇒ 这次 merge 在内容上是**空转**，
  冲突纯粹是 squash 抹掉血缘导致 merge-base 被拉回 `1bb270a0` 的副作用。
  同一轮还补齐了台账口径：main 快照 **242 已交付 / 0 进行中 / 223 未开发**（52.0%），
  集成头 `39edfe12` **258 / 0 / 207**（55.5%）⇒ main **落后 16 个模块**，
  且**没有任何一个模块是 main 领先**（模块集合两边完全相同，各 465 个）。
- 2026-10-21 05:15 轮**独立重验**了上面那条「逐字节相同」（不采信历史会话日志，直接跑
  `git rev-parse origin/main^{tree}` vs `git rev-parse d22bc1e1^{tree}` + `merge-base --is-ancestor`），
  结论不变 ⇒ **main 不可能领先于集成头的任何一行代码**。
  同轮还把冲突类型**拆到底**（用 `git cat-file -e` 核对 6 个文件在 merge-base 上是否存在）：
  **5 个 add/add + 1 个 content**，而不是笼统的「6 文件冲突」。
  另补一条「main 侧独有文本」的完整分布口径：共 **15 个文件**，
  其中 **8 个**由 `merge-union` 自动求并、**3 个**由 git 三方合并干净合上
  （`apps/web/src/app/api/crm/[...path]/route.ts`、`FulfillmentRequestService.java`、
  `FulfillmentRequestIntegrationTest.java`）、**4 个**构成冲突、**2 个**是集成头严格超集。
  ⇒ **main 侧的独有文本全部落在已重构/已收口的旧写法上，没有一处是 main 侧的新模块交付。**
- 2026-10-21 06:43 轮**第 3 次独立重验**「origin/main 的树 == 集成头祖先 `d22bc1e1` 的树」
  （不采信历史会话日志）：这次改用**在集成头全部 207 个历史提交里逐一比对树哈希**的方式，
  唯一命中者就是 `d22bc1e1`（batch-37）⇒ 结论仍然不变。
  同轮把冲突类型用 **base/ours/theirs 三段行数**拆到底，**6 个文件、5 个 add/add + 1 个 content**，
  与前四轮完全一致；「main 侧独有文本」的 15 个文件分布（8 求并 / 3 干净合上 / 4 冲突 / 2 严格超集）
  也与前轮一致 ⇒ **这个冲突面已经稳定，不随集成头前进而变化**。
  另记录一条实操口径：`ageSeconds` 中途掉到 23 又升回，**不是换人而是同一 tick 的 keepalive 续期**
  （`dispatch.gate/` 里多出 `keepalive-*` 文件、`owner` pid 不变）⇒ 判活只能看 `stale` 字段。
- 2026-10-21 07:40 轮**第 4 次独立重验**「origin/main 的树 == 集成头祖先 `d22bc1e1` 的树」
  （不采信历史会话日志）：在集成头**全部历史提交**里逐一比对树哈希，唯一命中者仍是 `d22bc1e1`；
  同一轮独立核验 squash 结构（`git log -1 --format='parents=[%P]' d28188d6` ⇒ **只有一个父提交**）
  与 merge-base 回退（`1bb270a0`）。
  冲突面**第 6 次复现且完全一致**：**6 个文件、5 个 add/add + 1 个 content**；
  「main 侧独有文本」的 15 个文件分布（8 求并 / 3 干净合上 / 4 冲突 / 2 严格超集）也与前轮一致
  ⇒ **这个冲突面已经稳定，不随集成头前进而变化**。
  同轮把台账口径从「读总览表」升级为**逐模块 ID + 状态 join**（465 行 vs 465 行）：
  状态不同 **24** 个（比 06:43 轮的 20 个多出的 4 个正是 batch-46 的
  5.4.1.1 调价单 / 5.5.1.1 价格历史 / 6.2.1.3 采购计划 / 10.4.2.2 备货预报），
  且 **main 领先（main 已交付、集成头未交付）的模块数 = 0**
  ⇒ **main 在 465 个模块里没有任何一个是集成头没有的**，差距只增不减。
- 那个 406 files / 262390 insertions 的 diffstat 是 **squash 把 85 个 commit 压成一颗造成的行数重放**，
  不是真有 26 万行新代码 —— **不要按这个数字判断工作量**。
- `merge-union` 驱动能自动合上的部分（契约 `packages/api-contracts/**` + 台账 `module-progress.md`）
  **不是问题源**；问题源是 6 个业务/测试文件上的 add/add。

---

## 待办内容

- [ ] **（首选，改发布侧）发布侧把集成头的真实 commit（不 squash）推到 main**，
      使 `origin/main` 的 tip 重新成为集成头的祖先 ⇒ 本任务回到「无新提交」的零改动常态，
      冲突从根上消失。**这是唯一的根治办法** —— 在集成头这边做任何解冲突都是治标。
      （2026-10-21 11:27 轮：**已部分落地** —— `ec225641` 用真实提交把 24 个模块推上了 main，
      台账差距 24 → 7、冲突面 6 → 2。**剩余 7 个落后模块也请一并用真实提交推上去**：
      5.4.2.1 平台价格同步 / 6.2.2.1 采购申请 / 6.4.2.1 质检单 / 6.5.1.1 采购对账 /
      6.5.2.1 采购分析 / 7.7.2.2 平台托管与全托管备货 / 16.2.2.2 认证与资质。）
- [ ] **（次选）人工决定 main 的基线**：把 main 重置到集成头，或接受 squash 作为新基线
      （后者需要配套一条「main 是集成头快照」的读取约定，否则台账会持续倒退）。
- [ ] **（若坚持并入）开一条 fix 车道解这 6 个冲突**，方向「以集成头为准 + 逐行核对 main 那
      2–18 行独有文本是否需要保留」。**不要用 `git checkout --ours/--theirs` 批量覆盖**：
      第 1/2/3/6 个文件 main 侧确有独有文本。解完**必须重跑 L2**（这批内容从未经过 L1/L2）。
      （2026-10-21 05:15 轮补充：这 6 个文件里 **5 个是 add/add** —— 它们在 merge-base `1bb270a0` 上
      **根本不存在**，是集成头在 batch-28 之后新增的；第 5 个 `api-proxy.ts` 是 content 冲突。
      另有 **15 个文件**含 main 独有文本，其中 **8 个**由 `merge-union` 求并、**3 个**由 git 三方合并
      干净合上、**4 个**即上述冲突文件、**2 个**是集成头严格超集。）
      （2026-10-21 11:27 轮更新：**冲突面已换成 2 个文件**，`api-proxy.ts`（content，main 侧独有 **0 行**，
      ours 严格超集）+ `PriceHistoryIntegrationTest.java`（add/add，main 侧独有 **27 行**，
      是**修复前的 JVM 墙钟写法**；集成头侧是 `fix/5-5-1-1-price-history-window` 车道
      刻意做的**显式 UTC + 同锚点 + 可执行不变量**的跨时钟域修复 ⇒ **不是语义等价**）。
      ⇒ 方向已清楚：**两个都可按 ours 收**（`api-proxy.ts` 是严格超集；`PriceHistory` 取 theirs
      等于**回退跨时钟域修复**），但仍需人工逐行确认，且解完**必须重跑 L2**。）
      （2026-10-29 05:15 第 98 轮更新：冲突面已变成 **27 个**；theirs 侧独有合计 **79 行**、
      **逐行分类完成 ⇒ 0 行是 main 侧的新交付**（详见上方「2026-10-29 05:15 轮」小节）。
      **新增的 1 个** `SaasUsageIntegrationTest.java` 是**高危型** ——
      取 theirs 会把 `c904ec63` 修掉的 flaky 子串断言装回去。）
- [ ] **（顺带）给同步任务补「回合被截断也要 `gate release`」的收尾保障**：
      2026-10-21 02:15 那个同步任务回合在 02:47:10 被截断，**既没写会话日志也没 release 闸**
      ⇒ 闸泄漏 70 分钟，白挡了 03:00 轮与 03:15 轮两次同步。
      建议协议第 6 步加兜底，或由调度器在回合异常结束时统一 release。
- [ ] **（07:40 轮新增）禁止并发开多个 `gate acquire` 竞态循环 —— 否则 release 瞬间闸被抢回来**：
      2026-10-21 07:40 轮等闸时，第一个密集竞态循环在第 1421 次尝试命中并拿到闸，
      但**在它命中之前又启动了第二个竞态循环**（因长时间无输出、误以为首个循环已被工具超时杀掉）。
      两个循环并发跑同一个 `gate acquire` ⇒ 只要本轮 `gate release`，
      **第二个循环会在同一秒把闸抢回来并持有到 TTL（4200s）**，而它的持闸进程随即退出
      ⇒ 等价于**闸泄漏 70 分钟**，白挡下一轮流水线。
      本轮在 release 前用 `task_stop` 停掉第二个循环才避免（acquire 1 次 / release 1 次，配平）。
      ⇒ 建议：竞态循环**只允许存在一个**；重跑竞态前先确认旧的已退出；
      收尾 `gate release` **之前**必须确认没有存活的竞态循环。
      （与上一条的「回合被截断不 release」是**两种不同的闸泄漏向量**，兜底要一起做。）
- [ ] **（11:27 轮新增，P0）`keepalive.sh` 会给死掉的持闸者无限续命 ⇒ TTL 接管路径形同虚设**：
      本轮闸被 08:00 那轮流水线 tick 从 **08:03:59 一直持到 11:27:37（3 小时 23 分）**，
      远超实测 45–60 min 的单轮时长。续期来自 `.tmp/keepalive.sh` —— 一个 **nohup 脱离父进程**、
      每 10 分钟新建 `keepalive-<HHMMSS>` 文件的常驻脚本（`seq 1 120` ⇒ 最多 20 小时）。
      ⇒ 闸目录 mtime 被持续顶高，**`stale` 永远为 false** ⇒ `gate acquire` 的
      「age > TTL 即接管」分支**永远走不到** ⇒ 闸只能被显式 release 释放。
      现象特征：持闸 tick 的**父会话被截断**后，keepalive 仍在跑，闸看起来「一直被持有」。
      ⇒ 建议：`keepalive.sh` **跟着持闸者的生命周期走**（按持闸者 PID 存活与否决定续期），
      或给续期加「本轮 tick 活性令牌」校验；否则「回合截断 ⇒ 闸泄漏 70 分钟」这条兜底
      会被 keepalive 放大成「泄漏 20 小时」。
- [ ] **（11:27 轮新增）调度侧保证同一时刻只有一个 main 同步竞态循环**：
      本轮实测同时存在 **≥3 个**其他同步轮次留下的 acquire 竞态/观测循环
      （`.tmp/sync-gate-race.sh`、`.tmp/sync-race-final.sh`、`.tmp/sync-race-solo.sh` 等，
      父进程都是 MCode helper）。本轮用「**先等别人的竞态循环退出，再单线程竞态**」规避，
      但根治应在调度器层保证唯一性 —— 否则就是上面那条「release 后被秒抢 ⇒ 闸泄漏」。

- [ ] **（11:45 轮新增）同步任务自身提交文档会制造下一个冲突文件**：
      本轮第 3 个冲突文件 `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`（add/add）
      **正是 11:27 轮把这份 backlog 文档提交进集成头造成的** ——
      main 侧也有一份 `d28188d6` 带来的 6 轮旧稿，两侧各自新增 ⇒ add/add。
      ⇒ **每轮同步把「main 上已有同名文件」的文档提交进集成头，就等于给下一轮多加一个冲突面。**
      ⇒ 建议：同步任务提交文档前先 `git cat-file -e origin/main:<path>` 查同名文件；
      main 侧已存在的文档（如本文件）**只在集成头改、不往 main 推**，或先与 main 侧对齐再提交。
- [ ] **（11:45 轮新增）`ec225641` 也是 squash —— 11:27 轮的「真实提交」措辞不成立**：
      独立核验 `git log -1 --format='parents=[%P]' ec225641` ⇒ **`parents=[d28188d6]`，单父提交**
      ⇒ 它与 `d28188d6` **同为 squash**，**不是**「真实 merge」。
      （11:27 轮写「2 个父提交（真实 merge）」、11:27 轮自己的更正 `053ee1f0` 写「真实提交，1 个父提交」，
      **两处措辞都自相矛盾**；本轮据实更正。）
      ⇒ 这解释了为什么 merge-base 仍停在 `1bb270a0`：main 上**两颗**提交都是 squash，
      **合并路径依然只能靠发布侧改用真提交**才能根治。
- [ ] **（2026-10-29 05:15 第 98 轮新增，P0）冲突面会随集成头前进而增长 —— 撞墙成本加速上升**：
      本轮冲突面 **26 → 27**，新增的那 1 个**不是 main 侧的新交付**，
      而是集成头自己的修复车道 `c904ec63`（改 `SaasUsageIntegrationTest.java`）撞上了 main 的旧快照
      ⇒ **集成头每落一条「改动已存在文件」的修复车道，就新增 1 个 add/add 冲突点**。
      ⇒ 建议：**发布侧修复到位前，同步任务改为按「模块 ID + 状态」做增量同步**
      （只同步 main 侧真正领先、且集成头未交付的模块），而不是整树 merge。
- [ ] **（2026-10-29 05:15 第 98 轮新增，P1）授权「逐文件取 ours + 人工核对 79 行」的机械解法**：
      本轮已把 theirs 侧 79 行**逐行分类**（35 + 11 墙钟基准、7 `parsed_at` 断言、
      3 旧 flaky 子串断言、2 + 3 提示串、2、其余 21 个文件各 1 行 import/常量写法）
      ⇒ **0 行是 main 侧的新交付**；且 **4 个文件 theirs 独有 0 行**（取 ours 无损）。
      ⇒ 机械解法已具备安全前提，但 `SYNC-MAIN.md` 现行硬规定是「**不自行解冲突**」
      ⇒ 需**协议层面显式授权**后才可执行，或由发布侧直接改 main 的 tip（首选）。
      ⚠️ 高危项必须点名：`SaasUsageIntegrationTest.java` 取 theirs
      会把 `c904ec63` 修掉的 flaky 断言装回去；`AnomalyOrder*` 取 theirs 会回退跨时钟域修复。

### 2026-10-29 05:15 轮（第 98 轮）：冲突面**会随集成头前进而增长**，且 theirs 侧**零行新交付**

> 本节回应流水线第 127 轮（2026-10-29 05:00）的明确请求：
> *「本流水线不碰它（那是同步任务的职责），但建议由同步任务侧排查那 26 个冲突的根因。」*

**① main tip 仍未动**：`origin/main` tip 仍是 `823ebca5 Integration/module dev (#66)`（**单父**，父 = `6522c497`），
与第 79–97 轮**同一颗 squash**；merge-base 仍停在 `6522c497`（2026-10-23 09:18）。
`git rev-list --count integration/module-dev..origin/main` = **1**。

**② 冲突面从 26 → 27，且新增的那 1 个不是 main 侧的新交付**（程序化集合比对，非目测）：

```
round97 = 26 个   round98 = 27 个
NEW  : backend/services/identity/src/test/java/com/starcon/identity/SaasUsageIntegrationTest.java
GONE : （无）
```

新增来源 = **集成头自己的修复车道** `c904ec63`（identity 用量统计 flaky 断言修复，经 `3e83aa41` 并入）
⇒ **集成头每落一条「改动已存在文件」的修复车道，就新增 1 个 add/add 冲突点**。

> ⚠️ **这条新增冲突是高危型**：`theirs` 侧独有 3 行正是
> `assertThat(forA.toString()).contains("111").doesNotContain("222");` 一族
> —— 即 `c904ec63` **修掉的随机 UUID 子串匹配写法**（原写法因 UUID 随机而 flaky）。
> ⇒ **取 theirs = 把刚修掉的 flaky 断言装回去** ⇒ 这一处必须取 ours。

**③ theirs 侧独有内容逐行分类（27 个文件全覆盖）**

口径：`git diff --numstat HEAD origin/main -- <file>`。

| 口径 | 结果 |
| --- | --- |
| 文件数 | 27（26 add/add + 1 content） |
| **theirs 是 ours 的严格超集（theirs 独有 = 0 ⇒ 取 ours 无损）** | **4**：`api-proxy.ts` / `LinkageRiskIdentityStub.java` / `ReportingFinancePort.java` / `ReportingUpstreamClient.java` |
| theirs 独有行合计 | **79 行** |
| ours 独有行合计 | **1643 行** |

theirs 独有 79 行的构成（**逐行看过**）：

| 文件 | 行数 | 性质 |
| --- | --- | --- |
| `AnomalyOrderIntegrationTest.java` | **35** | **JVM 墙钟基准** `static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)`；ours 侧已改 DB 时钟同源 ⇒ **取 theirs 回退跨时钟域修复** |
| `AnomalyOrderTenancyIntegrationTest.java` | 11 | 同族（墙钟基准） |
| `SalesTaxIntegrationTest.java` | 7 | `parsed_at` 必须是真时刻的断言 |
| `NotificationInboxController.java` | 3 | 「1.3.2.1 预警规则未交付」提示串 |
| `SaasUsageIntegrationTest.java` | 3 | **旧 flaky 子串断言**（见 ②） |
| `NotificationPreferenceService.java` | 2 | 「1.3.2.2 外部渠道推送未交付」提示串 |
| `FundAnalysisIntegrationTest.java` | 2 | — |
| 其余 21 个文件 | 各 **1** | **几乎全是 import 行 / 常量声明行**写法差异（例：`static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");`、`import { Boxes, ClipboardList, HeartPulse, LayoutGrid, Search } from 'lucide-react';`） |

⇒ **79 行里 0 行是 main 侧的新模块交付**，全部落在「已被集成头后续交付推翻或改写的旧写法」上。

**④ 由 ③ 推出的两条可执行结论**：

1. **方向仍是「以集成头为准」**（与第 93–97 轮一致，本轮证据更硬）；
   其中 4 个文件 theirs 独有 **0 行** ⇒ 取 ours **无损**；
   其余 23 个文件 theirs 独有共 79 行，其中 **21 个文件各只有 1 行**（import/常量写法），
   ⇒ **「逐文件取 ours + 人工核对那 79 行」是安全解法**，但**本任务协议禁止自行解冲突**
   ⇒ 需协议层面授权，或由发布侧直接改 main 的 tip（首选）。
2. **撞墙成本会随流水线推进而加速上升，不是恒定 26/27**
   ⇒ 建议在发布侧修复到位前，**同步任务改为按「模块 ID + 状态」做增量同步**
   （只同步 main 侧真正领先、且集成头未交付的模块），而不是整树 merge。

**⑤ 协议级待补**：`SYNC-MAIN.md` 可补一条判据 ——
**「add/add ≠ 真分叉」**：先查 main 侧 blob 是否在集成头历史中原样存在，
命中即线性演进、可机械取集成头侧（第 96 轮已提，本轮再次实证成立）。

**⑥ 另两条与本条相关的旧待办，本轮再次实证**：

- **「同步任务提交文档会制造下一个冲突文件」**：本轮提交的本 backlog 文件，
  `git cat-file -e origin/main:<path>` ⇒ **main 侧无同名文件**（main 的 squash 快照停在 2026-10-23，
  早于本文件创建）⇒ **本轮不会新增 add/add 冲突点**。
  （但「main 上已有同名文件」的文档仍适用旧规则：**只在集成头改、不往 main 推**。）
- **「`gate release` 只删自己持有的闸」**：本轮闸是空的、一次命中、只 release 一次 ⇒ 未触发闸泄漏。

### 2026-10-29 07:20 轮（第 99 轮）：**main 侧零新内容的充要证明**（三条独立证据）

> 本轮把「main 侧没有新交付」从「逐行核对冲突文件」升级为**可执行的充要判据**。
> 第 93–98 轮的口径只能证明「**冲突文件里**没有 main 的新东西」，
> **不能排除**「main 在非冲突文件里带了新内容」—— 本轮补上了这个缺口。

**① `origin/main` 的树 = 我们自己祖先的树（逐字节相同）**

```
git rev-parse origin/main^{tree}   ⇒ 83269e111f5f42e8b9481fd0e9549687aa8a7ed0
git rev-parse 1e3228b4^{tree}      ⇒ 83269e111f5f42e8b9481fd0e9549687aa8a7ed0   ← 相同
git merge-base --is-ancestor 1e3228b4 integration/module-dev ⇒ YES
```

⇒ main 的 tip = **集成头分支在 2026-10-25 23:16 时刻的旧快照**。

**② main 树里每个 blob 都已在集成头历史中**

```
git ls-tree -r origin/main | awk '{print $3}' | sort -u   ⇒ 5478 个唯一 blob
comm -23 main_blobs our_blobs                             ⇒ 0
```

**③ `-X ours` 合并的结果树 == 当前 HEAD 的树（结果完全一致）**

在**隔离 scratch clone**（`git clone --shared --no-checkout` 到 `/tmp`，**不碰集成头 worktree**）里：

```
git merge --no-ff --no-commit -X ours 823ebca5  ⇒ 0 个未解决冲突
git write-tree                 ⇒ 79a5711bce1e7e88117b4b60895c8210d0ca74e2
git rev-parse a475f1df^{tree}  ⇒ 79a5711bce1e7e88117b4b60895c8210d0ca74e2   ← 相同
```

⇒ **「27 个冲突全部取 ours」的结果与「什么都不做」的 HEAD 逐字节相同**
⇒ 「取 ours」从「大概安全」升级为**可以证明的无损操作**。

**④ 27 个冲突文件的 `theirs` blob 全部 = 我们自己的旧快照 `1e3228b4`**（27 相同 / 0 不同）。

> ⚠️ 本轮**没有**取 ours、**没有**解任何冲突 —— 协议第 3 步明令 `git merge --abort`，本轮照办。
> 证据 ③ 的作用是**为协议层授权提供依据**。

**冲突面：27 个，与第 98 轮逐字节相同**（`git merge-tree --write-tree --name-only` 程序化比对：NEW 空 / GONE 空）。

> ⚠️ **修正第 98 轮的一个表述**：第 98 轮写「冲突面会随集成头前进而增长」。
> **准确判据是「集成头是否改了已存在的业务/测试文件」，不是「集成头是否前进」。**
> 本轮集成头前进了 **3 个纯 docs 文件**（`f05f7c13 → a475f1df`）⇒ 落在已存在文件上的改动一个都没有
> ⇒ **本轮冲突面不增（27 → 27）**。
> （真正的触发器是第 97 轮之后那条 `c904ec63` 改 `SaasUsageIntegrationTest.java`。）

**⇒ 待办第 2 条的解法升级**：既然「取 ours 无损」已被证明到等价性级别，
「27 个冲突全部取 ours + 提交 merge commit」这条机械解法**不再依赖逐行人工核对 79 行**
⇒ **但仍需协议层授权**（改 `SYNC-MAIN.md` 加一条「main 侧 blob 已被集成头历史覆盖 ⇒ 允许 `-X ours` 机械合并」的判据），
或由发布侧直接改 main 的 tip（首选）。

### 2026-10-29 12:15 轮（第 100 轮）：**充要证明独立复现** ＋ **等闸 544 秒**

> 本轮**不引用**第 99 轮的结论，而是**重跑取证**，得到同一结果 ⇒ 判据可复现、可依赖。

**① `origin/main^{tree}` == 集成头祖先 `1e3228b4^{tree}`（逐字节相同）**

```
git rev-parse origin/main^{tree}  ⇒ 83269e111f5f42e8b9481fd0e9549687aa8a7ed0
git rev-parse 1e3228b4^{tree}     ⇒ 83269e111f5f42e8b9481fd0e9549687aa8a7ed0   ← 相同
```

**② main 树里的 5478 个唯一 blob，0 个不在集成头历史中**（`comm -23 main_blobs our_blobs` ⇒ 0）

**冲突面：27 个，与第 99 轮逐字节相同**（程序化集合比对，**按 basename**：
第 99 轮 backlog 把 channel 目录前缀只写了一次，故裸文件名与全路径直接比对会出现 15 个假差异）：

```
round99(记录) = 27   round100(实测) = 27
NEW  : （空）
GONE : （空）
```

| 口径                 | 第 99 轮 | **第 100 轮** |
| -------------------- | -------- | ------------ |
| 文件数               | 27       | **27**       |
| **theirs 独有 = 0 ⇒ 取 ours 无损** | **4** | **4**（同一批 4 个文件） |
| **theirs 独有行合计** | **79**   | **79**       |
| ours 独有行合计      | 1643     | **1786**     |

> ours 独有行 1643 → 1786（+143）是集成头这期间自己的推进（batch 110/111/112）。
> ⇒ **再次印证第 99 轮修正后的判据**：**冲突面只在「集成头改动已存在的业务/测试文件」时增长**，
> **不是**「集成头前进了就增长」——本轮集成头新增的是**新模块文件**，故 27 → 27 不变。

**⚠️ 新增观测：闸的 `ageSeconds` 会被 keepalive 重置。**

`touch` 闸目录**内**的文件会刷新**目录自身 mtime**，而 `ageSeconds` 正是
`Date.now() - statSync(GATE_DIR).mtimeMs`（`lane.mjs:612`）。
⇒ 实测 12:21 观察到 `ageSeconds` 从 41 突降到 **1**：**不是**「闸被释放又重取」，**是持闸者做了一次续期**。
⇒ **判活仍只看 `stale`**（续期只会让 `stale` 更不容易为真）；**不要**把 `ageSeconds` 回落当作闸已释放。

**⚠️ 等闸量级实测**：活着的 L2 tick 可以持有闸 **1 小时+**（含每 60 秒续期 ⇒ `stale` 永远为真）。
本轮用**密集 `gate acquire` 竞态**等 **544 秒**后取到闸（**未抢、未清、未 force**）。
⇒ 建议 `SYNC-MAIN.md` 补一条「等闸上限 + 超时如实汇报退出」的显式判据。

---

## 影响面

- **只影响「main → 集成头同步」这一个任务**（`tools/module-progress/SYNC-MAIN.md`）的推进节奏，
  **不影响**流水线的并入 / L2（集成头自身照常演进，batch-39~42 均正常交付）。
- **不动任何业务代码**：本条待办是**发布流程 / 仓库拓扑**问题，不是代码问题。
- 涉及文件（**最新一轮 —— 第 100 轮（2026-10-29 12:15）的冲突面，27 个 = 26 add/add + 1 content**，与第 99 轮**逐字节相同**；theirs 独有 **79 行**、ours 独有 **1786 行**）：
  - `apps/web/src/components/dashboard/workbench-card-registry.ts`（add/add）
  - `apps/web/src/components/dashboard/workbench-layout-model.test.ts`（add/add）
  - `apps/web/src/lib/api-proxy.ts`（**content**；theirs 独有 **0 行**，ours 严格超集）
  - `backend/services/channel/src/test/java/com/starcon/channel/` 下 **15 个**：
    `DataGovernanceIntegrationTest.java` / `DataGovernanceTenancyIntegrationTest.java` /
    `FilingCalendarIntegrationTest.java` / `FilingCalendarTenancyIntegrationTest.java` /
    `GlobalSearchGroupIntegrationTest.java` / `GlobalSearchIntegrationTest.java` /
    `LinkageRiskIdentityStub.java`（theirs 独有 **0 行**）/ `LinkageRiskIntegrationTest.java` /
    `LinkageRiskTenancyIntegrationTest.java` / `SalesTaxIntegrationTest.java` /
    `SalesTaxTenancyIntegrationTest.java` / `SyncHealthIntegrationTest.java` /
    `SyncHealthTenancyIntegrationTest.java` / `VatIossIntegrationTest.java` /
    `VatIossTenancyIntegrationTest.java`
  - `backend/services/finance/src/test/java/com/starcon/finance/FundAnalysisIntegrationTest.java`（add/add）
  - `backend/services/identity/src/main/java/com/starcon/identity/NotificationInboxController.java`（add/add）
  - `backend/services/identity/src/main/java/com/starcon/identity/NotificationPreferenceService.java`（add/add）
  - `backend/services/identity/src/test/java/com/starcon/identity/ApprovalTodoIntegrationTest.java`（add/add）
  - `backend/services/identity/src/test/java/com/starcon/identity/SaasUsageIntegrationTest.java`（add/add，**第 98 轮新增**）
  - `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingFinancePort.java`（add/add，theirs 独有 **0 行**）
  - `backend/services/reporting/src/main/java/com/starcon/reporting/ReportingUpstreamClient.java`（add/add，theirs 独有 **0 行**）
  - `backend/services/trade/src/test/java/com/starcon/trade/AnomalyOrderIntegrationTest.java`（add/add，theirs 独有 **35 行**，墙钟基准）
  - `backend/services/trade/src/test/java/com/starcon/trade/AnomalyOrderTenancyIntegrationTest.java`（add/add，theirs 独有 **11 行**，墙钟基准）
- 涉及文件（**11:45 轮的冲突面，3 个**）：
  - `apps/web/src/lib/api-proxy.ts`（content 冲突；main 侧独有 **0 行**，ours 严格超集；**同时**是共享白名单累积文件，见
    `docs/backlog/2026-10/2026-10-21-api-proxy-shared-file-merge-conflict.md`）
  - `backend/services/catalog/src/test/java/com/starcon/catalog/PriceHistoryIntegrationTest.java`（add/add；main 侧独有 **28 行**，是**修复前的 JVM 墙钟写法** `OffsetDateTime.now().minusDays(10)`；ours 侧是 5.5.1.1 修复后的 `a1At/a2At` **DB 时钟锚定**写法（`dayStart` / `atOffset(ZoneOffset.UTC)`）⇒ 取 theirs 会**回退修复**）
  - `docs/backlog/2026-10/2026-10-21-main-squash-merge-divergence.md`（add/add；**本文件自身** —— ours 是 7 轮版本 267 行，theirs 是 6 轮版本 178 行 ⇒ 纯文档版本差）
  - （前 6 轮的旧冲突面，6 个，随 `ec225641` 上 main 后已自然消解：
    `cost-composition-page.test.ts` / `cost-composition-model.test.ts` / `cost-composition-model.ts` /
    `cost-composition-workspace.tsx` / `api-proxy.ts` / `ListingPublishJobIntegrationTest.java`）
- 根提交：`d28188d6`（`Integration/module dev (#11)`，2026-10-20 20:39:44 +0800，**squash**）
  ＋ `ec225641`（`feat: 到岸成本、成本分摊等 24 项能力交付`，**同样是 squash**，1 个父提交 = `d28188d6`）
- merge-base：`1bb270a0`（被老 squash 拉回）
