# 3.3.2.1 品牌管理

## 决策摘要

交付卷 03 商品主数据系统 3.3.2.1「品牌管理」，分支 `feat/3-3-2-1-brand-management`，
基线 `feat/3-3-1-2-category-attribute-rules`（tip `5dd0d13`）。

四条核心决策：

1. **「需授权但无有效凭证不得刊登」落成读取时刻的判定，不落成 brand 表的 CHECK。**
   蓝图原话是「品牌授权状态直接影响能否刊登，品牌『需授权』但无有效凭证时应阻止发布」。
   这条规则有三个实现约束，逐条决定了形态：
   - CHECK 只能看见 `brand` 自己，而判定要读 `brand_authorization` 的有效期（还要按 asOf 当天求值），
     跨表条件在 CHECK 里写不出来；
   - 更关键的是语义：允许「先把品牌登记成需授权、凭证稍后补」是正常业务流
     （先建档、后补授权书是绝大多数公司的实际顺序）。在写入时就拒的话运营连建档都做不到——
     **被拒的应该是刊登，不是建档**；
   - 所以落成 `BrandRules.publishBlock`（纯规则、可离线穷举）+ 三个出口
     （列表行 / 详情 / 单个与批量判定端点），前端只把原因码翻成话，不重算判定。

2. **原因码分四条而不是一条 `BLOCKED`。**
   `BRAND_DISABLED` / `LICENSE_MISSING` / `LICENSE_NOT_YET_EFFECTIVE` / `LICENSE_EXPIRED`。
   分「未生效」与「已过期」是因为**下游处理动作不同**：前者等一等就能刊登，
   后者必须续约或换品牌——如果都返回「不能刊登」，04 刊登与 16 侵权检测只能靠匹配中文串，
   而后者要立刻下架该品牌下的全部商品。
   用码而不是一句话做接口字段，是为了两个下游能各自走自己的分支。

3. **授权凭证独立成表，一个品牌多条并存；撤销不是删除。**
   蓝图写的是「授权状态维护（可引用 16 的授权凭证）」——「凭证」是可数实体：
   同一个代理品牌往往先签一年期、再续签三年期，两条并存且各自有有效期。
   塞成 `brand` 上的 `license_no`/`expires_on` 两列，这两件事就只能留最新一条，
   历史凭证一续签就丢，而 16 侵权检测恰恰要回溯「出事那天有没有授权」。
   同理**刻意不提供删除凭证的端点**：`enabled=false` 是撤销、行仍在；
   删行就把「曾经有过授权、后来被撤销」这个事实抹掉了。
   （`brand_authorization` 放在 catalog 而不是 16，是因为 16 合规 23 个 L4 全部未开发；
   留一个指向不存在表的列比缺功能更糟。迁移结尾写明了 16 落地后的归属。）

4. **关联类目指向 3.3.1.1 的类目主键，不复刻一份类目文本。**
   `brand_category.category_id` 走复合外键 `(tenant_id, category_id) → product_category(tenant_id, id)`，
   与 V14 把 `category_attribute_rule` 改挂类目主键同一条路。集成测试真的去分类树里
   重命名一个类目，断言品牌侧显示名自动跟着变——关联指向主键，不是建关联时的快照。

## 关键 trade-off

- **判定在 SQL 侧与规则类里各有一份实现，口径逐字对齐。**
  列表的 `publishable` 筛选与 `blockedCount` 必须在 SQL 里算（否则「筛出不能刊登的品牌」
  这个筛选本身就做不到，那才是列表页的用途），而 `BrandRules.publishBlock` 要在内存里算，
  因为只有它能区分「未生效」与「已过期」。两处写成同一个布尔表达式，
  并由 `listPagesSearchesAndFiltersOnRealRows` 对同一关键词断言两边给出同一个集合。
  不这么做的症状是「页面筛得出 A、判定说 B」。

- **`spu.brand varchar(100)` 不改挂 brand 主键。**
  与 V14 处理 `category` 时同一口径：那是 V1 的列，属已交付模块（3.1.1.1 / 3.1.1.2）的
  写入所有权，改挂要连带重写三个已交付模块的 Service / DTO / 契约 / 页面 / 测试夹具，
  属于重开旧账（V9 / V11 / V13 / V14 四份迁移都反复强调过）。
  在那之前**品牌字典的权威是 `brand` 表**，`spu.brand` 文本继续按文本读写。

- **凭证有效期用 `date` 而不是 `timestamptz`。**
  授权合约按**日**生效与失效，存到秒只会制造「昨天 23:59 到期、今天 00:00 就失效」
  这种没人能预期的边界。跨时区判定在服务层按 **UTC 当天**做，判定结果回带 `asOf`——
  同一份数据在 UTC 机器与 Asia/Shanghai 机器上必须给出同一个刊登结论，
  而这是 16 侵权判定最不能容忍的一类漂移。

