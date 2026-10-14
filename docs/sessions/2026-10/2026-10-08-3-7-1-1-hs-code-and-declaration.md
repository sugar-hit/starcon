# 3.7.1.1 HS 编码与申报要素

## 结论

能力蓝图 **3.7.1.1「HS 编码与申报要素」** 收口：后端（`V94__customs_hs_declaration_elements.sql`

- `CustomsAttributeController/Service/Rules`）此前已由本分支完成并经复跑验证，
  本轮补齐**前端三件套**（`customs-attribute-model.ts` / `customs-attribute-workspace.tsx` /
  `customs-attribute-model.test.ts`）、api-proxy 白名单、真 proxy 状态码回归、契约重生成、
  台账与 V28 过期注释。

蓝图这一格只有一句限定语，但它决定了整个模块的形状：
「HS 编码错误直接导致申报失败与罚款，**必须允许审核流程而非任意填写**」。

## 决策摘要

### 1. 审核权限是**第三个**权限，页面必须区分「能编辑」与「能审核」

读 `catalog.compliance.read`、写 `catalog.compliance.update`、
审 `catalog.compliance.review`。审单独拆出来的理由不是洁癖：
HS 码错一次就是扣货 + 罚款，而填码的人恰恰是最容易「顺手自己批」的人。
前端 `writable()` 与 `reviewable()` 两个函数、页面上两组按钮，
就是为了让「有 update 没 review」的角色看到自己只能提交、批不了。

### 2. 「可报关性」与「字段填全了」在界面上必须是两件事

后端 `declarationReady=false` 有六种成因（`NOT_RECORDED` / `NOT_APPROVED` /
`SUBMITTED_AWAITING_REVIEW` / `DECLARATION_REJECTED` / `NO_ELEMENTS` /
`REQUIRED_ELEMENTS_INCOMPLETE`），下一步动作两两不同：去填 / 等审 / 改后重提 / 补要素。
塌缩成一个「未就绪」，运营就只能猜。

页面顶部一条横幅说结论，下面**逐条列中文原因**（后端已给 `blockerLabels`），
只有英文码可用时前端再兜一层中文 —— 把 `REQUIRED_ELEMENTS_INCOMPLETE`
甩给运营等于让运营自己翻译。

### 3. 审核态在界面上是**五种**不是三种

后端 `status` 只有 DRAFT / APPROVED / REJECTED，但「草稿」与「已提交待审」
在 `status` 上是同一个 DRAFT（靠 `submittedAt` 区分），而这两个状态下一步动作相反
（去提交 vs 等审核）。前端 `reviewState()` 拆成
`NOT_RECORDED / DRAFT / AWAITING_REVIEW / APPROVED / REJECTED`。

### 4. 保存是一次提交写两张表，位次由客户端按行序重排

`PUT /skus/{id}` 的 `elements` 是**完整清单**（位次唯一 + required 非空都是跨行不变量，
增量提交要前端自己算最终集合，漏对账会留下幽灵位次）。
位次（DB 有 `UNIQUE(tenant_id,customs_attribute_id,ordinal)` 且 CHECK > 0）
在草稿里不让用户手填，`draftToPayload` 按行序重排为 `1..n` ——
手填只会制造后端必然拒绝的请求，而行序本身已经表达了位次。

### 5. 提交 / 通过 / 驳回一律二次确认

这三个动作直接决定这份资料能不能报关，确认框写**后果**而不是弹「确定吗」；
驳回还要强制填原因（DB CHECK 也钉着），不写理由运营只能猜要怎么改。

## 关键 trade-off

- **前端只做「一定会被后端拒绝」的校验**，且逐条对齐后端 `validate`
  （HS 6–10 位纯数字、ISO 3166-1 alpha-2、享惠与协定代码双向自洽、申报价值 ≤6 位小数）。
  不做「前端更宽松」的美化：放宽只会把错误推迟到点保存那一刻。
- **金额只用字符串收发**。`declaredValueReference` 在草稿里是字符串，
  发出去前才转 number，校验走正则不走 `Number()` 运算 —— 禁浮点是硬约束，
  前端更不能拿浮点参与比较。
