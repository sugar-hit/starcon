# 3.3.1.1 分类树管理

- 日期：2026-10-07
- 分支：`feat/3-3-1-1-category-tree`（从 `feat/3-2-2-2-sku-attribute-override` @ `66c9a9d` 切出）
- 状态：已交付，全量验证通过

## 决策摘要

1. **树是独立实体，不是分类上的一个标签。**
   `product_category_tree` 有自己的编码、种类（`INTERNAL` / `PLATFORM`）、`platform_code`、启用状态与版本。
   蓝图写死了「分类是多棵独立的树，不能混为一棵——内部类目与平台类目的维护节奏完全不同」，
   在分类行上加 type 标签表达不了「两棵树要能各自启用停用、各自版本」。

2. **`category_id` 是权威键，`category` 文本列保留为已交付模块的兼容读列。**
   这是本模块最关键的一个取舍，见下节。

3. **防环双保险。** 服务层递归 CTE 上溯祖先链给可读原因；V14 触发器挡住绕过服务的写入。
   只有服务层那一道时，运维 psql 或未来 ETL 能造出有环的树，而环一旦形成，
   所有递归查询是**死循环**而不是报错，会占满连接池。

4. **停用与删除分成两件事**，并在迁移注释、Service 注释、前端文案三处写清下游影响：
   停用只是「不许再新建使用」，规则与已挂商品全部保留；删除才级联。

5. **主分类与辅助分类用一张带 role 的关联表**（`spu_category`），不是在 `spu` 上加一列。
   加一列只能表达单分类——V1 的 `spu.category varchar(100)` 就是这么来的，也正是本模块要替代的东西。

## 关键 trade-off：规则表怎么「改挂主键」

V9 / V11 / V13 三份迁移结尾都写过同一句话：「category 仍是自由文本，3.3.1.1 分类树落地后，
`category_attribute_rule.category` 与 `category_spec_axis.category` 应一起改挂 `product_category` 主键」。

不锚主键的后果很具体：3.2.1.1 的 `categoriesWithoutRuleCount`（AttributeDictionaryService）
只能按字符串比分类，运营在树上看到一个类目、统计里却是另一个名字，两边永远对不齐。

三个方案：

| 方案 | 结论 |
| --- | --- |
| **(A) 加 `category_id` 外键，保留 `category` 文本列** | **选它** |
| (B) 把 `category` 改成 `uuid` | 否：`category` 是 `category_attribute_rule` 主键 `(tenant_id,category,attribute_id)` 的一列，`category_attribute_option` / `category_spec_axis` 都有指向它的复合外键。改类型会连带重写三个已交付模块的 Service / Controller / DTO / OpenAPI 契约 / 前端页面 / 约 40 处集成测试夹具——那是**重开旧账**，与三份迁移反复强调的口径不符 |
| (C) 另建关联表 `category_rule_link` | 否：多一张事实表就多一个写入所有者，同一条规则出现在两个地方 |

选 (A) 之后唯一的风险是「文本与主键各说各话」，口径写死成一条：
**`CategoryTreeService` 是唯一允许写 `category` 文本的地方，且只在重命名路径写**（同一事务同步三张表）。
既有自由文本做**数据迁移**而不是留 TODO：扫 `spu.category` / `category_attribute_rule.category` /
`category_spec_axis.category` 三个来源（少扫一个就 NOT NULL 失败），每个租户建一棵 INTERNAL 树，
每个类目文本建一个根级节点，再回填 `category_id` 与 `spu_category` 的 PRIMARY 行。

### 连带发现：`category_id` NOT NULL 会打断已交付的规则写入路径

这是实现过程中撞出来的、也是本模块对已交付代码唯一必要的改动：
`AttributeDictionaryService.saveCategoryRule` 与 `SpecAxisService.saveCategoryRule` 的入参是**类目文本**，
它们的 INSERT 不写 `category_id`，`NOT NULL` 一上就全部 400。

处理方式（刻意选「不重开旧账」的那条）：

- 两个端点的**对外 API 语义一行未改**（入参仍是类目文本），契约不变。
- 新增 `CategoryTreeService.resolveOrCreateCategoryId(actor, categoryText)`：命中即用，
  miss 则在 INTERNAL 树里建节点，**编码生成规则与 V14 迁移逐字一致**（同样的规范化 + md5 后缀），
  所以「迁移时建的」与「现建的」是同一行，不会出现两个同名类目。
- 「miss 就建」而不是「miss 就拒」：拒绝会让两个已交付模块的规则配置能力**当场不可用**，
  而分类树是本模块刚交付的、运营手上还没有节点——那等于加了一条跨模块的硬顺序要求。
