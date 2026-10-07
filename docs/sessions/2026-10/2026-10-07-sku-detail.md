# 3.1.2.2 SKU 详情（D1–D2）

## 做了什么

交付能力蓝图 3.1.2.2「SKU 详情」：单个 SKU 的完整档案，分区聚合 + 编辑入口 + `version` 乐观锁。
基线 `feat/3-1-2-1-sku-list`（`a24a67a`），分支 `feat/3-1-2-2-sku-detail`。

蓝图职责逐条对应：

| 蓝图要求                            | 落地                                                             | 数据归属                                      |
| ----------------------------------- | ---------------------------------------------------------------- | --------------------------------------------- |
| 编码与规格                          | `sku` 既有列 + `variant_signature`；规格从 `variant_axis` 轴解析 | catalog                                       |
| 属性有效值（公共值 + 覆写解析结果） | `EffectiveValues` 纯函数 + 单条 join SQL                         | catalog                                       |
| 单位换算                            | `sku_unit_conversion`（版本化 + 生效区间）                       | catalog（3.1.2.4 在其上补单位字典与量纲校验） |
| 包装尺寸重量                        | `sku_package`                                                    | catalog（3.7.2.1 在其上补安全属性）           |
| 条码                                | `sku.barcode` + 外部条码归属说明                                 | catalog / 3.1.2.3                             |
| 库存分布                            | `SkuDetailProjectionPort` 未接入                                 | **07 库存**                                   |
| 平台 listing（含 seller_sku 区分）  | 同上                                                             | **04 刊登**                                   |
| 成本与售价                          | 同上                                                             | **06 采购 / 05 定价**                         |
| 各单据引用                          | 同上                                                             | **04/06/07/08/09/11**                         |
| 编辑入口 + `version`                | `PUT /catalog/skus/{id}`，CAS，冲突 409                          | catalog                                       |

## 关键 trade-off

### 1. 有效值解析：为什么不是一条 SQL COALESCE

任务要求优先 SQL COALESCE。**这里刻意没这么做**，因为 COALESCE 分不开三件事：

- **赋值**（SET）→ 取 SKU 的值
- **显式清空**（CLEAR）→ 有效值是空，且**不退回公共值**
- **根本没有这一行**（INHERIT）→ 才允许继承公共值

`COALESCE(sku_value, common_value)` 会让「SKU 清空了某个公共属性」悄悄变回公共值——
操作人以为清掉了，详情页却还显示着公共值，而且**无法自查**。

所以拆成两层：SQL 用两个 `LEFT JOIN` 一次取齐「定义 + 公共赋值 + SKU 赋值」（取值明细用
标量子查询 `array_agg` 聚成数组，避免 N+1），再交给纯函数 `EffectiveValues.resolve` 判优先级。
纯函数无 DB 依赖，四种 `value_mode` × 三种 SKU 侧状态穷举成 11 个单测。

**复用而非各写一份**：`CatalogService.detail()` 原来内联了一套同样的判定，已改成调用同一个
`EffectiveValues`。两处对「有效值」的理解漂移过一次就会产生用户无法理解的显示结果。
副作用是 `EffectiveValue.source` 的 code 从 `SKU/SPU/NONE` 变成
`SKU_OVERRIDE/SKU_CLEARED/SPU_COMMON/UNSET`，`catalog-workspace.tsx` 的 SKU 矩阵已同步。

**单测抓到一个真 bug**：`COMMON_LOCKED` 原本和 `SKU_ONLY` 一起短路成了「未赋值」，
于是公共固定属性即使有公共值也显示成没值。已修正——`SKU_ONLY` 才是不兜底的那个。

### 2. V1 的属性表结构已足够，没有补迁移

任务说「结构不足才补」。实际 `attribute_value(sku_id NULL, state SET|CLEAR)` +
`UNIQUE NULLS NOT DISTINCT(tenant_id,attribute_id,sku_id)` + `validate_value_owner` 触发器
**已经完整表达覆写语义**，所以 V6 只加包装/换算两张新表和详情页索引，没有动 V1。

V6 的 `sku_unit_conversion` 带一个生效区间不重叠触发器：同一 SKU 的同一单位同一角色，
同一时刻只能有一个生效版本。否则详情页面对两条「当前有效」只能随便挑一条——蓝图文案对多条有效报价
的口径是「必须报错让用户选」，这里对换算版本用同一条纪律。

### 3. 库存/成本/售价/listing/单据引用一列都不落库

沿用 3.1.2.1 的口径并扩到详情聚合：新增 `SkuDetailProjectionPort`（分区级 `available` 标记），
未接入时**整区写「未接入」+ 归属服务**。不落本地列的理由同上——落了就成第二个事实来源。

`seller_sku` 是**平台属性不是内部编码**（3.1.2.3 口径），所以 listing 区与条码区都写明这条，
且 catalog 不会反向写 `sku.code`。

### 4. `product_asset` 的结构缺陷（已知阻塞模块，本模块不修表）

`product_asset` 是 `LIKE product_research INCLUDING ALL` 的同构复制，**三处**缺陷：

