# 批次 31 · 发布任务 / 生命周期与下架 / 链接采集 / Listing 表现

> 时间：2026-10-20 04:05 tick 派发 4 条车道 → 04:27 / 04:31 / 04:32 / 待定 四条陆续落地 → 并入集成头 → L2 全量验证
> 集成头：`integration/module-dev`
> 模式：`MODE=full`（TOKEN_HORIZON=2026-10-25 前），LANES=4，THROTTLE_MIN=50，SILENT_WINDOW=0
> 闸：节流 116min ≥ 50 ✓ · therm 无降频 ✓ · load 2.30（10 核机，阈值 12）✓ · Testcontainers PG 0/6 ✓ · running 0/6 ✓ → **GO**
> 前情：batch-30（4.3.1.1 / 16.2.2.1 / 4.2.3.2 / 4.3.2.2）已由上一轮 tick 并入并 push；本轮开局 `merge-ready` 返回空集。

---

## 本批概览

| 模块 | 名称 | 落地服务 | 迁移 | 车道分支 | api-proxy 前缀 | 页面 |
| --- | --- | --- | --- | --- | --- | --- |
| 4.3.1.3 | 发布任务 | channel | V200 | `feat/4-3-1-3` | `listing-publish-tasks` | `/listings/publish`（加第三个页签） |
| 4.1.1.3 | 生命周期与下架 | channel | V201 | `feat/4-1-1-3` | `listing-lifecycles` | `/listings/lifecycle` |
| 4.4.1.1 | 链接采集 | channel | V202 | `feat/4-4-1-1` | `listing-collects` | `/listings/collect` |
| 4.5.1.1 | Listing 表现 | channel | V203 | `feat/4-5-1-1` | `listing-performance` | `/listings/monitor` |

四条车道全部落在 channel 服务（卷 04「刊登与内容」主责 `catalog + channel`，但刊登域的既有 Controller 全在 channel）。
L3 锚点互不相同（4.3.1 / 4.1.1 / 4.4.1 / 4.5.1），无同批互斥。

### 🔴 本批的调度难点：4 条车道同服务、同一个共享白名单文件

`apps/web/src/lib/api-proxy.ts` 是**多车道共用的累积型白名单**，且**不在** `.gitattributes` 的 union 合并清单里
（union 驱动器只覆盖 `packages/api-contracts/src/**` 与台账 `module-progress.md`）
⇒ 4 条车道各加一条 `services[]` 元素，若插入锚点重叠，git 必然判冲突。

调度者在派发前做了两件事：

1. **给每条车道指定互不重叠的插入锚点**（而不是让它们都插在「最后一条 channel 元素之后」这个约定位）：

   | 车道 | 锚点 | 基线行号 |
   | --- | --- | --- |
   | 4.1.1.3 | `listings`（4.1.1.1）元素之后 | 1086 |
   | 4.4.1.1 | `listing-claims`（4.4.2.1）元素之后 | 1595 |
   | 4.5.1.1 | `listing-status-events`（4.3.2.2）元素之后 | 1648 |
   | 4.3.1.3 | `one-click-publish`（4.3.1.1）元素之后 | 1742 |

2. **派发后立刻用 `git merge-tree` 预演合并**（不碰工作区，用临时 index 造 4 个真实提交）：
   6 组两两合并 **全部 CLEAN**，累积式 4 路合并也 **CLEAN**，最终文件里 4 个新元素**都在**。
   ⇒ 合并风险在派发后就地排掉了，而不是等 4 条车道落地才发现冲突。

另外三条共享文件同样按「只碰自己那一段」约束：

- `packages/api-contracts/src/channel.openapi.json` + `contracts/channel.ts` —— codegen 产物，**禁止手改**；
  union 驱动器按「路径 / 类型名」求并，4 条车道的纯新增路径**互不覆盖**。
- `docs/product/capability-blueprint/module-progress.md` —— 各改各那一行的「状态」「证据」两格。
- `packages/ui/src/lib/task-pages.ts` / `nav-config.ts` / channel `pom.xml` —— **一律不许动**（见下「统一边界」）。

### 统一边界（四条车道共同的硬约束，写进了每份任务书）

- 不许改 `channel/pom.xml`（4 条车道共用一个 pom）⇒ 4.4.1.1 的真实抓取只能用已有的 `RestClient`，
  HTML 解析自己写（`ListingCollectHtmlParser`），**不引入 jsoup**。