- 两个已交付测试里各有一处需要跟新 schema：
  `SkuAttributeOverrideIntegrationTest.categoryRule` 是裸 SQL 直插，补 `category_id` 即可，测试意图不变。

## 实测踩到的坑（都写进了迁移/代码注释）

1. **`UPDATE product_category c SET c.path_ids = ...` 会报 `column "c" does not exist`。**
   PostgreSQL 的 UPDATE 在 SET 左侧只认列名，别名只能出现在右边与 WHERE。
2. **`SELECT ... INTO var` 查不到行时把 var 置 NULL**。跨树挂父时 `path_ids` 变成 NULL，
   以 `null value ... violates not-null constraint` 的形式炸出来，运维完全猜不到原因。
   改成先取父节点的 `path_ids`，取不到就抛「父分类不存在，或它属于另一棵分类树」。
3. **jOOQ 把 `UUID[]` 绑成一个 `uuid` 参数**，`= any(?::uuid[])` 报
   `cannot cast type uuid to uuid[]`。改逐个占位符——本仓库已有同样做法
   （`OAuthAuthorizationService.loadCandidates` 的注释就写了这件事）。
4. **子树商品数方向写反**：`c.path_ids` 是「c 的祖先链」，
   `sc.category_id = any(c.path_ids)` 统计的是**祖先**上的商品。子树口径要反过来问：
   `exists(... where c.id = any(d.path_ids))`，`d` 是该商品挂的类目。这个是集成测试断言出来的。
5. **`spu_category` 的复合外键需要被引用列有唯一约束**，
   即 `(tenant_id,tree_id,id)`；只写 `(tenant_id,id)` 建不出「父节点必须同树」那条外键。

## 文件清单

新增：
- `backend/services/catalog/src/main/resources/db/migration/V14__category_tree.sql`
- `backend/services/catalog/src/main/java/com/starcon/catalog/CategoryTreeService.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/CategoryTreeController.java`
- `backend/services/catalog/src/main/java/com/starcon/catalog/CategoryTreeRules.java`
- `backend/services/catalog/src/test/java/com/starcon/catalog/CategoryTreeIntegrationTest.java`（15 例）
- `apps/web/src/components/catalog/category-tree-model.ts`
- `apps/web/src/components/catalog/category-tree-model.test.ts`（17 例）
- `apps/web/src/components/catalog/category-tree-workspace.tsx`
- `apps/web/src/app/products/categories/page.tsx`

改动：
- `CatalogModels.java`（3.3.1.1 的 DTO）
- `AttributeDictionaryService.java` / `SpecAxisService.java`（构造器注入 + 写 `category_id`）
- `api-proxy.ts`（放行 `categories` 前缀 + 中文注释说明每段路径用途）
- `apps/web/src/app/api/catalog/[...path]/route.test.ts`（放行 + 拒绝用例）
- `packages/api-contracts/src/catalog.ts` / `catalog.openapi.json`（`contracts:generate` 产物）
- `docs/product/capability-blueprint/module-progress.md`（3.3.1.1 一行）

## 验证

- `cd backend && ./mvnw verify` → BUILD SUCCESS；catalog 314 例、channel 66、warehouse 31，
  已交付模块零回归。
- `pnpm typecheck && pnpm lint && pnpm test` → 全绿，前端 295 例（含新增 17 + api-proxy 2）。
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build` → `/products/categories` 已产出。
- `node tools/module-progress/sync.mjs --check` → 台账与蓝图一致。
- V14 另用临时 PG16 容器按序跑 V1~V14，并预置既有自由文本数据验证迁移与三类成环拒绝
  （探针容器已删除，临时脚本在项目内 `.tmp/`）。

## 后续待办

- **`spu.category varchar(100)` 仍在，且仍是 3.1.1.x 的写入所有者。** 本模块不夺别人的写入所有权：
  「主分类 / 辅助分类」的权威关系已由 `spu_category` 承载，两者并存期间**以 `spu_category` 为准**。
  等 3.1.1.x 的商品编辑改为按类目选择后，那一列就可以退场——那是商品档案模块的改动。
- **3.2.1.1 的 `categoriesWithoutRuleCount` 仍按文本比**。规则表现已锚在 `category_id` 上，
  但统计语句本身在 AttributeDictionaryService 里、属于已交付模块，本次没动。
  下一步把它改成按 `spu_category` 主分类 join，是一次纯粹的统计口径修正。
- **平台类目映射树的叶子到平台 `category_id` 的映射**属于 04 刊登：本模块只把平台树建起来并可维护，
  「这个叶子对应平台的哪个类目 id」不在这里抢先定义。
- **3.3.1.2 类目属性要求**仍是独立模块；它的规则读写在 3.2.1.1 里，锚点已换成本模块的类目主键，
  但录入表单 schema 生成不在本模块。
