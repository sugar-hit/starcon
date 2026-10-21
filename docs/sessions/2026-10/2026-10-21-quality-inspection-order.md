# 2026-10-21 6.4.2.1 质检单（卷 06 采购与供应 / L3 锚点 6.4.2 来料质检）

## 决策摘要

- **归属 trade 服务**，后端命名空间 `/trade/quality-inspections`，前端路由 `/purchase/quality`
  （蓝图 06 卷 L2 表「6.4 加工与质检 → trade + warehouse」，列表第一项是 trade；同卷的
  6.4.1.1 / 6.4.1.2 / 6.2.1.3 都在 trade）。
- **命名撞车是本车道最大的坑**：trade 服务**已**有一整套 `QualityInspection*`
  （11.x 的**退货质检** `/trade/return-quality`，V179 八张 `return_quality_*` 表）。
  springdoc 用 record 的**简单类名**做 `components.schemas` 的键，重名会**静默覆盖** ——
  别人的字段形状被改掉、单测全绿、只有跨服务契约比对才红。
  ⇒ 本模块的 record / enum 一律以 `IncomingQualityInspection` 开头（蓝图 L3 锚点 6.4.2 的
  名字就叫「来料质检」）；表名前缀 `quality_inspection_*` 与 `return_quality_*` 也完全分开。
- **三量的口径定成「本次质检覆盖的批量」而不是「到货量」**：批量 `lot_size ≤ arrived_quantity`，
  三量和的基数是 `lot_size`。这是卷 06 加粗句「采购收货是分批、可有差异的」的直接落点 ——
  同一张收货单可以分几批来检，每次质检只覆盖其中一批。
- **到货量来自上游，不来自客户端**：读 8 收货的授权只读接口拿到实收量后写库；客户端只能指定
  「本次检哪一批」。
- **抽样由服务端算**：比例 × 批量 → 夹逼 [最小, 最大] → 按批量封顶 → 按舍入口径取整，
  算式原文写进 `sampling_basis`。比例是 **0~1 的比率**，不是百分数（写成 10 会算出 1000 倍）。
- **让步接收的三道闸**：原因与权限码 DB 层 CHECK 必填；服务层**真的校验记录人持有那个权限码**
  （只写一个码不算放行）；让步记录 append-only。
- **库存分流**如实标 `available=false` + `ownerModule` + 影响说明，**不假装已分流**。
  warehouse 侧唯一的质量状态写入口是 8.2.1.2 的**差异处置决定**，它必须挂在一条真实的收货差异上，
  不能拿来给一整批正常货做分流 ⇒ 本模块只落分流**意图**，`disposition_status` 标 `UNAVAILABLE`。

## 关键 trade-off

1. **逐项结论的「扣量」由质检员按批次单位录入，而不是由抽检不合格率自动折算。**
   折算要回答「同一件在外观和包装上都不合格算几件」，没有唯一答案；录入则每个数字都可追溯到
   一次具体判定。代价是录入负担 —— 用「应抽数量服务端算 + 缺项不许完结 + 关键项不合格按模板
   整批拒收」三道闸来兜。
2. **关键检验项不合格是否整批拒收做成模板上的规则**（`critical_failure_action`:
   REJECT_LOT / DEDUCT_ONLY），不是服务层的隐藏假设 —— 租户可以自己选，两种都有测试覆盖。
3. **新建 `QualityInspectionWarehousePort` 而不复用既有的 `WarehouseReceiptQueryPort`**：
   后者是 6.3.2.2 到货跟踪的文件，只读收货单头；本模块要的是**明细行**（哪个 SKU 到了多少）。
   改别人的文件会跨车道撞车，新开一个 port 代价是少量重复，但车道隔离干净。
4. **catalog 契约文件 revert 掉了**：重新跑 codegen 会带上「codegen 头部注释位置」的历史格式漂移
   （与本模块无关）。本车道只留 trade 契约的纯新增。

## 文件清单

### 后端（trade）

- `backend/services/trade/src/main/resources/db/migration/V249__quality_inspection_order.sql`
  —— 五张新表 + RLS（ENABLE **FORCE**）+ 索引 + GRANT/REVOKE + 4 个护栏触发器。零 ALTER 既有表。
