# 2026-10-22 · batch-59 · 广告组管理 / 批量操作 / 广告组与词报表 / 促销列表

> 本轮为**组批模式**：回收 4 条已落地车道 → L2 批量全量验证 → 写说明 → commit → push。
> 集成头 `integration/module-dev`，基线 `abf6861e` → `c4f28a45`。
> 进度基线：465 个 L4 模块，**已交付 305 / 进行中 0 / 未开发 160**（完成率 65.6%）。

## 本轮总览

| 模块 ID | 名称 | 迁移 | 服务 | 车道 | 并入 commit |
| --- | --- | --- | --- | --- | --- |
| 14.1.2.1 | 广告组管理 | `V281__ad_group_management.sql` | channel | feat/14-1-2-1 | `d5af366a` |
| 14.1.1.3 | 批量操作 | `V282__ad_campaign_bulk_operations.sql` | channel | feat/14-1-1-3 | `1d7f7dfd` |
| 14.3.1.2 | 广告组与词报表 | `V283__ad_group_keyword_reports.sql` | channel | feat/14-3-1-2 | `b6b2ca33` |
| 14.4.1.1 | 促销列表 | `V284__ad_promotion_lists.sql` | channel | feat/14-4-1-1 | `c4f28a45` |

四条车道均属 **L4 模块 14 广告与增长**，主责服务 `channel`（蓝图 README 的「后端主责服务」列第一项）。

---

## 模块 14.1.2.1 广告组管理

**这个功能做什么**：运营在广告活动下面维护「广告组」（一组投给同类商品的广告集合），
可以新建/修改组、把商品挂进或摘出组、看单个组的详情与汇总、查看组级的操作留痕。

**技术落地**：

- 迁移：`V281__ad_group_management.sql`
  - 新增表 `ad_group_operation_log`（组级操作留痕）；
  - 在既有表 `ad_campaign_group` 上**新增 4 列**：`match_type`（匹配类型，平台原文）、
    `targeting_settings`（定向设置，平台原文串）、`pending_platform_writeback`（组级待回写标记）、
    `local_changed_at`（本地最后改动时刻，可空）。
  - **RLS**：`tenant_id NOT NULL` + `ROW LEVEL SECURITY` + 2 条 `CREATE POLICY`（DB 层隔离，非应用层过滤）。
  - ⚠️ 4 个新列里 3 个是 `NOT NULL`，**全部带 `DEFAULT`**（`''` / `false`）——
    因为 V277 已交付的老 INSERT 不写这些列，无 DEFAULT 会必然违约。
- 端点：`GET /channel/ad-groups/capabilities`（能力声明）、
  `GET /channel/ad-groups/options`（筛选项）、`GET /channel/ad-groups/product-candidates`（可挂商品候选）、
  `GET /channel/ad-groups/summary`（汇总）、`GET /channel/ad-groups/{groupId}`（组详情）、
  `GET /channel/ad-groups/{groupId}/operations`（组级操作留痕）、
  `PATCH /channel/ad-groups/{groupId}`（改组）、`PUT /channel/ad-groups/{groupId}/products`（组内商品集合维护）。
- 页面：`apps/web/src/app/ads/groups/page.tsx` → 工作区 `apps/web/src/components/ads/ad-groups.tsx`（887 行）。
  筛选维度含活动 / 组状态 / 匹配类型 / 商品候选；操作含新建组、改组、组内商品增删、查看留痕。
  **错误态 / 空态 / 加载态均已实现**（分别有 24 / 3 / 4 处处理）。
- 测试：后端 `AdGroupManagementIntegrationTest` **15 例**（Testcontainers PostgreSQL 16，含多租户三断言）；
  前端 `ad-group-model.test.ts` **20 例** + `ad-group-allowlist.test.ts` **5 例**（api-proxy 白名单放行形状核对）。

**关键设计决策**：

1. **组级 `pending_platform_writeback` 而不是复用活动级 `ad_campaign.pending_platform_writeback`**（V273）。
   改一个组不该把整个活动标成待回写 —— 活动级标记的粒度太粗，回写队列会做无效工作。
2. **`targeting_settings` 存平台原文串，不做拆解与归一**。
   归一化（地域/人群/设备/时段/匹配类型 → 结构化维度）归属 **14.1.3.2 投放定位**（本批下一轮派发）。
   本模块只负责**原样存取 + 组级读回写标记**，把解读留给下游，避免两个模块抢同一列语义。
