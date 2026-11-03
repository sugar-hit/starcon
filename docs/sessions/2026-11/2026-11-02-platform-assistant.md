# 2026-11-02: 18.4.1.1 对话助手（受控意图目录 / 明细依据 / 追问延续）

## 摘要

蓝图 18「集成开放与智能自动化」→ 18.4「AI 与智能」→ 18.4.1「智能问答」→ 18.4.1.1 落地。

车道分支 `feat/18-4-1-1`，迁移版本号由调度者预分配为 **V438**
（`backend/services/platform/.../V438__platform_assistant.sql` +
`backend/services/identity/.../V438__platform_assistant_permission.sql`，
与 V419 / V428 / V430 同一「platform + identity 同号」的登记口径）。

蓝图原文：「**职责**：自然语言助手：用中文提问业务问题（"上周哪个店利润最差""哪些 SKU 该补货了"），
返回答案并附数据来源链接；支持追问与上下文延续。**输入**：用户问题；当前上下文（租户、数据范围、当前页面）。
**输出**：答案 + 数据明细链接 + 建议的下一步操作。**关系**：上游 = 各域只读查询接口；下游 = 无（只读）。
**答案必须附明细链接**——不给依据的 AI 数字不能用于决策。」

交付物：四张新表、7 条受控意图、4 个端点（`/platform/assistant`）、一个真实工作区页面
（`/platform/assistant`）、25 个后端集成测试 + 22 个前端测试。

## 这条车道的前史（前两任 worker 零产出）

本模块被熔断过两次：前两任 worker 只做了依赖自检、**没落任何文件** ⇒ 按 `LANE_MAX_ATTEMPTS=2` 熔断。
熔断结论是「18.4 AI 域依赖面过宽、任务书颗粒度过粗」⇒ 本轮任务书被拆细（只做受控意图目录 +
AI 端口如实答 + 追问延续 + 下一步建议）。

本轮按「**先提交迁移骨架再写服务**」执行：**第一个 commit 是迁移骨架**（`683f09b8`，20:11），
之后按「意图目录与查询层 → 端点 → 前端 → 测试」增量提交。

## 决策 / 结论

### 1. 🔴 受控意图目录是「主体」，不是「包装」

蓝图要求「用中文提问业务问题」，但**没有要求**把自然语言直译成 SQL。
本模块据此把能力边界划在**封闭词表**上：7 条意图，每条 = 一个受控问题形态 → 一条**写死的只读查询**
→ 答案 + 明细链接 + 建议的下一步。

- 意图识别 = **确定性关键词匹配**（强词权重 3 / 弱词权重 1，同分按目录顺序）。
  没有模型参与 ⇒ 同一个问句永远得到同样的查询。
- 「直译任意自然语言 → 任意 SQL」是 **18.4.1.2 智能查询**的边界，本模块**刻意不碰**。
- 目录行**落在租户自己的表里**（`platform_assistant_intent`，首次访问时幂等落库）⇒
  「这个租户被允许问什么、问到的数据来自哪里」是可审计的租户资产，不是代码里的常量。

### 2. 🔴 明细依据是**一张表**，不是一个字符串字段

蓝图加粗句「答案必须附明细链接」如果只落成一段说明文字，等于「不可下钻的结论」——
事后没人能回答「这个 -18.4% 是哪一行算出来的」。

⇒ `platform_assistant_evidence` 每行带**事实定位四件套**（事实服务 / **真表名** / **真字段名** / 事实引用）
+ **前端真实路由** + 口径说明 + 快照时间。配套的结构性保证：

- 服务层**只输出有事实支撑的结论**；读不到事实 ⇒ 那一轮**根本不出结论**（`answered=false` + 说明哪一格读不到）。
- 前端口径层**比后端更严**：`answered=true` 但没有可点的站内明细链接 ⇒ 页面按「没结论」显示。

