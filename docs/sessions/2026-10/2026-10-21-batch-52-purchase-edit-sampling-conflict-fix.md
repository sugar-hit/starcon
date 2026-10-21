# 2026-10-21 batch-52 —— 6.3.1.2 采购单创建与编辑 + 6.6.1.2 打样与样品管理

> 批次 `batch-52`，集成头 `integration/module-dev`（并入前 `bf274fab` → 并入后 `caffaa91`）。
> 本批 3 条待并车道里 **2 条并入成功**（6.3.1.2 / 6.6.1.2），
> **1 条冲突未并入**（6.3.2.1 跟单与交期 —— `apps/web/src/lib/api-proxy.ts` 同锚点插入冲突，
> 已按「修复任务」重新派发，见下文「本批并入冲突与处置」）。
> L2 批量验证在集成头上跑**一次**全量。
> 车道级详细决策记录在各车道自己的会话日志里，本文只做批次级说明。

## 本批概览

| 模块 | 名称 | 阶段 | 服务 | 迁移 | 后端端点 | 页面 | 测试 | 并入 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 6.3.1.2 | 采购单创建与编辑 | D6 | trade | `V258__purchase_order_create_edit.sql` | `/trade/purchase-order-editor`（8 条路径） | `/purchase/orders/new`、`/purchase/orders/{id}/edit` | 后端 20 例 + 前端 model/allowlist | ✅ |
| 6.6.1.2 | 打样与样品管理 | D6 | trade | `V261__product_development_sample.sql` | `/trade/development-samples`（17 条路径 / 25 个动作） | `/purchase/development/samples` | 后端 62 例 + 前端 23 例 | ✅ |
| 6.3.2.1 | 跟单与交期 | D6 | trade | `V259__purchase_followup_delivery.sql`（**沿用**，未新建） | `/trade/purchase-followups` | `/purchase/followups` | 后端 27 例 | ⚠️ 冲突，重派修复 |

两条成功并入的车道**全部落在 trade 服务**（卷 06 后端主责服务 = trade + catalog（party），取列表第一项 = trade），
迁移版本号 **V258 / V261** 互不撞号。
⚠️ **V259 / V260 未被本批占用** —— V259 属于冲突重派的 6.3.2.1（**沿用它原本的版本号，不新建 V263**），
V260 属于仍在在途的 7.4.1.1 调整单管理。**下一批的车道从 V262 起**，不得手工指定版本号。

---

## 模块 6.3.1.2 采购单创建与编辑

**这个功能做什么**：采购员能真的把一张采购单建起来 —— 选供应商、加 SKU 行（自动带出报价、箱规、可用库存快照）、
填数量单价币种、约定交期与发货地址、传附件、然后提交送审。
提交那一刻把单价、汇率、单位换算**冻结成快照**，之后报价怎么变都不影响这张单的成本口径；
编辑是整体替换语义 + 乐观锁，两人同时改不会互相覆盖。

**技术落地**：

- 迁移：`V258__purchase_order_create_edit.sql` —— **五张新表 + 3 个函数 + 1 个触发器**；
  对 6.3.1.1 的 V254 三张表**零 ALTER、零 DROP、零改既有触发器**：
  `purchase_order_line_snapshot`（行级快照：单位换算 / 报价 / 库存 / 税额口径，提交时冻结）·
  `purchase_order_submit_snapshot`（单级提交快照：本位币与汇率，提交时冻结）·
  `purchase_order_ship_address`（发货地址）·
  `purchase_order_attachment`（附件：catalog 媒体库**逻辑引用 + 元数据快照**，不建跨库 FK）·
  `purchase_order_draft_source`（6.2.2.2 合并草稿 → 采购单的来源留痕，追加式）。
  全部 `tenant_id NOT NULL` + ENABLE/FORCE RLS isolation + GRANT。
  对 V254 `purchase_order_line` 只有两处**纯增量**：`GRANT DELETE` **只放行行表**（单头 `purchase_order` 仍不可删 ⇒
  6.3.1.1 的保护原封不动）+ 新增 `guard_purchase_order_line_delete_guard` 触发器钉死「**只有草稿能删行**」
  （V254 既有 guard 只挂 INSERT OR UPDATE，管不到 DELETE）。
