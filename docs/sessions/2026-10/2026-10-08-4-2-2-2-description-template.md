---
title: 4.2.2.2 描述模板（阶段 D5）
date: 2026-10-08
module: 4.2.2.2
lane: feat/4-2-2-2
status: 已交付
---

# 4.2.2.2 描述模板

## 决策摘要

蓝图原文（`04-listing-and-content.md` L146）：

> 职责：管理富文本描述模板：段落结构（产品介绍、使用场景、规格参数、注意事项、售后承诺）、变量占位、多语言版本、图片插入位。
> 输出：描述模板 + 渲染后的描述 HTML（供编辑器与平台上传）。
> 关系：上游 = 3.4.2.2 描述库、3.4.3 多语言；下游 = 4.2.1.1。
> **变量未绑定必须显式提示**，不允许把 `{{尺寸}}` 原样发到平台。

### 与 V21（3.4.2.2）的取舍：混合复用，不是全复用也不是另起炉灶

V21 已经交付了 `content_template` / `content_template_variable` /
`content_template_translation` / `content_phrase` / `content_render*` 一整套机制，
并且已经把「变量缺失必须报告」钉在 DB 层。所以本模块**逐项判断**：

**复用（不另立表）**

| 复用对象                       | 理由                                                                                                   |
| ------------------------------ | ------------------------------------------------------------------------------------------------------ |
| `content_template_variable`    | 11 个绑定源枚举与取值代码都在 3.4.2.2。另建一张变量表 ⇒ `{{尺寸}}` 有两个「取自哪里」的答案            |
| `content_template_translation` | origin / status 与「译文占位符必须与源语言相等」都在 3.4.2.2。复制一份 ⇒「这段英文是谁写的」有两个答案 |
| `content_phrase`               | 使用场景段绑 `PHRASE_SCENE`、注意事项段绑 `PHRASE_CAUTION`，本来就是同一个池子                         |
| `content_template.body`        | 一个段落 = 一行 `content_template`，`description_template_section` 只负责挂进五段骨架并排序            |

**另立（V21 确实解决不了）**

| 新表                                                    | 为什么 V21 里无处安放                                                                                                                                                      |
| ------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `description_template` / `description_template_section` | V21 是「一整段文本 + 一个 content_type」，蓝图要的是**一个模板内部五类段落**且顺序可查。糊成一个大字符串后，「规格参数段还没配英文」就答不出来                             |
| `description_image_slot`                                | V21 全程纯文本，没有任何图片概念                                                                                                                                           |
| `description_render*`（4 张）                           | V21 的 `content_render_section` 有 `UNIQUE(tenant_id, render_id, sku_id)`：**一个批次里同一 SKU 只能落一段**，所以「一个模板 → 五段结构化 HTML」在它的渲染表里根本表达不了 |

**代码层落点**：`DescriptionTemplateService.saveSectionContent()` 建/改的是
`content_template` 行，不是第二张表。测试
`fiveSectionSkeletonPersistsAndReusesV21Tables()` 是这条判断的回归护栏——
哪天有人在本模块另起变量表，它会红。

## 关键 trade-off

1. **缺译文不整批拒，逐段降级。**
   3.4.2.2 的批量填充一次填几十个 SKU，缺译文会让整批报废所以提交前就拦；
   本模块一次渲染一个 SKU，缺一段译文按「该段报 `TRANSLATION_MISSING` 并降级
   `INCOMPLETE`」更有用（运营立刻看到「就差英文」），且**绝不悄悄退回源语言**。

2. **缺段落类型不拦保存，只在概览里报缺口数。**
   很多商品本来就没有独立售后承诺段，硬要求五段齐全只会逼运营填废话。
   DB 上还有 `UNIQUE(tenant_id,template_id,section_type)` 兜底重复类型。

3. **图片 `src` 的来源要说清楚。**
   V18 的 `media_asset` 只有 `storage_key`（文件头明写「对外 URL 由读取端现签」），
   本仓没有可直出的公开 URL。所以规则是「有已解析 URL 就用它，没有就退回
   `/api/media/{storage_key}` 这条约定路径」——两条都不是假地址。
   必填位取不到图时产 `IMAGE_UNBOUND` 报告并把该段降级，**绝不返回占位图**。

4. **`UNBOUND_PLACEHOLDER` 在正常路径上不可达。**
   保存期的 `variableBindingProblem` 会先拒掉没声明绑定的占位符。
   这条 issue_kind 是给「模板改版与译文不同步」那类漂移留的兜底。测试里对此有注释说明。

## 文件清单

**迁移**（V96，版本号由调度器预分配）

- `backend/services/catalog/src/main/resources/db/migration/V96__description_template_layout.sql`

**后端**

