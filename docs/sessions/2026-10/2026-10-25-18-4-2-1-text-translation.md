# 2026-10-25: 18.4.2.1 文本与翻译（崩溃恢复重派）

## 摘要

交付蓝图 18「集成开放与智能自动化」→ 18.4「AI 与智能」→ 18.4.2「AI 能力」→ **18.4.2.1「文本与翻译」**。

**本车道是崩溃恢复重派。** 上一轮 worker 在 2026-10-25 10:20 左右因 `ERR_CONNECTION_CLOSED`
被运行时杀掉，**写了一半、没 commit**。本轮接手时工作区里已有 13 个未跟踪文件（2 个迁移 +
9 个 Java 主源码 + 2 个测试类），**上一轮的 diff 为空**（全部是新增文件）。

本轮**没有推倒重写**：读完既有代码后确认上一轮的设计（Port 抽象 + `Unavailable*` 降级实现）
是对的，照着补完，并**就地修掉三个实质缺陷**（见下节）。

## 接手情况：读懂了什么 / 补完了什么 / 改了什么

上一轮写完的部分（**未提交，本轮一并提交**）：

- `V380__text_translation.sql`（platform，七表 + RLS + GRANT）—— **写完了，无需补**；
- `V380__platform_text_permission.sql`（identity，6 个权限码 + 存量 OWNER 补权）—— **写完了**；
- `AiTextGenerationPort` / `UnavailableAiTextGenerationPort` —— 端口 + 降级实现，**写完了**；
- `CatalogTranslationPort` / `ChannelListingPort` / `PlatformUpstreamClient` —— 跨服务只读端口，**写完了**；
- `TextQualityPipeline`（764 行，五条确定性规则 + 置信度四维加权）—— **写完了**；
- `TextTranslationModels` / `TextTranslationService` / `TextTranslationController` —— **写完了**；
- `TextTranslationIntegrationTest`（25 例）/ `TextTranslationTenancyIntegrationTest`（6 例）——
  **写完了，但测试里有 4 例是红的**。

上一轮**没写**的部分（本轮补上）：

- **前端整块缺失** —— 页面、模型、工作区、两个前端测试、api-proxy 白名单条目，**一个都没有**；
- **OpenAPI 契约**没重生成；
- **台账**没更新；
- **会话日志**没写；
- **没有 commit**。

### 就地修掉的三个实质缺陷

1. 🔴 **catalog 上游的 SPU/SKU 过滤参数写错了（生产级 bug）**。
   `TextTranslationService.sourceCandidates` 里判据写成 `"SPU".equals(type)`，
   而 `type` 的取值是 `CATALOG_SPU` —— **两边永远不相等** ⇒ `spuId`/`skuId` 两个过滤参数
   永远是 `null` ⇒ 上游收到的查询**不带任何 SPU/SKU 过滤**，返回的是全量条目。
   后果：「查某一个 SPU 的译文」实际变成「把所有 SPU 的译文都拿回来」。
   已改为按 `CATALOG_SPU` / `CATALOG_SKU` 判定；同时把测试桩改成**回显收到的 spuId**，
   让「上游到底有没有被按 SPU 过滤」成为一条**可断言的事实**而不是「返回了 1 条」这种看不出区别的假象。

2. **发布闸的状态语义错了（400 → 409）**。
   「参数没毛病、是**当前状态**不允许发布」被报成 400（参数错）—— 运营会去改一份根本没问题的内容。
   已把「未确认就发布」以及同族的「已发布后再改 / 已驳回后再确认 / 已发布后再驳回 / 已发布后再生成」
   统一成 **409**；而「驳回没写原因」仍然是 **400**（那确实是参数缺失）。
   分界写进了代码注释：参数本身合法但状态不允许 ⇒ 409；参数缺失/越界/格式不对 ⇒ 400。

