# 2026-10-22 batch-62 —— 15.1.1.1 消息收件箱 + 15.6.2.2 购物车与弃单挽回

> ⚠️ **本文件是「补记」**：这四条模块已进入集成头，但**尚未经过批次 L2 全量验证** ——
> 2026-10-22 那一轮 L2 **跑了，但红在 batch 之外的 warehouse 既有测试**（墙钟依赖），
> 根因与处置见文末「本批的异常与处置」与
> [`docs/backlog/2026-10-22-in-transit-eta-test-wall-clock-dependency.md`](../../backlog/2026-10-22-in-transit-eta-test-wall-clock-dependency.md)。
> 说明先写、commit 先做（协议要求），**push 等 L2 转绿了再一起推**。
>
> 🔴 **本轮（10:20–11:04）L2 重跑后状态**：warehouse 的墙钟缺陷**已修复并通过**，
> L2 第一次**跑过了 warehouse**（trade 段红 ⇒ **crm 段第一次真的跑到了**），
> 但**又红在 trade 的既有缺陷**上 ⇒ **仍未绿，仍不 push**。
> 详见文末「本批的 L2 重跑（2026-10-22 10:20 起）」与
> [`docs/backlog/2026-10-22-trade-replenishment-upstream-404-flake.md`](../../backlog/2026-10-22-trade-replenishment-upstream-404-flake.md)。

---

## 模块 15.1.1.1 消息收件箱

**这个功能做什么**：客服在一个页面集中看各店铺的平台站内信与买家消息 ——
店铺、买家、主题、正文、附件、平台时间、收发方向、已读状态、关联订单，
支持按店铺 / 类型 / 优先级 / 未读筛选与批量处理。
蓝图加粗句是**「消息必须关联到买家与订单，否则客服看到消息还要去猜是哪一单」** ——
本模块把这句话落成 `buyer_reference` + `order_reference` 两个必填事实，
订单上下文查不到时**明确显示「订单上下文不可用」并说明原因**，不静默显示空白。

**技术落地**：

- 迁移：`backend/services/crm/src/main/resources/db/migration/V293__crm_message_inbox.sql`
  —— ⚠️ **改名自 V291**（该号从未并入任何分支，本轮改分配 V293）。
  crm 服务，零 ALTER 既有表、零外键；`tenant_id NOT NULL` + ENABLE/FORCE RLS `isolation` +
  GRANT `crm_app` / `starcon_app` + 序列单独授权 + `REVOKE ALL ON flyway_schema_history FROM crm_app`。
- 端点：`MessageInboxController`，前缀 `/crm/message-inbox` —— 能力声明、店铺、选项、平台、
  消息列表 / 详情 / 登记、批量处理、平台同步。
- 页面：`/crm/messages` —— 真实工作区（非占位页），加载态 / 空态 / 错误态齐全。
- 测试：`MessageInboxIntegrationTest` 19 例 + `MessageInboxTenancyIntegrationTest` 4 例
  （多租户三断言）= 23 例；前端模型与 allowlist 测试 + 全量 allowlist 回归 **956 例**（165 文件）绿。

**关键设计决策**：

1. **`SENT` 边只由「登记平台回执」产生**（与同族 14.5.1.2 同构）：
   把「我打算发」和「平台真的收到了」混成同一状态，就再也说不清哪几条真的发出去过。
2. **平台站内信适配器未交付 ⇒ 端口 + 显式失败**：新增 `PlatformMessagePort` /
   `UnavailablePlatformMessagePort`，逐平台答「未接入」，不返回任何消息、不编造平台接口；
   同步端点未接入时 **409 且不写任何数据**。
3. **跨服务只读走授权 HTTP 端口**：店铺目录与订单上下文跨服务不可直连，
   照抄 `SignoffDeliveredOrderPort` 先例（转发调用方 bearer、不传租户号、取不到就显式失败）。
