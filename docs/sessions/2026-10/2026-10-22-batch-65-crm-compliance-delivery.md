# 2026-10-22 batch-65 —— 交付轮：卷 15 / 卷 16 四个模块并入 + L2 全绿

> 批次 `batch-65`，集成头 `integration/module-dev`。
> 上一批 `batch-64` 是**派发轮**（零并入 + 派发 4 条车道 + 两次并入预演），本轮是它的**交付轮**：
> 4 条车道全部 land → 一次 `merge-ready` 并入 → **一次 L2 全量** → 三件套。
> 派发轮的记录见 `2026-10-22-batch-64-dispatch-crm-compliance-lanes.md`。

**本轮台账变化**：已交付 **316 → 320**、进行中 0（未变）、未开发 **149 → 145**。
全库 465 个 L4 模块，完成率 **68.0% → 68.8%**（320 / 465），离全部交付还差 **145** 个。
分阶段：D7 已交付 **42 → 46**；分系统：卷 15 **8 → 11**、卷 16 **4 → 5**。
台账完整性：**465 行**、**重复 ID 0 个**。

---

## 一、L2 批量验证：**全绿** ✅

本批 4 条车道全部 land 后，集成头上**只跑这一次**全量验证（组批模式的核心：每组只验证一次）。

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `cd backend && ./mvnw verify` | **BUILD SUCCESS** — 6 个服务 **8178 例**，**failures 0 / errors 0**（catalog 1981 / trade 2383 / warehouse 1640 / channel 1638 / identity 270 / crm 266） |
| 2 | `pnpm typecheck` | **3/3 通过** |
| 3 | `pnpm lint` | **通过** |
| 4 | `pnpm test` | **470 个测试文件 / 6894 例全过** |
| 5 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | **成功**（Next production build 全路由出图） |
| 6 | `node tools/module-progress/sync.mjs --check` | **✓ 台账与蓝图一致** |

⇒ 六步 exit code 全 0，**L2 绿**。

> ⚠️ 协议文档里写的「后端 2829 例 / 前端 2163 例」是**过期数字** —— 本轮实测是**后端 8178 例 / 前端 6894 例**。
> 汇报与估算一律以本轮实测为准。

**这一轮没有出现过的失败形态**（留档给下一轮对照）：
- ❌ 跨车道互相破坏（4 条车道同时改 `crm.ts` / `crm.openapi.json` / `api-proxy.ts` / 台账）⇒ **未发生**，
  并集驱动按「导出类型名 / OpenAPI 路径 / 模块 ID」求并，三步顺序合并全 exit 0、无 `CONFLICT_KEYS`；
- ❌ 契约重名类型导致字段串味（batch-56 在 14.2.2.1 抓到过 `AdBudgetGuardrails` 撞名）⇒ **未复现**，
  合并后 `crm.ts` / `channel.ts` 的重名 `export type` 均为 **0 个**；
- ❌ L2 与在途车道 L1 并发导致的成片假红 ⇒ **刻意规避**：并入与 L2 全部安排在 4 条车道 land 之后，
  跑 L2 时 `lanes.json` 里 **0 条 running**。

**静态预检**（并入前，对本批 4 个新迁移单独扫）：三条「已知互相破坏形状」**全部零命中** ——
`ADD COLUMN` 无 `DEFAULT` **0**、`SET NOT NULL` **0**、`ADD CONSTRAINT ... CHECK` **0**；
四个迁移**零 ALTER 任何既有表**、**零外键**。

---

## 二、四个模块的交付说明

### 模块 15.1.1.2 消息发送

**这个功能做什么**：客服在收件箱里看到买家消息后，能用知识库话术或手工编辑回一条回复，带附件、
有发送记录、失败能重试；同一类问题可以批量用同一段话术回复。**平台投递本身如实答「未接入」**。

**技术落地**：

- **迁移**：`V297__crm_message_send.sql`（crm 服务，479 行）— 4 张新表
  `crm_message_send_template`（回复模板）/ `crm_message_send`（发送记录）/ `crm_message_send_attachment`（附件）/ `crm_message_send_attempt`（投递尝试）；
  全部 `tenant_id NOT NULL` + `ENABLE` **与** `FORCE ROW LEVEL SECURITY` + `isolation` 策略（USING 与 WITH CHECK 同表达式）
  + GRANT + **序列单独授权** + `REVOKE ALL ON flyway_schema_history FROM crm_app`；
  **零 ALTER 任何既有表**、**零外键**（跨表引用校验用触发器）。