- **契约生成撞名**：`CustomsSaved` 这个名字 10.5.1.1 的 warehouse 报关单据
  已经占了，而生成器把**所有服务**的 schema 拍平进同一个 `catalog.ts`，
  warehouse 在 catalog 之后合并 → 本模块同名 record 会被**静默覆盖**，
  前端 import 到的是字段完全不相干的 warehouse 形状，且 typecheck 全绿。
  已把 catalog 侧改名为 `CustomsProfileSaved`（**只改 OpenAPI component 名，
  JSON 字段仍是 `profile`，接口行为不变**）。

## 文件清单

| 文件                                                              | 动作                                                 |
| ----------------------------------------------------------------- | ---------------------------------------------------- |
| `apps/web/src/components/catalog/customs-attribute-model.ts`      | 新增：端点封装 + 审核态 + 阻断原因 + 草稿校验        |
| `apps/web/src/components/catalog/customs-attribute-workspace.tsx` | 新增：真实工作区（三态 + 二次确认 + 要素高亮）       |
| `apps/web/src/components/catalog/customs-attribute-model.test.ts` | 新增：49 例                                          |
| `apps/web/src/lib/customs-attribute-api-proxy.test.ts`            | 新增：47 例**真 proxy 状态码**回归（放行 + 反向）    |
| `apps/web/src/lib/api-proxy.ts`                                   | 白名单加 `customs-attributes` 五种形状 + 注释        |
| `apps/web/src/app/products/sku/[id]/customs/page.tsx`             | 新增：路由页（否则工作区不可达）                     |
| `packages/api-contracts/src/*`                                    | codegen 重新生成（1318 DTOs）                        |
| `backend/.../CustomsAttributeModels.java` 等 4 处                 | `CustomsSaved` → `CustomsProfileSaved`（解契约撞名） |
| `backend/.../V28__physical_safety_attributes.sql`                 | 只改注释：3.7.1.1 已交付、3.7.1.2 仍未开发           |
| `docs/product/capability-blueprint/module-progress.md`            | 3.7.1.1 一行状态+证据（另 5 行是派生统计）           |

## api-proxy 白名单的验证方式

白名单**只在浏览器端生效**，`pnpm test` 直连服务层永远测不出代理 404 ——
这正是本文件历史上连咬 4 次（8.2.1.2 / 10.4.1.1 / 10.4.2.1 / 10.5.1.3）的原因。

所以测试不是把正则抄出来自己测，而是：

1. **放行用例 5 条 + 反向用例 11 条**，打的是**真 `proxy` 处理器返回的 HTTP 状态码**
   （放行 = 200，未命中 = 404）。反向用例是必须的：没有它，一条「全部拒绝」的
   正义正则能让所有放行用例都变绿。
2. **路径不是手抄的**：调用 `customs-attribute-model` 里真正被调用的函数、
   捕获浏览器实际发出的 URL，再把这条 URL 原样喂给真 proxy。组件改路径这里就会红。
3. **反向回归 22 条已交付 catalog 路由**，钉住「白名单是求并，加新分支不许删旧路由」。
4. **变异验证**：手动删掉白名单分支后 8 条断言转红，确认测试不是空转。

写测试时踩到的一个真坑：`api()` 拼的是 `/api/<route>/<path>` 而 path 自带服务前缀，
所以 catalog 的真实地址是 `/api/catalog/catalog/...`（服务名出现两次）。
按直觉写成 `/api/catalog/...` 时，连 `products`、`brands` 这些早已交付的路由
也会一起变红 —— 那说明是测试写错了，不是白名单坏了。

## 后续待办

- **`physical-safety`（3.7.2.1）仍不在 api-proxy 白名单里**：`/products/sku/[id]/physical`
  页面在浏览器里会 404，而 typecheck / lint / vitest 全绿。本轮按约定**没有顺手加**
  （属另一个模块范围），需要单独收口。
- SKU 详情页目前**没有**指向 `/products/sku/[id]/customs` 的入口
  （物理与安全属性有 `sku-detail-workspace.tsx` 的入口卡）。本轮未改共享的
  `sku-detail-workspace.tsx`，避免与并行车道在同一文件行级冲突。
- 10 报关（V85 `customs_declaration_line`）的回填尚未接线：本模块已把
  `declarationElements` 生成为 jsonb 对象形态、键名与 V85 列逐一对应，
  V85 交付后可直接回填并把来源改成 `MODULE_3_7_1_1`，**不需要改结构**。
- `CustomsSaved` 撞名是生成器层面的隐患：schema 拍平进单文件时同名会静默覆盖。
  本轮只解开自己这一处，生成器本身未改（改它要动所有服务的快照，超出本车道范围）。