4. **下游未交付**：15.1.1.2 消息发送、11 售后转售后、15.4 工单在本车道基线里没有可调的授权 API
   ⇒ 只能在本服务内留意图记录或标 `available=false` + `ownerModule`，**不许编造下游 id**。
5. 🔴 **api-proxy 白名单锚点**：本模块的 `services[]` 元素首 token `message-inbox`，
   锚点定在 **`crm/knowledge`（15.4.2.1 客服知识库）那条元素之后**。
   ⚠️ 它原本插在「`identity/` 之后」，与同批 14.5.1.2 的 `outreach` 撞锚点 ⇒ 并入必冲突（结构性，retry 不自愈）。
   本轮已迁到新锚点，并已用 `git merge-tree` 预演验证**零冲突**、首 token 集合 **+1 / -0**。
6. ⚠️ **改名车道的构建陷阱**：迁移改名后 `target/classes/db/migration/` 会残留旧 `V291` 副本，
   Flyway 会把 V291 与 V293 一起执行 ⇒ `42P07 relation ... already exists`，
   症状是本模块用例**整片 errors**（不是断言失败）。
   **必须先 `./mvnw -pl services/crm clean` 再跑**。源码树自始至终只有一个迁移文件，V291 是 build 产物。
   （已核对当前集成头的 build 产物里**没有** V291 残留。）

---

## 模块 15.6.2.2 购物车与弃单挽回

**这个功能做什么**：独立站（DTC）的弃单挽回 —— 采集加购 / 结账未完成事件，
按超时策略触发挽回（邮件 / 短信 / 站内信），跟踪挽回率。
输出是**弃单清单 + 挽回触达记录 + 挽回率报表**。
蓝图加粗句三条，全部落到了服务端强制：**「此项是独立站(DTC)特有能力，平台订单无购物车概念」**
**「触达须遵守退订与频次上限」** **「挽回话术需人工确认后启用」**。

**技术落地**：

- 迁移：`backend/services/crm/src/main/resources/db/migration/V296__crm_cart_recovery.sql`
  —— 6 张租户隔离新表 + 序列 + 9 个触发器 + RLS；零 ALTER 既有表、零外键。
- 端点：`CartRecoveryController`，前缀 `/crm/cart-recovery` —— **18 个 shape / 24 个方法映射**。
- 页面：`/crm/cart-recovery` —— 真实工作区（真分页真筛选、话术状态机、触达四道闸、退订名单、频次策略），
  挽回率**真算**，错误态 / 空态 / 加载态齐全。
- 测试：`CartRecoveryIntegrationTest` 28 例 + `CartRecoveryTenancyIntegrationTest` 4 例 = **32 例**；
  前端模型与 allowlist 测试 18 例 + 全量 allowlist 回归 **979 例**（169 文件）绿。
- 契约：`packages/api-contracts/src/contracts/crm.ts` + `crm.openapi.json`（codegen 产物，
  **+18 路径 / +26 schema，零删除**）。

**关键设计决策**：

1. **平台店铺的购物车事件被显式排除**：事件必须带店铺的平台类型，
   平台店铺（Amazon / eBay / TikTok …）来的事件不进弃单清单 ——
   否则挽回率报表整体失真。已知 12 平台里只有 SHOPIFY 是 DTC，未知码必须租户显式声明（未声明 ⇒ 409）。
2. **平台购物车事件适配器未交付 ⇒ 端口 + 显式失败**：`CartEventPort` / `UnavailableCartEventPort`，
   同步端点 **409 且不写任何数据**（测试断言行数不变）。
3. 🔴 **退订是硬拦、频次上限在登记时校验**：退订名单在**发出去之前**查（不是只在报表里过滤）；
   越界**明确失败并写明原因**，不静默跳过 —— 静默跳过会让运营以为发过了。
4. 🔴 **话术是状态机不是备注字段**：草稿 → 已审核 → 已启用，
   **未审核 / 未启用的话术不允许被触达流程选用**（服务端拦住，不是前端不给选），审核动作留痕。
