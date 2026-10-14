# Batch 8 · 发货履约链路与代发异常跟踪

> 批次时间：2026-10-14 19:00 → 20:05（含 L2 重跑一次）
> 集成头：`baebb84c` · 分支 `integration/module-dev`
> L2：**绿** — 后端 3578 例 / 前端 2796 例（153 文件）/ typecheck / lint / Next build / sync --check 全通过
> 台账：已交付 144 → **152**（+8），未开发 321 → **313**，完成率 31.0% → **32.7%**

本批交付 **6 个模块**（4 条新派车道 + 2 条上批遗留解堵）、**2 个缺陷修复**，
以及一个**尚未解决的结构性冲突源**的记录。

---

## 模块 8.3.4.1 发货交接

**这个功能做什么**：仓库作业员在包裹打包称重完成后，在这里确认承运商与运单号、登记交接时间与交接人，
一次点击完成出库。交接同时把库存的「预占」转成「实扣」，并把订单推进到已发货。

**技术落地**：

- 迁移：`V123__warehouse_shipment_handover.sql` — 5 张新表，**零 ALTER 既有表**，
  因此对其它车道的 DDL 回归面为 0
- 端点：`/warehouse/shipment-handovers` — capabilities / POST / GET / `{id}` / `{id}/completion`
  / `{id}/waybills` / `{id}/confirm` / `{id}/cancel`
- 页面：`/warehouse/shipment-handovers`（`handover-workspace.tsx` + `handover-model.ts`）
- 测试：`ShipmentHandoverIntegrationTest` 21 例 + `handover-model.test.ts` 21 例
  + `shipment-handover-allowlist.test.ts` 8 例

**关键设计决策**：

- **「预占」在本服务是两本账，交接时必须一起动**，这是本模块最核心的发现：
  1. `stock_bucket.reserved` / `inventory_balance.reserved` = 7.1.1.1 **实物预占**，
     交接时减 `min(当前 reserved, 出库量)`。不减的话 `available` 立刻为负、被 V2 的
     `available >= 0` 守卫拒掉 —— **那正是「预占悬空」在 DB 层的形态**。
  2. `reservation_allocation`（V107）= 7.2.1.2 **承诺账**，V112 文件头明确写了它不碰 balance。
     处理是 `COMMITTED → CONSUMED` + `reservation_entry` + 父预占回写。
  漏掉任何一本都会导致库存账实不符，而两条车道的单跑测试**都不会**发现这一点。
- **实发扣减与预占消费在同一事务**：这是蓝图点名的硬要求。实现上把状态守卫做成 **DB 硬约束** ——
  置 `HANDED_OVER` 时逐行核对「实扣量恰好等于应发量 + 每行落到具体库存行 + 挂着台账流水」，
  应用层**写不出**更小的数。幂等锚点是台账的 `(ref_type, ref_id)`，重复投递撞 UNIQUE 而不是二次扣库存。
- **跨服务边界**：四个上游全在 warehouse 自己库里，所以「预占转实扣」走同库真外键 + 显式 jOOQ SQL，
  **没有触发** AGENTS.md 禁止的跨库读写。
- 分录复用 V1 的 `inventory_transaction` / `inventory_entry`，没有另开分录表 ——
  否则同一批货会有两份「扣了多少」的账。

---

## 模块 9.4.1.1 待发货

**这个功能做什么**：出库主管的待发货看板。按**仓库、承运渠道、承运商、发货时限**四个维度分组展示，
在大促期一眼看出「哪个承运商的货要压不住了」，并直接在该分组里批量申请运单号、批量打单、批量交接。

**技术落地**：

- 迁移：`V124__trade_pending_shipment.sql` — 6 张表
  （`shipment_pending_unit` / `_parcel`、`shipment_batch_task` / `_item`、
  `shipment_waybill`、`shipment_event`），全部 `tenant_id NOT NULL` + ENABLE/FORCE RLS + GRANT，
  `shipment_event` 按「授权在前、收回在后」REVOKE UPDATE/DELETE
- 端点：`/trade/fulfillment/pending-shipment` — 13 个（capabilities、sync、board、units 读写、
  `units/{id}/sla`、`units/{id}/cancel`、批量任务的建/查/执行/取消、运单回执、面单）
