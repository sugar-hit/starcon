# 2026-10-21 7.4.1.1 调整单管理（卷 07 库存核算 / L3 锚点 7.4.1 库存调整）

## 决策摘要

- **归属 warehouse 服务**，后端命名空间 `/warehouse/inventory/adjustments`，前端路由 `/inventory/adjustments`。
- **新建独立表而不是复用 V62 的 `stocktake_variance_*`**：V62 的 `stocktake_variance_adjustment` 头表有
  NOT NULL `sheet_id`（必须有盘点表才能建单），而本模块处理的是损坏 / 报废 / 错发纠正这类**通用调整** ——
  它们根本没有盘点表。硬复用等于把「盘出来的」和「盘不出来的」塞进同一张单据，而审批人恰恰要靠这个
  判断风险（盘出来的有账面数兜底，盘不出来的没有）。⇒ 五张新表 `inventory_adjustment_*`，
  分录以 `ref_type='INVENTORY_ADJUSTMENT'` 与 7.4.2.2 区分，**零 ALTER 既有表**。
- **蓝图红线「调整必须走单据」落在 DB 层而不是只落在 API 层**：九道触发器照抄 V62 纪律 ——
  状态机 / 入账闸门（未 APPROVED 不得写分录）/ 入账数量守恒 / 行不可改 / 单不可删 / 审计 append-only /
  行六维与 bucket 一致 / 只有草稿能删行 / 只有草稿能挂摘附件。应用层可以被绕过，DB 层不会。
- **状态枚举一次写全六个**（DRAFT / PENDING_APPROVAL / APPROVED / REJECTED / POSTED / CANCELLED），
  7.4.1.2 交付时不必 ALTER 本表 ⇒ 跨车道 DDL 回归面 = 0。
- **不建「调整流水表」**：分录落既有 `inventory_entry`，7.1.2.1 流水台账自然可见
  （集成测试实跑 `GET /warehouse/inventory/ledger?refTypes=INVENTORY_ADJUSTMENT` 断言）。再建一张就是第二个口径。
- **原因码是受控词表（6 个）**：`inventory_adjustment_reason` 表 + `InventoryAdjustmentReason` 枚举，
  码给统计用、原文（自由文本 `note`）给追责用，两个都要：
  `OVERAGE` 盘盈 / `SHORTAGE` 盘亏 / `DAMAGE` 损坏 / `SCRAP` 报废 / `MISSHIP_CORRECTION` 错发纠正 /
  `PLATFORM_VARIANCE` 平台仓差异（**唯一 available=false 的码**）。词表表是**全局的**（RLS 关闭）——
  它是跨租户共用的码表，租户隔离靠 order 表而不是靠码表。
- **不建审批人角色表**：见下节「审批动作的归属口径」。

## 审批动作的归属口径（本次最需要说清楚的一件事）

蓝图把 7.4.1.1 与 7.4.1.2 都挂在 `/inventory/adjustments` 下，7.4.1.2 叫「调整审批」。
本模块**没有**实现完整审批体系，落地的是：

| 能力 | 归属 | 本模块的做法 |
| --- | --- | --- |
| 阈值审批规则（金额 / 数量阈值、批量审批、驳回工作流、17.4 审批中心接入） | **7.4.1.2** | `capabilities` 里 `thresholdApproval` 恒 `available=false` + `ownerModule=7.4.1.2` |
| 执行「批准」这一动作 | **7.4.1.1（本模块）** | 显式端点 `POST {id}/approve` + 必填理由 + 提交人 ≠ 审批人（DB CHECK）+ DB 状态机 |

为什么批准动作放本模块：没有它，7.4.1.2 就没有可审批的对象（谁来产出一张「待审」的单？），
蓝图列的本模块输出也明写着「调整单（草稿 / 待审 / 已批 / 已执行）」——**「已批」是本模块的输出之一**。
为什么阈值规则不放本模块：阈值是策略配置（谁该批、批多少、批量怎么审），属 7.4.1.2；
本模块自建规则表 = 造出第二个审批事实源。

