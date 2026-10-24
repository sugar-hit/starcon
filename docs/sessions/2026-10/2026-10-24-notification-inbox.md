# 1.3.1.1 通知列表（卷 01 · L3 1.3.1 消息与预警的第一格）

- **日期**：2026-10-24
- **车道**：`feat/1-3-1-1`（worktree `integration/.worktrees/1.3.1.1`）
- **阶段**：D7 · 卷 01「工作台与门户」L3 1.3.1
- **形态**：**前端聚合层（通知内容）+ identity 端点（个人状态）**

---

## 决策摘要

### 1. 落法：内容 = 各域只读投影；个人状态 = identity

蓝图 1.3.1.1 写死「**通知是投影，允许丢弃后重算**，不作为业务事实来源」，
卷 01 卷首写死「后端归属：前端聚合层 + 各服务的只读投影（**不新增业务服务**）」，
而集成头里**没有** `support` 服务。

⇒ **不新建 `support` 微服务，也不建「通知内容表」**。本模块的落法是：

| 部分 | 落在哪 | 为什么 |
| --- | --- | --- |
| 通知**内容**（缺货 / 超时 / 同步失败 / 对账差异 / 站内信） | 各域**已交付**的授权只读端点，前端聚合 | 各域在不同库里，跨库 SQL/FK/视图禁止（AGENTS.md 模块边界纪律）；通知只带**引用 + 跳转链接**，不复制业务数据 |
| 通知**个人状态**（已读 / 按类型订阅） | identity 服务 + 两张新表 | 已读是**个人**的（按 `membership_id`，不是按租户共享一份）；各业务服务都不拥有「个人已读状态」这个概念 |

与兄弟模块 **1.1.1.1 我的待办**（同卷、同为聚合层，已交付）同一套形：
纯前端聚合 + 个人状态落库，不新建业务服务。

### 2. 迁移：占 V373（预分配号），**不复用 `user_preference`**

调度者预分配了 **V373**（历史废弃车道 1.5.1.2 曾登记过同号但从未落地迁移，故可用）。
本模块**确实需要**迁移，理由三条：

1. **形状不匹配**：已读集合条数**无上界**，而 `user_preference` 的 JSONB 载荷上限是
   16 KB / 单数组 64 项（`UserPreferencePayloadRules`）⇒ 读上几百条通知就会撞顶，
   到那时已读状态**静默写不进去**（界面表现为「读了就还是未读」）。
2. **语义不匹配**：`user_preference` 的写入是**整份覆盖**（upsert 同一个 page_key），
   而「批量已读」是**增量**（只把点名的那几条标成已读）⇒ 整份覆盖要先读后写，
   两个标签页同时操作会互相把对方的已读状态冲掉。
3. **键受控**：已读状态必须按「受控类型词表 + 上游主键」入库，JSONB 载荷做不到在
   **数据层**钉住类型词表（CHECK 约束）。

⇒ V373 建**两张表**（都归本车道，都不碰 `user_preference`）：

- `notification_read_state` —— 已读状态（一通知一成员一行）
- `notification_type_subscription` —— 按类型订阅

`user_preference` 留给兄弟模块 **1.3.1.2 通知偏好**（接收渠道 / 免打扰时段，
蓝图标明它「共享数据：user_preference」）—— 那是**一份偏好**（整份覆盖正合适），
与本模块的**逐条已读状态**是两件事。

### 3. 关键 trade-off：订阅集合不允许为空

`notification_type_subscription` 的行 = 用户的订阅集合。
「没自定义过」= 表里没有我的行 ⇒ **生效值 = 全部类型**（与「明确订阅了全部」行为一致，
不需要额外的标记位）。

但这样一来「取消勾选最后一项」会让集合为空，而空集合与「没行」无法区分 ⇒
**服务端拒绝空集合（400）**，页面把最后一类的复选框**禁用**并说明
「至少要订阅一类 —— 最后一类不能取消，取消后收件箱就空了」。

备选方案（存一份「默认订阅全部」的标记位）要多一列 + 多一套语义，收益只是允许
「订阅零类」这个无用状态 ⇒ 不取。

### 4. 关键 trade-off：已读状态查用 POST 而不是 GET

「我读过哪些」有两种问法：把**全部**已读记录拉回来（载荷随时间无上界），
或者把**手上这批通知的键**拿去问。

