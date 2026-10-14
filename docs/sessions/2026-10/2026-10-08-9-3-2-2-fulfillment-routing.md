# 9.3.2.2 FBA 与自发货选择 —— 决策摘要、关键 trade-off、文件清单与后续待办

- 日期：2026-10-08
- 车道：9.3.2.2（阶段 D4，L3 锚点 9.3.2）/ 分支 `feat/9-3-2-2` / worktree `.worktrees/9.3.2.2`
- 迁移版本：V92（预分配）
- 结论：**验证全绿，可并入集成头**（并入由调度器执行）

## 一、落哪个服务：trade，且这不是「默认塞进 warehouse」

指令要求「先读蓝图 9.3.2 的关系字段判断归属，不要默认塞进 warehouse」。实查三处一致指向 **trade**：

1. 蓝图 09 卷头部「**后端归属**：`trade` 服务拥有订单与售后单据；订单数据来自 `channel` 同步，库存动作由 `warehouse` 执行。」
2. L2 功能职责表：「**9.3 履约编排** … 拆合单、分仓与平台仓路径选择 … 主责服务 = trade」。
3. `apps/web/src/lib/api-proxy.ts` 的 trade/ 白名单注释：「卷 09 的 27 个 L4 模块一个都还没开发，因此这里刻意不放行任何子路径。
   **第一条 9.x 模块车道落地时，在本分支里补对应子路径即可**」—— 本车道就是那一条。

代价是 trade 服务此前只有 V1 基线、**零端点**，所以本车道顺带补齐了
`AccessService` / `ApiErrorHandler` / `OpenApiContractTest` 与浏览器出口。这是必要成本，不是范围蔓延：
没有这三样，端点无法通过已认证成员关系解析租户，契约也无法 codegen。

## 二、上游依赖实查结论（不 mock、不建假上游）

台账 `module-progress.md` 实查：

| 上游（蓝图关系段写的两处）      | 台账状态                                                                               | 实际归属                   | 本模块怎么处理                                                                                                                                                         |
| ------------------------------- | -------------------------------------------------------------------------------------- | -------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 7.1.3.1 多仓分布                | **已交付**（warehouse V55）                                                            | warehouse 服务的独立数据库 | 不跨库读（AGENTS.md 模块边界纪律）。落成本服务 `fulfillment_platform_stock_snapshot`，每行强制带 `stock_source`（MODULE_7_1_3_1 / TENANT_MAINTAINED）与 `collected_at` |
| 2.1.1.2 平台能力矩阵            | **已交付**（channel V1 的 `platform.capabilities` 已有 `multi_channel_delivery` 字段） | channel 服务的独立数据库   | 同上，落成 `fulfillment_mcf_authorization`，带 `authorization_source`（MODULE_2_1_1_2 / TENANT_MAINTAINED）                                                            |
| 10.1.2.1 运费计算器（成本口径） | **未开发**                                                                             | —                          | 成本与时效参数落 `fulfillment_path_cost_profile`，由租户维护。**不从代码常量取任何金额**                                                                               |
| 9.1.1.2 订单行（决策对象）      | **未开发**                                                                             | —                          | 只存 `orderRef` / `orderLineRef` 逻辑引用，不校验订单存在性（与 trade V1「不替后续车道猜表结构」同纪律）                                                               |
| 9.3.3.1 履约请求（下游）        | **未开发**                                                                             | —                          | 决策可确认落库但尚未下发；在 capabilities 里**显式报出不可用**                                                                                                         |

16 合规与渠道侧正式接口到位后的接法：**由同步器按同一形状往这三张表写，并把来源枚举改成 MODULE_***，
本模块的表结构与判定逻辑**不需要改动**。这句话也写进了 capabilities 响应与迁移文件头。

## 三、关键 trade-off（四个真正做过取舍的地方）

### 1. 「资质未核验」按「不许切」处理，而不是「默认可以」

蓝图关系段加粗：「平台仓缺货自动转自发货是高价值能力，但受平台政策约束，**必须先验证该店铺的多渠道配送资质**」。

- 取舍：把资质闸门放在**判定链的第一位**（资格 → 库存 → 成本），而不是「先比成本、最后再看资质」。
- 理由：「有货、有便宜、但没资质」在平台上不是「一个可以权衡的选项」，是非法的。
- 落点：既有服务层的软校验，也有 **DB 层 BEFORE 触发器 `guard_routing_switch_mcf` 在写入路径上核对** ——
  只靠服务层先查一次，会被「两个动作之间资质被收回」绕过（测试里真的这么打了一次，DB 挡住了）。