- `backend/services/trade/src/main/java/com/starcon/trade/QualityInspectionModels.java` —— 传输模型（`Incoming*` 前缀）
- `backend/services/trade/src/main/java/com/starcon/trade/QualityInspectionRules.java` —— 抽样与三量算法
- `backend/services/trade/src/main/java/com/starcon/trade/QualityInspectionService.java` —— 服务层（jOOQ 显式 SQL）
- `backend/services/trade/src/main/java/com/starcon/trade/QualityInspectionController.java` —— 14 个端点
- `backend/services/trade/src/main/java/com/starcon/trade/QualityInspectionWarehousePort.java` —— 读 8 收货（授权只读）
- `backend/services/trade/src/main/java/com/starcon/trade/QualityInspectionCatalogPort.java` —— 读 3.7.2.1 安全属性（授权只读）

### 测试

- `backend/services/trade/src/test/java/com/starcon/trade/QualityInspectionIntegrationTest.java`（34 例）
- `backend/services/trade/src/test/java/com/starcon/trade/QualityInspectionTenancyIntegrationTest.java`（4 例）

### 前端

- `apps/web/src/app/purchase/quality/page.tsx`
- `apps/web/src/components/purchase/quality-inspection-model.ts` + `.test.ts`
- `apps/web/src/components/purchase/quality-inspection-workspace.tsx`
- `apps/web/src/app/api/trade/quality-inspection-allowlist.test.ts`
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**（既有条目一字未动）

### 契约 / 台账

- `packages/api-contracts/src/contracts/trade.ts`、`packages/api-contracts/src/trade.openapi.json`
  —— codegen 产物（+21 DTO，纯新增）
- `docs/product/capability-blueprint/module-progress.md` —— 只改 6.4.2.1 那一行的「状态」「证据」两格

## 过程中被测试逼出来的真实缺陷（已修）

1. **INSERT 的占位符与参数错位**：模板与质检单两条 INSERT 最初写成一长串 `?::varchar`，
   多一个少一个 jOOQ 要么报「参数个数不匹配」（落成 409），要么**数量正好但顺序错了** ——
   值绑到别的列上而 SQL 不报错。⇒ 改成**列 / 占位符 / 参数三份平行数组** + 长度断言，
   错位当场炸而不是落库落歪。
2. **`List.of` 不接受 null 元素**：可空列（`category_id` 等）用 `List.of` 装参数直接 NPE。
   ⇒ 值列表改用 `Arrays.asList`。
3. **UPDATE 的时间列漏了 `?::timestamptz`**：insert 写了、update 忘了 —— PG 直接拒。
   ⇒ 全部 UPDATE 的时间列补上 `::timestamptz`（与兄弟服务同一写法）。
4. **客户端塞 `sampleQuantity` 会被静默忽略还是被拒？** trade 的 ObjectMapper 开了
   `fail-on-unknown-properties: true` ⇒ 直接 400 且零落库。测试断言就按这个写 ——
   「抽样数进不来」比「进了但被忽略」更硬。

## 后续待办

- **库存分流接线**：warehouse 需要提供一个「按质检结论把货登记到待检 / 残次 bucket」的授权写端点；
  有了之后本模块的 `disposition_status` 才能从 `UNAVAILABLE` 变成 `DISPATCHED`。
  （ownerModule：7.1.1.2 库存明细 / 8.2.1.2 收货差异处理。）
- **6.4.2.2 不良品处理**（退换货补货 / 返修 / 报废 / 转促销品）未交付：它要消费本模块的
  「不合格明细」与让步记录，并发出库存状态变更指令与供应商索赔记录。
- **6.1.1.3 评级**未交付：它要消费本模块的合格率与扣量（评级必须由客观数据算出）。
- **6.5.1 采购对账**未交付：它要消费本模块的质检扣量与扣量金额。
- **PURCHASE 来源的收货单**：warehouse 8.2.1.1 当前不接受 `PURCHASE` 来源（6.3 采购单未交付），
  来料质检目前只能挂在已存在的收货单上；6.3.1 采购单交付后这条限制才解除。
- **identity 的 page_resource**：trade 的页面权限码（`trade.quality-inspection.*`）在本仓没有像
  channel/warehouse 那样注册 page_resource —— trade 全服务的既有缺口，本车道沿用兄弟模块做法
  **不改 identity**（避免与其他车道撞迁移号）。
