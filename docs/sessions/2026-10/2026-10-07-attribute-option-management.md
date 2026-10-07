# 3.2.1.2 属性选项管理

- 基线：`feat/3-2-1-1-attribute-definition-list` @ `72eb339`
- 分支：`feat/3-2-1-2-attribute-option-management`（未 push）

## 决策摘要

3.2.1.1 已交付按单个 `attributeId` 取选项的路径，`attribute_option` 表与增删改查也已存在。
本模块的真实增量是三件事，不重做 3.2.1.1：

1. **跨属性聚合视图** — 租户级「所有枚举属性的选项」一张表，带筛选（属性 / 启用状态 / 是否被引用）、
   白名单排序、真实分页。这是蓝图把「引用计数」列为**输入**的落点。
2. **启用/停用独立端点** — 停用永远允许；有引用的选项只能停用（删除守卫沿用 3.2.1.1，不开第二个删除入口）。
3. **批量重排** — 覆盖式提交，事务内逐条校验。

## 关键 trade-off

- **删除不新增端点。** 蓝图说「有商品引用的选项只能停用」，3.2.1.1 的 `DELETE /{id}/options`
  已有引用守卫。开第二个删除入口，迟早有一处守卫被绕过。聚合页复用同一条删除路径。
- **引用计数不伪造。** `productValueCount` 目前恒为 0——`attribute_value` 的复合外键仍锁在 SPU 作用域，
  这是数据库现状（V11 结尾已注明）。选择**如实呈现 0 并解释成因**，而不是在 SQL 里绕开作用域去凑一个数。
  前端 `referenceText()` 因此在 0 时说「暂无引用」而非「已被 0 个商品使用」，并常驻一条口径说明。
  `categoryRuleCount` 是真实的，已单独测试。
- **批量重排用覆盖式提交而非逐条 update。** 拖拽排序天然是一组位置；逐条提交在中途失败时会留下半套顺序，
  比整批失败更难排查。整批在一个事务里（`AccessService.run` 已开事务）。
  `applied` 只数真正改动的行，使重复提交天然幂等。
- **排序走白名单。** 排序片段直接拼进 SQL，`ORDER_BY` 映射表之外的取值一律退回默认档，
  不做列名校验也不原样出库（注入面）。已测注入串与未知档位。
- **V12 只加索引。** `position` / `enabled` / `created_at` 三列 V11 已建好，**不重复 ALTER**。
  真正缺的是聚合读路径的索引：V11 的 `attribute_option_order` 只服务按单个 attribute_id 取值，
  聚合列表走不上；同时补上 V11 漏掉的 `category_attribute_option` 选项级反查索引。
- **不在本模块新增取值入口。** 聚合页只做「看 / 停 / 排」，登记取值仍在属性定义页——
  免得同一个动作有两个地方能做。

## 影响面

- 新增路由 `/catalog/attributes/options`（GET）、`/options/lifecycle`（PUT）、`/options/order`（PUT）。
  代理 allowlist 新增三条分支，靠「第一段是字面量 options 还是 UUID」与 `{uuid}/options` 区分。
- `/products/attributes` 改为标签页：「属性定义」（3.2.1.1）「选项管理」（本模块）。
- 契约 codegen 产物**纯增量**：204 个 DTO，新增 8 个类型，既有类型零改动。

## 文件清单

**迁移**
- `backend/services/catalog/src/main/resources/db/migration/V12__attribute_option_management.sql`

**后端**
- `.../catalog/AttributeOptionService.java`（新增）
- `.../catalog/AttributeOptionController.java`（新增）
- `.../catalog/CatalogModels.java`（+8 个 record）

**前端**
- `apps/web/src/components/catalog/attribute-option-model.ts`（新增）
- `apps/web/src/components/catalog/attribute-option-workspace.tsx`（新增）
- `apps/web/src/components/catalog/attribute-option-model.test.ts`（新增，9 例）
- `apps/web/src/app/products/attributes/page.tsx`（改为标签页宿主）
- `apps/web/src/lib/api-proxy.ts`（allowlist +3 条）
- `apps/web/src/app/api/catalog/[...path]/route.test.ts`（+1 例）

**生成物 / 文档**
- `packages/api-contracts/src/catalog.ts`、`catalog.openapi.json`（codegen，只读）
- `docs/product/capability-blueprint/module-progress.md`（仅 3.2.1.2 一行 + sync 重算的计数）

## 验证

- `./mvnw verify` — 418 例全绿（catalog 24 例新增，真实 PostgreSQL 16 + 强制 RLS）
- `pnpm typecheck` / `pnpm lint` / `pnpm test` — 262 例全绿
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` — 成功
- `node tools/module-progress/sync.mjs --check` — 一致

## 后续待办

- **3.2.2.1 / 3.2.2.2 接上属性赋值链路后**，`productValueCount` 会自动开始计数，
  前端「商品取用未接入」的标注与页面底部那条口径说明届时应一并撤掉。
  索引已就位，不需要额外迁移。
- 3.3.1.1 分类树落地后，`category_attribute_rule.category` 与 `category_spec_axis.category`
  仍应一起改挂 `product_category` 主键（V11 结尾已留口子，本模块未触碰）。
- 台账 3.2.1.2 状态已改为「已交付」；`/products/attributes` 同时承载 3.2.1.1 与 3.2.1.2。