- 「有资质但已过期」与「从未核验」在响应里**分两列**（`mcfStatus` / `mcfUsable`），
  因为前者要续期、后者要核验，运维要做的事不同；合成一个布尔会把两种动作都指错。

### 2. 缺货切换记录钉成 DB 不变量，而不是服务层的 if

蓝图输出句把「履约路径决策」与「缺货时切换记录」并列。取舍：把第二条输出做成**延迟约束触发器**，
提交时核对「选了非平台仓 ⇒ 必须存在一条 switch」，否则 23514。

- 代价：服务层「先插头 → 写候选 → 回填头 → 写切换」这个顺序必须与触发器的语义对齐。
- 收益：**绕过服务层直接写 SQL 同样被挡**，所以「先存决策、切换以后再补」在本模块做不到。
- 踩到的坑（本仓库已踩两次的那个）：延迟触发器里**不能用 `NEW` 的可变列**，必须回表读当前行。
  服务层的写入顺序会让 INSERT 那一次拿着半成品的 NEW，用它每次必报。
  照抄 V85 `customs_rate_snapshot` / V90 `duty_estimate_totals` 的解法。

### 3. 判定只有一处实现，预览与落库同源

`evaluate()` 是唯一的判定实现，`decide()` 落库时也调它。
取舍：宁可牺牲一点代码复用便利，也要保证「页面试算说的」与「系统真做的」是同一个结论 ——
两者不一致时，运营看到的和系统做的是两件事，这是最不能容忍的一种不一致。
为此 `evaluations` 端点**刻意不落库**：试算留痕会污染决策历史。

### 4. 候选路径落库存档，而不是查询时联算

库存会被补上、成本参数会被改。若详情页联算候选，「当时三条路径分别是多少」就再也答不出来，
而那正是「为什么这单没走平台仓」的完整答案。所以 `fulfillment_routing_candidate` 抄下决策当时的快照。

## 四、过程中修掉的真 bug（都是先红后修，不是改断言绕过）

按发现顺序，这些都是实现里真实存在的缺陷：

1. **`blockCode()` 返回 `""` 而非 `null`**，导致「平台仓可行」的判据 `blockCode == null` 永远为假，
   于是**三条路径全被判成不可行**、最该发生的自动切换一路掉进「无可行路径」错误。
2. **判定阶梯缺一支**：`平台仓不可用 + 自发货可行` 没有对应分支。
   少了它，最该发生的自动切换反而报成错误，而 DB 里的 `PLATFORM_STOCK_INSUFFICIENT` 永远没人写。
3. **`REVOKE UPDATE/DELETE` 写在 `GRANT` 之前**，被后面的全量授权覆盖回去 —— append-only 只剩注释。
   测试用 `has_table_privilege` 钉住才发现。
4. **append-only 触发器把状态推进也挡了**。修法用 transaction-local GUC 开窗，
   **不是**「看 `NEW.status` 就放行」：后者会把「把 reason 改得更好看」一起放行。
5. **只读评估的「手工可用量」参数没传给判定主体**，整个 override 形同虚设。
6. **未来采集时间的库存快照**：原实现只把数量置空、`snapshotId` 仍非空，
   于是路径被判成「可行但可用量为 null」—— 最坏的一种状态。改为整条视为「没有库存记录」。
7. **手工覆盖的平台仓可用量只改最终判定、不改候选行**，界面上会出现「平台仓候选可行」与
   「判定了平台仓不可用」两处互相打脸。
8. **成本参数映射读错列名**（`note` vs `notes`）。
9. **多处 INSERT 漏绑 `tenant_id`**，报出来的是「未设定参数值 N 的内容」——
   与真实原因完全无关的话。改成**由列数组生成 values 子句**（照抄 `StockOwnershipService` 的做法），
   并把 `tenant_id` 的绑定来源注释清楚（来自 `currentTenant()` 读 RLS GUC，不是请求头）。
10. **`fetchOne(sql, Long.class, binds)` 在本仓库的 jOOQ 版本落回 `Object` 重载**，
    `Class` 本身被当成绑定值绑定。统一改成仓库既有写法 `fetchOne(sql, binds).get("c", Long.class)`。

