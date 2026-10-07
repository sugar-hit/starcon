# 3.2.2.2 SKU 属性覆写（D1–D2）

## 做了什么

交付能力蓝图 3.2.2.2「SKU 属性覆写」：对单个 SKU 覆写 SPU 公共属性值、显式清空、取消覆写，
并给出解析后的**最终有效值**与来源标注。

基线 `feat/3-2-1-2-attribute-option-management`（`16c616e`），分支 `feat/3-2-2-2-sku-attribute-override`。

蓝图职责逐条对应：

| 蓝图要求                          | 落地                                                                                      |
| --------------------------------- | ----------------------------------------------------------------------------------------- |
| 覆写 SPU 公共属性值               | `PUT /catalog/sku-attribute-override/skus/{skuId}/attribute-overrides/{attributeId}`      |
| **显式清空**（区别于「未设置」）  | `PUT .../attribute-overrides/{attributeId}/clear`，写一行 `state='CLEAR'`                 |
| 取消覆写（回落公共值）            | `DELETE .../attribute-overrides/{attributeId}`，幂等                                      |
| 展示解析后的最终有效值 + 来源标注 | `GET /catalog/sku-attribute-override/skus/{skuId}` → `SkuOverrideView`，逐属性带 `source` |
| 缺失必填项时阻止保存              | 整 SKU 范围校验，返回 400 + ProblemDetail 的 `missing` 结构化清单                         |

## 关键 trade-off

### 1. 覆写行复用 V1 的 `attribute_value`，**不新建覆写表**

任务书建议新建 `V13__sku_attribute_override.sql` 含新表。**没有新建**，因为 V1 已经把这个模型建好了：

- `attribute_value.sku_id` 可空：`NULL` = SPU 公共侧那一行，非空 = 某个 SKU 的覆写行；
- `UNIQUE NULLS NOT DISTINCT(tenant_id,attribute_id,sku_id)` 保证两侧各自唯一；
- `state CHECK(state IN ('SET','CLEAR'))` 就是「赋值 / 显式清空」两个动作。

另建一张 `sku_attribute_override` 会让同一个 SKU 的属性值可能落在两张表里——直接违反
「每张事实表只有一个写入所有者」，也会让 3.1.2.2 详情页那条**已交付**的解析查询漏读一半数据。
V12（3.2.1.2）也是同一思路：只加索引，不建表，并写下留给下游的口子。

因此 V13 只做三件事：`attribute_value` 加 `updated_at`（覆写记录的系统时间）、
`sku_attribute_override_lookup` 部分索引（SKU 侧读路径，原唯一约束按 `attribute_id` 打头，用不上）、
RLS/GRANT 复核（表与策略 V1 已建，这里显式写一遍而不是靠推断）。

**连带必须改的代码**：`CatalogService.writeValue` 里 `insert into attribute_value values(?,?,?,?,?,?)`
是位置化插入，加列后会直接报错——已改成显式列名，与 V11 里 `attribute_option` 同一个坑。

### 2. 「显式清空」与「未设置」：靠**这一行在不在**区分，不靠 NULL

| source         | SKU 侧那一行                 | 有效值               |
| -------------- | ---------------------------- | -------------------- |
| `SKU_OVERRIDE` | `state='SET'` + 至少一条取值 | 覆写值               |
| `SKU_CLEARED`  | `state='CLEAR'`，**无**取值  | 空，**不退回公共值** |
| `SPU_COMMON`   | 没有这一行（`INHERIT`）      | 公共值               |
| `UNSET`        | 没有这一行，公共侧也没有值   | 空                   |

API 层把 `overrideState`（操作事实：SET/CLEAR/INHERIT）与 `source`（生效值来源）**分开返回**，
因为"覆写了一个与公共值相同的值"时两者不同：那是 `SKU_OVERRIDE`，但语义上等于没改。

### 3. 类目必填校验对「显式清空」的判定：**同样拒绝**

必填只看**解析后的有效值**，不看来源：`UNSET` 与 `SKU_CLEARED` 的有效值都是空，因此对必填属性
两者一律拒绝。理由：

- 类目规则里 `required=true` 的含义是「这个类目下的商品必须有这条属性」。显式清空恰恰是
  「这个 SKU 确实没有该属性」的主动断言，放行它等于让必填规则能被最刻意的那一个操作绕过。
- 所以「显式清空」的价值落在**非必填**属性上：容量、保质期这类只对部分变体成立的参数，
  正是需要"这个 SKU 没有"而不是"忘了填"的地方。
- 已交付的 3.1.2.2 详情页文案本来就是这个口径（「清空一个必填属性同样会进补全清单」）。

必填口径 = 属性定义自身 `required` **或** `category_attribute_rule` 里该类目要求必填（V11）。
校验是**整 SKU 范围**的：只要还有必填属性没有生效值，任何一次覆写/清空/取消覆写都被拒。

**写后校验而非预演**：先写再校验，校验在事务内完成，抛异常回滚。代价是多解析一次，
换来的是不必把解析规则复制一份去做"预演"——预演与真实写入两套逻辑长期必然漂移。

### 4. 三个写动作是三个端点，不是一个带 state 字段的 PUT

把清空塞进 `state` 字段会留下一个真实的坑：界面只要传了空数组，就从「取消覆写」变成「显式清空」，
解析结果从公共值变成空——两个方向相反的结果，一个手滑就能触发。清空是有后果的动作，
必须在 URL 上就选出来。

### 5. 端点按 SKU 寻址，不带商品 id

