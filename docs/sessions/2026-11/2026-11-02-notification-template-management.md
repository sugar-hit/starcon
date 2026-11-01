# 18.5.1.1 模板管理 —— 通知模板的版本化、渲染预览与按时刻解析（platform 服务）

> 蓝图 18-integration-and-automation.md 18.5.1.1：「管理通知与消息模板：模板内容、变量、适用场景、
> 语言版本、启用状态；修改后新产生的通知使用新版本，历史通知保留当时内容。输入：模板编辑；变量定义。
> 输出：模板记录 + 渲染预览。关系：上游 = 3.4.2 文案资产；下游 = 1.3.1.1、15.1.1.2、18.5.2。
> **模板必须版本化**，已发出的通知不能被新模板"篡改"。」
>
> 车道：18.5.1.1（阶段 D7，L3 锚点 18.5.1）· 分支 `feat/18-5-1-1` · 迁移 V427（platform）+ V427（identity）。

## 决策摘要

| 项 | 决定 | 为什么 |
| --- | --- | --- |
| 表结构 | **三张表**：主记录 + 不可变版本行 + **启用事件日志** | 主记录挂「当前版本指针」只能说明「现在是谁」，**说不清「昨天上午是谁」** —— 蓝图要的「按时刻解析」需要历史 |
| 「编辑」 | 只 INSERT 一行新版本，**老版本一个字节都不动** | GRANT 里**不给版本表 UPDATE / DELETE** + BEFORE UPDATE OR DELETE 触发器 ⇒ 不可篡改由**权限层**兜底，不是靠服务层记得 |
| 「同一 key + 语言只允许一个启用版本」 | **部分唯一索引**（`WHERE deactivated_at IS NULL`） | 数据库层写不进去第二条，比服务层检查可靠 |
| 模板 key | 小写机器名（`^[a-z0-9][a-z0-9._-]{0,63}$`）+ 语言规范化成 BCP-47（zh-cn → zh-CN） | 不规范化就会出现「同一个语言存成两条」的版本链 |
| 场景 | 受控词表（CHECK，7 个码） | 下游按场景取模板，自由文本会让「按场景取」退化成模糊匹配 |
| 渲染引擎 | 纯规则、不依赖任何外部系统；**失败一律点名报错** | 缺变量 / 未知变量 / 多余变量 / 语法错误 / 类型不符各有错误码与变量名；失败时正文返回 **null**（绝不空串、绝不残留 `{{xxx}}`） |
| 删除模板 | **归档 + 停用**（不物理删） | 版本行是历史通知「当时内容」的依据；物理删等于让那段历史说不清 |
| 上游 3.4.2 | `CatalogContentAssetPort` 只读 catalog 的 `/catalog/titles/templates` 与 `/catalog/description-templates/templates` | 跨服务只走授权 API；**只转发调用方自己的 bearer**，取不到一律 **503**（是「读不到」，不是「没有数据」） |
| 权限码 | 新登记 `platform.template.read / create / update / delete` | 与 18.4.2.1 的 `platform.text.*` **不是一件事**（客服话术草稿 vs 通知模板）；复用会让人以为可以拿「改文案」的权去改通知模板 |
| 契约命名 | 全部 `NotifyTemplate*` 前缀 | catalog 的 `Template*` 是**价格模板**的词汇；扁平契约里同名会被 codegen 判「字段级覆盖」并**中止** |
| api-proxy | **新增一条独立 services[] 元素**，锚点选在文件**中段** | 共享白名单是并行车道的累积文件；中段与开头/末尾的锚点各相距 12000 行以上 ⇒ 并入时 git 能干净求并 |

## 关键 trade-off

1. **多一张表（启用事件日志）换「按时刻解析」**。
   只在主记录上挂一个「当前生效版本」指针，实现最省事，但 resolve 只能回答「现在是谁」——
   蓝图要的「历史通知保留当时内容」会落空。代价是启用/停用要维护区间（`deactivated_at`），
   并且要用**部分唯一索引**把「同时只有一个生效版本」钉在数据库层。

2. **版本行严格 append-only（连 UPDATE 权限都不给）**。
   好处是「不可篡改」成为**结构事实**，测试里能直接断言「直连 SQL 改不动」。
   代价：任何「改某一版」的需求都得走「新增版本」，这正是蓝图要的语义。

3. **渲染失败返回 400 + 结构化清单**（而不是 200 + 空串）。
   后端其它 400 走 `ResponseStatusException`（MockMvc 里响应体是空的），
   但**渲染预览**这条必须带清单 —— 页面要显示「缺哪个变量」。
   所以预览端点由控制器直接返回 `ResponseEntity`（200/400 同一份结构）。

4. **契约用 `@Schema(nullable = true)` 标注可空字段**。
   codegen 的 `ts()` 遇到 `$ref` 属性会丢掉 `nullable` ⇒ `NotifyTemplateDetail.enabledVersion`
   这一条**表达不出来**，前端用 `enabledVersionOf()` 显式收窄（**不改 codegen 产物**）。

