# 4.2.2.1 刊登模板（D5 阶段，L3 锚点 4.2.2）

> 车道 `feat/4-2-2-1`，worktree `.worktrees/4.2.2.1`，基线 `integration/module-dev` 的 1bb270a0。

## 决策摘要

蓝图 §4.2.2.1 只有四行，成立与否全在那句加粗上：**模板引用的是「取值规则」而非一次性拷贝，
商品改价或换图后套用结果跟着变**。

这句话如果只写在文档里，下一个人就会顺手把「当前标题」「当前图片」抄进模板表 ——
模板随即退化成一次性拷贝，商品改价后模板不会跟着变，而**没有任何报错**。所以本模块把它
落成**四道结构约束**，缺一道这个不变量就守不住：

1. **物理分表** —— 模板表只有适用范围与「来源引用 + 取值表达式」，**没有任何列能存某个
   listing 的值**；值唯一存在的地方是套用日志（append-only 的历史事实），不是模板。
2. **来源引用只能是标识符**（DB CHECK）—— 「把商品现在的值抄进 source_ref」写不进去。
3. **占位符只能是标识符**（DB 触发器）—— 也不能把值藏进 `{{...}}`；
   且非 LITERAL 来源的表达式**必须至少引用一个占位符**（不引用来源的表达式 = 伪装的常量快照）。
4. **套用日志只追加**（DB 触发器）—— 「当时套用出来的是什么」改不了也删不掉。

实时求值是真的：套用时逐条规则**当场**读源商品（catalog 授权 HTTP API 取商品属性 /
媒体库当前版本的 storageKey / 文案库渲染结果）。集成测试打穿的是
「改一次源商品 → 重新套用 → 草稿里的值跟着变」，**同时断言模板那几行逐字未变**。

其余三条硬约束的落法：

- **两种作用域都真做且判定有真实数据支撑** —— `scope` 的适用范围列**互斥**（CHECK）；
  判定在真实 `listing` 行上做（平台 / 站点 / category_path 或 shop_id），
  并由 `GET /{id}/matches` 把真的命中的 listing 从库里查出来。
- **只产出草稿、绝不写线上** —— 没有任何一个端点能写线上 listing；写事务打开
  `app.draft_only`，V177 的触发器在期间拒绝对线上内容列的任何改动。
- **「不知道」是一个状态** —— 上游读不到记 `UNKNOWN_SOURCE`、引用不存在记
  `SOURCE_MISSING`，两者都带 `owner_module` 点名归属模块，绝不回退成「没有值」或 0。

## 开工自检：上游到底有没有交付

| 上游 | 结论 | 处置 |
| --- | --- | --- |
| 03 描述与卖点库（文案资产） | **已交付**（catalog V21 `content_template*` + `GET /catalog/description-templates/templates` / `POST /renders`） | 走 `ListingTemplateSourcePort` 读 |
| 03 媒体库 | **已交付**（catalog V18 `media_asset*` + `GET /catalog/media`、`GET /catalog/media/{id}`） | 同上 |
| 03 商品主数据（商品属性） | **已交付**（catalog `GET /catalog/skus/{id}` 返回 `attributes[].effectiveValues`） | 同上 |
| 4.2.1.2 批量编辑的草稿表 | **已交付**（V177 `listing_content_draft`） | **复用**，不另建平行草稿表 |
| 4.2.1.1 刊登编辑器 | **未交付**（同批并行） | 零依赖：不读它的表、不调它的端点；能力声明里 `editor_entry` 维度 `available=false` + `ownerModule='4.2.1.1'` |

## 关键 trade-off

| 决策 | 选择 | 理由 |
| --- | --- | --- |
| 草稿表 | **复用 V177 `listing_content_draft`** | 另建一张平行草稿表会让「哪份草稿是发布要用的」有两个答案。复用要接受它的列集（title/bullets/images/category_path/platform_category_id）与 `touched_fields` 一一对应的 CHECK —— 于是模板**不能**填描述正文，该维度在能力声明里 `available=false` + `ownerModule='4.2.1.1'`，不假装能做 |
| 草稿的 `last_operation_id` | 写**本次套用 id** | V177 该列无外键（两种来源都可能），4.2.1.2 写批量操作 id、本模块写套用 id；不写则 CHECK `state='DRAFT' ⇒ last_operation_id IS NOT NULL` 会拒 |
| 端口读不到上游时 | **返回 `available=false` 的结果对象**，不抛异常 | 既有端口（`SkuCandidateQueryPort`）读不到抛 503，那里「查不到候选」会让整批推荐无从谈起；本模块不同 —— 一条规则的上游挂掉**不该让整次套用失败**，该字段显式变成 `UNKNOWN_SOURCE` + `ownerModule`，其余字段照常产出草稿。端口契约与既有端口**刻意不同**并在注释里写明理由 |
| 幂等语义 | **两条并存**：不带键重复套用 = 覆盖草稿；带同一个键 = 返回第一次的结果 | 「改价后要看到新值」要求重复套用必须覆盖并重新求值；「重复触发不产生重复动作」要求带键时不得二次执行。两条各有其适用场景，语义写进能力声明与端点注释 |
| 平台维度上限 | 取 V177 `listing_bulk_template` 里该平台**最严**的一组 | 写死 500 会让发到短标题平台的商品直到被平台驳回才发现超长。沿用 V20「取最严」先例；无该平台行时用其列默认值 500/9/10（正是草稿表与 listing 的物理列宽） |
| 作用域互斥 | CATEGORY 与 SHOP 的适用范围列**互斥** | 两个都给会出现「按类目还是按店铺生效」两个答案，而判定逻辑只能挑一个 —— 挑错的那个就是静默用错模板 |
| `source_ref` 形状 | CHECK 限死为**标识符** | 这是「模板不存快照」的物理保证：把「现价 99.00」这类值当来源引用，DB 直接拒 |
| 取值表达式的占位符 | 限死为**标识符**，且非 LITERAL 必须至少引用一个 | 「把值藏进占位符」与「用一条不引用来源的常量冒充规则」两条路一起堵死 |
| 套用记录 | **append-only** | 套用记录是「当时算出了什么」的凭据；可改可删的话这个问题永远答不上来 |
| 契约字段的 UUID 空值 | `id` / `marketplaceId` / `shopId` 用 **String** | 前端契约里这三个字段是非空字符串；用 UUID 类型时空串会让 Jackson 400。服务层把空串当「没给」 |
| api-proxy 落法 | **新增一条独立 `listing-templates/` 元素** | 并进既有 channel 长正则必然与并行车道撞车；插入锚点固定在 trade 元素与 crm 元素之间 |