- **端点**：`/crm/message-sends/**` — 13 个方法映射 / 10 条路径
  `GET /capabilities`、`GET|POST /templates`、`GET|PUT|DELETE /templates/{templateId}`、`GET /knowledge-templates`、
  `GET /targets`、`GET /sends`、`GET /sends/{sendId}`、`POST /sends`、`POST /batch`、
  `POST /sends/{sendId}/result`、`POST /sends/{sendId}/retry`；权限码 `crm.message.send`（同族 `.read` 在既有 grant 里）。
- **页面**：`/crm/messages/sends`（`apps/web/src/app/crm/messages/sends/page.tsx` +
  `components/crm/message-send-model.ts` + `message-send-workspace.tsx`）；
  能力声明 → 目标消息筛选 → 回复编辑（模板/手工 + 附件）→ 发送记录与重试；加载态 / 错误态 / 空态齐全。
- **测试**：`MessageSendIntegrationTest` **26 例** + `MessageSendTenancyIntegrationTest` **4 例**（真实 PG16 Testcontainers），
  前端 `message-send-model.test.ts` **20 例** + `message-sends-allowlist.test.ts` **6 例**；
  改共享白名单后跑了 `pnpm vitest run apps/web/src/app/api` 全量 **173 文件 / 1000 例**。

**关键设计决策**：
1. **「发送失败要保留原消息状态」落成「不建那根列」** —— 「已回复」这个事实**只由发送记录推导**
   （存在一条 `status='SENT'` 的 `crm_message_send` 即算已回复），本模块**从不 UPDATE `crm_message`**。
   ⇒ 失败的那次发送**根本没有能力**把源消息改成「已回复」，也就没有「忘了改回来」这种事故；
   同时天然满足「不 ALTER 既有表」，避免与同批车道抢既有表的 DDL。测试断言源消息**逐列相同**（含 `updated_at`）。
2. **平台投递未接入 ⇒ 立端口如实答**：服务层拒绝 `transport=PLATFORM_API` → **409 且一次数据都不写**；
   只允许 `MANUAL_HANDOFF`。不返回假成功回执 —— 一条返回「已发送」的假通道会让「消息已经发给买家了」看起来发生过。
3. **批量隔离用 `PROPAGATION_NESTED`（保存点）而非 `REQUIRES_NEW`** —— 后者会丢事务局部的 `app.current_tenant` 绑定，
   且连接池大小为 1 时会自锁。
4. **api-proxy 首 token `message-sends`**（基线 192 条正则中命中数 0），锚点固定在 `^message\-inbox...` 之后。

---

### 模块 15.2.1.2 评价回复

**这个功能做什么**：对买家评价发公开回复（部分平台还支持追加卖家评论），能用按版本管理的模板、
支持多语言、可以批量回复同类评价；**回复内容会被平台措辞规范真的拦下来**。

**技术落地**：

- **迁移**：`V298__crm_review_reply.sql`（crm 服务，760 行）— 6 张新表
  `crm_review_reply_policy`（措辞规范，按平台 × 形态）/ `crm_review_reply_template`（模板）/
  `crm_review_reply_template_variant`（多语言变体）/ `crm_review_reply`（回复）/
  `crm_review_reply_delivery`（投递记录）/ `crm_review_reply_batch`（批量）；
  5 序列、9 触发器；6× `ENABLE`+`FORCE` RLS；**零 ALTER / 零 DROP / 零外键**。
- **端点**：`/crm/review-replies/**` — `GET /capabilities`、`GET /policies`、`GET /policies/{policyId}`、`PUT /policies`、
  `GET|POST /templates`、`GET|PUT /templates/{templateId}`、`POST /templates/{templateId}/publish`、
  `POST /templates/{templateId}/archive`、`GET|POST /replies`、`GET /replies/{replyId}`、
  `POST /replies/{replyId}/deliver`、`POST /replies/{replyId}/cancel`、`GET|POST /batches`；
  权限码 `crm.product_review.reply`。
