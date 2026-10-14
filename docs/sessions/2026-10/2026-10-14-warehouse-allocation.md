# 9.3.2.1 分仓规则（D4，L3 锚点 9.3.2，主责服务 trade）

日期：2026-10-14 · 车道 `9.3.2.1` · 分支 `feat/9-3-2-1` · 基线 `integration/module-dev`（fb59785）

## 决策摘要

1. **命名空间与 9.3.2.2 完全并列，零重叠。** `V116` 的八张表全部落在
   `fulfillment_warehouse_allocation_*` 下，**对 V92 的七张表一条 ALTER 都没发**，
   也不 JOIN、不读其中任何一张。集成测试里有一条专门反向钉住这点。
2. **不复用 9.3.2.2 的 `fulfillment_path_cost_profile`。** 它的口径是
   `(平台, 履约路径, 目的国)` 的费率；本模块要的是 `(仓, SKU, 目的国)` 的**每单运费估算**，
   维度不同构。复用等于跨模块读别人的事实表 —— 将来那条车道改一列就静默改掉本模块的判定。
   故建自己的 `..._shipping_estimate`。
3. **货权做成两层，不是一个附注字段。** 货权是**仓库的属性**
   （`..._warehouse.legal_entity_code`，NOT NULL CHECK 非空），所以：
   - **硬闸**：`require_legal_entity_match` → 法人不符的仓直接不可行
     （`LEGAL_ENTITY_MISMATCH`），由 BEFORE 触发器钉在**候选写入路径**上；
   - **权重**：`weight_legal_entity` 参与总分，同法人内部继续排序。
4. **端点挂在 `/trade/fulfillment/allocation`**，与 `/trade/fulfillment/routing` 并列而非嵌套；
   api-proxy 在两层结构里**新起一组**，不并进已存在的 routing 组。

## 关键 trade-off

| 取舍                             | 选择                                          | 理由                                                                                                                                                 |
| -------------------------------- | --------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| 货权是「闸」还是「分」           | **两者都是**                                  | 「收入记在哪个法人」是记账正确性，不是可权衡项，所以先当闸；但闸开着时同法人多个仓「都合法」不等于「一样好」，所以还要当权重                         |
| 放宽货权闸的入口                 | **只允许显式配**                              | `CROSS_LEGAL_ENTITY` 兜底策略，或把 `require_legal_entity_match` 关掉。两者都会让 `cross_legal_entity` 被如实标记并进汇总，无法悄悄发生              |
| 无可行仓时                       | **挂起（SUSPEND），不是回落默认仓**           | 悄悄回落会让「这单没货」显示成「已决策走 A 仓」。DB 用延迟约束触发器双向钉死（ALLOCATED 必有 selected 候选；SUSPENDED 必有 hold 且零 selected 候选） |
| 部分分配                         | **只在 `ALLOW_PARTIAL` 下允许**，差额必须挂起 | 否则「已分 30 / 需 50」会显示成已全部分配                                                                                                            |
| 无任何仓库档案                   | **400 报错，不是挂起**                        | 没有仓可枚举时「逐条列出每个仓被排除的原因」无从谈起，那是配置错误不是业务结论                                                                       |
| 库存不足（兜底非 ALLOW_PARTIAL） | **不可行**，不做隐式放宽                      | 兜底策略是租户显式口径，服务层不替它放宽                                                                                                             |
| 候选数据                         | **落库**，不查询时联算                        | 库存会被补上、运费估算会被改、权重会被调，联算会让「当时为什么分了 A 仓」永远答不出来                                                                |
| 判定顺序                         | **资格 → 库存 → 成本 → 打分**                 | 与 9.3.2.2 的「资格 → 库存 → 成本」自洽；货权进第 1 层做闸、进第 4 层做权重                                                                          |

## 踩过的坑（都已在代码或注释里钉住）

- **缺 `tenant_id` 绑定**：四处 insert 手写占位符时漏了 `currentTenant()`，
  报出来的是「未设定参数值 27 的内容」—— 与真实原因完全无关的一句话。
  已用脚本按「`?` 个数 vs 实参个数」审计全部 `sql.execute`。