## 文件清单

后端（channel 服务）

- `backend/services/channel/src/main/resources/db/migration/V195__listing_template.sql`（新，五张表 + 四道守卫 + RLS/GRANT）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingTemplateModels.java`（新，线上契约 record）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingTemplateService.java`（新，jOOQ 显式 SQL + 求值引擎）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingTemplateController.java`（新，12 个端点）
- `backend/services/channel/src/main/java/com/starcon/channel/ListingTemplateSourcePort.java`（新，03 三个上游的只读端口）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingTemplateIntegrationTest.java`（新，12 例）
- `backend/services/channel/src/test/java/com/starcon/channel/ListingTemplateTenancyIntegrationTest.java`（新，3 例）
- `backend/services/channel/src/test/java/com/starcon/channel/StubCatalogServer.java`（新，本地 catalog 服务，不是 mock：真 socket 真 HTTP）

前端

- `apps/web/src/components/listing-template/listing-template-model.ts`（新，取数 + 纯函数）
- `apps/web/src/components/listing-template/listing-template-workspace.tsx`（新，工作区）
- `apps/web/src/components/listing-template/listing-template-model.test.ts`（新，15 例）
- `apps/web/src/app/listings/templates/page.tsx`（改，**加成页签**，4.2.2.2 描述模板工作区原样保留）
- `apps/web/src/app/api/channel/listing-template-allowlist.test.ts`（新，4 例）
- `apps/web/src/lib/api-proxy.ts`（改，**新增一条** `listing-templates/` services[] 元素）

契约与台账

- `packages/api-contracts/src/channel.openapi.json` / `packages/api-contracts/src/contracts/channel.ts`（codegen 产物，禁手改）
- `docs/product/capability-blueprint/module-progress.md`（只改 4.2.2.1 那一行的「状态」「证据」两格）

## 验证

- L0 编译闸：`./mvnw -q -pl services/channel -am compile` 绿；`pnpm typecheck` 绿（3 包全过）；`pnpm lint` 绿（仅仓库既有 warning）。
- L1 模块闸：`./mvnw -pl services/channel -am test -Dtest='ListingTemplate*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` ⇒ **15 例全绿**（功能 12 + 租户隔离 3）。
- 前端：`pnpm vitest run apps/web/src/components/listing-template` ⇒ 15 例绿；
  `pnpm vitest run apps/web/src/app/api` ⇒ **全量 allowlist 回归 79 文件 496 例绿**（改过 api-proxy 的硬性例外）。
- 契约：先跑 channel 的 `OpenApiContractTest` 导出 spec，再 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi`（`index.ts` 未变，其余五个服务产物逐字未变）。

## 后续待办

- **4.2.1.1 刊登编辑器**（同批并行）落地后，把「编辑器里的套用入口」接上；本模块的
  `editor_entry` 维度从 `available=false` 改为 `true`，`ownerModule` 撤掉。
- **描述正文字段**（`DESCRIPTION`）：4.2.1.1 的草稿表加上描述列后，本模块的
  `description_field` 维度可改为 `true`，并在 `listing_template_field` 的 field 枚举里补上。
- **平台维度上限**：目前取自 V177 `listing_bulk_template`（批量编辑的按平台上限配置）。
  02 的平台能力矩阵（2.1.1.2）交付后应改读那边，本仓同一张 `platform_limits` 事实只应有一处。
- 03 媒体库的**标签**分组目前只支持 `TAG` 一种取组方式；若媒体库侧需要更细的组（按文件夹 /
  按 SPU 关联），`listing_template_asset_group` 需要再加一种 `group_kind`。