- **页面**：`/crm/review-replies`（`apps/web/src/app/crm/review-replies/page.tsx` +
  `components/crm/review-reply-model.ts` + `review-reply-workspace.tsx`）。
- **测试**：`ReviewReplyIntegrationTest` **15 例** + `ReviewReplyTenancyIntegrationTest` **4 例**（租户三断言实跑），
  前端 model + allowlist **21 例**，api-proxy 全量 allowlist 回归 **173 文件 / 1000 例**。

**关键设计决策**：
1. **加粗句「模板必须符合平台措辞规范」落成四处**：① 规范**可配置且版本化**（按平台 × 形态，带生效窗，
   触发器**禁止窗口重叠** —— 「当时生效的是哪一版」必须唯一）；② 校验**真的拦得住**
   （`ReplyWordingChecker`：按码点长度 / 联系方式 / 促销话术 / 外链 / 标点上限 / **1–2 星必致歉** /
   租户禁用词 / **指控性措辞不可禁用**；不合规模板存不进、不合规回复发不出，**400 且零行写入**）；
   ③ 差评必致歉（「转化机会」那一半）；④ **历史可追溯**（每条回复存 `template_id + version + key + locale + policy_version`）。
2. **模板 = 一行一个版本**，发布后冻结（改内容 = 新版本）；多语言按 locale 一行一变体；
   请求 locale 缺失时回落到模板默认语言，**并在回复里记录实际使用的语言**。
3. **平台回复未接入 ⇒ 409 + 回执恒空 + 每次尝试都留痕**（`outcome=NOT_INTEGRATED`）；
   409 在 **commit 之后**抛，尝试不会被事务回滚掉。
4. **刻意不动 15.2.1.1 的 `crm_product_review.replied`** —— 那列的语义是「平台侧已存在回复」，
   而我们的回复还没投递出去；改了就是撒谎。
5. **内置词表是「本仓的保守基线，不是任何平台的官方政策原文」** —— 已在能力声明与会话日志里标注，
   接平台适配器时要按平台正式政策复核。

---

### 模块 15.3.2.2 接触记录

**这个功能做什么**：把与客户的所有接触（客服通话、站内信、邮件、营销触达、售后沟通）记成一条可检索的时间线；
并把「不要再联系我」这种退订**变成一等状态**，让将来的营销触达能据此排除。

**技术落地**：

- **迁移**：`V299__crm_customer_contact.sql`（crm 服务，587 行）— 3 张新表
  `crm_customer_contact`（接触记录）/ `crm_customer_opt_out`（退订当前态）/ `crm_customer_opt_out_event`（退订审计事件，**只追加**）
  + 1 序列 + 7 触发器；RLS 用一条 DO 循环对 3 张表逐张 `ENABLE`+`FORCE`+`isolation`；**零外键**、**零 ALTER**。
- **端点**：`/crm/customer-contacts/**` — 12 方法映射 / 10 路径：
  `GET /capabilities`、`GET /contacts`、`GET /contacts/{contactId}`、`POST /contacts`、`GET /timeline`、
  `POST /ingest`、`GET /opt-outs`、`GET /opt-outs/{optOutId}`、`GET /opt-outs/{optOutId}/events`、
  `POST /opt-outs`、`POST /opt-outs/{optOutId}/revoke`、`GET /opt-out-check`；权限码 `crm.contact.read` / `crm.contact.update`。
- **页面**：`/crm/contacts`（`apps/web/src/app/crm/contacts/page.tsx` + `components/crm/customer-contact-model.ts` +
  `customer-contact-workspace.tsx`）。**刻意不挂 `/crm/customers`** —— 15.3.1.1 客户列表未交付，
  造一个客户列表页是越界。
- **测试**：`CustomerContactIntegrationTest` **18 例** + `CustomerContactTenancyIntegrationTest` **4 例**，
  前端 model + allowlist **16 例**，api-proxy 全量 allowlist 回归 **173 文件 / 999 例**。

**关键设计决策**：
1. **加粗句「接触记录是自动化的豁免依据」落成一条完整链路**：
   退订是一等状态（登记必带依据 + 快照）→ **写普通接触记录根本碰不到退订状态**（模型层不给 `OPT_OUT` 口子）
   → 撤销是**显式动作**（必带原因 + 只追加审计事件 + 时间线留痕；**已撤销不能改回生效**、退订删不掉）
   → `GET /opt-out-check` 给出**可查询的豁免判定**。