⇒ 取后者：`POST /identity/notification-inbox/read-state`，请求体带 `{ keys }`。
**用 POST 是因为键集合可能几百条，GET 的 URL 扛不住这个长度**（几十个键就会顶到
代理的 URL 上限）。这是一次**读**操作，但被读的是请求体，不在 URL 上。

批量标记同样只对**点名**的键生效 ⇒ 后端**没有**「把本租户全部通知标已读」的端点，
页面的「把当前清单标为已读」把**当前筛选出的集合**的键显式传下去。

### 5. 严重级别：受控词表 + 各来源按各自判据推

- **词表**：前端 `Severity` 是严格联合类型（`CRITICAL/HIGH/MEDIUM/LOW/UNKNOWN`），
  DB 侧 `notification_type` 由 CHECK 约束钉死五个码（与蓝图点名的五个上游一一对应）。
- **不统一硬编码阈值重算业务口径**：warehouse 给 `severity` ⇒ 只做**词表对齐**
  （`WARNING` → `HIGH`）；trade 按**超期时长**；channel 按**失败原因**
  （解析失败/映射缺失要人改规则 ⇒ `HIGH`，限流/网络错误 ⇒ `MEDIUM`）；
  finance 按**未结差异条数**；crm 按 `priority`。
- **判不了 ≠ 没有**：推不出严重度时落在 `UNKNOWN`（「判不了」），页面上如实显示，
  **绝不用 `LOW` 兜底** —— 用 `LOW` 兜底会让它们排到最后然后永远没人处理。
  时刻缺失同理（「时刻判不了」而不是「刚刚」）。

### 6. 未读计数与清单同源

概览的未读数由**清单渲染的那一批行**算出（`summarizeNotifications(visible)`），
**不另查 count 端点** —— 另查就会与清单口径漂移（清单改了、角标没改）。
按类型筛选条上的计数来自**订阅后**的收件箱集合，与概览分开标注
（「当前清单 N 条 · 订阅内共 M 条」），不混成两个口径。

### 7. 「读不到 / 无权限 / 没数据」严格分开

- **读不到**（服务报错）→ 逐类单列并报出原因；
- **无权限**（会话里没有该来源的读权限）→ 单独标「对你不开放」，**不去读**那个来源
  （读了必然 403，而且会把「无权限」说成「服务挂了」）；
- **没数据** → 才是 0 条。

三件事在页面上是三种不同的呈现，任何一种都不显示成别的。

---

## 关键 trade-off 汇总

| 决策 | 取舍 |
| --- | --- |
| 内容走前端聚合 vs 后端聚合服务 | 卷 01 明写「不新增业务服务」+ 无 `support` 服务 ⇒ 前端聚合；代价是浏览器要发 5 个只读请求（与 1.1.1.1 同款） |
| 新建两张表 vs 复用 `user_preference` | 复用会撞 16 KB / 64 项上限 + 整份覆盖语义 ⇒ 新建；代价是多一张表要维护 RLS 与 GRANT |
| 已读查用 POST | GET 的 URL 装不下几百个键；代价是「读」用了 POST（已在注释与能力声明里写明） |
| 空订阅集合被拒 | 消除「空集合 vs 没行」的歧义；代价是「订阅零类」这个状态不存在 |
| 通知键 = `<类型>:<上游主键>` | 键是前端与 identity 之间唯一的接口；主键段允许一段冒号后缀（`<factId>:<overdueKind>`），类型段由 CHECK 约束钉死 |
| 严重度按来源各自推 | 不越界重算业务口径；代价是五种来源有五套映射，页面要能显示「判不了」 |

---

## 文件清单

### 迁移

- `backend/services/identity/src/main/resources/db/migration/V373__notification_inbox.sql`
  （两张表 + RLS + GRANT + 索引；含「为什么落在这里 / 为什么不复用 user_preference /
  为什么按成员 / 为什么 FORCE / 类型词表」六节论证）

### 后端（identity 服务）

- `backend/services/identity/src/main/java/com/starcon/identity/NotificationInboxModels.java`
- `backend/services/identity/src/main/java/com/starcon/identity/NotificationInboxRules.java`
  （纯函数：受控词表 + 键解析 + 批量上限 500）
- `backend/services/identity/src/main/java/com/starcon/identity/NotificationInboxService.java`
  （jOOQ 显式 SQL；`?::timestamptz` + `OffsetDateTime`，不用 `Timestamp.valueOf`）
