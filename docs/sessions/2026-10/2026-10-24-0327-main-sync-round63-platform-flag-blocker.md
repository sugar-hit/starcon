# 2026-10-24: main 同步第 63 轮 —— 集成头零推进、闸门仍阻塞于平台开关精度缺陷

## 摘要

本轮**没有合入任何内容**。集成分支相对同步分支头**零推进**（`upstream/chore/sync-module-dev..upstream/integration/module-dev`
提交数为 0），同步分支已是集成头的全量镜像，**没有可刷新的新交付**。

PR #65（`chore/sync-module-dev` → `main`）的 CI 判定仍是**阻塞**：出现了 `main` 基线里没有的失败用例
`PlatformFlagIntegrationTest.公告生效期按值写回且窗外不展示`。

本轮做了三件有增量的事：① 独立复核了闸门标为「明细不可比」的三个作业，落到**文件级/用例级**；
② 确认该失败用例**至今没有任何车道覆盖**，因此在 `docs/backlog/` 补了可跟踪的条目；③ 查清集成头与同步分支头之间
那 10 个差异文件的真实性质（纯排版漂移，集成侧才是未格式化的一方）。

## 决策 / 结论

1. **本轮不合 PR #65**。闸工具结论「阻塞」：出现了 `main` 基线里没有的失败用例。
2. **本轮不刷新同步分支**。集成分支相对同步分支头**零提交推进**，没有新交付可取。
3. **不在同步分支上修那条失败用例**。它是集成侧交付内容的所有物；同步分支只做「合入」，不做开发。
   解除条件是集成侧（或人工）修掉该用例的精度假设。
4. **补 `docs/backlog/` 条目**。此前该缺陷只有会话记录，没有任何可跟踪的待办条目 ——
   而会话记录不进 backlog，**流水线按 backlog/台账派活时看不到它** ⇒ 该缺陷会无限期阻塞每一次合并。

## 关键 trade-off

- **为什么不因为「反正 main 也是红的」就合**：闸门判据是**基线相对**（PR 的失败不得比基线更多），
  不是「基线也红就放行」。本轮 PR 的失败用例集合严格包含基线集合且多出一条 ⇒ 不合。
- **为什么这次下钻「明细不可比」的三个作业**：闸门只比到作业粒度时，「持平」是**默认放行**而非证据。
  落到文件级/用例级才能区分「真的持平」与「只是看不见差异」。
- **为什么不自己修那条用例**：让 PR 能推进与让 PR 正确是两件事。缺陷在**测试的精度假设**
  （拿纳秒精度的 `OffsetDateTime.now()` 去逐值比较只存微秒的 `timestamptz` 回读值），
  在同步分支上打补丁会让同步分支与集成分支就此分叉，集成侧后续修复时还要再解一次冲突。
- **为什么补 backlog 条目而不是只写会话记录**：会话记录是**给人读的**，不是给派活器读的。
  这个缺陷已经连续三轮阻塞合并，而派活链路看不到它 —— 这正是它迟迟没人修的原因。

## 本轮关键取证

### ① 集成分支零推进 ⇒ 本轮无刷新对象

```
git rev-list --count upstream/chore/sync-module-dev..upstream/integration/module-dev  → 0
git merge-base --is-ancestor upstream/main upstream/chore/sync-module-dev            → true
```

集成头 `d7d58550`（2026-10-24 02:17:36）已被同步分支头 `1dc97957` 包含 ⇒ **同步分支已是集成头的全量镜像**。

### ② 闸门终态（run `40005009323`，状态 `completed`，head_sha 与 PR 头逐位相同）

| 作业                             | 基线 main | PR 本轮 |
| -------------------------------- | --------- | ------- |
| Catalog Browser & Contract Tests | ✗         | ✗       |
| Java 21 & PostgreSQL Integration | ✗         | ✗       |
| Lint                             | ✗         | ✗       |
| Security Scan                    | ✗         | ✗       |
| Typecheck                        | ✓         | ✓       |
| Unit & Integration Tests         | ✓         | ✓       |

