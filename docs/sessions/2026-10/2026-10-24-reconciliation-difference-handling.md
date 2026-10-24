# 12.3.2.2 差异处理（阶段 D6，L3 锚点 12.3.2 对账）

日期：2026-10-24
车道：`feat/12-3-2-2`（worktree `.worktrees/12.3.2.2`）
基线：`integration/module-dev` @ `aada69c3`
状态：已交付

## 蓝图原文（卷 12 财务核算 · 12.3.2 对账 · 第二格）

> - **职责**：处理对账差异：分类（我方多记/对方多记/时间差/汇率差/手续费遗漏）、指定责任人、人工判定与调整、闭环跟踪。
> - **输入**：差异明细；处理结论；调整凭证或说明。
> - **输出**：差异处理记录 + 调整分录 + 闭环状态。
> - **关系**：上游 = 12.3.2.1；下游 = 12.5.2 凭证、1.3 预警。**差异分类是改进闭环的输入**：某类差异反复出现说明上游流程有系统性问题。

## 关键决策

### 1. 🔴 上游差异是**执行时的事实** ⇒ 处理记录另起三张表，只推进上游的 `status`

12.3.2.1 的 `finance_reconciliation_difference` 上挂着一个触发器
`guard_finance_reconciliation_difference_immutable`：**除 `status` 外每个字段都不可改写**
（差异类型 / 侧 / 两侧金额 / 币种 / 差额 / 归因 / 原因 …），`status` 还只能**向前**。
这意味着「分类 / 责任人 / 结论 / 调整分录」**在物理上无处可放**。

三种做法：

1. `ALTER` 上游表加列 —— ❌ 明令禁止，且上游 V370 已进过集成头被应用过，改内容会炸 Flyway checksum；
2. 复用上游 `finance_reconciliation_event` 追加事件 —— ❌ 那张表的 `event_type` 被 CHECK 钉死成
   任务级事件（`TASK_CREATED` / `TASK_SCOPE_UPDATED` / `MATCH_COMPLETED`），塞「差异认领」进去
   要么撞 CHECK、要么借用一个语义不对的类型；而**放行新类型 = ALTER 上游的表**；
3. **本模块自建三张表**（`finance_reconciliation_handling` / `_adjustment` / `_handling_event`），
   上游那张表**只被推进 `status`** —— ✅ 采纳。

取舍：多了一次「上游状态 ↔ 处理记录状态」的同步。这份同步不是靠应用层自觉，而是由
`guard_finance_reconciliation_handling_state` 触发器**逐次核对**：
处理记录写什么状态，上游差异就必须**已经是**那个状态，否则直接拒。
⇒ 直连 SQL 也写不出「处理记录说已核销、上游差异还挂在待处理」这种自相矛盾。

服务的写法因此固定成**先推上游、再写处理记录**（反过来会被触发器拒）。

### 2. 🔴 状态机与上游**同词表、同相位**：OPEN → CLAIMED → ADJUSTED / WRITTEN_OFF

直接复用上游的四个词（上游的 `status_rank` 触发器已经钉死 OPEN(0) < CLAIMED(1) < ADJUSTED(2) < WRITTEN_OFF(3)），
好处是**不需要翻译层**：页面、契约、DB 三处说的是同一件事。

`ADJUSTED` 与 `WRITTEN_OFF` 是**并列的两个终态**，不是先后关系：
- 「已调整」= 判定要动账，**至少要有一条调整分录**；
- 「已核销」= 判定**不需要**动账（账已对平 / 不该动），因此**不要求**分录。

闭��后**整行冻结**（DB 触发器）：已调整 / 已核销之后连**分类都不能再改** ——
「处理结论」被改写等于把已经结掉的账重新打开。

### 3. 🔴 闭环是一张**有约束的工单**，不是「改个状态」

表级 CHECK 把蓝图输入句（处理结论；调整凭证或说明）变成数据层约束：

