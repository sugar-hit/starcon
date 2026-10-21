# 15.1.2.1 邮件收发（阶段 D7，L3 锚点 15.1.2）

日期：2026-10-22 ｜ 分支 `feat/15-1-2-1` ｜ 基线 `integration/module-dev` @ `7c1e688f`
迁移版本：**V292**（本车道预分配；同批另三条车道用 V289 / V290 / V291，V287 属上一批仍在跑的 14.4.2.1）

## 决策摘要

### 1. 加粗句「分开展示会导致重复回复买家」落成会话键，不落成外键

蓝图要「与站内信的双向同步**或标记为同源**」。同批并行的 15.1.1.1「消息收件箱」
**其表不在本车道基线里** ⇒ 本表**不建任何指向它的外键**（跨批次 FK 会让迁移在基线上直接失败，
也正是 AGENTS.md 禁止的形状）。改用两样东西：

- `conversation_key`（会话键）：`<shopId>|<buyerRef 小写>|<salesOrderNo 小写>`，
  无订单号时第三段写 `-`。由服务端**唯一推导点** `BuyerEmailService.conversationKey()` 算出 ——
  两边各算各的，对齐就永远对不上，而加粗句要的正是这个对齐。
- `source_message_id uuid`（**nullable 的软引用，不加 FK**）：留给 15.1.1.1 落地后回填真实主键。

**同源 = 会话键相等**。15.1.1.1 落地后按同一公式对齐是**下一次迭代**的事，不影响本车道独立落地。

### 2. 收 / 发两个方向都在邮件表里，状态词表分开

加粗句要求「同一会话处理」。会话里如果只有收到的邮件、没有发出的邮件，
客服照样会以为「我还没回过」⇒ 照样重复回复。所以**每条发送记录都同时落一行 OUTBOUND 邮件**，
与收到的那封共用同一个会话键。

方向与状态**必须自洽**（DB CHECK 强制）：

| 方向     | 状态词表                     | 含义                           |
| -------- | ---------------------------- | ------------------------------ |
| INBOUND  | `RECEIVED` / `REPLIED`       | 收到；是否已成功回复           |
| OUTBOUND | `QUEUED` / `SENT` / `FAILED` | 我们发的那一行；状态即发送结果 |

### 3. 「发送失败要保留原消息状态」在邮件侧同样适用

`status='FAILED'` 的发送记录**不改**收到的那封邮件的状态（它仍是 `RECEIVED`）。
失败由**发出去的那一行**（OUTBOUND + `FAILED`）+ 失败原因如实记着。

界面上的区分是硬的：`已成功回复过` / `尝试过但没有成功发出（状态仍为「已收到」）/ 还没有回过`
—— 三句话（`repliedStateLabel`）。混成一句「操作失败」会让人再回一次。

### 4. 平台买家邮件未接入 ⇒ 端口 + 显式失败，不放假实现

`channel/vendor` 只有 13 个 OAuth 适配器，**没有买家邮件适配器**。照 channel 侧
`UnavailableInfluencerDataPort`（14.5.1.1 达人库）的**同构**先例处理：

- `PlatformEmailPort`（接缝）+ `UnavailablePlatformEmailPort`（当前实现）：
  对所有平台答「未接入」，**不返回任何邮件、不编造平台接口、不返回硬编码列表、不抛异常**。
- 同步端点据此返回 **409 且不写任何数据**（测试断言「行数不变」）。
- 发送记录的 `transport='PLATFORM_API'` 由**服务层拒绝**（409），
  只允许 `MANUAL_HANDOFF` —— 一个「返回成功回执」的假通道会让「买家已经收到回复了」
  在数据上看起来发生过，而重复回复的代价正是平台违规与买家投诉。

### 5. 下游 15.1.1.2 未交付 ⇒ 只做邮件自己的发送记录

本模块**不实现**「从收件箱发送消息」这个动作（capabilities 里 `message_send` 维度
available=false，ownerModule=15.1.1.2）。蓝图输出句的「发送记录」是**邮件自己的**发送记录。
模板同理：只做**本模块自己的**邮件模板（CRUD + 发送时引用），不碰 15.4 知识库与 15.1.1.2。

### 6. 附件只落元数据

crm 服务**没有对象存储**，附件字节归平台侧与 8 仓储。
`crm_buyer_email_attachment` 只记「文件名 / 类型 / 字节数 / 存储键 / 校验和」，
让客服看得见「买家附了 3 个文件」而不假装本服务存了文件。
字节数是 `numeric(19,6)` + BigDecimal（**禁浮点**），与 channel V247 / V255、trade V239 同一形状。

## 关键 trade-off

| 决策                                 | 取舍                                                                                                                             |
| ------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------- |
| 会话键 vs 外键                       | 放弃「外键保证完整性」，换「跨批次可独立落地」。代价是完整性靠 15.1.1.1 按同一公式自律；收益是本迁移在**本车道基线上就能跑**     |
| 发出去的邮件单独落行 vs 只放发送记录 | 多一张 OUTBOUND 行，换「会话视图里收与发都在」。否则客服看到只有收到的邮件，仍会重复回复                                         |
| 同步端点 409 vs 返回空列表           | 选 409。空列表会被读成「这个店铺没有买家来信」，而真相是「我们拉不到」                                                           |
| 裸路径 `buyer-emails` 放行           | 任务书建议「末尾刻意不带 `?`」，但蓝图输出句点名「邮件列表与详情」，列表必须有集合路径。**放行并写明理由**，与 15.2.2.1 同一形状 |
| 附件字节数 numeric(19,6) vs bigint   | 跟随本仓近几条迁移（V247 / V255 / V239）统一用 numeric，避免同一语义两处类型不同                                                 |

