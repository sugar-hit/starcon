# 2026-10-22 · 15.2.1.2 评价回复（crm）

> 车道 `15.2.1.2`，分支 `feat/15-2-1-2`，工作树
> `starcon-worktrees/integration/.worktrees/15.2.1.2`。
> 蓝图为 `docs/product/capability-blueprint/15-crm-and-service.md` 的 15.2.1.2 节。

## 决策摘要

**蓝图原文**：职责「回复评价（公开回复 + 部分平台的卖家评论追加），支持模板、多语言、批量回复同类评价」；
输入「回复内容；目标评价」；输出「回复记录 + 平台回执」；关系「上游 = 15.2.1.1；下游 = 平台接口」；
**加粗句「差评回复是转化机会也是合规风险，模板必须符合平台措辞规范」**。

**四条能力全部真做，只有「平台投递」如实答未接入**：

| 蓝图要求 | 落地 |
| --- | --- |
| 公开回复 + 卖家评论追加 | `reply_type ∈ {PUBLIC_REPLY, SELLER_COMMENT_APPEND}`，两个形态各自要配各自的措辞规范 |
| 模板 | `crm_review_reply_template`：**一行 = 一个版本**（同 `template_key` 下 `version` 递增），带 `effective_from/effective_to`；已生效的模板内容由触发器冻结，改内容要发新版本 |
| 多语言 | `crm_review_reply_template_variant` 逐 locale 落库；请求语言缺失时用模板的兜底语言，回复记录记**实际**用掉的语言 |
| 批量回复同类评价 | `crm_review_reply_batch`：「同类」= 同平台 × 同店铺 × 同档位 × 同形态 × 同语言，从 `crm_product_review` 真筛、逐条落库、跳过条数与**每条原因**都落库 |
| 平台回执 | `PlatformReviewReplyPort` + `UnavailablePlatformReviewReplyPort`：投递端点**恒 409**、状态停在 `PENDING`、**回执恒为空**；但每次尝试记进 `crm_review_reply_delivery`（`outcome=NOT_INTEGRATED`） |

**加粗句的四处物理落点**（这是本模块的判据所在）：

1. 规范**可配置且版本化** —— `crm_review_reply_policy` 逐平台 × 逐形态；生效区间**不允许重叠**
   （触发器 `guard_crm_review_reply_policy_window`），「当时生效的是哪一版」必须唯一。
2. 校验**真的拦得住** —— `ReplyWordingChecker`：长度（按 Unicode 码点）/ 导流联系方式 / 促销措辞 /
   外链 / 标点上限 / **差评必致歉** / 租户禁用词 / **质疑买家（不可关闭）**；
   **不合规的模板存不进来、不合规的回复发不出去**（400，一行都不写）。
3. **差评必致歉** —— 1-2 星回复必须带致歉表达（「转化机会」的那一半）。
4. **历史可追溯** —— 回复记录落 `template_id + template_version + template_key + template_locale + policy_version`。

## 关键 trade-off

- **规范放库而不是写死在代码里**。规范若是代码常量，平台改了政策就只能改代码发版，
  而「运营改一句『这个词不许出现』」要等一次发版 ⇒ 规范必须是可以配置的数据。
  代价是多一张表 + 一条 PUT 端点；换来的是平台政策变更不依赖发版。
  内置词表（质疑买家 / 促销措辞 / 导流词）仍是代码 —— 那是**语言层面的合规红线**，
  与租户、店铺、平台都无关，属于规则而不是数据。
- **措辞规范的存在 = 该平台该形态「已开通」**。没配 `(platform, reply_type)` 的规范 ⇒ 该形态
  直接 409。理由：未配置既意味着「还没开通这个形态」，也意味着「没法校验合规」，
  两种情况都**不能**默认放行（放行 = 在没有规范的情况下往公开平台发内容）。
- **409 在事务提交之后才抛**。投递端点需要「记下这次尝试」**并且**「明确答未接入」；
  在事务里抛异常会把刚记下的尝试一起回滚。所以服务层**不抛**，返回
  `available=false` + 原因，由控制器在 `access.run` 返回（事务已提交）之后再抛 409。
- **本模块不修改 15.2.1.1 的 `crm_product_review.replied`**。那列的语义是
  「**平台侧**是否已有卖家公开回复」；本模块的回复尚未投递到平台 ⇒ 平台侧仍然没有回复
  ⇒ 该列必须保持 false。把没发出去的回复算成「平台已回复」比不更新更糟。
  本模块自己的「有没有回复记录」由本模块的表回答。