- `CLAIMED` ⇒ **有责任人**（没有主人的差异进不了处理流程；改派只在这一态允许）；
- `ADJUSTED` / `WRITTEN_OFF` ⇒ 有责任人 **且** 有分类归因 **且** 有闭环结论；
- **分类理由必填**（人工分类 + 理由是蓝图点名的输入，不是可选备注）；
- **调整分录 = 凭证 或 说明**：`voucher_no IS NOT NULL OR btrim(explanation) <> ''`
  —— 两者都没有 = 这条分录什么都不是（蓝图写的是「或」，不是「和」）。

另外两条 DB 级闸：
- 判定「已调整」**必须至少有一条调整分录**（`guard_finance_reconciliation_handling_close`）——
  服务层 400 拦一道，触发器再拦一道，直连 SQL 也绕不过；
- 调整分录**只允许挂在已认领的处理记录上**（没主人就没资格记账）。

### 4. 🔴 归因分类 ≠ 上游差异类型 —— 这是「改进闭环的输入」

上游的 `difference_type`（`EXTERNAL_ONLY` / `BOOK_ONLY` / `AMOUNT_MISMATCH` /
`CURRENCY_MISMATCH` / `DIMENSION_MISMATCH`）说的是**差在哪儿**；
本模块的 `category` 说的是**为什么差**（我方多记 / 对方多记 / 时间差 / 汇率差 / 手续费遗漏）。

蓝图那句「差异分类是改进闭环的输入：某类差异反复出现说明上游流程有系统性问题」指的正是后者 ——
所以队列汇总里必须给出**分类 × 币种**的分布（条数 + 同币种上游差额 + 同币种调整额）。
没有这张分布，蓝图那句话在系统里就没有落点。

分类**可以修正**（人在过程中会改判），但每次修正都**追加**一条时间线；闭环之后冻结。

### 5. 🔴 多币种绝不相加：每条分录自带币种

上游跨币种差异的 `difference_amount` 恒为 NULL（汇率不可得，DB CHECK 钉死，**不按 1:1 兜底**）。
本模块的调整分录**每条自带币种**，一切汇总**按币种分组** ⇒ 结构上不存在「把 USD 和 EUR 加起来」这一格。

一行差异上**只放分录条数、不放分录金额** —— 一条差异的调整分录可能跨币种，行上相加会算错；
金额一律落到**按币种分组**的汇总里。

### 6. 只存责任人 uuid，不存姓名

本仓**没有用户目录服务**（identity 只给当前会话的成员关系与权限）。
存姓名只能是前端编的 ⇒ 与 08 仓储的 `assignee_user_id` 同一口径，**只存 uuid**，
页面显示 uuid 前 8 位（完整 id 放 title）。页面**不编造姓名**。

### 7. 不生成凭证（下游 12.5.2.1 未交付）

蓝图说「下游 = 12.5.2 凭证」，而 12.5.2.1 未交付 ⇒ 本模块**只登记**调整分录
（凭证号 **或** 说明），凭证本身由 12.5.2.1 生成。自己造一套凭证 = 造出第二套账。

### 8. 权限码沿用 `finance.receivable.read / .update`

12 财务核算家族共用同一套词汇表，**不新造权限词表**（新造一档要改 identity 的迁移 ——
那是另一个服务的写入所有权，且迁移版本号由调度者统一分配）。

### 9. 契约修正：`ReconciliationDiffDetail` **不挂 `handling` 对象**

处理记录**可能还不存在**（没人碰过这条差异）。但 springdoc 对 `$ref` 类型的属性**不保留
`nullable`** ⇒ 契约里会变成「这个对象一定在」⇒ 前端按可空处理就是错的。
⇒ 处理记录的字段一律**摊平成可空的简单类型**（`claimedBy` / `categorySetBy` / `categorySetAt`），
分类 / 责任人 / 结论等其余字段已经在 `ReconciliationDiffRow` 上。
（本仓既有契约里**没有** nullable 复杂 `$ref` 的先例 —— 这不是风格问题，是表达不了。）

