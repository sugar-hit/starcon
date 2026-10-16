# 2026-10-16: 4.2.3.1 平台类目映射落地

## 摘要

交付蓝图 L3「4.2.3 平台与站点适配」下的 **4.2.3.1 平台类目映射**（阶段 D5，主责服务 catalog）：

- **迁移** `V145__platform_category_mapping.sql`：内部分类 ↔ 平台类目的**多对多**映射（带平台 + 站点两个独立维度）+ 一份独立的「平台类目必填属性映射规则」。
- **端点** `/catalog/category-mappings/**` 共 11 条路径，覆盖映射维护、属性规则维护、覆盖概览、候选来源与下游解析。
- **页面** `/listings/category-mappings`（映射台账 + 必填属性清单解析面板 + 属性规则维护）。
- **测试** 19 例（14 集成 + 5 规则），全部在 Testcontainers 真实 PostgreSQL 16 上通过；catalog 服务全量 1502 例 0 失败。

蓝图的加粗句「同一个内部类目在不同平台映射到不同平台类目，映射必须带平台与站点维度」是本模块的核心语义，已落成可证明的测试（`mappingIsManyToManyAndCarriesPlatformAndSiteSeparately`）。

## 决策 / 结论

1. **映射是独立事实表，不在 `product_category` 上加两列。** 加列的话 N 个平台就要 N 列，且永远表达不了「两个内部分类映射到同一个平台类目」这一侧；关系就该是一张表。两端各有 `UNIQUE(tenant_id,internal_category_id,platform_code,site_code,platform_category_id)` 兜重复。

2. **平台与站点是两条独立列，不合成 `amazon/us` 一个字符串。** 「Amazon US 与 Amazon DE 共用一条平台树」是最常见的情况，合成字符串就只能靠前缀解析还原两个维度；解析一旦写错，映射会静默串到别的站点——症状是刊登到 DE 站却填了 US 站的类目，要等平台审核才报错。

3. **「至多一个默认」用 partial unique index，不用服务层自查。** 自查挡不住并发：两个请求同时把两条映射设为默认都会通过检查，刊登侧拿到两条默认后「用哪个」变成数据库物理顺序，不可复现。

4. **不新建平台类目表。** V14 结尾已写死「叶子到平台 category_id 的映射属于 04 刊登」，所以平台类目就是 `product_category_tree.tree_kind='PLATFORM'` 的节点。**平台类目改名仍归 3.3.1.1 写入所有权**，本模块不夺走——每张事实表只有一个写入所有者。

5. **属性规则挂「映射」上而不是平台类目上。** 同一个平台类目在内部分类 A 上要求材质、在 B 上可能完全不要求，必填清单是按映射而论的。

6. **`internal_attribute_id` 可空是刻意取舍，不是放松约束。** 它正是「平台类目必填属性清单」这个输出的前提：平台要求填 material 而租户字典里还没有对应属性，这个事实必须能记下来，否则运营配不出这条规则，只能等刊登被驳回才知道自己缺一个属性。可空 + `requirement='REQUIRED' AND internal_attribute_id IS NULL` 就是「已知缺口」的确定口径。

7. **树种类与平台归属用触发器钉死，不用 CHECK。** CHECK 只能看本行列，看不到 `product_category` 挂在哪棵树上。只在服务层校验的话，运维 psql / 未来 ETL / 另一个服务的迁移脚本都能造出「内部类目映射到另一个内部类目」的行——而那行在界面上完全看不出异常，只有刊登提交时才暴露。服务层同样校验一遍，目的是给出可读原因（「左边这个类目来自平台树，不能当内部分类」），两者是纵深不是重复。

8. **「候选值里不许有空白项」必须用触发器。** PostgreSQL 明令 CHECK 里不能用子查询（实测 `0A000`），而 `not exists (select … from unnest(allowed_values) …)` 正是子查询。与 V14 防环、V37 认证守卫同一手法。

9. **时间戳一律由 SQL `now()` 写入，Java 侧不绑 `OffsetDateTime`。** 这从结构上绕开了两个已知坑：jOOQ 3.19 把 `OffsetDateTime` 绑成 varchar 被 PostgreSQL 拒收，以及 `Timestamp.valueOf(LocalDateTime)` 静默偏 −28800s。读取侧 `Record.get(…, OffsetDateTime.class)` 是安全的，DTO 输出 UTC ISO 串。对应测试 `timestampsRoundTripWithoutTheSilentEightHourShift` **断言写回的值**（落在测试时刻 ±5 分钟内 + 逐字比对 DTO 串），而不是只断言没抛异常。

10. **平台/站点码表在 channel 服务（全局、不带 tenant_id），因此不建跨服务 FK。** AGENTS.md 禁止跨库 FK/视图。代价是能填一个 channel 里不存在的平台码；缓解是候选取自**租户已建的 PLATFORM 树**与**真实历史映射**，不是硬编码常量。这个取舍写死在迁移末尾一节里。