- 页面：`/orders/fulfillment/pending-shipment`
- 测试：`PendingShipmentIntegrationTest` 21 例 + `pending-shipment-model.test.ts` 18 例
  + `pending-shipment-allowlist.test.ts` 5 例

**关键设计决策**：

- **待发货清单的数据来源选了「warehouse 授权 API + trade 侧只读投影表」的组合。**
  上游 `packing_order` / `parcel` / `parcel_item` 在 warehouse 自己的库里，AGENTS.md 禁止跨服务 SQL / FK /
  共享视图，所以**一条 SELECT 都不碰对方库**，只经 `PackingQueryPort` 读它已交付的
  `GET /warehouse/packing-orders?status=COMPLETED`。
  **转发的是调用方自己的 bearer**，所以租户隔离是 trade 侧 RLS + warehouse 侧 RLS **两道**，
  不是本服务替对方决定租户。
- **为什么不每次穿透**：① 四个分组维度要在**全量**单元上聚合，逐页穿透聚合会漏掉没翻到的页，
  聚合值本身不可信 —— 而大促期正是靠这一屏决定先发哪个承运商；
  ② 三个批量操作要留下本地任务凭据，这些天然属于 trade。
- **仓库不可达时显式 503，而不是返回空清单** —— 空清单会被当成「今天没有待发货的货」而误收工。
- 上游已不在 COMPLETED 列表的旧投影行**只报数不删除**（那些行可能已带运单号与交接记录）。

---

## 模块 10.2.2.1 包裹详情

**这个功能做什么**：输入一个包裹 ID，能看清里面装的是哪些 SKU、各多少件，
分别对应订单的哪一行，包裹多重多大、运单号是多少。

**技术落地**：

- 迁移：`V125__parcel_detail.sql`
- 端点：`GET /warehouse/parcels/capabilities`、`GET /warehouse/parcels/{id}`
- 页面：`/warehouse/parcels`
- 测试：`ParcelDetailIntegrationTest` 16 例 + `parcel-detail-model.test.ts` 13 例

**关键设计决策**：

- **落在 warehouse，不落 channel。** 蓝图卷 10 写「主责服务 = channel + trade + warehouse」，
  但那说的是**谁提供物流能力**，不是「单包裹视图落在哪个服务」。
  `parcel` / `parcel_item` 由上游 8.3.3.2（warehouse，V121）拥有且已 RLS，
  所以「把详情查询写进 channel 再连 warehouse 的库 SELECT」是**明确违规** ——
  **表所有者的读模型只能长在表所有者自己的服务里**。轨迹与交运信息在本服务不存在，
  逐段标 `available=false` + `ownerModule`，未返回空列表冒充。
- **`parcel_item` → 订单行的对应关系**是蓝图点名的核心（「缺了就只能算到订单级」）。
  开工后发现 `parcel_item` 已有 `outbound_line_id`，但它指向 `outbound_order_line` ——
  那是 **warehouse 自己的计划快照**；12 财务要摊运费的那一行是 **trade 的 `sales_order_line`**，
  跨服务不建 FK，warehouse 手上只有订单号字符串。**缺的正是这一环。**
  故新建 `parcel_item_sales_order_line`，额外存两样行里放不下的东西：
  **归集分母** `allocated_quantity`（触发器钉死等于明细项实装件数）与
  **匹配口径** `match_basis`（强/弱关联分开标）。
- **匹配靠 `(order, sku)` 唯一键，不靠行号** —— 两个服务各自独立编号行号，按行号对齐是纯猜。
  只有 `SALES` 来源才写行，调拨/退货/加工不写、不硬造 uuid。

---

## 模块 9.6.1.3 代发履约跟踪与异常

**这个功能做什么**：代发订单发出后，运营在这里盯供应商有没有真发货、货到哪了、
有没有异常；异常可以一键催供应商、改自营发货、或发起退款。
订单上「我方」和「供应商」两段物流**分开呈现**，不混成一个运单号。

**技术落地**：

- 迁移：`V126__trade_dropship_tracking_exceptions.sql` — 6 表，**零 ALTER 既有表**
- 端点：`/trade/fulfillment/dropship-tracking` — 15 路径 18 方法
- 页面：`/orders/fulfillment/dropship-tracking`
- 测试：`DropshipTrackingIntegrationTest` 29 例 + model 21 例 + allowlist 5 例

**关键设计决策**：

