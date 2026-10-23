# batch-74 —— 卷 01 工作台首批四条车道并入（异常订单队列 / 履约健康 / 库存风险 / 全局搜索框）

- 日期：2026-10-23
- 批次号：74（接 batch-73 往后排）
- 调度者：Mavis（pipeline cron tick）
- 集成头：`integration/module-dev`
- 上一轮状态：346/465 已交付；本轮结束 **350/465**（并入 4 条车道即 +4，起点 346）
- 四个并入 commit：`e7cf43e1`（1.1.1.2）→ `8eb00543`（1.1.2.3）→ `9cb37756`（1.1.3.1）→ `de6dbb98`（1.2.1.1）

---

## 本轮结论速览

| 项 | 结果 |
| --- | --- |
| 台账同步 | 465 个 L4 模块；起点 346 已交付 / 119 未开发 |
| 回收并入 | **4 条**：1.1.1.2 异常订单队列、1.1.2.3 履约健康、1.1.3.1 库存风险、1.2.1.1 全局搜索框（**零冲突**） |
| 静态预检 | 通过（本批唯一新迁移 V330 无 `ADD COLUMN` 无 `DEFAULT` / 无 `SET NOT NULL` / 无新增 `CHECK`） |
| L2 批量验证 | 见「L2 结果」一节 |
| 派发 | **4 条**：1.2.1.2 搜索结果分组、1.2.2.2 快捷入口配置、1.4.1.2 列表与表格偏好、1.5.1.1 同步与 API 健康（卷 01 第二批） |
| 服务缺口 | 卷 12 finance（32）+ 卷 13 reporting（23）+ 卷 18 platform（24）= **79 个模块永久排不出** |

> ⚠️ **本轮调度者自纠**：本 tick 内 `plan` 被**重复执行了两次**（`plan` 有副作用，会真的建分支 + worktree），
> 导致一次性排出 **8 条**车道、超出 `RUNNING_QUOTA=6`。已当场修正：
> abandon 其中 4 条（1.1.1.3 / 1.2.2.1 / 1.4.1.1 / 1.4.2.1）+ `gc` 回收 worktree +
> 把它们的分支重置回集成头（`git branch -f`，四条分支均 **0 commits ahead**，无成果丢失），
> 最终只保留基线正确的 4 条派发。**根因**：`lane.mjs plan` 复用已存在的分支时**不会把它 rebase 到当前集成头**
> （`if (!branchExists) git branch branch base`），这四条被复用的分支基线停在 `f9bce82c`（落后 17 个提交）。
> ⇒ **下一轮建议**：给 `lane.mjs plan` 补一条「复用分支时 reset 到 base」的逻辑，并加一条 itest
> （否则每次撞上「上一轮崩了留的分支」，都会拿到一个陈旧基线）。

---

## 模块 1.1.1.2 异常订单队列

**这个功能做什么**：用户在工作台一屏看到「哪些订单出问题了」—— 七类异常按紧急度排成清单，
点一条能看到凭据（为什么判定异常、从什么时候开始、持续多久），批量处置**跳转到归属系统**而不是在工作台直接改单。

**技术落地**：

- **迁移**：**无**（只读现有表 ⇒ 不占用预分配的 V327）。
  蓝图写的 `order.status_history` 真实表名是 `sales_order_status_history`；
  蓝图写的 `order_charge` 真实存在但**没有「异常标记」列**（它记的是费用，正负由 DB CHECK 钉死）
  ⇒ 本模块把它当**订单费用明细**进详情页，**不当异常判据**。
- **端点**：`GET /trade/anomaly-orders` 下 4 个（`AnomalyOrderController.java`，jOOQ 显式 SQL，权限码 `trade.orders.read`）
  —— `GET /capabilities`（七类异常逐条报 available + ownerModule + source + aggregation，外加跨服务来源清单、
  动作清单、**未接通清单**、两个阈值、数据截至时刻）· `GET /options`（异常类型词表 + 店铺 + 订单来源 + 停留时长档位）·
  `GET` 列表（**真分页 + 真筛选 + 真排序白名单 4 键 + 汇总 + 数据截至时刻**）· `GET /{orderId}` 明细。