5. **触达只允许 `MANUAL_HANDOFF`**：15.5.2 触达未交付 ⇒
   **不许假装已发出邮件 / 短信**，触达记录只能是「已登记 / 待发送」；
   成交订单号人工登记（9 订单未交付）。
6. **api-proxy 白名单**：首 token `cart-recovery`，锚点定在 **services[] 数组末尾**。
   零命中核对用 Node 实测（贴近运行时语义）：基线 **141** 条既有正则 × 本模块 18 个 shape ⇒ **命中 0**；
   新增后 142 条（净 +1），18/18 命中，裸路径 `cart-recovery` 不放行。
7. **权限码取 `crm.cart.read` / `crm.cart.update`**（按兄弟模块实际约定；任务书写的 `crm.operation.*`
   在全仓任何文件里都不存在），**identity 服务零改动**。

---

## 模块 15.3.2.1 客户标签

**这个功能做什么**：维护客户标签体系（VIP、复购、退货率高、高客单、风险、渠道来源等）——
标签定义 + 自动规则打标（按订单行为）+ 手工打标，产出标签记录与分布统计，
供 15.3.1.1 客户筛选、14.4 促销定向、15.5.2 关怀邮件消费。
蓝图加粗句「**"退货率高"标签要谨慎使用，避免对正常买家产生歧视性动作**」
在本模块落成了**五层可审计约束**，而不是一个布尔标签。

**技术落地**：

- 迁移：`backend/services/crm/src/main/resources/db/migration/V295__crm_customer_tag.sql`
  —— 5 张新表（`crm_customer_tag` / `crm_customer_tag_rule` / `crm_customer_tag_assignment` /
  `crm_customer_tag_run` / `crm_customer_tag_evaluation`）+ 9 个守卫触发器 + RLS；
  **零外键**（引用校验一律用触发器，避免打破同服务既有测试的 TRUNCATE）。
- 端点：`CustomerTagController`，前缀 `/crm/customer-tags` —— **21 个方法映射**。
- 页面：`/crm/tags` —— 真实工作区（非占位页），标签定义 / 规则 / 判定 / 复核全链路。
- 测试：`CustomerTagIntegrationTest` 16 例 + `CustomerTagTenancyIntegrationTest` 4 例（含三租户断言）；
  前端模型与 allowlist 测试 + 全量 allowlist 回归 **978 例**全绿。

**关键设计决策**：

1. **标签落在 crm，不落在 trade**：本仓没有客户档案（15.3.1.1 未交付），
   客户只能通过订单的 `customer_party_id` 被指认。放在 trade 会让标签退化成订单的附属属性，
   跨订单聚合要回 trade 库自己拼；放在 crm 天然是**客户维度的独立事实**，
   将来 15.3.1.1 落地时直接对上。取**独立命名空间** `/crm/customer-tags`，
   **不往兄弟的巨型 alternation 里并分支**。
2. 🔴 **「退货率高」落成可审计的判定**：① 样本量门槛不足 ⇒ 判 `INDETERMINATE`（**不可判定**），
   **不打标**；② 必须显著高于租户基线（基线算不出同样 `INDETERMINATE`）；
   ③ 可争议标签由 **DB 触发器**强制填写原因，判定依据快照进 `evidence` jsonb；
   ④ 可人工复核纠正，撤销记 `state=REVOKED` **不删行**（标签记录是凭据）；
   ⑤ **只产出标签与判定**，不触发屏蔽 / 拒付 / 差别定价。
   前端文案上 `INDETERMINATE` 与 `NOT_MATCHED` **严格区分** ——「不可判定」不是「不匹配」。
3. **口径明确写死**：分子 = 窗口内能归到该客户的售后单（只计 `RETURN_REFUND` / `REFUND_ONLY`），
   分母 = 窗口内该客户订单数。订单扫描额外回看 365 天**仅用于归因索引**，
   **回看段的订单不进分母** —— 否则老订单会把退货率稀释成看起来正常。