3. **测试夹具用 `Map.of` 造带 null 值的 JSON ⇒ NPE**。
   `Map.of` 对 null 值直接抛 NPE，而上游真实返回里「这一列没有值」是常态（SPU 行的 `skuId` 为空）。
   夹具自己先炸成 500，本服务如实翻成 503 ⇒ 测试看到的是「上游不可达」，
   **真正的错（夹具构造错了）反而被藏起来**。已改为允许 null 值的 map 构造。

另修上一轮**一处测试自身写错**：模板用例拿**词表内**的 `buyer_name`/`order_no` 断言 400 ——
那等于把「任何客服话术都存不进来」变成既定行为。已改为断言 200（并断言解析出的变量清单），
词表外的 `not_a_variable` 仍然是 400。

## 决策 / 结论

1. **🔴 AI 通道是显式的未接入端口，不做假输出。**
   本仓 backend / services / tools 下**不存在任何真实的 AI / LLM 供应商适配器**
   （openai / anthropic / qwen / deepseek 之类的客户端一个都没有）。
   处置：`AiTextGenerationPort` + `UnavailableAiTextGenerationPort`，与 crm 的
   `UnavailablePlatformMessageSendPort` **完全同构** —— 生成端点如实答 **409**，
   **一次数据都不写**（不落草稿、不落状态、不留任何「已翻译」的痕迹）。
   理由：一条返回成功结果的假通道会让「文案已经翻译好了」在数据上看起来发生过，
   而现场一个字都没翻译出来 —— 运营会拿着一条不存在的译文去发版，
   而蓝图加粗句「翻译结果必须人工确认后发布，直接发机器翻译是品牌风险」防的正是这件事。
   归属写 **18.4（卷级）**而不是编一个不存在的 L4 编号：供应商适配器是 18.4 各模块共用的基础设施。

2. **🔴 与 AI 无关的部分全部真做完**（不是「最小可行」）：
   术语保护（品牌词缺失 ⇒ **FAIL** 直接拦住发布）/ 占位符完整性（封闭变量词表 +
   一个不少一个不多）/ 违禁词命中（租户词表 + 内置合规红线 + 邮箱电话正则兜底）/
   长度（按 Unicode **码点**计）与字符集 / **人工编辑**（追加版本，旧版本留着）/
   **人工确认 + 发布双闸** / 客服话术模板的变量绑定 / 置信度的**可解释**四维加权。
   运营现在就能走「人工录入译文 + 确定性检查 + 人工确认发布」这条**完整路径**。

3. **🔴 「人工确认后发布」落到数据库层，不靠服务层记得检查。**
   三条 CHECK 把「状态与事实一致」钉死（CONFIRMED/PUBLISHED 必须有 confirmed_at、
   PUBLISHED 必须有 published_at、有草稿的三个状态必须挂 current_draft_id、被驳回必须写原因）。
   读路径上「下游可取」只返回 PUBLISHED 的行 —— **未确认的译文取不走**。

4. **🔴 置信度的不适用维度按剩余维度重新归一**（NULL ≠ 0.0000）。
   没有术语可保护的任务，术语覆盖率**没有信息量**，算成 0 是在惩罚一件本来就不该扣分的事。
   前端同步把「不适用」显示成「不适用」而不是 0%；**SKIPPED 是「没查」不是「通过」**（灰色）。

5. **内置词表是「规则」不是「数据源」**（照 crm `ReplyWordingChecker` 的纪律）：
   语言层面的合规红线写死在服务层；租户可配的部分（品牌词、专业术语、店铺自己的违禁词、
   长度与字符集上限）落库，**系统零预置行** —— 不替用户默认一套词表或长度上限。

6. **🔴 跨服务只读，只转发调用方自己的 bearer**（不传租户号）。
   租户隔离因此是**两道**（platform 侧 RLS + 上游侧 RLS），不是「本服务替对方决定租户」。
   **取不到一律 503**，消息里写明「读不到」而不是「没有数据」—— 空集合在文案工作区里
   会长成「上游没有需要翻译的文案」，而真相是「这一格没算出来」。
   不建跨服务 FK、不抄译文（译文是外部事实，抄一份就会漂移）。

