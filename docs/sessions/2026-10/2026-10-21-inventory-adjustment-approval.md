# 2026-10-21 7.4.1.2 调整审批（卷 07 库存核算 / L3 锚点 7.4.1 库存调整）

## 决策摘要

- **归属 warehouse 服务**，后端命名空间 `/warehouse/inventory/adjustment-approvals`（**刻意独立**于
  7.4.1.1 的 `/warehouse/inventory/adjustments`），前端与 7.4.1.1 共用蓝图路由 `/inventory/adjustments`（页签分开）。
- **阈值规则自持，不委托 17 组织的通用审批规则服务。** identity 的 `approval_trigger_rule`（V89）维度只有
  「金额 + 跨法人/跨部门/新供应商/高风险商品」，**没有「数量阈值」，也没有「调整单类型」**；
  它的 `/approval-rules/evaluate` 入参（`ApprovalEvaluationRequest`）同样只有
  `{documentTypeCode, currencyCode, amount, 4 个三态布尔}` —— 数量与调整单类型都传不进去。
  要让 identity 表达这两维就得**改 identity 的规则模型**（另一个服务的写入所有权），而且 identity 的
  Flyway 版本号**没有预分配给本车道**（当前 max V163）⇒ 并行车道撞号会让 identity 的 Flyway 直接炸掉。
  ⇒ **与 6.3.1.3 采购单审批（V262）同口径自持**，能力声明里 `identityApproval` 恒 `available=false`
  并点名原因（6.3.1.3 已经证明过同一个结论，本车道不重复同一次判断）。
- **批准动作仍归 7.4.1.1，本模块不重复实现。** 7.4.1.1 的会话日志已把边界写死：
  「阈值审批规则归 7.4.1.2 / 执行批准这一动作归 7.4.1.1」。⇒ 本模块的 `/approve` 做的是
  **阈值判定 → 记审批链路 → 人数凑齐 ⇒ 调 7.4.1.1 的 `InventoryAdjustmentService.approve`**。
  7.4.1.1 的表结构与状态机**一行未动**，只在 `inventory_adjustment_order` 上**新增两个触发器**（纯增量）。
- **阈值判定只有一个实现**：`inventory_adjustment_approval_match(...)` 是 DB 函数，服务端与数据库共用
  ⇒ 不存在「两套判定漂移」（照抄 6.3.1.3 的 `purchase_order_approval_match` 口径）。
- **DB 级阈值闸门**：本租户存在生效规则时，「待审 → 已批」这一步必须先过阈值判定且审批人数凑齐，
  否则数据库当场拒绝 —— 绕过审批中心直接调 7.4.1.1 的批准端点会报错，而不是悄悄放行。
  集成测试**真的**去数据库里硬改状态来证明这一点。
- **金额是「申报值」**：warehouse 库里**没有任何成本列**（`inventory_balance` / `stock_bucket` 逐列核对过），
  12 财务服务**不存在** ⇒ 调整单没有原生金额。金额由提交人**申报**、**提交那一刻冻结**、进 append-only
  审批链路记录。会计影响落 **warehouse 侧自有事实**（`inventory_adjustment_valuation`，
  **登记额 / 生效额分两列**，照抄 6.3.1.3 的 `purchase_order_payable` 与 11.2.3.3 的
  `supplier_claim_settlement` 写法）：过账那一刻生效额 = 登记额，与库存分录同一个事务。
- **不另建「调整审批流水表」**：审批链路记录是本模块自己的事实（append-only），
  而**库存分录**仍落 7.4.1.1 的 `inventory_entry`，7.1.2.1 流水台账自然可见。
- **7.4.1.1 的 `thresholdApproval` 能力位由 `available=false` 翻成 `true`**（上游日志点名的交接点），
  文案改成「阈值审批、批量审批与驳回工作流已由 7.4.1.2 交付」。

## 关键 trade-off

1. **自持规则表 vs 委托 identity** ⇒ 选了自持。代价是「审批规则」这件事在本仓有两套模型
   （identity 的通用审批规则 + warehouse 的调整阈值规则），收益是边界清楚、判定唯一、不改别的服务的规则模型。
   代价写进能力声明，不藏。
2. **阈值闸门「本租户无生效规则时放行」** ⇒ 选了这个口径，而不是「无规则 = 一律拒绝」。
   理由：① 7.4.1.1 的基础管控（提交人≠审批人的 DB CHECK、必填理由、状态机、入账闸门）**一道不少**，
   「没配规则就不审」是错的，「没配规则就**没有阈值档**」是对的；② 若做成「无规则即拒绝」，
   7.4.1.1 已交付的 25 个集成测试会当场红（它们不走本模块的提交路径）⇒ 那是跨车道回归，不该由本车道制造。
