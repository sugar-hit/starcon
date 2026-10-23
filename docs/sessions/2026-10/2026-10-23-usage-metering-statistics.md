# 17.7.1.2 用量统计（阶段 D7 · L3 锚点 17.7.1）

- **日期**：2026-10-23（Asia/Shanghai）
- **车道**：`feat/17-7-1-2`（worktree `/Users/guan/Projects/starcon-worktrees/integration/.worktrees/17.7.1.2`）
- **基线**：`integration/module-dev` @ `b388fa76`
- **迁移版本号**：`V325__usage_metering_statistics.sql`（预分配，跳号正常）

## 决策摘要

蓝图原文（`docs/product/capability-blueprint/17-org-permission-and-settings.md` 第 278-283 行）：

> ### 17.7.1.2 用量统计
>
> - **职责**：统计各租户资源用量（店铺、SKU、月订单、存储、API、AI）用于计费与容量规划，口径须与套餐定义严格一致。
> - **输入**：各系统用量埋点；统计周期。
> - **输出**：用量报表 + 趋势 + 计费依据。
> - **关系**：上游 = 全部系统埋点；下游 = 17.7.1.1 计费。**口径不一致会直接引发计费争议**。

本车道落地要点：

1. **本模块是 17.7.1.1 之上的「统计层」，不是另一个登记口。**
   上游已有 `POST /identity/saas-plans/usage`（登记用量）与 `GET /identity/saas-plans/entitlement-check`（超限判定），
   本模块**没有**同义端点。缺的那一块是：`saas_entitlement_usage` 只存「当前用量」（每个维度一行 upsert），
   **没有历史**，撑不起趋势，也撑不起「这个周期用了多少」这种按周期切分的报表。
   ⇒ 新增「**归集**」：把当前用量物化成**按周期分桶的统计事实**（`saas_usage_statistic`），
   报表 / 趋势 / 计费依据 / 容量规划全部只读这一份事实。
2. **口径与套餐定义严格一致，落成三条可检查的规则。**
   - 六个维度码与四个周期码**沿用 17.7.1.1 已定义的那一套**（DB CHECK 钉死，前端词表也直接引用
     `saas-plan-model` 的 `ENTITLEMENT_KIND_LABELS` / `PERIOD_LABELS`，不另抄一份）。
   - **流量口径**维度（套餐定义 `period ∈ DAY/MONTH/YEAR`）的桶粒度**固定**为套餐定义的周期：
     来源系统只提供该粒度的值，把月值切进日桶就是计费争议的起点。
     **存量口径**维度（`period = TOTAL`）的值是采集时刻的快照，桶粒度跟随**统计周期**——
     于是存量维度也有趋势。
   - **上限实时读 `saas_plan_entitlement`**，统计事实表里**不存上限快照**：上限改了，历史报表必须按新上限重算。
3. **没接通的不假装接通。**
   来源系统没登记的维度，统计事实里**根本没有这一行**（不是一行 0）；
   计费依据用 `LEFT JOIN LATERAL` 把这个维度**保留下来**，结论是 `UNDETERMINED`（判定不了），
   而不是「用量 0」也不是「没超限」。覆盖视图按维度报 `WIRED` / `PARTIAL` / `NOT_WIRED` / `NO_SUBSCRIPTION`，
   「谁真的接通了」是从 `saas_entitlement_usage` 的真实登记**推出来的**，不是本模块自造的清单。
4. **不落任何金额。** 没有发票 / 应收 / 付款 / 结算 / 收入确认；用量与上限都是数量（`NUMERIC(19,6)`）。
   测试用 `information_schema` 断言 `saas_usage_*` 表无金额语义列。
5. **跨租户读只有一条路径。** 6 条 SECURITY DEFINER 函数（`scope` / `report` / `billing` / `capacity` /
   `coverage` / `collections`），每条内部**自己再判一次闸门**（`saas_platform_gate`），
   应用层漏判不会让数据漏出去。写动作（归集）**逐租户单独成事务**（ADR-0005）。
6. **权限码沿用 `identity.subscription.read` / `identity.subscription.update`。**
   另起 `identity.usage.*` 的话，`saas_platform_register_role()` 不会把新码授给平台运营角色
   （它只授 `identity.plan.%` 与 `identity.subscription.%`），新码会变成「登记了也拿不到」的死码；
   同时**不去 `CREATE OR REPLACE` 上游的函数**——多车道并行时两条车道改同一个函数定义，
   后应用的那条会把前一条的授权分支覆盖掉。
7. **蓝图 17.7.1.2 的维度清单与 17.7.1.1 的套餐定义对不上：蓝图有「存储」，套餐给的是「用户数」。**
   本模块**不另起 `STORAGE` 维度码**（另起会让统计口径与套餐定义分叉，正是蓝图警告的争议来源），
   而是把该缺口在能力声明里如实报出来（`available=false`，`ownerModule = 17.7.1.1`）。
