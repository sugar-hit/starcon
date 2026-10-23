# 2026-10-24: main 同步第 64 轮 —— 新增第二条阻塞用例（用量统计隔离断言假阳性）、闸工具自身的假绿灯

## 摘要

本轮**没有合入任何内容**，PR #65 的闸门结论是**阻塞**，且比上一轮**多出一条**失败用例。

本轮的三件事有增量：

1. **定位了第二条新增失败用例的根因**：`SaasUsageIntegrationTest.tenantIsolationHoldsOnThreeAssertions`
   红了。它**不是租户隔离被突破**，而是断言用「用量值 111 / 222」当隔离标记，
   撞上了响应体里随机租户编码中的连续 `111` ⇒ **假阳性**（本轮随机标记取到 `E4D52111B`）。
2. **发现闸工具自身的判据缺陷**：run 仍在进行时调用它，它会输出「无失败作业」并给出
   **`结论：可以合并`**；同一个 run 收尾后再调，结论变成 `阻塞` ⇒ **假绿灯**。
   本轮没有采信这个假绿灯，因此没有把带新增失败的 PR 合进 `main`。
3. **独立复核了集成头相对同步分支头的推进**：仍是**一次纯格式化改写**（功能零变化、台账零变化），
   集成侧那 6 个 TS/TSX 文件仍不符合仓库自己的 Prettier 规范 ⇒ **本轮不刷新同步分支**。

## 决策 / 结论

1. **本轮不合 PR #65**。闸工具在 run 收尾后给出「阻塞」：出现了 `main` 基线里没有的失败用例 2 条。
2. **本轮不刷新同步分支**。集成头相对同步分支头的推进是纯格式化改写，功能增量与台账增量均为零，
   且集成侧原文不符合仓库自己的格式化标准 ⇒ 取用只会新增排版债。
3. **不在同步分支上修改那两条失败用例**。它们是集成侧交付内容的所有物；本任务只做「合入」，不做开发，
   也不允许为了让 PR 能推而改测试、放宽断言或跳过用例。
4. **给两条新缺陷补可跟踪的 backlog 条目**。会话记录是给人读的，**派活链路看不到** ——
   平台开关那条已在第 63 轮补过，本轮补上用量统计隔离断言与闸工具假绿灯这两条。
5. **不采信「run 仍在进行」时的闸门结论**。这是本轮最重要的一条操作性纪律。

## 关键 trade-off

- **为什么不因为「反正 main 也是红的」就合**：闸门判据是**基线相对**（PR 的失败不得比基线更多），
  不是「基线也红就放行」。本轮 Java 作业从基线的 **270 例 / 1 失败**变成 **515 例 / 3 失败**，
  多出来的两条都不在基线里 ⇒ 不合。
- **为什么把「随机编码撞车」查到底而不是记成「隔离用例红了」**：这条用例守的是
  「两租户互不可见」，是 `AGENTS.md` 多租户章节要求证明的三条之一 ——
  **误判成数据串租户会指向完全错误的修复方向**。查清「实际响应只有被查租户自己那一行、
  且 `contains("222")` 通过」之后，才敢下「隔离行为正确、断言写法有问题」的结论。
- **为什么要在同步分支上写会话与 backlog 记录，尽管这两条都不是合入动作**：
  AGENTS.md 要求每个有结论的会话都落 `docs/sessions/`；而**这两个缺陷会无限期阻塞每一次合并**，
  派活链路只看 backlog 与台账 ⇒ 不补条目就等于没人会修它们。
- **为什么不为省一轮而采信那个「可以合并」**：假绿灯放行的那一刻，红色还没出现。
  在基线相对判据下，「看不见失败」与「没有失败」在结果上无法区分 —— 只有 run 收尾后结论才有意义。

## 本轮关键取证

### ① 闸门终态（run `40011998322`，状态 `completed`/`failure`，head_sha = PR 头 `89dd8621`）

| 作业                             | 基线 main（run `39883208132`） | PR 本轮              | 判定                |
| -------------------------------- | ------------------------------ | -------------------- | ------------------- |
| Catalog Browser & Contract Tests | ✗                              | ✗                    | 明细不可比          |
| Java 21 & PostgreSQL Integration | ✗（270 例 / 1 失败）           | ✗（515 例 / 3 失败） | **多 2 条失败用例** |
| Lint                             | ✗                              | ✗                    | 明细不可比          |
| Security Scan                    | ✗                              | ✗                    | 明细不可比          |
| Typecheck                        | ✓                              | ✓                    | 持平                |
| Unit & Integration Tests         | ✓                              | ✓                    | 持平                |

新增失败作业：无。**新增失败用例 2 条**：
`PlatformFlagIntegrationTest.公告生效期按值写回且窗外不展示`、
`SaasUsageIntegrationTest.tenantIsolationHoldsOnThreeAssertions`。

### ② 闸工具在 run 进行中给出「可以合并」——假绿灯

run `40011998322` 于 `19:30:50Z` 创建、`19:39:50Z` 收尾。`19:31` 调用闸工具时，6 个作业**全部**
`in_progress`、`conclusion` 全为 `null`，工具输出：

