# 2026-10-15 · 9.7.1.2 经销与分销管理

## 一句话

9.7.1.2「经销与分销管理」在 trade 服务交付：授权台账、销量配额与回款考核、返点计提、跨区域窜货预警
四个输出全部真做完，迁移 `V133`，端点 `/trade/dealers`，页面 `/orders/dealers`，
L1 491 例全绿（本模块 29 例）。

## 开工前的两个事实修正（briefing 与仓库实际不符）

1. **3.6.1.2「角色档案」在 catalog，不在 identity。**
   落点是 `backend/services/catalog/.../V30__party_role_profiles.sql`（三张 1:1 档案表
   `party_supplier_profile` / `party_customer_profile` / `party_carrier_profile`）。
   briefing 说「identity 的 V30」——V30 这个文件名是对的，服务是错的；
   只在 identity 的迁移目录里找会误判成「上游未交付」。
2. **本车道基线落后集成头 68 个提交。**
   派单时说的基线是集成头，但 worktree 里 `feat/9-7-1-2` 停在 `cbd78408`，
   而 `integration/module-dev` 已到 `45107b67`。9.7.1.1（`V120`）与 3.6.1.2 都只存在于集成头。
   已 `git merge --ff-only integration/module-dev` 把本车道快进到集成头
   （停在车道自己的分支上，不 checkout 别的分支、不碰主工作区）。

## 关键 trade-off

### 1. 经销商建自己的表族，不进 9.7.1.1 的 `b2b_*`，也不 ALTER 它的表

蓝图把本模块与 9.7.1.1 放在同一个 L3 下，且 9.7.1.1 已经落了十张 `b2b_*` 表，
「顺手把 dealer_id / 授权区域 / 返点率加进去」看起来最省事。选的是建 `dealer_*` 表族，
**零 ALTER 任何既有表**：

- 授权区域与产品线是**多值集合**、授权期限是**区间**、配额是**期间 × 目标**、
  返点是**负债性承诺（不是应收）**、窜货要拿授权范围去比实际销售区域——
  这五件事在 `b2b_order` 上放不下，硬塞进去就是「一半的行永远是 NULL」。
- 跨车道 DDL 回归面 = 0 是本仓库已固化的纪律，而本批有 3 条 trade 车道同时落迁移。

代价：与 9.7.1.1 之间没有 FK 也没有 JOIN，需要其口径时只能只读引用。收益是两条车道互不影响。

### 2. 「未接通」一律显式暴露，绝不画成默认值

本模块有四个上游/下游没到位，每个都做成**数据而不是文案**：

| 缺口 | 数据上的表达 |
| --- | --- |
| 16 合规未交付 | `compliance_state` 服务层**只写得出 UNVERIFIED**（词表声明三态，其余两个留给 16 交付后写入） |
| 12 应付/返点未交付 | `finance_confirmed` **恒为 false**，界面每一行都显示「本模块口径 · 未与财务确认」 |
| 5.5.2 未交付 | 窜货判定只做授权范围越界，**没有价格倒挂维度**，capabilities 里写明「未与 5.5.2 合并」 |
| 13 分析未交付 | 汇总只在本模块内出，说明由谁读哪张表 |

把「没有校验过」画成「校验通过」是合规类字段最危险的默认值；
把「计提口径未与财务确认」藏在 tooltip 里，会让看板上的负债金额看起来已获认可。

### 3. 完成率是 SQL 的 NUMERIC 除法，服务层再复算一遍

完成率**不落库**（落一个 REAL 列会让列表和详情给出两个答案）：
`round(actual/target, 6)`，服务层用 BigDecimal 除法独立复算，两者不一致直接 500。
目标是 0（不限配额）时 SQL 用 `nullif` 得 null、服务层原样返回 null ——
0% 会被读成「一个都没完成」，100% 会被读成「超额完成」，两者都是编造的答案。

### 4. 授权期限用落库的两个 date 判定

不从 `created_at + N 天` 反推：补录历史授权时后者会把期限整体平移，
而「这个授权到哪天为止」是必须逐字对得上的合同事实。
销售登记用 `soldOn`（卖方所在时区的日历日，业务事实）而不是从 timestamptz 截 date ——
后者在夜里的单子上会偏移一天，偏移方向取决于 JVM 与数据库各自的时区设置。