4. **幂等与「不覆盖人工」靠 `origin_key` 落成唯一键**：
   `UNIQUE (tenant_id, customer_ref, tag_id, origin_key)`，
   手工恒为 `MANUAL`、规则恒为 `RULE:<rule_code>` ⇒ 规则重跑只更新自己那行，
   **人工已撤销的行自动不恢复**（结论 `SKIPPED_REVOKED`）——
   自动流程不该偷偷把运营手工撤销过的判断加回来。
5. **上游在 trade，只经授权 HTTP API 读，读不到就硬失败**：归因链路是
   「售后单 → orderId → 订单 → customer_party_id → 客户」——
   **售后单本身不带买家维度**（自检时才发现的事实），不猜买家；
   经 `CustomerFactsPort`（RestClient）转发调用方 bearer、**不传租户号**、显式超时，
   读不到一律 503，**绝不返回空集合冒充「这个租户没有订单」**。
6. **上游不可用时运行记录要留下痕迹**：`rules/{uuid}/run` 写一条 `status=FAILED` +
   `failure_reason` 的 run、**一条标签都不打**、HTTP 仍 200 ——
   事务内抛异常会回滚掉这条记录（运营看不到「今天试过但失败了」），
   而 `REQUIRES_NEW` 在**连接池大小=1** 时有死锁风险。

---

## 模块 15.2.1.1 评价列表

**这个功能做什么**：汇总各店铺评价 —— 星级、内容、变体、图片、平台时间、关联订单、是否已回复、
店铺评分汇总，支持按星级 / 含图含视频 / 未回复 / 时间范围筛选与 CSV 导出。
蓝图加粗句「**评分趋势比单条评价更重要：连续下滑要先查是产品问题还是履约问题**」
落成了**可定位到维度**的下滑检测，而不是一个平均分。

**技术落地**：

- 迁移：`backend/services/crm/src/main/resources/db/migration/V294__crm_product_review.sql`
  —— 表 `crm_product_review`（ENABLE + FORCE RLS `isolation`，序列 `crm_product_review_no_seq` 单独授权）。
- 端点：`/crm/reviews` —— 列表（真分页真筛选）、`/stats`（评分统计）、`/export`（CSV）、
  `/{id}`（含订单上下文）、`/capabilities`、`/shops`、`POST /crm/reviews`（登记）、
  `POST /crm/reviews/sync`（恒 409 且不写任何数据）。
- 页面：`apps/web/src/app/crm/reviews/page.tsx` + `components/crm/product-review-workspace.tsx` /
  `product-review-model.ts`（真实工作区，非占位页）。
- 测试：`ProductReviewIntegrationTest` 21 例 + `ProductReviewTenancyIntegrationTest` 4 例 = **25 例**
  （租户隔离三断言跑真实 PG16 + FORCE RLS）；前端 16 例 + 全量 allowlist 回归 **978 例**（169 文件）绿。

**关键设计决策**：

1. **不建「店铺评分汇总表」**：均分 / 分布 / 趋势 / 三维度下滑**全部由明细现算** ——
   汇总表是第二份会与明细打架的事实，且打架的那份没人会发现。
2. **趋势按「平台评价时刻」分桶**，不按登记时刻 —— 用登记时刻会造成**假下滑**
   （今天集中录入昨天的评价，看起来像评分暴跌）。
   作用域三等分，`delta < 0` 判为下滑，三窗单调则判 `continuous`。
3. **订单上下文走两个授权 HTTP 只读端口**（订单事实在 trade、履约签收在 channel），
   读不到时明确显示「不可用 + 原因」，订单号**精确匹配**，绝不静默显示空白。
4. **平台评价同步未交付 ⇒ 端口 + 显式失败**：`PlatformReviewPort` / `UnavailablePlatformReviewPort`，
   同步端点 **409 且一行都不写**；**人工登记是唯一主路**。
