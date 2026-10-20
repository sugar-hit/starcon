# 2026-10-21: 6.2.1.3 采购计划 —— 按周期 / 大促节奏 / 目标仓把补货建议汇总成可锁定的计划行

> 车道 `feat/6-2-1-3`，worktree `.worktrees/6.2.1.3`，基线 `4ebff806`（merge(6.1.2.3): 供应商资质）。
> 能力蓝图卷 06「采购与供应」L3 锚点 6.2.1，阶段 D6，L4 模块 6.2.1.3。
> **服务归属 = trade**（蓝图 06 卷 L2 表「6.2 采购需求 → trade」，同 L3 的 6.2.1.2 亦在 trade）。

## 摘要

本车道交付 6.2.1.3「采购计划」：把 6.2.1.2 的**补货建议**按**周期（月 / 季）**、按**店铺大促节奏**、
按**目标仓**汇总成采购计划行，支持**调整、锁定、导出**给采购员。

蓝图要求的是「能拿来用的计划」，不是「一张建议列表的别名」，所以本模块把三件事做成了
**结构而不是标签**：

1. **大促与日常分开** —— 列 + DB CHECK + 两套算式 + 汇总视图两段分开。
2. **锁定是真的锁定** —— 走 warehouse 的授权写端口真登记预占；**端口没接线就显式报错**，
   绝不降级成「假装锁住了」。
3. **大促日历是真实输入** —— 本模块自带、租户自己维护，不 mock。

六张新表、零 ALTER 既有表、零跨库 FK / 视图；端点 19 个；真实工作区页面 1 个；
测试 25 例（后端，含三租户断言）+ 17 例（前端）。

## 决策摘要

### 决策一：大促日历由本模块自带（租户可维护的真实输入）

任务书给的三条设计决策之一。落地前先做了**依赖自检**：

```
grep -rn "Campaign\|Promo\|Promotion" backend/services/*/src/main/java/**
```

结果：**仓内没有任何 Campaign / Promo 类**。大促日历在本仓**没有任何已交付来源**。

这意味着两个选项：要么 mock 一份假档期，要么让租户自己维护真档期。**选后者**：

- `purchase_plan_campaign`：档期编码 / 名称 / 店铺 / 开始日 / 结束日 / **销量系数** /
  **备货系数** / **提前天数** / 状态。
- 完整 CRUD + **真分页**（`GET /trade/purchase-plans/campaigns`）+ 真筛选（编码 / 状态 / 店铺）。
- **被计划引用时不允许删除**（409）—— 删掉档期会让已存在的计划行失去算式依据。
- 销量系数与备货系数是**业务输入**（运营按经验填），不是算出来的数。

**为什么不 mock**：mock 的档期会让「按大促节奏采购」这条最核心的业务能力变成演示效果，
且 mock 数据一旦进仓，别的模块（10.4.2.2 备货预报）会把它当真值用。

### 决策二：锁定走 warehouse 的授权写端口，端口未接线就显式报错

预占事实（`inventory_reservation`）的**写入所有者是 warehouse**（7.2.1.1，迁移 V102），
AGENTS.md 明令禁止跨服务 SQL / FK / 共享视图。所以本模块**不建任何预占影子表**，
而是定义授权写端口：

- `PurchasePlanLockPort`（接口）+ `HttpPurchasePlanLockPort`（真实现）
  - **`UnwiredPurchasePlanLockPort`（显式抛错）**。
- 接线判据：`WAREHOUSE_URL` 配置项为空 ⇒ 判定为**未接线**。
- 未接线时调用直接失败并报「未接线」，**不返回成功、不返回空结果**。
- 调用 warehouse `POST /warehouse/inventory/reservations`，
  `source_system='TRADE'` + `source_type='OTHER_HOLD'`（受 warehouse V102 的受控词表约束）。

本库只记**属于采购计划自己的事实**：「已提交请求 / 已确认 / 失败」
（`purchase_plan_lock_log`，append-only）。

> **部署态现状（如实记录）**：生产 compose（`deploy/docker/docker-compose.catalog.yml`）
> 的 trade 服务**未配置 `WAREHOUSE_URL`** ⇒ **部署态的锁库端口判定为未接线**，
> 锁定动作会显式报「未接线」。测试环境已接线并**真实登记了预占**。
> 补齐部署配置属于后续待办。

### 决策三：锁定失败必须留痕 —— 失败不能抛在事务里

`AccessService.run` 的整条调用链跑在**一个事务**里（租户上下文靠
`set_config('app.current_tenant', ?, true)` 事务局部绑定）。最初实现把失败
`throw` 在服务层，结果**「已提交请求」和失败原因被一起回滚** —— 留痕表里什么都没有。

`REQUIRES_NEW` 也不行：新事务拿不到租户 GUC，查不到租户数据。

最终做法：

1. 服务层**先记 FAILED 留痕再返回**（不抛异常）；
2. 控制器在**事务提交之后**把 `PurchasePlanLockResult.failed` / `errorStatus`
   翻成对应 HTTP 状态码。