5. **api-proxy 正则写成「两段各自可选」而不是一个大 alternation**。
   实测踩过：大 alternation 里再套 `{uuid}` 段时括号多一层 ⇒ `{uuid}` 段整体变成可选 ⇒
   `ai/templates/{uuid}` **匹配不上**（页面 404，而界面上看不出是白名单写错了）。
   现在是「字面量段（可选） + `{uuid}` 段（可选）」两段并列。

## 文件清单

**迁移**
- `backend/services/platform/src/main/resources/db/migration/V427__notification_template.sql` —— 三张表 + RLS(FORCE) + 租户触发器 + 版本不可篡改触发器 + 启用日志指向不可改触发器 + 部分唯一索引 + GRANT
- `backend/services/identity/src/main/resources/db/migration/V427__platform_template_permission.sql` —— page_resource `platform.template` + 4 个权限码 + 存量 OWNER 补权

**后端（platform）**
- `backend/services/platform/src/main/java/com/starcon/platform/NotificationTemplateModels.java`
- `backend/services/platform/src/main/java/com/starcon/platform/NotificationTemplateRenderer.java`
- `backend/services/platform/src/main/java/com/starcon/platform/NotificationTemplateService.java`
- `backend/services/platform/src/main/java/com/starcon/platform/NotificationTemplateController.java`
- `backend/services/platform/src/main/java/com/starcon/platform/CatalogContentAssetPort.java`

**后端测试**
- `backend/services/platform/src/test/java/com/starcon/platform/NotificationTemplateRendererTest.java`（12 例）
- `backend/services/platform/src/test/java/com/starcon/platform/NotificationTemplateIntegrationTest.java`（22 例）
- `backend/services/platform/src/test/java/com/starcon/platform/NotificationTemplateTenancyIntegrationTest.java`（6 例）

**前端**
- `apps/web/src/app/ai/templates/page.tsx`
- `apps/web/src/components/ai/notification-template-model.ts`
- `apps/web/src/components/ai/notification-template-workspace.tsx`
- `apps/web/src/components/ai/notification-template-model.test.ts`（12 例）
- `apps/web/src/app/api/notification-template-allowlist.test.ts`（4 例）
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 services[] 元素**，+61 / −0）

**契约（codegen 产物，未手改）**
- `packages/api-contracts/src/contracts/platform.ts`（新增 24 个 `NotifyTemplate*` 类型）
- `packages/api-contracts/src/platform.openapi.json`

**台账**
- `docs/product/capability-blueprint/module-progress.md`（只改 18.5.1.1 那一行的「状态」与「证据」两格）

## 验证

| 项 | 命令 | 结果 |
| --- | --- | --- |
| L0 编译 | `cd backend && ./mvnw -q compile` | exit **0** |
| L1 模块闸 | `./mvnw -pl services/platform -am test -Dtest='NotificationTemplate*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **Tests run: 40, Failures: 0, Errors: 0** / BUILD SUCCESS |
| 类型检查 | `pnpm typecheck` | **3/3 successful** |
| Lint | `pnpm lint` | **3/3 successful**（本车道新增文件零告警） |
| 格式自检 | `pnpm exec prettier --check <本车道改动文件>` | 全绿 |
| 前端单测 | `pnpm vitest run apps/web/src/components/ai/notification-template-model.test.ts` | 12 例全绿 |
| **全量 allowlist 回归** | `pnpm vitest run apps/web/src/app/api` | **284 文件 / 1600 例全绿**（改了共享白名单，必须跑全量） |
| identity 迁移 | 在真实 PostgreSQL 16 上连跑两次 V427 | 幂等：1 条 page_resource + 4 个权限码 + OWNER 补权，无报错 |

## 后续待办

1. **18.5.2.1 投递记录（未交付）**：本模块留出的接口是 `GET /platform/ai/templates/resolve` 的
   **版本 id + 内容快照**；投递记录落库时引用这个版本 id（而不是「当前版本」），
   才谈得上「历史通知保留当时内容」。投递侧还要记**退订**（蓝图加粗句）。
2. **1.3.1.1 通知列表（identity）/ 15.1.1.2 发送（crm）**：跨服务，下游按
   `template_key + scene + language` 取内容；本模块**不跨库读写对方表**。
3. **导航注册**：`packages/ui/src/lib/task-pages.ts` **刻意未登记**（多条车道并发改同一文件必然冲突）
   ⇒ 待调度者统一处理 `/ai/templates` 的菜单项。
4. **语言词表**：当前语言只做 IANA 形状校验，**不**做成租户数据（与租户无关的语言事实不落库）。
   若将来要「按店铺启用的站点推导需要哪些语言」，那是 3.4.3 的口径，不是本模块的。
