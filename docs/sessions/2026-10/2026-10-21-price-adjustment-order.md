# 2026-10-21 5.4.1.1 调价单：人工调价的受控动作与审批闭环

## 决策摘要

交付蓝图 05 定价卷的 **5.4.1.1 调价单**（L3 锚点 5.4.1，阶段 D6，服务归属 **catalog**）。

蓝图原文：

> **职责**：发起价格变更：选择 SKU 与目标店铺、输入新旧价、说明原因、提交审批（若启用）；记录变更前后值。
> **输入**：调价请求；`expected_version`；审批要求（17 组织的审批规则）。
> **输出**：调价单（草稿/待审/已批/已执行/已驳回）；生效后触发平台回写。
> **关系**：上游 = 5.3.1.1、5.3.2.2；下游 = 5.4.2.1、17 审批。**调价是受控动作**：大额或大幅调价走审批，普通调整可配置免审。

本模块的落地口径（每条都有代码与测试钉住）：

- **一张调价单 = 一个 SKU 在一个店铺上的一次价格变更。** 明细表上用
  `UNIQUE(tenant_id, order_id)` 把「一条单子只有一条明细」写死在 DB 上；
  「一批 listing 统一改价」是 5.4.1.2 的职责（V237 的两张表），本模块**一列都没改**、也没往里写。
- **零个测算算法**：改价前后的毛利额与毛利率全部来自 5.3.1.1 的
  `ProfitCalculatorService.calculate`。平台费率与物流单价仍是**手工假设**
  （5.2.3.1 / 5.2.3.2 未交付），页面上标「手工假设」。
- **当前价只取 catalog 内已有的价格事实**，按时刻取最新：① 5.5.2.1 的
  `price_anomaly_observation`（最新一条观测，优先 `internal_price` 退到 `platform_price`）；
  ② 5.4.1.2 的 `batch_price_adjustment_item` 里记下的新价（可能比观测更新）。
  这两张表各有唯一的写入所有者，本模块**只读**。
  **取不到就整单拒绝**（400 + 写明原因，**绝不用 0 顶** —— 0 会被当成「免费」）；
  **币种不同 ⇒ 整单拒绝，绝不按 1:1 折算**。
- **客户填的旧价与服务端解析到的当前价对不上 ⇒ 409**：表单已过期，
  照着过期价建单是错价。
- **状态机**（`PriceAdjustmentRules.ALLOWED` 白名单，非法转换一律拒绝并说清当前与目标状态）：
  草稿 → 待审 → 已批 → 已执行；草稿 / 待审 → 已驳回；**免审时草稿 → 已批**；
  **已执行与已驳回是终态**（要再调价就再发一张单，「这次到底改成了多少」必须事后可追溯）。
- **乐观锁 `expected_version`**：调价单带 `version` 列，每次状态流转 +1，
  流转语句的 where 带 `and version = ?`，影响行数为 0 ⇒ **409**。
  台账与详情都带出当前版本。
- **下游 5.4.2.1 平台价格同步（未交付）**：本模块在单子**执行**时只**产出「待回写」事实**
  （`price_adjustment_writeback`，幂等键 `price-adjustment:{调价单 id}`），
  **绝不自己调平台 API**；平台定价规则（最低价 / 自动比价）与「被平台调整后的实际价」归下游，
  下游回写完成后调 `POST /catalog/price-adjustments/writebacks/{id}/ack` 把这条事实结掉。

## 审批：Port 承接，且**不自造审批流**

初始任务书假设「17 组织的审批尚未交付 ⇒ 用 Unwired 假降级」。
调度者后续核实并纠正：**identity 服务已交付完整审批能力**，且
`approval_document_type` 出厂注册了 12 种单据类型，其中第 5 个就是
`('PRICE_CHANGE','调价单','售价与成本价调整',50)`（V83 第 304 行）。
据此改为**真审批**：

| 动作 | 真实端点 | 本模块的用法 |
|---|---|---|
| 要不要审 | `POST /identity/approval-rules/evaluate`（17.4.1.2，只读试算） | 提交前求值，`documentTypeCode='PRICE_CHANGE'` |
| 挂到哪条待办 | `POST /identity/approval-instances`（17.4.2.1，`X-Internal-Key` + 会话） | `sourceSystem='catalog'`、`sourceReferenceId=调价单 id` |
| 批了没有 | `GET /identity/approval-records/{id}` | **拉取式**同步已批 / 已驳回 |

