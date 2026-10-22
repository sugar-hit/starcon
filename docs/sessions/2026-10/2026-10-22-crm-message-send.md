# 15.1.1.2 消息发送（阶段 D7，L3 锚点 15.1.1）

- **日期**：2026-10-22
- **车道**：`feat/15-1-1-2`（worktree `integration/.worktrees/15.1.1.2`）
- **蓝图原文**（`docs/product/capability-blueprint/15-crm-and-service.md` 92-97 行）：
  > 职责：回复买家消息：调用模板、手工编辑、附件支持、发送记录、失败重试；支持批量相同回复（同类问题）。
  > 输入：回复内容（模板或手工）；目标消息；附件；`idempotency_key`。
  > 输出：发送记录 + 平台回执 + 失败原因。
  > 关系：上游 = 15.1.1.1、15.4.2 知识库；下游 = 平台消息接口。**发送失败要保留原消息状态**，不能让客服以为已回复。

## 决策摘要

### 1. 🔴 加粗句「发送失败要保留原消息状态」落成**推导**，而不是写回

`crm_message`（15.1.1.1 的表）**没有**「已回复」这一列。所以本模块的处置是：

> 一条消息「是否已回复」 ⇔ 存在一条 `status='SENT'` 的 `crm_message_send` 指向它。

于是**发送失败这条路径上，源消息的已回复状态根本没有能力改变** —— 没有那一列可以被改，也就没有「忘了改回来」这种事故。而「忘了不改」正是客服看到「已回复」而实际没发出去的**唯一**成因。

代价：要在列表上显示「已回复」必须 join 发送记录（`LEFT JOIN LATERAL`）。这是本模块唯一一次跨车道读 15.1.1.1 的表 —— **只读**，写入所有权仍在 15.1.1.1 那边，本模块**从不 UPDATE `crm_message`**。

### 2. 平台投递未接入 ⇒ 立端口 + 拒写，不放假回执

新增 `PlatformMessageSendPort` + `UnavailablePlatformMessageSendPort`（逐平台答「未接入 + 归模块 2」，**不返回任何成功回执**）。服务层**拒绝** `transport='PLATFORM_API'` 并返回 **409**，**一次数据都不写**；投递只允许 **MANUAL_HANDOFF**（人工交接：运营在平台侧实际发出后回登记结果与平台回执）。PLATFORM_API 词表**先钉死**，将来适配器交付时不用改语义。

理由：一个返回成功回执的假通道会让「消息已经发给买家了」在数据上看起来发生过，而现场一条真消息都没发出去。

### 3. 三种话术来源各自留痕（`body_source`）

`MANUAL` / `TEMPLATE` / `TEMPLATE_EDITED` / `KNOWLEDGE` / `KNOWLEDGE_EDITED` —— 事后能回答「这句到底是模板原话还是人改过的」。模板起稿后手工改 ⇒ 请求里的正文优先（= 手工编辑）；模板与知识库**不能同时**指定（来源二选一）。

### 4. 模板变量是**封闭词表**，填不上就让发送失败

11 个变量（`buyer_reference` / `order_reference` / `shop_name` / `platform_code` / `message_subject` 来自目标消息；`buyer_name` / `order_no` / `platform_order_no` / `sku_list` / `currency` / `order_amount` 来自 trade 的订单上下文）。

- **未定义的变量** ⇒ 400（正文里留着 `{{xxx}}` 没人看得懂，而那封信是要发给买家的）；
- **依赖订单上下文、而订单上下文读不到的变量** ⇒ 400（把 `{{order_amount}}` 原样发给买家比发不出去糟得多）。上游读不到时**不抛 503**，而是当作「填不上」—— 否则运营会以为系统坏了，而不是「这句模板里有填不上的东西」。

### 5. 幂等键在 DB 层兜底

`UNIQUE (tenant_id, idempotency_key)`。重放同一条键**只返回既有记录**并标记 `replayed=true`，**不会**产生第二条发送记录。理由：重复回复买家的代价是平台违规与买家投诉。前端也为每次表单生成一个键（同一份表单重试提交复用同一个键）。

### 6. 批量相同回复：逐条结论 + 每条一个保存点

`POST /crm/message-sends/batch` 同一正文发给多条消息，共享一个 `batch_id`，**逐条给出结论与原因**（失败的条目不会被悄悄跳过）。

每条独立成一个**保存点**（`PROPAGATION_NESTED`）。**不用 `REQUIRES_NEW`**：另开事务会丢掉事务局部的 `app.current_tenant` 绑定（RLS 会立刻看不见任何行），而且连接池只有 1 条连接时还会自锁。

### 7. 失败重试：每一次尝试一行

`crm_message_send_attempt` 独立成表 —— 若只在发送记录上放一个 `attempt_count`，「第 1 次为什么失败、第 2 次为什么失败」就没有落点，而失败原因恰恰是蓝图输出句里点名要输出的东西。重试**不改正文**（改正文就是另一次回复，不是重试）。

### 8. 跨服务只读只走授权 HTTP 端口

订单上下文读 trade `GET /trade/sales-orders/list`，照抄同服务 `MessageContextPort` 先例：转发**调用方自己的** `Authorization` bearer、**不传租户号**、显式超时、**取不到就显式失败**，绝不返回空集合冒充「上游没有数据」。