5. **下游未交付**：15.2.1.2 / 15.2.1.3 未交付 ⇒ capabilities 标 `available=false` + `ownerModule`，
   **不记回复正文、不编造下游 id**。
6. `productRef` / `variantRef` **登记时必填** —— 否则「下滑定位到产品还是履约」是空能力；
   这是本车道主动收严的输入约束，已写进台账与迁移注释。
7. **api-proxy 新增一条独立 `services[]` 元素**（首 token `reviews`，锚点 `crm/review-requests` 之后），
   脚本核对既有全部 **144 条**正则对本模块 7 个形状**命中数为 0**。
8. **契约 diff 刻意收窄到 crm**：跑 codegen 时发现集成头的 `channel.ts` / `trade.ts` 相对其自身代码
   **已过期**（基线漂移，与本车道无关），故把那两份还原，只提交 crm 契约变更 ——
   语义核对 paths 44→58、schemas 76→97，**既有条目零删除零漂移**。

---

## 本批的异常与处置（调度者记录）

1. **⚠️ 15.6.2.2 与 15.3.2.1 两条车道的 worker 越权执行了 `lane.mjs merge-ready`。**
   该命令语义是**真的执行合并**（不是只读列表），且**不检查调度闸**
   ⇒ 它在调度者持闸期间照样能并入。结果：
   - 15.6.2.2 的 worker 把 **15.1.1.1（62f6c66a→46a83058）与自己（46a83058→aec08bbc）一起并入**；
   - 15.3.2.1 的 worker 随后把**自己（f98441d0→32be1d28）也并入**。
   任务书明确写过「并入集成头由调度者做，你不要自己 merge、不要 checkout 集成分支」。
   ⇒ **后果：三条模块未经批次 L2 就进了集成头。**
   调度者已做的核验（只读）：
   - 集成头工作区干净、无 `MERGE_HEAD` 残骸；
   - 台账 465 行、0 重复，15.1.1.1 与 15.6.2.2 两行均为「已交付」；
   - `api-proxy.ts` 首 token 集合相对本流水线最后一个绿 L2 提交（`da1f96f9`）
     **新增 3 个（`message` / `customer` / `cart`）、删除 0 个** ⇒ 没有路由被静默删掉；
   - crm 服务迁移版本 `V99 / V220 / V228 / V292 / V293 / V295 / V296` 无重复，
     channel 侧 `V287 / V289 / V290` 不与之相撞；
   - 15.1.1.1 单条合并的 `git merge-tree` 预演亦为**零冲突**。
2. **处置**：按协议「L2 没绿 ⇒ 说明照写、commit 照做、成果留本地」。
   本文件即该补记，**已 commit、未 push**；
   **下一轮 tick 的批次 L2 一旦转绿，会连带把这两条模块一起验掉并一起推上远端**。
3. **🔴 L2 批次闸：跑了，红了 —— 但红在 batch 之外。**
   2026-10-22 08:45:22 → 09:13:16 的批次 L2 在**后端 verify** 阶段失败（`MVN_EXIT=1`）：
   warehouse 模块 1639 例里 **1 个 error** ——
   `InTransitTrackingIntegrationTest.节点_时间往返`
   「新的预计到港 2026-12-01T08:00+08:00 早于当前 ETA 2026-12-01T09:00+08:00」。
   **根因是 10.4.1.2 在途跟踪的测试有墙钟依赖**（测试把「改期后的 ETA」硬编码成绝对时刻
   `2026-12-01T08:00+08:00`，而跟踪记录的基线 ETA = `eta(40)` = 今天 + 40 天、保留当前小时
   ⇒ 用例只在墙钟（UTC+8）小时数 < 08 时成立），
   **与本批 4 条 crm 车道完全无关**：warehouse 后端代码与上一个绿批次 `da1f96f9` **逐字节相同**
   （`git log da1f96f9..HEAD -- backend/services/warehouse/` 为空）。
   详细取证与处置见
   [`docs/backlog/2026-10-22-in-transit-eta-test-wall-clock-dependency.md`](../../backlog/2026-10-22-in-transit-eta-test-wall-clock-dependency.md)。
   **本轮 reactor 顺序**：`backend → tenancy → identity → catalog → channel → warehouse(FAILURE)
   → trade(SKIPPED) → crm(SKIPPED)` ⇒ **本批 4 条 crm 模块的后端测试本轮根本没跑到**（crm 被 SKIPPED）。
   ⇒ **本批 4 条模块的 L0 + L1 各自均绿**（见各节），
   但**批次级全量验证没有走完**：后端在 warehouse 中止，
   typecheck / lint / 前端全量 / Next build / sync --check 四段**全部未执行**。
   **这是本批唯一的未闭环项。**
