# 8.2.2.2 待检处理（阶段 D3，L3 锚点 8.2.2）

- 日期：2026-10-21
- 车道：8.2.2.2（分支 `feat/8-2-2-2`，worktree `integration/.worktrees/8.2.2.2`）
- 归属服务：warehouse（卷 08 后端主责服务）

## 决策摘要

1. **本模块补上的是 6.4.2.1 质检单自己都点名的那个缺口**。
   trade 的 6.4.2.1 在能力声明里写明「warehouse 侧当前没有可用的授权写端点 ⇒
   分流只记意图，不假装已分流」，并写「要等 warehouse 提供批量质量状态写端点后，
   7 库存才会把待检 / 残次量扣出可售量」。8.2.2.2 就是那个写端点：
   蓝图输出句「质量状态变更分录；可售量更新」把它落在 warehouse 侧。

2. **质量状态变更在账上的形状不是「改一列」，而是「一对反向分录」**。
   V1/V2 的分层把质量状态放进 balance / bucket 的键里，所以「转可售」= 待检
   balance −Q + 可售 balance +Q（bucket 同步减 / 增）。
   ⇒ 本模块**不新增任何库存数量表**，复用 V1 已有的 inventory_transaction +
   inventory_entry，一次处置 = 一条 transaction + N 条分录。

3. **质检单（6.4.2.1）归 trade 所有，warehouse 只做跨服务调用**。
   不在本地建质检单表、不跨库读写（AGENTS.md 模块边界纪律）；只留逻辑 ID 与单号快照。
   发起质检走 trade 自己的授权端点 POST /trade/quality-inspections（转发调用方
   自己的 bearer，租户仍由 trade 按已认证成员关系解析 ⇒ RLS 两道）。
   trade 不可达一律 503，**绝不冒充「没有质检单」**。

4. **质检结论只有一个来源**。录入结果时**不接受调用方自报数量**，三量一律从
   trade 的质检单回读并冻结成快照 —— 让操作员再敲一遍数字等于让同一个结论在两个
   服务各有一份，迟早对不上且谁也说不清该信谁。

5. **四条不变量交给数据库裁决**（回读账本分录，不是单据自报数字）：P1 转出量 ==
   任务批量；P2 转入量 == 批量 − 离仓量；P3 每个目标余额行的转入量 == 指向它的
   明细量之和（双向比）；P4 目标余额行的质量角色与处置类型一致。
   绕过服务层直连 SQL 也绕不过（集成测试实跑断言）。

6. **目标库位类型复用 8.1.2.2 的权威函数** `location_allocation_required_type(role)`，
   不另抄一份 CASE —— 抄一份就会改一处忘一处，而「残次品混进非残次库位」是蓝图点名的硬规则。

## 关键 trade-off

- **「发起质检」用跨服务写（HTTP）而不是本地表**：代价是多一次网络往返与一次失败
  模式（503）；换来的是「每张事实表只有一个写入所有者」不被破坏。若改为本地建
  质检单表，trade 侧与 warehouse 侧会各有一份质检单，迟早分叉。
- **P3 按「余额行」对账而不是按「第几行」对**：分录不记自己是第几条明细生成的
  （那要额外一列且必然漂），所以对账口径只能是「每个目标余额行的转入量之和 =
  指向它的明细量之和」，且必须**双向**比。
- **报废 / 退货按「离仓」建模**（只有负向分录，没有目标质量状态与目标库位）：
  inventory_transaction.kind 的词表里没有「报废」，整单只做退货时取 kind='RETURN'，
  其余取 'ADJUSTMENT' 并在 notes 里写清构成。

## 文件清单

- 迁移：`backend/services/warehouse/src/main/resources/db/migration/V252__pending_inspection_disposition.sql`
- 后端：`backend/services/warehouse/src/main/java/com/starcon/warehouse/`
  - `PendingInspectionModels.java`（传输模型，全部带 `PendingInspection` 前缀避免 OpenAPI schema 撞名）
  - `PendingInspectionService.java`（jOOQ 显式 SQL）
  - `PendingInspectionController.java`（`/warehouse/pending-inspections/**`）
  - `QualityInspectionTradePort.java`（↔ trade 6.4.2.1 的跨服务端口）
- 测试：`backend/services/warehouse/src/test/java/com/starcon/warehouse/`
  - `PendingInspectionTestBase.java`（真实 PG 16 + identity 桩 + trade 桩）
  - `PendingInspectionIntegrationTest.java`（16 例）
  - `PendingInspectionTenancyIntegrationTest.java`（3 例，租户隔离三断言）
- 契约：`packages/api-contracts/`（codegen 重生成，4348 个 DTO）
- 前端：
  - `apps/web/src/components/warehouse/pending-inspection-model.ts`（+ `.test.ts`，10 例）
  - `apps/web/src/components/warehouse/pending-inspection-workspace.tsx`
  - `apps/web/src/app/warehouse/pending-inspections/page.tsx`
  - `apps/web/src/app/api/warehouse/pending-inspection-allowlist.test.ts`
  - `apps/web/src/lib/api-proxy.ts`（**纯增量**新增一条 services[] 元素）
- 台账：`docs/product/capability-blueprint/module-progress.md`（只改 8.2.2.2 那一行的状态与证据）

## 后续待办

- **6.4.2.2 不良品处理**（trade）交付后，需要它来接管不合格品的退换货补货 / 返修 /
  索赔；本模块已把「不合格量分流出去」的边界准备好（capabilities 里 available=false +
  ownerModule + 影响说明）。
- **6.4.2.1 质检单的结论回写**：目前 warehouse 是**拉取式**（录入结果时回读 trade）。
  若 trade 侧改成主动推送，warehouse 侧的 `record-result` 端点需要与之对齐。
- **建单时选收货明细行**：当前页面上这一项是**手填 goods_receipt_line.id**（待检库存行
  与收货明细行之间没有可直接 JOIN 的键，bucket 不记来源单据）。若要让操作员点选，
  需要在 bucket 上补「来源收货明细行」的登记 —— 那会动到 8.2.1.1 / V2 的既有模型，
  留给后续批次决定。
- **待检库位的一致性**：本模块**不强制**待检货必须放在 QUARANTINE 库位（硬规则是
  「待检品不可售」，由质量角色与预占守卫保证），清单里会对不在待检库位的行给出提示。
