# 3.1.2.3 编码与条码（D1–D2）

## 做了什么

交付能力蓝图 3.1.2.3「编码与条码」：内部 SKU 编码规则与三种编码方式，以及外部条码
`sku_identifier` 与**冲突拒绝清单**。
基线 `feat/3-1-2-2-sku-detail`（`ade50f9`），分支 `feat/3-1-2-3-sku-code-barcode`。

蓝图职责逐条对应：

| 蓝图要求                      | 落地                                                           |
| ----------------------------- | -------------------------------------------------------------- |
| 手工输入内部编码              | `PUT /catalog/sku-code/skus/{id}/code`，version 乐观锁         |
| 按规则自动生成                | `POST /catalog/sku-code/batch` action=`GENERATE`（只填空编码） |
| 批量补齐                      | `POST /catalog/sku-code/batch` action=`BACKFILL`（规范化补齐） |
| 编码规则定义                  | `sku_code_rule` + `sku_code_rule_seq`；CRUD 走 `/rules`        |
| 外部条码（scheme+value 唯一） | `sku_identifier`，`UNIQUE(tenant_id,scheme,value)`             |
| 条码批量导入 / 扫码录入       | `POST .../identifiers/import` 与 `POST .../identifiers`        |
| **唯一性冲突拒绝清单**        | `sku_code_batch_item` 逐行留痕 + 响应逐行返回                  |

## 关键 trade-off

### 1. 「批量补齐」的目标集合：不是「填空编码」

`sku` 上有 V1 带来的 `UNIQUE(tenant_id,code)`，**一个租户最多只能有一行空编码**。
所以「批量补齐 = 批量填空」在当前 schema 下最多命中一行，名不副实。

真正的编码治理场景是「一批历史/手工编码统一规范化到公司规则」。因此：

- `GENERATE`：只给**空编码**赋码，已有编码的 `SKIPPED` 并提示改走补齐；
- `BACKFILL`：扫规则范围内「**尚未按本规则赋码**」的 SKU（空编码，或最近一次留痕来自别的规则/手工），
  允许覆盖旧编码，**逐行留 `from_code`**，且强制预览先行，重复执行幂等。

代价：BACKFILL 会覆盖既有编码。用预览 + 留痕 + 幂等换掉"批量补齐"这个动作的实用性，
这是本模块最需要被 review 的一条设计决定。

### 2. `sku.barcode` 用触发器同步，不做应用层双写

`sku.barcode` 是 3.1.2.1 之前就有的单个字符串位（NOT NULL DEFAULT ''），主条码上 sku_identifier 后
需要同步过去。选择触发器而非应用层双写：双写迟早因某条路径漏调而让两处不一致，而条码不一致在
**08 拣货扫码现场**表现为「扫出来是另一个 SKU」——是会真的打错货的故障。
删除主条码时同样由触发器清空，避免留下一个已经不存在的条码。

### 3. 拒绝清单里看不见的 SKU，落库时 `sku_id` 置空

`sku_code_batch_item` 的 data_scope 策略不允许写指向不可见 SKU 的行（RLS 会直接拒绝整条插入）。
若把提交来的、当前角色看不见的 SKU ID 原样落库，不仅 RLS 过不去，把别人的 ID 存进自己的表
本身也不该做。因此：**响应里回提交者给的 ID**（操作人才知道改哪一行），
**落库时置空**。与 3.1.2.1 `SkuListService` 的同一条纪律。

### 4. 序号放独立表而不是规则行

`sku_code_rule_seq` 让「发一段号」这一个动作能单独加锁，规则改名/停用不必与批量生成互相阻塞。
发号用 `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` 一条语句完成「读—加—写」，
并发两批不会拿到同一段号；`next_value` 语义是「下一个尚未发出的序号」，首次分配走
`start_from + count` 插入，与后续 UPDATE 分支语义一致。

### 5. `seller_sku` 不进本模块

蓝图明确：店铺 `seller_sku` 是平台属性、映射由 04 刊登维护。混进内部编码会让「内部唯一条码」
这条约束被平台编号污染。因此 scheme 词表里没有它，`SkuCodeOverview.sellerSkuNote`
与界面上都常驻说明这一点（单测里也钉住了 `SELLER_SKU` 不在词表内）。

## 过程中修掉的两个真 bug

- **`BarcodeScheme.expectedCheckDigit` 权重错位**：原本拿**含校验位**的整串算权重，整体错位一格；
  又把 `char` 与 `char - '0'`（int）比较，等于拿 ASCII 码比数字值，结果**所有条码都被误判校验位错**。
  是纯函数单测钉出来的。改法：入参改为**数据位**，右起权重 3,1,3,1…。
- **`allocate` 差一**：`next_value` 推进 `count` 后，刚发出的号段是
  `[next - count, next - 1]`，起点应是 `next - count`；原先写成 `next - count + 1`，
  导致规则 `start_from=1` 的**第一次发号拿到 2**。

## 文件清单

**迁移**

- `backend/services/catalog/src/main/resources/db/migration/V7__sku_code_barcode.sql`

**后端**

- `.../catalog/SkuCodePattern.java`（编码展开纯规则，5 例单测）
- `.../catalog/BarcodeScheme.java`（scheme 与 GTIN mod-10 校验，9 例单测）
- `.../catalog/SkuCodeService.java`
- `.../catalog/SkuCodeController.java`
- `.../catalog/CatalogModels.java`（追加 3.1.2.3 一段 DTO）

**前端**

- `apps/web/src/components/catalog/sku-code-model.ts`（19 例单测）
- `apps/web/src/components/catalog/sku-code-workspace.tsx`
- `apps/web/src/app/products/sku/code/page.tsx`
- `apps/web/src/components/catalog/sku-list-workspace.tsx`（加一个入口链接）

**测试**

- `.../catalog/SkuCodeIntegrationTest.java`（28 例）

## 验证

`./mvnw verify`（backend 全量 215 例）、`pnpm typecheck`、`pnpm lint`、
`pnpm test`（136 例）、`NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build`、
`node tools/module-progress/sync.mjs --check` 全绿。

## 后续待办

- **3.1.2.4 单位与换算**：在 `sku_unit_conversion` 之上补单位字典（3.3.3）、量纲校验、
  采购/销售/基础三角色区分与单据冻结快照。
- **3.2.2.2 SKU 属性覆写**：3.1.2.2 详情页目前只读有效值，覆写编辑入口尚未开放。
- **3.1.2.2 的条码区**：详情页 `SkuBarcode` 仍是单值位 + 归属说明文案，文案里写着
  「3.1.2.3 尚未开发」。本模块落地后这段话已经不准确，应改为直接展示 sku_identifier 的多 scheme 条码
  并给出跳转到编码与条码工作区的入口。**本轮未改**（超出本模块改动面，留给该模块自己收口）。
- **条码粘贴导入**目前第一列只接受 SKU ID：catalog 没有按 code 检索 SKU 的端点，
  供应商表格里的业务编码列无法解析。待 SKU 检索能力具备后再放宽。