- 不许改 `packages/ui/src/lib/task-pages.ts` 与 `nav-config.ts` ⇒ 4 个页面**只建页面、不登记 task-pages**，
  与最近已交付车道（4.3.2.2 / 4.4.2.1 / 4.2.3.2）保持一致。
- 不许 `ALTER` 任何既有表 ⇒ 4 条车道的迁移**全部零 ALTER**（4.1.1.3 唯一改到的既有对象是 V171 的**触发器函数**，
  详见该节）。
- 负向断言（allowlist 的「未登记形状必须 404」）只能用**自己前缀**下的形状
  ⇒ 4 条车道各用 `/{自己前缀}/undeclared-action`，避免 batch-30 那次「同批合并后负向断言失效」的返工。

---

## 模块 4.3.1.3 发布任务

**这个功能做什么**：把一次发布任务摊开成一条可读的时间线：内容校验 → 平台创建 → 变体创建 → 媒体上传 →
价格库存设置 → 提交审核，每一步的状态、起止时间、失败原因一目了然，失败步骤可以单独重试。
运营看到的不是「任务失败了」，而是「第 4 步媒体上传失败，原因是 X，点这里重试」。

**技术落地**：

- 迁移：`V200__listing_publish_task.sql`（channel）—— 新表 `listing_publish_step_attempt`（重试尝试留痕），
  `tenant_id NOT NULL` + ENABLE/FORCE RLS + GRANT，**零 ALTER 任何既有表**。
  🔴 **不建「发布任务」表**：4.3.1.1 的 `listing_publish_job_target`（= 一个发布任务）与 4.3.1.2 的
  `publish_task` 已有主，本模块**只读**——这正是任务书里要求复用既有表的落点。
- 端点：`/channel/listing-publish-tasks` 共 **6 个** —— `GET capabilities` / `GET tasks`（真分页 + 来源/阶段/平台/店铺筛选）/
  `GET tasks/{taskId}` / `GET tasks/{taskId}/steps` / `POST tasks/{taskId}/retry`（任务级重试）/
  `POST tasks/{taskId}/steps/{stepKey}/retry`（步骤级重试）
- 页面：`/listings/publish` **加第三个页签**「发布任务（4.3.1.3）」，与既有的「一键刊登（4.3.1.1）」「批量发布（4.3.1.2）」并存
  → `apps/web/src/components/listing-publish-task/`（model / model.test / workspace）
- 测试：`ListingPublishTaskIntegrationTest` 14 + `ListingPublishTaskTenancyIntegrationTest` 3 = **17 例**（含多租户三断言）；
  前端 `listing-publish-task-model.test.ts` 25 + `listing-publish-task-allowlist.test.ts` 4 = **29 例**；
  回归 `-Dtest='ListingPublishJob*'` 21 例全绿（确认 V200 没打破 V196）

**关键设计决策**：

- **步骤级重试用新表而不是改既有步骤行**。`listing_publish_job_step` 是**事实**（那次运行那一步的状态），
  重试会产生新的尝试 —— 改写它等于抹掉历史。所以另立 `listing_publish_step_attempt` 记每次尝试。
- **蓝图六阶段里有两步在 4.3.1.1 没有独立步骤行**（「平台创建」「变体创建」）。本模块**如实报出缺口**，
  不凭空造步骤行来凑满六步 —— 造出来的步骤是假数据。
- **4.3.1.2 的队列任务没有步骤行**，其进度与重试仍归 4.3.1.2，本模块界面上明确标注归属，不越界代管。
- api-proxy 白名单用**字面 step_key 枚举**（TEMPLATE/CONTENT/MAPPING/MEDIA/PRICE/STOCK/SUBMIT/RESULT）
  而不是通配 —— 放行一个不存在的步骤动作比 404 更糟。

---

## 模块 4.1.1.3 生命周期与下架

**这个功能做什么**：管住平台商品的七态生命周期（在售 / 草稿 / 平台审核中 / 平台驳回 / 主动下架 / 平台下架 / 被封禁），
支持人工主动下架与重新上架（下架时可选择是否回写平台），并把每一次状态变化按**来源**（平台 / 人工）分开记账。
它专门消灭蓝图那句加粗：**平台下架与内部下架是两个事实** —— 平台把商品下架了而内部不知情，是最常见的漏单原因。

**技术落地**：

- 迁移：`V201__listing_lifecycle.sql`（channel）—— 新表 `listing_lifecycle_action`（人工动作的**意图**留痕：
  谁、为什么下架、是否选择回写、关联到哪个 writeback 批次）+ 3 条索引 + RLS/GRANT。
  🔴 **零 ALTER 任何既有表**；唯一改到的既有对象是 V171 的**触发器函数**（见下）。
