# 3.4.3.1 多语言内容（D1–D2）

## 决策摘要

蓝图 3.4.3.1 要求维护 `spu_translation` / `sku_translation`：按语言唯一、支持人工校对与
AI 翻译建议的采纳流程、翻译状态三态（待翻 / 已翻 / 已校对），并且
**需要哪些语言由店铺启用站点推导，不靠人肉记忆**。

四个关键判断：

1. **待翻是一等状态，不是「行不存在」。**「需要哪些语言」推导出来的待翻项必须落到可查的行上，
   否则「这个商品的德语还没翻」只能靠「查哪些行不存在」这种反直觉口径得到，完整度报表也无从汇总。
   于是三态在 DB 层互斥且自带可判定形状：`PENDING` 要求名称与描述都为空、
   `REVIEWED` 要求有名称且**必须有校对人、必须有校对时刻**。
2. **只有 `REVIEWED` 可刊登。**「已翻」是机器译文或刚填的草稿，发到平台等于没做校对这一步。
   后端把 `publishable` 算成独立一列并用部分索引 `..._publishable ... WHERE status='REVIEWED'`
   支撑 04 刊登的取用路径；前端只显示，不重算。
3. **站点推导走端口，未接入就说「未接入」。** 店铺与站点是 02 渠道的事实
   （`shop` / `shop_marketplace` / `marketplace.default_locale`），catalog 禁止跨服务 SQL，
   所以定义 `TranslationLocaleProjectionPort` + `UnwiredTranslationLocaleProjectionPort`。
   未接入时返回 `available=false` 与一句归 02 渠道的说明，
   **不编语言列表、不回 0、不回空数组** —— 0 会被读成「没有语言需求」，
   空数组会被读成「不需要做多语言」，两者都是假事实，而且是本模块最危险的错误方向。
   同时提供 `translation_locale`（人工维护）作为真实可写入的一侧。
4. **AI 建议处置是终态。** 采纳错了直接改译文内容，不把建议改回待处置再走一遍 ——
   同一条建议两次采纳会产出两条不同的译文历史，无从判断哪次算数。

## 关键 trade-off

- **与 3.4.2.2 的边界（本模块最大的判断）。** V21 已经有带 `locale` 的
  `content_template_translation` / `content_render_section`，但那是「描述模板与其渲染段落」的
  多语言，主体是 `content_template`；本模块的主体是商品档案。不复用的理由逐条写进了
  V22 文件头：主体不同（一条模板译文能被 500 个商品复用）／字段集不同（模板译文要校验占位符
  集合相等，本表正文是**已填好的成品**，里面不允许再有 `{`）／删除语义相反（删模板要连带删译文，
  删一条商品译文只是「这个商品少一种语言」）／取值形状不同（V21 的 locale 取自模板的 `source_locale`，
  这里的取自店铺启用站点）。**两个事实来源合并是必须显式挡住的事，否则两边数字迟早对不上。**
- **两张表而不是一张多态表。** 蓝图点名了两张表，而且合表就没法建复合外键，
  「译文必须挂在一个真实存在的商品上」只能降级成触发器。代价是 SPU/SKU 各一套 CRUD，
  换来的是「删 SPU 连带删它的译文、删 SKU 只删自己的」由 FK 保证。
- **批量是同步的「已完成」，不是异步的「已受理」。** 三件批量（补待翻占位 / 批量采纳建议 /
  批量标记已校对）都是几百行短事务，做成异步只会让运营为几十毫秒去盯进度条。
  所以 `translation_batch.status` 只有 SUCCEEDED / PARTIAL / FAILED，
  失败与跳过**逐条落库**而不是只回一个计数 —— 「这批 50 个商品的德语有 8 个因为已有译文被跳过」
  要能逐条回答。
- **建议正文快照留在 `translation_suggestion`。** 采纳后译文会被人工改，
  快照让「当初 AI 给的是哪一版」仍然答得上来。
- **代理白名单里 `translations` 是顶层分支，不是 `descriptions` 里的。**
  `descriptions/translations/{id}` 是 3.4.2.2 的模板译文删除路径，它必须继续挂在 `descriptions` 之下；
  两条规则混用会让「模板译文」与「商品译文」在代理层就变成同一条路径。

## 文件清单

**迁移**