## 踩过的三个坑（都属于同一类：不显式带类型/偏移，PG 就按 varchar 猜）

1. `synced_at` 等 timestamptz 列用裸 `?` → `column "synced_at" is of type timestamp with time zone
   but expression is of type character varying`。改成 `?::timestamptz`。
2. 改完 cast 后 `dealer_sale` / `dealer_quota` / `dealer_rebate` 三处的**占位符数量**没跟着改，
   报 `INSERT has more target columns than expressions` 与 `cannot cast type uuid to date`。
   **每次改 values 列表都要重新数列数**。
3. 测试里 `new ObjectMapper()` 把 JSON 数值读成 `Double`，`"333.3330"` 变 333.333，
   精确值断言全部失真。加 `USE_BIG_DECIMAL_FOR_FLOATS` 并统一走 `bd()` 辅助。

另外：`ApiErrorHandler` 把**所有** `DataAccessException` 统一映射成 409「履约路径数据与既有记录冲突」，
不带真正的原因。测试的 `body()` 辅助已把被处理的异常链拼进断言消息 ——
否则「DB 到底拒绝了哪一条约束」在报告里完全看不到，这个辅助对通过的用例无副作用，建议保留。

## 文件清单

**后端（trade）**

- `backend/services/trade/src/main/resources/db/migration/V133__trade_dealer_distribution.sql`
- `backend/services/trade/src/main/java/com/starcon/trade/DealerChannelModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/DealerChannelService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/DealerChannelController.java`
- `backend/services/trade/src/main/java/com/starcon/trade/DealerPartyQueryPort.java`（读 3.6.1.2 的跨服务只读 Port）
- `backend/services/trade/src/test/java/com/starcon/trade/DealerChannelIntegrationTest.java`（29 例）

**契约（codegen 生成物，禁止手改）**

- `packages/api-contracts/src/trade.openapi.json`、`packages/api-contracts/src/contracts/trade.ts`（+80 Dealer* DTO）

**前端**

- `apps/web/src/components/orders/dealer-channel-model.ts`
- `apps/web/src/components/orders/dealer-channel-workspace.tsx`
- `apps/web/src/app/orders/dealers/page.tsx`
- `apps/web/src/components/orders/dealer-channel-model.test.ts`（15 例）
- `apps/web/src/app/api/trade/dealer-channel-allowlist.test.ts`（6 例）

**共享文件（求并）**

- `apps/web/src/lib/api-proxy.ts` —— trade 段**外科式最小追加** `dealers` 一支
- `packages/ui/src/lib/task-pages.ts` —— 登记 `/orders/dealers`（workflow 必须**恰好 3 条**，
  `task-pages.test.ts` 会校验；二级导航由 `children` 派生，**不要**改 `nav-config.ts`）

**台账与会话**

- `docs/product/capability-blueprint/module-progress.md`（只改 9.7.1.2 那一行的状态与证据两格）

## 验证

- L0：`./mvnw -q -pl services/trade -am compile` → 退出 0
- L0：`pnpm typecheck` → 退出 0
- L1：`./mvnw -pl services/trade -am test` → **Tests run: 491, Failures: 0, Errors: 0**
- 前端：`pnpm vitest run <本模块两个测试文件>` → 21 例全绿
- 导航守卫：`pnpm vitest run packages/ui/src/lib/{nav-routes,task-pages}.test.ts` → 17 例全绿

## 后续待办

- **12 应付/返点交付后**：`dealer_rebate` 的口径确认与 `accrual_state → SETTLED` 的回写
  属财务的写入所有权，本模块刻意不提供该端点；届时由 12 读 `dealer_rebate`。
- **5.5.2 交付后**：窜货判定需要与站点价差倒挂合并（当前只有授权范围越界一个维度，
  且一条销售最多一条预警的合并规则需要与 5.5.2 侧确认口径）。
- **16 合规交付后**：`compliance_state` 的另两个取值才可达；服务层要加同步写入路径。
- **9.7.1.1 侧**：`order_type=B2B_DISTRIBUTION` 的经销订单要真正进入履约链路，
  需由 9.7.1.1 侧接入，本模块不代写 `b2b_order`。
