# 2026-11-02: 15.3.1.2 客户详情（统一时间线 · 跨店铺身份线索）

## 摘要

蓝图 15「客户与客服」→ 15.3「客户主档」→ 15.3.1「客户主档」→ 15.3.1.2「客户详情」落地。

车道分支 `feat/15-3-1-2`，迁移版本号由调度者预分配为 **V435**
（`backend/services/crm/src/main/resources/db/migration/V435__crm_customer_detail.sql`，
crm 服务第二十一个迁移；V432 是 15.3.1.1 客户列表，V436 是 15.6.2.1 生命周期，本车道**一个字都没动**它们）。

蓝图原文：「**职责**：客户全档案：基本信息、跨店铺身份关系、订单历史、售后历史、消息历史、评价、
标签、接触记录、风险与价值分层。**输入**：`customer_id`；聚合各域数据。**输出**：详情视图 +
时间线（订单/消息/售后合并时间线）。**关系**：上游 = 15.3.1.1；下游 = 9 订单、11 售后、15.1 消息。
**统一时间线是客服理解客户的唯一高效方式** —— 跨系统翻找会显著拖慢响应。」

交付物：两张新表、三个端点、一个真实工作区页面（`/crm/customers/[id]`）、
14 个后端集成测试（含 AGENTS.md 的租户隔离三断言）+ 26 个前端测试。

## 决策 / 结论

### 1. 🔴 时间线**物化**在本模块自己的表里，而不是每次请求现算

蓝图加粗句的后半句（「跨系统翻找会显著拖慢响应」）反过来给了一个硬约束：
详情页打开时**不能**为了拼一条时间线去串行地打 5 个上游（订单 / 售后 / 消息 / 评价 / 接触记录）。

⇒ 时间线落在 `crm_customer_timeline_entry`，重建是**显式动作**
（`POST /crm/customers/{id}/timeline/rebuild`），详情页**一次查询**拿到全部。

三条配套纪律：

- **读端点不写库**：GET 端点**不做**任何写入。自动建立时间线由前端在首次打开时
  调 POST 触发（需要 `crm.customers.update`）—— 读权限不引发写入。
- **重建幂等**：唯一键 (租户, 客户, 来源类别, 来源标识)；重建前先删掉该来源的旧条目
  再落新的 ⇒ 上游撤掉的单子不会留在时间线上，重建两次条目数不变。
- **排序统一时钟域**：`occurred_at DESC, indexed_at DESC, id DESC` ——
  后两个键都由 **DB 时钟**写；运行记录的 `started_at` / `finished_at` 也从 DB 取 `now()`，
  **不拿 JVM 墙钟和 DB 时钟比大小**。

### 2. 🔴「读不到」与「没有数据」是**两件事**

上游读不到 ⇒ 该来源标 `available=false` 并写明原因与归属模块，
**一条条目都不落**（绝不编数据）；整次运行的状态是 `PARTIAL`（部分来源读不到）
或 `FAILED`（一条都没落，`entry_count` 必须为 0 —— DB 触发器 `guard_crm_customer_timeline_run_result` 强制）。

这条在**前端**同样被钉死：`customerDetailSourceText()` 在不可用时输出
「不可用（原因）」而**不是**「0 条」；画成 0 条等于替上游说「这个人从来没下过单」。

### 3. 🔴 跨店铺身份关系按「合并映射尚未交付」的形态实现

15.3.1.3 拥有 `crm_customer_merge_*` 系列表（逻辑客户 / 合并映射 / 候选 / 操作记录 / 运行台账）。
本模块**不建、不读、不写**其中任何一张，也没有造一张「假的合并表」或「假的映射读取路径」。

⇒ 合并状态恒为 `NOT_MERGED`，归属写明 `15.3.1.3`；跨店铺身份关系只列**线索**
（同租户下另一行客户主档的**脱敏联系方式指纹**或**买家名**一致）。
**线索不是合并结论** —— 同名可能是两个人，掩码邮箱也可能是同一个人；
本模块不判定，判定与落库是 15.3.1.3 的事。前端文案层也钉死了这一点
（`customerDetailIdentityText()` 的断言里明确「绝不出现『已识别为同一人 / 已合并』」）。

### 4. 🔴 顺带修掉一个**真 bug**：查询串里的字面 `+` 会让整个售后窗口作废