**为什么批准权限码复用 `warehouse.inventory.update` 而不是新增一个**：新增权限码要在 identity 服务加迁移，
而 identity 的 Flyway 版本号**没有预分配给本车道**（当前 max V163）。并行车道撞号会让 Flyway 直接炸，
且后果不可逆。专属审批权限码随 7.4.1.2 一并注册。代价：本模块的批准动作暂时没有独立的权限码，
读 / 写 / 批准三者共用 update 码。

7.4.1.2 交付时的迁移面：把「批准判定」换成调用审批中心，**表结构与状态机一行都不用动**。

## 过账前置条件与 DB 触发器

`POST /warehouse/inventory/adjustments/{id}/post` 是本模块**唯一**会改库存余额的端点。它的前置条件：

1. 单据状态必须是 `APPROVED`（状态机触发器钉死）；
2. **每行仍要带 `expected_version`**，过账时**再校一次** `stock_bucket.version` —— 建单时校一次是不够的，
   一张单从建单到过账可能隔了很久，「拿旧数字改新余额」是这条路径最典型的翻车方式；
3. 盘盈必须正数、盘亏必须负数（服务层 400 拦截）；
4. 数量最多 4 位小数，用 `setScale(4, UNNECESSARY)` 报错，**不做静默四舍五入**。

过账时 DB 层还有三道闸：入账闸门（未 APPROVED 直接写分录 → 触发器拒）、入账数量守恒
（分录合计必须等于单据净量，少记 / 多记 / 只入一部分都拒）、行六维与 bucket 一致（行上的仓库 / 货位 / SKU /
批次 / 质量状态必须与 `stock_bucket` 对得上，否则调的就是另一批货）。

过账成功后写一条 `inventory_transaction(ADJUSTMENT)` + 逐行带符号分录，同时改 balance 与 bucket，
分录立刻出现在 7.1.2.1 台账里。

## 6.4.2.2 跨服务接入：是否可用、失败原因

蓝图把 6.4.2.2（不良品处理）列为本模块的上游之一，实现方式是**授权 HTTP**，照抄
`QualityInspectionTradePort` 的形态：`DefectiveDispositionTradePort` 转发调用方的 bearer 调
trade 的 `GET /trade/defective-dispositions`，**不跨库读 trade 的表**（AGENTS.md 模块边界纪律）。

**可用性**：端口**真的会去敲一次 trade**，而不是看配置猜（`TRADE_URL` 默认 `http://127.0.0.1:8083`）。
所以在本地 / 测试环境 trade 不在跑时：

- `capabilities` 里 `defectiveDisposition` 报 `available=false` + 原因 + `ownerModule=6.4.2.2`，
  **不返回空数组冒充「没有不良品处置单」** —— 那是把「查不到」说成「没有」，错得最隐蔽；
- 引用来源建单时上游不可达 → **503**（不是 404、不是 422），文案写明「调整单仍可独立发起（不引用来源）」；
- 上游可达但处置单里没有那条指令 → 404 + 明确指出「该指令不属于该处置单」。

本次集成测试环境下 trade **不可达**，因此验证的是**不可用路径**：capabilities 报 `available=false`，
引用来源返 503。可用路径的契约由 trade 侧的 `GET /trade/defective-dispositions` 端点保证，
但**跨服务的端到端联通未实跑**（需要 trade 与 warehouse 同时起）—— 这是本模块已知的验证缺口。

## 平台仓差异为什么不可用

蓝图输入里列了「平台仓差异」。本模块**保留这个词表码**（vocabulary 完整）但**建行时显式 422 拒绝**：

- V55 的 `guard_platform_warehouse_no_ledger` 禁止给 `PLATFORM_FBA` 仓写 balance / bucket 行 ——
  平台仓根本没有可调的 `stock_bucket` 行可调；
- `platform_stock_observation` 是 append-only 的观测序列，不是可写余额。

把平台观测值当成自货调整掉，会制造出「平台仓也有自有账本」的第二个真相。⇒ capabilities 报
`available=false` + `ownerModule=10.1.1.1 / 8.1.1.1`，平台仓差异请走 10 外部仓对账与 7.1.3.1 多仓分布。

