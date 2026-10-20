# `origin/main` 的 tip 是集成头的 squash —— main → 集成头同步结构性冲突（连续 5 轮未并入）

- 创建: 2026-10-21
- Owner: TBD（**发布侧**负责，见「待办内容」第 1 条）
- 优先级: **P0** —— 这是「main → 集成头同步」任务的**长期阻塞点**，每一轮 main 前进都会撞同一堵墙
- 关联: `tools/module-progress/SYNC-MAIN.md` · `docs/sessions/2026-10/2026-10-21-0115-main-sync-conflict-squash-divergence.md` · `docs/sessions/2026-10/2026-10-21-0315-main-sync-conflict-squash-divergence.md` · `docs/sessions/2026-10/2026-10-21-0415-main-sync-conflict-squash-divergence.md` · `docs/sessions/2026-10/2026-10-21-0515-main-sync-conflict-squash-divergence.md` · `docs/sessions/2026-10/2026-10-21-0643-main-sync-conflict-squash-divergence.md` · `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md`

---

## 背景

「main → 集成头同步」任务每轮做的是：`fetch` → `merge-base --is-ancestor origin/main integration/module-dev`
→ 有新提交就 `git merge --no-ff origin/main` → 预检 → push 集成头。

**连续 5 轮（2026-10-21 01:15、03:15、04:15、05:15、06:43）都在同一个地方停住**：`origin/main` 的 tip `d28188d6`
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

### 影响

- 「main → 集成头同步」任务**连续 5 轮产出「冲突未并入」**。
  各轮等闸时长：45 / 40 / 27 / **0** / **26** 分钟 ⇒ **闸不是瓶颈，唯一瓶颈是 main 的 tip 是 squash**。
  05:15 轮闸是空的、第 1 次尝试就拿到；06:43 轮闸被**上一轮流水线的后端 L2（`mvnw verify`）**持有，
  按协议用密集竞态 `gate acquire`（1 秒一次）等到第 1568 次命中（26 分 08 秒）。
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
- 那个 406 files / 262390 insertions 的 diffstat 是 **squash 把 85 个 commit 压成一颗造成的行数重放**，
  不是真有 26 万行新代码 —— **不要按这个数字判断工作量**。
- `merge-union` 驱动能自动合上的部分（契约 `packages/api-contracts/**` + 台账 `module-progress.md`）
  **不是问题源**；问题源是 6 个业务/测试文件上的 add/add。

---

## 待办内容

- [ ] **（首选，改发布侧）发布侧把集成头的真实 commit（不 squash）推到 main**，
      使 `origin/main` 的 tip 重新成为集成头的祖先 ⇒ 本任务回到「无新提交」的零改动常态，
      冲突从根上消失。**这是唯一的根治办法** —— 在集成头这边做任何解冲突都是治标。
- [ ] **（次选）人工决定 main 的基线**：把 main 重置到集成头，或接受 squash 作为新基线
      （后者需要配套一条「main 是集成头快照」的读取约定，否则台账会持续倒退）。
- [ ] **（若坚持并入）开一条 fix 车道解这 6 个冲突**，方向「以集成头为准 + 逐行核对 main 那
      2–18 行独有文本是否需要保留」。**不要用 `git checkout --ours/--theirs` 批量覆盖**：
      第 1/2/3/6 个文件 main 侧确有独有文本。解完**必须重跑 L2**（这批内容从未经过 L1/L2）。
      （2026-10-21 05:15 轮补充：这 6 个文件里 **5 个是 add/add** —— 它们在 merge-base `1bb270a0` 上
      **根本不存在**，是集成头在 batch-28 之后新增的；第 5 个 `api-proxy.ts` 是 content 冲突。
      另有 **15 个文件**含 main 独有文本，其中 **8 个**由 `merge-union` 求并、**3 个**由 git 三方合并
      干净合上、**4 个**即上述冲突文件、**2 个**是集成头严格超集。）
- [ ] **（顺带）给同步任务补「回合被截断也要 `gate release`」的收尾保障**：
      2026-10-21 02:15 那个同步任务回合在 02:47:10 被截断，**既没写会话日志也没 release 闸**
      ⇒ 闸泄漏 70 分钟，白挡了 03:00 轮与 03:15 轮两次同步。
      建议协议第 6 步加兜底，或由调度器在回合异常结束时统一 release。

---

## 影响面

- **只影响「main → 集成头同步」这一个任务**（`tools/module-progress/SYNC-MAIN.md`）的推进节奏，
  **不影响**流水线的并入 / L2（集成头自身照常演进，batch-39~42 均正常交付）。
- **不动任何业务代码**：本条待办是**发布流程 / 仓库拓扑**问题，不是代码问题。
- 涉及文件（冲突面，6 个）：
  - `apps/web/src/app/pricing/cost/cost-composition-page.test.ts`
  - `apps/web/src/components/pricing/cost-composition-model.test.ts`
  - `apps/web/src/components/pricing/cost-composition-model.ts`
  - `apps/web/src/components/pricing/cost-composition-workspace.tsx`
  - `apps/web/src/lib/api-proxy.ts`（**同时**是共享白名单累积文件，见
    `docs/backlog/2026-10-2026-10-21-api-proxy-shared-file-merge-conflict.md`）
  - `backend/services/channel/src/test/java/com/starcon/channel/ListingPublishJobIntegrationTest.java`
- 根提交：`d28188d6`（`Integration/module dev (#11)`，2026-10-20 20:39:44 +0800）
- merge-base：`1bb270a0`
