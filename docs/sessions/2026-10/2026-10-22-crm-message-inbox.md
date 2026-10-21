# 2026-10-22 —— 15.1.1.1 消息收件箱（卷 15 客户与客服 / D7）

> 车道 `15.1.1.1`，分支 `feat/15-1-1-1`，基线 `integration/module-dev`（7c1e688f）。
> 迁移版本号 **V291**（本批预分配：V289 / V290 / V292 属另外三条车道，V287 属上一批 14.4.2.1）。
> 归属服务：**crm**（`backend/services/crm`，端口 8086，DB 角色 `crm_app`）—— 不是 channel。

---

## 决策摘要

蓝图原文（`docs/product/capability-blueprint/15-crm-and-service.md`，`### 15.1.1.1 消息收件箱`）：

> **职责**：集中展示各店铺的平台站内信与买家消息：店铺、买家、主题、正文、附件、平台时间、
> 收发方向、已读状态、关联订单，支持按店铺/类型/优先级/未读筛选与批量处理。
> **输入**：平台消息同步（webhook + 拉取）；用户筛选条件；数据范围（店铺维度）。
> **输出**：消息列表 + 详情视图 + 关联订单上下文。
> **关系**：上游 = 2 渠道与店铺（同步）；下游 = 15.1.1.2 回复、11 售后（转售后）、15.4 工单。
> **消息必须关联到买家与订单**，否则客服看到消息还要去猜是哪一单。

本车道把这句话**逐词落到表、端点、页面与测试**上：

| 蓝图里的词 | 落点 |
| --- | --- |
| 集中展示各店铺 | `crm_message.shop_id`（channel 真实店铺清单）+ 数据范围 = 店铺维度 |
| 平台站内信与买家消息 | `message_type ∈ {PLATFORM_INBOX, BUYER_MESSAGE, SYSTEM_NOTICE, COMPLIANCE}`，DB CHECK 钉死 |
| 店铺 / 买家 / 主题 / 正文 | `shop_id` / `buyer_reference` / `subject` / `body` |
| 附件 | `crm_message_attachment` 独立表 + 消息行上的 `attachment_count`（**由 DB 触发器维护**） |
| 平台时间 | `platform_time timestamptz`（买家/平台侧的时刻，**不是**登记时刻） |
| 收发方向 | `direction ∈ {INBOUND, OUTBOUND}` |
| 已读状态 | `read_status ∈ {UNREAD, READ, ARCHIVED}`（已读必须带 `read_at`） |
| 关联订单 | `order_reference` **NOT NULL + CHECK** + trade 只读端口补全的订单上下文 |
| 按店铺/类型/优先级/未读筛选 | 8 个筛选参数逐个对应一条**真实存在**的列 + 独立 `COUNT(*)` |
| 批量处理 | `POST /crm/message-inbox/batch`，**逐条给出结论与原因** |
| 平台消息同步（webhook + 拉取） | `PlatformMessagePort` + `UnavailablePlatformMessagePort`：**逐平台答「未接入」，409 且不写数据** |
| 消息列表 + 详情视图 + 关联订单上下文 | `GET /messages` / `GET /messages/{uuid}`（详情 = 消息 + 附件 + 订单上下文 + 处置意图） |
| **消息必须关联到买家与订单** | 🔴 落成**三层**：DB 层 NOT NULL + CHECK、服务层必填、展示层「不可用 + 原因」 |

---

## 关键 trade-off

### 1. 「消息必须关联到买家与订单」落成 **DB 层约束**，不是界面上的两个输入框

蓝图那句是加粗的 ⇒ 它是硬要求。做成「可选输入 + 事后补录」等于把它降级成「建议填」，
而客服场景里「以后再补」的结果就是**永远补不上**：补不上的那一条永远没人知道是哪一单。

⇒ `buyer_reference` / `order_reference` 都是 `NOT NULL CHECK (btrim(...) <> '')`。
集成测试**绕开服务层直接写库**验证过这两条 CHECK 自己会拒（否则「DB 层兜底」只是注释）。

### 2. 订单上下文**查不到**与**读不到**是两种不同的结论，页面不得显示空白

上游 trade 的 `keyword` 是**模糊**匹配（内部单号 / 外部单号 / 买家名）。
所以本服务**只认订单号完全相等**的行 —— 拿一条「相近」的订单当上下文，比显示「不可用」更糟：
客服会照着错的单子去处理。

