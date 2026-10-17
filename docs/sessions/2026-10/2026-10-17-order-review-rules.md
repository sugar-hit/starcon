# 2026-10-17 9.2.1.2 审单规则（阶段 D4，trade 服务）

## 决策摘要

9.2.1.2「审单规则」在 trade 服务交付。核心不是「配几条规则」，而是把蓝图那句加粗的
**「规则命中结果要可解释：客服被买家投诉时能说出『为什么这单被拦了』」** 落成可查询的数据结构。

六张表：`order_review_rule`（优先级 + 生效期 + 强制转人工位）、`order_review_rule_condition`
（八维度 × 九运算符）、`order_review_buyer_profile`（等级 + 黑名单，租户自维护）、
`order_review_rule_evaluation`（判定结论 = 规则生效记录）、`order_review_rule_hit`（命中链，
**含未命中的规则**）、`order_review_rule_hit_condition`（逐条件到订单字段）。
零 ALTER 既有表，与并行 V150/V151/V152 零交集。

## 关键 trade-off

### 1. 「可解释」落成三层，且未命中项同样留痕

只落「本单被转人工」+ 一个布尔值，客服说不出任何理由。第三层
（`actual_field` / `actual_value` / `explanation`）是唯一能让客服把
「订单总额 200.0000 ≥ 阈值 100.0000」原话念给买家的东西。

代价：命中链会变长（每条参与判定的规则都留痕，含没命中的）。
**取舍理由**：客服被追问的第二个问题是「为什么那条规则没兜住」，
只记命中项时这个问题同样答不上来。

### 2. 强制转人工的效力高于优先级

`forced_manual` 位压过 `priority`。职责句的结构是「按 A/B/C 自动通过或转人工；
命中 X/Y/Z/W 的**强制**转人工」，两句话的效力本就不同。
实测：priority=999 的自动通过规则压不住 priority=1 的强制转人工规则。

### 3. 没有规则命中 = 转人工，且**刻意不做成租户开关**

与 9.2.1.1「批量通过必须基于明确的规则，而不是默认全过」同一立场。
做成可配置开关等于允许某个租户把「默认全过」打开 —— 那正是 9.2.1.1 加粗句点名要避免的事。
想要「小额单默认放行」的正确写法是一条显式 AUTO_APPROVE 规则。

### 4. 零条生效规则时给安全的 MANUAL_REVIEW 结论，而不是 409

初版写成 409「没有任何生效中的规则」，测试逼出后改了：「该租户此刻没有生效规则」
是真实且常见的运营状态（规则都过期了、还没配规则），报错会让 9.2.1.1 待审队列整批卡住，
而答案（转人工）是明确的、安全的。结论文案把「零条生效规则」与「有规则但都没命中」
分成两句话 —— 客服问「为什么这单被拦了」时这两种情况的答案不同。
`SELECTED` 口径下点名的规则查不到仍然 409（那是调用方的错误，含跨租户 id）。

### 5. 未登记买家画像 = 等级未知，不是 STANDARD

兜底一个 STANDARD 会让「VIP 自动通过」把全网买家都放过去。
`buyerLevel` 为 `null` 时 BUYER_LEVEL 条件**两个方向都判不满足**，不做乐观推断；
命中明细里 `actualValue` 是空串而不是编一个 `false`。

### 6. 两处上游缺口如实上报，不假装已支持（沿用 V144 同一手法）

- `sales_order` **没有 shop_code 列**，SHOP 维度当前只按平台级 `order_source` 判；
- trade 库里**没有买家主数据**，等级与黑名单改由本服务自维护（15 客户 / 16 合规未交付，
  且按模块边界纪律不得跨库读 `customer_party_id` 指向的 catalog 数据）。

两条都写进 `capabilities` 的 `available=false` + 交接说明，
D5 / 15 / 16 落地后表结构与判定逻辑都不用改。

### 7. 契约撞名改类型名，而不是往共享 allowlist 塞一条