3. **`local_changed_at` 可空且不带 DEFAULT**：可空列不需要 DEFAULT（`NULL` 即默认值），
   语义是「本地没动过这个组」，与活动级字段保持一致。

---

## 模块 14.1.1.3 批量操作

**这个功能做什么**：对广告活动做批量操作（批量调预算 / 批量改竞价 / 批量启停等），
先选中对象 → **预览**将要发生的改动 → 提交成任务 → 轮询任务执行结果。

**技术落地**：

- 迁移：`V282__ad_campaign_bulk_operations.sql`
  - 批量任务表 + 任务项表 + 逐项执行结果留痕。
  - **RLS**：`tenant_id NOT NULL` + `ROW LEVEL SECURITY` + **3 条** `CREATE POLICY`（比其它迁移多一条 ——
    任务项与任务本体两个层级都要单独隔离）。
- 端点：`GET /channel/ad-bulk-operations/capabilities`、`GET /channel/ad-bulk-operations/options`、
  `GET /channel/ad-bulk-operations/selection`（当前选中项）、`GET /channel/ad-bulk-operations/tasks`（任务列表）、
  `GET /channel/ad-bulk-operations/tasks/{id}`（任务详情）、`POST /channel/ad-bulk-operations/preview`（**改前预览**）、
  `POST /channel/ad-bulk-operations/tasks`（提交批量任务）。
- 页面：`apps/web/src/app/ads/bulk-operations/page.tsx` → `apps/web/src/components/ads/ad-bulk-operations.tsx`（756 行）。
  筛选维度含活动 / 操作类型 / 任务状态；操作含多选、预览、提交、任务轮询。
  错误态 / 空态 / 加载态均已实现（18 / 1 / 3 处）。
- 测试：后端 `AdBulkOperationIntegrationTest` **22 例**；前端 `ad-bulk-operation-model.test.ts` **17 例** +
  `ad-bulk-operations-allowlist.test.ts` **5 例**。

**关键设计决策**：

1. **必须有 `preview` 端点，批量改动不直接落库**。
   批量操作作用于几十上百个活动，错一次就是真金白银的损失；
   「先预览、再提交」把决策权交回人，也给后面的幂等设计留了边界。
2. **执行器 `AdBulkOperationExecutor` 与规则 `AdBulkOperationRules` 独立成类**，
   不塞进 Service：批量任务的执行节奏（逐项、限流、部分失败）与 CRUD 是两种关注点，
   拆开才能单独测执行语义。
3. **迁移不带任何无 DEFAULT 的 `ADD COLUMN`、不带 `SET NOT NULL`**，静态预检零命中 —— 与 V277/V281 的既有表零冲突面。

---

## 模块 14.3.1.2 广告组与词报表

**这个功能做什么**：从**广告组 / 关键词 / 搜索词**三个维度看效果，
按维度对比数据、真分页真筛选真排序出报表，并支持按当前筛选批量选中导出。

**技术落地**：

- 迁移：`V283__ad_group_keyword_reports.sql`
  - 组级 / 词级 / 搜索词级指标与报表快照表。
  - **RLS**：`tenant_id NOT NULL` + `ROW LEVEL SECURITY` + **3 条** `CREATE POLICY`。
- 端点：`GET /channel/ad-group-keyword-reports/capabilities`（**组级指标显式 `available=false`**）、
  `GET /channel/ad-group-keyword-reports/options`（筛选项 + 口径定义 + 缓存说明）、
  `GET /channel/ad-group-keyword-reports/rows`（报表表，真分页/真筛选/真排序 + 汇总）、
  `GET /channel/ad-group-keyword-reports/exports`（导出留痕列表）、
  `POST /channel/ad-group-keyword-reports/exports`（按当前筛选批量选中导出）、
  `GET /channel/ad-group-keyword-reports/exports/{exportId}`（单条留痕详情）。
- 页面：`apps/web/src/app/ads/group-keyword-reports/page.tsx` → `apps/web/src/components/ads/ad-group-keyword-report.tsx`（795 行）。
  筛选维度含维度类型（组/词/搜索词）、活动、时间窗；操作含排序、汇总、批量选中导出。
  错误态 / 空态 / 加载态均已实现（19 / 1 / 5 处）。
- 测试：后端 `AdGroupKeywordReportIntegrationTest` **19 例**；前端 `ad-group-keyword-report-model.test.ts` **23 例** +
  `ad-group-keyword-report-allowlist.test.ts` **6 例**。

**关键设计决策**：