- 端点：`/trade/purchase-order-editor`（**自有命名空间，与 6.3.1.1 的 `/trade/purchase-orders` 刻意分开**，
  让多车道共用的 api-proxy 白名单各自登记而不互相覆盖）：
  GET `/capabilities`（逐条标 available 与归属模块：6.3.1.3 审批 / 12 财务应付与税额 = **false**）·
  GET `/options`（供应商 / 目标仓 / 法人 / 币种，四样都是**真实数据**不是硬编码词表）·
  POST `/quote-preview`（带出报价 + 箱规 + 库存快照，只读无副作用）· POST `/orders`（建草稿，`source_type=MANUAL`）·
  GET `/orders/{uuid}`（编辑表单：头 + 行 + 冻结快照 + 地址 + 附件 + 金额结果）·
  PUT `/orders/{uuid}`（**整体替换**语义：少传一行 = 删掉那一行；`expected_version` 乐观锁不匹配 **409**）·
  POST `/orders/{uuid}/submit`（草稿 → 待审批，冻结单价 / 汇率 / 单位换算）·
  POST `/from-drafts`（由 6.2.2.2 合并草稿建单并把草稿置 CONVERTED，被拒草稿逐条给原因码）。
- 页面：`/purchase/orders/new` 与 `/purchase/orders/{id}/edit`
  （`app/purchase/orders/new/page.tsx` + `app/purchase/orders/[id]/edit/page.tsx` +
  `components/purchase/purchase-order-edit-workspace.tsx` + `purchase-order-edit-model.ts`）——
  四个金额卡（服务器口径总额 / **本页试算**（标明不是入账额）/ 本位币折算（无汇率快照显示「无数据」）/
  税额（归 12 财务算））+ 报价候选逐条可选 + 提交后的冻结快照面板 +
  **能力边界区**（未交付的下游原样列出）+ 加载 / 空 / 错误三态。
- 测试：`PurchaseOrderEditIntegrationTest` **16 例** + `PurchaseOrderEditTenancyIntegrationTest` **4 例**
  = **20 例实跑 Testcontainers PostgreSQL 16**（连接池压到 1）；前端 model 单测 + allowlist 双向断言。

**关键设计决策**：

1. 🔴 **汇率快照在提交那一刻冻结；取不到就是没有。** 草稿期间与取不到时一律 NULL，
   **绝不按 1:1 或固定汇率填一个数进去** —— 猜一个汇率会让成本核算整体偏移，
   而采购单是应付与收货对账的源头。页面对应呈现「无数据」而不是一个假数字。
2. 🔴 **税额恒为 NULL，不造税率表。** 税率的权威在 12 财务，而 `backend/services/` 下**没有 finance 服务**
   ⇒ 本模块只算**本位币折算快照**。同理金额与行数由 DB 触发器算，**调用方传不进来** ——
   「头金额与行金额对不上」不是代码路径而是不可能发生的事。
3. 🔴 **歧义报价不替用户挑。** 带出时并列候选逐条列出且 `selected=null`，
   提交时走 catalog `GET /catalog/supplier-quotes/effective/resolve` 严格取价，歧义直接 **409**，
   **且单仍是草稿**（提交没有半途成功）。
4. 🔴 **单位换算不猜成 1。** 取不到即 UNKNOWN + NULL —— 猜成 1 会让订量差一个数量级。
5. 🔴 **草稿 id 绝不塞进 `purchase_order_line.source_conversion_id`。** 那一列指向 6.2.2.1 的转单事实且带唯一键，
   混写会既混淆语义又可能误撞；草稿来源只记在本模块自己的 `purchase_order_draft_source`。
6. 🔴 **只有草稿能删行。** 既有 guard 管不到 DELETE，所以补了一个新触发器 ——
   绕过服务层直连 SQL 同样删不掉。

---

## 模块 6.6.1.2 打样与样品管理

**这个功能做什么**：新品开发阶段要打样 —— 这次打样做几个、用什么规格、寄到哪个供应商那边、
花了多少钱、做出来是什么样、最后确认通过 / 修改 / 放弃。
系统把「一次打样活动」（打样单）和「打出来的每一件样品」（样品台账）分成两层记账，
所以随时能答出「这张单打了几个样、其中几个确认通过」；物流轨迹与费用入账拿不到就**明说拿不到**，
不拿假接口糊弄。

**技术落地**：

