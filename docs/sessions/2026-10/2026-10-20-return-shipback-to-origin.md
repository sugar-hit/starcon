# 2026-10-20 10.7.1.2 退运回国（channel 服务，D5 期）

> 车道 `feat/10-7-1-2`，基线 `integration/module-dev`（baseSha 642e941e）。
> 蓝图原文：`docs/product/capability-blueprint/10-logistics-and-freight.md` § 10.7.1.2。

## 决策摘要

**加粗句「退运是批量、长周期、高单票成本操作，需合单以摊薄成本」落成三道结构闸。**

蓝图这一句最容易做成「一个退运单的空壳 + 一个留空的分摊字段」。本轮三件事同时做：

1. **合单是真的** —— 退运单是一张**批次单**，明细行可拼进**多条**处置单
   （一次 `POST /consignments` 提交多行 = 凑单 / 合箱）；**一条处置单最多进一个退运单**
   （`UNIQUE(tenant_id, disposition_order_id)` + 触发器 `guard_return_shipback_line_order`）。
   同一条货在两个退运单里 ⇒ 成本被摊两次、退运量算两遍。
2. **成本分摊是真的算出来的** —— 每记一笔成本就**立刻**产出逐行分摊
   （权重 / 权重合计 / 比例 scale 4 / 分摊金额 / 尾差调整额）。
   算法是「先算总额 → 按权重逐行分摊 → **尾差补到最后一笔**」，
   **SUM(分摊金额) 恒等于该笔成本金额**。
3. **分摊口径是数据不是代码常量** —— 每笔成本自带口径（重量 / 件数 / 申报货值 / 平均）；
   **权重合计为 0 时显式回退 EQUAL 并把实际口径写进 `applied_basis`**，绝不静默换口径。

其余关键决策：

- **上游边界按「同服务 / 跨服务」一刀切**：待退运清单来自 **10.7.1.1，它就在 channel（同一服务）**
  ⇒ 本模块**直接读** `return_disposition_order`，并由触发器钉死「必须是 `RETURN_TO_COUNTRY` 且未撤销」
  —— 转销售 / 弃置销毁的处置单**根本进不来**（服务层 422 + DB 拒各一次）。
  反过来 **10.5.1 报关（warehouse / trade）与 3.7.1.1 HS 编码（catalog）全部跨服务**
  ⇒ 本模块的报关资料是**本服务自持快照**（`source_reference` 只记上游单号做逻辑引用，
  零跨库 join、零跨服务 FK）。跨服务的那一半用快照 + 逻辑引用，同服务的那一半直接读，
  分界不靠「都做成快照」也不靠「都跨服务调」。
- **关税是派生的，不给两个互相矛盾的数**：`tariff_amount = declared_value × tariff_rate`
  （HALF_UP 到 4 位），`total_amount = tariff_amount + tax_amount` 由 DB CHECK 钉死。
  税率填 `15`（而不是 `0.15`）直接拒。
- **申报品名独立必填**：不照搬销售标题（10.4.1.1 的同款纪律 —— 申报品名给清关看，
  销售标题给转化看，混用是常见退单原因）。
- **下游缺口按仓库先例显式声明**：12 财务（finance 服务**不存在**）⇒ 退运成本以本服务自持的
  成本行 + 分摊明细落地（合计**不落表**、查询时 SUM 现算）；
  7 库存 7.4.1.1 与 10.7.1.3 **均未交付** ⇒ 重新入库 / 报废分录**只出事实不消费**
  （`posting_status` 恒 PENDING、`consumer_module` 恒 7.4.1.1 / 10.7.1.3，
  且 `REVOKE UPDATE/DELETE`）。13 售后分析未交付 ⇒ 退运比例由 13 在本模块事实之上自算。
- **状态机只有一处真相**（DRAFT → CONFIRMED → DISPATCHED → ARRIVED → CLOSED，
  CANCELLED 只在 DRAFT / CONFIRMED）：DB 触发器钉死合法迁移对；
  **确认前**必须既有明细行（空单摊不了成本）又有报关资料（退运回国必须报关）；
  **关闭前**每一行都要有重新入库 / 报废去向；**入库分录只在 ARRIVED 之后**可登记
  （货没回来就入库是假的）。

## 关键 trade-off

- **分摊金额按 scale 4 而不是 scale 2**：分摊金额落在 `NUMERIC(19,4)`，
  尾差因此是 `0.0001` 而不是 `0.01`。选 scale 4 是因为它与
  `NUMERIC(19,4)` 的金额列、与比例列的 scale 4 一致，**全链路口径统一**；
  代价是尾差看起来比「分到分」小。合计守恒由提交时的 DEFERRABLE 约束触发器保证，
  不依赖任何浮点累加。
- **尾差补在「最后一行」而不是「最大权重行」**：确定性优先（同一笔成本重算两次结果一致），
  尾差量级（< 0.0001 × 行数）远小于行金额，不会让任何一行变负。