- **零外键**（本仓既有约定：外键会打破同服务既有测试的 TRUNCATE）⇒ 跨表引用一律用触发器校验：
  回复的目标评价必须存在且**同店铺同平台**；引用的模板版本必须与模板本身一致且模板已生效；
  批次 / 投递记录的目标必须存在；已生效模板的正文与身份冻结；回复正文与目标评价建后不可改。
- **api-proxy 用独立的首 token `review-replies`**，不并进既有 `^reviews...` 的 alternation ——
  并进去会让本车道与并行车道的插入点被隔开，git 判冲突。

## 文件清单

**迁移（crm 服务）**
- `backend/services/crm/src/main/resources/db/migration/V298__crm_review_reply.sql` —— 六张新表 +
  五个序列 + 九条触发器 + 六组 RLS/授权；零 ALTER / 零 DROP / 零 FOREIGN KEY。

**后端（crm 服务）**
- `.../crm/ReviewReplyModels.java` —— 传输模型（输入 / 输出 / 词表）。
- `.../crm/ReplyWordingChecker.java` —— 措辞校验器（加粗句的执行者）。
- `.../crm/PlatformReviewReplyPort.java` + `.../crm/UnavailablePlatformReviewReplyPort.java` —— 投递接缝与「未接入」实现。
- `.../crm/ReviewReplyService.java` —— 规范 / 模板 / 回复 / 批量 四组业务。
- `.../crm/ReviewReplyController.java` —— 端点（命名空间 `/crm/review-replies`）。

**后端测试**
- `.../crm/ReviewReplyIntegrationTest.java`（15 例）
- `.../crm/ReviewReplyTenancyIntegrationTest.java`（4 例：租户隔离三条断言 + 六张表逐张验 RLS）

**契约（codegen 产物）**
- `packages/api-contracts/src/contracts/crm.ts`、`packages/api-contracts/src/crm.openapi.json`
  —— 由 `python3 tools/codegen/catalog-contracts.py --spec-dir backend/target/openapi` 生成，
  **只保留 crm 这一个服务的改动**（其余 5 个服务与 openapi.json 逐字未变）。

**前端**
- `apps/web/src/components/crm/review-reply-model.ts` —— 前端模型 + 可读名。
- `apps/web/src/components/crm/review-reply-workspace.tsx` —— 工作区（六个区块）。
- `apps/web/src/components/crm/review-reply-model.test.ts` —— 纯规则测试。
- `apps/web/src/app/crm/review-replies/page.tsx` —— 页面。
- `apps/web/src/app/api/crm/review-replies-allowlist.test.ts` —— 白名单双向断言。
- `apps/web/src/lib/api-proxy.ts` —— **新增一条** `services[]` 元素（锚点 = `crm/reviews` 那条之后）。

**台账**
- `docs/product/capability-blueprint/module-progress.md` —— **只改 15.2.1.2 那一行**（状态 + 证据两格）。

## 验证

- L0：`.mvnw -q compile` 通过；`pnpm typecheck` 3/3 任务通过；`pnpm lint` 通过。
- L1：`./mvnw -pl services/crm -am test -Dtest='ReviewReply*'` ⇒ **19 / 19 通过**
  （`ReviewReplyIntegrationTest` 15 + `ReviewReplyTenancyIntegrationTest` 4）。
- 契约：6 个服务的 `OpenApiContractTest` 全过（导出 6 份 spec），codegen 只改 crm。
- 前端：`pnpm vitest run apps/web/src/components/crm/review-reply-model.test.ts
  apps/web/src/app/api/crm/review-replies-allowlist.test.ts` ⇒ 21 / 21 通过。
- **api-proxy 全量 allowlist 回归**（改了共享白名单 ⇒ 必跑）：
  `pnpm vitest run apps/web/src/app/api` ⇒ **173 个文件 / 1000 个用例全过**。

## 后续待办

1. **平台评价回复适配器**（归模块 2，channel）：接上后本模块的投递端点自动放行，
   前端一行都不用改（端口就是那条接缝）。
2. **15.2.1.3 差评处理**：标记原因 / 判定申诉 / 联系买家解决 / 跟踪解决率。
   本模块**能对差评回复**，但不实现差评的专门处理流程 —— 两条线不要混。
3. **规范基线与平台官方政策的对齐**：内置词表目前是**本仓维护的保守基线**，
   不是任何平台的官方政策文本。接上平台适配器后，应按平台官方政策逐平台复核并收放。
4. **索评侧模板**（15.2.2.2 `crm_review_message_template`）与本模块的回复模板**刻意分表**：
   索评话术面向买家、评价回复面向公开平台，措辞红线完全不同。若将来要做「索评话术合规校验」，
   应在 15.2.2.2 侧另起，不复用本模块的表。