- 迁移：`V261__product_development_sample.sql` —— **九张新表、零 ALTER 零 DROP 既有表**（跨车道 DDL 回归面 = 0）：
  `development_sample_order`（**打样单 = 一次打样活动**：编号 / 来源 / 用途 / 状态 / 币种 / 申请人 / 期望到样）·
  `development_sample_order_transition`（打样单状态迁移事件）·
  `development_sample`（**样品台账 = 样品实体，一件一行**：规格 / 数量 `NUMERIC(19,6)` / 状态 / 打样轮次）·
  `development_sample_transition`（制作进度事件）· `development_sample_confirmation`（**确认记录**：通过 / 修改 / 放弃）·
  `development_sample_shipment`（物流快照）· `development_sample_media`（样品图片：catalog 媒体库逻辑引用 + 元数据快照）·
  `development_sample_cost`（成本头：**一个打样单一张、一个币种**）· `development_sample_cost_line`（成本明细行）。
  全部 `tenant_id uuid NOT NULL` + ENABLE/FORCE RLS + GRANT，**零跨库 FK、零跨库视图、零跨服务 SQL**。
- 端点：`/trade/development-samples/**` —— **17 条路径 / 25 个动作**（jOOQ 显式 SQL，不用 JPA 懒加载）：
  capabilities / options / summary / 打样单清单（真分页 + 真筛选）/ 新建打样单 / 详情 /
  改打样单（PUT 整体替换）/ 推进状态 / 样品台账 / 登记样品 / 样品详情 / 改样品 /
  推进制作进度 / 确认结果 / 确认记录 / 物流快照 / 样品图片 / 媒体库候选（跨服务只读）/
  样品成本 / 追加成本行 / 删成本行。
- 页面：`/purchase/development/samples` —— 真实工作区（非占位页），三态齐全：
  能力缺口横幅（**12 费用 / 10 物流 / 6.6.2.1 / 达人寄样四条常驻**）+ 状态概览 +
  打样单 / 样品台账双页签 + 筛选 + 清单 + 详情弹窗 + 新建打样单表单
  （立项下拉取自 6.6.1.1 真实端点、供应商下拉取自 catalog 真实端点、档口下拉取自 6.1.2.1 真实端点）。
- 测试：`DevelopmentSampleRulesTest` **28 例** + `DevelopmentSampleIntegrationTest` **31 例** +
  `DevelopmentSampleTenancyIntegrationTest` **3 例** = **62 例实跑 Testcontainers PostgreSQL 16**；
  前端 `development-sample-model.test.ts` **16 例** + `development-sample-allowlist.test.ts` **7 例**。

**关键设计决策**：

1. 🔴 **打样单是「次」、样品台账是「件」，1 : N，不糊进一张表。**
   糊进去就答不出「这张单打了几个样、其中几个确认通过」。
2. 🔴🔴 **打样 vs 达人寄样分得干干净净**：**来源 / 用途是两个维度**。
   `source_type` 封闭三值（SUPPLIER / OWN_FACTORY / STALL），`usage_type` **只有 DEVELOPMENT 一个值**，
   DB CHECK 在**存储层**拒绝其它用途。
   词表里**没有「其它」桶也没有营销寄样** —— 留一个「其它」就等于把达人寄样（14.5.1.2）塞进来，
   而那正是蓝图担心的「营销费用污染开发成本 ⇒ 新品利润被高估」；
   达人寄样同样来自供应商，**光看来源分不出来**，靠 `usage_type` 分。
3. 🔴 **确认结果必须由一条不可改写的确认记录授权。** 确认类状态走 `POST .../confirmations`
   （服务层先落记录再改状态，DB 守卫 `guard_development_sample_status()` 再挡一道）——
   没有对应记录的状态变更**在数据库层就做不到**。三张留痕表 append-only（UPDATE / DELETE 被触发器挡 + REVOKE）。
4. 🔴 **物流只记快照。** 真实物流轨迹属 10 物流 ⇒ `tracking_available` **恒为 false** +
   `tracking_owner_module='10 物流'`，**不硬造一个「物流已接入」的假接口**。
5. 🔴 **样品成本落本库，但「入账」如实标不可用。** 12 费用（finance 服务在本仓不存在）⇒
   成本头 **DB CHECK 钉死 `expense_posted = false`** + `expense_owner_module='12 费用'`，
   **不假装已经入账**，也**不造一张「费用」表**去冒充 12 财务的交付物。
   页面文案是「尚未进费用归集（归 12 费用管）」而不是「已入账」。