### 10. api-proxy：新增**一条独立**元素，首 token `reconciliation-differences`

插入锚点 = **12.3.2.1 对账任务那条之后**（12.3.2.2 是它的直接下游，挨着放最说得清）。
既有条目**一字未改、也不重排**。

发车前用 `.tmp/check-allowlist.mjs` 把本文件既有 **228** 条 services[] 正则**逐条抽出**实测：
本模块 **11 个正向 shape + 7 个负向 shape 对既有正则全部零命中**。
两个首 token 在 `reconciliation-` 之后分岔（`tasks` vs `differences`），互不为前缀。

⚠️ `adjustments` 必须排在 `adjust` **之前**（`adjust` 是它的前缀；虽然 JS 正则会回溯不出错，
但显式排序免得后人看不出意图）。

## 文件清单

**迁移**（新建 3 张表，零 ALTER 零 DROP 零 ADD COLUMN 到别人的表）

- `backend/services/finance/src/main/resources/db/migration/V374__reconciliation_difference_handling.sql`
  - `finance_reconciliation_handling`（处理记录：分类 + 责任人 + 闭环状态；`UNIQUE(tenant_id, difference_id)` ⇒ 一条差异至多一条处理记录）
  - `finance_reconciliation_adjustment`（调整分录，append-only）
  - `finance_reconciliation_handling_event`（处理时间线，append-only）
  - 三表 tenant_id NOT NULL + ENABLE **与 FORCE** RLS isolation + GRANT；处理记录**不可删**，分录与时间线只给 SELECT + INSERT

**后端**

- `backend/services/finance/src/main/java/com/starcon/finance/ReconciliationDifferenceModels.java`（DTO + 受控词表）
- `backend/services/finance/src/main/java/com/starcon/finance/ReconciliationDifferenceService.java`（jOOQ 显式 SQL）
- `backend/services/finance/src/main/java/com/starcon/finance/ReconciliationDifferenceController.java`（11 个端点）
- `backend/services/finance/src/test/java/com/starcon/finance/ReconciliationDifferenceIntegrationTest.java`（29 例）
- `backend/services/finance/src/test/java/com/starcon/finance/ReconciliationDifferenceTenancyIntegrationTest.java`（11 例）

**契约**（codegen 产物，未手改）

- `packages/api-contracts/src/contracts/finance.ts`（新增 26 个 `ReconciliationDiff*` DTO，**22 行新增 / 0 行删除**）
- `packages/api-contracts/src/finance.openapi.json`（快照）

**前端**

- `apps/web/src/components/finance/reconciliation-difference-model.ts`（模型 + 展示口径）
- `apps/web/src/components/finance/reconciliation-difference-model.test.ts`（17 例）
- `apps/web/src/components/finance/reconciliation-difference-workspace.tsx`（真实工作区）
- `apps/web/src/app/finance/reconciliation-differences/page.tsx`（路由页）
- `apps/web/src/app/api/finance/reconciliation-differences-allowlist.test.ts`（6 例）
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 services[] 元素**，既有条目一字未改）

**台账**

- `docs/product/capability-blueprint/module-progress.md`（**只改 12.3.2.2 那一行的「状态」「证据」两格**）

## 验证（真实输出）

- **L0 编译闸**：`cd backend && ./mvnw -q compile` ⇒ **EXIT=0**。
- **L1 模块闸**：`./mvnw -pl services/finance -am test -Dtest='ReconciliationDifference*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  ⇒ `Tests run: 40, Failures: 0, Errors: 0, Skipped: 0` **BUILD SUCCESS**
  （ReconciliationDifferenceIntegrationTest 29 + ReconciliationDifferenceTenancyIntegrationTest 11）。
- **前端本车道**：`pnpm vitest run <本模块两个测试文件>` ⇒ **2 文件 / 23 例全绿**（模型 17 + 白名单 6）。
- **全量 allowlist 回归**（改了共享白名单 api-proxy.ts，必须跑全量）：
  `pnpm vitest run apps/web/src/app/api/finance` ⇒ **30 文件 / 154 例全绿**；
  `pnpm vitest run apps/web/src/app/api` ⇒ **246 文件 / 1389 例全绿**。
- **类型**：`pnpm typecheck` ⇒ **3/3 successful**（vitest 不做类型检查，另跑）。
- **Lint**：`pnpm lint` ⇒ **3/3 successful**（本车道零告警；告警都在别的模块的既有文件上）。
- **契约**：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`
  ⇒ `Generated 6893 DTOs in 7 per-service files`；`git diff --numstat packages/api-contracts/`
  ⇒ `finance.ts 22 / 0`（**纯增量**），**其余六服务契约零变化**。