- **两段物流在数据模型上就是两组互不相干的列**，状态枚举刻意用两个不同词表：

  | | 供应商段 `supplier_leg_*` | 我方段 `own_leg_*` |
  |---|---|---|
  | 状态 | `NOT_SHIPMENT / SHIPPED / IN_TRANSIT / DELIVERED / LOST / ABANDONED` | `NOT_HANDED_OVER / HANDED_OVER / IN_TRANSIT / DELIVERED / RETURNED` |
  | 承运商 / 运单号 | `supplier_carrier_code` / `supplier_tracking_no` | `own_carrier_code` / `own_tracking_no` |

  我方段以「**交运**」而非「发货」为起点，且没有 `ABANDONED`。
  DB 层钉死：触发器拒绝两段同时在途；切到 OWN 必须与「供应商段落 ABANDONED」**在同一行完成** ——
  ABANDONED 是凭据不是删除。
- **三类异常是真 SQL 检出，不是手标**：
  1. `DELAYED_SHIPMENT` 超时未发货 — 已超过 `supplier_promised_ship_at + delay_tolerance_hours`
  2. `PARCEL_LOST` 丢件（轨迹停滞） — 已发货/在途**且有运单号**，
     `coalesce(supplier_last_event_at, supplier_shipped_at)` 超过 `stall_threshold_hours` 未更新
  3. `SUPPLIER_RUNNING_AWAY` 供应商跑单 — 已发货/在途/妥投却**没有运单号**，恒 CRITICAL
     （无单号即无法回传平台、无法举证、无法对账）
- 阈值走租户策略视图；每条异常存 `rule_threshold_hours` + `rule_evidence`，
  **事后改策略不改写历史**。同一 `(tracking, kind)` 非 DISMISSED 唯一，反复跑不刷屏。
- **下游（2.5.1.1 平台回传 / 11 售后 / 9.4.1.2 自营切换）尚未交付**，
  所以跨服务那一跳落在 `fulfillment_dropship_task` 这张待办表上：
  `PLATFORM_CALLBACK` / `REFUND_REQUEST` / `SELF_FULFILL_SWITCH` 三类，
  带状态机 + jsonb 载荷 + 幂等键 + 重试计数。
  下游消费前 `callback_status` 停在 PENDING/QUEUED，**不得显示已回传**。

---

## 模块 9.1.1.3 订单变更与取消（上批遗留，本批解堵）

**这个功能做什么**：客户要改地址、要取消订单时，运营在这里发起变更/取消，
并跟踪平台回写的结果。

**技术落地**：

- 迁移：`V119__trade_order_change_cancel.sql`（753 行）
- 后端：`OrderChangeCancelService` 1631 行 + `OrderChangeCancelController` 264 行
- 页面：`/orders/change`

**关键设计决策**：回传失败会导致平台显示未发货并可能判定迟发，
因此**结果未知必须先查证再重发**，不能盲目重试。

> 本条车道开发完成、测试全绿，但被 `api-proxy.ts` 白名单冲突卡了两批，本批才并入。

---

## 模块 9.7.1.1 批发订单与账期（上批遗留，本批解堵）

**这个功能做什么**：B2B/批发订单 —— 客户分级价、起订量、阶梯价、账期结算（不即时收款）、
开票与对账。与零售订单在订单类型、收款方式、成本口径上明确区分。

**技术落地**：

- 迁移：`V120__trade_b2b_order_terms.sql`（683 行）
- 后端：`B2BOrderService` 1421 行 + `B2BOrderController` 274 行
- 页面：`/orders/b2b`

**关键设计决策**：**B2B 未发货不产生应收，但发货确认为应收** ——
与零售「平台先收款后发货」的现金流方向相反，资金计划必须分开走。
本批还顺手修了它自带的导航登记缺陷，见下。

---

## 修复 1：8.3.2.2 波次时段用例的墙上时钟依赖

**现象**：L2 首轮红。后端 1422 例里 1 例挂：
`PickWaveIntegrationTest.plannedPickAtIsDerivedFromTheRuleSlotInWarehouseLocalTime`，
失败信息里写着 `时段 [9,18) … 时区小时=19`。

**根因**：服务端按
`extract(hour from (o.created_at at time zone w.timezone))` 算 `local_hour`，
而测试的 `outbound(...)` 夹具**不设 `created_at`**，走数据库默认 `now()`。
于是 `local_hour` 随运行时刻漂移 —— **该用例只在 Asia/Shanghai 本地 09:00–17:59 之间通过**。