1. **没有文件键**（存不了对象存储 key）→ 详情页画不出缩略图
2. **没有 `sku_id`** → 只能落到 SPU 级，无法精确到 SKU
3. 没有媒体类型 / 尺寸 / 格式列

处置：**只读它现有的可用列**（title / notes / reference / status / locale / target_country /
expires_on），并把缺陷写进响应的 `mediaLimitation` 返回、在界面上原样显示，指向 3.4.1.1 的
专门修表任务。本模块**没有**往这张错表上加字段。

**权限边界**：`product_asset` 的 data_scope 策略要求 `app.permission` 形如 `catalog.assets.%`（V3），
而详情接口跑在 `catalog.sku.read` 下。媒体分区因此用**同一个访问者**单独开一段
`catalog.assets.read` 租户事务去读；没有该权限时整区标 `available=false` + 说明原因，
**不回空数组**（空数组会被读成「这个 SKU 没有任何素材」）。

### 5. 编辑入口只覆盖本模块真正拥有的字段

`PUT /catalog/skus/{id}` 只编辑 名称 / 条码 / 基础单位 / 包装尺寸重量。属性覆写属 3.2.2.2、
编码规则与外部条码属 3.1.2.3、单位换算版本维护属 3.1.2.4，**都不在这里代劳**——
详情页的编辑按钮不打开别人模块的字段，免得改出跨模块的责任边界。

乐观锁用 `update ... where id=? and version=?` 的 CAS，不做「先查后写」：后者两个并发编辑会双双通过
校验然后互相覆盖。冲突返回 409，界面单独提示「已被他人修改，请刷新」。

包装全空则**删掉**那一行，让「没有维护包装」在数据上是真的没有，而不是留一行全 null 装作维护过。

### 6. 跨租户返回 404 而不是 403

RLS 已经过滤掉跨租户的行，再用 403 去区分「存在但没权限」等于给探测者一个存在性预言机。
跨租户读、写统一 404，与 3.1.2.1 的批量越权处置一致。

## 文件清单

**迁移**

- `backend/services/catalog/src/main/resources/db/migration/V6__sku_detail.sql`

**后端**

- `backend/services/catalog/src/main/java/com/starcon/catalog/EffectiveValues.java`（新增，纯函数）
- `.../SkuDetailService.java`（新增）
- `.../SkuDetailController.java`（新增）
- `.../SkuDetailProjectionPort.java` + `.../UnwiredSkuDetailProjectionPort.java`（新增）
- `.../CatalogModels.java`（新增 3.1.2.2 DTO）
- `.../CatalogService.java`（`detail()` 改为复用 `EffectiveValues`）

**测试**

- `backend/services/catalog/src/test/java/com/starcon/catalog/EffectiveValuesTest.java`（11 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/SkuDetailIntegrationTest.java`（22 例）

**前端**

- `apps/web/src/components/catalog/sku-detail-model.ts` + `.test.ts`（9 例）
- `apps/web/src/components/catalog/sku-detail-workspace.tsx`
- `apps/web/src/app/products/sku/[id]/page.tsx`
- `apps/web/src/components/catalog/sku-list-workspace.tsx`（列表编码点进详情）
- `apps/web/src/components/catalog/catalog-workspace.tsx`（有效值来源 code 同步）

**契约 / 文档**

- `packages/api-contracts/src/catalog.ts` + `catalog.openapi.json`（codegen，11 个新 DTO）
- `docs/product/capability-blueprint/module-progress.md`（3.1.2.2 一行）

## 验证

```
cd backend && ./mvnw verify          # BUILD SUCCESS，35 + 72 + 66 = 173 例
pnpm typecheck && pnpm lint && pnpm test   # 117 例
NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build
node tools/module-progress/sync.mjs --check
```

契约生成必须带 `--spec-dir`：`pnpm contracts:generate` 不带参数会打到 live 端口拿到旧 spec。

## 后续待办

- **3.4.1.1 媒体库**：`product_asset` 修表（补文件键、`sku_id`、媒体类型/尺寸/格式）。修好之前
  详情页媒体区只能给 SPU 级资料元数据，画不出缩略图。
- **3.7.1.1 HS 编码**：`product_compliance` 同构复制缺陷，未动。
- **3.2.2.2 SKU 属性覆写**：详情页目前只读有效值，覆写编辑入口尚未开放（等本模块）。
- **3.1.2.3 编码与条码**：`sku_identifier`（scheme+value 唯一）与编码规则；详情页条码区已标注归属。
- **3.1.2.4 单位与换算**：在 `sku_unit_conversion` 之上补单位字典（3.3.3）、量纲校验、
  采购/销售/基础三角色区分与单据冻结快照。本模块只做到「有效换算版本可判定」。
- **3.7.2.1 物理与安全属性**：在 `sku_package` 之上补是否带电/液体/危险品/磁性。
- **07 / 05 / 06 / 04**：落地后替换 `UnwiredSkuDetailProjectionPort` 与 `UnwiredSkuProjectionPort`
  两个 Bean 即可，详情分区结构与有效值解析无需改动。
