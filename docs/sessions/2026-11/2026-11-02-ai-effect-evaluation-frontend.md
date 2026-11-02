# 2026-11-02: 18.4.3.2 效果评估 —— 前端工作区与集成缺口修正

## 摘要

补齐 18.4.3.2「效果评估」（卷 18 → 18.4 AI 治理 → 18.4.3 AI 使用与效果管理）的前端工作区：
`/ai/effects` 真实页面 + 口径模型 + 纯函数口径层 + api-proxy 白名单条目 + 前端测试，
并按平台服务既有约定把阈值端点由 `PUT` 改为 `POST`（浏览器侧代理不导出 PUT，原方案在浏览器里 405 ⇒ 功能等于没交付）。
后端（V430 迁移 + 16 个端点 + 权限码）与 OpenAPI 契约已在前两个 commit 落地，本轮未重做。

## 决策 / 结论

1. **前端只按后端真实形状建模**：16 个端点 ⇒ 13 条路径（`thresholds` 的 GET/PUT 共用一条、
   `adoptions` / `feedback` 的 GET/POST 共用一条），模型层逐条对齐，**未凭空设计**。
2. **比率全程不碰浮点**：后端 `NUMERIC` 十进制文本 ⇒ 前端用**字符串移位**算百分比
   （`Number(x) * 100` 会把 `0.07` 变成 `7.000000000000001`，而比率是**判定口径**，低于阈值就判低价值）。
   `null`（没有分母）与 `0`（真的是 0%）**必须分开**：前者显示「—」，绝不画成 0% 的柱子。
3. **阈值端点 `PUT` → `POST`**：`apps/web/src/app/api/platform/[...path]/route.ts` 刻意不导出 PUT
   （该文件属 18.1.1.1，**未改动**），挂 PUT 端点等于让浏览器根本调不到。改用 platform 服务
   既有的 upsert 约定（配额 / 模型 / 敏感策略都走 POST），语义是「同一条覆盖」而非「整份替换」——
   与阈值「留空沿用当前值」的局部更新语义一致。
4. **契约只提交 platform 两个文件**：codegen 对**未重建 spec** 的其余 8 个服务产生**纯键序抖动**
   （`reporting.ts` 甚至多出 `AffiliateMarketingAttribution*` 类型）⇒ 逐个还原，
   避免把别的车道的契约冲掉。`platform.openapi.json` 的实际变化只有 `put → post` 三行。
5. **白名单负向样本只取本模块自己的前缀**：`platform/ai/…` 是兄弟车道共用命名空间，
   拿兄弟的前缀当「未交付形状」是假红。`suggestions/{uuid}/disable` **刻意不放行** —— 本仓没有下线端点。
6. **只出建议，不执行下线**：页面与台账都如实写「建议 / 不下线」，不假装存在下线动作。

## 涉及文件

前端（新增）

- `apps/web/src/app/ai/effects/page.tsx` —— 页面入口
- `apps/web/src/components/ai/ai-effect-model.ts` —— 端点封装 + 口径纯函数（比率移位、枚举说法、四件套 / 四件事、表单校验）
- `apps/web/src/components/ai/ai-effect-workspace.tsx` —— 真实工作区（八个页签，三态齐全）
- `apps/web/src/components/ai/ai-effect-model.test.ts` —— 30 例
- `apps/web/src/app/api/platform/ai-effect-allowlist.test.ts` —— 4 例

前端（改动）

- `apps/web/src/lib/api-proxy.ts` —— 新增**一条独立** `platform/` 条目（`^ai/effects/…`）

后端（改动，只动本模块自己的文件）

- `backend/services/platform/src/main/java/com/starcon/platform/AiEffectEvaluationController.java` —— `@PutMapping` → `@PostMapping`
- `backend/services/platform/src/test/java/com/starcon/platform/AiEffectEvaluationIntegrationTest.java` —— 2 处调用

契约 / 台账 / 日志

- `packages/api-contracts/src/platform.openapi.json` —— codegen 产物（未手改）
- `docs/product/capability-blueprint/module-progress.md` —— 只改 18.4.3.2 那一行的「状态」「证据」两格
- `docs/sessions/2026-11/2026-11-02-ai-effect-evaluation-frontend.md` —— 本文件

## 关键 trade-off / 讨论

- **改自己的后端 vs 改共享代理路由**：加 `export const PUT = proxy` 到 platform 路由也能打通，
  但那个文件归 18.1.1.1 且明确注释「刻意不导出 PUT」，且会让**整个 platform 服务**都接受 PUT（影响面更大）。
  改自己模块的端点只动自己的文件 ⇒ 选后者。
- **前端本地校验 vs 只靠后端报错**：阈值与采纳 / 反馈表单在前端**先**按后端同口径校验（比率 0~1、版本号 ≥ 1、
  驳回必填原因、译文 / 预测必填产物 id、能力码字面量），后端仍保留权威校验 —— 前端只为「别让人白提交一次」。
- **codegen 全量跑还是只提交 platform**：codegen 会重写 9 个服务的契约文件。本轮只重建了 platform 的 spec，
  其余 8 个是陈旧快照 ⇒ 跑完逐个还原。全量重建 9 份 spec 是调度者在集成头上的事，本车道不做。

## 后续待办

1. 上游 18.4 的其它 AI 能力（对话助手 / 智能查询 / 异常诊断）未交付 ⇒ 用量台账里**还没有真实调用记录**
   ⇒ **采纳率的分母目前是 0、比率恒为 null**（页面如实显示「—」）。各能力接上后自行回报。
2. **助手答案这一维无法评估**（上游 18.4.1.1 已熔断未交付），本模块只有反馈登记载体。
3. 「建议下线」**没有执行路径** —— 真正下线需人工执行，待 18.4.3.1 补按能力粒度的功能开关。
4. 兄弟模块 18.3.3.1（自动审批）与 18.3.2.1（执行日志）的后端各有 `PUT` 端点，
   但浏览器侧 platform 代理不导出 PUT ⇒ 那两个端点**前端同样调不到**（本车道未改动，只记录）。

## 关联

- 蓝图：`docs/product/capability-blueprint/18-integration-and-automation.md`（18.4.3 AI 治理）
- 台账：`docs/product/capability-blueprint/module-progress.md`（18.4.3.2 行）
- 兄弟模块会话：`docs/sessions/2026-11/2026-11-02-ai-usage-quota.md`（18.4.3.1 使用与配额）
