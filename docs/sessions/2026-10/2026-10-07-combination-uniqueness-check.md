# 3.1.3.2 组合唯一性校验（阶段 D1–D2）

- 分支：`feat/3-1-3-2-combination-uniqueness`（基线 `feat/3-1-3-1-spec-axis-definition` = `aa609db`）
- 迁移：V10　端点：`/catalog/variant-combination/**`　页面：`/products/sku/variant-combination`

## 决策摘要

1. **组合的唯一性作用域定为 `(tenant_id, spu_id, signature)`**，signature 是「轴编码升序 + `编码=取值` + `|` 连接」的可读文本，
   落成 `spu_variant_combination` 的 UNIQUE 约束。蓝图那句「唯一性约束在数据库层也要有，应用层校验只用于友好提示」
   就落在这条唯一索引上，应用层每一次比对都只是提前说破。
   - 选**可读文本**而不是哈希：冲突清单要能直接念给人听（"COLOR=红色|SIZE=大 撞了已有 SKU"）。
   - 排序键用**轴编码**而不是提交顺序：否则唯一性只在"按固定顺序提交"时成立。
   - 分隔符 `=` / `|` 不可能出现在取值里——V9 的取值字符集是 `[\p{IsHan}A-Za-z0-9 _\-./]`。
   - 两端同序：Java `String.compareTo` 与 DB `ORDER BY axis_code COLLATE "C"` 对 `[A-Z0-9_-]` 纯 ASCII 等价，
     且 V1 的 `attribute_definition(tenant_id,spu_id,code)` 唯一 + V9 强制「轴码=承载属性码」保证同一 SPU 上轴码不重复，
     所以"按轴编码排序"是全序。V10 的延迟约束触发器会在提交前用同一格式重算签名并核对。

2. **占位与兑现分两层，本模块只做到占位。** `spu_variant_combination` 是「组合已被占用」的台账，
   `sku_matrix_draft(_item)` 是"待建 SKU 草稿"。**不建 `sku` 行**——真正落 SKU 是下游 3.1.2.1 的职责
   （它同时要按 V7 编码方案分配 code 与 barcode）。作废草稿 = 物理删除占用行；留一行"已释放"会继续占着唯一索引。

3. **冲突输出结构化，不压成布尔值。** `CombinationConflict` 带 `reasonCode / signature / occurrenceIndexes /
existingSkuId / existingDraftId`，服务端在 409 的 ProblemDetail 上挂 `conflicts`，前端 `ApiError` 新增 `body` 字段承接。
   十类 reasonCode：批次内重复 / 已被草稿占用 / 与既有 SKU 相同 / 组合不完整 / 空组合 / 同组合内轴重复 /
   轴不属于本 SPU / 轴停用 / 取值不属该轴 / 取值停用（另有取值不合法、轴数超限、轴编码缺失、SPU 未绑轴）。

4. **生成草稿全有或全无。** 任一条冲突即整份不生成。草稿是"可以照着建 SKU"的交付物，半份会让下游不知道该不该建；
   宁可把完整清单退回让人改。面板给出「矩阵共 N / 已占 M / 还可生成 K」三个数，让人自己先缩小范围。

5. **矩阵规模超限按真实行数拒绝，不静默截断**（上限 2000）。截断出来的草稿会让人以为矩阵就那么多。

6. **既有 SKU 的组合在查询时实时解析，不做回填。** 按 `attribute_value_item` 上的 variant_axis 取值重算签名（精确比对）；
   解析不出轴值的历史数据退到 `variant_signature` 文本逐字比对，并标 `source=SKU_SIGNATURE` 让界面说清"没比到取值"。
   读路径不做写，因此没有"回填那一刻之前"的漏检窗口。

## DB 层硬约束（绕过应用层仍生效）