- **页面**：`/dashboard/todo`（`apps/web/src/app/dashboard/todo/page.tsx`）
  + `apps/web/src/components/dashboard/anomaly-order-{model,workspace}.tsx`
  —— 能力声明区 + 异常概览（七类计数**点一下就按这一类筛**，与清单同一集合算出，翻页不变）+ 筛选（类型 / 店铺 / 停留时长 / 关键字）
  + 清单（订单号 / 店铺 / 异常类型 / 停留时长 / 严重度 / 建议动作 / 明细）+ 批量处置条（勾选后给归属系统入口 + 复制选中订单号）
  + 单条明细（凭据逐条带起点与时长、订单行、费用、状态留痕、建议动作）；**加载 / 错误 / 空三态齐全，禁 mock**。
- **测试**：`AnomalyOrderIntegrationTest` 12 例 + `AnomalyOrderTenancyIntegrationTest` 7 例 = **19 例**
  实跑 Testcontainers PostgreSQL 16（连接池压到 1）；前端 `anomaly-order-model.test.ts` 25 例
  + `anomaly-order-allowlist.test.ts`（从源码反向抽真实调用点逐条断言放行 + 刻意不放行的形状仍 404）。

**关键设计决策**：

1. **七类异常 → 真实事实表**，每类的**停留时长锚点各不相同**（不是「订单创建至今」）：
   平台回传任务创建时刻 / 地址校验判定基准时刻 / 平台单下单时刻 / 风控评估基准时刻；
   一张单命中多类时清单行取**最久**那一条，逐条凭据各给各的起点。
2. **跨服务聚合放前端，不放 trade**：黑名单在 channel、可用量在 warehouse
   ⇒ **不跨库查它们的表**（AGENTS.md 模块边界纪律），由前端聚合层读它们的授权只读端点后合并。
   连接键**逐字同式**：黑名单按 `buyer_key`（收件人三件套 md5，**分隔符 U+001F**）· 库存按 `sku_id`。
3. **「查无库存账」≠「库存充足」**：warehouse 对没有库存账的 SKU 返回**没有那一行**而不是 0，
   本模块把它单列为「查无库存账」如实报出，**不算成「库存不足」**；
   channel / warehouse 读不到时页面明确写「本次**没有**黑名单信号 / **判不了**库存不足」，**不冒充「没有异常」**。
4. **批量动作一律落到归属系统**（转售后 → 11 售后 / 转人工 → 09 异常拦截 / 平台侧查询 → 02 渠道发货回传）；
   🔴 **「标记忽略」在 capabilities 里 `available=false` 并写明缺什么** ——
   归属系统当前没有「忽略一条异常」的写端点，而卷 01 明确工作台不写业务数据
   ⇒ 页面把它渲染成**带缺口的说明而不是能一键落地的按钮**，绝不造第二条写路径。
5. **截断如实报出**：清单最多 5 页 × 200、黑名单最多 5 页 × 200、可售量最多 10 页 × 200，
   被截断时页面写「只取回前 N 条（共 M 条）」。

---

## 模块 1.1.2.3 履约健康

**这个功能做什么**：回答「现在的履约健康吗」—— 七个履约维度的灯色卡片 + 五类红灯项清单，
每个数字都能点开看到算法、分母和来源表，不是一个不可解释的综合分。

**技术落地**：

- **迁移**：**无**（零新表：卷 01 是只读投影层，不产生新的业务事实）。
- **端点**：`GET /trade/fulfillment/health` 下 3 个
  —— `GET /capabilities`（七个维度逐条报出**算法 / 分母 / 来源表**）·
  `GET /summary`（健康度卡片，每张带 `metricKind`、分子分母、灯色与**灯色判定依据**）·
  `GET /red-flags`（五类红灯项，真分页 + 真筛选）。
- **页面**：`/dashboard/fulfillment-health`
  + `apps/web/src/components/dashboard/fulfillment-health-{model,workspace}.tsx`。
- **测试**：`FulfillmentHealthIntegrationTest` 14 例 + `FulfillmentHealthTenancyIntegrationTest` 7 例 = **21 例**
  （Testcontainers PG16，池大小 1）；前端 `fulfillment-health-model.test.ts` 14 例
  + `apps/web/src/app/api/trade/fulfillment-health-allowlist.test.ts` 6 例。