**为什么必须现在修**：批次栅格是 08:00 / 10:30 / 13:00 / 15:30 / 18:00。
按上面的推导，**08:00（hour=8）与 18:00（hour=18）两格必然也会挂** ——
一天 5 格里至少 2 格 L2 会红、push 被卡。

**为什么判定它不是回归**（三条独立证据）：

1. 本批合入的是 V121/V122，`git diff --name-only <上批HEAD> <本批HEAD> | grep -i pickwave` **为空**。
2. 同文件约 596 行的兄弟用例正确地用 `setCreatedAt(...)` 钉死了创建时刻，**不受时钟影响** ——
   说明服务端「按创建时刻匹配时段」的语义是既定的、正确的。
3. **两个互不相识的车道 worker（8.3.4.1 与 10.2.2.1）在各自的 L1 里独立撞到同一个失败**，
   都明确写下「不是我的改动」，并把判断交回调度器。

**改法**：+3 行，照抄同文件 `setCreatedAt(...)` 的既有用法，把 `OB-PLAN` 的 `created_at`
钉到时段内的仓本地 10:00；日期用 `LocalDate.now(WAREHOUSE_ZONE)` 与断言对齐，避免跨午夜。
**生产代码一行未动，断言未删改。**

**验证（反向对照实验）** —— 关键在于「绿一次」不能证明修好了，
因为此刻本就该是红的：

| 步骤 | pinned 值 | 墙上时钟 | 结果 | 输出里的 `local_hour` |
|---|---|---|---|---|
| 1 不改 | 无（走 `now()`） | 19:21 | 红 | 19 |
| 2 钉时段外 | 仓本地 20:00 | **19:22** | 红 | 20 |
| 3 钉时段内 | 仓本地 10:00 | **19:22** | 绿 | — |

步骤 2、3 墙上时钟**相同**却结果相反 ⇒ 成败只由 pinned 值决定，依赖已解除。
同批全量 warehouse 1422 例绿。

---

## 修复 2：9.7.1.1 的导航登记违反仓库约定

**现象**：并入 9.7.1.1 后 `packages/ui` 的 `nav-routes.test.ts` 与 `task-pages.test.ts` 失败，
报 `运营/批发订单 没有子页面: expected 0 to be greater than 0`。
已确认**该车道自己分支上单跑同样失败**，是车道自带缺陷。

**根因**：9.7.1.1 直接往 `nav-config.ts` 加了一个 `href: '/orders/b2b'` 的导航条目，
而它的真实页面（`apps/web/src/app/orders/b2b/page.tsx`）**存在**，
但它**没有**在 `TASK_PAGES` 登记。而 `nav-config.ts` 的二级导航是**派生**的：

```ts
children: IMPLEMENTED_TASK_PAGES.filter(
  (page) => page.domain === item.href.slice(1) && !isAccountDomain(page.domain),
)
```

对 `/orders/b2b` 来说 `href.slice(1)` 是 `orders/b2b`，
而各任务页的 `domain` 是**域级**的（`orders` / `warehouse` / `finance`），
没有任何一条等于 `orders/b2b` ⇒ `children` 为空 ⇒ `NAV_DESTINATIONS` 里没有这个入口。

**改法（按 `task-pages.ts` 注释写明的约定，方向反过来）**：
撤掉对 `nav-config.ts` 的直接修改，改为在 `TASK_PAGES` 登记
`{ href: '/orders/b2b', domain: 'orders', implemented: true, ... }`，
让 `/orders/b2b` 作为 `/orders` 的二级项**自动派生**出来。
**没有把 `domain` 写成 `orders/b2b`** —— 那是「让测试变绿」的错误方向，会破坏域级约定。

**验证**：两个测试绿 + `packages/ui` 全量 6 文件 45 例绿 + typecheck/lint 绿，
并且**脚本直接导入 `NAV_DESTINATIONS` 确认 `/orders/b2b` 真的在里面**
（只有测试绿不算数）。

---

## ⚠️ 未解决的结构性问题：`api-proxy.ts` 与 `--lanes 4` 冲突

**这是本批最值得记的一件事。**

