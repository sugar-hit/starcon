# 2026-10-23 1.1.2.3 履约健康（卷 01 · L3 1.1.2 关键指标 · 阶段 D7）

> 车道：`feat/1-1-2-3`（worktree `integration/.worktrees/1.1.2.3`）。
> 蓝图原文见 `docs/product/capability-blueprint/01-dashboard-and-portal.md` 第 110–115 行。
> **零迁移、零新表、零写端点** —— 卷 01 是只读投影层。

## 决策摘要

1. **后端落在既有 trade 服务，不新增服务**。卷首写死「后端归属：前端聚合层 + 各服务的只读投影
   （**不新增业务服务**）」，所以履约健康是 trade 服务里的一个新**只读**命名空间
   `/trade/fulfillment/health`（capabilities / summary / red-flags 三个 GET，无任何写端点）。
2. **「平均出库时长」刻意不落在 trade**。出库时效属 08 仓储作业 8.5.3.1（warehouse 服务），
   它自己出数（`/warehouse/outbound-timeliness/report`），前端合并时补上那张卡。
   本模块**不**跨服务查 warehouse 的库 —— AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图。
3. **迟发率与有效追踪率直接引用 9.4.1.2 的口径单点**（`ShipmentRecordService.performance`），
   不另立第二套算法。台账里「超时发货率」与「平台迟发率」两行指的是同一个测量，
   本模块**如实写明这一点**（卡片的 basis 里逐字说明），而不是造出两个看起来不同的数。
4. **平台 ODR 如实标未接通**。仓内**没有** `platform_performance_metric` 表，
   也没有 A-to-z 索赔 / 拒付 / 差评率这类事实表 ⇒ `available=false` +
   `ownerModule=02 渠道与店铺 · 平台绩效指标同步` + `impact`，
   卡片显示「未接通 + 灰灯」而不是 0%。**没有为了让页面好看而造一张表。**
5. **红灯项与卡片同源**：卡片带 `redFlagKind` + `alertCount`，
   「红灯项列表按该 kind 过滤的 total」必须等于 `alertCount` ——
   这是「点进去看到的条数」与「卡片上的数字」不会打架的保证，写进了集成测试。
6. **灯色只由业务事实判定，不用拍脑袋的百分比阈值**。平台迟发率与有效追踪率的门槛
   按平台与店铺不同，仓内无阈值配置 ⇒ 本模块只报数与绝对单量，不判定「是否超过平台门槛」。

## 关键 trade-off

| 取舍 | 选择 | 代价 |
| --- | --- | --- |
| 红灯项分类 | **五类**（待发货超时 / 迟发 / 轨迹异常 / 无轨迹观测 / 代发异常） | 一票货可能同时出现在两类里（迟发且无轨迹）—— 刻意如此：那是**两个**问题，各有各的举证材料 |
| 待发货积压卡的口径 | **计数**（`metricKind=COUNT`，没有分子分母） | 硬塞一个分母会让「3 件」读成「3/5 = 60%」；代价是这一格不参与比率语义 |
| 出库时效数据源 | **跨服务取数**（warehouse 的既有只读报表） | 前端要做一次合并；但换成「在 trade 里查 warehouse 的表」是跨服务 SQL，直接违规 |
| 红灯项分页 | **UNION ALL 一次查询**（而不是四次查询后 Java 侧合并） | SQL 长一点；换来的是分页边界不会随排序漂移，total 与卡片对得上 |
| 租户来源 | **已认证成员关系**（控制器把 `actor.tenantId()` 传进服务层，每条查询显式 `tenant_id = ?`） | 比「只靠 RLS」多一层显式谓词；换来的是伪造 `app.current_tenant` 时读到**零行**而不是别家的量 |

## 指标口径表

