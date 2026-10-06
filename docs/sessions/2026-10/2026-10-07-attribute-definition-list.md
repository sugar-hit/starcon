# 3.2.1.1 属性定义列表

## 决策摘要

交付卷 03 商品主数据系统 3.2.1.1「属性定义列表」，分支 `feat/3-2-1-1-attribute-definition-list`，
基线 `feat/7-1-1-1-inventory-overview`（tip `3dd8529`）。

### 数据模型张力怎么解的（本模块最大的取舍）

**张力**：`V1__catalog_and_flexible_attributes.sql` 的 `attribute_definition` 挂在单个 `spu_id`
下（`spu_id NOT NULL` + `UNIQUE(tenant_id,spu_id,code)`），表达的是「这个 SPU 有哪些属性」。
但 3.2.1.1 要管的是**属性字典**：路由 `/products/attributes` 是租户级列表页、蓝图要求
「按类目配置必填与可选范围」（这是类目 → 属性的关系）、V9 已把规格轴建成租户级字典并要求
**规格选项与属性选项共享同一个值源**（值源表的外键指向 `attribute_definition(tenant_id,id)`）。
这些在 SPU 作用域的表上表达不了。

**三个候选与取舍**：

| 方案 | 结论 | 理由 |
| --- | --- | --- |
| **(A) 同表提升**（`spu_id` 改可空） | **选它** | `spu_id IS NULL` = 租户级字典条目，`spu_id IS NOT NULL` = 已交付的 SPU 级定义，同表两种作用域。字典选项仍落在 `attribute_option`，于是「值源只有一处」**自动继续成立**，V9 的 `guard_spec_axis_option_source` 一个字都不用改 |
| (B) 另建 `attribute_dictionary` 表 | 否 | 不动 V1，但字典选项会落到第二张选项表，规格轴（V9 锁死在 `attribute_option`）与字典选项变成两个值源，直接违反蓝图硬约束 |
| (C) 字典条目彻底替代 SPU 级定义 | 否 | 最干净，但要重写已交付 3.2.2.1 的写入路径与全部既有测试，属于重开旧账 |

### (A) 的代价：两处连带改动（都不是可选项）

1. **编码唯一性必须换成 `UNIQUE NULLS NOT DISTINCT (tenant_id,spu_id,code)`**。
   换���字典行后 `spu_id` 为 NULL，而 PostgreSQL 认为 NULL 互不相等——同租户能建出两条
   `code='COLOR'` 的字典条目，字典最核心的「编码租户内唯一」当场失效。
   `NULLS NOT DISTINCT` 把 NULL 当真值去重：字典行编码租户内唯一，SPU 级行仍在该 SPU 内唯一
   （与 V1 同口径，既有行为不变）。
2. **V3 的 `data_scope` RLS 策略必须替换**。V3 给 `attribute_definition` 建的是 RESTRICTIVE 策略
   `EXISTS(SELECT 1 FROM spu ... AND p.id=attribute_definition.spu_id)`；`spu_id` 一旦可空，
   字典行永远不满足这个 EXISTS，写入被 RLS 直接拒掉，报的还是 `row-level security policy 违规`
   而不是可读的 409。**只改可空不改策略，字典一行都存不进去。** 替换后两段都保留：
   SPU 级定义仍跟随 SPU 可见性（与 V3 逐字等价），字典行在本租户内可见。租户隔离未被削弱——
   V1 的 `isolation` 策略仍然生效，且 RESTRICTIVE 策略之间是「与」的关系。

### 追加的字典侧约束（刻意只约束字典行）

`attribute_definition_variant_usage CHECK(spu_id IS NOT NULL OR NOT variant_axis OR usage='SPECIFICATION')`
——规格轴属性必须登记为「规格」用途，让蓝图 3.1.3.1 的「输入：属性定义（用途=规格）」在字典侧真的成立。