- 端点：`/channel/listing-lifecycles` 共 **9 个** —— `GET capabilities` / `GET filter-options` / `GET `（列表：
  七态 + 来源 + 店铺 + 平台 + 关键词 + 只看差异，真分页）/ `GET diff`（差异区）/ `GET {uuid}`（生命周期视图）/
  `GET {uuid}/timeline`（时间线，倒序）/ `POST {uuid}/actions`（人工动作，下架可选回写）/
  `POST {uuid}/platform-status`（平台状态被动同步）/ `POST sync`（批量同步）
- 页面：`/listings/lifecycle` → `apps/web/src/components/listing-lifecycle/`（model / model.test / workspace）
- 测试：`ListingLifecycleStateMachineTest` 12 + `ListingLifecycleIntegrationTest` 19 +
  `ListingLifecycleTenancyIntegrationTest` 3 = **34 例**（含多租户三断言）；
  前端 model 25 + allowlist 4 = **29 例**；全量 allowlist 回归 69 文件 369 例全绿

**关键设计决策**：

- 🔴 **状态机不自己记事件，靠 DB 触发器**。V171 建 `listing_status_event` 时就写明「事件记录不交给服务层写：
  `listing` 的 status 一变，AFTER UPDATE 触发器就地落一条」。所以人工动作 = **UPDATE `listing.status`**，
  事件由触发器自动落（`source='MANUAL'`），服务层**不直接 INSERT 事件表**（会重复记、也会被既有约束拒）。
- 🔴 **本批唯一改到的既有 DB 对象：V201 用 `CREATE OR REPLACE` 扩展了 V171 的 `record_listing_status_change()` 触发器函数**
  （读事务级 GUC `app.lifecycle_context` 决定事件来源）。不替换的话，平台下架会被记成「人工下架」——
  正是本模块要消灭的那个谎。向后兼容由专门回归测试钉住（无上下文时落库值与原 INSERT 逐字段相同）。
  **这是本批唯一一处「动了别人家的东西」，在此点名。**
- **主动下架复用 `writeback_batch`（kind='DELIST'）**，不另建下架任务表，也**不动** `writeback_batch.kind` 的 CHECK
  （既有行的值域不能动）。**重新上架不给「回写平台」选项** —— 那个 kind 不在值域里，归 4.3.1.2 / 4.3.2.2，
  能力表显式声明 `available=false` + ownerModule。
- **差异判定放在 SQL 侧**，因为差异区要能真分页；Java 侧直接调用 4.3.2.2 的 `internalSellability` /
  `platformSellability`，并由测试逐行比对两侧结论。

---

## 模块 4.4.1.1 链接采集

**这个功能做什么**：粘贴一个外部商品链接（1688 / 淘宝 / 供应商站 / 同行 listing），
系统真的去把那个页面抓下来，解析出标题、主图、规格、属性、描述，存成一条**采集草稿**，
等着后续被认领成内部商品。抓不到、白名单外、太频繁，同一个链接重复提交，都有明确的不同原因与可操作提示。

**技术落地**：

- 迁移：`V202__listing_collect.sql`（channel）—— 三张新表：
  `listing_collect_source`（**全局码表**，白名单来源，带 `tenant_id` 的业务表是下面两张）+
  `listing_collect_task`（采集任务：状态 / 尝试次数 / HTTP 状态 / 错误 / 起止时间 / 规范化 URL）+ 
  `listing_collect_draft`（采集草稿：标题 / 主图 URL / 图片 URL 列表 / 规格 / 属性 / 描述 / 来源价 / 抓取时间），
  业务表 `tenant_id NOT NULL` + ENABLE/FORCE RLS + GRANT，**零 ALTER 任何既有表**。
- 端点：`/channel/listing-collects` 共 **4 个** —— `GET capabilities`（逐维度能力声明 + 当前白名单）/
  `GET `（采集历史：真分页 + 状态/来源筛选 + 时间倒序）/ `POST `（发起一次链接采集）/
  `GET {uuid}`（采集任务详情：任务 + 草稿）
- 页面：`/listings/collect` → `apps/web/src/components/listing-collect/`（model / model.test / workspace）
- 测试：`ListingCollectIntegrationTest` 24 + `ListingCollectTenancyIntegrationTest` 3 = **27 例**（含多租户三断言，
  抓取用 JDK 自带 `com.sun.net.httpserver.HttpServer` 起**真实本地 HTTP 服务**返回真实商品页 HTML，
  断言解析器真的把标题/主图/规格解析出来 —— 不是断言「返回了预先写好的常量」）；
  前端 `listing-collect-model.test.ts` 25 + `listing-collect-allowlist.test.ts` 4 = **29 例**；
  allowlist 全量回归 **87 文件 / 537 例**全绿