代价：控制器要额外拿一个「失败结果」对象而不是靠异常传播。收益：失败是**事实**，
必须留得住 —— 否则运营永远不知道自己锁过什么、为什么没锁成。

### 决策四：大促与日常分开落成三层

蓝图加粗句「大促备货与日常补货要分开」，只做 UI 标签是不够的。分开落在三处：

| 层             | 做法                                                                                                                             |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| **结构**       | `purchase_plan.plan_type ∈ ('ROUTINE','CAMPAIGN')` 是**列**，DB 层 CHECK 钉死「CAMPAIGN 必须带 `campaign_id`、ROUTINE 必须不带」 |
| **算式**       | 大促 = 建议量 × 销量系数 × 备货系数；日常 = 建议量本身。算式原文写进 `quantity_basis`，人可复现                                  |
| **期望到货日** | 大促 = 大促开始日 − 档期登记的提前天数；日常 = 6.2.1.2 的预计到货日                                                              |
| **视图**       | `GET /trade/purchase-plans/summary` 返回**日常段 / 大促段两个独立块**                                                            |

**刻意不提供两者相加的总数量**：相加后的总数会让日常补货失焦 —— 采购员看到一个大数，
不知道其中多少是日常、多少是大促，而两者的采购节奏、提前期、备货策略完全不同。

### 决策五：补货建议只读 6.2.1.2，且重新汇总不覆盖已调整的行

- 只有 `advice='REPLENISH'` **且未被忽略**的建议才变成计划行 ——
  「答不出来」和「无需补货」不能变成要下单的量。
- 上游已调整过的建议，取**处置后的量**作基数（不是调整前的原始量）。
- **重新汇总不覆盖已调整的行**：跳过并计数返回。理由很直接 —— 采购员手工调过的数量
  是他刚花时间做的决定，重新汇总把它冲掉，这功能就没人敢用第二次了。
- 汇总**不修改上游建议表**（那张表的写入所有权是 6.2.1.2，本模块只读）。

### 决策六：周期边界由服务端裁决

月度 = 某月 1 号到该月月末；季度 = 季首月 1 号到季末月末。调用方给的对不上 ⇒ **400**，
**不悄悄改写**。悄悄改写会让运营以为自己定的是三月，实际拿到的是季度。

### 决策七：不提供删除计划

计划是**凭据**：行调整留痕与锁定留痕都指向它。删掉计划 ⇒ 留痕变成孤儿。
只允许删除**未被任何计划引用**的大促档期。

## 关键 trade-off

| 决策点        | 备选                                       | 采用                                     | 代价                                                                    |
| ------------- | ------------------------------------------ | ---------------------------------------- | ----------------------------------------------------------------------- |
| 大促日历来源  | mock 一份固定档期 / 跨服务找渠道促销       | 本模块自带、租户维护                     | 多一张表要维护；但这是真实业务输入，不是假数据                          |
| 预占事实落库  | 在 trade 建 `inventory_reservation` 影子表 | 走 warehouse 授权写端口                  | 依赖部署配置 `WAREHOUSE_URL`；未接线就显式失败（可诊断，优于假装成功）  |
| 锁定失败表达  | 抛异常（自然，但会回滚留痕）               | 先记 FAILED 再返回，控制器事务后翻状态码 | 调用链多一个结果对象；换来失败可追溯                                    |
| 汇总视图      | 给一个日常 + 大促的总数量                  | 两段分开，**不给总数**                   | 采购员要自己看两段；换来「日常补货不被大促数字淹没」                    |
| 重新汇总      | 全量覆盖计划行                             | 跳过已调整行并计数                       | 汇总结果不再是「干净的镜像」；换来实现「人调过的不被冲掉」              |
| 周期越界      | 服务端改写到最近合法区间                   | 400 拒绝                                 | 调用方要多一次校验；换来「用户定的就是他要的」                          |
| 目标仓 / 法人 | 在 trade 建仓库 / 法人影子表               | 经只读端口读 warehouse                   | 依赖上游可达性；取不到时**名称留空 + 如实记原因**，不编造、不阻塞建计划 |
| 计划删除      | 提供软删除                                 | 不提供                                   | 误建计划无法删除（可用状态流转作废）；换来留痕永不孤儿                  |
| 汇总查询口径  | 列表与详情各查各的                         | 统一走 `requirePlan` 单一取数形状        | 少一处灵活性；换来「列表说 3 行、点开只有 2 行」这种假象不会发生        |

## 文件清单

**后端（trade 服务）**

- `backend/services/trade/src/main/resources/db/migration/V242__purchase_plan.sql` ——
  **六张新表**（`purchase_plan_campaign` / `purchase_plan` / `purchase_plan_line` /
  `purchase_plan_adjustment` / `purchase_plan_lock_log` / `purchase_plan_lock_policy`）、
  **3 个触发器**（计划行来源冻结 / 调整留痕 append-only / 锁定留痕 append-only）、
  6 表 ENABLE + **FORCE** RLS + POLICY isolation、GRANT trade_app / starcon_app。
  **零 ALTER 既有表、零跨库 FK / 视图**。锁定 / 调整两张留痕表 REVOKE UPDATE/DELETE；
  计划与计划行不提供 DELETE。