**`spu_id IS NOT NULL OR` 这一段是必须的，不是可选**：已交付的
`SpecAxisService.ensureVariantAttribute` 建 SPU 级承载属性时不写 `usage`，会落到新增列的默认值
`DESCRIPTION`，直接违反这条 CHECK。初版漏掉这一段时 **VariantCombinationIntegrationTest 82 例全挂**
（409「创建规格轴承载属性失败」）。已交付模块的既有写入语义本模块不改，所以豁免它们是刻意的。

### 其他四条核心决策

2. **选项集合不塞进属性定义的 PUT**。选项有独立的子资源（增 / 改位置 / 停用 / 删除）。
   把选项集合放进 PUT 会让「保存定义」顺手删掉几个选项，而选项一旦被引用就删不动——
   那种失败发生在用户以为只是改了个名字之后，是最难排查的一类。
3. **编码、数据类型、是否规格轴建后不可改**。编码是 SPU 级定义与规格轴按 code 对齐的锚点，
   改编码会让已有对齐在下一次写入时突然报「不一致」。要改就新建一条字典条目。
4. **编码用 ASCII `[A-Z0-9_-]`，选项值允许中文**。编码是机器标识（COLOR / NET_WEIGHT），
   选项值是人话（深空灰 / XL）。与 `CatalogService.code`、`SpecAxisRules` 同一词表，
   保证三者可对齐。前后端各写一份同口径校验，注释点明为何不同。
5. **`attribute_option` 加 `position` / `enabled`（停用不删除）**。蓝图对属性选项的硬要求是
   「有商品引用的选项只能停用」，而 V1 的选项表没有这两列。停用挡住的是「不许再新建取用」，
   历史单据照常可解析。

## 关键 trade-off

- **`spuUsageCount` 不是外键计数，是按 `code` 对齐统计的**。字典条目与 SPU 级定义不在同一个
  命名空间里（`(tenant,NULL,'COLOR')` 与 `(tenant,spu1,'COLOR')` 是不同元组），没有外键可依。
  界面上写「N 个商品在用」而不是「N 个引用」，避免误解为强绑定。
- **`productValueCount` 现在恒为 0**：`attribute_value` 的复合外键仍锁在 SPU 作用域
  （MATCH SIMPLE 遇 NULL 跳过），字典条目暂时不会被赋属性值——那是下游 3.2.2.x 的接线。
  字段照实反映数据库现状，接上赋值链路后自动开始计数，界面不需要改。
  真正今天就有值的引用是 `categoryRuleCount`（本模块自己的类目规则），删除守卫两个都拦。
- **`category` 是 `spu.category` 自由文本，不是外键**。3.3.1.1 分类树未开发，没有可引用的类目主键。
  与 V9 的 `category_spec_axis` 同一口径，迁移头注释写明 3.3.1.1 落地后应改挂主键。
- **「可选范围」按数据类型分流**：枚举属性用 `allowedOptions` 收窄取值子集（复合外键保证写入的
  值必须真实存在于 `attribute_option` 值源），数值属性用 `min_value`/`max_value`（`numeric(30,10)`，
  与 `attribute_value_item.decimal_value` 同精度，避免同一属性两套小数位）。两者互斥在服务层拦住。
- **类目规则级联删除**：删属性时规则随之删除。规则是「这条属性的要求」，属性没了要求就没有意义，
  留着会变成指向空处的死规则。
- **概览分报 `dictionaryAttributeCount` 与 `spuLevelDefinitionCount`**：同表两种作用域不分开报，
  「本租户定义了几条字典」会被商品属性污染。

## 实现期发现并修掉的四个真实缺陷

1. **`DROP POLICY` 不是 `ALTER TABLE` 的子句**。写成 `ALTER TABLE x DROP POLICY y` 会报
   `syntax error`，正确写法是独立语句 `DROP POLICY y ON x`。
2. **V3 的 `data_scope` 策略没跟着 `spu_id` 一起改**（见上）。症状是所有字典写入被 RLS 拒，
   而报错被 `DataAccessException` 吞成「编码已被并发创建」这种误导性文案。