## 关键 trade-off

1. **新建五张表 vs 复用 V62 的 `stocktake_variance_*`**：选了新建。代价是同一「调整单」概念有两套表，
   收益是两类单据在契约上分得清（盘出来的有账面兜底，盘不出来的没有 —— 审批风险不同），
   且**零 ALTER**（跨车道 DDL 回归面 = 0）。
2. **批准动作归本模块、阈值规则归 7.4.1.2**：蓝图没写清，本模块按「输出里含『已批』」这条线索取最小口径。
   代价是批准权限暂时复用 update 码（见上）。
3. **附件只存逻辑引用 + 快照元数据**（媒体 id + 文件名 + 类型 + 大小 + 挂载时刻）：媒体库归 catalog 且是独立库，
   本模块**不建跨库外键、不跨库写、不本地复制文件**。代价是文件本体不在 warehouse 库里，
   将来做「凭证永久可读」需要 catalog 侧保证媒体不删。
4. **草稿可删行 / 摘凭证走 `POST {id}/.../remove` 动作后缀，路径上刻意不放 DELETE**：调整单不可删（作废留痕），
   DB 触发器也钉死只有 DRAFT 能删。代价是路径形状不典型，但比在路径上放 DELETE 诚实。
5. **过账时再校一次 `expected_version`**：多一次查询换一个「旧版本号改新余额」的防线，值得。

## 过程中被测试 / 类型检查逼出来的真实缺陷（已修）

1. **api-proxy 白名单正则括号不平衡**：插入的分支多了一个 `)`，导致第一组提前闭合、顶层 `|` 拆散整个 pattern、
   **`$` 锚点失效** —— `inventory/adjustments/summary`、`.../post/force`、`.../not-a-uuid` 全被错误放行。
   症状极隐蔽：静态检查全绿，只在浏览器里表现为「点一个不存在的动作居然没 404」。
   ⇒ 用 node 从文件里**抽出真实正则**逐条跑 27 个 case（放行 14 / 拒绝 13）来钉，而不是眼看正则。
2. **`errorText` 的 503 分支把后端原文直接抛给用户**：上游不可达时后端会带原因（如「读取 6.4.2.2 不良品处置单失败：…」），
   直接返回原文**看不出这是「不可用」**，用户最容易误判成「这单不能做」—— 而实际上调整单仍可独立发起。
   ⇒ 503 一律加「依赖的服务不可用：」前缀并保留后端原因。
3. **allowlist 测试里有两条路径本来就是错的**：`warehouse/inventory/count/capabilities` 与
   `warehouse/inventory/aging/capabilities` —— 后端**没有**这两个端点（`InventoryAgingController` 只有
   buckets / slow-moving / policies / policy），白名单里也从来没有它们（HEAD 上就是 404）。
   断言「必须 200」测的是「代理有没有放行一个不存在的路径」，与「有没有误伤」无关。⇒ 换成后端真实存在的端点。
4. **`stocktake-variances/adjustments` 被断言成 404**：那是 7.4.2.2 **自己**的既有端点（HEAD 上就放行），
   断言 404 会把「独立分支」误写成「掐掉兄弟模块的路由」。⇒ 改为断言通用调整单的能力面在顶层、
   且**没有**在 7.4.2.2 的前缀下再挂一份。
5. **共享白名单测试里「调整单尚未交付因而必须 404」的旧断言**（`route.test.ts` 与
   `cross-entity-transfer-allowlist.test.ts`）：本模块交付后失效，按 7.2.1 / 7.5.1.1 / 7.1.2.1
   交付时的同一处理方式移除并改写注释。
6. **typecheck 抓出 5 处真实类型错误**（前端工作区）：`exactOptionalPropertyTypes` 下不能显式传
   `undefined`（改为主动筛掉空值再拼 `BucketParams`）；`InventoryAdjustmentPostResult` 上**没有** `netQty`，
   正确字段是 `postedQty`（= 后端 `order.netQty()`）；`Notice` 的 tone 词表是
   `info/success/warning/danger`（不是 `warn/error`）且 `title` 是**必填**。