1. **组级指标显式声明 `available=false`**，而不是直接不返回。
   上游 14.1.2.1 广告组管理在本批才交付，组级指标口径还没稳定 ——
   用能力声明把「还没有」变成接口的一等公民，界面据此降级，而不是拿一个假数字糊弄。
2. **首 token 取最长的 `ad-group-keyword-reports`**，避免与将来的 `ad-group-reports`、`ad-keyword-reports` 撞车，
   也避免与 14.1.2.1 的 `ad-groups` 抢前缀。
3. **导出是集合级而不是单条级**（`POST /exports` + 按 id 取 CSV），
   形状与 14.3.1.1 的 `ad-reports` 一致，避免同一语义两套路径。

---

## 模块 14.4.1.1 促销列表

**这个功能做什么**：管理促销活动的列表与详情 —— 建促销、挂商品、设护栏（折扣/库存/时间窗）、
校验促销是否可执行、改状态，并看促销是否会被护栏拦下。

**技术落地**：

- 迁移：`V284__ad_promotion_lists.sql`
  - 表 `ad_promotion`（促销主体）、`ad_promotion_product`（促销商品）、
    `ad_promotion_guardrail`（护栏：折扣、库存、时间窗）、`ad_promotion_check`（校验结果留痕）。
  - **RLS**：`tenant_id NOT NULL` + `ROW LEVEL SECURITY` + **4 条** `CREATE POLICY`（四个表各一条）。
- 端点：`GET /channel/ad-promotions/capabilities`、`GET /channel/ad-promotions/options`、
  `GET /channel/ad-promotions/guardrails`、`GET /channel/ad-promotions/promotions`（列表）、
  `GET /channel/ad-promotions/promotions/{id}`（详情）、`POST /channel/ad-promotions/promotions`（新建）、
  `POST /channel/ad-promotions/promotions/{id}`（改）、`POST /channel/ad-promotions/promotions/{id}/check`（校验）、
  `POST /channel/ad-promotions/promotions/{id}/status`（改状态）、`POST /channel/ad-promotions/guardrails`（设护栏）。
- 页面：`apps/web/src/app/ads/promotions/page.tsx` → `apps/web/src/components/ads/ad-promotions.tsx`（885 行）。
  筛选维度含活动 / 促销状态 / 护栏状态；操作含新建、改、挂商品、设护栏、校验、改状态。
  错误态 / 空态 / 加载态均已实现（28 / 3 / 4 处）。
- 测试：后端 `AdPromotionIntegrationTest` **14 例**；前端 `ad-promotion-model.test.ts` **16 例** +
  `ad-promotion-allowlist.test.ts` **5 例**。

**关键设计决策**：

1. **护栏（`ad_promotion_guardrail`）与校验结果（`ad_promotion_check`）分表**：
   护栏是「规则」，check 是「某次校验的事实」，两者生命周期不同 —— 混在一张表里，
   每次校验都要重写规则，审计链就断了。
2. **促销放在 channel 服务而不新建服务**：促销数据要挂广告活动与商品，
   与广告域同库同服务才能用显式 SQL 做真聚合；跨服务就只剩快照。
3. **`/status` 独立成端点而不是 `PATCH` 的普通分支**：
   状态迁移有明确的状态机与前置校验（护栏不过就不许发布），单独成端点便于审计与前端做二次确认。

---

## 调度器本轮的其他动作（不属于某个模块，但影响台账）

### 1. 修复 14.1.2.1 台账行的畸形格式（false「未开发」）

- **现象**：车道的 docs commit（`c075e2c9`）重写台账行时**漏掉了行尾的 `|`**，
  该行只有 6 根竖线（正常 L4 行是 7 根）。
- **机制**：`sync.mjs` 用 `/^\|\s*(\d+\.\d+\.\d+\.\d+)\s*\|([^|\n]*)\|…\|([^|\n]*)\|/gm` 解析台账，
  **每格不允许跨行**。行尾缺 `|` ⇒ 该行匹配失败 ⇒ `existing.get(id)` 返回 undefined ⇒
  状态回落成「未开发」、证据被抹平成 `—`。
  连带后果：并入后统计少 1 个已交付（305 → 304），且该模块会被误判成「还能再排一次」。
- **修复**：把该行行尾补回 `|` 后重跑 `sync.mjs`，14.1.2.1 恢复「已交付」，统计回到 **305**。
- **排查范围**：全量扫了 465 行，**畸形行只有这一行**（其余 464 行均为 7 根竖线），无遗漏。
- **给 worker 的硬要求**（已写进本批 4 条新车道的任务书）：
  **台账行必须以 `|` 结尾**；漏一根竖线会让 `sync.mjs` 把「已交付」回落成「未开发」。