三条落地纪律：

1. **「普通调整可配置免审」由 17 组织承担，不自造审批阈值表。**
   `decision=EXEMPT`（命中免审规则）⇒ 直接进已批；
   `source=UNCONTROLLED`（本租户没给 `PRICE_CHANGE` 配生效规则集/流程）⇒ 按 identity 的
   同一语义**默认放行**，不拦死。自造一张「调价单审批阈值表」会让请求方自己选阈值，
   等于给绕过大额管控开了后门。
2. **审批服务不可达 ⇒ 停在待审并如实标错，绝不自动跳到已批。**
   失败原因写进 `approval_sync_error`，页面上原样显示。
   这条不能妥协：把没审过的调价单直接推到执行，就是蓝图那句「调价是受控动作」要防的事。
3. **本模块没有「本地批准」端点。**
   审批结论由 identity 那边的人做出；本模块只把结论**拉取**回来。
   在本地自己判「已批」会让 identity 那边什么都不知道，还留一条永远没人管的待办。
   同理，待审且已有在办实例时，本地「驳回」被**明确拒绝**（409）并告诉用户去 17 组织的待办里驳回。

**状态同步是拉取式的，不是事件推送** —— 本仓还没有事件总线
（`backend/libraries/` 下只有 `tenancy`）。同步有两条触发路径：
显式的 `POST /{id}/approval/refresh`，以及**读详情时顺带刷新**。
两者都只读 identity、拉不到就如实记错，**绝不改状态**。

跨服务调用形态照抄 `trade` 的 `ExchangeReservationPort`：真实 `RestClient` +
`X-Internal-Key` 从配置注入（`${INTERNAL_KEY}`，**绝不写死在代码里**）+
`IDENTITY_URL` 没配时走 `UnwiredPriceAdjustmentApprovalPort`（**显式抛错**，不静默放行）。
租户**不**由本端口传递：转发的是调用方自己的 `Authorization` bearer，
identity 侧仍按已认证成员关系解析租户并强制 RLS —— 隔离是两道，不是「本服务替对方决定租户」。

## 关键 trade-off

1. **另起三张表，不复用 5.4.1.2 的两张。**
   一张调价单（单条、有审批、有版本）与一批批量调价（预览报告、一次性、不可变）
   生命周期不同、审批口径不同、版本闸不同，合并到一张表会出现两套互斥的字段集。
   代价是同一卷里多了三张表；收益是两张表的写入所有者各自唯一、互不干扰。
2. **待回写事实用 outbox 表 + 授权读端点，而不是事件总线。**
   5.4.2.1 的输入句是「价格变更事件」，但本仓**没有事件总线**。
   落成一张带 `idempotency_key` 的 outbox 表，并经 `GET /writebacks` 暴露给下游，
   是当前仓库里唯一能既「产出事实」又不「假装已交付下游」的形态。
   下游的回执（ack）端点也留好了，等 5.4.2.1 落地时直接消费。
3. **审批状态只做「拉取 + 读取时校验」，不做事件订阅。**
   事件订阅需要 identity 侧新增推送通道（那是 17 组织的写入所有权）。
   在那之前，显式刷新 + 读详情顺带刷新已经能让页面看到正确的结论，
   代价是「审批刚批完的那一瞬间列表还是旧的」—— 刷新一下就好，
   而错误地自己发明一个审批流代价大得多。
4. **驳回语义拆成两条路径。**
   - 草稿 → 已驳回：本人撤单，本地状态机的一条合法转换。
   - 待审 → 已驳回：**只能**由 identity 侧的审批结论驱动（拉取回来）。
   两者共用同一个状态机与同一套拒绝规则，但入口不同 —— 这是「不发明审批流」的直接后果。
5. **契约 DTO 一律带 `PriceAdjustment` 前缀。**
   `CurrentPrice` / `RejectRequest` / `ExpectedVersionRequest` / `WritebackAckRequest`
   这类通用名在同一个 `contracts/catalog.ts` 里很容易与后续模块撞名（撞名 = TS 编译错误，
   而 typecheck 是 CI 必跑项）。前缀化把风险挡在并入之前。