`apps/web/src/lib/api-proxy.ts` 里**每个服务是一个物理行里的单行正则**，
按顶层 alternation 组织。多条车道各自把自己的新路径追加进自己服务的那个分组，
而那个分组**在同一个物理行里**。

⇒ **同一批里只要两条车道落到同一个服务，git 必然判冲突。**
这不是时序偶发，**retry 不会自愈** —— 下轮集成分支只会有更多分支。

本批实测命中两次：

| 服务 | 冲突的两条车道 | 结果 |
|---|---|---|
| warehouse | 8.3.4.1 + 10.2.2.1 | 手工求并解开 |
| trade | 9.4.1.1 + 9.6.1.3 | 手工求并解开 |

加上早先遗留的 9.1.1.3 + 9.7.1.1（同样卡在这个文件），一度有 **4 条车道**堵死。
按 `--lanes 4` 的派发密度，这几乎每批都会复发。

**本轮只是手工解开，不是机制解决。** 求并规则：按「服务前缀 + 路由组」求并，
外层 `^`/`$` 保持包住完整分组，既有分支逐字不动。
验证用**各写者自己生成的 OpenAPI 契约里的 path 当用例集**：
4 条车道 92/92 全部放行，新增未放行 0 条，既有路由回归 0 条。

**尚未处理的既有缺口**（前后一致，非本批引入，建议单独立项）：
catalog 40 条、identity 16 条、warehouse 4 条、channel 3 条、internal 1 条，共 64 条。

**可选方向（属设计决策，需拍板）**：

1. **给 api-proxy.ts 配 merge driver** —— 像现有的 `merge.contract-union` 那样按前缀求并。改动最小，不改运行时行为。
2. **拆文件** —— 每个服务多行排布，冲突面积从「整行」降到「几行」。更根治，但要改前端路由读法。
3. **降低同服务并发** —— 但 `plan` 挑模块时无法预知服务落点，等于把确定冲突换成随机阻塞。

---

## 其它记账偏差

- `lanes.json` 里 9.1.1.3 / 9.7.1.1 仍是 `conflict`、`headSha` 为空 ——
  它们从未调用过 `land`，`merge-ready` 没有 head 可匹配，补记失败。
  **代码与台账都是正确的**（已验证两者的 head 都是集成头的祖先，台账行均为「已交付」），
  且 `plan` 只挑「未开发」模块，所以**不会被重排、不会有双 writer**。
  副作用仅是这两个 worktree 不会被 `gc` 回收。
- 每轮 `merge-ready` 之后都需要提交一次 `sync.mjs` 生成的台账更新，
  否则 `merge-ready` 会因工作区不干净拒绝切分支。

---

## L2 验证结果

| 项 | 结果 |
|---|---|
| `backend && ./mvnw verify` | **BUILD SUCCESS**，3578 例，0 失败 0 错误 |
| `pnpm typecheck` | 通过（3 packages） |
| `pnpm lint` | 通过 |
| `pnpm test` | **153 文件 / 2796 例全通过** |
| `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` | 通过 |
| `sync.mjs --check` | ✓ 台账与蓝图一致 |

后端分模块耗时：warehouse 8:09 / catalog 1:39 / trade 35s / identity 45s / channel 9s / crm 6s。

---

## 遗留与后续

1. **`api-proxy.ts` 的 merge driver** —— 待决策（见上）。
2. **64 条既有白名单缺口** —— catalog 40 / identity 16 / warehouse 4 / channel 3 / internal 1，建议单独立项。
3. **10.2.2.1 的写入方尚未交付** —— `parcel_item_sales_order_line` 目前只有读模型与 DB 守卫，
   归集到行需要上游把关联写进来。trade 侧的
   `GET /trade/sales-orders/detail/fulfillment-reference` 已交付且带 `lines`，是对齐作业的现成授权 API。
4. **9.6.1.3 的 10.3 轨迹未交付** —— `supplier_last_event_at` 目前靠接口/录入，接入后换真实观测即可，判据不变。
5. **异常检出目前手动触发** —— 定时化归 `workers/` 或调度车道。
6. **`Clock` 注入**（含 `slotStartToday` 与 SQL 口径）是修复 1 的更彻底解法，属生产代码改造，需单独立项。
7. **契约 path 回归目前是外部脚本，不是常驻测试** —— 建议入仓，否则下次合并仍只能靠人记得跑。