**关键设计决策**：

1. **没有上游就不给公式也不给分母**：平台 ODR（`PLATFORM_ORDER_DEFECT_RATE`）在 channel 侧
   `platform_performance_metric` 一族事实落地前**没有读面** ⇒ `available=false` +
   `ownerModule`「02 渠道与店铺 · 平台绩效指标同步」+ `impact`。
   **给了就是编一个** —— 这是本模块最重要的一条口径。
2. **零迁移零新表**：派生值落库只会比它描述的履约事实**更早过期** ⇒ 只读投影，不建表。
3. **红灯项口径与卡片同源**：卡片 `redFlagKind` 过滤后的 `total` 必须**等于**该卡片的 `alertCount`
   （两个口径各算一套就会出现「卡片说 3 项、清单列出 5 条」）。
4. **数据来源逐条钉死**：待发货积压读 `shipment_pending_unit`（V124）·
   超时发货率 / 物流异常件占比 / 平台绩效读 `shipment_record` + `shipment_record_tracking_observation`（V127）。
5. **不替兄弟模块实现**：1.5.1.2 数据一致性体检、1.5.2.1 服务与任务状态本轮均未交付，
   本模块**不代它们实现**任何东西。

---

## 模块 1.1.3.1 库存风险

**这个功能做什么**：一眼看出「哪些 SKU 要出事」—— 四类库存风险（缺货 / 呆滞 / 活跃预占 / 在途）各一张卡，
配风险清单，点一行展开这个 SKU 的口径明细（可用量、在手、预占、阈值命中档位、库龄、在途量）。

**技术落地**：

- **迁移**：**无**（无新迁移：卷 01 是只读投影层，不产生业务事实）。
- **端点**：2 个只读端点（权限码 `warehouse.inventory.read`，与 7.1.1.2 / 7.1.1.3 同源同权）
  —— `GET /warehouse/inventory-risk/capabilities` · `GET /warehouse/inventory-risk/projection`
  （(仓 × SKU) 投影：可用量 / 在手 / 预占 / 阈值与命中档位 / 库龄 / 活跃预占 / 在途，**阈值随行返回**）。
- **页面**：`/dashboard/inventory-risk`
  + `apps/web/src/components/dashboard/inventory-risk-{model,workspace}.tsx`
  —— 能力声明 + 四类风险卡 + 风险清单 + 点行展开口径明细；**只读，一切状态变更回归属系统**。
- **测试**：`InventoryRiskTenancyIntegrationTest` **5 例**（含租户隔离三断言，Testcontainers PG16）；
  前端 `inventory-risk-model.test.ts` **27 例** + `apps/web/src/app/api/warehouse/inventory-risk-allowlist.test.ts`。
- **跨服务读面**：trade V234 已放行的 `GET /trade/stockout-risk/velocities`（**前端**合并日均销）——
  **不跨库读对方表**（AGENTS.md 模块边界纪律）。

**关键设计决策**：

1. **精确十进制，不许浮点**：可用天数在 `BigInt` scale 6 上算 —— `0.3 ÷ 0.1 = 3` 而不是浮点的 `2`。
   分母 0 与「未登记」都是**不可判定**，**不是 0**。
2. **四类判定的阈值全部来自既有表，没有一个是新造的常量**；
   **「未配置阈值」与「阈值 = 0」严格分开**：没配 `min_on_hand` 的行**不参与缺货判定**。
3. **口径与既有模块逐字一致**：库龄 = `coalesce(inventory_lot.received_on, stock_bucket.created_at::date)`（同 7.6.1.1）；
   在途量 = 仍在途的腿的 `inventory_transfer.quantity` 之和（同 7.5.1.2）；
   超卖用 **`reservation_allocation`** 而不是 `inventory_reservation`（后者没有仓库维度）。
4. **不编成本价**：warehouse 的库存事实层**没有任何成本列**（已 grep 全仓确认），成本口径属 12 财务、跨服务禁止读对方表
   ⇒ 「占用资金」在呆滞风险里**缺席**。**假成本比缺成本危险得多** —— 一个猜出来的金额会被当真去定清仓价。