| 约束                                                                         | 挡住的洞                                                      |
| ---------------------------------------------------------------------------- | ------------------------------------------------------------- |
| `UNIQUE(tenant_id,spu_id,signature)`                                         | 同 SPU 重复组合（本体）                                       |
| `guard_variant_combination_against_sku`（BEFORE INSERT，23505）              | 占用表与 `sku` 表同签名并存——V1 的唯一约束只管 sku 表内部     |
| `guard_variant_combination_lines`（DEFERRABLE INITIALLY DEFERRED）           | `signature` 自报、与真实轴值不符；`axis_count` 与明细行数不符 |
| `guard_variant_combination_value`（BEFORE INSERT/UPDATE，23514）             | 轴不属于本 SPU、轴或取值已停用、`axis_code` 自报              |
| `spu_variant_combination_value` PK 含 `spec_axis_id` + UNIQUE 含 `axis_code` | 一个组合里同一条轴出现两次                                    |

## 关键 trade-off

- **延迟约束触发器 vs 普通触发器**：应用层必须先写表头（要占唯一索引）再写明细行，用普通 BEFORE 触发器时
  明细还没写、无法判定签名。延迟到提交才复核是唯一可行的顺序。
- **不建触发器到 `sku` 表**：那样能彻底封死"预留 X 与新建 SKU X 并存"，但要改动已交付模块（3.1.1/3.1.2）拥有的表行为，
  超出本模块边界。改为在占用侧加触发器 + 断言 V1 的 sku 唯一约束仍然有效，剩下的一环留给 3.1.2.1（见待办）。
- **显式组合模式不提供预校验按钮（矩阵模式下）**：矩阵组合由服务端展开，前端没有"待校验的组合"可提交，
  硬凑空取值去 check 只会拿到假冲突。矩阵模式的冲突在生成时返回。
- **`ApiError` 增加 `body` 字段**：改了共享文件，但是纯增量（默认 `null`），不改变既有调用方行为。

## 文件清单

新增

- `backend/services/catalog/src/main/resources/db/migration/V10__variant_combination_uniqueness.sql`
- `backend/services/catalog/src/main/java/com/starcon/catalog/CombinationRules.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/VariantCombinationService.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/VariantCombinationController.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/CombinationRulesTest.java`（12 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/VariantCombinationIntegrationTest.java`（27 例）
- `apps/web/src/components/catalog/combination-model.ts`（17 例覆盖）
- `apps/web/src/components/catalog/combination-model.test.ts`
- `apps/web/src/components/catalog/variant-combination-workspace.tsx`
- `apps/web/src/app/products/sku/variant-combination/page.tsx`

改动

- `CatalogModels.java`（3.1.3.2 的 16 个 DTO record）、`ApiErrorHandler.java`（结构化 409）
- `apps/web/src/lib/api.ts`（`ApiError.body`）、`apps/web/src/lib/api-proxy.ts`（allowlist）
- `apps/web/src/components/catalog/sku-list-workspace.tsx`（入口）、`apps/web/src/app/api/catalog/[...path]/route.test.ts`
- `packages/api-contracts/src/catalog.ts` + `catalog.openapi.json`（`pnpm contracts:generate` 产物）
- `docs/product/capability-blueprint/module-progress.md`（本行 + sync 自动统计）

## 顺带发现（未改，记录待办）

- `SpecAxisRules.matrixRowCount`（3.1.3.1 交付）用「乘完再判上限」，`10^20` 会溢出成 `7766279631452241920`
  这个正数并被当成真实行数返回。本模块的 `CombinationRules.matrixSize` 改成**先判再乘**并有单测钉住，
  但没有改那个已交付文件——它不属于本模块边界，留给后续单独收口。

## 后续待办

1. **3.1.2.1 落 SKU 时把预留行兑现为 ACTIVE**：目前预留 X 之后仍可能新建一个签名文本相同的 SKU
   （`guard_variant_combination_against_sku` 只在**占用侧**设防）。兑现时应给台账加 `sku_id` / 状态，
   并在写 `sku` 时按预留签名校验——这是跨表唯一性的最后一环，需要 3.1.2.1 与本表的 owner 一起定。
2. 类目规则（V9 `category_spec_axis`）里的 `required=true` 轴，与本模块"每条已绑定轴都必须取到值"的口径重合。
   目前组合校验以 `spu_spec_axis` 的实际绑定为准；若将来要求"类目必绑轴齐了才允许生成草稿"，
   应在 `generateDraft` 里加前置判定，而不是改 `validate`（那是唯一性的职责）。
3. `SpecAxisRules.matrixRowCount` 的溢出保护（见上）。