```
PR #65（run #40011998322）：
  （无失败作业）

新增失败作业：无
新增失败用例：无

结论：可以合并 —— 失败情况与 main 基线持平，未出现新增失败。
```

run 收尾后对**同一个 run** 再调，结论变成「阻塞」。⇒ 在飞作业被当成了「通过」。
本轮未据此合并任何内容。详见 backlog
[`2026-10-24-ci-gate-in-progress-run-false-green.md`](../backlog/2026-10/2026-10-24-ci-gate-in-progress-run-false-green.md)。

### ③ 新增失败用例之二：用量统计「租户隔离」断言是假阳性

CI 断言原文：

```
Expecting actual:
  "[{"tenantId":"623e54f4-4723-4597-ac7b-e2a063c8c93d","tenantCode":"USE_TE4D52111B",
    "tenantName":"用量统计目标租户 E4D52111B",...,"quantity":222.0,...}]"
not to contain:
  "111"
	at ...SaasUsageIntegrationTest.tenantIsolationHoldsOnThreeAssertions(SaasUsageIntegrationTest.java:631)
```

- 断言用用量值 `111` / `222` 当租户隔离标记，在**整段 JSON 文本**里做裸数字子串匹配。
- 租户编码与租户名里带着随机标记 `tag`（`IdentityIntegrationTestBase.suffix()` =
  UUID 前 8 位大写十六进制），**本轮取到 `E4D52111B`**，其中含连续 `111` ⇒ `doesNotContain("111")` 误判。
- 响应只有一行，就是被查询租户自己的数据（`quantity = 222.0` 即该租户自己的用量，
  `contains("222")` 这条**通过**了）⇒ **租户隔离行为正确，断言写法有问题**。
- 撞车概率约 **0.15% / 租户**（8 位十六进制串含连续 `111` 的位置有 6 个，单位置概率 `1/4096`）。
- 归属：该用例在 `main` 上**不存在**（集成侧新交付），在集成头与同步分支头之间**逐字节相同**
  ⇒ 不是合并动作引入的。

详见 backlog
[`2026-10-24-saas-usage-tenant-isolation-marker-collision.md`](../backlog/2026-10/2026-10-24-saas-usage-tenant-isolation-marker-collision.md)。

### ④ 新增失败用例之一：平台开关精度缺陷（沿用第 62/63 轮结论，本轮复核）

```
expected: 2026-09-23T19:33:16.903871786Z (java.time.OffsetDateTime)
 but was: 2026-09-23T19:33:16.903872Z (java.time.OffsetDateTime)
when comparing values using 'OffsetDateTime.timeLineOrder()'
	at ...PlatformFlagIntegrationTest.公告生效期按值写回且窗外不展示(PlatformFlagIntegrationTest.java:273)
```

期望值 9 位小数（纳秒）、实际值 6 位小数（微秒）⇒ 少 **214 纳秒** ⇒ 按时刻序比较必然不等。
存储层 `V324__platform_feature_flags.sql` 的 `announcement_from timestamptz` 只有微秒精度 ⇒
写进去时被截断。**缺陷在测试的精度假设，不在业务逻辑**（第 62 轮已定位，第 63 轮已补 backlog 条目）。

### ⑤ 集成头相对同步分支头：仍是纯格式化改写 ⇒ 不刷新

集成头 `d7d58550` 相对 PR 头 `89dd8621` 推进 10 个文件，其中**没有台账文件** ⇒ 功能增量与台账增量均为零。

| 文件                                                                            | 同步分支头 | 集成头 | 归一化后 |
| ------------------------------------------------------------------------------- | ---------- | ------ | -------- |
| `apps/web/src/lib/api-proxy.ts`                                                 | CLEAN      | DIRTY  | 相同     |
| `packages/ui/src/components/common/command-palette.tsx`                         | CLEAN      | DIRTY  | 相同     |
| `packages/ui/src/components/layout/app-shell.tsx`                               | CLEAN      | DIRTY  | 相同     |
| `apps/web/src/components/catalog/catalog-workspace.tsx`                         | CLEAN      | DIRTY  | 相同     |
| `apps/web/src/components/warehouse/warehouse-outbound-timeliness-workspace.tsx` | CLEAN      | DIRTY  | 相同     |
| `apps/web/src/app/api/iam/[...path]/platform-ops-allowlist.test.ts`             | CLEAN      | DIRTY  | 相同     |

- 合规性用**仓库自己的 Prettier**（`--stdin-filepath` 后比字节哈希）判定：
  同步分支头侧 **6 个文件全 CLEAN**，集成头侧 **6 个文件全 DIRTY** ——
  集成侧按约 80 列重排，而仓库 `.prettierrc` 规定 `printWidth: 100`。
- Java 侧 `backend/libraries/tenancy/src/main/java/com/starcon/tenancy/GrantPermissions.java`：
  去掉注释与空白后两侧归一化到**同一个 SHA-1** ⇒ 代码零变化，差异全是 Javadoc 折行。