5. **不复用 trade 的断货风险端点**：6.2.1.1 是 trade 视角的断货风险，本模块是 warehouse 视角的四类库存风险，
   两者口径不同 ⇒ 另开一条 warehouse 只读投影。

---

## 模块 1.2.1.1 全局搜索框

**这个功能做什么**：按 `⌘K` 弹出一个搜索框，在 SKU / SPU / 订单 / 采购单 / 店铺 / 供应商 / 物流单 / 客户 / 仓库九个域里一次搜完，
结果按业务域分组，选中一条直接跳到对应页面并把主标识**回填**到那个页面的筛选器。

**技术落地**：

- **迁移**：`backend/services/identity/src/main/resources/db/migration/V330__global_search_recent.sql`
  —— 建 `global_search_recent`（`tenant_id` + `user_id` + `keyword` **一人一关键词一行**（`UNIQUE(tenant_id,user_id,keyword)`，
  重复搜只刷新 `searched_at`）+ `domain` + `primary_ref`）
  + 索引 `global_search_recent_by_user (tenant_id, user_id, searched_at DESC)`
  + `ENABLE` + **`FORCE`** RLS `isolation`（policy 只认 `app.current_tenant`）+ `GRANT SELECT, INSERT, UPDATE, DELETE TO identity_app`。
  **同迁移登记三条权限码**：`identity.global_search.read`（全局搜索入口闸门）
  + `trade.orders.read` / `trade.purchase.read`
  —— ⚠️ **这两个码 trade 自己的端点早就在 `require`，identity 的 `permission` 表里却从来没有过**，
  而 `role_permission.permission_code` 有指向 `permission(code)` 的外键
  ⇒ **没有任何角色可能持有它们**，trade 那两个列表端点在生产上恒 403。补登记后两个域才真的能被授权。
- **端点**：**五个服务各一组**（四个业务服务**刻意不提供任何写端点** —— 工作台只读 + 跳转是蓝图硬约束）
  —— `GET /{service}/global-search?q=&limit=` + `GET /{service}/global-search/capabilities`
  （catalog / trade / channel / warehouse），identity 一组 `GET/POST/DELETE /identity/global-search/recent`。
- **页面**：`⌘K` 面板（`packages/ui` 的 `CommandPalette` 与 `AppShell` 各加**可选**属性 ——
  `onQueryChange` / `statusText` / `commandPalette` 绑定；**不注入时行为与从前逐字一致**）
  + 独立页 `/dashboard/search`（`apps/web/src/app/dashboard/search/page.tsx` +
  `components/dashboard/global-search-workspace.tsx`，真实输入框、真实跨服务检索、真实分组结果与加载 / 空 / 错误三态）。
- **测试**：`GlobalSearchIntegrationTest`（catalog 9 / trade 11 / channel 7 / warehouse 9）
  + `GlobalSearchRecentIntegrationTest`（identity 11）= **47 例**实跑 Testcontainers PostgreSQL 16；
  前端 `global-search-model.test.ts` 14 例 + `global-search-allowlist.test.ts` 4 例。
- **命中回填**：`apps/web/src/lib/global-search-handoff.ts` 的 `usePrefillKeyword`
  —— 订单跳详情页 `/orders/{id}`，其余域跳对应列表页并把主标识**回填**到关键字筛选器
  （已在采购单 / 往来单位 / 店铺 / 仓库档案 / SKU 列表五个既有筛选器上各接一行）。

**关键设计决策**：

1. **检索端点落在五个服务里，合并在**前端**做**：聚合后端要么跨库查别人的表（AGENTS.md 明令禁止），
   要么就得新建一个服务（卷 01 明写「不新增业务服务」）⇒ 前端用 `Promise.allSettled` 语义并发打，
   **一个服务挂掉不影响其余域**（失败的服务进 `failedServices`，与「没权限的域」**分开报** ——
   混为一谈，界面就会把服务挂了显示成没有数据）。
