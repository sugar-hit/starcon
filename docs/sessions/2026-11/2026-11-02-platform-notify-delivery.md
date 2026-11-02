# 2026-11-02: 18.5.2.1 投递记录（通知投递结果 / 逐次尝试 / 退订 / 拦截 / 触达统计）

## 摘要

蓝图 18「集成开放与智能自动化」→ 18.5「通知模板」→ 18.5.2「投递记录」落地。

车道分支 `feat/18-5-2-1`，迁移版本号由调度者预分配为 **V439**
（`backend/services/platform/.../V439__platform_notify_delivery.sql` +
`backend/services/identity/.../V439__platform_notify_delivery_permission.sql`，
与 V419 / V420 / V427 同一「platform + identity 同号」的登记口径）。

蓝图原文：「**职责**：记录通知投递结果：模板、收件范围、渠道、状态、失败原因、重试；
统计触达率与退订情况。**输入**：投递记录；重试请求。**输出**：投递日志 + 统计。
**关系**：上游 = 18.5.1；下游 = 1.3.2.2 外部推送、15.5.2 触达。
**退订必须被尊重并生效**，反复触达退订用户是投诉与合规风险的主要来源。」

交付物：四张新表、10 个端点（`/platform/notifications`）、一个真实工作区页面
（`/notifications/deliveries`）、27 个后端集成测试 + 41 个前端测试。

## 🔴 这是一条**熔断后恢复**车道：设计稿是继承的，不是重做的

前两任 worker 合计约 8 小时**只产出了设计笔记、没落一个文件** ⇒ 按
`LANE_MAX_ATTEMPTS=2` 熔断。熔断时保留下来的设计定稿在
`.dev-agent/blocked-modules.md` 末尾「18.5.2.1 投递记录 —— 熔断时保留下来的设计稿」一节。

本轮**直接照那份稿子实现**，没有重新设计：归属 platform 服务、四张表
（`platform_notify_delivery_record` / `_attempt` / `_unsubscribe` / `_unsubscribe_block`）、
两个端点前缀（`/platform/notifications/deliveries` 与 `/platform/notifications/unsubscribes`）、
「退订先查后登记、命中拦截 ⇒ 进 block 表**并计入统计**」的关键语义。

恢复条件（任务书里写明的两条）本轮都满足：① **第一个 commit 就是迁移骨架**
（`1a95ed7f`，增量提交，没有「写完一次性提交」）；② **依赖面拆小** ——
只读 18.1.2.2 的 `platform_delivery_record` **表形状**、不读它的实现代码，
18.5.1.1 只当「已交付」的事实引用。

## 决策 / 结论

### 1. 🔴 命名空间：`platform_notify_*`，不是 `platform_delivery_*`

集成头里**已经有** 18.1.2.2 的 `platform_delivery_record` / `_attempt` / `_dead_letter` /
`_replay_task`（V413，**webhook** 投递事实表，路由 `platform/deliveries`）。
本模块是**通知**投递（模板 → 收件人）⇒ 必须走 `platform_notify_*`。

⇒ **只建 `platform_notify_*` 表，18.1.2.2 的表一个字不改、不抢它的路由**。
api-proxy 白名单也遵守同一条：新增的是**独立一条** `services[]` 元素
（首 token `notifications`），既有条目一字未改、不重排；守卫里**反验**
`platform/deliveries`（18.1.2.2）与 `platform/ai/templates`（18.5.1.1）仍然放行。

### 2. 🔴 蓝图加粗句「退订必须被尊重并生效」的四个落点

| 落点 | 做法 |
| --- | --- |
| 尊重（先查后登记） | 登记投递时**逐个收件人**先查退订表；命中退订的收件人**不进投递尝试**（没发出去的东西不能出现在投递日志里），改写一行 `platform_notify_unsubscribe_block` |
| 不许静默丢弃 | `blocked_count` 落在投递记录上 **并**进租户级统计 ⇒「因为退订少发了几百条」是**看得见**的数字 |
| 重试也要重查 | 第一次投递之后才退订的收件人，重试必须被拦下 —— 否则「退订必须被尊重并生效」就只在第一次成立 |
| 触达率口径 | 分母 = **实际投递**人数（不含被退订拦下的），分子 = 触达成功人数，分母为 0 记 0（不记 1、不记 null） |