三种结论在页面上的文案**逐条不同**：

| 情况 | 页面显示 |
| --- | --- |
| 查到了 | 订单号 / 平台单号 / 来源 / 金额 / 单据状态 / 平台状态 / 支付状态 / 履约状态 / 买家 |
| 上游没有这一单 | 「订单上下文不可用：上游 trade 订单服务里没有订单号 SO-1 这一单……」 |
| 上游读不到（不可达 / 无权限） | 「订单上下文不可用：trade 服务返回 500：订单上下文读不到」 |

**绝不显示空白** —— 空白会被客服读成「这一单没有信息」，而不是「我们没查到」。

### 3. 平台消息同步：**端口先立起来，实现如实答「未接入」**

蓝图输入句写着「平台消息同步（webhook + 拉取）」，但 channel 服务的 `vendor` 包下
**只有 13 个 OAuth 适配器，没有买家站内信适配器**。

⇒ 立 `PlatformMessagePort`（把「哪些平台接上了没有」变成一条**可查的事实**），
当前实现 `UnavailablePlatformMessagePort` 逐平台答「未接入 + 为什么 + 归谁」，
**平台清单来自真实店铺目录**（不是写死的常量 —— 写死的会漏掉新接的平台，或报出一个根本没在用的平台）。
同步端点**明确 409 且一条数据都不写**。

一个「返回成功回执」的假 vendor 会让「平台消息已经同步进来了」在数据上看起来发生过，
而现场一条真实消息都还没有 —— 那比明确报「未接入」糟得多。

### 4. 下游未交付：**只落处置意图，不写下游单据、不编下游 id**

15.1.1.2 消息发送、15.1.1.3 消息分类、11 售后（转售后）、15.4 工单
在本车道基线里**都没有可调的授权 API**。

⇒ 批量动作里提供「登记处置意图 / 撤销处置意图」，落 `crm_message_intent`（**reason 必填**）。
**该表刻意不存下游单据 id**：下游没交付就没有 id，编一个会让「已转售后」看起来发生过。
`status` 只有 `RECORDED` / `CANCELLED` 两档 —— **没有 `HANDED_OFF`**，
因为「已交接」这个事实要有下游系统来证明，本服务证明不了。

### 5. 附件计数**由 DB 触发器维护**，不靠应用层自觉

消息行上冗余一份 `attachment_count`，是为了让列表页**不 join** 就能显示「带附件」徽标。
两边各写各的迟早会漂 ⇒ `sync_crm_message_attachment_count` 是它**唯一**的来源。
集成测试直接删一行附件，断言计数自己从 2 走到 1。

### 6. 跨表引用用**触发器**，不建数据库外键

同批 batch-37 有一条车道在迁移里给既有表建了 FOREIGN KEY，结果打破了同服务既有测试的
TRUNCATE（PG：cannot truncate a table referenced in a foreign key constraint）。
本迁移**不给任何既有表建外键**，自己新建的三张表之间也一律用触发器做引用校验
（与同批 V220 同一手法）。

### 7. 裸路径 `/crm/message-inbox` **刻意不是端点**

消息集合挂在 `/crm/message-inbox/messages`（列表 / 登记 / 单条详情），
目录与动作挂在收件箱命名空间下（`/shops` `/options` `/platforms` `/capabilities` `/batch` `/sync`）。
⇒ api-proxy 白名单**末尾刻意不带 `?`**：带了就把裸路径一起放行，而那是一个后端不存在的映射。

---

## 文件清单

**后端（crm 服务）**

- `backend/services/crm/src/main/resources/db/migration/V291__crm_message_inbox.sql` —— 三表 + RLS + 索引 + GRANT + 序列单独授权 + 四个触发器
- `backend/services/crm/src/main/java/com/starcon/crm/MessageInboxModels.java` —— 传输模型
- `backend/services/crm/src/main/java/com/starcon/crm/MessageInboxService.java` —— jOOQ 显式 SQL + 能力声明 + 列表 / 详情 / 登记 / 批量 / 同步
- `backend/services/crm/src/main/java/com/starcon/crm/MessageInboxController.java` —— 8 path / 10 端点
- `backend/services/crm/src/main/java/com/starcon/crm/MessageContextPort.java` —— 跨服务只读端口（channel 店铺目录 + trade 订单上下文）
- `backend/services/crm/src/main/java/com/starcon/crm/PlatformMessagePort.java` —— 平台消息同步端口
- `backend/services/crm/src/main/java/com/starcon/crm/UnavailablePlatformMessagePort.java` —— 当前实现：逐平台答「未接入」
- `backend/services/crm/src/test/java/com/starcon/crm/MessageInboxIntegrationTest.java` —— 19 例
- `backend/services/crm/src/test/java/com/starcon/crm/MessageInboxTenancyIntegrationTest.java` —— 4 例（租户隔离三断言）

