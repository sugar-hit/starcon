# 9.2.1.1 待审队列（trade 服务）

> 会话：2026-10-20 · 车道 `feat/9-2-1-1`（worktree `.worktrees/9.2.1.1`）· 集成头基线 `03785467`

## 决策摘要

按蓝图 09 卷 9.2.1.1 交付「待审队列」，归属 **trade** 服务，
迁移 `V209__trade_order_pending_review.sql`（预分配版本号，**零 ALTER 既有表**）。

蓝图职责句是三件事：**展示待审订单**、**按风险与时效排序**、**支持批量通过/驳回/挂起**，
外加「显示每单命中的规则与风险信号」。落成：

| 蓝图项 | 落点 |
| --- | --- |
| 展示待审订单 | 队列 = `sales_order.status='SUBMITTED'` 且无终态结论的订单（不复制队列表） |
| 按风险与时效排序 | DB 层 `ORDER BY`：风险分 → 时效分 → 提交时刻 → 订单 id |
| 批量通过/驳回/挂起 | `POST /trade/order-pending-review/batches`（幂等键 + 规则） |
| 每单命中的规则 | 列表给规则编号数组；详情给逐条件到订单字段与实际值的完整命中链 |
| 风险信号 | 引用 9.2.2.2 的 `RiskComponent`（恒五行，缺信号那行带着「为什么缺」） |

上游实查结论（依赖自检通过）：`sales_order` 簇（V99）、`order_review_rule*`（V149，9.2.1.2）、
`order_risk_*`（V150，9.2.2.2）**都已在集成头**。9.2.2.1 地址与支付校验本批由并行车道交付，
本模块**不消费**它的结论，也不在 capabilities 里断言它的交付状态。

### 加粗句「批量通过必须基于明确的规则，而不是默认全过」的四层落点

1. **服务层**：action=APPROVE 必须带 `ruleCode + ruleVersion`，缺了就 400 —— **没有任何兜底路径**。
2. **服务层**：规则不存在 / 版本对不上 / 不在生效期 / 动作是转人工 / 强制转人工 —— 五种各自显式拒绝。
3. **逐单真判**：批量通过对每一单跑 9.2.1.2 的规则引擎，
   只有「判定结论 AUTO_APPROVE **且** 决定结论的规则就是指名的那条」才放行；
   判成转人工的、命中的是别的规则的，一律跳过并写清原因。
4. **DB 约束**：回执表与结论表上「动作=APPROVE ⇒ 必须指名规则 + 必须指向一次具体判定留痕」的 CHECK，
   绕过服务层直写也插不进去一条「没有依据的通过」。

## 关键 trade-off

- **队列成员不另造一张表。** 复制一张队列表再从订单同步过去，会立刻带来第二个真相 ——
  「订单说已提交、队列表说没进队」。而审单是发货前的最后闸门，漏单 = 漏审 = 货照发。
  代价是队列列表要做一次 `LEFT JOIN`（结论表）而不是读一张窄表 —— 这个代价远小于漏审。
- **刻意不改 `sales_order.status`。** V99 的 CHECK 只允许 `('DRAFT','SUBMITTED')`，
  且「这单审没审过」是**审单侧的结论**，不是订单单据的状态。
  塞进 `sales_order.status` 会让「单据状态」与「审单结论」两个维度挤进一个字段，
  履约侧（9.3）读到的就不是它以为的语义。审单结论落在 `order_review_queue_case`，
  一单一行、状态可推进（挂起 → 通过/驳回），历史由回执逐条承载。
- **时效分用 IMMUTABLE 函数而不是物化列。** 等候时长每时每刻都在变（等得越久越急），
  物化成列会立刻变陈旧，而「陈旧的时效分」比「没有时效分」更坏 —— 它看起来像个真分数。
  函数给定 `(submitted_at, at)` 结果确定 ⇒ 能进 WHERE 也能进 ORDER BY，
  Java 与前端都不重算（判定真身只有 DB 一处）。
- **未评估的风险分是 null，不是 0。** 0 分在风控语境里是「量过了，安全」，
  兜一个 0 会把没评估过的单排到最安全的那一档。排序时 null 排在有分之后。
- **通过必须「指名的那条规则」真的判出来。** 放宽成「任何一条自动通过规则判出来就行」也能满足加粗句，
  但那样「你选了规则 A、实际按规则 B 放行」就查不出来了。
  收紧的代价是：租户配了多条优先级不同的自动通过规则时，操作员可能反复试 ——
  换来的是每一单的结论都能说清「按哪条规则、哪一版」。
- **批量回执「两段写」而不是一次写完。** 幂等键必须**先占住**再干活：
  先干活后占键，两个并发同键请求会都跑一遍规则判定、都写一遍结论，
  最后其中一个撞唯一索引，那一份的副作用已经发生了。
  代价是回执表的统计数要在处理完成时回写一次 ⇒ 用**列级授权**把「能改的列」限死到那几格。
- **逐条结果刻意不建到 `sales_order` 的外键。** 逐条结果记录的是「**我们被要求处理的那个 id**」，
  它完全可能解析不到订单（跨租户、已删）。建了外键，这两种情况就写不进回执 ——
  而「这一单没找到 / 不属于本租户」恰恰是批量审单里最需要留痕的一条结果。
  订单是否存在由服务层在 RLS 下实查得出，不是靠外键兜。