任务书建议 `/catalog/products/{id}/skus/{sid}/...`。实际取 `/catalog/sku-attribute-override/skus/{id}`，
与 `SkuCodeController`（`/catalog/sku-code/skus/{id}`）、`UnitConversionController`
（`/catalog/unit-conversion/skus/{id}`）同形——那两个是仓库里最近交付的同类模块。理由有两条：

1. SKU 的归属本来就唯一，要求调用方再传一遍商品 id 只会造出「路径与 SKU 不匹配」这种自相矛盾的调用；
2. 前端按 SKU 寻址就**不必**先调 `catalog/skus/{id}` 拿 SPU id——那条路径目前不在 `api-proxy`
   allowlist 里（`docs/backlog/2026-10/2026-10-07-sku-code-api-proxy-allowlist.md` 记的 P0 同类问题），
   依赖它等于把另一个模块的已知缺口接进自己的链路。

### 6. 复用 SPU 侧校验，不另写一套

`CatalogService.validateValues`（原 private）与 `writeValue`（原 private）改为包可见，
`event`（原 private）同样。类型校验（OPTION/DECIMAL/BOOLEAN/DATE、多值上限、数值精度、日期格式）
与写入路径因此与已交付的 3.2.2.1 / 3.1.2.x **逐字同源**。

本模块额外加一条已交付路径没有的规则：枚举取值必须**处于启用状态**。3.2.1.1 的「停用只挡住新建使用」
到这里才真正生效——停用只挡 `set`，`clear` 与取消覆写仍然允许，否则「改不动」会变成「永远清不掉」。

### 7. 不推进 `sku.version`

`sku` 的写入所有者是 3.1.2.x，`sku.version` 是那些模块的乐观锁凭据。本模块改一个属性值就顶一次版本号，
会让并发编辑者在 SKU 资料、编码、包装这些无关区域也全部撞上版本冲突。覆写行的并发控制交给
`attribute_value` 的唯一约束（同一 SKU 的同一属性不可能有两行覆写）+ 覆盖写。
覆写记录自带 `updated_at`（系统时间），界面上能回答"这次覆写是什么时候写的"；
业务事件留痕仍在 `audit_event`（`SkuAttributeOverridden` / `SkuAttributeCleared` /
`SkuAttributeOverrideRemoved`），aggregate 仍是 SPU。

## 文件清单

**迁移**

- `backend/services/catalog/src/main/resources/db/migration/V13__sku_attribute_override.sql`（新建）

**后端**

- `backend/services/catalog/src/main/java/com/starcon/catalog/SkuAttributeOverrideService.java`（新建）
- `backend/services/catalog/src/main/java/com/starcon/catalog/SkuAttributeOverrideController.java`（新建）
- `backend/services/catalog/src/main/java/com/starcon/catalog/CatalogModels.java`（追加 3.2.2.2 的 6 个 record）
- `backend/services/catalog/src/main/java/com/starcon/catalog/ApiErrorHandler.java`（`RequiredMissingException` → 400 + `missing`）
- `backend/services/catalog/src/main/java/com/starcon/catalog/CatalogService.java`（`validateValues` / `writeValue` / `event` 改包可见；`writeValue` 插入语句改显式列名）

**契约（codegen 产物，未手改）**

- `packages/api-contracts/src/catalog.ts`（+4 个 DTO）
- `packages/api-contracts/src/catalog.openapi.json`（+3 条路径）

**前端**

- `apps/web/src/components/catalog/sku-attribute-override-model.ts`（新建）
- `apps/web/src/components/catalog/sku-attribute-override-workspace.tsx`（新建）
- `apps/web/src/components/catalog/sku-attribute-override-model.test.ts`（新建，14 例）
- `apps/web/src/app/products/sku/[id]/attributes/page.tsx`（新建）
- `apps/web/src/app/api/catalog/[...path]/route.test.ts`（allowlist 放行用例，+2 例）
- `apps/web/src/lib/api-proxy.ts`（catalog allowlist 新增 `sku-attribute-override` 分支）
- `apps/web/src/components/catalog/sku-detail-workspace.tsx`（属性区加「去覆写属性」入口）

**测试**

- `backend/services/catalog/src/test/java/com/starcon/catalog/SkuAttributeOverrideIntegrationTest.java`（新建，14 例）

**台账**

- `docs/product/capability-blueprint/module-progress.md`（3.2.2.2 → 已交付）

## 后续待办

- **类目规则目前只能挂字典条目**：`AttributeDictionaryService.saveCategoryRule` 走 `dictionaryRow`，
  要求 `spu_id is null`，所以界面上给不了 SPU 级属性建必填规则（而字典条目因为
  `attribute_value` 的复合外键锁在 SPU 作用域，又暂时拿不到属性值）。
  本模块的必填校验读的是 `category_attribute_rule` 全表，FK 与 RLS 都允许指向 SPU 级定义，
  所以一旦 3.3.1.2 类目取值规则落地（或放开 3.2.1.1 的守卫），校验立即生效。
  测试里用 SQL 直插建规则，原因与边界写在 `categoryRule()` 的注释里，不是测试后门。
- **字典条目仍不能被赋属性值**：`attribute_value` 的复合外键仍锁在 SPU 作用域，
  把类目配置与商品属性打通属于另一个改动，不在本模块。
- **`api-proxy` 的 `sku-code` / `skus/{id}` 缺口**：属已交付模块的 P0 backlog，本模块没有依赖它们，
  也没有越界修改。