- **退运单不支持改单**：退运单不是报价单，没有改单的业务价值；已发出的批次只能走
  「收货 → 入库 / 报废」，错单靠取消重来。代价是操作员改参数要重开一单。
- **候选清单用「未进过退运单」而不是「未发出」**：一条处置单进退运单就算被占用，
  避免同一批货被两个退运单分别记一次成本。

## 文件清单

### 后端（channel 服务）

- `backend/services/channel/src/main/resources/db/migration/V219__return_shipback_to_origin.sql`
  —— 八张表 + 状态机 / 行 / 报关 / 成本币种 / 分摊合计 / 入库分录六个触发器
  + RLS isolation 与 RESTRICTIVE data_scope + GRANT / REVOKE。
- `backend/services/channel/src/main/java/com/starcon/channel/ReturnShipbackModels.java`
  —— 请求 / 响应形状与枚举词表。
- `backend/services/channel/src/main/java/com/starcon/channel/ReturnShipbackService.java`
  —— jOOQ 显式 SQL（无 JPA 懒加载），含分摊算法。
- `backend/services/channel/src/main/java/com/starcon/channel/ReturnShipbackController.java`
  —— 16 个端点，挂在 `/channel/return-shipbacks`。
- `backend/services/channel/src/test/java/com/starcon/channel/ReturnShipbackIntegrationTest.java`
  —— 29 例。
- `backend/services/channel/src/test/java/com/starcon/channel/ReturnShipbackTenancyIntegrationTest.java`
  —— 3 例（多租户三断言）。

### 前端

- `apps/web/src/app/logistics/return-shipback/page.tsx`
- `apps/web/src/app/logistics/return-shipback/return-shipback-model.ts`
- `apps/web/src/app/logistics/return-shipback/return-shipback-model.test.ts`（19 例）
- `apps/web/src/app/logistics/return-shipback/return-shipback-workspace.tsx`
- `apps/web/src/app/api/channel/return-shipback-allowlist.test.ts`（5 例）
- `apps/web/src/lib/api-proxy.ts` —— **只新增一条 `channel/` 元素**
  （锚点：10.1.2.2 渠道比价那条**之后**、14.1.2.2 广告类型差异那条**之前**），既有条目一字未动。

### 契约与台账

- `packages/api-contracts/src/channel.openapi.json` + `src/contracts/channel.ts`
  —— 由 `tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 重新生成，**未手改**。
- `docs/product/capability-blueprint/module-progress.md` —— 只改 `10.7.1.2` 那一行的「状态」「证据」两格。

## 踩坑记录（留给下一条车道）

- **契约里的同名 DTO 会静默覆盖**：初版把请求 DTO 命名成 `RecordCostRequest`，
  与 10.7.1.1 的同名 DTO **结构不同** ⇒ 拍平命名空间里被覆盖且**无任何警告**
  （codegen 的碰撞闸只查**跨服务**同名，同服务内不查）。已改名
  `RecordShipbackCostRequest` / `RecordShipbackDeclarationRequest` /
  `RecordShipbackArrivalRequest` / `RecordShipbackEntryRequest` / `AdvanceShipbackRequest` /
  `ShipbackCapabilityDimension` / `ShipbackCurrencyTotal`。
  **在 channel 服务里加 DTO 时，名字要带模块前缀。**
- **重新生成契约会顺带改掉别人的 schema**：`channel.openapi.json` 重新生成后，
  `CreateListingPublishJobRequest` / `BatchCandidateRequest` 的
  `minimum: 0` 变成 `0.0`（SpringDoc 序列化差异，语义相同）；
  `trade.openapi.json` 也会整体重排。**只改自己服务相关的文件，其余回退**，
  否则会给并行车道制造巨大的文本冲突面。

## 后续待办

1. **7 库存 7.4.1.1 调整单管理**（warehouse，未交付）落地后，消费本模块的
   `return_shipback_inventory_entry`（`posting_status` 恒 PENDING，`consumer_module='7.4.1.1'`）。
2. **10.7.1.3 弃置与销毁**（未交付）落地后，消费 `entry_type='SCRAP'` 的分录
   （`consumer_module='10.7.1.3'`）。
3. **12 财务**（finance 服务不存在）落地后，与本模块的退运成本 + 分摊明细对账
   （本模块**不做汇率换算**，三个币种口径分行）。
4. **13 售后分析**（未交付）在本模块事实之上自算退运比例（产品质量代理指标）。
5. **10.5.1 报关**落地授权 API / event 后，把报关资料从**快照**升级为**读取 + 对账**。
6. 侧栏 / 搜索导航登记 `/logistics/return-shipback`（本车道**刻意没改**
   `packages/ui` 的共享任务页表，避免重排他人条目，与 10.7.1.1 / 10.3.2.1 现状一致）。