**抓取实现的真实落点**：

- **HTTP 客户端**：`RestClient`（JDK factory，`spring-boot-starter-web` 已带）—— 5s 连接 / 15s 读取 / **4MB 响应截断**；
  **手动跟随重定向最多 5 跳，且每一跳都重验白名单**（自动跟随会把请求带到内网，是 SSRF 的经典入口）。
- **解析策略**：`ListingCollectHtmlParser`（零新增依赖，JDK 正则 + Jackson）—— 只认 JSON-LD `Product` /
  Open Graph / meta / `<title>` / 规格表。**商品信号判据**：必须命中 JSON-LD Product / `og:title` / `og:image` 之一 ——
  光有 `<title>` 不算（404 页 / 列表页 / 登录页也都有 `<title>`）。
- **白名单来源**：`listing_collect_source` 是**全局码表**（不带 `tenant_id`、只授 SELECT ⇒ 租户不能自行加域名），
  匹配规则是「host 完全相等 或以 `.`+host 结尾」⇒ `evil-1688.com` **不命中** `1688.com`。白名单外一律 400。
- **限频**：基准是 `listing_collect_task` 抓取历史的投影（**不另建限频表**），按来源行的 `min_interval_seconds` 配；
  超限 429，并写明**还需等多少秒**。
- **去重**：规范化 URL（去跟踪参数 / 归一 host / 排序 query / 去 fragment，**路径大小写保持原样**）上的
  **部分唯一索引 `... WHERE state='SUCCEEDED'`** —— 失败的抓取**不占名额**，网络抖动不会把一个 URL 锁死。

**关键设计决策**：

- **只记图片 URL，不下载二进制、不写 catalog 的媒体表**。蓝图写「图片下载入媒体库」，但媒体库归 03 catalog，
  AGENTS.md 禁止跨服务数据库访问 ⇒ 采集草稿只存 URL，媒体入库留给 4.4.1.3 采集箱 → 3.1.1.1 建档那条链路。
- **解析落点只用稳定结构**，**不依赖任何平台的私有 DOM 类名** —— 1688 / 淘宝的类名随时会变，写死就是假实现。
- **采集只产生草稿**：草稿表**没有** sku / spu / stock / price 任何一列（`source_price_amount` 是**外部页面原文标价**，
  不是我方售价 —— 售价归 05 定价）；不建 SPU / SKU（那是 03 的 3.1.1.1），
  也不顺手做 4.4.1.2 的批量/插件采集或 4.4.1.3 的采集箱（`api-proxy` 刻意不放行 `/batch`、`/box`、`/{uuid}/claim`）。
- 🔴 **不引入 jsoup**（`channel/pom.xml` 是 4 条车道共用的，不许动）⇒ 自己写 `ListingCollectHtmlParser`。

---

## 模块 4.5.1.1 Listing 表现

**这个功能做什么**：单个 listing 的表现明细：曝光、点击、转化率、销量、销售额、Session、退货率、评分变化，
支持时间范围对比（本期 vs 上期）与日/周/月趋势；界面上每个指标都带「平台口径」角标，
并把「平台把商品下架了而内部不知情」这类**平台口径与内部口径的差异**摆在明处。

**技术落地**：

- 迁移：`V203__listing_performance.sql`（channel）—— 新表 `listing_performance_snapshot`（平台口径表现快照，
  一行 = 一个 listing 的一个平台统计日）+ `listing_performance_sync`（平台表现数据的同步结果），
  `tenant_id NOT NULL` + ENABLE/FORCE RLS + GRANT，**零 ALTER 任何既有表**；
  复合外键 `(tenant_id, listing_id)` ⇒ 跨租户挂引用在物理上不可能；
  两个触发器钉死「快照引用的同步批次必须同商品」与「同步批次终态不可再改」。
- 端点：`/channel/listing-performance` 共 **4 条 path / 5 个端点** —— `GET capabilities` /
  `POST `（平台表现数据同步结果入库）/ `GET `（快照列表，真分页 + listing/店铺/平台/统计日范围筛选）/
  `GET {listingId}`（表现明细 + 本期 vs 上期）/ `GET {listingId}/trend`（趋势）