3. **`variant_axis` 用途 CHECK 未豁免 SPU 级行**，打碎已交付的 3.1.3.1/3.1.3.2 写入路径（82 例）。
4. **`CatalogService` 里 `insert into attribute_option values(?,?,?)` 是位置化插入**，
   V11 给该表加列后随列数变化错位。改为显式列名——这是本模块对已交付代码的**唯一**改动。

另有一处自查修正：初版把选项的 `inUse` 定义为「被商品属性值取用」，但字典条目当前不可能有商品取值，
该字段会**恒为 false**，等于一个骗人的字段。改为返回两个真实计数
（`productValueCount` + `categoryRuleCount`）并由它们合成 `inUse`。

## 文件清单

**迁移**
- `backend/services/catalog/src/main/resources/db/migration/V11__attribute_dictionary.sql`（新增）

**后端**
- `backend/services/catalog/src/main/java/com/starcon/catalog/AttributeDictionaryRules.java`（新增，纯规则）
- `backend/services/catalog/src/main/java/com/starcon/catalog/AttributeDictionaryService.java`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/AttributeDictionaryController.java`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/CatalogModels.java`（改：追加 3.2.1.1 DTO）
- `backend/services/catalog/src/main/java/com/starcon/catalog/CatalogService.java`
  （改 1 行：`attribute_option` 位置化插入 → 显式列名）