## 文件清单

### 后端（catalog 服务）

- 迁移：`backend/services/catalog/src/main/resources/db/migration/V240__price_adjustment_order.sql`
  （三张新表 + 触发器 + RLS + GRANT；零 FK、零 ALTER 既有表）
- `PriceAdjustmentModels.java` —— 词表（Status / PriceSource / Sort）、入参、视图
- `PriceAdjustmentRules.java` —— 纯规则：舍入口径、幅度/价差、**状态机白名单**
- `PriceAdjustmentApprovalPort.java` —— identity 审批端口（HTTP 实现 + Unwired 实现 + 契约形状）
- `PriceAdjustmentApprovalConfiguration.java` —— 端口装配（`IDENTITY_URL` 空 ⇒ Unwired）
- `PriceAdjustmentService.java` —— 服务层（建单 / 台账 / 详情 / 提交 / 执行 / 驳回 / 刷新审批 / 待回写事实）
- `PriceAdjustmentController.java` —— 11 个端点，命名空间 `/catalog/price-adjustments`

### 测试（catalog 服务）

- `PriceAdjustmentRulesTest.java`（10 例）
- `PriceAdjustmentIntegrationTest.java`（24 例）
- `PriceAdjustmentTenancyIntegrationTest.java`（4 例，多租户三断言）
- `PriceAdjustmentFixture.java` —— 底座（继承 `BatchCalcFixture`，**未改它一字符**）+ identity 审批替身

### 前端

- `apps/web/src/components/pricing/price-adjustment-model.ts` + `.test.ts`（7 例）
- `apps/web/src/components/pricing/price-adjustment-section.tsx`
- `apps/web/src/app/pricing/adjust/page.tsx`
- `apps/web/src/app/api/catalog/price-adjustment-allowlist.test.ts`（5 例）
- `apps/web/src/lib/api-proxy.ts` —— **纯增量**新增 `price-adjustments` 一个独立元素，既有正则一字未动

### 契约与台账

- `packages/api-contracts/src/contracts/catalog.ts` + `src/catalog.openapi.json`
  （由 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 重新生成，
  **纯新增 11 个 DTO、零删改**）
- `docs/product/capability-blueprint/module-progress.md` —— 只改 `5.4.1.1` 那一行的「状态」「证据」两格

## 验证

- **L0 编译闸**：`./mvnw -q compile`（backend）+ `pnpm typecheck`（前端）—— 通过
- **L1 模块闸**：
  `./mvnw -pl services/catalog -am test -Dtest='PriceAdjustment*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  ⇒ **Tests run: 38, Failures: 0, Errors: 0**
- **api-proxy 全量 allowlist 回归**（因为改了共享白名单累积正则）：
  `pnpm vitest run apps/web/src/app/api` ⇒ **123 个文件 / 713 例全绿**
- `pnpm lint` —— 通过（只有仓库既有的、与本车道无关的 react-hooks 警告）

## 后续待办

1. **5.4.2.1 平台价格同步**（下游，未交付）：消费 `GET /catalog/price-adjustments/writebacks`
   （`status=PENDING`），按 `idempotency_key` 去重回写平台，处理平台的最低价 / 自动比价规则，
   记录「被平台调整后的实际价」与期望价的差异，最后调 `POST /writebacks/{id}/ack`。
2. **审批结论的推送**（17 组织侧）：当前是拉取式同步。要做到「批完立刻可见」，
   需要 identity 侧新增一条事件通道 —— 那是 identity 的写入所有权，本模块不代劳。
3. **5.5.1.1 价格历史**（同批另一车道）：本模块的 `price_adjustment_order_item`
   （SKU × 店铺、旧价、新价、价差、观测时刻、原因、操作人）已经是价格变更事实的现成来源，
   但**该表的写入所有者是本模块**，价格历史只应**读**它，不要另起一份。
4. **调价单的撤销 / 重开**：当前「已执行」「已驳回」是终态（蓝图那句「记录变更前后值」的要求），
   要改价就再发一张单。如果业务上需要「撤销一次已执行的调价」，需要另开一条补偿流程，
   而不是给终态加一个 UPDATE。
