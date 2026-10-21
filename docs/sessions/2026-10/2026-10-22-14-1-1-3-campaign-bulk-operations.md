# 14.1.1.3 批量操作（卷 14 广告与增长，L3 锚点 14.1.1）

- **日期**：2026-10-22
- **车道**：`feat/14-1-1-3`（worktree `integration/.worktrees/14.1.1.3`）
- **蓝图**：docs/product/capability-blueprint/14-ads-and-growth.md 第 94–99 行
- **迁移**：channel `V282__ad_campaign_bulk_operations.sql`

---

## 决策摘要

**职责句逐项落点**：「跨活动批量调整：预算增减、竞价调整、状态切换、结束时间设置，支持按筛选结果全选并预览变更。
输入：选中活动集合；调整参数；预览确认。输出：批量调整任务（异步）+ 每条执行结果。」

| 蓝图词 | 落地 |
| --- | --- |
| 预算增减 | `ADJUST_BUDGET` / `SET_BUDGET` → 写 `ad_campaign.daily_budget` |
| 竞价调整 | `ADJUST_BID` → 落点是**广告组**（`ad_campaign_group.default_bid`），一次请求可带多个活动的多个组 |
| 状态切换 | `PAUSE` / `ENABLE` → 写 `ad_campaign.status` |
| 结束时间设置 | `SET_END_AT` → 写 `ad_campaign.end_at`（**此前没有任何端点写过这一列**） |
| 按筛选结果全选 | `GET /selection` —— **服务端**按筛选条件解析出 id 集合（分页 + 游标 + 硬上限） |
| 预览变更 | `POST /preview` —— **不落库**的预演：逐条「会变成什么样」+ 总预算增量 + 护栏裁决 |
| 批量调整任务（异步） | `POST /tasks` → `ad_campaign_bulk_task` + `ad_campaign_bulk_task_item`，由 `AdBulkOperationExecutor` 在请求之外跑完 |
| 每条执行结果 | `GET /tasks/{uuid}` → 逐条 `APPLIED / REJECTED / SKIPPED` + 原因 |
| 单次上限与总消耗上限 | 8 个护栏上限常量，**随响应返回**（`/options`）+ **随任务快照进表**（`guardrail_*` 列） |

### 关键 trade-off

**1. 护栏越限 ⇒ 整批拒绝（与 14.1.1.1 的逐条拒绝刻意不同）。**
14.1.1.1 的 `bulk-operations` 是「逐条 APPLIED / REJECTED / SKIPPED」—— 一条失败不影响其余。
本模块**不**沿用那一套：蓝图加粗句是「**误操作一次可能烧掉整月预算**」，半批执行比零批执行更难解释
（运营要重跑一次才知道到底改了什么）。所以超上限 ⇒ `status=REJECTED`、**一条都不执行**，
但**每一条都留痕**（「是第 7 条越了上限」是事后复盘要看的第一件事）。
逐条业务规则（「已归档的活动不能暂停」）仍然是**逐条**拒 —— 那是事实层面的不匹配，不是「这次操作太危险」。
代价：两种语义并存，界面必须把「整批被拒」与「这一条被拒」分开渲染；收益：护栏的语义单一而可预期。

**2. 总额上限按「绝对敞口」判，不按「净变化」判。**
「+1000 与 −1000 净变化是 0，但动的是 2000 的钱」—— 只按净变化判，一批「有的加有的减」就能把
总额上限绕过去，而这正是最典型的烧钱形状。所以预览与任务都同时给两个数：
`totalBudgetDelta`（净，带方向）与 `totalBudgetDeltaAbs`（绝对敞口，**护栏按它判**）。
代价：界面上多一个数要解释；收益：护栏拦得住真正会烧钱的那一批。

**3. 「按筛选结果全选」在服务端，前端拿不到全量 id。**
`GET /selection` 在 SQL 里按筛选条件解析出目标集合，带**游标**（keyset，不是 offset）与**硬上限**
（`maxSelectablePerFilter = 1000`；超过 ⇒ `truncated=true` ⇒ 批量任务**整批拒绝**并提示缩小范围）。
前端**只**递筛选条件、拿回一页 id 与一个命中计数。理由：让前端把 id 全拉回来再循环等于全表扫 ——
「全选」在活动上千条时会把一次点击变成一次全表读。
代价：目标多于上限时不能一次全选（要缩小范围或分批）；收益：那条路径上的量天然有界。

**4. 任务与任务项：**「一条都不执行」与「执行过但一条都没生效」是相反的两件事。
任务表记状态与计数，**任务项表**记每一条的 before/after 与结果 —— 只有计数的话，
「为什么这一条没生效」永远查不出来。任务项上的 `campaign_id` / `group_id` **不建 FK**
（照抄 V277 `ad_campaign_group_bid_log` 的纪律）：审计要能记下「我想改的那个组压根不存在」。