### 3. 🔴 关键 trade-off：重试「全部被退订拦下」时**返回 200 而不是抛 409**

最初实现是抛 409（"本次重试的收件人全部命中退订"）。**集成测试打脸**：
409 是 `ResponseStatusException` ⇒ **整个事务回滚** ⇒ 刚写下的**拦截留痕一起没了**。

而那行留痕正是「为什么这个收件人没收到」的**证据** ——
蓝图加粗句要的恰恰是「不许静默丢弃」。

⇒ 改成**如实返回**（200 + 投递记录状态变 `BLOCKED` + `blocked_count` 上涨 +
拦截行与统计照常落库）。调用方从 `blocks` 与 `blockedCount` 上看得出
「这一次一个都没发出去」。这**不是**静默丢弃：证据与统计都写进库了。

### 4. 🔴 结果是**三态**不是布尔

`outcome` = `DELIVERED` / `FAILED` / `PENDING`（不是 `success` 布尔位）。
理由：「已受理未出结果」与「尝试过但没拿到响应」在布尔位上**都是 false** ——
混成一个数之后，「触达率的分母」就不可信了。

附带在 DB 层加了 CHECK：结果与响应码不许自相矛盾
（不许记下「已送达 + 500」这种编造出来的事实）。

### 5. 🔴 退订唯一键用**部分唯一索引**，不用表级 UNIQUE

「先查后登记」的数据库层兜底只需要保证「**同时**只能有一条**生效中**的退订」。
用表级 `UNIQUE(tenant_id, recipient_ref, channel, scope_type, scope_key)` 的话，
撤销退订（`revoked_at` 置上）之后**再**退订就只能改回那条老行 ——
那一段「曾订阅」的区间就永远查不到了，历史被抹掉。

⇒ 改成 `CREATE UNIQUE INDEX ... WHERE revoked_at IS NULL`：
「重新退订」落成**新行**，历史区间原样留着，而「生效中的退订只许一条」这个约束一点没松。

### 6. 🔴 本模块**不执行投递**

真正往渠道发消息的是下游 1.3.2.2「外部渠道推送」与 15.5.2「触达」。
本模块负责**登记投递结果、尊重退订、统计触达率** ⇒ 能力声明里
「投递执行器」如实答 `available=false` 并写明归谁，**不冒充**一个不存在的执行器。

### 7. 迁移纪律

- 四张表全部 `tenant_id NOT NULL` + `UNIQUE(tenant_id, id)` + ENABLE **与 FORCE** RLS
  （FORCE 是必须的：owner 身份一连就会绕过隔离）+ **DB 触发器兜底**
  （`platform_notify_delivery_tenant_guard`：缺 `app.current_tenant` ⇒ 28000，
  `tenant_id` 与上下文不符 ⇒ 42501）+ GRANT platform_app（**一律不给 DELETE** ——
  投递明细与退订登记是「这件事发生过」的证据）。
- 数量 `NUMERIC(19,6)`、比率 `NUMERIC(7,4)`，**零浮点**。
- 时间列一律 `timestamptz`，服务层 `OffsetDateTime` + `?::timestamptz` 绑定，
  落库时间戳取 **DB 时钟**（`clock_timestamp()`）。
- 外键指向 18.5.1.1 的 `platform_notify_template` / `_version`
  （`ON DELETE RESTRICT` —— 历史通知的模板依据不能随模板一起消失）。

### 8. 契约生成的一个坑：只能用**原始** spec 快照

`tools/codegen/catalog-contracts.py --spec-dir` 的输入必须是各服务
`OpenApiContractTest` 导出的**原始** spec。若拿已排序的 `packages/api-contracts/src/*.openapi.json`
快照当输入再生成，字段顺序会漂 ⇒ 8 个**没动过的**服务的 `.ts` 也整片重排（假 diff）。

本轮先跑了九个服务的 `OpenApiContractTest` 导出原始 spec，再生成 ⇒
**其余 8 个服务的产物逐字节未变**（git diff 为空），只有 platform 变了。

## 验证

- **L0 编译闸**：`./mvnw -o compile -pl services/platform -am` ⇒ BUILD SUCCESS
- **L1 模块闸**：`./mvnw -pl services/platform -am test -Dtest='NotifyDelivery*'`
  ⇒ **Tests run: 27, Failures: 0, Errors: 0, Skipped: 0**（功能 19 + 租户隔离 8）