3. **金额用「申报值」而不是想办法算出来** ⇒ 选了申报 + 冻结 + 留痕。
   算出来的前提是有一个**权威成本口径**，而本仓没有（warehouse 无成本列、12 财务不存在、
   catalog 的 `sku_cost_revision.total_unit_cost` 是**当前**成本修订不是这次调整的估值、且 catalog 是独立库）。
   残余风险（提交人低报以躲开金额档）**如实写进能力声明**，缓解是「冻结不可改 + 审批人看得到 +
   申报人与审批人分离 + 申报进 append-only 记录」。
4. **不实现「免审 / 自动通过」** ⇒ 蓝图 7.4.1.2 没有点这个名，且 V260 的职责分离 CHECK
   （`decided_by_user_id <> submitted_by_user_id`）会让「系统替提交人自动批准」直接违约
   （需要伪造一个系统身份）⇒ 那是 mock，不做。
5. **专属审批权限码 `warehouse.inventory.approve` 本次不注册** ⇒ identity 的 Flyway 版本号没预分配给本车道，
   撞号后果不可逆（7.4.1.1 的会话日志已写明同一判断）。本次沿用 `warehouse.inventory.update`
   —— **未注册的权限码会让所有人 403**（`AccessService.require` 同时要 `update` 与 `inventory.read`），
   所以宁可复用已注册的码，也不造一个用不了的码。专属码列为后续待办。
6. **审批单按「次」建（`attempt_no`）而不是「一张单一条」** ⇒ 驳回后重提会开**新的一条链**，
   上一轮的判定快照与链路记录**不被改写**（提交那一刻冻结的东西不能被改写）。
   用部分唯一索引 `WHERE status <> 'REJECTED'` 保证一张单同时只有一条「活着的」审批单。
7. **批量用保存点而不是「一张一个事务」** ⇒ 照抄 6.3.1.3：保存点回滚 = 这一张相当于没发生过，
   其它张不受影响；而回滚**失败不吞掉**（让整批失败比提交一条不一致的审批留痕安全）。
8. **api-proxy 就地扩展 `inventory(...)` 分组** ⇒ 照抄 7.4.1.1 的做法（任务书点名的本仓既定做法），
   `adjustment-approvals` 段**紧邻** 7.4.1.1 的 `adjustment-sources` 段插入，**只追加不收窄既有分支**。
   `{uuid}` 段用**完整 UUID 形状**（不是 `[0-9a-f-]+`）—— 后者会把 `bad` / `dead` 这类非 UUID 字面量也当成 id 放行。

## 过程中被测试 / 类型检查逼出来的真实缺陷（已修）

1. **plpgsql 变量与表别名同名遮蔽**（`guard_inventory_adjustment_order_threshold_approval`）：
   记录型变量叫 `c`，而 `FROM inventory_adjustment_approval_case c` 的别名也叫 `c` ——
   plpgsql 会把 `c.status` 解析成「记录变量 c 的字段」，判定读到错误的行（首次表现是批准动作 409）。
   ⇒ 变量改名 `v_case`。**这类错只在真跑 PostgreSQL 时才暴露**，静态检查全绿。
2. **`SET v.effective_amount = ...` 是语法错误**：PostgreSQL 的 SET 目标列**不能带表别名**
   （右侧引用才可以）。⇒ 改成 `SET effective_amount = v.registered_amount`。
3. **`rejected_at=?` 漏了 `?::timestamptz`**：`timestamptz` 列拿到 varchar 绑定直接报错
   （`column "rejected_at" is of type timestamp with time zone but expression is of type character varying`）。
   ⇒ 补 cast。**时间列的 cast 一律要写全**，漏一个就是运行时才炸。
4. **审批单投影不一致**：jOOQ 的 `record.get(name, type)` 遇到**不存在的列会抛 IllegalArgumentException**，
   而列名是否一致取决于每处查询自己写 ⇒ 把审批单的列集合固定成一个常量 `CASE_COLUMNS`，三处查询共用。
5. **待审批列表查了不存在的聚合列**：`o.net_qty` / `o.line_count` 是**聚合出来的**，单表上没有
   ⇒ 改成相关子查询。
6. **测试夹具撞唯一约束**：`inventory_balance` 对六维有唯一约束，一个租户内只能有一条 ——
   测试里每次建单都新建 balance ⇒ 改成「每个测试建一条，多张单复用」。
7. **契约里 `priority` / `note` 是「可为 null」而不是「可省略」**：`exactOptionalPropertyTypes` 下
   必须**显式**给值 ⇒ 前端 payload 显式带上（不写来表达「没填」是不行的）。