8. **平台运营三道闸门照抄 17.7.1.1**（本租户有 `saas_platform_role` 登记 / 登记的 roleId 正是会话持有的角色且
   `scope_mode='ALL'` / 该角色带有所需权限码），权限码**刻意不授 OWNER**。

## 关键 trade-off

| 决策点                                                            | 取舍                                             | 理由                                                                                                                                                                     |
| ----------------------------------------------------------------- | ------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 登记用量 vs 只读上游 + 新增「归集」                               | **只读上游 + 归集**                              | `saas_entitlement_usage` 是上游的事实表，写入所有者只有一个（AGENTS.md「每张事实表只有一个写入所有者」）。归集把「当前值」物化成周期桶，正好补上历史这��块                     |
| 周期桶粒度：跟随统计周期 vs **跟随套餐定义的周期**                 | **流量维度跟随套餐定义，存量维度跟随统计周期**  | 来源只给月值，切进日桶是争议起点；存量是快照，可以按任意粒度留痕，于是存量维度也有趋势                                                                                   |
| 上限：存进统计事实 vs **实时读套餐定义**                           | **实时读**                                       | 存快照上限 ⇒ 改一次上限，历史上所有报表都算错                                                                                                                             |
| 没接通的维度：留一行 0 vs **不建行 + 计费依据里 UNDETERMINED**   | **不建行 + UNDETERMINED**                       | 「量到了正好是 0」与「没量到」在报表上是两件完全不同的事。计费依据用 `LEFT JOIN LATERAL` 保住这一行，结论写「判定不了」                                                        |
| 归集目标：一次跨全部租户写 vs **逐租户单独成事务**                 | **逐租户单独成事务**                             | ADR-0005「每次业务事务选定一个明确目标租户」。批量入口只是**逐个租户**跑各自的事务，不是一个跨租户事务                                                                     |
| 归集触发：后台定时 vs **平台运营显式触发**                         | **平台运营显式触发**                             | 与上游 `POST /saas-plans/subscriptions/expire-due` 同一套形状；跨租户读出口都要会话 hash，后台任务没有会话                                                                   |
| 跨租户读：裸查询 vs **SECURITY DEFINER 函数**                     | **函数**                                         | 裸查询会拆掉「每次业务事务选定一个明确目标租户」这条纪律；函数内部自己再判闸门，应用层漏判也不漏数据                                                                       |
| 权限码：新建 `identity.usage.*` vs **沿用 `identity.subscription.*`** | **沿用**                                         | 新码不会被 `saas_platform_register_role()` 授出去 ⇒ 变成死码；且多车道并行时不该去 `CREATE OR REPLACE` 上游函数                                                               |
| 「存储」维度：另起 `STORAGE` 码 vs **如实报缺口**                  | **如实报缺口**                                   | 另起码会让统计口径与套餐定义分叉；蓝图自己就说「口径不一致会直接引发计费争议」                                                                                             |
| 前端文件位置：新建 `components/usage/` vs **`components/iam/`**     | **`components/iam/`**                            | 仓内既有惯例（`saas-plan-*` / `platform-flag-*` / `dictionary-*` 都在那）；另起目录会与既有导航、命名、测试摆放错位                                                             |
| 维度文案：另抄一份标签表 vs **引用 `saas-plan-model` 的词表**      | **引用**                                         | 那套词表就是套餐定义的一部分；另抄一份迟早漂移，而口径漂移正是计费争议的起点                                                                                             |

## 文件清单

### 后端

- `backend/services/identity/src/main/resources/db/migration/V325__usage_metering_statistics.sql`
  —— `saas_usage_statistic`（租户级，周期用量聚合）、`saas_usage_collection`（租户级，归集批次留痕，追加不可改）、
  RLS（ENABLE + FORCE + isolation）、GRANT（统计事实不给 DELETE）、6 条 SECURITY DEFINER 跨租户读函数。
- `backend/services/identity/src/main/java/com/starcon/identity/SaasUsageController.java`
  —— 9 个端点：GET `capabilities` / `options` / `report` / `trend` / `billing-basis` / `capacity` / `coverage` /
  `collections`，POST `collections`（归集）。
- `backend/services/identity/src/test/java/com/starcon/identity/SaasUsageIntegrationTest.java`
  —— 14 例，实跑 Testcontainers PostgreSQL 16；含多租户三断言与共享状态哨兵。

### 测试隔离（17.7.1.1 回归的同款缺陷，本车道不重犯）