**5. 任务的可见性靠任务项判定，而不是在任务行上存一份快照。**
`ad_campaign_bulk_task` 的 `data_scope` 策略反查**任务项**（任务项的 `data_scope` 已经按
活动 → 店铺收紧），于是可见性始终按**当下**的店铺归属算。为此任务项的外键做成
**`DEFERRABLE INITIALLY DEFERRED`**，服务层先写任务项、再写任务行 —— 顺序反过来，
任务行写入时一条任务项都还没有，可见性判定会把它拒掉。
代价：延迟外键比普通外怪更需要记住写入顺序；收益：不在任务行上存「当时的店铺数组」，
店铺换了负责人，任务不会按旧范围继续可见。

**6. 竞价落在广告组上；「待回写平台」的信号记在活动上。**
`ad_campaign_group`（V277）**没有** `pending_platform_writeback` 列（本模块不 ALTER 上游的表），
所以「本地已改、还没回写平台」这个信号记在**活动**上（`ad_campaign.pending_platform_writeback`）——
活动列表因此能看见「这条活动的竞价被本地改过」。组级审计写 V277 已有的
`ad_campaign_group_bid_log`（APPLIED / REJECTED / SKIPPED 三种都留）。

**7. 结束时间的审计不写上游的活动操作日志。**
`ad_campaign_operation_log.operation` 的 CHECK 枚举只有 PAUSE / ENABLE / SET_BUDGET / ADJUST_BUDGET，
加一项要 **ALTER 上游的表**（那是 14.1.1.1 的表）。本模块**不越界改** ⇒ 结束时间的审计只落在
自己的 `ad_campaign_bulk_task_item` 上。宁可少写一张表，也不用「借一个不相关的 operation 值」把事实写歪。
同理，「结束时间不能设到过去」这条规则**没有做成 `ad_campaign` 上的触发器** ——
平台同步会回写**已经结束**的活动（它的 `end_at` 本来就在过去），一条触发器会把正常同步打挂。
规则落在服务层的逐条校验上。

**8. 本模块是两张上游审计表的第二个写入方（有意）。**
活动级改动写 `ad_campaign_operation_log`（14.1.1.1 是第一个写入方），组级改竞价写
`ad_campaign_group_bid_log`（14.1.1.2 是第一个写入方）。理由：这两张表记的是
「谁在什么时候把这个活动 / 这个广告组改了」这个事实，改动来自批量任务不该让事实消失 ——
少了这一条，活动详情里的操作历史就会漏掉一整批改动。

**9. 异步执行沿用 catalog `ExportJobExecutor` 的形状，不是自造一套。**
`AdBulkOperationExecutor` 用固定线程池 + DB 裁决领取（`worker_claimed_at`）+
**每批单独开租户事务**（`app.current_tenant` / `app.grants` 是事务级 GUC，提交请求那个事务早结束了）。
控制器是**三段式**：`创建任务（事务 1）→ 领取（事务 2）→ 丢进线程池` ——
领取必须在创建事务**提交之后**，否则工作线程在另一个连接上看不到还没提交的任务行，
会把「任务不存在」当成「没有要执行的东西」而静默退出。
🔴 这条**与 catalog 的 `ExportJobService.create()` 不同**（那边在同一个事务里就 submit），
本车道按正确顺序实现。

**10. 没有消耗预估模型 ⇒ 预览恒 `spendImpactEstimated = false`。**
蓝图的「竞价调整」隐含「改竞价 → 曝光/消耗变化」，但本系统**没有**这个预估模型。
预览只报 **Δ 竞价**与**活动日预算**（真正的钱袋子），**不编**一个假的消耗预估 ——
编出来的预估会被当成事实，运营会照着它做决策。
`capabilities.spend_estimate` 显式 `available=false` + ownerModule + 影响。

---

## 文件清单

### 后端（channel 服务）

- `backend/services/channel/src/main/resources/db/migration/V282__ad_campaign_bulk_operations.sql`
  —— **纯新建、零 ALTER 任何既有表**。两张表（`ad_campaign_bulk_task` / `ad_campaign_bulk_task_item`）
  tenant_id NOT NULL + ENABLE/FORCE RLS isolation + `data_scope`（RESTRICTIVE）+ GRANT（`starcon_app` 零权限）。
- `backend/services/channel/src/main/java/com/starcon/channel/AdBulkOperationModels.java` —— 传输模型。
- `backend/services/channel/src/main/java/com/starcon/channel/AdBulkOperationRules.java` ——
  **纯逻辑**（不碰数据库）：参数校验、逐条预演、护栏裁决。预览与执行**共用**这一份。
- `backend/services/channel/src/main/java/com/starcon/channel/AdBulkOperationService.java` ——
  解析（服务端筛选 → id 集合）、预览、受理任务、任务查询。
- `backend/services/channel/src/main/java/com/starcon/channel/AdBulkOperationExecutor.java` ——
  异步执行器（线程池 + 领取 + 逐条应用 + 审计 + 收尾）。