6. 🔴 **成本一单一币种，跨币种绝不相加**（触发器挡住明细行币种与成本头不一致）；合计金额由 DB 触发器按明细行维护。
7. 🔴 **上游只读引用 + 展示快照，零 ALTER**：6.6.1.1 开发立项（同库）只 SELECT 存 id + 编号 / 名称快照；
   供应商（catalog 独立库）与样品图片（catalog 媒体库）走**只读端口 + 快照**，
   **不采信客户端自报的供应商名**。

---

## 本批并入冲突与处置（6.3.2.1 跟单与交期）

**症状**：6.3.2.1 车道的分支已落地（L0 编译绿 / L1 27 例全绿 / typecheck 绿 / 前端 562 例全绿），
但 `merge-ready` 在 `apps/web/src/lib/api-proxy.ts` 上报冲突，车道被标 `conflict`，未并入。

**根因（调度者用 `git merge-tree` 取证，非猜测）**：集成头先后并入了两个兄弟模块，
**它们都把新 `services[]` 元素插在同一个位置** —— 都在「`6.3.1.1 采购单列表`（`purchase-orders`）元素之后」：

| 车道 | 插入位置 | 正则首 token |
| --- | --- | --- |
| 6.3.1.2 采购单创建与编辑 | `purchase-orders` 元素之后 | `purchase-order-editor` |
| 6.3.2.1 跟单与交期 | `purchase-orders` 元素之后 | `purchase-followups` |

两个元素插在同一锚点 ⇒ git 无法自动求并。
**这是结构性的，retry 不会自愈** —— 两条车道当初各自检查「锚点与其它车道不重叠」，
但没意识到**彼此重叠**。

**处置**：按「定位到具体车道后派一个**修复任务**（只修这一个模块，不要重写）」的既定处置路径，
本轮把 6.3.2.1 **重新派发**为一条冲突修复任务，而不是让它重新实现一遍：

- 任务书明确要求 **rebase 到当前集成头 `caffaa91`**，并把冲突块手工整理成**两个元素并存**
  （`purchase-orders` → `purchase-order-editor` → `purchase-followups`），
  契约与台账按「模块 ID / 类型名 / OpenAPI 路径」求并，不取单边。
- 🔴 **明确禁止新建 `V263`**：`plan` 脚本给重派的车道分配了 `V263`，但那是过期信息 ——
  本车道自己的迁移 `V259__purchase_followup_delivery.sql` 已在分支上且内容完整。
  若 V259 与 V263 并存，Flyway 会把两份都跑一遍 ⇒ 建重复表/重复约束 ⇒ **整批 L2 全红**，另外三条车道一起陪葬。
  任务书要求 worker 在交付报告里**明确回答「没有建过 V263」**。
- 重派后仍按 L0 + L1 + **api-proxy 全量 allowlist 回归**复验（`pnpm vitest run apps/web/src/app/api/trade`），
  因为取错一侧会静默删掉别人已交付的路由，表现是页面 404，而 typecheck / lint / vitest **全绿**。

**根因层面的预防**：本轮另外 4 条新车道**各自被指派了互不重叠的 api-proxy 插入锚点**
（6.3.1.3 → `6.4.2.2 不良品处理` 之后 ≈5339 行；6.6.2.1 → `6.6.1.2 打样与样品管理` 之后 ≈4078 行；
11.4.1.1 → 文件末尾；6.3.2.1 → 保持 `6.3.1.1 采购单列表` 之后 ≈3785 行），
并在任务书里把**另外三条车道的锚点行号也写了出来**，让每条车道都能自查有没有撞车。

---

## 本批新派发车道（在途，不属本批交付）

| 模块 | 名称 | 阶段 | 服务 | 迁移 | api-proxy 锚点 | 状态 |
| --- | --- | --- | --- | --- | --- | --- |
| 6.3.1.3 | 采购单审批 | D6 | trade | `V262` | `6.4.2.2 不良品处理` 之后 | 在途 |
| 6.3.2.1 | 跟单与交期（冲突修复） | D6 | trade | `V259`（**沿用**） | `6.3.1.1 采购单列表` 之后 | ✅ **已修好并 `land`**（待下一轮并入） |
| 6.6.2.1 | 试产单 | D6 | trade | `V264` | `6.6.1.2 打样与样品管理` 之后 | 在途 |
| 11.4.1.1 | 纠纷列表 | D7 | trade | `V265` | 文件末尾 | 在途 |