**前端**

- `apps/web/src/app/crm/messages/page.tsx` —— 页面
- `apps/web/src/components/crm/message-inbox-model.ts` —— 模型 + 可读形态
- `apps/web/src/components/crm/message-inbox-workspace.tsx` —— 工作区
- `apps/web/src/components/crm/message-inbox-model.test.ts` —— 14 例
- `apps/web/src/app/api/crm/message-inbox-allowlist.test.ts` —— 5 例（白名单双向断言）
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `crm/` 元素**（锚点：`identity/` 那条之后）

**契约与台账**

- `packages/api-contracts/src/crm.openapi.json` / `packages/api-contracts/src/contracts/crm.ts` —— codegen 产物（+8 path / +21 schema / 0 删除）
- `docs/product/capability-blueprint/module-progress.md` —— **只改 15.1.1.1 那一行的「状态」「证据」两格**

---

## 验证

```bash
# L0 编译闸
cd backend && ./mvnw -q compile                                   # 绿
cd .. && pnpm typecheck                                          # 绿（3 包）

# L1 模块闸（只跑本模块新增的测试类）
cd backend && ./mvnw -pl services/crm -am test -Dtest='MessageInbox*' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
#   Tests run: 19 (MessageInboxIntegrationTest) 绿
#   Tests run:  4 (MessageInboxTenancyIntegrationTest) 绿
#   Tests run: 23, Failures: 0, Errors: 0  → BUILD SUCCESS

# 前端
pnpm vitest run apps/web/src/components/crm/message-inbox-model.test.ts \
                  apps/web/src/app/api/crm/message-inbox-allowlist.test.ts
#   Test Files 2 passed (2)，Tests 19 passed (19)

# ⚠️ 改了 api-proxy.ts ⇒ 必须跑全量 allowlist 回归
pnpm vitest run apps/web/src/app/api
#   Test Files 165 passed (165)，Tests 956 passed (956)
```

**契约**：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`，
crm 契约**纯增量**（+8 path / +21 schema / 0 删除）。
**未触碰 channel / trade 契约** —— 集成头上那两份存在**与本车道无关的既有路径排序漂移**
（path 集合与 schema 集合完全相同，只是顺序不同），本车道刻意复原，不把无关噪音带进本车道 diff。

---

## 后续待办

| 待办 | 归属 | 说明 |
| --- | --- | --- |
| 平台买家站内信适配器 | 2 渠道与店铺 | channel `vendor` 下只有 13 个 OAuth 适配器。交付后**只换** `UnavailablePlatformMessagePort` 的实现即可，同步入口与登记入口不用改 |
| 消息发送 | 15.1.1.2 | 未交付。本模块是纯收件箱，不发消息；发送失败要保留原消息状态是 15.1.1.2 自己的事 |
| 消息分类 | 15.1.1.3 | 未交付。本模块的优先级由**人工**设置；人工纠正回流训练样本也是它的事 |
| 转售后 | 11 售后 | 未交付。`crm_message_intent` 已留好落点；下游 API 就绪后补一条**写下游**的路径 |
| 建工单 | 15.4 客服工单 | 未交付。同上 |
| 邮件收发 | 15.1.2.1 | 未交付。部分平台要求邮件与站内信同会话处理，本模块**不假装**邮件已同步 |
| 买家档案 | 15.3.1.1 | 未交付。`buyer_reference` 现在是**逻辑引用**而不是主数据；有了买家档案之后可以补一份快照 |

**本车道刻意没做的事**（都如实记在台账「已知缺口」里）：没有 mock 平台同步、没有 fake 下游单据 id、
没有把「订单上下文查不到」渲染成空白、没有在前端做任何金额/数量的 JS 侧算术。