2. **反向闸**：退订**之后**的营销触达落不了库（要留痕须显式标 `VIOLATION`）；
   退订**之前**的历史触达照常补录 —— 补录历史不该被今天的退订状态追溯否决。
3. **自动写入是真的**：15.1 消息 / 15.1.2.1 邮件与本模块**同服务同库** ⇒ 用**库内物化触发器**自动写入
   （不改上游代码、不改上游表结构）。**11 售后在 trade（跨服务）** ⇒ 提供 `POST /ingest` 授权 HTTP 入口，
   但 **trade 侧未接线 ⇒ 如实标 `available=false` + `ownerModule=11.1.1.1`**，不假装已打通。
4. **已知耦合取舍**：选物化触发器而非改上游 service，代价是接触记录与 `crm_message` / `crm_buyer_email`
   在 DB 层耦合（上游表若被重建，触发器会失效）；换来的是与并行车道**零冲突面**
   （`MessageInboxService.java` 正被 15.1.1.2 车道并行改动）。理由与替代方案已写进车道会话日志。
5. **客户标识是逻辑引用**，不是主数据 —— 15.3.1.1 / 15.3.1.2 未交付，本仓没有买家档案。

---

### 模块 16.1.1.1 账号健康总览

**这个功能做什么**：按店铺看账号健康状态（ODR / 迟发率 / 取消率 / 有效追踪率），
给出**风险等级与跨店铺风险排序**，让运营先处理最可能掉号的店铺。
**平台绩效同步未接入** ⇒ 指标只能人工录入，并明确标成「人工录入」。

**技术落地**：

- **迁移**：`V300__account_health.sql`（**channel** 服务，376 行）— 3 张新表
  `account_health_metric`（指标快照）/ `account_health_threshold`（阈值配置）/ `account_health_assessment`（判定凭据）；
  RLS 用一条 DO 循环对 3 张表逐张 `ENABLE`+`FORCE`+`isolation`；
  另在 `account_health_metric` / `account_health_assessment` 上加 `data_scope` **RESTRICTIVE** 策略
  （写法与已交付的 V197（16.2.2.1）**逐字同构**）；**零 ALTER / 零 DROP / 零外键**。
- **端点**：`/channel/account-health/**` — 11 个：
  `GET /capabilities`、`GET /shops`、`GET /shops/{id}`、`GET /ranking`、`GET /pending-items`、
  `GET /thresholds`、`PUT /thresholds/{metricCode}`、`POST /shops/{id}/metrics`、`POST /shops/{id}/assess`、
  `GET /assessments`、`POST /shops/{id}/sync`；权限码 `channel.account.read` / `channel.account.update`。
- **页面**：`/compliance/account`（`apps/web/src/app/compliance/account/page.tsx` +
  `apps/web/src/components/account-health/account-health-model.ts` + `account-health-workspace.tsx`）。
- **测试**：`AccountHealthRulesTest` **13 例** + `AccountHealthIntegrationTest` **20 例** +
  `AccountHealthTenancyIntegrationTest` **3 例** = 后端 **36 例**；
  前端 `account-health-model.test.ts` **16 例** + `account-health-allowlist.test.ts` **5 例**；
  api-proxy 全量 allowlist 回归 **173 文件 / 999 例**。

**关键设计决策**：
1. **量不到 ≠ 0、量不到 ≠ 低风险** —— 指标一律 0..1 比值（`NUMERIC(5,4)`），**NULL = 量不到**；
   四项没量全 ⇒ 等级 `UNKNOWN`（判不了），**不能断言 `HEALTHY`**。这条用 DB CHECK 钉住，不是靠代码自觉。
2. **平台绩效适配器未交付 ⇒ 立端口如实答**：同步端点**恒 409 且一条数据都不写**；
   绩效只能手工录入，录入条目的 `source` 由服务端**写死 `MANUAL`**（入参没有 source 字段），页面有「人工录入」标签。
   —— 绝不返回假指标：一条返回「ODR = 3.2%」的假同步会让运营以为账号真的在掉号。