## 文件清单

### 后端（warehouse）

- `backend/services/warehouse/src/main/resources/db/migration/V260__inventory_adjustment_order.sql`
  —— 五张新表 + RLS（ENABLE **FORCE**）+ 索引 + GRANT/REVOKE + 九道护栏触发器。零 ALTER 既有表。
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryAdjustmentModels.java` —— 枚举 + records
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryAdjustmentService.java` —— 服务层（jOOQ 显式 SQL）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryAdjustmentController.java`
  —— 15 个端点（13 个在 `/warehouse/inventory/adjustments` + 2 个在
  `/warehouse/inventory/adjustment-sources/defective-dispositions`）
- `backend/services/warehouse/src/main/java/com/starcon/warehouse/DefectiveDispositionTradePort.java` —— 读 6.4.2.2（授权只读）

### 测试

- `backend/services/warehouse/src/test/java/com/starcon/warehouse/InventoryAdjustmentIntegrationTest.java`（21 例）
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/InventoryAdjustmentTenancyIntegrationTest.java`（3 例，
  租户隔离三断言实跑）

### 前端

- `apps/web/src/app/inventory/adjustments/page.tsx`
- `apps/web/src/components/inventory/inventory-adjustment-workspace.tsx` —— 真实工作区
- `apps/web/src/components/inventory/inventory-adjustment-model.ts` + `.test.ts`
- `apps/web/src/app/api/warehouse/inventory-adjustment-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts` —— **在 warehouse 的 `inventory(...)` 分组里就地扩展**两条（`adjustments` 与
  `adjustment-sources`），只追加不收窄既有分支

### 改动（既有文件）

- `backend/services/warehouse/src/main/java/com/starcon/warehouse/InventoryBucketService.java`
  —— 把 ADJUST 动作的 `available` 由 false 翻成 true（7.1.1.2 的代码注释逐字写着「目标模块交付时只要改这里的
  available」），这是行内入口的交接点。
- `backend/services/warehouse/src/test/java/com/starcon/warehouse/InventoryBucketIntegrationTest.java` —— 同步断言
- `apps/web/src/app/api/warehouse/[...path]/route.test.ts`、`cross-entity-transfer-allowlist.test.ts`
  —— 移除「尚未交付因而必须 404」的旧断言

### 契约 / 台账

- `packages/api-contracts/src/contracts/warehouse.ts`、`packages/api-contracts/src/warehouse.openapi.json`
  —— codegen 产物（+18 DTO，纯新增）
- `docs/product/capability-blueprint/module-progress.md` —— 只改 7.4.1.1 那一行的「状态」「证据」两格

## 后续待办

- **7.4.1.2 调整审批**：阈值规则、批量审批、驳回工作流、17.4 审批中心接入；专属审批权限码在 identity 注册；
  本模块的 `thresholdApproval` 能力位由 `available=false` 改成 true。**表结构与状态机不需要改**。
- **6.4.2.2 的端到端联通**：本模块的可用路径未实跑（需 trade 与 warehouse 同时起），
  `DefectiveDispositionTradePort` 的 `available=true` 分支目前只有契约保证，没有实跑证据。
- **平台仓差异**：归 10.1.1.1 / 8.1.1.1。若后续平台仓获得可调 bucket，本模块的 `PLATFORM_VARIANCE`
  码需要重新评估（当前是 422 拒绝）。
- **凭证附件的长期可读性**：附件只是 catalog 的逻辑引用 + 快照元数据，媒体被删之后凭证会取不到本体，
  需要 catalog 侧提供「媒体不可删」或本模块落一份对象存储指针 —— 尚未决定归属。

## 关联

- 蓝图：`docs/product/capability-blueprint/07-inventory.md`（7.4.1.1 调整单管理）
- 台账：`docs/product/capability-blueprint/module-progress.md` 第 251 行
- 参考样板：`backend/services/warehouse/src/main/resources/db/migration/V62__stocktake_variance_handling.sql`、
  `StocktakeVarianceService.java`、`backend/services/trade/.../QualityInspectionTradePort.java`
