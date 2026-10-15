# 6.1.1.2 供应商详情（阶段 D6）

## 决策摘要

- **本模块落在 catalog 而不是任务书指定的 trade**。任务书的指定建立在一个被证伪的前提上：
  「6.1.1.1 在 trade，本模块跨服务读 catalog」。集成头里 6.1.1.1 整个落在 catalog
  （`V137__vendor_supply_perspective.sql` + `/catalog/supply/vendors/**`），且**零跨服务读**。
  蓝图「输入」行写的是「`supplier_id`；采购历史聚合；报价列表（03）」—— 这三个输入**全部在 catalog**。
- **为什么不为了服从服务指定而搬到 trade**：两条硬要求同时约束着这件事，而它们都指向 catalog。
  ① 硬要求「采购历史与金额」「交期与质量表现」必须由**聚合 SQL** 从事实表算出，而事实表
  （`vendor_delivery_record` / `vendor_quality_record`）归 catalog；② 把事实表复制一份到 trade
  就能在 trade 写聚合 SQL，但那正是 AGENTS.md「每张事实表只有一个写入所有者」禁止的第二个写入所有者，
  且 `V137` 第三节已经论证过到货 / 质检事实为何归 catalog（指标的定义与原料同属一个写入所有者）。
  换句话说：trade 方案只有两种落地方式 —— Java 侧加总（违反聚合 SQL 要求）或复制事实表（违反写入所有权），两者都不可接受。
- **账期与开票信息是一份可维护的数据，不是只读展示**（蓝图加粗句「缺失时 12 财务无法自动生成付款申请」）。
  它有自己的表与 PUT 端点；未建档时全景返回 `termsFound=false` + 治理警告。
- **不回写 `party_supplier_profile.payment_terms_days`**：那是 3.6.1.2 的**主体默认**账期，
  与本采购线约定可以不同（USD 预付与 EUR 月结并存的主体很常见）。全景把两个值**同时返回并分开标注**。

## 关键 trade-off

| 决策 | 放弃了什么 | 为什么值得 |
|---|---|---|
| 结算方式词表**逐字沿用 3.6.1.2**（PREPAID/ON_DELIVERY/CREDIT/LETTER_OF_CREDIT），不另造一套 | 「寄售」这类真实安排没有独立枚举值（写进 `note`） | 两个字段在同一个界面并排显示，两套词汇会让同一个词对不上号；枚举只增不改要求同源。第一版我确实造了 `PREPAY/COD/NET/CONSIGNMENT`，跑测试时被 3.6.1.2 的校验报文打回来才发现 |
| 附件资质**有效期现算不落库** | 少一列可以排序 / 建索引的状态字段 | 存一列就要一个每日任务维护，而它与读出口算出的那天差一天时「这张证有没有过期」会答错 —— 这个答案直接决定能不能下单 |
| 「新建采购单」「发起对账」**灰掉并给理由**，不造占位端点 | 界面上暂时少两个能点的按钮 | 6.3 / 6.5 未交付。为还不存在的模块造空实现端点，就是本仓库「放行不该存在的路径比 404 更糟」那个教训 |
| 附件资质**文件本体不入库**，只存对象存储 key | 需要另接存储服务才能真正看文件 | 二进制进业务库会让备份、迁移与 RLS 审计都变成搬文件的工作 |
| 采购历史数量按**单位 × 币种**分桶，跨单位不给总数 | 界面上没有一个「总数量」 | 把「3 箱 + 200 件」加成一个数在报表上看着正常、实际没有意义 |

## 字段归属（本模块对上游一个字节都不写）

| 全景里的段落 | 写入所有者 | 本模块 |
|---|---|---|
| 编码 / 名称 / 联系方式 / 主体状态 | 3.6.1.1 `party` | 只读 |
| 合作等级 / **主体默认账期** | 3.6.1.2 `party_supplier_profile` | 只读 |
| 采购侧合作状态 / 主供类目 | 6.1.1.1 `vendor_supply_profile` | 只读 |
| **账期与开票信息** | **本模块 `vendor_settlement_terms`** | **可写** |
| **附件资质** | **本模块 `vendor_qualification_attachment`** | **可写** |
| 供货 SKU 与报价 | 3.6.2.1 `supplier_quote` | 只读 |
| 采购历史 / 交期 / 质量 | 6.1.1.1 两张只追加事实表 | 只读 + 聚合 |
| 联系人 | 3.6.x `party_contact` | 只读 |

## 文件清单

| 文件 | 说明 |
|---|---|
| `backend/services/catalog/src/main/resources/db/migration/V142__vendor_detail_terms_qualifications.sql` | 两张新表 + RLS + GRANT；**零 ALTER 任何既有表** |
| `backend/services/catalog/src/main/java/com/starcon/catalog/VendorDetailModels.java` | DTO（独立成文件，类型名统一 `Vendor` 前缀） |
| `backend/services/catalog/src/main/java/com/starcon/catalog/VendorDetailRules.java` | 闭集词表 + 纯函数（资质有效期四态、税率显示） |
| `backend/services/catalog/src/main/java/com/starcon/catalog/VendorDetailService.java` | jOOQ 显式 SQL + 全部聚合 |
| `backend/services/catalog/src/main/java/com/starcon/catalog/VendorDetailController.java` | 9 个端点 |
| `backend/services/catalog/src/test/java/com/starcon/catalog/VendorDetailIntegrationTest.java` | 26 例，Testcontainers 真实 PG 16 |
| `apps/web/src/components/supply/vendor-detail-model.ts` | 前端模型 + 展示口径纯函数 |
| `apps/web/src/components/supply/vendor-detail-model.test.ts` | 17 例 vitest |
| `apps/web/src/components/supply/vendor-detail.tsx` | 七分区全景页 |
| `apps/web/src/app/supply/vendors/[roleId]/page.tsx` | 路由 |
| `apps/web/src/lib/api-proxy.ts` | **纯增量**追加 `supply/vendors/**` 放行条目 |
| `apps/web/src/components/supply/vendors.tsx` | 每行加一个指向全景的链接（6.1.1.1 已有「采购视角详情」是另一个区块） |
| `packages/api-contracts/**` | codegen 产物（catalog.ts +17 个 `Vendor*` 类型，零删除） |

## 顺带修的既有缺陷

`apps/web/src/lib/api-proxy.ts` 的 catalog allowlist **此前完全没有 `supply/vendors`**：
6.1.1.1 交付时后端端点与页面都在，但浏览器侧请求会在代理层 404（界面以为已交付、实际调不通）。
本次按纯增量方式追加这一条，并逐字列出子路径（刻意不放行 6.3 / 6.5 等未交付路径），
用 18 个正反用例核对既有条目（`parties` / `supplier-quotes` / `skus/...` 等）未被破坏。

## 后续待办

- **6.3.1.2 采购单 / 6.5.1 对账未交付**：本模块两个操作入口灰掉并写明原因。它们交付后，
  「新建采购单」应带上本模块的账期与开票信息作为付款条款来源（到期日 = 单据日 + `payment_terms_days`）。
- **6.1.1.3 供应商评级未交付**：本模块**刻意不给综合评分**。评级要按周期算加权分并单独标记人工权重，
  在本模块顺手给一个分数会让那条要求被绕过。本模块的四个聚合正是它要的原料。
- 附件资质目前只登记文件 key，**尚未接上传端点**（文件由 3.4.1.1 媒体库或对象存储服务提供）。