真表名举例：`reporting_profit_fact` / `reporting_inventory_analysis_position` /
`reporting_sales_trend_fact` / `reporting_lifecycle_segmentation_result` /
`finance_receipt` + `finance_payment_plan` / `shipment_pending_unit` / `crm_churn_alert`。

### 3. 🔴 自由问句**如实答「未接通」**，照抄 18.4.2.1 的既有模式

本仓**没有任何真实 LLM 适配器**（openai / anthropic / qwen 一个都没有）⇒ 自由问句走既有
`AiTextGenerationPort`，拿到 `connected=false` 就如实翻成「AI 生成通道未接通 + 缺什么 + 归谁
（ownerModule=18.4）」，**不返回任何看似成功的答案、不伪造数字**。

**与 18.4.2.1 的一处有意差别**：翻译模块在 AI 端口未接通时「一次数据都不写」；
本模块会落一行**问答台账**（问过什么、为什么没答）。理由：会话上下文与追问延续**要求**台账存在，
而那一行里 `answered=false`、`evidence` 为空 —— 落的是「没答」的记录，**不是答案**。

### 4. 🔴 相对时间必须与数据落在**同一个时钟域**

「上周」「本月」这类窗口用 **DB 的 `now()`** 取「今天」再推导（`now() at time zone 'UTC'`），
不拿 JVM 墙钟与 DB 时钟比大小 —— 跨时钟域比较会产出**恒定的假红**（2026-10-21 实测过这个坑）。

「上周」定义为**上一自然周（周一至周日）**（`TemporalAdjusters.previous(MONDAY)`），
「本月」定义为**自然月至今**。

### 5. 🔴 金额与数量全程**十进制文本**

后端把 `BigDecimal` 暴露成 `String`（`toPlainString()`）而不是 JSON 数字：
走 JSON number 会在浏览器里被解析成 double 而掉精度，而「差一分钱」在报表上是查不出来的。
与 18.4.3.1 的 `quantity` / `unitCost` 同一口径。比率 `NUMERIC(7,4)`，本模块**没有一个 double**。

### 6. 追问与上下文延续：**只替换被显式修改的维度**

`platform_assistant_session` 记租户 + 会话 id + 已识别意图 + 上下文指针（店铺 / 数据范围 / 时间范围）。

- 「那第二名呢」⇒ 沿用上一轮的意图与口径，只换名次（答案与依据换成第 N 条事实）。
- 「换成上周」⇒ 沿用上一轮的意图与数据范围，只换时间窗口。
- 没提时间的追问**沿用上一轮的窗口**（不是每次都重置成「最近 30 天」）。

上下文**只从已认证会话解析**：租户来自 `AccessService.require` 的已认证成员关系，
请求体出现 `tenantId` / `userId` / `membershipId` 会被 Jackson 的 `fail-on-unknown-properties` 直接 400。

### 7. 建议的下一步**只落在已存在的真实路由**上

7 条意图各给 1–2 条下一步，全部是前端**已存在**的页面
（`/analytics/profit/profit-report`、`/analytics/inventory/analysis`、`/analytics/sales/products`、
`/analytics/products/lifecycle`、`/finance/fund-analysis`、`/crm/customer-lifecycle`、
`/orders/fulfillment/pending-shipment`、`/analytics/dashboard/store-comparison`、`/inventory/stockout-alerts`）。
**编造路由比不给建议更糟**（点了 404 ⇒ 界面像是坏了）。

## 关键 trade-off

- **意图目录 7 条而不是 8 条**：每条都必须有**真实只读数据源**与**能点的明细链接**。
  宁少一条，也不收一个「看起来能问、其实查不出来」的意图。
- **「客户流失名单」指向 crm 的 `/crm/customer-lifecycle/alerts`**（而不是 reporting 的生命周期分层）：
  reporting 那一格是**商品（SKU）维度**的下行，不是客户维度。目录里因此同时有
  `CUSTOMER_CHURN`（客户维度，crm）与 `LIFECYCLE_DECLINE`（商品维度，reporting）两条，
  各自的口径说明里写明维度差异 —— **不混成一个「流失名单」**。