codegen 报四个真撞名：catalog 已有 `RuleInput` / `RuleCondition` / `ReviewInput` / `Dimension`。
按脚本自己推荐的第一种改法给本模块加前缀（`ReviewRuleInput` / `ReviewRuleCondition` /
`ReviewRunInput` / `OrderReviewDimension`）。
**没有**动 `tools/codegen/contract-collision-allowlist.json` —— 那是多车道共用文件，
四条车道各自往里写会互相覆盖。

### 8. 运算符分三族，并加「维度与运算符必须匹配」的 DB CHECK

集合型（IN/NOT_IN）、数值型（GTE/GT/LTE/LT/EQ）、标记型（IS_TRUE/IS_FALSE）。
标记型存在的原因是黑名单是布尔量：让它挤进 EQ 就得在 Java 里把 true 翻译成 1，
那样命中明细会显示「1」而不是「在黑名单里」。
再加一条 CHECK 把「收货国家 ≥ 50」这类**永远判不出结果**的条件挡在库外 ——
那是最难查的一类故障：界面上看规则配得好好的，就是不命中。

## 文件清单

**迁移**
- `backend/services/trade/src/main/resources/db/migration/V149__trade_order_review_rules.sql`

**后端**
- `backend/services/trade/src/main/java/com/starcon/trade/OrderReviewRuleModels.java`
- `backend/services/trade/src/main/java/com/starcon/trade/OrderReviewRuleService.java`
- `backend/services/trade/src/main/java/com/starcon/trade/OrderReviewRuleController.java`

**测试**
- `backend/services/trade/src/test/java/com/starcon/trade/OrderReviewRuleIntegrationTest.java`（28 例）

**契约（codegen 产物，勿手改）**
- `packages/api-contracts/src/contracts/trade.ts`（纯新增）
- `packages/api-contracts/src/trade.openapi.json`

**前端**
- `apps/web/src/components/orders/order-review-rule-model.ts`
- `apps/web/src/components/orders/order-review-rule-model.test.ts`（23 例）
- `apps/web/src/components/orders/order-review-rule-workspace.tsx`
- `apps/web/src/app/orders/review/rules/page.tsx`
- `apps/web/src/app/api/trade/order-review-rule-allowlist.test.ts`（5 例）
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 services[] 元素**，锚点在既有 `trade/` 之前，未动那条超长 alternation）
- `packages/ui/src/lib/task-pages.ts`（**只新增 `/orders/review/rules` 一条**）

**台账**
- `docs/product/capability-blueprint/module-progress.md`（只改 9.2.1.2 那一行的「状态」「证据」两格）

## 验证

- L0：`./mvnw -q compile` 通过；`pnpm typecheck` 通过；`apps/web` 与 `packages/ui`
  另跑 `pnpm exec tsc --noEmit` 直出结果（typecheck 对这两个包报的是 cache hit，
  可能是别的 worktree 的缓存，不签字）；`pnpm lint` 通过。
- L1：`./mvnw -pl services/trade -am test` → **693 例全绿**（含本模块 28 例）。
- 前端：`pnpm vitest run`（本模块两个文件 + packages/ui）→ **70 例全绿**。

## 后续待办

1. **9.2.1.1 待审队列**（另一条车道）：按 `order_id` 读
   `order_review_rule_evaluation` 最近一条 `RECORDED` 判定，把命中链直接展示在
   待审列表的「命中规则」列上。交接方式已写进 capabilities。
2. **9.2.1.3 异常拦截**：本模块刻意不做自动拒单（职责句里没有，且它自己也明确
   「拦截不自动取消」）。拦截清单与处置建议属 9.2.1.3。
3. **18 自动化复用**：判定是纯函数式的（同一组订单事实 + 同一套规则 + 同一判定时刻 →
   同一结论与同一条命中链，无隐含外部状态），复用时按 `order_id` 取事实、按 tenant 取
   生效规则、调同一条判定即可，不需要把表结构搬走。
4. **D5 平台单同步**：`sales_order.shop_code` 到位后，SHOP 维度的条件取值改成店铺码。
5. **15 客户 / 16 合规**：由同步器补 `buyer_level` 与合规黑名单；`buyer_key` 口径不变，
   规则条件不用改。