| 卡片 | 公式 | 分母 | 来源表 |
| --- | --- | --- | --- |
| 待发货积压 | 已打包未交运未作废的待发货单元数（计数） | —（不是比率） | `shipment_pending_unit`（V124） |
| 平均出库时长 | 已发货出库单「订单到出库」时长均值（分钟） | 区间内已发货出库单数；终点未发生者进缺失样本数，不混进分母 | `outbound_order` + `pick_task` / `packing_order` / `shipment_handover`（warehouse V148，**跨服务取数**） |
| 超时发货率 | 发货时刻晚于发货时限的发货记录数 ÷ 登记过时限的记录数 | **非作废且已登记时限**的记录数 | `shipment_record`（V127） |
| 物流异常件占比 | 轨迹状态 EXCEPTION 的记录数 ÷ 全部非作废记录数 | 全部非作废发货记录数 | `shipment_record`（V127） |
| 平台迟发率 | 同「超时发货率」（同一分子同一分母） | 同上 | `shipment_record`（V127） |
| 有效追踪率 | （有主运单号 **且** 至少一条轨迹观测）的记录数 ÷ 全部非作废记录数 | 全部非作废发货记录数 | `shipment_record` + `shipment_record_tracking_observation`（V127） |
| 平台 ODR | — | — | **无上游 ⇒ 未接通**（ownerModule：02 渠道与店铺 · 平台绩效指标同步） |

## 文件清单

**后端（trade 服务，新增 3 主源 + 2 测试）**
- `backend/services/trade/src/main/java/com/starcon/trade/FulfillmentHealthModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/FulfillmentHealthService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/FulfillmentHealthController.java`
- `backend/services/trade/src/test/java/com/starcon/trade/FulfillmentHealthIntegrationTest.java`（14 例）
- `backend/services/trade/src/test/java/com/starcon/trade/FulfillmentHealthTenancyIntegrationTest.java`（7 例）

**前端（新增 4 文件）**
- `apps/web/src/components/dashboard/fulfillment-health-model.ts`
- `apps/web/src/components/dashboard/fulfillment-health-model.test.ts`（14 例）
- `apps/web/src/components/dashboard/fulfillment-health-workspace.tsx`
- `apps/web/src/app/dashboard/fulfillment-health/page.tsx`
- `apps/web/src/app/api/trade/fulfillment-health-allowlist.test.ts`（6 例）

**改动（共享文件，只动自己那几处）**
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**（`fulfillment/health` 组），
  插入锚点固定在 `prefix: 'identity/'` 那条元素之前；既有条目一字未改、也不重排。
- `packages/api-contracts/src/trade.openapi.json` + `packages/api-contracts/src/contracts/trade.ts`
  —— codegen 产物，只多了本模块的 9 个类型。
- `docs/product/capability-blueprint/module-progress.md` —— 只改 1.1.2.3 那一行的「状态」「证据」两格。

**没有动的**：`apps/web/src/app/page.tsx`（首页）、`packages/ui/src/lib/nav-config.ts`（导航）、
其余四个服务的契约文件（codegen 曾把它们按键序重排，已 `git checkout` 还原）。

## 后续待办

1. **页面入口**：本模块的路由是 `/dashboard/fulfillment-health`，**未登记进 `nav-config.ts`**
   —— 那个文件是多车道共用的，本车道刻意不碰。要不要在导航里给「工作台」挂一条，
   等调度者决定（那属于「1.1 今日工作台」整体的事，不属于本格）。
2. **平台 ODR（02 渠道）**：等 `platform_performance_metric` 这一族事实落地后，
   在 **channel 服务**（归属方）补只读端点，本模块的 `PLATFORM_ORDER_DEFECT_RATE` 维度
   从 `available=false` 翻成 `true` 即可，**不需要改本模块的任何口径代码**。
3. **承运商轨迹回传（10.3）**：现在「轨迹异常件占比」与「有效追踪率」都只覆盖
   **已登记轨迹**的那部分货（V127 的观测行）。10.3 落地后覆盖面会自动变大，
   但**分母口径要重新复核**（是否要把「无观测」计进分母）。