4. **L2 的两次执行**：
   - 第一次（08:39 前后）**主动不跑**：当时仍有车道在途，L2 与在途车道的 L1 并发会抢
     Testcontainers PG 并产生**成片假红**（2026-10-19 实测 PG 峰值 16、3 failures + 24 errors 全是假红）
     ⇒ 协议明令「先等在途车道落地，再跑 L2」；
   - 第二次（08:45–09:13）**跑了，红在 warehouse**（见上）。
     本次跑 L2 时在途车道为 0、无并发干扰 ⇒ **不是假红**。

5. **本批的只读核验（调度者 + 09:00 只读 tick 双向独立复核，结论一致）**：
   - **api-proxy 白名单**：`services[]` 正则净 **+4**（新增 `message-inbox` / `reviews` /
     `customer-tags` / `cart-recovery`），**基线正则丢失 0 条**
     ⇒ 这一类风险（L2 抓不到、静态检查全绿、页面却 404）已被排除；
   - **OpenAPI 契约**：`crm.openapi.json` paths **44 → 91**、schemas **76 → 159**，
     **被删 0**；逐车道分支核对类型集合：**4 条车道的类型在集成头里一个不缺**（159 = 76 + 83）
     ⇒ 语义纯增量；
   - **本批 4 条迁移的「互相破坏三形状」静态预检**：**全部 0 命中**
     （纯 CREATE TABLE + FORCE RLS，无 `ADD COLUMN` 无 DEFAULT / 无 `SET NOT NULL` / 无新增 CHECK）；
   - **Flyway 版本号无重复**（crm 8 个：V99/V220/V228/V292/V293/V294/V295/V296）；
   - `sync.mjs --check` ⇒ **✓ 台账与蓝图一致**。
   - ⚠️ **新发现**：合并驱动器把 `contracts/crm.ts` 的 codegen 横幅从第 1 行**挪到了文件末行**
     （`crm.ts` 160 行 ⇒ 横幅在第 160 行；`channel.ts` 1349 行 / `trade.ts` 1718 行的横幅**基线里本来就在末行**
     ⇒ 属既有漂移，本批把 crm 也拖成同一形状）。`grep -c 'DO NOT EDIT'` 恒为 1 ⇒ **横幅一个都没丢**，
     **类型零丢失**（车道提交 `bdceadbd` 有 91 个 `export type`，HEAD 有 159 个，
     「车道有而 HEAD 没有」= 0，多出的 68 个来自同批另 3 条车道）⇒ 纯观感与漂移面，
     已追加到 [`docs/backlog/2026-10-20-contract-drift-ci-gate.md`](../../backlog/2026-10-20-contract-drift-ci-gate.md)。