- `backend/services/identity/src/main/java/com/starcon/identity/NotificationInboxController.java`

### 后端测试

- `backend/services/identity/src/test/java/com/starcon/identity/NotificationInboxIntegrationTest.java`（12 例）
- `backend/services/identity/src/test/java/com/starcon/identity/NotificationInboxTenancyIntegrationTest.java`（4 例）

### 前端

- `apps/web/src/app/notifications/page.tsx`
- `apps/web/src/components/notifications/notification-inbox-model.ts`
- `apps/web/src/components/notifications/notification-inbox-workspace.tsx`
- `apps/web/src/components/notifications/notification-inbox-model.test.ts`（22 例）
- `apps/web/src/app/api/notification-inbox-allowlist.test.ts`（6 例）

### 共享文件（多车道共用，按约定各自改）

- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立的 `prefix: 'identity/'` 元素**
  （首 token `notification-inbox`；锚点 = `17.5.2.1 数据字典` 那条元素**之后**）。
  既有条目**一字未改、也不重排**。
  零命中核对用 `.tmp/zero-hit-check.mjs` 实测：既有 224 条正则里含 `notification` 串的
  只有 2 条（`price-anomaly-alerts/notifications`、`logistics-pickup-signoff/notifications`），
  首 token 与本模块不同 ⇒ 互不吃掉；本模块 5 个形状对 224 条正则**逐一零命中**，
  既有 31 条 `prefix: 'identity/'` 正则对本模块形状也**全部零命中**。
- `packages/api-contracts/src/contracts/identity.ts` + `packages/api-contracts/src/identity.openapi.json`
  —— codegen 产物，**未手改**；其余 6 个服务的契约文件**一字未动**。
- `docs/product/capability-blueprint/module-progress.md` —— 只改 1.3.1.1 **那一行**的
  「状态」「证据」两格，未重排整表。

---

## 验证

```
L0 编译闸
  cd backend && ./mvnw -q compile                      → 绿（无错误输出）
  pnpm typecheck                                        → 绿（3 packages 全过）

L1 模块闸（只跑本模块新增的测试类）
  cd backend && ./mvnw -pl services/identity -am test \
      -Dtest='NotificationInbox*' \
      -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
                                                       → Tests run: 16, Failures: 0, Errors: 0

前端
  pnpm vitest run apps/web/src/components/notifications/   → 22 passed
  pnpm vitest run apps/web/src/app/api                     → 241 files / 1374 tests passed
                                                      （改了 api-proxy.ts ⇒ 跑全量 allowlist 回归）
  pnpm --filter @starcon/web lint                          → 无 error（仅其它模块的既有 warning）

契约
  ./mvnw test -Dtest='OpenApiContractTest' …                → 7 个服务的 spec 全部导出
  python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi
                                                        → Generated 6824 DTOs in 7 per-service files
```

PG 容器：本车道 L1 只用到 identity 的 **1 个** Testcontainers PostgreSQL 16 容器
（`IdentityIntegrationTestBase` 的 static 容器，连接池压到 1）；
OpenAPI 契约导出**不启容器**（`OpenApiContractTest` 把 DataSource/Flyway/jOOQ 全部 exclude 掉了）。
容器由 Testcontainers 的 Ryuk 在 JVM 退出时回收。

---

## 后续待办

- **1.3.1.2 通知偏好**（未交付）：接收渠道 / 免打扰时段 ⇒ 落 `user_preference`
  （蓝图标明「共享数据：user_preference」），并保留「关键业务通知不可完全关闭」的底线项。
  本模块**刻意没有**占用 `user_preference`，两者不冲突。
- **1.3.2.1 预警规则**（未交付）：规则命中 → 生成通知；规则引擎本体在 18 系统。
  本模块的 `/capabilities` 已把「未交付」如实报出，前端不会假装有。
- **1.3.2.2 外部渠道推送**（未交付）：凭据只存 secret manager 引用，不落库。
- **`/notifications` 未进导航**：`packages/ui` 的 nav-config / task-pages 是多车道共用的
  真相源，改它会与并发车道冲突（兄弟模块 1.1.1.1 的 `/dashboard/todo/my-todos`
  同样未进导航）。若后续要进侧栏，由导航那条线统一加，本车道不碰。
- **上游可读性**：若某个来源将来被下线，`/capabilities` 的 `boundaries` 需要同步更新。