### 9. 与 15.1.2.1 邮件**刻意不共用表**

邮件有它自己的发送记录 `crm_buyer_email_send_record`。站内信另起 `crm_message_send_*` 系列表名 —— 把邮件的发送记录拿来当站内信的发送记录，会让「这封邮件发出去了」与「这条站内信发出去了」在数据上混成一件说不清的事。

## 关键 trade-off

| 取舍 | 选择 | 放弃了什么 | 为什么 |
| --- | --- | --- | --- |
| 「已回复」存在哪 | **推导**（SENT 的发送记录） | 列表页多一次 join | 失败路径上源消息状态**根本没有能力改变**；写回方案里「忘了不改」是客服被骗的唯一成因 |
| 平台投递 | 拒写 + 人工交接 | 「一键发出」的体验 | 平台适配器不存在；假回执比没有更糟 |
| 模板变量填不上 | **让发送失败** | 部分模板可用性 | 半截模板发给买家 = 真实投诉 |
| 批量隔离 | 保存点（NESTED） | 独立事务的自由度 | `REQUIRES_NEW` 会丢 `app.current_tenant` 绑定，池大小 1 时自锁 |
| 知识引用登记失败 | **不阻断发送**，如实回报在结果里 | 引用一定齐全 | 「发出去了」这个事实比「引用查得到」更不能丢 |

## 文件清单

**迁移（crm 服务）**
- `backend/services/crm/src/main/resources/db/migration/V297__crm_message_send.sql` —— 四张新表 + RLS（ENABLE **与** FORCE，policy 名 isolation）+ 序列单独授权 + 零外键 + 7 个触发器。

**后端**
- `backend/services/crm/src/main/java/com/starcon/crm/PlatformMessageSendPort.java`
- `backend/services/crm/src/main/java/com/starcon/crm/UnavailablePlatformMessageSendPort.java`
- `backend/services/crm/src/main/java/com/starcon/crm/MessageSendModels.java`
- `backend/services/crm/src/main/java/com/starcon/crm/MessageSendService.java`
- `backend/services/crm/src/main/java/com/starcon/crm/MessageSendController.java`

**测试**
- `backend/services/crm/src/test/java/com/starcon/crm/MessageSendIntegrationTest.java`（26 例）
- `backend/services/crm/src/test/java/com/starcon/crm/MessageSendTenancyIntegrationTest.java`（4 例，租户隔离三断言）

**契约（codegen 产物，只改 crm 一份）**
- `packages/api-contracts/src/contracts/crm.ts`（+24 行：24 个新类型）
- `packages/api-contracts/src/crm.openapi.json`（+10 条路径 / +24 个 schema，**纯增量，零删除**）

**前端**
- `apps/web/src/components/crm/message-send-model.ts`
- `apps/web/src/components/crm/message-send-model.test.ts`（20 例）
- `apps/web/src/components/crm/message-send-workspace.tsx`
- `apps/web/src/app/crm/messages/sends/page.tsx`
- `apps/web/src/app/api/crm/message-sends-allowlist.test.ts`（6 例，allowlist 反查）
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 `services[]` 元素**，锚点在 `^message\-inbox...` 之后；既有条目一字未动、不重排）

**台账**
- `docs/product/capability-blueprint/module-progress.md` —— 只改 `15.1.1.2` 一行的「状态」「证据」两格。

## 验证

- L0 编译闸：`./mvnw -pl services/crm -am compile` 绿；`pnpm typecheck` 三包绿；`pnpm --filter @starcon/web lint` 无新增告警。
- L1 模块闸：`./mvnw -pl services/crm -am test -Dtest='MessageSend*' …` ⇒ **30 例绿**（真实 PG16 Testcontainers，含租户隔离三断言）。
- 契约：`OpenApiContractTest` 6 个服务各 1 例绿 → `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` → 只改 crm 两份。
- 前端：模型 20 例绿 + allowlist 反查 6 例绿；**`pnpm vitest run apps/web/src/app/api` 全量 1000 例绿（173 文件）**（改了共享白名单必须全量回归）。

## 后续待办

1. **平台买家站内信发送适配器**（归模块 2，channel 服务的 vendor 包）—— 交付后只需换掉 `UnavailablePlatformMessageSendPort` 的实现类；`PLATFORM_API` 词表与 DB 约束都已就位，语义不用改。
2. **15.1.1.3 消息分类 / 15.5.1 自动回复** —— 未交付；本模块的能力声明里 `message_classification` / `auto_reply` 两维是 `available=false` + `ownerModule`，自动回复接进来时应当**经本模块的发送入口**走（否则会出现第二条不经幂等键的发送路径）。
3. **15.1.2.1 邮件同会话** —— 邮件已交付但刻意不共用表；两边要按**同一个会话键公式**对齐（`crm_buyer_email_send_record` 侧已有公式，本模块尚未引入同源概念）。
4. 台账里 `15.1.1.1` 那一行的「已知缺口 ② 15.1.1.2 消息发送未交付」在本车道交付后已过时 —— 本车道按纪律**只改自己那一行**，未动别的行，留给调度者并入时统一处理。