## 文件清单

**迁移（crm 服务）**

- `backend/services/crm/src/main/resources/db/migration/V292__crm_buyer_email.sql`
  —— 4 张新表（`crm_buyer_email` / `crm_buyer_email_attachment` / `crm_buyer_email_template` /
  `crm_buyer_email_send_record`）+ 6 个守卫触发器 + RLS（ENABLE + FORCE，策略名 `isolation`）
  - GRANT（表与**序列分开**授权）+ `REVOKE ALL ON flyway_schema_history FROM crm_app`。
    **零 ALTER 任何既有表**；**零外键**（含本文件自己新建的表之间）。

**后端（crm 服务，包 `com.starcon.crm`）**

- `BuyerEmailModels.java` —— 传输模型（`CrmBuyerEmail*` 前缀，避免跨服务同名 DTO 冲突）
- `BuyerEmailService.java` —— jOOQ 显式 SQL；能力声明；列表 / 详情 / 会话；登记 / 发送 / 附件 / 模板 CRUD；平台同步
- `BuyerEmailController.java` —— `/crm/buyer-emails/**` 共 **10 条路径 / 15 个方法映射**
- `PlatformEmailPort.java` / `UnavailablePlatformEmailPort.java` —— 平台买家邮件接缝与当前实现
- `src/test/.../BuyerEmailIntegrationTest.java`（21 例）、`BuyerEmailTenancyIntegrationTest.java`（4 例）

**契约（codegen 产物，禁止手改）**

- `packages/api-contracts/src/contracts/crm.ts` —— 新增 18 个 `CrmBuyerEmail*` 类型
- `packages/api-contracts/src/crm.openapi.json` —— 新增 10 条路径 + 18 个 schema（**纯增量**，既有路径与 schema 零漂移）

> 跑 codegen 时 channel / trade 的既有文件也被重排（**基线里就有的漂移**，与本车道无关），
> 已**刻意复原为基线版本**，保持本车道 diff 只含 crm。

**前端**

- `apps/web/src/app/crm/emails/page.tsx` —— 页面入口（与 `/crm/review-requests` 同级）
- `apps/web/src/components/crm/buyer-email-model.ts` —— API 客户端 + 可读形态（三态判定、状态词表、查询串拼装）
- `apps/web/src/components/crm/buyer-email-workspace.tsx` —— 真实工作区（能力边界 / 列表 / 详情 + 会话 / 登记 / 发送 / 附件 / 模板 / 同步）
- `apps/web/src/components/crm/buyer-email-model.test.ts`（6 例）
- `apps/web/src/app/api/crm/buyer-emails-allowlist.test.ts`（8 例）
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**，既有条目一字未动

**台账 / 日志**

- `docs/product/capability-blueprint/module-progress.md` —— 只改 15.1.2.1 那一行的「状态」「证据」两格
- `docs/sessions/2026-10/2026-10-22-crm-buyer-email.md` —— 本文件

## api-proxy 锚点与零命中核对

- **锚点**：11.2.2.1 `return-receipts` 之后 / 11.2.2.2 `receipt-allocations` 之前的 **trade 区**。
  与本批另外三条车道的落点（channel 广告 / 达人 ×2、crm 消息收件箱）**相距 1700 行以上**。
- **零命中核对**：全文 **143 条** services[] 正则里 `buyer-emails` 出现次数 **= 0**。
  带 `buyer` 字样的只有 `^order-review\/...`（9.2.1.2 trade 订单审核，内部有 `buyer-profiles` 子形状）
  与 `^purchase-order-approvals(...)`（trade 采购订单审批，内部有 `members/buyers` 子形状）——
  两者首 token 与本模块不同，且都在 `prefix: 'trade/'` 下 ⇒ **互不吃掉**。
- **不误伤**：allowlist 测试正向钉住 `crm/knowledge/*`、`crm/review-requests/*`、`crm/review-rules/*`
  与 catalog / channel / warehouse / trade / identity 的代表形状；全量 allowlist 回归 **956 例全绿**。

## 后续待办

- **15.1.1.1 消息收件箱**落地后：两边按**同一会话键公式**对齐，本车道补一个
  `source_message_id` 回填动作；在此之前 `inbox_thread` 维度保持 available=false。
- **平台买家邮件适配器**接上后：`PlatformEmailPort` 被真正的实现替换，
  `sync` 端点自动放行（落库路径已由测试替身覆盖，按 `external_message_id` 幂等）。
- **15.1.1.2 消息发送**落地后：若它提供通用发送通道，本模块的 `transport` 词表需要与之对齐
  （当前只允许 `MANUAL_HANDOFF`）。
- **15.2.2.2 邮件规则**落地后：自动分配 / 静默规则属它，本模块不代劳。
- **权限码**：本车道用 `crm.email.read` / `crm.email.update`（与同服务
  `crm.knowledge.*` / `crm.review.*` / `crm.rule.*` 同一命名约定）。
  ⚠️ 任务书写的 `crm.operation.*` 在**全仓任何文件里都不存在**（identity 的 `permission`
  表里也没有任何 `crm.` 前缀的权限码，既有 crm 模块同样用自由词表），
  故按**兄弟模块的实际约定**取名，**identity 服务零改动**。