还有一处**纪律性的**发现：`TRUNCATE` 不触发行级 DELETE 触发器，
拿它证明 append-only 会得到一个「什么都没发生」的假绿灯 —— 已改用显式 `DELETE`（两条都断言）。

## 五、文件清单

**迁移**

- `backend/services/trade/src/main/resources/db/migration/V92__trade_fulfillment_routing.sql`（新增，七表 + RLS/GRANT + 五条 DB 不变量）

**后端**

- `backend/services/trade/src/main/java/com/starcon/trade/FulfillmentRoutingModels.java`（新增）
- `backend/services/trade/src/main/java/com/starcon/trade/FulfillmentRoutingService.java`（新增）
- `backend/services/trade/src/main/java/com/starcon/trade/FulfillmentRoutingController.java`（新增，13 端点）
- `backend/services/trade/src/main/java/com/starcon/trade/AccessService.java`（新增，trade 首条端点引入）
- `backend/services/trade/src/main/java/com/starcon/trade/ApiErrorHandler.java`（新增）
- `backend/services/trade/src/test/java/com/starcon/trade/FulfillmentRoutingIntegrationTest.java`（新增，32 例）
- `backend/services/trade/src/test/java/com/starcon/trade/OpenApiContractTest.java`（新增，契约导出）
- `backend/services/trade/src/test/java/com/starcon/trade/TradeTenancyIntegrationTest.java`（改：补 IDENTITY_URL / INTERNAL_KEY）
- `tools/codegen/catalog-contracts.py`（改：服务清单新增 trade）

**契约（codegen 产物，禁止手改）**

- `packages/api-contracts/src/catalog.ts`、`packages/api-contracts/src/trade.openapi.json`

**前端**

- `apps/web/src/app/orders/fulfillment/routing/page.tsx`（新增）
- `apps/web/src/components/orders/fulfillment-routing-workspace.tsx`（新增）
- `apps/web/src/components/orders/fulfillment-routing-model.ts` + `.test.ts`（新增，7 例）
- `apps/web/src/app/api/trade/[...path]/route.ts`（新增，trade 首个浏览器出口）
- `apps/web/src/app/api/trade/fulfillment-routing-allowlist.test.ts`（新增，5 例）
- `apps/web/src/lib/api-proxy.ts`（改：trade/ 白名单首次放行）

**台账**

- `docs/product/capability-blueprint/module-progress.md`（仅本车道那一行的状态与证据；汇总额由 sync 生成）

## 六、两处「刻意不做」及理由

1. **刻意不占用 `/orders/fulfillment`**：那个路由在 `packages/ui/src/lib/task-pages.ts` 里
   已登记给 **9.3.3.1 待发货订单**（另一条车道）。两条车道各写一个 `page.tsx` 会互相覆盖，
   且 `nav-routes.test.ts` 的守卫会把「有真实页面却被标成未实现」判为失败。改用
   `/orders/fulfillment/routing`。
2. **刻意不新增 nav 菜单项**：`nav-config` 是全站共享文件，本轮跨了 `/orders` 与 `task-pages` 两处共享登记，
   再动 nav 会增加与并行车道的冲突面。页面路由已可用，入口由 9.1/9.3.3.1 车道接入。

## 七、后续待办（交回调度者 / 下一条车道）

- **9.3.2.1 分仓规则**（未开发，同属 trade）：本模块的 `from_path='PLATFORM_FBA'` CHECK 已把
  「两条自发货路径之间互切」挡在本表之外，明确归属 9.3.2.1；该车道可直接复用本模块的
  成本参数与库存快照表。
- **9.3.3.1 履约请求**（未开发）：`fulfillment_routing_switch.switch_source` 已预留
  `FULFILLMENT_REQUEST` 取值，履约请求下发时按同一形状写入即可。
- **9.1.1.2 订单详情**（未开发）：订单表落地后，由该车道给 `order_ref` 补引用或外键
  （本迁移刻意没猜订单表结构）。
- **16 合规 / 渠道侧正式接口**：到位后写同步器，把来源枚举从 `TENANT_MAINTAINED` 改为 `MODULE_*`。
- **10.1.2.1 运费试算**（未开发）：成本口径若要自动化，届时替换 `fulfillment_path_cost_profile` 的写入来源。

## 八、验证结果

见交付报告；全部命令实跑并记录真实数字（含 verify 期间同时存活的 PG 容器数与回收情况）。