- `backend/services/trade/src/main/java/com/starcon/trade/PurchasePlanModels.java` —— 传输模型与枚举。
- `.../PurchasePlanRules.java` —— 周期边界、两条算式、到货日、状态推导（纯规则，可单测）。
- `.../PurchasePlanService.java` —— 能力声明、档期 CRUD、计划 CRUD、汇总、行分页 / 详情、
  调整、锁定、汇总视图、CSV 导出、锁库策略（jOOQ 显式 SQL）。
- `.../PurchasePlanController.java` —— **19 个端点**，命名空间 `/trade/purchase-plans`，
  权限码 `trade.purchase-plan.read` / `trade.purchase-plan.update`。
- `.../PurchasePlanWarehousePort.java` —— 只读端口（目标仓 / 法人），不可达不编造数据。
- `.../PurchasePlanLockPort.java` —— 写端口接口 + HTTP 实现 + **未接线显式失败实现**。
- `.../PurchasePlanLockConfiguration.java` —— `WAREHOUSE_URL` 为空 ⇒ 未接线。

**测试**

- `backend/services/trade/src/test/java/com/starcon/trade/PurchasePlanIntegrationTest.java` —— **20 例**。
- `.../PurchasePlanTenancyIntegrationTest.java` —— **5 例**（三租户断言）。

**前端**

- `apps/web/src/components/purchase/purchase-plan-model.ts` + `.test.ts`（**11 例**）
- `apps/web/src/components/purchase/purchase-plan-workspace.tsx`
- `apps/web/src/app/purchase/demand/plans/page.tsx`
- `apps/web/src/app/api/trade/purchase-plan-allowlist.test.ts`（**6 例**）
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `purchase-plans` 正则元素**，
  锚点 = 既有 `replenishment-suggestions` 元素之后；**既有条目一字未动**。

**契约 / 台账**

- `packages/api-contracts/src/contracts/trade.ts` + `packages/api-contracts/src/trade.openapi.json`
  —— codegen 产物（**+15 paths / +27 schemas**）。
- `docs/product/capability-blueprint/module-progress.md` —— **只改 6.2.1.3 一行的「状态」「证据」两格**。

## 验证

- **L0**：`cd backend && ./mvnw -q compile` 全绿；`pnpm typecheck` 全绿；`pnpm lint` 本车道文件无告警。
- **L1**：`./mvnw -pl services/trade -am test -Dtest='PurchasePlan*' -Dsurefire.failIfNoSpecifiedTests=false
-DfailIfNoSpecifiedTests=false` —— **25 例 0 失败**（Flyway 日志：`Successfully applied 57 migrations ... now at version v242`）。
- **前端**：`pnpm vitest run apps/web/src/app/api/trade apps/web/src/components/purchase/purchase-plan-model.test.ts`
  —— **51 文件 / 277 例全绿**（其中 api/trade 全量 allowlist 回归 **50 文件 / 266 例**）。

## 后续待办

- **部署配置补齐**：给 trade 服务补 `WAREHOUSE_URL` 环境变量，否则**部署态锁库端口为未接线**，
  锁定动作会显式报「未接线」。这是**部署侧**待办，不在本车道代码范围内。
- **6.3.1.x 采购单**：未交付 ⇒ 本模块导出 CSV 给采购员，**不建采购单**；
  采购单交付后应考虑直接落单并回写来源单据号。
- **10.4.2.2 备货预报**（并行车道）：与本模块共享「按周期」的输入形态；
  两边都落地后应确认周期口径（UTC 日界）不漂移。
- **锁库策略的版本化收口**：当前 `purchase_plan_lock_policy` 已按 `plan_type` 版本化并用
  部分唯一索引钉死「同时只有一条生效」；随着策略种类增加，可考虑引入策略模板。
- **大促档期的批量维护**：当前为单条 CRUD，档期数量多时（一年 20+ 场）运营需要
  批量导入 / 按年复制，可作为后续增量。

## 关联

- 蓝图：卷 06「采购与供应」L3 锚点 6.2.1，模块 6.2.1.3。
- 上游：6.2.1.2「补货建议」（`V238__replenishment_suggestion.sql`）—— 本模块**只读**其建议。
- 下游：warehouse 7.2.1.1 预占（迁移 V102）—— 本模块**经授权写端口**登记预占。
- 相关 ADR：[ADR-0002 多租户策略](../decisions/0002-multi-tenancy-strategy.md)、
  [ADR-0004 Java / Spring Boot 微服务](../decisions/0004-java-spring-boot-microservices.md)。
- 相邻会话：`docs/sessions/2026-10/2026-10-21-stockout-risk.md`（6.2.1.1）、
  `docs/sessions/2026-10/2026-10-21-purchase-arrival-tracking.md`。