新增失败作业：无。**新增失败用例：1 条**（`PlatformFlagIntegrationTest.公告生效期按值写回且窗外不展示`）。

### ③ 三个「明细不可比」作业 → 自主下钻到文件级/用例级

| 作业            | 基线（main run `39883208132`）                    | PR（run `40005009323`）          | 判定               |
| --------------- | ------------------------------------------------- | -------------------------------- | ------------------ |
| Catalog Browser | channel 服务 `brandCredentialController` 装配失败 | 同上，错误文本与出现次数逐项相同 | **持平**           |
| Lint            | 646 个文件不合规                                  | 749 个文件不合规                 | **不劣化**（见下） |
| Security Scan   | `pnpm audit` 失败，11 项漏洞                      | `pnpm audit` 失败，10 项漏洞     | **不劣化**（见下） |

- **Lint 的排版债对账**：基线 **646** + PR 独有 **103** = **749**，与 CI 自报数字逐位吻合。
  逐个核对这 103 个文件在 `main` 上是否存在：**103 个全部是 `main` 上不存在的新文件，
  既有文件从合规退化为不合规的数量为 0** ⇒ 本次合入**没有让任何既有文件退化**。
- **Security Scan 的差异不来自代码**：`pnpm-lock.yaml` 在 `main` 与同步分支头之间**逐字节相同**
  （`ee031933ce5cf788f8758b5e0058b8c8395f0bf7`）⇒ 本次合入没有引入任何依赖变化；
  10 与 11 的差异来自两次运行之间漏洞库自身的数据漂移，两侧可见的 critical / high 集合相同。
- **Java 作业用例级比对**：基线 **270 例 / 1 失败**（`ApprovalRecordIntegrationTest`）→
  PR **515 例 / 2 失败**（同一条 + 新增 `PlatformFlagIntegrationTest`）。

### ④ 新增失败用例的归属与根因（复核，非合并引入）

CI 断言原文：

```
expected: 2026-09-23T18:42:19.524181159Z (java.time.OffsetDateTime)
 but was: 2026-09-23T18:42:19.524181Z (java.time.OffsetDateTime)
when comparing values using 'OffsetDateTime.timeLineOrder()'
	at ...PlatformFlagIntegrationTest.公告生效期按值写回且窗外不展示(PlatformFlagIntegrationTest.java:273)
```

- 期望值 **9 位小数（纳秒）**，实际值 **6 位小数（微秒）** ⇒ 少 **159 纳秒** ⇒ 按时刻序比较必然不等。
- 存储层：`V324__platform_feature_flags.sql:151` 声明 `announcement_from timestamptz`，
  PostgreSQL 的 `timestamptz` 默认精度就是**微秒** ⇒ 写进去时被截断。
- 用例注释预判的是「偏 8 小时（-28800s）」，**实际发生的是亚微秒截断** —— 从断言数值可直接看出
  **没有 28800 秒偏移** ⇒ 时区是对的，两者根因不同、修法也不同。
- 归属：该用例在 `main` 上**不存在**（由 `fdb21bec`（17.7.2.1）在集成分支引入），
  且在集成头与同步分支头之间**逐字节相同** ⇒ 不是合并动作引入的。

### ⑤ 集成头 vs 同步分支头：10 个差异文件是**纯排版漂移**，集成侧才是未格式化的一方

逐个用仓库自己的 Prettier 归一化后比对，**两侧归一化结果逐字节相同**：

| 文件                                                                            | 同步分支头 | 集成头 | 归一化后 |
| ------------------------------------------------------------------------------- | ---------- | ------ | -------- |
| `apps/web/src/lib/api-proxy.ts`                                                 | CLEAN      | DIRTY  | 相同     |
| `packages/ui/src/components/common/command-palette.tsx`                         | CLEAN      | DIRTY  | 相同     |
| `packages/ui/src/components/layout/app-shell.tsx`                               | CLEAN      | DIRTY  | 相同     |
| `apps/web/src/components/catalog/catalog-workspace.tsx`                         | CLEAN      | DIRTY  | 相同     |
| `apps/web/src/components/warehouse/warehouse-outbound-timeliness-workspace.tsx` | CLEAN      | DIRTY  | 相同     |
| `apps/web/src/app/api/iam/[...path]/platform-ops-allowlist.test.ts`             | CLEAN      | DIRTY  | 相同     |