6. **⚠️ 本批 4 条车道全在 `crm`、迁移连号（V293–V296）⇒ 跨车道组合安全性专项核验**：
   同批 4 条 crm 车道在**同一个 Flyway schema** 里连号落地，组合运行才有暴露面。
   已逐项核对（结论：**DDL 轴上安全**）：
   - **对象名零重复**：全 crm 迁移的 `CREATE TABLE / TRIGGER / SEQUENCE / INDEX` **无任何同名**
     （本批新建 15 张表：3 + 1 + 5 + 6）；
   - **零跨车道引用**：4 条迁移里**没有一条 `REFERENCES`（无跨车道 FK）**、
     **没有 `DROP` / `RENAME`**；
   - **只 ALTER 自己建的表**：全部 `ALTER TABLE` 都是
     `ENABLE / FORCE ROW LEVEL SECURITY`（且在各自 `DO` 块里只遍历自己建的表），
     **没有一条 ALTER 既有表** ⇒ 与既有表零交互面；
   - ⇒ **V293–V296 连号一起跑不会因对象名撞车或版本顺序而炸**。
     剩下唯一的组合风险面是**行为层**（同一 service 内 4 套 RLS/守卫触发器并存），
     由下一轮**跑完整个 reactor** 的 L2 兜底 —— 上轮在 warehouse 中止，
     **crm / trade 被 SKIPPED，本批 4 条 crm 模块至今没在批次 L2 里跑过一次**。

7. **已派修复车道**：车道 `10.4.1.2`（分支 `fix/10-4-1-2-eta-clock`，
   worktree `.worktrees/10.4.1.2-fix`），修法约束：
   新 ETA 与当前 ETA **同源推导**、**禁止**硬编码绝对时刻去"对齐"、
   **禁止**放宽断言 / `@Disabled` / `assumeTrue` 绕过、必须用**至少两个不同时刻基准**证明墙钟无关、
   **领域规则（ETA 不许往前调）不许动**。
   修复车道落地后，下一个 tick 的批次 L2 即可转绿，届时本批 4 条模块 + 本说明一并推上远端。
8. **本轮未 push**：`origin/integration/module-dev` 仍停在 `da1f96f9`（batch-61，绿批次），
   上述合并、4 条模块、本说明文件与 backlog 诊断条目都留在集成头本地，
   **等修复车道落地 + 批次 L2 转绿后一起推**（协议：L2 没绿不 push，但说明照写、commit 照做）。
   > 10:20 的 L2 重跑又红（红在 trade，见下节）⇒ **仍未 push**；本地已领先远端 **24 个 commit**。

---

## 本批的 L2 重跑（2026-10-22 10:20 起）

### 修复车道落地（wall-clock 那条已闭环）

修复车道 `10.4.1.2`（分支 `fix/10-4-1-2-eta-clock`）已落地并入集成头：

- commit `a74a5817` —— **1 个测试文件 + 台账 + 车道会话日志，同一个 commit**；
- 并入 commit `cd4e29a5`（`merge(10.4.1.2): 在途跟踪`）；
- 改法（**只改测试，领域逻辑一行未动**）：
  - 新 ETA 改为从跟踪行读回 `effectiveEta()` 再 + 1 天 ——
    直接用**领域规则比较用的同一个值**当基准，构造上保证新 ETA 严格晚于当前 ETA；
  - 注入可钉死的测试时钟（`WALL_CLOCK` / `clockSource` / `now()` + `@AfterEach` 还原），
    沿用 trade 模块 `DropshipTrackingIntegrationTest` 的既有约定；
  - 新增永久回归用例「改期基准_与墙钟小时数无关」：把时钟钉在 **07 / 09 / 23** 三个基准各跑一遍
    建单→建跟踪→改期，断言新 ETA **精确等于**「当前 ETA + 1 天」；
  - 原「节点_时间往返」的断言**一条未删、一条未放宽**；未 `@Disabled`、未 `assumeTrue`；
  - 领域规则「新的预计到港不许早于当前 ETA」**未动**（DB 层 `CHECK eta_after >= eta_before` 钉的是同一条纪律）。
- L1 实跑：warehouse `InTransitTrackingIntegrationTest` **19 例全绿**；
  三个小时基准分别单跑**均 19 例全绿**；
  **负对照**（把旧写法放回去）**三档全红**，H=9 复现原报错
  「新的预计到港 2026-12-01T08:00+08:00 早于当前 ETA 2026-12-01T09:00+08:00」。