售后扫描的时间窗原本直接发 `OffsetDateTime.toString()`。东八区会给出
`2026-10-09T08:00+08:00` —— 其中的 `+` 在查询串里是**字面加号**
（URI 的 query 允许 sub-delim，编码器不会动它），而上游用 `URLDecoder` 解码时
把 `+` 当成**空格**：上游拿到 `2026-10-09T08:00 08:00`，解析失败，
**整个售后窗口作废**（表现为「售后一条都没有」或「trade 服务不可达」）。

⇒ 本模块的端口改为一律发 **UTC** 的 ISO-8601（`withOffsetSameInstant` 保持**同一个绝对时刻**，
窗口语义不变，形如 `2026-10-09T00:00:00Z`，没有 `+`）。

**这条不是测试造出来的** —— 15.3.1.1 之所以没踩到，是因为它自己用
`OffsetDateTime.now(ZoneOffset.UTC)` 造窗口。

### 5. 🔴 售后归因窗口是**退货期**，不是缓冲

第一版按「订单时刻 ± 1 天」算窗口，结果「这一单后来退了」这条事实
**根本不会出现在时间线上** —— 而售后恰恰是客服最需要看到的那一段
（买家下单后**几周**才申请退货是常态，跨境更久）。

⇒ 窗口改成「订单时刻**前** 7 天 ~ 最后一张订单**后** 90 天」。
窗口变宽 ⇒ 一次扫描要翻的页更多 ⇒ 触到 `MAX_PAGES` 时如实报 `truncated`
（时间线不是全量），而不是悄悄少算。

### 6. 跨服务取数：订单按 `customerPartyId` 精确筛，不做全量扫描

trade 的 `GET /trade/sales-orders/list` **支持**按 `customerPartyId` 筛，
比全量扫描快一个数量级 ⇒ 本模块另起一条**自己的**只读端口
（`CustomerDetailFactsPort`），**一个字都不改**共享的 `CustomerFactsPort`
（AGENTS.md：并行车道只碰自己的文件；15.6.2.1 也是这么做的）。

售后单**只带 `orderId`**，没有买家维度 ⇒ 归因链路是
「售后单 → orderId → 订单 → customerPartyId → 客户」：
按客户首末订单时刻整段拉售后单，再在**服务层**用该客户的订单 id 建索引过滤，
本库**不建任何指向 trade 的外键**。

### 7. 契约重生成的一个坑：**必须导出活体 spec，不能拿已提交的快照当输入**

`packages/api-contracts/src/*.openapi.json` 是**键名排序**的快照。
拿它当 `--spec-dir` 的输入再跑一次 codegen，另外八个服务的 `.ts` 会被
**整体重排**（实测 -6000 行噪声 diff）—— 并入时会与并行车道大面积冲突。

⇒ 逐个跑了九个服务的 `OpenApiContractTest` 导出**活体** spec 后再生成，
结果**只有 crm 变了**（`crm.ts` +10 行 / `crm.openapi.json` +547 行），零存量漂移。

### 8. 一处**必须动别人文件**的地方（并已说明理由）

`apps/web/src/app/api/crm/customers-allowlist.test.ts`（15.3.1.1 拥有）里有一条负向断言：
`crm/customers/{uuid}` 必须 **404**（注释写着「客户详情是 15.3.1.2 的职责（未交付）」）。

15.3.1.2 交付后这条**事实性过期** —— 继续钉住 404 会把**已交付**的详情页判成红。
⇒ 只删了这一条负向断言（并加注释说明它为什么过期、15.3.1.2 的白名单测试在哪反向钉住它），
其余断言与既有形状**一字未改**。

### 9. `lastRun` 的可空口径：改前端类型，不碰共享 codegen

SpringDoc **不会**给 `$ref` 类型的属性标 `nullable`（注解在 `$ref` 上被忽略），
于是 codegen 产出的 `CrmCustomerDetailTimelinePage.lastRun` 是**必填**，
而后端在没有运行记录时确实会返回 `null`。

⇒ **不改共享的 codegen**（那会牵动全部九个服务与并行车道）、**不手改契约**，
而是在前端模型里把这一处口径收窄：
`Omit<CrmCustomerDetailTimelinePage, 'lastRun'> & { lastRun?: … | null }`。

## 文件清单

**迁移**
- `backend/services/crm/src/main/resources/db/migration/V435__crm_customer_detail.sql`（新建）