- `backend/services/channel/src/main/java/com/starcon/channel/AdBulkOperationController.java` ——
  7 个端点（capabilities / options / selection / preview / tasks / tasks/{id}）。
- `backend/services/channel/src/test/java/com/starcon/channel/AdBulkOperationIntegrationTest.java` —— 22 例。

### 前端

- `apps/web/src/components/ads/ad-bulk-operation-model.ts` + `.test.ts`（22 例）
- `apps/web/src/components/ads/ad-bulk-operations.tsx` —— 真实工作区
- `apps/web/src/app/ads/bulk-operations/page.tsx`
- `apps/web/src/app/api/channel/ad-bulk-operations-allowlist.test.ts` —— 5 例
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `ad-bulk-operations/` 元素**（既有元素一字未动、不重排）

### 契约

- `packages/api-contracts/src/channel.openapi.json` + `packages/api-contracts/src/contracts/channel.ts`
  —— 经 `catalog-contracts.py` 重新生成：channel **纯增量**（**+6 path / +15 schema / 0 删除 / 0 path 修改**）。

---

## 关键取舍记录（api-proxy 白名单）

- **首 token 零命中核对**：`ad-bulk-operations` 在既有 100+ 条 `services[]` 正则里**一次都没出现**
  （既有的 ad-* 首 token 是 `ad-campaigns` / `ad-campaign-details` / `ad-budgets` / `ad-keywords` /
  `ad-reports` / `ad-rules` / `ad-type-diffs` / `ad-diagnostics`），所以首 token 既是独立字面量，
  也不会被既有分支的前缀吃掉。
- **插入锚点**：`listing-templates`（4.2.2.1 刊登模板）那条**之前**。同批另外三条车道
  （14.1.2.1 广告组管理 / 14.3.1.2 广告组与词报表 / 14.4.1.1 促销列表）都落在 ad-* 与促销相关区域，
  与本处相隔 400 行以上，git 能干净求并。

---

## 验证

| 闸 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | 绿 |
| L1 后端 | `./mvnw -pl services/channel -am test -Dtest='AdBulkOperation*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **22 例绿**（JDK 25 与 JDK 21 各跑一次，均绿） |
| L1 前端 | `pnpm vitest run apps/web/src/components/ads/ad-bulk-operation-model.test.ts apps/web/src/app/api/channel/ad-bulk-operations-allowlist.test.ts` | **22 例绿** |
| api-proxy 全量回归 | `pnpm vitest run apps/web/src/app/api/channel` | **249 例绿（52 文件）**（本车道改了 `api-proxy.ts`，必须全量） |
| 类型 | `pnpm typecheck` | 三包绿 |
| Lint | `pnpm lint` | 绿 |
| 邻座回归 | `-Dtest='AdCampaignIntegrationTest,AdCampaignDetailIntegrationTest'` | 33 例绿（本模块写上游审计表，确认没打破上游） |

---

## 已知缺口（如实记录）

1. **平台广告 API 与回写队列未交付** ⇒ 批量调整**只落本地**并标记 `pending_platform_writeback`，
   界面显式写「本地已改、还没回写平台」。capabilities `platform_writeback` 维度 `available=false`。
2. **没有消耗预估模型** ⇒ 预览只报 Δ 竞价与活动日预算，`spendImpactEstimated` 恒 false。
   capabilities `spend_estimate` 维度 `available=false` + ownerModule + 影响。
3. **广告组的增删改不在本模块**（归 14.1.2.1）：本模块**只读** `ad_campaign_group`、
   **只写** `default_bid`，不碰广告组的其它字段。capabilities `ad_group_management` 维度 `available=false`。
4. **结束时间的审计不在 `ad_campaign_operation_log` 里**（那张表的 operation 枚举没有这一项，
   加一项要 ALTER 上游的表）。结束时间的逐条审计落在本模块的 `ad_campaign_bulk_task_item` 上。
5. **14.1.1.1 的能力声明快照仍把本模块的三项标为 `available=false`**（`bulk_select_by_filter` /
   `bulk_async_task`，ownerModule 写着 14.1.1.3）。那是 14.1.1.1 的文件（`AdCampaignService` /
   `AdCampaignModels` / 它的集成测试），本车道按「只碰自己的文件」的纪律**没有改** ——
   改它要连带改它的测试断言（它断言这三项为 false），那是别的模块的契约面。
   ⇒ **调度者可能要安排一次跨模块收口**：14.1.1.1 的 capabilities 快照与 14.1.1.1 页面上的
   「按筛选全选 / 变更预览 / 异步任务未交付」文案需要更新为已交付。
6. **任务没有取消语义、没有手动触发执行的端点**：任务由服务端执行器领取，
   界面轮询任务详情即可。capabilities 里没有列这两项（未交付段按「没有这个能力」处理）。