- 页面：`/listings/monitor` → `apps/web/src/components/listing-performance/`（model / model.test / workspace，图表用 recharts）
- 测试：`ListingPerformanceIntegrationTest` 22 + `ListingPerformanceTenancyIntegrationTest` 4 = **26 例**（含多租户三断言）；
  前端 model 25 + allowlist 4 = **29 例**；全量 allowlist 回归 68 文件 348 例全绿

**关键设计决策**：

- 🔴 **数据只从 ingest 端点进来，绝不去 trade 的订单表聚合**（跨服务查表是 AGENTS.md 明令禁止的），
  也**不造换算系数**把平台销量与内部订单行数硬凑成一个。每个响应带 `dataSource='PLATFORM_REPORTED'`，
  前端顶部固定横幅 + 每个指标卡「平台口径」角标 —— 蓝图加粗句「展示时需标注来源」是硬要求。
- **比率不落表**（照抄 9.5.2.1 的做法）：落了表就会与事实漂移。一律 SQL 现算，
  分母为 0 时返回 **null 而不是 0 或 100** —— 报 0 会被读成「转化率 0%」，那是结论不是缺口；上期为 0 时变化率同理。
- **卷 13 / 卷 14 未交付 ⇒ capabilities 显式声明 `available=false` + ownerModule + impact**，
  页面显示「未接入」而不是假数据。
- ingest 走**批次级幂等键 + 同日覆盖 `revision` 递增**，区间外的快照逐条跳过而不是整批失败。

---

## 验证与收尾

### 静态预检（L2 之前，本批 4 个新迁移）

| 形状 | 结果 |
| --- | --- |
| ① `ADD COLUMN` 无 `DEFAULT`（别人的老 INSERT 不写这列 ⇒ 必违约） | **0 命中** |
| ② `SET NOT NULL`（目标表无 BEFORE INSERT 触发器兜底） | **0 命中** |
| ③ 新增 `ADD CONSTRAINT ... CHECK` | **0 命中** |
| 既有表 ALTER | **0**（V200 / V202 / V203 零 ALTER；V201 只 `CREATE OR REPLACE` 一个触发器函数） |

### L2 批量验证（集成头上跑一次，唯一发热高峰）

```
L2 汇总：backend=0 typecheck=0 lint=0 test=0 nextbuild=0 sync=0
L2_RESULT=GREEN
```

| 段 | 结果 |
| --- | --- |
| 后端 `./mvnw verify` | **BUILD SUCCESS**，25:12 min，**5516 例 0 失败 0 错误 0 跳过**（identity 270 / catalog 1502 / channel 800 / warehouse 1535 / trade 1391 / crm 18） |
| `pnpm typecheck` | 3/3 通过 |
| `pnpm lint` | 3/3 通过 |
| `pnpm test` | **285 文件 / 4623 例全绿** |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | 成功（静态预渲染 + 动态服务端渲染均正常） |
| `node tools/module-progress/sync.mjs --check` | ✓ 台账与蓝图一致 |

**本批 9 个新后端测试类全部在 L2 里实跑**：ListingPublishTaskIntegrationTest 14 + Tenancy 3、
ListingLifecycleStateMachineTest 12 + Integration 19 + Tenancy 3、ListingCollectIntegrationTest 24 + Tenancy 3、
ListingPerformanceIntegrationTest 22 + Tenancy 4 = **104 例**。

⚠️ L2 跑之前已确认 **0 条在途车道、0 个带 Testcontainers 标签的 PG 容器、无 surefire 进程** ——
L2 与在途车道的 L1 并发会抢 PG 与 `target/`，2026-10-19 20:00 实测出过一整批假红。
本批是「4 条车道全部落地 → 一次 merge → 一次 L2」，没有并发。

### 并入与台账

- 4 条车道**零冲突**并入 `integration/module-dev`（merge commit：`2e225d92 → a6904493 → 45af10ce → 254eb125 → 771569eb`）。
  api-proxy 的 4 条新元素分别落在 1121 / 1661 / 1756 / 1892 行，**互不重叠**（派发前已用 `git merge-tree` 预演验证）。
- 台账：465 行、**0 个重复模块 ID**；4 行状态由「未开发」改为「已交付」，证据列各自写明迁移与端点。
- `sync.mjs` 口径：已交付 **222 → 226**，未开发 **243 → 239**，进行中 0，完成率 **48.6%**。

### 提交与推送

- 说明 + 台账**同一个 commit**（`docs(batch-31):` 前缀，与 batch-21 ~ batch-30 的既有惯例一致）。
- push 目标：`origin integration/module-dev`（**绝不 push main**）。