- `DescriptionTemplateRules.java` — 纯规则层（词表 / 上限 / 校验 / HTML 白名单转义）
- `DescriptionTemplateRenderer.java` — 纯函数渲染引擎（五段拼装 + `<img>` 落点）
- `DescriptionTemplateService.java` — 模板 CRUD + 段落 + 插图位
- `DescriptionTemplateRenderService.java` — 渲染 + 落库 + 列表
- `DescriptionTemplateController.java` — `/catalog/description-templates`
- `CatalogModels.java` — 新增 17 个 record（只追加，未改动既有 record）

**测试**

- `DescriptionTemplateIntegrationTest.java` — 20 个用例，真实 PostgreSQL 16 + 强制 RLS
- `DescriptionTemplateRendererTest.java` — 11 个纯函数用例

**契约**

- `packages/api-contracts/src/catalog.ts` / `catalog.openapi.json`（codegen 产物，只由
  `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 生成）

**前端**

- `apps/web/src/components/catalog/description-template-model.ts`
- `apps/web/src/components/catalog/description-template-workspace.tsx`
- `apps/web/src/app/listings/templates/page.tsx`
- `apps/web/src/lib/api-proxy.ts` — catalog 白名单新增 `description-templates` 分支（**求并**）
- `apps/web/src/lib/api-proxy-union.test.ts` — 新增 catalog 层 21 条双向断言

## DB 级硬约束（V96）

「变量未绑定必须显式提示」的双重落点（端点级由 `DescriptionTemplateRenderer` 剥空 + 逐条报告负责）：

1. `description_render_section` CHECK `status<>'COMPLETE' OR html NOT LIKE '%{%'`
2. `description_render_section` CHECK `status<>'COMPLETE' OR char_length(html)>0`
3. 触发器 `guard_description_render_section_html`（BEFORE INSERT/UPDATE，逐条兜底）
4. 触发器 `guard_description_render_section_complete`（INCOMPLETE → COMPLETE 被拒）
5. `description_render` CHECK `status<>'SUCCEEDED' OR html NOT LIKE '%{%'`
6. **`description_render` CHECK `status<>'SUCCEEDED' OR (incomplete_count=0 AND issue_count=0)`**

第 6 条是实现过程中发现的缺口并补上的：一个 PARTIAL 渲染的 HTML 是**干净的**
（占位符已被剥掉），所以只靠第 5 条的话，把 `status` 从 PARTIAL 改成 SUCCEEDED
就能让一份「brand 没填上」的描述标成可发布。集成测试专门断言这一条。

## 跨车道约束回归

V96 在 V21 的 `content_template` 上挂了一个触发器
`refuse_description_section_content_delete`：只在「该 content_template 已是某个描述模板的段落」
时拦，对 3.4.2.2 自己的模板行为完全不变（V21 自己的
`refuse_content_template_delete` 照旧生效，两者并存互不覆盖）。

按车道纪律回头重跑了被约束到的既有模块：

```
./mvnw -pl services/catalog test \
  -Dtest='DescriptionLibraryIntegrationTest,DescriptionRendererTest,DescriptionRulesTest'
→ Tests run: 47, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS
```

## 顺手发现的一个既有缺陷（不在本车道，未修）

`api-proxy.ts` 的 catalog 白名单里字面量包含 `translation-coveragetranslations`
（早先一次求并时丢了一个 `|`），导致 **3.4.2.2 自己已交付的路由**
`descriptions/templates/{uuid}/translations` 在浏览器端静默 404——
而 typecheck / lint / vitest / Playwright 全绿，正是本文件要抓的失败形态。

**本车道没有修**：那个分支属于 3.4.2.2 的车道。已在
`api-proxy-union.test.ts` 里以 `CATALOG_KNOWN_BROKEN` 断言「当前确实是 404」，
并写明修法与归属；等 3.4.2.2 修好那条 token，这个断言会红，届时把条目移进 `CATALOG_ALLOW`。

## 后续待办

- **4.2.2.1 刊登模板**：与本模块同挂 `/listings/templates`，交付后把该页改成页签
  （与 `/products/content` 里标题库 / 描述库用页签分开同一手法）。
- **4.2.1.1 刊登编辑器**：取 `description_render` 里 `status='SUCCEEDED'` 的 `html`；
  要结构化视图时取 `description_render_section` 的逐段 HTML 与
  `description_render_image` 的 `position` / `alt`。
- **`SKU_SPEC` 的空值**：该绑定源取 `sku.variant_signature`，而后者由规格轴属性算出。
  SPU 没有规格轴时它为空串，渲染会如实报 `EMPTY_FACT`。当前测试夹具没有规格轴，
  所以标准模板改用 `SKU_NAME` + `SKU_CODE`；要不要为它加一条更贴切的
  `EMPTY_SIGNATURE_AXIS` issue_kind，留给 3.4.2.2 判断。