平台运营角色登记（V323 的 `saas_platform_register_role`）会把 PLATFORM_OPS **永久挂到调用者本人**身上。
挂在**共享的 local-admin** 上 = 污染同一次 maven 运行里的所有 identity 测试类：
共享 owner 的角色从 1 个变成 2 个，而 `/auth/session` 的 grants 是**无序聚合**
（V47 的 `session_access` 用 `jsonb_agg` 且没有 `ORDER BY`），别的测试类再靠 `grants.get(0)` 猜角色就会踩空
——2026-10 的真实回归：`IdentityIntegrationTest` 因此把 PLATFORM_OPS 当成 OWNER 去删，拿到 200 而不是 403。

⇒ 本车道的做法：
1. **平台运营身份用专属子租户所有者**（真实走 `POST /identity/children`），**不挂共享 owner**。
   闸门是**按租户**判的（`saas_platform_gate` 只要求 `saas_platform_role.tenant_id = 会话所在租户`，
   没有「必须是根租户」的要求），所以子租户所有者可以在自己租户里登记 PLATFORM_OPS 并通过闸门。
2. **`@AfterEach` 哨兵**：`/auth/session` 的 grants 长度 == 1 且 `roleCode == "OWNER"`；
   数据库层 `select count(*) from role_assignment where membership_id = <共享 owner 的 membershipId>` == 1。
   放在 `@AfterEach` 而不是某个用例里：污染当场红，而不是等到别的测试类炸出来。

## 验证

- **L0**：`./mvnw -q -pl services/identity -am compile` 通过；`pnpm typecheck` 3 个包全绿。
- **L1（整个 identity 套件，连跑两次，证明确定性）**：
  ```
  [INFO] Tests run: 315, Failures: 0, Errors: 0, Skipped: 0
  [INFO] BUILD SUCCESS
  [INFO] Tests run: 315, Failures: 0, Errors: 0, Skipped: 0
  [INFO] BUILD SUCCESS
  ```
  其中本模块 `SaasUsageIntegrationTest` 14 例全过，`IdentityIntegrationTest` 17 例全过
  （共享 owner 哨兵在两个测试类里都是绿的 ⇒ 两条车道都不再污染共享状态）。
- **前端**：`usage-stat-model.test.ts` 19 例 + `usage-allowlist.test.ts` 5 例全过；
  改共享 `api-proxy.ts` 后跑了**全量 allowlist 回归** `pnpm vitest run apps/web/src/app/api/trade`
  ⇒ 68 文件 379 例全过。
- **首 token 零命中核对**：既有 182 条 `services[]` 正则对本模块 8 个 shape **零命中**（脚本实测，
  脚本落在 `.tmp/allowlist-zero-hit.mjs`）；反向核对本模块正则不误伤既有 identity/ 形状。


### 前端

- `apps/web/src/app/org/usage/page.tsx` —— 页面入口。
- `apps/web/src/components/iam/usage-stat-model.ts` —— 展示口径与前端预校验（纯函数）。
- `apps/web/src/components/iam/usage-stat-workspace.tsx` —— 工作区（四态各不相同）。
- `apps/web/src/components/iam/usage-stat-model.test.ts` —— 模型单测。
- `apps/web/src/app/api/iam/[...path]/usage-allowlist.test.ts` —— 白名单双向断言（真跑路由处理器）。
- `apps/web/src/lib/api-proxy.ts` —— **只新增一条独立 `services[]` 元素**（`prefix: 'identity/'`），
  锚点固定在 `// 2.4.2.3 映射缺失队列（channel 服务）` 之前；既有条目一字未改。

### 契约（codegen 产物，只读）

- `packages/api-contracts/src/contracts/identity.ts`、`packages/api-contracts/src/identity.openapi.json`
  —— `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 生成。

### 台账

- `docs/product/capability-blueprint/module-progress.md` —— 只改 17.7.1.2 那一行的「状态」「证据」两格。

## 后续待办

1. **来源系统埋点接入（本模块之外）**：catalog / channel / trade 各自把用量经
   `POST /identity/saas-plans/usage` 登记，登记后本模块的归集才能量到。
   在那之前，相关维度在覆盖视图里是 `NOT_WIRED`，在计费依据里是 `UNDETERMINED`。
2. **存储维度**：需 17.7.1.1 的套餐定义补一个存储维度（`saas_plan_entitlement`），否则存储用量没有可比对的权益上限。
3. **定时归集**：目前是平台运营显式触发（与上游 `expire-due` 同一形状）；接入后台 worker 需要会话之外的闸门机制，
   留给后续批次。
4. **迁移到 `apps/admin`**：蓝图里的 `/platform` 前端入口；`apps/admin` 目前仍只有空脚手架。
5. **上游 `capabilities` 的「权益用量自动计量」维度**：本车道**未改动**它（仍是 `available=false` / `ownerModule = 17.7.1.2`）——
   跨系统埋点尚未接通（见待办 1），把它翻成 `available=true` 会让下游据此放行。
   本模块的 `GET /identity/saas-usage/capabilities` 如实报了这条缺口。