### 2. 闸等待

本轮开工时调度闸被**另一个活着的 tick** 持有（`gate=held`, `stale=false`）。
按协议**不抢、不清、不 force**，改用密集 `gate acquire` 竞态等待（`mkdirSync` 原子互斥），
16 次尝试后在 **03:08:54** 取得闸。等待期间只做只读取证，未碰 git 状态。

### 3. L2 与在途车道的互斥

按协议「**L2 不得与在途车道的 L1 并发跑**」（2026-10-19 实测：并发时 PG 峰值 16，
后端 1364 例里 3 failures + 24 errors **全是假红**），
本轮把 L2 排在**新车道派发之前**执行：L2 跑完、push 完成后才派发 4 条新车道，
避免 L2 与 worker 的 Testcontainers 抢资源造成成片假红。

---

## 本批派发（下一轮回收）

L2 与 push 完成后派发 4 条新车道，L3 锚点互不相同、互不依赖：

| 模块 ID | 名称 | 迁移 | L3 锚点 | 分支 |
| --- | --- | --- | --- | --- |
| 14.1.3.2 | 投放定位 | V285 | 14.1.3 | feat/14-1-3-2 |
| 14.4.1.2 | 优惠券与秒杀 | V286 | 14.4.1 | feat/14-4-1-2 |
| 14.4.2.1 | 促销效果分析 | V287 | 14.4.2 | feat/14-4-2-1 |
| 14.5.1.1 | 达人库 | V288 | 14.5.1 | feat/14-5-1-1 |

---

## 静态预检（L2 之前的「已知互相破坏形状」扫描）

对 V281–V284 四个新迁移扫描：

| 形状 | 命中 | 结论 |
| --- | --- | --- |
| `ADD COLUMN` 无 `DEFAULT` | 1 处（V281 的 `local_changed_at timestamptz`） | **误报**：该列可空，可空列不需要 DEFAULT |
| `SET NOT NULL` | 0 | 无 |
| `ADD CONSTRAINT ... CHECK` | 0 | 无 |

⇒ **零命中，可安全进入 L2。**

---

## L2 批量验证结果

在集成头 `c4f28a45` 上跑**本批唯一一次**全量验证：

```bash
cd backend && ./mvnw verify
cd .. && pnpm typecheck && pnpm lint && pnpm test
NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build
node tools/module-progress/sync.mjs --check
```

**结果：🟢 全绿**（2026-10-22 03:16:17 → 03:59:41，含后端 41:33）

| 阶段 | 命令 | 结果 |
| --- | --- | --- |
| 后端全量 | `cd backend && ./mvnw verify` | ✅ **BUILD SUCCESS**（41:33）—— tenancy / identity / catalog(5:08) / channel(8:08) / warehouse(11:26) / trade(15:19) / crm **全绿**，0 failures / 0 errors |
| 类型 | `pnpm typecheck` | ✅ 3/3 任务通过 |
| Lint | `pnpm lint` | ✅ 3/3 任务通过（仅既有 `react-hooks/exhaustive-deps` 警告，无 error） |
| 前端单测 | `pnpm test` | ✅ **440 个测试文件 / 6585 例全过** |
| Next 构建 | `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | ✅ 构建成功 |
| 台账一致性 | `node tools/module-progress/sync.mjs --check` | ✅ 「台账与蓝图一致」 |

⇒ 本批 4 个模块（14.1.2.1 / 14.1.1.3 / 14.3.1.2 / 14.4.1.1）**允许 push**。
本轮**只跑了这一次**全量验证，没有二次验证。

## 后续待办

- 14.5.1.1 达人库的**平台侧数据同步适配器未交付**（channel 的 `vendor/` 目前只有 OAuth 适配器），
  该车道按「手动录入完整做完 + 同步端口明确表达未接入」交付，不 mock。
- 13.4 利润报表服务（`reporting`）不在集成头里 ⇒ 13.x 全部模块处于服务缺口状态，
  `plan` 持续把它们标为 `serviceBlocked`；14.4.2.1 的毛利分析因此自带快照计算，不依赖 13.4。
- 14.4.1.2 与 14.4.2.1 同批派发但锚点不同：14.4.2.1 只依赖**已交付**的 14.4.1.1 促销列表，
  不依赖同批的 14.4.1.2 优惠券，避免批内依赖倒挂。
