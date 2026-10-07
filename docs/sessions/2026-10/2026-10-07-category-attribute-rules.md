# 3.3.1.2 类目属性要求

## 决策摘要

交付卷 03 商品主数据系统 3.3.1.2「类目属性要求」，分支 `feat/3-3-1-2-category-attribute-rules`，
基线 `feat/3-3-1-1-category-tree`（tip `3b32c65`）。

四条核心决策：

1. **继承语义：支持从祖先继承，按属性逐条就近覆盖（nearest-wins）。**
   蓝图没写死，这里定死：有效规则集 = 沿 `product_category.path_ids` 从自己往根，
   对**每个属性**取第一条规则；这条规则 `enabled=false` 时该属性整体不进入有效集。
   停用最近那条 = 子节点显式豁免祖先要求。
   *为什么选继承而不是「绑定到具体节点」*：分类树是运营手工维护的多级结构（服饰 / 上装 / T恤）。
   不继承的话每件 T恤 都要把上装配好的颜色、尺码原样再配一遍，配错一处就是静默漏填——
   那是数据质量问题，不是配置问题。
   *代价（明确承认）*：祖先规则变更会影响全部子孙的有效规则集，所以读路径**按类目现算不缓存**，
   且每条规则都要标出 `depth` / `sourceCategoryName`，否则运营无法自查。

2. **停用必须作用在「最近的那条」，不能先过滤 enabled 再取最近。**
   这条是实现期发现并修正的真实缺陷：先在 SQL 里 `and r.enabled` 会让子节点的停用退化成
   「失效并回退到祖先」，这样子类目**没有任何办法**说「这个属性我不需要」——
   祖先要求一旦配上，子孙全部继承，停用只能把要求变成可选而不能取消。
   现在的顺序是：先按最近选定，再看启停；停用的那条如果最近，该属性就被豁免。

3. **`requirement` 是权威列，`required` 退化为派生列，由触发器单向同步。**
   V11 的 `required boolean` 表达不了 `conditional_required`：写 true 变成无条件必填，
   写 false 永远不校验，两种都错。用触发器而不是只在服务层同步，是因为已交付的
   3.2.1.1 `saveCategoryRule` 仍在显式写 `required` 且不认识 requirement——
   只在本模块服务层同步的话，别人下一次写 `required` 就把它改回去了。
   `CONDITIONAL_REQUIRED → required=false` 是刻意的：3.2.2.2 的 SKU 属性覆写无法求值条件，
   映射成 true 会把「颜色=红色时才必填保修期」变成「所有商品都必须填保修期」，把合法录入挡死。
   宁可少挡，不可乱挡。

4. **表单 schema 与保存前校验共用同一个条件求值器**（`CategoryAttributeRequirementRules.conditionHolds`）。
   两处各写一遍必然出现「表单亮必填、提交却放行」的分裂，而那种 bug 要等用户投诉才发现。
   同理，schema 的 `dependency.active` 与校验的 `conditionActive` 都由它产出。
   求值口径三条：前置属性没填 → 条件不成立（不是抛错也不是当真）；空取值集合 → 恒假；
   类型不可比（如对 OPTION 用 GT）→ 恒假而不是崩掉。配置层是主防线，求值层兜底。

## 关键 trade-off

- **加列而不是另建 `category_attribute_requirement` 表。** V11 已把「某类目对某属性有要求」
  建成事实表，V14 又把锚点从自由文本换成 `category_id`。另建表会让同一条「上装要求颜色」
  存在两份，改必填只改其中一张，校验读到哪张全靠调用方记得——两个写入所有者。

- **条件取值单独建表而不是塞 jsonb。** 这些值要参与反查（表单 schema 的依赖排序），
  且必须能被复合外键钉在规则与属性字典上，jsonb 两者都做不到。
  外键只能校验「依赖属性存在」，校验不了「就是该规则声明的那个依赖」，
  所以补了 `guard_category_attribute_condition_value` 触发器——这类错配不报错不崩，
  只是让条件永远求不出值，最难自查。

- **只加唯一索引、不换主键。** `category_attribute_rule` 主键仍是 `(tenant_id,category,attribute_id)`
  （V11 口径，3.2.1.1 的 on conflict / delete 依赖它）。本模块按 `category_id` 存取，
  在 id 上**另加**唯一索引 `(tenant_id,category_id,attribute_id)`——它既是
  「同一分类同一属性重复配置要拒绝」的权威落点，也是条件取值表外键的被引用键。
  换主键要连带改 3.2.1.1 的写入路径与它的一整套集成测试夹具，属于重开旧账。