- 另两个文件是同步分支侧多出来的文档（backlog 条目与会话记录），集成侧本就没有 ⇒ 无需处理。

⇒ **取集成侧原文 = 新增 6 个未格式化文件 = 自造排版债，换来零功能增量** ⇒ 不刷新。

### ⑥ PR 头是集成头的功能镜像；`main` 自 PR 建起未推进

- `merge-summary.mjs` 在 `main → 集成头` 与 `main → PR 头` 两个口径上输出**同一份清单**：
  **新增交付 28 项**（工作台与门户 13 项、售后与退货 1 项、财务核算 8 项、合规与风控 2 项、
  组织权限与设置 4 项）⇒ PR 头已包含集成头的全部功能交付。
- 台账：两侧均 **465 行**，**零重复模块 ID**。
- `main` 头 `6522c497`（2026-10-23 09:18:01 +0800），
  `git log --since='2026-10-23T18:00:00Z' upstream/main` 为空 ⇒ **`main` 自 PR 建起未推进**，
  PR 头就是基于当前 `main` 构建的。
- 合并预演：`git merge-tree --write-tree upstream/main upstream/integration/module-dev` 退出码 **0**
  ⇒ 若将来刷新，形态是**真合并无冲突**。

### ⑦ 验证级别说明

本轮**没有做代码合并**，因此没有代码增量需要本地验证 —— 证据全部取自 CI 作业日志（**用例级**）。
按协议默认**未跑本地全量 L2**（发热高峰，且本次没有增量可验）。台账 465 行与零重复 ID 已复核。

## 涉及文件

- `backend/services/identity/src/test/java/com/starcon/identity/SaasUsageIntegrationTest.java`
  —— 本轮新增失败用例所在处（集成侧交付内容），本轮未改动
- `backend/services/identity/src/test/java/com/starcon/identity/IdentityIntegrationTestBase.java`
  —— `suffix()`：随机租户标记的来源
- `backend/services/identity/src/main/resources/db/migration/V324__platform_feature_flags.sql`
  —— `announcement_from timestamptz`，微秒精度的证据
- `docs/backlog/2026-10/2026-10-24-saas-usage-tenant-isolation-marker-collision.md`
  —— 本轮新增：用量统计隔离断言假阳性的可跟踪待办条目
- `docs/backlog/2026-10/2026-10-24-ci-gate-in-progress-run-false-green.md`
  —— 本轮新增：闸工具在 run 进行中给出假绿灯的可跟踪待办条目
- `docs/product/capability-blueprint/module-progress.md` —— 台账，465 行
- `docs/sessions/2026-10/2026-10-24-0343-main-sync-round64-saas-usage-isolation-false-positive.md` —— 本篇

## 后续待办

1. **集成侧（或人工）修掉** `SaasUsageIntegrationTest.tenantIsolationHoldsOnThreeAssertions`：
   把隔离标记换成不可能出现在随机编码里的形式，或改为对租户 UUID / `quantity` 字段做精确断言。
   **这是本轮新增的阻塞项**，解除条件见对应的 backlog 条目。
2. **集成侧（或人工）修掉** `PlatformFlagIntegrationTest.公告生效期按值写回且窗外不展示`：
   构造夹具时先把精度截断到微秒（或改用容差比较）。**这一条从第 61 轮起就阻塞合并**，
   详细口径见 `docs/backlog/2026-10/2026-10-24-platform-flag-announcement-window-microsecond-precision.md`。
   ⚠️ **两条都要修** —— 闸门判据是「失败不得比基线更多」，一条修好仍然阻塞。
3. **修 `ci-gate.mjs`**：run 未收尾时按「状态取不到」处理（退出码 2），不得给出「可以合并」；
   并打印被判读 run 的 `status` / `conclusion` / `head_sha`。
4. Java 作业在 identity 模块失败后，reactor 会把 `catalog` / `channel` / `warehouse` / `trade` /
   `crm` / `finance` 全部 SKIPPED ⇒ 本次交付的 4 项财务能力（坏账与核销、付款计划、核销、结算单管理）
   的集成测试**在 CI 里一次都没跑过**；上面两条修好之后它们才会有 CI 证据。
5. 集成头若再推批量格式化改动，**同步前逐文件用仓库自己的 Prettier 比对合规性，取合规的一侧**。

## 关联

- `docs/backlog/2026-10/2026-10-24-platform-flag-announcement-window-microsecond-precision.md`
- `docs/backlog/2026-10/2026-10-24-saas-usage-tenant-isolation-marker-collision.md`
- `docs/backlog/2026-10/2026-10-24-ci-gate-in-progress-run-false-green.md`
- `docs/sessions/2026-10/2026-10-24-0327-main-sync-round63-platform-flag-blocker.md` —— 上一轮
- `docs/sessions/2026-10/2026-10-24-0237-main-sync-round62-platform-flag-precision-rootcause.md`
- `docs/sessions/2026-10/2026-10-24-0245-main-sync-round61-refresh-and-spotless-blocker.md`