7. **🔴 identity 权限码补缺**：端点用 `access.require(bearer, "platform.text." + 权限)` 强制鉴权，
   但 identity 的 `permission` 表里**从来没注册过** `platform.text` 任何一行
   ⇒ 「没有任何角色可能持有 `platform.text.read`」⇒ 这些端点在**真实环境里恒 403**。
   「人工确认」「发布」「驳回」是**三个独立的码** —— 能改文案的人不该顺手把「已确认」也点了。
   存量 OWNER 用 `LIKE 'platform.text.%'` 补权（新建租户由 `create_child` 自动拿到，存量租户不会）。

8. **前端路由取 `/text`**（顶层），不放在 `/integrations` 下 ——
   `/integrations` 是 18.1.1.1「谁可以接入我们」，18.4 是「AI 与智能」，两者是同卷不同 L3。
   与 18.1.1.1 一致，**没有**改共享的 `packages/ui` 导航配置（多车道共用文件，不引入冲突）。

9. **api-proxy 白名单新增一条独立 `services[]` 元素**（`prefix: 'platform/'`），
   锚在 **18.1.1.1 那条之前**、数组末尾之前约 56 行 —— 同批其它车道的插入点是
   「数组最开头」与「数组最末尾」，本车道与前者相距 12000 行以上、与后者相距 56 行，
   两个 hunk 各自独立，并入时 git 能干净求并。
   两条同为 `platform/` 前缀但**正则不相交**（首 token `apps` vs `text`）⇒ 天然互斥。
   既有 18.1.1.1 那条**逐字未动**，并由前端测试**断言不误伤**。

## 文件清单

**迁移**

- `backend/services/platform/src/main/resources/db/migration/V380__text_translation.sql`（七表 + RLS + GRANT）
- `backend/services/identity/src/main/resources/db/migration/V380__platform_text_permission.sql`（6 个权限码）

**后端主源码**（`backend/services/platform/src/main/java/com/starcon/platform/`）

- `TextTranslationService.java`（服务层，1491 行）
- `TextTranslationController.java`（22 个端点，挂 `/platform/text`）
- `TextTranslationModels.java`（传输模型）
- `TextQualityPipeline.java`（五条确定性规则 + 置信度四维）
- `AiTextGenerationPort.java` / `UnavailableAiTextGenerationPort.java`（AI 通道端口 + 降级实现）
- `CatalogTranslationPort.java` / `ChannelListingPort.java` / `PlatformUpstreamClient.java`（跨服务只读）

**后端测试**

- `backend/services/platform/src/test/java/com/starcon/platform/TextTranslationIntegrationTest.java`（25 例）
- `backend/services/platform/src/test/java/com/starcon/platform/TextTranslationTenancyIntegrationTest.java`（6 例）

**前端**

- `apps/web/src/app/text/page.tsx`（页面）
- `apps/web/src/components/text/text-translation-model.ts`（模型 + 展示口径）
- `apps/web/src/components/text/text-translation-workspace.tsx`（工作区）
- `apps/web/src/components/text/text-translation-model.test.ts`（20 例）
- `apps/web/src/app/api/platform/text-translation-allowlist.test.ts`（4 例）
- `apps/web/src/lib/api-proxy.ts`（**新增一条** services[] 元素，既有条目未动）

**契约 / 台账 / 日志**

- `packages/api-contracts/src/contracts/platform.ts`（codegen，+27 个 `Text*` 类型）
- `packages/api-contracts/src/platform.openapi.json`（codegen 快照）
- `docs/product/capability-blueprint/module-progress.md`（**只有 18.4.2.1 那一行** + 脚本自动重算的统计）
- `docs/sessions/2026-10/2026-10-25-18-4-2-1-text-translation.md`（本文件）

## 上游依赖自检（发车前 10 分钟，集成头逐条核对过）