- **表单 schema 与校验结果不落表。** 条件必填的求值依赖「当前已填了什么」，
  任何缓存都会在用户改完前置字段后过期。一律现算。

- **页面放在分类中心之下而不是属性中心。** 属性要求挂在类目上，离开类目就无从谈起；
  放 `/products/attributes` 会让「这个类目要求什么」变成要跨页拼出来的问题。
  与 3.3.1.1 分成两个页面（由分类树页面链过去），因为「改类目名」和「改必填项」
  挤在一屏里编辑会互相覆盖对方正在改的内容。

## 对已交付模块的影响面

- `AttributeDictionaryService.saveCategoryRule`：补写 `requirement` 并在 upsert 时清空
  `condition_*` 与条件取值。**只写 `required` 会让 V15 的触发器按旧 requirement 反推，
  把刚改成必填的规则打回可选**，3.2.1.1 的「改必填」当场失效（集成测试实测到这条）。
  API 语义与入参完全不变。清空条件是因为该端点表达不出「条件必填」，
  留着旧条件会撞 CHECK 报 409，而「退回普通必填/可选」才是调用方真正要的语义。
- 3.2.2.2 SKU 属性覆写：仍读 `required` 派生列，语义不变（见决策 3）。
- 3.3.1.1 分类树：仅多一个页面入口链接。

## 文件清单

**迁移**
- `backend/services/catalog/src/main/resources/db/migration/V15__category_attribute_requirements.sql`

**后端**
- `CategoryAttributeRequirementRules.java` — 词表、按数据类型分流的取值约束、条件求值（纯函数，可离线穷举）
- `CategoryAttributeRequirementService.java` — 规则 CRUD、启停、继承解析（`distinct on` nearest-wins）
- `CategoryAttributeRequirementForms.java` — 表单 schema 生成 + 保存前逐字段校验
- `CategoryAttributeRequirementController.java` — 6 个端点（读 catalog.attribute.read / 写 catalog.attribute.update）
- `CatalogModels.java` — 新增 14 个 record
- `AttributeDictionaryService.java` — 唯一被改动的已交付文件（见上）

**测试**
- `CategoryAttributeRequirementRulesTest.java` — 11 例纯函数
- `CategoryAttributeRequirementIntegrationTest.java` — 12 例，Testcontainers PG16 + 强制 RLS，含租户隔离三断言

**前端**
- `apps/web/src/app/products/categories/requirements/page.tsx`
- `apps/web/src/components/catalog/category-requirements-workspace.tsx`
- `apps/web/src/components/catalog/category-requirements-model.ts`
- `apps/web/src/components/catalog/category-requirements-model.test.ts` — 15 例
- `apps/web/src/lib/api-proxy.ts` — allowlist + 放行/拒绝用例

## 验证

- `cd backend && ./mvnw verify` → **469 tests, 0 failures**（catalog 337，其中本模块新增 23）
- 迁移另在 PG16 容器按序实跑 V1–V15 共 15 条全绿，并逐条验证约束拒绝与触发器防漂移
- `pnpm typecheck` / `pnpm lint` 绿
- `pnpm test` → **314 tests 全绿**
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` 通过
- `node tools/module-progress/sync.mjs --check` → ✓ 台账与蓝图一致

## 后续待办

- **3.1.1.2 商品编辑** 消费本模块的两个输出：录入时先取 `entry-form-schema` 渲染表单，
  保存前调 `validate`。注意条件必填的字段在录入页要跟着前置字段的取值实时重算必填性
  （重复调 `entry-form-schema?values=COLOR:RED` 即可，不要在前端复制一套求值逻辑）。
- **04 刊登表单** 的平台类目要求按蓝图归 04 自己维护：本模块的表单生成端点**只服务内部类目树**，
  平台树节点会早拒绝（不同平台的类目要求可能不同，混用会让刊登合规判断失效）。
- 若将来要把平台类目要求也纳入，需要先在 04 建「平台类目 → 内部类目」的映射，
  本模块的规则才能按映射间接作用到平台维度。
- `category_attribute_rule.category` 文本列仍是 V11 的兼容读列（主键含它）。
  同名类目跨节点会撞这个文本键——属已交付基线的既有约束，本模块不重开旧账；
  若 04 的平台映射引入同名节点，需要在换主键那一轮一并解决。