11. **权限复用商品主数据域**（读 `catalog.spu.read` / 写 `catalog.spu.update`），不新造权限词表——新词表要改 identity 服务的迁移，属于另一个服务的写入所有权。

## 关键 trade-off / 讨论

- **路由前缀选 `/catalog/category-mappings` 而不是挂 `/catalog/categories` 之下**：语义相邻但生命周期不同。分类树管「类目本身是什么」，本模块管「这个类目在某个平台某个站点对应哪个平台类目」，后者随平台改版频繁变动；挂在同一前缀下会让「改类目名」和「改平台映射」挤进一个页面、彼此覆盖对方正在改的内容。

- **api-proxy 白名单是 3 条车道冲突过的文件**：本轮只做**插入**（在 `categories` 之后加一条 `category-mappings` 分支 + 8 行注释）。已用脚本逐字符核验：把插入片段从新行里去掉后，与集成头 `1af79f98` 的该行**逐字节相同**——既有条目一字未动、未重排。

- **codegen 会顺带重写 channel 契约**：跑 `catalog-contracts.py` 时它处理全部 6 个服务，而 `backend/target/openapi/channel.openapi.json` 与仓库里的 channel 契约 DTO 顺序不一致（同字节数、顺序不同），会产出一个与本车道无关的 diff。已 `git checkout` 还原 channel 两个文件。**后续车道跑 codegen 时注意同样处理**，否则会把别的模块的噪声带进本车道提交。

## 涉及文件

**后端（catalog）**
- `backend/services/catalog/src/main/resources/db/migration/V145__platform_category_mapping.sql`（新建）
- `backend/services/catalog/src/main/java/com/starcon/catalog/PlatformCategoryMapping{Controller,Models,Rules,Service}.java`（新建）
- `backend/services/catalog/src/test/java/com/starcon/catalog/PlatformCategoryMapping{IntegrationTest,RulesTest}.java`（新建）

**前端**
- `apps/web/src/components/catalog/platform-mapping-model.ts`（新建：契约类型 + 纯规则 + 11 个接口函数）
- `apps/web/src/components/catalog/platform-mapping-model.test.ts`（新建：15 例）
- `apps/web/src/components/catalog/platform-mapping-workspace.tsx`（新建）
- `apps/web/src/app/listings/category-mappings/page.tsx`（新建：薄路由壳）
- `apps/web/src/lib/api-proxy.ts`（修改：catalog 白名单**插入**一条）

**契约与台账**
- `packages/api-contracts/src/contracts/catalog.ts`、`packages/api-contracts/src/catalog.openapi.json`（codegen 产物，未手改）
- `docs/product/capability-blueprint/module-progress.md`（仅第 193 行 4.2.3.1 的状态与证据两格）

## 关键遗留清理

接手时上一轮 worker 被配额打断，留下一个**未被引用的私有方法** `PlatformCategoryMappingService#currentTenantId()`，且其上方注释误称「tenant_id 由 currentTenantId() 走 RLS」（实际该方法显式收 `tenantId` 参数）。已删除死方法并改正注释，说明改为：租户隔离由 RLS 的 `WITH CHECK` 保证，显式带 `tenant_id` 只是为了让执行计划走 `platform_category_mapping_default` 这条 partial index，**不是安全边界的唯一来源**。

## 后续待办

- **4.2.3.2 合规预检**（同 L3，未开发）：直接消费本模块的 `platform_category_attribute_mapping_unmapped_required` partial index 作为阻断项清单，以及停用/未映射的内部分类。
- **4.2.1.1 表单生成**：调 `GET /catalog/category-mappings/resolve` 拿刊登目标与必填清单，按 `transform` 列执行值转换（本模块只存算子名，不存实现——一个算子的两份实现迟早会对不上）。
- **4.2.2.1 刊登模板**：`/listings/templates` 目前只挂了 4.2.2.2 描述模板，等 4.2.2.1 交付后加成页签。
- **channel 侧平台类目同步**：若 channel 后续交付「平台类目同步」，应通过 event 把平台码与站点码同步进 PLATFORM 分类树，届时 `platform_code` 自然有了权威来源；在那之前不改本表结构。

## 关联

- `docs/product/capability-blueprint/04-listing-and-content.md` L3 4.2.3.1（原文与加粗硬需求）
- `backend/services/catalog/src/main/resources/db/migration/V14__category_tree.sql`（3.3.1.1 分类树，本模块不夺其写入所有权）
- `backend/services/channel/src/main/resources/db/migration/V1__channel_registry_connections_and_shops.sql`（2.1.1.x 平台码表，跨服务边界）
- `docs/decisions/0002-multi-tenancy-strategy.md`（多租户与 RLS）