8. **`Map.of` 不接受 null 值**：测试里「上限留空 = 无上限」是正常业务语义 ⇒ 换 `LinkedHashMap`。
9. **错误体断言**：`ResponseStatusException` 在 MockMvc 里**没有 body**（与 7.4.1.1 同一口径）⇒
   只断言状态码，不断言 `$.message`。
10. **append-only 要验两道防线**：运行角色被 `REVOKE UPDATE,DELETE` 挡住（第一道），
    触发器连超管也挡（第二道）⇒ 两条分别断言（第二道用超管连接跑）。

## 文件清单

### 后端（warehouse）

- `backend/services/warehouse/src/main/resources/db/migration/V267__inventory_adjustment_approval.sql`
  —— 五张新表 + RLS（ENABLE **FORCE**）+ 索引 + GRANT/REVOKE + 判定函数 + 两个触发器。
  **零 ALTER 7.4.1.1 的既有列与状态机**（只在它的表上新增触发器）。
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryAdjustmentApprovalModels.java`
  —— 22 个 DTO（枚举外的全部 record）。
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryAdjustmentApprovalService.java`
  —— 服务层（jOOQ 显式 SQL；**调用** 7.4.1.1 的 submit/approve/reject，不重复实现）。
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryAdjustmentApprovalController.java`
  —— 17 个端点。

### 改动（既有文件）

- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryAdjustmentService.java`
  —— **只改能力声明那一处**：`thresholdApproval` 的 `available` 由 `false` 翻成 `true`，
  文案改成「已由 7.4.1.2 交付」并写清「批准动作仍是显式端点 + 必填理由 + 提交人≠审批人 + DB 状态机」。

### 测试

- `backend/services/warehouse/src/test/java/com/starcon/warehouse/InventoryAdjustmentApprovalIntegrationTest.java`（23 例）
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/InventoryAdjustmentApprovalTenancyIntegrationTest.java`（3 例，
  租户隔离三断言实跑，五张表逐张验 `relforcerowsecurity`）

### 前端

- `apps/web/src/app/inventory/adjustments/page.tsx` —— 加页签（单据 / 审批共用蓝图路由）
- `apps/web/src/components/inventory/inventory-adjustment-approval-workspace.tsx` —— 真实工作区
- `apps/web/src/components/inventory/inventory-adjustment-approval-model.ts` + `.test.ts`
- `apps/web/src/app/api/warehouse/inventory-adjustment-approval-allowlist.test.ts` —— 白名单双向断言
- `apps/web/src/lib/api-proxy.ts` —— **在 warehouse 的 `inventory(...)` 分组里就地追加** `adjustment-approvals` 段

### 契约 / 台账

- `packages/api-contracts/src/contracts/warehouse.ts`、`packages/api-contracts/src/warehouse.openapi.json`
  —— codegen 产物（+22 DTO，纯新增；**trade 侧的生成物已回退**，那属于别的车道）
- `docs/product/capability-blueprint/module-progress.md` —— 只改 7.4.1.2 那一行的「状态」「证据」两格

## 后续待办

- **专属审批权限码 `warehouse.inventory.approve`**：需要在 identity 注册权限码，
  而 identity 的 Flyway 版本号**没有预分配给本车道**（当前 max V163）⇒ 本次沿用
  `warehouse.inventory.update`，并在能力声明里写明。拿到 identity 的版本号后补。
- **17.4 审批中心接入**：identity 的规则模型补上「数量阈值」与「调整单类型」两维之后，
  本模块的规则表可以考虑改为委托 identity（届时 `identityApproval` 能力位可以翻成 true）。
- **12 财务落地**：真正的库存估值口径随 finance 服务落地后经授权 API / event 衔接；
  届时「申报值」可以退化为「系统估值」，`inventory_adjustment_valuation` 的登记额改为系统写入。
- **免审（自动通过）**：当前不做（V260 的职责分离 CHECK 不允许系统替提交人批准）。
  若业务需要，要先决定系统身份怎么表达。
- **6.4.2.2 的端到端联通**（7.4.1.1 遗留）：本模块不涉及。

## 关联

- 蓝图：`docs/product/capability-blueprint/07-inventory.md`（7.4.1.2 调整审批）
- 上游会话日志：`docs/sessions/2026-10/2026-10-21-inventory-adjustment-order.md`（边界口径的来源）
- 同口径先例：6.3.1.3 采购单审批 `backend/services/trade/src/main/resources/db/migration/V262__purchase_order_approval.sql`
  （自持规则表 + DB 判定函数 + 登记额/生效额分两列）
- 台账：`docs/product/capability-blueprint/module-progress.md` 第 269 行