- **租户隔离三断言**跑在本模块**自己的四张表**与**本模块的端点**上：
  ① 两租户互不可见（列表 + 按主键直查 + 端点 + 退订名单 + 拦截名单）；
  ② 伪造租户上下文无效（无上下文写入被挡，**且伪造 `X-Tenant-Id` 头后行确实落在
  会话租户名下**，不是被全表拒掉）；③ 连接池归还后不串租户（池固定 1 条连接，
  A→B→A→B 交替 5 轮）。
- **前端**：`pnpm vitest run apps/web/src/components/platform/notify-delivery-model.test.ts`
  ⇒ 35 例；`pnpm vitest run apps/web/src/app/api/platform/notify-delivery-allowlist.test.ts`
  ⇒ 6 例；`pnpm vitest run apps/web/src/app/api` 全量 allowlist 回归
  ⇒ 1661 passed / 3 failed（**3 例是 5.1.1.2 价目项车道留在基线里的既有失败**：
  把本车道的 api-proxy 改动 stash 掉重跑，同样这 3 例红 ⇒ 与本车道无关）。
- **类型闸**：`pnpm typecheck` ⇒ 3 successful / 3 total
- **格式闸**：`pnpm exec prettier --check <本车道文件>` ⇒ All matched files use Prettier code style
- **迁移实测**：V427 + V439 在真实 PostgreSQL 16 上两次全量 apply 均 exit 0，
  并实跑校验「UNIQUE 兜底 / CHECK 兜底 / RLS 隔离（t1 见 1 行、t2 见 0 行）」。

## 文件清单

**迁移**

- `backend/services/platform/src/main/resources/db/migration/V439__platform_notify_delivery.sql`
- `backend/services/identity/src/main/resources/db/migration/V439__platform_notify_delivery_permission.sql`

**后端**

- `backend/services/platform/src/main/java/com/starcon/platform/NotifyDeliveryModels.java`
- `backend/services/platform/src/main/java/com/starcon/platform/NotifyDeliveryService.java`
- `backend/services/platform/src/main/java/com/starcon/platform/NotifyDeliveryController.java`

**测试**

- `backend/services/platform/src/test/java/com/starcon/platform/NotifyDeliveryIntegrationTest.java`
- `backend/services/platform/src/test/java/com/starcon/platform/NotifyDeliveryTenancyIntegrationTest.java`

**契约（codegen 产物，禁止手改）**

- `packages/api-contracts/src/platform.openapi.json`
- `packages/api-contracts/src/contracts/platform.ts`

**前端**

- `apps/web/src/components/platform/notify-delivery-model.ts`
- `apps/web/src/components/platform/notify-delivery-model.test.ts`
- `apps/web/src/components/platform/notify-delivery-workspace.tsx`
- `apps/web/src/app/notifications/deliveries/page.tsx`
- `apps/web/src/app/api/platform/notify-delivery-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（**外科式追加**一条独立 `services[]` 元素）

**台账**

- `docs/product/capability-blueprint/module-progress.md`（只改 18.5.2.1 那一行的
  「状态」「证据」两格）

## 后续待办

1. **下游 1.3.2.2 / 15.5.2 尚未接入本模块的登记入口** —— 真正执行投递的那两个模块
   拿到渠道执行器的真实结果后，应调 `POST /platform/notifications/deliveries`
   与 `POST /platform/notifications/deliveries/{id}/retry` 来登记。本轮本模块
   自带登记入口（便于独立验证与运营补登），但**不是**渠道执行器。
2. **18.5.1.1 的模板还没有对外提供「按时刻解析当前生效版本」的 HTTP 端点** ——
   本模块的「没指定版本时解析当前生效版」是**直接查 activation 表**实现的
   （同服务同库，允许）。若将来 18.5.1.1 抽出跨服务端点，这处应改为调它的 API。
3. **`platform_notify_unsubscribe` 没有「退订偏好」维度**（按通知类型分别退订）——
   蓝图没要求，本轮按「渠道 × 范围（模板 / 场景 / 全局）」三档实现。
4. **基线里 5.1.1.2 价目项车道留下的 3 个 allowlist 失败**（见「验证」一节）
   不在本车道范围，已如实回报给调度者。