另有 **7.4.1.1 调整单管理**（V260）自上一批起仍在在途，本轮未干预。

### 6.3.2.1 冲突修复任务的实际结果（本轮内完成）

修复任务在 16:24 前后跑完并成功 `land`（`state: ready`），交付要点：

- `git rebase caffaa91` 后在 `api-proxy.ts` 上按预期停下，人工把冲突块整理成**两个元素并存**：
  `purchase-orders`（3742）→ `purchase-order-editor`（3786，6.3.1.2 的，**逐字节未变**）→
  `purchase-followups`（3839，本模块的）→ `fulfillment/consolidation`（3880）。
  任务书要求的证据口径已兑现：**6.3.1.2 的元素一个字符都没丢**。
- 契约按 key 求并：trade `trade.openapi.json` 668 路径 / 1481 schema → **674 / 1496**（+6 路径 / +15 schema，**零丢失**）；
  identity / catalog / channel / warehouse / crm 的契约**逐字节不变**。
- 台账按模块 ID 求并，与集成分支的差异**只有 6.3.2.1 那一行**。
- 复验全绿：`./mvnw -q compile` 退出 0；`pnpm typecheck` 3/3；
  L1 `PurchaseFollowup*` **27 例**（23 + 4 租户）0 失败；**api-proxy 全量 allowlist 回归 60 文件 / 330 例全绿**；
  模块自身前端测试 1 文件 / 14 例全绿。
- **未建 V263**（`git ls-files | grep -c V263` = 0），只保留 V259 ——
  任务书里那条「plan 分配的 V263 是过期信息」的警告被验证为真。


---

## L2 批量验证结果

在集成头 `caffaa91` 上跑**一次**全量（`.dev-agent/l2-run.sh`，16:30:33 → 17:10:15，耗时 40 分钟）：

| 阶段 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `backend/./mvnw verify` | ✅ **MVN_EXIT=0** —— identity 270 / catalog 1981 / channel 1244 / warehouse 1588 / trade 1983 / crm 70 = **7136 例，0 失败 0 错误** |
| 前端类型 | `pnpm typecheck` | ✅ **TYPECHECK_EXIT=0**（3/3 successful） |
| 前端 lint | `pnpm lint` | ✅ **LINT_EXIT=0**（3/3 successful） |
| 前端测试 | `pnpm test` | ✅ **TEST_EXIT=0** —— **395 个测试文件 / 5980 例全绿** |
| Next 构建 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ **BUILD_EXIT=0** |
| 台账一致性 | `node tools/module-progress/sync.mjs --check` | ✅ **SYNCCHECK_EXIT=0** —— 「✓ 台账与蓝图一致」 |

### ⚠️ 第一次 L2 是「假红」，本轮重跑一次才拿到绿

**第一次 L2（16:16:59 → 16:23:38）红在 `catalog` 服务**：
`TitleLibraryIntegrationTest` 13 个用例全报
`NoClassDefFound: Could not initialize class` / `ExceptionInInitializerError`（`<clinit>:121`）。

**根因是基础设施，不是代码回归**，三条独立证据：

1. **异常签名是连接超时，不是断言失败**：
   `Caused by: java.net.SocketTimeoutException: Read timed out`
   → `org.postgresql.core.v3.ConnectionFactoryImpl.enableSSL` ⇒
   PG 容器在**建连握手阶段**就超时了，测试类的静态初始化器根本没跑起来。
   断言失败会给出期望值 vs 实际值，这里一个都没有。
2. **本批 diff 对 catalog 后端一行未改**：
   `git diff --name-only bf274fab caffaa91 | grep '^backend/services/catalog/'` ⇒ **0 个文件**。
   本批两条已并入车道都是 trade 服务（V258 / V261）。**catalog 的代码没被碰过，不可能是本批改坏的。**
3. **当时容器数超限**：L2 跑的同时 4 条车道各自在跑 Testcontainers，
   实测存活 **11 个 `postgres:16-alpine`**（`PG_CONTAINER_LIMIT = 6`）⇒ Docker 被挤爆，
   容器起来得慢，连接握手超时。