**测试**
- `backend/services/catalog/src/test/java/com/starcon/catalog/AttributeDictionaryRulesTest.java`（新增，12 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/AttributeDictionaryIntegrationTest.java`（新增，26 例）

**契约（codegen 产物，未手改）**
- `packages/api-contracts/src/catalog.ts`、`catalog.openapi.json`、`identity.openapi.json`

**前端**
- `apps/web/src/components/catalog/attribute-dictionary-model.ts`（新增）
- `apps/web/src/components/catalog/attribute-dictionary-model.test.ts`（新增，27 例）
- `apps/web/src/components/catalog/attribute-dictionary-workspace.tsx`（新增）
- `apps/web/src/app/products/attributes/page.tsx`（新增）
- `apps/web/src/lib/api-proxy.ts`（改：catalog allowlist 放行 `attributes`）
- `apps/web/src/app/api/catalog/[...path]/route.test.ts`（改：新增 2 例放行测试）
- `apps/web/src/components/catalog/sku-list-workspace.tsx`（改：列表页加入口链接，最小改动）

**台账 / 日志**
- `docs/product/capability-blueprint/module-progress.md`（只改 3.2.1.1 一行；其余 5 行是
  sync.mjs 自动重算的计数器 40→41）

## 端点

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | `/catalog/attributes`（分页 + q/dataType/usage/filterable/enabled/groupName） | `catalog.attribute.read` |
| GET | `/catalog/attributes/overview` | `catalog.attribute.read` |
| POST | `/catalog/attributes` | `catalog.attribute.update` |
| PUT | `/catalog/attributes/{id}` | `catalog.attribute.update` |
| DELETE | `/catalog/attributes/{id}?version=` | `catalog.attribute.update` |
| GET | `/catalog/attributes/{id}/options` | `catalog.attribute.read` |
| POST | `/catalog/attributes/{id}/options` | `catalog.attribute.update` |
| PUT | `/catalog/attributes/{id}/options?value=` | `catalog.attribute.update` |
| DELETE | `/catalog/attributes/{id}/options?value=` | `catalog.attribute.update` |
| GET | `/catalog/attributes/category-rules?category=&attributeId=` | `catalog.attribute.read` |
| POST | `/catalog/attributes/{id}/category-rules` | `catalog.attribute.update` |
| DELETE | `/catalog/attributes/{id}/category-rules?category=` | `catalog.attribute.update` |

权限沿用属性域（`catalog.attribute.*`）：字典条目是属性体系的定义，不是 SKU 自身档案字段。
后端路由挂 `/catalog/attributes` 而非 `/products/attributes`——后者是台账里的**前端页面路径**
（与 3.1.3.1 的 `/products/sku/spec-axis` 对应后端 `/catalog/spec-axis` 同一性质），
且 `/catalog/products/{id}` 已是单个商品的档案页前缀，字典混进去会让「这是谁的属性」在 URL 上分不清。

## 验证

- `cd backend && ./mvnw verify` → BUILD SUCCESS（catalog **261 例**，identity 35 / channel 66 /
  warehouse 31，全绿 0 失败 0 错误）
- `pnpm typecheck` → 3 packages successful
- `pnpm lint` → ✔ No ESLint warnings or errors
- `pnpm test` → **23 files / 251 tests passed**（原 222，+29：model 27 + 代理放行 2）
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → ✓ Compiled successfully，
  `/products/attributes` 10.1 kB
- `node tools/module-progress/sync.mjs --check` → ✓ 台账与蓝图一致
- 迁移在真实 `postgres:16-alpine` 探针上按 V1→V11 顺序逐个 apply 全部通过（探针已 `docker rm -f` 清理）；
  另在探针上实测：同租户重复编码被 `attribute_definition_scope_code_key` 拒、跨租户同编码放行、
  `variant_axis` + 非规格用途被 CHECK 拒、非 OPTION 属性配可选值被触发器拒、`min>max` 被 CHECK 拒
- **已交付 3.2.2.1 未被破坏**：`deliveredSpuLevelAttributesStillWork` 是专立的回归桩
  （建 SPU 级属性 + 公共值 → 断言 `attribute_value`/`attribute_value_item` 照常落库、
  带选项的 SPU 级属性在加列后仍能写入）；`listOnlyContainsDictionaryRowsNotSpuLevelDefinitions`
  断言字典页只列 `spu_id IS NULL` 的行；`SpecAxisIntegrationTest` 27 例与
  `VariantCombinationIntegrationTest` 27 例全绿

## 后续待办

1. **3.2.2.2 SKU 属性覆写 / 字典接到赋值链路**：`attribute_value` 的复合外键仍锁在 SPU 作用域，
   字典条目目前还不能被赋属性值（`productValueCount` 因此恒为 0）。把字典条目接到
   3.2.2.1/3.2.2.2 的赋值链路属于**下游属性赋值模块**的改动——本模块交付的是字典 + 选项 +
   类目绑定规则，蓝图把「下游 = 3.2.2 属性赋值」写得很明确，故未越界改已交付模块的写入路径。
   接线时建议加一列指向字典条目（或让 SPU 级定义 `dictionary_id` 指向它），让
   `spuUsageCount` 从「按 code 统计」升级成外键计数。

2. **3.2.1.2 属性选项管理**：本模块已把选项的增删改查、排序与停用做进属性定义工作区
   （3.2.1.1 的输出明确含「选项集合」，不留残缺），但**未做它的独立页面**。
   该模块落地时可复用本模块的 `/catalog/attributes/{id}/options` 端点与值源约束。

3. **3.1.3.1 规格轴与字典的关系**：`SpecAxisService.ensureVariantAttribute` 目前在绑定时自动创建
   **SPU 级**承载属性，而不是复用租户级字典条目。字典交付后这条路径应扩展为「承载属性必须是
   用户选定的字典条目」，否则同一概念会在字典与自动创建两条路上各存一份。
   属于已交付模块的行为变更，本模块未擅动；`guard_spu_spec_axis` 的 code 一致性校验仍然成立。

4. **`apps/web/src/lib/api-proxy.ts` 的 catalog allowlist 仍缺 `sku-code`**（3.1.2.3 的账，本次未修）。
   本模块的 `attributes` 分支已按 backlog 建议的形式加进同一处正则，实测通过。

5. **3.3.1.1 分类树落地后**，`category_attribute_rule.category` 应与 `category_spec_axis.category`
   一起改挂 `product_category` 主键；本迁移按 V1 的自由文本列实现，头注释已写明这条债。