2. **客户与供应商落在 catalog 而不是 crm**：crm 的 `crm_customer_contact.customer_ref` 迁移注释写死
   「本仓没有买家档案（15.3.1.1 客户列表未交付）」，权威的客户与供应商主数据是 3.6.1.1 的 `party` + `party_role`。
   跨服务去 crm 查接触记录里的姓名快照当客户档案，拿到的是一份**可能已过期的副本**。
3. **匹配只做前缀**（`ilike 'kw%'`）：蓝图写死「只做前缀/精确匹配，不做全文语义检索」
   ⇒ 不上 trigram / 全文索引 / ES。**≥2 字符在后端校验**（`normalize()` 直接 400；前端也挡一道，但**后端是权威**）。
4. **数据范围照抄各服务既有口径，不另立一套**：catalog 与 channel 走既有 RLS `data_scope` 策略
   （每个域用**各自的权限码**开事务 —— `app.permission` 是事务级 GUC，一个事务里只能有一个值），
   trade 照 9.1.1.1 与 6.3.2.1 的 ALL / DEPARTMENT / SELF
   （`sales_order` 无部门列 ⇒ DEPARTMENT 往更严的一侧退化为 SELF）· warehouse 是租户级。
5. **最近搜索登记在「用户选中一条」时**，不是每次键入都登记 —— 否则最近搜索会退化成击键历史。
6. **回填刻意不用 `useSearchParams`** —— 那会让整条路由放弃静态预渲染，
   给 Next 的 production build 引入新的 Suspense 约束（本仓现有页面一个都没用它）。
7. **未接通的域逐条给出归属**，不假装接通：平台刊登（04 渠道侧）· 平台买家（02 买家域）· 售后单（11 售后与退货）
   · 发货与物流单（09.3 履约请求未交付）· 条码（03.2.x，条码列没有可走前缀的索引）。

---

## L2 结果

在集成头上跑**一次**全量（`.dev-agent/l2-run.sh`，带 `FORCE_TYPECHECK=1` 绕开 turbo 缓存 ——
本批契约产物有变更，`packages/api-contracts/` 不是 workspace 包，不 `--force` 会命中旧缓存导致 typecheck 假绿）。

在集成头上跑**一次**全量（`.dev-agent/l2-run.sh`，带 `FORCE_TYPECHECK=1` 绕开 turbo 缓存 ——
本批契约产物有变更，`packages/api-contracts/` 不是 workspace 包，不 `--force` 会命中旧缓存导致 typecheck 假绿），
2026-10-23 12:11:56 起、13:08:30 结束（**56:34**）。

| 步骤 | 内容 | 结果 |
| --- | --- | --- |
| [1/6] | 后端 `./mvnw verify` | **MVN_EXIT=0** —— BUILD SUCCESS，耗时 **53:51** |
| | identity 1:45 / catalog 5:43 / channel 16:56 / warehouse 12:27 / trade 15:37 / crm 1:21 —— **6 个模块全 SUCCESS** | |
| | 后端合计 **564 个测试类 / 9066 例 / 0 失败 0 错误 0 跳过** | |
| | （catalog 1990 / channel 2155 / trade 2434 / warehouse 1654 / crm 491 / identity 342） | |
| [2/6] | `pnpm typecheck` / `pnpm lint` / `pnpm test` | **EXIT=0 / 0 / 0** —— 前端 **529 文件 / 7536 例全过** |
| [3/6] | Next production build（`NEXT_TELEMETRY_DISABLED=1`） | **BUILD_EXIT=0** |
| [4/6] | `sync.mjs --check`（台账 ↔ 蓝图一致性） | **SYNCCHECK_EXIT=0** |
| [5/6] | `merge-union.itest.mjs` + codegen python 单测 | **ITEST_EXIT=0 / CODEGEN_EXIT=0** |
| [6/6] | 契约闸（CI catalog-e2e 同款：重新生成 + 逐字节比对） | **CONTRACT_GENERATE_EXIT=0 / CONTRACT_GATE_EXIT=0 / CONTRACT_TREE_DRIFT_EXIT=0** |
| 附加 | 强制 typecheck（`--force`） | **FORCED_TYPECHECK_EXIT=0** |

⇒ **L2 GREEN**，无二分定位、无修复重跑。