| 上游 | 蓝图写的 | 实际存在 | 结论 |
| --- | --- | --- | --- |
| 3.4.3 多语言 | catalog | `V22__multilingual_content.sql` + `GET /catalog/translations/locales` + `GET /catalog/translations/entries` | ✅ 已交付，响应形状逐字段比对过 |
| 4.2.1.1 编辑器 | channel | `V194__listing_editor.sql` + `GET /channel/listing-editor/drafts/{id}` | ✅ 已交付，`EditorDraftRow` 字段对得上 |
| 15.1.1.2 发送 | crm | `V297__crm_message_send.sql` | ✅ 已交付，封闭变量词表（11 个）逐字复用 |
| 18.1.1.1 样板 | platform | `V377__platform_app_credential.sql` | ✅ 已交付，Port 抽象与降级先例照抄 |

`TranslationLocaleRow` / `TranslationLocalePage` / `TranslationEntryPage` 与
`EditorDraftRow` / `EditorFieldValue` 的字段逐条比对过（上游多出来的字段用
`@JsonIgnoreProperties` 忽略，类型不匹配照样炸）。

## 验证

- **L0 编译闸**：`cd backend && ./mvnw -q compile` → 绿（exit 0）
- **L0 类型闸**：`pnpm typecheck` → 绿（3/3 tasks successful）
- **Lint**：`pnpm lint` → 绿（3/3 tasks successful；输出里的 warning 全在别的模块的既有文件上）
- **L1 模块闸**：
  `./mvnw -pl services/platform -am test -Dtest='TextTranslation*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false`
  → **Tests run: 31, Failures: 0, Errors: 0**（`TextTranslationIntegrationTest` 25 + `TextTranslationTenancyIntegrationTest` 6）
- **前端本模块测试**：`pnpm vitest run apps/web/src/components/text/text-translation-model.test.ts apps/web/src/app/api/platform/text-translation-allowlist.test.ts` → **22 例全过**
- **api-proxy 全量 allowlist 回归**（改了共享白名单，必须跑）：`pnpm vitest run apps/web/src/app/api` → **249 文件 / 1406 例全过**
- **契约**：`python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` →
  9 个服务 6978 个 DTO；**只有 platform.ts 与 platform.openapi.json 变化**，其余 8 个服务逐字节不变；
  用旧/新 spec 的 path 集合与 schema 集合逐条对过：**零路径丢失、零 schema 丢失**，
  新增 21 条路径 + 27 个 schema
- **未跑**：`./mvnw verify` 全量、整个 platform 服务的测试、`pnpm --filter @starcon/web build`
  （按任务书由调度器在集成头统一跑）

## 后续待办

1. **18.4 的 AI 供应商适配器仍未交付** —— 本模块的「AI 自动翻译 / 标题生成 / 卖点提炼」
   三个能力在页面上如实显示为「未接入」，归 **18.4**（卷级）。
   适配器交付后，只要换掉 `UnavailableAiTextGenerationPort` 的实现，`POST /platform/text/tasks/{id}/generate`
   立刻变成真的 —— 服务层已经把「生成结果落成一版可编辑草稿」的路铺好了。
   ⚠️ 注意：适配器落地时必须把 `protectedTerms` 真的进到供应商的提示词里，
   不能只是本模块自己记着（品牌词译错是品牌风险）。
2. **18.4.3.1 使用与配额**：AI 用量与成本的统计归那一格；当前 AI 通道未接入，没有可计量的调用。
3. **18.4.3.2 效果评估**：翻译采纳率是那一格的口径；本模块已留出「人工确认 / 驳回」的留痕。
4. **18.4.2.2 选品与需求预测 / 18.4.2.3 异常诊断**：同卷兄弟，本车道**不依赖**它们，
   也**没有**顺手实现它们。
5. **译文回流到刊登编辑器 / 消息发送**（下游 = 4.2.1.1 / 15.1.1.2）：
   本模块只把译文准备好并交出去（`GET /platform/text/published`），
   **不**回写对方的服务 —— 回流是那边的模块的事。
