# 2026-10-16 渠道比价（10.1.2.2）

车道 `feat/10-1-2-2`，阶段 D5，L3 锚点 10.1.2「运费试算」。基线 `integration/module-dev@2df5d49c`。

## 决策摘要

- **模块职责照蓝图执行，不扩写**：在满足时效与限制的渠道中按成本 / 时效 / 成功率多目标排序，
  支持按店铺预设优先级自动选择；输入比价条件 + 渠道评分；输出排序后的推荐渠道 + 评分明细。
- **依赖自检先行**：开工 10 分钟内 grep 确认上游 12 张表（V136 / V139 / V140）与
  `FreightCalculatorController` 均在集成头里，**未触发 BLOCKED**。
- **不重算钱**：成本 / 时效 / 可承运性一律来自上游 10.1.2.1（引用已落库试算单，或走一次无副作用试算）。
  限制与费率规则各只有一份，本模块再写一份就意味着总有一份会漏掉「磁力」这类限制。
- **刻意不引 V139（时效与限制）**：比价用的时效是「本次报价预计几天到」（`freight_quote_line`），
  V139 描述的是「渠道对某国某重量段的承诺时效」，语义不同；引它等于拿承诺时效去筛这一票。

## 关键 trade-off

### 1. 蓝图加粗句「只看价格选渠道是常见错误」落到三个闸，而不是一句提示语

实现出来最省事的假活就是「按运费升序取第一个」。本车道把它落成**三个彼此独立的闸**，
阈值全部是 `channel_rating_score` 上**租户可维护的数据列**（不是代码常量）：

| 闸 | 列 | 拦什么 |
|---|---|---|
| 投诉率红线 | `max_complaint_rate_pct` | 便宜但高丢件 |
| 最小样本量 | `min_sample_threshold` | 3 单全到的「100% 成功率」 |
| 评分新鲜度 | `observed_at` + `max_rating_age_days` | 三个月前的评分 |

**关键取舍：三个闸只挡自动选择，不挡评分。** 被闸拦下的渠道**仍然出现在对照表里**并给出
完整三维得分与综合分。从表里删掉它，会让人以为「它不存在」，而它明明最便宜 ——
运营就再也查不出「为什么这条便宜的渠道没被选」。

### 2. 比率做成 DB 生成列，而不是服务层算

成功率 / 时效达成率 / 投诉率用 `GENERATED ALWAYS AS … STORED` 从原始计数派生。
代价是 INSERT 不能列这几列；收益是**服务层写不进去，绕过服务层写 SQL 也编不出与计数对不上的比率**。
计数是「人可核对的原始事实」，比率是派生量 —— 这个方向不能反。

### 3. 两个跨行不变量用 DEFERRABLE INITIALLY DEFERRED 约束触发器，而不是行级 BEFORE

初版写成行级触发器，被真实 PostgreSQL 打脸两次：

- **店铺预设连续性**：行级 BEFORE 在多行语句里会看到**中间态**（先插 3 再插 2、1 时第一行就误判），
  且 BEFORE 根本看不到「本行改完后」的最终集合。改成延迟到提交才看到最终态。
  配套 trigger 禁止改动生效行的店铺 / 渠道 —— 否则会把一条从旧店铺的链里悄悄搬走，
  而旧店铺的断号在延迟触发器里拿不到 shop_code，检查不到。
- **单头与明细自洽**：初版挂在单头表上，结果「单头先于明细落库」这件事本身变成非法
  （服务层必然是先写头再写行）。改挂**明细表**并延迟，两个写入顺序的毛病一起消失。

### 4. 不可比返回 null 而不是 0 分

0 分是「比过了，确实最差」，null 是「压根没资格比」。缺一维时**不 renormalize 掉**：
那等于把「没登记时效」解读成「它其它两维格外重要」，而这是调用方没有表达过的意思。
前端同样按此渲染（`scoreText(null) === '不可比'`）。

### 5. 综合分由 DB 在写入路径上重算

服务层传什么都不作数 ⇒ 界面上「分项加权和」必然等于「综合分」。
差 0.01 的分项与合计在人工复核时就会被当成对账纠纷。

## 文件清单

**迁移**
- `backend/services/channel/src/main/resources/db/migration/V143__channel_rate_comparison.sql`
  （4 表 + 5 触发器 + RLS + GRANT；版本号由调度器预分配）

**后端**
- `.../channel/RateComparisonModels.java`（传输模型）
- `.../channel/ChannelScoringEngine.java`（纯函数评分引擎，无 DB / 无策略常量 / 无 double）
- `.../channel/RateComparisonService.java`（jOOQ 显式 SQL + 编排 + 落库）
- `.../channel/RateComparisonController.java`（9 个端点，`/channel/rate-comparison/**`）

**测试**
- `.../channel/RateComparisonIntegrationTest.java`（28 例，Testcontainers PostgreSQL 16）
- `.../channel/ChannelScoringEngineTest.java`（16 例，纯单测）
- `apps/web/src/components/logistics/rate-comparison-model.test.ts`（30 例）
- `apps/web/src/app/api/channel/rate-comparison-allowlist.test.ts`（6 例）

**前端**
- `apps/web/src/app/logistics/rate-comparison/page.tsx`
- `apps/web/src/components/logistics/rate-comparison.tsx`（工作区）
- `apps/web/src/components/logistics/rate-comparison-model.ts`

**共用文件（允许并入，按类型名 / path / 模块 ID 求并）**
- `apps/web/src/lib/api-proxy.ts`（**新增一条独立 `rate-comparison/` 元素**，既有三条未动未重排）
- `packages/api-contracts/src/{channel.openapi.json,contracts/channel.ts}`（codegen 产物，纯新增）

**台账 / 会话**
- `docs/product/capability-blueprint/module-progress.md`（只改 10.1.2.2 那一行）
- `docs/sessions/2026-10/2026-10-16-channel-rate-comparison.md`（本文件）

## 顺带修掉的相邻缺陷（需调度器知悉）

- **`freight/quotes/{uuid}` 在 api-proxy 里一直没被放行**（10.1.2.1 的既有漏洞）。
  后端端点存在、契约里有，但原正则 `quotes` 后面没再接一段 uuid，于是「试算单详情」这一页
  在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
  本车道顺手补上（与比价单详情同属一类形状，留着会继续骗人）。

## 观察到的既有漂移（**未改**，留给调度器裁决）

集成头的 `packages/api-contracts/src/contracts/trade.ts` 相对 trade 服务当前 Java 源码是**过期**的：
缺 `BuyerNotificationTask*` / `CarrierRedirectRule*` / `WaybillCancel*` 等类型。
跑一次全量 codegen 会连带改写它。本车道只提交 channel 契约，trade 契约已 `git checkout` 还原，
避免踩到 9.3.1.2 合单那条车道。

## 后续待办

- **10.6.2.1 时效分析**（渠道评分的理想生产者）：上线后把 `score_source` 切到
  `TRANSIT_ANALYTICS` 即可回灌，**表结构与排序逻辑都不需要改**。当前只能人工登记。
- **9.3.2.1 分仓 / 18 自动化规则**：两者会把本模块落库的比价单当决策依据消费。
  本模块已把推荐结果落库且可引用，但下游尚未建规则，暂不会被自动消费。
- 这两项在 `capabilities` 里逐段 `available=false` + ownerModule + 影响，不用空列表或 0 冒充。