⚠️ **本轮 L2 耗时 56:34，其中后端 53:51** —— 比 batch-73 的 53:47 还略长一点。
原因与上一轮同源：组批模式下 **L2 的全量构建与 4 条车道自己的 L1 构建在同一时段并发**
（4 条车道各自在 worktree 里跑 `mvnw -pl services/X -am test` + 各起 Testcontainers 容器）。
可观察到的代价：
- **PG 容器数一度冲到 100+**（绝大多数属于 L2 自己的那一个 Testcontainers session `1f13ce4f`，
  每个测试类起一个、ryuk 在 session 结束才统一回收）；车道跑完后计数回落到 3。
- **load 一度冲到 12.33**（> `LOAD_LIMIT=12`）后自行回落到 1.89 —— 90 秒采样确认是**瞬时尖峰**
  （车道 maven 与 L2 的容器churn 重叠），不是持续爬升；`pmset -g therm` 全程**无降频项**。
- 宿主内存 45% free、Docker VM 容器总占用仅 965MB / 11.67GiB ⇒ 不是内存压力。

⇒ **结论：本批的发热峰值仍在可控区间，但「L2 与车道 L1 并发」是本流水线当前唯一稳定的过热风险源**。
若后续用户再报温度偏高，**第一顺位回退是加大节流闸阈值（不是加并发）** —— 那是用户的决定，调度器不自行改。

---

## 本轮派发（卷 01 第二批，等下一轮回收并入）

| 模块 | 名称 | 迁移号 | 服务 | api-proxy 锚点 |
| --- | --- | --- | --- | --- |
| 1.2.1.2 | 搜索结果分组 | V331（默认不占用） | 前端聚合层 | `prefix: 'catalog/'` 那条之前 |
| 1.2.2.2 | 快捷入口配置 | V335 | identity | `prefix: 'identity/'` 那条之前 |
| 1.4.1.2 | 列表与表格偏好 | V336 | identity | `prefix: 'warehouse/'` 那条之前 |
| 1.5.1.1 | 同步与 API 健康 | V338（默认不占用） | channel | 数组末尾 |

**调度者为本批做的跨车道防撞裁决**（写进四份任务书，四条车道互不依赖）：

1. **表归属互斥**：`user_preference` 归 1.4.1.2（它要新建这张表）·
   `shortcut_entry_template` / `user_shortcut_override` 归 1.2.2.2。
   ⇒ 两条 identity 车道**不会各建一张 `user_preference`**（那会让 Flyway 在合并后直接炸）。
2. **api-proxy 锚点互斥**：四条车道各锚一个**不同**位置（catalog 之前 / identity 之前 / warehouse 之前 / 数组末尾），
   既有 179+ 条正则一字未改、不重排。
3. **api-proxy 改了就必须跑全量 allowlist 回归**（`pnpm vitest run apps/web/src/app/api`）——
   该文件是共享白名单累积正则，取错一侧会**静默**删掉别人已交付的路由，
   表现是页面 404，而 typecheck / lint / vitest / Playwright **全绿**。

## 后续待办

1. **79 个模块永久排不出**：卷 12 finance（32）+ 卷 13 reporting（23）+ 卷 18 platform（24）
   —— 这三个服务不在集成头，`plan` 的微服务闸会挡住它们的全部模块。
   其中 **1.1.3.2 资金与回款风险**的上游正是 12 财务 ⇒ 它也永久排不出，
   1.1.3.1 已如实把「占用资金」从呆滞风险里**留白**而不是编成本价。
2. **卷 01 的导航入口未登记**：`/dashboard/todo`、`/dashboard/fulfillment-health`、
   `/dashboard/inventory-risk`、`/dashboard/search` 四个路由都**没有**进 `packages/ui/src/lib/nav-config.ts`
   （那个文件是多车道共用的，本批四条车道都刻意不碰）。要不要在导航里给「工作台」挂一组，等调度者决定。
3. **1.1.1.1 我的待办 / 1.1.1.3 审批待办** 仍未交付 —— 本批派发的 1.2.1.2 / 1.2.2.2 等卷 01 第二批落地后，
   卷 01 的首屏才算齐。
4. **`lane.mjs plan` 的分支复用不 rebase**（本轮自纠的那一条）—— 建议补逻辑 + itest。