- **停用的类目不允许新增品牌关联，但已有关联保留。**
  新建一个指向停用类目的关联没有意义；而类目停用时级联删关联的话，
  运营就再也看不见「这个品牌挂在哪个已停用的类目上」，只能去数据库里翻。
  与 3.3.1.1「停用不删除、类目删除才级联」同一口径。

- **Logo 只存引用不存本体。**
  3.4.1 媒体库未交付，所以不建媒体表也不挂外键——挂一个不存在的表等于假数据。
  `logo_uri varchar(500)` 存对象存储 key 或 URL，与 `product_asset.reference` 同一性质。

## 对已交付模块的影响面（刻意最小）

- 未改任何已交付模块的 Service / DTO / 契约 / 页面 / 测试夹具。
- 契约重生成后**只有 operationId 重编号**（SpringDoc 因新增 BrandController 而位移），
  无语义变化：新增 10 条 `/catalog/brands*` 路径与 13 个 Brand* schema，
  既有 schema 与路径的响应体逐字相同。
- api-proxy 只新增 `brands` 前缀的放行，未改动其它任何一段 pattern。

## 文件清单

迁移

- `backend/services/catalog/src/main/resources/db/migration/V16__brand_management.sql`

后端

- `backend/services/catalog/src/main/java/com/starcon/catalog/BrandRules.java`（新增，纯规则 + 阻止刊登判定）
- `backend/services/catalog/src/main/java/com/starcon/catalog/BrandService.java`（新增，jOOQ 显式 SQL）
- `backend/services/catalog/src/main/java/com/starcon/catalog/BrandController.java`（新增）
- `backend/services/catalog/src/main/java/com/starcon/catalog/CatalogModels.java`（追加 3.3.2.1 模型段 + BrandEligibilityBatchInput）

测试

- `backend/services/catalog/src/test/java/com/starcon/catalog/BrandIntegrationTest.java`（15 例）
- `backend/services/catalog/src/test/java/com/starcon/catalog/BrandRulesTest.java`（10 例）

前端

- `apps/web/src/app/products/brands/page.tsx`（新增页面）
- `apps/web/src/components/catalog/brand-workspace.tsx`（新增工作区）
- `apps/web/src/components/catalog/brand-model.ts`（新增模型：接口 + 校验 + 显示口径）
- `apps/web/src/components/catalog/brand-model.test.ts`（32 例）
- `apps/web/src/lib/api-proxy.ts`（放行 `brands` 前缀）
- `apps/web/src/app/api/catalog/[...path]/route.test.ts`（放行用例）

契约

- `packages/api-contracts/src/catalog.openapi.json`、`packages/api-contracts/src/catalog.ts`（codegen 产物，未手改）

台账

- `docs/product/capability-blueprint/module-progress.md`（3.3.2.1 行状态改「已交付」）

## 后续待办

- **04 刊登**调 `GET /catalog/brands/{id}/listing-eligibility` 与
  `POST /catalog/brands/listing-eligibility` 落刊登前置校验；批量发布的品牌去重后一次批量判。
- **16 侵权检测**按品牌取凭证历史（`brand_authorization` 含撤销行）做回溯，
  并把凭证的写入所有权从 catalog 移过去（按模块边界改成授权 API / event，不做跨库读写）。
- **`spu.brand` 改挂 `brand` 主键**属于重开旧账，需要连同 3.1.1.1 / 3.1.1.2 与
  3.1.2.1 / 3.2.2.x 的写入路径一起排，不在单模块范围内。
- **Logo 本体**由 3.4.1 媒体库交付后接入；本模块只持有引用。
- 3.3.2.2（若蓝图后续拆分商标/类目品牌细分）沿用本表的授权状态与判定，不另起一套。

## 验证

- `cd backend && ./mvnw verify`：BUILD SUCCESS。
  identity 35 例、catalog **362 例**（新增 BrandIntegrationTest 15 + BrandRulesTest 10，
  已交付模块 0 失败）、channel 66 例、warehouse 31 例。
- `pnpm typecheck && pnpm lint && pnpm test`：3/3 包通过；test 336 例全绿
  （新增 brand-model 32 + api-proxy 放行 1）。
- `NEXT_TELEMETRY_DISABLED=1 pnpm --filter @starcon/web build`：通过，
  `/products/brands` 9.51 kB 静态预渲染。
- `node tools/module-progress/sync.mjs --check`：✓ 台账与蓝图一致。
- V16 另用临时 PG16 容器按版本号顺序 psql 跑过 V1~V16 全绿（探针容器已删除，脚本在 `.tmp/`）。