- `backend/services/catalog/src/main/resources/db/migration/V22__multilingual_content.sql`

**后端**

- `TranslationRules.java`（纯规则层：语言标签、三态与正文一致性、状态流转、占位符拒绝、容量上限）
- `TranslationService.java`（语言需求 / 译文 / 建议 / 完整度 / CSV / 合并视图）
- `TranslationBatchService.java`（三件同步批量 + 逐条结果）
- `TranslationController.java`（`/catalog/translations`）
- `TranslationLocaleProjectionPort.java` / `UnwiredTranslationLocaleProjectionPort.java`
- `CatalogModels.java`（追加 29 个 DTO record）

**测试**

- `TranslationIntegrationTest.java`（26 例，Testcontainers 真实 PostgreSQL 16，连接池固定 1 条）
- `TranslationRulesTest.java`（16 例）
- `apps/web/src/components/catalog/multilingual-model.test.ts`（29 例）
- `apps/web/src/app/api/catalog/[...path]/route.test.ts`（追加 3 例，正反断言）

**前端**

- `apps/web/src/components/catalog/multilingual-workspace.tsx`
- `apps/web/src/components/catalog/multilingual-model.ts`
- `apps/web/src/app/products/translations/page.tsx`
- `apps/web/src/lib/api-proxy.ts`（`translations` 白名单放行 + 中文注释）

**契约**（`pnpm contracts:generate --spec-dir backend/target/openapi` 重新生成，只增不改）

- `packages/api-contracts/src/catalog.ts`（+29 个 `Translation*` DTO）
- `packages/api-contracts/src/catalog.openapi.json`

**台账与会话**

- `docs/product/capability-blueprint/module-progress.md`（3.4.3.1 → 已交付）
- `docs/sessions/2026-10/2026-10-07-multilingual-content.md`（本文件）

## 开发过程中修掉的真实缺陷（都是自己写出来的，记下来免得重犯）

- `TranslationRules.statusTransitionProblem` 的条件写反了：文档说「带正文的译文不能退回待翻」，
  代码却判成「都为空才拒」。**一条方法两种语义是这类 bug 的温床，条件要逐字对一遍文档。**
- 译文列表的 UNION 分支里 join 了另一张商品表，于是 SQL 报
  `column t.sku_id does not exist`（`spu_translation` 行上没有这个列）。
  改成每个分支只 join 自己那张表；顺带发现筛选用未限定列名（`status` / `locale` / `name`）
  会撞 `spu`/`sku` 的同名列，必须全部带 `t.` 前缀。
- `reviewed_at` / `translated_at` 是 timestamptz，绑定时漏了 `?::timestamptz`
  （PG 报 `column is of type timestamp with time zone but expression is of type character varying`）。
- 批量先写逐条结果再写批次，当场撞 `translation_batch_item_batch_id_fkey`。
  改成先写批次（计数从 `plan()` 早就算出来了，不需要先执行）再逐条写。
- `toEntry` 从 `select *` 的行里取 `target_type` / 另一个主体列 —— 那种行里根本没有这些列。
  主体类型由调用方按定位到哪张表给出。
- 「整批没什么可做」永远不会触发：`plan()` 会把跳过/未执行也作为行返回，
  所以判据应是「没有一条可执行」而不是「列表为空」。

## 后续待办

- **02 渠道接入站点 → 语言的推导**：用真实适配器替换 `UnwiredTranslationLocaleProjectionPort`
  即可，`/locales/requirements` 与 `/locales/sync` 无需改动。在那之前语言需求靠人工维护，
  页面与接口都显式标着「未接入（归 02 渠道）」。
- **04 刊登填充**：按 `(spu_id|sku_id, locale)` 取 `status='REVIEWED'` 的行。
  部分索引 `spu_translation_publishable` / `sku_translation_publishable` 已为这条查询路径建好。
- **18 AI 翻译**：翻完调 `POST /catalog/translations/suggestions`（`origin='AI'` + `batchRef` 归组）
  写回候选，等 catalog 侧人工采纳或驳回。catalog 不主动调 AI —— 调它等于跨服务依赖调用顺序。
- **与 3.4.2.2 的关系**：模板译文与商品译文是两套 locale，谁要合并必须先在
  `docs/decisions/` 里立项（ADR），不能直接在 SQL 层 union 掉。