- **前端路由取 `/orders/review/queue`，另给 `/orders/pending-review` 别名。**
  蓝图 09 的 L3 锚点是 `/orders/review`，两个兄弟模块分别在 `/orders/review/rules`、
  `/orders/review/intercept`，所以 `/orders/review/queue` 是规范路径；
  批次任务书里给的是 `/orders/pending-review`，故留了一个别名路由 ——
  两个 URL 渲染**同一个**工作区组件，不存在两套实现。

## 文件清单

**迁移（trade 服务）**

- `backend/services/trade/src/main/resources/db/migration/V209__trade_order_pending_review.sql`
  —— 三张表 + 两个 IMMUTABLE 函数 + RLS/FORCE + GRANT + 终态触发器 + append-only 触发器。

**后端（trade 服务）**

- `backend/services/trade/src/main/java/com/starcon/trade/OrderPendingReviewModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/OrderPendingReviewService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/OrderPendingReviewController.java`
- `backend/services/trade/src/test/java/com/starcon/trade/OrderPendingReviewIntegrationTest.java`（30 条）
- `backend/services/trade/src/test/java/com/starcon/trade/OrderPendingReviewTenancyIntegrationTest.java`（5 条）

**前端**

- `apps/web/src/app/orders/review/queue/page.tsx`（规范路由）
- `apps/web/src/app/orders/pending-review/page.tsx`（别名路由）
- `apps/web/src/components/orders/order-pending-review-model.ts`
- `apps/web/src/components/orders/order-pending-review-workspace.tsx`
- `apps/web/src/components/orders/order-pending-review-model.test.ts`（16 条）
- `apps/web/src/app/api/trade/order-pending-review-allowlist.test.ts`（5 条）
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**，插入锚点固定在
  9.2.1.3 `order-intercept` 那一条**之后**；既有 trade 分支的超长单行 alternation 一字未动。

**契约（codegen 产物，只由工具改）**

- `packages/api-contracts/src/trade.openapi.json`、`packages/api-contracts/src/contracts/trade.ts`
  —— `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 重新生成，
  **纯新增**（7 条路径 + 17 个 schema，无删除、无重排）。
  channel 侧的重生成只产生排版噪声（类型集合逐行相同），已 `git checkout` 还原 ——
  不去动并行车道（4.4.1.3 / 4.5.2.2）正在改的 channel 契约文件。

**台账**

- `docs/product/capability-blueprint/module-progress.md` —— 只改 9.2.1.1 那一行的「状态」与「证据」两格。

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `backend && ./mvnw -q compile` | exit 0 |
| L0 类型 | `pnpm typecheck` | 3 successful / 3 total |
| L0 lint | `pnpm lint` | 3 successful / 3 total（零新告警） |
| L1 后端 | `./mvnw -pl services/trade -am test -Dtest='OrderPendingReview*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **35 tests / 0 failures / 0 errors / 0 skipped** |
| L1 前端 | `pnpm vitest run apps/web/src/app/api`（改了 api-proxy ⇒ 必跑全量 allowlist 回归） | **95 files / 572 tests 全绿** |
| L1 前端 | `pnpm vitest run` 本车道两个新文件 | 16 tests 全绿 |

PG 容器：Testcontainers 起的 `postgres:16-alpine` 由测试类 `@AfterAll` 的 `pg.stop()` 回收；
结束后 `docker ps -a --filter label=org.testcontainers=true` 为空（**无残留**，ryuk 未介入即已清理）。

## 过程中被测试逼出来的四个真实缺陷（已修）

1. **绑定顺序**：jOOQ 按 **SQL 文本里问号的出现顺序**绑参。
   SELECT 列表里的三个 `?::timestamptz` 出现在 WHERE 之前 ⇒ 它们必须先绑。
   顺序错了 PG 会拿 uuid 去 cast 成 timestamptz，报 `cannot cast type uuid to timestamp with time zone`。
2. **lateral 子查询缺 `tenant_id`**：命中链的 `array_agg` 子查询里引用了 `e.tenant_id`，
   而 lateral 投影里没带这一列 ⇒ `column e.tenant_id does not exist`。
3. **幂等回放的 `replayed` 标记传反了**：回放路径传了 `false`，
   界面会显示「已处理」而不是「幂等回放，没有再次改状态」。
4. **回执逐条的外键挡住了最该留痕的那条结果**：跨租户/不存在的订单 id 写不进回执
   ⇒ 批量审单整批 409。改成不建外键（理由见上面的 trade-off）。

## 后续待办

- **下游 9.3 履约编排**读 `order_review_queue_case`（APPROVED 才是「可下发货」）——
  9.3.1.1 拆单 / 9.3.3.1 履约请求已交付，但**还没有任何一条去读审单结论**：
  那是 9.3 车道自己的事，本车道只产出结论。
- **9.2.2.1 地址与支付校验**本批由并行车道交付。落地后它的结论应作为**新增的审单规则维度**
  接进 9.2.1.2 的规则引擎（`order_review_rule_condition.dimension` 加一个枚举值），
  而不是在本模块里另写一套判断 —— 那会造出第二个判定真身。
- **18 自动化**（规则引擎复用）未交付。9.2.1.2 的判定引擎是纯函数式的
  （同一组事实 + 同一套规则 + 同一时刻 ⇒ 同一结论与同一条命中链），
  18 自动化复用时按 `order_id` 取事实、按 tenant 取生效规则、调同一条判定即可，
  不需要把本模块的表结构搬走。