**后端**
- `backend/services/crm/src/main/java/com/starcon/crm/CustomerDetailModels.java`（新建）
- `backend/services/crm/src/main/java/com/starcon/crm/CustomerDetailFactsPort.java`（新建）
- `backend/services/crm/src/main/java/com/starcon/crm/CustomerDetailService.java`（新建）
- `backend/services/crm/src/main/java/com/starcon/crm/CustomerDetailController.java`（新建）
- `backend/services/crm/src/test/java/com/starcon/crm/CustomerDetailIntegrationTest.java`（新建，10 例）
- `backend/services/crm/src/test/java/com/starcon/crm/CustomerDetailTenancyIntegrationTest.java`（新建，4 例）

**契约（codegen 产物，未手改）**
- `packages/api-contracts/src/contracts/crm.ts`
- `packages/api-contracts/src/crm.openapi.json`

**前端**
- `apps/web/src/components/crm/customer-detail-model.ts`（新建）
- `apps/web/src/components/crm/customer-detail-model.test.ts`（新建）
- `apps/web/src/components/crm/customer-detail-workspace.tsx`（新建）
- `apps/web/src/app/crm/customers/[id]/page.tsx`（新建）
- `apps/web/src/app/api/crm/customer-detail-allowlist.test.ts`（新建）
- `apps/web/src/lib/api-proxy.ts`（**追加**一条独立的 services[] 元素；既有条目一字未改、也不重排）
- `apps/web/src/app/api/crm/customers-allowlist.test.ts`（删掉一条**已过期**的负向断言）

**文档**
- `docs/product/capability-blueprint/module-progress.md`（**只改** 15.3.1.2 那一行的「状态」「证据」两格）
- `docs/sessions/2026-11/2026-11-02-crm-customer-detail.md`（本文件）

## 验证

```bash
# L0 编译闸
cd backend && ./mvnw -o compile                      # BUILD SUCCESS
# L0 类型闸
cd <worktree> && pnpm typecheck                      # Tasks: 3 successful, 3 total
# L0 格式自检
pnpm exec prettier --check <改动文件>                 # All matched files use Prettier code style
# L1 模块闸（只跑本模块新增的测试类）
cd backend && ./mvnw -pl services/crm -am test \
    -Dtest='CustomerDetail*' \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
#   ⇒ Tests run: 14, Failures: 0, Errors: 0（10 + 4），BUILD SUCCESS
# 前端
pnpm vitest run apps/web/src/components/crm/customer-detail-model.test.ts
pnpm vitest run apps/web/src/app/api/crm/customer-detail-allowlist.test.ts
# 全量 allowlist 回归
pnpm vitest run apps/web/src/app/api
```

## 后续待办

1. 🔴 **共享的 `CustomerFactsPort`（15.3.1.1 拥有）有同样的 `+` 风险** ——
   它的 `orders()` / `aftersalesCases()` 也直接发 `from.toString()`。
   15.3.1.1 自己用 UTC 窗口所以没暴露，但它**允许调用方传窗口入参**
   （`POST /crm/customers/sync` 的 `windowFrom` / `windowTo`）⇒
   传一个带 `+08:00` 的窗口进去，整个窗口会作废。
   **本车道没有改它**（共享文件 + 并行车道纪律）⇒ **请调度者转给 15.3.1.1 车道**。
2. **15.3.1.3 跨店合并**交付后，「跨店铺身份关系」段要由它增强
   （合并映射 + 逻辑客户）：本模块的 `NOT_MERGED` 与线索段届时成为下游增强，
   **不是**本模块开工的前置条件。
3. **平台评价**目前只有人工登记这一半（`crm_product_review`）——
   `PlatformReviewPort` 对所有平台都答「未接入」（channel 服务只有 OAuth 适配器）。
   本模块的评价段如实按「同服务只读 + 人工登记」实现；平台评价接通后由它增强。
4. **消息 / 评价 / 接触记录与客户主档的关联**走的是**逻辑引用**
   （`buyer_reference` / `buyer_ref` / `customer_ref` = 客户标识字符串）。
   本仓没有买家档案（3.6.1.1 的 party 主数据在 catalog 服务），
   标识对不上时这些来源**匹配不到** —— 本模块不猜、不做模糊匹配。
5. `crm_customer_timeline_entry` 的 `payload` 存的是**上游原始载荷快照**，
   详情页目前只展示摘要字段；将来要做「展开原始报文」需要再定一版展示口径。