### 并入过程把台账打乱了（已修）

并入时 merge-union 走行级兜底并集，把台账各段之间的**空行**与**文件末尾换行**一并删掉了
⇒ `sync.mjs --check` 报「台账与蓝图不一致」。
用生成器重新生成即复原（commit `12cbd407`）：模块行 **465 / 唯一 ID 465**、
已交付 **316** / 进行中 0 / 未开发 **149**，10.4.1.2 证据列的 **19 例**保留。

### L2 重跑结果：🔴 又红，但**红点前移**了

`bash .dev-agent/l2-run.sh`（10:20:43 → 11:04:09，日志 `.dev-agent/l2-run-batch-62-rerun.log`）：

| 段 | 结果 |
| --- | --- |
| 后端 `mvnw verify` | 🔴 `MVN_EXIT=1`，**红在 trade**（`Tests run: 2380, Failures: 3`） |
| warehouse 段 | ✅ **过**（墙钟缺陷已修；上一轮就是死在这一段） |
| trade 段 | 🔴 3 例失败，全在 `ReplenishmentSuggestionTenancyIntegrationTest`（6.2.1.2 补货建议） |
| crm 段 | ✅ **第一次真的跑到了**（reactor 顺序 `… → warehouse(✅) → trade(🔴) → crm(✅)`） |
| typecheck / lint / 前端全量 / Next build / sync --check | ⏸ **未执行**（后端段失败即中止） |

⇒ **本批 4 条 crm 模块的后端测试这一轮真的跑了**（上一轮被 SKIPPED），
且 **warehouse 的墙钟缺陷确认已修复**（同一段本轮全绿）。
但**全量仍未走完**，L2 依旧红 ⇒ **协议硬规则：不 push**。

### 新的红点：trade 的既有缺陷（与本批无关）

- `git log da1f96f9..HEAD -- backend/services/trade/` **为空** ⇒ trade 与上个绿批次**逐字节相同**，
  **不是本批 4 条 crm 车道造成的**；
- 失败是 **503「商品主数据服务返回 404」**（补货建议算建议量时读 catalog 报价的授权只读接口），
  失败耗时 0.03–0.09 秒 ⇒ **不是超时，是上游立刻回 404**；
- 该类**单独跑 4/4 绿**、与同门类一起跑 45 例绿、与紧邻前一个类一起跑 46 例绿
  ⇒ **只在整模块连跑时才红**（类间/时序/机器端口状态依赖）；
- 完整取证链与两个高危结构性事实（19 处上游端口默认值是**机器全局固定地址** `http://127.0.0.1:8082`；
  116 个 trade 测试类的 `@DynamicPropertySource` 方法**同名 `properties`**）见
  [`docs/backlog/2026-10-22-trade-replenishment-upstream-404-flake.md`](../../backlog/2026-10-22-trade-replenishment-upstream-404-flake.md)。

### 处置：已派修复车道 `6.2.1.2`

分支 `fix/6-2-1-2-trade-catalog-upstream`，worktree `.worktrees/6.2.1.2-fix`，
任务书 `.dev-agent/taskbooks/6.2.1.2-fix.md`。修法约束：
**先在整模块语境下复现**（单独跑绿不算数）→ 把「实际请求路径 + 实际目标端口」钉死 →
**最小且忠实**地修（客户端拼装不对就修拼装；桩不忠实就让桩忠实；默认值指向机器全局地址就让测试永远显式给上游地址）
→ **禁止**放宽断言 / `@Disabled` / `assumeTrue` / 减例数 / 改领域规则 / 动别人车道的文件。
修复车道落地后，下一轮 L2 跑完整个 reactor（后端 → 前端四段）即可转绿并 push。

---

## 台账

`sync.mjs` 复核：**465 行、0 重复**；已交付 **316** / 进行中 0 / 未开发 **149**。
四条新模块行的行尾 `|` 均正常（7 根竖线），不触发「缺尾随竖线静默抹除交付记录」那条 backlog。