- **列/cast 数组差一位**：`DECISION_CASTS_IN` 手写数组与列数不等长。
  改成**由列名推导 cast**（`Set<String> DECISION_TS_COLUMNS`），从根上消除这类错位。
- **`RAISE` 占位符与实参个数不符**：迁移里两处，`too few parameters specified for RAISE`。
  已用脚本对全文件 `RAISE` 做「`%` 个数 vs 实参个数」审计。
- **`countWhere` 拼接**：无筛选条件时会拼出 `from t and cross_legal_entity` —— 一条语法错误 SQL，
  报错还指向无关的地方。已改成「有 where / 无 where」两种分支。
- **库存得分恒为 0**：归一化时把需求量当成了分母的占位。已修为 `min(1, 可用/需求)`。
- **append-only 的两层没分开验**：`REVOKE` 让运行角色先被 42501 拒，触发器根本跑不到。
  已补一条**以表所有者身份直连再撞一次**的用例，证明触发器不是死代码。
- **OpenAPI schema 重名**：本仓 warehouse 服务已有 `WarehouseRow` / `AllocationRequest` /
  `AllocationResult`，且 9.3.2.2 的嵌套 record 已占 `Dimension` 这个键。
  本模块所有 record/enum 一律以 `WarehouseAllocation` 开头（含嵌套的
  `WarehouseAllocationDimension`），重生成后核对：**30 个新增 schema 全部带前缀，零删除、零改写既有条目**。

## 文件清单

新增：

- `backend/services/trade/src/main/resources/db/migration/V116__trade_warehouse_allocation.sql`
- `backend/services/trade/src/main/java/com/starcon/trade/WarehouseAllocationModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/WarehouseAllocationService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/WarehouseAllocationController.java`
- `backend/services/trade/src/test/java/com/starcon/trade/WarehouseAllocationIntegrationTest.java`
- `apps/web/src/components/orders/warehouse-allocation-model.ts`
- `apps/web/src/components/orders/warehouse-allocation-model.test.ts`
- `apps/web/src/components/orders/warehouse-allocation-workspace.tsx`
- `apps/web/src/app/orders/fulfillment/allocation/page.tsx`
- `apps/web/src/app/api/trade/warehouse-allocation-allowlist.test.ts`

修改：

- `apps/web/src/lib/api-proxy.ts`（新起 `fulfillment/allocation` 一组）
- `packages/api-contracts/src/contracts/trade.ts`、`packages/api-contracts/src/trade.openapi.json`（codegen 产物，纯新增）
- `docs/product/capability-blueprint/module-progress.md`（**仅 9.3.2.1 那一行的两格**）

## 验证

- L0：`./mvnw -q compile` 绿；`pnpm typecheck` 绿；`pnpm lint` 无新增告警。
- L1：`./mvnw -pl services/trade -am test` → **195 例全绿**
  （本模块 40 例 + 9.3.2.2 的 32 例仍在，证明对兄弟车道零回归）。
- 前端：25 例（本模块 19 + 白名单 6）；另跑兄弟车道白名单 10 例确认未被挤掉。

## 后续待办

- **9.3.3.1 履约批次 / 8 出库单未交付**：分配可确认并可标记「已下发」，
  但下游尚未消费；`capabilities` 里 `reservesUpstreamStock=false` 已显式报出
  「本模块不预占 warehouse 库存」。下游落地时预占要走 warehouse 的**授权 API**，
  不要反过来在本模块加跨服务写。
- **7.1.1.3 可用量同步器未建**：将来同步器按同一形状往
  `..._stock_estimate` 写并把 `stock_source` 改成 `MODULE_7_1_1_3` 即可，
  **表结构与判定逻辑都不需要改**。
- **10 运费试算未开发**：`..._shipping_estimate` 当前全靠租户维护；
  那条线落地后改 `cost_source` 枚举即可接上。
- **9.1.1.2 订单行未交付**：只存 `orderRef` 逻辑引用 + 收货/货权快照列，
  不校验订单存在性；订单落地后由那条车道补引用或外键。
- 若将来认为「跨法人」应当在财务侧再加一道确认（而非仅靠规则开关），
  那属于 12 财务的职责，本模块只负责如实标记与暴露。