3. **阈值在服务端算、可配置、有版本** —— 内置默认阈值**写明是本模块的默认政策、不是平台事实**（各平台口径不通用）；
   改阈值必然推进 `rule_version`（触发器钉住）；判定凭据 **append-only** 并带**当时阈值快照**
   ⇒ **规则变更不追溯改变历史判定**。
4. **排序真的按风险排** —— `CRITICAL → WARNING → UNKNOWN → HEALTHY`，名次是**全局名次**，每一家带 `rankReason`。
   蓝图加粗句「风险排序比明细更重要」就落在这里。
5. **下游 16.1.1.2 / 16.1.2.1 / 16.1.2.2 未交付 ⇒ 不编违规数与申诉状态**；
   需处理项只给本模块真实存在的、跳转目标只指向本模块自己的页面；
   api-proxy **刻意不放行** `violations` / `appeals` / `trends`。

---

## 三、并入与台账交叉引用的一处修正

并入时发现 3 条**已交付行**的证据文本里写着本批模块「未交付」——那是派发时的事实、并入后就不再成立。
按纪律**车道只改自己那一行**，所以由调度者在并入后统一订正（只改证据列的**这一句**，不重排整表）：

| 行 | 订正前 | 订正后 |
| --- | --- | --- |
| 15.1.1.1 消息收件箱 | 已知缺口②「15.1.1.2 消息发送未交付 ⇒ 本模块是纯收件箱，不发消息」 | 改为「已交付（batch-64，V297）⇒ 发送能力在 `/crm/message-sends/**`；本模块仍只做**收件箱**」 |
| 15.1.1.1 消息收件箱 | 未交付形状「`/messages/{uuid}/replies` 15.1.1.2 未交付」 | 改为「已交付但**走另一个首 token** ⇒ 收件箱侧刻意不放行」——**放行判定本身不变**（该路径后端确实不存在） |
| 15.2.2.2 索评规则 | 「下游 15.1.1.2 消息发送未交付」 | 改为「已交付（batch-64，V297）」，**本模块只产话术不发送这个事实保持不变** |
| 15.2.1.1 评价列表 | 「下游 15.2.1.2 评价回复 / 15.2.1.3 差评处理未交付」 | 改为「15.2.1.2 已交付（batch-64，V298）/ **15.2.1.3 差评处理仍未交付**」 |

⚠️ **留一个已知欠账**：15.2.1.1 模块**自身**的 capabilities 里 `review_reply` 维度仍标 `available=false` —— 那是**代码事实**，
本轮不越界改别的模块的代码。**下一批需要回填这一格**（`review_reply` 已可用，`negative_review_handling` 仍不可用）。

---

## 四、收尾三件套

| 件 | 结果 |
| --- | --- |
| ① 写说明 | ✅ 本文件（`docs/sessions/2026-10/2026-10-22-batch-65-crm-compliance-delivery.md`），**4 个模块各一节** |
| ② commit | ✅ `docs(batch-65): ...` —— 说明文件与台账（4 行 + 总览 + 3 处交叉引用订正）**同一个 commit** |
| ③ push | ✅ `origin integration/module-dev`（**只推集成分支，`main` 未动**） |

## 五、下一轮 tick 可以直接接手的事

1. 派发下一批车道（`plan --lanes 4`）：`serviceBlocked` 仍卡着 **卷 12 finance / 卷 13 reporting / 卷 1 前端聚合**
   共 **46 个模块** —— 这三卷要人工决策是否并入对应服务，调度者不绕开。
2. 下一批若排到 **15.2.1.1 评价列表**或 **15.1.1.1 消息收件箱**，记得**回填 capabilities 里的 `review_reply` 维度**。
3. 继续沿用本批的纪律：**零 ALTER 既有表 / 零外键 / 触发器守卫 / api-proxy 锚点互不重叠 / 首 token 零命中核对 / 改共享白名单必跑全量 allowlist 回归**。
4. **温度与容量观测**：本轮 L2 期间带 `org.testcontainers.sessionId` 标签的 PG 容器峰值 **51 个**（全是本轮 surefire 自己的 fan-out，
   彼时 `lanes.json` 里 **0 条 running**，没有并发 L1）；load 1min 全程在 **1.85–3.82**（阈值 12）；`pmset -g therm` 全程无降频记录。
   ⇒ 下轮开工前**重新采样** PG 闸与 load，不要拿 L2 期间的高读数当常态。