**处置**：按「L2 红了不 push、不在集成头上改」的要求，**没有 push、没有二分、没有改任何代码**；
先确认 4 条车道都不在测试阶段（容器数归 0、load 2.5）后，**在 16:30 重跑了一次全量**。
第二次跑容器数全程 ≤ 8（多数时间 1–3），**catalog 一路过、trade 1983 例全绿**，L2 全绿。

⇒ **本轮只消耗了 1 次全量验证**（第一次红 + 一次重跑），未超过协议上限；
`git bisect` **未使用** —— 因为变更集里根本没有 catalog 的代码，二分定位不到任何东西，
属于协议里「二分找不到的 = 跨车道互相破坏」之外的第三类：**环境假红**。

⚠️ **给下一轮的建议**：L2 跑之前先看一眼容器数。
若 `docker ps --filter "label=org.testcontainers.sessionId" | wc -l` 明显高于 `PG_CONTAINER_LIMIT`，
**先等车道让出容器再跑 L2**，否则大概率复现同一个假红。


## 台账证据格缺陷与修复（6.3.1.2 行）

本轮发现并修复了一个**会静默吞掉整行交付记录**的台账缺陷，根因与修复如下。

**缺陷**：`6.3.1.2` 车道的证据格里写了
`purchase-order-editor/{capabilities | options | quote-preview | from-drafts | orders | orders/{uuid} | orders/{uuid}/submit}`
—— **证据格内部带了 6 个裸 `|`**。这让该行被切成 **12 个字段**（正常是 6 个），
既破坏 markdown 表格的列对齐，也**直接命中 `sync.mjs` 的行解析正则的失效条件**：

```js
// tools/module-progress/sync.mjs:192
const row = /^\|\s*(\d+\.\d+\.\d+\.\d+)\s*\|([^|\n]*)\|([^|\n]*)\|([^|\n]*)\|([^|\n]*)\|([^|\n]*)\|/gm;
```

该正则要求每行**恰好 6 个字段 + 结尾竖线**；字段数不对的行**匹配失败 → 整行被跳过** ⇒
`prev` 取不到 ⇒ 状态回落成「未开发」、证据被抹平成 `—`。
**后果**：只要跑一次 `sync.mjs`，`6.3.1.2` 就会从「已交付」**静默退回「未开发」**，
它那一整条 6000+ 字的交付证据（迁移口径、端点清单、测试覆盖、设计决策）**全部消失**。

**取证**：本批 465 行里，字段数异常的行**只有 6.3.1.2 这一行**（其余 464 行都是干净的 6 字段），
所以这是**本批车道新引入**的格式缺陷，不是历史遗留。

**修复**：把证据格内部的 ` | ` 改成 ` / `（**纯分隔符替换，不改任何结论内容**），
使该行恢复成合法的 6 字段行。修复后：

| 校验项 | 修复前 | 修复后 |
| --- | --- | --- |
| 该行字段数 | 12 | **6** |
| `sync.mjs` 是否匹配该行 | ❌ 匹配失败 | ✅ 匹配，status = 已交付 |
| `sync.mjs --check` | ✗ 台账与蓝图不一致 | ✅ 台账与蓝图一致 |
| 该行证据字节数 | 0（被抹平成 `—`） | **6330（完整保留）** |
| 全表字段数异常行数 | 1 | **0** |

⚠️ **给后续车道的教训**：证据格里**不要写裸 `|`**（路径枚举用 `/` 或 `、` 分隔）。
`sync.mjs` 的行解析是**严格 6 字段**的，多一个 `|` 就会让整行交付记录被静默抹掉。

---

## 静态预检（本批新迁移）

对本批并入的两份新迁移（V258 / V261）跑「已知互相破坏形状」扫描：

- `ADD COLUMN` 无 `DEFAULT`：**0 处**
- `SET NOT NULL`：**0 处**（V261 里唯一的命中是注释文本本身）
- `ADD CONSTRAINT ... CHECK`：**0 处**
- `DROP`：**0 处**；`ALTER TABLE` 仅出现在 RLS 启用循环里（作用于本模块自己的新表）

⇒ **两份迁移都是「只建新表」，跨车道 DDL 回归面 = 0**，静态预检无命中。