Java 侧 `backend/libraries/tenancy/src/main/java/com/starcon/tenancy/GrantPermissions.java`：
去掉注释与空白后两侧归一化到**同一个 1977 字符、逐字节相同** ⇒ 代码零变化，差异全是 Javadoc 折行。
（配**阴性对照**：注入已知改动后比较立即不成立 ⇒ 检测器有效。）

⇒ **这 10 个文件是集成头相对同步分支头推进的一次纯格式化改写**：集成侧按约 80 列重排，
而仓库自己的 `.prettierrc` 规定 `printWidth: 100` ⇒ **取集成侧 = 新增 6 个未格式化文件 = 自造排版债**。

### ⑥ 迁移形状预检（只针对本 PR 新增的 19 个迁移）

新增迁移全部是新建；唯一的 CHECK 约束（`platform_flag_setting_shape`，`V324`）作用在**同一迁移里新建的表**上。
`V337__trade_return_inventory_disposition.sql` 对 `return_quality_disposition_instruction`（`main` 上由 `V179` 建）
的改动是**放宽** CHECK（`'PENDING'` → `'PENDING'/'CONSUMED'`），不是收紧既有取值域 ⇒ 没有「收紧既有表」的形状。
CI 实跑证据：catalog 到 v245、identity 到 v336、trade 到 v337、warehouse 到 v337、finance 到 v345，**全部迁移成功**。

### ⑦ 台账与静态预检

- 台账 **465 行**、零重复模块 ID；`sync.mjs --check` 在 PR 头上**通过**（台账与蓝图一致）。
- 功能增量（`merge-summary.mjs`，以台账为准）：**新增交付 28 项**，与 PR 标题/正文一致。

## 涉及文件

- `backend/services/identity/src/test/java/com/starcon/identity/PlatformFlagIntegrationTest.java`
  —— 基线之外的失败用例所在处（集成侧交付内容），本轮未改动
- `backend/services/identity/src/main/resources/db/migration/V324__platform_feature_flags.sql`
  —— `announcement_from timestamptz`，微秒精度的证据
- `docs/backlog/2026-10/2026-10-24-platform-flag-announcement-window-microsecond-precision.md`
  —— 本轮新增：该缺陷的可跟踪待办条目（此前只存在于会话记录里，派活链路看不到）
- `docs/product/capability-blueprint/module-progress.md` —— 台账，465 行
- `docs/sessions/2026-10/2026-10-24-0327-main-sync-round63-platform-flag-blocker.md` —— 本篇

## 后续待办

1. **集成侧（或人工）修掉** `PlatformFlagIntegrationTest.公告生效期按值写回且窗外不展示`：
   构造夹具时先把精度截断到微秒（或在断言处改用容差比较）。
   **解除条件就是这一条** —— 它修好之前，本任务每一轮都会在同一处阻塞。
   详细口径见 `docs/backlog/2026-10/2026-10-24-platform-flag-announcement-window-microsecond-precision.md`。
2. 注意：**服务端截断不能替代修测试**。断言比较的是客户端那个未截断的纳秒值，
   服务端无论如何变不出那 159 纳秒 ⇒ 只有修测试侧能让这条断言转绿且不削弱它。
3. Java 作业在 identity 模块失败后，reactor 会把 `catalog`/`channel`/`warehouse`/`trade`/`crm`/`finance`
   全部 SKIPPED ⇒ 本次交付的 4 项财务能力（坏账与核销、付款计划、核销、结算单管理）
   的集成测试**在 CI 里一次都没跑过**；上一条解除后它们才会有 CI 证据。
4. 集成头若再推批量格式化改动，**同步前逐文件用仓库自己的 Prettier 比对合规性，取合规的一侧**
   —— 本轮集成头那 6 个 TS/TSX 文件就是反例（集成侧不合规）。