### ⚠️ 实测抓到的真 bug（都已修，且都有测试钉住）

1. **`GRANT ALL ON flyway_schema_history TO finance_app`** —— 我第一版迁移写反了（其余 16 个 finance
   迁移一律是 `REVOKE ALL ... FROM finance_app`）⇒ 改成 REVOKE。
2. **可空复杂 `$ref` 在契约里表达不了** ⇒ springdoc 对 `$ref` 属性丢掉 `nullable`
   ⇒ 详情 DTO 改成摊平成可空的简单类型（见决策 9）。
3. **jOOQ 把 `{}` 当模板表达式** —— 测试里直接写 `detail` 列的 `{}` 字面量会被 jOOQ 解析成空块
   ⇒ 报成「bad SQL grammar」而不是 RLS 拒绝 ⇒ 改成不写该列（用列默认值）。
4. **append-only 的两道保险顺序** —— 运行角色 UPDATE 调整分录 / 时间线时**权限检查先于触发器**
   （报「permission denied」而不是「不可改写」）⇒ 测试改成**两道都断言**：
   运行角色被权限挡住 **且** 表属主直连写也被触发器挡住。
5. **写入必须先推上游、再写处理记录** —— 反过来会被「同相」触发器拒（这是设计，不是 bug，
   但必须写进代码注释，否则下一个人会写反）。
6. **`Timestamp.valueOf(LocalDateTime)` 的坑没有出现，但仍然断言了写回的值** ——
   测试断言 `claimed_at` 的 **epoch second** 与接口返回值**逐位相等**且在当前时刻 5 分钟内，
   而不是只断言「没抛异常」。

## 后续待办 / 留给别人的动作

1. **12.5.2.1 凭证**（下游未交付）：本模块**只登记**调整分录（凭证号或说明），**不生成凭证**。
   12.5.2.1 落地后回填 `voucherNo` 即可，本模块的表结构不用改。
2. **1.3.2.1 预警规则**（下游未交付）：本模块**不推预警、不写预警规则** ——
   「某类差异反复出现」由分类分布暴露给 1.3 取数。
3. **12.3.2.3 自动匹配规则**（未交付）：分类分布本该喂给规则调优，那条车道还没做。
4. **identity 用户目录**（未接线）：本仓没有用户目录服务，处理记录**只存责任人 uuid**。
   用户目录交付后可以把 `assignee_name` 补成快照列（**追加列，不改既有行**）。
5. **导航入口**：本页**刻意没有**在 `packages/ui/src/lib/task-pages.ts` 登记 ——
   那个文件是多条车道并发修改的共享文件，留给调度者统一处理。
6. ⚠️ **12.3.2.1 页面里有一句「差异处理属 12.3.2.2（未交付）」已经过时** ——
   那属于**别的模块的文件**，本车道按纪律**未改动**，留给 12.3.2.1 的属主或调度者处理。
7. **台账汇总数字**：本车道**只改了自己那一行**（`node tools/module-progress/sync.mjs` 会连带重写
   总览 / 分阶段 / 分系统三张自动生成的小计表，而它们在基线里**已经因为别的车道改了行而漂移**）。
   按纪律本车道把那部分**回退**了 ⇒ 小计表由调度者在批次末尾统一 `sync.mjs`。