- **跨服务只读、绝不直连对方库表**：客户维度走 crm 的 HTTP 端点（转发调用方自己的 bearer），
  租户由上游按已认证成员关系解析 ⇒ 隔离是**两道**（platform 侧 RLS + 上游侧 RLS）。
- **api-proxy 白名单**：新增**一条独立** `platform/` 条目（首 token `assistant`），
  插入锚点选在 `ai/diagnosis`（18.4.2.3）之后 —— 避开同批其它车道用的「数组开头 / 数组末尾」两个锚点，
  降低并入时的冲突面。

## 文件清单

**迁移**

- `backend/services/platform/src/main/resources/db/migration/V438__platform_assistant.sql`（四表 + RLS + 触发器）
- `backend/services/identity/src/main/resources/db/migration/V438__platform_assistant_permission.sql`（权限码 + OWNER 回填）

**后端**（platform 服务，`/platform/assistant`）

- `AssistantModels.java`（传输模型）
- `AssistantIntentCatalog.java`（受控意图目录 + 确定性匹配 + 时间/名次识别）
- `AssistantService.java`（分派 / 会话上下文 / 落依据）
- `AssistantController.java`（4 个端点）
- `AssistantReportingPort.java` / `AssistantFinancePort.java` / `AssistantTradePort.java` / `AssistantCrmPort.java`（只读上游端口）

**前端**

- `apps/web/src/app/platform/assistant/page.tsx`
- `apps/web/src/components/platform/assistant-model.ts` / `assistant-model.test.ts` / `assistant-workspace.tsx`
- `apps/web/src/app/api/platform/assistant-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts`（新增一条 `assistant` 条目，既有条目一字未改）

**契约**：`packages/api-contracts`（codegen 产物，未手改）

**台账**：`docs/product/capability-blueprint/module-progress.md`（只改 18.4.1.1 那一行的「状态」「证据」两格）

## 验证

- L0 编译闸：`./mvnw -pl services/platform -am compile` ✅
- L0 类型闸：`pnpm typecheck` ✅；`prettier --check`（本车道文件）✅
- L1 模块闸：`./mvnw -pl services/platform -am test -Dtest='Assistant*'` ⇒ **25 例全过**
  （`AssistantIntegrationTest` 18 + `AssistantTenancyIntegrationTest` 7，真 Testcontainers PostgreSQL 16、FORCE RLS、连接池压到 1 条）
- 前端：`assistant-model.test.ts` 18 例 + `assistant-allowlist.test.ts` 4 例 ⇒ **22 例全过**
- api-proxy 全量 allowlist 回归：`pnpm vitest run apps/web/src/app/api` ⇒
  **292 文件 / 1655 例通过**；3 例失败为**基线既有**（catalog `price-lists` 一族的
  「未交付形状仍然 404」断言，与本车道无关 —— 去掉本车道的 api-proxy 改动后同样失败）
- 租户隔离三条断言：两租户互不可见（含按主键直查 + 拿到对方会话 id 也 404）/ 伪造 tenant header 无效
  （行仍落在**会话租户**名下）/ 连接池归还后不串租户（交替 5 轮）；
  另有一条独立断言钉住 FORCE RLS 与 `tenant_id NOT NULL`，以及「答案本身也按租户隔离」。

## 后续待办

1. **AI 供应商适配器仍未交付**（归 18.4 卷级基础设施）⇒ 自由问句继续如实答「未接通」；
   接上真实模型只需换 `AiTextGenerationPort` 的实现，本模块的服务层不用改。
2. **意图目录是封闭词表**：新增意图 = 后端加一条目录行 + 一条只读查询，**不改匹配逻辑**。
3. 明细链接上的 query 参数（`?shopCode=` / `?skuCode=` 等）是**下钻提示**，
   目标页是否据此预筛由各域页面自行决定，本模块**不代它们改页面**。
4. 本模块**只读**（蓝图关系句：下游 = 无）—— 不写任何业务域的数据，也不提供任何执行动作端点。
